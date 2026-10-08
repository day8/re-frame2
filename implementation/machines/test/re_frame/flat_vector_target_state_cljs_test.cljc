(ns re-frame.flat-vector-target-state-cljs-test
  "A vector `:target` commits the machine's own state shape (Spec 005 §Snapshot
  shape): a flat machine or flat region commits the leaf keyword, a compound
  region keeps the vector path, a root-level leaf included."
  (:require
   #?(:clj  [clojure.test :refer [deftest is]]
      :cljs [cljs.test :refer-macros [deftest is]])
   [re-frame.machines :as rf.machines]))

(defn- state-after
  "The `:state` the pure transition commits for `event` from `state`."
  [machine state event]
  (get-in (rf.machines/machine-transition machine {:state state :data {}} event)
          [:snapshot :state]))

(deftest flat-machine-vector-target-commits-a-keyword
  (is (= [:b :b]
         [(state-after {:initial :a :states {:a {:on {:go [:b]}} :b {}}} :a [:go])
          (state-after {:initial :a :states {:a {:on {:go {:target [:b]}}} :b {}}} :a [:go])])))

(def ^:private regions
  {:type    :parallel
   :on      {:jump [:flat :b]}
   :regions {:flat     {:initial :a
                        :states  {:a {:on {:go     [:b]
                                           :go-map {:target [:b]}}}
                                  :b {}}}
             :compound {:initial :x
                        :states  {:x {:on {:dive [:y :deep]}}
                                  :y {:initial :deep
                                      :states  {:deep {:on {:leave [:x]}}}}}}}})

(deftest flat-region-vector-target-commits-a-keyword
  ;; Includes a root region-qualified target; the sibling region is untouched.
  (is (= (repeat 3 {:flat :b :compound [:x]})
         (map #(state-after regions {:flat :a :compound [:x]} %) [[:go] [:go-map] [:jump]]))))

(deftest compound-region-vector-target-commits-its-path
  (is (= [{:flat :a :compound [:y :deep]} {:flat :a :compound [:x]}]
         [(state-after regions {:flat :a :compound [:x]} [:dive])
          (state-after regions {:flat :a :compound [:y :deep]} [:leave])])))
