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
      `Invalidated by` layout; spec §R3-D).
    - Grid-explosion clustering — collapse `≥ cluster-threshold`
      renders sharing `(view-id, triggered-by)` into one aggregate row
      with `× N · total ms · avg µs` stats (spec §Grid-explosion
      clustering; default threshold 50).
    - Heatmap-mode segments — % of cascade render time per view-id
      with a `<rest>` bucket for components contributing < 1% (spec
      §Heatmap mode).
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
  uses a half-circle (the two-column 'Invalidated by' card carries
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

(def default-heatmap-rest-fraction
  "Per spec §Heatmap mode — `<rest>` segment aggregates components
  whose share < 1% so the bar stays legible."
  0.01)

(def default-heatmap-auto-suggest-threshold
  "Per spec §Heatmap mode — auto-suggests heatmap when cluster-count
  > 20 after clustering. The view consumes this number to set the
  toggle's hinted state; it never auto-activates."
  20)

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
  auto-suggest-heatmap heuristic."
  [clustered]
  (count (filter #(= :cluster (:kind %)) clustered)))

;; ---- heatmap segments --------------------------------------------------

(defn- safe-div
  [num den]
  (if (zero? den) 0 (/ num den)))

(defn heatmap-segments
  "Per spec §Heatmap mode — derive horizontal-bar segments from a
  cascade's render list. Returns a vector of segment maps sorted by
  total-ms descending; components under the `rest-fraction` threshold
  aggregate into one trailing `:rest` segment.

    `[{:kind :component
       :view-id <view-id>
       :total-ms <ms>
       :fraction <0..1>
       :count <renders>
       :avg-ms <ms>}
      ...
      {:kind :rest
       :view-id ::rest
       :total-ms <ms>
       :fraction <0..1>
       :count <renders>
       :merged-view-ids #{<view-id> ...}}]`

  `rest-fraction` defaults to `default-heatmap-rest-fraction` (0.01)."
  ([renders]
   (heatmap-segments renders default-heatmap-rest-fraction))
  ([renders rest-fraction]
   (let [total           (reduce + 0 (keep :elapsed-ms renders))
         grouped         (group-by #(render-key->view-id (:render-key %)) renders)
         per-component   (for [[vid rs] grouped
                               :let [tms (reduce + 0 (keep :elapsed-ms rs))
                                     n   (count rs)]]
                           {:kind     :component
                            :view-id  vid
                            :total-ms tms
                            :fraction (safe-div tms total)
                            :count    n
                            :avg-ms   (safe-div tms n)})
         sorted          (sort-by (juxt (comp - :total-ms)
                                        (comp str :view-id))
                                  per-component)
         [big small]     (split-with #(>= (:fraction %) rest-fraction) sorted)
         big-vec         (vec big)
         rest-vec        (if (seq small)
                           (let [tms (reduce + 0 (map :total-ms small))]
                             [{:kind            :rest
                               :view-id         ::rest
                               :total-ms        tms
                               :fraction        (safe-div tms total)
                               :count           (reduce + 0 (map :count small))
                               :merged-view-ids (set (map :view-id small))}])
                           [])]
     (into big-vec rest-vec))))

;; ---- re-rendered group: invalidating-sub layout -----------------------

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
    consumed subs (`:trigger? false`, marked `·` in the view).
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
                              :heatmap-rest-fraction R
                              :heatmap? <bool>
                              :component-filter <view-id-or-nil>}`

  Output:
    `{:groups          {:mounted [<item>]
                        :rendered [<item>]
                        :unmounted [<item>]}
      :totals          {:mounted N
                        :rendered N
                        :unmounted N
                        :cascade-ms <sum>}
      :cluster-counts  {:mounted N :rendered N :unmounted N}
      :heatmap         {:segments [<segment> ...]
                        :total-ms <sum>}
      :auto-suggest-heatmap?  <bool>}`

  where each `<item>` is the clustered shape from `cluster-renders`
  with an added `:invalidated-by` slot (vector of trigger / non-
  trigger sub rows) for `:single`-kind entries in the `:rendered`
  group only. `:cluster` rows carry an `:invalidated-by` synthetic
  single-row pointing at the cluster's `:triggered-by` sub-id (per
  spec §Per-row content (Re-rendered) → Clustered renders)."
  [current-renders prior-renders sub-runs
   {:keys [cluster-threshold heatmap-rest-fraction
           heatmap? component-filter]
    :or   {cluster-threshold     default-cluster-threshold
           heatmap-rest-fraction default-heatmap-rest-fraction
           heatmap?              false
           component-filter      nil}}]
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
        segments   (heatmap-segments current-renders heatmap-rest-fraction)
        rendered-clusters (cluster-count (:rendered clustered))]
    {:groups         clustered-with-invalidated
     :totals         {:mounted    (count (:mounted groups))
                      :rendered   (count (:rendered groups))
                      :unmounted  (count (:unmounted groups))
                      :cascade-ms cascade-ms}
     :cluster-counts {:mounted   (cluster-count (:mounted clustered))
                      :rendered  rendered-clusters
                      :unmounted (cluster-count (:unmounted clustered))}
     :heatmap        {:segments segments
                      :total-ms cascade-ms}
     :heatmap?       (boolean heatmap?)
     :auto-suggest-heatmap?
     (> rendered-clusters default-heatmap-auto-suggest-threshold)
     :component-filter component-filter}))
