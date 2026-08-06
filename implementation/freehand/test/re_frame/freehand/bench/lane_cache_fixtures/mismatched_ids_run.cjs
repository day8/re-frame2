'use strict';
// FIXTURE — the hole rf2-t4j7c reopened for. The clear and the release name
// TWO DIFFERENT build ids, so this driver empties a cache nobody builds into
// and builds into a cache nobody cleared. It satisfies rider discovery, the
// require, clear-before-spawn and the no-literal check; only the build-id
// comparison can see it. Read as TEXT and never executed.
const path = require('node:path');
const { spawnSync } = require('node:child_process');
const { resetLaneBuildCache } = require('./lane_cache.cjs');

const IMPL = path.resolve(__dirname, '..', '..', '..', '..', '..');
const CLEAR_BUILD = 'freehand-release';
const RELEASE_BUILD = 'freehand-release-arm';
const CONFIG_MERGE = '{:output-dir "fixture" :init-fn fixture.arm/main}';
const runner = path.join(IMPL, 'node_modules', 'shadow-cljs', 'cli', 'runner.js');

if (resetLaneBuildCache(IMPL, CLEAR_BUILD)) {
  console.error(`[fixture] cleared .shadow-cljs/builds/${CLEAR_BUILD}`);
}

spawnSync(process.execPath, [runner, 'release', RELEASE_BUILD, '--config-merge', CONFIG_MERGE], {
  cwd: IMPL,
  stdio: 'inherit',
});
