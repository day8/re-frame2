(ns day8.re-frame2-xray.filters.pills-cljs-test
  "View + wiring tests for `filters/pills.cljs`. `pills-view` is pure
  hiccup, so the rows walk its output rather than mounting to a DOM."
  (:require [cljs.test :refer-macros [deftest is use-fixtures]]
            [re-frame.core :as rf]
            [re-frame.test-helpers :as rf.test-helpers]
            [day8.re-frame2-xray.filters.pills :as pills]
            [day8.re-frame2-xray.registry :as registry]
            [day8.re-frame2-xray.theme.tokens :refer [tokens]]
            [day8.re-frame2-xray.test-helpers.dynamic-shell-tree
             :as dynamic-shell-tree]
            [day8.re-frame2-xray.test-support :as xray-test-support]))

(use-fixtures :each (xray-test-support/make-xray-runtime-fixture))

(defn- node-attrs [tree testid]
  (second (rf.test-helpers/find-by-testid tree testid)))

(deftest pill-renders-label-in-its-mode-tone
  ;; The border colour is the pill's only include/exclude signal.
  (let [tree (pills/pills-view identity {:filters {:in  [{:pattern ":auth/*"}]
                                                   :out [{:pattern ":mouse-move"}]}})]
    (is (= [(str "1px solid " (:success tokens)) (str "1px solid " (:error tokens))]
           [(:border (:style (node-attrs tree "rf-xray-filter-pill-in-0")))
            (:border (:style (node-attrs tree "rf-xray-filter-pill-out-0")))]))
    (is (re-find #":auth/\*" (rf.test-helpers/text-content
                               (rf.test-helpers/find-by-testid tree "rf-xray-filter-pill-in-0-body"))))))

(deftest pill-clicks-dispatch-edit-and-remove
  (let [dispatched (atom [])
        tree       (pills/pills-view #(swap! dispatched conj %)
                                     {:filters {:in  [{:pattern ":auth/*"}]
                                                :out [{:pattern ":mouse-move"}
                                                      {:pattern ":anim-frame"}]}})]
    ((:on-click (node-attrs tree "rf-xray-filter-pill-in-0-body")) nil)
    ((:on-click (node-attrs tree "rf-xray-filter-pill-out-1-remove")) nil)
    (is (= [[:rf.xray/open-edit-popup {:source :pill :mode :in :idx 0
                                       :pill {:pattern ":auth/*"}}]
            [:rf.xray/remove-filter :out 1]]
           @dispatched))))

(deftest cluster-tooltip-shows-counts
  ;; spec/018 §3, with 'pattern' singular at a count of one.
  (is (= "IN: 3 patterns / OUT: 1 pattern"
         (:title (node-attrs (pills/pills-view identity
                                               {:filters {:in  [{:pattern ":a"} {:pattern ":b"} {:pattern ":c"}]
                                                          :out [{:pattern ":d"}]}})
                             "rf-xray-ribbon-filters")))))

(deftest add-filter-buttons-open-an-empty-in-popup
  ;; Both add buttons the shell mounts — the chrome ribbon's `+ filter` and
  ;; the events ribbon's `[+]` — open the edit popup, empty and IN.
  (registry/register-xray-handlers!)
  (rf/make-frame {:id :rf/xray})
  (let [handlers   (rf/with-frame :rf/xray
                     (let [tree (dynamic-shell-tree/shell-view-tree)]
                       (mapv #(:on-click (node-attrs tree %))
                             ["rf-xray-filter-add" "rf-xray-filter-add-events"])))
        dispatches (atom [])]
    (with-redefs [rf/dispatch-impl (fn
                                     ([ev]       (swap! dispatches conj ev) nil)
                                     ([ev _opts] (swap! dispatches conj ev) nil))]
      (doseq [h handlers] (h nil)))
    (is (= [[:rf.xray/open-edit-popup {:source :add :mode :in}]
            [:rf.xray/open-edit-popup {:source :add :mode :in}]]
           @dispatches))))
