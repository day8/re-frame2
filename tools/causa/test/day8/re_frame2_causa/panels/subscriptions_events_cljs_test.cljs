(ns day8.re-frame2-causa.panels.subscriptions-events-cljs-test
  "Per-leaf smoke test for `subscriptions-events` (rf2-nb8if).

  Calls the leaf's `install!` directly and asserts the six events
  are registered. Dispatches one happy-path event and asserts the
  :rf/causa app-db transition."
  (:require [cljs.test :refer-macros [deftest is use-fixtures]]
            [re-frame.core :as rf]
            [re-frame.frame :as frame]
            [re-frame.registrar :as registrar]
            [re-frame.substrate.plain-atom :as plain-atom]
            [re-frame.test-support :as test-support]
            [day8.re-frame2-causa.panels.subscriptions-events :as events]))

(use-fixtures :each
  (test-support/reset-runtime-fixture {:adapter plain-atom/adapter}))

(deftest leaf-install-registers-events
  (events/install!)
  (is (some? (registrar/handler :event :rf.causa/select-sub)))
  (is (some? (registrar/handler :event :rf.causa/clear-selected-sub)))
  (is (some? (registrar/handler :event :rf.causa/toggle-sub-filter)))
  (is (some? (registrar/handler :event :rf.causa/show-invalidation-chain)))
  (is (some? (registrar/handler :event :rf.causa/hide-invalidation-chain)))
  (is (some? (registrar/handler :event :rf.causa/set-sub-cache-override-for-test))))

(deftest select-sub-dispatch-writes-selected-sub
  (events/install!)
  (frame/reg-frame :rf/causa {})
  (rf/with-frame :rf/causa
    (rf/dispatch-sync [:rf.causa/select-sub [:cart/total]]))
  (is (= [:cart/total]
         (:selected-sub (frame/frame-app-db-value :rf/causa)))))
