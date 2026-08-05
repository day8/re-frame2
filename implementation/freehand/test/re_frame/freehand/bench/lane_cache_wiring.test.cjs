#!/usr/bin/env node
'use strict';
// EVERY RIDER OF A SHARED BUILD ID CLEARS ITS CACHE — rf2-t4j7c.
//
//     node freehand/test/re_frame/freehand/bench/lane_cache_wiring.test.cjs
//
// THE DEFECT THIS PINS. Seven drivers in this directory compile by merging
// their OWN `:init-fn` and `:output-dir` onto the ONE `freehand-release`
// build id. shadow-cljs derives the build cache directory from the build id
// ALONE — `<cache-root>/builds/<build-id>/<mode>`, fixed before any
// `--config-merge` data is applied — so the arm is invisible to the cache key
// and seven different programs shared one cache entry. None of the seven
// cleared it. `lane_cache.cjs` carries the fault class, the isolation that
// found the carrier (`shadow-js/index.json.transit`) and the alternatives that
// were rejected with reasons (rf2-2rtt6.20).
//
// MEASURED HERE, on unmodified `main`, at the commit this test lands against:
//
//     cold  -> b7's config-merge      204 files, 149 compiled, exit 0
//     warm  -> ladder's config-merge  160 files,  11 compiled, exit 0
//     cold  -> ladder's config-merge  160 files, 105 compiled, exit 0
//
// The two ladder bundles differ (`bfc7abfe…` against `fae4cd71…`, 649,134 B
// against 649,245 B) and only the cold one runs. Loaded in headless Chromium
// and left 3s to settle, the 11-compiled bundle raises
//
//     Cannot read properties of undefined (reading 'd')
//
// before its entry executes, while the 105-compiled bundle raises nothing and
// reaches its own application code. **Both builds exit 0**, which is the whole
// reason this has to be a source-level gate: no build-time signal exists to
// check, and the failure only appears when a page executes the bundle.
//
// WHY A GATE AND NOT JUST THE FIX. The three drivers a bead named "predate
// [the rule] or were never brought onto it" — the invariant was written down
// in `lane_cache.cjs` and enforced nowhere, so drivers kept landing armed and
// four more had accumulated by the time anyone counted. This file DISCOVERS
// the riders rather than listing them, so the eighth fails here instead of in
// a published table.
//
// Wired into implementation/package.json via `test:script-helpers`.

const assert = require('node:assert');
const fs = require('node:fs');
const path = require('node:path');
const test = require('node:test');

const DIR = __dirname;

// A rider is any file here that spawns a shadow-cljs release build with its
// own `--config-merge`. Discovered, never listed: a new driver is caught by
// existing, not by someone remembering to add it.
//
// EVERY PATTERN BELOW IS QUOTE-AGNOSTIC AND TOLERATES WHITESPACE, because the
// nearest sibling gate to this one was defeated by exactly that — a scan keyed
// to single quotes walked past a double-quoted call site, and its "found
// nothing at all" fallback was keyed the same way, so two checks agreed
// because both were blind. The two predicates here are therefore INDEPENDENT
// and are required to agree; disagreement is a failure, not a silence.
const SOURCES = fs.readdirSync(DIR)
  .filter((f) => f.endsWith('.cjs') && !f.endsWith('.test.cjs') && f !== 'lane_cache.cjs')
  .map((f) => ({ file: f, src: fs.readFileSync(path.join(DIR, f), 'utf8') }));

// Signal 1: it hands `--config-merge` to shadow-cljs's own CLI runner.
const byRunner = SOURCES.filter(
  ({ src }) => /runner\.js/.test(src) && /['"]--config-merge['"]/.test(src)
);
// Signal 2: it passes `release` as a spawn argument.
const bySpawn = SOURCES.filter(
  ({ src }) => /['"]release['"]\s*,/.test(src) && /['"]--config-merge['"]/.test(src)
);

const RIDERS = byRunner;

test('the two independent rider scans agree (neither pattern has drifted)', () => {
  const a = byRunner.map((r) => r.file).sort();
  const b = bySpawn.map((r) => r.file).sort();
  assert.deepStrictEqual(
    a, b,
    'the runner-path scan and the release-argv scan disagree, so one of them is ' +
      'stale. Repair the pattern — do not delete the check.'
  );
});

test('the rider set is non-empty (a discovery that finds nothing is not a pass)', () => {
  // The exact fail-open this lane keeps finding: a scan whose pattern drifted
  // reports zero riders and every assertion below vacuously passes.
  assert.ok(
    RIDERS.length >= 7,
    `expected at least the 7 known freehand-release riders, found ${RIDERS.length}: ` +
      RIDERS.map((r) => r.file).join(', ')
  );
});

for (const { file, src } of RIDERS) {
  test(`${file} requires lane_cache.cjs`, () => {
    assert.match(
      src,
      /require\('\.\/lane_cache\.cjs'\)/,
      `${file} spawns a shared-build-id release and must require ./lane_cache.cjs`
    );
  });

  test(`${file} calls resetLaneBuildCache BEFORE it spawns the build`, () => {
    // `search` with a quote-agnostic pattern, not `indexOf` on one spelling.
    const clear = src.search(/resetLaneBuildCache\s*\(/);
    const spawn = src.search(/['"]release['"]\s*,/);
    assert.notStrictEqual(clear, -1, `${file} never calls resetLaneBuildCache`);
    assert.notStrictEqual(spawn, -1, `${file} has no release spawn to guard`);
    assert.ok(
      clear < spawn,
      `${file} clears the cache AFTER spawning the build, which clears nothing`
    );
  });

  test(`${file} names the build id once, not as a literal in the spawn argv`, () => {
    // The clear and the build must be incapable of naming different ids.
    // A string literal in the argv slot is how they drift apart.
    assert.doesNotMatch(
      src,
      /['"]release['"]\s*,\s*['"]/,
      `${file} passes a literal build id to spawnSync; hoist it to a const and ` +
        'pass that same const to resetLaneBuildCache'
    );
  });
}
