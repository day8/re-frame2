// The hermetic orchestrator's port-file poller. Stale port files are wiped
// through `safeUnlinkInside`, which refuses a candidate (or its parent)
// symlinked outside FIXTURE_DIR; `readPortFile` applies the same
// containment on the READ side, so a stale `nrepl.port` behind such a link
// cannot satisfy the port-file wait and steer the inner tests at an
// unrelated runtime.
//
// The orchestrator is required as a module: its auto-run is guarded behind
// `require.main === module`.

'use strict';

const test = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const os = require('node:os');
const path = require('node:path');

const { readPortFile } = require(
  path.join(__dirname, '..', 'scripts', 'run-re-frame2-pair-live-hermetic-suite.cjs'),
);

// Realpath up-front: on macOS `os.tmpdir()` is itself a symlink.
function withTmpDirs(fn) {
  const dirs = ['fixture', 'outside'].map((label) =>
    fs.realpathSync(fs.mkdtempSync(path.join(os.tmpdir(), `rf2-khav7l-${label}-`))));
  try {
    fn(...dirs);
  } finally {
    for (const dir of dirs) fs.rmSync(dir, { recursive: true, force: true });
  }
}

// Symlink creation needs elevated rights on Windows.
test('readPortFile refuses an external port file behind a symlinked parent or leaf', { skip: process.platform === 'win32' }, () => {
  withTmpDirs((fixture, outside) => {
    const realShadow = path.join(outside, 'shadow-cljs');
    fs.mkdirSync(realShadow);
    const externalPort = path.join(realShadow, 'nrepl.port');
    fs.writeFileSync(externalPort, '9999');
    fs.symlinkSync(realShadow, path.join(fixture, '.shadow-cljs'), 'dir');
    fs.symlinkSync(externalPort, path.join(fixture, 'nrepl.port'));
    for (const candidate of [path.join(fixture, '.shadow-cljs', 'nrepl.port'), path.join(fixture, 'nrepl.port')]) {
      assert.throws(() => readPortFile([candidate], fixture), /must not be trusted as the live nREPL source/, candidate);
    }
  });
});

test('readPortFile skips a candidate it cannot read yet rather than calling it an escape', () => {
  withTmpDirs((fixture) => {
    // A directory where the file belongs reads as EISDIR, the same benign
    // class as a Windows lock while shadow-cljs writes the file.
    const shadowPort = path.join(fixture, '.shadow-cljs', 'nrepl.port');
    const rootPort = path.join(fixture, '.nrepl-port');
    fs.mkdirSync(shadowPort, { recursive: true });
    assert.equal(readPortFile([shadowPort], fixture), null);
    fs.writeFileSync(rootPort, '4711');
    assert.deepEqual(readPortFile([shadowPort, rootPort], fixture), { port: 4711, source: rootPort });
  });
});

test('readPortFile returns null until a candidate exists, skips a missing one, and reads the first present one', () => {
  withTmpDirs((fixture) => {
    const shadowPort = path.join(fixture, '.shadow-cljs', 'nrepl.port');
    const rootPort = path.join(fixture, '.nrepl-port');
    assert.equal(readPortFile([shadowPort], fixture), null);
    fs.writeFileSync(rootPort, '4711');
    assert.deepEqual(readPortFile([shadowPort, rootPort], fixture), { port: 4711, source: rootPort });
    fs.mkdirSync(path.dirname(shadowPort));
    fs.writeFileSync(shadowPort, '54321\n');
    assert.deepEqual(readPortFile([shadowPort, rootPort], fixture), { port: 54321, source: shadowPort });
  });
});
