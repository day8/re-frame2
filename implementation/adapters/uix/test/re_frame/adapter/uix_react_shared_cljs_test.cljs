(ns re-frame.adapter.uix-react-shared-cljs-test
  "UIx entry-point for the parameterised React-adapter suite
  (`re-frame.adapter.react-shared-suite`, rf2-sx77q).

  UIx and Helix wire their entire public surface out of the same
  `spine/make-react-spine` factory, so every spine-shared behaviour is
  asserted once in the shared suite and forwarded here with the UIx
  config. This file is the structural guarantee that UIx and Helix never
  drift on the shared contracts: a gap on one is a gap on both because
  there is exactly one suite source.

  Coverage forwarded (closes the rf2-sx77q parity gaps for the React
  pair): dispose MUSTs 1–4 + best-effort poison tolerance (G3),
  source-coord DOM stamping incl. the format-shape split (G2), view-id
  tagging, frame-context-corrupted (G4), warn-once fire-once + per-id
  (G5), and the write-after-destroy guard.

  The previously hand-duplicated UIx files retained for now (so no
  coverage is lost in this pass) are: `uix_dispose_adapter`,
  `uix_frame_context_corrupted`, `uix_view_id_attr`. They are superseded
  by this suite and slated for deletion in the follow-up consolidation
  bead. The remaining unique UIx files (after_render_dom,
  use_subscribe_dom — browser/DOM-only; parity, render_to_string, events,
  routing, http_managed, runtime, etc.) stay independent.

  ns ends in -cljs-test so shadow-cljs's :node-test build picks it up."
  (:require [cljs.test :refer-macros [deftest use-fixtures]]
            [re-frame.adapter.uix :as uix-adapter]
            [re-frame.adapter.react-shared-suite :as suite]
            [re-frame.test-support :as test-support]))

(use-fixtures :each
  (test-support/make-reset-runtime-fixture
    {:adapter uix-adapter/adapter}))

(def ^:private cfg
  {:adapter          uix-adapter/adapter
   :substrate-kw     :uix
   :name             "UIx"
   :wrap-view        uix-adapter/wrap-view
   :clear-warn!      uix-adapter/clear-warned-non-dom-roots!
   :set-emitter!     uix-adapter/set-hiccup-emitter!
   :render-to-string (:render-to-string uix-adapter/adapter)})

;; ---- dispose lifecycle (Spec 006) -----------------------------------------

(deftest dispose-clears-hiccup-emitter        (suite/assert-dispose-clears-hiccup-emitter cfg))
(deftest dispose-clear-warn-idempotent        (suite/assert-clear-warn-idempotent-post-dispose cfg))
(deftest dispose-post-delegation-throws       (suite/assert-post-dispose-delegation-throws cfg))
(deftest dispose-idempotent-no-roots          (suite/assert-dispose-idempotent-no-roots cfg))
(deftest dispose-clears-sub-caches            (suite/assert-dispose-clears-sub-caches cfg))
(deftest dispose-walk-best-effort             (suite/assert-dispose-walk-best-effort cfg))

;; ---- source-coord DOM stamping (Spec 006) ---------------------------------

(deftest source-coord-annotates-dom-root      (suite/assert-source-coord-annotates-dom-root cfg))
(deftest source-coord-merges-with-attrs       (suite/assert-source-coord-merges-with-attrs cfg))
(deftest source-coord-user-supplied-wins      (suite/assert-source-coord-user-supplied-wins cfg))
(deftest source-coord-fragment-exempt         (suite/assert-source-coord-fragment-exempt cfg))
(deftest source-coord-format-shape            (suite/assert-source-coord-format-shape cfg))

;; ---- view-id (data-rf-view) stamping (Spec 006) ---------------------------

(deftest view-id-tags-dom-root                (suite/assert-view-id-tags-dom-root cfg))
(deftest view-id-fragment-exempt              (suite/assert-view-id-fragment-exempt cfg))
(deftest view-id-user-supplied-wins           (suite/assert-view-id-user-supplied-wins cfg))
(deftest wrap-view-injects-explicit-coords    (suite/assert-wrap-view-injects-explicit-coords cfg))

;; ---- frame-context corrupted (Spec 009) -----------------------------------

(deftest frame-context-corrupted              (suite/assert-frame-context-corrupted cfg))

;; ---- warn-once fires-once (Spec 006) --------------------------------------

(deftest warn-once-fires-once                 (suite/assert-warn-once-fires-once cfg))
(deftest warn-once-per-id-not-global          (suite/assert-warn-once-per-id-not-global cfg))

;; ---- write-after-destroy guard (rf2-ft2b) ---------------------------------

(deftest write-after-destroy-guard            (suite/assert-write-after-destroy-guard cfg))
