(ns re-frame.frozen-region-select-test
  "Per Spec 005 §Transition broadcast (select-then-apply): every region's
  transition is selected against one frozen pre-event view of `:data`,
  `:all-state` and `:tags`, then applied in declaration order with `:data`
  accumulating. Actions read the same frozen `:all-state` / `:tags`."
  (:require [clojure.test :refer [deftest is]]
            [re-frame.machines :as rf.machines]))

(deftest action-all-state-frozen-data-accumulates
  (let [seen (atom nil)
        m    {:type    :parallel
              :actions {:rec-a (fn [{:keys [data]}] {:data (update data :log conj :a)})
                        :rec-b (fn [{:keys [all-state tags data]}]
                                 (reset! seen {:all-state all-state :tags tags :log (:log data)})
                                 {:data (update data :log conj :b)})}
              :regions {:a {:initial :start
                            :states  {:start {:tags #{:a/start}
                                              :on   {:go {:target :end :action :rec-a}}}
                                      :end   {:tags #{:a/end}}}}
                        :b {:initial :one
                            :states  {:one {:on {:go {:target :two :action :rec-b}}}
                                      :two {}}}}}]
    (rf.machines/machine-transition m {:state {:a :start :b :one} :data {:log []}} [:go])
    (is (= {:all-state {:a :start :b :one} :tags #{:a/start} :log [:a]} @seen)
        "b's action sees a's pre-event state and tag, and the :data a's action wrote")))

(defn- data-order-machine
  "Region `:a` bumps `:x` on `:go`; region `:b` takes `:go` only if `:x` is
  positive. Regions are declared in `region-order`."
  [region-order]
  (let [bodies {:a {:initial :idle
                    :states  {:idle {:on {:go {:target :done :action :bump}}}
                              :done {}}}
                :b {:initial :idle
                    :states  {:idle {:on {:go {:target :fire :guard :x-pos?}}}
                              :fire {}}}}]
    {:type    :parallel
     :guards  {:x-pos? (fn [{:keys [data]}] (pos? (:x data)))}
     :actions {:bump (fn [{:keys [data]}] {:data (update data :x inc)})}
     :regions (into {} (map (fn [rn] [rn (get bodies rn)])) region-order)}))

(deftest selection-declaration-order-independent
  ;; Declared a-then-b, an unfrozen :data view would let a's bump open b.
  (is (= [{:state {:a :done :b :idle} :data {:x 1}}
          {:state {:a :done :b :idle} :data {:x 1}}]
         (for [order [[:a :b] [:b :a]]]
           (-> (rf.machines/machine-transition (data-order-machine order)
                                               {:state {:a :idle :b :idle} :data {:x 0}} [:go])
               :snapshot
               (select-keys [:state :data]))))))
