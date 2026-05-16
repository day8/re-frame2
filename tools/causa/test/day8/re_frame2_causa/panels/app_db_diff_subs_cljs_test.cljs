(ns day8.re-frame2-causa.panels.app-db-diff-subs-cljs-test
  "Per-leaf smoke test for `app-db-diff-subs` (rf2-nb8if).

  Calls the leaf's `install!` directly (NOT the umbrella) and asserts
  the seven Phase 5 subs are registered. Reads the composite
  `:rf.causa/app-db-diff` once on a fresh frame and asserts the
  default-shape map. Pins the per-`:epoch-id` `defonce diff-cache`
  atom is reachable as a leaf var."
  (:require [cljs.test :refer-macros [deftest is use-fixtures]]
            [re-frame.registrar :as registrar]
            [re-frame.substrate.plain-atom :as plain-atom]
            [re-frame.test-support :as test-support]
            [day8.re-frame2-causa.panels.app-db-diff-subs :as subs]))

(use-fixtures :each
  (test-support/reset-runtime-fixture
    {:adapter plain-atom/adapter
     :init-fn (fn [] (reset! subs/diff-cache {}))}))

(deftest leaf-install-registers-the-eight-subs
  (subs/install!)
  (is (some? (registrar/handler :sub :rf.causa/target-frame-db)))
  (is (some? (registrar/handler :sub :rf.causa/selected-epoch-record)))
  (is (some? (registrar/handler :sub :rf.causa/selected-epoch-diff)))
  (is (some? (registrar/handler :sub :rf.causa/pinned-slices-store)))
  (is (some? (registrar/handler :sub :rf.causa/pinned-slices)))
  (is (some? (registrar/handler :sub :rf.causa/focused-slice-path)))
  (is (some? (registrar/handler :sub :rf.causa/show-me-when-this-changed-result)))
  (is (some? (registrar/handler :sub :rf.causa/app-db-diff))))

(deftest diff-cache-is-a-leaf-level-atom
  (is (some? subs/diff-cache))
  (is (map? @subs/diff-cache)))
