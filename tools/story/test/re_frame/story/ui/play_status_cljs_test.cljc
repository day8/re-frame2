(ns re-frame.story.ui.play-status-cljs-test
  "Tests for the play-status chip + failure-banner UI (rf2-8i2a9).

  Pure helpers run on both JVM and CLJS. The chip/banner Reagent
  components themselves are CLJS-only — their hiccup output is
  exercised via the public pure helpers (`chip-label`, `banner-text`)
  so JVM tests can verify the rendering decisions without DOM."
  (:require [clojure.test :refer [deftest is testing]]
            [re-frame.story.play.runner :as runner]
            #?(:cljs [re-frame.story.ui.play-status :as ps])))

;; ---- chip-label (pure) ---------------------------------------------------

#?(:cljs
   (deftest chip-label-idle
     (testing "nil state renders as 'Play: IDLE'"
       (is (= "Play: IDLE" (ps/chip-label nil))))))

#?(:cljs
   (deftest chip-label-running
     (testing "running state renders progress"
       (let [s (-> (runner/parse-spec {:script [[:wait 1] [:wait 2]]})
                   runner/initial-state
                   (assoc :status :running :step-idx 1))]
         (is (= "Play: RUNNING (step 2/2)" (ps/chip-label s)))))))

#?(:cljs
   (deftest chip-label-pass
     (testing "pass state renders the step total"
       (let [s (-> (runner/parse-spec {:script [[:wait 1]]})
                   runner/initial-state
                   (assoc :status :pass))]
         (is (= "Play: PASS (1 steps)" (ps/chip-label s)))))))

#?(:cljs
   (deftest chip-label-fail
     (testing "fail state renders the progress + total"
       (let [s (-> (runner/parse-spec {:script [[:wait 1] [:wait 2] [:wait 3]]})
                   runner/initial-state
                   (assoc :status :fail :step-idx 2))]
         (is (= "Play: FAIL (2/3 steps)" (ps/chip-label s)))))))

;; ---- banner-text (pure) --------------------------------------------------

#?(:cljs
   (deftest banner-text-nil-for-non-failure
     (testing "banner-text returns nil unless status is :fail"
       (is (nil? (ps/banner-text nil)))
       (let [s (-> (runner/parse-spec {:script [[:wait 1]]})
                   runner/initial-state
                   (assoc :status :pass))]
         (is (nil? (ps/banner-text s)))))))

#?(:cljs
   (deftest banner-text-renders-first-failure
     (testing "banner-text describes the first failing step"
       (let [base    (-> (runner/parse-spec
                           {:script [[:assert-db [:k] 1]
                                     [:assert-db [:k] 2]]})
                         runner/initial-state
                         (runner/start 0))
             with-pass (runner/record-step-result base
                         (runner/step-pass 0 [:assert-db [:k] 1]))
             with-fail (runner/record-step-result with-pass
                         (runner/step-fail 1 [:assert-db [:k] 2]
                                           {:message "got 1, expected 2"}))
             final    (runner/finish with-fail 1)
             text     (ps/banner-text final)]
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
    (let [s (-> (runner/parse-spec {:script [[:wait 1] [:wait 2]]})
                runner/initial-state
                (assoc :status :running :step-idx 1))]
      (is (= "RUNNING (step 2/2)" (runner/progress-str s))))))

(deftest jvm-banner-summary
  (testing "fail-summary describes the first failed result"
    (let [base   (-> (runner/parse-spec
                       {:script [[:assert-db [:k] 1]
                                 [:assert-db [:k] 2]]})
                     runner/initial-state
                     (runner/start 0))
          failed (-> base
                     (runner/record-step-result
                       (runner/step-pass 0 [:assert-db [:k] 1]))
                     (runner/record-step-result
                       (runner/step-fail 1 [:assert-db [:k] 2]
                                         {:message "no"}))
                     (runner/finish 1))
          summ   (runner/fail-summary failed)]
      (is (= 1 (:count summ)))
      (is (= 1 (:idx (:first summ)))))))

;; ---- multi-play helpers (rf2-tl7zk) -------------------------------------

#?(:cljs
   (deftest chip-label-multi-idle
     (testing "nil state with a play name renders 'Play <name> | IDLE'"
       (is (= "Play happy path | IDLE"
              (ps/chip-label-multi nil "happy path"))))))

#?(:cljs
   (deftest chip-label-multi-no-name-uses-default
     (testing "no play name falls back to (default)"
       (is (= "Play (default) | IDLE"
              (ps/chip-label-multi nil nil))))))

#?(:cljs
   (deftest chip-label-multi-running
     (testing "running state shows progress alongside the play name"
       (let [s (-> (runner/parse-spec {:script [[:wait 1] [:wait 2]]})
                   runner/initial-state
                   (assoc :status :running :step-idx 1))]
         (is (= "Play error path | RUNNING (step 2/2)"
                (ps/chip-label-multi s "error path")))))))

#?(:cljs
   (deftest dropdown-row-status-shapes
     (testing "dropdown-row-status renders short status badges"
       (is (= "IDLE" (ps/dropdown-row-status nil)))
       (is (= "IDLE" (ps/dropdown-row-status {:status :idle})))
       (is (= "RUN"  (ps/dropdown-row-status {:status :running})))
       (is (= "PASS" (ps/dropdown-row-status {:status :pass})))
       (is (= "FAIL" (ps/dropdown-row-status {:status :fail}))))))
