(ns re-frame.trace-listener-drain-deadlock-test
  "rf2-jl75r — the JVM trace `fanout-monitor` must NOT be held (or awaited) across
  a frame-drain emit. Public trace listeners are allowed to call `dispatch-sync`,
  and the drain path emits ordinary traces (`:rf.event/run-start`, …) while
  holding a frame's `:drain-lock`. Before the fix these two facts closed a hard
  AB-BA deadlock (`re-frame.trace.tooling/deliver-to-tooling!`):

    - **T1** does a clean `emit!`, acquires `fanout-monitor`, enters a listener
      that calls `dispatch-sync` into frame F, and spin-waits on F's `:drain-lock`
      (`re-frame.router/drain-block!`).
    - **the drainer** (here the JVM async-drain executor thread) already holds F's
      `:drain-lock` through `run-one-pass!`, reaches an ordinary in-run
      `:rf.event/run-start` emit, and blocks acquiring `fanout-monitor`.

  Neither can progress: `drain-block!`'s bounded-wait assumption is false because
  the active drainer is itself waiting on the caller (T1).

  Why an ASYNC drain drives the drainer side: a `dispatch-sync` seeds
  `:in-sync-drain? true` on the frame router, which makes a concurrent
  cross-thread `dispatch-sync` to that same frame a rejected nested-sync — it
  never reaches `drain-block!`, so it never spins. The genuine cycle needs the
  drainer holding the lock through the ASYNC path (`drain-try!` /
  `re-frame.interop/next-tick`'s single-thread executor), where `:in-sync-drain?`
  is false and T1's listener `dispatch-sync` really does spin on the lock.

  The fix keeps the whole synchronous, ordered, serialized fan-out (rf2-uw7hg)
  but takes the monitor OUT of the cycle: an emit issued while the framework owns
  a frame's `:drain-lock` never acquires the monitor at all. Per rf2-wxy1c it is
  APPENDED and delivered at the post-drain boundary
  (`re-frame.trace/call-with-deferred-listener-delivery`, which brackets every
  `:drain-lock` acquire → run → release region), where the lock is already down —
  so the drainer's side of the cycle blocks on nothing while holding the lock.
  (rf2-jl75r's original cut drove such an emit INLINE instead; that broke the
  deadlock but let two independent frames' drains enter one listener at once,
  which is what the deferral replaced.) This suite is the deterministic
  barrier/latch proof that the exact AB-BA interleaving now completes within a
  bounded timeout and the listener-dispatched event settles EXACTLY once.

  JVM-only (`.clj`): CLJS is single-threaded, has no monitor, and cannot race two
  drains — the deadlock cannot manifest there."
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [re-frame.core :as rf]
            ;; Load-bearing require (rf2-jl75r): with the epoch artefact on the
            ;; classpath the per-event settle emits the cascade trailers
            ;; (`:rf.epoch/snapshotted` / `:rf.epoch/outcome`) `outside any cascade`
            ;; — nil `:rf.trace/dispatch-id` — yet STILL on the drainer thread
            ;; holding the `:drain-lock`. That is the exact emit a dispatch-id-only
            ;; frame-drain test misclassifies (re-opening the deadlock), so the
            ;; suite must load epoch to exercise the frame-routability discriminator
            ;; rather than only the dispatch-id fast path. The FULL JVM suite always
            ;; loads epoch; this require makes the guard hold in isolation too.
            [re-frame.epoch]
            [re-frame.substrate.plain-atom :as plain-atom]
            [re-frame.test-support :as test-support]
            [re-frame.trace :as trace])
  (:import [java.util.concurrent CountDownLatch TimeUnit]))

(use-fixtures :each
  (test-support/make-reset-runtime-fixture {:adapter plain-atom/adapter}))

;; Loop the deterministic proof so a green run is determinism, not a lucky
;; interleaving. Env-overridable for a heavier local soak.
(def ^:private iters
  (or (some-> (System/getenv "RF2_JL75R_ITERS") Long/parseLong)
      25))

;; Bounded waits: a correct run completes in milliseconds. A deadlocked run
;; leaves the threads alive forever, so the timeout is what converts the hang
;; into a red assertion instead of hanging the whole suite.
(def ^:private join-timeout-ms 10000)
(def ^:private latch-timeout-s 10)

;; ---- Posture: dev-only, declared by `^:requires-debug` (rf2-d2841) ---------
;; Trace machinery end to end: under `-Dre-frame.debug=false` `trace/emit` is a
;; no-op, so there is no semantic residue to run under that posture, and a
;; `(when interop/debug-enabled? ...)` split -- the shape the rest of rf2-d2841
;; used -- would leave EMPTY deftests reporting green (class 2).  Every deftest
;; below is therefore TAGGED, and the production-gate lane skips the tag rather
;; than the file: the namespace is still LOADED there, so a load-time failure
;; under the gate still reddens the job, and an untagged new deftest joins that
;; lane BY DEFAULT.  Mechanism + rationale: `scripts/test-core-prod-gate.sh`.

(deftest ^:requires-debug fanout-monitor-drain-lock-ab-ba-does-not-deadlock
  (testing (str "T1 (fanout-monitor -> listener -> dispatch-sync F) against the "
                "async drainer (F.:drain-lock -> run-start emit) completes "
                "bounded and the dispatched event settles exactly once ("
                iters " iterations)")
    (dotimes [iter iters]
      (let [t1-event-runs  (atom 0)
            drain-first    (atom 0)
            drain-second   (atom 0)
            l-fired?       (atom false)
            ;; The drainer signals it is inside the drain, holding F's drain-lock.
            drainer-in     (CountDownLatch. 1)
            ;; T1's listener (holding fanout-monitor) signals the drainer to
            ;; proceed to its next in-run emit, which pre-fix blocks acquiring
            ;; that same monitor.
            drainer-go     (CountDownLatch. 1)]

        ;; --- handlers, re-registered each iter so they close over this iter's
        ;; latches/counters. All target the fixture-ensured :rf/default frame.
        (rf/reg-event :jl75r/drain-first
          (fn [{:keys [db]} _]
            (swap! drain-first inc)
            ;; This handler runs on the async-drain executor thread, INSIDE
            ;; :rf/default's drain — the active drainer holds the frame's
            ;; :drain-lock right now and `:in-sync-drain?` is false.
            (.countDown drainer-in)
            ;; Block mid-drain, STILL holding the drain-lock, until T1 has
            ;; acquired fanout-monitor inside its listener and started
            ;; dispatch-sync-spinning on our drain-lock.
            (.await drainer-go latch-timeout-s TimeUnit/SECONDS)
            ;; Queue a SECOND event. Its `:rf.event/run-start` — an ordinary
            ;; in-run (drain-nested) emit fanned out while we still hold the
            ;; drain-lock — is precisely what pre-fix blocked acquiring the
            ;; monitor (the run-end/db-changed emits of THIS event contend too).
            {:db (assoc db :jl75r/drain-first true)
             :fx [[:dispatch [:jl75r/drain-second]]]}))

        (rf/reg-event :jl75r/drain-second
          (fn [{:keys [db]} _]
            (swap! drain-second inc)
            {:db (assoc db :jl75r/drain-second true)}))

        (rf/reg-event :jl75r/t1-event
          (fn [{:keys [db]} _]
            (swap! t1-event-runs inc)
            {:db (assoc db :jl75r/t1-event true)}))

        (trace/register-listener! ::jl75r
          (fn [ev]
            ;; React ONLY to T1's clean trigger emit (never to the drain's own
            ;; run-start/run-end/db-changed emits), and exactly once.
            (when (and (= :jl75r/t1-trigger (:operation ev))
                       (compare-and-set! l-fired? false true))
              ;; We are on T1, HOLDING fanout-monitor (a clean emit's outermost
              ;; drive holds it across this callback). Release the drainer, then
              ;; dispatch-sync into :rf/default — which the executor is draining
              ;; — so this spins on the drain-lock (`drain-block!`). Pre-fix, the
              ;; drainer's next in-run emit blocks on the monitor we hold: the
              ;; AB-BA cycle.
              (.countDown drainer-go)
              (rf/dispatch-sync [:jl75r/t1-event] {:frame :rf/default}))))

        ;; Kick the ASYNC drain (runs on `re-frame.interop/next-tick`'s
        ;; single-thread executor, `:in-sync-drain?` false), then wait until it
        ;; is genuinely inside the drain holding :rf/default's drain-lock.
        (rf/dispatch [:jl75r/drain-first] {:frame :rf/default})
        (.await drainer-in latch-timeout-s TimeUnit/SECONDS)

        (let [t1 (Thread. ^Runnable
                          (fn [] (trace/emit! :info :jl75r/t1-trigger {}))
                          "jl75r-t1-listener")]
          (.start t1)
          (.join t1 join-timeout-ms)
          (trace/unregister-listener! ::jl75r)

          (is (not (.isAlive t1))
              (str "iter " iter ": T1 (fanout-monitor -> listener -> "
                   "dispatch-sync F) never finished within " join-timeout-ms
                   "ms — the fanout-monitor/drain-lock AB-BA deadlock is present"))
          (is (= 1 @t1-event-runs)
              (str "iter " iter ": the listener-dispatched event must settle "
                   "EXACTLY once; it ran " @t1-event-runs " time(s)"))
          (is (= 1 @drain-second)
              (str "iter " iter ": the frame-drain's second event (whose "
                   "run-start is the contended emit) must settle exactly once; "
                   "it ran " @drain-second " time(s)")))))))
