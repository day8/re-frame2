(ns re-frame.machine-lifecycle-schema-incarnation-fence-test
  "Fence machine lifecycle SCHEMA callbacks to the exact frame incarnation
  (rf2-vxgfnd.153 — part of the incarnation-fencing family established by the
  merged destroy contract #5818).

  A machine schema validator is APPLICATION code (the late-bound
  `:schemas/validate-with-registered-fn` adapter running an app-declared
  schema). It can synchronously destroy the frame incarnation A that owns the
  in-flight event and publish a same-id successor B before returning. The
  spawn cascade reads A's runtime-db ONCE (`old-rt`), derives an id allocation
  and initial snapshot from it, then runs the schema validator; every
  subsequent framework action — the snapshot / system-id / spawn-slot install,
  per-instance classification lowering, the spawn-order record, the two
  spawned traces, and the `:start` (or synthetic) dispatch — is A-derived
  tail. A bare-id `swap-runtime-db!` at install time resolves the CURRENT
  incarnation (B), so without a fence the A-derived allocation + bookkeeping
  land on B.

  These fixtures make CONFORMING, FAILING, and THROWING schema callbacks
  destroy A and publish same-id B, and assert that B's runtime-db, spawn-order,
  and trace/dispatch state stay byte-identical after the callback loses A. The
  sibling boundaries — `validate-update-snapshot-data!` (escape-hatch write)
  and `validate-completion-output!` (finalize diagnostic) — are covered too.

  Deterministic + single-threaded: the destroyer runs INSIDE the validator
  callback, so no latch coordination is needed (unlike the cross-thread
  core incarnation barriers) — the same-id successor B is published on the
  callback's own stack, exactly the synchronous re-entry the fence guards."
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [re-frame.core :as rf]
            [re-frame.frame :as frame]
            [re-frame.late-bind :as late-bind]
            [re-frame.machines :as machines]
            [re-frame.machines.data-validation :as data-validation]
            [re-frame.machines.lifecycle-fx.spawn :as spawn]
            [re-frame.machines.lifecycle-fx.update-snapshot :as update-snapshot]
            [re-frame.machines.spawn-order :as spawn-order]
            [re-frame.machines.test-support :as mtest]
            [re-frame.substrate.plain-atom :as plain-atom]))

;; Touch the artefact so the machines registration hooks are wired even when
;; this ns runs in isolation (`re-frame.machines` require has side effects).
(def ^:private _artefact machines/machine-transition)

(use-fixtures :each
  (mtest/make-reset-runtime-fixture {:adapter plain-atom/adapter}))

;; The instance address the fallback allocator mints for the first
;; hand-emitted `:machine-id` spawn: `<type>#1`. Constructed the way
;; `allocate-actor-id-in-runtime-db` builds it (avoids `#` in a keyword
;; literal).
(def ^:private child-instance-id (keyword "rf2-vxgfnd153" "child#1"))
(def ^:private live-child-instance-id (keyword "rf2-vxgfnd153" "live-child#1"))

;; ---- spawn cascade fence --------------------------------------------------

(defn- run-destroyer-spawn
  "Register a schema-bearing child, install a `::child-schema` validator that
  (on its first call) destroys frame `frame-a` and publishes a same-id
  successor B, then run `:rf.machine/spawn` for `frame-a` under a bound event
  owner (A's dequeue-time token). `on-validate` supplies the validator's
  verdict for the destroyed-A call (returns a boolean, or throws). Returns a
  map of the observable post-spawn state. Restores every hook/listener.

  Drives `spawn-fx` DIRECTLY under `call-with-event-owner-token` (the pattern
  `spawn-destroyed-frame-atomicity-test` uses) rather than through
  `dispatch-sync`: the destroyer publishes same-id B via `rf/make-frame` on the
  callback's own stack, and a live `dispatch-sync` drain's same-thread guard
  forbids nested frame creation. The direct call also bypasses the fx-walk's
  outer owner fence, so the assertions pin the MACHINE-layer fence itself."
  [frame-a on-validate]
  (spawn-order/reset-all!)
  (rf/reg-machine :rf2-vxgfnd153/child
    {:initial :running
     :data    {:seed :a}
     :schemas {:data ::child-schema}
     :states  {:running {:on {:go :done}}
               :done    {:final? true}}})
  (rf/make-frame {:id frame-a})
  (let [token-a        (frame/frame-incarnation-token frame-a)
        b-birth        (atom ::unset)
        fired?         (atom false)
        threw?         (atom false)
        spawn-traces   (atom [])
        fail-traces    (atom [])
        dispatches     (atom [])
        orig-validate  (late-bind/get-fn :schemas/validate-with-registered-fn)
        orig-dispatch! (late-bind/get-fn :router/dispatch!)]
    (rf/register-listener! :trace ::spawn-fence
      (fn [ev]
        (let [op (:operation ev)]
          (cond
            (contains? #{:rf.machine.spawn/spawned :rf.machine.lifecycle/spawned} op)
            (swap! spawn-traces conj ev)

            (and (= :rf.error/schema-validation-failure op)
                 (= :spawn (get-in ev [:tags :phase])))
            (swap! fail-traces conj ev)))))
    (try
      ;; Capture (never route) any dispatch the spawn cascade attempts — a
      ;; `[<child> <start>]` for an actor that was never legitimately installed
      ;; is itself a fence violation.
      (late-bind/set-fn! :router/dispatch!
                         (fn [ev opts] (swap! dispatches conj [ev opts]) nil))
      (late-bind/set-fn! :schemas/validate-with-registered-fn
        (fn [schema data]
          (if (and (= schema ::child-schema)
                   (compare-and-set! fired? false true))
            (do
              (frame/destroy-frame! frame-a)     ;; destroy incarnation A
              (rf/make-frame {:id frame-a})       ;; publish same-id successor B
              (reset! b-birth (mtest/runtime-db frame-a))
              (on-validate data))                 ;; verdict / throw
            true)))
      (try
        (frame/call-with-event-owner-token frame-a token-a
          (fn [] (spawn/spawn-fx {:frame frame-a}
                                 {:machine-id :rf2-vxgfnd153/child})))
        (catch Throwable _ (reset! threw? true)))
      {:b-birth      @b-birth
       :b-after      (mtest/runtime-db frame-a)
       :child-snap   (mtest/snapshot frame-a child-instance-id)
       :spawn-order  (spawn-order/frame-order frame-a)
       :spawn-traces @spawn-traces
       :fail-traces  @fail-traces
       :dispatches   @dispatches
       :fired?       @fired?
       :threw?       @threw?}
      (finally
        (rf/unregister-listener! :trace ::spawn-fence)
        (late-bind/set-fn! :schemas/validate-with-registered-fn orig-validate)
        (late-bind/set-fn! :router/dispatch! orig-dispatch!)))))

(defn- assert-successor-untouched
  "Assertions shared by every destroyer variant: the callback ran, and NONE of
  the A-derived spawn tail landed on successor B."
  [result]
  (is (true? (:fired? result))
      "the schema validator callback ran (the fence boundary was exercised)")
  (is (false? (:threw? result))
      "the obsolete callback (even a throwing one) does not escape after A is lost")
  (is (nil? (:child-snap result))
      "no A-derived snapshot was installed onto same-id B")
  (is (= (:b-birth result) (:b-after result))
      "B's runtime-db is byte-identical to its birth value (no A-derived allocation)")
  (is (empty? (:spawn-order result))
      "no A-derived spawn-order entry was recorded against B")
  (is (empty? (:spawn-traces result))
      "no :rf.machine.spawn/spawned / :rf.machine.lifecycle/spawned trace fired for B")
  (is (empty? (:dispatches result))
      "no :start (or synthetic) dispatch was fired into B"))

(deftest conforming-callback-destroy-does-not-mutate-successor
  (testing "a CONFORMING (returns true) validator that destroys A + publishes
            same-id B: the whole install / classification / spawn-order / trace
            / dispatch cascade is fenced — B stays byte-identical.
            Mutation tooth: dropping the `(continue?)` cascade gate installs
            the A-derived snapshot + spawn-counter + spawn-order onto B."
    (assert-successor-untouched
      (run-destroyer-spawn :rf2-vxgfnd153/conforming-frame (fn [_] true)))))

(deftest failing-callback-destroy-attributes-no-diagnostic-to-successor
  (testing "a FAILING (returns false) validator that destroys A: the
            :rf.error/schema-validation-failure :phase :spawn diagnostic is
            SUPPRESSED (it would otherwise be attributed to same-id B), and no
            cascade lands on B. Mutation tooth: leaving `validate-spawn-data!`
            on the `(constantly true)` continuation emits the failure trace
            after A was lost."
    (let [result (run-destroyer-spawn :rf2-vxgfnd153/failing-frame (fn [_] false))]
      (assert-successor-untouched result)
      (is (empty? (:fail-traces result))
          "no schema-validation-failure diagnostic was attributed to B after A lost the callback"))))

(deftest throwing-callback-destroy-is-inert-against-successor
  (testing "a THROWING validator that destroys A: once A is lost the throw is
            SWALLOWED at the validator (it belongs to A's dead continuation)
            and no A-derived tail lands on B. Mutation tooth: leaving
            `validate-spawn-data!` on the `(constantly true)` continuation
            re-throws through the machine cascade after A was lost (`:threw?`
            true)."
    (assert-successor-untouched
      (run-destroyer-spawn :rf2-vxgfnd153/throwing-frame
                           (fn [_] (throw (ex-info "schema validator lost A" {})))))))

(deftest live-owner-spawn-installs-normally
  (testing "control: a CONFORMING validator that does NOT destroy A leaves the
            spawn cascade fully intact — the fence is scoped to owner-loss only,
            so normal successful spawn behavior stays green."
    (spawn-order/reset-all!)
    (rf/reg-machine :rf2-vxgfnd153/live-child
      {:initial :running
       :data    {:seed :a}
       :schemas {:data ::live-child-schema}
       :states  {:running {:on {:go :done}}
                 :done    {:final? true}}})
    (rf/reg-event :rf2-vxgfnd153/live-spawn-event
      (fn [_ _] {:fx [[:rf.machine/spawn {:machine-id :rf2-vxgfnd153/live-child}]]}))
    (let [frame-a      :rf2-vxgfnd153/live-frame
          spawn-traces (atom [])
          orig         (late-bind/get-fn :schemas/validate-with-registered-fn)]
      (rf/make-frame {:id frame-a})
      (rf/register-listener! :trace ::live-spawn
        (fn [ev] (when (= :rf.machine.spawn/spawned (:operation ev))
                   (swap! spawn-traces conj ev))))
      (try
        (late-bind/set-fn! :schemas/validate-with-registered-fn (fn [_ _] true))
        (rf/dispatch-sync [:rf2-vxgfnd153/live-spawn-event] {:frame frame-a})
        (is (some? (mtest/snapshot frame-a live-child-instance-id))
            "the child snapshot installed (live-owner spawn is unaffected)")
        (is (seq @spawn-traces)
            "the :rf.machine.spawn/spawned trace fired for the live-owner spawn")
        (is (= [live-child-instance-id] (spawn-order/frame-order frame-a))
            "the spawn-order entry was recorded for the live-owner spawn")
        (finally
          (rf/unregister-listener! :trace ::live-spawn)
          (late-bind/set-fn! :schemas/validate-with-registered-fn orig))))))

;; ---- update-snapshot escape-hatch fence -----------------------------------

(deftest update-snapshot-validator-fences-write-to-successor
  (testing "the :rf.machine/update-snapshot escape hatch: a validator that
            destroys A + publishes same-id B (with a distinct sentinel
            snapshot) must NOT merge the A-derived patch onto B. Mutation
            tooth: leaving `validate-update-snapshot-data!` on the
            `(constantly true)` continuation returns truthy, so the caller's
            `(when validator (write))` merges the A patch onto same-id B."
    (rf/reg-machine :rf2-vxgfnd153/sing
      {:initial :running
       :data    {:v :a-init}
       :schemas {:data ::sing-schema}
       :states  {:running {:on {:go :done}}
                 :done    {:final? true}}})
    (let [frame-a :rf2-vxgfnd153/us-frame
          fired?  (atom false)
          orig    (late-bind/get-fn :schemas/validate-with-registered-fn)]
      (rf/make-frame {:id frame-a})
      (let [token-a (frame/frame-incarnation-token frame-a)]
        ;; Seed A's singleton snapshot so the escape hatch has a live target.
        (frame/swap-runtime-db! frame-a
          (fn [rt] (assoc-in rt [:rf.runtime/machines :snapshots :rf2-vxgfnd153/sing]
                             {:state :running :data {:v :a-init}})))
        (try
          (late-bind/set-fn! :schemas/validate-with-registered-fn
            (fn [schema _data]
              (if (and (= schema ::sing-schema)
                       (compare-and-set! fired? false true))
                (do
                  (frame/destroy-frame! frame-a)      ;; destroy A
                  (rf/make-frame {:id frame-a})        ;; same-id B
                  (frame/swap-runtime-db! frame-a
                    (fn [rt] (assoc-in rt [:rf.runtime/machines :snapshots :rf2-vxgfnd153/sing]
                                       {:state :running :data {:v :b-sentinel}})))
                  true)                                ;; conforms → red would write
                true)))
          ;; Drive the escape-hatch fx DIRECTLY under A's bound event owner (the
          ;; destroyer publishes same-id B via make-frame on its own stack).
          (frame/call-with-event-owner-token frame-a token-a
            (fn [] (update-snapshot/update-snapshot-fx
                     {:frame frame-a}
                     {:rf/machine-id :rf2-vxgfnd153/sing
                      :rf/patch      {:data {:v :patched}}})))
          (is (true? @fired?) "the escape-hatch schema validator callback ran")
          (is (= {:v :b-sentinel}
                 (:data (mtest/snapshot frame-a :rf2-vxgfnd153/sing)))
              "B's snapshot :data is untouched — the A-derived patch did not merge onto same-id B")
          (finally
            (late-bind/set-fn! :schemas/validate-with-registered-fn orig)))))))

;; ---- completion-output finalize diagnostic fence --------------------------

(defn- run-completion-validation
  "Invoke `validate-completion-output!` under a bound event owner (`token-a`
  for frame `id`) with a validator that reports a violation and — when
  `destroy?` — first destroys A + publishes same-id B. Returns the count of
  `:where :machine-output` schema-validation-failure traces emitted."
  [id destroy?]
  (rf/make-frame {:id id})
  (let [token-a (frame/frame-incarnation-token id)
        traces  (atom [])
        orig    (late-bind/get-fn :schemas/validate-with-registered-fn)]
    (rf/register-listener! :trace ::completion-fence
      (fn [ev] (when (and (= :rf.error/schema-validation-failure (:operation ev))
                          (= :machine-output (get-in ev [:tags :where])))
                 (swap! traces conj ev))))
    (try
      (late-bind/set-fn! :schemas/validate-with-registered-fn
        (fn [schema _result]
          (when (and destroy? (= schema ::output-schema))
            (frame/destroy-frame! id)     ;; destroy A
            (rf/make-frame {:id id}))      ;; same-id B
          false))                          ;; violation
      (frame/call-with-event-owner-token id token-a
        (fn []
          (data-validation/validate-completion-output!
            :rf2-vxgfnd153/completion-actor
            {:schemas {:output ::output-schema}}
            "bad-output")))
      (count @traces)
      (finally
        (rf/unregister-listener! :trace ::completion-fence)
        (late-bind/set-fn! :schemas/validate-with-registered-fn orig)))))

(deftest completion-output-validator-honors-exact-continuation
  (testing "a completion-output validator that destroys A + publishes same-id B
            suppresses the :where :machine-output diagnostic (it would
            otherwise be attributed to B, a frame that never ran this
            completion). Mutation tooth: leaving `validate-completion-output!`
            unfenced emits the boundary trace after A was lost."
    (is (zero? (run-completion-validation :rf2-vxgfnd153/completion-frame true))
        "no :machine-output diagnostic is attributed to B after the validator lost A")))

(deftest completion-output-validator-emits-while-owner-live
  (testing "control: a violation while A STILL owns the completion emits exactly
            one :machine-output trace — schema error reporting is unaffected by
            the fence."
    (is (= 1 (run-completion-validation :rf2-vxgfnd153/completion-live-frame false))
        "a violation with A live emits exactly one :machine-output diagnostic")))
