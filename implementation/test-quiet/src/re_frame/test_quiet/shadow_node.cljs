(ns re-frame.test-quiet.shadow-node
  "CLJS `:main` entry point for shadow-cljs's `:node-test` target.

  Mirrors `shadow.test.node/main` shape but installs the quiet
  reporter overrides on `cljs.test/report` before invoking the test
  runner.  Wired into `implementation/shadow-cljs.edn`'s `:node-test`
  build via `:main re-frame.test-quiet.shadow-node/main`.

  Requiring `re-frame.test-quiet` at the top is the install — the
  CLJS `defmethod`s install at load time.  By the time shadow's
  `run-all-tests` walks namespaces, the overrides are in place.

  Also installs a BUFFERING `js/console.warn` stub so first-time
  runtime warnings (`reg-view non-DOM root`, `reagent-slim
  keyword-on-non-HTML-prop`, etc.) don't leak to test stdout on the
  SUCCESS path — but are NOT lost on a RED run.  The stub holds back
  warning text in a bounded ring (`warn-buffer`) instead of discarding
  it; the `:end-run-tests` reporter replays that buffer to stderr when
  the run fails or errors, so a failing CLJS run keeps the diagnostic
  context that may explain the failure.  Green runs stay quiet (the
  buffer is simply dropped at the green exit).

  BUFFER SCOPE — `console.warn` ONLY (and the asymmetry with the JVM).
  This stub captures `console.warn` and nothing else: `console.error` and
  direct `process.stderr.write` are NOT buffered and pass straight
  through.  That is deliberate — `console.warn` is the channel re-frame2's
  expected first-run warnings travel on, while a `console.error` is a real
  error worth surfacing immediately even on the green path.  This makes the
  CLJS scope NARROWER than the JVM runner
  (`re-frame.test-quiet.runner`), which buffers the WHOLE stderr side
  (`*err*` + a `System/err` bridge).  The buffer/drop-on-green/
  replay-on-red POLICY is the same on both runtimes; only the captured
  scope differs (rf2-22s58s / rf2-nrk066).

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
  `warn-buffer/warn-buffer-cap` entries and MATERIALISES the trimmed
  window into a fresh vector — so discarded warnings are not retained via
  a shared `subvec` backing (rf2-6emzlh)."
  [args]
  (swap! warn-buffer wb/bound-conj args))

(defn- replay-buffered-warnings!
  "Replay the buffered warnings to stderr, prefixed so they are
  distinguishable from the test reporter's own stdout output.  Called
  from the `:end-run-tests` reporter ONLY on a red run, restoring the
  diagnostic context the green-path quieting withheld.

  Each buffered arg is replayed VERBATIM.

  SYNCHRONOUS WRITE (rf2-4co7oo). The replay writes to fd 2 via
  `fs.writeSync`, NOT `js/process.stderr.write`.  On POSIX a pipe-backed
  `process.stderr` is ASYNCHRONOUS, and the `:end-run-tests` reporter calls
  `js/process.exit 1` IMMEDIATELY after this returns — `process.exit` forces
  the process to exit with pending async stdout/stderr I/O still queued, so a
  large red replay (up to `warn-buffer-cap` = 256 arbitrarily-sized entries)
  could be TRUNCATED before it reached captured CI output, dropping the tail
  of the diagnostic context the ns docstring promises a red run keeps.
  `fs.writeSync` blocks until the bytes reach the fd, so the exit cannot drop
  them.  This matches the JVM runner, whose blocking `PrintWriter` over
  `System.err` is likewise synchronous (Windows dev-box pipe writes are
  synchronous too, which is why the bug is CI-only)."
  []
  (let [buffered @warn-buffer]
    (when (seq buffered)
      (let [fs (js/require "fs")]
        (binding [*print-fn* (fn [s] (.writeSync fs 2 s))]
          (println (str "[test-quiet] " (count buffered)
                        " console.warn message(s) buffered during this run"
                        " (replayed because the run was RED):"))
          (doseq [args buffered]
            (apply println "[test-quiet] console.warn:" args)))))))

;; The stub carries a `re-frame.test-quiet/silenced` marker property so it
;; is IDENTIFIABLE: a contract test can assert the live `console.warn` is
;; this stub (and so fail if a regression leaves native `console.warn` —
;; which has no such marker — in place), without requiring this
;; `:dev/always` ns (which would form a compile cycle).
(when (and (exists? js/console)
           (fn? (.-warn js/console)))
  ;; Return `nil` (like native `console.warn`) so callers that observe the
  ;; return value see no behavioural change from the buffering.
  (let [stub (fn [& args] (buffer-warning! (vec args)) nil)]
    (set! (.-rf-test-quiet-silenced stub) true)
    (set! (.-warn js/console) stub)))

;; ----------------------------------------------------------------------
;; The single override shadow.test.node ships — exit the node process
;; with the appropriate code.  Stays here because removing it would
;; break CI's pass/fail signal.  On a RED run we first replay any
;; buffered `console.warn` diagnostics (the green-path stub withheld
;; them) so a failing run keeps the context that may explain it.
;;
;; EXIT-CODE INTEGRITY (rf2-jmrdhn). This defmethod is the ONLY thing that
;; turns a RED cljs.test summary into a non-zero node exit, so two hazards
;; must never let a failing run go out the door GREEN:
;;   1. The red replay is DIAGNOSTIC-ONLY. If `replay-buffered-warnings!`
;;      threw, the old `(do (replay…) (js/process.exit 1))` never reached
;;      the `exit 1` — the throw propagated out of the reporter and node
;;      could unwind to a 0 exit. The replay is wrapped so a throw in it can
;;      never SWALLOW the red exit.
;;   2. Reaching a GREEN exit is the ONLY way `main` clears the seeded
;;      failure exit code (`execute-cli` sets `process.exitCode = 1` before
;;      running). So a run that executes tests but never dispatches THIS
;;      defmethod — a torn-down async run, or a reporter whose env does not
;;      match the `[:cljs.test/default …]` dispatch key — leaves the process
;;      to drain its event loop and exit with the seeded 1, failing loudly
;;      instead of a silent false-green.

(defmethod ct/report [:cljs.test/default :end-run-tests] [m]
  (if (ct/successful? m)
    (js/process.exit 0)
    (do (try
          (replay-buffered-warnings!)
          (catch :default _
            ;; Diagnostic-only: a throw here must never mask the red exit.
            nil))
        (js/process.exit 1))))

(defn- seed-failure-exit!
  "Seed the node process's exit code to FAILURE just before a test run
  begins.  The ONLY path that clears it back to 0 is a confirmed GREEN
  `:end-run-tests` (which `js/process.exit 0`s — see that defmethod's
  EXIT-CODE INTEGRITY note).  So a run that starts tests but never reaches a
  green summary — a torn-down async run, or a reporter env that never
  dispatches our exit defmethod — drains the event loop and exits with this
  seeded 1 rather than a silent false-green (rf2-jmrdhn).  It is a no-op on
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

(defn find-matching-test-vars [test-syms]
  (let [test-namespaces (->> test-syms (filter simple-symbol?) (set))
        test-var-syms   (->> test-syms (filter qualified-symbol?) (set))]
    (->> (env/get-test-vars)
         (filter (fn [the-var]
                   (let [{:keys [name ns]} (meta the-var)]
                     (or (contains? test-namespaces ns)
                         (contains? test-var-syms (symbol ns name)))))))))

(defn execute-cli [{:keys [test-syms help list unknown-args] :as _opts}]
  ;; Unknown args are a FATAL parse error (rf2-14nojy.1): the pure
  ;; `cli/parse-args` collects them into `:unknown-args` (without printing,
  ;; so it can be unit-pinned without leaking a non-summary line on a green
  ;; run; rf2-spzkgo). Pre-fix `execute-cli` merely PRINTED them and then
  ;; fell through — so a mistyped focused-run such as the space-separated
  ;; `--test missing.ns` (both tokens unknown, no `--test=` selector) or a
  ;; misspelled flag like `--tests=foo` ran NO requested tests, dropped into
  ;; the `:else` `run-all-tests` branch, and exited GREEN if the full suite
  ;; was green — a focused-run false green of the same class the
  ;; `--test=<missing>` guard (rf2-lbo79.1) closes. Reject unknown args here:
  ;; print them and `js/process.exit` NONZERO before running anything. This
  ;; runs ahead of `--help`/`--list`, so those stay clean successful
  ;; non-test commands ONLY when no unknown arg accompanies them.
  (when (seq unknown-args)
    (doseq [arg unknown-args]
      (println (str "Unknown arg: " arg)))
    (println "Use --help to see known options.")
    (js/process.exit 1))
  (let [test-env (ct/empty-env)]
    (cond
      help
      (do (println "Usage:")
          (println "  --list (list known test names)")
          (println "  --test=<ns-to-test>,<fqn-symbol-to-test> (run test for namespace or single var, separated by comma)"))

      list
      (doseq [[ns ns-info] (->> (env/get-tests) (sort-by first))]
        (println "Namespace:" ns)
        (doseq [var (:vars ns-info)
                :let [m (meta var)]]
          (println (str "  " (:ns m) "/" (:name m))))
        (println "---------------------------------"))

      (seq test-syms)
      (let [test-vars (find-matching-test-vars test-syms)
            unmatched (cli/unmatched-selectors test-syms test-vars)]
        ;; A `--test=` selection that matches NO test var must NOT be a
        ;; false green: shadow's `run-test-vars` over an empty set reports
        ;; a 0-test SUCCESS and the `:end-run-tests` defmethod exits 0, so
        ;; a typo in `--test=<selector>` would silently "pass" with zero
        ;; tests run (rf2-lbo79.1).  Reject any unmatched selector — print
        ;; the offenders and exit nonzero before running anything.
        (if (seq unmatched)
          (do
            (println "ERROR: no tests matched --test= selector(s):"
                     (str/join ", " (map str unmatched)))
            (println "Use --list to see known test names.")
            (js/process.exit 1))
          (do (seed-failure-exit!)
              (st/run-test-vars test-env test-vars))))

      :else
      (do (seed-failure-exit!)
          (st/run-all-tests test-env nil)))))

(defn main [& args]
  (reset-test-data!)
  (if env/UI-DRIVEN
    (js/console.log "Waiting for UI ...")
    (let [opts (cli/parse-args args)]
      (execute-cli opts))))
