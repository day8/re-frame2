(ns re-frame.adapter.uix-view-id-attr-cljs-test
  "Per Spec 006 §View tagging contract (rf2-01il5): the UIx adapter's
  wrap-view MUST inject `data-rf-view=\"<id>\"` on the rendered root
  React element ALONGSIDE `data-rf2-source-coord`. The injection rides
  the same React.cloneElement call, the same `interop/debug-enabled?`
  gate, and the same exemption for non-DOM roots.

  This file is the UIx-side counterpart to
  `re-frame.view-id-attr-cljs-test` (Reagent path). Same shape; the
  React-element output replaces the hiccup vector."
  (:require ["react" :as React]
            [cljs.test :refer-macros [deftest is testing use-fixtures]]
            [re-frame.core :as rf]
            [re-frame.adapter.uix :as uix-adapter]
            [re-frame.test-support :as test-support]))

(use-fixtures :each
  (test-support/reset-runtime-fixture-factory
    {:adapter uix-adapter/adapter}))

(defn- react-element-view-attr
  "Pull `data-rf-view` off a React element's `.-props`, or nil."
  [el]
  (when (and el (.-props el))
    (aget (.-props el) "data-rf-view")))

(defn- react-element-source-coord
  "Pull `data-rf2-source-coord` off a React element's `.-props`, or nil."
  [el]
  (when (and el (.-props el))
    (aget (.-props el) "data-rf2-source-coord")))

;; ---- DOM-tag root: both attrs injected ------------------------------------

(deftest reg-view-tags-dom-root-with-data-rf-view-uix
  (testing "Per rf2-01il5: a programmatic reg-view* under the UIx adapter
            routes the render-fn through (adapter/wrap-view ...) so the
            rendered React element carries BOTH data-rf-view AND
            data-rf2-source-coord on the root element."
    (let [user-fn (fn []
                    (React/createElement "span" #js {} "hi"))]
      (rf/reg-view* :rf.uix-view-id-test/dom-root user-fn)
      (let [render (rf/view :rf.uix-view-id-test/dom-root)
            out    (render)]
        (is (some? out))
        (is (= "span" (.-type out)) "root element type preserved")
        (let [view  (react-element-view-attr out)
              coord (react-element-source-coord out)]
          (is (string? view)
              "data-rf-view present on the root React element")
          (is (= ":rf.uix-view-id-test/dom-root" view)
              "view attribute value is (str id) — leading-colon preserved")
          (is (string? coord)
              "data-rf2-source-coord still present (parity contract)"))))))

;; ---- Fragment exemption ---------------------------------------------------

(deftest reg-view-fragment-root-is-exempt-for-view-id-uix
  (testing "A React.Fragment root is exempt — cloneElement is skipped,
            so neither attribute lands on the props of the Fragment."
    (let [Frag    (.-Fragment React)
          user-fn (fn []
                    (React/createElement Frag nil
                                         (React/createElement "p" nil "a")
                                         (React/createElement "p" nil "b")))]
      (rf/reg-view* :rf.uix-view-id-test/fragment-root user-fn)
      (let [render (rf/view :rf.uix-view-id-test/fragment-root)
            out    (render)]
        (is (some? out))
        (is (identical? Frag (.-type out)))
        (is (nil? (react-element-view-attr out))
            "no data-rf-view on Fragment root (exempt; walker falls back)")
        (is (nil? (react-element-source-coord out))
            "no data-rf2-source-coord on Fragment root (parity)")))))

;; ---- user-supplied attr wins ----------------------------------------------

(deftest reg-view-preserves-user-supplied-view-id-uix
  (testing "When the user component's root element already carries
            `data-rf-view`, the wrapper passes it through unchanged —
            no cloneElement clobber. Guards the existing-attr
            short-circuit in `inject-source-coord-attr`."
    (let [user-attr "stamped:by-user"
          user-fn   (fn []
                      (React/createElement "div"
                        #js {"data-rf-view" user-attr}
                        "hi"))]
      (rf/reg-view* :rf.uix-view-id-test/user-attr-root user-fn)
      (let [render (rf/view :rf.uix-view-id-test/user-attr-root)
            out    (render)]
        (is (some? out))
        (is (= user-attr (react-element-view-attr out))
            "user-supplied data-rf-view survives the wrap-view pass")))))

;; ---- format helper --------------------------------------------------------

(deftest wrap-view-coexists-with-data-rf-view-uix
  (testing "wrap-view returns a fn; calling it on a real React element
            yields output carrying data-rf-view alongside data-rf2-source-coord"
    (let [out-from-fn (atom nil)
          wrapped     (uix-adapter/wrap-view :rf.uix-view-id-test/sample
                                             {:line 42 :column 7}
                                             (fn []
                                               (reset! out-from-fn :ran)
                                               (React/createElement "div" #js {} "x")))]
      (is (fn? wrapped))
      (let [out (wrapped)]
        (is (= :ran @out-from-fn))
        (is (some? out))
        (let [view (react-element-view-attr out)]
          (is (= ":rf.uix-view-id-test/sample" view)
              "wrap-view's React.cloneElement injected data-rf-view"))))))
