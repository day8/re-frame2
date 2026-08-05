#!/usr/bin/env node
'use strict';
//
// DRIVER for the key warning's dev pre-pass clock (rf2-2rtt6.104).
//
//     node freehand/test/re_frame/bench/hicasso/keywarn_clock_run.cjs
//
// Stdout is the artefact: a markdown table quoted into the PR body, with the
// human framing riding as `;;` comments. See `keywarn_clock.cljs` for what is
// measured and why a local ablation copy is the right shape for it.
//
// ## NO EDIT TO shadow-cljs.edn
//
// A `:node-script` compile is what this needs, and the lane's own
// `:hicasso-bench` is a `:browser` build. Rather than add a build id — HD-017
// makes that a hot-zone edit to `implementation/shadow-cljs.edn` and therefore
// a sequenced dispatch — this rides the existing `:freehand-bench-node`
// `:node-script` id through `--config-merge`, supplying its own `:main` and
// `:output-to`. Exactly the mechanism `compile_gate.cjs` and `jsfb_build.cjs`
// use on `:hicasso-bench`; only the borrowed id differs.
//
// `compile`, not `release`: the pre-pass exists only where `goog.DEBUG` is
// true, and a dev compile is the build the figure is ABOUT. The clock refuses
// to run under a production build rather than reporting a zero.
//
// The shared build cache is cleared first (rf2-2rtt6.20): one build id serving
// two entries is precisely the shape that trap has.

const { spawnSync } = require('node:child_process');
const fs = require('node:fs');
const path = require('node:path');
const { shadowBuildVerdict, reportRefusal } = require('./lane_build.cjs');

const IMPL = path.resolve(__dirname, '../../../../..');
const BUILD_ID = 'freehand-bench-node';
const OUT = 'out/keywarn-clock.js';
const TAG = 'keywarn-clock';

fs.rmSync(path.join(IMPL, '.shadow-cljs', 'builds', BUILD_ID), {
  recursive: true, force: true,
});

// EDN, and ONE LINE: shadow-cljs's CLI re-splits `--config-merge` on
// whitespace once the data contains a newline, then reports `EOF while
// reading` from a fragment. JSON is not accepted at all.
const merge =
  `{:main re-frame.bench.hicasso.keywarn-clock/-main :output-to "${OUT}"}`;

console.error(`[${TAG}] compiling ${BUILD_ID} -> ${OUT}`);
const verdict = shadowBuildVerdict({
  impl: IMPL, mode: 'compile', buildId: BUILD_ID, configMerge: merge,
});
if (!verdict.ok) {
  reportRefusal(TAG, verdict);
  process.exit(1);
}

const r = spawnSync(process.execPath, [path.join(IMPL, OUT)], {
  cwd: IMPL, stdio: 'inherit',
});
process.exit(r.status === null ? 1 : r.status);
