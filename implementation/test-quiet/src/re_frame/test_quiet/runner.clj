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

  cognitect-test-runner emits exactly one stdout artefact of its own that
  we want gone — the `\\nRunning tests in #{...}` banner that
  `cognitect.test-runner/test` prints via bare `println` before kicking
  off `run-tests`.  We swallow ONLY that line, by binding `*out*` to a
  *filtering* writer that drops the discovery banner and passes every
  other byte straight through to the real stdout.

  This is a line-precise filter, NOT a global sink.  An earlier version
  sank `*out*` for the entire delegated call, which silenced far more
  than the banner: `-H`/`--test-help` usage text, CLI parse-error
  diagnostics, and any bare `(println ...)` a test or fixture emits all
  vanished — runner misuse went opaque and failure-time diagnostic stdout
  was lost (rf2-lbo79.2).  cognitect's `help` and parse-error paths print
  via the same `*out*` as the banner, so a blanket sink could not tell
  them apart; the filter keys on the banner's exact text instead, so
  those paths reach stdout while the banner stays quiet.

  Losing the banner on red costs nothing: it names only the directory
  set (constant across runs, recoverable from deps.edn).  The diagnostic
  signal a failing run needs — the per-ns `Testing <ns>` banner, the
  `FAIL`/`ERROR` block, the summary — is emitted by the
  `re-frame.test-quiet` reporter via `clojure.test/with-test-out`, which
  writes to `clojure.test/*test-out*`.  That var is bound to the REAL
  `*out*` at top level and is untouched by the rebinding below; it would
  survive even a sink, but with the filter it (and every other write)
  reaches stdout directly.  The contract is exercised by
  `re-frame.test-quiet-runner-contract-test`.

  Exit code is owned by cognitect-test-runner: 0 on green, 1 on any
  failure or error (and 1 on a CLI parse error).  This wrapper adds no
  exit logic and changes none of it."
  (:require
    [re-frame.test-quiet]
    [clojure.test]
    [cognitect.test-runner]))

(def ^:private discovery-banner-prefix
  "The literal text cognitect-test-runner's `test` fn prints via bare
  `println` before running tests: `(format \"\\nRunning tests in %s\" dirs)`.
  We match on this prefix so the directory-set tail (which varies) need
  not be enumerated."
  "Running tests in ")

(defn- discovery-banner-line?
  "True iff `line` (one logical println line, sans terminator) is
  cognitect's discovery banner and nothing else.  The banner is emitted
  alone on its own line, so an exact prefix match is unambiguous."
  [^String line]
  (.startsWith line discovery-banner-prefix))

(defn- banner-filtering-writer
  "A `java.io.Writer` that forwards every character to `target` EXCEPT a
  whole line equal to cognitect's discovery banner, which it drops.

  Buffers the current line until a newline arrives, then forwards
  newline-terminated lines verbatim unless they are the banner.  This is
  line-precise: help text, parse-error diagnostics, the reporter's
  failure output, and bare test stdout all pass through untouched; only
  the banner line is swallowed.  Any trailing unterminated text (no final
  newline) is forwarded on `flush`/`close` so nothing is silently lost.

  Clojure's `proxy` dispatches `write` by arity rather than by Java's
  static overload set, so the single-arg form must itself branch on the
  argument type (int char, `String`, or char[]).  Each arity feeds the
  same `consume-char!` line buffer, so the banner-detection logic lives
  in exactly one place."
  ^java.io.Writer [^java.io.Writer target]
  (let [buf (StringBuilder.)
        ;; Forward the buffered line `s` (without its trailing newline)
        ;; plus the newline, unless `s` is the banner — then drop both.
        flush-line! (fn [^String s]
                      (when-not (discovery-banner-line? s)
                        (.write target s)
                        (.write target (int \newline))))
        consume-char! (fn [c]
                        (if (= c \newline)
                          (do (flush-line! (.toString buf))
                              (.setLength buf 0))
                          (.append buf c)))
        consume-str! (fn [^String s]
                       (dotimes [i (.length s)]
                         (consume-char! (.charAt s i))))
        forward-partial! (fn []
                           (when (pos? (.length buf))
                             (.write target (.toString buf))
                             (.setLength buf 0)))]
    (proxy [java.io.Writer] []
      (write
        ([x]
         (cond
           (string? x)               (consume-str! x)
           (integer? x)              (consume-char! (char x))
           (instance? (Class/forName "[C") x)
           (consume-str! (String. ^chars x))
           :else (throw (IllegalArgumentException.
                          (str "unexpected write arg: " (class x))))))
        ([cbuf off len]
         (let [s (if (string? cbuf)
                   (subs cbuf off (+ off len))
                   (String. ^chars cbuf (int off) (int len)))]
           (consume-str! s))))
      (flush []
        ;; A bare flush with a partial (unterminated) line must not lose
        ;; it; forward it now. A line that turns out to be the banner is
        ;; only ever flushed via its terminating newline (cognitect uses
        ;; `println`), so this never leaks the banner.
        (forward-partial!)
        (.flush target))
      (close []
        (forward-partial!)
        (.flush target)))))

(defn -main [& args]
  ;; Bind `*out*` to a line-filtering writer over the real stdout that
  ;; drops ONLY cognitect's "\nRunning tests in #{...}" discovery banner.
  ;; Everything else — `-H` usage, parse-error diagnostics, bare test
  ;; stdout, and the reporter's failure output — passes through.
  ;;
  ;; `cognitect.test-runner/-main` calls `System/exit` on the test-run
  ;; path (from the computed fail/error counts) and on the parse-error
  ;; path, so control typically does not return past the `apply`. The
  ;; `-H`/help path is the exception: it prints usage and RETURNS without
  ;; exiting, so we must flush the filtering writer afterwards to forward
  ;; the trailing line. Flushing is harmless on the paths that did exit.
  (let [real-out *out*
        filtering (java.io.PrintWriter. (banner-filtering-writer real-out))]
    (binding [*out* filtering]
      (try
        (apply cognitect.test-runner/-main args)
        (finally
          (.flush filtering))))))
