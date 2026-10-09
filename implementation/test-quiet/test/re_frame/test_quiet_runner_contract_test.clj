(ns re-frame.test-quiet-runner-contract-test
  "Subprocess contract pins for the JVM entry point
  `re-frame.test-quiet.runner/-main`. cognitect.test-runner `System/exit`s
  from the computed tally, so each test relaunches a fresh JVM on this
  classpath against a throwaway fixture suite and asserts on its stdout,
  stderr and exit code."
  (:require [clojure.test :refer [deftest is]]
            [clojure.string :as str]
            [clojure.java.io :as io]))

;; ----------------------------------------------------------------------
;; Subprocess harness.

(defn- write-fixture!
  "Write a fixture test ns `probe.<ns-suffix>` holding `body` as
  `probe/<file-stem>.clj` under `dir`."
  [^java.io.File dir file-stem ns-suffix body]
  (let [pkg-dir (io/file dir "probe")]
    (.mkdirs pkg-dir)
    (spit (io/file pkg-dir (str file-stem ".clj"))
          (str "(ns probe." ns-suffix "\n"
               "  (:require [clojure.test :refer [deftest is use-fixtures]]))\n"
               body "\n"))))

(defn- write-deep-fixture!
  "Write `source` verbatim at `relative-path` under `dir`."
  [^java.io.File dir relative-path source]
  (let [file (io/file dir relative-path)]
    (.mkdirs (.getParentFile file))
    (spit file (str source "\n"))))

(defn- drain-process
  "Drain stdout and stderr on their own threads (a sequential drain deadlocks
  on a child that fills the stderr pipe), then wait under a timeout that
  force-kills a wedged child and reports exit -1."
  [^Process process]
  (let [stdout-future (future (slurp (.getInputStream process)))
        stderr-future (future (slurp (.getErrorStream process)))]
    (if (.waitFor process 60000 java.util.concurrent.TimeUnit/MILLISECONDS)
      {:exit (.exitValue process) :out @stdout-future :err @stderr-future}
      (do (.destroyForcibly process)
          {:exit -1 :out "" :err "the child timed out and was killed"}))))

(defn- invoke-runner
  "Relaunch `-main` in a fresh JVM with `roots` appended to this classpath and
  `dir` as `-d`. `env` is layered over an environment stripped of
  `RF2_MIN_TESTS`, so an outer shell's floor cannot perturb a pin."
  [{:keys [roots dir env]} & runner-args]
  (let [java-executable (str (io/file (System/getProperty "java.home") "bin"
                                      (if (str/includes?
                                            (str/lower-case (System/getProperty "os.name"))
                                            "win")
                                        "java.exe" "java")))
        classpath       (str/join (System/getProperty "path.separator")
                                  (cons (System/getProperty "java.class.path")
                                        (map #(.getAbsolutePath ^java.io.File %) roots)))
        builder         (ProcessBuilder. ^java.util.List
                                         (into [java-executable "-cp" classpath "clojure.main"
                                                "-m" "re-frame.test-quiet.runner"
                                                "-d" (.getAbsolutePath ^java.io.File dir)]
                                               runner-args))
        child-env       (.environment builder)]
    (.remove child-env "RF2_MIN_TESTS")
    (doseq [[k v] env] (.put child-env k v))
    (drain-process (.start builder))))

(defn- invoke-quiet-runner
  "`invoke-runner` with `dir` as both the classpath root and `-d`."
  [dir & runner-args]
  (apply invoke-runner {:roots [dir] :dir dir} runner-args))

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
;; Green and red paths.

(deftest green-runner-exact-shape
  ;; cognitect's banner is `\nRunning tests in #{...}`, so dropping only its
  ;; text would leave a second leading blank. Both stderr channels, `*err*`
  ;; and raw System.err, are buffered and dropped on green.
  (with-fixture-dir
    (fn [dir]
      (write-fixture! dir "exact_green_fixture_test" "exact-green-fixture-test"
                      (str "(deftest a-passing-test"
                           " (binding [*out* *err*] (println \"GREEN-STDERR-MARKER err\"))"
                           " (.println System/err \"GREEN-STDERR-MARKER raw\")"
                           " (is (= 1 1)))"))
      (let [{:keys [exit out err]} (invoke-quiet-runner dir)]
        (is (zero? exit) (str out err))
        (is (= ["" "Ran 1 tests containing 1 assertions." "0 failures, 0 errors." ""]
               (str/split (str/replace out "\r" "") #"\n" -1)))
        (is (not (str/includes? err "GREEN-STDERR-MARKER")) err)))))

(deftest red-runner-contract
  ;; A red run prints the withheld namespace banner over the library's own
  ;; FAIL/ERROR blocks, and replays the buffered stderr, raw System.err
  ;; included.
  (with-fixture-dir
    (fn [dir]
      (write-fixture! dir "red_jvm_fixture_test" "red-jvm-fixture-test"
                      (str "(deftest a-failing-test"
                           " (.println System/err \"RAW-SYSERR-MARKER-RED\")"
                           " (is (= :exp :act)))\n"
                           "(deftest an-erroring-test"
                           " (is (= 1 (throw (ex-info \"boom-marker\" {})))))"))
      (let [{:keys [exit out err]} (invoke-quiet-runner dir)]
        (is (= 1 exit) (str out err))
        (is (str/includes? out "Testing probe.red-jvm-fixture-test") out)
        (is (str/includes? out "FAIL in (a-failing-test)") out)
        (is (and (str/includes? out "expected:") (str/includes? out "actual:")) out)
        (is (str/includes? out "ERROR in (an-erroring-test)") out)
        (is (str/includes? out "boom-marker") out)
        (is (str/includes? out "1 failures, 1 errors.") out)
        (is (str/includes? err "RAW-SYSERR-MARKER-RED") err)))))

(deftest namespace-load-throw-exits-nonzero
  ;; A throw at require time is outside any `is`, so clojure.test never
  ;; counts it; it must still fail the process.
  (with-fixture-dir
    (fn [dir]
      (write-fixture! dir "load_throw_test" "load-throw-test"
                      (str "(deftest anchor (is (= 1 1)))\n"
                           "(throw (ex-info \"LOAD-THROW-MARKER\" {}))"))
      (let [{:keys [exit out err]} (invoke-quiet-runner dir)]
        (is (= 1 exit) (str out err))
        (is (str/includes? (str out err) "LOAD-THROW-MARKER"))))))

(deftest map-literal-fixture-refuses-the-run
  ;; cljs.test's map fixture is a key lookup on the JVM: the failing test never
  ;; runs and the tally reads clean. The fixture rule is read before the
  ;; coverage floor, whose advice would send the operator to the wrong file.
  (with-fixture-dir
    (fn [dir]
      (write-fixture! dir "map_fixture_test" "map-fixture-test"
                      (str "(use-fixtures :each {:before (fn []) :after (fn [])})\n"
                           "(deftest a-test-that-would-fail (is (= 1 2)))"))
      (let [{:keys [exit out err]} (invoke-quiet-runner dir)]
        (is (= 1 exit) (str out err))
        (is (str/includes? err "probe.map-fixture-test") err)
        (is (not (str/includes? err "below the floor of")) err)))))

;; ----------------------------------------------------------------------
;; The banner filter forwards everything but the banner.

(deftest invalid-flag-prints-parse-error
  ;; cognitect prints parse errors through the same `*out*` as its banner.
  (with-fixture-dir
    (fn [dir]
      (let [{:keys [exit out err]} (invoke-quiet-runner dir "--definitely-not-a-runner-option")]
        (is (= 1 exit) (str out err))
        (is (str/includes? out "Unknown option: \"--definitely-not-a-runner-option\"") out)))))

(deftest unterminated-partial-survives-exit
  ;; cognitect `System/exit`s before `-main`'s `finally`, so a bare `print`
  ;; with no newline survives only by the filter's eager forward and the
  ;; shutdown-hook flush.
  (with-fixture-dir
    (fn [dir]
      (write-fixture! dir "partial_fixture_test" "partial-fixture-test"
                      "(deftest a-partial-test (print \"PARTIAL-EXIT-MARKER\") (is (= 1 1)))")
      (let [{:keys [exit out err]} (invoke-quiet-runner dir)]
        (is (zero? exit) (str out err))
        (is (str/includes? out "PARTIAL-EXIT-MARKER") out)))))

;; ----------------------------------------------------------------------
;; The stderr ring is bounded to its newest characters.

(deftest stderr-ring-front-trims-to-newest-cap-on-red
  ;; ~600 K characters through `*err*` on a red run: the replay keeps the
  ;; newest 256 K, so the last line survives and the volume is bounded.
  (with-fixture-dir
    (fn [dir]
      (write-fixture! dir "ringcap_fixture_test" "ringcap-fixture-test"
                      (str "(deftest a-huge-warning-and-failing-test"
                           " (binding [*out* *err*]"
                           "   (dotimes [_ 2000] (println (apply str (repeat 300 \"F\"))))"
                           "   (println \"TAIL-MARKER-NEWEST-MUST-SURVIVE\"))"
                           " (is (= :exp :act)))"))
      (let [{:keys [err]} (invoke-quiet-runner dir)]
        (is (str/includes? err "TAIL-MARKER-NEWEST-MUST-SURVIVE") (count err))
        (is (< (count err) (+ (* 256 1024) 4096)) (count err))))))

;; ----------------------------------------------------------------------
;; What a lane claims: a suite lane must prove it ran, a `--probe` lane that
;; it resolved without running.

(deftest zero-test-discovery-is-red
  (with-fixture-dir
    (fn [dir]
      (let [{:keys [exit err]} (invoke-quiet-runner dir)]
        (is (= 1 exit) err)
        (is (str/includes? err "below the floor of 1") err)))))

(deftest floor-above-the-real-count-is-red
  (with-fixture-dir
    (fn [dir]
      (write-fixture! dir "floor_raised_fixture_test" "floor-raised-fixture-test"
                      "(deftest a-passing-test (is (= 1 1)))")
      (let [{:keys [exit err]} (invoke-runner {:roots [dir] :dir dir :env {"RF2_MIN_TESTS" "2"}})]
        (is (= 1 exit) err)
        (is (str/includes? err "executed 1 test(s), below the floor of 2") err)))))

(deftest probe-lane-with-zero-tests-is-green
  ;; `--probe` is stripped before cognitect parses, and a green probe
  ;; announces nothing.
  (with-fixture-dir
    (fn [dir]
      (let [{:keys [exit out err]} (invoke-quiet-runner dir "--probe")]
        (is (zero? exit) (str out err))
        (is (= ["Ran 0 tests containing 0 assertions." "0 failures, 0 errors."]
               (remove str/blank? (str/split-lines out))))))))

(deftest probe-lane-that-gained-tests-is-red
  (with-fixture-dir
    (fn [dir]
      (write-fixture! dir "probe_gained_fixture_test" "probe-gained-fixture-test"
                      "(deftest a-passing-test (is (= 1 1)))")
      (let [{:keys [exit err]} (invoke-quiet-runner dir "--probe")]
        (is (= 1 exit) err)
        (is (str/includes? err "declared a classpath probe") err)))))

(deftest malformed-floor-is-a-configuration-error
  ;; `1O` (letter O) silently defaulting would disable the floor; exit 2 marks
  ;; a configuration error, distinct from a red run's 1.
  (with-fixture-dir
    (fn [dir]
      (let [{:keys [exit out err]} (invoke-runner {:roots [dir] :dir dir :env {"RF2_MIN_TESTS" "1O"}})]
        (is (= 2 exit) (str out err))))))

;; ----------------------------------------------------------------------
;; What the lane will discover.

(deftest an-undiscoverable-file-refuses-the-run
  ;; One unescaped `"` in an ns docstring: cognitect's discovery drops the
  ;; file silently and the run would report `0 failures`. The guard names the
  ;; file and carries the reader's own message.
  (with-fixture-dir
    (fn [dir]
      (write-fixture! dir "discovery_fixture_test" "discovery-fixture-test"
                      "(deftest a-passing-test (is (= 1 1)))")
      (write-deep-fixture! dir "probe/unreadable_test.clj"
                           (str "(ns probe.unreadable-test\n"
                                "  \"A docstring with a stray \" quote in it.\"\n"
                                "  (:require [clojure.test :refer [deftest is]]))\n"
                                "(deftest silently-dropped (is (= 1 1)))"))
      (let [{:keys [exit err]} (invoke-quiet-runner dir)]
        (is (= 1 exit) err)
        (is (str/includes? err "unreadable_test.clj") err)
        (is (str/includes? err "EOF") err)))))

(deftest a-cljc-shadowed-by-a-clj-in-another-root-is-refused
  ;; `require` asks the whole classpath for a namespace's `.clj` before its
  ;; `.cljc`, so a selected failing `.cljc` beside a passing `.clj` of the same
  ;; namespace in another root would run the `.clj` and exit 0. Two depths,
  ;; because the selected file clears the own-path rule by a different arm at
  ;; each: its path relative to `-d`, then resolution through the classpath.
  (with-fixture-dir
    (fn [tmp]
      (let [selected (io/file tmp "selected")
            shadow   (io/file tmp "shadow")]
        (write-deep-fixture! selected "probe/deep/mixed_test.cljc"
                             (str "(ns probe.deep.mixed-test (:require [clojure.test :refer [deftest is]]))\n"
                                  "(deftest the-selected-cljc-fails (is (= :selected :shadow)))"))
        (write-deep-fixture! shadow "probe/deep/mixed_test.clj"
                             (str "(ns probe.deep.mixed-test (:require [clojure.test :refer [deftest is]]))\n"
                                  "(deftest the-shadow-clj-passes (is (= 1 1)))"))
        (doseq [dir [selected (io/file selected "probe")]]
          (let [{:keys [exit out err]} (invoke-runner {:roots [selected shadow] :dir dir})]
            (is (= 1 exit) (str out err))
            (is (str/includes? err "selected/probe/deep/mixed_test.cljc") err)
            (is (str/includes? err "shadow/probe/deep/mixed_test.clj`") err)))))))
