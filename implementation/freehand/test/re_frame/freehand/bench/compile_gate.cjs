#!/usr/bin/env node
'use strict';
// THE COMPILE GATE FOR THE BENCH NAMESPACES NO OTHER PR GATE REACHES — rf2-cfqk.
//
//     npm run test:bspine-compile         # from implementation/
//     node freehand/test/re_frame/freehand/bench/compile_gate.cjs --list
//     node freehand/test/re_frame/freehand/bench/compile_gate.cjs --self-test
//
// ## The gap
//
// rf2-bl0j walked every `.cljs`/`.cljc` under the bench trees and decided
// coverage by forward reachability from each PR gate's roots — `cljs-test$`
// for `:node-test`, `-dom-cljs-test$` for `:browser-test`, the hicasso lane's
// directory walk, and the entry namespaces of the PR-gated `:browser` /
// `:node-script` builds. Of 213 bench namespaces, 23 were reachable from
// NOTHING. PR #7907 closed four of them — the `:hicasso-bench` riders that sit
// outside the lane directory — by rostering them in that lane's own
// `compile_gate.cjs`. The NINETEEN rostered below are the rest, and they could
// not be closed the same way: no existing PR-gated compile reaches them, which
// is why this file and the workflow step that runs it landed together.
//
// What "reachable from nothing" costs is not hypothetical. rf2-bl0j proved it
// by sabotage: an undeclared var appended to an uncovered namespace reds no
// gate on any pull request. These nineteen are B-spine arms and attribution
// instruments whose figures are published in decision records; an arm that
// stopped compiling would be found by whoever next ran it by hand, which is
// the same as saying it would be found by nobody.
//
// ## The roster is STATED, not walked
//
// The hicasso lane's gate derives its entries by walking one directory, and
// that is right for a lane that IS a directory. This set is not: it spans
// `core/test/re_frame/bench/` and `freehand/test/re_frame/freehand/bench/`,
// and in both it is a strict subset — the neighbouring `*_cljs_test` and
// `*_dom_cljs_test` namespaces are already compiled by `:node-test` and
// `:browser-test`, and re-listing them here would buy a slower gate and a
// second place for their coverage to be argued about.
//
// So the covered set is a LIST, and every row is checked against disk before
// anything compiles: the file must exist, and it must declare exactly the
// namespace the row names. A moved, renamed or deleted arm therefore REDS this
// gate and names the file, instead of dropping silently out of the entry list
// the way a walk would let it. That is rf2-bl0j's principle applied, not
// improved on.
//
// ## ONE build, and the measurement that decided it
//
// rf2-cfqk expected TWO gates: a node-lane compile for the two
// `re-frame.bench.*` attribution rows (both instruments run under Node, and
// both document a `--config-merge` ride on `:ui-bench`, a `:node-script`
// build) and a `:freehand-release`-riding compile for the seventeen B-spine
// arms — on the reasoning that one module mixing Node and browser targets
// would "red or pass for reasons unrelated to their health".
//
// That was tested rather than assumed, and it came out the other way round.
// Measured on this tree, from a cleared cache:
//
//   shadow-cljs compile freehand-release   all 19 rows in one module
//                                          -> Build completed, 0 warnings
//   shadow-cljs compile ui-bench           `:main write-attribution/-main`
//                                          -> Build completed, 4 warnings
//
// All four were `:infer-warning`s, and all four were in
// `implementation/core/src/re_frame/substrate/spine.cljs` — PRODUCTION source
// on the row's transitive closure, at `(.-props out)` and its siblings, where
// `out` is a DOM element. Closure's browser externs make that inference
// succeed; a `:node-script` build has no DOM externs, so it cannot. A
// "warnings are failures" gate on that lane would therefore be RED ON ARRIVAL
// over source the bench rows do not own, and the only two ways to green it are
// to lower `:infer-externs` — which `lane_build.cjs` refuses on principle,
// because silencing a class rebuilds the defect it exists to remove — or to
// edit shipping code to satisfy a bench gate. The two-gate shape is the one
// that would have committed the category error, in the direction nobody
// predicted.
//
// What survives from that reasoning is the part that is true: the classes this
// gate closes — a deleted def, a renamed require, a dropped arity, an
// undeclared var — are all resolved by the ClojureScript ANALYSER, which does
// not vary by `:target`. So one module is not a compromise here; it is the
// whole of what a compile gate can claim, taken once.
//
// ## NO EDIT TO shadow-cljs.edn, deliberately
//
// The gate rides `:freehand-release` — the id every B-spine driver already
// builds (`b6_prod_run.cjs`, `b7_run.cjs`, `b8_run.cjs`, `b10_prod_run.cjs`,
// `reads_ladder_run.cjs`, `spine_ablation_run.cjs` all name it) — and supplies
// its own `:output-dir` and `:modules {:main {:entries [...]}}` through
// `--config-merge`, exactly as each of those drivers supplies its own
// `:init-fn`. HD-017 makes a new build id a hot-zone edit to
// `implementation/shadow-cljs.edn` and therefore a sequenced dispatch; a gate
// over the arms is no more entitled to that than an arm is.
//
// The base config's own `:init-fn re-frame.freehand.release-app/-main`
// survives the merge and adds the release entry to the compiled set. That is
// left alone rather than nulled out: it is already compiled by
// `test:freehand-reachability` and `test:freehand-evidence-elision`, so it
// costs a few files and no ambiguity, and the hicasso gate lives with the
// same inheritance.
//
// `compile`, not `release`. The regression classes above are all decided
// before optimisation, and the hicasso gate's own measurement (recorded in
// its header) showed externs inference running in dev mode too, so the
// `:advanced` half buys a multiple of the wall clock for Closure hard errors
// this source cannot produce.
//
// ## The cache rule (rf2-2rtt6.20 / rf2-6t03c)
//
// shadow-cljs derives the build cache directory from the build id ALONE,
// fixed before any `--config-merge` is applied, so a merged compile and a
// plain one share `.shadow-cljs/builds/freehand-release`. This gate clears
// that entry BEFORE it compiles, so it is never judging a cache some other
// arm's config wrote, and AFTER, so the next plain `freehand-release` build —
// `test:freehand-evidence-elision`, or a driver in a developer's checkout —
// is never handed one this gate wrote.
//
// ## LIMITS
//
//   * Anything that COMPILES. An arm whose numbers stopped meaning what its
//     name says compiles clean. This gate proves the nineteen still BUILD,
//     never that they still MEASURE.
//   * Nothing is executed and no page is mounted, so a runtime fault in an
//     arm nobody has run since it broke stays invisible here.
//   * The roster does not know when a row STOPS needing it. If a namespace
//     later becomes reachable from `:node-test` or `:browser-test`, this gate
//     keeps compiling it — a little redundant work, and no false claim. The
//     walker that would notice is the one rf2-bl0j declined to build, on the
//     grounds that implicit coverage is what put these nineteen here.
//
// `namespaceOf` below is a deliberate four-line twin of the hicasso lane
// gate's function of the same name. The two gates are siblings, not
// dependents: requiring one gate's entry point as the other's library would
// couple two lanes' schedules for the sake of one regex.

const fs = require('node:fs');
const path = require('node:path');

const { shadowBuildVerdict, reportRefusal } = require('../../bench/hicasso/lane_build.cjs');
const { resetLaneBuildCache } = require('./lane_cache.cjs');

const IMPL = path.resolve(__dirname, '../../../../..');
const BUILD_ID = 'freehand-release';
const OUT_DIR = 'out/bspine-compile-gate';
const TAG = 'bspine-compile';

/**
 * THE COVERED SET. Every row is a namespace that, on main at rf2-cfqk, was
 * compiled by no pull-request gate. Paths are relative to `implementation/`.
 *
 * Adding an arm to either bench tree does NOT cover it — check whether some
 * gate reaches it, and add a row here if none does.
 */
const ENTRIES = [
  // The two Node attribution instruments. Neither is named `*-cljs-test`,
  // nothing test-shaped requires either, and their own drive commands are
  // hand-run `--config-merge` rides on `:ui-bench`.
  { ns: 're-frame.bench.read-attribution-cljs', file: 'core/test/re_frame/bench/read_attribution_cljs.cljs' },
  { ns: 're-frame.bench.write-attribution', file: 'core/test/re_frame/bench/write_attribution.cljs' },

  // The seventeen B-spine arms. Their drivers ride `:freehand-release`, and
  // their only workflow home — `.github/workflows/freehand-bench.yml` — is
  // `schedule:`-only and gates nothing. `:browser-test-freehand-bench`
  // selects `.+-dom-cljs-test$` within the same namespace, so it does not
  // reach them either.
  { ns: 're-frame.freehand.bench.b6-prod-app', file: 'freehand/test/re_frame/freehand/bench/b6_prod_app.cljs' },
  { ns: 're-frame.freehand.bench.b6-profile-app', file: 'freehand/test/re_frame/freehand/bench/b6_profile_app.cljs' },
  { ns: 're-frame.freehand.bench.b6-witnesses-flat', file: 'freehand/test/re_frame/freehand/bench/b6_witnesses_flat.cljc' },
  { ns: 're-frame.freehand.bench.b6-yield-app', file: 'freehand/test/re_frame/freehand/bench/b6_yield_app.cljs' },
  { ns: 're-frame.freehand.bench.b7-app', file: 'freehand/test/re_frame/freehand/bench/b7_app.cljs' },
  { ns: 're-frame.freehand.bench.b7-heap', file: 'freehand/test/re_frame/freehand/bench/b7_heap.cljs' },
  { ns: 're-frame.freehand.bench.b7-mount-frame', file: 'freehand/test/re_frame/freehand/bench/b7_mount_frame.cljs' },
  { ns: 're-frame.freehand.bench.b7-pageerror-probe', file: 'freehand/test/re_frame/freehand/bench/b7_pageerror_probe.cljs' },
  { ns: 're-frame.freehand.bench.b8-alloc', file: 'freehand/test/re_frame/freehand/bench/b8_alloc.cljs' },
  { ns: 're-frame.freehand.bench.b8-app', file: 'freehand/test/re_frame/freehand/bench/b8_app.cljs' },
  { ns: 're-frame.freehand.bench.b9-app', file: 'freehand/test/re_frame/freehand/bench/b9_app.cljs' },
  { ns: 're-frame.freehand.bench.b9-nc', file: 'freehand/test/re_frame/freehand/bench/b9_nc.cljs' },
  { ns: 're-frame.freehand.bench.b10-prod-app', file: 'freehand/test/re_frame/freehand/bench/b10_prod_app.cljs' },
  { ns: 're-frame.freehand.bench.reads-ladder', file: 'freehand/test/re_frame/freehand/bench/reads_ladder.cljs' },
  { ns: 're-frame.freehand.bench.reads-ladder-app', file: 'freehand/test/re_frame/freehand/bench/reads_ladder_app.cljs' },
  { ns: 're-frame.freehand.bench.spine-ablation', file: 'freehand/test/re_frame/freehand/bench/spine_ablation.cljs' },
  { ns: 're-frame.freehand.bench.spine-ablation-app', file: 'freehand/test/re_frame/freehand/bench/spine_ablation_app.cljs' },
];

/**
 * The namespace a source declares. The `ns` form is always top level, so it is
 * matched at column 0 — which keeps the many `(ns ...)` spellings inside these
 * files' long docstrings out of the result.
 */
function namespaceOf(src) {
  const m = /^\(ns\s+(?:\^\{[\s\S]*?\}\s+)?([A-Za-z0-9._*+!?<>=$%&|-]+)/m.exec(src);
  return m ? m[1] : null;
}

/**
 * Check every row against disk. Returns the problems, most specific first; an
 * empty array means the roster still describes the tree.
 *
 * `implDir` is a parameter rather than the module constant so `--self-test`
 * can point the same code at a fixture tree and watch each refusal fire.
 */
function auditRoster(entries, implDir) {
  const problems = [];
  const seen = new Map();

  for (const { ns, file } of entries) {
    if (seen.has(ns)) {
      problems.push(`${ns}: listed twice (${seen.get(ns)} and ${file})`);
      continue;
    }
    seen.set(ns, file);

    const full = path.join(implDir, file);
    if (!fs.existsSync(full)) {
      problems.push(
        `${ns}: ${file} does not exist — the file moved, was renamed, or was ` +
          'deleted. Update the row or drop it; do not let it fall out of the gate.',
      );
      continue;
    }

    const declared = namespaceOf(fs.readFileSync(full, 'utf8'));
    if (declared === null) {
      problems.push(`${ns}: ${file} has no readable top-level (ns ...) form`);
    } else if (declared !== ns) {
      problems.push(`${ns}: ${file} declares ${declared} instead`);
    }
  }

  return problems;
}

// ---------------------------------------------------------------------------
// --self-test: prove each refusal FIRES.
//
// The compile half of this gate is proved by breaking a real arm and watching
// the build go red. The ROSTER half cannot be proved that way without leaving
// a broken tree behind, and it is the half that carries rf2-bl0j's whole
// argument — so it is proved here, against a fixture directory, on every run.
// ---------------------------------------------------------------------------

function selfTest() {
  const os = require('node:os');
  const root = fs.mkdtempSync(path.join(os.tmpdir(), 'bspine-compile-gate-'));
  const failures = [];

  const check = (label, ok) => {
    if (ok) console.error(`[${TAG}] self-test PASS  ${label}`);
    else {
      failures.push(label);
      console.error(`[${TAG}] self-test FAIL  ${label}`);
    }
  };

  try {
    fs.mkdirSync(path.join(root, 'src'), { recursive: true });
    fs.writeFileSync(path.join(root, 'src', 'ok.cljs'), '(ns fixture.ok)\n');
    fs.writeFileSync(path.join(root, 'src', 'renamed.cljs'), '(ns fixture.other)\n');
    fs.writeFileSync(path.join(root, 'src', 'headless.cljs'), ';; no ns form here\n');

    const row = (ns, file) => [{ ns, file: path.join('src', file) }];

    check(
      'a row whose file exists and declares its namespace passes',
      auditRoster(row('fixture.ok', 'ok.cljs'), root).length === 0,
    );
    check(
      'a MISSING file is refused',
      auditRoster(row('fixture.gone', 'gone.cljs'), root).length === 1,
    );
    check(
      'a file declaring a DIFFERENT namespace is refused',
      auditRoster(row('fixture.renamed', 'renamed.cljs'), root).length === 1,
    );
    check(
      'a file with no readable (ns ...) form is refused',
      auditRoster(row('fixture.headless', 'headless.cljs'), root).length === 1,
    );
    check(
      'a namespace listed twice is refused',
      auditRoster(
        [...row('fixture.ok', 'ok.cljs'), ...row('fixture.ok', 'ok.cljs')],
        root,
      ).length === 1,
    );
    check(
      'the LIVE roster still describes the tree',
      auditRoster(ENTRIES, IMPL).length === 0,
    );
  } finally {
    fs.rmSync(root, { recursive: true, force: true, maxRetries: 5, retryDelay: 100 });
  }

  if (failures.length > 0) {
    console.error(`[${TAG}] self-test FAILED — ${failures.length} case(s) did not hold`);
    process.exit(1);
  }
  console.error(`[${TAG}] self-test ok — every roster refusal fires`);
}

if (require.main === module) {
  const argv = process.argv.slice(2);

  if (argv.includes('--self-test')) {
    selfTest();
    process.exit(0);
  }

  const problems = auditRoster(ENTRIES, IMPL);
  if (problems.length > 0) {
    console.error(
      `[${TAG}] ${problems.length} roster row(s) no longer describe the tree — ` +
        'refusing to compile a set they have silently dropped out of:',
    );
    for (const p of problems) console.error(`  ${p}`);
    process.exit(1);
  }

  const namespaces = ENTRIES.map((e) => e.ns);

  if (argv.includes('--list')) {
    for (const ns of namespaces) console.log(ns);
    process.exit(0);
  }

  if (resetLaneBuildCache(IMPL, BUILD_ID)) {
    console.error(
      `[${TAG}] cleared .shadow-cljs/builds/${BUILD_ID} before compiling — one ` +
        'build id, N configs (rf2-2rtt6.20 / rf2-6t03c)',
    );
  }

  console.error(
    `[${TAG}] compiling all ${namespaces.length} rostered namespaces -> ${OUT_DIR}`,
  );

  // ONE LINE, deliberately: shadow-cljs's CLI re-splits `--config-merge` on
  // whitespace once the EDN contains a newline, then reports `EOF while
  // reading` from a fragment.
  const configMerge =
    `{:output-dir "${OUT_DIR}" :asset-path "." ` +
    `:modules {:main {:entries [${namespaces.join(' ')}]}}}`;

  // `shadowBuildVerdict` rather than its exiting sibling `shadowBuild`: a
  // REFUSED build must clear the shared cache too, and `process.exit` skips
  // `finally`, so the verdict has to come back here to be acted on.
  const verdict = shadowBuildVerdict({ impl: IMPL, mode: 'compile', buildId: BUILD_ID, configMerge });
  resetLaneBuildCache(IMPL, BUILD_ID);

  if (!verdict.ok) {
    reportRefusal(TAG, verdict);
    console.error(
      `[${TAG}] the entries were: ${namespaces.join(' ')}`,
    );
    process.exit(1);
  }

  console.error(
    `[${TAG}] ok — ${namespaces.length} namespaces compiled with zero warnings`,
  );
}

module.exports = { ENTRIES, namespaceOf, auditRoster };
