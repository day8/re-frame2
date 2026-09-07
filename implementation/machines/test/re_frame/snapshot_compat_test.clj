(ns re-frame.snapshot-compat-test
  "Per Spec 005 §Snapshot shape stability invariants 3 & 4.

  Verifies the handler-entry reconciler fires the right named
  `:rf.error/*` event AND resets the snapshot to a fresh initial-state
  derivative when:

    (3) the snapshot's `:state` is no longer a member of the (possibly
        hot-reloaded) definition's `:states` —
        `:rf.error/machine-state-not-in-definition`.
    (4) the snapshot's `:rf/snapshot-version` disagrees with the
        definition's — `:rf.error/machine-snapshot-version-mismatch`.

  Both checks run BEFORE the bootstrap-pending detection so the new
  initial state's `:entry` actions fire on the same handler call. The
  transient runtime-internal slots (`:rf/spawn-counter`,
  `:rf/after-epoch`, region-scoped epochs) reset with the snapshot."
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [re-frame.core :as rf]
            [re-frame.frame :as rf.frame]
            [re-frame.machines.test-support :as rf.machines.test-support]
            [re-frame.substrate.plain-atom :as rf.substrate.plain-atom]
            [re-frame.trace :as rf.trace]))

(use-fixtures :each
  (rf.machines.test-support/make-reset-runtime-fixture {:adapter rf.substrate.plain-atom/adapter}))

;; snapshot lookup via the shared machines test-support. The bespoke
;; `capture-error-traces` below stays local — it is an intentional
;; manual-stop idiom (returns a `:stop!` thunk the call sites invoke
;; explicitly) rather than a `finally`-scoped block.
(def ^:private snapshot rf.machines.test-support/snapshot)

(defn- capture-error-traces []
  (let [captured (atom [])
        cb-id    (gensym "snapshot-compat-test")]
    (rf.trace/register-listener! cb-id
                              (fn [ev]
                                (when (= :error (:op-type ev))
                                  (swap! captured conj ev))))
    {:captured captured
     :stop!    #(rf.trace/unregister-listener! cb-id)}))

;; ---- (3) state-not-in-definition -----------------------------------------

(deftest state-not-in-definition-resets-to-initial
  (testing "a snapshot whose :state vanished from the new definition resets to :initial and emits :rf.error/machine-state-not-in-definition"
    (let [{:keys [captured stop!]} (capture-error-traces)
          entry-calls (atom [])
          spec        {:initial :idle
                       :actions {:on-enter-idle (fn [_]
                                                  (swap! entry-calls conj :idle)
                                                  nil)}
                       :states  {:idle {:entry :on-enter-idle
                                        :on    {:go :next}}
                                 :next {}}}]
      (try
        (rf/reg-machine :compat/m1 spec)
        ;; Seed an incompatible snapshot directly — a state that's no
        ;; longer in `:states`. Mirrors a hot-reload that dropped a
        ;; state while the snapshot was still live.
        (rf.frame/swap-runtime-db! :rf/default
                              assoc-in
                              [:rf.runtime/machines :snapshots :compat/m1]
                              {:state :gone
                               :data  {:user-stuff 42}})
        (rf/dispatch-sync [:compat/m1 [:go]])
        (let [snap (snapshot :compat/m1)
              ev   (some (fn [e]
                           (when (= :rf.error/machine-state-not-in-definition
                                    (:operation e))
                             e))
                         @captured)]
          (is (some? ev) ":rf.error/machine-state-not-in-definition fired")
          (is (= :compat/m1 (get-in ev [:tags :machine-id])))
          (is (= :gone (get-in ev [:tags :state])))
          (is (= :reset-to-initial (:recovery ev)))
          ;; User :data is GONE — replaced by the fresh initial. Per
          ;; Spec 005 the reset semantics are "replace the snapshot",
          ;; not "merge with new initial".
          (is (nil? (get-in snap [:data :user-stuff])))
          ;; The new :initial state's :entry fired this same handler call
          (is (some #{:idle} @entry-calls))
          ;; The dispatched event :go ran AFTER the reset+bootstrap so
          ;; the post-step snapshot is on the :next state.
          (is (= :next (:state snap))))
        (finally (stop!))))))

;; ---- (3b) parallel region-key parity -------------------------------------
;;
;; A parallel configuration is EVERY declared region active simultaneously.
;; A snapshot MISSING a declared region (corrupted/old restore, or a hot
;; reload that ADDED a region) or carrying an EXTRA/stale region (a hot
;; reload that DROPPED a region) is malformed. The reconciler requires EXACT
;; declared-region key parity: a partial map like `{:left :done}` for a
;; 2-region machine is rejected rather than silently running a partial
;; configuration that could vacuously fire root :on-done / auto-destroy. A
;; key-parity violation resets through :rf.error/machine-state-not-in-definition.

(deftest parallel-missing-region-resets-to-initial
  (testing "a parallel snapshot MISSING a declared region resets to :initial and emits :rf.error/machine-state-not-in-definition (bz0ox.2 / x4s9t.2)"
    (let [{:keys [captured stop!]} (capture-error-traces)
          spec {:type    :parallel
                :data    {}
                ;; No root :on-done — but the reset rebuilds to the
                ;; NON-final initial config, and we deliver a DECLINED event
                ;; (`:noop`) so the snapshot survives for inspection (it does
                ;; not advance to the auto-destroying all-final config).
                :regions {:left  {:initial :run :states {:run {:on {:fin :done}} :done {:final? true}}}
                          :right {:initial :run :states {:run {:on {:fin :done}} :done {:final? true}}}}}]
      (try
        (rf/reg-machine :compat/par-missing spec)
        ;; Seed a partial snapshot missing :right — :left already final.
        ;; Without strict key parity a snapshot missing :right could vacuously
        ;; read all-final and auto-destroy the machine with a region missing.
        (rf.frame/swap-runtime-db! :rf/default
                              assoc-in
                              [:rf.runtime/machines :snapshots :compat/par-missing]
                              {:state {:left :done} :data {:corrupt true}})
        (rf/dispatch-sync [:compat/par-missing [:noop]])
        (let [snap (snapshot :compat/par-missing)
              ev   (some #(when (= :rf.error/machine-state-not-in-definition (:operation %)) %)
                         @captured)]
          (is (some? ev) ":rf.error/machine-state-not-in-definition fired for the missing region")
          (is (= :reset-to-initial (:recovery ev)))
          ;; Reset rebuilt the FULL 2-region initial config; :noop declined.
          (is (= {:left :run :right :run} (:state snap))
              "reset rebuilt the FULL 2-region initial config (both regions present)")
          (is (nil? (get-in snap [:data :corrupt]))
              "corrupt :data discarded by the reset"))
        (finally (stop!))))))

(deftest parallel-extra-region-resets-to-initial
  (testing "a parallel snapshot carrying an EXTRA/stale region resets to :initial and emits :rf.error/machine-state-not-in-definition (bz0ox.2 / x4s9t.2)"
    (let [{:keys [captured stop!]} (capture-error-traces)
          spec {:type    :parallel
                :data    {}
                :regions {:left  {:initial :run :states {:run {:on {:fin :done}} :done {:final? true}}}
                          :right {:initial :run :states {:run {:on {:fin :done}} :done {:final? true}}}}}]
      (try
        (rf/reg-machine :compat/par-extra spec)
        ;; Seed a snapshot with a stale :middle region a hot reload removed.
        (rf.frame/swap-runtime-db! :rf/default
                              assoc-in
                              [:rf.runtime/machines :snapshots :compat/par-extra]
                              {:state {:left :run :right :run :middle :run} :data {}})
        (rf/dispatch-sync [:compat/par-extra [:noop]])
        (let [snap (snapshot :compat/par-extra)
              ev   (some #(when (= :rf.error/machine-state-not-in-definition (:operation %)) %)
                         @captured)]
          (is (some? ev) ":rf.error/machine-state-not-in-definition fired for the extra region")
          (is (= :reset-to-initial (:recovery ev)))
          (is (= #{:left :right} (set (keys (:state snap))))
              "reset dropped the stale :middle region — exactly the declared keys")
          (is (= {:left :run :right :run} (:state snap))
              "reset rebuilt exactly the declared regions at their initial leaves"))
        (finally (stop!))))))

;; ---- (3c) occupied :history pseudo-state ---------------------------------
;;
;; A :type :history node is TARGETABLE but NEVER an occupied active state. A
;; snapshot whose active leaf IS a history node is malformed — node existence
;; alone is not occupiability. The reconciler rejects it and resets.

(deftest occupied-history-pseudo-state-resets-to-initial
  (testing "a flat/compound snapshot whose active leaf is a :type :history pseudo-state resets to :initial and emits :rf.error/machine-state-not-in-definition (bz0ox.2)"
    (let [{:keys [captured stop!]} (capture-error-traces)
          spec {:initial :playing
                :data    {}
                :states  {:playing {:type    :compound
                                    :initial :a
                                    :states  {:a    {:on {:go :b}}
                                              :b    {}
                                              :hist {:type :history}}}}}]
      (try
        (rf/reg-machine :compat/hist spec)
        ;; Seed a snapshot occupying the history pseudo-state — node-at
        ;; resolves it, but it is never occupiable.
        (rf.frame/swap-runtime-db! :rf/default
                              assoc-in
                              [:rf.runtime/machines :snapshots :compat/hist]
                              {:state [:playing :hist] :data {:stale true}})
        (rf/dispatch-sync [:compat/hist [:go]])
        (let [snap (snapshot :compat/hist)
              ev   (some #(when (= :rf.error/machine-state-not-in-definition (:operation %)) %)
                         @captured)]
          (is (some? ev) ":rf.error/machine-state-not-in-definition fired for the occupied history leaf")
          (is (= [:playing :hist] (get-in ev [:tags :state])))
          (is (= :reset-to-initial (:recovery ev)))
          (is (nil? (get-in snap [:data :stale])) "stale :data discarded by the reset")
          ;; Reset rebuilt the initial config [:playing :a], then :go ran.
          (is (= [:playing :b] (:state snap))
              "reset to the real initial leaf [:playing :a] then :go advanced to [:playing :b]"))
        (finally (stop!))))))

;; ---- (4) snapshot-version mismatch ---------------------------------------

(deftest snapshot-version-mismatch-resets-and-emits
  (testing "a snapshot whose :rf/snapshot-version disagrees with the definition's emits :rf.error/machine-snapshot-version-mismatch and resets"
    (let [{:keys [captured stop!]} (capture-error-traces)
          spec  {:initial :idle
                 :meta    {:rf/snapshot-version 2}
                 :states  {:idle {:on {:go :next}}
                           :next {}}}]
      (try
        (rf/reg-machine :compat/m2 spec)
        ;; Seed an old-version snapshot — `:state` is otherwise valid
        ;; in the new definition (so we're isolating version-check
        ;; from state-not-in-definition).
        (rf.frame/swap-runtime-db! :rf/default
                              assoc-in
                              [:rf.runtime/machines :snapshots :compat/m2]
                              {:state :idle
                               :data  {:legacy true}
                               :meta  {:rf/snapshot-version 1}})
        (rf/dispatch-sync [:compat/m2 [:go]])
        (let [snap (snapshot :compat/m2)
              ev   (some (fn [e]
                           (when (= :rf.error/machine-snapshot-version-mismatch
                                    (:operation e))
                             e))
                         @captured)]
          (is (some? ev) ":rf.error/machine-snapshot-version-mismatch fired")
          (is (= :compat/m2 (get-in ev [:tags :machine-id])))
          (is (= 1 (get-in ev [:tags :version-recorded])))
          (is (= 2 (get-in ev [:tags :version-current])))
          (is (= :reset-to-initial (:recovery ev)))
          ;; Legacy :data is gone — version mismatch means restart, not
          ;; patch.
          (is (nil? (get-in snap [:data :legacy])))
          ;; Post-:go the machine should be at :next (reset to :idle,
          ;; then :go transition applied).
          (is (= :next (:state snap))))
        (finally (stop!))))))

;; ---- compatibility recovery on a SPAWNED ACTOR ---------------------------
;;
;; rf2-2dk0. The two reconciler checks above fire at handler-entry against
;; ANY existing snapshot — including a spawned actor's. Recovery replaces the
;; snapshot with a fresh initial derivative, which is right for authored
;; state/data but must NOT discard the framework-owned identity envelope the
;; spawn-fx stamped: root `:rf/machine-type` plus `:data`'s `:rf/self-id` /
;; `:rf/parent-id` / `:rf/invoke-id` / `:rf/join-child`.
;;
;; `:rf/machine-type` is the ONLY thing the lazy resolver
;; (`lifecycle-fx.resolver/spec-from-snapshot`) can resolve a spawned actor's
;; handler from — a spawned actor has no per-instance registrar entry. Drop
;; it and the actor's snapshot is still physically present in runtime-db but
;; no longer addressable: the next dispatch falls through to a genuine
;; `:rf.error/no-such-handler`. That contradicts Spec 005
;; §Liveness is derived from runtime-db ("a spawned actor's liveness IS the
;; presence of its snapshot ... nothing else") and §Reserved snapshot-internal
;; keys §Persistence posture ("the only transient snapshot-root slot is
;; `:rf/bootstrap-pending?`; all other slots ride the snapshot").

(defn- capture-no-handler-traces
  "Capture `:rf.error/no-such-handler` traces — the exact symptom of an
  actor stranded by a recovery that dropped `:rf/machine-type`."
  []
  (let [captured (atom [])
        cb-id    (gensym "snapshot-compat-nsh")]
    (rf.trace/register-listener! cb-id
                              (fn [ev]
                                (when (= :rf.error/no-such-handler (:operation ev))
                                  (swap! captured conj ev))))
    {:captured captured
     :stop!    #(rf.trace/unregister-listener! cb-id)}))

(defn- bumping-child
  "A child TYPE at `version` whose live state is `state`, starting at
  `n`, incrementing `:n` on `:bump`."
  [version state n]
  (cond-> {:initial state
           :data    {:n n}
           :actions {:bump (fn [{data :data}] {:data (update data :n inc)})}
           :states  {state {:on {:bump {:action :bump}}}}}
    version (assoc :meta {:rf/snapshot-version version})))

(defn- spawning-parent
  "A parent that declaratively spawns `child-type` on entering `:working`,
  and leaves `:working` (tearing the child down) on `:stop`."
  [child-type]
  {:initial :idle
   :data    {}
   :states  {:idle    {:on {:start :working}}
             :working {:spawn {:machine-id child-type}
                       :on    {:stop :idle}}}})

(defn- spawned-actor-id
  "The spawned child's instance address, read from the PARENT's own
  `:data` under the spec'd `:rf/spawned` reverse map (keyed by the
  `:spawn`-bearing state's absolute prefix-path) — no hardcoded
  `<type>#<n>` spelling."
  [parent-id invoke-id]
  (get-in (:data (snapshot parent-id)) [:rf/spawned invoke-id]))

(deftest spawned-actor-survives-version-mismatch-recovery
  (testing "rf2-2dk0 — a version-mismatch reset on a SPAWNED actor keeps the
            runtime identity envelope, so the actor stays addressable"
    (let [{:keys [captured stop!]}                   (capture-error-traces)
          {nsh :captured stop-nsh! :stop!}           (capture-no-handler-traces)]
      (try
        (rf/reg-machine :compat/sc-child  (bumping-child 1 :live 0))
        (rf/reg-machine :compat/sc-parent (spawning-parent :compat/sc-child))
        (rf/dispatch-sync [:compat/sc-parent [:start]])
        (let [actor (spawned-actor-id :compat/sc-parent [:working])
              before (snapshot actor)]
          (is (some? actor) "the declarative :spawn allocated an actor id")
          (is (= :compat/sc-child (:rf/machine-type before))
              "spawn stamped the revertible TYPE reference")
          (is (= actor (get-in before [:data :rf/self-id])))
          (is (= :compat/sc-parent (get-in before [:data :rf/parent-id])))
          (is (= [:working] (get-in before [:data :rf/invoke-id])))
          (is (= 0 (get-in before [:data :n])))

          ;; Hot-reload the TYPE with an incompatible version. `:live` is
          ;; still a member of the new definition, so this isolates the
          ;; version check from state-not-in-definition.
          (rf/reg-machine :compat/sc-child (bumping-child 2 :live 100))

          ;; First event: discovers the drift, resets, then runs.
          (rf/dispatch-sync [actor [:bump]])
          ;; Second event to the SAME actor: this is the one that used to
          ;; fall through to :rf.error/no-such-handler.
          (rf/dispatch-sync [actor [:bump]])

          (let [after (snapshot actor)
                mismatches (filter #(= :rf.error/machine-snapshot-version-mismatch
                                       (:operation %))
                                   @captured)]
            (is (= 1 (count mismatches))
                "exactly one version-mismatch recovery occurred")
            (is (= :reset-to-initial (:recovery (first mismatches))))
            (is (empty? @nsh)
                "no :rf.error/no-such-handler — the recovered actor stayed addressable")
            ;; Authored data DID reset to the v2 definition's initial, then
            ;; both bumps ran: 100 → 101 → 102.
            (is (= 102 (get-in after [:data :n]))
                "recovery reset to the v2 initial and BOTH events were processed")
            (is (= 2 (get-in after [:meta :rf/snapshot-version]))
                "the recovered snapshot carries the new definition's version")
            ;; The identity envelope survived.
            (is (= :compat/sc-child (:rf/machine-type after))
                ":rf/machine-type survives the reset (the resolver's only key)")
            (is (= actor (get-in after [:data :rf/self-id]))
                ":rf/self-id survives the reset")
            (is (= :compat/sc-parent (get-in after [:data :rf/parent-id]))
                ":rf/parent-id survives the reset")
            (is (= [:working] (get-in after [:data :rf/invoke-id]))
                ":rf/invoke-id survives the reset"))

          ;; Lifecycle: the recovered child still tears down through its
          ;; parent's declarative exit cascade.
          (rf/dispatch-sync [:compat/sc-parent [:stop]])
          (is (nil? (snapshot actor))
              "the recovered child was destroyed by the parent's exit cascade"))
        (finally (stop-nsh!) (stop!))))))

(deftest spawned-actor-survives-state-not-in-definition-recovery
  (testing "rf2-2dk0 — the state-not-in-definition branch is the second entry
            path into the same recovery, and keeps the identity envelope too"
    (let [{:keys [captured stop!]}         (capture-error-traces)
          {nsh :captured stop-nsh! :stop!} (capture-no-handler-traces)]
      (try
        ;; No version on either side — this isolates the state check.
        (rf/reg-machine :compat/sn-child  (bumping-child nil :old 0))
        (rf/reg-machine :compat/sn-parent (spawning-parent :compat/sn-child))
        (rf/dispatch-sync [:compat/sn-parent [:start]])
        (let [actor (spawned-actor-id :compat/sn-parent [:working])]
          (is (some? actor))
          (is (= :old (:state (snapshot actor))))

          ;; Hot-reload the TYPE, dropping the live state entirely.
          (rf/reg-machine :compat/sn-child (bumping-child nil :ready 100))

          (rf/dispatch-sync [actor [:bump]])
          (rf/dispatch-sync [actor [:bump]])

          (let [after (snapshot actor)
                trips (filter #(= :rf.error/machine-state-not-in-definition
                                  (:operation %))
                              @captured)]
            (is (= 1 (count trips))
                "exactly one state-not-in-definition recovery occurred")
            (is (empty? @nsh)
                "no :rf.error/no-such-handler after the reset")
            (is (= :ready (:state after)) "reset to the new definition's initial")
            (is (= 102 (get-in after [:data :n]))
                "BOTH events processed against the fresh definition")
            (is (= :compat/sn-child (:rf/machine-type after))
                ":rf/machine-type survives the state-not-in-definition reset")
            (is (= actor (get-in after [:data :rf/self-id])))
            (is (= :compat/sn-parent (get-in after [:data :rf/parent-id])))
            (is (= [:working] (get-in after [:data :rf/invoke-id])))))
        (finally (stop-nsh!) (stop!))))))

(deftest spawn-all-child-keeps-join-membership-across-recovery
  (testing "rf2-2dk0 — a :spawn-all child's private :rf/join-child
            exact-attempt record survives compatibility recovery, so its
            completion still folds into the join it belongs to"
    (let [{:keys [captured stop!]}         (capture-error-traces)
          {nsh :captured stop-nsh! :stop!} (capture-no-handler-traces)]
      (try
        (rf/reg-machine :compat/ja-child (bumping-child 1 :live 0))
        (rf/reg-machine :compat/ja-parent
          {:initial :hydrating
           :data    {}
           :states  {:hydrating {:spawn-all
                                 {:children        [{:id :a :machine-id :compat/ja-child}]
                                  :join            :all
                                  :on-all-complete [:go-done]
                                  :on-any-failed   [:ja/cancel]}
                                 :on {:go-done   :done
                                      :ja/cancel :idle}}
                     :done      {}
                     :idle      {}}})
        (rf/dispatch-sync [:compat/ja-parent [:rf.machine.spawn/spawned]])
        (let [actor  (get-in (:data (snapshot :compat/ja-parent))
                             [:rf/spawned [:hydrating] :a])
              before (snapshot actor)]
          (is (some? actor) "the :spawn-all allocated a child actor id")
          (is (some? (get-in before [:data :rf/join-child]))
              "the :spawn-all child carries its private join-membership record")
          (let [join-before (get-in before [:data :rf/join-child])]
            ;; Hot-reload the child TYPE incompatibly.
            (rf/reg-machine :compat/ja-child (bumping-child 2 :live 100))
            (rf/dispatch-sync [actor [:bump]])
            (rf/dispatch-sync [actor [:bump]])
            (let [after (snapshot actor)]
              (is (empty? @nsh)
                  "no :rf.error/no-such-handler — the join child stayed addressable")
              (is (= 1 (count (filter #(= :rf.error/machine-snapshot-version-mismatch
                                          (:operation %))
                                      @captured)))
                  "exactly one recovery occurred")
              (is (= 102 (get-in after [:data :n])) "both events processed")
              (is (= :compat/ja-child (:rf/machine-type after)))
              (is (= join-before (get-in after [:data :rf/join-child]))
                  ":rf/join-child survives the reset byte-identical — the
                   exact-attempt coordinate still names the join attempt this
                   instance belongs to"))))
        (finally (stop-nsh!) (stop!))))))

;; ---- both-absent passes silently -----------------------------------------

(deftest no-version-on-either-side-passes-silently
  (testing "absent-on-both sides is the standard happy path — no error trace"
    (let [{:keys [captured stop!]} (capture-error-traces)
          spec {:initial :idle
                :states  {:idle {:on {:go :next}}
                          :next {}}}]
      (try
        (rf/reg-machine :compat/m3 spec)
        (rf/dispatch-sync [:compat/m3 [:go]])
        (rf/dispatch-sync [:compat/m3 [:go]]) ;; second dispatch — existing snapshot
        (is (not-any? (fn [e]
                        (#{:rf.error/machine-snapshot-version-mismatch
                           :rf.error/machine-state-not-in-definition}
                         (:operation e)))
                      @captured)
            "no compat-trip events fired for the happy path")
        (is (= :next (:state (snapshot :compat/m3))))
        (finally (stop!))))))

;; ---- compatible-version + valid-state passes silently --------------------

(deftest matching-version-and-valid-state-passes-silently
  (testing "version stamps agree AND state still in definition — no error trace"
    (let [{:keys [captured stop!]} (capture-error-traces)
          spec  {:initial :idle
                 :meta    {:rf/snapshot-version 7}
                 :states  {:idle {:on {:go :next}}
                           :next {}}}]
      (try
        (rf/reg-machine :compat/m4 spec)
        ;; Seed a snapshot whose version matches and whose :state is
        ;; in the definition.
        (rf.frame/swap-runtime-db! :rf/default
                              assoc-in
                              [:rf.runtime/machines :snapshots :compat/m4]
                              {:state :idle
                               :data  {:preserved true}
                               :meta  {:rf/snapshot-version 7}})
        (rf/dispatch-sync [:compat/m4 [:go]])
        (let [snap (snapshot :compat/m4)]
          (is (not-any? (fn [e]
                          (#{:rf.error/machine-snapshot-version-mismatch
                             :rf.error/machine-state-not-in-definition}
                           (:operation e)))
                        @captured)
              "no compat-trip events fired for the matching-version happy path")
          ;; The seeded :data slot is preserved across the compatible
          ;; snapshot — no reset happened.
          (is (true? (get-in snap [:data :preserved])))
          (is (= :next (:state snap))))
        (finally (stop!))))))
