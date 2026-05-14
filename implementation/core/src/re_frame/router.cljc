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
            [re-frame.error-emit :as error-emit]
            [re-frame.fx :as fx]
            [re-frame.substrate.adapter :as adapter]
            [re-frame.interop :as interop]
            [re-frame.late-bind :as late-bind]
            [re-frame.performance :as performance
             #?@(:cljs [:include-macros true])]
            [re-frame.privacy :as privacy]
            [re-frame.trace :as trace
             #?@(:cljs [:include-macros true])]))

#?(:clj (set! *warn-on-reflection* true))

;; ---- dispatch-id allocation -----------------------------------------------
;;
;; Per Spec 009 §Dispatch correlation: every dispatch is stamped with a
;; process-monotonic :dispatch-id at queue time. When the dispatch is
;; emitted as a side-effect of another event's processing (typically inside
;; an fx handler running in do-fx), the new dispatch's :parent-dispatch-id
;; is the in-flight event's :dispatch-id.
;;
;; The in-flight dispatch's id is tracked through
;; `re-frame.trace/*handler-scope*`'s `:dispatch-id` slot (per rf2-g6ih4 —
;; the scope-bundle Var lives in `trace` so `trace/emit!` can read it and
;; stamp every trace event emitted inside the cascade with the cascade-
;; wide id). `process-event!` binds the scope around the inner
;; `process-event*`; child dispatches read it both to populate
;; `:parent-dispatch-id` here AND to ride on every emit inside the
;; cascade.
;;
;; All of this rides the dev-only trace surface; production builds (where
;; interop/debug-enabled? is false at compile time) elide the allocation
;; (the counter increments harmlessly but the values are never read by
;; anyone except trace consumers, which are themselves dead).

(defonce ^:private dispatch-counter (atom 0))

(defn- next-dispatch-id []
  (swap! dispatch-counter inc))

;; ---- lexical-scope fx-override binding (rf2-5uwl) -------------------------
;;
;; Per the `rf/with-fx-overrides` macro (declared in re-frame.core) tests
;; bind this Var to a `{fx-id -> override}` map for the macro body's
;; lexical scope; `build-envelope` merges it into the per-call
;; `:fx-overrides` opt. Precedence: per-call opt > lexical
;; `*fx-overrides*` > per-frame `:fx-overrides` (the existing per-frame
;; merge stays in `apply-overrides` below).
;;
;; Plain map, not a per-frame map: the macro is a test-side ergonomic
;; aimed at "for THIS block of dispatches, swap these fx for stubs"; it
;; applies regardless of which frame each dispatch lands on. Tests that
;; need per-frame overrides keep using `make-frame`'s `:fx-overrides`
;; key (the per-frame tier).
(def ^:dynamic *fx-overrides* nil)

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
                        emitted from inside another event's processing
    :call-site          compile-time-captured invocation coord stamped by
                        the `dispatch` / `dispatch-sync` macro (rf2-ts1a).
                        nil for the fn-form path (`dispatch*` etc.) and
                        under `goog.DEBUG=false` advanced builds."
  [event opts]
  (let [dispatch-id        (when interop/debug-enabled? (next-dispatch-id))
        parent-dispatch-id (when interop/debug-enabled?
                             (some-> trace/*handler-scope* :dispatch-id))
        ;; Per rf2-ts1a: read the macro-stamped `:rf.trace/call-site`
        ;; only when interop/debug-enabled?. Wrap the read itself in
        ;; the gate so the closure compiler can DCE the keyword
        ;; reference under `:advanced` + `goog.DEBUG=false`. Without
        ;; this gate the `(:rf.trace/call-site opts)` keyword-as-fn
        ;; call survives even when the consuming `cond->` predicate
        ;; is dead, because the keyword's interned-string slot is
        ;; referenced syntactically.
        call-site          (when interop/debug-enabled?
                             (:rf.trace/call-site opts))
        ;; Per rf2-d4sf the 3-tier resolution chain (dynamic var →
        ;; React context → `:rf/default`) is single-sourced through
        ;; `frame/resolve-current-frame` (rf2-jj8xf) — the React-context
        ;; tier consults the `:adapter/current-frame` late-bind hook on
        ;; CLJS so dispatch picks it up; JVM and the no-adapter-loaded
        ;; case fall through to the dynamic-var → `:rf/default` chain.
        default-frame      (frame/resolve-current-frame)
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
             ;; Per rf2-5uwl: merge the lexical-scope `*fx-overrides*`
             ;; (bound by `rf/with-fx-overrides`) under the per-call opt so
             ;; the per-call opt wins on key collision. The per-frame
             ;; tier is still merged later inside `apply-overrides`.
             :fx-overrides           (merge *fx-overrides* (:fx-overrides opts {}))
             :interceptor-overrides  (:interceptor-overrides opts {})
             :trace-id               (:trace-id opts)
             :source                 (:source opts :ui)
             :origin                 (:origin opts :app)
             :dispatched-at          (interop/now-ms)}
      ;; Per rf2-ts1a: the macro form of `dispatch` / `dispatch-sync`
      ;; stamps an `:rf.trace/call-site` on the opts map. The read in
      ;; `call-site` above is gated on interop/debug-enabled? so this
      ;; branch and its keyword literal DCE under :advanced +
      ;; goog.DEBUG=false. fn-form callers (`dispatch*`) supply nil
      ;; and the key is omitted.
      call-site          (assoc :call-site         call-site)
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
  primarily aimed at) `*handler-scope*`'s `:trigger-handler` is nil and the
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
                        "(b) capture `(rf/dispatcher)` inside the "
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
  trace AND invoke the frame's `:on-error` policy fn through the
  always-on error-emit substrate (per rf2-hqbeh). The chain captures the
  exception into `:rf/interceptor-error` rather than re-throwing (the
  drain must not abort); this helper translates that into both delivery
  channels.

  Per Spec 009 §The `with-redacted` interceptor (line 1226): the
  `:tags :event` slot of `:rf.error/handler-exception` MUST honour
  `with-redacted` — if the failing handler declared redacted paths,
  the trace event carries the scrubbed event vector, not the
  unredacted one. `ctx` (the final interceptor context) carries the
  scrubbed form under `:rf/redacted-event` when `with-redacted`
  ran; we surface that here.

  Per rf2-hqbeh: the per-frame `:on-error` slot is a runtime error-
  recovery surface and MUST fire even when the trace surface is
  compile-time elided in CLJS production builds. Per rf2-bacs4: a
  corpus-wide listener registry runs in parallel for off-box
  observability shippers (Sentry / Honeybadger / Rollbar). We build
  the structured error-event map AND the tight error-record up-front,
  hand both to `error-emit/dispatch-on-error!` (always-on; survives
  `goog.DEBUG=false`), then forward to the dev-only
  `trace/emit-error!` for trace listeners and the retain-N buffer.
  The trace path enriches the emitted event with the cascade's
  `:dispatch-id` and the in-scope handler's source-coord; the
  always-on path delivers the same `:operation`/`:tags` body the
  policy fn expects PLUS the tight `:error/:event/:event-id/:frame/
  :time/:exception/:elapsed-ms` record to corpus-wide listeners."
  [error event-id event frame ctx start-ms]
  (let [e          (:exception error)
        msg        #?(:clj (.getMessage ^Throwable e) :cljs (.-message e))
        emit-event (or (:rf/redacted-event ctx) event)
        end-ms     (interop/now-ms)
        ;; Per rf2-bacs4 §Record shape: `:elapsed-ms` is an integer.
        ;; `interop/now-ms` returns a long on the JVM but a float on
        ;; CLJS (`js/performance.now()` carries sub-millisecond
        ;; precision). Round once at the substrate boundary so the
        ;; record's contract holds on both platforms (mirrors
        ;; rf2-ph8pa / rf2-rirbq).
        elapsed-ms (long (max 0 (- end-ms start-ms)))
        tags       {:event-id          event-id
                    :event             emit-event
                    :frame             frame
                    :failing-id        event-id
                    :handler-id        event-id
                    :phase             (:phase error)
                    :exception         e
                    :exception-message msg
                    :reason            "Event handler threw."
                    :recovery          :no-recovery}]
    ;; Always-on per rf2-hqbeh / rf2-bacs4: the `:on-error` policy fn
    ;; fires through the always-on substrate so production builds with
    ;; the trace surface elided still observe the error; in parallel,
    ;; every fn registered through `rf/register-error-emit-listener!`
    ;; receives the tight error-record. The synthesised error-event
    ;; matches the dev-side `:rf/trace-event` shape closely enough for
    ;; policy fns to discriminate on `:operation` / `:tags`. Trigger-
    ;; handler / dispatch-id enrichment is dev-only and not present
    ;; here — those ride the trace path below.
    (error-emit/dispatch-on-error!
      :rf.error/handler-exception
      emit-event
      event-id
      frame
      e
      elapsed-ms
      end-ms
      {:operation :rf.error/handler-exception
       :op-type   :error
       :tags      tags
       :recovery  :no-recovery})
    ;; Dev-side trace emission. Gated by `interop/debug-enabled?` inside
    ;; `trace/emit-error!`; DCEs to a no-op in CLJS prod builds.
    (trace/emit-error! :rf.error/handler-exception tags)))

(defn- run-post-commit-validation!
  "Per Spec 010 §Per-step recovery row 4 (rf2-wkxng / rf2-6m0se): validate
  app-db against registered schemas after each commit. Returns the
  validator's boolean conjunction — true when every registered schema
  for the frame conformed (or the schemas artefact isn't loaded / no
  validator is installed); false when at least one schema failed.

  Failures emit :rf.error/schema-validation-failure (one per failing
  entry) with `:rollback? true` and `:recovery :no-recovery` stamped
  in the tag — the caller restores the pre-handler app-db on a false
  return.

  Per Spec 010 §Per-frame schemas the validation walks the schemas
  registered against THIS dispatch's frame only — sibling frames'
  schemas don't fire here.

  Defensive truth-coercion: a host-thrown validator (e.g. a buggy
  user-supplied :schemas/set-schema-validator! fn) is caught and
  treated as `true` (no rollback) — the validator is failing on
  itself, not on a user schema, and a hard abort here would mask the
  actual app-db state from the rest of the cascade. Real schema
  failures route through the in-band false return."
  [db-after event-id frame]
  (if-let [validate (late-bind/get-fn :schemas/validate-app-db!)]
    (try
      ;; nil-coerce: pre-rf2-6m0se the fn returned nil on success.
      ;; Treat nil as true (don't roll back) so a hosted port that
      ;; still ships the older contract keeps working.
      (let [r (validate db-after event-id frame)]
        (if (nil? r) true r))
      (catch #?(:clj Throwable :cljs :default) _ true))
    true))

(defn- commit-db-effect!
  "Apply :db atomically: replace the app-db container, emit the
  :event/db-changed trace, then run post-commit schema validation.
  Returns true when the commit is durable (no :db effect, or all
  schemas conformed); false when post-commit validation rejected the
  new state and the container has been **rolled back** to the
  pre-handler value (per Spec 010 §Per-step recovery row 4 /
  rf2-wkxng / rf2-6m0se).

  On rollback, a second :event/db-changed trace is emitted for the
  restored state with `:phase :rollback` so listeners (subs, 10x,
  pair-tools) observe the post-rollback app-db without ambiguity —
  the trace stream's load-bearing ordering is `:db-changed (post-
  handler) → :rf.error/schema-validation-failure → :db-changed
  (post-rollback)`, mirroring the depth-exceeded pattern (the error
  trace fires AFTER the container is back at its pre-handler value,
  so a trace listener that reads app-db sees the rolled-back state).
  Subscriptions whose inputs changed re-fire on the second commit so
  the UI never settles on a non-conforming snapshot.

  Per Spec 009 §The `with-redacted` interceptor (line 1225): the
  `:event/db-changed` trace event MUST surface the scrubbed event
  vector under `:tags :event` when `with-redacted` ran on the
  handler's chain. The interceptor stashes `:rf/redacted-event`
  on the context; we read it through and emit the scrubbed form."
  [effects event-id event frame ctx db-before]
  (if (contains? effects :db)
    (let [container  (frame/get-frame-db frame)
          new-db     (:db effects)
          emit-event (or (:rf/redacted-event ctx) event)]
      (adapter/replace-container! container new-db)
      (trace/emit! :event :event/db-changed
                   {:event-id event-id :event emit-event :frame frame})
      (if (run-post-commit-validation! new-db event-id frame)
        true
        (do
          ;; Roll back: restore the pre-handler container value, then
          ;; emit a second :event/db-changed with :phase :rollback so
          ;; subs / listeners see the restored state on the trace
          ;; stream. The error trace from validate-app-db! has
          ;; already fired between the two commits — consumers see
          ;; (in order): forward commit, schema-failure error,
          ;; rollback commit.
          (adapter/replace-container! container db-before)
          (trace/emit! :event :event/db-changed
                       {:event-id event-id
                        :event    emit-event
                        :frame    frame
                        :phase    :rollback})
          false)))
    true))

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

;; ---- process-event* phases ------------------------------------------------
;;
;; `process-event*` decomposes into named phases per audit RT1 (rf2-mccjv).
;; Each phase owns one piece of the per-event cascade; the outer
;; `process-event*` is a thin driver that sequences them.
;;
;;   handle-frame-destroyed!     early-exit: emit :rf.error/frame-destroyed
;;                               when the frame record is gone (frame disposed
;;                               between enqueue and dispatch)
;;   handle-no-handler!          early-exit: emit fallthrough warning (when
;;                               applicable) plus :rf.error/no-such-handler
;;   prepare-handler-ctx         build the full interceptor chain + initial
;;                               context and the effective fx-overrides map;
;;                               returns a tight map consumed by run-chain
;;                               and commit-and-flow!
;;   run-chain                   execute the interceptor chain bracketed in
;;                               performance marks; skipped when event-payload
;;                               validation fails (per Spec 010 §Per-step
;;                               recovery step 1)
;;   commit-and-flow!            handler-exception emit (if any), :db commit,
;;                               flows, then walk :fx in source order
;;   emit-cascade-trailers!      :run-end trace + always-on event-emit fan-out
;;   run-handler-cascade!        sequence prepare → run → commit → trailers
;;                               under `trace/with-handler-scope`

(defn- handle-frame-destroyed!
  "Per Spec 002 §Run-to-completion: a frame disposed between enqueue and
  dispatch surfaces as `:rf.error/frame-destroyed`; the drain continues
  with the next envelope."
  [event frame]
  (trace/emit-error! :rf.error/frame-destroyed
                     {:frame frame :event event :reason :frame-destroyed}))

(defn- handle-no-handler!
  "Per rf2-o8m0: when a dispatch lands on `:rf/default` purely because
  resolution fell through (no `:frame` opt, dynamic var unbound, adapter
  context unresolvable) AND the handler is missing from `:rf/default`,
  the user-supplied event almost certainly belongs to a different frame
  and the dispatch lost its frame-context binding mid-flight (typically:
  an async callback attached inside a view body). Emit the warning ahead
  of the `:rf.error/no-such-handler` error so consumers see the specific
  diagnostic; the error fires too, preserving the existing handler-
  missing trace contract."
  [envelope event-id event frame]
  (emit-fallthrough-warning! envelope)
  (trace/emit-error! :rf.error/no-such-handler
                     {:event-id event-id
                      :event    event
                      :frame    frame
                      :kind     :event
                      :recovery :replaced-with-default}))

(defn- prepare-handler-ctx
  "Build the effective interceptor chain and initial context for a
  resolved handler. Merges per-frame + per-call overrides (Spec 002
  §Per-frame and per-call overrides) and threads them through the
  initial cofx map. Returns `{:full-chain :initial-ctx :fx-overrides}`."
  [envelope frame frame-record handler-meta]
  (let [{:keys [extra-interceptors fx-overrides]} (apply-overrides envelope frame-record)
        full-chain  (vec (concat extra-interceptors (:interceptors handler-meta)))
        initial-ctx (assemble-initial-ctx envelope frame frame-record fx-overrides)]
    {:full-chain   full-chain
     :initial-ctx  initial-ctx
     :fx-overrides fx-overrides}))

(defn- run-chain
  "Execute the interceptor chain bracketed in performance marks. When
  event-payload validation failed the handler is not invoked; per Spec
  010 §Per-step recovery step 1 the initial context is returned
  unchanged and the downstream queue continues.

  Per Spec 009 §Performance instrumentation (rf2-du3i): the
  `performance/mark-and-measure` bracket produces a
  `rf:event:<event-id>` measure entry under prod builds with the perf
  flag enabled. Default-off; under `:advanced` +
  `re-frame.performance/enabled?=false` the bracket DCEs and the call
  collapses to a plain `execute-chain` invocation."
  [event-id full-chain initial-ctx event-ok?]
  (if event-ok?
    (performance/mark-and-measure :event event-id
      (interceptor/execute-chain full-chain initial-ctx))
    initial-ctx))

(defn- commit-and-flow!
  "Settle the cascade: surface any chain exception, commit :db, run
  flows, then walk :fx in source order. Per Spec 002 §Drain-loop
  pseudocode. Trace ordering is load-bearing — :event/db-changed
  precedes flow evaluation, which precedes :fx walking.

  Per Spec 010 §Per-step recovery row 4 (rf2-wkxng / rf2-6m0se): a
  post-commit `:db` schema-validation failure rolls the container
  back to the pre-handler value AND treats the dispatch as failed —
  flows do NOT evaluate and `:fx` does NOT walk. The pre-handler db
  is read from `(get-in final-ctx [:coeffects :db])` (assemble-
  initial-ctx stamps it there). Downstream queued events still drain
  per run-to-completion (handled by `drain-loop!`'s outer pass)."
  [final-ctx event-id event frame frame-record fx-overrides start-ms]
  (let [effects   (:effects final-ctx)
        error     (:rf/interceptor-error final-ctx)
        db-before (get-in final-ctx [:coeffects :db])]
    (when error
      (emit-handler-exception! error event-id event frame final-ctx start-ms))
    (when (commit-db-effect! effects event-id event frame final-ctx db-before)
      (run-flows! frame event)
      (run-fx-effects! effects frame frame-record fx-overrides event))
    error))

(defn- emit-cascade-trailers!
  "Cascade-tail emissions: the dev-only `:run-end` trace then the
  always-on event-emit fan-out.

  Per rf2-rirbq: the event-emit substrate is ALWAYS-ON — it survives
  `:advanced` + `goog.DEBUG=false` while the trace surface above DCEs.
  Looked up through the late-bind hook table so the router carries no
  static dependency on `re-frame.event-emit`; when the event-emit
  namespace has not been loaded the hook is nil and the fan-out is a
  single nil-check. Per Spec 009 §Event-emit listener.

  Per rf2-rirbq §Record shape: `:elapsed-ms` is an integer.
  `interop/now-ms` returns a long on the JVM (`System/currentTimeMillis`)
  but a float on CLJS (`js/performance.now()` carries sub-millisecond
  precision). Round once at the substrate boundary so the record's
  contract holds on both platforms."
  [event-id event frame error start-ms]
  (trace/emit! :event :event
               {:event-id event-id
                :event    event
                :frame    frame
                :phase    :run-end})
  (when-let [emit-event! (late-bind/get-fn :event-emit/dispatch-on-event)]
    (let [end-ms     (interop/now-ms)
          elapsed-ms (long (max 0 (- end-ms start-ms)))]
      (emit-event! event
                   event-id
                   frame
                   end-ms
                   (if error :error :ok)
                   elapsed-ms))))

(defn- run-handler-cascade!
  "Sequence the four cascade phases under the handler's
  `trace/*handler-scope*` binding.

  Per rf2-ryri7: publish the event handler's HandlerScope —
  `:trigger-handler` (rf2-3nn8 error path / rf2-lf84g success path) so
  every trace emitted inside the cascade carries the triggering
  handler's source-coord; `:sensitive?` (rf2-isdwf) so emits inside the
  scope get a top-level `:sensitive? true` stamp per Spec 009 §Privacy;
  `:no-emit?` (rf2-qsjda) so trace emission short-circuits when the
  handler opts out. `:call-site` and `:dispatch-id` are inherited from
  the parent scope (bound by `process-event!` outer wrapper) per
  `inherit-scope`. Scope covers the interceptor chain, db commit, flows,
  and fx walk — covering :event/db-changed, :event/do-fx, :rf.fx/handled
  (the inner fx scope re-binds), :sub/run (sub recompute re-binds),
  :rf.error/* (every error emit inside the chain).

  Per rf2-rirbq: `start-ms` is captured at the very start of cascade
  execution (unconditional, single `now-ms` call per event) so the
  always-on event-emit substrate can report `:elapsed-ms` in its per-
  event record."
  [envelope event-id event frame frame-record handler-meta]
  (trace/with-handler-scope
    (trace/handler-scope-from-meta :event event-id handler-meta)
    (let [start-ms  (interop/now-ms)
          _         (trace/emit! :event :event
                                 {:event-id event-id
                                  :event    event
                                  :frame    frame
                                  :source   (:source envelope)
                                  :trace-id (:trace-id envelope)
                                  :phase    :run-start})
          event-ok? (validate-event! event-id event handler-meta)
          {:keys [full-chain initial-ctx fx-overrides]}
          (prepare-handler-ctx envelope frame frame-record handler-meta)
          final-ctx (run-chain event-id full-chain initial-ctx event-ok?)
          error     (commit-and-flow! final-ctx event-id event frame
                                      frame-record fx-overrides start-ms)]
      (emit-cascade-trailers! event-id event frame error start-ms))))

(defn- process-event*
  "Per-event drain body. Resolve handler, then sequence the four cascade
  phases under the handler-scope binding (see `run-handler-cascade!`).
  Per Spec 002 §Drain-loop pseudocode.

  This is the inner of `process-event!`; the outer wraps it in a
  `trace/*handler-scope*` binding (via `trace/with-dispatch-id+call-site`)
  so (a) child dispatches issued from within fx handlers inherit the
  in-flight dispatch's id as their `:parent-dispatch-id`, and (b) every
  trace event emitted inside the cascade carries the cascade's
  `:dispatch-id` under `:tags` (per Spec 009 §Dispatch correlation and
  rf2-g6ih4).

  Two early-exit branches precede the cascade: a destroyed frame and a
  missing handler. Both emit their respective error events and return
  without disturbing the queue — the drain continues with the next
  envelope."
  [envelope]
  (let [{:keys [event frame]} envelope
        event-id              (first event)
        frame-record          (frame/frame frame)]
    (cond
      (nil? frame-record)
      (handle-frame-destroyed! event frame)

      :else
      (let [handler-meta (resolve-handler event-id)]
        (if (nil? handler-meta)
          (handle-no-handler! envelope event-id event frame)
          (run-handler-cascade! envelope event-id event frame
                                frame-record handler-meta))))))

(defn- process-event!
  "Wrap process-event* in two dynamic bindings:

   1. `trace/*handler-scope*` — set with the cascade's `:dispatch-id`
      and the envelope's `:call-site`, inheriting the rest from parent.
      Per rf2-ryri7 (consolidation of the `:dispatch-id` slot per
      rf2-g6ih4 and the `:call-site` slot per rf2-ts1a) — child
      dispatches issued from within an fx handler inherit this event's
      `:dispatch-id` as their `:parent-dispatch-id`, every trace event
      emitted inside the cascade (sub runs, fx-handled, machine
      transitions, errors) rides the cascade's `:dispatch-id` under
      `:tags`, and any error emitted inside the chain attaches the
      call-site to the event as `:rf.trace/call-site` (nil for fn-form
      dispatch). Per Spec 009 §Dispatch correlation.

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
      a fresh stack with no dynamic binding. Use `(rf/dispatcher)`
      (capture-at-call-time), `:fx [[:dispatch ...]]` (fx-walker
      threads the frame), or `:dispatch-later` (frame captured in
      closure) for those paths. Per rf2-l5q3."
  [envelope]
  (trace/with-dispatch-id+call-site (:dispatch-id envelope) (:call-site envelope)
    (binding [frame/*current-frame* (:frame envelope)]
      (process-event* envelope))))

(def ^:private drain-depth-default
  ;; Deep enough for typical cascade depths; cheap atomic rollback per
  ;; Spec 002 §Run-to-completion rule 3 when exceeded.
  100)

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
  "Tail-path for the empty-queue branch of `drain!`. If at least one event
  was processed, commit the epoch record per Tool-Pair §Time-travel.

  Per rf2-ynk7 §single-drainer invariant: `:scheduled?` is no longer
  cleared here. The drain loop's outer `finally` clears `:scheduled?`
  AND releases `:drain-lock` under a single `locking router` block so
  any concurrent submitter's `ensure-drain-scheduled!` check serializes
  against the release. Splitting the two would re-open the
  enqueue-after-empty-check race that motivated this bead."
  [frame-id _router db-before depth]
  (when (pos? depth)
    (when-let [settle! (late-bind/get-fn :epoch/settle!)]
      (let [db-after (frame/frame-app-db-value frame-id)]
        (settle! frame-id db-before db-after)))))

;; ---- drain-loop! phases ---------------------------------------------------
;;
;; `drain-loop!` decomposes into five named phases per audit RT4 (rf2-hpkjg).
;; Each phase is a pure-ish helper that owns one piece of the lock-release
;; contract; the outer `drain-loop!` is now a thin driver that sequences them.
;;
;;   mark-drainer!         set `:in-drain?` to the current thread marker
;;   clear-drainer!        clear `:in-drain?` (finally-block partner)
;;   take-event!           peek+pop one envelope under the single-drainer
;;                         invariant (rf2-ynk7); returns nil on empty queue
;;   run-one-pass!         the inner loop body: process events to fixed
;;                         point or until depth limit; returns ::halt or
;;                         ::settled
;;   force-release-on-halt!  release the drain-lock after a ::halt outcome
;;                         (queue already drained by `handle-depth-exceeded!`)
;;   try-release-on-empty!   under lock, re-check queue; release both flags
;;                         on still-empty (returns false) or signal another
;;                         pass (returns true) — the orphan-prevention seam.

(defn- mark-drainer!
  "Stamp `:in-drain?` with this thread's marker so the dispatch-sync guard
  can distinguish same-thread nesting from a concurrent caller. Per
  rf2-ynk7. On CLJS — single-threaded — every check is necessarily
  same-thread, so `true` works as the marker."
  [router]
  (swap! router assoc :in-drain? #?(:clj (Thread/currentThread) :cljs true)))

(defn- clear-drainer!
  "Clear the `:in-drain?` marker. Paired with `mark-drainer!` in a
  try/finally to ensure the marker never outlives the pass."
  [router]
  (swap! router assoc :in-drain? nil))

(defn- take-event!
  "Atomic peek+pop of one envelope from the router queue. Returns the
  envelope or nil when the queue is empty.

  Per rf2-ynk7: with the single-drainer invariant held by `:drain-lock`,
  this peek+pop pair is atomic w.r.t. any other drain attempt. The
  pre-fix race (executor and main thread both peek the same envelope)
  cannot occur — the loser of the CAS in `drain-try!` / `drain-block!`
  never reaches this code."
  [router]
  (let [{:keys [queue]} @router]
    (when-not (empty? queue)
      (let [envelope (peek queue)]
        (swap! router update :queue pop)
        envelope))))

(defn- handle-drain-interrupted!
  "Per rf2-68kok / Spec 002 §Edge cases worth pinning §Frame disposal
  mid-drain: the drain-loop detected the frame was destroyed before
  the next dequeue. Drop the remaining queue ONCE, clear `:scheduled?`,
  and emit a single `:rf.frame/drain-interrupted` lifecycle trace
  carrying `:dropped-count` (per Spec 009 §`:rf.frame/drain-interrupted`
  and Spec-Schemas §DrainInterruptedTags).

  In-flight events are not affected — they have already been dequeued
  and `process-event!` ran them to completion before this check fires
  (run-to-completion per Spec 002 §Rules rule 1). Only events still
  in the queue at the moment of the check are dropped.

  The check fires AFTER `process-event!` returns and BEFORE the next
  `take-event!` — same seam as `handle-depth-exceeded!`."
  [frame-id router]
  (let [dropped (count (:queue @router))]
    (swap! router assoc :queue interop/empty-queue :scheduled? false)
    (trace/emit! :frame :rf.frame/drain-interrupted
                 {:frame         frame-id
                  :dropped-count dropped})))

(defn- run-one-pass!
  "Process events from the queue to fixed point or until `drain-depth` is
  exceeded. Returns `::settled` when the queue empties cleanly or
  `::halt` when the depth limit is reached OR the frame was destroyed
  mid-pass (the depth-exceeded / drain-interrupted handler has already
  cleared the queue and the `:scheduled?` flag in either halt case).

  Per rf2-68kok / Spec 002 §Frame disposal mid-drain: the destroyed-
  frame check fires BEFORE each dequeue, so an in-flight event runs to
  completion (run-to-completion per Spec 002 §Rules rule 1) but events
  still in the queue at the check point are dropped, with one
  `:rf.frame/drain-interrupted` lifecycle trace emitted carrying the
  dropped count.

  Each pass takes its pre-cascade `db-before` snapshot from the caller
  so the epoch settle callback (Tool-Pair §Time-travel) sees the right
  state for THIS pass."
  [frame-id router db-before drain-depth]
  (loop [depth      0
         last-event nil]
    (cond
      (>= depth drain-depth)
      (do (handle-depth-exceeded! frame-id router db-before depth last-event)
          ::halt)

      ;; Per rf2-68kok: destroyed-frame check fires BEFORE the next
      ;; dequeue. A handler in the just-completed event may have
      ;; called `destroy-frame!` on its own frame; the spec calls for
      ;; interrupting the drain at this exact seam — drop the
      ;; remaining queue, emit one `:rf.frame/drain-interrupted`
      ;; lifecycle event, halt. The just-completed event already ran
      ;; in full (run-to-completion). Skip the epoch settle on
      ;; interrupt — destroy-frame's `:epoch/on-frame-destroyed` hook
      ;; covers epoch cleanup and a half-cascade settle record would
      ;; mislead time-travel consumers.
      (frame/frame-disposed-for-drain? frame-id)
      (do (handle-drain-interrupted! frame-id router)
          ::halt)

      :else
      (if-let [envelope (take-event! router)]
        (do (process-event! envelope)
            (recur (inc depth) (:event envelope)))
        (do (handle-drain-settled! frame-id router db-before depth)
            ::settled)))))

(defn- force-release-on-halt!
  "Release the drain-lock after a `::halt` outcome. The depth-exceeded
  handler has already forcibly cleared the queue and set `:scheduled?`
  false, so we only need to drop the lock. Taken under `locking router`
  to serialize against `ensure-drain-scheduled!`'s flag-read."
  [router drain-lock]
  (locking router
    (reset! drain-lock false)))

(defn- try-release-on-empty!
  "Under the same lock that submitters take in `ensure-drain-scheduled!`,
  re-check the queue:

    * Empty  — clear `:scheduled?` AND release `:drain-lock` under one
               lock so a serialized submitter observes both flags false
               and schedules a fresh drain. Returns false (drainer is
               done).
    * Non-empty — a submitter enqueued between the inner empty-check
               and now. Leave both flags set and return true so the
               caller recurs into another pass.

  This is the orphan-prevention seam."
  [router drain-lock]
  (locking router
    (let [{:keys [queue]} @router]
      (if (empty? queue)
        (do (swap! router assoc :scheduled? false)
            (reset! drain-lock false)
            false)
        true))))

(defn- drain-loop!
  "The drain body proper. Assumes the caller holds `:drain-lock` (per
  rf2-ynk7 §single-drainer invariant) so this fn has exclusive access
  to the queue's peek+pop pair.

  Sequences three named phases per pass:

    1. mark-drainer! — stamp the in-drain marker (cleared in finally).
    2. run-one-pass! — process events to fixed point or depth-halt.
    3. force-release-on-halt! / try-release-on-empty! — outcome-specific
       release sequence under `locking router`.

  Outer loop re-enters whenever `try-release-on-empty!` reports a
  submitter raced in between the inner empty-check and the lock-protected
  release window. Each pass takes a fresh `db-before` snapshot so the
  epoch settle callback sees the right pre-cascade state for that pass."
  [frame-id router drain-lock drain-depth]
  (loop []
    (let [db-before (frame/frame-app-db-value frame-id)
          outcome   (try
                      (mark-drainer! router)
                      (run-one-pass! frame-id router db-before drain-depth)
                      (finally
                        (clear-drainer! router)))]
      (case outcome
        ::halt    (force-release-on-halt! router drain-lock)
        ::settled (when (try-release-on-empty! router drain-lock)
                    (recur))))))

(defn- drain-emergency-release!
  "Mid-drain panic path. An unhandled exception escaped `drain-loop!`
  past its own `finally` cleanup. Clear the router flags and release
  the drain-lock so the frame is not permanently stuck — then re-throw
  so the caller observes the failure."
  [router drain-lock]
  (locking router
    (swap! router assoc :scheduled? false :in-drain? nil)
    (reset! drain-lock false)))

(defn- drain-try!
  "Async drain entry point (called from `interop/next-tick`). CAS-tries
  the drain-lock; on lose, the active drainer holds the responsibility
  for the queue (its release block re-checks under lock — see
  drain-loop!). On win, runs the drain body and releases.

  Per rf2-ynk7 §single-drainer invariant."
  [frame-id]
  (let [frame-record (frame/frame frame-id)]
    (when frame-record
      (let [drain-lock  (:drain-lock frame-record)
            router      (:router frame-record)
            drain-depth (get-in frame-record [:config :drain-depth] drain-depth-default)]
        (when (compare-and-set! drain-lock false true)
          (try
            (drain-loop! frame-id router drain-lock drain-depth)
            (catch #?(:clj Throwable :cljs :default) t
              (drain-emergency-release! router drain-lock)
              (throw t))))))))

(defn- drain-block!
  "Synchronous drain entry point (called from `dispatch-sync!`). Unlike
  the async path, dispatch-sync's contract requires the cascade settle
  before return — so on CAS-loss this path BLOCKS (Thread/yield on JVM;
  trivially uncontended on CLJS) until the active drainer releases the
  lock, then runs `under-lock-fn` (typically the seed-push) and drains.

  Per rf2-ynk7 §single-drainer invariant: dispatch-sync's seed-push at
  the FRONT of the queue MUST happen while it holds the drain-lock —
  otherwise the prepend interleaves with the active drainer's peek+pop
  and produces the same race the drain-lock was introduced to fix
  (envelope A peek'd, B prepended, A popped becomes B, B processed as
  if it were A's pop result). The `under-lock-fn` callback shape lets
  the caller perform the seed-push inside the lock seam.

  `under-lock-fn` runs once, immediately after CAS-acquire, before the
  drain loop. Exceptions inside it propagate through the same emergency-
  release path as the drain loop body."
  [frame-id under-lock-fn]
  (let [frame-record (frame/frame frame-id)]
    (when frame-record
      (let [drain-lock  (:drain-lock frame-record)
            router      (:router frame-record)
            drain-depth (get-in frame-record [:config :drain-depth] drain-depth-default)]
        ;; Spin-CAS until we acquire. On JVM the active drainer holds
        ;; the lock for the duration of one drain pass — bounded by
        ;; drain-depth events at most — so the wait is bounded. CLJS
        ;; is single-threaded; the CAS succeeds on first attempt.
        (loop []
          (when-not (compare-and-set! drain-lock false true)
            #?(:clj (Thread/yield))
            (recur)))
        (try
          (under-lock-fn)
          (drain-loop! frame-id router drain-lock drain-depth)
          (catch #?(:clj Throwable :cljs :default) t
            (drain-emergency-release! router drain-lock)
            (throw t)))))))

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
      (interop/next-tick (fn [] (drain-try! frame-id))))))

(defn- emit-dispatched-trace!
  "Emit the :event :event/dispatched trace event for this envelope. Per
  Spec 009 §Dispatch correlation, :dispatch-id and :parent-dispatch-id
  ride on :tags. Per Spec 002 §Dispatch origin tagging, :origin rides
  on :tags too. Spec elision is automatic — trace/emit! short-circuits
  when interop/debug-enabled? is false at compile time.

  Per rf2-isdwf: `:event/dispatched` fires at queue/enqueue time —
  BEFORE the `*handler-scope*` binding's `:sensitive?` slot is
  established (the binding wraps `process-event*`, which runs later
  in the drain). Look up the target handler's registration metadata
  directly and pass `:sensitive?` in the tags so `emit!` hoists it
  to the top level. When the handler is missing the field is omitted
  (consumers treat absent as false).

  Per rf2-qsjda: same queue-time consideration applies for
  `:rf.trace/no-emit?`. The `*handler-scope*` binding's `:no-emit?`
  slot doesn't exist yet at enqueue time, so we read the flag
  directly off the target handler's registration meta and
  short-circuit the `:event/dispatched` emit when set. Without this,
  a Causa-style bookkeeping handler would have its enqueue trace
  delivered to listeners (re-entering the consumer's trace-cb)
  before the handler-scope binding ever took effect."
  [envelope sync?]
  (let [event        (:event envelope)
        event-id     (when (vector? event) (first event))
        handler-meta (when event-id (registrar/lookup :event event-id))
        sensitive?   (privacy/sensitive?-from-meta handler-meta)
        no-emit?     (trace/no-emit?-from-meta handler-meta)]
    (when-not no-emit?
      (trace/emit! :event :event/dispatched
                   (cond-> {:event    event
                            :frame    (:frame envelope)
                            :origin   (:origin envelope)
                            :source   (:source envelope)
                            :sync?    sync?}
                     sensitive?
                     (assoc :sensitive? true)
                     (:dispatch-id envelope)
                     (assoc :dispatch-id (:dispatch-id envelope))
                     (:parent-dispatch-id envelope)
                     (assoc :parent-dispatch-id (:parent-dispatch-id envelope)))))))

(defn dispatch!
  "Append the event to the target frame's router queue. Per Spec 002:
  FIFO at the runtime layer; no reordering, no priority lanes. The drain
  loop picks it up in this same drain cycle (run-to-completion).

  Per rf2-ts1a: the runtime-callable fn form (`re-frame.core/dispatch*`
  in public API terms). The macro form `re-frame.core/dispatch` stamps
  an `:rf.trace/call-site` onto `opts` at compile time; from there it
  rides the envelope and gets bound around the handler chain's
  invocation in `process-event!`."
  ([event] (dispatch! event {}))
  ([event opts]
   (let [envelope     (build-envelope event opts)
         frame-record (frame/frame (:frame envelope))]
     (cond
       (nil? frame-record)
       ;; The frame-destroyed emit fires synchronously — bind the
       ;; envelope's call-site so the error event carries it. Reading
       ;; the call-site through the envelope (already gated) avoids a
       ;; second `(:rf.trace/call-site opts)` keyword reference here.
       (trace/with-call-site (:call-site envelope)
         (trace/emit-error! :rf.error/frame-destroyed
                            {:frame (:frame envelope) :event event}))

       :else
       (let [router (:router frame-record)]
         (emit-dispatched-trace! envelope false)
         (swap! router update :queue conj envelope)
         (ensure-drain-scheduled! (:frame envelope) router)))
     nil)))

(defn- other-frame-mid-drain
  "Per rf2-fp97 — Spec 002 §dispatch-sync cross-frame note. Return the
  frame-id of any registered, non-destroyed frame OTHER than `target-id`
  whose router currently shows `:in-sync-drain?` or `:in-drain?` true.
  Returns nil when no such frame exists.

  Used by `dispatch-sync!` to detect the cross-frame cascade pattern
  (frame A mid-drain, a handler calls `(rf/dispatch-sync! [...] {:frame :b})`).
  The same-frame case is already an error; the cross-frame case is
  intentional but surprising, so we surface it as
  `:rf.warning/cross-frame-dispatch-sync-during-drain` rather than
  refuse. Frames are independent state machines (per Spec 002 §Rules
  rule 1) and frame B's drain doesn't violate frame A's contract.

  Dev-only — the caller gates on `interop/debug-enabled?` to skip the
  registry walk in production."
  [target-id]
  (some (fn [id]
          (when (not= id target-id)
            (when-let [fr (frame/frame id)]
              (let [r @(:router fr)]
                (when (or (:in-sync-drain? r) (:in-drain? r))
                  id)))))
        (frame/frame-ids)))

(defn- emit-cross-frame-warning!
  "Per rf2-fp97: emit `:rf.warning/cross-frame-dispatch-sync-during-drain`
  when `dispatch-sync!` lands on frame `target-id` while a different
  frame (`other-id`) is mid-drain. The caller frame is read from
  `frame/*current-frame*`; when unbound (no frame context — e.g. a
  process-level REPL caller threading the dispatch through some unusual
  path) the field is `:rf/none`.

  Per Mike's 2026-05-13 Option B decision: warn, do not refuse.
  Continues with the dispatch."
  [target-id other-id event]
  (let [caller-id (or frame/*current-frame* :rf/none)
        reason    (str "dispatch-sync! against `" target-id "` while frame `"
                       other-id "` is mid-drain. The two cascades will "
                       "interleave: `" target-id "`'s drain runs to settled "
                       "while `" other-id "` is still in flight, then `"
                       other-id "` continues. Frames are independent state "
                       "machines so this does not violate either frame's "
                       "contract (per Spec 002 §Run-to-completion §Rules "
                       "rule 1 — no cross-frame drain), but the interleaved "
                       "ordering is rarely the caller's intent. If the goal "
                       "is fire-and-forget cross-frame coordination, prefer "
                       "the async form `(rf/dispatch event {:frame other})` "
                       "— it queues on the target frame's router and drains "
                       "on a later cycle, after the caller's cascade settles.")]
    (trace/emit! :warning
                 :rf.warning/cross-frame-dispatch-sync-during-drain
                 {:caller-frame caller-id
                  :target-frame target-id
                  :other-frame  other-id
                  :event        event
                  :reason       reason
                  :recovery     :no-recovery})))

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
  depth limits behaving uniformly across sync and async dispatch.

  Per rf2-fp97: when the same-frame reentry check passes but ANOTHER
  frame is currently mid-drain, the runtime emits
  `:rf.warning/cross-frame-dispatch-sync-during-drain` and continues
  with the dispatch. The cross-frame cascade interleaves (target frame
  drains to settled before the caller's drain resumes); per Spec 002
  §Rules rule 1 this is intentional (frames are independent state
  machines) but rarely the caller's intent, so the warning surfaces the
  pattern for observability tools without refusing the call.

  Per rf2-ts1a: runtime-callable fn form for `dispatch-sync` (the macro
  form stamps an `:rf.trace/call-site` onto `opts` at compile time)."
  ([event] (dispatch-sync! event {}))
  ([event opts]
   (let [envelope     (build-envelope event opts)
         frame-record (frame/frame (:frame envelope))
         ;; Read the call-site from the envelope (already gated in
         ;; build-envelope) so the synchronous error emits below can
         ;; carry it without referencing the keyword a second time.
         call-site    (:call-site envelope)]
     (cond
       (nil? frame-record)
       (trace/with-call-site call-site
         (trace/emit-error! :rf.error/frame-destroyed
                            {:frame (:frame envelope) :event event
                             :recovery :no-recovery}))

       (let [r @(:router frame-record)
             ;; Per rf2-ynk7: `:in-drain?` now holds the drainer's
             ;; thread (or nil). Only flag as "nested" when the current
             ;; thread is the drainer — a different thread mid-drain is
             ;; a concurrent caller, which `drain-block!` handles
             ;; correctly by spin-CAS-waiting. CLJS: `:in-drain?` is
             ;; `true` or `nil`; the equality check still discriminates
             ;; (truthy = same-thread by construction on a single-
             ;; threaded host).
             same-thread-drain?
             #?(:clj  (identical? (:in-drain? r) (Thread/currentThread))
                :cljs (true? (:in-drain? r)))]
         (or (:in-sync-drain? r) same-thread-drain?))
       ;; Per Spec 002 §dispatch-sync: nesting dispatch-sync inside the
       ;; SAME frame's running drain (sync or async) is an error — the
       ;; event would interleave with the outer handler's run-to-completion.
       (trace/with-call-site call-site
         (trace/emit-error! :rf.error/dispatch-sync-in-handler
                            {:frame    (:frame envelope)
                             :event    event
                             :reason   "dispatch-sync called from inside a running drain. Use dispatch (the queued form) instead so the event runs after the current drain settles."
                             :recovery :no-recovery}))

       :else
       (let [router (:router frame-record)]
         ;; Per rf2-fp97 (Mike's 2026-05-13 Option B decision): the
         ;; same-frame reentry check passed; now check whether any OTHER
         ;; frame is mid-drain. If so, the dispatch will interleave with
         ;; that frame's cascade — warn but proceed. Dev-only: gated on
         ;; `interop/debug-enabled?` so production skips the registry
         ;; walk.
         (when interop/debug-enabled?
           (when-let [other-id (other-frame-mid-drain (:frame envelope))]
             (emit-cross-frame-warning! (:frame envelope) other-id event)))
         (emit-dispatched-trace! envelope true)
         (try
           ;; Per rf2-ynk7 §single-drainer invariant: dispatch-sync
           ;; needs the cascade settled before return AND the seed-
           ;; push at the FRONT of the queue must not interleave with
           ;; an active drainer's peek+pop. drain-block! spin-CAS-
           ;; acquires the drain-lock, THEN runs the callback below
           ;; (the prepend now sits inside the single-drainer window —
           ;; no other drain can be mid-peek+pop), THEN runs the drain
           ;; loop. The :in-sync-drain? flag suppresses any concurrent
           ;; dispatch-sync from another thread; :scheduled? true
           ;; suppresses async drain scheduling mid-cascade.
           ;; :in-sync-drain? is cleared in the outer finally after
           ;; drain-block! returns.
           (drain-block!
             (:frame envelope)
             (fn []
               (swap! router (fn [{:keys [queue] :as r}]
                               (assoc r
                                      :queue (into interop/empty-queue
                                                   (cons envelope queue))
                                      :scheduled?     true
                                      :in-sync-drain? true)))))
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
