(ns day8.re-frame2-causa.panels.ai-co-pilot-subs-cljs-test
  "Per-leaf smoke test for `ai-co-pilot-subs` (rf2-nb8if).

  Calls the leaf's `install!` directly and asserts the seven
  copilot-* subs are registered."
  (:require [cljs.test :refer-macros [deftest is use-fixtures]]
            [re-frame.registrar :as registrar]
            [re-frame.substrate.plain-atom :as plain-atom]
            [re-frame.test-support :as test-support]
            [day8.re-frame2-causa.panels.ai-co-pilot-subs :as subs]))

(use-fixtures :each
  (test-support/reset-runtime-fixture {:adapter plain-atom/adapter}))

(deftest leaf-install-registers-the-seven-copilot-subs
  (subs/install!)
  (is (some? (registrar/handler :sub :rf.causa/copilot-open?)))
  (is (some? (registrar/handler :sub :rf.causa/copilot-conversation)))
  (is (some? (registrar/handler :sub :rf.causa/copilot-provider)))
  (is (some? (registrar/handler :sub :rf.causa/copilot-cue-active?)))
  (is (some? (registrar/handler :sub :rf.causa/copilot-redaction-settings)))
  (is (some? (registrar/handler :sub :rf.causa/copilot-streaming-token-count)))
  (is (some? (registrar/handler :sub :rf.causa/copilot-input-text))))
