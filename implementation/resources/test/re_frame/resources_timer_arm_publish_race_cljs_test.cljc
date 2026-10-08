(ns re-frame.resources-timer-arm-publish-race-cljs-test
  "Ordering races in the two-phase timer arm (Spec 016 §Stale and GC
  scheduling / §Polling). An arm reserves its slot with a token before arming,
  so a cleanup that lands between reserve and publish wins and the orphan host
  handle is cancelled; every cancellation and fire is scoped to the exact
  attempt token it observed, so it never erases or reaps a successor. The
  interleavings are driven deterministically with `with-redefs`."
  (:require
   #?(:clj  [clojure.test :refer [deftest is testing use-fixtures]]
      :cljs [cljs.test :refer-macros [deftest is testing use-fixtures]])
   [re-frame.identity :as rf.identity]
   [re-frame.interop :as rf.interop]
   [re-frame.resources]
   [re-frame.resources.test-support]
   [re-frame.resources.timers :as rf.resources.timers]
   [re-frame.test-support :as rf.test-support]
   #?@(:clj  [[re-frame.substrate.plain-atom :as rf.substrate.plain-atom]]
       :cljs [[re-frame.adapter.reagent :as rf.adapter.reagent]])))

(use-fixtures :each
  (rf.test-support/make-reset-runtime-fixture
    #?(:clj  {:adapter rf.substrate.plain-atom/adapter}
       :cljs {:adapter rf.adapter.reagent/adapter})))

(defn- fresh-handle
  "A process-unique opaque host handle, distinguishable by `identical?`."
  []
  #?(:clj (Object.) :cljs #js {}))

(def ^:private frame :race/f)
(defn- rkey [tag] [:rf.scope/global tag {:id 1}])
(defn- tkey [rk kind] [frame (rf.identity/canonical-bytes rk) kind])
(defn- slot [rk kind] (get @rf.resources.timers/timer-table (tkey rk kind)))
(defn- armed? [rk kind] (contains? @rf.resources.timers/timer-table (tkey rk kind)))
(defn- among? [h hs] (boolean (some #(identical? h %) hs)))

(deftest arm-after-cleanup-leaves-no-slot-and-cancels-orphan-handle
  (doseq [[label cleanup!]
          [["release-frame!"  (fn [_rk] (rf.resources.timers/release-frame! frame))]
           ["cancel-for-key!" (fn [rk] (rf.resources.timers/cancel-for-key! frame rk))]
           ["reset-cache!"    (fn [_rk] (rf.resources.timers/reset-cache!))]]]
    (testing (str "cleanup owner: " label)
      (rf.resources.timers/reset-cache!)
      (let [rk           (rkey :race/arm)
            kind         rf.resources.timers/stale-kind
            cancelled    (atom [])
            armed-handle (fresh-handle)]
        (with-redefs [rf.interop/cancel-scheduled! (fn [h] (swap! cancelled conj h) nil)
                      rf.interop/schedule-after!
                      (fn [_thunk _ms]
                        ;; reserved but not yet published: the cleanup wins here
                        (cleanup! rk)
                        armed-handle)]
          (rf.resources.timers/schedule! frame rk kind 60000))
        (is (= [false true] [(armed? rk kind) (among? armed-handle @cancelled)])
            "the late arm published nothing and its orphan handle was cancelled")))))

(deftest cancellation-of-old-attempt-does-not-erase-successor
  ;; While A's host handle is being released, a successor B is published at the
  ;; same key: the stand-in for a concurrent re-arm.
  (rf.resources.timers/reset-cache!)
  (let [rk        (rkey :succ)
        kind      rf.resources.timers/gc-kind
        hA        (fresh-handle)
        hB        (fresh-handle)
        b-slot    {:token ::successor-token :handle hB}
        cancelled (atom [])]
    (with-redefs [rf.interop/schedule-after! (fn [_thunk _ms] hA)]
      (rf.resources.timers/schedule! frame rk kind 60000))
    (let [k (tkey rk kind)]
      (is (identical? hA (:handle (slot rk kind))) "precondition: A armed with hA")
      (with-redefs [rf.interop/cancel-scheduled!
                    (fn [h]
                      (swap! cancelled conj h)
                      (when (identical? h hA)
                        (swap! rf.resources.timers/timer-table assoc k b-slot))
                      nil)]
        (rf.resources.timers/cancel! frame rk kind))
      (is (= b-slot (get @rf.resources.timers/timer-table k)) "successor B survives A's cancellation")
      (is (= [true false] [(among? hA @cancelled) (among? hB @cancelled)])
          "only A's own handle was cancelled"))))

(deftest stale-poll-loser-thunk-cannot-reap-or-refetch-the-winner
  (rf.resources.timers/reset-cache!)
  (let [rk     (rkey :poll)
        kind   rf.resources.timers/poll-kind
        thunks (atom [])]
    (with-redefs [rf.interop/schedule-after! (fn [thunk _ms] (swap! thunks conj thunk) (fresh-handle))
                  rf.interop/cancel-scheduled! (fn [_h] nil)]
      (rf.resources.timers/schedule! frame rk kind 60000)
      (rf.resources.timers/schedule! frame rk kind 60000)
      (is (= [2 true] [(count @thunks) (armed? rk kind)]) "precondition: two arms, one live slot")
      (let [[thunk-1 thunk-2] @thunks]
        ;; the re-check dispatch lives inside the winning-claim branch, so a
        ;; surviving slot shows the loser's dispatch was suppressed
        (thunk-1)
        (is (= [true 2] [(armed? rk kind) (count @thunks)])
            "the stale loser neither reaped the winner nor re-armed")
        (thunk-2)
        (is (not (armed? rk kind)) "the winning thunk self-reaped its own slot")))))

(deftest synchronous-fire-during-arm-strands-no-spent-handle
  (rf.resources.timers/reset-cache!)
  (let [rk        (rkey :sync)
        kind      rf.resources.timers/stale-kind
        cancelled (atom [])]
    (with-redefs [rf.interop/cancel-scheduled! (fn [h] (swap! cancelled conj h) nil)
                  rf.interop/schedule-after! (fn [thunk _ms]
                                               (let [h (fresh-handle)]
                                                 ;; the host fires before returning the handle
                                                 (thunk)
                                                 h))]
      (rf.resources.timers/schedule! frame rk kind 60000))
    (is (= [false true] [(armed? rk kind) (boolean (seq @cancelled))])
        "the synchronous fire closed its slot and the spent handle was cancelled")))
