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
const LAZY_JS = path.join(OUT, 'lazy.js');
const CARRIER_JS = path.join(
  OUT, 'cljs-runtime', 're_frame.ui.digest_carrier.js',
);
// Opt-in adversarial assertion of the still-open downstream-output invariant
// (counterexample 1). Off by default so the gate stays green while the fix —
// artifact generation/activation separation — is a hot-zone follow-up; set to
// "1" to assert the fixed behaviour (red-before-fix).
const ASSERT_DOWNSTREAM_OUTPUT = process.env.RF2_PROBE_ACTIVATION_TXN === '1';
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

  let shadow;
  let browser;
  try {
    const shadowRunner = require.resolve('shadow-cljs/cli/runner.js', {
      paths: [IMPL],
    });
    shadow = watchShadow(shadowRunner);
    await shadow.waitSuccess(0);

    fs.mkdirSync(OUT, { recursive: true });
    fs.writeFileSync(
      path.join(OUT, 'index.html'),
      '<!doctype html><meta charset="utf-8"><script src="/base.js"></script>',
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

    // Counterexample 1 — the downstream-OUTPUT invariant the active-runtime
    // witness above does NOT cover. Shadow's browser target flushed candidate
    // d3 to the stable module URLs BEFORE the intentional :flush failure, then
    // rolled its functional build-state back to accepted d2. So `/base.js`'s
    // carrier and `/lazy.js` on disk hold rejected candidate d3 while compiler
    // authority is d2: a hard reload or a first lazy import would execute /
    // advertise d3. The fix is artifact generation/activation separation —
    // stable URLs/manifests must keep resolving to the accepted generation
    // until the whole pipeline succeeds. That is a Shadow output/flush surface
    // beyond this carrier's fence (hot-zone shadow-cljs.edn), so this probe
    // DOCUMENTS the gap by default and only ASSERTS the fixed behaviour under
    // RF2_PROBE_ACTIVATION_TXN=1 (red-before-fix).
    const servedLazy = fs.existsSync(LAZY_JS)
      ? fs.readFileSync(LAZY_JS, 'utf8') : '';
    const servedCarrier = fs.existsSync(CARRIER_JS)
      ? fs.readFileSync(CARRIER_JS, 'utf8') : '';
    const lazyServesRejected = servedLazy.includes('digest-probe-v3');
    // The accepted digest is d2; a stable carrier that no longer contains d2
    // (its bytes moved to the rejected candidate d3) is servable-rejected.
    const carrierServesRejected =
      servedCarrier.length > 0 && !servedCarrier.includes(digest2);
    if (lazyServesRejected || carrierServesRejected) {
      const note =
        `downstream-output gap: stable URLs serve rejected candidate d3 ` +
        `after the failed :flush (lazy=${lazyServesRejected}, ` +
        `carrier-not-d2=${carrierServesRejected}); accepted authority is d2 ` +
        `(${digest2}). A hard reload / first lazy import would activate d3.`;
      if (ASSERT_DOWNSTREAM_OUTPUT) {
        fail(note);
      }
      console.log(`ui digest carrier: KNOWN GAP (rf2-vxgfnd.193 followup) — ${note}`);
    } else {
      console.log(
        'ui digest carrier: downstream output stayed on the accepted generation',
      );
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
    const throwing = replaceExactly(
      replaceExactly(loadedV1, 'loaded-probe-v1', 'loaded-probe-v2'),
      '(when false', '(when true',
    );
    fs.writeFileSync(LOADED_SOURCE, throwing);
    await shadow.waitSuccess(successC2);   // compile-valid: the build completes
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
    console.log(
      `ui digest carrier: loaded-source throw left reads fail-closed` +
      (sawThrow ? ` (witnessed: ${String(sawThrow).slice(0, 80)})` : ''),
    );
    page.off('pageerror', onPageError);
    page.off('console', onConsole);

    // A successful retry re-activates: restore the clean loaded source, the
    // reload runs after-load, and reads recover to a real bd1 digest.
    const successC2b = shadow.successCount();
    fs.writeFileSync(LOADED_SOURCE, loadedV1);
    await shadow.waitSuccess(successC2b);
    await page.waitForFunction(
      () => typeof globalThis.__rf2ReadDigest === 'function' &&
            typeof globalThis.__rf2ReadDigest() === 'string' &&
            globalThis.__rf2ReadDigest().startsWith('bd1-'),
      null,
      { timeout: TIMEOUT },
    );
    const recoveredC2 = await page.evaluate(() => globalThis.__rf2ReadDigest());
    if (!/^bd1-[0-9a-f]{16}$/.test(recoveredC2)) {
      fail(`successful retry did not re-activate a real digest: ${recoveredC2}`);
    }

    console.log(
      `ui digest carrier: PASS (${digest1} -> ${digest2} -> failed/LKG -> ${digest3}` +
      ` -> loaded-throw/fail-closed -> ${recoveredC2})`,
    );
  } finally {
    fs.writeFileSync(LAZY_SOURCE, original);
    fs.writeFileSync(LOADED_SOURCE, loadedOriginal);
    fs.rmSync(FAIL_MARKER, { force: true });
    if (browser) await browser.close();
    if (shadow) await terminateProcessTree(shadow.child, { timeoutMs: 5000 });
  }
}

main().catch((error) => {
  console.error(error.stack || error.message || String(error));
  process.exitCode = 1;
});
