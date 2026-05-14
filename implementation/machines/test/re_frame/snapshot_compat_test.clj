(ns re-frame.snapshot-compat-test
  "Per rf2-fasdp / Spec 005 §Snapshot shape stability invariants 3 & 4.

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
            [re-frame.substrate.plain-atom :as plain-atom]
            [re-frame.test-support :as test-support]
            [re-frame.trace :as trace]))

(use-fixtures :each
  (test-support/reset-runtime-fixture {:adapter plain-atom/adapter}))

(defn- snapshot
  [machine-id]
  (get-in (rf/get-frame-db :rf/default) [:rf/machines machine-id]))

(defn- capture-error-traces []
  (let [captured (atom [])
        cb-id    (gensym "snapshot-compat-test")]
    (trace/register-trace-cb! cb-id
                              (fn [ev]
                                (when (= :error (:op-type ev))
                                  (swap! captured conj ev))))
    {:captured captured
     :stop!    #(trace/remove-trace-cb! cb-id)}))

;; ---- (3) state-not-in-definition -----------------------------------------

(deftest state-not-in-definition-resets-to-initial
  (testing "a snapshot whose :state vanished from the new definition resets to :initial and emits :rf.error/machine-state-not-in-definition"
    (let [{:keys [captured stop!]} (capture-error-traces)
          entry-calls (atom [])
          spec        {:initial :idle
                       :actions {:on-enter-idle (fn [_ _]
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
        (frame/swap-frame-db! :rf/default
                              assoc-in
                              [:rf/machines :compat/m1]
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
        (frame/swap-frame-db! :rf/default
                              assoc-in
                              [:rf/machines :compat/m2]
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
        (frame/swap-frame-db! :rf/default
                              assoc-in
                              [:rf/machines :compat/m4]
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
