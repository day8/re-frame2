(ns re-frame.ui.adapter-conformance-dom-cljs-test
  "Browser proof for the native adapter's render/flush/dispose lifecycle.

  Container and SSR semantics live in adapter-cljs-test; this focused arm
  exercises the two contract functions that need a real DOM and proves
  `rf/destroy-adapter!` drains adapter-owned React roots before re-init."
  (:require [cljs.test :refer-macros [deftest is testing use-fixtures]]
            ["react" :as react]
            [re-frame.core :as rf]
            [re-frame.substrate.adapter :as substrate-adapter]
            [re-frame.ui :as ui]))

(defn- browser? []
  (and (exists? js/document) (some? (.-createElement js/document))))

(use-fixtures
  :each
  (fn [f]
    (substrate-adapter/reset-lifecycle-state-for-tests!)
    (try
      (f)
      (finally
        (rf/destroy-adapter!)
        (substrate-adapter/reset-lifecycle-state-for-tests!)))))

(defn- render-label! [container label]
  (let [unmount (atom nil)]
    ((:flush-render! ui/adapter)
     #(reset! unmount
              ((:render ui/adapter)
               (react/createElement "span" #js {:className "native-adapter-probe"} label)
               container
               {})))
    @unmount))

(deftest render-dispose-and-reinit-own-the-react-root-lifecycle
  (if-not (browser?)
    (is true ":node — browser-test exercises the DOM contract")
    (let [container (js/document.createElement "div")]
      (is (nil? (rf/init! ui/adapter)))
      (let [unmount (render-label! container "first")]
        (is (fn? unmount) ":render returns its root-unmount handle")
        (is (= "first" (.-textContent container))
            ":flush-render! commits the adapter-owned React root synchronously"))

      (is (nil? (rf/destroy-adapter!)))
      (is (= "" (.-textContent container))
          "adapter disposal drains every active root")

      (is (nil? (rf/init! ui/adapter)))
      (render-label! container "second")
      (is (= "second" (.-textContent container))
          "re-init re-arms render ownership after disposal"))))
