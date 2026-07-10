#!/usr/bin/env node
/*
 * Orchestrator: spawn http-server over out/browser-test on a port,
 * wait for it to be reachable, then run the Playwright runner.
 * Always tear the server down (success or failure).
 *
 * Why this wrapper exists: the bead suggested a shell one-liner of
 * `http-server ... & sleep 2 && node ...`. That works on POSIX but not
 * on Windows, and `sleep 2` is a brittle race. This script is the same
 * idea but cross-platform and with a real readiness probe.
 *
 * Port selection (rf2-nuv7):
 *   1. If $BROWSER_TEST_PORT is set, try that port. If it's busy, fall
 *      back to a free port chosen by the OS.
 *   2. If unset, default to 8021 (historical behaviour). If 8021 is
 *      busy, fall back to a free port chosen by the OS.
 *   3. Either way, log clearly which port was used and thread it
 *      through to the Playwright runner via $BROWSER_TEST_URL.
 *
 * Early-exit detection: listen for the http-server child's `'exit'`
 * event during the readiness window. If it dies before becoming
 * reachable (typically EADDRINUSE), fail fast with a direct message
 * instead of waiting out the full readiness timeout.
 */

const fs = require('fs');
const path = require('path');
const { enforcePolicy, DEFAULT_OUT_ROOT } = require('./_path-policy.cjs');
const {
  TOKEN_FILE_BASENAME,
  createHarnessCleanup,
  isValidExplicitPort,
  publishOwnershipToken,
  resolveServePort,
  spawnHarnessProcess,
  waitForOwnedHttpReady,
} = require('./lib/local-browser-harness.cjs');

// Strict CLI options (rf2-hmgwk2). The two production browser gates
// (test:browser-prod-elision, test:browser-schemas-boundary-prod) call this
// shared runner DIRECTLY with `--root <dir> --port <n>` rather than routing
// through a per-gate wrapper that only set the BROWSER_TEST_ROOT/PORT env
// vars. CLI options take precedence over those env vars, which in turn take
// precedence over the historical defaults — so env-var and default behaviour
// are preserved when no CLI option is given. Parsing is strict: an unknown
// flag, a flag missing its value, or a `--port` that is not a 1..65535
// integer fails fast with a clear message rather than being silently coerced
// or ignored. A CLI `--root` is routed through the SAME path policy as the
// env var below (it cannot bypass the approved-roots check).
function parseCliOptions(argv) {
  const opts = { root: null, port: null };
  for (let i = 0; i < argv.length; i += 1) {
    const arg = argv[i];
    let flag = arg;
    let value = null;
    if (arg.startsWith('--')) {
      const eq = arg.indexOf('=');
      if (eq !== -1) {
        flag = arg.slice(0, eq);
        value = arg.slice(eq + 1);
      }
    }
    if (flag !== '--root' && flag !== '--port') {
      throw new Error(
        `Unknown option ${JSON.stringify(arg)}. Supported: --root <dir>, ` +
          `--port <n> (env: BROWSER_TEST_ROOT, BROWSER_TEST_PORT).`,
      );
    }
    // Read the value from either the `--flag=value` form or the next token.
    if (value === null) {
      if (i + 1 >= argv.length) {
        throw new Error(`${flag} requires a value (e.g. \`${flag} <value>\`).`);
      }
      value = argv[i + 1];
      i += 1;
    }
    if (flag === '--root') {
      if (value === '') throw new Error('--root requires a non-empty value.');
      opts.root = value;
    } else {
      const n = Number(value);
      if (!isValidExplicitPort(n)) {
        throw new Error(
          `--port must be an integer in 1..65535 (got ${JSON.stringify(value)}).`,
        );
      }
      opts.port = n;
    }
  }
  return opts;
}

let CLI;
try {
  CLI = parseCliOptions(process.argv.slice(2));
} catch (err) {
  console.error(`serve-and-run-browser-tests: ${err.message}`);
  process.exit(1);
}

const DEFAULT_PORT = 8021;
// `--root` (or $BROWSER_TEST_ROOT) lets a caller point the orchestrator at a
// different shadow-cljs :browser-test output directory — used by the two
// prod-mode gates (Spec 009/010 §Production builds, rf2-2zdu / rf2-uwg5 /
// rf2-r2uh / rf2-84e9), whose `:advanced + goog.DEBUG=false` bundles land in
// `out/browser-test-prod-elision/` and `out/browser-test-schemas-boundary-prod/`
// rather than the default `out/browser-test/`.
//
// Per rf2-o38lb (security audit): the override MUST land inside
// `implementation/out` unless `RE_FRAME_ALLOW_OUT_OF_TREE_PATHS=1` is
// set in the environment. The orchestrator writes `${ROOT}/index.html`
// and `${ROOT}/.rf-harness-token`; an unconstrained override would otherwise
// become an arbitrary file-write primitive in CI or downstream environments
// inheriting parent env state. The CLI `--root` (rf2-hmgwk2) routes through
// this SAME enforcePolicy check — a CLI root cannot bypass the path policy.
const ROOT_OVERRIDE = CLI.root || process.env.BROWSER_TEST_ROOT;
const ROOT = enforcePolicy(
  CLI.root ? '--root' : 'BROWSER_TEST_ROOT',
  ROOT_OVERRIDE || path.resolve(__dirname, '..', 'out', 'browser-test'),
  { allowedRoots: [DEFAULT_OUT_ROOT] },
);
const INDEX = path.join(ROOT, 'index.html');
const RUNNER = path.resolve(__dirname, 'run-browser-tests.cjs');
// Resolve http-server's own JS entry-point so we can spawn it under THIS
// `node` binary (`process.execPath`) with `shell:false` — never `npx`/
// `npx.cmd` under a shell (rf2-wn4o1). Spawning the resolved `.js` under
// `process.execPath` sidesteps both the Windows command-hijack accident
// class (a workspace-local `npx.cmd` resolving ahead of PATH when
// `shell:true` + a repo-controlled `cwd` are combined — rf2-33vvc) and
// the `.cmd`-under-no-shell `EINVAL` that the CVE-2024-27980 mitigation
// introduced. Same shell-free posture dev-testbed.cjs uses for
// shadow-cljs and serve-and-run-xray-feature-gate.cjs uses for
// http-server. Resolution is rooted at IMPL_ROOT (the implementation's
// local install) regardless of this script's own cwd.
const IMPL_ROOT = path.resolve(__dirname, '..');
let HTTP_SERVER_BIN;
try {
  HTTP_SERVER_BIN = require.resolve('http-server/bin/http-server', {
    paths: [IMPL_ROOT],
  });
} catch {
  console.error(
    'serve-and-run-browser-tests: could not resolve http-server. Run ' +
      `\`npm install\` in ${IMPL_ROOT} first.`,
  );
  process.exit(1);
}
const READY_TIMEOUT_MS = 30000;
const POLL_MS = 200;
const cleanup = createHarnessCleanup();
cleanup.installSignalHandlers();

// shadow-cljs's :browser-test target generates an index.html with an empty
// <body>. Some example namespaces (e.g. examples/patterns/nine_states/core.cljs)
// historically did `(rdc/create-root (js/document.getElementById "app"))` at
// namespace-load time. Per rf2-gkf9 the example mounts now defer `create-root`
// to their `run` fn, but the test harness still needs a single `#app` host so
// a future regression doesn't crash the runner before cljs.test prints its
// summary. Patch the generated index.html to include a hidden mount point.
// Idempotent.
function ensureMountPoint() {
  if (!fs.existsSync(INDEX)) return;
  const html = fs.readFileSync(INDEX, 'utf8');
  if (html.includes('id="app"')) return;
  const patched = html.replace(
    '<body>',
    '<body><div id="app" style="display:none"></div>'
  );
  if (patched !== html) {
    fs.writeFileSync(INDEX, patched, 'utf8');
    console.log(`Patched ${INDEX} with <div id="app"> mount point.`);
  }
}

// Server ownership token (rf2-gkf9). The readiness probe and the teardown
// path both verify that the server reachable on `port` is the one this
// orchestrator spawned:
//
//   1. publishOwnershipToken(ROOT) generates a per-run nonce and writes it
//      to a sentinel file under the served root BEFORE spawning http-server
//      (so the file is published the moment http-server serves the dir).
//   2. The readiness probe fetches `/.rf-harness-token` and compares the
//      body to the nonce — only then do we treat the server as "ours" and
//      proceed to the Playwright runner.
//   3. On teardown the returned `remove` unlinks the sentinel, but only if
//      it still holds THIS run's token (so an overlapping run's newer token
//      is never destroyed).
//
// This positively defeats two failure modes:
//   - An unrelated http-server (or any HTTP listener) on the same port
//     gives a 200 to `/` but does NOT have our sentinel — we fail fast
//     instead of running tests against the wrong asset tree.
//   - A stale http-server child from a previous aborted run that re-bound
//     the port between resolveServePort() and our spawn — same detection:
//     its sentinel won't match this run's nonce.
//
// The entire token lifecycle (nonce generation + write + concurrency-safe,
// idempotent cleanup) plus the probe/token-fetch/owned-readiness mechanics
// live in the shared local-browser-harness.cjs (publishOwnershipToken +
// waitForOwnedHttpReady + fetchToken); this script only owns the launcher
// diagnostics.

// Resolve the port to use via the shared harness primitive
// (local-browser-harness.cjs), honouring `--port` first, then
// $BROWSER_TEST_PORT, then the historical default 8021, then a free
// OS-chosen port. The shared `resolveServePort` carries the strict 1..65535
// explicit-port contract and the busy-port fallback (rf2-0u8kz / rf2-84gzw);
// this wrapper keeps the launcher-specific diagnostics (distinguishing an
// explicitly-set but unusable BROWSER_TEST_PORT from a busy default). A CLI
// `--port` is already validated to be a usable 1..65535 integer by
// parseCliOptions, so the only fallback it can trigger is a busy port.
async function resolvePort() {
  const cliSet = CLI.port != null;
  const envRaw = process.env.BROWSER_TEST_PORT;
  const envSet = !cliSet && !!(envRaw && envRaw.trim() !== '');
  const preferred = cliSet
    ? CLI.port
    : envSet ? parseInt(envRaw, 10) : DEFAULT_PORT;
  return await resolveServePort(preferred, {
    onFallback: (pref, fallback) => {
      if (cliSet) {
        console.warn(
          `--port ${pref} is busy; falling back to free port ${fallback}.`
        );
      } else if (envSet && !isValidExplicitPort(pref)) {
        console.error(
          `BROWSER_TEST_PORT="${envRaw}" is not a valid TCP port (want 1..65535); ` +
            `ignoring and using free port ${fallback}.`
        );
      } else if (envSet) {
        console.warn(
          `BROWSER_TEST_PORT=${pref} is busy; falling back to free port ${fallback}.`
        );
      } else {
        console.warn(
          `Default port ${DEFAULT_PORT} is busy; falling back to free port ${fallback}. ` +
            `Set BROWSER_TEST_PORT to pin a specific port.`
        );
      }
    },
  });
}

(async () => {
  ensureMountPoint();

  // Publish the per-run ownership token (rf2-gkf9) before spawning
  // http-server so the file is visible the moment the server starts
  // serving the directory. The returned `remove` (idempotent, only unlinks
  // our own token) is registered for teardown here rather than at module
  // load because it isn't known until the token is written.
  const published = publishOwnershipToken(ROOT);
  if (!published) {
    console.error(`Asset root missing: ${ROOT}. Did shadow-cljs compile run?`);
    process.exit(1);
  }
  const { token, remove } = published;
  cleanup.addCleanup(remove);

  const port = await resolvePort();
  console.log(`Serving ${ROOT} on http://127.0.0.1:${port}`);

  // Spawn http-server shell-free under this node binary (rf2-wn4o1): the
  // resolved absolute `.js` entry-point is the only thing the OS
  // interprets, so a workspace-local `npx.cmd` can no longer hijack the
  // launch, and there is no `shell:true` warning/quoting class.
  // Bind 127.0.0.1 (not http-server's 0.0.0.0 default): the readiness
  // probe and the browser only ever hit loopback, so the listener must
  // not be exposed on non-loopback interfaces during a test run
  // (rf2-utvst; matches serve-and-run-adapter-smokes.cjs).
  const args = [HTTP_SERVER_BIN, ROOT, '-a', '127.0.0.1', '-p', String(port), '-s', '-c-1'];
  const server = cleanup.trackProcess(spawnHarnessProcess(process.execPath, args, {
    cwd: IMPL_ROOT,
    stdio: ['ignore', 'inherit', 'inherit'],
  }));

  // Track the server's lifecycle so the readiness loop can fail fast
  // if http-server dies before becoming reachable. With pre-binding via
  // resolveServePort() this should be rare, but a slow-to-release socket
  // or a race against a sibling spawner can still trigger EADDRINUSE.
  const state = { exited: false, exitCode: null, exitSignal: null };
  server.on('exit', (code, signal) => {
    state.exited = true;
    state.exitCode = code;
    state.exitSignal = signal;
  });

  // Readiness WITH ownership-token verification via the shared harness
  // primitive (rf2-84gzw / rf2-gkf9): refuse to run tests against any
  // server on `port` that does not serve this run's token.
  const ready = await waitForOwnedHttpReady(port, token, Date.now() + READY_TIMEOUT_MS, {
    pollMs: POLL_MS,
    isAborted: () => state.exited,
  });
  if (!ready.ok) {
    if (ready.reason === 'child-exited' || state.exited) {
      console.error(
        `http-server exited before becoming reachable on :${port} ` +
          `(code=${state.exitCode}, signal=${state.exitSignal}). ` +
          `Likely cause: port already in use or http-server failed to start.`
      );
    } else if (ready.reason === 'token-mismatch') {
      console.error(
        `A server is reachable on :${port}, but its /${TOKEN_FILE_BASENAME} ` +
          `does not match this run's ownership token. Refusing to run tests ` +
          `against a server this harness did not launch. ` +
          `(got "${ready.got}", expected "${token}"). ` +
          `Set BROWSER_TEST_PORT to pin a different port if this is intentional.`
      );
    } else if (ready.reason === 'token-never-served') {
      console.error(
        `http-server on :${port} became reachable but never served ` +
          `/${TOKEN_FILE_BASENAME} within ${READY_TIMEOUT_MS}ms. ` +
          `Asset root may be inconsistent.`
      );
    } else {
      console.error(
        `http-server did not become reachable on :${port} within ${READY_TIMEOUT_MS}ms.`
      );
    }
    return 1;
  }

  const runner = cleanup.trackProcess(spawnHarnessProcess(process.execPath, [RUNNER], {
    stdio: 'inherit',
    env: { ...process.env, BROWSER_TEST_URL: `http://127.0.0.1:${port}` },
  }));

  // Resolve on either 'exit' or 'error'. A spawn failure (e.g. the runner
  // binary cannot be launched) emits 'error' and NOT 'exit'; without the
  // 'error' arm this promise would hang until the whole run is killed.
  const code = await new Promise((resolve) => {
    runner.once('exit', (exitCode) => resolve(exitCode));
    runner.once('error', (err) => {
      console.error(`Failed to launch test runner: ${err && err.message ? err.message : err}`);
      resolve(1);
    });
  });

  return code == null ? 1 : code;
})().then(async (code) => {
  await cleanup.cleanup();
  process.exit(code);
}).catch(async (err) => {
  console.error(err);
  await cleanup.cleanup();
  process.exit(1);
});
