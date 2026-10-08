(ns reagent2.dom.error-boundary-dom-cljs-test
  "`reagent2.core/create-class`'s `:component-did-catch` under a mounted React
  19 root: a descendant throwing in RENDER or in COMMIT reaches the NEAREST
  slim boundary, which commits its fallback, and an enclosing boundary does
  not fire. `outer-boundary-catches-when-inner-is-absent` is the control that
  makes the nested arm's zero a measurement. Each root diverts React's
  `onCaughtError` report into an atom, asserted non-empty as a second witness
  that React did the routing."
  (:require [cljs.test :refer-macros [deftest is testing]]
            [reagent2.core :as r]
            [reagent2.dom.client :as rdc]
            ["react-dom" :as react-dom]))

(def ^:private render-boom "child-render-boom")
(def ^:private commit-boom "child-commit-boom")

(defn- browser? []
  (and (exists? js/document)
       (some? (.-createElement js/document))))

(defn- make-mount-node! []
  (when (browser?)
    (.createElement js/document "div")))

(defn- root-opts
  "React 19 root options that divert the boundary-caught error report
  from `console.error` into `reports`."
  [reports]
  #js {:onCaughtError (fn [error _info]
                        (swap! reports conj (.-message ^js error)))})

(defn- boundary-class
  "A slim Form-3 error boundary named `label`: renders `(child-fn)` until a
  descendant throws, appends each caught message to `fired`, then renders
  `\"<label>:fallback\"` once the default `getDerivedStateFromError` marker
  reaches its state atom (IMPL-SPEC §6.5)."
  [label fired child-fn]
  (r/create-class
    {:display-name        (str label "-boundary")
     :component-did-catch (fn [_this ^js error _info]
                            (swap! fired conj (.-message error)))
     :reagent-render
     (fn []
       (let [this (r/current-component)]
         (if (:cljsHasError @(r/state-atom this))
           [:div {:class (str label "-fallback")} (str label ":fallback")]
           (child-fn))))}))

(defn- render-thrower
  "A Form-1 slim component whose RENDER throws."
  []
  (throw (js/Error. render-boom)))

(defn- commit-thrower
  "A slim Form-3 component that renders, then throws from
  `:component-did-mount` — React's COMMIT phase."
  []
  (r/create-class
    {:display-name        "commit-thrower"
     :component-did-mount (fn [_this] (throw (js/Error. commit-boom)))
     :reagent-render      (fn [] [:span "child"])}))

(deftest nested-boundaries-inner-catches-outer-does-not
  (testing "a descendant RENDER throw is caught by the NEAREST slim
            boundary; the enclosing outer boundary does not fire"
    (if-not (browser?)
      (is true ":node-test: no DOM — the :browser-test runner exercises the assertion")
      (let [outer-fired (atom [])
            inner-fired (atom [])
            reports     (atom [])
            inner       (boundary-class "inner" inner-fired (fn [] [render-thrower]))
            outer       (boundary-class "outer" outer-fired (fn [] [inner]))
            mount-node  (make-mount-node!)
            root        (rdc/create-root mount-node (root-opts reports))]
        (try
          (react-dom/flushSync (fn [] (rdc/render root [outer])))
          ;; A set: React 19 may re-run a failed render once.
          (is (= #{render-boom} (set @inner-fired)))
          (is (= [] @outer-fired))
          (is (= "inner:fallback" (.-textContent mount-node)))
          (is (seq @reports) "React reported the caught error through onCaughtError")
          (finally
            (rdc/unmount root)))))))

(deftest outer-boundary-catches-when-inner-is-absent
  (testing "the SAME throwing descendant with no inner boundary IS caught
            by the outer one"
    (if-not (browser?)
      (is true ":node-test: no DOM — the :browser-test runner exercises the assertion")
      (let [outer-fired (atom [])
            reports     (atom [])
            outer       (boundary-class "outer" outer-fired (fn [] [render-thrower]))
            mount-node  (make-mount-node!)
            root        (rdc/create-root mount-node (root-opts reports))]
        (try
          (react-dom/flushSync (fn [] (rdc/render root [outer])))
          (is (= #{render-boom} (set @outer-fired)))
          (is (= "outer:fallback" (.-textContent mount-node)))
          (is (seq @reports) "React reported the caught error through onCaughtError")
          (finally
            (rdc/unmount root)))))))

(deftest commit-phase-child-did-mount-throw-reaches-boundary
  (testing "a descendant that throws from :component-did-mount reaches the
            enclosing slim boundary, which commits its fallback"
    (if-not (browser?)
      (is true ":node-test: no DOM — the :browser-test runner exercises the assertion")
      (let [fired      (atom [])
            reports    (atom [])
            child      (commit-thrower)
            boundary   (boundary-class "commit" fired (fn [] [child]))
            mount-node (make-mount-node!)
            root       (rdc/create-root mount-node (root-opts reports))]
        (try
          (react-dom/flushSync (fn [] (rdc/render root [boundary])))
          (is (= #{commit-boom} (set @fired)))
          (is (= "commit:fallback" (.-textContent mount-node)))
          (is (seq @reports) "React reported the caught error through onCaughtError")
          (finally
            (rdc/unmount root)))))))
