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
 *   2. Waiting for the nREPL port file to land (shadow-cljs 3.x writes
 *      to `.shadow-cljs/nrepl.port`; we also probe the legacy
 *      `target/shadow-cljs/nrepl.port` and `.nrepl-port` fallbacks).
 *   3. Waiting for the `:app` bundle to actually compile (not just the
 *      nREPL port to bind — dev-http serves a SPA-style fallback HTML
 *      for `/out/main.js` 200 BEFORE the first compile completes; we
 *      gate on the Content-Type being JS).
 *   4. Launching headless Chromium (Playwright) at
 *      http://localhost:8030 so the bundle loads and the runtime
 *      preload sets `window.__re_frame_pair2_runtime`.
 *   5. Waiting for the runtime sentinel to be present in the browser
 *      AND for shadow-cljs's `:app` runtime to be addressable via
 *      `cljs-eval` over nREPL (so pair2-mcp's `ensure-runtime!` probe
 *      sees the runtime — that probe `.catch`es shadow's
 *      "no-runtime-connected" error to `false`, surfacing as a false
 *      `:runtime-not-preloaded`).
 *   6. Setting SHADOW_CLJS_NREPL_PORT=<port> and spawning
 *      `node test/live-pair2-overflow.js`.
 *   7. Tearing down browser + shadow-cljs cleanly on success, failure,
 *      or signal.
 *
 * Per rf2-kp1d8: pre-fix the script only waited on the nREPL port file
 * (at the wrong path) and the browser sentinel; the missing bundle-
 * compile and shadow-runtime gates surfaced as a 6-minute timeout on
 * CI even after rf2-papcw landed the deterministic catalogue fix.
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

// Shadow-cljs writes its nREPL port file under whichever cache-root
// the build is configured for. Default in 3.x is `.shadow-cljs/`; older
// configs used `target/shadow-cljs/`; nrepl itself drops `.nrepl-port`.
// pair2-mcp's runtime probe (`re_frame_pair2_mcp/nrepl.cljs`
// `port-file-candidates`) walks the same list — keep them in lockstep.
// Per rf2-kp1d8: the orchestrator previously only watched the legacy
// `target/shadow-cljs/nrepl.port` path; shadow-cljs 3.4.10 wrote to
// `.shadow-cljs/nrepl.port` and the harness timed out at 360s waiting
// for a file that was never going to arrive.
const NREPL_PORT_FILE_CANDIDATES = [
  path.join(FIXTURE_DIR, '.shadow-cljs', 'nrepl.port'),
  path.join(FIXTURE_DIR, 'target', 'shadow-cljs', 'nrepl.port'),
  path.join(FIXTURE_DIR, '.nrepl-port'),
];

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
  // Walk every candidate path; return `{port, source}` for the first
  // file that parses to a finite integer. The `source` string is used
  // for diagnostics so a successful read tells you *which* path
  // satisfied the wait — useful when shadow-cljs's default cache-root
  // moves between versions.
  for (const p of NREPL_PORT_FILE_CANDIDATES) {
    try {
      const txt = fs.readFileSync(p, 'utf8').trim();
      const n = parseInt(txt, 10);
      if (Number.isFinite(n)) return { port: n, source: p };
    } catch {
      // try next
    }
  }
  return null;
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

// Probe `/out/main.js` and confirm it is the actual compiled bundle —
// not shadow-cljs's SPA-style fallback HTML that dev-http returns 200
// for unknown paths. Used to wait for the bundle to compile before
// Chromium navigates; without this gate the page loads while shadow is
// still on its first compile, the runtime preload never runs, and the
// sentinel-wait times out (rf2-kp1d8).
//
// We accept the response iff the Content-Type starts with
// `application/javascript` (shadow's dev-http sets this for .js files
// it actually serves) AND the body begins with a known shadow-cljs
// preamble byte. The preamble guard is belt-and-braces — a partial
// write during compile would also fail the content-type check.
function probeBundleReady(port, hostname = '127.0.0.1') {
  return new Promise((resolve) => {
    const req = http.get(
      { host: hostname, port, path: '/out/main.js', timeout: 2000 },
      (res) => {
        const ct = (res.headers['content-type'] || '').toLowerCase();
        if (res.statusCode !== 200 || !ct.startsWith('application/javascript')) {
          res.resume();
          resolve(false);
          return;
        }
        // Read up to ~256 bytes to confirm the body is actually JS.
        // shadow-cljs's first-line preamble starts with `var $CLJS` or
        // a `SHADOW_ENV.setLoaded`/`var shadow=...` — anything beginning
        // with `<` is the SPA fallback HTML.
        let prefix = '';
        res.on('data', (chunk) => {
          prefix += chunk.toString('utf8');
          if (prefix.length >= 64) {
            req.destroy();
            const head = prefix.slice(0, 64).trimStart();
            resolve(!head.startsWith('<'));
          }
        });
        res.on('end', () => {
          const head = prefix.slice(0, 64).trimStart();
          resolve(prefix.length > 0 && !head.startsWith('<'));
        });
        res.on('error', () => resolve(false));
      },
    );
    req.on('error', () => resolve(false));
    req.on('timeout', () => {
      req.destroy();
      resolve(false);
    });
  });
}

// Probe shadow-cljs's `:app` CLJS runtime by attempting a trivial
// `cljs-eval` over nREPL. Returns true iff the eval round-trips
// successfully — which only happens once the browser-side runtime has
// registered with shadow via the devtools WebSocket.
//
// Why this is necessary: pair2-mcp's `runtime-preloaded?` (in
// `tools/probe.cljs`) wraps `cljs-eval` in a `.catch` that swallows
// every error to `false`, including the transient "No application has
// connected to the REPL server" error that shadow throws between
// page-load and runtime-registration. Without this gate the live-test
// fires while the runtime isn't yet addressable and pair2-mcp's first
// `eval-cljs` call surfaces as `:runtime-not-preloaded` — a false
// negative on the actual hermetic conformance.
//
// One bencode round-trip on a fresh socket; we don't try to share the
// connection with the live test because the live test fork-execs the
// pair2-mcp server (which opens its own nREPL connection).
function probeShadowRuntimeReady(nreplPort, hostname = '127.0.0.1') {
  return new Promise((resolve) => {
    const sock = net.connect({ host: hostname, port: nreplPort, timeout: 2000 });
    let buf = Buffer.alloc(0);
    let done = false;
    const finish = (ok) => {
      if (done) return;
      done = true;
      try { sock.end(); } catch {}
      resolve(ok);
    };
    sock.on('connect', () => {
      // Bencode-encoded nREPL eval op: route through
      // `shadow.cljs.devtools.api/cljs-eval` on build `:app`. A trivial
      // `1` form keeps the probe fast and avoids any reliance on the
      // runtime sentinel — we only care whether shadow can route the
      // eval to a connected runtime.
      const code =
        '(shadow.cljs.devtools.api/cljs-eval :app "1" {})';
      // Minimal bencode hand-encode (avoid adding a dep). Op fields:
      // {"op": "eval", "code": <code>, "id": "rt-probe"}
      const dict =
        'd' +
        '2:id' + '8:rt-probe' +
        '2:op' + '4:eval' +
        '4:code' + code.length + ':' + code +
        'e';
      sock.write(dict, 'utf8');
    });
    sock.on('data', (chunk) => {
      buf = Buffer.concat([buf, chunk]);
      const txt = buf.toString('utf8');
      // Look for a `:results` frame (signals successful eval round-trip
      // with a CLJS value coming back). The shape shadow emits is
      // bencoded but `:results` survives as literal bytes inside the
      // dict's `value` field. If shadow has no runtime it sends back
      // `:ex` with "No application has connected to the REPL server" —
      // we treat that as not-ready.
      if (txt.includes(':results') && !txt.includes('No application')) {
        finish(true);
      } else if (txt.includes('"status"') && txt.includes('done')) {
        // Op completed but no `:results` — either an error or empty.
        finish(false);
      }
    });
    sock.on('error', () => finish(false));
    sock.on('timeout', () => finish(false));
    sock.on('close', () => finish(false));
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

  // ---- Wipe any stale port files ---------------------------------------
  // A leftover port file from a previous run could otherwise satisfy
  // the poll-loop before shadow-cljs has actually re-bound to the port,
  // and the subsequent nREPL connect would race. The shadow-cljs watch
  // child will rewrite the appropriate file as part of its boot. Wipe
  // ALL candidate paths so a stale entry at one location can't shadow
  // the fresh file at another.
  for (const p of NREPL_PORT_FILE_CANDIDATES) {
    try {
      if (exists(p)) {
        fs.unlinkSync(p);
        log(`removed stale port file ${p}`);
      }
    } catch (e) {
      log(`could not remove stale port file ${p} (${e.message}); continuing`);
    }
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
    log(
      `waiting for shadow-cljs nREPL port file; candidates: ${
        NREPL_PORT_FILE_CANDIDATES.join(', ')
      }`,
    );
    let port = null;
    await waitUntil(
      'nREPL port file',
      async () => {
        if (shadowExited) {
          throw new Error('shadow-cljs exited before binding nREPL port');
        }
        const hit = readPortFile();
        if (hit) {
          port = hit.port;
          log(`nREPL port file appeared at ${hit.source}`);
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

    // ---- Wait for the :app bundle to actually compile -------------------
    // shadow-cljs `watch` writes the nREPL port file and starts the
    // dev-http server BEFORE the first compile completes. The fixture's
    // public/index.html references `/out/main.js`; if Chromium navigates
    // before that file exists the bundle 404s, no CLJS runs, and the
    // preload sentinel never lands. We poll the asset URL until it
    // returns 200, then navigate. The first cold compile on CI runs
    // 10–20s after the watch is up; rebuilds on a warm cache are <1s.
    // Per rf2-kp1d8 — the earlier shape navigated immediately and then
    // waited 60s for the sentinel, which never arrived.
    await waitUntil(
      `fixture bundle at ${FIXTURE_URL}out/main.js`,
      () => probeBundleReady(FIXTURE_HTTP_PORT),
      SHADOW_BOOT_TIMEOUT_MS,
    );
    log('fixture bundle compiled and served');

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

    // ---- Wait for shadow to register the browser runtime ----------------
    // pair2-mcp routes its preload-probe through `shadow.cljs.devtools.api/
    // cljs-eval :app ...` over the nREPL. Shadow dispatches that to
    // whichever CLJS runtime is currently connected for the build. The
    // browser's runtime registers via the shadow devtools WebSocket on
    // page load, but there's a brief window between page-load and the
    // websocket handshake during which `cljs-eval` returns
    // "No application has connected to the REPL server. Make sure your
    // JS environment has loaded your compiled ClojureScript code." —
    // which `runtime-preloaded?` catches and surfaces as
    // `:runtime-not-preloaded` (the .catch in `tools/probe.cljs`
    // swallows the underlying nREPL error). Poll the same probe pair2-mcp
    // uses until it returns true, so we hand off to the live test only
    // after the runtime is actually addressable. Per rf2-kp1d8.
    log('waiting for shadow :app runtime to register');
    await waitUntil(
      'shadow :app runtime addressable via cljs-eval',
      () => probeShadowRuntimeReady(port),
      RUNTIME_PRELOAD_TIMEOUT_MS,
    );
    log('shadow :app runtime addressable');

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
