#!/usr/bin/env node
/*
 * Orchestrator for the adapter-smoke Playwright suite (npm run
 * test:examples). The `examples/` tree is TEST-FREE — this
 * orchestrator compiles + stages only the surfaces paired with a
 * `spec.cjs` under the runner's SPEC_ROOTS, which is just the three
 * adapter smokes at implementation/adapters/<name>/testbed/.
 *
 * Framework + top-level testbed assertions live as CLJS/JVM unit
 * tests. The testbed surfaces themselves (tools/xray/testbeds/** and
 * top-level testbeds/**) stay in-tree as Xray observation targets;
 * they're not staged by this orchestrator.
 *
 * 1. Compiles each surface's shadow-cljs build (one per smoke).
 * 2. Stages each surface's hand-written index.html into its
 *    out/examples/<name>/ directory next to main.js.
 * 3. Resolves a free port (default 8050 — in the examples orchestrator's
 *    owned 805x band, clear of the top-level :dev-http bands; the resolver
 *    still pre-flights + scans forward — see examples-port.cjs for the
 *    policy and the OWNED-RANGE PORT MAP in
 *    implementation/scripts/dev-testbed.cjs) and spawns http-server over
 *    out/examples on 127.0.0.1:<port>.
 * 4. Waits for it to be reachable, then runs the Playwright runner
 *    (run-examples-tests.cjs).
 * 5. Always tears the server down.
 *
 * Build list, mount paths, and HTML sources are declared in EXAMPLES
 * below. Adding a new smoke: append an entry here ONLY when a
 * matching spec.cjs exists under SPEC_ROOTS; never stage a surface
 * that nothing tests.
 *
 * Cross-platform: compile via npx, but launch http-server directly from the
 * local package so teardown kills the real server process on Windows too.
 */

const { spawnSync } = require('child_process');
const fs = require('fs');
const path = require('path');
const { resolveExamplesPort } = require('./examples-port.cjs');
const {
  EXAMPLES,
  parseFilterPatterns,
  selectEntries,
} = require('./examples-filter.cjs');
const {
  createHarnessCleanup,
  spawnHarnessProcess,
  waitForHttpReady,
} = require('../../implementation/scripts/lib/local-browser-harness.cjs');

// Narrow filter. When set, only the EXAMPLES entries the filter selects
// are compiled + staged, and the value is propagated to the Playwright
// runner via the `EXAMPLES_FILTER` env-var so the runner executes exactly
// the same selected set's specs. Unset (or empty) = the full sweep. The
// filter is supplied via either:
//
//   1. CLI flag (cross-platform; the recommended shape):
//      node serve-and-run-examples-tests.cjs --filter adapters
//
//   2. Env var (for CI / scripted use):
//      EXAMPLES_FILTER=adapters node serve-and-run-examples-tests.cjs
//
// Multi-pattern filter: comma separates alternatives, OR-matched. The
// single CI invocation today (adapter-testbed-smokes) passes
// `adapters/` to scope the runner to the 3 adapter smokes.
//
// Selection is shared with the runner via examples-filter.cjs's
// `selectEntries`, which matches each pattern against an entry's
// shadow-cljs build id (`adapters/<name>-testbed`) AND its paired
// spec.cjs path in one canonical separator space. That means build-id
// shapes (`adapters/reagent-testbed`, `reagent-testbed`) and path shapes
// (`adapters/reagent/testbed`, `reagent/testbed`) select the SAME entries
// in both the compile/stage phase here and the spec-run phase in the
// runner (rf2-l72e2 — previously a build-id-shaped filter staged the
// surface then matched zero specs). Per Spec 008-Testing §Test surfaces
// — this is the changed-surface CI tier for adapter-mount regressions
// (the nightly / release rigorous gate remains separate).
function parseFilterFromArgs(argv) {
  // Accept `--filter <value>` or `--filter=<value>`. Ignore unknown
  // flags so future additions don't break — the orchestrator has no
  // other CLI args today.
  for (let i = 2; i < argv.length; i++) {
    const a = argv[i];
    if (a === '--filter') return (argv[i + 1] || '').trim();
    if (a.startsWith('--filter=')) return a.slice('--filter='.length).trim();
  }
  return '';
}
const FILTER = parseFilterFromArgs(process.argv)
            || (process.env.EXAMPLES_FILTER || '').trim();
// Split a comma-separated filter into the list of substrings. Empty
// filter returns an empty array (meaning "pass-through everything").
// Shared with the runner via examples-filter.cjs so the two phases parse
// the filter identically.
const FILTER_PATTERNS = parseFilterPatterns(FILTER);
// Port resolution lives in examples-port.cjs (resolveExamplesPort, called
// at the top of main()). Default is 8050 — in the examples orchestrator's
// owned 805x band, clear of the top-level :dev-http bands (see the
// OWNED-RANGE PORT MAP in implementation/scripts/dev-testbed.cjs).
// `EXAMPLES_PORT` overrides the default; when unset the resolver scans
// forward from 8050 to the next free port, and when set-but-busy it throws
// an actionable message (no raw EACCES stack). No CLI surface is added.
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
const cleanup = createHarnessCleanup();
cleanup.installSignalHandlers();

// The example set (shadow-cljs build id + HTML source + output dir +
// paired spec.cjs path) is declared ONCE in examples-filter.cjs and
// imported as EXAMPLES above. Policy reminder: the `examples/` tree is
// TEST-FREE; every entry pairs a build with an existing spec.cjs (never
// stage a surface nothing tests). Real regressions are caught by
// substrate contract tests, the Xray feature-matrix gate,
// bundle-isolation, the perf-bundle gate, and mcp-conformance.

// Selection is delegated to the shared `selectEntries`, which matches the
// filter against each entry's build id AND its spec path in one canonical
// separator space — so the orchestrator's compile/stage set is identical
// to the runner's spec set for any filter shape (rf2-l72e2). The same
// selection gates compile and stage so a narrow run never spins up
// resources for excluded surfaces.
function selectedExamples() {
  return selectEntries(FILTER_PATTERNS);
}

function compileAll() {
  const isWin = process.platform === 'win32';
  const cmd = isWin ? 'npx.cmd' : 'npx';
  // Compile every build in one shadow-cljs invocation — faster: it
  // shares the JVM warmup across builds. Silent-on-success: shadow-
  // cljs's own status lines flow through; that output is build-tool,
  // not test-runner, and is out of scope here.
  const builds = selectedExamples().map((e) => e.build);
  if (builds.length === 0) {
    throw new Error(
      `EXAMPLES_FILTER='${FILTER}' matched zero builds; nothing to compile.`,
    );
  }
  const args = ['shadow-cljs', 'compile', ...builds];
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

function copyDirRecursive(src, dest) {
  // Minimal recursive copy. Used by `stageShared` below to fan the
  // `examples/_shared/` design-system tree into every staged smoke
  // dir. Each file overwrites the destination unconditionally so
  // repeat runs pick up re-compiled bundles.
  fs.mkdirSync(dest, { recursive: true });
  for (const entry of fs.readdirSync(src, { withFileTypes: true })) {
    const s = path.join(src, entry.name);
    const d = path.join(dest, entry.name);
    if (entry.isDirectory()) {
      copyDirRecursive(s, d);
    } else if (entry.isFile()) {
      fs.copyFileSync(s, d);
    }
  }
}

// Shared examples design system: one stylesheet + favicon + OG image
// across all three substrates. The hand-written index.html files
// reference these via relative paths like `_shared/css/style.css`, so
// the orchestrator stages the `examples/_shared/` tree into every
// example's output dir alongside main.js + index.html. Single source
// on disk; one copy step per build (cheap — a handful of small files).
const SHARED_SRC = path.join(REPO_ROOT, 'examples', '_shared');

function stageShared(outDir) {
  if (!fs.existsSync(SHARED_SRC)) return;
  copyDirRecursive(SHARED_SRC, path.join(outDir, '_shared'));
}

function stageHtml() {
  // Silent-on-success: per-file staging notices are suppressed.
  // Errors still throw with the offending path.
  // EXAMPLES_FILTER: only stage selected entries.
  for (const ex of selectedExamples()) {
    if (!fs.existsSync(ex.outDir)) {
      throw new Error(`Build output dir missing: ${ex.outDir}`);
    }
    if (!fs.existsSync(ex.htmlSrc)) {
      throw new Error(`HTML source missing: ${ex.htmlSrc}`);
    }
    const dest = path.join(ex.outDir, 'index.html');
    fs.copyFileSync(ex.htmlSrc, dest);

    // Stage examples/_shared/ alongside index.html so the hand-written
    // page can <link>/<img> assets at the same relative path on every
    // example build.
    stageShared(ex.outDir);

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

async function main() {
  // Pre-flight the port before any compile work: the resolver binds-and-
  // releases on 127.0.0.1, picks the next free port from 8050 when
  // EXAMPLES_PORT is unset, and throws an actionable message (caught by
  // the bottom .catch, which prints err.message + exits 1) when an
  // explicit EXAMPLES_PORT is busy. Resolving first means a port clash
  // fails fast with a clear message instead of after a slow shadow build.
  const PORT = await resolveExamplesPort({ env: process.env });

  compileAll();
  stageHtml();

  // Bind 127.0.0.1 (not 0.0.0.0): the Playwright specs only ever hit
  // localhost, and the loopback-only bind sidesteps the Windows
  // dual-stack EACCES surprise that made the old 8030 clash cryptic.
  const server = cleanup.trackProcess(spawnHarnessProcess(process.execPath, [HTTP_SERVER_BIN, OUT_ROOT, '-a', '127.0.0.1', '-p', String(PORT), '-s', '-c-1'], {
    cwd: IMPL_ROOT,
    stdio: ['ignore', 'inherit', 'inherit'],
  }));

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

  const runner = cleanup.trackProcess(spawnHarnessProcess(process.execPath, [RUNNER], {
    stdio: 'inherit',
    env: {
      ...process.env,
      EXAMPLES_BASE_URL:    `http://127.0.0.1:${PORT}`,
      // Propagate the orchestrator's filter (CLI or env) to the runner
      // so spec-file selection matches the build/stage narrowing.
      // Empty = full sweep, which matches the runner's unset-env
      // default.
      EXAMPLES_FILTER:       FILTER,
    },
  }));

  const code = await new Promise((resolve) => runner.on('exit', resolve));

  return code == null ? 1 : code;
}

main().then(async (code) => {
  await cleanup.cleanup();
  process.exit(code);
}).catch(async (err) => {
  // Actionable errors (e.g. a port clash from resolveExamplesPort) carry
  // a fully-formed user-facing message — print just that, no raw stack.
  // Everything else prints in full for debugging.
  if (err && err.actionable) {
    console.error(err.message);
  } else {
    console.error(err);
  }
  await cleanup.cleanup();
  process.exit(1);
});
