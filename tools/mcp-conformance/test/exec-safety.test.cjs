// Unit tests for `lib/exec-safety.cjs` (rf2-33vvc).
//
// Uses Node's built-in `node:test` so the harness picks up no extra
// dev-dependency. Runs quiet on success (per docs/quiet-tests.md):
// `node:test` only prints a per-file summary line on green and
// dumps the full failure diff on red.
//
// Two surfaces under test:
//
//   1. `resolveTrustedExe` — must return an absolute path that
//      realpaths to OUTSIDE the workspace root, and must throw when
//      every PATH candidate falls inside the workspace (the exact
//      accident-class flagged by the audit). We drive both POSIX and
//      win32 code paths via the platform parameter; the workspace
//      itself doubles as the "compromised PATH entry" so the test is
//      hermetic — no real binary or temp PATH munging required.
//
//   2. `safeUnlinkInside` — must reject any candidate whose
//      realpath (or, for missing files, whose realpath'd parent
//      directory + basename) escapes the allowed root. Symlink-leaf
//      and symlinked-parent cases both covered. Symlink support is
//      gated on the platform — Windows requires elevated rights for
//      symlinkSync, so we soft-skip there.

'use strict';

const test = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const os = require('node:os');
const path = require('node:path');

const { resolveTrustedExe, safeUnlinkInside } = require('../lib/exec-safety.cjs');

// ---------------------------------------------------------------------
// Test scratch dir
// ---------------------------------------------------------------------

function freshTmpDir(label) {
  const base = fs.mkdtempSync(path.join(os.tmpdir(), `rf2-33vvc-${label}-`));
  // Realpath the tmpdir up-front — on macOS `os.tmpdir()` is
  // `/var/folders/...` which is itself a symlink to `/private/var/...`.
  // Without normalising, downstream comparisons would always fail.
  return fs.realpathSync(base);
}

function rmrf(p) {
  try {
    fs.rmSync(p, { recursive: true, force: true });
  } catch {
    // best-effort
  }
}

// Try to create a symlink; return null on platforms / permission
// configurations where symlinkSync fails (Windows without dev-mode /
// admin). Tests that need symlinks soft-skip when this returns null.
function trySymlink(target, link) {
  try {
    fs.symlinkSync(target, link);
    return link;
  } catch {
    return null;
  }
}

// ---------------------------------------------------------------------
// resolveTrustedExe
// ---------------------------------------------------------------------

test('resolveTrustedExe: returns absolute path outside workspace (posix)', () => {
  // Hermetic setup: build two PATH directories, one inside the
  // workspace and one outside. The outside one carries a real
  // executable file; the function MUST pick that one. Drive with
  // platform='linux' so the empty-extension code path is exercised.
  const workspace = freshTmpDir('workspace');
  const outsideDir = freshTmpDir('outside');
  try {
    // The "trusted" host-side binary lives outside the workspace.
    const trustedExe = path.join(outsideDir, 'mytool');
    fs.writeFileSync(trustedExe, '#!/bin/sh\necho hello\n', { mode: 0o755 });

    // A workspace-local "fake" binary that MUST NOT be picked.
    const fakeWorkspaceExe = path.join(workspace, 'mytool');
    fs.writeFileSync(fakeWorkspaceExe, '#!/bin/sh\necho gotcha\n', { mode: 0o755 });

    // workspace comes FIRST in PATH so a naive implementation would
    // pick it; the helper MUST skip it.
    const env = { PATH: [workspace, outsideDir].join(path.delimiter) };
    const resolved = resolveTrustedExe('mytool', {
      workspaceRoot: workspace,
      env,
      platform: 'linux',
    });
    assert.equal(path.isAbsolute(resolved), true, 'resolved path must be absolute');
    assert.equal(resolved, fs.realpathSync(trustedExe));
    assert.notEqual(
      resolved,
      fs.realpathSync(fakeWorkspaceExe),
      'must not pick the workspace-local candidate',
    );
  } finally {
    rmrf(workspace);
    rmrf(outsideDir);
  }
});

test('resolveTrustedExe: throws when every candidate resolves inside workspace', () => {
  // Setup: PATH carries ONLY the workspace dir. Every candidate
  // resolves inside; the function MUST throw rather than execute a
  // workspace-relative binary.
  const workspace = freshTmpDir('hijack-only');
  try {
    const fake = path.join(workspace, 'mytool');
    fs.writeFileSync(fake, '#!/bin/sh\necho gotcha\n', { mode: 0o755 });

    const env = { PATH: workspace };
    assert.throws(
      () =>
        resolveTrustedExe('mytool', {
          workspaceRoot: workspace,
          env,
          platform: 'linux',
        }),
      (err) => {
        assert.match(err.message, /workspace/);
        assert.match(err.message, /rf2-33vvc/);
        return true;
      },
    );
  } finally {
    rmrf(workspace);
  }
});

test('resolveTrustedExe: throws when name is not on PATH at all', () => {
  const workspace = freshTmpDir('empty-path');
  const outsideDir = freshTmpDir('empty-outside');
  try {
    const env = { PATH: outsideDir };
    assert.throws(
      () =>
        resolveTrustedExe('definitely-not-a-real-binary', {
          workspaceRoot: workspace,
          env,
          platform: 'linux',
        }),
      /could not find/,
    );
  } finally {
    rmrf(workspace);
    rmrf(outsideDir);
  }
});

test('resolveTrustedExe: rejects names containing a path separator', () => {
  const workspace = freshTmpDir('sep-reject');
  try {
    assert.throws(
      () =>
        resolveTrustedExe('foo/bar', {
          workspaceRoot: workspace,
          env: { PATH: workspace },
          platform: 'linux',
        }),
      /path separator/,
    );
    assert.throws(
      () =>
        resolveTrustedExe('foo\\bar', {
          workspaceRoot: workspace,
          env: { PATH: workspace },
          platform: 'linux',
        }),
      /path separator/,
    );
  } finally {
    rmrf(workspace);
  }
});

test('resolveTrustedExe: walks PATHEXT on win32 platform', () => {
  // win32 code path: even on a POSIX host we can exercise the
  // PATHEXT walk by passing platform='win32' explicitly. The
  // candidate file we write carries a `.CMD` extension; the helper
  // must locate it via the PATHEXT-driven extension probe.
  const workspace = freshTmpDir('win32-workspace');
  const outsideDir = freshTmpDir('win32-outside');
  try {
    // Note the `.CMD` extension — bare `mytool` does NOT exist on
    // disk; only `mytool.CMD` does. The PATHEXT walk must catch it.
    const trustedExe = path.join(outsideDir, 'mytool.CMD');
    fs.writeFileSync(trustedExe, '@echo hello\n');

    const env = {
      PATH: outsideDir,
      PATHEXT: '.COM;.EXE;.BAT;.CMD',
    };
    const resolved = resolveTrustedExe('mytool', {
      workspaceRoot: workspace,
      env,
      platform: 'win32',
    });
    assert.equal(resolved, fs.realpathSync(trustedExe));
  } finally {
    rmrf(workspace);
    rmrf(outsideDir);
  }
});

test('resolveTrustedExe: follows symlinks and rejects when target is inside workspace', { skip: process.platform === 'win32' }, () => {
  // Setup: outsideDir contains a symlink `mytool` → workspace/realtool.
  // A naive implementation would pick the symlink and call it
  // "outside the workspace" by string-prefix. realpath-driven check
  // catches the redirection and rejects.
  const workspace = freshTmpDir('symlink-workspace');
  const outsideDir = freshTmpDir('symlink-outside');
  try {
    const realtool = path.join(workspace, 'realtool');
    fs.writeFileSync(realtool, '#!/bin/sh\necho gotcha\n', { mode: 0o755 });
    const symlink = path.join(outsideDir, 'mytool');
    const linked = trySymlink(realtool, symlink);
    if (!linked) return; // platform/permissions can't symlink — soft-skip

    const env = { PATH: outsideDir };
    assert.throws(
      () =>
        resolveTrustedExe('mytool', {
          workspaceRoot: workspace,
          env,
          platform: 'linux',
        }),
      /workspace/,
    );
  } finally {
    rmrf(workspace);
    rmrf(outsideDir);
  }
});

// ---------------------------------------------------------------------
// safeUnlinkInside
// ---------------------------------------------------------------------

test('safeUnlinkInside: unlinks file inside allowed root', () => {
  const root = freshTmpDir('unlink-ok');
  try {
    const target = path.join(root, 'file.txt');
    fs.writeFileSync(target, 'contents');
    assert.equal(fs.existsSync(target), true);
    const removed = safeUnlinkInside(target, root);
    assert.equal(removed, true);
    assert.equal(fs.existsSync(target), false);
  } finally {
    rmrf(root);
  }
});

test('safeUnlinkInside: no-op when file does not exist', () => {
  const root = freshTmpDir('unlink-noop');
  try {
    const target = path.join(root, 'never-existed.txt');
    const removed = safeUnlinkInside(target, root);
    assert.equal(removed, false);
  } finally {
    rmrf(root);
  }
});

test('safeUnlinkInside: rejects when realpath escapes allowed root (symlinked leaf)', { skip: process.platform === 'win32' }, () => {
  // The hostile shape: a leaf file inside the allowed root that is
  // itself a symlink pointing OUTSIDE the root. Naive unlinkSync
  // would happily remove the symlink (Unix unlink only removes the
  // link itself, not the target — but the audit fix is to refuse to
  // touch any path whose realpath escapes the root, which is the
  // stronger property and gates the symlinked-parent case below.)
  const root = freshTmpDir('unlink-escape-leaf');
  const outsideDir = freshTmpDir('unlink-escape-outside');
  try {
    const outsideFile = path.join(outsideDir, 'sensitive.txt');
    fs.writeFileSync(outsideFile, 'do not delete');
    const candidate = path.join(root, 'innocent-looking.txt');
    const linked = trySymlink(outsideFile, candidate);
    if (!linked) return; // platform/permissions — soft-skip

    assert.throws(
      () => safeUnlinkInside(candidate, root),
      /symlink-escape/,
    );
    // The outside file must still exist; the symlink itself may be
    // intact (we refused to touch either).
    assert.equal(fs.existsSync(outsideFile), true);
  } finally {
    rmrf(root);
    rmrf(outsideDir);
  }
});

test('safeUnlinkInside: rejects when parent dir is a symlink escaping root', { skip: process.platform === 'win32' }, () => {
  // The exact accident class flagged by the audit: the candidate
  // path lives at `root/.shadow-cljs/nrepl.port`, but `.shadow-cljs`
  // is itself a symlink whose target is outside the root. The leaf
  // file may not even exist yet — the parent-symlink realpath check
  // catches it before any unlink fires.
  const root = freshTmpDir('unlink-escape-parent');
  const outsideDir = freshTmpDir('unlink-escape-parent-outside');
  try {
    // Set up the "real" .shadow-cljs target outside the root with a
    // file inside it.
    const realSide = path.join(outsideDir, 'shadow-cljs');
    fs.mkdirSync(realSide);
    const sensitiveFile = path.join(realSide, 'nrepl.port');
    fs.writeFileSync(sensitiveFile, 'arbitrary file');

    // Symlink `root/.shadow-cljs` → `outsideDir/shadow-cljs`.
    const symlinkedParent = path.join(root, '.shadow-cljs');
    const linked = trySymlink(realSide, symlinkedParent);
    if (!linked) return; // soft-skip

    const candidate = path.join(symlinkedParent, 'nrepl.port');
    // The candidate appears to live under `root/.shadow-cljs/...`,
    // but realpath resolves the parent symlink, so the resolved
    // path is `outsideDir/shadow-cljs/nrepl.port` — outside root.
    assert.throws(
      () => safeUnlinkInside(candidate, root),
      /symlink-escape/,
    );
    // The sensitive file MUST still exist — the whole point of the fix.
    assert.equal(fs.existsSync(sensitiveFile), true);
  } finally {
    rmrf(root);
    rmrf(outsideDir);
  }
});

test('safeUnlinkInside: rejects parent-symlink even when leaf does not exist', { skip: process.platform === 'win32' }, () => {
  // Variant of the above: the parent is a symlink escaping root AND
  // the leaf file doesn't exist yet. The parent-realpath check
  // still catches it (this is the "no-op when missing" path that
  // must not silently pass through symlink-escaped parents).
  const root = freshTmpDir('unlink-noop-escape-parent');
  const outsideDir = freshTmpDir('unlink-noop-escape-parent-outside');
  try {
    const realSide = path.join(outsideDir, 'shadow-cljs');
    fs.mkdirSync(realSide);
    // Do NOT create the leaf file; the parent-realpath check must
    // still reject the path.
    const symlinkedParent = path.join(root, '.shadow-cljs');
    const linked = trySymlink(realSide, symlinkedParent);
    if (!linked) return;

    const candidate = path.join(symlinkedParent, 'nrepl.port');
    assert.throws(
      () => safeUnlinkInside(candidate, root),
      /symlink-escape/,
    );
  } finally {
    rmrf(root);
    rmrf(outsideDir);
  }
});

test('safeUnlinkInside: rejects empty / missing inputs', () => {
  const root = freshTmpDir('unlink-bad-inputs');
  try {
    assert.throws(() => safeUnlinkInside('', root), /candidatePath/);
    assert.throws(() => safeUnlinkInside('/whatever', ''), /allowedRoot/);
    assert.throws(
      () => safeUnlinkInside('/whatever', '/this/path/does/not/exist/anywhere'),
      /does not exist/,
    );
  } finally {
    rmrf(root);
  }
});
