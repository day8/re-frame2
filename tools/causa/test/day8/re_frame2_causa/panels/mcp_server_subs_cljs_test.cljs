(ns day8.re-frame2-causa.panels.mcp-server-subs-cljs-test
  "Per-leaf smoke test for `mcp-server-subs` (rf2-nb8if).

  Calls the leaf's `install!` directly and asserts the three subs
  the panel reads are registered."
  (:require [cljs.test :refer-macros [deftest is use-fixtures]]
            [re-frame.registrar :as registrar]
            [re-frame.substrate.plain-atom :as plain-atom]
            [re-frame.test-support :as test-support]
            [day8.re-frame2-causa.panels.mcp-server-subs :as subs]))

(use-fixtures :each
  (test-support/reset-runtime-fixture {:adapter plain-atom/adapter}))

(deftest leaf-install-registers-subs
  (subs/install!)
  (is (some? (registrar/handler :sub :rf.causa/mcp-filters)))
  (is (some? (registrar/handler :sub :rf.causa/mcp-server)))
  (is (some? (registrar/handler :sub :rf.causa/mcp-origin-filter-enabled?))))
