(ns re-frame.routing-scroll-after-commit-reagent-slim-dom-cljs-test
  "The cross-route fragment row of
  `re-frame.routing-scroll-after-commit-reagent-dom-cljs-test`, under the
  reagent-slim adapter's ordinary mount path (`reagent2.dom.client`), whose
  after-render queue runs on the render scheduler's own microtask.

  ns ends in `-dom-cljs-test`; under `:node-test` the row is a stated skip."
  (:require [cljs.test :refer-macros [async deftest is use-fixtures]]
            ["react-dom" :as react-dom]
            [reagent2.dom.client :as rdc]
            [re-frame.adapter.reagent-slim :as rf.adapter.reagent-slim]
            [re-frame.core :as rf]
            [re-frame.routing :as rf.routing]
            [re-frame.routing-scroll-witness-test-support :as rf.routing-scroll-witness-test-support]
            [re-frame.test-support :as rf.test-support]
            [re-frame.views]))

(use-fixtures :each
  (rf.test-support/make-reset-runtime-fixture
    {:adapter       rf.adapter.reagent-slim/adapter
     :ambient-frame nil
     :async?        true
     :init-fn       (fn []
                      (set! (.-IS_REACT_ACT_ENVIRONMENT js/globalThis) false)
                      (rf.routing/reset-counters!)
                      (rf.routing/reset-scroll-cache!)
                      (rf.routing-scroll-witness-test-support/register-routes!))}))

(deftest a-cross-route-fragment-lands-on-the-arriving-section
  (if-not (rf.routing-scroll-witness-test-support/browser?)
    (is true "a scroll witness needs a real browser — :node-test has no layout or scroll model")
    (async done
      (rf.routing-scroll-witness-test-support/run-fragment-row!
        {:adapter-name "reagent-slim"
         :mount!       (fn [container frame-id]
                         (let [root (rdc/create-root container)]
                           (react-dom/flushSync
                             (fn [] (rdc/render root [rf/frame-provider {:frame frame-id}
                                                      [(rf/view ::rf.routing-scroll-witness-test-support/page)]])))
                           root))
         :unmount!     #(.unmount %)}
        done))))
