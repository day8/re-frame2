#!/usr/bin/env node
/*
 * Tests for `examples/scripts/examples-staging.cjs` (the shared staging helpers
 * and the standalone-example manifest derived from shadow-cljs.edn) and the pure
 * helpers of `examples/scripts/serve-example.cjs` (the `npm run dev:example`
 * runner). Standalone node-runnable suite, discovered by `npm run test:scripts`.
 */

'use strict';

const fs = require('fs');
const http = require('http');
const os = require('os');
const path = require('path');
const assert = require('assert');

const {
  parseExampleBuilds,
  buildNsIndex,
  listStandaloneExamples,
  cleanStageDirs,
  stageExample,
  stagePerExampleAssets,
  EXAMPLES_ROOT,
} = require('../../examples/scripts/examples-staging.cjs');

const { stagedAssetsByBuild } = require('../../examples/scripts/examples-asset-manifest.cjs');

const {
  decideRunnerExit,
  isDocumentNavigationRequest,
  startDocumentFallbackServer,
  waitForFirstBuild,
  watchExitAbortsRun,
} = require('../../examples/scripts/serve-example.cjs');

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

// `it` does not await, so an async body's rejection would escape its try/catch
// and print PASS. Async cases queue here and are drained by the runner at the
// bottom of the file.
const asyncTests = [];
function itAsync(label, f) {
  asyncTests.push([label, f]);
}

const HTML_ACCEPT = 'text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8';

// One plain loopback HTTP request; resolves { status, body } (status 0 on a transport error).
function httpRequest({ port, method = 'GET', path: reqPath, accept }) {
  return new Promise((resolve) => {
    const req = http.request(
      {
        hostname: '127.0.0.1',
        port,
        method,
        path: reqPath,
        agent: false,
        headers: accept ? { Accept: accept } : {},
      },
      (res) => {
        let body = '';
        res.setEncoding('utf8');
        res.on('data', (c) => { body += c; });
        res.on('end', () => resolve({ status: res.statusCode, body }));
      },
    );
    req.on('error', (err) => resolve({ status: 0, body: String(err) }));
    req.setTimeout(10000, () => { req.destroy(new Error('request timeout')); });
    req.end();
  });
}

console.log('examples-staging tests');

// ---- parser ----------------------------------------------------------------

// The build-id key on its own line with `{` on the next is the shape
// shadow-cljs.edn uses, and the one a single non-greedy regex with a lookahead
// skips every other entry of; the inline-brace and :node-library variants must
// be recovered too, and the commented-out key must not.
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

it('parseExampleBuilds recovers every adjacent example build with its :output-dir, :init-fn and :target', () => {
  assert.deepStrictEqual(parseExampleBuilds(FIXTURE), [
    { build: 'examples/alpha', target: ':browser', outputDir: 'out/examples/alpha', initFn: 'alpha.core/run' },
    { build: 'examples/beta', target: ':browser', outputDir: 'out/examples/beta', initFn: 'beta.views/run' },
    {
      build: 'examples/gamma',
      target: ':browser',
      outputDir: 'out/examples/gamma',
      initFn: 'seven-guis.gamma.core/run',
    },
    {
      build: 'examples/delta-server',
      target: ':node-library',
      outputDir: 'out/examples/delta-server',
      initFn: null,
    },
  ]);
});

// ---- FAIL-CLOSED ns-index enumeration --------------------------------------
//
// An unreadable examples/ subtree or source head must throw, naming the path,
// rather than hide the ns and let the dev runner call the build "not runnable".

// An io over the real fs that fails with EACCES for ONE directory or ONE file.
function failingFsIo({ badDir = null, badFile = null } = {}) {
  const eacces = (msg) => Object.assign(new Error(msg), { code: 'EACCES' });
  return {
    readdirSync: (dir, opts) => {
      if (badDir && path.resolve(dir) === path.resolve(badDir)) {
        throw eacces(`EACCES: permission denied, scandir '${dir}'`);
      }
      return fs.readdirSync(dir, opts);
    },
    readFileSync: (p, enc) => {
      if (badFile && path.resolve(p) === path.resolve(badFile)) {
        throw eacces(`EACCES: permission denied, open '${p}'`);
      }
      return fs.readFileSync(p, enc);
    },
  };
}

it('TEETH: buildNsIndex FAILS CLOSED on an unreadable subtree (rf2-3fc89f.31)', () => {
  const badDir = path.join(EXAMPLES_ROOT, 'core');
  assert.throws(
    () => buildNsIndex(EXAMPLES_ROOT, { io: failingFsIo({ badDir }) }),
    (err) => err.message.includes(badDir),
  );
});

it('TEETH: buildNsIndex FAILS CLOSED on an unreadable source file head (rf2-3fc89f.31)', () => {
  const someSourceDir = [...buildNsIndex(EXAMPLES_ROOT).values()][0];
  const someSource = fs
    .readdirSync(someSourceDir)
    .map((n) => path.join(someSourceDir, n))
    .find((p) => /\.clj[sc]$/.test(p));
  assert.throws(
    () => buildNsIndex(EXAMPLES_ROOT, { io: failingFsIo({ badFile: someSource }) }),
    (err) => err.message.includes(someSource),
  );
});

// ---- the documented run recipes resolve to a real host page ----------------
//
// The READMEs tell the reader to run `npm run dev:example -- <build-id>`, which
// reaches a page only if the build is in listStandaloneExamples() with an
// index.html on disk. Keep this roster in step with the READMEs.
const DOCUMENTED_BUILDS = [
  // core (the 7GUIs cluster is the build-id table in seven_guis/README.md)
  'examples/counter',
  'examples/login',
  'examples/todomvc',
  'examples/flows',
  'examples/managed-http-counter',
  'examples/notebook',
  'examples/temperature',
  'examples/flight-booker',
  'examples/timer',
  'examples/crud',
  'examples/circle-drawer',
  'examples/cells',
  // capability
  'examples/state-machine-walkthrough',
  'examples/routing',
  'examples/resources',
  'examples/infinite-feed',
  'examples/linearlite',
  'examples/ssr',
  'examples/resources-ssr',
  'examples/ssr-streaming',
  // substrate: the three view layers examples/substrates/README.md compares
  'examples/counter-uix',
  'examples/login-uix',
  'examples/dashboard-uix',
  'examples/login-fresco',
];

it('listStandaloneExamples resolves every documented build to a colocated index.html under out/examples', () => {
  const byId = Object.fromEntries(listStandaloneExamples().map((e) => [e.build, e]));
  const broken = DOCUMENTED_BUILDS.filter((build) => {
    const e = byId[build];
    return !e || !fs.existsSync(e.htmlSrc) || !/out[\\/]examples[\\/]/.test(e.outDir);
  });
  assert.deepStrictEqual(broken, [], 'documented as `npm run dev:example -- <build>` but not runnable');
});

// ---- clean-stage boundary --------------------------------------------------
//
// Overlaying staged files onto the shared out/examples root would leave a file a
// previous run staged serveable (a stale-file false green), so cleanStageDirs
// removes and recreates only the selected dirs, path-guarded under the root.

it('cleanStageDirs REFUSES the shared root itself and an out-of-tree target (path guard)', () => {
  const root = path.join('/tmp', 'out', 'examples');
  for (const target of [root, path.join('/tmp', 'elsewhere')]) {
    // An empty io: reaching any fs call would throw a TypeError the regex rejects.
    assert.throws(
      () => cleanStageDirs([target], root, { io: {} }),
      /not strictly under the owned staging root/,
      target,
    );
  }
});

it('dev-runner clean-then-stage removes a stale main.js and re-stages the example (rf2-rg2tze)', () => {
  const tmpRoot = fs.mkdtempSync(path.join(os.tmpdir(), 'rf2-serve-'));
  try {
    const outRoot = path.join(tmpRoot, 'out', 'examples');
    const sel = path.join(outRoot, 'counter-uix');
    const sibling = path.join(outRoot, 'login-uix');
    fs.mkdirSync(sel, { recursive: true });
    fs.mkdirSync(sibling, { recursive: true });
    fs.writeFileSync(path.join(sel, 'main.js'), 'STALE_BUNDLE');
    fs.writeFileSync(path.join(sibling, 'main.js'), 'SIBLING_BUNDLE');
    const htmlSrc = path.join(tmpRoot, 'index.html');
    fs.writeFileSync(htmlSrc, '<!doctype html><title>counter-uix</title>');

    // The sequence serve-example.cjs performs.
    cleanStageDirs([sel], outRoot);
    stageExample({ build: 'examples/counter-uix', outDir: sel, htmlSrc, srcDir: tmpRoot });

    assert.deepStrictEqual(fs.readdirSync(sel).sort(), ['_shared', 'index.html']);
    assert.strictEqual(fs.readFileSync(path.join(sibling, 'main.js'), 'utf8'), 'SIBLING_BUNDLE');
  } finally {
    fs.rmSync(tmpRoot, { recursive: true, force: true });
  }
});

// ---- serve-example dev-runner exit code ------------------------------------

it('decideRunnerExit: 0 for a clean or user-interrupted shutdown, 1 for any unexpected child crash', () => {
  for (const [outcomes, expected] of [
    [{}, 0],
    [{ server: { code: 0, signal: null } }, 0],
    [
      {
        server: { code: null, signal: 'SIGTERM' },
        watch: { code: null, signal: 'SIGTERM' },
        interrupted: true,
      },
      0,
    ],
    [{ server: { code: 0, signal: null }, watch: { code: 1, signal: null }, interrupted: false }, 1],
    [{ server: { code: 1, signal: null }, watch: { code: 0, signal: null }, interrupted: false }, 1],
    // A non-teardown signal is a crash even while the user is interrupting.
    [{ watch: { code: null, signal: 'SIGSEGV' }, interrupted: true }, 1],
  ]) {
    assert.strictEqual(decideRunnerExit(outcomes), expected, JSON.stringify(outcomes));
  }
});

// ---- per-example static assets ---------------------------------------------

it('stagedAssetsByBuild projects a synthetic manifest to build -> [{from,src,dest}], dropping htmlLinked (rf2-phpbo8)', () => {
  assert.deepStrictEqual(
    stagedAssetsByBuild([
      {
        build: 'examples/linked',
        assets: [{ from: 'node-modules', src: 'pkg/base.css', dest: 'base.css', htmlLinked: true }],
      },
      {
        build: 'examples/fetched',
        assets: [{ from: 'src', src: 'api/data.json', dest: 'api/data.json', htmlLinked: false }],
      },
    ]),
    {
      'examples/linked': [{ from: 'node-modules', src: 'pkg/base.css', dest: 'base.css' }],
      'examples/fetched': [{ from: 'src', src: 'api/data.json', dest: 'api/data.json' }],
    },
  );
});

it('stageExample stages a colocated per-example asset after a clean stage (rf2-cq6va5)', () => {
  // The real manifest's managed-http-counter entry against the real repo source.
  const srcDir = path.join(EXAMPLES_ROOT, 'core', 'managed_http_counter');
  const tmpRoot = fs.mkdtempSync(path.join(os.tmpdir(), 'rf2-asset-'));
  try {
    const outRoot = path.join(tmpRoot, 'out', 'examples');
    const outDir = path.join(outRoot, 'managed-http-counter');
    cleanStageDirs([outDir], outRoot);
    stageExample({
      build: 'examples/managed-http-counter',
      outDir,
      htmlSrc: path.join(srcDir, 'index.html'),
      srcDir,
    });
    assert.strictEqual(
      fs.readFileSync(path.join(outDir, 'api', 'inc.json'), 'utf8'),
      fs.readFileSync(path.join(srcDir, 'api', 'inc.json'), 'utf8'),
    );
  } finally {
    fs.rmSync(tmpRoot, { recursive: true, force: true });
  }
});

it('stagePerExampleAssets FAILS LOUD on a missing declared asset source (rf2-cq6va5)', () => {
  const tmpRoot = fs.mkdtempSync(path.join(os.tmpdir(), 'rf2-asset-miss-'));
  try {
    const emptySrc = path.join(tmpRoot, 'src');
    const outDir = path.join(tmpRoot, 'out');
    fs.mkdirSync(emptySrc, { recursive: true });
    fs.mkdirSync(outDir, { recursive: true });
    // The real manifest declares default-avatar.svg as a :src asset of this build.
    assert.throws(() => stagePerExampleAssets({ build: 'examples/realworld', outDir, srcDir: emptySrc }));
  } finally {
    fs.rmSync(tmpRoot, { recursive: true, force: true });
  }
});

// ---- serve-example first-build readiness and watch termination -------------
//
// In watch mode the staged root has just been cleaned and `shadow-cljs watch`
// is asynchronous, so the live URL is printed only once the bundle is served,
// and a watcher that exits before then (a clean code 0 included) ends the run.

it('watchExitAbortsRun: after the first build only an unexpected exit is terminal, and an interrupt never is', () => {
  for (const [outcome, expected] of [
    [{ code: 0, signal: null, firstBuildReady: true }, false],
    [{ code: 2, firstBuildReady: true }, true],
    [{ code: null, signal: 'SIGKILL', firstBuildReady: true }, true],
    [{ code: null, signal: 'SIGINT', interrupted: true, firstBuildReady: false }, false],
  ]) {
    assert.strictEqual(watchExitAbortsRun(outcome), expected, JSON.stringify(outcome));
  }
});

itAsync('TEETH: waitForFirstBuild keeps polling through a missing or zero-length entrypoint, then reports ready', async () => {
  const bodies = [null, '', 'console.log("booted");'];
  let probes = 0;
  const result = await waitForFirstBuild({
    fetchBody: async () => bodies[Math.min(probes++, bodies.length - 1)],
    isAborted: () => false,
    sleep: async () => {},
  });
  assert.deepStrictEqual({ result, probes }, { result: { ok: true }, probes: 3 });
});

itAsync('TEETH: a fake watcher that exits 0 before publishing aborts the wait rather than hanging (rf2-qwy3)', async () => {
  let probes = 0;
  let watchDied = false;
  const result = await waitForFirstBuild({
    fetchBody: async () => {
      probes++;
      if (probes === 2) {
        watchDied = watchExitAbortsRun({ code: 0, signal: null, interrupted: false, firstBuildReady: false });
      }
      // Bounded, so a misclassified exit fails here instead of hanging the suite.
      if (probes > 50) throw new Error('still polling after the watcher exited: the runner would hang');
      return null;
    },
    isAborted: () => watchDied,
    sleep: async () => {},
  });
  assert.deepStrictEqual(result, { ok: false, reason: 'child-exited' });
});

// ---- history-route document fallback --------------------------------------
//
// An HTML document navigation to a path no file answers gets the staged host
// page, so a history-routed example is reloadable; everything else stays a 404,
// or waitForFirstBuild would accept a host page in place of a missing main.js.

it('TEETH: isDocumentNavigationRequest admits only a GET/HEAD HTML navigation to an extensionless or .html path', () => {
  for (const [req, expected] of [
    [{ method: 'GET', url: '/articles/intro', accept: HTML_ACCEPT }, true],
    [{ method: 'HEAD', url: '/articles/intro', accept: HTML_ACCEPT }, true],
    [{ method: 'GET', url: '/index.html', accept: HTML_ACCEPT }, true],
    [{ method: 'GET', url: '/main.js', accept: HTML_ACCEPT }, false],
    [{ method: 'POST', url: '/articles', accept: HTML_ACCEPT }, false],
  ]) {
    assert.strictEqual(isDocumentNavigationRequest(req), expected, JSON.stringify(req));
  }
});

itAsync('the document fallback serves the host page for a history route and 404s every asset (rf2-fzbj.35)', async () => {
  // Node builtins only, so it runs in every lane, including the one with no npm ci.
  const HOST_HTML = '<!doctype html><title>RF2-FALLBACK-HOST-DOCUMENT</title>';
  const root = fs.mkdtempSync(path.join(os.tmpdir(), 'rf2-docfallback-'));
  fs.writeFileSync(path.join(root, 'index.html'), HOST_HTML);
  const fallback = await startDocumentFallbackServer({ indexPath: path.join(root, 'index.html') });
  const { port } = new URL(fallback.url);
  try {
    assert.deepStrictEqual(
      await httpRequest({ port, path: '/articles/intro', accept: HTML_ACCEPT }),
      { status: 200, body: HOST_HTML },
    );
    assert.deepStrictEqual(
      await httpRequest({ port, method: 'HEAD', path: '/articles/intro', accept: HTML_ACCEPT }),
      { status: 200, body: '' },
    );
    // The runner's own first-build probe sends no Accept header.
    assert.strictEqual((await httpRequest({ port, path: '/main.js' })).status, 404);
    // No host page staged means nothing to fall back to: a 404, not a 500.
    fs.rmSync(path.join(root, 'index.html'));
    assert.strictEqual((await httpRequest({ port, path: '/articles/intro', accept: HTML_ACCEPT })).status, 404);
  } finally {
    await fallback.close();
    fs.rmSync(root, { recursive: true, force: true, maxRetries: 3, retryDelay: 100 });
  }
});

// The end-to-end row needs the http-server package, which the CI lane running
// this file does not install; there it is reported as a SKIP, not a vacuous PASS.
let HTTP_SERVER_BIN = null;
try {
  HTTP_SERVER_BIN = require.resolve('http-server/bin/http-server', {
    paths: [path.join(__dirname, '..')],
  });
} catch {
  HTTP_SERVER_BIN = null;
}
const itAsyncWithHttpServer = HTTP_SERVER_BIN
  ? itAsync
  : (label) =>
      console.log(
        `  SKIP  ${label}\n        http-server is not installed in this lane (no npm ci); ` +
          'the fallback responder row above still ran.',
      );

itAsyncWithHttpServer('a history route is RELOADABLE through the real runner server, and assets still 404 (rf2-fzbj.35)', async () => {
  // The path serve-example takes: the real http-server bin under the shared
  // startLocalHttpServer, forwarding unresolved requests to the fallback.
  const {
    createHarnessCleanup,
    findFreePort,
    startLocalHttpServer,
  } = require('./lib/local-browser-harness.cjs');

  const HOST_HTML = '<!doctype html><title>RF2-STAGED-HOST-DOCUMENT</title><script src="main.js"></script>';
  const BUNDLE = 'console.log("booted");';
  const root = fs.mkdtempSync(path.join(os.tmpdir(), 'rf2-histroute-'));
  fs.writeFileSync(path.join(root, 'index.html'), HOST_HTML);
  fs.writeFileSync(path.join(root, 'main.js'), BUNDLE);

  const fallback = await startDocumentFallbackServer({ indexPath: path.join(root, 'index.html') });
  const cleanup = createHarnessCleanup({ onError: () => {} });
  try {
    const port = await findFreePort();
    await startLocalHttpServer({
      cleanup,
      httpServerBin: HTTP_SERVER_BIN,
      root,
      port,
      cwd: path.join(__dirname, '..'),
      readyTimeoutMs: 30000,
      log: () => {},
      unresolvedRequestUrl: fallback.url,
    });
    assert.deepStrictEqual(
      await httpRequest({ port, path: '/articles/intro', accept: HTML_ACCEPT }),
      { status: 200, body: HOST_HTML },
    );
    assert.deepStrictEqual(
      await httpRequest({ port, path: '/main.js', accept: '*/*' }),
      { status: 200, body: BUNDLE },
    );
    assert.notStrictEqual((await httpRequest({ port, path: '/not-built.js' })).status, 200);
  } finally {
    await cleanup.cleanup().catch(() => {});
    await fallback.close();
    fs.rmSync(root, { recursive: true, force: true, maxRetries: 3, retryDelay: 100 });
  }
});

// ---- serve-example main() wiring --------------------------------------------
//
// main() needs shadow-cljs to run, so the order it composes the helpers above
// in is pinned on its source.

it('TEETH: serve-example main() cleans before staging, withholds the live URL until the first build, and wires the document fallback', () => {
  const src = fs.readFileSync(
    path.join(__dirname, '..', '..', 'examples', 'scripts', 'serve-example.cjs'),
    'utf8',
  );
  const before = (a, b) => src.indexOf(a) !== -1 && src.indexOf(a) < src.indexOf(b);
  assert.ok(
    before('cleanStageDirs([entry.outDir], OUT_ROOT)', 'stageExample(entry)'),
    'the clean must precede the stage so no stale file survives into the served dir',
  );
  assert.ok(
    before('await waitForFirstBuild(', 'is live at http://127.0.0.1:'),
    'the first-build wait must precede the live banner',
  );
  assert.match(
    src,
    /if \(!first\.ok\) \{[\s\S]{0,600}?return 1;/,
    'a first build that never lands must exit non-zero',
  );
  assert.match(
    src,
    /watchProc\.on\('exit',[\s\S]{0,400}?watchExitAbortsRun\(\{[^}]*firstBuildReady[^}]*\}\)/,
    "the watch 'exit' handler must classify via watchExitAbortsRun, passing the readiness phase",
  );
  assert.ok(
    before('await waitForFirstBuild(', 'firstBuildReady = true;'),
    'first-build readiness must be recorded only after the wait that proves it',
  );
  assert.match(
    src,
    /unresolvedRequestUrl:\s*fallback\.url/,
    'serve-example must hand the document fallback to its server, or a history route 404s on refresh',
  );
});

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
