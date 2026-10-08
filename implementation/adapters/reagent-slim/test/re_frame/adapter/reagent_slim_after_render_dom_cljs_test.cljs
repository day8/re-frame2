(ns re-frame.adapter.reagent-slim-after-render-dom-cljs-test
  "An ordinary-path `rf.interop/after-render` callback on reagent-slim reads
  the COMMITTED DOM. The batching tests and the shared React suite only show
  the callback FIRED; one run while React had merely SCHEDULED the class
  update would pass them and still read stale DOM, so the callback here reads
  `textContent` itself. Nothing between the state change and that read —
  `flush-views!`, `flush-render!`, `flushSync`, an await — may impose a
  commit; the `flushSync` wraps only the initial mount, and the `setTimeout`
  only defers the assertion. The DOM is asserted still OLD after the
  dispatch, so a substrate that committed synchronously could not pass."
  (:require [cljs.test :refer-macros [deftest is testing use-fixtures async]]
            [reagent2.dom.client :as rdc]
            ["react-dom" :as react-dom]
            [re-frame.core :as rf]
            [re-frame.interop :as rf.interop]
            [re-frame.adapter.reagent-slim :as rf.adapter.reagent-slim]
            [re-frame.test-support :as rf.test-support]
            [re-frame.views]))

;; `:ambient-frame nil`: an ambient :rf/default would shadow the
;; frame-provider the probe's `subscribe` must resolve.
(use-fixtures :each
  (rf.test-support/make-reset-runtime-fixture
    {:adapter rf.adapter.reagent-slim/adapter
     :async? true
     :ambient-frame nil}))

(defn- browser? []
  (and (exists? js/document)
       (some? (.-createElement js/document))))

(defn- make-mount-node! []
  (when (browser?)
    (.createElement js/document "div")))

(deftest after-render-callback-observes-the-committed-dom
  (testing "reagent-slim — an ordinary-path after-render callback sees the
  COMMITTED DOM, not the pre-commit DOM"
    (if-not (browser?)
      (is true ":node-test: no DOM — :browser-test runner exercises the assertion")
      (async done
        (let [frame-kw :rf.reagent-slim-after-render/probe-frame]
          (rf/make-frame {:id frame-kw :doc "after-render post-commit probe frame"})
          (rf/reg-event ::seed (fn [_ _] {:db {:n 1}}))
          (rf/reg-event ::inc  (fn [{:keys [db]} _] {:db (update db :n inc)}))
          (rf/dispatch-sync [::seed] {:frame frame-kw})
          (rf/reg-sub ::n (fn [db _] (:n db)))
          (rf/reg-view* :rf.reagent-slim-after-render/probe
                        (fn probe []
                          [:div "n=" @(rf/subscribe [::n])]))
          (let [mount-node (make-mount-node!)
                root       (rdc/create-root mount-node)
                ;; Recorded from inside the callback: an `is` there would be
                ;; swallowed by the queue's per-callback throw isolation.
                seen       (atom [])]
            (react-dom/flushSync
              (fn []
                (rdc/render root [rf/frame-provider {:frame frame-kw}
                                  [(rf/view :rf.reagent-slim-after-render/probe)]])))
            (rf/dispatch-sync [::inc] {:frame frame-kw})
            (is (= "n=1" (.-textContent mount-node))
                "precondition: the mount committed n=1, and dispatch alone has NOT committed the change")
            (rf.interop/after-render
              (fn after-render-probe []
                (swap! seen conj (.-textContent mount-node))))

            (js/setTimeout
              (fn []
                (is (= [["n=2"] "n=2"] [@seen (.-textContent mount-node)])
                    "[callback-reads final-dom]: the callback fired once and read the COMMITTED n=2; a read of n=1 means it ran while React had only scheduled the update")
                (try (.unmount root) (catch :default _ nil))
                (done))
              50)))))))

(deftest after-render-with-no-dirty-component-still-fires
  (testing "reagent-slim — a callback queued with no dirty component still
  fires asynchronously, and the commit-aware path does not strand it"
    (async done
      (let [fired (atom 0)]
        (rf.interop/after-render (fn [] (swap! fired inc)))
        (is (= 0 @fired) "not yet — after-render is never synchronous")
        (js/setTimeout
          (fn []
            (is (= 1 @fired)
                "the callback fired on its own scheduler turn with nothing dirty")
            (done))
          50)))))
