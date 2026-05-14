(ns re-frame.story.ui.state.filters
  "Pure data → data helpers for the sidebar's faceted tag-filter UI and
  story-grouped variant listing. Split from
  `re-frame.story.ui.state` per rf2-gcpon (leaf-size ceiling rf2-zkca8).

  ## What lives here

  - `group-tags-by-axis`        — bucket tags into per-axis vectors.
  - `axis-display-order`        — canonical chip-row order.
  - `ordered-axes`              — render-order for an axis-bucket map.
  - `partition-tag-filter-by-axis` — split the active filter set by axis.
  - `variant-tag-match?`        — faceted predicate (AND-across / OR-within).
  - `filter-variants`           — apply the predicate over a variant map.
  - `group-variants-by-story`   — build the sidebar tree.

  The faceted-filter contract (rf2-7ncf9 SB9 parity): AND across axes,
  OR within an axis. Documented per-fn below."
  (:require [re-frame.story.predicates :as pred]))

(defn group-tags-by-axis
  "Pure data → data: split a seq of tags into `{axis-kw → [tag …]}`
  using `tag->axis` (a `{tag-id → axis-kw}` map). Each per-axis
  vector is sorted by `name` so the chip layout is stable across
  re-renders. Tags missing from `tag->axis` land under
  `:re-frame.story.registrar/no-axis` (the same sentinel
  `registrar/tag->axis` returns for un-axis-grouped tags).

  rf2-7ncf9 — faceted tag-filter UI. The sidebar's filter row walks
  this result to render one labelled chip row per axis."
  [tags tag->axis]
  (->> tags
       (reduce (fn [acc t]
                 (let [axis (get tag->axis t :re-frame.story.registrar/no-axis)]
                   (update acc axis (fnil conj []) t)))
               {})
       (reduce-kv (fn [acc axis ts]
                    (assoc acc axis (vec (sort-by name ts))))
                  {})))

(def axis-display-order
  "Pure data → data: the canonical chip-row order. Mirrors spec/001
  §reg-tag's documented facet axes — `:status` first, then `:role`,
  `:team`, `:feature`, then any project-defined axes (alphabetical),
  with the no-axis bucket last."
  [:status :role :team :feature])

(defn ordered-axes
  "Pure data → data: return the axes present in `by-axis` (a
  `{axis-kw → [tag …]}` map) in stable display order. Canonical axes
  appear first in `axis-display-order`; project-defined axes follow
  alphabetically; the no-axis sentinel is always last."
  [by-axis]
  (let [present       (set (keys by-axis))
        canonical     (filter present axis-display-order)
        extras        (->> present
                           (remove (set axis-display-order))
                           (remove #{:re-frame.story.registrar/no-axis})
                           (sort-by name))
        trailing      (when (contains? present
                                       :re-frame.story.registrar/no-axis)
                        [:re-frame.story.registrar/no-axis])]
    (vec (concat canonical extras trailing))))

(defn partition-tag-filter-by-axis
  "Pure data → data: split a `tag-filter` set into `{axis-kw → #{tag …}}`
  using `tag->axis` (a `{tag-id → axis-kw}` map; see
  `registrar/tag->axis-index`). Tags missing from `tag->axis` are
  bucketed under `:re-frame.story.registrar/no-axis` — the same
  sentinel `registrar/tag->axis` returns for un-axis-grouped tags. The
  faceted-filter predicate (`variant-tag-match?`) consumes the result.

  Faceted-filter semantics (rf2-7ncf9, SB9 parity): AND across axes,
  OR within an axis. Partitioning is the first half — the predicate
  enforces the AND-across rule by requiring every per-axis subset to
  intersect the variant's `:tags`."
  [tag-filter tag->axis]
  (reduce (fn [acc tag]
            (let [axis (get tag->axis tag :re-frame.story.registrar/no-axis)]
              (update acc axis (fnil conj #{}) tag)))
          {}
          tag-filter))

(defn variant-tag-match?
  "True iff `variant-body`'s `:tags` set satisfies the `tag-filter`.

  Faceted filter semantics (rf2-7ncf9 SB9 parity):

  - **OR within an axis** — if the filter activates `:status/alpha`
    AND `:status/beta`, a variant tagged with either passes that
    axis.
  - **AND across axes** — activating `:status/stable` AND `:role/design`
    narrows to variants carrying BOTH a `:status`-axis match AND a
    `:role`-axis match.
  - Tags without an `:axis` slot form a synthetic
    `:re-frame.story.registrar/no-axis` pseudo-axis with the same
    OR-within rule.
  - Empty filter → every variant passes (Stage-4 behaviour preserved).

  The pure 2-arity (without `tag->axis`) keeps the legacy OR-only
  semantics so existing callers that don't have an axis-index handy
  (tests, downstream tools) keep working. The 3-arity is the
  facet-aware form the sidebar uses.

  Stage 4 ignores the `:!`-prefix removal syntax — that's resolved at
  registration time in `re-frame.story.registrar` via
  `validate-tag-membership!`."
  ([variant-body tag-filter]
   (or (empty? tag-filter)
       (let [tset (or (:tags variant-body) #{})]
         (some #(contains? tset %) tag-filter))))
  ([variant-body tag-filter tag->axis]
   (or (empty? tag-filter)
       (let [tset       (or (:tags variant-body) #{})
             per-axis   (partition-tag-filter-by-axis tag-filter tag->axis)]
         ;; Every axis bucket must intersect the variant's tag set
         ;; (AND across axes; OR within an axis).
         (every? (fn [[_ active-on-axis]]
                   (some #(contains? tset %) active-on-axis))
                 per-axis)))))

(defn filter-variants
  "Return the subset of `id->body` whose `:tags` match the filter.
  Pure data → data; JVM-testable.

  The 2-arity preserves legacy OR-across-tags semantics. The 3-arity
  takes a `tag->axis` map (see `registrar/tag->axis-index`) and
  applies the faceted AND-across / OR-within rule (rf2-7ncf9). The
  sidebar calls the 3-arity with a registrar-derived axis-index; the
  legacy 2-arity stays for the bare-data callers that don't carry
  registrar context."
  ([id->body tag-filter]
   (into {}
         (filter (fn [[_ body]] (variant-tag-match? body tag-filter)))
         id->body))
  ([id->body tag-filter tag->axis]
   (into {}
         (filter (fn [[_ body]] (variant-tag-match? body tag-filter tag->axis)))
         id->body)))

(defn group-variants-by-story
  "Build a sorted vector of `{:story-id ... :variants [...]}` entries
  from the variants map. Variants whose parent story is unregistered
  still appear under their derived parent id — the sidebar surfaces them
  with a 'no story' indicator.

  Sorted by story id (alphabetic on the keyword name) so the sidebar is
  stable across re-renders."
  [id->body]
  (let [by-story (group-by (comp pred/parent-story-id key) id->body)]
    (->> by-story
         (map (fn [[story-id variants]]
                {:story-id story-id
                 :variants (vec (sort-by key variants))}))
         (sort-by (fn [{:keys [story-id]}]
                    (if story-id (str story-id) "")))
         vec)))
