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
  createHarnessCleanup,
  spawnHarnessProcess,
  waitForHttpReady,
} = require('../../implementation/scripts/lib/local-browser-harness.cjs');

// Narrow filter. When set, only the EXAMPLES entries whose `build` id
// includes any one of the comma-separated substrings are compiled +
// staged, and the value is propagated to the Playwright runner via
// the `EXAMPLES_FILTER` env-var so the runner only executes matching
// specs. Unset (or empty) = the full sweep. The filter is supplied
// via either:
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
// The filter is substring-matched against the shadow-cljs build id
// (`adapters/<name>-testbed`), giving a single knob that composes
// with both the orchestrator's compile/stage loop and the runner's
// spec walker. Per Spec 008-Testing §Test surfaces — this is the
// changed-surface CI tier for adapter-mount regressions (the nightly
// / release rigorous gate remains separate).
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
// filter returns an empty array (meaning "pass-through everything"
// — see `selectedExample` below).
function parseFilterPatterns(raw) {
  if (!raw) return [];
  return raw.split(',').map((s) => s.trim()).filter((s) => s.length > 0);
}
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

// Each entry: shadow-cljs build id, the html source to stage, and the
// directory under out/examples it lands in. The url-path is what the
// Playwright spec navigates to under http://127.0.0.1:PORT/.
//
// Policy: the `examples/` tree is TEST-FREE. The orchestrator drives
// the three adapter smokes (Reagent / UIx / Helix); every build below
// is paired with a `spec.cjs`; never add a build here whose only
// purpose is "compile + stage with no spec to drive it" (that's dead
// CI weight).
//
// Real regressions are caught by substrate contract tests, the Xray
// feature-matrix gate, bundle-isolation, the perf-bundle gate, and
// mcp-conformance — not by per-example or per-testbed Playwright
// specs. The testbed surfaces themselves (`tools/xray/testbeds/**` +
// `testbeds/**`) stay in-tree as Xray observation targets; the build
// targets stay in `implementation/shadow-cljs.edn` but no `EXAMPLES`
// row references them here.
const EXAMPLES = [
  // ----- Adapter smokes (3 of 3) ----------------------------------------
  // Per-adapter end-to-end smoke. Each adapter (Reagent / UIx / Helix)
  // ships a standalone counter under
  // implementation/adapters/<name>/testbed/ that proves the adapter
  // wires up end-to-end (mount, subscribe, dispatch, re-render). The
  // shadow build emits straight into out/examples/adapter-testbeds/<name>/,
  // so the HTML is staged alongside main.js and the static server picks
  // it up under /adapter-testbeds/<name>/. The companion spec.cjs sits
  // beside core.cljs + index.html and is discovered via SPEC_ROOTS in
  // run-examples-tests.cjs (which scans implementation/adapters/).
  {
    build: 'adapters/reagent-testbed',
    htmlSrc: path.join(REPO_ROOT, 'implementation', 'adapters', 'reagent', 'testbed', 'index.html'),
    outDir: path.join(OUT_ROOT, 'adapter-testbeds', 'reagent'),
  },
  {
    build: 'adapters/uix-testbed',
    htmlSrc: path.join(REPO_ROOT, 'implementation', 'adapters', 'uix', 'testbed', 'index.html'),
    outDir: path.join(OUT_ROOT, 'adapter-testbeds', 'uix'),
  },
  {
    build: 'adapters/helix-testbed',
    htmlSrc: path.join(REPO_ROOT, 'implementation', 'adapters', 'helix', 'testbed', 'index.html'),
    outDir: path.join(OUT_ROOT, 'adapter-testbeds', 'helix'),
  },
];

// Substring-match a build id against the filter. Empty filter =
// pass-through. The same predicate gates compile and stage so a
// narrow run never spins up resources for excluded surfaces.
//
// `normalizeForFilter` collapses kebab/snake and `\`/`/` cosmetic
// variants on both sides before substring-matching. Build ids already
// use kebab-case so this is a no-op for typical filters; the gain is
// symmetry with `run-examples-tests.cjs` (which sees
// snake_case-bearing spec paths). Keep the predicate in lockstep so a
// single EXAMPLES_FILTER value gates compile, stage, and spec runner
// identically. See the runner for the substring-trap rationale.
function normalizeForFilter(s) {
  return s.replace(/_/g, '-').replace(/\\/g, '/');
}
function selectedExample(ex) {
  if (FILTER_PATTERNS.length === 0) return true;
  const normalized = normalizeForFilter(ex.build);
  return FILTER_PATTERNS.some((p) => normalized.includes(normalizeForFilter(p)));
}

function selectedExamples() {
  return EXAMPLES.filter(selectedExample);
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
