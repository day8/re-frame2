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
            [re-frame.test-support :as test-support]))

(use-fixtures :each
  (test-support/reset-runtime-fixture
    {:adapter reagent-adapter/adapter}))

(defn- collect-warnings
  "Attach a listener that records `:rf.warning/interceptors-in-metadata-map`
  events and return the recording atom."
  [k]
  (let [a (atom [])]
    (rf/register-trace-cb! k
      (fn [ev]
        (when (and (= :warning (:op-type ev))
                   (= :rf.warning/interceptors-in-metadata-map (:operation ev)))
          (swap! a conj ev))))
    a))

(def ^:private noop-icpt
  {:id :test/noop :before identity :after identity})

(deftest reg-event-db-warns-on-meta-interceptors
  (testing "metadata-map :interceptors triggers :rf.warning/interceptors-in-metadata-map under the Reagent adapter"
    (let [warns (collect-warnings ::db-warn)]
      (rf/reg-event-db :test.bbea.cljs/db-bad
        {:doc "Wrongly-shaped." :interceptors [noop-icpt]}
        (fn [db _] db))
      (rf/remove-trace-cb! ::db-warn)
      (is (= 1 (count @warns)))
      (let [t (:tags (first @warns))]
        (is (= "reg-event-db" (:reg-fn t)))
        (is (= :test.bbea.cljs/db-bad (:id t)))))))

(deftest reg-event-fx-warns-on-meta-interceptors
  (let [warns (collect-warnings ::fx-warn)]
    (rf/reg-event-fx :test.bbea.cljs/fx-bad
      {:interceptors [noop-icpt]}
      (fn [_ _] {:db {}}))
    (rf/remove-trace-cb! ::fx-warn)
    (is (= 1 (count @warns)))
    (is (= "reg-event-fx" (:reg-fn (:tags (first @warns)))))))

(deftest reg-event-ctx-warns-on-meta-interceptors
  (let [warns (collect-warnings ::ctx-warn)]
    (rf/reg-event-ctx :test.bbea.cljs/ctx-bad
      {:interceptors [noop-icpt]}
      (fn [ctx] ctx))
    (rf/remove-trace-cb! ::ctx-warn)
    (is (= 1 (count @warns)))
    (is (= "reg-event-ctx" (:reg-fn (:tags (first @warns)))))))

(deftest correct-positional-form-stays-silent-cljs
  (testing "interceptors in the positional slot do NOT warn"
    (let [warns (collect-warnings ::quiet)]
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
      (rf/remove-trace-cb! ::quiet)
      (is (zero? (count @warns))))))
