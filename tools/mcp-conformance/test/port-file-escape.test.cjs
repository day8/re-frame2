// Call-site regression for the hermetic orchestrator's port-file poller.
//
// Uses Node's built-in `node:test` (same posture as
// `exec-safety.test.cjs` / `hermetic-setup-timeout.test.cjs` — no extra
// dev-dependency).
//
// ## The contract this pins
//
// `scripts/run-re-frame2-pair-live-hermetic-suite.cjs` wipes stale
// nREPL port files through `safeUnlinkInside`, which refuses (throws)
// when a candidate (or its parent dir) is a symlink whose realpath
// escapes FIXTURE_DIR. The poller's `readPortFile` applies the SAME
// containment check on the READ side, so a stale `nrepl.port` living
// behind a symlinked `.shadow-cljs` (or any escaped candidate) cannot
// satisfy the port-file wait and steer the inner conformance tests at an
// UNRELATED runtime. Both the delete and the read paths reject the same
// escape — closing the "refuse delete but trust read" split that would
// otherwise undermine the accident-gating the runner exists to provide.
//
// ## What this test drives
//
// It requires the orchestrator AS A MODULE (the auto-run is guarded
// behind `require.main === module`, so requiring it does NOT boot
// shadow-cljs / Chromium) and calls the exported `readPortFile` directly
// against a temp fixture whose `.shadow-cljs` is symlinked to an external
// dir carrying a stale `nrepl.port`. It asserts:
//
//   1. `readPortFile` THROWS on the escaped candidate (does NOT return
//      the external port) — the read path rejects the same escape the
//      cleanup step refused to delete.
//   2. the thrown error is classified as a containment escape by
//      `isContainmentEscape` — so the cleanup loop's "abort on escape"
//      branch (which discriminates escape from benign EACCES/EBUSY) is
//      driven by the same signal.
//   3. an in-fixture `nrepl.port` (no symlink) IS read normally — the
//      fix doesn't break the happy path.
//
// Symlink creation requires elevated rights on Windows; that arm
// soft-skips there (matching `exec-safety.test.cjs`).

'use strict';

const test = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const os = require('node:os');
const path = require('node:path');

const ORCH = path.join(
  __dirname,
  '..',
  'scripts',
  'run-re-frame2-pair-live-hermetic-suite.cjs',
);

// Requiring is side-effect-free: the orchestrator guards its auto-run
// behind `require.main === module`, so no shadow-cljs / Chromium spawns.
const { readPortFile, isContainmentEscape } = require(ORCH);

function freshTmpDir(label) {
  const base = fs.mkdtempSync(path.join(os.tmpdir(), `rf2-khav7l-${label}-`));
  // Realpath up-front: on macOS `os.tmpdir()` is itself a symlink.
  return fs.realpathSync(base);
}

function rmrf(p) {
  try {
    fs.rmSync(p, { recursive: true, force: true });
  } catch {
    // best-effort
  }
}

function trySymlink(target, link, type) {
  try {
    fs.symlinkSync(target, link, type);
    return link;
  } catch {
    return null;
  }
}

test('readPortFile: refuses an external port file behind a symlinked .shadow-cljs (rf2-khav7l)', { skip: process.platform === 'win32' }, () => {
  const fixture = freshTmpDir('fixture');
  const outsideDir = freshTmpDir('outside');
  try {
    // The hostile shape: `<fixture>/.shadow-cljs` is a symlink to an
    // external dir that carries a stale port file pointing at an
    // UNRELATED runtime.
    const realShadow = path.join(outsideDir, 'shadow-cljs');
    fs.mkdirSync(realShadow);
    const externalPort = path.join(realShadow, 'nrepl.port');
    fs.writeFileSync(externalPort, '9999');

    const symlinkedParent = path.join(fixture, '.shadow-cljs');
    const linked = trySymlink(realShadow, symlinkedParent, 'dir');
    if (!linked) return; // platform/permissions — soft-skip

    const candidate = path.join(symlinkedParent, 'nrepl.port');
    // Drive the EXACT poller function with this candidate as its first
    // (and only) candidate + the fixture as the allowed root.
    let thrown = null;
    assert.throws(
      () => readPortFile([candidate], fixture),
      (err) => {
        thrown = err;
        // Must name the candidate and the containment-escape rationale.
        assert.match(err.message, /khav7l/);
        return true;
      },
    );
    // The poller did NOT return the external port — it threw.
    // The cleanup loop's escape-vs-benign discriminator must classify
    // this as a containment escape (so it ABORTS, not log-and-continues).
    assert.equal(
      isContainmentEscape(thrown),
      true,
      'an escaped candidate must classify as a containment escape',
    );
    // And the external file is untouched — we never read or deleted it.
    assert.equal(fs.existsSync(externalPort), true);
  } finally {
    rmrf(fixture);
    rmrf(outsideDir);
  }
});

test('readPortFile: refuses an external port file behind a symlinked LEAF (rf2-khav7l)', { skip: process.platform === 'win32' }, () => {
  const fixture = freshTmpDir('fixture-leaf');
  const outsideDir = freshTmpDir('outside-leaf');
  try {
    const externalPort = path.join(outsideDir, 'nrepl.port');
    fs.writeFileSync(externalPort, '9999');
    // The candidate file itself is a symlink to the external port.
    const candidate = path.join(fixture, 'nrepl.port');
    const linked = trySymlink(externalPort, candidate);
    if (!linked) return; // soft-skip

    assert.throws(
      () => readPortFile([candidate], fixture),
      /khav7l/,
    );
    assert.equal(fs.existsSync(externalPort), true);
  } finally {
    rmrf(fixture);
    rmrf(outsideDir);
  }
});

test('readPortFile: reads a genuine in-fixture port file (happy path unbroken)', () => {
  const fixture = freshTmpDir('fixture-ok');
  try {
    const shadowDir = path.join(fixture, '.shadow-cljs');
    fs.mkdirSync(shadowDir);
    const portFile = path.join(shadowDir, 'nrepl.port');
    fs.writeFileSync(portFile, '54321\n');

    const hit = readPortFile([portFile], fixture);
    assert.notEqual(hit, null);
    assert.equal(hit.port, 54321);
    assert.equal(hit.source, portFile);
  } finally {
    rmrf(fixture);
  }
});

test('readPortFile: skips a missing candidate and falls through to a present one', () => {
  const fixture = freshTmpDir('fixture-fallthrough');
  try {
    const missing = path.join(fixture, '.shadow-cljs', 'nrepl.port');
    const present = path.join(fixture, '.nrepl-port');
    fs.writeFileSync(present, '4711');

    // First candidate is absent (parent dir doesn't even exist); the
    // poller must skip it and read the present one — NOT throw.
    const hit = readPortFile([missing, present], fixture);
    assert.notEqual(hit, null);
    assert.equal(hit.port, 4711);
    assert.equal(hit.source, present);
  } finally {
    rmrf(fixture);
  }
});

test('readPortFile: returns null when no candidate is present yet', () => {
  const fixture = freshTmpDir('fixture-none');
  try {
    const missing = path.join(fixture, '.shadow-cljs', 'nrepl.port');
    assert.equal(readPortFile([missing], fixture), null);
  } finally {
    rmrf(fixture);
  }
});
