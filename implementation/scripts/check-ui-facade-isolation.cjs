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
// STATUS (rf2-kxork): GREEN and REQUIRED. This fixture landed RED in #6182 —
// unimported sibling render functions were retained because the production
// `defview` arm bound `re-frame.ui.runtime/memo-view`'s `React.memo(renderFn,
// cmp)` call to a top-level `def`, which Closure does not treat as
// side-effect-free, so the unreferenced def (and its render fn + sentinel
// strings) survived `:advanced`. #6195 repaired that packaging behaviour and
// the isolation assertion now passes, so the gate has been promoted from
// parked acceptance test to standing regression net: it runs as the
// `cljs-ui-facade-isolation` job in .github/workflows/test.yml (fired by the
// ui_gates surface) and the required `all-required-passed` aggregator
// `needs:` it.
//
// SCOPE BOUND — read before adding a view. IMPORTED/SIBLINGS below is a
// HARDCODED roster, not one derived from the library source. The gate proves
// the isolation contract for exactly the six views it names; a SEVENTH view
// added to library.cljs is invisible to it, and would be retained in the
// single-view bundle without turning this gate red (verified by probe under
// rf2-kxork). Adding a view to the proof-pack library therefore REQUIRES
// adding its sentinel here. Deriving the roster from the library source is
// tracked separately (rf2-r4q98) — do not read the current PASS as covering
// views absent from these two lists.

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
      `bundle -> ${retained.join(', ')}. A single-view import must not drag its siblings into the ` +
      `bundle (EP-0035 readiness §4). This is the REGRESSION #6195 fixed: unreferenced view defs bound ` +
      `to React.memo(...) stop being DCE'd when the emitted view def / memo-view loses its purity ` +
      `annotation, so suspect a change to the defview emitter (compiler/emit_cljs.cljc) or to ` +
      `memo-view (ui/runtime.cljs).`);
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
