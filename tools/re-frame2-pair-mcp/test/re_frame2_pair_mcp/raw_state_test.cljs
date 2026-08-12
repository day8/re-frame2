(ns re-frame2-pair-mcp.raw-state-test
  "Unit tests for the `--allow-sensitive-reads` boot gate and the single
  intention-naming predicate `raw-state-allowed?`.

  Internal Clojure identifiers (`allow-raw-state?` atom, `:allow-raw-state?`
  keyword, `raw-state-allowed?` predicate) use the `raw-state` naming;
  the operator-facing CLI flag is `--allow-sensitive-reads`.

  Pins the three behaviours the gate guarantees:

    1. Default state — `allow-raw-state-enabled?` and `raw-state-allowed?`
       both return false; per-tool branches force redact + elide
       regardless of per-call args.
    2. Opt-in state — flipping the gate flips the predicate so the
       per-call args win again.
    3. `parse-launch-flags` parses `--allow-sensitive-reads` and rides
       alongside `--no-eval` without interference.

  End-to-end MCP-wire shape coverage lives in
  `re-frame2-pair-mcp.conformance-test` (the corpus has dedicated
  fixtures pinning the gated default and the opt-in path)."
  (:require [cljs.test :refer-macros [deftest is testing async use-fixtures]]
            [re-frame2-pair-mcp.server :as server]
            [re-frame2-pair-mcp.nrepl :as nrepl]
            [re-frame2-pair-mcp.tools.raw-state :as raw-state]))

;; The signal-runtime! tests `set!` the module-level
;; `nrepl/cljs-eval-value`. Restore the pristine original in a
;; fixture-scoped `:after` (NOT a per-test `.finally`, which fires after
;; `done` and can clobber a neighbour namespace's stub mid-eval). Also
;; clear the signal in-flight cache + reset the gate so each test starts
;; from the published default.
(def ^:private pristine-eval nrepl/cljs-eval-value)

(use-fixtures :each
  {:after (fn []
            (set! nrepl/cljs-eval-value pristine-eval)
            (raw-state/reset-runtime-signal-cache!)
            (raw-state/set-allow-raw-state! false))})

;; ---------------------------------------------------------------------------
;; Default-OFF posture.
;; ---------------------------------------------------------------------------

(deftest default-gate-is-off
  ;; Pre-boot / freshly-loaded ns: the raw-state gate is OFF (default).
  ;; The sibling eval-cljs gate defaults ON; this test asserts only the
  ;; raw-state surface.
  (raw-state/set-allow-raw-state! false)
  (is (false? (raw-state/allow-raw-state-enabled?)))
  (is (false? (raw-state/raw-state-allowed?))
      "Gate OFF ⇒ raw-state-allowed? false; per-tool branches force redact + elide"))

;; ---------------------------------------------------------------------------
;; Opt-in (--allow-sensitive-reads) posture.
;; ---------------------------------------------------------------------------

(deftest opt-in-flips-predicate
  ;; --allow-sensitive-reads ⇒ per-call args win.
  (raw-state/set-allow-raw-state! true)
  (is (true?  (raw-state/allow-raw-state-enabled?)))
  (is (true?  (raw-state/raw-state-allowed?))
      "Gate ON ⇒ raw-state-allowed? true; caller's :include-sensitive / :elision args win")
  ;; Restore for downstream tests.
  (raw-state/set-allow-raw-state! false))

;; ---------------------------------------------------------------------------
;; raw-state-allowed? predicate semantics.
;; ---------------------------------------------------------------------------

(deftest raw-state-allowed-tracks-gate
  ;; The single intention-naming predicate matches its truth value to
  ;; the operator's opt-in state — positive sense, no inversion.
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
    (is (true? (:eval-allowed? flags))
        "eval-cljs gate stays at its default ON (rf2-a0z0h)")))

(deftest parse-launch-flags-rides-alongside-no-eval
  ;; The two flags are independent — passing --no-eval (eval opt-out)
  ;; alongside --allow-sensitive-reads (sensitive opt-in) lands both.
  (let [flags (server/parse-launch-flags ["--no-eval" "--allow-sensitive-reads"])]
    (is (false? (:eval-allowed? flags)))
    (is (true? (:allow-raw-state? flags)))))

(deftest parse-launch-flags-defaults
  ;; eval-cljs defaults ON; raw-state defaults OFF.
  (let [flags (server/parse-launch-flags [])]
    (is (true? (:eval-allowed? flags))
        "eval-cljs gate defaults ON (rf2-a0z0h)")
    (is (false? (:allow-raw-state? flags))
        "raw-state gate defaults OFF (rf2-c2dtu)")))

(deftest parse-launch-flags-ignores-unknown
  ;; The PARSER stays permissive — future flags + node/shadow wrapper
  ;; argv must not break the parse. The validation layer
  ;; (`launch-diagnostics`) is what NAMES the unknown flag at boot; see
  ;; `launch-diagnostics-*` below. The parser itself just plucks what it
  ;; understands and leaves the rest alone.
  (let [flags (server/parse-launch-flags ["--no-such-flag" "--allow-sensitive-reads"])]
    (is (true? (:allow-raw-state? flags)))))

(deftest parse-launch-flags-old-name-rejected
  ;; The `--allow-raw-state` flag is not recognised. No back-compat shim
  ;; — passing it does NOT enable the gate. It ALSO earns a :removed-flag
  ;; diagnostic naming the replacement — see
  ;; `launch-diagnostics-names-removed-flag`.
  (let [flags (server/parse-launch-flags ["--allow-raw-state"])]
    (is (false? (:allow-raw-state? flags))
        "--allow-raw-state must not enable the gate")))

(deftest parse-launch-flags-legacy-allow-eval-is-noop
  ;; The `--allow-eval` opt-in flag is not recognised because the eval
  ;; default is ON. No back-compat shim — passing it does not change the
  ;; gate (the gate is on regardless). Rather than a SILENT no-op, the
  ;; validation layer emits an intentional :removed-flag diagnostic
  ;; naming the replacement (see `launch-diagnostics-names-removed-flag`);
  ;; the parsed gate state is unchanged (still default ON).
  (let [flags (server/parse-launch-flags ["--allow-eval"])]
    (is (true? (:eval-allowed? flags))
        "--allow-eval must NOT disable the gate (gate stays at default ON)")))

;; ---------------------------------------------------------------------------
;; Launch-config diagnostics. The parsers stay permissive; this layer
;; scans the SAME argv against the declared flag schema and returns
;; structured diagnostics naming any rejected / suspicious input + its
;; effective fallback. `main` logs each at boot before readiness.
;; ---------------------------------------------------------------------------

(deftest launch-diagnostics-clean-config-empty
  (is (= [] (server/launch-diagnostics [])))
  (is (= [] (server/launch-diagnostics
              ["--no-eval" "--allow-sensitive-reads" "--allow-writes"
               "--port-file" "/abs/nrepl.port" "--http-port=9700"
               "--max-concurrent-streams=20"]))
      "every recognised flag (boolean / valued / resource) parses clean"))

(deftest launch-diagnostics-names-unknown-flag
  (let [[d :as ds] (server/launch-diagnostics ["--no-eavl"])]
    (is (= 1 (count ds)))
    (is (= :unknown-flag (:issue d)))
    (is (= "--no-eavl"   (:input d)))
    (is (= :warn         (:severity d)))))

(deftest launch-diagnostics-names-removed-flag
  (testing "renamed --allow-raw-state names the replacement"
    (let [[d] (server/launch-diagnostics ["--allow-raw-state"])]
      (is (= :removed-flag (:issue d)))
      (is (re-find #"allow-sensitive-reads" (:effect d)))))
  (testing "removed --allow-eval names the default-ON flip"
    (let [[d] (server/launch-diagnostics ["--allow-eval"])]
      (is (= :removed-flag (:issue d)))
      (is (re-find #"--no-eval" (:effect d))))))

(deftest launch-diagnostics-names-missing-value
  (testing "trailing valued flag with no value"
    (let [[d] (server/launch-diagnostics ["--port-file"])]
      (is (= :missing-value (:issue d)))
      (is (= "--port-file"  (:input d)))))
  (testing "valued flag immediately followed by another flag"
    (let [[d] (server/launch-diagnostics ["--http-port" "--no-eval"])]
      (is (= :missing-value (:issue d)))
      (is (= "--http-port"  (:input d)))))
  (testing "a valued flag WITH a value is clean"
    (is (= [] (server/launch-diagnostics ["--port-file" "/abs/nrepl.port"])))))

(deftest launch-diagnostics-names-malformed-http-port
  (let [[d] (server/launch-diagnostics ["--http-port" "garbage"])]
    (is (= :malformed-value (:issue d)))
    (is (re-find #"9630" (:effect d)))))

(deftest launch-diagnostics-names-malformed-resource-flag
  (testing "non-positive resource cap"
    (let [[d] (server/launch-diagnostics ["--max-concurrent-streams=0"])]
      (is (= :malformed-value (:issue d)))
      (is (= "--max-concurrent-streams=0" (:input d)))))
  (testing "non-numeric resource cap"
    (let [[d] (server/launch-diagnostics ["--abuse-window-ms=abc"])]
      (is (= :malformed-value (:issue d)))))
  (testing "a valid positive resource cap is clean"
    (is (= [] (server/launch-diagnostics ["--max-events-per-sec=200"])))))

(deftest resource-env-diagnostics-names-invalid-env
  (testing "zero / non-numeric / blank env vars each earn a :malformed-env"
    (let [ds (server/resource-env-diagnostics
               #js {:RE_FRAME2_PAIR_MCP_MAX_STREAMS        "0"
                    :RE_FRAME2_PAIR_MCP_MAX_EVENTS_PER_SEC "not-a-number"})]
      (is (= 2 (count ds)))
      (is (every? #(= :malformed-env (:issue %)) ds))))
  (testing "valid + unset env is clean"
    (is (= [] (server/resource-env-diagnostics
                #js {:RE_FRAME2_PAIR_MCP_MAX_STREAMS "15"})))
    (is (= [] (server/resource-env-diagnostics #js {})))))

;; ---------------------------------------------------------------------------
;; --port-file launch flag — explicit, cwd-independent port file.
;; ---------------------------------------------------------------------------

(deftest parse-launch-flags-port-file-defaults-nil
  (let [flags (server/parse-launch-flags [])]
    (is (nil? (:port-file flags))
        "absent --port-file ⇒ :port-file nil")))

(deftest parse-launch-flags-port-file-space-form
  (testing "--port-file <path> reads the value from the next argv element"
    (let [flags (server/parse-launch-flags ["--port-file" "/abs/path/nrepl.port"])]
      (is (= "/abs/path/nrepl.port" (:port-file flags)))
      (is (true? (:eval-allowed? flags)) "other flags stay at defaults (eval-allowed? defaults true)"))))

(deftest parse-launch-flags-port-file-equals-form
  (testing "--port-file=<path> reads the inline value"
    (let [flags (server/parse-launch-flags ["--port-file=/abs/path/nrepl.port"])]
      (is (= "/abs/path/nrepl.port" (:port-file flags))))))

(deftest parse-launch-flags-port-file-rides-with-other-flags
  (let [flags (server/parse-launch-flags
                ["--no-eval" "--port-file" "/p/nrepl.port" "--allow-sensitive-reads"])]
    (is (= "/p/nrepl.port" (:port-file flags)))
    (is (false? (:eval-allowed? flags)))
    (is (true? (:allow-raw-state? flags)))))

(deftest parse-launch-flags-port-file-missing-value-is-nil
  (testing "a trailing --port-file with no value (or followed by a flag) yields nil"
    (is (nil? (:port-file (server/parse-launch-flags ["--port-file"])))
        "trailing --port-file with no value")
    (is (nil? (:port-file (server/parse-launch-flags ["--port-file" "--no-eval"])))
        "--port-file immediately followed by another flag is not a value")))

(deftest parse-launch-flags-port-file-last-occurrence-wins
  (let [flags (server/parse-launch-flags
                ["--port-file" "/first/nrepl.port" "--port-file=/second/nrepl.port"])]
    (is (= "/second/nrepl.port" (:port-file flags))
        "later --port-file overrides earlier (argv override semantics)")))

;; ---------------------------------------------------------------------------
;; --http-port. Same parsing shape as --port-file (one shared
;; `parse-string-value-flag` helper underneath); these tests pin the
;; cross-cutting accept-shape (space + equals + missing-value + numeric
;; coercion) for the flag.
;; ---------------------------------------------------------------------------

(deftest parse-launch-flags-http-port-defaults-nil
  (let [flags (server/parse-launch-flags [])]
    (is (nil? (:http-port flags))
        "absent --http-port ⇒ :http-port nil (cascade uses 9630 default downstream)")))

(deftest parse-launch-flags-http-port-space-form
  (testing "--http-port <n> coerces to int"
    (let [flags (server/parse-launch-flags ["--http-port" "9700"])]
      (is (= 9700 (:http-port flags))
          "numeric string is parsed to int"))))

(deftest parse-launch-flags-http-port-equals-form
  (testing "--http-port=<n> reads the inline value"
    (let [flags (server/parse-launch-flags ["--http-port=9701"])]
      (is (= 9701 (:http-port flags))))))

(deftest parse-launch-flags-http-port-non-numeric-is-nil
  (testing "garbage at --http-port collapses to nil; cascade uses the 9630 default"
    (let [flags (server/parse-launch-flags ["--http-port" "garbage"])]
      (is (nil? (:http-port flags))
          "isNaN guard — never surface a NaN port"))))

(deftest parse-launch-flags-http-port-rides-with-other-flags
  (let [flags (server/parse-launch-flags
                ["--no-eval" "--http-port" "9702"
                 "--port-file" "/p/nrepl.port"])]
    (is (= 9702 (:http-port flags)))
    (is (= "/p/nrepl.port" (:port-file flags)))
    (is (false? (:eval-allowed? flags)))))

;; ---------------------------------------------------------------------------
;; signal-runtime! — re-signals before EVERY state-emitting eval. The
;; runtime's raw-state posture resets to its permissive default on every
;; page/runtime reload, so caching a per-build "delivered" flag would
;; leave a post-reload runtime tapping RAW app-db. The signal
;; reconfigures each call; only a CONCURRENT in-flight configure for the
;; same build is deduped (the race guard).
;; ---------------------------------------------------------------------------

(deftest signal-runtime-reconfigures-each-call
  ;; Drive `signal-runtime!` against a stubbed `cljs-eval-value` and count
  ;; the configure round-trips. Sequential (non-overlapping) calls MUST
  ;; each fire a fresh configure — no permanent per-build skip — so a
  ;; post-reload runtime is re-signalled.
  (async done
    (let [calls (atom 0)
          stub (fn
                 ([_conn _build-id _form]
                  (swap! calls inc)
                  (js/Promise.resolve nil))
                 ([_conn _build-id _form _opts]
                  (swap! calls inc)
                  (js/Promise.resolve nil)))]
      (raw-state/reset-runtime-signal-cache!)
      (raw-state/set-allow-raw-state! false)
      (set! nrepl/cljs-eval-value stub)
      (-> (raw-state/signal-runtime! nil :app)
          (.then (fn [_] (raw-state/signal-runtime! nil :app)))
          (.then (fn [_] (raw-state/signal-runtime! nil :app)))
          (.then (fn [_]
                   (is (= 3 @calls)
                       "three sequential signals ⇒ three configure round-trips (no permanent per-build skip)")))
          ;; `signal-runtime!` swallows its own failures, so this arm cannot
          ;; fire — but it must still REPORT rather than pass the row silently,
          ;; and it must sit UPSTREAM of the single trailing `done`, the shape
          ;; the sibling row below already uses.
          (.catch (fn [e]
                    (is false (str "signal-runtime! must not reject: " (.-message e)))
                    nil))
          (.then (fn [_] (done)))))))

(deftest signal-runtime-dedups-concurrent-in-flight
  ;; Two CONCURRENT signals for the same build (issued before the first
  ;; configure resolves) share ONE in-flight Promise — the race guard.
  (async done
    (let [calls (atom 0)
          ;; A configure that only resolves when we tell it to, so both
          ;; callers are genuinely in-flight at once.
          resolve-fn* (atom nil)
          stub (fn
                 ([_conn _build-id _form]
                  (swap! calls inc)
                  (js/Promise. (fn [res _] (reset! resolve-fn* res))))
                 ([_conn _build-id _form _opts]
                  (swap! calls inc)
                  (js/Promise. (fn [res _] (reset! resolve-fn* res)))))]
      (raw-state/reset-runtime-signal-cache!)
      (raw-state/set-allow-raw-state! false)
      (set! nrepl/cljs-eval-value stub)
      (let [p1 (raw-state/signal-runtime! nil :app)
            p2 (raw-state/signal-runtime! nil :app)]
        ;; Both issued before resolution — only ONE configure fired.
        (is (= 1 @calls)
            "concurrent in-flight signals for the same build share ONE configure round-trip")
        (when-let [r @resolve-fn*] (r nil))
        ;; The claim is already asserted above; this chain only waits for both
        ;; signals to SETTLE, either way. The swallow sits UPSTREAM of the
        ;; single trailing `done` (rf2-qpns) — `done` runs the whole remainder
        ;; of the run synchronously, so a `.catch` after it would swallow a
        ;; foreign throw and fire `done` a second time.
        (-> (js/Promise.all #js [p1 p2])
            (.catch (fn [_] nil))
            (.then (fn [_] (done))))))))
