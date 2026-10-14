(ns re-frame.fresco.examples.grid.l0-cljs-test
  "L0 — THE GRID'S MODEL, WITH NO VIEW SUBSTRATE ANYWHERE.

  A hundred controlled fields, and the model behind them is one map.
  [[a-refused-keystroke-leaves-the-model-where-it-was]] is the controlled
  law's refusal half — the editor's four fields accept or normalise, so
  refusal lives here — and [[one-keystroke-moves-exactly-one-cell]] is the
  first number of the per-keystroke walk, asserted over a hundred
  addresses rather than four."
  (:require [cljs.test :refer-macros [deftest is use-fixtures]]
            [re-frame.adapter.uix :as rf.adapter.uix]
            [re-frame.core :as rf]
            [re-frame.fresco.examples.grid.app :as rf.fresco.examples.grid.app]
            [re-frame.fresco.examples.grid.events :as rf.fresco.examples.grid.events]
            [re-frame.fresco.examples.grid.subs :as rf.fresco.examples.grid.subs]
            [re-frame.test-support :as rf.test-support]))

(use-fixtures :each
  (rf.test-support/make-reset-runtime-fixture
    {:adapter       rf.adapter.uix/adapter
     :ambient-frame nil}))

(defn- with-grid
  "Run `f` against a fresh frame holding the default grid, seeded exactly
  as the application seeds itself."
  [f]
  (rf/with-new-frame [frame (rf/make-frame
                              {:initial-events (rf.fresco.examples.grid.app/initial-events
                                                 rf.fresco.examples.grid.events/default-dimensions)})]
    (f frame)))

(defn- read-sub [frame query-v] (rf/subscribe-once query-v {:frame frame}))

(defn- type! [frame row col text]
  (rf/dispatch-sync [::rf.fresco.examples.grid.events/edit row col text] {:frame frame}))

(deftest a-refused-keystroke-leaves-the-model-where-it-was
  (with-grid
    (fn [frame]
      (let [before (read-sub frame [::rf.fresco.examples.grid.subs/cell 3 4])]
        (type! frame 3 4 "34x")
        (is (= before (read-sub frame [::rf.fresco.examples.grid.subs/cell 3 4]))
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
        (is (= (assoc before [3 4] "77") after)
            "ONE address of a hundred, holding the accepted keystroke. The
             per-keystroke walk's first number, and it does not grow with
             the grid")))))
