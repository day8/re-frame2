(ns re-frame.flows.topo
  "Topological sort over a per-frame flow map (Spec 013).

  Pure-data input, pure-data output. No atoms, no side effects, no
  trace emission — every call decides the evaluation order of one
  frame's flows from the static `:inputs` / `:output-path` declarations alone.

  Per rf2-mnu8z this is the first leg of the flows split — pulled out
  of the original monolith so the algorithm is unit-testable in
  isolation. The registry calls `topo-sort` up-front on a prospective
  flow map to spot cycles before mutating state (rf2-7csri); the
  outermost-`:after` walker (`re-frame.flows/run-flows-on-db`) calls it
  on every drain to fix evaluation order.

  Per Spec 013 §Topological sort the rule is: flow B depends on flow
  A iff A's `:output-path` and any of B's `:inputs` share a path prefix in
  either direction. Kahn's algorithm produces the order; on cycle we
  reconstruct a DFS path through the stuck nodes and throw
  `:rf.error/flow-cycle` with a closing-repeat cycle vector
  (e.g. `[:a :b :a]`) — tools like Xray render this directly.

  This module also owns `detect-output-path-overlap!` — the symmetric
  check the registry runs alongside the cycle check at `reg-flow` time
  (Spec 013 §Disjoint output paths): two flows in one frame whose OUTPUT
  `:output-path`s overlap (one a prefix of the other, identical included) have
  no dependency edge between them (the edge rule compares `:output-path` vs
  `:inputs`, never `:output-path` vs `:output-path`), so their relative evaluation
  order is undefined and they would race for the shared slot under
  last-write-wins. That is an authoring footgun, not a valid topology,
  so it is rejected at registration with `:rf.error/flow-path-overlap`."
  ;; rf2-t3cfil (EP-0012 tier-2 flows consumer sweep): the prefix / overlap
  ;; relations are the SHARED `:rf/path` algebra (`re-frame.path`,
  ;; Conventions §The :rf/path algebra), not a flows-private dialect. Per the
  ;; EP-0012 disposition-1 graduation note, "subsystems MUST NOT keep private
  ;; ad hoc overlap / prefix logic once they cite these helpers — there is no
  ;; tool-only path semantics." Flows is exactly such a consumer (the
  ;; algebra's ns-doc names "flows" as a use site), so topo depends on
  ;; `re-frame.path` rather than re-deriving `prefix?` / a bespoke overlap
  ;; test. `re-frame.path` lives in core, which this artefact already depends
  ;; on, so this drags no new dependency onto the classpath and the module
  ;; stays .cljc-portable for the JVM test sweep.
  (:require [re-frame.error :as error]
            [re-frame.path :as path]))

(defn depends-on?
  "Per Spec 013 §Topological sort: B depends on A iff A's :output-path and any
  of B's :inputs share a path prefix in either direction. The prefix
  relation is the shared `re-frame.path/prefix?` (rf2-t3cfil)."
  [b-flow a-flow]
  (let [a-path (:output-path a-flow)]
    (boolean
      (some (fn [b-input]
              (or (path/prefix? a-path b-input)
                  (path/prefix? b-input a-path)))
            (:inputs b-flow)))))

(defn output-paths-overlap?
  "True iff two flows' OUTPUT `:output-path`s collide on the same app-db slot —
  i.e. one path is a prefix of the other (identical paths included, since
  every vector is a prefix of itself). Per Spec 013 §Disjoint output
  paths: `[:x]` overlaps `[:x]` (identical), `[:x]` overlaps `[:x :y]`
  (parent/child — writing `[:x]` clobbers everything under it, and writing
  `[:x :y]` lands inside `[:x]`'s value), but `[:x :y]` and `[:x :z]` are
  disjoint (siblings — neither is a prefix of the other).

  This is the shared `re-frame.path/overlap?` relation (rf2-t3cfil) — the
  SAME prefix-in-either-direction test every path-shaped subsystem inherits,
  no longer a flows-private predicate. `re-frame.path/overlap?` normalizes
  its inputs; flow paths are already validated vectors, so the behaviour is
  identical."
  [a-path b-path]
  (path/overlap? a-path b-path))

(defn- first-overlapping-pair
  "Scan the prospective per-frame `flow-map` (`{flow-id flow-map}`) for the
  first pair of DISTINCT flows whose output `:output-path`s overlap (one a prefix
  of the other). Return `{:flow-ids [lo hi] :paths [lo-path hi-path]}` for
  the offending pair, or `nil` if every pair is disjoint.

  Terminates by construction: a single `for` over the `(< i j)` upper
  triangle of the entry vector (O(F²) prefix comparisons, F = the frame's
  flow count — a handful of nodes at v1, same order as the graph build),
  short-circuited by `(some ...)`. The reported pair is canonically
  ordered by `(juxt hash str)` so the report is GENUINELY stable across
  runs (rf2-tx1ub): `hash` alone leaves the order undefined on a hash
  collision (distinct ids, equal hash) — `sort-by` is stable and would
  fall back to map-iteration order there, a non-contract across JVM runs.
  The `str` tie-break makes the lo/hi assignment depend only on the ids'
  values, never on iteration order, while still avoiding the requirement
  that flow-ids be mutually comparable (`sort` throws on mixed-type ids,
  but `str` is total). Mirrors `extract-cycle-path`'s deterministic pick."
  [flow-map]
  (let [entries (vec flow-map)
        n       (count entries)]
    (some (fn [[i j]]
            (let [[a-id a-flow] (nth entries i)
                  [b-id b-flow] (nth entries j)
                  a-path        (:output-path a-flow)
                  b-path        (:output-path b-flow)]
              (when (output-paths-overlap? a-path b-path)
                (let [[lo-id hi-id]     (sort-by (juxt hash str) [a-id b-id])
                      [lo-path hi-path] (if (= lo-id a-id)
                                          [a-path b-path]
                                          [b-path a-path])]
                  {:flow-ids [lo-id hi-id]
                   :paths    [lo-path hi-path]}))))
          (for [i (range n)
                j (range (inc i) n)]
            [i j]))))

(defn detect-output-path-overlap!
  "Symmetric with `topo-sort`'s cycle check (Spec 013 §Disjoint output
  paths). Given a prospective per-frame `flow-map` (`{flow-id flow-map}`),
  throw `:rf.error/flow-path-overlap` if any two distinct flows have
  overlapping output `:output-path`s — otherwise return `flow-map` unchanged so
  the caller can thread it.

  WHY this is a registration error, not a topo edge: the dependency rule
  (`depends-on?`) compares one flow's `:output-path` against another's `:inputs`,
  never `:output-path` vs `:output-path`. Two same-frame flows whose outputs overlap but
  whose inputs are disjoint therefore get NO edge — both are 'ready' at
  topo-sort start and their relative order falls out of map-iteration
  order, a non-contract. The loser of that undefined order silently loses
  the shared slot under last-write-wins. Rejecting at `reg-flow` (inside
  the atomic `swap!`, like the cycle check) closes the footgun before any
  state mutates.

  The `ex-info` carries the canonical thrown-error shape (per Spec 009
  §The thrown-error shape) — `:rf.error/id` / `:where` / `:recovery`
  `:fix-registration` (the caller fixes one flow's `:output-path` and retries,
  exactly like the cycle / validate-flow rejections) / `:reason` — plus
  `:overlap`, the offending `{:flow-ids [id-a id-b] :paths [path-a
  path-b]}` pair, so tools (Xray's flow panel) name the colliding flows
  directly.

  Pure-data, no requires (the shape is inlined rather than reaching for
  `registry.cljc`'s `flow-error` helper) — same posture as the cycle
  throw in `topo-sort`."
  [flow-map]
  (when-let [overlap (first-overlapping-pair flow-map)]
    (error/throw-error!
      :rf.error/flow-path-overlap 'rf/reg-flow
      "Two flows in the same frame have overlapping output :output-paths (one is a prefix of the other, identical included). Their relative evaluation order is undefined — the topo-sort dependency rule compares :output-path against :inputs, never :output-path against :output-path, so no edge orders them and the shared slot would be written last-write-wins in map-iteration order (per Spec 013 §Disjoint output paths). Give each flow a disjoint :output-path."
      {:recovery :fix-registration
       :extra    {:overlap overlap}}))
  flow-map)

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
          ;; vector built from a dead end would lie to tools (Xray,
          ;; the flow panel) about the offending chain.
          (error/throw-error!
            :rf.error/flow-cycle-extract-invariant 'rf/reg-flow
            "Cycle-path extraction reached a dead end: a stuck node found no stuck dependency to follow. Internal topo invariant violated — report with the :node / :stack / :seen / :remaining payload."
            {:extra {:node      node
                     :stack     stack
                     :seen      seen
                     :remaining remaining}})

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
  Tools (e.g. Xray) render this directly as the offending chain.

  Note: callers re-run this on every drain via
  `re-frame.flows/run-flows-on-db`. A memo was trialled and removed (rf2-cd00):
  the per-frame flow map is tiny (Kahn over a handful of nodes) and a
  memo keyed on the flow map needs explicit invalidation on every
  reg-flow / clear-flow anyway. The unmemoised call is the cheapest
  correct option."
  [flow-map]
  (let [ids (vec (keys flow-map))]
    ;; 0/1 flows have no edges — order is trivial; skip the O(n²) graph
    ;; build (the cost note lives once on `topo-sort`'s docstring above).
    (case (count ids)
      0 []
      1 ids
      ;; ≥2 flows: build the full dep graph and run Kahn's algorithm.
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
          (if-let [node-id (peek ready)]
            ;; Peel `node-id` from the remaining graph: drop its row, then
            ;; walk every other node, dropping the edge into `node-id` from
            ;; its dep-set. A dep-set that empties out is newly-ready.
            (let [remaining-without-node (dissoc remaining node-id)
                  [remaining' ready']
                  (reduce-kv (fn [[acc-remaining acc-ready] dep-id dep-set]
                               (if-not (contains? dep-set node-id)
                                 [acc-remaining acc-ready]
                                 (let [dep-set' (disj dep-set node-id)]
                                   [(assoc acc-remaining dep-id dep-set')
                                    (cond-> acc-ready (empty? dep-set') (conj dep-id))])))
                             [remaining-without-node (pop ready)]
                             remaining-without-node)]
              (recur ready' remaining' (conj order node-id)))
            (if (seq remaining)
              ;; Cycle ex-info carries the canonical thrown-error shape
              ;; (per Spec 009 §The thrown-error shape) every other flow
              ;; ex-info uses (`:rf.error/id` / `:where` / `:recovery` /
              ;; `:reason`) so tools (Xray, re-frame-10x,
              ;; late-bind-missing wrappers) read the `:rf.error/id`
              ;; discriminator uniformly across error surfaces.
              ;; `topo.cljc` is the pure-data module — no `:require`s —
              ;; so the shape is inlined rather than reaching for
              ;; `registry.cljc`'s `flow-error` helper.
              ;;
              ;; `:recovery :fix-registration` (rf2-ee38b.9) — a cycle is
              ;; the same class of error as the sibling `validate-flow`
              ;; rejections: detected at `reg-flow` time on a PROSPECTIVE
              ;; map BEFORE any state mutates (rf2-7csri), so the prior
              ;; registration survives and the caller fixes their
              ;; `:inputs` / `:output-path` and retries. Stamping `:no-recovery`
              ;; would make an `:on-error` policy or tool that branches on
              ;; `:recovery` to decide "is this user-fixable?" treat a
              ;; fixable cycle as terminal — inconsistent with every other
              ;; registration rejection. (The `extract-cycle-path`
              ;; dead-end throw below stays `:no-recovery`; that one is a
              ;; genuine internal-invariant violation, not caller-fixable.)
              (error/throw-error!
                :rf.error/flow-cycle 'rf/reg-flow
                "Cyclic flow dependency — at least one pair of flows' :output-path / :inputs overlap mutually (per Spec 013 §Dependency rule). The closing-repeat :cycle vector names the offending chain."
                {:recovery :fix-registration
                 :extra    {:cycle (extract-cycle-path graph
                                                       (set (keys remaining)))}})
              order)))))))
