(ns re-frame.schemas-cljs-test
  "CLJS checks for Spec 010 schema validation under the Reagent substrate:
  Malli's late-bound validator is wired on this host, an unbound hook
  soft-passes, and a development build publishes the humanizer. The
  platform-neutral validation surface is covered by re-frame.schemas-test."
  (:require [cljs.test :refer-macros [deftest is testing use-fixtures]]
            [re-frame.core :as rf]
            [re-frame.late-bind :as rf.late-bind]
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

(deftest cljs-unbound-validate-hook-soft-passes
  (testing "Per Spec 010 §Recommended soft-pass: with `:schemas/malli-validate`
            unbound (a substitute port that never published it) the default
            validator passes every value"
    (let [prior (rf.late-bind/get-fn :schemas/malli-validate)]
      (rf.late-bind/set-fn! :schemas/malli-validate nil)
      (try
        (rf/reg-app-schema [:n] :int)
        (rf/reg-event :n/break (fn [{:keys [db]} _] {:db (assoc db :n "definitely-not-an-int")}))
        (with-trace-recorder! [traces]
          (rf/dispatch-sync [:n/break])
          (is (empty? (failures traces))))
        (finally
          (rf.late-bind/set-fn! :schemas/malli-validate prior))))))

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

;; The dual, the hook unbound under `:advanced` + `goog.DEBUG=false`, is pinned
;; in schemas_boundary_prod_test.cljs.
(deftest humanizer-published-in-dev-cljs
  (testing "under goog.DEBUG=true the adapter has published the humanizer, so
            an app-db failure carries Malli's :explain-humanized payload"
    (rf/reg-app-schema [:auth :token] [:string])
    (with-trace-recorder! [traces]
      (rf.schemas/validate-app-schema! {:auth {:token 42}} :auth/init-bad)
      (is (= [["should be a string"]]
             (map (comp :explain-humanized :tags) (failures traces)))))))
