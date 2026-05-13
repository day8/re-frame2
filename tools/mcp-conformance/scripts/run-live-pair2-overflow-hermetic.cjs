#!/usr/bin/env node
/*
 * Hermetic orchestrator for the live-pair2-overflow conformance test
 * (rf2-uw6d6, follow-on from rf2-ynaoc).
 *
 * The sibling test (`test/live-pair2-overflow.js`) is gated on
 * $SHADOW_CLJS_NREPL_PORT. Without that env var it exits 0 with a SKIP
 * marker because the pair2-mcp server runs degraded — no real eval, no
 * cap-trigger, no overflow marker.
 *
 * This script makes the live path *actually* fire on CI by:
 *
 *   1. Spawning `shadow-cljs watch app` against the pair2 fixture at
 *      `skills/re-frame-pair2/tests/fixture/` — a tiny re-frame2 counter
 *      with `re-frame-pair2.runtime` already wired as a `:devtools
 *      :preloads` entry. The shadow-cljs build also serves an
 *      http-server on :8030.
 *   2. Waiting for the nREPL port file to land at
 *      `target/shadow-cljs/nrepl.port` and reading the port.
 *   3. Launching headless Chromium (Playwright) at
 *      http://localhost:8030 so the bundle loads and the runtime
 *      preload sets `window.__re_frame_pair2_runtime`.
 *   4. Waiting for the runtime sentinel to be present (so
 *      `ensure-runtime!` will pass on the first call).
 *   5. Setting SHADOW_CLJS_NREPL_PORT=<port> and spawning
 *      `node test/live-pair2-overflow.js`.
 *   6. Tearing down browser + shadow-cljs cleanly on success, failure,
 *      or signal.
 *
 * Exit codes:
 *   0  hermetic conformance passed
 *   1  conformance failure (overflow marker / SDK / etc.)
 *   2  orchestration failure (shadow-cljs didn't boot, port file
 *      missing, runtime preload didn't land, watchdog elapsed)
 *
 * Hard time-cap of HERMETIC_TIMEOUT_MS guards against runaway compiles
 * on a cold CI runner. The shadow-cljs `watch` child + Playwright
 * browser are killed in the `finally`; SIGINT/SIGTERM also wire to the
 * same cleanup.
 */
'use strict';

const { spawn, spawnSync } = require('node:child_process');
const fs = require('node:fs');
const http = require('node:http');
const net = require('node:net');
const path = require('node:path');
const os = require('node:os');

const HERE = __dirname;
const MCP_CONFORMANCE_ROOT = path.resolve(HERE, '..');
const REPO_ROOT = path.resolve(MCP_CONFORMANCE_ROOT, '..', '..');
const FIXTURE_DIR = path.join(
  REPO_ROOT,
  'skills',
  're-frame-pair2',
  'tests',
  'fixture',
);
const PAIR2_MCP_DIR = path.join(REPO_ROOT, 'tools', 'pair2-mcp');
const NREPL_PORT_FILE = path.join(
  FIXTURE_DIR,
  'target',
  'shadow-cljs',
  'nrepl.port',
);

const FIXTURE_HTTP_PORT = 8030; // hard-coded in fixture's shadow-cljs.edn
const FIXTURE_URL = `http://127.0.0.1:${FIXTURE_HTTP_PORT}/`;

// Wall-clock caps. shadow-cljs cold-start with a warm Maven cache is
// typically 30-60s on GHA; warm restart is much faster. We give the
// boot 360s so the first cold-cache run of the day (no `~/.m2`
// restore hit at all) still has headroom while Maven resolves the
// fixture's :local/root deps (core + reagent + epoch + Reagent/Malli
// trees). The CI workflow's `mcp-conformance-pair2` job hashes those
// inputs into its actions/cache key (rf2-c565x), so this stopgap only
// kicks in on the truly cold path; warm-cache runs still bind the
// nREPL port in <60s.
const SHADOW_BOOT_TIMEOUT_MS = 360_000;
const RUNTIME_PRELOAD_TIMEOUT_MS = 60_000;
const HERMETIC_TIMEOUT_MS = 540_000;
const POLL_MS = 500;

const LIVE_TEST = path.join(
  MCP_CONFORMANCE_ROOT,
  'test',
  'live-pair2-overflow.js',
);

function log(msg) {
  process.stdout.write(`[hermetic] ${msg}\n`);
}
function logErr(msg) {
  process.stderr.write(`[hermetic] ${msg}\n`);
}

function exists(p) {
  try {
    fs.statSync(p);
    return true;
  } catch {
    return false;
  }
}

function sleep(ms) {
  return new Promise((r) => setTimeout(r, ms));
}

function readPortFile() {
  try {
    const txt = fs.readFileSync(NREPL_PORT_FILE, 'utf8').trim();
    const n = parseInt(txt, 10);
    return Number.isFinite(n) ? n : null;
  } catch {
    return null;
  }
}

function probeHttp(port, hostname = '127.0.0.1') {
  return new Promise((resolve) => {
    const req = http.get(
      { host: hostname, port, path: '/', timeout: 1000 },
      (res) => {
        res.resume();
        resolve(res.statusCode != null);
      },
    );
    req.on('error', () => resolve(false));
    req.on('timeout', () => {
      req.destroy();
      resolve(false);
    });
  });
}

function probeTcp(port, hostname = '127.0.0.1') {
  return new Promise((resolve) => {
    const sock = net.connect({ host: hostname, port, timeout: 1000 }, () => {
      sock.end();
      resolve(true);
    });
    sock.on('error', () => resolve(false));
    sock.on('timeout', () => {
      sock.destroy();
      resolve(false);
    });
  });
}

async function waitUntil(label, predicate, timeoutMs) {
  const deadline = Date.now() + timeoutMs;
  while (Date.now() < deadline) {
    if (await predicate()) return true;
    await sleep(POLL_MS);
  }
  throw new Error(`timeout after ${timeoutMs}ms waiting for: ${label}`);
}

function spawnNpm(cmd, args, cwd) {
  const isWin = process.platform === 'win32';
  const bin = isWin ? `${cmd}.cmd` : cmd;
  const r = spawnSync(bin, args, {
    cwd,
    stdio: 'inherit',
    shell: isWin,
    env: process.env,
  });
  if (r.status !== 0) {
    throw new Error(`${cmd} ${args.join(' ')} in ${cwd} exited ${r.status}`);
  }
}

function resolvePlaywright() {
  // Resolve playwright either from local mcp-conformance deps or from
  // the implementation/ tree (which already lists it as a devDep). The
  // CI job installs both; locally Mike's machine likely has at least
  // one path. require.resolve throws if neither has it.
  const candidates = [MCP_CONFORMANCE_ROOT, path.join(REPO_ROOT, 'implementation')];
  for (const root of candidates) {
    try {
      const pwPath = require.resolve('playwright', { paths: [root] });
      return require(pwPath);
    } catch {
      // try next
    }
  }
  throw new Error(
    'playwright is not resolvable from tools/mcp-conformance or implementation/. ' +
      'Run `npm install` in one of those directories first.',
  );
}

async function main() {
  // ---- Sanity: required artefacts on disk -------------------------------
  if (!exists(path.join(FIXTURE_DIR, 'shadow-cljs.edn'))) {
    throw new Error(`fixture missing: ${FIXTURE_DIR}`);
  }
  if (!exists(LIVE_TEST)) {
    throw new Error(`live test missing: ${LIVE_TEST}`);
  }
  if (!exists(path.join(PAIR2_MCP_DIR, 'out', 'server.js'))) {
    throw new Error(
      `pair2-mcp server bundle missing: ${path.join(PAIR2_MCP_DIR, 'out', 'server.js')}. ` +
        'Compile with `npx shadow-cljs compile server` in tools/pair2-mcp first.',
    );
  }

  // ---- Wipe any stale port file ----------------------------------------
  // A leftover port file from a previous run could otherwise satisfy
  // the poll-loop before shadow-cljs has actually re-bound to the port,
  // and the subsequent nREPL connect would race. The shadow-cljs watch
  // child will rewrite this file as part of its boot.
  try {
    if (exists(NREPL_PORT_FILE)) {
      fs.unlinkSync(NREPL_PORT_FILE);
      log(`removed stale port file ${NREPL_PORT_FILE}`);
    }
  } catch (e) {
    log(`could not remove stale port file (${e.message}); continuing`);
  }

  // ---- Install fixture deps --------------------------------------------
  if (!exists(path.join(FIXTURE_DIR, 'node_modules'))) {
    log(`installing fixture deps in ${FIXTURE_DIR}`);
    spawnNpm('npm', ['install', '--no-audit', '--no-fund'], FIXTURE_DIR);
  }

  // ---- Boot shadow-cljs watch ------------------------------------------
  const isWin = process.platform === 'win32';
  const shadowBin = isWin ? 'npx.cmd' : 'npx';
  log(`spawning shadow-cljs watch app in ${FIXTURE_DIR}`);
  const shadow = spawn(shadowBin, ['shadow-cljs', 'watch', 'app'], {
    cwd: FIXTURE_DIR,
    stdio: ['ignore', 'pipe', 'pipe'],
    shell: isWin,
    env: { ...process.env, FORCE_COLOR: '0' },
  });
  let shadowExited = false;
  shadow.on('exit', (code, sig) => {
    shadowExited = true;
    log(`shadow-cljs exited code=${code} sig=${sig}`);
  });
  shadow.stdout.on('data', (d) => {
    process.stdout.write('[shadow] ' + d.toString());
  });
  shadow.stderr.on('data', (d) => {
    process.stderr.write('[shadow] ' + d.toString());
  });

  let browser = null;

  // Wire signal-driven cleanup so a CI cancel doesn't strand the JVM.
  const cleanup = () => {
    if (browser) {
      try { browser.close(); } catch {}
    }
    if (shadow && !shadowExited) {
      try { shadow.kill('SIGTERM'); } catch {}
      // Give it a moment to exit; SIGKILL if still up.
      setTimeout(() => {
        if (!shadowExited) {
          try { shadow.kill('SIGKILL'); } catch {}
        }
      }, 5000).unref();
    }
  };
  for (const sig of ['SIGINT', 'SIGTERM', 'SIGHUP']) {
    process.on(sig, () => {
      logErr(`caught ${sig} — tearing down`);
      cleanup();
      process.exit(130);
    });
  }

  try {
    // ---- Wait for nREPL port file ---------------------------------------
    log('waiting for shadow-cljs nREPL port file');
    let port = null;
    await waitUntil(
      'nREPL port file',
      async () => {
        if (shadowExited) {
          throw new Error('shadow-cljs exited before binding nREPL port');
        }
        const p = readPortFile();
        if (p) {
          port = p;
          return true;
        }
        return false;
      },
      SHADOW_BOOT_TIMEOUT_MS,
    );
    log(`nREPL bound to port ${port}`);

    // ---- Wait for nREPL TCP listener actually accepting -----------------
    await waitUntil(
      `nREPL TCP listener on :${port}`,
      () => probeTcp(port),
      SHADOW_BOOT_TIMEOUT_MS,
    );
    log(`nREPL TCP accepting on :${port}`);

    // ---- Wait for http server -------------------------------------------
    await waitUntil(
      `fixture http on :${FIXTURE_HTTP_PORT}`,
      () => probeHttp(FIXTURE_HTTP_PORT),
      SHADOW_BOOT_TIMEOUT_MS,
    );
    log(`fixture http reachable at ${FIXTURE_URL}`);

    // ---- Launch headless Chromium + load page ---------------------------
    const playwright = resolvePlaywright();
    log('launching headless Chromium');
    browser = await playwright.chromium.launch({ headless: true });
    const context = await browser.newContext();
    const page = await context.newPage();
    page.on('console', (msg) => {
      process.stdout.write(`[browser:${msg.type()}] ${msg.text()}\n`);
    });
    page.on('pageerror', (err) => {
      process.stderr.write(`[browser:error] ${err.message}\n`);
    });
    await page.goto(FIXTURE_URL, { waitUntil: 'load' });
    log('page loaded');

    // ---- Wait for the runtime sentinel ----------------------------------
    // The preload mirrors itself onto js/globalThis.__re_frame_pair2_runtime
    // at load time. This is exactly what pair2-mcp probes via
    // ensure-runtime!; if it's not present, eval-cljs returns
    // :reason :runtime-not-preloaded and the overflow path never trips.
    await page.waitForFunction(
      () => typeof window.__re_frame_pair2_runtime !== 'undefined',
      undefined,
      { timeout: RUNTIME_PRELOAD_TIMEOUT_MS },
    );
    const sentinel = await page.evaluate(() => window.__re_frame_pair2_runtime);
    log(`runtime preload sentinel = ${JSON.stringify(sentinel)}`);

    // ---- Run the live-overflow test -------------------------------------
    log(`running ${path.basename(LIVE_TEST)} with SHADOW_CLJS_NREPL_PORT=${port}`);
    const testEnv = {
      ...process.env,
      SHADOW_CLJS_NREPL_PORT: String(port),
    };
    const testRun = spawnSync(process.execPath, [LIVE_TEST], {
      cwd: MCP_CONFORMANCE_ROOT,
      stdio: 'inherit',
      env: testEnv,
    });
    if (testRun.status !== 0) {
      // Surface the inner test's exit code verbatim so CI sees a
      // conformance failure as exit 1 (the test's own code) rather
      // than 2 (which we reserve for orchestration failures —
      // shadow-cljs didn't boot, runtime didn't preload, etc.).
      const err = new Error(`live-pair2-overflow.js exited ${testRun.status}`);
      err.exitCode = testRun.status;
      throw err;
    }
    log('PAIR2-MCP LIVE OVERFLOW HERMETIC CONFORMANCE GREEN');
  } finally {
    cleanup();
  }
}

// Hard watchdog: if the orchestrator hangs past this, kill the process
// so CI gets a deterministic failure instead of waiting on the job
// timeout. Length set to cover cold Maven cache + cold chromium boot.
const watchdog = setTimeout(() => {
  logErr(`watchdog timeout (${HERMETIC_TIMEOUT_MS}ms) — bailing`);
  process.exit(2);
}, HERMETIC_TIMEOUT_MS);
watchdog.unref();

main()
  .then(() => {
    clearTimeout(watchdog);
    process.exit(0);
  })
  .catch((err) => {
    clearTimeout(watchdog);
    logErr('FAIL: ' + (err && err.message ? err.message : err));
    if (err && err.stack) logErr(err.stack);
    // err.exitCode is set when the inner live-pair2-overflow.js itself
    // exited non-zero — surface it so CI distinguishes conformance
    // failure (1) from orchestration failure (2).
    process.exit(err && typeof err.exitCode === 'number' ? err.exitCode : 2);
  });
