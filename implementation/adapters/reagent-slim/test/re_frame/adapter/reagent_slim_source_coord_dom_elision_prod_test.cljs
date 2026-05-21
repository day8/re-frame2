(ns re-frame.adapter.reagent-slim-source-coord-dom-elision-prod-test
  "Per Spec 006 §Source-coord annotation production-elision contract
  (beads rf2-uwg5 / rf2-sx77q G1): under `:advanced` + `goog.DEBUG=false`,
  the reagent-slim adapter's source-coord injection branch elides. A
  registered view's rendered hiccup carries NO data-rf2-source-coord
  attribute under prod-mode.

  WHY THIS FILE EXISTS (rf2-sx77q G1, the sharpest slim gap). slim is
  positioned as a drop-in Reagent replacement and ships the SAME
  `interop/debug-enabled?` stamping gate as the Reagent bridge — yet
  before this file there was zero proof the slim path DCEs under
  `:advanced` + `goog.DEBUG=false`. The Reagent bridge has
  `re-frame.source-coord-dom-elision-prod-test`; this is the slim
  sibling. Like the bridge, slim renders hiccup (not React elements), so
  the assertions inspect the hiccup attrs map directly.

  The JVM tests use `with-redefs` on the symbol — useful but doesn't
  prove the genuine closure-fold contract. This file compiles under
  `:browser-test-prod-elision` (the dedicated shadow-cljs build with
  `goog.DEBUG=false` + `:advanced`) so the gate is constant-folded.

  Naming convention: files ending in `-elision-prod-test.cljs` are picked
  up ONLY by the `:browser-test-prod-elision` build (the build's
  `:ns-regexp` is `-elision-prod-test$`). The default `:browser-test` and
  `:node-test` builds use regexes `-dom-cljs-test$` and `cljs-test$` and
  do NOT pick up this file. Running this file under `goog.DEBUG=true`
  would FAIL — the assertion is 'no annotation', which is only true under
  prod-mode."
  (:require [cljs.test :refer-macros [deftest is testing use-fixtures]]
            [clojure.string :as str]
            [re-frame.core :as rf]
            [re-frame.adapter.reagent-slim :as reagent-slim-adapter]
            [re-frame.test-support :as test-support]))

(use-fixtures :each
  (test-support/make-reset-runtime-fixture
    {:adapter reagent-slim-adapter/adapter}))

(defn- root-attr
  "Pull the :data-rf2-source-coord value from the root attrs map of a
  hiccup vector, or nil if no attrs map or no key. Defensively returns
  nil (not false) on every branch so the test assertions can use `nil?`."
  [hiccup]
  (when (and (vector? hiccup)
             (map? (second hiccup)))
    (:data-rf2-source-coord (second hiccup))))

(defn- root-view-attr
  "Pull the :data-rf-view value from the root attrs map of a hiccup
  vector, or nil."
  [hiccup]
  (when (and (vector? hiccup)
             (map? (second hiccup)))
    (:data-rf-view (second hiccup))))

;; ---- annotation elides under :advanced + goog.DEBUG=false ----------------

(deftest reg-view-output-has-no-source-coord-under-prod-slim
  (testing "Per Spec 006 §Source-coord annotation production-elision
            (rf2-sx77q G1, slim side): a registered view's rendered hiccup
            carries NO :data-rf2-source-coord attribute under :advanced +
            goog.DEBUG=false. The Closure compiler DCEs the entire
            (when interop/debug-enabled? ...) branch."
    (rf/reg-view* :rf.slim-prod-elision-test/no-attr
                  (fn [] [:span "hi"]))
    (let [render (rf/view :rf.slim-prod-elision-test/no-attr)
          out    (render)]
      (is (vector? out))
      (is (= :span (first out)) "root tag preserved")
      (is (nil? (root-attr out))
          "NO :data-rf2-source-coord on the rendered root — elision contract holds")
      (is (nil? (root-view-attr out))
          "NO :data-rf-view on the rendered root — view-id tagging rides the
           same interop/debug-enabled? gate and elides too"))))

(deftest reg-view-with-attrs-has-no-source-coord-under-prod-slim
  (testing "Even with an existing attrs map on the root, the wrapper does
            NOT inject :data-rf2-source-coord under prod-mode. User attrs
            pass through; no extra key is added."
    (rf/reg-view* :rf.slim-prod-elision-test/with-attrs
                  (fn [] [:div {:class "card" :id "x"} "body"]))
    (let [render (rf/view :rf.slim-prod-elision-test/with-attrs)
          out    (render)
          attrs  (second out)]
      (is (= :div (first out)))
      (is (map? attrs))
      (is (= "card" (:class attrs)) "user :class preserved")
      (is (= "x"    (:id    attrs)) "user :id preserved")
      (is (nil? (:data-rf2-source-coord attrs))
          "NO :data-rf2-source-coord merged in — elision contract holds")
      (is (nil? (:data-rf-view attrs))
          "NO :data-rf-view merged in — view-id tagging elides on the same gate"))))

(deftest reg-view-form-2-inner-output-has-no-source-coord-under-prod-slim
  (testing "Form-2 render fns also elide annotation under prod-mode: the
            inner-fn output passes through unchanged."
    (rf/reg-view* :rf.slim-prod-elision-test/form-2
      (fn []
        (fn inner-render []
          [:section.f2 "form-2 body"])))
    (let [wrapper (rf/view :rf.slim-prod-elision-test/form-2)
          out     (wrapper)]
      (is (fn? out) "outer wrapper returns a fn (Form-2 shape preserved)")
      (let [inner-out (out)]
        (is (vector? inner-out) "inner fn returns hiccup")
        (is (= :section.f2 (first inner-out)))
        (is (nil? (root-attr inner-out))
            "NO :data-rf2-source-coord on inner output — elision held through Form-2")))))

(deftest source-coord-literal-absent-from-rendered-output-slim
  (testing "Defensive cross-check: scanning the rendered hiccup for the
            literal `data-rf2-source-coord` keyword finds nothing under
            prod-mode. Pair-tool consumers grep for this attribute; if it
            leaked it'd break the elision-bundle invariant."
    (rf/reg-view* :rf.slim-prod-elision-test/literal-check
                  (fn [] [:p "scan me"]))
    (let [render (rf/view :rf.slim-prod-elision-test/literal-check)
          out    (render)
          flat   (pr-str out)]
      (is (not (str/includes? flat "data-rf2-source-coord"))
          "the literal data-rf2-source-coord does not appear in the
           rendered output under prod-mode — closure-fold contract holds"))))
