#!/usr/bin/env node
/*
 * Unit test for `.github/scripts/transform-reagent-slim-ns.sh` (rf2-olo8rc).
 *
 * The script performs the reagent-slim publication-time ns rename
 * (re_frame/adapter/reagent_slim.cljs → reagent.cljs, with the
 * `(ns re-frame.adapter.reagent-slim …)` form rewritten to
 * `(ns re-frame.adapter.reagent …)`). It is the ONLY release step where
 * the published artefact diverges from the tested tree, so it has its own
 * abort invariants — this test exercises the success path plus all three
 * abort cases against throwaway fixtures.
 *
 * Pattern mirrors `_changed-surfaces.test.cjs`: spawn the real shell
 * script via `bash`, assert on exit code + stdout/stderr. Wired into
 * `test:script-policy`.
 */

'use strict';

const assert = require('assert/strict');
const { spawnSync } = require('child_process');
const fs = require('fs');
const path = require('path');

const IMPL_ROOT = path.resolve(__dirname, '..');
const REPO_ROOT = path.resolve(IMPL_ROOT, '..');

// The script and the throwaway fixtures are addressed by paths RELATIVE to
// REPO_ROOT, which is handed to `bash` as its `cwd`. Relative POSIX paths
// are the only form every supported Bash flavour accepts unchanged — see
// the `run()` comment for the cross-platform rationale (rf2-6m7pn4).
const SCRIPT_REL = '.github/scripts/transform-reagent-slim-ns.sh';
// Scratch root for fixtures, kept INSIDE the repo (not os.tmpdir()) so a
// repo-relative path reaches it. `.scratch/` is gitignored.
const SCRATCH_ROOT = path.join(REPO_ROOT, '.scratch');

const tests = [];
function test(name, fn) {
  tests.push({ name, fn });
}

// The in-tree ns-declaration form the script keys off, plus a docstring
// line that legitimately mentions the slim artefact (must survive the
// rename — the script rewrites only the `(ns …)` token).
const SLIM_NS_FORM = '(ns re-frame.adapter.reagent-slim';
const SAMPLE_SOURCE = [
  '(ns re-frame.adapter.reagent-slim',
  '  "The day8/reagent-slim adapter — emits the substrate map.',
  '',
  '      (require \'[re-frame.adapter.reagent-slim :as reagent-slim])',
  '      (rf/init! reagent-slim/adapter)"',
  '  (:require [reagent2.core :as r]))',
  '',
  '(def adapter {:id :reagent-slim})',
  '',
].join('\n');

// Build a throwaway adapter dir with the slim source file at the path the
// script expects. The dir is created UNDER the repo (SCRATCH_ROOT) so the
// test can address it with a repo-relative path. Returns { dir, rel, src,
// dst } where `dir` is the absolute path (for fs assertions) and `rel` is
// the forward-slashed path relative to REPO_ROOT (handed to bash).
function makeFixture({ withSource = true, withDest = false, nsForm = SLIM_NS_FORM } = {}) {
  fs.mkdirSync(SCRATCH_ROOT, { recursive: true });
  const dir = fs.mkdtempSync(path.join(SCRATCH_ROOT, 'rf2-slim-ns-'));
  const adapterDir = path.join(dir, 'src', 're_frame', 'adapter');
  fs.mkdirSync(adapterDir, { recursive: true });
  const src = path.join(adapterDir, 'reagent_slim.cljs');
  const dst = path.join(adapterDir, 'reagent.cljs');
  if (withSource) {
    // Swap the ns form if the caller wants the "ns-form-not-found" case.
    const body = nsForm === SLIM_NS_FORM
      ? SAMPLE_SOURCE
      : SAMPLE_SOURCE.replace(SLIM_NS_FORM, nsForm);
    fs.writeFileSync(src, body);
  }
  if (withDest) {
    fs.writeFileSync(dst, '(ns re-frame.adapter.reagent)\n');
  }
  return { dir, rel: relPosix(dir), src, dst };
}

// Forward-slashed path of `abs` relative to REPO_ROOT.
function relPosix(abs) {
  return path.relative(REPO_ROOT, abs).split(path.sep).join('/');
}

function shQuote(s) {
  return `'${String(s).replace(/'/g, `'\\''`)}'`;
}

// Invoke the shell script under `bash` so it runs identically on Windows
// (Git Bash / WSL) and Ubuntu (rf2-6m7pn4).
//
// History — the bug this guards against:
//   - The original form — `spawnSync('bash', [SCRIPT, adapterDir])` with a
//     raw `C:\...` argv — failed on Git Bash (MSYS/MinGW), where the
//     backslashes in the argv path are consumed as shell escapes
//     (rf2-rcepku).
//   - The follow-up forward-slashed `C:/...` form fixed Git Bash but still
//     failed when `bash` resolves to WSL's `C:\Windows\system32\bash.exe`
//     (the default on a stock Windows box): WSL bash treats a
//     `C:/Users/...` argument as a literal *relative* path — it mounts
//     Windows drives at `/mnt/c/...`, not `C:/...` — so it reported
//     `…transform-reagent-slim-ns.sh: No such file or directory` and all
//     four cases failed (rf2-6m7pn4).
//
// Why no in-shell path conversion: a Windows drive path is not portable
// across the supported Bash flavours (Git Bash mounts `C:` at `/c`, WSL at
// `/mnt/c`, Linux has no drive letters), and converting in-shell is a dead
// end on WSL — `cygpath`/`wslpath` are Windows interop binaries whose
// output is silently lost when captured via `$( … )`.
//
// Fix: address everything by paths RELATIVE to a `cwd` of REPO_ROOT, the
// proven `_changed-surfaces.test.cjs` pattern. A relative POSIX path needs
// no drive translation and is accepted unchanged by Git Bash, WSL bash, and
// Linux bash alike. Fixtures therefore live under `.scratch/` inside the
// repo (gitignored) rather than `os.tmpdir()`, so a relative path reaches
// them.
function runRel(relAdapterDir) {
  const command = `${shQuote(`./${SCRIPT_REL}`)} ${shQuote(relAdapterDir)}`;
  return spawnSync('bash', ['-lc', command], { cwd: REPO_ROOT, encoding: 'utf8' });
}

function run(fixture) {
  return runRel(fixture.rel);
}

function cleanup() {
  fs.rmSync(SCRATCH_ROOT, { recursive: true, force: true });
}

test('success: renames reagent_slim.cljs → reagent.cljs and rewrites the ns form', () => {
  const fix = makeFixture();
  const { src, dst } = fix;
  try {
    const res = run(fix);
    assert.equal(res.status, 0, `expected exit 0, got ${res.status}\n${res.stderr}\n${res.stdout}`);
    // Source file is gone; destination exists.
    assert.equal(fs.existsSync(src), false, 'source reagent_slim.cljs should be removed');
    assert.equal(fs.existsSync(dst), true, 'destination reagent.cljs should exist');
    const out = fs.readFileSync(dst, 'utf8');
    // Canonical ns declaration present; slim ns declaration gone.
    assert.match(out, /^\(ns re-frame\.adapter\.reagent\b/m, 'canonical (ns …) form present');
    assert.doesNotMatch(
      out,
      /\(ns re-frame\.adapter\.reagent-slim\b/,
      'slim (ns …) declaration must be rewritten away',
    );
    // The docstring/require mention of the slim artefact survives (the
    // script rewrites only the leading `(ns …)` token, not every mention).
    assert.match(
      out,
      /re-frame\.adapter\.reagent-slim :as reagent-slim/,
      'non-ns mentions of the slim artefact must survive untouched',
    );
  } finally {
    cleanup();
  }
});

test('abort: source file missing → non-zero exit with ::error::', () => {
  const fix = makeFixture({ withSource: false });
  try {
    const res = run(fix);
    assert.notEqual(res.status, 0, 'expected non-zero exit when source is missing');
    assert.match(
      `${res.stdout}${res.stderr}`,
      /::error::expected source file .* not found/,
      'must emit the source-missing ::error:: line',
    );
  } finally {
    cleanup();
  }
});

test('abort: destination already exists → non-zero exit (in-tree ns clash)', () => {
  const fix = makeFixture({ withDest: true });
  try {
    const res = run(fix);
    assert.notEqual(res.status, 0, 'expected non-zero exit when destination exists');
    assert.match(
      `${res.stdout}${res.stderr}`,
      /::error::destination .* already exists/,
      'must emit the destination-exists ::error:: line',
    );
    // The source must be left untouched (no partial mv).
    assert.equal(fs.existsSync(fix.src), true, 'source must not be moved on abort');
  } finally {
    cleanup();
  }
});

test('abort: ns form not found → non-zero exit (in-tree source restructured)', () => {
  const fix = makeFixture({ nsForm: '(ns re-frame.adapter.something-else' });
  try {
    const res = run(fix);
    assert.notEqual(res.status, 0, 'expected non-zero exit when the slim ns form is absent');
    assert.match(
      `${res.stdout}${res.stderr}`,
      /::error::expected '\(ns re-frame\.adapter\.reagent-slim' declaration not found/,
      'must emit the ns-form-not-found ::error:: line',
    );
    // No mv on abort.
    assert.equal(fs.existsSync(fix.src), true, 'source must not be moved on abort');
  } finally {
    cleanup();
  }
});

// rf2-6m7pn4 — regression for the Windows path-portability defect. The
// fix addresses the script + fixture by paths RELATIVE to a bash `cwd` of
// REPO_ROOT, because an absolute Windows drive path (`C:\…` or `C:/…`) is
// not resolvable across all three supported Bash flavours. This test locks
// that contract: makeFixture() must hand back a forward-slashed,
// drive-letter-free, repo-relative path, and that relative path must
// resolve to the real fixture dir under bash's cwd. If a future edit
// reverts to an os.tmpdir() absolute path (the form that broke under WSL),
// the first two assertions fire before the four functional cases do,
// pointing straight at the path layer.
test('fixture path handed to bash is relative + drive-letter-free, and resolves under cwd (rf2-6m7pn4)', () => {
  const fix = makeFixture({ withSource: false });
  try {
    assert.equal(path.isAbsolute(fix.rel), false, 'the bash-facing path must be relative, not absolute');
    assert.doesNotMatch(fix.rel, /^[A-Za-z]:/, 'the bash-facing path must carry no Windows drive letter');
    assert.doesNotMatch(fix.rel, /\\/, 'the bash-facing path must use forward slashes only');
    // Prove bash resolves `<rel>/.` under cwd=REPO_ROOT to a real dir by
    // listing it. A drive-form path would make bash mis-read it as relative
    // (the WSL failure mode) and `test -d` would be false.
    const res = spawnSync('bash', ['-lc', `test -d ${shQuote(fix.rel)} && printf RESOLVED`], {
      cwd: REPO_ROOT,
      encoding: 'utf8',
    });
    assert.equal(res.status, 0, `expected exit 0, got ${res.status}\n${res.stderr}\n${res.stdout}`);
    assert.equal(res.stdout, 'RESOLVED', 'the relative fixture path must resolve under bash cwd=REPO_ROOT');
  } finally {
    cleanup();
  }
});

// rf2-83jsbh — the publication ns-rename rewrites ONLY the leading `(ns …)`
// token (by design, to protect docstrings/requires from over-rewrite). So a
// docstring `(require '[re-frame.adapter.reagent-slim …])` usage example would
// survive the ns-only rename into the published jar, where that namespace does
// NOT exist (the slim jar ships its adapter at the canonical
// `re-frame.adapter.reagent`). CLJS ships source in the jar, so such a stale
// example is user-visible and a copy-paste hits namespace-not-found. Lock the
// source rename-safe: NO require form may reference the `-slim` ns — every
// usage example must require the published `re-frame.adapter.reagent` instead.
//
// Scoped to the require-VECTOR form (`[re-frame.adapter.reagent-slim …]`), NOT
// every `re-frame.adapter.reagent-slim` substring: the `(ns …)` declaration
// (which the transform rewrites) and any bare qualified-symbol reference such
// as a diagnostic `'re-frame.adapter.reagent-slim/foo` marker are legitimately
// distinct concerns and must not trip this rf2-83jsbh gate.
test('real adapter source is rename-safe: no require form references the -slim ns (rf2-83jsbh)', () => {
  const realSrc = path.join(
    IMPL_ROOT, 'adapters', 'reagent-slim', 'src', 're_frame', 'adapter', 'reagent_slim.cljs',
  );
  const body = fs.readFileSync(realSrc, 'utf8');
  // Sanity: the in-tree source declares the slim ns (the transform's anchor).
  const nsForms = (body.match(/\(ns\s+re-frame\.adapter\.reagent-slim\b/g) || []).length;
  assert.equal(nsForms, 1, 'the in-tree source must carry exactly one (ns re-frame.adapter.reagent-slim …) declaration');
  // The defect: a require VECTOR aliasing the slim ns (a docstring usage
  // example). The adapter never requires ITSELF, so any such form is an
  // example that would go stale post-publish.
  const requireVectors = (body.match(/\[\s*re-frame\.adapter\.reagent-slim\b/g) || []).length;
  assert.equal(
    requireVectors,
    0,
    'no `(require [re-frame.adapter.reagent-slim …])` form may appear in the adapter '
      + 'source — the ns-only publication rename leaves it pointing at a namespace '
      + 'that does not exist in the published jar (which ships at '
      + 're-frame.adapter.reagent); usage examples must require the published ns '
      + '(rf2-83jsbh)',
  );
});

let failed = 0;
for (const { name, fn } of tests) {
  try {
    fn();
  } catch (err) {
    failed += 1;
    console.error(`FAIL ${name}`);
    console.error(err && err.stack ? err.stack : err);
  }
}

if (failed > 0) {
  console.error(`transform-reagent-slim-ns tests: ${failed} failed.`);
  process.exit(1);
}

console.log(`transform-reagent-slim-ns tests: ${tests.length} passed.`);
