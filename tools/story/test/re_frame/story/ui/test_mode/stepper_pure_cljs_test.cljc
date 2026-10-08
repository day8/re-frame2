(ns re-frame.story.ui.test-mode.stepper-pure-cljs-test
  "JVM + CLJS pure-data tests for the play step-debugger helpers
  (spec/009 §Play step-debugger).

  The substantive UI is CLJS but every projection function the view
  consumes is .cljc + pure so the JVM test corpus pins the contract
  without booting Reagent."
  (:require [clojure.test :refer [deftest is]]
            [re-frame.story.ui.test-mode.stepper-pure :as rf.story.ui.test-mode.stepper-pure]))

;; ---- enrich-statuses ----------------------------------------------------

(deftest enrich-statuses-stamps-position-and-bp
  (let [input [{:index 0 :event [:e/a] :label ":e/a" :status :event}
               {:index 1 :event [:rf.assert/path-equals [:a] 1]
                :label "lbl" :status :pass}
               {:index 2 :event [:rf.assert/path-equals [:b] 2]
                :label "lbl" :status :fail}]
        out   (rf.story.ui.test-mode.stepper-pure/enrich-statuses input 1 #{2})]
    (is (= [[:done false :event] [:current false :pass] [:pending true :fail]]
           (mapv (juxt :position :breakpoint? :outcome) out)))))

;; ---- progress-label -----------------------------------------------------

(deftest progress-label-shapes
  (is (= "ready · 3 steps" (rf.story.ui.test-mode.stepper-pure/progress-label 0 3)))
  (is (= "step 1 of 3"     (rf.story.ui.test-mode.stepper-pure/progress-label 1 3)))
  (is (= "done · 3 of 3"   (rf.story.ui.test-mode.stepper-pure/progress-label 3 3)))
  (is (= "no steps"        (rf.story.ui.test-mode.stepper-pure/progress-label 0 0))))

(deftest progress-label-safe-on-bad-inputs
  (is (= "" (rf.story.ui.test-mode.stepper-pure/progress-label nil 3))))

;; ---- can-step? / can-step-back? / can-rewind? ---------------------------

(deftest can-step-requires-active-and-not-end
  (is (true?  (rf.story.ui.test-mode.stepper-pure/can-step? {:active? true :cursor 2 :total 3})))
  (is (false? (rf.story.ui.test-mode.stepper-pure/can-step? {:active? true :cursor 3 :total 3})))
  (is (false? (rf.story.ui.test-mode.stepper-pure/can-step? {:active? false :cursor 0 :total 3}))))

(deftest can-step-back-requires-active-and-not-start
  (is (true?  (rf.story.ui.test-mode.stepper-pure/can-step-back? {:active? true :cursor 1})))
  (is (false? (rf.story.ui.test-mode.stepper-pure/can-step-back? {:active? true :cursor 0})))
  (is (false? (rf.story.ui.test-mode.stepper-pure/can-step-back? {:active? false :cursor 1}))))

(deftest can-rewind-mirrors-step-back
  (is (true?  (rf.story.ui.test-mode.stepper-pure/can-rewind? {:active? true :cursor 2})))
  (is (false? (rf.story.ui.test-mode.stepper-pure/can-rewind? {:active? true :cursor 0})))
  (is (false? (rf.story.ui.test-mode.stepper-pure/can-rewind? {:active? false :cursor 2}))))

;; ---- can-pause? / can-resume? -------------------------------------------

(deftest can-pause-only-while-playing
  (is (true?  (rf.story.ui.test-mode.stepper-pure/can-pause? {:active? true :auto-playing? true})))
  (is (false? (rf.story.ui.test-mode.stepper-pure/can-pause? {:active? true :auto-playing? false})))
  (is (false? (rf.story.ui.test-mode.stepper-pure/can-pause? {:active? false :auto-playing? true}))))

(deftest can-resume-not-while-playing-not-at-end
  (is (true?  (rf.story.ui.test-mode.stepper-pure/can-resume? {:active? true :auto-playing? false
                                 :cursor 0 :total 3})))
  (is (false? (rf.story.ui.test-mode.stepper-pure/can-resume? {:active? true :auto-playing? true
                                 :cursor 0 :total 3}))
      "already playing — no resume offered")
  (is (false? (rf.story.ui.test-mode.stepper-pure/can-resume? {:active? true :auto-playing? false
                                 :cursor 3 :total 3}))
      "parked at end — no resume offered"))

;; ---- breakpoint-hit? ----------------------------------------------------

(deftest breakpoint-hit-returns-true-on-match
  (is (true?  (rf.story.ui.test-mode.stepper-pure/breakpoint-hit? 2 #{2})))
  (is (false? (rf.story.ui.test-mode.stepper-pure/breakpoint-hit? 1 #{2}))))

;; ---- step-statuses (full-script step list) ------------------------------

;; Every step type gets a row, not just the dispatch steps, and a step with
;; no result yet reads neutral.
(deftest step-statuses-one-row-per-step
  (let [steps [[:dispatch-sync [:e/a]]
               [:wait 50]
               [:assert-db [:n] 5]
               [:assert-dom "div.x" :visible]
               [:click "button.y"]]
        rows  (rf.story.ui.test-mode.stepper-pure/step-statuses steps [])]
    (is (= [0 1 2 3 4] (mapv :index rows)))
    (is (= steps (mapv :step rows)))
    (is (= ["dispatch-sync [:e/a]" "wait 50ms" "assert-db [:n] = 5"]
           (mapv :label (take 3 rows))))
    (is (every? #(= :event (:status %)) rows))))

(deftest step-statuses-outcome-from-results
  (let [steps   [[:assert-db [:n] 5]
                 [:assert-db [:n] 9]
                 [:assert-dom "x" :visible]
                 [:dispatch [:e/a]]]
        results [{:type :assert-db  :passed? true}
                 {:type :assert-db  :passed? false}
                 {:type :assert-dom :passed? false :skipped? true}
                 {:type :dispatch   :passed? nil}]
        rows    (rf.story.ui.test-mode.stepper-pure/step-statuses steps results)]
    (is (= [:pass :fail :skip :event] (mapv :status rows)))))
