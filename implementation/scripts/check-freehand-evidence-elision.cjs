#!/usr/bin/env node
/*
 * F4g evidence — production-isolation ELISION PROBE (rf2-drpa3.166).
 *
 * The final acceptance of the F4g evidence schema (rf2-drpa3.37, criterion 6):
 * the dev-gated occurrence-record emission seam (rf2-3naow) — and therefore the
 * `re-frame.freehand.evidence` schema it reaches — does NOT survive a production
 * build. Proven the F3d way: a CONTROL BUILD, not a grep of source.
 *
 * Two `:advanced` bundles of the SAME entry (re-frame.freehand.release-app),
 * differing ONLY in `goog.DEBUG`:
 *
 *   out/freehand-release          goog.DEBUG=false  — RELEASE (what ships)
 *   out/freehand-release-control  goog.DEBUG=true   — CONTROL (the twin)
 *
 * The seam sits behind `re-frame.interop/debug-enabled?` (an alias of
 * `goog.DEBUG`). `re-frame.freehand.cell/emit-commit-evidence!` is the SOLE
 * Freehand namespace that reaches the evidence schema, and it does so inside
 * `(when interop/debug-enabled? ...)`. Under `:advanced` + goog.DEBUG=false the
 * Closure compiler folds the gate to `false`, the branch dies, and with it
 * `evidence/commit-record` → `evidence/record` → `evidence/projection` and
 * every string those doors carry. Under goog.DEBUG=true the branch is live and
 * those strings survive.
 *
 * So a string reachable ONLY through the seam's doors is a DISCRIMINATOR:
 *
 *   ABSENT  in the release bundle  (the branch DCE'd — the schema does not ship)
 *   PRESENT in the control bundle  (the branch is live — the grep has teeth)
 *
 * The control's PRESENT half is the positive control the bead demands: it
 * RETAINS exactly what the release proves absent, so a probe that stopped
 * rooting the seam (the seam removed, the emit un-called, the schema orphaned)
 * would go absent in the control too and FAIL here rather than passing
 * vacuously.
 *
 * Strategy: grep, not parse (the Closure compiler renames symbols but does not
 * rewrite string literals). No timing assertion anywhere — this is a pure
 * present/absent contract over two byte streams.
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

// ----- the isolation contract ------------------------------------------------

// DEV-ONLY sentinels: strings the evidence DOORS carry, reachable from a render
// ONLY through the dev-gated seam (`re-frame.freehand.cell/emit-commit-evidence!`
// → `evidence/commit-record` → `evidence/record` → `evidence/projection`). Each
// MUST be ABSENT in the release bundle (the seam DCE'd) and PRESENT in the
// control bundle (the seam is live). They are exact runtime string literals, not
// docstrings (docstrings do not ship) and not fragments synthesised from
// keywords — so a global grep is unambiguous.
const DEV_ONLY_SENTINELS = [
  // `re-frame.freehand.evidence/record` — the record door's refusal message
  // tail. rf2-3naow measured this exact fragment as the discriminator between
  // the two builds; it lives only in the throw of `record`, which a render
  // reaches only through the gated seam.
  { source: 're-frame.freehand.evidence/record (refusal tail)',
    sentinel: 'occurrence-keyed schema (D020)' },
  // `re-frame.freehand.evidence/defects-message` — the projection door's refusal
  // head. Reached from `record` (which validates every projection through
  // `projection` → `defects` → `defects-message`), so it too rides the seam and
  // must DCE in production. A second, independent door proves the whole schema —
  // not just one string — is gone.
  { source: 're-frame.freehand.evidence/defects-message (refusal head)',
    sentinel: 'This evidence projection cannot be emitted' },
  // `re-frame.freehand.cell/report-sink-escape!` — the CONTAINMENT plane's host
  // console line. A second, INDEPENDENT dev-gated branch in `cell`: the two
  // sentinels above ride the record-construction branch, and this one rides the
  // after-publication delivery guard. It is also the one dev string a user
  // would SEE if it shipped, which makes its absence in release a
  // user-observable contract and not merely a size argument. Rooted the same
  // way — the guard is inside `(when interop/debug-enabled? …)` in
  // `emit-commit-evidence!` — so it is ABSENT in release and PRESENT in the
  // control alongside the other two.
  //
  // NOT a sentinel for the current-occurrence index (rf2-xftdv) or the
  // declared-view index, and neither can have one: `re-frame.freehand.occurrences`
  // and `re-frame.freehand.registry` carry no runtime string literal at all,
  // and inventing a throw to root a grep on would be writing dead code to
  // satisfy a probe. Their elision is proven CAUSALLY instead, over source
  // rather than bytes, by `evidence-boundary-jvm-test`: every call site into the
  // index sits inside the same `interop/debug-enabled?` gate these three
  // sentinels prove folds away, and the registration is emitted into the
  // `v/defview` expansion under that gate (pinned by `tool-reads-cljs-test`).
  // A string-grep witness for a namespace of pure data is the one thing this
  // technique cannot supply.
  { source: 're-frame.freehand.cell/report-sink-escape! (containment console line)',
    sentinel: 're-frame.freehand: the evidence sink (a DEBUG tool ' },
];

// PROD-SURVIVING sentinels: strings the release-app SHIPS regardless of
// goog.DEBUG. Present in BOTH bundles. They are the non-vacuity floor for the
// grep methodology itself — proof that the release bundle is a real, inspected
// artefact (not empty, not a broken build) in which the dev-only sentinels'
// ABSENCE is a genuine DCE result rather than an accident of an empty file.
const PROD_SURVIVING_SENTINELS = [
  // The release-app's own registered event id — ordinary application data the
  // registrar keys on, with no debug gate. It survives :advanced in both builds.
  { source: 're-frame.freehand.release-app (registered id survives :advanced)',
    sentinel: 're-frame.freehand.release-app/bump' },
];

// ----- helpers ---------------------------------------------------------------

// Scan one bundle for a sentinel set. `mustContain` true ⇒ every sentinel must
// be PRESENT to pass; false ⇒ every sentinel must be ABSENT. The loop + tally
// are the shared `assertSentinelSet` (lib/sentinel-scan.cjs); this wrapper
// supplies the gate's exact diagnostic line format.
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

// Read a bundle dir, rejecting a missing or present-but-empty dir (the
// rf2-utvst non-vacuous floor — an empty bundle satisfies every ABSENCE check
// and would false-GREEN production isolation). Returns { ok, blob, bytes } or a
// failing sentinel on the reject path.
function readBundle(label, dir) {
  const { status, blob } = classifyReleaseBundle(dir);
  if (status === 'missing') {
    console.error(`[freehand-evidence-elision] ${label}: bundle path missing — ${dir}`);
    console.error('         Run "npm run build:freehand-evidence-elision".');
    return { ok: false, blob: null, bytes: null, reason: 'missing' };
  }
  if (status === 'empty') {
    console.error(`[freehand-evidence-elision] ${label}: bundle present but empty (zero top-level JS) — ${dir}`);
    console.error('         An empty bundle proves nothing got inspected. Rebuild with');
    console.error('         "npm run build:freehand-evidence-elision" or clear the stale dir.');
    return { ok: false, blob: null, bytes: 0, reason: 'empty' };
  }
  report.detail(`[freehand-evidence-elision] ${label}: ${dir}`);
  report.detail(`          bundle size: ${blob.length} chars`);
  return { ok: true, blob, bytes: blob.length };
}

// ----- main ------------------------------------------------------------------

function main() {
  report.detail('=== F4g evidence production-isolation probe (rf2-drpa3.166) ===');

  const releaseDir = path.join(ROOT, 'out', 'freehand-release');
  const controlDir = path.join(ROOT, 'out', 'freehand-release-control');

  const release = readBundle('release (goog.DEBUG=false)', releaseDir);
  const control = readBundle('control (goog.DEBUG=true) ', controlDir);

  if (!release.ok || !control.ok) {
    report.flushDetails();
    console.error('=== FAIL: a bundle was missing or empty (see above) ===');
    process.exit(1);
  }

  // RELEASE: the evidence doors must be ABSENT (DCE'd out of production), and
  // the application survivor must be PRESENT (the bundle is real).
  report.detail('[freehand-evidence-elision] RELEASE — evidence doors must be ABSENT (DCE):');
  const releaseAbsent   = assertSentinels(release.blob, DEV_ONLY_SENTINELS, false);
  report.detail('[freehand-evidence-elision] RELEASE — app survivor must be PRESENT (non-vacuity floor):');
  const releaseSurvives = assertSentinels(release.blob, PROD_SURVIVING_SENTINELS, true);

  // CONTROL: the SAME evidence doors must be PRESENT (the positive control — the
  // seam is live, so the grep has teeth), and the survivor is present here too.
  report.detail('[freehand-evidence-elision] CONTROL — evidence doors must be PRESENT (positive control):');
  const controlPresent  = assertSentinels(control.blob, DEV_ONLY_SENTINELS, true);
  report.detail('[freehand-evidence-elision] CONTROL — app survivor must be PRESENT:');
  const controlSurvives = assertSentinels(control.blob, PROD_SURVIVING_SENTINELS, true);

  const ok = releaseAbsent.ok && releaseSurvives.ok
          && controlPresent.ok && controlSurvives.ok;

  if (ok) {
    report.pass(
      'freehand-evidence-elision',
      `release ${releaseAbsent.passed}/${releaseAbsent.checked} doors absent (${release.bytes} chars); ` +
      `control ${controlPresent.passed}/${controlPresent.checked} doors present (${control.bytes} chars); ` +
      `survivor present in both`
    );
    process.exit(0);
  }

  report.flushDetails();
  console.error('=== FAIL ===');
  if (!releaseAbsent.ok) {
    console.error('Production isolation BROKE: an evidence door survived :advanced with');
    console.error('goog.DEBUG=false. The dev-gated emission seam');
    console.error('(re-frame.freehand.cell/emit-commit-evidence!) — or something newly');
    console.error('reaching re-frame.freehand.evidence OUTSIDE that gate — is shipping the');
    console.error('evidence schema into production. Every path to the schema must sit behind');
    console.error('re-frame.interop/debug-enabled? so Closure DCE removes it.');
  }
  if (!controlPresent.ok) {
    console.error('The CONTROL bundle is MISSING an evidence door — the probe would be');
    console.error('vacuous. The seam likely stopped rooting the schema (emit un-called, seam');
    console.error('removed, a door string renamed). This is the positive control failing:');
    console.error('fix the seam, or update DEV_ONLY_SENTINELS to track a deliberate rename.');
  }
  if (!releaseSurvives.ok || !controlSurvives.ok) {
    console.error('A PROD-SURVIVING survivor was absent — the release-app bundle is not the');
    console.error('artefact expected, so the dev-only ABSENCE result is not trustworthy.');
    console.error('Confirm the build entry (re-frame.freehand.release-app/-main) still ships');
    console.error('its registrations, or update PROD_SURVIVING_SENTINELS.');
  }
  process.exit(1);
}

main();
