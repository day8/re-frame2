#!/usr/bin/env node
/*
 * Tests for `examples/scripts/walk-tree.cjs`, the fail-closed directory walk the
 * examples-script scanners share: an unreadable directory or root is recorded,
 * never silently dropped, while an intentional skipDir prune is never read.
 * Discovered by `npm run test:scripts`.
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

console.log('walk-tree tests');

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

it('POLICY: walkDir collects every accepted file, and a skipDir prune is never read — even when unreadable', () => {
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
  assert.deepStrictEqual(rel(items), ['a/x.txt', 'z.txt']);
  assert.deepStrictEqual(walkErrors.map((e) => [path.resolve(e.path), e.code]), [[B, 'EACCES']]);
});

it('TEETH: an unreadable/missing ROOT is recorded by name (independent per-root)', () => {
  const missingRoot = path.resolve('/does-not-exist');
  const { items, walkErrors } = walkDir({
    roots: [ROOT, missingRoot],
    io: fakeIo(tree()),
    skipDir: skipNodeModules,
    acceptFile: acceptTxt,
  });
  assert.deepStrictEqual(rel(items), ['a/x.txt', 'b/y.txt', 'z.txt']);
  assert.deepStrictEqual(walkErrors.map((e) => [path.resolve(e.path), e.code]), [[missingRoot, 'ENOENT']]);
});

it('TEETH: assertWalkComplete throws (naming the path + cause) on a partial walk', () => {
  const walkErrors = [{ path: B, code: 'EACCES', message: `EACCES: scandir '${B}'` }];
  assert.throws(
    () => assertWalkComplete(walkErrors, 'my-enumeration'),
    (err) => err.actionable === true && err.message.includes('my-enumeration') && err.message.includes(B),
  );
});

it('walkErrorReport names the count, context, and every path', () => {
  const report = walkErrorReport(
    [
      { path: A, code: 'EACCES', message: `EACCES: scandir '${A}'` },
      { path: B, code: 'ENOENT', message: `ENOENT: scandir '${B}'` },
    ],
    'ctx',
  );
  assert.ok(report.includes(A) && report.includes(B));
  assert.ok(report.includes('ctx'));
});

if (failed > 0) {
  console.error(`\nwalk-tree tests: ${failed} FAILED.`);
  process.exit(1);
}
console.log('\nwalk-tree tests: all passed.');
