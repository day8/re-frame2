(ns re-frame.view-id-attr-cljs-test
  "In debug builds the Reagent adapter tags every registered view's DOM root
  with `data-rf-view=\"<id>\"` beside `data-rf2-source-coord` (Spec 006 §View
  tagging contract): merged into an existing attrs map, never over a
  user-supplied value, and spliced onto a Form-2 view's inner output. The
  bare-root splice and the Fragment and interop exemptions are pinned in
  `re-frame.source-coord-dom-cljs-test`; production elision by the
  elision-probe build's `data-rf-view` sentinel."
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

;; ---- DOM-keyword root with attrs map --------------------------------------

(deftest tags-dom-root-with-existing-attrs
  (testing "a reg-view'd component with [:tag {:class …} children…] has
            :data-rf-view merged into the existing attrs map alongside
            user attrs (without disturbing them)"
    (rf/reg-view ^{:rf/id :rf.view-id-test/with-attrs} with-attrs-view []
      [:div {:class "card" :id "x"} "body"])
    (let [out   ((rf/view :rf.view-id-test/with-attrs))
          attrs (second out)]
      (is (= [:div {:class "card" :id "x" :data-rf-view ":rf.view-id-test/with-attrs"} true]
             [(first out) (select-keys attrs [:class :id :data-rf-view])
              (string? (:data-rf2-source-coord attrs))])
          "both attributes merged in beside the user's, which are untouched"))))

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
    (let [inner-out (((rf/view :rf.view-id-test/form-2)))]
      (is (= [:section.f2 ":rf.view-id-test/form-2" true]
             [(first inner-out) (root-view-attr inner-out) (string? (root-coord-attr inner-out))])
          "the outer fn's inner output carries both attributes, the view's being (str id)"))))
