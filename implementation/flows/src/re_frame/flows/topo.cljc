(ns re-frame.flows.topo
  "Pure topology checks over one frame's flow map.

  Flow B depends on A when A's output path and one of B's input paths overlap
  by prefix. Kahn's algorithm produces evaluation order; cycle errors include a
  closing-repeat path for tools. Registration separately rejects overlapping
  output paths because output/output overlap creates no dependency edge and
  would otherwise leave write order undefined."
  (:require [re-frame.error :as rf.error]
            [re-frame.path :as rf.path]))

(defn depends-on?
  "True when B reads a path overlapping A's output path."
  [b-flow a-flow]
  (let [a-path (:output-path a-flow)]
    (boolean
      (some (fn [b-input]
              (or (rf.path/prefix? a-path b-input)
                  (rf.path/prefix? b-input a-path)))
            (:inputs b-flow)))))

(defn output-paths-overlap?
  "True when two output paths are equal or one is a prefix of the other."
  [a-path b-path]
  (rf.path/overlap? a-path b-path))

(defn- first-overlapping-pair
  "Return a stable description of the first overlapping output pair, or nil.

  Scan each unordered pair once. `(juxt hash str)` gives deterministic reports
  without requiring heterogeneous ids to be comparable."
  [flow-map]
  (let [entries     (vec flow-map)
        entry-count (count entries)]
    (some (fn [[i j]]
            (let [[a-id a-flow] (nth entries i)
                  [b-id b-flow] (nth entries j)
                  a-path        (:output-path a-flow)
                  b-path        (:output-path b-flow)]
              (when (output-paths-overlap? a-path b-path)
                (let [[first-flow-id second-flow-id]
                      (sort-by (juxt hash str) [a-id b-id])
                      [first-output-path second-output-path]
                      (if (= first-flow-id a-id)
                        [a-path b-path]
                        [b-path a-path])]
                  {:flow-ids [first-flow-id second-flow-id]
                   :paths    [first-output-path second-output-path]}))))
          (for [i (range entry-count)
                j (range (inc i) entry-count)]
            [i j]))))

(defn detect-output-path-overlap!
  "Reject overlapping output paths in a prospective flow map.

  Output/output overlap is not a topology edge, so accepting it would make
  last-write order depend on map iteration. Returns `flow-map` when valid."
  [flow-map]
  (when-let [overlap (first-overlapping-pair flow-map)]
    (rf.error/throw-error!
      :rf.error/flow-path-overlap 'rf/reg-flow
      "Two flows in the same frame have overlapping output :output-paths (one is a prefix of the other, identical included). Their relative evaluation order is undefined — the topo-sort dependency rule compares :output-path against :inputs, never :output-path against :output-path, so no edge orders them and the shared slot would be written last-write-wins in map-iteration order (per Spec 013 §Disjoint output paths). Give each flow a disjoint :output-path."
      {:recovery :fix-registration
       :extra    {:overlap overlap}}))
  flow-map)

(defn- extract-cycle-path
  "Extract a closing-repeat cycle path from Kahn's unpeeled nodes.

  Follow the original graph because Kahn's working dependency sets have been
  mutated while peeling acyclic nodes."
  [graph remaining]
  ;; Hash ordering is deterministic enough here: any cycle path is valid.
  (let [stuck-node-ids (vec (sort-by hash remaining))
        start-node-id  (first stuck-node-ids)]
    (loop [stack [start-node-id]
           seen  #{start-node-id}]
      (let [node-id (peek stack)
            ;; Only follow edges into other stuck nodes — edges to
            ;; already-peeled nodes can't be part of a remaining cycle.
            next-dependency-id
            (first (sort-by hash (filter remaining (graph node-id))))]
        (cond
          (nil? next-dependency-id)
          ;; Every unpeeled node must have an unpeeled dependency.
          (rf.error/throw-error!
            :rf.error/flow-cycle-extract-invariant 'rf/reg-flow
            "Cycle-path extraction reached a dead end: a stuck node found no stuck dependency to follow. Internal topo invariant violated — report with the :node / :stack / :seen / :remaining payload."
            {:extra {:node      node-id
                     :stack     stack
                     :seen      seen
                     :remaining remaining}})

          (contains? seen next-dependency-id)
          ;; Slice from the revisited node and append it to close the cycle.
          (let [cycle-start-index
                (loop [i 0]
                  (cond
                    (= i (count stack))                 0
                    (= (nth stack i) next-dependency-id) i
                    :else                               (recur (inc i))))]
            (conj (subvec stack cycle-start-index) next-dependency-id))

          :else
          (recur (conj stack next-dependency-id)
                 (conj seen next-dependency-id)))))))

(defn topo-sort
  "Return flow ids in Kahn topological order.

  Throws `:rf.error/flow-cycle` with a closing-repeat `:cycle` path. A flow
  whose own :inputs overlap its own :output-path depends on itself; that is a
  single-node cycle reported as `[id id]`. A flow is a pure derivation of
  independently-owned facts, never a recurrence over its own prior output
  (Spec 013 §Dependency rule), so the dependency graph RETAINS the `id -> id`
  self-edge and Kahn rejects it exactly like a multi-node cycle — there is no
  separate self-edge case and no singleton fast path that could smuggle one in.
  This is intentionally unmemoized: frame flow maps are small and lifecycle
  changes would require explicit cache invalidation."
  [flow-map]
  (let [ids (vec (keys flow-map))]
    (if (empty? ids)
      []
      ;; Build the full dependency graph — INCLUDING any `id -> id` self-edge —
      ;; even for a singleton. A one-flow registry whose input overlaps its own
      ;; output is a self-cycle and must be rejected, not fast-pathed to `[id]`.
      (let [graph (into {}
                        (map (fn [id]
                               (let [flow (flow-map id)]
                                 [id (into #{}
                                           (filter #(depends-on? flow (flow-map %)))
                                           ids)])))
                        ids)]
        (loop [ready     (filterv #(empty? (graph %)) ids)
               remaining graph
               order     []]
          (if-let [node-id (peek ready)]
            ;; Peel `node-id` from the remaining graph: drop its row, then
            ;; walk every other node, dropping the edge into `node-id` from
            ;; its dep-set. A dep-set that empties out is newly-ready. A
            ;; self-edged node never becomes ready (its own row is skipped by
            ;; `remaining-without-node`), so it stays stuck → cycle.
            (let [remaining-without-node (dissoc remaining node-id)
                  [remaining' ready']
                  (reduce-kv (fn [[acc-remaining acc-ready]
                                  dependent-id dependency-ids]
                               (if-not (contains? dependency-ids node-id)
                                 [acc-remaining acc-ready]
                                 (let [remaining-dependency-ids
                                       (disj dependency-ids node-id)]
                                   [(assoc acc-remaining dependent-id
                                           remaining-dependency-ids)
                                    (cond-> acc-ready
                                      (empty? remaining-dependency-ids)
                                      (conj dependent-id))])))
                             [remaining-without-node (pop ready)]
                             remaining-without-node)]
              (recur ready' remaining' (conj order node-id)))
            (if (seq remaining)
              ;; Registration validates the prospective map before mutation,
              ;; so callers can correct the graph and retry.
              (rf.error/throw-error!
                :rf.error/flow-cycle 'rf/reg-flow
                "Cyclic flow dependency — either two-or-more flows' :output-path / :inputs overlap mutually, or one flow's :inputs overlap its own :output-path (a self-cycle) (per Spec 013 §Dependency rule). The closing-repeat :cycle vector names the offending chain; a self-cycle repeats one id, e.g. [id id]."
                {:recovery :fix-registration
                 :extra    {:cycle (extract-cycle-path graph
                                                       (set (keys remaining)))}})
              order)))))))
