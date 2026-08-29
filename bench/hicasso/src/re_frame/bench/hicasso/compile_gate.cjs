#!/usr/bin/env node
'use strict';
// THE HICASSO LANE'S COMPILE GATE — rf2-2rtt6.73, half (b).
//
//     npm run test:hicasso-compile        # from implementation/
//     node hicasso/test/re_frame/bench/hicasso/compile_gate.cjs --list
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
// ## Auto-covering by construction — for the arms that live HERE
//
// The entry list is DERIVED by walking this directory, never listed here — a
// roster in this file would be the staleness class the gate exists to catch,
// and a new arm would land uncovered and look covered. Every `.cljs`/`.cljc`
// under the lane is an entry, INCLUDING the `*_cljs_test.cljs` files that
// already have lanes of their own: a filename-shaped exclusion is one more
// thing that can silently drop the file you cared about, and re-compiling a
// handful of test namespaces is cheaper than that risk.
//
// ## …and an EXPLICIT roster for the arms that do not (rf2-bl0j)
//
// A walk of one directory covers exactly that directory, and the lane does not
// fit in one. `:hicasso-bench` is the lane — the id every arm rides — but four
// of its riders live beside the artefact they measure rather than beside their
// siblings, because that is where the local copies they mirror live:
//
//     core/test/re_frame/bench/p0_app.cljs               re-frame.bench.p0-app
//     core/test/re_frame/bench/p0_pageerror_probe.cljs   …p0-pageerror-probe
//     adapters/reagent/test/re_frame/bench/hicasso_narrow.cljs      …hicasso-narrow
//     adapters/reagent/test/re_frame/bench/hicasso_narrow_app.cljs  …hicasso-narrow-app
//
// Until rf2-bl0j they were compiled by NOTHING: not by this walk, which never
// leaves `hicasso/`; not by `:node-test`, since none is named `*-cljs-test`
// and nothing test-shaped requires them; not by `:browser-test`, for the same
// reason. Their own drivers (`p0_run.cjs`, `hicasso_narrow_run.cjs`) compile
// them, and those drivers are hand-run. That is the identical fail-open this
// gate was built for — one directory over, and hidden by the very derivation
// that closed it here, because a walk announces nothing when a file moves out
// from under it.
//
// THE ROSTER IS DELIBERATELY DUMB. The tempting repair is a cleverer rule —
// walk more trees, or scrape `--config-merge` init-fns out of the sibling
// drivers — and it is the wrong one: a coverage rule that INFERS its members
// fails the same way again the first time a member stops matching the
// inference, silently and with the gate still green. So membership is stated,
// once, right here. What the roster cannot do is notice an arm that was never
// added to it; what it can do, and does below, is refuse to run when a listed
// file has been moved, renamed or deleted — the failure mode a derived list
// hides and a stated one cannot.
//
// EVERY member is listed, including the two reachable through a require
// (`p0-pageerror-probe` requires `p0-app`; `hicasso-narrow-app` requires
// `hicasso-narrow`). Coverage that arrives through somebody else's `:require`
// is exactly what evaporated here once already.
//
// ## …and the OPTIONAL MODULES, which nothing warnings-fatal reached (rf2-okhdf)
//
// The third entry source is not a lane member at all, and it is here because
// this is the only instrument in the repository that would have caught it.
//
// Hicasso's optional modules — `motion`, `overlay`, `forms`, `native` — are
// unreachable from the public door BY CONSTRUCTION, which is a product
// invariant that `hicasso/scripts/check_optional_module_reachability.py`
// enforces and that holds. The consequence is that no compile which starts at
// the door reaches them: MEASURED on `out/hicasso-release/main.js`, the
// artefact's only `:advanced` bundle, `anchorName` / `positionAnchor` /
// `showPopover` / `rf-overlay-` / `buffered-field` each occur ZERO times
// against `hicasso` at 75. Their own tests DO compile them — but under
// `:node-test-hicasso`, which sets `:infer-externs false`, and under
// `:browser-test`, which infers and then exits 0 on the warnings like every
// other shadow build. So the modules were compiled in two places and JUDGED in
// none.
//
// What that cost, concretely: four `:infer-warning`s on
// `(.. el -style -anchorName)` in `impl/overlay.cljs` lived on main until a
// worker read them off a browser build BY HAND (rf2-9zz0y). Under `:advanced`
// Closure renames a property it cannot see an extern for, so the trigger claim
// would have broken silently in every consumer that shipped an overlay.
//
// MEASURED, before and after, by re-introducing that exact fault — dropping
// the `^js` hint from `claim-anchor!`'s `el`:
//
//     against this gate BEFORE the roster was added   exit 0, 0 warnings
//     compiled directly, entry `…hicasso.impl.overlay`  2 x :infer-warning
//     against this gate AFTER                          exit 1, named
//
// The entries are READ FROM THE ROSTER — `check_optional_module_reachability.py
// --module-namespaces` — rather than restated here, and that is the whole
// design rather than a convenience. A hand-copied list would leave the NEXT
// optional module compiled by nothing while this gate went on reporting
// success, which is the defect being closed, one module later. Rowing a module
// in that roster is already mandatory for the invariant it exists to enforce;
// deriving from it means the same edit buys this coverage and there is no
// second place to remember.
//
// It fails CLOSED in four directions — the emitter missing, the emitter
// erroring, an empty or collapsed list, a malformed namespace — because an
// entry source that quietly contributed nothing would leave this gate green
// over exactly the code it was widened to cover. `compile_gate.test.cjs` pins
// each refusal.
//
// IT IS ALSO THE ONE ENTRY SOURCE HERE THAT IS NOT LANE-SCOPED, and whoever
// retires this directory owns the consequence (rf2-swhjb). The walk covers this
// directory and the roster above covers four lane members living elsewhere —
// both are the bench lane's own business and both should die with it. The
// optional modules are not: they are PRODUCT code under `hicasso/src/`, and
// they are compiled here only because this is the one instrument in the
// repository that would have caught the fault. So if this directory is ever
// retired, this third entry source must MOVE rather than go with it. Note that
// neither floor catches that: `MIN_NAMESPACES` reds on the directory EMPTYING
// and `MIN_MODULE_NAMESPACES` on the roster collapsing, but a gate that has
// been DELETED reports nothing at all.
//
// THAT DECISION IS NOT DUE YET, checked at source rather than assumed
// (2026-08-14). No retirement covers this directory: rf2-0yp7w retires
// `implementation/freehand/` and `implementation/ui/`, and hicasso is the
// survivor of that ruling — a Reagent adapter, a UIx adapter, and hicasso as
// re-frame2 native. The sibling coupling in rf2-d19nf was live because
// Freehand's tree had a scheduled deletion, since executed by PR #8322; this
// directory has none, and may never.
//
// WHAT A RELOCATION WOULD NEED, recorded now so the decision is cheap when it
// is actually due: somewhere to compile from. This gate takes NO new build id
// on purpose — see "NO EDIT TO shadow-cljs.edn" above — and rides
// `:hicasso-bench` through `--config-merge`. A relocated optional-module
// compile needs either a new build id, which HD-017 makes a hot-zone edit to
// `implementation/shadow-cljs.edn` and therefore a sequenced dispatch, or
// another existing id to ride. That is the whole of the design question, and
// it cannot be answered before the destination exists.
//
// NONE OF WHICH IS AN ARGUMENT AGAINST THE WALK, and a later reader must not
// take it as one. `laneSourceFiles` stays: seeing one directory is this gate's
// stated point, and a roster in its place would be the staleness class the gate
// exists to catch. The optional-module entry source added here reads no
// directory at all — it resolves entirely from the roster.
//
// ## …and the TWO RE-HOMED CORE INSTRUMENTS, whose own gate was deleted (rf2-peorl)
//
// The fourth entry source is not a lane member either, and like the optional
// modules it is here because this is now the only warnings-fatal compile that
// can reach it.
//
// `re-frame.bench.read-attribution-cljs` and `re-frame.bench.write-attribution`
// are the CLJS allocation instruments under `core/test/re_frame/bench/`. They
// were two rows of a nineteen-row roster in
// `freehand/test/re_frame/freehand/bench/compile_gate.cjs` — rf2-cfqk's gate,
// run by `npm run test:bspine-compile` as a step of the required `cljs` job.
// That gate's whole implementation lived inside `implementation/freehand/`, so
// PR #8322 retired it along with the tree. The seventeen `re-frame.freehand.
// bench.*` B-spine arms went with it CORRECTLY — they are gone. These two did
// not: they are still tracked, still uncovered by anything else, and between
// that merge and this row they had no compile gate at all.
//
// UNCOVERED, MEASURED rather than assumed (2026-08-16, at the tip this landed
// on). Neither namespace is named `*-cljs-test` or `*-dom-cljs-test`, so
// `:node-test` and `:browser-test` do not select them; and nothing anywhere in
// the tree `:require`s either one — `git grep -F` over both namespace symbols
// returns their own `ns` forms, one prose mention in `order_guard.cljc`'s
// docstring and one in a design page, and nothing else. The reachability their
// coverage would have to arrive through does not exist. (Positive control on
// the same search: `re-frame.bench.p0-app`, rostered four rows above, is found
// in this file, its own source, `p0_run.cjs` and three documents.)
//
// A BROWSER MODULE IS THE RIGHT HOST, and the contrary claim on record is the
// one that was tested and lost. rf2-cfqk predicted that compiling these two
// into a `:browser` module would be "a category error", because both are Node
// instruments whose drive commands rode `:ui-bench`, a `:node-script` build.
// rf2-bl0j's worker then measured it from a cleared cache and recorded the
// result in the gate this row replaces:
//
//     shadow-cljs compile freehand-release  (`:browser`)     0 warnings
//     shadow-cljs compile ui-bench          (`:node-script`) 4 warnings
//
// All four were `:infer-warning`s, all four in
// `implementation/core/src/re_frame/substrate/spine.cljs` — PRODUCTION source
// on these rows' transitive closure, at `(.-props out)` and its siblings, where
// `out` is a DOM element. Closure's browser externs make that inference
// succeed and a `:node-script` build has none, so a warnings-fatal gate on the
// Node lane is RED ON ARRIVAL over source these rows do not own. The category
// error was in the other direction, and `:hicasso-bench` is `:target :browser`
// with `:infer-externs :auto` — the same shape `:freehand-release` had.
//
// What that leaves is what a compile gate can honestly claim anyway: the
// classes closed here — a deleted def, a renamed require, a dropped arity, an
// undeclared var — are resolved by the ClojureScript ANALYSER, which does not
// vary by `:target`. Compiling a Node instrument in a browser module proves it
// still BUILDS. It never proved, and was never asked to prove, that it still
// runs under Node.
//
// NO NEW SCRIPT AND NO WORKFLOW EDIT, which is why this is two roster rows
// rather than a re-built gate. `npm run test:hicasso-compile` is already a step
// of the required `cljs` job, so the coverage is restored by the same edit that
// states it; a fresh npm script would have needed a `.github/workflows/**` step
// to satisfy `scripts/check_gate_scheduling.py`, which is a hot-zone edit and a
// sequenced dispatch for a gate that already exists.
//
// THIS IS THE SECOND ENTRY SOURCE THE RETIREMENT NOTE ABOVE APPLIES TO — that
// note calls the optional modules "the ONE entry source here that is not
// lane-scoped", and as of this row there are two. If this
// directory is ever retired these two rows must MOVE, exactly as the optional
// modules must — they are core's instruments, not the lane's, and they are here
// only because this is where a warnings-fatal browser compile still runs. That
// they arrived by outliving their own gate is the argument, not a counter to
// it: a roster row is cheap to move and impossible to lose silently, which is
// the property the deleted gate did not have.
//
// ## `:advanced` — MEASURED, and why there is no nightly release gate
//
// This block used to say that an "externs-inference fault" was invisible to a
// dev-mode gate and that `lane_build.cjs` caught the `:advanced` classes at
// driver time. rf2-2rtt6.77 was filed off that claim, asking for a nightly
// that releases each arm under `:advanced`. The claim was then TESTED rather
// than assumed, and it does not hold. Recorded here so it is not re-derived a
// third time. Timings are one box, with `.shadow-cljs/builds/hicasso-bench`
// cleared before each, which the lane's cache rule requires anyway:
//
//     dev compile, all 101 lane namespaces (THIS gate)     77s
//     `:advanced` release, ONE arm                         64s
//     `:advanced` release, all 101 in one module          132s
//
//   1. EXTERNS INFERENCE IS ALREADY COVERED HERE, in dev. `:infer-externs
//      :auto` is shadow-cljs's own `default-compiler-options`
//      (`shadow.build.api`), and `shadow.build.compiler` binds `:infer-warning`
//      into `ana/*cljs-warnings*` for every non-jar source in BOTH modes — it
//      is not a release-only pass. MUTATION-PROVED: rewriting
//      `parity_probe_app`'s `(some-> e .-message)` to an unknown property made
//      THIS gate exit 1 with `WARNING #1 - :infer-warning` / `Cannot infer
//      target type in expression`. The `:advanced` release of the same tree
//      reported the identical single warning from the identical line. Same
//      catch, half the wall clock. `check-examples-compile.cjs` already said
//      as much — it lists "an externs-inference miss" among the classes its
//      own DEV compile catches — so this file was the outlier, not the rule.
//   2. CLOSURE RENAMING FAULTS EMIT NO COMPILER DIAGNOSTIC AT ALL, so no build
//      gate in any mode sees them: a release of a program that will die on a
//      renamed property still exits 0 with 0 warnings. EXECUTION catches those
//      — the driver's fail-closed page.
//   3. THE rf2-2rtt6.20 CLASS IS EXPLICITLY NOT BUILD-OBSERVABLE. That bead's
//      own words are that the poisoned shadow-js index "COMPILES CLEANLY and
//      then produce[s] an `:advanced` bundle that DIED ON ITS FIRST
//      EXECUTION". Its fix is `lane_cache.cjs`, which this gate already calls.
//
// What is left for an `:advanced` BUILD to catch over lane sources is Closure
// hard errors, and that class is empty by construction: no lane source uses
// `js*`, so there is no unparsed JS for Closure to reject; the only npm
// modules the lane requires directly are `react`, `react-dom` and
// `react-dom/client`, the same three `test:bundle-isolation`,
// `test:perf-bundle`, `test:elision` and `test:ui-g1` already push through
// shadow-js under `:advanced`; and Closure type checking is off by shadow's
// default (`:closure-warnings {:check-types :off}`), so there are no
// `:advanced`-only type errors to find either.
//
// THE OPTIONAL MODULES FALL UNDER THAT SAME VERDICT, and rf2-okhdf asked for
// an `:advanced` build over them specifically, so the transfer is checked at
// source rather than assumed. Point (1) is the whole of what that bead's
// finding needs — `:infer-warning` is bound in both modes, so a DEV compile
// catches an un-externable property access identically, which the mutation
// above then demonstrated on the real fault. Point (2) is what the fix does
// NOT buy: a renaming fault that reaches the DOM emits no diagnostic in any
// mode, and the `-dom-cljs-test` suites that execute these modules run under
// `:none`, so nothing in this repository would see one. And the residual
// `:advanced`-only class is empty here for the same two reasons it is empty
// over lane sources — none of the seven module namespaces uses `js*`, and the
// only npm module any of them requires is `react`.
//
// So the fix delivered is a COMPILE, not a test, and the difference is not
// cosmetic: it decides mangling and externs, and it decides nothing about
// behaviour.
//
// So the nightly was NOT built: no fault could be constructed that it would
// catch and this gate would miss, and a gate nobody can make fire is the
// fail-open class this lane keeps repairing rather than a repair of it.
// Per-arm was priced for completeness — 25 `-main`s x 64s is ~27 minutes —
// and it buys nothing over the one-module release above, whose namespace set
// is a SUPERSET of every arm's.
//
// ## LIMITS — what this gate still cannot see
//
//   * Anything that COMPILES. An arm whose numbers stopped meaning what its
//     name says, a local copy that has drifted from the shipping code it
//     mirrors, a measurement window that no longer brackets the work — all
//     compile clean. This gate proves the lane still BUILDS, never that it
//     still MEASURES.
//   * Runtime faults. Nothing is executed here; no page is mounted — and per
//     (2) and (3) above, that is where the whole remaining `:advanced` risk
//     lives. An arm nobody has run since it broke can still be `:advanced`-
//     broken with every gate green. The only instrument that reaches it is a
//     BOOT of each arm's release bundle, ~30 minutes nightly across 25 arms
//     plus a per-arm sentinel contract; judged out of proportion to the
//     residual on rf2-2rtt6.77, not overlooked.

const fs = require('node:fs');
const path = require('node:path');
const { spawnSync } = require('node:child_process');

const { shadowBuild } = require('./lane_build.cjs');
const { resetLaneBuildCache } = require('../../../../../core/test/re_frame/bench/lane_cache.cjs');

const LANE_DIR = __dirname;
const IMPL = path.resolve(__dirname, '../../../../..');
const BUILD_ID = 'hicasso-bench';
const OUT_DIR = 'out/hicasso-compile-gate';
const TAG = 'hicasso-compile';

// The lane is ~60 source files today. A derivation that silently recovers a
// handful has broken, and a gate that compiles three namespaces while
// reporting success is the fail-open it replaced. The floor is deliberately
// far below the real count — it catches collapse, not growth.
//
// It guards the WALK only. The roster below needs no floor: every one of its
// rows is checked individually and by name, which is strictly stronger.
const MIN_NAMESPACES = 40;

/**
 * The lane's members that live outside this directory — see the roster section
 * of the header. `file` is relative to `implementation/`, and both halves are
 * verified: the file must exist AND declare that exact namespace.
 */
const OUTSIDE_LANE_ENTRIES = [
  {
    ns: 're-frame.bench.p0-app',
    file: 'core/test/re_frame/bench/p0_app.cljs',
    why: "P0's :advanced browser entry; driven by p0_run.cjs on :hicasso-bench",
  },
  {
    ns: 're-frame.bench.p0-pageerror-probe',
    file: 'core/test/re_frame/bench/p0_pageerror_probe.cljs',
    why: "the real P0 app plus one detached throw — p0_run.cjs's pageerror refusal, watched firing",
  },
  {
    ns: 're-frame.bench.hicasso-narrow',
    file: 'adapters/reagent/test/re_frame/bench/hicasso_narrow.cljs',
    why: 'the P0 ratom-spine narrow-write leg (write + flush summed)',
  },
  {
    ns: 're-frame.bench.hicasso-narrow-app',
    file: 'adapters/reagent/test/re_frame/bench/hicasso_narrow_app.cljs',
    why: "that leg's :advanced entry; driven by hicasso_narrow_run.cjs on :hicasso-bench",
  },
];

/**
 * The two core attribution instruments RE-HOMED here when their own gate was
 * deleted with `implementation/freehand/` — see the rf2-peorl section of the
 * header for why a browser module is the measured-correct host and why this is
 * two rows rather than a rebuilt gate. Same three fields and the same
 * both-halves check as the roster above; a SEPARATE constant because these are
 * not lane members, and folding them into `OUTSIDE_LANE_ENTRIES` would make
 * that roster's one-line description untrue.
 */
const REHOMED_BENCH_ENTRIES = [
  {
    ns: 're-frame.bench.read-attribution-cljs',
    file: 'core/test/re_frame/bench/read_attribution_cljs.cljs',
    why: "the READ path priced on the host re-frame2 ships to — read_attribution.clj's CLJS counterpart, arm for arm",
  },
  {
    ns: 're-frame.bench.write-attribution',
    file: 'core/test/re_frame/bench/write_attribution.cljs',
    why: "where the bytes of a NARROW WRITE go — the decomposition of B8's 457,181-byte write leg",
  },
];

// rf2-okhdf. The optional modules' roster, and the flag that makes it answer.
// It is the SAME file that forbids anything outside a module from requiring
// it; see this file's header for why the list is read rather than restated.
const MODULE_ROSTER = 'hicasso/scripts/check_optional_module_reachability.py';
const MODULE_ROSTER_FLAG = '--module-namespaces';

// Five optional modules today, eight namespaces between them (rf2-2a0ju added
// the `server` row). The floor is a COLLAPSE detector and not a count: it
// catches an emitter that has started answering nothing while still exiting 0,
// which is the only way this entry source can contribute silently. Growth is
// the roster's business, and this number is deliberately NOT raised to meet it
// — a floor that tracked the roster would have to be edited by every author
// who adds a module, which is the second place to remember that reading the
// roster exists to abolish.
const MIN_MODULE_NAMESPACES = 4;

// A CLJS namespace, as the emitter is contracted to print them. Anything else
// is a refusal rather than an entry: shadow-cljs would take a malformed token
// into `:entries` and fail with a resolution error naming a file nobody wrote,
// which sends the reader somewhere this gate can already say is wrong.
const NAMESPACE_RE = /^[A-Za-z][A-Za-z0-9._*+!?<>=$%&|-]*$/;

/**
 * Decide the optional-module entry list from the emitter's result. Pure, so
 * `compile_gate.test.cjs` can prove each refusal fires without spawning
 * Python — the shape `lane_build.cjs` uses for the same reason.
 *
 * @returns {{ok: true, namespaces: string[]} | {ok: false, reason: string, detail: string[]}}
 */
function decideModuleNamespaces({ error, status, stdout, stderr }) {
  const cmd = `python ${MODULE_ROSTER} ${MODULE_ROSTER_FLAG}`;
  if (error) {
    return {
      ok: false,
      reason: `could not run \`${cmd}\` — the optional-module roster could not be asked`,
      detail: [String(error.message || error)],
    };
  }
  if (status !== 0) {
    return {
      ok: false,
      reason: `\`${cmd}\` exited ${status}`,
      detail: String(stderr || '').split('\n').filter(Boolean),
    };
  }
  const namespaces = String(stdout || '')
    .split('\n')
    .map((line) => line.trim())
    .filter(Boolean);
  const malformed = namespaces.filter(
    (ns) => !NAMESPACE_RE.test(ns) || !ns.includes('.'),
  );
  if (malformed.length > 0) {
    return {
      ok: false,
      reason: `\`${cmd}\` emitted ${malformed.length} token(s) that are not namespaces`,
      detail: malformed.map((ns) => JSON.stringify(ns)),
    };
  }
  if (namespaces.length < MIN_MODULE_NAMESPACES) {
    return {
      ok: false,
      reason:
        `\`${cmd}\` emitted only ${namespaces.length} namespace(s) ` +
        `(floor ${MIN_MODULE_NAMESPACES}) — the roster emission has collapsed, ` +
        `and compiling the survivors would report success over the modules it dropped`,
      detail: namespaces,
    };
  }
  return { ok: true, namespaces: [...new Set(namespaces)].sort() };
}

/** Ask the roster which namespaces the optional modules own. */
function optionalModuleNamespaces(impl = IMPL) {
  const python = process.env.PYTHON || 'python';
  const r = spawnSync(python, [MODULE_ROSTER, MODULE_ROSTER_FLAG], {
    cwd: impl,
    encoding: 'utf8',
  });
  return decideModuleNamespaces(r);
}

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

/**
 * A stated roster, verified. A row whose file has moved, been renamed or been
 * deleted — or whose file no longer declares the namespace claimed for it — is
 * a FAILURE, not a skip, for the reason the header gives: a stated list can
 * only be honest if saying something untrue stops the gate.
 *
 * `rows` and `impl` are parameters so both rosters share one verifier and
 * `compile_gate.test.cjs` can watch each refusal fire against a fixture tree
 * without breaking a real one.
 */
function verifyRoster(rows, impl = IMPL) {
  const namespaces = [];
  const broken = [];
  for (const row of rows) {
    const full = path.join(impl, row.file);
    if (!fs.existsSync(full)) {
      broken.push(`${row.file} — no such file (roster claims ${row.ns})`);
      continue;
    }
    const declared = namespaceOf(full);
    if (declared !== row.ns) {
      broken.push(
        `${row.file} — declares ${declared ?? '(no readable ns form)'}, roster claims ${row.ns}`,
      );
      continue;
    }
    namespaces.push(row.ns);
  }
  return { namespaces, broken };
}

if (require.main === module) {
  const listOnly = process.argv.slice(2).includes('--list');
  const { namespaces: walked, unreadable } = laneNamespaces();
  const outsideRoster = verifyRoster(OUTSIDE_LANE_ENTRIES);
  const rehomedRoster = verifyRoster(REHOMED_BENCH_ENTRIES);
  const outside = outsideRoster.namespaces;
  const rehomed = rehomedRoster.namespaces;
  const broken = [...outsideRoster.broken, ...rehomedRoster.broken];
  const modules = optionalModuleNamespaces();

  if (!modules.ok) {
    console.error(
      `[${TAG}] the optional-module entry source failed (rf2-okhdf): ${modules.reason}. ` +
        `Refusing to compile a set the optional modules may be missing from — ` +
        `nothing else in this repository compiles them where warnings are fatal.`,
    );
    for (const d of modules.detail) console.error(`  ${d}`);
    process.exit(1);
  }

  if (broken.length > 0) {
    console.error(
      `[${TAG}] ${broken.length} roster row(s) in ${path.relative(IMPL, __filename)} no ` +
        `longer name a real namespace — refusing to compile a set they have silently ` +
        `dropped out of (rf2-bl0j):`,
    );
    for (const b of broken) console.error(`  ${b}`);
    process.exit(1);
  }

  if (unreadable.length > 0) {
    console.error(
      `[${TAG}] ${unreadable.length} lane source(s) have no readable top-level ` +
        `(ns ...) form — refusing to compile a set they are silently missing from:`,
    );
    for (const f of unreadable) console.error(`  ${f}`);
    process.exit(1);
  }

  if (walked.length < MIN_NAMESPACES) {
    console.error(
      `[${TAG}] only ${walked.length} namespace(s) derived from ${LANE_DIR} ` +
        `(floor ${MIN_NAMESPACES}) — the derivation has collapsed; refusing to ` +
        `pass a vacuous gate.`,
    );
    process.exit(1);
  }

  const namespaces = [
    ...new Set([...walked, ...outside, ...rehomed, ...modules.namespaces]),
  ].sort();

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
    `[${TAG}] compiling all ${namespaces.length} namespaces ` +
      `(${walked.length} walked from ${LANE_DIR}, ${outside.length} rostered ` +
      `from outside it, ${rehomed.length} re-homed core attribution instruments ` +
      `(rf2-peorl), ${modules.namespaces.length} optional-module namespaces ` +
      `read from ${MODULE_ROSTER}) -> ${OUT_DIR}`,
  );

  // ONE LINE, deliberately: shadow-cljs's CLI re-splits `--config-merge` on
  // whitespace once the EDN contains a newline, then reports `EOF while
  // reading` from a fragment.
  const configMerge =
    `{:output-dir "${OUT_DIR}" :asset-path "." ` +
    `:modules {:main {:entries [${namespaces.join(' ')}]}}}`;

  shadowBuild({ impl: IMPL, mode: 'compile', buildId: BUILD_ID, configMerge, tag: TAG });

  console.error(
    `[${TAG}] ok — ${namespaces.length} namespaces compiled with zero warnings ` +
      `(including ${modules.namespaces.length} optional-module namespaces and ` +
      `${rehomed.length} re-homed core attribution instruments no other ` +
      `warnings-fatal compile reaches)`,
  );
}

module.exports = {
  laneSourceFiles,
  namespaceOf,
  laneNamespaces,
  verifyRoster,
  decideModuleNamespaces,
  optionalModuleNamespaces,
  MIN_NAMESPACES,
  MIN_MODULE_NAMESPACES,
  MODULE_ROSTER,
  MODULE_ROSTER_FLAG,
  OUTSIDE_LANE_ENTRIES,
  REHOMED_BENCH_ENTRIES,
};
