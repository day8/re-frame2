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
            [re-frame.performance :as performance
             #?@(:cljs [:include-macros true])]
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
        parent-dispatch-id (when interop/debug-enabled? *current-dispatch-id*)
        ;; Per rf2-d4sf consult the `:adapter/current-frame` late-bind
        ;; hook on CLJS so dispatch picks up the React-context tier of
        ;; the resolution chain. Adapters publish the hook at ns-load
        ;; time; when unbound (JVM build, or no adapter ns loaded yet)
        ;; we fall back to `frame/current-frame` which honours the
        ;; dynamic var and `:rf/default` only.
        default-frame      #?(:cljs (if-let [f (late-bind/get-fn :adapter/current-frame)]
                                      (f)
                                      (frame/current-frame))
                              :clj  (frame/current-frame))
        explicit-frame?    (some? (:frame opts))
        ;; Per rf2-o8m0: capture whether resolution fell through the entire
        ;; chain (dynamic var unbound, adapter context unresolvable, no
        ;; explicit `:frame` opt) and landed on `:rf/default` purely as
        ;; the bottom-of-chain default. The warning surface in
        ;; `process-event*` uses this flag to discriminate "dispatch
        ;; explicitly targeted :rf/default" from "dispatch lost its
        ;; frame-context binding and silently slid to :rf/default." Dev-
        ;; only — like the rest of the trace surface, elided under
        ;; `interop/debug-enabled?` so production carries no allocation
        ;; for the warning detection.
        fallthrough?       (when interop/debug-enabled?
                             (and (not explicit-frame?)
                                  (nil? frame/*current-frame*)
                                  (= :rf/default default-frame)))]
    (cond-> {:event                  event
             :frame                  (or (:frame opts) default-frame)
             :fx-overrides           (:fx-overrides opts {})
             :interceptor-overrides  (:interceptor-overrides opts {})
             :trace-id               (:trace-id opts)
             :source                 (:source opts :ui)
             :origin                 (:origin opts :app)
             :dispatched-at          (interop/now-ms)}
      dispatch-id        (assoc :dispatch-id        dispatch-id)
      parent-dispatch-id (assoc :parent-dispatch-id parent-dispatch-id)
      fallthrough?       (assoc :fell-through-to-default? true))))

(defn- resolve-handler [event-id]
  (registrar/lookup :event event-id))

(defn- non-default-frame-registered?
  "True when at least one registered, non-destroyed frame other than
  `:rf/default` exists. The `:rf.warning/dispatch-from-async-callback-
  fell-through-to-default` warning is suppressed when this is false:
  single-frame apps cannot hit the footgun (the resolution chain has
  nowhere else to land), so emitting the warning would be noise rather
  than signal. Per rf2-o8m0."
  []
  (let [ids (frame/frame-ids)]
    (boolean (some (fn [k] (not= :rf/default k)) ids))))

(defn- emit-fallthrough-warning!
  "Per rf2-o8m0: dispatch landed on `:rf/default` purely because the
  resolution chain found nothing else (dynamic var unbound, adapter
  React-context unresolvable, no explicit `:frame` opt) AND the target
  handler does not exist on `:rf/default`. The user almost certainly
  wanted the dispatch to ride a non-default frame; the most common
  trigger is dispatching from an async callback (setTimeout,
  addEventListener, requestAnimationFrame, Promise.then) attached
  inside a view body, where the surrounding `*current-frame*` binding
  does not survive the async escape (per Spec 002 §Dispatches issued
  from inside a handler body).

  Suppressed when no non-default frame is registered — single-frame
  apps cannot hit the footgun.

  `:source-coord` is left to the existing `:rf.trace/trigger-handler`
  surface (rf2-3nn8): when a handler is in scope, `emit-error!` /
  `emit!` hoists the triggering handler's source-coord automatically.
  When no handler is in scope (the async-callback case the warning is
  primarily aimed at) `*current-trigger-handler*` is unbound and the
  field is omitted — `dispatch` is a fn, not a macro, so the call site
  cannot be stamped without changing the public API. Documented
  limitation; tools that need call-site attribution capture it
  externally."
  [envelope]
  (when (and (:fell-through-to-default? envelope)
             (non-default-frame-registered?))
    (let [event    (:event envelope)
          event-id (first event)
          reason   (str "Dispatch of `" event-id "` resolved to `:rf/default` "
                        "because no `:frame` was supplied and `*current-frame*` "
                        "was unbound, but no handler for that event is "
                        "registered on `:rf/default`. The dispatch most "
                        "likely originated from an async callback "
                        "(`setTimeout`, `addEventListener`, "
                        "`requestAnimationFrame`, `Promise.then`) attached "
                        "from inside a view body — the surrounding "
                        "frame-context binding does not survive the async "
                        "escape (per Spec 002 §Dispatches issued from "
                        "inside a handler body). Fixes (priority order): "
                        "(a) use `:dispatch-later` or a registered `reg-fx` "
                        "— both capture the frame in their closure; "
                        "(b) capture `(rf/bound-dispatcher)` inside the "
                        "render and call it from the callback; "
                        "(c) attach the listener from a Form-3 "
                        "`:component-did-mount` / `use-effect` hook so "
                        "the dispatcher is captured during render but "
                        "the listener runs after commit.")]
      (trace/emit! :warning
                   :rf.warning/dispatch-from-async-callback-fell-through-to-default
                   {:event        event
                    :event-id     event-id
                    :detected-at  (interop/now-ms)
                    :routed-to    :rf/default
                    :reason       reason
                    :recovery     :no-recovery}))))

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

(defn- validate-event!
  "Per Spec 010 §Validation order step 1 (rf2-jwm4): validate the
  dispatched event vector against the handler's :spec BEFORE the
  handler's interceptor chain runs. Failures emit
  :rf.error/schema-validation-failure with :where :event and skip the
  handler (recovery :no-recovery; downstream queue continues).

  Returns truthy when the handler should run, falsy when it should be
  skipped. Defaults to true when the schemas namespace hasn't been
  loaded."
  [event-id event handler-meta]
  (if-let [validate! (late-bind/get-fn :schemas/validate-event!)]
    (try (validate! event-id event handler-meta)
         (catch #?(:clj Throwable :cljs :default) _ true))
    true))

(defn- assemble-initial-ctx
  "Build the initial interceptor context per the standard shape. Envelope
  keys (:source :trace-id) are surfaced as cofx entries so handler bodies
  can read them. Per Spec 002 §Routing — the dispatch envelope."
  [envelope frame frame-record fx-overrides]
  (let [event     (:event envelope)
        db-value  (frame/frame-app-db-value frame)]
    {:coeffects (cond-> {:db    db-value
                         :event event
                         :frame frame}
                  (:source envelope)   (assoc :source (:source envelope))
                  (:trace-id envelope) (assoc :trace-id (:trace-id envelope)))
     :effects {}
     :rf/fx-overrides fx-overrides}))

(defn- emit-handler-exception!
  "Surface an interceptor-chain exception as :rf.error/handler-exception
  trace. The chain captures the exception into `:rf/interceptor-error`
  rather than re-throwing (the drain must not abort); this helper
  translates that into the trace surface."
  [error event-id event frame]
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

(defn- run-post-commit-validation!
  "Per Spec 010: validate app-db against registered schemas after each
  commit. Failures emit :rf.error/schema-validation-failure but don't
  roll back (recovery is :no-recovery by default).

  Per Spec 010 §Per-frame schemas the validation walks the schemas
  registered against THIS dispatch's frame only — sibling frames'
  schemas don't fire here."
  [db-after event-id frame]
  (when-let [validate (late-bind/get-fn :schemas/validate-app-db!)]
    (try
      (validate db-after event-id frame)
      (catch #?(:clj Throwable :cljs :default) _ nil))))

(defn- commit-db-effect!
  "Apply :db atomically: replace the app-db container, emit the
  :event/db-changed trace, then run post-commit schema validation."
  [effects event-id event frame]
  (when (contains? effects :db)
    (let [container (frame/get-frame-db frame)]
      (adapter/replace-container! container (:db effects)))
    (trace/emit! :event :event/db-changed
                 {:event-id event-id :event event :frame frame})
    (run-post-commit-validation! (:db effects) event-id frame)))

(defn- run-flows!
  "Per Spec 013 §Drain integration: run flows after :db commits and
  before :fx walks. Flow evaluation exceptions surface as
  :rf.error/flow-eval-exception traces; the drain continues."
  [frame event]
  (when-let [flows-fn (late-bind/get-fn :flows/run-flows!)]
    (try
      (flows-fn frame)
      (catch #?(:clj Throwable :cljs :default) e
        (trace/emit-error! :rf.error/flow-eval-exception
                           {:frame frame :event event :exception e})))))

(defn- run-fx-effects!
  "Walk :fx in source order, threading fx-overrides through so per-frame
  / per-call overrides take effect. Per-frame :platform overrides
  interop/platform when set."
  [effects frame frame-record fx-overrides event]
  (when-let [fx-vec (:fx effects)]
    (let [active-platform (or (get-in frame-record [:config :platform])
                              interop/platform)]
      (fx/do-fx frame fx-vec active-platform fx-overrides event))))

(defn- process-event*
  "Per-event drain body. Resolve handler, validate the event vector, run
  the interceptor chain, then commit :db, run flows, and walk :fx in
  source order. Per Spec 002 §Drain-loop pseudocode.

  This is the inner of `process-event!`; the outer wraps it in a binding
  of *current-dispatch-id* so child dispatches issued from within fx
  handlers inherit the in-flight dispatch's id as their
  :parent-dispatch-id (Spec 009 §Dispatch correlation)."
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
          (do
            ;; Per rf2-o8m0: when a dispatch lands on `:rf/default` purely
            ;; because resolution fell through (no `:frame` opt, dynamic
            ;; var unbound, adapter context unresolvable) AND the handler
            ;; is missing from `:rf/default`, the user-supplied event
            ;; almost certainly belongs to a different frame and the
            ;; dispatch lost its frame-context binding mid-flight
            ;; (typically: an async callback attached inside a view
            ;; body). Emit the warning ahead of the
            ;; `:rf.error/no-such-handler` error so consumers see the
            ;; specific diagnostic; the error fires too, preserving the
            ;; existing handler-missing trace contract.
            (emit-fallthrough-warning! envelope)
            (trace/emit-error! :rf.error/no-such-handler
                               {:event-id event-id
                                :event    event
                                :frame    frame
                                :kind     :event
                                :recovery :replaced-with-default}))

          :else
          ;; Per rf2-3nn8: bind `*current-trigger-handler*` for the
          ;; duration of this event's interceptor chain + post-chain
          ;; phases (db commit, flows, fx walk) so every error trace
          ;; emitted within the scope carries the triggering handler's
          ;; source-coord. The trace/trigger-handler-from-meta helper
          ;; returns nil when no source-coord was stamped (programmatic
          ;; registration / REPL eval); the binding is then nil and
          ;; the field is omitted from any emitted error event.
          (binding [trace/*current-trigger-handler*
                    (trace/trigger-handler-from-meta :event event-id handler-meta)]
            (let [_            (trace/emit! :event :event
                                            {:event-id event-id
                                             :event    event
                                             :frame    frame
                                             :source   (:source envelope)
                                             :trace-id (:trace-id envelope)
                                             :phase    :run-start})
                  event-ok?    (validate-event! event-id event handler-meta)
                  {:keys [extra-interceptors fx-overrides]} (apply-overrides envelope frame-record)
                  full-chain   (vec (concat extra-interceptors (:interceptors handler-meta)))
                  initial-ctx  (assemble-initial-ctx envelope frame frame-record fx-overrides)
                  ;; Per Spec 010 §Per-step recovery step 1: when event-payload
                  ;; validation failed the handler is not invoked; the
                  ;; downstream queue continues.
                  ;;
                  ;; Per Spec 009 §Performance instrumentation (rf2-du3i):
                  ;; bracket the handler invocation in performance marks so
                  ;; prod builds with the perf flag enabled produce a
                  ;; `rf:event:<event-id>` measure entry. Default-off; under
                  ;; `:advanced` + `re-frame.performance/enabled?=false` the
                  ;; bracket DCEs and the call collapses to the chain run.
                  final-ctx    (if event-ok?
                                 (performance/mark-and-measure :event event-id
                                   (interceptor/execute-chain full-chain initial-ctx))
                                 initial-ctx)
                  effects      (:effects final-ctx)
                  error        (:rf/interceptor-error final-ctx)]
              (when error
                (emit-handler-exception! error event-id event frame))
              (commit-db-effect! effects event-id event frame)
              (run-flows! frame event)
              (run-fx-effects! effects frame frame-record fx-overrides event)
              (trace/emit! :event :event
                           {:event-id event-id
                            :event    event
                            :frame    frame
                            :phase    :run-end}))))))))

(defn- process-event!
  "Wrap process-event* in two dynamic bindings:

   1. `*current-dispatch-id*` — so child dispatches issued from within
      an fx handler inherit this event's `:dispatch-id` as their
      `:parent-dispatch-id`. Per Spec 009 §Dispatch correlation.

   2. `frame/*current-frame*` — bound to the envelope's `:frame` for
      the duration of the handler chain. Per Spec 002 §Dispatch
      resolution chain — the dynamic-var tier of the resolution chain
      MUST cover the in-flight handler body so a synchronous
      `(rf/dispatch ...)` / `(rf/subscribe ...)` from inside the
      handler routes to the handler's own frame (not `:rf/default`).
      Without this binding, the handler body would see the same
      `*current-frame*` value the original dispatcher saw — typically
      `nil` for app-level dispatches — and child dispatches would
      slide to `:rf/default`, silently breaking multi-frame isolation.

      The binding does NOT survive async escapes (setTimeout,
      Promise.then, requestAnimationFrame): the JS callback fires on
      a fresh stack with no dynamic binding. Use `(rf/bound-dispatcher)`
      (capture-at-call-time), `:fx [[:dispatch ...]]` (fx-walker
      threads the frame), or `:dispatch-later` (frame captured in
      closure) for those paths. Per rf2-l5q3."
  [envelope]
  (binding [*current-dispatch-id*  (:dispatch-id envelope)
            frame/*current-frame*  (:frame envelope)]
    (process-event* envelope)))

(def ^:private drain-depth-default 100)

(defn- handle-depth-exceeded!
  "Tail-path for the depth-limit branch of `drain!`. Atomic rollback per
  Spec 002 §Run-to-completion §Rules rule 3: restore the pre-drain
  `:db` BEFORE emitting the error so any trace listener that reads
  app-db sees the rolled-back value, not a misleading partial cascade.

  Per Tool-Pair §Time-travel: a depth-exceeded drain is a *partial*
  drain — discard the in-flight capture buffer rather than emit a
  misleading epoch record."
  [frame-id router db-before depth last-event]
  (let [{:keys [queue]} @router
        container (frame/get-frame-db frame-id)]
    (when container
      (adapter/replace-container! container db-before))
    (trace/emit-error! :rf.error/drain-depth-exceeded
                       {:frame      frame-id
                        :depth      depth
                        :queue-size (count queue)
                        :last-event last-event
                        :rollback?  true
                        :recovery   :no-recovery})
    (swap! router assoc :queue interop/empty-queue :scheduled? false)
    (when-let [discard! (late-bind/get-fn :epoch/discard-buffer!)]
      (discard! frame-id))))

(defn- handle-drain-settled!
  "Tail-path for the empty-queue branch of `drain!`. Clear `:scheduled?`
  and, if at least one event was processed, commit the epoch record
  per Tool-Pair §Time-travel."
  [frame-id router db-before depth]
  (swap! router assoc :scheduled? false)
  (when (pos? depth)
    (when-let [settle! (late-bind/get-fn :epoch/settle!)]
      (let [db-after (frame/frame-app-db-value frame-id)]
        (settle! frame-id db-before db-after)))))

(defn- drain!
  "Outer drain loop for a single frame. Repeatedly dequeue and process
  until the queue is empty. Bounded by :drain-depth (default 100; per
  Spec 002 §Run-to-completion §Rules).

  Sets :in-drain? on the router state for the duration of the loop so
  reentrant dispatch-sync! detects 'we're already in a drain' and
  refuses (regardless of whether the outer drain came from
  dispatch-sync or async dispatch).

  Two tail-paths are extracted as named helpers:

    `handle-depth-exceeded!` — when the depth limit is hit, restore
    `app-db` to the pre-drain snapshot (atomic rollback per Spec 002
    §Run-to-completion §Rules rule 3), then emit
    :rf.error/drain-depth-exceeded, then halt (the remaining queued
    events are discarded). Rationale: re-frame's general 'events are
    atomic' principle extends to the drain — a depth-exceeded drain
    composes many events whose collective effect on `:db` is a partial
    cascade; preserving that partial cascade leaves callers observing
    a state no single event produced. Rolling back to `db-before` is
    the same discipline applied to the partial-drain boundary.

    `handle-drain-settled!` — when the queue empties, clear `:scheduled?`
    and commit the epoch record per Tool-Pair §Time-travel. The settle!
    / discard-buffer! hooks are looked up through late-bind so this
    namespace stays free of a require on re-frame.epoch."
  [frame-id]
  (let [frame-record (frame/frame frame-id)]
    (when frame-record
      (let [router       (:router frame-record)
            drain-depth  (get-in frame-record [:config :drain-depth] drain-depth-default)
            ;; Per Tool-Pair §Time-travel: snapshot `:db-before` at the
            ;; instant the drain begins so the eventual `:rf/epoch-record`
            ;; can carry the pre-cascade state regardless of how many
            ;; intermediate handlers commit `:db`. Per Spec 002 §Run-to-
            ;; completion §Rules rule 3, the same snapshot is the
            ;; rollback target if the drain trips the depth limit.
            db-before    (frame/frame-app-db-value frame-id)]
        (swap! router assoc :in-drain? true)
        (try
          (loop [depth      0
                 last-event nil]
            (cond
              (>= depth drain-depth)
              (handle-depth-exceeded! frame-id router db-before depth last-event)

              :else
              (let [{:keys [queue]} @router]
                (if (empty? queue)
                  (handle-drain-settled! frame-id router db-before depth)
                  (let [envelope (peek queue)]
                    (swap! router update :queue pop)
                    (process-event! envelope)
                    (recur (inc depth) (:event envelope)))))))
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
