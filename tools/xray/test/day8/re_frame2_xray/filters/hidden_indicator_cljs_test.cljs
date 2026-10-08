(ns day8.re-frame2-xray.filters.hidden-indicator-cljs-test
  "Integration tests for the L2 'N events filtered out' indicator: the
  `:rf.xray/hidden-by-filters` sub composed through the `:rf/xray` frame,
  and the count the events ribbon renders from it."
  (:require [cljs.test :refer-macros [deftest is use-fixtures]]
            [re-frame.core :as rf]
            [re-frame.test-helpers :as rf.test-helpers]
            [day8.re-frame2-xray.frame-switcher :as frame-switcher]
            [day8.re-frame2-xray.registry :as registry]
            [day8.re-frame2-xray.test-helpers.dynamic-shell-tree
             :as dynamic-shell-tree]
            [day8.re-frame2-xray.test-support :as xray-test-support]
            [day8.re-frame2-xray.trace-collector :as trace-collector]))

(use-fixtures :each
  (xray-test-support/make-xray-runtime-fixture
    {:post-reset (fn [] (frame-switcher/clear!))}))

(defn- xray-setup! []
  (registry/register-xray-handlers!)
  (rf/make-frame {:id :rf/xray}))

(defn- dispatch-trace-ev
  ([id event-vec] (dispatch-trace-ev id event-vec :rf/default))
  ([id event-vec frame-id]
   {:id           id
    :op-type      :rf.event
    :operation    :rf.event/dispatched
    :tags         {:rf.event/v       event-vec
                   :frame       frame-id
                   :rf.trace/dispatch-id id}}))

(deftest frame-is-a-view-scope-not-a-filter
  ;; The hidden baseline is taken WITHIN the selected frame, so scoping
  ;; another frame's events away never counts as hidden by filters.
  (xray-setup!)
  (trace-collector/seed-trace-for-test! (dispatch-trace-ev 1 [:a] :rf/frame-x))
  (trace-collector/seed-trace-for-test! (dispatch-trace-ev 2 [:b] :rf/frame-y))
  (trace-collector/seed-trace-for-test! (dispatch-trace-ev 3 [:c] :rf/frame-y))
  (rf/with-frame :rf/xray
    (rf/dispatch-sync [:rf.xray/select-frame :rf/frame-y])
    (is (= [0 2] [(:hidden @(rf/subscribe [:rf.xray/hidden-by-filters]))
                  (count @(rf/subscribe [:rf.xray/filtered-event-bundles]))])
        "the scope drops frame-x's event, and none of it counts as hidden")))

(deftest indicator-renders-the-hidden-count
  (xray-setup!)
  (trace-collector/seed-trace-for-test! (dispatch-trace-ev 1 [:a]))
  (trace-collector/seed-trace-for-test! (dispatch-trace-ev 2 [:noise/tick]))
  (rf/with-frame :rf/xray
    (rf/dispatch-sync [:rf.xray/add-filter :out {:pattern :noise/tick}])
    (is (re-find #"1 event filtered out"
                 (rf.test-helpers/text-content
                   (rf.test-helpers/find-by-testid (dynamic-shell-tree/shell-view-tree)
                                                   "rf-xray-filters-hidden-count"))))))
