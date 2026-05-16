(ns day8.re-frame2-causa.panels.subscriptions-subs-cljs-test
  "Per-leaf smoke test for `subscriptions-subs` (rf2-nb8if).

  Calls the leaf's `install!` directly and asserts the six subs +
  the composite `:rf.causa/subscriptions-data` are registered."
  (:require [cljs.test :refer-macros [deftest is use-fixtures]]
            [re-frame.registrar :as registrar]
            [re-frame.substrate.plain-atom :as plain-atom]
            [re-frame.test-support :as test-support]
            [day8.re-frame2-causa.panels.subscriptions-subs :as subs]))

(use-fixtures :each
  (test-support/reset-runtime-fixture {:adapter plain-atom/adapter}))

(deftest leaf-install-registers-the-subs
  (subs/install!)
  (is (some? (registrar/handler :sub :rf.causa/sub-cache)))
  (is (some? (registrar/handler :sub :rf.causa/sub-error-cache)))
  (is (some? (registrar/handler :sub :rf.causa/selected-sub)))
  (is (some? (registrar/handler :sub :rf.causa/sub-filters)))
  (is (some? (registrar/handler :sub :rf.causa/sub-chain-open?)))
  (is (some? (registrar/handler :sub :rf.causa/subscriptions-data))))
