(ns re-frame.error-emit
  "Always-on error-emit substrate. Per Spec 009 §What IS available in
  production §Error-emit listener.

  Survives `:advanced` + `goog.DEBUG=false`. Carries ONE fan-out path —
  the corpus-wide listener registry. Fired from every runtime `:rf.error/*`
  site PROMOTED onto this always-on axis (the promotion criterion, Spec 009
  §Observability channels) — NOT every production-reachable category: one
  whose sole surfacing is a caller-observed pure `throw-error!` stays
  diagnostic and fans no record here (the caller observes the throw at its
  own call site). The promoted set covers handler / interceptor / cofx
  exceptions, flow exceptions, reserved-fx typed throws, reactive +
  compute-sub exceptions, frame-destroyed dispatch / subscribe,
  no-such-handler, no-such-sub:

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
            [re-frame.interop        :as interop]
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

;; ---- unowned-error dev console fallback (rf2-fu75) ------------------------
;;
;; RULED (rf2-fu75, 2026-08-13): an UNTOOLED dev build DOES surface a
;; framework refusal. Before this, a captured refusal reached NO channel at
;; all unless the app had attached an `:errors` listener — measured, not
;; inferred: `dispatch` / `dispatch-sync` return normally (the interceptor
;; chain captures into `:rf/interceptor-error`; `router/emit-pipeline-
;; exception!` states the reason — "the drain must not abort"), nothing is
;; thrown, and nothing was printed. A typo'd event id produced literally
;; nothing. That trap cost rf2-06lp four browser runs and rf2-e4y9 a full
;; escalate-investigate-exonerate cycle.
;;
;; The fallback is deliberately the narrowest thing that removes it:
;;
;;   * `console.error`, NOT `js/reportError`. `reportError` reports "in the
;;     same fashion as an unhandled exception" (HTML Standard) — it
;;     dispatches a genuine window `error` event, and
;;     `implementation/scripts/run-browser-tests.cjs` treats console output
;;     as diagnostic-only but FAILS an otherwise-green run on ANY
;;     `pageerror` ("only pageerror is fatal", rf2-mwx08). Several suites
;;     exercise promoted refusals on purpose, so `reportError` would convert
;;     expected framework outcomes into runner failures. It is also
;;     semantically false here: a refusal is CAUGHT and normalised, and
;;     categories such as `:rf.error/no-such-handler` carry no exception at
;;     all, so it would synthesise an `Error` whose stack points at the
;;     reporting site rather than at the cause. (The two in-repo
;;     `reportError` sites — `freehand/root.cljs`, `substrate/spine.cljs` —
;;     preserve React's OWN default for uncaught / recoverable React
;;     callback errors. Different situation; untouched by this.)
;;
;;   * ONLY while the listener registry is EMPTY. Registering ANY `:errors`
;;     listener (`rf/register-listener! :errors …` →
;;     [[register-error-listener!]]) takes corpus-wide OWNERSHIP and the
;;     fallback goes quiet — even if that listener ignores this category or
;;     itself throws. That self-suppression is what keeps this from being a
;;     nag-diagnostic, and it is why there is NO suppression knob and no new
;;     API: the off-switch is the listener an owning app already has.
;;     Dropping the last listener resumes the fallback. Xray does NOT
;;     populate this registry (it rides the dev-only TRACE axis — `router`
;;     forwards to `trace/emit-error!`), so a console line may coexist with
;;     an Xray row; the tutorial already frames console + Xray as
;;     complementary and the duplication is accepted.
;;
;;   * DEV + BROWSER-HOSTED only. `interop/debug-enabled?` (`@define`
;;     `goog/DEBUG`) is the outer gate, so `:advanced` + `goog.DEBUG=false`
;;     constant-folds the entire body away — neither the prefix literal nor
;;     the call path survives into a production artefact. A bare
;;     `#?(:cljs …)` would be too broad: Node-targeted CLJS and CLJS SSR
;;     have a console too and stay listener-only, for exactly the reason the
;;     JVM lane does — those are REPL / test / server lanes where the caller
;;     observes the dispatch directly and a listener is one line, and
;;     neither measured incident happened there. `js/document` presence is
;;     this repo's DOM-host discriminator (see
;;     `resources/revalidate_listeners.cljc`).
;;
;; The record goes to the console AS A VALUE, with the original
;; `:exception` object as its own separate argument when the category
;; carries one — never a flattened string, so the host's inspector renders
;; the structure and the real stack survives. Consequence, accepted rather
;; than engineered around: the record's raw `:exception` (the deliberate
;; advanced-listener contract) now also reaches a local dev console.
;; Per-category ownership, sink discovery, a formatter, deduplication and a
;; suppression setting all stay REJECTED as premature.
;;
;; Everything is try/catch wrapped: observability must never abort the drain.

(defn- report-unowned-error!
  "Print `record` to the browser console when NOTHING owns it. Dev builds
  only, browser hosts only, and only while the corpus-wide `:errors`
  listener registry is EMPTY — see §Unowned-error dev console fallback
  above for why each of those three conditions is load-bearing.

  A no-op on the JVM, on Node-targeted CLJS (and CLJS SSR), and in any
  `goog.DEBUG=false` build, where the whole body constant-folds away.
  Never throws. Returns nil."
  [record]
  #?(:clj nil
     :cljs
     (when interop/debug-enabled?
       (try
         (when (and (empty? @listeners)
                    (exists? js/document)
                    (exists? js/console)
                    (fn? (.-error js/console)))
           (if-some [ex (:exception record)]
             (.error js/console "[re-frame2]" record ex)
             (.error js/console "[re-frame2]" record)))
         (catch :default _ nil))
       nil)))

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
;; EVENT id (a dispatch / dispatch-sync into a destroyed frame) AND with a
;; SUB id (a subscribe into a destroyed frame), and also from the UI
;; frame-bundle's stale-op seam (`re-frame.ui.frames`) for a `:dispatch` /
;; `:dispatch-sync` / `:subscribe` / `:capture` op against a dead
;; incarnation. The category keyword ALONE CANNOT name the realm: an
;; event-id and a sub-id may legitimately SHARE a keyword — they live in
;; SEPARATE registries (`[:event id]` vs `[:sub id]`), so a bare
;; `[:sub]`-then-`[:event]` probe attributes a same-keyword collision to the
;; WRONG realm (rf2-xgkgx — the earlier comment here wrongly claimed sub-ids
;; and event-ids never collide). Resolution therefore pivots on the exact
;; operation realm the record already carries in `:op`: `:dispatch` /
;; `:dispatch-sync` → `[:event]`, `:subscribe` → `[:sub]`, `:capture` →
;; NEITHER (a `(frame)` read that resolved a dead incarnation before any op
;; ran — no component source). The core router / subs emitters carry no
;; `:op`; for them the lookup falls back to `[:sub]`-then-`[:event]`, which
;; keeps them correct (the subs sub-id hits `[:sub]`; the router event-id
;; misses `[:sub]` then hits `[:event]`). A miss on the resolved realm falls
;; through to nil → the `:source-coord` slot is absent.

(def ^:private sub-error-categories
  "Categories whose `:event-id` slot carries a SUB id — their source
  coords live under `[:sub sub-id]` in the always-on registry, so
  `dispatch-on-error!` resolves them there rather than under the
  `[:event …]` default. Covers the parametric input-fn failures, the
  reactive sub-exception, and the observation port's on-change-failure
  wrapper (whose `:event-id` carries the former owner's ENTRY SUB id —
  rf2-q3fmqm: `[:sub …]` is the ONLY lookup realm, so a macro-registered
  sub resolves its exact coordinate and a same-id EVENT registration can
  never steal attribution; a programmatic sub registration resolves nil
  and the slot stays absent), so the always-on production error records
  carry the failing sub's `:source-coord`."
  #{:rf.error/sub-input-fn-exception
    :rf.error/sub-input-fn-bad-return
    :rf.error/sub-exception
    :rf.error/no-such-sub
    :rf.error/observation-on-change-failed})

;; ---- raw query-vector identity on the always-on error :event slot --------
;;
;; #6441 / rf2-zwgqe (RULED — option c, accepted fail-open, documented): a
;; subscription QUERY VECTOR is IDENTITY — the sub-cache key (Spec 006), the
;; skip-dedup key, the reactive-graph edge endpoint. Identity is structurally
;; public to every layer that touches the cache, so it is NEVER redacted at the
;; classification chokepoint; it egresses VERBATIM on EVERY query-vector-bearing
;; slot, INCLUDING "the always-on error `:query-v` / `:event` slots" (the
;; ruling's own words). The only retained exception is the SEPARATE Spec 010
;; schema-axis backstop (a `:sensitive?`-schema'd sub's validation-failure trace
;; whole-slot scrubs `:rf.sub/query-v` in `re-frame.schemas.validate`), which
;; this path does not touch.
;;
;; `dispatch-on-error!` runs `:event` through `elision/elide-wire-value` (the
;; frame's durable app-db elision registry). For a DISPATCHED EVENT that is
;; payload hygiene. For a SUB error `:event` is the query vector, and a concrete
;; integer app-db path coincidentally matching a query-vector coordinate mutates
;; identity at egress (`[1]` turns `[:patient/record "SECRET"]` into
;; `[:patient/record :rf/redacted]`). The closed decision note WRONGLY reasoned
;; an app-db path could not match a query vector; concrete integer paths prove
;; otherwise (`elision_test.clj` pins that behaviour — it is correct for
;; position-precise app-db elision and stays). So the always-on error path must
;; skip elision for a query-vector `:event`.

(def ^:private query-vector-event-categories
  "Every production `:rf.error/*` category whose positional `:event` slot
  carries a subscription QUERY VECTOR (raw IDENTITY per #6441 / rf2-zwgqe), NOT
  a dispatched event. ENUMERATED STRUCTURALLY by reading each emit site — NOT
  matched by a category-name prefix: a `sub-*` prefix check catches the
  reactive/compute + input-fn categories but MISSES the observation-port
  query-vector categories and the frame-destroyed subscribe realm, re-leaking
  the exact rf2-s3n6h 'bound that does not bound' class.

  A SUPERSET of [[sub-error-categories]] (whose narrower purpose is `[:sub id]`
  SOURCE-COORD resolution). It ALSO includes the two observation-port
  categories whose `:event` is the handle's query vector but whose source-coord
  is deliberately NOT `[:sub]`-resolved:
    - `:rf.error/read-after-release`         (`observation/read` on a released handle)
    - `:rf.error/observation-retry-exhausted`(`observation/acquire!` — fired for a
                                             frame it KNOWS is LIVE, so the
                                             coincidental-path match genuinely bites)
  Both pass the handle's `query-v` through `observation/emit-and-throw!` /
  `observation/read`. The realm-AMBIGUOUS `:rf.error/frame-destroyed` is handled
  separately in [[raw-identity-query-vector-event?]] — it carries a query vector
  only in the `:subscribe` operation realm."
  #{:rf.error/sub-input-fn-exception
    :rf.error/sub-input-fn-bad-return
    :rf.error/sub-exception
    :rf.error/no-such-sub
    :rf.error/observation-on-change-failed
    :rf.error/read-after-release
    :rf.error/observation-retry-exhausted})

(defn- raw-identity-query-vector-event?
  "STRUCTURAL discriminator: does the positional `event` slot for an `error-kw`
  / `op` error record carry a subscription QUERY VECTOR that must egress
  VERBATIM (raw IDENTITY per #6441 / rf2-zwgqe), rather than a dispatched EVENT
  whose args keep their existing per-path app-db elision (payload hygiene)?

  True for every category in [[query-vector-event-categories]], and for the
  realm-ambiguous `:rf.error/frame-destroyed` ONLY in the `:subscribe`
  operation realm — a captured / superseded subscribe passes the attempted
  query vector as `:event` (the record carries `:op :subscribe`, stamped by
  `subs/emit-frame-destroyed-recovery!` and `router/emit-frame-destroyed!`),
  whereas a `:dispatch` / `:dispatch-sync` passes a dispatched event that keeps
  its elision. Keyed on `=`, never `identical?`, on the keyword operands (a
  `.cljc` `identical?` keyword compare is JVM-only sound, CLJS-unreachable —
  #6365)."
  [error-kw op]
  (or (contains? query-vector-event-categories error-kw)
      (and (= :rf.error/frame-destroyed error-kw)
           (= :subscribe op))))

(defn- error-source-coord
  "Resolve the `{:ns :file :line}` source-coord for the failing `id` of an
  `error-kw` category, pivoting on the registry kind the `id` was
  registered with. Returns nil when no coords were captured
  (programmatic registration that bypassed the macro path, or an id that
  was never registered) — the caller `cond->`s the slot in, so nil means
  the `:source-coord` slot is ABSENT from the record rather than nil.

    - `:rf.error/sub-*` categories → look under `[:sub id]`.
    - `:rf.error/frame-destroyed` is realm-AMBIGUOUS on the id alone (an
      event-id and a sub-id may legitimately SHARE a keyword — they live in
      SEPARATE registries), so it pivots on the exact operation realm the
      record already carries in `op` (rf2-xgkgx / rf2-a2x2w — the `:op` realm
      attribution the record carries, RATIFIED PUBLIC wherever the realm is
      known: the frame-bundle stale-op seam, the `capture-frame` pre-check
      seam, and the router's late captured-op fences all stamp it — see
      `router/emit-frame-destroyed!` and Spec 009 §Error contract):
        - `:dispatch` / `:dispatch-sync` → the failing op is a DISPATCH, so
          the coord lives under `[:event id]`.
        - `:subscribe`                   → a SUBSCRIBE, coord under `[:sub id]`.
        - `:capture`                     → a `(frame)` read that resolved a
          dead incarnation BEFORE any op ran — no component source, so
          fabricate NEITHER coord (nil), even were an id somehow present.
        - `op` absent (the ordinary address-directed router DISPATCH emitter
          does not carry it — the subs SUBSCRIBE emitter now stamps
          `:op :subscribe` (rf2-alk8a) and so resolves realm-exact via the
          `:subscribe` case above, NOT this fallback) → fall back to
          `[:sub]`-then-`[:event]`, which keeps the remaining router-dispatch
          caller correct (the event-id misses `[:sub]` then hits `[:event]`).
    - every other category → look under `[:event id]`."
  [error-kw id op]
  (when id
    (cond
      (contains? sub-error-categories error-kw)
      (source-coords/error-coords-for :sub id)

      (= :rf.error/frame-destroyed error-kw)
      (case op
        (:dispatch :dispatch-sync) (source-coords/error-coords-for :event id)
        :subscribe                 (source-coords/error-coords-for :sub id)
        :capture                   nil
        ;; `op` absent — the ordinary address-directed core router DISPATCH
        ;; emitter (the subs SUBSCRIBE emitter now stamps `:op :subscribe` —
        ;; rf2-alk8a — and resolves realm-exact via the `:subscribe` case above).
        (or (source-coords/error-coords-for :sub id)
            (source-coords/error-coords-for :event id)))

      :else
      (source-coords/error-coords-for :event id))))

;; ---- emission -------------------------------------------------------------

(defn dispatch-on-error!
  "Surface an `:rf.error/*` event through the always-on corpus-wide
  error-emit listener registry (surface #4). Always-on (NOT gated by
  `re-frame.interop/debug-enabled?`) — fires in CLJS production builds
  where the trace surface is elided.

  Fires for every catalogued RUNTIME `:rf.error/*` category PROMOTED
  onto this always-on listener axis (the promotion criterion, Spec 009
  §Observability channels). This is the off-box observability stream
  (Sentry / Datadog / Xray / the SSR error-projection listener) and is
  the production-survivable source of truth for THAT set. Promotion is
  NOT automatic for every production-reachable category: a
  production-reachable category whose sole surfacing is a caller-observed
  pure `error/throw-error!` stays DIAGNOSTIC and does NOT fan a record
  here — the caller observes the throw at the call site and fixes the
  declaration there, so the fact does not silently disappear
  (`:rf.error/custom-element-conflict`, `:rf.error/dispatch-disconnected`,
  `:rf.error/flush-convergence-exceeded`). (Dev-only-validation /
  registration-time categories — dev schema checks,
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

  Called from every PROMOTED `:rf.error/*` emission site — directly from
  `router.cljc` (handler-exception, flow-eval, frame-destroyed) and via
  the `:error-emit/dispatch-on-error` late-bind hook from `fx.cljc`,
  `subs/memo.cljc`, `subs.cljc`, and `router/diagnostics.cljc` (those
  layers cannot static-require this ns — load cycle). Returns nil.

  ## Frame-owned sink-route suppression (rf2-bf0io)

  The trailing `route-frame?` (default true) gates ONLY the EP-0015
  frame-owned observability sink route below — the corpus-wide listener
  fan-out (axis 1's off-box source of truth) ALWAYS fires regardless.
  `re-frame.ui.frames`' `emit-and-throw-frame-destroyed!` passes false for a
  KNOWN-DEAD-incarnation `(frame)`-bundle emission: the captured bare frame id
  no longer names the incarnation the failure belongs to, so resolving it to a
  live same-id SUCCESSOR would deliver a dead incarnation's failure into the
  successor's own `:observability :errors` sink. This is the event-centric
  mirror of the union-path `route-frame?` seam rf2-vxgfnd.118 added for the
  post-dissoc teardown report. Every ordinary live / address-directed caller
  keeps the default, so normal frame-owned routing is untouched."
  ([error-kw event event-id frame-id exception elapsed-ms time]
   (dispatch-on-error! error-kw event event-id frame-id exception elapsed-ms time nil true))
  ([error-kw event event-id frame-id exception elapsed-ms time attrs]
   (dispatch-on-error! error-kw event event-id frame-id exception elapsed-ms time attrs true))
  ([error-kw event event-id frame-id exception elapsed-ms time attrs route-frame?]
   (when (trace/continuation-live?)
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
           ;; `[:event …]`. For the realm-ambiguous `:rf.error/frame-destroyed`
           ;; category the resolution ALSO pivots on the operation realm the
           ;; record carries in its `:op` attribution (rf2-xgkgx / rf2-a2x2w —
           ;; the ratified-public `:op` realm slot, which also STEERS this
           ;; source-coord resolution), so a same-keyword event vs subscription
           ;; is attributed to the correct realm. See [[error-source-coord]] /
           ;; [[sub-error-categories]].
           source-coord (try
                          (error-source-coord error-kw event-id (:op attrs))
                          (catch #?(:clj Throwable :cljs :default) e
                            (when (trace/continuation-live?)
                              (throw e))))]
       ;; Generation/source resolution is a callback-bearing stage.
       (when (trace/continuation-live?)
         (let [;; #6441 / rf2-zwgqe: a subscription QUERY VECTOR is raw IDENTITY
               ;; on the always-on error `:event` slot — it egresses VERBATIM,
               ;; NEVER app-db-elided (a concrete integer path coincidentally
               ;; matching a query-vector coordinate would otherwise mutate
               ;; identity). Enumerated STRUCTURALLY (see
               ;; [[raw-identity-query-vector-event?]] /
               ;; [[query-vector-event-categories]]), not by a category-name
               ;; prefix. A dispatched EVENT keeps its per-path elision below.
               raw-identity-event? (raw-identity-query-vector-event?
                                     error-kw (:op attrs))
               ;; Per-path wire-walker: paths flagged `:sensitive?` / `:large?`
               ;; via the per-frame `:rf.runtime/elision` registry get their
               ;; per-path substitutions. Skipped for a raw-identity query
               ;; vector, which egresses verbatim.
               elided-event (if raw-identity-event?
                              event
                              (try
                                (elision/elide-wire-value event {:frame frame-id})
                                (catch #?(:clj Throwable :cljs :default) e
                                  (when (trace/continuation-live?)
                                    (throw e)))))
               ;; Lift the component-attributed slots into the always-on
               ;; record so an off-box shipper sees WHICH interceptor / cofx
               ;; failed in production (the `:event-id` slot carries the EVENT
               ;; id for these categories; the failing component id would
               ;; otherwise ride only the DCE'd dev-trace tags). `cond->`'d in
               ;; — absent when nil/blank, so the tight record shape is
               ;; unchanged for categories that pass no `attrs` (or whose
               ;; failing id already equals `:event-id`).
               ;; Attribution slots the caller lifts onto the always-on record
               ;; are the component ids / discriminators the category promises:
               ;; `:failing-id` / `:reason` for interceptor / cofx categories,
               ;; and `:flow-id` + `:where :flow-eval` for flow-eval so its
               ;; attribution SURVIVES an egress profile that drops
               ;; `:exception` (rf2-z1332c — Spec 009 §Error event catalogue /
               ;; Spec 013 §Trace stream ordering). Merged UNDER the base
               ;; observability fields, which always win; nil-valued slots are
               ;; dropped. Callers keep these to tight identifiers — this
               ;; record is production-surviving and NOT privacy-gated.
               attribution (into {} (remove (comp nil? val)) attrs)
               record      (cond-> (merge attribution
                                           {:error      error-kw
                                            :event      elided-event
                                            :event-id   event-id
                                            :frame      frame-id
                                            :time       time
                                            :exception  exception
                                            :elapsed-ms elapsed-ms})
                               source-coord (assoc :source-coord source-coord))]
           ;; Elision is callback-bearing. Corpus sibling fanout is one
           ;; already-linearized publication; frame routing is a later one.
           (when (trace/continuation-live?)
             ;; rf2-fu75 — decided immediately before publication, under the
             ;; SAME continuation/ownership conditions, exactly once per
             ;; fully built record. Fires only when the registry is empty, so
             ;; the fan-out below is a no-op whenever this printed.
             (report-unowned-error! record)
             ((:fan-out registry) record trace/continuation-live?)
             ;; EP-0015 §9: frame-owned observability sink route. Pass the RAW
             ;; event so the sink projects under its own egress profile rather
             ;; than double-eliding. `raw-identity-event?` (#6441 / rf2-zwgqe)
             ;; rides through so the sink keeps a sub query vector VERBATIM on
             ;; THIS second egress route too — otherwise `project-error-record`
             ;; app-db-walks `:event` and the same coincidental integer path
             ;; redacts identity here. Late-bound to avoid a require cycle.
             ;; `route-frame?` false (rf2-bf0io) SUPPRESSES ONLY this route for a
             ;; known-dead-incarnation UI-bundle emission, so a dead incarnation's
             ;; bare id can never resolve to a same-id successor's error sink; the
             ;; corpus fan-out above still fired.
             (when (and route-frame? (trace/continuation-live?))
               (when-let [route-error! (late-bind/get-fn-cached
                                         :observability/route-error)]
                 (try
                   (route-error! error-kw event event-id frame-id exception
                                 elapsed-ms time nil raw-identity-event?)
                   (catch #?(:clj Throwable :cljs :default) e
                     (when (trace/continuation-live?)
                       (throw e)))))))))))
   nil))

;; ---- the two-channel fan-out helper ---------------------------------------
;;
;; Every PROMOTED runtime `:rf.error/*` site (the always-on set — not every
;; production-reachable category; a caller-observed pure `throw-error!` such as
;; `:rf.error/custom-element-conflict` stays diagnostic) fans the SAME category
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
  production). The shared two-channel fan-out every catalogued PROMOTED
  (always-on) runtime error site uses.

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

  The optional trailing `record-attrs` map carries CATEGORY-SPECIFIC
  attribution the caller wants on the always-on record (axis 1) INDEPENDENT of
  the dev-trace tags — e.g. the flow-eval category lifts `{:flow-id … :where
  :flow-eval}` so its attribution survives an egress profile that drops
  `:exception` (rf2-z1332c). It is NOT read from `trace-tags` (a `:where` there
  would leak onto unrelated categories' records — e.g. legacy-root's `'rf/reg-
  event`); the caller passes exactly the tight slots it wants lifted. `nil`
  (the default) leaves every existing caller's record unchanged.

  Returns nil. Reached directly by `router.cljc` (static require) and via the
  `:error-emit/emit-error-both` late-bind hook by `fx` / `subs` / `subs.memo` /
  `cofx` / `router.diagnostics` (those layers cannot static-require this ns — a
  load cycle through `elision` → `frame`).

  The trailing `route-frame?` (default true — rf2-bf0io) threads straight into
  [[dispatch-on-error!]]'s frame-owned sink route gate: false suppresses ONLY
  that route (the corpus record + the axis-2 dev trace still fire), for the UI
  dead-incarnation `(frame)`-bundle emit that must not deliver a dead
  incarnation's failure into a same-id successor's sink."
  ([category event event-id frame exception elapsed-ms time trace-tags]
   (emit-error-both! category event event-id frame exception elapsed-ms time
                     trace-tags nil true))
  ([category event event-id frame exception elapsed-ms time trace-tags record-attrs]
   (emit-error-both! category event event-id frame exception elapsed-ms time
                     trace-tags record-attrs true))
  ([category event event-id frame exception elapsed-ms time trace-tags record-attrs
    route-frame?]
   ;; Axis 1 — always-on corpus-wide listener (+ EP-0015 frame-owned sink).
   ;; Start from the caller's category-specific `record-attrs` (axis-1 only),
   ;; then lift the component-attributed `:failing-id` / `:reason` from the
   ;; trace-tags when the failing component is DISTINCT from the dispatched
   ;; event (interceptor / cofx categories). The distinct-from-event-id guard
   ;; keeps the record tight for the categories whose `:failing-id` already
   ;; equals `:event-id`. `route-frame?` gates ONLY the frame-owned sink route
   ;; inside `dispatch-on-error!` (rf2-bf0io) — the corpus fan-out is unconditional.
   (let [failing-id (:failing-id trace-tags)
         attrs      (cond-> record-attrs
                      (and (some? failing-id) (not= failing-id event-id))
                      (assoc :failing-id failing-id :reason (:reason trace-tags)))]
     (dispatch-on-error! category event event-id frame exception elapsed-ms time
                         attrs route-frame?))
   ;; Axis 2 — dev-only trace surface; DCEs under `:advanced` + `goog.DEBUG=false`
   ;; (the `interop/debug-enabled?` gate lives inside `trace/emit-error!`).
   (when (trace/continuation-live?)
     (trace/emit-error! category trace-tags))
   nil))

;; ---- first-emission provenance (exact-once at containment drains) ---------
;;
;; Several fail-loud sites EMIT their category's canonical record (through
;; [[emit-error-both!]] / [[dispatch-on-error!]]) and THEN throw the matching
;; canonical typed error — e.g. the observation port's `read` on a released
;; handle. A boundary that CATCHES such a throwable to keep draining siblings
;; (a containment drain, e.g. the port's disposal-notification drain) cannot
;; otherwise tell a throwable whose category is ALREADY visible from one that
;; has never been fanned: re-dispatching every caught typed throwable
;; double-emits the already-fanned ones — TWO always-on records for ONE
;; runtime error, the second able to overwrite the source's correct
;; frame/query attribution with the catching context's — while never
;; re-dispatching silently loses the unfanned ones at a swallowing boundary
;; (rf2-wbkjk9; Spec 009's one-runtime-error law).
;;
;; The repair is explicit provenance ON THE THROWABLE — never a global
;; seen-error registry (process-global dedup state would suppress genuinely
;; distinct recurrences of the same category and leak across frames/tests):
;; an emit-then-throw site stamps [[fanned-at-source-key]] into the ex-data
;; it throws (via the canonical builder's `:extra`), and a containment drain
;; consults [[fanned-at-source?]] — already-fanned ⇒ exactly-once is ALREADY
;; satisfied at the source with the source's own attribution, nothing more on
;; either channel; unfanned ⇒ the drain owns the first (and only) emission.
;;
;; [[canonical-typed-error?]] is the drain-side companion: STRUCTURAL
;; provenance that a caught throwable was built by the canonical thrown-error
;; builder (`re-frame.error/thrown-ex-info` — the single chokepoint every
;; framework throw routes through) under the RESERVED `rf.error` catalogue
;; namespace. It replaces `:rf.error/id`-truthiness classification: an
;; application ex-info carrying `{:rf.error/id :app/x}` (non-reserved
;; namespace), a malformed id (non-keyword), or a bare imitation of a
;; canonical id without the builder's required `:reason` sentence all
;; classify FALSE — a drain wraps them in its own stable catalogued category
;; instead of fanning them as though they were canonical framework
;; categories. Framework emissions under the reserved namespace are pinned
;; to catalogue rows at build time by the Spec 009 catalogue source-scan
;; gate (`error-catalogue-channel-conformance-test`), so the reserved-shape
;; check IS the runtime spelling of catalogue membership.

(def fanned-at-source-key
  "Ex-data slot carrying FIRST-EMISSION PROVENANCE (rf2-wbkjk9): `true` when
  the throwing site had ALREADY fanned this failure's canonical record per
  its category's channel contract before throwing (the emit-then-throw
  idiom), so a downstream containment drain must NOT re-emit it on either
  channel. Stamped by the throwing site via the canonical builder's `:extra`
  (e.g. `re-frame.substrate.observation`'s fail-loud surfaces); consulted
  through [[fanned-at-source?]]. Framework-internal — never part of the
  public thrown-error shape contract."
  ::fanned-at-source)

(defn fanned-at-source?
  "True when caught throwable `t` carries [[fanned-at-source-key]] — its
  failure was already surfaced by its source's own emission, with the
  source's own attribution, so exactly-once is already satisfied."
  [t]
  (true? (get (ex-data t) fanned-at-source-key)))

(defn canonical-typed-error?
  "True when caught throwable `t` carries the canonical framework
  thrown-error shape (Spec 009 §The thrown-error shape) under the RESERVED
  `rf.error` catalogue namespace: a keyword `:rf.error/id` whose namespace is
  `\"rf.error\"` AND the builder's required `:reason` human sentence. This is
  structural provenance of `re-frame.error/thrown-ex-info` — NOT an
  id-truthiness test (rf2-wbkjk9): a non-reserved application id, a
  malformed non-keyword id, or a bare canonical-id imitation without the
  builder shape all classify false, so a containment drain wraps them
  rather than letting them spoof a canonical category."
  [t]
  (let [data (ex-data t)
        id   (:rf.error/id data)]
    (and (keyword? id)
         (= "rf.error" (namespace id))
         (string? (:reason data)))))

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

(defn- dispatch-error-record*
  "Internal union-record fan-out with explicit frame-route authority.

  `route-frame?` is false only for an exact-incarnation teardown report emitted
  after that incarnation has been dissociated. Its corpus-wide fact survives,
  but the bare frame id can no longer name A's sink policy and must not resolve
  to a same-id successor B."
  [record route-frame?]
  ;; rf2-fu75 — the union-record fan-out site's half of the unowned-error dev
  ;; console fallback. Same rule as `dispatch-on-error!`: immediately before
  ;; publication, under the same (here unconditional) conditions, exactly once
  ;; per record, and only while the registry is empty.
  (report-unowned-error! record)
  ((:fan-out registry) record trace/continuation-live?)
  (when (and route-frame? (trace/continuation-live?))
    (when-let [route-error-record! (late-bind/get-fn-cached
                                     :observability/route-error-record)]
      (try
        (route-error-record! record)
        (catch #?(:clj Throwable :cljs :default) e
          (when (trace/continuation-live?) (throw e))))))
  nil)

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
  (dispatch-error-record* record true)
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
  nil)

;; ---- frame-teardown report (EP-0008 promotion criterion) ------------------
;;
;; The frame-destroy teardown recipe runs many optional late-bound cleanup
;; hooks plus a few guarded direct steps (notably the
;; `:frame/notify-machine-destruction!` machine cascade). A teardown STEP
;; throwing is production-reachable (long-lived SSR / tooling), is a
;; resource-leakage class (skipped teardown the next operation cannot see
;; locally), and compounds with process lifetime — all three legs of the
;; Spec 009 §promotion criterion hold. Rather than fan ONE always-on
;; emission out per failed step (an SSR per-request-destroy × M req/s flood
;; of the production error shipper), the runtime accumulates the per-step
;; failures and emits ONE bounded `:rf.error/frame-teardown-failed` record
;; carrying a `:hook-failures` vector (Spec 009 §Channel-promotion catalogue
;; rows — the report-vs-per-item idiom). The dev per-step diagnostic
;; (`:rf.warning/teardown-hook-exception`, DCE'd in prod) stays at its
;; causal positions, funneled through the shared
;; `frame/record-teardown-failure!` boundary both catch sites route through
;; (`frame/safe-call-hook!` for the late-bound hooks,
;; `frame/safe-teardown-step!` for the guarded direct steps).

(defn dispatch-frame-teardown-report!
  "Surface ONE always-on `:rf.error/frame-teardown-failed` report through
  the corpus-wide error-emit listener registry (surface #4). Always-on
  (NOT gated by `re-frame.interop/debug-enabled?`) — fires in CLJS
  production builds where the dev per-hook trace is DCE'd.

  Per Spec 009 §Observability channels §Channel-promotion catalogue rows
  (EP-0008 Open Issue 1, ruled): the frame-destroy case satisfies the
  promotion criterion with a SINGLE bounded report naming the higher-
  level fact, with the per-step detail carried as the `hook-failures`
  payload vector — NOT one record per failed step. The destroy IS the
  fact; the failed steps are detail rows, and one record preserves the
  which-steps-failed-together correlation external shippers will not
  reliably re-group.

  Builds the catalogue-shaped record (`:tags` keys `:frame`,
  `:hook-failures`, `:reason`; `:recovery :ignored` — teardown is
  best-effort) and fans it out. Called at most once per destroy
  (whether teardown completes or aborts) via the
  `:error-emit/dispatch-frame-teardown-report` late-bind hook from
  `frame.cljc`'s finally-shaped flush boundary (`frame` cannot static-
  require this ns — `error-emit` → `elision` → `frame` is a load cycle).

  `hook-failures` is a non-empty vector of
  `{:hook <step-key> :exception <ex> :where <catch-boundary>}` entries — one
  per failed teardown step, accumulated during the teardown walk through the
  shared `record-teardown-failure!` boundary. `:hook` names the late-bound
  cleanup-hook key that threw OR the guarded direct-step key (e.g.
  `:frame/notify-machine-destruction!`); `:where` is the catch boundary that
  recorded it — `:safe-call-hook!` for a late-bound hook, `:safe-teardown-step!`
  for a guarded direct step.
  Returns nil; a no-op when `hook-failures` is empty (no failures, no
  report)."
  ([frame-id hook-failures time]
   (dispatch-frame-teardown-report! frame-id hook-failures time true))
  ([frame-id hook-failures time route-frame?]
   (when (seq hook-failures)
     ;; The bounded report is itself a non-event union record. The actual
     ;; destroy recipe passes `route-frame? false`: by its finally boundary A
     ;; has been dissociated, so the bare id can no longer authorise A's
     ;; frame-owned sink and must not redirect the report into same-id B.
     ;; The 3-arity preserves direct/live-frame callers' established route.
     (dispatch-error-record*
       {:error          :rf.error/frame-teardown-failed
        :frame          frame-id
        :hook-failures  (vec hook-failures)
        :recovery       :ignored
        ;; rf2-d1yhx — "step(s)", not "cleanup hook(s)": a `:hook-failures`
        ;; entry names a late-bound cleanup hook OR a guarded direct step
        ;; (e.g. `:frame/notify-machine-destruction!`), so hook-only wording
        ;; misreports a destroy whose only failure was a direct step.
        :reason         (str (count hook-failures)
                             " frame-teardown step(s) threw"
                             " during destroy; teardown continued"
                             " best-effort (skipped cleanup may have"
                             " leaked resources)")
        :time           time}
       route-frame?))
   nil))

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
