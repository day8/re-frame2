(ns re-frame.router-next-tick-ordering-cljs-test
  "Host-ordering contract for the router's drain scheduling primitive
  (`rf.interop/next-tick`), per Spec 002 §Drain scheduling — task, not microtask
  and rf2-t8xyuk.

  The router schedules its drain via `rf.interop/next-tick` = `goog.async.nextTick`,
  which is a **macrotask** (a next-turn TASK), NOT a microtask. These tests PIN
  exactly that boundary — the whole guarantee the runtime makes — so a future
  change cannot silently swap the primitive to a true microtask
  (`js/queueMicrotask` / a resolved-`Promise` job) to match the historically
  wrong \"microtask\" prose the bead corrected.

  The distinguishing observation the event-loop contract makes available: a
  microtask scheduled from the same synchronous stack ALWAYS runs at the host's
  microtask checkpoint — strictly BEFORE the next macrotask. So if `next-tick`
  fires AFTER a `js/queueMicrotask` peer, it is a macrotask; if it fired before,
  it would be a microtask. Both tests turn on exactly that gap.

  What these tests deliberately do NOT assert (rf2-iweic): WHICH task mechanism
  Closure selects. `goog.async.nextTick` prefers a native `setImmediate`, else a
  `MessageChannel`/`postMessage` emulation, and falls back to `setTimeout(cb, 0)`
  where neither exists. All three are tasks, so every assertion below holds on
  every path — including the timer fallback. Asserting the absence of a timer
  (or of the nested-`setTimeout` clamp) would pin a promise the runtime does not
  make."
  (:require [cljs.test :refer-macros [deftest is testing async use-fixtures]]
            [re-frame.core :as rf]
            [re-frame.interop :as rf.interop]
            [re-frame.substrate.plain-atom :as rf.substrate.plain-atom]
            [re-frame.test-support :as rf.test-support]))

;; Persistent ambient :rf/default scope so a bare `dispatch` resolves a frame
;; across the async boundary (same shape as the async-reset-fixture suite).
(use-fixtures :each
  (rf.test-support/make-reset-runtime-fixture {:adapter rf.substrate.plain-atom/adapter
                                            :async?  true}))

;; ---- 1. the scheduling primitive is a macrotask ---------------------------

(deftest next-tick-is-a-macrotask-not-a-microtask
  (testing "rf.interop/next-tick fires as a next-turn TASK: after ALL synchronous work AND after a true js/queueMicrotask boundary"
    (async done
      (let [log (atom [])]
        (swap! log conj :sync-before)
        ;; Schedule the router primitive FIRST, then a true microtask peer.
        ;; If next-tick were a microtask, being scheduled first it would run
        ;; first (FIFO within the microtask checkpoint) — BEFORE :microtask.
        ;; As a macrotask it instead runs AFTER the whole microtask checkpoint.
        (rf.interop/next-tick (fn [] (swap! log conj :next-tick)))
        (js/queueMicrotask  (fn [] (swap! log conj :microtask)))
        (swap! log conj :sync-after)
        ;; The portable core of the contract, asserted on the scheduling stack
        ;; itself: `next-tick` SCHEDULES, it never runs f inline. This is the
        ;; one property both hosts share (on the JVM the executor may start the
        ;; callback concurrently, but never on the submitting thread), and it
        ;; holds on every Closure mechanism including the setTimeout fallback.
        (is (= [:sync-before :sync-after] @log)
            "next-tick did not invoke its callback synchronously")
        ;; A trailing macrotask observes the settled order. It is scheduled
        ;; after the drain primitive above, so it fires strictly later.
        (rf.interop/next-tick
          (fn []
            (is (= [:sync-before :sync-after :microtask :next-tick] @log)
                (str "expected order sync → microtask checkpoint → next-tick macrotask; got " @log))
            ;; The load-bearing distinctions, asserted directly:
            (is (< (.indexOf @log :sync-after) (.indexOf @log :microtask))
                "all synchronous work precedes the microtask checkpoint")
            (is (< (.indexOf @log :microtask) (.indexOf @log :next-tick))
                "a true queueMicrotask boundary precedes rf.interop/next-tick — so next-tick is NOT a microtask")
            (done)))))))

;; ---- 2. the ROUTER drain lands on that macrotask boundary -----------------

(deftest router-drain-lands-after-the-microtask-checkpoint
  (testing "a plain dispatch schedules the drain on the macrotask boundary: it has NOT run at the microtask checkpoint following the dispatch, and HAS run once the tick fires"
    (async done
      (rf/reg-event :rt/mark (fn [{:keys [db]} _] {:db (assoc db :marked true)}))
      (let [observed (atom {})]
        ;; `dispatch` (async) enqueues on the empty queue and schedules the
        ;; drain via rf.interop/next-tick. The event has NOT been processed yet.
        (rf/dispatch [:rt/mark])
        ;; A true microtask, scheduled AFTER the dispatch. Under the correct
        ;; macrotask contract the drain has NOT run when this fires. Were the
        ;; drain a microtask (scheduled at dispatch time, i.e. before this one),
        ;; it would have run first and :at-microtask would be true — the exact
        ;; regression this test fails on.
        (js/queueMicrotask
          (fn []
            (swap! observed assoc :at-microtask
                   (boolean (:marked (rf/app-db-value :rf/default))))))
        ;; Settle two macrotasks out — well past the drain's own tick — then
        ;; assert both the checkpoint observation and the eventual drain.
        (rf.interop/next-tick
          (fn []
            (rf.interop/next-tick
              (fn []
                (is (false? (:at-microtask @observed))
                    "the drain had NOT run at the microtask checkpoint after dispatch — the router boundary is a macrotask, not a microtask")
                (is (true? (:marked (rf/app-db-value :rf/default)))
                    "the event drained once the router macrotask fired")
                (done)))))))))
