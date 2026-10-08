(ns re-frame.adapter.reagent-slim-scu-argv-gate-dom-cljs-test
  "Every reagent-slim class carries the framework's argv-equality
  `shouldComponentUpdate`: when a parent re-renders with a `=` argv, the
  child's render fn and `:component-did-update` do NOT run, and when the argv
  changes they do. React consults it only in real reconciliation, which the
  prototype-level `component_cljs_test` cannot reach. That a same-argv
  SUBSCRIPTION change still commits (`forceUpdate` bypasses the gate) is
  `reagent_slim_flush_render_dom_cljs_test`'s."
  (:require [cljs.test :refer-macros [deftest is testing use-fixtures]]
            [reagent2.dom.client :as rdc]
            [reagent2.core :as r]
            ["react-dom" :as react-dom]
            [re-frame.core :as rf]
            [re-frame.adapter.reagent-slim :as rf.adapter.reagent-slim]
            [re-frame.test-support :as rf.test-support]
            [re-frame.views]))

;; `:ambient-frame nil`: the probe's subscribes must resolve the frame-provider.
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

(deftest scu-argv-gate-skips-equal-argv-child-renders-changed-argv
  (testing "reagent-slim — the framework-default sCU gates parent-propagated child re-renders by argv `=`"
    (if-not (browser?)
      (is true ":node-test: no DOM — :browser-test runner exercises the assertion")
      (let [frame-kw     :rf.reagent-slim-scu/probe-frame
            flush!       (:flush-render! rf.adapter.reagent-slim/adapter)
            render-count (atom 0)
            update-count (atom 0)
            ;; The child subscribes to nothing, so only parent propagation,
            ;; which the sCU gates, can re-render it.
            child (r/create-class
                    {:display-name "scu-probe-child"
                     :reagent-render
                     (fn [v]
                       (swap! render-count inc)
                       [:span "child-v=" v])
                     :component-did-update
                     (fn [_this _prev-argv _prev-state _snapshot]
                       (swap! update-count inc))})]
        (rf/make-frame {:id frame-kw :doc "sCU argv-gate probe frame"})
        (rf/reg-event ::seed       (fn [_ _] {:db {:tick 0 :child-v 1}}))
        (rf/reg-event ::bump-tick  (fn [{:keys [db]} _] {:db (update db :tick inc)}))
        (rf/reg-event ::bump-child (fn [{:keys [db]} _] {:db (update db :child-v inc)}))
        (rf/dispatch-sync [::seed] {:frame frame-kw})
        (rf/reg-sub ::tick    (fn [db _] (:tick db)))
        (rf/reg-sub ::child-v (fn [db _] (:child-v db)))
        ;; The parent re-renders on either sub but passes only :child-v down,
        ;; so :tick changes the parent without changing the child's argv.
        (rf/reg-view* :rf.reagent-slim-scu/parent
                      (fn parent []
                        (let [tick @(rf/subscribe [::tick])
                              cv   @(rf/subscribe [::child-v])]
                          [:div
                           [child cv]
                           [:span "tick=" tick]])))
        (let [mount-node (make-mount-node!)
              root       (rdc/create-root mount-node)]
          (try
            (react-dom/flushSync
              (fn []
                (rdc/render root [rf/frame-provider {:frame frame-kw}
                                  [(rf/view :rf.reagent-slim-scu/parent)]])))
            (is (= [1 0 "child-v=1tick=0"]
                   [@render-count @update-count (.-textContent mount-node)])
                "[child-renders child-updates text] after the initial mount")
            (rf/dispatch-sync [::bump-tick] {:frame frame-kw})
            (flush!)
            (is (= [1 0 "child-v=1tick=1"]
                   [@render-count @update-count (.-textContent mount-node)])
                "EQUAL argv: the parent re-rendered but the sCU skipped the child's render and update")
            (rf/dispatch-sync [::bump-child] {:frame frame-kw})
            (flush!)
            (is (= [2 1 "child-v=2tick=1"]
                   [@render-count @update-count (.-textContent mount-node)])
                "CHANGED argv: the sCU let the child render and update")
            (finally
              (try (.unmount root) (catch :default _ nil)))))))))
