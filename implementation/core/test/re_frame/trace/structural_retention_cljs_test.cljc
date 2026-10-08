(ns re-frame.trace.structural-retention-cljs-test
  "Retentionless structural delivery (`re-frame.trace/call-with-structural-delivery`).

  An obsolete incarnation A's terminal facts carry A's inherited dispatch-id
  and the bare frame id a same-id successor B now owns, so a ring push would
  land them in B's ring. Structural delivery therefore streams each fact live
  to every listener exactly once and retains it in no ring. The deterministic
  same-id A→B scenario lives in `frame-destroy-incarnation-jvm-test`; this
  suite pins the boundary synchronously on JVM and CLJS.

  Per Spec 009 §Per-frame trace rings and §Listener invocation rules."
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [re-frame.trace :as rf.trace]
            [re-frame.trace.tooling :as rf.trace.tooling]))

(defn- isolate [f]
  (rf.trace.tooling/clear-listeners!)
  (rf.trace.tooling/clear-trace-rings!)
  (try (f)
       (finally
         (rf.trace.tooling/clear-trace-rings!)
         (rf.trace.tooling/clear-listeners!))))

(use-fixtures :each isolate)

(defn- flat [frame-id]
  (rf.trace.tooling/trace-buffer frame-id {:flat true}))

(defn- ops [evs] (mapv :operation evs))

;; Every deftest is ^:requires-debug: emit! is a no-op under
;; -Dre-frame.debug=false (see scripts/test-core-prod-gate.sh).

(deftest ^:requires-debug ordinary-emit-is-retained-structural-emit-is-not
  (testing "ring retention is gated ONLY by structural delivery; live delivery is not"
    (let [live (atom [])
          tags {:frame :rf2-244/sync-frame :rf.trace/dispatch-id :rf2-244/run-1}]
      (rf.trace.tooling/register-listener! ::retention-live (fn [ev] (swap! live conj ev)))
      (rf.trace/emit! :test :rf2-244/ordinary tags)
      (let [ring-before (flat :rf2-244/sync-frame)]
        (is (= [:rf2-244/ordinary] (ops ring-before)) "control: an ordinary emit is retained")
        (rf.trace/call-with-structural-delivery #(rf.trace/emit! :test :rf2-244/structural tags))
        (is (= [:rf2-244/ordinary :rf2-244/structural] (ops @live))
            "both reach the live listener exactly once")
        (is (= ring-before (flat :rf2-244/sync-frame)) "the structural emit is not retained")))))

;; The listener fan-out runs inside the outer structural scope. A listener's
;; own nested emit must run under ORDINARY scope — otherwise work it does for
;; another frame C would stream live but never reach C's ring, epoch capture
;; or no-emit policy. Restoring the ambient structural bindings during the
;; fan-out leaves C's ring empty and fails this test.
(deftest ^:requires-debug listener-triggered-nested-emit-runs-under-normal-scope
  (rf.trace.tooling/register-listener! ::vf2qke-dispatcher
    (fn [ev]
      (when (= :rf2-vf2qke/a-structural (:operation ev))
        (rf.trace/emit! :test :rf2-vf2qke/c-nested
                        {:frame :rf2-vf2qke/c-frame :rf.trace/dispatch-id :rf2-vf2qke/c-run}))))
  (rf.trace/call-with-structural-delivery
    #(rf.trace/emit! :test :rf2-vf2qke/a-structural
                     {:frame :rf2-vf2qke/a-frame :rf.trace/dispatch-id :rf2-vf2qke/a-run}))
  (is (= [:rf2-vf2qke/c-nested] (ops (flat :rf2-vf2qke/c-frame)))
      "C's listener-triggered nested emit is retained in C's ring, and is all it holds"))

(deftest ^:requires-debug explicitly-nested-structural-delivery-stays-retentionless
  (testing "a listener that re-requests structural delivery for its nested emit keeps it retentionless"
    (let [live (atom [])]
      (rf.trace.tooling/register-listener! ::vf2qke-structural-dispatcher
        (fn [ev]
          (swap! live conj ev)
          (when (= :rf2-vf2qke/a2-structural (:operation ev))
            (rf.trace/call-with-structural-delivery
              #(rf.trace/emit! :test :rf2-vf2qke/c2-nested
                               {:frame :rf2-vf2qke/c2-frame :rf.trace/dispatch-id :rf2-vf2qke/c2-run})))))
      (rf.trace/call-with-structural-delivery
        #(rf.trace/emit! :test :rf2-vf2qke/a2-structural
                         {:frame :rf2-vf2qke/a2-frame :rf.trace/dispatch-id :rf2-vf2qke/a2-run}))
      (is (some #(= :rf2-vf2qke/c2-nested (:operation %)) @live)
          "control: the nested emit still streamed live")
      (is (empty? (flat :rf2-vf2qke/c2-frame))))))
