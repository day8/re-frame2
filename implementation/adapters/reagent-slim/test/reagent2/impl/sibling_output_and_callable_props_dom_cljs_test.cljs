(ns reagent2.impl.sibling-output-and-callable-props-dom-cljs-test
  "rf2-fzbj.30 (rf2-gwye.51, rf2-gwye.52) — two reagent-slim render-boundary
  contracts, witnessed through a real `react-dom/client` root.

  1. A component whose render returns a SEQUENCE commits sibling children:
     no wrapper element and no `:rf.error/template-bad-tag`. Before the fix,
     `wrap-render`'s untagged arm coerced the seq with `vec`, so
     `as-element` read the first child as a hiccup HEAD.
  2. A metadata-bearing callback (`cljs.core/MetaFn`, what `with-meta`
     returns for a fn) reaches React DOM as a real JS function, so a click
     runs it and a callback ref is called with the node. Before the fix the
     MetaFn object passed through unchanged; its `typeof` is \"object\", so
     React DOM refused it as a listener and treated it as an object ref.

  TEST-ONLY. The ns ends in `-dom-cljs-test`, so `:browser-test` runs the
  live bodies; `:node-test` also loads it (`cljs-test$` matches), where they
  gate on `(browser?)` and no-op. The node-lane counterparts live in
  `template_cljs_test.cljs`."
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

(defn- host-html [^js node]
  (some-> (.querySelector node ".host") .-innerHTML))

(defn- keyed-rows [] (list ^{:key "a"} [:span "a"] ^{:key "b"} [:span "b"]))
(defn- empty-rows [] (list))
(defn- text-rows [] (list "a" "b"))

(deftest sequence-output-commits-sibling-children
  (testing "reagent-slim — an untagged component returning a sequence commits its items as siblings (rf2-fzbj.30)"
    (if-not (browser?)
      (is true ":node-test: no DOM — :browser-test runner exercises the assertion")
      (with-attached-root
        (fn [root node]
          (react-dom/flushSync (fn [] (rdc/render root [:div.host [keyed-rows]])))
          (is (= "<span>a</span><span>b</span>" (host-html node))
              "two keyed sibling spans, with no wrapper element")

          (react-dom/flushSync (fn [] (rdc/render root [:div.host [empty-rows]])))
          (is (= "" (host-html node))
              "an empty sequence commits nothing")

          (react-dom/flushSync (fn [] (rdc/render root [:div.host [text-rows]])))
          (is (= "ab" (some-> (.querySelector node ".host") .-textContent))
              "text siblings commit as text")
          (is (= 0 (some-> (.querySelector node ".host") .-children .-length))
              "…and not as an `<a>` element holding the second item"))))))

(deftest metafn-click-handler-and-callback-ref-reach-react-dom
  (testing "reagent-slim — a metadata-bearing on-click and :ref are called by React DOM (rf2-fzbj.30)"
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
              (is (some? button) "the button committed")
              (is (and (some? button) (identical? button @seen))
                  "the metadata-bearing callback ref was CALLED with the committed node")
              (when button (.click button))
              (is (= 1 @clicks)
                  "a real click ran the metadata-bearing handler"))))))))
