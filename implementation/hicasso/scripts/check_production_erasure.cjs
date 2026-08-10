#!/usr/bin/env node
/*
 * THE PRODUCTION-ERASURE PROOF for implementation/hicasso/ (rf2-hic-024).
 *
 * Spec SN §3.6 buys Hicasso's dev affordances — coordinates, complaint
 * detail, the evidence projection, the test kit's route back to a body —
 * with an erasure: under `:advanced` + `goog.DEBUG=false` none of them
 * reaches the artefact a consumer ships. This gate is the proof, read off
 * the one build in the repo that compiles the package the way a consumer
 * ships it (`:hicasso-release`, entry `re-frame.hicasso.consumer-app`,
 * PR #7823).
 *
 * ## Unique sentinels, never a public-name grep
 *
 * Grepping a release bundle for `hicasso` proves nothing: the public names
 * legitimately survive, which is the whole point of shipping the package.
 * So every row of the roster below is a string that ONE dev-only surface
 * emits and nothing else does — a hit names WHICH surface leaked, not
 * merely that something did.
 *
 * A sentinel is only worth scanning for if it is LOAD-BEARING in the
 * surface it stands for: a marker planted beside the code would be dropped
 * by Closure whether or not the erasure held, and the gate would pass for
 * the wrong reason. Every sentinel here is a string the surface cannot
 * function without — an own-property name written and read by name, a
 * stable refusal id, a schema version pin, a console message.
 *
 * ## Reachable positive controls
 *
 * An absence check over a bundle that never compiled the surface is a
 * green that means nothing, and it is the failure this gate is most
 * exposed to. So each sentinel is PAIRED with a control that must be
 * PRESENT — chosen so that the control and the sentinel differ by the
 * `goog.DEBUG` gate and by nothing else wherever that pairing is
 * available:
 *
 *   - `hicassoBody` (dev-only) against `hicassoBoundary` (ungated) — two
 *     adjacent own-property slots in `impl/codec.cljs`, both written with
 *     `unchecked-set` on the same minted head, one behind the gate.
 *   - `hicasso-refusal-incomplete` (dev-only) against
 *     `rf.error/hicasso-empty-vector` (ungated) — a refusal minted inside
 *     `fail!`'s `debug-enabled?` guard against one minted by `fail!` on
 *     the shipped path. If `fail!` had not compiled at all, both would be
 *     absent and the second says so.
 *   - the entry's own view name, which `mint-view!` stamps
 *     unconditionally, proving the bundle really compiled a declaration.
 *
 * ## The live half is a test, not a comment
 *
 * A sentinel that has quietly stopped being emitted is absent from every
 * bundle for a reason that has nothing to do with erasure. That is why
 * `re-frame.hicasso.erasure-sentinels-cljs-test` exists: it runs in the
 * ordinary `goog.DEBUG` node lane and asserts each string below is what a
 * LIVE surface produces. The two halves are the A/B — surface on, string
 * present; surface off, string gone — and neither is evidence alone.
 *
 * The roster premise is also checked here, before the bundle is opened:
 * every sentinel and every control must still be found in the source file
 * this file names for it, so a rename fails the gate rather than reporting
 * a green absence for a string nobody emits any more.
 *
 * ## What this gate does NOT cover, and why
 *
 * `defview` / `defhost` SOURCE COORDINATES (rf2-hic-007) are not
 * scannable in THIS bundle, and the reason is worth stating rather than
 * leaving to be rediscovered. The coordinate the macro bakes is an
 * absolute file path, so the sentinel would have to be the declaring
 * file's name — and the only declaration reachable from `:hicasso-release`
 * lives in `consumer_app.cljs`, whose name core's own `reg-sub` /
 * `reg-event` coordinates already put in the release bundle: they are
 * emitted through `re-frame.source-coords/prod-coords-form`, which keeps
 * `:file` and `:line` in production by design and drops only `:column`.
 * Measured, not assumed — `consumer_app.cljs` occurs three times in the
 * current green bundle, at lines 87, 89 and 91, which are the three
 * `reg-sub` / `reg-event` forms; the `h/defview` at line 99 contributes
 * nothing. A filename sentinel here would be red on a correct build.
 *
 * That surface is covered where the sentinel IS discriminating:
 * `check_source_coord_elision.cjs` scans the `:browser-test-prod-elision`
 * bundle (also `:advanced` + `goog.DEBUG=false`) for
 * `coord_sentinel_source.cljs`, a namespace that carries declarations and
 * no registrations, and `re-frame.hicasso.error-source-coord-elision-prod-test`
 * asserts the ledger is empty in that build. Both are rf2-hic-007's and
 * both run today.
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
    surface: 'test kit — the dev-only body-retention door (rf2-hic-020, rf2-kjf5)',
    sentinel: 'hicassoBody',
    source: 'src/re_frame/hicasso/impl/codec.cljs',
    premise: '(def ^:private body-slot "hicassoBody")',
    why:
      'The own property `codec/retain-body!` writes and `codec/retained-body` ' +
      'reads — the L0-L2 kit\'s only route from a minted head back to the ' +
      'body its author wrote. Written from `mint-view!` inside ' +
      '`(when ^boolean js/goog.DEBUG …)`.',
    remedy:
      'Check that gate at impl/collector.cljs `mint-view!`; a production head ' +
      'must carry no body.',
  },
  {
    surface: 'test kit — the kit itself (rf2-hic-020)',
    sentinel: 'rf.error/hicasso-test-',
    source: 'test_kit/src/re_frame/hicasso/test.cljs',
    premise: ':rf.error/hicasso-test-',
    why:
      'The refusal-id family `re-frame.hicasso.test` mints, and nothing else ' +
      'does. The kit is a separate source root that no `src/` namespace may ' +
      'require, so one of its ids in a consumer bundle means the testing ' +
      'surface itself became reachable from the public door.',
    remedy:
      'Find the `:require` of `re-frame.hicasso.test` under ' +
      'implementation/hicasso/src/ and move it into the test that needs it.',
  },
  {
    surface: 'complaint payload detail — `fail!`\'s completeness guard (rf2-hic-007)',
    sentinel: 'hicasso-refusal-incomplete',
    source: 'src/re_frame/hicasso/impl/error.cljc',
    premise: ':rf.error/hicasso-refusal-incomplete',
    why:
      'The meta-refusal `check-complete!` mints when a refusal reaches ' +
      '`fail!` without the required four. `fail!` calls it inside ' +
      '`(when interop/debug-enabled? …)` — in production the incomplete ' +
      'refusal is thrown as-is rather than replaced, so this id and the ' +
      'sentence around it are dev-only in terms.',
    remedy:
      'Check the `(when interop/debug-enabled? (check-complete! data))` line ' +
      'in impl/error.cljc `fail!`.',
  },
  {
    surface: 'evidence projection — the versioned envelope (rf2-hic-023)',
    sentinel: 're-frame.hicasso.evidence',
    source: 'src/re_frame/hicasso/evidence.cljs',
    premise: ':re-frame.hicasso.evidence/v2',
    why:
      'The schema pin every envelope carries, and the namespace half of ' +
      'every defect keyword the projection raises. `re-frame.hicasso.evidence` ' +
      'and its consumer `re-frame.hicasso.tool` are dev-only by ' +
      'REACHABILITY: no namespace under src/ requires them, so a consumer ' +
      'who never asks for the projection never ships it.',
    remedy:
      'Find the `:require` of `re-frame.hicasso.evidence` or ' +
      '`re-frame.hicasso.tool` that made it reachable from the public door.',
  },
  {
    surface: 'dev diagnostics — the codec\'s console messages',
    sentinel: '[hicasso]',
    source: 'src/re_frame/hicasso/impl/codec.cljs',
    premise: '"[hicasso] ',
    why:
      'The prefix every console message the package prints begins with, and ' +
      'the FAMILY rather than one member: all four live in impl/codec.cljs — ' +
      '`boundary-props=`\'s fail-open notice, `set-lowering-owner!`\'s ' +
      'unbalanced set/clear warning, and `check-seq-keys!` / ' +
      '`check-member-key!`\'s two key warnings. Every one of them sits behind ' +
      '`(when ^boolean js/goog.DEBUG …)`, so under `:advanced` the `keywarn` ' +
      'object, its tables and every message string fold away with the ' +
      'branches that reach them. A row per message would name the leak more ' +
      'narrowly and leave three of the four uncovered the day a fifth is ' +
      'written; the prefix cannot.',
    remedy:
      'Check the `goog.DEBUG` wrappers at those four sites in ' +
      'impl/codec.cljs — both the call site and the message itself have to ' +
      'be inside one.',
  },
];

const CONTROLS = [
  {
    control: 're-frame.hicasso.consumer-app/app',
    source: 'test/re_frame/hicasso/consumer_app.cljs',
    premise: '(h/defview app',
    proves:
      'the release entry\'s `h/defview` really compiled — `mint-view!` stamps ' +
      'this name as the boundary\'s `displayName` and as its `:render` measure ' +
      'id, unconditionally. Without it the absences below would be the ' +
      'absences of a bundle that compiled no declaration at all.',
  },
  {
    control: 'hicassoBoundary',
    source: 'src/re_frame/hicasso/impl/codec.cljs',
    premise: '(unchecked-set f "hicassoBoundary" true)',
    proves:
      'impl/codec.cljs\'s own-property marker machinery compiled. It is the ' +
      'UNGATED sibling of the `hicassoBody` slot two hundred lines below it: ' +
      'same file, same `unchecked-set`, same minted head, and the only ' +
      'difference between them is the `goog.DEBUG` gate. That is what makes ' +
      '"no hicassoBody" a statement about erasure rather than about ' +
      'compilation.',
  },
  {
    control: 'rf.error/hicasso-empty-vector',
    source: 'src/re_frame/hicasso/impl/codec.cljs',
    premise: ':rf.error/hicasso-empty-vector',
    proves:
      '`impl.error/fail!` and a shipped refusal id compiled. The complaint ' +
      'sentinel above is a refusal minted INSIDE `fail!`\'s dev guard; this ' +
      'is one minted by `fail!` on the path every build keeps. If `fail!` ' +
      'had been dropped entirely, both would be absent and this control is ' +
      'what says so.',
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
        `DEV SURFACE LEAKED: ${s.surface}\n` +
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
    assert(problems[0].includes('DEV SURFACE LEAKED'), problems[0]);
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
    `hicasso production erasure: self-test OK ` +
    `(${SENTINELS.length} sentinels, ${CONTROLS.length} positive controls)\n`
  );
  return 0;
}

function assert(ok, message) {
  if (!ok) {
    process.stderr.write(`hicasso production erasure: SELF-TEST FAILED\n  ${message}\n`);
    process.exit(1);
  }
}

// ---------------------------------------------------------------------------

function fail(problems) {
  process.stderr.write('hicasso production erasure: FAIL\n');
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
    `OK: no dev-only Hicasso surface in the :advanced / goog.DEBUG=false bundle ` +
    `(${SENTINELS.length} sentinels absent, ${CONTROLS.length} positive controls present).\n`
  );
  return 0;
}

process.exit(main(process.argv.slice(2)));
