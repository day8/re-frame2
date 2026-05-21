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
            [re-frame.adapter.uix :as uix-adapter]
            [re-frame.adapter.react-shared-suite :as suite]
            [re-frame.test-support :as test-support]))

(use-fixtures :each
  (test-support/make-reset-runtime-fixture
    {:adapter uix-adapter/adapter}))

(defui Probe []
  ;; Bare UIx component — the rf2-334d9 sentinel is injected by the
  ;; spine's `make-render`, not by user code.
  ($ :div "probe"))

(def ^:private cfg
  {:adapter       uix-adapter/adapter
   :name          "UIx"
   :probe-element (fn [] ($ Probe))})

(deftest after-render-hook-wired-under-uix
  (suite/assert-after-render-hook-wired cfg))

(deftest after-render-runs-callback-after-next-commit-uix
  (suite/assert-after-render-runs-after-commit cfg))
