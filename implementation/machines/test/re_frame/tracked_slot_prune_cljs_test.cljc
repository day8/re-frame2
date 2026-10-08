(ns re-frame.tracked-slot-prune-cljs-test
  "A tracked single-`:spawn` exit prunes a slot naming an actor that is dead
  for that incarnation, or a live same-id replacement the slot does not own,
  without a second teardown (Spec 005 §Destroy is silent-idempotent). An
  imperative keyword destroy leaves the slot naming the dead actor, so slot
  presence must never read as liveness."
  (:require
   #?(:clj  [clojure.test :refer [deftest is use-fixtures]]
      :cljs [cljs.test :refer-macros [deftest is use-fixtures]])
   [re-frame.core :as rf]
   [re-frame.machines]
   [re-frame.machines.test-support :as rf.machines.test-support]
   #?@(:clj  [[re-frame.substrate.plain-atom :as rf.substrate.plain-atom]]
       :cljs [[re-frame.adapter.reagent :as rf.adapter.reagent]])))

(use-fixtures :each
  (rf.machines.test-support/make-reset-runtime-fixture
    #?(:clj  {:adapter rf.substrate.plain-atom/adapter}
       :cljs {:adapter rf.adapter.reagent/adapter}))
  rf.machines.test-support/trace-capture-fixture)

(def ^:private snapshot rf.machines.test-support/snapshot)

(defn- destroyed-reasons-for [actor-id]
  (into []
        (comp (filter #(= actor-id (:actor-id (:tags %))))
              (map (comp :reason :tags)))
        (rf.machines.test-support/events-of :rf.machine/destroyed)))

(defn- tracked-slot [parent-id]
  (get-in (rf.machines.test-support/runtime-db)
          [:rf.runtime/machines :spawned parent-id [:working]]))

(defn- destroy-imperatively! [actor-id]
  (rf/reg-event ::imperative-destroy
    (fn [_ [_ a]] {:fx [[:rf.machine/destroy a]]}))
  (rf/dispatch-sync [::imperative-destroy actor-id]))

(defn- reg-tracked-parent!
  "Register a parent whose `:working` state tracks one `:spawn` of `spawn-spec`
  and whose child's `:exit` bumps `exit-count`, start it into `:working`, and
  return the tracked child's actor id."
  [parent-kw child-kw spawn-spec exit-count]
  (rf/reg-machine child-kw
    {:initial :running
     :data    {}
     :states  {:running {:exit (fn [_] (swap! exit-count inc) {})}}})
  (rf/reg-machine parent-kw
    {:initial :idle
     :states  {:idle    {:on {:start :working}}
               :working {:spawn spawn-spec
                         :on    {:stop :idle}}}})
  (rf/dispatch-sync [parent-kw [:start]])
  (tracked-slot parent-kw))

(deftest imperative-destroy-then-parent-exit-tears-down-exactly-once
  (let [exit-count (atom 0)
        child-id   (reg-tracked-parent! :tsp/p1 :tsp/p1-child
                                        {:machine-id :tsp/p1-child}
                                        exit-count)]
    (destroy-imperatively! child-id)
    (is (= [nil child-id] [(snapshot child-id) (tracked-slot :tsp/p1)])
        "precondition: the actor is dead and the tracked slot still names it")
    (rf/dispatch-sync [:tsp/p1 [:stop]])
    (is (= {:slot nil :mirror nil :reasons [:explicit] :exits 1}
           {:slot    (tracked-slot :tsp/p1)
            :mirror  (get-in (snapshot :tsp/p1) [:data :rf/spawned [:working]])
            :reasons (destroyed-reasons-for child-id)
            :exits   @exit-count})
        "the stale slot and mirror are pruned with no second destroyed trace or :exit")))

(deftest repeated-tracked-exit-is-silent-idempotent
  ;; The exit cascade emits the tracked form whether or not the slot is still
  ;; set, e.g. after the child reached a final state.
  (let [exit-count (atom 0)]
    (reg-tracked-parent! :tsp/p2 :tsp/p2-child {:machine-id :tsp/p2-child} exit-count)
    (rf/dispatch-sync [:tsp/p2 [:stop]])
    (rf.machines.test-support/reset-captured!)
    (rf/reg-event ::refire-tracked
      (fn [_ _] {:fx [[:rf.machine/destroy {:rf/parent-id :tsp/p2
                                            :rf/invoke-id [:working]}]]}))
    (rf/dispatch-sync [::refire-tracked])
    (is (= [[] [] 1]
           [(rf.machines.test-support/events-of :rf.machine/destroyed)
            (rf.machines.test-support/events-of :rf.error/machine-destroy-bad-arg)
            @exit-count])
        "re-firing the tracked form at a pruned slot emits nothing and runs no :exit")))

(deftest stale-slot-cannot-destroy-same-id-replacement
  (reg-tracked-parent! :tsp/p3 :tsp/p3-child
                       {:machine-id :tsp/p3-child :fixed-actor-id :tsp/fixed-3}
                       (atom 0))
  (destroy-imperatively! :tsp/fixed-3)
  ;; A hand-emitted same-id respawn carries no ownership stamps for the old slot.
  (rf/reg-event ::respawn-fixed
    (fn [_ _] {:fx [[:rf.machine/spawn {:machine-id     :tsp/p3-child
                                        :fixed-actor-id :tsp/fixed-3}]]}))
  (rf/dispatch-sync [::respawn-fixed])
  (is (= :tsp/fixed-3 (tracked-slot :tsp/p3))
      "precondition: the stale slot still names the reused id")
  (rf.machines.test-support/reset-captured!)
  (rf/dispatch-sync [:tsp/p3 [:stop]])
  (is (= {:slot nil :replacement-live? true :destroyed []}
         {:slot              (tracked-slot :tsp/p3)
          :replacement-live? (some? (snapshot :tsp/fixed-3))
          :destroyed         (rf.machines.test-support/events-of :rf.machine/destroyed)})
      "the stale slot is pruned and the replacement survives the old parent's exit"))
