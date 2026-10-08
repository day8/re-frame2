(ns day8.re-frame2-xray.filters.typed-predicates-events-cljs-test
  "The typed-predicate `:rf.xray/filter-by-*` events: each appends a
  `{:kind … :params …}` pill to the IN bucket of `:rf.xray/active-filters`,
  and a repeated add collapses to one pill. The pure matchers are in
  `typed_predicates_cljs_test.cljc`."
  (:require [cljs.test :refer-macros [deftest is testing use-fixtures]]
            [re-frame.core :as rf]
            [day8.re-frame2-xray.registry :as registry]
            [day8.re-frame2-xray.test-support :as xray-test-support]))

(use-fixtures :each (xray-test-support/make-xray-runtime-fixture))

(defn- xray-setup! []
  (registry/register-xray-handlers!)
  (rf/make-frame {:id :rf/xray}))

(deftest filter-by-http-correlation-appends-typed-pill
  (testing "a repeated right-click add collapses to one pill"
    (xray-setup!)
    (rf/with-frame :rf/xray
      (rf/dispatch-sync [:rf.xray/filter-by-http-correlation "abc-123"])
      (rf/dispatch-sync [:rf.xray/filter-by-http-correlation "abc-123"])
      (is (= [{:kind :http-correlation :params {:correlation-id "abc-123"}}]
             (:in @(rf/subscribe [:rf.xray/active-filters])))))))

(deftest mixed-typed-and-legacy-pills-coexist
  (testing "the add-filter path (bare `{:pattern ...}`) and a filter-by-*
            path populate the same IN bucket without stepping on each other"
    (xray-setup!)
    (rf/with-frame :rf/xray
      (rf/dispatch-sync [:rf.xray/add-filter :in {:pattern :auth/*}])
      (rf/dispatch-sync [:rf.xray/filter-by-fx :rf.http/managed])
      (is (= [{:pattern :auth/*}
              {:kind :fx :params {:fx-id :rf.http/managed}}]
             (:in @(rf/subscribe [:rf.xray/active-filters])))))))
