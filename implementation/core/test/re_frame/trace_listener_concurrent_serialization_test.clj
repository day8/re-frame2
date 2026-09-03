(ns re-frame.trace-listener-concurrent-serialization-test
  "rf2-uw7hg — each registered trace listener must be invoked SERIALLY across
  concurrent JVM emits. Spec 009 §The listener contract (and
  `docs/api/re-frame.core.md`: \"Delivery is synchronous: the callback returns
  before the next record\") promise synchronous, in-order, event-at-a-time
  delivery PER listener — a tool callback / appender was told it can never
  re-enter itself.

  ## The defect

  `re-frame.trace.tooling/deliver-to-tooling!` defers reentrant fan-outs onto
  `*listener-fanout-queue*`, a THREAD-LOCAL dynamic binding. That orders
  same-thread nested emits (rf2-1zxlsm) but says nothing about two emits racing
  on two JVM threads: each opens its OWN outermost fan-out and each invokes the
  SAME registered listener callback CONCURRENTLY. A latch probe that blocks
  listener L while it handles event A on one thread, then emits B on another,
  observed B enter L before A's callback returned — the callback overlapped
  itself, and B could overtake A even when A's emit began first.

  ## The fix

  A single process-global JVM monitor (`fanout-monitor`) serializes each
  OUTERMOST fan-out — its transitive reentrant drain included. Concurrent emits
  linearize on monitor-acquisition order; no listener callback ever overlaps
  itself; an emit still returns only after its record's callback completes.
  Same-thread reentrancy never re-acquires the monitor (it enqueues on the
  thread-local queue), so the rf2-1zxlsm ordering is preserved and there is no
  self-deadlock. CLJS is single-threaded, so these races cannot manifest there
  and there is no monitor — hence this suite is JVM-only (`.clj`)."
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [re-frame.substrate.plain-atom :as rf.substrate.plain-atom]
            [re-frame.test-support :as rf.test-support]
            [re-frame.trace :as rf.trace])
  (:import [java.util.concurrent CountDownLatch TimeUnit]))

(use-fixtures :each
  (rf.test-support/make-reset-runtime-fixture {:adapter rf.substrate.plain-atom/adapter}))

(def ^:private probe-a :trace.serialize/a)
(def ^:private probe-b :trace.serialize/b)

;; Loop the deterministic latch proof so a green run is determinism, not a lucky
;; interleaving. Env-overridable for a heavier local soak.
(def ^:private latch-iters
  (or (some-> (System/getenv "RF2_UW7HG_LATCH_ITERS") Long/parseLong)
      50))

(def ^:private stress-iters
  (or (some-> (System/getenv "RF2_UW7HG_STRESS_ITERS") Long/parseLong)
      2000))

;; ---- 1. Deterministic two-thread latch proof -----------------------------

;; ---- Posture: dev-only, declared by `^:requires-debug` (rf2-d2841) ---------
;; Trace machinery end to end: under `-Dre-frame.debug=false` `rf.trace/emit` is a
;; no-op, so there is no semantic residue to run under that posture, and a
;; `(when interop/debug-enabled? ...)` split -- the shape the rest of rf2-d2841
;; used -- would leave EMPTY deftests reporting green (class 2).  Every deftest
;; below is therefore TAGGED, and the production-gate lane skips the tag rather
;; than the file: the namespace is still LOADED there, so a load-time failure
;; under the gate still reddens the job, and an untagged new deftest joins that
;; lane BY DEFAULT.  Mechanism + rationale: `scripts/test-core-prod-gate.sh`.

(deftest ^:requires-debug per-listener-callback-never-overlaps-across-concurrent-emits
  ;; A single listener L blocks INSIDE its callback while handling event A
  ;; (emitted on thread t1). While A is blocked, event B is emitted to the SAME
  ;; L on thread t2. Pre-fix, t2's outermost fan-out was a distinct thread-local
  ;; queue and ran concurrently, so L(B) entered while L(A) was still in flight
  ;; (max concurrent invocations 2, and B recorded before A returned). With
  ;; `fanout-monitor`, t2's fan-out blocks until t1's A callback returns, so L
  ;; never re-enters itself and B is delivered strictly after A.
  (testing (str "one listener cannot enter B until its A callback has returned "
                "(" latch-iters " iterations)")
    (dotimes [iter latch-iters]
      (let [log       (atom [])
            in-flight (atom 0)
            max-conc  (atom 0)
            a-entered (CountDownLatch. 1)
            b-entered (CountDownLatch. 1)
            release-a (CountDownLatch. 1)]
        (rf.trace/register-listener! ::probe
          (fn [ev]
            (let [op (:operation ev)]
              (when (or (= op probe-a) (= op probe-b))
                ;; Overlap detector: record the max simultaneous invocations.
                (let [n (swap! in-flight inc)]
                  (swap! max-conc max n))
                (swap! log conj [:enter op])
                (if (= op probe-a)
                  (do (.countDown a-entered)
                      ;; Block A mid-callback, holding the fan-out monitor, until
                      ;; the harness releases it — never released by B's delivery,
                      ;; so a serialized B cannot deadlock A.
                      (.await release-a 5 TimeUnit/SECONDS))
                  (.countDown b-entered))
                (swap! log conj [:exit op])
                (swap! in-flight dec)))))
        (let [t1 (Thread. ^Runnable (fn [] (rf.trace/emit! :info probe-a {})))
              t2 (Thread. ^Runnable (fn [] (rf.trace/emit! :info probe-b {})))]
          (.start t1)
          ;; A is now inside L, blocked on release-a (holding fanout-monitor).
          (.await a-entered 5 TimeUnit/SECONDS)
          (.start t2)
          ;; Wait until t2 has reached the fan-out seam: pre-fix it enters L(B)
          ;; (b-entered fires, overlap already recorded); fixed, it is BLOCKED
          ;; contending for fanout-monitor. late-bind + the emit path use only
          ;; lock-free atoms, so a BLOCKED state here means monitor contention.
          ;; Deadline-bounded so a mis-started thread cannot hang the suite.
          (let [deadline (+ (System/currentTimeMillis) 5000)]
            (loop []
              (when (and (< (System/currentTimeMillis) deadline)
                         (pos? (.getCount b-entered))
                         (not= java.lang.Thread$State/BLOCKED (.getState t2)))
                (Thread/yield)
                (recur))))
          (.countDown release-a)
          (.join t1 5000)
          (.join t2 5000))
        (rf.trace/unregister-listener! ::probe)
        (is (= 1 @max-conc)
            (str "iter " iter ": the listener callback was invoked concurrently "
                 "with itself across two emits (max concurrent invocations "
                 @max-conc "); per-listener delivery must never overlap"))
        (is (= [[:enter probe-a] [:exit probe-a] [:enter probe-b] [:exit probe-b]]
               @log)
            (str "iter " iter ": B reached the listener before A's callback "
                 "returned; expected strict A-before-B ordering, got "
                 (pr-str @log)))))))

;; ---- 2. Stress + registration churn (no overlap, no deadlock) ------------

(deftest ^:requires-debug ^:stress concurrent-emits-serialize-under-registration-churn
  ;; Many threads emit concurrently while a churn thread registers /
  ;; unregisters a second listener. A continuously-registered always-on
  ;; listener runs an overlap detector. Invariants:
  ;;   - it NEVER observes a concurrent self-invocation (max-conc == 1) — the
  ;;     monitor serializes every fan-out;
  ;;   - it receives EVERY emitted event exactly once (delivered == total) —
  ;;     registration churn on a sibling id neither drops nor double-delivers;
  ;;   - the run COMPLETES — no emitter thread is left alive (no deadlock,
  ;;     no retroactive stall) after joining.
  (testing (str "concurrent emits never overlap a listener and never deadlock "
                "under registration churn (" stress-iters " emits)")
    (let [in-flight  (atom 0)
          max-conc   (atom 0)
          delivered  (atom 0)
          n-threads  6
          per-thread (quot stress-iters n-threads)
          total      (* n-threads per-thread)]
      (rf.trace/register-listener! ::always-on
        (fn [_ev]
          (let [n (swap! in-flight inc)]
            (swap! max-conc max n))
          (swap! delivered inc)
          (swap! in-flight dec)))
      (let [start (CountDownLatch. 1)
            stop  (atom false)
            churn (Thread.
                    ^Runnable
                    (fn []
                      (.await start)
                      (let [toggle (atom false)]
                        (while (not @stop)
                          (if (swap! toggle not)
                            (rf.trace/register-listener! ::churned (fn [_ev] nil))
                            (rf.trace/unregister-listener! ::churned))
                          (Thread/yield)))))
            emitters (mapv (fn [t]
                             (Thread.
                               ^Runnable
                               (fn []
                                 (.await start)
                                 (dotimes [i per-thread]
                                   (rf.trace/emit! :info :trace.serialize/stress
                                                {:t t :i i})))))
                           (range n-threads))]
        (.start churn)
        (doseq [^Thread e emitters] (.start e))
        (.countDown start)
        (doseq [^Thread e emitters] (.join e 30000))
        (reset! stop true)
        (.join churn 5000)
        (let [stragglers (filterv (fn [^Thread e] (.isAlive e)) emitters)]
          (rf.trace/unregister-listener! ::always-on)
          (rf.trace/unregister-listener! ::churned)
          (is (empty? stragglers)
              (str "an emitter thread did not finish within the deadline — "
                   "possible deadlock; " (count stragglers) " still alive"))
          (is (= 1 @max-conc)
              (str "the always-on listener overlapped itself under concurrent "
                   "emits (max concurrent invocations " @max-conc ")"))
          (is (= total @delivered)
              (str "every concurrently-emitted event must reach the always-on "
                   "listener exactly once; expected " total ", got "
                   @delivered)))))))
