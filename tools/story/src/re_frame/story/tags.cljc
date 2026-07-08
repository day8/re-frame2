(ns re-frame.story.tags
  "Effective-tag resolution — the SINGLE authority that turns a variant's
  AUTHORED `:tags` into the EFFECTIVE tag set every consumer reads.

  Three responsibilities, applied in order (spec/001 §`!`-prefix removal
  syntax + §Authoring inheritance):

  1. **Inheritance.** A variant's tags come from its `:extends` chain
     (UNION, additive — root→child) whenever it or any ancestor declares
     `:tags`; otherwise they FALL BACK to the parent story's `:tags`. Story
     tags are a DEFAULT, not an addition — a variant that declares its own
     tags does not union with its story (`002-Runtime.md` §Authoring
     inheritance / the `own-tags` cascade rule).
  2. **Marker removal.** A `:!x` removal-marker keyword is dropped from the
     output — it is authoring syntax, never a visible / queryable tag.
  3. **Base subtraction.** Each `:!x` marker subtracts its base `:x` from the
     set, so `:!dev` cancels an inherited / unioned `:dev`.

  So `#{:dev :!dev :docs}` resolves to `#{:docs}`, and `#{:!dev}` to `#{}`.

  This is the ONE resolver plan compilation, tag queries
  (`variants-with-tags`), docs / sidebar chips, the tag filter, and
  testable-variant selection funnel through — so a `:!x` marker can never
  leak as a chip, a query hit, or a filter facet, and inherited tags read
  identically across every surface. Before this ns, plan compilation unioned
  the RAW tags (leaking `:!x` and matching an inherited `:x` the author had
  removed), while the UI surfaces read the raw child `:tags` with no
  inheritance at all (three divergent behaviours).

  Pure data → data. Lookups are INJECTED (a fn `id → body` OR a `{id → body}`
  map), so this ns stays a require-leaf — it never `:require`s the registrar,
  and any Story ns can consume it without a cycle. Live callers pass the
  registrar side-table; pure tests pass an explicit map."
  (:require [re-frame.story.predicates :as pred]))

;; ---- `:!x` removal markers -----------------------------------------------

(defn removal-marker?
  "True iff `tag` is a `:!x` removal-marker keyword — a keyword whose NAME
  begins with `!` (namespace ignored, so `:a/!b` is a marker too). Mirrors
  the membership-strip check in `re-frame.story.registrar`."
  [tag]
  (and (keyword? tag)
       (let [n (name tag)]
         (and (pos? (count n)) (= \! (first n))))))

(defn marker-base
  "The base tag a `:!x` removal marker cancels — `:!dev` → `:dev`, `:a/!b`
  → `:a/b` (namespace preserved). Returns a non-marker unchanged."
  [tag]
  (if (removal-marker? tag)
    (keyword (namespace tag) (subs (name tag) 1))
    tag))

(defn resolve-markers
  "Given a RAW tag set already unioned across inheritance layers, return the
  EFFECTIVE set: drop every `:!x` marker (responsibility 2) AND subtract each
  marker's base `:x` (responsibility 3). The shared marker-resolution core.
  A set with no markers is returned unchanged. Pure data → data."
  [raw-tags]
  (let [raw     (set raw-tags)
        markers (into #{} (filter removal-marker?) raw)]
    (if (empty? markers)
      raw
      (let [bases (into #{} (map marker-base) markers)]
        (into #{}
              (remove (fn [t] (or (removal-marker? t) (contains? bases t))))
              raw)))))

;; ---- inheritance ---------------------------------------------------------

(def ^:dynamic *max-extends-depth*
  "Bound on the `:extends` chain walk for tag resolution, mirroring
  `re-frame.story.plan/*max-extends-depth*`. The resolver is best-effort —
  the plan compiler is the authority that RAISES on a broken chain — so
  hitting the bound (or a missing parent / a cycle) simply stops the walk."
  32)

(defn- as-fn
  "Coerce a `id → body` lookup into a 1-arg fn: a fn is used as-is; a map is
  read via `get`; nil yields a constant-nil lookup (the layer contributes
  nothing)."
  [lookup]
  (cond
    (nil? lookup) (constantly nil)
    (map? lookup) #(get lookup %)
    :else         lookup))

(defn extends-chain-tags
  "Union the AUTHORED `:tags` across `variant-id`'s `:extends` chain
  (child→root), via `variant-lookup` (a fn or a `{id → raw-body}` map).
  Defensive: stops on a missing parent, a re-visited id (cycle), or the
  depth bound — it NEVER raises. Pure over the lookup."
  [variant-id variant-lookup]
  (let [vl (as-fn variant-lookup)]
    (loop [tags #{} id variant-id visited #{} depth 0]
      (let [body (vl id)]
        (if (or (nil? body) (contains? visited id) (>= depth *max-extends-depth*))
          tags
          (let [tags'  (into tags (:tags body))
                parent (:extends body)]
            (if (nil? parent)
              tags'
              (recur tags' parent (conj visited id) (inc depth)))))))))

(defn raw-inherited-tags
  "The RAW (pre-marker-resolution) inherited tag set for `variant-id`: the
  `:extends`-chain union when it (or any ancestor) declares tags, else the
  parent story's `:tags` (fallback, NOT a union — responsibility 1). Pure
  over the injected `lookups` (`{:variant <id→body> :story <id→body>}`)."
  [variant-id {:keys [variant story]}]
  (let [chain (extends-chain-tags variant-id variant)]
    (if (seq chain)
      chain
      (set (:tags ((as-fn story) (pred/parent-story-id variant-id)))))))

(defn effective-tags
  "The EFFECTIVE tag set for `variant-id` — inheritance (extends union /
  story fallback) then `:!x` marker resolution. The single authority every
  tag consumer reads.

  `lookups` is `{:variant <id→body> :story <id→body>}`, each a fn OR a
  `{id → body}` map. Both are OPTIONAL — an absent lookup contributes no
  layer, so a caller with only the variant side-table still resolves the
  extends chain + markers. Pure data → data."
  ([variant-id] (effective-tags variant-id nil))
  ([variant-id lookups]
   (resolve-markers (raw-inherited-tags variant-id lookups))))

(defn resolve-body-tags
  "Return `id->body` with each variant body's `:tags` replaced by its
  EFFECTIVE set (`effective-tags`). The single projection every UI consumer
  of a variant registry reads through — the sidebar tree, tag chips, the tag
  filter, testable selection, and search all see the SAME resolved tags, so a
  `:!x` marker never reaches a chip or a facet and inherited tags surface
  everywhere.

  `lookups` (optional) is `{:variant <id→body> :story <id→body>}`; the
  `:variant` layer defaults to `id->body` itself (the `:extends` chain
  resolves within the passed map). Pure data → data."
  ([id->body] (resolve-body-tags id->body nil))
  ([id->body {:keys [variant story]}]
   (let [vl (or variant id->body)]
     (persistent!
       (reduce-kv (fn [acc id body]
                    (assoc! acc id
                            (assoc body :tags
                                   (effective-tags id {:variant vl :story story}))))
                  (transient {})
                  id->body)))))
