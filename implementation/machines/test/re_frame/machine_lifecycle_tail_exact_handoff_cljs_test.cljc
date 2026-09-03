(ns re-frame.machine-lifecycle-tail-exact-handoff-cljs-test
  "rf2-rbxdxa — complete the STEPWISE exact-incarnation handoff across the machine
  lifecycle TAILS the #5913 (rf2-i4aj9c) fences ran ahead of. #5913 fenced the
  first exact write / broad tail guard of each pipeline, but several ADJACENT
  stages still shared ONE precheck even though the first stage can run user
  callbacks or exact-container callbacks. Each fixture SEEDS the actual successor
  registrar / spawn-order / timer-subscription / runtime state and drives the
  loss on the callback's own stack, so the mutation tooth bites when the recheck
  is missing (a false green under #5913's own fixtures, which never seeded the
  downstream successor-owned state).

  Runs on BOTH hosts (`clojure -M:test` + `npm run test:cljs`) — the `-cljs-test`
  ns suffix rides the consolidated node-test gate, and the JVM runner scans the
  same deftests.

  Five tails, each with a live-owner control (the fence is scoped to owner-loss
  only, never over-eager):

    1. ORDINARY DESTROY terminal fence — the `:rf.machine/system-id-released`
       trace + `rf.registrar/unregister!` no longer share one precheck: a listener
       that publishes same-id B and registers B's handler survives A's unregister.
    2. SPAWN classification-lowering seam — a container-write loss DURING
       `lower-at-spawn!` fences the bare-id `rf.machines.spawn-order/record!` (A's ghost child
       must not land in B's spawn-order).
    3. FINALIZE classification-drop seam — a loss DURING `drop-at-destroy!` fences
       the bare-id `rf.machines.spawn-order/forget!` + system-id release (B's spawn-order must
       survive; finalize returns inert).
    4. `destroy-single-actor!` success report — reports success ONLY when the
       teardown actually committed while authority stayed exact, so `:spawn-all`
       never emits a phantom `:rf.machine/destroyed`.
    5. NON-DESTROY timer-cancellation reasons — `:on-exit` (and every sibling
       reason) now self-fences the shared `rf.subs/unsubscribe` decrement, so a
       cancellation listener re-arming successor B's same query keeps B's fresh
       reaction."
  (:require #?(:clj  [clojure.test :refer [deftest is testing use-fixtures]]
               :cljs [cljs.test :refer-macros [deftest is testing use-fixtures]])
            [re-frame.core :as rf]
            [re-frame.frame :as rf.frame]
            [re-frame.late-bind :as rf.late-bind]
            [re-frame.machines :as rf.machines]
            [re-frame.machines.classification :as rf.machines.classification]
            [re-frame.machines.lifecycle-fx.destroy :as rf.machines.lifecycle-fx.destroy]
            [re-frame.machines.lifecycle-fx.finalize :as rf.machines.lifecycle-fx.finalize]
            [re-frame.machines.lifecycle-fx.spawn :as rf.machines.lifecycle-fx.spawn]
            [re-frame.machines.spawn-order :as rf.machines.spawn-order]
            [re-frame.machines.test-support :as rf.machines.test-support]
            [re-frame.machines.timer :as rf.machines.timer]
            [re-frame.registrar :as rf.registrar]
            [re-frame.subs :as rf.subs]
            [re-frame.substrate.plain-atom :as rf.substrate.plain-atom]
            [re-frame.trace.tooling :as rf.trace.tooling]))

;; Touch the artefact so the machines registration hooks are wired even when
;; this ns runs in isolation.
(def ^:private _artefact rf.machines/machine-transition)

(use-fixtures :each
  (rf.machines.test-support/make-reset-runtime-fixture {:adapter rf.substrate.plain-atom/adapter}))

;; ---- shared seed ----------------------------------------------------------

(def ^:private actor-type :rf2-rbxdxa/child)

(defn- snapshot-path [id] [:rf.runtime/machines :snapshots id])
(defn- system-id-path [sid] [:rf.runtime/machines :system-ids sid])

(defn- actor-spec
  "A spawned-actor spec whose `:running` `:exit` fires `on-exit` (used to drive
  the exit-cascade loss in the `destroy-single-actor!` fixture)."
  [on-exit]
  {:initial :running
   :states  {:running {:exit (fn [ctx] (when on-exit (on-exit)) (:data ctx))
                       :on   {:go :done}}
             :done    {:final? true}}})

(defn- seed-actor!
  "Seed a live spawned actor `actor-id` into `frame-id`: snapshot (with the
  spawned-actor `:rf/machine-type` discriminator), a `:system-id` reverse-index
  binding, and a spawn-order entry. Registers the TYPE so the spec (+ its `:exit`
  hook) resolves off the snapshot."
  [frame-id actor-id sid on-exit]
  (rf/reg-machine actor-type (actor-spec on-exit))
  (rf.frame/swap-runtime-db!
    frame-id
    (fn [rt] (-> rt
                 (assoc-in (snapshot-path actor-id)
                           {:state           :running
                            :data            {:rf/self-id actor-id}
                            :rf/machine-type actor-type})
                 (assoc-in (system-id-path sid) actor-id))))
  (rf.machines.spawn-order/record! frame-id actor-id))

;; ===========================================================================
;; (1) ORDINARY DESTROY — the terminal fence: `:rf.machine/system-id-released`
;;     trace + `rf.registrar/unregister!` must NOT share one precheck.
;; ===========================================================================

(deftest ordinary-destroy-preserves-successor-registrar-handoff
  (testing "a `:rf.machine/system-id-released` listener destroys A, publishes
            same-id B and registers B's fresh event handler at `actor-id` ON THAT
            TRACE's stack: ownership is rechecked AFTER the trace, so A's
            `rf.registrar/unregister!` cannot clear B's just-registered handler.
            Mutation tooth: the historically-grouped unregister erases B's
            handler + its provenance."
    (rf.machines.spawn-order/reset-all!)
    (let [frame-a  :rf2-rbxdxa/released-frame
          actor-id (keyword "rf2-rbxdxa" "released#1")
          sid      :rf2-rbxdxa/released-sid
          b-meta   {:fn (fn [db _] db) :rf/provenance :successor-B}
          fired?   (atom false)]
      (rf/make-frame {:id frame-a})
      (seed-actor! frame-a actor-id sid nil)          ;; spawned actor: NO registrar entry
      (rf.trace.tooling/register-listener!
        ::released-handoff
        (fn [ev]
          (when (and (= :rf.machine/system-id-released (:operation ev))
                     (compare-and-set! fired? false true))
            (rf.frame/destroy-frame! frame-a)            ;; destroy A
            (rf/make-frame {:id frame-a})             ;; same-id B
            ;; B registers its own event handler at `actor-id`.
            (rf.registrar/register! :event actor-id b-meta))))
      (try
        (let [token-a (rf.frame/frame-incarnation-token frame-a)]
          (rf.frame/call-with-event-owner-token frame-a token-a
            (fn [] (rf.machines.lifecycle-fx.destroy/destroy-machine-fx {:frame frame-a} actor-id))))
        (is (true? @fired?) "the system-id-released listener ran (fence exercised)")
        (is (= b-meta (rf.registrar/lookup :event actor-id))
            "successor B's event handler + provenance SURVIVED — A's unregister was fenced")
        (finally
          (rf.trace.tooling/unregister-listener! ::released-handoff)
          (rf.registrar/unregister! :event actor-id))))))

(deftest live-owner-destroy-unregisters-exactly-once
  (testing "control: an ordinary destroy whose tail fires no destroyer clears the
            actor's registrar entry EXACTLY once. The terminal fence is scoped to
            owner-loss only — a live destroy still unregisters."
    (rf.machines.spawn-order/reset-all!)
    (let [frame-a  :rf2-rbxdxa/live-unregister-frame
          actor-id (keyword "rf2-rbxdxa" "live-unregister#1")
          sid      :rf2-rbxdxa/live-unregister-sid]
      (rf/make-frame {:id frame-a})
      (seed-actor! frame-a actor-id sid nil)
      ;; Install a registrar entry so there is something to clear.
      (rf.registrar/register! :event actor-id {:fn (fn [db _] db) :rf/provenance :A})
      (try
        (let [token-a (rf.frame/frame-incarnation-token frame-a)]
          (rf.frame/call-with-event-owner-token frame-a token-a
            (fn [] (rf.machines.lifecycle-fx.destroy/destroy-machine-fx {:frame frame-a} actor-id))))
        (is (nil? (rf.registrar/lookup :event actor-id))
            "the live destroy unregistered the actor's handler exactly once")
        (is (nil? (rf.machines.test-support/snapshot frame-a actor-id))
            "the live destroy dissoc'd the snapshot")
        (finally
          (rf.registrar/unregister! :event actor-id))))))

;; ===========================================================================
;; (2) SPAWN — the classification-lowering seam: a loss DURING `lower-at-spawn!`
;;     must fence the bare-id `rf.machines.spawn-order/record!`.
;; ===========================================================================

(deftest spawn-classification-lowering-loss-fences-spawn-order-record
  (testing "a container-write loss DURING `lower-at-spawn!` (destroy A + publish
            same-id B): the spawn rechecks `(continue?)` AFTER the classification
            lowering, so the bare-id `rf.machines.spawn-order/record!` — which resolves to the
            CURRENT incarnation B — never records A's ghost child into B's
            spawn-order channel. Mutation tooth: the unfenced record! puts A's
            ghost child in B's spawn-order."
    (rf.machines.spawn-order/reset-all!)
    (rf/reg-machine actor-type (actor-spec nil))
    (let [frame-a        :rf2-rbxdxa/spawn-class-frame
          fired?         (atom false)
          orig-dispatch! (rf.late-bind/get-fn :router/dispatch!)]
      (rf/make-frame {:id frame-a})
      (try
        ;; Keep the actor-bootstrap dispatch synchronous + inert so no async
        ;; drain races the assertions (mirrors the #5889 spawn-write fixtures).
        (rf.late-bind/set-fn! :router/dispatch! (fn [_ev _opts] nil))
        ;; `with-redefs` restores `lower-at-spawn!` automatically on body exit.
        ;; MULTI-arity stub matching the real `[3]`/`[4]` arities: spawn.cljc calls
        ;; the 4-arity, which CLJS compiles to a DIRECT `arity$4` invoke — a
        ;; single- or variadic-arity replacement would not expose `arity$4` and
        ;; the CLJS run would `TypeError` (rf2-rbxdxa CI-gap: only a multi-arity
        ;; CLJS fn emits the `cljs$core$IFn$_invoke$arity$N` methods).
        (with-redefs [rf.machines.classification/lower-at-spawn!
                      (fn ([_frame _actor _spec] nil)
                          ([_frame _actor _spec _token]
                           ;; The exact elision write's container watch: destroy A
                           ;; / publish same-id B ON the classification write's stack.
                           (when (compare-and-set! fired? false true)
                             (rf.frame/destroy-frame! frame-a)
                             (rf/make-frame {:id frame-a}))
                           nil))]
          (let [token-a (rf.frame/frame-incarnation-token frame-a)]
            (rf.frame/call-with-event-owner-token frame-a token-a
              (fn [] (rf.machines.lifecycle-fx.spawn/spawn-fx {:frame frame-a}
                                     {:machine-id actor-type
                                      :system-id  :rf2-rbxdxa/spawn-class-sid
                                      :start      [:go]})))))
        (is (true? @fired?) "the classification-lowering loss ran (fence exercised)")
        (is (empty? (rf.machines.spawn-order/frame-order frame-a))
            "successor B's spawn-order is empty — A's ghost child was NOT recorded after the loss")
        (finally
          (rf.late-bind/set-fn! :router/dispatch! orig-dispatch!))))))

(deftest spawn-classification-live-records-child-once
  (testing "control: a spawn whose classification lowering runs cleanly records
            the child in spawn-order exactly once. The recheck must not suppress
            the live spawn-order record."
    (rf.machines.spawn-order/reset-all!)
    (rf/reg-machine actor-type (actor-spec nil))
    (let [frame-a        :rf2-rbxdxa/spawn-class-live-frame
          orig-dispatch! (rf.late-bind/get-fn :router/dispatch!)]
      (rf/make-frame {:id frame-a})
      (try
        (rf.late-bind/set-fn! :router/dispatch! (fn [_ev _opts] nil))
        (let [token-a (rf.frame/frame-incarnation-token frame-a)]
          (rf.frame/call-with-event-owner-token frame-a token-a
            (fn [] (rf.machines.lifecycle-fx.spawn/spawn-fx {:frame frame-a}
                                   {:machine-id actor-type
                                    :system-id  :rf2-rbxdxa/spawn-class-live-sid
                                    :start      [:go]}))))
        (let [order (vec (rf.machines.spawn-order/frame-order frame-a))]
          (is (= 1 (count order))
              "the live spawn recorded exactly one spawn-order entry")
          (is (some? (rf.machines.test-support/snapshot frame-a (first order)))
              "the live spawn installed the recorded child's snapshot"))
        (finally
          (rf.late-bind/set-fn! :router/dispatch! orig-dispatch!))))))

;; ===========================================================================
;; (3) FINALIZE — the classification-drop seam: a loss DURING `drop-at-destroy!`
;;     must fence the bare-id `rf.machines.spawn-order/forget!` + system-id release.
;; ===========================================================================

(defn- finishing-machine [frame-id]
  {:initial   :done
   :rf/frame  frame-id
   :rf/cofx   {:rf/time-ms 0}
   :data      {:result 42}
   :states    {:done {:final? true :output-key :result}}})

(defn- finishing-snapshot [] {:state :done :data {:result 42}})

(defn- seed-finishing! [frame-id machine-id sid]
  (rf.frame/swap-runtime-db!
    frame-id
    (fn [rt] (-> rt
                 (assoc-in (snapshot-path machine-id) (finishing-snapshot))
                 (assoc-in (system-id-path sid) machine-id))))
  (rf.machines.spawn-order/record! frame-id machine-id))

(deftest finalize-classification-drop-loss-fences-spawn-order-forget
  (testing "a loss DURING the finalize `drop-at-destroy!` (destroy A + publish
            same-id B, re-seeding B's spawn-order entry): ownership is rechecked
            AFTER the classification drop, so the bare-id `rf.machines.spawn-order/forget!` +
            system-id release do NOT run against B, and finalize returns the inert
            outcome. Mutation tooth: the grouped precheck lets the forget erase B's
            freshly-recorded spawn-order entry."
    (rf.machines.spawn-order/reset-all!)
    (let [frame-a    :rf2-rbxdxa/finalize-class-frame
          machine-id :rf2-rbxdxa/finalize-class-machine
          sid        :rf2-rbxdxa/finalize-class-sid
          fired?     (atom false)]
      (rf/reg-machine machine-id (finishing-machine frame-a))
      (rf/make-frame {:id frame-a})
      (seed-finishing! frame-a machine-id sid)
      ;; `with-redefs` restores `drop-at-destroy!` automatically on body exit.
      ;; MULTI-arity stub matching the real `[3]`/`[4]` arities (see the spawn
      ;; fixture note): finalize.cljc calls the 4-arity, a direct `arity$4` invoke
      ;; under CLJS.
      (let [ret (with-redefs
                  [rf.machines.classification/drop-at-destroy!
                   (fn ([_frame _mid _machine] nil)
                       ([_frame _mid _machine _token]
                        ;; The exact elision drop's container watch: destroy A /
                        ;; publish same-id B, re-seeding B's spawn-order entry.
                        (when (compare-and-set! fired? false true)
                          (rf.frame/destroy-frame! frame-a)
                          (rf/make-frame {:id frame-a})
                          (rf.machines.spawn-order/record! frame-a machine-id))
                        nil))]
                  (let [token-a (rf.frame/frame-incarnation-token frame-a)]
                    (rf.frame/call-with-event-owner-token frame-a token-a
                      (fn []
                        (rf.machines.lifecycle-fx.finalize/finalize-machine
                          (finishing-machine frame-a)
                          machine-id frame-a (rf.machines.test-support/runtime-db frame-a)
                          (finishing-snapshot) [:some-completing-event] [])))))]
        (is (true? @fired?) "the classification-drop loss ran (fence exercised)")
        (is (= [machine-id] (vec (rf.machines.spawn-order/frame-order frame-a)))
            "successor B's spawn-order entry SURVIVED — A's forget was fenced after the drop")
        (is (= [] (:fx ret))
            "finalize returned the inert outcome — no A-derived fx published onto B")))))

(deftest finalize-classification-live-forgets-spawn-order
  (testing "control: a finalize whose classification drop runs cleanly forgets the
            actor from spawn-order and dissocs its snapshot in the returned
            runtime-db. The recheck must not suppress the live teardown."
    (rf.machines.spawn-order/reset-all!)
    (let [frame-a    :rf2-rbxdxa/finalize-class-live-frame
          machine-id :rf2-rbxdxa/finalize-class-live-machine
          sid        :rf2-rbxdxa/finalize-class-live-sid]
      (rf/reg-machine machine-id (finishing-machine frame-a))
      (rf/make-frame {:id frame-a})
      (seed-finishing! frame-a machine-id sid)
      (let [token-a (rf.frame/frame-incarnation-token frame-a)
            ret     (rf.frame/call-with-event-owner-token frame-a token-a
                      (fn []
                        (rf.machines.lifecycle-fx.finalize/finalize-machine
                          (finishing-machine frame-a)
                          machine-id frame-a (rf.machines.test-support/runtime-db frame-a)
                          (finishing-snapshot) [:some-completing-event] [])))]
        (is (empty? (rf.machines.spawn-order/frame-order frame-a))
            "the live finalize forgot the actor from spawn-order")
        (is (nil? (get-in (:rf.db/runtime ret) (snapshot-path machine-id)))
            "the live finalize dissoc'd the actor's snapshot in the returned runtime-db")
        (is (nil? (get-in (:rf.db/runtime ret) (system-id-path sid)))
            "the live finalize released the :system-id binding")))))

;; ===========================================================================
;; (4) `destroy-single-actor!` — success report only on a genuine teardown.
;; ===========================================================================

(deftest destroy-single-actor-reports-abort-on-owner-loss
  (testing "when the actor's `:exit` cascade (the FIRST teardown step) destroys A
            + publishes same-id B, `teardown-live-actor!` aborts and
            `destroy-single-actor!` reports FALSEY — so a `:spawn-all` caller emits
            NO phantom `:rf.machine/destroyed`. Mutation tooth: the unconditional
            `true` return emits a phantom destroyed for a child whose teardown
            never committed."
    (rf.machines.spawn-order/reset-all!)
    (let [frame-a  :rf2-rbxdxa/single-abort-frame
          actor-id (keyword "rf2-rbxdxa" "single-abort#1")
          sid      :rf2-rbxdxa/single-abort-sid
          fired?   (atom false)]
      (rf/make-frame {:id frame-a})
      (let [destroy+B! (fn []
                         (when (compare-and-set! fired? false true)
                           (rf.frame/destroy-frame! frame-a)
                           (rf/make-frame {:id frame-a})))]
        (seed-actor! frame-a actor-id sid destroy+B!)   ;; :exit fires destroy+B!
        (let [token-a (rf.frame/frame-incarnation-token frame-a)
              fence   {:owner-gone? (fn [] (not (rf.frame/event-continuation-live? frame-a token-a)))
                       :owner-token token-a}
              ret     (rf.frame/call-with-event-owner-token frame-a token-a
                        (fn [] (rf.machines.lifecycle-fx.destroy/destroy-single-actor! frame-a actor-id fence)))]
          (is (true? @fired?) "the actor's :exit cascade ran + lost the owner (fence exercised)")
          (is (not ret)
              "destroy-single-actor! reported falsey — teardown aborted on owner loss, no phantom destroyed"))))))

(deftest destroy-single-actor-reports-success-when-live
  (testing "control: `destroy-single-actor!` on a live actor whose teardown fires
            no destroyer reports TRUTHY (the teardown projection committed) and
            dissocs the snapshot — so a `:spawn-all` caller emits `destroyed`
            exactly once for a genuinely-torn-down child."
    (rf.machines.spawn-order/reset-all!)
    (let [frame-a  :rf2-rbxdxa/single-live-frame
          actor-id (keyword "rf2-rbxdxa" "single-live#1")
          sid      :rf2-rbxdxa/single-live-sid]
      (rf/make-frame {:id frame-a})
      (seed-actor! frame-a actor-id sid nil)
      (let [token-a (rf.frame/frame-incarnation-token frame-a)
            fence   {:owner-gone? (fn [] (not (rf.frame/event-continuation-live? frame-a token-a)))
                     :owner-token token-a}
            ret     (rf.frame/call-with-event-owner-token frame-a token-a
                      (fn [] (rf.machines.lifecycle-fx.destroy/destroy-single-actor! frame-a actor-id fence)))]
        (is (true? (boolean ret))
            "destroy-single-actor! reported truthy — the teardown committed while exact")
        (is (nil? (rf.machines.test-support/snapshot frame-a actor-id))
            "the live teardown dissoc'd the actor's snapshot")))))

;; ===========================================================================
;; (5) NON-DESTROY timer cancellation (`:on-exit`) — the shared subscription
;;     decrement must self-fence a same-id successor.
;; ===========================================================================

(defn- seed-sub-vec-timer!
  "Seed one armed subscription-vector `:after` timer entry keyed under
  `parent-id` / `[]` invoke with a vector `delay-key` (so
  `release-entry-resources!` reaches the shared `rf.subs/unsubscribe` decrement)."
  [frame-id parent-id delay-key reaction]
  (swap! rf.machines.timer/after-timers assoc-in
         [frame-id {:parent parent-id :spawn [] :delay delay-key}]
         {:handle          nil
          :reaction        reaction
          :sub-watcher-key ::a-watch
          :resolved-ms     5000
          :epoch           0
          :state           :running
          :region          nil
          :delay-source    :sub
          :token           ::a-token}))

(deftest on-exit-timer-cancel-does-not-decrement-successor
  (testing "an `:on-exit` timer cancellation (via `after-cancel-fx`) whose
            callback-bearing `:rf.machine.timer/cancelled` trace destroys A +
            publishes same-id B (re-arming the SAME query): the 3-arity
            `cancel-after-timer-entry!` self-fences, so A's release does NOT
            decrement the shared `(frame,query-v)` ref-count — B's fresh reaction
            survives. Mutation tooth: the historically-unfenced non-destroy reason
            decrements B's ref."
    (let [frame-a     :rf2-rbxdxa/subvec-exit-frame
          actor-id    (keyword "rf2-rbxdxa" "subvec-exit#1")
          delay-key   [:rbxdxa/dyn]
          reaction    (atom 5000)
          unsub-count (atom 0)
          fired?      (atom false)]
      (rf/make-frame {:id frame-a})
      (seed-sub-vec-timer! frame-a actor-id delay-key reaction)
      (with-redefs [rf.subs/unsubscribe (fn ([_] (swap! unsub-count inc) nil)
                                        ([_ _] (swap! unsub-count inc) nil))]
        (rf.trace.tooling/register-listener!
          ::subvec-exit-fence
          (fn [ev]
            (when (and (= :rf.machine.timer/cancelled (:operation ev))
                       (compare-and-set! fired? false true))
              (rf.frame/destroy-frame! frame-a)          ;; destroy A
              (rf/make-frame {:id frame-a}))))        ;; same-id B re-arms the query
        (try
          (rf.machines.timer/after-cancel-fx {:frame frame-a}
                                 {:rf/parent-id actor-id :rf/invoke-id []})
          (is (true? @fired?) "the :on-exit timer-cancelled listener ran (fence exercised)")
          (is (zero? @unsub-count)
              "A's release did NOT decrement the shared ref-count after the same-id successor swap")
          (finally
            (rf.trace.tooling/unregister-listener! ::subvec-exit-fence)
            (swap! rf.machines.timer/after-timers dissoc frame-a)))))))

(deftest on-exit-timer-cancel-live-decrements-once
  (testing "control: an `:on-exit` cancellation whose trace does NOT publish a
            successor decrements the shared subscription ref-count EXACTLY once —
            the self-fence must not suppress an ordinary release."
    (let [frame-a     :rf2-rbxdxa/subvec-exit-live-frame
          actor-id    (keyword "rf2-rbxdxa" "subvec-exit-live#1")
          delay-key   [:rbxdxa/dyn-live]
          reaction    (atom 5000)
          unsub-count (atom 0)]
      (rf/make-frame {:id frame-a})
      (seed-sub-vec-timer! frame-a actor-id delay-key reaction)
      (with-redefs [rf.subs/unsubscribe (fn ([_] (swap! unsub-count inc) nil)
                                        ([_ _] (swap! unsub-count inc) nil))]
        (try
          (rf.machines.timer/after-cancel-fx {:frame frame-a}
                                 {:rf/parent-id actor-id :rf/invoke-id []})
          (is (= 1 @unsub-count)
              "the ordinary :on-exit release decremented the subscription ref-count exactly once")
          (finally
            (swap! rf.machines.timer/after-timers dissoc frame-a)))))))
