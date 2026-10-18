(ns re-frame.schemas-cljs-test
  "CLJS checks for Spec 010 schema validation under the Reagent substrate:
  the default Malli bundle validates on this host, and a development build
  carries its humanizer. The platform-neutral validation surface is covered
  by re-frame.schemas-test."
  (:require [cljs.test :refer-macros [deftest is testing use-fixtures]]
            [re-frame.core :as rf]
            [re-frame.schemas :as rf.schemas]
            [re-frame.adapter.reagent :as rf.adapter.reagent]
            [re-frame.test-support :as rf.test-support])
  (:require-macros [re-frame.test-support :refer [with-trace-recorder!]]))

;; Snapshot/restore rather than clear: CLJS cannot reload the framework
;; registrations a clear would wipe, and the restore hands ns-load app-db
;; schemas back to downstream tests.
(use-fixtures :each
  (rf.test-support/make-reset-runtime-fixture
    {:adapter rf.adapter.reagent/adapter}))

(defn- failures [traces]
  (filterv #(= :rf.error/schema-validation-failure (:operation %)) @traces))

;; The schema shape of the schema_violation testbed: a `:cat` whose tail is a
;; bare predicate fn, under a frame that also carries an app-db schema.
(deftest app-schema-present-sync-bad-event-args-fires-where-event
  (testing "with an app-db schema registered for the frame, a `:cat` event
            schema with a bare `pos-int?` tail runs the handler on a good arg
            and, on a bad one, skips it and emits :where :event"
    (rf/reg-app-schema [:auth] [:map [:token :string]])
    (let [calls (atom 0)]
      (rf/reg-event :rf2-lo28u/diag-bad-event-args
        {:schema [:cat [:= :rf2-lo28u/diag-bad-event-args] pos-int?]}
        (fn [{:keys [db]} _ev] (swap! calls inc) {:db db}))
      (with-trace-recorder! [traces]
        (rf/dispatch-sync [:rf2-lo28u/diag-bad-event-args 7])
        (rf/dispatch-sync [:rf2-lo28u/diag-bad-event-args "not-a-number"])
        (is (= 1 @calls) "the handler ran for the good arg only")
        (is (= 1 (count (filter #(= :event (-> % :tags :where)) (failures traces))))
            "exactly one :where :event violation fired")))))

;; The dual, no humanizer under `:advanced` + `goog.DEBUG=false`, is pinned in
;; schemas_boundary_prod_test.cljs.
(deftest humanizer-published-in-dev-cljs
  (testing "under goog.DEBUG=true the default bundle carries the humanizer, so
            an app-db failure carries Malli's :explain-humanized payload"
    (rf/reg-app-schema [:auth :token] [:string])
    (with-trace-recorder! [traces]
      (rf.schemas/validate-app-schema! {:auth {:token 42}} :auth/init-bad)
      (is (= [["should be a string"]]
             (map (comp :explain-humanized :tags) (failures traces)))))))
