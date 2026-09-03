(ns re-frame.machine-lifecycle-tail-incarnation-fence-test
  "rf2-4ipqe4 — fence the TIMER, REGISTRAR, and SPAWN-WRITE callbacks in the
  machine lifecycle tails after incarnation loss. The sibling of #5873/rf2-hloj0g
  (which fenced the destroyed / spawned-trace / HTTP-abort tails): #5873 grouped
  three still-callback-bearing operations under EARLIER ownership checks, so a
  lost-owner machine could still write / cancel / register against a same-id
  successor B.

  Three seams, one per callback class:

    - TIMER: `rf.machines.timer/cancel-actor-timers!` emits a SYNCHRONOUS
      `:rf.machine.timer/cancelled` per cancellation. A listener replaces A with
      same-id B after the FIRST cancellation; the stale snapshot loop then reads
      B's LIVE entry under a later key and cancels B's timer, and the finalize
      tail continues into classification / spawn-order / system-id-release
      against B. FIX: thread the monotonic `owner-gone?` gate into the
      cancellation loop (short-circuit after the losing cancellation) AND recheck
      it at the finalize call-site before the classification / spawn-order /
      system-id-release work.

    - REGISTRAR: `rf.registrar/unregister!` emits a SYNCHRONOUS
      `:rf.registry/handler-cleared`. A listener replaces A with B; the stale
      A-derived `:on-error` (spawn-error) dispatch then routes into B. FIX:
      recheck `owner-gone?` immediately after the unregister, before the
      `:on-error` dispatch.

    - SPAWN-WRITE: `install-spawn!` used a non-exact `rf.frame/swap-runtime-db!`. A
      container watch replaces A DURING the physical write; the bare write bumps
      B's id-keyed commit epoch and emits `:rf.machine/system-id-bound` before
      the later owner check. FIX: route the install through the exact owner token
      + `rf.frame/swap-runtime-db-exact!`, which binds the write to A's own
      container and returns nil on mid-write loss (no epoch bump, no
      system-id-bound, no lifecycle tail).

  These fixtures drive the tails DIRECTLY under a bound event owner (A's
  dequeue-time token) with a destroyer that publishes same-id B on the callback's
  own stack, and assert B stays byte-identical — the same deterministic,
  single-threaded shape the #5873 fence fixtures use (the destroyer runs INSIDE
  the callback / watch, so no latch coordination is needed). Each seam pairs its
  loss fixture with a LIVE-OWNER control that must still tear down / dispatch /
  install exactly once — the mutation tooth against an over-eager fence."
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [re-frame.core :as rf]
            [re-frame.frame :as rf.frame]
            [re-frame.late-bind :as rf.late-bind]
            [re-frame.machines :as rf.machines]
            [re-frame.machines.lifecycle-fx.finalize :as rf.machines.lifecycle-fx.finalize]
            [re-frame.machines.lifecycle-fx.spawn :as rf.machines.lifecycle-fx.spawn]
            [re-frame.machines.spawn-order :as rf.machines.spawn-order]
            [re-frame.machines.test-support :as rf.machines.test-support]
            [re-frame.machines.timer :as rf.machines.timer]
            [re-frame.registrar :as rf.registrar]
            [re-frame.substrate.adapter :as rf.substrate.adapter]
            [re-frame.substrate.plain-atom :as rf.substrate.plain-atom]
            [re-frame.trace :as rf.trace]
            [re-frame.trace.tooling :as rf.trace.tooling]))

;; Touch the artefact so the machines registration hooks are wired even when
;; this ns runs in isolation.
(def ^:private _artefact rf.machines/machine-transition)

(use-fixtures :each
  (rf.machines.test-support/make-reset-runtime-fixture {:adapter rf.substrate.plain-atom/adapter}))

;; ===========================================================================
;; TIMER seam — cancel-actor-timers! per-cancellation fence + finalize recheck
;; ===========================================================================

(defn- finishing-machine
  "A runtime-stamped finished singleton spec resting on a `:final?` leaf."
  [frame-id]
  {:initial  :done
   :rf/frame frame-id
   :rf/cofx  {:rf/time-ms 0}
   :data     {:result 42}
   :states   {:done {:final? true :output-key :result}}})

(defn- finishing-snapshot []
  {:state :done :data {:result 42}})

(defn- seed-finishing-runtime-db! [frame-id machine-id]
  (rf.frame/swap-runtime-db!
    frame-id
    (fn [rt] (assoc-in rt [:rf.runtime/machines :snapshots machine-id]
                       (finishing-snapshot)))))

(defn- timer-key [parent-id delay-key]
  {:parent parent-id :spawn [] :delay delay-key})

(defn- seed-timer!
  "Install a minimal armed-timer table entry under `[frame-id (timer-key
  parent-id delay-key)]` carrying a unique `token` and a nil host handle
  (release is then a no-op). Enough for `cancel-actor-timers!` to claim + emit
  a `:rf.machine.timer/cancelled` trace."
  [frame-id parent-id delay-key token]
  (swap! rf.machines.timer/after-timers assoc-in
         [frame-id (timer-key parent-id delay-key)]
         {:handle          nil
          :reaction        nil
          :sub-watcher-key nil
          :resolved-ms     delay-key
          :epoch           0
          :state           :done
          :region          nil
          :delay-source    :literal
          :token           token}))

(deftest timer-cancel-loss-fences-loop-and-tail
  (testing "A has two snapshotted `:after` timers. The FIRST cancellation's
            `:rf.machine.timer/cancelled` listener destroys A + publishes same-id
            B + arms B's timer under the SECOND key and records B's own
            spawn-order. The cancellation loop short-circuits (B's timer
            survives), and the finalize call-site rechecks ownership so the
            A-derived spawn-order forget never lands on B. Mutation tooth:
            without the fences the loop cancels B's timer under the second key
            and `rf.machines.spawn-order/forget!` drops B's entry."
    (rf.machines.spawn-order/reset-all!)
    (reset! rf.machines.timer/after-timers {})
    (let [frame-a    :rf2-4ipqe4/timer-loss-frame
          machine-id :rf2-4ipqe4/timer-loss
          k1         (timer-key machine-id 100)
          k2         (timer-key machine-id 200)]
      (rf/reg-machine machine-id (finishing-machine frame-a))
      (rf/make-frame {:id frame-a})
      (seed-finishing-runtime-db! frame-a machine-id)
      ;; A's two snapshotted timers (insertion order k1 then k2 → the loop's
      ;; PersistentArrayMap seq processes k1 first).
      (seed-timer! frame-a machine-id 100 ::a-tok-1)
      (seed-timer! frame-a machine-id 200 ::a-tok-2)
      (let [token-a       (rf.frame/frame-incarnation-token frame-a)
            fired?        (atom false)
            first-delay   (atom nil)
            cancelled     (atom [])
            b-runtime     (atom nil)]
        (rf.trace.tooling/register-listener!
          ::timer-fence
          (fn [ev]
            (when (= :rf.machine.timer/cancelled (:operation ev))
              (swap! cancelled conj ev)
              (when (compare-and-set! fired? false true)
                (reset! first-delay (get-in ev [:tags :delay]))
                (rf.frame/destroy-frame! frame-a)          ;; destroy A
                (rf/make-frame {:id frame-a})            ;; publish same-id B
                (reset! b-runtime (rf.machines.test-support/runtime-db frame-a))
                ;; B re-arms a timer under the SECOND snapshot key + records its
                ;; own spawn-order entry — the A-derived tail must touch neither.
                (seed-timer! frame-a machine-id 200 ::b-tok-2)
                (rf.machines.spawn-order/record! frame-a machine-id)))))
        (try
          (let [ret (rf.frame/call-with-event-owner-token frame-a token-a
                      (fn []
                        (rf.machines.lifecycle-fx.finalize/finalize-machine
                          (finishing-machine frame-a)
                          machine-id frame-a (rf.machines.test-support/runtime-db frame-a)
                          (finishing-snapshot) [:some-completing-event] [])))]
            (is (true? @fired?) "the timer-cancelled listener ran (fence exercised)")
            (is (= 100 @first-delay)
                "the FIRST cancellation was A's k1 (delay 100) — insertion order held")
            ;; --- loop fence: B's k2 timer survives ---
            (is (= ::b-tok-2 (get-in @rf.machines.timer/after-timers [frame-a k2 :token]))
                "B's timer under the second key survives — the cancellation loop
                 short-circuited after losing A and never cancelled B's timer")
            (is (= 1 (count (filter #(= :on-destroy (get-in % [:tags :reason])) @cancelled)))
                "exactly ONE :reason :on-destroy cancellation fired (A's k1); the
                 loop short-circuited before cancelling B's k2 timer. (A's OTHER
                 timer is cancelled by destroy-frame!'s own timer-table clear with
                 :reason :on-frame-destroy — the loop itself never reached it.)")
            (is (nil? (get-in @rf.machines.timer/after-timers [frame-a k1]))
                "A's own k1 timer was cancelled (already-delivered — it stands)")
            ;; --- call-site recheck: A-derived spawn-order forget never lands ---
            (is (= [machine-id] (rf.machines.spawn-order/frame-order frame-a))
                "B's spawn-order entry survives — the finalize call-site rechecked
                 ownership before the classification / spawn-order / system-id
                 tail, so `rf.machines.spawn-order/forget!` never ran against B")
            ;; --- inert publication ---
            (is (= [] (:fx ret)) "finalize published no fx after the loss")
            (is (contains? ret :rf.db/runtime) "finalize returns a runtime-db effect"))
          (finally
            (rf.trace.tooling/unregister-listener! ::timer-fence)))))))

(deftest timer-cancel-live-owner-cancels-all
  (testing "control: a completion whose timer-cancelled callbacks do NOT destroy
            A cancels BOTH armed timers and continues the teardown normally — the
            fence is scoped to owner-loss only."
    (rf.machines.spawn-order/reset-all!)
    (reset! rf.machines.timer/after-timers {})
    (let [frame-a    :rf2-4ipqe4/timer-live-frame
          machine-id :rf2-4ipqe4/timer-live]
      (rf/reg-machine machine-id (finishing-machine frame-a))
      (rf/make-frame {:id frame-a})
      (seed-finishing-runtime-db! frame-a machine-id)
      (seed-timer! frame-a machine-id 100 ::live-tok-1)
      (seed-timer! frame-a machine-id 200 ::live-tok-2)
      (let [token-a   (rf.frame/frame-incarnation-token frame-a)
            cancelled (atom [])]
        (rf.trace.tooling/register-listener!
          ::timer-live
          (fn [ev] (when (= :rf.machine.timer/cancelled (:operation ev))
                     (swap! cancelled conj ev))))
        (try
          (let [ret (rf.frame/call-with-event-owner-token frame-a token-a
                      (fn []
                        (rf.machines.lifecycle-fx.finalize/finalize-machine
                          (finishing-machine frame-a)
                          machine-id frame-a (rf.machines.test-support/runtime-db frame-a)
                          (finishing-snapshot) [:some-completing-event] [])))]
            (is (= 2 (count @cancelled))
                "both armed timers were cancelled (:reason :on-destroy) under the live owner")
            (is (empty? (get @rf.machines.timer/after-timers frame-a))
                "the live-owner teardown released both host-clock entries")
            (is (nil? (get-in (:rf.db/runtime ret)
                              [:rf.runtime/machines :snapshots machine-id]))
                "the live-owner completion tore down the actor's snapshot in the returned runtime-db"))
          (finally
            (rf.trace.tooling/unregister-listener! ::timer-live)))))))

;; ===========================================================================
;; REGISTRAR seam — recheck after handler-cleared before the :on-error dispatch
;; ===========================================================================

(defn- erroring-child-machine
  "A finished child resting on an `:error? :final?` leaf, its `:data` carrying
  the spawn ownership stamps so finalize routes the failure to the parent's
  `:spawn :on-error`."
  [parent-id invoke-id]
  {:initial  :failed
   :rf/frame :ignored
   :rf/cofx  {:rf/time-ms 0}
   :data     {:err "boom" :rf/parent-id parent-id :rf/invoke-id invoke-id}
   :states   {:failed {:final? true :error? true :output-key :err}}})

(defn- erroring-child-snapshot [parent-id invoke-id]
  {:state :failed :data {:err "boom" :rf/parent-id parent-id :rf/invoke-id invoke-id}})

(defn- on-error-parent-machine []
  {:initial :waiting
   :states  {:waiting {:spawn {:machine-id :rf2-4ipqe4/some-child
                               :on-error   {:target :recover}}}
             :recover {}}})

(defn- run-erroring-finalize
  "Register the parent (declaring `:spawn :on-error` at `[:waiting]`) + child,
  seed the child's finishing snapshot, install `on-cleared` as a
  `:rf.registry/handler-cleared` listener, and drive `finalize-machine` for the
  child under A's bound event owner. Captures every `:router/dispatch!`."
  [frame-a parent-id child-id on-cleared]
  (rf.machines.spawn-order/reset-all!)
  (let [invoke-id [:waiting]]
    (rf/reg-machine parent-id (on-error-parent-machine))
    (rf/reg-machine child-id  (erroring-child-machine parent-id invoke-id))
    (rf/make-frame {:id frame-a})
    (rf.frame/swap-runtime-db!
      frame-a (fn [rt] (assoc-in rt [:rf.runtime/machines :snapshots child-id]
                                 (erroring-child-snapshot parent-id invoke-id))))
    (let [token-a        (rf.frame/frame-incarnation-token frame-a)
          dispatches     (atom [])
          orig-dispatch! (rf.late-bind/get-fn :router/dispatch!)]
      (rf.trace.tooling/register-listener!
        ::registrar-fence
        (fn [ev] (when (= :rf.registry/handler-cleared (:operation ev))
                   (on-cleared ev))))
      (try
        (rf.late-bind/set-fn! :router/dispatch!
                           (fn [ev opts] (swap! dispatches conj [ev opts]) nil))
        (let [ret (rf.frame/call-with-event-owner-token frame-a token-a
                    (fn []
                      (rf.machines.lifecycle-fx.finalize/finalize-machine
                        (erroring-child-machine parent-id invoke-id)
                        child-id frame-a (rf.machines.test-support/runtime-db frame-a)
                        (erroring-child-snapshot parent-id invoke-id)
                        [:some-completing-event] [])))]
          {:ret        ret
           :dispatches @dispatches})
        (finally
          (rf.trace.tooling/unregister-listener! ::registrar-fence)
          ;; Restore the prior hook (nil re-registers as absent, matching a
          ;; conformance / no-router baseline).
          (rf.late-bind/set-fn! :router/dispatch! orig-dispatch!))))))

(deftest registrar-clear-loss-fences-on-error-dispatch
  (testing "a `:rf.registry/handler-cleared` listener (fired by the child's
            `rf.registrar/unregister!`) destroys A + publishes same-id B: ownership
            is rechecked AFTER the unregister, so the stale A-derived spawn-error
            (:on-error) dispatch is fenced and finalize publishes no fx. Mutation
            tooth: without the post-unregister recheck the spawn-error dispatch
            routes `[:rf.machine.spawn/error …]` into B."
    (let [frame-a    :rf2-4ipqe4/registrar-loss-frame
          parent-id  :rf2-4ipqe4/registrar-loss-parent
          child-id   :rf2-4ipqe4/registrar-loss-child
          fired?     (atom false)]
      (let [{:keys [ret dispatches]}
            (run-erroring-finalize
              frame-a parent-id child-id
              (fn [_ev]
                (when (compare-and-set! fired? false true)
                  (rf.frame/destroy-frame! frame-a)     ;; destroy A
                  (rf/make-frame {:id frame-a}))))]   ;; publish same-id B
        (is (true? @fired?) "the handler-cleared listener ran (fence exercised)")
        (is (empty? (filter (fn [[ev _]]
                              (and (vector? ev)
                                   (= :rf.machine.spawn/error
                                      (first (second ev)))))
                            dispatches))
            "no spawn-error / :on-error dispatch routed into B after the loss")
        (is (= [] (:fx ret)) "finalize published no A-derived fx after the loss")
        (is (contains? ret :rf.db/runtime) "finalize returns a runtime-db effect")))))

(deftest registrar-clear-live-owner-dispatches-on-error-once
  (testing "control: when the handler-cleared callback does NOT destroy A, the
            child's error leaf routes the `:on-error` (spawn-error) dispatch into
            the parent EXACTLY once — the fence must not suppress the live path."
    (let [frame-a    :rf2-4ipqe4/registrar-live-frame
          parent-id  :rf2-4ipqe4/registrar-live-parent
          child-id   :rf2-4ipqe4/registrar-live-child]
      (let [{:keys [dispatches]}
            (run-erroring-finalize frame-a parent-id child-id (fn [_ev] nil))]
        (let [spawn-errors (filter (fn [[ev _]]
                                     (and (vector? ev)
                                          (= :rf.machine.spawn/error
                                             (first (second ev)))))
                                   dispatches)]
          (is (= 1 (count spawn-errors))
              "the live-owner error leaf dispatched the spawn-error into the parent exactly once")
          (let [[[ev opts] _] [(first spawn-errors)]]
            (is (= parent-id (first ev)) "the dispatch targeted the parent")
            (is (= [:rf.machine.spawn/error [:waiting] "boom"] (second ev))
                "the spawn-error carried the child's invoke-id + error payload")
            (is (= frame-a (:frame opts)) "the dispatch was stamped with the frame")))))))

;; ===========================================================================
;; SPAWN-WRITE seam — install through swap-runtime-db-exact! (container watch)
;; ===========================================================================

(defn- install-watching-adapter!
  "Install a plain-atom-backed adapter whose `replace-container!` runs `on-write`
  exactly once, the first time a container write happens while `armed?` holds.
  The physical write always lands FIRST so A's write that linearized before the
  loss stands in A's captured container."
  [armed? on-write]
  (let [base-replace (:replace-container! rf.substrate.plain-atom/adapter)]
    (rf.substrate.adapter/dispose-adapter!)
    (reset! rf.frame/frames {})
    (rf.substrate.adapter/install-adapter!
      (assoc rf.substrate.plain-atom/adapter
             :kind :custom
             :replace-container!
             (fn [container value]
               (base-replace container value)
               (when (compare-and-set! armed? true false)
                 (on-write)))))))

(defn- restore-plain-adapter! []
  (reset! rf.frame/frames {})
  (rf.substrate.adapter/dispose-adapter!)
  (rf.substrate.adapter/install-adapter! rf.substrate.plain-atom/adapter))

(def ^:private spawn-child-instance-id (keyword "rf2-4ipqe4" "spawn-write-child#1"))

(deftest spawn-install-write-watch-loss-fences-tail
  (testing "a container watch that destroys A + publishes same-id B DURING the
            spawn install write: the exact write binds to A's own container and
            reports mid-write loss, so no A-derived child snapshot lands on B, no
            id-keyed commit epoch is bumped for B, and no `:rf.machine/
            system-id-bound` / `:rf.machine.lifecycle/spawned` / spawn-order tail
            is attributed after loss. Mutation tooth: the bare `swap-runtime-db!`
            bumps B's commit epoch and fires system-id-bound for B."
    (rf.machines.spawn-order/reset-all!)
    (rf/reg-machine :rf2-4ipqe4/spawn-write-child
      {:initial :running
       :states  {:running {:on {:go :done}}
                 :done    {:final? true}}})
    (let [frame-a        :rf2-4ipqe4/spawn-write-frame
          armed?         (atom false)
          fired?         (atom false)
          traces         (atom [])
          b-birth        (atom nil)
          b-commit       (atom nil)
          orig-dispatch! (rf.late-bind/get-fn :router/dispatch!)]
      (install-watching-adapter!
        armed?
        (fn []
          (when (compare-and-set! fired? false true)
            (rf.frame/destroy-frame! frame-a)       ;; destroy A during the install write
            (rf/make-frame {:id frame-a})          ;; publish same-id B
            (reset! b-birth (rf.machines.test-support/runtime-db frame-a))
            (reset! b-commit (rf.frame/frame-commit-epoch frame-a)))))
      (try
        ;; DETERMINISM FENCE (rf2-kz0bmb) — sibling of the live-owner test's
        ;; guard: hold the actor-bootstrap `:start` dispatch on a SYNCHRONOUS
        ;; in-thread no-op so no JVM background-executor drain (`interop/
        ;; next-tick`) races the assertions. The loss path fences the `:start`
        ;; before it fires, so this is a pure guard here — but it keeps the
        ;; whole spawn-write seam single-threaded and symmetric.
        (rf.late-bind/set-fn! :router/dispatch! (fn [_ev _opts] nil))
        (rf/make-frame {:id frame-a})
        (rf.trace/register-listener!
          ::spawn-write-fence
          (fn [ev] (swap! traces conj ev)))
        (let [token-a (rf.frame/frame-incarnation-token frame-a)]
          (reset! armed? true)
          (rf.frame/call-with-event-owner-token frame-a token-a
            (fn [] (rf.machines.lifecycle-fx.spawn/spawn-fx {:frame frame-a}
                                   {:machine-id :rf2-4ipqe4/spawn-write-child
                                    :system-id  :rf2-4ipqe4/sys-1
                                    :start      [:go]})))
          (is (true? @fired?) "the install-write container watch ran (fence exercised)")
          (is (some? @b-birth) "the watch published a same-id B")
          (is (nil? (rf.machines.test-support/snapshot frame-a spawn-child-instance-id))
              "no A-derived child snapshot installed onto same-id B")
          (is (= @b-birth (rf.machines.test-support/runtime-db frame-a))
              "B's runtime-db is byte-identical to its birth value (no A-derived install)")
          (is (= @b-commit (rf.frame/frame-commit-epoch frame-a))
              "A's mid-write loss never bumped B's id-keyed commit epoch")
          (is (empty? (rf.machines.spawn-order/frame-order frame-a))
              "no A-derived spawn-order entry recorded against B")
          (is (empty? (filter #(= :rf.machine/system-id-bound (:operation %)) @traces))
              "no :rf.machine/system-id-bound trace attributed after the write loss")
          (is (empty? (filter #(= :rf.machine.lifecycle/spawned (:operation %)) @traces))
              "no :rf.machine.lifecycle/spawned tail attributed after the write loss"))
        (finally
          (rf.trace/unregister-listener! ::spawn-write-fence)
          (rf.late-bind/set-fn! :router/dispatch! orig-dispatch!)
          (restore-plain-adapter!))))))

(deftest spawn-install-write-live-owner-installs-once
  (testing "control: a non-destroying install-write watch keeps A live — the
            exact write commits, the child snapshot installs, and the
            system-id-bound / lifecycle-spawned / spawn-order tail all fire
            exactly once. A wrongly-fencing exact write would skip the install."
    (rf.machines.spawn-order/reset-all!)
    (rf/reg-machine :rf2-4ipqe4/spawn-write-live-child
      {:initial :running
       :states  {:running {:on {:go :done}}
                 :done    {:final? true}}})
    (let [frame-a        :rf2-4ipqe4/spawn-write-live-frame
          armed?         (atom false)
          watch-runs     (atom 0)
          traces         (atom [])
          child-id       (keyword "rf2-4ipqe4" "spawn-write-live-child#1")
          orig-dispatch! (rf.late-bind/get-fn :router/dispatch!)]
      (install-watching-adapter!
        armed?
        (fn [] (swap! watch-runs inc)))          ; observe only — A stays live
      (try
        ;; DETERMINISM FENCE (rf2-kz0bmb) — drop the child's actor-bootstrap
        ;; `:start` dispatch onto a SYNCHRONOUS in-thread no-op. The default
        ;; `:router/dispatch!` hook schedules the bootstrap drain on the JVM
        ;; background executor (`interop/next-tick`); that async drain runs the
        ;; freshly-installed child's `[:go]` transition to its `:done`
        ;; `:final?` leaf and REAPS the child's snapshot + spawn-order on a
        ;; SEPARATE thread — racing the post-install assertions below (and the
        ;; `restore-plain-adapter!` teardown). The install this test asserts on
        ;; completes BEFORE the `:start` dispatch, so suppressing the async
        ;; bootstrap leaves every assertion's subject unchanged while making the
        ;; fixture single-threaded and deterministic (was green in isolation /
        ;; 8/8 reruns, red under CI load).
        (rf.late-bind/set-fn! :router/dispatch! (fn [_ev _opts] nil))
        (rf/make-frame {:id frame-a})
        (rf.trace/register-listener!
          ::spawn-write-live
          (fn [ev] (swap! traces conj ev)))
        (let [token-a (rf.frame/frame-incarnation-token frame-a)]
          (reset! armed? true)
          (rf.frame/call-with-event-owner-token frame-a token-a
            (fn [] (rf.machines.lifecycle-fx.spawn/spawn-fx {:frame frame-a}
                                   {:machine-id :rf2-4ipqe4/spawn-write-live-child
                                    :system-id  :rf2-4ipqe4/sys-live
                                    :start      [:go]})))
          (is (pos? @watch-runs) "the install-write watch fired (the exact write hit the container)")
          (is (some? (rf.machines.test-support/snapshot frame-a child-id))
              "the live-owner install committed the child snapshot through the exact write")
          (is (= [child-id] (rf.machines.spawn-order/frame-order frame-a))
              "the live-owner install recorded the child in spawn-order")
          (is (= 1 (count (filter #(= :rf.machine/system-id-bound (:operation %)) @traces)))
              "the live-owner install emitted :rf.machine/system-id-bound exactly once")
          (is (= 1 (count (filter #(= :rf.machine.lifecycle/spawned (:operation %)) @traces)))
              "the live-owner install emitted :rf.machine.lifecycle/spawned exactly once"))
        (finally
          (rf.trace/unregister-listener! ::spawn-write-live)
          (rf.late-bind/set-fn! :router/dispatch! orig-dispatch!)
          (restore-plain-adapter!))))))
