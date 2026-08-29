'use strict';
// FIXTURE — legitimate wiring, THE DOOR SHAPE. The control for the second of
// the two ways a program reaches a shadow-cljs release in this repo: it does
// not spawn `cli/runner.js` itself, it hands the build to the lane's one build
// door (`lane_build.cjs`), which owns the spawn. Every check in
// `lane_cache_wiring.test.cjs` must PASS on this file.
//
// This shape carries most of the riders — the whole `hicasso-bench` lane goes
// through the door — and it is invisible to a `runner.js` scan, which is the
// gap rf2-d19nf found. Read as TEXT and never executed.
const path = require('node:path');
const { resetLaneBuildCache } = require('../lane_cache.cjs');
const { shadowBuild } = require('../../../../../../bench/hicasso/src/re_frame/bench/hicasso/lane_build.cjs');

const PROJECT = path.resolve(__dirname, '..', '..', '..', '..', '..', '..', 'bench', 'hicasso');
const BUILD_ID = 'hicasso-bench';

// This driver merges its own `:init-fn` onto `BUILD_ID`, so `BUILD_ID`'s cache
// entry was written by a different program (rf2-2rtt6.20).
if (resetLaneBuildCache(PROJECT, BUILD_ID)) {
  console.error(`[fixture] cleared .shadow-cljs/builds/${BUILD_ID}`);
}

shadowBuild({
  project: PROJECT,
  mode: 'release',
  buildId: BUILD_ID,
  configMerge: '{:output-dir "fixture" :modules {:main {:init-fn fixture.arm/main}}}',
  tag: 'fixture',
});
