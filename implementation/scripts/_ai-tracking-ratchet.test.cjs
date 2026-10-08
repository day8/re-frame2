#!/usr/bin/env node
/*
 * Teeth test for `scripts/check-ai-tracking-ratchet.sh`.
 *
 * A stored count ceiling looks like a ratchet and is not one: `89 -> 61` passes,
 * and then `61 -> 62` ALSO passes, because 62 is under the stored 89. So the gate
 * takes a SET DIFFERENCE against the tracked `ai/` set at the change's base commit,
 * read from git, and counts never enter its decision.
 *
 * ARM 3 THEN ARM 4 ARE THE POINT, and so are 6a then 6b: each pair runs on ONE
 * fixture repository, in order, sharing history. Split into pristine-baseline
 * cases they would both pass under a count ceiling too, hiding exactly the defect
 * this file exists to catch. Do not "simplify" them into independent cases.
 *
 * Every arm builds a throwaway git repository under the OS temp dir and creates
 * its `ai/` paths there; nothing is written under this repository's `ai/`. The
 * real script is fed to `sh -s` with the fixture as cwd, as CI runs it.
 * Discovered by `npm run test:scripts`.
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

// A fixture repository; `run()` executes the real gate against its current state.
function withFixtureRepo(body) {
  const tmp = fs.mkdtempSync(path.join(os.tmpdir(), 'rf2-ai-ratchet-'));
  const scriptSource = fs.readFileSync(SCRIPT_PATH);
  try {
    gitIn(tmp, 'init', '-q');
    gitIn(tmp, 'config', 'user.email', 'ci@example.com');
    gitIn(tmp, 'config', 'user.name', 'CI');
    gitIn(tmp, 'config', 'commit.gpgsign', 'false');
    gitIn(tmp, 'config', 'core.autocrlf', 'false');

    const handle = {
      root: tmp,
      write: (rel, contents) => writeFileP(tmp, rel, contents),
      git: (...args) => gitIn(tmp, ...args),
      commit: (message) => {
        gitIn(tmp, 'add', '-A');
        gitIn(tmp, 'commit', '-q', '-m', message);
      },
      run: (baseRef) => {
        const env = { ...process.env };
        for (const key of Object.keys(env)) {
          if (key.startsWith('GIT_')) delete env[key];
        }
        delete env.AI_RATCHET_BASE_REF;
        if (baseRef !== undefined) env.AI_RATCHET_BASE_REF = baseRef;

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

test('ARM 1: a change touching nothing under ai/ passes', () => {
  withFixtureRepo((h) => {
    h.write('README.md', '# fixture\n');
    seedAi(h, 3);
    h.commit('seed');

    h.write('README.md', '# fixture, edited\n');
    h.commit('unrelated change');

    const r = h.run();
    assert.equal(r.status, 0, `expected pass, got ${r.status}\n${r.stderr}`);
  });
});

test('ARMS 3+4: 89 -> 61 passes, and THEN 61 -> 62 fails (the sequence, not the cases)', () => {
  withFixtureRepo((h) => {
    h.write('README.md', '# fixture\n');
    seedAi(h, 89);
    h.commit('seed 89 tracked ai/ files');

    // ARM 3 — a removal sweep: a decrease must never be blocked.
    for (let i = 61; i < 89; i += 1) {
      h.git('rm', '-q', `ai/findings/note-${String(i).padStart(3, '0')}.md`);
    }
    h.commit('remove 28 tracked ai/ files');

    const decrease = h.run();
    assert.equal(decrease.status, 0, `a decrease must pass, got ${decrease.status}\n${decrease.stderr}`);

    // ARM 4 — the very next commit, on the SAME history: 62 is still under the
    // 89 a count ceiling would hold, so only the set rule fails it.
    h.write('ai/findings/note-999.md', '# smuggled back in after the sweep\n');
    h.commit('add one file back after the sweep');

    const increase = h.run();
    assert.equal(
      increase.status,
      1,
      'an addition AFTER a decrease must fail — the exact hole a count ceiling leaves open',
    );
    assert.match(increase.stderr, /ADDED: ai\/findings\/note-999\.md/);
  });
});

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
    assert.equal(r.status, 1, 'an equal-count swap must fail — a new path is a new path');
    assert.match(r.stderr, /ADDED: ai\/findings\/smuggled\.md/);
    assert.doesNotMatch(
      r.stderr,
      /ADDED: ai\/findings\/removed\.md/,
      'the removed path is not an addition and must not be named as one',
    );
  });
});

// Also a sequence: a stale ceiling would leak 6b only because of the earlier drop.
test('ARMS 6a+6b: zero vs zero passes, and THEN an addition after zero fails', () => {
  withFixtureRepo((h) => {
    h.write('README.md', '# fixture\n');
    seedAi(h, 3);
    h.commit('seed 3 tracked ai/ files');

    h.git('rm', '-q', '-r', 'ai');
    h.commit('remove the last tracked ai/ files');
    const drained = h.run();
    assert.equal(drained.status, 0, 'draining ai/ to zero must pass');

    // ARM 6a — zero vs zero: the END STATE, held.
    h.write('README.md', '# fixture, edited\n');
    h.commit('unrelated change at the end state');
    const held = h.run();
    assert.equal(held.status, 0, `zero vs zero must pass, got ${held.status}\n${held.stderr}`);

    // ARM 6b — an addition made after the end state was reached.
    h.write('ai/notes/reintroduced.md', '# added after the end state\n');
    h.commit('add a file after the end state');
    const reintroduced = h.run();
    assert.equal(reintroduced.status, 1, 'an addition after zero must fail');
    assert.match(reintroduced.stderr, /ADDED: ai\/notes\/reintroduced\.md/);
  });
});

// A root commit is also what a depth-1 shallow clone looks like to this gate.
test('ARM 7: an unresolvable base ref fails closed rather than passing vacuously', () => {
  withFixtureRepo((h) => {
    h.write('README.md', '# fixture\n');
    seedAi(h, 2);
    h.commit('root commit — HEAD^ does not exist');

    const rootCommit = h.run();
    assert.equal(rootCommit.status, 1, 'an unresolvable HEAD^ must fail, not pass');
    assert.match(rootCommit.stderr, /cannot resolve base ref HEAD\^/);
  });
});

// On a multi-commit push HEAD^ is the push's own second-to-last commit, so a path
// first tracked earlier in the push is on both sides of a HEAD^ diff. The workflow
// passes the accepted base (github.event.before); against it the path is caught.
test('ARM 8: a multi-commit push — an ai/ path added before the tip escapes HEAD^ but is caught by the accepted push base', () => {
  withFixtureRepo((h) => {
    h.write('README.md', '# fixture\n');
    h.write('ai/findings/kept.md', '# already accepted\n');
    h.commit('B: the previously accepted main tip');
    const acceptedBase = h.git('rev-parse', 'HEAD').trim();

    h.write('ai/_audit-multicommit-probe.md', '# added in an earlier commit of the push\n');
    h.commit('c1: track a new ai/ path');

    h.write('README.md', '# fixture, edited at the tip\n');
    h.commit('c2: the pushed tip, probe retained');

    const viaHeadParent = h.run('HEAD^');
    assert.equal(
      viaHeadParent.status,
      0,
      'HEAD^ points inside the push and misses a path added before the tip',
    );

    const viaAcceptedBase = h.run(acceptedBase);
    assert.equal(
      viaAcceptedBase.status,
      1,
      'against the accepted push base a path first tracked in an earlier commit must fail',
    );
    assert.match(viaAcceptedBase.stderr, /ADDED: ai\/_audit-multicommit-probe\.md/);
  });
});

// `git rev-parse --verify --quiet <40-hex>` exits 0 for a sha absent from the
// object store, so a guard built on it would pass the base an over-shallow CI
// clone hands it and certify an END STATE it never read.
test('ARM 9: a base sha absent from the object store fails closed, not vacuously green', () => {
  withFixtureRepo((h) => {
    h.write('README.md', '# fixture\n');
    seedAi(h, 2);
    h.commit('seed');
    h.write('README.md', '# fixture, edited\n');
    h.commit('a second commit, so HEAD^ resolves and only the ABSENT base is at issue');

    const absent = '0123456789abcdef0123456789abcdef01234567';
    const r = h.run(absent);
    assert.equal(r.status, 1, 'an absent base sha must fail closed');
    assert.match(r.stderr, new RegExp(`cannot resolve base ref ${absent}`));
  });
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
