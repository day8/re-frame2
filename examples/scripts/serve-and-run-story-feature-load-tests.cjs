#!/usr/bin/env node
/*
 * Narrow orchestrator for the occasional Story feature/load gate.
 *
 * Compiles only the Story testbeds (counter-with-stories + login-form)
 * needed by the quiet runner's specs — tools/story/test/
 * story_feature_load.cjs AND story_browser_scenarios.cjs — stages their
 * HTML files, serves implementation/out/examples, and invokes the
 * runner (run-story-feature-load-tests.cjs).
 */

const { spawnSync } = require('child_process');
const fs = require('fs');
const path = require('path');
const { resolveStoryFeatureLoadPort } = require('./story-feature-load-port.cjs');
const { cleanStageDirs } = require('./examples-staging.cjs');
const {
  createHarnessCleanup,
  spawnHarnessProcess,
  waitForHttpReady,
} = require('../../implementation/scripts/lib/local-browser-harness.cjs');

const REPO_ROOT = path.resolve(__dirname, '..', '..');
const IMPL_ROOT = path.join(REPO_ROOT, 'implementation');
const OUT_ROOT = path.join(IMPL_ROOT, 'out', 'examples');
const RUNNER = path.resolve(__dirname, 'run-story-feature-load-tests.cjs');
const HTTP_SERVER_BIN = require.resolve('http-server/bin/http-server', {
  paths: [IMPL_ROOT],
});
// Resolve shadow-cljs's JS entry-point so compileAll() spawns it
// shell-free under process.execPath — never npx/npx.cmd/cmd.exe (the
// Windows command-hijack accident class). rf2-y9o5e3; matches
// story-build.cjs / dev-testbed.cjs.
let SHADOW_CLJS_RUNNER;
try {
  SHADOW_CLJS_RUNNER = require.resolve('shadow-cljs/cli/runner.js', {
    paths: [IMPL_ROOT],
  });
} catch {
  throw new Error(
    'serve-and-run-story-feature-load-tests: could not resolve shadow-cljs. ' +
      `Run \`npm install\` in ${IMPL_ROOT} first.`,
  );
}
const READY_TIMEOUT_MS = 30000;
const VERBOSE = process.env.RF2_VERBOSE_TESTS === '1';
const cleanup = createHarnessCleanup();
cleanup.installSignalHandlers();

const TESTBEDS = [
  {
    build: 'examples/counter-with-stories',
    htmlSrc: path.join(REPO_ROOT, 'tools', 'story', 'testbeds', 'counter_with_stories', 'index.html'),
    outDir: path.join(OUT_ROOT, 'counter-with-stories'),
  },
  {
    build: 'examples/login-form',
    htmlSrc: path.join(REPO_ROOT, 'tools', 'story', 'testbeds', 'login_form', 'index.html'),
    outDir: path.join(OUT_ROOT, 'login-form'),
  },
];

// Clean-stage boundary (rf2-bf4vdy): remove + recreate each testbed's output
// dir BEFORE shadow-cljs compiles into it, so every served file is produced
// from the current source this run — no stale file from a previous run can
// satisfy a browser request the current testbed no longer produces. Cleans
// only these two dirs (not the shared OUT_ROOT); the helper path-guards every
// target to live strictly under OUT_ROOT.
function cleanTestbedOutDirs() {
  cleanStageDirs(TESTBEDS.map((t) => t.outDir), OUT_ROOT);
}

function compileAll() {
  const builds = TESTBEDS.map((t) => t.build);
  // Spawn the resolved shadow-cljs JS entry-point under THIS node binary,
  // shell-free (rf2-y9o5e3). Capture output and surface it only on
  // failure (or under RF2_VERBOSE_TESTS) — the quiet-on-success posture
  // this gate already kept.
  const args = [SHADOW_CLJS_RUNNER, 'compile', ...builds];
  const result = spawnSync(process.execPath, args, {
    cwd: IMPL_ROOT,
    encoding: 'utf8',
  });
  const output = `${result.stdout || ''}${result.stderr || ''}`;
  if (process.env.RF2_VERBOSE_TESTS === '1' || result.status !== 0) {
    process.stdout.write(output);
  }
  if (result.status !== 0) {
    console.error(`> shadow-cljs compile ${builds.join(' ')}`);
    throw new Error(`shadow-cljs compile failed (exit ${result.status})`);
  }
}

function stageHtml() {
  for (const testbed of TESTBEDS) {
    if (!fs.existsSync(testbed.outDir)) {
      throw new Error(`Build output dir missing: ${testbed.outDir}`);
    }
    if (!fs.existsSync(testbed.htmlSrc)) {
      throw new Error(`HTML source missing: ${testbed.htmlSrc}`);
    }
    fs.copyFileSync(testbed.htmlSrc, path.join(testbed.outDir, 'index.html'));
  }
}

async function main() {
  const port = await resolveStoryFeatureLoadPort({ env: process.env, repoRoot: REPO_ROOT });
  cleanTestbedOutDirs();
  compileAll();
  stageHtml();

  // Bind 127.0.0.1 (not the http-server 0.0.0.0 default): the runner
  // and resolveStoryFeatureLoadPort only ever touch loopback, so binding
  // every interface is avoidable local-network surface. Matches the
  // adapter-smoke orchestrator (serve-and-run-adapter-smokes.cjs).
  // rf2-wf5al(2).
  const server = cleanup.trackProcess(spawnHarnessProcess(process.execPath, [HTTP_SERVER_BIN, OUT_ROOT, '-a', '127.0.0.1', '-p', String(port), '-s', '-c-1'], {
    cwd: IMPL_ROOT,
    stdio: ['ignore', 'pipe', 'pipe'],
  }));

  let serverDown = false;
  const serverOutput = [];
  const captureServerOutput = (chunk, stream) => {
    const text = chunk.toString();
    serverOutput.push(...text.split(/\r?\n/).filter(Boolean).map((line) => `[http-server:${stream}] ${line}`));
    if (VERBOSE) process[stream].write(text);
  };
  server.stdout.on('data', (chunk) => captureServerOutput(chunk, 'stdout'));
  server.stderr.on('data', (chunk) => captureServerOutput(chunk, 'stderr'));
  server.on('exit', (code, signal) => {
    serverDown = true;
    if (code !== 0 && code !== null) {
      console.error(`http-server exited unexpectedly (code=${code}, signal=${signal}).`);
    }
  });

  const ready = await waitForHttpReady(port, Date.now() + READY_TIMEOUT_MS, {
    isAborted: () => serverDown,
  });
  if (!ready || serverDown) {
    console.error(`http-server did not become reachable on :${port} within ${READY_TIMEOUT_MS}ms.`);
    for (const line of serverOutput.slice(-40)) console.error(line);
    return 1;
  }

  const runner = cleanup.trackProcess(spawnHarnessProcess(process.execPath, [RUNNER], {
    stdio: 'inherit',
    env: {
      ...process.env,
      STORY_FEATURE_LOAD_BASE_URL: `http://127.0.0.1:${port}`,
    },
  }));

  const code = await new Promise((resolve) => runner.on('exit', resolve));

  return code == null ? 1 : code;
}

main().then(async (code) => {
  await cleanup.cleanup();
  process.exit(code);
}).catch(async (err) => {
  console.error(err);
  await cleanup.cleanup();
  process.exit(1);
});
