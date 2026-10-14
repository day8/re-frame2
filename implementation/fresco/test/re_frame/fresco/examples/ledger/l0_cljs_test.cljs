(ns re-frame.fresco.examples.ledger.l0-cljs-test
  "L0 — THE LEDGER'S MODEL, AND THE VIRTUALIZER'S ARITHMETIC.

  Two populations, and they are separated on purpose.

  **The model**, which knows nothing about windowing. At ten thousand
  records a note write behaves exactly as it would behind a screen that
  mounted all ten thousand rows. That is the claim `ledger.events`' `ns` form makes structurally and
  this file makes behaviourally: virtualization is a rendering strategy
  and it does not reach the model.

  **The window function**, which is the one part of a virtualizer worth
  testing away from an engine. An off-by-one in
  `vendor/window-from` is invisible on a screen — a row too few at the
  edge is a row the user scrolls to anyway — and fatal to every count the
  DOM suite makes, because those counts are derived from exactly this
  arithmetic rather than typed in. Getting it wrong here would make the
  DOM suite agree with a wrong number rather than fail."
  (:require [cljs.test :refer-macros [deftest is testing use-fixtures]]
            [re-frame.adapter.uix :as rf.adapter.uix]
            [re-frame.core :as rf]
            [re-frame.fresco.examples.ledger.app :as rf.fresco.examples.ledger.app]
            [re-frame.fresco.examples.ledger.events :as rf.fresco.examples.ledger.events]
            [re-frame.fresco.examples.ledger.subs :as rf.fresco.examples.ledger.subs]
            [re-frame.fresco.examples.ledger.vendor :as rf.fresco.examples.ledger.vendor]
            [re-frame.fresco.examples.ledger.views :as rf.fresco.examples.ledger.views]
            [re-frame.test-support :as rf.test-support]))

(use-fixtures :each
  (rf.test-support/make-reset-runtime-fixture
    {:adapter       rf.adapter.uix/adapter
     :ambient-frame nil}))

(defn- with-ledger
  "Run `f` against a fresh frame holding `total` records, seeded exactly
  as the application seeds itself."
  [total f]
  (rf/with-new-frame [frame (rf/make-frame
                              {:initial-events (rf.fresco.examples.ledger.app/initial-events total)})]
    (f frame)))

(defn- read-sub [frame query-v] (rf/subscribe-once query-v {:frame frame}))
(defn- send! [frame event] (rf/dispatch-sync event {:frame frame}))

;; ---------------------------------------------------------------------------
;; The model — ordinary re-frame2, at ten thousand rows
;; ---------------------------------------------------------------------------

(deftest one-keystroke-moves-exactly-one-address
  (with-ledger 10000
    (fn [frame]
      (send! frame [::rf.fresco.examples.ledger.events/note 4136 "chase"])
      (is (= "chase" (read-sub frame [::rf.fresco.examples.ledger.subs/note 4136])))
      (is (= "" (read-sub frame [::rf.fresco.examples.ledger.subs/note 4135])))
      (is (= "" (read-sub frame [::rf.fresco.examples.ledger.subs/note 4137]))
          "the neighbours, because a map keyed by index is only worth
           having if a write reaches one key of it")
      (testing "and the records are untouched — the note is a second
                address and not a field of the record, so a keystroke
                cannot move what a row displays as its name"
        (is (= {:id "rec-104136" :name "Record 4136"}
               (read-sub frame [::rf.fresco.examples.ledger.subs/record 4136])))))))

;; ---------------------------------------------------------------------------
;; The window function
;; ---------------------------------------------------------------------------

(def ^:private geometry
  "The screen's own geometry, read from the view rather than typed, so
  this file and the DOM suite cannot disagree about the numbers the
  screen actually uses."
  {:row-height      rf.fresco.examples.ledger.views/row-height
   :viewport-height rf.fresco.examples.ledger.views/viewport-height
   :overscan        rf.fresco.examples.ledger.views/overscan
   :total           10000})

(defn- window [scroll-top] (rf.fresco.examples.ledger.vendor/window-from scroll-top geometry))

(deftest the-window-is-the-viewport-plus-the-overscan
  (testing "at the top, where the overscan above the viewport is clamped away"
    (is (= [0 23] (window 0))
        "twenty visible rows (480 / 24) plus three of overscan below, and
         nothing above — twenty-four rows of DOM for a ten-thousand-row
         model"))
  (testing "in the middle, where both edges get their overscan"
    (is (= [7 33] (window (* 10 rf.fresco.examples.ledger.views/row-height)))
        "scrolled to row 10: three above, twenty visible, three below"))
  (testing "at the bottom, where the model clamps the far edge"
    (is (= [9974 9999] (window (* 9977 rf.fresco.examples.ledger.views/row-height)))))
  (testing "a hundred records put the same window on screen as ten thousand"
    (is (= [0 23] (rf.fresco.examples.ledger.vendor/window-from 0 (assoc geometry :total 100)))))
  (testing "and a model SMALLER than one window is clamped, not padded"
    (is (= [0 9] (rf.fresco.examples.ledger.vendor/window-from 0 (assoc geometry :total 10))))))
