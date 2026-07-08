(ns re-frame.machine-identity-naming-cljs-test
  "Adversarial coverage for the machine-identity naming split.

  Machine identity is carried as three distinct facts. These tests pin the
  distinctions so a regression that re-conflates them fails loudly:

   1. **TYPE vs live actor INSTANCE**. The live-actor lifecycle
      traces (`:rf.machine/transition`, `:rf.machine/done`,
      `:rf.machine/system-id-bound` / `-released`, the `:rf.machine.timer/*`
      rows, `:rf.machine.lifecycle/destroyed`) carry the INSTANCE address
      under `:actor-id`, NOT `:machine-id`. `:machine-id` is reserved for the
      registered TYPE / singleton-registration id (it stays on the
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
            [re-frame.trace.tooling :as trace-tooling]
            [re-frame.adapter.reagent :as reagent-adapter]
            [re-frame.machines.test-support :as mtest]))

(use-fixtures :each
  (mtest/make-reset-runtime-fixture
    {:adapter reagent-adapter/adapter}))

(def ^:private snapshot mtest/snapshot)

(defn- ops [traces op]
  (filter #(= op (:operation %)) traces))

;; ---------------------------------------------------------------------------
;; (1) TYPE vs live actor INSTANCE — a SPAWNED actor's lifecycle traces carry
;;     `:actor-id` = the `<type>#<n>` instance, NOT `:machine-id` = the TYPE.
;; ---------------------------------------------------------------------------

(deftest spawned-actor-lifecycle-traces-carry-actor-id-not-machine-id
  (testing "rf2-ws5thu — transition/done/destroyed/system-id rows carry :actor-id (the instance), never :machine-id"
    (let [child {:initial :running
                 :states  {:running {:on {:finish :done}}
                           :done    {:final? true}}}
          parent {:initial :idle
                  :states
                  {:idle {:on {:go :working}}
                   :working
                   {:spawn {:machine-id :idn/child
                            :system-id  :idn/sys
                            :start      [:noop]}
                    :on    {:back :idle}}}}
          traces (atom [])]
      (rf/reg-machine :idn/child child)
      (rf/reg-machine :idn/parent parent)
      (trace-tooling/register-listener! ::idn (fn [ev] (swap! traces conj ev)))
      ;; Spawn the child (deterministic instance id :idn/child#1).
      (rf/dispatch-sync [:idn/parent [:go]])
      (let [spawned-id (get-in (:rf.db/runtime (rf/frame-state-value :rf/default))
                               [:rf.runtime/machines :spawned :idn/parent [:working]])]
        (is (= :idn/child#1 spawned-id)
            "the spawned instance id is <type>#<n>, distinct from the TYPE :idn/child")

        ;; system-id-bound: carries :actor-id = the instance, NOT :machine-id.
        (let [bound (first (ops @traces :rf.machine/system-id-bound))]
          (is (some? bound) ":rf.machine/system-id-bound fired")
          (is (= :idn/child#1 (:actor-id (:tags bound)))
              "system-id-bound carries the live INSTANCE under :actor-id")
          (is (not (contains? (:tags bound) :machine-id))
              "system-id-bound does NOT carry :machine-id (reserved for the TYPE)"))

        ;; The instance's own transition trace addresses it by :actor-id.
        (reset! traces [])
        (rf/dispatch-sync [spawned-id [:finish]])  ;; → :done :final? → auto-destroy
        (let [tr (first (ops @traces :rf.machine/transition))]
          (is (some? tr) "the spawned actor emitted a :rf.machine/transition")
          (is (= spawned-id (:actor-id (:tags tr)))
              "transition carries the live INSTANCE under :actor-id")
          (is (not (contains? (:tags tr) :machine-id))
              "transition does NOT carry :machine-id"))

        ;; done + destroyed + system-id-released all carry :actor-id = instance.
        (let [done (first (ops @traces :rf.machine/done))]
          (is (some? done) ":rf.machine/done fired on the :final? leaf")
          (is (= spawned-id (:actor-id (:tags done)))
              "done carries the finishing actor INSTANCE under :actor-id")
          (is (not (contains? (:tags done) :machine-id))))
        (let [destroyed (first (ops @traces :rf.machine/destroyed))]
          (is (some? destroyed) ":rf.machine/destroyed fired on auto-destroy")
          (is (= spawned-id (:actor-id (:tags destroyed)))
              "destroyed carries the reaped actor INSTANCE under :actor-id"))
        (let [released (first (ops @traces :rf.machine/system-id-released))]
          (is (some? released) ":rf.machine/system-id-released fired")
          (is (= spawned-id (:actor-id (:tags released)))
              "system-id-released carries the live INSTANCE under :actor-id"))))))

(deftest spawned-trace-keeps-machine-id-for-the-type
  (testing "rf2-ws5thu — the spawn observation rows keep :machine-id = the registered TYPE alongside :spawned-id = the instance"
    (let [child  {:initial :running :states {:running {}}}
          parent {:initial :idle
                  :states  {:idle    {:on {:go :working}}
                            :working {:spawn {:machine-id :idn2/child}}}}
          traces (atom [])]
      (rf/reg-machine :idn2/child child)
      (rf/reg-machine :idn2/parent parent)
      (trace-tooling/register-listener! ::idn2 (fn [ev] (swap! traces conj ev)))
      (rf/dispatch-sync [:idn2/parent [:go]])
      (let [spawned (first (ops @traces :rf.machine.lifecycle/spawned))]
        (is (some? spawned) ":rf.machine.lifecycle/spawned fired")
        (is (= :idn2/child (:machine-id (:tags spawned)))
            ":machine-id is the registered TYPE (NOT the instance)")
        (is (= :idn2/child#1 (:spawned-id (:tags spawned)))
            ":spawned-id is the live instance address")
        (is (not= (:machine-id (:tags spawned)) (:spawned-id (:tags spawned)))
            "TYPE and INSTANCE are not conflated — distinct values on one row")))))

;; ---------------------------------------------------------------------------
;; (2) Explicit actor-address INPUT vs declarative invocation PATH.
;;     On ONE declarative :spawn, `:fixed-actor-id` (explicit address, a bare
;;     keyword) and the runtime-stamped `:rf/invoke-id` (invocation path, a
;;     state-path vector) are distinct identity facts.
;; ---------------------------------------------------------------------------

(deftest fixed-actor-id-and-invoke-id-are-distinct-facts
  (testing "rf2-0ggtr5 — explicit :fixed-actor-id (address INPUT) ≠ runtime :rf/invoke-id (invocation PATH)"
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
      ;; The explicit address wins: the runtime registry slot (keyed by the
      ;; invocation PATH [:working]) binds the explicit address, NOT a gensym.
      (is (= :idn3/pinned
             (get-in (:rf.db/runtime (rf/frame-state-value :rf/default))
                     [:rf.runtime/machines :spawned :idn3/parent [:working]]))
          "the explicit :fixed-actor-id address is bound at the invocation-path slot")
      ;; The spawned actor's own :data carries BOTH facts under DISTINCT keys:
      ;;   :rf/self-id   = the actor's instance address (the explicit id here)
      ;;   :rf/invoke-id = the declarative invocation path (a state-path vector)
      (let [child-data (:data (snapshot :idn3/pinned))]
        (is (= :idn3/pinned (:rf/self-id child-data))
            ":rf/self-id is the actor's own (explicit) address — a keyword")
        (is (= [:working] (:rf/invoke-id child-data))
            ":rf/invoke-id is the declarative invocation path — a state-path vector")
        (is (not= (:rf/self-id child-data) (:rf/invoke-id child-data))
            "the explicit-address identity and the invocation-path identity are NOT conflated")
        (is (keyword? (:rf/self-id child-data)))
        (is (vector?  (:rf/invoke-id child-data)))
        ;; The overloaded `:rf/spawn-id` key is not a reserved key.
        (is (not (contains? child-data :rf/spawn-id))
            "the retired reserved key :rf/spawn-id is gone (now :rf/invoke-id)")))))

(deftest singleton-transition-carries-actor-id-equal-to-registration-id
  (testing "rf2-ws5thu — a singleton's transition still carries :actor-id (== its registration id), not :machine-id"
    (let [m {:initial :a :states {:a {:on {:go :b}} :b {}}}
          traces (atom [])]
      (rf/reg-machine :idn4/single m)
      (trace-tooling/register-listener! ::idn4 (fn [ev] (swap! traces conj ev)))
      (rf/dispatch-sync [:idn4/single [:go]])
      (let [tr (first (ops @traces :rf.machine/transition))]
        (is (some? tr))
        (is (= :idn4/single (:actor-id (:tags tr)))
            "a singleton's live address IS its registration id — carried under :actor-id")
        (is (not (contains? (:tags tr) :machine-id))
            "even for a singleton the transition row uses :actor-id, not :machine-id")))))
