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

// `binRel`, when given, is a repo-relative directory put at the head of PATH.
// The `bash -lc` string references only $PWD and $PATH: WSL's bash.exe
// expands it twice, so a variable it assigned itself would read empty.
function run(depsEdnRel, localRoot, version = VERSION, binRel = null) {
  const command = [
    ...(binRel ? ['env', `PATH="$PWD/${binRel}:$PATH"`] : []),
    shQuote(`./${SCRIPT_REL}`),
    shQuote(localRoot),
    shQuote(version),
    shQuote(depsEdnRel),
  ].join(' ');
  return spawnSync('bash', ['-lc', command], { cwd: REPO_ROOT, encoding: 'utf8' });
}

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

// ── Fail-loud invariants ──────────────────────────────────────────────

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

test('abort: no candidate runs Python 3.10+ → exit 2, naming each one and what it did', () => {
  // Stubs shadow all three candidates lib/python.sh tries: a `python3` shaped
  // like the Microsoft Store alias, a Python 2 `python`, and a `py` that fails.
  const bin = path.join(makeScratchDir(REPO_ROOT, 'rf2-local-root-nopy'), 'bin');
  fs.mkdirSync(bin, { recursive: true });
  const stubs = {
    python3: 'echo "Python was not found" >&2\nexit 49',
    python: 'printf 2.7\nexit 1',
    py: 'exit 9',
  };
  for (const [name, body] of Object.entries(stubs)) {
    const stub = path.join(bin, name);
    fs.writeFileSync(stub, `#!/usr/bin/env sh\n${body}\n`, { mode: 0o755 });
    fs.chmodSync(stub, 0o755);
  }
  const body = '{:deps {day8/re-frame2 {:local/root "../core"}}}\n';
  const fix = fixture(body);
  const res = run(fix.rel, '../core', VERSION, relPosix(bin));
  const out = res.stdout + res.stderr;
  assert.equal(res.status, 2, `expected exit 2, got ${res.status}\n${out}`);
  assert.match(out, /no Python 3\.10\+ interpreter runs on this host/);
  assert.match(out, /python3 +exit 49 — the Microsoft Store alias/);
  assert.match(out, /python +Python 2\.7, older than 3\.10/);
  assert.match(out, /py -3 +exit 9/);
  assert.equal(fs.readFileSync(fix.abs, 'utf8'), body, 'deps.edn must be left untouched');
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
    11,
    `expected 11 leaf declarations parsed from release.yml, got ${leaves.length}`,
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

cleanupScratchDirs();

if (failed > 0) {
  console.error(`rewrite-local-root-coord tests: ${failed} failed.`);
  process.exit(1);
}

console.log(`rewrite-local-root-coord tests: ${tests.length} passed.`);
