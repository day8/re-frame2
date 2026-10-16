(ns re-frame.registrar
  "The registrar — registration into, and resolution out of, the one
  registration table (`re-frame.source-store`, `kind → id → provenance-ns →
  descriptor`).

  Per Spec 001, the registrations are per-process, keyed by kind. Frames
  isolate STATE; the registrations are shared across all frames, and each
  frame resolves them through its own view (see `*generation*` and
  `*live-view*`).

  Reserved kinds (closed v1 set, per Spec 001 §Registry model):
    :event :sub :fx :cofx :interceptor :view :frame :route :head
    :error-projector :flow :resource :mutation :resource-scope :app-schema

  Machine guards and actions are NOT a registrar kind. Per Spec 005 the
  machine spec's `:guards` / `:actions` maps are the single source of
  truth — the runtime resolves them through the machine spec, never the
  registrar.
  Their dev-only fn-source handler-meta surface (`(rf/handler-meta {:source :store :kind :machine-guard :id [machine-id guard-id]})`, consumed by Xray's
  focused-transition lens + re-frame-pair source-jump) is DERIVED on
  demand from the machine's existing `:event` registration spec (the
  co-located `:guards` / `:actions` entries each carry `:source-code` /
  `:source-coords` in dev), not stored as a separate registrar entry —
  see `re-frame.core-machines/machine-handler-meta`. Production-elided
  per Spec 009 §Production builds (the macro emits no `:source-*` slots
  under `goog.DEBUG=false`, so the derivation returns nil).

  `:app-schema` is the app-db schema kind: `reg-app-schema` registers a
  schema under its canonical path vector, frame-agnostically, and every frame
  validates against the schemas it resolves, exactly as it resolves any other
  kind.

  The `:flow` kind is RESERVED but the registrar slot is
  intentionally **empty** — `reg-flow` writes only to the flows artefact's
  own per-frame store (`flows`, keyed `{frame-id {flow-id flow-map}}`),
  which is the single source of truth. Flows are FRAME-DIVERGENT-PER-ID
  (the same flow-id can hold different `:inputs` / `:derive` /
  `:output-path` per frame, Spec 013), so a frame-blind `{flow-id metadata}`
  registrar slot is the wrong shape; the kind keyword is preserved for
  Spec 001 §Registry model continuity. Tools introspecting flows go through
  `flows/flows` / `flows/flow-meta` / `flows/flows-snapshot` rather than
  `handlers :flow` / `handler-meta :flow`.

  The `:frame` kind is likewise RESERVED with an intentionally
  **empty** registrar slot — the frame engine (`re-frame.frame/upsert-frame!`)
  writes only to the frames registry (`re-frame.frame/frames`), the single
  source of truth for seated frames. A frame is a LIVE runtime object, not an
  image-resolved program member: routing a `:frame` row through `register!`
  would leak it into the provenance source store, bumping the source-store
  generation (invalidating the resolved-image-generation cache, EP-0023) and
  firing the live-frame reprojection hook on every seat/reseat. Frame
  introspection goes through `rf/frame-meta` / `rf/frame-ids` (Spec 002 §The
  public registrar query API), never `registrations :frame`.

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
  every other standard key is load-bearing in production (the
  `:sensitive` / `:large` / `:large?` classification keys drive egress
  projection, as do a resource or mutation spec's `:sensitive?` /
  `:large?` root props — a handler's own `:sensitive?` classifies
  nothing; `:tags` / `:interceptors` / the resource-mutation runtime keys
  drive runtime behaviour; `:rf/id` + the handler fn ARE the
  registration).

  `register!` is the single chokepoint every `reg-*` surface funnels
  through, so the strip lives here: under `:advanced` + `goog.DEBUG=false`
  (`rf.interop/debug-enabled?` constant-folds to `false`) `strip-pure-
  documentation` drops the pure-documentation keys from the metadata
  before it is stored, so `(rf/handler-meta {:source :store :kind kind :id id})` carries no `:doc`
  in production — consistent with the source-coords already being absent
  there (Spec 001 §Production elision contract, Policy A). The outermost
  `rf.interop/debug-enabled?` gate lets Closure constant-fold the strip away
  in dev and the dev `:doc` retention away in prod.

  The DCE of the dev-only `:doc` STRING bytes from the bundle rides the
  same `re-frame.events/merge-form-source` gate that already elides the
  whole `(reg-event-X :id {:doc \"…\"} …)` form-source under
  `goog.DEBUG=false` (the elision-probe `:probe/cs-event` sentinel
  covers it); the strip here pins the *handler-meta* absence."
  (:require [re-frame.error         :as rf.error]
            [re-frame.interop       :as rf.interop]
            [re-frame.late-bind     :as rf.late-bind]
            [re-frame.source-coords :as rf.source-coords]
            [re-frame.source-store  :as rf.source-store]))

#?(:clj (set! *warn-on-reflection* true))

;; ---- the kind set ---------------------------------------------------------

(def kinds
  "The closed set of registry kinds for v1. Adding a new kind is a Spec change.

  `:frame` is RESERVED with an intentionally EMPTY slot — seated
  frames live in the frames registry (`re-frame.frame/frames`), introspected
  via `rf/frame-meta` / `rf/frame-ids`; nothing writes `:frame` rows here
  (the `:flow` precedent below).

  Machine guards/actions are NOT registrar kinds — the runtime resolves
  them through the machine spec's `:guards` / `:actions` maps, and their
  dev-only fn-source handler-meta is DERIVED from the machine's `:event`
  registration spec (see `re-frame.core-machines/machine-handler-meta`),
  not stored here.

  `:app-schema` (Spec 010) is the app-db schema kind — `reg-app-schema`
  registers a schema under it, keyed by the canonical path vector. A frame
  resolves it like every other kind: live for a frame made without `:images`,
  through the sealed generation for a frame made with them.

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
    :error-projector :flow :resource :mutation :resource-scope :app-schema})

(defn valid-kind? [k]
  (contains? kinds k))

;; ---- the active resolved image GENERATION (EP-0023 §Frame-derived live
;;      registration resolution) ------------------------------------------------
;;
;; EP-0023 states the resolution invariant in image/frame terms:
;;
;;     target frame -> resolved image generation -> registration resolution
;;
;; A frame made with `:images` (`re-frame.live-frame/make-frame`) has a SEALED
;; image generation on its `frames` record (the `:generation` slot) rather than
;; addressing a realm registrar atom. The generation's `:rf.gen/resolver` is the id-disjoint
;; `{[kind id] descriptor}` map a frame resolves `(kind, id)` lookups through
;; (the descriptor is the SAME registration-metadata shape `register!` stores —
;; it carries `:handler-fn` and every standard metadata key — because the
;; source store records `register!`'s metadata verbatim, so a generation-routed
;; `lookup` returns a value byte-shape-identical to a registrar-routed one).
;;
;; `*generation*` is the resolution-routing seam: when BOUND (a dispatch /
;; subscribe / fx / cofx / view / resource lookup is in flight against an
;; EP-0023 frame object made with `:images`), `lookup` / `handler` /
;; `registrations` / `ids` resolve through the generation's resolver FIRST
;; instead of the registrar atom. When nil, a bound [[*live-view*]] (a frame
;; made without `:images`) answers instead; with neither bound — the
;; absence-is-default path, taken by EVERY caller that is not resolving
;; against a frame object — the reads take the latest registration of each id
;; in the registration table. This var is consulted with ONE nil check on the
;; read hot path.
;;
;; PERF / coherence: the binding is established ONCE per
;; cascade / subscribe-build by `re-frame.live-frame/call-with-frame-resolution`
;; (DERIVED from the carried frame object, never an ambient binding — EP-0002),
;; so event + cofx + fx + sub resolve coherently through the SAME generation
;; (ALL-OR-NOTHING — routing only some would be an incoherent half-dispatch
;; where a frame-local handler's effects resolve outside the frame's view).
;;
;; No `:require` on `re-frame.image-assembly` (that ns requires THIS one — a
;; back-require would cycle): the resolver is read as plain map data via the
;; documented `:rf.gen/*` shape. `re-frame.live-frame` (which DOES require
;; image-assembly) owns the binding seam and the generation read API.
(def ^:dynamic *generation*
  "The active resolved image GENERATION (a sealed `re-frame.image-assembly`
  generation value), or nil. Bound by
  `re-frame.live-frame/call-with-frame-resolution` around a dispatch /
  subscribe / fx / cofx / view / resource resolution targeting a frame made
  with explicit `:images`, so `(kind, id)` lookups resolve through the frame's
  OWN image generation rather than the registration table (EP-0023
  §Frame-derived live registration resolution). nil ⇒ the [[*live-view*]] when
  one is bound, else the latest registration of each id."
  nil)

;; ---- the LIVE view of a frame made without `:images` ----------------------
;;
;; A frame `make-frame` builds without `:images` carries no generation: it
;; resolves the registration store as the store stands (Spec 002 §Image
;; resolution and composition). `*live-view*` is that resolution, bound ONCE
;; per cascade by `re-frame.live-frame/call-with-record-resolution`, so an
;; event, its interceptors and its `:fx` resolve against one snapshot of the
;; store and a registration landing mid-event cannot split them. The value is
;; the resolver fn `re-frame.live-frame/live-view` builds over that snapshot:
;;
;;     (view kind id) — the one descriptor, nil for an unregistered id, or a
;;                      throw of `:rf.error/ambiguous-registration` when more
;;                      than one namespace registers `[kind id]`;
;;     (view kind)    — the `{id descriptor}` map of the ids of `kind` the
;;                      frame can run (an ambiguous id is not one of them).
;;
;; It is a fn rather than data because the rules it applies — framework
;; standards first, a superseded framework default dropped — belong to
;; `re-frame.image-assembly`, which this ns cannot require.

(def ^:dynamic *live-view*
  "The live-store resolver of a frame made without `:images` (see the section
  comment above), or nil. Consulted by `lookup` / `registrations` when no
  [[*generation*]] is bound."
  nil)

(defn frame-resolution-bound?
  "True while a frame's own resolution — a sealed [[*generation*]] or a
  [[*live-view*]] — is bound around the current call."
  []
  (or (some? *generation*) (some? *live-view*)))

(defn ambiguous-registration?
  "True when `e` is the `:rf.error/ambiguous-registration` throw a
  [[*live-view*]] raises for an id more than one namespace registers. A read
  that only wants to know about an id (a trace gate, an error record's source
  coordinate) treats that id as having no single registration, so the
  ambiguity is reported once, where the id is used."
  [e]
  (= :rf.error/ambiguous-registration (:rf.error/id (ex-data e))))

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

;; ---- framework standards refuse an application registration -------------
;;
;; A framework STANDARD (`re-frame.image-assembly/register-standard!`) encodes
;; an execution invariant, so an application registration of its `[kind id]`
;; is refused when it is made, rather than left to shadow — or be shadowed by —
;; the standard at resolution. The framework seeds its own registrar copy of a
;; standard through the fn path, which records no source namespace, so
;; "application" here means a registration with namespace provenance.

(defonce ^:private standard-keys
  ;; The `[kind id]` pairs of the framework standards. Written only by
  ;; `re-frame.image-assembly`, which owns the standard registry.
  (atom #{}))

(defn ^:no-doc set-standard-keys!
  "Record the `[kind id]` pairs of the framework standard registrations, so
  `register!` can refuse an application registration of one. Called by
  `re-frame.image-assembly/register-standard!` and `clear-standards!`, which
  keep it equal to the standard registry's keys. Returns nil."
  [ks]
  (reset! standard-keys (set ks))
  nil)

(defn- refuse-standard-replacement!
  "Throw `:rf.error/image-standard-replacement-forbidden` when `metadata` is an
  application registration (it carries namespace provenance) of a framework
  standard's `[kind id]`. Runs before anything is written."
  [kind id metadata]
  (when (contains? @standard-keys [kind id])
    (when-let [provenance (rf.source-store/provenance-ns metadata)]
      (rf.error/throw-error!
        :rf.error/image-standard-replacement-forbidden
        're-frame.registrar/register!
        (str (pr-str [kind id]) " is a FRAMEWORK STANDARD registration, so the "
             "registration of it from namespace " provenance " is refused. A "
             "standard encodes an execution invariant and is not an application "
             "extension point. Rename the application's id.")
        {:recovery :rename-the-app-id-or-deselect-it
         :extra    {:kind                kind
                    :id                  id
                    :standard-coordinate {:standard true}
                    :app-coordinate      {:ns provenance}}}))))

;; ---- the hot-reload cycle (dev builds) ------------------------------------
;;
;; Re-evaluating a namespace replaces each registration it makes again, but a
;; registration it no longer makes — a handler moved to another namespace,
;; renamed or deleted — keeps its slot in the registration table. A shadow-cljs development
;; build runs every `^:dev/before-load` hook before it reloads code and every
;; `^:dev/after-load` hook after, so the two hooks on `begin-reload-cycle!` and
;; `sweep-reload-cycle!` (below `clear-all!`) bracket one reload, with no setup
;; in the application. Between them `register!` notes, per source namespace,
;; each `[kind id]` that namespace registers; the sweep then forgets, for every
;; namespace that registered anything, each registration it did not make again.
;;
;; Only a reload is recorded. Registrations made at runtime, at the REPL or in
;; a test never count, because no cycle is open then. A namespace that
;; registered nothing in the cycle is untouched, and that includes one whose
;; LAST registration moved out: its old slot stays, and the id it shares is
;; ambiguous until `(rf/clear kind id)`. Nothing runs the hooks in a release
;; build, and the recording sits behind `rf.interop/debug-enabled?`, so Closure
;; removes all of it.

(defonce ^:private reload-cycle
  ;; nil outside a hot-reload cycle. During one: source namespace → the set of
  ;; `[kind id]` that namespace registered since the cycle began.
  (atom nil))

(defn- note-reload-registration!
  "Record that `stored` — the descriptor `register!` just wrote to the
  registration table — was made during the open hot-reload cycle, if one is open. A
  registration with no source namespace cannot be reloaded, so it is not
  recorded."
  [kind id stored]
  (when (some? @reload-cycle)
    (when-let [provenance (get stored rf.source-store/provenance-ns-key)]
      (swap! reload-cycle update provenance (fnil conj #{}) [kind id])))
  nil)

;; ---- registration ---------------------------------------------------------

(defonce ^:private replacement-hooks
  ;; Subscribers to "what an existing id means just changed": a
  ;; re-registration, or the hot-reload sweep deleting the id. Each is called
  ;; with a map {:kind :id :was :now :different-fn?}, `:now` nil for a
  ;; deletion. Registered by namespaces that need to respond to hot-reload
  ;; (e.g. subs.cljc invalidates its cache).
  (atom []))

(defn add-replacement-hook!
  "Register a fn called on every register! that replaces an existing
  registration, and on every deletion by the hot-reload sweep
  ([[sweep-reload-cycle!]]). Args: a map {:kind kind :id id :was prev-meta
  :now new-meta :different-fn? bool}; `:now` is nil for a deletion. An
  explicit [[unregister!]] does not fire it."
  [f]
  (swap! replacement-hooks conj f)
  nil)

(defn- run-replacement-hooks!
  "Call every replacement hook with `change`. Hooks run isolated — a
  listener's failure does not propagate."
  [change]
  (doseq [f @replacement-hooks]
    (try (f change)
         (catch #?(:clj Throwable :cljs :default) _ nil))))

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
;; — `rf.late-bind/get-fn-cached` memoises it.
;;
;; Each call site keeps its OUTERMOST `(when rf.interop/debug-enabled? ...)`
;; gate. That gate is the load-bearing condition Closure constant-folds
;; under `:advanced + goog.DEBUG=false` (Spec 009 §Production builds),
;; and the `:rf.registry/*` operation keywords are literal args at the
;; call sites — moving the gate inside this helper would leave those
;; literals reachable from the unconditional helper call and defeat the
;; elision-probe sentinels.

(defn- emit!
  "Invoke `:trace/emit!` with the `:rf.registry` op-type, memoising the
  late-bind resolution through `rf.late-bind/get-fn-cached` (the shared
  memoisation pattern lives in `re-frame.late-bind`). Callers MUST
  wrap invocations in `(when rf.interop/debug-enabled? ...)` so Closure
  DCE elides the call and its literal args under `:advanced +
  goog.DEBUG=false`."
  [operation tags]
  (when-let [f (rf.late-bind/get-fn-cached :trace/emit!)]
    (f :rf.registry operation tags)))

(defn- dedup-allow?
  "Consult the B4 hot-reload dedup-by-shape table (per Spec 009
  §Hot-reload dedup — re-emits suppressed by shape). Returns
  true if the emit should proceed; false if the prior emit for this
  `(kind, id)` already carried an identical shape (so this re-emit
  carries no new signal).

  When the trace.tooling sibling is not loaded (production CLJS counter
  bundle), the late-bind lookup returns nil and we allow-by-default —
  the surrounding `rf.interop/debug-enabled?` gate elides the whole branch
  anyway in :advanced + goog.DEBUG=false builds."
  [operation kind id meta]
  (if-let [f (rf.late-bind/get-fn-cached :trace.tooling/dedup-allow?)]
    (f operation kind id meta)
    true))

(defn- emit-warning!
  "Invoke `:trace/emit!` with the `:warning` op-type. Sibling to
  `emit!` (which uses `:rf.registry`). Callers MUST wrap invocations in
  `(when rf.interop/debug-enabled? ...)` so Closure DCE elides the call
  and its literal args under `:advanced + goog.DEBUG=false`."
  [operation tags]
  (when-let [f (rf.late-bind/get-fn-cached :trace/emit!)]
    (f :warning operation tags)))

;; ---- warn-once caches (Spec 001 §`:doc` is dev-warned when absent,
;;                        Spec 001 §Re-registration of a different
;;                        function — collision warning) ---------------------
;;
;; `:rf.warning/missing-doc` fires at most once per `(kind, id)` pair
;; within a runtime process. `:rf.warning/registration-collision` uses
;; the same suppression discipline.
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
  source coords merged in via `rf.source-coords/merge-coords` (Spec 001
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

(def ^:dynamic ^:no-doc *missing-doc-exempt?*
  "True while a registration whose authoring form has no slot for a `:doc` is
  in flight, so `register!` emits no `:rf.warning/missing-doc` for it. The
  schemas artefact binds it around each entry of a `reg-app-schemas` batch: a
  `{path -> schema}` pair has nowhere to put a per-path `:doc`."
  false)

(defn ^:no-doc maybe-emit-missing-doc!
  "Emit `:rf.warning/missing-doc` once per `(kind, id)` when `meta`
  came from the public macro path and carries no usable `:doc`.
  Per Spec 001 §`:doc` is dev-warned when absent.

  The one emitter for the warning: `register!` calls it for every
  registrar kind, sharing this suppression cache, unless
  [[*missing-doc-exempt?*]] is bound true.

  Callers MUST wrap invocations in `(when rf.interop/debug-enabled? ...)`
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
  registration table keys on (`source-store.cljc`: same `(kind, id, ns)` is a
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

  Callers MUST wrap invocations in `(when rf.interop/debug-enabled? ...)`
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
  production and MUST be retained — the `:sensitive` / `:large` /
  `:large?` classification keys drive egress projection, as do a resource
  or mutation spec's `:sensitive?` / `:large?` root props (a handler's own
  `:sensitive?` classifies nothing) (Spec 015 / EP-0015); `:tags` /
  `:interceptors` / the resource-mutation runtime keys drive runtime
  behaviour; `:schema` drives PRODUCTION payload validation — the
  `re-frame.spec/validate-at-boundary!` production arm (the router's
  step-1 site for `:boundary? true` handlers, on the original dispatched
  event vector, before the interceptor chain) and the always-on
  `re-frame.cofx` recordable-value check that throws
  `:rf.error/cofx-value-invalid` — and its per-slot `:sensitive?` /
  `:large?` props survive ONLY to redact THAT validator's own failure trace
  and thrown ex-data, NOT as a route into data classification (EP-0025:
  there is no schema→registry bridge, nor an EP-0005 `:data-schema`→marks
  bridge — see `re-frame.elision`). A resource `:data-schema`
  drives NEITHER axis: it is a statically reflected shape fact with no
  runtime validation consumer, surfaced as the process-node `:schema` by
  `re-frame.resources.tooling`, and is retained as that reflection surface.
  NOTHING is precomputed from either at registration time — durable app-db
  classification rides the commit-plane classification effects into the
  per-frame elision registry, and a registration's transient-payload
  classification is the author's own `:sensitive` / `:large` / `:large?`
  keys, DERIVED at read time off this registrar by
  `re-frame.classification/registration-classification`.
  `:rf/id` and the handler fn ARE the registration.

  Adding a key here is a Spec change (Spec 001 §Production elision
  contract — the elidable-vs-retained classification table)."
  #{:doc})

(defn strip-pure-documentation
  "Drop the pure-documentation keys (`pure-documentation-keys`) from a
  registration `metadata` map in production builds (`rf.interop/debug-enabled?`
  false). Returns `metadata` unchanged in dev. Per Spec 001 §Production
  elision contract.

  The `rf.interop/debug-enabled?` gate is the OUTERMOST form so Closure
  constant-folds it under `:advanced` + `goog.DEBUG=false`: the dev arm
  (the unchanged map) DCEs in production, and the `apply dissoc` strip
  DCEs in dev — zero runtime cost either way. A non-map `metadata` (no
  documentation keys to carry) passes through untouched."
  [metadata]
  (if (or (not rf.interop/debug-enabled?)
          (not (map? metadata)))
    ;; Production OR a non-map metadata: strip the pure-documentation keys.
    ;; (A non-map metadata cannot carry them, so `dissoc`/`apply dissoc`
    ;; would be a no-op anyway — short-circuit to avoid touching it.)
    (if (map? metadata)
      (apply dissoc metadata pure-documentation-keys)
      metadata)
    ;; Dev: retain documentation for tooling / agent inspection.
    metadata))

(defn- executable-identity
  "The value that IS this registration's executable identity — the thing
  `:different-fn?` compares, and the thing a devtool has to refresh its
  view of when it rotates.

  For every kind the framework registers through its own `reg-*` macros
  that value is the `:handler-fn`. A registration whose executable
  identity lives SOMEWHERE ELSE says where, by naming the key under
  `:executable-key`.

  NO registration in the tree uses that. Fresco's authoring-time `:view`
  alias (`re-frame.fresco.impl.collector/publish-view-alias!`) carries its
  minted head at `:handler-fn`, because `re-frame.views/view-head` returns
  a `:view` slot it did not itself build exactly as stored: the ordinary
  key costs the boundary nothing and buys `(rf/view id)` an answer on every
  substrate. The seam is for a registration whose executable is NOT a
  `:handler-fn` — a boundary whose head is a React component kept at a
  private slot with no `:handler-fn`, say. Comparing `:handler-fn` on that
  shape would compare nil with nil, reporting `:different-fn? false` for a
  real component swap and telling every hot-reload consumer the reload was
  idempotent when it was not; naming the private slot under
  `:executable-key` avoids that silent-idempotent-reload defect. It is two
  `get`s and no branch.

  It POINTS at the slot rather than duplicating the head into a second
  one: one home for the value, and a registrar that stays ignorant of
  which substrate wrote the entry — it reads a key the registration
  itself named. Absent `:executable-key` — every framework `reg-*` in
  the tree — this is exactly `(:handler-fn metadata)`. A non-map
  `metadata` answers nil, as a bare `:handler-fn` lookup would."
  [metadata]
  (get metadata (get metadata :executable-key :handler-fn)))

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
  real changes of the registration's executable identity, without
  re-emitting through a separate surface. That identity is the
  `:handler-fn` unless the registration named another slot under
  `:executable-key` — see `executable-identity`."
  [kind id metadata]
  (when-not (valid-kind? kind)
    (rf.error/throw-error!
      :rf.error/unknown-registry-kind
      'rf/register-handler
      (str "unknown registry kind " kind " — not one of the registered registry kinds")
      {:recovery :fix-registration
       :extra    {:kind kind
                  :id   id}}))
  (refuse-standard-replacement! kind id metadata)
  ;; Pure-documentation metadata elision. Under `:advanced` +
  ;; `goog.DEBUG=false` strip the pure-documentation keys (`:doc`) BEFORE the
  ;; metadata is stored, so `(rf/handler-meta {:source :store :kind kind :id id})` carries no `:doc` in
  ;; production — consistent with source-coords already being absent there
  ;; (Spec 001 §Production elision contract). The `rf.interop/debug-enabled?`
  ;; gate inside `strip-pure-documentation` is the OUTERMOST form, so Closure
  ;; constant-folds the strip away in dev and the dev `:doc` retention away
  ;; in prod. NOTE: the dev-only `:rf.warning/missing-doc` check below reads
  ;; `(:doc metadata)` — it is itself gated on `rf.interop/debug-enabled?`, so it
  ;; only fires in dev where `:doc` is still present; the strip never hides a
  ;; missing-doc warning.
  ;;
  ;; The registration's own coordinate rides the stored descriptor (as
  ;; metadata, so the public registry-meta stays coord-free in production) into
  ;; the registration table and every generation that selects it; error-emit
  ;; and tooling read it off the descriptor they resolved. A registration with
  ;; no macro on the stack carries none — see
  ;; `rf.source-coords/with-registration-coords`.
  (let [metadata (-> (strip-pure-documentation metadata)
                     (rf.source-coords/with-registration-coords
                       rf.source-coords/*pending-coords*))
        ;; The id's latest registration before this one, from whichever
        ;; namespace made it — so a second namespace registering the id is a
        ;; re-registration here, exactly as a same-namespace re-eval is.
        previous (rf.source-store/latest-descriptor kind id)
        ;; The one write. The table keys it by [kind id provenance-namespace],
        ;; so a cross-namespace duplicate is retained beside the first and a
        ;; same-namespace re-eval replaces its own slot (hot reload). NO
        ;; assembly / selection / collision decision is made here: that belongs
        ;; to the reader — a frame's live view, or image assembly.
        stored   (rf.source-store/record-descriptor! kind id metadata)]
    (when rf.interop/debug-enabled?
      (note-reload-registration! kind id stored))
    ;; RECORDING THE CHANGE COMPLETES BEFORE ANYTHING REACTS TO IT.
    ;;
    ;; These two hook batches are not peers. `registration-hooks` finish
    ;; RECORDING what just happened — `re-frame.live-frame`'s
    ;; `reproject-on-registration-change!` marks the live-frame projection dirty,
    ;; which is what makes this registration visible to the next frame-generation
    ;; resolution — while `replacement-hooks` REACT to it, and the reaction that
    ;; matters here is `re-frame.subs.cache`'s cache invalidation.
    ;;
    ;; A consumer that merely drops its cache entry and rebuilds LATER, past
    ;; the mark, cannot tell the two orders apart — but one rebuilds DURING the
    ;; invalidation: the React-hook spine reacquires from inside the disposal
    ;; so a mounted component follows its subscription across the eviction
    ;; instead of going deaf. Reacquiring before the projection was marked
    ;; dirty would resolve the frame's STALE generation and rebuild the entry
    ;; against the very body the re-registration had just replaced — the
    ;; hot-reload the invalidation exists to serve, defeated by the order in
    ;; which it was announced.
    ;;
    ;; Marking is a flag plus a coalesced `next-tick` schedule (per-frame
    ;; conditional at flush time, so marking on every `reg-*` is safe by that
    ;; hook's own contract), and both batches run ISOLATED, so the order
    ;; affects no failure propagation — only that a reactor observes a
    ;; fully-recorded world.
    (doseq [f @registration-hooks]
      (try (f {:kind kind :id id :was previous :now metadata})
           (catch #?(:clj Throwable :cljs :default) _ nil)))
    (cond
      ;; Re-registration path — fire hooks and emit handler-replaced.
      previous
      (let [different? (not= (executable-identity previous)
                             (executable-identity metadata))]
        ;; Hot-reload notifications. Hooks run isolated — listener failures
        ;; don't propagate. Hooks fire on EVERY re-registration so dependent
        ;; namespaces can clean up their caches even on idempotent reloads
        ;; (the same fn shape is fine; closure state may differ).
        (run-replacement-hooks! {:kind kind :id id :was previous :now metadata
                                 :different-fn? different?})
        ;; Per Spec 001 §Hot-reload trace surface: emit
        ;; `:rf.registry/handler-replaced` on EVERY re-registration —
        ;; not only when the handler-fn changes. Kinds like `:route`
        ;; replace the slot without rotating `:handler-fn`, so gating the
        ;; emit on `different?` would drop legitimate re-registration
        ;; events on the floor. The `:different-fn?` tag is preserved
        ;; for tools that want to suppress idempotent-reload noise on
        ;; their side.
        ;;
        ;; The `rf.interop/debug-enabled?` gate stays OUTERMOST so
        ;; `:advanced + goog.DEBUG=false` constant-folds the entire
        ;; branch (per Spec 009 §Production builds).
        ;;
        ;; Per Spec 009 §Hot-reload dedup — re-emits suppressed by shape
        ;; (B4 ruling): consult the dedup table. Identical
        ;; shape on re-register (a hot-reload that didn't actually
        ;; change the handler) emits ZERO `:rf.registry/handler-replaced`
        ;; trace events; a real edit emits exactly one.
        (when rf.interop/debug-enabled?
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
          ;; `:handler-fn` — `:route`, `:head` — can be deduped-away
          ;; by shape, so nesting the collision check inside that gate would
          ;; let a GENUINE cross-source clash for those kinds go unwarned.
          ;; Called here independently (still under `rf.interop/debug-enabled?`
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
      (when rf.interop/debug-enabled?
        (when (dedup-allow? :rf.registry/handler-registered kind id metadata)
          (emit! :rf.registry/handler-registered {:kind kind :id id}))))
    ;; Per Spec 001 §`:doc` is dev-warned when absent. Fires
    ;; on every reg-* call whose final metadata-map carries no usable
    ;; `:doc`, once per (kind, id) within the runtime process. Production
    ;; elides via the outer `rf.interop/debug-enabled?` gate (Spec 009
    ;; §Production builds). Fires on BOTH first-time and re-registration
    ;; — the consult+emit body is suppressed by the per-(kind, id) cache
    ;; on subsequent calls; obligation 2 says re-registering the same id
    ;; without `:doc` does NOT re-fire the warning.
    (when rf.interop/debug-enabled?
      (when-not *missing-doc-exempt?*
        (maybe-emit-missing-doc! kind id metadata)))
    ;; The always-on registration hooks — fired on BOTH first-time and
    ;; re-registration so cross-id invariants (e.g. routing's `:url-bound?`
    ;; exclusivity per Spec 012 §Multi-frame routing) are validated at the
    ;; moment of any registration — run ABOVE, before the replacement
    ;; hooks. See the note there for why the order is load-bearing.
    ;; Both sites are after the table write, so a hook inspects the final
    ;; registry state.
    {:was previous :now metadata}))

(defn unregister!
  "Remove a single id under kind. Hot-reload code paths use this; user code
  rarely does."
  [kind id]
  (let [previous (rf.source-store/latest-descriptor kind id)]
    ;; The id goes wholesale: every provenance slot for `(kind, id)`.
    (rf.source-store/forget-id! kind id)
    ;; A removal is a registration-table change — mark the live-frame
    ;; projection dirty (late-bound; the register side rides the registration
    ;; hook, removals ride this) so default-image frames drop the cleared
    ;; handler at their next resolution rather than resolving a stale sealed
    ;; generation. NOT gated on `rf.interop/debug-enabled?` — the
    ;; sealed generation of a frame made with `:images` is produced
    ;; unconditionally by `make-frame`, so
    ;; skipping the dirty mark in production would leave a cleared handler
    ;; resolving forever out of a table that no longer backs it. Late-bound by keyword;
    ;; unpublished (no frame ever constructed) means no-op.
    (when-let [mark-dirty! (rf.late-bind/get-fn :live-frame/mark-projection-dirty!)]
      (mark-dirty!))
    ;; Per Spec 009 §:op-type vocabulary: :rf.registry/handler-cleared
    ;; fires on explicit removal so hot-reload tools can update their
    ;; views. Only emit when something was actually present.
    ;;
    ;; Per Spec 009 §Hot-reload dedup (B4 ruling): a clear
    ;; of an id the dedup table thinks is already absent (a double-
    ;; clear) is suppressed. The first clear records the `::cleared`
    ;; sentinel; subsequent re-clears emit nothing.
    (when rf.interop/debug-enabled?
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
  (let [previous-ids (keys (get @rf.source-store/kind->id->ns->descriptor kind))
        clear-kind   (fn [cache-set]
                       (into #{} (remove #(= kind (first %))) cache-set))]
    ;; Through the source-store API, so the store GENERATION is bumped: a raw
    ;; `swap!` would leave the resolved-image-generation cache to HIT and return
    ;; a stale generation that still resolves the just-cleared `(kind, id)`s.
    (rf.source-store/clear-kind! kind)
    ;; Same ungated removal dirty-mark as
    ;; `unregister!` (see there).
    (when-let [mark-dirty! (rf.late-bind/get-fn :live-frame/mark-projection-dirty!)]
      (mark-dirty!))
    (swap! missing-doc-warned clear-kind)
    (swap! collision-warned   clear-kind)
    ;; Per Spec 009 §:op-type vocabulary: :rf.registry/handler-cleared
    ;; fires for each id so consumers see consistent registry transitions.
    ;; B4 dedup: a clear-of-a-cleared id is suppressed; the
    ;; first clear records the `::cleared` sentinel.
    (when rf.interop/debug-enabled?
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
  (reset! missing-doc-warned #{})
  (reset! collision-warned   #{})
  ;; Empty the registration table (and its namespace-string pool).
  (rf.source-store/clear-all!)
  ;; Same ungated removal dirty-mark as `unregister!`
  ;; (see there).
  (when-let [mark-dirty! (rf.late-bind/get-fn :live-frame/mark-projection-dirty!)]
    (mark-dirty!))
  ;; Clear the B4 dedup table when the trace.tooling sibling is loaded.
  ;; Production CLJS bundles that never load trace.tooling silently
  ;; no-op here — the dedup hook is unbound and there's no table to
  ;; clear anyway.
  (when rf.interop/debug-enabled?
    (when-let [clear! (rf.late-bind/get-fn-cached :trace.tooling/clear-dedup-table!)]
      (clear!)))
  nil)

;; ---- the hot-reload sweep (dev builds) ------------------------------------
;;
;; The two hooks that bracket a hot-reload cycle; see the section comment above
;; `reload-cycle`. Both are public only so shadow-cljs can find them, and so a
;; test can drive one cycle by calling them around the registrations a reload
;; would re-evaluate.

(defn ^:no-doc ^:dev/before-load begin-reload-cycle!
  "Open a hot-reload cycle: from now until [[sweep-reload-cycle!]],
  `register!` records which namespace makes each registration. Discards any
  cycle a failed reload left open. Returns nil."
  []
  (when rf.interop/debug-enabled?
    (reset! reload-cycle {}))
  nil)

(defn- forget-stale-registration!
  "Forget the registration namespace `provenance` made of `[kind id]`. When it
  is the id's only registration, that is [[unregister!]] followed by the
  replacement hooks with `:now` nil: the deletion changed what the id means,
  so state built from the deleted registration goes with it — a sub's cached
  reactions above all, which nothing else drops in a frame made without
  `:images`: it has no generation for a reprojection to diff, so it would keep
  running the deleted body. [[unregister!]] fires no hook
  itself, because an explicit `(rf/clear :sub id)` is registry-only and leaves
  the cache to `clear-sub-cache!`. The hooks run after [[unregister!]] has
  marked the live-frame projection dirty, so a consumer rebuilding inside one
  resolves a world without the id.

  Otherwise only that namespace's slot goes, and the other registrations stay."
  [kind id provenance]
  (let [ns->desc (rf.source-store/descriptors-for kind id)]
    (if (= #{provenance} (set (keys ns->desc)))
      (let [previous (rf.source-store/latest ns->desc)]
        (unregister! kind id)
        (run-replacement-hooks! {:kind kind :id id :was previous :now nil
                                 :different-fn? true}))
      (rf.source-store/forget-descriptor! kind id provenance))))

(defn ^:no-doc ^:dev/after-load sweep-reload-cycle!
  "Close the hot-reload cycle [[begin-reload-cycle!]] opened. Every namespace
  that registered anything during it loses each registration it did not make
  again — a handler moved to another namespace, renamed or deleted — so a
  moved id resolves to its new home and a deleted one is gone, a deleted
  sub's cached reactions included. A namespace that registered nothing keeps
  everything. With no cycle open (the first
  boot) it does nothing. Marks the live-frame projection dirty when anything
  was forgotten, so frames with explicit `:images` re-assemble; a frame made
  without `:images` reads the store at its next cascade anyway. Returns nil."
  []
  (when rf.interop/debug-enabled?
    (when-let [made @reload-cycle]
      (reset! reload-cycle nil)
      (let [stale (vec (for [[kind id->ns] @rf.source-store/kind->id->ns->descriptor
                             [id ns->desc] id->ns
                             provenance    (keys ns->desc)
                             :let  [ids (get made provenance)]
                             :when (and ids (not (contains? ids [kind id])))]
                         [kind id provenance]))]
        (doseq [[kind id provenance] stale]
          (forget-stale-registration! kind id provenance))
        (when (seq stale)
          (when-let [mark-dirty! (rf.late-bind/get-fn :live-frame/mark-projection-dirty!)]
            (mark-dirty!))))))
  nil)

;; ---- lookup ---------------------------------------------------------------

(defn lookup
  "Return the descriptor registered for (kind, id), or nil.

  EP-0023 §Frame-derived live registration resolution: when an
  EP-0023 frame OBJECT's image generation is in scope (`*generation*` bound by
  `re-frame.live-frame/call-with-frame-resolution`), the `(kind, id)` resolves
  through the FRAME'S OWN generation resolver — so two frames running different
  images resolve the same `[kind id]` to their OWN image's descriptor.
  With no generation bound, a bound `*live-view*` (a frame made without
  `:images`) resolves the id against its snapshot of the registration table,
  throwing `:rf.error/ambiguous-registration` for an id more than one namespace
  registers. With neither bound — every caller not resolving against a frame —
  the id's latest registration answers (`re-frame.source-store/latest`).

  The GENERATION-ROUTED branch builds a `[kind id]` pair per call (measured at
  ~98 B/call on the JVM), because `:rf.gen/resolver`'s `{[kind id] descriptor}`
  shape is normative (Spec API.md §`frame-generation`, Conventions.md
  §`:rf.gen/*`) and is read as plain data by Xray, the Pair MCP runtime and
  Story; the other two branches read paired `get`s."
  [kind id]
  (if-let [resolver (generation-resolver)]
    (get resolver [kind id])
    (if-let [view *live-view*]
      (view kind id)
      (rf.source-store/latest-descriptor kind id))))

(defn handler
  "Return just the handler fn from the metadata, or nil. The handler is
  stored under :handler-fn in the metadata map by the kind-specific reg-*
  macros (events) or directly (subs/fx)."
  [kind id]
  (when-let [meta (lookup kind id)]
    (:handler-fn meta)))

;; ---- query API (per Spec 002 §The public registrar query API) -------------

(defn registrations
  "All ids registered under kind, with their descriptors. Tools, agents,
  storybook resolution, all use this.

  Two arities:
    (registrations kind)
      Return the full `{id descriptor}` map for kind, or `{}` if the kind
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
  is in scope (`*generation*` bound), the `{id descriptor}` map is PROJECTED
  from the generation's resolver for `kind` — only the ids the frame's OWN image
  carries. A bound `*live-view*` projects the ids the live frame can run (an
  ambiguous id is not one of them). Unbound ⇒ each id's latest registration."
  ([kind]
   (if-let [resolver (generation-resolver)]
     (kind-registrations-from-resolver resolver kind)
     (if-let [view *live-view*]
       (view kind)
       (reduce-kv (fn [acc id ns->desc]
                    (assoc acc id (rf.source-store/latest ns->desc)))
                  {}
                  (get @rf.source-store/kind->id->ns->descriptor kind)))))
  ([kind pred-fn]
   (into {}
         (filter (fn [[_id meta]] (pred-fn meta)))
         (registrations kind))))

(defn ambiguous-registrations
  "The ids of `kind` that `view` cannot run because more than one namespace
  registers them, as `{id {provenance-ns descriptor}}` — the slots the
  registration table holds for each. `view` is a live view
  (`re-frame.live-frame/live-view`): the `{:source :store}` read passes a fresh
  one, and the one-arity form uses the bound [[*live-view*]].

  The one-arity form answers `{}` under a sealed generation and with no frame
  resolution bound, where each id resolves to one registration. A consumer that
  matches against a whole kind rather than looking up one id — the route
  table — reads it to keep an ambiguous id in view and raise when that id is the
  one it would use, instead of silently using another."
  ([kind]
   (if (generation-resolver)
     {}
     (if-let [view *live-view*]
       (ambiguous-registrations view kind)
       {})))
  ([view kind]
   (let [shared (into {}
                      (filter (fn [[_id ns->desc]] (< 1 (count ns->desc))))
                      (get @rf.source-store/kind->id->ns->descriptor kind))]
     (if (seq shared)
       (let [runnable (view kind)]
         (into {} (remove (fn [[id _]] (contains? runnable id))) shared))
       {}))))

(defn handler-meta
  "Public alias for lookup. Used by tooling."
  [kind id]
  (lookup kind id))

(defn ids
  "Just the id set for a kind."
  [kind]
  (-> (registrations kind) keys set))
