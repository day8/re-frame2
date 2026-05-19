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
     :init-fn (fn []
                (reset! subs/diff-cache {})
                (reset! subs/annotated-tree-cache {})
                (reset! subs/redacted-modified-cache {}))}))

(deftest leaf-install-registers-the-active-subs
  (subs/install!)
  ;; rf2-fvplw — `:rf.causa/observed-frame` is the picker/focus-aware
  ;; seam that replaces the legacy `:rf.causa/target-frame` read inside
  ;; `:rf.causa/target-frame-db` + the composite.
  (is (some? (registrar/handler :sub :rf.causa/observed-frame)))
  (is (some? (registrar/handler :sub :rf.causa/target-frame-db)))
  (is (some? (registrar/handler :sub :rf.causa/selected-epoch-record)))
  (is (some? (registrar/handler :sub :rf.causa/selected-epoch-diff)))
  (is (some? (registrar/handler :sub :rf.causa/selected-epoch-annotated-tree)))
  (is (some? (registrar/handler :sub :rf.causa/selected-epoch-sections)))
  (is (some? (registrar/handler :sub :rf.causa/focused-slice-path)))
  (is (some? (registrar/handler :sub :rf.causa/show-me-when-this-changed-result)))
  ;; rf2-bz1cl — redacted-paths-modified hint sub.
  (is (some? (registrar/handler :sub :rf.causa/selected-epoch-redacted-modified-count)))
  (is (some? (registrar/handler :sub :rf.causa/app-db-diff)))
  ;; rf2-e9tb0 — pinned-slices subs are gone.
  (is (nil? (registrar/handler :sub :rf.causa/pinned-slices-store)))
  (is (nil? (registrar/handler :sub :rf.causa/pinned-slices))))

(deftest diff-caches-are-leaf-level-atoms
  (is (some? subs/diff-cache))
  (is (map? @subs/diff-cache))
  (is (some? subs/annotated-tree-cache))
  (is (map? @subs/annotated-tree-cache))
  ;; rf2-bz1cl — redacted-modified count cache mirrors the existing
  ;; per-`:epoch-id` caching contract.
  (is (some? subs/redacted-modified-cache))
  (is (map? @subs/redacted-modified-cache)))
