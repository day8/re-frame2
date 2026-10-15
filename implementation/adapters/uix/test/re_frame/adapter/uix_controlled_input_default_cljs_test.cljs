(ns re-frame.adapter.uix-controlled-input-default-cljs-test
  "UIx controlled inputs default to React regardless of the other adapters on the classpath."
  (:require [cljs.test :refer-macros [deftest testing is]]
            [uix.compiler.input]
            [re-frame.adapter.uix]))

(def ^:private reagent-input-enabled-at-load?
  "Snapshot before another suite can overwrite the adapter load-time default."
  uix.compiler.input/*use-reagent-input-enabled?*)

(deftest react-controlled-input-is-the-adapters-default
  (testing "requiring the adapter is enough: a UIx `:input` is a plain React
           controlled input"
    (is (= [false false] [reagent-input-enabled-at-load? (uix.compiler.input/should-use-reagent-input?)])
        "the adapter's load left the var at false (read from the load-time
         snapshot, so no sibling suite's per-row pinning can mask its
         absence), and `should-use-reagent-input?` answers false")))
