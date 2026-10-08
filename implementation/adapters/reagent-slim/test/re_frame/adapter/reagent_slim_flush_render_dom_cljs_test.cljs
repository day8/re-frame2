(ns re-frame.adapter.reagent-slim-flush-render-dom-cljs-test
  "reagent-slim's `(adapter/flush-render! f)` SYNCHRONOUSLY commits the render
  a state change scheduled, through the substrate's own hiccup root (Spec 006
  §`flush-render!`; the re-frame2-pair dispatch → render → observe-DOM loop
  depends on it). Its `react-dom/flushSync` boundary is load-bearing: under
  React 19 a bare `forceUpdate` from outside React's batching is only
  SCHEDULED. The Reagent twin is `re-frame.adapter-flush-render-dom-cljs-test`;
  UIx's is the shared suite's `assert-flush-render-synchronously-commits`,
  which mounts a native element rather than hiccup."
  (:require [cljs.test :refer-macros [deftest is testing use-fixtures]]
            [reagent2.dom.client :as rdc]
            ["react-dom" :as react-dom]
            [re-frame.core :as rf]
            [re-frame.adapter.reagent-slim :as rf.adapter.reagent-slim]
            [re-frame.test-support :as rf.test-support]
            [re-frame.views]))

;; `:ambient-frame nil`: the render runs inside the test body's dynamic
;; extent, where an ambient :rf/default would shadow the frame-provider.
(use-fixtures :each
  (rf.test-support/make-reset-runtime-fixture
    {:adapter rf.adapter.reagent-slim/adapter
     :ambient-frame nil}))

(defn- browser? []
  (and (exists? js/document)
       (some? (.-createElement js/document))))

(defn- make-mount-node! []
  (when (browser?)
    (.createElement js/document "div")))

(deftest flush-render-synchronously-commits
  (testing "reagent-slim — flush-render! synchronously commits a pending render under createRoot"
    (if-not (browser?)
      (is true ":node-test: no DOM — :browser-test runner exercises the assertion")
      (let [frame-kw :rf.reagent-slim-flush-render/probe-frame
            flush!   (:flush-render! rf.adapter.reagent-slim/adapter)]
        (rf/make-frame {:id frame-kw :doc "flush-render! synchronous-commit probe frame"})
        (rf/reg-event ::seed (fn [{:keys [db]} _] {:db {:n 1}}))
        (rf/reg-event ::inc  (fn [{:keys [db]} _] {:db (update db :n inc)}))
        (rf/dispatch-sync [::seed] {:frame frame-kw})
        (rf/reg-sub ::n (fn [db _] (:n db)))
        (rf/reg-view* :rf.reagent-slim-flush-render/probe
                      (fn probe []
                        [:div "n=" @(rf/subscribe [::n])]))
        (let [mount-node (make-mount-node!)
              root       (rdc/create-root mount-node)]
          (try
            (react-dom/flushSync
              (fn []
                (rdc/render root [rf/frame-provider {:frame frame-kw}
                                  [(rf/view :rf.reagent-slim-flush-render/probe)]])))
            (is (= "n=1" (.-textContent mount-node))
                "committed DOM shows the seeded value n=1")
            ;; The dispatch only enqueues the re-render; flush-render! must
            ;; commit it before returning.
            (rf/dispatch-sync [::inc] {:frame frame-kw})
            (flush!)
            (is (= "n=2" (.-textContent mount-node))
                "DOM reflects the dispatched change SYNCHRONOUSLY after
                 flush-render! returns — no microtask wait")
            (finally
              (try (.unmount root) (catch :default _ nil)))))))))
