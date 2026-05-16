(ns day8.re-frame2-causa.panels.app-db-diff-events-cljs-test
  "Per-leaf smoke test for `app-db-diff-events` (rf2-nb8if).

  Calls the leaf's `install!` directly (NOT the umbrella
  `register-causa-handlers!`) so the leaf is pinned as an
  independently usable install unit. Dispatches one happy-path
  event and asserts the resulting :rf/causa app-db transition."
  (:require [cljs.test :refer-macros [deftest is use-fixtures]]
            [re-frame.core :as rf]
            [re-frame.frame :as frame]
            [re-frame.registrar :as registrar]
            [re-frame.substrate.plain-atom :as plain-atom]
            [re-frame.test-support :as test-support]
            [day8.re-frame2-causa.panels.app-db-diff-events :as events]))

(use-fixtures :each
  (test-support/reset-runtime-fixture {:adapter plain-atom/adapter}))

(deftest leaf-install-registers-events-and-fxs
  (events/install!)
  (is (some? (registrar/handler :event :rf.causa/pin-slice)))
  (is (some? (registrar/handler :event :rf.causa/unpin-slice)))
  (is (some? (registrar/handler :event :rf.causa/focus-slice-path)))
  (is (some? (registrar/handler :event :rf.causa/clear-slice-focus)))
  (is (some? (registrar/handler :fx :rf.causa.fx/copy-to-clipboard))))

(deftest pin-slice-dispatch-writes-pin-store
  (events/install!)
  (frame/reg-frame :rf/causa {})
  (rf/with-frame :rf/causa
    (rf/dispatch-sync [:rf.causa/pin-slice [:cart :items]]))
  (is (= [[:cart :items]]
         (get-in (frame/frame-app-db-value :rf/causa)
                 [:pinned-slices-store :rf/default]))))
