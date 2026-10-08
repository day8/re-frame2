(ns re-frame.timeout-cljs-test
  "State- and spawn-level `:timeout` / `:on-timeout`: the integer-ms / ISO-8601
  duration grammar, the registration-time refusals, and the lowering onto `:after`."
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [re-frame.core :as rf]
            [re-frame.machines]
            [re-frame.machines.test-support :as rf.machines.test-support]
            [re-frame.machines.timeout :as rf.machines.timeout]
            #?(:clj  [re-frame.substrate.plain-atom :as substrate-adapter]
               :cljs [re-frame.adapter.reagent :as substrate-adapter])
            [re-frame.subs]))

(use-fixtures :each
  (rf.machines.test-support/make-reset-runtime-fixture {:adapter substrate-adapter/adapter}))

(deftest duration-grammar
  ;; Integer ms or ISO-8601 only: the XState "5s" shorthand and the fn / vector
  ;; delays `:after` admits resolve to nil.
  (let [rows [[5000 5000] [1 1] [0 nil] [1.5 nil]
              ["PT5S" 5000] ["PT1H30M" 5400000] ["PT0.5S" 500] ["P1D" 86400000] ["pt5s" 5000]
              ["P" nil] ["5s" nil] [(fn [_] 5000) nil]]]
    (is (= rows (mapv (fn [[d _]] [d (rf.machines.timeout/resolve-duration-ms d)]) rows)))))

(defn- reg-error-id [machine]
  (try (rf/reg-machine (keyword "tt" (str (gensym))) machine) nil
       (catch #?(:clj clojure.lang.ExceptionInfo :cljs :default) e (:rf.error/id (ex-data e)))))

(deftest registration-refuses-malformed-timeouts
  (let [spawn (fn [m] {:initial :l :states {:l {:spawn (merge {:machine-id :stub} m)} :to {}}})
        both  (fn [state-ms spawn-ms]
                {:initial :l
                 :states  {:l  {:timeout state-ms :on-timeout {:target :st}
                                :spawn   {:machine-id :stub :timeout spawn-ms :on-timeout {:target :sp}}}
                           :st {} :sp {}}})
        rows  [[:rf.error/machine-bad-timeout-duration
                {:initial :w :states {:w {:timeout "5s" :on-timeout :d} :d {}}}]
               [:rf.error/machine-timeout-without-on-timeout (spawn {:timeout 10000})]
               [:rf.error/machine-on-timeout-without-timeout (spawn {:on-timeout :to})]
               [:rf.error/spawn-timeout-ms-removed (spawn {:timeout-ms 1000})]
               [:rf.error/spawn-timeout-ms-removed
                {:initial :h
                 :states  {:h {:spawn-all {:children        [{:id :a :machine-id :stub}]
                                           :join            :all
                                           :on-all-complete [:done!]
                                           :timeout-ms      5000}}}}]
               [:rf.error/machine-timeout-after-collision
                {:initial :w
                 :states  {:w {:after {5000 {:target :x}} :timeout 5000 :on-timeout {:target :d}}
                           :x {} :d {}}}]
               [:rf.error/machine-timeout-after-collision (both 5000 5000)]
               [nil (both 3000 5000)]
               [:rf.error/machine-unresolved-target
                {:initial :w :states {:w {:timeout 5000 :on-timeout {:target :nowhere}} :d {}}}]]]
    (is (= (mapv first rows) (mapv (comp reg-error-id second) rows)))))

(deftest desugar-lowers-timeouts-onto-the-state-after
  (testing "a spawn-level timeout lands on the spawn-bearing state's :after"
    (is (= {:initial :l :states {:l {:spawn {:machine-id :c} :after {10000 {:target :to}}} :to {}}}
           (rf.machines.timeout/desugar-timeouts
             {:initial :l
              :states  {:l {:spawn {:machine-id :c :timeout 10000 :on-timeout {:target :to}}} :to {}}}))))
  (testing "a state-level timeout merges into an explicit :after on the same node"
    (is (= {:initial :w :states {:w {:after {1000 :warn 5000 :done}} :warn {} :done {}}}
           (rf.machines.timeout/desugar-timeouts
             {:initial :w
              :states  {:w {:after {1000 :warn} :timeout "PT5S" :on-timeout :done} :warn {} :done {}}})))))

(deftest state-timeout-arms-and-fires
  ;; The elapsed event names the resolved 5000 ms key and epoch 1, so it moves
  ;; the machine only if entering :waiting armed the desugared :after timer.
  (rf/reg-machine :tt/fire {:initial :idle
                            :states  {:idle      {:on {:go :waiting}}
                                      :waiting   {:timeout "PT5S" :on-timeout {:target :timed-out}}
                                      :timed-out {}}})
  (rf/dispatch-sync [:tt/fire [:go]])
  (rf/dispatch-sync [:tt/fire [:rf.machine.timer/after-elapsed 5000 1 [:waiting]]])
  (is (= :timed-out (:state (rf.machines.test-support/snapshot :tt/fire)))))
