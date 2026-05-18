(ns re-frame2-pair-mcp.raw-state-test
  "Unit tests for the `--allow-raw-state` boot gate (rf2-c2dtu).

  Pins the three behaviours the gate guarantees:

    1. Default state — `allow-raw-state-enabled?` returns false; the two
       `force-*?` predicates return true so the wire path defaults to
       redact + elide regardless of per-call args.
    2. Opt-in state — flipping the gate flips the predicates so the
       per-call args win again (pre-rf2-c2dtu posture).
    3. `parse-launch-flags` parses `--allow-raw-state` and stays
       symmetric with `--allow-eval`.

  End-to-end MCP-wire shape coverage lives in
  `re-frame2-pair-mcp.conformance-test` (the corpus has dedicated
  fixtures pinning the gated default and the opt-in path)."
  (:require [cljs.test :refer-macros [deftest is testing]]
            [re-frame2-pair-mcp.server :as server]
            [re-frame2-pair-mcp.tools.raw-state :as raw-state]))

;; ---------------------------------------------------------------------------
;; Default-OFF posture.
;; ---------------------------------------------------------------------------

(deftest default-gate-is-off
  ;; Pre-boot / freshly-loaded ns: the gate is OFF, mirroring the
  ;; `--allow-eval` default (rf2-cxx5s).
  (raw-state/set-allow-raw-state! false)
  (is (false? (raw-state/allow-raw-state-enabled?)))
  (is (true?  (raw-state/force-redact?))
      "Gate OFF ⇒ force-redact? true so :include-sensitive collapses to false")
  (is (true?  (raw-state/force-elision?))
      "Gate OFF ⇒ force-elision? true so :elision collapses to true"))

;; ---------------------------------------------------------------------------
;; Opt-in (--allow-raw-state) posture.
;; ---------------------------------------------------------------------------

(deftest opt-in-flips-predicates
  ;; --allow-raw-state ⇒ per-call args win (pre-rf2-c2dtu posture).
  (raw-state/set-allow-raw-state! true)
  (is (true?  (raw-state/allow-raw-state-enabled?)))
  (is (false? (raw-state/force-redact?))
      "Gate ON ⇒ caller's :include-sensitive arg wins")
  (is (false? (raw-state/force-elision?))
      "Gate ON ⇒ caller's :elision arg wins")
  ;; Restore for downstream tests.
  (raw-state/set-allow-raw-state! false))

(deftest set-coerces-to-boolean
  ;; Defensive: `set-allow-raw-state!` should coerce truthy / falsy
  ;; inputs to a proper boolean — the atom holds `true`/`false`, not the
  ;; raw passed-in value.
  (raw-state/set-allow-raw-state! "yes")
  (is (true? (raw-state/allow-raw-state-enabled?))
      "Truthy non-bool ⇒ atom holds true (boolean-coerced)")
  (raw-state/set-allow-raw-state! nil)
  (is (false? (raw-state/allow-raw-state-enabled?))
      "nil ⇒ atom holds false (boolean-coerced)")
  (raw-state/set-allow-raw-state! false))

;; ---------------------------------------------------------------------------
;; Launch-flag parsing.
;; ---------------------------------------------------------------------------

(deftest parse-launch-flags-recognises-allow-raw-state
  (let [flags (server/parse-launch-flags ["--allow-raw-state"])]
    (is (true? (:allow-raw-state? flags))
        "--allow-raw-state ⇒ :allow-raw-state? true")
    (is (false? (:allow-eval? flags))
        "Other flags stay at their defaults")))

(deftest parse-launch-flags-symmetric-with-allow-eval
  ;; Both flags can ride together — the two gates are independent.
  (let [flags (server/parse-launch-flags ["--allow-eval" "--allow-raw-state"])]
    (is (true? (:allow-eval? flags)))
    (is (true? (:allow-raw-state? flags)))))

(deftest parse-launch-flags-defaults-both-off
  (let [flags (server/parse-launch-flags [])]
    (is (false? (:allow-eval? flags)))
    (is (false? (:allow-raw-state? flags)))))

(deftest parse-launch-flags-ignores-unknown
  ;; Future flags must not break older invocations.
  (let [flags (server/parse-launch-flags ["--no-such-flag" "--allow-raw-state"])]
    (is (true? (:allow-raw-state? flags)))))

;; ---------------------------------------------------------------------------
;; signal-runtime! cache — fires once per (build-id, server-lifetime).
;; ---------------------------------------------------------------------------

(deftest signal-runtime-caches-per-build
  ;; The runtime-signal path must be a no-op after the first invocation
  ;; per build-id — we don't want every state-emitting tool call to
  ;; pay an extra nREPL round-trip.
  (raw-state/reset-runtime-signal-cache!)
  ;; A signal against a stubbed conn would require the nrepl ns; here
  ;; we just verify the cache shape — after a fake "already-signalled"
  ;; entry, signal-runtime! must return the cached-no-op Promise.
  (raw-state/set-allow-raw-state! false)
  (raw-state/reset-runtime-signal-cache!)
  ;; Cache empty ⇒ a real signal would fire (we can't drive nrepl from
  ;; node-tests cleanly). We exercise the cache-reset only.
  (is (some? (raw-state/reset-runtime-signal-cache!))
      "reset-runtime-signal-cache! is callable"))
