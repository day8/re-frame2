#!/usr/bin/env node
/*
 * Self-test for the EDN-aware G-13 production-dependency boundary (rf2-2n0cv).
 *
 * PR #6039 proved the boundary with `productionDepsBlock`: it found the first
 * `:deps` substring, counted raw `{`/`}` bytes to slice out a block of TEXT,
 * then ran two regexes over that text. A byte scan cannot tell a real `}` from
 * one inside a `; comment` or a "string", nor a real `:deps` from one inside a
 * `#_` reader-discarded form — so three valid deps.edn shapes that DO carry
 * day8/re-frame2-resources in the real production :deps map each sliced out a
 * truncated block whose forbidden regex missed, reporting forbidden = false: a
 * silent false GREEN on a dependency-boundary gate (the worst failure mode).
 *
 * This suite pins the regression. For each shape it shows the OLD brace scan
 * false-greened (positive control still passed, forbidden missed), and that the
 * NEW EDN-aware check (scripts/lib/edn.cjs) reads the real :deps map and catches
 * the forbidden production dependency. It also pins the fail-closed behaviour
 * and that the real ui/deps.edn stays green with its :test-only extra-deps
 * (which legitimately include day8/re-frame2-resources) permitted.
 *
 * rf2-han0r extends it to the gate's POSITIVE control, which had the mirror-image
 * weakness one level up. The structural EDN parse above is genuine, so the
 * boundary's FORBIDDEN half reads real map keys — but the positive half asked
 * only `mapHasSymbolKey(deps, 'day8/re-frame2')`. Key presence: the coordinate
 * the key names was never inspected, so `{:local/root "../nowhere"}`,
 * `{:mvn/version "0.0.0-BOGUS"}` and a `:git/url` all satisfied it, and this
 * suite pinned that same weak assertion. A positive control that passes whatever
 * the coordinate says cannot fail. It is now bound to the local root ui/deps.edn
 * configures — the same binding G-12's Arm 2 uses (rf2-5e3ic), sharing that
 * gate's expected root and path comparator rather than answering the same
 * question a second way — and the teeth below drive every rejected shape on both
 * the Windows and POSIX path shapes the gate runs on.
 *
 * Standalone node-runnable suite (no external framework), matching the sibling
 * _*.test.cjs convention.
 */

'use strict';

const assert = require('assert/strict');
const fs = require('fs');
const path = require('path');
const {
  readEdn,
  EdnReadError,
  productionDepsMap,
  mapHasSymbolKey,
} = require('./lib/edn.cjs');
// The REAL gate rule, imported from the runner rather than restated here
// (rf2-han0r). `run-ui-g13.cjs` requires Playwright lazily, inside `main()`,
// precisely so this node-only suite can pin the shipped predicate: a hand-copied
// restatement can drift from the gate silently, which is the whole failure class
// this file exists to catch.
const {
  coreCoordinateResolvesTo,
  declaredCoreLocalRoot,
  CORE_COORDINATE,
  CORE_LOCAL_ROOT,
  UI_DIR,
} = require('./run-ui-g13.cjs');

// A faithful copy of the retired brace-counting extractor + its two regexes,
// kept ONLY here to demonstrate, per shape, exactly what the old proof did.
function legacyBoundary(depsEdn) {
  const key = depsEdn.indexOf(':deps');
  if (key < 0) throw new Error('legacy: no :deps');
  const open = depsEdn.indexOf('{', key);
  let block = '';
  let depth = 0;
  for (let i = open; i < depsEdn.length; i += 1) {
    const ch = depsEdn[i];
    if (ch === '{') depth += 1;
    else if (ch === '}' && (depth -= 1) === 0) {
      block = depsEdn.slice(open, i + 1);
      break;
    }
  }
  return {
    positiveControl: /day8\/re-frame2\s+\{/.test(block),
    forbidden: /day8\/re-frame2-resources\s+\{/.test(block),
  };
}

function fail(message) {
  throw new Error(message);
}

// The EDN-aware boundary, exactly as run-ui-g13.cjs now applies it: parse the
// real top-level :deps map, require the positive control, report the forbidden
// dependency's presence as a real map-key membership.
//
// The positive control is the runner's own BOUND predicate (rf2-han0r). It used
// to be `mapHasSymbolKey(deps, 'day8/re-frame2')` — KEY PRESENCE, pinned here at
// the same weakness as the gate: the coordinate the key names was never read, so
// `{:local/root "../nowhere"}`, `{:mvn/version "0.0.0-BOGUS"}` and a `:git/url`
// all satisfied it. The structural EDN parse was never the problem; the gap sat
// one level up, at the VALUE.
function ednForbiddenPresent(depsEdn) {
  const deps = productionDepsMap(depsEdn, fail);
  assert.ok(
    coreCoordinateResolvesTo(deps),
    `positive control ${CORE_COORDINATE} must resolve to ${CORE_LOCAL_ROOT}`,
  );
  return mapHasSymbolKey(deps, 'day8/re-frame2-resources');
}

// Each shape declares day8/re-frame2-resources in the REAL production :deps map
// but formats it so the old byte scan truncates before reaching it.
const FALSE_GREEN_SHAPES = {
  'closing brace in a comment': `
{:paths ["src"]
 :deps {day8/re-frame2 {:local/root "../core"} ;; a stray } lives in this comment
        day8/re-frame2-resources {:local/root "../resources"}}}
`,
  'closing brace in a string': `
{:paths ["src"]
 :deps {day8/re-frame2 {:local/root "../core" :note "an unbalanced } brace"}
        day8/re-frame2-resources {:local/root "../resources"}}}
`,
  'reader-discarded first :deps form': `
{:paths ["src"]
 #_{:deps {day8/re-frame2 {:local/root "../core"}}}
 :deps {day8/re-frame2 {:local/root "../core"}
        day8/re-frame2-resources {:local/root "../resources"}}}
`,
};

const tests = [];
function test(name, thunk) {
  tests.push([name, thunk]);
}

for (const [name, edn] of Object.entries(FALSE_GREEN_SHAPES)) {
  test(`old brace scan false-greened — ${name}`, () => {
    const legacy = legacyBoundary(edn);
    assert.equal(
      legacy.positiveControl,
      true,
      'old positive control passed, so the old gate reported a genuine (false) green',
    );
    assert.equal(
      legacy.forbidden,
      false,
      'old byte scan MISSED the forbidden dependency — this is the false green',
    );
  });
  test(`EDN-aware check now catches it — ${name}`, () => {
    assert.equal(
      ednForbiddenPresent(edn),
      true,
      'EDN-aware check must detect the forbidden production dependency',
    );
  });
}

// A nested-collection / `:deps`-text-in-a-string decoy must NOT be mistaken for
// the real production :deps map, and the real one (forbidden-free) stays green.
test('nested collections + :deps text in a string do not false-red', () => {
  const edn = `
{:paths ["src" "resources"]
 :aliases {:x {:note "the string :deps {day8/re-frame2-resources} is not real"}}
 :deps {day8/re-frame2 {:local/root "../core"
                        :exclusions [org.clojure/clojure]}}}
`;
  assert.equal(ednForbiddenPresent(edn), false, 'no forbidden dep in the real prod :deps');
});

// Fail-closed contract: unreadable input, missing :deps, and a non-map :deps.
test('fail-closed — unreadable EDN', () => {
  assert.throws(
    () => productionDepsMap('{:deps {day8/re-frame2 {:local/root "../core"', fail),
    /not readable EDN/,
  );
});
test('fail-closed — missing :deps', () => {
  assert.throws(() => productionDepsMap('{:paths ["src"]}', fail), /no production :deps map/);
});
test('fail-closed — :deps is not a map', () => {
  assert.throws(
    () => productionDepsMap('{:deps [day8/re-frame2]}', fail),
    /:deps is not a map/,
  );
});
test('fail-closed — top-level form is not a map', () => {
  assert.throws(() => productionDepsMap('[:deps {}]', fail), /not a map/);
});

// The real, shipped ui/deps.edn: positive control present, forbidden absent
// from production :deps even though it appears under the :test alias.
test('real ui/deps.edn — positive control present, forbidden absent, :test permitted', () => {
  const realEdn = fs.readFileSync(path.join(__dirname, '..', 'ui', 'deps.edn'), 'utf8');
  const deps = productionDepsMap(realEdn, fail);
  assert.ok(mapHasSymbolKey(deps, 'day8/re-frame2'), 'day8/re-frame2 present in production :deps');
  assert.equal(
    mapHasSymbolKey(deps, 'day8/re-frame2-resources'),
    false,
    'day8/re-frame2-resources absent from production :deps (present only under :test)',
  );
  // Sanity: the real file DOES carry the forbidden dep somewhere (the :test
  // alias), so the green above is because the boundary is correctly scoped —
  // not because the token is missing from the file entirely.
  assert.ok(
    /day8\/re-frame2-resources/.test(realEdn),
    'real deps.edn carries day8/re-frame2-resources under :test — the boundary scopes it out',
  );
});

// The gate's own `optional Resources dependency leak` mutation tooth injects
// the forbidden dep into the production `:deps {` with a text replace. Prove
// that injected leak is still caught by the EDN-aware check (the tooth stays
// red), using the exact replacement run-ui-g13.cjs performs.
test('gate mutation tooth — injected production dependency leak stays red', () => {
  const realEdn = fs.readFileSync(path.join(__dirname, '..', 'ui', 'deps.edn'), 'utf8');
  const leaked = realEdn.replace(
    /(:deps\s*\{)/,
    '$1day8/re-frame2-resources {:local/root "../resources"} ',
  );
  assert.equal(
    ednForbiddenPresent(leaked),
    true,
    'EDN-aware check must catch the injected production dependency leak',
  );
});

// --- rf2-han0r: the positive control bound to the configured local root ------
//
// The gate runs on Windows locally and POSIX in CI, so every case is driven on
// BOTH path shapes on whichever host is executing. `run-ui-g13.cjs` selects its
// path resolver from the SHAPE of the artefact dir (not `process.platform`), and
// the comparator it borrows from G-12 realpath-resolves both sides where they
// exist, unifies separators, and folds case for drive-lettered paths only — so
// these fixtures mean the same thing on either runner.
const HOSTS = {
  POSIX: { uiDir: '/repo/implementation/ui', coreRoot: '/repo/implementation/core' },
  Windows: {
    uiDir: 'C:\\proj\\re-frame2\\implementation\\ui',
    coreRoot: 'C:\\proj\\re-frame2\\implementation\\core',
  },
};

const depsEdnDeclaring = (coordinate) => `{:paths ["src"] :deps {${coordinate}}}`;

// [label, coordinate, expected]. `null` shape = run on BOTH hosts.
const BOUND_CONTROL_CASES = [
  // The legitimate configuration — must stay green on both hosts.
  ['genuinely-resolved local root', 'day8/re-frame2 {:local/root "../core"}', true, null],
  // The four shapes the bead names. Every one of these was accepted by the
  // presence-only control (the mutation control below proves it).
  ['bogus :local/root', 'day8/re-frame2 {:local/root "../nowhere"}', false, null],
  ['bogus :mvn/version', 'day8/re-frame2 {:mvn/version "0.0.0-BOGUS"}', false, null],
  [
    'a :git/url coordinate',
    'day8/re-frame2 {:git/url "https://example.invalid/x.git" :git/sha "abc1234"}',
    false,
    null,
  ],
  ['coordinate absent', 'day8/re-frame2-other {:local/root "../core"}', false, null],
  // Wrong-directory roots of every shape: a real sibling artefact, the parent,
  // and an absolute root pointing elsewhere.
  ['sibling artefact root', 'day8/re-frame2 {:local/root "../resources"}', false, null],
  ['parent of the configured root', 'day8/re-frame2 {:local/root ".."}', false, null],
  ['absolute root, wrong directory', 'day8/re-frame2 {:local/root "/not/the/repo"}', false, 'POSIX'],
  ['absolute root, wrong drive path', 'day8/re-frame2 {:local/root "C:\\\\not\\\\the\\\\repo"}', false, 'Windows'],
  // Benign variations a legitimate root may arrive in — an over-tightened
  // predicate that reds these breaks G-13 against correct configurations, which
  // is worse than the false-green it replaced.
  ['absolute root, correct directory', 'day8/re-frame2 {:local/root "/repo/implementation/core"}', true, 'POSIX'],
  ['trailing separator', 'day8/re-frame2 {:local/root "../core/"}', true, null],
  ['redundant same-dir segment', 'day8/re-frame2 {:local/root "./../core"}', true, null],
  // Windows paths are case-insensitive and take either separator; POSIX is
  // case-SENSITIVE, so the same fold must NOT be granted there.
  ['drive root, forward separators', 'day8/re-frame2 {:local/root "C:/proj/re-frame2/implementation/core"}', true, 'Windows'],
  ['drive root, folded case', 'day8/re-frame2 {:local/root "c:\\\\PROJ\\\\re-frame2\\\\implementation\\\\CORE"}', true, 'Windows'],
  ['POSIX case mismatch stays rejected', 'day8/re-frame2 {:local/root "../CORE"}', false, 'POSIX'],
  // Degenerate values: nothing here names a directory.
  ['empty :local/root', 'day8/re-frame2 {:local/root ""}', false, null],
  ['whitespace-only :local/root', 'day8/re-frame2 {:local/root "   "}', false, null],
  ['non-string :local/root', 'day8/re-frame2 {:local/root ../core}', false, null],
  ['nil :local/root', 'day8/re-frame2 {:local/root nil}', false, null],
  ['coordinate value is not a map', 'day8/re-frame2 "1.2.3"', false, null],
];

for (const [label, coordinate, expected, onlyShape] of BOUND_CONTROL_CASES) {
  for (const [shape, host] of Object.entries(HOSTS)) {
    if (onlyShape && onlyShape !== shape) continue;
    test(`bound positive control (${shape}) — ${label} -> ${expected}`, () => {
      const deps = productionDepsMap(depsEdnDeclaring(coordinate), fail);
      assert.equal(
        coreCoordinateResolvesTo(deps, host.coreRoot, host.uiDir),
        expected,
        `${label} must ${expected ? 'satisfy' : 'fail'} the bound positive control on ${shape}`,
      );
    });
  }
}

// MUTATION CONTROL (mirrors rf2-5e3ic). Prove the teeth are REAL by showing the
// retired predicate — `mapHasSymbolKey`, still live for the forbidden check —
// ACCEPTS every rejection case above. If a future edit makes these pass under
// presence-only too, the fixtures have stopped discriminating and the control is
// no longer mutation-proof.
test('mutation control — the retired presence-only check accepted every rejected coordinate', () => {
  const rejected = BOUND_CONTROL_CASES.filter(
    // `coordinate absent` is the ONE shape presence-only did catch; it is pinned
    // above so binding the value did not cost the original tooth.
    ([label, , expected]) => expected === false && label !== 'coordinate absent',
  );
  assert.ok(rejected.length >= 10, 'the mutation control must cover a real spread of spoofs');
  for (const [label, coordinate] of rejected) {
    const deps = productionDepsMap(depsEdnDeclaring(coordinate), fail);
    assert.equal(
      mapHasSymbolKey(deps, CORE_COORDINATE),
      true,
      `presence-only should accept "${label}" — it was the false-green this bead closes`,
    );
  }
});

// The real, shipped ui/deps.edn must satisfy the bound control on THIS host,
// resolving through whatever junctions/symlinks the checkout actually uses.
test('real ui/deps.edn — core coordinate resolves to the configured local root on this host', () => {
  const realEdn = fs.readFileSync(path.join(__dirname, '..', 'ui', 'deps.edn'), 'utf8');
  const deps = productionDepsMap(realEdn, fail);
  assert.equal(
    declaredCoreLocalRoot(deps),
    '../core',
    'ui/deps.edn must declare the core coordinate as {:local/root "../core"}',
  );
  assert.ok(
    coreCoordinateResolvesTo(deps),
    `real ui/deps.edn must resolve ${CORE_COORDINATE} to ${CORE_LOCAL_ROOT}`,
  );
  // Non-vacuity: the expected root is the real core artefact next to ui/.
  assert.equal(CORE_LOCAL_ROOT, path.resolve(UI_DIR, '..', 'core'));
  assert.ok(fs.existsSync(path.join(CORE_LOCAL_ROOT, 'deps.edn')), 'core artefact exists');
});

// ---- rf2-vr11t: a `#_` discard is ignorable content, wherever it sits ------
//
// The reader is a SHARED authority — this boundary gate, the release lockstep
// (.github/scripts/verify-version-lockstep.sh, both its :local/root inventory
// and its runtime git-coordinate check) and the bundle-isolation gate's
// publishable-artefact discovery (lib/publishable-runtimes.cjs) all read EDN
// through it — so a hole in it is a hole in all three at once.
//
// It could not read a collection whose LAST element was a `#_` discard.
// readForm consumed the marker, read the datum it drops, then looped round to
// read "the real next form" — which, in that position, is the CLOSING
// DELIMITER, so readDatum threw `unexpected '}'`. A discard anywhere else read
// fine, which is why the shape above ('reader-discarded first :deps form')
// passed while this one did not.
//
// Every consumer fails CLOSED on a throw, so this was never a false green: the
// lockstep exits 2 refusing to report a verdict, and publishable-runtimes falls
// back to a raw `:clein/build` text match. But `#_`-commenting out the last
// dependency in a map while debugging is an entirely ordinary thing to write,
// and it made those gates UN-RUNNABLE. Discards are now skipped as ignorable
// content — like whitespace and `;` comments — BEFORE the closing-delimiter
// test rather than after it, so position stops mattering.
const DISCARD_SHAPES = [
  ['last element of a map', '{:deps {org.clojure/clojure {:mvn/version "1.12.0"}\n'
    + '        #_day8/de-dupe #_{:git/url "https://example.invalid/x.git"}}}'],
  ['last element of a vector', '[:a :b #_:c]'],
  ['last element of a list', '(:a :b #_:c)'],
  ['last element of a set', '#{:a :b #_:c}'],
  ['the collection\'s ONLY content', '{:deps {#_a #_1}}'],
  ['before the close, across a newline', '{:deps {:a 1\n        #_:b #_2\n       }}'],
  ['a `#_ #_ a b` pair-discard at the end', '{:deps {:a 1 #_ #_ :b 2}}'],
  ['discard then `;` comment then close', '{:deps {:a 1 #_:b ; trailing\n}}'],
];

for (const [where, text] of DISCARD_SHAPES) {
  test(`reader contract — trailing #_ discard: ${where}`, () => {
    assert.doesNotThrow(() => readEdn(text), `readEdn must accept a discard ${where}`);
  });
}

// The discard must actually DROP its datum, not merely be tolerated — a reader
// that skipped the marker and kept the form would read these as clean too.
test('reader contract — a discarded entry is absent from the parsed map', () => {
  const top = readEdn('{:deps {org.clojure/clojure {:mvn/version "1.12.0"}\n'
    + '        #_day8/de-dupe #_{:git/url "https://example.invalid/x.git"}}}');
  const deps = top.entries.find(([k]) => k.edn === 'keyword' && k.name === 'deps')[1];
  assert.equal(deps.entries.length, 1, 'the discarded coordinate must not appear as an entry');
  assert.ok(mapHasSymbolKey(deps, 'org.clojure/clojure'));
  assert.ok(!mapHasSymbolKey(deps, 'day8/de-dupe'), 'day8/de-dupe was discarded');
});

test('reader contract — `#_ #_ a b` drops BOTH forms', () => {
  const top = readEdn('{:deps {#_ #_ day8/de-dupe {:git/url "x"} org.clojure/clojure {:mvn/version "1.12.0"}}}');
  const deps = top.entries.find(([k]) => k.edn === 'keyword' && k.name === 'deps')[1];
  assert.equal(deps.entries.length, 1);
  assert.ok(mapHasSymbolKey(deps, 'org.clojure/clojure'));
});

// Fail-closed is not weakened: a discard marker with nothing to discard, and a
// genuinely unbalanced collection, must still be REFUSED rather than read.
test('reader contract — a discard with no following form is still refused', () => {
  assert.throws(() => readEdn('{:deps {:a 1 #_'), EdnReadError);
  assert.throws(() => readEdn('#_'), EdnReadError);
});

test('reader contract — an unterminated collection is still refused', () => {
  assert.throws(() => readEdn('{:deps {:a 1 #_:b'), EdnReadError);
  assert.throws(() => readEdn('{:deps {:a 1}'), EdnReadError);
});

let failed = 0;
for (const [name, thunk] of tests) {
  try {
    thunk();
    console.log(`ok — ${name}`);
  } catch (err) {
    failed += 1;
    console.error(`FAIL ${name}`);
    console.error(err && err.stack ? err.stack : err);
  }
}

if (failed > 0) {
  console.error(`ui-deps-edn-boundary tests: ${failed} failed.`);
  process.exit(1);
}
console.log(`ui-deps-edn-boundary tests: ${tests.length} passed.`);
