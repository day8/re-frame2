(ns re-frame.test-quiet-runner-contract-test
  "Subprocess contracts for the JVM quiet runner."
  (:require [clojure.test :refer [deftest is testing]]
            [clojure.string :as str]
            [clojure.java.io :as io]))

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
  "Drain both pipes concurrently and kill a child after 60 seconds."
  [^Process process]
  (let [stdout-future (future (slurp (.getInputStream process)))
        stderr-future (future (slurp (.getErrorStream process)))]
    (if (.waitFor process 60000 java.util.concurrent.TimeUnit/MILLISECONDS)
      {:exit (.exitValue process) :out @stdout-future :err @stderr-future}
      (do (.destroyForcibly process)
          {:exit -1 :out "" :err "the child timed out and was killed"}))))

(defn- invoke-runner
  "Invoke a fresh JVM with explicit roots, discovery directory and clean runner env."
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
    (.remove child-env "RF2_VERBOSE_TESTS")
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

(defn- stdout-lines
  "`out` split on newlines, keeping the trailing empty string, with
  cognitect's `Running tests in #{...}` line replaced by `:banner` (its
  directory set is a temp path)."
  [out]
  (mapv #(if (re-matches #"Running tests in #\{.+\}" %) :banner %)
        (str/split (str/replace out "\r" "") #"\n" -1)))

(deftest green-runner-exact-shape
  (with-fixture-dir
    (fn [dir]
      (write-fixture! dir "exact_green_fixture_test" "exact-green-fixture-test"
                      (str "(deftest a-passing-test"
                           " (binding [*out* *err*] (println \"GREEN-STDERR-MARKER err\"))"
                           " (.println System/err \"GREEN-STDERR-MARKER raw\")"
                           " (is (= 1 1)))"))
      (let [{:keys [exit out err]} (invoke-quiet-runner dir)]
        (is (zero? exit) (str out err))
        (is (= ["" :banner "" "Ran 1 tests containing 1 assertions." "0 failures, 0 errors." ""]
               (stdout-lines out))
            out)
        (is (not (str/includes? err "GREEN-STDERR-MARKER")) err)))))

(deftest red-runner-contract
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
        (is (every? #(str/includes? out %)
                    ["Testing probe.red-jvm-fixture-test" "FAIL in (a-failing-test)"
                     "expected:" "actual:" "ERROR in (an-erroring-test)"
                     "boom-marker" "1 failures, 1 errors."]) out)
        (is (str/includes? err "RAW-SYSERR-MARKER-RED") err)))))

(defn- invoke-verbose-runner
  "`invoke-quiet-runner` with `RF2_VERBOSE_TESTS=1`."
  [dir]
  (invoke-runner {:roots [dir] :dir dir :env {"RF2_VERBOSE_TESTS" "1"}}))

(deftest verbose-green-run-names-each-namespace-once
  (with-fixture-dir
    (fn [dir]
      (write-fixture! dir "verbose_alpha_test" "verbose-alpha-test"
                      "(deftest an-alpha-test (is (= 1 1)))")
      (write-fixture! dir "verbose_beta_test" "verbose-beta-test"
                      "(deftest a-beta-test (is (= 1 1)))")
      (let [{:keys [exit out err]} (invoke-verbose-runner dir)
            shape (fn [first-ns second-ns]
                    ["" :banner
                     "" (str "Testing probe." first-ns)
                     "" (str "Testing probe." second-ns)
                     "" "Ran 2 tests containing 2 assertions." "0 failures, 0 errors." ""])]
        (is (zero? exit) (str out err))
        (is (contains? #{(shape "verbose-alpha-test" "verbose-beta-test")
                         (shape "verbose-beta-test" "verbose-alpha-test")}
                       (stdout-lines out))
            out)))))

(deftest verbose-red-run-prints-each-banner-once
  (with-fixture-dir
    (fn [dir]
      (write-fixture! dir "verbose_passing_test" "verbose-passing-test"
                      "(deftest a-passing-test (is (= 1 1)))")
      (write-fixture! dir "verbose_failing_test" "verbose-failing-test"
                      (str "(deftest a-failing-test (is (= :exp :act)))\n"
                           "(deftest an-erroring-test"
                           " (is (= 1 (throw (ex-info \"verbose-boom\" {})))))"))
      (let [{:keys [exit out err]} (invoke-verbose-runner dir)]
        (is (= 1 exit) (str out err))
        (is (= [1 1] (mapv #(count (re-seq % out))
                           [#"Testing probe\.verbose-passing-test"
                            #"Testing probe\.verbose-failing-test"])) out)
        (is (every? #(str/includes? out %)
                    ["FAIL in (a-failing-test)" "ERROR in (an-erroring-test)"]) out)))))

(deftest namespace-load-throw-replays-buffered-stderr
  (with-fixture-dir
    (fn [dir]
      (write-fixture! dir "load_throw_stderr_test" "load-throw-stderr-test"
                      (str "(binding [*out* *err*] (println \"PRE-LOAD-THROW-ERR-MARKER\"))\n"
                           "(.println System/err \"PRE-LOAD-THROW-RAW-MARKER\")\n"
                           "(deftest anchor (is (= 1 1)))\n"
                           "(throw (ex-info \"LOAD-THROW-STDERR-MARKER\" {}))"))
      (let [{:keys [exit out err]} (invoke-quiet-runner dir)]
        (is (= 1 exit) (str out err))
        (is (str/includes? (str out err) "LOAD-THROW-STDERR-MARKER"))
        (is (str/includes? err "PRE-LOAD-THROW-ERR-MARKER") err)
        (is (str/includes? err "PRE-LOAD-THROW-RAW-MARKER") err)))))

(deftest once-fixture-throw-replays-buffered-stderr
  (with-fixture-dir
    (fn [dir]
      (write-fixture! dir "once_throw_test" "once-throw-test"
                      (str "(use-fixtures :once"
                           " (fn [_run]"
                           "   (binding [*out* *err*] (println \"PRE-ONCE-THROW-ERR-MARKER\"))"
                           "   (throw (ex-info \"ONCE-THROW-MARKER\" {}))))\n"
                           "(deftest anchor (is (= 1 1)))"))
      (let [{:keys [exit out err]} (invoke-quiet-runner dir)]
        (is (= 1 exit) (str out err))
        (is (str/includes? err "PRE-ONCE-THROW-ERR-MARKER") err)))))

(deftest map-literal-fixture-refuses-the-run
  (with-fixture-dir
    (fn [dir]
      (write-fixture! dir "map_fixture_test" "map-fixture-test"
                      (str "(use-fixtures :each {:before (fn []) :after (fn [])})\n"
                           "(deftest a-test-that-would-fail (is (= 1 2)))"))
      (let [{:keys [exit out err]} (invoke-quiet-runner dir)]
        (is (= 1 exit) (str out err))
        (is (str/includes? err "probe.map-fixture-test") err)
        (is (not (str/includes? err "below the floor of")) err)))))

(deftest invalid-flag-prints-parse-error
  (with-fixture-dir
    (fn [dir]
      (let [{:keys [exit out err]} (invoke-quiet-runner dir "--definitely-not-a-runner-option")]
        (is (= 1 exit) (str out err))
        (is (str/includes? out "Unknown option: \"--definitely-not-a-runner-option\"") out)))))

(deftest unterminated-partial-survives-exit
  (with-fixture-dir
    (fn [dir]
      (write-fixture! dir "partial_fixture_test" "partial-fixture-test"
                      "(deftest a-partial-test (print \"PARTIAL-EXIT-MARKER\") (is (= 1 1)))")
      (let [{:keys [exit out err]} (invoke-quiet-runner dir)]
        (is (zero? exit) (str out err))
        (is (str/includes? out "PARTIAL-EXIT-MARKER") out)))))

(deftest stderr-ring-front-trims-to-newest-cap-on-red
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
  (with-fixture-dir
    (fn [dir]
      (let [{:keys [exit out err]} (invoke-quiet-runner dir "--probe")]
        (is (zero? exit) (str out err))
        (is (= [:banner "Ran 0 tests containing 0 assertions." "0 failures, 0 errors."]
               (remove #{""} (stdout-lines out)))
            out)))))

(deftest probe-lane-that-gained-tests-is-red
  (with-fixture-dir
    (fn [dir]
      (write-fixture! dir "probe_gained_fixture_test" "probe-gained-fixture-test"
                      "(deftest a-passing-test (is (= 1 1)))")
      (let [{:keys [exit err]} (invoke-quiet-runner dir "--probe")]
        (is (= 1 exit) err)
        (is (str/includes? err "declared a classpath probe") err)))))

(deftest malformed-floor-is-a-configuration-error
  (with-fixture-dir
    (fn [dir]
      (let [{:keys [exit out err]} (invoke-runner {:roots [dir] :dir dir :env {"RF2_MIN_TESTS" "1O"}})]
        (is (= 2 exit) (str out err))))))

(deftest an-undiscoverable-file-refuses-the-run
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
  ;; Both depths must refuse: each clears a different own-path arm.
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

(deftest a-nested-run-tests-reaches-neither-the-outer-verdict-nor-the-jvm
  ;; The nested run executes zero tests: a verdict read off every `:summary`
  ;; report would find it below the floor and exit before the second deftest.
  (with-fixture-dir
    (fn [dir]
      (write-deep-fixture! dir "probe/empty_inner.clj" "(ns probe.empty-inner)")
      (write-fixture! dir "nested_outer_test" "nested-outer-test"
                      (str "(require 'probe.empty-inner)\n"
                           "(deftest runs-an-empty-suite-inside"
                           " (is (zero? (:test (binding [clojure.test/*test-out* (java.io.StringWriter.)]"
                           " (clojure.test/run-tests 'probe.empty-inner))))))\n"
                           "(deftest still-runs-after-it (is (= 1 1)))"))
      (let [{:keys [exit out err]} (invoke-quiet-runner dir)]
        (is (zero? exit) (str out err))
        (is (str/includes? out "Ran 2 tests containing 2 assertions.") out)))))

(deftest a-loaded-namespace-the-run-did-not-select-cannot-fail-it
  ;; The support namespace is loaded, because the selected test requires it,
  ;; but not selected: it has no `-test` suffix. Its data fixture is not this
  ;; run's to audit.
  (with-fixture-dir
    (fn [dir]
      (write-deep-fixture! dir "probe/data_fixture_support.clj"
                           (str "(ns probe.data-fixture-support"
                                " (:require [clojure.test :refer [use-fixtures]]))\n"
                                "(use-fixtures :each {:before (fn []) :after (fn [])})"))
      (write-fixture! dir "focused_test" "focused-test"
                      (str "(require 'probe.data-fixture-support)\n"
                           "(deftest a-passing-test (is (= 1 1)))"))
      (let [{:keys [exit out err]} (invoke-quiet-runner dir)]
        (is (zero? exit) (str out err))
        (is (not (str/includes? err "probe.data-fixture-support")) err)))))

(defn- write-tagged-fixtures!
  "A namespace tagged `^:requires-debug` on its `(ns ...)` form, which
  announces its load on stdout and holds one failing deftest, beside an
  untagged namespace holding one passing deftest and one var-tagged failing
  one."
  [dir]
  (write-deep-fixture! dir "probe/ns_tagged_test.clj"
                       (str "(ns ^:requires-debug probe.ns-tagged-test"
                            " (:require [clojure.test :refer [deftest is]]))\n"
                            "(println \"NS-TAGGED-LOADED-MARKER\")\n"
                            "(deftest would-fail (is (= :ns-tagged :ran)))"))
  (write-fixture! dir "untagged_test" "untagged-test"
                  (str "(deftest a-passing-test (is (= 1 1)))\n"
                       "(deftest ^:requires-debug var-tagged-would-fail"
                       " (is (= :var-tagged :ran)))")))

(deftest a-namespace-level-tag-selects-like-a-var-level-one
  (with-fixture-dir
    (fn [dir]
      (write-tagged-fixtures! dir)
      (testing "-e loads the tagged namespace and runs none of it"
        (let [{:keys [exit out err]} (invoke-quiet-runner dir "-e" ":requires-debug")]
          (is (zero? exit) (str out err))
          (is (str/includes? out "NS-TAGGED-LOADED-MARKER") out)
          (is (str/includes? out "Ran 1 tests containing 1 assertions.") out)))
      (testing "-i takes both tag levels in"
        (let [{:keys [exit out err]} (invoke-quiet-runner dir "-i" ":requires-debug")]
          (is (= 1 exit) (str out err))
          (is (every? #(str/includes? out %)
                      ["FAIL in (would-fail)" "FAIL in (var-tagged-would-fail)"
                       "Ran 2 tests containing 2 assertions."])
              out))))))

(deftest a-load-error-in-a-namespace-level-excluded-namespace-is-red
  (with-fixture-dir
    (fn [dir]
      (write-deep-fixture! dir "probe/ns_tagged_throws_test.clj"
                           (str "(ns ^:requires-debug probe.ns-tagged-throws-test"
                                " (:require [clojure.test :refer [deftest is]]))\n"
                                "(throw (ex-info \"NS-TAGGED-LOAD-THROW-MARKER\" {}))"))
      (write-fixture! dir "untagged_anchor_test" "untagged-anchor-test"
                      "(deftest a-passing-test (is (= 1 1)))")
      (let [{:keys [exit out err]} (invoke-quiet-runner dir "-e" ":requires-debug")]
        (is (= 1 exit) (str out err))
        (is (str/includes? (str out err) "NS-TAGGED-LOAD-THROW-MARKER") (str out err))))))
