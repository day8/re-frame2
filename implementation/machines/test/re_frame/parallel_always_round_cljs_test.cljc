(ns re-frame.parallel-always-round-cljs-test
  "Cross-region `:always` is a parent-owned select-then-apply round loop:
  rounds select after the complete event set and converge birth whatever the
  region order, co-selected regions are one round, eventless work precedes the
  raise queue, failure is atomic, and a birth with all its rounds commits
  through one write."
  (:require [clojure.test :refer [deftest is use-fixtures]]
            [re-frame.core :as rf]
            [re-frame.frame :as rf.frame]
            [re-frame.machines]
            [re-frame.machines.parallel :as rf.machines.parallel]
            [re-frame.machines.result :as rf.machines.result]
            [re-frame.machines.test-support :as rf.machines.test-support]
            [re-frame.substrate.plain-atom :as rf.substrate.plain-atom]
            [re-frame.trace :as rf.trace]))

(use-fixtures :each
  (rf.machines.test-support/make-reset-runtime-fixture {:adapter rf.substrate.plain-atom/adapter}))

(defn- initial-snapshot [m]
  (rf.machines.parallel/build-initial-snapshot m {:bootstrap-pending? false}))

(defn- transition [m event]
  (rf.machines.parallel/machine-transition m (initial-snapshot m) event))

(defn- ordered-regions [order bodies]
  (into {} (map (fn [rn] [rn (get bodies rn)])) order))

(defn- event-order-machine [order guard-reads]
  {:type :parallel
   :data {:a? false :b? false}
   :region-order order
   :actions {:mark-a (fn [{:keys [data]}] {:data (assoc data :a? true)})
             :mark-b (fn [{:keys [data]}] {:data (assoc data :b? true)})}
   :guards {:both-marked?
            (fn [{:keys [data]}]
              (swap! guard-reads conj (select-keys data [:a? :b?]))
              (and (:a? data) (:b? data)))}
   :regions (ordered-regions
              order
              {:a {:initial :idle
                   :states {:idle {:on {:go {:target :done :action :mark-a}}}
                            :done {}}}
               :b {:initial :idle
                   :states {:idle    {:on {:go {:target :waiting :action :mark-b}}}
                            :waiting {:always {:target :ready :guard :both-marked?}}
                            :ready   {}}}})})

(deftest event-round-is-order-invariant-and-sees-complete-event-set
  (let [reads (atom [])
        settle #(select-keys (:snapshot (transition (event-order-machine % reads) [:go]))
                             [:state :data])]
    (is (= [{:state {:a :done :b :ready} :data {:a? true :b? true}}
            {:state {:a :done :b :ready} :data {:a? true :b? true}}]
           [(settle [:a :b]) (settle [:b :a])]))
    (is (every? #(= {:a? true :b? true} %) @reads)
        "the first eventless SELECT happens only after both event actions")))

(defn- birth-order-machine [order]
  {:type :parallel
   :data {:a? false}
   :region-order order
   :actions {:mark-a (fn [{:keys [data]}] {:data (assoc data :a? true)})}
   :guards {:a-marked? (fn [{:keys [data]}] (:a? data))}
   :regions (ordered-regions
              order
              {:a {:initial :boot
                   :states {:boot {:always {:target :done :action :mark-a}}
                            :done {}}}
               :b {:initial :waiting
                   :states {:waiting {:always {:target :ready :guard :a-marked?}}
                            :ready {}}}})})

(deftest birth-rounds-are-order-invariant
  ;; Every region enters before the parent loop: A writes in round 0, B reads
  ;; it from a fresh freeze in round 1, whatever the declaration order.
  (let [birth #(let [m (birth-order-machine %)
                     r (rf.machines.parallel/apply-initial-entry-cascade m (initial-snapshot m))]
                 [(select-keys (:snapshot r) [:state :data]) (rf.machines.result/microsteps r)])]
    (is (= [[{:state {:a :done :b :ready} :data {:a? true}} 2]
            [{:state {:a :done :b :ready} :data {:a? true}} 2]]
           [(birth [:a :b]) (birth [:b :a])]))))

(deftest co-selected-regions-are-one-observable-round
  (let [emits (atom [])
        staged (fn [action] {:initial :idle
                             :states {:idle {:on {:go :staged}}
                                      :staged {:always {:target :done :action action}}
                                      :done {}}})
        logs (fn [k] (fn [{:keys [data]}] {:data (update data :log conj k) :fx [[:note k]]}))
        m {:type :parallel
           :data {:log []}
           :region-order [:a :b]
           :actions {:a-always (logs :a) :b-always (logs :b)}
           :regions {:a (staged :a-always) :b (staged :b-always)}}
        r (with-redefs [rf.trace/emit! (fn [& xs] (swap! emits conj xs))]
            (transition m [:go]))]
    (is (= {:microsteps 1
            :cascade    [[:a 0] [:b 0]]
            :traces     [[:a 0] [:b 0]]
            :log        [:a :b]
            :fx         [[:note :a] [:note :b]]}
           {:microsteps (rf.machines.result/microsteps r)
            :cascade    (->> (rf.machines.result/cascade r)
                             (filter #(= :microstep (:kind %)))
                             (mapv (juxt :region :microstep-index)))
            :traces     (->> @emits
                             (filter #(= :rf.machine.microstep/transition (second %)))
                             (mapv #((juxt :region :microstep-index) (last %))))
            :log        (get-in (:snapshot r) [:data :log])
            :fx         (:fx r)}))))

(deftest eventless-round-precedes-raised-fifo
  (let [logs (fn [k] (fn [{:keys [data]}] {:data (update data :log conj k)}))
        m {:type :parallel
           :data {:log []}
           :region-order [:a :b]
           :actions {:event  (fn [{:keys [data]}]
                               {:data (update data :log conj :event) :fx [[:raise [:tick]]]})
                     :always (logs :always)
                     :raised (logs :raised)}
           :regions
           {:a {:initial :idle
                :states {:idle {:on {:go {:target :mid :action :event}}}
                         :mid {:always {:target :done :action :always}}
                         :done {}}}
            :b {:initial :waiting
                :states {:waiting {:on {:tick {:target :seen :action :raised}}}
                         :seen {}}}}}]
    (is (= [:event :always :raised]
           (get-in (transition m [:go]) [:snapshot :data :log])))))

(deftest parent-always-action-failure-is-atomic
  (let [m {:type :parallel
           :data {}
           :region-order [:a :b]
           :actions {:boom (fn [_] (throw (ex-info "boom" {})))}
           :regions
           {:a {:initial :idle
                :states {:idle {:on {:go :armed}}
                         :armed {:always {:target :done :action :boom}}
                         :done {}}}
            :b {:initial :idle
                :states {:idle {:on {:go :moved}} :moved {}}}}}]
    (is (= {:status :error} (dissoc (transition m [:go]) :error))
        "a failed macrostep publishes no partial snapshot or effects")))

(deftest parent-always-depth-counts-rounds-and-rolls-back
  (let [m {:type :parallel
           :data {}
           :always-depth-limit 2
           :region-order [:a]
           :regions
           {:a {:initial :idle
                :states {:idle {:on {:go :x}}
                         :x {:always :y}
                         :y {:always :x}}}}}]
    (is (= {:status :error
            :error  {:error-id :rf.error/machine-always-depth-exceeded :depth 2}}
           (update (transition m [:go]) :error select-keys [:error-id :depth])))))

(deftest all-rounds-publish-once
  (rf/reg-machine :parallel-round/publication (birth-order-machine [:b :a]))
  (rf/make-frame {:id :parallel-round/frame :doc "parent always round publication fixture"})
  ;; The first dispatch performs lazy birth (both parent rounds) and the
  ;; declined external event in one handler result.
  (let [container (rf.frame/frame-state-container :parallel-round/frame)
        writes    (atom 0)]
    (add-watch container ::publication
               (fn [_ _ before after] (when (not= before after) (swap! writes inc))))
    (try
      (rf/dispatch-sync [:parallel-round/publication [:poke]] {:frame :parallel-round/frame})
      (finally (remove-watch container ::publication)))
    (is (= [1 {:a :done :b :ready}]
           [@writes (:state (rf.machines.test-support/snapshot :parallel-round/frame
                                                               :parallel-round/publication))]))))
