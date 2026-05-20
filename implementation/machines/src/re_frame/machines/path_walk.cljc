(ns re-frame.machines.path-walk
  "Deepest-wins path walking. Per Spec 005 §Transition resolution.

  ## Why this namespace exists

  The state-machine runtime's most important resolution rule is
  **deepest-wins**: when an event arrives or an `:always` is evaluated
  at some leaf, the runtime walks from the leaf toward the root, asking
  each ancestor state-node along the way whether it declares a matching
  transition. The first match wins; ancestors above that match never
  see the event.

  Spec 005 spells this rule out for `:on`, for `:always`, for `:after`,
  and for `:spawn-all` join-event interception — four surfaces, one
  rule. Before this namespace existed, the rule was implemented four
  times across two files as the same `(loop [i (dec (count path))] ...
  (recur (dec i)))` shape, each picker re-deriving the boundary
  arithmetic and the prefix-construction by hand.

  Naming the rule once, in one place, lets the pickers read as their
  own intent: 'pick the deepest `:on` match', 'pick the deepest
  `:always` whose guard passes', etc., delegating the walk mechanics
  to `walk-path-leaf-to-root`. New pickers (e.g. per-state error
  handlers, if ever added) inherit the rule for free.

  ## Surface

      (walk-path-leaf-to-root tree path predicate)

  Walks `path` from leaf toward root. At each step, calls
  `(predicate prefix node)` where `prefix` is the inclusive-prefix
  vector (a `[:root :child :grandchild]`-shape path of length `i+1`,
  starting at full `path` and shortening by one element per step) and
  `node` is the state-node located at that prefix inside `tree`.
  Returns the first non-`nil` result of `predicate`, or `nil` if no
  level matched.

  `tree` is anything carrying a `:states` map of nested state-nodes
  — typically the runtime machine spec itself, or (for a parallel
  parent) a region body. The internal descent matches
  `transition/node-at`'s semantics exactly; this ns inlines the
  descent to avoid a cycle (transition requires path-walk; path-walk
  must not require transition).

  The predicate's return value IS the caller's hit shape — typically a
  map like `{:transition t :decl-path prefix}` — keeping the picker's
  semantics out of this primitive.

  ## Trace hook (future)

  Per the rf2-tjhhp note: this primitive paves the way for a
  `pick-trace` instrumentation that Causa wants — 'the runtime checked
  these states in this order before settling on the deepest match'.
  When that lands, the trace will hang off this single fn rather than
  fan out across four pickers.

  ## Why not also root→leaf?

  The inverse direction (root→leaf descent to a known absolute path,
  as in `find-invoke-spec-at`) is a different operation — it walks to
  a known target rather than seeking a deepest match. Treating it as
  the same primitive with a direction flag obscured the unifying rule
  (deepest-wins isn't 'a walk'; it's 'a walk in a specific direction
  with a specific tie-breaking semantics'). It lives in its own ns.")

#?(:clj (set! *warn-on-reflection* true))

(defn- resolve-path-nodes
  "Walk `(:states tree)` root→leaf following `path`, returning a vector
  of `node`s — one per prefix level — where `nodes[i]` is the
  state-node at `(subvec path 0 (inc i))` (so index 0 is the level-1
  node, index `(dec (count path))` is the leaf). When a level's node
  doesn't resolve (defensive — e.g. an exited compound branch), the
  slot is `nil` and descent stops; remaining slots are absent. The
  return vector's `count` ≤ `(count path)`.

  Per rf2-3h1pf the walk is done ONCE root→leaf to feed the leaf→root
  iteration in `walk-path-leaf-to-root` — the old shape re-descended
  from the root at every level (O(depth²) per picker call). One pass
  + a small vector of resolved nodes turns it into O(depth)."
  [tree path]
  (loop [m       (:states tree)
         remain  path
         acc     (transient [])]
    (if (empty? remain)
      (persistent! acc)
      (let [n (get m (first remain))]
        (if (nil? n)
          ;; Path doesn't resolve at this level — stop descending. The
          ;; predicate is skipped for this level (and any deeper one)
          ;; on the leaf→root walk; ancestors above remain valid via
          ;; the levels accumulated so far.
          (persistent! acc)
          (recur (:states n) (rest remain) (conj! acc n)))))))

(defn walk-path-leaf-to-root
  "Walk `path` leaf→root through `tree`'s `:states` map. For each
  inclusive-prefix `[prefix node]` pair (deepest first), call
  `(predicate prefix node)`. Return the FIRST non-`nil` predicate
  result, or `nil` if every level returned `nil` (or had no node).

  Per Spec 005 §Transition resolution — deepest-wins with parent
  fallthrough. The named home for the rule in code.

  `prefix` is the vector of path elements from `path[0]` through
  `path[i]` inclusive, of length `i+1`. `node` is the state-node map
  at that prefix; when the prefix doesn't resolve (defensive), the
  predicate is skipped and the walk continues toward the root.

  Per rf2-ogsrx the inclusive-prefix is taken via `subvec` (zero-copy
  slice) rather than `(vec (take (inc i) path))` (lazy-seq + realise +
  copy). On a depth-D path × P pickers per macrostep the walk now
  allocates D×P subvec references rather than D×P fresh vectors.

  Per rf2-3h1pf the descent through `tree`'s `:states` is done ONCE
  root→leaf via `resolve-path-nodes`, then iterated in reverse — the
  old shape called `node-at*` at every level, re-descending from the
  root each time, giving O(depth²) per picker call. The single-pass
  shape is O(depth). Four pickers (`:on`, `:always`, `:after`,
  `:spawn-all` join) all run this primitive on the same active path
  per macrostep, so the depth² → depth win compounds on every event.
  The `path` argument is coerced to a vector once at the top so
  callers may pass any seqable; downstream callers receiving the
  prefix may rely on the vector contract (predicates often
  `(last prefix)` or `conj` onto it)."
  [tree path predicate]
  (let [path  (if (vector? path) path (vec path))
        nodes (resolve-path-nodes tree path)]
    (loop [i (dec (count nodes))]
      (when (>= i 0)
        (let [prefix (subvec path 0 (inc i))
              node   (nth nodes i)
              hit    (predicate prefix node)]
          (if (some? hit)
            hit
            (recur (dec i))))))))
