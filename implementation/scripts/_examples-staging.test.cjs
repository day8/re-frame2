#!/usr/bin/env node
/*
 * Tests for `examples/scripts/examples-staging.cjs` — the shared staging
 * helpers + the standalone-example manifest derived from shadow-cljs.edn
 * (rf2-pdo5mx).
 *
 * What these pin
 * --------------
 *   - parseExampleBuilds reads EVERY `:examples/<name>` build def, including
 *     the brace-on-the-NEXT-line shape shadow-cljs.edn actually uses (the
 *     parser's first cut used a single non-greedy regex + lookahead that
 *     silently skipped every OTHER entry — the consume-the-delimiter bug;
 *     these tests would have caught it).
 *   - each parsed build recovers its :output-dir and :init-fn.
 *   - the real-repo derivation is non-vacuous (the project ships well over a
 *     dozen example builds) and the three UIx examples resolve to a runnable
 *     entry with a colocated index.html on disk.
 *
 * Standalone node-runnable suite — no external test framework. Wired into
 * package.json via `test:script-policy`.
 */

'use strict';

const fs = require('fs');
const assert = require('assert');

const {
  parseExampleBuilds,
  listStandaloneExamples,
  readShadowEdn,
} = require('../../examples/scripts/examples-staging.cjs');

let failed = 0;

function it(label, f) {
  try {
    f();
    console.log(`  PASS  ${label}`);
  } catch (err) {
    failed++;
    console.error(`  FAIL  ${label}`);
    console.error(`        ${err.message || err}`);
  }
}

console.log('examples-staging tests (rf2-pdo5mx)');

// ---- parser: synthetic fixture, both brace placements --------------------

// Mirrors shadow-cljs.edn shape: a top-level map of build defs, with the
// build-id key on its OWN line and the opening `{` on the FOLLOWING line
// (the shape that broke the first parser cut), plus an inline-brace variant
// to prove both are read. Adjacent example builds must BOTH be recovered.
const FIXTURE = `{:builds
 {:node-test {:target :node-test}

  :examples/alpha
  {:target     :browser
   :output-dir "out/examples/alpha"
   :asset-path "."
   :modules    {:main {:init-fn alpha.core/run}}}

  ;; a comment line :examples/should-be-ignored {
  :examples/beta
  {:target     :browser
   :output-dir "out/examples/beta"
   :modules    {:main {:init-fn beta.views/run}}}

  :examples/gamma {:target :browser
                   :output-dir "out/examples/gamma"
                   :modules {:main {:init-fn seven-guis.gamma.core/run}}}

  :some-other-build
  {:target :browser}}}`;

it('parseExampleBuilds recovers every adjacent example build (no skip-every-other bug)', () => {
  const builds = parseExampleBuilds(FIXTURE);
  assert.deepStrictEqual(
    builds.map((b) => b.build).sort(),
    ['examples/alpha', 'examples/beta', 'examples/gamma'],
  );
});

it('parseExampleBuilds recovers :output-dir + :init-fn per build', () => {
  const byId = Object.fromEntries(parseExampleBuilds(FIXTURE).map((b) => [b.build, b]));
  assert.strictEqual(byId['examples/alpha'].outputDir, 'out/examples/alpha');
  assert.strictEqual(byId['examples/alpha'].initFn, 'alpha.core/run');
  assert.strictEqual(byId['examples/beta'].initFn, 'beta.views/run');
  assert.strictEqual(byId['examples/gamma'].initFn, 'seven-guis.gamma.core/run');
  assert.strictEqual(byId['examples/gamma'].outputDir, 'out/examples/gamma');
});

it('parseExampleBuilds ignores commented-out build keys', () => {
  const ids = parseExampleBuilds(FIXTURE).map((b) => b.build);
  assert.ok(!ids.includes('examples/should-be-ignored'));
});

// ---- real-repo derivation: non-vacuous + the three UIx examples ----------

it('parseExampleBuilds(shadow-cljs.edn) is non-vacuous and every build has out+init', () => {
  const builds = parseExampleBuilds(readShadowEdn());
  assert.ok(
    builds.length >= 30,
    `expected the full example set (>=30), got ${builds.length} — parser/edn drift`,
  );
  for (const b of builds) {
    assert.ok(b.outputDir, `build ${b.build} missing :output-dir`);
    assert.ok(b.initFn, `build ${b.build} missing :init-fn`);
  }
});

it('listStandaloneExamples resolves the three UIx examples to a colocated index.html', () => {
  const byId = Object.fromEntries(listStandaloneExamples().map((e) => [e.build, e]));
  for (const build of ['examples/counter-uix', 'examples/login-uix', 'examples/dashboard-uix']) {
    const e = byId[build];
    assert.ok(e, `runnable manifest missing ${build}`);
    assert.ok(fs.existsSync(e.htmlSrc), `${build} index.html missing on disk: ${e.htmlSrc}`);
    assert.ok(
      /out[\\/]examples[\\/]/.test(e.outDir),
      `${build} outDir not under out/examples: ${e.outDir}`,
    );
  }
});

if (failed > 0) {
  console.error(`\nexamples-staging tests: ${failed} FAILED.`);
  process.exit(1);
}
console.log('\nexamples-staging tests: all passed.');
