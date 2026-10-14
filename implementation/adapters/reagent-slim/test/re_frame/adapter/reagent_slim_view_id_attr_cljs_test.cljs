(ns re-frame.adapter.reagent-slim-view-id-attr-cljs-test
  "Under the slim adapter, a registered view's DOM root carries
  `data-rf-view=\"<id>\"` beside `data-rf2-source-coord` in debug builds (Spec
  006 §View tagging contract). Neither ratom adapter routes a view wrap, so
  the merge, user-wins, Form-2 and Fragment rows are the shared views code's,
  pinned once by `re-frame.view-id-attr-cljs-test` and
  `re-frame.source-coord-dom-cljs-test`; this row pins that slim's installed
  head stamps at all."
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
