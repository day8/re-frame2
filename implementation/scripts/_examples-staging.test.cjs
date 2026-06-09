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
const os = require('os');
const path = require('path');
const assert = require('assert');

const {
  parseExampleBuilds,
  listStandaloneExamples,
  readShadowEdn,
  isStrictlyUnder,
  cleanStageDirs,
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

// ---- clean-stage boundary (rf2-bf4vdy) ----------------------------------
//
// The examples + Story harnesses share implementation/out/examples and used to
// OVERLAY staged fixtures onto it, so a file a previous run staged could remain
// under the served root (a stale-file false green). cleanStageDirs removes +
// recreates only the SELECTED output dirs, path-guarded so it can never touch
// the shared root or an out-of-tree path.

it('isStrictlyUnder is true only for a proper descendant (not self, not outside)', () => {
  const root = path.join('/tmp', 'out', 'examples');
  assert.ok(isStrictlyUnder(path.join(root, 'counter'), root), 'a child must be under');
  assert.ok(isStrictlyUnder(path.join(root, 'a', 'b'), root), 'a deep child must be under');
  assert.ok(!isStrictlyUnder(root, root), 'the root itself is NOT strictly under itself');
  assert.ok(!isStrictlyUnder(path.dirname(root), root), 'an ancestor is not under');
  assert.ok(!isStrictlyUnder(path.join('/tmp', 'other'), root), 'a sibling is not under');
});

it('cleanStageDirs REFUSES to delete the shared root itself (path guard)', () => {
  const root = path.join('/tmp', 'out', 'examples');
  assert.throws(
    () => cleanStageDirs([root], root, { io: noopIo() }),
    /not strictly under the owned staging root/,
    'cleaning OUT_ROOT itself must be refused',
  );
});

it('cleanStageDirs REFUSES an out-of-tree target (path guard)', () => {
  const root = path.join('/tmp', 'out', 'examples');
  assert.throws(
    () => cleanStageDirs([path.join('/tmp', 'elsewhere')], root, { io: noopIo() }),
    /not strictly under the owned staging root/,
    'cleaning a dir outside OUT_ROOT must be refused',
  );
  // A traversal escape (../) that resolves outside the root is also refused.
  assert.throws(
    () => cleanStageDirs([path.join(root, '..', '..', 'escape')], root, { io: noopIo() }),
    /not strictly under the owned staging root/,
    'a ../ escape must be refused',
  );
});

it('cleanStageDirs removes a stale file then recreates the dir empty (no stale residue)', () => {
  // Seed a real temp OUT_ROOT with a selected dir holding a STALE file that the
  // current source no longer produces, prove a clean run removes it.
  const tmpRoot = fs.mkdtempSync(path.join(os.tmpdir(), 'rf2-stage-'));
  try {
    const outRoot = path.join(tmpRoot, 'out', 'examples');
    const sel = path.join(outRoot, 'counter');
    const sibling = path.join(outRoot, 'login');
    fs.mkdirSync(sel, { recursive: true });
    fs.mkdirSync(sibling, { recursive: true });
    const staleFile = path.join(sel, 'retired-asset.js');
    const siblingFile = path.join(sibling, 'keep-me.js');
    fs.writeFileSync(staleFile, 'STALE');
    fs.writeFileSync(siblingFile, 'KEEP');

    cleanStageDirs([sel], outRoot);

    assert.ok(!fs.existsSync(staleFile), 'the stale file must be gone after clean-stage');
    assert.ok(fs.existsSync(sel), 'the selected dir must be recreated empty');
    assert.deepStrictEqual(fs.readdirSync(sel), [], 'the selected dir must be empty');
    // A narrow run must NOT wipe a sibling output another build/run relies on.
    assert.ok(fs.existsSync(siblingFile), 'a sibling output dir must be untouched');
  } finally {
    fs.rmSync(tmpRoot, { recursive: true, force: true });
  }
});

it('cleanStageDirs (re)creates a not-yet-existing selected dir (first run)', () => {
  const tmpRoot = fs.mkdtempSync(path.join(os.tmpdir(), 'rf2-stage-'));
  try {
    const outRoot = path.join(tmpRoot, 'out', 'examples');
    fs.mkdirSync(outRoot, { recursive: true });
    const sel = path.join(outRoot, 'never-built-yet');
    assert.ok(!fs.existsSync(sel));
    const cleaned = cleanStageDirs([sel], outRoot);
    assert.ok(fs.existsSync(sel), 'a missing selected dir must be created');
    assert.deepStrictEqual(cleaned, [path.resolve(sel)]);
  } finally {
    fs.rmSync(tmpRoot, { recursive: true, force: true });
  }
});

// A no-op io for the guard-refusal tests: they must throw BEFORE any fs call,
// so rmSync/mkdirSync are stubbed to fail loudly if ever reached.
function noopIo() {
  return {
    rmSync: () => { throw new Error('rmSync must not be called when the guard refuses'); },
    mkdirSync: () => { throw new Error('mkdirSync must not be called when the guard refuses'); },
  };
}

if (failed > 0) {
  console.error(`\nexamples-staging tests: ${failed} FAILED.`);
  process.exit(1);
}
console.log('\nexamples-staging tests: all passed.');
