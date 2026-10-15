(ns re-frame.adapter.uix-after-render-dom-cljs-test
  "UIx entry point for the shared after-render and hydration contracts."
  (:require [cljs.test :refer-macros [deftest use-fixtures]]
            [uix.core :refer-macros [defui $]]
            [re-frame.adapter.uix :as rf.adapter.uix]
            [re-frame.adapter.react-shared-suite :as rf.adapter.react-shared-suite]
            [re-frame.test-support :as rf.test-support]))

(use-fixtures :each
  (rf.test-support/make-reset-runtime-fixture
    {:adapter rf.adapter.uix/adapter :async? true}))

(defui Probe []
  ($ :div "probe"))

(def ^:private cfg
  {:adapter       rf.adapter.uix/adapter
   :name          "UIx"
   :probe-element (fn [] ($ Probe))})

(deftest after-render-hook-wired-under-uix
  (rf.adapter.react-shared-suite/assert-after-render-hook-wired cfg))

(deftest after-render-runs-callback-after-next-commit-uix
  (rf.adapter.react-shared-suite/assert-after-render-runs-after-commit cfg))

(deftest after-render-throw-is-isolated-and-surfaced-uix
  (rf.adapter.react-shared-suite/assert-after-render-throw-is-isolated-and-surfaced cfg))

(deftest after-render-fires-on-native-mount-uix
  (rf.adapter.react-shared-suite/assert-after-render-fires-on-native-mount cfg))

(deftest after-render-observes-commit-synchronously-on-native-first-call-uix
  (rf.adapter.react-shared-suite/assert-after-render-observes-commit-synchronously-on-native-first-call cfg))

(deftest render-hydrate-branch-mounts-without-remount-uix
  (rf.adapter.react-shared-suite/assert-render-hydrate-branch-mounts-without-remount cfg))

(deftest native-hydration-mismatch-surfaces-diagnostic-uix
  (rf.adapter.react-shared-suite/assert-native-hydration-mismatch-surfaces-diagnostic cfg))

(deftest native-hydration-window-bounds-emit-uix
  (rf.adapter.react-shared-suite/assert-native-hydration-window-bounds-emit cfg))
