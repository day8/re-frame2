(ns re-frame.views-current-component-cljs-test
  "Per rf2-wbnl — coverage for the no-adapter / no-component fall-through
  semantics of `re-frame.views/current-frame`.

  Pre-rf2-wbnl, views.cljs statically `:require`d `reagent.core` and
  called `(reagent.core/current-component)` directly. Post-rf2-wbnl
  views.cljs reads the in-flight component through the
  `:adapter/current-component` late-bind hook, which the active adapter
  installs at ns-load time.

  Resolution chain (READER, per Spec 002 §Frame target resolution — the
  carried invariant, EP-0002):
    1. `rf.frame/*current-frame*` (dynamic var; set by `with-frame`)
    2. closest enclosing frame-provider via React context
    3. nil — NO `:rf/default` floor (the runtime never synthesises a default)

  This file exercises the no-adapter path: with the hook unset (or
  installed but with no in-flight component), tier 2 returns nil and the
  reader returns nil ('no scope'). A public frame-scoped operation turns
  that nil into a loud `:rf.error/no-frame-context` via
  `rf.frame/require-current-frame!`; the reader itself never repairs absence.
  This is exactly the headless / pre-init shape the runtime relies on.

  ns ends in -cljs-test so shadow-cljs's :node-test build picks it up."
  (:require [cljs.test :refer-macros [deftest is testing]]
            [re-frame.frame :as rf.frame]
            [re-frame.late-bind :as rf.late-bind]
            [re-frame.views :as rf.views]))

(defn- with-hook-as-nil
  "Run `f` with the named late-bind hook set to nil. Restores the
  original value after `f` returns or throws — keeps cross-test
  isolation intact (the in-tree shadow-cljs build loads both adapter
  trees, so the hook value at test time is non-nil; flipping it lets
  us assert the absent-hook contract)."
  [hook-key f]
  (let [original (rf.late-bind/get-fn hook-key)]
    (try
      (rf.late-bind/set-fn! hook-key nil)
      (f)
      (finally
        (rf.late-bind/set-fn! hook-key original)))))

(deftest current-frame-with-no-adapter-hook
  (testing "current-frame returns nil (no scope) when the hook is unset — no :rf/default floor"
    (with-hook-as-nil :adapter/current-component
      (fn []
        (is (nil? (rf.late-bind/get-fn :adapter/current-component))
            "precondition: the hook is unset")
        ;; No dynamic var is bound, no adapter hook is installed → the
        ;; reader returns nil (EP-0002: no :rf/default synthesis).
        (is (nil? (rf.views/current-frame))
            "with no dynamic-var binding and no adapter hook, current-frame returns nil")))))

(deftest current-frame-honours-dynamic-var-without-hook
  (testing "with the hook unset, the dynamic-var tier still wins"
    (with-hook-as-nil :adapter/current-component
      (fn []
        (binding [rf.frame/*current-frame* :test/dynamic-frame]
          (is (= :test/dynamic-frame (rf.views/current-frame))
              "tier 1 (dynamic var) is consulted before the (absent) hook"))))))

(deftest current-frame-tolerates-hook-returning-nil
  (testing "an installed hook that returns nil is equivalent to no hook"
    ;; A real adapter's `current-component` returns nil outside a render.
    ;; views.cljs must treat nil-from-hook the same as no-hook: skip the
    ;; React-context tier, return nil ('no scope') — no :rf/default floor.
    (let [original (rf.late-bind/get-fn :adapter/current-component)]
      (try
        (rf.late-bind/set-fn! :adapter/current-component (constantly nil))
        (is (nil? (rf.views/current-frame))
            "hook returning nil → reader returns nil (no synthesis)")
        (finally
          (rf.late-bind/set-fn! :adapter/current-component original))))))
