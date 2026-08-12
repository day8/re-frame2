// Shared cljs.test summary-parsing + diagnostic-buffering for the
// browser-test runners (#1196).
//
// cljs.test's `Ran N tests containing M assertions.` / `K failures, L
// errors.` lines arrive interleaved with arbitrary browser-console
// noise (app logs, `[browser:log]`-prefixed forwards, …). The RegExps
// below pluck just those two summary lines out of the stream so a
// runner can print a one-line compact result and decide pass/fail from
// the failure/error counts — without echoing the whole noisy log on a
// green run.
//
//   summaryPartsFromText(text)  → { ran, failErr } — the LAST complete
//                                  cljs.test summary PAIR in the stream:
//                                  a `Ran ...` line plus the
//                                  `failures, errors` line that FOLLOWS
//                                  it. Returned as an atomic pair so a
//                                  prelude `0 failures, 0 errors.` app
//                                  log that PRECEDES the real `Ran ...`
//                                  line can never be mis-paired as the
//                                  failure summary (rf2-mwx08).
//   parseFailureCounts(failErr) → { failures, errors } or null. A gate
//                                  is green only when both are 0 AND a
//                                  `Ran ...` line was seen.
//   parseRanCounts(ran)         → { tests, assertions } or null — the two
//                                  integers RAN_RE already captures. A
//                                  gate needs them because `Ran 0 tests`
//                                  with a clean tally is a lane that
//                                  discovered nothing, not a pass
//                                  (rf2-qqzmf).
//   formatCompactSummary(parts) → the one-line `<label>: <ran> <failErr>`.
//   createDiagnosticBuffer()    → an ordered stdout/stderr line buffer
//                                  flushed verbatim (with stream routing)
//                                  only when the runner needs the trail.
//   findFixtureAbort(errors)    → the page error that ENDED the run, or null
//                                  (rf2-u0j8).
//   testingNamespaces(lines)    → the namespaces cljs-test-display announced,
//                                  in order — the trail's own answer to "how
//                                  far did the run get?" (rf2-u0j8).
//   classifyTrustedInputRequest(descriptor)
//                               → what the page published on the trusted-input
//                                  bridge: nothing, a servable request, or one
//                                  the runner cannot answer (rf2-il7b).
// isVerboseTests(env) is the RF2_VERBOSE_TESTS=1 escape hatch shared
// with lib/gate-report.cjs.

const RAN_RE = /Ran\s+(\d+)\s+tests?\s+containing\s+(\d+)\s+assertions?\./;
const FAIL_RE = /(\d+)\s+failures?,\s*(\d+)\s+errors?\.?/;

// rf2-u0j8 — the one uncaught page error that is TERMINAL for the whole run.
//
// cljs.test refuses `async` rows in a namespace whose fixtures are POSITIONAL
// (`(use-fixtures :once (fn [f] … (f)))`) rather than maps. `execution-strategy`
// picks `:sync` for a namespace whose fixtures are all fns, and the `:sync`
// branch wraps every test body in `disable-async`, which throws `::async-disabled`
// the moment a body returns an async object. `test-var-block*` catches that and
// rethrows a bare STRING:
//
//     ::async-disabled (throw "Async tests require fixtures to be specified as
//                              maps.  Testing aborted.")
//
// Nothing catches it after that. `cljs.test/run-block` has no try/catch, and
// shadow.test runs the ENTIRE lane — every namespace block plus the closing
// `:summary` / `:end-run-tests` reports — inside ONE `run-block`. So the throw
// unwinds the whole lane: every namespace sorted after the offender never
// executes, and the `Ran N tests containing M assertions.` line the runner
// waits for is never printed.
//
// Two independent substrings of the same sentence, so a reword upstream has to
// take out both before this stops matching. It does NOT have to keep matching
// for the runner to stay correct: the abort still produces a `pageerror` and no
// summary, which the generic no-summary-with-pageerror arm reports on its own.
// This matcher only buys IMMEDIACY (fail in seconds instead of burning the
// whole BROWSER_TEST_TIMEOUT_MS budget) and the named remedy.
const FIXTURE_ABORT_RE = /Testing aborted|fixtures to be specified as maps/;

// The `Testing <ns>` line cljs-test-display prints at every `:begin-test-ns`
// when `cljs-test-display.core/printing` is true (the `:browser-test` build
// sets it via `:closure-defines`). Anchored to the start of a line because
// that is where the reporter's own `(println "\nTesting" ns)` puts it.
const TESTING_NS_RE = /^Testing\s+([^\s]+)\s*$/;

function isVerboseTests(env = process.env) {
  return env.RF2_VERBOSE_TESTS === '1';
}

// Extract the cljs.test summary as an ATOMIC, ORDERED pair rather than
// two independent first-matches (rf2-mwx08). cljs.test always emits the
// `Ran N tests ...` line FIRST and the `K failures, L errors.` line
// IMMEDIATELY AFTER it. Arbitrary browser-console noise — including a
// prior app log shaped exactly like a zero-failure summary
// (`0 failures, 0 errors.`) — may be interleaved before the real run.
//
// The previous implementation took the first `Ran ...` match and the
// first `failures, errors` match from the whole blob independently, so a
// preceding noise line could be accepted as the failure summary and then
// paired with the later real `Ran ...` line — false-greening a red run.
//
// We instead walk the lines, and for each `Ran ...` line we look for the
// FIRST `failures, errors` line that FOLLOWS it (before the next
// `Ran ...`). That candidate pair is the summary for that run; we keep
// the LAST such complete pair (the most recent run wins if a page emits
// more than one cljs.test summary). A `failures, errors` line with no
// preceding `Ran ...` is ignored — it cannot be a cljs.test summary.
function summaryPartsFromText(text) {
  const value = text == null ? '' : String(text);
  const lines = value.split('\n');

  let pendingRan = null; // a `Ran ...` line awaiting its failures/errors pair
  let pairedRan = null;
  let pairedFailErr = null;

  for (const line of lines) {
    const ranMatch = line.match(RAN_RE);
    if (ranMatch) {
      // A new run begins. Any earlier un-paired `Ran ...` is abandoned
      // (its failures/errors line never arrived before this next run).
      pendingRan = ranMatch[0];
      continue;
    }
    if (pendingRan == null) {
      // No `Ran ...` seen yet on this run — a bare `failures, errors`
      // line here is console noise, not a cljs.test summary. Ignore it.
      continue;
    }
    const failMatch = line.match(FAIL_RE);
    if (failMatch) {
      // Complete pair: the failures/errors line that follows the most
      // recent `Ran ...`. Record it as the latest complete summary and
      // reset so a subsequent run can form its own pair.
      pairedRan = pendingRan;
      pairedFailErr = failMatch[0];
      pendingRan = null;
    }
  }

  // If a `Ran ...` line was seen but its failures/errors line has not
  // arrived yet (still streaming), surface the `ran` half so callers can
  // report a meaningful timeout. `failErr` stays null until the matching
  // line lands — which is exactly the "no green verdict yet" signal.
  return {
    ran: pairedRan != null ? pairedRan : pendingRan,
    failErr: pairedFailErr,
  };
}

function parseFailureCounts(failErr) {
  const match = failErr && String(failErr).match(FAIL_RE);
  if (!match) return null;
  return {
    failures: parseInt(match[1], 10),
    errors: parseInt(match[2], 10),
  };
}

// The `Ran N tests containing M assertions.` half of the summary, as the
// integers it already carries (rf2-qqzmf). RAN_RE has always captured both;
// nothing read them, so every browser lane derived its verdict from the
// failure tally alone and a lane that ran NOTHING was green.
function parseRanCounts(ran) {
  const match = ran && String(ran).match(RAN_RE);
  if (!match) return null;
  return {
    tests: parseInt(match[1], 10),
    assertions: parseInt(match[2], 10),
  };
}

// rf2-u0j8: the first captured page error that is the cljs.test fixture abort,
// or null. Kept pure (and separate from the runner) so the classification is
// unit-testable without launching a browser.
function findFixtureAbort(errors) {
  if (!errors) return null;
  for (const err of errors) {
    if (err != null && FIXTURE_ABORT_RE.test(String(err))) return String(err);
  }
  return null;
}

// rf2-u0j8: the namespaces the run announced, in order. `lines` may be raw
// console texts (which carry embedded newlines) or already-split lines; both
// are handled. The LAST entry is the namespace that was executing when a
// terminal error fired, and the COUNT is how far the lane got — the two numbers
// that turn "the run stopped" into "the run stopped HERE, and N namespaces
// after it never ran".
function testingNamespaces(lines) {
  const out = [];
  if (!lines) return out;
  for (const raw of lines) {
    if (raw == null) continue;
    for (const line of String(raw).replace(/\r\n/g, '\n').split('\n')) {
      const match = line.match(TESTING_NS_RE);
      if (match) out.push(match[1]);
    }
  }
  return out;
}

// rf2-il7b — the trusted-input bridge's one classification, kept PURE and
// out of the runner for the reason `findFixtureAbort` is: the runner's own
// half needs a live Chromium, this half does not, and the case that matters
// is the one a healthy tree never reaches.
//
// The page publishes `{token, keys, ack}` on a well-known global and then
// AWAITS `ack`. So a request the runner cannot answer is not a nuisance —
// it is TERMINAL. There is no ack to send (no token to match it against, or
// no function to send it to), the row never resumes, the lane never reaches
// its summary, and the whole BROWSER_TEST_TIMEOUT_MS budget burns down to
// "Timed out ... waiting for cljs.test summary" — which names the wait
// rather than the cause, exactly the fail-slow shape rf2-u0j8 fixed for the
// cljs.test abort.
//
// `descriptor` is what the in-page probe reports about the published value:
// deliberately a flat, serialisable summary rather than the value itself,
// because `ack` is a function and cannot cross the boundary. The runner
// reads the shape; this function decides what the shape MEANS.
//
//   { present: false }                    → 'idle'      (nothing published)
//   { present: true, token, keys, ack }   → 'ready'     (servable)
//   anything else                         → 'malformed' (terminal, with a reason)
function classifyTrustedInputRequest(descriptor) {
  if (descriptor == null || descriptor.present !== true) return { kind: 'idle' };

  const bad = (reason) => ({ kind: 'malformed', reason });

  if (typeof descriptor.token !== 'string' || descriptor.token === '') {
    return bad(
      `its \`token\` is ${descriptor.tokenType || typeof descriptor.token}, not a ` +
        'non-empty string. The token is what pairs an acknowledgement with the ' +
        'request that is waiting for it, so there is no way to answer this one.',
    );
  }
  if (!Array.isArray(descriptor.keys)) {
    return bad(
      `its \`keys\` is ${descriptor.keysType || typeof descriptor.keys}, not an ` +
        'array. The runner presses the keys in that array and has nothing to press.',
    );
  }
  if (descriptor.ackType !== 'function') {
    return bad(
      `its \`ack\` is ${descriptor.ackType}, not a function. The request can be ` +
        'served but its answer has nowhere to go, so the row awaiting it never resumes.',
    );
  }
  return { kind: 'ready', token: descriptor.token, keys: descriptor.keys.map(String) };
}

function formatCompactSummary({ label = 'Browser tests', ran, failErr, source }) {
  const sourceSuffix = source ? ` (source: ${source})` : '';
  return `${label}: ${ran} ${failErr}${sourceSuffix}`;
}

function createDiagnosticBuffer() {
  const entries = [];

  const add = (line, stream = 'stdout') => {
    if (line == null) return;
    const normalized = String(line).replace(/\r\n/g, '\n');
    for (const part of normalized.split('\n')) {
      entries.push({ stream, text: part });
    }
  };

  return {
    add,
    entries: () => entries.slice(),
    isEmpty: () => entries.length === 0,
    flush: ({ stdout = console.log, stderr = console.error } = {}) => {
      for (const entry of entries) {
        if (entry.stream === 'stderr') stderr(entry.text);
        else stdout(entry.text);
      }
    },
  };
}

module.exports = {
  RAN_RE,
  FAIL_RE,
  FIXTURE_ABORT_RE,
  findFixtureAbort,
  testingNamespaces,
  classifyTrustedInputRequest,
  isVerboseTests,
  summaryPartsFromText,
  parseFailureCounts,
  parseRanCounts,
  formatCompactSummary,
  createDiagnosticBuffer,
};
