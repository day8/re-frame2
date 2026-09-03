(ns re-frame.tracked-slot-prune-cljs-test
  "rf2-s2bsmw — the tracked single-`:spawn` exit prunes DEAD slots without
  double-destroying actors.

  The pre-fix defect: an imperative keyword destroy tears the tracked child
  down but leaves the parent's `[:spawned p i]` slot naming the now-dead
  actor (its teardown-args carry no parent/invoke). The later declarative
  parent exit treated slot PRESENCE as liveness (`destroy-resolved!`'s
  `slot-live?` override), re-ran the whole teardown pipeline against the
  dead actor — a second `:exit` cascade, a second `:rf.machine/destroyed`
  trace, destroyed reasons `[:explicit :explicit]` — violating Spec 005's
  silent-idempotent destroy law and risking duplicate exit cascades,
  resource releases, and terminal/diagnostic evidence.

  The fix separates stale ownership-slot cleanup from actor lifecycle
  teardown: a slot naming an actor that is dead FOR THAT EXACT INCARNATION
  is pruned (slot + parent `:rf/spawned` mirror only — `prune-tracked-slot!`)
  with no second teardown; a live same-id REPLACEMENT the slot does not own
  (`slot-owned-incarnation?` reads the snapshot's framework-reserved
  `:rf/parent-id` / `:rf/invoke-id` ownership stamps) is likewise left
  untouched; only a live, owned child takes the normal exact-incarnation
  `:explicit` destroy.

  Fixtures instrument lifecycle traces AND side effects (the child's
  `:exit` action bumps a counter), not just final registry state — mutating
  the tracked branch back to slot-presence liveness fires two traces and
  two `:exit` runs.

  The file is named `*-cljs-test.cljc` so it's discovered by both
  cognitect-style JVM runs and shadow-cljs (`cljs-test$` ns-regexp)."
  (:require
   #?(:clj  [clojure.test :refer [deftest is testing use-fixtures]]
      :cljs [cljs.test :refer-macros [deftest is testing use-fixtures]])
   [re-frame.core :as rf]
   ;; load the machines artefact so its fx handlers + late-bind hooks are
   ;; installed when this ns runs in isolation.
   [re-frame.machines]
   [re-frame.machines.test-support :as rf.machines.test-support]
   #?@(:clj  [[re-frame.substrate.plain-atom :as rf.substrate.plain-atom]]
       :cljs [[re-frame.adapter.reagent :as rf.adapter.reagent]])))

(use-fixtures :each
  (rf.machines.test-support/make-reset-runtime-fixture
    #?(:clj  {:adapter rf.substrate.plain-atom/adapter}
       :cljs {:adapter rf.adapter.reagent/adapter}))
  rf.machines.test-support/trace-capture-fixture)

(defn- destroyed-reasons-for [actor-id]
  (into []
        (comp (filter #(= actor-id (:actor-id (:tags %))))
              (map (comp :reason :tags)))
        (rf.machines.test-support/events-of :rf.machine/destroyed)))

(defn- tracked-slot [parent-id]
  (get-in (rf.machines.test-support/runtime-db)
          [:rf.runtime/machines :spawned parent-id [:working]]))

(defn- parent-mirror [parent-id]
  (get-in (rf.machines.test-support/snapshot parent-id) [:data :rf/spawned [:working]]))

(defn- destroy-imperatively!
  "Fire one imperative keyword destroy through an ordinary app event."
  [actor-id]
  (rf/reg-event ::imperative-destroy
    (fn [_ [_ a]] {:fx [[:rf.machine/destroy a]]}))
  (rf/dispatch-sync [::imperative-destroy actor-id]))

(defn- reg-tracked-parent!
  "Register a single tracked-`:spawn` parent whose child's `:exit` bumps
  `exit-count` (side-effect instrumentation). `spawn-spec` is the parent's
  `:spawn` map. Starts the parent into `:working` and returns the tracked
  child's actor id."
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

;; ---------------------------------------------------------------------------
;; the P2 repro — imperative destroy, then declarative parent exit
;; ---------------------------------------------------------------------------

(deftest imperative-destroy-then-parent-exit-tears-down-exactly-once
  (testing "rf2-s2bsmw — direct keyword destroy followed by the parent's
            declarative exit yields EXACTLY ONE stopped transition: one
            :rf.machine/destroyed trace, one :exit cascade run; the later
            tracked exit removes the stale slot + parent mirror WITHOUT
            re-running lifecycle teardown. Pre-fix: reasons
            [:explicit :explicit] and a second :exit run."
    (let [exit-count (atom 0)
          child-id   (reg-tracked-parent! :tsp/p1 :tsp/p1-child
                                          {:machine-id :tsp/p1-child}
                                          exit-count)]
      (is (keyword? child-id) "tracked child spawned")
      (is (some? (parent-mirror :tsp/p1)) "parent :rf/spawned mirror bound")
      ;; Imperative destroy: full teardown ONCE; the tracked slot survives
      ;; (its teardown-args carry no parent/invoke) — the bug's precondition.
      (destroy-imperatively! child-id)
      (is (nil? (rf.machines.test-support/snapshot child-id)) "actor dead after imperative destroy")
      (is (= child-id (tracked-slot :tsp/p1))
          "precondition: the tracked slot still names the now-dead actor")
      (is (= [:explicit] (destroyed-reasons-for child-id))
          "one destroyed trace from the imperative destroy")
      (is (= 1 @exit-count) "one :exit run from the imperative destroy")
      ;; Declarative parent exit: stale-slot cleanup only.
      (rf/dispatch-sync [:tsp/p1 [:stop]])
      (is (nil? (tracked-slot :tsp/p1)) "the stale slot is pruned")
      (is (nil? (parent-mirror :tsp/p1)) "the parent :rf/spawned mirror is pruned")
      (is (= [:explicit] (destroyed-reasons-for child-id))
          (str "NO second destroyed trace — Spec 005 silent-idempotent "
               "destroy law; saw " (destroyed-reasons-for child-id)))
      (is (= 1 @exit-count)
          "NO second :exit cascade for the already-dead incarnation"))))

(deftest repeated-tracked-exit-is-silent-idempotent
  (testing "rf2-s2bsmw — re-firing the tracked destroy form against the
            already-pruned slot is a silent no-op (no trace, no error)"
    (let [exit-count (atom 0)
          child-id   (reg-tracked-parent! :tsp/p2 :tsp/p2-child
                                          {:machine-id :tsp/p2-child}
                                          exit-count)]
      (destroy-imperatively! child-id)
      (rf/dispatch-sync [:tsp/p2 [:stop]])
      (rf.machines.test-support/reset-captured!)
      ;; Hand-fire the tracked form again — the slot is already gone.
      (rf/reg-event ::refire-tracked
        (fn [_ _] {:fx [[:rf.machine/destroy {:rf/parent-id :tsp/p2
                                              :rf/invoke-id [:working]}]]}))
      (rf/dispatch-sync [::refire-tracked])
      (is (empty? (rf.machines.test-support/events-of :rf.machine/destroyed))
          "repeat exit emits no destroyed trace")
      (is (empty? (rf.machines.test-support/events-of :rf.error/machine-destroy-bad-arg))
          "repeat exit raises no error — silent idempotence")
      (is (= 1 @exit-count) "no further :exit runs"))))

;; ---------------------------------------------------------------------------
;; exact incarnation identity — same-id replacement survives the stale slot
;; ---------------------------------------------------------------------------

(deftest stale-slot-cannot-destroy-same-id-replacement
  (testing "rf2-s2bsmw — destroy → same-id replacement (spawned through a
            DIFFERENT path, so it carries different ownership stamps) → old
            parent exit: the stale slot is pruned but the live replacement
            is NOT destroyed — exact incarnation identity is respected"
    (let [exit-count (atom 0)]
      (reg-tracked-parent! :tsp/p3 :tsp/p3-child
                           {:machine-id     :tsp/p3-child
                            :fixed-actor-id :tsp/fixed-3}
                           exit-count)
      (is (= :tsp/fixed-3 (tracked-slot :tsp/p3)))
      ;; Kill the tracked incarnation.
      (destroy-imperatively! :tsp/fixed-3)
      (is (nil? (rf.machines.test-support/snapshot :tsp/fixed-3)))
      ;; Hand-emit a same-id REPLACEMENT through the imperative spawn path —
      ;; no :rf/parent-id / :rf/invoke-id args, so its :data carries no
      ;; ownership stamps for the old slot.
      (rf/reg-event ::respawn-fixed
        (fn [_ _] {:fx [[:rf.machine/spawn {:machine-id     :tsp/p3-child
                                            :fixed-actor-id :tsp/fixed-3}]]}))
      (rf/dispatch-sync [::respawn-fixed])
      (is (some? (rf.machines.test-support/snapshot :tsp/fixed-3)) "replacement incarnation live")
      (is (= :tsp/fixed-3 (tracked-slot :tsp/p3))
          "precondition: the STALE slot still names the (reused) id")
      (rf.machines.test-support/reset-captured!)
      ;; Old parent exits: the stale slot may prune itself, never the
      ;; replacement.
      (rf/dispatch-sync [:tsp/p3 [:stop]])
      (is (nil? (tracked-slot :tsp/p3)) "the stale slot is pruned")
      (is (some? (rf.machines.test-support/snapshot :tsp/fixed-3))
          "the same-id replacement incarnation SURVIVES the old parent's exit")
      (is (empty? (rf.machines.test-support/events-of :rf.machine/destroyed))
          "no destroyed trace fired against the replacement"))))

;; ---------------------------------------------------------------------------
;; ordinary live tracked exit — unchanged
;; ---------------------------------------------------------------------------

(deftest live-owned-tracked-exit-keeps-full-teardown
  (testing "rf2-s2bsmw — the ordinary declarative exit of a still-live owned
            child retains the full teardown: one :exit run, one :explicit
            destroyed trace, slot + mirror cleared"
    (let [exit-count (atom 0)
          child-id   (reg-tracked-parent! :tsp/p4 :tsp/p4-child
                                          {:machine-id :tsp/p4-child}
                                          exit-count)]
      (rf/dispatch-sync [:tsp/p4 [:stop]])
      (is (nil? (rf.machines.test-support/snapshot child-id)) "live child torn down on exit")
      (is (= [:explicit] (destroyed-reasons-for child-id))
          "exactly one :explicit destroyed trace")
      (is (= 1 @exit-count) "exactly one :exit cascade run")
      (is (nil? (tracked-slot :tsp/p4)) "slot cleared")
      (is (nil? (parent-mirror :tsp/p4)) "parent mirror cleared"))))
