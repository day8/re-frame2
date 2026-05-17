(ns day8.re-frame2-causa.diff.annotated-tree
  "Structural-diff engine — annotated-tree walker (rf2-gfxmk Phase 1 of
  rf2-abts7).

  Design source-of-truth: `ai/findings/2026-05-18-difftastic-in-causa.md`
  §2.5.

  ## Output shape

  Walk `before` + `after` recursively, emit an annotated mirror tree
  where every node carries `:rf.causa.diff/op` keyed to one of:

      {::op :same      :value v}                ; values equal
      {::op :added     :value v}                ; only in after
      {::op :removed   :value v}                ; only in before
      {::op :modified  :before bv :after av}    ; leaf change
      {::op :children  :tag :map|:vec|:set
                       :value v
                       :children [...annotated children...]
                       :child-summary {:added n :removed n
                                       :modified n :children n
                                       :same n}}

  Container nodes carry `:tag` so the renderer knows the structural
  delimiters; `:children` carries the annotated children vector; the
  `:child-summary` slot is what the grouping pass + the renderer read
  to decide section coalescence and the collapsed-state chip.

  Map children carry `:key`; vector / set children carry `:index`.

  ## Pointer-equality short-circuit

  Top of every recursion: `(identical? before after)` → `:same` with no
  recursion. The structural-sharing walk is O(changed paths), not
  O(value size) — same guarantee `diff-paths` provides today.

  ## Sentinel handling

  Per `ai/findings/2026-05-18-difftastic-in-causa.md` §3.1 §Sentinel
  handling rules 1+3: when both sides are the same redacted sentinel,
  the structural `=` check returns true → `:same`. The elision contract
  is preserved — the diff renderer never overrides it.

  ## JVM-runnable

  Pure data → data. `.cljc` so the JVM unit-test target picks it up.

  ## Cost

  - Pointer-equality short-circuit at every level (O(changed) work)
  - Map walk: union of keys; per-key one classify + maybe recurse
  - Vector walk: positional pairwise (move detection deferred to a
    follow-on; see §2.6 of the design doc and the divergence note in
    `rf2-gfxmk`)
  - Set walk: difference + intersection; no positional pairing

  ## Move detection deferral

  Per the bead's divergence allowance, scalar / map-key changes are
  diffed correctly first; vector reorder detection is deferred to a
  follow-on so the engine ships clean. A reordered vector currently
  diffs as N modifications + N removed slots — same fidelity as the
  current `diff-paths` walker today."
  (:require [clojure.set :as set]))

;; ---- node constructors -------------------------------------------------

(defn- same-node
  "Build a `:same` node for pointer- or value-equal subtrees."
  [v]
  {::op :same :value v})

(defn- added-node
  ([v]      {::op :added :value v})
  ([v key-or-idx kind]
   (assoc {::op :added :value v} kind key-or-idx)))

(defn- removed-node
  ([v]      {::op :removed :value v})
  ([v key-or-idx kind]
   (assoc {::op :removed :value v} kind key-or-idx)))

(defn- modified-node
  ([before after]
   {::op :modified :before before :after after})
  ([before after key-or-idx kind]
   (assoc {::op :modified :before before :after after} kind key-or-idx)))

(defn- empty-summary
  []
  {:added 0 :removed 0 :modified 0 :children 0 :same 0})

(defn- summarise-children
  "Tally child ops into a `{:added n :removed n ...}` summary. Used by
  the grouping pass + the renderer to decide section coalescence and
  the collapsed-state chip."
  [children]
  (reduce (fn [acc child]
            (update acc (::op child) (fnil inc 0)))
          (empty-summary)
          children))

(defn- children-node
  "Build a container node tagged `:children` carrying the recursive
  annotated children + a summary."
  [value tag children]
  {::op :children
   :tag tag
   :value value
   :children children
   :child-summary (summarise-children children)})

(defn- has-changes?
  "True when the summary indicates at least one non-`:same` child."
  [summary]
  (boolean (or (pos? (:added summary 0))
               (pos? (:removed summary 0))
               (pos? (:modified summary 0))
               (pos? (:children summary 0)))))

;; ---- recursive walker --------------------------------------------------

(declare diff-tree)

(defn- diff-map
  "Walk both maps' key-union; emit per-key annotated children."
  [before after]
  (let [bks   (set (keys before))
        aks   (set (keys after))
        all   (into bks aks)
        ;; Iterate using `concat` over sorted keys for stable output.
        ;; The grouping pass + tests rely on deterministic ordering.
        sks   (try (sort all)
                   (catch #?(:clj Exception :cljs :default) _
                     all))]
    (reduce
      (fn [acc k]
        (let [in-b? (contains? before k)
              in-a? (contains? after k)
              bv    (when in-b? (get before k))
              av    (when in-a? (get after k))]
          (cond
            (and in-a? (not in-b?))
            (conj acc (added-node av k :key))

            (and in-b? (not in-a?))
            (conj acc (removed-node bv k :key))

            (identical? bv av)
            (conj acc (assoc (same-node av) :key k))

            (= bv av)
            (conj acc (assoc (same-node av) :key k))

            ;; Both present, not equal → recurse via the general walker.
            ;; The walker either emits a container, a `:modified`, or a
            ;; `:same` depending on the children. We tag the result with
            ;; the current key.
            :else
            (let [child (diff-tree bv av)]
              (conj acc (assoc child :key k))))))
      []
      sks)))

(defn- diff-vector
  "Walk both vectors positionally — pairwise per index, the longer side
  carries leftover children as `:added` / `:removed`. Move detection is
  deferred (see ns docstring).

  Works for any sequential collection (vector, list) — the caller
  reifies via `vec` if needed."
  [before after]
  (let [bv    (vec before)
        av    (vec after)
        n     (max (count bv) (count av))]
    (reduce
      (fn [acc i]
        (let [in-b? (< i (count bv))
              in-a? (< i (count av))
              b     (when in-b? (nth bv i))
              a     (when in-a? (nth av i))]
          (cond
            (and in-a? (not in-b?))
            (conj acc (added-node a i :index))

            (and in-b? (not in-a?))
            (conj acc (removed-node b i :index))

            (identical? b a)
            (conj acc (assoc (same-node a) :index i))

            (= b a)
            (conj acc (assoc (same-node a) :index i))

            :else
            (let [child (diff-tree b a)]
              (conj acc (assoc child :index i))))))
      []
      (range n))))

(defn- diff-set
  "Walk both sets: `:added` for elements only in after, `:removed` for
  elements only in before, `:same` for the intersection. No positional
  pairing (sets are unordered); no `:modified` (sets have no key-based
  identity at members)."
  [before after]
  (let [adds    (sort-by pr-str (set/difference after before))
        rems    (sort-by pr-str (set/difference before after))
        commons (sort-by pr-str (set/intersection before after))]
    (vec
      (concat
        (map added-node   adds)
        (map removed-node rems)
        (map same-node    commons)))))

(defn- collection-tag
  "Discriminate which structural-collection node we're building."
  [v]
  (cond
    (map? v)        :map
    (vector? v)     :vec
    (set? v)        :set
    (sequential? v) :vec   ; treat lists as vec-shaped
    :else           nil))

(defn- container?
  "True when v should recurse rather than leaf-modify. Records ARE maps
  in CLJS/JVM; for diff purposes we treat IRecord instances as opaque
  leaves so `(=` semantics apply at the record level — recursing into
  arbitrary record fields is rarely what the user wants for a diff."
  [v]
  (and (some? (collection-tag v))
       (not (record? v))))

(defn diff-tree
  "Walk `before` + `after` recursively, emit the annotated-tree shape
  described in the ns docstring. Pointer-equality short-circuits at
  every level; structural recursion stops at the first non-collection
  leaf.

  Returns the root annotated node. Map children carry `:key`, vector /
  set children carry `:index`, the root carries neither.

  Pure data → data. JVM-runnable."
  [before after]
  (cond
    ;; Pointer-equal subtrees — no recursion.
    (identical? before after)
    (same-node after)

    ;; Value-equal but pointer-different (re-allocated identicals from
    ;; JSON round-trip, sentinel re-allocation, etc). Per §3.1 sentinel
    ;; rule 3: if both sides equal a redacted sentinel, this is `:same`
    ;; — correct behaviour per the elision contract.
    (= before after)
    (same-node after)

    ;; Both maps — walk children. Empty map ↔ non-empty map: still goes
    ;; through here because both are map?; the walker emits `:added` /
    ;; `:removed` children for the difference and tags the container
    ;; with the summary. Empty map ↔ empty map handled by the `=` branch
    ;; above.
    (and (map? before) (map? after)
         (not (record? before)) (not (record? after)))
    (let [children (diff-map before after)
          summary  (summarise-children children)]
      ;; If no children-changes (e.g. one side has only `:same` children
      ;; via `=` short-circuit), the node degrades to `:same` — but this
      ;; case is caught by the `=` branch above. Still defensive.
      (if (has-changes? summary)
        (children-node after :map children)
        (same-node after)))

    (and (vector? before) (vector? after))
    (let [children (diff-vector before after)
          summary  (summarise-children children)]
      (if (has-changes? summary)
        (children-node after :vec children)
        (same-node after)))

    (and (set? before) (set? after))
    (let [children (diff-set before after)
          summary  (summarise-children children)]
      (if (has-changes? summary)
        (children-node after :set children)
        (same-node after)))

    ;; Mixed shape (one side is a map, the other a vector, etc.) — treat
    ;; as a `:modified` leaf. The renderer paints both sides; recursing
    ;; into a shape-mismatched pair would produce nonsense.
    ;;
    ;; Same for the "one collection, one leaf" case — :modified at this
    ;; node, both values preserved.
    (or (and (container? before) (not (container? after)))
        (and (not (container? before)) (container? after))
        (and (container? before) (container? after)))
    (modified-node before after)

    ;; Both non-collection leaves, not equal — `:modified` with both
    ;; sides preserved.
    :else
    (modified-node before after)))

;; ---- helpers for downstream consumers ----------------------------------

(defn changed?
  "True when the annotated node represents any non-`:same` op (rooted
  diff is non-empty)."
  [node]
  (let [op (::op node)]
    (or (not= op :same)
        ;; A `:children` summary with non-zero non-:same counts is a
        ;; "changed" container. This shouldn't happen given `diff-tree`
        ;; degrades non-changed containers to `:same`, but defensive.
        (and (= op :children)
             (has-changes? (:child-summary node))))))

(defn op-of
  "Read the op of an annotated node."
  [node]
  (::op node))

(defn child-key
  "Read the `:key` slot (map child) or `:index` slot (vector / set
  child) of an annotated node. Returns nil for the root."
  [node]
  (or (:key node) (:index node)))
