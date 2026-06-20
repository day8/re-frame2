(ns re-frame.registrar
  "The global registrar — `(kind, id) → metadata` lookup.

  Per Spec 001, the registrar is a single per-process map keyed by kind.
  Frames isolate STATE; the registrar is shared across all frames.

  Reserved kinds (closed v1 set, per Spec 001 §Registry model):
    :event :sub :fx :cofx :interceptor :view :frame :route :head
    :error-projector :flow :resource

  Machine guards and actions are NOT a registrar kind. Per Spec 005 the
  machine spec's `:guards` / `:actions` maps are the single source of
  truth — the runtime resolves them through the machine spec, never the
  registrar.
  Their dev-only fn-source handler-meta surface (`(rf/handler-meta
  :machine-guard [machine-id guard-id])`, consumed by Xray's
  focused-transition lens + re-frame-pair source-jump) is DERIVED on
  demand from the machine's existing `:event` registration spec (the
  co-located `:guards` / `:actions` entries each carry `:source-code` /
  `:source-coords` in dev), not stored as a separate registrar entry —
  see `re-frame.core-machines/machine-handler-meta`. Production-elided
  per Spec 009 §Production builds (the macro emits no `:source-*` slots
  under `goog.DEBUG=false`, so the derivation returns nil).

  App-db schemas are NOT a registrar kind. `reg-app-schema`
  writes only to the schemas artefact's own per-frame side-table
  (`schemas/schemas-by-frame`), which is the single source of truth.
  Tools introspecting app-db schemas go through `schemas/app-schemas`
  / `schemas/app-schema-meta-at`.

  ## Production elision

  The :rf.registry/* trace emit sites in this namespace are gated on
  `re-frame.interop/debug-enabled?` (per Spec 009 §Production builds) so
  that the late-bind lookup, the call into trace/emit!, and the small
  metadata map allocation all disappear from `:advanced` production
  bundles where `goog.DEBUG` is `false`.

  ## Pure-documentation metadata elision

  Per Spec 001 §Production elision contract, a registration-metadata key
  is **elidable** in production iff it has ZERO production runtime use AND
  zero production observability use — i.e. it is pure dev/authoring
  documentation. `:doc` is the one such key across every `reg-*` surface;
  every other standard key is load-bearing in production
  (`:sensitive?` / `:large?` drive redaction / egress projection;
  `:tags` / `:interceptors` / the resource-mutation runtime keys drive
  runtime behaviour; `:rf/id` + the handler fn ARE the registration).

  `register!` is the single chokepoint every `reg-*` surface funnels
  through, so the strip lives here: under `:advanced` + `goog.DEBUG=false`
  (`interop/debug-enabled?` constant-folds to `false`) `strip-pure-
  documentation` drops the pure-documentation keys from the metadata
  before it is stored, so `(rf/handler-meta kind id)` carries no `:doc`
  in production — consistent with the source-coords already being absent
  there (Spec 001 §Production elision contract, Policy A). The outermost
  `interop/debug-enabled?` gate lets Closure constant-fold the strip away
  in dev and the dev `:doc` retention away in prod.

  The DCE of the dev-only `:doc` STRING bytes from the bundle rides the
  same `re-frame.events/merge-form-source` gate that already elides the
  whole `(reg-event-X :id {:doc \"…\"} …)` form-source under
  `goog.DEBUG=false` (the elision-probe `:probe/cs-event` sentinel
  covers it); the strip here pins the *handler-meta* absence."
  (:require [re-frame.error         :as error]
            [re-frame.interop       :as interop]
            [re-frame.late-bind     :as late-bind]
            [re-frame.source-coords :as source-coords]
            [re-frame.source-store  :as source-store]))

#?(:clj (set! *warn-on-reflection* true))

;; ---- the kind set ---------------------------------------------------------

(def kinds
  "The closed set of registry kinds for v1. Adding a new kind is a Spec change.

  Machine guards/actions are NOT registrar kinds — the runtime resolves
  them through the machine spec's `:guards` / `:actions` maps, and their
  dev-only fn-source handler-meta is DERIVED from the machine's `:event`
  registration spec (see `re-frame.core-machines/machine-handler-meta`),
  not stored here.

  App-db schemas are NOT a registrar kind — they live in the schemas
  artefact's per-frame side-table (`schemas/schemas-by-frame`).
  Introspect via `schemas/app-schemas` / `schemas/app-schema-meta-at`.

  `:resource` (Spec 016 §Registration) is the resources
  artefact's registrar kind — `reg-resource` registers a resource spec
  under it. Deliberately `:resource`, NOT `:query` (which would collide
  with route query-params + prior-art names). Reserved whether or not the
  Resources artefact ships.

  `:mutation` (Spec 016 §Deferred slices / EP-0003 §Mutations,
  the first public-beta gate) is the resources artefact's mutation
  registrar kind — `reg-mutation` registers a mutation spec under it (the
  causal-write counterpart of `:resource`). Reserved whether or not the
  Resources artefact ships.

  `:resource-scope` (Spec 016 §Named resource-scope resolvers /
  EP-0016 D3) is the resources artefact's THIRD kind — `reg-resource-scope`
  registers a pure named scope resolver under it (the one scope-resolution
  currency reused by resource registration, route resources, ensure /
  subscriptions, invalidation descriptors, and clear-scope). Deliberately a
  distinct kind, not folded into `:resource`. Reserved whether or not the
  Resources artefact ships.

  `:interceptor` (Spec 001 §Interceptors / EP-0022) is the
  registered-interceptor registrar kind — `reg-interceptor` stores an
  interceptor DESCRIPTOR (`{:before}` / `{:after}` / `{:before :after}` /
  `{:factory}`) under it, keyed by a qualified keyword id. Event/frame
  `:interceptors` chains carry REFERENCES (bare keyword / `[id arg]`) into
  this kind; the runtime resolves the refs to executable interceptor values
  at chain assembly (`re-frame.interceptor-registry`). Application ids are
  application-owned; framework standard refs live under `:rf.interceptor/*`."
  #{:event :sub :fx :cofx :interceptor :view :frame :route :head
    :error-projector :flow :resource :mutation :resource-scope})

(defn valid-kind? [k]
  (contains? kinds k))

;; ---- the registry state ---------------------------------------------------

(defonce
  ^{:doc "kind → id → metadata-map. Atomic. The PROCESS-DEFAULT registrar — the
  `(kind, id) → metadata` table the process dispatches/subscribes/resolves
  against. Every `reg-*` / lookup routes through this one atom."}
  kind->id->metadata
  (atom {}))

;; ---- the active registrar -------------------------------------------------
;;
;; The active `(kind, id) → metadata` atom the registrar reads + writes. nil
;; means "the process-default registrar" (`kind->id->metadata`): unbound, every
;; `reg-*` / `register!` / `unregister!` / `lookup` / `registrations` hits the
;; one global atom, with ZERO added indirection on the dispatch hot path (the
;; var is consulted once and resolves to the global atom).
;;
;; `*registrar*` is a rebindable seam an alternate-registrar seating could bind
;; to redirect the `reg-*` lowering path to a different `(kind, id) → metadata`
;; table without re-plumbing an argument through `re-frame.events` / `.subs` /
;; `.fx` / `.cofx`. No live caller binds it; outside any binding it is nil and
;; the default path holds.
(def ^:dynamic *registrar*
  "The active registrar atom, or nil for the process-default
  `kind->id->metadata`. A rebindable seam; no live caller binds it (nil ⇒ the
  process-default registrar)."
  nil)

(defn active-registrar
  "Return the registrar atom registration + lookup currently target — the
  bound `*registrar*` when one is in scope, else the process-default
  `kind->id->metadata`. The single resolution point so every read/write below
  targets ONE consistent atom under a binding (the default path resolves to the
  global atom with no allocation)."
  []
  (or *registrar* kind->id->metadata))

;; ---- the active resolved image GENERATION (EP-0023 §Frame-derived live
;;      registration resolution) ------------------------------------------------
;;
;; EP-0023 states the resolution invariant in image/frame terms:
;;
;;     target frame -> resolved image generation -> registration resolution
;;
;; A live frame OBJECT (`re-frame.live-frame/make-frame`) carries a SEALED
;; image generation under `:rf.frame/generation` rather than addressing a realm
;; registrar atom. The generation's `:rf.gen/resolver` is the id-disjoint
;; `{[kind id] descriptor}` map a frame resolves `(kind, id)` lookups through
;; (the descriptor is the SAME registration-metadata shape `register!` stores —
;; it carries `:handler-fn` and every standard metadata key — because the
;; source store records `register!`'s metadata verbatim, so a generation-routed
;; `lookup` returns a value byte-shape-identical to a registrar-routed one).
;;
;; `*generation*` is the resolution-routing seam: when BOUND (a dispatch /
;; subscribe / fx / cofx / view / resource lookup is in flight against an
;; EP-0023 frame object), `lookup` / `handler` / `registrations` / `ids`
;; resolve through the generation's resolver FIRST instead of the registrar
;; atom. When nil — the absence-is-default path, taken by EVERY caller that is
;; not resolving against a frame object (the realm-routed dispatch and the
;; single-realm default path alike) — the reads target `active-registrar`'s
;; atom. This var is consulted with ONE nil check on the read hot path; the
;; dominant production path leaves it unbound and pays a single `nil?` branch.
;;
;; PERF / coherence: like `*registrar*`, the binding is established ONCE per
;; cascade / subscribe-build by `re-frame.live-frame/call-with-frame-resolution`
;; (DERIVED from the carried frame object, never an ambient binding — EP-0002),
;; so event + cofx + fx + sub resolve coherently through the SAME generation
;; (ALL-OR-NOTHING — routing only some would be an incoherent half-dispatch
;; where a frame-local handler's effects resolve in the global registrar).
;;
;; No `:require` on `re-frame.image-assembly` (that ns requires THIS one — a
;; back-require would cycle): the resolver is read as plain map data via the
;; documented `:rf.gen/*` shape. `re-frame.live-frame` (which DOES require
;; image-assembly) owns the binding seam and the generation read API.
(def ^:dynamic *generation*
  "The active resolved image GENERATION (a sealed `re-frame.image-assembly`
  generation value), or nil for the registrar-atom path. Bound by
  `re-frame.live-frame/call-with-frame-resolution` around a dispatch /
  subscribe / fx / cofx / view / resource resolution targeting an EP-0023 frame
  OBJECT, so `(kind, id)` lookups resolve through the frame's OWN image
  generation rather than the global/default registrar (EP-0023 §Frame-derived
  live registration resolution). nil ⇒ the absence-is-default
  registrar-atom path, taken by every caller not resolving against a frame."
  nil)

(defn- generation-resolver
  "The `{[kind id] descriptor}` resolver map of the currently-bound
  `*generation*`, or nil when no generation is bound. Reads the documented
  `:rf.gen/resolver` slot directly (no `image-assembly` require — that ns
  requires this one). Pure."
  []
  (when-let [gen *generation*]
    (:rf.gen/resolver gen)))

(defn- kind-registrations-from-resolver
  "Project a generation `resolver` (keyed `[kind id]`) into the registrar's
  `{id metadata}` query shape for one `kind` — the frame's OWN registrations
  of that kind. Pure. Used by the generation-routed `registrations` / `ids`
  query API so a frame-scoped query sees only the frame's image registrations."
  [resolver kind]
  (persistent!
    (reduce-kv (fn [acc [k id] descriptor]
                 (if (= k kind)
                   (assoc! acc id descriptor)
                   acc))
               (transient {})
               resolver)))

;; ---- registration ---------------------------------------------------------

(defonce ^:private replacement-hooks
  ;; Subscribers to "an existing id was replaced". Each is called with
  ;; a map {:kind :id :was :now}. Registered by namespaces that need to
  ;; respond to hot-reload (e.g. subs.cljc invalidates its cache).
  (atom []))

(defn add-replacement-hook!
  "Register a fn called on every register! that replaces an existing
  registration. Args: a map {:kind kind :id id :was prev-meta :now new-meta}."
  [f]
  (swap! replacement-hooks conj f)
  nil)

(defonce ^:private registration-hooks
  ;; Subscribers to "any id was registered" — first-time OR re-registration.
  ;; Each is called with a map {:kind :id :was :now} (`:was` is nil on
  ;; first-time registration). Registered by namespaces that need to
  ;; validate cross-id invariants at registration time (e.g. routing's
  ;; `:url-bound?` "only one frame owns the URL" rule per Spec 012
  ;; §Multi-frame routing). Fires AFTER the slot is written
  ;; so the hook can inspect the final registry state.
  (atom []))

(defn add-registration-hook!
  "Register a fn called on EVERY register! call — both first-time and
  re-registration. Args: a map `{:kind kind :id id :was prev-meta :now
  new-meta}`; `:was` is nil on first-time registration. Sibling to
  `add-replacement-hook!` (which only fires on re-registration). Used
  for cross-id invariant checks like routing's `:url-bound?` exclusivity
  (per Spec 012 §Multi-frame routing)."
  [f]
  (swap! registration-hooks conj f)
  nil)

;; ---- trace-emit helper ----------------------------------------------------
;;
;; The four :registry trace sites below share the late-bind lookup of
;; `:trace/emit!` (re-frame.trace depends on re-frame.registrar, so
;; `:require` would cycle). `:trace/emit!` is published once at
;; re-frame.trace load and never withdrawn, so the resolution is sticky
;; — `late-bind/get-fn-cached` memoises it.
;;
;; Each call site keeps its OUTERMOST `(when interop/debug-enabled? ...)`
;; gate. That gate is the load-bearing condition Closure constant-folds
;; under `:advanced + goog.DEBUG=false` (Spec 009 §Production builds),
;; and the `:rf.registry/*` operation keywords are literal args at the
;; call sites — moving the gate inside this helper would leave those
;; literals reachable from the unconditional helper call and defeat the
;; elision-probe sentinels.

(defn- emit!
  "Invoke `:trace/emit!` with the `:rf.registry` op-type, memoising the
  late-bind resolution through `late-bind/get-fn-cached` (the shared
  memoisation pattern lives in `re-frame.late-bind`). Callers MUST
  wrap invocations in `(when interop/debug-enabled? ...)` so Closure
  DCE elides the call and its literal args under `:advanced +
  goog.DEBUG=false`."
  [operation tags]
  (when-let [f (late-bind/get-fn-cached :trace/emit!)]
    (f :rf.registry operation tags)))

(defn- dedup-allow?
  "Consult the B4 hot-reload dedup-by-shape table (per Spec 009
  §Hot-reload dedup — re-emits suppressed by shape). Returns
  true if the emit should proceed; false if the prior emit for this
  `(kind, id)` already carried an identical shape (so this re-emit
  carries no new signal).

  When the trace.tooling sibling is not loaded (production CLJS counter
  bundle), the late-bind lookup returns nil and we allow-by-default —
  the surrounding `interop/debug-enabled?` gate elides the whole branch
  anyway in :advanced + goog.DEBUG=false builds."
  [operation kind id meta]
  (if-let [f (late-bind/get-fn-cached :trace.tooling/dedup-allow?)]
    (f operation kind id meta)
    true))

(defn- emit-warning!
  "Invoke `:trace/emit!` with the `:warning` op-type. Sibling to
  `emit!` (which uses `:rf.registry`). Callers MUST wrap invocations in
  `(when interop/debug-enabled? ...)` so Closure DCE elides the call
  and its literal args under `:advanced + goog.DEBUG=false`."
  [operation tags]
  (when-let [f (late-bind/get-fn-cached :trace/emit!)]
    (f :warning operation tags)))

;; ---- warn-once caches (Spec 001 §`:doc` is dev-warned when absent,
;;                        Spec 001 §Re-registration of a different
;;                        function — collision warning) ---------------------
;;
;; `:rf.warning/missing-doc` fires at most once per `(kind, id)` pair
;; within a runtime process. `:rf.warning/registration-collision` uses
;; the same suppression discipline. Mirrors the warn-once cache pattern
;; from re-frame.views (plain-fn-under-non-default-frame-once).
;;
;; Caches sit alongside the registry; production elision (Spec 009
;; §Production builds) elides the consult+emit branches, but the
;; atom allocation itself is process-load-time and harmless.

(defonce ^:private missing-doc-warned
  (atom #{}))

(defonce ^:private collision-warned
  (atom #{}))

(defn clear-warning-caches!
  "Reset the warn-once caches for `:rf.warning/missing-doc` and
  `:rf.warning/registration-collision`. Tests use this between cases
  so each case starts from a clean slate.

  Per Spec 001 §`:doc` is dev-warned when absent: suppression is
  per-process; the cache is process-local state, not registry state."
  []
  (reset! missing-doc-warned #{})
  (reset! collision-warned   #{})
  nil)

(defn- source-coords
  "Extract the source-coord subset of a registration metadata map.
  Returns nil when no coord slot is present (programmatic / non-macro
  path). Mirrors the `{:ns :line :file :column}` envelope per Spec 001
  §Source-coordinate capture (CLJS reference)."
  [meta]
  (let [coords (select-keys meta [:ns :line :file :column])]
    (when (seq coords) coords)))

(defn- macro-path?
  "Truthy when the metadata map carries the macro-path signature —
  source coords merged in via `source-coords/merge-coords` (Spec 001
  §Source-coordinate capture). Per Spec 001 §`:doc` is dev-warned
  obligation 4, programmatic re-registrations through internal
  helpers that bypass the public macro path are out of scope for
  `:rf.warning/missing-doc`. The `:ns` slot is the canonical signal
  the macro layer reached `register!`."
  [meta]
  (contains? meta :ns))

(defn- missing-doc?
  "True when `meta` has no usable `:doc` slot: absent, nil, or an
  empty string (per Spec 001 §`:doc` obligation 1)."
  [meta]
  (let [d (:doc meta)]
    (or (nil? d)
        (and (string? d) (= "" d)))))

(defn- maybe-emit-missing-doc!
  "Emit `:rf.warning/missing-doc` once per `(kind, id)` when `meta`
  came from the public macro path and carries no usable `:doc`.
  Per Spec 001 §`:doc` is dev-warned when absent.

  Callers MUST wrap invocations in `(when interop/debug-enabled? ...)`
  so the production bundle DCEs the consult+emit branch (Spec 009
  §Production builds). The keyword `:rf.warning/missing-doc` is a
  literal arg at the call site — moving the gate inside this helper
  would leave the literal reachable from the unconditional helper
  call and defeat the elision sentinel."
  [kind id meta]
  (when (and (macro-path? meta) (missing-doc? meta))
    (let [k [kind id]]
      (when-not (contains? @missing-doc-warned k)
        (swap! missing-doc-warned conj k)
        (emit-warning! :rf.warning/missing-doc
                       (cond-> {:kind kind :id id}
                         (source-coords meta) (assoc :source-coords
                                                     (source-coords meta))))))))

(defn- collision?
  "True when re-registering `new-meta` over `previous` is a genuine
  cross-source COLLISION (an accidental id clash between two different
  authoring sites) rather than a benign same-source re-eval (hot reload).

  Per Spec 001 §Re-registration of a different function — collision
  warning: \"A re-eval of the same source file produces the same
  `(file, line)` pair and is silent; a different file or line reassigning
  the id surfaces `:rf.warning/registration-collision`.\" The canonical
  identity is therefore the registration's PROVENANCE — its source-coord
  envelope (`:ns` / `:file` / `:line`), the same provenance boundary the
  source store keys on (`source-store.cljc`: same `(kind, id, ns)` is a
  hot reload; a different ns for the same `(kind, id)` is the
  image-isolation collision case).

  Provenance is the right basis, NOT fn identity: a same-file
  save-and-re-eval yields a FRESH fn instance, so an identity comparison
  always differs and would fire the warning on every hot reload — exactly
  the false positive the spec says MUST be silent. Comparing provenance,
  the same source site re-evaluates to the same `(ns, file, line)` and is
  correctly silent.

  Absent provenance on EITHER side (a programmatic / REPL `register!`
  that bypassed the macro path — no coords captured) is NOT a collision:
  there is no source identity to clash, and the macro-path carve-out
  matches `maybe-emit-missing-doc!`'s `macro-path?` discipline. Returns
  false in that case so programmatic churn stays silent."
  [previous new-meta]
  (let [prev-coords (source-coords previous)
        new-coords  (source-coords new-meta)]
    (and (some? prev-coords)
         (some? new-coords)
         (not= prev-coords new-coords))))

(defn- maybe-emit-collision!
  "Emit `:rf.warning/registration-collision` once per `(kind, id)`
  when a re-registration reassigns the id from a DIFFERENT source
  location (different `(ns, file, line)` provenance) — an accidental
  id clash between two authoring sites, not a hot-reload re-eval of
  the same source.

  Per Spec 001 §Re-registration of a different function — collision
  warning, the detection keys on the source-coord PROVENANCE pair, not
  fn identity (which a same-file re-eval always changes — see
  `collision?`). A same-source re-eval is silent; a different file/line/
  ns reassigning the id surfaces the warning. The existing
  `:rf.registry/handler-replaced` trace stays intact (with
  `:different-fn?` tag) on EVERY re-registration; this warning surface is
  the separate dev-nudge that single-source-of-truth tools surface to the
  developer. Same suppression discipline as missing-doc — fires once per
  `(kind, id)` to keep the dev stream readable.

  Callers MUST wrap invocations in `(when interop/debug-enabled? ...)`
  for production elision (Spec 009 §Production builds)."
  [kind id previous new-meta]
  (when (collision? previous new-meta)
    (let [k [kind id]]
      (when-not (contains? @collision-warned k)
        (swap! collision-warned conj k)
        (emit-warning! :rf.warning/registration-collision
                       (cond-> {:kind            kind
                                :id              id
                                :previous-coords (source-coords previous)}
                         (source-coords new-meta) (assoc :source-coords
                                                         (source-coords new-meta))))))))

;; ---- pure-documentation metadata elision ----------------------------------

(def pure-documentation-keys
  "The registration-metadata keys that are PURE documentation — zero
  production runtime use AND zero production observability use — so they
  are safe to strip from the stored metadata in `:advanced` +
  `goog.DEBUG=false` builds (per Spec 001 §Production elision contract).

  Closed set: `:doc` only. Every other standard key is load-bearing in
  production and MUST be retained — `:sensitive?` / `:large?` drive
  redaction / egress projection (Spec 015 / EP-0015); `:tags` /
  `:interceptors` / the resource-mutation runtime keys drive runtime
  behaviour; `:schema` / `:data-schema` are the SOURCE the `:sensitive?` /
  `:large?` marks are PRECOMPUTED from at registration time (the marks are
  stored as plain declarations, not derived from the schema VALUE at
  egress — see `re-frame.marks` / `re-frame.elision` boot population), and
  remain a dev introspection surface, so they are retained. `:rf/id` and
  the handler fn ARE the registration.

  Adding a key here is a Spec change (Spec 001 §Production elision
  contract — the elidable-vs-retained classification table)."
  #{:doc})

(defn strip-pure-documentation
  "Drop the pure-documentation keys (`pure-documentation-keys`) from a
  registration `metadata` map in production builds (`interop/debug-enabled?`
  false). Returns `metadata` unchanged in dev. Per Spec 001 §Production
  elision contract.

  The `interop/debug-enabled?` gate is the OUTERMOST form so Closure
  constant-folds it under `:advanced` + `goog.DEBUG=false`: the dev arm
  (the unchanged map) DCEs in production, and the `apply dissoc` strip
  DCEs in dev — zero runtime cost either way. A non-map `metadata` (no
  documentation keys to carry) passes through untouched."
  [metadata]
  (if (or (not interop/debug-enabled?)
          (not (map? metadata)))
    ;; Production OR a non-map metadata: strip the pure-documentation keys.
    ;; (A non-map metadata cannot carry them, so `dissoc`/`apply dissoc`
    ;; would be a no-op anyway — short-circuit to avoid touching it.)
    (if (map? metadata)
      (apply dissoc metadata pure-documentation-keys)
      metadata)
    ;; Dev: retain documentation for tooling / agent inspection.
    metadata))

(defn register!
  "Register an id under kind with the given metadata. Re-registering the
  same id replaces the slot atomically (per Spec 001 §Hot-reload semantics
  guarantee 1 — non-destructive to in-flight work; the runtime sees the
  new fn on the next lookup).

  When this is a re-registration, every replacement-hook fires and a
  :rf.registry/handler-replaced trace event is emitted on EVERY
  re-registration (per Spec 001 §Hot-reload trace surface + Spec 009 —
  devtools refresh their view from this event). The trace's `:tags`
  carry `:different-fn?` so tooling can branch idempotent reloads from
  real fn-identity changes without re-emitting through a separate
  surface."
  [kind id metadata]
  (when-not (valid-kind? kind)
    (error/throw-error!
      :rf.error/unknown-registry-kind
      'rf/register-handler
      (str "unknown registry kind " kind " — not one of the registered registry kinds")
      {:recovery :fix-registration
       :extra    {:kind kind
                  :id   id}}))
  ;; Always-on error-coord parallel registry (§Production
  ;; elision). When a public reg-* macro is on the stack `*pending-coords*`
  ;; carries the captured coord-map (slim in CLJS prod — no `:column`).
  ;; The error-emit substrate looks coords up via
  ;; `source-coords/error-coords-for` when assembling the tight error-
  ;; record / policy-event, so Sentry-style shippers still see source-
  ;; line info even when public registry-meta has been stripped of
  ;; coord-keys under `goog.DEBUG=false`. Programmatic paths
  ;; (`*pending-coords*` nil) no-op cleanly — `remember-error-coords!`
  ;; itself guards against nil.
  (when-let [pc source-coords/*pending-coords*]
    (source-coords/remember-error-coords! kind id pc))
  ;; Pure-documentation metadata elision. Under `:advanced` +
  ;; `goog.DEBUG=false` strip the pure-documentation keys (`:doc`) BEFORE the
  ;; metadata is stored, so `(rf/handler-meta kind id)` carries no `:doc` in
  ;; production — consistent with source-coords already being absent there
  ;; (Spec 001 §Production elision contract). The `interop/debug-enabled?`
  ;; gate inside `strip-pure-documentation` is the OUTERMOST form, so Closure
  ;; constant-folds the strip away in dev and the dev `:doc` retention away
  ;; in prod. NOTE: the dev-only `:rf.warning/missing-doc` check below reads
  ;; `(:doc metadata)` — it is itself gated on `interop/debug-enabled?`, so it
  ;; only fires in dev where `:doc` is still present; the strip never hides a
  ;; missing-doc warning.
  (let [metadata (strip-pure-documentation metadata)
        reg      (active-registrar)
        previous (-> @reg (get kind) (get id))]
    (swap! reg assoc-in [kind id] metadata)
    ;; EP-0023 provenance-preserving source store. In addition
    ;; to the resolver-map write above (the unchanged default-image runtime
    ;; path), record the descriptor in the source store keyed by
    ;; [kind id provenance-namespace]. Cross-namespace duplicate `(kind, id)`
    ;; registrations are BOTH retained there; a same-namespace re-eval replaces
    ;; its own source slot (hot reload). The store stamps `:rf.provenance/ns` as
    ;; a canonical string from the metadata's macro-captured `:ns` symbol. This
    ;; is a pure store write — NO assembly / selection / collision decision is
    ;; made here (that is image assembly, a later EP-0023 slice). `metadata`
    ;; written into the resolver map intentionally stays untouched; the store
    ;; keeps its own provenance-stamped copy.
    (source-store/record-descriptor! kind id metadata)
    (cond
      ;; Re-registration path — fire hooks and emit handler-replaced.
      previous
      (let [different? (not= (:handler-fn previous) (:handler-fn metadata))]
        ;; Hot-reload notifications. Hooks run isolated — listener failures
        ;; don't propagate. Hooks fire on EVERY re-registration so dependent
        ;; namespaces can clean up their caches even on idempotent reloads
        ;; (the same fn shape is fine; closure state may differ).
        (doseq [f @replacement-hooks]
          (try (f {:kind kind :id id :was previous :now metadata
                   :different-fn? different?})
               (catch #?(:clj Throwable :cljs :default) _ nil)))
        ;; Per Spec 001 §Hot-reload trace surface: emit
        ;; `:rf.registry/handler-replaced` on EVERY re-registration —
        ;; not only when the handler-fn changes. Kinds like `:frame`
        ;; replace the slot without rotating `:handler-fn`, so gating the
        ;; emit on `different?` would drop legitimate re-registration
        ;; events on the floor. The `:different-fn?` tag is preserved
        ;; for tools that want to suppress idempotent-reload noise on
        ;; their side.
        ;;
        ;; The `interop/debug-enabled?` gate stays OUTERMOST so
        ;; `:advanced + goog.DEBUG=false` constant-folds the entire
        ;; branch (per Spec 009 §Production builds).
        ;;
        ;; Per Spec 009 §Hot-reload dedup — re-emits suppressed by shape
        ;; (B4 ruling): consult the dedup table. Identical
        ;; shape on re-register (a hot-reload that didn't actually
        ;; change the handler) emits ZERO `:rf.registry/handler-replaced`
        ;; trace events; a real edit emits exactly one.
        (when interop/debug-enabled?
          (when (dedup-allow? :rf.registry/handler-replaced kind id metadata)
            (emit! :rf.registry/handler-replaced
                   {:kind kind :id id :different-fn? different?}))
          ;; Per Spec 001 §Re-registration of a different function —
          ;; collision warning. Decoupled from the handler-replaced dedup
          ;; gate: the collision warning detects a CROSS-SOURCE id clash by
          ;; PROVENANCE (different `(ns, file, line)`), not by handler-fn
          ;; shape, and carries its OWN per-(kind, id) suppression
          ;; (`collision-warned`). It must run independently of the
          ;; `dedup-allow?` (handler-replaced) gate: a kind with no rotating
          ;; `:handler-fn` — `:frame`, `:route`, `:head` — can be deduped-away
          ;; by shape, so nesting the collision check inside that gate would
          ;; let a GENUINE cross-source clash for those kinds go unwarned.
          ;; Called here independently (still under `interop/debug-enabled?`
          ;; for production elision); `collision?` keeps a same-source
          ;; hot-reload re-eval silent, and the warn-once cache keeps the dev
          ;; stream readable across reload churn.
          (maybe-emit-collision! kind id previous metadata)))
      ;; First-time registration — emit handler-registered per Spec 009
      ;; §:op-type vocabulary. Hot-reload tools (10x, re-frame-pair) use
      ;; this to track when fresh ids appear in the registry. The B4
      ;; dedup table is also consulted here so the FIRST emit per
      ;; (kind, id) records the baseline shape for subsequent re-emit
      ;; suppression. A first registration always allows
      ;; (no prior entry to compare against).
      :else
      (when interop/debug-enabled?
        (when (dedup-allow? :rf.registry/handler-registered kind id metadata)
          (emit! :rf.registry/handler-registered {:kind kind :id id}))))
    ;; Per Spec 001 §`:doc` is dev-warned when absent. Fires
    ;; on every reg-* call whose final metadata-map carries no usable
    ;; `:doc`, once per (kind, id) within the runtime process. Production
    ;; elides via the outer `interop/debug-enabled?` gate (Spec 009
    ;; §Production builds). Fires on BOTH first-time and re-registration
    ;; — the consult+emit body is suppressed by the per-(kind, id) cache
    ;; on subsequent calls; obligation 2 says re-registering the same id
    ;; without `:doc` does NOT re-fire the warning.
    (when interop/debug-enabled?
      (maybe-emit-missing-doc! kind id metadata))
    ;; Always-on registration hooks: fire on BOTH first-time
    ;; and re-registration so cross-id invariants (e.g. routing's
    ;; `:url-bound?` exclusivity per Spec 012 §Multi-frame routing) can
    ;; be validated at the moment of any registration. Hooks run isolated
    ;; — listener failures don't propagate so a buggy hook can't block
    ;; the registration.
    (doseq [f @registration-hooks]
      (try (f {:kind kind :id id :was previous :now metadata})
           (catch #?(:clj Throwable :cljs :default) _ nil)))
    {:was previous :now metadata}))

(defn unregister!
  "Remove a single id under kind. Hot-reload code paths use this; user code
  rarely does."
  [kind id]
  (let [reg      (active-registrar)
        previous (-> @reg (get kind) (get id))]
    (swap! reg update kind dissoc id)
    ;; EP-0023: keep the provenance source store in step with the resolver-map
    ;; removal. The resolver map keys by `(kind, id)`, so an unregister drops
    ;; the id wholesale; mirror that in the source store by forgetting every
    ;; provenance slot for `(kind, id)`.
    (source-store/forget-id! kind id)
    ;; Per Spec 009 §:op-type vocabulary: :rf.registry/handler-cleared
    ;; fires on explicit removal so hot-reload tools can update their
    ;; views. Only emit when something was actually present.
    ;;
    ;; Per Spec 009 §Hot-reload dedup (B4 ruling): a clear
    ;; of an id the dedup table thinks is already absent (a double-
    ;; clear) is suppressed. The first clear records the `::cleared`
    ;; sentinel; subsequent re-clears emit nothing.
    (when interop/debug-enabled?
      (when previous
        (when (dedup-allow? :rf.registry/handler-cleared kind id nil)
          (emit! :rf.registry/handler-cleared {:kind kind :id id})))))
  nil)

(defn clear-kind!
  "Remove every id under kind. Test fixtures use this to reset state.

  Also drops the matching slots from the per-process warn-once caches
  (`missing-doc-warned`, `collision-warned`) so a test fixture targeting
  a single kind starts each case from a clean diagnostic slate —
  mirrors `clear-all!`'s reset semantics, scoped to the named kind."
  [kind]
  (let [reg          (active-registrar)
        previous-ids (keys (get @reg kind))
        clear-kind   (fn [cache-set]
                       (into #{} (remove #(= kind (first %))) cache-set))]
    (swap! reg dissoc kind)
    ;; EP-0023: drop the matching kind from the provenance source store too, via
    ;; the source-store API so the store GENERATION is bumped. A raw
    ;; `(swap! (active-source-store) dissoc kind)` here would NOT bump the
    ;; generation, leaving the resolved-image-generation cache (keyed on the
    ;; source-store generation) to HIT and return a stale generation that still
    ;; resolves the just-cleared `(kind, id)`s.
    (source-store/clear-kind! kind)
    (swap! missing-doc-warned clear-kind)
    (swap! collision-warned   clear-kind)
    ;; Per Spec 009 §:op-type vocabulary: :rf.registry/handler-cleared
    ;; fires for each id so consumers see consistent registry transitions.
    ;; B4 dedup: a clear-of-a-cleared id is suppressed; the
    ;; first clear records the `::cleared` sentinel.
    (when interop/debug-enabled?
      (when (seq previous-ids)
        (doseq [id previous-ids]
          (when (dedup-allow? :rf.registry/handler-cleared kind id nil)
            (emit! :rf.registry/handler-cleared {:kind kind :id id}))))))
  nil)

(defn clear-all!
  "Remove every registration for every kind. Test fixtures use this.

  Also resets the per-process warn-once caches for
  `:rf.warning/missing-doc` and `:rf.warning/registration-collision`
  so each test case starts from a clean diagnostic state — without
  this, a test that re-registers an already-warned (kind, id) pair
  would silently miss the warning under suppression.

  Per Spec 009 §Hot-reload dedup (B4 ruling): also clears
  the dev-only `:rf.registry/*` dedup-by-shape table via the
  trace.tooling sibling's clearance hook — a test fixture targeting
  the registry must start each case from a clean dedup slate so the
  first emit for any `(kind, id)` proceeds (otherwise the table from
  the prior test would silently suppress the new test's first emit)."
  []
  (reset! kind->id->metadata {})
  (reset! missing-doc-warned #{})
  (reset! collision-warned   #{})
  ;; EP-0023: reset the provenance-preserving source store (and its
  ;; namespace-string pool) in lockstep with the process-default resolver map,
  ;; so a test fixture starts each case from a clean state on both surfaces.
  (source-store/clear-all!)
  ;; Also clear the always-on error-coord parallel registry
  ;; so test cases start from a clean state on both surfaces.
  (source-coords/forget-error-coords!)
  ;; Clear the B4 dedup table when the trace.tooling sibling is loaded.
  ;; Production CLJS bundles that never load trace.tooling silently
  ;; no-op here — the dedup hook is unbound and there's no table to
  ;; clear anyway.
  (when interop/debug-enabled?
    (when-let [clear! (late-bind/get-fn-cached :trace.tooling/clear-dedup-table!)]
      (clear!)))
  nil)

;; ---- lookup ---------------------------------------------------------------

(defn lookup
  "Return the metadata map registered for (kind, id), or nil.

  EP-0023 §Frame-derived live registration resolution: when an
  EP-0023 frame OBJECT's image generation is in scope (`*generation*` bound by
  `re-frame.live-frame/call-with-frame-resolution`), the `(kind, id)` resolves
  through the FRAME'S OWN generation resolver — so two frames running different
  images resolve the same `[kind id]` to their OWN image's descriptor. The
  generation's resolver is keyed `[kind id]`; the descriptor is the same
  registration-metadata shape `register!` stores (the source store records it
  verbatim), so the return value is byte-shape-identical to the registrar path.
  When `*generation*` is unbound — the absence-is-default path, taken by every
  caller not resolving against a frame object (single-realm + the realm-routed
  dispatch) — resolution targets the `active-registrar` atom, one `nil?` branch
  on the read.

  Uses paired `get` calls rather than `(get-in ... [kind id])` — `get-in`
  allocates a path vector per call, and `lookup` runs per
  dispatch (event handler), per fx (handler), and per sub (handler)."
  [kind id]
  (if-let [resolver (generation-resolver)]
    (get resolver [kind id])
    (-> @(active-registrar) (get kind) (get id))))

(defn handler
  "Return just the handler fn from the metadata, or nil. The handler is
  stored under :handler-fn in the metadata map by the kind-specific reg-*
  macros (events) or directly (subs/fx)."
  [kind id]
  (when-let [meta (lookup kind id)]
    (:handler-fn meta)))

;; ---- query API (per Spec 002 §The public registrar query API) -------------

(defn registrations
  "All ids registered under kind, with their metadata. Tools, agents,
  storybook resolution, all use this.

  Two arities:
    (registrations kind)
      Return the full `{id metadata}` map for kind, or `{}` if the kind
      has no registrations.
    (registrations kind pred-fn)
      Same shape, filtered: only entries for which `(pred-fn meta)`
      returns truthy are included. Returns `{}` when no entry matches.
      Tools (storybook resolvers, registry browsers, agent introspection)
      use this to narrow the result to a per-namespace, per-source-file,
      or per-marker subset without re-walking the registry map themselves.

  Per Spec 001 §The public registrar query API. The predicate's
  argument is the metadata-map only — the registration id is reachable
  from the metadata's `:ns` / `:line` / `:file` / `:doc` / `:tags` /
  custom slots (id-by-keyword filtering composes via the caller's own
  `filter` over the result map's keys when needed).

  EP-0023 §Frame-derived live registration resolution: when a frame generation
  is in scope (`*generation*` bound), the `{id metadata}` map is PROJECTED from
  the generation's resolver for `kind` — only the ids the frame's OWN image
  carries, so a generation-scoped query (a per-frame fx walk, a view-id
  resolution) sees the frame's registration universe, not the global
  registrar's. Unbound ⇒ the registrar atom path."
  ([kind]
   (if-let [resolver (generation-resolver)]
     (kind-registrations-from-resolver resolver kind)
     (get @(active-registrar) kind {})))
  ([kind pred-fn]
   (into {}
         (filter (fn [[_id meta]] (pred-fn meta)))
         (if-let [resolver (generation-resolver)]
           (kind-registrations-from-resolver resolver kind)
           (get @(active-registrar) kind {})))))

(defn handler-meta
  "Public alias for lookup. Used by tooling."
  [kind id]
  (lookup kind id))

(defn ids
  "Just the id set for a kind."
  [kind]
  (-> (registrations kind) keys set))
