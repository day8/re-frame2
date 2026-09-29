#!/usr/bin/env node

'use strict';

/*
 * A config-merged compile must leave a bundle that RUNS.
 *
 * THE HAZARD. `compile-node-test.cjs ... --config-merge ...` clears the build
 * id's shadow-cljs cache entry, `.shadow-cljs/builds/<id>`, before AND after
 * compiling, so a focused config leaves no residue for the shared id's next
 * full compile. A dev-mode node bundle loads its `cljs-runtime` files from the
 * build's `:output-dir` at RUN time, and shadow-cljs defaults that directory to
 * `<mode>/out` inside the very entry the clear removes. Unless the wrapper
 * moves it, the focused bundle compiles green and then dies on its first
 * `require` — `ENOENT .../builds/<id>/dev/out/cljs-runtime/...`, exit 1.
 *
 * WHAT RUNS FOR REAL: the wrapper's own bytes, through its own CLI, with its
 * own cache clear, and then the bundle it leaves behind, under `node` — the
 * step that fails. The wrapper is COPIED unmodified into a scratch lane beside
 * the `lane_cache.cjs` it requires, so the `IMPL_DIR` it derives from its own
 * location — and so the cache entry it clears — is the lane's, never this
 * checkout's.
 *
 * WHAT IS SUBSTITUTED is shadow-cljs, by a stand-in doing the two things the
 * defect turns on. It writes the runtime to the `:output-dir` named by the LAST
 * `--config-merge` value that names one (shadow-cljs deep-merges them in
 * order), else to shadow-cljs's own default for a node target,
 * `.shadow-cljs/builds/<id>/dev/out` (`shadow.build.targets.shared/
 * set-output-dir`). And it writes a bundle that loads that runtime relative to
 * `__dirname`, as shadow-cljs's dev node output does.
 *
 * THE CONTROL is the full compile, with no `--config-merge`: the same stand-in
 * writes its runtime INSIDE the cache entry and the wrapper leaves it there.
 * That is what shows the stand-in's default really lands in the directory the
 * focused arm clears, so the focused arm's green is the wrapper's doing.
 *
 * Discovered by `npm run test:scripts`.
 */

const assert = require('assert/strict');
const { spawnSync } = require('node:child_process');
const fs = require('node:fs');
const path = require('node:path');

const { createPolicyTestSuite } = require('./_policy-test-util.cjs');
const { makeScratchDir, cleanupScratchDirs } = require('./lib/scratch-fixtures.cjs');

const SCRIPTS_DIR = __dirname;
const REAL_IMPL_DIR = path.resolve(SCRIPTS_DIR, '..');
const REPO_ROOT = path.resolve(REAL_IMPL_DIR, '..');
const LANE_CACHE = path.join('core', 'test', 're_frame', 'bench', 'lane_cache.cjs');

const { test, run } = createPolicyTestSuite('compile-node-test-config-merge');

const lane = makeScratchDir(REPO_ROOT, 'config-merge-runtime');
const IMPL_DIR = path.join(lane, 'implementation');
const WRAPPER = path.join(IMPL_DIR, 'scripts', 'compile-node-test.cjs');
for (const [from, to] of [
  [path.join(SCRIPTS_DIR, 'compile-node-test.cjs'), WRAPPER],
  [path.join(REAL_IMPL_DIR, LANE_CACHE), path.join(IMPL_DIR, LANE_CACHE)],
]) {
  fs.mkdirSync(path.dirname(to), { recursive: true });
  fs.copyFileSync(from, to);
}

const BUILD_ID = 'proof-node-test';
const OUTPUT_TO = 'out/proof-node-test.js';
const BUNDLE = path.join(IMPL_DIR, OUTPUT_TO);
const CACHE_ENTRY = path.join(IMPL_DIR, '.shadow-cljs', 'builds', BUILD_ID);

// The stand-in for shadow-cljs, serialised from a real function so none of it
// has to be escaped. The wrapper spawns it as `<stand-in> compile <id> ...`
// with the implementation root as its working directory.
function shadowCljsStandIn() {
  const fs = require('node:fs');
  const path = require('node:path');
  const [, buildId, ...rest] = process.argv.slice(2);
  const entry = path.join(process.cwd(), '.shadow-cljs', 'builds', buildId, 'dev');
  let outputDir = path.join(entry, 'out');
  rest.forEach((arg, i) => {
    const named = rest[i - 1] === '--config-merge' && /:output-dir\s+("[^"]*")/.exec(arg);
    if (named) outputDir = path.resolve(process.cwd(), JSON.parse(named[1]));
  });
  // Analysis cache, which the clear exists to remove.
  fs.mkdirSync(path.join(entry, 'ana'), { recursive: true });
  fs.writeFileSync(path.join(entry, 'ana', 'cache.transit.json'), '{}\n');
  // Runtime, which the bundle loads when it runs.
  const runtime = path.join(outputDir, 'cljs-runtime');
  fs.mkdirSync(runtime, { recursive: true });
  fs.writeFileSync(path.join(runtime, 'goog.debug.error.js'), '// runtime\n');
  const bundle = process.env.RF2_PROOF_OUTPUT;
  const rel = path.relative(path.dirname(bundle), runtime).split(path.sep).join('/');
  fs.mkdirSync(path.dirname(bundle), { recursive: true });
  fs.writeFileSync(
    bundle,
    [
      `var SHADOW_IMPORT_PATH = __dirname + '/${rel}';`,
      "require(SHADOW_IMPORT_PATH + '/goog.debug.error.js');",
      "console.log('Ran 1 tests containing 1 assertions.');",
      '',
    ].join('\n'),
  );
  process.stdout.write(`[:${buildId}] Build completed. (2 files, 2 compiled, 0 warnings, 0.10s)\n`);
}
const STAND_IN = path.join(lane, 'shadow-cljs-stand-in.cjs');
fs.writeFileSync(STAND_IN, `'use strict';\n(${shadowCljsStandIn.toString()})();\n`);

// Point the wrapper's resolution of shadow-cljs's entry point at the stand-in.
const PRELOAD = path.join(lane, 'preload.cjs');
fs.writeFileSync(
  PRELOAD,
  [
    "'use strict';",
    "const Module = require('node:module');",
    'const origResolve = Module._resolveFilename;',
    'Module._resolveFilename = function (request, ...rest) {',
    "  if (request === 'shadow-cljs/cli/runner.js') return process.env.RF2_PROOF_STAND_IN;",
    '  return origResolve.call(this, request, ...rest);',
    '};',
    '',
  ].join('\n'),
);

// Compile through the wrapper's real CLI; every arm expects the compile itself
// to succeed, because the defect is in what it leaves behind.
function compile(extraArgs) {
  const result = spawnSync(
    process.execPath,
    ['--require', PRELOAD, WRAPPER, BUILD_ID, OUTPUT_TO, ...extraArgs],
    {
      cwd: IMPL_DIR,
      encoding: 'utf8',
      env: { ...process.env, RF2_PROOF_STAND_IN: STAND_IN, RF2_PROOF_OUTPUT: BUNDLE },
    },
  );
  assert.equal(result.error, undefined, `could not launch the wrapper: ${result.error}`);
  assert.equal(result.status, 0, `the compile exited ${result.status}; stderr:\n${result.stderr}`);
  return result;
}

function runBundle() {
  return spawnSync(process.execPath, [BUNDLE], { cwd: IMPL_DIR, encoding: 'utf8' });
}

const FOCUSED = ['--config-merge', '{:ns-regexp "proof-cljs-test$"}'];

test('a --config-merge compile leaves a bundle that runs', () => {
  compile(FOCUSED);
  const ran = runBundle();
  assert.equal(
    ran.status,
    0,
    `the focused bundle exited ${ran.status}: its runtime was deleted with the ` +
      `cache entry it was written into. stderr:\n${ran.stderr}`,
  );
  assert.match(ran.stdout, /Ran 1 tests/);
});

test('a --config-merge compile still clears the cache entry after compiling', () => {
  compile(FOCUSED);
  assert.equal(
    fs.existsSync(CACHE_ENTRY),
    false,
    'the focused compile left its cache entry behind for the next full compile',
  );
});

test('a following full compile keeps its runtime in the cache entry and runs (control)', () => {
  compile([]);
  assert.equal(
    fs.existsSync(path.join(CACHE_ENTRY, 'dev', 'out', 'cljs-runtime', 'goog.debug.error.js')),
    true,
    "the stand-in's default output-dir is not inside the cache entry, so the " +
      'focused arm proves nothing about the clear',
  );
  const ran = runBundle();
  assert.equal(ran.status, 0, `the full bundle exited ${ran.status}; stderr:\n${ran.stderr}`);
});

try {
  run();
} finally {
  cleanupScratchDirs();
}
