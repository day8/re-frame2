(ns day8.re-frame2-causa.filters.typed-predicates-events-cljs-test
  "CLJS integration tests for the typed-predicate `:rf.causa/filter-by-*`
  events (rf2-piye4).

  Asserts that:
   - each typed-add event lands a `{:kind … :params …}` pill in the IN
     bucket of `:rf.causa/active-filters`;
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
            [day8.re-frame2-causa.registry :as registry]
            [day8.re-frame2-causa.test-support :as causa-test-support]))

(defn- causa-init! []
  (causa-test-support/reset-all!))

(use-fixtures :each
  (test-support/make-reset-runtime-fixture
    {:adapter plain-atom/adapter
     :init-fn causa-init!}))

(defn- causa-setup! []
  (registry/register-causa-handlers!)
  (frame/reg-frame :rf/causa {}))

;; ---- :rf.causa/filter-by-machine ----------------------------------------

(deftest filter-by-machine-appends-typed-pill
  (causa-setup!)
  (rf/with-frame :rf/causa
    (rf/dispatch-sync [:rf.causa/filter-by-machine :form])
    (let [filters @(rf/subscribe [:rf.causa/active-filters])]
      (is (= [{:kind :machine :params {:machine-id :form}}]
             (:in filters))
          "machine pill landed in IN bucket")
      (is (= [] (:out filters))))))

(deftest filter-by-machine-idempotent
  (testing "duplicate add with the same machine-id collapses to one pill"
    (causa-setup!)
    (rf/with-frame :rf/causa
      (rf/dispatch-sync [:rf.causa/filter-by-machine :form])
      (rf/dispatch-sync [:rf.causa/filter-by-machine :form])
      (rf/dispatch-sync [:rf.causa/filter-by-machine :form])
      (let [filters @(rf/subscribe [:rf.causa/active-filters])]
        (is (= 1 (count (:in filters)))
            "three adds → one pill")))))

(deftest filter-by-machine-distinct-ids-accumulate
  (causa-setup!)
  (rf/with-frame :rf/causa
    (rf/dispatch-sync [:rf.causa/filter-by-machine :form])
    (rf/dispatch-sync [:rf.causa/filter-by-machine :auth])
    (let [filters @(rf/subscribe [:rf.causa/active-filters])]
      (is (= 2 (count (:in filters))))
      (is (= #{:form :auth}
             (set (map #(get-in % [:params :machine-id]) (:in filters))))))))

;; ---- :rf.causa/filter-by-http-correlation -------------------------------

(deftest filter-by-http-correlation-appends-typed-pill
  (causa-setup!)
  (rf/with-frame :rf/causa
    (rf/dispatch-sync [:rf.causa/filter-by-http-correlation "abc-123"])
    (let [filters @(rf/subscribe [:rf.causa/active-filters])]
      (is (= [{:kind :http-correlation :params {:correlation-id "abc-123"}}]
             (:in filters))))))

(deftest filter-by-http-correlation-idempotent
  (causa-setup!)
  (rf/with-frame :rf/causa
    (rf/dispatch-sync [:rf.causa/filter-by-http-correlation "abc-123"])
    (rf/dispatch-sync [:rf.causa/filter-by-http-correlation "abc-123"])
    (is (= 1 (count (:in @(rf/subscribe [:rf.causa/active-filters])))))))

;; ---- :rf.causa/filter-by-fx ---------------------------------------------

(deftest filter-by-fx-appends-typed-pill
  (causa-setup!)
  (rf/with-frame :rf/causa
    (rf/dispatch-sync [:rf.causa/filter-by-fx :rf.http/managed])
    (let [filters @(rf/subscribe [:rf.causa/active-filters])]
      (is (= [{:kind :fx :params {:fx-id :rf.http/managed}}]
             (:in filters))))))

(deftest filter-by-fx-idempotent
  (causa-setup!)
  (rf/with-frame :rf/causa
    (rf/dispatch-sync [:rf.causa/filter-by-fx :rf.http/managed])
    (rf/dispatch-sync [:rf.causa/filter-by-fx :rf.http/managed])
    (is (= 1 (count (:in @(rf/subscribe [:rf.causa/active-filters])))))))

;; ---- mixed typed + legacy keyword pills ---------------------------------

(deftest mixed-typed-and-legacy-pills-coexist
  (testing "the rf2-ak4ms add-filter path (legacy `{:pattern ...}`) and
            the rf2-piye4 filter-by-* paths can populate the same IN
            bucket without stepping on each other"
    (causa-setup!)
    (rf/with-frame :rf/causa
      (rf/dispatch-sync [:rf.causa/add-filter :in {:pattern :auth/*}])
      (rf/dispatch-sync [:rf.causa/filter-by-machine :form])
      (rf/dispatch-sync [:rf.causa/filter-by-fx :rf.http/managed])
      (let [filters @(rf/subscribe [:rf.causa/active-filters])]
        (is (= 3 (count (:in filters))))
        (is (= [{:pattern :auth/*}
                {:kind :machine :params {:machine-id :form}}
                {:kind :fx :params {:fx-id :rf.http/managed}}]
               (:in filters)))))))
