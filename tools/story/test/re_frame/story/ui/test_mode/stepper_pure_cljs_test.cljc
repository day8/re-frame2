(ns re-frame.story.ui.test-mode.stepper-pure-cljs-test
  "JVM + CLJS pure-data tests for the play step-debugger helpers
  (rf2-ulw5m + spec/009 §Play step-debugger).

  The substantive UI is CLJS but every projection function the view
  consumes is .cljc + pure so the JVM test corpus pins the contract
  without booting Reagent."
  (:require [clojure.test :refer [deftest is testing]]
            [re-frame.story.ui.test-mode.stepper-pure :as rf.story.ui.test-mode.stepper-pure]))

;; ---- step-position -------------------------------------------------------

(deftest step-position-cursor-zero
  (testing "with cursor 0 the first row is :current; later rows pending"
    (is (= :current (rf.story.ui.test-mode.stepper-pure/step-position 0 0)))
    (is (= :pending (rf.story.ui.test-mode.stepper-pure/step-position 1 0)))
    (is (= :pending (rf.story.ui.test-mode.stepper-pure/step-position 5 0)))))

(deftest step-position-mid-run
  (testing "with cursor 2 of 5: indices 0/1 are :done, 2 is :current, 3/4 pending"
    (is (= :done    (rf.story.ui.test-mode.stepper-pure/step-position 0 2)))
    (is (= :done    (rf.story.ui.test-mode.stepper-pure/step-position 1 2)))
    (is (= :current (rf.story.ui.test-mode.stepper-pure/step-position 2 2)))
    (is (= :pending (rf.story.ui.test-mode.stepper-pure/step-position 3 2)))
    (is (= :pending (rf.story.ui.test-mode.stepper-pure/step-position 4 2)))))

(deftest step-position-parked-at-end
  (testing "cursor = total ⇒ every row is :done; nothing is :current"
    (is (= :done (rf.story.ui.test-mode.stepper-pure/step-position 0 3)))
    (is (= :done (rf.story.ui.test-mode.stepper-pure/step-position 1 3)))
    (is (= :done (rf.story.ui.test-mode.stepper-pure/step-position 2 3)))))

;; ---- enrich-statuses ----------------------------------------------------

(deftest enrich-statuses-stamps-position-and-bp
  (testing "each input row gets :position + :breakpoint? + :outcome"
    (let [input    [{:index 0 :event [:e/a] :label ":e/a" :status :event}
                    {:index 1 :event [:rf.assert/path-equals [:a] 1]
                     :label "lbl" :status :pass}
                    {:index 2 :event [:rf.assert/path-equals [:b] 2]
                     :label "lbl" :status :fail}]
          out      (rf.story.ui.test-mode.stepper-pure/enrich-statuses input 1 #{2})
          [a b c]  out]
      (is (= :done    (:position a)))
      (is (= :current (:position b)))
      (is (= :pending (:position c)))
      (is (false?     (:breakpoint? a)))
      (is (false?     (:breakpoint? b)))
      (is (true?      (:breakpoint? c)))
      (is (= :event   (:outcome a)))
      (is (= :pass    (:outcome b)))
      (is (= :fail    (:outcome c))))))

(deftest enrich-statuses-empty-breakpoints
  (testing "nil breakpoints input is treated as empty set"
    (let [out (rf.story.ui.test-mode.stepper-pure/enrich-statuses
                [{:index 0 :event [:e/a] :label ":e/a" :status :event}]
                0 nil)]
      (is (false? (:breakpoint? (first out)))))))

;; ---- progress-label -----------------------------------------------------

(deftest progress-label-shapes
  (testing "renders the four canonical strings"
    (is (= "ready · 3 steps" (rf.story.ui.test-mode.stepper-pure/progress-label 0 3)))
    (is (= "step 1 of 3"     (rf.story.ui.test-mode.stepper-pure/progress-label 1 3)))
    (is (= "step 2 of 3"     (rf.story.ui.test-mode.stepper-pure/progress-label 2 3)))
    (is (= "done · 3 of 3"   (rf.story.ui.test-mode.stepper-pure/progress-label 3 3)))
    (is (= "no steps"        (rf.story.ui.test-mode.stepper-pure/progress-label 0 0)))))

(deftest progress-label-safe-on-bad-inputs
  (testing "non-integers collapse to the empty string"
    (is (= "" (rf.story.ui.test-mode.stepper-pure/progress-label nil 3)))
    (is (= "" (rf.story.ui.test-mode.stepper-pure/progress-label 1 nil)))
    (is (= "" (rf.story.ui.test-mode.stepper-pure/progress-label "x" "y")))))

;; ---- at-end? / at-start? ------------------------------------------------

(deftest at-end-predicate
  (is (true?  (rf.story.ui.test-mode.stepper-pure/at-end? 3 3)))
  (is (true?  (rf.story.ui.test-mode.stepper-pure/at-end? 4 3)))
  (is (false? (rf.story.ui.test-mode.stepper-pure/at-end? 2 3)))
  (is (false? (rf.story.ui.test-mode.stepper-pure/at-end? 0 3))))

(deftest at-start-predicate
  (is (true?  (rf.story.ui.test-mode.stepper-pure/at-start? 0)))
  (is (true?  (rf.story.ui.test-mode.stepper-pure/at-start? nil)))
  (is (false? (rf.story.ui.test-mode.stepper-pure/at-start? 1))))

;; ---- can-step? / can-step-back? / can-rewind? ---------------------------

(deftest can-step-requires-active-and-not-end
  (is (true?  (rf.story.ui.test-mode.stepper-pure/can-step? {:active? true :cursor 0 :total 3})))
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
  (is (false? (rf.story.ui.test-mode.stepper-pure/breakpoint-hit? 1 #{2})))
  (is (false? (rf.story.ui.test-mode.stepper-pure/breakpoint-hit? 2 #{})))
  (is (false? (rf.story.ui.test-mode.stepper-pure/breakpoint-hit? 2 nil)))
  (is (false? (rf.story.ui.test-mode.stepper-pure/breakpoint-hit? 2 [2]))
      "non-set inputs are rejected — only a set returns hit"))

;; ---- step-statuses (rf2-ee38b.3 — full-script step list) ----------------

(deftest step-statuses-one-row-per-step
  (testing "step-statuses produces one row per coerced step, covering EVERY
            step type — not just the dispatch steps the legacy projection
            surfaced"
    (let [steps   [[:dispatch-sync [:e/a]]
                   [:wait 50]
                   [:assert-db [:n] 5]
                   [:assert-dom "div.x" :visible]
                   [:click "button.y"]]
          rows    (rf.story.ui.test-mode.stepper-pure/step-statuses steps [])]
      (is (= 5 (count rows)) "all five step types render a row")
      (is (= [0 1 2 3 4] (mapv :index rows)))
      (is (= steps (mapv :step rows)))
      ;; Labels come from runner/step-summary (every step type, not just
      ;; the dispatch event id).
      (is (= "dispatch-sync [:e/a]" (:label (nth rows 0))))
      (is (= "wait 50ms"            (:label (nth rows 1))))
      (is (= "assert-db [:n] = 5"   (:label (nth rows 2))))
      ;; No results yet — every row is neutral.
      (is (every? #(= :event (:status %)) rows)))))

(deftest step-statuses-outcome-from-results
  (testing "each run step takes its outcome from the matching result record"
    (let [steps   [[:assert-db [:n] 5]      ; pass
                   [:assert-db [:n] 9]      ; fail
                   [:assert-dom "x" :visible] ; skip
                   [:dispatch [:e/a]]]      ; neutral (dispatch)
          results [{:type :assert-db  :passed? true}
                   {:type :assert-db  :passed? false}
                   {:type :assert-dom :passed? false :skipped? true}
                   {:type :dispatch   :passed? nil}]
          rows    (rf.story.ui.test-mode.stepper-pure/step-statuses steps results)]
      (is (= :pass  (:status (nth rows 0))))
      (is (= :fail  (:status (nth rows 1))))
      (is (= :skip  (:status (nth rows 2))))
      (is (= :event (:status (nth rows 3)))))))

(deftest step-statuses-not-yet-run-is-neutral
  (testing "steps beyond the results length render neutral (not yet run)"
    (let [steps   [[:assert-db [:n] 5] [:assert-db [:n] 6]]
          results [{:type :assert-db :passed? false}]
          rows    (rf.story.ui.test-mode.stepper-pure/step-statuses steps results)]
      (is (= :fail  (:status (nth rows 0))) "first step ran → its outcome")
      (is (= :event (:status (nth rows 1))) "second step not yet run → neutral"))))

;; ---- play-step-label re-export ------------------------------------------

(deftest play-step-label-roundtrips
  (testing "re-export keeps the same shape as test-mode-pure/play-step-label"
    (is (= ":auth/email-changed"
           (rf.story.ui.test-mode.stepper-pure/play-step-label [:auth/email-changed "x@y"])))
    (is (= "" (rf.story.ui.test-mode.stepper-pure/play-step-label nil)))
    (is (= "" (rf.story.ui.test-mode.stepper-pure/play-step-label [])))))
