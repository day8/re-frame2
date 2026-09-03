(ns re-frame.test-quiet.shadow-node
  "CLJS `:main` entry point for shadow-cljs's `:node-test` target.

  Mirrors `shadow.test.node/main` shape but installs the quiet
  reporter overrides on `cljs.test/report` before invoking the test
  runner.  Wired into `implementation/shadow-cljs.edn`'s `:node-test`
  build via `:main re-frame.test-quiet.shadow-node/main`.

  Requiring `re-frame.test-quiet` at the top is the install — the
  CLJS `defmethod`s install at load time.  By the time shadow's
  `run-all-tests` walks namespaces, the overrides are in place.

  Also installs a buffering `js/console.warn` stub so first-time
  runtime warnings (`reg-view non-DOM root`, `reagent-slim
  keyword-on-non-HTML-prop`, etc.) don't leak to test output on the
  success path but are not lost on a red run. The stub holds back
  warning calls in a bounded-entry ring (`warn-buffer`) instead of discarding
  it; the `:end-run-tests` reporter replays that buffer to stderr when
  the run fails or errors, so a failing CLJS run keeps the diagnostic
  context that may explain the failure.  Green runs stay quiet (the
  buffer is simply dropped at the green exit).

  Buffer scope: `console.warn` only.
  This stub captures `console.warn` and nothing else: `console.error` and
  direct `process.stderr.write` are NOT buffered and pass straight
  through.  That is deliberate — `console.warn` is the channel re-frame2's
  expected first-run warnings travel on, while a `console.error` is a real
  error worth surfacing immediately even on the green path.  This makes the
  CLJS scope narrower than the JVM runner (`re-frame.test-quiet.runner`),
  which buffers the test thread's `*err*` plus a process-global `System/err`
  bridge. The drop-on-green/replay-on-red policy is the same; only the
  captured scope differs.

  Tests that assert warning content all use the local
  `with-captured-console-warn` pattern — they save `(.-warn js/console)`,
  install a recording shim, run the body, then restore.  When our stub
  is in place, the saved value IS the stub; the recording shim still
  receives the calls; the restored value reverts to the stub.  Net
  effect: in-test capture works unchanged; out-of-test side-effect
  warnings are buffered (replayed on red, dropped on green)."
  {:dev/always true}
  (:require
    [clojure.string :as str]
    [re-frame.test-quiet]
    [re-frame.test-quiet.shadow-node-cli :as cli]
    [re-frame.test-quiet.warn-buffer :as wb]
    [shadow.test.env :as env]
    [shadow.test :as st]
    [cljs.test :as ct]))

;; Buffer stray runtime warnings instead of discarding them: quiet on
;; green, but replayed on red so a failing run keeps the diagnostic
;; context. See ns docstring for the capture-compatibility rationale.
;; Installed at ns-load time so it's in place before shadow's
;; `run-all-tests` walks the test ns set.

(defonce ^:private warn-buffer
  ;; A bounded ring of buffered warning arg-vectors. `defonce` so a
  ;; hot-reload of this `:dev/always` ns does not clobber warnings
  ;; already captured this run.
  (atom []))

(defn- buffer-warning!
  "Append `args` (one `console.warn` call's arguments) to the bounded
  ring via `warn-buffer/bound-conj`, which caps the ring to the newest
  `warn-buffer/warn-buffer-cap` entries and materialises the trimmed
  window into a fresh vector — so discarded warnings are not retained via
  a shared `subvec` backing."
  [warning-args]
  (swap! warn-buffer wb/bound-conj warning-args))

(defn- replay-buffered-warnings!
  "Replay the buffered warnings to stderr, prefixed so they are
  distinguishable from the test reporter's own stdout output.  Called
  from the `:end-run-tests` reporter ONLY on a red run, restoring the
  diagnostic context the green-path quieting withheld.

  Buffered argument values are rendered through `println`, not replayed
  through native `console.warn`; their formatting semantics therefore differ.

  The replay writes synchronously to fd 2 via
  `fs.writeSync`, NOT `js/process.stderr.write`.  On POSIX a pipe-backed
  `process.stderr` is ASYNCHRONOUS, and the `:end-run-tests` reporter calls
  `js/process.exit 1` IMMEDIATELY after this returns — `process.exit` forces
  the process to exit with pending async stdout/stderr I/O still queued, so a
  large red replay (up to `warn-buffer-cap` = 256 arbitrarily-sized entries)
  could be TRUNCATED before it reached captured CI output, dropping the tail
  of the diagnostic context the ns docstring promises a red run keeps.
  `fs.writeSync` blocks until the bytes reach the fd, so the exit cannot drop
  them. This matches the JVM runner's blocking stderr replay."
  []
  (let [buffered-warnings @warn-buffer]
    (when (seq buffered-warnings)
      (let [fs (js/require "fs")]
        (binding [*print-fn* (fn [s] (.writeSync fs 2 s))]
          (println (str "[test-quiet] " (count buffered-warnings)
                        " console.warn message(s) buffered during this run"
                        " (replayed because the run was RED):"))
          (doseq [warning-args buffered-warnings]
            (apply println "[test-quiet] console.warn:" warning-args)))))))

;; The stub carries an `rf-test-quiet-silenced` marker property so a
;; contract test can assert the live `console.warn` is
;; this stub (and so fail if a regression leaves native `console.warn` —
;; which has no such marker — in place), without requiring this
;; `:dev/always` ns (which would form a compile cycle).
(when (and (exists? js/console)
           (fn? (.-warn js/console)))
  ;; `nil` is the local stub contract. Native `console.warn` returns
  ;; JavaScript `undefined`; callers must not use either return value.
  (let [stub (fn [& warning-args]
               (buffer-warning! (vec warning-args))
               nil)]
    (set! (.-rf-test-quiet-silenced stub) true)
    (set! (.-warn js/console) stub)))

;; ----------------------------------------------------------------------
;; The single override shadow.test.node ships — exit the node process
;; with the appropriate code.  Stays here because removing it would
;; break CI's pass/fail signal.  On a RED run we first replay any
;; buffered `console.warn` diagnostics (the green-path stub withheld
;; them) so a failing run keeps the context that may explain it.
;;
;; Exit-code integrity: this defmethod is the only thing that
;; turns a RED cljs.test summary into a non-zero node exit, so two hazards
;; must never let a failing run exit green:
;;   1. Red replay is diagnostic-only. It is wrapped so a replay exception
;;      cannot prevent the nonzero exit.
;;   2. Reaching a GREEN exit is the ONLY way `main` clears the seeded
;;      failure exit code (`execute-cli` sets `process.exitCode = 1` before
;;      running). So a run that executes tests but never dispatches THIS
;;      defmethod — a torn-down async run, or a reporter whose env does not
;;      match the `[:cljs.test/default …]` dispatch key — leaves the process
;;      to drain its event loop and exit with the seeded 1, failing loudly
;;      instead of a silent false-green.

(defmethod ct/report [:cljs.test/default :end-run-tests] [summary]
  (if (ct/successful? summary)
    (js/process.exit 0)
    (do (try
          (replay-buffered-warnings!)
          (catch :default _
            ;; Diagnostic-only: a throw here must never mask the red exit.
            nil))
        (js/process.exit 1))))

(defn- seed-failure-exit!
  "Seed the node process's exit code to failure just before a test run
  begins. The only path that clears it back to 0 is a confirmed green
  `:end-run-tests` (which `js/process.exit 0`s — see that defmethod's
  EXIT-CODE INTEGRITY note).  So a run that starts tests but never reaches a
  green summary — a torn-down async run, or a reporter env that never
  dispatches our exit defmethod — drains the event loop and exits with this
  seeded 1 rather than a silent false-green. It is a no-op on
  the normal green/red paths, where the `:end-run-tests` defmethod calls
  `js/process.exit` and overrides it."
  []
  (set! (.-exitCode js/process) 1))

;; ----------------------------------------------------------------------
;; Test-data reset (mirrors shadow.test.node/reset-test-data!).
;;
;; `:dev/always true` on the ns is required: `env/get-test-data` is a
;; macro that expands at compile time and must re-resolve every cycle.

(defn ^:dev/after-load reset-test-data! []
  (-> (env/get-test-data)
      (env/reset-test-data!)))

;; ----------------------------------------------------------------------
;; CLI arg parsing lives in `re-frame.test-quiet.shadow-node-cli` so it
;; can be unit-pinned without a compile cycle (this ns is `:dev/always`
;; + expands the `env/get-test-data` test-ns-enumeration macro).

(defn find-matching-test-vars [test-selectors]
  (let [test-namespaces  (->> test-selectors (filter simple-symbol?) set)
        test-var-symbols (->> test-selectors (filter qualified-symbol?) set)]
    (->> (env/get-test-vars)
         (filter (fn [test-var]
                   (let [{test-name :name test-namespace :ns} (meta test-var)]
                     (or (contains? test-namespaces test-namespace)
                         (contains? test-var-symbols
                                    (symbol test-namespace test-name)))))))))

(def ^:private min-tests-env-var
  "Environment variable naming this lane's test-count floor. ONE name across
  every whole-suite lane — the JVM runner (`re-frame.test-quiet.runner`) and
  the browser runner (`implementation/scripts/run-browser-tests.cjs`) read
  the same variable. Because it is one name, scope it to the lane you are
  running: a value calibrated for this ~380-file build will red a focused
  build such as `:node-test-security`."
  "RF2_MIN_TESTS")

(defn- resolve-min-tests
  "`cli/parse-min-tests` over the live `process.env`, or `::cli/invalid`."
  []
  (cli/parse-min-tests
    (when (exists? js/process)
      (unchecked-get js/process.env min-tests-env-var))))

(defn- reject-below-test-floor!
  "The whole-suite counterpart to the `--test=` unmatched-selector guard: a
  build whose `:ns-regexp` selected fewer than `min-tests` test vars must not
  report a 0-test success. Returns true (and prints + exits nonzero) when the
  run must be refused, false when it may proceed.

  This is checked BEFORE `run-all-tests` rather than from the
  `:end-run-tests` reporter, because the count is already known and a
  configuration error should not first pretend to run a suite. Exit 2 marks a
  malformed floor (configuration), 1 an under-floor lane (red)."
  [min-tests discovered-test-count]
  (cond
    (= ::cli/invalid min-tests)
    (do (println (str "ERROR: " min-tests-env-var " is not a non-negative"
                      " integer."))
        (println (str "  " min-tests-env-var " is the minimum number of tests"
                      " this build must run; leave it unset for the default"
                      " of " cli/default-min-tests "."))
        (js/process.exit 2)
        true)

    (< discovered-test-count min-tests)
    (do (println (str "ERROR: this build discovered " discovered-test-count
                      " test var(s), below the floor of " min-tests
                      " (" min-tests-env-var ")."))
        (println (str "  A build whose :ns-regexp selects no tests is a"
                      " configuration error, not a pass: a renamed test file,"
                      " a one-character suffix drift, or a dropped"
                      " :source-paths entry empties a lane silently"
                      " (rf2-qqzmf)."))
        (js/process.exit 1)
        true)

    :else false))

(defn execute-cli
  [{:keys [help list unknown-args] test-selectors :test-syms :as _cli-options}]
  ;; Unknown args are a fatal parse error. The pure
  ;; `cli/parse-args` collects them into `:unknown-args` (without printing,
  ;; so it can be unit-pinned without leaking a non-summary line on a green
  ;; run). Reject unknown args here: print them and `js/process.exit`
  ;; nonzero before running anything. This check runs ahead of
  ;; `--help`/`--list`, so those stay clean successful
  ;; non-test commands ONLY when no unknown arg accompanies them.
  (when (seq unknown-args)
    (doseq [arg unknown-args]
      (println (str "Unknown arg: " arg)))
    (println "Use --help to see known options.")
    (js/process.exit 1))
  (let [test-environment (ct/empty-env)]
    (cond
      help
      (do (println "Usage:")
          (println "  --list (list known test names)")
          (println "  --test=<ns-to-test>,<fqn-symbol-to-test> (run test for namespace or single var, separated by comma)"))

      list
      (doseq [[test-namespace namespace-info]
              (->> (env/get-tests) (sort-by first))]
        (println "Namespace:" test-namespace)
        (doseq [test-var (:vars namespace-info)
                :let [test-var-meta (meta test-var)]]
          (println (str "  " (:ns test-var-meta) "/" (:name test-var-meta))))
        (println "---------------------------------"))

      (seq test-selectors)
      (let [matched-test-vars    (find-matching-test-vars test-selectors)
            unmatched-selectors (cli/unmatched-selectors test-selectors
                                                         matched-test-vars)]
        ;; A `--test=` selection that matches no test var must not be a
        ;; false green: `run-test-vars` over an empty set reports a 0-test
        ;; success. Reject any unmatched selector: print
        ;; the offenders and exit nonzero before running anything.
        (if (seq unmatched-selectors)
          (do
            (println "ERROR: no tests matched --test= selector(s):"
                     (str/join ", " (map str unmatched-selectors)))
            (println "Use --list to see known test names.")
            (js/process.exit 1))
          (do (seed-failure-exit!)
              (st/run-test-vars test-environment matched-test-vars))))

      :else
      ;; Whole-suite path. `run-all-tests` over an empty test-var set reports
      ;; a 0-test success exactly as `run-test-vars` does, so it gets the same
      ;; guard the `--test=` branch above has had all along (rf2-qqzmf).
      (when-not (reject-below-test-floor! (resolve-min-tests)
                                          (count (env/get-test-vars)))
        (seed-failure-exit!)
        (st/run-all-tests test-environment nil)))))

(defn main [& args]
  (reset-test-data!)
  (if env/UI-DRIVEN
    (js/console.log "Waiting for UI ...")
    (let [cli-options (cli/parse-args args)]
      (execute-cli cli-options))))
