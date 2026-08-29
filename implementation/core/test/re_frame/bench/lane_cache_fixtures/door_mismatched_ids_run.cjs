'use strict';
// FIXTURE — the mismatched-id hole, THE DOOR SHAPE. The clear and the build
// name TWO DIFFERENT build ids, so this driver empties a cache nobody builds
// into and builds into a cache nobody cleared.
//
// Its sibling `mismatched_ids_run.cjs` proves the comparison on the direct
// spawn, where the released id sits in the argv after `'release'`. Here it sits
// in a `buildId:` property, read by a DIFFERENT expression — so without this
// fixture the door half of the comparison would be unproven, and a door rider
// could carry the exact defect the direct riders are checked for. Read as TEXT
// and never executed.
const path = require('node:path');
const { resetLaneBuildCache } = require('../lane_cache.cjs');
const { shadowBuild } = require('../../../../../../bench/hicasso/src/re_frame/bench/hicasso/lane_build.cjs');

const PROJECT = path.resolve(__dirname, '..', '..', '..', '..', '..', '..', 'bench', 'hicasso');
const CLEAR_BUILD = 'hicasso-bench';
const RELEASE_BUILD = 'hicasso-bench-arm';

if (resetLaneBuildCache(PROJECT, CLEAR_BUILD)) {
  console.error(`[fixture] cleared .shadow-cljs/builds/${CLEAR_BUILD}`);
}

shadowBuild({
  project: PROJECT,
  mode: 'release',
  buildId: RELEASE_BUILD,
  configMerge: '{:output-dir "fixture" :modules {:main {:init-fn fixture.arm/main}}}',
  tag: 'fixture',
});
