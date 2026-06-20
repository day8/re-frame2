(ns re-frame.error-emit
  "Always-on error-emit substrate. Per Spec 009 §What IS available in
  production §Error-emit listener.

  Survives `:advanced` + `goog.DEBUG=false`. Carries ONE fan-out path —
  the corpus-wide listener registry. Fired from every production-reachable
  runtime `:rf.error/*` site (handler / interceptor / cofx exceptions,
  flow exceptions, reserved-fx typed throws, reactive + compute-sub
  exceptions, frame-destroyed dispatch / subscribe, no-such-handler,
  no-such-sub):

    Corpus-wide listener registry (surface #4) — every fn registered
    through [[register-error-listener!]] receives a tight error-record:

         {:error        <kw>     ;; e.g. :rf.error/handler-exception
          :event        <vector> ;; dispatched event vector (elided)
          :event-id     <kw>
          :frame        <kw>
          :time         <millis>
          :exception    <ex>
          :elapsed-ms   <int>
          :source-coord {:ns :file :line}  ;; absent if the failing
                                           ;; handler was registered
                                           ;; programmatically
                                           ;; (no macro capture)
          }

    For off-box observability shippers (Sentry, Honeybadger,
    Rollbar). The `:source-coord` slot rides the always-on parallel
    `error-coords-by-id` registry so it survives CLJS `:advanced` +
    `goog.DEBUG=false` builds where public registry-meta carries no
    coord-keys.

  Observability is the only concern here — there is no app-steering
  recovery policy. Recovery is framework-owned: the per-category typed
  defaults (frame-destroyed recovers + emits, sub-exception returns nil,
  handler-exception fails loud without crashing the app). Recovery is
  not a framework app-policy concern.

  A LISTENER throw is silently dropped (a sibling-isolation concern, not
  a framework error). Each listener invocation is try/catch wrapped.

  Listener REGISTRATION sites SHOULD use `goog.DEBUG=false` as a
  belt-and-braces gate alongside an explicit config flag. The substrate
  proper carries no gate.

  Sensitive data marking is path-based per the data-classification
  mechanism (separate spec doc); handler-meta `:sensitive?` is not
  consulted here. The per-path elision wire-walker is the load-bearing
  redaction surface on this path."
  (:require [re-frame.elision        :as elision]
            [re-frame.emit-substrate :as emit]
            [re-frame.late-bind      :as late-bind]
            [re-frame.source-coords  :as source-coords]
            [re-frame.trace          :as trace]))

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
  §Record shape); its return value is ignored. Returns `id`."
  (:register registry))

(def unregister-error-listener!
  "Drop the listener registered under `id`. Returns nil."
  (:unregister registry))

(def clear-error-listeners!
  "Drop every registered listener. Test-isolation only; production
  code should never call this. Returns nil."
  (:clear registry))

;; ---- kind-aware source-coord lookup --------------------------------------
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
  coords live under `[:sub sub-id]` in the always-on registry, so
  `dispatch-on-error!` resolves them there rather than under the
  `[:event …]` default. Covers the parametric input-fn failures and
  the reactive sub-exception, so the always-on production error records
  carry the failing sub's `:source-coord`."
  #{:rf.error/sub-input-fn-exception
    :rf.error/sub-input-fn-bad-return
    :rf.error/sub-exception
    :rf.error/no-such-sub})

(defn- error-source-coord
  "Resolve the `{:ns :file :line}` source-coord for the failing `id` of an
  `error-kw` category, pivoting on the registry kind the `id` was
  registered with. Returns nil when no coords were captured
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

(defn dispatch-on-error!
  "Surface an `:rf.error/*` event through the always-on corpus-wide
  error-emit listener registry (surface #4). Always-on (NOT gated by
  `re-frame.interop/debug-enabled?`) — fires in CLJS production builds
  where the trace surface is elided.

  ALWAYS fires for every catalogued production-reachable RUNTIME
  `:rf.error/*` category. This is the off-box observability stream
  (Sentry / Datadog / Xray / the SSR error-projection listener) and
  is the production-survivable source of truth: a category that does
  NOT fan out here goes silent under `goog.DEBUG=false`. (Dev-only-
  validation / registration-time categories — dev schema checks,
  machine-unresolved-guard — stay dev-trace-only and do NOT call
  this fn; that is correct, not a gap.)

  There is no app-steering recovery policy. Recovery is framework-owned
  (the per-category typed defaults); observability is this listener.

  Builds the tight error-record ONCE, runs
  `re-frame.elision/elide-wire-value` against `:event` with off-box
  defaults (large → `:rf.size/large-elided`; per-path sensitive
  declarations → `:rf/redacted`), then fans out to every listener.

  ## Payload hygiene (production-surviving — enforce at every site)

  The listener record (`{:error :event :event-id :frame :time
  :exception :elapsed-ms}`, plus an optional `:failing-id` / `:reason`)
  is production-surviving and is NOT privacy-
  gated like the dev trace. Every caller MUST keep identifiers tight,
  elide `:event` (done here via the wire-walker), and carry NO raw
  app-db slice. Sensitive-data redaction on this path is path-based:
  the per-frame `:rf.runtime/elision` registry's `:sensitive-
  declarations` drive the wire-walker's per-slot substitutions.
  Handler-meta `:sensitive?` is not consulted; path-marked
  classification is the mechanism (separate spec doc).

  ## Component attribution

  `attrs` is an OPTIONAL trailing map carrying the component-attributed
  slots `{:failing-id <kw> :reason <string>}`. For the categories whose
  failing component is DISTINCT from the dispatched event — a user
  interceptor (`:rf.error/interceptor-exception`, `:failing-id` = the
  interceptor id) or a coeffect supplier (`:rf.error/coeffect-exception`,
  `:failing-id` = the cofx id) — the `:event-id` slot carries the EVENT
  id, so the failing component id would otherwise be observable ONLY on
  the dev-trace tags (DCE'd under `goog.DEBUG=false`). Lifting it into the
  always-on record lets off-box shippers (Sentry / Datadog) tell WHICH
  interceptor / cofx failed in production, not just the category. The
  slots are `cond->`'d in — absent when nil, so the tight record shape is
  unchanged for the categories whose failing id already equals `:event-id`
  (handler-exception, the sub-* categories where the sub-id rides
  `:event-id`).

  Called from every `:rf.error/*` emission site — directly from
  `router.cljc` (handler-exception, flow-eval, frame-destroyed) and via
  the `:error-emit/dispatch-on-error` late-bind hook from `fx.cljc`,
  `subs/memo.cljc`, `subs.cljc`, and `router/diagnostics.cljc` (those
  layers cannot static-require this ns — load cycle). Returns nil."
  ([error-kw event event-id frame-id exception elapsed-ms time]
   (dispatch-on-error! error-kw event event-id frame-id exception elapsed-ms time nil))
  ([error-kw event event-id frame-id exception elapsed-ms time attrs]
  (let [;; Always-on error-coord registry: source-coords
        ;; for the failing handler/sub ride the always-on parallel
        ;; registry (NOT the public registry-meta — which is stripped of
        ;; coord-keys under CLJS `:advanced + goog.DEBUG=false`). The
        ;; lookup here surfaces `{:ns :file :line}` for Sentry-style
        ;; shippers in BOTH dev AND production. Returns nil for
        ;; programmatic registrations that bypassed the macro path —
        ;; that's fine; the slot is absent from the record rather than nil.
        ;;
        ;; The lookup is KIND-AWARE: the registry is keyed
        ;; by `[registry-kind id]`, so a sub-id (`:rf.error/sub-*`
        ;; categories) must resolve under `[:sub …]`, not the hardcoded
        ;; `[:event …]`. See [[error-source-coord]] / [[sub-error-categories]].
        source-coord (error-source-coord error-kw event-id)
        ;; Per-path wire-walker: paths flagged `:sensitive?` / `:large?`
        ;; via the per-frame `:rf.runtime/elision` registry get their
        ;; per-path substitutions.
        elided-event (elision/elide-wire-value event {:frame frame-id})
        ;; Lift the component-attributed slots into the always-on
        ;; record so an off-box shipper sees WHICH interceptor / cofx failed in
        ;; production (the `:event-id` slot carries the EVENT id for these
        ;; categories; the failing component id would otherwise ride only the
        ;; DCE'd dev-trace tags). `cond->`'d in — absent when nil/blank, so the
        ;; tight record shape is unchanged for categories that pass no `attrs`
        ;; (or whose failing id already equals `:event-id`).
        failing-id   (:failing-id attrs)
        reason       (:reason attrs)
        record       (cond-> {:error      error-kw
                              :event      elided-event
                              :event-id   event-id
                              :frame      frame-id
                              :time       time
                              :exception  exception
                              :elapsed-ms elapsed-ms}
                       source-coord       (assoc :source-coord source-coord)
                       (some? failing-id) (assoc :failing-id failing-id)
                       (some? reason)     (assoc :reason reason))]
    ;; Corpus-wide listeners fan out (the ADVANCED integration registry).
    ((:fan-out registry) record)
    ;; EP-0015 §9: the frame-owned observability sink route —
    ;; the NORMAL production error-observation surface (Spec 015 §Frame-owned
    ;; observability sink policy). Parallel to the corpus-wide listener
    ;; fan-out above: routes the error record to THIS frame's declared
    ;; `:observability :errors` sinks, projected under the frame's
    ;; classification + the sink's egress profile (the `:event` tree slot
    ;; redacts; `:exception` drops under `:rf.egress/public-error`). Passes
    ;; the RAW `event` (not the listener-elided one) so the projector applies
    ;; the frame's policy at the sink's profile rather than double-eliding.
    ;; Late-bound: a static `error-emit` → `observability` → `projection` →
    ;; `elision` require would re-enter this ns's own require graph; the hook
    ;; is nil (no-op) until `re-frame.observability` loads. Fail-closed +
    ;; sibling-isolated inside `route-error!`. Always-on.
    (when-let [route-error! (late-bind/get-fn-cached :observability/route-error)]
      (route-error! error-kw event event-id frame-id exception elapsed-ms
                    time nil))
   nil)))

;; ---- the two-channel fan-out helper ---------------------------------------
;;
;; EVERY production-reachable runtime `:rf.error/*` site fans the SAME category
;; out along BOTH error channels in lock-step: the always-on
;; `dispatch-on-error!` listener registry (axis 1 — production-survivable; the
;; off-box-shipper / SSR-projector source of truth) AND the dev-only
;; `trace/emit-error!` surface (axis 2 — DCE'd under CLJS `:advanced` +
;; `goog.DEBUG=false`). This is the ONE shared helper those sites use: the
;; ~12 emit sites across `subs` / `subs.memo` / `cofx` / `router.diagnostics`
;; (reaching it through the `:error-emit/emit-error-both` late-bind hook), the
;; 4 `router` wrappers (which static-require this ns), and `fx`'s
;; `emit-fx-error!` — all the same positional record + the category-specific
;; dev-trace `tags` map.

(defn emit-error-both!
  "Fan a runtime `:rf.error/*` `category` out through BOTH error channels in one
  call: the always-on corpus-wide [[dispatch-on-error!]] listener
  registry (axis 1 — production-survivable; survives CLJS `:advanced` +
  `goog.DEBUG=false`, the off-box-shipper + SSR-error-projector source of truth)
  AND the dev-only `re-frame.trace/emit-error!` surface (axis 2 — DCE'd in CLJS
  production). The shared two-channel fan-out every catalogued production-
  reachable runtime error site uses.

  `category` is the `:rf.error/*` keyword (the SAME value flows to both
  channels). `event` / `event-id` / `frame` / `exception` / `elapsed-ms` / `time`
  are the always-on listener record's positional fields (see
  [[dispatch-on-error!]] — `event` is elided by the wire-walker there, `time` is
  the emit instant in millis, `elapsed-ms` is `0` at the non-timed invalid-op
  sites and the measured duration at the router's timed pipeline/flow paths).
  `trace-tags` is the category-specific dev-trace tag map — built at the call
  site and passed UNCHANGED to `trace/emit-error!`, so dev-trace consumers see
  exactly that shape.

  The COMPONENT-ATTRIBUTED slots `:failing-id` / `:reason` are
  ALSO lifted out of `trace-tags` onto the always-on record (axis 1) — but ONLY
  when the `:failing-id` is present AND DISTINCT from `:event-id`. That is the
  case exactly for the categories whose failing component is not the dispatched
  event: a user interceptor (`:rf.error/interceptor-exception`) or a coeffect
  supplier (`:rf.error/coeffect-exception`), where `:event-id` carries the EVENT
  id and the interceptor / cofx id would otherwise ride only the DCE'd dev-trace
  tags. For handler-exception and the sub-* categories the failing id already EQUALS
  `:event-id`, so nothing extra is stamped and the tight record is unchanged.
  The off-box shipper now learns WHICH interceptor / cofx failed in production.

  Returns nil. Reached directly by `router.cljc` (static require) and via the
  `:error-emit/emit-error-both` late-bind hook by `fx` / `subs` / `subs.memo` /
  `cofx` / `router.diagnostics` (those layers cannot static-require this ns — a
  load cycle through `elision` → `frame`)."
  [category event event-id frame exception elapsed-ms time trace-tags]
  ;; Axis 1 — always-on corpus-wide listener (+ EP-0015 frame-owned sink).
  ;; Lift the component-attributed `:failing-id` / `:reason` from
  ;; the trace-tags onto the always-on record when the failing component is
  ;; DISTINCT from the dispatched event (interceptor / cofx categories). The
  ;; distinct-from-event-id guard keeps the record tight for the categories
  ;; whose `:failing-id` already equals `:event-id`.
  (let [failing-id (:failing-id trace-tags)
        attrs      (when (and (some? failing-id) (not= failing-id event-id))
                     {:failing-id failing-id
                      :reason     (:reason trace-tags)})]
    (dispatch-on-error! category event event-id frame exception elapsed-ms time
                        attrs))
  ;; Axis 2 — dev-only trace surface; DCEs under `:advanced` + `goog.DEBUG=false`
  ;; (the `interop/debug-enabled?` gate lives inside `trace/emit-error!`).
  (trace/emit-error! category trace-tags)
  nil)

;; ---- general non-event always-on record (EP-0008 union shape) -------------
;;
;; `dispatch-on-error!` (above) is the EVENT-centric always-on path: it takes
;; the positional `[error-kw event event-id frame-id exception elapsed-ms
;; time]` shape, elides the `:event` wire-value, resolves a kind-aware
;; `:source-coord`, and ALSO routes to the EP-0015 frame-owned observability
;; sink. That shape is right for handler / interceptor / cofx / sub / fx /
;; flow categories — failures of a DISPATCHED EVENT or a SUBSCRIBE.
;;
;; But not every always-on `:rf.error/*` is an event failure. The
;; frame-teardown report is a non-event always-on record — a destroy-time
;; fact with a `:hook-failures` vector, no event, no `:event-id`. The EP-0008
;; SSR promotion adds six more:
;; render-time / writer-phase / head-resolution / projector-fallback /
;; hydration-parse failures, each carrying its OWN flat category keys
;; (`:exception` / `:phase` / `:reason` / `:projector-id` / …) and either a
;; `:frame` (the server frame) or `nil` (the pre-frame hydration-parse
;; FRAMELESS case, per the EP-0002 resolution-6 `:rf.error/no-frame-context`
;; precedent — a frameless always-on record IS supported).
;;
;; `dispatch-error-record!` is the GENERAL always-on emit these share: it
;; takes a PRE-BUILT union record and fans it out unchanged. The one union
;; shape every non-event always-on record uses:
;;
;;     {:error <kw>          ;; the :rf.error/* category (REQUIRED)
;;      :frame <id-or-nil>   ;; the owning frame, or nil (frameless)
;;      :time  <millis>      ;; when (REQUIRED)
;;      …flat category keys… ;; :exception / :phase / :reason / :recovery /
;;                           ;; :hook-failures / :projector-id / :where / …}
;;
;; The SSR error-emit-projection-listener consumes these generically (every
;; non-`:error` slot rides onto its synthesised `:tags`), so a custom
;; projector reading `(get-in event [:tags :exception])` sees the same keys
;; on this path as on the trace path. Designed compatibly with the EP-0015
;; §9 frame-owned observability sink routing these will eventually flow
;; through (the sink projects the flat record under the frame's
;; classification + the sink's egress profile).
;;
;; Always-on (NOT `interop/debug-enabled?`-gated): it fires in CLJS
;; production builds where the dev trace surface is DCE'd. The caller keeps
;; its existing `trace/emit-error!` for dev richness; this is the
;; production-survivable sibling.

(defn dispatch-error-record!
  "Fan a PRE-BUILT always-on error record out through the corpus-wide
  error-emit listener registry (surface #4). The general, non-event
  counterpart of [[dispatch-on-error!]] — for `:rf.error/*` categories that
  are NOT a dispatched-event / subscribe failure and so do not fit the
  event-centric positional shape (the frame-teardown report, the EP-0008
  promoted SSR categories). Always-on (NOT gated by
  `re-frame.interop/debug-enabled?`) — fires in CLJS production builds where
  the dev trace surface is elided.

  `record` is the union shape (see §General non-event always-on record):
  `{:error <kw> :frame <id-or-nil> :time <millis> …flat category keys…}`.
  The caller builds the record (it owns the category-specific slots), keeps
  identifiers tight, and carries no raw app-db slice — this record is
  production-surviving and is NOT privacy-gated like the dev trace.

  Reached by other artefacts (the SSR / ssr-ring host layers) through the
  published `:error-emit/dispatch-error-record` late-bind hook — symmetric
  with how `frame.cljc` reaches the frameless `:rf.error/no-frame-context`
  emit and how `frame`/`ssr` reach the teardown report (a static
  `<artefact>` → `error-emit` require would close a load cycle, or the
  artefact simply ships above core's require graph). Returns nil."
  [record]
  ;; Corpus-wide listeners fan out (the ADVANCED integration registry). The
  ;; record is delivered UNCHANGED — raw `:exception` and all — because this
  ;; surface is the off-box-shipper API (Sentry / Datadog need the host
  ;; exception / stack), NOT privacy-gated like the dev trace; the caller is
  ;; contracted to keep identifiers tight and carry no raw app-db slice.
  ((:fan-out registry) record)
  ;; EP-0015 §9 / Spec 015 §Frame-owned observability sink policy:
  ;; the frame-owned `:observability :errors` sink route — the
  ;; NORMAL production error-observation surface. Parallel to the corpus-wide
  ;; fan-out above, exactly as the event-centric `dispatch-on-error!` routes to
  ;; `route-error!`: a NON-EVENT union record carrying a resolvable `:frame`
  ;; ALSO routes to that frame's declared error sinks, PROJECTED under the
  ;; frame's classification + the sink's egress profile (the flat category
  ;; slots ride `:tags` → redacted; `:exception` drops under
  ;; `:rf.egress/public-error`). Late-bound (the static
  ;; `error-emit` → `observability` → `projection` → `elision` require would
  ;; re-enter this ns's own require graph); the hook is nil (no-op) until
  ;; `re-frame.observability` loads. Fail-closed + sibling-isolated inside
  ;; `route-error-record!` (a frameless `:frame nil` record routes nothing —
  ;; no frame-owned policy exists). Always-on.
  (when-let [route-error-record! (late-bind/get-fn-cached
                                   :observability/route-error-record)]
    (route-error-record! record))
  nil)

;; ---- frame-teardown report (EP-0008 promotion criterion) ------------------
;;
;; The frame-destroy teardown recipe runs many optional cleanup hooks. A
;; hook throwing is production-reachable (long-lived SSR / tooling), is a
;; resource-leakage class (skipped teardown the next operation cannot see
;; locally), and compounds with process lifetime — all three legs of the
;; Spec 009 §promotion criterion hold. Rather than fan ONE always-on
;; emission out per failed hook (an SSR per-request-destroy × M req/s flood
;; of the production error shipper), the runtime accumulates the per-hook
;; failures and emits ONE bounded `:rf.error/frame-teardown-failed` record
;; carrying a `:hook-failures` vector (Spec 009 §Channel-promotion catalogue
;; rows — the report-vs-per-item idiom). The dev per-hook diagnostic
;; (`:rf.warning/teardown-hook-exception`, DCE'd in prod) stays at its
;; causal positions inside `frame/safe-call-hook!`.

(defn dispatch-frame-teardown-report!
  "Surface ONE always-on `:rf.error/frame-teardown-failed` report through
  the corpus-wide error-emit listener registry (surface #4). Always-on
  (NOT gated by `re-frame.interop/debug-enabled?`) — fires in CLJS
  production builds where the dev per-hook trace is DCE'd.

  Per Spec 009 §Observability channels §Channel-promotion catalogue rows
  (EP-0008 Open Issue 1, ruled): the frame-destroy case satisfies the
  promotion criterion with a SINGLE bounded report naming the higher-
  level fact, with the per-hook detail carried as the `hook-failures`
  payload vector — NOT one record per failed hook. The destroy IS the
  fact; the hooks are detail rows, and one record preserves the
  which-hooks-failed-together correlation external shippers will not
  reliably re-group.

  Builds the catalogue-shaped record (`:tags` keys `:frame`,
  `:hook-failures`, `:reason`; `:recovery :ignored` — teardown is
  best-effort) and fans it out. Called at most once per destroy
  (whether teardown completes or aborts) via the
  `:error-emit/dispatch-frame-teardown-report` late-bind hook from
  `frame.cljc`'s finally-shaped flush boundary (`frame` cannot static-
  require this ns — `error-emit` → `elision` → `frame` is a load cycle).

  `hook-failures` is a non-empty vector of
  `{:hook <late-bind-hook-key> :exception <ex> :where :safe-call-hook!}`
  entries — one per failed hook, accumulated during the teardown walk.
  Returns nil; a no-op when `hook-failures` is empty (no failures, no
  report)."
  [frame-id hook-failures time]
  (when (seq hook-failures)
    ;; The bounded report is itself a non-event union record — fan it out
    ;; through the shared general helper so the teardown report and the
    ;; EP-0008 SSR promotions ride ONE fan-out path / ONE record shape.
    (dispatch-error-record!
      {:error          :rf.error/frame-teardown-failed
       :frame          frame-id
       :hook-failures  (vec hook-failures)
       :recovery       :ignored
       :reason         (str (count hook-failures)
                            " frame-teardown cleanup hook(s) threw"
                            " during destroy; teardown continued"
                            " best-effort (skipped cleanup may have"
                            " leaked resources)")
       :time           time}))
  nil)

;; ---- late-bind hook registration ------------------------------------------
;;
;; `router.cljc` statically `:require`s this namespace (the substrate is a
;; foundational always-on surface alongside the router itself), so a late-bind
;; hook isn't strictly needed here. We publish one anyway for symmetry with the
;; `:event-emit/dispatch-on-event` hook and to keep the substrate
;; addressable from other artefacts that may want to fire error
;; records without static-requiring this ns.

(late-bind/set-fn! :error-emit/dispatch-on-error dispatch-on-error!)

;; The shared two-channel fan-out. `fx` / `subs` / `subs.memo` /
;; `cofx` / `router.diagnostics` reach it through this hook — they cannot
;; static-require this ns (the `error-emit` → `elision` → `frame` load cycle).
(late-bind/set-fn! :error-emit/emit-error-both emit-error-both!)

;; The frame-teardown report fires from `frame/destroy-frame!`'s finally-
;; shaped flush. `frame` MUST reach it via late-bind: a static
;; `re-frame.frame` → `re-frame.error-emit` require closes a load cycle
;; (`error-emit` → `elision` → `frame`). Per EP-0008.
(late-bind/set-fn! :error-emit/dispatch-frame-teardown-report
                   dispatch-frame-teardown-report!)

;; The general non-event always-on record helper. The EP-0008
;; SSR error-emit promotions (`:rf.error/ssr-render-failed`,
;; `:rf.error/ssr-streaming-writer-failed`, `:rf.error/malformed-hydration-
;; payload` — incl. the pre-frame FRAMELESS parse path, `:rf.error/ssr-head-
;; resolution-failed`, `:rf.error/sanitised-on-projection`,
;; `:rf.error/ssr-ring-error-view-failed`) reach the always-on axis through
;; this hook from the SSR / ssr-ring host layers (which ship above core's
;; require graph; the hook keeps them addressable without a static require).
(late-bind/set-fn! :error-emit/dispatch-error-record dispatch-error-record!)

;; The corpus-wide listener registry's register / unregister surfaces are
;; published through late-bind so `frame.cljc` can install a TRANSIENT
;; always-on error listener around the `:on-destroy` dispatch without a
;; static require (the same `error-emit` → `elision` → `frame` load cycle).
;; This is the production-survivable capture for the common `:on-destroy`-throw
;; path: the router fans the handler throw out as
;; `:rf.error/handler-exception` on THIS always-on axis, so a transient
;; listener here observes it under `goog.DEBUG=false` where the dev trace is
;; DCE'd. Survives `:advanced` + `goog.DEBUG=false` — these are the same
;; surfaces `rf/register-error-listener!` exports, just addressable from a
;; non-requiring artefact.
(late-bind/set-fn! :error-emit/register-error-listener!   register-error-listener!)
(late-bind/set-fn! :error-emit/unregister-error-listener! unregister-error-listener!)
