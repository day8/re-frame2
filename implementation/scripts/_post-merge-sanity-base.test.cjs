#!/usr/bin/env node

'use strict';

/*
 * Teeth for the base resolution in `.github/workflows/post-merge-workflow-sanity.yml`
 * (rf2-8oh5).
 *
 * THE DEFECT. That workflow applied the rf2-7hq4l remedy correctly — it
 * resolves its diff base from `github.event.before`, the tip `main` pointed at
 * BEFORE the push, rather than from `HEAD^`, which on a multi-commit push is
 * only that same push's second-to-last commit. But it checked out at
 * `fetch-depth: 2`, which holds HEAD and HEAD^ and nothing else. On a push of
 * two or more commits the accepted base is not in the clone at all, so:
 *
 *     changed=$(git diff --name-only "$before" HEAD -- '.github/workflows/*.yml' \
 *       | xargs -n1 -I{} basename {} || true)
 *
 * `git diff` failed, the `|| true` swallowed it — and `pipefail` with it — and
 * the step reported "No workflow files changed in this push." and exited 0.
 * The canary whose whole job is to dispatch the workflows a push edited
 * dispatched nothing, silently, on exactly the multi-commit pushes rf2-34yg
 * measured as routine here.
 *
 * WHY THIS FILE EXISTS. The defect is invisible to any test that runs on a
 * single-commit push, and invisible to any test that hands the step a base it
 * can already see. Both halves have to be real for the proof to mean anything:
 * a MULTI-COMMIT push, and a checkout SHALLOWER than its accepted base. So
 * every behavioural arm builds a throwaway origin repository with four commits,
 * then `git clone --depth=2 file://…` from it, and asserts as a fixture
 * invariant that the accepted base is genuinely absent from the clone before it
 * runs anything. Without that invariant the whole file would pass vacuously.
 *
 * ARM 1 IS THE CONTROL AND IT IS THE POINT. It runs the PRE-FIX step body,
 * frozen as a string constant here, against the same fixture, and asserts the
 * old bug: exit 0, "No workflow files changed in this push.", empty output —
 * over a push that changed a workflow file in its first commit. ARM 2 runs the
 * REAL step body, read out of the workflow file itself so it cannot drift from
 * what CI executes, and asserts the file is found. Delete ARM 1 and ARM 2 stops
 * proving anything, because a fixture that armed trivially would look identical.
 *
 * ARM 3 is the second control, aimed at the base SELECTION rather than the
 * depth: the same fixed body with no accepted base falls to HEAD^ and misses
 * the same change. So ARM 2 needs BOTH the accepted base and the fetch.
 *
 * The step body is fed to `bash` ON STDIN (`bash -s`) with the fixture checkout
 * as cwd — the form `_ai-tracking-ratchet.test.cjs` uses — which runs the real
 * bytes while sidestepping Git Bash absolute-path translation. `bash` rather
 * than `sh` because GitHub's default shell for a `run:` block is `bash -e {0}`,
 * and the body sets `pipefail`.
 *
 * The workflow is read through `lib/workflow-yaml.cjs` rather than scraped with
 * regexes, so `run:` arrives already dedented and `fetch-depth` is read off the
 * checkout step as structure. Nothing is ever written inside this repository:
 * every fixture lives under the OS temp dir and is removed on the way out.
 *
 * Discovered by `npm run test:scripts`.
 */

const assert = require('assert/strict');
const { spawnSync, execFileSync } = require('child_process');
const fs = require('fs');
const os = require('os');
const path = require('path');
const { pathToFileURL } = require('url');

const { createPolicyTestSuite } = require('./_policy-test-util.cjs');
const { parseWorkflowYaml } = require('./lib/workflow-yaml.cjs');

const IMPL_ROOT = path.resolve(__dirname, '..');
const REPO_ROOT = path.resolve(IMPL_ROOT, '..');
const WORKFLOW = path.join(REPO_ROOT, '.github', 'workflows', 'post-merge-workflow-sanity.yml');
const JOB_ID = 'dispatch-affected-workflows';
const STEP_NAME = 'Identify changed workflow files';

// The workflow file the fixture's push edits. Any name would do; this one is
// the canary's real subject — a cron-only workflow whose edits are otherwise
// unverified until the next nightly tick.
const EDITED_WORKFLOW = 'expensive-tests.yml';

const { test, run } = createPolicyTestSuite('post-merge-sanity-base');

// ── reading the workflow ────────────────────────────────────────────────────

function dispatchJob() {
  const doc = parseWorkflowYaml(fs.readFileSync(WORKFLOW, 'utf8'));
  const job = doc.jobs && doc.jobs[JOB_ID];
  assert.notEqual(job, undefined, `job ${JOB_ID} not found in ${WORKFLOW}`);
  return job;
}

function identifyStep() {
  const step = dispatchJob().steps.find((s) => s.name === STEP_NAME);
  assert.notEqual(step, undefined, `step "${STEP_NAME}" not found in ${JOB_ID}`);
  return step;
}

function checkoutStep() {
  const step = dispatchJob().steps.find(
    (s) => typeof s.uses === 'string' && s.uses.startsWith('actions/checkout@'),
  );
  assert.notEqual(step, undefined, `${JOB_ID} must check the repository out`);
  return step;
}

// THE PRE-FIX BODY, frozen. One deliberate difference from the shipped bytes:
// the workflow interpolated `${{ github.event.before }}` directly into the run
// body and this harness cannot evaluate an Actions expression, so the base is
// read from the same PUSH_BEFORE variable the fixed step uses. That
// substitution is orthogonal to the defect — the bug is the base being
// unreachable, not how it arrives — and it keeps both arms on one runner.
const LEGACY_STEP_BODY = `set -euo pipefail
before="\${PUSH_BEFORE:-}"
if [ -z "$before" ] || [ "$before" = "0000000000000000000000000000000000000000" ]; then
  before="HEAD^"
fi
changed=$(git diff --name-only "$before" HEAD -- '.github/workflows/*.yml' \\
  | xargs -n1 -I{} basename {} || true)
if [ -z "$changed" ]; then
  echo "No workflow files changed in this push."
  echo "files=" >> "$GITHUB_OUTPUT"
  exit 0
fi
echo "Changed workflow files:"
echo "$changed"
changed_oneline=$(echo "$changed" | tr '\\n' ' ')
echo "files=\${changed_oneline}" >> "$GITHUB_OUTPUT"
`;

// ── the fixture ─────────────────────────────────────────────────────────────

function gitIn(cwd, ...args) {
  return execFileSync('git', args, { cwd, encoding: 'utf8', stdio: ['ignore', 'pipe', 'pipe'] });
}

function writeFileP(root, relPath, contents) {
  const abs = path.join(root, relPath);
  fs.mkdirSync(path.dirname(abs), { recursive: true });
  fs.writeFileSync(abs, contents);
}

// A real multi-commit push, cloned shallower than its own accepted base.
//
//   B  ← the tip main pointed at BEFORE the push; `github.event.before`
//   c1   edits .github/workflows/<EDITED_WORKFLOW>   ← THE CHANGE
//   c2   docs only
//   c3   docs only                                   ← the pushed TIP, HEAD
//
// The clone is `--depth=2`, so it holds c3 and c2. HEAD^ is c2, which already
// carries c1's workflow edit on both sides of a diff — and B, the base that
// would see it, is not in the clone at all. That is the shape both halves of
// the defect need, and the `cat-file` probe below asserts the second half
// rather than assuming it.
function withPushFixture(body) {
  const tmp = fs.mkdtempSync(path.join(os.tmpdir(), 'rf2-sanity-base-'));
  try {
    const originRoot = path.join(tmp, 'origin');
    fs.mkdirSync(originRoot, { recursive: true });
    gitIn(originRoot, 'init', '-q', '-b', 'main');
    gitIn(originRoot, 'config', 'user.email', 'ci@example.com');
    gitIn(originRoot, 'config', 'user.name', 'CI');
    gitIn(originRoot, 'config', 'commit.gpgsign', 'false');
    gitIn(originRoot, 'config', 'core.autocrlf', 'false');
    // GitHub serves any REACHABLE sha to `git fetch`, which is what makes
    // fetch-by-name work there; the fixture's origin must do the same or these
    // arms would be testing a stricter server than production.
    gitIn(originRoot, 'config', 'uploadpack.allowReachableSHA1InWant', 'true');

    const commit = (message) => {
      gitIn(originRoot, 'add', '-A');
      gitIn(originRoot, 'commit', '-q', '-m', message);
    };

    writeFileP(originRoot, 'README.md', '# fixture\n');
    writeFileP(
      originRoot,
      `.github/workflows/${EDITED_WORKFLOW}`,
      'name: expensive\non:\n  workflow_dispatch:\n',
    );
    commit('B: the tip main pointed at BEFORE the push');
    const acceptedBase = gitIn(originRoot, 'rev-parse', 'HEAD').trim();

    writeFileP(
      originRoot,
      `.github/workflows/${EDITED_WORKFLOW}`,
      'name: expensive\non:\n  workflow_dispatch:\n  schedule:\n    - cron: "17 15 * * *"\n',
    );
    commit('push commit 1 — THE WORKFLOW EDIT');
    writeFileP(originRoot, 'docs/a.md', '# a\n');
    commit('push commit 2 — docs only');
    writeFileP(originRoot, 'docs/b.md', '# b\n');
    commit('push commit 3 — the pushed TIP, docs only');

    const checkoutRoot = path.join(tmp, 'checkout');
    execFileSync(
      'git',
      ['clone', '--quiet', '--depth=2', pathToFileURL(originRoot).href, checkoutRoot],
      { cwd: tmp, encoding: 'utf8', stdio: ['ignore', 'pipe', 'pipe'] },
    );

    // FIXTURE INVARIANT, measured not assumed. If the accepted base were in the
    // clone, every arm below would pass with or without the fix.
    const probe = spawnSync('git', ['cat-file', '-e', `${acceptedBase}^{commit}`], {
      cwd: checkoutRoot,
      encoding: 'utf8',
    });
    assert.notEqual(
      probe.status,
      0,
      'fixture invariant: the accepted base must be OUTSIDE the depth-2 clone, ' +
        'or these arms prove nothing',
    );

    let runCount = 0;
    const handle = {
      acceptedBase,
      checkoutRoot,
      // Run a step body against the fixture checkout, returning its exit
      // status, its combined output, and the `files=` output it wrote.
      run: (script, pushBefore) => {
        const env = { ...process.env };
        for (const key of Object.keys(env)) {
          if (key.startsWith('GIT_')) delete env[key];
        }
        delete env.PUSH_BEFORE;
        if (pushBefore !== undefined) env.PUSH_BEFORE = pushBefore;

        runCount += 1;
        const outFile = path.join(tmp, `github-output-${runCount}.txt`);
        fs.writeFileSync(outFile, '');
        // Forward slashes: the body redirects into "$GITHUB_OUTPUT" and a
        // backslashed Windows path does not survive a bash redirect.
        env.GITHUB_OUTPUT = outFile.replace(/\\/g, '/');

        const proc = spawnSync('bash', ['-s'], {
          cwd: checkoutRoot,
          env,
          input: script,
          encoding: 'utf8',
        });
        assert.equal(proc.error, undefined, `failed to spawn bash: ${proc.error}`);
        const written = fs.readFileSync(outFile, 'utf8');
        const m = /^files=(.*)$/m.exec(written);
        return {
          status: proc.status,
          output: `${proc.stdout}${proc.stderr}`,
          files: m === null ? null : m[1].trim(),
        };
      },
    };
    body(handle);
  } finally {
    fs.rmSync(tmp, { recursive: true, force: true });
  }
}

// ── ARM 1 — the defect, frozen as the control ───────────────────────────────

test('ARM 1 (CONTROL): the pre-fix body reports "No workflow files changed" over a push that changed one (rf2-8oh5)', () => {
  withPushFixture((h) => {
    const r = h.run(LEGACY_STEP_BODY, h.acceptedBase);
    assert.equal(
      r.status,
      0,
      'the pre-fix body EXITED 0 — that is the defect: `|| true` swallowed the ' +
        `failed diff and the step passed. Got ${r.status}\n${r.output}`,
    );
    assert.match(
      r.output,
      /No workflow files changed in this push\./,
      'the pre-fix body reported nothing changed; if this stops matching, ARM 2 ' +
        'has lost its control',
    );
    assert.equal(r.files, '', 'and dispatched nothing');
  });
});

// ── ARM 2 — the fix ─────────────────────────────────────────────────────────

test('ARM 2: the shipped step finds a workflow edited in commit 1 of a 3-commit push (rf2-8oh5)', () => {
  withPushFixture((h) => {
    const r = h.run(identifyStep().run, h.acceptedBase);
    assert.equal(r.status, 0, `the step must succeed, got ${r.status}\n${r.output}`);
    assert.equal(
      r.files,
      EDITED_WORKFLOW,
      `the step must report ${EDITED_WORKFLOW}; a base outside the shallow ` +
        `clone makes this empty (rf2-8oh5)\n${r.output}`,
    );
  });
});

// ── ARM 3 — the base-selection control ──────────────────────────────────────

test('ARM 3 (CONTROL): with no accepted base the same step falls to HEAD^ and misses it (rf2-7hq4l)', () => {
  withPushFixture((h) => {
    // The rf2-7hq4l blind spot, kept live: HEAD^ is commit 2 of this same push,
    // which already carries commit 1's workflow edit, so the diff is empty. ARM
    // 2 therefore needs the accepted base AND the fetch — neither alone.
    const r = h.run(identifyStep().run, '');
    assert.equal(r.status, 0, `a manual dispatch must not red, got ${r.status}\n${r.output}`);
    assert.equal(
      r.files,
      '',
      'HEAD^ points INSIDE the push and misses a workflow edited before the tip',
    );
  });
});

// ── ARM 4 — the all-zeros sentinel ──────────────────────────────────────────

test('ARM 4: the all-zeros sentinel folds to HEAD^ rather than being passed to git (rf2-8oh5)', () => {
  withPushFixture((h) => {
    // A first push to a fresh ref carries an all-zeros `before`. There is no
    // earlier accepted state to have missed, so HEAD^ loses nothing — but it
    // must not reach `git diff` as a literal ref, and it must not be fetched.
    const r = h.run(identifyStep().run, '0'.repeat(40));
    assert.equal(r.status, 0, `all-zeros must fold, not fail, got ${r.status}\n${r.output}`);
    assert.equal(r.files, '');
    assert.doesNotMatch(r.output, /fetch/i, 'the sentinel must not be fetched by name');
  });
});

// ── ARM 5 — an unresolvable base fails LOUD ─────────────────────────────────

test('ARM 5: an unresolvable base reds the step instead of reporting "nothing changed" (rf2-8oh5)', () => {
  withPushFixture((h) => {
    // The force-push case: `before` is the DISCARDED tip, reachable from no ref,
    // so no depth and no fetch can produce it. This canary GATES NOTHING — the
    // merge has already happened and no required check consumes its verdict — so
    // a red costs one visible failure and blocks nobody, which makes "say so
    // where somebody will see it" better than either alternative: falling back
    // to HEAD^ silently reinstates the defect, and dispatching everything spends
    // the nightly matrix to answer the question while leaving no trace that the
    // base resolution broke at all.
    const r = h.run(identifyStep().run, 'dead0000'.repeat(5));
    assert.notEqual(r.status, 0, `an unresolvable base must RED\n${r.output}`);
    assert.match(r.output, /dead0000/, 'and must name the base it could not resolve');
    assert.doesNotMatch(
      r.output,
      /No workflow files changed in this push\./,
      'it must never report "nothing changed" over a base it cannot read',
    );
  });
});

// ── ARM 6 — the workflow half ───────────────────────────────────────────────

test('the checkout depth and the fetch-by-name travel together (rf2-8oh5)', () => {
  // THE CALLER HALF. ARMS 1-5 execute the step body against a fixture and would
  // all stay green if a future edit raised the checkout to a depth that happened
  // to cover the fixture while CI's real pushes went deeper. Reachability of the
  // accepted base is ONE decision spread across two keys, so it is pinned as one:
  // either the clone carries full history, or the step fetches the base by name.
  const depth = checkoutStep().with['fetch-depth'];
  if (depth !== '0') {
    assert.match(
      identifyStep().run,
      /git fetch --no-tags --no-recurse-submodules --depth=1 origin "\$base"/,
      `the checkout is fetch-depth: ${depth}, so the accepted base — which is ` +
        'arbitrarily deeper — must be fetched BY NAME (portability.yml is the ' +
        'worked precedent). Depth alone is the rf2-8oh5 defect.',
    );
  }
});

test('the reachability check consults the object store (rf2-uol6)', () => {
  assert.match(
    identifyStep().run,
    /git rev-parse --verify --quiet "\$base\^\{commit\}"/,
    '`git rev-parse --verify` echoes any 40-hex string back with exit 0 WITHOUT ' +
      'consulting the object store (rf2-uol6), so peeling to ^{commit} is what ' +
      'makes this a reachability check rather than a syntax check',
  );
});

test('the accepted base arrives through env:, never interpolated into the run body (rf2-8oh5)', () => {
  const step = identifyStep();
  assert.equal(
    step.env && step.env.PUSH_BEFORE,
    '${{ github.event.before }}',
    'the step must take github.event.before as PUSH_BEFORE',
  );
  assert.doesNotMatch(
    step.run,
    /\$\{\{/,
    'context values arrive via env:, never interpolated into the script body — ' +
      'portability.yml states the reason: the step stays injection-safe whatever ' +
      'the event carries',
  );
});

test('the diff cannot swallow its own failure (rf2-8oh5)', () => {
  // The single byte-sequence that turned a broken diff into a green "nothing
  // changed". With it gone, `set -euo pipefail` reds any residual failure, so
  // the whole path is fail-closed rather than fail-open.
  assert.doesNotMatch(
    identifyStep().run,
    /basename \{\}\s*\|\|\s*true/,
    '`|| true` on the diff pipeline converts a git failure into "no workflow ' +
      'files changed" and swallows pipefail with it (rf2-8oh5)',
  );
});

run();
