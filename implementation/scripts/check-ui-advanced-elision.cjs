#!/usr/bin/env node
'use strict';

// rf2-vxgfnd.195 ARM 4 — the ADVANCED-BROWSER digest/descriptor elision
// consumer.
//
// WHAT WAS SYNTHETIC. The bead's fourth obligation reads: "Production elision
// is grepped only in the node-script `:ui-bench` bundle and does not force an
// advanced browser consumer of the digest/descriptor path, so browser-specific
// retention can remain unobserved." Two concrete gaps sit behind that:
//
//   1. The pre-existing residue scan lives INLINE in run-ui-bench.cjs over
//      `out/ui-bench.js`, and `:ui-bench` is a `:node-script` target — not a
//      browser target at all.
//   2. Nothing in that build CONSUMED the digest path, so the three fragments
//      it did forbid (`__RF2_UI_DIGEST_XX__`, `bd1-`, `digest_carrier`) were
//      absent by UNREACHABILITY, not by `goog.DEBUG` elision. An absence that
//      would hold even if every gate in digest_carrier.cljs were deleted
//      proves nothing about elision.
//
// WHAT THIS GATE DOES. It spawns the REAL pinned Shadow CLI twice over a
// fixture project (ui/test/re_frame/ui/digest_probe/advanced_elision/) whose
// entry namespace genuinely roots the carrier slot, the sentinel, the `bd1-`
// prefix test, the fail-loud configuration diagnostic, the live-root registry
// traversal and the reload producers. Shadow itself is therefore the producer
// of every artifact under test: a release that retains the residue — or that
// fails to compile the consumer at all — fails this gate. The two builds hold
// the `:advanced` optimization level constant and vary ONLY `goog.DEBUG`.
//
// THE ORACLE, AND THE TWO IT IS NOT.
//
//   * USED — the STRING-LITERAL oracle. Closure renames symbols but never
//     rewrites string literals, so a literal present in source and absent from
//     the bundle proves its branch was eliminated. Every entry below was shown
//     to move under a real source lever (see TEETH).
//
//   * NOT USED — counting `goog.DEBUG` occurrences. A sibling measurement
//     found 225 occurrences in an `:advanced` bundle, every one inside a
//     DOCSTRING carried in by var reification rather than live code.
//
//   * NOT USED, AND MEASURED WHY — the FUNCTION-NAME oracle (a `:pseudo-names
//     true` release, grepping for `$ns$fn$$`). It was built, run, and then
//     REMOVED because it is not load-bearing for THIS residue: Closure inlines
//     these small functions, so their names vanish from the release whether or
//     not the gate elided them. Measured on this fixture:
//       - `re_frame$ui$digest_carrier$stage_BANG_` stays absent from the
//         pseudo-named release even with its `goog.DEBUG` gate REMOVED
//         (inlined into its single call site);
//       - `re_frame$ui$digest_carrier$cell` likewise stays absent with BOTH
//         its own gate and `current`'s removed (the `#js {}` literal is
//         scalar-replaced);
//       - `re_frame$ui$digest_carrier$before_load` / `after_load` do not even
//         appear in the goog.DEBUG=TRUE control, so an absence assertion on
//         them could never have been verified.
//     Shipping those tokens would have shipped four passengers: assertions
//     that can never go red and therefore prove nothing.
//
// VERIFYING THE VERIFIER. Absence proves nothing until the same grep is shown
// to find the same tokens when the code IS present. Two independent controls,
// both string-literal so neither is at the mercy of inlining:
//
//   * `:ui-advanced-elision-control` is the SAME entry at the SAME optimization
//     level with `goog.DEBUG true`. Every ABSENT_STRINGS entry is asserted
//     PRESENT there, per entry. `goog.DEBUG` is the single variable, so the
//     grep is proven to fire on these exact byte sequences.
//   * RETAINED_STRINGS is asserted PRESENT in the very PRODUCTION bundle in
//     which ABSENT_STRINGS is asserted absent. A wrong path, a stale directory
//     or an empty blob fails loudly instead of passing vacuously — the control
//     bundle alone could not catch a production-side mis-target.
//
// SUBSTRING CONTAINMENT. Measured live on this fixture while the function-name
// oracle was still in place: a grep for `re_frame$ui$client$descriptor`
// reported 2 hits in the production bundle, but all 2 were passengers of
// `$re_frame$ui$client$descriptor_index$$` — `client/descriptor` itself is
// entirely absent. A gate written against the bare tail would have reported a
// survivor that is not there. The same rule binds the teeth below: a rename
// tooth must change a PREFIX, never extend a suffix, since `…$currentx` still
// CONTAINS `…$current` and such a tooth would prove only a passenger. The
// string tokens that survived into this gate are distinctive full literals, so
// none of them is a tail of another.
//
// PER-ENTRY SET CHECKS. Every set below is asserted entry by entry via
// `assertSentinelSet`, never as a union — a union-only guard stays silent
// while all but one member regresses.
//
// THE RETENTION THIS GATE FOUND (RETAINED_STRINGS, follow-up bead
// rf2-vxgfnd.299). `re-frame.ui.client/descriptor` and `/descriptor-index`
// carry NO `goog.DEBUG` gate. Their docstrings say "Empty in production" /
// "nil in production", which is true BEHAVIOURALLY — `current-build-digest`
// returns nil and the live-root registry is empty — but the registry-traversal
// code and the `:build-digest` keyword literal still cost production bytes.
// That is precisely the browser-specific retention the bead predicted would
// "remain unobserved", and this gate is what surfaced it. Fixing it means
// editing implementation/ui/src/re_frame/ui/client.cljs, which is outside this
// arm's surface, so the residue is PINNED PRESENT rather than left to a
// comment: when the follow-up gates those two fns, this gate goes RED and the
// entry must move into ABSENT_STRINGS. A pin that announces itself beats a
// TODO nobody reads, and it doubles as the production-side non-vacuity floor.
//
// TEETH (documented mutation/revert; run at development time, NOT committed).
// Every ABSENT_STRINGS entry has a demonstrated lever, with measured counts:
//
//   * L1 — DROP the `goog.DEBUG` wrapper on the namespace-load "was not
//     finalized" validation in digest_carrier.cljs, so the slot becomes live.
//     RED on all three entries: sentinel 0 -> 1, `bd1-` 0 -> 1, diagnostic
//     0 -> 1. This is the most realistic regression shape — it is what
//     "someone removed a gate" actually looks like.
//   * L3 — ungate `current` alone (`(when true …)`). RED on `bd1-` (0 -> 1)
//     via the `finalized-digest?` prefix test that `current` calls.
//   * L-vacuity — rename a sentinel in digest_carrier.cljs with a PREFIX
//     change and do NOT update this gate: the CONTROL assertion for that entry
//     goes RED, proving the token is live rather than folklore.
//   * L-mistarget — point the production scan at the control directory: the
//     three ABSENT entries go RED (they are present there by construction).
//
// A REJECTED TOOTH, recorded because it is instructive. The obvious first
// lever — make `compiled-digest` unconditional
// (`(def compiled-digest "__RF2_UI_DIGEST_XX__")`) — leaves the gate GREEN.
// The def's own gate is not what keeps the sentinel out of production; every
// READER of it is gated, so an ungated def is still unreachable and Closure
// drops it. A tooth that "passes" is not proof the gate is weak; here it was
// proof the lever was aimed at the wrong thing. The levers above all work by
// making the path LIVE, which is the only way residue actually ships.
//
// ROOTING NOTE. The fixture roots the reload producers by CALL, never by
// exporting the vars. Exporting `digest-carrier/before-load` directly keeps the
// (by then empty) function object alive purely because the fixture references
// it; measured, that alone put `before_load`/`after_load` back into the
// production bundle at 2 occurrences each — a leak no real consumer has.
// Rooting the call is what a real consumer does and is the honest shape.

const fs = require('fs');
const path = require('path');
const { spawnSync } = require('child_process');
const { createGateReporter } = require('./lib/gate-report.cjs');
const { assertSentinelSet, classifyOrFail } = require('./lib/sentinel-scan.cjs');

const IMPL = path.join(__dirname, '..');
const FIX = path.join(
  IMPL, 'ui', 'test', 're_frame', 'ui', 'digest_probe', 'advanced_elision',
);
const CONSUMER = path.join(FIX, 'consumer.cljs');

const PROD_DIR = path.join(IMPL, 'out', 'ui-advanced-elision');
const CONTROL_DIR = path.join(IMPL, 'out', 'ui-advanced-elision-control');

const report = createGateReporter();

// --- the residue sets -------------------------------------------------------

// Asserted ABSENT in the production bundle, PRESENT in the goog.DEBUG=true
// control. Each has a demonstrated lever above.
const ABSENT_STRINGS = [
  {
    source: 're-frame.ui.digest-carrier/compiled-digest (fixed-width carrier SENTINEL)',
    sentinel: '__RF2_UI_DIGEST_XX__',
  },
  {
    source: 're-frame.ui.digest-carrier/finalized-digest? (bd1- whole-build digest LITERAL)',
    sentinel: 'bd1-',
  },
  {
    source: 're-frame.ui.digest-carrier (fail-loud unfinalized-build DEBUG MANIFEST diagnostic)',
    sentinel: 're-frame.ui build digest was not finalized',
  },
];

// Asserted PRESENT in the PRODUCTION bundle. Two jobs: it is the production-
// side non-vacuity floor (proving the descriptor path really is compiled into
// the artifact being scanned), and it is the self-announcing pin on the one
// residue class that is genuinely retained today.
const RETAINED_STRINGS = [
  {
    source: 're-frame.ui.client/descriptor + /descriptor-index (UNGATED registry traversal — rf2-vxgfnd.299)',
    sentinel: 'build-digest',
  },
];

const BUILDS = [
  { id: 'ui-advanced-elision', role: 'production (goog.DEBUG=false)' },
  { id: 'ui-advanced-elision-control', role: 'control    (goog.DEBUG=true)' },
];

// The consumer must genuinely consume the digest/descriptor path. Without
// these, every absence would hold by unreachability — the exact defect that
// made the :ui-bench scan vacuous.
const CONSUMED = [
  'digest-carrier/current',
  'digest-carrier/stage!',
  'digest-carrier/before-load',
  'digest-carrier/after-load',
  'client/current-build-digest',
  'client/descriptor',
  'client/descriptor-index',
  'client/register-live-root!',
];

function fail(message) {
  report.flushDetails();
  console.error('=== FAIL ===');
  console.error(message);
  process.exit(1);
}

// --- real Shadow ------------------------------------------------------------

// Hardened, shell-free spawn (rf2-wn4o1): this node binary running Shadow's
// resolved JS entry point. Nothing is handed to a shell to interpret.
function releaseBuild({ id, role }) {
  report.detail(`[advanced-elision] shadow-cljs release ${id} — ${role}`);
  const runner = require.resolve('shadow-cljs/cli/runner.js', { paths: [IMPL] });
  const result = spawnSync(process.execPath, [runner, 'release', id], {
    cwd: FIX,
    encoding: 'utf8',
    maxBuffer: 32 * 1024 * 1024,
  });
  if (result.status !== 0) {
    fail(
      `real Shadow failed to produce the ${id} release (exit ${result.status}).\n` +
      'The advanced browser consumer must COMPILE for its elision to mean\n' +
      'anything; a build failure is a gate failure, never a skip.\n' +
      `${result.error || ''}\n${result.stdout || ''}\n${result.stderr || ''}`,
    );
  }
}

function blobFor(dir, label) {
  const cls = classifyOrFail(dir, {
    onMissing: () => fail(`[advanced-elision] ${label}: release output missing — ${dir}`),
    onEmpty: () => fail(
      `[advanced-elision] ${label}: release output present but EMPTY — ${dir}\n` +
      'An empty bundle satisfies every absence check vacuously (the rf2-utvst\n' +
      'non-vacuous floor). Treated as a failure.',
    ),
  });
  return cls.blob;
}

// --- assertions -------------------------------------------------------------

// Per ENTRY, never as a union: a union-only guard stays silent while all but
// one member regresses.
function assertSet(blob, label, sentinels, mustContain) {
  return assertSentinelSet(blob, sentinels, {
    mustContain,
    count: true,
    emit: (line) => report.detail(line),
    formatLine: ({ source, sentinel, hits, tag }) => {
      const expected = mustContain ? 'PRESENT' : 'ABSENT';
      return `          [${tag}] ${label} — ${source}: ${JSON.stringify(sentinel)} ` +
             `expected ${expected}, found ${hits} occurrence(s)`;
    },
  });
}

function run() {
  report.detail('=== rf2-vxgfnd.195 arm 4 — advanced-browser digest elision ===');

  const consumer = fs.readFileSync(CONSUMER, 'utf8');
  const unconsumed = CONSUMED.filter((c) => !consumer.includes(c));
  if (unconsumed.length !== 0) {
    fail(
      'the advanced-browser fixture no longer CONSUMES the digest/descriptor\n' +
      `path: ${unconsumed.join(', ')}\n` +
      'Elision has nothing to elide against an unrooted path, so every\n' +
      'absence assertion below would pass vacuously. Restore the consumer.',
    );
  }
  report.detail(
    `[advanced-elision] consumer roots ${CONSUMED.length} digest/descriptor entry points`,
  );

  for (const build of BUILDS) releaseBuild(build);

  const prod = blobFor(PROD_DIR, 'production');
  const control = blobFor(CONTROL_DIR, 'control');
  report.detail(
    `[advanced-elision] production ${prod.length} chars, control ${control.length} chars`,
  );

  report.detail('[advanced-elision] production (goog.DEBUG=false) — residue must be ABSENT:');
  const absent = assertSet(prod, 'production', ABSENT_STRINGS, false);

  report.detail('[advanced-elision] production — pinned retention + non-vacuity floor (must be PRESENT):');
  const retained = assertSet(prod, 'production', RETAINED_STRINGS, true);

  report.detail('[advanced-elision] control (goog.DEBUG=true) — the same residue must be PRESENT:');
  const control1 = assertSet(control, 'control', ABSENT_STRINGS, true);

  if (!(absent.ok && retained.ok && control1.ok)) {
    if (!absent.ok) {
      console.error('Dev-only whole-build digest machinery survived an :advanced');
      console.error(':browser release. The carrier, its sentinel, the bd1- literal');
      console.error('and the fail-loud diagnostic must cost production zero bytes');
      console.error('(Spec 004C §2). Check the goog.DEBUG gates in digest_carrier.cljs.');
    }
    if (!retained.ok) {
      console.error('The pinned retention is ABSENT from the production bundle.');
      console.error('Either the descriptor path is no longer compiled into this');
      console.error('artifact — in which case every absence result above is vacuous');
      console.error('and the fixture must be repaired — or rf2-vxgfnd.299 landed');
      console.error('and gated the registry traversal, in which case move the entry');
      console.error('from RETAINED_STRINGS into ABSENT_STRINGS.');
    }
    if (!control1.ok) {
      console.error('The goog.DEBUG=true control is MISSING residue tokens, so the');
      console.error('absence assertions would be vacuous. A token was most likely');
      console.error('renamed or moved out of its gated branch; update this gate.');
    }
    fail('advanced-browser digest elision: at least one assertion failed.');
  }

  report.pass(
    'ui-advanced-elision',
    `production ${absent.passed}/${absent.checked} residue absent + ` +
    `${retained.passed}/${retained.checked} pinned retention present; ` +
    `control ${control1.passed}/${control1.checked} present`,
  );
}

module.exports = { run };

if (require.main === module) {
  try {
    run();
  } catch (error) {
    console.error(error.stack || error.message || String(error));
    process.exitCode = 1;
  }
}
