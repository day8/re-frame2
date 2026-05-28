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
            [re-frame.router.diagnostics :as diag]
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
;; interop/debug-enabled? is false at compile time) elide the allocation:
;; `build-envelope`'s `(when interop/debug-enabled? (next-dispatch-id))`
;; gate (see below) means the `swap!` and its counter increment are
;; unreachable under `:advanced + goog.DEBUG=false`. The `defonce` atom
;; allocation itself is process-load-time and harmless.

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
    :source             trigger-kind classifier — closed set
                        `:ui :frame-init :machine-spawn
                        :machine-action :always :after-timer
                        :fx-dispatch :fx-dispatch-later :http :repl
                        :ssr-hydration :test :unknown :other`.
                        Default `:unknown` per rf2-hxj0d
                        (changed from `:ui` to avoid false
                        attribution of unstamped dispatches as
                        UI-driven). UI handler call-sites stamp
                        `:source :ui` explicitly; substrate-internal
                        dispatch sites stamp the matching specific
                        value per rf2-ejtpd + rf2-c3990:
                          - machine `:after` timer       → :after-timer
                          - machine `:always` microstep  → :always (on the
                            per-microstep trace; `:always` does not
                            produce its own envelope)
                          - machine spawn fx             → :machine-spawn
                          - `:dispatch`(-later) fx from a
                            machine handler              → :machine-action
                            (rf2-c3990 — the actor-message path)
                          - `:dispatch` fx               → :fx-dispatch
                          - `:dispatch-later` fx         → :fx-dispatch-later
                        Other origins stamp per the documented
                        vocabulary. Per rf2-c3990 the prior broad
                        `:fx` / `:machine` / `:dispatch-later` /
                        `:timer` aliases are dropped — every dispatch
                        site stamps the specific kind.
    :origin             actor identity tag (:app default; :pair, :story,
                        :test, ... per Spec 002 §Dispatch origin tagging)
    :rf/dispatch-origin closed-enum functional source per Xray A.5 /
                        Spec 009 §Dispatch-origin tagging — one of
                        :user :router :websocket :http :ssr :fx-emit
                        :timer :test-harness :tool :internal. Defaults
                        to :user. Distinct from :origin (actor identity)
                        and :source (trigger kind). Per rf2-t1lxr.
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
                                  (= :rf/default default-frame)))
        ;; Per rf2-j20a7 / Spec 005 §Level 4: a dispatch emitted from a
        ;; machine's own processing (its `:action` / `:entry` / `:exit` /
        ;; transition handling, via `:fx [[:dispatch …]]` or an inter-
        ;; machine dispatch) is a machine-internal continuation. The
        ;; `:dispatch` / `:dispatch-later` fx body stamps
        ;; `:rf.machine/internal? true` on the child opts when the
        ;; emitting handler is a machine (see `child-dispatch-opts` in
        ;; re-frame.fx, which copies the flag off the machine-tagged
        ;; parent envelope). `dispatch!` reads it to insert the envelope
        ;; at the FRONT of the queue so the macrostep settles to
        ;; quiescence before the next EXTERNAL event. This is a runtime
        ;; ordering guarantee — NOT a trace concern — so the flag is
        ;; carried unconditionally (never gated on interop/debug-enabled?).
        machine-internal?  (true? (:rf.machine/internal? opts))]
    (cond-> {:event                  event
             :frame                  (or (:frame opts) default-frame)
             ;; Per rf2-5uwl: merge the lexical-scope `*fx-overrides*`
             ;; (bound by `rf/with-fx-overrides`) under the per-call opt so
             ;; the per-call opt wins on key collision. The per-frame
             ;; tier is still merged later inside `apply-overrides`.
             :fx-overrides           (merge *fx-overrides* (:fx-overrides opts {}))
             :interceptor-overrides  (:interceptor-overrides opts {})
             :trace-id               (:trace-id opts)
             ;; Per rf2-hxj0d: default `:source` is `:unknown` —
             ;; previously defaulted to `:ui`, which silently
             ;; misattributed every unstamped dispatch (frame-init,
             ;; internal continuations, REPL eval) as UI-driven. UI
             ;; handler call-sites stamp `:source :ui` explicitly; the
             ;; `:on-create` frame-init dispatch (frame.cljc) stamps
             ;; `:source :frame-init`; fx-emit dispatches (fx.cljc)
             ;; inherit the parent's `:source`; tests / REPL stamp
             ;; their own kind. `:unknown` surfaces "we lost track"
             ;; rather than fabricating an origin.
             :source                 (:source opts :unknown)
             ;; Per rf2-5qp4g: per-source-kind detail riding alongside
             ;; the closed-set `:source` value. Optional; only stamped
             ;; by substrate dispatch sites that carry kind-specific
             ;; payload (e.g. `:dispatch-later` fx stamps `{:ms <ms>}`
             ;; so the Epoch panel's DISPATCH step renders the
             ;; originally scheduled delay alongside the kind label).
             ;; Tools read this off the `:rf.event/source-detail` tag
             ;; on `:rf.event/dispatched` (stamped in
             ;; `emit-dispatched-trace`).
             :source-detail          (:source-detail opts)
             :origin                 (:origin opts :app)
             ;; Per rf2-t1lxr: the closed-enum functional source per
             ;; Xray A.5 / Spec 009 §Dispatch-origin tagging. The 10-value
             ;; taxonomy (:user :router :websocket :http :ssr :fx-emit
             ;; :timer :test-harness :tool :internal) classifies WHERE the
             ;; dispatch came from. Defaults to :user; internal callers
             ;; (router routing emits, fx `:dispatch` cascades, HTTP reply
             ;; dispatches, machine timer fires, …) self-tag at their
             ;; emit site to override the default.
             :rf/dispatch-origin     (:rf/dispatch-origin opts :user)
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
      fallthrough?       (assoc :fell-through-to-default? true)
      ;; Per rf2-j20a7: carry the machine-internal continuation flag onto
      ;; the envelope so `dispatch!` can front-of-queue insert it.
      machine-internal?  (assoc :rf.machine/internal? true))))

(defn- resolve-handler [event-id]
  (registrar/lookup :event event-id))

;; Fallthrough-to-default + cross-frame dispatch-sync warnings + the
;; no-handler error path live in `re-frame.router.diagnostics` —
;; extracted per rf2-0ytl4 Phase-2 seam R-B. Every one of those fns
;; runs on a cold/error path or sits behind `interop/debug-enabled?`,
;; so the cross-ns indirection adds no measurable cost.

(def ^:private empty-fx-overrides
  "Shared sentinel returned by `apply-overrides` on the no-override hot
  path. Reused across every override-free dispatch so the cascade
  doesn't churn a fresh empty map per event."
  {})

(def ^:private empty-extra-interceptors
  "Shared sentinel for the no-extra-interceptors hot path."
  [])

(def ^:private empty-icpt-overrides
  "Shared sentinel for the no-interceptor-overrides hot path."
  {})

(defn- apply-overrides
  "Per Spec 002 §Per-frame and per-call overrides: per-frame and per-call
  override maps merge with per-call winning. Returns
  `{:fx-overrides :icpt-overrides :extra-interceptors}` for this dispatch.

  HOT PATH: fires on every dispatch. The dominant production path is
  override-free — most apps neither set per-frame `:fx-overrides` /
  `:interceptor-overrides` / `:interceptors` in their `reg-frame`
  config nor pass per-call overrides in the dispatch envelope. On that
  path we short-circuit to shared empty sentinels rather than `merge`-
  ing empty maps and `vec`-ing an empty `concat`.

  Per Spec 002 §`:interceptor-overrides` (lines 1108-1139): per-frame
  and per-call interceptor-override maps merge with per-call winning,
  same as `:fx-overrides`. The merged map is consumed by
  `apply-icpt-overrides` in `prepare-handler-ctx` to substitute
  interceptors in the chain by `:id`."
  [envelope frame-record]
  (let [frame-cfg            (:config frame-record)
        per-call-fx          (:fx-overrides envelope)
        per-frame-fx         (:fx-overrides frame-cfg)
        per-call-icpt        (:interceptor-overrides envelope)
        per-frame-icpt       (:interceptor-overrides frame-cfg)
        frame-interceptors   (:interceptors frame-cfg)]
    (if (and (nil? per-call-fx)
             (nil? per-frame-fx)
             (nil? per-call-icpt)
             (nil? per-frame-icpt)
             (nil? frame-interceptors))
      {:fx-overrides       empty-fx-overrides
       :icpt-overrides     empty-icpt-overrides
       :extra-interceptors empty-extra-interceptors}
      {:fx-overrides       (merge per-frame-fx per-call-fx)
       :icpt-overrides     (merge per-frame-icpt per-call-icpt)
       :extra-interceptors (vec frame-interceptors)})))

(defn- apply-icpt-overrides
  "Per Spec 002 §`:interceptor-overrides` (lines 1124-1130): walk
  `chain` and substitute interceptors by `:id` against `overrides`.
  Entries whose `:id` is a key in `overrides` are replaced by the
  override's value; `nil`-valued overrides remove the interceptor from
  the chain.

  HOT PATH no-op: when `overrides` is empty the chain is returned
  unchanged."
  [chain overrides]
  (if (empty? overrides)
    chain
    (->> chain
         (mapv #(if (and (map? %) (contains? overrides (:id %)))
                  (get overrides (:id %))
                  %))
         (filterv some?))))

(defn- validate-event!
  "Per Spec 010 §Validation order step 1 (rf2-jwm4): validate the
  dispatched event vector against the handler's :schema BEFORE the
  handler's interceptor chain runs. Failures emit
  :rf.error/schema-validation-failure with :where :event and skip the
  handler (recovery :no-recovery; downstream queue continues).

  Returns truthy when the handler should run, falsy when it should be
  skipped. Defaults to true when the schemas namespace hasn't been
  loaded.

  Body gated on `interop/debug-enabled?` (rf2-gaqwr). Spec 010
  validate-*! is a dev-only validator surface — per
  `re-frame.schemas.validate` §Production builds, every dev-time
  `validate-*!` body sits inside its own `(if interop/debug-enabled?
  ...)` gate and DCE-elides under :advanced+goog.DEBUG=false. The
  validator therefore unconditionally returns true in production
  whether or not the schemas artefact is loaded (the boundary-
  validation seam `:schemas/validate-with-registered-fn` is the
  production-side surface, not this one). Gating the router-side
  caller collapses the late-bind lookup, the try/catch frame, and
  the `:schemas/validate-event!` keyword's interned slot to a
  constant `true` on the hot path."
  [event-id event handler-meta]
  (if interop/debug-enabled?
    ;; Sticky hook (rf2-f72pd) — `:schemas/validate-event!` is published
    ;; once at re-frame.schemas load and never withdrawn in dev; fires
    ;; per-dispatch.
    (if-let [validate! (late-bind/get-fn-cached :schemas/validate-event!)]
      (try (validate! event-id event handler-meta)
           (catch #?(:clj Throwable :cljs :default) _ true))
      true)
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

  Schema-derived redaction is reflected in the `:tags :event` slot:
  when the handler's path-scoped db slice overlaps a sensitive schema
  slot, the router-installed redaction interceptor stores the scrubbed
  event form under `:rf/redacted-event`; this helper surfaces it.

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
        emit-event (privacy/redacted-event-from-ctx ctx)
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
    ;; every fn registered through `rf/register-error-listener!`
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

  Per rf2-jbbp7 / Spec 010 §Per-step recovery row 7: AND-conjoins the
  app-db validator with `:machines/validate-machine-data!` (the
  `:where :machine-data` boundary). The machine walker iterates
  `[:rf/machines]` and validates each snapshot's `:data` against the
  registered machine's top-level `:schema`. Both validators run on
  every commit so the operator gets the full failure surface; the
  conjunction means a `false` from either rolls back the cascade.

  Defensive truth-coercion: a host-thrown validator (e.g. a buggy
  user-supplied :schemas/set-schema-validator! fn) is caught and
  treated as `true` (no rollback) — the validator is failing on
  itself, not on a user schema, and a hard abort here would mask the
  actual app-db state from the rest of the cascade. Real schema
  failures route through the in-band false return."
  [db-after event-id frame]
  (let [app-ok?
        ;; Sticky hook (rf2-f72pd) — fires per-dispatch.
        (if-let [validate (late-bind/get-fn-cached :schemas/validate-app-schema!)]
          (try
            ;; nil-coerce: treat a nil return as success (don't roll
            ;; back) so a host that returns nil rather than true on a
            ;; clean validate keeps working.
            (let [r (validate db-after event-id frame)]
              (if (nil? r) true r))
            (catch #?(:clj Throwable :cljs :default) _ true))
          true)
        machines-ok?
        ;; Per rf2-jbbp7 — the machine-data boundary (Spec 005 §Schema
        ;; validation). The hook is absent when the machines artefact
        ;; isn't on the classpath; absent → true (no machines means no
        ;; machine-data to validate).
        (if-let [validate-md (late-bind/get-fn-cached
                               :machines/validate-machine-data!)]
          (try
            (let [r (validate-md db-after event-id frame)]
              (if (nil? r) true r))
            (catch #?(:clj Throwable :cljs :default) _ true))
          true)]
    ;; Both must conform for the cascade to keep its commit; the per-
    ;; failure traces have already been emitted independently so the
    ;; operator sees every violation, not just the first.
    (and app-ok? machines-ok?)))

(defn- commit-db-effect!
  "Apply :db atomically: replace the app-db container, emit the
  :event/db-changed trace, then run post-commit schema validation.
  Returns true when the commit is durable (no :db effect, or all
  schemas conformed); false when post-commit validation rejected the
  new state and the container has been **rolled back** to the
  pre-handler value (per Spec 010 §Per-step recovery row 4 /
  rf2-wkxng / rf2-6m0se).

  Per Spec 013 §Drain integration (rf2-u0zz5): `(:db effects)` here is
  the FLOW-AUGMENTED value — the OUTERMOST flows-after-interceptor has
  already rewritten the pending `:db` effect by the time the chain
  returns. So `:event/db-changed` reflects the flow-derived db and fires
  AFTER `:rf.flow/computed` (per Spec 009 §Canonical per-event trace
  sequence).

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

  Schema-derived redaction is reflected in the `:event/db-changed`
  trace event's `:tags :event` slot. The router-installed internal
  interceptor stashes the scrubbed form under `:rf/redacted-event`;
  this commit path reads it through."
  [effects event-id event frame ctx db-before]
  (if (contains? effects :db)
    (let [container  (frame/get-frame-db frame)
          new-db     (:db effects)
          emit-event (privacy/redacted-event-from-ctx ctx)]
      (adapter/replace-container! container new-db)
      (trace/emit! :rf.event :rf.event/db-changed
                   {:rf.trace/event-id event-id :rf.event/v emit-event :frame frame})
      (if (run-post-commit-validation! new-db event-id frame)
        true
        (do
          ;; Roll back: restore the pre-handler container value, then
          ;; emit a second :event/db-changed with :phase :rollback so
          ;; subs / listeners see the restored state on the trace
          ;; stream. The error trace from validate-app-schema! has
          ;; already fired between the two commits — consumers see
          ;; (in order): forward commit, schema-failure error,
          ;; rollback commit.
          (adapter/replace-container! container db-before)
          (trace/emit! :rf.event :rf.event/db-changed
                       {:rf.trace/event-id event-id
                        :rf.event/v        emit-event
                        :frame             frame
                        :rf.trace/phase    :rollback})
          false)))
    true))

(def ^:private flows-after-interceptor
  "Per Spec 013 §Drain integration (rf2-u0zz5): the framework-owned
  OUTERMOST `:after` interceptor that runs the flow transform. The router
  PREPENDS it to the dispatch-time `full-chain` (NOT the registered
  handler-meta chain — see `prepare-handler-ctx`), so it is the first
  interceptor in declaration order and therefore the LAST `:after` to
  fire: after the rest of the `:after` chain, before `:db` install, and
  before `:fx`.

  Outermost (not innermost) is load-bearing: the `path` std-interceptor's
  `:after` splices the handler's slice back into the FULL db, and flows
  read full-app-db `:inputs` paths — so the flow transform MUST run after
  that reshape. Running flows innermost would expose them to the
  un-spliced path slice and mis-read their inputs.

  Its `:after` reads the chain's PENDING `:db` effect (the full,
  fully-reshaped value — or the current app-db value when no `:db` effect
  was produced), runs `:flows/run-flows-on-db` over that value, and writes
  the flow-augmented db back into `(:effects ctx :db)`. The eventual `:db`
  install and the `:fx` walk therefore observe the flow-derived db.

  When the flows artefact is absent (`:flows/run-flows-on-db` hook nil)
  the `:after` is a single nil-check no-op.

  Failure (Spec 013 §Failure semantics — atomicity contract, Mike
  2026-05-24): a flow throw is a PRE-INSTALL throw, so it aborts the whole
  event exactly like a handler / interceptor-`:after` throw. The `:after`
  catches the ex-info, DISCARDS the pending `:db` effect (`dissoc`-ing it
  from `(:effects ctx)`) so the single deferred install installs NOTHING —
  app-db is left UNCHANGED, NO `:rf.event/db-changed` is emitted, and NO
  `:fx` run. There is no partial commit: prior successful flows' writes do
  NOT land either (they were only ever in the pending `:db` effect, never
  installed). The throw is stashed under `:rf/flow-error` so
  `commit-and-flow!` can emit `:rf.error/flow-eval-exception` and skip the
  install + `:fx`. It is NOT recorded into `:rf/interceptor-error` — a
  flow-eval failure is a distinct error category from a handler/interceptor
  exception, with its own substrate routing and `:where :flow-eval`
  discriminator.

  Frame-agnostic: a single shared interceptor value (no per-dispatch
  allocation). The dispatching frame is read from the context coeffects
  (`assemble-initial-ctx` stamps `:frame`).

  Per rf2-ta0y7 (Mike 2026-05-25): this interceptor is also the emit
  point for the t1 / t2 pending-`:db` snapshot pair on the trace
  stream. The handler returned its `:db` effect; the rest of the
  `:after` chain reshaped it (e.g. `path`-interceptor splice) and the
  flow transform may (or may not) reshape it further before the single
  deferred install. The pair stamps the full value at two endpoints:

    t1 `:rf.event/db-pending`            — POST-handler-chain,
                                            PRE-flow-transform (the
                                            value the handler chain
                                            returned, before any flow
                                            could touch it).
    t2 `:rf.event/db-pending-post-flow`  — POST-flow-transform, PRE-
                                            commit (the value flows
                                            reshaped, when they did).

  Mike's ruling stores the full value plain — same posture as
  `:rf.event/fx` on `:rf.fx/do-fx`. Persistent data structures make
  the cost a pointer per emit (structural sharing with app-db); no
  copy, no diff, no DEBUG conditional. `day8/de-dupe` at the pair-mcp
  wire boundary (rf2-obpa9) collapses the repeated subtrees on
  egress. The Xray Handler panel reads t1 to render the returned
  `:db` value under EVENT HANDLER and (t1, t2) together to render
  the t1→t2 reshape under FLOWS — the framework does NOT precompute
  a diff.

  Emit gating:
  - t1 fires when `has-db?` is true (the handler returned a `:db`
    slot — mirrors how `:rf.event/fx` only carries data when there
    is `:fx`). Fires regardless of whether the flows artefact is
    loaded — apps that never registered a flow still get t1.
  - t2 fires only when flows actually transformed the pending value
    (`(not (identical? new-db pending-db))`). If no flow's `:after`
    touched `:db`, t2 == t1 and the second emit is omitted (no
    information). Implies: no-flows-artefact apps never emit t2.

  Both emits sit inside `trace/emit!` which is DCE-gated by
  `interop/debug-enabled?` — production CLJS bundles fold both away.
  An aborted-by-flow-throw event (the catch arm) does NOT emit t2:
  the partial-cascade `:db` was discarded along with all flow side
  effects (no `:rf.event/db-changed` will fire either)."
  (interceptor/->interceptor
    :id          :rf/flows
    :rf/default? true
    :after
    (fn [ctx]
      (let [frame       (:frame (:coeffects ctx))
            effects     (:effects ctx)
            has-db?     (contains? effects :db)
            run-on-db   (late-bind/get-fn-cached :flows/run-flows-on-db)
            pending-db  (if has-db?
                          (:db effects)
                          (when run-on-db (frame/frame-app-db-value frame)))
            ;; Per rf2-ta0y7: stamp `:rf.event/v` + `:rf.trace/event-id`
            ;; on the t1 / t2 trace events so they carry the same
            ;; per-event attribution every other `:op-type :rf.event`
            ;; emit carries (parity with `:rf.event/run-start` /
            ;; `:rf.event/run-end` / `:rf.event/db-changed`; tests like
            ;; `inv-6-frame-created-not-folded-into-next-epoch` assert
            ;; every event-family trace in an epoch carries the
            ;; epoch's `:rf.event/v`). Read the redacted event from
            ;; ctx so schema-sensitive event payloads ride the same
            ;; scrubbed value the rest of the family does.
            emit-event  (when has-db? (privacy/redacted-event-from-ctx ctx))
            event-id    (when has-db? (some-> emit-event first))]
        ;; t1 — stamp the handler-returned (post-`:after`-chain, pre-
        ;; flow-transform) `:db` value. Always fires when the handler
        ;; returned `:db`, whether or not the flows artefact is loaded.
        ;; The value is the persistent reference; structural sharing
        ;; with app-db means the emit cost is pointer-sized.
        (when has-db?
          (trace/emit! :rf.event :rf.event/db-pending
                       {:rf.trace/event-id event-id
                        :rf.event/v        emit-event
                        :frame             frame
                        :rf.event/db       pending-db}))
        (if run-on-db
          (try
            (let [new-db (run-on-db frame pending-db)]
              ;; t2 — flows transformed the pending `:db`. Stamp the
              ;; flow-augmented value so the Xray panel can render the
              ;; t1→t2 reshape. The dirty-check below is the same
              ;; identical-by-reference guard the effect-publish below
              ;; uses; t2 fires on the same condition that a `:db`
              ;; effect survives to `commit-db-effect!`. Per
              ;; rf2-ta0y7 t2 may fire even when the handler returned
              ;; no `:db` (flows synthesised one from app-db); resolve
              ;; the attribution-event lazily so the no-handler-`:db`
              ;; case still carries it.
              (when (not (identical? new-db pending-db))
                (let [t2-event   (or emit-event (privacy/redacted-event-from-ctx ctx))
                      t2-evt-id  (or event-id (some-> t2-event first))]
                  (trace/emit! :rf.event :rf.event/db-pending-post-flow
                               {:rf.trace/event-id t2-evt-id
                                :rf.event/v        t2-event
                                :frame             frame
                                :rf.event/db       new-db})))
              ;; Only publish a `:db` effect when flows actually changed
              ;; the value OR the handler already had one — a no-flow /
              ;; no-write event must not synthesise a spurious `:db`
              ;; effect (which would force an app-db install + db-changed
              ;; trace on an event that wrote nothing).
              (if (or has-db? (not (identical? new-db pending-db)))
                (interceptor/assoc-effect ctx :db new-db)
                ctx))
            (catch #?(:clj Throwable :cljs :default) e
              ;; Atomicity contract (Spec 013 §Failure semantics, Mike
              ;; 2026-05-24): a flow throw is a PRE-INSTALL throw, so it
              ;; aborts the whole event. DISCARD any pending `:db` effect
              ;; (the handler's and any prior flows' writes) so the single
              ;; deferred install installs NOTHING — app-db stays unchanged,
              ;; no `:rf.event/db-changed`, no `:fx`. No partial commit.
              ;; Stash the throw under `:rf/flow-error` so `commit-and-flow!`
              ;; surfaces `:rf.error/flow-eval-exception` and skips install +
              ;; `:fx`. `dissoc`-ing `:db` is the whole mechanism — winding
              ;; back on a pre-install throw is FREE because the install was
              ;; already deferred to one write.
              ;;
              ;; No t2 emit on the throw arm: the partial cascade `:db` was
              ;; discarded (no install, no db-changed). The t1 emit above
              ;; already fired with the handler-returned value; consumers
              ;; that pair t1 with an event abort see no t2.
              (-> (update ctx :effects dissoc :db)
                  (assoc :rf/flow-error e))))
          ;; No flows artefact loaded — short-circuit (steady state for
          ;; apps that never registered any flow). t1 above already fired
          ;; when the handler returned `:db`; t2 is by definition
          ;; impossible here (no flow could have transformed the value).
          ctx)))))

(defn- emit-flow-eval-exception!
  "Surface a flow-eval throw (stashed by `flows-after-interceptor` under
  `:rf/flow-error`) as `:rf.error/flow-eval-exception` through BOTH the
  dev-only trace surface AND the always-on error-emit substrate (per
  rf2-hrt5c — the security-audit fix). Per Spec 013 §Failure semantics
  rule 3 the caller skips `:fx` after this fires; the drain continues
  with the NEXT event.

  Per rf2-hrt5c: `trace/emit-error!` is gated by `interop/debug-enabled?`
  and DCEs under `:advanced` + `goog.DEBUG=false` — so the substrate path
  is what survives prod elision and reaches off-box monitors. Mirrors
  the handler-exception path (`emit-handler-exception!`). There is no
  `:handler-id` (the throw came from the flow transform, not a handler);
  `:where :flow-eval` discriminates this path for policy fns.

  Per rf2-je5p8: `evaluate-flow!`'s catch (preserved through
  `run-flows-on-db`'s re-wrap) carries `:rf.flow/failed-id` on the
  ex-data; it is stamped onto the substrate record's `:tags` as
  `:flow-id` so CLJS-prod ops (where `:rf.flow/failed` DCEs) can
  attribute the cascade-level error to a specific flow. Attribution is
  `:flow-id`-only — there is no real flow value to carry."
  [e event event-id frame start-ms]
  (let [end-ms     (interop/now-ms)
        ;; Per rf2-bacs4 §Record shape: `:elapsed-ms` is an integer.
        ;; Round once at the substrate boundary (JVM long / CLJS float).
        elapsed-ms (long (max 0 (- end-ms start-ms)))
        msg        #?(:clj (.getMessage ^Throwable e) :cljs (.-message e))
        flow-id    (some-> (ex-data e) :rf.flow/failed-id)
        tags       (cond-> {:event-id          event-id
                            :event             event
                            :frame             frame
                            :where             :flow-eval
                            :handler-id        nil
                            :exception         e
                            :exception-message msg
                            :reason            "Flow evaluation threw."
                            :recovery          :no-recovery}
                     flow-id (assoc :flow-id flow-id))]
    ;; Always-on per rf2-bacs4 / rf2-hqbeh — fires in CLJS production
    ;; where `trace/emit-error!` below is compile-time elided. The two
    ;; fan-out paths (corpus-wide listener registry + per-frame
    ;; `:on-error` policy) are independent and isolated.
    (error-emit/dispatch-on-error!
      :rf.error/flow-eval-exception
      event
      event-id
      frame
      e
      elapsed-ms
      end-ms
      {:operation :rf.error/flow-eval-exception
       :op-type   :error
       :tags      tags
       :recovery  :no-recovery})
    ;; Dev-side trace emission. Gated by `interop/debug-enabled?` inside
    ;; `trace/emit-error!`; DCEs in CLJS prod. Same payload shape as
    ;; before — existing trace consumers are unaffected.
    (trace/emit-error! :rf.error/flow-eval-exception
                       {:frame frame :event event :exception e})))

(defn- run-fx-effects!
  "Walk :fx in source order, threading fx-overrides through so per-frame
  / per-call overrides take effect. Per-frame :platform overrides the
  host-wide platform marker (`interop/active-platform`, toggled via
  `re-frame.core/init-platform`) when set.

  Per Spec 002 §The binary fx-handler signature (line 603) and §Cascade
  propagation (line 1162): the originating dispatch envelope is
  threaded into `do-fx` so reserved-fx defmethods (`:dispatch`,
  `:dispatch-later`) can copy inheritable keys (`:fx-overrides`,
  `:interceptor-overrides`, `:trace-id`, `:origin`, `:source`) onto
  child dispatches. User fxs see it at `(:envelope m)`.

  Per rf2-twt7m Change 2: the full effects map is also threaded to
  `do-fx` (the `:effects` opt) so the terminating `:event/do-fx` trace
  marker can stamp `:fx` (the returned vector) and `:db-present?`
  (whether the handler returned a `:db` slot). The value of `:db`
  is NOT stamped — App-db diff traces already carry slice changes.

  Per rf2-9dk9y: the user-injected coeffects projection moved OFF the
  `:rf.fx/do-fx` marker and ONTO `:rf.event/run-end` (see
  `emit-cascade-trailers!`). The prior placement silently dropped the
  COEFFECTS row whenever a handler returned only `:db` (no `:fx`) — the
  fx walk was short-circuited so the marker never emitted. Pinning the
  cofx stamp to the always-fires run-end emit makes the COEFFECTS
  section render uniformly across event flavours.

  Per rf2-ee38b.1: the former positional do-fx arity ladder collapsed
  into a single `opts` map — this is the sole caller threading the full
  set of optionals."
  [effects frame frame-record fx-overrides envelope]
  (when-let [fx-vec (:fx effects)]
    (let [active-platform (or (-> frame-record :config :platform)
                              (interop/active-platform))
          event           (:event envelope)]
      (fx/do-fx frame fx-vec active-platform
                {:overrides       fx-overrides
                 :origin-event    event
                 :parent-envelope envelope
                 :effects         effects}))))

;; ---- process-event* phases ------------------------------------------------
;;
;; `process-event*` decomposes into named phases per audit RT1 (rf2-mccjv).
;; Each phase owns one piece of the per-event cascade; the outer
;; `process-event*` is a thin driver that sequences them.
;;
;;   handle-frame-destroyed!     early-exit: emit :rf.error/frame-destroyed
;;                               when the frame record is gone (frame disposed
;;                               between enqueue and dispatch)
;;   diag/handle-no-handler!     early-exit: emit fallthrough warning (when
;;                               applicable) plus :rf.error/no-such-handler.
;;                               Lives in re-frame.router.diagnostics per
;;                               rf2-0ytl4 seam R-B.
;;   prepare-handler-ctx         build the full interceptor chain (incl. the
;;                               outermost flows-after-interceptor) + initial
;;                               context and the effective fx-overrides map;
;;                               returns a tight map consumed by run-chain
;;                               and commit-and-flow!
;;   run-chain                   execute the interceptor chain bracketed in
;;                               performance marks; skipped when event-payload
;;                               validation fails (per Spec 010 §Per-step
;;                               recovery step 1). Flows run HERE, inside the
;;                               chain, as the outermost :after (rf2-u0zz5).
;;   commit-and-flow!            handler-exception emit (if any), flow-eval
;;                               error emit (if any), :db commit (of the
;;                               flow-augmented db), then walk :fx in source
;;                               order; returns the dispatch outcome keyword
;;                               (:ok / :error / :rolled-back / :flow-error)
;;                               for the event-emit record
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

(defn- refresh-elision-from-schemas!
  "Refresh schema-owned elision registries before a handler runs. No-op
  when the schemas artefact is absent."
  [frame]
  (when-let [populate! (late-bind/get-fn-cached :elision/populate-from-schemas!)]
    (try
      (populate! frame)
      (catch #?(:clj Throwable :cljs :default) _ nil))))

(defn- prepare-handler-ctx
  "Build the effective interceptor chain and initial context for a
  resolved handler. Merges per-frame + per-call overrides (Spec 002
  §Per-frame and per-call overrides) and threads them through the
  initial cofx map. Returns `{:full-chain :initial-ctx :fx-overrides}`.

  Chain assembly order, per Spec 002:
  1. Prepend per-frame `:interceptors` to the handler's own chain
     (additive — §`:interceptors` — *add* interceptors).
  2. Walk the assembled chain and apply `:interceptor-overrides`
     (replace-by-`:id` per §`:interceptor-overrides` lines 1108-1139);
     `nil`-valued overrides remove an interceptor from the chain.

  HOT PATH: fires on every dispatch. On the override-free path (no
  per-frame / per-call `:fx-overrides`, no per-frame / per-call
  `:interceptor-overrides`, no per-frame `:interceptors`),
  `apply-overrides` returns shared empty sentinels and we reuse the
  handler's own `:interceptors` vector directly without `concat`-ing
  an empty extra-interceptors prefix or walking the chain for
  interceptor-overrides."
  [envelope frame frame-record handler-meta]
  (let [{:keys [extra-interceptors fx-overrides icpt-overrides]}
        (apply-overrides envelope frame-record)
        prepended-chain (if (seq extra-interceptors)
                          (vec (concat extra-interceptors (:interceptors handler-meta)))
                          (:interceptors handler-meta))
        base-chain      (apply-icpt-overrides prepended-chain icpt-overrides)
        redaction-paths (privacy/schema-redaction-paths frame base-chain)
        ;; Per rf2-461sp — user-installed `(rf/redact-interceptor paths)`
        ;; interceptors expose their paths on the interceptor map so the
        ;; pre-chain trace projection (`:run-start`, `emit-cascade-
        ;; trailers`) honours them too. Each user `:before` ALSO runs
        ;; during chain execution and extends `:rf/redacted-event`
        ;; in-chain, which is what the schema-redaction interceptor
        ;; (when also installed) composes with. The union here is the
        ;; OUT-OF-CHAIN projection used by emit sites that fire BEFORE
        ;; the chain.
        user-paths      (privacy/user-redaction-paths base-chain)
        redacted-chain  (if (seq redaction-paths)
                          (into [(privacy/schema-redaction-interceptor
                                   redaction-paths)]
                                base-chain)
                          base-chain)
        ;; Per Spec 013 §Drain integration (rf2-u0zz5): PREPEND the
        ;; framework's flow-transform interceptor at the HEAD of the
        ;; dispatch-time chain so its `:after` is the OUTERMOST `:after`
        ;; — it fires after the rest of the `:after` chain (handler body
        ;; + every user / framework `:after`) has fully reshaped the
        ;; pending `:db` effect into the complete app-db form. This is
        ;; load-bearing: a `(rf/path :slice)` interceptor's `:after`
        ;; splices the handler's slice back into the FULL db, so the flow
        ;; transform (which reads full-db `:inputs` paths) MUST run after
        ;; that splice — i.e. outermost. The rest of the `:after` chain
        ;; therefore precedes flows and sees its INPUT; the flow output
        ;; reaches `:fx`, the reactive cascade, and the single `:db`
        ;; install (all of which run after the chain). Added here
        ;; (dispatch-time) rather than baked into the registered handler-
        ;; meta chain so tooling that reads `(handler-meta :event id)
        ;; :interceptors` still sees the user-authored chain with the
        ;; handler-wrapper at its tail.
        full-chain      (into [flows-after-interceptor] redacted-chain)
        initial-ctx     (assemble-initial-ctx envelope frame frame-record fx-overrides)
        all-paths       (into (vec redaction-paths) user-paths)]
    {:full-chain   full-chain
     :initial-ctx  initial-ctx
     :fx-overrides fx-overrides
     :emit-event   (if (seq all-paths)
                     (privacy/redact-event (:event envelope) all-paths)
                     (:event envelope))
     ;; `:schema-sensitive?` strictly tracks the schema-declared
     ;; sensitive path overlap — it drives the scope-meta `:sensitive?`
     ;; stamp on every emitted trace event. User `redact-interceptor` does
     ;; NOT stamp `:sensitive?`; sensitivity is path-marked via the
     ;; schema-slot meta (the handler-meta annotation has been
     ;; removed).
     :schema-sensitive? (boolean (seq redaction-paths))}))

(defn- run-chain
  "Execute the interceptor chain bracketed in performance marks. When
  event-payload validation failed (`event-ok?` false) the handler is
  suppressed via `:rf/skip-handler?` on the initial context — the same
  mechanism the cofx-failure path (Spec 010 step 2) uses — rather than
  skipping the chain wholesale. Per Spec 010 §Per-step recovery step 1
  the handler does not run and the downstream queue continues; per Spec
  002 §Interceptor chain execution rule 2 the chain STILL executes so
  the `:after` pass always runs in full and cleanup-on-`:after`
  interceptors (debug pp/snapshot, Story snapshot capturer) fire even
  on a pre-handler validation failure. Symmetric with the cofx path:
  both failures keep teardown intact.

  Per Spec 009 §Performance instrumentation (rf2-du3i): the
  `performance/mark-and-measure` bracket produces a
  `rf:event:<event-id>` measure entry under prod builds with the perf
  flag enabled. Default-off; under `:advanced` +
  `re-frame.performance/enabled?=false` the bracket DCEs and the call
  collapses to a plain `execute-chain` invocation."
  [event-id full-chain initial-ctx event-ok?]
  (let [ctx (if event-ok?
              initial-ctx
              (assoc initial-ctx :rf/skip-handler? true))]
    (performance/mark-and-measure :event event-id
      (interceptor/execute-chain full-chain ctx))))

(defn- commit-and-flow!
  "Settle the cascade: surface any chain / flow exception, commit the
  (flow-augmented) :db, then walk :fx in source order. Per Spec 002
  §Drain-loop pseudocode. Flows have already run as the outermost
  `:after` inside the chain (rf2-u0zz5), so by the time this fn executes
  the pending `:db` effect is the flow-augmented value; the install here
  is the single deferred commit, and `:fx` walks after it.

  Per Spec 010 §Per-step recovery row 4 (rf2-wkxng / rf2-6m0se): a
  post-commit `:db` schema-validation failure rolls the container
  back to the pre-handler value AND treats the dispatch as failed —
  flows do NOT evaluate and `:fx` does NOT walk. The pre-handler db
  is read from `(get-in final-ctx [:coeffects :db])` (assemble-
  initial-ctx stamps it there). Downstream queued events still drain
  per run-to-completion (handled by `drain-loop!`'s outer pass).

  Per Spec 002 §Cascade propagation: `envelope` is threaded into
  `run-fx-effects!` so reserved-fx defmethods can propagate
  inheritable keys onto child dispatches.

  Per Spec 013 §Drain integration (rf2-u0zz5): flows have ALREADY run
  by the time this fn executes — the framework's OUTERMOST `:after`
  interceptor (`flows-after-interceptor`) transformed the pending `:db`
  effect inside the chain. So `(:db effects)` here is the FLOW-AUGMENTED
  value, and a flow throw is signalled by `(:rf/flow-error final-ctx)`
  rather than an inline `run-flows!` call.

  Atomicity contract (Spec 013 §Failure semantics, Mike 2026-05-24): the
  `:db` install is the single, deferred, all-or-nothing commit boundary.
  ANY pre-install throw — handler, interceptor `:after`, or the flow
  transform — aborts the event: NO install, app-db UNCHANGED, NO
  `:rf.event/db-changed`, NO `:fx`. This is uniform and FREE: the
  handler / interceptor-error path returns `:error` WITHOUT calling
  `commit-db-effect!`, and the flow-throw path's `:after` already
  `dissoc`-ed the pending `:db` effect — so even if the commit ran it
  would install nothing (`commit-db-effect!` is a no-op when no `:db`
  effect is present). `:fx` is the ONLY post-install stage; an fx throw
  does NOT wind back app-db (its side effects may already have fired).

  Returns the dispatch OUTCOME keyword for the always-on event-emit
  record (Spec 009 §Event-emit listener §Record shape):

    :ok          — clean settle (db committed, flows ran, :fx walked).
    :error       — the interceptor chain (handler or interceptor)
                   threw; `emit-handler-exception!` has already fired.
                   No install, app-db unchanged, :fx skipped.
    :rolled-back — post-commit `:db` schema validation rejected the
                   new state and the container was restored to its
                   pre-handler value (Spec 010 row 4); :fx was skipped.
    :flow-error  — a flow's `:output` threw (Spec 013 §Failure
                   semantics); the event aborted — no install, app-db
                   unchanged, no db-changed, :fx skipped.

  All three non-`:ok` values surface to off-box observability shippers
  (Datadog / Sentry / Honeycomb) so a dispatch that rolled back its
  whole `:db` write or aborted on a flow throw is NOT mis-reported as
  a clean `:ok`. A chain exception is reported as `:error` regardless
  of any downstream rollback — it is the proximate, most-actionable
  signal."
  [final-ctx event-id event frame frame-record fx-overrides envelope start-ms]
  (let [effects    (:effects final-ctx)
        error      (:rf/interceptor-error final-ctx)
        flow-error (:rf/flow-error final-ctx)
        db-before  (get-in final-ctx [:coeffects :db])]
    (when error
      (emit-handler-exception! error event-id event frame final-ctx start-ms))
    (cond
      error :error
      ;; Per Spec 013 §Failure semantics (atomicity contract, Mike
      ;; 2026-05-24): a flow's `:output` threw during the outermost
      ;; `:after` flow transform. A flow throw is a PRE-INSTALL throw, so
      ;; the event ABORTS — no install, app-db unchanged, no
      ;; `:rf.event/db-changed`, no `:fx`. The `:after` already `dissoc`-ed
      ;; the pending `:db` effect, so we do NOT call `commit-db-effect!`
      ;; here at all: the only post-install stage (`:fx`) is skipped and
      ;; nothing is committed. Surface the cascade-level
      ;; `:rf.error/flow-eval-exception`; the trace stream is therefore
      ;; `flow/failed → flow-eval-exception` with NO `db-changed`.
      flow-error
      (do
        (emit-flow-eval-exception! flow-error event event-id frame start-ms)
        :flow-error)
      ;; Per Spec 010 §Per-step recovery row 4: `commit-db-effect!`
      ;; returns false when post-commit schema validation rejected the
      ;; new state and rolled the container back to its pre-handler
      ;; value. `:fx` is skipped; the dispatch failed.
      (not (commit-db-effect! effects event-id event frame final-ctx db-before))
      :rolled-back
      :else
      (do
        (run-fx-effects! effects frame frame-record fx-overrides envelope)
        :ok))))

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
  contract holds on both platforms.

  `outcome` is the keyword `commit-and-flow!` returns — `:ok`,
  `:error`, `:rolled-back`, or `:flow-error` — and rides straight onto
  the event-emit record's `:outcome` slot (Spec 009 §Record shape).

  `handler-elapsed-ms` (rf2-hhh92) is the HANDLER-BODY-only wall-clock
  (the interceptor-chain duration, captured before `commit-and-flow!`),
  surfaced onto the dev-only `:rf.event/run-end` trace as
  `:rf.event/elapsed-ms` so the Trace panel's DURATION column reads the
  per-op handler duration — NOT the whole run-end − run-start cascade
  bracket (which also covers fx+subs+views). nil in production (the
  caller's read rides `interop/debug-enabled?`); the `(some? ...)` slot
  then collapses.

  Per rf2-9dk9y two further `:tags` slots ride this emit so the Xray
  Event lens's COEFFECTS / AFTER INTERCEPTORS sections render uniformly
  regardless of whether the handler returned `:fx`:

    `:rf.event/coeffects`    — the USER-INJECTED subset of the
                                handler's final coeffects map (framework
                                defaults `:db` `:event` `:frame`
                                `:source` `:trace-id` filtered out at
                                this boundary). Absent entirely when
                                zero user cofx were injected.
    `:rf.event/after-deltas` — vector of per-`:after` interceptor
                                ctx-delta records `{:rf.icpt/id <id>
                                :rf.icpt/ctx-delta {...}}` populated by
                                `interceptor/execute-chain` for every
                                user-registered `:after` that mutated
                                the context. Absent when no user-`:after`
                                ran. The Xray AFTER INTERCEPTORS section
                                reads it to render an EDN-diff under
                                each row."
  [event-id event emit-event frame outcome start-ms handler-elapsed-ms final-ctx]
  ;; The cofx / after-delta projections are dev-only: their cost rides
  ;; `interop/debug-enabled?` so production CLJS bundles DCE the
  ;; projection AND the `trace/emit!` body below (the emit itself is
  ;; gated the same way internally).
  (let [user-cofx    (when interop/debug-enabled?
                       (fx/user-injected-coeffects (:coeffects final-ctx)))
        after-deltas (when interop/debug-enabled?
                       (not-empty (:rf/icpt-after-deltas final-ctx)))]
    (trace/emit! :rf.event :rf.event/run-end
                 (cond-> {:rf.trace/event-id event-id
                          :rf.event/v        emit-event
                          :frame             frame
                          :rf.trace/phase    :run-end}
                   (some? handler-elapsed-ms)
                   (assoc :rf.event/elapsed-ms handler-elapsed-ms)
                   (some? user-cofx)
                   (assoc :rf.event/coeffects user-cofx)
                   (some? after-deltas)
                   (assoc :rf.event/after-deltas after-deltas)))
    ;; Sticky hook (rf2-f72pd) — always-on per-event observability fan-out
    ;; per rf2-rirbq; survives `:advanced` + `goog.DEBUG=false`.
    (when-let [emit-event! (late-bind/get-fn-cached :event-emit/dispatch-on-event)]
      (let [end-ms     (interop/now-ms)
            elapsed-ms (long (max 0 (- end-ms start-ms)))]
        (emit-event! emit-event
                     event-id
                     frame
                     end-ms
                     outcome
                     elapsed-ms)))))

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
  (refresh-elision-from-schemas! frame)
  (let [{:keys [full-chain initial-ctx fx-overrides emit-event
                schema-sensitive?]}
        (prepare-handler-ctx envelope frame frame-record handler-meta)
        ;; Per rf2-j20a7 / Spec 005 §Level 4: tag the in-flight envelope
        ;; as machine-originated when THIS handler is a machine (its
        ;; registration meta carries `:rf/machine? true`, stamped by
        ;; re-frame.machines `reg-machine*`). The tagged envelope is the
        ;; `parent-envelope` threaded into `do-fx`; `child-dispatch-opts`
        ;; (re-frame.fx) copies the flag onto every `:dispatch` /
        ;; `:dispatch-later` child emitted during this handler's fx walk,
        ;; so those continuation events front-of-queue insert (see
        ;; `enqueue-envelope!`). The cut is the dispatch's ORIGIN — an
        ;; event that merely TARGETS a machine but originates elsewhere
        ;; carries no flag and stays FIFO. `:raise` is untouched: it
        ;; never reaches the router queue (it drains in-memory inside the
        ;; machine handler invocation, pre-commit).
        envelope   (cond-> envelope
                     (:rf/machine? handler-meta)
                     (assoc :rf.machine/internal? true))
        ;; The schema-derived `:rf/sensitive?` key drives the scope's
        ;; `:sensitive?` trace-event stamp (read by `handler-scope-from-
        ;; meta`). Path-marked via app-schema slot meta; the handler-
        ;; meta `:sensitive?` annotation has been removed.
        scope-meta (cond-> handler-meta
                     schema-sensitive? (assoc :rf/sensitive? true))]
    (trace/with-handler-scope
      (trace/handler-scope-from-meta :event event-id scope-meta)
      (let [start-ms  (interop/now-ms)
            _         (trace/emit! :rf.event :rf.event/run-start
                                   {:rf.trace/event-id event-id
                                    :rf.event/v        emit-event
                                    :frame             frame
                                    :source            (:source envelope)
                                    :rf.trace/trace-id (:trace-id envelope)
                                    :rf.trace/phase    :run-start})
            event-ok? (validate-event! event-id event handler-meta)
            final-ctx (run-chain event-id full-chain initial-ctx event-ok?)
            ;; rf2-hhh92: the HANDLER-BODY-only elapsed — the interceptor
            ;; chain (`run-chain`) duration, captured BEFORE
            ;; `commit-and-flow!` (db commit + flows + fx walk). This is
            ;; distinct from the `:rf.event/run-end` whole-cascade bracket
            ;; (run-end − run-start, which also covers fx+subs+views). The
            ;; Trace panel's DURATION column reads THIS handler-body figure
            ;; off the `:rf.event/run-end` tag. Dev-only: the read rides
            ;; `interop/debug-enabled?` so production DCEs it.
            handler-elapsed-ms (when interop/debug-enabled?
                                 (- (interop/now-ms) start-ms))
            ;; `commit-and-flow!` returns the dispatch outcome keyword
            ;; (:ok / :error / :rolled-back / :flow-error) so the always-on
            ;; event-emit record reflects schema-rollback and flow-throw
            ;; failures, not just the chain exception.
            outcome   (commit-and-flow! final-ctx event-id event frame
                                        frame-record fx-overrides envelope start-ms)]
        (emit-cascade-trailers! event-id event emit-event frame outcome
                                start-ms handler-elapsed-ms final-ctx)))))

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
          (diag/handle-no-handler! envelope event-id event frame)
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
  "Tail-path for the depth-limit branch of `drain!`. Per Spec 002
  §Drain versus event — the epoch unit (rf2-u6jsj/rf2-nj6p7): the epoch
  boundary is the dequeued EVENT, so the events that already ran in this
  drain each settled their own DURABLE `:ok` epoch (and their own db
  write) as they completed — there is NO whole-drain rollback under
  per-event epochs. The depth limit stops processing the NEXT event (the
  halting event, still at the head of the queue); the work that already
  ran is a sequence of complete, individually-atomic events.

  Per rf2-v0jwt §Outcomes: commit a `:halted-depth` epoch record so
  devtools (Xray, re-frame2-pair) get a clear 'drain halted here'
  marker following the runaway `:ok` epochs. The halting event never ran,
  so its record's `:db-before` / `:db-after` both equal the current
  (last-settled) db value and its buffer is empty — `commit-halt-record!`
  synthesises the record from the halting event's trigger. Listeners
  receive it like any other; `restore-epoch` refuses non-`:ok` targets.

  ## Reconcile with Spec 002 rule 3 (NOTED for spec-tightening, rf2-nj6p7)

  Rule 3 (and the `drain-depth-limit.edn` conformance fixture) describe
  WHOLE-DRAIN atomic rollback to a pre-drain snapshot, written for the
  pre-rf2-u6jsj per-drain epoch model. The merged spec PR #1948 redefined
  the epoch as per-event but did NOT update rule 3 / the fixture
  (\"impl follow-up rf2-nj6p7\"). Under per-event epochs, whole-drain
  rollback is incoherent — each settled event is independently atomic and
  durable. This impl follows the per-event model the merged spec defines:
  already-settled siblings are durable; only the halting event (which
  never ran) gets a `:halted-depth` marker. Rule 3's pre-drain-rollback
  text and the fixture need tightening to the per-event boundary."
  [frame-id router depth last-event]
  (let [{:keys [queue]} @router
        queue-size      (count queue)
        ;; The halting event — the next one that would have been dequeued.
        ;; It never runs; its event vector pins the `:halted-depth` marker.
        ;; The queue holds ENVELOPES (`build-envelope` maps), so reach the
        ;; raw `[event-id …]` vector through `:event`. Falls back to
        ;; `last-event` (the most-recently-run event) if the queue is empty
        ;; at the halt seam (defensive — the depth-exceed path always has a
        ;; pending child under the runaway-cascade pattern that trips it).
        halting-event   (or (:event (peek queue)) last-event)
        ;; Current durable db value — the state the last-settled event
        ;; left behind. The halting event makes no write, so :db-before
        ;; equals :db-after on its record.
        db-now          (frame/frame-app-db-value frame-id)
        halt-reason     {:operation  :rf.error/drain-depth-exceeded
                         :depth      depth
                         :queue-size queue-size
                         :last-event last-event}]
    (trace/emit-error! :rf.error/drain-depth-exceeded
                       {:frame      frame-id
                        :depth      depth
                        :queue-size queue-size
                        :last-event last-event
                        ;; Per rf2-nj6p7: no whole-drain rollback under
                        ;; per-event epochs — the already-settled events
                        ;; are durable. `:rollback? false` reflects that.
                        :rollback?  false
                        :recovery   :no-recovery})
    (swap! router assoc :queue interop/empty-queue :scheduled? false)
    (when-let [commit-halt! (late-bind/get-fn-cached :epoch/commit-halt-record!)]
      ;; The halting event never ran, so the capture buffer is empty and
      ;; `settle!` would skip; `commit-halt-record!` commits regardless,
      ;; pinning the halting event's trigger. :db-before equals :db-after
      ;; — the halting event made no write.
      (commit-halt! frame-id db-now db-now :halted-depth halt-reason
                    halting-event))))

(defn- settle-event-epoch!
  "Commit the just-completed event's epoch (Tool-Pair §Time-travel). Per
  Spec 002 §Drain versus event — the epoch unit (rf2-u6jsj/rf2-nj6p7):
  the epoch boundary is the dequeued EVENT, not the drain-settle. Called
  by `run-one-pass!` after each `process-event!` returns, with that one
  event's own pre-/post-cascade db snapshot pair. The epoch surface
  harvests the in-flight capture buffer — which, within a frame's
  single-threaded run-to-completion drain, holds exactly this event's
  six-domino cascade (the previous event already harvested its own at its
  settle). A machine macrostep ran inside `process-event!` and rides this
  one event's buffer / epoch (Spec 005 §macrostep), so `:raise` /
  `:always` microsteps do not allocate a new epoch.

  `settle!` itself skips an empty buffer (a rejected/aborted dispatch that
  never fired `:event/run-start`), so a no-handler / frame-destroyed early
  exit commits no misleading record."
  [frame-id db-before db-after]
  (when-let [settle! (late-bind/get-fn-cached :epoch/settle!)]
    (settle! frame-id db-before db-after)))

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
  emit a single `:rf.frame/drain-interrupted` lifecycle trace carrying
  `:dropped-count` (per Spec 009 §`:rf.frame/drain-interrupted`
  and Spec-Schemas §DrainInterruptedTags), and commit a
  `:halted-destroy` epoch record per rf2-v0jwt §Outcomes.

  In-flight events are not affected — they have already been dequeued
  and `process-event!` ran them to completion before this check fires
  (run-to-completion per Spec 002 §Rules rule 1). Only events still
  in the queue at the moment of the check are dropped.

  The check fires AFTER `process-event!` returns and BEFORE the next
  `take-event!` — same seam as `handle-depth-exceeded!`.

  Per rf2-v0jwt: the partial epoch record's `:db-after` is read from
  the live container BEFORE the frame's container is torn down (the
  destroy hook flips `:destroyed?` but doesn't drop the container atom
  itself, so the post-cascade db value is still readable). On the
  common path (a handler called `destroy-frame!` on its own frame
  synchronously) the destroy hook ran inside the handler — the
  capture-buffer was harvested by `on-frame-destroyed`'s belt-and-
  braces clear, leaving `settle!` to commit an empty-events record
  pinning `:outcome :halted-destroy`. Devtools still get a halted-
  cascade record with the right outcome; the absent `:trace-events`
  reflects the destroy-during-handler reality."
  [frame-id router db-before]
  (let [dropped     (count (:queue @router))
        ;; The container may have been dissoc'd by destroy-frame!'s
        ;; step 6 (which runs BEFORE the destroy hook's epoch-side
        ;; cleanup but only flips :destroyed? — the atom value itself
        ;; survives). `frame-app-db-value` returns nil for a destroyed
        ;; frame; fall back to db-before so the record's :db-after
        ;; slot is never nil-from-destroy-race (the record's slot
        ;; semantics are 'whatever the runtime settled to' — for a
        ;; destroy that consumed the container, db-before is the
        ;; closest meaningful approximation).
        db-after    (or (frame/frame-app-db-value frame-id) db-before)
        halt-reason {:operation     :rf.frame/drain-interrupted
                     :dropped-count dropped}]
    (swap! router assoc :queue interop/empty-queue :scheduled? false)
    (trace/emit! :rf.frame :rf.frame/drain-interrupted
                 {:frame         frame-id
                  :dropped-count dropped})
    (when-let [settle! (late-bind/get-fn-cached :epoch/settle!)]
      (settle! frame-id db-before db-after :halted-destroy halt-reason))))

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

  Per rf2-u6jsj/rf2-nj6p7 §Drain versus event — the epoch unit: the epoch
  boundary is the dequeued EVENT, not the drain. Each event takes its OWN
  pre-cascade `db-before` snapshot immediately before `process-event!` and
  its OWN post-cascade `db-after` immediately after; `settle-event-epoch!`
  commits one `:rf/epoch-record` per event. A drain that processes a
  parent and an `:fx [[:dispatch …]]` child it queued therefore commits
  TWO records — one per event — even though both settled in the same
  drain. (The frame-level `db-before` the caller passes is retained only
  for the destroy-interrupt path, which reads it as a fallback when the
  destroyed frame's container is no longer readable.)"
  [frame-id router drain-db-before drain-depth]
  (loop [depth      0
         last-event nil]
    (cond
      (>= depth drain-depth)
      (do (handle-depth-exceeded! frame-id router depth last-event)
          ::halt)

      ;; Per rf2-68kok: destroyed-frame check fires BEFORE the next
      ;; dequeue. A handler in the just-completed event may have
      ;; called `destroy-frame!` on its own frame; the spec calls for
      ;; interrupting the drain at this exact seam — drop the
      ;; remaining queue, emit one `:rf.frame/drain-interrupted`
      ;; lifecycle event, halt.
      ;;
      ;; Per rf2-v0jwt: the just-completed event already ran in full
      ;; (run-to-completion) AND already settled its own per-event epoch
      ;; (rf2-nj6p7) — that record is durable. Devtools (Xray, re-frame-
      ;; re-frame2-pair) also receive a `:halted-destroy` record for the
      ;; interrupted drain from the destroy hook / `handle-drain-
      ;; interrupted!`. `restore-epoch` refuses these records, preserving
      ;; the original "time-travel never lands in a misleading state"
      ;; invariant.
      (frame/frame-disposed-for-drain? frame-id)
      (do (handle-drain-interrupted! frame-id router drain-db-before)
          ::halt)

      :else
      (if-let [envelope (take-event! router)]
        ;; Per rf2-nj6p7: per-event epoch boundary. Snapshot this event's
        ;; OWN db-before, run it to completion, snapshot its db-after, and
        ;; settle its epoch — before the next event is dequeued.
        (let [db-before (frame/frame-app-db-value frame-id)]
          (process-event! envelope)
          (let [db-after (frame/frame-app-db-value frame-id)]
            (settle-event-epoch! frame-id db-before db-after))
          (recur (inc depth) (:event envelope)))
        ::settled))))

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
  release window. Each pass snapshots a frame-level `db-before` that the
  destroy-interrupt path uses as a fallback; per-event epoch snapshots
  (rf2-nj6p7) are taken inside `run-one-pass!` per dequeued event, not
  here."
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
  on :tags too. Per rf2-t1lxr, :rf/dispatch-origin (Xray A.5 closed-
  enum functional source) also rides on :tags so Xray's L2 epoch
  timeline can render the per-row origin tag. Spec elision is
  automatic — trace/emit! short-circuits when interop/debug-enabled?
  is false at compile time.

  Per rf2-qsjda: queue-time `:rf.trace/no-emit?` consideration. The
  `*handler-scope*` binding's `:no-emit?` slot doesn't exist yet at
  enqueue time, so we read the flag directly off the target handler's
  registration meta and short-circuit the `:event/dispatched` emit
  when set. Without this, a Xray-style bookkeeping handler would
  have its enqueue trace delivered to listeners (re-entering the
  consumer's trace-cb) before the handler-scope binding ever took
  effect.

  Per rf2-twt7m Change 1: hoist `:rf.trace/call-site` onto this
  success-path emit too. The envelope's `:call-site` was stamped by
  the surface `dispatch` / `dispatch-sync` macro (rf2-ts1a); we
  publish it through `trace/with-call-site` so `build-event` hoists
  it onto the trace event via the existing scope-driven hoist path
  (same machinery the error path uses). Without this, the Event lens
  redesign (rf2-zh2qc) and any consumer building click-to-source UX
  on the enqueue trace would lose the dispatch-site coord."
  [envelope sync?]
  (let [event        (:event envelope)
        event-id     (when (vector? event) (first event))
        handler-meta (when event-id (registrar/lookup :event event-id))
        no-emit?     (trace/no-emit?-from-meta handler-meta)]
    (when-not no-emit?
      (trace/with-call-site (:call-site envelope)
        (trace/emit! :rf.event :rf.event/dispatched
                     (cond-> {:rf.event/v         event
                              :frame              (:frame envelope)
                              :rf.event/origin    (:origin envelope)
                              :rf/dispatch-origin (:rf/dispatch-origin envelope)
                              :source             (:source envelope)
                              :rf.event/sync?     sync?}
                       (:dispatch-id envelope)
                       (assoc :rf.trace/dispatch-id (:dispatch-id envelope))
                       (:parent-dispatch-id envelope)
                       (assoc :rf.trace/parent-dispatch-id (:parent-dispatch-id envelope))
                       ;; Per rf2-5qp4g: optional per-source-kind detail
                       ;; map (e.g. `{:ms 500}` for `:dispatch-later`)
                       ;; so the Epoch panel's DISPATCH source-kind
                       ;; enrichment (spec/021 §9.1.6.3) can render
                       ;; kind-specific chrome. Only stamped when the
                       ;; envelope carried `:source-detail` (the
                       ;; substrate dispatch site opt-in).
                       (:source-detail envelope)
                       (assoc :rf.event/source-detail (:source-detail envelope))))))))

(defn- front-insert-machine-internal
  "Return `q` (a PersistentQueue of envelopes) with `envelope` spliced in
  at the boundary between the machine-internal PREFIX and the external
  TAIL — i.e. after any already-queued machine-internal envelopes but
  ahead of the first external one.

  Why a boundary splice, not a head `cons`: each sibling machine-internal
  dispatch from one macrostep (`:fx [[:dispatch :a] [:dispatch :b]]`,
  walked left-to-right by `do-fx`) is a SEPARATE `dispatch!` call, so this
  fn is invoked once per sibling. A plain head-push would reverse them
  (`:b` ends up ahead of `:a`). Inserting each new internal envelope at
  the END of the existing internal prefix keeps siblings in source order
  (`[:a :b …external]`) while still placing the whole internal run ahead
  of every external event already on the queue.

  PersistentQueue has no native splice, so the queue is rebuilt: take the
  leading run of machine-internal envelopes, append `envelope`, then the
  external remainder. `split-with` on `:rf.machine/internal?` is exact —
  external envelopes never carry the flag."
  [q envelope]
  (let [[internal external] (split-with :rf.machine/internal? q)]
    (into interop/empty-queue (concat internal [envelope] external))))

(defn- enqueue-envelope!
  "Insert `envelope` into the frame's router queue. Per Spec 002, ordinary
  dispatches go to the BACK (plain FIFO via `conj` on the PersistentQueue).

  Per rf2-j20a7 / Spec 005 §Level 4 — the one exception: a machine-
  internal continuation envelope (`:rf.machine/internal? true`, stamped
  by `build-envelope` from the machine-tagged child opts) leap-frogs
  ahead of any already-queued EXTERNAL events, so the machine settles its
  macrostep to quiescence before the next external event runs (SCXML
  'internal before external'). It is spliced in by
  `front-insert-machine-internal` AFTER any sibling machine-internal
  envelopes already queued this macrostep, so source order is preserved
  among siblings (first emitted is dequeued first).

  Front-of-queue changes ORDER ONLY, not granularity: the leap-frogged
  envelope is still a separately-dequeued event with its own epoch (per
  Spec 002 §Drain versus event and Spec 005 §Level 4). `:raise` is a
  different lever — it never reaches this queue (it drains in-memory,
  intra-macrostep, inside the machine handler invocation)."
  [router envelope]
  (if (:rf.machine/internal? envelope)
    (swap! router update :queue front-insert-machine-internal envelope)
    (swap! router update :queue conj envelope)))

(defn dispatch!
  "Append the event to the target frame's router queue. Per Spec 002:
  FIFO at the runtime layer. The drain loop picks it up in this same
  drain cycle (run-to-completion).

  Per rf2-j20a7 / Spec 005 §Level 4: the single exception to FIFO is a
  machine-internal continuation event (a dispatch emitted from a
  machine's own processing), which `enqueue-envelope!` inserts at the
  FRONT of the queue so the machine settles its macrostep before the
  next external event. The cut is the dispatch's ORIGIN (machine
  processing), not its target — an event that merely targets a machine
  but originates from user code / the UI / a non-machine effect stays
  FIFO at the back.

  Per rf2-ts1a: the runtime-callable fn form (`re-frame.core/dispatch*`
  in public API terms). The macro form `re-frame.core/dispatch` stamps
  an `:rf.trace/call-site` onto `opts` at compile time; from there it
  rides the envelope and gets bound around the handler chain's
  invocation in `process-event!`.

  Canonical `event` shape is `[<id>]`, `[<id> <single-scalar>]`, or
  `[<id> <map>]` — best practice, not enforced. Variadic vectors are
  tolerated for v1-migration / caller convenience. See spec/Conventions.md
  §Canonical event-vector shape."
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
         (enqueue-envelope! router envelope)
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
           (when-let [other-id (diag/other-frame-mid-drain (:frame envelope))]
             (diag/emit-cross-frame-warning! (:frame envelope) other-id event)))
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
