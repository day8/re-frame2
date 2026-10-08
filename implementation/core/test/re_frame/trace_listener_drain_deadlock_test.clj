(ns re-frame.trace-listener-drain-deadlock-test
  "A trace listener may call `dispatch-sync`, and the drain emits traces while
  holding a frame's `:drain-lock`, so the JVM `fanout-monitor` must never be
  taken for a drain-owned emit. Otherwise:

    - T1 does a clean `emit!`, holds `fanout-monitor`, enters a listener that
      `dispatch-sync`s into frame F and spin-waits on F's `:drain-lock`;
    - the drainer holds F's `:drain-lock`, reaches an in-run
      `:rf.event/run-start` emit and blocks on `fanout-monitor`.

  A drain-owned emit is instead appended and delivered at the post-drain
  boundary (`re-frame.trace/call-with-deferred-listener-delivery`), once the lock
  is down. This suite drives that exact interleaving and requires it to finish
  within a bounded timeout, with the listener-dispatched event settling once.

  The drainer side runs on the ASYNC drain: a `dispatch-sync` marks the router
  `:in-sync-drain?`, which turns a concurrent cross-thread `dispatch-sync` into
  a rejected nested-sync that never spins on the lock. JVM-only: CLJS has one
  thread and no monitor."
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [re-frame.core :as rf]
            ;; With epoch loaded the per-event settle emits its trailers with a
            ;; nil dispatch-id, still on the drainer thread under the lock, so
            ;; the deferral seam is exercised for those emits too.
            [re-frame.epoch]
            [re-frame.substrate.plain-atom :as rf.substrate.plain-atom]
            [re-frame.test-support :as rf.test-support]
            [re-frame.trace.tooling :as rf.trace.tooling]
            [re-frame.trace :as rf.trace])
  (:import [java.util.concurrent CountDownLatch TimeUnit]))

(use-fixtures :each
  (rf.test-support/make-reset-runtime-fixture {:adapter rf.substrate.plain-atom/adapter}))

;; Repeated so a green run is not one lucky interleaving. Env-overridable soak.
(def ^:private iters
  (or (some-> (System/getenv "RF2_JL75R_ITERS") Long/parseLong)
      25))

;; A deadlocked run leaves the threads alive forever; the bounded waits turn
;; that hang into a red assertion.
(def ^:private join-timeout-ms 10000)
(def ^:private latch-timeout-s 10)

;; Every deftest is `^:requires-debug`: the suite drives the dev trace end to
;; end (see scripts/test-core-prod-gate.sh).

(deftest ^:requires-debug fanout-monitor-drain-lock-ab-ba-does-not-deadlock
  (testing (str "T1 (fanout-monitor -> listener -> dispatch-sync F) against the "
                "async drainer (F.:drain-lock -> run-start emit) completes "
                "bounded and the dispatched event settles exactly once ("
                iters " iterations)")
    (dotimes [iter iters]
      (let [t1-event-runs (atom 0)
            drain-second  (atom 0)
            l-fired?      (atom false)
            drainer-in    (CountDownLatch. 1)
            drainer-go    (CountDownLatch. 1)]

        ;; Runs on the async-drain executor, holding :rf/default's drain-lock.
        (rf/reg-event :jl75r/drain-first
          (fn [{:keys [db]} _]
            (.countDown drainer-in)
            ;; Hold the lock until T1 is inside its listener, then queue a
            ;; second event whose run-start is the contended emit.
            (.await drainer-go latch-timeout-s TimeUnit/SECONDS)
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

        (rf.trace.tooling/register-listener! ::jl75r
          (fn [ev]
            ;; Only T1's clean trigger emit, once. On T1, holding the monitor:
            ;; release the drainer, then spin on its drain-lock.
            (when (and (= :jl75r/t1-trigger (:operation ev))
                       (compare-and-set! l-fired? false true))
              (.countDown drainer-go)
              (rf/dispatch-sync [:jl75r/t1-event] {:frame :rf/default}))))

        (rf/dispatch [:jl75r/drain-first] {:frame :rf/default})
        (.await drainer-in latch-timeout-s TimeUnit/SECONDS)

        (let [t1 (Thread. ^Runnable
                          (fn [] (rf.trace/emit! :info :jl75r/t1-trigger {}))
                          "jl75r-t1-listener")]
          (.start t1)
          (.join t1 join-timeout-ms)
          (rf.trace.tooling/unregister-listener! ::jl75r)

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
