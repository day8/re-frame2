#!/usr/bin/env node
'use strict';

// G-18 — library facade isolation (fixture-first; 07 §5, EP-0035, readiness §4).
//
// An advanced build importing EXACTLY ONE view from a multi-view library
// namespace must retain NO unused sibling views, schemas, docs, or dev
// registration. This runner builds the proof-pack single-view + all-views
// advanced bundles and asserts:
//
//   positive control (all-views): every sentinel PRESENT — proves the library
//     views compile and their sentinels are real, DCE-able strings (so a
//     single-view absence is genuine elision, not a renamed literal).
//   isolation (single-view): the imported view's sentinel PRESENT, and every
//     unimported sibling's sentinel ABSENT.
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
// THE ROSTER IS DERIVED FROM THE LIBRARY SOURCE (rf2-r4q98). It used to be two
// hardcoded sentinel constants, which proved the contract for exactly the six
// views they named. rf2-kxork probe-verified the drift: a SEVENTH view added to
// library.cljs and rooted in single_view.cljs was genuinely RETAINED in the
// advanced single-view bundle while this gate printed `0/5` and `G-18 PASS`,
// exit 0. Not a false green on the contract as it stood — six views, all six
// rostered — but a new view was invisible unless the author remembered to edit
// a constant. Enumerating `defview` forms and their sentinel literals means
// adding a view ARMS the gate instead of quietly escaping it. A change under
// implementation/ui/proof-pack/** arms `ui_gates`, so the derived roster is
// re-taken on the very PR that adds the view.
//
// EVERY DERIVED SET IS CHECKED PER ENTRY, NEVER AS A UNION (rf2-nyjml). That
// bead found an anti-vacuity guard that only fired when the union was empty:
// renaming one source root left 140 of 144 files present and the guard stayed
// silent. So each view must carry at least one sentinel of its own, sentinels
// must not be shared between views, and an empty or single-view roster FAILS —
// a derivation that stops matching must RED, not pass (rf2-5jbev, rf2-vxgfnd.167).
//
// `--self-test` drives the derivation over synthetic sources, including the
// seven-view drift case and every anti-vacuity branch. It needs no build, no
// JVM and no toolchain, so it is the arm that runs cheaply everywhere; the
// bundle assertions above are verified by CI. Same shape as
// check-ui-adapter-isolation.cjs --self-test.

const fs = require('fs');
const path = require('path');
const { spawnSync } = require('child_process');
const { classifyReleaseBundle } = require('./lib/read-release-bundle.cjs');

const IMPL = path.resolve(__dirname, '..');
const SINGLE = path.join(IMPL, 'out', 'proof-pack-single');
const ALL = path.join(IMPL, 'out', 'proof-pack-all');

const PROOF_PACK = path.join(IMPL, 'ui', 'proof-pack', 're_frame', 'ui', 'proof_pack');
const LIBRARY_SRC = path.join(PROOF_PACK, 'library.cljs');
const CONSUMER_SRC = path.join(PROOF_PACK, 'single_view.cljs');

// The namespace the single-view consumer imports from. Naming the derivation's
// two INPUTS is not a roster — what is derived is which views exist and which
// one is rooted.
const LIBRARY_NS = 're-frame.ui.proof-pack.library';

// Clojure symbol characters, minus the namespace separator.
const SYM = "[A-Za-z0-9*+!?<>=_'-]+";
const SENTINEL_RE = /rf2-pp-[A-Za-z0-9-]*sentinel/g;

function escapeRe(s) {
  return s.replace(/[.*+?^${}()|[\]\\]/g, '\\$&');
}

class RosterError extends Error {}

function fail(message) {
  throw new RosterError(`G-18 roster: ${message}`);
}

// --- derivation -------------------------------------------------------------

// Enumerate the library's `defview` forms and the sentinel literals each one
// carries. A view's span runs from its own top-level open paren to the next
// top-level form, so a sentinel is attributed to exactly the view that owns it.
function deriveViews(librarySource) {
  const heads = [];
  const re = new RegExp(`^\\(defview\\s+(${SYM})`, 'gm');
  let m;
  while ((m = re.exec(librarySource)) !== null) {
    heads.push({ name: m[1], index: m.index });
  }
  return heads.map(({ name, index }) => {
    // Search from just past this form's open paren for the next line that
    // starts a top-level form.
    const rest = librarySource.slice(index + 1);
    const nextTop = rest.search(/^\(/m);
    const end = nextTop === -1 ? librarySource.length : index + 1 + nextTop;
    const body = librarySource.slice(index, end);
    const sentinels = [...new Set(body.match(SENTINEL_RE) || [])];
    return { name, sentinels };
  });
}

// Which library vars the single-view consumer actually roots. The alias is read
// from the consumer's own `:require`, so renaming it does not silently empty
// the result — an unresolvable alias fails below.
function deriveRooted(consumerSource) {
  const aliasMatch = consumerSource.match(
    new RegExp(`${escapeRe(LIBRARY_NS)}\\s+:as\\s+(${SYM})`),
  );
  if (!aliasMatch) {
    fail(
      `the single-view consumer does not :require ${LIBRARY_NS} with an :as alias. ` +
      'Without it the rooted view cannot be identified, and the gate would be ' +
      'asserting isolation against an empty import.',
    );
  }
  const alias = aliasMatch[1];
  const re = new RegExp(`\\b${escapeRe(alias)}/(${SYM})`, 'g');
  const names = new Set();
  let m;
  while ((m = re.exec(consumerSource)) !== null) names.add(m[1]);
  return { alias, names: [...names] };
}

function deriveRoster(librarySource, consumerSource) {
  const views = deriveViews(librarySource);

  // Anti-vacuity, per entry.
  if (views.length === 0) {
    fail(
      'no `defview` forms found in the proof-pack library. The roster is derived ' +
      'from that source, so an empty derivation means the gate is asserting nothing. ' +
      'Usual cause: the library moved, or `defview` stopped being a top-level form.',
    );
  }
  if (views.length < 2) {
    fail(
      `only ${views.length} view derived (${views[0].name}). The isolation contract ` +
      'needs at least one imported view AND one sibling that must vanish; with a ' +
      'single view there is nothing to prove.',
    );
  }

  const sentinelless = views.filter((v) => v.sentinels.length === 0);
  if (sentinelless.length) {
    fail(
      `${sentinelless.length} view(s) carry no rf2-pp-*-sentinel literal -> ` +
      `${sentinelless.map((v) => v.name).join(', ')}. A view without a sentinel is ` +
      'invisible to a bundle grep, so its retention could never be detected. Give ' +
      'each view a distinct sentinel string in its markup.',
    );
  }

  // Sentinels must be unique across views: a shared literal makes "sibling
  // absent" unprovable, because the imported view keeps it in the bundle.
  const owners = new Map();
  const shared = [];
  for (const v of views) {
    for (const s of v.sentinels) {
      if (owners.has(s)) shared.push(`${s} (${owners.get(s)} and ${v.name})`);
      else owners.set(s, v.name);
    }
  }
  if (shared.length) {
    fail(
      `sentinel literal(s) shared between views -> ${shared.join('; ')}. Each view ` +
      'needs its own literal, or a sibling\'s absence cannot be distinguished from ' +
      'the imported view\'s presence.',
    );
  }

  const { alias, names: rooted } = deriveRooted(consumerSource);
  const known = new Set(views.map((v) => v.name));
  const unknown = rooted.filter((n) => !known.has(n));
  const rootedViews = rooted.filter((n) => known.has(n));

  if (rootedViews.length === 0) {
    fail(
      `the single-view consumer roots no library view (alias ${alias}/, saw ` +
      `${rooted.length ? rooted.join(', ') : 'nothing'}). It must root EXACTLY ONE, ` +
      'or the single-view bundle proves nothing about isolation.',
    );
  }
  if (rootedViews.length > 1) {
    fail(
      `the single-view consumer roots ${rootedViews.length} views -> ` +
      `${rootedViews.join(', ')}. G-18 is the ONE-view contract; rooting more makes ` +
      'the sibling-absence assertion vacuous for each extra import.',
    );
  }
  if (unknown.length) {
    // Not fatal on its own — `register-pp!` and friends are legitimate non-view
    // references — but a rooted name that LOOKS like a view and is not derived
    // is exactly the drift this derivation exists to catch, so it is reported.
    process.stdout.write(
      `  note: consumer references ${unknown.length} non-view library var(s): ${unknown.join(', ')}\n`,
    );
  }

  const importedView = views.find((v) => v.name === rootedViews[0]);
  const siblingViews = views.filter((v) => v.name !== importedView.name);

  const imported = importedView.sentinels;
  const siblings = siblingViews.flatMap((v) => v.sentinels);

  if (siblings.length === 0) {
    fail('no sibling sentinels derived — nothing would be asserted absent.');
  }

  return {
    views,
    alias,
    importedView: importedView.name,
    imported,
    siblings,
    all: [...imported, ...siblings],
  };
}

function readSource(abs, label) {
  let src;
  try {
    src = fs.readFileSync(abs, 'utf8');
  } catch (err) {
    fail(
      `${label} is missing or unreadable at ${path.relative(IMPL, abs)} ` +
      `(${err.code || err.message}). A vanished derivation input must RED, not ` +
      'silently pass (rf2-5jbev).',
    );
  }
  if (!src.trim()) {
    fail(`${label} is empty at ${path.relative(IMPL, abs)}.`);
  }
  return src;
}

function rosterFromRepo() {
  return deriveRoster(
    readSource(LIBRARY_SRC, 'proof-pack library'),
    readSource(CONSUMER_SRC, 'proof-pack single-view consumer'),
  );
}

// --- the gate ---------------------------------------------------------------

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

// The contract itself, as a pure function of the derived roster and the two
// bundle texts. Pure so the self-test can drive every red path — including the
// seven-view retention rf2-kxork probed — without a JVM or an advanced build.
function evaluateBundles(roster, allBlob, singleBlob) {
  const problems = [];

  // Positive control — non-vacuity: every sentinel present in the all-views build.
  for (const s of roster.all) {
    if (!allBlob.includes(s)) {
      problems.push(`positive control: ${s} missing from all-views bundle (sentinel not real/DCE-able)`);
    }
  }

  // Isolation — the contract.
  const importedMissing = roster.imported.filter((s) => !singleBlob.includes(s));
  for (const s of importedMissing) {
    problems.push(`isolation: imported view sentinel ${s} absent from single-view bundle (import not rooted)`);
  }
  const retained = roster.siblings.filter((s) => singleBlob.includes(s));

  if (retained.length) {
    problems.push(
      `isolation: ${retained.length} unimported sibling view(s) retained in the advanced single-view ` +
      `bundle -> ${retained.join(', ')}. A single-view import must not drag its siblings into the ` +
      `bundle (EP-0035 readiness §4). This is the REGRESSION #6195 fixed: unreferenced view defs bound ` +
      `to React.memo(...) stop being DCE'd when the emitted view def / memo-view loses its purity ` +
      `annotation, so suspect a change to the defview emitter (compiler/emit_cljs.cljc) or to ` +
      `memo-view (ui/runtime.cljs).`);
  }

  return { problems, retained, importedMissing };
}

function main() {
  const roster = rosterFromRepo();
  console.log('=== G-18 derived roster ===');
  console.log(`  library views: ${roster.views.length} -> ${roster.views.map((v) => v.name).join(', ')}`);
  console.log(`  rooted by the single-view consumer: ${roster.importedView} (alias ${roster.alias}/)`);
  console.log(`  imported sentinel(s): ${roster.imported.join(', ')}`);
  console.log(`  sibling sentinel(s): ${roster.siblings.length}`);

  shadow('release', 'proof-pack-single', 'proof-pack-all');

  const allBlob = bundleBlobOrThrow('all-views', ALL);
  const singleBlob = bundleBlobOrThrow('single-view', SINGLE);

  const { problems, retained, importedMissing } = evaluateBundles(roster, allBlob, singleBlob);

  console.log('=== G-18 library facade isolation ===');
  console.log(`  positive control (all-views): ${roster.all.length} sentinels`);
  console.log(`  single-view import: ${roster.importedView} present=${importedMissing.length === 0}`);
  console.log(`  siblings retained in single-view: ${retained.length}/${roster.siblings.length}` +
    (retained.length ? ` -> ${retained.join(', ')}` : ''));

  if (problems.length) {
    console.error('\n=== G-18 FAIL ===');
    for (const p of problems) console.error(`  - ${p}`);
    process.exit(1);
  }
  console.log('G-18 PASS — a single-view import retains no unused siblings.');
}

// --- self-test (hermetic; no build artefacts, no JVM, no shell) --------------

function selfTest() {
  let failed = 0;
  const check = (name, fn) => {
    try {
      fn();
      console.log(`  ok   ${name}`);
    } catch (err) {
      failed += 1;
      console.error(`  FAIL ${name}: ${err && err.message ? err.message : err}`);
    }
  };
  const assert = (cond, msg) => {
    if (!cond) throw new Error(msg);
  };
  const expectFail = (fn, needle) => {
    let threw = null;
    try {
      fn();
    } catch (err) {
      threw = err;
    }
    assert(threw instanceof RosterError, `expected a RosterError, got ${threw || 'no throw'}`);
    assert(
      threw.message.includes(needle),
      `expected the failure to mention "${needle}", got: ${threw.message}`,
    );
  };

  const view = (name, sentinel) =>
    `(defview ${name} [{:keys [x]}]\n  [:div {:data-pp "${sentinel}"} x])\n`;
  const lib = (...bodies) => `(ns re-frame.ui.proof-pack.library)\n\n${bodies.join('\n')}`;
  const consumer = (rooted, alias = 'lib') =>
    `(ns re-frame.ui.proof-pack.single-view\n  (:require [${LIBRARY_NS} :as ${alias}]))\n` +
    `(defn -main []\n  (unchecked-set js/globalThis "__X__" ${alias}/${rooted}))\n`;

  const SIX = [
    view('alpha', 'rf2-pp-alpha-sentinel'),
    view('beta', 'rf2-pp-beta-sentinel'),
    view('gamma', 'rf2-pp-gamma-sentinel'),
    view('delta', 'rf2-pp-delta-sentinel'),
    view('epsilon', 'rf2-pp-epsilon-sentinel'),
    view('zeta', 'rf2-pp-zeta-sentinel'),
  ];

  console.log('[ui-facade-isolation] self-test');

  check('derives every view and splits imported from siblings', () => {
    const r = deriveRoster(lib(...SIX), consumer('alpha'));
    assert(r.views.length === 6, `expected 6 views, got ${r.views.length}`);
    assert(r.importedView === 'alpha', `expected alpha imported, got ${r.importedView}`);
    assert(r.imported.join() === 'rf2-pp-alpha-sentinel', `imported: ${r.imported}`);
    assert(r.siblings.length === 5, `expected 5 siblings, got ${r.siblings.length}`);
    assert(!r.siblings.includes('rf2-pp-alpha-sentinel'), 'imported must not be a sibling');
  });

  // THE DRIFT CASE rf2-kxork PROVED, now covered: a seventh view is rostered
  // automatically, so it can no longer be retained behind a green gate.
  check('a SEVENTH view is rostered automatically (the rf2-kxork drift case)', () => {
    const r = deriveRoster(lib(...SIX, view('eta', 'rf2-pp-eta-sentinel')), consumer('alpha'));
    assert(r.views.length === 7, `expected 7 views, got ${r.views.length}`);
    assert(r.siblings.includes('rf2-pp-eta-sentinel'), 'the 7th view must appear as a sibling');
    assert(r.siblings.length === 6, `expected 6 siblings, got ${r.siblings.length}`);
  });

  check('the rooted view is followed, not assumed', () => {
    const r = deriveRoster(lib(...SIX), consumer('gamma'));
    assert(r.importedView === 'gamma', `expected gamma, got ${r.importedView}`);
    assert(r.imported.join() === 'rf2-pp-gamma-sentinel', `imported: ${r.imported}`);
    assert(r.siblings.includes('rf2-pp-alpha-sentinel'), 'alpha becomes a sibling');
  });

  check('a renamed :as alias is followed, not hardcoded', () => {
    const r = deriveRoster(lib(...SIX), consumer('beta', 'pplib'));
    assert(r.alias === 'pplib', `expected alias pplib, got ${r.alias}`);
    assert(r.importedView === 'beta', `expected beta, got ${r.importedView}`);
  });

  check('a multi-form defview (props schema before the arglist) is derived', () => {
    const schemaView =
      '(defview described\n  {:props [:map [:label {:default "rf2-pp-described-sentinel"} :string]]}\n' +
      '  [{:keys [label]}]\n  [:label {:data-pp "rf2-pp-described-sentinel"} label])\n';
    const r = deriveRoster(lib(...SIX, schemaView), consumer('alpha'));
    assert(r.views.some((v) => v.name === 'described'), 'the schema-carrying view must be derived');
    assert(r.siblings.includes('rf2-pp-described-sentinel'), 'its sentinel must be rostered once');
    assert(
      r.siblings.filter((s) => s === 'rf2-pp-described-sentinel').length === 1,
      'a sentinel repeated within one view must be deduped',
    );
  });

  // Anti-vacuity, per entry. Each of these must RED rather than pass quietly.
  check('an empty roster FAILS rather than passing vacuously', () => {
    expectFail(() => deriveRoster('(ns re-frame.ui.proof-pack.library)\n', consumer('alpha')),
      'no `defview` forms found');
  });

  check('a single-view roster FAILS (nothing to prove absent)', () => {
    expectFail(() => deriveRoster(lib(view('alpha', 'rf2-pp-alpha-sentinel')), consumer('alpha')),
      'only 1 view derived');
  });

  check('ONE view missing its sentinel FAILS, named — not a union check (rf2-nyjml)', () => {
    const noSentinel = '(defview mute [{:keys [x]}]\n  [:div x])\n';
    expectFail(() => deriveRoster(lib(...SIX, noSentinel), consumer('alpha')),
      'carry no rf2-pp-*-sentinel literal');
  });

  check('a sentinel shared between two views FAILS', () => {
    expectFail(() => deriveRoster(lib(...SIX, view('clone', 'rf2-pp-alpha-sentinel')), consumer('alpha')),
      'shared between views');
  });

  check('a consumer rooting NO view FAILS', () => {
    const none = `(ns re-frame.ui.proof-pack.single-view\n  (:require [${LIBRARY_NS} :as lib]))\n(defn -main [] nil)\n`;
    expectFail(() => deriveRoster(lib(...SIX), none), 'roots no library view');
  });

  check('a consumer rooting TWO views FAILS', () => {
    const two =
      `(ns re-frame.ui.proof-pack.single-view\n  (:require [${LIBRARY_NS} :as lib]))\n` +
      '(defn -main []\n  (unchecked-set js/globalThis "__A__" lib/alpha)\n' +
      '  (unchecked-set js/globalThis "__B__" lib/beta))\n';
    expectFail(() => deriveRoster(lib(...SIX), two), 'roots 2 views');
  });

  check('a consumer with no :as alias FAILS', () => {
    const noAlias = `(ns re-frame.ui.proof-pack.single-view\n  (:require [${LIBRARY_NS}]))\n(defn -main [] nil)\n`;
    expectFail(() => deriveRoster(lib(...SIX), noAlias), 'does not :require');
  });

  check('a missing derivation input REDs rather than passing (rf2-5jbev)', () => {
    expectFail(() => readSource(path.join(PROOF_PACK, 'no-such-file.cljs'), 'proof-pack library'),
      'missing or unreadable');
  });

  // Finally: the REAL sources. Structural assertions only — deliberately not a
  // pinned view count, so adding a view arms the gate instead of reddening this.
  check('the real proof-pack sources derive a coherent roster', () => {
    const r = rosterFromRepo();
    assert(r.views.length >= 2, `expected at least 2 real views, got ${r.views.length}`);
    assert(r.imported.length >= 1, 'the imported view must carry a sentinel');
    assert(
      r.siblings.length >= r.views.length - 1,
      `expected at least ${r.views.length - 1} sibling sentinels, got ${r.siblings.length}`,
    );
    assert(!r.siblings.some((s) => r.imported.includes(s)), 'imported and sibling sets must be disjoint');
    console.log(`       real roster: ${r.views.length} views, imported=${r.importedView}, ` +
      `siblings=${r.siblings.length}`);
  });

  // --- the bundle contract, driven over synthetic blobs ---------------------
  //
  // These are G-18's own arms, run without a build. `bundle()` fakes an
  // advanced bundle as the text of the sentinels it retained.
  const bundle = (...sentinels) => `/*minified*/${sentinels.map((s) => `"${s}"`).join(',')}`;
  const SEVEN = [...SIX, view('eta', 'rf2-pp-eta-sentinel')];

  check('BUNDLE: a clean single-view import passes', () => {
    const r = deriveRoster(lib(...SIX), consumer('alpha'));
    const { problems } = evaluateBundles(r, bundle(...r.all), bundle(...r.imported));
    assert(problems.length === 0, `expected no problems, got: ${problems.join(' | ')}`);
  });

  check('BUNDLE: a retained sibling FAILS, naming it', () => {
    const r = deriveRoster(lib(...SIX), consumer('alpha'));
    const single = bundle(...r.imported, 'rf2-pp-gamma-sentinel');
    const { problems, retained } = evaluateBundles(r, bundle(...r.all), single);
    assert(retained.join() === 'rf2-pp-gamma-sentinel', `retained: ${retained}`);
    assert(problems.some((p) => p.includes('rf2-pp-gamma-sentinel')), 'the retained sibling must be named');
  });

  // THE rf2-kxork PROBE, now caught. A seventh view rooted alongside the
  // import stays in the single-view bundle. Under the retired hardcoded roster
  // this printed `0/5` and `G-18 PASS`, exit 0 — the view was invisible.
  check('BUNDLE: a retained SEVENTH view FAILS (was a silent pass before rf2-r4q98)', () => {
    const r = deriveRoster(lib(...SEVEN), consumer('alpha'));
    const single = bundle(...r.imported, 'rf2-pp-eta-sentinel');
    const { problems, retained } = evaluateBundles(r, bundle(...r.all), single);
    assert(retained.includes('rf2-pp-eta-sentinel'), 'the 7th view must be reported retained');
    assert(problems.some((p) => p.includes('rf2-pp-eta-sentinel')), 'the 7th view must be named');

    // Negative control: the retired roster named only the original six, so the
    // very same bundle produced no problems at all. That is the drift.
    const legacy = { ...r, siblings: r.siblings.filter((s) => s !== 'rf2-pp-eta-sentinel') };
    legacy.all = [...legacy.imported, ...legacy.siblings];
    const legacyResult = evaluateBundles(legacy, bundle(...legacy.all), single);
    assert(
      legacyResult.problems.length === 0,
      `the hardcoded roster PASSED this bundle — that is the gap being closed; got: ${legacyResult.problems.join(' | ')}`,
    );
  });

  check('BUNDLE: an unrooted import FAILS', () => {
    const r = deriveRoster(lib(...SIX), consumer('alpha'));
    const { problems, importedMissing } = evaluateBundles(r, bundle(...r.all), bundle());
    assert(importedMissing.length === 1, `importedMissing: ${importedMissing}`);
    assert(problems.some((p) => p.includes('import not rooted')), 'must report the unrooted import');
  });

  check('BUNDLE: a missing positive control FAILS (sentinel not real/DCE-able)', () => {
    const r = deriveRoster(lib(...SIX), consumer('alpha'));
    const all = bundle(...r.all.filter((s) => s !== 'rf2-pp-delta-sentinel'));
    const { problems } = evaluateBundles(r, all, bundle(...r.imported));
    assert(
      problems.some((p) => p.includes('positive control') && p.includes('rf2-pp-delta-sentinel')),
      `expected a positive-control problem, got: ${problems.join(' | ')}`,
    );
  });

  if (failed > 0) {
    console.error(`[ui-facade-isolation] self-test FAILED: ${failed}`);
    process.exit(1);
  }
  console.log('[ui-facade-isolation] self-test PASS (derived roster + per-entry anti-vacuity + bundle arms)');
}

if (require.main === module) {
  try {
    if (process.argv.includes('--self-test')) selfTest();
    else main();
  } catch (err) {
    if (err instanceof RosterError) {
      console.error(`\n=== G-18 FAIL ===\n  - ${err.message}`);
      process.exit(1);
    }
    throw err;
  }
}

module.exports = { deriveRoster, deriveViews, deriveRooted, rosterFromRepo, evaluateBundles };
