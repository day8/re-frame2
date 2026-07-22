#!/usr/bin/env node
/*
 * UIx-only bundles must NOT pull in `reagent.ratom` or
 * `reagent.impl.batching` (rf2-jicu2; resolves the rf2-ykqee
 * audit's Verdict B). Originally check-uix-helix-reagent-free.cjs;
 * the Helix arm left with the Helix adapter (S7/W13, rf2-d6epb).
 *
 * Pre-rf2-jicu2 the substrate spine (`re-frame.substrate.spine`)
 * reified `reagent.ratom/IDisposable` on its derived-value container
 * — a single require dragged ~9KB optimised / 2-3KB gzipped of
 * `reagent.ratom` + `reagent.impl.batching` into every UIx-only
 * release bundle. The spine now reifies a re-frame-owned
 * protocol (`re-frame.disposable/IDisposable`); the UIx adapter
 * drops its `reagent.core` / `reagent.ratom` requires entirely.
 *
 * This script grep-asserts that bundle for the Reagent sentinel
 * strings. The closure compiler may rename symbols under :advanced
 * but it does NOT rewrite the string literals Reagent declares via
 * `set!` on JS-interop slots (`cljsRatom`, `cljsRatomGeneration`,
 * Reagent's batching method names). If any appear in the UIx-only
 * counter bundle, the spine is dragging Reagent back in — bundle
 * isolation is broken.
 *
 * Methodology sanity check. To avoid a vacuous negative grep, the
 * same sentinels MUST appear in a Reagent-using bundle (the
 * `:examples/counter` build uses the Reagent adapter); the
 * present-check on the Reagent bundle proves the grep has signal.
 * If a future refactor displaces the sentinel strings, both the
 * Reagent present-check and the UIx absent-check go silent —
 * this script then fails fast on the present-check.
 *
 * Exit 0 on PASS, 1 on FAIL.
 */

'use strict';

const path = require('path');
const { createGateReporter } = require('./lib/gate-report.cjs');
const { classifyReleaseBundle } = require('./lib/read-release-bundle.cjs');
const { assertSentinelSet } = require('./lib/sentinel-scan.cjs');

const ROOT = path.resolve(__dirname, '..');
const report = createGateReporter();

// ----- sentinels -------------------------------------------------------------
//
// Each sentinel is a string fragment unique to `reagent.ratom` or
// `reagent.impl.batching`. The bead's measurement (rf2-ykqee) names
// those two namespaces as the dominant payload — every Reagent
// sibling that came along for the ride was transitively imported by
// one or the other.
//
//   `cljsRatom`     — set as a JS property on React components by
//                     reagent.ratom.cljs; survives :advanced because
//                     it is an interop string (`set! (.-cljsRatom
//                     component) …`), not a CLJS field.
//   `cljsIsDirty`   — interop property `reagent.impl.batching`'s
//                     `RenderQueue.run-queue` reads on each per-frame
//                     drain (`(.-cljsIsDirty c)`). Survives :advanced
//                     for the same interop-string reason. Direct
//                     evidence the batching module body is in the bundle.
const REAGENT_SENTINELS = [
  { source: 'reagent.ratom cljsRatom field (set on React component)',
    sentinel: 'cljsRatom' },
  { source: 'reagent.impl.batching cljsIsDirty (RenderQueue.run-queue interop)',
    sentinel: 'cljsIsDirty' },
];

// ----- helpers ---------------------------------------------------------------
//
// Bundle reading is shared with the sibling check-* scripts via
// scripts/lib/read-release-bundle.cjs (rf2-jkake.15); the per-sentinel
// present/absent scan loop + tally is the shared assertSentinelSet
// (scripts/lib/sentinel-scan.cjs, rf2-j552l2).

function checkBundle(label, bundlePath, mustContain) {
  const { status, blob } = classifyReleaseBundle(bundlePath);
  if (status === 'missing') {
    console.error(`[uix-reagent-free] ${label}: bundle path missing — ${bundlePath}`);
    console.error('                          Did you run the matching shadow-cljs release?');
    return { ok: false, checked: 0, passed: 0, bytes: null, missing: true };
  }
  if (status === 'empty') {
    // Non-vacuous floor (rf2-utvst): the UIx bundle is checked
    // negative-only; a present-but-empty bundle satisfies every Reagent-
    // sentinel absence check and would false-GREEN.
    console.error(`[uix-reagent-free] ${label}: bundle present but empty (zero top-level JS) — ${bundlePath}`);
    console.error('                          The release emitted no inspectable bundle; the');
    console.error('                          Reagent-absence checks would pass vacuously.');
    console.error('                          Rebuild the matching shadow-cljs release.');
    return { ok: false, checked: 0, passed: 0, bytes: 0, empty: true };
  }
  report.detail(`  ${label}: ${bundlePath}`);
  report.detail(`    bundle size: ${blob.length} chars`);

  const { ok, passed } = assertSentinelSet(blob, REAGENT_SENTINELS, {
    mustContain,
    count: true,
    emit: (line) => report.detail(line),
    formatLine: ({ source, sentinel, present, hits, tag }) => {
      const expected = mustContain ? 'PRESENT (>=1)' : 'ABSENT (0)';
      const actual   = present     ? `PRESENT (${hits})` : 'ABSENT (0)';
      return `    [${tag}] ${source}: sentinel ${JSON.stringify(sentinel)} expected ${expected}, was ${actual}`;
    },
  });
  return {
    ok,
    checked: REAGENT_SENTINELS.length,
    passed,
    bytes: blob.length,
    bundlePath,
    missing: false,
  };
}

// ----- main ------------------------------------------------------------------

function main() {
  report.detail('=== UIx-only Reagent isolation (rf2-jicu2) ===');
  report.detail('');

  const uixDir     = path.join(ROOT, 'out', 'examples', 'counter-uix');
  const reagentDir = path.join(ROOT, 'out', 'examples', 'counter');

  // Negative assertion: the new spine produces a UIx-only bundle with
  // no Reagent dependency.
  const uix   = checkBundle('UIx-only counter   (must NOT contain reagent.ratom / reagent.impl.batching)',
                            uixDir, false);
  report.detail('');
  // Positive assertion: the Reagent-using counter bundle DOES carry the
  // sentinels. Without this present-check, a sentinel-name regression
  // would silently turn the negative greps above into vacuous passes.
  const reagent = checkBundle('Reagent counter    (methodology sanity — sentinels MUST be present)',
                              reagentDir, true);

  if (uix.ok && reagent.ok) {
    const checked = uix.checked + reagent.checked;
    report.pass(
      'uix-reagent-free',
      `2 bundles checked; ${checked} sentinel checks; uix=${uixDir} (${uix.bytes} chars); ` +
        `reagent=${reagentDir} (${reagent.bytes} chars)`
    );
    process.exit(0);
  } else {
    report.flushDetails();
    console.error('');
    console.error('=== FAIL ===');
    console.error('');
    if (!uix.ok) {
      console.error('A UIx-only release bundle pulled in reagent.ratom');
      console.error('or reagent.impl.batching. Per rf2-jicu2 the substrate spine reifies');
      console.error('the re-frame-owned `re-frame.disposable/IDisposable` protocol —');
      console.error('the UIx adapter ns ships no `reagent.core` /');
      console.error('`reagent.ratom` require. A regression here usually means:');
      console.error('  (a) the spine reified `reagent.ratom/IDisposable` again, or');
      console.error('  (b) a UIx-side ns picked up a transitive Reagent dep');
      console.error('      (e.g. via a new `:require [reagent.* ...]` in adapter wiring).');
    }
    if (!reagent.ok) {
      console.error('The Reagent-bundle present-check failed — the sentinel grep would');
      console.error('be vacuous. Either the sentinel strings have moved (refactor in');
      console.error('reagent.ratom / reagent.impl.batching upstream) or the counter');
      console.error('example stopped depending on the Reagent adapter. Investigate and');
      console.error('refresh REAGENT_SENTINELS in this script.');
    }
    process.exit(1);
  }
}

// Checker-owned target contract (rf2-kfn9q): the exact implementation-relative
// runtimes this gate isolates. It proves the UIx-only bundle carries no stock
// Reagent while the Reagent bundle does (the cross-substrate positive
// control), so it isolates both adapter runtimes. See the binding in
// check-bundle-isolation.cjs (validateDedicatedGate).
const COVERS_RUNTIMES = ['adapters/reagent', 'adapters/uix'];

module.exports = { COVERS_RUNTIMES };

// Run only when invoked directly, not when required for its target contract.
if (require.main === module) {
  main();
}
