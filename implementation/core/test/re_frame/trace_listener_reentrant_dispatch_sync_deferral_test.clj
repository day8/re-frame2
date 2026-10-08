(ns re-frame.trace-listener-reentrant-dispatch-sync-deferral-test
  "A trace listener that calls `dispatch-sync` from inside an outer fan-out
  must not have the nested drain's traces fanned out while that frame's
  `:drain-lock` is held.

  `re-frame.trace.tooling/deliver-to-tooling!` checks the post-drain deferral
  scope BEFORE the reentrant `*fanout-ctx*` fast path; taking the fast path
  first would drive the outer schedule inline and run listener code under the
  nested drain's lock. That silently misbehaves rather than throwing, so the
  probe records whether the lock was held at each callback. Same-thread and
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
;; end (see scripts/test-core-prod-gate.sh).

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
    ;; Reacts to a clean emit (so `*fanout-ctx*` is bound) by draining
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
