(ns re-frame.machine-identity-naming-cljs-test
  "Adversarial coverage for the machine-identity naming split.

  Machine identity is carried as three distinct facts. These tests pin the
  distinctions so a change that conflates them fails loudly:

   1. **TYPE vs live actor INSTANCE**. The live-actor lifecycle
      traces (`:rf.machine/transition`, `:rf.machine/done`, the
      `:rf.machine.timer/*`
      rows, `:rf.machine.lifecycle/destroyed`) carry the INSTANCE address
      under `:actor-id`, NOT `:machine-id`. `:machine-id` is reserved for the
      registered TYPE / singleton-registration id (it is carried on the
      `:rf.machine.spawn/spawned` / `:rf.machine.lifecycle/spawned` /
      `:rf.machine.lifecycle/created` spec-time-type rows). For a SPAWNED
      actor the two are provably different values (`<type>#<n>` ≠ `<type>`).

   2. **Explicit actor-address INPUT vs declarative invocation PATH**.
      On one declarative `:spawn` the explicit-address input
      key `:fixed-actor-id` and the runtime-stamped invocation-path key
      `:rf/invoke-id` are distinct facts carrying distinct shapes (a bare
      keyword address vs a state-path vector)."
  (:require [cljs.test :refer-macros [deftest is testing use-fixtures]]
            [re-frame.core :as rf]
            [re-frame.trace.tooling :as rf.trace.tooling]
            [re-frame.adapter.reagent :as rf.adapter.reagent]
            [re-frame.machines.test-support :as rf.machines.test-support]))

(use-fixtures :each
  (rf.machines.test-support/make-reset-runtime-fixture
    {:adapter rf.adapter.reagent/adapter}))

(def ^:private snapshot rf.machines.test-support/snapshot)

(defn- ops [traces op]
  (filter #(= op (:operation %)) traces))

;; ---------------------------------------------------------------------------
;; (1) TYPE vs live actor INSTANCE — a SPAWNED actor's lifecycle traces carry
;;     `:actor-id` = the `<type>#<n>` instance, NOT `:machine-id` = the TYPE.
;; ---------------------------------------------------------------------------

(deftest spawned-actor-lifecycle-traces-carry-actor-id-not-machine-id
  (testing "transition/done/destroyed rows carry :actor-id (the instance), never :machine-id"
    (let [child {:initial :running
                 :states  {:running {:on {:finish :done}}
                           :done    {:final? true}}}
          parent {:initial :idle
                  :states
                  {:idle {:on {:go :working}}
                   :working
                   {:spawn {:machine-id :idn/child
                            :start      [:noop]}
                    :on    {:back :idle}}}}
          traces (atom [])]
      (rf/reg-machine :idn/child child)
      (rf/reg-machine :idn/parent parent)
      (rf.trace.tooling/register-listener! ::idn (fn [ev] (swap! traces conj ev)))
      ;; Spawn the child (deterministic instance id :idn/child#1).
      (rf/dispatch-sync [:idn/parent [:go]])
      (let [spawned-id (get-in (:rf.db/runtime (rf/frame-state-value :rf/default))
                               [:rf.runtime/machines :spawned :idn/parent [:working]])]
        (is (= :idn/child#1 spawned-id)
            "the spawned instance id is <type>#<n>, distinct from the TYPE :idn/child")

        (reset! traces [])
        (rf/dispatch-sync [spawned-id [:finish]])  ;; → :done :final? → auto-destroy
        (let [tags     (fn [op] (:tags (first (ops @traces op))))
              instance (juxt :actor-id #(contains? % :machine-id))]
          (is (= [[spawned-id false] [spawned-id false] spawned-id]
                 [(instance (tags :rf.machine/transition))
                  (instance (tags :rf.machine/done))
                  (:actor-id (tags :rf.machine/destroyed))])
              "transition and done carry the INSTANCE under :actor-id and no :machine-id;
               destroyed carries it under :actor-id"))))))

;; ---------------------------------------------------------------------------
;; (2) Explicit actor-address INPUT vs declarative invocation PATH.
;;     On ONE declarative :spawn, `:fixed-actor-id` (explicit address, a bare
;;     keyword) and the runtime-stamped `:rf/invoke-id` (invocation path, a
;;     state-path vector) are distinct identity facts.
;; ---------------------------------------------------------------------------

(deftest fixed-actor-id-and-invoke-id-are-distinct-facts
  (testing "explicit :fixed-actor-id (address INPUT) ≠ runtime :rf/invoke-id (invocation PATH)"
    (let [child  {:initial :running :states {:running {}}}
          parent {:initial :idle
                  :states
                  {:idle {:on {:go :working}}
                   :working
                   {:spawn {:machine-id     :idn3/child
                            ;; explicit actor-address INPUT — pins the id
                            ;; instead of gensym.
                            :fixed-actor-id :idn3/pinned}}}}]
      (rf/reg-machine :idn3/child child)
      (rf/reg-machine :idn3/parent parent)
      (rf/dispatch-sync [:idn3/parent [:go]])
      (let [child-data (:data (snapshot :idn3/pinned))]
        (is (= [:idn3/pinned :idn3/pinned [:working]]
               [(get-in (:rf.db/runtime (rf/frame-state-value :rf/default))
                        [:rf.runtime/machines :spawned :idn3/parent [:working]])
                (:rf/self-id child-data)
                (:rf/invoke-id child-data)])
            "the explicit address is bound at the invocation-path slot and is the actor's
             :rf/self-id, while :rf/invoke-id is the state-path vector")))))

(deftest singleton-transition-carries-actor-id-equal-to-registration-id
  (testing "a singleton's transition carries :actor-id (== its registration id), not :machine-id"
    (let [m {:initial :a :states {:a {:on {:go :b}} :b {}}}
          traces (atom [])]
      (rf/reg-machine :idn4/single m)
      (rf.trace.tooling/register-listener! ::idn4 (fn [ev] (swap! traces conj ev)))
      (rf/dispatch-sync [:idn4/single [:go]])
      (let [tags (:tags (first (ops @traces :rf.machine/transition)))]
        (is (= [:idn4/single false] [(:actor-id tags) (contains? tags :machine-id)])
            "a singleton's live address IS its registration id, carried under :actor-id,
             not :machine-id")))))
