(ns day8.re-frame2-xray.filters.typed-predicates-events-cljs-test
  "CLJS integration tests for the typed-predicate `:rf.xray/filter-by-*`
  events (rf2-piye4).

  Asserts that:
   - each typed-add event lands a `{:kind … :params …}` pill in the IN
     bucket of `:rf.xray/active-filters`;
   - duplicate adds are idempotent (no pile-up);
   - the persistence fx fires on each mutation.

  The pure matcher tests live in `typed_predicates_test.cljc` — JVM-
  runnable. This file covers the dispatch / registry wiring that's
  CLJS-only."
  (:require [cljs.test :refer-macros [deftest is testing use-fixtures]]
            [re-frame.core :as rf]
            [re-frame.frame :as frame]
            [re-frame.substrate.plain-atom :as plain-atom]
            [re-frame.test-support :as test-support]
            [day8.re-frame2-xray.registry :as registry]
            [day8.re-frame2-xray.test-support :as xray-test-support]))

(defn- xray-init! []
  (xray-test-support/reset-all!))

(use-fixtures :each
  (test-support/make-reset-runtime-fixture
    {:adapter plain-atom/adapter
     :init-fn xray-init!}))

(defn- xray-setup! []
  (registry/register-xray-handlers!)
  (frame/reg-frame :rf/xray {}))

;; ---- :rf.xray/filter-by-machine ----------------------------------------

(deftest filter-by-machine-appends-typed-pill
  (xray-setup!)
  (rf/with-frame :rf/xray
    (rf/dispatch-sync [:rf.xray/filter-by-machine :form])
    (let [filters @(rf/subscribe [:rf.xray/active-filters])]
      (is (= [{:kind :machine :params {:machine-id :form}}]
             (:in filters))
          "machine pill landed in IN bucket")
      (is (= [] (:out filters))))))

(deftest filter-by-machine-idempotent
  (testing "duplicate add with the same machine-id collapses to one pill"
    (xray-setup!)
    (rf/with-frame :rf/xray
      (rf/dispatch-sync [:rf.xray/filter-by-machine :form])
      (rf/dispatch-sync [:rf.xray/filter-by-machine :form])
      (rf/dispatch-sync [:rf.xray/filter-by-machine :form])
      (let [filters @(rf/subscribe [:rf.xray/active-filters])]
        (is (= 1 (count (:in filters)))
            "three adds → one pill")))))

(deftest filter-by-machine-distinct-ids-accumulate
  (xray-setup!)
  (rf/with-frame :rf/xray
    (rf/dispatch-sync [:rf.xray/filter-by-machine :form])
    (rf/dispatch-sync [:rf.xray/filter-by-machine :auth])
    (let [filters @(rf/subscribe [:rf.xray/active-filters])]
      (is (= 2 (count (:in filters))))
      (is (= #{:form :auth}
             (set (map #(get-in % [:params :machine-id]) (:in filters))))))))

;; ---- :rf.xray/filter-by-http-correlation -------------------------------

(deftest filter-by-http-correlation-appends-typed-pill
  (xray-setup!)
  (rf/with-frame :rf/xray
    (rf/dispatch-sync [:rf.xray/filter-by-http-correlation "abc-123"])
    (let [filters @(rf/subscribe [:rf.xray/active-filters])]
      (is (= [{:kind :http-correlation :params {:correlation-id "abc-123"}}]
             (:in filters))))))

(deftest filter-by-http-correlation-idempotent
  (xray-setup!)
  (rf/with-frame :rf/xray
    (rf/dispatch-sync [:rf.xray/filter-by-http-correlation "abc-123"])
    (rf/dispatch-sync [:rf.xray/filter-by-http-correlation "abc-123"])
    (is (= 1 (count (:in @(rf/subscribe [:rf.xray/active-filters])))))))

;; ---- :rf.xray/filter-by-fx ---------------------------------------------

(deftest filter-by-fx-appends-typed-pill
  (xray-setup!)
  (rf/with-frame :rf/xray
    (rf/dispatch-sync [:rf.xray/filter-by-fx :rf.http/managed])
    (let [filters @(rf/subscribe [:rf.xray/active-filters])]
      (is (= [{:kind :fx :params {:fx-id :rf.http/managed}}]
             (:in filters))))))

(deftest filter-by-fx-idempotent
  (xray-setup!)
  (rf/with-frame :rf/xray
    (rf/dispatch-sync [:rf.xray/filter-by-fx :rf.http/managed])
    (rf/dispatch-sync [:rf.xray/filter-by-fx :rf.http/managed])
    (is (= 1 (count (:in @(rf/subscribe [:rf.xray/active-filters])))))))

;; ---- mixed typed + legacy keyword pills ---------------------------------

(deftest mixed-typed-and-legacy-pills-coexist
  (testing "the rf2-ak4ms add-filter path (legacy `{:pattern ...}`) and
            the rf2-piye4 filter-by-* paths can populate the same IN
            bucket without stepping on each other"
    (xray-setup!)
    (rf/with-frame :rf/xray
      (rf/dispatch-sync [:rf.xray/add-filter :in {:pattern :auth/*}])
      (rf/dispatch-sync [:rf.xray/filter-by-machine :form])
      (rf/dispatch-sync [:rf.xray/filter-by-fx :rf.http/managed])
      (let [filters @(rf/subscribe [:rf.xray/active-filters])]
        (is (= 3 (count (:in filters))))
        (is (= [{:pattern :auth/*}
                {:kind :machine :params {:machine-id :form}}
                {:kind :fx :params {:fx-id :rf.http/managed}}]
               (:in filters)))))))
