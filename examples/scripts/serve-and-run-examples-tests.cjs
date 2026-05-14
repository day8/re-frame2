#!/usr/bin/env node
/*
 * Orchestrator for the examples Playwright suite.
 *
 * 1. Compiles every example shadow-cljs build (one per example).
 * 2. Stages each example's hand-written index.html into its
 *    out/examples/<name>/ directory next to main.js.
 * 3. Spawns http-server over out/examples on port 8030.
 * 4. Waits for it to be reachable, then runs the Playwright runner
 *    (run-examples-tests.cjs).
 * 5. Always tears the server down.
 *
 * Build list, mount paths, and HTML sources are declared in EXAMPLES
 * below. Adding a new example: add its shadow-cljs build target,
 * append an entry here, and add a Playwright spec.
 *
 * Cross-platform: compile via npx, but launch http-server directly from the
 * local package so teardown kills the real server process on Windows too.
 */

const { spawn, spawnSync } = require('child_process');
const fs = require('fs');
const http = require('http');
const path = require('path');

const PORT = 8030;
// __dirname is <repo>/examples/scripts. IMPL_ROOT is <repo>/implementation
// (where shadow-cljs runs and node_modules lives); REPO_ROOT is <repo>.
const REPO_ROOT = path.resolve(__dirname, '..', '..');
const IMPL_ROOT = path.join(REPO_ROOT, 'implementation');
const OUT_ROOT = path.join(IMPL_ROOT, 'out', 'examples');
const RUNNER = path.resolve(__dirname, 'run-examples-tests.cjs');
// http-server is a devDependency of implementation/package.json. Resolve
// it from there explicitly so this script can be invoked from any cwd.
const HTTP_SERVER_BIN = require.resolve('http-server/bin/http-server', {
  paths: [IMPL_ROOT],
});
const READY_TIMEOUT_MS = 30000;
const POLL_MS = 200;

// Each example: shadow-cljs build id, the html source to stage, and the
// directory under out/examples it lands in. The url-path is what the
// Playwright spec navigates to under http://127.0.0.1:PORT/.
const EXAMPLES = [
  {
    build: 'examples/counter',
    htmlSrc: path.join(REPO_ROOT, 'examples', 'reagent', 'counter', 'index.html'),
    outDir: path.join(OUT_ROOT, 'counter'),
  },
  // counter-slim-and-fast (rf2-5lbx) — the same counter mounted on
  // day8/reagent-slim instead of the bridge. Paired with the
  // bundle-comparison contract in scripts/check-counter-slim-and-fast.cjs.
  {
    build: 'examples/counter-slim-and-fast',
    htmlSrc: path.join(REPO_ROOT, 'examples', 'reagent', 'counter_slim_and_fast', 'index.html'),
    outDir: path.join(OUT_ROOT, 'counter-slim-and-fast'),
  },
  // Performance-API instrumented variant of the counter.
  // rf2-p8f2s — the perf testbed is tool-owned (lives under
  // tools/causa/testbeds/perf_counter/) so the canonical tutorial
  // counter (examples/reagent/counter/) stays on the idiomatic
  // reg-event-db :initialise shape. The paired
  // tools/causa/testbeds/perf_counter/spec.cjs asserts a real
  // dispatch through the perf-on bundle produces `rf:event:*` /
  // `rf:sub:*` / `rf:fx:*` / `rf:render:*` measure entries on
  // `performance.getEntriesByType`.
  {
    build: 'examples/counter-perf',
    htmlSrc: path.join(REPO_ROOT, 'tools', 'causa', 'testbeds', 'perf_counter', 'index.html'),
    outDir: path.join(OUT_ROOT, 'counter-perf'),
  },
  {
    build: 'examples/temperature',
    htmlSrc: path.join(REPO_ROOT, 'examples', 'reagent', '7Guis', 'temperature', 'temperature.html'),
    outDir: path.join(OUT_ROOT, 'temperature'),
  },
  {
    build: 'examples/flight-booker',
    htmlSrc: path.join(REPO_ROOT, 'examples', 'reagent', '7Guis', 'flight_booker', 'flight_booker.html'),
    outDir: path.join(OUT_ROOT, 'flight-booker'),
  },
  {
    build: 'examples/timer',
    htmlSrc: path.join(REPO_ROOT, 'examples', 'reagent', '7Guis', 'timer', 'timer.html'),
    outDir: path.join(OUT_ROOT, 'timer'),
  },
  {
    build: 'examples/nine-states',
    htmlSrc: path.join(REPO_ROOT, 'examples', 'reagent', 'nine_states', 'index.html'),
    outDir: path.join(OUT_ROOT, 'nine-states'),
  },
  {
    build: 'examples/routing',
    htmlSrc: path.join(REPO_ROOT, 'examples', 'reagent', 'routing', 'index.html'),
    outDir: path.join(OUT_ROOT, 'routing'),
  },
  {
    build: 'examples/todomvc',
    htmlSrc: path.join(REPO_ROOT, 'examples', 'reagent', 'todomvc', 'index.html'),
    outDir: path.join(OUT_ROOT, 'todomvc'),
    extraFiles: [
      {
        src: path.join(IMPL_ROOT, 'node_modules', 'todomvc-common', 'base.css'),
        dest: 'base.css',
      },
      {
        src: path.join(IMPL_ROOT, 'node_modules', 'todomvc-app-css', 'index.css'),
        dest: 'todomvc-app.css',
      },
    ],
  },
  // Spec 014 — managed HTTP counter. Stages a static
  // /api/inc.json for the +1 button to fetch; a missing /api/does-not-exist
  // path naturally produces a 404 from http-server for the Fail button.
  {
    build: 'examples/managed-http-counter',
    htmlSrc: path.join(REPO_ROOT, 'examples', 'reagent', 'managed_http_counter', 'index.html'),
    outDir: path.join(OUT_ROOT, 'managed-http-counter'),
    extraFiles: [
      {
        src: path.join(REPO_ROOT, 'examples', 'reagent', 'managed_http_counter', 'api', 'inc.json'),
        dest: path.join('api', 'inc.json'),
      },
    ],
  },
  {
    build: 'examples/crud',
    htmlSrc: path.join(REPO_ROOT, 'examples', 'reagent', '7Guis', 'crud', 'crud.html'),
    outDir: path.join(OUT_ROOT, 'crud'),
  },
  {
    build: 'examples/circle-drawer',
    htmlSrc: path.join(REPO_ROOT, 'examples', 'reagent', '7Guis', 'circle_drawer', 'circle_drawer.html'),
    outDir: path.join(OUT_ROOT, 'circle-drawer'),
  },
  {
    build: 'examples/cells',
    htmlSrc: path.join(REPO_ROOT, 'examples', 'reagent', '7Guis', 'cells', 'cells.html'),
    outDir: path.join(OUT_ROOT, 'cells'),
  },
  {
    build: 'examples/login',
    htmlSrc: path.join(REPO_ROOT, 'examples', 'reagent', 'login', 'index.html'),
    outDir: path.join(OUT_ROOT, 'login'),
  },
  // UIx adapter smoke trio (counter + login, realworld skipped).
  // Different folders from the canonical Reagent versions so the
  // bundle-isolation grep can confirm UIx code does NOT appear in
  // Reagent-substrate examples and vice versa. The UIx tree lives
  // under examples/uix/.
  {
    build: 'examples/counter-uix',
    htmlSrc: path.join(REPO_ROOT, 'examples', 'uix', 'counter_uix', 'index.html'),
    outDir: path.join(OUT_ROOT, 'counter-uix'),
  },
  {
    build: 'examples/login-uix',
    htmlSrc: path.join(REPO_ROOT, 'examples', 'uix', 'login_uix', 'index.html'),
    outDir: path.join(OUT_ROOT, 'login-uix'),
  },
  // Helix adapter smoke trio (counter + login, realworld skipped —
  // the eight UIx decisions transfer unchanged). Different folders
  // from the canonical Reagent and UIx versions so the
  // bundle-isolation grep can confirm Helix code does NOT appear
  // in Reagent / UIx-substrate examples and vice versa. The Helix
  // tree lives under examples/helix/.
  {
    build: 'examples/counter-helix',
    htmlSrc: path.join(REPO_ROOT, 'examples', 'helix', 'counter_helix', 'index.html'),
    outDir: path.join(OUT_ROOT, 'counter-helix'),
  },
  {
    build: 'examples/login-helix',
    htmlSrc: path.join(REPO_ROOT, 'examples', 'helix', 'login_helix', 'index.html'),
    outDir: path.join(OUT_ROOT, 'login-helix'),
  },
  {
    build: 'examples/realworld',
    htmlSrc: path.join(REPO_ROOT, 'examples', 'reagent', 'realworld', 'index.html'),
    outDir: path.join(OUT_ROOT, 'realworld'),
  },
  // Pattern-Boot example (rf2-dsm2). The :app/boot state machine
  // owns the application's initialisation graph; the example's
  // four mocked endpoints are served entirely by an in-process
  // :rf.http/managed canned-success override (no static files
  // needed).
  {
    build: 'examples/boot',
    htmlSrc: path.join(REPO_ROOT, 'examples', 'reagent', 'boot', 'index.html'),
    outDir: path.join(OUT_ROOT, 'boot'),
  },
  // Runnable companion to docs/guide/08-state-machines.md.
  {
    build: 'examples/state-machine-walkthrough',
    htmlSrc: path.join(REPO_ROOT, 'examples', 'reagent', 'state_machine_walkthrough', 'index.html'),
    outDir: path.join(OUT_ROOT, 'state-machine-walkthrough'),
  },
  // SSR + hydration walkthrough.
  {
    build: 'examples/ssr',
    htmlSrc: path.join(REPO_ROOT, 'examples', 'reagent', 'ssr', 'index.html'),
    outDir: path.join(OUT_ROOT, 'ssr'),
  },
  // Story Stage 8 — the canonical counter app with the
  // seven `reg-*` Story macros wired up. URL-hash-routed: `#/`
  // renders the live counter; `#/stories` mounts the Story shell.
  // rf2-p8f2s — testbed-shaped (33× canonical counter); now lives
  // under tools/story/testbeds/counter_with_stories/.
  {
    build: 'examples/counter-with-stories',
    htmlSrc: path.join(REPO_ROOT, 'tools', 'story', 'testbeds', 'counter_with_stories', 'index.html'),
    outDir: path.join(OUT_ROOT, 'counter-with-stories'),
  },
  // Pattern-LongRunningWork worked example (rf2-o9fg) —
  // :invoke-all spawn-and-join with progress reporting and a
  // cooperative cancellation cascade on parent-unmount.
  {
    build: 'examples/long-running-work',
    htmlSrc: path.join(REPO_ROOT, 'examples', 'reagent', 'long_running_work', 'index.html'),
    outDir: path.join(OUT_ROOT, 'long-running-work'),
  },
  // Pattern-WebSocket worked example (rf2-yf97). Connection machine
  // with hierarchical :active, :invoke'd socket actor, :after backoff,
  // :always queue-flush, :fsm/tags, request-reply correlation, and
  // connection-epoch staleness. The transport is an in-process mock
  // WebSocket so the example is standalone — no network needed.
  {
    build: 'examples/websocket',
    htmlSrc: path.join(REPO_ROOT, 'examples', 'reagent', 'websocket', 'index.html'),
    outDir: path.join(OUT_ROOT, 'websocket'),
  },
  // rf2-ik4io — SSR hydration baseline. Static index.html bakes
  // pre-rendered HTML + a :rf/hydration-payload script; the browser-
  // side run reads the payload, dispatches :rf/hydrate, and calls
  // verify-hydration!. The companion spec.cjs lives at
  // testbeds/ssr_basic/spec.cjs.
  //
  // These three SSR testbeds are top-level `testbeds/<surface>/`
  // siblings of `examples/<substrate>/` (per the testbeds split,
  // rf2-96nb3); the shadow-cljs build id uses the `testbeds/` prefix
  // and lands the bundle under `implementation/out/testbeds/<id>/`.
  // The orchestrator serves http://127.0.0.1:8030/<id>/ from OUT_ROOT
  // (out/examples/), so we redirect outDir into the served root with
  // a `testbed-` prefix to keep the URL path unambiguous.
  {
    build: 'testbeds/ssr-basic',
    htmlSrc: path.join(REPO_ROOT, 'testbeds', 'ssr_basic', 'index.html'),
    outDir: path.join(OUT_ROOT, 'testbed-ssr-basic'),
  },
  // rf2-ik4io — SSR hydration-mismatch surface. The payload bakes a
  // deliberately wrong :rf/render-hash so verify-hydration! emits
  // :rf.ssr/hydration-mismatch with :recovery :warned-and-replaced.
  {
    build: 'testbeds/ssr-hydration-mismatch',
    htmlSrc: path.join(REPO_ROOT, 'testbeds', 'ssr_hydration_mismatch', 'index.html'),
    outDir: path.join(OUT_ROOT, 'testbed-ssr-hydration-mismatch'),
  },
  // rf2-ik4io — SSR multi-frame surface. Three frames each hydrate
  // independently from their own payload slice; the three panels
  // prove per-frame state restoration through the hydration
  // boundary.
  {
    build: 'testbeds/ssr-multi-frame',
    htmlSrc: path.join(REPO_ROOT, 'testbeds', 'ssr_multi_frame', 'index.html'),
    outDir: path.join(OUT_ROOT, 'testbed-ssr-multi-frame'),
  },
];

function compileAll() {
  const isWin = process.platform === 'win32';
  const cmd = isWin ? 'npx.cmd' : 'npx';
  // Compile every build in one shadow-cljs invocation — faster: it
  // shares the JVM warmup across builds.  Silent-on-success
  // (rf2-try1x): shadow-cljs's own status lines flow through; that
  // output is build-tool, not test-runner, and is out of scope here.
  const args = ['shadow-cljs', 'compile', ...EXAMPLES.map((e) => e.build)];
  const result = spawnSync(cmd, args, {
    cwd: IMPL_ROOT,
    stdio: 'inherit',
    shell: isWin,
  });
  if (result.status !== 0) {
    console.error(`> ${cmd} ${args.join(' ')}`);
    throw new Error(`shadow-cljs compile failed (exit ${result.status})`);
  }
}

function stageHtml() {
  // Silent-on-success (rf2-try1x): per-file staging notices are
  // suppressed.  Errors still throw with the offending path.
  for (const ex of EXAMPLES) {
    if (!fs.existsSync(ex.outDir)) {
      throw new Error(`Build output dir missing: ${ex.outDir}`);
    }
    if (!fs.existsSync(ex.htmlSrc)) {
      throw new Error(`HTML source missing: ${ex.htmlSrc}`);
    }
    const dest = path.join(ex.outDir, 'index.html');
    fs.copyFileSync(ex.htmlSrc, dest);

    for (const extra of ex.extraFiles || []) {
      if (!fs.existsSync(extra.src)) {
        throw new Error(`Static asset missing: ${extra.src}`);
      }
      const assetDest = path.join(ex.outDir, extra.dest);
      fs.mkdirSync(path.dirname(assetDest), { recursive: true });
      fs.copyFileSync(extra.src, assetDest);
    }
  }
}

function probe(port) {
  return new Promise((resolve) => {
    const req = http.get(
      { host: '127.0.0.1', port, path: '/', timeout: 1000 },
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

async function waitForReady(port, deadline) {
  while (Date.now() < deadline) {
    if (await probe(port)) return true;
    await new Promise((r) => setTimeout(r, POLL_MS));
  }
  return false;
}

(async () => {
  compileAll();
  stageHtml();

  const server = spawn(process.execPath, [HTTP_SERVER_BIN, OUT_ROOT, '-p', String(PORT), '-s', '-c-1'], {
    cwd: IMPL_ROOT,
    stdio: ['ignore', 'inherit', 'inherit'],
  });

  let serverDown = false;
  server.on('exit', (code, signal) => {
    serverDown = true;
    if (code !== 0 && code !== null) {
      console.error(`http-server exited unexpectedly (code=${code}, signal=${signal}).`);
    }
  });

  const ready = await waitForReady(PORT, Date.now() + READY_TIMEOUT_MS);
  if (!ready || serverDown) {
    console.error(`http-server did not become reachable on :${PORT} within ${READY_TIMEOUT_MS}ms.`);
    if (!serverDown) server.kill();
    process.exit(1);
  }

  const runner = spawn(process.execPath, [RUNNER], {
    stdio: 'inherit',
    env: { ...process.env, EXAMPLES_BASE_URL: `http://127.0.0.1:${PORT}` },
  });

  const code = await new Promise((resolve) => runner.on('exit', resolve));

  if (!serverDown) {
    server.kill();
  }
  process.exit(code == null ? 1 : code);
})().catch((err) => {
  console.error(err);
  process.exit(1);
});
