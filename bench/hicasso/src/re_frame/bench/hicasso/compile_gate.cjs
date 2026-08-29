#!/usr/bin/env node
'use strict';
// THE BENCH PROJECT'S COMPILE CHECK — rf2-2rtt6.73, re-homed by rf2-6c12m.1.
//
//     npm run check        # from bench/hicasso/ — this, then the .cjs self-tests
//     node src/re_frame/bench/hicasso/compile_gate.cjs --list
//
// ## The gap this closes
//
// The lane's arms are deliberately LOCAL COPIES of shipping code (the
// rf2-2rtt6.32 call-convention discipline), so they drift by construction, and
// nothing test-shaped requires them: `:node-test` and `:browser-test` select by
// namespace suffix and never see an arm. Before this gate the only compiler
// that ever saw one was `:hicasso-bench`, driven BY HAND through drivers that
// passed on warnings — an arm could stop compiling and no run could go red.
//
// ## The shape
//
// One `shadow-cljs compile` of every namespace under `bench/hicasso/src/`,
// judged by `lane_build.cjs` (a warning is a failure). `compile`, not
// `release`: the classes this closes — a deleted def, a renamed require, a
// dropped arity, an undeclared var, an un-externable property access — are
// all resolved by the analyser before optimisation, and `:infer-externs :auto`
// is bound in both modes (MUTATION-PROVED: an unknown property in
// `parity_probe_app` reds this gate with `:infer-warning`, and an `:advanced`
// release of the same tree reports the identical warning from the identical
// line, at twice the wall clock).
//
// ## Auto-covering by construction
//
// The entry list is DERIVED by walking `src/`, never listed here — a roster
// in this file would be the staleness class the gate exists to catch, and a
// new arm would land uncovered and look covered. Every `.cljs`/`.cljc` under
// the walk is an entry, INCLUDING the `*_cljs_test.cljs` suites: a
// filename-shaped exclusion is one more thing that can silently drop the file
// you cared about. Since rf2-6c12m.1 the whole lane lives under this one
// source root — the four riders that used to sit beside the artefacts they
// measure (`p0_app`, `p0_pageerror_probe`, `hicasso_narrow`,
// `hicasso_narrow_app`) moved in with it — so the walk IS the lane and no
// stated roster is needed any more.
//
// The optional-module and re-homed-core-instrument entry sources this gate
// used to carry are PRODUCT concerns, not the lane's, and they stayed in the
// package when the lane left it: `implementation/hicasso/scripts/
// check_modules_compile.cjs`, run by `npm run test:hicasso-compile` on every
// PR. Nothing here reaches `implementation/hicasso/src/` on purpose.
//
// ## What it cannot see
//
// Anything that COMPILES. A local copy that drifted from the shipping code it
// mirrors, a window that no longer brackets the work, a figure whose name
// stopped meaning what it says — all compile clean. This gate proves the lane
// still BUILDS, never that it still MEASURES; execution is the drivers' job.

const fs = require('node:fs');
const path = require('node:path');

const { shadowBuild } = require('./lane_build.cjs');
const { resetLaneBuildCache } = require('../../../../../../implementation/core/test/re_frame/bench/lane_cache.cjs');

const PROJECT = path.resolve(__dirname, '../../../..');
const LANE_DIR = path.join(PROJECT, 'src');
const BUILD_ID = 'hicasso-bench';
const OUT_DIR = 'out/hicasso-compile-gate';
const TAG = 'hicasso-compile';

// The lane is ~100 namespaces today. A derivation that silently recovers a
// handful has broken, and a gate that compiles three namespaces while
// reporting success is the fail-open it replaced. The floor is deliberately
// far below the real count — it catches collapse, not growth.
const MIN_NAMESPACES = 40;

/** Every `.cljs` / `.cljc` under the lane, recursively, sorted. */
function laneSourceFiles(dir = LANE_DIR) {
  const out = [];
  for (const entry of fs.readdirSync(dir, { withFileTypes: true })) {
    const full = path.join(dir, entry.name);
    if (entry.isDirectory()) out.push(...laneSourceFiles(full));
    else if (/\.clj[sc]$/.test(entry.name)) out.push(full);
  }
  return out.sort();
}

/**
 * The namespace a lane source declares. The `ns` form is always top level, so
 * it is matched at column 0 — which keeps the many `(ns ...)` spellings inside
 * this lane's long docstrings out of the result.
 */
function namespaceOf(file) {
  const src = fs.readFileSync(file, 'utf8');
  const m = /^\(ns\s+(?:\^\{[\s\S]*?\}\s+)?([A-Za-z0-9._*+!?<>=$%&|-]+)/m.exec(src);
  return m ? m[1] : null;
}

/**
 * The lane's entries. A file with no readable `ns` form is a FAILURE, not a
 * skip: skipping it would drop it from the gate silently, which is the whole
 * defect being repaired.
 */
function laneNamespaces() {
  const files = laneSourceFiles();
  const namespaces = [];
  const unreadable = [];
  for (const f of files) {
    const ns = namespaceOf(f);
    if (ns) namespaces.push(ns);
    else unreadable.push(path.relative(PROJECT, f));
  }
  return { namespaces: [...new Set(namespaces)].sort(), unreadable };
}

if (require.main === module) {
  const listOnly = process.argv.slice(2).includes('--list');
  const { namespaces, unreadable } = laneNamespaces();

  if (unreadable.length > 0) {
    console.error(
      `[${TAG}] ${unreadable.length} lane source(s) have no readable top-level ` +
        `(ns ...) form — refusing to compile a set they are silently missing from:`,
    );
    for (const f of unreadable) console.error(`  ${f}`);
    process.exit(1);
  }

  if (namespaces.length < MIN_NAMESPACES) {
    console.error(
      `[${TAG}] only ${namespaces.length} namespace(s) derived from ${LANE_DIR} ` +
        `(floor ${MIN_NAMESPACES}) — the derivation has collapsed; refusing to ` +
        `pass a vacuous gate.`,
    );
    process.exit(1);
  }

  if (listOnly) {
    for (const ns of namespaces) console.log(ns);
    process.exit(0);
  }

  if (resetLaneBuildCache(PROJECT, BUILD_ID)) {
    console.error(
      `[${TAG}] cleared .shadow-cljs/builds/${BUILD_ID} — one build id, N arms (rf2-2rtt6.20)`,
    );
  }

  console.error(
    `[${TAG}] compiling all ${namespaces.length} namespaces walked from ${LANE_DIR} -> ${OUT_DIR}`,
  );

  // ONE LINE, deliberately: shadow-cljs's CLI re-splits `--config-merge` on
  // whitespace once the EDN contains a newline, then reports `EOF while
  // reading` from a fragment.
  const configMerge =
    `{:output-dir "${OUT_DIR}" :asset-path "." ` +
    `:modules {:main {:entries [${namespaces.join(' ')}]}}}`;

  shadowBuild({ project: PROJECT, mode: 'compile', buildId: BUILD_ID, configMerge, tag: TAG });

  console.error(`[${TAG}] ok — ${namespaces.length} namespaces compiled with zero warnings`);
}

module.exports = {
  laneSourceFiles,
  namespaceOf,
  laneNamespaces,
  MIN_NAMESPACES,
};
