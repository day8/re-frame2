(ns re-frame.adapter.uix-evicted-shared-child-dom-cljs-test
  "Two mounted parents retain their shared input across cache eviction and release it on unmount."
  (:require [cljs.test :refer-macros [deftest is testing use-fixtures async]]
            ["react-dom/client" :as react-dom-client]
            [uix.core :refer-macros [defui $]]
            [re-frame.core :as rf]
            [re-frame.frame :as rf.frame]
            [re-frame.subs.cache :as rf.subs.cache]
            [re-frame.adapter.uix :as rf.adapter.uix]
            [re-frame.test-support :as rf.test-support]
            [re-frame.adapter.react-test-support :refer [browser? react-act]]))

(use-fixtures :each
  (rf.test-support/make-reset-runtime-fixture
    {:adapter rf.adapter.uix/adapter :async? true}))

(def ^:private ec-frame :rf.uix-evicted-shared-child/frame)

(defui ParentA []
  (let [v (rf.adapter.uix/use-sub [::p1] {:frame ec-frame})]
    ($ :div {:id "ec-a"} (str "a=" v))))

(defui ParentB []
  (let [v (rf.adapter.uix/use-sub [::p2] {:frame ec-frame})]
    ($ :div {:id "ec-b"} (str "b=" v))))

(defui BothParents []
  ($ :div
     ($ ParentA)
     ($ ParentB)))

(def ^:private horizon-settle-ms
  "Wait past the provisional reap horizon before reading the resulting references."
  24)

(defn- settle! [k] (js/setTimeout k horizon-settle-ms))

(defn- ref-count-of
  [query-v]
  (or (get-in @(:sub-cache (rf.frame/frame ec-frame)) [query-v :ref-count]) 0))

(deftest two-mounted-parents-keep-sharing-one-child-across-a-cache-clear
  (testing "UIx — two mounted use-sub consumers over ONE shared declared input
            survive clear-sub-cache! still sharing that input"
    (if-not (browser?)
      (is true ":node-test: no DOM — :browser-test runner exercises the assertion")
      (let [act-fn (react-act)]
        (async done
          (set! (.-IS_REACT_ACT_ENVIRONMENT js/globalThis) true)
          (rf/make-frame {:id  ec-frame
                          :doc "two-parent / shared-child probe frame"})
          (rf/reg-event ::seed (fn [_ctx _e] {:db {:n 1}}))
          (rf/reg-event ::inc  (fn [{:keys [db]} _e] {:db (update db :n inc)}))
          (rf/dispatch-sync [::seed] {:frame ec-frame})
          (rf/reg-sub ::child (fn [db _q] (:n db)))
          (rf/reg-sub ::p1 {:inputs [[::child]]} (fn [[c] _q] (* 10 c)))
          (rf/reg-sub ::p2 {:inputs [[::child]]} (fn [[c] _q] (* 100 c)))

          (let [mount-node (.createElement js/document "div")
                root       (react-dom-client/createRoot mount-node)]
            (act-fn (fn [] (.render root ($ BothParents))))
            (settle!
              (fn []
                (is (= [2 "a=10b=100"] [(ref-count-of [::child]) (.-textContent mount-node)])
                    "baseline: both parents committed, and the shared child carries one ref per parent")

                (act-fn (fn [] (rf.subs.cache/clear-sub-cache! ec-frame)))

                (settle!
                  (fn []
                    (is (= [2 1 1] (mapv ref-count-of [[::child] [::p1] [::p2]]))
                        "[child p1 p2] ref-counts: the shared child keeps both refs across the eviction (an address-only release reads 1, having disposed the successor), and each parent holds its rebuilt reaction once")

                    (act-fn (fn [] (rf/dispatch-sync [::inc] {:frame ec-frame})))
                    (settle!
                      (fn []
                        (is (= "a=20b=200" (.-textContent mount-node))
                            "BOTH mounted parents re-committed after the
                               eviction — neither is watching a child that was
                               disposed out from under it")
                        (act-fn (fn [] (.unmount root)))
                        (settle!
                          (fn []
                            (is (zero? (ref-count-of [::child]))
                                "unmounting both parents releases the shared
                                   child exactly — no leak, no double-release")
                            (done)))))))))))))))
