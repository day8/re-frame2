(ns re-frame.story.ui.test-mode-state-cljs-test
  "Tests for the `:test` pane's local state surface.

  Covers the race guard: `select-step!` must no-op while a
  re-run is in flight (the variant frame is being reset; restoring
  against it would land against transient state and the new
  :epoch-ids slice would silently re-index :selected-step against a
  different epoch on resolve).

  Runs CLJS-only because `re-frame.story.ui.test-mode.state` is a
  .cljs file (it derefs Reagent ratoms + calls into runtime). Cross-
  platform pure assertions on `assertion-row :row-key` live in
  `re-frame.story-ui-test` (JVM)."
  (:require [cljs.test :refer-macros [deftest is testing use-fixtures]]
            [re-frame.core :as rf]
            [re-frame.story.ui.test-mode.state :as rf.story.ui.test-mode.state]))

;; ---- fixtures ------------------------------------------------------------

(defn reset-results! []
  (reset! rf.story.ui.test-mode.state/results-atom {}))

(use-fixtures :each {:before reset-results! :after reset-results!})

;; ---- select-step! race guard ---------------------------------------------

(deftest select-step-noops-while-running
  (testing "select-step! no-ops while a re-run is in flight: store-result!
            writes a fresh :epoch-ids slice on resolve, so a restore now
            would land on the frame being reset and :selected-step would
            index a different epoch"
    (let [variant-id :story.unit/race
          restored   (atom [])]
      (swap! rf.story.ui.test-mode.state/results-atom assoc variant-id
             {:running? true :epoch-ids [:epoch/a :epoch/b :epoch/c]})
      (with-redefs [rf/restore-epoch! (fn [vid eid]
                                       (swap! restored conj [vid eid]))]
        (rf.story.ui.test-mode.state/select-step! variant-id 1))
      (is (= [] @restored))
      (is (nil? (get-in @rf.story.ui.test-mode.state/results-atom [variant-id :selected-step]))))))

(deftest select-step-fires-when-not-running
  (let [variant-id :story.unit/idle
        restored   (atom [])]
    (swap! rf.story.ui.test-mode.state/results-atom assoc variant-id
           {:running? false :epoch-ids [:epoch/a :epoch/b :epoch/c]})
    (with-redefs [rf/restore-epoch! (fn [vid eid]
                                     (swap! restored conj [vid eid]))]
      (rf.story.ui.test-mode.state/select-step! variant-id 1))
    (is (= [[variant-id :epoch/b]] @restored))
    (is (= 1 (get-in @rf.story.ui.test-mode.state/results-atom [variant-id :selected-step])))))

;; ---- toggle-expanded! keyed by row-key -----------------------------------

;; Add k1, add k2, toggle k1 again: only #{k2} survives if both the add and
;; the remove work. View consumers pass assertion-row's :row-key.
(deftest toggle-expanded-uses-row-key
  (let [variant-id :story.unit/expand
        k1         ":rf.assert/path-equals [[:count] 1]"
        k2         ":rf.assert/path-equals [[:count] 2]"]
    (rf.story.ui.test-mode.state/toggle-expanded! variant-id k1)
    (rf.story.ui.test-mode.state/toggle-expanded! variant-id k2)
    (rf.story.ui.test-mode.state/toggle-expanded! variant-id k1)
    (is (= #{k2} (get-in @rf.story.ui.test-mode.state/results-atom [variant-id :expanded])))))
