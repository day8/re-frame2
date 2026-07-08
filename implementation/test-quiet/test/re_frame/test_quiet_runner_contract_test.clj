(ns re-frame.test-quiet-runner-contract-test
  "Subprocess contract pin for the JVM entry point
  `re-frame.test-quiet.runner/-main` (rf2-vespg).

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
     discovery banner is STILL absent (swallowed unconditionally, not
     replayed on red — the documented capture-and-replay was
     unreachable and the docs now match this).
   - ERROR: exit 1; the `ERROR in` block + the thrown message reach
     stdout.
   - STDERR BUFFER (rf2-nrk066): a GREEN run that emits expected
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
  hanging the whole JVM suite (rf2-8dfq5j.3)."
  60000)

(defn- drain-process
  "Drain `proc`'s stdout AND stderr concurrently, then `waitFor` UNDER A
  TIMEOUT.  Returns {:exit :out :err :timed-out?}.  The concurrent drain
  is what `run-runner` relies on to avoid the pipe deadlock (rf2-spzkgo);
  this helper is the shared primitive every subprocess path uses, so the
  deadlock regression proves the EXACT drain order the real harness
  ships.

  The timeout/kill policy lives HERE (rf2-8dfq5j.3) so EVERY subprocess
  test inherits fail-fast behaviour: on expiry the child is force-killed
  (`destroyForcibly`), the drain threads are interrupted, and the result
  carries `:timed-out? true` plus whatever stdout/stderr drained before
  the kill — so an assertion sees a structured failure with command
  context, not a hung suite.  `:exit` is -1 on a timeout (no real exit
  code).  Pass `timeout-ms` to override the default ceiling."
  ([^Process proc] (drain-process proc default-drain-timeout-ms))
  ([^Process proc timeout-ms]
   (let [out-f (future (slurp (.getInputStream proc)))
         err-f (future (slurp (.getErrorStream proc)))
         done? (.waitFor proc timeout-ms java.util.concurrent.TimeUnit/MILLISECONDS)]
     (if done?
       {:exit (.exitValue proc) :out @out-f :err @err-f :timed-out? false}
       (do
         ;; The child outlived the ceiling — force-kill it and interrupt
         ;; the drain threads so neither this helper nor the futures hang.
         (.destroyForcibly proc)
         (future-cancel out-f)
         (future-cancel err-f)
         {:exit       -1
          :out        (try @out-f (catch Throwable _ ""))
          :err        (try @err-f (catch Throwable _ ""))
          :timed-out? true})))))

(defn- run-runner
  "Relaunch a fresh JVM that runs `re-frame.test-quiet.runner/-main` with
  `extra-args`, putting `fixture-dir` on both the classpath and the
  runner's `-d` discovery set.  Returns {:exit :out :err}."
  [^java.io.File fixture-dir & extra-args]
  (let [java-bin (str (io/file (System/getProperty "java.home") "bin"
                               (if (str/includes?
                                     (str/lower-case (System/getProperty "os.name"))
                                     "win")
                                 "java.exe" "java")))
        sep      (System/getProperty "path.separator")
        cp       (str (System/getProperty "java.class.path") sep
                      (.getAbsolutePath fixture-dir))
        cmd      (into [java-bin "-cp" cp "clojure.main"
                        "-m" "re-frame.test-quiet.runner"
                        "-d" (.getAbsolutePath fixture-dir)]
                       extra-args)
        proc     (-> (ProcessBuilder. ^java.util.List cmd)
                     (.redirectErrorStream false)
                     (.start))]
    ;; Drain stdout AND stderr CONCURRENTLY (rf2-spzkgo): with separate
    ;; pipes, slurping stdout fully and only THEN reading stderr can
    ;; deadlock — a child that fills the stderr pipe blocks on write (so
    ;; it never closes stdout / exits), while the parent blocks on stdout
    ;; EOF and never reaches the stderr drain or `waitFor`.
    ;; `drain-process` reads both on their own threads, so both pipes keep
    ;; flowing regardless of which stream the child writes to.
    (drain-process proc)))

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
;; Green path.

(deftest green-runner-contract
  (testing "a green suite through the real -main: exit 0, silent-on-success"
    (with-fixture-dir
      (fn [dir]
        (write-fixture! dir "green_jvm_fixture_test" "green-jvm-fixture-test"
                        "(deftest a-passing-test (is (= 1 1)))")
        (let [{:keys [exit out err]} (run-runner dir)
              non-blank (->> (str/split-lines out)
                             (remove str/blank?))]
          (is (zero? exit)
              (str "green suite must exit 0; got " exit
                   "\n--- stdout ---\n" out "\n--- stderr ---\n" err))
          (is (not (str/includes? out discovery-banner-marker))
              (str "the cognitect discovery banner must be swallowed on"
                   " green; captured stdout:\n" out))
          (is (<= (count non-blank) 3)
              (str "green stdout must be the canonical <=3-line summary;"
                   " got " (count non-blank) " non-blank lines:\n"
                   (str/join "\n" non-blank)))
          (is (str/includes? out "0 failures, 0 errors.")
              (str "green stdout must carry the summary line; got:\n" out)))))))

;; ----------------------------------------------------------------------
;; Exact green stdout shape — no banner-leading-blank leak (rf2-khecvs).
;;
;; cognitect's banner is `\nRunning tests in #{...}\n` — it opens with a
;; BLANK LINE.  Dropping only the `Running tests in #{...}` text left that
;; leading newline behind, so a green real `-main` run started with TWO
;; blank lines before the summary (the banner's leak + clojure.test's own
;; leading `\n` on the `:summary` line). The line-count pins counted only
;; NON-blank lines, so they were false-green for this.  This pin asserts
;; the EXACT line shape: a single leading blank, then the two summary
;; lines, and no `Running tests in` text anywhere.

(deftest green-runner-exact-shape
  (testing "a green real -main run has no leaked banner-leading blank line (rf2-khecvs)"
    (with-fixture-dir
      (fn [dir]
        (write-fixture! dir "exact_green_fixture_test" "exact-green-fixture-test"
                        "(deftest a-passing-test (is (= 1 1)))")
        (let [{:keys [exit out err]} (run-runner dir)
              ;; Normalise CRLF and split keeping empties so we can assert
              ;; on leading-blank position, not just non-blank count.
              lines (-> out (str/replace "\r" "") (str/split #"\n" -1))]
          (is (zero? exit)
              (str "green suite must exit 0; got " exit
                   "\n--- stdout ---\n" out "\n--- stderr ---\n" err))
          (is (not (str/includes? out "Running tests in"))
              (str "no part of the discovery banner — not even its text —"
                   " may reach stdout; got:\n" out))
          ;; The CORE of rf2-khecvs: the first line is the summary's own
          ;; single leading blank; the SECOND line is already the `Ran`
          ;; line. Pre-fix, line 0 AND line 1 were both blank (the banner's
          ;; leaked leading newline sat above the summary's own).
          (is (str/blank? (nth lines 0 nil))
              (str "the canonical summary opens with one leading blank;"
                   " got lines:\n" (pr-str lines)))
          (is (str/starts-with? (str (nth lines 1 "")) "Ran ")
              (str "the SECOND line must be the `Ran ...` summary — a"
                   " second leading blank means the banner's leading"
                   " newline leaked (rf2-khecvs); got lines:\n"
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
        (let [{:keys [exit out err]} (run-runner dir)]
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
        (let [{:keys [exit out err]} (run-runner dir)]
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
;; Exit-code integrity for failures that ESCAPE clojure.test's counters —
;; rf2-jmrdhn.
;;
;; `red-runner-contract` / `error-runner-contract` above pin the COUNTED
;; paths (a failing `is`, a throwing `is`) — clojure.test tallies those and
;; cognitect exits 1 from the tally.  But the false-green trap the bead
;; closes is a failure that clojure.test NEVER counts, so a naive runner
;; could print/emit the problem yet exit 0.  Two such cases exist on the JVM
;; and MUST still exit non-zero:
;;   - a namespace that THROWS at load time (cognitect `require`s it during
;;     discovery, so the throw is outside any `is`/report);
;;   - a `:once` fixture that throws (it runs OUTSIDE `test-var`, so its
;;     exception is not tallied as an `:error`).
;; Both propagate out of `-main`, so `clojure.main` exits non-zero — this
;; pins that no counter-escaping failure can slip out GREEN.

(deftest namespace-load-throw-exits-nonzero
  (testing "a test ns that throws at LOAD time exits non-zero (uncounted failure; rf2-jmrdhn)"
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
              (run-runner dir "-n" "probe.load-throw-test")]
          (is (not timed-out?)
              "the load-throw run must terminate, not hang")
          ;; The CORE pin: a load-time exception is not a counted test
          ;; failure, but it MUST still fail the process.
          (is (not (zero? exit))
              (str "a namespace that throws at load time must exit NON-ZERO"
                   " even though clojure.test never counts it (rf2-jmrdhn);"
                   " got exit " exit "\n--- stdout ---\n" out
                   "\n--- stderr ---\n" err))
          (is (str/includes? (str out err) "LOAD-THROW-MARKER")
              (str "the load-time exception message must surface so the"
                   " failure is diagnosable; got\n--- stdout ---\n" out
                   "\n--- stderr ---\n" err)))))))

(deftest once-fixture-throw-exits-nonzero
  (testing "a :once fixture that throws exits non-zero (uncounted failure; rf2-jmrdhn)"
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
              (run-runner dir "-n" "probe.fixture-throw-test")]
          (is (not timed-out?)
              "the fixture-throw run must terminate, not hang")
          (is (not (zero? exit))
              (str "a :once fixture that throws must exit NON-ZERO even though"
                   " its exception is never tallied as a test error"
                   " (rf2-jmrdhn); got exit " exit "\n--- stdout ---\n" out
                   "\n--- stderr ---\n" err))
          (is (str/includes? (str out err) "FIXTURE-THROW-MARKER")
              (str "the fixture exception message must surface; got"
                   "\n--- stdout ---\n" out "\n--- stderr ---\n" err)))))))

;; ----------------------------------------------------------------------
;; Nested-run banner correctness — rf2-8n97n.1 (mislabel) + .2 (orphan).
;;
;; A `deftest` that nests a `run-tests` over a sibling namespace and then
;; fails must still print `Testing <OUTER-ns>` above its own FAIL block.
;; Pre-fix, a single mutable "current ns" cell — clobbered by the nested
;; run's `:begin-test-ns` — produced one of two broken outputs:
;;   .1 MISLABEL: the OUTER failure printed under `Testing <INNER-ns>`;
;;   .2 ORPHAN:   when the nested run was itself RED, the inner :fail set
;;                the printed-flag, so the OUTER failure printed with NO
;;                banner at all.
;; The fix derives the banner ns from the FAILING var's metadata, so the
;; banner always matches the var that failed regardless of nesting.
;;
;; These run through the REAL `-main` with source-ordered real deftest
;; vars (the inner suite nests BEFORE the outer assertion in source
;; order), so they are immune to the ns-interns hashmap-ordering artifact
;; that made an earlier ad-hoc repro look "correct".  The inner suite is
;; named `*-suite` (not `*-test`) so cognitect's default discovery skips
;; it as a top-level ns — it is exercised only via the outer's nested
;; `run-tests` — and the run is pinned to the outer ns with `-n`.

(deftest nested-green-then-outer-fail-banner
  (testing "outer fail after a GREEN nested run names the OUTER ns (rf2-8n97n.1)"
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
              (run-runner dir "-n" "probe.nested-green-outer-test")]
          (is (= 1 exit)
              (str "outer fail must exit 1; got " exit
                   "\n--- stdout ---\n" out "\n--- stderr ---\n" err))
          (is (str/includes? out "FAIL in (outer-fails-after-green)")
              (str "the outer FAIL block must reach stdout; got:\n" out))
          ;; The CORE of rf2-8n97n.1: the banner above the OUTER failure
          ;; must name the OUTER ns, not the inner one the nested run
          ;; entered last.
          (is (str/includes? out "Testing probe.nested-green-outer-test")
              (str "the outer FAIL must carry its OWN ns banner"
                   " (rf2-8n97n.1 mislabel); got:\n" out))
          (is (not (str/includes? out "Testing probe.nested-green-inner-suite"))
              (str "a GREEN nested run must stay silent — its inner ns"
                   " banner must NOT appear (and must not be borrowed by"
                   " the outer failure); got:\n" out)))))))

(deftest nested-red-then-outer-fail-banner
  (testing "outer fail after a RED nested run keeps BOTH banners correct (rf2-8n97n.2)"
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
              (run-runner dir "-n" "probe.nested-red-outer-test")]
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
          ;; The CORE of rf2-8n97n.2: pre-fix the inner :fail set the
          ;; printed-flag, orphaning the outer failure (NO banner). The
          ;; outer FAIL must now carry its OWN ns banner.
          (is (str/includes? out "Testing probe.nested-red-outer-test")
              (str "the outer FAIL must carry its OWN ns banner, not be"
                   " orphaned (rf2-8n97n.2); got:\n" out))
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
;; Nested-run failure-tally + exit-code soundness — rf2-8n97n.3.
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
  (testing "an outer FAIL after a GREEN nested run propagates to summary + exit 1 (rf2-8n97n.3a)"
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
              (run-runner dir "-n" "probe.tally-green-outer-test")]
          (is (= 1 exit)
              (str "the outer failure MUST drive exit 1 — a regression that"
                   " dropped it would be a false GREEN; got " exit
                   "\n--- stdout ---\n" out "\n--- stderr ---\n" err))
          (is (str/includes? out "1 failures, 0 errors.")
              (str "the OUTER summary must count exactly the outer failure"
                   " (the green nested run adds nothing); got:\n" out)))))))

(deftest nested-inner-fail-does-not-leak-to-outer
  (testing "an inner FAIL the outer IGNORES does not leak into the outer tally/exit (rf2-8n97n.3b)"
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
              (run-runner dir "-n" "probe.scope-red-outer-test")]
          (is (zero? exit)
              (str "the outer run is green — the inner failure the outer"
                   " ignores must NOT leak into the exit code; got " exit
                   "\n--- stdout ---\n" out "\n--- stderr ---\n" err))
          (is (str/includes? out "0 failures, 0 errors.")
              (str "the OUTER summary must stay clean — the scoped inner"
                   " failure must not leak into it; got:\n" out)))))))

;; ----------------------------------------------------------------------
;; test-ns-hook failure banner — rf2-g92dsm.
;;
;; The quiet reporter suppresses `:begin-test-ns` and derives the failure
;; banner ns from `*testing-vars*` (the failing var).  But a namespace's
;; `test-ns-hook` — a supported clojure.test path (`test-ns` calls it in
;; place of `test-all-vars`) — runs OUTSIDE `test-var`, so a bare failing
;; assertion inside it reports with an EMPTY `*testing-vars*`: the
;; var-derived ns is nil and the failure printed with NO banner, violating
;; the failure-banner contract.  The fix maintains a begin/end ns stack and
;; falls back to the innermost open ns when no failing var is in scope.
;; This pin drives the REAL `-main` against a fixture whose `test-ns-hook`
;; fails, and asserts the ns banner is present above the FAIL block.

(deftest test-ns-hook-failure-gets-ns-banner
  (testing "a failing test-ns-hook (no test-var in scope) still prints its ns banner (rf2-g92dsm)"
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
        (let [{:keys [exit out err]} (run-runner dir)]
          (is (= 1 exit)
              (str "the failing test-ns-hook must exit 1; got " exit
                   "\n--- stdout ---\n" out "\n--- stderr ---\n" err))
          ;; The CORE of rf2-g92dsm: pre-fix `failing-ns` was nil (no var in
          ;; scope) so print-banner! no-op'd and the FAIL had no heading. The
          ;; ns-stack fallback must now supply the banner.
          (is (str/includes? out "Testing probe.ns-hook-fail-test")
              (str "a failing test-ns-hook must still carry its ns banner —"
                   " the var-derived ns is nil, so the ns-stack fallback must"
                   " supply it (rf2-g92dsm); got:\n" out))
          (is (str/includes? out "FAIL in")
              (str "the FAIL block must reach stdout; got:\n" out))
          (is (and (str/includes? out "expected:")
                   (str/includes? out "actual:"))
              (str "expected:/actual: lines must reach stdout; got:\n" out))
          (is (str/includes? out "1 failures, 0 errors.")
              (str "the failing summary must reach stdout; got:\n" out)))))))

;; ----------------------------------------------------------------------
;; Diagnostics-survive contract — rf2-lbo79.2.
;;
;; The wrapper used to bind `*out*` to a blanket SINK for the whole
;; delegated `cognitect.test-runner/-main` call, which silenced far more
;; than the discovery banner: `-H` usage text, CLI parse-error
;; diagnostics, and bare `(println ...)` from tests/fixtures all
;; vanished. The fix swaps the sink for a line-precise filter that drops
;; ONLY the `Running tests in #{...}` banner; everything else reaches the
;; real stdout. These subprocess pins prove the misuse paths are no
;; longer opaque while the green-path banner stays quiet.

(deftest help-flag-prints-usage
  (testing "-H prints cognitect usage and exits 0 (was swallowed by the old *out* sink)"
    (with-fixture-dir
      (fn [dir]
        (let [{:keys [exit out err]} (run-runner dir "-H")]
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
              (run-runner dir "--definitely-not-a-runner-option")]
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
  (testing "bare (println ...) from a passing test reaches stdout; banner stays quiet (rf2-lbo79.2)"
    (with-fixture-dir
      (fn [dir]
        (write-fixture! dir "stdout_green_fixture_test" "stdout-green-fixture-test"
                        (str "(deftest a-talking-test"
                             " (println \"BARE-STDOUT-MARKER\")"
                             " (is (= 1 1)))"))
        (let [{:keys [exit out err]} (run-runner dir)]
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
;; Banner-prefix precision — rf2-pjlx6.1.
;;
;; The filter must drop ONLY cognitect's own banner, which always renders
;; as `Running tests in #{...}` (the directory set is always a set: it
;; defaults to `#{"test"}` and `-d` accumulates via `(fnil conj #{})`).  A
;; bare prefix match on `Running tests in ` would also eat a legitimate
;; diagnostic that merely begins with those words — e.g. a fixture that
;; prints `Running tests in local fixture ...`.  This pins that such a
;; line survives while the real discovery banner is still swallowed.

(deftest banner-lookalike-diagnostic-survives
  (testing "a test line beginning 'Running tests in ' but NOT the banner survives (rf2-pjlx6.1)"
    (with-fixture-dir
      (fn [dir]
        (write-fixture! dir "lookalike_fixture_test" "lookalike-fixture-test"
                        (str "(deftest a-lookalike-test"
                             " (println \"Running tests in local fixture LOOKALIKE-MARKER\")"
                             " (is (= 1 1)))"))
        (let [{:keys [exit out err]} (run-runner dir)]
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
;; Banner-prefix OVERDROP — rf2-14nojy.2.
;;
;; The earlier prefix-precision fix (rf2-pjlx6.1) still dropped a line as
;; soon as it reached the EXACT prefix `Running tests in #{`, swallowing
;; everything to the newline.  cognitect prints the banner with `println`,
;; so the real banner is the WHOLE line and stops at the set literal's
;; closing `}` — but a user/fixture diagnostic that merely SHARES that
;; prefix and carries trailing content, e.g.
;; `Running tests in #{:fixture :phase} MARKER`, was silently overdropped
;; even though it is not the banner.  The fix treats a full-prefix match as
;; a CANDIDATE and confirms it at the newline: drop only when the remainder
;; is a balanced set literal followed by nothing but whitespace.  This pins
;; that the overdrop line now SURVIVES while the real banner stays absent.

(deftest banner-prefix-overdrop-survives
  (testing "a line starting EXACTLY 'Running tests in #{' with trailing content survives (rf2-14nojy.2)"
    (with-fixture-dir
      (fn [dir]
        (write-fixture! dir "overdrop_fixture_test" "overdrop-fixture-test"
                        (str "(deftest an-overdrop-test"
                             " (println \"Running tests in #{:fixture :phase} OVERDROP-MARKER\")"
                             " (is (= 1 1)))"))
        (let [{:keys [exit out err]} (run-runner dir)]
          (is (zero? exit)
              (str "the overdrop suite is green; must exit 0; got " exit
                   "\n--- stdout ---\n" out "\n--- stderr ---\n" err))
          ;; The CORE of rf2-14nojy.2: a fixture line sharing the EXACT
          ;; banner prefix but carrying trailing content after the set
          ;; literal must NOT be swallowed.
          (is (str/includes? out "Running tests in #{:fixture :phase} OVERDROP-MARKER")
              (str "a fixture line that starts exactly 'Running tests in #{'"
                   " but has trailing content after the set literal is NOT"
                   " the banner and must survive (rf2-14nojy.2 overdrop);"
                   " got:\n" out))
          (is (str/includes? out "0 failures, 0 errors.")
              (str "the green summary must still print; got:\n" out)))))))

(deftest real-banner-still-dropped-alongside-overdrop-lookalike
  (testing "the real `Running tests in #{...}` discovery banner is STILL dropped (rf2-14nojy.2 negative control)"
    (with-fixture-dir
      (fn [dir]
        ;; A clean green fixture: the ONLY `Running tests in #{...}` line on
        ;; stdout would be cognitect's own discovery banner. Proving it is
        ;; absent confirms the narrowed candidate/confirm logic did not stop
        ;; dropping the genuine banner while it gained the overdrop guard.
        (write-fixture! dir "banner_still_dropped_test" "banner-still-dropped-test"
                        "(deftest a-passing-test (is (= 1 1)))")
        (let [{:keys [exit out err]} (run-runner dir)]
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
;; Banner EXACT-SHAPE overdrop — rf2-m8dbb9.
;;
;; The rf2-14nojy.2 fix above forwards a banner-shaped line that carries
;; TRAILING content after the set literal, but a user/fixture line whose
;; WHOLE text exactly matches the banner — `Running tests in #{:fixture}`
;; with no trailing marker — was still indistinguishable from cognitect's
;; own banner and silently overdropped, contradicting the pass-through
;; stdout contract.  cognitect prints its banner via
;; `(format "\nRunning tests in %s" dirs)`, so the genuine banner is ALWAYS
;; opened by a leading blank line; a bare `(println "Running tests in
;; #{:fixture}")` is not.  The fix requires that held leading blank before
;; dropping a banner-shaped line, so an exact-shape user line printed with
;; no preceding blank survives while the real banner stays absent.

(deftest banner-exact-shape-user-line-survives
  (testing "an exact-shape `Running tests in #{...}` user line (no trailing marker, no leading blank) survives (rf2-m8dbb9)"
    (with-fixture-dir
      (fn [dir]
        ;; A bare `println` of the EXACT banner shape — no trailing content,
        ;; no preceding blank line. Pre-fix this was swallowed wholesale; it
        ;; must now reach stdout because it lacks the banner's leading blank.
        (write-fixture! dir "exact_shape_fixture_test" "exact-shape-fixture-test"
                        (str "(deftest an-exact-shape-test"
                             " (println \"Running tests in #{:fixture}\")"
                             " (is (= 1 1)))"))
        (let [{:keys [exit out err]} (run-runner dir)]
          (is (zero? exit)
              (str "the exact-shape suite is green; must exit 0; got " exit
                   "\n--- stdout ---\n" out "\n--- stderr ---\n" err))
          ;; The CORE of rf2-m8dbb9: a fixture line whose WHOLE text matches
          ;; the banner shape but is emitted with no leading blank is NOT the
          ;; banner and must survive.
          (is (str/includes? out "Running tests in #{:fixture}")
              (str "an exact-shape user line `Running tests in #{:fixture}`"
                   " printed via bare println (no leading blank) is NOT the"
                   " cognitect banner and must survive (rf2-m8dbb9); got:\n"
                   out))
          (is (str/includes? out "0 failures, 0 errors.")
              (str "the green summary must still print; got:\n" out)))))))

(deftest real-banner-still-dropped-alongside-exact-shape-user-line
  (testing "the real banner is STILL dropped even when a fixture also prints an exact-shape line (rf2-m8dbb9 negative control)"
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
        (let [{:keys [exit out err]} (run-runner dir)
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
;; Blank-led banner-shape OVER-DROP after the real banner — rf2-6nrk8x.
;;
;; The rf2-m8dbb9 fix drops a banner-shaped line only when it is preceded
;; by a held leading blank — but it applied that rule to EVERY blank-led
;; `Running tests in #{...}` line, not just cognitect's one discovery
;; banner.  cognitect prints its banner exactly ONCE (via `println`, before
;; any test runs), so a test that ITSELF prints a blank line then
;; banner-shaped text — `(println)` then `(println "Running tests in
;; #{:fixture}")` — matched the same shape and was silently swallowed as a
;; phantom SECOND banner, losing valid diagnostic stdout and violating the
;; pass-through contract.  The fix latches `banner-dropped?` when the real
;; banner is dropped and forwards every later blank-led banner-shaped line.
;; This pin drives the REAL `-main`: the fixture's blank-led banner-shape
;; line must survive while cognitect's genuine banner is still dropped.

(deftest blank-led-banner-shape-user-line-survives
  (testing "a test's own blank-line-then-banner-shape stdout survives after the real banner is dropped (rf2-6nrk8x)"
    (with-fixture-dir
      (fn [dir]
        ;; The fixture prints a blank line IMMEDIATELY followed by an
        ;; exact-shape banner line carrying a keyword-set body (distinct from
        ;; cognitect's own `#{"<dir>"}` string-set banner). Pre-fix, the held
        ;; leading blank + balanced-set shape made the filter overdrop it as a
        ;; second banner; post-fix the `banner-dropped?` latch forwards it.
        (write-fixture! dir "blank_led_banner_fixture_test" "blank-led-banner-fixture-test"
                        (str "(deftest a-blank-then-banner-test"
                             " (println)"
                             " (println \"Running tests in #{:user-diagnostic}\")"
                             " (is (= 1 1)))"))
        (let [{:keys [exit out err]} (run-runner dir)]
          (is (zero? exit)
              (str "the fixture is green; must exit 0; got " exit
                   "\n--- stdout ---\n" out "\n--- stderr ---\n" err))
          ;; The CORE of rf2-6nrk8x: the test's OWN blank-led banner-shape
          ;; line is genuine stdout and must reach the real stdout.
          (is (str/includes? out "Running tests in #{:user-diagnostic}")
              (str "a test's blank-line-then-banner-shape stdout must survive"
                   " once cognitect's one banner has already been dropped"
                   " (rf2-6nrk8x over-drop); got:\n" out))
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
;; Unterminated-partial survival across System/exit — rf2-pjlx6.2.
;;
;; cognitect exits straight from the computed fail/error counts, so the
;; wrapper's `finally` flush never runs on the test path.  A bare
;; `(print ...)` with no trailing newline AND no explicit flush must still
;; reach stdout: the filter forwards non-banner text eagerly (it only ever
;; holds back a live banner-prefix candidate) and `-main` registers a JVM
;; shutdown hook that flushes the filtering writer.  Pre-fix, such a
;; partial sat in the wrapper's line buffer and was lost at exit.

(deftest unterminated-partial-survives-exit
  (testing "a bare (print ...) with no newline/flush reaches stdout before System/exit (rf2-pjlx6.2)"
    (with-fixture-dir
      (fn [dir]
        ;; No trailing newline, no (flush) — the worst case the finding
        ;; describes. The marker must still survive to stdout.
        (write-fixture! dir "partial_fixture_test" "partial-fixture-test"
                        (str "(deftest a-partial-test"
                             " (print \"PARTIAL-EXIT-MARKER\")"
                             " (is (= 1 1)))"))
        (let [{:keys [exit out err]} (run-runner dir)]
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
;; Central stderr buffer + red replay — rf2-nrk066.
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
  (testing "a GREEN run that writes expected stderr stays quiet — the warning is buffered, not leaked (rf2-nrk066)"
    (with-fixture-dir
      (fn [dir]
        ;; A passing test that emits an expected warning to *err* — exactly
        ;; the class warning-heavy suites used to wrap in a private *err*
        ;; sink. With the central buffer it must be DROPPED on green.
        (write-fixture! dir "green_warn_fixture_test" "green-warn-fixture-test"
                        (str "(deftest a-warning-but-passing-test"
                             " (binding [*out* *err*]"
                             "   (println \"EXPECTED-WARN-MARKER-GREEN\"))"
                             " (is (= 1 1)))"))
        (let [{:keys [exit out err]} (run-runner dir)]
          (is (zero? exit)
              (str "the warning-but-passing suite is green; must exit 0; got "
                   exit "\n--- stdout ---\n" out "\n--- stderr ---\n" err))
          ;; The CORE green pin: the expected warning is buffered + dropped,
          ;; so it appears on NEITHER stdout NOR stderr.
          (is (not (str/includes? (str out err) "EXPECTED-WARN-MARKER-GREEN"))
              (str "an expected stderr warning on a GREEN run must be buffered"
                   " and dropped — not leaked to stdout or stderr (rf2-nrk066);"
                   " got\n--- stdout ---\n" out "\n--- stderr ---\n" err))
          (is (str/includes? out "0 failures, 0 errors.")
              (str "the green summary must still print; got:\n" out)))))))

(deftest red-run-replays-buffered-stderr
  (testing "a RED run replays the buffered stderr context to real stderr (rf2-nrk066)"
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
        (let [{:keys [exit out err]} (run-runner dir)]
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
                   " (rf2-nrk066); got\n--- stdout ---\n" out
                   "\n--- stderr ---\n" err))
          (is (str/includes? err "buffered stderr replayed because the run was RED")
              (str "the replay must be labelled so it is distinguishable from"
                   " the reporter's own output; got stderr:\n" err)))))))

;; ----------------------------------------------------------------------
;; Subprocess-harness pipe-deadlock regression — rf2-spzkgo.
;;
;; The harness drains a child's stdout and stderr on separate threads
;; (see `drain-process` / `run-runner`).  Pre-fix it slurped stdout to
;; EOF and only THEN read stderr: a child that fills the stderr pipe
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
  (testing "a child flooding stderr while exiting nonzero is drained without deadlock (rf2-spzkgo)"
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
          (let [os-win   (str/includes? (str/lower-case (System/getProperty "os.name")) "win")
                java-bin (str (io/file (System/getProperty "java.home") "bin"
                                       (if os-win "java.exe" "java")))
                cmd      [java-bin "-cp" (System/getProperty "java.class.path")
                          "clojure.main" (.getAbsolutePath flood-file)]
                result-f (future
                           (-> (ProcessBuilder. ^java.util.List cmd)
                               (.redirectErrorStream false)
                               (.start)
                               (drain-process)))
                ;; A correctly draining harness returns in well under a
                ;; second; 60s is a generous ceiling that still FAILS (not
                ;; hangs) if the sequential-drain deadlock is reintroduced.
                result   (deref result-f 60000 ::timed-out)]
            (is (not= ::timed-out result)
                (str "the harness deadlocked: a >pipe-buffer stderr flood with a"
                     " sequential (stdout-then-stderr) drain hangs forever"
                     " (rf2-spzkgo) — it must drain both streams concurrently"))
            (when (not= ::timed-out result)
              (let [{:keys [exit out err timed-out?]} result]
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
;; Shared drain timeout/kill policy — rf2-8dfq5j.3.
;;
;; `drain-process` carries the timeout/kill policy so EVERY subprocess
;; path (the real `run-runner`, the deadlock regression, future helpers)
;; fails fast on a wedged child instead of hanging the suite.  This pins
;; that fail-fast behaviour directly: a child that NEVER exits must be
;; force-killed at the ceiling and the helper must return `:timed-out?
;; true` promptly — so a future change that drops the `.waitFor` timeout
;; cannot silently reintroduce an unbounded hang.

(deftest drain-process-kills-and-flags-a-hanging-child
  (testing "a child that never exits is force-killed and flagged timed-out (rf2-8dfq5j.3)"
    (with-fixture-dir
      (fn [dir]
        ;; A child that blocks forever with no output and no exit path —
        ;; the worst case the finding describes.  Written to a file for
        ;; the same cross-platform arg-quoting reason as the flood child.
        (let [hang-file (io/file dir "hang_forever.clj")]
          (spit hang-file "@(promise)\n") ; deref an unfulfilled promise → blocks forever
          (let [os-win   (str/includes? (str/lower-case (System/getProperty "os.name")) "win")
                java-bin (str (io/file (System/getProperty "java.home") "bin"
                                       (if os-win "java.exe" "java")))
                cmd      [java-bin "-cp" (System/getProperty "java.class.path")
                          "clojure.main" (.getAbsolutePath hang-file)]
                proc     (-> (ProcessBuilder. ^java.util.List cmd)
                             (.redirectErrorStream false)
                             (.start))
                ;; A SHORT explicit ceiling keeps the test fast; the outer
                ;; deref is the belt-and-suspenders so even a regressed
                ;; helper fails as a TIMEOUT, never an infinite hang.
                result-f (future (drain-process proc 1500))
                result   (deref result-f 30000 ::outer-timeout)]
            (is (not= ::outer-timeout result)
                (str "the helper itself hung: `drain-process` must enforce its"
                     " own `.waitFor` timeout and return promptly (rf2-8dfq5j.3)"))
            (when (not= ::outer-timeout result)
              (is (true? (:timed-out? result))
                  (str "a never-exiting child must be flagged `:timed-out? true`;"
                       " got " (pr-str (dissoc result :out :err))))
              (is (= -1 (:exit result))
                  "a timed-out drain reports exit -1 (no real exit code)")
              (is (not (.isAlive proc))
                  "the child must be force-killed on the timeout, not left alive"))
            ;; Cleanup belt: ensure the child is gone regardless.
            (.destroyForcibly proc)))))))
