(ns re-frame-2.router
  "Per-frame FIFO router and the drain loop. Per Spec 002 §Run-to-completion
  dispatch (drain semantics) and §Drain-loop pseudocode.

  The router maintains a per-frame FIFO queue. Dispatch appends to the
  back; the drain loop dequeues, runs the handler, applies effects, and
  loops until the queue empties. Run-to-completion is locked: every event
  dispatched synchronously during a drain settles to fixed point before
  any further external event is processed for that frame, and before any
  view re-renders."
  (:require [re-frame-2.frame :as frame]
            [re-frame-2.registrar :as registrar]
            [re-frame-2.interceptor :as interceptor]
            [re-frame-2.fx :as fx]
            [re-frame-2.substrate.adapter :as adapter]
            [re-frame-2.interop :as interop]
            [re-frame-2.trace :as trace]))

;; ---- envelope construction ------------------------------------------------

(defn- build-envelope
  "Build the dispatch envelope per Spec 002 §Routing: the dispatch envelope.
  The envelope carries:
    :event              the user-facing event vector
    :frame              resolved frame keyword (caller-supplied or default)
    :fx-overrides       per-call fx-id-to-fx-id remapping
    :interceptor-overrides
    :trace-id           tooling
    :source             :ui :timer :http :repl :machine ..."
  [event opts]
  {:event                  event
   :frame                  (:frame opts :rf/default)
   :fx-overrides           (:fx-overrides opts {})
   :interceptor-overrides  (:interceptor-overrides opts {})
   :trace-id               (:trace-id opts)
   :source                 (:source opts :ui)
   :dispatched-at          (interop/now-ms)})

;; ---- per-event drain ------------------------------------------------------

(defn- resolve-handler [event-id]
  (registrar/lookup :event event-id))

(defn- apply-overrides
  "Per Spec 002 §Per-frame and per-call overrides: per-frame and per-call
  override maps merge with per-call winning. Returns the effective
  interceptor list and fx-overrides map for this dispatch."
  [envelope frame-record]
  (let [frame-cfg            (:config frame-record)
        per-call-fx          (:fx-overrides envelope)
        per-frame-fx         (:fx-overrides frame-cfg {})
        per-call-interceptors  (:interceptor-overrides envelope)
        per-frame-interceptors (:interceptor-overrides frame-cfg {})]
    {:fx-overrides           (merge per-frame-fx per-call-fx)
     :interceptor-overrides  (merge per-frame-interceptors per-call-interceptors)
     :extra-interceptors     (vec (concat (:interceptors frame-cfg [])))}))

(defn- process-event!
  "Per-event drain. Resolve handler, run interceptor chain, apply :db,
  walk :fx in source order. Per Spec 002 §Drain-loop pseudocode."
  [envelope]
  (let [{:keys [event frame]} envelope
        event-id              (first event)
        frame-record          (frame/frame frame)]
    (cond
      (nil? frame-record)
      (trace/emit-error! :rf.error/frame-destroyed
                         {:frame frame :event event :reason :frame-destroyed})

      :else
      (let [handler-meta (resolve-handler event-id)]
        (cond
          (nil? handler-meta)
          (trace/emit-error! :rf.error/no-such-handler
                             {:event-id event-id
                              :event    event
                              :frame    frame
                              :kind     :event
                              :recovery :replaced-with-default})

          :else
          (let [_ (trace/emit! :event :event
                               {:event-id event-id
                                :event    event
                                :frame    frame
                                :phase    :run-start})
                {:keys [extra-interceptors]} (apply-overrides envelope frame-record)
                base-chain   (:interceptors handler-meta)
                full-chain   (vec (concat extra-interceptors base-chain))
                ;; Build the initial context per the standard shape.
                db-value     (frame/frame-app-db-value frame)
                initial-ctx  {:coeffects {:db    db-value
                                          :event event
                                          :frame frame
                                          :original-event event}
                              :effects {}}
                final-ctx    (interceptor/execute-chain full-chain initial-ctx)
                effects      (:effects final-ctx)
                error        (:rf/interceptor-error final-ctx)]
            (when error
              (trace/emit-error! :rf.error/handler-exception
                                 {:event-id event-id :event event :frame frame
                                  :phase   (:phase error)
                                  :exception (:exception error)
                                  :recovery :no-recovery}))
            ;; Apply :db atomically.
            (when (contains? effects :db)
              (let [container (frame/get-frame-db frame)]
                (adapter/replace-container! container (:db effects)))
              (trace/emit! :event :event/db-changed
                           {:event-id event-id :event event :frame frame}))
            ;; Run flows (per Spec 013 §Drain integration: after :db
            ;; commits and before :fx walks).
            (when-let [run-flows! (resolve 're-frame-2.flows/run-flows!)]
              (try
                ((deref run-flows!) frame)
                (catch #?(:clj Throwable :cljs :default) e
                  (trace/emit-error! :rf.error/flow-eval-exception
                                     {:frame frame :event event :exception e}))))
            ;; Walk :fx in source order.
            (when-let [fx-vec (:fx effects)]
              (fx/do-fx frame fx-vec interop/platform))
            (trace/emit! :event :event
                         {:event-id event-id
                          :event    event
                          :frame    frame
                          :phase    :run-end})))))))

;; ---- outer drain ----------------------------------------------------------

(def ^:private drain-depth-default 100)

(defn- drain!
  "Outer drain loop for a single frame. Repeatedly dequeue and process
  until the queue is empty. Bounded by :drain-depth (default 100; per
  Spec 002 §Run-to-completion §Rules)."
  [frame-id]
  (let [frame-record (frame/frame frame-id)]
    (when frame-record
      (let [router       (:router frame-record)
            drain-depth  (get-in frame-record [:config :drain-depth] drain-depth-default)]
        (loop [depth 0]
          (cond
            (> depth drain-depth)
            (do (trace/emit-error! :rf.error/drain-depth-exceeded
                                   {:frame frame-id :depth depth
                                    :recovery :no-recovery})
                (swap! router assoc :scheduled? false))

            :else
            (let [{:keys [queue]} @router]
              (if (empty? queue)
                (swap! router assoc :scheduled? false)
                (let [envelope (peek queue)]
                  (swap! router update :queue pop)
                  (process-event! envelope)
                  (recur (inc depth)))))))))))

(defn- ensure-drain-scheduled!
  [frame-id router]
  (let [should-schedule?
        (locking router
          (let [{:keys [scheduled?]} @router]
            (if scheduled?
              false
              (do (swap! router assoc :scheduled? true)
                  true))))]
    (when should-schedule?
      (interop/next-tick (fn [] (drain! frame-id))))))

;; ---- public dispatch ------------------------------------------------------

(defn dispatch!
  "Append the event to the target frame's router queue. Per Spec 002:
  FIFO at the runtime layer; no reordering, no priority lanes. The drain
  loop picks it up in this same drain cycle (run-to-completion)."
  ([event] (dispatch! event {}))
  ([event opts]
   (let [envelope     (build-envelope event opts)
         frame-record (frame/frame (:frame envelope))]
     (cond
       (nil? frame-record)
       (trace/emit-error! :rf.error/frame-destroyed
                          {:frame (:frame envelope) :event event})

       :else
       (let [router (:router frame-record)]
         (swap! router update :queue conj envelope)
         (ensure-drain-scheduled! (:frame envelope) router)))
     nil)))

(defn dispatch-sync!
  "Bypass the queue scheduler and process this single event end-to-end
  immediately, then drain any synchronously-enqueued events to fixed
  point. Per Spec 002 §dispatch-sync: this is for outside-the-runtime
  callers (test setup, REPL). Calling from inside a handler raises
  :rf.error/dispatch-sync-in-handler.

  After the seed event runs, the drain loop processes anything that
  landed in the queue via :fx [[:dispatch ev]] — so chained dispatches
  settle synchronously."
  ([event] (dispatch-sync! event {}))
  ([event opts]
   (let [envelope (build-envelope event opts)]
     (process-event! envelope)
     ;; Drain anything that the handler's :fx put on the router queue
     ;; (e.g. chained :dispatch fx). Per run-to-completion, we settle
     ;; before returning.
     (drain! (:frame envelope))
     nil)))
