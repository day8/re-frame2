(ns re-frame.machines-choice-cljs-test
  "CLJS-side coverage for EP-0029 A5 `:type :choice` transient / choice
  states under the Reagent reactive substrate.

  Confirms cross-host parity for:
    - the `:choice` → `:always` desugar;
    - registration fail-loud validation (the `:type` / `:choice` pairing;
      the declarative candidate-array shape rejecting the function form; the
      required default; the self-loop);
    - the dispatch boundary — entering a choice state resolves IMMEDIATELY
      to the first guard-passing candidate (no event needed), with the
      default taken when every guard fails, and a transient INITIAL choice
      leaf settling on birth.

  Mirrors the JVM choice_test.clj."
  (:require [cljs.test :refer-macros [deftest is testing use-fixtures]]
            [re-frame.core :as rf]
            [re-frame.machines.choice :as choice]
            [re-frame.adapter.reagent :as reagent-adapter]
            [re-frame.machines.test-support :as mtest]))

(use-fixtures :each
  (mtest/make-reset-runtime-fixture
    {:adapter reagent-adapter/adapter}))

(def ^:private snapshot mtest/snapshot)

(deftest desugar-choice-to-always-cljs
  (testing ":type :choice lowers to an :always candidate vector cross-host"
    (is (= {:initial :c
            :states {:c {:always [{:guard :g :target :a} {:target :b}]}
                     :a {} :b {}}}
           (choice/desugar-choices
             {:initial :c
              :states {:c {:type :choice
                           :choice [{:guard :g :target :a} {:target :b}]}
                       :a {} :b {}}})))))

(defn- reg-error-id [machine]
  (try (rf/reg-machine (keyword "ctc" (str (gensym))) machine) nil
       (catch :default e (:rf.error/id (ex-data e)))))

(deftest registration-fail-loud-cljs
  (testing ":type :choice with no :choice slot fails loud"
    (is (= :rf.error/machine-choice-missing-choice
           (reg-error-id {:initial :c :states {:c {:type :choice} :d {}}}))))
  (testing "a function-valued :choice is rejected (A2 / C1 divergence)"
    (is (= :rf.error/machine-bad-choice
           (reg-error-id {:initial :c
                          :states {:c {:type :choice :choice (fn [_] :d)} :d {}}}))))
  (testing "a choice state with no default candidate fails loud"
    (is (= :rf.error/machine-choice-no-default
           (reg-error-id {:initial :c
                          :guards {:g1 (fn [_] true) :g2 (fn [_] true)}
                          :states {:c {:type :choice
                                       :choice [{:guard :g1 :target :a}
                                                {:guard :g2 :target :b}]}
                                   :a {} :b {}}}))))
  (testing "a self-targeting choice candidate fails loud"
    (is (= :rf.error/machine-choice-self-loop
           (reg-error-id {:initial :c
                          :guards {:g (fn [_] true)}
                          :states {:c {:type :choice
                                       :choice [{:guard :g :target :c}
                                                {:target :d}]}
                                   :d {}}})))))

(deftest choice-resolves-on-entry-cljs
  (testing "entering a choice state takes the first guard-passing candidate immediately"
    (let [m {:initial :idle :data {:ok? true}
             :guards {:ok? (fn [{:keys [data]}] (:ok? data))}
             :states {:idle     {:on {:go :checking}}
                      :checking {:type   :choice
                                 :choice [{:guard :ok? :target :accepted}
                                          {:target :rejected}]}
                      :accepted {} :rejected {}}}]
      (rf/reg-machine :ctc/pass m)
      (rf/dispatch-sync [:ctc/pass [:go]])
      (is (= :accepted (:state (snapshot :ctc/pass)))
          "the guard passed — settled past the transient state to :accepted"))))

(deftest choice-default-on-entry-cljs
  (testing "when every guard fails the unguarded default is taken"
    (let [m {:initial :idle :data {:ok? false}
             :guards {:ok? (fn [{:keys [data]}] (:ok? data))}
             :states {:idle     {:on {:go :checking}}
                      :checking {:type   :choice
                                 :choice [{:guard :ok? :target :accepted}
                                          {:target :rejected}]}
                      :accepted {} :rejected {}}}]
      (rf/reg-machine :ctc/default m)
      (rf/dispatch-sync [:ctc/default [:go]])
      (is (= :rejected (:state (snapshot :ctc/default)))
          "the guard failed — routed to the default :rejected"))))

(deftest choice-initial-settles-on-birth-cljs
  (testing "a transient INITIAL choice leaf resolves on start"
    (let [m {:initial :checking :data {:ok? true}
             :guards {:ok? (fn [{:keys [data]}] (:ok? data))}
             :states {:checking {:type   :choice
                                 :choice [{:guard :ok? :target :accepted}
                                          {:target :rejected}]}
                      :accepted {} :rejected {}}}]
      (rf/reg-machine :ctc/birth m)
      (rf/dispatch-sync [:ctc/birth [:rf.machine/start]])
      (is (= :accepted (:state (snapshot :ctc/birth)))
          "the initial choice leaf settled to :accepted on birth"))))
