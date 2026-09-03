(ns re-frame.test-quiet-runner-lifecycle-test
  "Lifecycle pins for `re-frame.test-quiet.runner/-main`'s reporter + shutdown-
  hook restoration on RETURNING and THROWING invocations (rf2-j538f7.17).

  `-main` installs an invocation-scoped `clojure.test/report :summary` method
  (the red-run stderr replay), swaps `System.err` to a bounded ring, and
  registers a stdout flush-on-exit shutdown hook.  On the standalone test path
  `cognitect.test-runner` `System/exit`s, so none of that needs unwinding — the
  process dies.  But `-H`/help intentionally RETURNS, and embedded/REPL/in-
  process callers can return OR throw.  On those paths every one of those
  global mutations must be undone, or an unrelated later `clojure.test` run
  inherits this run's ring/writer, repeated calls chain wrapper-over-wrapper,
  and flush hooks accumulate until JVM shutdown.

  These can only be observed by RUNNING `-main` and then inspecting the JVM's
  reporter/`System.err`/hook state AFTER it returns.  A standalone
  `System/exit` erases that state across a process boundary, and running the
  probe in THIS test JVM (itself driven by the quiet runner) would be
  re-entrant, so each pin is a self-checking Clojure program run in a fresh
  JVM (`clojure.main <file>`) that exits 0 iff its lifecycle invariant holds.
  Each program stubs `cognitect.test-runner/-main` so it RETURNS/THROWS
  instead of exiting, reproducing the returning lifecycle deterministically."
  (:require [clojure.test :refer [deftest is testing]]
            [clojure.string :as str]
            [clojure.java.io :as io]))

;; ----------------------------------------------------------------------
;; Fresh-JVM program harness.

(defn- drain-process
  "Drain `process`'s stdout + stderr concurrently, then `waitFor` under a
  timeout.  Returns {:exit :out :err :timed-out?}.  Concurrent drain avoids a
  pipe deadlock; the timeout force-kills a wedged child so a regression fails
  fast instead of hanging the suite."
  [^Process process timeout-ms]
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
        (.destroyForcibly process)
        (.waitFor process 5000 java.util.concurrent.TimeUnit/MILLISECONDS)
        (future-cancel stdout-future)
        (future-cancel stderr-future)
        {:exit -1
         :out (try @stdout-future (catch Throwable _ ""))
         :err (try @stderr-future (catch Throwable _ ""))
         :timed-out? true}))))

(defn- run-lifecycle-program
  "Spawn a fresh JVM on THIS test's classpath running `clojure.main` over
  `program-source` (spat to a temp file). Reusing `java.class.path` keeps the child on
  the same classpath (runner src + cognitect + clojure) without re-resolving
  deps or shelling the `clojure` launcher (dodging Windows `-Sdeps` escaping)."
  [program-source]
  (let [program-file    (java.io.File/createTempFile "tq-lifecycle" ".clj")
        windows?        (str/includes?
                          (str/lower-case (System/getProperty "os.name"))
                          "win")
        java-executable (str (io/file (System/getProperty "java.home") "bin"
                                      (if windows? "java.exe" "java")))
        command         [java-executable "-cp"
                         (System/getProperty "java.class.path")
                         "clojure.main" (.getAbsolutePath program-file)]]
    (spit program-file program-source)
    (try
      (drain-process (-> (ProcessBuilder. ^java.util.List command)
                         (.redirectErrorStream false)
                         (.start))
                     60000)
      (finally (.delete program-file)))))

(def ^:private program-preamble
  (str "(require '[re-frame.test-quiet.runner :as r]\n"
       "         '[clojure.test :as t]\n"
       "         '[clojure.string :as s]\n"
       "         '[cognitect.test-runner :as ctr])\n"))

;; ----------------------------------------------------------------------
;; A returning invocation restores the summary reporter, and a later run
;; still emits its summary (criteria 1, 2).
;;
;; The core defect: `install-summary-replay-hook!` installed a global
;; `:summary` defmethod closing over this run's ring + original-err and NEVER
;; restored it.  After a returning `-main` (help/embedded), an unrelated
;; `clojure.test` summary saw the stale ring and replayed a previous run's
;; stderr, and the reporter method was permanently the wrapper.  The fix makes
;; the method invocation-scoped, restored in `-main`'s `finally`.

(def ^:private returning-restores-summary-program
  (str program-preamble
       "(let [stderr (java.io.StringWriter.)\n"
       "      testout (java.io.StringWriter.)\n"
       "      initial (get-method t/report :summary)]\n"
       "  (binding [*err* (java.io.PrintWriter. stderr)\n"
       "            t/*test-out* (java.io.PrintWriter. testout)]\n"
       "    (with-redefs [ctr/-main (fn [& _]\n"
       "                              (.println ^java.io.PrintWriter *err* \"STALE-FROM-RETURNED-RUN\"))]\n"
       "      (r/-main \"-H\"))\n"
       "    (let [restored? (identical? initial (get-method t/report :summary))]\n"
       "      (t/report {:type :summary :test 1 :pass 0 :fail 1 :error 0})\n"
       "      (.flush ^java.io.PrintWriter *err*)\n"
       "      (let [summary-out (str testout)\n"
       "            later-err (str stderr)\n"
       "            stale? (s/includes? later-err \"STALE-FROM-RETURNED-RUN\")\n"
       "            replay? (s/includes? later-err \"buffered stderr replayed\")\n"
       "            emitted? (s/includes? summary-out \"1 failures\")]\n"
       "        (println \"RESTORED?\" restored?)\n"
       "        (println \"STALE-LEAKED?\" stale?)\n"
       "        (println \"REPLAY-LEAKED?\" replay?)\n"
       "        (println \"SUMMARY-EMITTED?\" emitted?)\n"
       "        (flush)\n"
       "        (if (and restored? (not stale?) (not replay?) emitted?)\n"
       "          (do (println \"LIFECYCLE-OK\") (flush) (System/exit 0))\n"
       "          (do (println \"LIFECYCLE-FAIL\") (flush) (System/exit 1)))))))\n"))

(deftest returning-invocation-restores-summary-reporter
  (testing "a returning -main restores the prior :summary; a later run still emits its summary with no stale replay"
    (let [{:keys [exit out err timed-out?]}
          (run-lifecycle-program returning-restores-summary-program)]
      (is (not timed-out?) "the probe must terminate, not hang")
      (is (zero? exit)
          (str "the returning invocation must restore the reporter (RESTORED? true),"
               " leak no earlier stderr into a later summary (STALE/REPLAY false),"
               " and the later summary must still print (SUMMARY-EMITTED? true)."
               "\n--- stdout ---\n" out "\n--- stderr ---\n" err))
      (is (str/includes? out "LIFECYCLE-OK")
          (str "expected the LIFECYCLE-OK marker; got:\n" out)))))

;; ----------------------------------------------------------------------
;; Two returning invocations do not chain summary methods (criterion 3).
;;
;; The pre-fix code wrapped the already-wrapped method on each call, so a
;; later red summary walked the whole chain and replayed EVERY invocation's
;; ring (two returning runs -> two replay blocks).  Invocation scoping means
;; each run restores the prior method, so no chain accumulates.

(def ^:private no-chain-program
  (str program-preamble
       "(let [stderr (java.io.StringWriter.)\n"
       "      testout (java.io.StringWriter.)\n"
       "      initial (get-method t/report :summary)]\n"
       "  (binding [*err* (java.io.PrintWriter. stderr)\n"
       "            t/*test-out* (java.io.PrintWriter. testout)]\n"
       "    (with-redefs [ctr/-main (fn [& _]\n"
       "                              (.println ^java.io.PrintWriter *err* \"STALE-RUN\"))]\n"
       "      (r/-main \"-H\")\n"
       "      (r/-main \"-H\"))\n"
       "    (let [restored? (identical? initial (get-method t/report :summary))]\n"
       "      (t/report {:type :summary :test 1 :pass 0 :fail 1 :error 0})\n"
       "      (.flush ^java.io.PrintWriter *err*)\n"
       "      (let [later-err (str stderr)\n"
       "            replay-blocks (count (re-seq #\"buffered stderr replayed\" later-err))]\n"
       "        (println \"RESTORED?\" restored?)\n"
       "        (println \"REPLAY-BLOCKS\" replay-blocks)\n"
       "        (flush)\n"
       "        (if (and restored? (zero? replay-blocks))\n"
       "          (do (println \"NO-CHAIN-OK\") (flush) (System/exit 0))\n"
       "          (do (println \"NO-CHAIN-FAIL\") (flush) (System/exit 1)))))))\n"))

(deftest two-returning-invocations-do-not-chain-summary-methods
  (testing "sequential returning invocations restore the reporter each time — no wrapper chain, no accumulated replay"
    (let [{:keys [exit out err timed-out?]}
          (run-lifecycle-program no-chain-program)]
      (is (not timed-out?) "the probe must terminate, not hang")
      (is (zero? exit)
          (str "two returning invocations must not chain reporters (RESTORED? true,"
               " REPLAY-BLOCKS 0); the pre-fix code accumulated one replay block per"
               " invocation.\n--- stdout ---\n" out "\n--- stderr ---\n" err))
      (is (str/includes? out "NO-CHAIN-OK")
          (str "expected the NO-CHAIN-OK marker; got:\n" out)))))

;; ----------------------------------------------------------------------
;; A throwing delegate restores state and propagates (criterion 4).
;;
;; `-main`'s `finally` fires on the throwing path too: `System.err` and the
;; prior `:summary` method are restored, and the original exception propagates
;; unchanged.

(def ^:private throwing-restores-program
  (str program-preamble
       "(let [initial (get-method t/report :summary)\n"
       "      original-system-err System/err\n"
       "      threw? (atom false)]\n"
       "  (with-redefs [ctr/-main (fn [& _]\n"
       "                            (.println ^java.io.PrintWriter *err* \"ERR-CHANNEL\")\n"
       "                            (.println System/err \"RAW-CHANNEL\")\n"
       "                            (throw (ex-info \"BOOM-DELEGATE\" {})))]\n"
       "    (try\n"
       "      (r/-main)\n"
       "      (catch Throwable e\n"
       "        (when (s/includes? (str (.getMessage e)) \"BOOM-DELEGATE\")\n"
       "          (reset! threw? true)))))\n"
       "  (let [restored? (identical? initial (get-method t/report :summary))\n"
       "        system-err-restored? (identical? original-system-err System/err)]\n"
       "    (println \"PROPAGATED?\" @threw?)\n"
       "    (println \"RESTORED?\" restored?)\n"
       "    (println \"SYSERR-RESTORED?\" system-err-restored?)\n"
       "    (flush)\n"
       "    (if (and @threw? restored? system-err-restored?)\n"
       "      (do (println \"THROW-OK\") (flush) (System/exit 0))\n"
       "      (do (println \"THROW-FAIL\") (flush) (System/exit 1)))))\n"))

(deftest throwing-invocation-restores-state-and-propagates
  (testing "a throwing delegate restores System.err + the prior :summary in finally, and the exception propagates"
    (let [{:keys [exit out err timed-out?]}
          (run-lifecycle-program throwing-restores-program)]
      (is (not timed-out?) "the probe must terminate, not hang")
      (is (zero? exit)
          (str "a throwing delegate must propagate (PROPAGATED? true) AND restore"
               " both the reporter (RESTORED? true) and System.err (SYSERR-RESTORED?"
               " true) in finally.\n--- stdout ---\n" out "\n--- stderr ---\n" err))
      (is (str/includes? out "THROW-OK")
          (str "expected the THROW-OK marker; got:\n" out)))))

;; ----------------------------------------------------------------------
;; A pre-existing custom :summary method is preserved (criterion 5).
;;
;; A third party may install its own `:summary` reporter before invoking the
;; runner.  `-main` must DELEGATE to it during the run (exactly once per
;; summary) and RESTORE it on return — never leave its own wrapper installed
;; in its place.

(def ^:private custom-summary-preserved-program
  (str program-preamble
       "(let [calls (atom 0)\n"
       "      custom (fn [_m] (swap! calls inc))]\n"
       "  (.addMethod ^clojure.lang.MultiFn clojure.test/report :summary custom)\n"
       "  (with-redefs [ctr/-main (fn [& _]\n"
       "                            (t/report {:type :summary :test 1 :pass 1 :fail 0 :error 0}))]\n"
       "    (r/-main \"-H\"))\n"
       "  (let [installed-after (get-method t/report :summary)\n"
       "        restored? (identical? custom installed-after)]\n"
       "    (println \"CUSTOM-CALLS\" @calls)\n"
       "    (println \"CUSTOM-RESTORED?\" restored?)\n"
       "    (flush)\n"
       "    (if (and (= 1 @calls) restored?)\n"
       "      (do (println \"CUSTOM-OK\") (flush) (System/exit 0))\n"
       "      (do (println \"CUSTOM-FAIL\") (flush) (System/exit 1)))))\n"))

(deftest returning-invocation-preserves-custom-summary-method
  (testing "a pre-existing custom :summary is delegated exactly once during the run and re-installed afterward"
    (let [{:keys [exit out err timed-out?]}
          (run-lifecycle-program custom-summary-preserved-program)]
      (is (not timed-out?) "the probe must terminate, not hang")
      (is (zero? exit)
          (str "a pre-existing custom :summary must be delegated exactly once"
               " (CUSTOM-CALLS 1) and re-installed after the returning invocation"
               " (CUSTOM-RESTORED? true).\n--- stdout ---\n" out "\n--- stderr ---\n" err))
      (is (str/includes? out "CUSTOM-OK")
          (str "expected the CUSTOM-OK marker; got:\n" out)))))

;; ----------------------------------------------------------------------
;; Returning invocations do not accumulate shutdown hooks (criterion 6).
;;
;; `-main` registers a stdout flush-on-exit hook through the
;; `*register-flush-hook!*` seam and deregisters it on every returning/throwing
;; path.  Binding the seam to a counter proves repeated help invocations net
;; zero registered hooks — without the removal, each returning call would leave
;; a filtering-writer hook registered until JVM shutdown.

(def ^:private hook-lifecycle-program
  (str program-preamble
       "(let [registered (atom 0)]\n"
       "  (binding [r/*register-flush-hook!*\n"
       "            (fn [_hook]\n"
       "              (swap! registered inc)\n"
       "              (fn [] (swap! registered dec)))]\n"
       "    (with-redefs [ctr/-main (fn [& _] nil)]\n"
       "      (dotimes [_ 5] (r/-main \"-H\"))))\n"
       "  (println \"NET-HOOKS\" @registered)\n"
       "  (flush)\n"
       "  (if (zero? @registered)\n"
       "    (do (println \"HOOK-OK\") (flush) (System/exit 0))\n"
       "    (do (println \"HOOK-FAIL\") (flush) (System/exit 1))))\n"))

(deftest returning-invocations-do-not-accumulate-shutdown-hooks
  (testing "each returning -main deregisters its flush hook — five help calls net zero registered hooks"
    (let [{:keys [exit out err timed-out?]}
          (run-lifecycle-program hook-lifecycle-program)]
      (is (not timed-out?) "the probe must terminate, not hang")
      (is (zero? exit)
          (str "repeated returning invocations must net zero registered flush hooks"
               " (NET-HOOKS 0) — the hook must be removed on every returning path."
               "\n--- stdout ---\n" out "\n--- stderr ---\n" err))
      (is (str/includes? out "HOOK-OK")
          (str "expected the HOOK-OK marker; got:\n" out)))))
