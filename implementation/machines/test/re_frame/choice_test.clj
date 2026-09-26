(ns re-frame.choice-test
  "`:type :choice` transient states.

  Covers:
    - desugaring `:type :choice` / `:choice` onto the `:always`
      eventless-transition mechanism (distinct authoring intent, ONE
      mechanism);
    - registration-time fail-loud validation (the `:type` / `:choice`
      pairing; the declarative candidate-array shape, REJECTING the
      function form; the
      reserved-key exclusion; the required default candidate; the
      self-loop rejection);
    - the dispatch boundary — a choice state entered mid-macrostep resolves
      IMMEDIATELY to the first guard-passing candidate (no event needed),
      and at machine BIRTH a transient initial choice leaf settles past on
      start.

  The dispatch-boundary tests drive the real runtime (`reg-machine` +
  `dispatch-sync`) and read the settled snapshot, so they exercise the
  desugared `:always` end-to-end through the macrostep / birth-settle
  loops — a choice state IS an `:always`-bearing transient node after
  desugar."
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [re-frame.core :as rf]
            [re-frame.machines.choice :as rf.machines.choice]
            [re-frame.machines.test-support :as rf.machines.test-support]
            [re-frame.substrate.plain-atom :as rf.substrate.plain-atom]
            [re-frame.subs]))

(use-fixtures :each
  (rf.machines.test-support/make-reset-runtime-fixture {:adapter rf.substrate.plain-atom/adapter}))

(def ^:private snapshot rf.machines.test-support/snapshot)

;; ---- desugaring (distinct intent, one mechanism) --------------------------

(deftest desugar-choice-to-always
  (testing "a :type :choice state lowers to an ordinary state carrying its
            candidate vector under :always, dropping :type / :choice"
    (is (= {:initial :start
            :states {:start {:on {:go :checking}}
                     :checking {:always [{:guard :valid? :target :ok}
                                         {:target :bad}]}
                     :ok {} :bad {}}}
           (rf.machines.choice/desugar-choices
             {:initial :start
              :states {:start {:on {:go :checking}}
                       :checking {:type :choice
                                  :choice [{:guard :valid? :target :ok}
                                           {:target :bad}]}
                       :ok {} :bad {}}})))))

(deftest desugar-choice-free-unchanged
  (testing "a machine with no :type :choice is returned UNCHANGED (fast-path)"
    (let [m {:initial :a :states {:a {:on {:go :b}} :b {}}}]
      (is (identical? m (rf.machines.choice/desugar-choices m))))))

(deftest desugar-nested-choice
  (testing "a :type :choice nested inside a compound desugars in place"
    (let [out (rf.machines.choice/desugar-choices
                {:initial :outer
                 :states {:outer {:initial :pick
                                  :states {:pick {:type :choice
                                                  :choice [{:guard :g :target :hit}
                                                           {:target :miss}]}
                                           :hit {} :miss {}}}}})]
      (is (= [{:guard :g :target :hit} {:target :miss}]
             (get-in out [:states :outer :states :pick :always])))
      (is (not (contains? (get-in out [:states :outer :states :pick]) :type)))
      (is (not (contains? (get-in out [:states :outer :states :pick]) :choice))))))

;; ---- registration-time fail-loud validation -------------------------------

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
           ["a keyword :choice"
            :rf.error/machine-bad-choice
            {:initial :c :states {:c {:type :choice :choice :d} :d {}}}]
           ["an empty-vector :choice"
            :rf.error/machine-bad-choice
            {:initial :c :states {:c {:type :choice :choice []} :d {}}}]
           ["a map :choice"
            :rf.error/machine-bad-choice
            {:initial :c :states {:c {:type :choice :choice {:target :d}} :d {}}}]
           ["a choice state that also declares :on (ordinary waiting-state behaviour)"
            :rf.error/machine-choice-extra-keys
            {:initial :c
             :guards {:g (fn [_] true)}
             :states {:c {:type :choice
                          :choice [{:guard :g :target :d} {:target :e}]
                          :on {:ev :e}}
                      :d {} :e {}}}]
           ["a choice state that also declares :entry"
            :rf.error/machine-choice-extra-keys
            {:initial :c
             :actions {:a (fn [ctx] (:data ctx))}
             :states {:c {:type :choice
                          :choice [{:target :d}]
                          :entry :a}
                      :d {}}}]
           ["a choice state carrying a :timeout"
            :rf.error/machine-choice-extra-keys
            {:initial :c
             :states {:c {:type :choice
                          :choice [{:target :d}]
                          :timeout 5000 :on-timeout {:target :e}}
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

;; ---- dispatch boundary — the choice state resolves immediately on entry ----

(deftest choice-resolves-first-passing-candidate
  (testing "entering a choice state mid-macrostep takes the first guard-passing
            candidate immediately — no event needed"
    (let [m {:initial :idle :data {:ok? true}
             :guards {:ok? (fn [{:keys [data]}] (:ok? data))}
             :states {:idle    {:on {:go :checking}}
                      :checking {:type :choice
                                 :choice [{:guard :ok? :target :accepted}
                                          {:target :rejected}]}
                      :accepted {} :rejected {}}}]
      (rf/reg-machine :ct/first m)
      (rf/dispatch-sync [:ct/first [:go]])
      (is (= :accepted (:state (snapshot :ct/first)))
          "the choice state settled past to :accepted (the guard passed) within
           the SAME macrostep — :checking is never externally observed"))))

(deftest choice-falls-through-to-default
  (testing "when every guard fails the unguarded DEFAULT candidate is taken"
    (let [m {:initial :idle :data {:ok? false}
             :guards {:ok? (fn [{:keys [data]}] (:ok? data))}
             :states {:idle    {:on {:go :checking}}
                      :checking {:type :choice
                                 :choice [{:guard :ok? :target :accepted}
                                          {:target :rejected}]}
                      :accepted {} :rejected {}}}]
      (rf/reg-machine :ct/default m)
      (rf/dispatch-sync [:ct/default [:go]])
      (is (= :rejected (:state (snapshot :ct/default)))
          "the guard failed, so the choice state routed to the default :rejected"))))

(deftest choice-as-initial-state-settles-on-birth
  (testing "a transient INITIAL choice leaf resolves on start (birth-time settle)"
    (let [m {:initial :checking :data {:ok? true}
             :guards {:ok? (fn [{:keys [data]}] (:ok? data))}
             :states {:checking {:type :choice
                                 :choice [{:guard :ok? :target :accepted}
                                          {:target :rejected}]}
                      :accepted {} :rejected {}}}]
      (rf/reg-machine :ct/birth m)
      ;; Eager creation kick — brings the machine to life and runs the birth
      ;; macrostep (initial cascade + the :always settle).
      (rf/dispatch-sync [:ct/birth [:rf.machine/start]])
      (is (= :accepted (:state (snapshot :ct/birth)))
          "the initial choice leaf settled past to :accepted on start — the
           transient initial state is never externally observed"))))

(deftest choice-candidate-action-runs
  (testing "a choice candidate may carry an :action that runs on the routing transition"
    (let [m {:initial :idle :data {:ok? true}
             :guards {:ok? (fn [{:keys [data]}] (:ok? data))}
             :actions {:mark (fn [{:keys [data]}] {:data (assoc data :marked? true)})}
             :states {:idle    {:on {:go :checking}}
                      :checking {:type :choice
                                 :choice [{:guard :ok? :target :accepted :action :mark}
                                          {:target :rejected}]}
                      :accepted {} :rejected {}}}]
      (rf/reg-machine :ct/action m)
      (rf/dispatch-sync [:ct/action [:go]])
      (let [s (snapshot :ct/action)]
        (is (= :accepted (:state s)))
        (is (true? (get-in s [:data :marked?]))
            "the first-passing candidate's :action ran on the routing transition")))))
