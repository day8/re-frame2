(ns re-frame2-pair-mcp.raw-state-test
  "Unit tests for the `--allow-sensitive-reads` boot gate (rf2-c2dtu;
  CLI flag aligned cross-MCP per rf2-2x3ql) and the single
  intention-naming predicate `raw-state-allowed?` (rf2-p1qli).

  Internal Clojure identifiers (`allow-raw-state?` atom, `:allow-raw-state?`
  keyword, `raw-state-allowed?` predicate) retain the legacy `raw-state`
  naming; only the operator-facing CLI flag was renamed (rf2-2x3ql).

  Pins the three behaviours the gate guarantees:

    1. Default state — `allow-raw-state-enabled?` and `raw-state-allowed?`
       both return false; per-tool branches force redact + elide
       regardless of per-call args.
    2. Opt-in state — flipping the gate flips the predicate so the
       per-call args win again (pre-rf2-c2dtu posture).
    3. `parse-launch-flags` parses `--allow-sensitive-reads` and stays
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
  (is (false? (raw-state/raw-state-allowed?))
      "Gate OFF ⇒ raw-state-allowed? false; per-tool branches force redact + elide"))

;; ---------------------------------------------------------------------------
;; Opt-in (--allow-sensitive-reads) posture.
;; ---------------------------------------------------------------------------

(deftest opt-in-flips-predicate
  ;; --allow-sensitive-reads ⇒ per-call args win (pre-rf2-c2dtu posture).
  (raw-state/set-allow-raw-state! true)
  (is (true?  (raw-state/allow-raw-state-enabled?)))
  (is (true?  (raw-state/raw-state-allowed?))
      "Gate ON ⇒ raw-state-allowed? true; caller's :include-sensitive / :elision args win")
  ;; Restore for downstream tests.
  (raw-state/set-allow-raw-state! false))

;; ---------------------------------------------------------------------------
;; raw-state-allowed? predicate semantics (rf2-p1qli).
;; ---------------------------------------------------------------------------

(deftest raw-state-allowed-tracks-gate
  ;; The single intention-naming predicate (rf2-p1qli) matches its truth
  ;; value to the operator's opt-in state — positive sense, no inversion.
  ;; Per-tool bodies branch on this directly:
  ;;
  ;;   (if (raw-state/raw-state-allowed?)
  ;;     (args/parse-bool-arg raw-args :include-sensitive) ; gate ON  → caller wins
  ;;     false)                                            ; gate OFF → force redact
  ;;
  ;;   (if (raw-state/raw-state-allowed?)
  ;;     (args/parse-bool-arg raw-args :elision)           ; gate ON  → caller wins
  ;;     true)                                             ; gate OFF → force elide
  (raw-state/set-allow-raw-state! false)
  (is (false? (raw-state/raw-state-allowed?))
      "Gate OFF ⇒ operator did NOT opt in; force redact + elide")
  (raw-state/set-allow-raw-state! true)
  (is (true? (raw-state/raw-state-allowed?))
      "Gate ON ⇒ operator opted in via --allow-sensitive-reads; per-call args win")
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

(deftest parse-launch-flags-recognises-allow-sensitive-reads
  (let [flags (server/parse-launch-flags ["--allow-sensitive-reads"])]
    (is (true? (:allow-raw-state? flags))
        "--allow-sensitive-reads ⇒ :allow-raw-state? true (internal key)")
    (is (false? (:allow-eval? flags))
        "Other flags stay at their defaults")))

(deftest parse-launch-flags-symmetric-with-allow-eval
  ;; Both flags can ride together — the two gates are independent.
  (let [flags (server/parse-launch-flags ["--allow-eval" "--allow-sensitive-reads"])]
    (is (true? (:allow-eval? flags)))
    (is (true? (:allow-raw-state? flags)))))

(deftest parse-launch-flags-defaults-both-off
  (let [flags (server/parse-launch-flags [])]
    (is (false? (:allow-eval? flags)))
    (is (false? (:allow-raw-state? flags)))))

(deftest parse-launch-flags-ignores-unknown
  ;; Future flags must not break older invocations.
  (let [flags (server/parse-launch-flags ["--no-such-flag" "--allow-sensitive-reads"])]
    (is (true? (:allow-raw-state? flags)))))

(deftest parse-launch-flags-old-name-rejected
  ;; Hard rename (rf2-2x3ql): the legacy `--allow-raw-state` flag is no
  ;; longer recognised. No back-compat shim — passing it must NOT enable
  ;; the gate.
  (let [flags (server/parse-launch-flags ["--allow-raw-state"])]
    (is (false? (:allow-raw-state? flags))
        "Legacy --allow-raw-state must not enable the gate post-rename")))

;; ---------------------------------------------------------------------------
;; --port-file launch flag (rf2-3dbwh) — explicit, cwd-independent port file.
;; ---------------------------------------------------------------------------

(deftest parse-launch-flags-port-file-defaults-nil
  (let [flags (server/parse-launch-flags [])]
    (is (nil? (:port-file flags))
        "absent --port-file ⇒ :port-file nil")))

(deftest parse-launch-flags-port-file-space-form
  (testing "--port-file <path> reads the value from the next argv element"
    (let [flags (server/parse-launch-flags ["--port-file" "/abs/path/nrepl.port"])]
      (is (= "/abs/path/nrepl.port" (:port-file flags)))
      (is (false? (:allow-eval? flags)) "other flags stay at defaults"))))

(deftest parse-launch-flags-port-file-equals-form
  (testing "--port-file=<path> reads the inline value"
    (let [flags (server/parse-launch-flags ["--port-file=/abs/path/nrepl.port"])]
      (is (= "/abs/path/nrepl.port" (:port-file flags))))))

(deftest parse-launch-flags-port-file-rides-with-other-flags
  (let [flags (server/parse-launch-flags
                ["--allow-eval" "--port-file" "/p/nrepl.port" "--allow-sensitive-reads"])]
    (is (= "/p/nrepl.port" (:port-file flags)))
    (is (true? (:allow-eval? flags)))
    (is (true? (:allow-raw-state? flags)))))

(deftest parse-launch-flags-port-file-missing-value-is-nil
  (testing "a trailing --port-file with no value (or followed by a flag) yields nil"
    (is (nil? (:port-file (server/parse-launch-flags ["--port-file"])))
        "trailing --port-file with no value")
    (is (nil? (:port-file (server/parse-launch-flags ["--port-file" "--allow-eval"])))
        "--port-file immediately followed by another flag is not a value")))

(deftest parse-launch-flags-port-file-last-occurrence-wins
  (let [flags (server/parse-launch-flags
                ["--port-file" "/first/nrepl.port" "--port-file=/second/nrepl.port"])]
    (is (= "/second/nrepl.port" (:port-file flags))
        "later --port-file overrides earlier (argv override semantics)")))

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
