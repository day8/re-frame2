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
            [re-frame.trace :as rf.trace]
            [re-frame.trace.tooling :as rf.trace.tooling]))

(defn- flat [frame-id]
  (rf.trace.tooling/trace-buffer frame-id {:flat true}))

;; ---- Posture: dev-only, declared by `^:requires-debug` (rf2-d2841) ---------
;; Trace machinery end to end: under `-Dre-frame.debug=false` `rf.trace/emit` is a
;; no-op, so there is no semantic residue to run under that posture, and a
;; `(when interop/debug-enabled? ...)` split -- the shape the rest of rf2-d2841
;; used -- would leave EMPTY deftests reporting green (class 2).  Every deftest
;; below is therefore TAGGED, and the production-gate lane skips the tag rather
;; than the file: the namespace is still LOADED there, so a load-time failure
;; under the gate still reddens the job, and an untagged new deftest joins that
;; lane BY DEFAULT.  Mechanism + rationale: `scripts/test-core-prod-gate.sh`.

(deftest ^:requires-debug ordinary-emit-is-retained-structural-emit-is-not
  (testing "ring retention is gated ONLY by structural delivery; live delivery is not"
    (let [fid  :rf2-244/sync-frame
          did  :rf2-244/run-1
          live (atom [])]
      (rf.trace/clear-listeners!)
      (rf.trace.tooling/clear-trace-rings!)
      (rf.trace/register-listener! ::retention-live (fn [ev] (swap! live conj ev)))
      (try
        ;; Control: an ordinary (non-structural) emit carrying a frame id + a
        ;; dispatch-id IS retained in that frame's ring.
        (rf.trace/emit! :test :rf2-244/ordinary
                     {:frame fid :rf.trace/dispatch-id did})
        (is (= 1 (count (flat fid)))
            "an ordinary emit is retained in the frame's ring")
        (is (= 1 (count @live))
            "and the ordinary emit reached the live listener")

        ;; Boundary: a structural emit of the SAME shape reaches the live
        ;; listener but is NOT retained in the ring.
        (let [ring-before (flat fid)
              live-before (count @live)]
          (rf.trace/call-with-structural-delivery
            #(rf.trace/emit! :test :rf2-244/structural
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
          (rf.trace/unregister-listener! ::retention-live)
          (rf.trace.tooling/clear-trace-rings!)
          (rf.trace/clear-listeners!))))))

(deftest ^:requires-debug structural-emit-never-allocates-a-successor-ring
  (testing "a structural emit tagged with a never-emitted frame id creates no ring"
    ;; This is the same-id-successor case in miniature: `fid` stands in for B's
    ;; freshly-installed id, which has emitted nothing of its own yet. A's
    ;; terminal fact — carrying that bare id under structural delivery — must
    ;; not conjure a ring keyed by it. Before the fix the push allocated one.
    (let [fid  :rf2-244/fresh-successor
          did  :rf2-244/a-inherited
          live (atom [])]
      (rf.trace/clear-listeners!)
      (rf.trace.tooling/clear-trace-rings!)
      (rf.trace/register-listener! ::fresh-live (fn [ev] (swap! live conj ev)))
      (try
        (is (empty? (flat fid))
            "no ring exists for the fresh successor id yet")
        (rf.trace/call-with-structural-delivery
          #(rf.trace/emit! :test :rf2-244/a-terminal
                        {:frame fid :rf.trace/dispatch-id did}))
        (is (= 1 (count @live))
            "A's terminal fact still reaches the live listener exactly once")
        (is (empty? (flat fid))
            "no ring is allocated for the successor id by A's structural fact")
        (finally
          (rf.trace/unregister-listener! ::fresh-live)
          (rf.trace.tooling/clear-trace-rings!)
          (rf.trace/clear-listeners!))))))

;; ---- rf2-vf2qke — structural scope must not taint listener-triggered work ----
;;
;; #5865 binds the structural-delivery flags (epoch-capture / frame-policy /
;; ring-retention all false) around the ENTIRE synchronous outer emit — but the
;; public tooling listener fan-out runs INSIDE that dynamic scope. A listener
;; reacting to A's structural terminal fact that performs its own legitimate
;; nested emission (a direct `emit!` or a `dispatch`) into another frame C or
;; same-id successor B had that nested work INHERIT A's retentionless scope: it
;; streamed live but was never retained in C's own ring, never captured, and
;; bypassed C's own frame-no-emit policy. The merged fixtures above use PASSIVE
;; atom listeners and miss this — the taint only manifests when a listener EMITS.
;;
;; The fix captures the outer envelope's structural decisions BEFORE the listener
;; fan-out (the outer ring push already received the captured retain?), then
;; restores ordinary delivery defaults while invoking the public tooling
;; listeners, so a listener's own emitted work runs under normal scope. An
;; explicitly nested `call-with-structural-delivery` can still re-request
;; structural semantics.

(deftest ^:requires-debug listener-triggered-nested-emit-runs-under-normal-scope
  (testing "a public listener reacting to A's structural terminal fact that emits
            legitimate nested work into unrelated frame C: the nested emit gets
            NORMAL ring retention, not A's outer retentionless scope (rf2-vf2qke)"
    ;; Mutation tooth: restoring the ambient structural bindings during the
    ;; listener fan-out (i.e. NOT restoring ordinary defaults) leaves C's nested
    ;; emit retentionless and this fixture fails — C's ring stays empty.
    (let [a-fid :rf2-vf2qke/a-frame
          a-did :rf2-vf2qke/a-run
          c-fid :rf2-vf2qke/c-frame
          c-did :rf2-vf2qke/c-run
          live  (atom [])
          fired? (atom false)]
      (rf.trace/clear-listeners!)
      (rf.trace.tooling/clear-trace-rings!)
      ;; A public tooling listener that, on seeing A's structural terminal fact,
      ;; performs legitimate nested work: a direct public emit! into UNRELATED
      ;; frame C carrying C's own dispatch-id. That nested emit is ordinary work,
      ;; not part of A's structural delivery, so it must land in C's ring.
      (rf.trace/register-listener! ::vf2qke-dispatcher
        (fn [ev]
          (swap! live conj ev)
          (when (and (= :rf2-vf2qke/a-structural (:operation ev))
                     (compare-and-set! fired? false true))
            (rf.trace/emit! :test :rf2-vf2qke/c-nested
                         {:frame c-fid :rf.trace/dispatch-id c-did}))))
      (try
        (is (empty? (flat c-fid)) "C has no ring before A's terminal fact")
        ;; A emits its terminal fact under retentionless structural delivery.
        (rf.trace/call-with-structural-delivery
          #(rf.trace/emit! :test :rf2-vf2qke/a-structural
                        {:frame a-fid :rf.trace/dispatch-id a-did}))
        ;; A's structural fact reached listeners once and is never retained.
        (is (= 1 (count (filter #(= :rf2-vf2qke/a-structural (:operation %)) @live)))
            "A's structural fact reached the listener exactly once")
        (is (empty? (filter #(= :rf2-vf2qke/a-structural (:operation %)) (flat a-fid)))
            "A's structural fact is NOT retained in A's ring")
        ;; THE FIX: C's listener-triggered nested emit IS retained in C's ring —
        ;; legitimate nested work runs under normal (non-structural) scope.
        (is (= 1 (count (flat c-fid)))
            "C's listener-triggered nested emit is retained in C's ring")
        (is (= :rf2-vf2qke/c-nested (:operation (first (flat c-fid))))
            "C's ring holds exactly the nested emit")
        ;; And the nested emit reached the live stream too.
        (is (some #(= :rf2-vf2qke/c-nested (:operation %)) @live)
            "the nested emit also streamed live")
        (finally
          (rf.trace/unregister-listener! ::vf2qke-dispatcher)
          (rf.trace.tooling/clear-trace-rings!)
          (rf.trace/clear-listeners!))))))

(deftest ^:requires-debug explicitly-nested-structural-delivery-stays-retentionless
  (testing "a listener that itself re-requests structural delivery for its nested
            emit keeps retentionless semantics — restoring ordinary defaults for
            the fan-out does not defeat an explicit nested request (rf2-vf2qke)"
    (let [a-fid :rf2-vf2qke/a2-frame
          a-did :rf2-vf2qke/a2-run
          c-fid :rf2-vf2qke/c2-frame
          c-did :rf2-vf2qke/c2-run
          live  (atom [])
          fired? (atom false)]
      (rf.trace/clear-listeners!)
      (rf.trace.tooling/clear-trace-rings!)
      (rf.trace/register-listener! ::vf2qke-structural-dispatcher
        (fn [ev]
          (swap! live conj ev)
          (when (and (= :rf2-vf2qke/a2-structural (:operation ev))
                     (compare-and-set! fired? false true))
            ;; The listener EXPLICITLY requests structural delivery for its own
            ;; nested emit — which must stay retentionless despite the fan-out
            ;; running under restored ordinary defaults.
            (rf.trace/call-with-structural-delivery
              #(rf.trace/emit! :test :rf2-vf2qke/c2-nested
                            {:frame c-fid :rf.trace/dispatch-id c-did})))))
      (try
        (rf.trace/call-with-structural-delivery
          #(rf.trace/emit! :test :rf2-vf2qke/a2-structural
                        {:frame a-fid :rf.trace/dispatch-id a-did}))
        (is (some #(= :rf2-vf2qke/c2-nested (:operation %)) @live)
            "the explicitly-structural nested emit still streamed live")
        (is (empty? (flat c-fid))
            "the explicitly-nested structural emit is NOT retained in C's ring")
        (finally
          (rf.trace/unregister-listener! ::vf2qke-structural-dispatcher)
          (rf.trace.tooling/clear-trace-rings!)
          (rf.trace/clear-listeners!))))))

(deftest ^:requires-debug error-emit-under-structural-delivery-is-also-retentionless
  (testing "emit-error! honours the retentionless boundary too (terminal diagnostics)"
    ;; A's terminal diagnostics (`:rf.epoch.cb/listener-exception`,
    ;; `:rf.warning/teardown-hook-exception`) travel through `emit-error!`
    ;; under structural delivery. They must reach listeners but never a ring.
    (let [fid  :rf2-244/err-frame
          did  :rf2-244/err-run
          live (atom [])]
      (rf.trace/clear-listeners!)
      (rf.trace.tooling/clear-trace-rings!)
      (rf.trace/register-listener! ::err-live (fn [ev] (swap! live conj ev)))
      (try
        ;; Control: an ordinary error emit is retained.
        (rf.trace/emit-error! :rf2-244/ordinary-error
                           {:frame fid :rf.trace/dispatch-id did})
        (is (= 1 (count (flat fid)))
            "an ordinary error emit is retained in the ring")
        (let [ring-before (flat fid)]
          (rf.trace/call-with-structural-delivery
            #(rf.trace/emit-error! :rf2-244/structural-error
                                {:frame fid :rf.trace/dispatch-id did}))
          (is (= 2 (count @live))
              "the structural error still reaches the live listener")
          (is (= ring-before (flat fid))
              "the structural error is NOT retained in the ring"))
        (finally
          (rf.trace/unregister-listener! ::err-live)
          (rf.trace.tooling/clear-trace-rings!)
          (rf.trace/clear-listeners!))))))
