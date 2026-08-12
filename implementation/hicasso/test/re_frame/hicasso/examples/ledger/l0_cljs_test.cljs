(ns re-frame.hicasso.examples.ledger.l0-cljs-test
  "L0 — THE LEDGER'S MODEL, AND THE VIRTUALIZER'S ARITHMETIC (rf2-hic-047).

  Two populations, and they are separated on purpose.

  **The model**, which knows nothing about windowing. Ten thousand
  records, four handlers and five reads, and every one of them behaves
  exactly as it would behind a screen that mounted all ten thousand rows.
  That is the claim `ledger.surface-cljs-test` makes structurally and
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
            [re-frame.adapter.uix :as uix-adapter]
            [re-frame.core :as rf]
            [re-frame.hicasso.examples.ledger.app :as app]
            [re-frame.hicasso.examples.ledger.events :as events]
            [re-frame.hicasso.examples.ledger.subs :as subs]
            [re-frame.hicasso.examples.ledger.vendor :as vendor]
            [re-frame.hicasso.examples.ledger.views :as views]
            [re-frame.test-support :as test-support]))

(use-fixtures :each
  (test-support/make-reset-runtime-fixture
    {:adapter       uix-adapter/adapter
     :ambient-frame nil}))

(defn- with-ledger
  "Run `f` against a fresh frame holding `total` records, seeded exactly
  as the application seeds itself."
  ([f] (with-ledger 200 f))
  ([total f]
   (rf/with-new-frame [frame (rf/make-frame
                               {:initial-events (app/initial-events total)})]
     (f frame))))

(defn- read-sub [frame query-v] (rf/subscribe-once query-v {:frame frame}))
(defn- send! [frame event] (rf/dispatch-sync event {:frame frame}))

;; ---------------------------------------------------------------------------
;; Pure — the record, and the id that is deliberately not the index
;; ---------------------------------------------------------------------------

(deftest a-records-id-is-not-its-index
  (is (= {:id "rec-100000" :name "Record 0"} (events/record 0)))
  (is (= {:id "rec-104136" :name "Record 4136"} (events/record 4136)))
  (testing "which is what makes a focus assertion an assertion about the
            RECORD — an id that read `5` would be equally satisfied by
            focus having stayed on the fifth row of the window, and the
            fifth row of the window is exactly what a slot-keyed
            virtualizer leaves it on"
    (is (not= (str 5) (:id (events/record 5))))))

(deftest the-default-ledger-is-ten-thousand-records
  (is (= 10000 events/default-total)
      "specification §7's `10K-row behavior`, as a number the witnesses read")
  (let [db (events/seed 10000)]
    (is (= 10000 (count (:records db))))
    (is (= {} (:notes db)) "an untouched record costs no entry")
    (is (= -1 (:focused db)) "nothing is pinned before anything has focus")))

;; ---------------------------------------------------------------------------
;; The model — ordinary re-frame2, at ten thousand rows
;; ---------------------------------------------------------------------------

(deftest one-keystroke-moves-exactly-one-address
  (with-ledger 10000
    (fn [frame]
      (send! frame [::events/note 4136 "chase"])
      (is (= "chase" (read-sub frame [::subs/note 4136])))
      (is (= "" (read-sub frame [::subs/note 4135])))
      (is (= "" (read-sub frame [::subs/note 4137]))
          "the neighbours, because a map keyed by index is only worth
           having if a write reaches one key of it")
      (testing "and the records are untouched — the note is a second
                address and not a field of the record, so a keystroke
                cannot move what a row displays as its name"
        (is (= {:id "rec-104136" :name "Record 4136"}
               (read-sub frame [::subs/record 4136])))))))

(deftest a-record-is-read-by-index-and-answers-by-identity
  (with-ledger
    (fn [frame]
      (let [before (read-sub frame [::subs/record 7])]
        (send! frame [::events/note 7 "x"])
        (is (identical? before (read-sub frame [::subs/record 7]))
            "IDENTICAL, not merely `=`. A note write must not mint a fresh
             record map, because the row's record read is what a
             keystroke must NOT notify — and a subscription's equality
             gate is the thing standing between one body run and
             twenty-six")))))

(deftest the-flag-toggles-and-nothing-else-does
  (with-ledger
    (fn [frame]
      (is (false? (read-sub frame [::subs/flagged? 3])))
      (send! frame [::events/flag {:index 3}])
      (is (true? (read-sub frame [::subs/flagged? 3])))
      (is (false? (read-sub frame [::subs/flagged? 4])))
      (send! frame [::events/flag {:index 3}])
      (is (false? (read-sub frame [::subs/flagged? 3])) "and back"))))

(deftest focus-is-recorded-as-an-index-and-read-back-as-the-pin
  (with-ledger
    (fn [frame]
      (is (= -1 (read-sub frame [::subs/pinned-index]))
          "`-1` and not `nil`: the value goes to a foreign component as a
           number, and `nil` there would be a prop the vendor has to
           special-case")
      (send! frame [::events/row-focused {:index 12}])
      (is (= 12 (read-sub frame [::subs/pinned-index])))
      (testing "the next focus replaces it — one pin, and it is released
                by the next focus and by nothing else, because a `:on-blur`
                companion would unmount the row while the platform is
                still moving focus through it"
        (send! frame [::events/row-focused {:index 13}])
        (is (= 13 (read-sub frame [::subs/pinned-index])))))))

(deftest the-window-the-vendor-reports-is-ordinary-data
  (with-ledger
    (fn [frame]
      (send! frame [::events/window-shown {:from 40 :to 66}])
      (is (= {:from 40 :to 66} (read-sub frame [::subs/window]))))))

;; ---------------------------------------------------------------------------
;; The window function
;; ---------------------------------------------------------------------------

(def ^:private geometry
  "The screen's own geometry, read from the view rather than typed, so
  this file and the DOM suite cannot disagree about the numbers the
  screen actually uses."
  {:row-height      views/row-height
   :viewport-height views/viewport-height
   :overscan        views/overscan
   :total           10000})

(defn- window [scroll-top] (vendor/window-from scroll-top geometry))

(deftest the-window-is-the-viewport-plus-the-overscan
  (testing "at the top, where the overscan above the viewport is clamped away"
    (is (= [0 23] (window 0))
        "twenty visible rows (480 / 24) plus three of overscan below, and
         nothing above — twenty-four rows of DOM for a ten-thousand-row
         model"))
  (testing "in the middle, where both edges get their overscan"
    (is (= [7 33] (window (* 10 views/row-height)))
        "scrolled to row 10: three above, twenty visible, three below")
    (is (= [497 523] (window (* 500 views/row-height)))))
  (testing "at the bottom, where the model clamps the far edge"
    (is (= [9974 9999] (window (* 9977 views/row-height))))))

(deftest the-window-does-not-grow-with-the-model
  ;; The virtualizer half of the screen's scaling claim, stated where it
  ;; is arithmetic. The DOM suite measures the same thing on a real
  ;; engine; this row is what makes that measurement's expected value a
  ;; derivation rather than a constant somebody typed.
  (let [at-100   (vendor/window-from 0 (assoc geometry :total 100))
        at-10000 (window 0)]
    (is (= at-100 at-10000)
        "a hundred records and ten thousand produce the same window at the
         same offset — which is `the mounted rows do not follow the
         collection`, before any DOM is involved")
    (is (= [0 23] at-10000)))
  (testing "and a model SMALLER than one window is clamped, not padded"
    (is (= [0 9] (vendor/window-from 0 (assoc geometry :total 10))))))

(deftest a-scroll-of-n-rows-moves-the-window-by-n
  ;; The premise the DOM suite's `a scroll costs the rows that entered`
  ;; row rests on: if a three-row scroll moved the window by more than
  ;; three, the body count it measures would be about the vendor's
  ;; arithmetic rather than about the screen's topology.
  (let [[from-a to-a] (window (* 100 views/row-height))
        [from-b to-b] (window (* 103 views/row-height))]
    (is (= 3 (- from-b from-a)))
    (is (= 3 (- to-b to-a)))))
