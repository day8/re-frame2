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
      event references a missing interceptor / resource a missing scope
        -> :rf.error/image-missing-reference
      a :replace / :replace-standard declared for a key with no actual
        collision (zero or exactly one selected descriptor — stale / typo /
        an order override, NOT a collision resolution)
        -> :rf.error/image-replacement-no-collision
      a :replace / :replace-standard winner that resolves to zero or many
        selected descriptors (a stale winner source, a typo, an ambiguous coord)
        -> :rf.error/image-replacement-winner-unresolved
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

(defn register-standard!
  "Contribute a framework-standard descriptor for `[kind id]`. `descriptor` is
  stamped `:standard true`; its replacement policy comes from
  `:rf.standard/replaceable?` (default false) and `:rf.standard/requires-conformance`
  (default `#{}`). Idempotent per `[kind id]` — re-registering replaces the slot
  (the standard owner's hot-reload path). Returns the stamped descriptor."
  [kind id descriptor]
  (let [desc (-> descriptor
                 (assoc :kind kind :id id :standard true)
                 (update :rf.standard/replaceable? boolean)
                 (update :rf.standard/requires-conformance #(set (or % #{}))))]
    (swap! standard-registry assoc [kind id] desc)
    desc))

(defn forget-standard!
  "Remove the framework-standard descriptor for `[kind id]`. Mirror of
  `register-standard!` for the standard owner's teardown path."
  [kind id]
  (swap! standard-registry dissoc [kind id])
  nil)

(defn clear-standards!
  "Reset the framework-standard registry. Test fixtures use this between cases."
  []
  (reset! standard-registry {})
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

(defn select-for-images
  "Run slice-.3's `image/select-descriptors` for EACH image value in `images`
  against `descriptors` (the source-store pool), returning the concatenated
  selected descriptors (registered glob-matches + each image's inline
  descriptors). Order is image order then selector order; collision validation
  (which makes order irrelevant to the WINNER) runs downstream. Any zero-match
  `:include-ns` pattern fails loud inside `select-descriptors` (slice .3).
  Pure — a function of the image values and the candidate pool."
  [images descriptors]
  (into []
        (mapcat #(image/select-descriptors % descriptors))
        images))

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
                        :standard-coordinate (descriptor-coordinate standard)}})

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

(defn check-references!
  "Throw `:rf.error/image-missing-reference` when a selected event/frame
  descriptor's `:interceptors` chain names an APPLICATION interceptor id that the
  resolved generation does not provide (EP-0023 §Image Validation: \"event
  references missing interceptor -> image assembly error\"). `resolver` is the
  sealed `{[kind id] descriptor}` map; an interceptor ref resolves iff
  `[:interceptor ref-id]` is a key. Framework-standard `:rf.interceptor/*` refs
  are not image-supplied and are skipped. Returns `resolver`.

  This is the assembly-time structural reference check; resource→scope-resolver
  references are the resources artefact's deeper check (it owns the
  `:resource-scope` kind) and plug into this same fail-loud point when that
  artefact wires assembly — the structure is here."
  [image-id resolver]
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
                    :missing-reference [:interceptor ref-id]}})))
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

(defn- replace-maps
  "Merge the `:replace` / `:replace-standard` maps across `images` into two
  combined `{[kind id] winner}` maps. EP v1 does not specify cross-image
  replacement-map conflict resolution beyond last-merge; replacement winners are
  validated against the actual selected collisions downstream, so a stale entry
  fails there. Returns `[replace replace-standard]`."
  [images]
  [(into {} (mapcat #(:replace % {})) images)
   (into {} (mapcat #(:replace-standard % {})) images)])

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

  Two arities:
    (assemble images)            — select against the live source store.
    (assemble images descriptors)— select against an explicit descriptor pool
                                   (tests / harnesses / a pre-snapshotted store).

  `images` may be a single image value (wrapped) or a seq. Returns the sealed
  generation:

    {:rf.gen/resolver {[kind id] descriptor …}
     :rf.gen/images   [<image value> …]
     :rf.gen/requires #{:rf.capability/* …}
     :rf.gen/kinds    #{kind …}}"
  ([images]
   (assemble images (source-store-descriptors)))
  ([images descriptors]
   (let [images   (if (map? images) [images] (vec images))
         ;; A representative image id for diagnostics (the first that carries
         ;; one); collision/winner errors also name exact coordinates.
         image-id (some :rf.image/id images)
         selected (select-for-images images descriptors)
         standard (standard-descriptors)
         all-ds   (into (vec selected) standard)
         [rep rep-std] (replace-maps images)]
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
        :rf.gen/kinds    (into #{} (map first) (keys resolver))}))))

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
