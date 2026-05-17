(ns re-frame.fx
  "Effect interpreter (do-fx) and reserved fx-id table.

  Per Spec 002 §`:fx` ordering and atomicity guarantees:
    1. :db commits first, atomically.
    2. :fx entries process in source order.
    3. Each fx-handler runs synchronously before the next entry begins.
    4. Subscriptions observe the post-:db state.

  Reserved fx-ids (per Conventions §Reserved fx-ids):
    :dispatch         — runtime, intra-frame dispatch (back of router queue)
    :dispatch-later   — runtime, delayed dispatch
    :raise            — machine-internal (machine handler routes locally)
    :rf.fx/reg-flow   — runtime, register a flow (Spec 013)
    :rf.fx/clear-flow — runtime, clear a flow

  The machine fx-ids `:rf.machine/spawn` and `:rf.machine/destroy` are
  registered by `re-frame.machines` (ships in `day8/re-frame2-machines`)
  at its ns-load time via the regular `reg-fx` path. They are NOT
  reserved in core's case-block — apps that don't pull in the machines
  artefact don't carry the trace strings or the handler for them."
  (:require [re-frame.registrar :as registrar]
            [re-frame.interop :as interop]
            [re-frame.late-bind :as late-bind]
            [re-frame.performance :as performance
             #?@(:cljs [:include-macros true])]
            [re-frame.source-coords :as source-coords]
            [re-frame.trace :as trace
             #?@(:cljs [:include-macros true])]))

#?(:clj (set! *warn-on-reflection* true))

;; ---- registration ---------------------------------------------------------

(defn reg-fx
  "Register an effect handler under `id`. The handler runs when a
  `reg-event-fx` returns an effect-map carrying `[id args]` inside its
  `:fx` vector — `{:fx [[:my-fx args] ...]}`.

  Handler signature: `(fn [ctx args] ...)` — **v2 changed from v1**.

    `ctx`  is a small map carrying:
             `:frame` — the active frame id (Spec 002 §`:fx` ordering)
             `:event` — the originating event vector (Spec 014 §Reply
                        addressing). The fx may capture the originating
                        `event-id` to address replies back without a
                        separate cofx-injection step.
    `args` is the second element of the `[id args]` pair as emitted by
           the event handler (any value — map, vector, scalar).

  Shapes:

      (reg-fx :id                                  (fn [ctx args] ...))
      (reg-fx :id {:doc \"...\" :platforms #{:client}} (fn [ctx args] ...))

  Optional metadata keys:

      :doc        one-sentence what-and-why; surfaces via
                  `(rf/handler-meta :fx id)`.
      :spec       Malli schema for `args` (per Spec 010 §:spec on fx
                  registrations).
      :platforms  set of `#{:client :server}`; default
                  `#{:client :server}`. The fx is skipped on platforms
                  not in the set (`:rf.fx/skipped-on-platform` warning
                  trace).

  Returns `id`.

  Example:

      (rf/reg-fx :my/notify
        {:doc       \"Show a toast notification.\"
         :platforms #{:client}}
        (fn [_ctx {:keys [message level]}]
          (js/window.toast level message)))

      ;; Consumed from an event handler:
      (rf/reg-event-fx :user/login-failed
        (fn [_ [_ reason]]
          {:fx [[:my/notify {:level :error :message (str \"Login failed: \" reason)}]]}))

  Framework-shipped fx (`:dispatch`, `:dispatch-later`, `:rf.http/managed`,
  `:rf.nav/push-url`, ...) are documented in `spec/API.md §Effect-map
  shape` and their per-feature Spec; introspect via
  `(rf/handler-meta :fx <id>)`.

  See also: `reg-cofx` (the input-side counterpart), `clear-fx`,
  `reg-event-fx` (the consumer)."
  [id metadata-or-handler & maybe-handler]
  (let [[meta handler-fn]
        (if (map? metadata-or-handler)
          [metadata-or-handler (first maybe-handler)]
          [{} metadata-or-handler])]
    (registrar/register! :fx id (assoc (source-coords/merge-coords meta)
                                       :handler-fn handler-fn))
    id))

(defn clear-fx
  "Unregister an fx handler. Zero-arity clears every registered fx;
  one-arity clears the named one. Hot-reload tools and test fixtures
  call this between rebuilds.

  Returns nil. See also: `reg-fx`, the user-facing surface `rf/clear-fx`
  (this is the underlying fn — they point at the same value)."
  ([] (registrar/clear-kind! :fx))
  ([id] (registrar/unregister! :fx id)))

;; ---- the platform predicate -----------------------------------------------

(defn runs-on-platform?
  "Does the `:platforms` metadata permit `active-platform`?

  Per Spec 011 §634-642 the `:platforms` slot applies symmetrically to
  `reg-fx` AND `reg-cofx`. Default is `#{:client :server}` (both
  permitted). The same predicate body answered both questions —
  re-frame.cofx aliases this fn so the contract has one definition
  (rf2-4ymm0 SP6)."
  [meta active-platform]
  (let [platforms (:platforms meta #{:client :server})]
    (contains? platforms active-platform)))

;; Local alias kept for internal call sites' readability — `(fx-runs-on-
;; platform? meta plat)` reads better at `do-fx` than `(runs-on-platform?
;; meta plat)` where the fx-vs-cofx context is implicit.
(def ^:private fx-runs-on-platform? runs-on-platform?)

;; ---- do-fx ----------------------------------------------------------------

(declare dispatch-fx-handler)

;; ---- reserved fx-id table -------------------------------------------------
;;
;; Per Conventions §Reserved fx-ids — `:dispatch`, `:dispatch-later`,
;; `:rf.fx/reg-flow`, `:rf.fx/clear-flow` resolve to runtime-internal
;; callables held behind `late-bind` hooks (avoiding cyclic loads against
;; the router and flows namespaces). Each entry maps the fx-id to a small
;; body-fn so the case-block in `handle-one-fx` is a dispatch off this
;; table — adding a reserved fx-id is a data edit, not a code edit.
;;
;; Body-fn signature: `(fn [frame-id args])`. It is invoked inside the
;; perf bracket; on success it returns; the caller emits `:rf.fx/handled`
;; uniformly. When a hook is unregistered (the producing artefact is not
;; on the classpath) the body-fn is a no-op — matching the pre-existing
;; `when-let [f (late-bind/get-fn ...)]` shape across all four sites.
;;
;; `:dispatch-later` carries its own body because it wraps the hook call
;; in `set-timeout!` and destructures `{:keys [ms event]}` from args; the
;; other three are uniform `(hook args {:frame frame-id})` calls.

(defn- call-frame-scoped-hook!
  "Resolve `hook-key` and invoke it with `(hook args {:frame frame-id})`.
  When the hook is unregistered (producing artefact absent), this is a
  no-op — matches the pre-refactor `when-let` shape."
  [hook-key frame-id args]
  (when-let [f (late-bind/get-fn hook-key)]
    (f args {:frame frame-id})))

;; Inheritable envelope fields — copied from parent to child when
;; `:dispatch` / `:dispatch-later` queue a new envelope. Per Spec 002
;; §Cascade propagation (line 1162) and §Drain-loop pseudocode
;; `inheritable-envelope-keys` (lines 947-952). `:event` and
;; `:dispatched-at` are NOT inherited — the child gets its own.
(def ^:private inheritable-envelope-keys
  [:frame :fx-overrides :interceptor-overrides :trace-id :origin :source])

(defn- child-dispatch-opts
  "Project the parent envelope's inheritable keys onto the opts map for a
  child dispatch. Per Spec 002 §Cascade propagation: the dispatched
  child inherits `:frame`, `:fx-overrides`, `:interceptor-overrides`,
  `:trace-id`, `:origin`, `:source`. When `parent-envelope` is nil
  (caller did not thread one through — legacy routing-artefact callers
  or test fixtures), falls back to `{:frame frame-id}` so single-key
  propagation still holds."
  [frame-id parent-envelope]
  (if parent-envelope
    (select-keys parent-envelope inheritable-envelope-keys)
    {:frame frame-id}))

(def ^:private reserved-fx-handlers
  "Reserved fx-id → body-fn `(fn [frame-id parent-envelope args])`.
  Driven by `handle-one-fx`; emit of `:rf.fx/handled` lives in the
  caller so each reserved fx surfaces exactly one success trace,
  uniformly.

  `parent-envelope` is the dispatch envelope of the event that produced
  this fx vector. Per Spec 002 §The binary fx-handler signature (line
  603) and §Drain-loop pseudocode (lines 916, 961-963), the reserved-fx
  defmethods for `:dispatch` / `:dispatch-later` read the parent envelope
  to propagate inheritable keys (`:fx-overrides`, `:interceptor-overrides`,
  `:trace-id`, `:origin`, `:source`) onto the child dispatch — per
  Spec 002 §Cascade propagation."
  {:dispatch
   ;; Append to back of the frame's router queue. Per Spec 002
   ;; §Cascade propagation, the child envelope inherits the parent's
   ;; `:fx-overrides` / `:interceptor-overrides` / `:trace-id` /
   ;; `:origin` / `:source`.
   (fn [frame-id parent-envelope args]
     ;; Sticky hook (rf2-f72pd) — `:router/dispatch!` is published once
     ;; at re-frame.router load and never withdrawn; this fires per
     ;; `:dispatch` fx invocation.
     (when-let [f (late-bind/get-fn-cached :router/dispatch!)]
       (f args (child-dispatch-opts frame-id parent-envelope))))

   :dispatch-later
   ;; Delayed dispatch — wraps the same router hook in `set-timeout!`.
   ;; Inheritable keys are projected at fx-firing time and captured in
   ;; the closure so the deferred dispatch carries the parent envelope's
   ;; overrides into the eventual child cascade.
   (fn [frame-id parent-envelope {:keys [ms event]}]
     (let [opts (child-dispatch-opts frame-id parent-envelope)]
       (interop/set-timeout!
         (fn []
           ;; Sticky hook (rf2-f72pd) — same as above; the timer
           ;; callback fires per scheduled :dispatch-later.
           (when-let [dispatch! (late-bind/get-fn-cached :router/dispatch!)]
             (dispatch! event opts)))
         ms)))

   ;; Per Spec 013 — flows are frame-scoped. The flow registers against
   ;; the dispatching frame.
   ;;
   ;; Both fx-ids route through the SAME hooks the public API uses
   ;; (`:flows/reg-flow` / `:flows/clear-flow`). Pre-rf2-7ppmo the
   ;; flows artefact published four hooks — two API-shape, two fx-
   ;; shape — but the API-shape hooks already accept `(arg opts)` with
   ;; opts carrying `:frame`, and `call-frame-scoped-hook!` passes
   ;; `{:frame frame-id}` as the second arg. The fx-shape hooks were
   ;; one-line pass-throughs; consolidated to two hooks.
   :rf.fx/reg-flow
   (fn [frame-id _parent-envelope args]
     (call-frame-scoped-hook! :flows/reg-flow frame-id args))

   :rf.fx/clear-flow
   (fn [frame-id _parent-envelope args]
     (call-frame-scoped-hook! :flows/clear-flow frame-id args))})

(defn- resolve-fx-with-overrides
  "Apply fx-id overrides per Spec 002 §Per-frame and per-call overrides.

  Three override-value shapes are honoured (per [002 §`:fx-overrides`](spec/002-Frames.md#fx-overrides--replace-fx-handlers)):

    1. **Missing key** — no override; the original fx-id flows through.
    2. **Keyword value** — id-redirect: the registered fx at the target id
       runs in place of the original. If the target is not registered,
       emit `:rf.error/override-fallthrough` and fall back to the original
       fx-id. This is the **pattern-level**, portable form (SSR-safe).
    3. **Function value** `(fn [m args] ...)` — CLJS reference convenience
       for test fixtures and story decorators. The fn runs in place of the
       registered fx; no registry lookup against the original fx-id is
       performed. Spec/002 marks this form as a CLJS-reference local
       affordance (not portable across the wire); the JVM-side reference
       (this code) supports it too — `.cljc` is single-source.

  Returns the resolved fx-id (keyword); for the fn-value branch, returns
  the original-fx-id (used only for trace shape — the actual handler
  invocation goes through `:rf.fx/override-applied` and the synthesised
  meta returned by `resolved-fx-meta`)."
  [original-fx-id overrides]
  (if (contains? overrides original-fx-id)
    (let [override-target (get overrides original-fx-id)]
      (cond
        ;; (3) function value — CLJS-reference convenience.
        (fn? override-target)
        (do
          (trace/emit! :fx :rf.fx/override-applied
                       {:from original-fx-id :to ::fn-value})
          original-fx-id)

        ;; (2) id-redirect to a registered fx.
        (keyword? override-target)
        (if (registrar/lookup :fx override-target)
          (do
            (trace/emit! :fx :rf.fx/override-applied
                         {:from original-fx-id :to override-target})
            override-target)
          (do
            (trace/emit-error! :rf.error/override-fallthrough
                               {:failing-id     original-fx-id
                                :overrides-map  overrides
                                :looked-up-id   override-target
                                :reason         (str "Override redirected `"
                                                     original-fx-id
                                                     "` to `"
                                                     override-target
                                                     "`, which is not registered. Using the registered `"
                                                     original-fx-id
                                                     "` instead.")
                                :recovery       :replaced-with-default})
            original-fx-id))

        :else
        ;; Neither fn nor keyword — treat as "no override" and fall
        ;; through to the original fx. Includes `nil` (documented in
        ;; spec/002 §`:fx-overrides` as a noop-style placeholder).
        original-fx-id))
    original-fx-id))

(defn- resolved-fx-meta
  "Return the fx-handler meta to invoke for `original-fx-id` under
  `overrides`. The fn-value branch synthesises a transient meta that
  carries the user-supplied lambda as `:handler-fn`; the id-redirect
  and no-override branches look up the registrar entry under the
  resolved fx-id.

  Returns `nil` when no handler is resolvable (the caller then emits
  `:rf.error/no-such-fx`)."
  [original-fx-id resolved-fx-id overrides]
  (let [override (get overrides original-fx-id)]
    (if (and (contains? overrides original-fx-id)
             (fn? override))
      ;; Function-value override — synthesise a meta with the user's fn.
      ;; `:platforms` defaults to both so the fn is callable from JVM and
      ;; browser tests alike (the override is a test/story affordance —
      ;; gating it by platform would surprise the test author).
      {:handler-fn override
       :platforms  #{:client :server}}
      (registrar/lookup :fx resolved-fx-id))))

(defn- emit-handled!
  "Emit a `:rf.fx/handled` success trace for a dispatched fx. Per Spec-Schemas
  §`:rf/epoch-record` `:effects` projection: every dispatched fx surfaces
  one entry, with `:outcome :ok` for the success path. The epoch projection
  consumes this trace; pair tools route off it without re-folding the raw
  trace stream.

  When called inside the fx-handler's `*handler-scope*` binding (the
  user-registered fx branch), `emit!` hoists the fx handler's
  registration coord onto the emitted event's `:rf.trace/trigger-
  handler` slot — so consumers can jump to the fx's `reg-fx` site
  from the success trace. Reserved fx-id calls (`:dispatch`,
  `:dispatch-later`, `:rf.fx/reg-flow`, `:rf.fx/clear-flow`) emit
  outside any fx-handler binding; the outer event handler's scope
  (if any) stamps the event handler's coord instead, which is the
  right attribution for those — they don't have their own
  registration site."
  [fx-id args frame-id]
  (trace/emit! :fx :rf.fx/handled
               {:fx-id   fx-id
                :fx-args args
                :frame   frame-id}))

(defn handle-one-fx
  "Process one [fx-id args] pair. Falls into one of three buckets:
   1. Reserved fx-id with runtime handling (:dispatch, :dispatch-later, :rf.fx/...).
   2. User-registered fx looked up via registrar.
   3. Unknown fx-id — emit :rf.error/no-such-fx and continue.

  Successful dispatches emit `:rf.fx/handled` so the epoch `:effects`
  projection records one entry per dispatched fx (per Spec-Schemas
  §`:rf/epoch-record`). Warning and error paths emit their existing
  traces (`:rf.fx/skipped-on-platform`, `:rf.error/fx-handler-exception`,
  `:rf.error/no-such-fx`) and do NOT additionally emit `:rf.fx/handled`,
  so the projection stays one-entry-per-fx.

  `origin-event` (when supplied) is the originating event vector, threaded
  through to the user-registered fx handler's ctx so handlers like
  `:rf.http/managed` (Spec 014 §Reply addressing) can address replies back
  to the originator without a separate cofx-injection step.

  `parent-envelope` (when supplied) is the dispatch envelope of the
  originating event. Per Spec 002 §The binary fx-handler signature
  (line 603) and §Drain-loop pseudocode (lines 916, 961-963) it is
  exposed on the fx-handler ctx at `(:envelope m)` — reserved-fx
  defmethods (`:dispatch`, `:dispatch-later`) read it to propagate
  inheritable envelope keys onto child dispatches per
  §Cascade propagation. User fxs typically only read `(:frame m)`.

  Public so that fx wrappers (per Spec 012 §Navigation tokens
  `:rf.route/with-nav-token`, and any future single-fx re-entry helper)
  can route a single inner fx entry through the same machinery as the
  outer walk — without re-emitting the `:event/do-fx` boundary marker
  that `do-fx` terminates each walk with. `do-fx` remains the entry
  point for the whole `:fx` vector."
  ([frame-id pair active-platform overrides origin-event]
   (handle-one-fx frame-id pair active-platform overrides origin-event nil))
  ([frame-id [original-fx-id args] active-platform overrides origin-event parent-envelope]
  (let [fx-id (resolve-fx-with-overrides original-fx-id overrides)
        resolved-meta (resolved-fx-meta original-fx-id fx-id overrides)
        origin-event-id (when (vector? origin-event) (first origin-event))]
   ;; Per Spec 009 §Performance instrumentation (rf2-du3i): every fx
   ;; invocation — reserved or user-registered — runs inside a perf
   ;; bracket so prod builds with the perf flag enabled produce a
   ;; `rf:fx:<fx-id>` measure entry per fx walk-step. Default-off: the
   ;; bracket DCEs under :advanced + `re-frame.performance/enabled?=false`.
   ;; The bracket sits at the top of `handle-one-fx` so it covers reserved
   ;; fx-ids too (`:dispatch`, `:dispatch-later`, `:rf.fx/reg-flow`,
   ;; `:rf.fx/clear-flow`) — without that, an app whose handlers only
   ;; emit `:dispatch` produces zero `rf:fx:*` entries even with the perf
   ;; flag on.
   (performance/mark-and-measure :fx fx-id
    (if-let [reserved-body (get reserved-fx-handlers fx-id)]
      ;; Reserved fx-id — dispatch through the table; one uniform
      ;; `:rf.fx/handled` emit follows. The `:rf.machine/spawn` and
      ;; `:rf.machine/destroy` machine fx-ids are NOT in this table —
      ;; they are registered by re-frame.machines (day8/re-frame2-machines)
      ;; via the regular reg-fx path and arrive here through the
      ;; registrar default below. The reserved-fx body signature is
      ;; `(fn [frame-id parent-envelope args])` — `:dispatch` /
      ;; `:dispatch-later` read parent-envelope to propagate
      ;; inheritable envelope keys per Spec 002 §Cascade propagation.
      ;;
      ;; Generic typed-throw routing (rf2-eb4lp + rf2-on7sj-class
      ;; pattern): if a reserved-fx body throws with an `:error`-keyed
      ;; ex-data slot carrying a keyword category, route it through
      ;; the always-on `error-emit` substrate so prod monitors get
      ;; the typed signal; ex-data is preserved verbatim including
      ;; any reserved-fx-specific slots (e.g. `:cycle`). Reached via
      ;; the late-bind hook `:error-emit/dispatch-on-error` (fx.cljc
      ;; cannot statically require error-emit — would form a load
      ;; cycle). Untyped throws re-throw to preserve the crash-loud
      ;; contract. This generalisation keeps reserved-fx-specific
      ;; error keywords (e.g. flow-cycle) out of core/fx.cljc so they
      ;; DCE from consumer bundles that don't use the offending fx.
      (try
        (reserved-body frame-id parent-envelope args)
        (emit-handled! fx-id args frame-id)
        (catch #?(:clj Throwable :cljs :default) e
          (let [d        (ex-data e)
                category (:error d)]
            (if (keyword? category)
              (let [msg  #?(:clj (.getMessage ^Throwable e)
                            :cljs (.-message e))
                    time (interop/now-ms)]
                ;; Sticky hook (rf2-f72pd) — always-on per-error
                ;; observability fan-out per rf2-bacs4; survives
                ;; `:advanced` + `goog.DEBUG=false`.
                (when-let [dispatch-on-error!
                           (late-bind/get-fn-cached :error-emit/dispatch-on-error)]
                  (dispatch-on-error!
                    category
                    origin-event
                    origin-event-id
                    frame-id
                    e
                    0
                    time
                    {:operation category
                     :op-type   :error
                     :tags      (merge {:event-id          origin-event-id
                                        :event             origin-event
                                        :frame             frame-id
                                        :fx-id             fx-id
                                        :fx-args           args
                                        :handler-id        nil
                                        :exception         e
                                        :exception-message msg
                                        :recovery          :no-recovery}
                                       (dissoc d :error))
                     :recovery  :no-recovery}))
                ;; Trace path for dev consumers; DCE'd in CLJS prod.
                (trace/emit-error! category
                                   (merge {:failing-id        fx-id
                                           :fx-id             fx-id
                                           :fx-args           args
                                           :frame             frame-id
                                           :exception         e
                                           :exception-message msg
                                           :recovery          :no-recovery}
                                          (dissoc d :error))))
              ;; Untyped reserved-fx throw — preserve crash-loud
              ;; contract by re-throwing.
              (throw e)))))
      ;; Default: user-registered fx — OR a synthesised meta carrying a
      ;; function-value override (per `resolved-fx-meta` above; the
      ;; spec/002 CLJS-reference convenience form). `resolved-meta` was
      ;; computed once at top of `handle-one-fx` so the fallthrough
      ;; honours both registry hits and the fn-value override branch
      ;; without a second lookup.
      (if-let [meta resolved-meta]
      (if (fx-runs-on-platform? meta active-platform)
        ;; Per Spec 010 §Validation order step 5 (rf2-xp2o3): before the
        ;; fx handler runs, validate its args against any `:spec` on the
        ;; fx's registration meta. The schemas artefact is optional — when
        ;; absent or when no `:spec` is registered, the late-bind hook
        ;; resolves nil and the call is a no-op (true / pass).
        ;; On failure (returns false) the offending fx is skipped (per
        ;; Spec 010 §Per-step recovery row 5: `:recovery :skipped`) and
        ;; the walk continues with the next entry in the `:fx` vector —
        ;; sibling fx are not impacted, the cascade does not halt.
        ;; `validate-fx!` itself emits the `:rf.error/schema-validation-
        ;; failure :where :fx-args` trace; this caller only honours the
        ;; boolean.
        ;; Sticky hook (rf2-f72pd) — fires per-fx invocation.
        (let [validate-fx! (late-bind/get-fn-cached :schemas/validate-fx!)
              fx-ok?       (if (and validate-fx! (:spec meta))
                             (try
                               (validate-fx! fx-id origin-event-id args meta)
                               (catch #?(:clj Throwable :cljs :default) _ true))
                             true)]
        (if-not fx-ok?
          ;; Schema validation failed — the offending fx is skipped.
          ;; `validate-fx!` already emitted the structured error trace;
          ;; do NOT emit `:rf.fx/handled` (the fx did not run) and do
          ;; NOT emit a sibling warning (the schema-validation-failure
          ;; trace IS the warning, per Spec 010).
          nil
          ;; Publish the fx handler's HandlerScope — `:trigger-handler`
          ;; for the fx handler's invocation AND the success-path
          ;; `:rf.fx/handled` emit; `:no-emit?` per Spec 009
          ;; "innermost handler wins". (`:sensitive?` is path-marked
          ;; via schema-slot meta; the handler-meta annotation has
          ;; been removed.) Errors emitted from
          ;; inside the fx body carry the fx handler's source-coord;
          ;; the success-path `:rf.fx/handled` emit picks up the same
          ;; coord through `emit!`'s hoist of `*handler-scope*` — the
          ;; outer event handler's scope would otherwise stamp the
          ;; event handler's coord onto the
          ;; `:rf.fx/handled` event (Story/Causa want jump-to-source to
          ;; land on the fx handler's `reg-fx` site, not the event
          ;; handler that produced the fx vector). `:call-site` /
          ;; `:dispatch-id` are inherited from the outer scope.
          (trace/with-handler-scope
            (trace/handler-scope-from-meta :fx fx-id meta)
            (let [ok? (try
                        ;; Per Spec 002 §The binary fx-handler signature
                        ;; (line 603): the fx-handler ctx carries `:frame`
                        ;; (active frame id), `:event` (origin event
                        ;; vector — Spec 014 §Reply addressing), and
                        ;; `:envelope` (parent dispatch envelope — read
                        ;; by reserved fxs only; surfaced here too so
                        ;; user fxs can observe `:trace-id` / `:origin`
                        ;; / `:source` without a separate cofx hop).
                        ((:handler-fn meta) (cond-> {:frame frame-id}
                                              origin-event   (assoc :event origin-event)
                                              parent-envelope (assoc :envelope parent-envelope))
                                            args)
                        true
                        (catch #?(:clj Throwable :cljs :default) e
                          (let [msg (#?(:clj .getMessage :cljs .-message) e)]
                            (trace/emit-error! :rf.error/fx-handler-exception
                                               {:failing-id        fx-id
                                                :fx-id             fx-id
                                                :fx-args           args
                                                :frame             frame-id
                                                :exception         e
                                                :exception-message msg
                                                :reason            (str "Effect handler `" fx-id "` threw: " msg ".")
                                                :recovery          :no-recovery}))
                          false))]
              (when ok?
                (emit-handled! fx-id args frame-id))))))
        (trace/emit! :warning :rf.fx/skipped-on-platform
                     {:fx-id                fx-id
                      :frame                frame-id
                      :fx-args              args
                      :platform             active-platform
                      :registered-platforms (:platforms meta)
                      :recovery             :skipped}))
      (trace/emit-error! :rf.error/no-such-fx
                         {:fx-id    fx-id
                          :fx-args  args
                          :frame    frame-id
                          :recovery :no-recovery})))))))

(defn do-fx
  "Walk the :fx vector in source order. Per Spec 002 §`:fx` ordering rule 3:
  each entry's handler returns synchronously before the next begins.
  Errors trace independently and the walk continues (rule 4: one bad
  fx does not halt the rest).

  Per Spec 002 §Per-frame and per-call overrides: an fx-id override map
  may be provided. Each [fx-id args] is rewritten through that map
  before lookup.

  The 5-arity passes the originating event vector through to user-
  registered fx handlers as `:event` on their ctx — needed by Spec 014
  §Reply addressing (the fx captures the originating event-id from the
  dispatch envelope's cofx).

  The 6-arity additionally threads the originating dispatch envelope
  through to reserved-fx defmethods (and exposes it at `(:envelope m)`
  on user-fx ctx) per Spec 002 §The binary fx-handler signature (line
  603) and §Drain-loop pseudocode (lines 916, 961-963). Reserved-fx
  bodies for `:dispatch` and `:dispatch-later` read the envelope to
  propagate inheritable keys onto the child dispatch per §Cascade
  propagation."
  ([frame-id fx-vec active-platform]
   (do-fx frame-id fx-vec active-platform {} nil nil))
  ([frame-id fx-vec active-platform overrides]
   (do-fx frame-id fx-vec active-platform overrides nil nil))
  ([frame-id fx-vec active-platform overrides origin-event]
   (do-fx frame-id fx-vec active-platform overrides origin-event nil))
  ([frame-id fx-vec active-platform overrides origin-event parent-envelope]
   (doseq [pair fx-vec]
     (when (and (vector? pair) (seq pair))
       (handle-one-fx frame-id pair active-platform overrides origin-event parent-envelope)))
   (trace/emit! :event :event/do-fx {:frame frame-id})))
