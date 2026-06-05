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
     selectors; `--list` / `--help` flags; unknown args are tolerated.
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
            [re-frame.test-quiet.shadow-node-cli :as cli]))

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
  (testing "unknown args are tolerated and do not abort parsing"
    (is (= {:test-syms '[ok.ns]}
           (cli/parse-args ["--bogus" "--test=ok.ns"]))
        "an unknown flag is skipped; later valid flags still parse")))

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
