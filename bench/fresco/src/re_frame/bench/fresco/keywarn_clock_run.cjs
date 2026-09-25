#!/usr/bin/env node
'use strict';
//
// DRIVER for the key warning's dev pre-pass clock.
//
//     node src/re_frame/bench/fresco/keywarn_clock_run.cjs     (from bench/fresco/)
//
// Stdout is the artefact: a markdown table, with the human framing riding as
// `;;` comments. See `keywarn_clock.cljs` for what is measured and why a
// local ablation copy is the right shape for it.
//
// ## WHICH BUILD ID
//
// A `:node-script` compile is what this needs, and the lane's `:fresco-bench`
// is a `:browser` build. So the id below is the lane's own `:node-script` id,
// declared beside `:fresco-bench` and shared with `ssr/driver.cjs` — one id,
// both of the lane's Node programs. This driver supplies its own `:main` and
// `:output-to` through `--config-merge`, the same mechanism `compile_gate.cjs`
// and `jsfb_build.cjs` use on `:fresco-bench`.
//
// `compile`, not `release`: the pre-pass exists only where `goog.DEBUG` is
// true, and a dev compile is the build the figure is ABOUT. The clock refuses
// to run under a production build rather than reporting a zero.
//
// The shared build cache is cleared first: one build id serving two entries
// would otherwise run a bundle this driver did not build (`lane_cache.cjs`).

const { spawnSync } = require('node:child_process');
const fs = require('node:fs');
const path = require('node:path');
const { shadowBuildVerdict, reportRefusal } = require('./lane_build.cjs');

const PROJECT = path.resolve(__dirname, '../../../..');
const BUILD_ID = 'fresco-bench-node';
const OUT = 'out/keywarn-clock.js';
const TAG = 'keywarn-clock';

fs.rmSync(path.join(PROJECT, '.shadow-cljs', 'builds', BUILD_ID), {
  recursive: true, force: true,
});

// EDN, and ONE LINE: shadow-cljs's CLI re-splits `--config-merge` on
// whitespace once the data contains a newline, then reports `EOF while
// reading` from a fragment. JSON is not accepted at all.
const merge =
  `{:main re-frame.bench.fresco.keywarn-clock/-main :output-to "${OUT}"}`;

console.error(`[${TAG}] compiling ${BUILD_ID} -> ${OUT}`);
const verdict = shadowBuildVerdict({
  project: PROJECT, mode: 'compile', buildId: BUILD_ID, configMerge: merge,
});
if (!verdict.ok) {
  reportRefusal(TAG, verdict);
  process.exit(1);
}

const r = spawnSync(process.execPath, [path.join(PROJECT, OUT)], {
  cwd: PROJECT, stdio: 'inherit',
});
process.exit(r.status === null ? 1 : r.status);
