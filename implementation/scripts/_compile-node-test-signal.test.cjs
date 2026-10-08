#!/usr/bin/env node

'use strict';

/*
 * A signal-killed compile must not report success. Node reports a signal death
 * as (code=null, signal), and process.exit(null) exits 0 — so a wrapper passing
 * that null through hands automation a green for a compile that never finished.
 * The defect lives at the process boundary, so this runs the wrapper's real CLI
 * and kills a real child; only the program the child runs is substituted (a
 * stand-in for shadow-cljs, via a --require preload). The unsignalled control arm
 * shows the signal arm's red is about the signal, not the harness.
 * Discovered by `npm run test:scripts`.
 */

const assert = require('assert/strict');
const { spawnSync } = require('node:child_process');
const fs = require('node:fs');
const path = require('node:path');

const { createPolicyTestSuite } = require('./_policy-test-util.cjs');
const { makeScratchDir, cleanupScratchDirs } = require('./lib/scratch-fixtures.cjs');

const SCRIPTS_DIR = __dirname;
const IMPL_DIR = path.resolve(SCRIPTS_DIR, '..');
const REPO_ROOT = path.resolve(IMPL_DIR, '..');
const WRAPPER = path.join(SCRIPTS_DIR, 'compile-node-test.cjs');

const { test, run } = createPolicyTestSuite('compile-node-test-signal');

const lane = makeScratchDir(REPO_ROOT, 'rf2-i7q4-signal');

// The wrapper runs `require.resolve('shadow-cljs/cli/runner.js')` under node; the
// preload points that at this stand-in, whose behaviour each arm picks by env.
const VICTIM = path.join(lane, 'shadow-cljs-stand-in.cjs');
fs.writeFileSync(
  VICTIM,
  [
    "'use strict';",
    "const fs = require('node:fs');",
    'const mode = process.env.RF2_PROOF_MODE;',
    'const out = process.env.RF2_PROOF_OUTPUT;',
    "const TALLY = '[:signal-proof] Build completed. (2 files, 2 compiled, 0 warnings, 0.42s)\\n';",
    "if (mode === 'signal') {",
    // The first byte of output is the parent's cue to kill.
    "  process.stdout.write('[:signal-proof] Compiling ...\\n');",
    '  setInterval(() => {}, 1000);',
    '  setTimeout(() => process.exit(0), 15000);',
    '} else {',
    "  if (mode !== 'no-output') fs.writeFileSync(out, '// compiled\\n');",
    '  process.stdout.write(TALLY);',
    "  process.exit(mode === 'code3' ? 3 : 0);",
    '}',
    '',
  ].join('\n'),
);

// The kill is issued by the wrapper's own process against its own child, so
// `close` reports a real (null, 'SIGTERM').
const PRELOAD = path.join(lane, 'preload.cjs');
fs.writeFileSync(
  PRELOAD,
  [
    "'use strict';",
    "const Module = require('node:module');",
    "const cp = require('node:child_process');",
    'const victim = process.env.RF2_PROOF_VICTIM;',
    'const origResolve = Module._resolveFilename;',
    'Module._resolveFilename = function (request, ...rest) {',
    "  if (request === 'shadow-cljs/cli/runner.js') return victim;",
    '  return origResolve.call(this, request, ...rest);',
    '};',
    'const origSpawn = cp.spawn;',
    'cp.spawn = function (...args) {',
    '  const child = origSpawn.apply(this, args);',
    "  if (process.env.RF2_PROOF_MODE === 'signal') {",
    "    child.stdout.once('data', () => child.kill('SIGTERM'));",
    '  }',
    '  return child;',
    '};',
    '',
  ].join('\n'),
);

const OUTPUT_ABS = path.join(lane, 'signal-proof-out.js');
// The wrapper resolves `:output-to` against the implementation root.
const OUTPUT_REL = path.relative(IMPL_DIR, OUTPUT_ABS);

function runWrapper(mode) {
  const result = spawnSync(
    process.execPath,
    ['--require', PRELOAD, WRAPPER, 'signal-proof', OUTPUT_REL],
    {
      cwd: IMPL_DIR,
      encoding: 'utf8',
      env: {
        ...process.env,
        RF2_PROOF_MODE: mode,
        RF2_PROOF_VICTIM: VICTIM,
        RF2_PROOF_OUTPUT: OUTPUT_ABS,
      },
    },
  );
  return result;
}

test('a signal-killed child makes the CLI exit 1 and names the signal (rf2-i7q4)', () => {
  // An aborted compile must leave NO bundle, not a stale one.
  fs.writeFileSync(OUTPUT_ABS, '// stale bundle from an earlier compile\n');

  const result = runWrapper('signal');

  assert.equal(
    result.status,
    1,
    'a compile whose child was killed by SIGTERM reported exit ' +
      `${result.status}. Node reports a signal death as (null, 'SIGTERM'), and ` +
      'process.exit(null) exits 0 — so an unnormalised null status hands ' +
      'automation a green for a compile that never finished.',
  );
  assert.match(
    result.stderr,
    /terminated by signal SIGTERM/,
    `the diagnostic must name the signal; stderr was:\n${result.stderr}`,
  );
  assert.equal(
    fs.existsSync(OUTPUT_ABS),
    false,
    'the stale bundle survived a signalled compile — output pre-deletion regressed',
  );
});

test('the same stand-in WITHOUT the kill still compiles green (control)', () => {
  const result = runWrapper('clean');
  assert.equal(
    result.status,
    0,
    `the control arm exited ${result.status}; stderr was:\n${result.stderr}`,
  );
  assert.equal(fs.existsSync(OUTPUT_ABS), true, 'the control arm wrote no bundle');
});

// The child's own code passes through: collapsing every failure to 1 loses it.
test("a numeric child status is the child's own, not a normalised 1", () => {
  const result = runWrapper('code3');
  assert.equal(result.status, 3, `expected the child's exit 3, got ${result.status}`);
});

test('a clean exit that writes no bundle is still refused', () => {
  fs.rmSync(OUTPUT_ABS, { force: true });
  const result = runWrapper('no-output');
  assert.equal(result.status, 1, `expected 1, got ${result.status}`);
});

try {
  run();
} finally {
  cleanupScratchDirs();
}
