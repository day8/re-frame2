(ns re-frame.db-pending-trace-test
  "The post-handler-chain (t1) `:rf.event/db-pending` emit (Spec 009 §Canonical
  per-event trace sequence): it fires whenever the handler returned a `:db`
  slot, stamping the pending value by reference under `:tags :rf.event/db`,
  between `:rf.event/run-start` and the commit. The flows-driven t2 emit is
  covered in the flows artefact (`re-frame.flows-t2-trace-test`).

  Every deftest is `^:requires-debug`: under `-Dre-frame.debug=false` the trace
  is a no-op, so there is nothing to assert, and the production-gate lane
  skips the tag while still loading the namespace."
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [re-frame.core :as rf]
            [re-frame.frame :as rf.frame]
            [re-frame.registrar :as rf.registrar]
            [re-frame.schemas :as rf.schemas]
            [re-frame.substrate.plain-atom :as rf.substrate.plain-atom]))

(defn- reset-runtime [test-fn]
  (rf.registrar/clear-all!)
  (reset! rf.frame/frames {})
  (rf.schemas/clear-schemas-by-frame!)
  (rf/init! rf.substrate.plain-atom/adapter)
  (require 're-frame.routing :reload)
  (require 're-frame.ssr     :reload)
  (require 're-frame.machines :reload)
  (rf/make-frame {:id :rf/default})
  (rf/with-frame :rf/default
    (test-fn)))

(use-fixtures :each reset-runtime)

(defn- traces-of
  "The trace events emitted while dispatching `event`."
  [event]
  (let [acc (atom [])]
    (rf/register-listener! :trace ::t1 (fn [ev] (swap! acc conj ev)))
    (try
      (rf/dispatch-sync event)
      @acc
      (finally
        (rf/unregister-listener! :trace ::t1)))))

(deftest ^:requires-debug t1-emits-when-handler-returns-db
  (rf/reg-fx :t1/noop (fn [_ _] :ok))
  (doseq [[label effects expected]
          [["a :db slot stamps one t1 carrying the value and its frame"
            {:db {:counter 42 :seeded? true}}
            [[{:counter 42 :seeded? true} :rf/default]]]
           ["an :fx-only return stamps no t1"
            {:fx [[:t1/noop {}]]}
            []]]]
    (testing label
      (rf/reg-event :t1/returns (fn [_ _] effects))
      (is (= expected
             (->> (traces-of [:t1/returns])
                  (filter #(= :rf.event/db-pending (:operation %)))
                  (map (juxt #(-> % :tags :rf.event/db) #(-> % :tags :frame)))))))))

(deftest ^:requires-debug t1-value-is-identical-by-reference-no-copy
  (testing "on a frame with no classification the stamp is the very reference the
            handler returned — no copy, so the cost is pointer-sized"
    (let [shared-payload {:big (vec (range 1000)) :nested {:k :v}}]
      (rf/reg-event :t1/return-shared (fn [_ _] {:db shared-payload}))
      (is (identical? shared-payload
                      (->> (traces-of [:t1/return-shared])
                           (filter #(= :rf.event/db-pending (:operation %)))
                           first :tags :rf.event/db))))))

(deftest ^:requires-debug t1-precedes-db-changed-and-do-fx
  (testing "run-start -> db-pending -> db-changed -> do-fx -> run-end"
    (rf/reg-fx :t1/tail-fx (fn [_ _] :ok))
    (rf/reg-event :t1/order-probe
      (fn [_ _] {:db {:tick 1} :fx [[:t1/tail-fx {}]]}))
    (let [ops (mapv :operation (traces-of [:t1/order-probe]))
          idx (fn [op] (first (keep-indexed (fn [i x] (when (= x op) i)) ops)))]
      (is (apply < (map idx [:rf.event/run-start :rf.event/db-pending
                             :rf.event/db-changed :rf.fx/do-fx :rf.event/run-end]))))))
