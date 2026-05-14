(ns re-frame.flows.topo
  "Topological sort over a per-frame flow map (Spec 013).

  Pure-data input, pure-data output. No atoms, no side effects, no
  trace emission — every call decides the evaluation order of one
  frame's flows from the static `:inputs` / `:path` declarations alone.

  Per rf2-mnu8z this is the first leg of the flows split — pulled out
  of the original monolith so the algorithm is unit-testable in
  isolation. The registry calls `topo-sort` up-front on a prospective
  flow map to spot cycles before mutating state (rf2-7csri); the
  post-drain walker (`re-frame.flows/run-flows!`) calls it on every
  drain to fix evaluation order.

  Per Spec 013 §Topological sort the rule is: flow B depends on flow
  A iff A's `:path` and any of B's `:inputs` share a path prefix in
  either direction. Kahn's algorithm produces the order; on cycle we
  reconstruct a DFS path through the stuck nodes and throw
  `:rf.error/flow-cycle` with a closing-repeat cycle vector
  (e.g. `[:a :b :a]`) — tools like Causa render this directly."
  ;; Pure module — no requires. .cljc-portable so the JVM test sweep
  ;; can exercise the algorithm without dragging the CLJS runtime in.
  )

(defn- prefix? [a b]
  (and (>= (count b) (count a))
       (= a (vec (take (count a) b)))))

(defn depends-on?
  "Per Spec 013 §Topological sort: B depends on A iff A's :path and any
  of B's :inputs share a path prefix in either direction."
  [b-flow a-flow]
  (let [a-path (:path a-flow)]
    (boolean
      (some (fn [b-input]
              (or (prefix? a-path b-input)
                  (prefix? b-input a-path)))
            (:inputs b-flow)))))

(defn- extract-cycle-path
  "Given the dependency graph `id → #{deps...}` and the set of stuck
  ids `remaining` (those Kahn's couldn't peel), return an ordered cycle
  path with a CLOSING REPEAT — e.g. `[:a :b :a]` for the cycle
  `:a → :b → :a`. Tools render this directly.

  DFS from an arbitrary stuck node, following dependency edges within
  `remaining`. When a node is revisited along the current path stack,
  the slice `[stack-from-revisit ... current]` plus the revisited node
  closes the cycle.

  `remaining` is a set of stuck ids (NOT the live `remaining` map from
  Kahn's loop — its values have been mutated as deps were peeled away,
  so we read fresh dep edges from `graph`)."
  [graph remaining]
  ;; Deterministic pick: sort by hash so cycle reports are stable across
  ;; runs without requiring flow-ids be mutually comparable (sort fails
  ;; on mixed types). Hash collisions don't matter — we only need ONE
  ;; cycle path, any pick produces a valid one.
  (let [stuck-sorted (vec (sort-by hash remaining))
        start        (first stuck-sorted)]
    (loop [stack [start]
           seen  #{start}]
      (let [node (peek stack)
            ;; Only follow edges into other stuck nodes — edges to
            ;; already-peeled nodes can't be part of a remaining cycle.
            next-dep (first (sort-by hash (filter remaining (graph node))))]
        (cond
          (nil? next-dep)
          ;; Dead end within `remaining` — by Kahn's algorithm every
          ;; stuck node has at least one stuck dep (that's why Kahn
          ;; couldn't peel it). Reaching this branch means the topo
          ;; state is internally inconsistent: a stuck node found no
          ;; stuck dep to follow. Fail loud rather than silently
          ;; returning a malformed cycle path — a closing-repeat
          ;; vector built from a dead end would lie to tools (Causa,
          ;; the flow panel) about the offending chain.
          (throw (ex-info ":rf.error/flow-cycle-extract-invariant"
                          {:reason   ":rf.error/flow-cycle-extract-invariant"
                           :node     node
                           :stack    stack
                           :seen     seen
                           :remaining remaining
                           :recovery :no-recovery}))

          (contains? seen next-dep)
          ;; Cycle found. Slice the stack from the revisited node
          ;; forward, then append the revisited node again to close.
          ;; Pure-Clojure index search keeps this .cljc-portable.
          (let [idx (loop [i 0]
                      (cond
                        (= i (count stack))      0
                        (= (nth stack i) next-dep) i
                        :else                    (recur (inc i))))]
            (conj (subvec stack idx) next-dep))

          :else
          (recur (conj stack next-dep) (conj seen next-dep)))))))

(defn topo-sort
  "Kahn's algorithm — pure `loop`/`recur` over immutable state. Returns
  flows in evaluation order; throws `:rf.error/flow-cycle` if the graph
  is cyclic. `ready` is a vector used as a LIFO stack
  (`peek`/`pop`/`conj`); `remaining` is the live id→dep-set map; `order`
  is the accumulating result.

  On cycle: ex-data carries `:cycle` — an ordered cycle path with a
  closing repeat (e.g. `[:a :b :a]`) extracted via DFS through the
  stuck nodes. Per Spec 013 §Cycle detection / Spec 009 §Error contract.
  Tools (e.g. Causa) render this directly as the offending chain.

  Note: callers re-run this on every drain via
  `re-frame.flows/run-flows!`. A memo was trialled and removed (rf2-cd00):
  the per-frame flow map is tiny (Kahn over a handful of nodes) and a
  memo keyed on the flow map needs explicit invalidation on every
  reg-flow / clear-flow anyway. The unmemoised call is the cheapest
  correct option."
  [flow-map]
  (let [ids (vec (keys flow-map))]
    ;; Fast-paths for the trivial sizes — topo-sort runs on every drain
    ;; AND every `reg-flow`, so the dominant call shape during steady-
    ;; state is the small-registry case where the O(n²) graph build is
    ;; pure waste. Self-edges are excluded (per the `(not= id %)` filter
    ;; below), so a single-flow registry has no dependency edges and
    ;; the sort order is just `[that-id]`.
    (case (count ids)
      0 []
      1 ids
      ;; ≥2 flows: build the full dep graph and run Kahn's algorithm.
      ;; Graph construction is O(n² · max-inputs · max-path-length) —
      ;; tiny at v1 per-frame flow counts (a handful of nodes); even a
      ;; 20-flow registry stays under a millisecond. If a real
      ;; bottleneck shows up at larger node counts, a memo keyed on
      ;; the flow-map identity would be the next move (rf2-cd00
      ;; removed the previous memo; the contract is just deterministic
      ;; order per drain).
      (let [graph (into {}
                        (map (fn [id]
                               (let [flow (flow-map id)]
                                 [id (into #{}
                                           (filter #(and (not= id %)
                                                         (depends-on? flow (flow-map %))))
                                           ids)])))
                        ids)]
        (loop [ready     (filterv #(empty? (graph %)) ids)
               remaining graph
               order     []]
          (if-let [n (peek ready)]
            (let [rem0 (dissoc remaining n)
                  [remaining' ready']
                  (reduce-kv (fn [[rem rdy] m m-deps]
                               (if-not (contains? m-deps n)
                                 [rem rdy]
                                 (let [m-deps' (disj m-deps n)]
                                   [(assoc rem m m-deps')
                                    (cond-> rdy (empty? m-deps') (conj m))])))
                             [rem0 (pop ready)]
                             rem0)]
              (recur ready' remaining' (conj order n)))
            (if (seq remaining)
              (throw (ex-info ":rf.error/flow-cycle"
                              {:cycle (extract-cycle-path graph
                                                          (set (keys remaining)))}))
              order)))))))
