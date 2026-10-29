#!/usr/bin/env node
/*
 * Orchestrator for the adapter-smoke Playwright suite (npm run
 * test:adapter-smokes; implementation/adapters/TESTING.md).
 *
 * 1. Compiles each selected smoke's shadow-cljs build.
 * 2. Stages its hand-written index.html next to main.js in its
 *    out/examples/adapter-testbeds/<name>/ directory.
 * 3. Resolves a free port (default 8050; see examples-port.cjs and the
 *    OWNED-RANGE PORT MAP in implementation/scripts/dev-testbed.cjs) and
 *    serves out/examples on 127.0.0.1:<port>.
 * 4. Runs the Playwright runner (run-adapter-smokes.cjs) against it.
 * 5. Always tears the server down.
 *
 * The smoke set comes from ADAPTER_SMOKES in adapter-smoke-filter.cjs.
 *
 * Cross-platform: shadow-cljs and http-server both run shell-free under THIS
 * node binary (process.execPath), so teardown kills the real server process on
 * Windows too. Never npx/npx.cmd under a shell.
 */

const path = require('path');
// The port resolver and the testbed preparation are shared with the example
// dev runner and the Story launchers, which is why they live in the examples
// tree.
const { resolveExamplesPort } = require('../../../examples/scripts/examples-port.cjs');
const { prepareTestbeds } = require('../../../examples/scripts/examples-staging.cjs');
const { parseFilterPatterns, selectEntries } = require('./adapter-smoke-filter.cjs');
const {
  createHarnessCleanup,
  spawnHarnessProcess,
  startLocalHttpServer,
} = require('../../scripts/lib/local-browser-harness.cjs');

// The filter narrows the run to the smokes whose build id contains any of its
// comma-separated patterns (`--filter adapters/`, `--filter reagent`); unset or
// empty runs every smoke. It is passed to the runner, so both phases select
// the same set.
function parseFilterFromArgs(argv) {
  // `--filter <value>` or `--filter=<value>`; other flags are ignored.
  for (let i = 2; i < argv.length; i++) {
    const a = argv[i];
    if (a === '--filter') return (argv[i + 1] || '').trim();
    if (a.startsWith('--filter=')) return a.slice('--filter='.length).trim();
  }
  return '';
}
const FILTER = parseFilterFromArgs(process.argv)
            || (process.env.ADAPTER_SMOKE_FILTER || '').trim();
const FILTER_PATTERNS = parseFilterPatterns(FILTER);
// __dirname is <repo>/implementation/adapters/scripts. IMPL_ROOT is
// <repo>/implementation (where shadow-cljs runs and node_modules lives).
const REPO_ROOT = path.resolve(__dirname, '..', '..', '..');
const IMPL_ROOT = path.join(REPO_ROOT, 'implementation');
const OUT_ROOT = path.join(IMPL_ROOT, 'out', 'examples');
const RUNNER = path.resolve(__dirname, 'run-adapter-smokes.cjs');
const HTTP_SERVER_BIN = require.resolve('http-server/bin/http-server', {
  paths: [IMPL_ROOT],
});
const READY_TIMEOUT_MS = 30000;
const cleanup = createHarnessCleanup();
cleanup.installSignalHandlers();

function selectedSmokes() {
  return selectEntries(FILTER_PATTERNS);
}

// Clean, compile and stage the selected smokes (examples-staging.cjs
// prepareTestbeds). Only the selected output dirs are cleaned, so a narrow run
// leaves its siblings alone.
function prepareSelectedSmokes() {
  const smokes = selectedSmokes();
  if (smokes.length === 0) {
    throw new Error(
      `ADAPTER_SMOKE_FILTER='${FILTER}' matched zero builds; nothing to compile.`,
    );
  }
  prepareTestbeds(smokes, { outRoot: OUT_ROOT });
}

async function main() {
  // Resolve the port before compiling, so a clash fails fast with an
  // actionable message instead of after a slow build.
  const PORT = await resolveExamplesPort({ env: process.env });

  prepareSelectedSmokes();

  // Loopback only: the specs only hit localhost, and binding 127.0.0.1
  // sidesteps the Windows dual-stack EACCES surprise.
  const { ready } = await startLocalHttpServer({
    cleanup,
    httpServerBin: HTTP_SERVER_BIN,
    root: OUT_ROOT,
    port: PORT,
    cwd: IMPL_ROOT,
    readyTimeoutMs: READY_TIMEOUT_MS,
  });
  if (!ready) return 1;

  const runner = cleanup.trackProcess(spawnHarnessProcess(process.execPath, [RUNNER], {
    stdio: 'inherit',
    env: {
      ...process.env,
      ADAPTER_SMOKE_BASE_URL: `http://127.0.0.1:${PORT}`,
      ADAPTER_SMOKE_FILTER:   FILTER,
    },
  }));

  const code = await new Promise((resolve) => runner.on('exit', resolve));

  return code == null ? 1 : code;
}

main().then(async (code) => {
  await cleanup.cleanup();
  process.exit(code);
}).catch(async (err) => {
  // Actionable errors (a port clash from resolveExamplesPort) carry a
  // user-facing message; print just that. Everything else prints in full.
  if (err && err.actionable) {
    console.error(err.message);
  } else {
    console.error(err);
  }
  await cleanup.cleanup();
  process.exit(1);
});
