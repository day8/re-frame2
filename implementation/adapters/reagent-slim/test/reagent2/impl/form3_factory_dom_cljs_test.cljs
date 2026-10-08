(ns reagent2.impl.form3-factory-dom-cljs-test
  "The Form-3 FACTORY shape `FORM-3.md` §\"A complete example\" documents: a
  plain `defn` whose body closes over per-mount state and returns a
  `create-class` result. A slim class IS a JS function, so classifying the
  factory's output with a bare `fn?` would take the Form-2 branch and apply
  the class constructor as a render fn; `wrap-render` and the static
  serialiser's `emit-render-fn` check `reagent-class?` first. Only a real
  `[factory args...]` mount exercises that seam. The static-markup test is
  not browser-gated."
  (:require [cljs.test :refer-macros [deftest is testing]]
            [reagent2.core :as r]
            [reagent2.dom.client :as rdc]
            [reagent2.dom.server :as server]
            ["react-dom" :as react-dom]))

(defn- browser? []
  (and (exists? js/document)
       (some? (.-createElement js/document))))

(defn- make-mount-node! []
  (when (browser?)
    (.createElement js/document "div")))

(def ^:private panel-html "<div class=\"factory-panel\">hello</div>")

(defn- make-probe-factory
  "A Form-3 factory in the documented shape: each mount creates a fresh
  closure token and class, and logs `[phase token]` to `lifecycle`."
  [lifecycle]
  (fn probe-factory [_initial-label]
    (let [token (gensym "instance")]
      (r/create-class
        {:display-name "form3-factory-probe"
         :reagent-render
         (fn [label] [:div {:class "factory-panel"} label])
         :component-did-mount
         (fn [_this] (swap! lifecycle conj [:mount token]))
         :component-will-unmount
         (fn [_this] (swap! lifecycle conj [:unmount token]))}))))

(deftest factory-returned-class-mounts-updates-and-unmounts
  (testing "reagent-slim — a plain factory returning create-class mounts its class, keeps closure identity across an update, and runs mount/unmount exactly once"
    (if-not (browser?)
      (is true ":node-test: no DOM — :browser-test runner exercises the assertion")
      (let [lifecycle  (atom [])
            factory    (make-probe-factory lifecycle)
            mount-node (make-mount-node!)
            root       (rdc/create-root mount-node)]
        (try
          (react-dom/flushSync (fn [] (rdc/render root [factory "hello"])))
          (is (= panel-html (.-innerHTML mount-node))
              "the class's own :reagent-render output mounted")
          (react-dom/flushSync (fn [] (rdc/render root [factory "updated"])))
          (is (= "updated" (.-textContent mount-node)))
          (rdc/unmount root)
          (let [token (second (first @lifecycle))]
            (is (= [[:mount token] [:unmount token]] @lifecycle)
                "one mount and one unmount sharing a closure token — the update reused the instance"))
          (finally
            (try (rdc/unmount root) (catch :default _ nil))))))))

(deftest sibling-factory-instances-own-separate-closures
  (testing "reagent-slim — two sibling mounts of one factory each own a separate closure and class"
    (if-not (browser?)
      (is true ":node-test: no DOM — :browser-test runner exercises the assertion")
      (let [lifecycle  (atom [])
            factory    (make-probe-factory lifecycle)
            mount-node (make-mount-node!)
            root       (rdc/create-root mount-node)]
        (try
          (react-dom/flushSync
            (fn [] (rdc/render root [:div [factory "a"] [factory "b"]])))
          (let [tokens (map second @lifecycle)]
            (is (= ["ab" [:mount :mount] 2]
                   [(.-textContent mount-node) (map first @lifecycle) (count (set tokens))])
                "both siblings mounted, each with its own closure token"))
          (finally
            (try (rdc/unmount root) (catch :default _ nil))))))))

(deftest static-markup-of-a-factory-emits-content-without-lifecycle
  (testing "reagent-slim — render-to-static-markup of a factory-returned class emits the class's markup and runs no lifecycle"
    (let [lifecycle (atom [])
          factory   (make-probe-factory lifecycle)]
      (is (= [panel-html []]
             [(server/render-to-static-markup [factory "hello"]) @lifecycle])))))
