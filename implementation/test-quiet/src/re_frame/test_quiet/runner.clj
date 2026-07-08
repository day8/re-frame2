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

  ## The cross-runtime quiet contract (rf2-nrk066)

  re-frame2 runs the same silent-on-success policy on three runtimes —
  JVM (this runner), CLJS node (`re-frame.test-quiet.shadow-node`), and
  browser-test.  The contract is *documented pass-through stdout +
  central noise buffering*.  The buffering POLICY is symmetric across
  runtimes — buffer the noise channel, drop it on green, replay it on red
  — but the buffered SCOPE differs by runtime (the asymmetry is called out
  on the stderr bullet below): the JVM runner captures the WHOLE stderr
  side (`*err*` + `System/err`), whereas the CLJS node runner captures only
  `console.warn` (not `console.error` / `process.stderr`).

   - STDOUT is pass-through, NOT summary-only.  The reporter
     (`re-frame.test-quiet`) makes a green run's stdout the canonical
     summary, but the runner forwards every OTHER byte a test, fixture,
     or the runner's own CLI emits — `-H`/`--test-help` usage,
     CLI parse-error diagnostics, and any bare `(println ...)` a test
     makes.  This is deliberate (rf2-lbo79.2): a blanket stdout sink
     made runner misuse opaque and lost failure-time diagnostics.  Only
     cognitect's own discovery banner is dropped (see below).  The CLJS
     node runner forwards stdout the same way.
   - STDERR/WARNINGS are BUFFERED, replayed on red, dropped on green.
     A green run must not flood stdout/stderr with the expected
     warnings that warning-heavy suites emit (e.g.
     `re-frame.mcp-base.sensitive`'s fail-closed contract-drift WARN on
     thousands of malformed `:sensitive?` stamps).  This runner binds
     `*err*`/`System/err` to a bounded ring buffer for the duration of
     the delegated run; the captured stderr is REPLAYED to the real
     stderr only when the run is RED (so a failing run keeps the
     diagnostic context that may explain it), and DROPPED on green.
     This mirrors the buffer-drop-replay SHAPE of the CLJS node runner's
     `console.warn` ring + red replay — so suites no longer need ad-hoc
     `(binding [*err* <sink>] …)` workarounds to stay quiet on green
     (rf2-80jyfk / rf2-nrk066).  The buffered SCOPE is asymmetric by
     design, though: the JVM binds the WHOLE stderr side — both `*err*`
     and a `System/err` bridge — so any Clojure or Java stderr write is
     captured, whereas the CLJS node runner buffers ONLY `console.warn`
     (a `console.warn` stub), NOT `console.error` or direct
     `process.stderr` writes.  The asymmetry reflects what each runtime's
     noise actually is: CLJS first-run warnings come through
     `console.warn`, while a deliberate `console.error` stays a real error
     channel; the JVM has no comparable convention, so it buffers the
     whole side.  Tests that ASSERT on captured warning text still capture
     `*err*` locally; only the noise-suppression-only sinks are removed.

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
    [clojure.string :as str]
    [clojure.test]
    [cognitect.test-runner]))

(def ^:private discovery-banner-prefix
  "The literal text cognitect-test-runner's `test` fn prints via bare
  `println` before running tests: `(format \"\\nRunning tests in %s\" dirs)`.

  `dirs` is ALWAYS a set — it defaults to `#{\"test\"}` and the `-d` CLI
  option accumulates via `(fnil conj #{})` — so the banner renders as
  `Running tests in #{...}`.  We match the prefix up to and including the
  set-literal opener `#{` rather than the bare `Running tests in ` so that
  a legitimate diagnostic such as `Running tests in local fixture...`
  emitted by a test or fixture is forwarded, not silently swallowed: only
  the runner's own banner opens a set literal here.

  The prefix is NECESSARY but not SUFFICIENT to confirm the banner (the
  rf2-14nojy.2 overdrop): cognitect emits the banner via `println`, so it
  is the WHOLE line — `(format \"Running tests in %s\" dirs)` and nothing
  more — whereas a user/fixture line such as
  `Running tests in #{:fixture :phase} MARKER` shares the exact prefix but
  carries trailing content after the closing `}`.  Dropping on the prefix
  alone swallowed that trailing diagnostic.  `banner-filtering-writer`
  therefore treats a full-prefix match only as a CANDIDATE: it buffers the
  rest of the line and drops it only if the remainder is a balanced set
  literal followed by nothing but whitespace (`banner-line-remainder?`).

  Note the leading `\\n` in cognitect's format string: the banner is
  `\\nRunning tests in #{...}\\n` — it opens with a BLANK LINE.  That
  leading newline is part of the banner artefact and is swallowed along
  with the `Running tests in #{...}` text (see the pending-blank handling
  in `banner-filtering-writer`); the text we *match* on is the
  non-blank-line portion below."
  "Running tests in #{")

(defn- banner-line-remainder?
  "True iff `remainder` — the text on a banner-candidate line AFTER the
  `discovery-banner-prefix` (`Running tests in #{`) — completes the
  cognitect discovery banner and NOTHING else.

  cognitect prints the banner with `println` as `(format \"Running tests
  in %s\" dirs)` where `dirs` is the dir-string SET, so the whole line is
  `Running tests in #{<set-body>}` and stops there.  This confirms that
  shape: the remainder must contain the set body, the closing `}` that
  balances the prefix's `#{`, and then only whitespace.  Anything after
  the closing brace — e.g. the ` MARKER` in a fixture's
  `Running tests in #{:fixture :phase} MARKER` — means it is NOT the
  banner and must be forwarded (the rf2-14nojy.2 overdrop guard).

  The scan tracks brace depth (the prefix already opened depth 1) and
  skips over Clojure string literals so a `}` inside a quoted dir name
  (`#{\"a}b\"}`) does not close the set early.  Returns false if the
  remainder never balances back to depth 0 (a partial banner that has not
  yet reached its own newline — handled as a still-open candidate by the
  caller)."
  [^String remainder]
  (let [n (.length remainder)]
    (loop [i 0, depth 1, in-str? false]
      (if (>= i n)
        false ; never closed the set on this line → not a complete banner
        (let [c (.charAt remainder i)]
          (cond
            in-str?
            (cond
              (= c \\)        (recur (+ i 2) depth in-str?) ; skip escaped char
              (= c \")        (recur (inc i) depth false)
              :else           (recur (inc i) depth in-str?))

            (= c \")          (recur (inc i) depth true)
            (= c \{)          (recur (inc i) (inc depth) in-str?)
            (= c \})          (let [d (dec depth)]
                                (if (zero? d)
                                  ;; Set closed — banner iff only whitespace
                                  ;; (a `\r` before the `\n`, say) follows.
                                  (str/blank? (subs remainder (inc i)))
                                  (recur (inc i) d in-str?)))
            :else             (recur (inc i) depth in-str?)))))))

(defn- banner-filtering-writer
  "A `java.io.Writer` that forwards every character to `target` EXCEPT
  cognitect's discovery banner line, which it drops.

  Forwards eagerly, holding back only as much text as could still BECOME
  the banner.  At the start of each line we are watching whether the
  incoming characters spell out `discovery-banner-prefix`:

   - while the buffered run is still a viable prefix of the banner we hold
     it (the banner candidate);
   - the moment it diverges from the banner prefix we forward the whole
     run immediately and pass the rest of the line straight through;
   - the moment it reaches the full banner prefix the line is a banner
     CANDIDATE, not a confirmed banner: cognitect prints the banner with
     `println`, so the banner is the WHOLE line and stops at the set
     literal's closing `}`.  We keep buffering the remainder and decide at
     the line's newline — drop the line only if the remainder is a
     balanced set literal followed by nothing but whitespace
     (`banner-line-remainder?`), this line was preceded by the held
     leading blank, AND cognitect's one discovery banner has NOT already
     been dropped (`banner-dropped?`).  cognitect's banner is
     `(format \"\\nRunning tests in %s\" dirs)`, so the genuine banner is
     ALWAYS opened by a blank line; requiring that held blank means an
     exact-shape user/fixture line such as a bare
     `(println \"Running tests in #{:fixture}\")` — same text, but no
     leading blank — is forwarded rather than silently overdropped
     (rf2-m8dbb9).  cognitect emits the banner exactly ONCE, before any
     test runs, so the `banner-dropped?` latch means a LATER blank-led
     banner-shaped line — a test that prints `(println)` then
     `(println \"Running tests in #{:fixture}\")` — is forwarded rather
     than swallowed as a phantom second banner (rf2-6nrk8x).  Otherwise the
     prefix was shared by a user/fixture diagnostic such as
     `Running tests in #{:fixture} MARKER` and the whole buffered line is
     forwarded (the rf2-14nojy.2 overdrop guard).

  cognitect's banner opens with a BLANK LINE — the format string is
  `\"\\nRunning tests in %s\"`, so the leading `\\n` renders an empty line
  immediately before the `Running tests in #{...}` text.  That blank line
  is part of the same artefact and must vanish too, else a green run leaks
  one stray leading blank line (rf2-khecvs).  We can't know a blank line
  is the banner's lead-in until the NEXT line proves to be the banner, so
  an empty line seen while still watching is held as a single PENDING
  BLANK: dropped if the next line is confirmed the banner, emitted
  otherwise (a divergent line, a short watched line, or a second blank).
  At most one blank is ever pending — it is resolved at the next line's
  first character.

  A newline resets the watch for the next line.  The crucial property
  versus a line-at-a-time buffer: non-banner text is never retained across
  the runner's `System/exit` (cognitect exits straight from the computed
  fail/error counts, so neither `flush` nor `close` runs).  An eager
  forward means a bare `(print ...)` diagnostic with no trailing newline
  still reaches stdout before exit.  The only text that can sit unflushed
  at exit is a banner candidate (the full buffered line so far — a strict
  banner prefix, possibly extended into an as-yet-unterminated banner
  remainder) or a single pending blank — all of which the `finally` flush
  and the JVM shutdown hook forward (a genuine trailing blank line and a
  bare `Running tests in #{...`-prefixed partial therefore both survive
  exit), while the real banner itself completes and is dropped before any
  exit because cognitect prints it with `println` and runs the suite
  after.

  Help text, parse-error diagnostics, the reporter's failure output, and
  bare test stdout all pass through untouched; only the banner line is
  swallowed.

  Clojure's `proxy` dispatches `write` by arity rather than by Java's
  static overload set, so the single-arg form must itself branch on the
  argument type (int char, `String`, or char[]).  Each arity feeds the
  same `consume-char!` watcher, so the banner-detection logic lives in
  exactly one place."
  ^java.io.Writer [^java.io.Writer target]
  (let [prefix-len (.length ^String discovery-banner-prefix)
        ;; `buf` holds the live candidate for the current line — the
        ;; banner-prefix run while `:watching`, extended with the
        ;; post-prefix remainder while `:banner-candidate`. `state` is one
        ;; of:
        ;;   :watching         — buf is a viable prefix of the banner-so-far;
        ;;   :banner-candidate — buf reached the full prefix; keep buffering
        ;;                       the remainder, decide drop/forward at the
        ;;                       line's newline (rf2-14nojy.2);
        ;;   :passthrough      — this line diverged; forward chars verbatim.
        buf   (StringBuilder.)
        state (volatile! :watching)
        ;; cognitect emits its discovery banner EXACTLY ONCE, via `println`
        ;; before any test runs (`cognitect.test-runner/test`), so it is the
        ;; first — and only — blank-led `Running tests in #{...}` line on the
        ;; stream.  Once we have dropped it, every later banner-shaped line is
        ;; genuine test/fixture stdout and must pass through: a test that
        ;; prints a blank line then banner-shaped text was otherwise
        ;; over-dropped, violating the pass-through contract (rf2-6nrk8x).
        banner-dropped? (volatile! false)
        ;; `pending-blank?` holds back a single empty line that MIGHT be
        ;; the banner's leading newline (`\nRunning tests in #{...}`). It
        ;; is resolved at the next line's first character: dropped if that
        ;; line confirms the banner, emitted otherwise.
        pending-blank? (volatile! false)
        emit! (fn [^String s] (when (pos? (.length s)) (.write target s)))
        flush-pending-blank! (fn []
                               ;; The held blank was NOT the banner's
                               ;; lead-in after all — emit it as a real
                               ;; empty line.
                               (when @pending-blank?
                                 (.write target (int \newline))
                                 (vreset! pending-blank? false)))
        drop-pending-blank! (fn []
                              ;; The held blank WAS the banner's lead-in —
                              ;; discard it with the banner.
                              (vreset! pending-blank? false))
        forward-partial! (fn []
                           ;; Forward any held blank + candidate (the
                           ;; candidate is only ever a strict banner
                           ;; prefix). Called on flush/close for the
                           ;; in-process help/return path and the JVM
                           ;; shutdown hook, so a genuine trailing blank
                           ;; line and a bare partial both survive exit.
                           (flush-pending-blank!)
                           (when (pos? (.length buf))
                             (.write target (.toString buf))
                             (.setLength buf 0)))
        reset-line! (fn []
                      (.setLength buf 0)
                      (vreset! state :watching))
        consume-char! (fn [c]
                        (cond
                          (= c \newline)
                          (do (case @state
                                ;; Empty watched run → a blank line. It may
                                ;; be the banner's leading newline, so hold
                                ;; it pending instead of forwarding now. A
                                ;; blank already pending means the prior
                                ;; blank was NOT followed by the banner —
                                ;; flush it and hold this one.
                                ;; A non-empty watched run is a short line
                                ;; that never reached the banner prefix —
                                ;; any pending blank preceded real content,
                                ;; so flush it, then forward the run.
                                :watching    (if (zero? (.length buf))
                                               (do (flush-pending-blank!)
                                                   (vreset! pending-blank? true))
                                               (do (flush-pending-blank!)
                                                   (emit! (.toString buf))
                                                   (.write target (int \newline))))
                                ;; Reached the full prefix — DECIDE now that
                                ;; the line is complete (rf2-14nojy.2). The
                                ;; remainder is everything buffered after the
                                ;; prefix; if it is a balanced set literal +
                                ;; only whitespace AND this line was preceded
                                ;; by the held leading blank, this IS the
                                ;; discovery banner → drop the whole line (and
                                ;; the pending blank that was its leading
                                ;; newline). Otherwise the prefix was shared
                                ;; by a real diagnostic → flush the pending
                                ;; blank and forward the whole buffered line.
                                ;;
                                ;; The leading-blank requirement (rf2-m8dbb9)
                                ;; distinguishes cognitect's banner — printed
                                ;; via `(format "\nRunning tests in %s" dirs)`,
                                ;; so ALWAYS opened by a blank line — from a
                                ;; user/fixture line that exact-matches the
                                ;; banner shape (`Running tests in #{:fixture}`)
                                ;; but is emitted by a bare `println` with no
                                ;; preceding blank. Without this, such a line
                                ;; was indistinguishable from the banner and
                                ;; silently overdropped, contradicting the
                                ;; pass-through stdout contract.
                                ;; The leading-blank + balanced-set-literal
                                ;; shape is NECESSARY but, on its own, not
                                ;; SUFFICIENT: cognitect prints the banner only
                                ;; once, so once we have dropped it, a later
                                ;; blank-led banner-shaped line is genuine test
                                ;; stdout and must be forwarded, not swallowed
                                ;; as a second "banner" (rf2-6nrk8x). Gate the
                                ;; drop on `banner-dropped?` and latch it here.
                                :banner-candidate
                                (let [remainder (subs (.toString buf) prefix-len)]
                                  (if (and (not @banner-dropped?)
                                           @pending-blank?
                                           (banner-line-remainder? remainder))
                                    (do (vreset! banner-dropped? true)
                                        (drop-pending-blank!))
                                    (do (flush-pending-blank!)
                                        (.write target (.toString buf))
                                        (.write target (int \newline)))))
                                ;; Diverged line — its chars already went
                                ;; straight through; terminate it.
                                :passthrough (.write target (int \newline)))
                              (reset-line!))

                          (= @state :passthrough)
                          (.write target (int c))

                          ;; A full-prefix candidate keeps accreting its
                          ;; remainder; the drop/forward call is deferred to
                          ;; the newline above.
                          (= @state :banner-candidate)
                          (.append buf c)

                          :else ; :watching — extend the candidate
                          (do
                            (.append buf c)
                            (let [s (.toString buf)]
                              (cond
                                ;; Reached (or passed) the full prefix AND it
                                ;; really IS the prefix → banner CANDIDATE
                                ;; (not yet confirmed; the line might carry
                                ;; trailing content). Keep the pending blank
                                ;; held — it stands or falls with this line at
                                ;; the newline decision.
                                (and (>= (.length buf) prefix-len)
                                     (.startsWith s ^String discovery-banner-prefix))
                                (vreset! state :banner-candidate)
                                ;; Still a viable prefix of the banner — keep
                                ;; holding (and keep any pending blank held;
                                ;; it stands or falls with this line).
                                (.startsWith ^String discovery-banner-prefix s)
                                nil
                                ;; Diverged → this line is not the banner, so
                                ;; the pending blank preceded real content;
                                ;; flush it, then forward the diverged run.
                                :else
                                (do (flush-pending-blank!)
                                    (emit! s)
                                    (.setLength buf 0)
                                    (vreset! state :passthrough)))))))
        consume-str! (fn [^String s]
                       (dotimes [i (.length s)]
                         (consume-char! (.charAt s i))))]
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

;; ----------------------------------------------------------------------
;; Central stderr buffer + red replay (rf2-nrk066).
;;
;; The symmetric counterpart to the CLJS node runner's `console.warn`
;; ring buffer (`re-frame.test-quiet.shadow-node`).  Warning-heavy JVM
;; suites emit expected stderr noise on the green path (e.g.
;; `re-frame.mcp-base.sensitive` logging a contract-drift WARN for every
;; malformed `:sensitive?` stamp it fail-closes).  Pre-rf2-nrk066 the JVM
;; runner had NO central stderr handling — it filtered only cognitect's
;; stdout banner — so those warnings flooded a green run unless each
;; namespace bound `*err*` to a private sink.  We now buffer stderr in a
;; bounded ring for the delegated run and:
;;   - GREEN: drop the buffer silently (quiet on success);
;;   - RED:   replay it to the real stderr from the `:summary` reporter
;;            hook below, BEFORE cognitect computes its exit code, so a
;;            failing run keeps the diagnostic context.
;;
;; clojure.test routes its assertion / FAIL / ERROR / summary output
;; through `clojure.test/*test-out*` (bound to the real `*out*`), NOT
;; through `*err*`, so buffering stderr never hides failure diagnostics —
;; the red FAIL/ERROR blocks reach stdout via the `*out*` filter exactly
;; as before.

(def ^:private stderr-buffer-cap
  "Bounded stderr ring capacity (characters).  Caps memory + replay
  volume on a red run while keeping enough recent context to explain a
  failure — the newest `stderr-buffer-cap` characters are retained,
  older ones dropped.  ~256 KB is generous for diagnostic warnings
  without risking heap pressure on a pathologically chatty suite."
  (* 256 1024))

(defn- buffering-stderr-writer
  "A `java.io.Writer` over the shared `sb` ring that captures everything
  written to it (trimming `sb` to the newest `stderr-buffer-cap`
  characters) and forwards NOTHING to the real stderr.  The runner reads
  `sb` back at `:summary` time and replays it only on red
  (`install-summary-replay-hook!`); on green the buffer is dropped."
  ^java.io.Writer [^StringBuilder sb]
  (let [append! (fn [^String s]
                  (.append sb s)
                  ;; Trim from the FRONT once past the cap so the ring
                  ;; keeps the most-recent context (the bytes nearest a
                  ;; failure), matching the CLJS ring's newest-N policy.
                  (let [n (.length sb)]
                    (when (> n stderr-buffer-cap)
                      (.delete sb 0 (- n stderr-buffer-cap)))))]
    (proxy [java.io.Writer] []
      (write
        ([x]
         (cond
           (string? x)  (append! x)
           (integer? x) (append! (String. (char-array [(char x)])))
           (instance? (Class/forName "[C") x)
           (append! (String. ^chars x))
           :else (throw (IllegalArgumentException.
                          (str "unexpected write arg: " (class x))))))
        ([cbuf off len]
         (append! (if (string? cbuf)
                    (subs cbuf off (+ off len))
                    (String. ^chars cbuf (int off) (int len))))))
      (flush [])
      (close []))))

(defn- install-summary-replay-hook!
  "Install a `clojure.test/report :summary` override that REPLAYS the
  buffered stderr (`sb`) to `real-err` when the run is RED, then delegates
  to whatever `:summary` method was installed before us so the canonical
  summary line still prints.

  `:summary` is the JVM-side counterpart to the CLJS `:end-run-tests`
  reporter: it fires once at the end of `run-tests` from the same
  fail/error counts cognitect reads to compute the exit code, and it runs
  on the test-driver thread INSIDE the `binding` that rebinds `*err*` —
  so it runs before cognitect's `System/exit` and can flush the captured
  context.  A run is RED iff `(pos? (+ fail error))`, matching cognitect's
  `(zero? (+ fail error))` green test.

  SCOPE — the replay is bounded to the TEST-RUN red path.  cognitect only
  fires `:summary` once it has reached `run-tests`, so the buffered stderr
  is replayed only for a red run that actually executed tests.  The
  non-test exit-1 path — a CLI parse error
  (`cognitect.test-runner/-main` prints the parse diagnostics + usage and
  `System/exit`s 1 BEFORE ever calling `run-tests`) — never fires
  `:summary`, so its buffer is dropped, not replayed.  That is sound here:
  on the parse-error path the diagnostics cognitect emits go to `*out*`
  (the banner-filtering writer), NOT `*err*`, so nothing diagnostic is
  lost — the only thing dropped is whatever incidental `*err*`/`System.err`
  noise was written before the parse failed (in practice none; arg parsing
  does not log to stderr).  A `finally`-flush in `-main` cannot widen the
  scope: cognitect `System/exit`s straight from the parse-error branch, so
  `-main`'s `finally` never runs on that path (it runs only on the `-H`
  help/return path).  Tightening this docstring rather than adding an
  unreachable flush keeps the contract honest (rf2-22s58s).

  The replay goes to the REAL stderr (the `*err*` ring is still bound at
  this point, so we must NOT use `*err*`/`println` here or the replay
  would feed straight back into the buffer).  Each captured chunk is
  written VERBATIM; a header marks it as a red-run replay so it is
  distinguishable from the reporter's own stdout, mirroring the CLJS
  replay's `[test-quiet]` prefix.  On green the buffer is simply dropped
  (never written)."
  [^StringBuilder sb ^java.io.Writer real-err]
  (let [prior-summary (get-method clojure.test/report :summary)]
    (defmethod clojure.test/report :summary [m]
      (let [{:keys [fail error]} m]
        (when (and (pos? (+ (or fail 0) (or error 0)))
                   (pos? (.length sb)))
          (let [captured (.toString sb)]
            (.write real-err
                    (str "\n[test-quiet] buffered stderr replayed"
                         " because the run was RED:\n"))
            (.write real-err captured)
            (when-not (.endsWith captured "\n")
              (.write real-err "\n"))
            (.flush real-err))))
      ;; Delegate to the prior :summary so the canonical
      ;; "Ran N tests…/K failures, J errors." line still prints.
      (when prior-summary (prior-summary m)))))

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
  ;; exiting, so we flush the filtering writer in the `finally` to forward
  ;; any trailing line; flushing is harmless on the paths that did exit.
  ;;
  ;; The `finally` cannot fire on the `System/exit` paths, so we also
  ;; register a JVM shutdown hook that flushes the filtering writer (and,
  ;; through it, the real stdout).  Without it, a bare `(print ...)`
  ;; diagnostic with no trailing newline — buffered anywhere between this
  ;; wrapper and the OS — could be lost exactly when a failing run needs
  ;; it most.  The hook makes flush-on-exit deterministic rather than
  ;; relying on the runtime or cognitect to flush before exiting.
  ;;
  ;; We ALSO bind `*err*` (and `System/err`) to a bounded ring buffer for
  ;; the delegated run (rf2-nrk066): expected stderr warnings are captured
  ;; rather than flooding a green run, and the `:summary` reporter hook
  ;; replays them to the real stderr only on RED — symmetric with the CLJS
  ;; node runner's `console.warn` buffer + red replay.  `*err*` is bound on
  ;; the test-driver thread (cognitect runs single-threaded), so library
  ;; code that logs via `*err*` is captured; `System/err` is also swapped
  ;; so a raw `System.err` write is buffered too.  The summary hook reads
  ;; the buffer and writes any red replay to the REAL stderr captured here.
  ;; The replay is scoped to the TEST-RUN red path: `:summary` fires only
  ;; once cognitect reaches `run-tests`, so the non-test exit-1 path (a CLI
  ;; parse error) drops its buffer rather than replaying it — sound because
  ;; cognitect's parse diagnostics go to `*out*` (the filter), not `*err*`,
  ;; and that path `System/exit`s before `-main`'s `finally` can run (see
  ;; `install-summary-replay-hook!`).
  (let [real-out   *out*
        real-err   *err*
        sys-err    System/err
        filtering  (java.io.PrintWriter. (banner-filtering-writer real-out))
        stderr-sb  (StringBuilder.)
        buffered-w (buffering-stderr-writer stderr-sb)
        buffered-e (java.io.PrintWriter. buffered-w)]
    (.addShutdownHook (Runtime/getRuntime)
                      (Thread. ^Runnable #(.flush filtering)))
    (install-summary-replay-hook! stderr-sb real-err)
    ;; Route raw `System/err` bytes into the SAME ring as `*err*` so a
    ;; library that writes `System.err` directly is buffered too. Each chunk
    ;; is decoded to a `String` and appended to the ring (this buffer is the
    ;; diagnostic/red-replay path only — never the green-run summary). Note
    ;; the encoding asymmetry between the two `write` proxy arities:
    ;;   - write(byte[],off,len): a chatty library prints via
    ;;     `PrintStream.print(...)`/`write(byte[])`, which the autoFlush
    ;;     UTF-8 `PrintStream` below routes through THIS multi-byte arity, so
    ;;     a whole encoded chunk is decoded as one unit and survives intact.
    ;;   - write(int): the single-byte arity decodes that ONE byte on its
    ;;     own. A multi-byte UTF-8 sequence delivered one byte at a time —
    ;;     only a raw `System.err.write(int)` per byte hits this path; the
    ;;     `PrintStream` itself never splits a code point across single-byte
    ;;     writes — would decode each byte separately and mangle the
    ;;     character. This arity is therefore BEST-EFFORT / ASCII-only by
    ;;     design: it preserves byte fidelity for the common ASCII diagnostic
    ;;     case and tolerates (does not guarantee) multi-byte text on the
    ;;     pathological raw per-byte path. Buffering raw bytes to decode once
    ;;     at replay is not a clean fit here — the ring is a shared char
    ;;     `Writer` that `*err*` also writes into directly, so there is no
    ;;     single byte stream to defer-decode (rf2-22s58s).
    (let [sys-bridge (proxy [java.io.OutputStream] []
                       (write
                         ([b]
                          ;; proxy dispatches by arity; the 1-arg form is
                          ;; write(int) — the low 8 bits are the byte (0-255),
                          ;; so use `unchecked-byte` (a plain `byte` cast
                          ;; throws for 128-255). Best-effort/ASCII (see above).
                          (.write buffered-w (String. (byte-array [(unchecked-byte b)]))))
                         ([b off len]
                          (.write buffered-w (String. ^bytes b (int off) (int len))))))]
      (System/setErr (java.io.PrintStream. sys-bridge true "UTF-8")))
    (binding [*out* filtering
              *err* buffered-e]
      (try
        (apply cognitect.test-runner/-main args)
        (finally
          (.flush filtering)
          ;; Restore the real System/err for any post-run / shutdown code.
          (System/setErr sys-err))))))
