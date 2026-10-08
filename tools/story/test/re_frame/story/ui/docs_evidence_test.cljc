(ns re-frame.story.ui.docs-evidence-test
  "JVM coverage of the docs-mode status / fidelity / view-arg schema /
  evidence-excerpt pure projections (spec/022 §1 + §2). The React render and
  the live result-slot read are CLJS."
  (:require [clojure.test :refer [deftest is testing]]
            [re-frame.story.play.evidence :as rf.story.play.evidence]
            [re-frame.story.ui.docs :as rf.story.ui.docs]))

(def ^:private script
  [[:dispatch [:counter/inc]]
   [:dispatch [:counter/add 5]]
   [:assert [:rf.assert/path-equals [:count] 6]]])

(def ^:private epoch-tape
  [{:epoch-id 100 :dispatch-id 100 :trigger-event [:counter/inc]
    :db-before {:count 0} :db-after {:count 1}
    :effects [{:fx-id :db :outcome :ok}]
    :sub-runs [{:sub-id :count :cause-event-id :counter/inc}]
    :renders [] :trace-events [] :outcome :ok :rf.story/script-idx 0}
   {:epoch-id 101 :dispatch-id 101 :trigger-event [:counter/add 5]
    :db-before {:count 1} :db-after {:count 6}
    :effects [] :sub-runs [] :renders [] :trace-events []
    :outcome :ok :rf.story/script-idx 1}])

;; ===========================================================================
;; status-summary  (spec/022 §1 — docs↔Test agreement)
;; ===========================================================================

(deftest status-summary-agrees-with-test-run-status
  (testing "a nil result (never run) is :pending with zero counts"
    (is (= {:status :pending :passed 0 :failed 0 :cannot-run 0 :total 0 :ran? false}
           (rf.story.ui.docs/status-summary nil nil))))
  (testing "a run reports its counts and its run-level :status"
    (is (= {:status :pass :passed 1 :failed 0 :cannot-run 0 :total 1 :ran? true}
           (rf.story.ui.docs/status-summary
             {:status :pass}
             {:passed 1 :failed 0 :cannot-run 0 :total 1 :all-passed? true}))))
  (testing "a tape-floor :fail wins over green assertion counts, exactly as in Test mode"
    (is (= :fail (:status (rf.story.ui.docs/status-summary
                            {:status :fail}
                            {:passed 1 :failed 0 :cannot-run 0 :total 1 :all-passed? true}))))))

;; ===========================================================================
;; fidelity / world-input  (spec/022 §1)
;; ===========================================================================

(deftest fidelity-badges-order-and-presence
  (testing "the full ladder, highest fidelity first, each marked present? against the plan's :fidelity set"
    (is (= [[:real-setup true] [:db-seed false] [:sub-overrides false]]
           (mapv (juxt :rung :present?)
                 (rf.story.ui.docs/fidelity-badges {:fidelity #{:real-setup}}))))))

(deftest world-input-chips-only-present
  (testing "only the world inputs the variant actually declares surface"
    (is (= #{:setup :script :db-seed :sub-overrides :network}
           (into #{} (map :key)
                 (rf.story.ui.docs/world-input-chips
                   {:setup [[:a]] :script [[:b]]
                    :world {:db-seed {:x 1}
                            :render {:sub-overrides {[:s] 1}}
                            :network {[:get "/x"] {}}}})))))
  (testing "a bare variant with no world inputs surfaces no chips"
    (is (= [] (rf.story.ui.docs/world-input-chips {:world {}})))))

;; ===========================================================================
;; view-arg schema table  (spec/022 §1)
;; ===========================================================================

(deftest view-arg-schema-rows-projects-map-entries
  (testing "a [:map …] view-args-schema projects one row per entry, marking
            {:optional true} entries as not-required"
    (is (= [{:key :label :required? true :schema :string}
            {:key :n :required? false :schema :int}]
           (rf.story.ui.docs/view-arg-schema-rows
             {:world {:view-args-schema [:map
                                         [:label :string]
                                         [:n {:optional true} :int]]}}))))
  (testing "a non-:map top-level schema yields no rows (no per-prop table)"
    (is (= [] (rf.story.ui.docs/view-arg-schema-rows
                {:world {:view-args-schema [:and :map]}})))))

;; ===========================================================================
;; evidence excerpt  (spec/022 §2 — sparse, not a debug log)
;; ===========================================================================

(deftest evidence-excerpt-caps-and-reuses-spine-projection
  (testing "the excerpt surfaces the run's leading beats, decorated by the spine projection"
    (let [exc  (rf.story.ui.docs/evidence-excerpt
                 (rf.story.play.evidence/narrative script epoch-tape))
          beat (first (:beats exc))]
      (is (true? (:available? exc)))
      (is (= 100 (:epoch-id beat)))
      (is (some? (:strength beat))
          "excerpt beats carry the spine's evidence-strength projection")))
  (testing "a longer narrative caps the inline beats and reports the overflow"
    (let [long-tape   (mapv (fn [i]
                              {:epoch-id i :dispatch-id i
                               :trigger-event [:e i] :db-after {:i i}
                               :effects [] :sub-runs [] :renders []
                               :trace-events [] :outcome :ok
                               :rf.story/script-idx i})
                            (range 5))
          long-script (mapv (fn [i] [:dispatch [:e i]]) (range 5))
          exc (rf.story.ui.docs/evidence-excerpt
                (rf.story.play.evidence/narrative long-script long-tape))]
      (is (= 5 (:beat-count exc)))
      (is (= rf.story.ui.docs/evidence-excerpt-beat-cap (count (:beats exc))))
      (is (= (- 5 rf.story.ui.docs/evidence-excerpt-beat-cap) (:more exc)))))
  (testing "a nil narrative is an honest no-evidence excerpt"
    (is (= {:available? false :beats [] :beat-count 0 :more 0}
           (rf.story.ui.docs/evidence-excerpt nil)))))
