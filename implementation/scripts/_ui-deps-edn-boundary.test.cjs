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
 * Standalone node-runnable suite (no external framework), matching the sibling
 * _*.test.cjs convention.
 */

'use strict';

const assert = require('assert/strict');
const fs = require('fs');
const path = require('path');
const {
  productionDepsMap,
  mapHasSymbolKey,
} = require('./lib/edn.cjs');

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
function ednForbiddenPresent(depsEdn) {
  const deps = productionDepsMap(depsEdn, fail);
  assert.ok(
    mapHasSymbolKey(deps, 'day8/re-frame2'),
    'positive control day8/re-frame2 must be present',
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
