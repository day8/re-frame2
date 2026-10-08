(ns re-frame.region-order-cljs-test
  "Parallel-region declaration order is the explicit `:region-order`, held
  once the `:regions` map passes eight entries and iterates in hash order
  (which differs between CLJ and CLJS). Every machine here has ten regions,
  so a test passes only on authored order. A >8-region map without a
  `:region-order`, or with one that is not an exact permutation of the
  regions, is refused.

  The `-cljs-test` suffix puts this file on the `:node-test` lane too, so the
  order holds on both hosts."
  (:require [clojure.test :refer [deftest is]]
            [re-frame.machines.parallel :as rf.machines.parallel]))

(def ^:private r10
  "Ten region names in authored declaration order."
  (mapv #(keyword (str "r" %)) (range 10)))

(defn- boot
  [m]
  (rf.machines.parallel/build-initial-snapshot m {:bootstrap-pending? false}))

(defn- go-machine
  "Regions exactly `order`, built through `into {}` so ten of them form a hash
  map. Each region's `:go` appends its id to `[:data :order]`. `:region-order`
  is declared when `include-order?`."
  [order include-order?]
  (let [regions (into {} (map (fn [rn]
                                [rn {:initial :idle
                                     :states  {:idle {:on {:go {:target :idle
                                                                :action rn}}}}}]))
                      order)
        actions (into {} (map (fn [rn]
                                [rn (fn [{d :data}]
                                      {:data (update d :order (fnil conj []) rn)})]))
                      order)]
    (cond-> {:type :parallel :data {:order []} :actions actions :regions regions}
      include-order? (assoc :region-order order))))

(deftest action-data-order-preserved-computed
  ;; The reversed row tells authored order apart from sorted order.
  (doseq [order [r10 (vec (reverse r10))]
          :let  [m (go-machine order true)]]
    (is (= order (get-in (:snapshot (rf.machines.parallel/machine-transition m (boot m) [:go]))
                         [:data :order])))))

(deftest root-multi-target-apply-order-preserved
  ;; The root :on has its own ordering of region-qualified targets.
  (let [actions (into {} (map (fn [rn]
                                [rn (fn [{d :data}]
                                      {:data (update d :entered (fnil conj []) rn)})]))
                      r10)
        regions (into {} (map (fn [rn]
                                [rn {:initial :one
                                     :states  {:one {}
                                               :two {:entry rn}}}]))
                      r10)
        m       {:type :parallel :data {:entered []} :region-order r10
                 :actions actions :regions regions
                 :on {:go {:target (mapv (fn [rn] [rn :two]) r10)}}}]
    (is (= r10 (get-in (:snapshot (rf.machines.parallel/machine-transition m (boot m) [:go]))
                       [:data :entered])))))

(deftest missing-region-order-rejected
  (is (thrown-with-msg?
        #?(:clj Exception :cljs js/Error)
        #":rf.error/machine-parallel-region-order-required"
        (rf.machines.parallel/machine-transition (go-machine r10 false) {:state {} :data {}} [:go]))))

(deftest mismatched-region-order-rejected
  ;; Same count as the regions, one duplicated: only the set comparison refuses it.
  (is (thrown-with-msg?
        #?(:clj Exception :cljs js/Error)
        #":rf.error/machine-parallel-region-order-mismatch"
        (rf.machines.parallel/machine-transition
          (assoc (go-machine r10 false) :region-order (assoc r10 9 :r0))
          {:state {} :data {}} [:go]))))
