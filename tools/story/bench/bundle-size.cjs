#!/usr/bin/env node
/*
 * tools/story/bench/bundle-size.cjs — one-shot Story bundle-size benchmark
 * (bead rf2-xgay8; companion findings doc:
 * ai/findings/story-bundle-vs-sb9-20260513.md).
 *
 * Purpose
 * -------
 *
 * Measures the shipped byte cost of Story on a representative worked
 * example (`examples/reagent/counter_with_stories`) under three views:
 *
 *   A. Plain counter, no Story registrations            (:examples/counter)
 *   B. Counter + Story (the rf/story panels enabled)    (:examples/counter-with-stories)
 *   C. Static-export build (the publishable Story shell)
 *      (:story-static/counter-with-stories — see
 *      tools/story/spec/013-Static-Build.md)
 *
 * For each build the script reports:
 *   - raw bytes of `out/<target>/main.js`
 *   - gzipped bytes (Node's built-in zlib)
 *   - per-build delta vs the no-Story baseline (A)
 *
 * The delta (B - A) is the cost of "I enabled Story for this app in dev"
 * — i.e. the JS that would ship if a consumer were to deploy Story as
 * dev-bundle-equivalent. Production builds with the
 * `re-frame.story.config/*enabled?*` `goog-define` flipped to false elide
 * the entire Story surface to zero bytes (per Spec 005 §Production
 * elision under `:advanced`); that path is enforced by
 * `implementation/scripts/check-bundle-isolation.cjs` and isn't
 * remeasured here.
 *
 * Why one-shot, not CI
 * --------------------
 *
 * The deliverable is informational, not a regression gate. The
 * production-elision contract already has a gate
 * (`check-bundle-isolation.cjs`); the Story dev bundle's absolute size
 * doesn't have a meaningful budget yet, and a hard cap would just bake
 * in the current implementation cost. Re-run this script when the
 * comparison number needs refreshing.
 *
 * Invocation
 * ----------
 *
 *   cd implementation
 *   node ../tools/story/bench/bundle-size.cjs
 *
 * Set BENCH_SKIP_BUILD=1 to skip the `shadow-cljs release` step
 * (useful when the bundles are already fresh in `implementation/out/`).
 *
 * Exit 0 always — informational only. The script reports numbers; the
 * companion findings doc carries the comparison narrative.
 */

'use strict';

const { spawnSync } = require('child_process');
const fs   = require('fs');
const path = require('path');
const zlib = require('zlib');

const BENCH_DIR = __dirname;                                          // tools/story/bench
const REPO_ROOT = path.resolve(BENCH_DIR, '..', '..', '..');          // <repo>
const IMPL_ROOT = path.join(REPO_ROOT, 'implementation');             // <repo>/implementation

const BUILDS = [
  {
    label:  'A. counter (no Story)',
    target: 'examples/counter',
    outDir: path.join(IMPL_ROOT, 'out', 'examples', 'counter'),
    role:   'baseline',
  },
  {
    label:  'B. counter + Story (Story enabled — dev-bundle-equivalent)',
    target: 'examples/counter-with-stories',
    outDir: path.join(IMPL_ROOT, 'out', 'examples', 'counter-with-stories'),
    role:   'story-on',
  },
  {
    label:  'C. story-static export (publishable Story shell)',
    target: 'story-static/counter-with-stories',
    outDir: path.join(IMPL_ROOT, 'out', 'story-static', 'counter-with-stories'),
    role:   'story-static',
  },
];

function release(targets) {
  const isWin = process.platform === 'win32';
  const cmd   = isWin ? 'npx.cmd' : 'npx';
  const args  = ['shadow-cljs', 'release', ...targets];
  console.log(`> (cwd=implementation) ${cmd} ${args.join(' ')}`);
  const result = spawnSync(cmd, args, {
    cwd:   IMPL_ROOT,
    stdio: 'inherit',
    shell: isWin,
  });
  if (result.status !== 0) {
    throw new Error(`shadow-cljs release failed (exit ${result.status})`);
  }
}

function measure(file) {
  const raw = fs.readFileSync(file);
  const gz  = zlib.gzipSync(raw, { level: 9 });
  return { raw: raw.length, gz: gz.length };
}

function fmt(n) {
  return n.toLocaleString('en-US');
}

function pct(part, whole) {
  if (whole === 0) return 'n/a';
  return ((part / whole) * 100).toFixed(1) + '%';
}

function main() {
  console.log('=== tools/story/bench/bundle-size.cjs ===');
  console.log(`repo root:           ${REPO_ROOT}`);
  console.log(`implementation root: ${IMPL_ROOT}`);
  console.log('');

  if (process.env.BENCH_SKIP_BUILD === '1') {
    console.log('BENCH_SKIP_BUILD=1 — assuming bundles in implementation/out/ are fresh.');
  } else {
    release(BUILDS.map((b) => b.target));
  }
  console.log('');

  // Measure each.
  const measured = BUILDS.map((b) => {
    const main = path.join(b.outDir, 'main.js');
    if (!fs.existsSync(main)) {
      throw new Error(`Bundle missing: ${main}. Ran the release step? (or set BENCH_SKIP_BUILD=1 only when out/ is fresh.)`);
    }
    return { ...b, mainFile: main, ...measure(main) };
  });

  const baseline = measured.find((m) => m.role === 'baseline');

  // Print the table.
  console.log('=== Bundle sizes (advanced compile, main.js only) ===');
  console.log('');
  const w = '─'.repeat(82);
  console.log(w);
  console.log('build                                                |   raw bytes |   gzipped');
  console.log(w);
  for (const m of measured) {
    const raw = fmt(m.raw).padStart(11);
    const gz  = fmt(m.gz).padStart(11);
    console.log(`${m.label.padEnd(53)}|${raw} |${gz}`);
  }
  console.log(w);
  console.log('');

  // Print the deltas.
  console.log('=== Deltas (vs baseline A) ===');
  console.log('');
  for (const m of measured) {
    if (m.role === 'baseline') continue;
    const rawΔ = m.raw - baseline.raw;
    const gzΔ  = m.gz  - baseline.gz;
    console.log(`${m.label}`);
    console.log(`  raw delta:  +${fmt(rawΔ)} bytes (+${pct(rawΔ, baseline.raw)} over baseline)`);
    console.log(`  gz  delta:  +${fmt(gzΔ)} bytes (+${pct(gzΔ, baseline.gz)} over baseline)`);
    console.log('');
  }

  // Headline number for the findings doc.
  const storyOn = measured.find((m) => m.role === 'story-on');
  const rawCost = storyOn.raw - baseline.raw;
  const gzCost  = storyOn.gz  - baseline.gz;
  console.log('=== Headline: "What does Story cost a consumer in dev?" ===');
  console.log(`  raw:  ${fmt(rawCost)} bytes (~${(rawCost / 1024).toFixed(0)} KiB)`);
  console.log(`  gz:   ${fmt(gzCost)} bytes (~${(gzCost / 1024).toFixed(0)} KiB)`);
  console.log('');
  console.log('Reminder: under :advanced + re-frame.story.config/*enabled?*=false,');
  console.log('this cost collapses to ZERO. The gate is implementation/scripts/check-bundle-isolation.cjs.');
  console.log('');
  console.log('See ai/findings/story-bundle-vs-sb9-20260513.md for the SB9 comparison.');

  // Always exit 0 — informational.
  process.exit(0);
}

try {
  main();
} catch (err) {
  console.error(err.message || err);
  process.exit(1);
}
