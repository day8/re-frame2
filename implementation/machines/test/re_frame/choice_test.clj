(ns re-frame.choice-test
  "EP-0029 A5 — `:type :choice` transient / choice states.

  Covers:
    - desugaring `:type :choice` / `:choice` onto the existing `:always`
      eventless-transition mechanism (distinct authoring intent, ONE
      mechanism);
    - registration-time fail-loud validation (the `:type` / `:choice`
      pairing; the declarative candidate-array shape, REJECTING the
      function form — the operator-ruled A2 / C1 divergence; the
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
            [re-frame.machines.choice :as choice]
            [re-frame.machines.test-support :as mtest]
            [re-frame.substrate.plain-atom :as plain-atom]
            [re-frame.subs]))

(use-fixtures :each
  (mtest/make-reset-runtime-fixture {:adapter plain-atom/adapter}))

(def ^:private snapshot mtest/snapshot)

;; ---- desugaring (distinct intent, one mechanism) --------------------------

(deftest desugar-choice-to-always
  (testing "a :type :choice state lowers to an ordinary state carrying its
            candidate vector under :always, dropping :type / :choice"
    (is (= {:initial :start
            :states {:start {:on {:go :checking}}
                     :checking {:always [{:guard :valid? :target :ok}
                                         {:target :bad}]}
                     :ok {} :bad {}}}
           (choice/desugar-choices
             {:initial :start
              :states {:start {:on {:go :checking}}
                       :checking {:type :choice
                                  :choice [{:guard :valid? :target :ok}
                                           {:target :bad}]}
                       :ok {} :bad {}}})))))

(deftest desugar-choice-free-unchanged
  (testing "a machine with no :type :choice is returned UNCHANGED (fast-path)"
    (let [m {:initial :a :states {:a {:on {:go :b}} :b {}}}]
      (is (identical? m (choice/desugar-choices m))))))

(deftest desugar-choice-idempotent
  (testing "desugaring an already-desugared spec is a no-op"
    (let [m {:initial :c
             :states {:c {:always [{:guard :g :target :x} {:target :y}]}
                      :x {} :y {}}}]
      (is (= m (choice/desugar-choices (choice/desugar-choices m)))))))

(deftest desugar-nested-choice
  (testing "a :type :choice nested inside a compound desugars in place"
    (let [out (choice/desugar-choices
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

(deftest choice-accepts-valid
  (testing "a well-formed choice state (guarded candidates + a default) registers cleanly"
    (is (nil? (reg-error-id
                {:initial :start
                 :guards {:valid? (fn [_] true)}
                 :states {:start {:on {:go :checking}}
                          :checking {:type :choice
                                     :choice [{:guard :valid? :target :ok}
                                              {:target :bad}]}
                          :ok {} :bad {}}})))))

(deftest choice-type-requires-choice-slot
  (testing ":type :choice with no :choice slot fails loud"
    (is (= :rf.error/machine-choice-missing-choice
           (reg-error-id {:initial :c
                          :states {:c {:type :choice} :d {}}})))))

(deftest choice-slot-requires-type
  (testing "a :choice slot without :type :choice fails loud"
    (is (= :rf.error/machine-choice-without-type
           (reg-error-id {:initial :c
                          :states {:c {:choice [{:target :d}]} :d {}}})))))

(deftest choice-rejects-function-form
  (testing "a function-valued :choice is REJECTED (A2 / C1 operator-ruled divergence)"
    (is (= :rf.error/machine-bad-choice
           (reg-error-id {:initial :c
                          :states {:c {:type :choice :choice (fn [_] :d)} :d {}}})))))

(deftest choice-rejects-non-candidate-shapes
  (testing "a keyword / empty-vector / map :choice is malformed"
    (is (= :rf.error/machine-bad-choice
           (reg-error-id {:initial :c :states {:c {:type :choice :choice :d} :d {}}})))
    (is (= :rf.error/machine-bad-choice
           (reg-error-id {:initial :c :states {:c {:type :choice :choice []} :d {}}})))
    (is (= :rf.error/machine-bad-choice
           (reg-error-id {:initial :c :states {:c {:type :choice :choice {:target :d}} :d {}}})))))

(deftest choice-rejects-reserved-keys
  (testing "a choice state that also declares ordinary waiting-state behaviour fails loud"
    (is (= :rf.error/machine-choice-extra-keys
           (reg-error-id {:initial :c
                          :guards {:g (fn [_] true)}
                          :states {:c {:type :choice
                                       :choice [{:guard :g :target :d} {:target :e}]
                                       :on {:ev :e}}
                                   :d {} :e {}}})))
    (is (= :rf.error/machine-choice-extra-keys
           (reg-error-id {:initial :c
                          :actions {:a (fn [ctx] (:data ctx))}
                          :states {:c {:type :choice
                                       :choice [{:target :d}]
                                       :entry :a}
                                   :d {}}})))
    (testing "a choice state must not carry a :timeout (the timeout key is named)"
      (is (= :rf.error/machine-choice-extra-keys
             (reg-error-id {:initial :c
                            :states {:c {:type :choice
                                         :choice [{:target :d}]
                                         :timeout 5000 :on-timeout {:target :e}}
                                     :d {} :e {}}}))))))

(deftest choice-requires-default
  (testing "a choice state whose every candidate is GUARDED (no default) fails loud"
    (is (= :rf.error/machine-choice-no-default
           (reg-error-id {:initial :c
                          :guards {:g1 (fn [_] true) :g2 (fn [_] true)}
                          :states {:c {:type :choice
                                       :choice [{:guard :g1 :target :a}
                                                {:guard :g2 :target :b}]}
                                   :a {} :b {}}})))))

(deftest choice-rejects-self-loop
  (testing "a choice candidate that targets its own declaring state fails loud"
    (is (= :rf.error/machine-choice-self-loop
           (reg-error-id {:initial :c
                          :guards {:g (fn [_] true)}
                          :states {:c {:type :choice
                                       :choice [{:guard :g :target :c}
                                                {:target :d}]}
                                   :d {}}})))))

(deftest choice-unresolved-target-fails-loud
  (testing "a choice candidate target that resolves to no state fails loud
            (the desugared :always flows through the same target check)"
    (is (= :rf.error/machine-unresolved-target
           (reg-error-id {:initial :c
                          :states {:c {:type :choice
                                       :choice [{:target :nowhere}]}
                                   :d {}}})))))

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
