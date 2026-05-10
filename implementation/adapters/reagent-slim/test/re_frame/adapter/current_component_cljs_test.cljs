(ns re-frame.adapter.current-component-cljs-test
  "Per rf2-wbnl — coverage for the `:adapter/current-component` late-bind
  hook installed by `re-frame.adapter.reagent-slim`.

  Background: before rf2-wbnl, `re-frame.views` statically `:require`d
  `reagent.core` and called `(reagent.core/current-component)` directly.
  Under the slim adapter (which ships `reagent2.core`) that read returned
  nil for slim-rendered components and silently dropped the React-context
  tier of the frame resolution chain — meaning `(rf/init! reagent-slim/adapter)`
  was structurally callable but not yet a drop-in functional swap for the
  bridge.

  Scope of this file (slim adapter):
    - Requiring `re-frame.adapter.reagent-slim` registers
      `reagent2.core/current-component` against the hook.
    - With no slim component in flight, the hook returns nil — matching
      the no-component shape views.cljs relies on.

  Note re cross-test order (bridge vs slim): both adapter ns's register
  the same hook key at ns-load. Whichever is loaded last wins. Under the
  in-tree shadow-cljs node-test build that loads both adapter trees, this
  test asserts the slim adapter's hook value at the moment its own ns
  loaded — by re-establishing the slim binding inside the test we make
  the assertion order-independent.

  ns ends in -cljs-test so shadow-cljs's :node-test build picks it up."
  (:require [cljs.test :refer-macros [deftest is testing]]
            [reagent2.core :as r]
            [re-frame.late-bind :as late-bind]
            ;; ns-load registers the :adapter/current-component hook.
            [re-frame.adapter.reagent-slim]))

(defn- with-slim-hook
  "Install the slim adapter's hook for the duration of `f`, restoring the
  pre-existing value afterwards. Lets this test assert the slim binding
  even when the bridge's ns-load registered a different reader after the
  slim ns loaded."
  [f]
  (let [original (late-bind/get-fn :adapter/current-component)]
    (try
      (late-bind/set-fn! :adapter/current-component r/current-component)
      (f)
      (finally
        (late-bind/set-fn! :adapter/current-component original)))))

(deftest slim-adapter-installs-hook
  (testing "requiring re-frame.adapter.reagent-slim registers a current-component hook"
    (with-slim-hook
      (fn []
        (is (some? (late-bind/get-fn :adapter/current-component))
            "the hook is installed")
        (is (identical? r/current-component
                        (late-bind/get-fn :adapter/current-component))
            "the hook points at reagent2.core/current-component (the slim build)")))))

(deftest slim-hook-returns-nil-outside-render
  (testing "calling the installed slim hook outside a render returns nil"
    (with-slim-hook
      (fn []
        (let [hook (late-bind/get-fn :adapter/current-component)]
          (is (nil? (hook))
              "no in-flight slim component → nil"))))))
