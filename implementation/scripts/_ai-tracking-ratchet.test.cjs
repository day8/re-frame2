#!/usr/bin/env node
/*
 * Teeth test for `scripts/check-ai-tracking-ratchet.sh` (rf2-fr5yx).
 *
 * WHY THIS FILE EXISTS — the guard it tests already shipped broken once, and
 * the reason it shipped broken is precisely that its proof was not committed.
 *
 * rf2-lsp1i (#6380) landed the ratchet with three genuine, passing teeth. Each
 * one tested a SINGLE transition from a pristine baseline, and each one passed
 * honestly. The gate was nevertheless a static COUNT CEILING rather than a
 * ratchet: `89 -> 61` passed, and then `61 -> 62` ALSO passed, because 62 is
 * still under the stored 89. No individual tooth was wrong. The defect lived
 * only in the COMPOSITION of two transitions, which nothing exercised.
 *
 * rf2-ow7dz (#6403) replaced the count comparison with a SET DIFFERENCE taken
 * against the tracked `ai/` set at the change's base commit, read from git, so
 * counts never enter the decision. It proved seven arms — but the proof lived
 * in a worker's chat report, so nothing re-ran it and nothing could have caught
 * a regression back toward a ceiling. This file is that proof, committed.
 *
 * ARM 3 THEN ARM 4 ARE THE POINT. They run against ONE fixture repository, in
 * order, sharing history: the decrease must pass and the very next increase
 * must fail. Split into two pristine-baseline tests they both pass under the
 * OLD broken gate too, which is exactly how the defect survived review. Do not
 * "simplify" them into independent cases.
 *
 * NOTHING IS EVER WRITTEN UNDER THIS REPOSITORY'S `ai/`. Every arm builds a
 * throwaway git repository under the OS temp dir and creates its `ai/` paths
 * there, so the fixtures need no `git add -f` and leave no trace here. That
 * matters more than it looks: the fixed gate has NO in-band escape hatch, so a
 * test that genuinely tracked a file under `ai/` could not clean up after
 * itself without an operator policy exception.
 *
 * The script is fed to `sh` ON STDIN (`sh -s`) with the fixture repo as cwd —
 * the form `_changed-surfaces.test.cjs` uses — which runs the REAL script bytes
 * while sidestepping Git Bash / WSL absolute-path translation entirely. `sh`
 * rather than `bash` because CI invokes it as `sh scripts/...` and the script
 * is POSIX by contract.
 *
 * Wired into `npm run test:script-policy`.
 */

'use strict';

const assert = require('assert/strict');
const { spawnSync, execFileSync } = require('child_process');
const fs = require('fs');
const os = require('os');
const path = require('path');

const IMPL_ROOT = path.resolve(__dirname, '..');
const REPO_ROOT = path.resolve(IMPL_ROOT, '..');
const SCRIPT_PATH = path.join(REPO_ROOT, 'scripts', 'check-ai-tracking-ratchet.sh');

const tests = [];
function test(name, fn) {
  tests.push({ name, fn });
}

function gitIn(cwd, ...args) {
  return execFileSync('git', args, { cwd, encoding: 'utf8', stdio: ['ignore', 'pipe', 'pipe'] });
}

function writeFileP(root, relPath, contents) {
  const abs = path.join(root, relPath);
  fs.mkdirSync(path.dirname(abs), { recursive: true });
  fs.writeFileSync(abs, contents);
}

// A fixture repository, plus the handle each arm drives it through.
//
// `run()` executes the real gate against the CURRENT state of the repo and
// returns its exit status, streams, and the tracked-`ai/` counts on both sides
// of the comparison. The counts are measured, never assumed, so the negative
// control below reasons about the numbers the fixtures actually produce.
function withFixtureRepo(body) {
  const tmp = fs.mkdtempSync(path.join(os.tmpdir(), 'rf2-ai-ratchet-'));
  const scriptSource = fs.readFileSync(SCRIPT_PATH);
  try {
    gitIn(tmp, 'init', '-q');
    gitIn(tmp, 'config', 'user.email', 'ci@example.com');
    gitIn(tmp, 'config', 'user.name', 'CI');
    gitIn(tmp, 'config', 'commit.gpgsign', 'false');
    gitIn(tmp, 'config', 'core.autocrlf', 'false');

    // Paranoia, cheap: prove the fixture is NOT this repository before any arm
    // writes an `ai/` path into it. If a future refactor ever pointed the
    // fixture at the real worktree, these arms would start force-adding under
    // `ai/` for real, and the gate has no way to undo that.
    const fixtureRoot = fs.realpathSync(gitIn(tmp, 'rev-parse', '--show-toplevel').trim());
    assert.notEqual(
      fixtureRoot,
      fs.realpathSync(REPO_ROOT),
      'fixture repo must not be this repository — arms create ai/ paths inside it',
    );

    const handle = {
      root: tmp,
      write: (rel, contents) => writeFileP(tmp, rel, contents),
      git: (...args) => gitIn(tmp, ...args),
      commit: (message) => {
        gitIn(tmp, 'add', '-A');
        gitIn(tmp, 'commit', '-q', '-m', message);
      },
      trackedAi: () =>
        gitIn(tmp, 'ls-files', 'ai/')
          .split(/\r?\n/)
          .filter(Boolean),
      run: (baseRef) => {
        const env = { ...process.env };
        for (const key of Object.keys(env)) {
          if (key.startsWith('GIT_')) delete env[key];
        }
        delete env.AI_RATCHET_BASE_REF;
        if (baseRef !== undefined) env.AI_RATCHET_BASE_REF = baseRef;

        const currentCount = handle.trackedAi().length;
        const proc = spawnSync('sh', ['-s'], {
          cwd: tmp,
          env,
          encoding: 'utf8',
          input: scriptSource,
        });
        if (proc.error) throw proc.error;
        return {
          status: proc.status,
          stdout: proc.stdout || '',
          stderr: proc.stderr || '',
          currentCount,
        };
      },
    };

    return body(handle);
  } finally {
    try {
      fs.rmSync(tmp, { recursive: true, force: true });
    } catch {
      // Windows can transiently hold a lock on the fixture .git; the OS temp
      // dir is reclaimed anyway. A cleanup failure must not fail the test.
    }
  }
}

// Seed `count` tracked files under `ai/` with stable, sortable names.
function seedAi(h, count, prefix = 'note') {
  for (let i = 0; i < count; i += 1) {
    h.write(`ai/findings/${prefix}-${String(i).padStart(3, '0')}.md`, `# ${prefix} ${i}\n`);
  }
}

// ---------------------------------------------------------------------------
// NEGATIVE CONTROL
//
// The strongest evidence #6403 produced was not that the fixed gate fails the
// bad arms — a gate that failed EVERYTHING would do that too. It was that the
// same fixtures returned 0 under the OLD script on arms 4, 5 and 6b while arms
// 2 and 3 behaved correctly there. That proves each fixture DISCRIMINATES.
//
// Reproduced here as the old gate's DECISION RULE rather than as a resurrected
// copy of the old script: a stored count ceiling, compared against the current
// count, blind to WHICH paths are tracked. A rule cannot be run by accident,
// cannot rot into a second gate, and cannot drift out of sync with a shell file
// nobody maintains. The `ceiling` each arm passes is the count that the old
// gate's committed snapshot file would have been holding at that moment —
// stale wherever the arm's whole point is that the snapshot was never
// refreshed after an earlier decrease.
const LEGACY_PASS = 0;
const LEGACY_FAIL = 1;

function legacyCountCeilingVerdict({ ceiling, currentCount }) {
  return currentCount > ceiling ? LEGACY_FAIL : LEGACY_PASS;
}

// ---------------------------------------------------------------------------
// ARM 1 — unmutated tree passes.

test('ARM 1: a change touching nothing under ai/ passes', () => {
  withFixtureRepo((h) => {
    h.write('README.md', '# fixture\n');
    seedAi(h, 3);
    h.commit('seed');

    h.write('README.md', '# fixture, edited\n');
    h.commit('unrelated change');

    const r = h.run();
    assert.equal(r.status, 0, `expected pass, got ${r.status}\n${r.stderr}`);
    assert.match(r.stdout, /3 tracked file\(s\) under ai\/, unchanged from base/);
  });
});

// ---------------------------------------------------------------------------
// ARM 2 — a force-add fails, and NAMES THE PATH.

test('ARM 2: a newly tracked ai/ path fails and names the path', () => {
  withFixtureRepo((h) => {
    h.write('README.md', '# fixture\n');
    seedAi(h, 1);
    h.commit('seed');

    h.write('ai/decisions/dossier.md', '# a force-added dossier\n');
    h.commit('force-add under ai/');

    const r = h.run();
    assert.equal(r.status, 1, 'a newly tracked ai/ path must fail');
    assert.match(r.stderr, /ADDED: ai\/decisions\/dossier\.md/);
    assert.match(r.stderr, /1 path\(s\) newly tracked under ai\//);

    // Discrimination: the OLD gate caught this one too (1 -> 2 exceeds a fresh
    // ceiling of 1). Arm 2 is here to prove the message names the path, not to
    // prove the set rule — it is one of the two arms where old and new agree.
    assert.equal(
      legacyCountCeilingVerdict({ ceiling: 1, currentCount: r.currentCount }),
      LEGACY_FAIL,
      'negative control: the old count ceiling also failed a plain force-add',
    );
  });
});

// ---------------------------------------------------------------------------
// ARMS 3 AND 4 — THE SEQUENCE. One repo, two transitions, in order.

test('ARMS 3+4: 89 -> 61 passes, and THEN 61 -> 62 fails (the sequence, not the cases)', () => {
  withFixtureRepo((h) => {
    h.write('README.md', '# fixture\n');
    seedAi(h, 89);
    h.commit('seed 89 tracked ai/ files');
    const ceilingAtSnapshot = h.trackedAi().length;
    assert.equal(ceilingAtSnapshot, 89, 'fixture must start at 89 tracked ai/ files');

    // ARM 3 — an S7-style removal sweep. A decrease is the expected direction
    // of travel and must never be blocked.
    for (let i = 61; i < 89; i += 1) {
      h.git('rm', '-q', `ai/findings/note-${String(i).padStart(3, '0')}.md`);
    }
    h.commit('remove 28 tracked ai/ files');

    const decrease = h.run();
    assert.equal(decrease.status, 0, `a decrease must pass, got ${decrease.status}\n${decrease.stderr}`);
    assert.equal(decrease.currentCount, 61);
    assert.match(decrease.stdout, /FELL from 89 to 61/);
    assert.equal(
      legacyCountCeilingVerdict({ ceiling: 89, currentCount: decrease.currentCount }),
      LEGACY_PASS,
      'negative control: the old ceiling also passed the decrease (old and new agree here)',
    );

    // ARM 4 — the very next commit, on the SAME history. This is the transition
    // the original gate waved through, and the whole reason this file exists.
    h.write('ai/findings/note-999.md', '# smuggled back in after the sweep\n');
    h.commit('add one file back after the sweep');

    const increase = h.run();
    assert.equal(increase.currentCount, 62);
    assert.equal(
      increase.status,
      1,
      'an addition AFTER a decrease must fail — this is the exact hole rf2-ow7dz closed',
    );
    assert.match(increase.stderr, /ADDED: ai\/findings\/note-999\.md/);
    assert.match(increase.stderr, /Base HEAD\^ tracked 61 file\(s\) under ai\/; this change tracks 62/);

    // The fixture's discriminating property, made explicit: 62 is still far
    // BELOW the 89 the old snapshot was holding, so the old rule saw nothing
    // wrong. Counts do not enter the new decision at all.
    assert.ok(increase.currentCount < ceilingAtSnapshot, 'arm 4 only discriminates while 62 < 89');
    assert.equal(
      legacyCountCeilingVerdict({ ceiling: ceilingAtSnapshot, currentCount: increase.currentCount }),
      LEGACY_PASS,
      'negative control: the old count ceiling PASSED this — the fixture discriminates',
    );
  });
});

// ---------------------------------------------------------------------------
// ARM 5 — an equal-count swap fails, naming only the ADDED path.

test('ARM 5: a swap that leaves the count unchanged fails, naming the added path', () => {
  withFixtureRepo((h) => {
    h.write('README.md', '# fixture\n');
    h.write('ai/findings/kept.md', '# kept\n');
    h.write('ai/findings/removed.md', '# to be removed\n');
    h.commit('seed 2 tracked ai/ files');

    h.git('rm', '-q', 'ai/findings/removed.md');
    h.write('ai/findings/smuggled.md', '# brand new, invisible to a count\n');
    h.commit('swap one ai/ file for another');

    const r = h.run();
    assert.equal(r.currentCount, 2, 'the swap must leave the count unchanged');
    assert.equal(r.status, 1, 'an equal-count swap must fail — a new path is a new path');
    assert.match(r.stderr, /ADDED: ai\/findings\/smuggled\.md/);
    assert.doesNotMatch(
      r.stderr,
      /ADDED: ai\/findings\/removed\.md/,
      'the removed path is not an addition and must not be named as one',
    );

    assert.equal(
      legacyCountCeilingVerdict({ ceiling: 2, currentCount: r.currentCount }),
      LEGACY_PASS,
      'negative control: the old count ceiling PASSED an equal-count swap — the fixture discriminates',
    );
  });
});

// ---------------------------------------------------------------------------
// ARMS 6a AND 6b — the END STATE, and additions after it. Also a sequence: the
// stale ceiling that made 6b leak only exists because of the earlier drop.

test('ARMS 6a+6b: zero vs zero passes, and THEN an addition after zero fails', () => {
  withFixtureRepo((h) => {
    h.write('README.md', '# fixture\n');
    seedAi(h, 3);
    h.commit('seed 3 tracked ai/ files');
    const ceilingAtSnapshot = h.trackedAi().length;

    h.git('rm', '-q', '-r', 'ai');
    h.commit('remove the last tracked ai/ files');
    const drained = h.run();
    assert.equal(drained.status, 0, 'draining ai/ to zero must pass');
    assert.equal(drained.currentCount, 0);

    // ARM 6a — zero vs zero: the END STATE, held.
    h.write('README.md', '# fixture, edited\n');
    h.commit('unrelated change at the end state');
    const held = h.run();
    assert.equal(held.status, 0, `zero vs zero must pass, got ${held.status}\n${held.stderr}`);
    assert.equal(held.currentCount, 0);
    assert.match(held.stdout, /nothing under ai\/ is tracked\. END STATE reached/);

    // ARM 6b — an addition made after the end state was reached. Under a stored
    // ceiling this leaked indefinitely: the ceiling was still 3.
    h.write('ai/notes/reintroduced.md', '# added after the end state\n');
    h.commit('add a file after the end state');
    const reintroduced = h.run();
    assert.equal(reintroduced.currentCount, 1);
    assert.equal(reintroduced.status, 1, 'an addition after zero must fail');
    assert.match(reintroduced.stderr, /ADDED: ai\/notes\/reintroduced\.md/);

    assert.equal(
      legacyCountCeilingVerdict({ ceiling: ceilingAtSnapshot, currentCount: reintroduced.currentCount }),
      LEGACY_PASS,
      'negative control: the old ceiling of 3 PASSED an addition after zero — the fixture discriminates',
    );
  });
});

// ---------------------------------------------------------------------------
// ARM 7 — an unresolvable base fails CLOSED. A guard that cannot see its base
// must not certify anything.

test('ARM 7: an unresolvable base ref fails closed rather than passing vacuously', () => {
  withFixtureRepo((h) => {
    h.write('README.md', '# fixture\n');
    seedAi(h, 2);
    h.commit('root commit — HEAD^ does not exist');

    // The realistic shape, taking the DEFAULT base: a root commit, which is
    // also what a depth-1 shallow clone looks like to this gate. This is why
    // portability.yml checks out with fetch-depth: 2.
    const rootCommit = h.run();
    assert.equal(rootCommit.status, 1, 'an unresolvable HEAD^ must fail, not pass');
    assert.match(rootCommit.stderr, /cannot resolve base ref HEAD\^/);
    assert.match(rootCommit.stderr, /fetch-depth: 2/);

    // And the explicit-override shape, for completeness.
    const bogus = h.run('refs/heads/no-such-branch');
    assert.equal(bogus.status, 1, 'an unresolvable explicit base ref must fail, not pass');
    assert.match(bogus.stderr, /cannot resolve base ref refs\/heads\/no-such-branch/);
  });
});

// ---------------------------------------------------------------------------
// Non-vacuity of the SUITE itself: the arms above are not all failures. If a
// future edit made the gate fail unconditionally, arms 1, 3, 6a and the drain
// would catch it — this asserts that mix is actually present, so the suite
// cannot be trimmed down to failures-only without noticing.

test('SUITE: the arms include genuine passes, so a fail-everything gate is caught', () => {
  const names = tests.map((t) => t.name);
  assert.ok(names.some((n) => n.startsWith('ARM 1')), 'a passing arm must be present');
  assert.ok(names.some((n) => n.startsWith('ARMS 3+4')), 'the decrease-then-increase sequence must be present');
  assert.ok(names.some((n) => n.startsWith('ARMS 6a+6b')), 'the end-state sequence must be present');
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
  console.error(`ai/-tracking-ratchet teeth: ${failed} failed.`);
  process.exit(1);
}

console.log(`ai/-tracking-ratchet teeth: ${tests.length} passed.`);
