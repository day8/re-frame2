#!/usr/bin/env node
/*
 * Schemas-artefact bundle-cost gate (Spec 010 §Bundle cost; beads
 * rf2-fqbcy, rf2-kybsf, rf2-v4o7e).
 *
 * WHAT THIS GATE ASSERTS, AND WHY IT IS A MARGIN AND NOT A CEILING.
 *
 * Spec 010 §Bundle cost budgets a MARGINAL quantity: what requiring
 * `re-frame.schemas` adds on top of an app that already uses re-frame2.
 * Until rf2-v4o7e this gate asserted an ABSOLUTE gzipped ceiling on a
 * probe bundle instead — a number dominated by things the budget is not
 * about. `cljs.core` alone is ~186 KB of the probe's ~494 KB optimized
 * bytes, and `re-frame.core` is most of the rest, so every unrelated
 * framework change moved this gate's number.
 *
 * It duly went wrong. Measured, gzipped:
 *
 *   2026-05-14 (rf2-fqbcy, gate authored)   probe  80.1 KB, Malli marginal 29.8 KB
 *   2026-08-05 (rf2-kybsf, first-ever run)  probe 124.9 KB, Malli marginal 30.8 KB
 *
 * The probe grew 44.8 KB in three months; the schemas surface accounted
 * for 1.0 KB of it and `cljs.core` + `re-frame.core` for 43.8 KB. The
 * gate fired at the schemas artefact for growth that happened entirely
 * outside it — and its ceiling had never been derived from these probes
 * in the first place (rf2-fqbcy lifted 100/125 KB from Spec 010's
 * Reagent/React harness rows and parked them next to a bare probe
 * measuring 50.3 KB; rf2-v96fh then made both probes Malli-bearing and
 * LOWERED the Malli-bearing arm to the Malli-FREE 100 KB figure). No
 * absolute constant would have survived a quarter, so this gate no
 * longer has one. Absolute bundle size is owned by the perf-bundle and
 * bundle-isolation gates; this one owns the schemas margin.
 *
 * THE A/B. Two `:advanced` + `goog.DEBUG=false` browser builds that
 * differ by EXACTLY ONE require:
 *
 *   schemas-bundle-control  `[re-frame.core]` only.
 *   schemas-bundle-probe    `[re-frame.core]` + `[re-frame.schemas]`.
 *
 * `probe - control` is therefore the schemas opt-in and nothing else.
 * Per rf2-v96fh the `re-frame.schemas` facade `:require`s the
 * `re-frame.schemas.malli` adapter in its own ns-form, so the probe's
 * posture is the ONLY schemas posture a consumer can buy — which is why
 * the control is core-only rather than a facade-with-the-adapter-
 * stripped counterfactual. That counterfactual remains a useful
 * ATTRIBUTION technique (rf2-kybsf used it once, to split "Malli grew"
 * from "core grew"); it is not a posture, so it is not the control.
 *
 * FIRST MATCHED RUN (2026-08-05, cold, Windows, node 24, malli 0.20.1 —
 * the run these thresholds are set from):
 *
 *   schemas-bundle-control      86356 B gzipped (84.3 KB)
 *   schemas-bundle-probe       127926 B gzipped (124.9 KB)
 *   margin                      41570 B gzipped (40.6 KB)
 *
 * Composition of that margin, from `shadow.cljs.build-report` optimized
 * bytes (post-Closure, uncompressed): Malli 121.5 KB (`malli.core` 88.6,
 * `malli.impl.regex` 16.9, `malli.error` 12.0, `malli.registry` 2.0,
 * `malli.sci` 1.3, `malli.impl.util` 0.7) + `borkdude.dynaload` 6.6 KB +
 * the `re-frame.schemas` artefact 14.3 KB + ~43 KB of `re-frame.core` /
 * `goog` that the schemas path roots and the bare control DCEs away
 * (`goog.crypt.sha256` behind `re-frame.schemas.digest`, and so on).
 *
 * That last term is why the margin is an UPPER BOUND on what a real
 * consumer pays: an app that already roots those core paths pays them
 * once, not twice. Erring high is the conservative direction for a
 * budget, and it is the price of a control that is a control — anything
 * the control rooted that the probe does not would make the margin
 * UNDERSTATE the cost, and that is the direction that loosens a gate.
 *
 * WHY IT IS TWO-SIDED. A margin that COLLAPSES is not good news — it
 * means the A/B stopped measuring what it measures, which is exactly how
 * a probe goes vacuous. The floor is also the in-gate structural echo of
 * schema-implies-validation (rf2-v96fh), and that was measured rather
 * than assumed: with `[re-frame.schemas.malli]` deleted from the facade's
 * ns-form the probe rebuilds at 96332 B (94.1 KB) and the margin falls to
 * 9976 B (9.7 KB) — 10.3 KB below the floor, exit 1. The invariant's
 * PRIMARY owner is the behavioural test
 * `implementation/schemas/test/re_frame/schemas_implies_validation_test.clj`
 * (the `:schemas/malli-validate` hook is bound by requiring the facade
 * alone, and a bad write to a registered slot fires
 * `:rf.error/schema-validation-failure`); this gate is the bundle-shaped
 * corroboration, which is why the old byte-equality guard between two
 * near-identical probes retired with the `-malli` probe it compared.
 *
 * Strategy: gzip every top-level .js file under each bundle's output-dir
 * and sum the compressed sizes.
 *
 * Exit 0 on PASS, 1 on FAIL.
 */

'use strict';

const fs   = require('fs');
const path = require('path');
const zlib = require('zlib');
const { createGateReporter } = require('./lib/gate-report.cjs');
const {
  listReleaseJsFiles,
  classifyReleaseBundle,
} = require('./lib/read-release-bundle.cjs');

const ROOT = path.resolve(__dirname, '..');
const report = createGateReporter();

// ----- the A/B ---------------------------------------------------------------

const CONTROL = 'schemas-bundle-control';
const PROBE   = 'schemas-bundle-probe';

const BUNDLES = [
  {
    name:      CONTROL,
    bundleDir: path.join(ROOT, 'out', CONTROL),
    role:      '`[re-frame.core]` only — the subtrahend',
  },
  {
    name:      PROBE,
    bundleDir: path.join(ROOT, 'out', PROBE),
    role:      '`[re-frame.core]` + `[re-frame.schemas]` ⇒ Malli wired (rf2-v96fh)',
  },
];

// ----- the margin contract ---------------------------------------------------

// Both bounds are set from the first matched A/B run recorded in the
// header (41570 B / 40.6 KB), per the rf2-kybsf ruling: numbers from the
// measurement, never inherited from a prior constant.
//
// CEILING — 45 KB, i.e. the measured margin plus ~4.4 KB (~11 %) of
// stated headroom. The headroom absorbs a Malli patch bump and small
// additions to the schemas artefact's own surface; for scale, Malli's
// marginal cost moved 29.8 -> 30.8 KB across the three months and the
// version bump between rf2-fqbcy and rf2-kybsf.
//
// It was calibrated against the failure it exists to catch — a namespace
// off Spec 010's restrict-to-dev/test list reaching the production path —
// by MEASURING one rather than trusting the list's headline figures. Add
// a `malli.transform` require to the probe and NOTHING HAPPENS (+0.2 KB):
// Closure DCEs a merely-required namespace, which is Spec 010's own
// "inter-namespace DCE works" claim holding. Add a call to
// `malli.transform/json-transformer` and the namespace becomes reachable:
// +5.5 KB gzipped on top of the already-present `malli.core`, margin
// 47201 B (46.1 KB), 1.1 KB over this ceiling, exit 1. So the headroom is
// tight enough for the smallest of the two heavy restrict-list
// namespaces, and `malli.generator` (heavier still, carries test.check)
// reds by more. Note the list's per-namespace figures are STANDALONE
// weights — the incremental cost when `malli.core` is already in the
// bundle is smaller, and the incremental one is what this gate sees.
// The headroom is a tolerance, not a licence to grow into.
//
// FLOOR — 20 KB, a little under half the measured margin. It restores the
// "Malli arm strictly larger" methodology guard rf2-fqbcy originally had,
// in the shape the A/B allows: a reverted facade adapter require drops
// the margin to the measured 9.7 KB, and a control that accidentally
// became Malli-bearing or a probe that stopped rooting the schemas
// surface drops it toward 0. Both are FAILURES of the measurement rather
// than good news about the bundle.
const MARGIN_MAX_BYTES = 45 * 1024;
const MARGIN_MIN_BYTES = 20 * 1024;

// ----- helpers ---------------------------------------------------------------

// Bundle file listing is shared with the sibling check-* scripts via
// scripts/lib/read-release-bundle.cjs (rf2-qlk4w). Top-level *.js
// only; a stale dev-build `cljs-runtime/` subdir is skipped.

function gzippedSize(file) {
  const raw = fs.readFileSync(file);
  return zlib.gzipSync(raw, { level: 9 }).length;
}

function sumGzippedBytes(dir) {
  const files = listReleaseJsFiles(dir);
  if (files == null) {
    return null;
  }
  return files.reduce((acc, f) => acc + gzippedSize(f), 0);
}

function fmtKb(bytes) {
  return (bytes / 1024).toFixed(1) + ' KB';
}

// ----- main ------------------------------------------------------------------

function main() {
  report.detail('=== Schemas bundle-cost gate (Spec 010 §Bundle cost, rf2-v4o7e) ===');
  report.detail('');

  const sizes = {};
  let bundlesOk = true;

  for (const bundle of BUNDLES) {
    // Non-vacuous floor (rf2-utvst): a present-but-empty output dir sums
    // to 0 gzipped bytes. Reject it before measuring — a margin computed
    // from a zero-byte arm measures nothing.
    const cls = classifyReleaseBundle(bundle.bundleDir);
    if (cls.status === 'empty') {
      console.error(`[schemas-bundle] ${bundle.name}: bundle present but empty (zero top-level JS) — ${bundle.bundleDir}`);
      console.error('              The release emitted no bundle; a zero-byte arm would');
      console.error('              make the margin meaningless. Rebuild with "shadow-cljs');
      console.error(`              release ${bundle.name}" or clear the stale dir.`);
      bundlesOk = false;
      continue;
    }
    const total = sumGzippedBytes(bundle.bundleDir);
    if (total == null) {
      console.error(`[schemas-bundle] ${bundle.name}: bundle dir missing — ${bundle.bundleDir}`);
      console.error('              Did you run "shadow-cljs release ' +
                    `${bundle.name}"?`);
      bundlesOk = false;
      continue;
    }
    sizes[bundle.name] = total;
    report.detail(`  [measured] ${bundle.name}`);
    report.detail(`        role:   ${bundle.role}`);
    report.detail(`        bundle: ${fmtKb(total)} gzipped (${total} bytes)`);
  }

  if (!bundlesOk) {
    report.flushDetails();
    console.error('=== FAIL ===');
    console.error('');
    console.error('An arm of the A/B is missing or empty, so no margin could be');
    console.error('measured. Rebuild both arms:');
    console.error(`  shadow-cljs release ${CONTROL} ${PROBE}`);
    process.exit(1);
  }

  // ----- the assertion -------------------------------------------------------
  //
  // `probe - control` is the schemas opt-in: `re-frame.schemas`, the
  // `re-frame.schemas.malli` adapter its ns-form pulls, Malli's reachable
  // body, and the core/goog surface the schemas path roots. Asserted
  // TWO-SIDED — see the header for why a collapsed margin is a failure.
  const control = sizes[CONTROL];
  const probe   = sizes[PROBE];
  const margin  = probe - control;

  const overCeiling = margin > MARGIN_MAX_BYTES;
  const underFloor  = margin < MARGIN_MIN_BYTES;
  const marginOk    = !overCeiling && !underFloor;

  report.detail('');
  report.detail(`  [${marginOk ? 'OK' : 'FAIL'}] schemas margin (Spec 010 §Bundle cost — the MARGINAL budget)`);
  report.detail(`        margin: ${fmtKb(margin)} gzipped (${margin} bytes)`);
  report.detail(`        band:   ${fmtKb(MARGIN_MIN_BYTES)} … ${fmtKb(MARGIN_MAX_BYTES)} ` +
                `(${MARGIN_MIN_BYTES} … ${MARGIN_MAX_BYTES} bytes)`);

  if (overCeiling) {
    report.detail(`        REGRESSION: margin exceeds the ceiling by ${fmtKb(margin - MARGIN_MAX_BYTES)}`);
  }
  if (underFloor) {
    report.detail(`        COLLAPSE: margin is ${fmtKb(MARGIN_MIN_BYTES - margin)} below the floor`);
  }

  report.detail('');
  if (marginOk) {
    report.pass(
      'schemas-bundle',
      `${CONTROL}=${fmtKb(control)}; ${PROBE}=${fmtKb(probe)}; ` +
        `margin=${fmtKb(margin)} within ${fmtKb(MARGIN_MIN_BYTES)}…${fmtKb(MARGIN_MAX_BYTES)}`
    );
    process.exit(0);
  }

  report.flushDetails();
  console.error('=== FAIL ===');
  console.error('');
  if (overCeiling) {
    console.error('THE MARGIN GREW. Requiring `re-frame.schemas` now costs more');
    console.error('than Spec 010 §Bundle cost budgets. Likely causes, in the');
    console.error('order worth checking:');
    console.error('  - A transitive require dragged a namespace off Spec 010\'s');
    console.error('    restrict-to-dev/test list onto the production path, and');
    console.error('    something REACHED it (a bare require is DCE\'d) —');
    console.error('    `malli.transform` (+5.5 KB gzipped when rooted, measured),');
    console.error('    `malli.generator` (heavier; carries test.check),');
    console.error('    `malli.util`. Check the `re-frame.schemas` ns-form, the');
    console.error('    adapter\'s, and what the probe\'s init-fn now reaches.');
    console.error('  - Malli\'s own reachable body grew across a version bump.');
    console.error('  - The schemas artefact grew a genuinely new surface.');
    console.error('');
    console.error('To attribute it, build both arms with a build report and diff');
    console.error('the per-source optimized bytes:');
    console.error(`  shadow-cljs run shadow.cljs.build-report ${PROBE} probe.html`);
    console.error(`  shadow-cljs run shadow.cljs.build-report ${CONTROL} control.html`);
    console.error('');
    console.error('Note what this gate CANNOT be failing for: `cljs.core` and');
    console.error('`re-frame.core` growth cancels between the two arms. If the');
    console.error('cause turns out to be core, the margin is not where it shows');
    console.error('up, and the answer is not a bigger number here — that is the');
    console.error('mistake rf2-kybsf documents. Update Spec 010 §Bundle cost');
    console.error('first, with the measurement, and move this band in lockstep.');
  }
  if (underFloor) {
    console.error('THE MARGIN COLLAPSED, which is a measurement failure and not');
    console.error('a saving. Requiring `re-frame.schemas` is supposed to cost');
    console.error('what Spec 010 §Bundle cost says it costs; a margin this small');
    console.error('means one of the two arms stopped being what it claims:');
    console.error('  - The `re-frame.schemas` facade no longer `:require`s');
    console.error('    `re-frame.schemas.malli`, so requiring schemas no longer');
    console.error('    implies validation (the rf2-v96fh regression). The');
    console.error('    behavioural owner of that invariant is');
    console.error('    schemas/test/re_frame/schemas_implies_validation_test.clj');
    console.error('    — run it; it will red too, and it says why in words.');
    console.error(`  - \`${CONTROL}\` picked up a schemas or Malli require and`);
    console.error('    stopped being a control. Its ns-form must require');
    console.error('    `re-frame.core` and nothing else.');
    console.error(`  - \`${PROBE}\`'s init-fn stopped rooting the schemas surface,`);
    console.error('    so Closure DCE\'d the artefact the gate exists to measure.');
  }
  process.exit(1);
}

main();
