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
                                :source   (:source envelope)
                                :trace-id (:trace-id envelope)
                                :phase    :run-start})
                {:keys [extra-interceptors fx-overrides]} (apply-overrides envelope frame-record)
                base-chain   (:interceptors handler-meta)
                full-chain   (vec (concat extra-interceptors base-chain))
                ;; Build the initial context per the standard shape.
                ;; Envelope keys (:source :trace-id) are surfaced as cofx
                ;; entries so handler bodies can read them. Per Spec 002
                ;; §Routing — the dispatch envelope.
                db-value     (frame/frame-app-db-value frame)
                initial-ctx  {:coeffects (cond-> {:db    db-value
                                                  :event event
                                                  :frame frame}
                                          (:source envelope)   (assoc :source (:source envelope))
                                          (:trace-id envelope) (assoc :trace-id (:trace-id envelope)))
                              :effects {}
                              :rf/fx-overrides fx-overrides}
                final-ctx    (interceptor/execute-chain full-chain initial-ctx)
                effects      (:effects final-ctx)
                error        (:rf/interceptor-error final-ctx)]
            (when error
              (let [e (:exception error)
                    msg #?(:clj (.getMessage e) :cljs (.-message e))]
                (trace/emit-error! :rf.error/handler-exception
                                   {:event-id          event-id
                                    :event             event
                                    :frame             frame
                                    :failing-id        event-id
                                    :handler-id        event-id
                                    :phase             (:phase error)
                                    :exception         e
                                    :exception-message msg
                                    :reason            "Event handler threw."
                                    :recovery          :no-recovery})))
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
            ;; Walk :fx in source order, threading fx-overrides through so
            ;; per-frame / per-call overrides take effect.
            (when-let [fx-vec (:fx effects)]
              (fx/do-fx frame fx-vec interop/platform
                        (or (:rf/fx-overrides initial-ctx) {})))
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
  Spec 002 §Run-to-completion §Rules).

  When the depth limit is hit, emit :rf.error/drain-depth-exceeded with
  :depth, :queue-size, and :last-event tags, then halt (the remaining
  queued events are discarded)."
  [frame-id]
  (let [frame-record (frame/frame frame-id)]
    (when frame-record
      (let [router       (:router frame-record)
            drain-depth  (get-in frame-record [:config :drain-depth] drain-depth-default)
            last-event-a (atom nil)]
        (loop [depth 0]
          (cond
            (>= depth drain-depth)
            (let [{:keys [queue]} @router]
              (trace/emit-error! :rf.error/drain-depth-exceeded
                                 {:frame      frame-id
                                  :depth      depth
                                  :queue-size (count queue)
                                  :last-event @last-event-a
                                  :recovery   :no-recovery})
              (swap! router assoc :queue interop/empty-queue :scheduled? false))

            :else
            (let [{:keys [queue]} @router]
              (if (empty? queue)
                (swap! router assoc :scheduled? false)
                (let [envelope (peek queue)]
                  (reset! last-event-a (:event envelope))
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

  Implementation: the seed event is pushed at the FRONT of the queue
  and then the drain loop runs. Because the scheduled? flag is set to
  true before draining, any dispatch! calls inside the seed handler's
  :fx vector enqueue without scheduling an async drain — the sync drain
  picks them up. Counting the seed event as drain depth 0 keeps drain-
  depth limits behaving uniformly across sync and async dispatch."
  ([event] (dispatch-sync! event {}))
  ([event opts]
   (let [envelope     (build-envelope event opts)
         frame-record (frame/frame (:frame envelope))]
     (when frame-record
       (let [router (:router frame-record)]
         ;; Put the seed at the front and mark scheduled to suppress async.
         (swap! router (fn [{:keys [queue] :as r}]
                         (assoc r
                                :queue (into interop/empty-queue
                                             (cons envelope queue))
                                :scheduled? true)))
         (try
           (drain! (:frame envelope))
           (catch #?(:clj Throwable :cljs :default) e
             (swap! router assoc :scheduled? false)
             (throw e)))))
     nil)))
