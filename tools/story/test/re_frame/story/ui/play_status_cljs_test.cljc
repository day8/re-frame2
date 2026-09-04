(ns re-frame.story.ui.play-status-cljs-test
  "Tests for the play-status chip + failure-banner UI (rf2-8i2a9).

  Pure helpers run on both JVM and CLJS. The chip/banner Reagent
  components themselves are CLJS-only — their hiccup output is
  exercised via the public pure helpers (`chip-label`, `banner-text`)
  so JVM tests can verify the rendering decisions without DOM."
  (:require [clojure.test :refer [deftest is testing]]
            [re-frame.story.play.runner :as rf.story.play.runner]
            #?(:cljs [re-frame.story.ui.play-status :as rf.story.ui.play-status])))

;; ---- chip-label (pure) ---------------------------------------------------

#?(:cljs
   (deftest chip-label-idle
     (testing "nil state renders as 'Play: IDLE'"
       (is (= "Play: IDLE" (rf.story.ui.play-status/chip-label nil))))))

#?(:cljs
   (deftest chip-label-running
     (testing "running state renders progress"
       (let [s (-> (rf.story.play.runner/parse-spec {:script [[:wait 1] [:wait 2]]})
                   rf.story.play.runner/initial-state
                   (assoc :status :running :step-idx 1))]
         (is (= "Play: RUNNING (step 2/2)" (rf.story.ui.play-status/chip-label s)))))))

#?(:cljs
   (deftest chip-label-pass
     (testing "pass state renders the step total"
       (let [s (-> (rf.story.play.runner/parse-spec {:script [[:wait 1]]})
                   rf.story.play.runner/initial-state
                   (assoc :status :pass))]
         (is (= "Play: PASS (1 steps)" (rf.story.ui.play-status/chip-label s)))))))

#?(:cljs
   (deftest chip-label-fail
     (testing "fail state renders the progress + total"
       (let [s (-> (rf.story.play.runner/parse-spec {:script [[:wait 1] [:wait 2] [:wait 3]]})
                   rf.story.play.runner/initial-state
                   (assoc :status :fail :step-idx 2))]
         (is (= "Play: FAIL (2/3 steps)" (rf.story.ui.play-status/chip-label s)))))))

;; ---- banner-text (pure) --------------------------------------------------

#?(:cljs
   (deftest banner-text-nil-for-non-failure
     (testing "banner-text returns nil unless status is :fail"
       (is (nil? (rf.story.ui.play-status/banner-text nil)))
       (let [s (-> (rf.story.play.runner/parse-spec {:script [[:wait 1]]})
                   rf.story.play.runner/initial-state
                   (assoc :status :pass))]
         (is (nil? (rf.story.ui.play-status/banner-text s)))))))

#?(:cljs
   (deftest banner-text-renders-first-failure
     (testing "banner-text describes the first failing step"
       (let [base    (-> (rf.story.play.runner/parse-spec
                           {:script [[:assert-db [:k] 1]
                                     [:assert-db [:k] 2]]})
                         rf.story.play.runner/initial-state
                         (rf.story.play.runner/start 0))
             with-pass (rf.story.play.runner/record-step-result base
                         (rf.story.play.runner/step-pass 0 [:assert-db [:k] 1]))
             with-fail (rf.story.play.runner/record-step-result with-pass
                         (rf.story.play.runner/step-fail 1 [:assert-db [:k] 2]
                                           {:message "got 1, expected 2"}))
             final    (rf.story.play.runner/finish with-fail 1)
             text     (rf.story.ui.play-status/banner-text final)]
         (is (string? text))
         (is (re-find #"1 failure" text))
         (is (re-find #"step 2" text))
         (is (re-find #"assert-db" text))
         (is (re-find #"got 1, expected 2" text))))))

;; ---- JVM-side pure helper exercises --------------------------------------
;;
;; The play-status ns itself is CLJS-only so JVM gates the require under
;; the reader conditional above. We still want JVM coverage of the
;; underlying pure runner fns the banner / chip read — which already
;; live in `runner_test.cljc`. The four tests below are a smoke check
;; that the runner exports survive a separate JVM-side require + a
;; minimal banner-shape assertion (without depending on the .cljs file).

(deftest jvm-progress-and-summary
  (testing "runner helpers used by the chip render the expected strings"
    (let [s (-> (rf.story.play.runner/parse-spec {:script [[:wait 1] [:wait 2]]})
                rf.story.play.runner/initial-state
                (assoc :status :running :step-idx 1))]
      (is (= "RUNNING (step 2/2)" (rf.story.play.runner/progress-str s))))))

(deftest jvm-banner-summary
  (testing "fail-summary describes the first failed result"
    (let [base   (-> (rf.story.play.runner/parse-spec
                       {:script [[:assert-db [:k] 1]
                                 [:assert-db [:k] 2]]})
                     rf.story.play.runner/initial-state
                     (rf.story.play.runner/start 0))
          failed (-> base
                     (rf.story.play.runner/record-step-result
                       (rf.story.play.runner/step-pass 0 [:assert-db [:k] 1]))
                     (rf.story.play.runner/record-step-result
                       (rf.story.play.runner/step-fail 1 [:assert-db [:k] 2]
                                         {:message "no"}))
                     (rf.story.play.runner/finish 1))
          summ   (rf.story.play.runner/fail-summary failed)]
      (is (= 1 (:count summ)))
      (is (= 1 (:idx (:first summ)))))))

;; ---- multi-play helpers (rf2-tl7zk) -------------------------------------

#?(:cljs
   (deftest chip-label-multi-idle
     (testing "nil state with a play name renders 'Play <name> | IDLE'"
       (is (= "Play happy path | IDLE"
              (rf.story.ui.play-status/chip-label-multi nil "happy path"))))))

#?(:cljs
   (deftest chip-label-multi-no-name-uses-default
     (testing "no play name falls back to (default)"
       (is (= "Play (default) | IDLE"
              (rf.story.ui.play-status/chip-label-multi nil nil))))))

#?(:cljs
   (deftest chip-label-multi-running
     (testing "running state shows progress alongside the play name"
       (let [s (-> (rf.story.play.runner/parse-spec {:script [[:wait 1] [:wait 2]]})
                   rf.story.play.runner/initial-state
                   (assoc :status :running :step-idx 1))]
         (is (= "Play error path | RUNNING (step 2/2)"
                (rf.story.ui.play-status/chip-label-multi s "error path")))))))

#?(:cljs
   (deftest dropdown-row-status-shapes
     (testing "dropdown-row-status renders short status badges"
       (is (= "IDLE" (rf.story.ui.play-status/dropdown-row-status nil)))
       (is (= "IDLE" (rf.story.ui.play-status/dropdown-row-status {:status :idle})))
       (is (= "RUN"  (rf.story.ui.play-status/dropdown-row-status {:status :running})))
       (is (= "PASS" (rf.story.ui.play-status/dropdown-row-status {:status :pass})))
       (is (= "FAIL" (rf.story.ui.play-status/dropdown-row-status {:status :fail}))))))
