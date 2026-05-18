(ns day8.re-frame2-causa.diff.section-grouping
  "Section-grouping pass — annotated tree → N path-headed sections
  (rf2-gfxmk Phase 1 of rf2-abts7).

  Design source-of-truth:
  `ai/findings/2026-05-18-difftastic-in-causa.md` §3.1.1.

  ## What a section is

      {:path    [:cart :items]
       :subtree <annotated-subtree-rooted-at-path>}

  The renderer renders N sections vertically, each headed by its path
  (breadcrumb) and a body that's the local annotated subtree.

  ## Algorithm

  1. Walk the annotated tree, collect every change point — every
     `:added` / `:removed` / `:modified` node — with its full path.
  2. Each change point starts as a candidate section, headed by its
     full path with the change-point subtree as body.
  3. Coalesce siblings: if two candidate sections share an ancestor
     within `max-coalesce-depth` (default 3) levels, merge into one
     section headed by the shared ancestor; the new body is the
     annotated subtree rooted at the ancestor.
  4. Split overfilled: if a coalesced section would render more than
     `max-unchanged-context` (default 50) paths of unchanged context,
     split back into the constituent candidate sections.
  5. Whole-DB replacement: if every top-level key changed (count of
     changed top-level keys equals total top-level key count), emit
     ONE root-rooted section.
  6. Ordering: lexicographic by path (stable across re-renders).

  ## Why root is special

  The root path `[]` is never coalesced-into unless rule 5 applies. A
  change at `[:flash]` and a change at `[:status]` share only the
  empty-path ancestor; coalescing both into one root-rooted section
  defeats the sections-per-cluster premise (we'd render the whole DB).
  Both stand as separate root-level singleton sections.

  ## Cost

  - Walk-to-collect: O(N) over the annotated tree
  - Coalescence: O(M log M) where M = change-point count (sort by path)
  - Split overfilled: O(M × subtree-size) worst case
  - Whole-DB detect: O(top-level-key-count) — constant per epoch

  Negligible relative to the annotated-tree walk itself.

  ## JVM-runnable

  Pure data → data. `.cljc` so the JVM unit-test target picks it up."
  (:require [day8.re-frame2-causa.diff.annotated-tree :as at]))

(def default-opts
  "Tunable knobs. Defaults per design §3.1.1; tune against a real corpus
  (rf2-ogkh0)."
  {:max-coalesce-depth    3
   :max-unchanged-context 50})

;; ---- 1. walk to collect change points ----------------------------------

(defn- change-op?
  "True when the node represents a direct leaf change (not a container
  of further changes). The recurse step descends into `:children` and
  treats their per-child ops as the change points; the container
  itself is not a change point."
  [op]
  (contains? #{:added :removed :modified} op))

(defn- collect-into!
  "Inner walker: takes a transient accumulator + current node + path.
  Returns the transient. Recursion-friendly arity used by
  `collect-change-points`."
  [acc node path]
  (let [op (at/op-of node)]
    (cond
      (= op :same)
      acc

      (= op :children)
      (reduce
        (fn [a child]
          (let [k (at/child-key child)]
            (collect-into! a child (if (some? k) (conj path k) path))))
        acc
        (:children node))

      (change-op? op)
      (conj! acc {:path (vec path) :subtree node})

      :else
      acc)))

(defn collect-change-points
  "Walk the annotated tree and return a vector of `{:path [...]
  :subtree <node>}` for every change point.

  A change point is any `:added` / `:removed` / `:modified` node. The
  `:children` containers are recursed into; the path tracks the chain
  of `:key` / `:index` slots used to reach this point.

  Pure data → data."
  [root]
  (persistent! (collect-into! (transient []) root [])))

;; ---- 2. coalesce siblings ----------------------------------------------

(defn- common-prefix
  "Longest common prefix of two paths."
  [a b]
  (let [n (min (count a) (count b))]
    (loop [i 0]
      (if (and (< i n) (= (nth a i) (nth b i)))
        (recur (inc i))
        (subvec (vec a) 0 i)))))

(defn- coalesce-pair?
  "Two candidate sections are coalesceable when their common-prefix is
  within `max-coalesce-depth` levels of either path AND the prefix is
  non-root (root coalescence is reserved for the whole-DB replacement
  rule).

  Concretely: the distance from each path to the prefix (path-count -
  prefix-count) must be ≤ max-coalesce-depth, AND the prefix must be
  non-empty (or the whole-DB replacement rule would apply)."
  [path-a path-b max-coalesce-depth]
  (let [prefix     (common-prefix path-a path-b)
        prefix-len (count prefix)]
    (and (pos? prefix-len)
         (<= (- (count path-a) prefix-len) max-coalesce-depth)
         (<= (- (count path-b) prefix-len) max-coalesce-depth))))

(defn- group-by-ancestor
  "Walk candidate sections (sorted by path); fold each into the running
  cluster when coalescence applies, else start a new cluster.

  Each cluster carries its current shared-prefix and the list of
  contributing candidate paths."
  [candidates max-coalesce-depth]
  (reduce
    (fn [clusters {:keys [path]}]
      (let [last-cluster (peek clusters)]
        (if last-cluster
          (let [last-prefix (:prefix last-cluster)
                ;; Coalesce when this path shares an ancestor with the
                ;; cluster's current prefix within the depth budget.
                new-prefix  (common-prefix last-prefix path)]
            (if (and (pos? (count new-prefix))
                     (<= (- (count last-prefix) (count new-prefix))
                         max-coalesce-depth)
                     (<= (- (count path) (count new-prefix))
                         max-coalesce-depth))
              (conj (pop clusters)
                    (-> last-cluster
                        (assoc :prefix new-prefix)
                        (update :paths conj path)))
              (conj clusters {:prefix path :paths [path]})))
          [{:prefix path :paths [path]}])))
    []
    candidates))

;; ---- 3. resolve a subtree from a path ---------------------------------

(defn subtree-at-path
  "Walk the annotated tree to the node at `path`. Returns nil if any
  segment is missing. Used to read the body of a coalesced section.

  The `:children` container's `:children` vector is keyed by the
  child's `:key` (for maps) or `:index` (for vectors). When the path
  is empty, returns `root`."
  [root path]
  (loop [node root
         path path]
    (if (empty? path)
      node
      (when (= :children (at/op-of node))
        (let [seg   (first path)
              child (first (filter #(= seg (at/child-key %))
                                   (:children node)))]
          (when child
            (recur child (rest path))))))))

;; ---- 4. count rendered nodes (for the unchanged-context budget) -------

(defn count-rendered-nodes
  "Count how many nodes the renderer would visit for `subtree`. Used
  for the `max-unchanged-context` split rule (§3.1.1 step 3).

  ## Renderer-faithful accounting (rf2-szzjh)

  The renderer (`diff/render.cljs`) collapses **all** `:same` direct
  children of a `:children` container into a single
  `(N entries unchanged)` chip — many siblings or few, the chip
  occupies one row's worth of canvas. Earlier versions counted each
  `:same` sibling verbatim, which inflated the cost of legitimately-
  coalesced clusters with many unchanged siblings (e.g.
  `pg/large-multi-tier`: 50 new catalog keys merged into a 200-key
  unchanged catalog → counter reported 251, defeated every
  `max-unchanged-context` ≤ 250 and shattered into 50 singletons).

  This counter mirrors the renderer's collapse:

  - `:children` container → `1` (header) + Σ counts for **non-`:same`**
    children + `1` if **any** `:same` children exist (the chip)
  - `:added` / `:removed` / `:modified` / `:same` leaf → `1`

  Discovered by rf2-ogkh0's 53-pair corpus sweep; see
  `ai/findings/2026-05-19-causa-section-grouping-heuristics.md` §5.1."
  [subtree]
  (let [op (at/op-of subtree)]
    (if (= op :children)
      (let [children    (:children subtree)
            same?       #(= :same (at/op-of %))
            changed     (remove same? children)
            any-same?   (boolean (some same? children))]
        (+ 1
           (reduce + 0 (map count-rendered-nodes changed))
           (if any-same? 1 0)))
      1)))

;; ---- 5. whole-DB replacement detection --------------------------------

(defn- top-level-replacement?
  "True when every top-level key of `root` is a *direct* change point
  (`:added` / `:removed` / `:modified`) — i.e. no `:same` AND no
  `:children` direct children. Detection rule per design §3.1.1 step
  4: this is the `reset-frame-db!` wholesale-replacement signature.
  A cascade that partly nests (some children are `:children`
  containers because the slot was edited rather than replaced)
  is NOT a whole-DB replacement; those decompose into sections per
  the main algorithm.

  Operates on a `:children` root with `:tag :map`; degenerate cases
  (non-map root, no children) return false. Maps only; vector- /
  set-rooted replacements are rare and the main algorithm handles
  them acceptably."
  [root]
  (and (= :children (at/op-of root))
       (= :map (:tag root))
       (let [children      (:children root)
             child-count   (count children)
             changed-count (count (filter #(change-op? (at/op-of %))
                                          children))]
         ;; ≥ 2 children gate: a singleton-key change should render as
         ;; one section headed at the key path, not as a root section.
         ;; The whole-DB replacement signature is "many keys, all
         ;; changed" — singletons collapse to root sections trivially
         ;; and lose the path-header context.
         (and (>= child-count 2)
              (= changed-count child-count)))))

;; ---- 6. assemble sections ---------------------------------------------

(defn- promote-singleton-to-parent
  "When a cluster has exactly one candidate path AND that path is
  deeper than 1 segment, promote the section path to the candidate's
  parent so the section breadcrumb gives container context (per design
  §3.1.1 worked example — `[:user :prefs]` heads a section whose body
  is the `:theme :light → :dark` leaf, NOT `[:user :prefs :theme]`).

  Top-level singletons (path length 1) keep their full path — the
  breadcrumb `[:flash]` carries identifying context even at root."
  [prefix paths]
  (if (and (= 1 (count paths))
           (let [p (first paths)]
             (and (= prefix p) (> (count p) 1))))
    (subvec (vec (first paths)) 0 (dec (count (first paths))))
    prefix))

(defn- sections-from-candidates
  "Given candidates + clusters (already coalesced), build the final
  section vector with overfilled-section splitting + singleton-promote
  applied."
  [root clusters {:keys [max-unchanged-context]}]
  (mapcat
    (fn [{:keys [prefix paths]}]
      (let [promoted-prefix   (promote-singleton-to-parent prefix paths)
            coalesced-subtree (subtree-at-path root promoted-prefix)
            rendered          (when coalesced-subtree
                                (count-rendered-nodes coalesced-subtree))]
        (cond
          ;; Subtree missing — shouldn't happen for well-formed inputs
          ;; but be defensive (return per-candidate sections).
          (nil? coalesced-subtree)
          (for [p paths]
            {:path p :subtree (subtree-at-path root p)})

          ;; Overfilled — split back into per-candidate sections.
          (> rendered max-unchanged-context)
          (for [p paths]
            {:path p :subtree (subtree-at-path root p)})

          :else
          [{:path promoted-prefix :subtree coalesced-subtree}])))
    clusters))

;; ---- public entry ------------------------------------------------------

(defn group-into-sections
  "Group an annotated tree into N path-headed sections per the
  sections-per-cluster decomposition (design §3.1.1).

  Args:
    `root`  — root annotated node (from `annotated-tree/diff-tree`).
    `opts`  — optional `{:max-coalesce-depth 3 :max-unchanged-context 50}`.

  Returns a sorted vector of `{:path [...] :subtree <annotated-node>}`.

  When the root has no changes returns `[]`. When the root is a
  whole-DB replacement (rule 5), returns one section rooted at `[]`."
  ([root] (group-into-sections root nil))
  ([root opts]
   (let [{:keys [max-coalesce-depth max-unchanged-context] :as opts}
         (merge default-opts opts)
         root-op (at/op-of root)]
     (cond
       ;; No changes at root → no sections.
       (= root-op :same)
       []

       ;; Whole-DB replacement rule (§3.1.1 step 4): every top-level
       ;; key changed → one root section.
       (top-level-replacement? root)
       [{:path [] :subtree root}]

       ;; Root itself is a direct change (`:added`/`:removed`/`:modified`
       ;; at root); single root-level section.
       (change-op? root-op)
       [{:path [] :subtree root}]

       :else
       (let [candidates (collect-change-points root)
             ;; Stable lexicographic ordering by path-as-pr-str so
             ;; sections sort consistently across re-renders.
             sorted     (vec (sort-by #(pr-str (:path %)) candidates))
             clusters   (group-by-ancestor sorted max-coalesce-depth)]
         (vec (sections-from-candidates root clusters opts)))))))
