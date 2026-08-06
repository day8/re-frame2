'use strict';
// FIXTURE — the second hole. This driver NEVER clears the cache: its only
// occurrence of the clear is COMMENT TEXT, the shape you get when a refactor
// deletes the call and leaves the explanation behind. A text search for
// `resetLaneBuildCache(` is satisfied; nothing executes. Read as TEXT and
// never executed.
const path = require('node:path');
const { spawnSync } = require('node:child_process');
const { resetLaneBuildCache } = require('./lane_cache.cjs');

const IMPL = path.resolve(__dirname, '..', '..', '..', '..', '..');
const BUILD = 'freehand-release';
const CONFIG_MERGE = '{:output-dir "fixture" :init-fn fixture.arm/main}';
const runner = path.join(IMPL, 'node_modules', 'shadow-cljs', 'cli', 'runner.js');

// This driver merges its own `:init-fn` onto `BUILD`, so `BUILD`'s cache entry
// was written by a different program — resetLaneBuildCache(IMPL, BUILD) is how
// that's handled. The apostrophes above are deliberate: a comment scanner that
// treats them as string delimiters swallows the code below.

spawnSync(process.execPath, [runner, 'release', BUILD, '--config-merge', CONFIG_MERGE], {
  cwd: IMPL,
  stdio: 'inherit',
});
