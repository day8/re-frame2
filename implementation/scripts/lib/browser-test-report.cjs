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
//   summaryPartsFromText(text)  → { ran, failErr } (each the matched
//                                  line or null).
//   parseFailureCounts(failErr) → { failures, errors } or null. A gate
//                                  is green only when both are 0 AND a
//                                  `Ran ...` line was seen.
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

function summaryPartsFromText(text) {
  const value = text == null ? '' : String(text);
  const ran = value.match(RAN_RE);
  const failErr = value.match(FAIL_RE);
  return {
    ran: ran ? ran[0] : null,
    failErr: failErr ? failErr[0] : null,
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
  formatCompactSummary,
  createDiagnosticBuffer,
};
