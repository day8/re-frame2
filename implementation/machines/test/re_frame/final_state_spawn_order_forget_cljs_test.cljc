(ns re-frame.final-state-spawn-order-forget-cljs-test
  "rf2-p6fw3q — the `:final?`-state AUTO-DESTROY path (`finalize-machine`)
  MUST forget the finished actor from the per-frame `spawn-order` channel,
  exactly like the explicit-destroy path (`destroy/teardown-live-actor!`,
  destroy.cljc step 7).

  `record!` runs when a spawn commits and `forget!` when a destroy tears
  the actor down, so the channel tracks exactly the actors this process
  spawned and has not yet destroyed. If finalize omits the `forget!`, a
  finished actor STAYS recorded even though its snapshot is dissoc'd —
  bookkeeping that names an actor the frame no longer holds.

  These JVM+CLJS tests pin: (1) finalize forgets the actor from
  spawn-order; (2) a subsequent stale destroy is a SILENT no-op — no phantom
  destroyed trace, no re-fired release; (3) frame-destroy emits no phantom
  for the finished actor.

  Which of the three actually DISCRIMINATE changed under rf2-1vlyg, and it
  is worth saying rather than leaving a reader to assume. This channel used
  to be `actor-live?`'s standalone alive-or-gone bit, and frame destroy
  unioned it into its walk membership — so a stranded entry made (2) emit a
  phantom `:rf.machine/destroyed` and (3) a phantom
  `:rf.machine.lifecycle/destroyed :parent-frame-destroyed`. Both consumers
  now confirm against the LIVE runtime-db first (rf2-1vlyg audit: no
  runtime-state install clears this cache, so a `restore-epoch!` that
  rewinds past a spawn leaves it naming a DISCARDED actor, and believing it
  reaped the dead). A stranded entry can therefore no longer resurrect a
  dissoc'd actor by itself.

  So (2) and (3) still pin their contracts — an already-finished actor is
  never re-reaped — but they no longer FAIL on a missing `forget!`;
  measured by deleting it from `finalize-machine`, which reds (1) and the
  `frame-order` assertion inside (3) and leaves their trace assertions
  green. (1), which asserts on `frame-order` directly, is the pin that
  keeps the `forget!` honest, and it is why this suite still catches
  rf2-p6fw3q's regression.

  Named `*-cljs-test.cljc` so both cognitect.test-runner (JVM, plain-atom)
  and shadow-cljs (CLJS, reagent) discover it."
  (:require
   #?(:clj  [clojure.test :refer [deftest is testing use-fixtures]]
      :cljs [cljs.test :refer-macros [deftest is testing use-fixtures]])
   [re-frame.core :as rf]
   [re-frame.events :as rf.events]
   [re-frame.machines]
   [re-frame.machines.spawn-order :as rf.machines.spawn-order]
   [re-frame.machines.test-support :as rf.machines.test-support]
   #?@(:clj  [[re-frame.substrate.plain-atom :as rf.substrate.plain-atom]]
       :cljs [[re-frame.adapter.reagent :as rf.adapter.reagent]])))

(use-fixtures :each
  (rf.machines.test-support/make-reset-runtime-fixture
    #?(:clj  {:adapter rf.substrate.plain-atom/adapter}
       :cljs {:adapter rf.adapter.reagent/adapter}))
  rf.machines.test-support/trace-capture-fixture)

(def ^:private snapshot rf.machines.test-support/snapshot)

;; A stub `:rf.resource/release-owner` handler recording every released
;; owner — the machine side dispatches this by NAME (machines never
;; :requires resources), so a stub stands in for the real resources
;; artefact. Mirrors `actor_resource_owner_release_cljs_test`.
(def ^:private released (atom []))

(defn- install-release-stub! []
  (reset! released [])
  (rf.events/reg-event :rf.resource/release-owner
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
  (get-in (rf.machines.test-support/runtime-db frame-id)
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
      (is (some #(= spawned-id %) (rf.machines.spawn-order/frame-order :rf/default))
          "the live spawned child is recorded in spawn-order")
      ;; Drive the child into its :final? leaf → auto-destroy.
      (rf/dispatch-sync [spawned-id [:fin]])
      (is (nil? (snapshot spawned-id))
          "the finished child's snapshot was synchronously dissoc'd")
      (is (not-any? #(= spawned-id %) (rf.machines.spawn-order/frame-order :rf/default))
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
      (rf.machines.test-support/reset-captured!)
      (rf/dispatch-sync [::stale-destroy spawned-id])
      (is (empty? (rf.machines.test-support/events-of :rf.machine/destroyed))
          "the stale destroy fired NO phantom :rf.machine/destroyed trace
           (silent-idempotent) — without the forget! it RE-RUNS teardown")
      (is (= [[:machine spawned-id]] @released)
          "the stale destroy did NOT re-fire the resource release
           (still exactly one release) — a re-run would double it"))))

;; ===========================================================================
;; 3. frame-destroy emits no phantom for the finished actor
;; ===========================================================================

(defn- parent-frame-destroyed-actor-ids []
  (->> (rf.machines.test-support/events-of :rf.machine.lifecycle/destroyed)
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
      (is (not-any? #(= spawned-id %) (rf.machines.spawn-order/frame-order :fsf3/scratch))
          "the finished child was forgotten from the scratch frame's spawn-order")
      ;; Scope the trace check to the frame-destroy window.
      (rf.machines.test-support/reset-captured!)
      (rf/destroy-frame! :fsf3/scratch)
      (is (not (contains? (parent-frame-destroyed-actor-ids) spawned-id))
          "frame-destroy emitted NO :parent-frame-destroyed phantom for the
           already-finished child (its spawn-order entry was cleared at finish)"))))
