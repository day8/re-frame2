#!/usr/bin/env node
'use strict';

// Real Shadow 3.4.10 acceptance proof for Spec 004C §2.1. One warm watch
// daemon and one live browser cross good -> downstream-failed -> good passes.
// The edited view lives only in an unexecuted lazy module; the base runtime's
// O(1) carrier still moves, proving whole-build rather than loaded-module
// identity. The intentional :flush-hook failure is after compile-finish and
// target output, so the still-active browser is the authoritative LKG witness.

const fs = require('fs');
const path = require('path');
const { chromium } = require('playwright');
const {
  createHarnessCleanup,
  resolveServePort,
  spawnHarnessProcess,
  startLocalHttpServer,
  terminateProcessTree,
} = require('./lib/local-browser-harness.cjs');

const IMPL = path.join(__dirname, '..');
const LAZY_SOURCE = path.join(
  IMPL, 'ui', 'test', 're_frame', 'ui', 'digest_probe', 'lazy.cljs',
);
const LOADED_SOURCE = path.join(
  IMPL, 'ui', 'test', 're_frame', 'ui', 'digest_probe', 'loaded.cljs',
);
const OUT = path.join(IMPL, 'out', 'ui-digest-probe-lazy');
// rf2-vxgfnd.237 — artifact generation/activation separation. Shadow flushes
// the CANDIDATE generation to OUT/candidate (its :output-dir); the browser is
// served the STABLE generation from OUT/stable (the dev-http :http-root). The
// re-frame.ui promote hook publishes candidate -> stable only on a fully
// successful flush, so a downstream failure leaves the served bytes on the
// prior accepted generation.
const CANDIDATE = path.join(OUT, 'candidate');
const STABLE = path.join(OUT, 'stable');
// The SERVED (stable) module bytes — what a fresh page load / first lazy-module
// request actually receives.
const LAZY_JS = path.join(STABLE, 'lazy.js');
const CARRIER_JS = path.join(
  STABLE, 'cljs-runtime', 're_frame.ui.digest_carrier.js',
);
// The CANDIDATE module bytes Shadow speculatively flushed — after a downstream
// failure these hold the REJECTED generation, which the separation must keep
// off the served URLs.
const CANDIDATE_LAZY_JS = path.join(CANDIDATE, 'lazy.js');
const CANDIDATE_CARRIER_JS = path.join(
  CANDIDATE, 'cljs-runtime', 're_frame.ui.digest_carrier.js',
);
const TARGET = path.join(IMPL, 'target', 'ui-digest-probe');
const FAIL_MARKER = path.join(TARGET, 'fail-after-compile');
const ACCEPTED_FILE = path.join(TARGET, 'accepted-digest.txt');
const PREPARE_FILE = path.join(TARGET, 'prepare-snapshots.tsv');
const HOOKLESS_CONFIG_ROOT = path.join(
  IMPL, 'ui', 'test', 're_frame', 'ui', 'digest_probe', 'hookless',
);
const HOOKLESS_OUT = path.join(IMPL, 'out', 'ui-digest-probe-hookless');
const HOOKLESS_CARRIER = path.join(
  HOOKLESS_OUT, 'cljs-runtime', 're_frame.ui.digest_carrier.js',
);
const DIGEST_SENTINEL = '__RF2_UI_DIGEST_XX__';
const URL = 'http://127.0.0.1:8059/index.html';
const TIMEOUT = 90000;
const HOOKLESS_ERROR_TIMEOUT = 15000;

function fail(message) {
  throw new Error(`FAIL: ${message}`);
}

function replaceExactly(source, from, to) {
  const first = source.indexOf(from);
  if (first < 0 || source.indexOf(from, first + from.length) >= 0) {
    fail(`expected exactly one ${JSON.stringify(from)} marker in lazy fixture`);
  }
  return source.slice(0, first) + to + source.slice(first + from.length);
}

function watchShadow(shadowRunner) {
  // Established harness primitive: a POSIX process-group leader (or Windows
  // taskkill tree) so the Java daemon cannot outlive the test runner.
  const child = spawnHarnessProcess(process.execPath, [
    shadowRunner, 'watch', 'ui-digest-probe-lazy',
  ], {
    cwd: IMPL,
    env: process.env,
    stdio: ['ignore', 'pipe', 'pipe'],
  });

  let successes = 0;
  let failures = 0;
  let transcript = '';
  const lineBuffers = { stdout: '', stderr: '' };
  const waiters = [];

  function settleWaiters() {
    for (let i = waiters.length - 1; i >= 0; i -= 1) {
      const w = waiters[i];
      const count = w.kind === 'success' ? successes : failures;
      if (count > w.after) {
        clearTimeout(w.timer);
        waiters.splice(i, 1);
        w.resolve(count);
      }
    }
  }

  function ingest(chunk, stream) {
    const text = chunk.toString();
    transcript += text;
    process.stdout.write(text);
    const lines = (lineBuffers[stream] + text).split(/\r?\n/);
    lineBuffers[stream] = lines.pop();
    for (const line of lines) {
      if (line.includes('[:ui-digest-probe-lazy] Build completed.')) successes += 1;
      if (line.includes('[:ui-digest-probe-lazy] Build failure:')) failures += 1;
    }
    settleWaiters();
  }

  child.stdout.on('data', (chunk) => ingest(chunk, 'stdout'));
  child.stderr.on('data', (chunk) => ingest(chunk, 'stderr'));
  child.on('exit', (code, signal) => {
    if (waiters.length === 0) return;
    const error = new Error(
      `shadow watch exited before proof completed (code=${code}, signal=${signal})\n${transcript}`,
    );
    while (waiters.length) {
      const w = waiters.pop();
      clearTimeout(w.timer);
      w.reject(error);
    }
  });

  function wait(kind, after) {
    return new Promise((resolve, reject) => {
      const timer = setTimeout(() => {
        const idx = waiters.findIndex((w) => w.timer === timer);
        if (idx >= 0) waiters.splice(idx, 1);
        reject(new Error(
          `timed out waiting for Shadow ${kind} after count ${after}\n${transcript}`,
        ));
      }, TIMEOUT);
      waiters.push({ kind, after, resolve, reject, timer });
      settleWaiters();
    });
  }

  return {
    child,
    waitSuccess: (after) => wait('success', after),
    waitFailure: (after) => wait('failure', after),
    successCount: () => successes,
    failureCount: () => failures,
  };
}

function compileHooklessShadow(shadowRunner) {
  const child = spawnHarnessProcess(process.execPath, [
    shadowRunner, 'compile', 'ui-digest-probe-hookless',
  ], {
    cwd: HOOKLESS_CONFIG_ROOT,
    env: process.env,
    stdio: ['ignore', 'pipe', 'pipe'],
  });
  let transcript = '';
  const capture = (chunk) => {
    const text = chunk.toString();
    transcript += text;
    process.stdout.write(text);
  };
  child.stdout.on('data', capture);
  child.stderr.on('data', capture);

  const completed = new Promise((resolve, reject) => {
    const timer = setTimeout(() => {
      reject(new Error(`hookless Shadow compile timed out\n${transcript}`));
    }, TIMEOUT);
    child.once('error', (error) => {
      clearTimeout(timer);
      reject(error);
    });
    child.once('exit', (code, signal) => {
      clearTimeout(timer);
      if (code === 0) resolve(transcript);
      else reject(new Error(
        `hookless Shadow compile failed (code=${code}, signal=${signal})\n${transcript}`,
      ));
    });
  });
  return { child, completed };
}

async function readBrowserDigest(page) {
  await page.waitForFunction(
    () => typeof globalThis.__rf2ReadDigest === 'function' &&
          typeof globalThis.__rf2ReadDigest() === 'string' &&
          globalThis.__rf2ReadDigest().startsWith('bd1-'),
    null,
    { timeout: TIMEOUT },
  );
  return page.evaluate(() => globalThis.__rf2ReadDigest());
}

async function waitForDigestChange(page, prior, timeout = TIMEOUT) {
  // Wait for a NEW fully-activated digest, not merely any change: a hot reload
  // fences reads fail-closed (null) between before-load and after-load, so the
  // stable post-promotion value is a bd1 digest that differs from `prior`.
  await page.waitForFunction(
    (oldDigest) => {
      if (typeof globalThis.__rf2ReadDigest !== 'function') return false;
      const d = globalThis.__rf2ReadDigest();
      return typeof d === 'string' && d.startsWith('bd1-') && d !== oldDigest;
    },
    prior,
    { timeout },
  );
  return page.evaluate(() => globalThis.__rf2ReadDigest());
}

async function readMountedProgram(page) {
  // The rendered program the mounted root currently shows — the DOM text of
  // loaded-view. nil until the first render commits or if no root is mounted.
  return page.evaluate(() => typeof globalThis.__rf2MountedProgram === 'function'
    ? globalThis.__rf2MountedProgram()
    : 'accessor-absent');
}

async function readMountedDescriptorDigest(page) {
  // The mounted root's COMPLETE Root Descriptor :build-digest (Spec 004C §2):
  // the read-time projection stamped from the carrier. nil while fenced.
  return page.evaluate(() => typeof globalThis.__rf2MountedDescriptorDigest === 'function'
    ? globalThis.__rf2MountedDescriptorDigest()
    : 'accessor-absent');
}

function acceptedSnapshot() {
  const value = fs.readFileSync(ACCEPTED_FILE, 'utf8').trim();
  const [versionText, digest] = value.split('\t');
  const version = Number(versionText);
  if (!Number.isSafeInteger(version) || !/^bd1-[0-9a-f]{16}$/.test(digest)) {
    fail(`test hook wrote invalid accepted snapshot ${JSON.stringify(value)}`);
  }
  return { version, digest };
}

function lastPrepareSnapshot() {
  const lines = fs.readFileSync(PREPARE_FILE, 'utf8').trim().split(/\r?\n/);
  const [versionText, digest] = lines.at(-1).split('\t');
  const version = Number(versionText);
  if (!Number.isSafeInteger(version) || !/^bd1-[0-9a-f]{16}$/.test(digest)) {
    fail(`test hook wrote invalid prepare snapshot ${JSON.stringify(lines.at(-1))}`);
  }
  return { version, digest };
}

async function proveHooklessBuildFailsClosed(shadowRunner, browser) {
  const config = fs.readFileSync(
    path.join(HOOKLESS_CONFIG_ROOT, 'shadow-cljs.edn'), 'utf8',
  );
  if (/:build-hooks\b/.test(config.replace(/;.*$/gm, ''))) {
    fail('hookless fixture unexpectedly configures a build hook');
  }

  fs.rmSync(HOOKLESS_OUT, { recursive: true, force: true });
  const cleanup = createHarnessCleanup();
  let tearingDown = false;
  let compile;
  let page;
  try {
    compile = compileHooklessShadow(shadowRunner);
    await compile.completed;

    const carrier = fs.readFileSync(HOOKLESS_CARRIER, 'utf8');
    if (!carrier.includes(DIGEST_SENTINEL) || /bd1-[0-9a-f]{16}/.test(carrier)) {
      fail('hookless output unexpectedly published a compiler digest');
    }

    fs.writeFileSync(
      path.join(HOOKLESS_OUT, 'index.html'),
      '<!doctype html><meta charset="utf-8"><script src="/base.js"></script>',
    );
    const httpServerBin = require.resolve('http-server/bin/http-server', {
      paths: [IMPL],
    });
    const port = await resolveServePort(8061);
    const served = await startLocalHttpServer({
      cleanup,
      httpServerBin,
      root: HOOKLESS_OUT,
      port,
      cwd: IMPL,
      captureOutput: true,
      readyTimeoutMs: 30000,
      suppressExitDiagnostic: () => tearingDown,
    });
    if (!served.ready) fail('hookless output server did not become ready');

    page = await browser.newPage();
    const rejected = page.waitForEvent('pageerror', {
      predicate: (error) => error.message.includes(
        're-frame.ui build digest was not finalized',
      ),
      timeout: HOOKLESS_ERROR_TIMEOUT,
    });
    await page.goto(`http://127.0.0.1:${port}/index.html`, {
      waitUntil: 'load', timeout: TIMEOUT,
    });
    const error = await rejected;
    if (error.name !== 'Error' ||
        !error.message.includes('Configure (re-frame.ui.compiler.build-hook/hook)')) {
      fail(`hookless runtime produced the wrong diagnostic: ${error.name}: ${error.message}`);
    }
    // Script-loader mode reports the top-level throw as a pageerror, then the
    // browser continues loading later independent scripts and installs the
    // digest + descriptor accessors. Namespace load is only an EARLY diagnostic;
    // the enforcement boundary is digest-carrier/current itself, so EVERY later
    // read must FAIL CLOSED on the unfinalized carrier — the raw sentinel is
    // never a readable identity (rf2-vxgfnd.205).
    const laterDigest = await page.evaluate(
      () => typeof globalThis.__rf2ReadDigest === 'function'
        ? globalThis.__rf2ReadDigest()
        : null,
    );
    if (laterDigest !== null) {
      fail(`hookless digest read did not fail closed (returned ${JSON.stringify(laterDigest)}); the sentinel must never be a readable identity`);
    }
    // Explicit negative assertion over descriptor PUBLICATION: a COMPLETE Root
    // Descriptor built now stamps :build-digest from current-build-digest, which
    // is fail-closed — so its :build-digest is null, never the raw sentinel. No
    // complete descriptor can carry an invalid build identity.
    const descriptorDigest = await page.evaluate(
      () => typeof globalThis.__rf2ReadDescriptorDigest === 'function'
        ? globalThis.__rf2ReadDescriptorDigest()
        : 'accessor-absent',
    );
    if (descriptorDigest !== null) {
      fail(`hookless complete descriptor published an unfinalized build identity: ${JSON.stringify(descriptorDigest)}`);
    }
    // The on-disk carrier still holds only the raw sentinel (asserted above): no
    // bd1 digest was ever projected, corroborating that nothing published. The
    // last-known-good of an ACCEPTED build lineage is proven CAUSALLY by the
    // same-watch-daemon downstream-failure section below; this SEPARATE hookless
    // config/process is deliberately NOT used as causal LKG proof — its
    // independent runtime cannot witness an accepted lineage losing its hook
    // (rf2-vxgfnd.205).
    console.log(
      'ui digest carrier: hookless build fails closed at every digest and ' +
      'descriptor read (no sentinel-stamped identity)',
    );
  } finally {
    if (page) await page.close();
    tearingDown = true;
    await cleanup.cleanup();
    if (compile) await terminateProcessTree(compile.child, { timeoutMs: 5000 });
    fs.rmSync(HOOKLESS_OUT, { recursive: true, force: true });
  }
}

async function main() {
  const original = fs.readFileSync(LAZY_SOURCE, 'utf8');
  const loadedOriginal = fs.readFileSync(LOADED_SOURCE, 'utf8');
  if (!original.includes('digest-probe-v1')) {
    fail('lazy fixture is not at its checked-in v1 marker');
  }
  fs.mkdirSync(TARGET, { recursive: true });
  fs.rmSync(FAIL_MARKER, { force: true });
  fs.rmSync(ACCEPTED_FILE, { force: true });
  fs.rmSync(PREPARE_FILE, { force: true });
  // Fresh candidate/stable slate so a prior run's promoted bytes or served
  // shell cannot mask a regression in the generation/activation separation.
  fs.rmSync(OUT, { recursive: true, force: true });

  let shadow;
  let browser;
  try {
    const shadowRunner = require.resolve('shadow-cljs/cli/runner.js', {
      paths: [IMPL],
    });
    shadow = watchShadow(shadowRunner);
    await shadow.waitSuccess(0);

    // The served shell lives in the STABLE directory (the dev-http :http-root),
    // which the first successful build's promote hook has just created. It is
    // not a build artifact, so the promote hook never overwrites or deletes it.
    fs.mkdirSync(STABLE, { recursive: true });
    fs.writeFileSync(
      path.join(STABLE, 'index.html'),
      '<!doctype html><meta charset="utf-8">' +
      // rf2-vxgfnd.242 — a mount container so base.cljs mounts a REAL root whose
      // rendered program (loaded-view text) and complete Root Descriptor are
      // observed across the runtime-activation fence.
      '<div id="rf2-mounted"></div>' +
      '<script src="/base.js"></script>',
    );

    browser = await chromium.launch({ headless: true });
    const page = await browser.newPage();
    const requests = [];
    page.on('request', (request) => requests.push(request.url()));
    await page.goto(URL, { waitUntil: 'load', timeout: TIMEOUT });
    await page.waitForFunction(
      () => typeof globalThis.__rf2HmrReady === 'function' &&
            globalThis.__rf2HmrReady() === true,
      null,
      { timeout: TIMEOUT },
    );

    const digest1 = await readBrowserDigest(page);
    const accepted1 = acceptedSnapshot();
    if (digest1 !== accepted1.digest) {
      fail('initial JVM accepted snapshot and client carrier differ');
    }
    if (await page.evaluate(() => globalThis.__rf2LazyExecuted === true)) {
      fail('lazy module executed during initial base load');
    }

    // rf2-vxgfnd.242 — the REAL mounted root renders the accepted v1 program and
    // its COMPLETE descriptor stamps the SAME accepted digest as the global
    // carrier. This is the mounted-root/descriptor pair the runtime-activation
    // counterexample then drives through the fence.
    await page.waitForFunction(
      () => typeof globalThis.__rf2MountedProgram === 'function' &&
            globalThis.__rf2MountedProgram() === 'loaded-probe-v1',
      null,
      { timeout: TIMEOUT },
    );
    await page.waitForFunction(
      (want) => typeof globalThis.__rf2MountedDescriptorDigest === 'function' &&
                globalThis.__rf2MountedDescriptorDigest() === want,
      digest1,
      { timeout: TIMEOUT },
    );

    // A separate, real Shadow configuration deliberately omits every build
    // hook while compiling the same client entry. Its top-level throw surfaces
    // as a pageerror, and every later digest/descriptor read must fail closed.
    await proveHooklessBuildFailsClosed(shadowRunner, browser);

    // Warm successful pass: only the unexecuted lazy view changes. The
    // ^:dev/always carrier must be regenerated from its sentinel and reloaded.
    const success1 = shadow.successCount();
    fs.writeFileSync(
      LAZY_SOURCE,
      replaceExactly(original, 'digest-probe-v1', 'digest-probe-v2'),
    );
    await shadow.waitSuccess(success1);
    const digest2 = await waitForDigestChange(page, digest1);
    const accepted2 = acceptedSnapshot();
    if (digest2 !== accepted2.digest) {
      fail('warm-pass JVM accepted snapshot and client carrier differ');
    }
    const warmPrepare = lastPrepareSnapshot();
    if (warmPrepare.version !== accepted1.version ||
        warmPrepare.digest !== accepted1.digest) {
      fail('warm pass did not prepare from the initial accepted snapshot');
    }
    if (await page.evaluate(() => globalThis.__rf2LazyExecuted === true)) {
      fail('lazy module executed after its warm watch recompile');
    }

    // Downstream failure: target output and digest compile-finish have already
    // run when the test :flush hook throws. The active runtime must retain d2.
    fs.writeFileSync(FAIL_MARKER, 'fail\n');
    const failure0 = shadow.failureCount();
    const v2 = fs.readFileSync(LAZY_SOURCE, 'utf8');
    fs.writeFileSync(
      LAZY_SOURCE,
      replaceExactly(v2, 'digest-probe-v2', 'digest-probe-v3'),
    );
    await shadow.waitFailure(failure0);
    const failedPrepare = lastPrepareSnapshot();
    if (failedPrepare.version !== accepted2.version ||
        failedPrepare.digest !== accepted2.digest) {
      fail('failed pass did not prepare from the warm accepted snapshot');
    }
    await page.waitForTimeout(500);
    const afterFailure = await page.evaluate(() => globalThis.__rf2ReadDigest());
    if (afterFailure !== digest2 || acceptedSnapshot().digest !== digest2) {
      fail('late failed pass changed active runtime or accepted JVM witness');
    }

    // Counterexample 1 — the downstream-OUTPUT invariant, CLOSED by the
    // generation/activation separation (rf2-vxgfnd.237). Shadow's browser
    // target flushed candidate d3 to its :output-dir (CANDIDATE) before the
    // intentional :flush failure, then rolled its functional build-state back
    // to accepted d2. The re-frame.ui promote hook — the LAST :flush hook —
    // never ran (the earlier failure aborted the build), so the SERVED stable
    // directory still holds accepted d2. First prove Shadow really did flush the
    // rejected candidate (else the invariant would be vacuous), then assert the
    // served generation stayed accepted. Asserted BY DEFAULT now the fix landed.
    const candidateLazy = fs.existsSync(CANDIDATE_LAZY_JS)
      ? fs.readFileSync(CANDIDATE_LAZY_JS, 'utf8') : '';
    if (!candidateLazy.includes('digest-probe-v3')) {
      fail('candidate output did not receive the rejected d3 generation; the ' +
           'downstream-output separation would be vacuously satisfied');
    }
    const servedLazy = fs.existsSync(LAZY_JS)
      ? fs.readFileSync(LAZY_JS, 'utf8') : '';
    const servedCarrier = fs.existsSync(CARRIER_JS)
      ? fs.readFileSync(CARRIER_JS, 'utf8') : '';
    const lazyServesRejected = servedLazy.includes('digest-probe-v3');
    // The accepted digest is d2; a served carrier that no longer contains d2
    // (its bytes moved to the rejected candidate d3) is servable-rejected.
    const carrierServesRejected =
      servedCarrier.length === 0 || !servedCarrier.includes(digest2);
    if (lazyServesRejected || carrierServesRejected) {
      fail(
        `downstream-output gap: served stable URLs no longer resolve to the ` +
        `accepted generation after the failed :flush (lazy-serves-v3=` +
        `${lazyServesRejected}, carrier-not-d2=${carrierServesRejected}); ` +
        `accepted authority is d2 (${digest2}). A hard reload / first lazy ` +
        `import would activate the rejected candidate d3.`);
    }
    console.log(
      'ui digest carrier: served (stable) generation stayed on accepted d2 ' +
      'while the candidate output-dir held the rejected d3',
    );

    // rf2-vxgfnd.237 — the post-:flush-failure LAZY-MODULE-REQUEST fixture the
    // existing fixtures deliberately do NOT exercise (they assert the ACTIVE
    // runtime never lazy-loads). A brand-new page (a hard reload) and a genuine
    // first `/lazy.js` request, both against the served origin while the failed
    // d3 candidate is the latest build, must receive the prior accepted d2 — or
    // a fail-closed response — never the rejected candidate.
    const freshPage = await browser.newPage();
    try {
      await freshPage.goto(URL, { waitUntil: 'load', timeout: TIMEOUT });
      // Hard reload: the served base carrier must read the accepted d2 (or, if
      // ever fail-closed, null) — never the rejected candidate d3.
      const freshDigest = await freshPage.evaluate(async () => {
        for (let i = 0; i < 200; i += 1) {
          if (typeof globalThis.__rf2ReadDigest === 'function') {
            const d = globalThis.__rf2ReadDigest();
            if (d === null || (typeof d === 'string' && d.startsWith('bd1-'))) return d;
          }
          await new Promise((r) => setTimeout(r, 50));
        }
        return typeof globalThis.__rf2ReadDigest === 'function'
          ? globalThis.__rf2ReadDigest() : 'accessor-absent';
      });
      if (freshDigest !== digest2 && freshDigest !== null) {
        fail(`hard reload after the failed :flush advertised ${JSON.stringify(freshDigest)}; ` +
             `expected the accepted d2 (${digest2}) or a fail-closed null`);
      }
      // First lazy import: a genuine request for the served /lazy.js from the
      // fresh origin must return the accepted d2 generation, never rejected d3.
      const lazyBody = await freshPage.evaluate(async () => {
        const res = await fetch('/lazy.js', { cache: 'no-store' });
        return { ok: res.ok, status: res.status, text: await res.text() };
      });
      if (!lazyBody.ok) {
        fail(`served first /lazy.js request failed (status ${lazyBody.status})`);
      }
      if (lazyBody.text.includes('digest-probe-v3')) {
        fail('first lazy-module request served the rejected candidate d3 (digest-probe-v3)');
      }
      if (!lazyBody.text.includes('digest-probe-v2')) {
        fail('first lazy-module request did not serve the accepted d2 generation (digest-probe-v2)');
      }
      console.log(
        `ui digest carrier: post-failure hard reload + first lazy import both ` +
        `resolved to the accepted generation ` +
        `(reload=${freshDigest === null ? 'fail-closed' : freshDigest}, lazy=d2)`,
      );
    } finally {
      await freshPage.close();
    }

    // Recovery success starts from Shadow's retained d2 state, not the failed
    // candidate or a macro ghost, then publishes one new byte-identical scalar.
    fs.rmSync(FAIL_MARKER, { force: true });
    const success2 = shadow.successCount();
    const v3 = fs.readFileSync(LAZY_SOURCE, 'utf8');
    fs.writeFileSync(
      LAZY_SOURCE,
      replaceExactly(v3, 'digest-probe-v3', 'digest-probe-v4'),
    );
    await shadow.waitSuccess(success2);
    const recoveryPrepare = lastPrepareSnapshot();
    if (recoveryPrepare.version !== accepted2.version ||
        recoveryPrepare.digest !== accepted2.digest) {
      fail('recovery observed the downstream-failed candidate as accepted state');
    }
    const digest3 = await waitForDigestChange(page, digest2);
    const accepted3 = acceptedSnapshot();
    if (digest3 === digest1 || digest3 !== accepted3.digest ||
        accepted3.version !== accepted2.version + 1) {
      fail('recovery pass did not publish its isolated accepted digest');
    }
    if (await page.evaluate(() => globalThis.__rf2LazyExecuted === true)) {
      fail('lazy module executed during recovery');
    }
    if (requests.some((url) => /(?:\/lazy\.js|digest_probe\.lazy)/.test(url))) {
      fail('browser fetched the lazy module during the proof');
    }

    // Counterexample 2 — the runtime-activation fence. A LOADED base-module
    // source (loaded.cljs) is edited compile-valid but to throw at top-level
    // BEFORE its view re-registers. The build compiles, Shadow hot-reloads, the
    // carrier evaluates first and STAGES the candidate, then the throwing source
    // stops the reload before after-load — so the candidate is never promoted.
    // Digest reads must be fail-closed (nil), never the candidate. (Removing the
    // before/after-load fence would publish the candidate on carrier evaluation,
    // failing the null assertion below.)
    const loadedV1 = fs.readFileSync(LOADED_SOURCE, 'utf8');
    if (!loadedV1.includes('loaded-probe-v1') || !loadedV1.includes('(when false')) {
      fail('loaded fixture is not at its checked-in inert v1 markers');
    }
    if (!(await page.evaluate(() => globalThis.__rf2LoadedExecuted === true))) {
      fail('loaded base-module source did not execute on the initial page load');
    }
    // Shadow catches a reloaded-source top-level throw inside its reload driver
    // (routing it to the load-failure path, which is exactly why after-load is
    // skipped), so it surfaces on the console rather than as an uncaught
    // pageerror. Capture both as informational witnesses; the hard signal is
    // the fail-closed read below.
    let sawThrow = null;
    const onPageError = (error) => {
      if (error.message.includes('counterexample-2 top-level throw')) sawThrow = error.message;
    };
    const onConsole = (msg) => {
      const text = msg.text();
      if (text.includes('counterexample-2 top-level throw') ||
          (text.includes('digest_probe/loaded') && /fail|error/i.test(text))) {
        sawThrow = sawThrow || text;
      }
    };
    page.on('pageerror', onPageError);
    page.on('console', onConsole);

    const successC2 = shadow.successCount();
    // The last accepted identity + program before the failed reload.
    const digestBeforeC2 = await page.evaluate(() => globalThis.__rf2ReadDigest());
    if (!/^bd1-[0-9a-f]{16}$/.test(digestBeforeC2)) {
      fail(`counterexample 2 did not start from an activated digest: ${digestBeforeC2}`);
    }
    if ((await readMountedProgram(page)) !== 'loaded-probe-v1') {
      fail('mounted root was not on the accepted v1 program before the failed reload');
    }
    // Edit loaded.cljs to a DISTINCT v2 body (moves the whole-build digest) that
    // ALSO throws at top-level before it re-registers: a compile-VALID build
    // whose RUNTIME activation fails.
    const throwing = replaceExactly(
      replaceExactly(loadedV1, 'loaded-probe-v1', 'loaded-probe-v2'),
      '(when false', '(when true',
    );
    fs.writeFileSync(LOADED_SOURCE, throwing);
    await shadow.waitSuccess(successC2);   // compile-valid: the build completes
    // The build ACCEPTED the distinct v2 candidate (compile-finish computed and
    // carried its digest); only runtime activation failed. This exact digest is
    // what a forward recovery must converge on — NOT the prior v1 identity.
    const acceptedC2 = acceptedSnapshot();
    if (acceptedC2.digest === digestBeforeC2) {
      fail('the v2 candidate did not move the whole-build digest; the forward-recovery oracle would be vacuous');
    }
    // The hot reload fences reads (before-load), the carrier stages the
    // candidate, then loaded.cljs throws before registration — so after-load
    // never promotes. A read fail-closing to null can ONLY happen when a hot
    // reload started (before-load ran) and did not finish (after-load skipped):
    // that is the runtime-activation fence holding. Reads must never advertise
    // the staged candidate.
    await page.waitForFunction(
      () => typeof globalThis.__rf2ReadDigest === 'function' &&
            globalThis.__rf2ReadDigest() === null,
      null,
      { timeout: TIMEOUT },
    );
    const stampedC2 = await page.evaluate(() => globalThis.__rf2ReadDigest());
    if (stampedC2 !== null) {
      fail(`runtime advertised an unactivated digest after a loaded throw: ${stampedC2}`);
    }
    // rf2-vxgfnd.242 — the mounted root and its COMPLETE descriptor fail closed
    // TOGETHER with the carrier while the failed candidate is rejected: the
    // rendered program stays on the last accepted v1, and the descriptor's
    // :build-digest is nil — never the staged v2 candidate, never the stale
    // prior digest. (Deleting the digest-carrier before/after-load fence branch
    // republishes a non-nil digest, flipping BOTH the null carrier read above
    // and the null descriptor read here red.)
    if ((await readMountedProgram(page)) !== 'loaded-probe-v1') {
      fail('mounted root left the accepted v1 program while the failed candidate was rejected');
    }
    const fencedDescriptorDigest = await readMountedDescriptorDigest(page);
    if (fencedDescriptorDigest !== null) {
      fail(`mounted root descriptor leaked a digest during the fence: ` +
           `${JSON.stringify(fencedDescriptorDigest)} (must be null — never the ` +
           `staged candidate ${acceptedC2.digest} nor the prior ${digestBeforeC2})`);
    }
    console.log(
      `ui digest carrier: loaded-source throw left carrier + mounted descriptor fail-closed` +
      (sawThrow ? ` (witnessed: ${String(sawThrow).slice(0, 80)})` : ''),
    );
    page.off('pageerror', onPageError);
    page.off('console', onConsole);

    // Forward recovery: remove ONLY the runtime-failure condition, KEEPING the
    // distinct v2 body. The reload runs after-load and the runtime must converge
    // on the EXACT compiler-accepted v2 digest/bytes (acceptedC2) — never fall
    // back to the prior v1 identity — advancing the mounted program exactly once.
    const successC2b = shadow.successCount();
    const recovered = replaceExactly(throwing, '(when true', '(when false');
    if (!recovered.includes('loaded-probe-v2') || recovered.includes('(when true')) {
      fail('recovery source must keep the distinct v2 body and only drop the throw');
    }
    fs.writeFileSync(LOADED_SOURCE, recovered);
    await shadow.waitSuccess(successC2b);
    // Re-activation must land the EXACT accepted v2 digest, not merely any bd1.
    await page.waitForFunction(
      (want) => typeof globalThis.__rf2ReadDigest === 'function' &&
                globalThis.__rf2ReadDigest() === want,
      acceptedC2.digest,
      { timeout: TIMEOUT },
    );
    const recoveredC2 = await page.evaluate(() => globalThis.__rf2ReadDigest());
    if (recoveredC2 !== acceptedC2.digest) {
      fail(`recovery did not converge on the accepted v2 digest: runtime ${recoveredC2}, accepted ${acceptedC2.digest}`);
    }
    if (recoveredC2 === digestBeforeC2) {
      fail(`recovery returned to the prior v1 identity ${digestBeforeC2}; it did not ` +
           `prove forward convergence to the accepted v2 candidate`);
    }
    if (acceptedSnapshot().digest !== recoveredC2) {
      fail('recovery runtime and JVM accepted witness disagree on the active digest');
    }
    // The mounted program advanced exactly once (v1 through the fence -> v2 now),
    // and the complete descriptor now carries the exact active v2 digest.
    if ((await readMountedProgram(page)) !== 'loaded-probe-v2') {
      fail('mounted root did not advance to the recovered v2 program');
    }
    const recoveredDescriptorDigest = await readMountedDescriptorDigest(page);
    if (recoveredDescriptorDigest !== recoveredC2) {
      fail(`mounted root descriptor did not restamp the exact active digest: ` +
           `descriptor ${JSON.stringify(recoveredDescriptorDigest)}, active ${recoveredC2}`);
    }

    // Counterexample 3 — overlapping async activations (rf2-vxgfnd.243). The
    // probe's default `:loader-mode :eval` runs a reload's
    // before-load/stage!/after-load synchronously per cycle, so real Shadow
    // never overlaps activations. Under `:loader-mode :script` a reload's
    // sources load asynchronously and a second reload's before-load/stage! can
    // interleave between a first reload's before-load and its after-load. We
    // MODEL that interleaving deterministically by driving the REAL carrier
    // cell's raw activation hooks (exported by base.cljs): two reloads paused,
    // then RELEASED NEWEST-FIRST, then a stale straggler firing after a newer
    // reload has re-fenced. The generation fence must make the stale after-load
    // INERT — never regressing the published digest, never clearing the newer
    // fence. (Deleting the `generation > activated` guard in digest-carrier's
    // after-load turns the stale straggler back into an unconditional
    // promote+release, flipping the "newer fence intact" assertion below red.)
    // Driven last: mutating the live cell corrupts nothing downstream.
    const dA = 'bd1-aaaaaaaaaaaaaaaa';
    const dB = 'bd1-bbbbbbbbbbbbbbbb';
    const dC = 'bd1-cccccccccccccccc';
    const carrierReady = await page.evaluate(() =>
      typeof globalThis.__rf2CarrierBeforeLoad === 'function' &&
      typeof globalThis.__rf2CarrierStage === 'function' &&
      typeof globalThis.__rf2CarrierAfterLoad === 'function');
    if (!carrierReady) {
      fail('counterexample 3: activation-transaction hooks were not exported');
    }
    if ((await page.evaluate(() => globalThis.__rf2ReadDigest())) !== recoveredC2) {
      fail('counterexample 3 did not start from the last accepted activated digest');
    }
    // Step the model and read `current` after each hook. `beforeLoad` fences
    // (null); `stage` mints the next generation; `afterLoad` promotes+releases
    // only for a not-yet-activated generation.
    const step = (op, arg) => page.evaluate(({ op, arg }) => {
      if (op === 'before') globalThis.__rf2CarrierBeforeLoad();
      else if (op === 'stage') globalThis.__rf2CarrierStage(arg);
      else if (op === 'after') globalThis.__rf2CarrierAfterLoad();
      return globalThis.__rf2ReadDigest();
    }, { op, arg });

    // Reload A pauses (fenced + staged); reload B pauses (fenced + staged).
    if ((await step('before')) !== null) fail('c3: reload A before-load did not fence reads');
    if ((await step('stage', dA)) !== null) fail('c3: staging under the fence published early');
    if ((await step('before')) !== null) fail('c3: reload B before-load did not keep reads fenced');
    if ((await step('stage', dB)) !== null) fail('c3: second staging published under the fence');
    // Release NEWEST-FIRST: reload B (the newer generation) activates.
    const afterB = await step('after');
    if (afterB !== dB) {
      fail(`c3: newest reload B did not publish its generation (got ${JSON.stringify(afterB)}, want ${dB})`);
    }
    // The STALE straggler — reload A's after-load, released second — must be
    // inert: its generation is no longer ahead of the activated high-water mark.
    const afterStaleA = await step('after');
    if (afterStaleA !== dB) {
      fail(`c3: stale reload A completion REGRESSED the active digest to ` +
           `${JSON.stringify(afterStaleA)} (must stay on the newer ${dB})`);
    }
    // A newer reload C now re-fences. A late stale straggler firing here must
    // NOT clear C's newer fence — the load-bearing overlap invariant.
    if ((await step('before')) !== null) fail('c3: reload C before-load did not re-fence reads');
    const staleAfterCFence = await step('after');
    if (staleAfterCFence !== null) {
      fail(`c3: a stale completion CLEARED the newer reload C's fence ` +
           `(read ${JSON.stringify(staleAfterCFence)}; the newer fence must hold reads closed)`);
    }
    // C completes normally and publishes its generation exactly once.
    if ((await step('stage', dC)) !== null) fail('c3: reload C staging published under its own fence');
    const afterC = await step('after');
    if (afterC !== dC) {
      fail(`c3: reload C did not publish its generation after release (got ${JSON.stringify(afterC)}, want ${dC})`);
    }
    console.log(
      'ui digest carrier: overlapping activations fenced by generation ' +
      '(newest-first release inert for the stale straggler; newer fence held)',
    );

    console.log(
      `ui digest carrier: PASS (${digest1} -> ${digest2} -> failed/LKG -> ${digest3}` +
      ` -> v2-loaded-throw/fail-closed -> forward-recovery ${recoveredC2}` +
      ` -> overlap-fence ${dB}/${dC})`,
    );
  } finally {
    fs.writeFileSync(LAZY_SOURCE, original);
    fs.writeFileSync(LOADED_SOURCE, loadedOriginal);
    fs.rmSync(FAIL_MARKER, { force: true });
    if (browser) await browser.close();
    if (shadow) await terminateProcessTree(shadow.child, { timeoutMs: 5000 });
  }
}

// rf2-vxgfnd.195 — the fifth real-host arm. Runs after the browser proof has
// torn down its own Shadow daemon, so the two never contend. It drives its OWN
// self-contained Shadow config (no browser) proving final-schedule
// reconciliation: an inherited re-frame.ui hook running before a build-local
// :compile-prepare hook that forces a viewless recompile, evicting the accepted
// row in both sequential and parallel modes, and failing loud when the per-pass
// schedule evidence is unavailable.
const { run: runFinalSchedule } = require('./check-ui-final-schedule.cjs');

main()
  .then(runFinalSchedule)
  .catch((error) => {
    console.error(error.stack || error.message || String(error));
    process.exitCode = 1;
  });
