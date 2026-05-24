(ns day8.re-frame2-xray.panels.app-db-diff-subs-cljs-test
  "Per-leaf smoke test for `app-db-diff-subs` (rf2-nb8if).

  Calls the leaf's `install!` directly (NOT the umbrella) and asserts
  the seven Phase 5 subs are registered. Reads the composite
  `:rf.xray/app-db-diff` once on a fresh frame and asserts the
  default-shape map. Pins the per-`:epoch-id` `defonce diff-cache`
  atom is reachable as a leaf var."
  (:require [cljs.test :refer-macros [deftest is use-fixtures]]
            [re-frame.registrar :as registrar]
            [re-frame.substrate.plain-atom :as plain-atom]
            [re-frame.test-support :as test-support]
            [day8.re-frame2-xray.panels.app-db-diff-subs :as subs]))

(use-fixtures :each
  (test-support/make-reset-runtime-fixture
    {:adapter plain-atom/adapter
     :init-fn (fn []
                (reset! subs/diff-cache {})
                (reset! subs/annotated-tree-cache {})
                (reset! subs/redacted-modified-cache {})
                (reset! subs/flow-writes-cache {}))}))

(deftest leaf-install-registers-the-active-subs
  (subs/install!)
  ;; rf2-fvplw — `:rf.xray/observed-frame` is the picker/focus-aware
  ;; seam that replaces the legacy `:rf.xray/target-frame` read inside
  ;; `:rf.xray/target-frame-db` + the composite.
  (is (some? (registrar/handler :sub :rf.xray/observed-frame)))
  (is (some? (registrar/handler :sub :rf.xray/target-frame-db)))
  (is (some? (registrar/handler :sub :rf.xray/selected-epoch-record)))
  (is (some? (registrar/handler :sub :rf.xray/selected-epoch-diff)))
  (is (some? (registrar/handler :sub :rf.xray/selected-epoch-annotated-tree)))
  (is (some? (registrar/handler :sub :rf.xray/selected-epoch-sections)))
  (is (some? (registrar/handler :sub :rf.xray/focused-slice-path)))
  (is (some? (registrar/handler :sub :rf.xray/show-me-when-this-changed-result)))
  ;; rf2-bz1cl — redacted-paths-modified hint sub.
  (is (some? (registrar/handler :sub :rf.xray/selected-epoch-redacted-modified-count)))
  ;; rf2-s8r6c — flow-writes projection sub for the origin-tag chip.
  (is (some? (registrar/handler :sub :rf.xray/selected-epoch-flow-writes)))
  (is (some? (registrar/handler :sub :rf.xray/app-db-diff)))
  ;; rf2-okvit — the current-state inspector's section-model sub.
  (is (some? (registrar/handler :sub :rf.xray/app-db-state)))
  ;; rf2-e9tb0 — pinned-slices subs are gone.
  (is (nil? (registrar/handler :sub :rf.xray/pinned-slices-store)))
  (is (nil? (registrar/handler :sub :rf.xray/pinned-slices))))

(deftest diff-caches-are-leaf-level-atoms
  (is (some? subs/diff-cache))
  (is (map? @subs/diff-cache))
  (is (some? subs/annotated-tree-cache))
  (is (map? @subs/annotated-tree-cache))
  ;; rf2-bz1cl — redacted-modified count cache mirrors the existing
  ;; per-`:epoch-id` caching contract.
  (is (some? subs/redacted-modified-cache))
  (is (map? @subs/redacted-modified-cache))
  ;; rf2-s8r6c — flow-writes cache mirrors the same per-`:epoch-id`
  ;; caching contract.
  (is (some? subs/flow-writes-cache))
  (is (map? @subs/flow-writes-cache)))
