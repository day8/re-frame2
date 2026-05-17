(ns re-frame.sub-cache-deferred-grace-test
  "Targeted coverage for the **deferred-dispose path** (grace > 0)
  interacting with the layer-2+ disposal cascade (rf2-f3rd) and the
  hot-reload re-registration hook (rf2-moend).

  The existing `sub_cache_test.clj` covers the cascade and hot-reload
  surfaces in isolation, but each assertion runs under
  `grace-period-ms 0` for determinism. With `grace = 0` the
  `dispose-entry-now!` branch fires synchronously inside
  `unsubscribe`, which short-circuits the `pending-dispose` handle
  bookkeeping the deferred path stresses.

  The cascade under deferred grace has three nontrivial properties:

  1. **Cascade scheduling.** Disposing a layer-2 sub under grace > 0
     stashes a `:pending-dispose` timer on the parent slot. When that
     timer fires it dissociates the parent slot AND its `add-on-dispose!`
     callback runs `(unsubscribe input-q)` for every `:<-` input —
     each of which, if it was the last holder, ALSO schedules a
     grace-period timer. So the cascade is a tree of pending timers,
     not a single synchronous dissociation.

  2. **Hot-reload cancellation.** If the parent's `reg-sub` is
     re-registered while its slot is in the grace-period window,
     `invalidate-sub-on-replace!` evicts the slot AND cancels the
     timer (subs.cljc lines 624-635). The on-dispose callback still
     fires when the reaction is `dispose!`d, so the input ref-counts
     are still released — symmetrically — but via the eviction path,
     not the deferred-timer path.

  3. **Resubscribe-mid-window cancellation across the chain.** A new
     subscribe arriving inside the grace window must cancel the parent
     slot's timer AND the inputs must reach ref-count 1 (re-acquired
     via construction or re-incremented if the input slot also had a
     pending dispose).

  All three were UNDER-tested before this bead. The file pins each.

  Timing notes: tests below use *long* grace-periods (typically 60s)
  so the intermediate `:pending-dispose` state is deterministic — we
  trigger transitions by explicit action (re-register, resubscribe,
  clear-sub-cache!) rather than by waiting for the timer.
  Where we DO need to observe natural timer-fire, the grace is small
  (~30ms) but we wait an order-of-magnitude longer (300ms+) so the
  ScheduledExecutorService has comfortable room."
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [re-frame.core :as rf]
            [re-frame.subs.cache :as subs-cache]
            [re-frame.frame :as frame]
            [re-frame.registrar :as registrar]
            [re-frame.schemas :as schemas]
            [re-frame.flows :as flows]
            [re-frame.substrate.plain-atom :as plain-atom]
            [re-frame.test-support :as test-support]))

(defn reset-runtime [test-fn]
  (registrar/clear-all!)
  (reset! frame/frames {})
  (reset! flows/flows {})
  (reset! schemas/schemas-by-frame {})
  (rf/init! plain-atom/adapter)
  (require 're-frame.routing :reload)
  (require 're-frame.ssr :reload)
  (require 're-frame.machines :reload)
  (try (test-fn)
       (finally
         (subs-cache/configure! {:grace-period-ms 50}))))

(use-fixtures :each reset-runtime)

(defn- cache-keys
  []
  (set (keys @(:sub-cache (frame/frame :rf/default)))))

(defn- entry-ref-count
  [query-v]
  (get-in @(:sub-cache (frame/frame :rf/default)) [query-v :ref-count]))

(defn- pending-dispose?
  [query-v]
  (some? (get-in @(:sub-cache (frame/frame :rf/default)) [query-v :pending-dispose])))

;; ---- (1) Layer-2 disposal cascade under non-zero grace --------------------
;;
;; Per Spec 006 §Reference counting and disposal: when a layer-2 sub's
;; ref-count drops to zero under grace > 0, the parent slot is scheduled
;; for deferred disposal. When the timer fires, `dispose-entry-now!`
;; dissociates the slot AND triggers the reaction's `add-on-dispose!`
;; callback, which calls `unsubscribe` on each input — propagating the
;; ref-count → 0 transition down the chain. Each input slot then
;; schedules its OWN grace-period timer if it was the last holder.
;;
;; Pre-rf2-f3rd the on-dispose callback skipped the input-unsubscribe
;; step entirely (input ref-counts leaked); the v1 path didn't traverse
;; this cascade under deferred grace at all. The fix runs `unsubscribe`
;; for each input — which means the input cascade necessarily goes
;; through the SAME deferred-dispose path as a top-level unsubscribe.
;; This test pins that cascade behaviour by observing the pre-timer-
;; fire state (long grace, no waiting) and the post-fire state
;; (short grace, deliberate wait).

(deftest layer-2-cascade-under-deferred-grace-pre-fire
  (testing "with a *long* grace, after unsubscribing a layer-2 sub the
            parent enters its grace window; inputs are STILL held
            at ref-count 1 because the cascade only fires when the
            parent reaction is actually disposed (i.e. when the timer
            lands or when invalidate-sub-on-replace! evicts the slot)"
    ;; Long grace — we'll never let it fire naturally during this test.
    (subs-cache/configure! {:grace-period-ms 60000})
    (rf/reg-event-db :init (fn [_ _] {:a 2 :b 3}))
    (rf/reg-sub :a (fn [db _] (:a db)))
    (rf/reg-sub :b (fn [db _] (:b db)))
    (rf/reg-sub :sum
      :<- [:a]
      :<- [:b]
      (fn [[a b] _] (+ a b)))
    (rf/dispatch-sync [:init])

    (let [r (rf/subscribe [:sum])]
      (is (= 5 @r))
      (is (= 1 (entry-ref-count [:sum])))
      (is (= 1 (entry-ref-count [:a])))
      (is (= 1 (entry-ref-count [:b]))))

    (rf/unsubscribe [:sum])
    (is (contains? (cache-keys) [:sum])
        "parent slot stays in cache for the entire grace window")
    (is (= 0 (entry-ref-count [:sum]))
        "ref-count is 0 immediately on the last unsubscribe")
    (is (pending-dispose? [:sum])
        "parent has a deferred-dispose handle stashed")
    (is (= 1 (entry-ref-count [:a]))
        "inputs still at ref-count 1 — cascade hasn't fired yet")
    (is (= 1 (entry-ref-count [:b])))
    (is (not (pending-dispose? [:a]))
        "inputs have NOT been scheduled for dispose")
    (is (not (pending-dispose? [:b])))))

(deftest layer-2-cascade-under-deferred-grace-full-fire
  (testing "with a short grace, the parent's timer fires, the cascade
            schedules input dispose, and after waiting another grace
            window the entire chain is disposed"
    (subs-cache/configure! {:grace-period-ms 30})
    (rf/reg-event-db :init (fn [_ _] {:a 2 :b 3}))
    (rf/reg-sub :a (fn [db _] (:a db)))
    (rf/reg-sub :b (fn [db _] (:b db)))
    (rf/reg-sub :sum
      :<- [:a]
      :<- [:b]
      (fn [[a b] _] (+ a b)))
    (rf/dispatch-sync [:init])

    (rf/subscribe [:sum])
    (rf/unsubscribe [:sum])
    (is (pending-dispose? [:sum])
        "parent is in its grace-period window")

    ;; Poll until the cascade fully drains — parent + both inputs
    ;; disposed (rf2-fun38). Cleaner than fixed 500ms sleep; tests
    ;; fail fast on a stuck cascade timer.
    (test-support/poll-until
      #(not-any? (fn [k] (contains? (cache-keys) k)) [[:sum] [:a] [:b]])
      {:timeout-ms 2000 :label "layer-2 cascade fully drained"})
    (is (not (contains? (cache-keys) [:sum]))
        "parent disposed")
    (is (not (contains? (cache-keys) [:a]))
        "input :a disposed via the cascade + its own grace")
    (is (not (contains? (cache-keys) [:b]))
        "input :b disposed via the cascade + its own grace")))

(deftest layer-3-cascade-under-deferred-grace
  (testing "deferred-dispose cascade walks the chain layer by layer:
            each level's grace fires, releases the next-down ref-count
            (which schedules ITS grace), and so on. The whole chain
            takes roughly N × grace to drain"
    (subs-cache/configure! {:grace-period-ms 30})
    (rf/reg-event-db :init (fn [_ _] {:a 2}))
    (rf/reg-sub :a (fn [db _] (:a db)))
    (rf/reg-sub :a*2 :<- [:a]   (fn [a _]  (* 2 a)))
    (rf/reg-sub :a*4 :<- [:a*2] (fn [a2 _] (* 2 a2)))
    (rf/dispatch-sync [:init])

    (let [r (rf/subscribe [:a*4])]
      (is (= 8 @r))
      (is (= 1 (entry-ref-count [:a*4])))
      (is (= 1 (entry-ref-count [:a*2])))
      (is (= 1 (entry-ref-count [:a]))))

    (rf/unsubscribe [:a*4])
    ;; Top of the chain is in its grace window; the rest of the chain
    ;; is still held at ref-count 1.
    (is (pending-dispose? [:a*4]))
    (is (= 1 (entry-ref-count [:a*2]))
        "middle layer untouched until top fires")
    (is (= 1 (entry-ref-count [:a])))

    ;; Poll until the 3-layer cascade fully drains (rf2-fun38). The
    ;; whole chain disappearing IS the observable signal — no need to
    ;; fix a sleep against 3 × grace + executor margin.
    (test-support/poll-until
      #(not-any? (fn [k] (contains? (cache-keys) k))
                 [[:a*4] [:a*2] [:a]])
      {:timeout-ms 2000 :label "layer-3 cascade fully drained"})
    (is (not (contains? (cache-keys) [:a*4])) "top disposed")
    (is (not (contains? (cache-keys) [:a*2])) "middle disposed")
    (is (not (contains? (cache-keys) [:a])) "leaf disposed")))

(deftest layer-2-cascade-with-shared-input-under-deferred-grace
  (testing "with grace > 0 and a layer-1 input shared by two layer-2
            subs, disposing only one parent leaves the shared input at
            ref-count 1 — even after waiting past the parent's grace,
            the shared input is NEVER scheduled for dispose"
    (subs-cache/configure! {:grace-period-ms 30})
    (rf/reg-event-db :init (fn [_ _] {:a 2 :b 3 :c 4}))
    (rf/reg-sub :a (fn [db _] (:a db)))
    (rf/reg-sub :b (fn [db _] (:b db)))
    (rf/reg-sub :c (fn [db _] (:c db)))
    (rf/reg-sub :ab :<- [:a] :<- [:b] (fn [[a b] _] (+ a b)))
    (rf/reg-sub :ac :<- [:a] :<- [:c] (fn [[a c] _] (+ a c)))
    (rf/dispatch-sync [:init])

    (rf/subscribe [:ab])
    (rf/subscribe [:ac])
    (is (= 2 (entry-ref-count [:a])) "shared input :a has ref-count 2")

    ;; Drop :ab. After its grace fires the cascade decrements :a and :b
    ;; — :a falls from 2 → 1 (NOT scheduled), :b falls from 1 → 0
    ;; (scheduled, then fires).
    (rf/unsubscribe [:ab])
    ;; Poll for the cascade's positive signal — :ab and :b disposed
    ;; (rf2-fun38). The :a-NOT-disposed assertion below is the
    ;; timer-semantics part (proving absence); polling for the positive
    ;; signal guarantees the cascade has fully run before we assert.
    (test-support/poll-until
      #(and (not (contains? (cache-keys) [:ab]))
            (not (contains? (cache-keys) [:b])))
      {:timeout-ms 2000 :label ":ab cascade fully drained"})
    (is (not (contains? (cache-keys) [:ab])))
    (is (not (contains? (cache-keys) [:b])) ":b disposed via cascade")
    (is (contains? (cache-keys) [:a])
        ":a alive — still held by :ac")
    (is (= 1 (entry-ref-count [:a])))
    (is (not (pending-dispose? [:a]))
        ":a was NEVER scheduled for dispose — it never hit ref-count 0")
    (is (contains? (cache-keys) [:ac]))
    (is (= 1 (entry-ref-count [:ac])))))

;; ---- (2) Hot-reload race during pending layer-2 cascade -------------------
;;
;; The on-dispose callback's `unsubscribe` calls release input ref-counts.
;; If `invalidate-sub-on-replace!` evicts the parent slot during its
;; grace window:
;;   - The pending timer is cancelled (subs.cljc line 631).
;;   - `dispose!` is called on the parent reaction (line 634).
;;   - The reaction's on-dispose fires the input-unsubscribe cascade.
;; The end-state is the same as if the timer had fired naturally — the
;; cascade WAS exercised, just synchronously via eviction. This test
;; pins that the cascade actually fires even when the parent slot is
;; evicted out-of-band.

(deftest hot-reload-of-layer-2-mid-grace-still-cascades-input-disposal
  (testing "re-registering a layer-2 sub during its grace window evicts
            the parent slot AND cascades input-ref-count release via the
            reaction's on-dispose callback — no input ref-count leak"
    ;; Long grace so the eviction path is the only thing that can
    ;; release the parent slot.
    (subs-cache/configure! {:grace-period-ms 60000})
    (rf/reg-event-db :init (fn [_ _] {:a 2 :b 3}))
    (rf/reg-sub :a (fn [db _] (:a db)))
    (rf/reg-sub :b (fn [db _] (:b db)))
    (rf/reg-sub :sum
      :<- [:a]
      :<- [:b]
      (fn [[a b] _] (+ a b)))
    (rf/dispatch-sync [:init])

    (rf/subscribe [:sum])
    (rf/unsubscribe [:sum])
    (is (pending-dispose? [:sum]))
    (is (= 1 (entry-ref-count [:a]))
        ":a still held by the parent reaction (cascade hasn't fired yet)")
    (is (= 1 (entry-ref-count [:b])))

    ;; Re-register the parent sub with a different body. `invalidate-
    ;; sub-on-replace!` cancels the pending timer AND disposes the
    ;; reaction — the on-dispose callback fires the input-unsubscribe
    ;; cascade synchronously.
    (rf/reg-sub :sum
      :<- [:a]
      :<- [:b]
      (fn [[a b] _] (* a b)))

    (is (not (contains? (cache-keys) [:sum]))
        "parent slot evicted by hot-reload")
    ;; Inputs cascade — under grace > 0 each input slot enters ITS OWN
    ;; grace window (slot present, ref-count 0, pending handle stashed).
    (is (contains? (cache-keys) [:a])
        ":a still present — it's in its own grace window now")
    (is (= 0 (entry-ref-count [:a]))
        ":a's ref-count released by the cascade — no leak")
    (is (pending-dispose? [:a])
        ":a entered its own grace-period after the cascade fired")
    (is (= 0 (entry-ref-count [:b])))
    (is (pending-dispose? [:b]))))

(deftest hot-reload-race-resubscribes-during-input-cascade-grace
  (testing "hot-reload of a layer-2 sub mid-grace + immediate resubscribe:
            the new parent reaction re-acquires the inputs. Each input
            slot has a pending-dispose handle from the cascade; the
            resubscribe path cancels it and increments the ref-count.
            Net effect: new parent at ref-count 1, inputs at ref-count 1,
            no leaked timers, no stale references."
    (subs-cache/configure! {:grace-period-ms 60000})
    (rf/reg-event-db :init (fn [_ _] {:a 2 :b 3}))
    (rf/reg-sub :a (fn [db _] (:a db)))
    (rf/reg-sub :b (fn [db _] (:b db)))
    (rf/reg-sub :sum :<- [:a] :<- [:b] (fn [[a b] _] (+ a b)))
    (rf/dispatch-sync [:init])

    (let [r1 (rf/subscribe [:sum])]
      (is (= 5 @r1))
      (rf/unsubscribe [:sum]))

    ;; Hot-reload: cascade fires, inputs enter their own grace.
    (rf/reg-sub :sum
      :<- [:a]
      :<- [:b]
      (fn [[a b] _] (* a b)))
    (is (pending-dispose? [:a]))
    (is (pending-dispose? [:b]))
    (is (= 0 (entry-ref-count [:a])))
    (is (= 0 (entry-ref-count [:b])))

    ;; Resubscribe to the NEW :sum. It rebuilds against the cached-
    ;; but-pending-dispose input slots: each `subscribe` call inside
    ;; `compute-and-cache!` for the parent re-acquires the inputs,
    ;; cancelling THEIR pending-dispose timers and bumping ref-count.
    (let [r2 (rf/subscribe [:sum])]
      (is (= 6 @r2) "new sum body — product, not sum")
      (is (= 1 (entry-ref-count [:sum])) "new parent has one holder")
      (is (= 1 (entry-ref-count [:a]))
          ":a re-acquired by the new parent; ref-count back to 1")
      (is (not (pending-dispose? [:a]))
          ":a's pending-dispose timer was cancelled on re-acquisition")
      (is (= 1 (entry-ref-count [:b])))
      (is (not (pending-dispose? [:b]))))

    ;; And the new parent properly cascades on its own disposal.
    (rf/unsubscribe [:sum])
    (is (pending-dispose? [:sum])
        "new parent now in its own grace window")))

(deftest hot-reload-of-input-mid-cascade-grace
  (testing "an INPUT (not the parent) is hot-reloaded while its
            cascade-pending-dispose is in flight: invalidate-sub-on-
            replace! cancels the pending timer and disposes the input
            reaction. The parent (already disposed) is unaffected."
    (subs-cache/configure! {:grace-period-ms 60000})
    (rf/reg-event-db :init (fn [_ _] {:a 2 :b 3}))
    (rf/reg-sub :a (fn [db _] (:a db)))
    (rf/reg-sub :b (fn [db _] (:b db)))
    (rf/reg-sub :sum :<- [:a] :<- [:b] (fn [[a b] _] (+ a b)))
    (rf/dispatch-sync [:init])

    (rf/subscribe [:sum])
    (rf/unsubscribe [:sum])
    ;; Force the cascade by hot-reloading the parent — inputs end up in
    ;; their own grace windows.
    (rf/reg-sub :sum
      :<- [:a]
      :<- [:b]
      (fn [[a b] _] (* a b)))
    (is (pending-dispose? [:a]))

    ;; Now hot-reload one of the inputs. invalidate-sub-on-replace!
    ;; should cancel :a's pending timer and dispose the cache slot.
    (rf/reg-sub :a (fn [db _] (* 10 (:a db))))
    (is (not (contains? (cache-keys) [:a]))
        ":a evicted by hot-reload — no stale reaction served")
    (is (contains? (cache-keys) [:b])
        ":b untouched — different sub-id re-registered")
    (is (pending-dispose? [:b])
        ":b still in its grace window")))

;; ---- (3) Resubscribe-mid-window cancellation across the cascade ----------
;;
;; The crucial race: a subscribe arriving INSIDE the parent's grace
;; window. The parent's pending timer is cancelled (subscribe's hit
;; branch, lines 452-461). Inputs were never released (the cascade
;; only fires when the timer lands), so they should remain at the
;; same ref-count they had during the parent's grace window.

(deftest resubscribe-mid-grace-cancels-parent-and-preserves-inputs
  (testing "a new subscribe inside the parent's grace window cancels
            disposal AND preserves the inputs' ref-counts — the cascade
            never fires because the parent reaction was never disposed.

            Determinism: cancellation is an in-memory CAS in `subscribe`'s
            HIT branch — it does NOT depend on wall-clock elapsed time
            within the grace window. So we use a *long* grace (60s) and
            call unsubscribe → subscribe back-to-back. The window is
            vastly larger than any plausible JVM scheduler hiccup; the
            ScheduledExecutorService cannot fire mid-test and `identical?`
            holds deterministically. The earlier version of this test
            used grace=200ms and Thread/sleep 80 — under CI scheduler
            variation the timer could fire before the resubscribe arrived,
            evicting the slot and forcing compute-and-cache! to mint a
            new reaction (rf2-e8e74)."
    ;; Long grace — the timer cannot fire during this test under any
    ;; realistic scheduler.
    (subs-cache/configure! {:grace-period-ms 60000})
    (rf/reg-event-db :init (fn [_ _] {:a 2 :b 3}))
    (rf/reg-sub :a (fn [db _] (:a db)))
    (rf/reg-sub :b (fn [db _] (:b db)))
    (rf/reg-sub :sum :<- [:a] :<- [:b] (fn [[a b] _] (+ a b)))
    (rf/dispatch-sync [:init])

    (let [r1 (rf/subscribe [:sum])]
      (is (= 5 @r1))
      (is (= 1 (entry-ref-count [:sum])))
      (is (= 1 (entry-ref-count [:a])))
      (is (= 1 (entry-ref-count [:b])))

      (rf/unsubscribe [:sum])
      (is (pending-dispose? [:sum]))
      (is (= 0 (entry-ref-count [:sum])))
      (is (= 1 (entry-ref-count [:a]))
          "inputs untouched during parent's grace window")
      (is (= 1 (entry-ref-count [:b])))

      ;; In-window resubscribe — no sleep needed. Cancellation is a CAS
      ;; on the cache map; whether we resubscribe at t=0 or t=grace/2
      ;; exercises the same code path.
      (let [r2 (rf/subscribe [:sum])]
        (is (identical? r1 r2)
            "resubscribe within grace returns the same reaction")
        (is (not (pending-dispose? [:sum]))
            "parent's pending timer was cancelled")
        (is (= 1 (entry-ref-count [:sum]))
            "parent's ref-count restored to 1")
        (is (= 1 (entry-ref-count [:a]))
            "inputs still at ref-count 1 — cascade never fired")
        (is (= 1 (entry-ref-count [:b])))))))

(deftest resubscribe-mid-grace-cancelled-timer-stays-dead
  (testing "the cancelled grace-period timer must NOT fire after the
            original grace-period would have elapsed — proves the
            cancellation path actually calls clear-timeout!, not just
            nulls the handle in the cache map.

            Uses a short grace + post-window wait. Unlike the sibling
            test, we don't assert reaction identity here, so the
            scheduler-jitter risk is gone — we're only asserting that
            the slot is STILL ALIVE after a wait that's an order of
            magnitude past the original window."
    (subs-cache/configure! {:grace-period-ms 50})
    (rf/reg-event-db :init (fn [_ _] {:a 2 :b 3}))
    (rf/reg-sub :a (fn [db _] (:a db)))
    (rf/reg-sub :b (fn [db _] (:b db)))
    (rf/reg-sub :sum :<- [:a] :<- [:b] (fn [[a b] _] (+ a b)))
    (rf/dispatch-sync [:init])

    (rf/subscribe [:sum])
    (rf/unsubscribe [:sum])
    (is (pending-dispose? [:sum])
        "parent entered grace window after last unsubscribe")
    ;; Resubscribe immediately — cancellation handler runs synchronously.
    (rf/subscribe [:sum])
    (is (not (pending-dispose? [:sum]))
        "timer handle cleared from cache map")

    ;; Timer-semantics sleep (rf2-fun38): we are proving the *absence*
    ;; of dispose — if clear-timeout! is broken, the orphaned timer
    ;; would fire here and dispose-entry-now! would evict the slot.
    ;; No observable signal to poll; the 500ms (10 × grace) is the
    ;; quiescence budget.
    (Thread/sleep 500)
    (is (contains? (cache-keys) [:sum])
        "cancelled timer did not fire")
    (is (contains? (cache-keys) [:a]))
    (is (contains? (cache-keys) [:b]))
    (is (= 1 (entry-ref-count [:sum]))
        "ref-count untouched by would-have-been-fired timer")))

(deftest resubscribe-during-input-grace-after-cascade-rebuilds-cleanly
  (testing "after the parent has cascaded its inputs into their own
            grace windows, a fresh subscribe to the parent rebuilds it
            against the still-cached inputs — the cache-miss path's
            per-input subscribe goes through the HIT branch on each
            input, cancelling its pending-dispose timer and bumping
            ref-count from 0 → 1"
    (subs-cache/configure! {:grace-period-ms 60000})
    (rf/reg-event-db :init (fn [_ _] {:a 2 :b 3}))
    (rf/reg-sub :a (fn [db _] (:a db)))
    (rf/reg-sub :b (fn [db _] (:b db)))
    (rf/reg-sub :sum :<- [:a] :<- [:b] (fn [[a b] _] (+ a b)))
    (rf/dispatch-sync [:init])

    (rf/subscribe [:sum])
    (rf/unsubscribe [:sum])
    ;; Force the parent's cascade via hot-reload (cancels timer, fires
    ;; on-dispose). Inputs now in their own grace windows.
    (rf/reg-sub :sum :<- [:a] :<- [:b] (fn [[a b] _] (* a b)))
    (is (pending-dispose? [:a]))
    (is (pending-dispose? [:b]))

    ;; Subscribe to the new parent. compute-and-cache! calls
    ;; subscribe for each input — each goes through the HIT branch
    ;; (the slots are still cached) and cancels the pending timers.
    (rf/subscribe [:sum])
    (is (= 1 (entry-ref-count [:sum])))
    (is (= 1 (entry-ref-count [:a])))
    (is (not (pending-dispose? [:a]))
        "input :a's pending timer was cancelled on the parent rebuild")
    (is (= 1 (entry-ref-count [:b])))
    (is (not (pending-dispose? [:b])))))

(deftest mid-grace-window-resubscribe-stress
  (testing "stress: rapid subscribe/unsubscribe pairs with grace > 0
            and a resubscribe interleaved with the grace window. Either
            path (cancel-the-timer OR let-it-fire + rebuild) must
            converge — no leaked slots, balanced ref-counts.

            Two-grace setup (rf2-e8e74): the cancel-arm uses a LONG
            grace (60s) so the resubscribe deterministically lands
            inside the window — no scheduler race against `Thread/sleep`.
            After asserting `identical?` and the cancellation effect,
            we collapse the grace to 0 and unsubscribe so the cascade
            tears the slot down synchronously without a wait.

            The let-fire arm uses a SHORT grace (30ms) + wait so the
            ScheduledExecutorService timer lands and the full cascade
            actually fires through the deferred path."
    (rf/reg-event-db :init (fn [_ _] {:a 2 :b 3}))
    (rf/reg-sub :a (fn [db _] (:a db)))
    (rf/reg-sub :b (fn [db _] (:b db)))
    (rf/reg-sub :sum :<- [:a] :<- [:b] (fn [[a b] _] (+ a b)))
    (rf/dispatch-sync [:init])

    ;; 20 iterations, alternating cancel-arm (10) vs let-fire-arm (10).
    (dotimes [i 20]
      (if (even? i)
        ;; Cancel-mid-window arm — long grace, no wall-clock dependency.
        (do
          (subs-cache/configure! {:grace-period-ms 60000})
          (let [r (rf/subscribe [:sum])]
            (is (= 5 @r))
            (is (= 1 (entry-ref-count [:sum])))
            (rf/unsubscribe [:sum])
            (is (pending-dispose? [:sum]))
            ;; Resubscribe deterministically inside the 60s window.
            (let [r2 (rf/subscribe [:sum])]
              (is (identical? r r2)
                  (str "iter " i ": mid-window resubscribe returned same reaction"))
              (is (not (pending-dispose? [:sum]))
                  (str "iter " i ": timer cancelled on resubscribe"))))
          ;; Collapse grace to 0 to tear down synchronously — both arms
          ;; converge on a fully drained cache before the next iteration.
          (subs-cache/configure! {:grace-period-ms 0})
          (rf/unsubscribe [:sum]))
        ;; Let-fire arm: short grace + poll on cache-drained so the
        ;; full cascade fires through the deferred path (rf2-fun38).
        (do
          (subs-cache/configure! {:grace-period-ms 30})
          (let [r (rf/subscribe [:sum])]
            (is (= 5 @r))
            (is (= 1 (entry-ref-count [:sum])))
            (rf/unsubscribe [:sum])
            (is (pending-dispose? [:sum])))
          (test-support/poll-until
            #(not-any? (fn [k] (contains? (cache-keys) k))
                       [[:sum] [:a] [:b]])
            {:timeout-ms 2000 :label (str "iter " i ": let-fire cascade drained")})))
      ;; Both arms converge: cache fully drained.
      (is (not (contains? (cache-keys) [:sum]))
          (str "iter " i ": parent must be disposed"))
      (is (not (contains? (cache-keys) [:a]))
          (str "iter " i ": input :a must be disposed"))
      (is (not (contains? (cache-keys) [:b]))
          (str "iter " i ": input :b must be disposed"))))
  (is (empty? (cache-keys))
      "after the stress loop the cache is fully drained"))
