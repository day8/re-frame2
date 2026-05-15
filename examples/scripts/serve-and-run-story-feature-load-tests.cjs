#!/usr/bin/env node
/*
 * Narrow orchestrator for the occasional Story feature/load gate.
 *
 * Compiles only the Story testbeds needed by
 * tools/story/test/story_feature_load.cjs, stages their HTML files,
 * serves implementation/out/examples, and invokes the quiet runner.
 */

const { spawnSync } = require('child_process');
const fs = require('fs');
const path = require('path');
const { resolveStoryFeatureLoadPort } = require('./story-feature-load-port.cjs');
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

function compileAll() {
  const isWin = process.platform === 'win32';
  const npx = isWin ? 'npx.cmd' : 'npx';
  const shadowArgs = ['shadow-cljs', 'compile', ...TESTBEDS.map((t) => t.build)];
  const command = isWin ? 'cmd.exe' : npx;
  const args = isWin
    ? ['/d', '/s', '/c', [npx, ...shadowArgs].join(' ')]
    : shadowArgs;
  const result = spawnSync(command, args, {
    cwd: IMPL_ROOT,
    encoding: 'utf8',
  });
  const output = `${result.stdout || ''}${result.stderr || ''}`;
  if (process.env.RF2_VERBOSE_TESTS === '1' || result.status !== 0) {
    process.stdout.write(output);
  }
  if (result.status !== 0) {
    console.error(`> ${npx} ${shadowArgs.join(' ')}`);
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
  compileAll();
  stageHtml();

  const server = cleanup.trackProcess(spawnHarnessProcess(process.execPath, [HTTP_SERVER_BIN, OUT_ROOT, '-p', String(port), '-s', '-c-1'], {
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
