// Unit tests for `lib/exec-safety.cjs`.
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

const {
  resolveTrustedExe,
  safeUnlinkInside,
  safeReadFileInside,
} = require('../lib/exec-safety.cjs');

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

// A realpath FAILURE (as opposed to a clean resolution) used to fall
// back to the raw, unresolved candidate path
// (`realpathSyncOrNull(candidate) || candidate`) — trusting exactly the
// path this module exists to verify. On Windows, `fs.realpathSync` is
// known to throw on certain reparse points (App-Execution-Alias stubs
// under `%LOCALAPPDATA%\Microsoft\WindowsApps`) that `fs.statSync` sees
// as an ordinary file — so this isn't a hypothetical failure mode. These
// two tests monkeypatch `fs.realpathSync` (restored in `finally`) to
// simulate that failure on a specific candidate, independent of the
// actual host OS/filesystem (rf2-6i2yi4 finding 6).

test('resolveTrustedExe: a realpath failure on a candidate is rejected (not trusted unresolved) — falls through to the next candidate', () => {
  const workspace = freshTmpDir('realpath-fail-workspace');
  const unverifiableDir = freshTmpDir('realpath-fail-unverifiable');
  const trustedDir = freshTmpDir('realpath-fail-trusted');
  const realRealpathSync = fs.realpathSync;
  try {
    // First PATH dir: a candidate that EXISTS (passes statSync) but
    // whose realpath will be made to throw.
    const unverifiableExe = path.join(unverifiableDir, 'mytool');
    fs.writeFileSync(unverifiableExe, '#!/bin/sh\necho unverifiable\n', { mode: 0o755 });
    // Second PATH dir: a genuine, verifiable, outside-workspace binary.
    const trustedExeFile = path.join(trustedDir, 'mytool');
    fs.writeFileSync(trustedExeFile, '#!/bin/sh\necho trusted\n', { mode: 0o755 });
    const trustedExeReal = realRealpathSync(trustedExeFile);

    fs.realpathSync = function patchedRealpathSync(p, ...rest) {
      if (path.resolve(p) === path.resolve(unverifiableExe)) {
        const e = new Error('simulated realpath failure (e.g. reparse point)');
        e.code = 'UNKNOWN';
        throw e;
      }
      return realRealpathSync.call(fs, p, ...rest);
    };

    const env = { PATH: [unverifiableDir, trustedDir].join(path.delimiter) };
    const resolved = resolveTrustedExe('mytool', {
      workspaceRoot: workspace,
      env,
      platform: 'linux',
    });
    assert.equal(
      resolved,
      trustedExeReal,
      'must skip the unverifiable candidate (NOT return its raw unresolved ' +
        'path) and resolve the next, verifiable candidate',
    );
    assert.notEqual(
      resolved,
      unverifiableExe,
      'must never return the raw, unresolved candidate path',
    );
  } finally {
    fs.realpathSync = realRealpathSync;
    rmrf(workspace);
    rmrf(unverifiableDir);
    rmrf(trustedDir);
  }
});

test('resolveTrustedExe: throws (does not silently trust an unresolved candidate) when every candidate is realpath-unverifiable', () => {
  const workspace = freshTmpDir('realpath-fail-only-workspace');
  const outsideDir = freshTmpDir('realpath-fail-only-outside');
  const realRealpathSync = fs.realpathSync;
  try {
    const exe = path.join(outsideDir, 'mytool');
    fs.writeFileSync(exe, '#!/bin/sh\necho x\n', { mode: 0o755 });

    fs.realpathSync = function patchedRealpathSync(p, ...rest) {
      if (path.resolve(p) === path.resolve(exe)) {
        throw new Error('simulated realpath failure');
      }
      return realRealpathSync.call(fs, p, ...rest);
    };

    assert.throws(
      () =>
        resolveTrustedExe('mytool', {
          workspaceRoot: workspace,
          env: { PATH: outsideDir },
          platform: 'linux',
        }),
      (err) => {
        assert.match(err.message, /unverifiable/i);
        return true;
      },
      'must throw rather than falling back to the raw unresolved candidate ' +
        '(the pre-fix behaviour)',
    );
  } finally {
    fs.realpathSync = realRealpathSync;
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

// ---------------------------------------------------------------------
// safeReadFileInside
//
// The read-side counterpart to safeUnlinkInside: a port-file candidate
// the cleanup step refused to UNLINK (because its realpath escapes the
// allowed root) must ALSO be refused on READ — closing the
// "refuse-delete-but-trust-read" split. Both helpers share the same
// `resolveContainedLeaf` containment check, so a candidate that throws
// from safeUnlinkInside throws identically from safeReadFileInside.
// ---------------------------------------------------------------------

test('safeReadFileInside: reads file inside allowed root', () => {
  const root = freshTmpDir('read-ok');
  try {
    const target = path.join(root, 'nrepl.port');
    fs.writeFileSync(target, '54321');
    const contents = safeReadFileInside(target, root);
    assert.equal(contents, '54321');
  } finally {
    rmrf(root);
  }
});

test('safeReadFileInside: returns null when file does not exist', () => {
  const root = freshTmpDir('read-missing');
  try {
    const target = path.join(root, 'never-existed.port');
    assert.equal(safeReadFileInside(target, root), null);
  } finally {
    rmrf(root);
  }
});

test('safeReadFileInside: honors an encoding-string option', () => {
  const root = freshTmpDir('read-encoding');
  try {
    const target = path.join(root, 'nrepl.port');
    fs.writeFileSync(target, 'abc');
    const buf = safeReadFileInside(target, root, null); // null -> default utf8
    assert.equal(buf, 'abc');
    const raw = safeReadFileInside(target, root, { encoding: null });
    assert.equal(Buffer.isBuffer(raw), true, 'encoding:null returns a Buffer');
    assert.equal(raw.toString('utf8'), 'abc');
  } finally {
    rmrf(root);
  }
});

test('safeReadFileInside: rejects when realpath escapes allowed root (symlinked leaf)', { skip: process.platform === 'win32' }, () => {
  // The hostile read shape: a leaf `nrepl.port` inside the allowed
  // root that is itself a symlink to an EXTERNAL port file. A naive
  // `fs.readFileSync` would follow the link and trust the external
  // port; safeReadFileInside must refuse, the same way safeUnlinkInside
  // refuses to delete it.
  const root = freshTmpDir('read-escape-leaf');
  const outsideDir = freshTmpDir('read-escape-outside');
  try {
    const externalPort = path.join(outsideDir, 'nrepl.port');
    fs.writeFileSync(externalPort, '9999'); // a stale EXTERNAL runtime's port
    const candidate = path.join(root, 'nrepl.port');
    const linked = trySymlink(externalPort, candidate);
    if (!linked) return; // platform/permissions — soft-skip

    assert.throws(
      () => safeReadFileInside(candidate, root),
      /symlink-escape/,
    );
  } finally {
    rmrf(root);
    rmrf(outsideDir);
  }
});

test('safeReadFileInside: rejects when parent dir is a symlink escaping root', { skip: process.platform === 'win32' }, () => {
  // The orchestrator's load-bearing case: `<root>/.shadow-cljs` is a
  // symlink to an external dir carrying a stale `nrepl.port`. The
  // candidate path APPEARS to live under the allowed root, but realpath
  // resolves the parent symlink to outside it. The read must refuse —
  // otherwise the runner trusts an external runtime's port.
  const root = freshTmpDir('read-escape-parent');
  const outsideDir = freshTmpDir('read-escape-parent-outside');
  try {
    const realSide = path.join(outsideDir, 'shadow-cljs');
    fs.mkdirSync(realSide);
    const externalPort = path.join(realSide, 'nrepl.port');
    fs.writeFileSync(externalPort, '9999');

    const symlinkedParent = path.join(root, '.shadow-cljs');
    const linked = trySymlink(realSide, symlinkedParent);
    if (!linked) return; // soft-skip

    const candidate = path.join(symlinkedParent, 'nrepl.port');
    assert.throws(
      () => safeReadFileInside(candidate, root),
      /symlink-escape/,
    );
  } finally {
    rmrf(root);
    rmrf(outsideDir);
  }
});

test('safeReadFileInside: a candidate refused by safeUnlinkInside is also refused on read (rf2-khav7l parity)', { skip: process.platform === 'win32' }, () => {
  // The read/unlink parity contract: prove the SAME escaped candidate that
  // safeUnlinkInside refuses to delete is ALSO refused by
  // safeReadFileInside. A future cleanup refactor cannot reintroduce the
  // "refuse delete but trust read" split as long as this parity holds.
  const root = freshTmpDir('read-unlink-parity');
  const outsideDir = freshTmpDir('read-unlink-parity-outside');
  try {
    const realSide = path.join(outsideDir, 'shadow-cljs');
    fs.mkdirSync(realSide);
    const externalPort = path.join(realSide, 'nrepl.port');
    fs.writeFileSync(externalPort, '9999');

    const symlinkedParent = path.join(root, '.shadow-cljs');
    const linked = trySymlink(realSide, symlinkedParent);
    if (!linked) return; // soft-skip

    const candidate = path.join(symlinkedParent, 'nrepl.port');
    assert.throws(() => safeUnlinkInside(candidate, root), /symlink-escape/);
    assert.throws(() => safeReadFileInside(candidate, root), /symlink-escape/);
    // The external port file MUST still exist (neither op touched it).
    assert.equal(fs.existsSync(externalPort), true);
  } finally {
    rmrf(root);
    rmrf(outsideDir);
  }
});

test('safeReadFileInside: rejects empty / missing inputs', () => {
  const root = freshTmpDir('read-bad-inputs');
  try {
    assert.throws(() => safeReadFileInside('', root), /candidatePath/);
    assert.throws(() => safeReadFileInside('/whatever', ''), /allowedRoot/);
    assert.throws(
      () => safeReadFileInside('/whatever', '/this/path/does/not/exist/anywhere'),
      /does not exist/,
    );
  } finally {
    rmrf(root);
  }
});
