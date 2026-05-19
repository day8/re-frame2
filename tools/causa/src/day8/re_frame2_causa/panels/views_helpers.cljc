(ns day8.re-frame2-causa.panels.views-helpers
  "Pure-data helpers for Causa's Views panel (rf2-21ob3).

  Per `tools/causa/spec/012-Views.md` (Views replaces the legacy Subs
  tab; subs nest under each view that consumed them). This namespace
  owns the cascade-projection algebra:

    - Three-group classification (mounted / re-rendered / unmounted)
      derived from each cascade's `:renders` vector + the prior
      cascade's `:renders` for the unmounted-set difference.
    - Re-rendered group sub-grouping by triggering sub (per
      `012-Views.md` §Per-row content (Re-rendered) — two-column
      `Rerendered because` layout; spec §R3-D). The data slot stays
      named `:invalidated-by` for shape stability; the UI text is
      developer-framed.
    - Grid-explosion clustering — collapse `≥ cluster-threshold`
      renders sharing `(view-id, triggered-by)` into one aggregate row
      with `× N · total ms · avg µs` stats (spec §Grid-explosion
      clustering; default threshold 50).
    - Per-component sub list — for each render, the `:sub-runs`
      entries that recomputed this cascade and the focused view
      `:triggered-by` reference (spec §Sub-status legibility).

  All logic is pure-data + JVM-portable. CLJS-only concerns
  (cljs-devtools renderer, React-fiber data) live in the `views.cljs`
  view code. Per spec §Data sources the panel observes data the
  framework already records; this helper never reads from a `defonce`
  or trace bus — every input is passed in explicitly.

  ## Mount/unmount derivation under v1 instrumentation

  Spec §Data sources documents an `:owning-frame` render-tracker tag
  + per-render mount/unmount-reason as the canonical signals. The
  current `:rf/epoch-record` `:renders` shape (per
  `spec/Spec-Schemas.md` §`:rf/epoch-record`) ships only
  `[<render-key> <triggered-by> <elapsed-ms>]` — no explicit
  mount/unmount lifecycle markers. The v1 projection therefore
  derives the three groups structurally:

    - **Mounted**: `[view-id instance-token]` appears in this
      cascade's `:renders` AND did NOT appear in the prior cascade's
      `:renders` AND `:triggered-by` is nil (sub-driven re-renders
      with `:triggered-by` set go to the Re-rendered group; first-
      mount renders have a nil triggered-by per the runtime
      contract).
    - **Re-rendered**: appeared in this cascade AND in the prior
      cascade (instance-token match), regardless of `:triggered-by`.
    - **Unmounted**: appeared in the prior cascade AND did NOT
      appear in this cascade. The row carries the prior cascade's
      `:elapsed-ms` and `:triggered-by` for context.

  When P17 (render-tracker `:owning-frame` tagging) and a future
  lifecycle-marker bead land, the projection switches to read those
  signals directly; the three-group output shape stays unchanged so
  the view code is forward-compatible.

  ## Frame isolation

  Per spec §Isolation invariant (I3) the Views panel's render
  projection is filtered to the selected frame ONLY. The current
  `:renders` entries carry no `:owning-frame` tag (P17 pending), so
  v1 the projection takes a `frame-id` arg and is a no-op filter
  until the tag lands. The `:rf.causa/views-data` sub passes the
  spine's selected frame; when P17 lands the helper's `filter-frame`
  branch will activate without changing the sub graph."
  (:require [clojure.set :as set]))

;; ---- gutter glyphs + group order ----------------------------------------

(def group-glyph
  "Per spec §Group gutters. Mounted is a filled diamond; Re-rendered
  uses a half-circle (the two-column 'Rerendered because' card carries
  the glyph in its header); Unmounted is an open diamond + struck-
  through row text."
  {:mounted    "◆"
   :rendered   "◐"
   :unmounted  "◇"})

(def group-order
  "Per spec §Three-group layout — fixed top-to-bottom order. The view
  code consumes this vector directly so a future addition (e.g. a
  fourth group for parent-forced renders) is one append away."
  [:mounted :rendered :unmounted])

(def default-cluster-threshold
  "Per spec §Grid-explosion clustering — ≥ 50 renders sharing
  `(view-id, triggered-by)` collapse into one aggregate row. Below
  threshold each render lists individually. Configurable via
  Settings → Buffer → `:views/cluster-threshold`."
  50)

;; ---- view-id rendering --------------------------------------------------

(defn render-key->view-id
  "Extract the view-id (first slot) from a `:render-key` tuple. Per
  Spec-Schemas the tuple is `[<view-id-or-:rf.view/anonymous>
  <instance-token>]`; anonymous renders all collapse to a single
  bucket under this view-id so clustering still groups them
  meaningfully (the per-instance-token discriminator stays available
  for drilldown)."
  [render-key]
  (when (and (vector? render-key) (>= (count render-key) 1))
    (first render-key)))

(defn render-key->instance-token
  "Extract the instance-token (second slot) from a `:render-key` tuple.
  Returns nil for malformed keys (instance-token absent / single-
  element vector)."
  [render-key]
  (when (and (vector? render-key) (>= (count render-key) 2))
    (second render-key)))

(defn format-view-id
  "Pretty-print a view-id for the row label. Keyword view-ids render
  with their leading colon dropped (`:cart/order-row` →
  `cart/order-row`); the `:rf.view/anonymous` sentinel renders as
  `<anonymous>` so the user sees a placeholder rather than a
  framework keyword."
  [view-id]
  (cond
    (= :rf.view/anonymous view-id) "<anonymous>"
    (keyword? view-id) (subs (str view-id) 1)
    :else (pr-str view-id)))

;; ---- three-group classification ----------------------------------------

(defn- index-renders
  "Build `{instance-token <render>}` so the cross-cascade diff can do
  O(1) lookups. Anonymous renders share the same view-id slot; the
  instance-token is the per-mount discriminator (per Spec-Schemas
  §`:rf/epoch-record` `:renders` slot 2)."
  [renders]
  (reduce (fn [acc r]
            (let [tok (render-key->instance-token (:render-key r))]
              (cond-> acc tok (assoc tok r))))
          {}
          renders))

(defn classify-renders
  "Partition the current cascade's renders into three groups by
  comparing each render's instance-token against the prior cascade's
  render set. Returns
    `{:mounted [<render> ...]
      :rendered [<render> ...]
      :unmounted [<render> ...]}`
  Render entries pass through unchanged — the view code reads
  `:render-key`, `:triggered-by`, and `:elapsed-ms` off each row.

  - `current` is the current cascade's `:renders` vector.
  - `prior` is the immediately-prior cascade's `:renders` vector, or
    nil for the first cascade in a session.

  Per the v1 derivation in this ns's docstring:
    - First-cascade in a session → every render goes to `:mounted`.
    - Re-rendered = appears in both cascades' renders by instance-
      token.
    - Mounted = appears in current but not prior.
    - Unmounted = appears in prior but not current (the row carries
      the prior cascade's elapsed-ms and triggered-by)."
  [current prior]
  (let [current-by-token (index-renders current)
        prior-by-token   (index-renders prior)
        current-tokens   (set (keys current-by-token))
        prior-tokens     (set (keys prior-by-token))
        mounted-tokens   (set/difference current-tokens prior-tokens)
        rendered-tokens  (set/intersection current-tokens prior-tokens)
        unmounted-tokens (set/difference prior-tokens current-tokens)]
    {:mounted   (mapv current-by-token (sort mounted-tokens))
     :rendered  (mapv current-by-token (sort rendered-tokens))
     :unmounted (mapv prior-by-token   (sort unmounted-tokens))}))

;; ---- grid-explosion clustering -----------------------------------------

(defn- cluster-identity
  "Per spec §Grid-explosion clustering — clustering key is
  `(view-id, triggered-by)`. Renders sharing this tuple cluster
  together; the cluster's per-instance values vary."
  [render]
  [(render-key->view-id (:render-key render))
   (:triggered-by render)])

(defn cluster-renders
  "Apply the cluster-threshold rule (spec §Grid-explosion clustering)
  to a group's render list. Returns a vector where each entry is
  either

    `{:kind :single  :render <render>}`

  or

    `{:kind :cluster
      :view-id <view-id>
      :triggered-by <sub-id-or-nil>
      :count <N>
      :total-ms <sum>
      :avg-ms <mean>
      :p95-ms <p95>
      :renders [<render> ...]}`

  Single renders preserve their original order; clusters list after
  their first-occurrence index so the view's row order stays stable.

  `threshold` defaults to `default-cluster-threshold` (50)."
  ([renders]
   (cluster-renders renders default-cluster-threshold))
  ([renders threshold]
   (let [groups        (group-by cluster-identity renders)
         ;; first-index-per-group keeps the row order stable.
         indexed       (map-indexed vector renders)
         first-idx     (reduce (fn [acc [idx r]]
                                 (let [k (cluster-identity r)]
                                   (if (contains? acc k) acc (assoc acc k idx))))
                               {}
                               indexed)
         items
         (for [[k group-renders] groups]
           (if (< (count group-renders) threshold)
             (mapv (fn [r] {:kind :single :render r}) group-renders)
             [{:kind         :cluster
               :view-id      (first k)
               :triggered-by (second k)
               :count        (count group-renders)
               :total-ms     (reduce + 0 (keep :elapsed-ms group-renders))
               :avg-ms       (let [n (count group-renders)
                                   t (reduce + 0 (keep :elapsed-ms group-renders))]
                               (if (pos? n) (/ t n) 0))
               :p95-ms       (let [es (sort (keep :elapsed-ms group-renders))
                                   n  (count es)]
                               (if (pos? n)
                                 (nth es (min (dec n) (int (* 0.95 n))))
                                 0))
               :renders      (vec group-renders)}]))
         flattened     (apply concat items)
         with-order    (map (fn [item]
                              (let [k (case (:kind item)
                                        :single  (cluster-identity (:render item))
                                        :cluster [(:view-id item) (:triggered-by item)])]
                                (assoc item ::order (get first-idx k 0))))
                            flattened)]
     (->> with-order
          (sort-by ::order)
          (mapv #(dissoc % ::order))))))

(defn cluster-count
  "Count clusters (kind :cluster) in a clustered vector. Used by the
  `:cluster-counts` projection in `build-views-data` so callers can
  display per-group cluster totals."
  [clustered]
  (count (filter #(= :cluster (:kind %)) clustered)))

;; ---- re-rendered group: invalidating-sub layout -----------------------

(defn sub-status
  "Classify one `:invalidated-by` row into one of three statuses for the
  glyph decoration in the Re-rendered group's right-column list (per
  spec §Sub-status legibility + §0ter.1 R3 — the cache-miss-equal
  gap):

    `:cache-miss-trigger` — sub's recomputed value changed and is
      the trigger for this re-render. Rendered as `✱` (amber).
    `:cache-miss-equal`   — sub recomputed this cascade but the new
      value structurally equalled the prior; React skipped re-render
      of any view reading only this sub. Rendered as `≈` (muted).
    `:cache-hit`          — sub was consumed but did NOT recompute
      (cache hit). Rendered as `·` (muted).

  Inputs:
    - `row` — one entry from `re-render-invalidated-by`'s output
      vector. Carries `:trigger?` (true → trigger) and
      `:recomputed?` (true → recomputed this cascade).

  The three statuses are mutually exclusive; the classifier returns
  a single keyword."
  [row]
  (cond
    (:trigger? row)     :cache-miss-trigger
    (:recomputed? row)  :cache-miss-equal
    :else               :cache-hit))

(defn re-render-invalidated-by
  "Per spec §Per-row content (Re-rendered) — derive the 'Invalidated
  by' list for one re-render row. Returns a vector

    `[{:sub-id <sub-id>
       :recomputed? true/false
       :trigger? true/false}    ;; ✱ marker
      ...]`

  - The render's `:triggered-by` sub-id (when present) is the
    primary trigger (`:trigger? true`); a `:triggered-by nil`
    means parent re-rendered → forced child re-render (the row
    surfaces a synthetic `:sub-id ::parent-forced` entry).
  - The remaining `:sub-runs` entries this cascade are also-
    consumed subs (`:trigger? false`, marked `·` for cache-hit or
    `≈` for cache-miss-equal in the view per `sub-status`).
    The view filters these to subs the component actually consumed
    once per-render sub-attribution lands.

  `sub-runs` is the cascade's `:sub-runs` vector."
  [render sub-runs]
  (let [triggered-by (:triggered-by render)
        trigger      (when triggered-by
                       {:sub-id triggered-by :recomputed? true :trigger? true})
        parent-forced (when-not triggered-by
                        {:sub-id      ::parent-forced
                         :recomputed? false
                         :trigger?    true})
        sub-rows     (for [sr sub-runs
                           :when (not= (:sub-id sr) triggered-by)]
                       {:sub-id      (:sub-id sr)
                        :recomputed? (boolean (:recomputed? sr))
                        :trigger?    false})]
    (vec (concat (filter some? [trigger parent-forced]) sub-rows))))

;; ---- group-by sub: inverted hierarchy ----------------------------------
;;
;; Per spec §Group-by toggle the alternate hierarchy is `:sub` —
;; top-level rows are subs that ran this cascade; under each sub-row,
;; the components that consumed it. Answers the symmetric question:
;; "which sub caused all this rendering?"
;;
;; The data is mathematically symmetric with the `:component`
;; grouping: each Re-rendered single-row's `:invalidated-by` list
;; carries `{:sub-id ... :trigger? ...}` rows, which we invert here
;; into `sub-id → [view-id ...]`. We restrict to the Re-rendered
;; group only — Mounted / Unmounted sub-attribution isn't surfaced
;; per-render (Mounted has no prior to compare; Unmounted has no
;; current).

(defn build-sub-grouped
  "Invert the `:rendered` group's component → subs mapping into a
  sub → components mapping. Returns a vector of sub-rows, each:

    `{:sub-id       <sub-id>
      :trigger?     <bool>        ; true when this sub triggered
                                   ; at least one re-render this cascade
      :recomputed?  <bool>        ; true when this sub recomputed
      :views        [{:view-id <kw>
                      :render-key <[view-id token]>
                      :trigger? <bool>
                      :elapsed-ms <ms>} ...]
      :view-count   <N>}`

  `rendered-items` is the clustered+annotated `:rendered` group
  (each item from `cluster-renders` with `:invalidated-by`).
  Cluster items contribute their cluster's `:triggered-by` sub-id
  with `:view-count` = cluster's `:count`. Single items contribute
  each `:invalidated-by` row's `:sub-id`.

  Stable ordering: sub-rows sort by first-occurrence index in the
  source list so the inverted view keeps a deterministic order
  matching the source cascade."
  [rendered-items]
  (let [contributions
        (for [[idx item] (map-indexed vector rendered-items)
              :let [r (:render item)
                    view-id (render-key->view-id (:render-key r))
                    invalidated-by (or (:invalidated-by item) [])]
              row    invalidated-by]
          {:idx          idx
           :sub-id       (:sub-id row)
           :trigger?     (boolean (:trigger? row))
           :recomputed?  (boolean (:recomputed? row))
           :clustered?   (= :cluster (:kind item))
           :clustered-n  (when (= :cluster (:kind item)) (:count item))
           :view-id      (or view-id (:view-id item))
           :render-key   (:render-key r)
           :elapsed-ms   (:elapsed-ms r)})
        by-sub (group-by :sub-id contributions)
        first-idx (reduce (fn [acc {:keys [sub-id idx]}]
                            (if (contains? acc sub-id)
                              acc
                              (assoc acc sub-id idx)))
                          {}
                          contributions)
        sub-rows
        (for [[sub-id rows] by-sub
              :let [any-trigger?    (some :trigger? rows)
                    any-recomputed? (some :recomputed? rows)
                    views (vec (for [r rows]
                                 (cond-> {:view-id      (:view-id r)
                                          :render-key   (:render-key r)
                                          :trigger?     (:trigger? r)
                                          :elapsed-ms   (:elapsed-ms r)}
                                   (:clustered? r)
                                   (assoc :clustered?  true
                                          :clustered-n (:clustered-n r)))))
                    view-count (reduce + 0
                                       (map (fn [r]
                                              (if (:clustered? r)
                                                (or (:clustered-n r) 1)
                                                1))
                                            rows))]]
          {:sub-id       sub-id
           :trigger?     (boolean any-trigger?)
           :recomputed?  (boolean any-recomputed?)
           :views        views
           :view-count   view-count
           ::order       (get first-idx sub-id 0)})]
    (->> sub-rows
         (sort-by ::order)
         (mapv #(dissoc % ::order)))))

;; ---- assembled views-data ---------------------------------------------

(defn build-views-data
  "The cascade projection the view consumes. Composes the helpers
  above into the single shape `:rf.causa/views-data` returns. Pure
  data so unit-tests can exercise the whole pipeline without
  re-frame.

  Inputs:
    - `current-renders`  — current cascade's `:renders` vector
    - `prior-renders`    — prior cascade's `:renders` vector (or nil)
    - `sub-runs`         — current cascade's `:sub-runs` vector
    - `opts`             — `{:cluster-threshold N
                              :component-filter <view-id-or-nil>}`

  Output:
    `{:groups          {:mounted [<item>]
                        :rendered [<item>]
                        :unmounted [<item>]}
      :totals          {:mounted N
                        :rendered N
                        :unmounted N
                        :cascade-ms <sum>}
      :cluster-counts  {:mounted N :rendered N :unmounted N}}`

  where each `<item>` is the clustered shape from `cluster-renders`
  with an added `:invalidated-by` slot (vector of trigger / non-
  trigger sub rows) for `:single`-kind entries in the `:rendered`
  group only. `:cluster` rows carry an `:invalidated-by` synthetic
  single-row pointing at the cluster's `:triggered-by` sub-id (per
  spec §Per-row content (Re-rendered) → Clustered renders)."
  [current-renders prior-renders sub-runs
   {:keys [cluster-threshold component-filter]
    :or   {cluster-threshold default-cluster-threshold
           component-filter  nil}}]
  (let [filter-renders
        (if component-filter
          (fn [rs] (filterv #(= component-filter
                                (render-key->view-id (:render-key %)))
                            rs))
          identity)
        groups (classify-renders (filter-renders current-renders)
                                 (filter-renders prior-renders))
        clustered (into {}
                        (for [[g rs] groups]
                          [g (cluster-renders rs cluster-threshold)]))
        ;; Annotate :rendered group items with :invalidated-by.
        clustered-with-invalidated
        (update clustered :rendered
                (fn [items]
                  (mapv (fn [item]
                          (case (:kind item)
                            :single
                            (assoc item :invalidated-by
                                   (re-render-invalidated-by (:render item) sub-runs))
                            :cluster
                            (assoc item :invalidated-by
                                   [{:sub-id      (:triggered-by item)
                                     :recomputed? true
                                     :trigger?    true
                                     :clustered?  true}])))
                        items)))
        cascade-ms (reduce + 0 (keep :elapsed-ms current-renders))
        ;; Per spec §Group-by toggle — the inverted hierarchy
        ;; (sub-rows at top, components consumed underneath) reuses
        ;; the :rendered group's annotated items. The view-layer's
        ;; group-by `:sub` renderer reads `:sub-grouped`.
        sub-grouped (build-sub-grouped (:rendered clustered-with-invalidated))]
    {:groups         clustered-with-invalidated
     :sub-grouped    sub-grouped
     :totals         {:mounted    (count (:mounted groups))
                      :rendered   (count (:rendered groups))
                      :unmounted  (count (:unmounted groups))
                      :cascade-ms cascade-ms}
     :cluster-counts {:mounted   (cluster-count (:mounted clustered))
                      :rendered  (cluster-count (:rendered clustered))
                      :unmounted (cluster-count (:unmounted clustered))}
     :component-filter component-filter}))
