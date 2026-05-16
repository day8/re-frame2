(ns day8.re-frame2-causa.panels.ai-co-pilot-events-cljs-test
  "Per-leaf smoke test for `ai-co-pilot-events` (rf2-nb8if).

  Calls the leaf's `install!` directly and asserts the copilot
  events + the pull-only `:rf.causa.fx/llm-stream` no-op fx are
  registered. Dispatches one happy-path event
  (`:rf.causa/copilot-toggle`) and asserts the :rf/causa app-db
  flips both `:copilot-open?` and `:copilot-first-used?`."
  (:require [cljs.test :refer-macros [deftest is use-fixtures]]
            [re-frame.core :as rf]
            [re-frame.frame :as frame]
            [re-frame.registrar :as registrar]
            [re-frame.substrate.plain-atom :as plain-atom]
            [re-frame.test-support :as test-support]
            [day8.re-frame2-causa.panels.ai-co-pilot-events :as events]))

(use-fixtures :each
  (test-support/reset-runtime-fixture {:adapter plain-atom/adapter}))

(deftest leaf-install-registers-events-and-fx
  (events/install!)
  (is (some? (registrar/handler :event :rf.causa/copilot-toggle)))
  (is (some? (registrar/handler :event :rf.causa/copilot-set-input-text)))
  (is (some? (registrar/handler :event :rf.causa/copilot-cycle-provider)))
  (is (some? (registrar/handler :event :rf.causa/copilot-submit-question)))
  (is (some? (registrar/handler :event :rf.causa/copilot-clear-conversation)))
  (is (some? (registrar/handler :event :rf.causa/copilot-chip-clicked)))
  (is (some? (registrar/handler :fx :rf.causa.fx/llm-stream))))

(deftest copilot-toggle-flips-open-and-first-used
  (events/install!)
  (frame/reg-frame :rf/causa {})
  (rf/with-frame :rf/causa
    (rf/dispatch-sync [:rf.causa/copilot-toggle]))
  (let [db (frame/frame-app-db-value :rf/causa)]
    (is (true? (:copilot-open? db)))
    (is (true? (:copilot-first-used? db)))))
