(ns re-frame.story.ui.test-mode-state-cljs-test
  "Tests for the `:test` pane's local state surface (rf2-tistm).

  Covers the rf2-tistm race-guard: `select-step!` must no-op while a
  re-run is in flight (the variant frame is being reset; restoring
  against it would land against transient state and the new
  :epoch-ids slice would silently re-index :selected-step against a
  different epoch on resolve).

  Runs CLJS-only because `re-frame.story.ui.test-mode.state` is a
  .cljs file (it derefs Reagent ratoms + calls into runtime). Cross-
  platform pure assertions on `assertion-row :row-key` live in
  `re-frame.story-ui-test` (JVM)."
  (:require [cljs.test :refer-macros [deftest is testing use-fixtures]]
            [re-frame.epoch :as epoch]
            [re-frame.story.ui.test-mode.state :as tm-state]))

;; ---- fixtures ------------------------------------------------------------

(defn reset-results! []
  (reset! tm-state/results-atom {}))

(use-fixtures :each {:before reset-results! :after reset-results!})

;; ---- rf2-tistm: select-step! race guard ----------------------------------

(deftest select-step-noops-while-running
  (testing "rf2-tistm — select-step! must no-op when the variant's slot
            carries :running? true. Concurrent restore against the
            frame being reset by run-variant-pane! would corrupt the
            scrubber state on resolve (store-result! writes a fresh
            :epoch-ids slice; :selected-step would silently index a
            different epoch)."
    (let [variant-id :story.unit/race
          epoch-ids  [:epoch/a :epoch/b :epoch/c]
          restored   (atom [])]
      (swap! tm-state/results-atom assoc variant-id
             {:running?  true
              :epoch-ids epoch-ids})
      (with-redefs [epoch/restore-epoch (fn [vid eid]
                                          (swap! restored conj [vid eid]))]
        (tm-state/select-step! variant-id 1))
      (is (= [] @restored)
          "epoch/restore-epoch is not called while :running? is true")
      (is (nil? (get-in @tm-state/results-atom [variant-id :selected-step]))
          ":selected-step is NOT mutated while :running? is true — preserving
           the prior scrubber position so the post-resolve render is
           consistent"))))

(deftest select-step-fires-when-not-running
  (testing "rf2-tistm sanity: the guard is conditional on :running? — when
            the slot is idle (:running? falsy), select-step! restores the
            epoch and stamps :selected-step as before"
    (let [variant-id :story.unit/idle
          epoch-ids  [:epoch/a :epoch/b :epoch/c]
          restored   (atom [])]
      (swap! tm-state/results-atom assoc variant-id
             {:running?  false
              :epoch-ids epoch-ids})
      (with-redefs [epoch/restore-epoch (fn [vid eid]
                                          (swap! restored conj [vid eid]))]
        (tm-state/select-step! variant-id 1))
      (is (= [[variant-id :epoch/b]] @restored)
          "the idle path still calls restore-epoch against the targeted slot")
      (is (= 1 (get-in @tm-state/results-atom [variant-id :selected-step]))
          ":selected-step is stamped to the requested index"))))

;; ---- rf2-tistm: toggle-expanded! keyed by row-key ------------------------

(deftest toggle-expanded-uses-row-key
  (testing "rf2-tistm — toggle-expanded! threads its key (a string row-key
            in normal use, but any value) straight into the :expanded set.
            View consumers pass assertion-row's :row-key — see the JVM
            test pinning row-key = :label in re-frame.story-ui-test."
    (let [variant-id :story.unit/expand]
      (tm-state/toggle-expanded! variant-id ":rf.assert/path-equals [[:count] 1]")
      (is (= #{":rf.assert/path-equals [[:count] 1]"}
             (get-in @tm-state/results-atom [variant-id :expanded]))
          "first toggle inserts the row-key")
      (tm-state/toggle-expanded! variant-id ":rf.assert/path-equals [[:count] 2]")
      (is (= #{":rf.assert/path-equals [[:count] 1]"
               ":rf.assert/path-equals [[:count] 2]"}
             (get-in @tm-state/results-atom [variant-id :expanded]))
          "second toggle with a different key adds to the set")
      (tm-state/toggle-expanded! variant-id ":rf.assert/path-equals [[:count] 1]")
      (is (= #{":rf.assert/path-equals [[:count] 2]"}
             (get-in @tm-state/results-atom [variant-id :expanded]))
          "toggling an already-present key removes it"))))
