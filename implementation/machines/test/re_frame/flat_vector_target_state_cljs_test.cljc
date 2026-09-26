(ns re-frame.flat-vector-target-state-cljs-test
  "In a FLAT machine a keyword-vector `:target` commits the same single
  keyword a keyword target does, because a flat machine's `:state` is a
  single keyword (Spec 005 §Snapshot shape). Every state of a flat machine
  is a root-level leaf, so the absolute path `[:b]` and the sibling keyword
  `:b` name one state and commit one spelling of it.

  A hierarchical machine's `:state` is a vector path, so there a vector
  target commits its path — a root-level leaf included.

  A parallel machine's regions follow the same two arms inside the region
  map (Spec 005 §Parallel regions §Snapshot shape): a FLAT region's value
  is a keyword, so a vector target naming one of its states commits the
  keyword, while a compound region's value stays a vector path.

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

(def ^:private regions
  {:type    :parallel
   :on      {:jump [:flat :b]}
   :regions {:flat     {:initial :a
                        :states  {:a {:on {:go     [:b]
                                           :go-map {:target [:b]}
                                           :go-kw  :b}}
                                  :b {}}}
             :compound {:initial :x
                        :states  {:x {:on {:dive [:y :deep]}}
                                  :y {:initial :deep
                                      :states  {:deep {:on {:leave [:x]}}}}}}}})

(defn- region-after
  "The value `region` commits in the region map for `event` from `state`."
  [region state event]
  (get (state-after regions state event) region))

(deftest flat-region-vector-target-commits-a-keyword
  (let [from {:flat :a :compound [:x]}]
    (testing "the flat region commits :b for [:b] and {:target [:b]}, as for :b"
      (is (= :b (region-after :flat from [:go])))
      (is (= :b (region-after :flat from [:go-map])))
      (is (= :b (region-after :flat from [:go-kw]))))
    (testing "a root region-qualified target into the flat region commits :b"
      (is (= :b (region-after :flat from [:jump]))))
    (testing "the sibling region is untouched"
      (is (= [:x] (region-after :compound from [:go])))))
  (testing "a registered parallel machine's live snapshot reads :b for the flat region"
    (rf/reg-machine :flat-vec/regions regions)
    (rf/dispatch-sync [:flat-vec/regions [:go]])
    (is (= :b (get-in (snapshot :flat-vec/regions) [:state :flat])))))

(deftest compound-region-vector-target-commits-its-path
  (testing "a vector target into a compound commits the in-region leaf path"
    (is (= [:y :deep] (region-after :compound {:flat :a :compound [:x]} [:dive]))))
  (testing "a vector target naming the region's root-level leaf commits a one-element path"
    (is (= [:x] (region-after :compound {:flat :a :compound [:y :deep]} [:leave])))))
