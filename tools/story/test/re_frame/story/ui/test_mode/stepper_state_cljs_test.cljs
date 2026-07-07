(ns re-frame.story.ui.test-mode.stepper-state-cljs-test
  "CLJS tests for the play step-debugger local state (rf2-ulw5m + spec/009
  §Play step-debugger).

  The substantive runtime calls (`runtime/reset-variant`,
  `play/begin-stepper!`, `rf/restore-epoch!`) are exercised by the
  feature-load browser gate. These unit tests pin the mutator semantics
  by redef-ing the substrate calls so the slot transitions can be
  observed deterministically without booting the runtime."
  (:require [cljs.test :refer-macros [deftest is testing use-fixtures async]]
            [re-frame.core :as rf]
            [re-frame.story.assertions :as assertions]
            [re-frame.story.play :as play]
            [re-frame.story.registrar :as registrar]
            [re-frame.story.runtime :as runtime]
            [re-frame.story.ui.test-mode.stepper-state :as st]))

;; ---- fixtures ------------------------------------------------------------

(defn reset-results! []
  (reset! st/results-atom {}))

(use-fixtures :each {:before reset-results! :after reset-results!})

;; A helper that seeds a slot directly without going through begin! — the
;; tests that exercise step!/step-back!/rewind! use this to skip the async
;; reset-variant promise.

(defn- seed-slot!
  [variant-id play-steps]
  (let [total (count play-steps)]
    (swap! st/results-atom assoc variant-id
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
      (with-redefs [play/step-once!    (fn [v]
                                         (swap! dispatched conj v))
                    rf/epoch-history (fn [_]
                                          [{:epoch-id :epoch/before-a}])
                    assertions/read-assertions (fn [_] [])]
        (st/step! vid)
        (let [s (get @st/results-atom vid)]
          (is (= [vid] @dispatched) "play/step-once! is called with the variant id")
          (is (= 1 (:cursor s))     "cursor increments to 1")
          (is (= [:epoch/seed :epoch/before-a] (:epoch-stack s))
              "the pre-step epoch-id is pushed onto the stack"))))))

(deftest step-noops-at-end
  (testing "step! does nothing when cursor = total"
    (let [vid        :story.unit/end
          dispatched (atom [])]
      (seed-slot! vid [[:e/a]])
      (swap! st/results-atom assoc-in [vid :cursor] 1)
      (with-redefs [play/step-once!    (fn [v] (swap! dispatched conj v))
                    rf/epoch-history (fn [_] [{:epoch-id :x}])
                    assertions/read-assertions (fn [_] [])]
        (st/step! vid)
        (is (empty? @dispatched) "play/step-once! is NOT called")
        (is (= 1 (:cursor (get @st/results-atom vid)))
            "cursor stays at total")))))

(deftest step-noops-when-inactive
  (testing "step! does nothing when the slot is :active? false"
    (let [vid        :story.unit/inactive
          dispatched (atom [])]
      (seed-slot! vid [[:e/a]])
      (swap! st/results-atom assoc-in [vid :active?] false)
      (with-redefs [play/step-once!    (fn [v] (swap! dispatched conj v))
                    rf/epoch-history (fn [_] [{:epoch-id :x}])
                    assertions/read-assertions (fn [_] [])]
        (st/step! vid)
        (is (empty? @dispatched))))))

;; ---- step-back! ----------------------------------------------------------

(deftest step-back-pops-and-restores
  (testing "step-back! restores against the CURRENT top of the epoch
            stack (the pre-image of the step being undone), then pops
            it — rf2-4e545l finding 7. The prior implementation popped
            BEFORE peeking, which restored one epoch too far (the entry
            one further down the stack) for cursor >= 2."
    (let [vid      :story.unit/back
          restored (atom [])]
      (seed-slot! vid [[:e/a] [:e/b]])
      ;; Pretend we've already stepped twice: cursor=2, stack has two
      ;; pre-images on top of the seed.
      (swap! st/results-atom update vid
             (fn [s] (-> s
                         (assoc :cursor 2)
                         (assoc :epoch-stack [:epoch/seed
                                              :epoch/before-a
                                              :epoch/before-b]))))
      (with-redefs [rf/restore-epoch! (fn [v eid]
                                       (swap! restored conj [v eid]))
                    rf/epoch-history (fn [_] [{:epoch-id :x}])
                    assertions/read-assertions (fn [_] [])]
        (st/step-back! vid)
        (let [s (get @st/results-atom vid)]
          (is (= [[vid :epoch/before-b]] @restored)
              "restored against the TOP of the stack (before-b) — the
               pre-image of the step cursor=2 just ran, NOT the entry
               one further down (before-a, the pre-fix bug)")
          (is (= 1 (:cursor s)) "cursor decrements to 1")
          (is (= [:epoch/seed :epoch/before-a] (:epoch-stack s))
              "stack popped (the top is removed regardless of the
               peek/pop ordering fix — only the RESTORE target
               changed)"))))))

(deftest step-back-cursor-2-plus-does-not-undershoot
  (testing "rf2-4e545l finding 7 — reproduces the reported scenario
            directly: `begin!` seeds :epoch-stack with the pre-play
            epoch, and step 0 (no domino between begin! and the first
            step!) pushes that SAME epoch again, so the stack carries a
            duplicate bottom entry [S0 S0 S1 S2 …]. Stepping back from
            cursor=3 must restore S2 (the state right before the THIRD
            step ran) — not S1, which the pre-fix `(peek (butlast
            stack))` under-shoot returned."
    (let [vid      :story.unit/back-cursor3
          restored (atom [])]
      (seed-slot! vid [[:e/a] [:e/b] [:e/c]])
      (swap! st/results-atom update vid
             (fn [s] (-> s
                         (assoc :cursor 3)
                         ;; The real duplicate-seed shape begin!/step!
                         ;; build: seed pushed twice (S0 S0), then one
                         ;; genuine pre-image per subsequent step (S1, S2).
                         (assoc :epoch-stack [:epoch/s0 :epoch/s0
                                              :epoch/s1 :epoch/s2]))))
      (with-redefs [rf/restore-epoch! (fn [v eid]
                                       (swap! restored conj [v eid]))
                    rf/epoch-history (fn [_] [{:epoch-id :x}])
                    assertions/read-assertions (fn [_] [])]
        (st/step-back! vid)
        (is (= [[vid :epoch/s2]] @restored)
            "restores S2 — the pre-image of the step just taken —
             rather than S1 (the pre-fix off-by-one under-shoot)")
        (is (= 2 (:cursor (get @st/results-atom vid))))
        ;; Stepping back again from cursor=2 must land on S1, exercising
        ;; the SAME correct behaviour continues past the duplicate-seed
        ;; entry at the bottom.
        (st/step-back! vid)
        (is (= [[vid :epoch/s2] [vid :epoch/s1]] @restored)
            "a second step-back restores S1 — the duplicate seed at the
             bottom of the stack is consumed correctly, never restoring
             one epoch too far")
        (is (= 1 (:cursor (get @st/results-atom vid))))))))

(deftest step-back-noops-at-start
  (testing "step-back! does nothing when cursor is 0"
    (let [vid      :story.unit/back-start
          restored (atom [])]
      (seed-slot! vid [[:e/a]])
      (with-redefs [rf/restore-epoch! (fn [v eid]
                                       (swap! restored conj [v eid]))
                    rf/epoch-history (fn [_] [{:epoch-id :x}])
                    assertions/read-assertions (fn [_] [])]
        (st/step-back! vid)
        (is (empty? @restored))
        (is (= 0 (:cursor (get @st/results-atom vid))))))))

;; ---- rewind! -------------------------------------------------------------

(deftest rewind-resets-to-seed
  (testing "rewind! restores against the bottom-of-stack epoch and zeros
            cursor"
    (let [vid      :story.unit/rewind
          restored (atom [])]
      (seed-slot! vid [[:e/a] [:e/b]])
      (swap! st/results-atom update vid
             (fn [s] (-> s
                         (assoc :cursor 2)
                         (assoc :epoch-stack [:epoch/seed
                                              :epoch/before-a
                                              :epoch/before-b]))))
      (with-redefs [rf/restore-epoch! (fn [v eid]
                                       (swap! restored conj [v eid]))
                    rf/epoch-history (fn [_] [{:epoch-id :x}])
                    assertions/read-assertions (fn [_] [])]
        (st/rewind! vid)
        (is (= [[vid :epoch/seed]] @restored)
            "restored against the SEED epoch-id (bottom of stack) — rf2-luzky:
             that epoch-restore alone rewinds [:rf.story/assertions]; there is
             no longer a side-table accumulator to clear separately")
        (let [s (get @st/results-atom vid)]
          (is (= 0 (:cursor s)))
          (is (= [:epoch/seed] (:epoch-stack s))))))))

(deftest rewind-clears-interval
  (testing "rewind! pauses any in-flight auto-play"
    (let [vid :story.unit/rewind-autoplay]
      (seed-slot! vid [[:e/a]])
      (swap! st/results-atom update vid
             (fn [s] (-> s
                         (assoc :cursor 1)
                         (assoc :auto-playing? true)
                         (assoc :interval-id 999))))
      (with-redefs [rf/restore-epoch! (fn [_ _] nil)
                    rf/epoch-history (fn [_] [])
                    assertions/read-assertions (fn [_] [])
                    js/clearInterval (fn [_] nil)]
        (st/rewind! vid)
        (let [s (get @st/results-atom vid)]
          (is (false? (:auto-playing? s)))
          (is (nil?   (:interval-id   s))))))))

;; ---- pause! / resume! ----------------------------------------------------

(deftest pause-clears-interval
  (testing "pause! clears the interval and flips :auto-playing? to false"
    (let [vid     :story.unit/pause
          cleared (atom [])]
      (seed-slot! vid [[:e/a]])
      (swap! st/results-atom update vid
             (fn [s] (assoc s :auto-playing? true :interval-id 42)))
      (with-redefs [js/clearInterval (fn [h] (swap! cleared conj h))]
        (st/pause! vid)
        (is (= [42] @cleared))
        (is (false? (:auto-playing? (get @st/results-atom vid))))
        (is (nil?   (:interval-id   (get @st/results-atom vid))))))))

(deftest resume-noops-at-end
  (testing "resume! does nothing when parked at the end"
    (let [vid    :story.unit/resume-end
          inter  (atom 0)]
      (seed-slot! vid [[:e/a]])
      (swap! st/results-atom assoc-in [vid :cursor] 1)
      (with-redefs [js/setInterval (fn [_ _]
                                     (swap! inter inc)
                                     :id)]
        (st/resume! vid)
        (is (zero? @inter) "no interval is set")
        (is (false? (:auto-playing? (get @st/results-atom vid))))))))

(deftest resume-sets-auto-playing
  (testing "resume! sets :auto-playing? true + records the interval id"
    (let [vid :story.unit/resume]
      (seed-slot! vid [[:e/a] [:e/b]])
      (with-redefs [js/setInterval (fn [_ _] :iid-99)]
        (st/resume! vid)
        (let [s (get @st/results-atom vid)]
          (is (true?  (:auto-playing? s)))
          (is (= :iid-99 (:interval-id s))))))))

;; ---- toggle-breakpoint! --------------------------------------------------

(deftest toggle-breakpoint-adds-and-removes
  (testing "toggle-breakpoint! adds when absent, removes when present"
    (let [vid :story.unit/bp]
      (seed-slot! vid [[:e/a] [:e/b] [:e/c]])
      (with-redefs [assertions/read-assertions (fn [_] [])]
        (st/toggle-breakpoint! vid 1)
        (is (= #{1} (:breakpoints (get @st/results-atom vid))))
        (st/toggle-breakpoint! vid 2)
        (is (= #{1 2} (:breakpoints (get @st/results-atom vid))))
        (st/toggle-breakpoint! vid 1)
        (is (= #{2} (:breakpoints (get @st/results-atom vid))))))))

(deftest toggle-breakpoint-noops-when-inactive
  (testing "toggle-breakpoint! is a no-op when there is no slot"
    (let [vid :story.unit/bp-noslot]
      (st/toggle-breakpoint! vid 0)
      (is (nil? (get @st/results-atom vid))))))

;; ---- end! ---------------------------------------------------------------

(deftest end-clears-everything
  (testing "end! tears down the substrate, clears the interval, and
            removes the slot"
    (let [vid      :story.unit/end-all
          ended    (atom [])
          cleared  (atom [])]
      (seed-slot! vid [[:e/a]])
      (swap! st/results-atom update vid
             (fn [s] (assoc s :auto-playing? true :interval-id 77)))
      (with-redefs [play/end-stepper! (fn [v] (swap! ended conj v))
                    js/clearInterval  (fn [h] (swap! cleared conj h))]
        (st/end! vid)
        (is (= [vid] @ended)
            "play/end-stepper! is called against the variant id")
        (is (= [77] @cleared))
        (is (nil? (get @st/results-atom vid))
            "the slot is removed from the local atom")))))
