(ns re-frame.flows-direct-reg-deferral-cljs-test
  "Spec 013 §Why a direct `reg-flow` does not settle, on both hosts. A direct
  registration declares and the next drain evaluates, even when the flow's
  inputs are already seeded: boot code registers before app-db is seeded, and
  a `:derive` need not be total on absent inputs. A direct replacement leaves
  the previous derive's value in its slot, stale but owned by a live flow,
  until that drain. A direct clear settles instead, because what it leaves
  behind has no owner (`re-frame.flows-direct-clear-settle-cljs-test`).

  `app-db-value` is a pure read, so observing cannot trigger a pass."
  (:require
   #?(:clj  [clojure.test :refer [deftest is use-fixtures]]
      :cljs [cljs.test :refer-macros [deftest is use-fixtures]])
   [re-frame.core :as rf]
   [re-frame.flows :as rf.flows]
   [re-frame.test-support :as rf.test-support]
   #?(:clj  [re-frame.substrate.plain-atom :as substrate]
      :cljs [re-frame.adapter.reagent :as substrate])))

(use-fixtures :each
  (rf.test-support/make-reset-runtime-fixture {:adapter substrate/adapter}))

(defn- db [] (rf/app-db-value :rf/default))

(defn- reg-seed-and-noop! []
  (rf/reg-event :seed (fn [_ _] {:db {:x 2}}))
  (rf/reg-event :noop (fn [_ _] {})))

(deftest direct-cold-reg-flow-defers-initial-output-to-the-next-drain
  (let [derives (atom 0)]
    (reg-seed-and-noop!)
    ;; Seeded first, so an absent output can only mean the derive did not run.
    (rf/dispatch-sync [:seed])
    (rf.flows/reg-flow :probe/a {:inputs [[:x]] :output-path [:a]}
      (fn [x] (swap! derives inc) x))
    (is (= [{:x 2} 0] [(db) @derives]))
    (rf/dispatch-sync [:noop])
    (is (= [{:x 2 :a 2} 1] [(db) @derives]) "the next drain evaluates it, once")))

(deftest direct-reg-flow-replacement-defers-recompute-to-the-next-drain
  (reg-seed-and-noop!)
  (rf.flows/reg-flow :probe/a {:inputs [[:x]] :output-path [:a]} identity)
  (rf/dispatch-sync [:seed])
  (rf.flows/reg-flow :probe/a {:inputs [[:x]] :output-path [:a]} (fn [x] (* 10 x)))
  (is (= {:x 2 :a 2} (db)) "the slot keeps the previous derive's value")
  (rf/dispatch-sync [:noop])
  (is (= {:x 2 :a 20} (db)) "the next drain re-evaluates with the new derive"))
