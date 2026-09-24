(ns re-frame.machine-transition-keys-test
  "The closed bare-key rule on TRANSITION maps and on ROOT-ONLY keys.

  A state node's bare keys are a closed vocabulary (`machine-node-keys-test`).
  The same rule covers two more places, and both fail with the same
  `:rf.error/machine-unknown-node-key`:

    - a transition map carries only `:target` `:reenter?` `:guard` `:action`
      `:meta` (plus the macro-stamped `:source-coords` / `:source-code`). A bare
      key outside that set is refused at registration, in every transition slot,
      with `:slot` naming the slot. Otherwise `{:targt :b}` is a targetless
      no-op that emits no trace, `:cond` fires the transition unguarded,
      `:actions` drops the action and `:reenter` never re-enters.
    - `:data` `:schemas` `:internal-events` `:guards` `:actions` `:region-order`
      belong on the machine root. On a nested state or a parallel region body
      the runtime never reads them, so they are refused there.

  Namespaced keys pass on both, the open extension carve-out."
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [re-frame.core :as rf]
            [re-frame.machines]
            [re-frame.machines.test-support :as rf.machines.test-support]
            [re-frame.substrate.plain-atom :as rf.substrate.plain-atom]))

(use-fixtures :each
  (rf.machines.test-support/make-reset-runtime-fixture
    {:adapter rf.substrate.plain-atom/adapter}))

(defn- reg-error
  "Register `machine` under a fresh id. Returns the refusal's ex-data, or nil
  when registration succeeds."
  [machine]
  (try (rf/reg-machine (keyword "tk" (str (gensym))) machine) nil
       (catch clojure.lang.ExceptionInfo e (ex-data e))))

(defn- with-state-a
  "A flat machine whose `:a` state is `node` and whose `:b` is a plain target."
  [node]
  {:initial :a :states {:a node :b {}}})

;; ---- transition maps -------------------------------------------------------

(deftest unknown-transition-key-refused-in-every-slot
  (testing "a bare non-grammar key on a transition map is refused, whichever
            slot carries the map, and :slot names that slot"
    (doseq [[slot machine]
            {:on             (with-state-a {:on {:go {:targt :b}}})
             :after          (with-state-a {:after {1000 {:target :b :bogus 1}}})
             :always         (with-state-a {:always [{:target :b :guard (constantly false) :cond :x}]})
             :choice         {:initial :g
                              :states  {:g {:type :choice :choice [{:target :b :actions [:x]}]}
                                        :b {}}}
             :on-done        {:initial :o
                              :states  {:o {:initial :i
                                            :states  {:i {:final? true}}
                                            :on-done {:target :b :internal true}}
                                        :b {}}}
             :on-timeout     (with-state-a {:timeout 1000 :on-timeout {:target :b :reenter true}})
             :spawn/on-error (with-state-a {:spawn {:machine-id :tk/child
                                                    :on-error   {:targt :b}}})}]
      (let [d (reg-error machine)]
        (is (= :rf.error/machine-unknown-node-key (:rf.error/id d))
            (str slot ": refused with the node-key id"))
        (is (= slot (:slot d))
            (str slot ": :slot names the transition slot"))))))

(deftest unknown-transition-key-refused-on-root-and-region
  (testing "the root's own :on and a parallel region body's :on are
            transition slots too"
    (is (= :rf.error/machine-unknown-node-key
           (:rf.error/id (reg-error {:initial :a
                                     :on      {:reset {:target :a :targt :a}}
                                     :states  {:a {}}}))))
    (is (= :rf.error/machine-unknown-node-key
           (:rf.error/id (reg-error {:type    :parallel
                                     :regions {:r {:initial :x
                                                   :on      {:go {:targt :y}}
                                                   :states  {:x {} :y {}}}}}))))))

(deftest xstate-spellings-named-in-the-refusal
  (testing "the ex-data names the offending keys and the valid vocabulary, and
            the message names the re-frame2 spelling of each XState key"
    (let [d (reg-error (with-state-a {:on {:go {:target :b :cond :ok? :actions [:x]}}}))]
      (is (= :a (:state d)))
      (is (= #{:cond :actions} (set (:offending-keys d))))
      (is (contains? (:valid-keys d) :guard))
      (is (re-find #":cond is :guard" (:reason d)))
      (is (re-find #":actions is :action" (:reason d))))))

(deftest valid-transition-maps-register
  (testing "the whole transition vocabulary, the value forms, and namespaced
            keys register cleanly"
    (is (nil? (reg-error
                (with-state-a
                  {:on    {:go    {:target        :b
                                   :reenter?      false
                                   :guard         (constantly true)
                                   :action        (fn [_] nil)
                                   :meta          {:doc "x"}
                                   :source-coords {:line 1}
                                   :source-code   {:action "(fn [_] nil)"}
                                   :my.app/note   "kept"}
                           :kw    :b
                           :path  [:b]
                           :cands [{:target :b :guard (constantly false)} {:target :a}]
                           :stay  nil}
                   :after {1000 :b}}))))))

;; ---- root-only keys --------------------------------------------------------

(deftest root-only-keys-refused-below-the-root
  (testing "a root-only key on a nested state is refused and named root-only"
    (doseq [[k v] {:data            {:leaf 1}
                   :schemas         {:data :any}
                   :internal-events #{:tick}
                   :guards          {:ok? (constantly true)}
                   :actions         {:go (fn [_] nil)}
                   :region-order    [:r]}]
      (let [d (reg-error (with-state-a {k v}))]
        (is (= :rf.error/machine-unknown-node-key (:rf.error/id d))
            (str k ": refused on a nested state"))
        (is (= [k] (:offending-keys d)) (str k ": named as the offending key"))
        (is (re-find #"root-only" (:reason d)) (str k ": the message says root-only"))))))

(deftest root-only-key-refused-on-a-region-body
  (testing "a parallel region body is not the machine root"
    (is (= :rf.error/machine-unknown-node-key
           (:rf.error/id (reg-error {:type    :parallel
                                     :regions {:r {:initial :x
                                                   :guards  {:ok? (constantly true)}
                                                   :states  {:x {}}}}}))))))

(deftest root-only-keys-register-on-the-root
  (testing "control: the same keys register on the machine root"
    (is (nil? (reg-error {:initial         :a
                          :data            {:n 1}
                          :schemas         {:data :any}
                          :internal-events #{:tick}
                          :guards          {:ok? (constantly true)}
                          :actions         {:go (fn [_] nil)}
                          :states          {:a {:on {:tick {:target :a :guard :ok? :action :go}}}}})))))
