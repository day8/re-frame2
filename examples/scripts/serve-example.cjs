#!/usr/bin/env node
/*
 * `serve-example` — one-command dev runner for a STANDALONE example build
 * (rf2-pdo5mx).
 *
 * The gap this closes
 * -------------------
 * A standalone example (e.g. `examples/counter-uix`) was only runnable via a
 * multi-step manual recipe: `shadow-cljs watch <build>`, then hand-copy the
 * example's `index.html` AND the `examples/_shared/` design-system tree next
 * to the emitted `main.js`, then serve the output dir yourself. Easy to get
 * wrong or stale — and a compile-only gate can stay green while the page a
 * beginner actually opens is missing its HTML or shared assets.
 *
 * This script collapses that recipe into ONE command. It:
 *
 *   1. Resolves the selected build's output dir + source folder by READING
 *      the build def from shadow-cljs.edn (its `:output-dir` + `:init-fn`),
 *      so there is no hardcoded build->folder table to drift. The example's
 *      hand-written `index.html` is the one colocated with the source file
 *      that declares the build's init-fn namespace.
 *   2. Stages `index.html` + the `examples/_shared/` tree into the output dir
 *      (the SAME staging the adapter-smoke orchestrator uses — reused from
 *      examples-staging.cjs, not re-implemented).
 *   3. Resolves a free port (reusing the examples port resolver) and serves
 *      the output dir over http-server on 127.0.0.1.
 *   4. Spawns `shadow-cljs watch <build>` so edits recompile live. A
 *      `--no-watch` flag runs a one-shot `compile` instead (CI-shaped).
 *
 * The shared staging + harness helpers do the hardened work (cross-platform
 * shell-free spawning, process-tree teardown, port pre-flight); this script
 * is just the dev-ergonomics front door.
 *
 * Policy preserved: `npm run test:examples` stays the adapter-smoke runner,
 * and standalone examples keep their compile coverage via
 * `test:examples-compile`. This is a DEV command, not a test gate — it adds
 * no `*.spec.cjs` under examples/ (the tree stays test-free, rf2-8cevm).
 *
 * CLI
 * ---
 *   node examples/scripts/serve-example.cjs --build examples/counter-uix
 *   node examples/scripts/serve-example.cjs examples/dashboard-uix   # positional
 *   node examples/scripts/serve-example.cjs --build examples/login-uix --no-watch
 *   node examples/scripts/serve-example.cjs --list                   # list buildable examples
 *
 * Via npm (from implementation/):
 *   npm run dev:example -- examples/counter-uix
 *
 * SPAWN FORM: resolve shadow-cljs's / http-server's own JS entry-points and
 * run them under THIS node binary (process.execPath), shell-free — never
 * `npx`/`npx.cmd` under a shell (rf2-y9o5e3). Same hardened posture as
 * serve-and-run-examples-tests.cjs.
 */

'use strict';

const fs = require('fs');
const path = require('path');
const { resolveExamplesPort } = require('./examples-port.cjs');
const { stageExample, listStandaloneExamples } = require('./examples-staging.cjs');
const {
  createHarnessCleanup,
  spawnHarnessProcess,
  waitForHttpReady,
} = require('../../implementation/scripts/lib/local-browser-harness.cjs');

// __dirname is <repo>/examples/scripts. REPO_ROOT is <repo>; IMPL_ROOT is
// where shadow-cljs runs and node_modules lives.
const REPO_ROOT = path.resolve(__dirname, '..', '..');
const IMPL_ROOT = path.join(REPO_ROOT, 'implementation');
const READY_TIMEOUT_MS = 30000;

function parseArgs(argv) {
  let build = '';
  let watch = true;
  let list = false;
  for (let i = 2; i < argv.length; i++) {
    const a = argv[i];
    if (a === '--list') list = true;
    else if (a === '--no-watch') watch = false;
    else if (a === '--watch') watch = true;
    else if (a === '--build') build = (argv[i + 1] || '').trim(), i++;
    else if (a.startsWith('--build=')) build = a.slice('--build='.length).trim();
    else if (!a.startsWith('--') && !build) build = a.trim();
  }
  // Normalise: accept `counter-uix` as shorthand for `examples/counter-uix`.
  if (build && !build.startsWith('examples/')) build = `examples/${build}`;
  return { build, watch, list };
}

function resolveShadowRunner() {
  try {
    return require.resolve('shadow-cljs/cli/runner.js', { paths: [IMPL_ROOT] });
  } catch {
    throw new Error(
      `serve-example: could not resolve shadow-cljs. Run \`npm install\` in ${IMPL_ROOT} first.`,
    );
  }
}

function resolveHttpServerBin() {
  return require.resolve('http-server/bin/http-server', { paths: [IMPL_ROOT] });
}

async function main() {
  const { build, watch, list } = parseArgs(process.argv);
  const examples = listStandaloneExamples();

  if (list) {
    console.log('serve-example: buildable standalone examples:');
    for (const e of examples) console.log(`  ${e.build}`);
    return 0;
  }

  if (!build) {
    console.error(
      'serve-example: no build selected. Pass one, e.g.\n' +
        '  node examples/scripts/serve-example.cjs --build examples/counter-uix\n' +
        '  npm run dev:example -- examples/counter-uix\n' +
        'Run with --list to see every buildable standalone example.',
    );
    return 1;
  }

  const entry = examples.find((e) => e.build === build);
  if (!entry) {
    console.error(
      `serve-example: '${build}' is not a known standalone example build.\n` +
        'Run with --list to see the available builds.',
    );
    return 1;
  }

  const cleanup = createHarnessCleanup();
  cleanup.installSignalHandlers();

  // Pre-flight the port before any compile work so a clash fails fast.
  const PORT = await resolveExamplesPort({ env: process.env });

  const shadowRunner = resolveShadowRunner();
  const httpServerBin = resolveHttpServerBin();

  // For `--no-watch` we compile once up front (CI-shaped: the output dir is
  // fully built before staging + serving). For the default watch mode the
  // watch process below produces main.js, so we stage immediately and the
  // first compile lands in place — http-server serves the freshly built file
  // the moment the watch finishes its initial compile.
  if (!watch) {
    const { spawnSync } = require('child_process');
    const result = spawnSync(process.execPath, [shadowRunner, 'compile', entry.build], {
      cwd: IMPL_ROOT,
      stdio: 'inherit',
    });
    if (result.status !== 0) {
      throw new Error(`shadow-cljs compile ${entry.build} failed (exit ${result.status})`);
    }
  }

  // Stage index.html + the _shared design-system tree into the output dir.
  // The output dir is created by the staging helper if the watch hasn't
  // emitted into it yet, so the page's assets are present from the start.
  stageExample(entry);

  if (watch) {
    cleanup.trackProcess(
      spawnHarnessProcess(process.execPath, [shadowRunner, 'watch', entry.build], {
        cwd: IMPL_ROOT,
        stdio: 'inherit',
      }),
    );
  }

  // Serve the example's output dir on 127.0.0.1 (loopback-only — the dev
  // browser hits localhost, and it sidesteps the Windows dual-stack EACCES
  // surprise). `-c-1` disables caching so a recompiled main.js is picked up
  // on reload.
  const server = cleanup.trackProcess(
    spawnHarnessProcess(
      process.execPath,
      [httpServerBin, entry.outDir, '-a', '127.0.0.1', '-p', String(PORT), '-s', '-c-1'],
      { cwd: IMPL_ROOT, stdio: ['ignore', 'inherit', 'inherit'] },
    ),
  );

  let serverDown = false;
  server.on('exit', (code, signal) => {
    serverDown = true;
    if (code !== 0 && code !== null) {
      console.error(`http-server exited unexpectedly (code=${code}, signal=${signal}).`);
    }
  });

  const ready = await waitForHttpReady(PORT, Date.now() + READY_TIMEOUT_MS, {
    isAborted: () => serverDown,
  });
  if (!ready || serverDown) {
    console.error(`http-server did not become reachable on :${PORT} within ${READY_TIMEOUT_MS}ms.`);
    return 1;
  }

  console.log(
    `\nserve-example: ${entry.build} is live at http://127.0.0.1:${PORT}/` +
      (watch ? '  (shadow-cljs watch running — edits recompile live)' : '  (one-shot compile)') +
      '\n  Press Ctrl+C to stop.\n',
  );

  // The watch + server run until interrupted; resolve only when the server
  // exits (Ctrl+C is handled by the installed signal handlers, which tear the
  // whole process tree down).
  if (watch) {
    await new Promise((resolve) => server.on('exit', resolve));
    return 0;
  }

  // --no-watch: keep serving until the server is stopped (Ctrl+C).
  await new Promise((resolve) => server.on('exit', resolve));
  return 0;
}

main()
  .then(async (code) => {
    await cleanupAndExit(code);
  })
  .catch(async (err) => {
    if (err && err.actionable) {
      console.error(err.message);
    } else {
      console.error(err && err.stack ? err.stack : err);
    }
    await cleanupAndExit(1);
  });

// The cleanup handle is created inside main(); for the top-level catch we
// fall back to a plain exit (the in-main signal handlers already cover the
// tracked-process teardown on Ctrl+C, and a throw before they're installed
// has no children to sweep).
async function cleanupAndExit(code) {
  process.exit(code == null ? 1 : code);
}
