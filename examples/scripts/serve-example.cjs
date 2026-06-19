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
 *   2. Applies the clean-stage boundary to the selected output dir (remove +
 *      recreate it empty, path-guarded under out/examples), then stages
 *      `index.html` + the `examples/_shared/` tree into it — the SAME
 *      clean-then-stage contract the adapter-smoke / Story orchestrators use,
 *      reused from examples-staging.cjs, not re-implemented. Serving from a
 *      freshly cleaned dir means no stale prior bundle/asset is ever served as
 *      a fresh run (rf2-rg2tze).
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
const { stageExample, listStandaloneExamples, cleanStageDirs } = require('./examples-staging.cjs');
const {
  createHarnessCleanup,
  spawnHarnessProcess,
  waitForHttpReady,
} = require('../../implementation/scripts/lib/local-browser-harness.cjs');

// __dirname is <repo>/examples/scripts. REPO_ROOT is <repo>; IMPL_ROOT is
// where shadow-cljs runs and node_modules lives.
const REPO_ROOT = path.resolve(__dirname, '..', '..');
const IMPL_ROOT = path.join(REPO_ROOT, 'implementation');
// The shared staging root every example build emits under
// (`:output-dir "out/examples/<name>"`). It is the clean-stage boundary's
// owned root: cleanStageDirs path-guards each selected outDir to live strictly
// under it, so a clean can never touch the shared root or a sibling output the
// same root holds. Mirrors OUT_ROOT in serve-and-run-examples-tests.cjs.
const OUT_ROOT = path.join(IMPL_ROOT, 'out', 'examples');
const READY_TIMEOUT_MS = 30000;

// Decide the dev-runner's process exit code from the observed child outcomes
// (rf2-35lfqo). PURE — no I/O, no process state — so it is unit-testable in
// isolation. The runner long-runs until the user interrupts it (Ctrl+C), so a
// shutdown the USER asked for is success; an UNEXPECTED child crash is a
// failure the runner must surface with a non-zero exit (the prior code always
// returned 0, false-greening a `shadow-cljs watch` that died on a compile/JVM
// error or an http-server that fell over). Each child outcome is a
// `{ code, signal }` record (a process 'exit' event's args) or null if that
// child was never started (e.g. no watch in --no-watch mode).
//
//   - `interrupted` true  => the user stopped the runner (SIGINT/SIGTERM). A
//     child exiting via the SAME terminating signal is part of that teardown,
//     not a crash — success.
//   - a child that exited cleanly (code 0, or code null because it was killed
//     by our own teardown signal) is not a failure.
//   - any other child exit — a non-zero code, or a signal kill that was NOT our
//     teardown — is an unexpected crash; the runner exits non-zero.
//
// Returns 0 on clean shutdown, 1 on any unexpected child failure.
function decideRunnerExit({ server = null, watch = null, interrupted = false } = {}) {
  const teardownSignals = new Set(['SIGINT', 'SIGTERM']);
  const childFailed = (outcome) => {
    if (!outcome) return false; // child never started — nothing to judge
    const { code = null, signal = null } = outcome;
    if (typeof code === 'number') return code !== 0; // explicit exit code wins
    if (signal != null) {
      // Killed by a signal. Our own teardown (or a user Ctrl+C that propagated)
      // is expected; any other signal kill is a crash.
      return !(interrupted && teardownSignals.has(signal));
    }
    return false; // code null + no signal => clean exit
  };
  return childFailed(server) || childFailed(watch) ? 1 : 0;
}

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

  // Clean-stage boundary (rf2-bf4vdy / rf2-rg2tze): remove + recreate the
  // SELECTED build's output dir BEFORE any compile or staging, so every served
  // file is produced from the CURRENT source this run — exactly the contract
  // the CI/Story orchestrators already honour (serve-and-run-examples-tests.cjs
  // cleanSelectedOutDirs, the Story load/play runners). Without this the dev
  // runner overlaid index.html + _shared onto whatever a PRIOR run left behind,
  // so a stale main.js (or a retired asset the manifest no longer produces)
  // stayed serveable — and in watch mode the browser could render that old
  // bundle while the runner had already printed a live URL, masking an initial
  // compile that had not yet landed (or had failed). Cleaning first makes a
  // missing bundle VISIBLY absent until the current watch's first compile lands
  // — no stale prior main.js is ever advertised or served as a fresh run. We
  // clean ONLY this dir (never the shared OUT_ROOT) so sibling outputs another
  // run/harness relies on survive; the helper path-guards the target to live
  // strictly under OUT_ROOT.
  cleanStageDirs([entry.outDir], OUT_ROOT);

  // For `--no-watch` we compile once up front (CI-shaped: the output dir is
  // fully built into the freshly cleaned dir before staging + serving). For the
  // default watch mode the watch process below produces main.js into the clean
  // dir, so we stage immediately and the first compile lands in place — until
  // it does, the cleaned dir has no main.js, so http-server cannot serve a
  // stale bundle.
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

  // Stage index.html + the _shared design-system tree into the (now clean)
  // output dir. The output dir already exists (cleanStageDirs recreated it),
  // so the page's assets are present from the start.
  stageExample(entry);

  // Record the observed child outcomes so the runner's own exit code reflects
  // an unexpected crash rather than always returning 0 (rf2-35lfqo). `interrupted`
  // flips true once a teardown signal lands so a signal-killed child during
  // Ctrl+C is read as an expected shutdown, not a crash.
  let interrupted = false;
  for (const sig of ['SIGINT', 'SIGTERM']) {
    process.once(sig, () => { interrupted = true; });
  }
  let watchOutcome = null;
  let serverOutcome = null;
  // Tracks server reachability so a child crash aborts the readiness wait.
  // Declared here (before the watch handler) so the watch-exit listener can
  // flip it; the server-exit listener below also sets it.
  let serverDown = false;

  if (watch) {
    const watchProc = cleanup.trackProcess(
      spawnHarnessProcess(process.execPath, [shadowRunner, 'watch', entry.build], {
        cwd: IMPL_ROOT,
        stdio: 'inherit',
      }),
    );
    // A `shadow-cljs watch` that dies unexpectedly (compile loop crash, JVM
    // OOM, killed) must NOT leave the runner happily serving a now-frozen
    // bundle and exiting 0. Record its outcome, abort the readiness wait, and
    // tear the tree down (stopping http-server, which resolves the main wait)
    // so the runner returns non-zero via decideRunnerExit.
    watchProc.on('exit', (code, signal) => {
      watchOutcome = { code, signal };
      if (!interrupted && !(typeof code === 'number' && code === 0)) {
        console.error(`shadow-cljs watch exited unexpectedly (code=${code}, signal=${signal}).`);
        serverDown = true; // abort the readiness wait below if still pending
        // Stop http-server too; the watch is the source of truth for a live
        // build. cleanup() is idempotent and safe to call alongside the
        // signal handlers.
        cleanup.cleanup().catch(() => {});
      }
    });
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

  server.on('exit', (code, signal) => {
    serverDown = true;
    serverOutcome = { code, signal };
    if (code !== 0 && code !== null && !interrupted) {
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

  // The watch + server run until interrupted; resolve when the server exits
  // (Ctrl+C is handled by the installed signal handlers, which tear the whole
  // process tree down). In watch mode an unexpected watch crash also tears the
  // server down (see the watch 'exit' handler), so this resolves either way.
  await new Promise((resolve) => {
    if (serverDown) return resolve(); // already exited before we awaited
    server.on('exit', resolve);
  });

  // Surface an unexpected child crash as a non-zero runner exit rather than the
  // unconditional 0 the runner used to return (rf2-35lfqo).
  return decideRunnerExit({ server: serverOutcome, watch: watchOutcome, interrupted });
}

// Only launch the runner when executed directly (`node serve-example.cjs …`).
// When required as a module (the decideRunnerExit unit test), DON'T run main()
// — just expose the pure helpers.
if (require.main === module) {
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
}

// The cleanup handle is created inside main(); for the top-level catch we
// fall back to a plain exit (the in-main signal handlers already cover the
// tracked-process teardown on Ctrl+C, and a throw before they're installed
// has no children to sweep).
async function cleanupAndExit(code) {
  process.exit(code == null ? 1 : code);
}

module.exports = { decideRunnerExit, parseArgs };
