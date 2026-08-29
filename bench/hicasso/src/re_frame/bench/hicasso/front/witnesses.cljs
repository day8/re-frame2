(ns re-frame.bench.hicasso.front.witnesses
  "THE WITNESS SET, as data (rf2-2rtt6.8).

  validation.md §P1 pins one measurement discipline above all others:
  *same React version, build settings, tree, frame, queries, writes, and
  data across arms*. Two arms enumerating the witness set from two
  hand-written lists satisfies that on the day it is written and stops
  satisfying it the first time one list gains a row. So the set is
  declared once, here, as data — and each arm reads the roster rather
  than restating it.

  This namespace holds **no runtime and mounts nothing**. It is the
  roster, the two scaling curves computed from their stated bounds, and
  the shape parameters an arm needs to build a page. What each arm does
  with a row is the arm's business; *which rows exist* is not.

  ## The families

  Each row names the family it belongs to, because the red-zone ratios
  are set per family, before candidate results are opened (validation.md
  §The bar), and a row whose family is implicit is a row whose gate is
  implicit.

  ## Reading a row

      :id        the stable identifier an arm's result rows cite
      :family    the red-zone family this row is gated under
      :what      one sentence, in the voice a result table can print
      :shape     the parameters an arm needs to build the page
      :asserts   what must be true regardless of the clock
      :gates     the budget or kill-criterion rows this witness feeds

  `:asserts` is the half that does not depend on a number, and it is
  first-class here for the reason validation.md gives: a candidate that
  is fast and wrong has failed, and the assertions are what say so."
  (:require [clojure.set :as set]))

;; ---------------------------------------------------------------------------
;; The two scaling curves, separated (validation.md §Witness set)
;; ---------------------------------------------------------------------------

(def reads-per-boundary
  "The 1/3/7/20 ladder. Seven is the census's archetype and the number
  HD-020's ≤2-hook budget is argued against; twenty is the stress rung."
  [1 3 7 20])

(def boundary-counts
  "The bulk sizes validation.md names — 100 and 300 — plus the small rung
  that makes a mount figure legible next to them."
  [10 100 300])

(def fixed-reads-curve
  "**Fixed reads × growing boundaries.** One read per boundary, the
  boundary count growing. Isolates the per-boundary cost."
  (mapv (fn [n] {:boundaries n :reads 1}) boundary-counts))

(def fixed-boundaries-curve
  "**Fixed boundaries × growing reads.** One hundred boundaries, the read
  count growing across the ladder. Isolates the per-read cost.

  The heap ladder is measured with **distinct queries per boundary**
  (Q = E), which rf2-2rtt6.16 established as the mandatory worst case:
  a candidate must not be able to pass the shell budget by amortising one
  subscription set across four roots."
  (mapv (fn [r] {:boundaries 100 :reads r :distinct-queries? true}) reads-per-boundary))

;; ---------------------------------------------------------------------------
;; The roster
;; ---------------------------------------------------------------------------

(def witnesses
  "Every witness validation.md §Witness set names, in declaration order."
  [{:id      :bulk/one-commit
    :family  :bulk
    :what    "300 boundaries, one commit, the whole page dirty"
    :shape   {:boundaries 300 :reads 1 :write :broad}
    :asserts [:every-boundary-re-ran :dom-matches-the-model]
    :gates   [:K2 :ship-bar-clock]}

   {:id      :bulk/narrow-write
    :family  :bulk
    :what    "300 boundaries, one commit dirtying one subscription"
    :shape   {:boundaries 300 :reads 1 :write :narrow}
    :asserts [:exactly-one-boundary-re-ran :dom-matches-the-model]
    :gates   [:K2 :ship-bar-clock]}

   {:id      :mount/list-and-form
    :family  :mount
    :what    "cold mount of the reference list + form"
    :shape   {:boundaries 300 :reads 1 :cold? true}
    :asserts [:dom-matches-the-model]
    :gates   [:K1 :ship-bar-clock]}

   {:id      :scaling/fixed-reads
    :family  :heap
    :what    "fixed reads, growing boundaries — the per-boundary curve"
    :shape   {:curve fixed-reads-curve}
    :asserts [:no-retained-per-occurrence-objects-after-teardown]
    :gates   [:K3 :shell-budget]}

   {:id      :scaling/fixed-boundaries
    :family  :heap
    :what    "fixed boundaries, growing reads — the per-read curve, distinct queries"
    :shape   {:curve fixed-boundaries-curve}
    :asserts [:no-retained-per-occurrence-objects-after-teardown]
    :gates   [:K3 :per-read-budget]}

   {:id      :controlled/grid-100
    :family  :controlled
    :what    "the 100-cell controlled grid — the synchronous door (HD-019)"
    :shape   {:cells 100 :controlled? true}
    :asserts [:same-turn-echo
              :mid-string-caret
              :selection-preserved
              :ime-composition-commits-nothing
              :unchanged-model-rejection
              :async-normalisation]
    :gates   [:K4 :per-keystroke-budget]}

   {:id      :keyed/insert-delete-reorder
    :family  :correctness
    :what    "keyed insert, delete and reorder against one list"
    :shape   {:boundaries 100 :keyed? true}
    :asserts [:identity-follows-the-key :no-node-recreated-on-reorder]
    :gates   [:component-abi]}

   {:id      :abandoned/query-identity
    :family  :correctness
    :what    "a boundary's query identity changes through an abandoned render"
    :shape   {:boundaries 1 :abandon-render? true}
    :asserts [:no-edge-from-the-abandoned-render :index-matches-the-winning-render]
    :gates   [:index-laws :HD-002-ownership]}

   {:id      :foreign/host-and-error-boundary
    :family  :correctness
    :what    "a foreign hook/context/ref component beside a real error boundary"
    :shape   {:foreign? true :error-boundary? true}
    :asserts [:foreign-component-renders :error-boundary-catches-and-resets]
    :gates   [:HD-020-error-boundary :host-boundary-budget]}

   {:id      :lifecycle/strict-abandoned-teardown-hmr
    :family  :correctness
    :what    "StrictMode, an abandoned first mount, root teardown, an HMR body swap"
    :shape   {:strict-mode? true :hmr? true}
    :asserts [:zero-leaked-subscription-refcounts-after-teardown
              :unchanged-hot-read-performs-no-new-attach-or-release
              :hmr-preserves-root-frame-and-app-db]
    :gates   [:HD-021-execution-contract]}])

;; ---------------------------------------------------------------------------
;; Reading the roster
;; ---------------------------------------------------------------------------

(def by-id (into {} (map (juxt :id identity)) witnesses))

(defn of-family [family] (filterv #(= family (:family %)) witnesses))

(def families (into #{} (map :family) witnesses))

(defn gates
  "Every gate any witness feeds. An arm that reports fewer rows than this
  has left a gate unwitnessed."
  []
  (into #{} (mapcat :gates) witnesses))

(defn missing
  "The witness ids an arm has NOT reported, given the ids it has. The one
  function the arms actually call: `(missing (map :id my-rows))` is the
  partial-run banner's input, and an empty result is the only thing that
  licenses a complete-run claim."
  [reported-ids]
  (set/difference (into #{} (map :id) witnesses) (set reported-ids)))
