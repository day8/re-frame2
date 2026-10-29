(ns re-frame.test-support-flush-views-dom-cljs-test
  "`re-frame.test-support/flush-views!` under the Reagent adapter: a ratom
  update re-renders and commits before the call returns, the commit-phase
  lifecycle has run by then, and the call names no adapter. Reagent queues a
  ratom's re-render for its next animation frame, so a DOM reading the new
  value on the next line can only be the flush; the control proves the
  queue is really there. UIx's twin is the component recipe,
  `re-frame.adapter.uix-component-recipe-dom-cljs-test`."
  (:require [cljs.test :refer-macros [deftest is use-fixtures]]
            [reagent.core :as r]
            [reagent.dom.client :as rdc]
            [re-frame.adapter.reagent :as rf.adapter.reagent]
            [re-frame.test-support :as rf.test-support]
            [re-frame.adapter.react-test-support :refer [browser?]]))

(use-fixtures :each
  (rf.test-support/make-reset-runtime-fixture
    {:adapter rf.adapter.reagent/adapter}))

(defn- with-act-environment
  "Run `f` with React's act environment declared, restoring the flag found."
  [f]
  (let [prev (.-IS_REACT_ACT_ENVIRONMENT js/globalThis)]
    (set! (.-IS_REACT_ACT_ENVIRONMENT js/globalThis) true)
    (try (f)
         (finally (set! (.-IS_REACT_ACT_ENVIRONMENT js/globalThis) prev)))))

(deftest a-ratom-update-commits-through-the-core-flush
  (if-not (browser?)
    (is true ":node-test: no DOM — the :browser-test runner exercises the assertions")
    (with-act-environment
      (fn []
        (let [n       (r/atom 1)
              updates (atom 0)
              ran     (atom 0)
              counter (r/create-class
                        {:component-did-update (fn [_ _] (swap! updates inc))
                         :reagent-render       (fn [] [:div "n=" @n])})
              node    (.createElement js/document "div")
              root    (rdc/create-root node)]
          (try
            (rf.test-support/flush-views! #(rdc/render root [counter]))
            (is (= "n=1" (.-textContent node))
                "the mount committed before flush-views! returned")
            (is (nil? (rf.test-support/flush-views!
                        (fn [] (swap! ran inc) (reset! n 2))))
                "flush-views! returns nil")
            (is (= 1 @ran) "f ran exactly once")
            (is (= "n=2" (.-textContent node))
                "the ratom's re-render committed before flush-views! returned")
            (is (= 1 @updates)
                "componentDidUpdate ran inside the flush, not on a later frame")
            ;; Control: without the flush the re-render waits for Reagent's
            ;; next frame, so the DOM still shows the old value.
            (reset! n 3)
            (is (= "n=2" (.-textContent node))
                "control: a bare ratom write has not reached the DOM")
            (rf.test-support/flush-views!)
            (is (= "n=3" (.-textContent node))
                "the 0-arity commits the render already pending")
            (finally
              (rf.test-support/flush-views! #(rdc/unmount root)))))))))
