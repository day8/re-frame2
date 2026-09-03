(ns re-frame.resources-timer-arm-publish-race-cljs-test
  "Adversarial ordering regressions for the resource stale / GC / poll timer
  two-phase arm (rf2-j538f7.10). Two races the serial suite never exercised:

    1. ARM-AFTER-CLEANUP — a host arm that returns AFTER a lifecycle cleanup
       (`release-frame!` / `cancel-for-key!` / `reset-cache!`) already ran must
       NOT publish live host work onto a torn-down frame / removed entry. The
       fix RESERVES the `[frame rkey kind]` slot with a token-stamped arming
       sentinel (`:handle nil`) BEFORE arming, so a concurrent cleanup
       atomically CLAIMS the attempt and the publish phase finds its token gone
       and cancels the orphan handle.

    2. OLD-CANCEL-ERASES-SUCCESSOR — a trailing cancellation of an OLD attempt
       must NOT erase a re-armed SUCCESSOR occupying the same reused
       `[frame rkey kind]` slot. The fix scopes every cancellation to the exact
       attempt token it observed, so a successor published mid-cancellation
       survives with its handle intact.

  The races are only genuinely CONCURRENT on the JVM, but the deterministic
  interleavings here are driven with `with-redefs` — running the cleanup INSIDE
  the host-arm stub (between reserve and publish), or firing the captured thunk
  synchronously — so BOTH runtimes execute the identical reserve / publish /
  claim code. Per Spec 016 §Stale and GC scheduling / §Polling.

  Dual-target (`.cljc`): the JVM runner selects it on `.*-test$`, Shadow's
  `:node-test` build on `cljs-test$`. The `-cljs-test` suffix is therefore
  load-bearing — a `.cljc` test whose ns ends in a plain `-test` compiles
  nowhere but the JVM and reads as covered (rf2-dn6v7, rf2-lgozq)."
  (:require
   #?(:clj  [clojure.test :refer [deftest is testing use-fixtures]]
      :cljs [cljs.test :refer-macros [deftest is testing use-fixtures]])
   [re-frame.identity :as rf.identity]
   [re-frame.interop :as rf.interop]
   ;; load-bearing side-effecting require: the façade registers the resources
   ;; events + the test-support reset hook that clears timer-table.
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

;; ===========================================================================
;; RACE 1 — arm-after-cleanup
;; ===========================================================================

(deftest arm-after-cleanup-leaves-no-slot-and-cancels-orphan-handle
  (doseq [[label cleanup!]
          [["release-frame!"  (fn [rk] (rf.resources.timers/release-frame! frame))]
           ["cancel-for-key!" (fn [rk] (rf.resources.timers/cancel-for-key! frame rk))]
           ["reset-cache!"    (fn [_]  (rf.resources.timers/reset-cache!))]]]
    (testing (str "cleanup owner: " label)
      (rf.resources.timers/reset-cache!)
      (let [rk           (rkey :race/arm)
            kind         rf.resources.timers/stale-kind
            cancelled    (atom [])
            armed-handle (fresh-handle)]
        (with-redefs [rf.interop/cancel-scheduled! (fn [h] (swap! cancelled conj h) nil)
                      rf.interop/schedule-after!
                      (fn [_thunk _ms]
                        ;; the slot is reserved (sentinel) but not yet
                        ;; published: a cleanup wins here.
                        (cleanup! rk)
                        armed-handle)]
          (rf.resources.timers/schedule! frame rk kind 60000))
        (is (not (armed? rk kind))
            "no slot survives — the late arm did not publish onto a cleaned frame")
        (is (some #(identical? armed-handle %) @cancelled)
            "the orphan host handle returned by the late arm was cancelled")))))

;; ===========================================================================
;; RACE 2 — old-cancel-erases-successor
;; ===========================================================================

(deftest cancellation-of-old-attempt-does-not-erase-successor
  ;; Arm A for real, then — DURING A's cancellation, at the moment its host
  ;; handle is released — publish a successor B at the same reused key (the
  ;; deterministic stand-in for a concurrent re-arm landing between A's read and
  ;; its removal). A's cancellation must claim ONLY A and leave B tracked.
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
                      ;; B lands at the same key while A's handle is released —
                      ;; the concurrent re-arm publishing a successor.
                      (when (identical? h hA)
                        (swap! rf.resources.timers/timer-table assoc k b-slot))
                      nil)]
        (rf.resources.timers/cancel! frame rk kind))
      (is (= b-slot (get @rf.resources.timers/timer-table k))
          "successor B SURVIVES A's cancellation — the old cancel did not erase it")
      (is (identical? hB (:handle (get @rf.resources.timers/timer-table k))) "B's host handle is intact")
      (is (some #(identical? hA %) @cancelled) "A's own handle was cancelled")
      (is (not (some #(identical? hB %) @cancelled))
          "B's handle was NOT cancelled by A's cancellation"))))

;; ===========================================================================
;; RACE 2b — a stale poll loser thunk cannot refetch or reap the winner after a
;; same-kind re-arm (dispatch authority via the per-attempt token).
;; ===========================================================================

(deftest stale-poll-loser-thunk-cannot-reap-or-refetch-the-winner
  (rf.resources.timers/reset-cache!)
  (let [rk     (rkey :poll)
        kind   rf.resources.timers/poll-kind
        thunks (atom [])]
    (with-redefs [rf.interop/schedule-after! (fn [thunk _ms] (swap! thunks conj thunk) (fresh-handle))
                  rf.interop/cancel-scheduled! (fn [_h] nil)]
      (rf.resources.timers/schedule! frame rk kind 60000)   ;; attempt-1 → thunk-1
      (rf.resources.timers/schedule! frame rk kind 60000)   ;; attempt-2 (cancel-then-arm) → thunk-2
      (is (= 2 (count @thunks)) "captured both arm thunks")
      (is (armed? rk kind) "exactly one poll slot armed")
      (let [thunk-1 (first @thunks)
            thunk-2 (second @thunks)]
        ;; Fire the STALE loser. Its token no longer owns the slot, so its
        ;; atomic claim fails: it neither reaps the winner nor refetches (the
        ;; re-check dispatch lives INSIDE the winning-claim branch, so a
        ;; surviving slot proves the dispatch was suppressed).
        (thunk-1)
        (is (armed? rk kind) "the stale loser thunk did NOT reap the winner")
        (is (= 2 (count @thunks)) "no phantom re-arm followed the loser fire")
        ;; Fire the winner — it owns the slot → claims + self-reaps it.
        (thunk-2)
        (is (not (armed? rk kind)) "the winning thunk self-reaped its own slot")))))

;; ===========================================================================
;; SYNC-FIRE — a scheduler that fires the callback synchronously inside the arm,
;; before returning the handle, strands no spent handle.
;; ===========================================================================

(deftest synchronous-fire-during-arm-strands-no-spent-handle
  (rf.resources.timers/reset-cache!)
  (let [rk        (rkey :sync)
        kind      rf.resources.timers/stale-kind
        cancelled (atom [])]
    (with-redefs [rf.interop/cancel-scheduled! (fn [h] (swap! cancelled conj h) nil)
                  rf.interop/schedule-after! (fn [thunk _ms]
                                            (let [h (fresh-handle)]
                                              ;; the host fires synchronously
                                              ;; BEFORE returning the handle
                                              (thunk)
                                              h))]
      (rf.resources.timers/schedule! frame rk kind 60000))
    (is (not (armed? rk kind))
        "the synchronous fire closed its sentinel — no spent handle was published")
    (is (seq @cancelled)
        "the post-arm publish found its token gone and cancelled the spent handle")))
