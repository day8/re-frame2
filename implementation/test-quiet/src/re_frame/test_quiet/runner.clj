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

  ## The discovery-banner contract

  cognitect-test-runner emits exactly one stdout artefact of its own —
  the `\\nRunning tests in #{...}` banner that `cognitect.test-runner/test`
  prints via bare `println` before kicking off `run-tests`.  We swallow
  it UNCONDITIONALLY: it lands in a throwaway buffer on both the green
  and the red path and is never forwarded.

  This is deliberate, not capture-and-replay.  We *cannot* replay it on
  red even if we wanted to: `cognitect.test-runner/-main` computes the
  fail/error counts and calls `(System/exit ...)` itself, in-process,
  before returning.  Control never comes back to this wrapper after the
  `apply` below — neither on green nor on red — so there is nothing to
  inspect and nothing to forward.  The banner is therefore gone on every
  path by construction.

  Losing the banner on red costs nothing: it names only the directory
  set (constant across runs, recoverable from deps.edn).  The diagnostic
  signal a failing run needs — the per-ns `Testing <ns>` banner, the
  `FAIL`/`ERROR` block, the summary — is emitted by the
  `re-frame.test-quiet` reporter via `clojure.test/with-test-out`, which
  writes to `clojure.test/*test-out*`.  That var is bound to the REAL
  `*out*` at top level and is untouched by the `*out*` rebinding below,
  so all of it reaches the real stdout regardless of the swap.  The
  contract is exercised by `re-frame.test-quiet-runner-contract-test`.

  Exit code is owned by cognitect-test-runner: 0 on green, 1 on any
  failure or error (and 1 on a CLI parse error).  This wrapper adds no
  exit logic and changes none of it."
  (:require
    [re-frame.test-quiet]
    [clojure.test]
    [cognitect.test-runner]))

(defn -main [& args]
  ;; Swallow cognitect-test-runner's "\nRunning tests in #{...}\n"
  ;; discovery line by binding `*out*` to a sink for the duration of the
  ;; delegated call.  The discovery line uses a bare `println` so it
  ;; lands in the sink; the reporter's failure output goes through
  ;; `with-test-out` (bound to the real `*out*`) and bypasses the swap.
  ;;
  ;; `cognitect.test-runner/-main` calls `System/exit` from the computed
  ;; fail/error counts, so control never returns past the `apply`: the
  ;; sink is discarded and nothing here forwards it. See the ns docstring
  ;; for why this is unconditional rather than capture-and-replay.
  (binding [*out* (java.io.PrintWriter.
                    (proxy [java.io.Writer] []
                      (write
                        ([s] nil)
                        ([s off len] nil))
                      (flush [] nil)
                      (close [] nil)))]
    (apply cognitect.test-runner/-main args)
    ;; Unreachable: -main exited the JVM above. Present only so a future
    ;; change that stops cognitect from calling System/exit fails loud
    ;; (returns nil, no accidental stdout) rather than leaking the sink.
    nil))
