(ns re-frame.compound-state-shape-cljs-test
  "The MACHINE decides the shape of `:state`, never the spelling of the
  target or `:initial` that reached the leaf (Spec 005 §Snapshot shape). A
  compound machine's `:state` is the vector path from the root to the
  active leaf, so a root-level leaf reads `[:out]` whether a keyword target,
  a vector target or the machine's own `:initial` reached it — and the
  initial snapshot reads the same value before the machine boots as after.

  A compound parallel region follows the compound arm inside the region map
  (Spec 005 §Parallel regions §Snapshot shape), while a flat machine and a
  flat region keep their keyword.

  Named `*-cljs-test.cljc` so both the JVM runner (`clojure -M:test`) and
  the shadow-cljs `:node-test` build discover it."
  (:require
   #?(:clj  [clojure.test :refer [deftest is testing use-fixtures]]
      :cljs [cljs.test :refer-macros [deftest is testing use-fixtures]])
   [re-frame.core :as rf]
   [re-frame.machines :as rf.machines]
   [re-frame.machines.parallel :as rf.machines.parallel]
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

(defn- initial-state
  "The `:state` of `machine`'s initial snapshot, before it boots."
  [machine]
  (:state (rf.machines.parallel/build-initial-snapshot machine {:bootstrap-pending? false})))

(def ^:private hierarchical
  {:initial :out
   :states  {:out {:on {:login [:in :home]}}
             :in  {:initial :home
                   :on      {:logout     :out
                             :logout-vec [:out]}
                   :states  {:home {:on {:next :away}}
                             :away {}}}}})

(deftest compound-machine-keyword-target-commits-its-path
  (testing "a keyword target naming a root-level leaf commits a one-element path"
    (is (= [:out] (state-after hierarchical [:in :home] [:logout]))))
  (testing "the keyword and vector targets for one leaf commit one value"
    (is (= (state-after hierarchical [:in :home] [:logout-vec])
           (state-after hierarchical [:in :home] [:logout]))))
  (testing "a sibling keyword target inside a compound keeps its full path"
    (is (= [:in :away] (state-after hierarchical [:in :home] [:next]))))
  (testing "a registered compound machine's live snapshot reads [:out]"
    (rf/reg-machine :cshape/machine hierarchical)
    (rf/dispatch-sync [:cshape/machine [:login]])
    (is (= [:in :home] (:state (snapshot :cshape/machine))))
    (rf/dispatch-sync [:cshape/machine [:logout]])
    (is (= [:out] (:state (snapshot :cshape/machine))))))

(deftest compound-machine-initial-snapshot-reads-its-path
  (testing "a root-level leaf :initial reads a one-element path before boot"
    (is (= [:out] (initial-state hierarchical))))
  (testing "the booted machine reads the same value it read before boot"
    (rf/reg-machine :cshape/boot hierarchical)
    (rf/dispatch-sync [:cshape/boot [:rf.machine/start]])
    (is (= (initial-state hierarchical) (:state (snapshot :cshape/boot))))))

(def ^:private flat
  {:initial :a :states {:a {:on {:go :b}} :b {}}})

(deftest flat-machine-keeps-its-keyword
  (testing "a flat machine's keyword target and :initial stay keywords"
    (is (= :b (state-after flat :a [:go])))
    (is (= :a (initial-state flat)))))

(def ^:private regions
  {:type    :parallel
   :on      {:reset [:compound :x]}
   :regions {:flat     {:initial :a
                        :states  {:a {:on {:go :b}}
                                  :b {}}}
             :compound {:initial :x
                        :states  {:x {:on {:dive [:y :deep]}}
                                  :y {:initial :deep
                                      :on      {:leave :x}
                                      :states  {:deep {}}}}}}})

(defn- region-after
  "The value `region` commits in the region map for `event` from `state`."
  [region state event]
  (get (state-after regions state event) region))

(deftest compound-region-follows-the-compound-arm
  (let [deep {:flat :a :compound [:y :deep]}]
    (testing "a region-local keyword target naming the region's root-level leaf commits [:x]"
      (is (= [:x] (region-after :compound deep [:leave]))))
    (testing "a root region-qualified target into the compound region commits [:x]"
      (is (= [:x] (region-after :compound deep [:reset]))))
    (testing "the flat sibling region keeps its keyword"
      (is (= :b (region-after :flat deep [:go])))))
  (testing "the initial region map reads [:x] for the compound region, :a for the flat one"
    (is (= {:flat :a :compound [:x]} (initial-state regions))))
  (testing "a registered parallel machine reads the same region map before and after boot"
    (rf/reg-machine :cshape/regions regions)
    (rf/dispatch-sync [:cshape/regions [:rf.machine/start]])
    (is (= {:flat :a :compound [:x]} (:state (snapshot :cshape/regions))))))
