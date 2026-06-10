(ns re-frame.events-cljs-test
  "Per rf2-bpmszk (the rf2-iczn3 resolution) — CLJS-side coverage that the
  `reg-event-*` metadata-map `:interceptors` superset form threads the chain
  under the Reagent reactive substrate, and that the both-places guard fires.

  This SUPERSEDES the former rf2-bbea CLJS coverage (which asserted
  `:rf.warning/interceptors-in-metadata-map` fired): `:interceptors` inside the
  metadata-map is now the documented home, not a typo. The JVM coverage lives
  in re-frame.events-test; this companion exists so the superset form resolves
  correctly under the Reagent substrate where macro indirection (re-frame.core's
  `reg-event-db` is a CLJS macro that wraps the runtime fn) might otherwise hide
  the call site.

  ns ends in -cljs-test so shadow-cljs ':node-test' picks it up."
  (:require [cljs.test :refer-macros [deftest is testing use-fixtures]]
            [re-frame.core :as rf]
            [re-frame.adapter.reagent :as reagent-adapter]
            [re-frame.test-support :as test-support]))

(use-fixtures :each
  (test-support/make-reset-runtime-fixture
    {:adapter reagent-adapter/adapter}))

(def ^:private noop-icpt
  {:id :test/noop :before identity :after identity})

(deftest reg-event-db-metadata-interceptors-threads-the-chain
  (testing "metadata-map :interceptors threads the chain under the Reagent adapter"
    (rf/reg-event-db :test.bpmszk.cljs/db-super
      {:doc "Superset form." :interceptors [noop-icpt]}
      (fn [db _] db))
    (let [meta (rf/handler-meta :event :test.bpmszk.cljs/db-super)
          ids  (mapv :id (:interceptors meta))]
      (is (= "Superset form." (:doc meta)))
      (is (= [:test/noop :rf/db-handler] ids)
          "the metadata-map :interceptors chain sits before the runtime wrapper"))))

(deftest reg-event-fx-metadata-interceptors-threads-the-chain
  (rf/reg-event-fx :test.bpmszk.cljs/fx-super
    {:interceptors [noop-icpt]}
    (fn [_ _] {:db {}}))
  (let [ids (mapv :id (:interceptors (rf/handler-meta :event :test.bpmszk.cljs/fx-super)))]
    (is (= [:test/noop :rf/fx-handler] ids))))

(deftest reg-event-ctx-metadata-interceptors-threads-the-chain
  (rf/reg-event-ctx :test.bpmszk.cljs/ctx-super
    {:interceptors [noop-icpt]}
    (fn [ctx] ctx))
  (let [ids (mapv :id (:interceptors (rf/handler-meta :event :test.bpmszk.cljs/ctx-super)))]
    (is (= [:test/noop :rf/ctx-handler] ids))))

(deftest both-places-guard-fires-under-reagent
  (testing "metadata-map :interceptors + positional vector throws :rf.error/interceptors-supplied-twice"
    (is (thrown-with-msg?
          cljs.core/ExceptionInfo
          #":rf\.error/interceptors-supplied-twice"
          (rf/reg-event-db :test.bpmszk.cljs/twice
            {:interceptors [noop-icpt]}
            [{:id :other :before identity}]
            (fn [db _] db))))))

(deftest malformed-metadata-interceptors-fires-under-reagent
  (testing "a non-vector :interceptors value throws :rf.error/reg-event-bad-interceptors"
    (is (thrown-with-msg?
          cljs.core/ExceptionInfo
          #":rf\.error/reg-event-bad-interceptors"
          (rf/reg-event-db :test.bpmszk.cljs/bad
            {:interceptors noop-icpt}
            (fn [db _] db))))))

(deftest sugar-equivalence-under-reagent
  (testing "[i] and {:interceptors [i]} register the identical effective chain"
    (rf/reg-event-db :test.bpmszk.cljs/via-vector
      [noop-icpt]
      (fn [db _] db))
    (rf/reg-event-db :test.bpmszk.cljs/via-map
      {:interceptors [noop-icpt]}
      (fn [db _] db))
    (let [vec-ids (mapv :id (:interceptors (rf/handler-meta :event :test.bpmszk.cljs/via-vector)))
          map-ids (mapv :id (:interceptors (rf/handler-meta :event :test.bpmszk.cljs/via-map)))]
      (is (= vec-ids map-ids)
          "both forms register the identical effective chain (ids + order)"))))
