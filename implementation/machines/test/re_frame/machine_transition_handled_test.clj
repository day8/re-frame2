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
   :guards  {:has-key? (fn [{:keys [data]}] (:key? data))}
   :actions {:boom (fn [_] (throw (ex-info "boom" {})))}
   :states  {:idle {:on {:go      :busy
                         :open    {:target :busy :guard :has-key?}
                         :consume {}
                         :explode {:target :busy :action :boom}}}
             :busy {}}})

(defn- step [machine snapshot event]
  (rf.machines/machine-transition machine snapshot event))

(def ^:private at-idle {:state :idle :data {:key? false}})

(deftest a-flat-machine-is-handled-exactly-when-a-transition-is-selected
  (doseq [[event state handled?] [[:go            :busy true]    ;; a transition that moves the machine
                                  [:consume       :idle true]    ;; a targetless consumer that changes nothing
                                  [:open          :idle false]   ;; every candidate's guard declined
                                  [:no-such-event :idle false]]] ;; no transition matched
    (is (= {:status :ok :snapshot (assoc at-idle :state state) :fx [] :handled? handled?}
           (step flat at-idle [event]))
        (str event))))

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
    (is (true? (:handled? (step compound at-inner [:root-only]))) "the root :on took it")))

(def ^:private parallel
  {:type    :parallel
   :guards  {:never? (fn [_] false)}
   :on      {:root-reset {:target [:a :a1]}}
   :regions {:a {:initial :a1 :states {:a1 {:on {:blocked {:target :a2 :guard :never?}}}
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
    (is (false? (:handled? (step parallel par-snap [:blocked]))))))
