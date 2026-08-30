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
 * FIRST MATCHED RUN (2026-08-05, cold, Windows, node 24, malli 0.20.1):
 *
 *   schemas-bundle-control      86356 B gzipped (84.3 KB)
 *   schemas-bundle-probe       127926 B gzipped (124.9 KB)
 *   margin                      41570 B gzipped (40.6 KB)
 *
 * SECOND MATCHED RUN (2026-08-30, rf2-tiymn, Windows, node 24, malli
 * 0.20.1 — the run these thresholds are set from), after the adapter
 * stopped publishing the humanizer in production builds:
 *
 *   schemas-bundle-control      86851 B gzipped (84.8 KB)
 *   schemas-bundle-probe       127736 B gzipped (124.7 KB)
 *   margin                      40885 B gzipped (39.9 KB)
 *
 * Five builds of that tree on the same box gave 127696 B once and
 * 127736 B four times, with the raw byte count identical every time —
 * Closure's output is not byte-stable across runs here, so read the
 * last two digits of any figure in this file as noise.
 *
 * Both directions of the humanizer assertion were made to fire before
 * it was wired: ungating the adapter's publication puts the keyword back
 * (probe 128485 B, margin 41634 B, exit 1), and an application that
 * calls `malli.error/humanize` itself, the login example's posture, is
 * left alone (keyword still absent, margin 41635 B — the app pays the
 * same ~750 B the gate stopped charging everyone — exit 0).
 *
 * Composition of that margin, from `shadow.cljs.build-report` optimized
 * bytes (post-Closure, uncompressed): Malli 120.0 KB (`malli.core` 88.6,
 * `malli.impl.regex` 16.9, `malli.error` 10.5, `malli.registry` 2.0,
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
 * THE HUMANIZER, AND WHAT CLOSURE KEEPS OF `malli.error`. The adapter
 * publishes `malli.error/humanize` under `:schemas/humanize-explain!`
 * only when `interop/debug-enabled?` is true (rf2-tiymn) — the hook's
 * sole reader, the `:explain-humanized` enrichment of dev traces, sits
 * behind the same gate. Under `:advanced` + `goog.DEBUG=false` the
 * publication folds away, so this gate asserts the hook keyword is
 * ABSENT from the probe (a `set-fn!` that survived would leave its
 * keyword literal behind) and PRESENT for the sibling
 * `:schemas/malli-validate` publication from the same ns-load, which is
 * what proves the blob inspected is the probe's and that a surviving
 * publication does leave a keyword. Measured on the same control, same
 * day: unconditional publication 41595 B margin, gated 40845 B (-750 B;
 * `malli.error` 12.0 -> 10.5 KB optimized), and with the adapter's
 * `[malli.error]` require deleted outright 38333 B (-3262 B; every
 * `malli.error` literal gone). The difference between the last two is
 * `malli.error`'s message table (`default-errors`, built by
 * `PersistentHashMap.fromArrays`) and its negation prefix: top-level
 * defs whose initialisers Closure cannot prove pure, so it keeps them
 * even with no reader. Only taking the namespace out of the module
 * graph reaches that 2.5 KB, and the library cannot do that for a
 * consumer without a build-side knob, so it is priced inside the margin
 * and recorded in Spec 010 §Bundle cost rather than asserted away here.
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
  countSubstring,
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

// Both bounds are set from the second matched A/B run recorded in the
// header (40885 B / 39.9 KB), per the rf2-kybsf ruling: numbers from the
// measurement, never inherited from a prior constant. The first run set
// them at 45 / 20 KB from 41570 B; rf2-tiymn re-derived them from its own
// measurement by the same rule, and the ceiling moved with the margin.
//
// CEILING — 44 KB, i.e. the measured margin plus ~4.1 KB (~10 %) of
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
// +5.5 KB gzipped on top of the already-present `malli.core`: measured
// 2026-08-05 at margin 47201 B against the 45 KB ceiling, and again
// 2026-08-30 at 46550 B (45.5 KB) against this one, 1.5 KB over, exit 1.
// So the headroom is tight enough for the smallest of the two heavy
// restrict-list namespaces, and `malli.generator` (heavier still, carries
// test.check) reds by more. Note the list's per-namespace figures are
// STANDALONE weights — the incremental cost when `malli.core` is already
// in the bundle is smaller, and the incremental one is what this gate
// sees. The headroom is a tolerance, not a licence to grow into.
//
// FLOOR — 20 KB, about half the measured margin. It restores the "Malli
// arm strictly larger" methodology guard rf2-fqbcy originally had, in the
// shape the A/B allows: a reverted facade adapter require drops the
// margin to the measured 9.7 KB, and a control that accidentally became
// Malli-bearing or a probe that stopped rooting the schemas surface drops
// it toward 0. Both are FAILURES of the measurement rather than good news
// about the bundle.
const MARGIN_MAX_BYTES = 44 * 1024;
const MARGIN_MIN_BYTES = 20 * 1024;

// ----- the humanizer contract ------------------------------------------------

// The late-bind keyword the adapter publishes the humanizer under, and the
// one it publishes the validator under. A `set-fn!` call that survives
// Closure leaves its keyword literal in the bundle (`new Keyword("schemas",
// "malli-validate", "schemas/malli-validate", …)`), so the fully-qualified
// name is a direct marker for "this publication reached production".
// The humanizer's must be absent from the probe; the validator's must be
// present, or the absence check inspected nothing (rf2-tiymn).
const HUMANIZE_HOOK = 'schemas/humanize-explain!';
const VALIDATE_HOOK = 'schemas/malli-validate';

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
  const blobs = {};
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
    blobs[bundle.name] = cls.blob;
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

  // ----- the humanizer assertion --------------------------------------------
  //
  // The dev-only humanizer must not reach the production probe, and the
  // check must be shown to be looking at the probe: the validator hook the
  // same ns-load publishes unconditionally has to be there.
  const humanizeInProbe   = countSubstring(blobs[PROBE], HUMANIZE_HOOK);
  const validateInProbe   = countSubstring(blobs[PROBE], VALIDATE_HOOK);
  const humanizeInControl = countSubstring(blobs[CONTROL], HUMANIZE_HOOK);
  const validateInControl = countSubstring(blobs[CONTROL], VALIDATE_HOOK);

  const humanizerLeaked = humanizeInProbe > 0 || humanizeInControl > 0;
  const validatorAbsent = validateInProbe === 0;
  const controlTainted  = validateInControl > 0;
  const humanizerOk     = !humanizerLeaked && !validatorAbsent && !controlTainted;

  report.detail('');
  report.detail(`  [${humanizerOk ? 'OK' : 'FAIL'}] humanizer publication is dev-only (Spec 010 §Humanize-hook, rf2-tiymn)`);
  report.detail(`        \`${HUMANIZE_HOOK}\`: probe ${humanizeInProbe}, control ${humanizeInControl} (both must be 0)`);
  report.detail(`        \`${VALIDATE_HOOK}\`:    probe ${validateInProbe}, control ${validateInControl} (probe > 0, control 0)`);

  report.detail('');
  if (marginOk && humanizerOk) {
    report.pass(
      'schemas-bundle',
      `${CONTROL}=${fmtKb(control)}; ${PROBE}=${fmtKb(probe)}; ` +
        `margin=${fmtKb(margin)} within ${fmtKb(MARGIN_MIN_BYTES)}…${fmtKb(MARGIN_MAX_BYTES)}; ` +
        `humanizer hook absent from the probe`
    );
    process.exit(0);
  }

  report.flushDetails();
  console.error('=== FAIL ===');
  console.error('');
  if (humanizeInProbe > 0) {
    console.error('THE HUMANIZER REACHED THE PRODUCTION PROBE. The');
    console.error(`\`${HUMANIZE_HOOK}\` keyword survived Closure, which means`);
    console.error('something published or read the hook outside the');
    console.error('`interop/debug-enabled?` gate. Check the adapter\'s');
    console.error('`(when interop/debug-enabled? (late-bind/set-fn! …))` form in');
    console.error('`re-frame.schemas.malli`, and every `get-fn` of the key in');
    console.error('`re-frame.schemas.validate` — each must sit inside a gated body.');
    console.error('');
  }
  if (validatorAbsent) {
    console.error(`THE VALIDATOR HOOK IS MISSING FROM THE PROBE. \`${VALIDATE_HOOK}\``);
    console.error('is published unconditionally by the same ns-load, so its absence');
    console.error('means the adapter never loaded (the rf2-v96fh regression, which');
    console.error('the floor also catches) or the blob inspected is not the probe.');
    console.error('The humanizer check above proves nothing until this is fixed.');
    console.error('');
  }
  if (humanizeInControl > 0 || controlTainted) {
    console.error(`THE CONTROL CARRIES A SCHEMAS PUBLICATION. \`${CONTROL}\` must`);
    console.error('require `re-frame.core` and nothing else; a schemas keyword in');
    console.error('it means it stopped being a control.');
    console.error('');
  }
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
    if (overCeiling || humanizeInProbe > 0 || validatorAbsent || controlTainted) {
      console.error('');
    }
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
