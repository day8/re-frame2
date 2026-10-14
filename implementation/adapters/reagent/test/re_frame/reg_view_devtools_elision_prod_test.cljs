(ns re-frame.reg-view-devtools-elision-prod-test
  "Per Spec 006 §React DevTools support: under `:advanced` +
  `goog.DEBUG=false`, the reg-view* wrapper's `displayName`
  assignment elides — the closure compiler constant-folds the
  surrounding `(when interop/debug-enabled? ...)` gate and the entire
  branch DCEs.

  This file compiles under `:browser-test-prod-elision` (the dedicated
  shadow-cljs build with `goog.DEBUG=false` + `:advanced`); the
  matching CLJS-runtime test (`reg_view_devtools_cljs_test.cljs`)
  exercises the same surface under dev-mode, where it also holds that
  the framework injects no `_jsx*` props.

  Naming convention: files ending in `-prod-test.cljs` are picked up
  ONLY by the `:browser-test-prod-elision` build."
  (:require [cljs.test :refer-macros [deftest is testing use-fixtures]]
            [re-frame.core :as rf]
            [re-frame.adapter.reagent :as rf.adapter.reagent]
            [re-frame.test-support :as rf.test-support]))

(use-fixtures :each
  (rf.test-support/make-reset-runtime-fixture
    {:adapter rf.adapter.reagent/adapter}))

(deftest reg-view-wrapped-fn-has-no-display-name-under-prod
  (testing "Per Spec 006 §React DevTools support production-elision:
            the reg-view wrapper's `displayName` assignment also rides
            the (when interop/debug-enabled? ...) gate, so the wrapped
            fn carries NO `displayName` under prod-mode."
    (rf/reg-view* :rf.prod-elision-test/dn-no-attr
                  (fn [] [:p "hi"]))
    ;; Reading a property off a missing view throws.
    (let [wrapped (rf/view :rf.prod-elision-test/dn-no-attr)]
      (is (nil? (.-displayName ^js wrapped))
          "wrapped fn carries no .displayName under prod-mode — elision held"))))
