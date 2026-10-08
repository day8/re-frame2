(ns re-frame.story.ui.play-status-cljs-test
  "Tests for the play-status chip + failure-banner pure helpers.
  `re-frame.story.ui.play-status` is CLJS-only, so every test here is too;
  the runner fns the helpers read are pinned on both lanes by
  `runner_test.cljc`."
  #?(:cljs
     (:require [clojure.test :refer [deftest is]]
               [re-frame.story.play.runner :as rf.story.play.runner]
               [re-frame.story.ui.play-status :as rf.story.ui.play-status])))

#?(:cljs
   (deftest chip-label-idle
     (is (= "Play: IDLE" (rf.story.ui.play-status/chip-label nil)))))

#?(:cljs
   (deftest chip-label-running
     (let [s (-> (rf.story.play.runner/parse-spec {:script [[:wait 1] [:wait 2]]})
                 rf.story.play.runner/initial-state
                 (assoc :status :running :step-idx 1))]
       (is (= "Play: RUNNING (step 2/2)" (rf.story.ui.play-status/chip-label s))))))

#?(:cljs
   (deftest banner-text-nil-for-non-failure
     (is (nil? (rf.story.ui.play-status/banner-text nil)))
     (let [s (-> (rf.story.play.runner/parse-spec {:script [[:wait 1]]})
                 rf.story.play.runner/initial-state
                 (assoc :status :pass))]
       (is (nil? (rf.story.ui.play-status/banner-text s))))))

;; The step that passes first is what pins the FIRST FAILED pick: a
;; `(first results)` read would name step 1.
#?(:cljs
   (deftest banner-text-renders-first-failure
     (let [base      (-> (rf.story.play.runner/parse-spec
                           {:script [[:assert-db [:k] 1]
                                     [:assert-db [:k] 2]]})
                         rf.story.play.runner/initial-state
                         (rf.story.play.runner/start 0))
           with-pass (rf.story.play.runner/record-step-result base
                       (rf.story.play.runner/step-pass 0 [:assert-db [:k] 1]))
           with-fail (rf.story.play.runner/record-step-result with-pass
                       (rf.story.play.runner/step-fail 1 [:assert-db [:k] 2]
                                                       {:message "got 1, expected 2"}))
           final     (rf.story.play.runner/finish with-fail 1)]
       (is (= "1 failure — step 2: assert-db [:k] = 2 — got 1, expected 2"
              (rf.story.ui.play-status/banner-text final))))))

#?(:cljs
   (deftest chip-label-multi-no-name-uses-default
     (is (= "Play (default) | IDLE"
            (rf.story.ui.play-status/chip-label-multi nil nil)))))

#?(:cljs
   (deftest chip-label-multi-running
     (let [s (-> (rf.story.play.runner/parse-spec {:script [[:wait 1] [:wait 2]]})
                 rf.story.play.runner/initial-state
                 (assoc :status :running :step-idx 1))]
       (is (= "Play error path | RUNNING (step 2/2)"
              (rf.story.ui.play-status/chip-label-multi s "error path"))))))

#?(:cljs
   (deftest dropdown-row-status-shapes
     (is (= "IDLE" (rf.story.ui.play-status/dropdown-row-status nil)))
     (is (= "RUN"  (rf.story.ui.play-status/dropdown-row-status {:status :running})))))
