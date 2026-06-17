(ns re-frame.image-assembly
  "EP-0023 §Image Validation / §Image Patching And Overrides / §Image
  Composition — the ASSEMBLY slice (rf2-32siq3.4): resolve one or more image
  values into a SEALED, VALIDATED image generation and fail loud before a frame
  can run it.

  This is the integration of the two merged foundation slices:

    * `re-frame.source-store` (slice .2) — the provenance-preserving registration
      SOURCE STORE keyed `[kind id provenance-ns]`. `all-descriptors` is the
      candidate pool this slice selects from.
    * `re-frame.image` (slice .3) — the `rf/image` constructor + the PURE
      `select-descriptors` selector. This slice runs that selector against the
      live source store's descriptors, then validates and seals.

  ## What a sealed image generation IS

  The resolved image generation is the inert, immutable data structure a frame
  resolves registration lookups against (EP-0023 §Specification Summary):

      {:rf.gen/resolver  {[kind id] descriptor, …}   ;; the sealed [kind id] map
       :rf.gen/images    [<normalized image value> …]
       :rf.gen/requires  #{:rf.capability/* …}        ;; union of image requires
       :rf.gen/kinds     #{kind …}}                   ;; kinds present, for tools

  `:rf.gen/resolver` is the heart: a map from a `[kind id]` pair to exactly ONE
  descriptor. After selection + declared replacements the map is id-disjoint by
  `(kind, id)` (EP-0023 §Id Spaces). The runtime resolves `(kind, id)` lookups
  through it; `resolve-descriptor` is the read API. The generation carries no
  function closures beyond the descriptors themselves and is safe to share
  across frames (EP-0023 §Image — \"Resolved generations are immutable\").

  ## The pipeline (EP-0023 §Namespace-Selected Images \"Image assembly then\")

      1. collect the requested image values;
      2. select matching registered descriptors per image (via
         re-frame.image/select-descriptors against the source store's
         all-descriptors) PLUS each image's inline descriptors;
      3. (slice .3 already fails any zero-match :include-ns pattern);
      4. add the framework STANDARD registrations;
      5. validate collisions, replacements, capabilities, references, kinds;
      6. seal the result into an immutable [kind id] resolver;
      7. give the frame that sealed generation (frame loading is slice .7).

  ## The DEFAULT image (rf2-32siq3.24)

  The DEFAULT image (EP-0023 §Default Image Semantics) is the implicit selector
  over the WHOLE default source store: `reg-*` mutates the source store, and the
  default image projects ALL of its descriptors (+ the framework standards) into
  a sealed generation. It is the no-explicit-`:images` frame path. `default-image`
  is a marker image value (`:rf.image/default? true`) that rides the SAME
  `assemble*` pipeline + the SAME resolved-generation cache as an explicit image
  — the only difference is selection (the whole pool rather than an `:include-ns`
  glob match). Crucially, the default projection FAILS LOUD on a cross-namespace
  same-`[kind id]` collision via the same `resolve-collision` /
  `:rf.error/image-duplicate-id` path (no last-write-wins on the default path).
  Entry points: `assemble-default` (both arities), and `assemble` routes its
  empty-`:images` case to it. The default generation is cached keyed on the
  source-store generation (+ standard generation), so it invalidates the instant
  any `reg-*` / `forget-*` / `clear-*` bumps the store.

  ## Validation is FAIL-LOUD (the central guarantee)

  Every validation failure throws via the central `re-frame.error/throw-error!`
  with a DISTINCT `:rf.error/id`. The load-bearing rule (EP-0023 §Image
  Composition): **order must NEVER silently decide which descriptor wins.** A
  real `(kind, id)` collision among selected descriptors with no declared
  replacement winner is an ERROR (`:rf.error/image-duplicate-id`), not a
  last-write. The conditions detected here (the EP §Image Validation list):

      duplicate id (same [kind id], differing impl, no declared winner)
        -> :rf.error/image-duplicate-id
      unsupported registration kind in the image path
        -> :rf.error/image-unsupported-kind
      event references a missing interceptor / resource references a missing
        scope resolver ({:from-db <id>} naming an unselected :resource-scope)
        -> :rf.error/image-missing-reference
      a :replace / :replace-standard declared for a key with no actual
        collision (zero or exactly one selected descriptor — stale / typo /
        an order override, NOT a collision resolution)
        -> :rf.error/image-replacement-no-collision
      a :replace / :replace-standard winner that resolves to zero or many
        selected descriptors (a stale winner source, a typo, an ambiguous coord)
        -> :rf.error/image-replacement-winner-unresolved
      composed images declaring DIFFERENT winners for the same [kind id]
        (a cross-image replacement conflict — order must not decide)
        -> :rf.error/image-replacement-conflict
      replacing a non-replaceable framework standard registration
        -> :rf.error/image-standard-replacement-forbidden
      an image-required capability the frame does not supply
        -> :rf.error/image-missing-capability

  ## The .5 / .6 seams (documented boundary)

  This slice builds the validation STRUCTURE and fails loud on every condition
  the EP names for assembly. One seam (.6) is left for a sibling slice to deepen;
  the .5 replacement policy is now LANDED here:

    * SLICE .5 (replacement policy — LANDED) — owns the EXACT `:replace` /
      `:replace-standard` winner policy: a replacement declares the survivor of
      a REAL collision and is never a silent order override. The three fail-loud
      legs (EP-0023 §Image Validation / §Image Patching And Overrides):
        - a declared winner must identify EXACTLY ONE selected descriptor
          ([[resolve-replacement-winner]] → `:rf.error/image-replacement-winner-unresolved`);
        - a declared key must name a REAL collision — a `:replace` /
          `:replace-standard` for a `(kind, id)` with zero or exactly one
          selected descriptor is an error
          ([[check-replacement-keys-collide!]] → `:rf.error/image-replacement-no-collision`);
        - a framework STANDARD is non-replaceable by default; `:replace-standard`
          is the opt-in, and an invariant-coupled standard (a non-empty
          `:rf.standard/requires-conformance`, e.g. `:rf.interceptor/path`) stays
          non-replaceable regardless of the flag until a conformance profile
          proves the invariant is preserved ([[standard-replaceable?]] +
          `resolve-collision` → `:rf.error/image-standard-replacement-forbidden`).
      The seam fns [[resolve-replacement-winner]] / [[standard-replaceable?]]
      remain the future plug points for richer winner-source matching and the
      conformance-profile proof that would lift the invariant-coupled lock; the
      detection STRUCTURE (`.4`'s undeclared-collision fail-loud) is unchanged.

    * SLICE .6 (capability checks) — owns the DEEP capability-map checking
      against the frame's host `:capabilities`. This slice already collects the
      union image-`:rf.image/requires` set onto the generation and exposes
      [[check-capabilities!]] (the fail-loud point keyed
      `:rf.error/image-missing-capability`). `assemble` does NOT call it (no
      frame capability map exists at pure-assembly time — that arrives at
      frame loading, slice .7); slice .6 wires the call at the frame boundary.

  ## Production elision

  Pure data + map/seq ops over plain maps; no trace emit sites, no DEBUG-gated
  branches, no feature sentinels. The only requires are `re-frame.image`,
  `re-frame.source-store`, `re-frame.registrar` (the closed kind set), and
  `re-frame.error` (fail-loud diagnostics) — all already in the core spine.
  Assembly runs on the runtime/SSR path, not under a debug gate; an app that
  never assembles an image never reaches these fns (Closure DCE removes them)."
  (:require [re-frame.image       :as image]
            [re-frame.source-store :as source-store]
            [re-frame.registrar   :as registrar]
            [re-frame.late-bind   :as late-bind]
            [re-frame.error       :as error]))

#?(:clj (set! *warn-on-reflection* true))

;; ===========================================================================
;; Framework standard registrations (EP-0023 §Image — \"+ framework standard
;; registrations\")
;; ===========================================================================
;;
;; A resolved image generation = selected descriptors + framework STANDARD
;; registrations. A standard descriptor is identified by `{:standard true}`
;; plus its `(kind, id)` (EP-0023 §Image Fragments). It carries the
;; replacement-policy keys the standard-replacement guard reads:
;;
;;   :rf.standard/replaceable?            default false — non-replaceable
;;   :rf.standard/requires-conformance    #{invariant …} — a replacement must
;;                                        satisfy the named invariants
;;
;; The standard SET is a runtime registry (an atom) so feature artefacts that
;; ship standard registrations (e.g. the `:rf.interceptor/path` standard from
;; EP-0022, the `:rf.nav/*` standards from routing) can contribute their
;; descriptors at load without this core ns static-requiring them. Assembly
;; reads the current set. The first version ships an EMPTY standard set — the
;; structure + policy keys are in place; the specific standard descriptors are
;; contributed by their owning artefacts in later wave work.

(defonce ^{:doc "Framework-standard descriptors, keyed `[kind id]` → descriptor.
  Each descriptor carries `:standard true` and the `:rf.standard/*` policy keys.
  An atom so an artefact shipping a standard registration contributes its
  descriptor at load (`register-standard!`) without core static-requiring it."}
  standard-registry
  (atom {}))

(defonce ^{:doc "A MONOTONIC generation integer for the framework-standard
  registry (EP-0023 §Image — the resolved-generation cache's
  framework-standard-registration generation key leg). Bumped on every
  `register-standard!` / `forget-standard!` / `clear-standards!` mutation; read
  by the resolved-generation cache key so a cached generation is invalidated the
  instant any standard descriptor changes. Starts at 0."
            :private true}
  standard-generation*
  (atom 0))

(defn standard-generation
  "The framework-standard registry's MONOTONIC generation integer (EP-0023
  §Image — the resolved-generation cache key's standard-registration leg).
  Increments on every `register-standard!` / `forget-standard!` /
  `clear-standards!`; equal across two reads ⇒ the standard set is unchanged and
  a sealed generation assembled then may be reused."
  []
  @standard-generation*)

(defn register-standard!
  "Contribute a framework-standard descriptor for `[kind id]`. `descriptor` is
  stamped `:standard true`; its replacement policy comes from
  `:rf.standard/replaceable?` (default false) and `:rf.standard/requires-conformance`
  (default `#{}`). Idempotent per `[kind id]` — re-registering replaces the slot
  (the standard owner's hot-reload path). Bumps the standard generation so the
  resolved-generation cache invalidates. Returns the stamped descriptor."
  [kind id descriptor]
  (let [desc (-> descriptor
                 (assoc :kind kind :id id :standard true)
                 (update :rf.standard/replaceable? boolean)
                 (update :rf.standard/requires-conformance #(set (or % #{}))))]
    (swap! standard-registry assoc [kind id] desc)
    (swap! standard-generation* inc)
    desc))

(defn forget-standard!
  "Remove the framework-standard descriptor for `[kind id]`. Mirror of
  `register-standard!` for the standard owner's teardown path. Bumps the
  standard generation."
  [kind id]
  (swap! standard-registry dissoc [kind id])
  (swap! standard-generation* inc)
  nil)

(defn clear-standards!
  "Reset the framework-standard registry. Test fixtures use this between cases.
  Bumps the standard generation so a cache keyed on the prior generation
  invalidates."
  []
  (reset! standard-registry {})
  (swap! standard-generation* inc)
  nil)

(defn standard-descriptors
  "The current framework-standard descriptors as a flat seq (the `{:standard
  true}` set assembly unions into every generation)."
  []
  (vals @standard-registry))

;; ===========================================================================
;; Descriptor coordinates (EP-0023 §Image Fragments — descriptor source
;; identity)
;; ===========================================================================
;;
;;   registered  -> {:ns \"some.source.ns\"}
;;   inline      -> {:image :test/small :inline [:reg-fx :checkout.http/post]}
;;   standard    -> {:standard true} + (kind, id)

(defn descriptor-kind+id
  "The `[kind id]` pair a descriptor resolves under. Pure."
  [descriptor]
  [(:kind descriptor) (:id descriptor)])

(defn descriptor-coordinate
  "The descriptor's SOURCE coordinate — the stable name errors and replacement
  winners use (EP-0023 §Image Fragments). Returns one of:

    {:ns \"...\"}                         registered (provenance namespace)
    {:image <id> :inline [section id]}   inline (image-supplied)
    {:standard true}                     framework standard

  Pure; a function of the descriptor's provenance/standard slots only."
  [descriptor]
  (cond
    (:standard descriptor)
    {:standard true}

    (:rf.provenance/inline descriptor)
    {:image  (:rf.provenance/image descriptor)
     :inline (:rf.provenance/inline descriptor)}

    :else
    {:ns (:rf.provenance/ns descriptor)}))

(defn- descriptor-impl
  "The implementation value carried by a descriptor — `:impl` for an inline
  descriptor, `:handler-fn` for a registered one (the registrar's stored slot).
  Used to decide whether two descriptors for the same `(kind, id)` are the SAME
  registration (a dedupe) or a genuine collision. Pure."
  [descriptor]
  (or (:impl descriptor) (:handler-fn descriptor)))

(defn- same-registration?
  "True when two descriptors for the same `(kind, id)` are the SAME registration
  — identical source coordinate AND identical impl — so they DEDUPE rather than
  collide (EP-0023 §Image Validation: \"same kind + id selected twice with same
  source/impl -> dedupe or ok\"). A registered descriptor selected by two
  overlapping `:include-ns` globs is the canonical dedupe case. Pure."
  [a b]
  (and (= (descriptor-coordinate a) (descriptor-coordinate b))
       (= (descriptor-impl a) (descriptor-impl b))))

;; ===========================================================================
;; Selection — run the slice-.3 selector per image against the live source
;; store, union the framework standards (EP-0023 §Image)
;; ===========================================================================

(defn- source-store-descriptors
  "Every retained registered descriptor across every kind in the active source
  store (slice .2's `all-descriptors` flattened over `kinds-present`). The
  candidate pool slice-.3's `select-descriptors` selects from by
  `:rf.provenance/ns`. Pure read of the store snapshot."
  []
  (into []
        (mapcat source-store/all-descriptors)
        (source-store/kinds-present)))

;; ---- the DEFAULT image (EP-0023 §Default Image Semantics / §Namespace-Selected
;;      Images) — the implicit selector over the WHOLE default source store ------
;;
;; > the default image is the implicit selector over [the default registration]
;; > source [store] … The default image is the implicit selector over all
;; > descriptors in the default source store. It works only while selected ids
;; > are globally unique across the loaded namespaces. If two loaded namespaces
;; > both register the same `(kind, id)`, the default image does not guess and
;; > does not let load order win; default image assembly fails with a collision
;; > error.
;;
;; The default image is NOT an `:include-ns` glob image: it selects EVERY
;; descriptor in the source store, not a namespace-matched subset, so there is
;; no zero-match concern (an empty store projects an empty generation, which is
;; valid — a frame with no app registrations resolving only framework
;; standards). It is a marker image value (`:rf.image/default? true`) so it
;; flows through the SAME `assemble*` pipeline + the SAME resolved-generation
;; cache as an explicit image — the only difference is selection: the whole pool
;; rather than a glob match. Validation + sealing (collision detection via
;; `resolve-collision` → `:rf.error/image-duplicate-id`, standard union,
;; reference checks) are byte-identical to the explicit path, so a
;; cross-namespace same-`[kind id]` collision in the default projection FAILS
;; LOUD exactly as an explicit image's collision does — load order never decides
;; the survivor on the default path either.

(def default-image
  "The DEFAULT image VALUE — the implicit selector over the WHOLE source-store
  pool (EP-0023 §Default Image Semantics). A normalized image value marked
  `:rf.image/default? true` (rather than carrying `:include-ns` globs) so it
  rides the same `assemble*` pipeline + resolved-generation cache as an explicit
  image; `select-for-images` recognizes the marker and selects every descriptor
  in the pool. It declares no inline descriptors, no `:include-ns`, no
  `:rf.image/requires`, and no `:replace` / `:replace-standard` — the default
  path assumes globally-unique ids and fails loud (`:rf.error/image-duplicate-id`)
  on any cross-namespace `(kind, id)` collision rather than declaring a winner.
  A constant value: the default selection is fully described by the marker, so
  the cache key's image-vector leg is constant and the store-generation +
  standard-generation legs are the only invalidation signals (EP-0023 §Image —
  the default generation is cached keyed on the source-store generation)."
  {:rf.image/default?   true
   :rf.image/include-ns []
   :rf.image/inline     []
   :rf.image/requires   #{}})

(defn default-image?
  "True when `image` is the DEFAULT image value — the implicit whole-store
  selector (`:rf.image/default? true`), as opposed to an explicit `rf/image`
  value. Pure."
  [image]
  (boolean (:rf.image/default? image)))

(defn select-for-images
  "Run slice-.3's `image/select-descriptors` for EACH image value in `images`
  against `descriptors` (the source-store pool), returning the concatenated
  selected descriptors (registered glob-matches + each image's inline
  descriptors). Order is image order then selector order; collision validation
  (which makes order irrelevant to the WINNER) runs downstream. Any zero-match
  `:include-ns` pattern fails loud inside `select-descriptors` (slice .3).

  The DEFAULT image (`default-image?`) is the implicit whole-store selector
  (EP-0023 §Default Image Semantics): it selects EVERY descriptor in
  `descriptors` rather than running a glob, with no zero-match fail-loud (an
  empty store is a valid empty default projection). Every other case routes
  through the pure `:include-ns`/inline selector unchanged.

  Pure — a function of the image values and the candidate pool."
  [images descriptors]
  (into []
        (mapcat (fn [image]
                  (if (default-image? image)
                    descriptors
                    (image/select-descriptors image descriptors))))
        images))

;; ---- inline-registration lowering (EP-0023 §Image Fragments, rf2-ffc6s0) ---
;;
;; An inline `:registrations` entry lowers (in the pure `re-frame.image` slice)
;; to a descriptor carrying its raw fn BODY under `:impl` — inert data with NO
;; runnable slots. A registered descriptor, by contrast, carries the RUNNABLE
;; shape `register!` stored (`:handler-fn` + the per-kind discriminators: an
;; event's `:interceptors` wrapper chain, a sub's `:input-kind` / `:input-signals`).
;; So a generation built from inline `:registrations` resolved the descriptor
;; but ran NOTHING — the cascade reads `registrar/handler` (`:handler-fn`) and
;; an event needs `:interceptors`, neither of which an `:impl`-only descriptor
;; carries (rf2-ffc6s0). EP-0023 §Image Fragments is explicit: "Both paths
;; should lower to the same runtime descriptor shape."
;;
;; This step closes that gap: for each SELECTED descriptor that is inline
;; (`:rf.provenance/inline`) and carries a real fn body (`:impl`), call the
;; kind's late-bound lowering (`:image/lower-inline-<kind>`, published by
;; `re-frame.events` / `.subs` / `.fx` / `.cofx`) with the inline `:metadata` +
;; `:impl`, and MERGE the returned runnable slots onto the descriptor. `:impl`,
;; the provenance coordinate, `:kind` / `:id`, and `:metadata` are PRESERVED so
;; replacement-winner coordinates, dedupe, and introspection are unchanged; the
;; merge only ADDS the runnable slots a registered descriptor of the same kind
;; would carry.
;;
;; Late-bound (not a static require) because `image-assembly` is required by
;; `re-frame.live-frame`, which `re-frame.subs` requires — a static require of
;; the lowering producers would cycle. The lowering producers are core spine
;; namespaces loaded at boot, so the hook is always published by the time any
;; image is assembled (a `make-frame` happens at runtime, well after boot); a
;; metadata-only inline entry (no `:impl`) and every registered descriptor pass
;; through untouched.

(defn lower-inline-descriptor
  "Lower ONE selected descriptor into its runnable shape when it is an inline
  descriptor carrying a real fn body (EP-0023 §Image Fragments, rf2-ffc6s0).
  An inline descriptor (`:rf.provenance/inline`) with an `:impl` fn is merged
  with the runnable slots its kind's late-bound lowering produces (the same
  slots `register!` stores for a `reg-*` of that kind) — preserving `:impl`,
  provenance, `:kind` / `:id`, and `:metadata`. A non-inline descriptor (a
  registered or standard one), a metadata-only inline entry (no `:impl`), or a
  kind with no published lowering hook returns UNCHANGED. Pure modulo the
  late-bind lookup."
  [descriptor]
  (if (and (:rf.provenance/inline descriptor)
           (contains? descriptor :impl))
    (if-let [lower (late-bind/get-fn
                     (keyword "image" (str "lower-inline-" (name (:kind descriptor)))))]
      ;; Merge runnable slots UNDER the descriptor's own keys (the descriptor
      ;; wins on any shared key — :impl / provenance / :kind / :id stay put;
      ;; the lowering only contributes :handler-fn + the per-kind runtime slots).
      (merge (lower (:metadata descriptor) (:impl descriptor)) descriptor)
      descriptor)
    descriptor))

(defn lower-inline-descriptors
  "Map `lower-inline-descriptor` over `descriptors` — lowering every selected
  inline descriptor with a real fn body into its runnable shape before
  validation + sealing (EP-0023 §Image Fragments, rf2-ffc6s0). Registered /
  standard / metadata-only descriptors pass through untouched. Order
  preserved (it never decides a collision winner downstream)."
  [descriptors]
  (mapv lower-inline-descriptor descriptors))

;; ===========================================================================
;; Validation — every point is FAIL-LOUD via re-frame.error/throw-error!
;; ===========================================================================

;; ---- unsupported kind (EP-0023 §Image Validation: \"unsupported registration
;;      kind in image path -> image assembly error\") --------------------------

(defn check-supported-kinds!
  "Throw `:rf.error/image-unsupported-kind` for any selected descriptor whose
  `:kind` is not in the closed `re-frame.registrar/kinds` set (Spec 001 registry
  taxonomy). A descriptor reaching assembly with an unknown kind is a malformed
  inline image or a corrupt source-store entry — fail before sealing rather than
  produce a generation the runtime cannot resolve. Returns `descriptors`."
  [image-id descriptors]
  (doseq [d descriptors]
    (let [k (:kind d)]
      (when-not (registrar/valid-kind? k)
        (error/throw-error!
          :rf.error/image-unsupported-kind
          'rf/make-frame
          (str "rf/image assembly: descriptor for id " (pr-str (:id d))
               " has unsupported registration kind " (pr-str k)
               " — not one of the registered registry kinds "
               (pr-str (sort registrar/kinds)) ". Inline image sections must "
               "use a reg-* section key; registered descriptors carry a valid "
               "kind.")
          {:recovery :correct-the-descriptor-kind
           :extra    {:image image-id
                      :kind  k
                      :id    (:id d)
                      :rf.provenance/ns (:rf.provenance/ns d)
                      :coordinate (descriptor-coordinate d)}}))))
  descriptors)

;; ---- replacement winner resolution (EP-0023 §Image Patching And Overrides —
;;      the .5 seam; the detection + the simple winner rule live here) ---------

(defn matches-coordinate?
  "True when a selected `descriptor`'s source coordinate matches a declared
  winner-source coordinate `winner` from a `:replace` / `:replace-standard`
  map. The winner spelling (EP-0023):

    {:ns \"checkout.story.http\"}                  a registered descriptor
    {:image :checkout/story :inline [:reg-fx id]}  an inline descriptor
    {:standard true}                               the framework standard

  Matching is by the descriptor's `descriptor-coordinate`. SLICE .5 SEAM: this
  is the single point richer winner-source matching plugs into; the first
  version is exact coordinate equality. Pure."
  [winner descriptor]
  (= winner (descriptor-coordinate descriptor)))

(defn resolve-replacement-winner
  "Given the `colliding` descriptors for one `[kind id]` and a declared `winner`
  source coordinate, return the single winning descriptor. FAIL-LOUD:

    * a winner that matches ZERO selected descriptors, or MORE THAN ONE, throws
      `:rf.error/image-replacement-winner-unresolved` (a stale winner source, a
      typo, or an ambiguous coordinate — EP-0023 §Image Patching: \"the winner
      source coordinate must identify exactly one selected descriptor\").

  SLICE .5 SEAM: the deep replacement policy (conformance-profile proofs, etc.)
  extends this; the structural guarantee — exactly one winner or an error — is
  fixed here. Pure (modulo the throw)."
  [image-id kind id colliding winner]
  (let [winners (filterv #(matches-coordinate? winner %) colliding)]
    (case (count winners)
      1 (first winners)
      0 (error/throw-error!
          :rf.error/image-replacement-winner-unresolved
          'rf/make-frame
          (str "rf/image assembly: the replacement winner " (pr-str winner)
               " declared for " (pr-str [kind id]) " identifies NO selected "
               "descriptor — a stale winner source, a typo, or a namespace "
               "that is no longer selected. Selected descriptor coordinates "
               "for this id: " (pr-str (mapv descriptor-coordinate colliding)) ".")
          {:recovery :fix-the-replacement-winner-source
           :extra    {:image image-id
                      :kind  kind :id id
                      :winner winner
                      :selected-coordinates (mapv descriptor-coordinate colliding)}})
      (error/throw-error!
        :rf.error/image-replacement-winner-unresolved
        'rf/make-frame
        (str "rf/image assembly: the replacement winner " (pr-str winner)
             " declared for " (pr-str [kind id]) " is AMBIGUOUS — it matches "
             (count winners) " selected descriptors, so it does not name one "
             "exact survivor. Make the winner coordinate identify exactly one.")
        {:recovery :make-the-winner-coordinate-unambiguous
         :extra    {:image image-id
                    :kind  kind :id id
                    :winner winner
                    :match-count (count winners)}}))))

;; ---- standard-replacement policy (EP-0023 §Image Patching: \":replace-standard\"
;;      — the .5 seam for invariant-coupled conformance) ------------------------

(defn standard-replaceable?
  "True when a framework `standard` descriptor MAY be replaced by an image.
  Policy (EP-0023 §Image Patching And Overrides):

    :rf.standard/replaceable? false  -> forbidden (the DEFAULT)
    :rf.standard/replaceable? true   -> allowed

  SLICE .5 SEAM: `:rf.standard/requires-conformance` invariants (e.g. the
  `:rf.interceptor/path` `identical?`-preserving db no-op) are where the deep
  policy plugs a conformance-profile proof in. The first version keeps the
  simpler rule the EP mandates — an invariant-coupled standard (a non-empty
  `:rf.standard/requires-conformance`) is NOT replaceable regardless of the
  flag, until a later spec provides the conformance profile. Pure."
  [standard-descriptor]
  (and (boolean (:rf.standard/replaceable? standard-descriptor))
       (empty? (:rf.standard/requires-conformance standard-descriptor #{}))))

;; ---- the collision validator — THE central \"order never decides\" guarantee
;;      (EP-0023 §Image Composition / §Image Validation) ----------------------

(defn- standard-descriptor?
  [d] (boolean (:standard d)))

(defn resolve-collision
  "Resolve the `colliding` descriptors for one `[kind id]` into the single
  winning descriptor, honouring the image's declared `:replace` /
  `:replace-standard` maps. This is the heart of the \"order never silently
  decides a winner\" guarantee.

  Cases (EP-0023 §Image Composition / §Image Patching And Overrides):

    * After dedupe, exactly ONE descriptor — no collision, it wins.
    * A genuine collision (≥2 distinct registrations for the same `(kind, id)`)
      with NO declared winner -> `:rf.error/image-duplicate-id` (NOT a
      last-write; load order does not decide).
    * A collision involving a framework STANDARD descriptor with no
      `:replace-standard` winner -> `:rf.error/image-standard-replacement-forbidden`
      (a standard must not be shadowed accidentally).
    * A declared `:replace-standard` winner where the standard is NOT
      replaceable per policy -> `:rf.error/image-standard-replacement-forbidden`.
    * A declared `:replace` / `:replace-standard` winner that does not resolve
      to exactly one selected descriptor -> `:rf.error/image-replacement-winner-unresolved`
      (via `resolve-replacement-winner`).

  `replace` / `replace-standard` are `{[kind id] winner-coordinate}` maps from
  the image spec. Returns the winning descriptor. SLICE .5 deepens the winner
  policy; the detection + the fail-loud structure are fixed here."
  [image-id kind id colliding replace replace-standard]
  (let [k+id            [kind id]
        has-standard?   (some standard-descriptor? colliding)
        std-winner      (get replace-standard k+id)
        app-winner      (get replace k+id)]
    (cond
      ;; No real collision: one descriptor (post-dedupe) wins outright.
      (= 1 (count colliding))
      (first colliding)

      ;; A standard is in the collision. It can ONLY be replaced via
      ;; :replace-standard, and only when policy allows.
      has-standard?
      (let [standard (first (filter standard-descriptor? colliding))]
        (cond
          (nil? std-winner)
          (error/throw-error!
            :rf.error/image-standard-replacement-forbidden
            'rf/make-frame
            (str "rf/image assembly: id " (pr-str k+id) " collides with a "
                 "FRAMEWORK STANDARD registration. A standard registration must "
                 "not be shadowed accidentally — declare an explicit "
                 ":replace-standard winner for it, and the standard must be "
                 "marked :rf.standard/replaceable?. Standard registrations are "
                 "non-replaceable by default.")
            {:recovery :declare-replace-standard-or-rename
             :extra    {:image image-id :kind kind :id id
                        :standard-coordinate (descriptor-coordinate standard)
                        :colliding-coordinates (mapv descriptor-coordinate colliding)}})

          (not (standard-replaceable? standard))
          (error/throw-error!
            :rf.error/image-standard-replacement-forbidden
            'rf/make-frame
            (str "rf/image assembly: id " (pr-str k+id) " declares a "
                 ":replace-standard winner, but the framework standard "
                 "registration is NOT replaceable"
                 (if (seq (:rf.standard/requires-conformance standard #{}))
                   (str " — it is invariant-coupled (:rf.standard/requires-conformance "
                        (pr-str (:rf.standard/requires-conformance standard))
                        "), so it is not image-replaceable until a conformance "
                        "profile proves the invariant is preserved")
                   " (:rf.standard/replaceable? is false, the default)")
                 ". Remove the :replace-standard declaration or rename the id.")
            {:recovery :remove-replace-standard-or-rename
             :extra    {:image image-id :kind kind :id id
                        :standard-coordinate (descriptor-coordinate standard)
                        :colliding-coordinates (mapv descriptor-coordinate colliding)
                        :requires-conformance
                        (:rf.standard/requires-conformance standard #{})}})

          :else
          (resolve-replacement-winner image-id kind id colliding std-winner)))

      ;; Application-owned collision with a declared :replace winner.
      (some? app-winner)
      (resolve-replacement-winner image-id kind id colliding app-winner)

      ;; A genuine application-owned collision with NO declared winner —
      ;; the central fail-loud rule: order must NOT decide the survivor.
      :else
      (error/throw-error!
        :rf.error/image-duplicate-id
        'rf/make-frame
        (str "rf/image assembly: id " (pr-str k+id) " is registered by "
             (count colliding) " distinct sources with different implementations "
             "and no declared replacement winner — image assembly will NOT let "
             "load order decide which one a frame runs. Declare a :replace "
             "winner naming the exact survivor, narrow the :include-ns "
             "selector so only one is selected, or rename the duplicate id. "
             "Colliding source coordinates: "
             (pr-str (mapv descriptor-coordinate colliding)) ".")
        {:recovery :declare-replace-winner-or-disambiguate
         :extra    {:image image-id :kind kind :id id
                    :colliding-coordinates (mapv descriptor-coordinate colliding)}}))))

(defn distinct-by-id
  "Group `descriptors` by `[kind id]` and dedupe same-registration descriptors
  within each group (same coordinate + impl — an identical registration selected
  by two overlapping globs is ONE registration, EP-0023 §Image Validation). The
  result `{[kind id] [distinct-descriptor …]}` is the post-dedupe view both the
  resolver projection and the replacement-key collision check read: a group with
  ≥2 entries is a GENUINE `(kind, id)` collision; a group with exactly 1 entry is
  no collision. Pure — order within a group does not decide a winner downstream."
  [descriptors]
  (let [by-id (reduce (fn [acc d] (update acc (descriptor-kind+id d) (fnil conj []) d))
                      {}
                      descriptors)]
    (reduce-kv
      (fn [acc k+id ds]
        (assoc acc k+id
               (reduce (fn [distinct-ds d]
                         (if (some #(same-registration? d %) distinct-ds)
                           distinct-ds
                           (conj distinct-ds d)))
                       []
                       ds)))
      {}
      by-id)))

;; ---- replacement declares a REAL collision only (EP-0023 §Image Validation:
;;      \"replace names a key with no actual collision -> image assembly error\";
;;      §Image Patching: \"a replacement declaration for a non-colliding key is an
;;      image assembly error\") — the .5 winner-policy: a declared winner is for a
;;      REAL collision, NOT a silent order override ---------------------------

(defn check-replacement-keys-collide!
  "Throw `:rf.error/image-replacement-no-collision` for any `:replace` /
  `:replace-standard` key `[kind id]` that does NOT name a GENUINE selected
  collision in `distinct-by-id` (the post-dedupe `{[kind id] [descriptor …]}`
  view). A replacement declaration asserts that a `(kind, id)` collision is
  INTENTIONAL and names the survivor — so it is only legal where a real
  collision exists (≥2 distinct registrations for that `(kind, id)`). Two
  non-collision shapes fail loud here (EP-0023 §Image Patching And Overrides —
  replacement is fail-loud, NOT a silent order override):

    * the key names a `(kind, id)` with EXACTLY ONE selected descriptor — there
      is nothing to replace; the declaration is stale or the winner is being
      used to override selection, not resolve a collision;
    * the key names a `(kind, id)` with ZERO selected descriptors — a typo or a
      no-longer-selected id; there is no collision to resolve at all.

  This is the `.5` winner-policy guarantee paired with
  [[resolve-replacement-winner]]: that fn proves a DECLARED winner names exactly
  one selected descriptor; THIS fn proves the KEY it is declared for is a real
  collision. Together they make `:replace` / `:replace-standard` an exact
  resolution of an intentional collision and never a back-door order override.
  Runs BEFORE `build-resolver` so a bad declaration fails before any winner is
  resolved. `replace` / `replace-standard` are `{[kind id] winner}` maps.
  Returns nil."
  [image-id distinct-by-id replace replace-standard]
  (doseq [[which m] [[:replace replace] [:replace-standard replace-standard]]
          [[kind id] winner] m
          :let [colliding (get distinct-by-id [kind id] [])
                n         (count colliding)]
          :when (< n 2)]
    (error/throw-error!
      :rf.error/image-replacement-no-collision
      'rf/make-frame
      (str "rf/image assembly: the " which " declaration for " (pr-str [kind id])
           " names a key with NO actual collision — it selects "
           (case n 0 "ZERO" "exactly ONE")
           " descriptor, so there is nothing to replace. A replacement winner "
           "resolves a REAL `(kind, id)` collision (two or more distinct "
           "registrations); it is NOT a silent order override. "
           (case n
             0 (str "No selected descriptor has id " (pr-str [kind id])
                    " — fix the typo, select the namespace that registers it, "
                    "or remove the stale declaration.")
             (str "Only one descriptor (" (pr-str (descriptor-coordinate (first colliding)))
                  ") is selected for " (pr-str [kind id])
                  " — remove the replacement declaration (it overrides nothing) "
                  "or select the colliding source you meant to replace.")))
      {:recovery :remove-the-replacement-or-introduce-the-collision
       :extra    {:image image-id
                  :which which
                  :kind  kind :id id
                  :winner winner
                  :selected-count n
                  :selected-coordinates (mapv descriptor-coordinate colliding)}}))
  nil)

(defn build-resolver
  "Project the selected `descriptors` (selected + standard) into the sealed
  `{[kind id] descriptor}` resolver map. Groups by `[kind id]`, dedupes
  same-registration descriptors, then resolves any genuine collision via
  `resolve-collision` (honouring `replace` / `replace-standard`). The result is
  id-disjoint by `(kind, id)` — EVERY collision either deduped, resolved by a
  declared winner, or threw. FAIL-LOUD: no `[kind id]` resolves ambiguously.

  Returns the resolver map. Order of `descriptors` does NOT affect the winner
  (it only affects iteration); the winner is decided by dedupe + declared
  replacement, never by position."
  [image-id descriptors replace replace-standard]
  (reduce-kv
    (fn [resolver [kind id] distinct-ds]
      (assoc resolver [kind id]
             (resolve-collision image-id kind id distinct-ds
                                replace replace-standard)))
    {}
    (distinct-by-id descriptors)))

;; ---- reference validation (EP-0023 §Image Validation: \"event references
;;      missing interceptor / resource references missing scope resolver\") ----

(defn- interceptor-refs
  "The interceptor-reference ids an event/frame descriptor's `:interceptors`
  chain names (EP-0022 by-reference grammar: a bare keyword id, or an `[id arg]`
  parameterized ref). Returns the keyword ids only (a non-keyword head is a
  malformed chain entry, handled at registration, not here). Pure."
  [descriptor]
  (let [chain (or (:interceptors descriptor)
                  (get-in descriptor [:metadata :interceptors]))]
    (->> chain
         (keep (fn [entry]
                 (cond
                   (keyword? entry) entry
                   (and (vector? entry) (keyword? (first entry))) (first entry)
                   :else nil))))))

(defn- application-interceptor-ref?
  "True when an interceptor-reference id is APPLICATION-owned (so the image must
  supply it). Framework-standard refs live under the reserved `:rf.interceptor/*`
  namespace and are provided by the framework standard set / EP-0022 substrate,
  not selected by the image — they are out of scope for this missing-reference
  check. Pure."
  [ref-id]
  (not (and (keyword? ref-id)
            (= "rf.interceptor" (namespace ref-id)))))

(defn- resource-scope-ref
  "The named scope-resolver id a `:resource`-kind descriptor's spec references by
  a `{:from-db <scope-resolver-id>}` derived-scope reference, or nil (Spec 016
  §Resolver references — `{:from-db <id>}`; EP-0016 D3). A resource carries its
  spec at `:rf/resource`; the spec's `:scope` is a `{:from-db <id>}` reference iff
  it is a map with a `:from-db` key. A concrete scope (`:rf.scope/global`, a
  `[:rf.scope/session …]` tuple, a fn resolver, a plain map without `:from-db`)
  has no named-resolver reference to validate. The referenced resolver resolves
  iff `[:resource-scope <id>]` is in the sealed generation. Pure — the
  structural shape only, mirroring `resources.scope-registry/from-db-reference?`
  without core requiring the resources artefact."
  [descriptor]
  (let [scope (:scope (:rf/resource descriptor))]
    (when (and (map? scope) (contains? scope :from-db))
      (:from-db scope))))

(defn check-references!
  "Throw `:rf.error/image-missing-reference` when a selected descriptor names a
  reference the sealed generation does not provide (EP-0023 §Image Validation —
  the missing-reference legs). `resolver` is the sealed `{[kind id] descriptor}`
  map. Two reference legs are checked:

    * an event/frame descriptor's `:interceptors` chain naming an APPLICATION
      interceptor id (\"event references missing interceptor\") — resolves iff
      `[:interceptor ref-id]` is a key; framework-standard `:rf.interceptor/*`
      refs are framework-provided and skipped;
    * a `:resource` descriptor's `{:from-db <scope-resolver-id>}` derived-scope
      reference (\"resource references missing scope resolver\") — resolves iff
      `[:resource-scope <scope-resolver-id>]` is a key (Spec 016 §Resolver
      references / EP-0016 D3). A concrete `:scope` (`:rf.scope/global`, a tuple,
      a fn) names no resolver to validate.

  Both legs share the one `:rf.error/image-missing-reference` fail-loud point and
  carry the same structured diagnostics: the referencing image, `[kind id]`, the
  referencing descriptor's provenance namespace, the unresolved `:missing-reference`
  `[kind id]`, and a repair path. Returns `resolver`."
  [image-id resolver]
  ;; ---- event/frame -> interceptor references --------------------------------
  (doseq [[[kind _id] descriptor] resolver
          :when (contains? #{:event :frame} kind)
          ref-id (interceptor-refs descriptor)
          :when (application-interceptor-ref? ref-id)]
    (when-not (contains? resolver [:interceptor ref-id])
      (error/throw-error!
        :rf.error/image-missing-reference
        'rf/make-frame
        (str "rf/image assembly: " (name kind) " " (pr-str (:id descriptor))
             " references interceptor " (pr-str ref-id)
             " in its :interceptors chain, but no :interceptor registration "
             "with that id is selected into the image. Select the namespace "
             "that registers it, or correct the reference.")
        {:recovery :select-the-missing-registration-or-fix-the-reference
         :extra    {:image image-id
                    :kind  kind
                    :id    (:id descriptor)
                    :rf.provenance/ns (:rf.provenance/ns descriptor)
                    :coordinate (descriptor-coordinate descriptor)
                    :missing-reference [:interceptor ref-id]}})))
  ;; ---- resource -> resource-scope resolver references -----------------------
  (doseq [[[kind _id] descriptor] resolver
          :when (= :resource kind)
          :let  [scope-id (resource-scope-ref descriptor)]
          :when scope-id]
    (when-not (contains? resolver [:resource-scope scope-id])
      (error/throw-error!
        :rf.error/image-missing-reference
        'rf/make-frame
        (str "rf/image assembly: resource " (pr-str (:id descriptor))
             " references scope resolver " (pr-str scope-id)
             " via its {:from-db " (pr-str scope-id) "} :scope, but no "
             ":resource-scope registration with that id is selected into the "
             "image. Select the namespace that reg-resource-scope's it, or "
             "correct the {:from-db …} reference.")
        {:recovery :select-the-missing-registration-or-fix-the-reference
         :extra    {:image image-id
                    :kind  kind
                    :id    (:id descriptor)
                    :rf.provenance/ns (:rf.provenance/ns descriptor)
                    :coordinate (descriptor-coordinate descriptor)
                    :missing-reference [:resource-scope scope-id]}})))
  resolver)

;; ---- capability check (EP-0023 §Public API — the .6 seam) ------------------

(defn check-capabilities!
  "Throw `:rf.error/image-missing-capability` for any `:rf.image/requires`
  capability absent from the frame's `capabilities` map (EP-0023 §Public API:
  \"If any :rf.image/requires capability is absent from the frame's :capabilities,
  frame creation fails before the image generation becomes runnable\"). The
  diagnostic distinguishes a missing CAPABILITY from a missing REGISTRATION.
  Returns `requires`.

  `capabilities` may be nil — a frame that supplies NO `:capabilities` map
  provides nothing, so ANY non-empty `requires` fails loud (a nil map is read as
  `{}`, parallel to EP-0013's `app_value/check-capabilities!` reading an absent
  realm capability map as `#{}`). A no-op only when `requires` is empty.

  This is the fail-loud point the FRAME boundary calls (`live-frame/make-frame`
  supplies the frame's `:capabilities`). `assemble` does NOT call it — pure
  assembly has no frame capability map; the union `:rf.image/requires` set rides
  the generation (`:rf.gen/requires`) for this frame-boundary step."
  [requires capabilities]
  (let [missing (remove #(contains? capabilities %) requires)]
    (when (seq missing)
      (error/throw-error!
        :rf.error/image-missing-capability
        'rf/make-frame
        (str "rf/make-frame: the image requires capabilities the frame does not "
             "supply: " (pr-str (vec (sort missing))) ". The frame's :capabilities "
             "map provides " (pr-str (vec (sort (keys capabilities))))
             ". This is a missing CAPABILITY (a host service the frame must "
             "supply), not a missing registration — supply the capability in "
             ":capabilities, or drop it from the image's :rf.image/requires.")
        {:recovery :supply-the-capability-or-drop-the-requirement
         :extra    {:missing-capabilities (vec (sort missing))
                    :supplied-capabilities (vec (sort (keys capabilities)))}})))
  requires)

;; ===========================================================================
;; The sealed generation + the public assembly entry point
;; ===========================================================================

(defn- image-requires
  "Union the `:rf.image/requires` capability sets across all `images`."
  [images]
  (into #{} (mapcat #(:rf.image/requires % #{})) images))

(defn- merge-replace-maps
  "Merge the `which` (`:replace` / `:replace-standard`) maps across `images`
  into one combined `{[kind id] winner}` map, FAILING LOUD on a cross-image
  CONFLICT: two composed images declaring the SAME `[kind id]` with DIFFERENT
  winner coordinates (EP-0023 §Image Composition — \"Order must not silently
  decide which registration wins\"). A bare `(into {} (mapcat …))` would let the
  later image's winner silently last-merge-win — exactly the order-decides-the-
  survivor footgun the EP forbids at composition. Identical declarations across
  images are IDEMPOTENT (no conflict): two images that name the same survivor for
  the same key agree, so the merge keeps the one shared winner.

  `:rf.error/image-replacement-conflict` is the cross-IMAGE counterpart of
  `:rf.error/image-replacement-winner-unresolved` (which polices a single
  declaration against the selected descriptors): this polices two images
  DISAGREEING about the survivor before resolution even begins. `image-id` is the
  representative image id for diagnostics; the conflict ex-data enumerates the
  exact disagreeing winners. Returns the merged `{[kind id] winner}` map. Pure
  (modulo the throw)."
  [image-id which images]
  (reduce
    (fn [merged image]
      (reduce-kv
        (fn [acc [kind id :as k+id] winner]
          (if-let [existing (find acc k+id)]
            (if (= (val existing) winner)
              acc                                   ;; idempotent: same winner, no conflict
              (error/throw-error!
                :rf.error/image-replacement-conflict
                'rf/make-frame
                (str "rf/image assembly: composed images declare CONFLICTING "
                     (pr-str which) " winners for " (pr-str k+id) " — "
                     (pr-str (val existing)) " and " (pr-str winner)
                     ". Image composition must NOT let order silently decide "
                     "which replacement survives. Two composed images naming "
                     "DIFFERENT winners for the same (kind, id) is a real "
                     "conflict: reconcile them to one agreed winner coordinate, "
                     "or drop the duplicate declaration. (Identical declarations "
                     "across images are fine — they agree.)")
                {:recovery :reconcile-the-conflicting-replacement-winners
                 :extra    {:image  image-id
                            :which  which
                            :kind   kind :id id
                            :winners [(val existing) winner]}}))
            (assoc acc k+id winner)))
        merged
        (get image which {})))
    {}
    images))

(defn- replace-maps
  "Build the two combined `{[kind id] winner}` replacement maps across `images`
  via `merge-replace-maps`, which FAILS LOUD on a cross-image conflict (two
  composed images naming DIFFERENT winners for the same key) rather than
  last-merge-winning — EP-0023 §Image Composition's \"order must not silently
  decide\" applied to the composition's replacement maps. Identical declarations
  across images are idempotent. Returns `[replace replace-standard]`."
  [image-id images]
  [(merge-replace-maps image-id :replace images)
   (merge-replace-maps image-id :replace-standard images)])

(defn- assemble*
  "The PURE assembly pipeline: select → union standards → validate → seal.
  Returns the sealed generation. `images` is already a normalized vector;
  `descriptors` is the candidate pool. This is the function the cache wraps —
  it has no cache awareness, so every cache MISS computes one full generation
  here (the SSR re-seal the cache exists to avoid). Throws on every fail-loud
  condition exactly as before (a throwing input is NOT cached)."
  [images descriptors]
  (let [;; A representative image id for diagnostics (the first that carries
        ;; one); collision/winner errors also name exact coordinates.
        image-id (some :rf.image/id images)
        ;; Lower every selected INLINE descriptor's `:impl` fn body into its
        ;; runnable shape (`:handler-fn` + per-kind runtime slots) so the
        ;; sealed resolver's inline entries route through dispatch / subscribe
        ;; identically to a `:include-ns`-selected registered descriptor
        ;; (EP-0023 §Image Fragments — "the same runtime descriptor shape",
        ;; rf2-ffc6s0). Registered / standard / metadata-only entries are
        ;; unchanged; `:impl` + provenance are preserved for collision /
        ;; replacement / dedupe (all unchanged downstream).
        selected (lower-inline-descriptors (select-for-images images descriptors))
        standard (standard-descriptors)
        all-ds   (into (vec selected) standard)
        [rep rep-std] (replace-maps image-id images)]
    ;; (3) Fail loud on any unsupported descriptor kind before sealing.
    (check-supported-kinds! image-id all-ds)
    ;; (4) Fail loud on any :replace / :replace-standard declaration for a key
    ;;     with no real collision — a replacement is for an intentional
    ;;     collision, never a silent order override. Runs before resolution so
    ;;     a stale/typo'd declaration fails before any winner is matched.
    (check-replacement-keys-collide! image-id (distinct-by-id all-ds) rep rep-std)
    (let [;; (5) Project into the sealed resolver — every collision resolved
          ;;     by a declared winner or thrown (order never decides).
          resolver (build-resolver image-id all-ds rep rep-std)]
      ;; (6) Validate application interceptor references against the sealed set.
      (check-references! image-id resolver)
      ;; (7) Seal.
      {:rf.gen/resolver resolver
       :rf.gen/images   images
       :rf.gen/requires (image-requires images)
       :rf.gen/kinds    (into #{} (map first) (keys resolver))})))

;; ===========================================================================
;; Resolved-generation cache (EP-0023 §Image — "The reference implementation
;; MUST cache resolved generations") + rf2-3sjdmi (cache-key correctness)
;; ===========================================================================
;;
;; Resolved generations are IMMUTABLE and may be physically shared across many
;; frames when the same image inputs resolve to the same descriptor set. SSR is
;; the primary motivation: a request-scoped frame must NOT re-run glob
;; selection + validation + sealing on every request when the inputs are
;; unchanged. The cache turns the repeated `assemble` of an unchanged
;; composition into a single map lookup returning the SAME sealed object.
;;
;; ---- the cache key (EP-0023 minimum key) ----------------------------------
;;
;; The EP's minimum key is:
;;
;;     normalized :images vector
;;     + registration source-store generation
;;     + framework-standard registration generation
;;     + inline descriptor fingerprints
;;     + declared replacement maps
;;
;; The NORMALIZED IMAGE VALUES carry, by value, every one of the
;; image-side legs already:
;;
;;   * `:rf.image/include-ns` — the glob selection input;
;;   * `:rf.image/inline`     — the inline descriptor fingerprints (the inline
;;                              descriptors themselves, lowered + stamped, are
;;                              part of the image value — equal images ⇒ equal
;;                              inline fingerprints);
;;   * `:replace` / `:replace-standard` — the declared replacement maps.
;;
;; So the image vector IS the "normalized :images + inline fingerprints +
;; replacement maps" portion of the key. This is the rf2-3sjdmi correctness
;; point: the key is built from the image INPUTS, NOT from `:rf.gen/resolver`
;; alone — two compositions differing ONLY in `:replace` carry different image
;; values, so they occupy different cache slots and can never cache-collide on
;; a shared resolver shape.
;;
;; The store-side legs are the live store IDENTITY + its monotonic generation,
;; plus the framework-standard generation:
;;
;;   * single-arity (live store): `[source-store/store-identity
;;     source-store/store-generation]` — the active store atom paired with its
;;     monotonic generation. The generation alone is NOT enough: it is keyed
;;     PER store (see source-store), so two DISTINCT stores (a realm-bound
;;     `*source-store*` vs the process-default) can each sit at the same
;;     generation integer over DIFFERENT descriptor pools. The store identity
;;     disambiguates them so a sealed generation assembled from one store is
;;     never handed back for another (rf2-1x2zuc). ANY reg-*/forget-*/clear on
;;     the active store bumps its generation leg.
;;   * the framework-standard generation: `standard-generation` — ANY
;;     register-standard!/forget-standard!/clear bumps it.
;;
;; For the EXPLICIT-POOL arity `(assemble images descriptors)` the registered
;; pool is supplied directly (tests / a pre-snapshotted store), NOT read from
;; the live store — so neither the store identity nor its generation is a
;; faithful invalidation signal there. The honest pool leg in that arity is the
;; descriptor pool itself (a value), so the explicit-pool key folds in the
;; descriptors rather than a store identity/generation: two distinct pools are
;; distinct VALUES and so already distinct keys. The standard generation still
;; applies (standards are always read live).
;;
;; This is a FINER key than the EP minimum (the EP permits a finer
;; descriptor-set fingerprint so unrelated store changes need not invalidate)
;; only in that two distinct stores at the same generation integer never alias
;; — the live-store leg carries the store identity alongside the per-store
;; generation. It is never COARSER: any change to a selected, standard, inline,
;; OR replacement input changes a key leg and forces a re-seal.

(defonce ^{:doc "cache-key → sealed generation. The resolved-generation cache
  (EP-0023 §Image). A plain map atom: an SSR request assembling an unchanged
  composition hits this in O(1) and reuses the SAME sealed object rather than
  re-running selection + validation + sealing."
            :private true}
  generation-cache
  (atom {}))

(defn clear-generation-cache!
  "Drop every cached resolved generation. Test fixtures call this between cases
  so a stale generation never leaks across a test that mutated the store /
  standard registry out from under the generation counters. Idempotent."
  []
  (reset! generation-cache {})
  nil)

(defn cache-size
  "The number of distinct cached resolved generations (introspection / tests)."
  []
  (count @generation-cache))

(defn- cache-key
  "Build the resolved-generation cache key for a normalized image vector under
  a descriptor `pool-leg`. The `pool-leg` is the store-side identity leg:

    * live-store arity — `[store-identity store-generation]`: the active store
      atom (identity, so distinct stores never alias) paired with its monotonic
      per-store generation (so a mutation re-seals). The identity is REQUIRED
      because the generation is keyed per store: two distinct stores at the
      same generation integer would otherwise collide (rf2-1x2zuc);
    * explicit-pool arity — the explicit descriptor pool VALUE itself (distinct
      pools are distinct values, so distinct keys without a separate identity).

  The image vector carries the inline fingerprints + replacement maps by value;
  `pool-leg` + the standard generation are the store-side legs. A vector so it
  hashes + compares as a value (the live-store `pool-leg`'s embedded store atom
  is an opaque, reference-identity key leg — never dereferenced)."
  [images pool-leg]
  [images pool-leg (standard-generation)])

(defn- assemble-cached
  "Look the normalized `images` up in the resolved-generation cache under
  `[images pool-leg (standard-generation)]` (the live-store `pool-leg` is the
  `[store-identity store-generation]` pair, so distinct stores at the same
  generation never alias — rf2-1x2zuc); on a MISS compute the sealed
  generation via `assemble*` (against `descriptors`), cache it, and return it.
  A cache HIT returns the SAME sealed object — this is the SSR no-re-seal
  guarantee. A throwing `assemble*` (a fail-loud validation) is NOT cached: the
  exception propagates and the slot stays empty, so a corrected input on the
  next call recomputes cleanly."
  [images descriptors pool-leg]
  (let [k (cache-key images pool-leg)]
    (if-let [hit (find @generation-cache k)]
      (val hit)
      (let [gen (assemble* images descriptors)]
        ;; Cache only a successfully sealed generation (assemble* threw on a
        ;; fail-loud input, so we never reach here for one). swap! is enough —
        ;; a benign double-compute under contention seals to an equal value.
        (swap! generation-cache assoc k gen)
        gen))))

;; `assemble` routes its empty-`:images` case to `assemble-default` (the
;; dedicated default-projection entry, defined just below). Forward-declared so
;; the narrative order — the general `assemble`, then its default sibling — reads
;; top-down.
(declare assemble-default)

(defn assemble
  "Resolve `images` (a seq of normalized `rf/image` values) into a SEALED,
  VALIDATED image generation (EP-0023 §Image Validation). The integration of
  slices .2 (source store) and .3 (selector):

    1. select registered descriptors per image (by `:rf.provenance/ns` against
       the source store) + each image's inline descriptors;
    2. add the framework standard registrations;
    3. validate unsupported kinds;
    4. validate every declared `:replace` / `:replace-standard` key names a REAL
       collision (a declaration for a non-colliding key FAILS LOUD — replacement
       resolves an intentional collision, it is never a silent order override);
    5. project into the sealed `[kind id]` resolver, resolving every collision
       via declared `:replace` / `:replace-standard` winners — a genuine
       collision with no declared winner FAILS LOUD (order never decides);
    6. validate application interceptor references against the sealed resolver;
    7. seal into an immutable generation value.

  Capability checking (step against the frame's `:capabilities`) is NOT done
  here — pure assembly has no frame; it is the slice-.6 / slice-.7 frame-boundary
  step. The union image-requires set is carried on the generation for that step.

  ## Resolved-generation caching (EP-0023 §Image — MUST cache)

  Sealed generations are immutable and may be physically shared across frames
  when the same inputs resolve to the same descriptor set. `assemble` caches by
  the EP minimum key — normalized image vector (which carries inline
  fingerprints + the declared `:replace` / `:replace-standard` maps by value) +
  the registration source-store generation + the framework-standard generation
  — so a repeated assembly of an UNCHANGED composition returns the SAME sealed
  object without re-running selection + validation + sealing. This is the SSR
  fast path. ANY change to a selected, standard, inline, or replacement input
  bumps a key leg and forces a re-seal; two compositions differing only in
  `:replace` never cache-collide (rf2-3sjdmi).

  Two arities:
    (assemble images)            — select against the live source store; the
                                   key's pool leg is the live source-store
                                   IDENTITY + its generation (the identity stops
                                   two distinct stores at the same generation
                                   from aliasing — rf2-1x2zuc).
    (assemble images descriptors)— select against an explicit descriptor pool
                                   (tests / harnesses / a pre-snapshotted
                                   store); the key's pool leg is the descriptor
                                   pool value itself (distinct pools are
                                   distinct values, so the live store
                                   identity/generation does not apply).

  `images` is ALWAYS a seq/vector of image values — never a single image map
  (the EP is emphatic that `:images` is vector-only; `live-frame/validate-images!`
  enforces vector-only at the make-frame boundary, and a normalized image value
  IS itself a map, so a `map?`-wrapping branch here would be ambiguous and
  contradict the EP). An EMPTY (or nil) `images` is the DEFAULT-image case — it
  projects `default-image`, the implicit selector over the WHOLE source store
  (EP-0023 §Default Image Semantics; see `assemble-default`). Returns the sealed
  generation:

    {:rf.gen/resolver {[kind id] descriptor …}
     :rf.gen/images   [<image value> …]
     :rf.gen/requires #{:rf.capability/* …}
     :rf.gen/kinds    #{kind …}}"
  ([images]
   (let [images (vec images)]
     (if (empty? images)
       ;; No explicit image ⇒ the DEFAULT image projection over the live store.
       (assemble-default)
       ;; Live-store arity: the pool leg is the source-store generation. Read it
       ;; BEFORE selecting so the cached object is keyed to the store snapshot it
       ;; was assembled from.
       ;;
       ;; SINGLE-THREADED-REGISTRATION assumption (EP-0023 co-fix F3): the
       ;; descriptor pool and the generation are read in two separate steps with
       ;; no lock between them. This is sound only because registration mutations
       ;; (`reg-*` / `forget-*` / `clear-*`) are single-threaded relative to
       ;; assembly — they happen at load/hot-reload time, not concurrently with a
       ;; live `assemble`. A concurrent mutation between these two reads could key
       ;; a freshly-assembled pool to a stale generation; the framework does not
       ;; defend against that because registration is not a concurrent surface.
       ;;
       ;; The pool leg pairs the active store IDENTITY with its generation: the
       ;; generation is keyed per store, so the identity is what stops two
       ;; distinct stores at the same generation from aliasing one sealed
       ;; generation (rf2-1x2zuc).
       (assemble-cached images
                        (source-store-descriptors)
                        [(source-store/store-identity)
                         (source-store/store-generation)]))))
  ([images descriptors]
   (let [images (vec images)]
     (if (empty? images)
       ;; No explicit image ⇒ the DEFAULT image projection over the supplied
       ;; pool (the deterministic explicit-pool form for tests/harnesses).
       (assemble-default descriptors)
       ;; Explicit-pool arity: the pool leg is the descriptor pool value itself —
       ;; the live store generation does not describe a supplied pool.
       (assemble-cached images descriptors descriptors)))))

(defn assemble-default
  "Resolve the DEFAULT image — the implicit selector over the WHOLE registration
  source store + the framework standards — into a SEALED, VALIDATED image
  generation (EP-0023 §Default Image Semantics / Bead Plan item 6). The default
  path projects ALL default-source `reg-*` descriptors into the default image
  generation, FAILING LOUD if that projection contains same-kind same-id
  collisions.

  This is the no-explicit-`:images` frame path: `reg-*` mutates the default
  source store, and the default image is the implicit selector over that store.
  It runs through the SAME `assemble*` pipeline + the SAME resolved-generation
  cache as an explicit image — selection (the whole pool, via the `default-image`
  marker), the framework-standard union, collision validation, reference checks,
  and sealing are byte-identical. So a cross-namespace same-`(kind, id)`
  collision in the default projection FAILS LOUD with
  `:rf.error/image-duplicate-id` (via `resolve-collision`) exactly as an explicit
  image's collision does — load order NEVER silently decides the survivor on the
  default path; there is no last-write-wins. A product that intentionally wants
  same ids with different meanings must use explicit images with disjoint
  selectors (EP-0023 §Default Image Semantics).

  Two arities mirror `assemble`:
    (assemble-default)            — select against the LIVE source store; the
                                   cache key's pool leg is the source-store
                                   IDENTITY + its generation, so the default
                                   generation is cached, invalidated the instant
                                   any `reg-*` / `forget-*` / `clear-*` bumps it,
                                   and never aliased across two distinct stores
                                   at the same generation (rf2-1x2zuc; EP-0023
                                   §Image — the default generation is cached
                                   keyed on the source-store generation).
    (assemble-default descriptors)— select against an explicit descriptor pool
                                   (tests / harnesses / a pre-snapshotted store);
                                   the pool leg is the descriptor pool value
                                   itself (the deterministic test form).

  The default image carries no `:include-ns` (so no zero-match fail-loud — an
  empty store is a valid empty default projection resolving only the framework
  standards) and no inline / replacement declarations. Returns the sealed
  generation (the same shape `assemble` returns)."
  ([]
   ;; Live-store default: pair the active store identity with its generation so
   ;; the default generation of two distinct stores at the same generation never
   ;; aliases (rf2-1x2zuc).
   (assemble-cached [default-image]
                    (source-store-descriptors)
                    [(source-store/store-identity)
                     (source-store/store-generation)]))
  ([descriptors]
   (assemble-cached [default-image] descriptors descriptors)))

;; ===========================================================================
;; The resolver READ API — the surface slices .7 / .8 (frame loading,
;; frame-derived live resolution) build on
;; ===========================================================================

(defn resolve-descriptor
  "Resolve `(kind, id)` against a sealed `generation`, returning the single
  descriptor or nil. The runtime registration-resolution read of a frame's
  resolved image generation (EP-0023 §Specification — \"target frame -> resolved
  image generation -> registration resolution\"). Pure."
  [generation kind id]
  (get (:rf.gen/resolver generation) [kind id]))

(defn generation-kinds
  "The set of kinds present in a sealed `generation`'s resolver. Tools use this."
  [generation]
  (:rf.gen/kinds generation))

(defn generation-ids
  "The ids registered under `kind` in a sealed `generation`. Pure."
  [generation kind]
  (into #{}
        (comp (filter #(= kind (first %))) (map second))
        (keys (:rf.gen/resolver generation))))
