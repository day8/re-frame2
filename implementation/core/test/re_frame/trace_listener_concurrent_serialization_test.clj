(ns re-frame.trace-listener-concurrent-serialization-test
  "Each registered trace listener is invoked SERIALLY across concurrent JVM
  emits (Spec 009 §The listener contract; `docs/api/re-frame.core.md`:
  \"Delivery is synchronous: the callback returns before the next record\").

  `*fanout-ctx*` orders same-thread reentrant emits only. Emits racing on two
  threads each open their own outermost fan-out, so the process-wide
  `fanout-monitor` is what keeps one listener from running on two threads at
  once. JVM-only: CLJS has one thread and no monitor."
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [re-frame.substrate.plain-atom :as rf.substrate.plain-atom]
            [re-frame.test-support :as rf.test-support]
            [re-frame.trace.tooling :as rf.trace.tooling]
            [re-frame.trace :as rf.trace])
  (:import [java.util.concurrent CountDownLatch TimeUnit]))

(use-fixtures :each
  (rf.test-support/make-reset-runtime-fixture {:adapter rf.substrate.plain-atom/adapter}))

(def ^:private probe-a :trace.serialize/a)
(def ^:private probe-b :trace.serialize/b)

;; Repeated so a green run is not one lucky interleaving. Env-overridable soak.
(def ^:private latch-iters
  (or (some-> (System/getenv "RF2_UW7HG_LATCH_ITERS") Long/parseLong)
      50))

(def ^:private stress-iters
  (or (some-> (System/getenv "RF2_UW7HG_STRESS_ITERS") Long/parseLong)
      2000))

;; Every deftest is `^:requires-debug`: the suite drives the dev trace end to
;; end (see scripts/test-core-prod-gate.sh).

(deftest ^:requires-debug per-listener-callback-never-overlaps-across-concurrent-emits
  ;; Listener L latches inside its callback for A (thread t1) while B is
  ;; emitted on t2. t2's fan-out must block on the monitor until L(A) returns.
  (testing (str "one listener cannot enter B until its A callback has returned "
                "(" latch-iters " iterations)")
    (dotimes [iter latch-iters]
      (let [log       (atom [])
            a-entered (CountDownLatch. 1)
            b-entered (CountDownLatch. 1)
            release-a (CountDownLatch. 1)]
        (rf.trace.tooling/register-listener! ::probe
          (fn [ev]
            (let [op (:operation ev)]
              (when (or (= op probe-a) (= op probe-b))
                (swap! log conj [:enter op])
                (if (= op probe-a)
                  (do (.countDown a-entered)
                      (.await release-a 5 TimeUnit/SECONDS))
                  (.countDown b-entered))
                (swap! log conj [:exit op])))))
        (let [t1 (Thread. ^Runnable (fn [] (rf.trace/emit! :info probe-a {})))
              t2 (Thread. ^Runnable (fn [] (rf.trace/emit! :info probe-b {})))]
          (.start t1)
          (.await a-entered 5 TimeUnit/SECONDS)
          (.start t2)
          ;; Wait until t2 has either entered L(B) (overlap) or is BLOCKED on
          ;; the monitor — the emit path otherwise uses only lock-free atoms.
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
        (rf.trace.tooling/unregister-listener! ::probe)
        ;; An L(B) entry while L(A) was latched would land before A's :exit.
        (is (= [[:enter probe-a] [:exit probe-a] [:enter probe-b] [:exit probe-b]]
               @log)
            (str "iter " iter ": B reached the listener before A's callback "
                 "returned"))))))

(deftest ^:requires-debug ^:stress concurrent-emits-serialize-under-registration-churn
  ;; Six threads emit while a churn thread registers / unregisters a sibling
  ;; listener. The always-on listener must never overlap itself, must receive
  ;; every event exactly once, and every emitter must finish.
  (testing (str "concurrent emits never overlap a listener and never deadlock "
                "under registration churn (" stress-iters " emits)")
    (let [in-flight  (atom 0)
          max-conc   (atom 0)
          delivered  (atom 0)
          n-threads  6
          per-thread (quot stress-iters n-threads)
          total      (* n-threads per-thread)]
      (rf.trace.tooling/register-listener! ::always-on
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
                            (rf.trace.tooling/register-listener! ::churned (fn [_ev] nil))
                            (rf.trace.tooling/unregister-listener! ::churned))
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
          (rf.trace.tooling/unregister-listener! ::always-on)
          (rf.trace.tooling/unregister-listener! ::churned)
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
