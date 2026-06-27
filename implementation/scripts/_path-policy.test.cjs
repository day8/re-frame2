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
const fs = require('fs');
const os = require('os');
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

// ---- symlink / junction escape (rf2-l9upzf) ------------------------------
//
// A symlink/junction UNDER an allowed root whose target resolves OUTSIDE
// the approved boundary must be REJECTED — the lexical prefix check alone
// (path.relative) would wrongly accept it, letting the writing scripts
// follow the link and escape. A legitimate in-root symlink (target stays
// inside the allowed root) must still PASS.
//
// Cross-platform: dir symlinks need Developer Mode / admin on Windows;
// when creation isn't permitted we SKIP (not fail) the symlink cases so
// the suite stays green on a locked-down Windows runner while still
// asserting on POSIX (and Windows with the privilege). The escape-
// rejection logic itself is OS-agnostic (it relies on fs.realpathSync,
// which resolves both POSIX symlinks and Windows junctions/symlinks).

// Create a fresh scratch sandbox: an "allowed root" dir, an "outside"
// dir well clear of it, and a per-run temp parent so concurrent test
// runs never collide.
function makeSandbox() {
  const base = fs.mkdtempSync(path.join(os.tmpdir(), 'rf2-pathpolicy-'));
  const allowedRoot = path.join(base, 'allowed-root');
  const outside = path.join(base, 'outside-the-boundary');
  fs.mkdirSync(allowedRoot, { recursive: true });
  fs.mkdirSync(outside, { recursive: true });
  // realpath the allowed root so the policy's canonicalisation of the
  // root (which also realpaths, e.g. macOS /var -> /private/var) lines up
  // with what the test passes in.
  return { base, allowedRoot: fs.realpathSync(allowedRoot), outside: fs.realpathSync(outside) };
}

// Try to create a directory symlink; on Windows fall back to a junction.
// Returns true on success, false if the platform refused (no privilege).
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
    // Lexically `link` is INSIDE allowedRoot, but it resolves OUTSIDE.
    // The policy must reject it (and reject a child path written through
    // it, the actual escape vector the writing scripts hit).
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
    // The link resolves to a path still INSIDE the allowed root, so a
    // legitimate in-root override must still pass.
    const result = enforcePolicy('STORY_BUILD_OUTPUT_DIR', link, {
      allowedRoots: [allowedRoot],
    });
    assert.strictEqual(result, path.resolve(link));
    // And a not-yet-created child written under the in-root link passes
    // too (nonexistent-final-path handling: existing parent realpaths
    // inside the root).
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
    // allowed-root/escape-dir -> outside ; then probe a DEEPER child whose
    // intermediate (escape-dir) is the escaping link. Exercises that the
    // policy canonicalises parent-directory links, not just the final
    // component.
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

it('the opt-in env var lets an out-of-tree WRITE TARGET through', () => {
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

it('the SAME opt-in env var lets an out-of-tree READ SOURCE through', () => {
  // The knob is named for paths, not writes: it must also broaden the
  // read-source roots (STORY_BUILD_INDEX_HTML), not just write targets.
  // Without the opt-in an out-of-tree HTML template is refused; with it
  // set, the same candidate (against the HTML allowed-roots) passes.
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
  const examplesPath = path.join(REPO_ROOT, 'examples', 'core', 'foo.html');
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
