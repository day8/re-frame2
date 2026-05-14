#!/usr/bin/env node
/*
 * Tests for `_path-policy.cjs` (rf2-o38lb security audit).
 *
 * Standalone node-runnable suite — no external test framework. Each
 * test logs PASS / FAIL; the process exits 0 only when every test
 * passes. Wire into `package.json` via `test:script-policy`.
 */

'use strict';

const path = require('path');
const assert = require('assert');
const {
  enforcePolicy,
  DEFAULT_OUT_ROOT,
  DEFAULT_HTML_ROOTS,
  OPT_IN_VAR,
  IMPL_ROOT,
  REPO_ROOT,
} = require('./_path-policy.cjs');

let failed = 0;

function it(label, f) {
  // Reset the opt-in env var around each test so they're hermetic.
  const prior = process.env[OPT_IN_VAR];
  delete process.env[OPT_IN_VAR];
  try {
    f();
    console.log(`  PASS  ${label}`);
  } catch (err) {
    failed++;
    console.error(`  FAIL  ${label}`);
    console.error(`        ${err.message || err}`);
  } finally {
    if (prior == null) delete process.env[OPT_IN_VAR];
    else process.env[OPT_IN_VAR] = prior;
  }
}

console.log('path-policy tests (rf2-o38lb)');

it('accepts a path inside the default out root', () => {
  const out = path.join(DEFAULT_OUT_ROOT, 'browser-test');
  const result = enforcePolicy('BROWSER_TEST_ROOT', out, {
    allowedRoots: [DEFAULT_OUT_ROOT],
  });
  assert.strictEqual(result, path.resolve(out));
});

it('accepts the out root itself', () => {
  const result = enforcePolicy('BROWSER_TEST_ROOT', DEFAULT_OUT_ROOT, {
    allowedRoots: [DEFAULT_OUT_ROOT],
  });
  assert.strictEqual(result, path.resolve(DEFAULT_OUT_ROOT));
});

it('rejects a sibling-of-out path', () => {
  const sibling = path.join(IMPL_ROOT, 'not-out');
  assert.throws(
    () =>
      enforcePolicy('BROWSER_TEST_ROOT', sibling, {
        allowedRoots: [DEFAULT_OUT_ROOT],
      }),
    /outside the approved roots/,
  );
});

it('rejects an absolute path elsewhere on the filesystem', () => {
  // Use an absolute path that is clearly outside the repo. On Windows
  // this looks like 'C:\\tmp\\evil'; on POSIX '/tmp/evil'. Either way
  // it is outside DEFAULT_OUT_ROOT.
  const elsewhere = process.platform === 'win32' ? 'C:\\tmp\\evil' : '/tmp/evil';
  assert.throws(
    () =>
      enforcePolicy('STORY_BUILD_OUTPUT_DIR', elsewhere, {
        allowedRoots: [DEFAULT_OUT_ROOT],
      }),
    /outside the approved roots/,
  );
});

it('rejects path-traversal attempts', () => {
  const traversal = path.join(DEFAULT_OUT_ROOT, '..', '..', 'etc');
  assert.throws(
    () =>
      enforcePolicy('STORY_BUILD_OUTPUT_DIR', traversal, {
        allowedRoots: [DEFAULT_OUT_ROOT],
      }),
    /outside the approved roots/,
  );
});

it('rejects empty path', () => {
  assert.throws(
    () =>
      enforcePolicy('BROWSER_TEST_ROOT', '', {
        allowedRoots: [DEFAULT_OUT_ROOT],
      }),
    /empty path/,
  );
});

it('the opt-in env var lets an out-of-tree path through', () => {
  process.env[OPT_IN_VAR] = '1';
  try {
    const elsewhere =
      process.platform === 'win32' ? 'C:\\tmp\\downstream-out' : '/tmp/downstream-out';
    const result = enforcePolicy('STORY_BUILD_OUTPUT_DIR', elsewhere, {
      allowedRoots: [DEFAULT_OUT_ROOT],
    });
    assert.strictEqual(result, path.resolve(elsewhere));
  } finally {
    delete process.env[OPT_IN_VAR];
  }
});

it("the opt-in env var doesn't fire on 'false' / '0' / unset", () => {
  for (const v of ['false', '0', 'no', 'off']) {
    process.env[OPT_IN_VAR] = v;
    try {
      const elsewhere = process.platform === 'win32' ? 'C:\\tmp\\x' : '/tmp/x';
      assert.throws(
        () =>
          enforcePolicy('STORY_BUILD_OUTPUT_DIR', elsewhere, {
            allowedRoots: [DEFAULT_OUT_ROOT],
          }),
        /outside the approved roots/,
        `OPT_IN_VAR=${v} should not enable opt-in`,
      );
    } finally {
      delete process.env[OPT_IN_VAR];
    }
  }
});

it('accepts paths under multiple allowed roots', () => {
  // STORY_BUILD_INDEX_HTML default policy (rf2-p8f2s): under <repo>/examples,
  // <repo>/tools, OR <repo>/implementation.
  const examplesPath = path.join(REPO_ROOT, 'examples', 'reagent', 'foo.html');
  const result1 = enforcePolicy('STORY_BUILD_INDEX_HTML', examplesPath, {
    allowedRoots: DEFAULT_HTML_ROOTS,
  });
  assert.strictEqual(result1, path.resolve(examplesPath));

  const implPath = path.join(IMPL_ROOT, 'foo.html');
  const result2 = enforcePolicy('STORY_BUILD_INDEX_HTML', implPath, {
    allowedRoots: DEFAULT_HTML_ROOTS,
  });
  assert.strictEqual(result2, path.resolve(implPath));

  const toolsPath = path.join(
    REPO_ROOT,
    'tools',
    'story',
    'testbeds',
    'counter_with_stories',
    'story_static.index.html',
  );
  const result3 = enforcePolicy('STORY_BUILD_INDEX_HTML', toolsPath, {
    allowedRoots: DEFAULT_HTML_ROOTS,
  });
  assert.strictEqual(result3, path.resolve(toolsPath));
});

if (failed > 0) {
  console.error(`\n${failed} test(s) failed.`);
  process.exit(1);
} else {
  console.log('\nAll path-policy tests passed.');
}
