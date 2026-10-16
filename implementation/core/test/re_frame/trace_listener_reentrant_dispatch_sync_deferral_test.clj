(ns re-frame.trace-listener-reentrant-dispatch-sync-deferral-test
  "A trace listener that calls `dispatch-sync` from inside an outer fan-out
  must not have the nested drain's traces fanned out while that frame's
  `:drain-lock` is held.

  `re-frame.trace.tooling/deliver-to-tooling!` defers every drain-owned emit,
  and the post-drain flush appends the batch to the outer fan-out's queue, so
  the nested drain's traces reach listeners only after the lock is released.
  Running listener code under the lock silently misbehaves rather than
  throwing, so the probe records whether the lock was held at each callback. Same-thread and
  deterministic. JVM-only: the lock read is meaningful only where a real lock
  cell exists; `trace-listener-post-drain-settled-state-cljs-test` carries the
  cross-host settled-state contract."
  (:require [clojure.test :refer [deftest is use-fixtures]]
            [re-frame.core :as rf]
            ;; With epoch loaded the per-event settle also emits its trailers
            ;; under the lock, so they take the deferral seam too.
            [re-frame.epoch]
            [re-frame.frame :as rf.frame]
            [re-frame.substrate.plain-atom :as rf.substrate.plain-atom]
            [re-frame.test-support :as rf.test-support]
            [re-frame.trace.tooling :as rf.trace.tooling]
            [re-frame.trace :as rf.trace]))

(use-fixtures :each
  (rf.test-support/make-reset-runtime-fixture {:adapter rf.substrate.plain-atom/adapter}))

(defn- drain-lock-held? [frame-id]
  (boolean (some-> (rf.frame/frame frame-id) :drain-lock deref)))

;; Every deftest is `^:requires-debug`: the suite drives the dev trace end to
;; end (see the `:prod-gate` alias in deps.edn).

(deftest ^:requires-debug listener-initiated-dispatch-sync-never-runs-listeners-under-drain-lock
  (rf/reg-event :6t6qk/settle (fn [_ _] {}))
  (let [observed (atom [])]
    ;; Records [op lock-held?] for each run emit of the nested :rf/default drain.
    (rf.trace.tooling/register-listener! ::probe
      (fn [ev]
        (when (and (= :rf/default (rf.trace/frame-of ev))
                   (contains? #{:rf.event/run-start :rf.event/run-end}
                              (:operation ev)))
          (swap! observed conj
                 [(:operation ev) (drain-lock-held? :rf/default)]))))
    ;; Reacts to a clean emit (so a fan-out queue is active) by draining
    ;; :rf/default on this thread.
    (rf.trace.tooling/register-listener! ::trigger
      (fn [ev]
        (when (= :6t6qk/trigger (:operation ev))
          (rf/dispatch-sync [:6t6qk/settle] {:frame :rf/default}))))
    (try
      (rf.trace/emit! :info :6t6qk/trigger {})
      (is (= #{[:rf.event/run-start false] [:rf.event/run-end false]}
             (set @observed))
          "a trace listener ran while :rf/default's :drain-lock was held")
      (finally
        (rf.trace.tooling/unregister-listener! ::probe)
        (rf.trace.tooling/unregister-listener! ::trigger)))))

(deftest ^:requires-debug listener-scheduled-async-drain-reaches-listeners
  ;; JVM `next-tick` conveys dynamic bindings to the executor thread, so the
  ;; async drain a listener schedules runs with the listener's bindings. Its
  ;; traces must open their own fan-out there: appended to the scheduling
  ;; thread's queue, whose loop has already finished, they would reach no
  ;; listener.
  (rf/reg-event :6t6qk/async (fn [_ _] {}))
  (let [run-end (promise)]
    (rf.trace.tooling/register-listener! ::trigger
      (fn [ev]
        (when (= :6t6qk/async-trigger (:operation ev))
          (rf/dispatch [:6t6qk/async] {:frame :rf/default}))))
    (rf.trace.tooling/register-listener! ::probe
      (fn [ev]
        (when (and (= :rf.event/run-end (:operation ev))
                   (= :6t6qk/async (first (-> ev :tags :rf.event/v))))
          (deliver run-end true))))
    (try
      (rf.trace/emit! :info :6t6qk/async-trigger {})
      (is (true? (deref run-end 5000 false))
          "the listener-scheduled async drain's run-end never reached a listener")
      (finally
        (rf.trace.tooling/unregister-listener! ::probe)
        (rf.trace.tooling/unregister-listener! ::trigger)))))
