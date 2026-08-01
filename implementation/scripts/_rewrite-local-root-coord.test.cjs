#!/usr/bin/env node
/*
 * Unit test for `.github/scripts/rewrite-local-root-coord.sh` (rf2-ldkuk).
 *
 * The script swaps a leaf's in-repo `:local/root "<path>"` coordinate for
 * the published `:mvn/version "<version>"` before clein packages the jar.
 * It is load-bearing for correctness, not convenience: `clein pom`
 * SILENTLY SKIPS :local/root coordinates (rf2-do3m2 / #6340), so a pom
 * built without this rewrite carries no day8/re-frame2 dependency at all.
 *
 * THE BUG THIS FIXES (rf2-ldkuk): the inline step this replaces asserted a
 * RAW substring count of 1. A `;;` comment quoting the same coordinate
 * therefore counted as a second "occurrence" — and reagent-slim's header
 * comment does exactly that, so `day8/reagent-slim` could not be released
 * at all. The fix is not to reword one comment (the next ordinary comment
 * reintroduces the abort — eight of the thirteen leaves already document
 * coordinates in prose comments) but to make the match comment-aware, so
 * the invariant is honest rather than luck.
 *
 * Pattern mirrors `_transform-reagent-slim-ns.test.cjs`: spawn the real
 * shell script via `bash` with repo-relative paths and a cwd of REPO_ROOT
 * (the cross-platform form — see that file's `run()` comment for the Git
 * Bash / WSL rationale, rf2-6m7pn4). Wired into `test:script-policy`.
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
// Fixtures live INSIDE the repo (gitignored `.scratch/`) so a repo-relative
// path reaches them under every supported Bash flavour. Lanes are
// process-scoped and the shared root is never removed, so a concurrent
// suite cannot delete this one's fixtures mid-run (rf2-2i1ay).
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

// A minimal leaf deps.edn carrying the core coordinate plus a sibling
// :local/root dep that must survive untouched.
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

test('success: sibling :local/root deps on other paths are untouched', () => {
  const fix = fixture(PLAIN);
  run(fix.rel, '../core');
  const out = fs.readFileSync(fix.abs, 'utf8');
  assert.match(
    out,
    /day8\/re-frame2-test-quiet \{:local\/root "\.\.\/test-quiet"\}/,
    'only the coordinate named by LOCAL_ROOT may be rewritten',
  );
});

// ── The rf2-ldkuk regression ──────────────────────────────────────────
// This is the reagent-slim shape: a prose comment quoting the very literal
// the rewrite keys off. Under the old raw-substring assertion this aborted
// the deploy; the leaf was unreleasable.
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
  // If the scanner mistook this `;` for a comment start it would drop the
  // rest of the line and miss the coordinate entirely (exit 3).
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
  assert.equal(run(fix.rel, '../core').status, 0);
  assert.equal(run(fix.rel, '../ssr').status, 0);
  const out = fs.readFileSync(fix.abs, 'utf8');
  assert.match(out, /day8\/re-frame2     \{:mvn\/version "9\.9\.9-TEST"\}/);
  assert.match(out, /day8\/re-frame2-ssr \{:mvn\/version "9\.9\.9-TEST"\}/);
});

// ── Fail-loud invariants ──────────────────────────────────────────────

test('abort: missing deps.edn → exit 2', () => {
  const res = run('.scratch/does-not-exist/deps.edn', '../core');
  assert.equal(res.status, 2);
  assert.match(res.stdout + res.stderr, /::error::.*not found/);
});

test('abort: coordinate absent entirely → exit 3', () => {
  const fix = fixture('{:deps {day8/re-frame2 {:mvn/version "1.0.0"}}}\n');
  const res = run(fix.rel, '../core');
  assert.equal(res.status, 3);
  assert.match(res.stdout + res.stderr, /::error::.*found 0 in code/);
});

test('abort: coordinate present ONLY in comments → exit 3 with a distinct diagnostic', () => {
  // The real coordinate was renamed/removed but the comment still quotes
  // it. Silently doing nothing here would publish a pom with no framework
  // dep, so this must abort — and say why.
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
  assert.match(res.stdout + res.stderr, /::error::.*found 2 in code/);
});

test('abort: deps.edn is left unmodified when the rewrite aborts', () => {
  const body = '{:deps {day8/re-frame2 {:mvn/version "1.0.0"}}}\n';
  const fix = fixture(body);
  run(fix.rel, '../core');
  assert.equal(fs.readFileSync(fix.abs, 'utf8'), body, 'fail-closed: no partial write');
});

test('the script is committed executable (release.yml invokes it directly)', () => {
  // release.yml runs "$GITHUB_WORKSPACE/.github/scripts/…" as a command,
  // so a non-executable mode is a "Permission denied" abort on the runner.
  // Asserted via the git index rather than fs.statSync because Windows
  // checkouts do not carry the POSIX exec bit — which is exactly why this
  // slipped through local testing once already.
  const res = spawnSync('git', ['ls-files', '-s', SCRIPT_REL], {
    cwd: REPO_ROOT,
    encoding: 'utf8',
  });
  assert.equal(res.status, 0, 'git ls-files failed');
  assert.match(
    res.stdout,
    /^100755 /,
    `${SCRIPT_REL} must be mode 100755 in the index, got: ${res.stdout.trim()}`,
  );
});

// ── The fleet gate: every declared leaf must rewrite cleanly ────────────
// Parsed out of release.yml so the test follows the workflow rather than a
// hand-copied duplicate of it. Every `- leaf:` declaration counts, across
// BOTH deploy jobs: the `deploy-leaf` matrix (the eleven independent leaves)
// and `deploy-ssr-ring`, which rf2-p4a93 moved into its own job so it cannot
// publish ahead of the sibling `ssr` leaf its pom depends on. That job keeps
// its leaf declaration in the same matrix shape precisely so this gate keeps
// covering it; the ordering property itself is asserted in
// _release-dag-policy.test.cjs.
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
  // Guard against a silent parse failure reading as green (the false-green
  // trap): if release.yml's matrix shape changes, fail loudly here. Bumped
  // 13 -> 14 for the day8/re-frame2-ui deploy leaf (rf2-vxgfnd.99.2 — the
  // compiled-view substrate joins the lockstep release train), then
  // 14 -> 13 when the helix deploy leaf left the train (S7/W13, rf2-d6epb),
  // then 13 -> 12 when the ui leaf left it again (rf2-a32r7 — re-frame.ui is
  // donor-only and is never published). It stayed 12 when rf2-p4a93 moved
  // ssr-ring out of the matrix into its own job: 11 + 1.
  assert.equal(
    leaves.length,
    12,
    `expected 12 leaf declarations parsed from release.yml, got ${leaves.length}`,
  );

  for (const { leaf, directory, localRoot, extraLocalRoot } of leaves) {
    const real = path.join(REPO_ROOT, directory, 'deps.edn');
    assert.ok(fs.existsSync(real), `${leaf}: ${directory}/deps.edn missing`);
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
