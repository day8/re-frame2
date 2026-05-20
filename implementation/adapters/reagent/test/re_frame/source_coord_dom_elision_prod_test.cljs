(ns re-frame.source-coord-dom-elision-prod-test
  "Per Spec 006 §Source-coord annotation production-elision contract
  (bead rf2-uwg5): under `:advanced` + `goog.DEBUG=false`, the
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
            [re-frame.adapter.reagent :as reagent-adapter]
            [re-frame.test-support :as test-support]))

(use-fixtures :each
  (test-support/make-reset-runtime-fixture
    {:adapter reagent-adapter/adapter}))

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
    (let [render (rf/view :rf.prod-elision-test/no-attr)
          out    (render)]
      (is (vector? out))
      (is (= :span (first out))
          "root tag preserved")
      (is (nil? (root-attr out))
          "NO data-rf2-source-coord on the rendered root — elision contract holds"))))

(deftest reg-view-with-attrs-has-no-source-coord-under-prod
  (testing "Even with an existing attrs map on the root, the wrapper does
            NOT inject data-rf2-source-coord under prod-mode. User attrs
            pass through; no extra key is added."
    (rf/reg-view* :rf.prod-elision-test/with-attrs
                  (fn [] [:div {:class "card" :id "x"} "body"]))
    (let [render (rf/view :rf.prod-elision-test/with-attrs)
          out    (render)
          attrs  (second out)]
      (is (= :div (first out)))
      (is (map? attrs))
      (is (= "card" (:class attrs)) "user :class preserved")
      (is (= "x"    (:id    attrs)) "user :id preserved")
      (is (nil? (:data-rf2-source-coord attrs))
          "NO data-rf2-source-coord merged in — elision contract holds"))))

(deftest reg-view-form-2-inner-output-has-no-source-coord-under-prod
  (testing "Form-2 render fns also elide annotation under prod-mode:
            the inner-fn output passes through unchanged."
    (rf/reg-view* :rf.prod-elision-test/form-2
      (fn []
        (fn inner-render []
          [:section.f2 "form-2 body"])))
    (let [wrapper (rf/view :rf.prod-elision-test/form-2)
          out     (wrapper)]
      (is (fn? out) "outer wrapper returns a fn (Form-2 shape preserved)")
      (let [inner-out (out)]
        (is (vector? inner-out) "inner fn returns hiccup")
        (is (= :section.f2 (first inner-out)))
        (is (nil? (root-attr inner-out))
            "NO data-rf2-source-coord on inner output — elision held through Form-2")))))

(deftest source-coord-literal-absent-from-rendered-output
  (testing "Defensive cross-check: scanning the rendered hiccup for the
            literal `data-rf2-source-coord` keyword finds nothing under
            prod-mode. Pair-tool consumers grep for this attribute; if
            it leaked it'd break the elision-bundle invariant."
    (rf/reg-view* :rf.prod-elision-test/literal-check
                  (fn [] [:p "scan me"]))
    (let [render (rf/view :rf.prod-elision-test/literal-check)
          out    (render)
          ;; Pretty-print and search for the literal key. Under dev-mode
          ;; this would find `:data-rf2-source-coord "..."` in the
          ;; attrs map; under prod-mode (here) it must not.
          flat   (pr-str out)]
      (is (not (clojure.string/includes? flat "data-rf2-source-coord"))
          "the literal data-rf2-source-coord does not appear in the
           rendered output under prod-mode — closure-fold contract holds"))))
