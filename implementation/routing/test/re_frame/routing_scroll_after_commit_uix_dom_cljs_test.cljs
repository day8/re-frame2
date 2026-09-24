(ns re-frame.routing-scroll-after-commit-uix-dom-cljs-test
  "The cross-route fragment row of
  `re-frame.routing-scroll-after-commit-reagent-dom-cljs-test`, under the UIx
  adapter's NATIVE mount (a raw `createRoot` + `.render`, the documented boot
  idiom). The starting navigation asks for no scroll, so the fragment
  navigation's scroll is this adapter generation's FIRST after-render use —
  the call that lazily mounts the spine's after-render driver root.

  ns ends in `-dom-cljs-test`; under `:node-test` the row is a stated skip."
  (:require [cljs.test :refer-macros [async deftest is use-fixtures]]
            ["react-dom" :as react-dom]
            ["react-dom/client" :as react-dom-client]
            [uix.core :refer-macros [defui $]]
            [re-frame.adapter.uix :as rf.adapter.uix]
            [re-frame.routing :as rf.routing]
            [re-frame.routing-scroll-witness-test-support :as rf.routing-scroll-witness-test-support]
            [re-frame.test-support :as rf.test-support]))

(use-fixtures :each
  (rf.test-support/make-reset-runtime-fixture
    {:adapter       rf.adapter.uix/adapter
     :ambient-frame nil
     :async?        true
     :init-fn       (fn []
                      (set! (.-IS_REACT_ACT_ENVIRONMENT js/globalThis) false)
                      (rf.routing/reset-counters!)
                      (rf.routing/reset-scroll-cache!)
                      (rf.routing-scroll-witness-test-support/register-routes!))}))

(defui Page []
  (if (= rf.routing-scroll-witness-test-support/docs-route (rf.adapter.uix/use-sub [:rf.route/id]))
    ($ :div
       ($ :div {:style {:height "3000px"}})
       ($ :h2 {:id rf.routing-scroll-witness-test-support/install-id} "Install")
       ($ :div {:style {:height "3000px"}}))
    ($ :div {:style {:height "100px"}} "home")))

(deftest a-cross-route-fragment-lands-on-the-arriving-section
  (if-not (rf.routing-scroll-witness-test-support/browser?)
    (is true "a scroll witness needs a real browser — :node-test has no layout or scroll model")
    (async done
      (rf.routing-scroll-witness-test-support/run-fragment-row!
        {:adapter-name "UIx (native mount)"
         :mount!       (fn [container frame-id]
                         (let [root (react-dom-client/createRoot container)]
                           (react-dom/flushSync
                             (fn [] (.render root ($ rf.adapter.uix/frame-provider {:frame frame-id}
                                                     ($ Page)))))
                           root))
         :unmount!     #(.unmount %)}
        done))))
