(ns re-frame.adapter.reagent-slim-view-id-attr-cljs-test
  "Under the slim adapter, a registered view's DOM root carries
  `data-rf-view=\"<id>\"` beside `data-rf2-source-coord` in debug builds (Spec
  006 §View tagging contract): spliced into a bare root, merged into an
  existing attrs map, never over a user-supplied value, and onto a Form-2
  view's inner output. The Reagent twin is `re-frame.view-id-attr-cljs-test`;
  the Fragment exemption is pinned in
  `re-frame.adapter.reagent-slim-source-coord-dom-cljs-test`."
  (:require [cljs.test :refer-macros [deftest is testing use-fixtures]]
            [re-frame.core :as rf]
            [re-frame.adapter.reagent-slim :as rf.adapter.reagent-slim]
            [re-frame.test-support :as rf.test-support]
            [re-frame.views]))

(use-fixtures :each
  (rf.test-support/make-reset-runtime-fixture
    {:adapter rf.adapter.reagent-slim/adapter}))

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

(deftest tags-dom-root-without-attrs
  (testing "a reg-view'd component with [:tag children…] gets BOTH
            :data-rf2-source-coord AND :data-rf-view spliced in under slim"
    (rf/reg-view ^{:rf/id :rf.slim-view-id/no-attrs} no-attrs-view []
      [:span "hi"])
    (let [out ((rf/view :rf.slim-view-id/no-attrs))]
      (is (= [:span ":rf.slim-view-id/no-attrs" true]
             [(first out) (root-view-attr out) (string? (root-coord-attr out))])
          "the root tag is kept, the view attribute is (str id), and the source coord rides beside it"))))

;; ---- DOM-keyword root with attrs map --------------------------------------

(deftest tags-dom-root-with-existing-attrs
  (testing "a reg-view'd component with [:tag {:class …} children…] has
            :data-rf-view merged into the existing attrs map alongside
            user attrs (without disturbing them)"
    (rf/reg-view ^{:rf/id :rf.slim-view-id/with-attrs} with-attrs-view []
      [:div {:class "card" :id "x"} "body"])
    (let [out   ((rf/view :rf.slim-view-id/with-attrs))
          attrs (second out)]
      (is (= [:div {:class "card" :id "x" :data-rf-view ":rf.slim-view-id/with-attrs"} true]
             [(first out) (select-keys attrs [:class :id :data-rf-view])
              (string? (:data-rf2-source-coord attrs))])
          "both attributes merged in beside the user's, which are untouched"))))

;; ---- user-supplied data-rf-view wins --------------------------------------

(deftest user-supplied-data-rf-view-wins
  (testing "a render-fn that already set :data-rf-view is not overwritten
            — composability with hand-stamped tools"
    (rf/reg-view ^{:rf/id :rf.slim-view-id/user-stamped} user-stamped-view []
      [:p {:data-rf-view "stamped:by-user"} "ok"])
    (let [render (rf/view :rf.slim-view-id/user-stamped)
          out    (render)]
      (is (= "stamped:by-user" (:data-rf-view (second out)))
          "user-supplied attribute survives the wrapper's merge"))))

;; ---- Form-2: render-fn returns a fn --------------------------------------

(deftest tags-form-2-inner-output
  (testing "Form-2 render-fns return a fn; the wrapper recurses on the
            inner fn's output so :data-rf-view lands on the eventual
            rendered DOM root, not the outer fn"
    (rf/reg-view* :rf.slim-view-id/form-2
      (fn []
        (fn inner-render []
          [:section.f2 "form-2 body"])))
    (let [inner-out (((rf/view :rf.slim-view-id/form-2)))]
      (is (= [:section.f2 ":rf.slim-view-id/form-2" true]
             [(first inner-out) (root-view-attr inner-out) (string? (root-coord-attr inner-out))])
          "both attributes landed on the inner output's root"))))
