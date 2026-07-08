(ns re-frame.test-quiet-shadow-node-cljs-test
  "Contract pin for the CLJS `:node-test` entry point
  `re-frame.test-quiet.shadow-node` (rf2-vespg).

  The shadow-node runner carries custom CLI parsing, var-filtering, an
  exit-code defmethod, and a global `console.warn` stub — none of which
  had coverage.  This namespace pins the surfaces that can be exercised
  in-process; the two that cannot (`st/run-all-tests` discovery and the
  `js/process.exit` calls) are pinned at the level of the pure decision
  they branch on, since calling them would tear down the node runner.

  Contracts pinned:

   - `--test=` SELECTION: comma-split into symbols, simple symbols are
     namespace selectors and qualified symbols are single-var
     selectors; `--list` / `--help` flags; unknown args are COLLECTED by
     the pure parser (into `:unknown-args`) and rejected as a fatal parse
     error by `execute-cli` (rf2-14nojy.1) — the process-level pins below
     prove that boundary.
   - FAILURE EXIT: the `:end-run-tests` defmethod exits 0 iff the
     summary is `cljs.test/successful?`, 1 otherwise — pinned via the
     predicate the defmethod branches on (calling the defmethod itself
     would `process.exit` and kill this runner).
   - console.warn CAPTURE COMPAT: the ns-load `console.warn` stub does
     not break the local save/shim/restore capture pattern that
     warning-assertion tests use — a shim installed over the stub still
     records, and restore reverts to the stub.

  NESTED-RUN BANNER (rf2-8n97n.1/.2) — JVM-ONLY SCOPE: the nested-run
  banner-correctness regressions live in the JVM
  `re-frame.test-quiet-runner-contract-test`, not here.  A nested
  `cljs.test/run-tests` cannot be exercised in-process under this
  runner: `run-tests` is block-based/async and unconditionally fires
  `:end-run-tests`, which shadow-node overrides to `js/process.exit`
  (see `end-run-tests-exit-decision` above) — a nested run would tear
  down the node runner before the outer assertion. The FIX is shared
  CLJC (the banner ns is derived from the failing var's metadata, not a
  clobberable global cell), so the CLJS reporter benefits identically
  for sequential runs; only the nesting REGRESSION is JVM-scoped."
  ;; NB: must NOT require re-frame.test-quiet.shadow-node — that ns is
  ;; `:dev/always` and expands the test-ns-enumeration macro, so a test
  ;; requiring it forms a compile cycle. Pure CLI parsing lives in the
  ;; `-cli` ns; the console.warn stub it installs is live at runtime
  ;; anyway because shadow-node is the :node-test build's :main.
  (:require [cljs.test :refer-macros [deftest is testing] :refer [successful?]]
            [clojure.string :as str]
            [re-frame.test-quiet.shadow-node-cli :as cli]
            [re-frame.test-quiet.warn-buffer :as wb]))

;; ----------------------------------------------------------------------
;; --test= selection / flag parsing.

(deftest parse-args-test-selection
  (testing "--test= splits on comma into symbols"
    (is (= {:test-syms '[my.ns]}
           (cli/parse-args ["--test=my.ns"]))
        "a single simple symbol selects a whole namespace")
    (is (= {:test-syms '[my.ns other.ns]}
           (cli/parse-args ["--test=my.ns,other.ns"]))
        "comma-separated values become multiple symbols")
    (is (= {:test-syms '[my.ns/a-test]}
           (cli/parse-args ["--test=my.ns/a-test"]))
        "a qualified symbol selects a single var")
    (is (= {:test-syms '[my.ns my.ns/a-test]}
           (cli/parse-args ["--test=my.ns,my.ns/a-test"]))
        "ns and fqn selectors coexist")
    (is (= {:test-syms '[a b c d]}
           (cli/parse-args ["--test=a,b" "--test=c,d"]))
        "repeated --test= flags accumulate into one list")))

(deftest parse-args-flags
  (testing "--list and --help set their flags and default an empty test-syms"
    (is (= {:test-syms [] :list true} (cli/parse-args ["--list"])))
    (is (= {:test-syms [] :help true} (cli/parse-args ["--help"]))))
  (testing "no args yields the run-all default (empty test-syms, no flags)"
    (is (= {:test-syms []} (cli/parse-args []))))
  (testing "unknown args are collected (not printed); the pure parser does not abort"
    ;; The pure parser must NOT print on an unknown arg — it collects
    ;; them into `:unknown-args` and the real CLI path (`execute-cli`)
    ;; reports them and exits NONZERO (rf2-14nojy.1, pinned at the process
    ;; boundary below). Keeping the PARSER pure-and-silent is what keeps
    ;; the green-run contract gate truly silent-on-success: a
    ;; `(println \"Unknown arg: ...\")` here would leak a non-summary line
    ;; into every consolidated `npm run test:cljs` run (rf2-spzkgo).
    (is (= {:test-syms '[ok.ns] :unknown-args ["--bogus"]}
           (cli/parse-args ["--bogus" "--test=ok.ns"]))
        "an unknown flag is collected; later valid flags still parse")
    (is (= {:test-syms '[ok.ns] :unknown-args ["--bogus" "--nope"]}
           (cli/parse-args ["--bogus" "--test=ok.ns" "--nope"]))
        "multiple unknown flags accumulate in input order")
    (is (not (contains? (cli/parse-args ["--test=ok.ns"]) :unknown-args))
        "a clean arg set carries no :unknown-args key at all")))

;; ----------------------------------------------------------------------
;; Warning-ring memory bound — rf2-6emzlh.
;;
;; The buffered-`console.warn` ring claims to cap memory to the newest
;; `warn-buffer-cap` warnings.  The pre-fix trim used `subvec`, which in
;; ClojureScript shares — and thereby RETAINS — its entire underlying
;; vector via `.-v` (and `conj`-ing onto a `Subvec` grows that underlying
;; vector), so every discarded warning stayed alive until process exit and
;; the "bound" leaked without limit.  `warn-buffer/bound-conj` now
;; materialises the trimmed window into a fresh `PersistentVector`.  This
;; is an in-memory STRUCTURAL property (the backing must not be a growing
;; `Subvec`), so it can only be pinned as a pure unit test — not across the
;; process boundary the other shadow-node pins use.

(deftest warn-buffer-is-bounded-and-materialised
  (testing "bound-conj retains only the newest cap entries as a fresh vector (rf2-6emzlh)"
    ;; Fill FAR past a tiny cap: the ring must report exactly `cap` entries,
    ;; hold the NEWEST ones, and — the core pin — its backing must NOT be a
    ;; Subvec (which would retain the full discarded history via its shared
    ;; underlying vector).
    (let [cap 4
          buf (reduce (fn [b i] (wb/bound-conj b [i] cap)) [] (range 1000))]
      (is (= cap (count buf))
          "the ring is bounded to cap entries no matter how many are appended")
      (is (= [[996] [997] [998] [999]] buf)
          "the ring retains the NEWEST cap entries, dropping the oldest")
      (is (not (instance? cljs.core/Subvec buf))
          (str "the trimmed ring must NOT be a Subvec — a Subvec shares and"
               " retains its full underlying vector, so the bound would leak"
               " every discarded warning until process exit (rf2-6emzlh)"))
      (is (instance? cljs.core/PersistentVector buf)
          "the trimmed ring is a materialised PersistentVector, not a view")))
  (testing "below the cap the ring is a plain growing vector (no trim, no Subvec)"
    (let [buf (reduce (fn [b i] (wb/bound-conj b [i] 10)) [] (range 3))]
      (is (= [[0] [1] [2]] buf))
      (is (not (instance? cljs.core/Subvec buf))
          "an untrimmed ring is never a Subvec")))
  (testing "under the REAL default cap the ring never exceeds warn-buffer-cap"
    ;; Drive several multiples of the real cap through the real default arity
    ;; so a regression that dropped the materialisation (or the cap) is caught
    ;; against the production constant, not just a synthetic tiny cap.
    (let [buf (reduce (fn [b i] (wb/bound-conj b [i]))
                      []
                      (range (* 4 wb/warn-buffer-cap)))]
      (is (= wb/warn-buffer-cap (count buf))
          "the ring stays capped at the production warn-buffer-cap")
      (is (not (instance? cljs.core/Subvec buf))
          "the production-cap ring must not degrade into a retaining Subvec"))))

;; ----------------------------------------------------------------------
;; Var filtering — simple symbols match by ns, qualified by fully-qualified
;; name.  Driven against synthetic var-like maps so the pin does not depend
;; on the live test-data registry.

(defn- fake-var
  "A stand-in for a test var: `meta` returns {:ns :name}, matching what
  `find-matching-test-vars` reads."
  [ns name]
  (with-meta (fn []) {:ns ns :name name}))

(deftest find-matching-test-vars-filtering
  ;; find-matching-test-vars reads (env/get-test-vars); we cannot inject
  ;; that registry here, but we CAN pin the selection predicate it builds
  ;; — the (or (contains? namespaces ns) (contains? fqns (symbol ns name)))
  ;; rule — by reproducing it over fake vars. This locks the documented
  ;; "simple symbol = whole ns, qualified symbol = single var" contract.
  (let [vars        [(fake-var 'my.ns 'a-test)
                     (fake-var 'my.ns 'b-test)
                     (fake-var 'other.ns 'c-test)]
        select      (fn [test-syms]
                      (let [namespaces (->> test-syms (filter simple-symbol?) set)
                            fqns       (->> test-syms (filter qualified-symbol?) set)]
                        (->> vars
                             (filter (fn [v]
                                       (let [{:keys [name ns]} (meta v)]
                                         (or (contains? namespaces ns)
                                             (contains? fqns (symbol ns name))))))
                             (map (fn [v] (let [{:keys [ns name]} (meta v)]
                                            (symbol ns name)))))))]
    (testing "a simple symbol selects every var in that namespace"
      (is (= '[my.ns/a-test my.ns/b-test] (select '[my.ns]))))
    (testing "a qualified symbol selects exactly that var"
      (is (= '[my.ns/b-test] (select '[my.ns/b-test]))))
    (testing "ns + fqn selectors combine"
      (is (= '[my.ns/a-test my.ns/b-test other.ns/c-test]
             (select '[my.ns other.ns/c-test]))))
    (testing "an unmatched selector yields nothing"
      (is (= '[] (select '[absent.ns]))))))

;; ----------------------------------------------------------------------
;; Unmatched-selector guard (rf2-lbo79.1) — a `--test=<selector>` that
;; matches NO test var must be rejected, not reported as a 0-test SUCCESS.
;;
;; Pre-fix, `execute-cli` ran `st/run-test-vars` over an empty matched-var
;; set whenever `test-syms` was non-empty; shadow's runner then reported a
;; 0-test successful summary and the `:end-run-tests` defmethod exited 0,
;; so a typo in `npm run test:cljs -- --test=<selector>` silently "passed"
;; with ZERO tests. The fix computes the unmatched selectors and, when any
;; exist, prints them and `js/process.exit`s NONZERO before running.
;;
;; `cli/unmatched-selectors` is the pure decision `execute-cli` branches
;; on — `(seq unmatched) -> exit 1`. It is pinned directly here (the
;; runner ns is `:dev/always` and forms a compile cycle, so the guard
;; lives in the pure `-cli` ns precisely so it can be unit-pinned). Vars
;; are the same fake `{:ns :name}`-meta stand-ins `find-matching-test-vars`
;; reads, so this pins the REAL guard against the REAL matched-var shape.

(deftest unmatched-selectors-guard
  (let [a-test    (fake-var 'my.ns 'a-test)
        b-test    (fake-var 'my.ns 'b-test)
        all-vars  [a-test b-test]]
    (testing "a fully-matched selection has no unmatched selectors -> runs (no exit)"
      ;; ns selector matches: my.ns has matched vars.
      (is (= '() (cli/unmatched-selectors '[my.ns] all-vars)))
      ;; fqn selector matches: my.ns/a-test is in the matched set.
      (is (= '() (cli/unmatched-selectors '[my.ns/a-test] [a-test]))))
    (testing "--test=missing.ns (absent namespace) is reported unmatched -> guard exits nonzero"
      ;; The CORE false-green case: a typo'd namespace matches zero vars.
      ;; Pre-fix this ran 0 tests and exited 0; the guard now sees a
      ;; non-empty unmatched set and the `(seq unmatched)` branch exits 1.
      (let [unmatched (cli/unmatched-selectors '[missing.ns] [])]
        (is (= '[missing.ns] unmatched)
            "an absent namespace selector matches nothing -> unmatched")
        (is (seq unmatched)
            "non-empty unmatched -> execute-cli takes the exit-1 branch, NOT run-test-vars")))
    (testing "--test=missing.ns/a-test (absent var) is reported unmatched -> guard exits nonzero"
      (let [unmatched (cli/unmatched-selectors '[missing.ns/a-test] [])]
        (is (= '[missing.ns/a-test] unmatched)
            "an absent fully-qualified selector matches nothing -> unmatched")
        (is (seq unmatched)
            "non-empty unmatched -> execute-cli takes the exit-1 branch")))
    (testing "a typo'd fqn against a present ns is STILL unmatched (the ns matching some vars does not cover a wrong var name)"
      ;; my.ns exists and has vars, but my.ns/c-test does not — a qualified
      ;; selector must match by FQN, not be rescued by its namespace having
      ;; OTHER matched vars. This is the subtle false-green: the matched-var
      ;; set is non-empty (a-test/b-test), but the SELECTOR matched nothing.
      (is (= '[my.ns/c-test]
             (cli/unmatched-selectors '[my.ns/c-test] all-vars))
          "a qualified selector for an absent var is unmatched even when its ns has other matches"))
    (testing "a mix of matched + unmatched reports only the unmatched, in input order"
      (is (= '[gone.ns missing.ns/x]
             (cli/unmatched-selectors '[my.ns gone.ns my.ns/a-test missing.ns/x]
                                      [a-test]))
          "matched selectors drop out; unmatched ones survive in order"))))

;; ----------------------------------------------------------------------
;; Failure-exit decision — pinned via the predicate the :end-run-tests
;; defmethod branches on. (Invoking the defmethod itself calls
;; js/process.exit, which would tear down this runner.)

(deftest end-run-tests-exit-decision
  (testing "successful? is the green/red branch the exit defmethod uses"
    (is (true? (successful? {:fail 0 :error 0}))
        "0 failures + 0 errors is green -> the defmethod exits 0")
    (is (false? (successful? {:fail 1 :error 0}))
        "any failure is red -> the defmethod exits 1")
    (is (false? (successful? {:fail 0 :error 1}))
        "any error is red -> the defmethod exits 1"))
  (testing "the exit defmethod is registered for the default reporter"
    (is (some? (get-method cljs.test/report
                           [:cljs.test/default :end-run-tests]))
        "shadow-node must own the process-exit signal")))

;; ----------------------------------------------------------------------
;; console.warn stub compatibility — the ns-load stub must not break the
;; local capture pattern warning-assertion tests rely on.

(deftest console-warn-capture-compat
  (testing "the save/shim/restore capture pattern round-trips over the live baseline"
    ;; Under the real :node-test build, shadow-node is :main, so the live
    ;; `console.warn` at this point IS its silencing stub.  Warning-assertion
    ;; tests don't depend on WHICH baseline is installed — only that the
    ;; save -> install-recording-shim -> run -> restore pattern records the
    ;; body's warnings and reverts cleanly.  Pinning the round-trip (rather
    ;; than `identical?`-ing against the stub, which would require coupling
    ;; to shadow-node and re-form the compile cycle) is the contract that
    ;; keeps those tests working whether or not the stub is in place.
    (let [saved    (.-warn js/console)
          recorded (atom [])]
      (set! (.-warn js/console) (fn [& args] (swap! recorded conj (vec args))))
      (try
        (js/console.warn "captured-marker" 42)
        (is (= [["captured-marker" 42]] @recorded)
            "the recording shim receives the warning call unchanged")
        (finally
          (set! (.-warn js/console) saved)))
      (is (identical? saved (.-warn js/console))
          "restore reverts console.warn to exactly the saved baseline")
      ;; After restore, the recording shim is gone: a further warning does
      ;; not leak back into the capture atom.
      (reset! recorded [])
      (js/console.warn "post-restore-marker")
      (is (= [] @recorded)
          "after restore the recording shim no longer captures (clean revert)")))
  (testing "the silencing stub — not native console.warn — is the live baseline (shadow-node is :main)"
    ;; The stub carries a `rf-test-quiet-silenced` marker property (set in
    ;; shadow-node at ns-load).  Native Node `console.warn` has no such
    ;; property, so asserting the marker is present is positive proof the
    ;; SILENCING stub is installed — not just that the call returns nil
    ;; (native console.warn also returns undefined while still EMITTING the
    ;; warning text).  This fails if a regression drops the stub and the
    ;; runner falls back to native `console.warn`, which would reintroduce
    ;; green-path warning noise — the slice's core operational contract.
    (is (true? (.-rf-test-quiet-silenced (.-warn js/console)))
        (str "the live console.warn must be the identifiable silencing stub"
             " (marker present) — a missing marker means native console.warn"
             " is in place and green-path warnings would leak"))
    ;; And it must still accept a bare call without throwing.
    (is (nil? (js/console.warn "stub-smoke"))
        "the silencing stub must accept a bare call without throwing")))

;; ----------------------------------------------------------------------
;; Process-level quiet-shape regression (rf2-spzkgo).
;;
;; The pure-parser pins above lock that `parse-args` no longer PRINTS on
;; an unknown arg — but the slice's core promise is that the REAL
;; shadow-node entry point is silent-on-success.  That promise lived only
;; in the JVM contract test (`re-frame.test-quiet-runner-contract-test`);
;; the CLJS process path could regress without any test catching it (a
;; stray `println`, a dropped console.warn stub, a banner leak).
;;
;; This regression spawns the SAME built `out/node-test.js` shadow-node
;; runner this very process is executing, focused on a known-GREEN suite
;; (`re-frame.test-quiet-green-fixture-cljs-test`, a single `is (= 1 1)`),
;; captures its stdout, and fails if any green line other than the
;; allowed canonical summary appears — `Unknown arg`, a `Testing <ns>`
;; banner, leaked warnings, or any other non-summary text.

(def ^:private node-child-process (js/require "child_process"))

(def ^:private allowed-green-line-re
  "A non-blank green stdout line must be one of the two canonical summary
  lines.  `Ran N tests containing M assertions.` / `K failures, J errors.`"
  #"^(Ran \d+ tests containing \d+ assertions\.|\d+ failures, \d+ errors\.)$")

(def ^:private spawn-timeout-ms
  "Hard ceiling for a spawned focused runner.  A correctly-exiting child
  returns in well under a second; this generous bound makes a child that
  stops exiting FAIL with a structured `:timed-out?` diagnostic instead
  of hanging the whole CLJS suite (rf2-8dfq5j.3)."
  60000)

(def ^:private spawn-max-buffer-bytes
  "Output cap for a spawned focused runner — `spawnSync` kills the child
  and surfaces an ENOBUFS-class error once either stream exceeds this,
  so a runaway child cannot exhaust this process's memory."
  (* 8 1024 1024))

(defn- spawn-runner
  "Re-spawn the SAME built `out/node-test.js` shadow-node runner this
  process is executing, with `argv` (a CLJS vector of string args), and
  return `{:status :stdout :stderr :error :signal :timed-out?}`.

  `process.argv[1]` is the runner script path and `process.argv[0]` the
  node binary, so this exercises the REAL `shadow-node/main` -> `parse-args`
  -> `execute-cli` CLI path end-to-end across a process boundary — the only
  way to observe the `js/process.exit` codes the false-green guards branch
  on (calling `execute-cli` in-process would tear down this runner).

  `opts` is an optional CLJS map:
   - `:env` — extra environment entries (merged over the parent env) the
     child runs with;
  A shared `:timeout`/`:maxBuffer` policy is applied to EVERY spawn so a
  wedged or runaway child fails fast with a diagnostic rather than
  stranding the suite (rf2-8dfq5j.3).  On a `spawnSync` timeout the child
  is sent SIGTERM and `res.signal` is `\"SIGTERM\"`; `:timed-out?`
  surfaces that for assertions."
  ([argv] (spawn-runner argv {}))
  ([argv {:keys [env]}]
   (let [self     (aget js/process.argv 1)
         child-env (when env
                     (let [merged (js/Object.assign #js {} js/process.env)]
                       (doseq [[k v] env]
                         (aset merged (name k) v))
                       merged))
         res  (.spawnSync node-child-process
                          (aget js/process.argv 0) ; the node binary
                          (apply array self argv)
                          (cond-> #js {:encoding  "utf8"
                                       :timeout   spawn-timeout-ms
                                       :maxBuffer spawn-max-buffer-bytes}
                            child-env (doto (aset "env" child-env))))
         signal (.-signal res)]
     {:status     (.-status res)
      :stdout     (or (.-stdout res) "")
      :stderr     (or (.-stderr res) "")
      ;; cljs.test spawn errors (e.g. ENOENT, ETIMEDOUT, ENOBUFS) surface
      ;; on res.error.
      :error      (.-error res)
      :signal     signal
      ;; spawnSync kills the child with SIGTERM on a :timeout expiry.
      :timed-out? (= signal "SIGTERM")})))

(deftest real-shadow-node-green-run-is-quiet
  (testing "the real out/node-test.js entry point emits ONLY the canonical summary on a focused green run (rf2-spzkgo)"
    ;; Re-running the runner focused on the green fixture exercises the real
    ;; CLI path end-to-end.
    (let [green-ns "re-frame.test-quiet-green-fixture-cljs-test"
          {status :status stdout :stdout stderr :stderr err :error}
          (spawn-runner [(str "--test=" green-ns)])]
      (is (nil? err)
          (str "spawning the real runner must not error; got: " (pr-str err)))
      (is (zero? status)
          (str "the focused green run must exit 0; got " status
               "\n--- stdout ---\n" stdout "\n--- stderr ---\n" stderr))
      (let [non-blank (->> (str/split-lines stdout)
                           (map str/trim)
                           (remove str/blank?))]
        ;; The CORE pin: NOT ONE non-summary line. This is what `Unknown
        ;; arg: --bogus` used to violate before the parser was made pure.
        (is (every? #(re-matches allowed-green-line-re %) non-blank)
            (str "a green run must emit ONLY the canonical summary lines —"
                 " any other stdout line (e.g. `Unknown arg`, a `Testing`"
                 " banner, a leaked warning) breaks silent-on-success"
                 " (rf2-spzkgo). Got non-blank lines:\n"
                 (str/join "\n" non-blank)))
        (is (not (str/includes? stdout "Unknown arg"))
            (str "the specific rf2-spzkgo regression: `Unknown arg` must"
                 " NEVER reach a green run's stdout; got:\n" stdout))
        (is (not (str/includes? stdout "Testing "))
            (str "no per-ns banner may leak on green; got:\n" stdout))
        (is (some #(str/starts-with? % "Ran ") non-blank)
            (str "the summary `Ran ...` line must still be present; got:\n"
                 stdout))
        (is (some #(re-matches #"0 failures, 0 errors\." %) non-blank)
            (str "the green tally line must be present; got:\n" stdout))))))

;; ----------------------------------------------------------------------
;; Unknown-arg FALSE-GREEN guard (rf2-14nojy.1) — process-level.
;;
;; The pure-parser pins above lock that `parse-args` COLLECTS unknown args
;; into `:unknown-args` (it no longer prints; rf2-spzkgo).  But the real
;; FALSE-GREEN lived past the parser, in `execute-cli`: pre-fix it merely
;; PRINTED the unknowns and then fell through to the `:else`
;; `run-all-tests` branch whenever no `--test=` selector survived.  A
;; mistyped focused run — the space-separated `--test missing.ns` (both
;; tokens unknown, no `--test=` form), or a misspelled flag like
;; `--tests=foo` — therefore ran the FULL suite and exited GREEN, silently
;; ignoring the requested selection.  The fix makes unknown args a fatal
;; parse error: print them and `js/process.exit` NONZERO before running
;; anything.  These pins drive the REAL runner across a process boundary
;; (the only place the exit code is observable) and prove the false-green
;; is closed — with the green-fixture-still-passes negative control that a
;; correct invocation continues to exit 0.

(deftest unknown-arg-is-fatal-not-false-green
  (testing "a misspelled selector flag (--tests=...) is fatal, NOT a green full-suite run (rf2-14nojy.1)"
    ;; `--tests=` (note the typo'd plural) is an unknown arg, not the
    ;; `--test=` selector. Pre-fix it printed `Unknown arg: --tests=...`
    ;; then ran the whole suite and exited 0. It must now exit NONZERO.
    (let [{status :status stdout :stdout stderr :stderr err :error}
          (spawn-runner ["--tests=re-frame.test-quiet-green-fixture-cljs-test"])]
      (is (nil? err)
          (str "spawning the real runner must not error; got: " (pr-str err)))
      (is (and (number? status) (not (zero? status)))
          (str "a misspelled selector flag must exit NONZERO (not fall"
               " through to a green full-suite run); got status " status
               "\n--- stdout ---\n" stdout "\n--- stderr ---\n" stderr))
      (is (str/includes? stdout "Unknown arg: --tests=re-frame.test-quiet-green-fixture-cljs-test")
          (str "the offending unknown arg must be named; got:\n" stdout))
      ;; It must NOT have run the suite: no canonical summary line.
      (is (not (str/includes? stdout "Ran "))
          (str "the suite must NOT have run on an unknown-arg parse error —"
               " a `Ran ...` summary means it fell through to run-all-tests"
               " (the false green); got:\n" stdout))))
  (testing "space-separated --test <selector> (both tokens unknown) is fatal, NOT a green run (rf2-14nojy.1)"
    ;; The plausible space-separated form: `--test` and `missing.ns` are
    ;; BOTH unknown args (the parser only recognises the `--test=` glued
    ;; form), so no selector survives. Pre-fix this ran the full suite green.
    (let [{status :status stdout :stdout stderr :stderr err :error}
          (spawn-runner ["--test" "missing.ns"])]
      (is (nil? err)
          (str "spawning the real runner must not error; got: " (pr-str err)))
      (is (and (number? status) (not (zero? status)))
          (str "space-separated --test <selector> must exit NONZERO; got "
               status "\n--- stdout ---\n" stdout "\n--- stderr ---\n" stderr))
      (is (and (str/includes? stdout "Unknown arg: --test")
               (str/includes? stdout "Unknown arg: missing.ns"))
          (str "both unknown tokens must be named; got:\n" stdout))
      (is (not (str/includes? stdout "Ran "))
          (str "the suite must NOT have run; a `Ran ...` summary means the"
               " false-green fall-through to run-all-tests; got:\n" stdout))))
  (testing "a clean focused invocation still exits 0 (negative control)"
    ;; The guard must reject ONLY malformed args — a correct `--test=`
    ;; selector against a real green ns must still run and exit 0.
    (let [{status :status stdout :stdout stderr :stderr err :error}
          (spawn-runner ["--test=re-frame.test-quiet-green-fixture-cljs-test"])]
      (is (nil? err)
          (str "spawning the real runner must not error; got: " (pr-str err)))
      (is (zero? status)
          (str "a clean focused run must STILL exit 0 — the guard must not"
               " reject valid args; got status " status
               "\n--- stdout ---\n" stdout "\n--- stderr ---\n" stderr))
      (is (not (str/includes? stdout "Unknown arg"))
          (str "a clean invocation must emit no `Unknown arg`; got:\n" stdout)))))

(deftest unmatched-selector-is-fatal-at-real-runner
  (testing "--test=<missing> (a parsed-but-unmatched selector) exits NONZERO at the REAL runner, not just the pure helper (rf2-lbo79.1 boundary)"
    ;; The unmatched-selector guard was previously pinned only via the pure
    ;; `cli/unmatched-selectors` helper. This drives it across the REAL
    ;; process boundary: a well-formed `--test=` selector that matches NO
    ;; test var must print the ERROR + exit NONZERO, never a 0-test green.
    (let [{status :status stdout :stdout stderr :stderr err :error}
          (spawn-runner ["--test=definitely.absent.namespace"])]
      (is (nil? err)
          (str "spawning the real runner must not error; got: " (pr-str err)))
      (is (and (number? status) (not (zero? status)))
          (str "an unmatched --test= selector must exit NONZERO (not a"
               " 0-test green); got status " status
               "\n--- stdout ---\n" stdout "\n--- stderr ---\n" stderr))
      (is (str/includes? stdout "no tests matched --test= selector")
          (str "the unmatched-selector ERROR must reach stdout; got:\n" stdout))
      (is (str/includes? stdout "definitely.absent.namespace")
          (str "the offending selector must be named; got:\n" stdout))
      (is (not (str/includes? stdout "Ran "))
          (str "no suite may have run; a `Ran ...` summary is the false"
               " green; got:\n" stdout)))))

;; ----------------------------------------------------------------------
;; console.warn buffer red-replay (rf2-8dfq5j.2).
;;
;; The shadow-node `console.warn` stub no longer DISCARDS warnings on the
;; success path — it buffers them in a bounded ring and the
;; `:end-run-tests` reporter replays the buffer to stderr ONLY on a RED
;; run, restoring the diagnostic context a failing CLJS run needs.  This
;; can only be observed across a process boundary (the replay fires just
;; before `js/process.exit`).  We drive the REAL runner against
;; `re-frame.test-quiet-red-warn-fixture-cljs-test`, whose warn-then-fail
;; behaviour is gated on `RF2_TQ_RED_WARN_FIXTURE=1` (so the whole-suite
;; run stays green): with the env var set the fixture warns + fails, and
;; the buffered warning must surface in the red output; with it UNSET the
;; same fixture is green and emits no warning.

(deftest red-run-replays-buffered-console-warn
  (testing "a RED run replays the buffered console.warn diagnostic (rf2-8dfq5j.2)"
    (let [red-ns "re-frame.test-quiet-red-warn-fixture-cljs-test"
          {status :status stdout :stdout stderr :stderr err :error
           timed-out? :timed-out?}
          (spawn-runner [(str "--test=" red-ns)]
                        {:env {:RF2_TQ_RED_WARN_FIXTURE "1"}})]
      (is (nil? err)
          (str "spawning the real runner must not error; got: " (pr-str err)))
      (is (not timed-out?)
          "the armed fixture run must not time out")
      (is (and (number? status) (not (zero? status)))
          (str "the armed fixture is RED; must exit NONZERO; got " status
               "\n--- stdout ---\n" stdout "\n--- stderr ---\n" stderr))
      ;; The CORE pin: the warning the green-path stub withholds is
      ;; replayed on red, so its marker text must appear in the combined
      ;; output (the replay targets stderr).
      (is (str/includes? (str stdout stderr) "RED-WARN-FIXTURE-MARKER")
          (str "the buffered console.warn must be replayed on a RED run"
               " (rf2-8dfq5j.2); got\n--- stdout ---\n" stdout
               "\n--- stderr ---\n" stderr))
      (is (str/includes? (str stdout stderr) "[test-quiet] console.warn:")
          (str "the replay must label the buffered warnings; got\n"
               "--- stderr ---\n" stderr))))
  (testing "the SAME fixture is GREEN + quiet when unarmed (negative control)"
    ;; Without the env arming flag the fixture passes and emits no
    ;; warning, so the green-path quiet contract holds and the marker is
    ;; ABSENT from stdout — proving the warning is genuinely withheld on
    ;; green, not merely always printed.
    (let [red-ns "re-frame.test-quiet-red-warn-fixture-cljs-test"
          {status :status stdout :stdout stderr :stderr err :error}
          (spawn-runner [(str "--test=" red-ns)])]
      (is (nil? err)
          (str "spawning the real runner must not error; got: " (pr-str err)))
      (is (zero? status)
          (str "the unarmed fixture is GREEN; must exit 0; got " status
               "\n--- stdout ---\n" stdout "\n--- stderr ---\n" stderr))
      (is (not (str/includes? stdout "RED-WARN-FIXTURE-MARKER"))
          (str "an unarmed (green) run must NOT emit the warning marker on"
               " stdout — green stays quiet; got:\n" stdout))
      (let [non-blank (->> (str/split-lines stdout)
                           (map str/trim)
                           (remove str/blank?))]
        (is (every? #(re-matches allowed-green-line-re %) non-blank)
            (str "a green run must emit ONLY the canonical summary lines;"
                 " got:\n" (str/join "\n" non-blank)))))))

;; ----------------------------------------------------------------------
;; Large red-replay is not truncated by js/process.exit (rf2-4co7oo).
;;
;; The red-replay writes to fd 2 synchronously (`fs.writeSync`), NOT the
;; async `js/process.stderr.write`, so `js/process.exit 1` fired immediately
;; after it cannot drop the tail. On POSIX a pipe-backed `process.stderr` is
;; async and `process.exit` forces exit with pending writes still queued, so a
;; large replay (near `warn-buffer-cap` = 256 arbitrarily-sized entries) could
;; be truncated before it reached captured CI output — the exit code stays
;; correct (never a false green) but the tail of the diagnostic context is
;; lost. This drives the REAL runner across a process boundary against the
;; ring-cap-filling volume fixture and asserts the NEWEST warning (replayed
;; LAST — the exact byte range the async bug drops) survives to the output.
;; Windows dev-box pipe writes are synchronous, so the pre-fix bug would not
;; reproduce here, but this pin locks the fix and catches a POSIX (CI)
;; regression back to the async write.

(deftest large-red-replay-is-not-truncated
  (testing "a RED replay that fills the ring to cap with large warnings keeps its TAIL (rf2-4co7oo)"
    (let [red-ns "re-frame.test-quiet-red-warn-fixture-cljs-test"
          {status :status stdout :stdout stderr :stderr err :error
           timed-out? :timed-out?}
          (spawn-runner [(str "--test=" red-ns)]
                        {:env {:RF2_TQ_RED_REPLAY_VOLUME "1"}})
          combined (str stdout stderr)]
      (is (nil? err)
          (str "spawning the real runner must not error; got: " (pr-str err)))
      (is (not timed-out?)
          "the armed volume fixture run must not time out")
      (is (and (number? status) (not (zero? status)))
          (str "the armed volume fixture is RED; must exit NONZERO; got " status
               "\n--- stdout ---\n" stdout "\n--- stderr ---\n" stderr))
      ;; The head of the large replay is present (the replay genuinely ran and
      ;; was large — not a trivially-short buffer that would fit a pipe anyway).
      (is (str/includes? combined "RED-REPLAY-VOLUME-0")
          (str "the HEAD of the large replay must be present; got\n"
               "--- stderr ---\n" stderr))
      ;; The CORE pin: the NEWEST warning — replayed LAST — is the exact tail
      ;; that an async `process.stderr` + `process.exit` truncation would drop.
      ;; Its presence proves the synchronous `fs.writeSync` replay is not
      ;; truncated (rf2-4co7oo).
      (is (str/includes? combined "RED-REPLAY-TAIL-MARKER")
          (str "the TAIL of a large red replay must NOT be truncated by"
               " js/process.exit — fs.writeSync makes the write synchronous"
               " (rf2-4co7oo); got\n--- stdout ---\n" stdout
               "\n--- stderr ---\n" stderr)))))

;; ----------------------------------------------------------------------
;; Exit-code integrity — a printed failure MUST exit non-zero (rf2-jmrdhn).
;;
;; The false-green trap the bead closes: the runner PRINTS a failing
;; assertion but exits 0, so a local gate reads green while CI goes red.
;; This drives the REAL runner across a process boundary (the only place the
;; exit code is observable) against a deliberately-failing suite and asserts
;; BOTH halves at once — the FAIL block reaches stdout AND the process exits
;; non-zero — so a regression that decouples the two (a swallowed red exit, a
;; run torn down before the `:end-run-tests` exit fires) is caught. The
;; negative control proves the exit is tied to the ACTUAL failure, not always
;; non-zero.

(deftest deliberately-failing-test-exits-nonzero
  (testing "a real failing test PRINTS its FAIL block AND exits non-zero (rf2-jmrdhn)"
    ;; The armed red fixture runs a genuine `(is (= :expected :actual))` that
    ;; fails — the ordinary counted-failure path, not an edge case. The whole
    ;; point of rf2-jmrdhn is that this must NOT be able to print a failure
    ;; while exiting 0.
    (let [red-ns "re-frame.test-quiet-red-warn-fixture-cljs-test"
          {status :status stdout :stdout stderr :stderr err :error
           timed-out? :timed-out?}
          (spawn-runner [(str "--test=" red-ns)]
                        {:env {:RF2_TQ_RED_WARN_FIXTURE "1"}})]
      (is (nil? err)
          (str "spawning the real runner must not error; got: " (pr-str err)))
      (is (not timed-out?)
          "the armed fixture run must not time out")
      ;; The CORE pin: a printed failure MUST drive a non-zero exit.
      (is (and (number? status) (not (zero? status)))
          (str "a deliberately-failing test must exit NON-ZERO — a printed"
               " failure with a 0 exit is the local-green≠CI-red trap"
               " (rf2-jmrdhn); got status " status
               "\n--- stdout ---\n" stdout "\n--- stderr ---\n" stderr))
      ;; It genuinely RAN (not a false green from running nothing)…
      (is (str/includes? stdout "Ran ")
          (str "the suite must have actually run (a `Ran ...` summary) — a"
               " non-zero exit with no run would be a different failure;"
               " got:\n" stdout))
      ;; …and the failure was PRINTED — the exact symptom the exit code must
      ;; now agree with.
      (is (str/includes? stdout "FAIL in")
          (str "the FAIL block must reach stdout AND the exit must be"
               " non-zero — the two must never disagree (rf2-jmrdhn); got:\n"
               stdout))))
  (testing "the SAME fixture is GREEN + exits 0 when unarmed (negative control)"
    ;; Proving the non-zero exit is tied to the ACTUAL failure, not a runner
    ;; that always exits non-zero.
    (let [red-ns "re-frame.test-quiet-red-warn-fixture-cljs-test"
          {status :status stdout :stdout stderr :stderr err :error}
          (spawn-runner [(str "--test=" red-ns)])]
      (is (nil? err)
          (str "spawning the real runner must not error; got: " (pr-str err)))
      (is (zero? status)
          (str "the unarmed fixture is GREEN; a correct run must still exit 0"
               " — the exit code tracks the real result, it is not always"
               " non-zero; got status " status
               "\n--- stdout ---\n" stdout "\n--- stderr ---\n" stderr))
      (is (not (str/includes? stdout "FAIL in"))
          (str "the unarmed fixture prints no failure; got:\n" stdout)))))

;; ----------------------------------------------------------------------
;; Shared spawn timeout/output policy (rf2-8dfq5j.3).
;;
;; The process-level pins above all route through `spawn-runner`, which
;; applies a single `:timeout` + `:maxBuffer` policy to every spawn so a
;; wedged or runaway child fails fast with a diagnostic rather than
;; hanging the whole CLJS suite.  This pins the fail-fast behaviour itself
;; via a deliberately-hanging child so a future change cannot silently
;; drop the timeout: a child that never exits must surface a SIGTERM
;; timeout, not block forever.  We spawn the node binary directly on a
;; tiny inline program (no runner needed) with a SHORT explicit timeout so
;; the test stays fast.

(deftest spawn-timeout-kills-a-hanging-child
  (testing "spawnSync timeout terminates a child that never exits (rf2-8dfq5j.3)"
    (let [;; A child that blocks forever: an idle interval keeps the event
          ;; loop alive with no exit path.
          res (.spawnSync node-child-process
                          (aget js/process.argv 0) ; node binary
                          #js ["-e" "setInterval(function(){}, 1000)"]
                          #js {:encoding  "utf8"
                               :timeout   1500
                               :maxBuffer (* 1024 1024)})]
      ;; The CORE pin: spawnSync must have KILLED the child on timeout
      ;; (SIGTERM), proving the shared policy fails fast instead of
      ;; hanging. A regression that dropped `:timeout` would block here
      ;; forever (the child never exits on its own).
      (is (= "SIGTERM" (.-signal res))
          (str "a hanging child must be SIGTERM-killed on the spawnSync"
               " timeout (rf2-8dfq5j.3); got signal " (pr-str (.-signal res))
               " status " (pr-str (.-status res))))
      (is (some? (.-error res))
          "spawnSync must surface an ETIMEDOUT-class error on the timeout"))))
