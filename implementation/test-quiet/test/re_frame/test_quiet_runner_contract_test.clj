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
     stdout."
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
                     (.start))
        out      (slurp (.getInputStream proc))
        err      (slurp (.getErrorStream proc))
        exit     (.waitFor proc)]
    {:exit exit :out out :err err}))

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
