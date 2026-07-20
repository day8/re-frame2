(ns re-frame.final-state-spawn-order-forget-cljs-test
  "rf2-p6fw3q — the `:final?`-state AUTO-DESTROY path (`finalize-machine`)
  MUST forget the finished actor from the per-frame `spawn-order` channel,
  exactly like the explicit-destroy path (`destroy/teardown-live-actor!`,
  destroy.cljc step 7).

  `spawn-order/frame-order` is `actor-live?`'s \"most reliable
  alive-or-gone bit\" (destroy.cljc:83): `record!` runs unconditionally on
  spawn, `forget!` unconditionally on destroy. If finalize omits the
  `forget!`, a finished actor STAYS recorded even though its snapshot is
  dissoc'd — so:

    - a later stale `[:rf.machine/destroy id]` sees it LIVE (spawn-order
      hit) → `teardown-live-actor!` RE-RUNS → a PHANTOM
      `:rf.machine/destroyed` trace + a RE-FIRED resource release, violating
      the silent-idempotent destroy contract (Spec 005 §Destroy is
      silent-idempotent); and
    - frame-destroy's spawn-order walk (frame_destroy.cljc segment (b))
      emits a PHANTOM `:rf.machine.lifecycle/destroyed :parent-frame-destroyed`
      for the already-finished actor.

  These JVM+CLJS tests pin: (1) finalize forgets the actor from
  spawn-order; (2) a subsequent stale destroy is a SILENT no-op — no phantom
  destroyed trace, no re-fired release; (3) frame-destroy emits no phantom
  for the finished actor.

  Named `*-cljs-test.cljc` so both cognitect.test-runner (JVM, plain-atom)
  and shadow-cljs (CLJS, reagent) discover it."
  (:require
   #?(:clj  [clojure.test :refer [deftest is testing use-fixtures]]
      :cljs [cljs.test :refer-macros [deftest is testing use-fixtures]])
   [re-frame.core :as rf]
   [re-frame.events :as events]
   [re-frame.machines]
   [re-frame.machines.spawn-order :as spawn-order]
   [re-frame.machines.test-support :as mtest]
   #?@(:clj  [[re-frame.substrate.plain-atom :as plain-atom]]
       :cljs [[re-frame.adapter.reagent :as reagent-adapter]])))

(use-fixtures :each
  (mtest/make-reset-runtime-fixture
    #?(:clj  {:adapter plain-atom/adapter}
       :cljs {:adapter reagent-adapter/adapter}))
  mtest/trace-capture-fixture)

(def ^:private snapshot mtest/snapshot)

;; A stub `:rf.resource/release-owner` handler recording every released
;; owner — the machine side dispatches this by NAME (machines never
;; :requires resources), so a stub stands in for the real resources
;; artefact. Mirrors `actor_resource_owner_release_cljs_test`.
(def ^:private released (atom []))

(defn- install-release-stub! []
  (reset! released [])
  (events/reg-event :rf.resource/release-owner
    (fn [_ [_ {:keys [owner]}]]
      (swap! released conj owner)
      {})))

;; A parent that spawns one child of a registered TYPE on entry to
;; :working (the synthetic `[:rf.machine.spawn/spawned]` initial-entry
;; event drives the `:spawn`). The child reaches a `:final?` leaf on
;; `[:fin]` → auto-destroy.
(defn- reg-parent-and-child! [parent-id child-id]
  (rf/reg-machine child-id
    {:initial :running
     :data    {}
     :states  {:running {:on {:fin :done}}
               :done    {:final? true}}})
  (rf/reg-machine parent-id
    {:initial :working
     :states  {:working {:spawn {:machine-id child-id}}}}))

(defn- spawn-child! [parent-id frame-id]
  (rf/dispatch-sync [parent-id [:rf.machine.spawn/spawned]] {:frame frame-id})
  (get-in (mtest/runtime-db frame-id)
          [:rf.runtime/machines :spawned parent-id [:working]]))

;; ===========================================================================
;; 1. finalize forgets the finished actor from spawn-order
;; ===========================================================================

(deftest final-state-forgets-actor-from-spawn-order
  (testing "rf2-p6fw3q — a spawned child reaching a :final? leaf is removed
            from the per-frame spawn-order channel by finalize-machine"
    (reg-parent-and-child! :fsf1/parent :fsf1/child)
    (let [spawned-id (spawn-child! :fsf1/parent :rf/default)]
      (is (some? spawned-id) "child was spawned")
      (is (some #(= spawned-id %) (spawn-order/frame-order :rf/default))
          "the live spawned child is recorded in spawn-order")
      ;; Drive the child into its :final? leaf → auto-destroy.
      (rf/dispatch-sync [spawned-id [:fin]])
      (is (nil? (snapshot spawned-id))
          "the finished child's snapshot was synchronously dissoc'd")
      (is (not-any? #(= spawned-id %) (spawn-order/frame-order :rf/default))
          "the finished child was forgotten from spawn-order (the fix) —
           without it the liveness bit strands and destroy re-runs"))))

;; ===========================================================================
;; 2. a stale destroy after finish is a SILENT no-op
;;    (no phantom destroyed trace, no re-fired release)
;; ===========================================================================

(deftest stale-destroy-after-final-is-silent-no-op
  (testing "rf2-p6fw3q — after a spawned child auto-destroys on :final?, a
            later [:rf.machine/destroy id] is a silent no-op: NO phantom
            :rf.machine/destroyed trace, NO re-fired resource release"
    (install-release-stub!)
    (reg-parent-and-child! :fsf2/parent :fsf2/child)
    (rf/reg-event ::stale-destroy
      (fn [_ [_ id]] {:fx [[:rf.machine/destroy id]]}))
    (let [spawned-id (spawn-child! :fsf2/parent :rf/default)]
      ;; Finish the child — finalize releases the [:machine spawned-id] owner
      ;; once (via its appended :fx) and emits ONE :rf.machine/destroyed.
      (rf/dispatch-sync [spawned-id [:fin]])
      (is (= [[:machine spawned-id]] @released)
          "the finish released the actor's [:machine actor-id] owner exactly once")
      ;; Scope the phantom check to the stale-destroy window only.
      (mtest/reset-captured!)
      (rf/dispatch-sync [::stale-destroy spawned-id])
      (is (empty? (mtest/events-of :rf.machine/destroyed))
          "the stale destroy fired NO phantom :rf.machine/destroyed trace
           (silent-idempotent) — without the forget! it RE-RUNS teardown")
      (is (= [[:machine spawned-id]] @released)
          "the stale destroy did NOT re-fire the resource release
           (still exactly one release) — a re-run would double it"))))

;; ===========================================================================
;; 3. frame-destroy emits no phantom for the finished actor
;; ===========================================================================

(defn- parent-frame-destroyed-actor-ids []
  (->> (mtest/events-of :rf.machine.lifecycle/destroyed)
       (filter #(= :parent-frame-destroyed (-> % :tags :reason)))
       (map #(-> % :tags :actor-id))
       set))

(deftest frame-destroy-after-final-emits-no-phantom
  (testing "rf2-p6fw3q — a spawned child that finished (auto-destroyed) is
            NOT re-reaped by frame-destroy: no phantom
            :rf.machine.lifecycle/destroyed :parent-frame-destroyed for it"
    (rf/make-frame {:id :fsf3/scratch :doc "final-state / frame-destroy scratch frame"})
    (reg-parent-and-child! :fsf3/parent :fsf3/child)
    (let [spawned-id (spawn-child! :fsf3/parent :fsf3/scratch)]
      (is (some? spawned-id) "child spawned in the scratch frame")
      ;; Finish the child in the scratch frame.
      (rf/dispatch-sync [spawned-id [:fin]] {:frame :fsf3/scratch})
      (is (nil? (snapshot :fsf3/scratch spawned-id))
          "the child auto-destroyed in the scratch frame")
      (is (not-any? #(= spawned-id %) (spawn-order/frame-order :fsf3/scratch))
          "the finished child was forgotten from the scratch frame's spawn-order")
      ;; Scope the trace check to the frame-destroy window.
      (mtest/reset-captured!)
      (rf/destroy-frame! :fsf3/scratch)
      (is (not (contains? (parent-frame-destroyed-actor-ids) spawned-id))
          "frame-destroy emitted NO :parent-frame-destroyed phantom for the
           already-finished child (its spawn-order entry was cleared at finish)"))))
