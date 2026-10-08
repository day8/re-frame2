#!/usr/bin/env node
/*
 * Tests for `.github/scripts/transform-reagent-slim-ns.sh`, the publication-time
 * rename of re_frame/adapter/reagent_slim.cljs to reagent.cljs with its (ns ...)
 * form rewritten: the one release step where the published artefact diverges from
 * the tested tree. Runs the real script under bash against throwaway fixtures.
 * Discovered by `npm run test:scripts`.
 */

'use strict';

const assert = require('assert/strict');
const { spawnSync } = require('child_process');
const fs = require('fs');
const path = require('path');

const IMPL_ROOT = path.resolve(__dirname, '..');
const REPO_ROOT = path.resolve(IMPL_ROOT, '..');

// Paths are RELATIVE to a bash cwd of REPO_ROOT: an absolute Windows drive path
// resolves under neither WSL bash nor every Git Bash argv, so fixtures live under
// the gitignored `.scratch/` inside the repo.
const SCRIPT_REL = '.github/scripts/transform-reagent-slim-ns.sh';
const { makeScratchDir, cleanupScratchDirs } = require('./lib/scratch-fixtures.cjs');

const tests = [];
function test(name, fn) {
  tests.push({ name, fn });
}

// The docstring's mention of the slim ns must survive: only the (ns ...) token is rewritten.
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

function makeFixture({ withSource = true, withDest = false, nsForm = SLIM_NS_FORM } = {}) {
  const dir = makeScratchDir(REPO_ROOT, 'rf2-slim-ns');
  const adapterDir = path.join(dir, 'src', 're_frame', 'adapter');
  fs.mkdirSync(adapterDir, { recursive: true });
  const src = path.join(adapterDir, 'reagent_slim.cljs');
  const dst = path.join(adapterDir, 'reagent.cljs');
  if (withSource) {
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

function relPosix(abs) {
  return path.relative(REPO_ROOT, abs).split(path.sep).join('/');
}

function shQuote(s) {
  return `'${String(s).replace(/'/g, `'\\''`)}'`;
}

function runRel(relAdapterDir) {
  const command = `${shQuote(`./${SCRIPT_REL}`)} ${shQuote(relAdapterDir)}`;
  return spawnSync('bash', ['-lc', command], { cwd: REPO_ROOT, encoding: 'utf8' });
}

function run(fixture) {
  return runRel(fixture.rel);
}

function cleanup() {
  cleanupScratchDirs();
}

test('success: renames reagent_slim.cljs → reagent.cljs and rewrites the ns form', () => {
  const fix = makeFixture();
  const { src, dst } = fix;
  try {
    const res = run(fix);
    assert.equal(res.status, 0, `expected exit 0, got ${res.status}\n${res.stderr}\n${res.stdout}`);
    assert.equal(fs.existsSync(src), false, 'source reagent_slim.cljs should be removed');
    const out = fs.readFileSync(dst, 'utf8');
    assert.match(out, /^\(ns re-frame\.adapter\.reagent\b/m, 'canonical (ns …) form present');
    assert.doesNotMatch(
      out,
      /\(ns re-frame\.adapter\.reagent-slim\b/,
      'slim (ns …) declaration must be rewritten away',
    );
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
    assert.equal(fs.existsSync(fix.src), true, 'source must not be moved on abort');
  } finally {
    cleanup();
  }
});

// The rename rewrites only the (ns ...) token, so a docstring usage example that
// requires `[re-frame.adapter.reagent-slim ...]` would ship pointing at a namespace
// the published jar does not have. Only the require-VECTOR form is refused.
test('real adapter source is rename-safe: no require form references the -slim ns (rf2-83jsbh)', () => {
  const realSrc = path.join(
    IMPL_ROOT, 'adapters', 'reagent-slim', 'src', 're_frame', 'adapter', 'reagent_slim.cljs',
  );
  const body = fs.readFileSync(realSrc, 'utf8');
  // The transform's anchor, so the scan below reads the right file.
  const nsForms = (body.match(/\(ns\s+re-frame\.adapter\.reagent-slim\b/g) || []).length;
  assert.equal(nsForms, 1, 'the in-tree source must carry exactly one (ns re-frame.adapter.reagent-slim …) declaration');
  const requireVectors = (body.match(/\[\s*re-frame\.adapter\.reagent-slim\b/g) || []).length;
  assert.equal(
    requireVectors,
    0,
    'no `(require [re-frame.adapter.reagent-slim …])` form may appear in the adapter '
      + 'source — the ns-only publication rename leaves it pointing at a namespace '
      + 'that does not exist in the published jar (which ships at '
      + 're-frame.adapter.reagent); usage examples must require '
      + 'the published ns',
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
