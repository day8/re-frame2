#!/usr/bin/env node
/*
 * THE NATIVE TIER'S ZERO-RENT PROOF, read off a real production bundle
 * (rf2-hic-034).
 *
 * > 6. The native namespace is separately reachable. An interpreted-only
 * >    production dependency graph and bundle contain neither native-tier
 * >    runtime nor UIx code.
 * >
 * >   — the native-boundary law,
 * >     `docs/design/hicasso/product/lanes/design-laws.md`
 *
 * Clause 6 has TWO halves and they are checked in two places, because
 * neither instrument can answer the other's question:
 *
 *   - the DEPENDENCY GRAPH is a property of the source, and
 *     `check_optional_module_reachability.py` decides it over `:require`
 *     forms — exhaustively, including the shapes that leave no string in
 *     a bundle at all (see "What a string scan cannot see" below);
 *   - the BUNDLE is this file. It reads the one build in the repo that
 *     compiles the package the way a consumer ships it — `:hicasso-release`,
 *     `:advanced` + `goog.DEBUG=false`, entry `re-frame.hicasso.consumer-app`
 *     — and asks whether any native-tier runtime survived into it.
 *
 * The source-side gate is the stronger of the two and is not made
 * redundant by this one. What this one adds is confidence about the
 * COMPILER and the LINKER: that an unreferenced namespace really does
 * leave nothing behind after Closure has run, on the real graph, at the
 * real optimisation level.
 *
 * ## Unique sentinels, never a public-name grep
 *
 * Grepping the bundle for `native` proves nothing — the word occurs in
 * React's own event plumbing and in the codec's prose. Each row below is
 * a string that ONE surface emits and nothing else does, so a hit names
 * WHICH surface leaked rather than merely that something did.
 *
 * A sentinel is only worth scanning for if it is LOAD-BEARING. A marker
 * planted beside the code would be dropped by Closure whether or not the
 * isolation held, and the gate would pass for the wrong reason. Both
 * sentinels here are strings their surface cannot function without:
 *
 *   - `rf2:hicasso-native-tier` is the own-property NAME
 *     `native/component` writes with `unchecked-set` and `native/marker`
 *     reads back with `unchecked-get`. Closure cannot rename a string
 *     used as a computed property key and cannot delete it while the
 *     code that writes it is reachable. `native.cljc` says so at
 *     [[tier-sentinel]], where it also names this bead as the proof.
 *   - `rf.error/hicasso-native-` is the refusal-id FAMILY the tier mints
 *     — all seven of its ids share it and no other surface uses it.
 *
 * ## Two sentinels because there are two reachability shapes
 *
 * They are not belt-and-braces. Closure's dead-code elimination works
 * per function, so which strings survive depends on WHICH of the tier a
 * consumer touched:
 *
 *   - a consumer who writes `n/defcomponent` (or `n/memo` / `n/lazy`)
 *     drags `component` in, and with it the marker string — while the
 *     refusal ids may fold away entirely, since `checked`'s per-child
 *     fence is `debug-enabled?`-gated;
 *   - a consumer who writes `(n/props …)` drags `props*` and therefore
 *     `prop-slots` in, whose last three refusals are UNGATED and hold on
 *     both paths — while `component` is never called and the marker
 *     string is absent.
 *
 * Either sentinel alone is green against the other's leak. Both together
 * cover every part of the tier that has a production footprint.
 *
 * ## What a string scan cannot see, stated rather than left to be found
 *
 * There is a third shape and it leaves NOTHING to find: a consumer who
 * writes only `n/$` with literal props. The macro lowers the props at
 * expansion, `checked` folds away under `goog.DEBUG=false`, and what
 * remains is `react/createElement` — React's own function, which every
 * bundle already has. Past DCE, `n/$` IS `createElement`.
 *
 * That is not a hole in the zero-rent claim; it is the claim at its
 * strongest, and it is why the source-side gate exists and why this file
 * does not pretend to subsume it. A scan that reported green there would
 * be right by accident, so the sentence is written here instead of a row
 * nobody can falsify.
 *
 * ## Reachable positive controls
 *
 * An absence check over a bundle that never compiled anything is a green
 * that means nothing, and it is the failure this class of gate is most
 * exposed to. So each sentinel is PAIRED with a control that must be
 * PRESENT, chosen so the pair differs by REACHABILITY and by nothing
 * else:
 *
 *   - `hicassoBoundary` (present) against `rf2:hicasso-native-tier`
 *     (absent) — two own-property marker names, both written with
 *     `unchecked-set` onto a freshly minted component, one in a
 *     namespace the public door leads to and one in a namespace it never
 *     names. That pairing is what makes the absence a statement about
 *     reachability rather than about compilation.
 *   - `rf.error/hicasso-empty-vector` (present) against
 *     `rf.error/hicasso-native-` (absent) — a shipped refusal id minted
 *     by `impl.error/fail!` against the tier's own family minted by the
 *     same `fail!`. If `fail!` had been dropped whole, both would be
 *     absent and the first is what says so.
 *   - `rf-uix-sub-` (present) — and this one is the interesting control.
 *     See the next section.
 *
 * ## `zero UIx` does not hold, and the reason is worth a control
 *
 * Clause 6 says an interpreted-only bundle contains no UIx code. THE
 * MEASURED BUNDLE CONTAINS UIx, and it is not a defect: Hicasso ships no
 * reactive adapter of its own — `consumer_app.cljs`'s docstring says so
 * where the consumer is standing — and the three adapters a consumer may
 * pick from are Reagent, reagent-slim and UIx. Every one of them is a
 * view library. The exemplar picks UIx, so `re-frame.adapter.uix` is on
 * the entry's require list and `uix.core` comes with it.
 *
 * A row asserting `no uix` here would be red on a correct build, and a
 * row asserting it against some other fixture would be asserting a fact
 * about which adapter the fixture happened to choose. So the string is
 * carried as a CONTROL instead, where it earns its place twice: it
 * proves a whole React view library and a whole reactive substrate
 * really compiled into this bundle, which makes the native tier's
 * absence a much sharper reading than it would be from a bundle that
 * compiled almost nothing.
 *
 * The clause's UIx half is therefore a claim about a consumer's
 * dependency graph — *nothing Hicasso makes you take drags UIx in* —
 * and that is the source-side gate's kind of question, not a bundle's.
 * It is reported rather than quietly re-scoped.
 *
 * ## The live half is a test, not a comment
 *
 * A sentinel that has quietly stopped being emitted is absent from every
 * bundle for a reason that has nothing to do with isolation. The roster
 * premise below is checked against the source before the bundle is
 * opened, so a rename fails the gate rather than reporting a green
 * absence for a string nobody emits any more; and
 * `re-frame.hicasso.native-fence-cljs-test`'s
 * `the-tier-sentinel-is-reachable-rather-than-a-dead-literal` asserts in
 * the ordinary `goog.DEBUG` node lane that the marker is what a LIVE
 * `n/defcomponent` really stamps. Surface on, string present; surface
 * unreachable, string gone — and neither half is evidence alone.
 */

'use strict';

const fs = require('fs');
const path = require('path');

const HERE = path.dirname(path.resolve(__filename));
const PACKAGE_ROOT = path.dirname(HERE);
const IMPL_ROOT = path.dirname(PACKAGE_ROOT);

const BUNDLE = path.join(IMPL_ROOT, 'out', 'hicasso-release', 'main.js');

// ---------------------------------------------------------------------------
// The roster
//
// `sentinel` is scanned for in the bundle and must be ABSENT. `source` is
// where it is emitted from, and `premise` is the text that must still be
// found there — usually the sentinel itself, spelled the way the source
// spells it.
// ---------------------------------------------------------------------------

const SENTINELS = [
  {
    surface: 'native tier — the marker every minted component carries (rf2-hic-030)',
    sentinel: 'rf2:hicasso-native-tier',
    source: 'src/re_frame/hicasso/native.cljc',
    premise: '"rf2:hicasso-native-tier"',
    why:
      'The own-property name `native/component` writes with `unchecked-set` ' +
      'onto the author\'s own function, and `native/marker` reads back with ' +
      '`unchecked-get`. It is in this bundle if and only if `defcomponent`, ' +
      '`n/memo` or `n/lazy` is reachable from the entry.',
    remedy:
      'Find the `:require` of `re-frame.hicasso.native` that made the tier ' +
      'reachable from the public door. Nothing under implementation/hicasso/src/ ' +
      'may name it; an application requires it directly, in the region that ' +
      'wants it.',
  },
  {
    surface: 'native tier — the refusal-id family the fence mints (rf2-hic-030)',
    sentinel: 'rf.error/hicasso-native-',
    source: 'src/re_frame/hicasso/native.cljc',
    premise: ':rf.error/hicasso-native-slot-collision',
    why:
      'The prefix all seven of the tier\'s refusal ids share and no other ' +
      'surface uses. It reaches a bundle by a DIFFERENT route from the marker ' +
      'above: `prop-slots`\'s last three refusals are ungated, so a consumer ' +
      'who writes `(n/props …)` and never a `defcomponent` leaks these and ' +
      'not the marker. Either sentinel alone is green against the other\'s ' +
      'leak, which is why there are two.',
    remedy:
      'Same as above — the tier became reachable. `props*` is the runtime ' +
      'half of `n/$`\'s props rule and only a dynamic props operand calls it.',
  },
];

const CONTROLS = [
  {
    control: 're-frame.hicasso.consumer-app/app',
    source: 'test/re_frame/hicasso/consumer_app.cljs',
    premise: '(h/defview app',
    proves:
      'the release entry\'s `h/defview` really compiled — `mint-view!` stamps ' +
      'this name as the boundary\'s `displayName`, unconditionally. Without ' +
      'it the absences above would be the absences of a bundle that compiled ' +
      'no declaration at all.',
  },
  {
    control: 'hicassoBoundary',
    source: 'src/re_frame/hicasso/impl/codec.cljs',
    premise: '(unchecked-set f "hicassoBoundary" true)',
    proves:
      'the interpreted tier\'s own marker machinery compiled. It is the ' +
      'REACHABLE counterpart of `rf2:hicasso-native-tier`: same idiom, same ' +
      '`unchecked-set`, same freshly minted component, and the only ' +
      'difference between the two is whether the public door\'s require ' +
      'graph leads to the namespace that writes it. That is what makes "no ' +
      'native marker" a statement about reachability rather than about ' +
      'compilation.',
  },
  {
    control: 'rf.error/hicasso-empty-vector',
    source: 'src/re_frame/hicasso/impl/codec.cljs',
    premise: ':rf.error/hicasso-empty-vector',
    proves:
      '`impl.error/fail!` and a shipped refusal id compiled. The tier\'s ' +
      'family above is minted by the same `fail!`; if it had been dropped ' +
      'entirely both would be absent, and this is what says so.',
  },
  {
    control: 'rf-uix-sub-',
    source: '../adapters/uix/src/re_frame/adapter/uix.cljs',
    premise: ':gensym-prefix-sub     "rf-uix-sub-"',
    proves:
      'a whole React view library and a whole reactive substrate really ' +
      'compiled into this bundle — the UIx adapter the exemplar installs, ' +
      'which Hicasso deliberately ships none of its own. It is the reason ' +
      'the absences above are worth reading: a bundle carrying UIx, React, ' +
      'core and the interpreted codec, and STILL carrying no native-tier ' +
      'runtime, has isolated the tier rather than compiled nothing. It is ' +
      'also the honest record that clause 6\'s "nor UIx code" does not hold ' +
      'of this fixture and why — see the header.',
  },
];

// ---------------------------------------------------------------------------
// The scan
// ---------------------------------------------------------------------------

/** Answer the problems `bundle` has, as a list of strings. */
function scan(bundle) {
  const problems = [];

  for (const c of CONTROLS) {
    if (!bundle.includes(c.control)) {
      problems.push(
        `POSITIVE CONTROL ABSENT: ${c.control}\n` +
        `    It proves ${c.proves}\n` +
        '    Every absence this gate reports below is therefore ' +
        'unfalsifiable. Fix this first.'
      );
    }
  }

  for (const s of SENTINELS) {
    if (bundle.includes(s.sentinel)) {
      problems.push(
        `NATIVE TIER LEAKED: ${s.surface}\n` +
        `    sentinel: ${JSON.stringify(s.sentinel)}\n` +
        `    ${s.why}\n` +
        `    ${s.remedy}`
      );
    }
  }

  return problems;
}

/** Answer the roster's own broken premises, as a list of strings. */
function checkPremises() {
  const problems = [];
  const rows = [
    ...SENTINELS.map((s) => ({ text: s.premise, source: s.source, of: `sentinel ${JSON.stringify(s.sentinel)}` })),
    ...CONTROLS.map((c) => ({ text: c.premise, source: c.source, of: `positive control ${JSON.stringify(c.control)}` })),
  ];

  for (const row of rows) {
    const file = path.join(PACKAGE_ROOT, row.source);
    if (!fs.existsSync(file)) {
      problems.push(
        `ROSTER PREMISE BROKEN: the source of ${row.of} is gone — ${row.source}\n` +
        '    A scan for a string nobody emits any more is a green that means nothing.'
      );
      continue;
    }
    if (!fs.readFileSync(file, 'utf8').includes(row.text)) {
      problems.push(
        `ROSTER PREMISE BROKEN: ${row.source} no longer contains ${JSON.stringify(row.text)},\n` +
        `    which is where ${row.of} comes from. Either the string was renamed — in ` +
        'which case rename it here too — or the surface was removed, in which case ' +
        'drop the row.'
      );
    }
  }

  return problems;
}

// ---------------------------------------------------------------------------
// The self-test — because a scanner that stopped seeing reports a clean
// bundle forever, and this one's whole claim is about what it does not find
// ---------------------------------------------------------------------------

function selfTest() {
  const green = CONTROLS.map((c) => c.control).join(' … ');

  const clean = scan(green);
  assert(clean.length === 0, `a bundle with every control and no sentinel is green: ${clean}`);

  // Every sentinel is seen, individually, and names its own surface.
  for (const s of SENTINELS) {
    const problems = scan(`${green} … ${s.sentinel} …`);
    assert(problems.length === 1, `one problem for ${s.sentinel}: ${problems}`);
    assert(problems[0].includes('NATIVE TIER LEAKED'), problems[0]);
    assert(problems[0].includes(s.surface), problems[0]);
    assert(problems[0].includes(s.sentinel), problems[0]);
  }

  // The scan FAILS CLOSED: a bundle with no controls in it is refused even
  // though every sentinel is absent from it, which is the shape an empty or
  // wrong bundle has.
  const empty = scan('');
  assert(empty.length === CONTROLS.length, `an empty bundle fails on every control: ${empty}`);
  for (const p of empty) assert(p.includes('POSITIVE CONTROL ABSENT'), p);

  // Each control is missed on its own, so a control that quietly stopped
  // being emitted cannot hide behind its siblings.
  for (const c of CONTROLS) {
    const others = CONTROLS.filter((o) => o !== c).map((o) => o.control).join(' … ');
    const problems = scan(others);
    assert(problems.length === 1, `one problem for a missing ${c.control}: ${problems}`);
    assert(problems[0].includes(c.control), problems[0]);
  }

  // The scan is not TOO EAGER, and this is the direction a guard is almost
  // never tested in. Each string below is shape-adjacent to a sentinel and
  // is legal in a consumer bundle; none may be mistaken for one.
  //
  //   - the tier's own name in PROSE reaches no bundle, but the codec's
  //     docstrings name it and a scanner reading source rather than the
  //     artefact would trip on them;
  //   - `hicassoBoundary` is the interpreted marker, one word away from
  //     the native one;
  //   - the other refusal families share `rf.error/hicasso-` with the
  //     tier's, and the tier's suffix is the whole of the difference.
  const legal = [
    `${green} … re-frame.hicasso.native/declared-server …`,   // a docstring link
    `${green} … hicassoBody hicassoBoundary hicassoOwner …`,   // neighbouring markers
    `${green} … rf.error/hicasso-empty-vector rf.error/hicasso-bad-head …`,
    `${green} … "native" nativeEvent isComposing …`,           // React's own plumbing
  ];
  for (const bundle of legal) {
    const problems = scan(bundle);
    assert(problems.length === 0, `a legal bundle stays green: ${problems}`);
  }

  // The roster's premises hold against the real tree — a renamed sentinel
  // fails here rather than reporting a green absence.
  const premises = checkPremises();
  assert(premises.length === 0, `roster premises hold:\n${premises.join('\n')}`);

  // No sentinel may be a substring of another, or of a control: a hit has
  // to name one surface.
  const all = [...SENTINELS.map((s) => s.sentinel), ...CONTROLS.map((c) => c.control)];
  for (const a of all) {
    for (const b of all) {
      if (a !== b) assert(!a.includes(b), `${JSON.stringify(a)} contains ${JSON.stringify(b)}`);
    }
  }

  process.stdout.write(
    `hicasso bundle isolation: self-test OK ` +
    `(${SENTINELS.length} sentinels, ${CONTROLS.length} positive controls)\n`
  );
  return 0;
}

function assert(ok, message) {
  if (!ok) {
    process.stderr.write(`hicasso bundle isolation: SELF-TEST FAILED\n  ${message}\n`);
    process.exit(1);
  }
}

// ---------------------------------------------------------------------------

function fail(problems) {
  process.stderr.write('hicasso bundle isolation: FAIL\n');
  for (const p of problems) process.stderr.write(`  ${p}\n`);
  return 1;
}

function main(argv) {
  if (argv.includes('--self-test')) return selfTest();

  const premises = checkPremises();
  if (premises.length > 0) return fail(premises);

  if (!fs.existsSync(BUNDLE)) {
    return fail([
      `MISSING RELEASE BUNDLE: ${path.relative(IMPL_ROOT, BUNDLE)}\n` +
      '    Run `npm run build:hicasso-release`, which compiles it.',
    ]);
  }

  const problems = scan(fs.readFileSync(BUNDLE, 'utf8'));
  if (problems.length > 0) return fail(problems);

  process.stdout.write(
    `OK: no native-tier runtime in the interpreted-only :advanced / goog.DEBUG=false ` +
    `bundle (${SENTINELS.length} sentinels absent, ${CONTROLS.length} positive controls present).\n`
  );
  return 0;
}

process.exit(main(process.argv.slice(2)));
