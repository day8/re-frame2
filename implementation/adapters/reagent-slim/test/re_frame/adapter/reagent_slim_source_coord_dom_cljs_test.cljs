(ns re-frame.adapter.reagent-slim-source-coord-dom-cljs-test
  "Under the slim adapter, a registered view's DOM root carries
  `data-rf2-source-coord` in debug builds (Spec 006 §Source-coord
  annotation): never over a user-supplied value, never on a Fragment root,
  and as `<ns>:<sym>:?:?` for a programmatic `reg-view*`. The Reagent twin is
  `re-frame.source-coord-dom-cljs-test`; the splice onto DOM-keyword and
  Form-2 roots is pinned in
  `re-frame.adapter.reagent-slim-view-id-attr-cljs-test`, and the formatted
  value by the Reagent bridge's `annotates-dom-root-without-attrs`."
  (:require [cljs.test :refer-macros [deftest is testing use-fixtures]]
            [re-frame.core :as rf]
            [re-frame.adapter.reagent-slim :as rf.adapter.reagent-slim]
            [re-frame.test-support :as rf.test-support]
            [re-frame.views]))

(use-fixtures :each
  (rf.test-support/make-reset-runtime-fixture
    {:adapter rf.adapter.reagent-slim/adapter}))

;; ---- helpers ---------------------------------------------------------------

(defn- root-attr
  "Pull the :data-rf2-source-coord value from the root attrs map of a
  hiccup vector, if any."
  [hiccup]
  (and (vector? hiccup)
       (map? (second hiccup))
       (:data-rf2-source-coord (second hiccup))))

;; ---- user-supplied coord wins ---------------------------------------------

(deftest user-supplied-data-rf2-source-coord-wins
  (testing "a render-fn that already set :data-rf2-source-coord is not
            overwritten — composability with hand-stamped tools"
    (rf/reg-view ^{:rf/id :rf.slim-src-coord/user-stamped} user-stamped-view []
      [:p {:data-rf2-source-coord "stamped:by-user"} "ok"])
    (let [render (rf/view :rf.slim-src-coord/user-stamped)
          out    (render)]
      (is (= "stamped:by-user" (:data-rf2-source-coord (second out)))
          "user-supplied attribute survives the wrapper's merge"))))

;; ---- React Fragment / non-DOM root: skip ----------------------------------

(deftest fragment-root-is-exempt
  (testing "a render-fn that returns a React Fragment :<> at the root is
            exempt — no attribute injected"
    (rf/reg-view ^{:rf/id :rf.slim-src-coord/fragment} fragment-view []
      [:<> [:p "a"] [:p "b"]])
    (let [render (rf/view :rf.slim-src-coord/fragment)
          out    (render)]
      (is (= [:<> false]
             [(first out) (boolean (and (map? (second out))
                                        (contains? (second out) :data-rf2-source-coord)))])
          "the fragment root is kept, with no :data-rf2-source-coord"))))

;; ---- programmatic registration without macro source-coords ---------------

(deftest programmatic-registration-degrades-gracefully
  (testing "a programmatic reg-view* (no macro coords) still annotates
            with the id-derived <ns>:<sym> portion; line/col are `?`"
    (rf/reg-view* :rf.slim-src-coord/programmatic
      (fn [] [:p "p"]))
    (let [render (rf/view :rf.slim-src-coord/programmatic)
          out    (render)
          attr   (root-attr out)]
      (is (= "rf.slim-src-coord:programmatic:?:?" attr)
          "format degrades to <ns>:<sym>:?:? when coords are absent"))))
