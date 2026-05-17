(ns day8.re-frame2-causa.popover.causality-graph-helpers
  "Pure-data helpers for the Causality popover graph (rf2-dqnuu).

  Per `tools/causa/spec/018-Event-Spine.md` §10 the popover renders the
  causal graph of the currently-focused event:

    - **Ancestor chain** (root cause → focused), LR layout, breadcrumb
      style. Depth-capped at 8 (deeper → `… N more ancestors above`).
    - **Descendants tree** below the focused node, TB layout. Per-level
      breadth-capped at 8 (deeper → `… N more children` inline).
    - **Focused node** sits between the two regions and is highlighted
      with the cyan border (matches the spine row gutter `◉`).

  ## Why a separate ns from `panels/causality_graph_helpers.cljc`

  The popover graph projects *from a focal event* — `:ancestors`,
  `:focused`, `:descendants` — rather than from the full cascade
  vector. The panel helper's `project-cascades-to-graph` walks every
  cascade in the buffer and emits one node per cascade; the popover
  helper walks parent / children chains rooted at the focused id and
  caps depth/breadth so the graph fits the 640×480 popover frame.

  The pure node colour / glyph tokens are re-used from the panel
  helper via `(:require day8.re-frame2-causa.panels.causality-graph-
  helpers :as panel-h)` — single source of truth for the design-token
  rendering even when the layout surface differs.

  ## Data model

  Input:

      cascades      ; the same vector `:rf.causa/cascades` exposes
      buffer        ; raw trace events (for `enrich-cascades`)
      focused-id    ; the `:dispatch-id` the spine has focused on

  Output (the `build-payload` shape consumed by ELK + by the fallback
  renderer):

      {:focused      <node-or-nil>
       :ancestors    [<node> ...]    ; root-cause first, focused-parent last
       :descendants  {<id> [<node> ...]}    ; flat by level
       :nodes        [<node> ...]    ; convenience flat list
       :edges        [[<from-id> <to-id>] ...]
       :truncated?   {:ancestors? bool :descendants? bool}
       :empty?       bool}

  Pure data → data. JVM-runnable. Layout (pixel positions) is computed
  separately by the ELK pass in the CLJS view (or by the CSS-grid
  fallback when ELK is unavailable)."
  (:require [day8.re-frame2-causa.panels.causality-graph-helpers :as panel-h]))

;; ---- depth / breadth caps -----------------------------------------------

(def ^:const ancestor-cap
  "Max ancestors surfaced in the LR chain. Per spec §10 §Depth limits —
  'Ancestor chain depth-capped at 8 (rare to exceed; deeper → … N more
  ancestors above disclosure at the far-left)'."
  8)

(def ^:const descendants-breadth-cap
  "Max children surfaced per level in the TB tree. Per spec §10 §Depth
  limits — 'Descendants tree breadth-capped per level at 8 (rare;
  deeper → … N more children disclosure inline)'."
  8)

(def ^:const descendants-depth-cap
  "Defensive depth cap on the TB descendants walk. Spec §10 doesn't
  pin a per-level depth (only breadth-per-level), but a pathological
  long chain — handler A dispatches B dispatches C dispatches … —
  would otherwise blow out the popover frame vertically. Cap at 8
  levels — matches the ancestor depth and the chosen LR chain shape
  reads symmetrically."
  8)

;; ---- focused-cascade lookup ---------------------------------------------

(defn focused-cascade
  "Find the cascade in `enriched-cascades` whose `:dispatch-id` matches
  `focused-id`. Returns nil when the id has aged out of the buffer (or
  the spine has no focus yet)."
  [enriched-cascades focused-id]
  (when focused-id
    (some #(when (= focused-id (:dispatch-id %)) %) enriched-cascades)))

;; ---- ancestor chain ------------------------------------------------------

(defn- parent-by-id
  "Build a `{dispatch-id parent-id}` map from the cascade vector. Uses
  the same `parent-of` algebra the panel helper uses (private there;
  we re-derive from the enriched cascade's `:event-trace` slot)."
  [enriched-cascades]
  (reduce (fn [m c]
            (let [id     (:dispatch-id c)
                  parent (some-> c :event-trace :tags :parent-dispatch-id)]
              (cond-> m
                id (assoc id parent))))
          {}
          enriched-cascades))

(defn ancestor-chain
  "Walk `focused-id`'s parent chain upward. Returns the chain
  oldest-first (root cause at position 0, the focused-event's parent
  at the end). The focused id itself is NOT included.

  Caps at `ancestor-cap` — when the chain is longer, only the
  closest `ancestor-cap` ancestors surface. The caller pulls the
  `:truncated?` flag off the higher-level payload to know whether to
  render the `… N more ancestors above` disclosure.

  Pure data → vector. Cycle-safe: a cascade whose parent forms a
  cycle (defensive — production data never loops, but stale buffers
  shouldn't crash) stops at the cycle entry."
  [parent-map focused-id]
  (loop [acc      []
         seen     #{focused-id}
         cur      (get parent-map focused-id)]
    (cond
      (nil? cur) (vec (reverse acc))
      (contains? seen cur) (vec (reverse acc))
      (>= (count acc) ancestor-cap) (vec (reverse acc))
      :else (recur (conj acc cur) (conj seen cur) (get parent-map cur)))))

;; ---- descendants tree ---------------------------------------------------

(defn- children-by-parent
  "Build a `{parent-id [child-id ...]}` map from the cascade vector.
  Children are kept in dispatch-id order (re-frame allocates ids
  monotonically per session, so this is the chronological order)."
  [enriched-cascades]
  (reduce (fn [m c]
            (let [parent (some-> c :event-trace :tags :parent-dispatch-id)
                  id     (:dispatch-id c)]
              (cond-> m
                (and parent id) (update parent (fnil conj []) id))))
          {}
          enriched-cascades))

(defn descendants-tree
  "Walk `focused-id`'s child chains downward, level by level. Returns

      {:levels     [[<id> ...] [<id> ...] ...]   ; level 0 = direct children
       :truncated? {<parent-id> <int> ...}}      ; clipped children counts

  Caps each level's per-parent breadth at `descendants-breadth-cap`;
  caps total depth at `descendants-depth-cap`. The clipped-count map
  surfaces both surfaces ('this parent has N more children we didn't
  render' AND 'the tree was truncated at depth D')."
  [children-map focused-id]
  (loop [acc-levels  []
         truncated   {}
         frontier    [focused-id]
         seen        #{focused-id}
         depth       0]
    (if (or (empty? frontier) (>= depth descendants-depth-cap))
      ;; Truncated depth path — note the deepest pending frontier
      ;; as a depth-cut by pseudo-key :__depth__ so the caller can
      ;; surface a "depth limit reached" disclosure if desired.
      (let [truncated' (if (and (seq frontier) (>= depth descendants-depth-cap))
                         (assoc truncated :__depth__ (count frontier))
                         truncated)]
        {:levels    acc-levels
         :truncated truncated'})
      (let [;; For each parent in the frontier, collect its children
            ;; capped at `descendants-breadth-cap`.
            level-rows  (mapv (fn [pid]
                                (let [kids (or (get children-map pid) [])
                                      kept (vec (take descendants-breadth-cap
                                                      (remove seen kids)))
                                      total (count (remove seen kids))
                                      cut   (max 0 (- total descendants-breadth-cap))]
                                  {:parent pid :kept kept :cut cut}))
                              frontier)
            level-ids   (vec (mapcat :kept level-rows))
            truncated'  (reduce (fn [m {:keys [parent cut]}]
                                  (if (pos? cut) (assoc m parent cut) m))
                                truncated
                                level-rows)]
        (if (empty? level-ids)
          {:levels    acc-levels
           :truncated truncated'}
          (recur (conj acc-levels level-ids)
                 truncated'
                 level-ids
                 (into seen level-ids)
                 (inc depth)))))))

;; ---- payload assembly ----------------------------------------------------

(defn- node-for
  "Project a cascade into the popover-node shape. Re-uses
  `panel-h/origin->fill` + status-stroke tables so colour treatment
  matches the panel-render's tokens."
  [cascade role]
  ;; role = :focused | :ancestor | :descendant
  (let [origin (or (some-> cascade :event-trace :tags :origin) :app)
        error? (boolean (some #(= :error (:op-type %)) (:other cascade)))
        warn?  (boolean (some #(= :warning (:op-type %)) (:other cascade)))]
    {:dispatch-id (:dispatch-id cascade)
     :event       (:event cascade)
     :origin      origin
     :role        role
     :error?      error?
     :warning?    warn?
     :fill        (get panel-h/origin->fill origin
                       (get panel-h/origin->fill :app))
     :stroke      (cond
                    (= role :focused) "#43C3D0"    ; cyan — matches gutter ◉
                    error?            (:error  panel-h/status->stroke)
                    warn?             (:warning panel-h/status->stroke)
                    :else             (:default panel-h/status->stroke))
     :glyph       (case role
                    :focused    "◉"
                    :ancestor   "→"
                    :descendant "○")}))

(defn build-payload
  "Build the popover graph payload from the cascade list and focused
  id. Returns the `{:focused :ancestors :descendants :nodes :edges
  :truncated? :empty?}` shape consumed by the ELK pass and by the
  fallback list-renderer.

  Pure data → data. The caller (the view ns) folds this through the
  ELK layout pass; tests assert against the shape directly."
  [enriched-cascades focused-id]
  (let [focused-c (focused-cascade enriched-cascades focused-id)]
    (if (nil? focused-c)
      {:focused     nil
       :ancestors   []
       :descendants {:levels [] :truncated {}}
       :nodes       []
       :edges       []
       :truncated?  {:ancestors? false :descendants? false}
       :empty?      true}
      (let [pmap        (parent-by-id enriched-cascades)
            cmap        (children-by-parent enriched-cascades)
            anc-ids     (ancestor-chain pmap focused-id)
            desc-tree   (descendants-tree cmap focused-id)
            by-id       (into {} (map (juxt :dispatch-id identity)) enriched-cascades)
            anc-nodes   (mapv #(node-for (get by-id %) :ancestor) anc-ids)
            focused-n   (node-for focused-c :focused)
            desc-flat   (vec (mapcat identity (:levels desc-tree)))
            desc-nodes  (mapv #(node-for (get by-id %) :descendant) desc-flat)
            ;; LR edges along the ancestor chain root→...→focused.
            anc-edges   (vec (map vector
                                  (concat anc-ids [focused-id])
                                  (concat (rest anc-ids) [focused-id]
                                          [focused-id])))
            ;; descendant edges — parent→child for each (level k → k+1).
            desc-edges  (vec
                          (for [[level-idx ids] (map-indexed vector (:levels desc-tree))
                                child           ids
                                :let [parent (get pmap child)]
                                :when (and parent
                                           (or (= parent focused-id)
                                               (contains? (set (apply concat (take level-idx (:levels desc-tree))))
                                                          parent)
                                               (= parent focused-id)))]
                            [parent child]))
            nodes       (vec (concat anc-nodes [focused-n] desc-nodes))
            edges       (vec (concat
                               ;; Ancestor chain edges: root → ... → focused
                               (when (seq anc-ids)
                                 (map vector anc-ids (concat (rest anc-ids) [focused-id])))
                               desc-edges))]
        {:focused     focused-n
         :ancestors   anc-nodes
         :descendants {:levels    (:levels desc-tree)
                       :truncated (:truncated desc-tree)
                       :nodes     desc-nodes}
         :nodes       nodes
         :edges       edges
         :truncated?  {:ancestors?   (let [chain-walk (loop [n 0 cur (get pmap focused-id) seen #{focused-id}]
                                                        (cond
                                                          (nil? cur) n
                                                          (contains? seen cur) n
                                                          :else (recur (inc n) (get pmap cur) (conj seen cur))))]
                                       (> chain-walk ancestor-cap))
                       :descendants? (boolean (seq (:truncated desc-tree)))}
         :empty?      false}))))

;; ---- layout (ELK input shape) ------------------------------------------

(defn ->elk-graph
  "Project a `build-payload` result into the ELK JSON graph shape:

      {:id        \"root\"
       :layoutOptions {\"elk.algorithm\" \"layered\"
                       \"elk.direction\" \"DOWN\"}
       :children  [{:id ... :width ... :height ...}]
       :edges     [{:id ... :sources [...] :targets [...]}]}

  Per spec §10 Q12 the popover defaults to **LR for the ancestor
  chain** and **TB (DOWN) for the descendants tree**, but ELK takes a
  single direction per graph. v1 ships a single TB graph (the
  focused node sits at the top of the descendants subtree and the
  ancestor chain stacks above it as a vertical pre-amble); the
  per-region LR/TB hybrid lands in a follow-on bead (the spec calls
  it out as 'first implementation will tell us if it reads well').

  Layout direction is selectable via `direction` (`:lr` | `:tb`) so
  the popover footer toggle can flip it."
  [payload direction]
  (let [dir-str (case direction
                  :lr "RIGHT"
                  :tb "DOWN"
                  "DOWN")
        node-w  140
        node-h  44
        mk-child (fn [n]
                   {:id     (str (:dispatch-id n))
                    :width  node-w
                    :height node-h})
        mk-edge  (fn [[from to]]
                   {:id      (str from "-" to)
                    :sources [(str from)]
                    :targets [(str to)]})]
    {:id            "root"
     :layoutOptions {"elk.algorithm"               "layered"
                     "elk.direction"               dir-str
                     "elk.spacing.nodeNode"        "24"
                     "elk.layered.spacing.nodeNodeBetweenLayers" "32"}
     :children      (mapv mk-child (:nodes payload))
     :edges         (mapv mk-edge  (:edges payload))}))
