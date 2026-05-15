#!/usr/bin/env node
/*
 * Schemas-artefact bundle-cost gate (Spec 010 §Bundle cost, bead rf2-fqbcy).
 *
 * Spec 010 §Bundle cost (lines 567-606) catalogues the gzipped costs
 * of requiring `re-frame.schemas` against the baseline counter:
 *
 *   Baseline (no schemas, no Malli):                       91.7 KB
 *   `[re-frame.schemas]` required, no Malli:               97.2 KB
 *   `[re-frame.schemas]` + `[malli.core]` required:       120.8 KB
 *   Typical app: reg-app-schema + :spec on every reg-*:   121.5 KB
 *
 * This gate uses two probe builds to assert the schemas surface stays
 * within band:
 *
 *   schemas-bundle-probe        — schemas required, NOT Malli.
 *                                 Asserts ≤ 100 KB gzipped (spec row 2
 *                                 + 3 KB headroom).
 *   schemas-bundle-probe-malli  — schemas + Malli adapter required.
 *                                 Asserts ≤ 125 KB gzipped (spec row 3
 *                                 + 5 KB headroom).
 *
 * Also asserts the Malli-on bundle is STRICTLY LARGER than the
 * Malli-off bundle (a regression that DCE-eliminated Malli would
 * silently turn the budget check into a vacuous "pass" — this guard
 * keeps the methodology honest).
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

// Each entry's `gzippedMaxBytes` is the spec's documented figure plus a
// small headroom (3-5 KB) so non-deterministic compression variations
// (zlib version, OS) don't trigger spurious fails. Tighten over time as
// the figures stabilise — every figure below was anchored against the
// spec text rather than current empirical runs to give the gate
// regression-detection teeth from day one.
const BUNDLES = [
  {
    name:            'schemas-bundle-probe',
    bundleDir:       path.join(ROOT, 'out', 'schemas-bundle-probe'),
    specRow:         'row 2 — [re-frame.schemas] required, no Malli (97.2 KB)',
    gzippedMaxBytes: 100 * 1024,   // 100 KB (spec 97.2 KB + 2.8 KB headroom)
  },
  {
    name:            'schemas-bundle-probe-malli',
    bundleDir:       path.join(ROOT, 'out', 'schemas-bundle-probe-malli'),
    specRow:         'row 3 — [re-frame.schemas] + [malli.core] (120.8 KB)',
    gzippedMaxBytes: 125 * 1024,   // 125 KB (spec 120.8 KB + 4.2 KB headroom)
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

  for (const b of BUNDLES) {
    const total = sumGzippedBytes(b.bundleDir);
    if (total == null) {
      console.error(`[schemas-bundle] ${b.name}: bundle dir missing — ${b.bundleDir}`);
      console.error('              Did you run "shadow-cljs release ' +
                    `${b.name}"?`);
      bundlesOk = false;
      continue;
    }
    sizes[b.name] = total;
    const ok = total <= b.gzippedMaxBytes;
    const tag = ok ? 'OK' : 'FAIL';
    report.detail(`  [${tag}] ${b.name}`);
    report.detail(`        spec:      ${b.specRow}`);
    report.detail(`        bundle:    ${fmtKb(total)} gzipped (${total} bytes)`);
    report.detail(`        threshold: ${fmtKb(b.gzippedMaxBytes)} (${b.gzippedMaxBytes} bytes)`);
    if (!ok) {
      bundlesOk = false;
      report.detail(`        REGRESSION: bundle exceeds threshold by ${fmtKb(total - b.gzippedMaxBytes)}`);
    }
  }

  // Methodology guard — the Malli-on bundle MUST be strictly larger
  // than the Malli-off bundle. If both end up the same size, Closure
  // has DCE'd malli.core away (e.g. because the adapter ns no longer
  // publishes the late-bind hooks at load time), and the +Malli gate
  // becomes vacuous.
  let methodologyOk = true;
  if (sizes['schemas-bundle-probe'] != null &&
      sizes['schemas-bundle-probe-malli'] != null) {
    const probe      = sizes['schemas-bundle-probe'];
    const probeMalli = sizes['schemas-bundle-probe-malli'];
    const delta      = probeMalli - probe;
    // Spec row 3 - row 2 = 23.6 KB.  Require ≥ 15 KB of growth to
    // confirm Malli landed; below that, Closure DCE'd something.
    const minDelta = 15 * 1024;
    const ok = delta >= minDelta;
    const tag = ok ? 'OK' : 'FAIL';
    report.detail('');
    report.detail(`  [${tag}] methodology guard — Malli-on bundle is strictly larger`);
    report.detail(`        delta: ${fmtKb(delta)} (${delta} bytes)`);
    report.detail(`        threshold: ≥ ${fmtKb(minDelta)} (Spec row 3 − row 2 = 23.6 KB)`);
    if (!ok) {
      methodologyOk = false;
      report.detail('        FAIL — Malli adapter\'s body was eliminated;');
      report.detail('        the +Malli gate is now vacuous.');
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
