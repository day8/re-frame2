#!/usr/bin/env node
/*
 * Tests for `_path-policy.cjs`.
 * Discovered by `npm run test:scripts`.
 */

'use strict';

const path = require('path');
const fs = require('fs');
const os = require('os');
const assert = require('assert');
const {
  enforcePolicy,
  DEFAULT_OUT_ROOT,
  DEFAULT_HTML_ROOTS,
  OPT_IN_VAR,
  IMPL_ROOT,
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

console.log('path-policy tests');

it('accepts the out root itself and a path inside it', () => {
  for (const p of [DEFAULT_OUT_ROOT, path.join(DEFAULT_OUT_ROOT, 'browser-test')]) {
    assert.strictEqual(enforcePolicy('BROWSER_TEST_ROOT', p, { allowedRoots: [DEFAULT_OUT_ROOT] }), path.resolve(p));
  }
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

// ---- symlink / junction escape --------------------------------------------
//
// A link under an allowed root that resolves OUTSIDE it must be rejected (a
// lexical prefix check would accept it); an in-root link must still pass. Where
// the host refuses to create links (Windows without the privilege) these skip.

function makeSandbox() {
  const base = fs.mkdtempSync(path.join(os.tmpdir(), 'rf2-pathpolicy-'));
  const allowedRoot = path.join(base, 'allowed-root');
  const outside = path.join(base, 'outside-the-boundary');
  fs.mkdirSync(allowedRoot, { recursive: true });
  fs.mkdirSync(outside, { recursive: true });
  // realpath'd to match the policy's own canonicalisation (macOS /var -> /private/var).
  return { base, allowedRoot: fs.realpathSync(allowedRoot), outside: fs.realpathSync(outside) };
}

// A directory symlink, else on Windows a junction; false if the host refuses both.
function trySymlinkDir(target, linkPath) {
  try {
    fs.symlinkSync(target, linkPath, 'dir');
    return true;
  } catch (_) {
    if (process.platform === 'win32') {
      try {
        fs.symlinkSync(target, linkPath, 'junction');
        return true;
      } catch (_) {
        return false;
      }
    }
    return false;
  }
}

it('REJECTS a symlink/junction under an allowed root that targets outside it', () => {
  const { base, allowedRoot, outside } = makeSandbox();
  try {
    // allowed-root/escape-link  ->  ../outside-the-boundary (out of root)
    const link = path.join(allowedRoot, 'escape-link');
    if (!trySymlinkDir(outside, link)) {
      console.log('        (skipped: symlink/junction creation not permitted on this host)');
      return;
    }
    // Lexically inside, but resolves outside; so does a path written through it.
    assert.throws(
      () => enforcePolicy('BROWSER_TEST_ROOT', link, { allowedRoots: [allowedRoot] }),
      /outside the approved roots/,
      'a symlink under the allowed root pointing outside was wrongly accepted',
    );
    assert.throws(
      () =>
        enforcePolicy('STORY_BUILD_INDEX_HTML', path.join(link, 'index.html'), {
          allowedRoots: [allowedRoot],
        }),
      /outside the approved roots/,
      'a path written THROUGH an escaping symlink was wrongly accepted',
    );
  } finally {
    fs.rmSync(base, { recursive: true, force: true });
  }
});

it('ACCEPTS a symlink under an allowed root whose target stays inside it', () => {
  const { base, allowedRoot } = makeSandbox();
  try {
    const realInner = path.join(allowedRoot, 'real-inner');
    fs.mkdirSync(realInner, { recursive: true });
    const link = path.join(allowedRoot, 'inner-link'); // -> allowed-root/real-inner
    if (!trySymlinkDir(realInner, link)) {
      console.log('        (skipped: symlink/junction creation not permitted on this host)');
      return;
    }
    const result = enforcePolicy('STORY_BUILD_OUTPUT_DIR', link, {
      allowedRoots: [allowedRoot],
    });
    assert.strictEqual(result, path.resolve(link));
    // A not-yet-created child under the in-root link passes too.
    const child = path.join(link, 'index.html');
    const childResult = enforcePolicy('STORY_BUILD_INDEX_HTML', child, {
      allowedRoots: [allowedRoot],
    });
    assert.strictEqual(childResult, path.resolve(child));
  } finally {
    fs.rmSync(base, { recursive: true, force: true });
  }
});

it('REJECTS via a PARENT-directory symlink that escapes the allowed root', () => {
  const { base, allowedRoot, outside } = makeSandbox();
  try {
    // The escaping link is an intermediate directory, not the final component.
    const parentLink = path.join(allowedRoot, 'escape-dir');
    if (!trySymlinkDir(outside, parentLink)) {
      console.log('        (skipped: symlink/junction creation not permitted on this host)');
      return;
    }
    const deepChild = path.join(parentLink, 'nested', 'manifest.json');
    assert.throws(
      () =>
        enforcePolicy('STORY_BUILD_OUTPUT_DIR', deepChild, {
          allowedRoots: [allowedRoot],
        }),
      /outside the approved roots/,
      'a path under a parent-directory symlink that escapes was wrongly accepted',
    );
  } finally {
    fs.rmSync(base, { recursive: true, force: true });
  }
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

it('the SAME opt-in env var lets an out-of-tree READ SOURCE through', () => {
  // The opt-in broadens read-source roots too, not only write targets.
  const elsewhere =
    process.platform === 'win32' ? 'C:\\tmp\\downstream\\index.html' : '/tmp/downstream/index.html';
  assert.throws(
    () =>
      enforcePolicy('STORY_BUILD_INDEX_HTML', elsewhere, {
        allowedRoots: DEFAULT_HTML_ROOTS,
      }),
    /outside the approved roots/,
    'an out-of-tree HTML read source must be refused without the opt-in',
  );
  process.env[OPT_IN_VAR] = '1';
  try {
    const result = enforcePolicy('STORY_BUILD_INDEX_HTML', elsewhere, {
      allowedRoots: DEFAULT_HTML_ROOTS,
    });
    assert.strictEqual(result, path.resolve(elsewhere));
  } finally {
    delete process.env[OPT_IN_VAR];
  }
});

it("the opt-in env var doesn't fire on a non-'1' value such as 'false'", () => {
  process.env[OPT_IN_VAR] = 'false';
  const elsewhere = process.platform === 'win32' ? 'C:\\tmp\\x' : '/tmp/x';
  assert.throws(
    () => enforcePolicy('STORY_BUILD_OUTPUT_DIR', elsewhere, { allowedRoots: [DEFAULT_OUT_ROOT] }),
    /outside the approved roots/,
  );
});

it('accepts a path under a later allowed root, not only the first', () => {
  const implPath = path.join(IMPL_ROOT, 'foo.html');
  assert.strictEqual(
    enforcePolicy('STORY_BUILD_INDEX_HTML', implPath, { allowedRoots: DEFAULT_HTML_ROOTS }),
    path.resolve(implPath),
  );
});

if (failed > 0) {
  console.error(`\n${failed} test(s) failed.`);
  process.exit(1);
} else {
  console.log('\nAll path-policy tests passed.');
}
