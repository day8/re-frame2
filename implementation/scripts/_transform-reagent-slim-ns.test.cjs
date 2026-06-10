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
const os = require('os');
const path = require('path');

const IMPL_ROOT = path.resolve(__dirname, '..');
const REPO_ROOT = path.resolve(IMPL_ROOT, '..');
const SCRIPT = path.join(REPO_ROOT, '.github', 'scripts', 'transform-reagent-slim-ns.sh');

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
// script expects. Returns { dir, src, dst }.
function makeFixture({ withSource = true, withDest = false, nsForm = SLIM_NS_FORM } = {}) {
  const dir = fs.mkdtempSync(path.join(os.tmpdir(), 'rf2-slim-ns-'));
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
  return { dir, src, dst };
}

function run(adapterDir) {
  return spawnSync('bash', [SCRIPT, adapterDir], { encoding: 'utf8' });
}

function cleanup(dir) {
  fs.rmSync(dir, { recursive: true, force: true });
}

test('success: renames reagent_slim.cljs → reagent.cljs and rewrites the ns form', () => {
  const { dir, src, dst } = makeFixture();
  try {
    const res = run(dir);
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
    cleanup(dir);
  }
});

test('abort: source file missing → non-zero exit with ::error::', () => {
  const { dir } = makeFixture({ withSource: false });
  try {
    const res = run(dir);
    assert.notEqual(res.status, 0, 'expected non-zero exit when source is missing');
    assert.match(
      `${res.stdout}${res.stderr}`,
      /::error::expected source file .* not found/,
      'must emit the source-missing ::error:: line',
    );
  } finally {
    cleanup(dir);
  }
});

test('abort: destination already exists → non-zero exit (in-tree ns clash)', () => {
  const { dir, src } = makeFixture({ withDest: true });
  try {
    const res = run(dir);
    assert.notEqual(res.status, 0, 'expected non-zero exit when destination exists');
    assert.match(
      `${res.stdout}${res.stderr}`,
      /::error::destination .* already exists/,
      'must emit the destination-exists ::error:: line',
    );
    // The source must be left untouched (no partial mv).
    assert.equal(fs.existsSync(src), true, 'source must not be moved on abort');
  } finally {
    cleanup(dir);
  }
});

test('abort: ns form not found → non-zero exit (in-tree source restructured)', () => {
  const { dir, src } = makeFixture({ nsForm: '(ns re-frame.adapter.something-else' });
  try {
    const res = run(dir);
    assert.notEqual(res.status, 0, 'expected non-zero exit when the slim ns form is absent');
    assert.match(
      `${res.stdout}${res.stderr}`,
      /::error::expected '\(ns re-frame\.adapter\.reagent-slim' declaration not found/,
      'must emit the ns-form-not-found ::error:: line',
    );
    // No mv on abort.
    assert.equal(fs.existsSync(src), true, 'source must not be moved on abort');
  } finally {
    cleanup(dir);
  }
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
