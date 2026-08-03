#!/usr/bin/env node
'use strict';
// THE HICASSO LANE'S COMPILE GATE — rf2-2rtt6.73, half (b).
//
//     npm run test:hicasso-compile        # from implementation/
//     node freehand/test/re_frame/bench/hicasso/compile_gate.cjs --list
//
// ## The gap
//
// NO PR GATE COMPILED THIS LANE. `:node-test` selects `:ns-regexp
// "cljs-test$"` plus transitive requires and `:browser-test` selects
// `-dom-cljs-test$` likewise, and nothing test-shaped requires the arms — so
// `out/node-test.js` contains zero occurrences of `walk_profile_app` and
// `out/browser-test/` has no such module. The only compiler that ever saw an
// arm was `:hicasso-bench`, driven BY HAND. An arm could stop compiling and
// no automated run could go red.
//
// That matters more here than it would elsewhere because the lane's arms are
// deliberately LOCAL COPIES of shipping code — the call-convention discipline
// rf2-2rtt6.32 established, and it is correct. Local copies drift by
// construction; the only thing between a drifted copy and a published figure
// is a gate.
//
// ## The shape
//
// One `shadow-cljs compile` of every namespace in the lane, judged by
// `lane_build.cjs` (warnings are failures). `compile`, not `release`: the
// regression classes this closes — a deleted def, a renamed require, a
// dropped arity — are all resolved before optimization, and a dev compile is
// a fraction of the cost. See LIMITS below for what that choice gives up.
//
// ## NO EDIT TO shadow-cljs.edn, deliberately
//
// The gate rides `:hicasso-bench`, the id the whole lane already shares, and
// supplies its own `:output-dir` and `:modules {:main {:entries [...]}}`
// through `--config-merge` — exactly as every arm rides it with its own
// `:init-fn`. HD-017 makes a new build id a hot-zone edit to
// `implementation/shadow-cljs.edn` and therefore a sequenced dispatch; the
// lane was built so a sibling never has to pay that, and a gate over the lane
// is no more entitled to than an arm is. The cache rule (rf2-2rtt6.20) is
// honoured the same way every driver honours it: clear the shared entry
// first.
//
// ## Auto-covering by construction
//
// The entry list is DERIVED by walking this directory, never listed here — a
// roster in this file would be the staleness class the gate exists to catch,
// and a new arm would land uncovered and look covered. Every `.cljs`/`.cljc`
// under the lane is an entry, INCLUDING the `*_cljs_test.cljs` files that
// already have lanes of their own: a filename-shaped exclusion is one more
// thing that can silently drop the file you cared about, and re-compiling a
// handful of test namespaces is cheaper than that risk.
//
// ## LIMITS — what this gate still cannot see
//
//   * `:advanced`-only breakage. It compiles in dev mode, so a Closure
//     renaming / externs-inference fault that only appears in the `:advanced`
//     bundle the drivers actually measure is invisible here. `lane_build.cjs`
//     catches those at driver time, on the run that would publish them.
//   * Anything that COMPILES. An arm whose numbers stopped meaning what its
//     name says, a local copy that has drifted from the shipping code it
//     mirrors, a measurement window that no longer brackets the work — all
//     compile clean. This gate proves the lane still BUILDS, never that it
//     still MEASURES.
//   * Runtime faults. Nothing is executed here; no page is mounted.

const fs = require('node:fs');
const path = require('node:path');

const { shadowBuild } = require('./lane_build.cjs');
const { resetLaneBuildCache } = require('../../freehand/bench/lane_cache.cjs');

const LANE_DIR = __dirname;
const IMPL = path.resolve(__dirname, '../../../../..');
const BUILD_ID = 'hicasso-bench';
const OUT_DIR = 'out/hicasso-compile-gate';
const TAG = 'hicasso-compile';

// The lane is ~60 source files today. A derivation that silently recovers a
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
    else unreadable.push(path.relative(IMPL, f));
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

  if (resetLaneBuildCache(IMPL, BUILD_ID)) {
    console.error(
      `[${TAG}] cleared .shadow-cljs/builds/${BUILD_ID} — one build id, N arms (rf2-2rtt6.20)`,
    );
  }

  console.error(
    `[${TAG}] compiling all ${namespaces.length} lane namespaces -> ${OUT_DIR}`,
  );

  // ONE LINE, deliberately: shadow-cljs's CLI re-splits `--config-merge` on
  // whitespace once the EDN contains a newline, then reports `EOF while
  // reading` from a fragment.
  const configMerge =
    `{:output-dir "${OUT_DIR}" :asset-path "." ` +
    `:modules {:main {:entries [${namespaces.join(' ')}]}}}`;

  shadowBuild({ impl: IMPL, mode: 'compile', buildId: BUILD_ID, configMerge, tag: TAG });

  console.error(
    `[${TAG}] ok — ${namespaces.length} lane namespaces compiled with zero warnings`,
  );
}

module.exports = { laneSourceFiles, namespaceOf, laneNamespaces, MIN_NAMESPACES };
