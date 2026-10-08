(ns re-frame.story.ui.test-mode.stepper-state-cljs-test
  "CLJS tests for the play step-debugger local state (spec/009
  §Play step-debugger).

  The substantive runtime calls (`rf.story.runtime/prepare-variant`,
  `rf.story.play/begin-stepper!`, `rf/restore-epoch!`) are exercised by the
  feature-load browser gate. These unit tests pin the mutator semantics
  by redef-ing the substrate calls so the slot transitions can be
  observed deterministically without booting the runtime."
  (:require [cljs.test :refer-macros [deftest is testing use-fixtures]]
            [re-frame.core :as rf]
            [re-frame.story.play :as rf.story.play]
            [re-frame.story.runtime :as rf.story.runtime]
            [re-frame.story.ui.test-mode.stepper-state :as rf.story.ui.test-mode.stepper-state]))

;; ---- fixtures ------------------------------------------------------------

(defn reset-results! []
  (reset! rf.story.ui.test-mode.stepper-state/results-atom {}))

(use-fixtures :each {:before reset-results! :after reset-results!})

;; A helper that seeds a slot directly without going through begin! — the
;; tests that exercise step!/step-back!/rewind! use this to skip the async
;; prepare-variant promise.

(defn- seed-slot!
  [variant-id play-steps]
  (let [total (count play-steps)]
    (swap! rf.story.ui.test-mode.stepper-state/results-atom assoc variant-id
           {:variant-id    variant-id
            :active?       true
            :auto-playing? false
            :cursor        0
            :total         total
            :play-steps    (vec play-steps)
            :statuses      []
            :breakpoints   #{}
            :epoch-stack   [:epoch/seed]
            :interval-id   nil
            :tick-ms       100})))

;; ---- step! ---------------------------------------------------------------

(deftest step-advances-cursor-and-pushes-epoch
  (testing "step! dispatches the next event, increments cursor, and
            pushes the pre-step epoch-id onto the stack"
    (let [vid        :story.unit/step
          events     [[:e/a] [:e/b] [:e/c]]
          dispatched (atom [])]
      (seed-slot! vid events)
      (with-redefs [rf.story.play/step-once!    (fn [v]
                                         (swap! dispatched conj v))
                    rf/epoch-history (fn [_]
                                          [{:epoch-id :epoch/before-a}])]
        (rf.story.ui.test-mode.stepper-state/step! vid)
        (let [s (get @rf.story.ui.test-mode.stepper-state/results-atom vid)]
          (is (= [vid] @dispatched) "rf.story.play/step-once! is called with the variant id")
          (is (= 1 (:cursor s))     "cursor increments to 1")
          (is (= [:epoch/seed :epoch/before-a] (:epoch-stack s))
              "the pre-step epoch-id is pushed onto the stack"))))))

(deftest step-noops-at-end
  (testing "step! does nothing when cursor = total"
    (let [vid        :story.unit/end
          dispatched (atom [])]
      (seed-slot! vid [[:e/a]])
      (swap! rf.story.ui.test-mode.stepper-state/results-atom assoc-in [vid :cursor] 1)
      (with-redefs [rf.story.play/step-once!    (fn [v] (swap! dispatched conj v))
                    rf/epoch-history (fn [_] [{:epoch-id :x}])]
        (rf.story.ui.test-mode.stepper-state/step! vid)
        (is (empty? @dispatched) "rf.story.play/step-once! is NOT called")
        (is (= 1 (:cursor (get @rf.story.ui.test-mode.stepper-state/results-atom vid)))
            "cursor stays at total")))))

;; ---- step-back! ----------------------------------------------------------

(deftest step-back-cursor-2-plus-does-not-undershoot
  (testing "`begin!` seeds the stack with the pre-play epoch and the first
            step! pushes that SAME epoch again, so the stack reads
            [S0 S0 S1 S2]. Stepping back from cursor 3 restores S2, the
            pre-image of the step just taken — not S1, which a
            `(peek (butlast stack))` under-shoot would return"
    (let [vid      :story.unit/back-cursor3
          restored (atom [])]
      (seed-slot! vid [[:e/a] [:e/b] [:e/c]])
      (swap! rf.story.ui.test-mode.stepper-state/results-atom update vid
             assoc :cursor 3 :epoch-stack [:epoch/s0 :epoch/s0 :epoch/s1 :epoch/s2])
      (with-redefs [rf/restore-epoch! (fn [v eid]
                                       (swap! restored conj [v eid]))]
        (rf.story.ui.test-mode.stepper-state/step-back! vid)
        (is (= [[vid :epoch/s2]] @restored))
        (is (= 2 (:cursor (get @rf.story.ui.test-mode.stepper-state/results-atom vid))))
        (rf.story.ui.test-mode.stepper-state/step-back! vid)
        (is (= [[vid :epoch/s2] [vid :epoch/s1]] @restored)
            "a second step-back restores S1, never one epoch too far")
        (is (= 1 (:cursor (get @rf.story.ui.test-mode.stepper-state/results-atom vid))))))))

(deftest step-back-noops-at-start
  (testing "step-back! does nothing when cursor is 0"
    (let [vid      :story.unit/back-start
          restored (atom [])]
      (seed-slot! vid [[:e/a]])
      (with-redefs [rf/restore-epoch! (fn [v eid]
                                       (swap! restored conj [v eid]))]
        (rf.story.ui.test-mode.stepper-state/step-back! vid)
        (is (empty? @restored))
        (is (= 0 (:cursor (get @rf.story.ui.test-mode.stepper-state/results-atom vid))))))))

;; ---- rewind! -------------------------------------------------------------

;; Restoring the bottom-of-stack seed epoch is the whole rewind: the
;; assertions accumulator lives in that app-db, so nothing else is cleared.
(deftest rewind-resets-to-seed
  (let [vid      :story.unit/rewind
        restored (atom [])]
    (seed-slot! vid [[:e/a] [:e/b]])
    (swap! rf.story.ui.test-mode.stepper-state/results-atom update vid
           assoc :cursor 2 :epoch-stack [:epoch/seed :epoch/before-a :epoch/before-b]
                 :auto-playing? true :interval-id 999)
    (with-redefs [rf/restore-epoch! (fn [v eid]
                                     (swap! restored conj [v eid]))
                  js/clearInterval  (fn [_] nil)]
      (rf.story.ui.test-mode.stepper-state/rewind! vid)
      (is (= [[vid :epoch/seed]] @restored))
      (is (= {:cursor 0 :epoch-stack [:epoch/seed] :auto-playing? false :interval-id nil}
             (select-keys (get @rf.story.ui.test-mode.stepper-state/results-atom vid)
                          [:cursor :epoch-stack :auto-playing? :interval-id]))
          "rewind! also stops any in-flight auto-play"))))

;; ---- pause! / resume! ----------------------------------------------------

(deftest pause-clears-interval
  (let [vid     :story.unit/pause
        cleared (atom [])]
    (seed-slot! vid [[:e/a]])
    (swap! rf.story.ui.test-mode.stepper-state/results-atom update vid
           assoc :auto-playing? true :interval-id 42)
    (with-redefs [js/clearInterval (fn [h] (swap! cleared conj h))]
      (rf.story.ui.test-mode.stepper-state/pause! vid)
      (is (= [42] @cleared))
      (is (= {:auto-playing? false :interval-id nil}
             (select-keys (get @rf.story.ui.test-mode.stepper-state/results-atom vid)
                          [:auto-playing? :interval-id]))))))

(deftest resume-noops-at-end
  (testing "resume! does nothing when parked at the end"
    (let [vid    :story.unit/resume-end
          inter  (atom 0)]
      (seed-slot! vid [[:e/a]])
      (swap! rf.story.ui.test-mode.stepper-state/results-atom assoc-in [vid :cursor] 1)
      (with-redefs [js/setInterval (fn [_ _]
                                     (swap! inter inc)
                                     :id)]
        (rf.story.ui.test-mode.stepper-state/resume! vid)
        (is (zero? @inter) "no interval is set")
        (is (false? (:auto-playing? (get @rf.story.ui.test-mode.stepper-state/results-atom vid))))))))

(deftest resume-sets-auto-playing
  (testing "resume! sets :auto-playing? true + records the interval id"
    (let [vid :story.unit/resume]
      (seed-slot! vid [[:e/a] [:e/b]])
      (with-redefs [js/setInterval (fn [_ _] :iid-99)]
        (rf.story.ui.test-mode.stepper-state/resume! vid)
        (let [s (get @rf.story.ui.test-mode.stepper-state/results-atom vid)]
          (is (true?  (:auto-playing? s)))
          (is (= :iid-99 (:interval-id s))))))))

;; ---- toggle-breakpoint! --------------------------------------------------

;; Add 1, add 2, toggle 1 again: only #{2} survives if both the add and the
;; remove work.
(deftest toggle-breakpoint-adds-and-removes
  (let [vid :story.unit/bp]
    (seed-slot! vid [[:e/a] [:e/b] [:e/c]])
    (rf.story.ui.test-mode.stepper-state/toggle-breakpoint! vid 1)
    (rf.story.ui.test-mode.stepper-state/toggle-breakpoint! vid 2)
    (rf.story.ui.test-mode.stepper-state/toggle-breakpoint! vid 1)
    (is (= #{2} (:breakpoints (get @rf.story.ui.test-mode.stepper-state/results-atom vid))))))

;; ---- end! ---------------------------------------------------------------

(deftest end-clears-everything
  (testing "end! tears down the substrate, clears the interval, and
            removes the slot"
    (let [vid      :story.unit/end-all
          ended    (atom [])
          cleared  (atom [])]
      (seed-slot! vid [[:e/a]])
      (swap! rf.story.ui.test-mode.stepper-state/results-atom update vid
             (fn [s] (assoc s :auto-playing? true :interval-id 77)))
      (with-redefs [rf.story.play/end-stepper! (fn [v] (swap! ended conj v))
                    js/clearInterval  (fn [h] (swap! cleared conj h))]
        (rf.story.ui.test-mode.stepper-state/end! vid)
        (is (= [vid] @ended)
            "rf.story.play/end-stepper! is called against the variant id")
        (is (= [77] @cleared))
        (is (nil? (get @rf.story.ui.test-mode.stepper-state/results-atom vid))
            "the slot is removed from the local atom")))))

;; ---- begin! ---------------------------------------------------------------

(deftest begin-reaches-its-start-position-through-the-pre-play-lifecycle
  (testing "begin! prepares the variant through
            `rf.story.runtime/prepare-variant` (phases 0-2, script left
            pending) and NEVER through `reset-variant`, whose promise
            settles only after phase 4 has run the whole script, so the
            section would show cursor 0 over a post-script app-db. The
            behaviour behind the seam is pinned by
            `re-frame.story.stepper-start-cljs-test`."
    (let [vid      :story.unit/begin-seam
          prepared (atom [])
          reset    (atom [])
          ended    (atom [])]
      ;; Each stub carries EVERY arity of the fn it replaces: ClojureScript
      ;; compiles `begin!`'s call to the arity-specialised entry point, which
      ;; a single-arity stub lacks, so the seam would die with `… is not a
      ;; function` instead of being exercised.
      (with-redefs [rf.story.runtime/prepare-variant
                    (fn stub-prepare
                      ([v] (stub-prepare v nil))
                      ([v _opts]
                       (swap! prepared conj v)
                       ;; Never settles, so the continuation stays out of the way.
                       (js/Promise. (fn [_ _] nil))))
                    rf.story.runtime/reset-variant
                    (fn stub-reset
                      ([v] (stub-reset v nil))
                      ([v _opts] (swap! reset conj v) (js/Promise.resolve nil)))

                    rf.story.play/end-stepper!
                    (fn [v] (swap! ended conj v))]
        (rf.story.ui.test-mode.stepper-state/begin! vid)
        (is (= [vid] @prepared)
            "Start prepares the variant through the pre-play lifecycle")
        (is (= [] @reset)
            "Start never runs the full reset/run path")
        (is (= [vid] @ended)
            "any prior stepper session is torn down first")))))
