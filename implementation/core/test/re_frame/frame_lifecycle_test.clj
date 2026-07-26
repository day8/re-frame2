(ns re-frame.frame-lifecycle-test
  "JVM lifecycle tests for re-frame.frame. The smoke suite covers the
  basics (frame-lifecycle, frame-multi-instance, destroy-frame-signals-
  active-machines); this file exercises the edge cases of make-frame,
  make-frame, destroy-frame!, ensure-default-frame!, and the surgical-
  update path. Per Spec 002 §Frames, §Destroy, §make-frame is atomic,
  §Re-registration — surgical update, §Per-instance frames.

  ## Posture split (rf2-d2841)

  Every assertion here is posture-independent — it holds in the ordinary
  `clojure -M:test` suite AND under the real production gate
  (`scripts/test-core-prod-gate.sh`, `-Dre-frame.debug=false`) — UNLESS it sits
  inside a `(when interop/debug-enabled? …)` arm marked `rf2-d2841`.

  Those arms observe the `:trace` stream, which is dev-only: every
  `trace/emit-error!` / lifecycle emit site is gated on
  `interop/debug-enabled?`, read once at namespace-load time, so under the gate
  the framework emits none of it BY DESIGN. What matters about a destroy is not
  that it ANNOUNCED itself but that it HAPPENED — the frame left the registry,
  the sub-cache disposed, the pending events never ran, the teardown continued
  past a throwing `:on-destroy`. Those are the assertions left outside the
  arms, and several were ADDED by rf2-d2841 where the trace had been the only
  witness."
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [re-frame.core :as rf]
            [re-frame.frame :as frame]
            [re-frame.interop :as interop]
            [re-frame.late-bind :as late-bind]
            [re-frame.registrar :as registrar]
            [re-frame.schemas :as schemas]
            [re-frame.source-store :as source-store]
            [re-frame.flows :as flows]
            [re-frame.substrate.adapter :as adapter]
            [re-frame.substrate.plain-atom :as plain-atom]
            [re-frame.trace :as trace])
  (:import [java.util.concurrent CountDownLatch TimeUnit]))

(defn reset-runtime [test-fn]
  (registrar/clear-all!)
  (reset! frame/frames {})
  (flows/reset-flows!)
  (schemas/clear-schemas-by-frame!)
  (trace/clear-listeners!)
  (rf/init! plain-atom/adapter)
  ;; Framework events / fx / subs are registered at namespace-load time;
  ;; clear-all! wiped them. Reload to resurrect the framework registrations.
  (require 're-frame.routing :reload)
  (require 're-frame.ssr     :reload)
  (require 're-frame.machines :reload)
  (test-fn))

(use-fixtures :each reset-runtime)

(defn- executor-barrier!
  "Wait until work already submitted through `interop/next-tick` has run.
  The JVM executor is single-threaded FIFO; the timeout is only a hang guard."
  []
  (let [latch (CountDownLatch. 1)]
    (interop/next-tick #(.countDown latch))
    (is (.await latch 5 TimeUnit/SECONDS)
        "the executor reached the deterministic barrier")))

;; ---- Spec 002 §Re-registration — surgical update -------------------------

(deftest make-frame-surgical-update-preserves-runtime-state
  (testing "re-registering a live frame replaces metadata but preserves app-db, sub-cache, and queue"
    ;; Set up a frame with live state.
    (rf/make-frame {:id :tenant :doc "v1 metadata" :preset :default})
    (rf/reg-event :seed (fn [{:keys [db]} [_ n]] {:db {:n n :payload "live"}}))
    (rf/reg-sub :n (fn [db _] (:n db)))
    ;; Populate app-db.
    (rf/dispatch-sync [:seed 42] {:frame :tenant})
    ;; Pin a sub in the cache.
    (let [pinned         (rf/subscribe [:n] {:frame :tenant})
          orig-record    (frame/frame :tenant)
          orig-app-db    (:app-db orig-record)
          orig-sub-cache (:sub-cache orig-record)
          orig-router    (:router orig-record)
          orig-lifecycle (:lifecycle orig-record)]
      (is (= 42 @pinned) "sub returns the seeded value before re-registration")
      (is (contains? @orig-sub-cache [:n]) "sub-cache holds the pinned entry")

      ;; Surgical update: re-register with new metadata.
      (rf/make-frame {:id :tenant :doc          "v2 metadata"
                      :fx-overrides {:http :stub.v2}})

      (let [new-record (frame/frame :tenant)]
        ;; Replaceable slot — config — is the new value.
        (is (= "v2 metadata" (get-in new-record [:config :doc]))
            ":config slot was replaced")
        (is (= {:http :stub.v2} (get-in new-record [:config :fx-overrides]))
            "new :fx-overrides are visible")
        ;; Preserved slots — app-db, sub-cache, router, lifecycle — are
        ;; the SAME mutable objects (identical?).
        (is (identical? orig-app-db    (:app-db new-record))
            "app-db container was preserved across re-registration")
        (is (identical? orig-sub-cache (:sub-cache new-record))
            "sub-cache atom was preserved")
        (is (identical? orig-router    (:router new-record))
            "router atom was preserved")
        (is (identical? orig-lifecycle (:lifecycle new-record))
            ":lifecycle map was preserved (not bumped to a new :created-at)"))

      ;; The seeded app-db value survives the re-registration.
      (is (= {:n 42 :payload "live"} (rf/app-db-value :tenant))
          "app-db value survived the surgical update")
      ;; The pinned reaction still resolves to the same value.
      (is (= 42 @pinned)
          "pinned subscription continues to read the live app-db"))))

;; ---- Spec 002 §Destroy — pending events ----------------------------------

(deftest destroy-frame-discards-pending-events
  (testing "events queued via async dispatch are not processed once the frame is destroyed"
    (rf/make-frame {:id :worker :doc "worker frame"})
    (let [side-effects (atom 0)
          traces       (atom [])
          ;; rf2-iosc: async dispatch schedules a drain via interop/next-tick
          ;; on a single-thread executor. On the JVM that drain can race the
          ;; main thread — the executor sometimes fires between the two
          ;; dispatches (or between dispatch and the queue read), draining
          ;; one or both events before the assertion runs. That made this
          ;; test flake under load (~1/200 runs in isolation, reliably
          ;; 1-in-N on CI). We deterministically suppress the drain by
          ;; intercepting next-tick for the lifetime of the enqueue +
          ;; queue-read, matching the same with-redefs pattern used by
          ;; `drain-after-destroy-does-not-npe` below.
          captured-ticks (atom [])]
      (rf/reg-event :tick (fn [{:keys [db]} _] (swap! side-effects inc) {:db db}))
      (rf/register-listener! :trace ::pending (fn [ev] (swap! traces conj ev)))
      (with-redefs [interop/next-tick (fn [f] (swap! captured-ticks conj f) nil)]
        (rf/dispatch [:tick] {:frame :worker})
        (rf/dispatch [:tick] {:frame :worker})
        ;; With the drain captured (never run), the queue deterministically
        ;; holds both pending events.
        (let [router (:router (frame/frame :worker))
              queue  (:queue @router)]
          (is (= 2 (count queue))
              "two events are queued and have NOT yet been processed")))
      ;; Destroy the frame. Per Spec 002 §Destroy: pending events drain
      ;; or get discarded; either way they MUST NOT corrupt state on a
      ;; gone frame, and any later attempt to dispatch traces
      ;; :rf.error/frame-destroyed.
      (rf/destroy-frame! :worker)
      (rf/unregister-listener! :trace ::pending)

      ;; The frame is gone.
      (is (nil? (frame/frame :worker))
          "destroy-frame! removed the frame from the registry")
      ;; A subsequent dispatch on the destroyed frame must trace
      ;; :rf.error/frame-destroyed (the recovery path; events do not
      ;; silently land in a void).
      (let [t-after (atom [])]
        (rf/register-listener! :trace ::after (fn [ev] (swap! t-after conj ev)))
        (rf/dispatch-sync [:tick] {:frame :worker})
        (rf/unregister-listener! :trace ::after)
        ;; rf2-d2841 — dev-instrumentation arm (see ns docstring).
        (when interop/debug-enabled?
          (is (some #(= :rf.error/frame-destroyed (:operation %)) @t-after)
              "post-destroy dispatch traces :rf.error/frame-destroyed"))
        ;; The handler did NOT run for the post-destroy attempt — the
        ;; counter stays bounded by what (if anything) drained.
        (is (<= @side-effects 2)
            "the handler did not run on the destroyed frame")))))

;; ---- Spec 002 §Destroy — machine cascade ---------------------------------

(deftest destroy-frame-cascade-emits-per-active-machine
  (testing "destroy emits one :rf.machine.lifecycle/destroyed per active machine snapshot, carrying :reason :parent-frame-destroyed"
    (rf/make-frame {:id :ten :doc "tenant"})
    ;; Seed three machine snapshots directly into app-db so we don't
    ;; depend on a full machine-runtime invocation.
    ;; EP-0001 (rf2-vzld77): machine snapshots are durable runtime-db state.
    (rf/reg-event :seed-machines
      (fn [{rt :rf.db/runtime} _]
        {:rf.db/runtime
         (assoc-in (or rt {}) [:rf.runtime/machines :snapshots]
                   {:flow/login    {:state :authed     :data {:user "a"}}
                    :flow/checkout {:state :reviewing  :data {:cart [1 2]}}
                    :flow/billing  {:state :collected  :data {}}})}))
    (rf/dispatch-sync [:seed-machines] {:frame :ten})
    ;; SEMANTIC, posture-independent (rf2-d2841): three machine snapshots
    ;; really are active, so the cascade below has three actors to walk.
    (is (= #{:flow/login :flow/checkout :flow/billing}
           (set (keys (get-in (:rf.db/runtime (rf/frame-state-value :ten))
                              [:rf.runtime/machines :snapshots]))))
        "three machine snapshots are active on :ten before the destroy")
    (let [traces (atom [])]
      (rf/register-listener! :trace ::cascade (fn [ev] (swap! traces conj ev)))
      (rf/destroy-frame! :ten)
      (rf/unregister-listener! :trace ::cascade)
      ;; SEMANTIC, posture-independent: the destroy completed — the frame and
      ;; its three snapshots are gone from the registry.
      (is (nil? (frame/frame :ten))
          ":ten (and every snapshot it held) left the registry")
      ;; rf2-d2841 — dev-instrumentation arm (see ns docstring). The
      ;; per-machine SIGNAL is a developer/tooling notification and rides the
      ;; dev `:trace` stream only.
      (when interop/debug-enabled?
        (let [cascade (filter #(= :rf.machine.lifecycle/destroyed (:operation %))
                              @traces)]
          (is (= 3 (count cascade))
              "one trace event per active machine snapshot")
          (is (every? #(= :ten (:frame (:tags %))) cascade)
              "all events carry the destroyed frame's id")
          (is (= #{:flow/login :flow/checkout :flow/billing}
                 (set (map #(:actor-id (:tags %)) cascade)))
              "every actor-id is signalled exactly once (rf2-ws5thu — the reaped live INSTANCE address; :machine-id reserved for the registered TYPE)")
          (is (= #{:authed :reviewing :collected}
                 (set (map #(:last-state (:tags %)) cascade)))
              "each event carries that machine's last-state from the snapshot")
          (is (every? #(= :parent-frame-destroyed (:reason (:tags %))) cascade)
              "each event carries :reason :parent-frame-destroyed (the unified discriminator)"))
        ;; A :rf.frame/destroyed trace is also emitted (after the cascade).
        (is (some #(= :rf.frame/destroyed (:operation %)) @traces)
            "expected a :rf.frame/destroyed trace")))))

;; ---- rf2-vsigt — frame destroy runs `:exit` cascades reverse-creation ----
;;
;; Per Spec 005 §Cross-Spec Interactions §1: a frame destroy with active
;; machine instances must run each machine's `:exit` cascade in reverse-
;; creation order BEFORE the snapshot is cleared. The legacy
;; `notify-machine-destruction!` only emitted the trace + fired the HTTP
;; abort hook (the test above) — this test exercises the real cascade
;; through the machines artefact via `reg-machine` + `[:rf.machine/spawn
;; ...]` so the wired-up runtime path is verified at the core boundary.

(deftest destroy-frame-runs-exit-cascades-in-reverse-creation-order
  (testing "destroy-frame! runs spawned actors' :exit actions newest-spawn-first"
    (rf/make-frame {:id :rf2-vsigt/auth :doc "rf2-vsigt cross-slice test frame"})
    (let [exit-log (atom [])
          child    {:initial :running
                    :data    {}
                    :states  {:running {:exit (fn [{data :data}]
                                                 (swap! exit-log
                                                        conj (:rf/self-id data))
                                                 {})}}}
          boot     {:initial :idle
                    :data    {}
                    :states
                    {:idle {:on {:go {:action (fn [_]
                                        {:fx [[:rf.machine/spawn
                                               {:machine-id :rf2-vsigt/child
                                                :id-prefix  :rf2-vsigt/child}]
                                              [:rf.machine/spawn
                                               {:machine-id :rf2-vsigt/child
                                                :id-prefix  :rf2-vsigt/child}]
                                              [:rf.machine/spawn
                                               {:machine-id :rf2-vsigt/child
                                                :id-prefix  :rf2-vsigt/child}]]})}}}}}]
      (rf/reg-machine :rf2-vsigt/child child)
      (rf/reg-machine :rf2-vsigt/boot boot)
      (rf/dispatch-sync [:rf2-vsigt/boot [:go]] {:frame :rf2-vsigt/auth})
      ;; All 3 spawned actors are live.
      (is (= 3 (count (filter #{:rf2-vsigt/child#1
                                 :rf2-vsigt/child#2
                                 :rf2-vsigt/child#3}
                              (keys (get-in (:rf.db/runtime (rf/frame-state-value :rf2-vsigt/auth)) [:rf.runtime/machines :snapshots])))))
          "three spawned actor snapshots live at [:rf.runtime/machines :snapshots <id>]")
      ;; Destroy the frame.
      (rf/destroy-frame! :rf2-vsigt/auth)
      ;; :exit fired three times in REVERSE-spawn order.
      (is (= [:rf2-vsigt/child#3 :rf2-vsigt/child#2 :rf2-vsigt/child#1]
             @exit-log)
          ":exit ran newest-first per Spec 005 §Cross-Spec Interactions §1")
      ;; Every spawned actor handler is unregistered.
      (is (nil? (registrar/lookup :event :rf2-vsigt/child#1))
          "spawned actor handler #1 was unregistered")
      (is (nil? (registrar/lookup :event :rf2-vsigt/child#2))
          "spawned actor handler #2 was unregistered")
      (is (nil? (registrar/lookup :event :rf2-vsigt/child#3))
          "spawned actor handler #3 was unregistered")
      ;; The singleton `:rf2-vsigt/child` / `:rf2-vsigt/boot` machines
      ;; stay registered — handlers live in the global registrar; the
      ;; frame destroy walk does NOT unregister them.
      (is (some? (registrar/lookup :event :rf2-vsigt/child))
          "singleton :rf2-vsigt/child handler stays globally registered")
      (is (some? (registrar/lookup :event :rf2-vsigt/boot))
          "singleton :rf2-vsigt/boot handler stays globally registered"))))

;; ---- Spec 002 §Destroy — live subscribers --------------------------------

(deftest destroy-frame-with-live-subscribers
  (testing "destroy clears the sub-cache, fires on-dispose, and post-destroy subs return nil"
    (rf/make-frame {:id :live :doc "live"})
    (rf/reg-event :seed (fn [{:keys [db]} _] {:db {:answer 7}}))
    (rf/reg-sub :answer (fn [db _] (:answer db)))
    (rf/dispatch-sync [:seed] {:frame :live})
    (let [r1            (rf/subscribe [:answer] {:frame :live})
          r2            (rf/subscribe [:answer] {:frame :live})
          dispose-fired (atom 0)]
      ;; Pin the cache entry and attach an on-dispose hook so we can
      ;; observe destroy clearing it.
      (re-frame.interop/add-on-dispose! r1 (fn [] (swap! dispose-fired inc)))
      (is (= 7 @r1) "sub reads the seeded value before destroy")
      (is (identical? r1 r2) "the cache returns the same reaction on repeat subscribe")
      (let [cache (:sub-cache (frame/frame :live))]
        (is (contains? @cache [:answer]) "cache holds the entry"))

      ;; Capture traces and destroy.
      (let [traces (atom [])]
        (rf/register-listener! :trace ::sub-destroy (fn [ev] (swap! traces conj ev)))
        (rf/destroy-frame! :live)
        (rf/unregister-listener! :trace ::sub-destroy)
        (is (= 1 @dispose-fired)
            "on-dispose hook fired exactly once when destroy walked the sub-cache")
        ;; rf2-d2841 — dev-instrumentation arm (see ns docstring). The
        ;; cache-clearing this deftest is named for is `@dispose-fired` above
        ;; and the nil post-destroy subscribe below — both posture-independent.
        (when interop/debug-enabled?
          (is (some #(= :rf.frame/destroyed (:operation %)) @traces)
              "expected :rf.frame/destroyed trace")))

      ;; Post-destroy subscribe returns nil with :replaced-with-default.
      (let [t-after (atom [])]
        (rf/register-listener! :trace ::post (fn [ev] (swap! t-after conj ev)))
        (let [r3 (rf/subscribe [:answer] {:frame :live})]
          (is (nil? r3)
              "subscribe against a destroyed frame returns nil"))
        (rf/unregister-listener! :trace ::post)
        ;; rf2-d2841 — dev-instrumentation arm (see ns docstring).
        (when interop/debug-enabled?
          (is (some (fn [ev]
                      (and (= :rf.error/frame-destroyed (:operation ev))
                           (= :replaced-with-default (:recovery ev))))
                    @t-after)
              "expected :rf.error/frame-destroyed trace with :replaced-with-default recovery"))))))

;; ---- Spec 002 §:rf/default — ensure-default-frame! idempotence -----------

(deftest ensure-default-frame-is-idempotent
  (testing "calling the TEST-ONLY ensure-default-frame! helper more than once does not duplicate or replace the default frame"
    ;; Per EP-0002, init! no longer creates :rf/default. ensure-default-frame!
    ;; survives only as a test-fixture helper; create the frame on demand,
    ;; then prove repeat calls are idempotent.
    (is (nil? (frame/frame :rf/default))
        ":rf/default is absent after init! (the runtime never synthesises it)")
    (frame/ensure-default-frame!)
    (let [original (frame/frame :rf/default)]
      (is (some? original) ":rf/default exists after the test-only helper creates it")
      ;; Repeated calls should be no-ops on the already-registered frame.
      (frame/ensure-default-frame!)
      (frame/ensure-default-frame!)
      (frame/ensure-default-frame!)
      (let [now (frame/frame :rf/default)]
        (is (identical? (:app-db    original) (:app-db    now))
            ":rf/default's app-db container was NOT replaced")
        (is (identical? (:sub-cache original) (:sub-cache now))
            ":rf/default's sub-cache was NOT replaced")
        (is (identical? (:router    original) (:router    now))
            ":rf/default's router was NOT replaced")
        (is (= (:created-at (:lifecycle original))
               (:created-at (:lifecycle now)))
            ":rf/default's :created-at is unchanged across repeat calls"))
      ;; Only one entry for :rf/default lives in the frames atom.
      (is (= 1 (count (filter #{:rf/default} (keys @frame/frames))))
          "the frames map has a single :rf/default entry"))))

;; ---- Spec 002 §Per-instance frames --------------------------------------

(deftest make-frame-gensyms-id-and-isolates-fx-overrides
  (testing "make-frame returns gensym'd ids; per-frame :fx-overrides isolate effect handling"
    (let [calls-real (atom [])
          calls-A    (atom [])
          calls-B    (atom [])]
      ;; Register the canonical fx and two stubs. The override map lets
      ;; each frame redirect :http to its own stub independently.
      (rf/reg-fx :http
                 {:platforms #{:server :client}}
                 (fn [_ args] (swap! calls-real conj args)))
      (rf/reg-fx :http.stub-A
                 {:platforms #{:server :client}}
                 (fn [_ args] (swap! calls-A conj args)))
      (rf/reg-fx :http.stub-B
                 {:platforms #{:server :client}}
                 (fn [_ args] (swap! calls-B conj args)))
      (rf/reg-event :go
        (fn [_ [_ payload]]
          {:fx [[:http payload]]}))

      (let [a (frame/make-anon-frame-record! {:fx-overrides {:http :http.stub-A}})
            b (frame/make-anon-frame-record! {:fx-overrides {:http :http.stub-B}})]
        ;; Gensym contract: id is a keyword in the :rf.frame namespace,
        ;; the two ids differ, and neither collides with :rf/default.
        (is (keyword? a))
        (is (keyword? b))
        (is (= "rf.frame" (namespace a)))
        (is (= "rf.frame" (namespace b)))
        (is (not= a b)
            "two make-frame calls produce distinct gensym'd ids")
        (is (not= a :rf/default))
        (is (not= b :rf/default))

        ;; Drive the same event into each frame; per-frame overrides
        ;; route the :http effect to that frame's stub.
        (rf/dispatch-sync [:go {:url "/A"}] {:frame a})
        (rf/dispatch-sync [:go {:url "/B"}] {:frame b})

        (is (= [{:url "/A"}] @calls-A)
            "frame A's override sent :http to stub-A")
        (is (= [{:url "/B"}] @calls-B)
            "frame B's override sent :http to stub-B")
        (is (empty? @calls-real)
            "the canonical :http handler was not invoked under either override")))))

;; ---- Spec 002 §make-frame is atomic — :initial-events runs synchronously --

(deftest make-frame-initial-events-runs-synchronously
  (testing ":initial-events completes inside make-frame; app-db is fully populated by the time it returns (EP-0027)"
    (let [observed (atom nil)]
      (rf/reg-event :boot
        (fn [{:keys [db]} [_ payload]]
          {:db {:booted? true :payload payload :seq [:a :b :c]}}))
      ;; Hook a trace listener so we can assert the ordering between the
      ;; setup-step dispatch and :rf.frame/created emission. Per Spec 002
      ;; §make-frame is atomic — the setup runs first, then :rf.frame/created
      ;; is emitted (the frame becomes observable to listeners).
      (let [traces (atom [])]
        (rf/register-listener! :trace ::oc (fn [ev] (swap! traces conj ev)))
        (rf/make-frame {:id :booted :doc            "frame with initial-events"
                        :initial-events [[:boot {:hello "world"}]]})
        (rf/unregister-listener! :trace ::oc)

        ;; The moment make-frame returned, app-db must already reflect
        ;; the setup event's commit.
        (reset! observed (rf/app-db-value :booted))
        (is (true? (:booted? @observed))
            ":initial-events ran synchronously — app-db reflects its commit")
        (is (= {:hello "world"} (:payload @observed))
            ":initial-events payload landed in app-db")
        (is (= [:a :b :c] (:seq @observed))
            "full setup handler body completed before make-frame returned")

        ;; Ordering: the :rf.event/run-end for :boot precedes :rf.frame/created
        ;; (make-frame emits :rf.frame/created AFTER the setup dispatch-syncs).
        ;;
        ;; rf2-d2841 — dev-instrumentation arm (see ns docstring). The
        ;; SYNCHRONY this deftest is named for is the three app-db assertions
        ;; above, read the instant `make-frame` returned; they run in both
        ;; postures. The trace ORDER below is the same fact restated on a
        ;; dev-only channel — under the gate there is no trace ring to index
        ;; into, so `run-end-idx` / `created-idx` are both nil.
        (when interop/debug-enabled?
        (let [run-end-idx (->> @traces
                               (keep-indexed
                                 (fn [i ev]
                                   (when (and (= :rf.event/run-end (:operation ev))
                                              (= :boot (:rf.trace/event-id (:tags ev))))
                                     i)))
                               first)
              created-idx (->> @traces
                               (keep-indexed
                                 (fn [i ev]
                                   (when (= :rf.frame/created (:operation ev))
                                     i)))
                               first)]
          (is (some? run-end-idx)
              "expected an :event :run-end trace for the setup event")
          (is (some? created-idx)
              "expected a :rf.frame/created trace")
          (is (< run-end-idx created-idx)
              "the setup's :run-end precedes :rf.frame/created — frame is fully booted before listeners observe it")))))))

;; ---- rf2-68kok — destroy-frame! interrupts active drain on next dequeue --
;;
;; Per Spec 002 §Edge cases worth pinning §Frame disposal mid-drain: the
;; ordinary drain checks destruction ownership before each dequeue; the exact
;; incarnation claim is the cutoff, lifecycle-dead may publish later. On detect
;; it stops and emits one `:rf.frame/drain-interrupted` whose dropped count
;; combines claim-time and check-time removals. An authored callback already on
;; the stack may return and entered authored afters may unwind, but its returned
;; framework tail is inert.

(deftest destroy-from-handler-interrupts-drain-and-emits-interrupted
  (testing "a handler that destroys its own frame mid-drain stops the
            drain, drops the remaining queue, and emits exactly one
            :rf.frame/drain-interrupted carrying the dropped count.
            The authored self-destruct callback may return, but its returned
            framework tail is inert and later queued events are dropped"
    (rf/make-frame {:id :drain-int/worker :doc "rf2-68kok drain-interrupt frame"})
    (let [ran           (atom [])
          traces        (atom [])
          captured-tick (atom [])]
      ;; Plain mutator — runs whenever the drain dequeues.
      (rf/reg-event :drain-int/tick
        (fn [{:keys [db]} _]
          (swap! ran conj :tick)
          {:db (update db :n (fnil inc 0))}))
      ;; Handler that destroys its own frame mid-cascade. The first
      ;; tick has already run; destroy mid-drain MUST interrupt the
      ;; remainder.
      (rf/reg-event :drain-int/self-destruct
        (fn [_ _]
          (swap! ran conj :self-destruct)
          ;; Call destroy-frame! directly — bypasses the
          ;; `dispatch-sync-in-handler` policy that would otherwise
          ;; bite us if we tried to dispatch destruction through the
          ;; router. Spec 002 says destroy-frame! interrupts the
          ;; ACTIVE drain; the trigger can be anything.
          (frame/destroy-frame! :drain-int/worker)
          {}))
      (rf/register-listener! :trace ::drain-int (fn [ev] (swap! traces conj ev)))
      ;; Pre-seed the queue with 4 plain async dispatches, then sync-
      ;; drain the self-destruct AT THE FRONT — it runs first, destroys
      ;; the frame, and the remaining 4 ticks (still queued) must be
      ;; dropped, with exactly one :rf.frame/drain-interrupted emitted.
      ;;
      ;; Per rf2-68kok JVM-vs-CLJS race fix: the JVM `interop/next-tick`
      ;; runs on a separate single-thread executor; without redef'ing it
      ;; the async drain races the main thread and may drain the 4 ticks
      ;; BEFORE `dispatch-sync` reaches the drain-lock — leaving an empty
      ;; queue and yielding `:dropped-count 0`. CLJS `next-tick` is a
      ;; next-turn TASK (not a microtask) and cannot run until the test's
      ;; synchronous stack unwinds, so the race is JVM-only. Capturing
      ;; the scheduled drains via `with-redefs` makes the ordering
      ;; deterministic on both platforms — by the time `dispatch-sync`
      ;; runs, the 4 ticks are still in the queue and no async drainer
      ;; has touched the drain-lock. The captured drains are discarded
      ;; (the frame is destroyed before any of them would dequeue, and
      ;; rf2-dpny says a drain on an already-destroyed frame is a silent
      ;; no-op — the existing `destroy-from-different-thread-also-…`
      ;; test pins that contract separately).
      (with-redefs [interop/next-tick (fn [f] (swap! captured-tick conj f) nil)]
        (rf/dispatch [:drain-int/tick] {:frame :drain-int/worker})
        (rf/dispatch [:drain-int/tick] {:frame :drain-int/worker})
        (rf/dispatch [:drain-int/tick] {:frame :drain-int/worker})
        (rf/dispatch [:drain-int/tick] {:frame :drain-int/worker})
        (rf/dispatch-sync [:drain-int/self-destruct] {:frame :drain-int/worker}))
      (rf/unregister-listener! :trace ::drain-int)

      ;; The self-destruct ran first (dispatch-sync seeds at the front);
      ;; the trailing ticks did NOT run.
      (is (= [:self-destruct] @ran)
          "the only handler that ran was the self-destruct seed itself")

      ;; The frame is gone.
      (is (nil? (frame/frame :drain-int/worker))
          "destroy-frame! removed the frame from the registry")

      ;; Exactly one :rf.frame/drain-interrupted trace fired carrying
      ;; the dropped count (4 ticks queued before the seed).
      ;;
      ;; rf2-d2841 — dev-instrumentation arm (see ns docstring). The
      ;; INTERRUPTION itself is `(= [:self-destruct] @ran)` and the empty
      ;; registry slot above — both posture-independent; the report of how
      ;; many events were dropped is a dev-tooling detail.
      (when interop/debug-enabled?
        (let [interrupts (filterv (fn [ev]
                                    (= :rf.frame/drain-interrupted (:operation ev)))
                                  @traces)]
          (is (= 1 (count interrupts))
              "exactly one :rf.frame/drain-interrupted trace")
          (let [ev (first interrupts)]
            (is (= :rf.frame (:op-type ev))
                ":op-type is :frame (lifecycle family, not :error)")
            (is (= :drain-int/worker (:frame (:tags ev)))
                ":tags carries the destroyed frame id")
            (is (= 4 (:dropped-count (:tags ev)))
                ":dropped-count reflects the 4 trailing ticks left in the queue")))))))

(deftest destroy-from-handler-allows-authored-callback-return
  (testing "destroy does not forcibly interrupt authored code already on the
            stack, but the returned framework tail is inert"
    (rf/make-frame {:id :rtc/worker})
    (let [completed (atom false)]
      ;; This handler:
      ;;   (a) destroys the frame partway through
      ;;   (b) keeps running — does more work after the destroy call
      ;;   (c) sets the completion flag at the very end
      ;; Authored code already on the stack may return. The exact-incarnation
      ;; fence governs the framework-owned work after that return.
      (rf/reg-event :rtc/work-then-destroy
        (fn [_ _]
          (frame/destroy-frame! :rtc/worker)
          ;; Post-destroy bookkeeping still runs inside this handler.
          (reset! completed true)
          {}))
      (rf/dispatch-sync [:rtc/work-then-destroy] {:frame :rtc/worker})
      (is (true? @completed)
          "authored handler code returned after calling destroy-frame!"))))

(deftest destroy-from-different-thread-also-interrupts-drain-on-next-pass
  (testing "if another thread (or the same thread between passes)
            destroys the frame, the next pass's destroyed-check fires
            and interrupts the drain just the same"
    (rf/make-frame {:id :cross-thread/worker})
    (let [traces (atom [])
          captured-tick (atom [])]
      (rf/reg-event :cross-thread/tick (fn [{:keys [db]} _] {:db db}))
      (rf/register-listener! :trace ::xt (fn [ev] (swap! traces conj ev)))
      ;; Capture the executor's tick so we can land destroy BETWEEN
      ;; the async dispatch and the actual drain.
      (with-redefs [interop/next-tick (fn [f] (swap! captured-tick conj f) nil)]
        (rf/dispatch [:cross-thread/tick] {:frame :cross-thread/worker})
        (rf/dispatch [:cross-thread/tick] {:frame :cross-thread/worker})
        (rf/dispatch [:cross-thread/tick] {:frame :cross-thread/worker})
        ;; All 3 in queue, drain captured (not yet run).
        )
      ;; Destroy BEFORE the drain runs. drain-try!'s outer guard
      ;; (`when frame-record`) catches this case and never enters
      ;; the drain loop at all — that's the existing rf2-dpny
      ;; contract and our new interrupt-handler doesn't fire here
      ;; (the drain pass never starts).
      (frame/destroy-frame! :cross-thread/worker)
      ;; Run captured ticks. Pre-destroy drain-try! sees nil
      ;; frame-record and short-circuits without emitting
      ;; :rf.frame/drain-interrupted (the drain never started).
      (doseq [tick @captured-tick] (tick))
      (rf/unregister-listener! :trace ::xt)
      ;; The pre-existing rf2-dpny contract: NO trace at all from the
      ;; drain itself. The interrupt trace only fires when a drain
      ;; pass actually STARTED on a live frame and then detected
      ;; destruction mid-pass.
      (let [interrupts (filter #(= :rf.frame/drain-interrupted (:operation %))
                               @traces)]
        (is (zero? (count interrupts))
            "no :rf.frame/drain-interrupted — the drain never started
             on the already-destroyed frame")))))

;; ---- EP-0027 — handler-time frame construction is FORBIDDEN --------------
;;
;; Per EP-0027 §Construction: frames are created by the VIEW (`frame-root`, the
;; ENSURE boundary — `frame-provider` is SCOPE and creates nothing) or at TOP
;; LEVEL (tests, boot, SSR per request — an explicit `make-frame`).
;; Constructing a frame INSIDE an
;; event handler is not supported — a handler changes app-db, and the view
;; materializes frames from it — and fails loud with
;; `:rf.error/frame-construction-in-handler`. This REMOVES the prior rf2-cufbh
;; two-regime `:on-create` handling (the mid-cascade case used to async-queue the
;; child's `:on-create`; it is now a fail-loud error). The signal for "inside a
;; handler" is `trace/*handler-scope*` being bound (the router binds it for the
;; duration of a handler's run and ONLY then; a bare ambient `with-frame` scope
;; does not bind it), so a genuine TOP-LEVEL construction still runs setup
;; synchronously.

(deftest make-frame-in-handler-fails-loud
  (testing "make-frame called inside a handler fails
            :rf.error/frame-construction-in-handler and leaves no half-registered
            child (EP-0027 §Construction)"
    ;; EP-0002 (rf2-9o48ih): the parent frame is the carried scope for the bare
    ;; parent dispatch below — register `:rf/default` explicitly (the fixture no
    ;; longer synthesises it) and target it.
    (rf/make-frame {:id :rf/default})
    (let [caught (atom nil)]
      (rf/reg-event :child/boot
        (fn [{:keys [db]} _] {:db (assoc db :child-booted? true)}))
      (rf/reg-event :parent/spawn-child
        (fn [{:keys [db]} _]
          ;; Capture the construction throw inside the handler so we can assert
          ;; its id without depending on how dispatch-sync surfaces it.
          (reset! caught
                  (try (rf/make-frame {:id :child :initial-events [[:child/boot]]})
                       nil
                       (catch clojure.lang.ExceptionInfo e
                         (:rf.error/id (ex-data e)))))
          {:db db}))
      (rf/dispatch-sync [:parent/spawn-child] {:frame :rf/default})
      (is (= :rf.error/frame-construction-in-handler @caught)
          "a handler-time make-frame fails loud")
      (is (nil? (frame/frame :child))
          "the just-created child container was torn back down — no half-registered frame"))))

(deftest make-frame-construction-failure-leaves-no-registrar-or-trace-residue
  (testing "rf2-hhlzky — a make-frame whose frame CONSTRUCTION fails (no adapter
            installed: make-frame before rf/init!) leaves NO frames-store record
            and NO trace-disabled residue. `new-frame-record` (which calls
            make-state-container) is built BEFORE the trace-
            suppression writes, so a construction throw escapes before any
            process-global metadata is written — nothing to roll back."
    (let [fid :test/no-adapter-frame]
      ;; Cold-start the adapter lifecycle to the never-installed state so
      ;; make-state-container raises :rf.error/no-adapter-installed — the exact
      ;; "make-frame before rf/init!" scenario the bead names. The test frame
      ;; carries :rf.trace/frame-no-emit? true, whose set-frame-no-emit! write
      ;; is the trace residue we assert never lands.
      (adapter/reset-lifecycle-state-for-tests!)
      (let [thrown-id (try (rf/make-frame {:id fid :rf.trace/frame-no-emit? true})
                           nil
                           (catch clojure.lang.ExceptionInfo e
                             (:rf.error/id (ex-data e))))]
        (is (= :rf.error/no-adapter-installed thrown-id)
            "construction fails loud with the no-adapter throw (frame never built)"))
      ;; No half-registered process-global state survives the failed build:
      (is (nil? (frame/frame fid))
          "no frame record landed in the frames atom")
      (is (nil? (frame/frame-meta fid))
          "the failed frame is invisible to the frame-meta introspection surface")
      (is (false? (trace/frame-trace-disabled? fid))
          "no trace-disabled residue — set-frame-no-emit! never ran for the failed frame")
      ;; Restore a working adapter so the fixture teardown / next test are clean.
      (rf/init! plain-atom/adapter))))

;; ---- rf2-h1vqa4 — substrate ownership: frames never touch the registrar /
;;      source store ---------------------------------------------------------
;;
;; A frame is a LIVE runtime object, not an image-resolved program member.
;; Before the fix, the engine routed every seat/reseat through
;; `registrar/register! :frame`, which ALSO recorded a `:frame` descriptor in
;; the provenance source store and bumped the source-store GENERATION — the
;; key leg of the resolved-image-generation cache (EP-0023). So merely seating
;; or hot-reseating a frame invalidated the default-generation cache and fired
;; the live-frame reprojection hook, for a change that is NOT a
;; registration-pool change. This test pins the ownership fix end-to-end.

(deftest frame-seating-writes-no-registrar-row-and-no-source-store-descriptor
  (testing "rf2-h1vqa4 — seating + reseating a frame writes NO :frame registrar
            row, records NO :frame source-store descriptor, and does NOT bump
            the source-store generation (no resolved-image-generation cache
            invalidation, no image-descriptor churn); frame introspection rides
            frame-meta"
    (let [gen-before (source-store/store-generation)]
      ;; First seat + hot reseat (surgical update) + a make-frame reseat.
      (rf/make-frame {:id :h1vqa4/owned :doc "v1"})
      (rf/make-frame {:id :h1vqa4/owned :doc "v2 (reseat refreshes config)"})
      (is (= "v2 (reseat refreshes config)" (:doc (rf/frame-meta :h1vqa4/owned)))
          "the reseat refreshed the stored config (upsert semantics) via frame-meta")
      (is (nil? (registrar/lookup :frame :h1vqa4/owned))
          "no :frame registrar row exists for the seated frame")
      (is (not (contains? (registrar/registrations :frame) :h1vqa4/owned))
          "the (reserved, intentionally empty) :frame registrar slot stays empty")
      (is (empty? (source-store/descriptors-for :frame :h1vqa4/owned))
          "no :frame descriptor was recorded in the provenance source store")
      (is (= gen-before (source-store/store-generation))
          "the source-store generation did NOT move — seating/reseating a frame
           cannot invalidate the resolved-image-generation cache")
      ;; Destroy leaves the world exactly as before (frames store only).
      (rf/destroy-frame! :h1vqa4/owned)
      (is (nil? (rf/frame-meta :h1vqa4/owned)) "destroyed frame is gone")
      (is (= gen-before (source-store/store-generation))
          "destroy does not move the source-store generation either"))))

(deftest top-level-make-frame-initial-events-runs-synchronously
  (testing "Top-level make-frame (NOT inside a handler) runs :initial-events
            synchronously — the fast path is preserved (EP-0027 §Construction)"
    (let [order (atom [])]
      (rf/reg-event :top/init
        (fn [{:keys [db]} _]
          (swap! order conj :top/init-ran)
          {:db {:initialised? true}}))
      ;; No in-flight handler; *current-frame* is nil. The setup must run
      ;; synchronously, before make-frame returns.
      (rf/make-frame {:id :top :initial-events [[:top/init]]})
      (is (= [:top/init-ran] @order)
          ":top/init ran inside make-frame (synchronous top-level path)")
      (is (true? (:initialised? (rf/app-db-value :top)))
          "top-level app-db reflects the setup commit by make-frame return"))))

(deftest make-frame-record-in-handler-fails-loud
  (testing "make-frame / make-anon-frame-record! from inside a handler also fails
            loud — it shares the make-frame construction guard (EP-0027)"
    (rf/make-frame {:id :rf/default})
    (let [caught   (atom nil)
          child-id (atom nil)]
      (rf/reg-event :sub-actor/boot
        (fn [{:keys [db]} _] {:db (assoc db :booted? true)}))
      (rf/reg-event :parent/spawn-sub-actor
        (fn [{:keys [db]} _]
          (reset! caught
                  (try (reset! child-id
                               (frame/make-anon-frame-record!
                                 {:initial-events [[:sub-actor/boot]]}))
                       nil
                       (catch clojure.lang.ExceptionInfo e
                         (:rf.error/id (ex-data e)))))
          {:db db}))
      (rf/dispatch-sync [:parent/spawn-sub-actor] {:frame :rf/default})
      (is (= :rf.error/frame-construction-in-handler @caught)
          "a handler-time make-anon-frame-record! fails loud")
      (when @child-id
        (is (nil? (frame/frame @child-id))
            "no half-registered child frame is left behind")))))

;; ---- Spec 002 §Frame presets — closed v1 expansion table -----------------
;;
;; Presets expand at registration time into a fixed bundle of metadata
;; keys. Per Spec 002 §Frame presets the closed v1 set is :default,
;; :test, :story, :ssr-server. User-supplied keys win on conflict; the
;; original :preset value is preserved verbatim for inspection.

(deftest preset-expansion-default
  (testing ":default expands to {} (identical to omitting :preset)"
    (rf/make-frame {:id :p/default :preset :default})
    (let [cfg (:config (frame/frame :p/default))]
      (is (= :default (:preset cfg))
          ":preset is preserved on the config for inspection")
      (is (nil? (:fx-overrides cfg))
          ":default does not introduce :fx-overrides")
      (is (nil? (:drain-depth cfg))
          ":default does not introduce :drain-depth"))))

(deftest preset-expansion-test
  (testing ":test expansion: HTTP redirect + drain-depth 100 + strict mint policy"
    (rf/make-frame {:id :p/test :preset :test})
    (let [cfg (:config (frame/frame :p/test))]
      (is (= :test (:preset cfg)))
      (is (= {:rf.http/managed :rf.http/managed-canned-success}
             (:fx-overrides cfg))
          ":test redirects the canonical Spec 014 HTTP fx to its canned-success stub")
      (is (= 100 (:drain-depth cfg))
          ":test stamps :drain-depth 100 explicitly so tooling reads it off frame-meta")
      ;; EP-0017 §6 / slice-B.8 (rf2-5spzo7): the :test preset defaults the
      ;; cofx mint policy to :strict — a declared-absent generator-backed
      ;; recordable fact under a test frame is missing-required, never a
      ;; freshly-minted per-run value (strict-by-default tests are core).
      (is (= :strict (:rf.cofx/mint-policy cfg))
          ":test defaults the cofx mint policy to :strict"))))

(deftest preset-expansion-story
  (testing ":story expansion: HTTP redirect + tighter drain-depth 16"
    (rf/make-frame {:id :p/story :preset :story})
    (let [cfg (:config (frame/frame :p/story))]
      (is (= :story (:preset cfg)))
      (is (= {:rf.http/managed :rf.http/managed-canned-success}
             (:fx-overrides cfg)))
      (is (= 16 (:drain-depth cfg))
          ":story tightens :drain-depth to 16 so a runaway cascade fails fast under a story")
      ;; EP-0017 §6 / slice-B.8 (rf2-5spzo7): a story is a live demo, NOT a
      ;; determinism fixture — it is NOT strict-by-default and rides the
      ;; router's :live default (no mint-policy entry on the expansion).
      (is (nil? (:rf.cofx/mint-policy cfg))
          ":story carries no mint-policy entry — it rides the :live router default"))))

(deftest preset-expansion-ssr-server
  (testing ":ssr-server expansion: :platform :server"
    (rf/make-frame {:id :p/ssr :preset :ssr-server})
    (let [cfg (:config (frame/frame :p/ssr))]
      (is (= :ssr-server (:preset cfg)))
      (is (= :server (:platform cfg))
          ":ssr-server stamps :platform :server (singular keyword — one platform per frame)"))))

(deftest preset-user-keys-win-on-conflict
  (testing "user-supplied keys override individual expansion entries"
    ;; :test sets :drain-depth 100 by default; user overrides to 1000.
    (rf/make-frame {:id :p/override :preset      :test
                    :drain-depth 1000})
    (let [cfg (:config (frame/frame :p/override))]
      (is (= :test (:preset cfg)))
      (is (= 1000 (:drain-depth cfg))
          "user :drain-depth wins over the preset's expansion default")
      (is (= {:rf.http/managed :rf.http/managed-canned-success}
             (:fx-overrides cfg))
          "non-overridden expansion entries still apply"))))

(deftest preset-unknown-throws
  (testing "unknown preset values throw :rf.error/unknown-preset"
    (is (thrown? Exception
          (rf/make-frame {:id :p/bad :preset :devcards}))
        "passing a preset outside the closed v1 set is a registration-time error")))

;; ---- rf2-ft2b: drain-after-destroy null guard ----------------------------
;;
;; Per the rf2-ft2b reproducer: a scheduled drain races frame destruction.
;; By the time the drain fires, the frame's app-db container is nil
;; (because frame/app-db-container returns nil for destroyed frames), and
;; the per-event :db commit at router.cljc would write through nil →
;; NullPointerException on the executor thread.
;;
;; The fix is a defense-in-depth guard at the single choke point through
;; which every container write flows: substrate/adapter/replace-container!
;; no-ops when container is nil and emits :rf.error/write-after-destroy.
;; (EP-0008 / rf2-500ech promoted this from :rf.warning to the always-on
;; :rf.error category — same destroy-race the dispatch/subscribe paths
;; already surfaced production-survivably as :rf.error/frame-destroyed.)

(deftest replace-container-no-ops-on-nil-container
  ;; Direct unit-level coverage of the guard at the adapter wrapper. The
  ;; nil-container call must not throw and must emit the error trace.
  (testing "replace-container! with nil container is a no-op + :rf.error/write-after-destroy"
    (let [recorded (atom [])]
      (rf/register-listener! :trace ::rec (fn [ev] (swap! recorded conj ev)))
      ;; Must not throw NPE.
      (is (nil? (adapter/replace-container! nil {:any :value}))
          "nil container is a documented no-op, not an exception")
      ;; rf2-d2841 — dev-instrumentation arm (see ns docstring). The guard's
      ;; production-real half is the no-op-instead-of-NPE assertion above,
      ;; which is the whole point of the rf2-ft2b fix and runs in both
      ;; postures; the REPORT of the skipped write is a dev diagnostic.
      (when interop/debug-enabled?
        (let [errs (filterv (fn [ev]
                              (and (= :error (:op-type ev))
                                   (= :rf.error/write-after-destroy
                                      (:operation ev))))
                            @recorded)]
          (is (= 1 (count errs))
              "exactly one :rf.error/write-after-destroy trace fired"))))))

(deftest drain-after-destroy-does-not-npe
  ;; Reproducer for the original race (rf2-ft2b): a scheduled drain that
  ;; reaches the per-event :db commit AFTER the frame has been destroyed
  ;; must NOT throw. Instead the adapter-level nil guard skips the write
  ;; and emits :rf.error/write-after-destroy (EP-0008 / rf2-500ech).
  ;;
  ;; The race is forced deterministically by capturing the next-tick
  ;; callback (instead of running it on the executor) so that the
  ;; destroy-frame! call slots in between the drain being scheduled and
  ;; the drain actually running.
  (testing "scheduled drain that fires after destroy is a no-op + warning, not an NPE"
    (let [captured-tick (atom nil)
          recorded      (atom [])]
      (rf/register-listener! :trace ::rec (fn [ev] (swap! recorded conj ev)))
      (rf/make-frame {:id :race/frame :doc "rf2-ft2b reproducer frame"})
      ;; A simple :db-writing event handler. The drain that processes
      ;; this event is what we want to land AFTER destroy.
      (rf/reg-event :write
        (fn [{:keys [db]} _]
          {:db {:committed? true}}))
      (with-redefs [interop/next-tick (fn [f] (reset! captured-tick f) nil)]
        ;; Async dispatch — schedules the drain via next-tick. The
        ;; with-redefs binding captures the drain thunk into
        ;; @captured-tick instead of executing it.
        (rf/dispatch [:write] {:frame :race/frame})
        (is (some? @captured-tick)
            "the async dispatch scheduled a drain via next-tick"))
      ;; Now destroy the frame. After this, frame/app-db-container returns
      ;; nil, so the captured drain — when it fires — would write
      ;; through nil if not for the adapter guard.
      (frame/destroy-frame! :race/frame)
      ;; Fire the captured drain. Pre-fix this raised an NPE; post-fix
      ;; the drain enters drain!, finds (frame frame-id) is nil (the
      ;; outer guard at drain! line ~261), so process-event! is never
      ;; called and no write is attempted. We assert no throw.
      (is (nil? (try (@captured-tick) nil
                     (catch Throwable e e)))
          "the drain ran without throwing"))))

;; ---- rf2-dpny: pin race semantics rather than the workaround --------------
;;
;; Per rf2-dpny: the PR #327 fix is correct but fragile. If someone
;; later removes the `with-redefs [interop/next-tick ...]` thinking it's
;; redundant, the flake returns. This test pins the CONTRACT —
;; "draining onto a destroyed frame is a quiet no-op" — by
;; capturing next-tick, dispatching events, destroying the frame, and
;; THEN running the captured tick. The drain must not throw or mutate state;
;; queued events cut by destruction are quiet drops rather than one error per
;; envelope.

(deftest destroy-frame-pending-drain-pins-no-op-contract
  (testing "queued events that drain AFTER destroy do not mutate state,
            do not throw, and are dropped quietly"
    (rf/make-frame {:id :rf2-dpny/worker :doc "rf2-dpny race-pin frame"})
    (let [side-effects (atom 0)
          traces       (atom [])
          captured     (atom [])]
      (rf/reg-event :rf2-dpny/tick
                       (fn [{:keys [db]} _] (swap! side-effects inc) {:db db}))
      (rf/register-listener! :trace ::rf2-dpny (fn [ev] (swap! traces conj ev)))

      ;; Capture next-tick: dispatches schedule a drain, the drain
      ;; thunk goes into the atom instead of executing.
      (with-redefs [interop/next-tick (fn [f] (swap! captured conj f) nil)]
        (rf/dispatch [:rf2-dpny/tick] {:frame :rf2-dpny/worker})
        (rf/dispatch [:rf2-dpny/tick] {:frame :rf2-dpny/worker})
        ;; Two events are queued; neither has drained yet.
        (let [router (:router (frame/frame :rf2-dpny/worker))
              queue  (:queue @router)]
          (is (= 2 (count queue))
              "two events queued, deterministically un-drained")))

      ;; Destroy the frame BEFORE the captured ticks run.
      (rf/destroy-frame! :rf2-dpny/worker)
      (is (nil? (frame/frame :rf2-dpny/worker))
          "the frame is gone from the registry")

      ;; NOW run the captured ticks. Pre-fix: this would have raced
      ;; the destroy; post-fix the drain at drain! line ~261 sees a
      ;; nil frame and emits the trace. The contract is "no-op + trace".
      (doseq [tick @captured]
        (is (nil? (try (tick) nil
                       (catch Throwable e e)))
            "the captured drain ran without throwing"))

      (rf/unregister-listener! :trace ::rf2-dpny)

      ;; (a) State did NOT mutate — the handler never ran.
      (is (= 0 @side-effects)
          "the :rf2-dpny/tick handler did NOT increment the counter")

      ;; (b) The drain-onto-destroyed-frame path is a SILENT no-op:
      ;;     `drain!` short-circuits via `(when frame-record ...)`
      ;;     without emitting any trace (router.cljc:300). The
      ;;     :rf.error/frame-destroyed trace is emitted by dispatch! /
      ;;     dispatch-sync!, NOT by the drain itself — events queued
      ;;     before destroy already passed the dispatch check.
      ;;     Pre-destroy queued events become quiet drops. Pin that.
      (let [destroyed-traces (filter #(= :rf.error/frame-destroyed (:operation %))
                                     @traces)]
        (is (zero? (count destroyed-traces))
            "the drain itself does NOT emit :rf.error/frame-destroyed —
             that trace fires at dispatch time, not at drain time"))

      ;; (c) Defence-in-depth: a subsequent dispatch AFTER destroy DOES
      ;;     trace :rf.error/frame-destroyed (the public contract for
      ;;     dispatching to a destroyed frame).
      (let [after-traces (atom [])]
        (rf/register-listener! :trace ::rf2-dpny-after (fn [ev] (swap! after-traces conj ev)))
        (rf/dispatch-sync [:rf2-dpny/tick] {:frame :rf2-dpny/worker})
        (rf/unregister-listener! :trace ::rf2-dpny-after)
        ;; rf2-d2841 — dev-instrumentation arm (see ns docstring). NOTE that
        ;; (b) above is a NEGATIVE trace assertion and is therefore vacuous
        ;; under the gate; the no-op contract this deftest is named for is
        ;; carried by (a), (d) and (e), all posture-independent.
        (when interop/debug-enabled?
          (is (some #(= :rf.error/frame-destroyed (:operation %)) @after-traces)
              "post-destroy dispatch (not the drain) traces :rf.error/frame-destroyed")))

      ;; (d) The handler still never ran.
      (is (= 0 @side-effects)
          "no handler ran across the entire lifecycle")

      ;; (e) Frame stays gone.
      (is (nil? (frame/frame :rf2-dpny/worker))
          "the frame did not somehow re-materialise from the post-destroy drain"))))

(deftest destroy-claim-prevents-cold-release-from-running-queued-handlers
  (testing "the destroy claim is the queued-work cutoff, before lifecycle-dead publication"
    (let [frame-id       :destroy-claim/cold-release
          parent-runs    (atom 0)
          child-runs     (atom 0)
          user-effects   (atom 0)
          captured-ticks (atom [])
          gap-observation (atom nil)
          original-hook  (late-bind/get-fn :ui/on-frame-destroyed!)]
      (rf/make-frame {:id frame-id})
      (rf/reg-fx :destroy-claim/effect
        (fn [_ _] (swap! user-effects inc)))
      (rf/reg-event :destroy-claim/child
        (fn [{:keys [db]} _]
          (swap! child-runs inc)
          {:db (assoc db :child-ran? true)}))
      (rf/reg-event :destroy-claim/queued
        (fn [{:keys [db]} _]
          (swap! parent-runs inc)
          {:db (assoc db :queued-handler-ran? true)
           :fx [[:destroy-claim/effect :payload]
                [:dispatch [:destroy-claim/child]]]}))

      ;; Queue two events while suppressing their first scheduled drain. The
      ;; destroy claim must both suppress a replacement kick from its COLD
      ;; serialized release and fence this not-yet-fired original attempt.
      (with-redefs [interop/next-tick
                    (fn [f]
                      (swap! captured-ticks conj f)
                      nil)]
        (rf/dispatch [:destroy-claim/queued] {:frame frame-id})
        (rf/dispatch [:destroy-claim/queued] {:frame frame-id}))
      (is (= 1 (count @captured-ticks))
          "the initial dispatch pair scheduled one drain attempt")
      (is (= 2 (count (:queue @(:router (frame/frame frame-id)))))
          "both events are queued before destroy starts")

      ;; `:ui/on-frame-destroyed!` runs after claim-frame-destroy! has returned
      ;; (and therefore after the cold lock release) but before
      ;; mark-frame-destroyed!. Its FIFO executor barrier forces the captured
      ;; drain to finish inside that exact claim -> lifecycle-dead window.
      (try
        (late-bind/set-fn!
          :ui/on-frame-destroyed!
          (fn [id]
            (when original-hook
              (original-hook id))
            (when (= frame-id id)
              ;; Submit the drain attempt captured at enqueue time only AFTER
              ;; claim-frame-destroy! has released the cold lock. This covers
              ;; the path where that attempt had not yet CAS-lost when claim
              ;; completed; the claim predicate, not scheduling luck, must
              ;; prevent it from invoking either queued handler.
              ;; `next-tick` deliberately conveys dynamic bindings through
              ;; `bound-fn`. Scheduling from this teardown hook therefore also
              ;; proves that owner privilege is ACTUAL host-thread identity,
              ;; not the conveyed `*destroying-frame-id*` value.
              (interop/next-tick (first @captured-ticks))
              (executor-barrier!)
              (let [record (get @frame/frames frame-id)]
                (reset! gap-observation
                        {:destroyed? (-> record :lifecycle :destroyed?)
                         :queued     (count (:queue @(:router record)))})))))
        (rf/destroy-frame! frame-id)
        (finally
          (late-bind/set-fn! :ui/on-frame-destroyed! original-hook)))

      (is (= 0 @parent-runs)
          "queued handlers are no-op as soon as this incarnation's destroy is claimed")
      (is (= 0 @user-effects)
          "a dropped queued handler cannot emit user effects")
      (is (= 0 @child-runs)
          "a dropped queued handler cannot enqueue or run child dispatches")
      (is (= {:destroyed? false :queued 0} @gap-observation)
          "the queued drain was disposed while the frame was claimed but still lifecycle-live")
      (is (nil? (frame/frame frame-id))
          "destroy still completes after the claim-window barrier"))))

(deftest post-claim-dispatch-enters-real-queue-but-never-executes
  (testing "ordinary work submitted after claim may queue but cannot run in the claim-to-dead gap"
    (let [frame-id        :destroy-claim/post-claim-dispatch
          parent-runs     (atom 0)
          child-runs      (atom 0)
          user-effects    (atom 0)
          captured-ticks  (atom [])
          traces          (atom [])
          gap-observation (atom nil)
          original-hook   (late-bind/get-fn :ui/on-frame-destroyed!)]
      (rf/make-frame {:id frame-id})
      (rf/reg-fx :destroy-claim/post-claim-effect
        (fn [_ _] (swap! user-effects inc)))
      (rf/reg-event :destroy-claim/post-claim-child
        (fn [{:keys [db]} _]
          (swap! child-runs inc)
          {:db (assoc db :post-claim-child-ran? true)}))
      (rf/reg-event :destroy-claim/post-claim-parent
        (fn [{:keys [db]} _]
          (swap! parent-runs inc)
          {:db (assoc db :post-claim-parent-ran? true)
           :fx [[:destroy-claim/post-claim-effect :payload]
                [:dispatch [:destroy-claim/post-claim-child]]]}))

      (rf/register-listener! :trace ::post-claim-combined-count
                             (fn [ev] (swap! traces conj ev)))
      ;; One ordinary envelope exists before the claim. Capturing its scheduled
      ;; drain keeps it pending until `claim-frame-destroy!` atomically cuts it.
      (with-redefs [interop/next-tick
                    (fn [f]
                      (swap! captured-ticks conj f)
                      nil)]
        (rf/dispatch [:destroy-claim/post-claim-parent] {:frame frame-id}))

      (try
        (late-bind/set-fn!
          :ui/on-frame-destroyed!
          (fn [id]
            (when original-hook
              (original-hook id))
            (when (= frame-id id)
              ;; This hook runs strictly AFTER `claim-frame-destroy!` returns
              ;; and BEFORE `mark-frame-destroyed!` flips lifecycle liveness.
              ;; Submit genuinely NEW ordinary work here, capture its scheduled
              ;; drain, then force that drain through the real bound-fn executor
              ;; before allowing teardown to advance.
              (with-redefs [interop/next-tick
                            (fn [f]
                              (swap! captured-ticks conj f)
                              nil)]
                (rf/dispatch [:destroy-claim/post-claim-parent]
                             {:frame frame-id}))
              (is (= 2 (count @captured-ticks))
                  "one pre-claim and one post-claim submit each scheduled a drain")
              (is (= 1 (count (:queue @(:router (get @frame/frames frame-id)))))
                  "the ordinary envelope was enqueued after the claim cutoff")
              ;; Execute BOTH already-captured scheduler callbacks separately.
              ;; Claim cleared :scheduled?, so the post-claim submit captured a
              ;; second callback. The first callback atomically consumes the
              ;; claim-time count and compare-marks this router generation; the
              ;; second still clears rejected work but cannot emit again. These
              ;; internal-state assertions pin the algorithm shown in Spec 002
              ;; §Drain-loop pseudocode, not merely its eventual trace count.
              (let [[first-tick second-tick] @captured-ticks]
                (interop/next-tick first-tick)
                (executor-barrier!)
                (let [router-state @(:router (get @frame/frames frame-id))
                      interrupts   (filterv
                                     #(= :rf.frame/drain-interrupted
                                         (:operation %))
                                     @traces)]
                  ;; These two are ROUTER STATE — production-real, and they are
                  ;; the algorithm this deftest pins (Spec 002 §Drain-loop
                  ;; pseudocode). They stay outside the arm.
                  (is (true? (:destroy-claim-report-emitted? router-state))
                      "the first callback compare-marks this router generation")
                  (is (not (contains? router-state
                                      :destroy-claim-dropped-count))
                      "the first callback atomically consumes claim-time evidence")
                  ;; rf2-d2841 — dev-instrumentation arm (see ns docstring).
                  (when interop/debug-enabled?
                    (is (= 1 (count interrupts))
                        "the compare/mark winner emits the one combined report")))
                (interop/next-tick second-tick)
                (executor-barrier!)
                ;; rf2-d2841 — dev-instrumentation arm (see ns docstring).
                (when interop/debug-enabled?
                  (is (= 1 (count (filter
                                    #(= :rf.frame/drain-interrupted
                                        (:operation %))
                                    @traces)))
                      "the second captured callback observes the mark and emits nothing")))
              (let [record (get @frame/frames frame-id)]
                (reset! gap-observation
                        {:destroyed? (-> record :lifecycle :destroyed?)
                         :queued     (count (:queue @(:router record)))})))))
        (rf/destroy-frame! frame-id)
        (finally
          (late-bind/set-fn! :ui/on-frame-destroyed! original-hook)
          (rf/unregister-listener! :trace ::post-claim-combined-count)))

      (is (zero? @parent-runs)
          "the post-claim ordinary handler never runs")
      (is (zero? @user-effects)
          "a drain-dropped post-claim handler cannot emit user effects")
      (is (zero? @child-runs)
          "a drain-dropped post-claim handler cannot enqueue or run children")
      (is (= {:destroyed? false :queued 0} @gap-observation)
          "the claim predicate emptied the real queue while lifecycle was still live")
      ;; rf2-d2841 — dev-instrumentation arm (see ns docstring). The
      ;; "never executes" half of this deftest's name is the four zero-count
      ;; assertions plus `@gap-observation` above, all posture-independent.
      (when interop/debug-enabled?
        (let [interrupts (filterv #(= :rf.frame/drain-interrupted (:operation %))
                                  @traces)]
          (is (= 1 (count interrupts))
              "the observing drain emits exactly one interruption trace")
          (is (= 2 (get-in (first interrupts) [:tags :dropped-count]))
              "dropped-count combines one claim-time and one check-time removal")))
      (is (nil? (frame/frame frame-id)) "destroy completes after the gap proof"))))

(deftest on-destroy-cascade-is-isolated-from-preclaim-and-bound-work
  (testing "only the cleanup seed and its queued descendants run after claim"
    (let [frame-id        :destroy-claim/isolated-cleanup
          ordinary-runs   (atom 0)
          cleanup-runs    (atom 0)
          child-runs      (atom 0)
          effect-runs     (atom 0)
          escaped-runs    (atom 0)
          nested-sync-runs (atom 0)
          captured-ticks  (atom [])]
      (rf/make-frame {:id frame-id
                      :on-destroy [:destroy-claim/cleanup]})
      (rf/reg-event :destroy-claim/ordinary
        (fn [{:keys [db]} _]
          (swap! ordinary-runs inc)
          {:db (assoc db :ordinary-ran? true)}))
      (rf/reg-event :destroy-claim/escaped
        (fn [{:keys [db]} _]
          (swap! escaped-runs inc)
          {:db (assoc db :escaped-ran? true)}))
      (rf/reg-event :destroy-claim/nested-sync
        (fn [{:keys [db]} _]
          (swap! nested-sync-runs inc)
          {:db (assoc db :nested-sync-ran? true)}))
      (rf/reg-event :destroy-claim/cleanup-child
        (fn [{:keys [db]} _]
          (swap! child-runs inc)
          {:db (assoc db :cleanup-child-ran? true)}))
      (rf/reg-fx :destroy-claim/cleanup-effect
        (fn [_ _] (swap! effect-runs inc)))
      (rf/reg-event :destroy-claim/cleanup
        (fn [{:keys [db]} _]
          (swap! cleanup-runs inc)
          ;; `next-tick` captures dynamic bindings with `bound-fn` on JVM.
          ;; Force the callback to run while the claim is still live: actual
          ;; host-thread identity must keep it out of the private cleanup queue.
          (interop/next-tick
            #(rf/dispatch [:destroy-claim/escaped] {:frame frame-id}))
          (executor-barrier!)
          ;; The internal teardown entry is narrow; it must not turn generic
          ;; nested dispatch-sync into a legal handler operation.
          (rf/dispatch-sync [:destroy-claim/nested-sync] {:frame frame-id})
          {:db db
           :fx [[:destroy-claim/cleanup-effect :payload]
                [:dispatch [:destroy-claim/cleanup-child]]]}))

      ;; Hold one ordinary event in the real router. It linearizes before the
      ;; destroy claim but must never sit behind the cleanup seed.
      (with-redefs [interop/next-tick
                    (fn [f]
                      (swap! captured-ticks conj f)
                      nil)]
        (rf/dispatch [:destroy-claim/ordinary] {:frame frame-id}))
      (is (= 1 (count (:queue @(:router (frame/frame frame-id)))))
          "ordinary pre-claim work is waiting on the real router")

      (rf/destroy-frame! frame-id)
      ;; Flush both the bound callback's real-router drain and the original
      ;; pre-claim drain attempt. Neither may invoke application work.
      (doseq [tick @captured-ticks] (tick))
      (executor-barrier!)

      (is (= 1 @cleanup-runs) ":on-destroy seed runs exactly once")
      (is (= 1 @child-runs) "queued cleanup child runs on the isolated cascade")
      (is (= 1 @effect-runs) "cleanup effects execute normally")
      (is (zero? @ordinary-runs) "pre-claim ordinary work is discarded")
      (is (zero? @escaped-runs)
          "bound-fn executor work cannot inherit teardown authority")
      (is (zero? @nested-sync-runs)
          "generic nested dispatch-sync remains rejected during cleanup")
      (is (nil? (frame/frame frame-id)) "teardown completes"))))

(deftest destroy-from-active-handler-runs-cleanup-and-cuts-off-old-queue
  (testing "self-destroy has one private cleanup cascade inside the active drain"
    (let [frame-id      :destroy-claim/inside-handler
          cleanup-runs  (atom 0)
          ordinary-runs (atom 0)
          captured-ticks (atom [])]
      (rf/make-frame {:id frame-id
                      :on-destroy [:destroy-claim/inside-cleanup]})
      (rf/reg-event :destroy-claim/inside-cleanup
        (fn [_ _]
          (swap! cleanup-runs inc)
          {}))
      (rf/reg-event :destroy-claim/inside-ordinary
        (fn [_ _]
          (swap! ordinary-runs inc)
          {}))
      (rf/reg-event :destroy-claim/self-destroy
        (fn [_ _]
          (frame/destroy-frame! frame-id)
          {}))

      ;; Queue old work without letting its async drain start, then prepend a
      ;; synchronous destroying event. The handler already owns the real
      ;; router's drain serialization when destroy-frame! enters.
      (with-redefs [interop/next-tick
                    (fn [f]
                      (swap! captured-ticks conj f)
                      nil)]
        (rf/dispatch [:destroy-claim/inside-ordinary] {:frame frame-id}))
      (rf/dispatch-sync [:destroy-claim/self-destroy] {:frame frame-id})
      (doseq [tick @captured-ticks] (tick))

      (is (= 1 @cleanup-runs)
          "the internal cleanup seed runs despite generic nested-sync rejection")
      (is (zero? @ordinary-runs)
          "the pre-existing queue is cut off by the handler's destroy claim")
      (is (nil? (frame/frame frame-id)) "self-destroy completes exactly once"))))

(deftest replace-container-on-destroyed-frame-does-not-npe
  ;; Tighter reproducer that hits the adapter guard directly via the
  ;; live runtime. The router's per-event :db commit reads the frame's
  ;; app-db container right before writing — and if the frame was
  ;; destroyed mid-drain, app-db-container returns nil. We exercise that
  ;; exact shape by reading app-db-container AFTER destroy and feeding the
  ;; nil container straight into replace-container!.
  (testing "frame/app-db-container on a destroyed frame is nil; replace-container! handles it"
    (let [recorded (atom [])]
      (rf/register-listener! :trace ::rec (fn [ev] (swap! recorded conj ev)))
      (rf/make-frame {:id :race/destroyed-mid-write})
      (frame/destroy-frame! :race/destroyed-mid-write)
      (let [container (frame/app-db-container :race/destroyed-mid-write)]
        (is (nil? container)
            "app-db-container on a destroyed frame returns nil — the precondition for the rf2-ft2b NPE")
        ;; This is the exact call shape from router.cljc's :db commit.
        ;; Pre-fix: NPE. Post-fix: no-op + always-on error trace.
        (is (nil? (adapter/replace-container! container {:would :have :npe'd true}))
            "writing through the nil container is a documented no-op"))
      ;; rf2-d2841 — dev-instrumentation arm (see ns docstring). The rf2-ft2b
      ;; NPE this deftest reproduces is closed by the two assertions above,
      ;; both posture-independent.
      (when interop/debug-enabled?
        (let [errs (filterv (fn [ev]
                              (and (= :error (:op-type ev))
                                   (= :rf.error/write-after-destroy
                                      (:operation ev))))
                            @recorded)]
          (is (pos? (count errs))
              ":rf.error/write-after-destroy fired for the post-destroy write"))))))

;; ---- rf2-2e6k: frame-ids / frame-meta re-export round-trip ----------------
;;
;; Per test-coverage-review-2026-05-12 P3-18: frame-lifecycle exercises read
;; via direct atom reads. The public re-exports `rf/frame-ids` and
;; `rf/frame-meta` (core.cljc:1160-1161) are the documented introspection
;; surface; this test pins their round-trip contract.

(deftest frame-ids-round-trip
  (testing "(rf/frame-ids) returns every registered, non-destroyed frame id"
    ;; EP-0002 (rf2-5q7um6): `:rf/default` is an ORDINARY id — `init!` no
    ;; longer creates it, and `frame-ids` is a frame-neutral enumeration
    ;; surface (it reports exactly the registered frames, never synthesises
    ;; a default). It appears here only because this test registers it
    ;; explicitly, alongside the user frames — proving the round-trip
    ;; without leaning on a runtime-synthesised floor.
    (rf/make-frame {:id :rf/default :doc "explicitly-registered ordinary default frame"})
    (rf/make-frame {:id :tenants/acme :doc "acme tenant"  :preset :default})
    (rf/make-frame {:id :tenants/widgets :doc "widgets co"   :preset :default})
    (let [ids (rf/frame-ids)]
      (is (set? ids) "frame-ids returns a set")
      (is (contains? ids :rf/default)
          ":rf/default appears because it was EXPLICITLY registered (no runtime floor)")
      (is (contains? ids :tenants/acme))
      (is (contains? ids :tenants/widgets))
      ;; Sanity: a never-registered id is absent.
      (is (not (contains? ids :tenants/nonexistent))
          "frame-ids excludes ids that were never registered")))
  (testing "frame-ids does NOT synthesise :rf/default when it was never registered"
    ;; Fresh slate (the :each fixture already reset frames + init!'d without
    ;; creating :rf/default). Register only user frames.
    (reset! frame/frames {})
    (rf/init! plain-atom/adapter)
    (rf/make-frame {:id :tenants/solo :doc "sole user frame"})
    (let [ids (rf/frame-ids)]
      (is (not (contains? ids :rf/default))
          "EP-0002: :rf/default is not present unless explicitly registered")
      (is (contains? ids :tenants/solo)))))

(deftest frame-meta-round-trip
  (testing "(rf/frame-meta id) returns the canonical flat :rf/frame-meta shape
            per Spec-Schemas §:rf/frame-meta — user-supplied metadata,
            preset-expansion keys, and lifecycle fields all at the top level"
    (rf/make-frame {:id :tenants/acme :doc          "acme tenant"
                    :preset       :default
                    :fx-overrides {:rf.http/managed
                                   :rf.http/managed-canned-success}})
    (let [m (rf/frame-meta :tenants/acme)]
      (is (map? m) "frame-meta returns a map")
      (is (= :tenants/acme (:id m))
          ":id reflects the registered frame id")
      (is (= "acme tenant" (:doc m))
          "user-supplied :doc is at the top level of the flat shape")
      (is (= :default (:preset m))
          ":preset is preserved verbatim per Spec 002 §Frame presets")
      (is (= {:rf.http/managed :rf.http/managed-canned-success}
             (:fx-overrides m))
          ":fx-overrides is at the top level of the flat shape")
      (is (number? (:created-at m))
          ":created-at is the host clock at make-frame time — flat, not nested
           under any :lifecycle sub-map")
      (is (false? (:destroyed? m))
          ":destroyed? is at the top level of the flat shape")
      (is (nil? (:config m))
          "no internal :config grouping leaks through — flat shape only")
      (is (nil? (:lifecycle m))
          "no internal :lifecycle grouping leaks through — flat shape only"))))

(deftest frame-meta-preset-expansion-flat-shape
  (testing "(rf/frame-meta id) on a preset frame returns the preset expansion
            merged flat per Spec 002 §Expansion algorithm worked example:
              {:preset       :test
               :fx-overrides {:rf.http/managed :rf.http/managed-canned-success}
               :drain-depth  100}"
    (rf/make-frame {:id :test/auth-flow :preset :test})
    (let [m (rf/frame-meta :test/auth-flow)]
      (is (= :test (:preset m))
          ":preset key preserved verbatim")
      (is (= {:rf.http/managed :rf.http/managed-canned-success}
             (:fx-overrides m))
          ":test preset's :fx-overrides expansion lifted to top level")
      (is (= 100 (:drain-depth m))
          ":test preset's :drain-depth expansion lifted to top level")
      (is (= :test/auth-flow (:id m))
          ":id reflects the registered frame id"))))

(deftest frame-meta-nonexistent-is-nil
  (testing "(rf/frame-meta id) on an unregistered id returns nil cleanly
            (no throw, no warning)"
    (is (nil? (rf/frame-meta :no-such-frame))
        "missing frame-id → nil (frame-meta delegates to frame which gates
         on the registry)")
    ;; A destroyed frame is also indistinguishable from never-registered
    ;; per the documented surface — `frame` returns nil for destroyed.
    (rf/make-frame {:id :short-lived :doc "will be destroyed"})
    (rf/destroy-frame! :short-lived)
    (is (nil? (rf/frame-meta :short-lived))
        "frame-meta on a destroyed frame returns nil (no resurrection)")))

;; ---- rf2-mwrh: full-reset (destroy-frame! + make-frame) preservation contract
;;
;; Per test-coverage-review-2026-05-12 P3-27 (originally pinning the now-
;; retired `rf/reset-frame!` — rf2-lxwpob deleted that verb since it was
;; nothing but `destroy-frame! followed by make-frame with the same config`,
;; a composition with a single caller, not a surface): this test pins the
;; contract that flows from that composition:
;;   - app-db is reset (fresh container).
;;   - sub-cache is cleared (sub re-registers against the fresh frame).
;;   - The config metadata is preserved (same :doc, same :initial-events, etc.)
;;     because the caller re-supplies it.
;;   - :initial-events re-runs (the new frame is freshly booted; EP-0027).
;;   - The frame is still queryable (not in the destroyed state).

(deftest reset-frame-preserves-config-resets-app-db
  (testing "destroy-frame! + re-make-frame with the SAME config: app-db is reset,
            config metadata is preserved, :initial-events re-runs, frame remains
            queryable (EP-0027)"
    (let [boot-count (atom 0)]
      (rf/reg-event :seed
        (fn [{:keys [db]} [_ payload]]
          (swap! boot-count inc)
          {:db {:booted? true :payload payload :extra :seeded}}))
      (let [config {:doc            "frame with initial-events"
                    :initial-events [[:seed {:hello "world"}]]
                    :fx-overrides {:custom :value}}]
        (rf/make-frame (assoc config :id :app/main))
        ;; First setup dispatch increments the counter.
        (is (= 1 @boot-count) ":initial-events ran once at make-frame time")
        (is (= {:booted? true :payload {:hello "world"} :extra :seeded}
               (rf/app-db-value :app/main))
            "app-db reflects the setup commit")

        ;; Mutate app-db: a subsequent event extends the value.
        (rf/reg-event :extend (fn [{:keys [db]} _] {:db (assoc db :runtime-write? true)}))
        (rf/dispatch-sync [:extend] {:frame :app/main})
        (is (true? (:runtime-write? (rf/app-db-value :app/main)))
            "mutation is visible before the reset")

        ;; Capture the original frame record so we can compare.
        (let [orig-record    (frame/frame :app/main)
              orig-app-db    (:app-db orig-record)
              orig-sub-cache (:sub-cache orig-record)
              orig-router    (:router orig-record)]

          ;; Reset — destroy + re-make-frame with the SAME config.
          (rf/destroy-frame! :app/main)
          (rf/make-frame (assoc config :id :app/main))

          ;; After reset: the frame is queryable.
          (let [new-record (frame/frame :app/main)]
            (is (some? new-record)
                "the frame still exists post-reset (not destroyed)")
            ;; Config metadata preserved: same :doc, same :initial-events,
            ;; same :fx-overrides.
            (is (= "frame with initial-events" (get-in new-record [:config :doc]))
                ":config :doc preserved across reset")
            (is (= [[:seed {:hello "world"}]]
                   (get-in new-record [:config :initial-events]))
                ":config :initial-events preserved across reset")
            (is (= {:custom :value} (get-in new-record [:config :fx-overrides]))
                ":config :fx-overrides preserved across reset")
            ;; The slot identity-replaceable parts are FRESH containers.
            (is (not (identical? orig-app-db (:app-db new-record)))
                "app-db container is a fresh allocation post-reset")
            (is (not (identical? orig-sub-cache (:sub-cache new-record)))
                "sub-cache atom is fresh post-reset")
            (is (not (identical? orig-router (:router new-record)))
                "router atom is fresh post-reset"))

          ;; :initial-events re-ran on the fresh frame: counter incremented.
          (is (= 2 @boot-count)
              ":initial-events re-dispatches against the freshly-registered frame")

          ;; The runtime mutation is gone — fresh app-db reflects only
          ;; the setup's commit, not the prior :extend.
          (is (= {:booted? true :payload {:hello "world"} :extra :seeded}
                 (rf/app-db-value :app/main))
              "app-db is reset to the :initial-events state; runtime writes are gone")
          (is (nil? (:runtime-write? (rf/app-db-value :app/main)))
              "the :extend handler's write is absent — reset wiped runtime state"))))))

;; ---- rf2-r1ciy: :on-destroy handler throw semantics ----------------------
;;
;; Per Spec 002 §Destroy — `:on-destroy` handler throw semantics: a throw
;; from the user-supplied `:on-destroy` event handler MUST NOT abort
;; teardown. The runtime catches, emits :rf.error/on-destroy-handler-
;; exception, and continues the destroy recipe end-to-end. Mike decision
;; 2026-05-17 00:18 AUSEST (option b: catch + emit trace + continue).

(deftest on-destroy-throw-does-not-abort-teardown
  (testing "a throwing :on-destroy event still results in a fully destroyed
            frame — machine cascade, sub-cache, :rf.frame/destroyed,
            registry dissoc all run regardless"
    (rf/make-frame {:id :throwy/worker :doc       "rf2-r1ciy throwy on-destroy frame"
                    :on-destroy [:throwy/blow-up]})
    ;; Handler that always throws.
    (rf/reg-event :throwy/blow-up
      (fn [{:keys [db]} _]
        {:db (throw (ex-info ":throwy/intentional" {:purpose :test-fixture}))}))
    ;; Pin a live subscription so we can verify sub-cache disposal still ran.
    (rf/reg-event :throwy/seed (fn [{:keys [db]} _] {:db {:n 42}}))
    (rf/reg-sub :throwy/n (fn [db _] (:n db)))
    (rf/dispatch-sync [:throwy/seed] {:frame :throwy/worker})
    (let [pinned         (rf/subscribe [:throwy/n] {:frame :throwy/worker})
          dispose-fired  (atom 0)
          traces         (atom [])]
      (is (= 42 @pinned) "sub reads the seeded value before destroy")
      (re-frame.interop/add-on-dispose! pinned
                                        (fn [] (swap! dispose-fired inc)))
      (rf/register-listener! :trace ::rf2-r1ciy (fn [ev] (swap! traces conj ev)))

      ;; Destroy MUST NOT propagate the throw.
      (is (nil? (try (rf/destroy-frame! :throwy/worker)
                     (catch Throwable e e)))
          "destroy-frame! returns nil even though :on-destroy threw")

      (rf/unregister-listener! :trace ::rf2-r1ciy)

      ;; (a) The :rf.error/on-destroy-handler-exception trace fired.
      ;; rf2-d2841 — dev-instrumentation arm (see ns docstring). "Does not
      ;; abort teardown" is (b), (c) and the non-propagating `destroy-frame!`
      ;; above — all posture-independent; the REPORT of the swallowed throw is
      ;; a dev diagnostic.
      (when interop/debug-enabled?
        (let [errs (filterv (fn [ev]
                              (and (= :error (:op-type ev))
                                   (= :rf.error/on-destroy-handler-exception
                                      (:operation ev))))
                            @traces)]
          (is (= 1 (count errs))
              "exactly one :rf.error/on-destroy-handler-exception trace")
          (let [ev (first errs)
                tags (:tags ev)]
            (is (= :throwy/worker (:frame tags))
                ":tags carries the destroyed frame id")
            (is (= [:throwy/blow-up] (:event tags))
                ":tags carries the :on-destroy event vector")
            (is (some? (:exception tags))
                ":tags carries the captured exception")
            (is (= :fire-on-destroy-event! (:where tags))
                ":tags carries the call-site :where marker"))))

      ;; (b) The frame is fully gone from the registry.
      (is (nil? (frame/frame :throwy/worker))
          "destroy-frame! removed the frame from the registry despite the throw")
      (is (not (contains? @frame/frames :throwy/worker))
          "the frame entry is dissoc'd from the frames atom")

      ;; (c) The sub-cache was disposed (on-dispose fired).
      (is (= 1 @dispose-fired)
          "sub-cache disposal ran — on-dispose hook fired once")

      ;; (d) :rf.frame/destroyed trace was emitted (post-throw step still ran).
      ;; rf2-d2841 — dev-instrumentation arm (see ns docstring).
      (when interop/debug-enabled?
        (is (some #(= :rf.frame/destroyed (:operation %)) @traces)
            ":rf.frame/destroyed trace was emitted — teardown continued past the throw")))))

(deftest on-destroy-throw-then-fresh-make-frame-clean
  (testing "after a throwy destroy, the same frame id can be re-registered
            cleanly — the in-flight guard is cleared even on the exception path"
    (rf/make-frame {:id :throwy/resurrected :on-destroy [:throwy/blow-up-2]})
    (rf/reg-event :throwy/blow-up-2
      (fn [{:keys [db]} _] {:db (throw (ex-info ":throwy/second-blow" {}))}))
    (rf/destroy-frame! :throwy/resurrected)
    (is (nil? (frame/frame :throwy/resurrected))
        "original frame destroyed (despite the throw)")
    ;; A fresh registration after the throwy destroy must work.
    (rf/make-frame {:id :throwy/resurrected :doc "post-resurrection"})
    (is (some? (frame/frame :throwy/resurrected))
        "re-registration succeeds — the in-flight guard cleared in finally")
    ;; And a fresh destroy (no throw this time) works normally.
    (rf/destroy-frame! :throwy/resurrected)
    (is (nil? (frame/frame :throwy/resurrected))
        "second destroy completes normally")))

;; ---- rf2-r1ciy: re-entrant destroy-frame! is a silent no-op --------------
;;
;; Per Spec 002 §Destroy — re-entrant `destroy-frame!` is a silent no-op:
;; a call to `destroy-frame!` from inside the same id's `:on-destroy`
;; handler (or any code reachable from it) MUST short-circuit silently —
;; the outer destroy is already in flight; re-running the recipe would
;; corrupt half-torn-down state.

(deftest re-entrant-destroy-from-on-destroy-is-silent-noop
  (testing "destroy-frame! called from within :on-destroy must NOT recurse —
            :on-destroy fires exactly once, teardown completes once"
    (let [on-destroy-count (atom 0)
          traces           (atom [])]
      (rf/make-frame {:id :reent/worker :doc        "rf2-r1ciy re-entrancy frame"
                      :on-destroy [:reent/cleanup]})
      ;; The :on-destroy handler itself calls destroy-frame! on its own
      ;; frame. Without the re-entrancy guard this would recurse
      ;; indefinitely (or until a stack overflow).
      (rf/reg-event :reent/cleanup
        (fn [_ _]
          (swap! on-destroy-count inc)
          (frame/destroy-frame! :reent/worker)
          {}))
      (rf/register-listener! :trace ::reent (fn [ev] (swap! traces conj ev)))
      (rf/destroy-frame! :reent/worker)
      (rf/unregister-listener! :trace ::reent)

      ;; (a) :on-destroy ran exactly once — the re-entrant inner call was a no-op.
      (is (= 1 @on-destroy-count)
          ":on-destroy fired once; the re-entrant destroy-frame! did not re-run it")

      ;; (b) Exactly one :rf.frame/destroyed trace emitted.
      ;; (c) No :rf.error/on-destroy-handler-exception (the inner call
      ;;     was a silent no-op, not a throw).
      ;; rf2-d2841 — dev-instrumentation arm (see ns docstring). The
      ;; re-entrancy guard itself is (a) above, posture-independent; (c) is a
      ;; NEGATIVE trace assertion and would pass vacuously under the gate,
      ;; which is why it sits inside the arm rather than outside it.
      (when interop/debug-enabled?
        (let [destroyed-traces (filter #(= :rf.frame/destroyed (:operation %)) @traces)]
          (is (= 1 (count destroyed-traces))
              "exactly one :rf.frame/destroyed trace — teardown ran end-to-end once"))
        (let [errs (filter #(= :rf.error/on-destroy-handler-exception (:operation %))
                           @traces)]
          (is (zero? (count errs))
              "re-entrant no-op is silent — no error trace")))

      ;; (d) The frame is gone.
      (is (nil? (frame/frame :reent/worker))
          "the frame is fully destroyed"))))

(deftest re-entrant-destroy-from-different-id-still-runs
  (testing "destroy-frame! for frame B from within frame A's :on-destroy
            still runs B's teardown — the in-flight guard is per-id, not global"
    (rf/make-frame {:id :reent.a/worker :on-destroy [:reent.a/cleanup]})
    (rf/make-frame {:id :reent.b/worker})
    (let [b-still-alive-during-a (atom nil)]
      (rf/reg-event :reent.a/cleanup
        (fn [_ _]
          ;; Sanity: B is alive at the start of A's :on-destroy.
          (reset! b-still-alive-during-a (some? (frame/frame :reent.b/worker)))
          (frame/destroy-frame! :reent.b/worker)
          {}))
      (rf/destroy-frame! :reent.a/worker)
      (is (true? @b-still-alive-during-a)
          "B was alive when A's :on-destroy started")
      ;; Both A and B are gone — the guard is per-id, not a global lock.
      (is (nil? (frame/frame :reent.a/worker))
          "A is fully destroyed")
      (is (nil? (frame/frame :reent.b/worker))
          "B was successfully destroyed from inside A's :on-destroy"))))

;; ---- rf2-moftbs — exact-incarnation cleanup via the frame VALUE ------------
;;
;; A fresh `make-frame` VALUE carries EXACT incarnation authority
;; (`:rf.frame/incarnation-token` = the installed `:drain-lock`). The one-arg
;; `destroy-frame!` of that value is incarnation-EXACT: it destroys ONLY the
;; incarnation the value names, never a same-id successor reseated in between.
;; A frame-id KEYWORD stays address-directed (destroys the current incarnation).

(deftest make-frame-value-carries-exact-incarnation-token
  (testing "the returned frame VALUE carries the live incarnation's token"
    (let [va (rf/make-frame {:id :inc/tok :doc "A"})]
      (is (frame/frame-value? va) "make-frame returns a frame VALUE")
      (is (some? (frame/frame-value-incarnation-token va))
          "the value carries an incarnation token")
      (is (identical? (frame/frame-value-incarnation-token va)
                      (frame/frame-incarnation-token :inc/tok))
          "the carried token IS the live incarnation's :drain-lock")))
  (testing "an idempotent re-make-frame carries the SAME (preserved) token"
    (let [va (rf/make-frame {:id :inc/rereg :doc "A"})
          vb (rf/make-frame {:id :inc/rereg :doc "A-refreshed"})]
      (is (identical? (frame/frame-value-incarnation-token va)
                      (frame/frame-value-incarnation-token vb))
          "re-registration preserves :drain-lock, so both values name one incarnation"))))

(deftest destroy-value-N-does-not-tear-down-successor-N+1
  (testing "destroying a STALE frame VALUE (incarnation A) leaves a same-id
            successor B alive, while ordinary cleanup of B's own value releases it"
    (let [va      (rf/make-frame {:id :inc/x :doc "A"})
          a-token (frame/frame-value-incarnation-token va)]
      ;; Tear A down (address-directed) and re-seat a fresh incarnation B under
      ;; the SAME id — a distinct incarnation with a distinct token.
      (rf/destroy-frame! :inc/x)
      (is (nil? (frame/frame :inc/x)) "A is gone after its address-directed destroy")
      (let [vb      (rf/make-frame {:id :inc/x :doc "B"})
            b-token (frame/frame-value-incarnation-token vb)]
        (is (not (identical? a-token b-token)) "B is a distinct incarnation")
        ;; The OWNER of the stale value A exits: destroying value-A must NOT
        ;; reap B (the carried token is stale → the two-arg arity no-ops).
        (rf/destroy-frame! va)
        (is (some? (frame/frame :inc/x))
            "B survives — the stale value-A teardown did not destroy incarnation N+1")
        (is (identical? b-token (frame/frame-incarnation-token :inc/x))
            "the live incarnation is still exactly B")
        ;; Ordinary A-style cleanup still works: destroying B's OWN value fully
        ;; releases B.
        (rf/destroy-frame! vb)
        (is (nil? (frame/frame :inc/x))
            "B is fully released by destroying its own value")))))

(deftest destroy-value-fully-releases-its-own-incarnation
  (testing "destroying the LIVE value it was handed fully tears the frame down"
    (let [va (rf/make-frame {:id :inc/solo :doc "solo"})]
      (is (some? (frame/frame :inc/solo)) "the frame is live")
      (rf/destroy-frame! va)
      (is (nil? (frame/frame :inc/solo)) "destroying the value released the frame")
      (is (nil? (frame/frame-incarnation-token :inc/solo)) "no incarnation remains"))))

(deftest with-new-frame-exit-teardown-is-incarnation-exact
  (testing "with-new-frame binds make-frame's VALUE; a body that destroys A and
            reseats B under the same id must leave B alive on macro exit"
    (let [b-token (atom nil)]
      (rf/with-new-frame [f (rf/make-frame {:id :wnf/x :doc "A"})]
        ;; Inside the scope: tear A down and re-seat a fresh B under the same id.
        (rf/destroy-frame! :wnf/x)
        (rf/make-frame {:id :wnf/x :doc "B"})
        (reset! b-token (frame/frame-incarnation-token :wnf/x)))
      ;; The macro's `finally (destroy-frame! f)` consumed value-A's stale token
      ;; → a no-op against B.
      (is (some? (frame/frame :wnf/x))
          "B survives with-new-frame's exit teardown")
      (is (identical? @b-token (frame/frame-incarnation-token :wnf/x))
          "the surviving incarnation is exactly B")
      (rf/destroy-frame! :wnf/x)))
  (testing "ordinary with-new-frame cleanup releases exactly the incarnation it created"
    (rf/with-new-frame [f (rf/make-frame {:id :wnf/y :doc "solo"})]
      (is (some? (frame/frame :wnf/y)) "the frame is live inside the scope"))
    (is (nil? (frame/frame :wnf/y))
        "with-new-frame destroyed exactly the incarnation it created")))

(deftest keyword-destroy-remains-address-directed
  (testing "destroying by frame-id KEYWORD pins the CURRENT incarnation — the
            address-directed semantics rf2-moftbs deliberately preserves"
    (rf/make-frame {:id :addr/x :doc "A"})
    (let [a-token (frame/frame-incarnation-token :addr/x)]
      (rf/destroy-frame! :addr/x)                    ;; destroys A (the current one)
      (rf/make-frame {:id :addr/x :doc "B"})         ;; reseat B under the same id
      (let [b-token (frame/frame-incarnation-token :addr/x)]
        (is (not (identical? a-token b-token)) "B is a distinct incarnation")
        (rf/destroy-frame! :addr/x)                  ;; keyword → destroys CURRENT = B
        (is (nil? (frame/frame :addr/x))
            "a bare-id destroy targets whatever incarnation is currently live")))))

;; ---- frame-provider target triage (rf2-6muz2) ------------------------------
;; `require-frame-provider-target!` teaches ONE frame-target grammar: a frame-id
;; KEYWORD or the live frame VALUE `make-frame` returns. rf2-6muz2 retired the
;; keyword-only names this fn and its recovery id used to carry, which
;; contradicted the triage the fn actually performs. These pin the grammar and
;; the recovery id so neither can silently narrow back to keyword-only.

(deftest require-frame-provider-target-accepts-both-target-spellings
  (testing "a frame-id KEYWORD normalizes to itself"
    (rf/make-frame {:id :target/kw :doc "kw target"})
    (is (= :target/kw
           (frame/require-frame-provider-target! :target/kw 'test/where))
        "a keyword target passes through as the frame id"))
  (testing "a live frame VALUE normalizes to the SAME id the keyword names"
    (let [v (rf/make-frame {:id :target/val :doc "value target"})]
      (is (frame/frame-value? v) "make-frame returns a frame VALUE")
      (is (= :target/val
             (frame/require-frame-provider-target! v 'test/where))
          "a frame value normalizes to its frame id — the same grammar as the keyword"))))

(deftest require-frame-provider-target-rejects-neither-shape-with-supply-frame-target
  (testing "a non-nil value that is neither a keyword nor a frame value throws
            :rf.error/bad-frame-provider-arg carrying :recovery :supply-frame-target"
    (doseq [bad ["app" 7 ['x] {:not :a-frame}]]
      (let [data (try
                   (frame/require-frame-provider-target! bad 'test/where)
                   ::no-throw
                   (catch clojure.lang.ExceptionInfo e (ex-data e)))]
        (is (map? data) (str "a " (pr-str bad) " target throws ex-info"))
        (is (= :rf.error/bad-frame-provider-arg (:rf.error/id data))
            (str (pr-str bad) " is a bad public provider argument"))
        (is (= :supply-frame-target (:recovery data))
            (str (pr-str bad) " carries the target-grammar recovery id, not a keyword-only one"))
        (is (= bad (:received data)) "the payload echoes the offending value")
        (is (= 'test/where (:where data)) "the payload names the validating call site")))))

(deftest require-frame-provider-target-nil-remains-no-frame-context
  (testing "nil stays ABSENCE — :rf.error/no-frame-context, not a bad argument"
    (let [data (try
                 (frame/require-frame-provider-target! nil 'test/where)
                 ::no-throw
                 (catch clojure.lang.ExceptionInfo e (ex-data e)))]
      (is (= :rf.error/no-frame-context (:rf.error/id data))
          "a nil target is absence, a distinct category from a bad argument")
      (is (= :supply-frame (:recovery data))
          "the absence recovery is unchanged by the target-vocabulary rename"))))

(deftest bad-frame-provider-arg-payload-carries-supply-frame-target
  (testing "the canonical payload builder spells the recovery :supply-frame-target"
    (let [payload (frame/bad-frame-provider-arg-payload "app")]
      (is (= :rf.error/bad-frame-provider-arg (:rf.error/id payload)))
      (is (= :supply-frame-target (:recovery payload)))
      (is (re-find #"frame id keyword" (:reason payload))
          "the human reason still names the keyword arm")
      (is (re-find #"live frame value" (:reason payload))
          "the human reason still names the frame-value arm — the recovery id now agrees with it"))))
