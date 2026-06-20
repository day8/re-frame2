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
            [re-frame.frame :as frame]
            [re-frame.machines.test-support :as mtest]
            [re-frame.substrate.plain-atom :as plain-atom]
            [re-frame.trace :as trace]))

(use-fixtures :each
  (mtest/make-reset-runtime-fixture {:adapter plain-atom/adapter}))

;; snapshot lookup via the shared machines test-support. The bespoke
;; `capture-error-traces` below stays local — it is an intentional
;; manual-stop idiom (returns a `:stop!` thunk the call sites invoke
;; explicitly) rather than a `finally`-scoped block.
(def ^:private snapshot mtest/snapshot)

(defn- capture-error-traces []
  (let [captured (atom [])
        cb-id    (gensym "snapshot-compat-test")]
    (trace/register-listener! cb-id
                              (fn [ev]
                                (when (= :error (:op-type ev))
                                  (swap! captured conj ev))))
    {:captured captured
     :stop!    #(trace/unregister-listener! cb-id)}))

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
        (frame/swap-runtime-db! :rf/default
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
        (frame/swap-runtime-db! :rf/default
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
        (frame/swap-runtime-db! :rf/default
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
        (frame/swap-runtime-db! :rf/default
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
        (frame/swap-runtime-db! :rf/default
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
        (frame/swap-runtime-db! :rf/default
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
