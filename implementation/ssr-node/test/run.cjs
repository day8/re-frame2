#!/usr/bin/env node
'use strict';
// THE PACKAGE'S OWN GATE.
//
//     node implementation/ssr-node/test/run.cjs
//
// No build, no npm install, no browser, no port of its own — the HTTP rows
// bind port 0 and let the OS pick, so a run never collides with a
// developer's server or with a concurrent worker's.
//
// Each file is spawned in its OWN PROCESS, which is not tidiness. Every
// suite here starts worker threads and several deliberately kill them; a
// shared process would let one file's leaked isolate turn the next file's
// clean failure into a hang, and a hang reads as a broken runner rather
// than as the leak it is. One process per file also means the exit code
// this script reports is a real aggregate of real exit codes rather than a
// tally something in-process computed about itself.
//
// The root is printed first, deliberately. A gate that does not say which
// checkout it ran in can be believed about the wrong tree — this repo has
// had a backgrounded gate adopt a sibling worker's worktree and report a
// complete, internally consistent verdict about somebody else's work.

const path = require('node:path');
const { spawnSync } = require('node:child_process');

const HERE = __dirname;
const PACKAGE_DIR = path.resolve(HERE, '..');

// Ordered cheapest-first, so a contract mistake surfaces before three
// seconds of worker threads and ten of filesystem scanning.
const FILES = [
  'protocol.test.cjs',
  'egress.test.cjs',
  'isolation.test.cjs',
  'concurrency.test.cjs',
  'timeout.test.cjs',
  'bytes.test.cjs',
  'envelope.test.cjs',
  'absence.test.cjs',
];

console.log(`gate root: ${PACKAGE_DIR}`);
console.log(`gate: re-frame2 ssr-node — ${FILES.length} suites\n`);
console.log(`gate files: ${FILES.join(' ')}\n`);

const failures = [];
for (const file of FILES) {
  const started = Date.now();
  const result = spawnSync(process.execPath, [path.join(HERE, file)], {
    stdio: 'inherit',
    cwd: PACKAGE_DIR,
  });
  const code = result.status === null ? 1 : result.status;
  const ms = Date.now() - started;
  console.log(`\n[ssr-node] ${code === 0 ? 'PASS' : 'FAIL'} ${file} (exit ${code}, ${ms} ms)\n`);
  if (code !== 0) failures.push({ file, code });
}

if (failures.length > 0) {
  console.error(`[ssr-node] FAILED: ${failures.map((f) => `${f.file} (exit ${f.code})`).join(', ')}`);
  process.exit(1);
}
console.log(`[ssr-node] PASS — ${FILES.length} suites, all green`);
