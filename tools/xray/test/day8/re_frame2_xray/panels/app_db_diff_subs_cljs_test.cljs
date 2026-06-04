(ns day8.re-frame2-xray.panels.app-db-diff-subs-cljs-test
  "Per-leaf smoke test for `app-db-diff-subs` (rf2-nb8if).

  Calls the leaf's `install!` directly (NOT the umbrella) and asserts
  the live app-db-tab subs are registered.

  ## rf2-p53m2 — dead diff-sub family pruned

  The `:rf.xray/selected-epoch-diff` → `:rf.xray/app-db-diff` composite
  (plus the `:rf.xray/selected-epoch-redacted-modified-count` /
  `:rf.xray/selected-epoch-flow-writes` inputs and the three
  `[frame-id epoch-id]` caches) had no production view consumer and was
  removed. The assertions below pin that the family stays gone — a
  regression guard against re-introducing the hardened-but-unrendered
  surface."
  (:require [cljs.test :refer-macros [deftest is use-fixtures]]
            [re-frame.registrar :as registrar]
            [re-frame.substrate.plain-atom :as plain-atom]
            [re-frame.test-support :as test-support]
            [day8.re-frame2-xray.panels.app-db-diff-subs :as subs]))

(use-fixtures :each
  (test-support/make-reset-runtime-fixture
    {:adapter plain-atom/adapter}))

(deftest leaf-install-registers-the-active-subs
  (subs/install!)
  ;; rf2-fvplw — `:rf.xray/observed-frame` is the picker/focus-aware
  ;; seam that replaces the legacy `:rf.xray/target-frame` read inside
  ;; `:rf.xray/target-frame-db`.
  (is (some? (registrar/handler :sub :rf.xray/observed-frame)))
  (is (some? (registrar/handler :sub :rf.xray/target-frame-db)))
  (is (some? (registrar/handler :sub :rf.xray/selected-epoch-record)))
  (is (some? (registrar/handler :sub :rf.xray/focused-slice-path)))
  (is (some? (registrar/handler :sub :rf.xray/show-me-when-this-changed-result)))
  ;; rf2-yng0y — the atomic current-state + focused-epoch before-image
  ;; sub the panel pivots on (collapses the former 5-deep focus chain so
  ;; `:before` / `:epoch-id` move together — no stale-`before` frame).
  (is (some? (registrar/handler :sub :rf.xray/app-db-current+diff)))
  ;; rf2-okvit — the current-state inspector's section-model sub (now
  ;; derived from the atomic sub above).
  (is (some? (registrar/handler :sub :rf.xray/app-db-state))))

(deftest pruned-diff-sub-family-stays-gone
  ;; rf2-p53m2 — the dead composite diff family had no production view
  ;; consumer; the Epoch panel reads `:rf.xray/selected-epoch-record`
  ;; (not these), and the MCP `get-app-db-diff` tool goes directly
  ;; through `diff.engine/project`. Guard against re-introduction.
  (subs/install!)
  (is (nil? (registrar/handler :sub :rf.xray/selected-epoch-diff)))
  (is (nil? (registrar/handler :sub :rf.xray/selected-epoch-redacted-modified-count)))
  (is (nil? (registrar/handler :sub :rf.xray/selected-epoch-flow-writes)))
  (is (nil? (registrar/handler :sub :rf.xray/app-db-diff)))
  ;; rf2-e9tb0 — pinned-slices subs are gone (older prune; kept here as
  ;; part of the dead-surface guard).
  (is (nil? (registrar/handler :sub :rf.xray/pinned-slices-store)))
  (is (nil? (registrar/handler :sub :rf.xray/pinned-slices))))
