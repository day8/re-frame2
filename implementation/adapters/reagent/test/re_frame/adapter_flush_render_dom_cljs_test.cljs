(ns re-frame.adapter-flush-render-dom-cljs-test
  "The Reagent `(adapter/flush-render! f)` SYNCHRONOUSLY commits the render a
  state change scheduled, without waiting for Reagent's rAF-scheduled drain,
  which a backgrounded tab throttles to almost never (Spec 006
  §`flush-render!`; the re-frame2-pair dispatch-and-settle op depends on it).
  The dispatch only enqueues the re-render, so the DOM reading the new value
  on the next line can only be the flush. UIx's twin is the shared suite's
  `assert-flush-render-synchronously-commits`."
  (:require [cljs.test :refer-macros [deftest is testing use-fixtures]]
            [reagent.dom.client :as rdc]
            ["react-dom" :as react-dom]
            [re-frame.core :as rf]
            [re-frame.adapter.reagent :as rf.adapter.reagent]
            [re-frame.test-support :as rf.test-support]
            [re-frame.views]))

;; `:ambient-frame nil`: the render runs inside the test body's dynamic
;; extent, where an ambient :rf/default would shadow the frame-provider.
(use-fixtures :each
  (rf.test-support/make-reset-runtime-fixture
    {:adapter rf.adapter.reagent/adapter
     :ambient-frame nil}))

(defn- browser? []
  (and (exists? js/document)
       (some? (.-createElement js/document))))

(defn- make-mount-node! []
  (when (browser?)
    (.createElement js/document "div")))

(deftest flush-render-synchronously-commits
  (testing "Reagent — flush-render! synchronously commits a pending render"
    (if-not (browser?)
      (is true ":node-test: no DOM — :browser-test runner exercises the assertion")
      (let [frame-kw :rf.reagent-flush-render/probe-frame
            flush!   (:flush-render! rf.adapter.reagent/adapter)]
        (rf/make-frame {:id frame-kw :doc "flush-render! synchronous-commit probe frame"})
        (rf/reg-event ::seed (fn [{:keys [db]} _] {:db {:n 1}}))
        (rf/reg-event ::inc  (fn [{:keys [db]} _] {:db (update db :n inc)}))
        (rf/dispatch-sync [::seed] {:frame frame-kw})
        (rf/reg-sub ::n (fn [db _] (:n db)))
        (rf/reg-view* :rf.reagent-flush-render/probe
                      (fn probe []
                        [:div "n=" @(rf/subscribe [::n])]))
        (let [mount-node (make-mount-node!)
              root       (rdc/create-root mount-node)]
          (try
            (react-dom/flushSync
              (fn []
                (rdc/render root [rf/frame-provider {:frame frame-kw}
                                  [(rf/view :rf.reagent-flush-render/probe)]])))
            (is (= "n=1" (.-textContent mount-node))
                "committed DOM shows the seeded value n=1")
            (rf/dispatch-sync [::inc] {:frame frame-kw})
            (flush!)
            (is (= "n=2" (.-textContent mount-node))
                "DOM reflects the dispatched change SYNCHRONOUSLY after
                 flush-render! returns — no rAF wait")
            (finally
              (try (.unmount root) (catch :default _ nil)))))))))
