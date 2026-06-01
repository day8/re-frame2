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
const { listReleaseJsFiles } = require('./lib/read-release-bundle.cjs');

const ROOT = path.resolve(__dirname, '..');
const report = createGateReporter();

// ----- the schemas bundle-cost contract -------------------------------------

// Per rf2-v96fh both probes pull Malli (the facade requires the
// adapter), so both sit at the schemas-surface-with-Malli cost
// (~91 KB gzipped empirically; the spec's older 120.8 KB row was a
// conservative pre-Closure-tuning estimate). The ≤ 100 KB ceiling
// leaves ~9 KB headroom over the empirical figure so non-deterministic
// compression variations (zlib version, OS) don't trigger spurious
// fails. Tighten over time as the figures stabilise.
const BUNDLES = [
  {
    name:            'schemas-bundle-probe',
    bundleDir:       path.join(ROOT, 'out', 'schemas-bundle-probe'),
    specRow:         '[re-frame.schemas] required ⇒ Malli wired (rf2-v96fh)',
    gzippedMaxBytes: 100 * 1024,   // 100 KB (empirical ~91 KB + ~9 KB headroom)
  },
  {
    name:            'schemas-bundle-probe-malli',
    bundleDir:       path.join(ROOT, 'out', 'schemas-bundle-probe-malli'),
    specRow:         '[re-frame.schemas] + explicit [malli] (redundant, rf2-v96fh)',
    gzippedMaxBytes: 100 * 1024,   // 100 KB (same surface as the sibling probe)
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
    console.error('budgets are normative. A regression most likely means:');
    console.error('  - A transitive require dragged a heavy ns into the schemas');
    console.error('    surface; check `re-frame.schemas` ns-form and downstream.');
    console.error('  - The Malli adapter\'s ns-load body grew unexpectedly.');
    console.error('  - shadow-cljs / Closure compiler upgraded; if the new');
    console.error('    figures reflect a deliberate substrate change, update');
    console.error('    the spec § first, then bump the thresholds here in');
    console.error('    lockstep.');
    process.exit(1);
  }
}

main();
