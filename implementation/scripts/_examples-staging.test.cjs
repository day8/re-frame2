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
  stageExample,
  stagePerExampleAssets,
  PER_EXAMPLE_ASSETS,
  EXAMPLES_ROOT,
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

// ---- serve-example dev-runner clean-stage boundary (rf2-rg2tze) ----------
//
// `npm run dev:example` / serve-example.cjs used to overlay index.html +
// _shared onto whatever a PRIOR run left in the selected output dir — so a
// stale main.js (or a retired asset) stayed serveable, and in watch mode the
// browser could render that old bundle while the runner had already printed a
// live URL. The fix pins serve-example to the SAME clean-then-stage boundary
// the CI/Story orchestrators use: cleanStageDirs([entry.outDir], OUT_ROOT)
// BEFORE stageExample(entry). These tests pin both the behaviour (a stale
// main.js is removed, siblings survive, assets land) and the call-site (the
// clean precedes the stage), so a refactor that drops the clean fails here.

it('dev-runner clean-then-stage removes a stale main.js and re-stages the example (rf2-rg2tze)', () => {
  // Seed a temp OUT_ROOT with a selected dir holding a STALE main.js + retired
  // asset from a "prior run", plus a sibling output another build relies on.
  // Run the EXACT sequence serve-example.cjs now performs — clean the selected
  // dir, then stageExample — and prove the stale bundle is gone, the example's
  // index.html landed fresh, and the sibling survived.
  const tmpRoot = fs.mkdtempSync(path.join(os.tmpdir(), 'rf2-serve-'));
  try {
    const outRoot = path.join(tmpRoot, 'out', 'examples');
    const sel = path.join(outRoot, 'counter-uix');
    const sibling = path.join(outRoot, 'login-uix');
    fs.mkdirSync(sel, { recursive: true });
    fs.mkdirSync(sibling, { recursive: true });
    // A stale bundle + retired asset the current source no longer produces.
    fs.writeFileSync(path.join(sel, 'main.js'), 'STALE_BUNDLE');
    fs.writeFileSync(path.join(sel, 'retired-asset.js'), 'RETIRED');
    fs.writeFileSync(path.join(sibling, 'main.js'), 'SIBLING_BUNDLE');

    // A real (temp) hand-written index.html for the example source.
    const srcDir = path.join(tmpRoot, 'src');
    fs.mkdirSync(srcDir, { recursive: true });
    const htmlSrc = path.join(srcDir, 'index.html');
    fs.writeFileSync(htmlSrc, '<!doctype html><title>counter-uix</title>');
    const entry = { build: 'examples/counter-uix', outDir: sel, htmlSrc, srcDir };

    // The serve-example contract: clean the SELECTED dir first, then stage.
    cleanStageDirs([entry.outDir], outRoot);
    stageExample(entry);

    assert.ok(!fs.existsSync(path.join(sel, 'main.js')), 'the stale main.js must be removed by the clean');
    assert.ok(!fs.existsSync(path.join(sel, 'retired-asset.js')), 'the retired asset must be removed');
    assert.ok(fs.existsSync(path.join(sel, 'index.html')), 'the example index.html must be staged fresh');
    assert.strictEqual(
      fs.readFileSync(path.join(sel, 'index.html'), 'utf8'),
      '<!doctype html><title>counter-uix</title>',
      'the staged index.html must be the current source',
    );
    // The sibling output another build/run relies on must be untouched.
    assert.ok(fs.existsSync(path.join(sibling, 'main.js')), 'a sibling output dir must survive a narrow clean');
    assert.strictEqual(fs.readFileSync(path.join(sibling, 'main.js'), 'utf8'), 'SIBLING_BUNDLE');
  } finally {
    fs.rmSync(tmpRoot, { recursive: true, force: true });
  }
});

it('serve-example.cjs calls cleanStageDirs on the selected outDir BEFORE stageExample (rf2-rg2tze)', () => {
  const src = fs.readFileSync(
    path.join(__dirname, '..', '..', 'examples', 'scripts', 'serve-example.cjs'),
    'utf8',
  );
  assert.ok(
    /cleanStageDirs\s*\(\s*\[\s*entry\.outDir\s*\]\s*,\s*OUT_ROOT\s*\)/.test(src),
    'serve-example must clean the selected entry.outDir under OUT_ROOT (clean-stage boundary)',
  );
  const cleanAt = src.indexOf('cleanStageDirs([entry.outDir], OUT_ROOT)');
  const stageAt = src.indexOf('stageExample(entry)');
  assert.ok(cleanAt !== -1 && stageAt !== -1, 'both the clean call and the stage call must be present');
  assert.ok(cleanAt < stageAt, 'the clean must precede the stage so no stale file survives into the served dir');
});

// ---- serve-example dev-runner exit-code decision (rf2-35lfqo) ------------
//
// serve-example.cjs used to return 0 unconditionally once the http-server
// exited — so a `shadow-cljs watch` that crashed (compile loop / JVM error)
// or an http-server that fell over false-greened the dev runner. The pure
// decideRunnerExit helper now maps the observed child outcomes to the runner's
// exit code: clean/interrupted shutdown -> 0, any unexpected child crash -> 1.

const { decideRunnerExit } = require('../../examples/scripts/serve-example.cjs');

it('decideRunnerExit returns 0 on a clean interrupted shutdown (Ctrl+C)', () => {
  // User hit Ctrl+C: both children killed by our teardown signal — expected.
  assert.strictEqual(
    decideRunnerExit({
      server: { code: null, signal: 'SIGTERM' },
      watch: { code: null, signal: 'SIGTERM' },
      interrupted: true,
    }),
    0,
  );
  // A clean code-0 exit is also success, interrupted or not.
  assert.strictEqual(decideRunnerExit({ server: { code: 0, signal: null } }), 0);
  // No children recorded (e.g. nothing ran) is not a failure.
  assert.strictEqual(decideRunnerExit({}), 0);
});

it('TEETH: decideRunnerExit returns 1 when shadow-cljs watch crashes unexpectedly', () => {
  // The watch died with a non-zero code while the user did NOT interrupt —
  // the exact false-green the prior unconditional `return 0` masked.
  assert.strictEqual(
    decideRunnerExit({
      server: { code: 0, signal: null },
      watch: { code: 1, signal: null },
      interrupted: false,
    }),
    1,
  );
  // A signal kill that is NOT our teardown (not interrupted) is also a crash.
  assert.strictEqual(
    decideRunnerExit({
      watch: { code: null, signal: 'SIGSEGV' },
      interrupted: false,
    }),
    1,
  );
});

it('TEETH: decideRunnerExit returns 1 when http-server exits non-zero unexpectedly', () => {
  assert.strictEqual(
    decideRunnerExit({
      server: { code: 1, signal: null },
      watch: { code: 0, signal: null },
      interrupted: false,
    }),
    1,
  );
});

it('decideRunnerExit treats a teardown-signal kill during interrupt as expected (not a crash)', () => {
  // During an interrupt, a child killed by SIGINT/SIGTERM is part of teardown.
  assert.strictEqual(
    decideRunnerExit({
      server: { code: null, signal: 'SIGINT' },
      watch: { code: null, signal: 'SIGINT' },
      interrupted: true,
    }),
    0,
  );
  // But a NON-teardown signal even during interrupt is still a crash.
  assert.strictEqual(
    decideRunnerExit({
      watch: { code: null, signal: 'SIGSEGV' },
      interrupted: true,
    }),
    1,
  );
});

// ---- per-example static assets (rf2-cq6va5) ------------------------------
//
// The clean-stage boundary recreates the selected output dir EMPTY, so any
// per-example static asset an example references via a flat (output-root-
// relative) href / fetch URL must be re-staged each run or it 404s after a
// clean. `stageExample` now stages index.html + _shared + the declared
// per-example assets; these tests prove the declared assets land after a clean
// stage and that a missing source fails LOUD (not a silent skip that ships an
// unstyled / 404ing / broken-image page).

it('PER_EXAMPLE_ASSETS declares the documented per-example assets (rf2-cq6va5)', () => {
  // TodoMVC's official CSS (from node_modules), managed-http-counter's success
  // fixture, and both RealWorld variants' fallback avatar.
  assert.deepStrictEqual(
    PER_EXAMPLE_ASSETS['examples/todomvc'].map((a) => a.dest).sort(),
    ['base.css', 'index.css'],
    'TodoMVC must stage base.css + index.css',
  );
  assert.ok(
    PER_EXAMPLE_ASSETS['examples/todomvc'].every((a) => a.from === 'node-modules'),
    'TodoMVC CSS is fetched into node_modules, not vendored',
  );
  assert.ok(
    PER_EXAMPLE_ASSETS['examples/managed-http-counter'].some((a) =>
      /inc\.json$/.test(a.dest),
    ),
    'managed-http-counter must stage its api/inc.json success fixture',
  );
  for (const build of ['examples/realworld', 'examples/realworld-resources']) {
    assert.ok(
      PER_EXAMPLE_ASSETS[build].some((a) => a.dest === 'default-avatar.svg'),
      `${build} must stage default-avatar.svg`,
    );
  }
});

it('stageExample stages a colocated per-example asset after a clean stage (rf2-cq6va5)', () => {
  // managed-http-counter: its api/inc.json lives colocated in the source folder
  // and must land under outDir/api/inc.json after a clean stage. Drive the real
  // manifest against the real repo source through the real clean-then-stage
  // sequence serve-example performs.
  const srcDir = path.join(EXAMPLES_ROOT, 'core', 'managed_http_counter');
  const htmlSrc = path.join(srcDir, 'index.html');
  assert.ok(fs.existsSync(htmlSrc), `fixture precondition: ${htmlSrc} must exist in-repo`);

  const tmpRoot = fs.mkdtempSync(path.join(os.tmpdir(), 'rf2-asset-'));
  try {
    const outRoot = path.join(tmpRoot, 'out', 'examples');
    const sel = path.join(outRoot, 'managed-http-counter');
    const entry = { build: 'examples/managed-http-counter', outDir: sel, htmlSrc, srcDir };

    cleanStageDirs([entry.outDir], outRoot);
    stageExample(entry);

    const staged = path.join(sel, 'api', 'inc.json');
    assert.ok(fs.existsSync(staged), `the success fixture must be staged at ${staged}`);
    assert.strictEqual(
      fs.readFileSync(staged, 'utf8'),
      fs.readFileSync(path.join(srcDir, 'api', 'inc.json'), 'utf8'),
      'the staged fixture must be a faithful copy of the source',
    );
    // The RealWorld avatar pattern is the same colocated-:src shape — exercise
    // it too so both RealWorld variants are covered.
    const rwSrc = path.join(EXAMPLES_ROOT, 'real-apps', 'realworld_http');
    const rwEntry = {
      build: 'examples/realworld',
      outDir: path.join(outRoot, 'realworld'),
      htmlSrc: path.join(rwSrc, 'index.html'),
      srcDir: rwSrc,
    };
    cleanStageDirs([rwEntry.outDir], outRoot);
    stageExample(rwEntry);
    assert.ok(
      fs.existsSync(path.join(rwEntry.outDir, 'default-avatar.svg')),
      'the RealWorld fallback avatar must be staged',
    );
  } finally {
    fs.rmSync(tmpRoot, { recursive: true, force: true });
  }
});

it('stagePerExampleAssets FAILS LOUD on a missing declared asset source (rf2-cq6va5)', () => {
  // Point a manifest-declared build at a srcDir that lacks the asset — staging
  // must throw with the offending path, never silently ship a broken page.
  const tmpRoot = fs.mkdtempSync(path.join(os.tmpdir(), 'rf2-asset-miss-'));
  try {
    const emptySrc = path.join(tmpRoot, 'src');
    fs.mkdirSync(emptySrc, { recursive: true });
    const entry = {
      build: 'examples/realworld', // declares default-avatar.svg as a :src asset
      outDir: path.join(tmpRoot, 'out'),
      srcDir: emptySrc,
    };
    fs.mkdirSync(entry.outDir, { recursive: true });
    assert.throws(
      () => stagePerExampleAssets(entry),
      /required asset for 'examples\/realworld' is missing/,
      'a missing per-example asset source must fail loud',
    );
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
