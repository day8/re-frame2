(ns re-frame.view-id-attr-cljs-test
  "Per Spec 006 §View tagging contract: when
  `interop/debug-enabled?` is true, the Reagent substrate adapter MUST
  also inject `data-rf-view=\"<id>\"` on the rendered root DOM element
  of every registered view — ALONGSIDE `data-rf2-source-coord`. The
  view-id attribute is the runtime view-id capture surface, read
  forward (id → rendered root) and in reverse (node → producing view).

  Coverage (mirrors `source_coord_dom_cljs_test.cljs` shape):

    - DOM-keyword root WITH an existing attrs map: both attributes are
      merged in alongside the user's attrs.
    - User-supplied data-rf-view wins (don't overwrite).
    - Form-2 (render-fn returns a fn): inner-fn output gets BOTH attrs,
      spliced into a root with no attrs map, the view attribute's value
      being `(str id)` — i.e. `\":ns/sym\"`. A top-level root with no
      attrs map takes the same splice; `source_coord_dom_cljs_test.cljs`
      pins its coord.

  React Fragment and `[:> Cmp …]` interop roots are exempt from both
  attributes by the same branch; `re-frame.source-coord-dom-cljs-test`
  pins both exemptions.

  Production elision (interop/debug-enabled? = false at build time) is
  verified separately by the elision-probe build via the
  `data-rf-view` sentinel registered in `scripts/check-elision.cjs`."
  (:require [cljs.test :refer-macros [deftest is testing use-fixtures]]
            [re-frame.core :as rf]
            [re-frame.adapter.reagent :as rf.adapter.reagent]
            [re-frame.test-support :as rf.test-support]
            [re-frame.views]))

(use-fixtures :each
  (rf.test-support/make-reset-runtime-fixture
    {:adapter rf.adapter.reagent/adapter}))

;; ---- helpers ---------------------------------------------------------------

(defn- root-view-attr
  "Pull the :data-rf-view value from the root attrs map of a hiccup
  vector, if any."
  [hiccup]
  (and (vector? hiccup)
       (map? (second hiccup))
       (:data-rf-view (second hiccup))))

(defn- root-coord-attr
  "Pull the :data-rf2-source-coord value from the root attrs map of a
  hiccup vector, if any."
  [hiccup]
  (and (vector? hiccup)
       (map? (second hiccup))
       (:data-rf2-source-coord (second hiccup))))

;; ---- DOM-keyword root, no existing attrs map ------------------------------

;; ---- DOM-keyword root with attrs map --------------------------------------

(deftest tags-dom-root-with-existing-attrs
  (testing "a reg-view'd component with [:tag {:class …} children…] has
            :data-rf-view merged into the existing attrs map alongside
            user attrs (without disturbing them)"
    (rf/reg-view ^{:rf/id :rf.view-id-test/with-attrs} with-attrs-view []
      [:div {:class "card" :id "x"} "body"])
    (let [render (rf/view :rf.view-id-test/with-attrs)
          out    (render)
          attrs  (second out)]
      (is (vector? out))
      (is (= :div (first out)))
      (is (map? attrs))
      (is (= "card" (:class attrs)) "user :class preserved")
      (is (= "x"    (:id    attrs)) "user :id preserved")
      (is (= ":rf.view-id-test/with-attrs" (:data-rf-view attrs))
          ":data-rf-view merged in alongside user attrs")
      (is (string? (:data-rf2-source-coord attrs))
          ":data-rf2-source-coord still merged in (parity)"))))

;; ---- user-supplied data-rf-view wins --------------------------------------

(deftest user-supplied-data-rf-view-wins
  (testing "a render-fn that already set :data-rf-view is not overwritten
            — composability with hand-stamped tools (e.g. for tests that
            inject a synthetic view-id)"
    (rf/reg-view ^{:rf/id :rf.view-id-test/user-stamped} user-stamped-view []
      [:p {:data-rf-view "stamped:by-user"} "ok"])
    (let [render (rf/view :rf.view-id-test/user-stamped)
          out    (render)]
      (is (= "stamped:by-user" (:data-rf-view (second out)))
          "user-supplied attribute survives the wrapper's merge"))))

;; ---- Form-2: render-fn returns a fn --------------------------------------

(deftest tags-form-2-inner-output
  (testing "Form-2 render-fns return a fn; the wrapper recurses on the
            inner fn's output so :data-rf-view lands on the eventual
            rendered DOM root, not the outer fn"
    (rf/reg-view* :rf.view-id-test/form-2
      (fn []
        (fn inner-render []
          [:section.f2 "form-2 body"])))
    (let [wrapper (rf/view :rf.view-id-test/form-2)
          out     (wrapper)]
      (is (fn? out) "outer wrapper returns a fn (Form-2 shape preserved)")
      (let [inner-out (out)]
        (is (vector? inner-out) "inner fn returns hiccup")
        (is (= :section.f2 (first inner-out)))
        (is (= ":rf.view-id-test/form-2" (root-view-attr inner-out))
            ":data-rf-view landed on the inner output's root")
        (is (string? (root-coord-attr inner-out))
            ":data-rf2-source-coord landed on the inner output's root too")))))
