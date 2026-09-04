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
 *     dozen example builds) and every DOCUMENTED build — core, capability and
 *     substrate (the three UIx examples plus the Hicasso login) — resolves to a
 *     runnable entry with a colocated index.html on disk.
 *
 * Standalone node-runnable suite — no external test framework. Discovered by `npm run test:scripts`.
 */

'use strict';

const fs = require('fs');
const os = require('os');
const path = require('path');
const assert = require('assert');

const {
  parseExampleBuilds,
  buildNsIndex,
  listStandaloneExamples,
  readShadowEdn,
  isStrictlyUnder,
  cleanStageDirs,
  stageExample,
  stagePerExampleAssets,
  PER_EXAMPLE_ASSETS,
  EXAMPLES_ROOT,
} = require('../../examples/scripts/examples-staging.cjs');

const {
  EXAMPLE_ASSET_MANIFEST,
  stagedAssetsByBuild,
} = require('../../examples/scripts/examples-asset-manifest.cjs');

// A SYNTHETIC manifest, injected so the staging projection + consumer are pinned
// without restating the production data (rf2-phpbo8). It carries a vendored-CSS
// entry (both assets html-linked, node_modules-sourced) and a staging-only
// colocated-fixture entry (not html-linked).
const SYNTHETIC_MANIFEST = [
  {
    build: 'examples/synth-vendored',
    page: 'examples/synth/vendored/index.html',
    reason: 'synthetic: links vendored CSS instead of the shared stylesheet',
    assetExemptions: ['_shared/css/style.css'],
    assets: [
      { from: 'node-modules', src: 'synth-common/base.css', dest: 'base.css', htmlLinked: true },
      { from: 'node-modules', src: 'synth-app/index.css', dest: 'index.css', htmlLinked: true },
    ],
  },
  {
    build: 'examples/synth-fixture',
    page: 'examples/synth/fixture/index.html',
    reason: 'synthetic: fetches a colocated fixture from app code (not the HTML)',
    assetExemptions: [],
    assets: [{ from: 'src', src: 'api/data.json', dest: 'api/data.json', htmlLinked: false }],
  },
];

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

// `it` above calls f() WITHOUT awaiting, so an async body's rejection escapes
// its try/catch and the test prints PASS regardless — a false green. Async
// cases (the first-build readiness wait, which polls) are therefore queued here
// and drained by the awaiting runner at the bottom of this file, which owns the
// summary + exit code for both kinds.
const asyncTests = [];
function itAsync(label, f) {
  asyncTests.push([label, f]);
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

  :examples/delta-server
  {:target      :node-library
   :output-dir  "out/examples/delta-server"
   :exports-var delta.server/module}

  :some-other-build
  {:target :browser}}}`;

it('parseExampleBuilds recovers every adjacent example build (no skip-every-other bug)', () => {
  const builds = parseExampleBuilds(FIXTURE);
  assert.deepStrictEqual(
    builds.map((b) => b.build).sort(),
    ['examples/alpha', 'examples/beta', 'examples/delta-server', 'examples/gamma'],
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

// `target` is what separates a PAGE build from a server-side one, and a
// non-`:browser` example build must still be RECOVERED (it is an example
// build; check-examples-compile.cjs compiles it) while carrying no :init-fn.
// Dropping it from the parse would hide it from every reader at once.
it('parseExampleBuilds recovers :target, and a :node-library build keeps no :init-fn', () => {
  const byId = Object.fromEntries(parseExampleBuilds(FIXTURE).map((b) => [b.build, b]));
  assert.strictEqual(byId['examples/alpha'].target, ':browser');
  assert.strictEqual(byId['examples/gamma'].target, ':browser');
  assert.strictEqual(byId['examples/delta-server'].target, ':node-library');
  assert.strictEqual(byId['examples/delta-server'].outputDir, 'out/examples/delta-server');
  assert.strictEqual(byId['examples/delta-server'].initFn, null);
});

// ---- real-repo derivation: non-vacuous + the three UIx examples ----------

// The out+init invariant is asserted of the PAGE builds — the `:browser` ones
// listStandaloneExamples turns into runnable entries. A server-side example
// build (`:node-library`, publishing an `:exports-var` for a Node sidecar to
// require — rf2-8arzr.5) is not a page and correctly declares neither, so
// holding it to the page invariant asserts something false of the domain.
// Every build must still declare a `:target`: a null one would silently drop a
// real page build out of the loop below, which is the vacuity this pins shut.
it('parseExampleBuilds(shadow-cljs.edn) is non-vacuous and every :browser build has out+init', () => {
  const builds = parseExampleBuilds(readShadowEdn());
  for (const b of builds) {
    assert.ok(b.target, `build ${b.build} declares no :target — parser/edn drift`);
  }
  const pages = builds.filter((b) => b.target === ':browser');
  assert.ok(
    pages.length >= 30,
    `expected the full example page set (>=30), got ${pages.length} of ${builds.length} — parser/edn drift`,
  );
  for (const b of pages) {
    assert.ok(b.outputDir, `build ${b.build} missing :output-dir`);
    assert.ok(b.initFn, `build ${b.build} missing :init-fn`);
  }
});

// ---- FAIL-CLOSED ns-index enumeration (rf2-3fc89f.31) --------------------
//
// buildNsIndex feeds listStandaloneExamples: an unreadable examples/ subtree (or
// an unreadable source file whose head we read for its ns) must FAIL CLOSED
// (throw, naming the path) rather than hide the ns and make the dev runner
// advertise the build as merely "not runnable". The old catch-and-continue
// swallowed BOTH the readdirSync AND the readFileSync failure. On OLD code
// buildNsIndex was not even exported and ignored an injected io, so these tests
// fail on old code (regression teeth).

// An io that delegates to the real fs but fails for ONE path: EACCES on
// readdirSync of `badDir`, or throws on readFileSync of `badFile`.
function failingFsIo({ badDir = null, badFile = null } = {}) {
  const realFs = require('fs');
  const bd = badDir && path.resolve(badDir);
  const bf = badFile && path.resolve(badFile);
  return {
    readdirSync: (dir, opts) => {
      if (bd && path.resolve(dir) === bd) {
        const e = new Error(`EACCES: permission denied, scandir '${dir}'`);
        e.code = 'EACCES';
        throw e;
      }
      return realFs.readdirSync(dir, opts);
    },
    readFileSync: (p, enc) => {
      if (bf && path.resolve(p) === bf) {
        const e = new Error(`EACCES: permission denied, open '${p}'`);
        e.code = 'EACCES';
        throw e;
      }
      return realFs.readFileSync(p, enc);
    },
  };
}

it('TEETH: buildNsIndex FAILS CLOSED on an unreadable subtree (rf2-3fc89f.31)', () => {
  const badDir = path.join(EXAMPLES_ROOT, 'core');
  assert.throws(
    () => buildNsIndex(EXAMPLES_ROOT, { io: failingFsIo({ badDir }) }),
    (err) =>
      /enumeration FAILED/.test(err.message) &&
      err.message.includes(badDir) &&
      Array.isArray(err.walkErrors),
    'an unreadable subtree must throw (naming the path), not hide the ns',
  );
});

it('TEETH: buildNsIndex FAILS CLOSED on an unreadable source file head (rf2-3fc89f.31)', () => {
  // Find a real .cljs source under examples/ to mark unreadable.
  const realFs = require('fs');
  const clean = buildNsIndex(EXAMPLES_ROOT, { io: realFs }); // a full clean index
  assert.ok(clean.size > 0, 'precondition: the clean ns-index is non-empty');
  const someSourceDir = [...clean.values()][0];
  const someSource = realFs
    .readdirSync(someSourceDir)
    .map((n) => path.join(someSourceDir, n))
    .find((p) => /\.clj[sc]$/.test(p));
  assert.ok(someSource, 'precondition: found a real source file to mark unreadable');
  assert.throws(
    () => buildNsIndex(EXAMPLES_ROOT, { io: failingFsIo({ badFile: someSource }) }),
    (err) => /enumeration FAILED/.test(err.message) && err.message.includes(someSource),
    'an unreadable ns source head must fail closed by name (not vanish from the index)',
  );
});

it('buildNsIndex builds the full index with a clean io (no false failure) (rf2-3fc89f.31)', () => {
  const idx = buildNsIndex(EXAMPLES_ROOT, { io: require('fs') });
  assert.ok(idx.size >= 30, `expected a non-vacuous ns-index, got ${idx.size}`);
});

// ---- the documented run recipes resolve to a real host page (rf2-iacw2, rf2-ujdn0)
//
// Every core and capability README tells the reader to run exactly
// `npm run dev:example -- <build-id>`. That recipe only reaches a page if the
// build is in listStandaloneExamples() AND has an index.html on disk to stage.
// If a build falls out of the runner's manifest, the README keeps READING
// correct while the command it documents stops working — the exact failure
// these rosters exist to catch. Keep them in step with the READMEs.
const DOCUMENTED_CORE_BUILDS = [
  'examples/counter',
  'examples/login',
  'examples/todomvc',
  'examples/flows',
  'examples/managed-http-counter',
  'examples/notebook',
  // the 7GUIs cluster, documented as a build-id table in seven_guis/README.md
  'examples/temperature',
  'examples/flight-booker',
  'examples/timer',
  'examples/crud',
  'examples/circle-drawer',
  'examples/cells',
];

const DOCUMENTED_CAPABILITY_BUILDS = [
  'examples/state-machine-walkthrough',
  'examples/routing',
  'examples/resources',
  'examples/infinite-feed',
  'examples/linearlite',
  'examples/ssr',
  'examples/resources-ssr',
  'examples/ssr-streaming',
];

const DOCUMENTED_SUBSTRATE_BUILDS = [
  'examples/counter-uix',
  'examples/login-uix',
  'examples/dashboard-uix',
  // rf2-fmns2 — the Hicasso login, the third arm of the one-model
  // three-view-layer comparison examples/substrates/README.md documents.
  'examples/login-hicasso',
];

it('the documented rosters are non-vacuous (a roster emptied by an edit cannot pass silently)', () => {
  assert.strictEqual(DOCUMENTED_CORE_BUILDS.length, 12, 'expected 12 documented core builds');
  assert.strictEqual(
    DOCUMENTED_CAPABILITY_BUILDS.length,
    8,
    'expected 8 documented capability builds',
  );
  assert.strictEqual(
    DOCUMENTED_SUBSTRATE_BUILDS.length,
    4,
    'expected 4 documented substrate builds',
  );
});

for (const [family, builds] of [
  ['core', DOCUMENTED_CORE_BUILDS],
  ['capability', DOCUMENTED_CAPABILITY_BUILDS],
  ['substrate', DOCUMENTED_SUBSTRATE_BUILDS],
]) {
  it(`listStandaloneExamples resolves every documented ${family} build to a colocated index.html`, () => {
    const byId = Object.fromEntries(listStandaloneExamples().map((e) => [e.build, e]));
    for (const build of builds) {
      const e = byId[build];
      assert.ok(e, `runnable manifest missing ${build} (documented as \`npm run dev:example -- ${build}\`)`);
      assert.ok(fs.existsSync(e.htmlSrc), `${build} index.html missing on disk: ${e.htmlSrc}`);
      assert.ok(
        /out[\\/]examples[\\/]/.test(e.outDir),
        `${build} outDir not under out/examples: ${e.outDir}`,
      );
    }
  });
}

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

// ---- manifest projection: staging consumer (rf2-phpbo8) ------------------
//
// The staging PER_EXAMPLE_ASSETS map is no longer a literal declaration — it is
// the projection of the single examples asset/exception manifest, the shared
// owner the static scanner also consumes. These pin the projection against a
// SYNTHETIC manifest (so the logic is exact, not a restated copy of production
// data) plus a real-manifest NON-VACUITY + cross-consistency check.

it('stagedAssetsByBuild projects a synthetic manifest to build -> [{from,src,dest}], dropping htmlLinked (rf2-phpbo8)', () => {
  const projected = stagedAssetsByBuild(SYNTHETIC_MANIFEST);
  // EVERY declared asset is staged regardless of htmlLinked (a scanner concern);
  // the staging view carries only from/src/dest.
  assert.deepStrictEqual(projected['examples/synth-vendored'], [
    { from: 'node-modules', src: 'synth-common/base.css', dest: 'base.css' },
    { from: 'node-modules', src: 'synth-app/index.css', dest: 'index.css' },
  ]);
  assert.ok(
    !('htmlLinked' in projected['examples/synth-vendored'][0]),
    'the staging projection must drop the scanner-only htmlLinked flag',
  );
  assert.deepStrictEqual(projected['examples/synth-fixture'], [
    { from: 'src', src: 'api/data.json', dest: 'api/data.json' },
  ]);
});

it('stagePerExampleAssets stages from an INJECTED synthetic-manifest projection (rf2-phpbo8)', () => {
  // Drive the real staging consumer with a synthetic manifest projection: a
  // colocated (:src) fixture must land under outDir/api/data.json after staging.
  const tmpRoot = fs.mkdtempSync(path.join(os.tmpdir(), 'rf2-synth-'));
  try {
    const srcDir = path.join(tmpRoot, 'src');
    fs.mkdirSync(path.join(srcDir, 'api'), { recursive: true });
    fs.writeFileSync(path.join(srcDir, 'api', 'data.json'), '{"ok":true}');
    const outDir = path.join(tmpRoot, 'out');
    fs.mkdirSync(outDir, { recursive: true });
    const entry = { build: 'examples/synth-fixture', outDir, srcDir };

    stagePerExampleAssets(entry, {
      perExampleAssets: stagedAssetsByBuild(SYNTHETIC_MANIFEST),
    });

    const staged = path.join(outDir, 'api', 'data.json');
    assert.ok(fs.existsSync(staged), `the synthetic fixture must be staged at ${staged}`);
    assert.strictEqual(fs.readFileSync(staged, 'utf8'), '{"ok":true}');
  } finally {
    fs.rmSync(tmpRoot, { recursive: true, force: true });
  }
});

it('the LIVE staging PER_EXAMPLE_ASSETS IS the real manifest projection, non-vacuously (rf2-phpbo8)', () => {
  // Cross-consistency: the exported staging map is exactly the real manifest's
  // staging projection (no second, drift-prone literal declaration).
  assert.deepStrictEqual(
    PER_EXAMPLE_ASSETS,
    stagedAssetsByBuild(EXAMPLE_ASSET_MANIFEST),
    'PER_EXAMPLE_ASSETS must be the manifest projection, not an independent copy',
  );
  // Non-vacuity: the real manifest declares the documented per-example builds,
  // and TodoMVC's official CSS is fetched from node_modules (not vendored).
  for (const build of [
    'examples/todomvc',
    'examples/managed-http-counter',
    'examples/realworld',
    'examples/realworld-resources',
  ]) {
    assert.ok(
      Array.isArray(PER_EXAMPLE_ASSETS[build]) && PER_EXAMPLE_ASSETS[build].length > 0,
      `the manifest must declare staged assets for ${build}`,
    );
  }
  assert.ok(
    PER_EXAMPLE_ASSETS['examples/todomvc'].every((a) => a.from === 'node-modules'),
    'TodoMVC CSS is fetched into node_modules, not vendored',
  );
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

// ---- serve-example first-build readiness (rf2-qwy3) ----------------------
//
// The dev runner used to print `<build> is live at <url>` the moment
// http-server proved it owned the staged root. In WATCH mode that root has just
// been cleaned (rf2-rg2tze) and `shadow-cljs watch` is asynchronous, so the
// advertised page served its host HTML + _shared assets while the `main.js` it
// requires was still absent: a blank, non-booted app, with no bundle loaded and
// therefore no shadow client to turn the later compile into a live reload.
// Server ownership and application-build readiness are different facts;
// serve-example composes them via waitForFirstBuild.
//
// These pin BOTH halves: the predicate's behaviour (delayed artifact, empty
// artifact, watch died before first output) and the call-site ordering (the
// wait precedes the live banner), so a refactor that re-opens the window fails
// here.

const {
  waitForFirstBuild,
  BUILD_ENTRYPOINT,
} = require('../../examples/scripts/serve-example.cjs');

const SERVE_EXAMPLE_SRC = fs.readFileSync(
  path.join(__dirname, '..', '..', 'examples', 'scripts', 'serve-example.cjs'),
  'utf8',
);

it('the entrypoint serve-example waits for is the one every example host loads', () => {
  // check-examples-assets.cjs makes `<script src="main.js">` mandatory on every
  // example page, which is what licenses the runner to key its readiness on a
  // fixed name rather than parsing each host page.
  assert.strictEqual(BUILD_ENTRYPOINT, 'main.js');
});

itAsync('waitForFirstBuild waits through a DELAYED artifact, then reports ready (rf2-qwy3)', async () => {
  // A fake producer that publishes only on the 4th probe — the cold-compile
  // case, where the server is ready long before the bundle exists.
  let probes = 0;
  const result = await waitForFirstBuild({
    fetchBody: async () => {
      probes++;
      return probes < 4 ? null : 'console.log("booted");';
    },
    isAborted: () => false,
    sleep: async () => {},
  });
  assert.deepStrictEqual(result, { ok: true });
  assert.strictEqual(probes, 4, 'must keep polling until the artifact is actually served');
});

itAsync('TEETH: waitForFirstBuild does NOT report ready on a zero-length artifact (rf2-qwy3)', async () => {
  // An empty main.js boots nothing. Announcing it would restate the exact lie
  // this wait removes, so an empty body must keep the runner waiting.
  let probes = 0;
  const result = await waitForFirstBuild({
    fetchBody: async () => {
      probes++;
      // Empty for the first three probes (the acceptance criterion's
      // "zero-length" case), then real content.
      return probes < 4 ? '' : 'console.log("booted");';
    },
    isAborted: () => false,
    sleep: async () => {},
  });
  assert.deepStrictEqual(result, { ok: true });
  assert.strictEqual(probes, 4, 'a zero-length entrypoint must not satisfy readiness');
});

itAsync('TEETH: waitForFirstBuild aborts (never reports ready) when the watch dies first (rf2-qwy3)', async () => {
  // The watch child crashed before its first compile landed. The runner must
  // NOT announce the app as live; the caller turns this into a non-zero exit.
  let watchDied = false;
  let probes = 0;
  const result = await waitForFirstBuild({
    fetchBody: async () => {
      probes++;
      watchDied = true; // the watch's exit handler fires between polls
      return null; // the entrypoint never appears
    },
    isAborted: () => watchDied,
    sleep: async () => {},
  });
  assert.deepStrictEqual(result, { ok: false, reason: 'child-exited' });
  assert.ok(probes >= 1, 'the wait must have actually probed before aborting');
});

itAsync('waitForFirstBuild reports ready off a REAL http server once a delayed producer writes the entrypoint (rf2-qwy3)', async () => {
  // End-to-end over the real HTTP path serve-example uses, with a fake
  // "watch child" (a timer) writing main.js into an initially clean staged
  // root — no shadow-cljs required. Proves the runner stays silent while the
  // directory holds only the host page, and flips once the bundle lands.
  const http = require('http');
  const { fetchToken: fetchServedBody } = require('./lib/local-browser-harness.cjs');

  const tmpRoot = fs.mkdtempSync(path.join(os.tmpdir(), 'rf2-firstbuild-'));
  // The freshly cleaned staged root: host page + assets present, NO main.js.
  fs.writeFileSync(path.join(tmpRoot, 'index.html'), '<!doctype html><script src="main.js"></script>');

  const server = http.createServer((req, res) => {
    const rel = decodeURIComponent(req.url.split('?')[0]).replace(/^\/+/, '');
    const target = path.join(tmpRoot, rel);
    if (!fs.existsSync(target) || !fs.statSync(target).isFile()) {
      res.statusCode = 404;
      res.end('not found');
      return;
    }
    res.statusCode = 200;
    res.end(fs.readFileSync(target));
  });
  try {
    await new Promise((resolve) => server.listen(0, '127.0.0.1', resolve));
    const port = server.address().port;

    // Before any build: the entrypoint is genuinely unserved — the window the
    // old code advertised as live.
    assert.strictEqual(
      await fetchServedBody(port, { host: '127.0.0.1', path: `/${BUILD_ENTRYPOINT}` }),
      null,
      'the cleaned staged root must not serve an entrypoint before the first compile',
    );

    // The fake watch child publishes the bundle a few polls in.
    const timer = setTimeout(() => {
      fs.writeFileSync(path.join(tmpRoot, BUILD_ENTRYPOINT), 'console.log("booted");');
    }, 120);
    timer.unref?.();

    const result = await waitForFirstBuild({
      fetchBody: () => fetchServedBody(port, { host: '127.0.0.1', path: `/${BUILD_ENTRYPOINT}` }),
      isAborted: () => false,
      sleep: (ms) => new Promise((r) => setTimeout(r, ms)),
      pollMs: 25,
    });
    clearTimeout(timer);
    assert.deepStrictEqual(result, { ok: true });
    assert.ok(
      fs.existsSync(path.join(tmpRoot, BUILD_ENTRYPOINT)),
      'readiness must not be reported before the producer wrote the entrypoint',
    );
  } finally {
    await new Promise((resolve) => server.close(resolve));
    fs.rmSync(tmpRoot, { recursive: true, force: true });
  }
});

it('TEETH: serve-example awaits the first build BEFORE printing the live URL (rf2-qwy3)', () => {
  // The call-site pin. On the pre-fix ordering there is no wait at all between
  // startLocalHttpServer's ready result and the `is live` banner, so this test
  // fails against it — which is the whole point.
  const waitAt = SERVE_EXAMPLE_SRC.indexOf('await waitForFirstBuild(');
  const liveAt = SERVE_EXAMPLE_SRC.indexOf('is live at http://127.0.0.1:');
  assert.ok(waitAt !== -1, 'serve-example must await the first-build readiness wait');
  assert.ok(liveAt !== -1, 'serve-example must still print the live URL');
  assert.ok(
    waitAt < liveAt,
    'the first-build wait must precede the live/openable banner, or the runner ' +
      'advertises a page whose main.js is still absent',
  );
});

it('serve-example keeps the wait on the WATCH path only, and tears down on first-build failure (rf2-qwy3)', () => {
  // --no-watch already compiles synchronously before the server starts, so its
  // output is complete on the first byte served; it must not grow a wait.
  assert.ok(
    /if\s*\(watch\)\s*\{[\s\S]{0,1200}?await waitForFirstBuild\(/.test(SERVE_EXAMPLE_SRC),
    'the first-build wait must be guarded by the watch branch (--no-watch stays synchronous)',
  );
  // A watch that dies before first output must not leave the server up.
  const failBlock = SERVE_EXAMPLE_SRC.slice(SERVE_EXAMPLE_SRC.indexOf('if (!first.ok)'));
  assert.ok(
    /cleanup\.cleanup\(\)/.test(failBlock.slice(0, 600)),
    'a first-build failure must tear the server down',
  );
  assert.ok(
    /return 1;/.test(failBlock.slice(0, 600)),
    'a first-build failure must exit non-zero',
  );
});

// Drain the queued async cases, then own the summary + exit code for the whole
// file (both the synchronous `it` results already counted in `failed` and these).
(async () => {
  for (const [label, f] of asyncTests) {
    try {
      await f();
      console.log(`  PASS  ${label}`);
    } catch (err) {
      failed++;
      console.error(`  FAIL  ${label}`);
      console.error(`        ${err.message || err}`);
    }
  }
  if (failed > 0) {
    console.error(`\nexamples-staging tests: ${failed} FAILED.`);
    process.exit(1);
  }
  console.log('\nexamples-staging tests: all passed.');
})();
