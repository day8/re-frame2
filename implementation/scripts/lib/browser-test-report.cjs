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
// isVerboseTests(env) is the RF2_VERBOSE_TESTS=1 escape hatch shared
// with lib/gate-report.cjs.

const RAN_RE = /Ran\s+(\d+)\s+tests?\s+containing\s+(\d+)\s+assertions?\./;
const FAIL_RE = /(\d+)\s+failures?,\s*(\d+)\s+errors?\.?/;

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
  isVerboseTests,
  summaryPartsFromText,
  parseFailureCounts,
  parseRanCounts,
  formatCompactSummary,
  createDiagnosticBuffer,
};
