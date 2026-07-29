(ns re-frame.trace-listener-concurrent-drain-serialization-test
  "rf2-wxy1c — two DIFFERENT frames draining CONCURRENTLY must never enter the
  same registered trace listener at the same time, and no listener callback may
  run while the framework owns a frame's `:drain-lock`.

  ## The defect this pins (rf2-wxy1c)

  rf2-rakqk (PR #6248) fixed the shape-based bypass by keying the
  `fanout-monitor` opt-out on REAL drain-lock ownership: an outermost emit whose
  thread actually holds the target frame's `:drain-lock` drove its listener
  fan-out INLINE, off the monitor, so the rf2-jl75r AB-BA cycle
  (monitor -> listener -> `dispatch-sync` -> drain-lock -> monitor) could not
  close. That is deadlock-correct but NOT serial: a single frame cannot have two
  simultaneous drain-lock owners, yet two INDEPENDENT frames can drain at the
  same time on two JVM threads, each owning its OWN frame's lock. Both then
  qualified for the inline path and both entered the same arbitrary listener
  callback concurrently — the public serial-listener contract (Spec 009 §The
  listener contract: synchronous, event-at-a-time, per-listener ordered
  delivery) broken exactly where rf2-rakqk left the relaxation, and arbitrary
  programmer/tool listener code running while the framework held a drain lock,
  contrary to rf2-rakqk's own acceptance criterion.

  The three merged suites stayed green because they cover clean-vs-clean
  (`trace-listener-concurrent-serialization-test`), a frame-SHAPED public emit
  outside any drain (`trace-listener-frame-tagged-serialization-test`), and the
  one-frame AB-BA cycle (`trace-listener-drain-deadlock-test`) — none of them
  drain-vs-drain.

  ## The probe

  One listener. Thread A `dispatch-sync`es into frame ALPHA and the listener
  LATCHES inside its `:rf.event/run-start` callback. While A is latched, thread
  B `dispatch-sync`es into frame BETA — a different frame, so a different
  `:drain-lock`, so no mutual exclusion between the two drains.

  Pre-fix, B entered the same callback while A was still in flight (max
  concurrent invocations 2, `[[:enter ALPHA] [:enter BETA] [:exit BETA]]`
  observed before A was released) and BOTH callbacks ran with their frame's
  `:drain-lock` held.

  Post-fix, a drain's listener fan-out is DEFERRED to the post-drain boundary
  (`re-frame.trace/call-with-deferred-listener-delivery`, established around
  every `:drain-lock` acquire/release region) and flushed there under
  `fanout-monitor`. A's callback therefore runs with A's lock already released
  and the monitor held; B's flush BLOCKS contending for that monitor until A's
  callback returns. Max concurrent invocations 1, strict A-before-B, and every
  callback observes its frame's `:drain-lock` free.

  JVM-only (`.clj`): CLJS is single-threaded, has no monitor, cannot run two
  drains at once, and deliberately keeps the historical inline delivery — the
  overlap cannot manifest there."
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [re-frame.core :as rf]
            ;; Load-bearing require (mirrors the rf2-jl75r suite): with the epoch
            ;; artefact on the classpath the per-event settle also emits the
            ;; cascade trailers on the drainer thread while the drain-lock is
            ;; held, so the deferral seam is exercised for the trailer emits too
            ;; and not only for the dispatch-id-carrying in-run emits.
            [re-frame.epoch]
            [re-frame.frame :as frame]
            [re-frame.substrate.plain-atom :as plain-atom]
            [re-frame.test-support :as test-support]
            [re-frame.trace :as trace])
  (:import [java.util.concurrent CountDownLatch TimeUnit]))

(use-fixtures :each
  (test-support/make-reset-runtime-fixture {:adapter plain-atom/adapter}))

(def ^:private frame-alpha :wxy1c/alpha)
(def ^:private frame-beta  :wxy1c/beta)

;; Loop the deterministic latch proof so a green run is determinism, not a lucky
;; interleaving. Env-overridable for a heavier local soak.
(def ^:private iters
  (or (some-> (System/getenv "RF2_WXY1C_ITERS") Long/parseLong)
      25))

(def ^:private latch-timeout-s 10)
(def ^:private join-timeout-ms 10000)

(defn- drain-lock-held?
  "True iff `frame-id`'s `:drain-lock` is currently taken — the direct read of
  the single-drainer cell the router CAS-acquires for a drain pass and
  `frame/call-serialized-with-drain!` CAS-acquires for a cold section. Read from
  inside a listener callback this answers the rf2-wxy1c acceptance question
  literally: is arbitrary listener code running while the framework owns this
  frame's drain lock?"
  [frame-id]
  (boolean (some-> (frame/frame frame-id) :drain-lock deref)))

;; ---- Posture: dev-only, declared by `^:requires-debug` (rf2-d2841) ---------
;; Trace machinery end to end: under `-Dre-frame.debug=false` `trace/emit` is a
;; no-op, so there is no semantic residue to run under that posture, and a
;; `(when interop/debug-enabled? ...)` split -- the shape the rest of rf2-d2841
;; used -- would leave EMPTY deftests reporting green (class 2).  Every deftest
;; below is therefore TAGGED, and the production-gate lane skips the tag rather
;; than the file: the namespace is still LOADED there, so a load-time failure
;; under the gate still reddens the job, and an untagged new deftest joins that
;; lane BY DEFAULT.  Mechanism + rationale: `scripts/test-core-prod-gate.sh`.

(deftest ^:requires-debug concurrent-drains-of-different-frames-never-overlap-one-listener
  (testing (str "two simultaneous drains of DIFFERENT frames neither enter one "
                "listener concurrently nor invoke it under a held drain-lock ("
                iters " iterations)")
    (dotimes [iter iters]
      (rf/make-frame {:id frame-alpha :doc "rf2-wxy1c drain A"})
      (rf/make-frame {:id frame-beta  :doc "rf2-wxy1c drain B"})
      (rf/reg-event :wxy1c/alpha-work {:frame frame-alpha}
        (fn [{:keys [db]} _] {:db (assoc db :ran? true)}))
      (rf/reg-event :wxy1c/beta-work {:frame frame-beta}
        (fn [{:keys [db]} _] {:db (assoc db :ran? true)}))

      (let [log        (atom [])
            in-flight  (atom 0)
            max-conc   (atom 0)
            under-lock (atom [])
            a-entered  (CountDownLatch. 1)
            b-entered  (CountDownLatch. 1)
            release-a  (CountDownLatch. 1)]
        (trace/register-listener! ::probe
          (fn [ev]
            (when (= :rf.event/run-start (:operation ev))
              (when-let [f (#{frame-alpha frame-beta} (trace/frame-of ev))]
                ;; Overlap detector: record the max simultaneous invocations.
                (let [n (swap! in-flight inc)]
                  (swap! max-conc max n))
                (swap! log conj [:enter f])
                ;; Ownership detector: is the framework holding this frame's
                ;; drain-lock while our arbitrary callback runs?
                (swap! under-lock conj [f (drain-lock-held? f)])
                (if (= f frame-alpha)
                  (do (.countDown a-entered)
                      ;; Latch A mid-callback. Never released by B's delivery, so
                      ;; a serialized B cannot deadlock A.
                      (.await release-a latch-timeout-s TimeUnit/SECONDS))
                  (.countDown b-entered))
                (swap! log conj [:exit f])
                (swap! in-flight dec)))))

        (let [t1 (Thread. ^Runnable
                          (fn [] (rf/dispatch-sync [:wxy1c/alpha-work]
                                                   {:frame frame-alpha}))
                          "wxy1c-drain-alpha")
              t2 (Thread. ^Runnable
                          (fn [] (rf/dispatch-sync [:wxy1c/beta-work]
                                                   {:frame frame-beta}))
                          "wxy1c-drain-beta")]
          (.start t1)
          ;; A is now inside the listener, latched on release-a.
          (.await a-entered latch-timeout-s TimeUnit/SECONDS)
          (.start t2)
          ;; Wait until t2 has reached the fan-out seam: pre-fix it drives its
          ;; own drain's fan-out INLINE and enters the listener (b-entered fires,
          ;; the overlap is already recorded); fixed, it is BLOCKED contending
          ;; for fanout-monitor at its post-drain flush. Nothing else on t2's
          ;; path contends for a JVM monitor with t1 (the two frames own
          ;; independent routers and drain-locks), so a BLOCKED state here means
          ;; fanout-monitor contention. Deadline-bounded so a mis-started thread
          ;; cannot hang the suite.
          (let [deadline (+ (System/currentTimeMillis) 5000)]
            (loop []
              (when (and (< (System/currentTimeMillis) deadline)
                         (pos? (.getCount b-entered))
                         (not= java.lang.Thread$State/BLOCKED (.getState t2)))
                (Thread/yield)
                (recur))))
          (let [observed-before-release @log]
            (.countDown release-a)
            (.join t1 join-timeout-ms)
            (.join t2 join-timeout-ms)
            (trace/unregister-listener! ::probe)

            (is (not (.isAlive t1))
                (str "iter " iter ": frame ALPHA's dispatch-sync never completed"))
            (is (not (.isAlive t2))
                (str "iter " iter ": frame BETA's dispatch-sync never completed"))
            (is (= 1 @max-conc)
                (str "iter " iter ": two concurrent frame drains overlapped the "
                     "SAME listener callback (max concurrent invocations "
                     @max-conc "); each drain owns only its OWN frame's "
                     ":drain-lock, so drain-lock ownership alone cannot "
                     "serialize the fan-out. Observed before release: "
                     (pr-str observed-before-release)))
            (is (= [[:enter frame-alpha]] observed-before-release)
                (str "iter " iter ": frame BETA's drain reached the listener "
                     "while frame ALPHA's callback was still in flight; expected "
                     "only ALPHA's :enter before the release, got "
                     (pr-str observed-before-release)))
            (is (= [[:enter frame-alpha] [:exit frame-alpha]
                    [:enter frame-beta] [:exit frame-beta]]
                   @log)
                (str "iter " iter ": expected strict ALPHA-before-BETA delivery, "
                     "got " (pr-str @log)))
            (is (= [[frame-alpha false] [frame-beta false]] @under-lock)
                (str "iter " iter ": a listener callback ran while the framework "
                     "owned the frame's :drain-lock; arbitrary listener code "
                     "must be invoked at the post-drain boundary, got "
                     (pr-str @under-lock)))))))))
