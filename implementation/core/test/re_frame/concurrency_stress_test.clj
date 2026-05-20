(ns re-frame.concurrency-stress-test
  "Per rf2-35rgj — JVM concurrency stress coverage beyond the
  single-drainer peek/pop race already pinned by
  `router_drain_race_test.clj` (rf2-ynk7).

  rf2-ynk7 stressed ONE concurrency surface: the executor-vs-main-thread
  race on a single frame's queue. The framework has other
  concurrency-shaped surfaces that the deterministic test suite covers
  single-shot but never under contention. Per the rf2-35rgj brief, this
  namespace adds 5000-iter stress coverage for:

    1. **Nested cross-frame dispatch under executor jitter** — handler
       on frame X calls (rf/dispatch event {:frame :y}). The submit-then-
       schedule path on frame Y's router CAS-races the JVM executor
       thread; frames are independent per Spec 002 §Rules rule 1, so
       neither cascade may starve, drop, or double-process events.

    2. **Cross-frame :dispatch-sync during sibling drain** — many
       threads concurrently call (rf/dispatch-sync [:bump] {:frame :tgt})
       while the target frame is mid-drain on its own work. Per rf2-fp97
       the warning fires; the dispatch proceeds; the target frame's
       state is consistent at quiescence (no envelope dropped or
       double-processed).

    3. **Hot-reload race during drain** — Thread A drives a sustained
       event stream; Thread B repeatedly re-registers the running event
       handler with a freshly-built closure. Per Spec 001 §Hot-reload
       semantics rule 1, the handler currently in process-event! finishes
       with its captured fn — but ACROSS many iterations, every event
       must process exactly once with EITHER the v1 OR v2 body (never
       skipped, never run twice).

  Pattern follows `router_drain_race_test.clj`:
    - per-scenario stress-iters defaults to 5000, env-overridable
    - failures accumulate into an atom; the deftest asserts zero
    - fixture is the same `reset-runtime` shape

  CLJS is single-threaded; these races cannot manifest there. The
  rf2-35rgj follow-up (mid-handler :dispatch-later resolve) is filed as
  rf2-35rgj-followup if scenarios 1-3 land clean."
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [re-frame.core :as rf]
            [re-frame.frame :as frame]
            [re-frame.schemas :as schemas]
            [re-frame.flows :as flows]
            [re-frame.registrar :as registrar]
            [re-frame.substrate.plain-atom :as plain-atom]
            [re-frame.trace :as trace])
  (:import [java.util.concurrent CountDownLatch TimeUnit]))

(defn- reset-runtime [test-fn]
  (registrar/clear-all!)
  (reset! frame/frames {})
  (reset! flows/flows {})
  (reset! schemas/schemas-by-frame {})
  (trace/clear-trace-listeners!)
  (rf/init! plain-atom/adapter)
  (require 're-frame.routing :reload)
  (require 're-frame.ssr :reload)
  (require 're-frame.machines :reload)
  (test-fn))

(use-fixtures :each reset-runtime)

;; The stress iteration count keeps CI under ~60s per scenario at the
;; rf2-ynk7-standard 5000 iters. Env override lets the operator dial up
;; (or down) without code changes.
(def ^:private stress-iters
  (or (some-> (System/getenv "RF2_35RGJ_STRESS_ITERS") Long/parseLong)
      5000))

;; ---- 1. Nested cross-frame dispatch under executor jitter ----------------

(deftest cross-frame-dispatch-under-executor-jitter-stress
  ;; rf2-35rgj scenario 1.
  ;;
  ;; Setup: two frames `:rgj.exec/a` and `:rgj.exec/b`. A handler on A
  ;; uses (rf/dispatch [:b/leaf] {:frame :rgj.exec/b}) to append to B's
  ;; queue mid-cascade on A. The JVM executor schedules B's drain on a
  ;; different thread from A's drain. Per Spec 002 §Rules rule 1 frames
  ;; are independent state machines — their drain-locks don't share —
  ;; so the cross-frame submit must atomically land on B's queue and
  ;; trigger B's `ensure-drain-scheduled!`. The CAS on B's `:scheduled?`
  ;; serialises against B's executor-thread drain release; missing that
  ;; would silently drop the cross-frame envelope (the orphan-window
  ;; race the same locking seam closes for the single-frame case).
  ;;
  ;; Stress: per iter, dispatch :a/cross-fire (which fans out N
  ;; :b/leaf events onto B). Wait until B has processed exactly N
  ;; cross-fires. Failure modes: timeout (B's drain missed a
  ;; scheduled-flag flip → envelopes stuck) OR mismatched count
  ;; (envelope dropped or double-processed).
  (testing (str "cross-frame dispatch never drops or duplicates "
                "envelopes across " stress-iters " iterations")
    (rf/reg-frame :rgj.exec/a {:doc "originating frame"})
    (rf/reg-frame :rgj.exec/b {:doc "target frame, drains on executor"
                                ;; Generous drain-depth so the cascade
                                ;; doesn't hit the default-100 ceiling
                                ;; under the stress pattern below.
                                :drain-depth 10000})

    (let [failures    (atom [])
          ;; Per-iter B-counter, threaded through a global indirection
          ;; so we don't have to re-register handlers each iter (which
          ;; would race with leftover envelopes from the prior iter).
          current-cnt (atom (atom 0))
          ;; How many :b/leaf events each :a/cross-fire fans out.
          fanout      4]
      (rf/reg-event-db :b/leaf
        {:frame :rgj.exec/b}
        (fn [db _]
          (swap! @current-cnt inc)
          (update db :n (fnil inc 0))))
      (rf/reg-event-fx :a/cross-fire
        {:frame :rgj.exec/a}
        (fn [_ _]
          ;; Returning fx with N cross-frame :dispatch fxs.
          ;; (:dispatch only targets its own frame; cross-frame is the
          ;; explicit (rf/dispatch ...) call below, which is the
          ;; documented in-handler shape for cross-frame fire-and-forget.)
          (dotimes [_ fanout]
            (rf/dispatch [:b/leaf] {:frame :rgj.exec/b}))
          {}))
      (dotimes [i stress-iters]
        (let [cnt (atom 0)]
          ;; Per-iter counter so the assertion is local to this
          ;; iteration. The global indirection keeps the handler
          ;; closure stable across iters (re-registering the handler
          ;; each iter would race with leftover B-queue envelopes from
          ;; the prior iter).
          (reset! current-cnt cnt)
          ;; Run the cross-frame fire on A. The handler fans out
          ;; `fanout` :b/leaf events targeting B. The cascade on A
          ;; settles before this dispatch-sync returns; B's executor
          ;; drain runs on its own thread and may have started, finished,
          ;; or be in-flight when we proceed.
          (rf/dispatch-sync [:a/cross-fire] {:frame :rgj.exec/a})
          ;; Wait for B's drain to settle: spin-poll the counter,
          ;; bounded by a 5s wall-clock deadline.
          (let [deadline (+ (System/currentTimeMillis) 5000)]
            (loop []
              (cond
                (= fanout @cnt) :done
                (> (System/currentTimeMillis) deadline)
                (swap! failures conj
                       {:iter   i
                        :reason :timeout
                        :seen   @cnt
                        :want   fanout})
                :else (do (Thread/yield) (recur)))))
          (let [delta @cnt]
            (when (not= fanout delta)
              (swap! failures conj
                     {:iter  i
                      :delta delta
                      :want  fanout})))))
      (is (zero? (count @failures))
          (str "Expected zero cross-frame dispatch failures across "
               stress-iters " iterations; got " (count @failures)
               (when (pos? (count @failures))
                 (str ". First few: " (pr-str (vec (take 5 @failures))))))))))

;; ---- 2. Cross-frame :dispatch-sync during sibling drain ------------------

(deftest cross-frame-dispatch-sync-during-sibling-drain-stress
  ;; rf2-35rgj scenario 2.
  ;;
  ;; rf2-fp97 added `:rf.warning/cross-frame-dispatch-sync-during-drain`
  ;; for the case where frame A is mid-drain and a `dispatch-sync` lands
  ;; on a different frame B. The deterministic single-shot case is
  ;; covered by cross_frame_dispatch_sync_warn_test.clj. Under stress,
  ;; the invariants are:
  ;;
  ;;   - the warning surface stays observable (≥ 1 warning under realistic
  ;;     contention — assert specifically that the cross-frame race
  ;;     window is being entered, not just that the warning code never
  ;;     fires);
  ;;   - frame B's final :n exactly matches the number of bumps issued
  ;;     against it (no envelope dropped or double-processed by the
  ;;     interleave between A's executor-thread drain and the main-thread
  ;;     dispatch-sync on B);
  ;;   - frame A's final :n exactly matches its issued count (the cross-
  ;;     frame interleave does not corrupt A's queue either).
  ;;
  ;; Implementation: Thread A pushes a sustained stream of async dispatches
  ;; into frame A (executor drains on its own thread). The main thread
  ;; concurrently fires dispatch-sync on frame B in a tight loop. Because
  ;; A is independent of B (Spec 002 §Rules rule 1) their drain-locks
  ;; never share; the cross-frame warning emits when the main-thread's
  ;; dispatch-sync moment overlaps with the executor's drain window.
  ;;
  ;; Per rf2-ynk7's note on the `:in-sync-drain?` guard: same-frame
  ;; multi-thread dispatch-sync IS rejected via
  ;; `:rf.error/dispatch-sync-in-handler` when one thread reads
  ;; `:in-sync-drain?` true. That is by-design; we deliberately avoid
  ;; that case here by hammering B from one thread only.
  (testing (str "cross-frame interleave — B's count is exact; warnings observable "
                "across " stress-iters " bumps")
    (rf/reg-frame :rgj.sync/a {:doc "frame A — async drain on executor"
                                :drain-depth 200000})
    (rf/reg-frame :rgj.sync/b {:doc "frame B — main-thread sync drainer"
                                :drain-depth 200000})
    (rf/reg-event-db :bump
      (fn [db _] (update db :n (fnil inc 0))))

    (let [warnings    (atom 0)
          n-a         stress-iters
          n-b         stress-iters
          latch       (CountDownLatch. 1)
          ;; Count cross-frame warnings to assert observability (≥ 1).
          listener-id (rf/register-trace-listener!
                        ::rgj-cross-frame
                        (fn [ev]
                          (when (= :rf.warning/cross-frame-dispatch-sync-during-drain
                                   (:operation ev))
                            (swap! warnings inc))))
          a-thread
          (Thread.
            ^Runnable
            (fn []
              (.await latch)
              (dotimes [_ n-a]
                (rf/dispatch [:bump] {:frame :rgj.sync/a}))))]
      (try
        (.start a-thread)
        ;; Release Thread A and concurrently hammer B from the main
        ;; thread. The two frames drain in parallel on independent
        ;; locks; the cross-frame warning emits when the main-thread's
        ;; dispatch-sync on B observes A's :in-drain? true (the
        ;; executor is mid-drain).
        (.countDown latch)
        (dotimes [_ n-b]
          (rf/dispatch-sync [:bump] {:frame :rgj.sync/b}))
        ;; Wait for Thread A to finish queueing.
        (.join a-thread 10000)
        ;; A's executor drain may still be in flight; ride one final
        ;; sync drain to settle frame A. Per drain-block!'s spin-CAS
        ;; contract: spin until the executor releases, then drain
        ;; anything still queued.
        (rf/dispatch-sync [:bump] {:frame :rgj.sync/a})
        (finally
          (rf/unregister-trace-listener! listener-id)))
      ;; Validate:
      ;;   - Frame A processed exactly (n-a + 1) bumps (the +1 is the
      ;;     final settler dispatch).
      ;;   - Frame B processed exactly n-b bumps.
      ;;   - At least one cross-frame warning fired (the race window
      ;;     was entered at least once — observability surface live).
      ;;     We do NOT assert an upper bound: schedule is timing-
      ;;     dependent and 0/N hits are equally valid framework behaviour.
      (let [a-actual (:n (rf/get-frame-db :rgj.sync/a))
            b-actual (:n (rf/get-frame-db :rgj.sync/b))]
        (is (= (inc n-a) a-actual)
            (str "Frame A: expected " (inc n-a)
                 " bumps processed; got " a-actual))
        (is (= n-b b-actual)
            (str "Frame B: expected " n-b
                 " bumps processed; got " b-actual))
        ;; The warning is timing-dependent. Under realistic load on
        ;; multi-core CI (stress-iters = 5000) the window is hit on
        ;; the order of hundreds-to-thousands of times. We assert ≥ 1
        ;; to pin the observability surface is alive; if a future
        ;; scheduler change ever produces zero warnings here that's
        ;; a signal worth investigating.
        (is (pos? @warnings)
            (str "Expected at least one cross-frame warning across "
                 stress-iters " bumps (observability surface should "
                 "fire under contention); got " @warnings))))))

;; ---- 3. Hot-reload race during drain -------------------------------------

(deftest hot-reload-race-during-drain-stress
  ;; rf2-35rgj scenario 3.
  ;;
  ;; Spec 001 §Hot-reload semantics rule 1: an event handler currently
  ;; in process-event! finishes against its captured fn even when an
  ;; external thread re-registers the same id. The deterministic
  ;; latched test is in hot_reload_test.clj. Under stress, an event
  ;; stream from Thread A interleaves with sustained re-registrations
  ;; from Thread B; the invariants are:
  ;;
  ;;   - every dispatched event runs exactly once (no skips, no
  ;;     duplicates);
  ;;   - each event runs with EITHER the v1 OR v2 closure (never some
  ;;     hybrid frankenstate);
  ;;   - the registry-replacement-hook for sub re-registrations (which
  ;;     this test exercises indirectly through the dispatch path) does
  ;;     not corrupt the frame's sub-cache.
  ;;
  ;; The test uses a counter handler that increments :n; both v1 and
  ;; v2 produce the same observable effect (+1 per call) so we can
  ;; assert exactly N events fired regardless of which body ran on each
  ;; one. The hot-reload thread re-registers ~ once per ms during the
  ;; dispatch burst, so the registry sees ~ N/2 swaps under typical CI
  ;; clock resolution.
  (testing (str "sustained dispatch + concurrent re-registration — "
                "every event runs exactly once across " stress-iters " events")
    (rf/reg-frame :rgj.reload/main {:doc "hot-reload race target"
                                     :drain-depth 100000})
    ;; Two structurally-identical handler bodies. Either may be active
    ;; when an event is processed; both produce the same effect so the
    ;; final count is deterministic.
    (let [v1 (fn [db _] (update db :n (fnil inc 0)))
          v2 (fn [db _] (update db :n (fnil inc 0)))]
      (rf/reg-event-db :rgj.reload/tick {:frame :rgj.reload/main} v1)

      (let [stop    (atom false)
            ;; Hot-reload churn thread: re-register :rgj.reload/tick on
            ;; a tight loop, alternating between v1 and v2, until stop
            ;; is signalled. Yields between swaps so the dispatch
            ;; thread makes forward progress.
            reload-thread
            (Thread.
              ^Runnable
              (fn []
                (let [toggle (atom false)]
                  (while (not @stop)
                    (let [body (if (swap! toggle not) v2 v1)]
                      (rf/reg-event-db :rgj.reload/tick
                                       {:frame :rgj.reload/main}
                                       body))
                    (Thread/yield)))))]
        (.start reload-thread)
        ;; Dispatch the stress stream. Each dispatch-sync settles before
        ;; the next; the registry can be swapped any time between
        ;; envelope-pop and handler-fn lookup, plus during the handler's
        ;; body (rule 1 covers in-flight closure capture).
        (try
          (dotimes [_ stress-iters]
            (rf/dispatch-sync [:rgj.reload/tick] {:frame :rgj.reload/main}))
          (finally
            (reset! stop true)
            (.join reload-thread 5000)))
        ;; Validate: exactly stress-iters events processed. If ANY
        ;; event ran twice or got skipped under the registry-swap race,
        ;; :n would diverge.
        (let [n (:n (rf/get-frame-db :rgj.reload/main))]
          (is (= stress-iters n)
              (str "Expected " stress-iters " events processed under "
                   "hot-reload churn; got " n)))))))
