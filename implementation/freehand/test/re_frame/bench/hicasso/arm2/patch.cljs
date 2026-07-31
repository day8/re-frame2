(ns re-frame.bench.hicasso.arm2.patch
  "THE DIFFER — hiccup against hiccup, patch against the live DOM
  (rf2-2rtt6.10, architecture.md Arm 2).

  ## The previous tree is the previous hiccup

  This renderer keeps **no virtual DOM of its own**. The tree it diffs
  against is the hiccup the last render produced — the author's own data,
  retained as-is — and the nodes it patches are the real ones, reached by
  walking the live DOM in step with the two hiccup trees. There is no
  intermediate node object, no `VNode` record, no per-element instance
  cell. That is not an economy measure; it is the whole reason a PATCH
  arm can be lighter than a React arm rather than merely different, and
  it is why the retained-size question for this arm is *the boundary
  record and the index edges*, with nothing per element.

  The parallel walk is sound because of the emitter's 1:1 law (see
  [[re-frame.bench.hicasso.arm2.dom]]): every hiccup child occupies
  exactly one DOM node, `nil` included. Old child *i* is therefore
  `parent.childNodes[i]`, always, with no bookkeeping to keep in step.

  ## The three tiers, and what each covers

  | tier | entered when | what it costs |
  |---|---|---|
  | **1 — hole plan** | old and new share a static shape ([[re-frame.bench.hicasso.arm2.template]]) | one structural verify + one pass over the holes; no map walk, no name conversion, no recursion |
  | **2 — equality cutoff** | `identical?`, then `=`, on any subtree | one comparison, then nothing |
  | **3 — keyed diff** | everything else | props diff + [[re-frame.bench.hicasso.arm2.reconcile]]'s plan |

  Tier 2 is checked first at every child slot, because it is the cheapest
  and — with ClojureScript's structural sharing — the most often taken:
  an author's `for` over an unchanged collection returns hiccup whose
  unchanged rows are `identical?` to last render's, so a 300-row list
  where one row changed compares 300 pointers and patches one row.

  **Tier 2 is sound at a boundary child too**, and that is worth stating
  plainly because it looks like the memoization HD-006 refuses. It is
  not. HD-006 refuses *guessing* that equal arguments imply an equal
  rendering — true under React, where a boundary's data arrives through
  props. Here a boundary's data arrives through the index: a boundary
  whose subscriptions changed is in this commit's dirty set and re-runs
  from [[re-frame.bench.hicasso.arm2.runtime/commit!]] on its own,
  whether or not an ancestor reached it. Skipping an unchanged boundary
  element therefore skips *nothing that would have happened*; it is the
  index paying for itself, not a memo.

  ## Sequences and fragments splice; they are not nodes

  [[flatten-children]] realizes seqs and `[:<>]` fragments into the
  parent's own child vector before any diffing. A `for` inside a `for`
  splices twice; a fragment contributes its children. This is what lets
  the keyed reconciler see one flat list — the list an author thinks
  they wrote — instead of a tree of grouping nodes with their own
  identity rules.

  ## Raw hiccup in, lowering at the last moment

  The retained previous tree holds the author's **raw** props, not
  lowered ones. An event intent is a value (`[:dogfood/toggle 3]`), so an
  unchanged handler compares `=` and is never lowered at all: no closure
  allocated, no register written, nothing touched. Only a *changed*
  intent pays [[intent/lower-prop]]. A renderer that lowered before
  diffing would allocate a fresh closure per event prop per render and
  then discover it could not compare them."
  (:require [re-frame.bench.hicasso.arm2.dom :as dom]
            [re-frame.bench.hicasso.arm2.reconcile :as reconcile]
            [re-frame.bench.hicasso.arm2.template :as template]
            [re-frame.bench.hicasso.front.codec :as codec]
            [re-frame.bench.hicasso.front.intent :as intent]))

(declare mount-child patch-child! patch-element-slow!)

;; ---------------------------------------------------------------------------
;; The boundary seam
;; ---------------------------------------------------------------------------
;;
;; The differ knows that a marked `defview` product is a legal head and
;; that it occupies one node. It knows nothing else about boundaries —
;; not the index, not the sub layer, not the commit. The runtime installs
;; three functions here, in the same shape as the front half's evidence
;; sink: a holder, a setter, and a nil-checked call.

(defonce ^:private !boundary-ops (atom nil))

(defn set-boundary-ops!
  "Install the runtime's boundary operations:

      {:mount   (fn [hiccup] node)
       :patch   (fn [node old-hiccup new-hiccup] node)
       :unmount (fn [subtree-root-node] nil)}"
  [ops]
  (reset! !boundary-ops ops)
  nil)

(defn- boundary-ops []
  (or @!boundary-ops
      (throw (ex-info (str "A boundary element was rendered with no runtime installed. "
                           "[:rf.error/hicasso-patch-no-runtime]")
                      {:rf.error/id :rf.error/hicasso-patch-no-runtime
                       :where       'arm2.patch/boundary-ops
                       :reason      "set-boundary-ops! has not been called."
                       :recovery    :mount-through-the-runtime}))))

(defn unmount-subtree!
  "Tell the runtime that `node` and everything under it is leaving the
  document, so the boundaries inside it unmount and their index edges
  drop. Called before every removal and every replacement — the standing
  `zero-leaked-subscription-refcounts-after-teardown` assertion is this
  call being unconditional."
  [node]
  (when-some [ops @!boundary-ops] ((:unmount ops) node))
  nil)

;; ---------------------------------------------------------------------------
;; Hiccup shape
;; ---------------------------------------------------------------------------

(defn- fragment? [form]
  (and (vector? form) (pos? (count form)) (= :<> (nth form 0))))

(defn props-of
  "The props map of a hiccup vector, or nil. Slot 1 is props when it is a
  map — the codec's rule, restated for the emitter's own walk."
  [argv]
  (let [p (nth argv 1 nil)] (when (map? p) p)))

(defn first-child-index [argv] (if (map? (nth argv 1 nil)) 2 1))

(defn- splice!
  "Append one child form to the transient accumulator, splicing a seq or
  a fragment into it rather than treating it as a node."
  [acc c]
  (cond
    (seq? c)      (reduce splice! acc c)
    (fragment? c) (let [n (count c)]
                    (loop [i (first-child-index c) a acc]
                      (if (< i n) (recur (inc i) (splice! a (nth c i))) a)))
    :else         (conj! acc c)))

(defn flatten-children
  "The trailing forms of `argv`, with seqs and fragments spliced
  recursively, as one flat vector. Returns `[]` when there are none."
  [argv from]
  (let [n (count argv)]
    (loop [i from acc (transient [])]
      (if (>= i n)
        (persistent! acc)
        (recur (inc i) (splice! acc (nth argv i)))))))

(defn child-key
  "The `:key` a child declares, or nil. Read off the props map, which is
  the only place the component ABI (HD-016) puts it."
  [form]
  (when (vector? form)
    (when-some [p (props-of form)] (:key p))))

(defn- scalar? [x] (or (string? x) (number? x)))

(defn- same-type?
  "Can the node at this slot be patched in place, or must it be replaced?
  Same head under `=`, which covers both a tag keyword and a boundary
  function identity."
  [old new]
  (and (vector? old) (vector? new) (= (nth old 0) (nth new 0))))

;; ---------------------------------------------------------------------------
;; Props
;; ---------------------------------------------------------------------------

(defn- lower [k v] (if (dom/event-prop? k) (intent/lower-prop k v) v))

(defn- apply-raw-props!
  "Write a merged, still-raw props map onto a fresh node, lowering only
  at the event positions."
  [node props]
  (reduce-kv (fn [_ k v] (dom/set-prop! node k (lower k v) nil) nil) nil props)
  nil)

(defn- diff-raw-props!
  "Patch a node's props from one raw merged map to the next. The `=`
  comparison happens on the *author's values*, so an unchanged intent
  never becomes a closure."
  [node old-props new-props]
  (when-not (identical? old-props new-props)
    (reduce-kv (fn [_ k v]
                 (let [o (get old-props k ::absent)]
                   (when-not (= o v)
                     (dom/set-prop! node k (lower k v) (when (not= o ::absent) o))))
                 nil)
               nil
               new-props)
    (reduce-kv (fn [_ k _] (when-not (contains? new-props k) (dom/clear-prop! node k)) nil)
               nil
               old-props))
  nil)

;; ---------------------------------------------------------------------------
;; Mounting
;; ---------------------------------------------------------------------------

(defn- mount-element
  "Build one native element and its children. Tier 1 takes this path
  whenever the shape has a plan: the plan's template is cloned in one
  native call and only the holes are written."
  [argv]
  (if-some [plan (template/plan-for argv)]
    (template/mount-from-plan! plan argv lower)
    (let [parsed (codec/cached-parse (nth argv 0))
          props  (dom/merge-shorthand (or (props-of argv) {}) parsed)
          node   (js/document.createElement (.-tag parsed))]
      (apply-raw-props! node props)
      (doseq [c (flatten-children argv (first-child-index argv))]
        (.appendChild node (mount-child c)))
      node)))

(defn mount-child
  "Build the one node this child form occupies."
  [form]
  (cond
    (nil? form)     (dom/create-anchor)
    (false? form)   (dom/create-anchor)
    (scalar? form)  (dom/create-text form)
    (vector? form)  (let [head (nth form 0 nil)]
                      (cond
                        (codec/boundary-head? head) ((:mount (boundary-ops)) form)
                        (or (keyword? head) (string? head) (symbol? head)) (mount-element form)
                        :else
                        (throw (ex-info (str "Hiccup head " (pr-str head) " is not a valid element head. "
                                             "[:rf.error/hicasso-bad-head]")
                                        {:rf.error/id :rf.error/hicasso-bad-head
                                         :where       'arm2.patch/mount-child
                                         :reason      "Head must be a tag keyword or a view minted by defview."
                                         :recovery    :supply-a-valid-hiccup-head}))))
    (true? form)    (throw (ex-info (str "`true` is not a renderable child. "
                                         "[:rf.error/hicasso-true-child]")
                                    {:rf.error/id :rf.error/hicasso-true-child
                                     :where       'arm2.patch/mount-child
                                     :reason      "nil and false render nothing; true is an error (HD-016)."
                                     :recovery    :use-nil-or-false}))
    :else           (dom/create-text form)))

;; ---------------------------------------------------------------------------
;; Children
;; ---------------------------------------------------------------------------

(defn- patch-children!
  "Reconcile one parent's children. The ordering decisions are all
  [[reconcile/plan]]'s; this function only applies them, carrying one
  anchor as it walks the placements backwards."
  [parent old-forms new-forms]
  (let [old-nodes (let [cs (.-childNodes parent)
                        n  (.-length cs)]
                    (loop [i 0 acc (transient [])]
                      (if (< i n) (recur (inc i) (conj! acc (.item cs i))) (persistent! acc))))
        {:keys [removes places]} (reconcile/plan (mapv child-key old-forms) (mapv child-key new-forms))]
    (doseq [i removes]
      (let [node (nth old-nodes i)]
        (unmount-subtree! node)
        (dom/remove! parent node)))
    (loop [ps (seq places) anchor nil]
      (when ps
        (let [{:keys [op new old]} (first ps)
              node (case op
                     :mount (let [n (mount-child (nth new-forms new))]
                              (dom/insert! parent n anchor)
                              n)
                     :keep  (patch-child! parent (nth old-nodes old) (nth old-forms old) (nth new-forms new))
                     :move  (let [n (patch-child! parent (nth old-nodes old) (nth old-forms old) (nth new-forms new))]
                              (dom/insert! parent n anchor)
                              n))]
          (recur (next ps) node))))
    nil))

;; ---------------------------------------------------------------------------
;; One child slot
;; ---------------------------------------------------------------------------

(defn- patch-element!
  "Patch a native element in place. Tier 1 when the node's stamp says it
  was last rendered from exactly this shape; tier 3 otherwise, after
  which the node is re-stamped so the *next* render can take tier 1."
  [node old new]
  (let [plan (template/plan-for new)]
    (if (template/fits? plan node)
      (template/patch-from-plan! plan node old new lower)
      (do (patch-element-slow! node old new)
          (template/stamp! node (:sig plan)))))
  node)

(defn patch-element-slow!
  "Tier 3 on one element: a raw props diff and a keyed children plan."
  [node old new]
  (let [parsed (codec/cached-parse (nth new 0))]
    (diff-raw-props! node
                     (dom/merge-shorthand (or (props-of old) {}) parsed)
                     (dom/merge-shorthand (or (props-of new) {}) parsed))
    (patch-children! node
                     (flatten-children old (first-child-index old))
                     (flatten-children new (first-child-index new)))
    nil))

(defn- replace-child!
  "Build the new node, swap it in, and unmount everything the old one
  held."
  [parent node new]
  (let [fresh (mount-child new)]
    (dom/replace! parent node fresh)
    (unmount-subtree! node)
    fresh))

(defn patch-child!
  "Bring the node occupying one child slot from `old` to `new`, and
  return the node now occupying it (the same node whenever the slot was
  patched in place).

  Tier 2 is the first two lines, and the ones that carry the bulk
  witness."
  [parent node old new]
  (cond
    (identical? old new) node
    (= old new)          node

    (and (scalar? old) (scalar? new))
    (do (set! (.-nodeValue node) (str new)) node)

    (and (or (nil? old) (false? old)) (or (nil? new) (false? new)))
    node

    (same-type? old new)
    (if (codec/boundary-head? (nth new 0))
      ((:patch (boundary-ops)) node old new)
      (patch-element! node old new))

    :else
    (replace-child! parent node new)))

;; ---------------------------------------------------------------------------
;; Root
;; ---------------------------------------------------------------------------

(defn render-root!
  "Render `new` into `container`, against the `old` hiccup the container
  last held (nil on a cold mount). Returns the node the root occupies.

  The container is the renderer's own: it owns every child of it, which
  is what makes the parallel walk legal from the very first slot."
  [container old new]
  (if (nil? old)
    (let [node (mount-child new)]
      (.appendChild container node)
      node)
    (patch-child! container (.-firstChild container) old new)))
