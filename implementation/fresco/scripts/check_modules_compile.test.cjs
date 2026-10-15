#!/usr/bin/env node
'use strict';
// Entry lists accept valid rosters and refuse silent coverage loss.

const assert = require('node:assert/strict');
const fs = require('node:fs');
const os = require('node:os');
const path = require('node:path');

const {
  decideModuleNamespaces,
  optionalModuleNamespaces,
  verifyRoster,
  MIN_MODULE_NAMESPACES,
  REHOMED_BENCH_ENTRIES,
} = require('./check_modules_compile.cjs');

const IMPL = path.resolve(__dirname, '../..');
const NAMES = [
  're-frame.fresco.forms',
  're-frame.fresco.impl.overlay',
  're-frame.fresco.impl.presence',
  're-frame.fresco.impl.presence-react',
];

function emitted(namespaces) {
  return { error: null, status: 0, stdout: namespaces.join('\n') + '\n', stderr: '' };
}

const cases = [];
function test(name, fn) {
  cases.push([name, fn]);
}

test('duplicates collapse and order is normalised', () => {
  assert.deepEqual(
    decideModuleNamespaces(emitted([NAMES[3], NAMES[1], NAMES[0], NAMES[2], NAMES[1]])),
    { ok: true, namespaces: NAMES },
  );
});

test('trailing whitespace and blank lines are not entries', () => {
  assert.deepEqual(decideModuleNamespaces({
    error: null,
    status: 0,
    stdout: `\n  ${NAMES[0]}  \r\n${NAMES[1]}\n\n${NAMES[2]}\n${NAMES[3]}\n\n`,
    stderr: '',
  }), { ok: true, namespaces: NAMES });
});

test('REFUSAL 1 — the emitter could not be run at all', () => {
  const d = decideModuleNamespaces({
    error: new Error('spawn python ENOENT'),
    status: null,
    stdout: '',
    stderr: '',
  });
  assert.equal(d.ok, false);
  assert.match(d.detail.join('\n'), /ENOENT/);
});

test('REFUSAL 2 — the emitter ran and failed', () => {
  const d = decideModuleNamespaces({
    error: null,
    status: 2,
    stdout: '',
    stderr: 'Traceback (most recent call last):\nNameError: MODULES\n',
  });
  assert.equal(d.ok, false);
  assert.match(d.detail.join('\n'), /NameError/);
});

test('REFUSAL 3 — an empty or collapsed list, which is the silent one', () => {
  for (const namespaces of [[], NAMES.slice(0, MIN_MODULE_NAMESPACES - 1)]) {
    assert.equal(decideModuleNamespaces(emitted(namespaces)).ok, false);
  }
  assert.equal(decideModuleNamespaces(emitted(NAMES)).ok, true);
});

test('REFUSAL 4 — a token that is not a namespace', () => {
  for (const bad of ['notanamespace', 're-frame.fresco.overlay)']) {
    assert.equal(decideModuleNamespaces(emitted([...NAMES, bad])).ok, false, bad);
  }
});

test('the real roster still answers, and answers namespaces', () => {
  const d = optionalModuleNamespaces(IMPL);
  assert.equal(d.ok, true, d.ok ? '' : `${d.reason}\n${(d.detail || []).join('\n')}`);
  assert.ok(d.namespaces.length >= MIN_MODULE_NAMESPACES);
  for (const ns of d.namespaces) assert.match(ns, /^re-frame\.fresco\./, ns);
  // The overlay implementation is the warnings-fatal gate's interop subject.
  assert.ok(d.namespaces.includes('re-frame.fresco.impl.overlay'), d.namespaces.join(', '));
});

test('the two re-homed rows still name real namespaces', () => {
  const { namespaces, broken } = verifyRoster(REHOMED_BENCH_ENTRIES);
  assert.deepEqual(broken, []);
  assert.deepEqual(namespaces.sort(), [
    're-frame.bench.read-attribution-cljs',
    're-frame.bench.write-attribution',
  ]);
});

test('a roster row whose file has moved, or been renamed, is REFUSED', () => {
  const root = fs.mkdtempSync(path.join(os.tmpdir(), 'rehomed-roster-'));
  try {
    fs.mkdirSync(path.join(root, 'bench'), { recursive: true });
    fs.writeFileSync(path.join(root, 'bench', 'kept.cljs'), '(ns re-frame.bench.kept)\n');
    fs.writeFileSync(path.join(root, 'bench', 'renamed.cljs'), '(ns re-frame.bench.other)\n');
    fs.writeFileSync(path.join(root, 'bench', 'headless.cljs'), ';; no ns form here\n');
    const row = (ns, file) => [{ ns, file: path.join('bench', file) }];
    assert.deepEqual(verifyRoster(row('re-frame.bench.kept', 'kept.cljs'), root).broken, []);

    const gone = verifyRoster(row('re-frame.bench.gone', 'gone.cljs'), root);
    assert.equal(gone.namespaces.length, 0);
    assert.match(gone.broken.join('\n'), /no such file/);

    const renamed = verifyRoster(row('re-frame.bench.renamed', 'renamed.cljs'), root);
    assert.equal(renamed.namespaces.length, 0);
    assert.match(renamed.broken.join('\n'), /declares re-frame\.bench\.other/);

    const headless = verifyRoster(row('re-frame.bench.headless', 'headless.cljs'), root);
    assert.equal(headless.namespaces.length, 0);
    assert.match(headless.broken.join('\n'), /no readable ns form/);
  } finally {
    fs.rmSync(root, { recursive: true, force: true, maxRetries: 5, retryDelay: 100 });
  }
});

let failed = 0;
for (const [name, fn] of cases) {
  try {
    fn();
    console.log(`  ok  ${name}`);
  } catch (e) {
    failed += 1;
    console.error(`  FAIL  ${name}`);
    console.error(`        ${e.message}`);
  }
}
if (failed > 0) {
  console.error(`check_modules_compile.test.cjs: ${failed} of ${cases.length} failed`);
  process.exit(1);
}
console.log(`check_modules_compile.test.cjs: ${cases.length} passed`);
