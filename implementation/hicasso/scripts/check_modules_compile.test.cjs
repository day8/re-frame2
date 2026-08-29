#!/usr/bin/env node
'use strict';
// THE OPTIONAL-MODULE ENTRY SOURCE, PINNED — rf2-okhdf.
//
//     node hicasso/test/re_frame/bench/hicasso/compile_gate.test.cjs
//
// `compile_gate.cjs` compiles the Hicasso optional modules because nothing
// else in this repository compiles them anywhere warnings are fatal, and it
// learns which namespaces those are by asking
// `check_optional_module_reachability.py --module-namespaces`.
//
// An entry source that quietly contributed NOTHING would leave that gate
// green over exactly the code it was widened to cover — the defect rf2-okhdf
// exists to close, reproduced one level up in the instrument that closes it.
// So each of the four refusals is driven here, against
// [[decideModuleNamespaces]], which is pure for this reason. The live arm at
// the bottom then asserts the real roster still answers, because every
// refusal above could fire correctly over an emitter that had stopped
// existing and the suite would still be green.
//
// It is a SIBLING of `lane_build.test.cjs` and shares its shape deliberately:
// spawn nothing, decide from a captured result, and pin both directions.

// rf2-peorl added a FOURTH entry source — the two `re-frame.bench.*` core
// attribution instruments, re-homed here when `implementation/freehand/` took
// their own gate with it. Its refusals are pinned at the bottom for the same
// reason: a stated roster that stopped naming real namespaces would leave the
// gate compiling a set it had silently dropped them out of, and the two rows
// are here precisely because nothing else in the repository would notice.

const assert = require('node:assert/strict');
const fs = require('node:fs');
const os = require('node:os');
const path = require('node:path');

const {
  decideModuleNamespaces,
  optionalModuleNamespaces,
  verifyRoster,
  MIN_MODULE_NAMESPACES,
  MODULE_ROSTER,
  MODULE_ROSTER_FLAG,
  REHOMED_BENCH_ENTRIES,
} = require('./compile_gate.cjs');

const IMPL = path.resolve(__dirname, '../../../../..');

/** A clean emitter result, over the shape the real roster prints today. */
function emitted(namespaces) {
  return { error: null, status: 0, stdout: namespaces.join('\n') + '\n', stderr: '' };
}

const SEVEN = [
  're-frame.hicasso.forms',
  're-frame.hicasso.impl.overlay',
  're-frame.hicasso.impl.presence',
  're-frame.hicasso.impl.presence-react',
  're-frame.hicasso.motion',
  're-frame.hicasso.native',
  're-frame.hicasso.overlay',
];

const cases = [];
function test(name, fn) {
  cases.push([name, fn]);
}

// ---------------------------------------------------------------------------
// THE DIRECTION THAT MUST PASS. It is first because every refusal below is
// worthless without it: a decider that had simply started refusing everything
// would satisfy all four and pin nothing.
// ---------------------------------------------------------------------------

test('a clean emission becomes the entry list', () => {
  const d = decideModuleNamespaces(emitted(SEVEN));
  assert.equal(d.ok, true);
  assert.deepEqual(d.namespaces, SEVEN);
});

test('duplicates collapse and order is normalised', () => {
  const d = decideModuleNamespaces(
    emitted(['re-frame.hicasso.overlay', 're-frame.hicasso.forms', 're-frame.hicasso.overlay',
      're-frame.hicasso.motion', 're-frame.hicasso.native']),
  );
  assert.equal(d.ok, true);
  assert.deepEqual(d.namespaces, [
    're-frame.hicasso.forms',
    're-frame.hicasso.motion',
    're-frame.hicasso.native',
    're-frame.hicasso.overlay',
  ]);
});

test('trailing whitespace and blank lines are not entries', () => {
  const d = decideModuleNamespaces({
    error: null,
    status: 0,
    stdout: `\n  ${SEVEN[0]}  \r\n${SEVEN[1]}\n\n${SEVEN[2]}\n${SEVEN[3]}\n\n`,
    stderr: '',
  });
  assert.equal(d.ok, true);
  assert.deepEqual(d.namespaces, SEVEN.slice(0, 4).sort());
});

// ---------------------------------------------------------------------------
// THE FOUR REFUSALS
// ---------------------------------------------------------------------------

test('REFUSAL 1 — the emitter could not be run at all', () => {
  const d = decideModuleNamespaces({
    error: new Error('spawn python ENOENT'),
    status: null,
    stdout: '',
    stderr: '',
  });
  assert.equal(d.ok, false);
  assert.match(d.reason, /could not run/);
  assert.match(d.detail.join('\n'), /ENOENT/);
});

test('REFUSAL 2 — the emitter ran and failed', () => {
  const d = decideModuleNamespaces({
    error: null,
    status: 2,
    stdout: '',
    stderr: 'Traceback (most recent call last):\nNameError: MODULES\n',
  });
  assert.equal(d.ok, false);
  assert.match(d.reason, /exited 2/);
  assert.match(d.detail.join('\n'), /NameError/);
});

test('REFUSAL 3 — an empty or collapsed list, which is the silent one', () => {
  // The direction that matters most. A green exit and no output reads exactly
  // like a module set that has legitimately shrunk to nothing, and compiling
  // the survivors would report success over everything it dropped.
  for (const stdout of ['', '\n', '\n\n  \n']) {
    const d = decideModuleNamespaces({ error: null, status: 0, stdout, stderr: '' });
    assert.equal(d.ok, false, JSON.stringify(stdout));
    assert.match(d.reason, /collapsed/);
  }
  const short = decideModuleNamespaces(emitted(SEVEN.slice(0, MIN_MODULE_NAMESPACES - 1)));
  assert.equal(short.ok, false);
  assert.match(short.reason, /floor 4/);

  // And the floor does not bite at the floor — an off-by-one here would red a
  // correct tree the first time a module was retired.
  assert.equal(decideModuleNamespaces(emitted(SEVEN.slice(0, MIN_MODULE_NAMESPACES))).ok, true);
});

test('REFUSAL 4 — a token that is not a namespace', () => {
  // shadow-cljs would take one of these into `:entries` and fail with a
  // resolution error naming a file nobody wrote, which sends the reader
  // somewhere this gate can already say is wrong.
  for (const bad of [
    'hicasso optional-module reachability: OK',  // the banner, on the wrong stream
    'notanamespace',                             // no dot: a word, not a namespace
    're-frame.hicasso.overlay)',                 // punctuation from a half-parse
    '"re-frame.hicasso.overlay"',                // quoted, e.g. printed as a repr
  ]) {
    const d = decideModuleNamespaces(emitted([...SEVEN, bad]));
    assert.equal(d.ok, false, bad);
    assert.match(d.reason, /not namespaces/, bad);
    assert.match(d.detail.join('\n'), /\S/, bad);
  }
});

// ---------------------------------------------------------------------------
// THE LIVE ARM. Everything above is fixtures, and fixtures go on passing over
// an emitter that has been renamed, moved or taught a different flag.
// ---------------------------------------------------------------------------

test('the real roster still answers, and answers namespaces', () => {
  const d = optionalModuleNamespaces(IMPL);
  assert.equal(
    d.ok,
    true,
    `\`python ${MODULE_ROSTER} ${MODULE_ROSTER_FLAG}\` did not answer: ` +
      (d.ok ? '' : `${d.reason}\n${(d.detail || []).join('\n')}`),
  );
  assert.ok(d.namespaces.length >= MIN_MODULE_NAMESPACES);
  for (const ns of d.namespaces) {
    assert.match(ns, /^re-frame\.hicasso\./, ns);
  }
  // The overlay module is the one rf2-okhdf was filed off. Naming it pins the
  // live arm to a subject rather than to a count.
  assert.ok(d.namespaces.includes('re-frame.hicasso.impl.overlay'), d.namespaces.join(', '));
});

// ---------------------------------------------------------------------------
// THE RE-HOMED CORE INSTRUMENTS — rf2-peorl.
//
// These two rows are the whole of the coverage the deleted `test:bspine-compile`
// gate used to give `core/test/re_frame/bench/{read_attribution_cljs,
// write_attribution}.cljs`. A row that has stopped naming a real namespace is
// the one way this entry source can go quiet while the gate stays green, so
// both directions are pinned: the live rows resolve, and a row that lies is
// refused.
// ---------------------------------------------------------------------------

test('the two re-homed rows still name real namespaces', () => {
  const { namespaces, broken } = verifyRoster(REHOMED_BENCH_ENTRIES);
  assert.deepEqual(broken, []);
  assert.deepEqual(namespaces.sort(), [
    're-frame.bench.read-attribution-cljs',
    're-frame.bench.write-attribution',
  ]);
});

test('a roster row whose file has moved, or been renamed, is REFUSED', () => {
  const root = fs.mkdtempSync(path.join(os.tmpdir(), 'rehomed-roster-'));
  try {
    fs.mkdirSync(path.join(root, 'bench'), { recursive: true });
    fs.writeFileSync(path.join(root, 'bench', 'kept.cljs'), '(ns re-frame.bench.kept)\n');
    fs.writeFileSync(path.join(root, 'bench', 'renamed.cljs'), '(ns re-frame.bench.other)\n');
    fs.writeFileSync(path.join(root, 'bench', 'headless.cljs'), ';; no ns form here\n');

    const row = (ns, file) => [{ ns, file: path.join('bench', file) }];

    // The direction that must pass, first — a verifier that had started
    // refusing everything would satisfy all three refusals below and pin nothing.
    assert.deepEqual(verifyRoster(row('re-frame.bench.kept', 'kept.cljs'), root).broken, []);

    const gone = verifyRoster(row('re-frame.bench.gone', 'gone.cljs'), root);
    assert.equal(gone.namespaces.length, 0);
    assert.match(gone.broken.join('\n'), /no such file/);

    const renamed = verifyRoster(row('re-frame.bench.renamed', 'renamed.cljs'), root);
    assert.equal(renamed.namespaces.length, 0);
    assert.match(renamed.broken.join('\n'), /declares re-frame\.bench\.other/);

    const headless = verifyRoster(row('re-frame.bench.headless', 'headless.cljs'), root);
    assert.equal(headless.namespaces.length, 0);
    assert.match(headless.broken.join('\n'), /no readable ns form/);
  } finally {
    fs.rmSync(root, { recursive: true, force: true, maxRetries: 5, retryDelay: 100 });
  }
});

let failed = 0;
for (const [name, fn] of cases) {
  try {
    fn();
    console.log(`  ok  ${name}`);
  } catch (e) {
    failed += 1;
    console.error(`  FAIL  ${name}`);
    console.error(`        ${e.message}`);
  }
}
if (failed > 0) {
  console.error(`compile_gate.test.cjs: ${failed} of ${cases.length} failed`);
  process.exit(1);
}
console.log(`compile_gate.test.cjs: ${cases.length} passed`);
