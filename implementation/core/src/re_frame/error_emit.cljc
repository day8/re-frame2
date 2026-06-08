(ns re-frame.error-emit
  "Always-on error-emit substrate. Per Spec 009 §What IS available in
  production §Error-handler policy.

  Survives `:advanced` + `goog.DEBUG=false`. Carries two independent
  fan-out paths (the listener registry is BROAD; the per-frame policy
  is NARROW — see the two-axis catalogue in [[dispatch-on-error!]]).
  Fired from every production-reachable runtime `:rf.error/*` site
  (handler / interceptor / cofx exceptions, flow exceptions, reserved-fx
  typed throws, reactive + compute-sub exceptions, frame-destroyed
  dispatch / subscribe, no-such-handler, no-such-sub, and — fired by this
  ns itself — `:rf.error/on-error-policy-exception` when a frame's
  `:on-error` policy fn throws):

    1. Corpus-wide listener registry (BROAD, axis 1 / surface #4) —
       every fn registered through [[register-error-listener!]] receives
       a tight error-record:

         {:error        <kw>     ;; e.g. :rf.error/handler-exception
          :event        <vector> ;; dispatched event vector (elided)
          :event-id     <kw>
          :frame        <kw>
          :time         <millis>
          :exception    <ex>
          :elapsed-ms   <int>
          :source-coord {:ns :file :line}  ;; rf2-3un2g; absent if
                                           ;; the failing handler was
                                           ;; registered programmatically
                                           ;; (no macro capture)
          }

       For off-box observability shippers (Sentry, Honeybadger,
       Rollbar). Per rf2-3un2g the `:source-coord` slot rides the
       always-on parallel `error-coords-by-id` registry so it survives
       CLJS `:advanced` + `goog.DEBUG=false` builds where public
       registry-meta has been stripped of coord-keys.

    2. Per-frame `:on-error` policy fn (NARROW, axis 2 / surface #5) —
       the frame's `:on-error` slot, when present, receives a structured
       error event (`:operation` / `:tags` / `:recovery` shape) for
       in-app recovery decisions. This path is RECOVERY-SCOPED: it fires
       ONLY for categories where a `{:swallow | :replacement | :default}`
       choice is meaningful (handler-exception and the handler-like
       exception categories — interceptor / cofx / flow / reserved-fx).
       Categories with no recovery point (frame-destroyed, no-such-
       handler, no-such-sub — invalid operations; sub-exception — its
       recovery is the built-in 'return nil', not a policy choice) pass
       `error-event` as `nil` so ONLY axis 1 fires. See the two-axis
       catalogue in [[dispatch-on-error!]].

  Both paths are independent (a buggy listener cannot block the policy
  fn; a buggy policy fn cannot block listeners). Both are try/catch
  wrapped. A LISTENER throw is silently dropped (a sibling-isolation
  concern, not a framework error). A POLICY-FN throw is NOT silent: it is
  surfaced LOUDLY as `:rf.error/on-error-policy-exception` through the
  always-on listener (axis 1, listener-only — NOT re-invoking the policy:
  unbounded-recursion guard), then recovery proceeds with the original
  error's per-category default (per rf2-ciy / rf2-avnzbp + the loud-
  failure posture). See [[fire-on-error-policy!]] / [[emit-policy-
  exception!]].

  Listener REGISTRATION sites SHOULD use `goog.DEBUG=false` as a
  belt-and-braces gate alongside an explicit config flag. The substrate
  proper carries no gate.

  NOTE: handler-meta `:sensitive?` is no longer consulted here.
  Sensitive data marking is path-based per the upcoming data-
  classification mechanism (separate spec doc; in progress) — the
  per-path elision wire-walker is the load-bearing redaction surface
  on this path."
  (:require [re-frame.elision        :as elision]
            [re-frame.emit-substrate :as emit]
            [re-frame.frame          :as frame]
            [re-frame.interop        :as interop]
            [re-frame.late-bind      :as late-bind]
            [re-frame.source-coords  :as source-coords]))

#?(:clj (set! *warn-on-reflection* true))

;; ---- listener registry ----------------------------------------------------

(defonce ^:private listeners
  ;; id -> listener fn. `defonce` so hot reload of this namespace
  ;; does not silently drop a long-lived production listener that
  ;; the consuming app registered at boot.
  (atom {}))

(def ^:private registry
  (emit/make-listener-registry {:listeners listeners}))

(def register-error-listener!
  "Register a listener `f` under `id`. Re-registering the same id
  replaces. `f` receives a single error-record map (see ns docstring
  §Record shape); its return value is ignored. Returns `id`. Per
  rf2-bacs4."
  (:register registry))

(def unregister-error-listener!
  "Drop the listener registered under `id`. Returns nil."
  (:unregister registry))

(def clear-error-listeners!
  "Drop every registered listener. Test-isolation only; production
  code should never call this. Returns nil."
  (:clear registry))

;; ---- kind-aware source-coord lookup (rf2-bxud9v) --------------------------
;;
;; The always-on `error-coords-by-id` parallel registry is keyed by
;; `[registry-kind id]` — the SAME `kind` the public reg-* macro path
;; stamped at registration (`re-frame.registrar/register!` →
;; `remember-error-coords!`). A `reg-sub` stores coords under `[:sub
;; sub-id]`; a `reg-event-*` under `[:event event-id]`. So the lookup
;; MUST pivot on the registry kind the failing `id` was registered with,
;; not assume `:event`.
;;
;; The error categories carry that kind: a `:rf.error/sub-*` record's
;; `:event-id` slot holds a SUB id (the call sites in `subs.cljc` /
;; `subs/memo.cljc` pass `query-id`), so its coords live under `[:sub …]`.
;; All other production categories (handler / interceptor / cofx / flow /
;; reserved-fx / no-such-handler) carry an EVENT id under `[:event …]`.
;;
;; `:rf.error/frame-destroyed` is the one shared category — fired with an
;; EVENT id from `router.cljc` (a dispatch into a destroyed frame) AND with
;; a SUB id from `subs.cljc` (a subscribe into a destroyed frame). We can't
;; disambiguate on the kw alone, so for it we try `:sub` then `:event`
;; (a sub-id never collides with an event-id under the same registry, so
;; the first hit is unambiguous; a miss falls through to nil → slot absent).

(def ^:private sub-error-categories
  "Categories whose `:event-id` slot carries a SUB id — their source
  coords live under `[:sub sub-id]` in the always-on registry. Per
  rf2-bxud9v: `dispatch-on-error!` looked these up under `[:event …]`
  (the hardcoded default), so the always-on production error records
  for the parametric input-fn failures (and the reactive sub-exception)
  omitted the failing sub's `:source-coord`."
  #{:rf.error/sub-input-fn-exception
    :rf.error/sub-input-fn-bad-return
    :rf.error/sub-exception
    :rf.error/no-such-sub})

(defn- error-source-coord
  "Resolve the `{:ns :file :line}` source-coord for the failing `id` of an
  `error-kw` category, pivoting on the registry kind the `id` was
  registered with (rf2-bxud9v). Returns nil when no coords were captured
  (programmatic registration that bypassed the macro path, or an id that
  was never registered) — the caller `cond->`s the slot in, so nil means
  the `:source-coord` slot is ABSENT from the record rather than nil.

    - `:rf.error/sub-*` categories → look under `[:sub id]`.
    - `:rf.error/frame-destroyed` is fired with both an event-id (router)
      and a sub-id (subs), so try `[:sub id]` first then `[:event id]`.
    - every other category → look under `[:event id]`."
  [error-kw id]
  (when id
    (cond
      (contains? sub-error-categories error-kw)
      (source-coords/error-coords-for :sub id)

      (= :rf.error/frame-destroyed error-kw)
      (or (source-coords/error-coords-for :sub id)
          (source-coords/error-coords-for :event id))

      :else
      (source-coords/error-coords-for :event id))))

;; ---- emission -------------------------------------------------------------

(defn- emit-policy-exception!
  "A frame's `:on-error` policy fn itself threw while processing an error
  event. Surface `:rf.error/on-error-policy-exception` LOUDLY through the
  always-on listener registry (axis 1 / surface #4) so the policy-fn bug
  is observable in production rather than silently swallowed (the
  loud-failure posture per rf2-2hvga / rf2-avnzbp), then RECOVER — the
  exception does not propagate to user code; the caller falls back to the
  original error's documented per-category recovery.

  LISTENER-ONLY: this fans out NO axis-2 policy invocation. Per rf2-ciy
  the runtime does NOT recursively invoke the policy on its own exception
  — that would risk unbounded recursion (a buggy policy fn would re-throw
  on the re-emission, re-trigger the policy, …). We deliberately fan a
  tight record straight at the listener registry rather than re-entering
  [[dispatch-on-error!]], so there is no path back into
  [[fire-on-error-policy!]].

  The tight record carries the same listener shape as every other
  category (`:error :event :event-id :frame :time :exception :elapsed-ms`)
  with `:event`/`:event-id`/`:elapsed-ms` nil — the policy throw has no
  failing event vector of its own; `:exception` is the policy's throw
  (message + ex-data ride on it) and `:frame` is the policy's host frame.
  Per the catalogue row's `:original` tag (per [[dispatch-on-error!]]
  §catalogue + the `:rf/error-event` envelope), the operation of the
  error the policy was processing rides on the record so observability
  shippers can correlate the policy failure with the error that triggered
  it. `:original` is an additive slot (open-map convention) — listeners
  filtering on the tight key set ignore it."
  [frame-id error-event policy-ex]
  (let [original (when (map? error-event) (:operation error-event))
        record   (cond-> {:error      :rf.error/on-error-policy-exception
                          :event      nil
                          :event-id   nil
                          :frame      frame-id
                          :time       (interop/now-ms)
                          :exception  policy-ex
                          :elapsed-ms nil}
                   original (assoc :original original))]
    ;; Axis 1 ONLY — fan straight at the listener registry; never re-enter
    ;; the policy path (no recursion).
    ((:fan-out registry) record)
    nil))

(defn- fire-on-error-policy!
  "Invoke the frame's `:on-error` policy fn with `error-event`. No-op
  when `error-event` is nil (the LISTENER-ONLY caller signal — see
  [[dispatch-on-error!]] §two-axis catalogue), when the frame is
  unregistered, when no `:on-error` slot is configured, or when the
  slot is not a fn.

  Per Spec 009 §Error event catalogue (`:rf.error/on-error-policy-
  exception`, rf2-ciy) + rf2-avnzbp: a policy-fn throw is caught here so
  a buggy policy fn cannot break the cascade — but it is NOT silently
  swallowed. The throw is surfaced LOUDLY as
  `:rf.error/on-error-policy-exception` through the always-on listener
  (axis 1, via [[emit-policy-exception!]]) so the bug is observable in
  production, then recovery proceeds (the caller applies the original
  error's per-category default). The runtime does NOT recursively invoke
  the policy on its own exception (unbounded-recursion guard). Returns
  the policy fn's return value, or nil on a policy throw / no-op."
  [frame-id error-event]
  (when (some? error-event)
    (when-let [f (frame/frame frame-id)]
      (when-let [policy (get-in f [:config :on-error])]
        (when (fn? policy)
          (try
            (policy error-event)
            (catch #?(:clj Throwable :cljs :default) policy-ex
              (emit-policy-exception! frame-id error-event policy-ex))))))))

(defn dispatch-on-error!
  "Surface an `:rf.error/*` event through the always-on error-emit
  fan-out paths. Always-on (NOT gated by `re-frame.interop/debug-
  enabled?`) — fires in CLJS production builds where the trace
  surface is elided.

  ## Two-axis catalogue (Mike-ruled rf2-2hvga = B + recover-but-emit)

  The two fan-out paths are gated INDEPENDENTLY:

    - **axis 1 — corpus-wide listener registry (surface #4): BROAD.**
      ALWAYS fires for every catalogued production-reachable RUNTIME
      `:rf.error/*` category. This is the off-box observability stream
      (Sentry / Datadog / Xray / the SSR error-projection listener) and
      is the production-survivable source of truth: a category that does
      NOT fan out here goes silent under `goog.DEBUG=false`. (Dev-only-
      validation / registration-time categories — dev schema checks,
      machine-unresolved-guard — stay dev-trace-only and do NOT call
      this fn; that is correct, not a gap.)

    - **axis 2 — per-frame `:on-error` policy fn (surface #5): NARROW,
      recovery-scoped.** Fires ONLY when `error-event` is non-nil. Pass
      a structured `{:operation :op-type :tags :recovery}` map ONLY for
      categories where a `{:swallow | :replacement | :default}` recovery
      decision is meaningful (handler-exception + the handler-like
      exception categories: interceptor / cofx / flow / reserved-fx).
      Pass `nil` for invalid-operation categories with no recovery point
      (frame-destroyed, no-such-handler, no-such-sub) and for
      sub-exception (whose recovery is the built-in 'return nil', not a
      policy choice) — then ONLY axis 1 fires.

  Builds the tight error-record ONCE, runs
  `re-frame.elision/elide-wire-value` against `:event` with off-box
  defaults (large → `:rf.size/large-elided`; per-path sensitive
  declarations → `:rf/redacted`), then fans out along the two
  independent paths.

  ## Payload hygiene (production-surviving — enforce at every site)

  The listener record (`{:error :event :event-id :frame :time
  :exception :elapsed-ms}`) is production-surviving and is NOT privacy-
  gated like the dev trace. Every caller MUST keep identifiers tight,
  elide `:event` (done here via the wire-walker), and carry NO raw
  app-db slice. Sensitive-data redaction on this path is path-based:
  the per-frame `[:rf/runtime :elision]` registry's `:sensitive-
  declarations` drive the wire-walker's per-slot substitutions.
  Handler-meta `:sensitive?` is no longer consulted (path-marked
  classification is the v2 mechanism; separate spec doc; in progress).

  Called from every `:rf.error/*` emission site — directly from
  `router.cljc` (handler-exception, flow-eval, frame-destroyed) and via
  the `:error-emit/dispatch-on-error` late-bind hook from `fx.cljc`,
  `subs/memo.cljc`, `subs.cljc`, and `router/diagnostics.cljc` (those
  layers cannot static-require this ns — load cycle). Returns nil."
  [error-kw event event-id frame-id exception elapsed-ms time error-event]
  (let [;; Per rf2-3un2g §Always-on error-coord registry: source-coords
        ;; for the failing handler/sub ride the always-on parallel
        ;; registry (NOT the public registry-meta — which is stripped of
        ;; coord-keys under CLJS `:advanced + goog.DEBUG=false`). The
        ;; lookup here surfaces `{:ns :file :line}` for Sentry-style
        ;; shippers and the per-frame `:on-error` policy fn in BOTH dev
        ;; AND production. Returns nil for programmatic registrations that
        ;; bypassed the macro path — that's fine; the slot is absent
        ;; from the record / tags rather than nil.
        ;;
        ;; Per rf2-bxud9v the lookup is KIND-AWARE: the registry is keyed
        ;; by `[registry-kind id]`, so a sub-id (`:rf.error/sub-*`
        ;; categories) must resolve under `[:sub …]`, not the hardcoded
        ;; `[:event …]`. See [[error-source-coord]] / [[sub-error-categories]].
        source-coord (error-source-coord error-kw event-id)
        ;; Per-path wire-walker: paths flagged `:sensitive?` / `:large?`
        ;; via the per-frame `[:rf/runtime :elision]` registry get their
        ;; per-path substitutions.
        elided-event (elision/elide-wire-value event {:frame frame-id})
        record       (cond-> {:error      error-kw
                              :event      elided-event
                              :event-id   event-id
                              :frame      frame-id
                              :time       time
                              :exception  exception
                              :elapsed-ms elapsed-ms}
                       source-coord (assoc :source-coord source-coord))
        ;; The structured policy-event's `:tags` get the same
        ;; `:source-coord` slot (innermost-wins: caller-supplied
        ;; `:source-coord` already on `:tags` would survive
        ;; intentionally; we only `assoc-when-missing`).
        policy-event (if (and source-coord
                              (map? error-event)
                              (map? (:tags error-event))
                              (not (contains? (:tags error-event) :source-coord)))
                       (update error-event :tags assoc :source-coord source-coord)
                       error-event)]
    ;; Axis 1 (BROAD) — corpus-wide listeners ALWAYS fan out.
    ((:fan-out registry) record)
    ;; Axis 2 (NARROW) — `fire-on-error-policy!` no-ops when `policy-event`
    ;; is nil (the listener-only signal for non-recovery categories).
    (fire-on-error-policy! frame-id policy-event))
  nil)

;; ---- late-bind hook registration ------------------------------------------
;;
;; `router.cljc` already statically `:require`s this namespace (per
;; rf2-hqbeh; the substrate is a foundational always-on surface
;; alongside the router itself), so a late-bind hook isn't strictly
;; needed here. We publish one anyway for symmetry with rf2-rirbq's
;; `:event-emit/dispatch-on-event` hook and to keep the substrate
;; addressable from other artefacts that may want to fire error
;; records without static-requiring this ns.

(late-bind/set-fn! :error-emit/dispatch-on-error dispatch-on-error!)
