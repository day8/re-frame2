(ns re-frame.sub-cache-test
  "Tests for the per-frame sub-cache disposal contract (Spec 006
  §Reference counting and disposal, rf2-s9dn).

  The cache uses **deferred ref-counting with a grace-period**: when
  the last subscriber drops, the entry is scheduled for disposal after
  grace-period-ms. A new subscriber arriving in the window cancels
  disposal and reuses the cached value.

  These tests exercise the contract under both grace=0 (synchronous,
  fastest test path) and grace>0 (the production path)."
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
  ;; Restore the default grace-period after each test — tests below
  ;; toggle it for assertions and we don't want the value to leak.
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

;; ---- configuration --------------------------------------------------------

(deftest default-grace-period
  (testing "the default grace-period is 50ms"
    ;; 50ms is short enough not to leak under genuine disposal but long
    ;; enough to bridge React re-render churn (per Spec 006).
    (subs/configure! {:grace-period-ms 50})  ;; explicit; the fixture restores
    (is (= 50 (:grace-period-ms (subs/current-config))))))

(deftest configure-overrides-grace-period
  (testing "(rf/configure :sub-cache {...}) updates the grace-period"
    (rf/configure :sub-cache {:grace-period-ms 200})
    (is (= 200 (:grace-period-ms (subs/current-config))))
    (rf/configure :sub-cache {:grace-period-ms 0})
    (is (= 0 (:grace-period-ms (subs/current-config))))))

;; ---- synchronous-disposal path (grace = 0) --------------------------------

(deftest sync-disposal-when-grace-is-zero
  (testing "with grace=0, ref-count → 0 disposes the cache slot synchronously"
    (subs/configure! {:grace-period-ms 0})
    (rf/reg-event-db :init (fn [_ _] {:n 7}))
    (rf/reg-sub :n (fn [db _] (:n db)))
    (rf/dispatch-sync [:init])

    (rf/subscribe [:n])
    (is (contains? (cache-keys) [:n]))
    (is (= 1 (entry-ref-count [:n])))

    (rf/unsubscribe [:n])
    ;; grace=0: the slot is gone immediately, no scheduling.
    (is (not (contains? (cache-keys) [:n])))))

(deftest sync-disposal-respects-multiple-subscribers
  (testing "with grace=0, only the LAST subscriber dropping disposes"
    (subs/configure! {:grace-period-ms 0})
    (rf/reg-event-db :init (fn [_ _] {:n 7}))
    (rf/reg-sub :n (fn [db _] (:n db)))
    (rf/dispatch-sync [:init])

    (rf/subscribe [:n])
    (rf/subscribe [:n])
    (is (= 2 (entry-ref-count [:n])))

    (rf/unsubscribe [:n])
    (is (= 1 (entry-ref-count [:n])))
    (is (contains? (cache-keys) [:n]))

    (rf/unsubscribe [:n])
    (is (not (contains? (cache-keys) [:n])))))

;; ---- deferred-disposal path (grace > 0) -----------------------------------

(deftest deferred-disposal-after-grace-period
  (testing "ref-count → 0 → grace-period elapses → entry disposed"
    (subs/configure! {:grace-period-ms 30})
    (rf/reg-event-db :init (fn [_ _] {:n 7}))
    (rf/reg-sub :n (fn [db _] (:n db)))
    (rf/dispatch-sync [:init])

    (rf/subscribe [:n])
    (rf/unsubscribe [:n])

    ;; Immediately after unsubscribe: slot still present, dispose pending.
    (is (contains? (cache-keys) [:n])
        "slot should not be disposed before the grace-period elapses")
    (is (= 0 (entry-ref-count [:n]))
        "ref-count is zero immediately on the last unsubscribe")
    (is (pending-dispose? [:n])
        "a deferred-dispose handle should be stashed on the entry")

    ;; Wait for the grace-period (plus a margin for executor scheduling).
    (Thread/sleep 200)

    (is (not (contains? (cache-keys) [:n]))
        "after the grace-period the entry MUST be disposed")))

(deftest resubscribe-within-grace-cancels-disposal
  (testing "resubscribe inside the grace-period cancels disposal; cached value reused"
    (subs/configure! {:grace-period-ms 100})
    (rf/reg-event-db :init (fn [_ _] {:n 7}))
    (rf/reg-sub :n (fn [db _] (:n db)))
    (rf/dispatch-sync [:init])

    (let [r1 (rf/subscribe [:n])]
      (rf/unsubscribe [:n])
      (is (pending-dispose? [:n])
          "dispose was scheduled when ref-count dropped to zero")
      (let [r2 (rf/subscribe [:n])]
        (is (identical? r1 r2)
            "resubscribe within grace-period MUST return the same reaction")
        (is (= 1 (entry-ref-count [:n]))
            "ref-count is back to 1 after the resubscribe")
        (is (not (pending-dispose? [:n]))
            "the pending-dispose handle is cleared on resubscribe"))

      ;; Sleep past the original grace-period — the cancelled timer
      ;; must NOT fire and the slot stays alive.
      (Thread/sleep 250)
      (is (contains? (cache-keys) [:n])
          "cancelled timer must not fire — slot survives"))))

(deftest grace-zero-disposes-without-pending-handle
  (testing "with grace=0 the synchronous path leaves no pending-dispose handle"
    (subs/configure! {:grace-period-ms 0})
    (rf/reg-event-db :init (fn [_ _] {:n 7}))
    (rf/reg-sub :n (fn [db _] (:n db)))
    (rf/dispatch-sync [:init])

    (rf/subscribe [:n])
    (rf/unsubscribe [:n])
    (is (not (contains? (cache-keys) [:n]))
        "slot disposed synchronously")))

;; ---- rf2-zmufj: subscribe-value teardown is synchronous -------------------
;;
;; subscribe-value's internal unsubscribe runs with `{:grace 0}` so the
;; one-shot read's whole lifetime — subscribe, deref, dispose — completes
;; in the calling tick. Pre-fix, even with the per-runtime grace-period
;; set to 50ms, subscribe-value would schedule a deferred dispose timer
;; whose callback fired AFTER the caller had moved on, leaking dispose
;; side-effects past the call's observable lifetime.

(deftest subscribe-value-disposes-synchronously
  (testing "subscribe-value's teardown is synchronous regardless of the configured grace-period (rf2-zmufj)"
    (subs/configure! {:grace-period-ms 60000})  ;; long grace; pre-fix this leaked
    (rf/reg-event-db :init (fn [_ _] {:n 7}))
    (rf/reg-sub :n (fn [db _] (:n db)))
    (rf/dispatch-sync [:init])

    ;; Pre-condition: no cache slot exists for [:n] yet.
    (is (not (contains? (cache-keys) [:n]))
        "no cache slot before subscribe-value")

    ;; subscribe-value: builds the sub, derefs, and tears down. Per
    ;; rf2-zmufj the teardown is synchronous — when this returns, the
    ;; slot MUST already be evicted, with no pending-dispose handle.
    (is (= 7 (rf/subscribe-value [:n])))
    (is (not (contains? (cache-keys) [:n]))
        "slot is disposed synchronously inside subscribe-value — no deferred timer")))

(deftest subscribe-value-respects-concurrent-subscriber
  (testing "subscribe-value's :grace 0 teardown only disposes when it drove 1→0 (rf2-zmufj)"
    (subs/configure! {:grace-period-ms 60000})
    (rf/reg-event-db :init (fn [_ _] {:n 7}))
    (rf/reg-sub :n (fn [db _] (:n db)))
    (rf/dispatch-sync [:init])

    ;; Pin the slot with a reactive subscribe — ref-count = 1.
    (let [pin (rf/subscribe [:n])]
      (is (= 1 (entry-ref-count [:n])))

      ;; subscribe-value runs: subscribe (ref-count → 2), deref,
      ;; unsubscribe {:grace 0} (ref-count → 1). The decrement does NOT
      ;; drive 1→0 — the pinning subscribe is still live — so the slot
      ;; MUST survive untouched.
      (is (= 7 (rf/subscribe-value [:n])))
      (is (contains? (cache-keys) [:n])
          "slot survives — the pinning subscriber kept ref-count > 0")
      (is (= 1 (entry-ref-count [:n]))
          "ref-count back to 1 after subscribe-value's paired inc/dec")
      (is (not (pending-dispose? [:n]))
          "no dispose was scheduled — the {:grace 0} teardown only fires on 1→0")

      ;; Cleanup: release the pin so the fixture's grace-period reset
      ;; doesn't leave a live slot behind. (Re-frame.core does not
      ;; expose `dispose!` directly — the `unsubscribe` call below
      ;; releases the imperative subscribe's ref-count.)
      (rf/unsubscribe [:n])
      ;; `pin` is no longer used after this point; binding kept above
      ;; only to materialise the pinning subscribe — its reactive
      ;; lifetime ends with the line above.
      pin)))

(deftest unsubscribe-with-grace-opt-overrides-configured-grace
  (testing "(unsubscribe frame-id query-v {:grace 0}) disposes synchronously even when configured grace > 0"
    (subs/configure! {:grace-period-ms 60000})
    (rf/reg-event-db :init (fn [_ _] {:n 7}))
    (rf/reg-sub :n (fn [db _] (:n db)))
    (rf/dispatch-sync [:init])

    (rf/subscribe [:n])
    (is (= 1 (entry-ref-count [:n])))

    ;; The {:grace 0} opt forces synchronous disposal for THIS call only
    ;; — the per-runtime configured grace-period is unchanged.
    (rf/unsubscribe :rf/default [:n] {:grace 0})
    (is (not (contains? (cache-keys) [:n]))
        "slot disposed synchronously despite configured grace=60000ms")
    (is (= 60000 (:grace-period-ms (subs/current-config)))
        "per-runtime grace-period-ms is unchanged — only THIS call was overridden")))

;; ---- clear-subscription-cache! cancels pending timers ---------------------

(deftest clear-subscription-cache-cancels-pending
  (testing "clear-subscription-cache! cancels any pending grace-period timers"
    (subs/configure! {:grace-period-ms 60000})  ;; long grace; we'll clear before it fires
    (rf/reg-event-db :init (fn [_ _] {:n 7}))
    (rf/reg-sub :n (fn [db _] (:n db)))
    (rf/dispatch-sync [:init])

    (rf/subscribe [:n])
    (rf/unsubscribe [:n])
    (is (pending-dispose? [:n]))

    (rf/clear-subscription-cache! :rf/default)
    ;; The cache is empty — and the pending timer was cancelled.
    ;; Nothing observable to assert on the cancellation directly,
    ;; but the cache emptying without throwing covers the path.
    (is (empty? (cache-keys)))))

;; ---- hot-reload re-registration disposes pending entries ------------------

(deftest hot-reload-cancels-pending-and-disposes
  (testing "re-registering a sub disposes cached slots and cancels pending timers"
    (subs/configure! {:grace-period-ms 60000})
    (rf/reg-event-db :init (fn [_ _] {:n 7}))
    (rf/reg-sub :n (fn [db _] (:n db)))
    (rf/dispatch-sync [:init])

    (rf/subscribe [:n])
    (rf/unsubscribe [:n])
    (is (pending-dispose? [:n]))

    ;; Re-register with a different body — invalidate-sub-on-replace! fires.
    (rf/reg-sub :n (fn [db _] (* 2 (:n db))))
    (is (not (contains? (cache-keys) [:n]))
        "hot-reload evicts the slot regardless of pending-dispose")))

;; ---- subscribe-before-register does NOT cache (rf2-l9u5) -----------------
;;
;; Per Spec 006 §What happens when a sub references an unknown sub:
;; subscribing to an unregistered sub-id emits :rf.error/no-such-sub and
;; returns a nil-yielding reaction. Crucially, that miss is NOT cached —
;; the cache slot stays empty so that a later registration (boot order,
;; lazy-loaded namespace) is observed by the next subscribe, which builds
;; a fresh reaction against the real body.

(deftest subscribe-before-register-does-not-cache
  (testing "subscribing before reg-sub does not cache; later registration is observed"
    (subs/configure! {:grace-period-ms 0})
    (rf/reg-event-db :init (fn [_ _] {:n 7}))
    (rf/dispatch-sync [:init])

    ;; Subscribe BEFORE the sub is registered.
    (let [r1 (rf/subscribe [:my-sub])]
      (is (some? r1)
          "subscribe still returns a (nil-yielding) reaction so callers don't deref nil")
      (is (nil? @r1)
          "the reaction yields nil per :replaced-with-default recovery")
      (is (not (contains? (cache-keys) [:my-sub]))
          "the no-such-sub miss MUST NOT be cached (rf2-l9u5)"))

    ;; Now register the sub. First-time registration does NOT fire the
    ;; replacement hook — the boot-order fix is "don't cache on miss",
    ;; not "invalidate on first register".
    (rf/reg-sub :my-sub (fn [db _] (:n db)))
    (is (not (contains? (cache-keys) [:my-sub]))
        "first registration leaves the cache untouched")

    ;; Next subscribe builds a fresh reaction against the real body.
    (let [r2 (rf/subscribe [:my-sub])]
      (is (= 7 @r2)
          "post-registration subscribe yields the real value")
      (is (contains? (cache-keys) [:my-sub])
          "the post-registration reaction IS cached"))))

(deftest subscribe-before-register-survives-multiple-misses
  (testing "repeated subscribe-before-register calls do not poison the cache"
    (subs/configure! {:grace-period-ms 0})
    (rf/reg-event-db :init (fn [_ _] {:n 11}))
    (rf/dispatch-sync [:init])

    (dotimes [_ 3]
      (let [r (rf/subscribe [:lazy-sub])]
        (is (nil? @r))
        (is (not (contains? (cache-keys) [:lazy-sub])))))

    (rf/reg-sub :lazy-sub (fn [db _] (:n db)))
    (is (= 11 @(rf/subscribe [:lazy-sub]))
        "after registration, the cache is fresh and the real body runs")))

;; ---- clear-sub does NOT clear the per-frame cache (rf2-79tl) -------------
;;
;; v2 preserves v1's contract: clear-sub removes the registration but
;; leaves cached reactions in place. Cache eviction is a separate
;; concern, owned by clear-subscription-cache!, hot-reload (re-register)
;; and frame disposal. This split keeps clear-sub a pure registry op
;; (no per-frame side effects) and matches v1's documented behaviour
;; (per spec/API.md §Clearing registrations: clear-sub is "v1
;; (preserved)") and the v1 docstring's explicit caller-responsibility
;; note ("Depending on the usecase, it may be necessary to call
;; clear-subscription-cache! afterwards").
;;
;; If you want both effects in one call: clear-sub then
;; clear-subscription-cache! — or use re-registration if you have a
;; replacement body.

(deftest clear-sub-id-leaves-cache-intact
  (testing "(clear-sub id) removes the registration but does not evict cache slots"
    (subs/configure! {:grace-period-ms 60000})
    (rf/reg-event-db :init (fn [_ _] {:n 7}))
    (rf/reg-sub :n (fn [db _] (:n db)))
    (rf/dispatch-sync [:init])

    (let [r1 (rf/subscribe [:n])]
      (is (= 7 @r1))
      (is (contains? (cache-keys) [:n])))

    (rf/clear-sub :n)
    ;; Cache slot survives clear-sub — this is the documented v1 contract.
    (is (contains? (cache-keys) [:n])
        "clear-sub leaves the cache slot in place; use clear-subscription-cache! to evict")
    (is (nil? (registrar/lookup :sub :n))
        "the registration is gone")

    ;; Subsequent subscribe inside the grace-period reuses the cached reaction
    ;; (reading the still-derived value), even though the registration is gone.
    (let [r2 (rf/subscribe [:n])]
      (is (= 7 @r2)
          "cache hit serves the previously-derived value"))

    ;; clear-subscription-cache! is the explicit follow-up that fully resets.
    (rf/clear-subscription-cache! :rf/default)
    (is (empty? (cache-keys))
        "clear-subscription-cache! is the cache-eviction half of the pair")))

(deftest clear-sub-no-arg-leaves-cache-intact
  (testing "(clear-sub) clears every registration but leaves the cache untouched"
    (subs/configure! {:grace-period-ms 60000})
    (rf/reg-event-db :init (fn [_ _] {:n 7}))
    (rf/reg-sub :a (fn [db _] (:n db)))
    (rf/reg-sub :b (fn [db _] (* 2 (:n db))))
    (rf/dispatch-sync [:init])

    (rf/subscribe [:a])
    (rf/subscribe [:b])
    (is (= #{[:a] [:b]} (cache-keys)))

    (rf/clear-sub)
    (is (= #{[:a] [:b]} (cache-keys))
        "clear-sub with no args wipes registrations but not cache slots")
    (is (= {} (registrar/handlers :sub))
        "every :sub registration is gone")

    (rf/clear-subscription-cache! :rf/default)
    (is (empty? (cache-keys)))))

;; ---- idempotent unsubscribe (rf2-zikr) ------------------------------------
;;
;; Per Spec 006 §Reference counting and disposal: `unsubscribe` is
;; ref-count-based. Calling it past zero (cleanup in both a teardown hook
;; and a `finally`) must be idempotent — it must NOT
;;
;;   (a) decrement the ref-count into negative territory, OR
;;   (b) schedule a second `pending-dispose` timer on top of the existing
;;       handle (leaking the prior handle).
;;
;; The fix clamps the decrement at zero and only triggers drop-to-zero on
;; the 1→0 transition when no pending-dispose is already in flight.
;;
;; Pre-fix, the second unsubscribe computed `n = -1`, took the
;; `(<= n 0)` branch a second time, and called `set-timeout!` again — the
;; outer swap then overwrote the prior `:pending-dispose` slot, leaking the
;; original handle. This test asserts the post-fix invariant directly: the
;; ref-count stays at 0 and the same `:pending-dispose` handle is preserved
;; across repeated `unsubscribe` calls.

(deftest unsubscribe-past-zero-is-idempotent
  (testing "calling unsubscribe twice for the same sub-id does not leak pending-dispose handles (rf2-zikr)"
    (subs/configure! {:grace-period-ms 60000})  ;; long grace; we won't let it fire
    (rf/reg-event-db :init (fn [_ _] {:n 7}))
    (rf/reg-sub :n (fn [db _] (:n db)))
    (rf/dispatch-sync [:init])

    (rf/subscribe [:n])
    (is (= 1 (entry-ref-count [:n])))

    ;; First unsubscribe — ref-count 1→0, dispose scheduled, handle stashed.
    (rf/unsubscribe [:n])
    (is (= 0 (entry-ref-count [:n]))
        "first unsubscribe lands ref-count at zero")
    (is (pending-dispose? [:n])
        "first unsubscribe schedules the dispose timer")
    (let [handle-after-first
          (get-in @(:sub-cache (frame/frame :rf/default)) [[:n] :pending-dispose])]

      ;; Second unsubscribe — must be a no-op. Pre-fix this took
      ;; ref-count to -1 AND scheduled a new timer, overwriting
      ;; `handle-after-first` and leaking it.
      (rf/unsubscribe [:n])
      (is (= 0 (entry-ref-count [:n]))
          "ref-count clamped at zero — does NOT go negative on extra unsubscribe")
      (is (pending-dispose? [:n])
          "the original pending-dispose handle is preserved (not overwritten)")
      (let [handle-after-second
            (get-in @(:sub-cache (frame/frame :rf/default)) [[:n] :pending-dispose])]
        (is (identical? handle-after-first handle-after-second)
            "the same timer handle is on the slot after the second unsubscribe — no new schedule was made"))

      ;; And a third call for good measure — still idempotent.
      (rf/unsubscribe [:n])
      (is (= 0 (entry-ref-count [:n])))
      (let [handle-after-third
            (get-in @(:sub-cache (frame/frame :rf/default)) [[:n] :pending-dispose])]
        (is (identical? handle-after-first handle-after-third)
            "third unsubscribe still preserves the original handle"))))

  (testing "extra unsubscribe before any subscribe is a complete no-op"
    (subs/configure! {:grace-period-ms 0})
    (rf/reg-event-db :init (fn [_ _] {:n 7}))
    (rf/reg-sub :n (fn [db _] (:n db)))
    (rf/dispatch-sync [:init])
    ;; No subscribe — the entry doesn't exist; unsubscribe must not throw,
    ;; must not create an entry, and must not schedule a timer.
    (rf/unsubscribe [:n])
    (is (not (contains? (cache-keys) [:n]))
        "unsubscribe against a missing entry leaves the cache untouched")))

;; ---- rf2-fi4m: unsubscribe-then-no-recompute under value-change ----------
;;
;; Per test-coverage-review-2026-05-12 P3-26: `unsubscribe` symmetry with
;; `subscribe` is covered transitively through ref-counting, but no direct
;; deftest pins the value-change contract: once every subscriber has
;; unsubscribed and the entry has been disposed, a subsequent app-db
;; change MUST NOT recompute the sub fn.

(deftest unsubscribe-then-no-recompute-under-value-change
  (testing "after full unsubscription, a state change does NOT re-invoke
            the sub's compute fn — the cache entry has been disposed,
            its watch on app-db unwound"
    (subs/configure! {:grace-period-ms 0})  ;; sync dispose so the contract is observable
    (let [recompute-count (atom 0)]
      (rf/reg-event-db :seed   (fn [_ _]   {:n 0}))
      (rf/reg-event-db :update (fn [db [_ n]] (assoc db :n n)))
      (rf/reg-sub :n (fn [db _]
                       (swap! recompute-count inc)
                       (:n db)))
      (rf/dispatch-sync [:seed])

      ;; Step 1: two ref-counted subscribes against [:n].
      (let [r1 (rf/subscribe [:n])
            r2 (rf/subscribe [:n])]
        (is (identical? r1 r2)
            "the cache returns the same reaction across both subscribes")
        ;; First read forces compute.
        (is (= 0 @r1))
        (is (= 1 @recompute-count) "compute fn ran once for the initial read")
        (is (= 2 (entry-ref-count [:n]))
            "ref-count is 2 — both subscribers are tracked")

        ;; Step 2: change the source value. The reaction recomputes once.
        (rf/dispatch-sync [:update 1])
        (is (= 1 @r1) "reaction reflects the new value")
        (is (= 2 @recompute-count) "compute fn ran exactly once for the change")

        ;; Step 3: first unsubscribe — sub still cached (ref-count > 0).
        (rf/unsubscribe [:n])
        (is (contains? (cache-keys) [:n])
            "cache slot is still present (one ref remains)")
        (is (= 1 (entry-ref-count [:n])) "ref-count dropped to 1")

        ;; Step 4: second unsubscribe — drops to 0 → synchronous dispose
        ;; (grace=0 fixture). The cache slot is gone; the underlying
        ;; reaction's watch on app-db is unwound.
        (rf/unsubscribe [:n])
        (is (not (contains? (cache-keys) [:n]))
            "cache slot disposed — no remaining ref")
        (let [count-after-dispose @recompute-count]

          ;; Step 5: a subsequent app-db change MUST NOT re-invoke the
          ;; compute fn. The reaction has been disposed; there is no
          ;; live cache entry to recompute, and the watch the reaction
          ;; held on the app-db container was removed at dispose-time.
          (rf/dispatch-sync [:update 2])
          (is (= count-after-dispose @recompute-count)
              "compute fn did NOT run after full unsubscription — the
               reaction was disposed; its watch on app-db is gone")
          (rf/dispatch-sync [:update 3])
          (rf/dispatch-sync [:update 4])
          (is (= count-after-dispose @recompute-count)
              "subsequent state changes still do not recompute"))))))

;; ---- rf2-f3rd: layer-2+ disposal decrements input ref-counts --------------
;;
;; Per Spec 006 §Reference counting and disposal — disposal cascades clause:
;; "When a layer-2 sub disposes, its layer-1 inputs lose one reader each;
;; if they were held only by that layer-2 sub, they enter their own
;; grace-period." Pre-fix, `compute-and-cache!`'s `add-on-dispose!`
;; callback only dissoc'd the parent slot and never decremented the
;; input ref-counts that `subscribe` incremented during construction —
;; so input ref-counts leaked. The fix walks `:input-signals` and calls
;; `unsubscribe` on each input symmetrically with the construction-time
;; `subscribe` calls. These tests pin the symmetric invariant.

(deftest layer-2-disposal-decrements-input-ref-counts
  (testing "disposing a layer-2 sub decrements ref-counts on every :<- input"
    (subs/configure! {:grace-period-ms 0})
    (rf/reg-event-db :init (fn [_ _] {:a 2 :b 3}))
    (rf/reg-sub :a (fn [db _] (:a db)))
    (rf/reg-sub :b (fn [db _] (:b db)))
    (rf/reg-sub :sum
      :<- [:a]
      :<- [:b]
      (fn [[a b] _] (+ a b)))
    (rf/dispatch-sync [:init])

    ;; Subscribe to the parent layer-2 sub. This recursively subscribes
    ;; to both inputs, bumping each to ref-count 1.
    (let [r (rf/subscribe [:sum])]
      (is (= 5 @r))
      (is (= 1 (entry-ref-count [:sum])) "parent ref-count = 1")
      (is (= 1 (entry-ref-count [:a])) "input :a ref-count = 1 after layer-2 build")
      (is (= 1 (entry-ref-count [:b])) "input :b ref-count = 1 after layer-2 build"))

    ;; Dispose the parent (sole subscriber drops → grace=0 sync dispose).
    (rf/unsubscribe [:sum])

    ;; Parent slot is gone; inputs cascaded to ref-count 0 and were
    ;; themselves disposed (no other holders).
    (is (not (contains? (cache-keys) [:sum])) "parent disposed")
    (is (not (contains? (cache-keys) [:a]))
        "input :a disposed via cascade (ref-count → 0)")
    (is (not (contains? (cache-keys) [:b]))
        "input :b disposed via cascade (ref-count → 0)")))

(deftest layer-2-disposal-respects-shared-inputs
  (testing "disposing one layer-2 sub decrements shared input only by one"
    (subs/configure! {:grace-period-ms 0})
    (rf/reg-event-db :init (fn [_ _] {:a 2 :b 3 :c 4}))
    (rf/reg-sub :a (fn [db _] (:a db)))
    (rf/reg-sub :b (fn [db _] (:b db)))
    (rf/reg-sub :c (fn [db _] (:c db)))
    ;; Two layer-2 subs that share input :a.
    (rf/reg-sub :ab :<- [:a] :<- [:b] (fn [[a b] _] (+ a b)))
    (rf/reg-sub :ac :<- [:a] :<- [:c] (fn [[a c] _] (+ a c)))
    (rf/dispatch-sync [:init])

    (rf/subscribe [:ab])
    (rf/subscribe [:ac])

    ;; :a is held by both layer-2 subs → ref-count 2.
    (is (= 2 (entry-ref-count [:a])) "shared input :a has ref-count 2")
    (is (= 1 (entry-ref-count [:b])))
    (is (= 1 (entry-ref-count [:c])))

    ;; Dispose one layer-2 sub.
    (rf/unsubscribe [:ab])

    ;; :a's ref-count drops to 1 (not 0) — the other layer-2 still holds it.
    (is (not (contains? (cache-keys) [:ab])) ":ab disposed")
    (is (contains? (cache-keys) [:a])
        ":a survives — still referenced by :ac")
    (is (= 1 (entry-ref-count [:a]))
        "shared input ref-count dropped by exactly 1 (now 1, not 0, not 2)")
    (is (not (contains? (cache-keys) [:b]))
        ":b was held only by :ab — disposed via cascade")
    (is (contains? (cache-keys) [:c])
        ":c untouched — still held by :ac")
    (is (= 1 (entry-ref-count [:c])))

    ;; Dispose the other layer-2 sub. Now :a's ref-count hits 0.
    (rf/unsubscribe [:ac])
    (is (not (contains? (cache-keys) [:ac])))
    (is (not (contains? (cache-keys) [:a]))
        ":a finally disposed after the last layer-2 holder dropped")
    (is (not (contains? (cache-keys) [:c]))
        ":c disposed via cascade")))

(deftest layer-2-disposal-respects-externally-held-input
  (testing "an input also held by a direct subscribe is not over-disposed"
    (subs/configure! {:grace-period-ms 0})
    (rf/reg-event-db :init (fn [_ _] {:a 2 :b 3}))
    (rf/reg-sub :a (fn [db _] (:a db)))
    (rf/reg-sub :b (fn [db _] (:b db)))
    (rf/reg-sub :sum :<- [:a] :<- [:b] (fn [[a b] _] (+ a b)))
    (rf/dispatch-sync [:init])

    ;; External subscriber to :a, parallel to the layer-2 sub that also uses :a.
    (rf/subscribe [:a])
    (rf/subscribe [:sum])

    ;; :a has TWO holders: the direct subscribe AND :sum's construction.
    (is (= 2 (entry-ref-count [:a]))
        ":a ref-count is 2 — one direct subscribe, one layer-2 dependency")
    (is (= 1 (entry-ref-count [:b])))
    (is (= 1 (entry-ref-count [:sum])))

    ;; Dispose the layer-2 sub. :a's count drops from 2 → 1 (NOT 0).
    (rf/unsubscribe [:sum])
    (is (not (contains? (cache-keys) [:sum])))
    (is (contains? (cache-keys) [:a]) ":a NOT disposed — external holder remains")
    (is (= 1 (entry-ref-count [:a]))
        "decrement was exactly one — external subscribe still holds :a")
    (is (not (contains? (cache-keys) [:b]))
        ":b had only the layer-2 holder — disposed via cascade")

    ;; External unsubscribe finishes the job.
    (rf/unsubscribe [:a])
    (is (not (contains? (cache-keys) [:a]))
        ":a disposed when external holder drops")))

(deftest layer-3-disposal-cascades-through-chain
  (testing "disposal cascades recursively through a layer-3 chain"
    (subs/configure! {:grace-period-ms 0})
    (rf/reg-event-db :init (fn [_ _] {:a 2}))
    (rf/reg-sub :a (fn [db _] (:a db)))
    (rf/reg-sub :a*2 :<- [:a]   (fn [a _] (* 2 a)))
    (rf/reg-sub :a*4 :<- [:a*2] (fn [a2 _] (* 2 a2)))
    (rf/dispatch-sync [:init])

    (let [r (rf/subscribe [:a*4])]
      (is (= 8 @r))
      (is (= 1 (entry-ref-count [:a*4])))
      (is (= 1 (entry-ref-count [:a*2])))
      (is (= 1 (entry-ref-count [:a]))))

    (rf/unsubscribe [:a*4])

    ;; Whole chain cascaded to disposal.
    (is (not (contains? (cache-keys) [:a*4])))
    (is (not (contains? (cache-keys) [:a*2])))
    (is (not (contains? (cache-keys) [:a])))))

(deftest layer-2-multi-subscriber-disposal-keeps-inputs-alive
  (testing "with two parent subscribers, dropping one keeps inputs at ref-count 1"
    (subs/configure! {:grace-period-ms 0})
    (rf/reg-event-db :init (fn [_ _] {:a 2 :b 3}))
    (rf/reg-sub :a (fn [db _] (:a db)))
    (rf/reg-sub :b (fn [db _] (:b db)))
    (rf/reg-sub :sum :<- [:a] :<- [:b] (fn [[a b] _] (+ a b)))
    (rf/dispatch-sync [:init])

    ;; Two subscribers to the same parent — parent ref-count goes to 2,
    ;; inputs are constructed once (only the FIRST subscribe runs the
    ;; cache-miss path that subscribes to inputs).
    (rf/subscribe [:sum])
    (rf/subscribe [:sum])
    (is (= 2 (entry-ref-count [:sum])))
    (is (= 1 (entry-ref-count [:a]))
        "inputs only acquired on the cache-miss path — ref-count 1")
    (is (= 1 (entry-ref-count [:b])))

    ;; First unsubscribe: parent drops to 1, inputs untouched
    ;; (the parent slot didn't dispose, so its on-dispose didn't fire).
    (rf/unsubscribe [:sum])
    (is (= 1 (entry-ref-count [:sum])))
    (is (= 1 (entry-ref-count [:a]))
        "inputs untouched — parent slot still live")
    (is (= 1 (entry-ref-count [:b])))

    ;; Second unsubscribe: parent disposes, cascade fires.
    (rf/unsubscribe [:sum])
    (is (not (contains? (cache-keys) [:sum])))
    (is (not (contains? (cache-keys) [:a])))
    (is (not (contains? (cache-keys) [:b])))))

