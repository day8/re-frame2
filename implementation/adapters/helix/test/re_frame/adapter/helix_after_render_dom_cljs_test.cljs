(ns re-frame.adapter.helix-after-render-dom-cljs-test
  "Helix DOM/browser entry-point for the after-render twin of the
  parameterised React-adapter suite (`re-frame.adapter.react-shared-suite`).

  rf2-5or96 folded the UIx/Helix after-render twins (rf2-334d9) into the
  shared suite. Helix defines its probe component with `helix.core/defnc`
  + `helix.dom` — substrate macros the suite cannot mint at runtime — so
  the probe var is built HERE and handed to the suite via the cfg map's
  `:probe-element` thunk; the orchestration + every assertion lives once
  in the suite (Approach A: components passed in as elements). A gap on
  UIx is a gap on Helix by construction.

  Coverage forwarded (rf2-334d9): the ns-load smoke (node-safe — runs
  under :node-test) that `interop/after-render` is wired and returns nil,
  plus the act-driven mount/schedule/drain behaviour (browser-only).

  ns ends in `-dom-cljs-test` so shadow-cljs's `:browser-test`
  (ns-regexp `-dom-cljs-test$`) discovers it for the real DOM assertions;
  `:node-test`'s `cljs-test$` regex also matches, running the node-safe
  smoke + the self-gated behaviour no-op."
  (:require [cljs.test :refer-macros [deftest use-fixtures]]
            [helix.core :refer-macros [$ defnc]]
            [helix.dom  :as d]
            [re-frame.adapter.helix :as helix-adapter]
            [re-frame.adapter.react-shared-suite :as suite]
            [re-frame.test-support :as test-support]))

(use-fixtures :each
  (test-support/make-reset-runtime-fixture
    {:adapter helix-adapter/adapter}))

(defnc Probe []
  ;; Bare Helix component — the rf2-334d9 sentinel is injected by the
  ;; spine's `make-render`, not by user code.
  (d/div "probe"))

(def ^:private cfg
  {:adapter       helix-adapter/adapter
   :name          "Helix"
   :probe-element (fn [] ($ Probe))
   ;; rf2-b6nm5 — the canonical test-flush Var for the parity shape pin.
   :flush-views!  helix-adapter/flush-views!})

(deftest after-render-hook-wired-under-helix
  (suite/assert-after-render-hook-wired cfg))

(deftest after-render-runs-callback-after-next-commit-helix
  (suite/assert-after-render-runs-after-commit cfg))

;; rf2-t0x90 — after-render parity on the NATIVE-mount path (raw
;; createRoot + .render, the documented boot idiom that bypasses the
;; spine's Fragment-wrap sentinel). The singleton driver root restores
;; post-commit timing previously only the :render-slot path got.
(deftest after-render-fires-on-native-mount-helix
  (suite/assert-after-render-fires-on-native-mount cfg))

;; rf2-he7se finding 3 — the native after-render driver-root setter is
;; installed from a LAYOUT effect (flushSync always flushes layout effects
;; synchronously), so the first native after-render observes the committed
;; app state synchronously via the post-commit layout-effect drain rather
;; than the (version-dependent) queueMicrotask fallback.
(deftest after-render-observes-commit-synchronously-on-native-first-call-helix
  (suite/assert-after-render-observes-commit-synchronously-on-native-first-call cfg))

;; rf2-b6nm5 — flush-views! is surfaced from the adapter ns with the
;; canonical nil-return shape (Decision 6), converged across all four
;; substrates. Node-safe shape pin.
(deftest flush-views-canonical-shape-helix
  (suite/assert-flush-views-canonical-shape cfg))

;; rf2-ee38b.1 — the spine `make-render` :hydrate? true branch
;; (hydrateRoot) had no React-hook coverage; close it for UIx + Helix.
(deftest render-hydrate-branch-mounts-without-remount-helix
  (suite/assert-render-hydrate-branch-mounts-without-remount cfg))
