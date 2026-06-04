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
