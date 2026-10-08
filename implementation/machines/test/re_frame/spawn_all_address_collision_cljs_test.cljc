(ns re-frame.spawn-all-address-collision-cljs-test
  "A `:spawn-all` invoke whose distinct logical children resolve to one actor
  address is rejected whole under the childless sentinel, before any child TYPE
  is resolved, with one structural-only `:rf.error/machine-spawn-all-duplicate-id`
  naming each collision group (Spec 009)."
  (:require
   #?(:clj  [clojure.test :refer [deftest is use-fixtures]]
      :cljs [cljs.test :refer-macros [deftest is use-fixtures]])
   [re-frame.core :as rf]
   [re-frame.machines]
   [re-frame.machines.test-support :as rf.machines.test-support]
   ;; Routes a child's `[:schemas :data]` validator, so the ORDER test's
   ;; counter would see a call.
   [re-frame.schemas]
   [re-frame.schemas.malli]
   #?@(:clj  [[re-frame.substrate.plain-atom :as rf.substrate.plain-atom]]
       :cljs [[re-frame.adapter.reagent :as rf.adapter.reagent]])))

(use-fixtures :each
  (rf.machines.test-support/make-reset-runtime-fixture
    #?(:clj  {:adapter rf.substrate.plain-atom/adapter}
       :cljs {:adapter rf.adapter.reagent/adapter}))
  rf.machines.test-support/trace-capture-fixture)

(def ^:private plain-child
  {:initial :running :data {} :states {:running {}}})

(defn- parent-over [children]
  {:initial :idle
   :states  {:idle    {:on {:start :forking}}
             :forking {:spawn-all {:children        children
                                   :join            :all
                                   :on-all-complete [:all/done]}
                       :on        {:all/done :ready}}
             :ready   {}}})

(defn- join-slot [parent-id]
  (get-in (rf.machines.test-support/runtime-db) [:rf.runtime/machines :spawned parent-id [:forking]]))

(defn- collision-rejects []
  (rf.machines.test-support/events-of :rf.error/machine-spawn-all-duplicate-id))

(defn- collision-tags []
  (mapv #(select-keys (:tags %) [:parent-id :invoke-id :collisions]) (collision-rejects)))

(deftest fixed-fixed-collision-rejects-the-whole-invoke
  (rf/reg-machine :sa/dup (assoc plain-child :data {:token "zzsecretzz"}))
  (rf/reg-machine :sup/dup
    (parent-over [{:id :a :machine-id :sa/dup :fixed-actor-id :dup/actor :data {:token "zzsecretzz-a"}}
                  {:id :b :machine-id :sa/dup :fixed-actor-id :dup/actor :data {:token "zzsecretzz-b"}}]))
  (rf/dispatch-sync [:sup/dup [:start]])
  (is (= {:rf/spawn-all-rejected? true} (join-slot :sup/dup)))
  (is (nil? (rf.machines.test-support/snapshot :dup/actor)))
  (is (= [{:parent-id :sup/dup :invoke-id [:forking] :collisions [[:dup/actor [:a :b]]]}]
         (collision-tags)))
  (is (not (re-find #"zzsecretzz" (pr-str (collision-rejects))))
      "the reject carries no spawn args or :data"))

(deftest fixed-generated-collision-rejects-the-whole-invoke
  ;; :b allocates the generated :sa/gen#1, the address :a fixes.
  (let [gen-id (keyword "sa" "gen#1")]
    (rf/reg-machine :sa/gen plain-child)
    (rf/reg-machine :sup/fixgen (parent-over [{:id :a :machine-id :sa/gen :fixed-actor-id gen-id}
                                              {:id :b :machine-id :sa/gen}]))
    (rf/dispatch-sync [:sup/fixgen [:start]])
    (is (= [[[gen-id [:a :b]]]] (mapv :collisions (collision-tags))))))

(deftest aliased-batch-rejects-before-type-resolution-across-groups
  ;; Ten groups: past the 8-entry array-map threshold, so their order cannot
  ;; come from map iteration. Each pairs a validated child with an unregistered
  ;; one, declared all-firsts then all-seconds so no group is contiguous.
  (let [calls    (atom 0)
        addr     (fn [i] (keyword "g" (str "actor" i)))
        first-id (fn [i] (keyword (str "a" i)))
        last-id  (fn [i] (keyword (str "b" i)))]
    (rf/reg-machine :sa/adv (assoc plain-child :schemas {:data [:fn (fn [_] (swap! calls inc) true)]}))
    (rf/reg-machine :sup/adv
      (parent-over (into (mapv (fn [i] {:id (first-id i) :machine-id :sa/adv :fixed-actor-id (addr i)})
                               (range 10))
                         (mapv (fn [i] {:id (last-id i) :machine-id :sa/nope :fixed-actor-id (addr i)})
                               (range 10)))))
    (rf/dispatch-sync [:sup/adv [:start]])
    (is (= [:rf.error/machine-spawn-all-duplicate-id]
           (into [] (comp (map :operation)
                          (filter #{:rf.error/machine-spawn-all-duplicate-id
                                    :rf.error/machine-spawn-unregistered-type}))
                 (rf.machines.test-support/captured-events)))
        "one structural reject, and no unregistered-type reject")
    (is (zero? @calls) "no child [:schemas :data] validator ran")
    (is (= [(mapv (fn [i] [(addr i) [(first-id i) (last-id i)]]) (range 10))]
           (mapv :collisions (collision-tags)))
        "groups in first-appearance order, each naming its children in declaration order")))
