#!/usr/bin/env node
/*
 * tools/story/bench/bundle-size.cjs — one-shot Story bundle-size benchmark.
 *
 * Purpose
 * -------
 *
 * Measures the shipped byte cost of Story on a representative worked
 * example (`tools/story/testbeds/counter_with_stories`) under three views:
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
 * dev-bundle-equivalent. None of the three builds sets
 * `re-frame.story.config/enabled?` to false, so this script does not
 * measure the production path Spec 005 §Production elision under
 * `:advanced` specifies. `npm run test:bundle-isolation` builds that path
 * and checks its shell (Spec 005 §Verification), and 005 §Residue
 * measures what it still carries.
 *
 * Why one-shot, not CI
 * --------------------
 *
 * The deliverable is informational, not a regression gate. The Story dev
 * bundle's absolute size has no meaningful budget, and a hard cap would
 * just bake in the current implementation cost. Re-run this script when
 * the comparison number needs refreshing.
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
 * Exit 0 whenever it measures — informational only; a failed release or
 * a missing bundle exits 1. The script reports numbers; the output is the
 * comparison record.
 */

'use strict';

const { spawnSync } = require('child_process');
const fs   = require('fs');
const path = require('path');
const zlib = require('zlib');
const { SHADOW_CLI } = require('../../../implementation/scripts/lib/shadow.cjs');

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

// Through the shared shadow-cljs launcher (build lane, owned process tree),
// shell-free under this node binary.
function release(targets) {
  console.log(`> (cwd=implementation) shadow-cljs release ${targets.join(' ')}`);
  const result = spawnSync(process.execPath, [SHADOW_CLI, 'release', ...targets], {
    cwd:   IMPL_ROOT,
    stdio: 'inherit',
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

  // Headline number: what enabling Story costs a consumer's dev bundle.
  const storyOn = measured.find((m) => m.role === 'story-on');
  const rawCost = storyOn.raw - baseline.raw;
  const gzCost  = storyOn.gz  - baseline.gz;
  console.log('=== Headline: "What does Story cost a consumer in dev?" ===');
  console.log(`  raw:  ${fmt(rawCost)} bytes (~${(rawCost / 1024).toFixed(0)} KiB)`);
  console.log(`  gz:   ${fmt(gzCost)} bytes (~${(gzCost / 1024).toFixed(0)} KiB)`);
  console.log('');
  console.log('None of these builds sets re-frame.story.config/enabled? to false, so this');
  console.log('measures no production-elided Story build; npm run test:bundle-isolation checks one.');
  console.log('');
  console.log('See tools/story/spec/005-SOTA-Features.md §Bundle-size comparison for the Storybook 9 comparison.');

  // Always exit 0 — informational.
  process.exit(0);
}

try {
  main();
} catch (err) {
  console.error(err.message || err);
  process.exit(1);
}
