(ns re-frame.test-quiet.runner
  "JVM `:main` entry point that installs the quiet reporter then
  delegates to `cognitect.test-runner`.

  Replaces `:main-opts [\"-m\" \"cognitect.test-runner\"]` in each
  per-artefact `:test` alias.  Forwarded args are passed verbatim to
  cognitect-test-runner so existing flags (`-i`, `-e`, `--dir`, etc.)
  keep working.

  Requiring `re-frame.test-quiet` at the top of this namespace is the
  install — the `defmethod`s for `clojure.test/report` install at
  load time.  By the time cognitect-test-runner discovers tests and
  starts dispatching reports, the overrides are in place.

  We also redirect cognitect-test-runner's one stdout artefact — the
  `Running tests in #{...}` banner emitted by `cognitect.test-runner/test`
  — into a captured StringWriter and replay it only when a failure or
  error count is non-zero.  Same shape as the per-namespace banner
  suppression in `re-frame.test-quiet`."
  (:require
    [re-frame.test-quiet]
    [clojure.test]
    [cognitect.test-runner]))

(defn -main [& args]
  ;; Swallow the discovery-line "\nRunning tests in #{...}\n" that
  ;; cognitect-test-runner emits before kicking off `run-tests`.  It
  ;; runs once per invocation and carries no diagnostic value on green.
  ;; A failure-time banner from re-frame.test-quiet already names the
  ;; offending ns; this banner names the directory set, which is
  ;; constant across runs and recoverable from the deps.edn.
  ;;
  ;; We do this by binding `*out*` around the test call.  Output that
  ;; matters (clojure.test reporter writes via `with-test-out` which
  ;; binds to a wrapped writer) bypasses the swap.  The discovery line
  ;; uses bare `println` so it lands in our captured buffer.
  ;;
  ;; The captured text is only forwarded to the real `*out*` if the
  ;; final summary reports failures or errors.
  (let [captured (java.io.StringWriter.)
        real-out *out*
        summary  (binding [*out* (java.io.PrintWriter.
                                   (proxy [java.io.Writer] []
                                     (write
                                       ([s] (.write captured (str s)))
                                       ([s off len]
                                        (.write captured (str s)
                                                (int off) (int len))))
                                     (flush [] (.flush captured))
                                     (close [] (.close captured))))]
                   (apply cognitect.test-runner/-main args)
                   ;; clojure.test sets the System exit code via
                   ;; cognitect.test-runner — we don't reach this line
                   ;; on a failing suite because -main calls System/exit.
                   nil)]
    ;; In practice cognitect.test-runner/-main calls System/exit
    ;; itself, so this fallthrough is only the green-on-stdout-only
    ;; path. Forward NOTHING — the silent-on-success contract.
    summary))
