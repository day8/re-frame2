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
