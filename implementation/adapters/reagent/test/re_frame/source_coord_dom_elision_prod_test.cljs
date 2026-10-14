(ns re-frame.source-coord-dom-elision-prod-test
  "Per Spec 006 §Source-coord annotation production-elision contract:
  under `:advanced` + `goog.DEBUG=false`, the
  reg-view* wrapper's `data-rf2-source-coord` injection branch elides.
  A registered view's rendered output carries NO source-coord attribute
  under prod-mode.

  The JVM tests use `with-redefs` on the symbol — useful but doesn't
  prove the genuine closure-fold contract. This file compiles under
  `:browser-test-prod-elision` (the dedicated shadow-cljs build with
  `goog.DEBUG=false` + `:advanced`) so the gate is constant-folded.

  Under that compile:
    - `views/reg-view*` registers the wrapped fn.
    - The wrapper's body still calls render-fn (rendering is not gated;
      only the source-coord injection branch is).
    - The `(when interop/debug-enabled? ...)` guard around the
      inject-source-coord-attr call DCEs to its else-branch (which
      returns the raw render-fn output unchanged).

  Naming convention: files ending in `-prod-test.cljs` are picked up
  ONLY by the `:browser-test-prod-elision` build (the build's
  `:ns-regexp` is `-prod-test$`). The default `:browser-test` and
  `:node-test` builds use regexes `-cljs-test$` and `cljs-test$` and
  do NOT pick up these files. Running this file under
  `goog.DEBUG=true` would FAIL — the assertion is 'no annotation',
  which is only true under prod-mode."
  (:require [cljs.test :refer-macros [deftest is testing use-fixtures]]
            [re-frame.core :as rf]
            [re-frame.adapter.reagent :as rf.adapter.reagent]
            [re-frame.test-support :as rf.test-support]))

(use-fixtures :each
  (rf.test-support/make-reset-runtime-fixture
    {:adapter rf.adapter.reagent/adapter}))

(defn- root-attr
  "Pull the :data-rf2-source-coord value from the root attrs map of a
  hiccup vector, or nil if no attrs map or no key. Defensively returns
  nil (not false) on every branch so the test assertions can use `nil?`."
  [hiccup]
  (when (and (vector? hiccup)
             (map? (second hiccup)))
    (:data-rf2-source-coord (second hiccup))))

;; ---- annotation elides under :advanced + goog.DEBUG=false ----------------

(deftest reg-view-output-has-no-source-coord-under-prod
  (testing "Per Spec 006 §Source-coord annotation production-elision: a
            registered view's rendered output carries NO
            data-rf2-source-coord attribute under :advanced +
            goog.DEBUG=false. The Closure compiler DCEs the entire
            (when interop/debug-enabled? ...) branch."
    (rf/reg-view* :rf.prod-elision-test/no-attr
                  (fn [] [:span "hi"]))
    (let [out ((rf/view :rf.prod-elision-test/no-attr))]
      (is (= [true :span nil] [(vector? out) (first out) (root-attr out)])
          "root tag preserved, and NO data-rf2-source-coord on the rendered root — elision contract holds"))))
