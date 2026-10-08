(ns day8.re-frame2-xray.filters.right-click-integration-cljs-test
  "Right-click event-row integration. The row's `on-context-menu` opens
  the row context menu, whose hide item dispatches
  `:rf.xray/hide-event-type` (the pre-filled popup and its save path are
  pinned in `filters.edit-popup-cljs-test`). And once an OUT pill is
  installed, the rendered L2 list drops the matching row."
  (:require [cljs.test :refer-macros [deftest is use-fixtures]]
            [re-frame.core :as rf]
            [re-frame.test-helpers :as rf.test-helpers]
            [day8.re-frame2-xray.registry :as registry]
            [day8.re-frame2-xray.test-helpers.dynamic-shell-tree
             :as dynamic-shell-tree]
            [day8.re-frame2-xray.test-support :as xray-test-support]
            [day8.re-frame2-xray.trace-collector :as trace-collector]))

(use-fixtures :each (xray-test-support/make-xray-runtime-fixture))

(defn- xray-setup! []
  (registry/register-xray-handlers!)
  (rf/make-frame {:id :rf/xray}))

(defn- dispatch-trace-ev [id event-vec]
  {:id           id
   :op-type      :rf.event
   :operation    :rf.event/dispatched
   :tags         {:rf.event/v       event-vec
                  :frame       :rf/default
                  :rf.trace/dispatch-id id}})

(deftest right-click-row-opens-context-menu
  ;; preventDefault suppresses the browser's own menu; the click coords
  ;; position ours.
  (xray-setup!)
  (trace-collector/seed-trace-for-test! (dispatch-trace-ev 7 [:user/mouse-move {:x 1}]))
  (let [dispatches (atom [])
        prevented? (atom false)
        event      #js {:preventDefault (fn [] (reset! prevented? true))
                        :clientX        128
                        :clientY        256}]
    (with-redefs [rf/dispatch-impl (fn
                                     ([ev]       (swap! dispatches conj ev) nil)
                                     ([ev _opts] (swap! dispatches conj ev) nil))]
      (rf/with-frame :rf/xray
        (let [row (rf.test-helpers/find-by-testid (dynamic-shell-tree/shell-view-tree)
                                                  "rf-xray-event-row-7")]
          ((:on-context-menu (second row)) event))))
    (is (true? @prevented?))
    (is (some #{[:rf.xray/open-row-context-menu {:event-id :user/mouse-move :x 128 :y 256}]}
              @dispatches))))

(deftest out-pill-removes-matching-row-from-event-list
  (xray-setup!)
  (trace-collector/seed-trace-for-test! (dispatch-trace-ev 1 [:auth/login]))
  (trace-collector/seed-trace-for-test! (dispatch-trace-ev 2 [:mouse-move]))
  (trace-collector/seed-trace-for-test! (dispatch-trace-ev 3 [:order/submit]))
  (rf/with-frame :rf/xray
    (let [rows-present (fn []
                         (let [tree (dynamic-shell-tree/shell-view-tree)]
                           (mapv #(some? (rf.test-helpers/find-by-testid tree (str "rf-xray-event-row-" %)))
                                 [1 2 3])))]
      (is (= [true true true] (rows-present)))
      (rf/dispatch-sync [:rf.xray/add-filter :out {:pattern :mouse-move}])
      (is (= [true false true] (rows-present)) "row 2 (:mouse-move) filtered out"))))
