(ns re-frame.machine-transition-handled-test
  "The pure Level-1 `machine-transition` reports `:handled?` on its `:ok` map
  (Spec 005 §Testing Level 1): true when the event selected a transition —
  even a targetless one that changed nothing — and false when nothing took
  it, because no transition matched or every candidate's guard declined.

  Flat, compound and `:type :parallel` machines alike. The `:status :error`
  map carries no `:handled?`: the macrostep failed, so there is no answer."
  (:require [clojure.test :refer [deftest is testing]]
            [re-frame.machines :as rf.machines]))

(def ^:private flat
  {:initial :idle
   :data    {:key? false}
   :guards  {:has-key? (fn [{:keys [data]}] (:key? data))}
   :actions {:noop (fn [_] nil)
             :boom (fn [_] (throw (ex-info "boom" {})))}
   :on      {:root-noop {:action :noop}}
   :states  {:idle {:on {:go      :busy
                         :open    {:target :busy :guard :has-key?}
                         :consume {}
                         :noop    {:action :noop}
                         :explode {:target :busy :action :boom}}}
             :busy {}}})

(defn- step [machine snapshot event]
  (rf.machines/machine-transition machine snapshot event))

(def ^:private at-idle {:state :idle :data {:key? false}})

(deftest a-taken-transition-is-handled
  (testing "a matched transition that moves the machine"
    (let [r (step flat at-idle [:go])]
      (is (= :busy (get-in r [:snapshot :state])))
      (is (true? (:handled? r)))))
  (testing "a guard that passes"
    (is (true? (:handled? (step flat {:state :idle :data {:key? true}} [:open]))))))

(deftest a-taken-transition-that-changes-nothing-is-handled
  (testing "a targetless, actionless consumer"
    (let [r (step flat at-idle [:consume])]
      (is (= at-idle (select-keys (:snapshot r) [:state :data])))
      (is (= [] (:fx r)))
      (is (true? (:handled? r)))))
  (testing "a targetless action that returns nil"
    (is (true? (:handled? (step flat at-idle [:noop])))))
  (testing "the root :on's targetless action"
    (is (true? (:handled? (step flat at-idle [:root-noop]))))))

(deftest an-event-nothing-took-is-not-handled
  (testing "every candidate's guard declined"
    (let [r (step flat at-idle [:open])]
      (is (= :ok (:status r)))
      (is (= :idle (get-in r [:snapshot :state])))
      (is (false? (:handled? r)))))
  (testing "no transition matched"
    (let [r (step flat at-idle [:no-such-event])]
      (is (= :ok (:status r)))
      (is (= [] (:fx r)))
      (is (false? (:handled? r))))))

(deftest an-error-carries-no-handled-flag
  (let [r (step flat at-idle [:explode])]
    (is (= :error (:status r)))
    (is (not (contains? r :handled?)))))

(def ^:private compound
  {:initial :outer
   :on      {:root-only :done}
   :states  {:outer {:initial :inner
                     :on      {:up :done}
                     :states  {:inner {}}}
             :done  {}}})

(deftest a-compound-machine-reports-the-ancestor-that-took-it
  (let [at-inner {:state [:outer :inner] :data {}}]
    (is (true? (:handled? (step compound at-inner [:up]))) "the parent :on took it")
    (is (true? (:handled? (step compound at-inner [:root-only]))) "the root :on took it")
    (is (false? (:handled? (step compound at-inner [:nobody]))) "nothing took it")))

(def ^:private parallel
  {:type    :parallel
   :guards  {:never? (fn [_] false)}
   :on      {:root-reset {:target [:a :a1]}}
   :regions {:a {:initial :a1 :states {:a1 {:on {:a-go :a2 :blocked {:target :a2 :guard :never?}}}
                                       :a2 {}}}
             :b {:initial :b1 :states {:b1 {:on {:b-go :b2}} :b2 {}}}}})

(def ^:private par-snap {:state {:a :a1 :b :b1} :data {}})

(deftest a-parallel-machine-is-handled-when-any-region-or-the-root-takes-it
  (testing "one region takes it"
    (let [r (step parallel par-snap [:b-go])]
      (is (= {:a :a1 :b :b2} (get-in r [:snapshot :state])))
      (is (true? (:handled? r)))))
  (testing "the root :on takes it"
    (is (true? (:handled? (step parallel par-snap [:root-reset])))))
  (testing "every region declines"
    (is (false? (:handled? (step parallel par-snap [:blocked])))))
  (testing "no region and not the root"
    (is (false? (:handled? (step parallel par-snap [:nobody]))))))
