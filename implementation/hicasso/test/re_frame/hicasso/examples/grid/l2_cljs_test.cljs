(ns re-frame.hicasso.examples.grid.l2-cljs-test
  "L2 — THE GRID'S BODIES, AND THE READ TOPOLOGY THE SCALING CLAIM RESTS
  ON (rf2-hic-078).

  Specification §6 asks that *narrow-update body work scales with changed
  rows rather than all mounted rows*. `scaling-dom-cljs-test` measures
  the bodies; this file establishes the thing that makes the measurement
  come out — the read topology — and it establishes it in the one way a
  structural tier can.

  `ht/tree` refuses a read no fixture answers. So the fixture map handed
  to each body IS that body's read set, and four rows here are nothing
  but an argument about which map is enough:

  | body | fixture map | what an over-large map would mean |
  |---|---|---|
  | `cell` | one `[::subs/cell r c]` | a cell reading a second address is a second notification group |
  | `row-total` | one `[::subs/row-total r]` | — |
  | `grid-row` | `[::subs/dimensions]` alone | a row body reading a cell puts ten cells' keystrokes on the row's path |
  | `grid` | `[::subs/dimensions]` alone | the parent on every keystroke in the page |

  A body that grew a read reds this file before anything is mounted,
  which is the earliest a topology regression can be caught."
  (:require [cljs.test :refer-macros [deftest is testing use-fixtures]]
            [re-frame.adapter.uix :as uix-adapter]
            [re-frame.hicasso :as h]
            [re-frame.hicasso.examples.grid.events :as events]
            [re-frame.hicasso.examples.grid.subs :as subs]
            [re-frame.hicasso.examples.grid.views :as views]
            [re-frame.hicasso.test :as ht]
            [re-frame.test-support :as test-support]))

(use-fixtures :each
  (test-support/make-reset-runtime-fixture
    {:adapter       uix-adapter/adapter
     :ambient-frame nil}))

(defn- tagged [tree tag] (ht/find tree #(= tag (:tag %))))

;; ---------------------------------------------------------------------------
;; The cell — the whole of the typing surface
;; ---------------------------------------------------------------------------

(deftest a-cell-reads-one-address-and-writes-one-intent
  (let [tree  (ht/tree [views/cell {:row 3 :col 4}]
                       {:subs {[::subs/cell 3 4] "34"}})
        attrs (ht/attrs (tagged tree :input))]
    (is (= "34" (:value attrs)))
    (is (= [::events/edit 3 4 ::h/value] (:on-input attrs))
        "a THREE-argument positional intent. It is positional for the
         reason the editor's two-argument ones are — a marker below the
         top level of an intent vector is not substituted — and the
         collision does not soften as the payload grows; three positional
         arguments is exactly where a payload map would have started to
         pay for itself. rf2-hic-025 finding 1, confirmed at a second
         arity")
    (is (= "cell-3-4" (:id attrs)))
    (is (= "cell 3,4" (:aria-label attrs))
        "every one of a hundred controls has an accessible name, and it
         comes from `events/cell-label` so that a witness looking a cell
         up and the view rendering it cannot spell it differently")))

(deftest a-cell-is-the-editors-text-field-with-a-coordinate
  ;; The claim the grid exists to make. Both are `:value` off a
  ;; subscription and an intent vector at `:on-input`, and the difference
  ;; between one field and a hundred is the loop that writes them.
  (let [form [:input {:type "text" :value "34"
                      :on-input [::events/edit 3 4 ::h/value]}]]
    (is (true? (ht/controlled? form))
        "the hundredth cell installs the same converge shadow as the
         first, and there is no second way of writing a field for when
         there are a hundred of them")
    (is (nil? (ht/revision form))
        "and the grid carries no reset trigger anywhere — it has no
         discard, so `::h/revision` never appears in this application")))

(deftest the-row-total-reads-its-own-row-and-nothing-else
  (let [tree (ht/tree [views/row-total {:row 2}]
                      {:subs {[::subs/row-total 2] 42}})]
    (is (= "42" (ht/text tree)))
    (is (= "2" (:data-total (ht/attrs tree))))))

;; ---------------------------------------------------------------------------
;; The layout bodies — the half of the topology that is an ABSENCE
;; ---------------------------------------------------------------------------

(deftest a-row-body-reads-the-dimensions-and-no-cell
  (let [tree  (ht/tree [views/grid-row {:row 2}]
                       {:subs {[::subs/dimensions] {:rows 10 :cols 4}}})
        cells (ht/find-all tree #(= "re-frame.hicasso.examples.grid.views/cell"
                                    (:view-id %)))]
    (is (= 4 (count cells))
        "one call per column — and a CALL, so the child's body did not run
         and its read is not on this fixture map")
    (is (= [[2 0] [2 1] [2 2] [2 3]]
           (mapv (fn [c] [(:row (ht/attrs c)) (:col (ht/attrs c))]) cells)))
    (is (= ["0" "1" "2" "3"] (mapv :key cells))
        "keyed by column. A list keyed by anything that moves reuses the
         wrong element the moment the order changes, and on a page of
         controlled fields that is a caret landing in another cell")
    (is (= 1 (count (ht/find-all tree #(= "re-frame.hicasso.examples.grid.views/row-total"
                                          (:view-id %)))))
        "and one total, at the end of the row")))

(deftest the-grid-body-reads-the-dimensions-and-no-cell
  ;; THE ROW THE SCALING CLAIM RESTS ON. The grid's whole fixture map is
  ;; one read of a value nothing writes while anybody is typing, so a
  ;; keystroke cannot reach this body. A `[::subs/all-cells]` here — the
  ;; guide's named anti-shape — would put the parent, and a props compare
  ;; over every row, on every keystroke's path.
  (let [tree (ht/tree [views/grid {}]
                      {:subs {[::subs/dimensions] {:rows 3 :cols 3}}})
        rows (ht/find-all tree #(= "re-frame.hicasso.examples.grid.views/grid-row"
                                   (:view-id %)))]
    (is (= 3 (count rows)))
    (is (= [0 1 2] (mapv (comp :row ht/attrs) rows)))
    (is (= ["0" "1" "2"] (mapv :key rows)))
    (is (some? (tagged tree :table)))))

;; ---------------------------------------------------------------------------
;; A hundred of them
;; ---------------------------------------------------------------------------

(deftest a-hundred-cells-carry-a-hundred-distinct-addresses
  ;; Structural, and cheap: the grid's calls are enumerated at the real
  ;; size, and every cell's coordinate pair is distinct. A witness that
  ;; measured scaling on a grid whose cells shared an address would be
  ;; measuring an accident.
  (let [dims  {:rows 10 :cols 10}
        rows  (range (:rows dims))
        cells (into []
                    (mapcat (fn [row]
                              (let [tree (ht/tree [views/grid-row {:row row}]
                                                  {:subs {[::subs/dimensions] dims}})]
                                (->> (ht/find-all
                                       tree
                                       #(= "re-frame.hicasso.examples.grid.views/cell"
                                           (:view-id %)))
                                     (mapv (fn [c] [(:row (ht/attrs c))
                                                    (:col (ht/attrs c))]))))))
                    rows)]
    (is (= 100 (count cells)))
    (is (= 100 (count (set cells))))
    (is (= (set (for [r rows c (range (:cols dims))] [r c])) (set cells))
        "the coordinate grid, complete and without duplicates — 100 cells,
         100 addresses, 100 notification groups")))
