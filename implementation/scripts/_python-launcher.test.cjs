#!/usr/bin/env node
'use strict';

/*
 * lib/python.cjs picks the first candidate that RUNS Python 3.10+ and hands the
 * script's exit status back unchanged. The fake interpreters here are node
 * itself under `-e`, answering the version probe the way a real Python would,
 * so the suite needs no Python on the host. Discovered by `npm run test:scripts`.
 */

const assert = require('assert/strict');
const os = require('os');
const path = require('path');
const { spawnSync } = require('child_process');

const { CANDIDATES, main, resolvePython } = require('./lib/python.cjs');

const LAUNCHER = path.join(__dirname, 'lib', 'python.cjs');

// A fake interpreter: answers the `-c` probe with `version`; run with a script,
// it executes `onRun(args)`, where `args` is what the launcher passed after the
// candidate's own leading arguments.
function fake(version, onRun = 'process.exit(0)') {
  const body =
    'const args = process.argv.slice(1);' +
    `if (args[0] === '-c') { process.stdout.write(${JSON.stringify(version)}); process.exit(0); }` +
    onRun;
  return [process.execPath, ['-e', body, '--']];
}

const STORE_ALIAS = [process.execPath, ['-e', 'process.exit(9009)', '--']];

// The environment with PATH (in whatever case the host spells it) pointing
// at a directory that does not exist, so no bare interpreter name resolves.
function envWithoutInterpreters() {
  const env = {};
  for (const [k, v] of Object.entries(process.env)) {
    if (k.toUpperCase() !== 'PATH') env[k] = v;
  }
  env.PATH = path.join(os.tmpdir(), `rf2-no-python-here-${process.pid}`);
  return env;
}

const tests = [];
function test(name, fn) {
  tests.push({ name, fn });
}

test('candidates run in order python, python3, py -3', () => {
  assert.deepEqual(CANDIDATES, [
    ['python', []],
    ['python3', []],
    ['py', ['-3']],
  ]);
});

test('the Store alias is refused by its exit code, as are a missing name and an old Python; the first that runs wins and nothing after it is probed', () => {
  const missing = ['rf2-no-such-python', []];
  const old = fake('2.7');
  const good = fake('3.12');
  const { found, tried } = resolvePython({
    candidates: [STORE_ALIAS, missing, old, good, fake('3.13')],
  });
  assert.equal(found, good);
  assert.equal(tried.length, 4);
  assert.match(tried[0].refusal, /^exit (9009|49) — the Microsoft Store alias/);
  assert.equal(tried[1].refusal, 'not found');
  assert.match(tried[2].refusal, /Python 2\.7, older than 3\.10/);
  assert.equal(tried[3].refusal, null);
});

test("the script's arguments pass through and its exit status comes back unchanged", () => {
  const onRun =
    "const ok = JSON.stringify(args) === JSON.stringify(['check.py', '--self-test', 'a b']);" +
    'process.exit(ok ? 7 : 1);';
  const status = main(['check.py', '--self-test', 'a b'], {
    candidates: [STORE_ALIAS, fake('3.12', onRun)],
    log: () => {},
  });
  assert.equal(status, 7);
});

test('with no interpreter on PATH the launcher exits 2 naming every candidate it tried', () => {
  const r = spawnSync(process.execPath, [LAUNCHER, 'check.py', '--self-test'], {
    cwd: os.tmpdir(),
    encoding: 'utf8',
    env: envWithoutInterpreters(),
  });
  assert.equal(r.status, 2, `stderr:\n${r.stderr}`);
  assert.match(r.stderr, /no Python 3\.10\+ interpreter runs on this host/);
  for (const name of ['python', 'python3', 'py -3']) {
    assert.match(r.stderr, new RegExp(`^ +${name} +not found\\r?$`, 'm'));
  }
});

let failed = 0;
for (const { name, fn } of tests) {
  try {
    fn();
  } catch (err) {
    failed += 1;
    console.error(`FAIL ${name}`);
    console.error(err && err.stack ? err.stack : err);
  }
}

if (failed > 0) {
  console.error(`python-launcher tests: ${failed} failed.`);
  process.exit(1);
}
