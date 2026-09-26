(ns re-frame.flat-vector-target-state-cljs-test
  "In a FLAT machine a keyword-vector `:target` commits the same single
  keyword a keyword target does, because a flat machine's `:state` is a
  single keyword (Spec 005 §Snapshot shape). Every state of a flat machine
  is a root-level leaf, so the absolute path `[:b]` and the sibling keyword
  `:b` name one state and commit one spelling of it.

  A hierarchical machine's `:state` is a vector path, so there a vector
  target commits its path — a root-level leaf included.

  Named `*-cljs-test.cljc` so both the JVM runner (`clojure -M:test`) and
  the shadow-cljs `:node-test` build discover it."
  (:require
   #?(:clj  [clojure.test :refer [deftest is testing use-fixtures]]
      :cljs [cljs.test :refer-macros [deftest is testing use-fixtures]])
   [re-frame.core :as rf]
   [re-frame.machines :as rf.machines]
   [re-frame.machines.test-support :as rf.machines.test-support]
   #?@(:clj  [[re-frame.substrate.plain-atom :as rf.substrate.plain-atom]]
       :cljs [[re-frame.adapter.reagent :as rf.adapter.reagent]])))

(use-fixtures :each
  (rf.machines.test-support/make-reset-runtime-fixture
    #?(:clj  {:adapter rf.substrate.plain-atom/adapter}
       :cljs {:adapter rf.adapter.reagent/adapter})))

(def ^:private snapshot rf.machines.test-support/snapshot)

(defn- state-after
  "The `:state` the pure transition commits for `event` from `state`."
  [machine state event]
  (let [r (rf.machines/machine-transition machine {:state state :data {}} event)]
    (is (= :ok (:status r)) (pr-str r))
    (get-in r [:snapshot :state])))

(def ^:private flat-keyword-target
  {:initial :a :states {:a {:on {:go :b}} :b {}}})

(def ^:private flat-vector-target
  {:initial :a :states {:a {:on {:go [:b]}} :b {}}})

(def ^:private flat-map-vector-target
  {:initial :a :states {:a {:on {:go {:target [:b]}}} :b {}}})

(deftest flat-machine-vector-target-commits-a-keyword
  (testing "the pure transition commits :b for [:b] and {:target [:b]}, as for :b"
    (is (= :b (state-after flat-keyword-target :a [:go])))
    (is (= :b (state-after flat-vector-target :a [:go])))
    (is (= :b (state-after flat-map-vector-target :a [:go]))))
  (testing "a registered flat machine's live snapshot reads :b"
    (rf/reg-machine :flat-vec/machine flat-vector-target)
    (rf/dispatch-sync [:flat-vec/machine [:go]])
    (is (= :b (:state (snapshot :flat-vec/machine))))))

(def ^:private hierarchical
  {:initial :out
   :states  {:out {:on {:login [:in :home]}}
             :in  {:initial :home
                   :on      {:logout [:out]}
                   :states  {:home {}}}}})

(deftest hierarchical-machine-vector-target-commits-its-path
  (testing "a vector target into a compound commits the leaf path"
    (is (= [:in :home] (state-after hierarchical [:out] [:login]))))
  (testing "a vector target naming a root-level leaf commits a one-element path"
    (is (= [:out] (state-after hierarchical [:in :home] [:logout])))))
