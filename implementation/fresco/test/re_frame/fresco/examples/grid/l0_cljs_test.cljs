(ns re-frame.hicasso.examples.grid.l0-cljs-test
  "L0 — THE GRID'S MODEL, WITH NO VIEW SUBSTRATE ANYWHERE.

  A hundred controlled fields, and the model behind them is one map, one
  policy function and three handlers. That is the claim this file makes:
  breadth costs nothing in the model tier either.

  Two rows earn their place beyond the ordinary.
  [[a-refused-keystroke-leaves-the-model-where-it-was]] is the controlled
  law's refusal half — the editor's four fields accept or normalise, so
  refusal lives here — and [[one-keystroke-moves-exactly-one-cell]] is the
  first number of the per-keystroke walk, asserted over a hundred
  addresses rather than four."
  (:require [cljs.test :refer-macros [deftest is testing use-fixtures]]
            [re-frame.adapter.uix :as rf.adapter.uix]
            [re-frame.core :as rf]
            [re-frame.hicasso.examples.grid.app :as rf.hicasso.examples.grid.app]
            [re-frame.hicasso.examples.grid.events :as rf.hicasso.examples.grid.events]
            [re-frame.hicasso.examples.grid.subs :as rf.hicasso.examples.grid.subs]
            [re-frame.test-support :as rf.test-support]))

(use-fixtures :each
  (rf.test-support/make-reset-runtime-fixture
    {:adapter       rf.adapter.uix/adapter
     :ambient-frame nil}))

(defn- with-grid
  "Run `f` against a fresh frame holding a grid of `dimensions`, seeded
  exactly as the application seeds itself."
  ([f] (with-grid rf.hicasso.examples.grid.events/default-dimensions f))
  ([dimensions f]
   (rf/with-new-frame [frame (rf/make-frame
                               {:initial-events (rf.hicasso.examples.grid.app/initial-events dimensions)})]
     (f frame))))

(defn- read-sub [frame query-v] (rf/subscribe-once query-v {:frame frame}))

(defn- type! [frame row col text]
  (rf/dispatch-sync [::rf.hicasso.examples.grid.events/edit row col text] {:frame frame}))

;; ---------------------------------------------------------------------------
;; Pure
;; ---------------------------------------------------------------------------

(deftest the-default-grid-is-a-hundred-cells
  (is (= {:rows 10 :cols 10} rf.hicasso.examples.grid.events/default-dimensions))
  (is (= 100 (count (rf.hicasso.examples.grid.events/seed-cells rf.hicasso.examples.grid.events/default-dimensions))))
  (is (= "0" (get (rf.hicasso.examples.grid.events/seed-cells rf.hicasso.examples.grid.events/default-dimensions) [0 0])))
  (is (= "99" (get (rf.hicasso.examples.grid.events/seed-cells rf.hicasso.examples.grid.events/default-dimensions) [9 9]))
      "each cell seeds to a distinct digit string, so a witness reading
       one cell can tell it apart from its neighbours"))

(deftest the-policy-refuses-anything-that-is-not-a-digit-string
  (is (= "12" (rf.hicasso.examples.grid.events/digits-only "9" "12")) "accepted")
  (is (= "" (rf.hicasso.examples.grid.events/digits-only "9" "")) "an empty field is a legal state")
  (is (= "9" (rf.hicasso.examples.grid.events/digits-only "9" "1a")) "refused — the model does not move")
  (is (= "9" (rf.hicasso.examples.grid.events/digits-only "9" "-1")))
  (is (= "9" (rf.hicasso.examples.grid.events/digits-only "9" " ")))
  (testing "a refusal answers the COMMITTED value by identity"
    ;; Not merely `=`. The value the field is handed back has to be the
    ;; one it was handed before, or a refusal would look to every
    ;; equality gate above it like a change.
    (let [old "9"]
      (is (identical? old (rf.hicasso.examples.grid.events/digits-only old "x"))))))

;; ---------------------------------------------------------------------------
;; Transitions
;; ---------------------------------------------------------------------------

(deftest an-accepted-keystroke-lands-in-its-own-cell
  (with-grid
    (fn [frame]
      (type! frame 3 4 "77")
      (is (= "77" (read-sub frame [::rf.hicasso.examples.grid.subs/cell 3 4])))
      (is (= "35" (read-sub frame [::rf.hicasso.examples.grid.subs/cell 3 5]))
          "and its neighbour is untouched — the seed's own value, still"))))

(deftest a-refused-keystroke-leaves-the-model-where-it-was
  (with-grid
    (fn [frame]
      (let [before (read-sub frame [::rf.hicasso.examples.grid.subs/cell 3 4])]
        (type! frame 3 4 "34x")
        (is (= before (read-sub frame [::rf.hicasso.examples.grid.subs/cell 3 4]))
            "THE REFUSAL HALF OF THE CONTROLLED LAW, at the model tier: the
             field will be handed back the committed value, which is what
             makes the echo in `scaling-dom-cljs-test` a snap-back rather
             than an acceptance")))))

(deftest one-keystroke-moves-exactly-one-cell
  (with-grid
    (fn [frame]
      (let [before (:cells (rf/app-db-value frame))
            _      (type! frame 3 4 "77")
            after  (:cells (rf/app-db-value frame))]
        (is (= 100 (count before) (count after))
            "no cell arrived and none left")
        (is (= [[3 4]] (vec (for [[k v] after :when (not= v (get before k))] k)))
            "ONE address of a hundred. The per-keystroke walk's first
             number, and it does not grow with the grid")))))

(deftest a-row-total-is-the-sum-of-its-row
  (with-grid {:rows 2 :cols 3}
    (fn [frame]
      (is (= (+ 0 1 2) (read-sub frame [::rf.hicasso.examples.grid.subs/row-total 0])))
      (is (= (+ 3 4 5) (read-sub frame [::rf.hicasso.examples.grid.subs/row-total 1])))
      (type! frame 1 1 "40")
      (is (= (+ 3 40 5) (read-sub frame [::rf.hicasso.examples.grid.subs/row-total 1])))
      (is (= (+ 0 1 2) (read-sub frame [::rf.hicasso.examples.grid.subs/row-total 0]))
          "the other row's total did not move, which is what makes it a
           per-row edge rather than a whole-grid one")))

  (testing "an emptied cell counts as zero rather than throwing"
    (with-grid {:rows 1 :cols 2}
      (fn [frame]
        (type! frame 0 0 "")
        (is (= 1 (read-sub frame [::rf.hicasso.examples.grid.subs/row-total 0])))))))

(deftest clearing-a-row-moves-that-row-and-no-other
  (with-grid {:rows 3 :cols 4}
    (fn [frame]
      (rf/dispatch-sync [::rf.hicasso.examples.grid.events/clear-row {:row 1}] {:frame frame})
      (is (= ["" "" "" ""] (mapv #(read-sub frame [::rf.hicasso.examples.grid.subs/cell 1 %]) (range 4))))
      (is (= 0 (read-sub frame [::rf.hicasso.examples.grid.subs/row-total 1])))
      (is (= ["0" "1" "2" "3"] (mapv #(read-sub frame [::rf.hicasso.examples.grid.subs/cell 0 %]) (range 4)))
          "a BROAD update is still scoped to what changed. `scaling-dom`
           counts the bodies this runs, so the narrow number has something
           to be narrow compared to"))))

(deftest the-grid-is-parameterised-by-its-dimensions
  ;; The property `scaling-dom-cljs-test` rests on: the same application,
  ;; mounted at two sizes, differing in nothing but this.
  (with-grid {:rows 5 :cols 5}
    (fn [frame]
      (is (= {:rows 5 :cols 5} (read-sub frame [::rf.hicasso.examples.grid.subs/dimensions])))
      (is (= 25 (count (:cells (rf/app-db-value frame))))))))
