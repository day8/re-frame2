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

  ## Quiet contract

  The JVM and CLJS node runners use pass-through stdout plus bounded noise
  buffering. Both drop the buffer on green and replay it on red, but their
  capture scopes differ. Browser diagnostics are owned by the browser
  harness, not this runner.

   - STDOUT is pass-through, NOT summary-only.  The reporter
     (`re-frame.test-quiet`) makes a green run's stdout the canonical
     summary, but the runner forwards every OTHER byte a test, fixture,
     or the runner's own CLI emits — `-H`/`--test-help` usage,
     CLI parse-error diagnostics, and any bare `(println ...)` a test
     makes. Only cognitect's own discovery banner is dropped (see below). The CLJS
     node runner forwards stdout the same way.
   - STDERR/WARNINGS are BUFFERED, replayed on red, dropped on green.
     A green run must not flood stdout/stderr with the expected
     warnings that warning-heavy suites emit (e.g.
     `re-frame.mcp-base.sensitive`'s fail-closed contract-drift WARN on
     thousands of malformed `:sensitive?` stamps). This runner binds the
     test-driver thread's `*err*` and swaps the process-global `System/err`
     to a bounded ring buffer. Captured stderr is replayed to the real
     stderr only when the run is red and dropped on green.
     The CLJS node runner uses the same policy for a narrower scope: a
     `console.warn` stub, not `console.error` or direct
     `process.stderr` writes.  The asymmetry reflects what each runtime's
     noise actually is: CLJS first-run warnings come through
     `console.warn`, while a deliberate `console.error` stays a real error
     channel; the JVM has no comparable convention, so it buffers the
     current-thread `*err*` plus process-global `System.err`. Tests that
     assert on warning text still capture `*err*` locally.

  ## The discovery-banner contract

  cognitect-test-runner emits exactly one stdout artefact of its own that
  we want gone — the `\\nRunning tests in #{...}` banner that
  `cognitect.test-runner/test` prints via bare `println` before kicking
  off `run-tests`.  We swallow ONLY that line, by binding `*out*` to a
  *filtering* writer that drops the discovery banner and passes every
  other byte straight through to the real stdout.

  This is a line-precise filter, not a global sink. cognitect's help and
  parse-error paths use the same `*out*` as the banner, so the filter keys
  on the banner's exact shape and forwards every other line.

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

  ## What a lane claims, and what it must therefore prove

  Exit code is otherwise owned by cognitect-test-runner: 0 on green, 1 on
  any failure or error (and 1 on a CLI parse error).  This wrapper adds
  exactly one exit rule of its own, and which form it takes depends on
  what the lane claims:

   - a SUITE lane claims coverage, so it must prove it RAN: fewer than
     `RF2_MIN_TESTS` tests executed (default 1) is red, however clean the
     tally.  `run-tests` over an empty namespace set reports `Ran 0 tests
     containing 0 assertions. / 0 failures, 0 errors.` and cognitect exits
     0 from that, so a discovery set that silently collapsed to nothing —
     a `-r`/`-n` selector matching no namespace, a `:test` alias that lost
     its `:extra-paths [\"test\"]`, a renamed test file — was
     indistinguishable from a green suite.  See `parse-min-tests`.
   - a PROBE lane claims only that its deps and classpath RESOLVE, so zero
     tests is its correct outcome and the coverage floor must not apply.
     Such a lane says so explicitly by passing `--probe` in its `:test`
     alias `:main-opts` (`implementation/adapters/reagent` and
     `implementation/adapters/uix` are the two: their `test/` trees are
     CLJS-only).  A probe still has to prove something mechanically: it
     must reach its summary having executed exactly zero tests.  Reaching
     the summary at all means deps resolved and the discovery dirs were
     scanned; and if the lane ever GAINS a JVM test it goes red, because it
     is then claiming coverage and must drop `--probe` and take the floor.

  `--probe` is this wrapper's only own flag and is stripped before args are
  forwarded to cognitect.  Nothing is printed on a green probe: the
  silent-on-success contract stands, and the probe's proof is the assertion,
  not an announcement.

  ## What the lane will DISCOVER

  Both rules above are about the tests that RAN.  Neither can see a test
  file that never entered the run at all — and there are two ways for one
  not to.  Cognitect's discovery reads each file's `(ns ...)` form and
  silently drops the ones it cannot read; and it returns a namespace once
  per FILE, so two files declaring the same one leave `require` loading
  whichever the classpath resolves while the other never loads, running a
  suite twice under a tally that goes UP rather than down.
  `discovery-defects` is the third rule and it fires BEFORE any test does;
  see its own docstring, and rf2-vruo9."
  (:require
    [re-frame.test-quiet]
    [clojure.java.io :as io]
    [clojure.string :as str]
    [clojure.test]
    [clojure.tools.cli :as cli]
    [clojure.tools.namespace.file :as ns-file]
    [clojure.tools.namespace.find :as ns-find]
    [clojure.tools.namespace.parse :as ns-parse]
    [cognitect.test-runner]))

(def ^:private discovery-banner-prefix
  "The literal text cognitect-test-runner's `test` fn prints via bare
  `println` before running tests: `(format \"\\nRunning tests in %s\" dirs)`.

  `dirs` is a set, so the banner renders as `Running tests in #{...}`. We
  match the prefix up to and including the
  set-literal opener `#{` rather than the bare `Running tests in ` so that
  a legitimate diagnostic such as `Running tests in local fixture...`
  emitted by a test or fixture is forwarded, not silently swallowed: only
  the runner's own banner opens a set literal here.

  The prefix is necessary but not sufficient: cognitect emits the banner
  as the whole line, whereas a user/fixture line such as
  `Running tests in #{:fixture :phase} MARKER` shares the exact prefix but
  carries trailing content after the closing `}` and is a distinct
  diagnostic. `banner-filtering-writer` therefore treats a
  full-prefix match only as a candidate: it buffers the
  rest of the line and drops it only if the remainder is a balanced set
  literal followed by nothing but whitespace (`banner-line-remainder?`).

  The banner is `\\nRunning tests in #{...}\\n`, so its leading blank line
  is part of the banner artefact and is swallowed along
  with the `Running tests in #{...}` text (see the pending-blank handling
  in `banner-filtering-writer`); the text we *match* on is the
  non-blank-line portion below."
  "Running tests in #{")

(defn- banner-line-remainder?
  "True iff `remainder`, the text after `discovery-banner-prefix`,
  completes the cognitect discovery banner and nothing else.

  cognitect prints the banner with `println` as `(format \"Running tests
  in %s\" dirs)` where `dirs` is the directory set, so the whole line is
  `Running tests in #{<set-body>}` and stops there.  This confirms that
  shape: the remainder must contain the set body, the closing `}` that
  balances the prefix's `#{`, and then only whitespace.  Anything after
  the closing brace — e.g. the ` MARKER` in a fixture's
  `Running tests in #{:fixture :phase} MARKER` — means it is NOT the
  banner and must be forwarded.

  The scan tracks brace depth (the prefix already opened depth 1) and
  skips over Clojure string literals so a `}` inside a quoted dir name
  (`#{\"a}b\"}`) does not close the set early.  Returns false if the
  remainder never balances back to depth 0 (a partial banner that has not
  yet reached its own newline — handled as a still-open candidate by the
  caller)."
  [^String remainder]
  (let [remainder-length (.length remainder)]
    (loop [index 0, brace-depth 1, inside-string? false]
      (if (>= index remainder-length)
        false ; never closed the set on this line → not a complete banner
        (let [character (.charAt remainder index)]
          (cond
            inside-string?
            (cond
              (= character \\) (recur (+ index 2) brace-depth inside-string?)
              (= character \") (recur (inc index) brace-depth false)
              :else            (recur (inc index) brace-depth inside-string?))

            (= character \") (recur (inc index) brace-depth true)
            (= character \{) (recur (inc index) (inc brace-depth) inside-string?)
            (= character \}) (let [next-depth (dec brace-depth)]
                                (if (zero? next-depth)
                                  ;; Set closed — banner iff only whitespace
                                  ;; (a `\r` before the `\n`, say) follows.
                                  (str/blank? (subs remainder (inc index)))
                                  (recur (inc index) next-depth inside-string?)))
            :else (recur (inc index) brace-depth inside-string?)))))))

(defn- banner-filtering-writer
  "A `java.io.Writer` that forwards every character to `target` EXCEPT
  cognitect's discovery banner line, which it drops.

  Forwards eagerly, holding back only as much text as could still become
  the banner.  At the start of each line we are watching whether the
  incoming characters spell out `discovery-banner-prefix`:

   - while the buffered run is still a viable prefix of the banner we hold
     it (the banner candidate);
   - the moment it diverges from the banner prefix we forward the whole
     run immediately and pass the rest of the line straight through;
   - the moment it reaches the full banner prefix the line is a banner
     candidate, not a confirmed banner: cognitect prints the banner with
     `println`, so the banner is the WHOLE line and stops at the set
     literal's closing `}`.  We keep buffering the remainder and decide at
     the line's newline — drop the line only if the remainder is a
     balanced set literal followed by nothing but whitespace
     (`banner-line-remainder?`), this line was preceded by the held
     leading blank, AND cognitect's one discovery banner has NOT already
     been dropped (`banner-dropped?`).  cognitect's banner is
      `(format \"\\nRunning tests in %s\" dirs)`, so the genuine banner is
      always opened by a blank line; requiring that held blank means an
     exact-shape user/fixture line such as a bare
      `(println \"Running tests in #{:fixture}\")` - same text, but no
      leading blank - is forwarded. cognitect emits the banner exactly
      once, before any test runs, so the `banner-dropped?` latch means a
      later blank-led
     banner-shaped line — a test that prints `(println)` then
     `(println \"Running tests in #{:fixture}\")` — is forwarded rather
      than swallowed as a phantom second banner. Otherwise the
     prefix was shared by a user/fixture diagnostic such as
     `Running tests in #{:fixture} MARKER` and the whole buffered line is
      forwarded.

  cognitect's banner opens with a blank line: the format string is
  `\"\\nRunning tests in %s\"`, so the leading `\\n` renders an empty line
  immediately before the `Running tests in #{...}` text.  That blank line
  is part of the same artefact and must vanish too, else a green run leaks
  one stray leading blank line. We can't know a blank line
  is the banner's lead-in until the NEXT line proves to be the banner, so
  an empty line seen while still watching is held as a single pending
  blank: dropped if the next line is confirmed the banner, emitted
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
  remainder) or a single pending blank. The explicit flush on returning
  paths and the JVM shutdown hook on `System/exit` forward those candidates,
  so a genuine trailing blank line and a bare
  `Running tests in #{...`-prefixed partial both survive exit. The real
  banner completes and is dropped before exit because cognitect prints it
  with `println` and runs the suite after.

  Help text, parse-error diagnostics, the reporter's failure output, and
  bare test stdout all pass through untouched; only the banner line is
  swallowed.

  Clojure's `proxy` dispatches `write` by arity rather than by Java's
  static overload set, so the single-arg form must itself branch on the
  argument type (int char, `String`, or char[]).  Each arity feeds the
  same `consume-character!` watcher, so the banner-detection logic lives in
  exactly one place."
  ^java.io.Writer [^java.io.Writer target]
  (let [banner-prefix-length (.length ^String discovery-banner-prefix)
        ;; `line-candidate` holds the live candidate for the current line — the
        ;; banner-prefix run while `:watching`, extended with the
        ;; post-prefix remainder while `:banner-candidate`. `line-mode` is one
        ;; of:
        ;;   :watching         — line-candidate is a viable banner prefix;
        ;;   :banner-candidate — line-candidate reached the full prefix; buffer
        ;;                       the remainder, decide drop/forward at the
        ;;                       line's newline;
        ;;   :passthrough      — this line diverged; forward chars verbatim.
        line-candidate (StringBuilder.)
        line-mode      (volatile! :watching)
        ;; cognitect emits its discovery banner EXACTLY ONCE, via `println`
        ;; before any test runs (`cognitect.test-runner/test`), so it is the
        ;; first — and only — blank-led `Running tests in #{...}` line on the
        ;; stream.  Once we have dropped it, every later banner-shaped line is
        ;; genuine test/fixture stdout and must pass through: a test that
        ;; prints a blank line then banner-shaped text was otherwise
        ;; over-dropped, violating the pass-through contract.
        banner-dropped? (volatile! false)
        ;; `pending-blank?` holds back a single empty line that MIGHT be
        ;; the banner's leading newline (`\nRunning tests in #{...}`). It
        ;; is resolved at the next line's first character: dropped if that
        ;; line confirms the banner, emitted otherwise.
        pending-blank? (volatile! false)
        emit-text! (fn [^String text]
                     (when (pos? (.length text)) (.write target text)))
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
                           (when (pos? (.length line-candidate))
                             (.write target (.toString line-candidate))
                             (.setLength line-candidate 0)))
        reset-line! (fn []
                      (.setLength line-candidate 0)
                      (vreset! line-mode :watching))
        consume-character! (fn [character]
                        (cond
                          (= character \newline)
                          (do (case @line-mode
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
                                :watching    (if (zero? (.length line-candidate))
                                               (do (flush-pending-blank!)
                                                   (vreset! pending-blank? true))
                                               (do (flush-pending-blank!)
                                                   (emit-text! (.toString line-candidate))
                                                   (.write target (int \newline))))
                                ;; Reached the full prefix — DECIDE now that
                                ;; the line is complete. The
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
                                ;; The leading-blank requirement
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
                                ;; as a second "banner". Gate the
                                ;; drop on `banner-dropped?` and latch it here.
                                :banner-candidate
                                (let [remainder (subs (.toString line-candidate)
                                                      banner-prefix-length)]
                                  (if (and (not @banner-dropped?)
                                           @pending-blank?
                                           (banner-line-remainder? remainder))
                                    (do (vreset! banner-dropped? true)
                                        (drop-pending-blank!))
                                    (do (flush-pending-blank!)
                                        (.write target (.toString line-candidate))
                                        (.write target (int \newline)))))
                                ;; Diverged line — its chars already went
                                ;; straight through; terminate it.
                                :passthrough (.write target (int \newline)))
                              (reset-line!))

                          (= @line-mode :passthrough)
                          (.write target (int character))

                          ;; A full-prefix candidate keeps accreting its
                          ;; remainder; the drop/forward call is deferred to
                          ;; the newline above.
                          (= @line-mode :banner-candidate)
                          (.append line-candidate character)

                          :else ; :watching — extend the candidate
                          (do
                            (.append line-candidate character)
                            (let [candidate-text (.toString line-candidate)]
                              (cond
                                ;; Reached (or passed) the full prefix AND it
                                ;; really IS the prefix → banner CANDIDATE
                                ;; (not yet confirmed; the line might carry
                                ;; trailing content). Keep the pending blank
                                ;; held — it stands or falls with this line at
                                ;; the newline decision.
                                (and (>= (.length line-candidate)
                                         banner-prefix-length)
                                     (.startsWith candidate-text
                                                  ^String discovery-banner-prefix))
                                (vreset! line-mode :banner-candidate)
                                ;; Still a viable prefix of the banner — keep
                                ;; holding (and keep any pending blank held;
                                ;; it stands or falls with this line).
                                (.startsWith ^String discovery-banner-prefix
                                             candidate-text)
                                nil
                                ;; Diverged → this line is not the banner, so
                                ;; the pending blank preceded real content;
                                ;; flush it, then forward the diverged run.
                                :else
                                (do (flush-pending-blank!)
                                    (emit-text! candidate-text)
                                    (.setLength line-candidate 0)
                                    (vreset! line-mode :passthrough)))))))
        consume-string! (fn [^String text]
                          (dotimes [index (.length text)]
                            (consume-character! (.charAt text index))))]
    (proxy [java.io.Writer] []
      (write
        ([value]
         (cond
           (string? value)               (consume-string! value)
           (integer? value)              (consume-character! (char value))
           (instance? (Class/forName "[C") value)
           (consume-string! (String. ^chars value))
           :else (throw (IllegalArgumentException.
                          (str "unexpected write arg: " (class value))))))
        ([char-buffer offset length]
         (let [text (if (string? char-buffer)
                      (subs char-buffer offset (+ offset length))
                      (String. ^chars char-buffer (int offset) (int length)))]
           (consume-string! text))))
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
;; Central stderr buffer + red replay.
;;
;; The symmetric counterpart to the CLJS node runner's `console.warn`
;; ring buffer (`re-frame.test-quiet.shadow-node`). The runner buffers
;; stderr in a bounded ring for the delegated run and:
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
  failure. The newest `stderr-buffer-cap` characters are retained and
  older ones are dropped."
  (* 256 1024))

(defn- buffering-stderr-writer
  "A `java.io.Writer` over the shared `stderr-ring` that captures everything
  written to it (trimming `stderr-ring` to the newest `stderr-buffer-cap`
  characters) and forwards NOTHING to the real stderr.  The runner reads
  `stderr-ring` back at `:summary` time and replays it only on red
  (`make-summary-replay-method`); on green the buffer is dropped."
  ^java.io.Writer [^StringBuilder stderr-ring]
  (let [append-to-ring! (fn [^String text]
                  ;; Serialize the WHOLE append-plus-front-trim transaction on
                  ;; `stderr-ring`'s monitor. Both JVM stderr channels funnel here —
                  ;; `*err*` via one PrintWriter and raw `System.err` via the
                  ;; `System/setErr` PrintStream bridge (see `-main`) — and those
                  ;; two wrappers serialize only their OWN calls under DISTINCT
                  ;; locks, so without this shared monitor a write from each
                  ;; channel can interleave inside this body: a `.append` racing
                  ;; a front-trim `.delete` tears StringBuilder's internal
                  ;; count/array and throws ArrayIndexOutOfBoundsException from an
                  ;; otherwise-valid test. StringBuilder (unlike StringBuffer) has
                  ;; no synchronized methods, so nothing else contends for this
                  ;; monitor; the summary snapshot in
                  ;; `make-summary-replay-method` locks on the SAME ring, so a
                  ;; red replay never reads a half-applied mutation.
                  (locking stderr-ring
                    (.append stderr-ring text)
                    ;; Trim from the front once past the cap so the ring
                    ;; keeps the most-recent context (the characters nearest a
                    ;; failure), matching the CLJS ring's newest-N policy.
                    (let [ring-length (.length stderr-ring)]
                      (when (> ring-length stderr-buffer-cap)
                        (.delete stderr-ring 0
                                 (- ring-length stderr-buffer-cap))))))]
    (proxy [java.io.Writer] []
      (write
        ([value]
         (cond
           (string? value)  (append-to-ring! value)
           (integer? value) (append-to-ring!
                              (String. (char-array [(char value)])))
           (instance? (Class/forName "[C") value)
           (append-to-ring! (String. ^chars value))
           :else (throw (IllegalArgumentException.
                          (str "unexpected write arg: " (class value))))))
        ([char-buffer offset length]
         (append-to-ring!
           (if (string? char-buffer)
             (subs char-buffer offset (+ offset length))
             (String. ^chars char-buffer (int offset) (int length))))))
      (flush [])
      (close []))))

;; ----------------------------------------------------------------------
;; What a lane claims, and what it must therefore prove (rf2-qqzmf) — the
;; coverage floor for a suite lane, `probe-flag` for a resolution lane.
;;
;; The project already holds this standard in two places and had not
;; generalised it:
;;   - `re-frame.test-quiet.shadow-node/execute-cli` rejects a `--test=`
;;     SELECTOR that matches no var, "precisely because run-test-vars over
;;     an empty set reports a 0-test success";
;;   - `re-frame.conformance-runner` asserts `(>= (count run) 150)` over the
;;     conformance CENSUS.
;; Both are places that claim COVERAGE, and both say the same thing: a run
;; that executed nothing is a configuration error, not a pass.  Neither is a
;; resolution probe — the existing standard was already scoped this way, and
;; the floor below generalises it to the whole-suite path every per-artefact
;; `:test` alias takes rather than widening it to lanes that claim something
;; else (see the ns docstring, and `probe-flag`).
;;
;; The DEFAULT floor is 1 rather than a per-artefact calibrated number.
;; That is deliberate: 1 is the bound that can never go stale (no SUITE lane
;; will ever legitimately ship zero tests), while ~20 hand-maintained
;; per-artefact numbers would have to be re-ratcheted on ordinary churn and
;; buy detection only for a PARTIAL collapse — which no observed instance of
;; this failure class has been.  `RF2_MIN_TESTS` is there for a caller that
;; does want a calibrated bound on a specific lane.

(def ^:private min-tests-env-var
  "Environment variable naming this lane's test-count floor.  Shared with
  the CLJS node runner (`re-frame.test-quiet.shadow-node`) and the browser
  runner (`implementation/scripts/run-browser-tests.cjs`) so the floor has
  ONE name across every whole-suite lane.  Because it is one name, scope it
  to the lane you are running — a value calibrated for the ~380-file node
  build will red a single-artefact JVM suite."
  "RF2_MIN_TESTS")

(def ^:private default-min-tests
  "Floor applied when `RF2_MIN_TESTS` is unset." 1)

(defn- parse-min-tests
  "Resolve the test-count floor from `raw` (an `RF2_MIN_TESTS` value, or
  nil/blank when unset).  Returns the floor as a long, or `::invalid` when
  `raw` is present but not a non-negative integer.

  A malformed floor is `::invalid` rather than a silent fall back to the
  default: `RF2_MIN_TESTS=1O` (letter O) quietly disabling the very gate
  that catches silent non-execution would be this bug wearing a hat."
  [raw]
  (if (or (nil? raw) (str/blank? raw))
    default-min-tests
    (let [n (try (Long/parseLong (str/trim raw))
                 (catch NumberFormatException _ nil))]
      (if (and n (not (neg? n))) n ::invalid))))

(def ^:private probe-flag
  "The one flag this wrapper owns, stripped before args reach cognitect.

  A lane passes it in its `:test` alias `:main-opts` to declare that it
  claims RESOLUTION, not coverage — its deps and classpath must load, and
  zero tests is its correct outcome. The declaration lives in the artefact's
  own `deps.edn` rather than in a job definition or an env var so that
  `clojure -M:test` behaves identically in CI and on a laptop, and so the
  exemption is self-documenting where the lane is defined."
  "--probe")

(defn- split-runner-args
  "Partition `args` into this wrapper's own flags and the args to forward
  verbatim to cognitect-test-runner. Returns `[{:probe? bool} forwarded]`.

  Only `--probe` is ours; everything else — including anything unrecognised —
  is forwarded, so cognitect keeps owning its own arg contract and its own
  parse-error diagnostics."
  [args]
  [{:probe? (boolean (some #{probe-flag} args))}
   (remove #{probe-flag} args)])

(defn- resolve-min-tests!
  "`parse-min-tests` over the live environment, exiting 2 (a configuration
  error, distinct from cognitect's 1 = red) on a malformed value."
  []
  (let [raw    (System/getenv min-tests-env-var)
        parsed (parse-min-tests raw)]
    (if (= ::invalid parsed)
      (do (binding [*out* *err*]
            (println (str "ERROR: " min-tests-env-var "=" (pr-str raw)
                          " is not a non-negative integer."))
            (println (str "  " min-tests-env-var
                          " is the minimum number of tests this run must"
                          " execute; leave it unset for the default of "
                          default-min-tests "."))
            (flush))
          (System/exit 2))
      parsed)))

;; ----------------------------------------------------------------------
;; What the lane will DISCOVER (rf2-vruo9) — a file the runner cannot see
;; is not a file that passed.
;;
;; `cognitect.test-runner/test` builds its namespace set by READING each
;; source file's `(ns ...)` form:
;;
;;     (mapcat find/find-namespaces-in-dir dirs)
;;
;; and `clojure.tools.namespace.find/find-ns-decls-in-dir` is a `keep` over
;; a helper named `ignore-reader-exception` — which is precisely what it
;; does.  A file whose `(ns ...)` FORM the reader cannot read contributes NO
;; namespace, and downstream nothing can tell a file that was dropped from a
;; file that was never written.  One unescaped `"` inside the ns docstring
;; is enough.
;;
;; MEASURED on this repo, 2026-07-29.  A single stray quote in the ns
;; docstring of `implementation/core/test/re_frame/late_bind_drift_test.clj`
;; took `clojure -M:test` in `implementation/core` from 2190 tests / 11245
;; assertions to 2182 / 10571 — exactly that file's eight deftests — and it
;; printed `0 failures, 0 errors.` and exited 0.  Exit code zero, honestly
;; earned, over a suite missing a whole file.
;;
;; NOTHING ELSE SEES IT, and that is not for want of guards.  `RF2_MIN_TESTS`
;; above is a COLLAPSE detector: eight tests out of 2190 sit far inside the
;; headroom any growing lane must leave itself.  `verify_roster` in
;; `scripts/test-core-prod-gate.sh` compares the file count against the
;; namespace count but SCRAPES the `(ns ` line with `sed`, and
;; `scripts/check_test_lane_bijection.py` matches it with a regex — both
;; therefore match the APPEARANCE of a declaration rather than the
;; declaration, and both were measured green over the broken file above.
;; Reading the form is the whole point, so the check lives HERE, in the one
;; process that already has the lane's classpath and the discovery library
;; on it, rather than in a script that would have to imitate a reader.
;;
;; THE RULE, and why it is one rule rather than three.  Every source file in
;; a discovery directory must declare — readably — the namespace its own
;; path spells.  That single sentence closes every silent door:
;;
;;   * an unreadable form declares nothing, so it cannot match its path;
;;   * a file declaring somebody else's namespace is loaded by nobody:
;;     `require` resolves the DECLARED name back to a path, so whatever
;;     lives at THAT path is what runs, and this file never does;
;;   * and two files cannot both spell one namespace, because their paths
;;     differ — so the shadowing pair, where one file's tests run twice and
;;     the other's never run at all, is caught by the same comparison.
;;
;; "The discovered count equals the file count" was the other candidate and
;; it is strictly weaker: a count is satisfiable by coincidence (a file lost
;; while another gains a second declaration nets zero), it cannot NAME the
;; file it is missing, and it is implied anyway — a total, injective map
;; from files to namespaces has exactly as many namespaces as it has files.
;; It is a consequence of this rule, not a second rule.
;;
;; SCOPE.  This is the JVM lane's discovery, so it covers every artefact
;; whose `:test` alias runs this wrapper — which is every JVM artefact in
;; the repo bar `migration/from-re-frame-v1/codemod`, which deliberately
;; calls cognitect directly.  The CLJS lanes discover through shadow-cljs's
;; own indexer and are not in this rule's reach.

(def ^:private discovery-platform
  "The platform cognitect discovers under.  `cognitect.test-runner/test`
  calls the single-arity `find/find-namespaces-in-dir`, which defaults to
  `find/clj`: `.clj` + `.cljc`, read with `{:read-cond :allow :features
  #{:clj}}`.  Named rather than inlined so the guard and the runner cannot
  drift apart about what a source file even is."
  ns-find/clj)

(defn discovery-dirs
  "The directories this run will scan, or nil when `args` do not parse.

  Resolved with cognitect's OWN option spec and its OWN default —
  `(or (:dir options) #{\"test\"})` — so there is no second, hand-rolled
  reading of `-d` to disagree with the first.  Unparseable args yield nil
  and the guard stands down: cognitect prints its parse diagnostics and
  exits 1 before discovery, so at that point there is nothing to guard."
  [args]
  (let [{:keys [options errors]} (cli/parse-opts args cognitect.test-runner/cli-options)]
    (when-not (seq errors)
      (or (:dir options) #{"test"}))))

(defn- ns->path
  "The classpath-relative path `require` resolves `ns-sym` to, under `ext`."
  [ns-sym ext]
  (str (-> (name ns-sym) (str/replace "-" "_") (str/replace "." "/")) ext))

(defn- relative-path
  "`file`'s path relative to `dir`, forward-slashed on every platform."
  [^java.io.File dir ^java.io.File file]
  (-> (.relativize (.toPath dir) (.toPath file)) str (str/replace "\\" "/")))

(defn- declared-namespace
  "What `file` declares to discovery: `[ns-sym nil]`, or `[nil complaint]`
  when it declares nothing discovery can use.

  `read-file-ns-decl` THROWS on a file the reader cannot read — that is why
  `find-ns-decls-in-dir` wraps this very call in `ignore-reader-exception`,
  and the exception it discards is the one sentence saying where the file is
  broken.  Catching it here instead is the whole difference between a red an
  operator can act on and a red they have to bisect, so the reader's own
  line and column travel with the complaint."
  [^java.io.File file]
  (try
    (if-let [decl (ns-file/read-file-ns-decl file (:read-opts discovery-platform))]
      [(ns-parse/name-from-ns-decl decl) nil]
      [nil "it holds no `(ns ...)` form."])
    (catch Exception e
      (let [{:keys [line col]} (ex-data e)
            msg (or (some-> (ex-message e) str/split-lines first)
                    (.getName (class e)))]
        [nil (str "the reader cannot read it"
                  (when line (str " (line " line ", column " col ")"))
                  ": " msg)]))))

(defn- scan
  "Every source file discovery will WALK under `dirs`, one map per physical
  file: `{:path :rel :ext :declared :complaint}`.

  The files come from `find/find-sources-in-dir` — the same walk
  `find-ns-decls-in-dir` makes, before it drops anything — so what follows
  compares what discovery SAW against what discovery KEEPS, rather than
  against a file-naming convention this guard invented.

  Deduplicated by CANONICAL path, so two `-d` roots naming one directory
  contribute each file once. The same file is one file; a collision below
  has to mean two of them, or this guard would redden a lane over its
  alias spelling rather than over its sources."
  [dirs]
  (->> (for [d               (sort dirs)
             :let            [dir (io/file d)]
             ^java.io.File f (ns-find/find-sources-in-dir dir discovery-platform)
             :let  [rel       (relative-path dir f)
                    [declared complaint] (declared-namespace f)]]
         {:path      (str/replace (.getPath f) "\\" "/")
          :canonical (.getCanonicalPath f)
          :rel       rel
          :ext       (subs rel (str/last-index-of rel "."))
          :declared  declared
          :complaint complaint})
       (reduce (fn [[seen acc] {:keys [canonical] :as file}]
                 (if (contains? seen canonical)
                   [seen acc]
                   [(conj seen canonical) (conj acc file)]))
               [#{} []])
       second))

(defn- own-path-defect
  "Why `file` will not reach the runner as ITS OWN namespace, or nil.

  Either the reader cannot read its `(ns ...)` form — in which case
  discovery drops it and it contributes no namespace at all — or the name
  it declares resolves to some other file's path."
  [{:keys [rel ext declared complaint]}]
  (when (not= rel (some-> declared (ns->path ext)))
    (or complaint
        (str "it declares `" declared "`, which discovery resolves to `"
             (ns->path declared ext) "` - a different file."))))

(defn- collision-defects
  "One `[path complaint]` per file for every namespace declared by MORE
  THAN ONE of `files`, each complaint naming the others.

  Applied only to files that already spell their own path, because a file
  that does not is named by `own-path-defect` and its declaration is
  already known bad.  Two files can both spell their own path and still
  collide, which is the whole reason this rule exists beside that one:

    - `probe/x_test.clj` beside `probe/x_test.cljc`, each resolving under
      its OWN extension;
    - one relative path repeated under two `-d` roots.

  Neither is two suites.  Discovery returns the name once PER FILE, so
  `require` loads whichever the classpath resolves and treats the rest as
  already loaded, and `run-tests` is then handed the same symbol twice:
  one file runs twice while the other never runs at all.  The tally goes
  UP, not down, so neither the summary nor the `RF2_MIN_TESTS` floor can
  see the substitution (rf2-vruo9)."
  [files]
  (for [[declared group] (group-by :declared files)
        :when            (next group)
        :let             [paths (sort (map :path group))]
        {:keys [path]}   group]
    [path (str "it declares `" declared "`, which is also declared by "
               (str/join ", " (map #(str "`" % "`") (remove #{path} paths)))
               ". Discovery returns that name once per file, so `require`"
               " loads only the file the classpath resolves and the"
               " other(s) never run.")]))

(defn discovery-defects
  "Every source file under `dirs` that will NOT reach the runner as its own
  namespace, as `[path complaint]` pairs sorted by path.

  Empty is the only acceptable answer.  Two rules, both required: a file
  must declare a namespace that resolves to ITS OWN path
  (`own-path-defect`), and no two files may declare the SAME one
  (`collision-defects`).  The first alone leaves the shadowing door open,
  because it derives each file's extension from that file and so clears a
  `.clj`/`.cljc` pair — and a repeated relative path under two roots —
  where each half spells its own path perfectly and only one of them ever
  loads."
  [dirs]
  (let [files (scan dirs)]
    (vec
      (sort
        (concat (for [f files :let [complaint (own-path-defect f)] :when complaint]
                  [(:path f) complaint])
                (collision-defects (remove own-path-defect files)))))))

(defn- verify-discovery!
  "Refuse the run when any file in this lane's discovery directories will
  not reach the runner as its own namespace (rf2-vruo9).

  Called before `-main` rebinds anything, so the diagnostic goes straight
  to the real stderr rather than into the red-replay ring, and exits 1
  before a single test runs.  ASCII only: this text goes through the
  platform-default stderr encoding, where an em dash renders as a
  replacement character on a Windows console.  Silent when clean."
  [args]
  (when-let [dirs (discovery-dirs args)]
    (when-let [defects (seq (discovery-defects dirs))]
      (binding [*out* *err*]
        (println (str "\n[test-quiet] ERROR: " (count defects) " file(s) under "
                      (pr-str (vec (sort dirs)))
                      " will not reach the runner as their own namespace:"))
        (doseq [[path complaint] defects]
          (println (str "  " path))
          (println (str "      " complaint)))
        (println (str "cognitect.test-runner discovers a namespace by READING each"
                      " file's `(ns ...)` form,\n"
                      "once per file, and both ways that can go wrong end in a"
                      " green run:\n\n"
                      "  - a file it CANNOT READ is dropped silently"
                      " (clojure.tools.namespace.find/find-ns-decls-in-dir\n"
                      "    is a `keep` over `ignore-reader-exception`), so it"
                      " contributes no namespace and its\n"
                      "    tests simply do not run. Measured: one stray quote in"
                      " an ns docstring took\n"
                      "    implementation/core from 2190 tests to 2182, green.\n"
                      "  - two files declaring ONE namespace yield that name"
                      " TWICE. `require` loads whichever\n"
                      "    the classpath resolves and skips the rest as already"
                      " loaded, then `run-tests` gets the\n"
                      "    symbol twice: one file runs twice, the other never"
                      " runs, and the tally goes UP - so\n"
                      "    neither the summary nor the RF2_MIN_TESTS floor can"
                      " see the substitution.\n\n"
                      "Either way the suite still prints `0 failures, 0 errors.`"
                      " and exits 0. Fix the file, or\n"
                      "move it out of the discovery directory (rf2-vruo9).\n"))
        (flush))
      (System/exit 1))))

(defn- install-summary-method!
  "Install `f` as `clojure.test/report`'s `:summary` method, or — when `f`
  is nil — remove any installed method so the multimethod falls back to its
  default. Centralises the `MultiFn` mutation (and its type hint) that
  `-main` uses to install this invocation's replay method and to restore the
  prior one."
  [f]
  (if f
    (.addMethod ^clojure.lang.MultiFn clojure.test/report :summary f)
    (remove-method clojure.test/report :summary)))

(defn- make-summary-replay-method
  "Build the `clojure.test/report :summary` method for ONE `-main`
  invocation. On a RED run it replays `stderr-ring` to `original-err`, then
  delegates to `prior-summary-method` (the `:summary` method installed
  before this invocation) so the canonical summary line still prints, and
  finally enforces this lane's claim — the `min-tests` floor for a suite
  lane, or the zero-test expectation for a `--probe` lane (see the ns
  docstring and `parse-min-tests`).

  `:summary` is where both belong because it is the one place that
  holds BOTH the executed-test count and control before cognitect exits.
  clojure.test's `run-tests` computes its summary map, calls `do-report` on
  it, and RETURNS that same map to cognitect, which derives the process exit
  code from `(zero? (+ fail error))` — a tally a 0-test run satisfies.  A
  reporter cannot amend the returned map, so a sub-floor run exits 1 from
  here directly, mirroring how the CLJS node runner's `:end-run-tests`
  reporter owns `js/process.exit`.  The floor is checked AFTER the delegate
  prints, so the operator sees the real `Ran N tests …` tally above the
  explanation.  The stdout flush hook `-main` registers runs on this exit,
  so nothing buffered in the banner-filtering writer is lost.

  `:summary` is the JVM-side counterpart to the CLJS `:end-run-tests`
  reporter: it fires once at the end of `run-tests` from the same
  fail/error counts cognitect reads to compute the exit code, and it runs
  on the test-driver thread inside the `binding` that rebinds `*err*`, so it
  runs before cognitect's `System/exit` and can flush the captured context.
  A run is red iff `(pos? (+ fail error))`, matching cognitect's
  `(zero? (+ fail error))` green test.

  This method is INVOCATION-SCOPED, not a permanent global override: `-main`
  installs it via `install-summary-method!` before the delegated run and
  RESTORES `prior-summary-method` on every returning/throwing path (its `finally`). A
  returning `-main` — notably `-H` help, or an embedded/REPL/in-process
  call — therefore leaves no global closure over this run's ring/`original-err`
  installed for a later, unrelated `clojure.test` run to trip over, and
  repeated invocations never chain wrapper-over-wrapper.

  The replay is bounded to the test-run red path. cognitect only fires
  `:summary` once it has reached `run-tests`, so the buffered stderr is
  replayed only for a red run that actually executed tests.  The non-test
  exit-1 path — a CLI parse error (`cognitect.test-runner/-main` prints the
  parse diagnostics + usage and `System/exit`s 1 BEFORE ever calling
  `run-tests`) — never fires `:summary`, so its buffer is dropped, not
  replayed.  That is sound: on the parse-error path the diagnostics cognitect
  emits go to `*out*` (the banner-filtering writer), NOT `*err*`, so nothing
  diagnostic is lost — the only thing dropped is whatever incidental
  `*err*`/`System.err` noise preceded the parse failure (in practice none;
  arg parsing does not log to stderr).

  The replay goes to `original-err` — the ORIGINAL stderr writer captured before
  the `*err*` ring was bound — so it is never fed back into the buffer. Each
  captured chunk is written verbatim under a red-run header, mirroring the
  CLJS replay's `[test-quiet]` prefix.  On green the buffer is dropped."
  [^StringBuilder stderr-ring
   ^java.io.Writer original-err
   prior-summary-method
   {:keys [probe? min-tests]}]
  (fn summary-replay [summary]
    (let [{:keys [fail error]} summary]
      (when (pos? (+ (or fail 0) (or error 0)))
        ;; Snapshot `stderr-ring` under its monitor — the SAME lock every
        ;; writer in `buffering-stderr-writer` takes — so the `.length`
        ;; guard and the `.toString` copy observe a consistent ring rather
        ;; than a mutation half-applied by a still-live background writer.
        ;; Copy out under the lock, then do the (unbounded) original-err replay
        ;; I/O OUTSIDE it so a writer is never blocked on the replay. An
        ;; empty ring yields `nil` and replays nothing, exactly as the prior
        ;; `(pos? (.length stderr-ring))` guard did.
        (let [captured-stderr (locking stderr-ring
                                (when (pos? (.length stderr-ring))
                                  (.toString stderr-ring)))]
          (when captured-stderr
            (.write original-err
                    (str "\n[test-quiet] buffered stderr replayed"
                         " because the run was RED:\n"))
            (.write original-err captured-stderr)
            (when-not (.endsWith captured-stderr "\n")
              (.write original-err "\n"))
            (.flush original-err)))))
    ;; Delegate to the prior :summary so the canonical
    ;; "Ran N tests…/K failures, J errors." line still prints.
    (when prior-summary-method
      (prior-summary-method summary))
    ;; Then this lane's own claim (rf2-qqzmf). A suite lane claims coverage
    ;; and must prove it RAN; a probe lane claims resolution and must prove
    ;; it resolved WITHOUT running. Diagnostics are ASCII only: they go
    ;; through the platform-default stderr encoding, where an em dash renders
    ;; as a replacement char on a Windows console.
    (let [test-count (or (:test summary) 0)
          exit-for-claim-mismatch!
          (fn [message]
            (.write original-err (str "\n[test-quiet] ERROR: " message))
            (.flush original-err)
            (System/exit 1))]
      (cond
        ;; A probe reaching this hook has already proved what it claims:
        ;; deps resolved and the discovery dirs were scanned. All that is
        ;; left is that it really is a probe.
        probe?
        (when (pos? test-count)
          (exit-for-claim-mismatch!
            (str "this lane is declared a classpath probe (" probe-flag
                      ") but executed " test-count " test(s).\n"
                      "A lane with tests claims COVERAGE, not resolution:"
                      " drop " probe-flag " from its `:test` alias"
                      " `:main-opts`\n"
                      "so the test-count floor applies to it (rf2-qqzmf).\n")))

        (< test-count min-tests)
        (exit-for-claim-mismatch!
          (str "this run executed " test-count
                    " test(s), below the floor of " min-tests
                    " (" min-tests-env-var ").\n"
                    "A suite lane that discovered no tests is a"
                    " configuration error: a `-r`/`-n` selector matching"
                    " nothing, a `:test` alias\n"
                    "missing its `:extra-paths [\"test\"]`, a renamed test"
                    " file. It is not a pass. Failing the run"
                    " (rf2-qqzmf).\n"
                    "If this lane claims only that its classpath RESOLVES,"
                    " declare that with " probe-flag " instead.\n"))))))

(def ^:dynamic *register-flush-hook!*
  "Test seam over the JVM shutdown-hook registry for `-main`'s stdout
  flush-on-exit hook.  Called with the hook `Thread`; it must register the
  hook and RETURN a 0-arg deregister fn that `-main` invokes on every
  returning/throwing path.  Deregistering matters because a returning `-main`
  (help, or an embedded/in-process call) does NOT terminate the JVM: without
  removal, each such invocation would leave its filtering-writer hook
  registered until shutdown, accumulating one per call.

  Defaults to the real `Runtime` registry.  Tests rebind it to observe the
  add/remove lifecycle in-process without mutating the test JVM's own
  shutdown-hook set."
  (fn [^Thread hook]
    (.addShutdownHook (Runtime/getRuntime) hook)
    (fn deregister-flush-hook []
      ;; `removeShutdownHook` throws only while the JVM is already shutting
      ;; down; a returning `-main` never is, so this is safe. It returns
      ;; false (harmless) if the hook already ran or was removed.
      (.removeShutdownHook (Runtime/getRuntime) hook))))

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
  ;; We also bind the test-driver thread's `*err*` and swap process-global
  ;; `System/err` to a bounded ring buffer. Expected warnings are captured
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
  ;; `make-summary-replay-method`).
  (let [;; This lane's claim, resolved BEFORE anything is rebound so a
        ;; malformed floor is a plain stderr diagnostic + exit 2, not a
        ;; buffered one (rf2-qqzmf). `--probe` is ours and is stripped from
        ;; what cognitect sees.
        [{:keys [probe?]} forwarded-args] (split-runner-args args)
        lane-claim {:probe? probe? :min-tests (resolve-min-tests!)}
        ;; And what this lane will DISCOVER, resolved from the same args
        ;; cognitect is about to parse (rf2-vruo9). Also before anything is
        ;; rebound, and before a single test runs: a file the reader cannot
        ;; read is invisible to discovery, so no later hook — not the floor,
        ;; not the summary — has anything left to notice.
        _          (verify-discovery! forwarded-args)
        original-out        *out*
        original-err        *err*
        original-system-err System/err
        filtered-stdout     (java.io.PrintWriter.
                              (banner-filtering-writer original-out))
        stderr-ring         (StringBuilder.)
        buffered-stderr-writer (buffering-stderr-writer stderr-ring)
        buffered-stderr     (java.io.PrintWriter. buffered-stderr-writer)
        ;; The flush-on-`System/exit` hook for the stdout filtering writer,
        ;; registered through the `*register-flush-hook!*` seam. Its
        ;; `deregister-flush-hook!` is called on every returning/throwing path
        ;; so repeated in-process/help invocations don't accumulate a
        ;; filtering-writer hook apiece.
        stdout-flush-hook (Thread. ^Runnable #(.flush filtered-stdout))
        deregister-flush-hook! (*register-flush-hook!* stdout-flush-hook)
        ;; Capture the `:summary` method installed before us, then install
        ;; THIS invocation's replay method. The override is invocation-scoped:
        ;; we DELEGATE to `prior-summary-method` during the run and restore it
        ;; (see the `finally`), never leaving a global closure over this run's
        ;; ring/`original-err` behind.
        prior-summary-method (get-method clojure.test/report :summary)
        summary-method (make-summary-replay-method
                         stderr-ring
                         original-err
                         prior-summary-method
                         lane-claim)]
    (install-summary-method! summary-method)
    ;; Route raw `System/err` bytes into the same ring as `*err*` so a
    ;; library that writes `System.err` directly is buffered too. Each chunk
    ;; is decoded to a `String` and appended to the ring (this buffer is the
    ;; diagnostic/red-replay path only — never the green-run summary). Note
    ;; the encoding asymmetry between the two `write` proxy arities:
    ;;   - write(byte[],off,len): a chatty library prints via
    ;;     `PrintStream.print(...)`/`write(byte[])`, which the autoFlush
    ;;     UTF-8 `PrintStream` below routes through this multi-byte arity. The
    ;;     chunk is decoded as one unit using the JVM's default charset, so
    ;;     non-ASCII fidelity depends on that default also being UTF-8.
    ;;   - write(int): the single-byte arity decodes that ONE byte on its
    ;;     own. A multi-byte UTF-8 sequence delivered one byte at a time —
    ;;     only a raw `System.err.write(int)` per byte hits this path; the
    ;;     `PrintStream` itself never splits a code point across single-byte
    ;;     writes — would decode each byte separately and mangle the
    ;;     character. This arity is therefore best-effort / ASCII-only by
    ;;     design: it preserves byte fidelity for the common ASCII diagnostic
    ;;     case and tolerates (does not guarantee) multi-byte text on the
    ;;     pathological raw per-byte path. Buffering raw bytes to decode once
    ;;     at replay is not a clean fit here — the ring is a shared char
    ;;     `Writer` that `*err*` also writes into directly, so there is no
    ;;     single byte stream to defer-decode.
    (let [system-err-bridge (proxy [java.io.OutputStream] []
                       (write
                         ([byte-value]
                          ;; proxy dispatches by arity; the 1-arg form is
                          ;; write(int) — the low 8 bits are the byte (0-255),
                          ;; so use `unchecked-byte` (a plain `byte` cast
                          ;; throws for 128-255). Best-effort/ASCII (see above).
                          (.write buffered-stderr-writer
                                  (String.
                                    (byte-array
                                      [(unchecked-byte byte-value)]))))
                         ([byte-buffer offset length]
                          (.write buffered-stderr-writer
                                  (String. ^bytes byte-buffer
                                           (int offset)
                                           (int length))))))]
      (System/setErr (java.io.PrintStream. system-err-bridge true "UTF-8")))
    (binding [*out* filtered-stdout
              *err* buffered-stderr]
      (try
        (apply cognitect.test-runner/-main forwarded-args)
        (finally
          (.flush filtered-stdout)
          ;; Returning paths (notably help) restore System/err + the prior
          ;; `:summary` reporter and deregister the flush hook. `System/exit`
          ;; paths terminate WITHOUT running this `finally`, which is correct:
          ;; the summary replay already fired during the run, and the flush
          ;; hook must survive to flush stdout at shutdown.
          (System/setErr original-system-err)
          ;; Restore the prior `:summary` method — but ONLY if ours is still
          ;; the installed one. If an unrelated run replaced it during this
          ;; invocation, that run now owns the method and must not be
          ;; clobbered by a blind restore.
          (when (identical? summary-method
                            (get-method clojure.test/report :summary))
            (install-summary-method! prior-summary-method))
          (deregister-flush-hook!))))))
