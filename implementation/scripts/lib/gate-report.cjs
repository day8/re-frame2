// Shared gate-reporter for the check-* JS gate scripts (#1197).
//
// A gate's normal output is exactly one `PASS <label>` line; the noisy
// per-check detail is buffered, not printed, unless either (a)
// RF2_VERBOSE_TESTS=1, or (b) the gate fails and the caller calls
// flushDetails() to dump the buffer (to stderr by default) for triage.
// This keeps a green CI run to one line per gate while preserving the
// full diagnostic trail on red.
//
// createGateReporter(opts) → { detail, flushDetails, pass, isVerbose,
//                              bufferedLineCount }
//   detail(line)        buffer a detail line (printed immediately when
//                       verbose; multi-line strings are split).
//   flushDetails({stream}) emit the buffered lines (no-op when verbose,
//                       since they were already printed) and clear them.
//   pass(label, summary)   print the one-line `PASS <label>[: <summary>]`.
// `verbose` defaults to isVerboseTests(env); stdout/stderr are injectable
// for the unit tests (_gate-report.test.cjs).

'use strict';

const { isVerboseTests } = require('./browser-test-report.cjs');

function splitLines(text) {
  return String(text == null ? '' : text).replace(/\r\n/g, '\n').split('\n');
}

function createGateReporter({
  env = process.env,
  verbose = isVerboseTests(env),
  stdout = (line) => console.log(line),
  stderr = (line) => console.error(line),
} = {}) {
  const buffered = [];

  function detail(line = '') {
    for (const part of splitLines(line)) {
      if (verbose) stdout(part);
      else buffered.push(part);
    }
  }

  function flushDetails({ stream = stderr } = {}) {
    if (verbose) return;
    for (const line of buffered) stream(line);
    buffered.length = 0;
  }

  function pass(label, summary) {
    const suffix = summary ? `: ${summary}` : '';
    stdout(`PASS ${label}${suffix}`);
  }

  return {
    detail,
    flushDetails,
    pass,
    isVerbose: () => verbose,
    bufferedLineCount: () => buffered.length,
  };
}

module.exports = {
  createGateReporter,
  isVerboseTests,
};
