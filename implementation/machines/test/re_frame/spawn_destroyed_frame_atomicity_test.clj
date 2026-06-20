(ns re-frame.spawn-destroyed-frame-atomicity-test
  "Destroyed-/unknown-frame spawn atomicity.

  `spawn-fx*` (the accepted-spawn body of `:rf.machine/spawn`) reads the
  frame's runtime-db once as `old-rt`; for a DESTROYED or never-created
  frame that read is nil, and the fallback id-allocator yields a nil
  `spawned-id` (the `:else` branch).

  The WHOLE accepted-spawn cascade — the `:rf.machine.spawn/spawned`
  trace, the snapshot / system-id / spawn-slot install, and the `:start`
  (or synthetic) dispatch — is gated on `(and (not rejected?) old-rt)`, so
  a dead-frame spawn is a clean no-op: no trace, no install, no dispatch,
  nil returned. This keeps the spawn atomic — there is never a phantom
  `spawned` trace or a `[nil <start>]` dispatch for an actor that was not
  installed."
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [re-frame.core :as rf]
            [re-frame.late-bind :as late-bind]
            [re-frame.machines :as machines]
            [re-frame.machines.lifecycle-fx.spawn :as spawn]
            [re-frame.machines.test-support :as mtest]
            [re-frame.substrate.plain-atom :as plain-atom]
            [re-frame.trace.tooling :as trace-tooling]))

;; touch the artefact so the machines registration hook is wired even when
;; this ns is run in isolation (`re-frame.machines` require has side effects).
(def ^:private _artefact machines/machine-transition)

(use-fixtures :each
  (mtest/make-reset-runtime-fixture {:adapter plain-atom/adapter}))

(deftest destroyed-frame-spawn-fires-no-trace-no-dispatch
  (testing "C3: spawning into a never-created / destroyed frame installs nothing,
            emits no :rf.machine.spawn/spawned trace, and dispatches nothing"
    (rf/reg-machine :rf2-g13nm2/ghost-child
      {:initial :running
       :data    {}
       :states  {:running {:on {:go :done}}
                 :done    {:final? true}}})
    (let [spawned-traces (atom [])
          dispatches     (atom [])
          ghost-frame    :rf2-g13nm2/never-created-frame
          orig-dispatch! (late-bind/get-fn :router/dispatch!)]
      (trace-tooling/register-listener!
        ::ghost-spawn
        (fn [ev]
          (when (= :rf.machine.spawn/spawned (:operation ev))
            (swap! spawned-traces conj ev))))
      ;; Wrap the dispatch hook so any dispatch the spawn fx attempts is
      ;; captured (and NOT actually routed — a [nil <start>] dispatch would
      ;; otherwise blow up downstream).
      (late-bind/set-fn! :router/dispatch!
                         (fn [ev opts] (swap! dispatches conj [ev opts]) nil))
      (try
        (let [ret (spawn/spawn-fx {:frame ghost-frame}
                                  {:machine-id :rf2-g13nm2/ghost-child
                                   :start      [:go]})]
          (is (nil? ret)
              "spawn-fx returns nil for a dead-frame spawn (no id allocated)")
          (is (empty? @spawned-traces)
              "no :rf.machine.spawn/spawned trace fired for the dead-frame spawn")
          (is (empty? @dispatches)
              "no :start dispatch fired for an actor that was never installed")
          (is (nil? (get-in (rf/runtime-db-value ghost-frame)
                            [:rf.runtime/machines :snapshots]))
              "no snapshot was installed into the dead frame"))
        (finally
          (trace-tooling/unregister-listener! ::ghost-spawn)
          (when orig-dispatch!
            (late-bind/set-fn! :router/dispatch! orig-dispatch!)))))))

(deftest live-frame-spawn-still-fires-trace-and-dispatch
  (testing "C3 control: a LIVE-frame spawn still emits the spawned trace,
            installs the snapshot, and dispatches :start (the fix is scoped to
            the dead-frame case only)"
    (rf/reg-machine :rf2-g13nm2/live-child
      {:initial :running
       :data    {}
       :states  {:running {:on {:go :done}}
                 :done    {:final? true}}})
    ;; Drive a live singleton through a state that spawns the child, so the
    ;; spawn fx runs against a REAL (live) frame's runtime-db.
    (rf/reg-machine :rf2-g13nm2/live-parent
      {:initial :idle
       :states  {:idle     {:on {:start :spawning}}
                 :spawning {:spawn {:machine-id :rf2-g13nm2/live-child}}}})
    (let [spawned-traces (atom [])]
      (trace-tooling/register-listener!
        ::live-spawn
        (fn [ev]
          (when (= :rf.machine.spawn/spawned (:operation ev))
            (swap! spawned-traces conj ev))))
      (try
        (rf/dispatch-sync [:rf2-g13nm2/live-parent [:start]])
        (let [spawned-id (get-in (rf/runtime-db-value :rf/default)
                                 [:rf.runtime/machines :spawned
                                  :rf2-g13nm2/live-parent [:spawning]])]
          (is (some? spawned-id)
              "the child was installed (live-frame spawn is unaffected by the fix)")
          (is (seq @spawned-traces)
              "the :rf.machine.spawn/spawned trace fired for the live-frame spawn"))
        (finally
          (trace-tooling/unregister-listener! ::live-spawn))))))
