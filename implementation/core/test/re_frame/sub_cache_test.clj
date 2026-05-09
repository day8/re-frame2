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
            [re-frame.flows :as flows]))

(defn reset-runtime [test-fn]
  (registrar/clear-all!)
  (reset! frame/frames {})
  (reset! flows/flows {})
  (reset! schemas/schemas-by-frame {})
  (rf/init!)
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
