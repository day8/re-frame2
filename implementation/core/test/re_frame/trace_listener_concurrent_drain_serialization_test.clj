(ns re-frame.trace-listener-concurrent-drain-serialization-test
  "Two DIFFERENT frames draining concurrently never enter one trace listener at
  once, and no listener callback runs while the framework owns a frame's
  `:drain-lock` (Spec 009 §The listener contract).

  Each drain owns only its own frame's lock, so drain-lock ownership cannot
  serialize the fan-out. A drain's listener delivery is deferred to the
  post-drain boundary (`re-frame.trace/call-with-deferred-listener-delivery`)
  and flushed under the process-wide `fanout-monitor`.

  The probe: thread A drains frame ALPHA and its listener callback latches on
  `:rf.event/run-start`; thread B then drains frame BETA. B's flush must block
  on the monitor until A's callback returns, and both callbacks must see their
  frame's `:drain-lock` free. JVM-only: CLJS has one thread and no monitor."
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [re-frame.core :as rf]
            ;; With epoch loaded the per-event settle also emits its trailers
            ;; on the drainer thread under the lock, so they take the deferral
            ;; seam too.
            [re-frame.epoch]
            [re-frame.frame :as rf.frame]
            [re-frame.substrate.plain-atom :as rf.substrate.plain-atom]
            [re-frame.test-support :as rf.test-support]
            [re-frame.trace.tooling :as rf.trace.tooling]
            [re-frame.trace :as rf.trace])
  (:import [java.util.concurrent CountDownLatch TimeUnit]))

(use-fixtures :each
  (rf.test-support/make-reset-runtime-fixture {:adapter rf.substrate.plain-atom/adapter}))

(def ^:private frame-alpha :wxy1c/alpha)
(def ^:private frame-beta  :wxy1c/beta)

;; Repeated so a green run is not one lucky interleaving. Env-overridable soak.
(def ^:private iters
  (or (some-> (System/getenv "RF2_WXY1C_ITERS") Long/parseLong)
      25))

(def ^:private latch-timeout-s 10)
(def ^:private join-timeout-ms 10000)

(defn- drain-lock-held? [frame-id]
  (boolean (some-> (rf.frame/frame frame-id) :drain-lock deref)))

;; Every deftest is `^:requires-debug`: the suite drives the dev trace end to
;; end (see scripts/test-core-prod-gate.sh).

(deftest ^:requires-debug concurrent-drains-of-different-frames-never-overlap-one-listener
  (testing (str "two simultaneous drains of DIFFERENT frames neither enter one "
                "listener concurrently nor invoke it under a held drain-lock ("
                iters " iterations)")
    (dotimes [iter iters]
      (rf/make-frame {:id frame-alpha :doc "drain A"})
      (rf/make-frame {:id frame-beta  :doc "drain B"})
      (rf/reg-event :wxy1c/alpha-work {:frame frame-alpha}
        (fn [{:keys [db]} _] {:db (assoc db :ran? true)}))
      (rf/reg-event :wxy1c/beta-work {:frame frame-beta}
        (fn [{:keys [db]} _] {:db (assoc db :ran? true)}))

      (let [log        (atom [])
            under-lock (atom [])
            a-entered  (CountDownLatch. 1)
            b-entered  (CountDownLatch. 1)
            release-a  (CountDownLatch. 1)]
        (rf.trace.tooling/register-listener! ::probe
          (fn [ev]
            (when (= :rf.event/run-start (:operation ev))
              (when-let [f (#{frame-alpha frame-beta} (rf.trace/frame-of ev))]
                (swap! log conj [:enter f])
                (swap! under-lock conj [f (drain-lock-held? f)])
                (if (= f frame-alpha)
                  (do (.countDown a-entered)
                      (.await release-a latch-timeout-s TimeUnit/SECONDS))
                  (.countDown b-entered))
                (swap! log conj [:exit f])))))

        (let [t1 (Thread. ^Runnable
                          (fn [] (rf/dispatch-sync [:wxy1c/alpha-work]
                                                   {:frame frame-alpha}))
                          "wxy1c-drain-alpha")
              t2 (Thread. ^Runnable
                          (fn [] (rf/dispatch-sync [:wxy1c/beta-work]
                                                   {:frame frame-beta}))
                          "wxy1c-drain-beta")]
          (.start t1)
          (.await a-entered latch-timeout-s TimeUnit/SECONDS)
          (.start t2)
          ;; Wait until B has either entered the listener (overlap) or is
          ;; BLOCKED on the monitor at its post-drain flush — nothing else on
          ;; B's path contends with A for a JVM monitor.
          (let [deadline (+ (System/currentTimeMillis) 5000)]
            (loop []
              (when (and (< (System/currentTimeMillis) deadline)
                         (pos? (.getCount b-entered))
                         (not= java.lang.Thread$State/BLOCKED (.getState t2)))
                (Thread/yield)
                (recur))))
          (.countDown release-a)
          (.join t1 join-timeout-ms)
          (.join t2 join-timeout-ms)
          (rf.trace.tooling/unregister-listener! ::probe)

          (is (not (.isAlive t1))
              (str "iter " iter ": frame ALPHA's dispatch-sync never completed"))
          (is (not (.isAlive t2))
              (str "iter " iter ": frame BETA's dispatch-sync never completed"))
          ;; A BETA entry while ALPHA was latched would land before ALPHA's :exit.
          (is (= [[:enter frame-alpha] [:exit frame-alpha]
                  [:enter frame-beta] [:exit frame-beta]]
                 @log)
              (str "iter " iter ": expected strict ALPHA-before-BETA delivery"))
          (is (= [[frame-alpha false] [frame-beta false]] @under-lock)
              (str "iter " iter ": a listener callback ran while the framework "
                   "owned the frame's :drain-lock")))))))
