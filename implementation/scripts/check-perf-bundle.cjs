#!/usr/bin/env node
/*
 * Performance-API bundle-isolation / bundle-presence verifier
 * (Spec 009 §Performance instrumentation, bead rf2-du3i).
 *
 * Asserts the production-elision contract for the perf flag, the dual
 * of scripts/check-elision.cjs:
 *
 *   1. The default counter bundle (`:examples/counter` — `:advanced`,
 *      `re-frame.performance/enabled? false` — the implicit goog-define
 *      default) does NOT contain `performance.mark` / `performance.measure`
 *      strings or the `re-frame.performance` namespace fragment. This is
 *      the bundle-isolation proof: shipped binaries with the perf flag
 *      off carry zero User-Timing instrumentation.
 *
 *   2. The perf-on counter bundle (`:examples/counter-perf` — `:advanced`,
 *      `re-frame.performance/enabled? true`) DOES contain those strings.
 *      Without the perf-on bundle, the bundle-isolation assertion would
 *      be vacuous: a refactor that *moved* the strings would silently
 *      turn the negative grep into a false pass.
 *
 * Strategy: grep, not parse. The closure compiler may rename symbols
 * but does not rewrite string literals. Matching `performance.mark`
 * proves the JS-interop call site (from `(.mark js/performance ...)`)
 * survived; matching the bracketed entry-name shape `rf:` proves the
 * helper's name-building survived too.
 *
 * Exit 0 on PASS, 1 on FAIL.
 */

'use strict';

const path = require('path');
const { createGateReporter } = require('./lib/gate-report.cjs');
const { readReleaseBlob } = require('./lib/read-release-bundle.cjs');

const ROOT = path.resolve(__dirname, '..');
const report = createGateReporter();

// ----- the perf-flag elision contract ---------------------------------------

// Each sentinel is a string fragment that appears ONLY when the perf
// flag is on at compile time AND the namespace's call sites are reached.
// If any of these appear in the OFF bundle, the (if performance/enabled?
// ...) bracket survived dead-code elimination — the perf elision contract
// is broken. If any are MISSING from the ON bundle, the strings have
// moved and the negative assertion is now vacuous.
//
// The first three are the JS-interop strings the helper emits via
// `(.mark js/performance ...)` / `(.measure js/performance ...)`; the
// fourth is the namespace fragment (load-order proof — the ns is in the
// bundle but every body-form should DCE on the OFF build).
const PERF_SENTINELS = [
  { source: 're-frame.performance/mark-and-measure (performance.mark)',
    sentinel: 'performance.mark' },
  { source: 're-frame.performance/mark-and-measure (performance.measure)',
    sentinel: 'performance.measure' },
  { source: 're-frame.performance/build-name (rf: name prefix)',
    sentinel: '"rf:' },
];

// ----- helpers ---------------------------------------------------------------

// Bundle reading is shared with the sibling check-* scripts via
// scripts/lib/read-release-bundle.cjs (rf2-qlk4w). Top-level *.js
// only; a stale dev-build `cljs-runtime/` subdir is skipped.

function checkBundle(label, bundlePath, mustContain) {
  const blob = readReleaseBlob(bundlePath);
  if (blob == null) {
    console.error(`[perf-bundle] ${label}: bundle path missing — ${bundlePath}`);
    console.error('              Did you run the matching shadow-cljs release?');
    return { ok: false, checked: 0, passed: 0, bytes: null, missing: true };
  }
  report.detail(`[perf-bundle] ${label}: ${bundlePath}`);
  report.detail(`              bundle size: ${blob.length} chars`);

  let ok = true;
  let passedCount = 0;
  for (const { source, sentinel } of PERF_SENTINELS) {
    const present = blob.includes(sentinel);
    const expected = mustContain ? 'PRESENT' : 'ABSENT';
    const actual   = present     ? 'PRESENT' : 'ABSENT';
    const passed   = present === mustContain;
    const tag      = passed ? 'OK' : 'FAIL';
    report.detail(`              [${tag}] ${source}: sentinel ${JSON.stringify(sentinel)} expected ${expected}, was ${actual}`);
    if (passed) passedCount += 1;
    if (!passed) ok = false;
  }
  return {
    ok,
    checked: PERF_SENTINELS.length,
    passed: passedCount,
    bytes: blob.length,
    bundlePath,
    missing: false,
  };
}

// Count `performance.mark|performance.measure|re-frame.performance` for
// the report. The PR body wants the raw count number for both bundles,
// per the bead's bundle-grep contract.
function countOccurrences(blob, patterns) {
  if (blob == null) return null;
  const re = new RegExp(patterns.join('|'), 'g');
  const m  = blob.match(re);
  return m ? m.length : 0;
}

// ----- main ------------------------------------------------------------------

function main() {
  report.detail('=== Performance-API bundle isolation / presence (Spec 009 §Performance instrumentation) ===');

  const offDir = path.join(ROOT, 'out', 'examples', 'counter');
  const onDir  = path.join(ROOT, 'out', 'examples', 'counter-perf');

  // OFF bundle: sentinels MUST be absent.
  const off = checkBundle('perf-off (default counter, enabled?=false)',
                          offDir, false);

  // ON bundle: sentinels MUST be present.
  const on  = checkBundle('perf-on  (counter-perf,  enabled?=true) ',
                          onDir,  true);

  // Report the counts the bead asks for.
  const offBlob = readReleaseBlob(offDir);
  const onBlob  = readReleaseBlob(onDir);
  const patterns = ['performance\\.mark',
                    'performance\\.measure',
                    're-frame\\.performance'];
  const offCount = countOccurrences(offBlob, patterns);
  const onCount  = countOccurrences(onBlob,  patterns);
  report.detail('');
  report.detail('=== Bundle-grep counts ===');
  report.detail(`  perf-off counter (must be 0):     ${offCount}`);
  report.detail(`  perf-on  counter (must be > 0):   ${onCount}`);

  const countsOk = (offCount === 0) && (onCount > 0);

  if (off.ok && on.ok && countsOk) {
    report.pass(
      'perf-bundle',
      `off-count=${offCount}; on-count=${onCount}; off=${offDir} (${off.bytes} chars); ` +
        `on=${onDir} (${on.bytes} chars)`
    );
    process.exit(0);
  } else {
    report.flushDetails();
    console.error('=== FAIL ===');
    if (!off.ok || offCount !== 0) {
      console.error('Perf-off bundle isolation broke: a Performance API call');
      console.error('site or the re-frame.performance ns survived advanced');
      console.error('compilation with re-frame.performance/enabled?=false.');
      console.error('Per Spec 009 §Performance instrumentation, the bracket');
      console.error('site must collapse to (f) so DCE removes the gated body.');
    }
    if (!on.ok || !(onCount > 0)) {
      console.error('Perf-on bundle missing expected sentinels — the grep');
      console.error('test would be vacuous. The helper or its call sites');
      console.error('may have been refactored without updating sentinels.');
    }
    process.exit(1);
  }
}

main();
