(ns re-frame.machine-runtime-classification-cljs-test
  "A machine's `:sensitive` / `:large` declaration applies at egress to the
  actor snapshots a runtime-db holds (Spec 015 §Subsystem projection-relative
  classification): the `:machines/runtime-classification` hook re-roots each
  snapshot's machine declaration onto its absolute snapshot path, so an actor
  is classified exactly while its snapshot is present — from its spawn, gone
  with its destroy, back the moment an installer restores it — and nothing is
  written into the frame's elision registry."
  (:require
   #?(:clj  [clojure.test :refer [deftest is use-fixtures]]
      :cljs [cljs.test :refer-macros [deftest is use-fixtures]])
   [re-frame.core :as rf]
   [re-frame.elision :as rf.elision]
   [re-frame.interop :as rf.interop]
   [re-frame.machines]
   [re-frame.machines.classification :as rf.machines.classification]
   [re-frame.machines.test-support :as rf.machines.test-support]
   #?@(:clj  [[re-frame.substrate.plain-atom :as rf.substrate.plain-atom]]
       :cljs [[re-frame.adapter.reagent :as rf.adapter.reagent]])))

(use-fixtures :each
  (rf.machines.test-support/make-reset-runtime-fixture
    #?(:clj  {:adapter rf.substrate.plain-atom/adapter}
       :cljs {:adapter rf.adapter.reagent/adapter})))

(def ^:private classified
  {:sensitive [[:data :token]]
   :large     [[:data :blob]]
   :initial   :idle
   :data      {:token "t" :blob "b"}
   :states    {:idle {}}})

(defn- token-path [actor-id]
  [:rf.runtime/machines :snapshots actor-id :data :token])

(defn- claimed? [actor-id]
  (contains? (rf.elision/sensitive-declarations :rf/default) (token-path actor-id)))

(defn- registry-machine-claims
  "Every `:source :machine` owner the frame's elision registry itself holds."
  []
  (for [[_ decls] (:rf.runtime/elision (rf.machines.test-support/runtime-db))
        :when (map? decls)
        [_ owners] decls
        owner owners
        :when (= :machine (:source owner))]
    owner))

(deftest the-hook-gives-each-snapshot-its-own-machines-declaration
  (rf/reg-machine :rtc/kid classified)
  (rf/reg-machine :rtc/single classified)
  (rf/reg-machine :rtc/plain {:initial :idle :states {:idle {}}})
  (let [decls (rf.machines.classification/runtime-classification)
        out   (rf.machines.classification/runtime-classification
                {:snapshots {:rtc/kid#7  {:rf/runtime {:machine-type :rtc/kid}}
                             :rtc/single {:state :idle}
                             :rtc/plain  {:state :idle}}}
                decls)]
    (is (= {:sensitive [[:data :token]] :large [[:data :blob]]} (get decls :rtc/kid))
        "collect gathers each declaring machine's declaration by id")
    (is (not (contains? decls :rtc/plain)) "a machine that declares nothing is not collected")
    (is (= #{(token-path :rtc/kid#7) (token-path :rtc/single)} (set (:sensitive out)))
        "a spawned snapshot names its machine by type, a singleton by its own id")
    (is (= #{[:rf.runtime/machines :snapshots :rtc/kid#7 :data :blob]
             [:rf.runtime/machines :snapshots :rtc/single :data :blob]}
           (set (:large out))))))

(deftest an-actor-is-classified-exactly-while-its-snapshot-is-present
  (rf/reg-machine :rtc/kid classified)
  (rf/reg-machine :rtc/host
    {:initial :idle
     :states  {:idle {:on {:open :open}}
               :open {:spawn {:machine-id :rtc/kid}
                      :on    {:close :idle}}}})
  (rf/dispatch-sync [:rtc/host [:open]])
  (let [child (get-in (rf.machines.test-support/runtime-db) [:rf.runtime/machines :spawned :rtc/host [:open]])
        saved (:rf.runtime/machines (rf.machines.test-support/runtime-db))]
    (is (keyword? child) "control: the host spawned its child")
    (is (claimed? child) "spawned: the child's snapshot is claimed, with no classification step")
    (is (empty? (registry-machine-claims)) "nothing is written into the frame's elision registry")
    (rf/dispatch-sync [:rtc/host [:close]])
    (is (not (claimed? child)) "destroyed: the claim goes with the snapshot, with no drop step")
    (rf/dispatch-sync [:rf/install-frame-state {:rf.db/runtime {:rf.runtime/machines saved}}])
    (is (claimed? child) "installed: the restored snapshot is classified at once")
    (is (empty? (registry-machine-claims)) "and still nothing is written into the registry")))

(deftest a-singletons-first-event-traces-are-classified-before-its-snapshot-commits
  (rf/reg-machine :rtc/login
    {:sensitive [[:data :token]]
     :initial   :anon
     :data      {:token nil :tries 0}
     :states    {:anon   {:on {:login {:target :authed
                                       :action (fn [{:keys [data event]}]
                                                 {:data (assoc data :token (second event) :tries 1)})}}}
                 :authed {}}})
  (let [seen (atom [])]
    (rf/register-listener! :trace ::first-event (fn [ev] (swap! seen conj ev)))
    (try
      (rf/dispatch-sync [:rtc/login [:login "rtc-token-SECRET"]])
      (finally
        (rf/unregister-listener! :trace ::first-event)))
    (is (= "rtc-token-SECRET" (get-in (rf.machines.test-support/runtime-db)
                                      [:rf.runtime/machines :snapshots :rtc/login :data :token]))
        "control: the committed snapshot holds the raw token")
    (when rf.interop/debug-enabled?
      (let [afters (for [ev @seen
                         :when (#{:rf.machine/transition :rf.machine/snapshot-updated} (:operation ev))]
                     (get-in ev [:tags :after :data]))]
        (is (= 2 (count afters)) "control: the boot event traced its transition and snapshot update")
        (is (every? #(= {:token :rf/redacted :tries 1} %) afters)
            "the traces describe a snapshot runtime-db does not hold yet, and still redact")))))
