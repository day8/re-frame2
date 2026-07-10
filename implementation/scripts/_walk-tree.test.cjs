#!/usr/bin/env node
/*
 * Tests for `examples/scripts/walk-tree.cjs` — the FAIL-CLOSED directory-walk
 * primitive shared by the examples-script scanners (rf2-3fc89f.31).
 *
 * What these pin
 * --------------
 *   - walkDir collects every accepted FILE across the roots;
 *   - an unreadable directory is RECORDED in walkErrors (with its cause), NEVER
 *     silently dropped — the readable siblings still return, so the failure is
 *     visible rather than shrinking the result;
 *   - an unreadable/missing ROOT is recorded by name (each root is enumerated
 *     independently);
 *   - an INTENTIONAL skip (skipDir policy) is never visited and never an error —
 *     the deliberate-prune vs unexpected-failure distinction the whole fix rests
 *     on;
 *   - assertWalkComplete is a no-op on a clean walk and throws (carrying
 *     .walkErrors + .actionable, naming each path) on a partial walk.
 *
 * Standalone node-runnable suite — no external framework, mirroring the sibling
 * script suites. Wired into package.json via `test:script-policy`.
 */

'use strict';

const path = require('path');
const assert = require('assert');

const {
  walkDir,
  walkErrorReport,
  assertWalkComplete,
} = require('../../examples/scripts/walk-tree.cjs');

let failed = 0;
function it(label, fn) {
  try {
    fn();
    console.log(`  PASS  ${label}`);
  } catch (err) {
    failed++;
    console.error(`  FAIL  ${label}`);
    console.error(`        ${(err && err.message) || err}`);
  }
}

console.log('walk-tree tests (rf2-3fc89f.31)');

// A synthetic filesystem. `dirs` maps an absolute dir path -> [{ name, type }]
// ('dir' | 'file'); `unreadable` is a Set of absolute dir paths whose
// readdirSync throws EACCES (a torn checkout / permissions fault). A dir absent
// from `dirs` throws ENOENT (a missing root/subtree).
function fakeIo(dirs, unreadable = new Set()) {
  const norm = (p) => path.resolve(p);
  const normDirs = {};
  for (const [k, v] of Object.entries(dirs)) normDirs[norm(k)] = v;
  const normUnreadable = new Set([...unreadable].map(norm));
  return {
    readdirSync(dir) {
      const key = norm(dir);
      if (normUnreadable.has(key)) {
        const e = new Error(`EACCES: permission denied, scandir '${dir}'`);
        e.code = 'EACCES';
        throw e;
      }
      const entries = normDirs[key];
      if (!entries) {
        const e = new Error(`ENOENT: no such file or directory, scandir '${dir}'`);
        e.code = 'ENOENT';
        throw e;
      }
      return entries.map((en) => ({
        name: en.name,
        isDirectory: () => en.type === 'dir',
        isFile: () => en.type === 'file',
      }));
    },
  };
}

// A small tree rooted at an absolute /root:
//   /root  { a/, b/, z.txt }
//   /root/a  { x.txt, node_modules/ }
//   /root/a/node_modules  { junk.txt }   (skip policy)
//   /root/b  { y.txt }
const ROOT = path.resolve('/root');
const A = path.join(ROOT, 'a');
const B = path.join(ROOT, 'b');
const NM = path.join(A, 'node_modules');
function tree() {
  return {
    [ROOT]: [
      { name: 'a', type: 'dir' },
      { name: 'b', type: 'dir' },
      { name: 'z.txt', type: 'file' },
    ],
    [A]: [
      { name: 'x.txt', type: 'file' },
      { name: 'node_modules', type: 'dir' },
    ],
    [NM]: [{ name: 'junk.txt', type: 'file' }],
    [B]: [{ name: 'y.txt', type: 'file' }],
  };
}

const acceptTxt = (name) => name.endsWith('.txt');
const skipNodeModules = (name) => name === 'node_modules';
const rel = (items) => items.map((p) => path.relative(ROOT, p).split(path.sep).join('/')).sort();

it('walkDir collects every accepted file across the tree (clean walk)', () => {
  const { items, walkErrors } = walkDir({
    roots: [ROOT],
    io: fakeIo(tree()),
    skipDir: skipNodeModules,
    acceptFile: acceptTxt,
  });
  assert.deepStrictEqual(walkErrors, [], 'a clean walk records no errors');
  // node_modules/junk.txt is pruned by policy; x/y/z remain.
  assert.deepStrictEqual(rel(items), ['a/x.txt', 'b/y.txt', 'z.txt']);
});

it('POLICY: a skipDir prune is never visited and never an error', () => {
  // Even if node_modules were UNREADABLE, the policy skip means it is never
  // read — so no walkError and its contents never appear.
  const { items, walkErrors } = walkDir({
    roots: [ROOT],
    io: fakeIo(tree(), new Set([NM])),
    skipDir: skipNodeModules,
    acceptFile: acceptTxt,
  });
  assert.deepStrictEqual(walkErrors, [], 'a pruned dir is never read, so never an error');
  assert.deepStrictEqual(rel(items), ['a/x.txt', 'b/y.txt', 'z.txt']);
});

it('TEETH: an unreadable subtree is RECORDED, not silently dropped', () => {
  const { items, walkErrors } = walkDir({
    roots: [ROOT],
    io: fakeIo(tree(), new Set([B])),
    skipDir: skipNodeModules,
    acceptFile: acceptTxt,
  });
  // b/ could not be read: y.txt is NOT in items, but the failure is visible.
  assert.deepStrictEqual(rel(items), ['a/x.txt', 'z.txt']);
  assert.strictEqual(walkErrors.length, 1, 'the unreadable subtree must be recorded');
  assert.strictEqual(path.resolve(walkErrors[0].path), path.resolve(B));
  assert.strictEqual(walkErrors[0].code, 'EACCES');
});

it('TEETH: an unreadable/missing ROOT is recorded by name (independent per-root)', () => {
  const missingRoot = path.resolve('/does-not-exist');
  const { items, walkErrors } = walkDir({
    roots: [ROOT, missingRoot],
    io: fakeIo(tree()),
    skipDir: skipNodeModules,
    acceptFile: acceptTxt,
  });
  // The readable root's files still come back; the bad root is named.
  assert.deepStrictEqual(rel(items), ['a/x.txt', 'b/y.txt', 'z.txt']);
  assert.strictEqual(walkErrors.length, 1);
  assert.strictEqual(path.resolve(walkErrors[0].path), missingRoot);
  assert.strictEqual(walkErrors[0].code, 'ENOENT');
});

it('assertWalkComplete is a no-op on a clean walk', () => {
  assert.doesNotThrow(() => assertWalkComplete([], 'ctx'));
});

it('TEETH: assertWalkComplete throws (naming the path + cause) on a partial walk', () => {
  const walkErrors = [{ path: B, code: 'EACCES', message: `EACCES: scandir '${B}'` }];
  let thrown = null;
  try {
    assertWalkComplete(walkErrors, 'my-enumeration');
  } catch (err) {
    thrown = err;
  }
  assert.ok(thrown, 'a non-empty walkErrors must throw (fail closed)');
  assert.ok(thrown.message.includes('my-enumeration'), 'the context is named');
  assert.ok(thrown.message.includes('enumeration FAILED'), 'the failure is explicit');
  assert.ok(thrown.message.includes(B), 'the unreadable path is named');
  assert.strictEqual(thrown.actionable, true, 'the error is marked actionable');
  assert.deepStrictEqual(thrown.walkErrors, walkErrors, 'the walkErrors ride along');
});

it('walkErrorReport names the count, context, and every path + cause', () => {
  const report = walkErrorReport(
    [
      { path: A, code: 'EACCES', message: `EACCES: scandir '${A}'` },
      { path: B, code: 'ENOENT', message: `ENOENT: scandir '${B}'` },
    ],
    'ctx',
  );
  assert.ok(report.includes('2 path(s)'));
  assert.ok(report.includes(A) && report.includes(B));
  assert.ok(report.includes('ctx'));
});

if (failed > 0) {
  console.error(`\nwalk-tree tests: ${failed} FAILED.`);
  process.exit(1);
}
console.log('\nwalk-tree tests: all passed.');
