#!/usr/bin/env node
/*
 * Orchestrator for the examples Playwright suite (rf2-lyj0).
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
const IMPL_ROOT = path.resolve(__dirname, '..');
const REPO_ROOT = path.resolve(IMPL_ROOT, '..');
const OUT_ROOT = path.join(IMPL_ROOT, 'out', 'examples');
const RUNNER = path.resolve(__dirname, 'run-examples-tests.cjs');
const HTTP_SERVER_BIN = require.resolve('http-server/bin/http-server');
const READY_TIMEOUT_MS = 30000;
const POLL_MS = 200;

// Each example: shadow-cljs build id, the html source to stage, and the
// directory under out/examples it lands in. The url-path is what the
// Playwright spec navigates to under http://127.0.0.1:PORT/.
const EXAMPLES = [
  {
    build: 'examples/counter',
    htmlSrc: path.join(REPO_ROOT, 'examples', 'counter', 'index.html'),
    outDir: path.join(OUT_ROOT, 'counter'),
  },
  {
    build: 'examples/temperature',
    htmlSrc: path.join(REPO_ROOT, 'examples', 'seven_guis', 'temperature.html'),
    outDir: path.join(OUT_ROOT, 'temperature'),
  },
  {
    build: 'examples/flight-booker',
    htmlSrc: path.join(REPO_ROOT, 'examples', 'seven_guis', 'flight_booker.html'),
    outDir: path.join(OUT_ROOT, 'flight-booker'),
  },
  {
    build: 'examples/timer',
    htmlSrc: path.join(REPO_ROOT, 'examples', 'seven_guis', 'timer.html'),
    outDir: path.join(OUT_ROOT, 'timer'),
  },
  {
    build: 'examples/nine-states',
    htmlSrc: path.join(REPO_ROOT, 'examples', 'nine_states', 'index.html'),
    outDir: path.join(OUT_ROOT, 'nine-states'),
  },
  {
    build: 'examples/routing',
    htmlSrc: path.join(REPO_ROOT, 'examples', 'routing', 'index.html'),
    outDir: path.join(OUT_ROOT, 'routing'),
  },
  {
    build: 'examples/todomvc',
    htmlSrc: path.join(REPO_ROOT, 'examples', 'todomvc', 'index.html'),
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
  // Spec 014 — managed HTTP counter (rf2-cfig). Stages a static
  // /api/inc.json for the +1 button to fetch; a missing /api/does-not-exist
  // path naturally produces a 404 from http-server for the Fail button.
  {
    build: 'examples/managed-http-counter',
    htmlSrc: path.join(REPO_ROOT, 'examples', 'managed_http_counter', 'index.html'),
    outDir: path.join(OUT_ROOT, 'managed-http-counter'),
    extraFiles: [
      {
        src: path.join(REPO_ROOT, 'examples', 'managed_http_counter', 'api', 'inc.json'),
        dest: path.join('api', 'inc.json'),
      },
    ],
  },
  // Phase 3 — rf2-w3vn.
  {
    build: 'examples/crud',
    htmlSrc: path.join(REPO_ROOT, 'examples', 'seven_guis', 'crud.html'),
    outDir: path.join(OUT_ROOT, 'crud'),
  },
  {
    build: 'examples/circle-drawer',
    htmlSrc: path.join(REPO_ROOT, 'examples', 'seven_guis', 'circle_drawer.html'),
    outDir: path.join(OUT_ROOT, 'circle-drawer'),
  },
  {
    build: 'examples/cells',
    htmlSrc: path.join(REPO_ROOT, 'examples', 'seven_guis', 'cells.html'),
    outDir: path.join(OUT_ROOT, 'cells'),
  },
  {
    build: 'examples/login',
    htmlSrc: path.join(REPO_ROOT, 'examples', 'login', 'index.html'),
    outDir: path.join(OUT_ROOT, 'login'),
  },
  {
    build: 'examples/realworld',
    htmlSrc: path.join(REPO_ROOT, 'examples', 'realworld', 'index.html'),
    outDir: path.join(OUT_ROOT, 'realworld'),
  },
  // rf2-vq2s — runnable companion to docs/guide/05-state-machines.md.
  {
    build: 'examples/state-machine-walkthrough',
    htmlSrc: path.join(REPO_ROOT, 'examples', 'state_machine_walkthrough', 'index.html'),
    outDir: path.join(OUT_ROOT, 'state-machine-walkthrough'),
  },
  // rf2-vq2s — SSR + hydration walkthrough.
  {
    build: 'examples/ssr',
    htmlSrc: path.join(REPO_ROOT, 'examples', 'ssr', 'index.html'),
    outDir: path.join(OUT_ROOT, 'ssr'),
  },
];

function compileAll() {
  const isWin = process.platform === 'win32';
  const cmd = isWin ? 'npx.cmd' : 'npx';
  // Compile every build in one shadow-cljs invocation — faster: it
  // shares the JVM warmup across builds.
  const args = ['shadow-cljs', 'compile', ...EXAMPLES.map((e) => e.build)];
  console.log(`> ${cmd} ${args.join(' ')}`);
  const result = spawnSync(cmd, args, {
    cwd: IMPL_ROOT,
    stdio: 'inherit',
    shell: isWin,
  });
  if (result.status !== 0) {
    throw new Error(`shadow-cljs compile failed (exit ${result.status})`);
  }
}

function stageHtml() {
  for (const ex of EXAMPLES) {
    if (!fs.existsSync(ex.outDir)) {
      throw new Error(`Build output dir missing: ${ex.outDir}`);
    }
    if (!fs.existsSync(ex.htmlSrc)) {
      throw new Error(`HTML source missing: ${ex.htmlSrc}`);
    }
    const dest = path.join(ex.outDir, 'index.html');
    fs.copyFileSync(ex.htmlSrc, dest);
    console.log(`Staged ${ex.htmlSrc} -> ${dest}`);

    for (const extra of ex.extraFiles || []) {
      if (!fs.existsSync(extra.src)) {
        throw new Error(`Static asset missing: ${extra.src}`);
      }
      const assetDest = path.join(ex.outDir, extra.dest);
      fs.mkdirSync(path.dirname(assetDest), { recursive: true });
      fs.copyFileSync(extra.src, assetDest);
      console.log(`Staged ${extra.src} -> ${assetDest}`);
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
