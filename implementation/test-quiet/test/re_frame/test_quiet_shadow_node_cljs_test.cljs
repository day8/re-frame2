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
  (testing "the ns-load stub is installed in this build (shadow-node is :main)"
    ;; Soft evidence the silencing stub is live: calling console.warn at
    ;; the baseline must not throw. This catches a regression that replaced
    ;; the stub with something that errors on a bare call.
    (is (nil? (js/console.warn "stub-smoke"))
        "the live baseline console.warn must accept a bare call without throwing")))
