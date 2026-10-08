(ns reagent2.dom.lifecycle-prev-state-dom-cljs-test
  "`reagent2.core/create-class`'s paired update lifecycles on a real React 19
  update: `:get-snapshot-before-update (fn [this prev-argv prev-state])` and
  `:component-did-update (fn [this prev-argv prev-state snapshot])` each fire
  once, with the previous argv, React's own prevState and gSBU's snapshot.
  The callbacks are FIXED-arity, so a bridge that drops or shifts an argument
  fails rather than being absorbed by a variadic probe.
  `reagent2.impl.component-cljs-test` covers the same bridge without a
  reconciler."
  (:require [cljs.test :refer-macros [deftest is testing]]
            [reagent2.core :as r]
            [reagent2.dom.client :as rdc]
            ["react-dom" :as react-dom]))

(defn- browser? []
  (and (exists? js/document)
       (some? (.-createElement js/document))))

(defn- make-mount-node! []
  (when (browser?)
    (.createElement js/document "div")))

(defn- probe [prev-state]
  (when prev-state (.-probe ^js prev-state)))

(deftest mounted-update-forwards-prev-argv-prev-state-and-snapshot
  (testing "reagent-slim — a real createRoot update feeds fixed-arity gSBU/cDU the documented (this prev-argv prev-state snapshot) shape"
    (if-not (browser?)
      (is true ":node-test: no DOM — :browser-test runner exercises the assertion")
      (let [gsbu-calls (atom [])
            cdu-calls  (atom [])
            klass (r/create-class
                    {:display-name "prev-state-probe"
                     :reagent-render
                     (fn [v] [:div "v=" v])
                     ;; Seed a real React state. The argv-equality sCU
                     ;; swallows this setState's re-render, so no gSBU/cDU
                     ;; fires for it, but React commits the state, and the
                     ;; later changed-argv update sees it as prevState.
                     :component-did-mount
                     (fn [this]
                       (.setState ^js this #js {:probe "seeded-prev-state"}))
                     :get-snapshot-before-update
                     (fn [_this prev-argv prev-state]
                       (swap! gsbu-calls conj [(second prev-argv) (probe prev-state)])
                       :snapshot-sentinel-42)
                     :component-did-update
                     (fn [_this prev-argv prev-state snapshot]
                       (swap! cdu-calls conj [(second prev-argv) (probe prev-state) snapshot]))})
            mount-node (make-mount-node!)
            root       (rdc/create-root mount-node)]
        (try
          (react-dom/flushSync
            (fn [] (rdc/render root [klass "a"])))
          (is (= ["v=a" [] []]
                 [(.-textContent mount-node) @gsbu-calls @cdu-calls])
              "the mount, and the sCU-swallowed setState pass, fire neither update lifecycle")
          (react-dom/flushSync
            (fn [] (rdc/render root [klass "b"])))
          (is (= ["v=b"
                  [["a" "seeded-prev-state"]]
                  [["a" "seeded-prev-state" :snapshot-sentinel-42]]]
                 [(.-textContent mount-node) @gsbu-calls @cdu-calls])
              "the update committed, and each lifecycle fired once with the previous argv, React's prevState and gSBU's snapshot")
          (finally
            (try (rdc/unmount root) (catch :default _ nil))))))))
