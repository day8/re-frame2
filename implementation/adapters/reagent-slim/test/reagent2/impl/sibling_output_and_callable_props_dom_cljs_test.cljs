(ns reagent2.impl.sibling-output-and-callable-props-dom-cljs-test
  "A reagent-slim render-boundary contract, witnessed through a real
  `react-dom/client` root: a metadata-bearing callback (`cljs.core/MetaFn`,
  what `with-meta` returns for a fn) reaches React DOM as a real JS function,
  so a click runs it and a callback ref is called with the node. Passed
  through unchanged, the MetaFn object's `typeof` is \"object\", so React DOM
  would refuse it as a listener and treat it as an object ref.

  A component returning a SEQUENCE is pinned on the node lane, through the
  generated class, by `template_cljs_test.cljs`."
  (:require [cljs.test :refer-macros [deftest is testing]]
            [reagent2.dom.client :as rdc]
            ["react-dom" :as react-dom]))

(defn- browser? []
  (and (exists? js/document)
       (some? (.-createElement js/document))))

(defn- with-attached-root
  "Mount a fresh root on a node attached to the document (so a real click
  bubbles to React's root listener), call `(f root node)`, and always
  unmount and detach."
  [f]
  (let [node (.createElement js/document "div")
        root (rdc/create-root node)]
    (.appendChild (.-body js/document) node)
    (try
      (f root node)
      (finally
        (try (rdc/unmount root) (catch :default _ nil))
        (.remove node)))))

(deftest metafn-click-handler-and-callback-ref-reach-react-dom
  (testing "reagent-slim — a metadata-bearing on-click and :ref are called by React DOM"
    (if-not (browser?)
      (is true ":node-test: no DOM — :browser-test runner exercises the assertion")
      (let [clicks   (atom 0)
            seen     (atom nil)
            on-click (with-meta (fn [_e] (swap! clicks inc)) {:rf/probe true})
            ref-fn   (with-meta (fn [el] (when el (reset! seen el))) {:rf/probe true})]
        (with-attached-root
          (fn [root node]
            (react-dom/flushSync
              (fn []
                (rdc/render root [:button {:id       "metafn-probe"
                                           :on-click on-click
                                           :ref      ref-fn}
                                  "Click"])))
            (let [button (.querySelector node "#metafn-probe")]
              (is (and (some? button) (identical? button @seen))
                  "the metadata-bearing callback ref was CALLED with the committed node")
              (when button (.click button))
              (is (= 1 @clicks)
                  "a real click ran the metadata-bearing handler"))))))))
