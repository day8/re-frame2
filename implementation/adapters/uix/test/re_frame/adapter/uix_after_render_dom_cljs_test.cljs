(ns re-frame.adapter.uix-after-render-dom-cljs-test
  "UIx DOM/browser entry-point for the after-render twin of the
  parameterised React-adapter suite (`re-frame.adapter.react-shared-suite`).

  rf2-5or96 folded the UIx/Helix after-render twins (rf2-334d9) into the
  shared suite. UIx defines its probe component with `uix.core/defui` +
  `$` — a substrate macro the suite cannot mint at runtime — so the probe
  var is built HERE and handed to the suite via the cfg map's
  `:probe-element` thunk; the orchestration + every assertion lives once
  in the suite (Approach A: components passed in as elements).

  Coverage forwarded (rf2-334d9): the ns-load smoke (node-safe — runs
  under :node-test) that `interop/after-render` is wired and returns nil,
  plus the act-driven mount/schedule/drain behaviour (browser-only).

  ns ends in `-dom-cljs-test` so shadow-cljs's `:browser-test`
  (ns-regexp `-dom-cljs-test$`) discovers it for the real DOM assertions;
  `:node-test`'s `cljs-test$` regex also matches, running the node-safe
  smoke + the self-gated behaviour no-op."
  (:require [cljs.test :refer-macros [deftest use-fixtures]]
            [uix.core :as uix :refer-macros [defui $]]
            [re-frame.adapter.uix :as rf.adapter.uix]
            [re-frame.adapter.react-shared-suite :as rf.adapter.react-shared-suite]
            [re-frame.test-support :as rf.test-support]))

;; `:async? true` returns the map-form fixture required by the async
;; native-hydration-mismatch DOM test below (rf2-qfz65); a plain-fn fixture
;; aborts an async cljs.test with "Async tests require fixtures to be specified
;; as maps". The map form runs the file's synchronous tests identically.
(use-fixtures :each
  (rf.test-support/make-reset-runtime-fixture
    {:adapter rf.adapter.uix/adapter :async? true}))

(defui Probe []
  ;; Bare UIx component — the rf2-334d9 sentinel is injected by the
  ;; spine's `make-render`, not by user code.
  ($ :div "probe"))

(def ^:private cfg
  {:adapter       rf.adapter.uix/adapter
   :name          "UIx"
   :probe-element (fn [] ($ Probe))
   ;; rf2-b6nm5 — the canonical test-flush Var for the parity shape pin.
   :flush-views!  rf.adapter.uix/flush-views!})

(deftest after-render-hook-wired-under-uix
  (rf.adapter.react-shared-suite/assert-after-render-hook-wired cfg))

(deftest after-render-runs-callback-after-next-commit-uix
  (rf.adapter.react-shared-suite/assert-after-render-runs-after-commit cfg))

;; rf2-t0x90 — after-render parity on the NATIVE-mount path (raw
;; createRoot + .render, the documented boot idiom that bypasses the
;; spine's Fragment-wrap sentinel). The singleton driver root restores
;; post-commit timing previously only the :render-slot path got.
(deftest after-render-fires-on-native-mount-uix
  (rf.adapter.react-shared-suite/assert-after-render-fires-on-native-mount cfg))

;; rf2-he7se finding 3 — the native after-render driver-root setter is
;; installed from a LAYOUT effect (flushSync always flushes layout effects
;; synchronously), so the first native after-render observes the committed
;; app state synchronously via the post-commit layout-effect drain rather
;; than the (version-dependent) queueMicrotask fallback.
(deftest after-render-observes-commit-synchronously-on-native-first-call-uix
  (rf.adapter.react-shared-suite/assert-after-render-observes-commit-synchronously-on-native-first-call cfg))

;; rf2-b6nm5 — flush-views! is surfaced from the adapter ns with the
;; canonical nil-return shape (Decision 6), converged across all four
;; substrates. Node-safe shape pin.
(deftest flush-views-canonical-shape-uix
  (rf.adapter.react-shared-suite/assert-flush-views-canonical-shape cfg))

;; rf2-ee38b.1 — the spine `make-render` :hydrate? true branch
;; (hydrateRoot) had no React-hook coverage; this closes it.
(deftest render-hydrate-branch-mounts-without-remount-uix
  (rf.adapter.react-shared-suite/assert-render-hydrate-branch-mounts-without-remount cfg))

;; rf2-qfz65 — a hydrating native root that adopts DIVERGENT server markup now
;; surfaces the framework :rf.ssr/hydration-mismatch diagnostic (composed
;; onRecoverableError), a host :on-recoverable-error still fires, and a clean
;; adoption stays silent. Browser-only mounted DOM proof.
(deftest native-hydration-mismatch-surfaces-diagnostic-uix
  (rf.adapter.react-shared-suite/assert-native-hydration-mismatch-surfaces-diagnostic cfg))

;; rf2-qfz65 residual — the native reporter's framework emit is bounded to the
;; hydration ADOPTION WINDOW: after the window closes (the adoption-window-closer
;; clears the flag on the hydration commit) a later recoverable error no longer
;; emits a FALSE :rf.ssr/hydration-mismatch, but the host callback still fires.
(deftest native-hydration-window-bounds-emit-uix
  (rf.adapter.react-shared-suite/assert-native-hydration-window-bounds-emit cfg))
