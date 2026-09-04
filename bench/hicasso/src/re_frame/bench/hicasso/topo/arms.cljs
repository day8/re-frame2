(ns re-frame.bench.hicasso.topo.arms
  "THE FOUR TOPOLOGY ARMS — one table, cut four ways (rf2-hic-036).

  The markup is written **once**, in [[row-markup]], and every arm calls
  it. That is the tournament's fairness guarantee and it is the same
  discipline [[re-frame.bench.hicasso.shapes.card]] uses to make shapes 2
  and 3 differ in exactly one authoring decision: if the four arms wrote
  their own rows, an arm-to-arm difference would be a difference of
  markup and the tournament would prove nothing about topology.

  | arm | boundaries | reads | what it buys |
  |---|---|---|---|
  | `fine` | `B + 1` | `2` per row | sparse invalidation |
  | `coarse` | `1` | one view-model | cheap mount, cheap bulk |
  | `chunked` | `⌈B/k⌉ + 1` | one slice per chunk | the middle |
  | `virtual` | `W + 1` | `2` per VISIBLE row | fewer rows in the DOM |

  ## Three arms are DOM-identical; the fourth is not, by construction

  `fine`, `coarse` and `chunked` render the same `B` rows into the same
  `<ul>`, so they are canonical-DOM comparable and the census gates them
  on it. **`virtual` renders `W` rows and cannot be**, because putting
  fewer rows in the DOM *is* the arm. The counterfactual worksheet says
  the same thing from the other side when it refuses to predict this arm
  at all (its N2): virtualization *\"is not a read-topology choice … no
  quantity on this page is about it.\"*

  So every `virtual` figure is a figure about **a different page**, and
  this file marks the arm exempt rather than letting a parity gate be
  quietly widened to admit it. A comparison that crosses that line is
  reported as crossing it.

  ## Body counts are taken on TWO independent instruments

  [[counters]] is this file's own — incremented at each body and at each
  row of markup. [[re-frame.bench.hicasso.arm1.runtime/body-runs]] is the
  runtime's, incremented inside `run-once` where a body is actually
  invoked. They share no traversal, so the census asserts them against
  each other: a memo bail-out that this file counted and the runtime did
  not would be a real disagreement rather than a rounding one."
  (:require [re-frame.bench.hicasso.arm1.runtime :refer [sub]]
            [re-frame.bench.hicasso.topo.model :as rf.bench.hicasso.topo.model])
  (:require-macros [re-frame.bench.hicasso.arm1.lang :refer [defview]]))

;; ---------------------------------------------------------------------------
;; The counters
;; ---------------------------------------------------------------------------

(def counters
  "`:list` / `:row` / `:chunk` are boundary body runs; `:markup` is rows
  of MARKUP built, which is the quantity that separates the arms on a
  sparse write — `fine` builds one row, `coarse` builds `B` in a single
  body. A witness that counted only bodies would call those two equal."
  (atom {:list 0 :row 0 :chunk 0 :markup 0}))

(defn reset-counters! [] (reset! counters {:list 0 :row 0 :chunk 0 :markup 0}) nil)

(defn runs [] @counters)

;; ---------------------------------------------------------------------------
;; The markup — written once
;; ---------------------------------------------------------------------------

(defn row-markup
  "One row. A plain function: called from a body it inlines, and it reads
  NO subscription — every value arrives as data, so the caller decides
  where the read edge lives and this function cannot tilt an arm.

  `:key` is in the attribute map because the row is a keyed list child in
  every arm: a direct child under `coarse`/`chunked`, and a boundary's
  root under `fine`/`virtual`. A key React does not need is a key React
  ignores, so one spelling serves all four."
  [{:keys [id label n draft]}]
  (swap! counters update :markup inc)
  [:li.topo-row {:key id :data-i id :data-testid (str "row-" id)}
   [:span.label label]
   [:em.n {:data-testid (str "n-" id)} n]
   [:span.draft {:data-testid (str "draft-" id)} draft]
   [:button.bump {:type "button" :data-testid (str "bump-" id) :on-click [:topo/bump id]} "+"]])

;; ---------------------------------------------------------------------------
;; Arm 1 — fine
;; ---------------------------------------------------------------------------

(defview fine-row
  "One row, as a boundary, holding its own two read edges."
  [{:keys [i]}]
  (swap! counters update :row inc)
  (row-markup (assoc (sub [:topo/row i]) :draft (sub [:topo/draft i]))))

(defview fine-list
  "`B` row boundaries under one list boundary. The list reads `:order`
  and nothing else, so no data write reaches it."
  [_]
  (swap! counters update :list inc)
  (into [:ul.topo-list {:data-testid "list"}]
        (map (fn [i] [fine-row {:key i :i i}]))
        (sub [:topo/order])))

;; ---------------------------------------------------------------------------
;; Arm 2 — coarse view-model
;; ---------------------------------------------------------------------------

(defview coarse-list
  "ONE boundary, one read. The per-instance join that `fine` spends
  `B·R` read edges on has moved into the subscription graph, whose
  internal edges are not memberships."
  [_]
  (swap! counters update :list inc)
  (into [:ul.topo-list {:data-testid "list"}]
        (map row-markup)
        (sub [:topo/view-model])))

;; ---------------------------------------------------------------------------
;; Arm 3 — chunked
;; ---------------------------------------------------------------------------

(defview chunk-view
  "One chunk of `k` rows, as a boundary reading its own slice. The rows
  inside it are inline markup, so the chunk holds ONE read edge for `k`
  rows."
  [{:keys [c]}]
  (swap! counters update :chunk inc)
  (into [:<>] (map row-markup) (sub [:topo/chunk c])))

(defview chunked-list
  "`⌈B/k⌉` chunk boundaries under one list boundary."
  [_]
  (swap! counters update :list inc)
  (into [:ul.topo-list {:data-testid "list"}]
        (map (fn [c] [chunk-view {:key c :c c}]))
        (range (sub [:topo/chunk-count]))))

;; ---------------------------------------------------------------------------
;; Arm 4 — windowed / virtualized
;; ---------------------------------------------------------------------------

(defview virtual-list
  "Only the visible window exists. Each visible row is a boundary with
  the same two reads `fine` gives every row — the arm changes how many
  rows there ARE, not how a row reads."
  [_]
  (swap! counters update :list inc)
  (into [:ul.topo-list {:data-testid "list"}]
        (map (fn [i] [fine-row {:key i :i i}]))
        (sub [:topo/visible])))

;; ---------------------------------------------------------------------------
;; The roster
;; ---------------------------------------------------------------------------

(def arm-ids
  "Pre-registered order. `:virtual` is last because it is the one arm
  whose figures are about a different page."
  [:fine :coarse :chunked :virtual])

(defn view-of
  "The root view for an arm."
  [arm]
  (case arm
    :fine    fine-list
    :coarse  coarse-list
    :chunked chunked-list
    :virtual virtual-list))

(defn dom-comparable?
  "Whether this arm's DOM may be compared against the others'. False for
  `:virtual`, by construction and not by tolerance."
  [arm]
  (not= :virtual arm))

(defn expected
  "What the census predicts for `arm` at `b`, from arithmetic rather than
  from a previous run's numbers.

  `:boundaries` counts registrations holding at least one read edge — the
  list plus, per arm, its row or chunk boundaries. A `coarse` page is one
  boundary because there is nothing beneath it holding a read."
  ([arm b] (expected arm b rf.bench.hicasso.topo.model/window-size))
  ([arm b w]
   (let [rendered (rf.bench.hicasso.topo.model/rendered-rows arm b w)]
     {:rendered-rows rendered
      :elements      (rf.bench.hicasso.topo.model/elements-for rendered)
      :edges         (inc (rf.bench.hicasso.topo.model/memberships arm b w))
      :boundaries    (case arm
                       :fine    (inc b)
                       :coarse  1
                       :chunked (inc (long (Math/ceil (/ b rf.bench.hicasso.topo.model/chunk-size))))
                       :virtual (inc (min b w)))})))
