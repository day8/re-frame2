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
  spawnHarnessProcess,
  terminateProcessTree,
} = require('./lib/local-browser-harness.cjs');

const IMPL = path.join(__dirname, '..');
const LAZY_SOURCE = path.join(
  IMPL, 'ui', 'test', 're_frame', 'ui', 'digest_probe', 'lazy.cljs',
);
const OUT = path.join(IMPL, 'out', 'ui-digest-probe-lazy');
const TARGET = path.join(IMPL, 'target', 'ui-digest-probe');
const FAIL_MARKER = path.join(TARGET, 'fail-after-compile');
const ACCEPTED_FILE = path.join(TARGET, 'accepted-digest.txt');
const PREPARE_FILE = path.join(TARGET, 'prepare-snapshots.tsv');
const URL = 'http://127.0.0.1:8059/index.html';
const TIMEOUT = 90000;

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
  await page.waitForFunction(
    (oldDigest) => typeof globalThis.__rf2ReadDigest === 'function' &&
                   globalThis.__rf2ReadDigest() !== oldDigest,
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

async function main() {
  const original = fs.readFileSync(LAZY_SOURCE, 'utf8');
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

    console.log(
      `ui digest carrier: PASS (${digest1} -> ${digest2} -> failed/LKG -> ${digest3})`,
    );
  } finally {
    fs.writeFileSync(LAZY_SOURCE, original);
    fs.rmSync(FAIL_MARKER, { force: true });
    if (browser) await browser.close();
    if (shadow) await terminateProcessTree(shadow.child, { timeoutMs: 5000 });
  }
}

main().catch((error) => {
  console.error(error.stack || error.message || String(error));
  process.exitCode = 1;
});
