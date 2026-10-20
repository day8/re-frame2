(ns re-frame.ssr.ring.debug-gate-default-test
  "The JVM debug gate's default on a JVM that hosts SSR (Spec 011 §The debug
  gate on an SSR JVM).

  `re-frame.interop/debug-enabled?` is read ONCE, when `re-frame.interop`
  loads, so no assertion made inside this JVM can say what a different
  setting would have produced. Each case below therefore launches its own
  child JVM, on this suite's classpath or that classpath with the ssr-ring
  artefact taken out, and reads what the gate resolved to there:

    - ssr-ring on the classpath, nothing set           -> false
    - ssr-ring on the classpath, `-Dre-frame.debug=true` -> true
    - ssr-ring on the classpath, `RE_FRAME_DEBUG=true`   -> true
    - ssr-ring on the classpath, an unrecognised value   -> false
    - no ssr-ring, nothing set                         -> true
    - no ssr-ring, `-Dre-frame.debug=false`            -> false

  The last test is about THIS JVM: the `:test` alias opts the suite into dev
  posture with `-Dre-frame.debug=true`, because ssr-ring's tests read traces
  and ssr-ring on the classpath would otherwise turn them off."
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [re-frame.interop :as rf.interop])
  (:import [java.io File]
           [java.util.concurrent TimeUnit]))

(set! *warn-on-reflection* true)

(def ^:private probe
  "The child's whole program: load the gate and print what it resolved to."
  "(require 're-frame.interop) re-frame.interop/debug-enabled?")

(defn- ssr-ring-dir
  "implementation/ssr-ring, found from this file's own classpath resource
  rather than the working directory."
  ^File []
  (-> (io/resource "re_frame/ssr/ring/debug_gate_default_test.clj")
      io/as-file
      .getCanonicalFile
      .getParentFile                ; ring
      .getParentFile                ; ssr
      .getParentFile                ; re_frame
      .getParentFile                ; test
      .getParentFile))              ; ssr-ring

(defn- canonical-entry
  "A classpath entry as a canonical path; a relative entry resolves against
  the working directory, as the child resolves it."
  ^String [^String entry]
  (let [f (io/file entry)
        f (if (.isAbsolute f) f (io/file (System/getProperty "user.dir") entry))]
    (.getCanonicalPath ^File f)))

(defn- classpath-entries []
  (str/split (System/getProperty "java.class.path")
             (re-pattern (java.util.regex.Pattern/quote File/pathSeparator))))

(defn- without-ssr-ring
  "This suite's classpath minus every root inside the ssr-ring artefact."
  [entries]
  (let [root (str (.getCanonicalPath (ssr-ring-dir)) File/separator)]
    (remove #(str/starts-with? (canonical-entry %) root) entries)))

(defn- run-probe
  "Launch a child JVM on `entries` with `jvm-opts` and `env` (a map; the
  inherited `RE_FRAME_DEBUG` is always removed first) and return what
  `debug-enabled?` read there, as the printed string.

  The classpath travels in an argument file, which keeps a long Windows
  classpath under the command-line limit; forward slashes keep the file free
  of the backslashes the argument-file syntax would read as escapes."
  [entries jvm-opts env]
  (let [argfile (File/createTempFile "rf-debug-gate-cp" ".txt")]
    (try
      (spit argfile (str "-cp \""
                         (str/join File/pathSeparator
                                   (map #(str/replace % "\\" "/") entries))
                         "\"\n"))
      (let [java (str (io/file (System/getProperty "java.home") "bin" "java"))
            cmd  (concat [java (str "@" (.getPath argfile))]
                         jvm-opts
                         ["clojure.main" "-e" probe])
            pb   (doto (ProcessBuilder. ^java.util.List (vec cmd))
                   (.directory (io/file (System/getProperty "user.dir")))
                   (.redirectErrorStream true))
            penv (.environment pb)]
        (.remove penv "RE_FRAME_DEBUG")
        (doseq [[k v] env] (.put penv k v))
        (let [p   (.start pb)
              out (future (slurp (.getInputStream p)))]
          (if (.waitFor p 120 TimeUnit/SECONDS)
            (let [lines (str/split-lines (str/trim @out))]
              {:exit (.exitValue p) :value (last lines) :output @out})
            (do (.destroyForcibly p)
                {:exit :timeout :value nil :output nil}))))
      (finally
        (.delete argfile)))))

(defn- reads [expected {:keys [exit value output]} what]
  (is (= 0 exit) (str what ": the child JVM exited " exit "\n" output))
  (is (= expected value) (str what ": debug-enabled? read " (pr-str value)
                              ", expected " expected "\n" output)))

(deftest an-ssr-host-jvm-defaults-the-gate-off
  (testing "ssr-ring on the classpath and no setting: the gate reads false"
    (reads "false" (run-probe (classpath-entries) [] {})
           "ssr-ring, nothing set")))

(deftest an-ssr-host-jvm-opts-dev-in-explicitly
  (testing "`-Dre-frame.debug=true` turns it back on"
    (reads "true" (run-probe (classpath-entries) ["-Dre-frame.debug=true"] {})
           "ssr-ring, -Dre-frame.debug=true"))
  (testing "so does `RE_FRAME_DEBUG=true`"
    (reads "true" (run-probe (classpath-entries) [] {"RE_FRAME_DEBUG" "true"})
           "ssr-ring, RE_FRAME_DEBUG=true"))
  (testing "a value outside both vocabularies is no opt-in: the default stands"
    (reads "false" (run-probe (classpath-entries) ["-Dre-frame.debug=enabled"] {})
           "ssr-ring, -Dre-frame.debug=enabled")))

(deftest a-jvm-without-ssr-ring-defaults-the-gate-on
  (let [entries (without-ssr-ring (classpath-entries))]
    (testing "the probe really did take ssr-ring out"
      (is (< (count entries) (count (classpath-entries)))))
    (testing "nothing set: the gate reads true"
      (reads "true" (run-probe entries [] {}) "no ssr-ring, nothing set"))
    (testing "an explicit `-Dre-frame.debug=false` still turns it off"
      (reads "false" (run-probe entries ["-Dre-frame.debug=false"] {})
             "no ssr-ring, -Dre-frame.debug=false"))))

(deftest this-suite-runs-opted-in
  (testing "the `:test` alias's `-Dre-frame.debug=true` reached this JVM,
            so the suite's trace-reading tests ran in dev posture"
    (is (= "true" (System/getProperty "re-frame.debug"))
        "run ssr-ring's tests through `clojure -M:test`, which carries the opt-in")
    (is (true? rf.interop/debug-enabled?))))
