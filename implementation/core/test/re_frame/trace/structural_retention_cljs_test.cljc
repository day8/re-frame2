(ns re-frame.trace.structural-retention-cljs-test
  "Synchronous retention-boundary coverage for retentionless structural
  delivery (rf2-vxgfnd.244) — the extension #5850's epoch-only fence missed.

  #5850 made an obsolete incarnation A's terminal facts bypass a same-id
  successor B's epoch CAPTURE and B's no-emit POLICY (via
  `re-frame.trace/call-with-structural-delivery`). But the per-frame trace
  RING was still written: A's terminal facts carry A's inherited dispatch-id
  and A's bare frame id, so `re-frame.trace.tooling/push-to-ring!` appended
  them onto the CURRENT ring for that id — which is B's ring once B is
  installed under the shared id.

  The fix makes structural delivery RETENTIONLESS: the fact still streams live
  to every registered trace listener exactly once, but no per-frame ring
  retains it. This suite pins that boundary directly and synchronously (no
  threads), on BOTH JVM and CLJS — the ring + emit substrate is
  platform-agnostic. The deterministic same-id A→B pause/resume scenario lives
  in the JVM `frame-destroy-incarnation-jvm-test`.

  Per Spec 009 §Per-frame trace rings and §Listener invocation rules."
  (:require [clojure.test :refer [deftest is testing]]
            [re-frame.trace :as trace]
            [re-frame.trace.tooling :as trace-tooling]))

(defn- flat [frame-id]
  (trace-tooling/trace-buffer frame-id {:flat true}))

(deftest ordinary-emit-is-retained-structural-emit-is-not
  (testing "ring retention is gated ONLY by structural delivery; live delivery is not"
    (let [fid  :rf2-244/sync-frame
          did  :rf2-244/run-1
          live (atom [])]
      (trace/clear-listeners!)
      (trace-tooling/clear-trace-rings!)
      (trace/register-listener! ::retention-live (fn [ev] (swap! live conj ev)))
      (try
        ;; Control: an ordinary (non-structural) emit carrying a frame id + a
        ;; dispatch-id IS retained in that frame's ring.
        (trace/emit! :test :rf2-244/ordinary
                     {:frame fid :rf.trace/dispatch-id did})
        (is (= 1 (count (flat fid)))
            "an ordinary emit is retained in the frame's ring")
        (is (= 1 (count @live))
            "and the ordinary emit reached the live listener")

        ;; Boundary: a structural emit of the SAME shape reaches the live
        ;; listener but is NOT retained in the ring.
        (let [ring-before (flat fid)
              live-before (count @live)]
          (trace/call-with-structural-delivery
            #(trace/emit! :test :rf2-244/structural
                          {:frame fid :rf.trace/dispatch-id did}))
          (is (= (inc live-before) (count @live))
              "the structural emit still reaches the live listener exactly once")
          (is (= :rf2-244/structural (:operation (last @live)))
              "the live listener saw the structural event")
          (is (= ring-before (flat fid))
              "the structural emit is NOT retained in the frame's ring")
          (is (empty? (filter #(= :rf2-244/structural (:operation %)) (flat fid)))
              "the structural operation never appears in the ring"))
        (finally
          (trace/unregister-listener! ::retention-live)
          (trace-tooling/clear-trace-rings!)
          (trace/clear-listeners!))))))

(deftest structural-emit-never-allocates-a-successor-ring
  (testing "a structural emit tagged with a never-emitted frame id creates no ring"
    ;; This is the same-id-successor case in miniature: `fid` stands in for B's
    ;; freshly-installed id, which has emitted nothing of its own yet. A's
    ;; terminal fact — carrying that bare id under structural delivery — must
    ;; not conjure a ring keyed by it. Before the fix the push allocated one.
    (let [fid  :rf2-244/fresh-successor
          did  :rf2-244/a-inherited
          live (atom [])]
      (trace/clear-listeners!)
      (trace-tooling/clear-trace-rings!)
      (trace/register-listener! ::fresh-live (fn [ev] (swap! live conj ev)))
      (try
        (is (empty? (flat fid))
            "no ring exists for the fresh successor id yet")
        (trace/call-with-structural-delivery
          #(trace/emit! :test :rf2-244/a-terminal
                        {:frame fid :rf.trace/dispatch-id did}))
        (is (= 1 (count @live))
            "A's terminal fact still reaches the live listener exactly once")
        (is (empty? (flat fid))
            "no ring is allocated for the successor id by A's structural fact")
        (finally
          (trace/unregister-listener! ::fresh-live)
          (trace-tooling/clear-trace-rings!)
          (trace/clear-listeners!))))))

(deftest error-emit-under-structural-delivery-is-also-retentionless
  (testing "emit-error! honours the retentionless boundary too (terminal diagnostics)"
    ;; A's terminal diagnostics (`:rf.epoch.cb/listener-exception`,
    ;; `:rf.warning/teardown-hook-exception`) travel through `emit-error!`
    ;; under structural delivery. They must reach listeners but never a ring.
    (let [fid  :rf2-244/err-frame
          did  :rf2-244/err-run
          live (atom [])]
      (trace/clear-listeners!)
      (trace-tooling/clear-trace-rings!)
      (trace/register-listener! ::err-live (fn [ev] (swap! live conj ev)))
      (try
        ;; Control: an ordinary error emit is retained.
        (trace/emit-error! :rf2-244/ordinary-error
                           {:frame fid :rf.trace/dispatch-id did})
        (is (= 1 (count (flat fid)))
            "an ordinary error emit is retained in the ring")
        (let [ring-before (flat fid)]
          (trace/call-with-structural-delivery
            #(trace/emit-error! :rf2-244/structural-error
                                {:frame fid :rf.trace/dispatch-id did}))
          (is (= 2 (count @live))
              "the structural error still reaches the live listener")
          (is (= ring-before (flat fid))
              "the structural error is NOT retained in the ring"))
        (finally
          (trace/unregister-listener! ::err-live)
          (trace-tooling/clear-trace-rings!)
          (trace/clear-listeners!))))))
