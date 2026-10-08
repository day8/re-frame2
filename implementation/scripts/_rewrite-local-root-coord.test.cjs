#!/usr/bin/env node
/*
 * Tests for `.github/scripts/rewrite-local-root-coord.sh`, which swaps a leaf's
 * in-repo `:local/root` coordinate for the published `:mvn/version` before the jar
 * is packaged; `clein pom` silently skips :local/root, so without it the pom carries
 * no framework dependency. The match is comment-aware, because leaves quote their
 * coordinates in prose comments. Runs the real script under bash with
 * repo-relative paths. Discovered by `npm run test:scripts`.
 */

'use strict';

const assert = require('assert/strict');
const { spawnSync } = require('child_process');
const fs = require('fs');
const path = require('path');

const IMPL_ROOT = path.resolve(__dirname, '..');
const REPO_ROOT = path.resolve(IMPL_ROOT, '..');

const SCRIPT_REL = '.github/scripts/rewrite-local-root-coord.sh';
const RELEASE_YML = path.join(REPO_ROOT, '.github', 'workflows', 'release.yml');
const { makeScratchDir, cleanupScratchDirs } = require('./lib/scratch-fixtures.cjs');

const VERSION = '9.9.9-TEST';

const tests = [];
function test(name, fn) {
  tests.push({ name, fn });
}

function relPosix(abs) {
  return path.relative(REPO_ROOT, abs).split(path.sep).join('/');
}

function shQuote(s) {
  return `'${String(s).replace(/'/g, `'\\''`)}'`;
}

// Write `body` to a throwaway deps.edn and return its repo-relative path.
function fixture(body) {
  const dir = makeScratchDir(REPO_ROOT, 'rf2-local-root');
  const abs = path.join(dir, 'deps.edn');
  fs.writeFileSync(abs, body);
  return { abs, rel: relPosix(abs) };
}

function run(depsEdnRel, localRoot, version = VERSION) {
  const command = [
    shQuote(`./${SCRIPT_REL}`),
    shQuote(localRoot),
    shQuote(version),
    shQuote(depsEdnRel),
  ].join(' ');
  return spawnSync('bash', ['-lc', command], { cwd: REPO_ROOT, encoding: 'utf8' });
}

function cleanup() {
  cleanupScratchDirs();
}

// A minimal leaf deps.edn: the core coordinate plus a sibling :local/root dep.
const PLAIN = [
  '{:paths ["src"]',
  ' :deps  {day8/re-frame2 {:local/root "../core"}}',
  '',
  ' :aliases',
  ' {:test {:extra-deps {day8/re-frame2-test-quiet {:local/root "../test-quiet"}}}}}',
  '',
].join('\n');

test('success: rewrites the code coordinate to :mvn/version', () => {
  const fix = fixture(PLAIN);
  const res = run(fix.rel, '../core');
  assert.equal(res.status, 0, `expected exit 0, got ${res.status}\n${res.stderr}`);
  const out = fs.readFileSync(fix.abs, 'utf8');
  assert.match(out, /day8\/re-frame2 \{:mvn\/version "9\.9\.9-TEST"\}/);
  assert.doesNotMatch(out, /day8\/re-frame2 \{:local\/root "\.\.\/core"\}/);
});

// The reagent-slim shape: a prose comment quoting the literal the rewrite keys off.
test('regression (rf2-ldkuk): a comment quoting the literal does not break the rewrite', () => {
  const fix = fixture(
    [
      ';; The day8/re-frame2 coordinate uses :local/root "../core" — the',
      ';; release workflow rewrites it to :mvn/version "${VERSION}".',
      '{:paths ["src"]',
      ' :deps  {day8/re-frame2 {:local/root "../core"}}}',
      '',
    ].join('\n'),
  );
  const res = run(fix.rel, '../core');
  assert.equal(
    res.status,
    0,
    `a commented occurrence must not defeat the count — got exit ${res.status}\n${res.stderr}`,
  );
  const out = fs.readFileSync(fix.abs, 'utf8');
  assert.match(out, /day8\/re-frame2 \{:mvn\/version "9\.9\.9-TEST"\}/);
  assert.match(
    out,
    /;; The day8\/re-frame2 coordinate uses :local\/root "\.\.\/core" —/,
    'the comment must survive verbatim — comments are documentation, not code',
  );
});

test('regression: many commented occurrences still leave exactly one code match', () => {
  const fix = fixture(
    [
      ';; :local/root "../core" :local/root "../core" :local/root "../core"',
      '{:deps {day8/re-frame2 {:local/root "../core"}}} ;; :local/root "../core"',
      '',
    ].join('\n'),
  );
  const res = run(fix.rel, '../core');
  assert.equal(res.status, 0, `expected exit 0, got ${res.status}\n${res.stderr}`);
  const out = fs.readFileSync(fix.abs, 'utf8');
  assert.match(out, /\{:deps \{day8\/re-frame2 \{:mvn\/version "9\.9\.9-TEST"\}\}\}/);
  assert.match(out, /\}\}\} ;; :local\/root "\.\.\/core"/, 'trailing comment preserved');
});

test('a `;` inside a string does not start a comment', () => {
  const fix = fixture(
    '{:note "semi ; colon" :deps {day8/re-frame2 {:local/root "../core"}}}\n',
  );
  const res = run(fix.rel, '../core');
  assert.equal(res.status, 0, `expected exit 0, got ${res.status}\n${res.stderr}`);
  assert.match(fs.readFileSync(fix.abs, 'utf8'), /:mvn\/version "9\.9\.9-TEST"/);
});

test('ssr-ring shape: two sequential invocations rewrite both in-repo coords', () => {
  const fix = fixture(
    [
      '{:deps  {day8/re-frame2     {:local/root "../core"}',
      '         day8/re-frame2-ssr {:local/root "../ssr"}}}',
      '',
    ].join('\n'),
  );
  run(fix.rel, '../core');
  run(fix.rel, '../ssr');
  const out = fs.readFileSync(fix.abs, 'utf8');
  assert.match(out, /day8\/re-frame2     \{:mvn\/version "9\.9\.9-TEST"\}/);
  assert.match(out, /day8\/re-frame2-ssr \{:mvn\/version "9\.9\.9-TEST"\}/);
});

// ── Fail-loud invariants ──────────────────────────────────────────────

test('abort: missing deps.edn → exit 2', () => {
  const res = run('.scratch/does-not-exist/deps.edn', '../core');
  assert.equal(res.status, 2);
});

test('abort: coordinate absent entirely → exit 3', () => {
  const fix = fixture('{:deps {day8/re-frame2 {:mvn/version "1.0.0"}}}\n');
  const res = run(fix.rel, '../core');
  assert.equal(res.status, 3);
});

test('abort: coordinate present ONLY in comments → exit 3 with a distinct diagnostic', () => {
  // Doing nothing here would publish a pom with no framework dep.
  const fix = fixture(
    [
      ';; historically {:local/root "../core"}, now vendored',
      '{:deps {day8/re-frame2 {:mvn/version "1.0.0"}}}',
      '',
    ].join('\n'),
  );
  const res = run(fix.rel, '../core');
  assert.equal(res.status, 3);
  assert.match(
    res.stdout + res.stderr,
    /ALL inside EDN comments/,
    'the operator must be told the occurrences are comment-only',
  );
});

test('abort: two real code coordinates → exit 4 (ambiguous, refuse to guess)', () => {
  const fix = fixture(
    [
      '{:deps {day8/re-frame2   {:local/root "../core"}',
      '        day8/re-frame2-x {:local/root "../core"}}}',
      '',
    ].join('\n'),
  );
  const res = run(fix.rel, '../core');
  assert.equal(res.status, 4);
});

test('abort: deps.edn is left unmodified when the rewrite aborts', () => {
  const body = '{:deps {day8/re-frame2 {:mvn/version "1.0.0"}}}\n';
  const fix = fixture(body);
  run(fix.rel, '../core');
  assert.equal(fs.readFileSync(fix.abs, 'utf8'), body, 'fail-closed: no partial write');
});

test('the script is committed executable (release.yml invokes it directly)', () => {
  // release.yml runs it as a command. Read from the index: Windows checkouts
  // carry no POSIX exec bit.
  const res = spawnSync('git', ['ls-files', '-s', SCRIPT_REL], {
    cwd: REPO_ROOT,
    encoding: 'utf8',
  });
  assert.match(
    res.stdout,
    /^100755 /,
    `${SCRIPT_REL} must be mode 100755 in the index, got: ${res.stdout.trim()}`,
  );
});

// The fleet gate: every `- leaf:` declaration in release.yml, across the
// deploy-leaf matrix and the post-matrix deploy jobs, must rewrite cleanly.
function parseDeployLeafMatrix() {
  const yml = fs.readFileSync(RELEASE_YML, 'utf8');
  const leaves = [];
  const re = /^\s*- leaf: (\S+)\s*$/gm;
  let m;
  while ((m = re.exec(yml)) !== null) {
    const block = yml.slice(m.index, m.index + 500);
    const dir = /^\s*directory: (\S+)\s*$/m.exec(block);
    const localRoot = /^\s*local-root: (\S+)\s*$/m.exec(block);
    const extra = /^\s*extra-local-root: (\S+)\s*$/m.exec(block);
    if (dir && localRoot) {
      leaves.push({
        leaf: m[1],
        directory: dir[1],
        localRoot: localRoot[1],
        extraLocalRoot: extra ? extra[1] : null,
      });
    }
  }
  return leaves;
}

test('every deploy-leaf rewrites its real deps.edn to exactly one published coord', () => {
  const leaves = parseDeployLeafMatrix();
  // A changed matrix shape must fail here, not parse to fewer leaves.
  assert.equal(
    leaves.length,
    13,
    `expected 13 leaf declarations parsed from release.yml, got ${leaves.length}`,
  );

  for (const { leaf, directory, localRoot, extraLocalRoot } of leaves) {
    const real = path.join(REPO_ROOT, directory, 'deps.edn');
    const fix = fixture(fs.readFileSync(real, 'utf8'));

    const res = run(fix.rel, localRoot);
    assert.equal(
      res.status,
      0,
      `${leaf}: rewrite of '${localRoot}' aborted (exit ${res.status})\n${res.stdout}${res.stderr}`,
    );
    if (extraLocalRoot) {
      const res2 = run(fix.rel, extraLocalRoot);
      assert.equal(
        res2.status,
        0,
        `${leaf}: rewrite of '${extraLocalRoot}' aborted (exit ${res2.status})\n${res2.stdout}${res2.stderr}`,
      );
    }

    const out = fs.readFileSync(fix.abs, 'utf8');
    assert.match(
      out,
      new RegExp(`day8/re-frame2\\b[^\\n]*\\{:mvn/version "${VERSION.replace(/\./g, '\\.')}"\\}`),
      `${leaf}: published pom coordinate not present after rewrite`,
    );
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

cleanup();

if (failed > 0) {
  console.error(`rewrite-local-root-coord tests: ${failed} failed.`);
  process.exit(1);
}

console.log(`rewrite-local-root-coord tests: ${tests.length} passed.`);
