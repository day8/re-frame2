(ns re-frame.trace-listener-frame-tagged-serialization-test
  "A frame-SHAPED trace emit issued by a thread that holds no `:drain-lock`
  serializes through `fanout-monitor` like every other clean emit: routing keys
  on the post-drain deferral scope, never on the payload's shape, so a `:frame`
  tag cannot opt an emit out of the monitor (Spec 009 §The listener contract).

  The drain-vs-listener deadlock and drain-vs-drain overlap laws live in
  `trace-listener-drain-deadlock-test` and
  `trace-listener-concurrent-drain-serialization-test`. JVM-only: CLJS has one
  thread and no monitor."
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [re-frame.substrate.plain-atom :as rf.substrate.plain-atom]
            [re-frame.test-support :as rf.test-support]
            [re-frame.trace.tooling :as rf.trace.tooling]
            [re-frame.trace :as rf.trace])
  (:import [java.util.concurrent CountDownLatch TimeUnit]))

(use-fixtures :each
  (rf.test-support/make-reset-runtime-fixture {:adapter rf.substrate.plain-atom/adapter}))

(def ^:private probe-a :trace.rakqk/a)
(def ^:private probe-b :trace.rakqk/b)

;; Repeated so a green run is not one lucky interleaving. Env-overridable soak.
(def ^:private latch-iters
  (or (some-> (System/getenv "RF2_RAKQK_LATCH_ITERS") Long/parseLong)
      50))

;; Every deftest is `^:requires-debug`: the suite drives the dev trace end to
;; end (see scripts/test-core-prod-gate.sh).

(deftest ^:requires-debug frame-tagged-public-emits-not-under-a-drain-serialize
  ;; Two `{:frame :rf/default}` emits from two ordinary threads, neither draining
  ;; `:rf/default`. Listener L latches inside its callback for A (t1); t2's
  ;; fan-out for B must block on the monitor until L(A) returns.
  (testing (str "a frame-shaped emit that owns no drain-lock cannot enter B until "
                "its A callback has returned (" latch-iters " iterations)")
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
        (let [t1 (Thread. ^Runnable
                          (fn [] (rf.trace/emit! :info probe-a {:frame :rf/default})))
              t2 (Thread. ^Runnable
                          (fn [] (rf.trace/emit! :info probe-b {:frame :rf/default})))]
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
            (str "iter " iter ": a frame-tagged emit that owns no drain-lock "
                 "reached the listener before A's callback returned"))))))
