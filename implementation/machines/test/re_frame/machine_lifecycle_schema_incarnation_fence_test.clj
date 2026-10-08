(ns re-frame.machine-lifecycle-schema-incarnation-fence-test
  "Machine lifecycle SCHEMA callbacks are fenced to the exact frame
  incarnation. A schema validator is application code: it can destroy the
  frame incarnation A that owns the in-flight event and publish a same-id
  successor B before it returns. Nothing A-derived — the spawn install and
  its bookkeeping, traces and `:start` dispatch, an escape-hatch
  `:rf.machine/update-snapshot` write, or a schema-failure diagnostic — may
  then land on B. The destroyer runs on the callback's own stack, so every
  fixture is deterministic and single-threaded."
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [re-frame.core :as rf]
            [re-frame.frame :as rf.frame]
            [re-frame.late-bind :as rf.late-bind]
            [re-frame.machines :as rf.machines]
            [re-frame.machines.data-validation :as rf.machines.data-validation]
            [re-frame.machines.lifecycle-fx.spawn :as rf.machines.lifecycle-fx.spawn]
            [re-frame.machines.lifecycle-fx.update-snapshot :as rf.machines.lifecycle-fx.update-snapshot]
            [re-frame.machines.spawn-order :as rf.machines.spawn-order]
            [re-frame.machines.test-support :as rf.machines.test-support]
            [re-frame.substrate.plain-atom :as rf.substrate.plain-atom]))

;; Touch the artefact so the machines registration hooks are wired even when
;; this ns runs in isolation (`re-frame.machines` require has side effects).
(def ^:private _artefact rf.machines/machine-transition)

(use-fixtures :each
  (rf.machines.test-support/make-reset-runtime-fixture {:adapter rf.substrate.plain-atom/adapter}))

;; ---- spawn cascade fence --------------------------------------------------

(defn- run-destroyer-spawn
  "Register a schema-bearing child, install a `::child-schema` validator that
  (on its first call) destroys frame `frame-a`, publishes a same-id successor B
  and returns `(on-validate data)`, then run `:rf.machine/spawn` for `frame-a`
  under A's event owner. Returns the observable post-spawn state.

  Drives `spawn-fx` DIRECTLY under `call-with-event-owner-token` rather than
  through `dispatch-sync`: a live drain forbids nested frame creation, and the
  direct call bypasses the fx-walk's outer owner fence, so the assertions pin
  the MACHINE-layer fence itself."
  [frame-a on-validate]
  (rf.machines.spawn-order/reset-all!)
  (rf/reg-machine :rf2-vxgfnd153/child
    {:initial :running
     :data    {:seed :a}
     :schemas {:data ::child-schema}
     :states  {:running {:on {:go :done}}
               :done    {:final? true}}})
  (rf/make-frame {:id frame-a})
  (let [token-a        (rf.frame/frame-incarnation-token frame-a)
        b-birth        (atom ::unset)
        fired?         (atom false)
        threw?         (atom false)
        spawn-traces   (atom [])
        fail-traces    (atom [])
        dispatches     (atom [])
        orig-validate  (rf.late-bind/get-fn :schemas/validate-with-registered-fn)
        orig-dispatch! (rf.late-bind/get-fn :router/dispatch!)]
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
      ;; Capture (never route) any dispatch the spawn cascade attempts.
      (rf.late-bind/set-fn! :router/dispatch!
                         (fn [ev opts] (swap! dispatches conj [ev opts]) nil))
      (rf.late-bind/set-fn! :schemas/validate-with-registered-fn
        (fn [schema data]
          (if (and (= schema ::child-schema)
                   (compare-and-set! fired? false true))
            (do
              (rf.frame/destroy-frame! frame-a)
              (rf/make-frame {:id frame-a})
              (reset! b-birth (rf.machines.test-support/runtime-db frame-a))
              (on-validate data))
            true)))
      (try
        (rf.frame/call-with-event-owner-token frame-a token-a
          (fn [] (rf.machines.lifecycle-fx.spawn/spawn-fx {:frame frame-a}
                                 {:machine-id :rf2-vxgfnd153/child})))
        (catch Throwable _ (reset! threw? true)))
      {:b-birth      @b-birth
       :b-after      (rf.machines.test-support/runtime-db frame-a)
       :spawn-order  (rf.machines.spawn-order/frame-order frame-a)
       :spawn-traces @spawn-traces
       :fail-traces  @fail-traces
       :dispatches   @dispatches
       :threw?       @threw?}
      (finally
        (rf/unregister-listener! :trace ::spawn-fence)
        (rf.late-bind/set-fn! :schemas/validate-with-registered-fn orig-validate)
        (rf.late-bind/set-fn! :router/dispatch! orig-dispatch!)))))

(deftest schema-callback-destroy-leaves-successor-untouched
  (testing "a schema validator that destroys A and publishes same-id B, then
            reports a violation or throws: the throw is swallowed and no
            A-derived install, spawn-order entry, spawned trace, failure
            diagnostic or :start dispatch reaches B"
    (doseq [[frame-a on-validate] [[:rf2-vxgfnd153/failing-frame (fn [_] false)]
                                   [:rf2-vxgfnd153/throwing-frame
                                    (fn [_] (throw (ex-info "schema validator lost A" {})))]]]
      (let [{:keys [b-birth] :as r} (run-destroyer-spawn frame-a on-validate)]
        ;; b-birth stays ::unset unless the validator ran, so this equality
        ;; also proves the fence was exercised.
        (is (= {:b-after b-birth :threw? false :spawn-order []
                :spawn-traces [] :fail-traces [] :dispatches []}
               (dissoc r :b-birth))
            (str frame-a))))))

;; ---- update-snapshot escape-hatch fence -----------------------------------

(deftest update-snapshot-validator-fences-write-to-successor
  (testing "an escape-hatch schema validator that destroys A and publishes
            same-id B (with a sentinel snapshot): A's patch never merges onto B"
    (rf/reg-machine :rf2-vxgfnd153/sing
      {:initial :running
       :data    {:v :a-init}
       :schemas {:data ::sing-schema}
       :states  {:running {:on {:go :done}}
                 :done    {:final? true}}})
    (let [frame-a :rf2-vxgfnd153/us-frame
          fired?  (atom false)
          orig    (rf.late-bind/get-fn :schemas/validate-with-registered-fn)]
      (rf/make-frame {:id frame-a})
      (let [token-a (rf.frame/frame-incarnation-token frame-a)]
        (rf.frame/swap-runtime-db! frame-a
          (fn [rt] (assoc-in rt [:rf.runtime/machines :snapshots :rf2-vxgfnd153/sing]
                             {:state :running :data {:v :a-init}})))
        (try
          (rf.late-bind/set-fn! :schemas/validate-with-registered-fn
            (fn [schema _data]
              (when (and (= schema ::sing-schema)
                         (compare-and-set! fired? false true))
                (rf.frame/destroy-frame! frame-a)
                (rf/make-frame {:id frame-a})
                (rf.frame/swap-runtime-db! frame-a
                  (fn [rt] (assoc-in rt [:rf.runtime/machines :snapshots :rf2-vxgfnd153/sing]
                                     {:state :running :data {:v :b-sentinel}}))))
              true))
          (rf.frame/call-with-event-owner-token frame-a token-a
            (fn [] (rf.machines.lifecycle-fx.update-snapshot/update-snapshot-fx
                     {:frame frame-a}
                     {:rf/machine-id :rf2-vxgfnd153/sing
                      :rf/patch      {:data {:v :patched}}})))
          ;; Only the destroyer writes the sentinel, so this also proves it ran.
          (is (= {:v :b-sentinel}
                 (:data (rf.machines.test-support/snapshot frame-a :rf2-vxgfnd153/sing))))
          (finally
            (rf.late-bind/set-fn! :schemas/validate-with-registered-fn orig)))))))

(deftest update-snapshot-db-trace-listener-fences-write-to-successor
  (testing "a `:db` key in the patch fires the :rf.error/machine-action-wrote-db
            trace BEFORE the validator runs; a :trace listener on it that
            destroys A and publishes same-id B leaves B's snapshot and commit
            epoch untouched. Once A is lost the would-reject validator is never
            consulted, so only the post-trace fence blocks the write."
    (rf/reg-machine :rf2-vxgfnd22/sing
      {:initial :running
       :data    {:v :a-init}
       :schemas {:data ::db-trace-schema}
       :states  {:running {:on {:go :done}}
                 :done    {:final? true}}})
    (let [frame-a :rf2-vxgfnd22/dbtrace-frame
          fired?  (atom false)
          b-epoch (atom ::unset)
          orig    (rf.late-bind/get-fn :schemas/validate-with-registered-fn)]
      (rf/make-frame {:id frame-a})
      (let [token-a (rf.frame/frame-incarnation-token frame-a)]
        (rf.frame/swap-runtime-db! frame-a
          (fn [rt] (assoc-in rt [:rf.runtime/machines :snapshots :rf2-vxgfnd22/sing]
                             {:state :running :data {:v :a-init}})))
        (rf/register-listener! :trace ::db-trace-destroyer
          (fn [ev]
            (when (and (= :rf.error/machine-action-wrote-db (:operation ev))
                       (compare-and-set! fired? false true))
              (rf.frame/destroy-frame! frame-a)
              (rf/make-frame {:id frame-a})
              (rf.frame/swap-runtime-db! frame-a
                (fn [rt] (assoc-in rt [:rf.runtime/machines :snapshots :rf2-vxgfnd22/sing]
                                   {:state :running :data {:v :b-sentinel}})))
              (reset! b-epoch (rf.frame/frame-commit-epoch frame-a)))))
        (try
          ;; A REJECTING validator for this machine's schema.
          (rf.late-bind/set-fn! :schemas/validate-with-registered-fn
            (fn [schema _data] (not= schema ::db-trace-schema)))
          (rf.frame/call-with-event-owner-token frame-a token-a
            (fn [] (rf.machines.lifecycle-fx.update-snapshot/update-snapshot-fx
                     {:frame frame-a}
                     {:rf/machine-id :rf2-vxgfnd22/sing
                      :rf/patch      {:db   {:naughty :write}
                                      :data {:v :patched}}})))
          (is (= [{:v :b-sentinel} @b-epoch]
                 [(:data (rf.machines.test-support/snapshot frame-a :rf2-vxgfnd22/sing))
                  (rf.frame/frame-commit-epoch frame-a)])
              "B's snapshot :data and commit epoch are untouched by A's patch")
          (finally
            (rf/unregister-listener! :trace ::db-trace-destroyer)
            (rf.late-bind/set-fn! :schemas/validate-with-registered-fn orig)))))))

;; ---- completion-output finalize diagnostic fence --------------------------

(deftest completion-output-validator-honors-exact-continuation
  (testing "a completion-output validator that destroys A, publishes same-id B
            and reports a violation emits no :where :machine-output diagnostic —
            it would otherwise be attributed to B"
    (let [id      :rf2-vxgfnd153/completion-frame
          traces  (atom [])
          orig    (rf.late-bind/get-fn :schemas/validate-with-registered-fn)]
      (rf/make-frame {:id id})
      (let [token-a (rf.frame/frame-incarnation-token id)]
        (rf/register-listener! :trace ::completion-fence
          (fn [ev] (when (and (= :rf.error/schema-validation-failure (:operation ev))
                              (= :machine-output (get-in ev [:tags :where])))
                     (swap! traces conj ev))))
        (try
          (rf.late-bind/set-fn! :schemas/validate-with-registered-fn
            (fn [schema _result]
              (when (= schema ::output-schema)
                (rf.frame/destroy-frame! id)
                (rf/make-frame {:id id}))
              false))
          (rf.frame/call-with-event-owner-token id token-a
            (fn []
              (rf.machines.data-validation/validate-completion-output!
                :rf2-vxgfnd153/completion-actor
                {:schemas {:output ::output-schema}}
                "bad-output")))
          (is (empty? @traces))
          (finally
            (rf/unregister-listener! :trace ::completion-fence)
            (rf.late-bind/set-fn! :schemas/validate-with-registered-fn orig)))))))
