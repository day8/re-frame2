(ns re-frame.machine-destroy-tail-incarnation-fence-test
  "The ORDINARY `:rf.machine/destroy` teardown tail is fenced to the exact
  incarnation that entered it (rf2-i4aj9c). It completes the family started
  by #5873/rf2-hloj0g (spawn / finalize tails) and #5889/rf2-4ipqe4 (timer /
  registrar / spawn-write tails): those two fenced the FINAL-STATE auto-destroy
  path (`finalize-machine`) and the spawn cascade, and this covers the three
  tails they ran ahead of:

    1. ORDINARY `:rf.machine/destroy` — the effect runs inside the destroying
       event's drain (an exact event-owner binding), but `teardown-live-actor!`
       ran an UNFENCED pipeline: the `:exit` cascade, the late-bound HTTP-abort
       hook, the `:rf.machine.timer/cancelled` traces, the bare classification
       drop, the bare durable teardown projection, the `:rf.machine/destroyed`
       trace, the spawn-order forget, the `:rf.machine/system-id-released` +
       `:rf.registry/handler-cleared` traces, and the resource-owner release. A
       callback at ANY of those boundaries could destroy A / publish same-id B,
       and the whole tail continued against B. FIX: capture A's continuation +
       raw token ONCE at the effect entry, recheck after every callback-bearing
       boundary, and route the durable writes through the exact owner token.

    2. CLASSIFICATION — `lower-at-spawn!` / `drop-at-destroy!` wrote the
       per-instance `:sensitive` / `:large` declarations through the BARE
       two-arity elision swap. A container watch that destroyed A mid-write
       re-rooted the claim onto same-id B's registry. FIX: thread A's
       `owner-token` through the EXACT elision write (the same exact-write the
       flow lifecycle ops issue).

    3. SUBSCRIPTION-VECTOR TIMER — `cancel-after-timer-entry!` emits the
       callback-bearing `:rf.machine.timer/cancelled` trace and THEN releases
       the timer entry, whose `subs/unsubscribe frame-id delay-key` decrements
       the subscription cache ref-count keyed by `(frame-id, query-v)`. A
       listener that re-armed the SAME query in same-id B would have A's release
       dispose B's fresh reaction. FIX: skip ONLY that shared decrement once A
       is lost (the entry's own host handle / watcher still release).

  These fixtures drive the `:rf.machine/destroy` EFFECT (`destroy-machine-fx`)
  DIRECTLY under a bound event owner (A's dequeue-time token) with a destroyer
  that publishes same-id B on the callback's / hook's own stack, and assert B
  stays byte-identical — the deterministic, single-threaded shape the #5873 /
  #5889 fence fixtures use. Each loss fixture pairs with a LIVE-OWNER control
  that must still tear down / release exactly once (the mutation tooth against an
  over-eager fence), plus an EVENTLESS frame-destroy control that retains full
  authority."
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [re-frame.core :as rf]
            [re-frame.frame :as frame]
            [re-frame.late-bind :as late-bind]
            [re-frame.machines :as machines]
            [re-frame.machines.classification :as classification]
            [re-frame.machines.lifecycle-fx.destroy :as destroy]
            [re-frame.machines.spawn-order :as spawn-order]
            [re-frame.machines.test-support :as mtest]
            [re-frame.machines.timer :as timer]
            [re-frame.subs :as subs]
            [re-frame.substrate.adapter :as adapter]
            [re-frame.substrate.plain-atom :as plain-atom]
            [re-frame.trace :as trace]
            [re-frame.trace.tooling :as trace-tooling]))

;; Touch the artefact so the machines registration hooks are wired even when
;; this ns runs in isolation.
(def ^:private _artefact machines/machine-transition)

(use-fixtures :each
  (mtest/make-reset-runtime-fixture {:adapter plain-atom/adapter}))

;; ---- shared seed ----------------------------------------------------------

(def ^:private actor-type :rf2-i4aj9c/child)
(def ^:private actor-id   (keyword "rf2-i4aj9c" "child#1"))
(def ^:private the-sid    :rf2-i4aj9c/child-sid)

(defn- snapshot-path [id] [:rf.runtime/machines :snapshots id])
(defn- system-id-path [sid] [:rf.runtime/machines :system-ids sid])
(defn- elision-slot [frame-id] (get-in (rf/frame-state-value frame-id) [:rf.db/runtime :rf.runtime/elision]))
(defn- runtime-db [frame-id] (:rf.db/runtime (rf/frame-state-value frame-id)))
(defn- snapshot [frame-id id] (get-in (runtime-db frame-id) (snapshot-path id)))

(defn- classified-spec
  "A classified spawned-actor spec: declares a `:sensitive` `:data` path so the
  per-instance classification lowering writes into the frame's elision registry,
  and an `:exit`-side-effect hook (fired by the destroy exit cascade) closing
  over `on-exit`."
  [on-exit]
  {:initial   :running
   :sensitive [[:data :token]]
   :states    {:running {:exit (fn [ctx] (when on-exit (on-exit)) (:data ctx))
                         :on   {:go :done}}
               :done    {:final? true}}})

(defn- seed-live-actor!
  "Seed a LIVE spawned actor `actor-id` into `frame-id`: snapshot (with the
  spawned-actor `:rf/machine-type` discriminator), a `:system-id` reverse-index
  binding, a spawn-order entry, and its per-instance classification lowered into
  the elision registry. Registers the TYPE so the spec (and its `:exit` hook)
  resolves off the snapshot."
  [frame-id on-exit]
  (rf/reg-machine actor-type (classified-spec on-exit))
  (frame/swap-runtime-db!
    frame-id
    (fn [rt] (-> rt
                 (assoc-in (snapshot-path actor-id)
                           {:state           :running
                            :data            {:rf/self-id actor-id :token "secret"}
                            :rf/machine-type actor-type})
                 (assoc-in (system-id-path the-sid) actor-id))))
  (spawn-order/record! frame-id actor-id)
  (classification/lower-at-spawn! frame-id actor-id (classified-spec on-exit)))

;; ---- ordinary-destroy tail loss fixtures ----------------------------------

(defn- run-destroy-tail
  "Seed a live actor in frame A, install a destroyer keyed on `trigger`, then
  drive `destroy/destroy-machine-fx` (the imperative keyword form) DIRECTLY under
  A's bound event owner. The destroyer — fired EXACTLY once on the trigger's
  callback / hook stack — destroys frame A and republishes same-id B (re-seeding
  B identically: snapshot, system-id, spawn-order, classification). Returns the
  observable post-destroy state of B.

  `trigger` ∈ {:exit :http-abort :timer-cancelled :destroyed :system-id-released}."
  [frame-a trigger]
  (spawn-order/reset-all!)
  (rf/make-frame {:id frame-a})
  (let [fired?     (atom false)
        b-birth    (atom nil)
        orig-abort (late-bind/get-fn :http/abort-on-actor-destroy)
        destroy+B! (fn []
                     (when (compare-and-set! fired? false true)
                       (frame/destroy-frame! frame-a)     ;; destroy A
                       (rf/make-frame {:id frame-a})        ;; same-id B
                       ;; Re-seed B identically (no :exit hook on B).
                       (frame/swap-runtime-db!
                         frame-a
                         (fn [rt] (-> rt
                                      (assoc-in (snapshot-path actor-id)
                                                {:state           :running
                                                 :data            {:rf/self-id actor-id :token "secret"}
                                                 :rf/machine-type actor-type})
                                      (assoc-in (system-id-path the-sid) actor-id))))
                       (spawn-order/record! frame-a actor-id)
                       (classification/lower-at-spawn! frame-a actor-id (classified-spec nil))
                       (reset! b-birth (runtime-db frame-a))))]
    (seed-live-actor! frame-a (when (= trigger :exit) destroy+B!))
    (when (= trigger :timer-cancelled)
      ;; Seed one armed `:after` timer entry so `cancel-actor-timers!` emits a
      ;; `:rf.machine.timer/cancelled` trace we can hook.
      (swap! timer/after-timers assoc-in
             [frame-a {:parent actor-id :spawn [] :delay 3600000}]
             {:handle nil :reaction nil :sub-watcher-key nil
              :resolved-ms 3600000 :epoch 0 :state :running
              :region nil :delay-source :literal :token ::a-timer}))
    (trace-tooling/register-listener!
      ::destroy-tail-fence
      (fn [ev]
        (case (:operation ev)
          :rf.machine.timer/cancelled   (when (= trigger :timer-cancelled) (destroy+B!))
          :rf.machine/destroyed         (when (= trigger :destroyed) (destroy+B!))
          :rf.machine/system-id-released (when (= trigger :system-id-released) (destroy+B!))
          nil)))
    (try
      (when (= trigger :http-abort)
        (late-bind/set-fn! :http/abort-on-actor-destroy
                           (fn [_actor-id] (destroy+B!) nil)))
      (let [token-a (frame/frame-incarnation-token frame-a)]
        (frame/call-with-event-owner-token frame-a token-a
          (fn [] (destroy/destroy-machine-fx {:frame frame-a} actor-id))))
      {:fired?      @fired?
       :b-birth     @b-birth
       :b-runtime   (runtime-db frame-a)
       :b-snapshot  (snapshot frame-a actor-id)
       :b-system-id (get-in (runtime-db frame-a) (system-id-path the-sid))
       :b-elision   (elision-slot frame-a)
       :spawn-order (vec (spawn-order/frame-order frame-a))}
      (finally
        (trace-tooling/unregister-listener! ::destroy-tail-fence)
        (late-bind/set-fn! :http/abort-on-actor-destroy orig-abort)
        (swap! timer/after-timers dissoc frame-a)))))

(defn- assert-b-inert [{:keys [b-birth b-runtime b-snapshot b-system-id b-elision spawn-order]}]
  (is (some? b-snapshot)
      "successor B's snapshot survived — the A-derived teardown projection did not dissoc it")
  (is (= actor-id b-system-id)
      "successor B's :system-id binding survived — no A-derived release landed on B")
  (is (= [actor-id] spawn-order)
      "successor B's spawn-order entry survived — A's forget was fenced")
  (is (and (some? b-elision) (seq b-elision))
      "successor B's classification claim survived — A's drop-at-destroy! was fenced")
  (is (= b-birth b-runtime)
      "successor B's runtime-db is byte-identical to its birth value (no A-derived tail landed)"))

(deftest exit-cascade-loss-fences-destroy-tail
  (testing "the actor's `:exit` action (run by the teardown exit cascade)
            destroys A + publishes same-id B: ownership is rechecked after the
            exit cascade, so the HTTP-abort / classification drop / timer cancel
            / teardown projection / destroyed trace / spawn-order forget /
            system-id-release / registrar unregister / resource-release tail is
            all fenced. Mutation tooth: the historically-unfenced tail runs
            against B."
    (let [result (run-destroy-tail :rf2-i4aj9c/exit-frame :exit)]
      (is (true? (:fired? result)) "the :exit action ran (fence exercised)")
      (assert-b-inert result))))

(deftest http-abort-loss-fences-destroy-tail
  (testing "the late-bound :http/abort-on-actor-destroy hook destroys A +
            publishes same-id B: ownership is rechecked after the abort hook, so
            the classification drop / timer cancel / teardown projection / rest of
            the tail is fenced."
    (let [result (run-destroy-tail :rf2-i4aj9c/abort-frame :http-abort)]
      (is (true? (:fired? result)) "the HTTP-abort hook ran (fence exercised)")
      (assert-b-inert result))))

(deftest timer-cancelled-loss-fences-destroy-tail
  (testing "a :rf.machine.timer/cancelled listener destroys A + publishes same-id
            B: `cancel-actor-timers!` short-circuits and the destroy tail's
            teardown projection / destroyed trace / spawn-order forget /
            system-id-release / registrar / resource-release are fenced."
    (let [result (run-destroy-tail :rf2-i4aj9c/timer-frame :timer-cancelled)]
      (is (true? (:fired? result)) "the timer-cancelled listener ran (fence exercised)")
      (assert-b-inert result))))

(deftest destroyed-trace-loss-fences-destroy-tail
  (testing "a :rf.machine/destroyed listener destroys A + publishes same-id B
            (the trace fires after the teardown projection committed against A):
            ownership is rechecked after it, so the spawn-order forget /
            system-id-release / registrar unregister / resource-release tail is
            fenced — B's re-seeded state survives."
    (let [result (run-destroy-tail :rf2-i4aj9c/destroyed-frame :destroyed)]
      (is (true? (:fired? result)) "the :rf.machine/destroyed listener ran (fence exercised)")
      (assert-b-inert result))))

(deftest system-id-released-loss-fences-destroy-tail
  (testing "a :rf.machine/system-id-released listener destroys A + publishes
            same-id B (late in the tail): ownership is rechecked after it, so the
            registrar unregister + resource-release tail is fenced."
    (let [result (run-destroy-tail :rf2-i4aj9c/released-frame :system-id-released)]
      (is (true? (:fired? result)) "the system-id-released listener ran (fence exercised)")
      (assert-b-inert result))))

;; ---- live-owner control (full teardown once) ------------------------------

(deftest live-owner-destroy-tears-down-once
  (testing "control: an ordinary `:rf.machine/destroy` whose tail fires no
            destroyer tears the actor down FULLY — snapshot dissoc'd, system-id
            released, spawn-order forgotten, classification dropped, destroyed
            trace fired exactly once. The fence is scoped to owner-loss only."
    (spawn-order/reset-all!)
    (let [frame-a   :rf2-i4aj9c/live-destroy-frame
          destroyed (atom [])]
      (rf/make-frame {:id frame-a})
      (seed-live-actor! frame-a nil)
      (trace-tooling/register-listener!
        ::live-destroy
        (fn [ev] (when (= :rf.machine/destroyed (:operation ev))
                   (swap! destroyed conj ev))))
      (try
        (let [token-a (frame/frame-incarnation-token frame-a)]
          (frame/call-with-event-owner-token frame-a token-a
            (fn [] (destroy/destroy-machine-fx {:frame frame-a} actor-id))))
        (is (nil? (snapshot frame-a actor-id))
            "the live destroy dissoc'd the actor's snapshot")
        (is (nil? (get-in (runtime-db frame-a) (system-id-path the-sid)))
            "the live destroy released the :system-id binding")
        (is (empty? (spawn-order/frame-order frame-a))
            "the live destroy forgot the actor from spawn-order")
        (is (empty? (or (elision-slot frame-a) {}))
            "the live destroy dropped the actor's classification claim")
        (is (= 1 (count @destroyed))
            "exactly one :rf.machine/destroyed trace fired")
        (finally
          (trace-tooling/unregister-listener! ::live-destroy))))))

;; ---- eventless frame-destroy control (retains authority) ------------------

(deftest eventless-frame-destroy-tears-down-fully
  (testing "control: `destroy-single-actor!` reached with NO event owner (the
            frame-destroy cascade) uses the inert eventless fence — it tears the
            actor down fully regardless of any ambient ownership. A wrongly-eager
            fence would strand the actor on frame teardown."
    (spawn-order/reset-all!)
    (let [frame-a :rf2-i4aj9c/eventless-frame]
      (rf/make-frame {:id frame-a})
      (seed-live-actor! frame-a nil)
      ;; No `call-with-event-owner-token` — genuinely eventless.
      (destroy/destroy-single-actor! frame-a actor-id)
      (is (nil? (snapshot frame-a actor-id))
          "the eventless teardown dissoc'd the snapshot")
      (is (nil? (get-in (runtime-db frame-a) (system-id-path the-sid)))
          "the eventless teardown released the :system-id binding")
      (is (empty? (spawn-order/frame-order frame-a))
          "the eventless teardown forgot the actor from spawn-order"))))

;; ===========================================================================
;; SUBSCRIPTION-VECTOR TIMER seam — release fence (subs/unsubscribe)
;; ===========================================================================

(defn- seed-sub-vec-timer!
  "Seed one armed subscription-vector `:after` timer entry for `parent-id` under
  `frame-id`, carrying a `:reaction` + `:sub-watcher-key` and a vector
  `delay-key` (so `release-entry-resources!` reaches the `subs/unsubscribe`
  branch)."
  [frame-id parent-id delay-key reaction]
  (swap! timer/after-timers assoc-in
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

(deftest sub-vec-timer-release-loss-does-not-decrement-b
  (testing "a subscription-vector `:after` timer is cancelled on actor destroy; a
            :rf.machine.timer/cancelled listener re-arms the SAME query in same-id
            B (bumping the shared (frame,query-v) ref-count), then A's release runs.
            The shared `subs/unsubscribe` decrement is SKIPPED once A is lost, so
            B's fresh reaction ref is not disposed. Mutation tooth: the unfenced
            release decrements B's ref."
    (let [frame-a     :rf2-i4aj9c/subvec-frame
          delay-key   [:i4aj9c/dyn]
          reaction    (atom 5000)
          unsub-count (atom 0)
          fired?      (atom false)]
      (rf/make-frame {:id frame-a})
      (seed-sub-vec-timer! frame-a actor-id delay-key reaction)
      (with-redefs [subs/unsubscribe (fn ([_] (swap! unsub-count inc) nil)
                                        ([_ _] (swap! unsub-count inc) nil))]
        (trace-tooling/register-listener!
          ::subvec-fence
          (fn [ev]
            (when (and (= :rf.machine.timer/cancelled (:operation ev))
                       (compare-and-set! fired? false true))
              (frame/destroy-frame! frame-a)   ;; destroy A
              (rf/make-frame {:id frame-a}))))   ;; same-id B re-arms the same query
        (try
          (let [token-a   (frame/frame-incarnation-token frame-a)
                owner-gone? (fn [] (not (frame/event-continuation-live? frame-a token-a)))]
            (frame/call-with-event-owner-token frame-a token-a
              (fn [] (timer/cancel-actor-timers! frame-a actor-id owner-gone?))))
          (is (true? @fired?) "the timer-cancelled listener ran (fence exercised)")
          (is (zero? @unsub-count)
              "A's release did NOT decrement the shared (frame,query-v) ref-count after the loss")
          (finally
            (trace-tooling/unregister-listener! ::subvec-fence)
            (swap! timer/after-timers dissoc frame-a)))))))

(deftest sub-vec-timer-release-live-owner-unsubscribes-once
  (testing "control: when the cancelled listener does NOT destroy A, the
            subscription-vector timer's release decrements the ref-count EXACTLY
            once — the fence must not suppress the live release."
    (let [frame-a     :rf2-i4aj9c/subvec-live-frame
          delay-key   [:i4aj9c/dyn-live]
          reaction    (atom 5000)
          unsub-count (atom 0)]
      (rf/make-frame {:id frame-a})
      (seed-sub-vec-timer! frame-a actor-id delay-key reaction)
      (with-redefs [subs/unsubscribe (fn ([_] (swap! unsub-count inc) nil)
                                        ([_ _] (swap! unsub-count inc) nil))]
        (try
          (let [token-a     (frame/frame-incarnation-token frame-a)
                owner-gone? (fn [] (not (frame/event-continuation-live? frame-a token-a)))]
            (frame/call-with-event-owner-token frame-a token-a
              (fn [] (timer/cancel-actor-timers! frame-a actor-id owner-gone?))))
          (is (= 1 @unsub-count)
              "the live-owner release decremented the subscription ref-count exactly once")
          (finally
            (swap! timer/after-timers dissoc frame-a)))))))

;; ===========================================================================
;; SPAWN-ALL seam — children iteration + final join-slot cleanup fence
;; ===========================================================================

(def ^:private sa-parent :rf2-i4aj9c/sa-parent)
(def ^:private sa-invoke [:sa])
(def ^:private sa-child-a (keyword "rf2-i4aj9c" "sa-a#1"))
(def ^:private sa-child-b (keyword "rf2-i4aj9c" "sa-b#1"))

(defn- child-snap [id]
  {:state :running :data {:rf/self-id id} :rf/machine-type actor-type})

(defn- seed-spawn-all! [frame-id]
  (rf/reg-machine actor-type (classified-spec nil))
  (frame/swap-runtime-db!
    frame-id
    (fn [rt] (-> rt
                 (assoc-in (snapshot-path sa-child-a) (child-snap sa-child-a))
                 (assoc-in (snapshot-path sa-child-b) (child-snap sa-child-b))
                 (assoc-in [:rf.runtime/machines :spawned sa-parent sa-invoke]
                           {:children   {:a sa-child-a :b sa-child-b}
                            :done       #{}
                            :failed     #{}
                            :resolved?  false
                            :spec       {}
                            :rf/attempt 1}))))
  (spawn-order/record! frame-id sa-child-a)
  (spawn-order/record! frame-id sa-child-b))

(deftest spawn-all-iteration-loss-fences-remaining-children-and-clear
  (testing "a `:spawn-all` teardown iterates its children, firing a
            callback-bearing `:rf.machine/destroyed` per child. A listener on the
            FIRST child's destroyed trace destroys A + republishes same-id B
            (re-seeding the whole join): the iteration `:while` short-circuits and
            the final join-slot clear is fenced, so B's re-seeded join slot +
            children survive byte-identical. Mutation tooth: the unfenced
            iteration tears down B's children and the unfenced clear vacates B's
            join slot."
    (spawn-order/reset-all!)
    (let [frame-a :rf2-i4aj9c/spawn-all-frame
          fired?  (atom false)
          b-birth (atom nil)]
      (rf/make-frame {:id frame-a})
      (seed-spawn-all! frame-a)
      (trace-tooling/register-listener!
        ::spawn-all-fence
        (fn [ev]
          (when (and (= :rf.machine/destroyed (:operation ev))
                     (compare-and-set! fired? false true))
            (frame/destroy-frame! frame-a)       ;; destroy A
            (rf/make-frame {:id frame-a})          ;; same-id B
            (seed-spawn-all! frame-a)              ;; re-seed the whole join
            (reset! b-birth (runtime-db frame-a)))))
      (try
        (let [token-a (frame/frame-incarnation-token frame-a)]
          (frame/call-with-event-owner-token frame-a token-a
            (fn [] (destroy/destroy-machine-fx
                     {:frame frame-a}
                     {:rf/spawn-all true :rf/parent-id sa-parent :rf/invoke-id sa-invoke}))))
        (is (true? @fired?) "the per-child :rf.machine/destroyed listener ran (fence exercised)")
        (is (some? @b-birth) "the listener published a same-id B")
        (is (some? (get-in (runtime-db frame-a) [:rf.runtime/machines :spawned sa-parent sa-invoke]))
            "B's re-seeded join slot survived — the A-derived final clear was fenced")
        (is (= @b-birth (runtime-db frame-a))
            "B's runtime-db is byte-identical to its birth (no A-derived iteration / clear landed)")
        (finally
          (trace-tooling/unregister-listener! ::spawn-all-fence))))))

(deftest spawn-all-live-owner-tears-down-all-children
  (testing "control: a `:spawn-all` teardown whose per-child destroyed traces fire
            no destroyer tears down EVERY child and clears the join slot. The
            fence must not suppress the live path."
    (spawn-order/reset-all!)
    (let [frame-a :rf2-i4aj9c/spawn-all-live-frame]
      (rf/make-frame {:id frame-a})
      (seed-spawn-all! frame-a)
      (let [token-a (frame/frame-incarnation-token frame-a)]
        (frame/call-with-event-owner-token frame-a token-a
          (fn [] (destroy/destroy-machine-fx
                   {:frame frame-a}
                   {:rf/spawn-all true :rf/parent-id sa-parent :rf/invoke-id sa-invoke}))))
      (is (nil? (snapshot frame-a sa-child-a)) "child A torn down")
      (is (nil? (snapshot frame-a sa-child-b)) "child B torn down")
      (is (nil? (get-in (runtime-db frame-a) [:rf.runtime/machines :spawned sa-parent sa-invoke]))
          "the join slot was cleared once every child settled"))))

;; ===========================================================================
;; CLASSIFICATION exact-write seam — mid-write container-watch loss (removal)
;; ===========================================================================

(defn- install-watching-adapter!
  "Install a plain-atom-backed adapter whose `replace-container!` runs `on-write`
  exactly once, the first time a container write happens while `armed?` holds.
  The physical write always lands FIRST so A's write that linearized before the
  loss stands in A's captured container. Mirrors the #5889 spawn-write seam."
  [armed? on-write]
  (let [base-replace (:replace-container! plain-atom/adapter)]
    (adapter/dispose-adapter!)
    (reset! frame/frames {})
    (adapter/install-adapter!
      (assoc plain-atom/adapter
             :kind :custom
             :replace-container!
             (fn [container value]
               (base-replace container value)
               (when (compare-and-set! armed? true false)
                 (on-write)))))))

(defn- restore-plain-adapter! []
  (reset! frame/frames {})
  (adapter/dispose-adapter!)
  (adapter/install-adapter! plain-atom/adapter))

(deftest classification-drop-write-watch-loss-fences-removal
  (testing "a container watch that destroys A + publishes same-id B DURING the
            classification-DROP elision write: the EXACT elision write binds to
            A's own container and reports mid-write loss, so A's drop cannot
            re-root the removal onto same-id B's registry (which re-lowered the
            SAME `{:source :machine :actor-id …}` owner) nor bump B's commit
            epoch. Mutation tooth: the bare `swap-elision-slot!` removes B's
            claim."
    (let [frame-a  :rf2-i4aj9c/class-drop-frame
          armed?   (atom false)
          fired?   (atom false)
          b-claim  (atom nil)
          b-commit (atom nil)]
      (install-watching-adapter!
        armed?
        (fn []
          (when (compare-and-set! fired? false true)
            (frame/destroy-frame! frame-a)         ;; destroy A during the drop write
            (rf/make-frame {:id frame-a})            ;; same-id B
            ;; B re-lowers the SAME owner's classification claim.
            (classification/lower-at-spawn! frame-a actor-id (classified-spec nil))
            (reset! b-claim (elision-slot frame-a))
            (reset! b-commit (frame/frame-commit-epoch frame-a)))))
      (try
        (rf/reg-machine actor-type (classified-spec nil))
        (rf/make-frame {:id frame-a})
        (let [token-a (frame/frame-incarnation-token frame-a)]
          (reset! armed? true)
          (frame/call-with-event-owner-token frame-a token-a
            (fn [] (classification/drop-at-destroy!
                     frame-a actor-id (classified-spec nil) token-a))))
        (is (true? @fired?) "the drop-write container watch ran (fence exercised)")
        (is (and (some? @b-claim) (seq @b-claim))
            "B re-lowered its classification claim")
        (is (= @b-claim (elision-slot frame-a))
            "B's classification claim SURVIVES — A's mid-write drop did not re-root onto B")
        (is (= @b-commit (frame/frame-commit-epoch frame-a))
            "A's mid-write loss never bumped B's commit epoch")
        (finally
          (restore-plain-adapter!))))))
