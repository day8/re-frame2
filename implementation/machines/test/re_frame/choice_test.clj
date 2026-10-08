(ns re-frame.choice-test
  "`:type :choice` transient states: the desugar onto `:always` reaches nested
  compound states, registration refuses each malformed shape, and through the
  live runtime a choice state entered mid-macrostep takes the first passing
  candidate (running its `:action`) or the default, and a transient initial
  choice leaf settles on birth."
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [re-frame.core :as rf]
            [re-frame.machines.choice :as rf.machines.choice]
            [re-frame.machines.test-support :as rf.machines.test-support]
            [re-frame.substrate.plain-atom :as rf.substrate.plain-atom]
            [re-frame.subs]))

(use-fixtures :each
  (rf.machines.test-support/make-reset-runtime-fixture {:adapter rf.substrate.plain-atom/adapter}))

(def ^:private snapshot rf.machines.test-support/snapshot)

(deftest desugar-nested-choice
  (testing "a :type :choice nested inside a compound desugars in place"
    (is (= {:always [{:guard :g :target :hit} {:target :miss}]}
           (get-in (rf.machines.choice/desugar-choices
                     {:initial :outer
                      :states  {:outer {:initial :pick
                                        :states  {:pick {:type   :choice
                                                         :choice [{:guard :g :target :hit}
                                                                  {:target :miss}]}
                                                  :hit  {} :miss {}}}}})
                   [:states :outer :states :pick])))))

(defn- reg-error-id [machine]
  (try (rf/reg-machine (keyword "ct" (str (gensym))) machine) nil
       (catch clojure.lang.ExceptionInfo e (:rf.error/id (ex-data e)))))

(deftest choice-registration-refusals
  (doseq [[label error-id machine]
          [[":type :choice with no :choice slot"
            :rf.error/machine-choice-missing-choice
            {:initial :c
             :states {:c {:type :choice} :d {}}}]
           ["a :choice slot without :type :choice"
            :rf.error/machine-choice-without-type
            {:initial :c
             :states {:c {:choice [{:target :d}]} :d {}}}]
           ["a function-valued :choice — choice candidates are declarative data"
            :rf.error/machine-bad-choice
            {:initial :c
             :states {:c {:type :choice :choice (fn [_] :d)} :d {}}}]
           ["an empty-vector :choice"
            :rf.error/machine-bad-choice
            {:initial :c :states {:c {:type :choice :choice []} :d {}}}]
           ["a choice state that also declares :on (ordinary waiting-state behaviour)"
            :rf.error/machine-choice-extra-keys
            {:initial :c
             :guards {:g (fn [_] true)}
             :states {:c {:type :choice
                          :choice [{:guard :g :target :d} {:target :e}]
                          :on {:ev :e}}
                      :d {} :e {}}}]
           ["every candidate GUARDED (no default)"
            :rf.error/machine-choice-no-default
            {:initial :c
             :guards {:g1 (fn [_] true) :g2 (fn [_] true)}
             :states {:c {:type :choice
                          :choice [{:guard :g1 :target :a}
                                   {:guard :g2 :target :b}]}
                      :a {} :b {}}}]
           ["a candidate that targets its own declaring state"
            :rf.error/machine-choice-self-loop
            {:initial :c
             :guards {:g (fn [_] true)}
             :states {:c {:type :choice
                          :choice [{:guard :g :target :c}
                                   {:target :d}]}
                      :d {}}}]
           ["a candidate target that resolves to no state (the desugared :always
             flows through the same target check)"
            :rf.error/machine-unresolved-target
            {:initial :c
             :states {:c {:type :choice
                          :choice [{:target :nowhere}]}
                      :d {}}}]]]
    (testing label
      (is (= error-id (reg-error-id machine))))))

(deftest choice-falls-through-to-default
  (rf/reg-machine :ct/default
    {:initial :idle :data {:ok? false}
     :guards  {:ok? (fn [{:keys [data]}] (:ok? data))}
     :states  {:idle     {:on {:go :checking}}
               :checking {:type   :choice
                          :choice [{:guard :ok? :target :accepted}
                                   {:target :rejected}]}
               :accepted {} :rejected {}}})
  (rf/dispatch-sync [:ct/default [:go]])
  (is (= :rejected (:state (snapshot :ct/default)))))

(deftest choice-as-initial-state-settles-on-birth
  (rf/reg-machine :ct/birth
    {:initial :checking :data {:ok? true}
     :guards  {:ok? (fn [{:keys [data]}] (:ok? data))}
     :states  {:checking {:type   :choice
                          :choice [{:guard :ok? :target :accepted}
                                   {:target :rejected}]}
               :accepted {} :rejected {}}})
  (rf/dispatch-sync [:ct/birth [:rf.machine/start]])
  (is (= :accepted (:state (snapshot :ct/birth)))))

(deftest choice-candidate-action-runs
  (rf/reg-machine :ct/action
    {:initial :idle :data {:ok? true}
     :guards  {:ok? (fn [{:keys [data]}] (:ok? data))}
     :actions {:mark (fn [{:keys [data]}] {:data (assoc data :marked? true)})}
     :states  {:idle     {:on {:go :checking}}
               :checking {:type   :choice
                          :choice [{:guard :ok? :target :accepted :action :mark}
                                   {:target :rejected}]}
               :accepted {} :rejected {}}})
  (rf/dispatch-sync [:ct/action [:go]])
  (let [s (snapshot :ct/action)]
    (is (= [:accepted true] [(:state s) (get-in s [:data :marked?])]))))
