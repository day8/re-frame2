'use strict';
// FIXTURE — the third hole. This driver NEVER clears the cache: its only
// occurrences of the clear are STRING text — once single-quoted, once inside a
// template — which is what a refactor leaves when it demotes the call to
// something the driver merely says about itself. Blanking comments does not
// touch either one, so both stood in for the call and all four checks passed on
// a file with nothing executable to pass. Read as TEXT and never executed.
//
// LIKE ITS COMMENT-ONLY SIBLING it requires `lane_cache.cjs` and binds nothing:
// a clear-less driver that keeps the binding is already reported by
// `no-unused-vars` a gate earlier, so the variant no linter can see is the one
// worth modelling here. Both forms are present on purpose — a projection that
// blanked only template bodies, or only single-quoted ones, would still pass
// this file on the half it missed.
const path = require('node:path');
const { spawnSync } = require('node:child_process');
require('./lane_cache.cjs');

const IMPL = path.resolve(__dirname, '..', '..', '..', '..', '..');
const BUILD = 'freehand-release';
const CONFIG_MERGE = '{:output-dir "fixture" :init-fn fixture.arm/main}';
const runner = path.join(IMPL, 'node_modules', 'shadow-cljs', 'cli', 'runner.js');

// The words survive; the call does not. Note that the id named in the string
// AGREES with the one released, so the build-id comparison has nothing to
// object to either — this file is caught for having no clear, not for
// clearing the wrong thing.
const POLICY = 'resetLaneBuildCache(IMPL, BUILD) before the release';
console.error(`[fixture] ${BUILD}: resetLaneBuildCache(IMPL, BUILD) — ${POLICY}`);

spawnSync(process.execPath, [runner, 'release', BUILD, '--config-merge', CONFIG_MERGE], {
  cwd: IMPL,
  stdio: 'inherit',
});
