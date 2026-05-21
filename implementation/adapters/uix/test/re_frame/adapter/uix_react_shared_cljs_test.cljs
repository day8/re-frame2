(ns re-frame.adapter.uix-react-shared-cljs-test
  "UIx entry-point for the parameterised React-adapter suite
  (`re-frame.adapter.react-shared-suite`, rf2-sx77q).

  UIx and Helix wire their entire public surface out of the same
  `spine/make-react-spine` factory, so every spine-shared behaviour is
  asserted once in the shared suite and forwarded here with the UIx
  config. This file is the structural guarantee that UIx and Helix never
  drift on the shared contracts: a gap on one is a gap on both because
  there is exactly one suite source.

  Coverage forwarded: dispose MUSTs 1–4 + best-effort poison tolerance
  (G3), source-coord DOM stamping incl. the format-shape split (G2),
  view-id tagging, frame-context-corrupted (G4), warn-once fire-once +
  per-id (G5), and the write-after-destroy guard (rf2-sx77q); plus, per
  rf2-p4736, the rest of the UIx/Helix twin clusters: render-time parity
  (hot-reload re-register, anonymous render-key, wrap-view callable),
  reg-event metadata-interceptor warnings, render-to-string + late-bind
  chain wiring, the late-bind hook publication set + directory
  cross-check, chained clear-warn-once-caches!, the routing pipeline,
  the headless runtime slice, :rf.view/rendered, make-derived-value
  per-arity + watch-baseline, and managed-HTTP.

  The async *current-frame*-across-dispatch contract (rf2-l5q3) is
  forwarded from a dedicated entry pair carrying a map-form fixture —
  `uix_dispatch_frame_capture_cljs_test.cljs` — because async tests
  require a {:before :after} fixture so :after lands after `done`.

  Remaining UIx twins NOT folded here (DOM/browser — they define
  substrate-specific component vars via `defui`/`$`): `after_render_dom`
  and `use_subscribe_dom`. Splitting those into the shared suite needs a
  node-vs-browser component-element parameterisation; tracked separately.

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
   :producer-ns      're-frame.adapter.uix
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

;; ---- render-time parity (rf2-v1y7 / *_parity) -----------------------------

(deftest parity-view-re-register-rerender     (suite/assert-view-re-register-causes-rerender cfg))
(deftest parity-current-render-key-anon        (suite/assert-current-render-key-anonymous-fallback cfg))
(deftest parity-wrap-view-callable             (suite/assert-wrap-view-callable-dispatches-to-user-fn cfg))

;; ---- reg-event metadata-interceptor warnings (rf2-bbea / *_events) --------

(deftest events-warns-on-meta-interceptors    (suite/assert-reg-event-warns-on-meta-interceptors cfg))
(deftest events-positional-stays-silent        (suite/assert-reg-event-positional-interceptors-silent cfg))

;; ---- render-to-string + late-bind chain (rf2-gc5v9 / *_render_to_string) --

(deftest rts-throws-with-no-emitter            (suite/assert-render-to-string-throws-with-no-emitter cfg))
(deftest rts-returns-html-after-install        (suite/assert-render-to-string-returns-html-after-direct-install cfg))
(deftest rts-published-through-chain           (suite/assert-set-hiccup-emitter-published-through-chain cfg))

;; ---- late-bind hook publication set (rf2-rrwwy / *_late_bind_publication) --

(deftest late-bind-publishes-expected-set      (suite/assert-adapter-publishes-expected-hook-set cfg))
(deftest late-bind-cross-checked-directory      (suite/assert-adapter-hooks-cross-checked-against-directory cfg))

;; ---- chained clear-warn-once-caches! (rf2-e54wc / *_clear_warn_once_chain) -

(deftest clear-warn-chain-empties-cache        (suite/assert-chained-clear-warn-once-empties-cache cfg))
(deftest clear-warn-direct-resets-cache         (suite/assert-clear-warned-non-dom-roots-resets-directly cfg))

;; ---- routing pipeline (Spec 012 / *_routing) ------------------------------

(deftest routing-handle-url-change             (suite/assert-routing-handle-url-change cfg))
(deftest routing-multi-frame                    (suite/assert-routing-multi-frame cfg))

;; ---- headless runtime slice (*_runtime) -----------------------------------

(deftest runtime-dispatch-sync                 (suite/assert-dispatch-sync cfg))
(deftest runtime-sub-chain                      (suite/assert-sub-chain cfg))
(deftest runtime-with-frame                     (suite/assert-with-frame-binds-current-frame cfg))
(deftest runtime-bound-fn                        (suite/assert-bound-fn-captures-frame cfg))
(deftest runtime-multi-frame-isolation          (suite/assert-multi-frame-state-isolation cfg))
(deftest runtime-reactive-sub                    (suite/assert-reactive-sub-tracks-changes cfg))
(deftest runtime-sub-hot-reload                  (suite/assert-sub-hot-reload cfg))
(deftest runtime-machine-transition              (suite/assert-machine-transition cfg))
(deftest runtime-sub-exception-recovers          (suite/assert-sub-exception-recovers-to-nil cfg))

;; ---- :rf.view/rendered op (rf2-25zo2 / *_view_rendered_op) -----------------

(deftest view-rendered-fires-on-render         (suite/assert-rf-view-rendered-fires-on-render cfg))
(deftest view-rendered-attribution-in-cascade   (suite/assert-rf-view-rendered-attribution-in-cascade cfg))

;; ---- make-derived-value per-arity (rf2-eoy63 / *_make_derived_value_arity) -

(deftest derived-value-arities                 (suite/assert-derived-value-arities cfg))

;; ---- derived-value watch-baseline (rf2-66hb / *_derived_value_baseline) ----

(deftest derived-baseline-projections          (suite/assert-derived-baseline-projections cfg))
(deftest derived-baseline-sequence              (suite/assert-derived-baseline-sequence cfg))
(deftest derived-baseline-multi-source          (suite/assert-derived-baseline-multi-source cfg))

;; ---- managed HTTP (Spec 014 / *_http_managed) -----------------------------

(deftest http-canned-success-default-reply     (suite/assert-http-canned-success-default-reply cfg))
(deftest http-canned-failure-on-failure         (suite/assert-http-canned-failure-on-failure cfg))
(deftest http-canned-success-on-success         (suite/assert-http-canned-success-on-success cfg))
(deftest http-silenced-reply                     (suite/assert-http-silenced-reply cfg))
(deftest http-with-managed-request-stubs         (suite/assert-http-with-managed-request-stubs cfg))
(deftest http-with-managed-request-stubs-failure (suite/assert-http-with-managed-request-stubs-failure cfg))
(deftest http-multi-frame-reply-isolation        (suite/assert-http-multi-frame-reply-isolation cfg))

;; ---- Cross-Spec interactions (headless subset / *_cross_spec) --------------

(deftest xspec-frame-destroy-active-machines   (suite/assert-xspec-frame-destroy-with-active-machines cfg))
(deftest xspec-machine-microstep-subscribe      (suite/assert-xspec-machine-microstep-subscribe cfg))
(deftest xspec-boot-order-adapter-ready          (suite/assert-xspec-boot-order-adapter-ready cfg))
(deftest xspec-machines-under-ssr                (suite/assert-xspec-machines-under-ssr cfg))
(deftest xspec-route-not-found-ssr               (suite/assert-xspec-route-not-found-ssr cfg))
(deftest xspec-headless-frame-resolution         (suite/assert-xspec-headless-frame-resolution-chain cfg))
(deftest xspec-machine-action-throws             (suite/assert-xspec-machine-action-throws cfg))
(deftest xspec-machine-fx-handler-throws         (suite/assert-xspec-machine-fx-handler-throws cfg))
(deftest xspec-hot-reload-machine-action         (suite/assert-xspec-hot-reload-machine-action cfg))
(deftest xspec-dispatch-sync-from-handler        (suite/assert-xspec-dispatch-sync-from-handler-raises cfg))
(deftest xspec-time-travel-revert                (suite/assert-xspec-time-travel-revert cfg))
(deftest xspec-server-error-projection           (suite/assert-xspec-server-error-projection cfg))
(deftest xspec-hot-reload-sub-mid-cascade        (suite/assert-xspec-hot-reload-sub-mid-cascade cfg))
(deftest xspec-portable-story-fx-override        (suite/assert-xspec-portable-story-fx-override cfg))
(deftest xspec-adapter-already-installed         (suite/assert-xspec-adapter-already-installed cfg))
