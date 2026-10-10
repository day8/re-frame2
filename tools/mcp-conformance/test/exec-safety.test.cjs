// Unit tests for `lib/exec-safety.cjs`.
//
//   1. `resolveTrustedExe` returns the realpath of the first PATH candidate
//      that resolves OUTSIDE the workspace root, and throws when none does
//      (the command-hijack accident class). The workspace doubles as the
//      compromised PATH entry, so no real binary is needed, and the platform
//      parameter drives both the POSIX and the PATHEXT walks.
//
//   2. `safeUnlinkInside` refuses any candidate whose realpath (or, for a
//      missing leaf, whose realpath'd parent plus basename) escapes the
//      allowed root.
//
// `safeReadFileInside` shares that containment check; its refusals and its
// missing-file result are pinned through its one caller, `readPortFile`, in
// `port-file-escape.test.cjs`.
//
// Symlink creation needs elevated rights on Windows, so the symlink cases
// skip there.

'use strict';

const test = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const os = require('node:os');
const path = require('node:path');

const { resolveTrustedExe, safeUnlinkInside } = require('../lib/exec-safety.cjs');

// Realpath up-front: on macOS `os.tmpdir()` is itself a symlink.
function withTmpDirs(labels, fn) {
  const dirs = labels.map((label) =>
    fs.realpathSync(fs.mkdtempSync(path.join(os.tmpdir(), `rf2-33vvc-${label}-`))));
  try {
    fn(...dirs);
  } finally {
    for (const dir of dirs) fs.rmSync(dir, { recursive: true, force: true });
  }
}

function writeExe(file) {
  fs.writeFileSync(file, '#!/bin/sh\necho hello\n', { mode: 0o755 });
  return file;
}

function resolveOnLinux(name, workspace, dirs) {
  return resolveTrustedExe(name, {
    workspaceRoot: workspace,
    env: { PATH: dirs.join(path.delimiter) },
    platform: 'linux',
  });
}

// ---- resolveTrustedExe ------------------------------------------------------

test('resolveTrustedExe skips a workspace candidate earlier on PATH and returns the outside one (posix)', () => {
  withTmpDirs(['workspace', 'outside'], (workspace, outside) => {
    writeExe(path.join(workspace, 'mytool'));
    const trusted = writeExe(path.join(outside, 'mytool'));
    assert.equal(resolveOnLinux('mytool', workspace, [workspace, outside]), fs.realpathSync(trusted));
  });
});

test('resolveTrustedExe throws when every candidate resolves inside the workspace', () => {
  withTmpDirs(['hijack-only'], (workspace) => {
    writeExe(path.join(workspace, 'mytool'));
    assert.throws(() => resolveOnLinux('mytool', workspace, [workspace]), /command-hijack accident-gating/);
  });
});

test('resolveTrustedExe walks PATHEXT on the win32 platform', () => {
  withTmpDirs(['win32-workspace', 'win32-outside'], (workspace, outside) => {
    // Only `mytool.CMD` exists on disk.
    const trusted = path.join(outside, 'mytool.CMD');
    fs.writeFileSync(trusted, '@echo hello\n');
    const resolved = resolveTrustedExe('mytool', {
      workspaceRoot: workspace,
      env: { PATH: outside, PATHEXT: '.COM;.EXE;.BAT;.CMD' },
      platform: 'win32',
    });
    assert.equal(resolved, fs.realpathSync(trusted));
  });
});

test('resolveTrustedExe follows a symlink outside the workspace to a target inside it, and refuses', { skip: process.platform === 'win32' }, () => {
  withTmpDirs(['symlink-workspace', 'symlink-outside'], (workspace, outside) => {
    fs.symlinkSync(writeExe(path.join(workspace, 'realtool')), path.join(outside, 'mytool'));
    assert.throws(() => resolveOnLinux('mytool', workspace, [outside]), /workspace/);
  });
});

// Windows `fs.realpathSync` throws on some reparse points (App-Execution-Alias
// stubs under `%LOCALAPPDATA%\Microsoft\WindowsApps`) that `fs.statSync` sees
// as ordinary files. Such a candidate is unverifiable, never trusted raw.
test('resolveTrustedExe skips a candidate whose realpath fails and resolves the next one', () => {
  withTmpDirs(['realpath-fail-workspace', 'realpath-fail-unverifiable', 'realpath-fail-trusted'], (workspace, unverifiableDir, trustedDir) => {
    const unverifiable = writeExe(path.join(unverifiableDir, 'mytool'));
    const trustedReal = fs.realpathSync(writeExe(path.join(trustedDir, 'mytool')));
    const realRealpathSync = fs.realpathSync;
    fs.realpathSync = function patchedRealpathSync(p, ...rest) {
      if (path.resolve(p) === path.resolve(unverifiable)) throw new Error('simulated realpath failure (e.g. reparse point)');
      return realRealpathSync.call(fs, p, ...rest);
    };
    try {
      assert.equal(resolveOnLinux('mytool', workspace, [unverifiableDir, trustedDir]), trustedReal);
    } finally {
      fs.realpathSync = realRealpathSync;
    }
  });
});

// ---- safeUnlinkInside -------------------------------------------------------

test('safeUnlinkInside unlinks a file inside the allowed root', () => {
  withTmpDirs(['unlink-ok'], (root) => {
    const target = path.join(root, 'file.txt');
    fs.writeFileSync(target, 'contents');
    assert.deepEqual([safeUnlinkInside(target, root), fs.existsSync(target)], [true, false]);
  });
});

test('safeUnlinkInside refuses a leaf symlinked outside the root', { skip: process.platform === 'win32' }, () => {
  withTmpDirs(['unlink-escape-leaf', 'unlink-escape-outside'], (root, outside) => {
    const outsideFile = path.join(outside, 'sensitive.txt');
    fs.writeFileSync(outsideFile, 'do not delete');
    const candidate = path.join(root, 'innocent-looking.txt');
    fs.symlinkSync(outsideFile, candidate);
    assert.throws(() => safeUnlinkInside(candidate, root), /symlink-escape/);
  });
});

// `root/.shadow-cljs` linked outside the root: unlinking through it would
// delete the real file on the far side.
test('safeUnlinkInside refuses a candidate under a parent symlinked outside the root', { skip: process.platform === 'win32' }, () => {
  withTmpDirs(['unlink-escape-parent', 'unlink-escape-parent-outside'], (root, outside) => {
    const realSide = path.join(outside, 'shadow-cljs');
    fs.mkdirSync(realSide);
    const sensitiveFile = path.join(realSide, 'nrepl.port');
    fs.writeFileSync(sensitiveFile, 'arbitrary file');
    fs.symlinkSync(realSide, path.join(root, '.shadow-cljs'));
    assert.throws(() => safeUnlinkInside(path.join(root, '.shadow-cljs', 'nrepl.port'), root), /symlink-escape/);
    assert.equal(fs.existsSync(sensitiveFile), true);
  });
});

// A missing leaf takes the parent-realpath path, which must not pass an
// escaped parent through as a no-op.
test('safeUnlinkInside refuses an escaped parent even when the leaf does not exist', { skip: process.platform === 'win32' }, () => {
  withTmpDirs(['unlink-noop-escape-parent', 'unlink-noop-escape-parent-outside'], (root, outside) => {
    const realSide = path.join(outside, 'shadow-cljs');
    fs.mkdirSync(realSide);
    fs.symlinkSync(realSide, path.join(root, '.shadow-cljs'));
    assert.throws(() => safeUnlinkInside(path.join(root, '.shadow-cljs', 'nrepl.port'), root), /symlink-escape/);
  });
});
