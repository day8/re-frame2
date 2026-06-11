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

  Observability is the only concern here — there is no app-steering
  recovery policy. Recovery is framework-owned: the per-category typed
  defaults (frame-destroyed recovers + emits, sub-exception returns nil,
  handler-exception fails loud without crashing the app). The per-frame
  `:on-error` recovery policy was REMOVED (rf2-hiqtk8, superseding the
  rf2-2hvga axis-2 / recovery-policy-eligible column): recovery is not a
  framework app-policy concern, and the policy's return value was never
  read or applied — a documented-but-fictional contract.

  A LISTENER throw is silently dropped (a sibling-isolation concern, not
  a framework error). Each listener invocation is try/catch wrapped.

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

  There is no app-steering recovery policy: the per-frame `:on-error`
  recovery policy was REMOVED (rf2-hiqtk8, superseding the rf2-2hvga
  axis-2 / recovery-policy-eligible column). Recovery is framework-owned
  (the per-category typed defaults); observability is this listener.

  Builds the tight error-record ONCE, runs
  `re-frame.elision/elide-wire-value` against `:event` with off-box
  defaults (large → `:rf.size/large-elided`; per-path sensitive
  declarations → `:rf/redacted`), then fans out to every listener.

  ## Payload hygiene (production-surviving — enforce at every site)

  The listener record (`{:error :event :event-id :frame :time
  :exception :elapsed-ms}`) is production-surviving and is NOT privacy-
  gated like the dev trace. Every caller MUST keep identifiers tight,
  elide `:event` (done here via the wire-walker), and carry NO raw
  app-db slice. Sensitive-data redaction on this path is path-based:
  the per-frame `:rf.runtime/elision` registry's `:sensitive-
  declarations` drive the wire-walker's per-slot substitutions.
  Handler-meta `:sensitive?` is no longer consulted (path-marked
  classification is the v2 mechanism; separate spec doc; in progress).

  Called from every `:rf.error/*` emission site — directly from
  `router.cljc` (handler-exception, flow-eval, frame-destroyed) and via
  the `:error-emit/dispatch-on-error` late-bind hook from `fx.cljc`,
  `subs/memo.cljc`, `subs.cljc`, and `router/diagnostics.cljc` (those
  layers cannot static-require this ns — load cycle). Returns nil."
  [error-kw event event-id frame-id exception elapsed-ms time]
  (let [;; Per rf2-3un2g §Always-on error-coord registry: source-coords
        ;; for the failing handler/sub ride the always-on parallel
        ;; registry (NOT the public registry-meta — which is stripped of
        ;; coord-keys under CLJS `:advanced + goog.DEBUG=false`). The
        ;; lookup here surfaces `{:ns :file :line}` for Sentry-style
        ;; shippers in BOTH dev AND production. Returns nil for
        ;; programmatic registrations that bypassed the macro path —
        ;; that's fine; the slot is absent from the record rather than nil.
        ;;
        ;; Per rf2-bxud9v the lookup is KIND-AWARE: the registry is keyed
        ;; by `[registry-kind id]`, so a sub-id (`:rf.error/sub-*`
        ;; categories) must resolve under `[:sub …]`, not the hardcoded
        ;; `[:event …]`. See [[error-source-coord]] / [[sub-error-categories]].
        source-coord (error-source-coord error-kw event-id)
        ;; Per-path wire-walker: paths flagged `:sensitive?` / `:large?`
        ;; via the per-frame `:rf.runtime/elision` registry get their
        ;; per-path substitutions.
        elided-event (elision/elide-wire-value event {:frame frame-id})
        record       (cond-> {:error      error-kw
                              :event      elided-event
                              :event-id   event-id
                              :frame      frame-id
                              :time       time
                              :exception  exception
                              :elapsed-ms elapsed-ms}
                       source-coord (assoc :source-coord source-coord))]
    ;; Corpus-wide listeners fan out.
    ((:fan-out registry) record))
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
    (let [record {:error          :rf.error/frame-teardown-failed
                  :frame          frame-id
                  :hook-failures  (vec hook-failures)
                  :recovery       :ignored
                  :reason         (str (count hook-failures)
                                       " frame-teardown cleanup hook(s) threw"
                                       " during destroy; teardown continued"
                                       " best-effort (skipped cleanup may have"
                                       " leaked resources)")
                  :time           time}]
      ;; Corpus-wide listeners fan out.
      ((:fan-out registry) record)))
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

;; The frame-teardown report fires from `frame/destroy-frame!`'s finally-
;; shaped flush. `frame` MUST reach it via late-bind: a static
;; `re-frame.frame` → `re-frame.error-emit` require closes a load cycle
;; (`error-emit` → `elision` → `frame`). Per EP-0008 / rf2-ini4wr.
(late-bind/set-fn! :error-emit/dispatch-frame-teardown-report
                   dispatch-frame-teardown-report!)
