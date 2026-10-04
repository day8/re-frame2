(ns re-frame.machines-spawn-cljs-test
  "CLJS-side coverage for declarative `:spawn` (child-machine spawning)
  under the Reagent reactive substrate.

  Mirrors the conformance fixture
  ../spec/conformance/fixtures/spawn-on-entry-destroy-on-exit.edn —
  entering a state with `:spawn` emits a `:rf.machine/spawn` fx (observable
  as `:rf.machine.spawn/spawned` trace); exiting emits `:rf.machine/destroy`
  (observable as `:rf.machine/destroyed` trace).

  Concerns covered:
    - A spawn emits both the fx-substrate and the registrar-substrate
      spawned traces.
    - State-level `:after` on a `:spawn`-bearing state:
      synthetic timer-elapsed cancels the child via the standard exit
      cascade and transitions the parent.

  The rejected `:timeout-ms` spawn slot is timeout-cljs-test's, on both
  hosts.

  The `:spawn :data` fn form is pinned on both hosts by
  `spawn_ordering_ep0029_cljs_test`.

  The deterministic child id is read back from the
  runtime spawn-registry slot at
  `[:rf.runtime/machines :spawned <parent> <invoke-id>]`."
  (:require [cljs.test :refer-macros [deftest is testing use-fixtures]]
            [re-frame.core :as rf]
            ;; listener / buffer surface lives in re-frame.trace.tooling.
            [re-frame.trace.tooling :as rf.trace.tooling]
            [re-frame.adapter.reagent :as rf.adapter.reagent]
            [re-frame.machines.test-support :as rf.machines.test-support]))

(use-fixtures :each
  (rf.machines.test-support/make-reset-runtime-fixture
    {:adapter rf.adapter.reagent/adapter}))

;; snapshot lookup via the shared machines test-support — no hardcoded
;; `[:rf.runtime/machines :snapshots …]` path. The per-section
;; trace captures below keep their raw trace.tooling register/unregister: they
;; reset and re-scope the capture mid-test (interleaved with assertions), which
;; the scope-macro / :each-fixture forms cannot express.
(def ^:private snapshot rf.machines.test-support/snapshot)

;; ---- two-axis spawn observation -----------------------------------------
;; A spawn emits TWO traces: the fx-substrate observation
;; `:rf.machine.spawn/spawned` (the spawn fx ran) AND the registrar-substrate
;; observation `:rf.machine.lifecycle/spawned` (the actor's snapshot landed in
;; runtime-db). The latter is the symmetric `spawned` half of the
;; created/spawned/destroyed lifecycle triple and carries `:spawned-id` +
;; `:state` (initial state) so observers + the Xray managed-fx INVOKE adapter
;; can render the actor without re-reading runtime-db. Per 009 §Two-axis machine
;; observation.

(deftest machine-spawn-two-axis-cljs
  (testing "a spawn emits BOTH :rf.machine.spawn/spawned (fx) and :rf.machine.lifecycle/spawned (registrar)"
    (let [child  {:initial :running :data {} :states {:running {}}}
          parent {:initial :idle
                  :data    {}
                  :states  {:idle    {:on {:go :working}}
                            :working {:spawn {:machine-id :qpuk4/worker}}}}
          traces (atom [])]
      (rf/reg-machine :qpuk4/worker child)
      (rf/reg-machine :qpuk4/sup    parent)
      (rf.trace.tooling/register-listener! ::two-axis (fn [ev] (swap! traces conj ev)))
      (rf/dispatch-sync [:qpuk4/sup [:go]])
      (rf.trace.tooling/unregister-listener! ::two-axis)
      ;; fx-substrate axis
      (is (some (fn [ev]
                  (and (= :rf.machine.spawn/spawned (:operation ev))
                       (= :qpuk4/worker (:machine-id (:tags ev)))))
                @traces)
          "fx-substrate axis: expected :rf.machine.spawn/spawned")
      ;; registrar-substrate axis — the round-trip the Xray consumer keys on
      (is (some (fn [ev]
                  (and (= :rf.machine.lifecycle/spawned (:operation ev))
                       (= :qpuk4/worker   (:machine-id (:tags ev)))
                       (= :qpuk4/worker#1 (:spawned-id (:tags ev)))
                       (= :running        (:state (:tags ev)))))
                @traces)
          "registrar-substrate axis: expected :rf.machine.lifecycle/spawned carrying :spawned-id + initial :state"))))

;; ---- state-level :after on :spawn-bearing state -------------------------
;; Per Spec 005 §Wall-clock timeouts on :spawn — use parent state's :after.
;; Wall-clock guards on a spawn are expressed via :after on the
;; :spawn-bearing state itself (:spawn / :spawn-all carry no :timeout-ms
;; slot). When :after fires, the standard exit cascade tears down the
;; spawned child via :rf.machine/destroy.

(deftest machine-after-on-spawn-bearing-state-cljs
  (testing ":after on a :spawn-bearing state — synthetic timer-elapsed cancels child + transitions"
    (let [child  {:initial :running
                  :states  {:running {:on {:never-fires :done}}
                            :done    {}}}
          parent {:initial :idle
                  :data    {}
                  :states
                  {:idle {:on {:go :authenticating}}
                   :authenticating
                   {:spawn {:machine-id :child/auth-after}
                    :after  {30000 :timed-out}
                    :on    {:auth/succeeded :authenticated}}
                   :authenticated {}
                   :timed-out     {}}}
          traces (atom [])]
      (rf/reg-machine :child/auth-after child)
      (rf/reg-machine :sup/auth-after  parent)
      (rf.trace.tooling/register-listener! ::ato (fn [ev] (swap! traces conj ev)))
      (rf/dispatch-sync [:sup/auth-after [:go]])
      (is (= :authenticating (:state (snapshot :sup/auth-after)))
          "parent transitioned :idle → :authenticating")
      (is (some (fn [ev]
                  (and (= :rf.machine.timer/scheduled (:operation ev))
                       (= 30000   (:delay (:tags ev)))
                       (= :literal (:delay-source (:tags ev)))))
                @traces)
          "expected :rf.machine.timer/scheduled with :delay-source :literal")
      (let [child-id (get-in (:rf.db/runtime (rf/frame-state-value :rf/default))
                             [:rf.runtime/machines :spawned :sup/auth-after [:authenticating]])
            epoch    (get-in (snapshot :sup/auth-after) [:data :rf/after-epoch [:authenticating]])]
        (is (some? child-id) "spawn slot bound to the spawned child id")
        (reset! traces [])
        ;; Synthetically dispatch the :after-elapsed timer event with the
        ;; current epoch + decl-path — mirrors the wall-clock setTimeout firing.
        (rf/dispatch-sync [:sup/auth-after [:rf.machine.timer/after-elapsed 30000 epoch [:authenticating]]])
        (is (= :timed-out (:state (snapshot :sup/auth-after)))
            "parent transitioned :authenticating → :timed-out via :after firing")
        (is (nil? (get-in (:rf.db/runtime (rf/frame-state-value :rf/default))
                          [:rf.runtime/machines :snapshots child-id]))
            "child machine snapshot torn down by the standard exit cascade"))
      (rf.trace.tooling/unregister-listener! ::ato))))
