(ns re-frame.machine-completion-spawn-incarnation-fence-test
  "rf2-3evq0x — terminally fence machine COMPLETION and SPAWN tails after
  incarnation loss.

  #5849 fenced the machine SCHEMA-validator callbacks (spawn-data,
  update-snapshot, completion-output) to the exact frame incarnation, but two
  callback-bearing tails still crossed the terminal fence after ownership
  changed:

    - Completion: `validate-completion-output!` can return
      `:rf/stale-incarnation`, but `finalize-machine` bound the result to `_`
      and continued — parent resolution / `:on-done` / done trace / teardown /
      HTTP+timer cancellation / classification+spawn-order drop / registrar
      unregister / `:on-error` dispatch / result+fx publication all ran against
      the successor B. A `:rf.machine/done` trace LISTENER that destroys A +
      publishes same-id B was likewise unfenced.
    - Spawn: the accepted spawn cascade checked ownership ONCE (the schema-
      validator gate), then emitted the callback-bearing `:rf.machine.spawn/
      spawned` (and `:rf.machine.lifecycle/spawned` / system-id-collision)
      traces before install / classification / spawn-order / dispatch. A trace
      LISTENER could replace A with B and the old cascade still committed
      framework-owned actions into B's name.

  The ruled policy: already-entered authored callbacks may unwind, but loss of
  exact-incarnation ownership is a TERMINAL fence for every subsequent
  framework-owned action. These fixtures drive the tails DIRECTLY under a bound
  event owner (A's dequeue-time token) with a destroyer that publishes same-id
  B on the callback's / listener's own stack, and assert B stays byte-identical.

  Deterministic + single-threaded — the destroyer runs INSIDE the callback /
  listener, so no latch coordination is needed (the same-id successor B is
  published on the callback's own stack)."
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [re-frame.core :as rf]
            [re-frame.frame :as rf.frame]
            [re-frame.late-bind :as rf.late-bind]
            [re-frame.machines :as rf.machines]
            [re-frame.machines.lifecycle-fx.finalize :as rf.machines.lifecycle-fx.finalize]
            [re-frame.machines.lifecycle-fx.spawn :as rf.machines.lifecycle-fx.spawn]
            [re-frame.machines.spawn-order :as rf.machines.spawn-order]
            [re-frame.machines.test-support :as rf.machines.test-support]
            [re-frame.registrar :as rf.registrar]
            [re-frame.substrate.plain-atom :as rf.substrate.plain-atom]
            [re-frame.trace.tooling :as rf.trace.tooling]))

;; Touch the artefact so the machines registration hooks are wired even when
;; this ns runs in isolation.
(def ^:private _artefact rf.machines/machine-transition)

(use-fixtures :each
  (rf.machines.test-support/make-reset-runtime-fixture {:adapter rf.substrate.plain-atom/adapter}))

;; ---- completion-tail fence (finalize-machine) -----------------------------

(defn- finishing-machine
  "A runtime-stamped, finished singleton spec: rests on a `:final?` leaf whose
  `:output-key` designates the completion result. `frame-id` is stamped as the
  live frame; `schema?` toggles the `[:schemas :output]` completion validator."
  [frame-id schema?]
  (cond-> {:initial   :done
           :rf/frame  frame-id
           :rf/cofx   {:rf/time-ms 0}
           :data      {:result 42}
           :states    {:done {:final? true :output-key :result}}}
    schema? (assoc :schemas {:output ::output-schema})))

(defn- finishing-snapshot []
  {:state :done :data {:result 42}})

(defn- seed-finishing-runtime-db!
  "Install `machine-id`'s finishing snapshot into `frame-id`'s runtime-db so
  the finalize teardown has a live snapshot to (attempt to) reap."
  [frame-id machine-id]
  (rf.frame/swap-runtime-db!
    frame-id
    (fn [rt] (assoc-in rt [:rf.runtime/machines :snapshots machine-id]
                       (finishing-snapshot)))))

(defn- run-completion-finalize
  "Register `machine-id` as a singleton, make frame A, seed its finishing
  snapshot, install a destroyer keyed on `trigger`, then call
  `finalize-machine` DIRECTLY under A's bound event owner. `trigger` is
  `:validator` (a completion-output validator that destroys A) or `:done-trace`
  (a `:rf.machine/done` trace listener that destroys A). Returns the observable
  post-finalize state."
  [frame-a machine-id trigger]
  (rf.machines.spawn-order/reset-all!)
  (let [schema?   (= trigger :validator)]
    (rf/reg-machine machine-id (finishing-machine frame-a schema?))
    (rf/make-frame {:id frame-a})
    (seed-finishing-runtime-db! frame-a machine-id)
    (let [token-a        (rf.frame/frame-incarnation-token frame-a)
          fired?         (atom false)
          done-traces    (atom [])
          destroyed      (atom [])
          orig-validate  (rf.late-bind/get-fn :schemas/validate-with-registered-fn)
          destroy+B!     (fn []
                           (when (compare-and-set! fired? false true)
                             (rf.frame/destroy-frame! frame-a)   ;; destroy A
                             (rf/make-frame {:id frame-a})     ;; same-id B
                             (seed-finishing-runtime-db! frame-a machine-id)))]
      (rf.trace.tooling/register-listener!
        ::completion-fence
        (fn [ev]
          (case (:operation ev)
            :rf.machine/done      (do (swap! done-traces conj ev)
                                      (when (= trigger :done-trace) (destroy+B!)))
            :rf.machine/destroyed (swap! destroyed conj ev)
            nil)))
      (try
        (when (= trigger :validator)
          (rf.late-bind/set-fn! :schemas/validate-with-registered-fn
            (fn [schema _result]
              (when (= schema ::output-schema) (destroy+B!))
              false)))                                       ;; violation verdict
        (let [ret (rf.frame/call-with-event-owner-token frame-a token-a
                    (fn []
                      (rf.machines.lifecycle-fx.finalize/finalize-machine
                        (finishing-machine frame-a schema?)
                        machine-id frame-a (rf.machines.test-support/runtime-db frame-a)
                        (finishing-snapshot) [:some-completing-event] [])))]
          {:fired?       @fired?
           :ret          ret
           :b-runtime    (rf.machines.test-support/runtime-db frame-a)
           :b-snapshot   (rf.machines.test-support/snapshot frame-a machine-id)
           :reg-entry    (rf.registrar/lookup :event machine-id)
           :spawn-order  (rf.machines.spawn-order/frame-order frame-a)
           :done-traces  @done-traces
           :destroyed    @destroyed})
        (finally
          (rf.trace.tooling/unregister-listener! ::completion-fence)
          (rf.late-bind/set-fn! :schemas/validate-with-registered-fn orig-validate))))))

(defn- assert-completion-inert [{:keys [ret b-snapshot reg-entry spawn-order destroyed]} frame-a machine-id]
  (is (= {:rf.db/runtime (rf.machines.test-support/runtime-db frame-a) :fx []} ret)
      "finalize returns the inert outcome — runtime-db untouched, no fx, before every framework-owned action")
  (is (some? b-snapshot)
      "successor B's finishing snapshot was NOT dissoc'd by the A-derived teardown")
  (is (some? reg-entry)
      "successor B's registrar entry was NOT unregistered by the A-derived teardown")
  (is (empty? spawn-order)
      "no A-derived spawn-order mutation landed on B")
  (is (empty? destroyed)
      "no :rf.machine/destroyed trace fired for the A-lost completion"))

(deftest completion-output-validator-loss-fences-teardown
  (testing "a completion-output validator that destroys A + publishes same-id B:
            finalize consumes the :rf/stale-incarnation verdict and returns the
            inert outcome BEFORE teardown / unregister / destroyed trace / fx
            publication. Mutation tooth: binding the validator result to `_`
            runs the whole teardown tail against B."
    (let [frame-a    :rf2-3evq0x/completion-validator-frame
          machine-id :rf2-3evq0x/completion-validator]
      (let [result (run-completion-finalize frame-a machine-id :validator)]
        (is (true? (:fired? result)) "the completion-output validator ran (fence exercised)")
        (assert-completion-inert result frame-a machine-id)))))

(deftest done-trace-listener-loss-fences-teardown
  (testing "a :rf.machine/done trace LISTENER that destroys A + publishes same-id
            B: ownership is rechecked AFTER the callback-bearing done trace, so
            the teardown / unregister / destroyed trace / fx tail is fenced.
            Mutation tooth: without the post-trace recheck the teardown reaps B."
    (let [frame-a    :rf2-3evq0x/done-trace-frame
          machine-id :rf2-3evq0x/done-trace]
      (let [result (run-completion-finalize frame-a machine-id :done-trace)]
        (is (true? (:fired? result)) "the :rf.machine/done trace listener ran (fence exercised)")
        (is (= 1 (count (:done-traces result)))
            "the done trace fired exactly once (it precedes the loss)")
        (assert-completion-inert result frame-a machine-id)))))

(deftest live-owner-completion-tears-down-normally
  (testing "control: a completion whose validator does NOT destroy A tears down
            fully — the fence is scoped to owner-loss only."
    (rf.machines.spawn-order/reset-all!)
    (let [frame-a    :rf2-3evq0x/live-completion-frame
          machine-id :rf2-3evq0x/live-completion]
      (rf/reg-machine machine-id (finishing-machine frame-a false))
      (rf/make-frame {:id frame-a})
      (seed-finishing-runtime-db! frame-a machine-id)
      (let [token-a  (rf.frame/frame-incarnation-token frame-a)
            destroyed (atom [])]
        (rf.trace.tooling/register-listener!
          ::live-completion
          (fn [ev] (when (= :rf.machine/destroyed (:operation ev))
                     (swap! destroyed conj ev))))
        (try
          (let [ret (rf.frame/call-with-event-owner-token frame-a token-a
                      (fn []
                        (rf.machines.lifecycle-fx.finalize/finalize-machine
                          (finishing-machine frame-a false)
                          machine-id frame-a (rf.machines.test-support/runtime-db frame-a)
                          (finishing-snapshot) [:some-completing-event] [])))]
            ;; finalize returns the teardown as a runtime-db EFFECT (it never
            ;; imperatively writes the live frame); check the returned value.
            (is (nil? (get-in (:rf.db/runtime ret)
                              [:rf.runtime/machines :snapshots machine-id]))
                "the live-owner completion tore down the actor's snapshot in the returned runtime-db")
            (is (= 1 (count @destroyed))
                "exactly one :rf.machine/destroyed trace fired for the live completion")
            (is (contains? ret :rf.db/runtime)
                "the live completion returns a runtime-db effect"))
          (finally
            (rf.trace.tooling/unregister-listener! ::live-completion)))))))

;; ---- spawn-tail fence (trace listener replaces A with B) -------------------

(def ^:private spawn-child-instance-id (keyword "rf2-3evq0x" "spawn-child#1"))

(deftest spawn-trace-listener-loss-fences-install
  (testing "a :rf.machine.spawn/spawned trace LISTENER that destroys A +
            publishes same-id B: ownership is rechecked AFTER the callback-
            bearing spawned trace, so the install / classification / spawn-order
            / :start dispatch tail is fenced — B stays byte-identical. Mutation
            tooth: with only the pre-trace cascade gate the A-derived snapshot +
            spawn-order + :start dispatch land on B."
    (rf.machines.spawn-order/reset-all!)
    (rf/reg-machine :rf2-3evq0x/spawn-child
      {:initial :running
       :states  {:running {:on {:go :done}}
                 :done    {:final? true}}})
    (let [frame-a        :rf2-3evq0x/spawn-frame
          fired?         (atom false)
          dispatches     (atom [])
          orig-dispatch! (rf.late-bind/get-fn :router/dispatch!)]
      (rf/make-frame {:id frame-a})
      (let [token-a (rf.frame/frame-incarnation-token frame-a)
            b-birth (atom nil)]
        (rf.trace.tooling/register-listener!
          ::spawn-trace-fence
          (fn [ev]
            (when (and (= :rf.machine.spawn/spawned (:operation ev))
                       (compare-and-set! fired? false true))
              (rf.frame/destroy-frame! frame-a)     ;; destroy A during the spawned trace
              (rf/make-frame {:id frame-a})       ;; publish same-id B
              (reset! b-birth (rf.machines.test-support/runtime-db frame-a)))))
        (try
          ;; Capture (never route) any dispatch the cascade attempts.
          (rf.late-bind/set-fn! :router/dispatch!
                             (fn [ev opts] (swap! dispatches conj [ev opts]) nil))
          (rf.frame/call-with-event-owner-token frame-a token-a
            (fn [] (rf.machines.lifecycle-fx.spawn/spawn-fx {:frame frame-a}
                                   {:machine-id :rf2-3evq0x/spawn-child :start [:go]})))
          (is (true? @fired?) "the :rf.machine.spawn/spawned trace listener ran (fence exercised)")
          (is (nil? (rf.machines.test-support/snapshot frame-a spawn-child-instance-id))
              "no A-derived child snapshot installed onto same-id B")
          (is (= @b-birth (rf.machines.test-support/runtime-db frame-a))
              "B's runtime-db is byte-identical to its birth value (no A-derived install)")
          (is (empty? (rf.machines.spawn-order/frame-order frame-a))
              "no A-derived spawn-order entry recorded against B")
          (is (empty? @dispatches)
              "no :start dispatch fired into B after the spawned-trace loss")
          (finally
            (rf.trace.tooling/unregister-listener! ::spawn-trace-fence)
            (when orig-dispatch!
              (rf.late-bind/set-fn! :router/dispatch! orig-dispatch!))))))))
