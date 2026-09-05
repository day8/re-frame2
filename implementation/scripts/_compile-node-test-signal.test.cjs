#!/usr/bin/env node

'use strict';

/*
 * rf2-i7q4 — a signal-killed compile must not report success.
 *
 * THE DEFECT. Node's `close` event reports a signal death as
 * `(code=null, signal='SIGTERM')`. `compile-node-test.cjs` kept only the first
 * argument, and its abnormal-exit branch returned that `null` straight to
 * `process.exit()` — which reads a non-number as SUCCESS. The wrapper printed
 * `did not complete (exit null)` on stderr and handed its caller exit 0 in the
 * same breath. An OOM kill, a cancelled CI job, or an administrative taskkill
 * of the shadow-cljs JVM all land there, and every one of them recorded a
 * green compile.
 *
 * WHY THIS RUNS A REAL PROCESS. The defect lives at the process boundary: it
 * is about what Node reports when a child is killed, and what
 * `process.exit(null)` then does. A unit test over a hand-made result object
 * would assert the fix's own arithmetic back at itself and could not see
 * either half. So this suite kills a REAL child with a REAL signal and reads
 * the REAL exit status of the REAL CLI:
 *
 *   - the wrapper runs unmodified, as its own process, through its own
 *     `require.main === module` CLI path and its own `process.exit(code)`;
 *   - `runCapturing` does its own `spawn`, and the child that dies is an
 *     ordinary OS process killed with `child.kill('SIGTERM')` by the wrapper's
 *     own process;
 *   - the verdict read here is the exit status the OS reports for the wrapper,
 *     the same number an npm lane or a CI step would branch on.
 *
 * WHAT IS SUBSTITUTED, and it is one thing: WHICH PROGRAM the child runs. A
 * `--require` preload points shadow-cljs's entry-point resolution at a stub
 * whose behaviour each arm chooses, because the alternative is a multi-minute
 * ClojureScript compile that would have to be killed mid-flight to prove
 * anything. Nothing in the wrapper is patched, and the signal is not simulated.
 *
 * THE CONTROL ARM IS THE POINT. `a compile that is NOT signalled still
 * succeeds` runs the identical stub through the identical preload with the
 * kill deleted and nothing else changed. It must be GREEN. If it ever reds,
 * the signal arm's red is an artefact of the harness rather than evidence
 * about signals, and neither number means anything.
 *
 * EXPECTED EXIT CODES, stated per arm, because refusing IS this wrapper's job:
 * the signal arm expects 1 (and expected 0 before the fix — that is the bug),
 * the control arm expects 0, the numeric-status arm expects the child's own 3,
 * and the missing-output arm expects 1.
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
const IMPL_DIR = path.resolve(SCRIPTS_DIR, '..');
const REPO_ROOT = path.resolve(IMPL_DIR, '..');
const WRAPPER = path.join(SCRIPTS_DIR, 'compile-node-test.cjs');

const { test, run } = createPolicyTestSuite('compile-node-test-signal');

// The lane holds the two fixture programs and the wrapper's `:output-to`
// target. Per-process and torn down by the shared helper, so a concurrent
// sibling suite in the same checkout is never touched (rf2-2i1ay).
const lane = makeScratchDir(REPO_ROOT, 'rf2-i7q4-signal');

// A stand-in for shadow-cljs. The wrapper spawns whatever
// `require.resolve('shadow-cljs/cli/runner.js')` yields under `process.execPath`
// with `compile <build-id>` appended, so this only has to be a Node script; the
// arm picks its behaviour through the environment.
const VICTIM = path.join(lane, 'shadow-cljs-stand-in.cjs');
fs.writeFileSync(
  VICTIM,
  [
    "'use strict';",
    "const fs = require('node:fs');",
    'const mode = process.env.RF2_PROOF_MODE;',
    'const out = process.env.RF2_PROOF_OUTPUT;',
    // A real shadow-cljs tally, so the arms that reach the tally reader are
    // reading the shape it actually parses rather than a green it invented.
    "const TALLY = '[:signal-proof] Build completed. (2 files, 2 compiled, 0 warnings, 0.42s)\\n';",
    "if (mode === 'signal') {",
    // Announce, then hold. The first byte of output is the parent's cue to
    // kill, which makes the arm deterministic rather than timing-dependent.
    "  process.stdout.write('[:signal-proof] Compiling ...\\n');",
    '  setInterval(() => {}, 1000);',
    // If the kill never lands, do not hang the suite: exit cleanly and let the
    // stderr assertion say what actually happened.
    '  setTimeout(() => process.exit(0), 15000);',
    '} else {',
    "  if (mode !== 'no-output') fs.writeFileSync(out, '// compiled\\n');",
    '  process.stdout.write(TALLY);',
    "  process.exit(mode === 'code3' ? 3 : 0);",
    '}',
    '',
  ].join('\n'),
);

// Preload. Two seams, both minimal: point the entry-point resolution at the
// stand-in, and — in the signal arm only — kill the child the wrapper spawned.
// The kill is issued by the wrapper's own process against its own child, which
// is what makes `close` report (null, 'SIGTERM') rather than a synthesised
// shape.
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

// Run the wrapper's real CLI and return the OS-reported status plus its stderr.
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
  assert.equal(result.error, undefined, `could not launch the wrapper: ${result.error}`);
  // A wrapper killed in its own right would report a signal here; every arm
  // expects the wrapper itself to exit under its own control.
  assert.equal(
    result.signal,
    null,
    `the wrapper process itself was signalled (${result.signal}); this suite ` +
      'kills the CHILD, never the wrapper.',
  );
  return result;
}

// ── The defect ───────────────────────────────────────────────────────────

// EXPECTED EXIT: 1. Before the fix this arm read 0 — the whole bug.
test('a signal-killed child makes the CLI exit 1 and names the signal (rf2-i7q4)', () => {
  // A stale bundle from a previous, successful compile. The wrapper deletes
  // `:output-to` before every attempt, and a signal must not resurrect that
  // guarantee's failure mode: an aborted compile leaves NO bundle.
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
  // Nothing in the diagnostic may still describe the death as an exit status:
  // "exit null" is the sentence that used to sit beside the false green.
  assert.doesNotMatch(result.stderr, /exit null/, 'stderr still calls a signal death an exit status');
  assert.equal(
    fs.existsSync(OUTPUT_ABS),
    false,
    'the stale bundle survived a signalled compile — output pre-deletion regressed',
  );
});

// ── The control: same fault, signal deleted ──────────────────────────────

// EXPECTED EXIT: 0. This arm is what makes the arm above evidence. It runs the
// identical preload and the identical stand-in with only the kill removed; a
// red here means the harness reds for reasons that have nothing to do with
// signals, and the signal arm proves nothing.
test('the same stand-in WITHOUT the kill still compiles green (control)', () => {
  const result = runWrapper('clean');
  assert.equal(
    result.status,
    0,
    `the control arm exited ${result.status}; stderr was:\n${result.stderr}`,
  );
  assert.equal(fs.existsSync(OUTPUT_ABS), true, 'the control arm wrote no bundle');
});

// ── The mapping either side of the seam ──────────────────────────────────

// EXPECTED EXIT: 3 — the child's own status, passed through rather than
// normalised. Collapsing every abnormal outcome to 1 would lose real codes.
test("a numeric child status is the child's own, not a normalised 1", () => {
  const result = runWrapper('code3');
  assert.equal(result.status, 3, `expected the child's exit 3, got ${result.status}`);
  assert.match(result.stderr, /did not complete \(exit 3\)/, result.stderr);
});

// EXPECTED EXIT: 1. Preserved from before this change: a child that exits 0
// without writing the bundle is still fatal.
test('a clean exit that writes no bundle is still refused', () => {
  fs.rmSync(OUTPUT_ABS, { force: true });
  const result = runWrapper('no-output');
  assert.equal(result.status, 1, `expected 1, got ${result.status}`);
  assert.match(result.stderr, /is missing — treating as fatal/, result.stderr);
});

try {
  run();
} finally {
  cleanupScratchDirs();
}
