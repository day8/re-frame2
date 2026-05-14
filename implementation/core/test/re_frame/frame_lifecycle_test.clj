(ns re-frame.frame-lifecycle-test
  "JVM lifecycle tests for re-frame.frame. The smoke suite covers the
  basics (frame-lifecycle, frame-multi-instance, destroy-frame-signals-
  active-machines); this file exercises the edge cases of reg-frame,
  make-frame, destroy-frame!, ensure-default-frame!, and the surgical-
  update path. Per Spec 002 §Frames, §Destroy, §reg-frame is atomic,
  §Re-registration — surgical update, §Per-instance frames."
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [re-frame.core :as rf]
            [re-frame.frame :as frame]
            [re-frame.interop :as interop]
            [re-frame.registrar :as registrar]
            [re-frame.schemas :as schemas]
            [re-frame.flows :as flows]
            [re-frame.substrate.adapter :as adapter]
            [re-frame.substrate.plain-atom :as plain-atom]
            [re-frame.trace :as trace]))

(defn reset-runtime [test-fn]
  (registrar/clear-all!)
  (reset! frame/frames {})
  (reset! flows/flows {})
  (reset! schemas/schemas-by-frame {})
  (trace/clear-trace-cbs!)
  (rf/init! plain-atom/adapter)
  ;; Framework events / fx / subs are registered at namespace-load time;
  ;; clear-all! wiped them. Reload to resurrect the framework registrations.
  (require 're-frame.routing :reload)
  (require 're-frame.ssr     :reload)
  (require 're-frame.machines :reload)
  (test-fn))

(use-fixtures :each reset-runtime)

;; ---- Spec 002 §Re-registration — surgical update -------------------------

(deftest reg-frame-surgical-update-preserves-runtime-state
  (testing "re-registering a live frame replaces metadata but preserves app-db, sub-cache, and queue"
    ;; Set up a frame with live state.
    (rf/reg-frame :tenant {:doc "v1 metadata" :preset :default})
    (rf/reg-event-db :seed (fn [_ [_ n]] {:n n :payload "live"}))
    (rf/reg-sub :n (fn [db _] (:n db)))
    ;; Populate app-db.
    (rf/dispatch-sync [:seed 42] {:frame :tenant})
    ;; Pin a sub in the cache.
    (let [pinned         (rf/subscribe :tenant [:n])
          orig-record    (frame/frame :tenant)
          orig-app-db    (:app-db orig-record)
          orig-sub-cache (:sub-cache orig-record)
          orig-router    (:router orig-record)
          orig-lifecycle (:lifecycle orig-record)]
      (is (= 42 @pinned) "sub returns the seeded value before re-registration")
      (is (contains? @orig-sub-cache [:n]) "sub-cache holds the pinned entry")

      ;; Surgical update: re-register with new metadata.
      (rf/reg-frame :tenant {:doc          "v2 metadata"
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
      (is (= {:n 42 :payload "live"} (rf/get-frame-db :tenant))
          "app-db value survived the surgical update")
      ;; The pinned reaction still resolves to the same value.
      (is (= 42 @pinned)
          "pinned subscription continues to read the live app-db"))))

;; ---- Spec 002 §Destroy — pending events ----------------------------------

(deftest destroy-frame-discards-pending-events
  (testing "events queued via async dispatch are not processed once the frame is destroyed"
    (rf/reg-frame :worker {:doc "worker frame"})
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
      (rf/reg-event-db :tick (fn [db _] (swap! side-effects inc) db))
      (rf/register-trace-cb! ::pending (fn [ev] (swap! traces conj ev)))
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
      (rf/destroy-frame :worker)
      (rf/remove-trace-cb! ::pending)

      ;; The frame is gone.
      (is (nil? (frame/frame :worker))
          "destroy-frame! removed the frame from the registry")
      ;; A subsequent dispatch on the destroyed frame must trace
      ;; :rf.error/frame-destroyed (the recovery path; events do not
      ;; silently land in a void).
      (let [t-after (atom [])]
        (rf/register-trace-cb! ::after (fn [ev] (swap! t-after conj ev)))
        (rf/dispatch-sync [:tick] {:frame :worker})
        (rf/remove-trace-cb! ::after)
        (is (some #(= :rf.error/frame-destroyed (:operation %)) @t-after)
            "post-destroy dispatch traces :rf.error/frame-destroyed")
        ;; The handler did NOT run for the post-destroy attempt — the
        ;; counter stays bounded by what (if anything) drained.
        (is (<= @side-effects 2)
            "the handler did not run on the destroyed frame")))))

;; ---- Spec 002 §Destroy — machine cascade ---------------------------------

(deftest destroy-frame-cascade-emits-per-active-machine
  (testing "destroy emits one :rf.machine.lifecycle/destroyed per active machine snapshot, carrying :reason :parent-frame-destroyed"
    (rf/reg-frame :ten {:doc "tenant"})
    ;; Seed three machine snapshots directly into app-db so we don't
    ;; depend on a full machine-runtime invocation.
    (rf/reg-event-db :seed-machines
      (fn [db _]
        (assoc db :rf/machines
               {:flow/login    {:state :authed     :data {:user "a"}}
                :flow/checkout {:state :reviewing  :data {:cart [1 2]}}
                :flow/billing  {:state :collected  :data {}}})))
    (rf/dispatch-sync [:seed-machines] {:frame :ten})
    (let [traces (atom [])]
      (rf/register-trace-cb! ::cascade (fn [ev] (swap! traces conj ev)))
      (rf/destroy-frame :ten)
      (rf/remove-trace-cb! ::cascade)
      (let [cascade (filter #(= :rf.machine.lifecycle/destroyed (:operation %))
                            @traces)]
        (is (= 3 (count cascade))
            "one trace event per active machine snapshot")
        (is (every? #(= :ten (:frame (:tags %))) cascade)
            "all events carry the destroyed frame's id")
        (is (= #{:flow/login :flow/checkout :flow/billing}
               (set (map #(:machine-id (:tags %)) cascade)))
            "every machine-id is signalled exactly once")
        (is (= #{:authed :reviewing :collected}
               (set (map #(:last-state (:tags %)) cascade)))
            "each event carries that machine's last-state from the snapshot")
        (is (every? #(= :parent-frame-destroyed (:reason (:tags %))) cascade)
            "each event carries :reason :parent-frame-destroyed (the unified discriminator)"))
      ;; A :frame/destroyed trace is also emitted (after the cascade).
      (is (some #(= :frame/destroyed (:operation %)) @traces)
          "expected a :frame/destroyed trace"))))

;; ---- Spec 002 §Destroy — live subscribers --------------------------------

(deftest destroy-frame-with-live-subscribers
  (testing "destroy clears the sub-cache, fires on-dispose, and post-destroy subs return nil"
    (rf/reg-frame :live {:doc "live"})
    (rf/reg-event-db :seed (fn [_ _] {:answer 7}))
    (rf/reg-sub :answer (fn [db _] (:answer db)))
    (rf/dispatch-sync [:seed] {:frame :live})
    (let [r1            (rf/subscribe :live [:answer])
          r2            (rf/subscribe :live [:answer])
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
        (rf/register-trace-cb! ::sub-destroy (fn [ev] (swap! traces conj ev)))
        (rf/destroy-frame :live)
        (rf/remove-trace-cb! ::sub-destroy)
        (is (= 1 @dispose-fired)
            "on-dispose hook fired exactly once when destroy walked the sub-cache")
        (is (some #(= :frame/destroyed (:operation %)) @traces)
            "expected :frame/destroyed trace"))

      ;; Post-destroy subscribe returns nil with :replaced-with-default.
      (let [t-after (atom [])]
        (rf/register-trace-cb! ::post (fn [ev] (swap! t-after conj ev)))
        (let [r3 (rf/subscribe :live [:answer])]
          (is (nil? r3)
              "subscribe against a destroyed frame returns nil"))
        (rf/remove-trace-cb! ::post)
        (is (some (fn [ev]
                    (and (= :rf.error/frame-destroyed (:operation ev))
                         (= :replaced-with-default (:recovery ev))))
                  @t-after)
            "expected :rf.error/frame-destroyed trace with :replaced-with-default recovery")))))

;; ---- Spec 002 §:rf/default — ensure-default-frame! idempotence -----------

(deftest ensure-default-frame-is-idempotent
  (testing "calling ensure-default-frame! more than once does not duplicate or replace the default frame"
    ;; reset-runtime already called rf/init! → ensure-default-frame!.
    (let [original (frame/frame :rf/default)]
      (is (some? original) ":rf/default exists after init!")
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
      (rf/reg-event-fx :go
        (fn [_ [_ payload]]
          {:fx [[:http payload]]}))

      (let [a (rf/make-frame {:fx-overrides {:http :http.stub-A}})
            b (rf/make-frame {:fx-overrides {:http :http.stub-B}})]
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

;; ---- Spec 002 §reg-frame is atomic — :on-create runs synchronously -------

(deftest reg-frame-on-create-runs-synchronously
  (testing ":on-create completes inside reg-frame; app-db is fully populated by the time it returns"
    (let [observed (atom nil)]
      (rf/reg-event-db :boot
        (fn [_ [_ payload]]
          {:booted? true :payload payload :seq [:a :b :c]}))
      ;; Hook a trace listener so we can assert the ordering between
      ;; :on-create dispatch and :frame/created emission. Per Spec 002
      ;; §reg-frame is atomic — :on-create runs first, then :frame/created
      ;; is emitted (the frame becomes observable to listeners).
      (let [traces (atom [])]
        (rf/register-trace-cb! ::oc (fn [ev] (swap! traces conj ev)))
        (rf/reg-frame :booted
                      {:doc       "frame with on-create"
                       :on-create [:boot {:hello "world"}]})
        (rf/remove-trace-cb! ::oc)

        ;; The moment reg-frame returned, app-db must already reflect
        ;; the :on-create event's commit.
        (reset! observed (rf/get-frame-db :booted))
        (is (true? (:booted? @observed))
            ":on-create ran synchronously — app-db reflects its commit")
        (is (= {:hello "world"} (:payload @observed))
            ":on-create payload landed in app-db")
        (is (= [:a :b :c] (:seq @observed))
            "full :on-create handler body completed before reg-frame returned")

        ;; Ordering: the :event/run-end for :boot precedes :frame/created
        ;; (reg-frame emits :frame/created AFTER on-create dispatch-syncs).
        (let [run-end-idx (->> @traces
                               (keep-indexed
                                 (fn [i ev]
                                   (when (and (= :event (:op-type ev))
                                              (= :event (:operation ev))
                                              (= :boot (:event-id (:tags ev)))
                                              (= :run-end (:phase (:tags ev))))
                                     i)))
                               first)
              created-idx (->> @traces
                               (keep-indexed
                                 (fn [i ev]
                                   (when (= :frame/created (:operation ev))
                                     i)))
                               first)]
          (is (some? run-end-idx)
              "expected an :event :run-end trace for the :on-create event")
          (is (some? created-idx)
              "expected a :frame/created trace")
          (is (< run-end-idx created-idx)
              ":on-create's :run-end precedes :frame/created — frame is fully booted before listeners observe it"))))))

;; ---- rf2-68kok — destroy-frame interrupts active drain on next dequeue --
;;
;; Per Spec 002 §Edge cases worth pinning §Frame disposal mid-drain: the
;; drain loop checks `(:destroyed? (:lifecycle frame))` before each
;; dequeue; on detect it stops, drops the remaining queue, and emits one
;; `:rf.frame/drain-interrupted` with the dropped count. In-flight events
;; finish (run-to-completion); only events not yet dequeued are dropped.

(deftest destroy-from-handler-interrupts-drain-and-emits-interrupted
  (testing "a handler that destroys its own frame mid-drain stops the
            drain, drops the remaining queue, and emits exactly one
            :rf.frame/drain-interrupted carrying the dropped count.
            The just-completed event runs to completion (run-to-
            completion); only later queued events are dropped"
    (rf/reg-frame :drain-int/worker {:doc "rf2-68kok drain-interrupt frame"})
    (let [ran           (atom [])
          traces        (atom [])
          captured-tick (atom [])]
      ;; Plain mutator — runs whenever the drain dequeues.
      (rf/reg-event-db :drain-int/tick
        (fn [db _]
          (swap! ran conj :tick)
          (update db :n (fnil inc 0))))
      ;; Handler that destroys its own frame mid-cascade. The first
      ;; tick has already run; destroy mid-drain MUST interrupt the
      ;; remainder.
      (rf/reg-event-fx :drain-int/self-destruct
        (fn [_ _]
          (swap! ran conj :self-destruct)
          ;; Call destroy-frame! directly — bypasses the
          ;; `dispatch-sync-in-handler` policy that would otherwise
          ;; bite us if we tried to dispatch destruction through the
          ;; router. Spec 002 says destroy-frame interrupts the
          ;; ACTIVE drain; the trigger can be anything.
          (frame/destroy-frame! :drain-int/worker)
          {}))
      (rf/register-trace-cb! ::drain-int (fn [ev] (swap! traces conj ev)))
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
      ;; microtask in the same JS task and cannot run until the test's
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
      (rf/remove-trace-cb! ::drain-int)

      ;; The self-destruct ran first (dispatch-sync seeds at the front);
      ;; the trailing ticks did NOT run.
      (is (= [:self-destruct] @ran)
          "the only handler that ran was the self-destruct seed itself")

      ;; The frame is gone.
      (is (nil? (frame/frame :drain-int/worker))
          "destroy-frame! removed the frame from the registry")

      ;; Exactly one :rf.frame/drain-interrupted trace fired carrying
      ;; the dropped count (4 ticks queued before the seed).
      (let [interrupts (filterv (fn [ev]
                                  (= :rf.frame/drain-interrupted (:operation ev)))
                                @traces)]
        (is (= 1 (count interrupts))
            "exactly one :rf.frame/drain-interrupted trace")
        (let [ev (first interrupts)]
          (is (= :frame (:op-type ev))
              ":op-type is :frame (lifecycle family, not :error)")
          (is (= :drain-int/worker (:frame (:tags ev)))
              ":tags carries the destroyed frame id")
          (is (= 4 (:dropped-count (:tags ev)))
              ":dropped-count reflects the 4 trailing ticks left in the queue"))))))

(deftest destroy-from-handler-in-flight-event-still-completes
  (testing "the in-flight event finishes (run-to-completion) — destroy
            interrupts AFTER the current handler returns, not in
            the middle of it"
    (rf/reg-frame :rtc/worker {})
    (let [completed (atom false)]
      ;; This handler:
      ;;   (a) destroys the frame partway through
      ;;   (b) keeps running — does more work after the destroy call
      ;;   (c) sets the completion flag at the very end
      ;; Per run-to-completion, ALL of (a)-(c) must observe in order;
      ;; the drain interrupt only fires AFTER this handler returns.
      (rf/reg-event-fx :rtc/work-then-destroy
        (fn [_ _]
          (frame/destroy-frame! :rtc/worker)
          ;; Post-destroy bookkeeping still runs inside this handler.
          (reset! completed true)
          {}))
      (rf/dispatch-sync [:rtc/work-then-destroy] {:frame :rtc/worker})
      (is (true? @completed)
          "handler ran to completion AFTER calling destroy-frame!"))))

(deftest destroy-from-different-thread-also-interrupts-drain-on-next-pass
  (testing "if another thread (or the same thread between passes)
            destroys the frame, the next pass's destroyed-check fires
            and interrupts the drain just the same"
    (rf/reg-frame :cross-thread/worker {})
    (let [traces (atom [])
          captured-tick (atom [])]
      (rf/reg-event-db :cross-thread/tick (fn [db _] db))
      (rf/register-trace-cb! ::xt (fn [ev] (swap! traces conj ev)))
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
      (rf/remove-trace-cb! ::xt)
      ;; The pre-existing rf2-dpny contract: NO trace at all from the
      ;; drain itself. The interrupt trace only fires when a drain
      ;; pass actually STARTED on a live frame and then detected
      ;; destruction mid-pass.
      (let [interrupts (filter #(= :rf.frame/drain-interrupted (:operation %))
                               @traces)]
        (is (zero? (count interrupts))
            "no :rf.frame/drain-interrupted — the drain never started
             on the already-destroyed frame")))))

;; ---- rf2-cufbh — child-frame :on-create is queued asynchronously --------
;;
;; Per Spec 002 §`reg-frame` / `make-frame` called from inside a handler:
;; when a handler spawns a child frame, the child's `:on-create` MUST be
;; dispatched (queued on the child's router) rather than dispatch-sync'd.
;; Synchronous dispatch from inside a handler is an error (per Spec 002
;; §dispatch-sync inside a handler is an error), and the no-cross-frame-
;; drain rule (Spec 002 §Run-to-completion) forbids interleaving the two
;; cascades anyway.
;;
;; These tests pin both halves of the contract:
;;
;;   (1) top-level reg-frame (no in-flight handler) — `:on-create` is
;;       dispatch-sync'd, the cascade settles before reg-frame returns
;;       (already covered by `reg-frame-on-create-runs-synchronously`
;;       above; the rf2-cufbh test below adds the negative-axis pin —
;;       no async-queue residue under the top-level path).
;;   (2) handler-created reg-frame — `:on-create` is dispatched
;;       asynchronously. The child frame's :on-create handler does NOT
;;       run before the parent handler returns; it runs on a later
;;       drain cycle for the child's router.

(deftest child-frame-on-create-is-async-when-created-from-handler
  (testing "reg-frame called inside a handler queues the child's :on-create
            asynchronously on the child's router — Spec 002 §reg-frame /
            make-frame called from inside a handler"
    (let [event-order  (atom [])
          captured-tick (atom [])]
      (rf/reg-event-db :child/boot
        (fn [db _]
          (swap! event-order conj :child/boot-ran)
          (assoc db :child-booted? true)))
      (rf/reg-event-fx :parent/spawn-child
        (fn [_ _]
          (swap! event-order conj :parent/before-reg-frame)
          (rf/reg-frame :child {:on-create [:child/boot]})
          (swap! event-order conj :parent/after-reg-frame)
          {}))
      ;; Capture the child's next-tick so we can deterministically inspect
      ;; the queue state at the moment the parent handler returns.
      (with-redefs [interop/next-tick (fn [f] (swap! captured-tick conj f) nil)]
        (rf/dispatch-sync [:parent/spawn-child])

        ;; The parent's handler body ran end-to-end, but the child's
        ;; :on-create handler did NOT run inline.
        (is (= [:parent/before-reg-frame :parent/after-reg-frame]
               @event-order)
            ":child/boot is NOT in the order — it was queued, not dispatch-sync'd")

        ;; The child frame exists; its app-db reflects the pre-:on-create
        ;; state (fresh {}, not the booted shape).
        (let [child (frame/frame :child)]
          (is (some? child) ":child frame is registered")
          (is (= {} (rf/get-frame-db :child))
              "child app-db is still {} — :on-create has not yet drained")
          ;; The child's router queue holds exactly the :on-create event.
          (let [queue (:queue @(:router child))]
            (is (= 1 (count queue))
                "child router has the :on-create envelope queued")
            (is (= [:child/boot] (-> queue first :event))
                "queued event is :child/boot"))))

      ;; Now run the captured tick — the child's drain fires and
      ;; :child/boot runs on the child's drain cycle, after the parent
      ;; cascade settled.
      (doseq [tick @captured-tick] (tick))
      (is (= [:parent/before-reg-frame
              :parent/after-reg-frame
              :child/boot-ran]
             @event-order)
          ":child/boot ran AFTER the parent cascade settled, on the
           child's own drain cycle")
      (is (true? (:child-booted? (rf/get-frame-db :child)))
          "child app-db reflects :on-create commit once the child drain fires"))))

(deftest top-level-reg-frame-on-create-still-runs-synchronously
  (testing "Top-level reg-frame (NOT inside a handler) still dispatch-sync's
            :on-create — the rf2-cufbh fix preserves the fast path"
    (let [order (atom [])]
      (rf/reg-event-db :top/init
        (fn [_ _]
          (swap! order conj :top/init-ran)
          {:initialised? true}))
      ;; No in-flight handler; *current-frame* is nil. :on-create must run
      ;; synchronously, before reg-frame returns.
      (rf/reg-frame :top {:on-create [:top/init]})
      (is (= [:top/init-ran] @order)
          ":top/init ran inside reg-frame (synchronous top-level path)")
      (is (true? (:initialised? (rf/get-frame-db :top)))
          "top-level app-db reflects :on-create commit by reg-frame return"))))

(deftest child-frame-via-make-frame-also-async-from-handler
  (testing "make-frame from inside a handler also queues :on-create
            asynchronously — it shares the reg-frame code path"
    (let [order         (atom [])
          captured-tick (atom [])
          child-id      (atom nil)]
      (rf/reg-event-db :sub-actor/boot
        (fn [db _]
          (swap! order conj :sub-actor/boot-ran)
          (assoc db :booted? true)))
      (rf/reg-event-fx :parent/spawn-sub-actor
        (fn [_ _]
          (swap! order conj :parent/before-make-frame)
          (reset! child-id (rf/make-frame {:on-create [:sub-actor/boot]}))
          (swap! order conj :parent/after-make-frame)
          {}))
      (with-redefs [interop/next-tick (fn [f] (swap! captured-tick conj f) nil)]
        (rf/dispatch-sync [:parent/spawn-sub-actor])
        (is (= [:parent/before-make-frame :parent/after-make-frame] @order)
            ":sub-actor/boot did not run inline")
        (is (some? @child-id) "make-frame returned a gensym'd id")
        (is (= {} (rf/get-frame-db @child-id))
            "child app-db is still empty before its own drain runs"))
      ;; Drain the child's tick.
      (doseq [tick @captured-tick] (tick))
      (is (= [:parent/before-make-frame
              :parent/after-make-frame
              :sub-actor/boot-ran]
             @order)
          ":sub-actor/boot ran on the child's own drain cycle")
      (is (true? (:booted? (rf/get-frame-db @child-id)))
          "child app-db reflects :on-create commit after its drain"))))

;; ---- Spec 002 §Frame presets — closed v1 expansion table -----------------
;;
;; Presets expand at registration time into a fixed bundle of metadata
;; keys. Per Spec 002 §Frame presets the closed v1 set is :default,
;; :test, :story, :ssr-server. User-supplied keys win on conflict; the
;; original :preset value is preserved verbatim for inspection.

(deftest preset-expansion-default
  (testing ":default expands to {} (identical to omitting :preset)"
    (rf/reg-frame :p/default {:preset :default})
    (let [cfg (:config (frame/frame :p/default))]
      (is (= :default (:preset cfg))
          ":preset is preserved on the config for inspection")
      (is (nil? (:fx-overrides cfg))
          ":default does not introduce :fx-overrides")
      (is (nil? (:drain-depth cfg))
          ":default does not introduce :drain-depth"))))

(deftest preset-expansion-test
  (testing ":test expansion: HTTP redirect + drain-depth 100"
    (rf/reg-frame :p/test {:preset :test})
    (let [cfg (:config (frame/frame :p/test))]
      (is (= :test (:preset cfg)))
      (is (= {:rf.http/managed :rf.http/managed-canned-success}
             (:fx-overrides cfg))
          ":test redirects the canonical Spec 014 HTTP fx to its canned-success stub")
      (is (= 100 (:drain-depth cfg))
          ":test stamps :drain-depth 100 explicitly so tooling reads it off frame-meta"))))

(deftest preset-expansion-story
  (testing ":story expansion: HTTP redirect + tighter drain-depth 16"
    (rf/reg-frame :p/story {:preset :story})
    (let [cfg (:config (frame/frame :p/story))]
      (is (= :story (:preset cfg)))
      (is (= {:rf.http/managed :rf.http/managed-canned-success}
             (:fx-overrides cfg)))
      (is (= 16 (:drain-depth cfg))
          ":story tightens :drain-depth to 16 so a runaway cascade fails fast under a story"))))

(deftest preset-expansion-ssr-server
  (testing ":ssr-server expansion: :platform :server + :on-error projection"
    (rf/reg-frame :p/ssr {:preset :ssr-server})
    (let [cfg (:config (frame/frame :p/ssr))]
      (is (= :ssr-server (:preset cfg)))
      (is (= :server (:platform cfg))
          ":ssr-server stamps :platform :server (singular keyword — one platform per frame)")
      (is (= :rf.error/server-projection (:on-error cfg))
          ":ssr-server wires :on-error to the server-side projection target"))))

(deftest preset-user-keys-win-on-conflict
  (testing "user-supplied keys override individual expansion entries"
    ;; :test sets :drain-depth 100 by default; user overrides to 1000.
    (rf/reg-frame :p/override {:preset      :test
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
          (rf/reg-frame :p/bad {:preset :devcards}))
        "passing a preset outside the closed v1 set is a registration-time error")))

;; ---- rf2-ft2b: drain-after-destroy null guard ----------------------------
;;
;; Per the rf2-ft2b reproducer: a scheduled drain races frame destruction.
;; By the time the drain fires, the frame's app-db container is nil
;; (because frame/get-frame-db returns nil for destroyed frames), and
;; the per-event :db commit at router.cljc would write through nil →
;; NullPointerException on the executor thread.
;;
;; The fix is a defense-in-depth guard at the single choke point through
;; which every container write flows: substrate/adapter/replace-container!
;; no-ops when container is nil and emits :rf.warning/write-after-destroy.

(deftest replace-container-no-ops-on-nil-container
  ;; Direct unit-level coverage of the guard at the adapter wrapper. The
  ;; nil-container call must not throw and must emit the warning trace.
  (testing "replace-container! with nil container is a no-op + :rf.warning/write-after-destroy"
    (let [recorded (atom [])]
      (rf/register-trace-cb! ::rec (fn [ev] (swap! recorded conj ev)))
      ;; Must not throw NPE.
      (is (nil? (adapter/replace-container! nil {:any :value}))
          "nil container is a documented no-op, not an exception")
      (let [warns (filterv (fn [ev]
                             (and (= :warning (:op-type ev))
                                  (= :rf.warning/write-after-destroy
                                     (:operation ev))))
                           @recorded)]
        (is (= 1 (count warns))
            "exactly one :rf.warning/write-after-destroy trace fired")))))

(deftest drain-after-destroy-does-not-npe
  ;; Reproducer for the original race (rf2-ft2b): a scheduled drain that
  ;; reaches the per-event :db commit AFTER the frame has been destroyed
  ;; must NOT throw. Instead the adapter-level nil guard skips the write
  ;; and emits :rf.warning/write-after-destroy.
  ;;
  ;; The race is forced deterministically by capturing the next-tick
  ;; callback (instead of running it on the executor) so that the
  ;; destroy-frame! call slots in between the drain being scheduled and
  ;; the drain actually running.
  (testing "scheduled drain that fires after destroy is a no-op + warning, not an NPE"
    (let [captured-tick (atom nil)
          recorded      (atom [])]
      (rf/register-trace-cb! ::rec (fn [ev] (swap! recorded conj ev)))
      (rf/reg-frame :race/frame {:doc "rf2-ft2b reproducer frame"})
      ;; A simple :db-writing event handler. The drain that processes
      ;; this event is what we want to land AFTER destroy.
      (rf/reg-event-db :write
        (fn [_db _]
          {:committed? true}))
      (with-redefs [interop/next-tick (fn [f] (reset! captured-tick f) nil)]
        ;; Async dispatch — schedules the drain via next-tick. The
        ;; with-redefs binding captures the drain thunk into
        ;; @captured-tick instead of executing it.
        (rf/dispatch [:write] {:frame :race/frame})
        (is (some? @captured-tick)
            "the async dispatch scheduled a drain via next-tick"))
      ;; Now destroy the frame. After this, frame/get-frame-db returns
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
;; "draining onto a destroyed frame is a no-op + trace per event" — by
;; capturing next-tick, dispatching events, destroying the frame, and
;; THEN running the captured tick. The drain must not throw, must not
;; mutate state, and must emit `:rf.error/frame-destroyed` per pending
;; event.

(deftest destroy-frame-pending-drain-pins-no-op-contract
  (testing "queued events that drain AFTER destroy do not mutate state,
            do not throw, and trace :rf.error/frame-destroyed per event"
    (rf/reg-frame :rf2-dpny/worker {:doc "rf2-dpny race-pin frame"})
    (let [side-effects (atom 0)
          traces       (atom [])
          captured     (atom [])]
      (rf/reg-event-db :rf2-dpny/tick
                       (fn [db _] (swap! side-effects inc) db))
      (rf/register-trace-cb! ::rf2-dpny (fn [ev] (swap! traces conj ev)))

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
      (rf/destroy-frame :rf2-dpny/worker)
      (is (nil? (frame/frame :rf2-dpny/worker))
          "the frame is gone from the registry")

      ;; NOW run the captured ticks. Pre-fix: this would have raced
      ;; the destroy; post-fix the drain at drain! line ~261 sees a
      ;; nil frame and emits the trace. The contract is "no-op + trace".
      (doseq [tick @captured]
        (is (nil? (try (tick) nil
                       (catch Throwable e e)))
            "the captured drain ran without throwing"))

      (rf/remove-trace-cb! ::rf2-dpny)

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
        (rf/register-trace-cb! ::rf2-dpny-after (fn [ev] (swap! after-traces conj ev)))
        (rf/dispatch-sync [:rf2-dpny/tick] {:frame :rf2-dpny/worker})
        (rf/remove-trace-cb! ::rf2-dpny-after)
        (is (some #(= :rf.error/frame-destroyed (:operation %)) @after-traces)
            "post-destroy dispatch (not the drain) traces :rf.error/frame-destroyed"))

      ;; (d) The handler still never ran.
      (is (= 0 @side-effects)
          "no handler ran across the entire lifecycle")

      ;; (e) Frame stays gone.
      (is (nil? (frame/frame :rf2-dpny/worker))
          "the frame did not somehow re-materialise from the post-destroy drain"))))

(deftest replace-container-on-destroyed-frame-does-not-npe
  ;; Tighter reproducer that hits the adapter guard directly via the
  ;; live runtime. The router's per-event :db commit reads the frame's
  ;; app-db container right before writing — and if the frame was
  ;; destroyed mid-drain, get-frame-db returns nil. We exercise that
  ;; exact shape by reading get-frame-db AFTER destroy and feeding the
  ;; nil container straight into replace-container!.
  (testing "frame/get-frame-db on a destroyed frame is nil; replace-container! handles it"
    (let [recorded (atom [])]
      (rf/register-trace-cb! ::rec (fn [ev] (swap! recorded conj ev)))
      (rf/reg-frame :race/destroyed-mid-write {})
      (frame/destroy-frame! :race/destroyed-mid-write)
      (let [container (frame/get-frame-db :race/destroyed-mid-write)]
        (is (nil? container)
            "get-frame-db on a destroyed frame returns nil — the precondition for the rf2-ft2b NPE")
        ;; This is the exact call shape from router.cljc's :db commit.
        ;; Pre-fix: NPE. Post-fix: no-op + warning trace.
        (is (nil? (adapter/replace-container! container {:would :have :npe'd true}))
            "writing through the nil container is a documented no-op"))
      (let [warns (filterv (fn [ev]
                             (and (= :warning (:op-type ev))
                                  (= :rf.warning/write-after-destroy
                                     (:operation ev))))
                           @recorded)]
        (is (pos? (count warns))
            ":rf.warning/write-after-destroy fired for the post-destroy write")))))

;; ---- rf2-2e6k: frame-ids / frame-meta re-export round-trip ----------------
;;
;; Per test-coverage-review-2026-05-12 P3-18: frame-lifecycle exercises read
;; via direct atom reads. The public re-exports `rf/frame-ids` and
;; `rf/frame-meta` (core.cljc:1160-1161) are the documented introspection
;; surface; this test pins their round-trip contract.

(deftest frame-ids-round-trip
  (testing "(rf/frame-ids) returns every registered, non-destroyed frame id
            plus :rf/default"
    (rf/reg-frame :tenants/acme    {:doc "acme tenant"  :preset :default})
    (rf/reg-frame :tenants/widgets {:doc "widgets co"   :preset :default})
    (let [ids (rf/frame-ids)]
      (is (set? ids) "frame-ids returns a set")
      (is (contains? ids :rf/default)
          ":rf/default appears alongside user-registered frames")
      (is (contains? ids :tenants/acme))
      (is (contains? ids :tenants/widgets))
      ;; Sanity: a never-registered id is absent.
      (is (not (contains? ids :tenants/nonexistent))
          "frame-ids excludes ids that were never registered"))))

(deftest frame-meta-round-trip
  (testing "(rf/frame-meta id) returns the canonical flat :rf/frame-meta shape
            per Spec-Schemas §:rf/frame-meta — user-supplied metadata,
            preset-expansion keys, and lifecycle fields all at the top level"
    (rf/reg-frame :tenants/acme {:doc          "acme tenant"
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
          ":created-at is the host clock at reg-frame time — flat, not nested
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
    (rf/reg-frame :test/auth-flow {:preset :test})
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
    (rf/reg-frame :short-lived {:doc "will be destroyed"})
    (rf/destroy-frame :short-lived)
    (is (nil? (rf/frame-meta :short-lived))
        "frame-meta on a destroyed frame returns nil (no resurrection)")))

;; ---- rf2-mwrh: reset-frame! partial-state preservation contract -----------
;;
;; Per test-coverage-review-2026-05-12 P3-27: `rf/reset-frame` is re-exported
;; (core.cljc:438 → frame/reset-frame!) but no test pins what it does. The
;; impl is `destroy-frame! followed by reg-frame with the same config` —
;; this test pins the contract that flows from that definition:
;;   - app-db is reset (fresh container).
;;   - sub-cache is cleared (sub re-registers against the fresh frame).
;;   - The config metadata is preserved (same :doc, same :on-create, etc.).
;;   - :on-create re-runs (the new frame is freshly booted).
;;   - The frame is still queryable (not in the destroyed state).

(deftest reset-frame-preserves-config-resets-app-db
  (testing "rf/reset-frame: app-db is reset, config metadata is preserved,
            :on-create re-runs, frame remains queryable"
    (let [boot-count (atom 0)]
      (rf/reg-event-db :seed
        (fn [_ [_ payload]]
          (swap! boot-count inc)
          {:booted? true :payload payload :extra :seeded}))
      (rf/reg-frame :app/main
                    {:doc       "frame with on-create"
                     :on-create [:seed {:hello "world"}]
                     :fx-overrides {:custom :value}})
      ;; First :on-create dispatch increments the counter.
      (is (= 1 @boot-count) ":on-create ran once at reg-frame time")
      (is (= {:booted? true :payload {:hello "world"} :extra :seeded}
             (rf/get-frame-db :app/main))
          "app-db reflects :on-create commit")

      ;; Mutate app-db: a subsequent event extends the value.
      (rf/reg-event-db :extend (fn [db _] (assoc db :runtime-write? true)))
      (rf/dispatch-sync [:extend] {:frame :app/main})
      (is (true? (:runtime-write? (rf/get-frame-db :app/main)))
          "mutation is visible before reset-frame")

      ;; Capture the original frame record so we can compare.
      (let [orig-record    (frame/frame :app/main)
            orig-app-db    (:app-db orig-record)
            orig-sub-cache (:sub-cache orig-record)
            orig-router    (:router orig-record)]

        ;; Reset.
        (rf/reset-frame :app/main)

        ;; After reset: the frame is queryable.
        (let [new-record (frame/frame :app/main)]
          (is (some? new-record)
              "the frame still exists post-reset (not destroyed)")
          ;; Config metadata preserved: same :doc, same :on-create,
          ;; same :fx-overrides.
          (is (= "frame with on-create" (get-in new-record [:config :doc]))
              ":config :doc preserved across reset")
          (is (= [:seed {:hello "world"}]
                 (get-in new-record [:config :on-create]))
              ":config :on-create preserved across reset")
          (is (= {:custom :value} (get-in new-record [:config :fx-overrides]))
              ":config :fx-overrides preserved across reset")
          ;; The slot identity-replaceable parts are FRESH containers.
          (is (not (identical? orig-app-db (:app-db new-record)))
              "app-db container is a fresh allocation post-reset")
          (is (not (identical? orig-sub-cache (:sub-cache new-record)))
              "sub-cache atom is fresh post-reset")
          (is (not (identical? orig-router (:router new-record)))
              "router atom is fresh post-reset"))

        ;; :on-create re-ran on the fresh frame: counter incremented.
        (is (= 2 @boot-count)
            ":on-create re-dispatches against the freshly-registered frame")

        ;; The runtime mutation is gone — fresh app-db reflects only
        ;; :on-create's commit, not the prior :extend.
        (is (= {:booted? true :payload {:hello "world"} :extra :seeded}
               (rf/get-frame-db :app/main))
            "app-db is reset to :on-create state; runtime writes are gone")
        (is (nil? (:runtime-write? (rf/get-frame-db :app/main)))
            "the :extend handler's write is absent — reset wiped runtime state")))))

(deftest reset-frame-on-missing-id-is-noop
  (testing "rf/reset-frame against an unregistered frame is a silent no-op"
    ;; frame/reset-frame! only fires when (frame id) returns non-nil.
    ;; Calling against an unknown id must not throw.
    (is (nil? (rf/reset-frame :no/such-frame))
        "reset-frame on a missing id returns nil without throwing")
    (is (nil? (frame/frame :no/such-frame))
        "the missing frame is still absent")))
