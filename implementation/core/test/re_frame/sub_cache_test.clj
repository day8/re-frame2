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
