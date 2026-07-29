(ns re-frame.trace-listener-frame-tagged-serialization-test
  "rf2-rakqk — a frame-SHAPED trace emit that is NOT issued while the emitting
  thread actually holds the target frame's `:drain-lock` must STILL serialize
  through `fanout-monitor` like every other clean emit (rf2-uw7hg). The public
  trace-listener contract (Spec 009 §The listener contract) promises a single
  registered listener is never entered concurrently with itself across JVM
  threads, whatever the event payload looks like.

  ## The defect this pins (rf2-rakqk)

  PR #6200 (rf2-jl75r) broke the fanout-monitor↔drain-lock AB-BA deadlock by
  routing any FRAME-SHAPED outermost emit inline, off the monitor. But the
  discriminator was event-shape inference: a `:frame` tag / caller-supplied
  dispatch-id / ambient frame / `retain? false` opted an emit out of the monitor
  even when the emitting thread was NOT actually inside a held drain-lock. A
  public/manual/stale `:frame`-tagged `trace/emit!` — issued from an ordinary
  thread that owns no drain-lock — was therefore driven inline and LOST the
  whole-fanout serial law: two such emits could enter the same listener callback
  concurrently.

  A deterministic latch probe below starts two `{:frame :rf/default}`-tagged
  emits on two threads while the first listener callback is latched. Pre-fix the
  second entered the callback while the first was still in flight (max concurrent
  invocations 2). Post-fix neither emitting thread is inside a drain — no
  `:drain-lock` region, so no post-drain deferral scope
  (`re-frame.trace/call-with-deferred-listener-delivery`, rf2-wxy1c) — so both
  emits take the ordinary clean-emit path and serialize on the monitor: the second
  is BLOCKED contending for it until the first callback returns. Max concurrent
  invocations 1, strict A-before-B. Event payload SHAPE never enters the routing
  decision, which is the law this suite pins.

  The complementary laws — a drain-owned emit never deadlocks against a
  listener's `dispatch-sync` (`re-frame.trace-listener-drain-deadlock-test`,
  rf2-jl75r), and two concurrent drains of DIFFERENT frames never overlap one
  listener (`re-frame.trace-listener-concurrent-drain-serialization-test`,
  rf2-wxy1c) — are pinned by those suites and deliberately not duplicated here.

  JVM-only (`.clj`): CLJS is single-threaded, has no monitor, and cannot race two
  emits — the overlap cannot manifest there."
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [re-frame.substrate.plain-atom :as plain-atom]
            [re-frame.test-support :as test-support]
            [re-frame.trace :as trace])
  (:import [java.util.concurrent CountDownLatch TimeUnit]))

(use-fixtures :each
  (test-support/make-reset-runtime-fixture {:adapter plain-atom/adapter}))

(def ^:private probe-a :trace.rakqk/a)
(def ^:private probe-b :trace.rakqk/b)

;; Loop the deterministic latch proof so a green run is determinism, not a lucky
;; interleaving. Env-overridable for a heavier local soak.
(def ^:private latch-iters
  (or (some-> (System/getenv "RF2_RAKQK_LATCH_ITERS") Long/parseLong)
      50))

;; ---- Posture: dev-only, declared by `^:requires-debug` (rf2-d2841) ---------
;; Trace machinery end to end: under `-Dre-frame.debug=false` `trace/emit` is a
;; no-op, so there is no semantic residue to run under that posture, and a
;; `(when interop/debug-enabled? ...)` split -- the shape the rest of rf2-d2841
;; used -- would leave EMPTY deftests reporting green (class 2).  Every deftest
;; below is therefore TAGGED, and the production-gate lane skips the tag rather
;; than the file: the namespace is still LOADED there, so a load-time failure
;; under the gate still reddens the job, and an untagged new deftest joins that
;; lane BY DEFAULT.  Mechanism + rationale: `scripts/test-core-prod-gate.sh`.

(deftest ^:requires-debug frame-tagged-public-emits-not-under-a-drain-serialize
  ;; Two frame-TAGGED emits (`{:frame :rf/default}`) issued from two ordinary
  ;; threads, NEITHER of which is draining `:rf/default` — so neither holds its
  ;; `:drain-lock`. A single listener L blocks INSIDE its callback while handling
  ;; A (thread t1). While A is blocked, B is emitted to the SAME L on t2. Pre-fix
  ;; the `:frame` tag routed both inline (bypassing `fanout-monitor`), so L(B)
  ;; entered while L(A) was still in flight (max concurrent invocations 2). With
  ;; the drain-lock OWNERSHIP discriminator, an unowned frame-tagged emit is NOT
  ;; treated as a drain emit, so t2's fan-out blocks on the monitor until t1's A
  ;; callback returns: L never re-enters itself, B delivered strictly after A.
  (testing (str "a frame-shaped emit that owns no drain-lock cannot enter B until "
                "its A callback has returned (" latch-iters " iterations)")
    (dotimes [iter latch-iters]
      (let [log       (atom [])
            in-flight (atom 0)
            max-conc  (atom 0)
            a-entered (CountDownLatch. 1)
            b-entered (CountDownLatch. 1)
            release-a (CountDownLatch. 1)]
        (trace/register-listener! ::probe
          (fn [ev]
            (let [op (:operation ev)]
              (when (or (= op probe-a) (= op probe-b))
                ;; Overlap detector: record the max simultaneous invocations.
                (let [n (swap! in-flight inc)]
                  (swap! max-conc max n))
                (swap! log conj [:enter op])
                (if (= op probe-a)
                  (do (.countDown a-entered)
                      ;; Block A mid-callback (holding the fan-out monitor iff the
                      ;; monitor path is taken) until the harness releases it —
                      ;; never released by B's delivery, so a serialized B cannot
                      ;; deadlock A.
                      (.await release-a 5 TimeUnit/SECONDS))
                  (.countDown b-entered))
                (swap! log conj [:exit op])
                (swap! in-flight dec)))))
        ;; Both emits carry a `:frame` tag but run on fresh threads that are NOT
        ;; draining `:rf/default` — so the payload is frame-SHAPED yet the thread
        ;; owns no drain-lock. This is exactly the emit the shape heuristic
        ;; mis-routed inline.
        (let [t1 (Thread. ^Runnable
                          (fn [] (trace/emit! :info probe-a {:frame :rf/default})))
              t2 (Thread. ^Runnable
                          (fn [] (trace/emit! :info probe-b {:frame :rf/default})))]
          (.start t1)
          ;; A is now inside L, blocked on release-a.
          (.await a-entered 5 TimeUnit/SECONDS)
          (.start t2)
          ;; Wait until t2 has reached the fan-out seam: pre-fix it enters L(B)
          ;; (b-entered fires, overlap already recorded); fixed, it is BLOCKED
          ;; contending for fanout-monitor. The ownership probe + emit path use
          ;; only lock-free atoms, so a BLOCKED state here means monitor
          ;; contention. Deadline-bounded so a mis-started thread cannot hang the
          ;; suite.
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
        (trace/unregister-listener! ::probe)
        (is (= 1 @max-conc)
            (str "iter " iter ": a frame-tagged public emit that owns no "
                 "drain-lock overlapped the listener callback with itself (max "
                 "concurrent invocations " @max-conc "); event-shape must not opt "
                 "an emit out of the rf2-uw7hg serial monitor"))
        (is (= [[:enter probe-a] [:exit probe-a] [:enter probe-b] [:exit probe-b]]
               @log)
            (str "iter " iter ": B reached the listener before A's callback "
                 "returned; expected strict A-before-B ordering, got "
                 (pr-str @log)))))))
