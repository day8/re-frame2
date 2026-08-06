'use strict';
// FIXTURE — legitimate wiring. Every check in `lane_cache_wiring.test.cjs`
// must PASS on this file. It is the control: a gate that rejects valid wiring
// is worse than one that admits invalid wiring, because everyone routes around
// it. Read as TEXT and never executed — see the fixtures note in the gate.
const path = require('node:path');
const { spawnSync } = require('node:child_process');
const { resetLaneBuildCache } = require('./lane_cache.cjs');

const IMPL = path.resolve(__dirname, '..', '..', '..', '..', '..');
const BUILD = 'freehand-release';
const CONFIG_MERGE = '{:output-dir "fixture" :init-fn fixture.arm/main}';
const runner = path.join(IMPL, 'node_modules', 'shadow-cljs', 'cli', 'runner.js');

// This driver merges its own `:init-fn` onto `BUILD`, so `BUILD`'s cache entry
// was written by a different program (rf2-2rtt6.20).
if (resetLaneBuildCache(IMPL, BUILD)) {
  console.error(`[fixture] cleared .shadow-cljs/builds/${BUILD}`);
}

spawnSync(process.execPath, [runner, 'release', BUILD, '--config-merge', CONFIG_MERGE], {
  cwd: IMPL,
  stdio: 'inherit',
});
