#!/usr/bin/env node

'use strict';

/*
 * Teeth for `.github/workflows/post-merge-workflow-sanity.yml`, the canary that
 * dispatches the workflows a push edited.
 *
 * THE BASE. The identify step diffs against `github.event.before`, the tip
 * `main` pointed at BEFORE the push, not `HEAD^`, which on a multi-commit push
 * is only that push's second-to-last commit. A `fetch-depth: 2` checkout does
 * not hold that base, so the step must fetch it — and a diff that failed there
 * and was swallowed would report "No workflow files changed" and dispatch
 * nothing on exactly the multi-commit pushes that are routine here. So every
 * base arm builds a four-commit origin, clones it `--depth=2`, and asserts as a
 * fixture invariant that the accepted base is absent from the clone; without
 * that, the arms would pass whether or not the step fetches.
 *
 * THE DISPATCH. `gh workflow run --ref` resolves in the REF namespace, so a
 * commit id 422s; the dispatch step must use a branch ref and red when a
 * dispatch fails rather than swallow it.
 *
 * Step bodies are read from the workflow through `lib/workflow-yaml.cjs` and fed
 * to `bash -s` (GitHub's default `run:` shell is bash, and the bodies set
 * `pipefail`) with a fixture checkout as cwd. Every fixture lives under the OS
 * temp dir. Discovered by `npm run test:scripts`.
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
const DISPATCH_STEP_NAME =
  'Dispatch each affected workflow (excluding release + self + push-triggered)';

// The workflow file the fixture's push edits: the canary's real subject, a
// cron-only workflow whose edits are otherwise unverified until the next nightly.
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

function dispatchStep() {
  const step = dispatchJob().steps.find((s) => s.name === DISPATCH_STEP_NAME);
  assert.notEqual(step, undefined, `step "${DISPATCH_STEP_NAME}" not found in ${JOB_ID}`);
  return step;
}

// ── the base fixture ────────────────────────────────────────────────────────

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
// The `--depth=2` clone holds c3 and c2: HEAD^ already carries c1's edit on both
// sides of a diff, and B is not in the clone at all.
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
    // GitHub serves any REACHABLE sha to `git fetch`; the fixture's origin must
    // too, or these arms would test a stricter server than production.
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

    // FIXTURE INVARIANT, measured not assumed.
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
        // Forward slashes: a backslashed Windows path does not survive a bash redirect.
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

test('ARM 2: the shipped step finds a workflow edited in commit 1 of a 3-commit push (rf2-8oh5)', () => {
  withPushFixture((h) => {
    const r = h.run(identifyStep().run, h.acceptedBase);
    assert.equal(r.status, 0, `the step must succeed, got ${r.status}\n${r.output}`);
    assert.equal(
      r.files,
      EDITED_WORKFLOW,
      `the step must report ${EDITED_WORKFLOW}; a base outside the shallow ` +
        `clone makes this empty\n${r.output}`,
    );
  });
});

test('ARM 4: the all-zeros sentinel folds to HEAD^ rather than being passed to git (rf2-8oh5)', () => {
  withPushFixture((h) => {
    // A first push to a fresh ref carries an all-zeros `before`, as a manual
    // dispatch carries none: HEAD^ loses nothing there, but the sentinel must not
    // reach `git diff` as a literal ref, nor be fetched.
    const r = h.run(identifyStep().run, '0'.repeat(40));
    assert.deepEqual(
      { status: r.status, files: r.files, fetched: /fetch/i.test(r.output) },
      { status: 0, files: '', fetched: false },
      r.output,
    );
  });
});

test('ARM 5: an unresolvable base reds the step instead of reporting "nothing changed" (rf2-8oh5)', () => {
  withPushFixture((h) => {
    // The force-push case: `before` is the DISCARDED tip, reachable from no ref.
    // This canary gates nothing, so a visible red blocks nobody, while falling
    // back to HEAD^ would silently reinstate the defect.
    const r = h.run(identifyStep().run, 'dead0000'.repeat(5));
    assert.notEqual(r.status, 0, `an unresolvable base must RED\n${r.output}`);
  });
});

// The arms supply PUSH_BEFORE and DISPATCH_REF themselves, so the workflow's
// wiring of them is pinned here. `main` as a literal ref would be wrong under
// workflow_dispatch, and github.sha cannot resolve in the ref namespace at all.
test('the accepted base and the dispatch ref arrive through env: from the right contexts (rf2-8oh5, rf2-amh0)', () => {
  assert.deepEqual(
    [identifyStep().env.PUSH_BEFORE, dispatchStep().env.DISPATCH_REF],
    ['${{ github.event.before }}', '${{ github.ref_name }}'],
  );
});

// ── the dispatch step ───────────────────────────────────────────────────────

// The one Actions expression the shipped dispatch body carries, deliberately
// unquoted there because the word split is load-bearing. This harness cannot
// evaluate it, so it is substituted with the loop's input.
const FILES_EXPRESSION = '${{ steps.changed.outputs.files }}';

function dispatchBody() {
  return dispatchStep().run.split(FILES_EXPRESSION).join('$CHANGED_FILES');
}

// A `gh` that records every call and fails the targets it is told to, the way
// the real endpoint fails a commit id. A shell FUNCTION prepended to the body
// shadows PATH lookup unconditionally, sidestepping exec-bit and extension rules
// that differ between this box and the Linux runner.
const GH_STUB = `gh() {
  printf '%s\\n' "$*" >> "$GH_CALLS"
  case " \${GH_FAIL_FOR:-} " in
    *" $3 "*)
      echo "could not create workflow dispatch event: HTTP 422: No ref found for: $5" >&2
      return 1
      ;;
  esac
  return 0
}
`;

// A checkout holding just the workflow files the loop reads. `expensive-tests`
// is the canary's real non-excluded dispatchable target; `no-trigger` exercises
// the guard that must skip rather than dispatch.
function withDispatchFixture(body) {
  const tmp = fs.mkdtempSync(path.join(os.tmpdir(), 'rf2-sanity-dispatch-'));
  try {
    const checkoutRoot = path.join(tmp, 'checkout');
    writeFileP(
      checkoutRoot,
      '.github/workflows/expensive-tests.yml',
      'name: expensive\non:\n  workflow_dispatch:\n  schedule:\n    - cron: "17 15 * * *"\n',
    );
    writeFileP(
      checkoutRoot,
      '.github/workflows/other-cron.yml',
      'name: other\non:\n  workflow_dispatch:\n',
    );
    writeFileP(checkoutRoot, '.github/workflows/no-trigger.yml', 'name: nt\non:\n  schedule: []\n');
    writeFileP(checkoutRoot, '.github/workflows/test.yml', 'name: test\non:\n  push:\n');

    let runCount = 0;
    const handle = {
      // Run a dispatch body against the fixture, returning its exit status, its
      // combined output, and every `gh` invocation it made.
      run: (script, { files, ref, failFor = '' }) => {
        const env = { ...process.env };
        for (const key of Object.keys(env)) {
          if (key.startsWith('GIT_')) delete env[key];
        }
        runCount += 1;
        const callFile = path.join(tmp, `gh-calls-${runCount}.txt`);
        fs.writeFileSync(callFile, '');
        env.GH_CALLS = callFile.replace(/\\/g, '/');
        env.GH_FAIL_FOR = failFor;
        env.CHANGED_FILES = files;
        env.DISPATCH_REF = ref;

        const proc = spawnSync('bash', ['-s'], {
          cwd: checkoutRoot,
          env,
          input: `${GH_STUB}${script}`,
          encoding: 'utf8',
        });
        assert.equal(proc.error, undefined, `failed to spawn bash: ${proc.error}`);
        return {
          status: proc.status,
          output: `${proc.stdout}${proc.stderr}`,
          ghCalls: fs
            .readFileSync(callFile, 'utf8')
            .split('\n')
            .filter((l) => l.trim() !== ''),
        };
      },
    };
    body(handle);
  } finally {
    fs.rmSync(tmp, { recursive: true, force: true });
  }
}

test('ARM D2: the shipped dispatch step reds when the dispatch fails (rf2-amh0)', () => {
  withDispatchFixture((h) => {
    const r = h.run(dispatchBody(), {
      files: 'expensive-tests.yml',
      ref: 'main',
      failFor: 'expensive-tests.yml',
    });
    assert.notEqual(
      r.status,
      0,
      'a canary that cannot dispatch must FAIL. A green meaning "we tried" reads to ' +
        `every reader as "it ran"\n${r.output}`,
    );
    assert.match(
      r.output,
      /::error::post-merge workflow sanity could not dispatch expensive-tests\.yml/,
      'and must say so where a reader sees it without opening the log',
    );
  });
});

test('ARM D3: a successful dispatch forwards the branch ref verbatim (rf2-amh0)', () => {
  withDispatchFixture((h) => {
    const r = h.run(dispatchBody(), { files: 'expensive-tests.yml', ref: 'main' });
    assert.equal(r.status, 0, `a clean dispatch must pass, got ${r.status}\n${r.output}`);
    assert.deepEqual(
      r.ghCalls,
      ['workflow run expensive-tests.yml --ref main'],
      'exactly one dispatch, at the BRANCH ref it was given',
    );
  });
});

test('ARM D4: a failing dispatch still attempts the rest, then reds once (rf2-amh0)', () => {
  withDispatchFixture((h) => {
    const r = h.run(dispatchBody(), {
      files: 'expensive-tests.yml other-cron.yml no-trigger.yml test.yml missing.yml',
      ref: 'main',
      failFor: 'expensive-tests.yml',
    });
    assert.notEqual(r.status, 0, `the failure must red the step\n${r.output}`);
    assert.deepEqual(
      r.ghCalls,
      ['workflow run expensive-tests.yml --ref main', 'workflow run other-cron.yml --ref main'],
      'failures are counted rather than aborting the loop, so one bad workflow ' +
        'cannot hide the fate of the others; untriggered, excluded and deleted ones are skipped',
    );
  });
});

run();
