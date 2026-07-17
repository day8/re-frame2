#!/usr/bin/env node
'use strict';

// G-18 — library facade isolation (fixture-first; 07 §5, EP-0035, readiness §4).
//
// An advanced build importing EXACTLY ONE view from a multi-view library
// namespace must retain NO unused sibling views, schemas, docs, or dev
// registration. This runner builds the proof-pack single-view + all-views
// advanced bundles and asserts:
//
//   positive control (all-views): every sentinel PRESENT — proves the six
//     library views compile and their sentinels are real, DCE-able strings
//     (so a single-view absence is genuine elision, not a renamed literal).
//   isolation (single-view): the imported `controlled-input` sentinel PRESENT,
//     and the five unimported siblings' sentinels ABSENT.
//
// FIXTURE-FIRST STATUS: as of landing this fixture, the isolation assertion is
// RED — the unimported sibling render functions are retained because the
// production `defview` arm binds `re-frame.ui.runtime/memo-view`'s
// `React.memo(renderFn, cmp)` call to a top-level `def`, and Closure does not
// treat that call as side-effect-free, so the unreferenced def (and its render
// fn + sentinel strings) survive `:advanced`. Per EP-0035 readiness §4 that is
// exactly the structural failure that JUSTIFIES a substrate packaging change
// (pure-annotating the emitted view def / memo-view so an unreferenced view
// DCEs). That change touches the shipped emitter/runtime and is tracked
// separately (see bead rf2-edpam); this gate is the
// acceptance test for it and is intentionally NOT in the required CI matrix
// until it goes green.

const path = require('path');
const { spawnSync } = require('child_process');
const { classifyReleaseBundle } = require('./lib/read-release-bundle.cjs');

const IMPL = path.resolve(__dirname, '..');
const SINGLE = path.join(IMPL, 'out', 'proof-pack-single');
const ALL = path.join(IMPL, 'out', 'proof-pack-all');

// The imported view + the five siblings that must vanish from the single build.
const IMPORTED = 'rf2-pp-controlled-input-sentinel';
const SIBLINGS = [
  'rf2-pp-selection-controller-sentinel',
  'rf2-pp-list-cell-sentinel',
  'rf2-pp-safe-form-control-sentinel',
  'rf2-pp-schema-described-sentinel',
  'rf2-pp-inline-popover-sentinel',
];
const ALL_SENTINELS = [IMPORTED, ...SIBLINGS];

function resolveBin(modulePath) {
  return require.resolve(modulePath, { paths: [IMPL] });
}

function shadow(...args) {
  const runner = resolveBin('shadow-cljs/cli/runner.js');
  console.log(`> shadow-cljs ${args.join(' ')}`);
  const result = spawnSync(process.execPath, [runner, ...args], {
    cwd: IMPL, env: process.env, shell: false, stdio: 'inherit',
  });
  if (result.error) throw result.error;
  if (result.status !== 0) throw new Error(`shadow-cljs ${args.join(' ')} exited ${result.status}`);
}

function bundleBlobOrThrow(label, dir) {
  const { status, blob } = classifyReleaseBundle(dir);
  if (status !== 'ok') throw new Error(`G-18: ${label} bundle is ${status}, not inspectable (${dir})`);
  return blob;
}

function main() {
  shadow('release', 'proof-pack-single', 'proof-pack-all');

  const allBlob = bundleBlobOrThrow('all-views', ALL);
  const singleBlob = bundleBlobOrThrow('single-view', SINGLE);

  const problems = [];

  // Positive control — non-vacuity: every sentinel present in the all-views build.
  for (const s of ALL_SENTINELS) {
    if (!allBlob.includes(s)) {
      problems.push(`positive control: ${s} missing from all-views bundle (sentinel not real/DCE-able)`);
    }
  }

  // Isolation — the contract.
  if (!singleBlob.includes(IMPORTED)) {
    problems.push(`isolation: imported view sentinel ${IMPORTED} absent from single-view bundle (import not rooted)`);
  }
  const retained = SIBLINGS.filter((s) => singleBlob.includes(s));

  console.log('=== G-18 library facade isolation ===');
  console.log(`  positive control (all-views): ${ALL_SENTINELS.length} sentinels`);
  console.log(`  single-view import: ${IMPORTED} present=${singleBlob.includes(IMPORTED)}`);
  console.log(`  siblings retained in single-view: ${retained.length}/${SIBLINGS.length}` +
    (retained.length ? ` -> ${retained.join(', ')}` : ''));

  if (retained.length) {
    problems.push(
      `isolation: ${retained.length} unimported sibling view(s) retained in the advanced single-view ` +
      `bundle -> ${retained.join(', ')}. This is the fixture-first STRUCTURAL FAILURE (EP-0035 readiness ` +
      `§4): unreferenced view defs bound to React.memo(...) are not DCE'd. Remediation is a substrate ` +
      `packaging change to the emitted view def / memo-view purity — off-limits to the conformance bead; ` +
      `tracked by rf2-edpam.`);
  }

  if (problems.length) {
    console.error('\n=== G-18 FAIL ===');
    for (const p of problems) console.error(`  - ${p}`);
    process.exit(1);
  }
  console.log('G-18 PASS — a single-view import retains no unused siblings.');
}

if (require.main === module) main();

module.exports = { IMPORTED, SIBLINGS, ALL_SENTINELS };
