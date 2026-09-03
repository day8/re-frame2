(ns re-frame.test-quiet-runner-contract-test
  "Subprocess contract pin for the JVM entry point
  `re-frame.test-quiet.runner/-main`.

  The stdout-line-count pin in `re-frame.test-quiet-pin-test` drives a
  green sub-suite through `clojure.test/run-tests` directly — it never
  touches the actual `-main` path the per-artefact `:test` aliases use,
  the JVM failure/error path, or the discovery-banner contract.  Those
  are the highest-risk promises in the runner's ns docs, and they can
  only be observed across a process boundary: `cognitect.test-runner`
  calls `System/exit` from the computed fail/error counts, so an
  in-process call would kill this test JVM.

  Each test here therefore relaunches a fresh JVM
  (`java -cp <this-classpath>+<fixtures> clojure.main -m
  re-frame.test-quiet.runner ...`) against a throwaway fixture suite
  and asserts on the captured stdout / stderr / exit code.  Reusing the
  running JVM's own `java.class.path` keeps the subprocess on the same
  classpath this test already has (src + cognitect-test-runner +
  clojure) without re-resolving deps or shelling through the `clojure`
  launcher — which dodges Windows path-escaping in `-Sdeps`.

  Contracts pinned (all against the REAL `-main`, not `run-tests`):

   - GREEN: exit 0; canonical silent-on-success summary; the
     cognitect `Running tests in #{...}` discovery banner is absent.
   - RED:   exit 1; the per-ns `Testing <ns>` banner + the `FAIL`
     block + `expected:`/`actual:` lines are present (diagnostic
     output survives the `*out*` swap via `with-test-out`); the
     discovery banner is still absent.
   - ERROR: exit 1; the `ERROR in` block + the thrown message reach
     stdout.
   - DISCOVERY: a file whose `(ns ...)` form the reader cannot read
     refuses the run (exit 1, named on stderr) instead of leaving it
     silently short a suite — and the same fixture is green the moment
     before that file arrives.
   - STDERR BUFFER: a green run that emits expected
     stderr warnings stays quiet (the warnings are buffered + dropped);
     a RED run REPLAYS the buffered stderr context so a failing run
     keeps it.  This is the JVM counterpart to the CLJS node runner's
     `console.warn` buffer + red replay."
  (:require [clojure.test :refer [deftest is testing]]
            [clojure.string :as str]
            [clojure.java.io :as io]))

;; ----------------------------------------------------------------------
;; Subprocess harness.

(def ^:private discovery-banner-marker
  "The bare-`println` artefact cognitect-test-runner emits in
  `cognitect.test-runner/test` — the one stdout line the runner swallows."
  "Running tests in")

(defn- write-fixture!
  "Write a fixture test ns `probe.<file-stem>` into `dir` under the
  underscore file layout cognitect-test-runner's classpath `require`
  expects.  `body` is the deftest source."
  [^java.io.File dir file-stem ns-suffix body]
  (let [pkg-dir (io/file dir "probe")]
    (.mkdirs pkg-dir)
    (spit (io/file pkg-dir (str file-stem ".clj"))
          (str "(ns probe." ns-suffix "\n"
               "  (:require [clojure.test :refer [deftest is]]\n"
               "            [re-frame.test-quiet]))\n"
               body "\n"))))

(defn- write-raw-fixture!
  "Write `source` verbatim as `probe/<file-stem>.clj` into `dir`.  Unlike
  `write-fixture!` this does NOT inject a fixed ns form, so the caller can
  control the ns name (e.g. a non-`*-test` inner suite the default
  discovery regex skips) and the require set (e.g. a nested `run-tests`
  over a sibling ns).  Used by the nested-run banner/tally regressions."
  [^java.io.File dir file-stem source]
  (let [pkg-dir (io/file dir "probe")]
    (.mkdirs pkg-dir)
    (spit (io/file pkg-dir (str file-stem ".clj")) (str source "\n"))))

(def ^:private default-drain-timeout-ms
  "Shared ceiling for a drained subprocess.  A correctly-exiting child
  returns in well under a second; this generous bound makes a wedged
  child FAIL with a structured `:timed-out?` diagnostic instead of
  hanging the whole JVM suite."
  60000)

(def ^:private force-kill-reap-timeout-ms
  "Bounded wait after `destroyForcibly`. The JDK only requests forced
  termination, so the timeout path gives the OS time to reap an ordinary
  child before returning. A pathological child may still outlive this
  secondary ceiling; the helper remains bounded either way."
  10000)

(defn- drain-process
  "Drain `process`'s stdout AND stderr concurrently, then `waitFor` UNDER A
  TIMEOUT.  Returns {:exit :out :err :timed-out?}.  The concurrent drain
  is what `invoke-quiet-runner` relies on to avoid a pipe deadlock;
  this helper is the shared primitive every subprocess path uses, so the
  deadlock regression proves the EXACT drain order the real harness
  ships.

  The timeout/kill policy lives here so every subprocess
  test inherits fail-fast behaviour: on expiry the child is force-killed
  (`destroyForcibly`), the drain threads are interrupted, and the result
  carries `:timed-out? true` plus whatever stdout/stderr drained before
  the kill — so an assertion sees a structured failure with command
  context, not a hung suite.  `:exit` is -1 on a timeout (no real exit
  code).  Pass `timeout-ms` to override the default ceiling."
  ([^Process process]
   (drain-process process default-drain-timeout-ms))
  ([^Process process timeout-ms]
   (let [stdout-future (future (slurp (.getInputStream process)))
         stderr-future (future (slurp (.getErrorStream process)))
         exited?       (.waitFor process timeout-ms
                                 java.util.concurrent.TimeUnit/MILLISECONDS)]
     (if exited?
       {:exit       (.exitValue process)
        :out        @stdout-future
        :err        @stderr-future
        :timed-out? false}
       (do
         ;; The child outlived the ceiling — force-kill it and interrupt
         ;; the drain threads so neither this helper nor the futures hang.
         (.destroyForcibly process)
         ;; `destroyForcibly` only REQUESTS termination; block (bounded)
         ;; for the OS to reap an ordinary child before returning. The wait
         ;; remains bounded for a pathological child.
         (.waitFor process force-kill-reap-timeout-ms
                   java.util.concurrent.TimeUnit/MILLISECONDS)
         (future-cancel stdout-future)
         (future-cancel stderr-future)
         {:exit       -1
          :out        (try @stdout-future (catch Throwable _ ""))
          :err        (try @stderr-future (catch Throwable _ ""))
          :timed-out? true})))))

(defn- invoke-quiet-runner-with-env
  "Relaunch a fresh JVM that runs `re-frame.test-quiet.runner/-main` with
  `runner-args`, putting `fixture-dir` on both the classpath and the
  runner's `-d` discovery set. `extra-environment` is a map of environment
  variables layered over the inherited environment (`{}` for none) — used by
  the `RF2_MIN_TESTS` floor pins.  Returns {:exit :out :err}."
  [^java.io.File fixture-dir extra-environment runner-args]
  (let [java-executable (str (io/file (System/getProperty "java.home") "bin"
                                      (if (str/includes?
                                            (str/lower-case
                                              (System/getProperty "os.name"))
                                            "win")
                                        "java.exe" "java")))
        path-separator (System/getProperty "path.separator")
        classpath      (str (System/getProperty "java.class.path")
                            path-separator
                            (.getAbsolutePath fixture-dir))
        command        (into [java-executable "-cp" classpath "clojure.main"
                        "-m" "re-frame.test-quiet.runner"
                        "-d" (.getAbsolutePath fixture-dir)]
                       runner-args)
        process-builder (doto (ProcessBuilder. ^java.util.List command)
                          (.redirectErrorStream false))
        child-environment (.environment process-builder)
        ;; Strip the floor variable from the INHERITED environment first, so
        ;; an outer shell that exported `RF2_MIN_TESTS` (for a different lane)
        ;; cannot perturb any pin here. Each floor pin then sets exactly the
        ;; value it means to test.
        _        (.remove child-environment "RF2_MIN_TESTS")
        _        (doseq [[environment-name environment-value] extra-environment]
                   (.put child-environment
                         (str environment-name)
                         (str environment-value)))
        process  (.start process-builder)]
    ;; Drain stdout and stderr concurrently: with separate
    ;; pipes, slurping stdout fully and only THEN reading stderr can
    ;; deadlock — a child that fills the stderr pipe blocks on write (so
    ;; it never closes stdout / exits), while the parent blocks on stdout
    ;; EOF and never reaches the stderr drain or `waitFor`.
    ;; `drain-process` reads both on their own threads, so both pipes keep
    ;; flowing regardless of which stream the child writes to.
    (drain-process process)))

(defn- invoke-quiet-runner
  "`invoke-quiet-runner-with-env` over the inherited environment."
  [^java.io.File fixture-dir & extra-args]
  (invoke-quiet-runner-with-env fixture-dir {} extra-args))

(defn- with-fixture-dir
  "Make a fresh temp dir, run `f` with it, then delete it."
  [f]
  (let [dir (.toFile (java.nio.file.Files/createTempDirectory
                       "tq-contract"
                       (make-array java.nio.file.attribute.FileAttribute 0)))]
    (try
      (f dir)
      (finally
        (doseq [file (reverse (file-seq dir))]
          (.delete ^java.io.File file))))))

;; ----------------------------------------------------------------------
;; Green path — the exact green stdout shape.
;;
;; This is the ONE one-passing-test green subprocess row. A weaker sibling
;; (`green-runner-contract`) spawned the same fixture through the same
;; `-main` and asserted a strict subset of what follows: exit 0, no
;; `Running tests in`, at most three non-blank lines, and `0 failures,
;; 0 errors.` appearing somewhere. Every one of those clauses follows from
;; the pins below, which fix the exact line at each position, so it bought
;; nothing but a second JVM process start (rf2-6r9j.92).
;;
;; cognitect's banner is `\nRunning tests in #{...}\n` — it opens with a
;; BLANK LINE.  Dropping only the `Running tests in #{...}` text left that
;; leading newline behind, so a green real `-main` run started with TWO
;; The filter must remove the banner's leading newline while preserving
;; clojure.test's own leading `\n` on the `:summary` line. This pin asserts
;; the exact line shape: a single leading blank, then the two summary
;; lines, and no `Running tests in` text anywhere.

(deftest green-runner-exact-shape
  (testing "a green real -main run has no leaked banner-leading blank line"
    (with-fixture-dir
      (fn [dir]
        (write-fixture! dir "exact_green_fixture_test" "exact-green-fixture-test"
                        "(deftest a-passing-test (is (= 1 1)))")
        (let [{:keys [exit out err]} (invoke-quiet-runner dir)
              ;; Normalise CRLF and split keeping empties so we can assert
              ;; on leading-blank position, not just non-blank count.
              lines (-> out (str/replace "\r" "") (str/split #"\n" -1))]
          (is (zero? exit)
              (str "green suite must exit 0; got " exit
                   "\n--- stdout ---\n" out "\n--- stderr ---\n" err))
          (is (not (str/includes? out "Running tests in"))
              (str "no part of the discovery banner — not even its text —"
                   " may reach stdout; got:\n" out))
          ;; The first line is the summary's own leading blank; the second
          ;; line is already the `Ran` line.
          (is (str/blank? (nth lines 0 nil))
              (str "the canonical summary opens with one leading blank;"
                   " got lines:\n" (pr-str lines)))
          (is (str/starts-with? (str (nth lines 1 "")) "Ran ")
              (str "the SECOND line must be the `Ran ...` summary — a"
                   " second leading blank means the banner's leading"
                   " newline leaked; got lines:\n"
                   (pr-str lines)))
          ;; And the full non-blank shape is exactly the canonical two.
          (let [non-blank (remove str/blank? lines)]
            (is (= 2 (count non-blank))
                (str "green stdout must be exactly the 2 non-blank summary"
                     " lines; got:\n" (str/join "\n" non-blank)))
            (is (str/starts-with? (first non-blank) "Ran ")
                (str "first non-blank line must be `Ran ...`; got:\n"
                     (str/join "\n" non-blank)))
            (is (= "0 failures, 0 errors." (second non-blank))
                (str "second non-blank line must be the failure tally;"
                     " got:\n" (str/join "\n" non-blank)))))))))

;; ----------------------------------------------------------------------
;; Red path — failure.

(deftest red-runner-contract
  (testing "a failing suite through the real -main: exit 1, diagnostics survive"
    (with-fixture-dir
      (fn [dir]
        (write-fixture! dir "red_jvm_fixture_test" "red-jvm-fixture-test"
                        "(deftest a-failing-test (is (= :exp :act)))")
        (let [{:keys [exit out err]} (invoke-quiet-runner dir)]
          (is (= 1 exit)
              (str "failing suite must exit 1; got " exit
                   "\n--- stdout ---\n" out "\n--- stderr ---\n" err))
          (is (str/includes? out "Testing probe.red-jvm-fixture-test")
              (str "the per-ns banner must be flushed on failure; got:\n" out))
          (is (str/includes? out "FAIL in (a-failing-test)")
              (str "the FAIL block must reach stdout; got:\n" out))
          (is (and (str/includes? out "expected:")
                   (str/includes? out "actual:"))
              (str "expected:/actual: lines must reach stdout; got:\n" out))
          (is (str/includes? out "1 failures, 0 errors.")
              (str "the failing summary must reach stdout; got:\n" out))
          (is (not (str/includes? out discovery-banner-marker))
              (str "the discovery banner must STILL be swallowed on red"
                   " (it is not replayed); captured stdout:\n" out)))))))

;; ----------------------------------------------------------------------
;; Red path — error.

(deftest error-runner-contract
  (testing "an erroring suite through the real -main: exit 1, ERROR block survives"
    (with-fixture-dir
      (fn [dir]
        (write-fixture! dir "error_jvm_fixture_test" "error-jvm-fixture-test"
                        (str "(deftest an-erroring-test"
                             " (is (= 1 (throw (ex-info \"boom-marker\" {})))))"))
        (let [{:keys [exit out err]} (invoke-quiet-runner dir)]
          (is (= 1 exit)
              (str "erroring suite must exit 1; got " exit
                   "\n--- stdout ---\n" out "\n--- stderr ---\n" err))
          (is (str/includes? out "ERROR in (an-erroring-test)")
              (str "the ERROR block must reach stdout; got:\n" out))
          (is (str/includes? out "boom-marker")
              (str "the thrown exception message must reach stdout; got:\n" out))
          (is (not (str/includes? out discovery-banner-marker))
              (str "the discovery banner must be swallowed on error too;"
                   " captured stdout:\n" out)))))))

;; ----------------------------------------------------------------------
;; Exit-code integrity for failures that escape clojure.test's counters.
;;
;; `red-runner-contract` / `error-runner-contract` above pin the COUNTED
;; paths (a failing `is`, a throwing `is`) — clojure.test tallies those and
;; cognitect exits 1 from the tally. Failures that clojure.test never counts
;; still propagate out of `-main` and must produce a nonzero process exit.
;; Two such cases exist on the JVM
;; and MUST still exit non-zero:
;;   - a namespace that THROWS at load time (cognitect `require`s it during
;;     discovery, so the throw is outside any `is`/report);
;;   - a `:once` fixture that throws (it runs OUTSIDE `test-var`, so its
;;     exception is not tallied as an `:error`).
;; Both propagate out of `-main`, so `clojure.main` exits non-zero — this
;; pins that no counter-escaping failure can slip out GREEN.

(deftest namespace-load-throw-exits-nonzero
  (testing "a test ns that throws at load time exits nonzero"
    (with-fixture-dir
      (fn [dir]
        (write-raw-fixture! dir "load_throw_test"
          (str "(ns probe.load-throw-test\n"
               "  (:require [clojure.test :refer [deftest is]]\n"
               "            [re-frame.test-quiet]))\n"
               ;; anchor so cognitect's contains-tests? selects the ns…
               "(deftest anchor (is (= 1 1)))\n"
               ;; …then a top-level throw at require time — outside any `is`,
               ;; so clojure.test never tallies it.
               "(throw (ex-info \"LOAD-THROW-MARKER\" {}))"))
        (let [{:keys [exit out err timed-out?]}
              (invoke-quiet-runner dir "-n" "probe.load-throw-test")]
          (is (not timed-out?)
              "the load-throw run must terminate, not hang")
          ;; The CORE pin: a load-time exception is not a counted test
          ;; failure, but it MUST still fail the process.
          (is (not (zero? exit))
              (str "a namespace that throws at load time must exit NON-ZERO"
                   " even though clojure.test never counts it;"
                   " got exit " exit "\n--- stdout ---\n" out
                   "\n--- stderr ---\n" err))
          (is (str/includes? (str out err) "LOAD-THROW-MARKER")
              (str "the load-time exception message must surface so the"
                   " failure is diagnosable; got\n--- stdout ---\n" out
                   "\n--- stderr ---\n" err)))))))

(deftest once-fixture-throw-exits-nonzero
  (testing "a :once fixture that throws exits nonzero"
    (with-fixture-dir
      (fn [dir]
        (write-raw-fixture! dir "fixture_throw_test"
          (str "(ns probe.fixture-throw-test\n"
               "  (:require [clojure.test :refer [deftest is use-fixtures]]\n"
               "            [re-frame.test-quiet]))\n"
               ;; A :once fixture runs OUTSIDE test-var; its throw is not
               ;; tallied as an :error, so a naive runner could exit 0.
               "(use-fixtures :once (fn [f] (throw (ex-info \"FIXTURE-THROW-MARKER\" {}))))\n"
               "(deftest a-test (is (= 1 1)))"))
        (let [{:keys [exit out err timed-out?]}
              (invoke-quiet-runner dir "-n" "probe.fixture-throw-test")]
          (is (not timed-out?)
              "the fixture-throw run must terminate, not hang")
          (is (not (zero? exit))
              (str "a :once fixture that throws must exit NON-ZERO even though"
                   " its exception is never tallied as a test error"
                   "; got exit " exit "\n--- stdout ---\n" out
                   "\n--- stderr ---\n" err))
          (is (str/includes? (str out err) "FIXTURE-THROW-MARKER")
              (str "the fixture exception message must surface; got"
                   "\n--- stdout ---\n" out "\n--- stderr ---\n" err)))))))

;; ----------------------------------------------------------------------
;; Nested-run banner correctness.
;;
;; A `deftest` that nests a `run-tests` over a sibling namespace and then
;; fails must still print `Testing <OUTER-ns>` above its own FAIL block.
;; Deriving the banner namespace from the failing var prevents the nested
;; run's `:begin-test-ns` from mislabelling or orphaning the outer failure.
;;
;; These run through the REAL `-main` with source-ordered real deftest
;; vars (the inner suite nests BEFORE the outer assertion in source
;; order), so they do not depend on `ns-interns` hash-map ordering. The inner suite is
;; named `*-suite` (not `*-test`) so cognitect's default discovery skips
;; it as a top-level ns — it is exercised only via the outer's nested
;; `run-tests` — and the run is pinned to the outer ns with `-n`.

(deftest nested-green-then-outer-fail-banner
  (testing "outer fail after a green nested run names the outer namespace"
    (with-fixture-dir
      (fn [dir]
        (write-raw-fixture! dir "nested_green_inner_suite"
          (str "(ns probe.nested-green-inner-suite\n"
               "  (:require [clojure.test :refer [deftest is]]\n"
               "            [re-frame.test-quiet]))\n"
               "(deftest inner-passes (is (= 1 1)))"))
        (write-raw-fixture! dir "nested_green_outer_test"
          (str "(ns probe.nested-green-outer-test\n"
               "  (:require [clojure.test :refer [deftest is run-tests]]\n"
               "            [re-frame.test-quiet]\n"
               "            [probe.nested-green-inner-suite]))\n"
               "(deftest outer-fails-after-green\n"
               "  (run-tests 'probe.nested-green-inner-suite)\n"
               "  (is (= :outer-exp :outer-act)))"))
        (let [{:keys [exit out err]}
              (invoke-quiet-runner dir "-n" "probe.nested-green-outer-test")]
          (is (= 1 exit)
              (str "outer fail must exit 1; got " exit
                   "\n--- stdout ---\n" out "\n--- stderr ---\n" err))
          (is (str/includes? out "FAIL in (outer-fails-after-green)")
              (str "the outer FAIL block must reach stdout; got:\n" out))
          ;; The outer failure must use the outer banner, not the namespace
          ;; most recently entered by the nested run.
          (is (str/includes? out "Testing probe.nested-green-outer-test")
              (str "the outer FAIL must carry its OWN ns banner"
                   "; got:\n" out))
          (is (not (str/includes? out "Testing probe.nested-green-inner-suite"))
              (str "a GREEN nested run must stay silent — its inner ns"
                   " banner must NOT appear (and must not be borrowed by"
                   " the outer failure); got:\n" out)))))))

(deftest nested-red-then-outer-fail-banner
  (testing "outer fail after a red nested run keeps both banners correct"
    (with-fixture-dir
      (fn [dir]
        (write-raw-fixture! dir "nested_red_inner_suite"
          (str "(ns probe.nested-red-inner-suite\n"
               "  (:require [clojure.test :refer [deftest is]]\n"
               "            [re-frame.test-quiet]))\n"
               "(deftest inner-fails (is (= :inner-exp :inner-act)))"))
        (write-raw-fixture! dir "nested_red_outer_test"
          (str "(ns probe.nested-red-outer-test\n"
               "  (:require [clojure.test :refer [deftest is run-tests]]\n"
               "            [re-frame.test-quiet]\n"
               "            [probe.nested-red-inner-suite]))\n"
               "(deftest outer-fails-after-red\n"
               "  (run-tests 'probe.nested-red-inner-suite)\n"
               "  (is (= :outer-exp :outer-act)))"))
        (let [{:keys [exit out err]}
              (invoke-quiet-runner dir "-n" "probe.nested-red-outer-test")]
          (is (= 1 exit)
              (str "outer fail must exit 1; got " exit
                   "\n--- stdout ---\n" out "\n--- stderr ---\n" err))
          ;; Both failures must be visible AND each must carry its own
          ;; correct banner.
          (is (str/includes? out "Testing probe.nested-red-inner-suite")
              (str "the RED inner run must flush its OWN ns banner; got:\n" out))
          ;; The inner FAIL renders the full nested var stack
          ;; `(outer-fails-after-red inner-fails)` because the inner var
          ;; runs inside the outer var's dynamic scope — `inner-fails` is
          ;; the innermost name, which is what `(first *testing-vars*)`
          ;; reads for the banner ns.
          (is (str/includes? out "inner-fails) (nested_red_inner_suite.clj")
              (str "the inner FAIL block must reach stdout; got:\n" out))
          ;; The inner failure must not mark the outer namespace's banner as
          ;; already printed.
          (is (str/includes? out "Testing probe.nested-red-outer-test")
              (str "the outer FAIL must carry its OWN ns banner, not be"
                   " orphaned; got:\n" out))
          (is (str/includes? out "FAIL in (outer-fails-after-red)")
              (str "the outer FAIL block must reach stdout; got:\n" out))
          ;; Order pin: the outer banner appears AFTER the inner FAIL
          ;; block, i.e. it is the OUTER failure's own heading, not the
          ;; inner one mislabelled.
          (is (< (.indexOf out "nested_red_inner_suite.clj")
                 (.indexOf out "Testing probe.nested-red-outer-test"))
              (str "the outer banner must follow the inner FAIL (it heads"
                   " the OUTER failure, not the inner); got:\n" out)))))))

;; ----------------------------------------------------------------------
;; Nested-run failure tally and exit-code soundness.
;;
;; The behaviour is currently SOUND (the quiet layer never overrides
;; :summary/:pass and counts :fail/:error exactly once); this pins it so a
;; future change — overriding :summary, sharing a counter, hoisting banner
;; handling into the counter path — can't silently turn an outer failure
;; into a false GREEN, or leak an inner-ignored failure into the outer
;; tally. cognitect computes the exit code from the outer summary's
;; fail+error, so asserting BOTH the exit code AND the summary text pins
;; the whole propagation path.

(deftest nested-green-outer-fail-is-counted
  (testing "an outer failure after a green nested run propagates to summary and exit 1"
    (with-fixture-dir
      (fn [dir]
        (write-raw-fixture! dir "tally_green_inner_suite"
          (str "(ns probe.tally-green-inner-suite\n"
               "  (:require [clojure.test :refer [deftest is]]\n"
               "            [re-frame.test-quiet]))\n"
               "(deftest inner-passes (is (= 1 1)))"))
        (write-raw-fixture! dir "tally_green_outer_test"
          (str "(ns probe.tally-green-outer-test\n"
               "  (:require [clojure.test :refer [deftest is run-tests]]\n"
               "            [re-frame.test-quiet]\n"
               "            [probe.tally-green-inner-suite]))\n"
               "(deftest outer-fails-after-green\n"
               "  (run-tests 'probe.tally-green-inner-suite)\n"
               "  (is (= :outer-exp :outer-act)))"))
        (let [{:keys [exit out err]}
              (invoke-quiet-runner dir "-n" "probe.tally-green-outer-test")]
          (is (= 1 exit)
              (str "the outer failure MUST drive exit 1 — a regression that"
                   " dropped it would be a false GREEN; got " exit
                   "\n--- stdout ---\n" out "\n--- stderr ---\n" err))
          (is (str/includes? out "1 failures, 0 errors.")
              (str "the OUTER summary must count exactly the outer failure"
                   " (the green nested run adds nothing); got:\n" out)))))))

(deftest nested-inner-fail-does-not-leak-to-outer
  (testing "an ignored inner failure does not leak into the outer tally or exit"
    (with-fixture-dir
      (fn [dir]
        ;; The outer deftest nests a RED run but makes NO failing
        ;; assertion of its own. clojure.test scopes *report-counters*
        ;; per test-ns, so the inner failure is confined to the nested
        ;; run's summary and must NOT appear in the outer run's tally.
        (write-raw-fixture! dir "scope_red_inner_suite"
          (str "(ns probe.scope-red-inner-suite\n"
               "  (:require [clojure.test :refer [deftest is]]\n"
               "            [re-frame.test-quiet]))\n"
               "(deftest inner-fails (is (= :inner-exp :inner-act)))"))
        (write-raw-fixture! dir "scope_red_outer_test"
          (str "(ns probe.scope-red-outer-test\n"
               "  (:require [clojure.test :refer [deftest is run-tests]]\n"
               "            [re-frame.test-quiet]\n"
               "            [probe.scope-red-inner-suite]))\n"
               "(deftest outer-ignores-inner-failure\n"
               "  (run-tests 'probe.scope-red-inner-suite)\n"
               "  (is (= 1 1)))"))
        (let [{:keys [exit out err]}
              (invoke-quiet-runner dir "-n" "probe.scope-red-outer-test")]
          (is (zero? exit)
              (str "the outer run is green — the inner failure the outer"
                   " ignores must NOT leak into the exit code; got " exit
                   "\n--- stdout ---\n" out "\n--- stderr ---\n" err))
          (is (str/includes? out "0 failures, 0 errors.")
              (str "the OUTER summary must stay clean — the scoped inner"
                   " failure must not leak into it; got:\n" out)))))))

;; ----------------------------------------------------------------------
;; test-ns-hook failure banner.
;;
;; The quiet reporter suppresses `:begin-test-ns` and derives the failure
;; banner ns from `*testing-vars*` (the failing var).  But a namespace's
;; `test-ns-hook` — a supported clojure.test path (`test-ns` calls it in
;; place of `test-all-vars`) — runs OUTSIDE `test-var`, so a bare failing
;; assertion inside it reports with an EMPTY `*testing-vars*`: the
;; var-derived ns is nil and the failure printed with NO banner, violating
;; the failure-banner contract. A begin/end namespace stack
;; falls back to the innermost open ns when no failing var is in scope.
;; This pin drives the REAL `-main` against a fixture whose `test-ns-hook`
;; fails, and asserts the ns banner is present above the FAIL block.

(deftest test-ns-hook-failure-gets-ns-banner
  (testing "a failing test-ns-hook with no test var still prints its namespace banner"
    (with-fixture-dir
      (fn [dir]
        ;; `test-ns-hook` TAKES PRECEDENCE over test-all-vars, so the bare
        ;; `(is ...)` inside it is what runs — with an empty *testing-vars*.
        ;; The `deftest` is present only so cognitect's `contains-tests?`
        ;; discovery filter includes this ns (it requires >=1 :test var); it
        ;; does NOT run, because the hook replaces test-all-vars.  The ns
        ;; name ends in `-test` so cognitect's default ns-filter selects it.
        (write-raw-fixture! dir "ns_hook_fail_test"
          (str "(ns probe.ns-hook-fail-test\n"
               "  (:require [clojure.test :refer [deftest is]]\n"
               "            [re-frame.test-quiet]))\n"
               ;; satisfies cognitect's contains-tests? (>=1 :test var);
               ;; never runs — test-ns-hook replaces test-all-vars.
               "(deftest a-discovery-anchor (is (= 1 1)))\n"
               "(defn test-ns-hook []\n"
               "  (is (= :hook-expected :hook-actual)))"))
        (let [{:keys [exit out err]} (invoke-quiet-runner dir)]
          (is (= 1 exit)
              (str "the failing test-ns-hook must exit 1; got " exit
                   "\n--- stdout ---\n" out "\n--- stderr ---\n" err))
          ;; With no failing var in scope, the namespace stack supplies the
          ;; banner.
          (is (str/includes? out "Testing probe.ns-hook-fail-test")
              (str "a failing test-ns-hook must still carry its ns banner —"
                   " the var-derived ns is nil, so the open-namespace stack fallback must"
                   " supply it; got:\n" out))
          (is (str/includes? out "FAIL in")
              (str "the FAIL block must reach stdout; got:\n" out))
          (is (and (str/includes? out "expected:")
                   (str/includes? out "actual:"))
              (str "expected:/actual: lines must reach stdout; got:\n" out))
          (is (str/includes? out "1 failures, 0 errors.")
              (str "the failing summary must reach stdout; got:\n" out)))))))

;; ----------------------------------------------------------------------
;; Diagnostics-survive contract.
;;
;; The line-precise filter drops only `Running tests in #{...}`. Help,
;; parse-error diagnostics, and test output must still reach stdout.

(deftest help-flag-prints-usage
  (testing "-H prints cognitect usage and exits 0 (was swallowed by the old *out* sink)"
    (with-fixture-dir
      (fn [dir]
        (let [{:keys [exit out err]} (invoke-quiet-runner dir "-H")]
          (is (zero? exit)
              (str "-H must exit 0; got " exit
                   "\n--- stdout ---\n" out "\n--- stderr ---\n" err))
          (is (str/includes? out "USAGE:")
              (str "the help USAGE header must reach stdout; got:\n" out))
          (is (str/includes? out "--test-help")
              (str "the help body (option listing) must reach stdout; got:\n" out))
          (is (not (str/includes? out discovery-banner-marker))
              (str "the help path never runs tests, so the discovery banner"
                   " must be absent; got:\n" out)))))))

(deftest invalid-flag-prints-parse-error
  (testing "an unknown flag prints the parse error + usage and exits 1 (was swallowed)"
    (with-fixture-dir
      (fn [dir]
        (let [{:keys [exit out err]}
              (invoke-quiet-runner dir "--definitely-not-a-runner-option")]
          (is (= 1 exit)
              (str "an unknown flag must exit 1; got " exit
                   "\n--- stdout ---\n" out "\n--- stderr ---\n" err))
          (is (str/includes? out "Unknown option")
              (str "the cli parse-error diagnostic must reach stdout; got:\n" out))
          (is (str/includes? out "--definitely-not-a-runner-option")
              (str "the offending flag must be named in the diagnostic; got:\n" out))
          (is (str/includes? out "USAGE:")
              (str "cognitect prints usage after the parse error; got:\n" out)))))))

(deftest test-stdout-survives-on-green
  (testing "bare test output reaches stdout while the banner stays quiet"
    (with-fixture-dir
      (fn [dir]
        (write-fixture! dir "stdout_green_fixture_test" "stdout-green-fixture-test"
                        (str "(deftest a-talking-test"
                             " (println \"BARE-STDOUT-MARKER\")"
                             " (is (= 1 1)))"))
        (let [{:keys [exit out err]} (invoke-quiet-runner dir)]
          (is (zero? exit)
              (str "the talking suite is green; must exit 0; got " exit
                   "\n--- stdout ---\n" out "\n--- stderr ---\n" err))
          (is (str/includes? out "BARE-STDOUT-MARKER")
              (str "a test's bare println must reach the real stdout — the"
                   " filter forwards everything but the banner; got:\n" out))
          (is (not (str/includes? out discovery-banner-marker))
              (str "the discovery banner must STILL be swallowed even though"
                   " other stdout is forwarded; got:\n" out))
          (is (str/includes? out "0 failures, 0 errors.")
              (str "the green summary must still print; got:\n" out)))))))

;; ----------------------------------------------------------------------
;; Banner-prefix precision.
;;
;; The filter must drop ONLY cognitect's own banner, which always renders
;; as `Running tests in #{...}` (the directory set is always a set: it
;; defaults to `#{"test"}` and `-d` accumulates via `(fnil conj #{})`).  A
;; bare prefix match on `Running tests in ` would also eat a legitimate
;; diagnostic that merely begins with those words — e.g. a fixture that
;; prints `Running tests in local fixture ...`.  This pins that such a
;; line survives while the real discovery banner is still swallowed.

(deftest banner-lookalike-diagnostic-survives
  (testing "a test line beginning 'Running tests in ' but not the banner survives"
    (with-fixture-dir
      (fn [dir]
        (write-fixture! dir "lookalike_fixture_test" "lookalike-fixture-test"
                        (str "(deftest a-lookalike-test"
                             " (println \"Running tests in local fixture LOOKALIKE-MARKER\")"
                             " (is (= 1 1)))"))
        (let [{:keys [exit out err]} (invoke-quiet-runner dir)]
          (is (zero? exit)
              (str "the lookalike suite is green; must exit 0; got " exit
                   "\n--- stdout ---\n" out "\n--- stderr ---\n" err))
          (is (str/includes? out "Running tests in local fixture LOOKALIKE-MARKER")
              (str "a diagnostic that merely starts 'Running tests in ' (but"
                   " is not the `#{...}` discovery banner) must survive; got:\n"
                   out))
          ;; Assert the real banner's distinctive `#{` shape is absent — NOT
          ;; the bare `discovery-banner-marker` prefix, which the lookalike
          ;; line legitimately contains.
          (is (not (str/includes? out "Running tests in #{"))
              (str "the real discovery banner (`Running tests in #{...}`)"
                   " must STILL be swallowed; got:\n" out)))))))

;; ----------------------------------------------------------------------
;; Banner-prefix overdrop guard.
;;
;; cognitect's banner is the whole line and stops at the set literal's
;; closing `}`. A user/fixture diagnostic may share that prefix and carry
;; trailing content, for example
;; `Running tests in #{:fixture :phase} MARKER`, was silently overdropped
;; The filter therefore treats a full-prefix match as a candidate and drops
;; it only when the remainder is a balanced set literal followed by
;; whitespace.

(deftest banner-prefix-overdrop-survives
  (testing "a line starting exactly 'Running tests in #{' with trailing content survives"
    (with-fixture-dir
      (fn [dir]
        (write-fixture! dir "overdrop_fixture_test" "overdrop-fixture-test"
                        (str "(deftest an-overdrop-test"
                             " (println \"Running tests in #{:fixture :phase} OVERDROP-MARKER\")"
                             " (is (= 1 1)))"))
        (let [{:keys [exit out err]} (invoke-quiet-runner dir)]
          (is (zero? exit)
              (str "the overdrop suite is green; must exit 0; got " exit
                   "\n--- stdout ---\n" out "\n--- stderr ---\n" err))
          ;; A fixture line with trailing content after the set literal is
          ;; not the banner and must be forwarded.
          (is (str/includes? out "Running tests in #{:fixture :phase} OVERDROP-MARKER")
              (str "a fixture line that starts exactly 'Running tests in #{'"
                   " but has trailing content after the set literal is NOT"
                   " the banner and must survive;"
                   " got:\n" out))
          (is (str/includes? out "0 failures, 0 errors.")
              (str "the green summary must still print; got:\n" out)))))))

(deftest real-banner-still-dropped-alongside-overdrop-lookalike
  (testing "the real `Running tests in #{...}` discovery banner is still dropped"
    (with-fixture-dir
      (fn [dir]
        ;; A clean green fixture: the ONLY `Running tests in #{...}` line on
        ;; stdout would be cognitect's own discovery banner. Proving it is
        ;; absent confirms the narrowed candidate/confirm logic did not stop
        ;; dropping the genuine banner while it gained the overdrop guard.
        (write-fixture! dir "banner_still_dropped_test" "banner-still-dropped-test"
                        "(deftest a-passing-test (is (= 1 1)))")
        (let [{:keys [exit out err]} (invoke-quiet-runner dir)]
          (is (zero? exit)
              (str "green suite must exit 0; got " exit
                   "\n--- stdout ---\n" out "\n--- stderr ---\n" err))
          (is (not (str/includes? out "Running tests in #{"))
              (str "the genuine `Running tests in #{...}` banner must STILL"
                   " be swallowed — the overdrop guard must not have made the"
                   " filter forward the real banner; got:\n" out))
          (is (not (str/includes? out discovery-banner-marker))
              (str "no part of the banner may reach stdout; got:\n" out)))))))

;; ----------------------------------------------------------------------
;; Exact-shape banner overdrop guard.
;;
;; A user/fixture line can exactly match `Running tests in #{:fixture}`.
;; cognitect prints its banner via
;; `(format "\nRunning tests in %s" dirs)`, so the genuine banner is ALWAYS
;; opened by a leading blank line; a bare `(println "Running tests in
;; #{:fixture}")` is not. Requiring that held leading blank distinguishes
;; the runner banner from an exact-shape user line.

(deftest banner-exact-shape-user-line-survives
  (testing "an exact-shape banner user line without a leading blank survives"
    (with-fixture-dir
      (fn [dir]
        ;; A bare `println` of the exact banner shape lacks the banner's
        ;; leading blank and must reach stdout.
        (write-fixture! dir "exact_shape_fixture_test" "exact-shape-fixture-test"
                        (str "(deftest an-exact-shape-test"
                             " (println \"Running tests in #{:fixture}\")"
                             " (is (= 1 1)))"))
        (let [{:keys [exit out err]} (invoke-quiet-runner dir)]
          (is (zero? exit)
              (str "the exact-shape suite is green; must exit 0; got " exit
                   "\n--- stdout ---\n" out "\n--- stderr ---\n" err))
          ;; Exact text without the held leading blank is not the banner.
          (is (str/includes? out "Running tests in #{:fixture}")
              (str "an exact-shape user line `Running tests in #{:fixture}`"
                   " printed via bare println (no leading blank) is NOT the"
                   " cognitect banner and must survive; got:\n"
                   out))
          (is (str/includes? out "0 failures, 0 errors.")
              (str "the green summary must still print; got:\n" out)))))))

(deftest real-banner-still-dropped-alongside-exact-shape-user-line
  (testing "the real banner is still dropped alongside an exact-shape user line"
    (with-fixture-dir
      (fn [dir]
        ;; The fixture prints an exact-shape line of its own; cognitect's
        ;; genuine banner (leading blank + `Running tests in #{...}`) is the
        ;; ONLY banner-shaped line preceded by a blank. The user line must
        ;; survive AND there must be exactly ONE `Running tests in #{` on
        ;; stdout — the user's — proving the genuine banner was still dropped.
        (write-fixture! dir "exact_shape_neg_test" "exact-shape-neg-test"
                        (str "(deftest a-neg-test"
                             " (println \"Running tests in #{:user-only}\")"
                             " (is (= 1 1)))"))
        (let [{:keys [exit out err]} (invoke-quiet-runner dir)
              hits (count (re-seq #"Running tests in #\{" out))]
          (is (zero? exit)
              (str "green suite must exit 0; got " exit
                   "\n--- stdout ---\n" out "\n--- stderr ---\n" err))
          (is (str/includes? out "Running tests in #{:user-only}")
              (str "the user's exact-shape line must survive; got:\n" out))
          (is (= 1 hits)
              (str "exactly one `Running tests in #{` must reach stdout (the"
                   " user's) — the genuine cognitect banner must STILL be"
                   " dropped; got " hits " occurrences:\n" out)))))))

;; ----------------------------------------------------------------------
;; Blank-led banner shape after the real banner.
;;
;; cognitect prints its banner once, before tests run. `banner-dropped?`
;; ensures a later user line with the same blank-led shape is forwarded
;; rather than treated as a second discovery banner.

(deftest blank-led-banner-shape-user-line-survives
  (testing "a test's blank-led banner-shaped output survives after the real banner"
    (with-fixture-dir
      (fn [dir]
        ;; The fixture prints a blank line IMMEDIATELY followed by an
        ;; exact-shape banner line carrying a keyword-set body (distinct from
        ;; cognitect's own `#{"<dir>"}` string-set banner). The latch forwards
        ;; this later line.
        (write-fixture! dir "blank_led_banner_fixture_test" "blank-led-banner-fixture-test"
                        (str "(deftest a-blank-then-banner-test"
                             " (println)"
                             " (println \"Running tests in #{:user-diagnostic}\")"
                             " (is (= 1 1)))"))
        (let [{:keys [exit out err]} (invoke-quiet-runner dir)]
          (is (zero? exit)
              (str "the fixture is green; must exit 0; got " exit
                   "\n--- stdout ---\n" out "\n--- stderr ---\n" err))
          ;; The test's own blank-led banner-shape
          ;; line is genuine stdout and must reach the real stdout.
          (is (str/includes? out "Running tests in #{:user-diagnostic}")
              (str "a test's blank-line-then-banner-shape stdout must survive"
                   " once cognitect's one banner has already been dropped"
                   "; got:\n" out))
          ;; cognitect's genuine banner renders the DIR set as a string set —
          ;; `#{\"<dir>\"}` — so `Running tests in #{\"` uniquely identifies
          ;; it. Its absence proves the real banner was STILL dropped (the
          ;; latch narrowed the drop to exactly one banner, it did not stop
          ;; dropping the real one).
          (is (not (str/includes? out "Running tests in #{\""))
              (str "cognitect's genuine discovery banner (`Running tests in"
                   " #{\"<dir>\"}`) must STILL be dropped — the latch narrows"
                   " the drop to the one real banner, it must not forward it;"
                   " got:\n" out))
          (is (str/includes? out "0 failures, 0 errors.")
              (str "the green summary must still print; got:\n" out)))))))

;; ----------------------------------------------------------------------
;; Unterminated partial survival across System/exit.
;;
;; cognitect exits straight from the computed fail/error counts, so the
;; wrapper's `finally` flush never runs on the test path.  A bare
;; `(print ...)` with no trailing newline AND no explicit flush must still
;; reach stdout: the filter forwards non-banner text eagerly (it only ever
;; holds back a live banner-prefix candidate) and `-main` registers a JVM
;; shutdown hook that flushes any held candidate.

(deftest unterminated-partial-survives-exit
  (testing "a bare print with no newline or flush reaches stdout before System/exit"
    (with-fixture-dir
      (fn [dir]
        ;; No trailing newline, no (flush) — the worst case the finding
        ;; describes. The marker must still survive to stdout.
        (write-fixture! dir "partial_fixture_test" "partial-fixture-test"
                        (str "(deftest a-partial-test"
                             " (print \"PARTIAL-EXIT-MARKER\")"
                             " (is (= 1 1)))"))
        (let [{:keys [exit out err]} (invoke-quiet-runner dir)]
          (is (zero? exit)
              (str "the partial-print suite is green; must exit 0; got " exit
                   "\n--- stdout ---\n" out "\n--- stderr ---\n" err))
          (is (str/includes? out "PARTIAL-EXIT-MARKER")
              (str "a bare unterminated (print ...) must reach stdout even"
                   " though cognitect System/exits before the finally flush;"
                   " got:\n" out))
          (is (str/includes? out "0 failures, 0 errors.")
              (str "the green summary must still print; got:\n" out)))))))

;; ----------------------------------------------------------------------
;; Central stderr buffer + red replay.
;;
;; The JVM runner buffers everything written to `*err*` (and `System/err`)
;; during the delegated run into a bounded ring, then:
;;   - GREEN: drops it silently — a passing run that emits expected
;;            warnings (e.g. a fail-closed contract-drift WARN) stays
;;            quiet, with NO ad-hoc per-namespace `*err*` sink needed;
;;   - RED:   replays it to the real stderr from the `:summary` reporter
;;            hook, before cognitect's `System/exit`, so the failing run
;;            keeps the diagnostic context.
;; This is the symmetric JVM counterpart to the CLJS node runner's
;; `console.warn` ring + `:end-run-tests` red replay
;; (`re-frame.test-quiet-shadow-node-cljs-test`).  These pins can only be
;; observed across a process boundary (the replay fires from `:summary`
;; just before the runner exits).

(deftest green-run-buffers-and-drops-expected-stderr
  (testing "a green run that writes expected stderr stays quiet"
    (with-fixture-dir
      (fn [dir]
        ;; A passing test emits an expected warning to *err*. The central
        ;; buffer must drop it on green.
        (write-fixture! dir "green_warn_fixture_test" "green-warn-fixture-test"
                        (str "(deftest a-warning-but-passing-test"
                             " (binding [*out* *err*]"
                             "   (println \"EXPECTED-WARN-MARKER-GREEN\"))"
                             " (is (= 1 1)))"))
        (let [{:keys [exit out err]} (invoke-quiet-runner dir)]
          (is (zero? exit)
              (str "the warning-but-passing suite is green; must exit 0; got "
                   exit "\n--- stdout ---\n" out "\n--- stderr ---\n" err))
          ;; The CORE green pin: the expected warning is buffered + dropped,
          ;; so it appears on NEITHER stdout NOR stderr.
          (is (not (str/includes? (str out err) "EXPECTED-WARN-MARKER-GREEN"))
              (str "an expected stderr warning on a GREEN run must be buffered"
                   " and dropped - not leaked to stdout or stderr;"
                   " got\n--- stdout ---\n" out "\n--- stderr ---\n" err))
          (is (str/includes? out "0 failures, 0 errors.")
              (str "the green summary must still print; got:\n" out)))))))

(deftest red-run-replays-buffered-stderr
  (testing "a red run replays buffered stderr context to real stderr"
    (with-fixture-dir
      (fn [dir]
        ;; A test that emits a diagnostic to *err* and then FAILS. The
        ;; warning is buffered as the run proceeds, then replayed on the
        ;; red exit so the failing run keeps the context.
        (write-fixture! dir "red_warn_fixture_test" "red-warn-fixture-test"
                        (str "(deftest a-warning-and-failing-test"
                             " (binding [*out* *err*]"
                             "   (println \"EXPECTED-WARN-MARKER-RED diagnostic context\"))"
                             " (is (= :exp :act)))"))
        (let [{:keys [exit out err]} (invoke-quiet-runner dir)]
          (is (= 1 exit)
              (str "the warning-and-failing suite is red; must exit 1; got "
                   exit "\n--- stdout ---\n" out "\n--- stderr ---\n" err))
          ;; The failure diagnostics still reach stdout (routed via
          ;; *test-out*, never *err*, so the buffer never hides them).
          (is (str/includes? out "FAIL in (a-warning-and-failing-test)")
              (str "the FAIL block must still reach stdout; got:\n" out))
          ;; The CORE red pin: the buffered stderr is replayed (to the real
          ;; stderr) so the failing run keeps the diagnostic context.
          (is (str/includes? err "EXPECTED-WARN-MARKER-RED")
              (str "the buffered stderr must be REPLAYED on a RED run"
                   "; got\n--- stdout ---\n" out
                   "\n--- stderr ---\n" err))
          (is (str/includes? err "buffered stderr replayed because the run was RED")
              (str "the replay must be labelled so it is distinguishable from"
                   " the reporter's own output; got stderr:\n" err)))))))

;; ----------------------------------------------------------------------
;; Subprocess-harness pipe-deadlock guard.
;;
;; The harness drains a child's stdout and stderr on separate threads
;; (see `drain-process` / `invoke-quiet-runner`). A sequential stdout-then-stderr
;; drain deadlocks when a child fills the stderr pipe
;; buffer (~64 KB on most OSes) blocks on its stderr write before
;; closing stdout, so the parent — still blocked on stdout EOF — never
;; reaches the stderr drain or `waitFor`, and both hang forever.
;;
;; This child writes a stderr payload far larger than any pipe buffer
;; (~1 MB) AND a stdout marker, then exits NONZERO.  With the concurrent
;; drain the harness must return the full captured stderr, the stdout
;; marker, and the real exit code — promptly.  We run it on a bounded
;; future so a regression that reintroduces the sequential drain fails
;; as a TIMEOUT (deref deadline) instead of hanging the whole suite.

(def ^:private big-stderr-bytes
  "~1 MB — comfortably past any plausible OS pipe buffer (~64 KB)."
  (* 1024 1024))

(deftest harness-does-not-deadlock-on-large-stderr
  (testing "a child flooding stderr while exiting nonzero is drained without deadlock"
    (with-fixture-dir
      (fn [dir]
        ;; The child program is written to a FILE rather than passed via
        ;; `clojure.main -e`: on Windows, `ProcessBuilder` re-quoting
        ;; strips the embedded double-quotes from an inline `-e` form, so
        ;; `"STDOUT-MARKER"` would arrive as a bare symbol and the child
        ;; would die compiling. A `.clj` file on disk has no such
        ;; cross-platform arg-quoting hazard.  It writes a stdout marker,
        ;; floods stderr with `big-stderr-bytes` of payload, then
        ;; `System/exit`s NONZERO.
        (let [flood-file (io/file dir "deadlock_flood.clj")]
          (spit flood-file
                (str "(.print (System/out) \"STDOUT-MARKER\")\n"
                     "(.flush (System/out))\n"
                     "(let [chunk (apply str (repeat 1024 \"E\"))]\n"
                     "  (dotimes [_ " (quot big-stderr-bytes 1024) "]\n"
                     "    (.print (System/err) chunk)))\n"
                     "(.flush (System/err))\n"
                     "(System/exit 3)\n"))
          (let [windows?        (str/includes?
                                  (str/lower-case
                                    (System/getProperty "os.name"))
                                  "win")
                java-executable (str (io/file (System/getProperty "java.home")
                                              "bin"
                                              (if windows? "java.exe" "java")))
                command         [java-executable
                                 "-cp" (System/getProperty "java.class.path")
                                 "clojure.main" (.getAbsolutePath flood-file)]
                result-future   (future
                           (-> (ProcessBuilder. ^java.util.List command)
                               (.redirectErrorStream false)
                               (.start)
                               (drain-process)))
                ;; A correctly draining harness returns in well under a
                ;; second; 60s is a generous ceiling that still FAILS (not
                ;; hangs) if the sequential-drain deadlock is reintroduced.
                process-result  (deref result-future 60000 ::timed-out)]
            (is (not= ::timed-out process-result)
                (str "the harness deadlocked: a >pipe-buffer stderr flood with a"
                     " sequential (stdout-then-stderr) drain hangs forever"
                     " - it must drain both streams concurrently"))
            (when (not= ::timed-out process-result)
              (let [{:keys [exit out err timed-out?]} process-result]
                (is (false? timed-out?)
                    "the flood child exits, so the helper must NOT time out")
                (is (= 3 exit)
                    (str "the child's nonzero exit code must be captured; got "
                         exit "\n--- stderr head ---\n"
                         (subs err 0 (min 300 (count err)))))
                (is (str/includes? out "STDOUT-MARKER")
                    (str "the stdout marker must be captured alongside the"
                         " stderr flood; got stdout:\n" out))
                (is (>= (count err) big-stderr-bytes)
                    (str "the full stderr flood must be captured, not truncated"
                         " by a partial drain; got " (count err)
                         " bytes, expected >= " big-stderr-bytes))))))))))

;; ----------------------------------------------------------------------
;; Shared drain timeout/kill policy.
;;
;; `drain-process` carries the timeout/kill policy so EVERY subprocess
;; path (the real `invoke-quiet-runner`, the deadlock regression, future helpers)
;; fails fast on a wedged child instead of hanging the suite.  This pins
;; that fail-fast behaviour directly: a child that NEVER exits must be
;; force-killed at the ceiling and the helper must return `:timed-out?
;; true` promptly — so a future change that drops the `.waitFor` timeout
;; cannot silently reintroduce an unbounded hang.

(deftest drain-process-kills-and-flags-a-hanging-child
  (testing "a child that never exits is force-killed and flagged timed out"
    (with-fixture-dir
      (fn [dir]
        ;; A child that blocks forever with no output and no exit path —
        ;; the worst case the finding describes.  Written to a file for
        ;; the same cross-platform arg-quoting reason as the flood child.
        (let [hang-file (io/file dir "hang_forever.clj")]
          (spit hang-file "@(promise)\n") ; deref an unfulfilled promise → blocks forever
          (let [windows?        (str/includes?
                                  (str/lower-case
                                    (System/getProperty "os.name"))
                                  "win")
                java-executable (str (io/file (System/getProperty "java.home")
                                              "bin"
                                              (if windows? "java.exe" "java")))
                command         [java-executable
                                 "-cp" (System/getProperty "java.class.path")
                                 "clojure.main" (.getAbsolutePath hang-file)]
                process         (-> (ProcessBuilder. ^java.util.List command)
                             (.redirectErrorStream false)
                             (.start))
                ;; A SHORT explicit ceiling keeps the test fast; the outer
                ;; deref is the belt-and-suspenders so even a regressed
                ;; helper fails as a TIMEOUT, never an infinite hang.
                result-future   (future (drain-process process 1500))
                process-result  (deref result-future 30000 ::outer-timeout)]
            (is (not= ::outer-timeout process-result)
                (str "the helper itself hung: `drain-process` must enforce its"
                     " own `.waitFor` timeout and return promptly"))
            (when (not= ::outer-timeout process-result)
              (is (true? (:timed-out? process-result))
                  (str "a never-exiting child must be flagged `:timed-out? true`;"
                       " got " (pr-str (dissoc process-result :out :err))))
              (is (= -1 (:exit process-result))
                  "a timed-out drain reports exit -1 (no real exit code)")
              (is (not (.isAlive process))
                  "the child must be force-killed on the timeout, not left alive"))
            ;; Cleanup belt: ensure the child is gone regardless.
            (.destroyForcibly process)))))))

;; ----------------------------------------------------------------------
;; Stderr ring is bounded: front trimming keeps the newest characters.
;;
;; `stderr-buffer-cap` is 256 KB and `buffering-stderr-writer` front-trims
;; `(.delete stderr-ring 0 (- ring-length stderr-buffer-capacity))` so the
;; ring retains the NEWEST `stderr-buffer-capacity`
;; characters and drops older ones (runner.clj `stderr-buffer-cap` +
;; `buffering-stderr-writer`).  The CLJS analogue is rigorously pinned
;; (`warn-buffer-is-bounded-and-materialised` drives 4x capacity); the JVM ring
;; had NO equivalent — the green/red stderr pins write tiny payloads, so a
;; broken trim (wrong end, off-by-one, dropped) was uncaught and could OOM
;; a chatty red suite.  This drives a RED run that floods `*err*` with
;; ~600 KB (well past the 256 KB cap), then asserts the newest tail marker
;; is REPLAYED, the oldest head marker is DROPPED, and the replayed volume
;; is bounded to ~capacity — proving the ring capped rather than merely that a
;; marker happened to be absent.

(deftest stderr-ring-front-trims-to-newest-cap-on-red
  (testing "an *err* flood past the 256K-character cap keeps the newest characters"
    (with-fixture-dir
      (fn [dir]
        ;; ~600 KB of filler (2000 lines x ~300 chars) brackets a HEAD
        ;; marker (written FIRST → oldest → must be front-trimmed away) and
        ;; a TAIL marker (written LAST → newest → must survive), all to
        ;; *err* on a run that then FAILS so the ring is replayed.
        (write-fixture! dir "ringcap_fixture_test" "ringcap-fixture-test"
                        (str "(deftest a-huge-warning-and-failing-test"
                             " (binding [*out* *err*]"
                             "   (println \"HEAD-MARKER-OLDEST-MUST-BE-DROPPED\")"
                             "   (dotimes [_ 2000] (println (apply str (repeat 300 \"F\"))))"
                             "   (println \"TAIL-MARKER-NEWEST-MUST-SURVIVE\"))"
                             " (is (= :exp :act)))"))
        (let [{:keys [exit out err]} (invoke-quiet-runner dir)
              stderr-buffer-capacity (* 256 1024)]
          (is (= 1 exit)
              (str "the flooding-and-failing suite is red; must exit 1; got "
                   exit "\n--- stdout ---\n" out
                   "\n--- stderr head ---\n" (subs err 0 (min 300 (count err)))))
          (is (str/includes? err "buffered stderr replayed because the run was RED")
              (str "the red replay label must be present; got stderr head:\n"
                   (subs err 0 (min 300 (count err)))))
          ;; NEWEST retained: the tail marker written last survives the ring.
          (is (str/includes? err "TAIL-MARKER-NEWEST-MUST-SURVIVE")
              (str "the NEWEST bytes must be retained by the front-trim ring"
                   "; tail marker missing. stderr length="
                   (count err)))
          ;; OLDEST dropped: the head marker written first is trimmed away.
          (is (not (str/includes? err "HEAD-MARKER-OLDEST-MUST-BE-DROPPED"))
              (str "the OLDEST bytes must be front-trimmed once past the "
                   stderr-buffer-capacity "-char cap; the head marker leaked, so"
                   " the ring either did not trim or trimmed the WRONG end."
                   " stderr length=" (count err)))
          ;; BOUNDED: the replay is ~capacity + the fixed label, NOT the full
          ;; ~600 KB stream — proving the ring actually capped. Without the
          ;; front-trim, (count err) would be ~600 KB.
          (is (< (count err) (+ stderr-buffer-capacity 4096))
              (str "the replayed stderr must be bounded to ~"
                   stderr-buffer-capacity " chars +"
                   " the replay label; an unbounded ring would"
                   " replay the whole ~600 KB flood. got " (count err)
                   " chars")))))))

;; ----------------------------------------------------------------------
;; Raw `System.err` bridge is buffered too.
;;
;; `-main` swaps `System/setErr` for a `PrintStream` over an
;; `OutputStream` proxy that routes raw `System.err` bytes into the SAME
;; ring as `*err*`. These pins write via `(.println System/err ...)` and
;; assert the raw payload is dropped on green and replayed on red.

(deftest raw-system-err-buffered-and-dropped-on-green
  (testing "a green run drops raw System.err through the bridge"
    (with-fixture-dir
      (fn [dir]
        (write-fixture! dir "raw_syserr_green_fixture_test" "raw-syserr-green-fixture-test"
                        (str "(deftest a-raw-syserr-but-passing-test"
                             " (.println System/err \"RAW-SYSERR-MARKER-GREEN\")"
                             " (is (= 1 1)))"))
        (let [{:keys [exit out err]} (invoke-quiet-runner dir)]
          (is (zero? exit)
              (str "the raw-System.err-but-passing suite is green; must exit 0; got "
                   exit "\n--- stdout ---\n" out "\n--- stderr ---\n" err))
          ;; The CORE green pin: a RAW System.err write is routed through the
          ;; setErr bridge into the ring, so on green it is dropped — absent
          ;; from BOTH streams (proving the bridge, not just the *err* path).
          (is (not (str/includes? (str out err) "RAW-SYSERR-MARKER-GREEN"))
              (str "a raw System.err write on a GREEN run must be buffered by"
                   " the setErr bridge and dropped - not leaked;"
                   " got\n--- stdout ---\n" out "\n--- stderr ---\n" err))
          (is (str/includes? out "0 failures, 0 errors.")
              (str "the green summary must still print; got:\n" out)))))))

(deftest raw-system-err-replayed-on-red
  (testing "a red run replays raw System.err through the bridge"
    (with-fixture-dir
      (fn [dir]
        (write-fixture! dir "raw_syserr_red_fixture_test" "raw-syserr-red-fixture-test"
                        (str "(deftest a-raw-syserr-and-failing-test"
                             " (.println System/err \"RAW-SYSERR-MARKER-RED diagnostic context\")"
                             " (is (= :exp :act)))"))
        (let [{:keys [exit out err]} (invoke-quiet-runner dir)]
          (is (= 1 exit)
              (str "the raw-System.err-and-failing suite is red; must exit 1; got "
                   exit "\n--- stdout ---\n" out "\n--- stderr ---\n" err))
          (is (str/includes? out "FAIL in (a-raw-syserr-and-failing-test)")
              (str "the FAIL block must still reach stdout; got:\n" out))
          ;; The CORE red pin: the RAW System.err payload, routed through the
          ;; bridge into the ring, is REPLAYED to real stderr on red.
          (is (str/includes? err "RAW-SYSERR-MARKER-RED")
              (str "a raw System.err write must be buffered by the setErr"
                   " bridge and replayed on a red run; got"
                   "\n--- stdout ---\n" out "\n--- stderr ---\n" err))
          (is (str/includes? err "buffered stderr replayed because the run was RED")
              (str "the replay must be labelled; got stderr:\n" err)))))))

;; ----------------------------------------------------------------------
;; Concurrent dual-channel red run — the process exits for the INTENTIONAL
;; assertion, not an internal buffer exception.
;;
;; `-main` routes the test thread's `*err*` (a PrintWriter) and raw
;; process-global `System.err` (a `System/setErr` PrintStream bridge) into
;; ONE StringBuilder ring. The two wrappers hold DISTINCT locks, so before
;; the ring's writes were serialized on a shared monitor, overlapping
;; writes from the two channels could tear the StringBuilder and throw
;; `ArrayIndexOutOfBoundsException` mid-run — a reporter bug that changes a
;; failing run's exit/diagnostics. This drives the REAL `-main` against a
;; fixture that writes through BOTH channels concurrently past the ring cap,
;; then fails an assertion, and proves: the process exits 1 for the
;; assertion (not an internal buffer exception), the FAIL block survives,
;; the red replay is well formed, and no `ArrayIndexOutOfBoundsException`
;; leaks from the ring. The in-process trial harness
;; (`re-frame.test-quiet-stderr-ring-concurrency-test`) reliably trips the
;; underlying race; this is the end-to-end counterpart through the real
;; runner + `:summary` replay hook.

(deftest concurrent-dual-channel-red-exits-for-assertion-not-buffer-exception
  (testing "joined concurrent *err* + raw System.err writes on a red run: exit 1, no buffer exception, replay well formed"
    (with-fixture-dir
      (fn [dir]
        ;; The background writer is a raw Thread (NOT a future) so it writes
        ;; through the process-global System.err bridge, independent of the
        ;; test thread's *err* binding; the test thread writes through *err*.
        ;; 6 x 50 000 chars per channel (~300 KB) drives the front-trim past
        ;; the 256 KiB cap while both channels are live — the exact overlap
        ;; the race needs. Both are released together on a promise and JOINED
        ;; before the failing assertion, so the replay content is settled.
        (write-fixture! dir "concurrent_dual_channel_red_test"
                        "concurrent-dual-channel-red-test"
                        (str "(deftest a-concurrent-dual-channel-failing-test"
                             " (let [gate (promise)"
                             "       big (apply str (repeat 50000 \\Z))"
                             "       t (Thread. (fn [] @gate"
                             "                    (dotimes [_ 6] (.println System/err big))"
                             "                    (.println System/err \"SYS-TAIL-CONCURRENT-MARKER\")))]"
                             "   (.start t)"
                             "   (deliver gate :go)"
                             "   (binding [*out* *err*]"
                             "     (dotimes [_ 6] (println big))"
                             "     (println \"ERR-TAIL-CONCURRENT-MARKER\"))"
                             "   (.join t)"
                             "   (is (= :expected-marker :actual-marker))))"))
        (let [{:keys [exit out err timed-out?]} (invoke-quiet-runner dir)
              both (str out err)]
          (is (not timed-out?)
              "the concurrent dual-channel run must terminate, not hang")
          ;; CORE pin: the process exits 1 for the intentional ASSERTION —
          ;; not a torn-buffer exception (which could change the exit path).
          (is (= 1 exit)
              (str "the concurrent dual-channel suite is red; must exit 1 for"
                   " the intentional assertion; got " exit
                   "\n--- stdout ---\n" out "\n--- stderr ---\n" err))
          (is (str/includes? out "FAIL in (a-concurrent-dual-channel-failing-test)")
              (str "the FAIL block for the intentional assertion must reach"
                   " stdout — proving the process failed for the assertion,"
                   " not an internal buffer exception; got:\n" out))
          ;; No torn-StringBuilder exception may surface anywhere: the whole
          ;; point of the fix is that the ring never throws from concurrent
          ;; writes.
          (is (not (str/includes? both "ArrayIndexOutOfBoundsException"))
              (str "a concurrent dual-channel red run must NOT throw from the"
                   " stderr ring; got\n--- stdout ---\n" out
                   "\n--- stderr ---\n" err))
          ;; The red replay is well formed: labelled, and it carries the
          ;; newest buffered context. The two channels' floods total ~600 KB
          ;; past the 256 KiB cap, so which channel's tail marker survives the
          ;; front-trim depends on the interleaving; the LAST write overall is
          ;; always one of the two tail markers, so at least one must survive.
          (is (str/includes? err "buffered stderr replayed because the run was RED")
              (str "the red replay must be labelled; got stderr:\n"
                   (subs err 0 (min 400 (count err)))))
          (is (or (str/includes? err "ERR-TAIL-CONCURRENT-MARKER")
                  (str/includes? err "SYS-TAIL-CONCURRENT-MARKER"))
              (str "the replay must retain the NEWEST buffered context — at"
                   " least one channel's tail marker must survive the ring;"
                   " got stderr tail:\n"
                   (subs err (max 0 (- (count err) 400)) (count err)))))))))

;; ----------------------------------------------------------------------
;; What a lane claims, and what it must therefore prove (rf2-qqzmf).
;;
;; `clojure.test/run-tests` over an empty namespace set reports
;; `Ran 0 tests containing 0 assertions. / 0 failures, 0 errors.` and
;; cognitect exits 0 from that tally, so a discovery set that silently
;; collapsed to nothing was indistinguishable from a green suite.
;;
;; The rule is not "every lane must run tests" — it is that a lane which
;; claims COVERAGE must prove it ran, while a lane which claims only
;; RESOLUTION (`--probe`) must prove it resolved without running. Both halves
;; are pinned, and both are pinned in BOTH directions, because a check that
;; cannot go red is the same defect wearing a different hat:
;;
;;   suite: a zero-test discovery set exits 1; an ordinary suite exits 0;
;;          a floor ABOVE the real count reds a genuinely green suite;
;;          a malformed floor exits 2, never a silent default.
;;   probe: a zero-test run exits 0 (the false RED this rule must not
;;          create); a probe that GAINED tests exits 1 and says to drop the
;;          flag.

(deftest zero-test-discovery-is-red
  (testing "a run whose selector matches no namespace exits 1, not 0"
    (with-fixture-dir
      (fn [dir]
        (write-fixture! dir "floor_zero_fixture_test" "floor-zero-fixture-test"
                        "(deftest a-passing-test (is (= 1 1)))")
        ;; `-r` is cognitect's namespace-regex selector; this one matches
        ;; nothing on the fixture classpath, so `run-tests` runs 0 tests.
        (let [{:keys [exit out err]}
              (invoke-quiet-runner dir "-r" "^zzz-matches-nothing$")]
          (is (= 1 exit)
              (str "a 0-test run must exit 1 — a discovery set that collapsed"
                   " to nothing is a configuration error, not a green suite;"
                   " got " exit "\n--- stdout ---\n" out
                   "\n--- stderr ---\n" err))
          (is (str/includes? out "Ran 0 tests")
              (str "the real tally must still print above the diagnostic;"
                   " got:\n" out))
          (is (str/includes? err "below the floor of 1")
              (str "the failure must name the floor it violated; got"
                   " stderr:\n" err)))))))

(deftest ordinary-suite-clears-the-floor
  (testing "an ordinary green suite is unaffected by the floor"
    (with-fixture-dir
      (fn [dir]
        (write-fixture! dir "floor_ordinary_fixture_test" "floor-ordinary-fixture-test"
                        "(deftest a-passing-test (is (= 1 1)))")
        (let [{:keys [exit out err]} (invoke-quiet-runner dir)]
          (is (zero? exit)
              (str "a suite that ran tests must still exit 0; got " exit
                   "\n--- stdout ---\n" out "\n--- stderr ---\n" err))
          (is (not (str/includes? (str out err) "below the floor"))
              (str "no floor diagnostic may appear on a clearing run; got"
                   "\n--- stdout ---\n" out "\n--- stderr ---\n" err)))))))

(deftest floor-above-the-real-count-is-red
  (testing "RF2_MIN_TESTS above the executed count reds an otherwise-green suite"
    (with-fixture-dir
      (fn [dir]
        (write-fixture! dir "floor_raised_fixture_test" "floor-raised-fixture-test"
                        "(deftest a-passing-test (is (= 1 1)))")
        ;; The fixture runs exactly ONE test; a floor of 2 must fire. This is
        ;; the "prove it can red at N-1" half: without it, a floor that never
        ;; fires would pass the zero-test pin by accident of some other exit.
        (let [{:keys [exit out err]}
              (invoke-quiet-runner-with-env dir {"RF2_MIN_TESTS" "2"} [])]
          (is (= 1 exit)
              (str "a suite below its configured floor must exit 1 even with a"
                   " clean tally; got " exit "\n--- stdout ---\n" out
                   "\n--- stderr ---\n" err))
          (is (str/includes? out "0 failures, 0 errors.")
              (str "the tally really was clean — the floor is what failed the"
                   " run; got:\n" out))
          (is (str/includes? err "executed 1 test(s), below the floor of 2")
              (str "the diagnostic must name both counts; got stderr:\n" err)))))))

(deftest probe-lane-with-zero-tests-is-green
  (testing "a --probe lane exits 0 on zero tests — the floor must not false-red it"
    (with-fixture-dir
      (fn [dir]
        ;; The two live probe lanes are implementation/adapters/reagent and
        ;; implementation/adapters/uix: CLJS-only artefacts whose `:test`
        ;; alias exists to prove deps + classpath resolve. `test.yml` documents
        ;; the contract ("the cognitect test-runner returns 0 when there are
        ;; no test namespaces") and names the jobs "Diagnostic skip-ok". This
        ;; fixture dir has NO JVM test file at all — the same shape.
        (let [{:keys [exit out err]} (invoke-quiet-runner dir "--probe")]
          (is (zero? exit)
              (str "a declared classpath probe must exit 0 on zero tests: a"
                   " lane that claims resolution, not coverage, has nothing"
                   " to run; got " exit "\n--- stdout ---\n" out
                   "\n--- stderr ---\n" err))
          (is (str/includes? out "Ran 0 tests")
              (str "the probe must still have REACHED its summary — that is"
                   " what proves deps resolved and the dirs were scanned;"
                   " got:\n" out))
          (is (not (str/includes? (str out err) "below the floor"))
              (str "no coverage-floor diagnostic may appear on a probe lane;"
                   " got\n--- stdout ---\n" out "\n--- stderr ---\n" err))
          ;; Silent-on-success stands: a green probe announces nothing extra.
          (let [non-blank (->> (str/split-lines out) (remove str/blank?))]
            (is (= 2 (count non-blank))
                (str "a green probe must emit exactly the canonical two"
                     " summary lines and no announcement of its own; got:\n"
                     (str/join "\n" non-blank)))))))))

(deftest probe-lane-that-gained-tests-is-red
  (testing "a --probe lane with tests is red: it now claims coverage"
    (with-fixture-dir
      (fn [dir]
        ;; The exemption is a declaration, not a hole. The moment the lane has
        ;; a JVM test it is claiming coverage, so it must drop `--probe` and
        ;; take the floor — otherwise a real suite could sit behind a probe
        ;; declaration and lose the floor silently.
        (write-fixture! dir "probe_gained_fixture_test" "probe-gained-fixture-test"
                        "(deftest a-passing-test (is (= 1 1)))")
        (let [{:keys [exit out err]} (invoke-quiet-runner dir "--probe")]
          (is (= 1 exit)
              (str "a probe lane that executed tests must exit 1; got " exit
                   "\n--- stdout ---\n" out "\n--- stderr ---\n" err))
          (is (str/includes? out "0 failures, 0 errors.")
              (str "the tally really was clean — the claim mismatch is what"
                   " failed the run; got:\n" out))
          (is (str/includes? err "declared a classpath probe")
              (str "the diagnostic must name the mismatch; got stderr:\n" err))
          (is (str/includes? err "drop --probe")
              (str "the diagnostic must say what to do about it; got"
                   " stderr:\n" err)))))))

(deftest probe-flag-is-not-forwarded-to-cognitect
  (testing "--probe is consumed by the wrapper, not passed to the test runner"
    (with-fixture-dir
      (fn [dir]
        ;; cognitect owns its own arg contract and rejects what it does not
        ;; know, so the wrapper's one flag must be stripped before delegating.
        ;; A leaked `--probe` would surface as a cognitect parse error.
        (write-fixture! dir "probe_strip_fixture_test" "probe-strip-fixture-test"
                        "(deftest a-passing-test (is (= 1 1)))")
        (let [{:keys [exit out err]}
              (invoke-quiet-runner dir "-r" "^zzz-matches-nothing$" "--probe")
              both (str out err)]
          (is (zero? exit)
              (str "a probe whose selector matches nothing is still a green"
                   " probe (0 tests is its correct outcome); got " exit
                   "\n--- stdout ---\n" out "\n--- stderr ---\n" err))
          (is (not (str/includes? both "--probe"))
              (str "no diagnostic may echo --probe: a cognitect parse error"
                   " naming it means the flag leaked through; got\n" both)))))))

(deftest malformed-floor-is-a-configuration-error
  (testing "a non-integer RF2_MIN_TESTS exits 2 rather than silently defaulting"
    (with-fixture-dir
      (fn [dir]
        (write-fixture! dir "floor_malformed_fixture_test" "floor-malformed-fixture-test"
                        "(deftest a-passing-test (is (= 1 1)))")
        ;; `1O` (letter O) silently falling back to the default would disable
        ;; the very gate that catches silent non-execution.
        (let [{:keys [exit out err]}
              (invoke-quiet-runner-with-env dir {"RF2_MIN_TESTS" "1O"} [])]
          (is (= 2 exit)
              (str "a malformed floor must exit 2 (configuration error),"
                   " distinct from 1 (red); got " exit
                   "\n--- stdout ---\n" out "\n--- stderr ---\n" err))
          (is (str/includes? (str out err) "is not a non-negative integer")
              (str "the diagnostic must name the malformed value; got"
                   "\n--- stdout ---\n" out "\n--- stderr ---\n" err))
          (is (not (str/includes? out "Ran "))
              (str "the run must be rejected BEFORE any test executes; got:\n"
                   out)))))))

;; ----------------------------------------------------------------------
;; What the lane will DISCOVER (rf2-vruo9).
;;
;; The floor above is a COLLAPSE detector and cannot see a lane that lost
;; ONE file: `cognitect.test-runner` discovers namespaces by READING each
;; file's `(ns ...)` form, and drops the files it cannot read. Measured on
;; this repo, a single unescaped quote in an ns docstring took
;; `implementation/core` from 2190 tests to 2182 — the broken file's eight
;; deftests — printing `0 failures, 0 errors.` and exiting 0.
;;
;; The rule itself is pinned against the discovery library in
;; `re-frame.test-quiet-discovery-integrity-test`. What is pinned HERE is
;; the only part that needs a real process: that `-main` applies it, refuses
;; before a test runs, and says which file — and that the SAME fixture is
;; green the moment before the broken file arrives, which is what makes the
;; red attributable to the file rather than to the guard.

(deftest an-undiscoverable-file-refuses-the-run
  (testing "a file whose `(ns ...)` form the reader cannot read reds the
            lane instead of vanishing from it"
    (with-fixture-dir
      (fn [dir]
        (write-fixture! dir "discovery_fixture_test" "discovery-fixture-test"
                        "(deftest a-passing-test (is (= 1 1)))")
        (let [{:keys [exit out err]} (invoke-quiet-runner dir)]
          (is (zero? exit)
              (str "BEFORE: the fixture alone is green, so the red below"
                   " belongs to the broken file and not to the guard; got "
                   exit "\n--- stdout ---\n" out "\n--- stderr ---\n" err)))

        ;; One unescaped `"` inside the ns docstring. The reader consumes
        ;; the rest of the file as a string and hits EOF, so the form never
        ;; reads and the namespace never enters the discovered set.
        (write-raw-fixture!
          dir "unreadable_test"
          (str "(ns probe.unreadable-test\n"
               "  \"A docstring with a stray \" quote in it.\"\n"
               "  (:require [clojure.test :refer [deftest is]]))\n"
               "(deftest silently-dropped (is (= 1 1)))"))

        (let [{:keys [exit out err]} (invoke-quiet-runner dir)]
          (is (= 1 exit)
              (str "AFTER: an undiscoverable file must red the run; exit 0"
                   " here is the bug — the suite would report `0 failures`"
                   " over a file it never loaded. Got " exit
                   "\n--- stdout ---\n" out "\n--- stderr ---\n" err))
          (is (str/includes? err "unreadable_test.clj")
              (str "the diagnostic must NAME the file, or the operator is"
                   " left bisecting; got stderr:\n" err))
          (is (str/includes? err "will not reach the runner")
              (str "and must say what is wrong with it; got stderr:\n" err))
          (is (not (str/includes? out "Ran "))
              (str "the run must be refused BEFORE any test executes,"
                   " because by the time a tally exists the missing file is"
                   " already invisible to it; got:\n" out)))))))
