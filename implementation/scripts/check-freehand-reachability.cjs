#!/usr/bin/env node
/*
 * B5 REACHABILITY — unused runtime modules are absent from the production
 * bundle (rf2-drpa3.52, acceptance 1; D021 measurement B5).
 *
 * D021's B5 row splits in two. Bundle BYTES and parse/compile TIME are
 * evidence, published with provenance and never thresholded — that half is
 * `re-frame.freehand.bench.b5`. This is the other half, and it is a hard
 * DETERMINISTIC gate: a page that ships Freehand does not pay for the parts
 * of Freehand it does not use.
 *
 * WHY A CONTROL BUILD AND NOT A GREP
 *
 * Both naive oracles have been MEASURED WRONG in this repository:
 *
 *   - counting a debug flag's occurrences in a release bundle reported 225
 *     survivors in a build where the entire gated surface had been
 *     eliminated. They were prose, not code.
 *   - grepping for a small function's name can never go red, because the
 *     Closure compiler inlines the function and the name disappears whether
 *     the code shipped or not.
 *
 * What both mistakes share is the absence of a POSITIVE CONTROL. A grep that
 * has never been observed to fire cannot distinguish `absent` from
 * `misspelled`, `renamed`, `inlined`, or `never in this bundle to begin
 * with`. So the oracle is validated FIRST, in the same build configuration,
 * against a bundle where the symbol is known to be present.
 *
 * THE PAIR
 *
 *   out/freehand-release                        PRODUCTION — what ships.
 *                                               Entry: release-app/-main.
 *   out/freehand-release-reachability-control   CONTROL — a strict SUPERSET.
 *                                               Entry: the same -main CALLED,
 *                                               plus one view that calls
 *                                               `v/controller-key`.
 *
 * Same `:advanced`, same `goog.DEBUG false`, same everything else. The ONLY
 * variable is that the control's app reaches the semantic-controller
 * infrastructure (`re-frame.freehand.control`) and the production app does
 * not. So a string that lives only behind that door is a DISCRIMINATOR:
 *
 *   ABSENT  in production  — the unused module did not ship (the claim)
 *   PRESENT in the control — the grep has teeth (the oracle's validation)
 *
 * Note what this does NOT share with `check-freehand-evidence-elision.cjs`.
 * That gate moves `goog.DEBUG` and holds the app still, proving a DEV-GATED
 * seam elides. This one holds the flag still and moves the APP, proving an
 * unused MODULE elides. Two different claims, and neither substitutes for
 * the other: the controller strings below are absent from the goog.DEBUG=true
 * control too, which is exactly why that build cannot prove this.
 *
 * Strategy: grep, not parse — Closure renames symbols but does not rewrite
 * string literals. No byte assertion anywhere: D021's NON-GOALS bar a
 * byte-size threshold, and this states only present/absent.
 *
 * Exit 0 on PASS, 1 on FAIL.
 */

'use strict';

const path = require('path');
const { createGateReporter } = require('./lib/gate-report.cjs');
const { classifyReleaseBundle } = require('./lib/read-release-bundle.cjs');
const { assertSentinelSet } = require('./lib/sentinel-scan.cjs');

const ROOT   = path.resolve(__dirname, '..');
const report = createGateReporter();

// ----- the reachability contract ---------------------------------------------

// UNUSED-MODULE sentinels: exact runtime string literals from
// `re-frame.freehand.control`, the semantic-controller infrastructure. They
// are the refusal messages of `record-key`'s two doors — an absent `:control`
// and an explicit `nil` one — and `record-key` is what `v/controller-key`
// resolves to. Any view that asks for a controller key reaches both; a bundle
// whose app never asks reaches neither.
//
// They are error-path strings on purpose. An error message must fire in
// production, so it is not behind `goog.DEBUG` and its absence is a
// REACHABILITY result rather than a flag result. They are also not
// docstrings (those do ship — see the 225-survivor trap above) and not
// fragments synthesised from keywords, so the grep is unambiguous.
//
// TWO independent doors, not one, so a single message being reworded cannot
// silently halve the gate: both must move together, and the control half
// reds if either stops being reachable.
const UNUSED_MODULE_SENTINELS = [
  { source: 're-frame.freehand.control/missing-address! + nil-address! (shared head)',
    sentinel: 'A writable controller of kind ' },
  { source: 're-frame.freehand.control/nil-address! (refusal tail)',
    sentinel: 'was rendered with :control nil' },
];

// PROD-SURVIVING sentinels: strings the release app ships regardless. Present
// in BOTH bundles, and the non-vacuity floor for the methodology itself —
// proof that the production bundle is a real, inspected artefact in which the
// unused module's ABSENCE is a DCE result and not an accident of reading the
// wrong file, an empty file, or a broken build.
const PROD_SURVIVING_SENTINELS = [
  { source: 're-frame.freehand.release-app (registered id survives :advanced)',
    sentinel: 're-frame.freehand.release-app/bump' },
];

// ----- helpers ---------------------------------------------------------------

function assertSentinels(blob, sentinels, mustContain) {
  return assertSentinelSet(blob, sentinels, {
    mustContain,
    emit: (line) => report.detail(line),
    formatLine: ({ source, sentinel, present, tag }) => {
      const expected = mustContain ? 'PRESENT' : 'ABSENT';
      const actual   = present     ? 'PRESENT' : 'ABSENT';
      return `          [${tag}] ${source}: sentinel ${JSON.stringify(sentinel)} expected ${expected}, was ${actual}`;
    },
  });
}

// Read a bundle dir, rejecting a missing or present-but-empty dir. An empty
// bundle satisfies every ABSENCE check and would false-GREEN the whole gate.
function readBundle(label, dir) {
  const { status, blob } = classifyReleaseBundle(dir);
  if (status === 'missing') {
    console.error(`[freehand-reachability] ${label}: bundle path missing — ${dir}`);
    console.error('         Run "npm run test:freehand-reachability".');
    return { ok: false, blob: null, bytes: null };
  }
  if (status === 'empty') {
    console.error(`[freehand-reachability] ${label}: bundle present but empty (zero top-level JS) — ${dir}`);
    console.error('         An empty bundle proves nothing got inspected. Rebuild with');
    console.error('         "npm run test:freehand-reachability" or clear the stale dir.');
    return { ok: false, blob: null, bytes: 0 };
  }
  report.detail(`[freehand-reachability] ${label}: ${dir}`);
  report.detail(`          bundle size: ${blob.length} chars`);
  return { ok: true, blob, bytes: blob.length };
}

// ----- main ------------------------------------------------------------------

function main() {
  report.detail('=== B5 reachability probe — unused runtime modules (rf2-drpa3.52) ===');

  const releaseDir = path.join(ROOT, 'out', 'freehand-release');
  const controlDir = path.join(ROOT, 'out', 'freehand-release-reachability-control');

  const release = readBundle('production (release-app)          ', releaseDir);
  const control = readBundle('control    (release-app + control)', controlDir);

  if (!release.ok || !control.ok) {
    report.flushDetails();
    console.error('=== FAIL: a bundle was missing or empty (see above) ===');
    process.exit(1);
  }

  // THE ORACLE IS VALIDATED FIRST. The control must PRESENT every sentinel;
  // until it does, the production ABSENCE result below means nothing, and
  // saying so in this order is the difference between a gate and a habit.
  report.detail('[freehand-reachability] CONTROL — the doors must be PRESENT (oracle validation):');
  const controlPresent  = assertSentinels(control.blob, UNUSED_MODULE_SENTINELS, true);
  report.detail('[freehand-reachability] CONTROL — app survivor must be PRESENT:');
  const controlSurvives = assertSentinels(control.blob, PROD_SURVIVING_SENTINELS, true);

  // PRODUCTION: the same doors must be ABSENT (the module was not reachable
  // and Closure removed it), and the application survivor must be PRESENT
  // (the bundle really is the artefact, really was read, really was scanned).
  report.detail('[freehand-reachability] PRODUCTION — the doors must be ABSENT (unused module DCE):');
  const releaseAbsent   = assertSentinels(release.blob, UNUSED_MODULE_SENTINELS, false);
  report.detail('[freehand-reachability] PRODUCTION — app survivor must be PRESENT (non-vacuity floor):');
  const releaseSurvives = assertSentinels(release.blob, PROD_SURVIVING_SENTINELS, true);

  const ok = controlPresent.ok && controlSurvives.ok
          && releaseAbsent.ok  && releaseSurvives.ok;

  if (ok) {
    report.pass(
      'freehand-reachability',
      `oracle validated: control ${controlPresent.passed}/${controlPresent.checked} doors present ` +
      `(${control.bytes} chars); production ${releaseAbsent.passed}/${releaseAbsent.checked} doors absent ` +
      `(${release.bytes} chars); survivor present in both`
    );
    process.exit(0);
  }

  report.flushDetails();
  console.error('=== FAIL ===');
  if (!controlPresent.ok) {
    console.error('The ORACLE IS NOT VALID: the control bundle is missing a controller');
    console.error('door, so the production ABSENCE result below proves nothing. Most');
    console.error('likely the control entry stopped reaching the door (the');
    console.error('v/controller-key call removed or made unreachable), or a refusal');
    console.error('message was reworded. Fix the control entry, or update');
    console.error('UNUSED_MODULE_SENTINELS to track a deliberate rename — but do NOT');
    console.error('relax this half: it is the only thing making the gate falsifiable.');
  }
  if (!releaseAbsent.ok) {
    console.error('REACHABILITY BROKE: the semantic-controller runtime survived into the');
    console.error('production bundle, whose app declares no controller. Something now');
    console.error('reaches re-frame.freehand.control from the release entry, so a page');
    console.error('that uses no controller pays for the controller infrastructure.');
    console.error('Find the new edge — a render path calling controller-key, a registry');
    console.error('holding the module live, an eagerly-rooted var — and cut it.');
  }
  if (!releaseSurvives.ok || !controlSurvives.ok) {
    console.error('A PROD-SURVIVING survivor was absent — the bundle scanned is not the');
    console.error('artefact expected, so the ABSENCE result is not trustworthy. Confirm');
    console.error('the build entry (re-frame.freehand.release-app/-main) still ships its');
    console.error('registrations, or update PROD_SURVIVING_SENTINELS.');
  }
  process.exit(1);
}

main();
