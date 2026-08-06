'use strict';
// FIXTURE — the second hole. This driver NEVER clears the cache: its only
// occurrence of the clear is COMMENT TEXT, the shape you get when a refactor
// deletes the call and leaves the explanation behind. A text search for
// `resetLaneBuildCache(` is satisfied; nothing executes. Read as TEXT and
// never executed.
//
// IT REQUIRES `lane_cache.cjs` WITHOUT BINDING ANYTHING, which is the point
// rather than an oversight — do not tidy it back into a destructure. A
// refactor that deletes the clear can leave either shape behind, and the two
// are not equally dangerous. Keep the binding and `no-unused-vars` reports the
// orphan, so the lint gate catches that driver before this one ever sees it;
// the repo's ESLint config names that arm outright ("a bad `require` whose
// binding is NEVER read is caught, by `no-unused-vars`"). Drop the binding too
// and nothing in the lint path can see anything wrong: the file parses, every
// name is used, and only the missing CALL separates it from a correct driver.
// That second variant is the one no other gate owns, so it is the one modelled
// here — the fixture covers the hole its sibling gate cannot.
const path = require('node:path');
const { spawnSync } = require('node:child_process');
require('./lane_cache.cjs');

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
