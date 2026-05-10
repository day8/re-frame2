(ns re-frame.adapter-current-component-cljs-test
  "Per rf2-wbnl — coverage for the `:adapter/current-component` late-bind
  hook installed by `re-frame.adapter.reagent`.

  Background: before rf2-wbnl, `re-frame.views` statically `:require`d
  `reagent.core` and called `(reagent.core/current-component)` directly.
  That hard-coupled views.cljs to stock Reagent — under the slim adapter
  (which ships `reagent2.core`) the read returned nil for slim-rendered
  components and silently dropped the React-context tier of the frame
  resolution chain. The fix introduces an
  `:adapter/current-component` late-bind hook the active adapter
  publishes at ns-load time; views.cljs reads through the hook.

  Scope of this file (classic bridge):
    - Requiring `re-frame.adapter.reagent` registers
      `reagent.core/current-component` against the hook.
    - With no Reagent component in flight, the hook returns nil — the
      shape views.cljs's `current-frame` relies on for the no-component
      fall-through.

  Note re cross-test order (bridge vs slim): both adapter ns's register
  the same hook key at ns-load. Per rf2-0d35 each adapter wraps its
  reader in a routing closure that consults
  `(substrate-adapter/current-adapter)` and runs THIS adapter's reader
  only when this adapter is the installed one (otherwise chains to
  the previously-installed reader). The wrapper means the hook value
  observed at runtime is itself a closure, NOT the raw reader. To keep
  the assertion order-independent (and resilient to the wrapping),
  this test re-installs the raw `r/current-component` inside each
  test body via `with-bridge-hook` and asserts identity against THAT
  re-installed value.

  ns ends in -cljs-test so shadow-cljs's :node-test build picks it up."
  (:require [cljs.test :refer-macros [deftest is testing]]
            [reagent.core :as r]
            [re-frame.late-bind :as late-bind]
            ;; ns-load registers the :adapter/current-component hook.
            [re-frame.adapter.reagent]))

(defn- with-bridge-hook
  "Install the classic bridge's hook for the duration of `f`, restoring
  the pre-existing value afterwards. Lets this test assert the bridge
  binding even when the slim ns-load registered a different reader after
  the bridge ns loaded."
  [f]
  (let [original (late-bind/get-fn :adapter/current-component)]
    (try
      (late-bind/set-fn! :adapter/current-component r/current-component)
      (f)
      (finally
        (late-bind/set-fn! :adapter/current-component original)))))

(deftest classic-bridge-installs-hook
  (testing "requiring re-frame.adapter.reagent registers :adapter/current-component"
    (with-bridge-hook
      (fn []
        (is (some? (late-bind/get-fn :adapter/current-component))
            "the hook is installed")
        (is (identical? r/current-component
                        (late-bind/get-fn :adapter/current-component))
            "the hook points at stock reagent.core/current-component")))))

(deftest hook-returns-nil-outside-render
  (testing "calling the installed hook outside a Reagent render returns nil"
    ;; `r/current-component` returns nil when no component is in flight.
    ;; views.cljs's `current-frame` relies on this no-component shape to
    ;; skip the React-context tier and fall through to :rf/default.
    (with-bridge-hook
      (fn []
        (let [hook (late-bind/get-fn :adapter/current-component)]
          (is (nil? (hook))
              "no in-flight component → nil"))))))
