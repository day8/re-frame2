#!/usr/bin/env node
/*
 * Narrow orchestrator for the occasional Story feature/load gate.
 *
 * Compiles only the Story testbeds needed by
 * tools/story/test/story_feature_load.cjs, stages their HTML files,
 * serves implementation/out/examples, and invokes the quiet runner.
 */

const { spawn, spawnSync } = require('child_process');
const fs = require('fs');
const http = require('http');
const path = require('path');
const { resolveStoryFeatureLoadPort } = require('./story-feature-load-port.cjs');

const REPO_ROOT = path.resolve(__dirname, '..', '..');
const IMPL_ROOT = path.join(REPO_ROOT, 'implementation');
const OUT_ROOT = path.join(IMPL_ROOT, 'out', 'examples');
const RUNNER = path.resolve(__dirname, 'run-story-feature-load-tests.cjs');
const HTTP_SERVER_BIN = require.resolve('http-server/bin/http-server', {
  paths: [IMPL_ROOT],
});
const READY_TIMEOUT_MS = 30000;
const POLL_MS = 200;
const VERBOSE = process.env.RF2_VERBOSE_TESTS === '1';

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
    await new Promise((resolve) => setTimeout(resolve, POLL_MS));
  }
  return false;
}

(async () => {
  const PORT = await resolveStoryFeatureLoadPort({ env: process.env, repoRoot: REPO_ROOT });
  compileAll();
  stageHtml();

  const server = spawn(process.execPath, [HTTP_SERVER_BIN, OUT_ROOT, '-p', String(PORT), '-s', '-c-1'], {
    cwd: IMPL_ROOT,
    stdio: ['ignore', 'pipe', 'pipe'],
  });

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

  const ready = await waitForReady(PORT, Date.now() + READY_TIMEOUT_MS);
  if (!ready || serverDown) {
    console.error(`http-server did not become reachable on :${PORT} within ${READY_TIMEOUT_MS}ms.`);
    for (const line of serverOutput.slice(-40)) console.error(line);
    if (!serverDown) server.kill();
    process.exit(1);
  }

  const runner = spawn(process.execPath, [RUNNER], {
    stdio: 'inherit',
    env: {
      ...process.env,
      STORY_FEATURE_LOAD_BASE_URL: `http://127.0.0.1:${PORT}`,
    },
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
