(ns re-frame.events-cljs-test
  "Per rf2-bbea — CLJS-side coverage that `reg-event-*` warns when
  `:interceptors` is mistakenly placed inside the metadata-map.

  The JVM coverage lives in re-frame.events-test; this companion exists
  so the warning fires correctly under the Reagent reactive substrate
  where macro indirection (re-frame.core's `reg-event-db` is a CLJS
  macro that wraps the runtime fn) might otherwise hide the call site.

  ns ends in -cljs-test so shadow-cljs ':node-test' picks it up."
  (:require [cljs.test :refer-macros [deftest is testing use-fixtures]]
            [re-frame.core :as rf]
            [re-frame.adapter.reagent :as reagent-adapter]
            [re-frame.test-support :as test-support])
  (:require-macros [re-frame.test-support :refer [with-trace-recorder!]]))

(use-fixtures :each
  (test-support/make-reset-runtime-fixture
    {:adapter reagent-adapter/adapter}))

(def ^:private noop-icpt
  {:id :test/noop :before identity :after identity})

(def ^:private interceptors-warn-pred
  "Filter predicate shared by the deftests below — matches the
  metadata-map `:interceptors` warning trace."
  (fn [ev]
    (and (= :warning (:op-type ev))
         (= :rf.warning/interceptors-in-metadata-map (:operation ev)))))

(deftest reg-event-db-warns-on-meta-interceptors
  (testing "metadata-map :interceptors triggers :rf.warning/interceptors-in-metadata-map under the Reagent adapter"
    (with-trace-recorder! [warns {:pred interceptors-warn-pred}]
      (rf/reg-event-db :test.bbea.cljs/db-bad
        {:doc "Wrongly-shaped." :interceptors [noop-icpt]}
        (fn [db _] db))
      (is (= 1 (count @warns)))
      (let [tags (:tags (first @warns))]
        (is (= "reg-event-db" (:reg-fn tags)))
        (is (= :test.bbea.cljs/db-bad (:id tags)))))))

(deftest reg-event-fx-warns-on-meta-interceptors
  (with-trace-recorder! [warns {:pred interceptors-warn-pred}]
    (rf/reg-event-fx :test.bbea.cljs/fx-bad
      {:interceptors [noop-icpt]}
      (fn [_ _] {:db {}}))
    (is (= 1 (count @warns)))
    (is (= "reg-event-fx" (:reg-fn (:tags (first @warns)))))))

(deftest reg-event-ctx-warns-on-meta-interceptors
  (with-trace-recorder! [warns {:pred interceptors-warn-pred}]
    (rf/reg-event-ctx :test.bbea.cljs/ctx-bad
      {:interceptors [noop-icpt]}
      (fn [ctx] ctx))
    (is (= 1 (count @warns)))
    (is (= "reg-event-ctx" (:reg-fn (:tags (first @warns)))))))

(deftest correct-positional-form-stays-silent-cljs
  (testing "interceptors in the positional slot do NOT warn"
    (with-trace-recorder! [warns {:pred interceptors-warn-pred}]
      (rf/reg-event-db :test.bbea.cljs/quiet-1
        [noop-icpt]
        (fn [db _] db))
      (rf/reg-event-db :test.bbea.cljs/quiet-2
        {:doc "metadata only"}
        [noop-icpt]
        (fn [db _] db))
      (rf/reg-event-db :test.bbea.cljs/quiet-3
        {:doc "metadata only, no positional interceptors"}
        (fn [db _] db))
      (is (zero? (count @warns))))))
