(ns day8.re-frame2-causa.diff.hiccup
  "Hiccup-tree-diff micro-engine (rf2-i39w2 Phase 3 of rf2-abts7).

  Design source-of-truth:
  `ai/findings/2026-05-18-difftastic-in-causa.md` §3.3 + §4.

  ## Why a separate engine

  The generic annotated-tree walker (Phase 1) treats hiccup as a plain
  vector — `[tag attrs? & children]` — which produces noisy output for
  rendered hiccup:

    1. Slot 1 (the attrs map) deserves its own diff treatment so a
       single attribute change reads as one delta, not a 'slot 1
       modified' with the whole map stacked.
    2. Children should be tracked by `:key` metadata when present so a
       reordered keyed child surfaces as `:element-moved`, not as N
       modifications.
    3. Text children are leaves; element children recurse. The generic
       walker doesn't know the difference and recurses into strings as
       sequences (CLJS treats strings as seqable).
    4. **Function-valued props would flag every re-render as a diff.**
       Anonymous event handlers are created fresh per render; treating
       their reference change as `:modified` drowns the actual signal.
       Per §4.5 — opaque-by-default rule.

  ## Output shape

  Returns annotated nodes the renderer dispatches on via `::op`:

      {::op :same         :value v}
      {::op :added        :value v}
      {::op :removed      :value v}
      {::op :modified     :before bv :after av}
      {::op :element-changed
       :tag t
       :attrs-diff   <annotated-attrs-node>
       :children-diff <vector-of-annotated-children>}
      {::op :element-moved
       :value v
       :from-index N
       :to-index   M
       :key        k}
      {::op :fn-ref-changed
       :before bv
       :after  av}

  Each child of an element-changed carries either `:index` (positional
  children) or `:key` (keyed children) so the renderer can label the
  position.

  ## Opaque-by-default rule (§4.5 — load-bearing)

  Function-valued props are opaque. The vast majority of `:on-click` /
  `:on-change` / `:ref` handlers are anonymous fns created fresh per
  render; treating their reference change as `:modified` would drown
  the actual signal. The classifier returns `:same` for the
  (opaque, opaque) case by default. The opt-in toggle promotes that
  case to `:fn-ref-changed` for memoisation-issue diagnosis.

  Other opaque shapes covered by the same rule: channels, promises,
  atoms (CLJS Atom / Volatile), DOM nodes, React class components.

  ## Pure data → data

  CLJC so the JVM unit-test target picks it up. No runtime dependency
  on the substrate.

  ## Divergences from the design doc

  - Vector reorder detection on positional children: deferred per the
    bead's divergence allowance. Positional children diff as
    add/remove/modified by index; only KEYED children get
    :element-moved. Follow-on bead for LCS-driven move detection on
    positional children.
  - Per-attr-key fn-ref opt-in (§4.5 detection nuance): v1 ships one
    global toggle; per-attr-key granularity is a follow-on bead."
  (:require [clojure.set :as set]))

;; ---- predicates --------------------------------------------------------

(defn hiccup-vector?
  "True when `x` is a hiccup vector — a vector whose first element is a
  keyword tag (`:div`, `:span`, `:<>`, etc) or a function (Reagent
  functional component) or a Reagent class component.

  Per design §4.3 — Reagent functional components are treated as
  opaque (slot 0 is a fn); the predicate treats them as element-shape
  so the engine routes through the element-diff path. Mismatched
  shapes (`fn` vs keyword tag) end up as `:modified`."
  [x]
  (and (vector? x)
       (pos? (count x))
       (or (keyword? (nth x 0))
           (fn?      (nth x 0))
           (symbol?  (nth x 0)))))

(defn fragment?
  "True when `x` is a hiccup `[:<> ...]` fragment vector. Per design
  §4.3 — fragment children flatten into the parent's children-vec
  before diffing."
  [x]
  (and (hiccup-vector? x) (= :<> (nth x 0))))

(defn- attrs-map?
  "True when slot 1 of a hiccup vector is an attribute map. Hiccup
  optionally omits attrs; this discriminates `[:span 'text']` from
  `[:span {} 'text']`. Records ARE maps but are typically passed as
  payload (not as attrs); treat records as non-attrs."
  [x]
  (and (map? x) (not (record? x))))

(defn split-attrs+children
  "Return `[attrs children-vec]` for a hiccup element vector. Attrs is
  `{}` when absent; children-vec is `[]` when there are no children."
  [v]
  (when (hiccup-vector? v)
    (let [tail (subvec v 1)]
      (if (and (seq tail) (attrs-map? (first tail)))
        [(first tail) (vec (rest tail))]
        [{}           (vec tail)]))))

(defn- get-tag [v]
  (when (hiccup-vector? v) (nth v 0)))

;; ---- opaque predicate (§4.5) -------------------------------------------

(defn opaque?
  "True when `v` is treated as opaque for hiccup-diff purposes.

  Per design §4.5 — function-valued props are the headline case, but
  the same logic extends to:

    - channels (`cljs.core.async/chan`)
    - promises / Deferred / atoms (mutable references)
    - DOM nodes (rare but possible)
    - React component classes / fragments

  All default to `(opaque)` rendering with reference-change suppressed
  unless the opt-in toggle is on.

  The predicate is intentionally conservative — false-positive flags
  on values the user wanted to deep-diff are recoverable (the renderer
  shows the value); false negatives (treating a fn as not opaque)
  would drown signal in re-render noise."
  [v]
  (cond
    (nil? v)     false
    (fn? v)      true
    #?@(:cljs
        [(satisfies? IDeref v)              true   ; atoms, Volatiles, Reagent reactions
         (instance? js/Promise v)           true
         ;; DOM nodes — `nodeType` is the cross-browser marker.
         (and (object? v)
              (.-nodeType v))               true])
    #?@(:clj
        [(instance? clojure.lang.IDeref v)  true
         (instance? java.util.concurrent.CompletionStage v) true])
    :else        false))

;; ---- node constructors -------------------------------------------------

(defn- same-node    [v]              {::op :same     :value v})
(defn- added-node   [v]              {::op :added    :value v})
(defn- removed-node [v]              {::op :removed  :value v})
(defn- modified-node
  [bv av]
  {::op :modified :before bv :after av})

(defn- element-changed-node
  [tag attrs-diff children-diff]
  {::op :element-changed
   :tag tag
   :attrs-diff   attrs-diff
   :children-diff children-diff})

(defn- element-moved-node
  [v key from-index to-index]
  {::op :element-moved
   :value v
   :key   key
   :from-index from-index
   :to-index   to-index})

(defn- fn-ref-changed-node
  [bv av]
  {::op :fn-ref-changed :before bv :after av})

;; ---- per-prop classifier (§4.5) ----------------------------------------

(declare diff-hiccup-node)

(defn classify-prop
  "Diff a single hiccup prop value. The fn-ref toggle is consulted ONLY
  for the (opaque, opaque) case; all other cases are unaffected.

  Per design §4.5 — the load-bearing rule. Function-valued props honour
  opaque-by-default; the opt-in toggle promotes the reference-changed
  case to `:fn-ref-changed` for memoisation diagnosis."
  [before after {:keys [highlight-fn-ref-changes?] :as opts}]
  (cond
    ;; Both opaque (functions, atoms, channels, promises, DOM nodes).
    (and (opaque? before) (opaque? after))
    (if (and highlight-fn-ref-changes?
             (not (identical? before after)))
      (fn-ref-changed-node before after)
      (same-node after))

    ;; Opaque removed (was a fn, now nil).
    (and (opaque? before) (nil? after))
    (removed-node before)

    ;; Opaque added (was nil, now a fn).
    (and (nil? before) (opaque? after))
    (added-node after)

    ;; Type-shift: opaque → non-opaque or vice versa. The value type
    ;; changed; that's a real `:modified`.
    (or (and (opaque? before) (not (opaque? after)))
        (and (not (opaque? before)) (opaque? after)))
    (modified-node before after)

    ;; Both nil / both equal — `:same`.
    (= before after)
    (same-node after)

    ;; Otherwise delegate to the generic value-diff (style maps,
    ;; class strings, nested data). For props, the children-aware
    ;; hiccup walker isn't appropriate (a prop value is rarely a
    ;; hiccup tree); we use the modified-node leaf form here.
    :else
    (modified-node before after)))

;; ---- attrs-map diff ----------------------------------------------------

(defn- diff-attrs
  "Diff two attribute maps. Returns a node tagged `::op :attrs` whose
  `:children` is a vector of per-attr annotated nodes (each carrying
  a `:key` slot for the attr name). Each prop value is routed through
  `classify-prop` (§4.5) before being placed under its key."
  [attrs-before attrs-after opts]
  (let [bks   (set (keys attrs-before))
        aks   (set (keys attrs-after))
        all   (into bks aks)
        sks   (try (sort all)
                   (catch #?(:clj Exception :cljs :default) _ all))
        per-key
        (mapv
          (fn [k]
            (let [in-b? (contains? attrs-before k)
                  in-a? (contains? attrs-after k)
                  bv    (when in-b? (get attrs-before k))
                  av    (when in-a? (get attrs-after k))]
              (cond
                (and in-a? (not in-b?))
                (assoc (added-node av) :key k)

                (and in-b? (not in-a?))
                (assoc (removed-node bv) :key k)

                :else
                (assoc (classify-prop bv av opts) :key k))))
          sks)
        summary (reduce (fn [acc child]
                          (update acc (::op child) (fnil inc 0)))
                        {}
                        per-key)]
    {::op :attrs
     :children per-key
     :child-summary summary}))

(defn- attrs-has-changes?
  "True when the attrs-diff carries at least one non-`:same` child."
  [attrs-diff]
  (let [s (:child-summary attrs-diff)]
    (boolean (or (pos? (:added s 0))
                 (pos? (:removed s 0))
                 (pos? (:modified s 0))
                 (pos? (:fn-ref-changed s 0))))))

;; ---- children flattening + keying --------------------------------------

(defn- flatten-children
  "Flatten `[:<> ...]` fragments + realise lazy seqs into a single
  vector of children. Per design §4.3 — fragments are diff-transparent
  (the children diff against the parent's children directly).

  Lazy seqs cap at `cap` items so a non-realising producer doesn't
  hang the differ; per design §4.3 the cap matches the §007 perf
  budget of 200."
  ([xs] (flatten-children xs 200))
  ([xs cap]
   (vec
     (mapcat
       (fn [c]
         (cond
           (fragment? c)
           (let [[_ children] (split-attrs+children c)]
             (flatten-children children cap))

           (and (seq? c) (not (vector? c)))
           (flatten-children (vec (take cap c)) cap)

           :else
           [c]))
       xs))))

(defn- child-key
  "Read the `:key` slot from a hiccup element's attrs, or nil. The
  presence of `:key` is the move-tracking signal — design §4.1 ties
  identity to it."
  [c]
  (when (hiccup-vector? c)
    (let [[attrs _] (split-attrs+children c)]
      (get attrs :key))))

(defn- all-keyed?
  "True when EVERY element of `xs` is a hiccup vector whose attrs map
  carries a `:key`. Per design §4.1 — only the all-keyed case triggers
  identity-tracked diff; a mixed-keyed list falls back to positional."
  [xs]
  (and (seq xs)
       (every? (fn [c]
                 (and (hiccup-vector? c)
                      (some? (child-key c))))
               xs)))

;; ---- children diff: keyed ---------------------------------------------

(defn- index-by-key
  "Map `:key` → index for a vector of keyed hiccup children. Earlier
  occurrences of a duplicate key win (a duplicate is a bug in the
  user's hiccup; we don't try to repair it)."
  [xs]
  (reduce
    (fn [acc [i c]]
      (let [k (child-key c)]
        (if (contains? acc k) acc (assoc acc k i))))
    {}
    (map-indexed vector xs)))

(defn- diff-children-keyed
  "Both sides have :keyed children. Match by :key; emit per-child:

    :element-changed (or :same / :modified) — present in both,
                     diffed via diff-hiccup-node
    :element-moved   — present in both, key matches but index changed
    :added           — present in after only
    :removed         — present in before only"
  [bxs axs opts]
  (let [bidx (index-by-key bxs)
        aidx (index-by-key axs)
        bkeys (set (keys bidx))
        akeys (set (keys aidx))
        all-keys (vec (concat
                        ;; Preserve order: keys of `after` (their new
                        ;; positions) then deletions trailing.
                        (for [k (map child-key axs)] k)
                        (filter #(not (contains? akeys %)) (map child-key bxs))))]
    (vec
      (for [k all-keys]
        (let [bi (get bidx k)
              ai (get aidx k)
              bc (when bi (nth bxs bi nil))
              ac (when ai (nth axs ai nil))]
          (cond
            ;; In after only.
            (and ac (not bc))
            (assoc (added-node ac) :key k :index ai)

            ;; In before only.
            (and bc (not ac))
            (assoc (removed-node bc) :key k :index bi)

            ;; In both, same index, same content (pointer or =).
            (and (= bi ai) (or (identical? bc ac) (= bc ac)))
            (assoc (same-node ac) :key k :index ai)

            ;; In both, same index, content differs.
            (= bi ai)
            (assoc (diff-hiccup-node bc ac opts) :key k :index ai)

            ;; In both, different index — moved. If the content also
            ;; differs, the renderer paints both the move chip + the
            ;; inner diff; we attach the inner diff as `:inner-diff`.
            :else
            (let [inner (when-not (or (identical? bc ac) (= bc ac))
                          (diff-hiccup-node bc ac opts))]
              (cond-> (element-moved-node ac k bi ai)
                inner (assoc :inner-diff inner)))))))))

;; ---- children diff: positional ----------------------------------------

(defn- diff-children-positional
  "Positional walk: pairwise per index. The longer side carries
  leftover children as `:added` / `:removed`. Per the divergence
  allowance — no LCS / move-detection on positional children; a
  follow-on bead handles that.

  Strings + numbers + nil are scalar leaves (`:same` / `:modified` /
  `:added` / `:removed`); hiccup vectors recurse via diff-hiccup-node."
  [bxs axs opts]
  (let [n (max (count bxs) (count axs))]
    (vec
      (for [i (range n)]
        (let [in-b? (< i (count bxs))
              in-a? (< i (count axs))
              b     (when in-b? (nth bxs i))
              a     (when in-a? (nth axs i))]
          (cond
            (and in-a? (not in-b?))
            (assoc (added-node a) :index i)

            (and in-b? (not in-a?))
            (assoc (removed-node b) :index i)

            (or (identical? b a) (= b a))
            (assoc (same-node a) :index i)

            :else
            (assoc (diff-hiccup-node b a opts) :index i)))))))

;; ---- top-level walker --------------------------------------------------

(defn diff-hiccup-node
  "Diff two hiccup nodes — scalar (string / number / nil), hiccup
  element vector, or a fragment.

  `opts` keys:

    :highlight-fn-ref-changes?  (default false) — when true, opaque
                                  function references with different
                                  identity surface as
                                  `:fn-ref-changed` instead of `:same`."
  ([before after] (diff-hiccup-node before after {}))
  ([before after opts]
   (cond
     ;; Pointer-equality short-circuit. The most common case — a
     ;; render that returned identical hiccup to its previous render.
     (identical? before after)
     (same-node after)

     (= before after)
     (same-node after)

     ;; Both scalar (string / number / nil / keyword / boolean — anything
     ;; that's not a hiccup vector). At this point we know they're not
     ;; equal; emit a leaf-modified.
     (and (not (hiccup-vector? before)) (not (hiccup-vector? after)))
     (modified-node before after)

     ;; One side hiccup, the other a scalar — shape mismatch; the
     ;; renderer paints both sides.
     (not (and (hiccup-vector? before) (hiccup-vector? after)))
     (modified-node before after)

     :else
     (let [btag (get-tag before)
           atag (get-tag after)]
       (cond
         ;; Different tags — whole element is :modified (we don't try
         ;; to align attrs+children of different elements).
         (not= btag atag)
         (modified-node before after)

         ;; Same tag — diff attrs + children. Fragments (`:<>`) are
         ;; transparent containers; their attrs are typically empty,
         ;; their children flatten into the parent. Here we treat
         ;; same-tag fragments as element-changed with the standard
         ;; diff applied (flattening happens inside children-diff).
         :else
         (let [[battrs bchildren] (split-attrs+children before)
               [aattrs achildren] (split-attrs+children after)
               bch (flatten-children bchildren)
               ach (flatten-children achildren)
               attrs-diff    (diff-attrs battrs aattrs opts)
               children-diff (if (and (all-keyed? bch) (all-keyed? ach))
                               (diff-children-keyed     bch ach opts)
                               (diff-children-positional bch ach opts))]
           (element-changed-node atag attrs-diff children-diff)))))))

;; ---- changed? helper ---------------------------------------------------

(defn changed?
  "True when the annotated node represents any non-`:same` op.

  For an `:element-changed` container, the node is `changed?` only
  when it carries at least one non-`:same` attr or child — a synthetic
  empty container with all-same kids degrades to `false` so callers
  can use this as a cheap 'should I render the diff chrome' gate."
  [node]
  (let [op (::op node)]
    (cond
      (= op :same) false

      (= op :element-changed)
      (or (attrs-has-changes? (:attrs-diff node))
          (boolean (some (fn [c] (not= :same (::op c)))
                         (:children-diff node))))

      :else true)))

(defn op-of
  "Read the op of an annotated node."
  [node]
  (::op node))
