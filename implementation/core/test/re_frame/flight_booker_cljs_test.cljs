(ns re-frame.flight-booker-cljs-test
  "The 7GUIs Flight Booker example: `valid-date?`'s calendar check and the
  `:flight/book-enabled?` sub graph, driven through the real handlers on an
  anon frame and read back with `rf/compute-sub`. `examples/` stays
  test-free, so these run here, on the `:node-test` build, which has
  `../examples/core` on its source paths."
  (:require [cljs.test :refer-macros [deftest is use-fixtures]]
            [re-frame.core :as rf]
            [re-frame.frame :as rf.frame]
            [re-frame.adapter.reagent :as rf.adapter.reagent]
            [re-frame.test-support :as rf.test-support]
            [seven-guis.flight-booker.core :as flight]))

;; Each test drives its own anon frame with an explicit `{:frame f}`, so no
;; ambient scope may mask frame resolution.
(use-fixtures :each
  (rf.test-support/make-reset-runtime-fixture
    {:adapter       rf.adapter.reagent/adapter
     :ambient-frame nil}))

(deftest valid-date?-accepts-only-real-calendar-dates
  (doseq [[s expected] [["2024-02-29" true]    ;; a leap day round-trips
                        ;; setUTCFullYear, not js/Date.UTC, which maps year 26 to 1926
                        ["0026-05-06" true]
                        ["2025-02-29" false]   ;; shape-valid, but overflows to March
                        ["2026-5-6"   false]   ;; not ISO yyyy-mm-dd
                        [nil          false]]]
    (is (= expected (flight/valid-date? s)) (pr-str s))))

(defn- flight-frame!
  "A fresh anon frame seeded by `:flight/initialise`: one-way, with start and
  return both 2026-05-06."
  []
  (let [f (rf.frame/make-anon-frame-record! {:doc "flight-booker test frame"})]
    (rf/dispatch-sync [:flight/initialise] {:frame f})
    f))

(defn- sub
  "Every flight sub reads app-db directly, so the frame's app-db value suffices."
  [f query-v]
  (rf/compute-sub query-v (rf/app-db-value f)))

(deftest invalid-start-date-blocks-book
  (let [f (flight-frame!)]
    (rf/dispatch-sync [:flight/set-start "2026-02-31"] {:frame f})
    (is (false? (sub f [:flight/book-enabled?])))))

(deftest one-way-skips-return-validation
  (let [f (flight-frame!)]
    (rf/dispatch-sync [:flight/set-return "not-a-date-at-all"] {:frame f})
    (is (true? (sub f [:flight/book-enabled?]))
        "a one-way trip waives the return field")))

(deftest return-trip-requires-a-valid-return-date
  (let [f (flight-frame!)]
    (rf/dispatch-sync [:flight/set-trip-type :return] {:frame f})
    (rf/dispatch-sync [:flight/set-return "2026-02-31"] {:frame f})
    (is (false? (sub f [:flight/return-valid?])))))

(deftest return-on-or-after-start-is-coherent
  ;; ISO dates compare as strings; return = start is the >= boundary.
  (doseq [[return expected] [["2026-05-05" false]
                             ["2026-05-06" true]]]
    (let [f (flight-frame!)]
      (rf/dispatch-sync [:flight/set-trip-type :return] {:frame f})
      (rf/dispatch-sync [:flight/set-start "2026-05-06"] {:frame f})
      (rf/dispatch-sync [:flight/set-return return] {:frame f})
      (is (= expected (sub f [:flight/book-enabled?])) return))))
