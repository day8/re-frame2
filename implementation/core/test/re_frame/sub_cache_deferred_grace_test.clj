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
            [re-frame.subs :as subs]
            [re-frame.frame :as frame]
            [re-frame.registrar :as registrar]
            [re-frame.schemas :as schemas]
            [re-frame.flows :as flows]
            [re-frame.substrate.plain-atom :as plain-atom]))

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
         (subs/configure! {:grace-period-ms 50}))))

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
    (subs/configure! {:grace-period-ms 60000})
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
    (subs/configure! {:grace-period-ms 30})
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

    ;; Wait WELL past 2 × grace — parent fires, cascade schedules
    ;; inputs, inputs fire. The whole chain is gone.
    (Thread/sleep 500)
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
    (subs/configure! {:grace-period-ms 30})
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

    ;; Wait WELL past 3 × grace so each layer fires in turn and the
    ;; full chain drains.
    (Thread/sleep 600)
    (is (not (contains? (cache-keys) [:a*4])) "top disposed")
    (is (not (contains? (cache-keys) [:a*2])) "middle disposed")
    (is (not (contains? (cache-keys) [:a])) "leaf disposed")))

(deftest layer-2-cascade-with-shared-input-under-deferred-grace
  (testing "with grace > 0 and a layer-1 input shared by two layer-2
            subs, disposing only one parent leaves the shared input at
            ref-count 1 — even after waiting past the parent's grace,
            the shared input is NEVER scheduled for dispose"
    (subs/configure! {:grace-period-ms 30})
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
    (Thread/sleep 300)  ;; well past 2 × grace
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
    (subs/configure! {:grace-period-ms 60000})
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
    (subs/configure! {:grace-period-ms 60000})
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
    (subs/configure! {:grace-period-ms 60000})
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
            never fires because the parent reaction was never disposed"
    (subs/configure! {:grace-period-ms 200})
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

      ;; Mid-window resubscribe (sleep ~40% of the grace-period).
      (Thread/sleep 80)
      (let [r2 (rf/subscribe [:sum])]
        (is (identical? r1 r2)
            "resubscribe within grace returns the same reaction")
        (is (not (pending-dispose? [:sum]))
            "parent's pending timer was cancelled")
        (is (= 1 (entry-ref-count [:sum]))
            "parent's ref-count restored to 1")
        (is (= 1 (entry-ref-count [:a]))
            "inputs still at ref-count 1 — cascade never fired")
        (is (= 1 (entry-ref-count [:b]))))

      ;; And waiting past the ORIGINAL grace-period: the cancelled
      ;; timer must NOT fire — the cache stays alive.
      (Thread/sleep 400)
      (is (contains? (cache-keys) [:sum])
          "cancelled timer did not fire")
      (is (contains? (cache-keys) [:a]))
      (is (contains? (cache-keys) [:b])))))

(deftest resubscribe-during-input-grace-after-cascade-rebuilds-cleanly
  (testing "after the parent has cascaded its inputs into their own
            grace windows, a fresh subscribe to the parent rebuilds it
            against the still-cached inputs — the cache-miss path's
            per-input subscribe goes through the HIT branch on each
            input, cancelling its pending-dispose timer and bumping
            ref-count from 0 → 1"
    (subs/configure! {:grace-period-ms 60000})
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
            and a resubscribe at a randomised offset inside the grace
            window. Either path (cancel-the-timer OR let-it-fire +
            rebuild) must converge — no leaked slots, balanced ref-counts.

            We use grace=200ms so resubscribe-at-100ms is comfortably
            inside the window. The 'fire' arm sleeps 400ms (> 2 × grace
            so the full cascade fires)."
    (subs/configure! {:grace-period-ms 200})
    (rf/reg-event-db :init (fn [_ _] {:a 2 :b 3}))
    (rf/reg-sub :a (fn [db _] (:a db)))
    (rf/reg-sub :b (fn [db _] (:b db)))
    (rf/reg-sub :sum :<- [:a] :<- [:b] (fn [[a b] _] (+ a b)))
    (rf/dispatch-sync [:init])

    ;; 20 iterations, alternating: cancel-mid-window (10) vs let-fire (10).
    ;; Bigger counts blow out the test runtime — grace=200ms means even
    ;; the cancel path costs ~100ms per iteration.
    (dotimes [i 20]
      (let [r (rf/subscribe [:sum])]
        (is (= 5 @r))
        (is (= 1 (entry-ref-count [:sum])))
        (rf/unsubscribe [:sum])
        (is (pending-dispose? [:sum]))
        (if (even? i)
          ;; Cancel-mid-window arm.
          (do (Thread/sleep 100)
              (let [r2 (rf/subscribe [:sum])]
                (is (identical? r r2)
                    "mid-window resubscribe returned same reaction"))
              (rf/unsubscribe [:sum])
              ;; Now wait for the cascade to fully fire.
              (Thread/sleep 500))
          ;; Let-fire arm: wait past 2 × grace.
          (Thread/sleep 500))
        ;; Both arms converge: cache fully drained.
        (is (not (contains? (cache-keys) [:sum]))
            (str "iter " i ": parent must be disposed"))
        (is (not (contains? (cache-keys) [:a]))
            (str "iter " i ": input :a must be disposed"))
        (is (not (contains? (cache-keys) [:b]))
            (str "iter " i ": input :b must be disposed")))))
  (is (empty? (cache-keys))
      "after the stress loop the cache is fully drained"))
