(ns day8.re-frame2-causa.panels.mcp-server-events-cljs-test
  "Per-leaf smoke test for `mcp-server-events` (rf2-nb8if).

  Calls the leaf's `install!` directly and asserts the four events
  are registered. Dispatches one happy-path event and asserts the
  :rf/causa app-db transition."
  (:require [cljs.test :refer-macros [deftest is use-fixtures]]
            [re-frame.core :as rf]
            [re-frame.frame :as frame]
            [re-frame.registrar :as registrar]
            [re-frame.substrate.plain-atom :as plain-atom]
            [re-frame.test-support :as test-support]
            [day8.re-frame2-causa.panels.mcp-server-events :as events]))

(use-fixtures :each
  (test-support/reset-runtime-fixture {:adapter plain-atom/adapter}))

(deftest leaf-install-registers-events
  (events/install!)
  (is (some? (registrar/handler :event :rf.causa/toggle-mcp-op-type)))
  (is (some? (registrar/handler :event :rf.causa/set-mcp-since-seconds)))
  (is (some? (registrar/handler :event :rf.causa/clear-mcp-filters)))
  (is (some? (registrar/handler :event :rf.causa/toggle-mcp-origin-filter))))

(deftest toggle-mcp-op-type-flips-active-set
  (events/install!)
  (frame/reg-frame :rf/causa {})
  (rf/with-frame :rf/causa
    (rf/dispatch-sync [:rf.causa/toggle-mcp-op-type :tool-call]))
  (is (= #{:tool-call}
         (:mcp-active-op-types (frame/frame-app-db-value :rf/causa))))
  (rf/with-frame :rf/causa
    (rf/dispatch-sync [:rf.causa/toggle-mcp-op-type :tool-call]))
  (is (not (contains? (or (:mcp-active-op-types
                           (frame/frame-app-db-value :rf/causa)) #{})
                      :tool-call))
      "toggling the same op-type a second time disjs it"))
