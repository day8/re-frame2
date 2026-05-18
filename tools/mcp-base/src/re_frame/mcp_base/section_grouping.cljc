(ns re-frame.mcp-base.section-grouping
  "Patch-list → path-headed cluster sections (rf2-qeous).

  ## What this is

  The MCP wire boundary's `:db-after` shape evolves from flat patches
  to path-headed cluster sections — the same sections-per-cluster
  decomposition Causa's panel renderer ships (rf2-gfxmk Phase 1 of
  rf2-abts7), but projected from the patch list rather than from the
  annotated-tree.

  An agent that asks 'what did this cascade do?' gets N scoped
  cluster summaries — each headed by a path breadcrumb — instead of
  a flat 1-D triple list it has to re-cluster mentally.

  ## Why operate on patches, not the annotated tree

  The annotated-tree engine + sections-per-cluster pass live in
  `tools/causa/src/.../diff/` — Causa's panel surface. mcp-base ships
  independently to Clojars and cannot pull `tools/causa` in (the dep
  arrow is tool → mcp-base, never the reverse, and Causa is a panel
  bundle, not a base library).

  The patches already carry path + op + value — every signal needed
  to head a cluster. Operating on patches:

    - Keeps mcp-base dep-free (no causa pull-in, no jar bloat).
    - Round-trips losslessly: each section's `:patches` is a subset
      of the flat list; concatenating sections' patches reconstructs
      the original (path-ordered) list, which `apply-patches` then
      replays unchanged.
    - Loses no semantic fidelity vs the annotated-tree projection
      for the agent-query use case — both produce the same N
      path-headed clusters; only the per-cluster body shape differs
      (patches here vs annotated subtree there).

  ## Shape

  Input: a sorted (by path-as-pr-str) patch vector — the output of
  `re-frame.mcp-base.diff-encode/collect-patches`.

  Output: a vector of sections —

      [{:section-path [:cart :items]
        :section-kind :modified
        :patches      [[[:cart :items 0 :qty] :assoc 2]
                       [[:cart :items 0 :discount] :assoc 0.1]]}
       {:section-path [:checkout :state]
        :section-kind :modified
        :patches      [[[:checkout :state] :assoc :paying]]}]

  `:section-kind` summarises the cluster: `:added` when every patch
  is `:assoc` at a path strictly deeper than the section-path
  (newly-introduced subtree); `:removed` when every patch is
  `:dissoc`; otherwise `:modified`. The agent uses this to skim
  cluster intent without walking every patch.

  ## Algorithm

  Mirrors the causa pass (`section_grouping.cljc` §3.1.1), recast
  over patches:

  1. **Trivial cases first.**
     - Empty patches → `[]`.
     - One patch at root path `[]` → one `[]`-headed section
       (whole-DB replacement).

  2. **Sort by path-as-pr-str.** `collect-patches` already emits
     in walk order; the sort makes the cluster algorithm
     deterministic across runs and tolerant of any future encoder
     reorder.

  3. **Coalesce siblings.** Walk the sorted patches; for each, take
     the longest common prefix with the running cluster. If the
     prefix is non-empty AND both paths sit within
     `max-coalesce-depth` (default 3) levels of it, fold the patch
     into the running cluster (with the prefix narrowed to the
     common ancestor). Otherwise start a new cluster.

     Root coalescence (empty common prefix) is reserved for the
     whole-DB replacement case — two unrelated root-key changes
     each get their own cluster rather than collapsing to a
     `[]`-headed section that defeats the path-breadcrumb premise.

  4. **Promote singletons to parent.** When a cluster has exactly
     one patch at a path deeper than 1 segment, head the section at
     the patch's parent path so the breadcrumb gives container
     context (a change to `[:user :prefs :theme]` heads as
     `[:user :prefs]` modified, not as `[:user :prefs :theme]`).
     Top-level singletons (path length 1) keep their full path —
     the breadcrumb `[:flash]` already carries identifying context
     at the root.

  5. **Classify each section.**
     - All `:dissoc` → `:section-kind :removed`.
     - All `:assoc` AND `:section-path` is the immediate parent of
       each patch's path (the cluster sits one level deep under the
       header) → `:section-kind :added` (newly-introduced
       container). Most-common case for newly-added subtrees.
     - Otherwise → `:section-kind :modified`. The conservative
       default; the agent reads `:modified` and knows the cluster
       carries a mix of inserts / changes / deletes.

     A more precise `:added` vs `:modified` split would require
     looking at `:db-before` to detect 'was this path previously
     absent?' — patches alone can't tell. The current rule is the
     cheapest accurate signal: a section is `:added` only when all
     its patches insert leaves under a fresh parent. False
     negatives (a wholly-added subtree shaped as multiple
     `:assoc`s under the same header that actually corresponds to
     a parent that already existed but was empty) are tagged
     `:modified` — semantically defensible.

  ## Whole-DB replacement

  A single `[[] :assoc <full-db>]` patch projects as one section
  headed at `[]` with `:section-kind :modified`. This is the
  signature of a `reset-frame-db!` or any wholesale root
  replacement; collapsing to one root section matches Causa's
  whole-DB rule (§3.1.1 step 4).

  ## Ordering

  Sections sort by `:section-path` (lexicographic, as
  `pr-str`-keyed). Stable across re-renders — the same cascade
  always produces the same section order.

  ## Cost

  - Sort: O(N log N) where N = patch count.
  - Coalesce: O(N) single pass over sorted patches.
  - Promote / classify: O(N) over clusters.

  All passes are linear in patch count — negligible vs the
  walk that produced the patches. Pure data → data; `.cljc`."
  (:require [clojure.string :as str]))

(def default-opts
  "Tunable knobs. Mirrors `tools/causa/.../section_grouping.cljc`'s
  defaults so the agent sees the same cluster shape Causa's panel
  renders. Tune against a real corpus in rf2-ogkh0."
  {:max-coalesce-depth 3})

;; ---- coalescence -------------------------------------------------------

(defn- common-prefix
  "Longest common prefix of two paths (vectors)."
  [a b]
  (let [n (min (count a) (count b))]
    (loop [i 0]
      (if (and (< i n) (= (nth a i) (nth b i)))
        (recur (inc i))
        (subvec (vec a) 0 i)))))

(defn- patch-path
  "Extract the path from a patch tuple. Patches are
  `[path :assoc v]` (3-tuple) or `[path :dissoc]` (2-tuple); the
  path is always the first element."
  [patch]
  (first patch))

(defn- patch-op
  "Extract the op keyword (`:assoc` / `:dissoc`) from a patch tuple."
  [patch]
  (second patch))

(defn- coalesce-into-cluster?
  "Decide whether `path` folds into the running cluster headed by
  `cluster-prefix`. The decision: both paths sit within
  `max-coalesce-depth` of their common prefix, AND that prefix is
  non-empty.

  Root prefix (empty common ancestor) is reserved for the
  whole-DB rule — unrelated root-key changes stand as separate
  clusters."
  [cluster-prefix path max-coalesce-depth]
  (let [prefix     (common-prefix cluster-prefix path)
        prefix-len (count prefix)]
    (and (pos? prefix-len)
         (<= (- (count cluster-prefix) prefix-len) max-coalesce-depth)
         (<= (- (count path) prefix-len) max-coalesce-depth)
         prefix)))

(defn- group-by-ancestor
  "Walk sorted patches; fold each into the running cluster when
  coalescence applies, else start a new cluster.

  Each cluster carries `{:prefix p :patches [patch ...]}`. The
  `:prefix` slot tracks the current common-ancestor path; the
  `:patches` slot accumulates the contributing patch tuples."
  [sorted-patches max-coalesce-depth]
  (reduce
    (fn [clusters patch]
      (let [path         (patch-path patch)
            last-cluster (peek clusters)]
        (if-let [new-prefix (and last-cluster
                                 (coalesce-into-cluster?
                                   (:prefix last-cluster)
                                   path
                                   max-coalesce-depth))]
          (conj (pop clusters)
                (-> last-cluster
                    (assoc :prefix new-prefix)
                    (update :patches conj patch)))
          (conj clusters {:prefix path :patches [patch]}))))
    []
    sorted-patches))

;; ---- singleton-promote --------------------------------------------------

(defn- promote-singleton
  "When a cluster has exactly one patch AND the patch path is deeper
  than 1 segment, head the section at the patch's parent — the
  breadcrumb gives container context. Top-level singletons (path
  length 1) keep their full path.

  Mirrors `tools/causa/.../section_grouping.cljc`'s singleton-promote
  worked example."
  [{:keys [prefix patches]}]
  (if (= 1 (count patches))
    (let [p (patch-path (first patches))]
      (if (> (count p) 1)
        {:prefix (subvec (vec p) 0 (dec (count p))) :patches patches}
        {:prefix p :patches patches}))
    {:prefix prefix :patches patches}))

;; ---- kind classification -----------------------------------------------

(defn- all-op?
  "True when every patch in `patches` has op `op`."
  [patches op]
  (every? #(= op (patch-op %)) patches))

(defn- patches-all-direct-child?
  "True when every patch's path is exactly one segment deeper than
  `prefix`. Together with all-`:assoc` this signals 'newly-added
  subtree under a fresh container' — the `:added` case."
  [patches prefix]
  (let [prefix-len (count prefix)]
    (every? (fn [patch]
              (let [path (patch-path patch)]
                (= (count path) (inc prefix-len))))
            patches)))

(defn- section-kind
  "Classify a cluster as `:added` / `:removed` / `:modified`. See
  §Classify each section in the ns docstring for the rules."
  [prefix patches]
  (cond
    (all-op? patches :dissoc)
    :removed

    (and (all-op? patches :assoc)
         (patches-all-direct-child? patches prefix))
    :added

    :else
    :modified))

;; ---- public entry ------------------------------------------------------

(defn- root-replacement?
  "True when the patch list is a single root-headed `:assoc` — the
  signature of a `reset-frame-db!` or wholesale root replacement.
  Projects to ONE `[]`-headed section."
  [patches]
  (and (= 1 (count patches))
       (let [[p op _] (first patches)]
         (and (= p [])
              (= op :assoc)))))

(defn group-patches-into-sections
  "Project a flat patch vector into path-headed cluster sections.

  Returns a sorted vector of `{:section-path [...] :section-kind
  <kw> :patches [patch ...]}` where:

    - `:section-path` is the cluster's breadcrumb (the shared
      ancestor for multi-patch clusters; the patch's parent for
      promoted singletons; the patch's own path for top-level
      singletons; `[]` for root replacement).
    - `:section-kind` is one of `:added` / `:removed` / `:modified`.
    - `:patches` is a sub-sequence of the input list.

  Concatenating every section's `:patches` reconstructs (modulo
  order within a section, which is stable per the sort) the
  flattened patch list — apply-patches replays it unchanged.

  Empty input ⇒ `[]`. Root replacement ⇒ one section at `[]`.

  Args:
    `patches` — the flat patch vector (e.g. from
                `re-frame.mcp-base.diff-encode/collect-patches`).
    `opts`    — optional `{:max-coalesce-depth 3}` (default).

  Pure data → data; JVM-runnable (`.cljc`)."
  ([patches] (group-patches-into-sections patches nil))
  ([patches opts]
   (let [{:keys [max-coalesce-depth]} (merge default-opts opts)]
     (cond
       (or (nil? patches) (empty? patches))
       []

       (root-replacement? patches)
       [{:section-path [] :section-kind :modified :patches (vec patches)}]

       :else
       (let [sorted   (vec (sort-by #(pr-str (patch-path %)) patches))
             clusters (group-by-ancestor sorted max-coalesce-depth)]
         (mapv (fn [cluster]
                 (let [{:keys [prefix patches]} (promote-singleton cluster)]
                   {:section-path prefix
                    :section-kind (section-kind prefix patches)
                    :patches      patches}))
               clusters))))))

;; ---- decoder helper ----------------------------------------------------

(defn sections->patches
  "Flatten sections back into the patch list.
  `(apply-patches db-before (sections->patches sections))`
  reproduces the original `:db-after`. Lossless inverse of
  `group-patches-into-sections`."
  [sections]
  (vec (mapcat :patches sections)))
