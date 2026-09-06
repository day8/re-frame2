(ns re-frame.test-quiet-shadow-node-cljs-test
  "Contract pin for the CLJS `:node-test` entry point
  `re-frame.test-quiet.shadow-node`.

  The shadow-node runner carries custom CLI parsing, var-filtering, an
  exit-code defmethod, and a global `console.warn` stub. This namespace
  pins pure helpers in-process and uses child processes for discovery and
  exit paths that would tear down the current node runner.

  Contracts pinned:

   - `--test=` SELECTION: comma-split into symbols, simple symbols are
     namespace selectors and qualified symbols are single-var
     selectors; `--list` / `--help` flags; unknown args are COLLECTED by
     the pure parser (into `:unknown-args`) and rejected as a fatal parse
     error by `execute-cli`; the process-level pins below
     prove that boundary.
   - EXIT-CODE INTEGRITY: the `:end-run-tests` defmethod exits 0 on a
     green summary and 1 on a red one, and two safeguards stop a run
     draining to a false green — the red warning replay is wrapped so a
     throw cannot pre-empt the nonzero exit, and `execute-cli` seeds
     `process.exitCode = 1` so a run that never dispatches the defmethod
     still fails.  All four are pinned at the REAL process boundary, the
     two safeguards each through their own fault fixture.  None is pinned
     in-process: a summary predicate is upstream `cljs.test`'s, and the
     mere presence of a `[:cljs.test/default :end-run-tests]` method is
     ClojureScript's own no-op — neither is evidence about this runner
     (rf2-6r9j.89).
   - console.warn CAPTURE COMPAT: the ns-load `console.warn` stub does
     not break the local save/shim/restore capture pattern that
     warning-assertion tests use — a shim installed over the stub still
     records, and restore reverts to the stub.

  Nested-run banner coverage is JVM-only. Those tests live in the JVM
  `re-frame.test-quiet-runner-contract-test`, not here.  A nested
  `cljs.test/run-tests` cannot be exercised in-process under this
  runner: `run-tests` is block-based/async and unconditionally fires
  `:end-run-tests`, which shadow-node overrides to `js/process.exit`
  — a nested run would tear
  down the node runner before the outer assertion. The implementation is shared
  CLJC (the banner ns is derived from the failing var's metadata, not a
  clobberable global cell), so the CLJS reporter benefits identically
  for sequential runs; only the nesting test is JVM-scoped."
  ;; NB: must NOT require re-frame.test-quiet.shadow-node — that ns is
  ;; `:dev/always` and expands the test-ns-enumeration macro, so a test
  ;; requiring it forms a compile cycle. Pure CLI parsing lives in the
  ;; `-cli` ns; the console.warn stub it installs is live at runtime
  ;; anyway because shadow-node is the :node-test build's :main.
  (:require [cljs.test :refer-macros [deftest is testing]]
            [clojure.string :as str]
            [re-frame.test-quiet.shadow-node-cli :as rf.test-quiet.shadow-node-cli]
            [re-frame.test-quiet.warn-buffer :as rf.test-quiet.warn-buffer]))

;; ----------------------------------------------------------------------
;; --test= selection / flag parsing.

(deftest parse-args-test-selection
  (testing "--test= splits on comma into symbols"
    (is (= {:test-syms '[my.ns]}
           (rf.test-quiet.shadow-node-cli/parse-args ["--test=my.ns"]))
        "a single simple symbol selects a whole namespace")
    (is (= {:test-syms '[my.ns other.ns]}
           (rf.test-quiet.shadow-node-cli/parse-args ["--test=my.ns,other.ns"]))
        "comma-separated values become multiple symbols")
    (is (= {:test-syms '[my.ns/a-test]}
           (rf.test-quiet.shadow-node-cli/parse-args ["--test=my.ns/a-test"]))
        "a qualified symbol selects a single var")
    (is (= {:test-syms '[my.ns my.ns/a-test]}
           (rf.test-quiet.shadow-node-cli/parse-args ["--test=my.ns,my.ns/a-test"]))
        "ns and fqn selectors coexist")
    (is (= {:test-syms '[a b c d]}
           (rf.test-quiet.shadow-node-cli/parse-args ["--test=a,b" "--test=c,d"]))
        "repeated --test= flags accumulate into one list")))

(deftest parse-args-flags
  (testing "--list and --help set their flags and default an empty test-syms"
    (is (= {:test-syms [] :list true} (rf.test-quiet.shadow-node-cli/parse-args ["--list"])))
    (is (= {:test-syms [] :help true} (rf.test-quiet.shadow-node-cli/parse-args ["--help"]))))
  (testing "no args yields the run-all default (empty test-syms, no flags)"
    (is (= {:test-syms []} (rf.test-quiet.shadow-node-cli/parse-args []))))
  (testing "unknown args are collected (not printed); the pure parser does not abort"
    ;; The pure parser must NOT print on an unknown arg — it collects
    ;; them into `:unknown-args` and the real CLI path (`execute-cli`)
    ;; reports them and exits nonzero, pinned at the process boundary below.
    ;; Keeping the parser pure and silent is what keeps
    ;; the green-run contract gate truly silent-on-success: a
    ;; `(println \"Unknown arg: ...\")` here would leak a non-summary line
    ;; into every consolidated `npm run test:cljs` run.
    (is (= {:test-syms '[ok.ns] :unknown-args ["--bogus"]}
           (rf.test-quiet.shadow-node-cli/parse-args ["--bogus" "--test=ok.ns"]))
        "an unknown flag is collected; later valid flags still parse")
    (is (= {:test-syms '[ok.ns] :unknown-args ["--bogus" "--nope"]}
           (rf.test-quiet.shadow-node-cli/parse-args ["--bogus" "--test=ok.ns" "--nope"]))
        "multiple unknown flags accumulate in input order")
    (is (not (contains? (rf.test-quiet.shadow-node-cli/parse-args ["--test=ok.ns"]) :unknown-args))
        "a clean arg set carries no :unknown-args key at all")))

;; ----------------------------------------------------------------------
;; Warning-ring entry-count and backing-vector bound.
;;
;; The buffered-`console.warn` ring retains the newest `warn-buffer-cap`
;; calls. Individual arguments are not byte-bounded. A `subvec` in
;; ClojureScript shares and retains its underlying
;; vector via `.-v` (and `conj`-ing onto a `Subvec` grows that underlying
;; vector), so the helper materialises the trimmed window into a fresh
;; `PersistentVector`. This is a structural property (the backing must not be a growing
;; `Subvec`), so it can only be pinned as a pure unit test — not across the
;; process boundary the other shadow-node pins use.

(deftest warn-buffer-is-bounded-and-materialised
  (testing "bound-conj retains only the newest cap entries as a fresh vector"
    ;; Fill FAR past a tiny cap: the ring must report exactly `cap` entries,
    ;; hold the NEWEST ones, and — the core pin — its backing must NOT be a
    ;; Subvec (which would retain the full discarded history via its shared
    ;; underlying vector).
    (let [capacity 4
          buffer   (reduce (fn [current-buffer entry]
                             (rf.test-quiet.warn-buffer/bound-conj current-buffer [entry] capacity))
                           []
                           (range 1000))]
      (is (= capacity (count buffer))
          "the ring is bounded to cap entries no matter how many are appended")
      (is (= [[996] [997] [998] [999]] buffer)
          "the ring retains the NEWEST cap entries, dropping the oldest")
      (is (not (instance? cljs.core/Subvec buffer))
          (str "the trimmed ring must NOT be a Subvec — a Subvec shares and"
               " retains its full underlying vector, so the bound would leak"
               " every discarded warning until process exit"))
      (is (instance? cljs.core/PersistentVector buffer)
          "the trimmed ring is a materialised PersistentVector, not a view")))
  (testing "below the cap the ring is a plain growing vector (no trim, no Subvec)"
    (let [buffer (reduce (fn [current-buffer entry]
                           (rf.test-quiet.warn-buffer/bound-conj current-buffer [entry] 10))
                         []
                         (range 3))]
      (is (= [[0] [1] [2]] buffer))
      (is (not (instance? cljs.core/Subvec buffer))
          "an untrimmed ring is never a Subvec")))
  (testing "under the REAL default cap the ring never exceeds warn-buffer-cap"
    ;; Drive several multiples of the real cap through the real default arity
    ;; so a regression that dropped the materialisation (or the cap) is caught
    ;; against the production constant, not just a synthetic tiny cap.
    (let [buffer (reduce (fn [current-buffer entry]
                           (rf.test-quiet.warn-buffer/bound-conj current-buffer [entry]))
                         []
                         (range (* 4 rf.test-quiet.warn-buffer/warn-buffer-cap)))]
      (is (= rf.test-quiet.warn-buffer/warn-buffer-cap (count buffer))
          "the ring stays capped at the production warn-buffer-cap")
      (is (not (instance? cljs.core/Subvec buffer))
          "the production-cap ring must not degrade into a retaining Subvec"))))

;; ----------------------------------------------------------------------
;; Var filtering — simple symbols match by namespace, qualified by fully-qualified
;; var name. Driven against synthetic var-like maps so the pin does not depend
;; on the live test-data registry.

(defn- fake-var
  "A stand-in for a test var: `meta` returns namespace/name metadata, matching
  what `cli/select-matching-test-vars` reads."
  [test-namespace test-name]
  (with-meta (fn []) {:ns test-namespace :name test-name}))

(deftest find-matching-test-vars-filtering
  ;; This drives the SHIPPED selector. `shadow-node/find-matching-test-vars`
  ;; is `cli/select-matching-test-vars` over the live `(env/get-test-vars)`
  ;; registry, which cannot be injected here — so the registry lookup is the
  ;; only part this cannot reach, and the RULE is exercised directly.
  ;;
  ;; It used to be a handwritten COPY of the predicate, which is a false
  ;; green by construction: production could stop matching qualified symbols
  ;; entirely and this stayed green (rf2-6r9j.76). `select-syms` below only
  ;; RENDERS the returned vars as symbols; it makes no selection decision.
  (let [test-vars   [(fake-var 'my.ns 'a-test)
                     (fake-var 'my.ns 'b-test)
                     (fake-var 'other.ns 'c-test)]
        select-syms (fn [test-selectors]
                      (->> (rf.test-quiet.shadow-node-cli/select-matching-test-vars test-selectors
                                                          test-vars)
                           (map (fn [test-var]
                                  (let [{test-namespace :ns test-name :name}
                                        (meta test-var)]
                                    (symbol test-namespace test-name))))))]
    (testing "a simple symbol selects every var in that namespace"
      (is (= '[my.ns/a-test my.ns/b-test]
             (select-syms '[my.ns]))))
    (testing "a qualified symbol selects exactly that var"
      (is (= '[my.ns/b-test]
             (select-syms '[my.ns/b-test]))))
    (testing "namespace + fully-qualified selectors combine"
      (is (= '[my.ns/a-test my.ns/b-test other.ns/c-test]
             (select-syms '[my.ns other.ns/c-test]))))
    (testing "an unmatched selector yields nothing"
      (is (= '[] (select-syms '[absent.ns]))))
    (testing "no selectors select nothing (the whole-suite path, not this one)"
      (is (= '[] (select-syms '[]))))
    (testing "selection does not depend on HOW MANY selectors were given"
      ;; Nine, because eight is where ClojureScript's set representation
      ;; changes: up to eight entries a set is array-map-backed and finds a
      ;; key by `=`, above that it hashes. A qualified selector is matched
      ;; against a symbol rebuilt from a var's `{:ns :name}` METADATA, whose
      ;; parts are symbols, not strings — `=` to the reader's `ns/name` but
      ;; not hash-equal to it — so every qualified selector silently stopped
      ;; matching at the ninth. Found by driving the shipped selector; the
      ;; copied predicate this row replaced could not see it (rf2-6r9j.76).
      (let [many-vars (mapv #(fake-var 'many.ns (symbol (str "t" %))) (range 9))
            many-syms (mapv #(symbol "many.ns" (str "t" %)) (range 9))]
        (is (= many-syms
               (mapv (fn [test-var]
                       (let [{test-namespace :ns test-name :name} (meta test-var)]
                         (symbol (str test-namespace) (str test-name))))
                     (rf.test-quiet.shadow-node-cli/select-matching-test-vars many-syms many-vars)))
            "nine qualified selectors must select all nine of their vars")))))

;; ----------------------------------------------------------------------
;; Unmatched-selector guard: a `--test=<selector>` that
;; matches NO test var must be rejected, not reported as a 0-test SUCCESS.
;;
;; `run-test-vars` over an empty set reports a 0-test success. The runner
;; must reject unmatched selectors before running.
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
      (is (= '() (rf.test-quiet.shadow-node-cli/unmatched-selectors '[my.ns] all-vars)))
      ;; fqn selector matches: my.ns/a-test is in the matched set.
      (is (= '() (rf.test-quiet.shadow-node-cli/unmatched-selectors '[my.ns/a-test] [a-test]))))
    (testing "--test=missing.ns (absent namespace) is reported unmatched -> guard exits nonzero"
      ;; A typo'd namespace produces a non-empty unmatched set, which the
      ;; process-level branch rejects.
      (let [unmatched (rf.test-quiet.shadow-node-cli/unmatched-selectors '[missing.ns] [])]
        (is (= '[missing.ns] unmatched)
            "an absent namespace selector matches nothing -> unmatched")
        (is (seq unmatched)
            "non-empty unmatched -> execute-cli takes the exit-1 branch, NOT run-test-vars")))
    (testing "--test=missing.ns/a-test (absent var) is reported unmatched -> guard exits nonzero"
      (let [unmatched (rf.test-quiet.shadow-node-cli/unmatched-selectors '[missing.ns/a-test] [])]
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
             (rf.test-quiet.shadow-node-cli/unmatched-selectors '[my.ns/c-test] all-vars))
          "a qualified selector for an absent var is unmatched even when its ns has other matches"))
    (testing "a mix of matched + unmatched reports only the unmatched, in input order"
      (is (= '[gone.ns missing.ns/x]
             (rf.test-quiet.shadow-node-cli/unmatched-selectors '[my.ns gone.ns my.ns/a-test missing.ns/x]
                                      [a-test]))
          "matched selectors drop out; unmatched ones survive in order"))))

;; ----------------------------------------------------------------------
;; Whole-suite test-count floor (rf2-qqzmf) — the pure half.
;;
;; `unmatched-selectors` above guards the `--test=` path. The whole-suite
;; path has the same hazard for a different reason: shadow-cljs's
;; `find-test-namespaces` returns `[]` when a build's `:ns-regexp` matches
;; nothing, silently, and `run-all-tests` over an empty set reports a 0-test
;; success. `parse-min-tests` resolves the floor that closes it.
;;
;; Only the resolution is pinned in-process. The real whole-suite floor is
;; NOT driven through `spawn-runner`: a spawn with no `--test=` selector runs
;; the entire build — including this namespace — so a regressed guard would
;; recurse rather than fail cleanly. The lane-level proof (zero-test build
;; reds, ordinary build passes) belongs to the gate run, not to a child of
;; the suite it is gating.

(deftest min-tests-floor-resolution
  (testing "an unset or blank RF2_MIN_TESTS resolves to the default floor"
    ;; Default 1, not 0: the bound that can never go stale, since no build
    ;; legitimately ships zero tests.
    (is (= 1 rf.test-quiet.shadow-node-cli/default-min-tests))
    (is (= rf.test-quiet.shadow-node-cli/default-min-tests (rf.test-quiet.shadow-node-cli/parse-min-tests nil)))
    (is (= rf.test-quiet.shadow-node-cli/default-min-tests (rf.test-quiet.shadow-node-cli/parse-min-tests "")))
    (is (= rf.test-quiet.shadow-node-cli/default-min-tests (rf.test-quiet.shadow-node-cli/parse-min-tests "   "))))
  (testing "a non-negative integer is honoured, with surrounding whitespace trimmed"
    (is (= 0 (rf.test-quiet.shadow-node-cli/parse-min-tests "0")) "0 explicitly disables the floor")
    (is (= 1 (rf.test-quiet.shadow-node-cli/parse-min-tests "1")))
    (is (= 3000 (rf.test-quiet.shadow-node-cli/parse-min-tests "3000")))
    (is (= 3000 (rf.test-quiet.shadow-node-cli/parse-min-tests " 3000 "))))
  (testing "a malformed value is ::invalid, never a silent fall back to the default"
    ;; The whole point of the gate is catching silent non-execution; a typo'd
    ;; floor quietly disabling it would be the same bug in a new place.
    (doseq [bad ["1O" "abc" "1.5" "-1" "1e3x" "٣"]]
      (is (= :re-frame.test-quiet.shadow-node-cli/invalid
             (rf.test-quiet.shadow-node-cli/parse-min-tests bad))
          (str (pr-str bad) " must be rejected, not coerced")))))

;; ----------------------------------------------------------------------
;; NO in-process failure-exit unit test lives here, deliberately (rf2-6r9j.89).
;;
;; The one that did asserted upstream `cljs.test/successful?`'s own
;; behaviour, and that SOME method is registered for
;; `[:cljs.test/default :end-run-tests]` — but ClojureScript itself defines a
;; no-op method under exactly that key (cljs/test.cljs), so neither clause
;; said anything about this runner.  Invoking the real defmethod in-process
;; is not an option either: it calls `js/process.exit`.
;;
;; The decision is pinned end-to-end instead, by the process rows below:
;; a green focused run exits 0, a red one exits 1, and — because
;; `execute-cli` seeds `process.exitCode = 1` before every run — a build that
;; LOST the runner's defmethod and fell back to ClojureScript's no-op would
;; drain to 1 and red `real-shadow-node-green-run-is-quiet`.  Ownership of
;; the exit signal is therefore proven, not asserted.

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
;; Process-level quiet shape.
;;
;; This test spawns the same built `out/node-test.js` shadow-node
;; runner this very process is executing, focused on a known-GREEN suite
;; (`re-frame.test-quiet-green-fixture-cljs-test`, two trivially passing
;; tests),
;; captures its stdout, and fails if any green line other than the
;; allowed canonical summary appears. The `console.warn` stub itself is
;; pinned separately above because this fixture deliberately emits no warning.

(def ^:private node-child-process (js/require "child_process"))

(def ^:private allowed-green-line-re
  "A non-blank green stdout line must be one of the two canonical summary
  lines.  `Ran N tests containing M assertions.` / `K failures, J errors.`"
  #"^(Ran \d+ tests containing \d+ assertions\.|\d+ failures, \d+ errors\.)$")

(def ^:private spawn-timeout-env-var
  "Override for the spawn ceiling, in milliseconds.  A machine running
  several worker checkouts at once needs a larger one than a quiet CI
  runner; see `default-spawn-timeout-ms` for why the ceiling is a
  wall-clock backstop rather than a performance assertion."
  "RF2_SPAWN_TIMEOUT_MS")

(def ^:private default-spawn-timeout-ms
  "Hard ceiling for a spawned focused runner (rf2-hofhx).

  THIS IS A BACKSTOP, NOT A PERFORMANCE ASSERTION.  The previous 60 s
  value was justified by the claim that `a correctly-exiting child
  returns in well under a second`.  That was never true of this build.
  `out/node-test.js` is a dev-mode loader: every spawn re-`require`s the
  whole consolidated node-test output — measured on this tree at 4201
  modules / 484 MB — so a perfectly healthy child costs

    ~10-13 s   on an idle box
    ~30-450 s  while sibling worker checkouts saturate the machine
               (measured: 449 s, and the child still exited 0)

  Against a 60 s ceiling that starves a HEALTHY child to death, and the
  resulting failure is indistinguishable at a glance from a regression:
  spawnSync reports ETIMEDOUT with both child streams empty.  The field
  report has the failure count tracking box load on an unchanged tree,
  0 -> 9 -> 18 -> 9.

  Raising it does NOT weaken the fail-fast contract.  That contract is
  pinned separately and strictly by `spawn-timeout-kills-a-hanging-child`,
  which proves a never-exiting child is SIGTERM-killed using its own 1.5 s
  ceiling.  This constant only bounds how long a wedged child may stall
  the suite before that same mechanism ends it.

  WHY 600 s AND NOT SOMETHING TIGHTER.  The two errors are not symmetric.
  Too tight costs a false RED on an honest change — the failure this bead
  is about, which burns a 15-minute suite and a human diagnosis every time
  it fires.  Too loose costs one wedged child sitting for a few extra
  minutes before the mechanism above ends it anyway, in a scenario that has
  never been observed.  On an unloaded CI runner a child costs ~10 s, so
  this ceiling is never approached there and the choice is free; it is only
  reachable on a developer box running several checkouts, which is exactly
  the case that must not go red.  600 s is set above the slowest HEALTHY
  child actually measured (449 s), not guessed."
  600000)

(defn- resolve-spawn-timeout-ms
  "Parse the ceiling override.  A malformed value THROWS rather than
  falling back: `RF2_SPAWN_TIMEOUT_MS=60O` (letter O) silently restoring a
  starvation-prone ceiling would be this defect wearing a hat."
  [raw]
  (if (or (nil? raw) (str/blank? (str raw)))
    default-spawn-timeout-ms
    (let [n (js/Number (str/trim (str raw)))]
      (when-not (and (js/Number.isInteger n) (pos? n))
        (throw (ex-info (str spawn-timeout-env-var "=\"" raw "\" is not a positive"
                             " integer number of milliseconds.  Leave it unset for"
                             " the default (" default-spawn-timeout-ms " ms).")
                        {:value raw})))
      n)))

(def ^:private spawn-timeout-ms
  (resolve-spawn-timeout-ms (aget js/process.env spawn-timeout-env-var)))

(def ^:private spawn-max-buffer-bytes
  "Output cap for a spawned focused runner — `spawnSync` kills the child
  and surfaces an ENOBUFS-class error once either stream exceeds this,
  so a runaway child cannot exhaust this process's memory."
  (* 8 1024 1024))

(defn- spawn-runner
  "Re-spawn the SAME built `out/node-test.js` shadow-node runner this
  process is executing, with `runner-args` (a CLJS vector of string args), and
  return `{:status :stdout :stderr :error :signal :timed-out?}`.

  `process.argv[1]` is the runner script path and `process.argv[0]` the
  node binary, so this exercises the REAL `shadow-node/main` -> `parse-args`
  -> `execute-cli` CLI path end-to-end across a process boundary — the only
  way to observe the `js/process.exit` codes the false-green guards branch
  on (calling `execute-cli` in-process would tear down this runner).

  `cli-options` is an optional CLJS map:
   - `:env` — extra environment entries (merged over the parent env) the
     child runs with;
  A shared `:timeout`/`:maxBuffer` policy is applied to EVERY spawn so a
  wedged or runaway child fails fast with a diagnostic rather than
  stranding the suite. `:timed-out?` is derived from a SIGTERM result; callers
  inspect `:error` to distinguish timeout and output-buffer failures."
  ([runner-args] (spawn-runner runner-args {}))
  ([runner-args {:keys [env]}]
   (let [runner-script (aget js/process.argv 1)
         child-environment (when env
                     (let [merged-environment
                           (js/Object.assign #js {} js/process.env)]
                       (doseq [[environment-name environment-value] env]
                         (aset merged-environment
                               (name environment-name)
                               environment-value))
                       merged-environment))
         spawn-result (.spawnSync node-child-process
                                  (aget js/process.argv 0) ; the node binary
                                  (apply array runner-script runner-args)
                                  (cond-> #js {:encoding  "utf8"
                                               :timeout   spawn-timeout-ms
                                               :maxBuffer spawn-max-buffer-bytes}
                                    child-environment
                                    (doto (aset "env" child-environment))))
         signal (.-signal spawn-result)]
     {:status     (.-status spawn-result)
      :stdout     (or (.-stdout spawn-result) "")
      :stderr     (or (.-stderr spawn-result) "")
      ;; cljs.test spawn errors (e.g. ENOENT, ETIMEDOUT, ENOBUFS) surface
      ;; on spawn-result.error.
      :error      (.-error spawn-result)
      :signal     signal
       ;; A timeout normally yields SIGTERM. This flag is intentionally only
       ;; a signal shorthand; `:error` carries the precise spawn failure.
      :timed-out? (= signal "SIGTERM")})))

(defn- spawn-error-explanation
  "Message for the `(is (nil? err) ...)` pin every process-level test makes.

  A spawn killed at the ceiling is STILL A FAILURE — a run whose child never
  started has verified nothing, and downgrading it to a skip would be a
  fail-open gate.  But it is a failure of the BOX, not of the diff under
  test, and the bare `spawnSync ... ETIMEDOUT` it used to print gave a
  reader nothing to tell those apart: both child streams are empty, so it
  reads exactly like a runner that produced no output.  That ambiguity cost
  real diagnosis time (rf2-hofhx), so the ETIMEDOUT case names the ceiling,
  the knob that moves it, and what the child was actually doing."
  [err]
  (str "spawning the real runner must not error; got: " (pr-str err)
       (when (and (some? err) (= "ETIMEDOUT" (.-code err)))
         (str "\n\n  The child was KILLED at the " spawn-timeout-ms " ms spawn"
              " ceiling, so BOTH of its streams are empty — it never produced"
              " a byte.\n  Each spawn re-loads the whole consolidated"
              " node-test build (~4200 dev modules), which costs ~10-13 s on"
              " an idle box\n  and has been measured above 400 s while sibling"
              " worker checkouts saturate the machine.  An oversubscribed box"
              "\n  is therefore a far likelier explanation than a defect in"
              " your change: re-run on a quiet box, or raise the ceiling with"
              "\n  " spawn-timeout-env-var "=<ms>.  A child that never ran"
              " cannot verify the contract, so this stays RED either way."))))

(deftest real-shadow-node-green-run-is-quiet
  (testing "the real out/node-test.js entry point emits only the canonical summary on green"
    ;; Re-running the runner focused on the green fixture exercises the real
    ;; CLI path end-to-end.
    (let [green-ns "re-frame.test-quiet-green-fixture-cljs-test"
          {status :status stdout :stdout stderr :stderr err :error}
          (spawn-runner [(str "--test=" green-ns)])]
      (is (nil? err)
          (spawn-error-explanation err))
      (is (zero? status)
          (str "the focused green run must exit 0; got " status
               "\n--- stdout ---\n" stdout "\n--- stderr ---\n" stderr))
      (let [non-blank (->> (str/split-lines stdout)
                           (map str/trim)
                           (remove str/blank?))]
        ;; No non-summary stdout line is allowed.
        (is (every? #(re-matches allowed-green-line-re %) non-blank)
            (str "a green run must emit ONLY the canonical summary lines —"
                 " any other stdout line (e.g. `Unknown arg`, a `Testing`"
                 " banner, a leaked warning) breaks silent-on-success"
                 ". Got non-blank lines:\n"
                 (str/join "\n" non-blank)))
        (is (not (str/includes? stdout "Unknown arg"))
            (str "`Unknown arg` must never reach a green run's stdout; got:\n"
                 stdout))
        (is (not (str/includes? stdout "Testing "))
            (str "no per-ns banner may leak on green; got:\n" stdout))
        ;; The fixture ns holds exactly TWO test vars, so `Ran 2` is also the
        ;; end-to-end proof that a SIMPLE symbol selects EVERY var in the
        ;; namespace — `Ran 1` would mean the namespace branch selected only
        ;; one. Its qualified-symbol sibling is the row below (rf2-6r9j.76).
        (is (some #(str/starts-with? % "Ran 2 tests") non-blank)
            (str "the namespace selector must run BOTH vars in the fixture"
                 " ns, and the `Ran ...` summary must still be present;"
                 " got:\n" stdout))
        (is (some #(re-matches #"0 failures, 0 errors\." %) non-blank)
            (str "the green tally line must be present; got:\n" stdout))))))

(deftest real-shadow-node-qualified-selector-runs-exactly-that-var
  (testing "--test=<ns>/<var> runs exactly that var, not its whole namespace"
    ;; The shipped selector's QUALIFIED-symbol branch, end to end.
    ;; `--test=<ns>/<var>` is a documented CLI form (`--help` names it) that
    ;; had no process-level proof at all: every other spawn here selects a
    ;; namespace or nothing (rf2-6r9j.76).  Paired with the `Ran 2` pin
    ;; above — same fixture ns, two vars — the two rows discriminate the two
    ;; branches: a runner treating a qualified symbol as a namespace selector
    ;; runs both HERE, one that dropped the namespace branch runs one THERE.
    (let [{status :status stdout :stdout stderr :stderr err :error}
          (spawn-runner
            ["--test=re-frame.test-quiet-green-fixture-cljs-test/a-passing-test"])]
      (is (nil? err)
          (spawn-error-explanation err))
      (is (zero? status)
          (str "a valid qualified selector must run and exit 0; got " status
               "\n--- stdout ---\n" stdout "\n--- stderr ---\n" stderr))
      (let [non-blank (->> (str/split-lines stdout)
                           (map str/trim)
                           (remove str/blank?))]
        (is (some #(str/starts-with? % "Ran 1 tests") non-blank)
            (str "exactly ONE var must run — `Ran 2` means the qualified"
                 " selector was treated as a namespace selector; got:\n"
                 stdout))
        (is (some #(re-matches #"0 failures, 0 errors\." %) non-blank)
            (str "the selected var passes; got:\n" stdout))))))

;; ----------------------------------------------------------------------
;; Process-level unknown-arg false-green guard.
;;
;; `parse-args` collects unknown args, and `execute-cli` must reject them
;; before falling through to `run-all-tests`. These tests exercise the real
;; process boundary where the exit code is observable.

(deftest unknown-arg-is-fatal-not-false-green
  (testing "a misspelled selector flag is fatal, not a green full-suite run"
    ;; `--tests=` (note the typo'd plural) is an unknown arg, not the
    ;; `--test=` selector and must exit nonzero without running tests.
    (let [{status :status stdout :stdout stderr :stderr err :error}
          (spawn-runner ["--tests=re-frame.test-quiet-green-fixture-cljs-test"])]
      (is (nil? err)
          (spawn-error-explanation err))
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
  (testing "space-separated --test and selector tokens are fatal unknown args"
    ;; The plausible space-separated form: `--test` and `missing.ns` are
    ;; BOTH unknown args (the parser only recognises the `--test=` glued
    ;; form), so no selector survives.
    (let [{status :status stdout :stdout stderr :stderr err :error}
          (spawn-runner ["--test" "missing.ns"])]
      (is (nil? err)
          (spawn-error-explanation err))
      (is (and (number? status) (not (zero? status)))
          (str "space-separated --test <selector> must exit NONZERO; got "
               status "\n--- stdout ---\n" stdout "\n--- stderr ---\n" stderr))
      (is (and (str/includes? stdout "Unknown arg: --test")
               (str/includes? stdout "Unknown arg: missing.ns"))
          (str "both unknown tokens must be named; got:\n" stdout))
      (is (not (str/includes? stdout "Ran "))
          (str "the suite must NOT have run; a `Ran ...` summary means the"
               " false-green fall-through to run-all-tests; got:\n" stdout))))
  ;; NO clean-focused-invocation control here, deliberately (rf2-6r9j.92).
  ;; It spawned the exact `--test=re-frame.test-quiet-green-fixture-cljs-test`
  ;; invocation `real-shadow-node-green-run-is-quiet` above already spawns,
  ;; and asserted a strict subset of that row's pins — no spawn error, exit 0,
  ;; no `Unknown arg` — for the price of another whole-bundle child start.
  ;; That row IS the positive control that valid args are not rejected.
  )

(deftest unmatched-selector-is-fatal-at-real-runner
  (testing "a parsed but unmatched selector exits nonzero at the real runner"
    ;; This drives the guard across the process boundary: a well-formed
    ;; `--test=` selector that matches no
    ;; test var must print the ERROR + exit NONZERO, never a 0-test green.
    (let [{status :status stdout :stdout stderr :stderr err :error}
          (spawn-runner ["--test=definitely.absent.namespace"])]
      (is (nil? err)
          (spawn-error-explanation err))
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
;; console.warn buffer red replay.
;;
;; The shadow-node `console.warn` stub buffers warnings in a bounded ring.
;; The `:end-run-tests` reporter replays the buffer to stderr only on a red
;; run, restoring the diagnostic context a failing CLJS run needs.  This
;; can only be observed across a process boundary (the replay fires just
;; before `js/process.exit`).  We drive the REAL runner against
;; `re-frame.test-quiet-red-warn-fixture-cljs-test`, whose warn-then-fail
;; behaviour is gated on `RF2_TQ_RED_WARN_FIXTURE=1` (so the whole-suite
;; run stays green): with the env var set the fixture warns + fails, and
;; the buffered warning must surface in the red output; with it UNSET the
;; same fixture is green and emits no warning.
;;
;; This row is ALSO the ordinary printed-failure/exit-status agreement pin: a
;; red run prints its `FAIL in` block and must exit nonzero, and the unarmed
;; control proves the exit tracks the real result rather than always being
;; nonzero. A separate regression used to spawn this same armed/unarmed pair
;; a second time for exactly those assertions (rf2-6r9j.89).

(deftest red-run-replays-warnings-and-exits-nonzero
  (testing "a red run replays the buffered console.warn diagnostic"
    (let [red-ns "re-frame.test-quiet-red-warn-fixture-cljs-test"
          {status :status stdout :stdout stderr :stderr err :error
           timed-out? :timed-out?}
          (spawn-runner [(str "--test=" red-ns)]
                        {:env {:RF2_TQ_RED_WARN_FIXTURE "1"}})]
      (is (nil? err)
          (spawn-error-explanation err))
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
               "; got\n--- stdout ---\n" stdout
               "\n--- stderr ---\n" stderr))
      (is (str/includes? (str stdout stderr) "[test-quiet] console.warn:")
          (str "the replay must label the buffered warnings; got\n"
               "--- stderr ---\n" stderr))
      ;; It genuinely RAN — a nonzero exit with no run is a different failure.
      (is (str/includes? stdout "Ran ")
          (str "the suite must have actually run (a `Ran ...` summary);"
               " got:\n" stdout))
      ;; …and the failure was PRINTED: the printed symptom and the exit code
      ;; must never disagree, which is the local-green-not-CI-red trap.
      (is (str/includes? stdout "FAIL in")
          (str "the FAIL block must reach stdout AND the exit must be"
               " nonzero — the two must never disagree; got:\n" stdout))))
  (testing "the SAME fixture is GREEN + quiet when unarmed (negative control)"
    ;; Without the env arming flag the fixture passes and emits no
    ;; warning, so the green-path quiet contract holds and the marker is
    ;; ABSENT from stdout — proving the warning is genuinely withheld on
    ;; green, not merely always printed.
    (let [red-ns "re-frame.test-quiet-red-warn-fixture-cljs-test"
          {status :status stdout :stdout stderr :stderr err :error}
          (spawn-runner [(str "--test=" red-ns)])]
      (is (nil? err)
          (spawn-error-explanation err))
      (is (zero? status)
          (str "the unarmed fixture is GREEN; must exit 0; got " status
               "\n--- stdout ---\n" stdout "\n--- stderr ---\n" stderr))
      (is (not (str/includes? stdout "RED-WARN-FIXTURE-MARKER"))
          (str "an unarmed (green) run must NOT emit the warning marker on"
               " stdout — green stays quiet; got:\n" stdout))
      (is (not (str/includes? stdout "FAIL in"))
          (str "the unarmed fixture prints no failure — the nonzero exit"
               " above tracks the REAL result; got:\n" stdout))
      (let [non-blank (->> (str/split-lines stdout)
                           (map str/trim)
                           (remove str/blank?))]
        (is (every? #(re-matches allowed-green-line-re %) non-blank)
            (str "a green run must emit ONLY the canonical summary lines;"
                 " got:\n" (str/join "\n" non-blank)))))))

;; ----------------------------------------------------------------------
;; Large red replay is not truncated by js/process.exit.
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
;; Synchronous fd writes make the guarantee independent of whether Node's
;; stderr stream is synchronous for the current host and destination.

(deftest large-red-replay-is-not-truncated
  (testing "a red replay that fills the ring with large warnings keeps its tail"
    (let [red-ns "re-frame.test-quiet-red-warn-fixture-cljs-test"
          {status :status stdout :stdout stderr :stderr err :error
           timed-out? :timed-out?}
          (spawn-runner [(str "--test=" red-ns)]
                        {:env {:RF2_TQ_RED_REPLAY_VOLUME "1"}})
          combined (str stdout stderr)]
      (is (nil? err)
          (spawn-error-explanation err))
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
      ;; truncated.
      (is (str/includes? combined "RED-REPLAY-TAIL-MARKER")
          (str "the TAIL of a large red replay must NOT be truncated by"
               " js/process.exit — fs.writeSync makes the write synchronous"
               "; got\n--- stdout ---\n" stdout
               "\n--- stderr ---\n" stderr)))))

;; ----------------------------------------------------------------------
;; Exit-code integrity: the two safeguards, one fault fixture each.
;;
;; `shadow-node`'s `:end-run-tests` defmethod is the only thing that turns a
;; red cljs.test summary into a nonzero node exit, and it carries two
;; safeguards against a run draining to a false green: the red warning replay
;; is wrapped so a throw cannot pre-empt `js/process.exit 1`, and
;; `execute-cli` seeds `process.exitCode = 1` before running so a run that
;; never dispatches the defmethod still fails.
;;
;; An ORDINARY red or green run traverses NEITHER — both already exited
;; correctly before either safeguard existed, so a regression could delete
;; either one and every ordinary row stayed green (rf2-6r9j.89). Each
;; safeguard therefore gets a run that ENTERS its own failure mode, driven
;; against `re-frame.test-quiet-exit-integrity-fixture-cljs-test`, and each
;; fixture emits a reached-state marker: a nonzero child status by itself is
;; also what a spawn error, a timeout, a parse error, a skipped suite or an
;; unrelated uncaught exception look like, and none of those may satisfy
;; these rows.

(def ^:private exit-integrity-fixture-ns
  "re-frame.test-quiet-exit-integrity-fixture-cljs-test")

(deftest red-replay-throw-cannot-mask-the-nonzero-exit
  (testing "a red run whose warning replay THROWS still exits 1, quietly"
    (let [{status :status stdout :stdout stderr :stderr err :error
           timed-out? :timed-out?}
          (spawn-runner [(str "--test=" exit-integrity-fixture-ns)]
                        {:env {:RF2_TQ_REPLAY_THROW_FIXTURE "1"}})
          combined (str stdout stderr)]
      (is (nil? err)
          (spawn-error-explanation err))
      (is (not timed-out?)
          "the armed replay-throw fixture run must not time out")
      ;; A GENUINE counted failure was reached — not a parse error, not a
      ;; skipped suite, not a namespace that failed to load.
      (is (str/includes? stdout "Ran ")
          (str "the suite must have actually run; got:\n" stdout))
      (is (str/includes? stdout "FAIL in")
          (str "a genuine counted failure must have been reached — without"
               " one the red replay never fires at all; got:\n" stdout))
      ;; The replay was ENTERED: its header is written before the poison.
      (is (str/includes? combined
                         "console.warn message(s) buffered during this run")
          (str "the red replay must have been entered; got\n"
               "--- stderr ---\n" stderr))
      ;; …and ABORTED part-way: the marker buffered AFTER the poison, which a
      ;; completed replay would print, never arrives.
      (is (not (str/includes? combined "EXIT-INTEGRITY-REPLAY-TAIL-MARKER"))
          (str "the replay must have THROWN part-way — the warning buffered"
               " after the poisoned one must not have been replayed; got\n"
               "--- stderr ---\n" stderr))
      ;; The CORE pin. Note that STATUS alone cannot discriminate: with the
      ;; try/catch removed the same exception escapes, node prints it and
      ;; exits 1 too. What discriminates is that the exception was SWALLOWED
      ;; — its message never reaches the output — so the exit is the
      ;; deliberate `js/process.exit 1`, not a crash that happened to agree.
      (is (= 1 status)
          (str "a red run whose replay throws must still exit 1; got " status
               "\n--- stdout ---\n" stdout "\n--- stderr ---\n" stderr))
      (is (not (str/includes? combined "EXIT-INTEGRITY-REPLAY-POISON"))
          (str "the replay exception must be swallowed by the guard, never"
               " surfaced as an uncaught runner crash; got\n"
               "--- stdout ---\n" stdout "\n--- stderr ---\n" stderr)))))

(deftest run-that-never-dispatches-the-exit-defmethod-drains-nonzero
  (testing "a GREEN run whose exit defmethod is a no-op drains on the seed"
    (let [{status :status stdout :stdout stderr :stderr err :error
           timed-out? :timed-out?}
          (spawn-runner [(str "--test=" exit-integrity-fixture-ns)]
                        {:env {:RF2_TQ_NO_EXIT_DISPATCH_FIXTURE "1"}})]
      (is (nil? err)
          (spawn-error-explanation err))
      (is (not timed-out?)
          "a run draining on the seeded exit code must not hang")
      ;; The fixture reached the state under test. Without this marker a
      ;; nonzero status proves nothing about the seed.
      (is (str/includes? stdout "EXIT-INTEGRITY-NO-EXIT-DISPATCH-INSTALLED")
          (str "the fixture must have replaced the exit defmethod; got:\n"
               stdout))
      ;; The suite RAN and was GREEN, so nothing but the seed is left to
      ;; explain a nonzero status: no parse error, no failure, no crash.
      (is (str/includes? stdout "Ran ")
          (str "the suite must have actually run; got:\n" stdout))
      (is (str/includes? stdout "0 failures, 0 errors.")
          (str "the run must be GREEN — a real failure would explain the"
               " nonzero exit without the seed; got:\n" stdout))
      (is (not (str/includes? stdout "Unknown arg"))
          (str "no parse error may explain the exit; got:\n" stdout))
      (is (not (str/includes? stdout "no tests matched"))
          (str "no unmatched selector may explain the exit; got:\n" stdout))
      ;; The CORE pin: exit 1 out of `process.exitCode`, drained after a green
      ;; run that never called `js/process.exit`. Without `seed-failure-exit!`
      ;; this child exits 0 — the silent false green the seed exists to stop.
      (is (= 1 status)
          (str "a run that never dispatches the exit defmethod must drain"
               " with the SEEDED 1, never 0; got status " status
               "\n--- stdout ---\n" stdout "\n--- stderr ---\n" stderr)))))

;; ----------------------------------------------------------------------
;; Shared spawn timeout/output policy.
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
  (testing "spawnSync timeout terminates a child that never exits"
    (let [;; A child that blocks forever: an idle interval keeps the event
          ;; loop alive with no exit path.
          spawn-result (.spawnSync node-child-process
                                   (aget js/process.argv 0) ; node binary
                                   #js ["-e" "setInterval(function(){}, 1000)"]
                                   #js {:encoding  "utf8"
                                        :timeout   1500
                                        :maxBuffer (* 1024 1024)})]
      ;; The CORE pin: spawnSync must have KILLED the child on timeout
      ;; (SIGTERM), proving the shared policy fails fast instead of
      ;; hanging. A regression that dropped `:timeout` would block here
      ;; forever (the child never exits on its own).
      (is (= "SIGTERM" (.-signal spawn-result))
          (str "a hanging child must be SIGTERM-killed on the spawnSync"
               " timeout; got signal " (pr-str (.-signal spawn-result))
               " status " (pr-str (.-status spawn-result))))
      (is (some? (.-error spawn-result))
          "spawnSync must surface an ETIMEDOUT-class error on the timeout"))))
