(ns re-frame.spawn-on-spawn-throw-test
  "A throwing `:on-spawn` advisory callback routes through the documented
  machine action exception contract — exactly as a throwing `:action` /
  `:data`-fn does (spawn_data_fn_form_test §4) — and MUST NOT escape as a
  generic `:rf.error/handler-exception`.

  `:on-spawn` is a machine action (resolved through `:on-spawn-actions`
  with `:actions` fallback, Spec 005 §Declarative `:spawn`). The callback is
  invoked inside the machine layer's try/catch with Result conversion, so a
  throwing observer is caught and routed through the machine-scoped
  `:rf.error/machine-action-exception` diagnostic, the frame-tagged trace,
  and the atomic-rollback discipline — never aborting the whole event as an
  uncaught handler failure.

  The contract, verified here for BOTH single `:spawn` and `:spawn-all`:

   1. A throwing `:on-spawn` emits EXACTLY ONE
      `:rf.error/machine-action-exception` (op-type `:error`) stamped with
      the stable action id `:rf.machine.spawn/on-spawn` and the machine's frame.
   2. NO generic `:rf.error/handler-exception` is emitted — the throw is
      caught inside the machine layer, not at the core dispatch boundary.
   3. ATOMIC ROLLBACK: no parent snapshot, no spawned child snapshot, and
      no `[:rf.runtime/machines :spawned ...]` registry slot commit — the
      accumulated effects are dropped.

  All tests run on the JVM through the plain-atom substrate, exercising
  the full lifecycle handler (the path where the bug surfaced)."
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [re-frame.core :as rf]
            [re-frame.machines.test-support :as mtest]
            [re-frame.substrate.plain-atom :as plain-atom]))

(use-fixtures :each
  (mtest/make-reset-runtime-fixture {:adapter plain-atom/adapter}))

;; runtime-db / snapshot lookup via the shared machines test-support
;; — no hardcoded `[:rf.runtime/machines …]` path.
(def ^:private snapshot mtest/snapshot)
(def ^:private frame-db mtest/runtime-db)

(defn- capture-traces!
  "Drive `thunk` while recording every emitted trace envelope; return the
  vector of envelopes (deterministic on the JVM — no wall-clock / random).
  The VALUE-returning counterpart to `mtest/with-trace-capture` (which is a
  scope macro, not a value-producing call) — kept local because the call
  sites below bind the returned vector in a `let`. Still guarantees
  unregister in a `finally` via the shared helper's listener."
  [thunk]
  (mtest/with-trace-capture seen
    (thunk)
    @seen))

(defn- action-exceptions
  "The `:rf.error/machine-action-exception` envelopes among `traces`."
  [traces]
  (filter #(and (= :error (:op-type %))
                (= :rf.error/machine-action-exception (:operation %)))
          traces))

(defn- handler-exceptions
  "The generic `:rf.error/handler-exception` envelopes among `traces` —
  the throw must NOT reach the core dispatch boundary, so this is expected
  to be empty."
  [traces]
  (filter #(= :rf.error/handler-exception (:operation %)) traces))

;; ---- (1) single :spawn — throwing :on-spawn ------------------------------

(deftest single-spawn-on-spawn-throw-routes-to-machine-action-exception
  (testing "a throwing :on-spawn callback on a single :spawn emits exactly
   one :rf.error/machine-action-exception (action-id :rf.machine.spawn/on-spawn,
   frame-tagged), NO generic :rf.error/handler-exception, and commits no
   snapshot or registry slot"
    (let [child  {:initial :running :data {} :states {:running {}}}
          parent {:initial :idle
                  :on-spawn-actions
                  {:boom (fn [_] (throw (ex-info "on-spawn boom" {:why :test})))}
                  :states
                  {:idle    {:on {:start :working}}
                   :working {:spawn {:machine-id :worker/proc
                                      :on-spawn   :boom}}}}]
      (rf/reg-machine :worker/proc child)
      (rf/reg-machine :sup/on-spawn-throw parent)
      (let [traces (capture-traces!
                     #(rf/dispatch-sync [:sup/on-spawn-throw [:start]]))
            errs   (action-exceptions traces)]
        ;; (1) exactly one machine-action-exception, correctly tagged.
        (is (= 1 (count errs))
            "exactly one :rf.error/machine-action-exception for the throw")
        (let [{:keys [tags]} (first errs)]
          (is (= :rf.machine.spawn/on-spawn (:action-id tags))
              "the error carries the stable :rf.machine.spawn/on-spawn action id")
          (is (= :sup/on-spawn-throw (:actor-id tags))
              "the error is scoped to the spawning parent LIVE actor (rf2-yyvtk5)")
          (is (= :rf/default (:frame tags))
              "the error is frame-tagged (epoch-capture admission)")
          (is (some? (:exception tags))
              "the originating throwable rides the error trace"))
        ;; (2) no generic handler-exception — the throw never escaped.
        (is (zero? (count (handler-exceptions traces)))
            "NO generic :rf.error/handler-exception — caught in the machine layer")
        ;; (3) atomic rollback.
        (is (nil? (snapshot :worker/proc#1))
            "no spawned child snapshot committed (effects dropped)")
        (let [parent-snap (snapshot :sup/on-spawn-throw)]
          (is (or (nil? parent-snap) (= :idle (:state parent-snap)))
              "parent did not commit the :working transition"))
        (is (nil? (get-in (frame-db) [:rf.runtime/machines :spawned :sup/on-spawn-throw]))
            "no [:rf.runtime/machines :spawned ...] registry slot committed")))))

;; ---- (2) :spawn-all — one throwing child :on-spawn -----------------------

(deftest spawn-all-on-spawn-throw-routes-to-machine-action-exception
  (testing "a throwing :on-spawn on ONE :spawn-all child emits exactly one
   :rf.error/machine-action-exception (action-id :rf.machine.spawn/on-spawn,
   frame-tagged), NO generic :rf.error/handler-exception, and terminates
   the spawn reducer with full atomic rollback — no child snapshot, no
   registry slot, no parent transition commit"
    (let [child  {:initial :running :data {} :states {:running {}}}
          parent {:initial :idle
                  :on-spawn-actions
                  {:ok   (fn [_] nil)
                   :boom (fn [_] (throw (ex-info "spawn-all on-spawn boom"
                                                 {:why :test})))}
                  :states
                  {:idle      {:on {:fan-out :hydrating}}
                   :hydrating {:spawn-all
                               {:children
                                [{:id :a :machine-id :hydra/leaf :on-spawn :ok}
                                 {:id :b :machine-id :hydra/leaf :on-spawn :boom}]
                                :on-child-done   :child/done
                                :on-child-error  :child/error
                                :on-all-complete [:done]}}}}]
      (rf/reg-machine :hydra/leaf child)
      (rf/reg-machine :sup/all-throw parent)
      (let [traces (capture-traces!
                     #(rf/dispatch-sync [:sup/all-throw [:fan-out]]))
            errs   (action-exceptions traces)]
        ;; (1) exactly one machine-action-exception, correctly tagged.
        (is (= 1 (count errs))
            "exactly one :rf.error/machine-action-exception for the throwing child")
        (let [{:keys [tags]} (first errs)]
          (is (= :rf.machine.spawn/on-spawn (:action-id tags))
              "the error carries the stable :rf.machine.spawn/on-spawn action id")
          (is (= :sup/all-throw (:actor-id tags))
              "the error is scoped to the spawning parent LIVE actor (rf2-yyvtk5)")
          (is (= :rf/default (:frame tags))
              "the error is frame-tagged"))
        ;; (2) no generic handler-exception.
        (is (zero? (count (handler-exceptions traces)))
            "NO generic :rf.error/handler-exception for the spawn-all throw")
        ;; (3) atomic rollback — neither child commits (the whole spawn
        ;; phase is dropped, not just the throwing child).
        (is (nil? (snapshot :hydra/leaf#1))
            "no child snapshot committed — the spawn phase rolled back atomically")
        (is (nil? (snapshot :hydra/leaf#2))
            "the throwing child's slot also did not commit")
        (let [parent-snap (snapshot :sup/all-throw)]
          (is (or (nil? parent-snap) (= :idle (:state parent-snap)))
              "parent did not commit the :hydrating transition"))
        (is (nil? (get-in (frame-db) [:rf.runtime/machines :spawned :sup/all-throw]))
            "no spawn-all join-state / registry slot committed")))))

;; ---- (3) regression guard — a NON-throwing :on-spawn still spawns ---------

(deftest non-throwing-on-spawn-still-spawns-cleanly
  (testing "the try/catch wrap is transparent on the happy path — a quiet
   :on-spawn observer still fires, the actor spawns, and NO error trace fires"
    (let [observed (atom nil)
          child    {:initial :running :data {} :states {:running {}}}
          parent   {:initial :idle
                    :on-spawn-actions
                    {:observe (fn [{:keys [id]}] (reset! observed id) nil)}
                    :states
                    {:idle    {:on {:start :working}}
                     :working {:spawn {:machine-id :worker/proc
                                        :on-spawn   :observe}}}}]
      (rf/reg-machine :worker/proc child)
      (rf/reg-machine :sup/quiet parent)
      (let [traces (capture-traces!
                     #(rf/dispatch-sync [:sup/quiet [:start]]))]
        (is (= :worker/proc#1 @observed)
            ":on-spawn still fires (observed the spawned id) on the happy path")
        (is (some? (snapshot :worker/proc#1))
            "the actor spawned — the success path is unchanged")
        (is (zero? (count (action-exceptions traces)))
            "no machine-action-exception when :on-spawn does not throw")
        (is (zero? (count (handler-exceptions traces)))
            "no handler-exception on the happy path")))))
