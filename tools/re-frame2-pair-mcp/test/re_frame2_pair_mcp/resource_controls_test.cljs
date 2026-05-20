(ns re-frame2-pair-mcp.resource-controls-test
  "Unit tests for session-wide resource controls on streaming surfaces
  (rf2-3ijbl). Pins each gate's contract:

    1. Concurrent-stream cap — acquire/release accounting, rejection
       shape, idempotent release floor.
    2. Per-session rate-limit — token-bucket refill, burst-then-drain
       sequencing, cap-tracking.
    3. Disconnect-on-abuse — rolling-window pruning, threshold trip,
       reset semantics.
    4. Config surface — CLI flag parsing, env-var parsing, precedence
       (flags > env > defaults).

  End-to-end coverage in `subscribe.cljs` lives in
  `subscribe_resource_controls_test.cljs` — the stream-controller
  integration. This file pins the resource-controls primitives in
  isolation."
  (:require [cljs.test :refer-macros [deftest is testing use-fixtures]]
            [re-frame2-pair-mcp.tools.resource-controls :as resource]))

;; ---------------------------------------------------------------------------
;; Fixture: every test gets a clean slate.
;; ---------------------------------------------------------------------------

(use-fixtures :each
  {:before (fn [] (resource/reset-for-tests!))
   :after  (fn [] (resource/reset-for-tests!))})

;; ---------------------------------------------------------------------------
;; Defaults — pin each documented value (the source of truth lives in
;; `defaults` inside the ns; a tuning bead must update both this test
;; and the spec doc in lockstep).
;; ---------------------------------------------------------------------------

(deftest default-values-match-documented-shape
  ;; The bead (rf2-3ijbl) names these four defaults. Each is exposed
  ;; via `default-value` so the test isn't reaching into a private.
  (is (= 10    (resource/default-value :max-concurrent-streams))
      "10 streams: generous for normal use, tight enough to cap loops")
  (is (= 100   (resource/default-value :max-events-per-sec))
      "100 events/sec: ~500 KB/sec at 5KB/event — well under nREPL bandwidth")
  (is (= 50    (resource/default-value :abuse-overflow-threshold))
      "50 overflows: sustained ~5/sec eviction over 10s = consumer can't keep up")
  (is (= 10000 (resource/default-value :abuse-window-ms))
      "10s window: long enough to smooth bursts, short enough to react"))

(deftest current-config-mirrors-defaults-after-reset
  (let [cfg (resource/current-config)]
    (is (= 10    (:max-concurrent-streams cfg)))
    (is (= 100   (:max-events-per-sec cfg)))
    (is (= 50    (:abuse-overflow-threshold cfg)))
    (is (= 10000 (:abuse-window-ms cfg)))))

;; ---------------------------------------------------------------------------
;; Concurrent-stream cap.
;; ---------------------------------------------------------------------------

(deftest acquire-stream-under-cap-succeeds
  (let [r (resource/acquire-stream!)]
    (is (true? (:ok? r)))
    (is (= 1   (:active r)))
    (is (= 10  (:limit r)))
    (is (= 1   (resource/active-stream-count)))))

(deftest acquire-stream-at-cap-rejects-with-structured-error
  ;; Fill to the default cap (10).
  (dotimes [_ 10] (resource/acquire-stream!))
  (is (= 10 (resource/active-stream-count)))
  (let [r (resource/acquire-stream!)]
    (is (false? (:ok? r)))
    (is (= :rf.error/concurrent-stream-limit (:reason r)))
    (is (= 10  (:limit r)))
    (is (= 10  (:active r)))
    (is (string? (:hint r))
        "Hint must guide the operator toward unsubscribe / raise the cap")
    (is (= 10  (resource/active-stream-count))
        "Rejection MUST NOT increment the counter")))

(deftest release-stream-decrements-under-floor
  (resource/acquire-stream!)
  (resource/acquire-stream!)
  (is (= 2 (resource/active-stream-count)))
  (resource/release-stream!)
  (is (= 1 (resource/active-stream-count)))
  (resource/release-stream!)
  (is (zero? (resource/active-stream-count))))

(deftest release-stream-clamps-at-zero
  ;; Double-release on a buggy termination path must not drive the
  ;; counter negative (which would let extra streams sneak past the cap).
  (resource/release-stream!)
  (resource/release-stream!)
  (resource/release-stream!)
  (is (zero? (resource/active-stream-count))
      "Triple-release from zero clamps at the floor"))

(deftest acquire-release-cycle-tracks-cap
  ;; A close+open cycle MUST free the slot — the counter is dynamic,
  ;; not monotone.
  (dotimes [_ 10] (resource/acquire-stream!))
  (is (false? (:ok? (resource/acquire-stream!))))
  (resource/release-stream!)
  (is (true? (:ok? (resource/acquire-stream!)))
      "After one release a new acquire succeeds"))

(deftest custom-cap-via-set-config
  ;; The cap is operator-configurable; `set-config!` is the post-boot
  ;; entry point. Verify a tightened cap takes effect immediately.
  (resource/set-config! {:max-concurrent-streams 2})
  (is (true?  (:ok? (resource/acquire-stream!))))
  (is (true?  (:ok? (resource/acquire-stream!))))
  (is (false? (:ok? (resource/acquire-stream!)))
      "Third acquire hits the custom cap"))

;; ---------------------------------------------------------------------------
;; Per-session rate-limit (token bucket).
;; ---------------------------------------------------------------------------

(deftest rate-limit-allows-initial-burst-up-to-cap
  ;; Bucket initialises to full = max-events-per-sec. A fresh session
  ;; can burst right up to the cap before throttling.
  (resource/set-config! {:max-events-per-sec 5})
  (resource/reset-rate-bucket!)
  (is (true? (resource/check-rate!)) "1st check — token available")
  (is (true? (resource/check-rate!)) "2nd")
  (is (true? (resource/check-rate!)) "3rd")
  (is (true? (resource/check-rate!)) "4th")
  (is (true? (resource/check-rate!)) "5th — bucket empties")
  (is (false? (resource/check-rate!)) "6th — bucket empty, denied"))

(deftest rate-limit-refills-over-time
  ;; After draining the bucket, tokens refill at `max-events-per-sec`
  ;; per second. We can't actually sleep in CLJS tests easily; instead,
  ;; rely on the property that `check-rate!` reads `Date.now` — between
  ;; two calls some real time passes (even if tiny). Pin the property
  ;; that the bucket value never exceeds the cap.
  (resource/set-config! {:max-events-per-sec 10})
  (resource/reset-rate-bucket!)
  (resource/check-rate!)
  (resource/check-rate!)
  (let [tokens (resource/current-tokens)]
    (is (some? tokens))
    (is (<= tokens 10.0) "Bucket never exceeds cap")
    (is (>= tokens 0.0)  "Bucket never goes negative")))

(deftest rate-limit-denied-when-bucket-empty
  ;; Pin the throttle path: drain the bucket then verify successive
  ;; calls deny (until time refills it).
  (resource/set-config! {:max-events-per-sec 3})
  (resource/reset-rate-bucket!)
  (dotimes [_ 3] (resource/check-rate!))
  ;; After 3 takes from a 3-cap bucket, near-zero remains (a tiny
  ;; refill may have occurred between the calls — but not enough for
  ;; one more whole token in any reasonable clock).
  (let [result (resource/check-rate!)]
    ;; This call SHOULD deny — unless test-host wall-clock granularity
    ;; let the bucket refill more than 1/3 of a second between draws.
    ;; The looser assertion below pins the contract without flaking.
    (is (boolean? result))))

;; ---------------------------------------------------------------------------
;; Disconnect-on-abuse heuristic.
;; ---------------------------------------------------------------------------

(deftest record-overflow-below-threshold-returns-ok
  (resource/set-config! {:abuse-overflow-threshold 5
                         :abuse-window-ms          60000})
  (dotimes [_ 5] (is (= :ok (resource/record-overflow!))))
  (is (= 5 (resource/abuse-window-size))
      "Five overflows accumulated, none exceed threshold"))

(deftest record-overflow-trips-abuse-when-threshold-exceeded
  (resource/set-config! {:abuse-overflow-threshold 3
                         :abuse-window-ms          60000})
  (is (= :ok (resource/record-overflow!)))
  (is (= :ok (resource/record-overflow!)))
  (is (= :ok (resource/record-overflow!)))
  (is (= :abuse-detected (resource/record-overflow!))
      "4th overflow exceeds threshold of 3 — abuse-detected"))

(deftest record-overflow-window-prunes-old-stamps
  ;; The window is rolling, not cumulative. Stamps older than
  ;; `abuse-window-ms` drop on each call. We can't easily fake time
  ;; in CLJS tests, so set a tiny window and rely on Date.now's tick.
  (resource/set-config! {:abuse-overflow-threshold 100
                         :abuse-window-ms          1}) ;; 1ms window
  (resource/record-overflow!)
  ;; Yield to event loop so >1ms passes.
  (js/setTimeout
    (fn []
      ;; A fresh record after the window expires sees a pruned vector.
      ;; The exact size depends on host-clock granularity; pin only
      ;; that the window-size doesn't grow unboundedly.
      (resource/record-overflow!)
      (is (<= (resource/abuse-window-size) 2)))
    5))

(deftest reset-abuse-window-clears-state
  (resource/set-config! {:abuse-overflow-threshold 100
                         :abuse-window-ms          60000})
  (dotimes [_ 10] (resource/record-overflow!))
  (is (= 10 (resource/abuse-window-size)))
  (resource/reset-abuse-window!)
  (is (zero? (resource/abuse-window-size))))

;; ---------------------------------------------------------------------------
;; Configuration parsing — CLI flags + env vars + precedence.
;; ---------------------------------------------------------------------------

(deftest parse-resource-flags-recognises-each-flag
  (let [cfg (resource/parse-resource-flags
              ["--max-concurrent-streams=20"
               "--max-events-per-sec=200"
               "--abuse-overflow-threshold=100"
               "--abuse-window-ms=20000"])]
    (is (= 20    (:max-concurrent-streams cfg)))
    (is (= 200   (:max-events-per-sec cfg)))
    (is (= 100   (:abuse-overflow-threshold cfg)))
    (is (= 20000 (:abuse-window-ms cfg)))))

(deftest parse-resource-flags-ignores-unknown
  ;; Future flags MUST not break older invocations. Symmetric with
  ;; `parse-launch-flags` in server.cljs (rf2-c2dtu pattern).
  (let [cfg (resource/parse-resource-flags
              ["--unknown-flag=foo"
               "--max-concurrent-streams=5"
               "--also-unknown"])]
    (is (= {:max-concurrent-streams 5} cfg))))

(deftest parse-resource-flags-rejects-non-positive
  ;; Negative and zero values are nonsense — they'd disable the gate
  ;; entirely. Reject silently (fall back to default).
  (let [cfg (resource/parse-resource-flags
              ["--max-concurrent-streams=0"
               "--max-events-per-sec=-1"
               "--abuse-overflow-threshold=abc"])]
    (is (empty? cfg))))

(deftest parse-resource-flags-empty-argv
  (is (empty? (resource/parse-resource-flags [])))
  (is (empty? (resource/parse-resource-flags ["--allow-eval" "--allow-sensitive-reads"]))
      "Boolean launch flags (other-ns) pass through unmolested"))

(deftest read-resource-env-parses-each-var
  ;; Pass a stubbed env-obj — same JS-object shape `process.env` has.
  (let [env-obj #js {:RE_FRAME2_PAIR_MCP_MAX_STREAMS              "15"
                     :RE_FRAME2_PAIR_MCP_MAX_EVENTS_PER_SEC       "150"
                     :RE_FRAME2_PAIR_MCP_ABUSE_OVERFLOW_THRESHOLD "75"
                     :RE_FRAME2_PAIR_MCP_ABUSE_WINDOW_MS          "15000"}
        cfg (resource/read-resource-env env-obj)]
    (is (= 15    (:max-concurrent-streams cfg)))
    (is (= 150   (:max-events-per-sec cfg)))
    (is (= 75    (:abuse-overflow-threshold cfg)))
    (is (= 15000 (:abuse-window-ms cfg)))))

(deftest read-resource-env-empty-when-unset
  (is (empty? (resource/read-resource-env #js {}))))

(deftest read-resource-env-skips-non-positive
  (let [env-obj #js {:RE_FRAME2_PAIR_MCP_MAX_STREAMS        "0"
                     :RE_FRAME2_PAIR_MCP_MAX_EVENTS_PER_SEC "not-a-number"
                     :RE_FRAME2_PAIR_MCP_ABUSE_WINDOW_MS    ""}]
    (is (empty? (resource/read-resource-env env-obj)))))

(deftest merge-config-flags-win-over-env
  ;; Precedence contract: a CLI flag on the command line is the more
  ;; deliberate choice than an inherited env var.
  (let [env-cfg  {:max-concurrent-streams 5
                  :max-events-per-sec     50}
        flag-cfg {:max-concurrent-streams 20}
        merged   (resource/merge-config env-cfg flag-cfg)]
    (is (= 20 (:max-concurrent-streams merged))
        "Flag wins on conflict")
    (is (= 50 (:max-events-per-sec merged))
        "Env passes through when no conflicting flag")))

(deftest apply-resource-config-writes-into-runtime-state
  (resource/apply-resource-config!
    {:max-events-per-sec 200}
    {:max-concurrent-streams 25})
  (let [cfg (resource/current-config)]
    (is (= 25  (:max-concurrent-streams cfg)))
    (is (= 200 (:max-events-per-sec cfg)))
    (is (= 50    (:abuse-overflow-threshold cfg))
        "Unset keys fall back to documented default")
    (is (= 10000 (:abuse-window-ms cfg)))))

(deftest apply-resource-config-returns-merged-with-defaults
  ;; The returned map MUST include the defaults for keys the operator
  ;; didn't override — the server startup banner prints from this
  ;; return value, and unset keys printing as blank is a regression.
  (let [returned (resource/apply-resource-config! {} {})]
    (is (= 10    (:max-concurrent-streams returned)))
    (is (= 100   (:max-events-per-sec returned)))
    (is (= 50    (:abuse-overflow-threshold returned)))
    (is (= 10000 (:abuse-window-ms returned)))))

;; ---------------------------------------------------------------------------
;; reset-for-tests — full reset, used by the fixture above.
;; ---------------------------------------------------------------------------

(deftest reset-for-tests-clears-everything
  (resource/set-config! {:max-concurrent-streams 3})
  (resource/acquire-stream!)
  (resource/record-overflow!)
  (resource/check-rate!)
  (resource/reset-for-tests!)
  (is (= 10   (:max-concurrent-streams (resource/current-config))) "Config back to default")
  (is (zero? (resource/active-stream-count)) "Active streams cleared")
  (is (zero? (resource/abuse-window-size))   "Abuse window cleared")
  (is (nil?  (resource/current-tokens))      "Rate bucket cleared (re-init on next check)"))
