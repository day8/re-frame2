#!/usr/bin/env node
'use strict';
//
// DRIVER for the boundary hand-off walk clock.
//
//     node src/re_frame/bench/fresco/boundary_walk_clock_run.cjs          (from bench/fresco/)
//     node src/re_frame/bench/fresco/boundary_walk_clock_run.cjs --dev    a dev compile instead
//
// Stdout is the artefact: a markdown table, framing as `;;` comments. See
// `boundary_walk_clock.cljs` for what is measured. The clock requires the
// SHIPPING codec, so it prices whatever codec is in the tree: run it before
// and after a change to the hand-off and compare the tables.
//
// `release` by default, with goog.DEBUG false so every dev-only check is
// compiled out as it is in what a consumer ships. `:simple` rather than
// `:advanced`: the lane's `:node-script` id carries no externs, and
// `:advanced` renames interop the bundle then calls. `--dev` takes a
// `compile` for the dev-build figure.
//
// The lane's `:node-script` id is shared with `ssr/driver.cjs` and
// `keywarn_clock_run.cjs`, so the build cache is cleared first and this
// driver supplies its own `:main` and `:output-to` through `--config-merge`.

const { spawnSync } = require('node:child_process');
const path = require('node:path');
const { shadowBuildVerdict, reportRefusal } = require('./lane_build.cjs');
const { resetLaneBuildCache } = require('../../../../../../implementation/core/test/re_frame/bench/lane_cache.cjs');

const PROJECT = path.resolve(__dirname, '../../../..');
const BUILD_ID = 'fresco-bench-node';
const OUT = 'out/boundary-walk-clock.js';
const TAG = 'boundary-walk-clock';
const MODE = process.argv.includes('--dev') ? 'compile' : 'release';

resetLaneBuildCache(PROJECT, BUILD_ID);

// EDN, and ONE LINE: shadow-cljs's CLI re-splits `--config-merge` on
// whitespace once the data contains a newline.
const merge =
  `{:main re-frame.bench.fresco.boundary-walk-clock/-main :output-to "${OUT}"` +
  (MODE === 'release'
    ? ` :compiler-options {:optimizations :simple :closure-defines {goog.DEBUG false}}}`
    : '}');

console.error(`[${TAG}] ${MODE} ${BUILD_ID} -> ${OUT}`);
const verdict = shadowBuildVerdict({
  project: PROJECT, mode: MODE, buildId: BUILD_ID, configMerge: merge,
});
if (!verdict.ok) {
  reportRefusal(TAG, verdict);
  process.exit(1);
}

// A `:node-script` resolves `react` through Node at run time, and the lane has
// no `node_modules` of its own, so the run is pointed at the implementation's.
const r = spawnSync(process.execPath, [path.join(PROJECT, OUT)], {
  cwd: PROJECT,
  stdio: 'inherit',
  env: { ...process.env, NODE_PATH: path.resolve(PROJECT, '../../implementation/node_modules') },
});
process.exit(r.status === null ? 1 : r.status);
