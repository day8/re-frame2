(ns re-frame.adapter.helix-view-id-attr-cljs-test
  "Per Spec 006 §View tagging contract (rf2-01il5): the Helix adapter's
  wrap-view MUST inject `data-rf-view=\"<id>\"` on the rendered root
  React element ALONGSIDE `data-rf2-source-coord`. The injection rides
  the same React.cloneElement call, the same `interop/debug-enabled?`
  gate, and the same exemption for non-DOM roots.

  This file is the Helix-side counterpart to
  `re-frame.view-id-attr-cljs-test` (Reagent path) and
  `re-frame.adapter.uix-view-id-attr-cljs-test` (UIx path)."
  (:require ["react" :as React]
            [cljs.test :refer-macros [deftest is testing use-fixtures]]
            [re-frame.core :as rf]
            [re-frame.adapter.helix :as helix-adapter]
            [re-frame.test-support :as test-support]))

(use-fixtures :each
  (test-support/reset-runtime-fixture-factory
    {:adapter helix-adapter/adapter}))

(defn- react-element-view-attr
  [el]
  (when (and el (.-props el))
    (aget (.-props el) "data-rf-view")))

(defn- react-element-source-coord
  [el]
  (when (and el (.-props el))
    (aget (.-props el) "data-rf2-source-coord")))

;; ---- DOM-tag root: both attrs injected ------------------------------------

(deftest reg-view-tags-dom-root-with-data-rf-view-helix
  (testing "Per rf2-01il5: a programmatic reg-view* under the Helix adapter
            routes the render-fn through (adapter/wrap-view ...) so the
            rendered React element carries BOTH data-rf-view AND
            data-rf2-source-coord on the root element."
    (let [user-fn (fn []
                    (React/createElement "span" #js {} "hi"))]
      (rf/reg-view* :rf.helix-view-id-test/dom-root user-fn)
      (let [render (rf/view :rf.helix-view-id-test/dom-root)
            out    (render)]
        (is (some? out))
        (is (= "span" (.-type out)))
        (let [view  (react-element-view-attr out)
              coord (react-element-source-coord out)]
          (is (string? view))
          (is (= ":rf.helix-view-id-test/dom-root" view))
          (is (string? coord)))))))

;; ---- Fragment exemption ---------------------------------------------------

(deftest reg-view-fragment-root-is-exempt-for-view-id-helix
  (testing "Fragment root is exempt — no attribute lands on Fragment props."
    (let [Frag    (.-Fragment React)
          user-fn (fn []
                    (React/createElement Frag nil
                                         (React/createElement "p" nil "a")
                                         (React/createElement "p" nil "b")))]
      (rf/reg-view* :rf.helix-view-id-test/fragment-root user-fn)
      (let [render (rf/view :rf.helix-view-id-test/fragment-root)
            out    (render)]
        (is (some? out))
        (is (identical? Frag (.-type out)))
        (is (nil? (react-element-view-attr out)))
        (is (nil? (react-element-source-coord out)))))))

;; ---- user-supplied attr wins ----------------------------------------------

(deftest reg-view-preserves-user-supplied-view-id-helix
  (testing "User-supplied data-rf-view survives the wrap-view pass."
    (let [user-attr "stamped:by-user"
          user-fn   (fn []
                      (React/createElement "div"
                        #js {"data-rf-view" user-attr}
                        "hi"))]
      (rf/reg-view* :rf.helix-view-id-test/user-attr-root user-fn)
      (let [render (rf/view :rf.helix-view-id-test/user-attr-root)
            out    (render)]
        (is (some? out))
        (is (= user-attr (react-element-view-attr out)))))))

;; ---- wrap-view direct exercise --------------------------------------------

(deftest wrap-view-coexists-with-data-rf-view-helix
  (testing "wrap-view returns a fn; calling it on a real React element
            yields output carrying data-rf-view alongside data-rf2-source-coord"
    (let [out-from-fn (atom nil)
          wrapped     (helix-adapter/wrap-view :rf.helix-view-id-test/sample
                                               {:line 42 :column 7}
                                               (fn []
                                                 (reset! out-from-fn :ran)
                                                 (React/createElement "div" #js {} "x")))]
      (is (fn? wrapped))
      (let [out (wrapped)]
        (is (= :ran @out-from-fn))
        (is (= ":rf.helix-view-id-test/sample" (react-element-view-attr out)))))))
