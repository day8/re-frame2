#!/usr/bin/env node
/*
 * UIx-only and Helix-only bundles must NOT pull in `reagent.ratom`
 * or `reagent.impl.batching` (rf2-jicu2; resolves the rf2-ykqee
 * audit's Verdict B).
 *
 * Pre-rf2-jicu2 the substrate spine (`re-frame.substrate.spine`)
 * reified `reagent.ratom/IDisposable` on its derived-value container
 * — a single require dragged ~9KB optimised / 2-3KB gzipped of
 * `reagent.ratom` + `reagent.impl.batching` into every UIx-only and
 * Helix-only release bundle. The spine now reifies a re-frame-owned
 * protocol (`re-frame.disposable/IDisposable`); the UIx and Helix
 * adapters drop their `reagent.core` / `reagent.ratom` requires
 * entirely.
 *
 * This script grep-asserts those bundles for the Reagent sentinel
 * strings. The closure compiler may rename symbols under :advanced
 * but it does NOT rewrite the string literals Reagent declares via
 * `set!` on JS-interop slots (`cljsRatom`, `cljsRatomGeneration`,
 * Reagent's batching method names). If any appear in the UIx-only
 * or Helix-only counter bundles, the spine is dragging Reagent
 * back in — bundle isolation is broken.
 *
 * Methodology sanity check. To avoid a vacuous negative grep, the
 * same sentinels MUST appear in a Reagent-using bundle (the
 * `:examples/counter` build uses the Reagent adapter); the
 * present-check on the Reagent bundle proves the grep has signal.
 * If a future refactor displaces the sentinel strings, both the
 * Reagent present-check and the UIx/Helix absent-check go silent —
 * this script then fails fast on the present-check.
 *
 * Exit 0 on PASS, 1 on FAIL.
 */

'use strict';

const path = require('path');
const { readReleaseBlob } = require('./lib/read-release-bundle.cjs');

const ROOT = path.resolve(__dirname, '..');

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

function escapeRe(s) {
  return s.replace(/[.*+?^${}()|[\]\\]/g, '\\$&');
}

function countSubstring(blob, needle) {
  const re = new RegExp(escapeRe(needle), 'g');
  const m  = blob.match(re);
  return m ? m.length : 0;
}

function checkBundle(label, bundlePath, mustContain) {
  const blob = readReleaseBlob(bundlePath);
  if (blob == null) {
    console.error(`[uix-helix-reagent-free] ${label}: bundle path missing — ${bundlePath}`);
    console.error('                          Did you run the matching shadow-cljs release?');
    return false;
  }
  console.log(`  ${label}: ${bundlePath}`);
  console.log(`    bundle size: ${blob.length} chars`);

  let ok = true;
  for (const { source, sentinel } of REAGENT_SENTINELS) {
    const hits     = countSubstring(blob, sentinel);
    const present  = hits > 0;
    const expected = mustContain ? 'PRESENT (>=1)' : 'ABSENT (0)';
    const actual   = present     ? `PRESENT (${hits})` : 'ABSENT (0)';
    const passed   = present === mustContain;
    const tag      = passed ? 'OK' : 'FAIL';
    console.log(`    [${tag}] ${source}: sentinel ${JSON.stringify(sentinel)} expected ${expected}, was ${actual}`);
    if (!passed) ok = false;
  }
  return ok;
}

// ----- main ------------------------------------------------------------------

function main() {
  console.log('=== UIx-only / Helix-only Reagent isolation (rf2-jicu2) ===');
  console.log('');

  const uixDir     = path.join(ROOT, 'out', 'examples', 'counter-uix');
  const helixDir   = path.join(ROOT, 'out', 'examples', 'counter-helix');
  const reagentDir = path.join(ROOT, 'out', 'examples', 'counter');

  // Negative assertions: the new spine produces a UIx-only / Helix-only
  // bundle with no Reagent dependency.
  const uixOk   = checkBundle('UIx-only counter   (must NOT contain reagent.ratom / reagent.impl.batching)',
                              uixDir, false);
  console.log('');
  const helixOk = checkBundle('Helix-only counter (must NOT contain reagent.ratom / reagent.impl.batching)',
                              helixDir, false);
  console.log('');
  // Positive assertion: the Reagent-using counter bundle DOES carry the
  // sentinels. Without this present-check, a sentinel-name regression
  // would silently turn the negative greps above into vacuous passes.
  const reagentOk = checkBundle('Reagent counter    (methodology sanity — sentinels MUST be present)',
                                reagentDir, true);

  if (uixOk && helixOk && reagentOk) {
    console.log('');
    console.log('=== PASS ===');
    process.exit(0);
  } else {
    console.error('');
    console.error('=== FAIL ===');
    console.error('');
    if (!uixOk || !helixOk) {
      console.error('A UIx-only or Helix-only release bundle pulled in reagent.ratom');
      console.error('or reagent.impl.batching. Per rf2-jicu2 the substrate spine reifies');
      console.error('the re-frame-owned `re-frame.disposable/IDisposable` protocol —');
      console.error('the UIx and Helix adapter ns\'s ship no `reagent.core` /');
      console.error('`reagent.ratom` require. A regression here usually means:');
      console.error('  (a) the spine reified `reagent.ratom/IDisposable` again, or');
      console.error('  (b) a UIx/Helix-side ns picked up a transitive Reagent dep');
      console.error('      (e.g. via a new `:require [reagent.* ...]` in adapter wiring).');
    }
    if (!reagentOk) {
      console.error('The Reagent-bundle present-check failed — the sentinel grep would');
      console.error('be vacuous. Either the sentinel strings have moved (refactor in');
      console.error('reagent.ratom / reagent.impl.batching upstream) or the counter');
      console.error('example stopped depending on the Reagent adapter. Investigate and');
      console.error('refresh REAGENT_SENTINELS in this script.');
    }
    process.exit(1);
  }
}

main();
