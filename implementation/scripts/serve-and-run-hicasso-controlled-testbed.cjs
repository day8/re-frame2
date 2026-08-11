#!/usr/bin/env node
'use strict';

/*
 * THE HICASSO CONTROLLED-INPUT GATE — invariant I15 in three real engines
 * (rf2-hic-016).
 *
 *   node implementation/scripts/serve-and-run-hicasso-controlled-testbed.cjs
 *   npm run test:hicasso-controlled          (from implementation/)
 *
 * Compiles the `:hicasso/testbed` build, serves it, and runs
 * `implementation/hicasso/testbed/spec.cjs` once per engine in Chromium,
 * Firefox and WebKit.
 *
 * ## Why this is not an entry in the adapter-smoke manifest
 *
 * That runner is ONE engine over N specs; this surface is ONE spec over N
 * engines, and the engines are the contract rather than a detail of how it
 * is run. Widening the shared runner would have put three browser launches
 * behind every adapter smoke to serve one caller. The reagent-slim and
 * tenant-switcher testbeds set the precedent for a surface that carries its
 * own orchestrator, and `run-ui-g8.cjs` sets it for a correctness gate that
 * drives more than one engine.
 *
 * ## The three engines, and why each is mandatory
 *
 * Chromium is where every controlled-input claim in this repo was
 * previously witnessed — `run-ui-g8.cjs` adds WebKit for re-frame.ui, and
 * `bench/hicasso/ime_run.cjs` states its own scope as Chromium-only because
 * `Input.imeSetComposition` is a CDP method. Nothing had ever driven
 * Hicasso's element-path converge outside Chromium, and the mechanism is
 * made of exactly the things engines differ on: caret and selection
 * restoration, composition event carriage, and the order in which a
 * discrete event's work is flushed. Firefox is the third because it is the
 * one major engine the repo had never launched at all.
 *
 * ## Two verdicts, kept apart
 *
 * REQUIRED rows are the spec's own assertions; a throw in any engine fails
 * the gate. RECORDED rows are conduct the spec measures without demanding
 * — and they are not a way of not asserting, because this runner COMPARES
 * them across engines and fails on a divergence that no entry in
 * `NARROWINGS` names, with its engines and its reason. An engine that
 * quietly starts behaving differently is therefore a red gate rather than a
 * silently-updated record.
 *
 * Both verdicts rest on the suite having actually run, so the coverage
 * floor is STRUCTURAL rather than a total: `REQUIRED_SECTIONS` pins the
 * witnesses by name and `REQUIRED_RECORDS` pins the keys each measured row
 * carries. A count alone was fail-open in both directions — a whole section
 * could be deleted and still clear it, and three engines recording nothing
 * agree perfectly. Neither passes now.
 *
 * The comparator, the section floor and the record schema all carry
 * mutation teeth that run before any browser launches: a gate whose own
 * verdict logic cannot fail is worse than a gate that is red.
 *
 * ## Coverage — what these witnesses reach, and what they do not
 *
 * ### Behaviour by engine, measured rather than implied by the job name
 *
 * The job is called `real Chromium + Firefox + WebKit`, so the first
 * question anyone should ask it is which of the behaviours it names are
 * actually driven in three engines and which are driven in one. Every
 * section of `spec.cjs` runs unmodified in each engine — that is the
 * runner's whole shape — so the interesting rows are the ones that are NOT
 * in this spec at all, and they were found by reading the other witnesses
 * rather than by trusting the name. Measured 2026-08-10 against
 * `main@02d10e7b70`, before this bead's second pass changed anything:
 *
 * | behaviour | Cr | Ff | Wk | witness |
 * |---|---|---|---|---|
 * | same-turn echo | ✓ | ✓ | ✓ | `spec.cjs` `same-turn-convergence` |
 * | rejection / normalisation echoes the committed value | ✓ | ✓ | ✓ | `spec.cjs` `same-turn-convergence`, `caret-across-the-echo` |
 * | caret preserved (dispatched edit, read in-turn) | ✓ | ✓ | ✓ | `spec.cjs` `caret-across-the-echo` |
 * | caret preserved (the browser's own trusted keystrokes) | ✓ | ✓ | ✓ | `spec.cjs` `caret-under-real-typing` |
 * | composition — the event SEQUENCE, on a refusing field | ✓ | ✓ | ✓ | `spec.cjs` `composition-safety` |
 * | composition — release on `compositionend` / blur / non-composing change / unmount | ✓ | ✓ | ✓ | `spec.cjs` `composition-release-edges` |
 * | composition — `beforeinput` carried, and not what drives the converge | — | — | — | **nothing.** GAP 4 below; now `spec.cjs` `beforeinput-does-not-drive-the-converge` |
 * | composition — an ACCEPTING model takes every composing update | ✓ | — | — | `front/revision_dom_cljs_test:575`, Chromium lane. GAP 2; now `spec.cjs` `an-accepting-model-during-a-composition` |
 * | composition — a revision arriving MID-exchange defers to its close | ✓ | — | — | `front/revision_dom_cljs_test:512`, Chromium lane. GAP 1; now `spec.cjs` `a-revision-arriving-mid-composition` |
 * | composition — the browser's real composition RANGE and candidate window | ✓ | — | — | `bench/hicasso/ime_run.cjs` (CDP, so Chromium by construction) |
 * | composition — the ABORT signature (a value write killing an exchange with no `compositionend`) | ✓ | — | — | `bench/hicasso/ime_run.cjs`; unreachable from page script in any engine |
 * | selection — a RANGE across an out-of-band write | ✓ | ✓ | ✓ | `spec.cjs` `selection-across-an-out-of-band-write` |
 * | selection — DIRECTION across an out-of-band write | ~ | ~ | ~ | same section, RECORDED — but vacuously. GAP 3; the premise is now asserted |
 * | revision reset at rest, preserving element identity | ✓ | ✓ | ✓ | `spec.cjs` `revision-reset-preserves-identity` |
 * | blur / unmount edges | ✓ | ✓ | ✓ | `spec.cjs` `composition-release-edges` |
 * | form reset | ✓ | ✓ | ✓ | `spec.cjs` `form-reset-and-fill-proxy` (RECORDED) |
 * | autofill, natively | — | — | — | no cross-engine drive exists; the proxy is recorded in all three and named as one |
 * | owned `:value` / `::h/checked` win by presence | ✓ | ✓ | ✓ | `spec.cjs` `same-turn-convergence`, `owned-checked-pair` |
 *
 * The headline holds: of the behaviours the bead enumerates, everything
 * this spec contains really is driven in three engines, because the runner
 * cannot run a section in fewer. The two real-IME rows are Chromium-only by
 * RULING rather than by omission — the operator amended this bead's
 * acceptance on 2026-08-10 so that the synthetic sequence IS the recurring
 * three-engine witness, with native conduct on Firefox and WebKit verified
 * once by hand against `docs/design/hicasso/native-ime-manual-witness.md`.
 *
 * ### The four gaps that table found
 *
 * Three behaviours the bead names were driven in ONE engine by another
 * suite, and a fourth was carried but never asserted. Each is a witness
 * whose stated scope exceeded the cases it drove, which is worth naming
 * because it is the failure this gate exists to make impossible:
 *
 * 1. **A revision arriving mid-composition.** `controlled.cljs` documents
 *    the deferral at length and `front/revision_dom_cljs_test`'s
 *    `a-revision-arriving-mid-composition-defers-to-the-close` asserts it —
 *    on the `:browser-test` lane, which launches Chromium and only Chromium
 *    (`scripts/run-browser-tests.cjs:31`). A deferral is a claim about the
 *    order a browser flushes a discrete event's work, so one engine is
 *    exactly the wrong number.
 * 2. **An accepting model during a composition.** Same file, same lane, and
 *    the sharper of the two: the shadow holds the DRAFT, but the author's
 *    handler still runs on every composing `input`
 *    (`controlled.cljs` `shadowed-props`, which calls `inner` before it
 *    branches on `composing-input?`), so an accepting model moves
 *    throughout the exchange. `controlled.cljs` states it as the deferral's
 *    honest limit; one engine had measured it. It is also the fact the
 *    manual-witness checklist's "app-db clean until commit" contradicts.
 * 3. **Selection direction.** The section set `(1, 3, 'backward')`,
 *    asserted the WIDTH, and recorded the post-write `direction`. Had an
 *    engine never honoured `'backward'` at all, the recorded row would
 *    still have agreed with the others perfectly — three engines agreeing
 *    about a selection that was never directional. Recording an outcome
 *    whose premise is unread is the cross-engine comparator's blind spot.
 * 4. **`beforeinput`.** The `spec.cjs` header states composition is driven
 *    as the sequence `compositionstart` / `beforeinput` / `input` /
 *    `compositionend`, and the page helper has always carried a
 *    `{ beforeinput: false }` knob no caller used. Carrying an event is not
 *    witnessing it: nothing distinguished a run with `beforeinput` from a
 *    run without, in any engine.
 *
 * All four are filled: `REQUIRED_SECTIONS` gained
 * `beforeinput-does-not-drive-the-converge`,
 * `an-accepting-model-during-a-composition` and
 * `a-revision-arriving-mid-composition`, and the direction row's premise is
 * asserted rather than assumed. The table keeps its as-measured columns
 * because the point of it is what the gate looked like BEFORE, and because
 * the two Chromium-only real-IME rows do not move.
 *
 * ### The third pass: two witnesses that ran but could not red
 *
 * The #7815 audit then asked the sharper version of the same question of
 * the rows the second pass had just added — not "did this run?" but "what
 * single narrowing of the implementation would let it pass while the law
 * is broken?" — and two of them had no answer:
 *
 * 1. **The mid-composition revision** drove the ACCEPTING `revision`
 *    field. Its composing `:tb/edit` has already moved that field's model
 *    to `keepあ` before the bump, so a reset that deferred and a reset
 *    that reasserted immediately had the SAME string to write: React finds
 *    nothing differing, and the row reads `keepあ` under either conduct.
 *    The repair is a model policy rather than machinery — the testbed
 *    gained `revision-strict`, the same `::h/revision` on a field that
 *    REFUSES the kana, so mid-exchange the reset's target is `42` while
 *    the draft is `42あ`. The row now reds on exactly the mutation
 *    `controlled.cljs` names as the alternative it rejected: an immediate
 *    `element.value` write. The accepting field keeps its rows for the
 *    other half — the deferral's honest limit, that the reset can be lost.
 * 2. **`armed-edges-are-wired` clicked one of the two arms.** A dead
 *    `arm-unmount`, or an arm firing an event nobody registered, stayed
 *    green while the section's name and the PR both claimed the pair.
 *    Waiting the five seconds out per arm per engine is not the answer;
 *    resolving the event at ARM time is. `:tb/arm` now looks its event up
 *    when it is armed, carries it in the `:dispatch-later` payload and
 *    puts it in the on-screen readout, so both arms are witnessed in the
 *    turn they are clicked — and the operator's readout says what is
 *    queued instead of only that something is.
 *
 * ### The clauses of I15, and where each is proven
 *
 * I15's clauses, and where each is proven:
 *
 * | clause | witnessed | isolates THIS runtime? |
 * |---|---|---|
 * | converge within the edit turn | yes, read inside the dispatching task | no — see below |
 * | echo only committed state | yes, DOM and store both read | no — see below |
 * | rejection / normalisation echo the committed value | yes, one field per policy | no — see below |
 * | caret preserved across the echo | yes, mid-string edits, dispatched AND real-keyboard | YES |
 * | in-flight composition preserved | yes, on a REFUSING field, plus blur / non-composing / unmount releases | YES |
 * | reset is an explicit revision | yes, structurally — removing the revision read leaves the draft in place | YES |
 * | reset preserves element identity | yes, an expando survives the bump; a `:key` bump destroys it | YES |
 * | owned slots win by presence, not truthiness | partly — `value: ""` and `checked: false` are both proven live | no |
 * | a forwarded attribute cannot replace an owned one | NO — see below |
 *
 * ### Why only some rows isolate this runtime, and how that was established
 *
 * Measured, not assumed. Three deliberate regressions were driven through
 * the whole matrix — the converge deferred a task (the UIx-port conduct),
 * the caret restored to the end of the string (plain React's conduct), and
 * `converge-to!` handed the pre-flush record (the stale-value trap the
 * namespace docstring names) — and ALL THREE were caught by the CARET rows
 * and by nothing else. The value rows stayed green under every one of them.
 *
 * The reason is React's own end-of-event restore. `updateInput` assigns
 * `element.value` whenever the DOM disagrees with the committed value, in
 * the same discrete event, so any value-level misconduct of the converge is
 * corrected before the next line runs. What React cannot undo is the caret:
 * its own restore assigns the value, and assigning moves the cursor to the
 * end. So in a real browser the caret is the only observable that separates
 * this runtime from the two it was built to beat — which is exactly why
 * this gate is a browser gate, and why the caret rows edit in the MIDDLE of
 * the string.
 *
 * The value rows are still worth their place: they are the invariant as
 * stated, and their negative controls (an expectation flipped per row) all
 * bite. They just do not, on their own, attribute the conduct.
 *
 * ### Out of reach here, and where it lives instead
 *
 * - **A real IME.** `Input.imeSetComposition` is CDP, so real composition
 *   ranges are Chromium-only and stay with `bench/hicasso/ime_run.cjs`.
 *   The abort SIGNATURE that harness detects — a value write killing an
 *   exchange with no `compositionend` — cannot be reproduced from page
 *   script in any engine, so it is not claimed here.
 * - **Autofill.** No cross-engine drive exists (Chromium's needs CDP and a
 *   profile). The spec records the two shapes it CAN drive — a fill that
 *   dispatches an input event, and one that dispatches nothing — and names
 *   them a proxy. The conformance matrix is hic-040's.
 * - **Owned-vs-forwarded attribute merge.** There is no public merge
 *   surface on the door today, so a testbed asserting it would be
 *   measuring its own helper. The presence-not-truthiness HALF is proven
 *   (a `value: ""` field is controlled and converges; a `checked: false`
 *   box tracks its model); the forwarding half is not, and is not claimed.
 *
 * ### The sabotage, run rather than reasoned about
 *
 * rf2-hic-016's acceptance names one: disabling the composition guard must
 * turn the WebKit IME witness red. It was run on 2026-08-10, and again on
 * 2026-08-11 against the third pass, by replacing `composing-input?`'s body
 * with `false` — the whole carve-out off, both halves, since the shadow is
 * held from the same reading — and driving
 * `HICASSO_TESTBED_ENGINES=webkit`. It exits 1 on the first composing
 * update:
 *
 *     FAIL Hicasso controlled input (I15) — three engines (webkit):
 *       [webkit] the first composing update survives in the field:
 *       expected "123あ", got "123"
 *
 * which is the carve-out's whole point stated as a failure: the refusing
 * model's value written over a live draft. The guard was then restored
 * byte-identically (`git checkout --`, working tree clean) and WebKit
 * returned to 91 checks (the count of that day; the armed-edges repair of
 * 2026-08-11 took it to 95). A gate nobody has seen fail is not a gate.
 *
 * ### And the same sabotage, used to measure ONE row against another
 *
 * A gate that has been seen to fail somewhere is still not evidence that
 * each of its rows can fail. The #7815 audit's claim about the
 * mid-composition revision was checkable rather than arguable, so it was
 * checked: with the guard disabled — the law broken in exactly the way
 * this section names — the two halves were run separately on WebKit, with
 * `SECTIONS` cut to `revision-reset-preserves-identity` and this section
 * so that nothing else could red first.
 *
 * | half | field | result under the broken guard |
 * |---|---|---|
 * | as shipped in #7815 | `revision`, accepting | **6 of 6 green.** The only complaints were the coverage floor's, about the sections the cut had removed. |
 * | the repair | `revision-strict`, refusing | **red**, `[webkit] the field held the composing draft: expected "42あ", got "42"` |
 *
 * So the audit was right, and it is now a measurement rather than a
 * reading: the accepting field's model had already taken the draft, so the
 * broken and the correct conduct wrote the same string and every row
 * agreed with both. The refusing field is the same section with something
 * to disagree about.
 *
 * ### What the three engines actually said
 *
 * Nothing diverged, on the second pass or the third. Every RECORDED row is
 * byte-identical in Chromium, Firefox and WebKit, so `NARROWINGS` is empty
 * and no per-control refusal policy is owed to the hic-005 table from this
 * bead. Three conducts worth carrying forward, all three-engine unanimous:
 *
 *   1. An out-of-band model write lands on the NEXT TASK, not the same
 *      one. A keystroke is inside its turn only because `converge!` buys
 *      that with a `flushSync`; a button buys none, and a concurrent root
 *      flushes its sync lane in a microtask.
 *   2. A RANGE selection does not survive an out-of-band write: it
 *      collapses to a caret at the end of the new value. That is
 *      rf2-n3dxw's stated limit, now measured in three engines rather
 *      than one, and it is React's restore doing it rather than this
 *      runtime.
 *   3. `form.reset()` is VISUALLY INERT on a converged field, because
 *      `defaultValue` — the record `controlled/last-rendered` reads — is
 *      already the model. The one ordinary browser action that touches
 *      the converge's own bookkeeping agrees with it in all three
 *      engines, which is a direct check on that dependency.
 */

const fs = require('fs');
const path = require('path');
const { spawnSync } = require('child_process');
const playwright = require('playwright');
const {
  createHarnessCleanup,
  resolveServePort,
  startLocalHttpServer,
} = require('./lib/local-browser-harness.cjs');
const { enforcePolicy, DEFAULT_OUT_ROOT } = require('./_path-policy.cjs');

const IMPL_ROOT = path.resolve(__dirname, '..');
const BUILD_ID = 'hicasso/testbed';
const ROOT = enforcePolicy(
  'HICASSO_TESTBED_ROOT',
  path.join(IMPL_ROOT, 'out', 'hicasso-testbed'),
  { allowedRoots: [DEFAULT_OUT_ROOT] },
);
const HTML_SRC = path.join(IMPL_ROOT, 'hicasso', 'testbed', 'index.html');
const SPEC = require(path.join(IMPL_ROOT, 'hicasso', 'testbed', 'spec.cjs'));

const DEFAULT_PORT = 8065;
const READY_TIMEOUT_MS = 30000;
const SPEC_TIMEOUT_MS = parseInt(process.env.HICASSO_TESTBED_SPEC_TIMEOUT_MS || '90000', 10);
// The navigation's own ceiling, named so a CI log cannot read it as the
// spec budget above. `'commit'` rather than `'load'`: the page's own script
// is an un-optimized dev bundle that mounts the app, and everything after
// the navigation auto-waits on the execution context anyway.
const NAV_WAIT_UNTIL = 'commit';
const NAV_TIMEOUT_MS = 60000;

// Chromium, Firefox and WebKit. `HICASSO_TESTBED_ENGINES` narrows the set
// for local iteration; the floor below is prorated so a partial run stays
// honest about being one.
const ALL_ENGINES = ['chromium', 'firefox', 'webkit'];
const ONLY = (process.env.HICASSO_TESTBED_ENGINES || '').trim();
const ENGINES = ONLY
  ? ALL_ENGINES.filter((e) => ONLY.split(',').map((s) => s.trim()).includes(e))
  : ALL_ENGINES;

// ---------------------------------------------------------------------------
// The coverage floor — STRUCTURAL, not a total.
//
// A bare count cannot see a deleted section, and this gate learned that the
// hard way: with a floor of 50 against a full engine's 55 checks, either of
// the two three-row sections could be deleted WHOLE and the run still
// banked 52 and exited 0. A floor that survives the deletion of what it
// guards is decoration.
//
// So the pin is the section NAMES, each with the number of checks it banks
// today. The names make a deleted section a red gate that says which one is
// gone; the counts make a row quietly dropped from a surviving section red
// too. Adding rows is free — these are minimums — but adding a SECTION is
// not: the set must match exactly, so a witness added tomorrow is required
// from the moment it lands instead of being deletable again silently. The
// list is deliberately in THIS file rather than beside the sections it
// names, so deleting a witness means deliberately editing the gate that
// requires it.
//
// Sum today: 95, which is what each engine reports.
// ---------------------------------------------------------------------------

const REQUIRED_SECTIONS = {
  'same-turn-convergence': 8,
  'beforeinput-does-not-drive-the-converge': 7,
  'caret-across-the-echo': 9,
  'caret-under-real-typing': 4,
  'selection-across-an-out-of-band-write': 4,
  'composition-safety': 7,
  'composition-release-edges': 8,
  'an-accepting-model-during-a-composition': 8,
  'revision-reset-preserves-identity': 7,
  'a-revision-arriving-mid-composition': 15,
  'owned-checked-pair': 6,
  'form-reset-and-fill-proxy': 3,
  'armed-edges-are-wired': 9,
};

// The RECORDED rows, with the keys each must carry. Without this the
// comparator was fail-open in the worst way available to it: three engines
// all recording `{}` agree perfectly, so every measured conduct could
// vanish at once and `divergenceReport` would raise nothing. Agreement is
// only evidence when there is something to agree about.
//
// `[]` means a scalar row — the pin is that it is present and defined. A
// row the spec records but nobody pinned is also a problem: a new
// measurement joins the schema or it is not measured.
const REQUIRED_RECORDS = {
  'out-of-band-write-lands-in-the-same-task': [],
  'selection-across-out-of-band-write': ['start', 'end', 'direction', 'collapsed'],
  'form-reset': [
    'default-value-mirrors-the-model',
    'value-after-reset',
    'model-after-reset',
    'reset-is-visually-inert',
  ],
  'fill-proxy': [
    'eventless-fill-leaves-a-draft',
    'form-reset-clears-the-eventless-draft',
    'value-after-reset',
  ],
};

const has = (o, k) => Object.prototype.hasOwnProperty.call(o, k);

// ---------------------------------------------------------------------------
// Cross-engine narrowings — every RECORDED row that is allowed to differ,
// with the engines it differs on and why. An entry here is a claim about
// the world; an unlisted divergence is a red gate.
// ---------------------------------------------------------------------------

const NARROWINGS = [
  // Populated only by a divergence this gate actually measured. An empty
  // list is the honest starting state: nothing is excused in advance.
];

/**
 * Compare the RECORDED rows across engines. Returns a list of problems —
 * empty when every row either agrees everywhere or is covered by a
 * narrowing that names the engines it saw.
 */
function divergenceReport(perEngine, narrowings = NARROWINGS) {
  const problems = [];
  const engines = Object.keys(perEngine);
  if (engines.length < 2) return problems;
  const rows = new Set();
  for (const e of engines) for (const r of Object.keys(perEngine[e])) rows.add(r);

  for (const row of rows) {
    const byValue = new Map();
    for (const e of engines) {
      const key = JSON.stringify(perEngine[e][row]);
      if (!byValue.has(key)) byValue.set(key, []);
      byValue.get(key).push(e);
    }
    if (byValue.size === 1) continue;
    const narrowing = narrowings.find((n) => n.row === row);
    const groups = [...byValue.entries()]
      .map(([value, es]) => `${es.join('+')}: ${value}`)
      .join('  |  ');
    if (!narrowing) {
      problems.push(
        `RECORDED row "${row}" diverges across engines and no NARROWINGS ` +
        `entry names it — ${groups}. Either the runtime should agree here ` +
        `(fix it) or the divergence is real (add a narrowing with its ` +
        `engines and reason, and carry it to the hic-005 table).`);
      continue;
    }
    const seen = engines.filter((e) => JSON.stringify(perEngine[e][row]) !== JSON.stringify(perEngine[engines[0]][row]));
    const unexpected = seen.filter((e) => !narrowing.engines.includes(e));
    if (unexpected.length > 0) {
      problems.push(
        `RECORDED row "${row}" has a narrowing for ${narrowing.engines.join('+')}, ` +
        `but ${unexpected.join('+')} also diverged — ${groups}.`);
    }
  }
  return problems;
}

/**
 * Did this engine run the whole suite? Returns a list of problems — empty
 * when every pinned section ran and banked at least the rows it banks
 * today. A spec that reports no sections at all fails every entry, which is
 * the fail-closed direction.
 */
function coverageReport(result, required = REQUIRED_SECTIONS) {
  const problems = [];
  const sections = (result && result.sections) || {};
  for (const [name, min] of Object.entries(required)) {
    if (!has(sections, name)) {
      problems.push(
        `required section "${name}" did not run — the suite reported ` +
        `[${Object.keys(sections).join(', ') || 'nothing'}]. Deleting a ` +
        `witness means deleting its entry in REQUIRED_SECTIONS too, ` +
        `deliberately.`);
    } else if (sections[name] < min) {
      problems.push(
        `section "${name}" banked ${sections[name]} checks, was ${min} — ` +
        `rows were dropped from a section that still runs.`);
    }
  }
  for (const name of Object.keys(sections)) {
    if (!has(required, name)) {
      problems.push(
        `section "${name}" ran but is not pinned in REQUIRED_SECTIONS — add ` +
        `it with the rows it banks, or it is a witness that can be deleted ` +
        `again silently.`);
    }
  }
  return problems;
}

/**
 * Are the RECORDED rows actually there, carrying what they claim to
 * measure? Returns a list of problems. This is what stops the cross-engine
 * comparator from passing on unanimous emptiness.
 */
function recordSchemaReport(recorded, required = REQUIRED_RECORDS) {
  const problems = [];
  const rows = recorded || {};
  for (const [row, keys] of Object.entries(required)) {
    if (!has(rows, row) || rows[row] === undefined) {
      problems.push(
        `RECORDED row "${row}" is missing — the comparator cannot find a ` +
        `divergence in a measurement nobody took.`);
      continue;
    }
    if (keys.length === 0) continue;
    const value = rows[row];
    if (value === null || typeof value !== 'object' || Array.isArray(value)) {
      problems.push(
        `RECORDED row "${row}" should be an object carrying ` +
        `[${keys.join(', ')}], got ${JSON.stringify(value)}.`);
      continue;
    }
    const missing = keys.filter((k) => !has(value, k));
    if (missing.length > 0) {
      problems.push(
        `RECORDED row "${row}" is missing key(s) [${missing.join(', ')}] — ` +
        `got [${Object.keys(value).join(', ')}].`);
    }
  }
  for (const row of Object.keys(rows)) {
    if (!has(required, row)) {
      problems.push(
        `RECORDED row "${row}" is not pinned in REQUIRED_RECORDS — add it ` +
        `with the keys it carries, so a new measurement cannot be dropped ` +
        `again later without reddening.`);
    }
  }
  return problems;
}

// ---------------------------------------------------------------------------
// Mutation teeth — the gate's own verdict logic, proven able to fail before
// a single browser is launched.
// ---------------------------------------------------------------------------

function runMutationTeeth() {
  const teeth = [];
  const bite = (name, fn) => {
    if (!fn()) throw new Error(`MUTATION TOOTH DID NOT BITE: ${name}`);
    teeth.push(name);
  };

  bite('agreement across engines raises nothing', () =>
    divergenceReport({
      chromium: { row: { a: 1 } }, firefox: { row: { a: 1 } }, webkit: { row: { a: 1 } },
    }, []).length === 0);

  bite('an unnarrowed divergence is a problem', () =>
    divergenceReport({
      chromium: { row: { a: 1 } }, firefox: { row: { a: 2 } }, webkit: { row: { a: 1 } },
    }, []).length === 1);

  bite('a narrowing that names the engine excuses it', () =>
    divergenceReport({
      chromium: { row: { a: 1 } }, firefox: { row: { a: 2 } },
    }, [{ row: 'row', engines: ['firefox'], why: 'tooth' }]).length === 0);

  bite('a narrowing that names the WRONG engine does not', () =>
    divergenceReport({
      chromium: { row: { a: 1 } }, firefox: { row: { a: 2 } },
    }, [{ row: 'row', engines: ['webkit'], why: 'tooth' }]).length === 1);

  bite('a single engine cannot diverge from itself', () =>
    divergenceReport({ chromium: { row: { a: 1 } } }, []).length === 0);

  // A full run, as the spec reports it, and the same run with one thing
  // taken away. Derived from the pins so a tooth cannot rot against them.
  const fullSections = () => ({ ...REQUIRED_SECTIONS });
  const fullRecords = () => Object.fromEntries(
    Object.entries(REQUIRED_RECORDS).map(([row, keys]) => [
      row,
      keys.length === 0 ? true : Object.fromEntries(keys.map((k) => [k, 'x'])),
    ]));

  bite('the check floor refuses a run that asserted almost nothing', () =>
    coverageReport({ checks: 3, sections: {} }).length
      === Object.keys(REQUIRED_SECTIONS).length
    && coverageReport({ checks: 95, sections: fullSections() }).length === 0);

  // The hole this gate was reopened for: 55 checks with a floor of 50 meant
  // either three-row section could be deleted whole and still exit 0.
  bite('deleting a whole section reds, and the message names it', () => {
    const sections = fullSections();
    delete sections['form-reset-and-fill-proxy'];
    const problems = coverageReport({ checks: 88, sections });
    return problems.length === 1
      && problems[0].includes('form-reset-and-fill-proxy');
  });

  bite('a section that stopped banking rows reds', () => {
    const sections = fullSections();
    sections['selection-across-an-out-of-band-write'] -= 1;
    return coverageReport({ checks: 90, sections }).length === 1;
  });

  // The second hole: unanimous emptiness is not agreement.
  bite('an engine that recorded nothing reds', () =>
    recordSchemaReport({}).length === Object.keys(REQUIRED_RECORDS).length
    && divergenceReport({ chromium: {}, firefox: {}, webkit: {} }, []).length === 0);

  bite('the record schema accepts the rows the spec records', () =>
    recordSchemaReport(fullRecords()).length === 0);

  bite('a recorded row missing a key reds', () => {
    const records = fullRecords();
    delete records['selection-across-out-of-band-write'].direction;
    const problems = recordSchemaReport(records);
    return problems.length === 1 && problems[0].includes('direction');
  });

  bite('a recorded row nobody pinned reds', () =>
    recordSchemaReport({ ...fullRecords(), invented: 1 }).length === 1);

  // Both pins are bijections, so a witness added tomorrow is required from
  // the moment it lands rather than being deletable again silently.
  bite('a section nobody pinned reds', () =>
    coverageReport({ checks: 92, sections: { ...fullSections(), invented: 1 } })
      .length === 1);

  return teeth;
}

// ---------------------------------------------------------------------------
// Build and serve
// ---------------------------------------------------------------------------

function compile() {
  const runner = require.resolve('shadow-cljs/cli/runner.js', { paths: [IMPL_ROOT] });
  console.log(`> shadow-cljs compile ${BUILD_ID}`);
  const result = spawnSync(process.execPath, [runner, 'compile', BUILD_ID], {
    cwd: IMPL_ROOT, env: process.env, shell: false, stdio: 'inherit',
  });
  if (result.error) throw result.error;
  if (result.status !== 0) {
    throw new Error(`shadow-cljs compile ${BUILD_ID} exited ${result.status}`);
  }
}

function stageHtml() {
  fs.mkdirSync(ROOT, { recursive: true });
  fs.copyFileSync(HTML_SRC, path.join(ROOT, 'index.html'));
}

// ---------------------------------------------------------------------------
// One engine
// ---------------------------------------------------------------------------

async function driveEngine(engine, baseUrl) {
  const browserType = playwright[engine];
  if (!browserType) throw new Error(`unknown engine ${engine}`);
  const browser = await browserType.launch({ headless: true });
  // Page errors live in a DEDICATED array and flip the verdict, even when
  // every assertion otherwise passed.
  const pageErrors = [];
  const lines = [];
  const log = (s) => lines.push(s);
  let passed = false;
  let result = null;
  try {
    const context = await browser.newContext();
    const page = await context.newPage();
    page.on('console', (msg) => log(`[${engine}:${msg.type()}] ${msg.text()}`));
    page.on('pageerror', (err) => {
      pageErrors.push(err);
      log(`[${engine}:pageerror] ${err.message}`);
      if (err.stack) log(err.stack);
    });
    // The spec's page-side vocabulary, in place before the app's own script
    // runs so nothing races the mount.
    await page.addInitScript(SPEC.pageHelpers);

    const url = SPEC.url.startsWith('http') ? SPEC.url : baseUrl + SPEC.url;
    log(`\n=== ${SPEC.name} — ${engine} ===`);
    try {
      await page.goto(url, { waitUntil: NAV_WAIT_UNTIL, timeout: NAV_TIMEOUT_MS });
    } catch (err) {
      throw new Error(
        `[${engine}] NAVIGATION FAILED — the page.goto ceiling fired ` +
        `(waitUntil: '${NAV_WAIT_UNTIL}', timeout: ${NAV_TIMEOUT_MS}ms), which ` +
        `is NOT the ${SPEC_TIMEOUT_MS}ms spec budget that would have followed ` +
        `it. No witness ran, so nothing about the runtime has been observed. ` +
        `An unbuilt bundle or a dead server fails here at any size. ` +
        `Underlying: ${err.message}`);
    }
    // The app must be up before the first witness touches a field.
    await page.waitForSelector('[data-testid="hicasso-controlled-testbed"]',
      { timeout: SPEC_TIMEOUT_MS });

    result = await withTimeout(SPEC.run(page, { engine }), SPEC_TIMEOUT_MS,
      `${SPEC.name} (${engine})`);

    if (pageErrors.length > 0) {
      throw new Error(
        `[${engine}] page emitted ${pageErrors.length} uncaught error(s); ` +
        `first: ${pageErrors[0].message}`);
    }
    const gaps = [
      ...coverageReport(result),
      ...recordSchemaReport(result.recorded),
    ];
    if (gaps.length > 0) {
      throw new Error(
        `[${engine}] banked ${result.checks} checks but the coverage floor ` +
        `is not met:\n  - ${gaps.join('\n  - ')}`);
    }
    passed = true;
  } catch (err) {
    log(`FAIL ${SPEC.name} (${engine}): ${err && err.message ? err.message : err}`);
    if (err && err.stack) log(err.stack);
  } finally {
    await browser.close();
  }
  if (!passed) for (const ln of lines) console.log(ln);
  console.log(passed
    ? `PASS  ${engine} — ${result.checks} checks across ` +
      `${Object.keys(result.sections).length} sections`
    : `FAIL  ${engine}`);
  return { passed, result };
}

function withTimeout(promise, ms, label) {
  let timer;
  return Promise.race([
    promise.finally(() => clearTimeout(timer)),
    new Promise((_, reject) => {
      timer = setTimeout(() => reject(new Error(`${label} exceeded ${ms}ms`)), ms);
    }),
  ]);
}

// ---------------------------------------------------------------------------

async function main() {
  const cleanup = createHarnessCleanup();
  cleanup.installSignalHandlers();
  let tearingDown = false;

  if (ENGINES.length === 0) {
    console.error(`HICASSO_TESTBED_ENGINES=${ONLY} selects no engine; ` +
      `known: ${ALL_ENGINES.join(', ')}`);
    return 1;
  }

  try {
    const teeth = runMutationTeeth();
    console.log(`> verdict logic teeth bit: ${teeth.length}`);

    compile();
    stageHtml();

    const port = await resolveServePort(
      Number(process.env.HICASSO_TESTBED_PORT) || DEFAULT_PORT,
      { onFallback: (p, f) => console.log(`> port ${p} busy, serving on ${f}`) },
    );
    const httpServerBin = require.resolve('http-server/bin/http-server', { paths: [IMPL_ROOT] });
    const server = await startLocalHttpServer({
      cleanup,
      httpServerBin,
      root: ROOT,
      port,
      cwd: IMPL_ROOT,
      readyTimeoutMs: READY_TIMEOUT_MS,
      suppressExitDiagnostic: () => tearingDown,
    });
    if (!server.ready) {
      console.error('the testbed server did not prove owned readiness');
      return 1;
    }
    const baseUrl = `http://127.0.0.1:${port}`;

    const recorded = {};
    let failures = 0;
    for (const engine of ENGINES) {
      const { passed, result } = await driveEngine(engine, baseUrl);
      if (!passed) failures += 1;
      else recorded[engine] = result.recorded;
    }
    if (failures > 0) {
      console.error(`\n${failures} of ${ENGINES.length} engine(s) failed.`);
      return 1;
    }

    console.log('\n--- RECORDED conduct (measured, not required) ---');
    for (const engine of ENGINES) {
      console.log(`  [${engine}] ${JSON.stringify(recorded[engine])}`);
    }
    const problems = divergenceReport(recorded);
    if (problems.length > 0) {
      console.error('\nCROSS-ENGINE DIVERGENCE:');
      for (const p of problems) console.error(`  - ${p}`);
      return 1;
    }
    for (const n of NARROWINGS) {
      console.log(`  narrowing: ${n.row} on ${n.engines.join('+')} — ${n.why}`);
    }
    console.log(`\nHICASSO CONTROLLED-INPUT PASS (${ENGINES.join(' + ')})`);
    return 0;
  } finally {
    tearingDown = true;
    await cleanup.cleanup();
  }
}

module.exports = {
  divergenceReport,
  runMutationTeeth,
  coverageReport,
  recordSchemaReport,
  NARROWINGS,
  REQUIRED_SECTIONS,
  REQUIRED_RECORDS,
};

if (require.main === module) {
  main().then((code) => process.exit(code)).catch((error) => {
    console.error(error.stack || String(error));
    process.exit(1);
  });
}
