#!/usr/bin/env node
/*
 * Schemas-artefact bundle-cost gate (Spec 010 §Bundle cost, bead rf2-fqbcy).
 *
 * Spec 010 §Bundle cost catalogues the gzipped cost of requiring
 * `re-frame.schemas`. Per rf2-v96fh (schema implies validation) the
 * `re-frame.schemas` facade now `:require`s `re-frame.schemas.malli`
 * itself, so REQUIRING THE SCHEMAS ARTEFACT IMPLIES MALLI — there is no
 * longer a 'schemas required, no Malli' bundle posture. A registered
 * schema therefore always validates rather than soft-passing into a
 * silent no-op. The only Malli-free posture is not requiring the
 * schemas artefact at all (the no-feature counter app, pinned by the
 * counter bundle-isolation gate — NOT this gate).
 *
 * This gate uses two probe builds:
 *
 *   schemas-bundle-probe        — requires ONLY `re-frame.schemas`.
 *                                 Under Ruling A the facade pulls the
 *                                 Malli adapter, so this is the
 *                                 canonical 'schemas surface (Malli
 *                                 wired)' cost. Asserts ≤ 100 KB gzipped.
 *   schemas-bundle-probe-malli  — requires `re-frame.schemas` AND the
 *                                 adapter EXPLICITLY. Under Ruling A the
 *                                 explicit require is redundant (the
 *                                 facade already loaded it), so this
 *                                 bundle is the SAME size. Asserts
 *                                 ≤ 100 KB gzipped.
 *
 * Schema-implies-validation regression guard (replaces the pre-rf2-v96fh
 * 'Malli strictly larger' delta guard): the two bundles MUST be
 * APPROXIMATELY EQUAL. Ruling A made Malli mandatory for any schemas
 * consumer, so the explicit-require delta collapsed to ~0 by design. If
 * a future change reverts the facade's adapter require, `schemas-bundle-
 * probe` (no explicit adapter require) would drop back to its ~59 KB
 * Malli-free figure while `schemas-bundle-probe-malli` stayed at ~91 KB
 * — the equality assertion then fails, catching the regression and
 * proving the facade still auto-wires Malli (i.e. schema still implies
 * validation).
 *
 * Strategy: gzip every .js file under the bundle's output-dir and
 * sum the compressed sizes. Mirrors the methodology used by the
 * external bundle audit findings/malli-bundle-cost-audit.md.
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

// ----- the schemas bundle-cost contract -------------------------------------

// Per rf2-v96fh both probes pull Malli (the facade requires the
// adapter), so both sit at the schemas-surface-with-Malli cost.
//
// THE CEILINGS BELOW ARE KNOWN-WRONG AND ARE AWAITING A RULING
// (rf2-kybsf). Do not "fix" this gate by raising them; the number is
// not the problem. Measured history, all figures gzipped:
//
//   2026-05-14 (rf2-fqbcy, gate authored)  probe 50.3 KB  probe-malli 80.1 KB
//   2026-08-05 (rf2-kybsf, first-ever run) probe 124.9 KB probe-malli 124.9 KB
//
// The 100/125 KB ceilings were never derived from these probes. They
// were lifted verbatim from Spec 010's representative-scenario harness
// (a Reagent/React counter app: 97.2 KB schemas-no-Malli, 120.8 KB
// schemas-plus-Malli) and parked next to a bare probe that measured
// 50.3 KB — ~50 KB of unexamined slack, as rf2-fqbcy's own commit
// message says outright. rf2-v96fh then made both probes Malli-bearing
// and, instead of adopting the Malli-bearing 125 KB ceiling already in
// this file, LOWERED the Malli-bearing probe to the Malli-FREE 100 KB
// one, citing a "~91 KB empirical" figure no run ever produced. The
// gate had no scheduled home, so nothing contradicted it for 83 days.
//
// What actually grew is NOT the schemas surface. Malli's marginal cost
// is flat: 29.8 KB in May, 30.8 KB now (measured 2026-08-05 by building
// this probe with the facade's adapter require removed: 94.1 KB, vs
// 124.9 KB with it). metosin/malli was already pinned at 0.20.1 when
// the 80.1 KB baseline was taken, so the 0.13.0 -> 0.20.1 bump is
// excluded by date. The +43.8 KB is cljs.core + re-frame.core over
// three months of framework work — a quantity this gate does not exist
// to police, and which will keep moving these absolute numbers.
//
// The fix is therefore a delta gate against a Malli-free control build,
// not a bigger constant (rf2-v4o7e). Until that is ruled, this gate
// stays red and honest.
const BUNDLES = [
  {
    name:            'schemas-bundle-probe',
    bundleDir:       path.join(ROOT, 'out', 'schemas-bundle-probe'),
    specRow:         '[re-frame.schemas] required ⇒ Malli wired (rf2-v96fh)',
    gzippedMaxBytes: 100 * 1024,   // 100 KB — UNRULED, see the block above
  },
  {
    name:            'schemas-bundle-probe-malli',
    bundleDir:       path.join(ROOT, 'out', 'schemas-bundle-probe-malli'),
    specRow:         '[re-frame.schemas] + explicit [malli] (redundant, rf2-v96fh)',
    gzippedMaxBytes: 100 * 1024,   // 100 KB — UNRULED, same surface as sibling
  },
];

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
  report.detail('=== Schemas bundle-cost gate (Spec 010 §Bundle cost, rf2-fqbcy) ===');
  report.detail('');

  const sizes = {};
  let bundlesOk = true;

  for (const bundle of BUNDLES) {
    // Non-vacuous floor (rf2-utvst): a present-but-empty output dir sums
    // to 0 gzipped bytes and would pass the `<= ceiling` size assertion
    // (and the equality guard) vacuously. Reject it before measuring.
    const cls = classifyReleaseBundle(bundle.bundleDir);
    if (cls.status === 'empty') {
      console.error(`[schemas-bundle] ${bundle.name}: bundle present but empty (zero top-level JS) — ${bundle.bundleDir}`);
      console.error('              The release emitted no bundle; a zero-byte bundle would');
      console.error('              pass the size ceiling vacuously. Rebuild with "shadow-cljs');
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
    const ok = total <= bundle.gzippedMaxBytes;
    const tag = ok ? 'OK' : 'FAIL';
    report.detail(`  [${tag}] ${bundle.name}`);
    report.detail(`        spec:      ${bundle.specRow}`);
    report.detail(`        bundle:    ${fmtKb(total)} gzipped (${total} bytes)`);
    report.detail(`        threshold: ${fmtKb(bundle.gzippedMaxBytes)} (${bundle.gzippedMaxBytes} bytes)`);
    if (!ok) {
      bundlesOk = false;
      report.detail(`        REGRESSION: bundle exceeds threshold by ${fmtKb(total - bundle.gzippedMaxBytes)}`);
    }
  }

  // Schema-implies-validation regression guard (rf2-v96fh) — replaces
  // the pre-rf2-v96fh 'Malli-on strictly larger' delta guard. Under
  // Ruling A the `re-frame.schemas` facade requires the Malli adapter
  // itself, so the explicit `[re-frame.schemas.malli]` require in the
  // `-malli` probe is redundant and both bundles carry the SAME Malli
  // surface. They MUST therefore be APPROXIMATELY EQUAL.
  //
  // If a future change reverts the facade's adapter require,
  // `schemas-bundle-probe` (no explicit adapter require) drops back to
  // its ~59 KB Malli-free figure while `schemas-bundle-probe-malli`
  // (explicit require) stays at ~91 KB — a ~32 KB gap that trips this
  // guard, proving the facade no longer auto-wires Malli (i.e. schema
  // no longer implies validation). The tolerance absorbs the few bytes
  // of init-fn-name difference between the two probe namespaces.
  let methodologyOk = true;
  if (sizes['schemas-bundle-probe'] != null &&
      sizes['schemas-bundle-probe-malli'] != null) {
    const probe      = sizes['schemas-bundle-probe'];
    const probeMalli = sizes['schemas-bundle-probe-malli'];
    const delta      = Math.abs(probeMalli - probe);
    // Equality tolerance. The two probes differ only by their init-fn
    // names; the Malli surface is identical. 2 KB comfortably absorbs
    // symbol-name noise while still catching the ~32 KB regression a
    // reverted facade require would produce.
    const maxDelta = 2 * 1024;
    const ok = delta <= maxDelta;
    const tag = ok ? 'OK' : 'FAIL';
    report.detail('');
    report.detail(`  [${tag}] schema-implies-validation guard — both probes carry Malli (equal)`);
    report.detail(`        |delta|: ${fmtKb(delta)} (${delta} bytes)`);
    report.detail(`        tolerance: ≤ ${fmtKb(maxDelta)} (Ruling A: facade auto-wires Malli)`);
    if (!ok) {
      methodologyOk = false;
      report.detail('        FAIL — the two probes diverged. Most likely the');
      report.detail('        `re-frame.schemas` facade no longer requires');
      report.detail('        `re-frame.schemas.malli`, so requiring schemas no');
      report.detail('        longer implies validation (rf2-v96fh regression).');
    }
  }

  report.detail('');
  if (bundlesOk && methodologyOk) {
    const probe = sizes['schemas-bundle-probe'];
    const probeMalli = sizes['schemas-bundle-probe-malli'];
    report.pass(
      'schemas-bundle',
      `schemas-bundle-probe=${fmtKb(probe)}/${fmtKb(BUNDLES[0].gzippedMaxBytes)}; ` +
        `schemas-bundle-probe-malli=${fmtKb(probeMalli)}/${fmtKb(BUNDLES[1].gzippedMaxBytes)}; ` +
        `delta=${fmtKb(probeMalli - probe)}`
    );
    process.exit(0);
  } else {
    report.flushDetails();
    console.error('=== FAIL ===');
    console.error('');
    console.error('Per Spec 010 §Bundle cost the schemas-artefact bundle cost');
    console.error('budgets are normative. A size failure most likely means:');
    console.error('  - A transitive require dragged a heavy ns into the schemas');
    console.error('    surface; check `re-frame.schemas` ns-form and downstream.');
    console.error('  - The Malli adapter\'s ns-load body grew unexpectedly.');
    console.error('  - cljs.core / re-frame.core grew. This gate cannot tell');
    console.error('    that apart from the two causes above, because it asserts');
    console.error('    an ABSOLUTE size while Spec 010 budgets a MARGINAL one.');
    console.error('    That is the known defect tracked by rf2-v4o7e.');
    console.error('');
    console.error('BEFORE YOU RAISE A THRESHOLD, read the comment block above');
    console.error('BUNDLES. The ceilings are unruled (rf2-kybsf) and were never');
    console.error('derived from these probes. Raising them to match whatever');
    console.error('shipped is how a budget stops meaning anything. To attribute');
    console.error('a rise, rebuild this probe with the `re-frame.schemas.malli`');
    console.error('require removed from the `re-frame.schemas` ns-form and diff');
    console.error('the two figures — that isolates the schemas surface from the');
    console.error('framework growth underneath it.');
    process.exit(1);
  }
}

main();
