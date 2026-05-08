(ns re-frame.router
  "Per-frame FIFO router and the drain loop. Per Spec 002 §Run-to-completion
  dispatch (drain semantics) and §Drain-loop pseudocode.

  The router maintains a per-frame FIFO queue. Dispatch appends to the
  back; the drain loop dequeues, runs the handler, applies effects, and
  loops until the queue empties. Run-to-completion is locked: every event
  dispatched synchronously during a drain settles to fixed point before
  any further external event is processed for that frame, and before any
  view re-renders."
  (:require [re-frame.frame :as frame]
            [re-frame.registrar :as registrar]
            [re-frame.interceptor :as interceptor]
            [re-frame.fx :as fx]
            [re-frame.substrate.adapter :as adapter]
            [re-frame.interop :as interop]
            [re-frame.late-bind :as late-bind]
            [re-frame.trace :as trace]))

;; ---- dispatch-id allocation -----------------------------------------------
;;
;; Per Spec 009 §Dispatch correlation: every dispatch is stamped with a
;; process-monotonic :dispatch-id at queue time. When the dispatch is
;; emitted as a side-effect of another event's processing (typically inside
;; an fx handler running in do-fx), the new dispatch's :parent-dispatch-id
;; is the in-flight event's :dispatch-id. *current-dispatch-id* tracks the
;; in-flight dispatch during process-event!; child dispatches read it to
;; populate :parent-dispatch-id.
;;
;; All of this rides the dev-only trace surface; production builds (where
;; interop/debug-enabled? is false at compile time) elide the allocation
;; (the counter increments harmlessly but the values are never read by
;; anyone except trace consumers, which are themselves dead).

(defonce ^:private dispatch-counter (atom 0))

(defn- next-dispatch-id []
  (swap! dispatch-counter inc))

(def ^:dynamic ^:private *current-dispatch-id* nil)

;; ---- envelope construction ------------------------------------------------

(defn- build-envelope
  "Build the dispatch envelope per Spec 002 §Routing: the dispatch envelope.
  The envelope carries:
    :event              the user-facing event vector
    :frame              resolved frame keyword (caller-supplied via opts,
                        else the active *current-frame*, else :rf/default)
    :fx-overrides       per-call fx-id-to-fx-id remapping
    :interceptor-overrides
    :trace-id           tooling
    :source             :ui :timer :http :repl :machine ...
    :origin             actor identity tag (:app default; :pair, :story,
                        :test, ... per Spec 002 §Dispatch origin tagging)
    :dispatch-id        process-monotonic id allocated here per
                        Spec 009 §Dispatch correlation
    :parent-dispatch-id the in-flight dispatch's id when this dispatch is
                        emitted from inside another event's processing"
  [event opts]
  (let [dispatch-id        (when interop/debug-enabled? (next-dispatch-id))
        parent-dispatch-id (when interop/debug-enabled? *current-dispatch-id*)]
    (cond-> {:event                  event
             :frame                  (or (:frame opts) (frame/current-frame))
             :fx-overrides           (:fx-overrides opts {})
             :interceptor-overrides  (:interceptor-overrides opts {})
             :trace-id               (:trace-id opts)
             :source                 (:source opts :ui)
             :origin                 (:origin opts :app)
             :dispatched-at          (interop/now-ms)}
      dispatch-id        (assoc :dispatch-id        dispatch-id)
      parent-dispatch-id (assoc :parent-dispatch-id parent-dispatch-id))))

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

(defn- process-event*
  "Per-event drain body. Resolve handler, run interceptor chain, apply :db,
  walk :fx in source order. Per Spec 002 §Drain-loop pseudocode.

  This is the inner of `process-event!`; the outer wraps it in a binding of
  *current-dispatch-id* so child dispatches issued from within fx handlers
  inherit the in-flight dispatch's id as their :parent-dispatch-id (Spec
  009 §Dispatch correlation)."
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
                ;; Per Spec 010 §Validation order step 1: validate the
                ;; dispatched event vector against the handler's :spec
                ;; BEFORE the handler's interceptor chain runs. Failures
                ;; emit :rf.error/schema-validation-failure with
                ;; :where :event and skip the handler (recovery
                ;; :no-recovery; downstream queue continues). Tracked
                ;; as rf2-jwm4.
                event-ok?
                (if-let [validate-event! (late-bind/get-fn :schemas/validate-event!)]
                  (try (validate-event! event-id event handler-meta)
                       (catch #?(:clj Throwable :cljs :default) _ true))
                  true)
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
                ;; If event-payload validation failed, skip the handler
                ;; entirely. Per Spec 010 §Per-step recovery step 1:
                ;; "Handler is not invoked"; downstream queue continues.
                final-ctx    (if event-ok?
                               (interceptor/execute-chain full-chain initial-ctx)
                               initial-ctx)
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
                           {:event-id event-id :event event :frame frame})
              ;; Per Spec 010: validate app-db against registered schemas
              ;; after each commit. Failures emit
              ;; :rf.error/schema-validation-failure but don't roll back
              ;; (recovery is :no-recovery by default).
              (when-let [validate (late-bind/get-fn :schemas/validate-app-db!)]
                (try
                  (validate (:db effects) event-id)
                  (catch #?(:clj Throwable :cljs :default) _ nil))))
            ;; Run flows (per Spec 013 §Drain integration: after :db
            ;; commits and before :fx walks).
            (when-let [run-flows! (late-bind/get-fn :flows/run-flows!)]
              (try
                (run-flows! frame)
                (catch #?(:clj Throwable :cljs :default) e
                  (trace/emit-error! :rf.error/flow-eval-exception
                                     {:frame frame :event event :exception e}))))
            ;; Walk :fx in source order, threading fx-overrides through so
            ;; per-frame / per-call overrides take effect. Per-frame
            ;; :platform overrides interop/platform when set.
            (when-let [fx-vec (:fx effects)]
              (let [active-platform (or (get-in frame-record [:config :platform])
                                        interop/platform)]
                (fx/do-fx frame fx-vec active-platform
                          (or (:rf/fx-overrides initial-ctx) {})
                          event)))
            (trace/emit! :event :event
                         {:event-id event-id
                          :event    event
                          :frame    frame
                          :phase    :run-end})))))))

(defn- process-event!
  "Wrap process-event* in a binding of *current-dispatch-id* so child
  dispatches issued from within an fx handler inherit this event's
  :dispatch-id as their :parent-dispatch-id. Per Spec 009 §Dispatch
  correlation."
  [envelope]
  (binding [*current-dispatch-id* (:dispatch-id envelope)]
    (process-event* envelope)))

;; ---- outer drain ----------------------------------------------------------

(def ^:private drain-depth-default 100)

(defn- drain!
  "Outer drain loop for a single frame. Repeatedly dequeue and process
  until the queue is empty. Bounded by :drain-depth (default 100; per
  Spec 002 §Run-to-completion §Rules).

  Sets :in-drain? on the router state for the duration of the loop so
  reentrant dispatch-sync! detects 'we're already in a drain' and
  refuses (regardless of whether the outer drain came from
  dispatch-sync or async dispatch).

  When the depth limit is hit, restore `app-db` to the pre-drain
  snapshot (atomic rollback per Spec 002 §Run-to-completion §Rules
  rule 3), then emit :rf.error/drain-depth-exceeded with :depth,
  :queue-size, and :last-event tags, then halt (the remaining queued
  events are discarded). Rationale: re-frame's general 'events are
  atomic' principle extends to the drain — a depth-exceeded drain
  composes many events whose collective effect on `:db` is a partial
  cascade; preserving that partial cascade leaves callers observing a
  state no single event produced. Rolling back to `db-before` is the
  same discipline applied to the partial-drain boundary.

  Per Tool-Pair §Time-travel: every drain-settle (queue empty) appends
  an `:rf/epoch-record` to the frame's epoch history. Atomic — partial
  drains (e.g. depth-exceeded) discard the in-flight capture buffer
  rather than commit a misleading record. The settle! / discard-buffer!
  hooks are looked up through late-bind so this namespace stays free of
  a require on re-frame.epoch."
  [frame-id]
  (let [frame-record (frame/frame frame-id)]
    (when frame-record
      (let [router       (:router frame-record)
            drain-depth  (get-in frame-record [:config :drain-depth] drain-depth-default)
            last-event-a (atom nil)
            ;; Per Tool-Pair §Time-travel: snapshot `:db-before` at the
            ;; instant the drain begins so the eventual `:rf/epoch-record`
            ;; can carry the pre-cascade state regardless of how many
            ;; intermediate handlers commit `:db`. Per Spec 002 §Run-to-
            ;; completion §Rules rule 3, the same snapshot is the
            ;; rollback target if the drain trips the depth limit.
            db-before    (frame/frame-app-db-value frame-id)]
        (swap! router assoc :in-drain? true)
        (try
          (loop [depth 0]
            (cond
              (>= depth drain-depth)
              (let [{:keys [queue]} @router]
                ;; Atomic rollback: restore the pre-drain `:db` BEFORE
                ;; emitting the error so any trace listener that reads
                ;; app-db sees the rolled-back value, not a misleading
                ;; partial cascade. Per Spec 002 §Run-to-completion
                ;; §Rules rule 3.
                (let [container (frame/get-frame-db frame-id)]
                  (when container
                    (adapter/replace-container! container db-before)))
                (trace/emit-error! :rf.error/drain-depth-exceeded
                                   {:frame      frame-id
                                    :depth      depth
                                    :queue-size (count queue)
                                    :last-event @last-event-a
                                    :rollback?  true
                                    :recovery   :no-recovery})
                (swap! router assoc :queue interop/empty-queue :scheduled? false)
                ;; Per Tool-Pair §Time-travel: a depth-exceeded drain is
                ;; a *partial* drain — discard the in-flight capture
                ;; buffer rather than emit a misleading epoch record.
                (when-let [discard! (late-bind/get-fn :epoch/discard-buffer!)]
                  (discard! frame-id)))

              :else
              (let [{:keys [queue]} @router]
                (if (empty? queue)
                  (do
                    (swap! router assoc :scheduled? false)
                    ;; Drain settle: queue is empty after at least one
                    ;; processed event. Commit the epoch record.
                    (when (pos? depth)
                      (when-let [settle! (late-bind/get-fn :epoch/settle!)]
                        (let [db-after (frame/frame-app-db-value frame-id)]
                          (settle! frame-id db-before db-after)))))
                  (let [envelope (peek queue)]
                    (reset! last-event-a (:event envelope))
                    (swap! router update :queue pop)
                    (process-event! envelope)
                    (recur (inc depth)))))))
          (finally
            (swap! router assoc :in-drain? false)))))))

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

(defn- emit-dispatched-trace!
  "Emit the :event :event/dispatched trace event for this envelope. Per
  Spec 009 §Dispatch correlation, :dispatch-id and :parent-dispatch-id
  ride on :tags. Per Spec 002 §Dispatch origin tagging, :origin rides
  on :tags too. Spec elision is automatic — trace/emit! short-circuits
  when interop/debug-enabled? is false at compile time."
  [envelope sync?]
  (trace/emit! :event :event/dispatched
               (cond-> {:event    (:event envelope)
                        :frame    (:frame envelope)
                        :origin   (:origin envelope)
                        :source   (:source envelope)
                        :sync?    sync?}
                 (:dispatch-id envelope)
                 (assoc :dispatch-id (:dispatch-id envelope))
                 (:parent-dispatch-id envelope)
                 (assoc :parent-dispatch-id (:parent-dispatch-id envelope)))))

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
         (emit-dispatched-trace! envelope false)
         (swap! router update :queue conj envelope)
         (ensure-drain-scheduled! (:frame envelope) router)))
     nil)))

(defn dispatch-sync!
  "Bypass the queue scheduler and process this single event end-to-end
  immediately, then drain any synchronously-enqueued events to fixed
  point. Per Spec 002 §dispatch-sync: this is for outside-the-runtime
  callers (test setup, REPL). Calling from inside a handler raises
  :rf.error/dispatch-sync-in-handler — handler bodies should use
  dispatch (the queued form) instead.

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
     (cond
       (nil? frame-record)
       (trace/emit-error! :rf.error/frame-destroyed
                          {:frame (:frame envelope) :event event
                           :recovery :no-recovery})

       (let [r @(:router frame-record)]
         (or (:in-sync-drain? r) (:in-drain? r)))
       ;; Per Spec 002 §dispatch-sync: nesting dispatch-sync inside ANY
       ;; running drain (sync or async) is an error — the event would
       ;; interleave with the outer handler's run-to-completion.
       (trace/emit-error! :rf.error/dispatch-sync-in-handler
                          {:frame    (:frame envelope)
                           :event    event
                           :reason   "dispatch-sync called from inside a running drain. Use dispatch (the queued form) instead so the event runs after the current drain settles."
                           :recovery :no-recovery})

       :else
       (let [router (:router frame-record)]
         (emit-dispatched-trace! envelope true)
         ;; Put the seed at the front and mark scheduled to suppress async.
         ;; :in-sync-drain? lets a nested dispatch-sync detect the unsafe
         ;; call and refuse rather than silently interleave.
         (swap! router (fn [{:keys [queue] :as r}]
                         (assoc r
                                :queue (into interop/empty-queue
                                             (cons envelope queue))
                                :scheduled?     true
                                :in-sync-drain? true)))
         (try
           (drain! (:frame envelope))
           (finally
             (swap! router assoc :in-sync-drain? false)))))
     nil)))

;; ---- late-bind hook registration ------------------------------------------
;;
;; Other namespaces that load BEFORE this one (re-frame.frame for :on-create
;; / :on-destroy, re-frame.fx for :dispatch / :dispatch-later) need to call
;; into the router. They cannot `:require` this namespace without a cyclic
;; load order, so we publish our entry points through the late-bind hook
;; registry once this namespace is loaded. The hook keys are documented in
;; re-frame.late-bind.

(late-bind/set-fn! :router/dispatch!       dispatch!)
(late-bind/set-fn! :router/dispatch-sync!  dispatch-sync!)
