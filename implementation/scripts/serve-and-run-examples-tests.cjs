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
 * Cross-platform: same npx shell discipline as serve-and-run-browser-tests.cjs.
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

  const isWin = process.platform === 'win32';
  const httpServerCmd = isWin ? 'npx.cmd' : 'npx';
  const args = ['http-server', OUT_ROOT, '-p', String(PORT), '-s', '-c-1'];
  const server = spawn(httpServerCmd, args, {
    stdio: ['ignore', 'inherit', 'inherit'],
    shell: isWin,
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
