#!/usr/bin/env node
/*
 * Orchestrator for the adapter-smoke + framework-testbed Playwright
 * suite (npm run test:examples). Per rf2-8cevm (Mike directive
 * 2026-05-19) the `examples/` tree is TEST-FREE — this orchestrator
 * compiles + stages only the surfaces paired with a `spec.cjs` under
 * the runner's SPEC_ROOTS (adapter smokes at
 * implementation/adapters/<name>/testbed/, framework testbeds at
 * tools/causa/testbeds/, and the top-level testbeds/ tree).
 *
 * 1. Compiles each surface's shadow-cljs build (one per testbed).
 * 2. Stages each surface's hand-written index.html into its
 *    out/examples/<name>/ directory next to main.js.
 * 3. Spawns http-server over out/examples on port 8030.
 * 4. Waits for it to be reachable, then runs the Playwright runner
 *    (run-examples-tests.cjs).
 * 5. Always tears the server down.
 *
 * Build list, mount paths, and HTML sources are declared in EXAMPLES
 * below. Adding a new testbed: append an entry here ONLY when a
 * matching spec.cjs exists under SPEC_ROOTS; never stage a surface
 * that nothing tests.
 *
 * Cross-platform: compile via npx, but launch http-server directly from the
 * local package so teardown kills the real server process on Windows too.
 */

const { spawnSync } = require('child_process');
const fs = require('fs');
const path = require('path');
const {
  createHarnessCleanup,
  spawnHarnessProcess,
  waitForHttpReady,
} = require('../../implementation/scripts/lib/local-browser-harness.cjs');

// rf2-h9ut9 — narrow filter. When set, only the EXAMPLES entries
// whose `build` id includes the substring are compiled + staged, and
// the value is propagated to the Playwright runner via the
// `EXAMPLES_FILTER` env-var so the runner only executes matching
// specs. Unset (or empty) = the full sweep. The filter is supplied
// via either:
//
//   1. CLI flag (cross-platform; the recommended shape):
//      node serve-and-run-examples-tests.cjs --filter realworld
//
//   2. Env var (for CI / scripted use):
//      EXAMPLES_FILTER=realworld node serve-and-run-examples-tests.cjs
//
// The packaged narrow shortcut lives in implementation/package.json
// as `npm run test:examples:realworld`.
//
// The filter is substring-matched against the shadow-cljs build id
// (`examples/<name>` or `testbeds/<name>`), giving a single knob that
// composes with both the orchestrator's compile/stage loop and the
// runner's spec walker. Live-SSR JVM server bring-up is skipped when
// the filter excludes the `examples/ssr` build — keeps narrow runs
// fast (~5s vs ~25s cold). Per Spec 008-Testing §Test surfaces — this
// is the changed-surface CI tier for cross-artefact runtime changes
// (the full sweep remains the local / nightly / release rigorous gate).
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
// rf2-043cm — `EXAMPLES_PORT` env-var override defaults to 8030. Lets
// parallel workers / contended dev sessions (e.g. a long-running
// `shadow-cljs watch` on the parallel-frames testbed at 8030)
// retarget this orchestrator to a free port without editing the
// script. No CLI surface is added.
const PORT = Number(process.env.EXAMPLES_PORT || 8030);
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
// Policy (rf2-8cevm, Mike directive 2026-05-19): the `examples/` tree
// is TEST-FREE. The runner orchestrates the three adapter smokes
// (Reagent / UIx / Helix), the two framework testbeds owned by Causa
// (perf-live + parallel-frames), and the cross-cutting top-level
// `testbeds/` (rf2-ik4io SSR + rf2-fe84r framework-behaviour). Every
// build below is paired with a `spec.cjs`; never add a build here
// whose only purpose is "compile + stage with no spec to drive it"
// (that's dead CI weight). Per-example smoke coverage that previously
// lived under `examples/<substrate>/<name>/*.spec.cjs` has been
// permanently retired — real regressions are caught by substrate
// contract tests, the Causa feature-matrix gate, bundle-isolation,
// the perf-bundle gate, and mcp-conformance.
const EXAMPLES = [
  // ----- Adapter smokes (3 of 3) ----------------------------------------
  // rf2-eceuv — per-adapter end-to-end smoke. Each adapter (Reagent
  // / UIx / Helix) ships a standalone counter under
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

  // ----- Framework testbeds (2) -----------------------------------------
  // Performance-API instrumented variant of the counter (paired with
  // tools/causa/testbeds/perf_counter/spec.cjs). Asserts a real
  // dispatch through the perf-on bundle produces `rf:event:*` /
  // `rf:sub:*` / `rf:fx:*` / `rf:render:*` measure entries on
  // `performance.getEntriesByType` — the live counterpart to the
  // static perf-bundle grep gate at scripts/check-perf-bundle.cjs.
  {
    build: 'examples/counter-perf',
    htmlSrc: path.join(REPO_ROOT, 'tools', 'causa', 'testbeds', 'perf_counter', 'index.html'),
    outDir: path.join(OUT_ROOT, 'counter-perf'),
  },
  // Parallel-Frames testbed (rf2-m00rw) — THE canonical multi-frame
  // isolation demo. One app, mounted in TWO frames (:above + :below)
  // on ONE page with zero cross-frame coupling. Paired with
  // tools/causa/testbeds/parallel_frames/spec.cjs.
  {
    build: 'examples/parallel-frames',
    htmlSrc: path.join(REPO_ROOT, 'tools', 'causa', 'testbeds', 'parallel_frames', 'index.html'),
    outDir: path.join(OUT_ROOT, 'parallel-frames'),
  },

  // ----- SSR testbeds (rf2-ik4io) ---------------------------------------
  // Top-level `testbeds/<surface>/` siblings of `examples/<substrate>/`
  // (per the testbeds split, rf2-96nb3). The shadow-cljs build id uses
  // the `testbeds/` prefix and lands the bundle under
  // `implementation/out/testbeds/<id>/`. The orchestrator serves
  // http://127.0.0.1:8030/<id>/ from OUT_ROOT (out/examples/), so we
  // redirect outDir into the served root with a `testbed-` prefix to
  // keep the URL path unambiguous.
  {
    build: 'testbeds/ssr-basic',
    htmlSrc: path.join(REPO_ROOT, 'testbeds', 'ssr_basic', 'index.html'),
    outDir: path.join(OUT_ROOT, 'testbed-ssr-basic'),
  },
  {
    build: 'testbeds/ssr-hydration-mismatch',
    htmlSrc: path.join(REPO_ROOT, 'testbeds', 'ssr_hydration_mismatch', 'index.html'),
    outDir: path.join(OUT_ROOT, 'testbed-ssr-hydration-mismatch'),
  },
  {
    build: 'testbeds/ssr-multi-frame',
    htmlSrc: path.join(REPO_ROOT, 'testbeds', 'ssr_multi_frame', 'index.html'),
    outDir: path.join(OUT_ROOT, 'testbed-ssr-multi-frame'),
  },

  // ----- Cross-cutting framework testbeds (rf2-fe84r / rf2-kzcim) -------
  // Shared framework-behaviour testbed surfaces. These emit into
  // `out/testbeds/<id>/` and the orchestrator copies the bundle into
  // `OUT_ROOT/testbeds/<id>/` so http-server serves it under the same
  // origin as the adapter smokes. The `bundleSrc` field opts a build
  // into the bundle-copy step.
  {
    build: 'testbeds/deliberate-throw',
    htmlSrc: path.join(REPO_ROOT, 'testbeds', 'deliberate_throw', 'index.html'),
    bundleSrc: path.join(IMPL_ROOT, 'out', 'testbeds', 'deliberate-throw'),
    outDir: path.join(OUT_ROOT, 'testbeds', 'deliberate-throw'),
  },
  {
    build: 'testbeds/http-toggle',
    htmlSrc: path.join(REPO_ROOT, 'testbeds', 'http_toggle', 'index.html'),
    bundleSrc: path.join(IMPL_ROOT, 'out', 'testbeds', 'http-toggle'),
    outDir: path.join(OUT_ROOT, 'testbeds', 'http-toggle'),
    extraFiles: [
      {
        src: path.join(REPO_ROOT, 'testbeds', 'http_toggle', 'api', 'success.json'),
        dest: path.join('api', 'success.json'),
      },
    ],
  },
  {
    build: 'testbeds/deep-machine',
    htmlSrc: path.join(REPO_ROOT, 'testbeds', 'deep_machine', 'index.html'),
    bundleSrc: path.join(IMPL_ROOT, 'out', 'testbeds', 'deep-machine'),
    outDir: path.join(OUT_ROOT, 'testbeds', 'deep-machine'),
  },
  {
    build: 'testbeds/non-trivial-app-db',
    htmlSrc: path.join(REPO_ROOT, 'testbeds', 'non_trivial_app_db', 'index.html'),
    bundleSrc: path.join(IMPL_ROOT, 'out', 'testbeds', 'non-trivial-app-db'),
    outDir: path.join(OUT_ROOT, 'testbeds', 'non-trivial-app-db'),
  },
  {
    build: 'testbeds/long-flow-w-failure',
    htmlSrc: path.join(REPO_ROOT, 'testbeds', 'long_flow_w_failure', 'index.html'),
    bundleSrc: path.join(IMPL_ROOT, 'out', 'testbeds', 'long-flow-w-failure'),
    outDir: path.join(OUT_ROOT, 'testbeds', 'long-flow-w-failure'),
  },
  {
    build: 'testbeds/drain-depth-trigger',
    htmlSrc: path.join(REPO_ROOT, 'testbeds', 'drain_depth_trigger', 'index.html'),
    bundleSrc: path.join(IMPL_ROOT, 'out', 'testbeds', 'drain-depth-trigger'),
    outDir: path.join(OUT_ROOT, 'testbeds', 'drain-depth-trigger'),
  },
];

// rf2-h9ut9 — substring-match a build id against the filter. Empty
// filter = pass-through. The same predicate gates compile, stage, and
// the JVM live-SSR bring-up below so a narrow run never spins up
// resources for excluded surfaces.
//
// rf2-mpa3x — `normalizeForFilter` collapses kebab/snake and `\`/`/`
// cosmetic variants on both sides before substring-matching. Build ids
// already use kebab-case so this is a no-op for typical filters; the
// gain is symmetry with `run-examples-tests.cjs` (which sees
// snake_case-bearing spec paths). Keep the predicate in lockstep so a
// single EXAMPLES_FILTER value gates compile, stage, and spec runner
// identically. See the runner for the substring-trap rationale.
function normalizeForFilter(s) {
  return s.replace(/_/g, '-').replace(/\\/g, '/');
}
function selectedExample(ex) {
  if (FILTER === '') return true;
  return normalizeForFilter(ex.build).includes(normalizeForFilter(FILTER));
}

function selectedExamples() {
  return EXAMPLES.filter(selectedExample);
}

function compileAll() {
  const isWin = process.platform === 'win32';
  const cmd = isWin ? 'npx.cmd' : 'npx';
  // Compile every build in one shadow-cljs invocation — faster: it
  // shares the JVM warmup across builds.  Silent-on-success
  // (rf2-try1x): shadow-cljs's own status lines flow through; that
  // output is build-tool, not test-runner, and is out of scope here.
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

// rf2-1w76p — panel-gallery smokes opt-in. The two scripts under
// tools/causa/testbeds/panel_gallery/_smoke.cjs and
// _workspace_switch_smoke.cjs are stand-alone (each spawns its own
// http-server + Playwright) and don't fit the spec.cjs shape the
// runner walks. They're gated behind RF2_CAUSA_RUN_GALLERY_SMOKES=1
// (off in the fast PR loop, on in the rigorous browser job). When
// enabled we compile :testbeds/panel-gallery, stage its index.html
// next to the bundle, and invoke both smokes in sequence. A failure
// in either smoke fails the whole `npm run test:examples` run.
const GALLERY_SMOKES_ENABLED =
  process.env.RF2_CAUSA_RUN_GALLERY_SMOKES === '1';

function compileAndStagePanelGallery() {
  const isWin = process.platform === 'win32';
  const cmd = isWin ? 'npx.cmd' : 'npx';
  const args = ['shadow-cljs', 'compile', 'testbeds/panel-gallery'];
  const result = spawnSync(cmd, args, {
    cwd: IMPL_ROOT,
    stdio: 'inherit',
    shell: isWin,
  });
  if (result.status !== 0) {
    console.error(`> ${cmd} ${args.join(' ')}`);
    throw new Error(
      `shadow-cljs compile :testbeds/panel-gallery failed (exit ${result.status})`,
    );
  }
  // Stage index.html next to the compiled bundle. The smokes serve
  // implementation/out/testbeds/panel-gallery/ as the http-server
  // root and request `/index.html`, so the file must live there.
  const galleryHtmlSrc = path.join(
    REPO_ROOT,
    'tools', 'causa', 'testbeds', 'panel_gallery', 'index.html',
  );
  const galleryOutDir = path.join(
    IMPL_ROOT, 'out', 'testbeds', 'panel-gallery',
  );
  if (!fs.existsSync(galleryOutDir)) {
    throw new Error(`Panel-gallery bundle dir missing: ${galleryOutDir}`);
  }
  if (!fs.existsSync(galleryHtmlSrc)) {
    throw new Error(`Panel-gallery index.html missing: ${galleryHtmlSrc}`);
  }
  fs.copyFileSync(galleryHtmlSrc, path.join(galleryOutDir, 'index.html'));
}

function runPanelGallerySmokes() {
  const smokes = [
    path.join(REPO_ROOT, 'tools', 'causa', 'testbeds', 'panel_gallery', '_smoke.cjs'),
    path.join(REPO_ROOT, 'tools', 'causa', 'testbeds', 'panel_gallery', '_workspace_switch_smoke.cjs'),
  ];
  let anyFailed = false;
  for (const smoke of smokes) {
    console.log(`\n=== panel-gallery smoke: ${path.basename(smoke)} ===`);
    const result = spawnSync(process.execPath, [smoke], {
      stdio: 'inherit',
      cwd: REPO_ROOT,
    });
    if (result.status !== 0) {
      console.error(`FAIL panel-gallery smoke ${path.basename(smoke)} (exit ${result.status})`);
      anyFailed = true;
    }
  }
  return anyFailed ? 1 : 0;
}

function copyDirRecursive(src, dest) {
  // Minimal recursive copy. Used only for `bundleSrc` — staging a
  // shadow-cljs build whose `:output-dir` lives outside `OUT_ROOT`
  // (testbed surfaces, rf2-fe84r) into the served tree. Each file
  // overwrites the destination unconditionally so repeat runs pick up
  // re-compiled bundles.
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

// rf2-jzqs9 — Shared examples design system: one stylesheet + favicon +
// OG image across all three substrates (rf2-v4fpe Option 2). The
// hand-written index.html files reference these via relative paths like
// `_shared/css/style.css`, so the orchestrator stages the
// `examples/_shared/` tree into every example's output dir alongside
// main.js + index.html. Single source on disk; one copy step per build
// (cheap — a handful of small files).
const SHARED_SRC = path.join(REPO_ROOT, 'examples', '_shared');

function stageShared(outDir) {
  if (!fs.existsSync(SHARED_SRC)) return;
  copyDirRecursive(SHARED_SRC, path.join(outDir, '_shared'));
}

function stageHtml() {
  // Silent-on-success (rf2-try1x): per-file staging notices are
  // suppressed.  Errors still throw with the offending path.
  // rf2-h9ut9 — narrow EXAMPLES_FILTER: only stage selected entries.
  for (const ex of selectedExamples()) {
    // rf2-fe84r — testbed surfaces ship their shadow-cljs `:output-dir`
    // outside OUT_ROOT (per the per-surface `:testbeds/<name>` build's
    // `out/testbeds/<name>` setting). Stage the compiled bundle into
    // OUT_ROOT first so the static server picks it up under the
    // expected URL path. Examples that emit straight into OUT_ROOT
    // omit `bundleSrc` and skip this step.
    if (ex.bundleSrc) {
      if (!fs.existsSync(ex.bundleSrc)) {
        throw new Error(`Bundle source missing: ${ex.bundleSrc}`);
      }
      copyDirRecursive(ex.bundleSrc, ex.outDir);
    }

    if (!fs.existsSync(ex.outDir)) {
      throw new Error(`Build output dir missing: ${ex.outDir}`);
    }
    if (!fs.existsSync(ex.htmlSrc)) {
      throw new Error(`HTML source missing: ${ex.htmlSrc}`);
    }
    const dest = path.join(ex.outDir, 'index.html');
    fs.copyFileSync(ex.htmlSrc, dest);

    // rf2-jzqs9 — stage examples/_shared/ alongside index.html so the
    // hand-written page can <link>/<img> assets at the same relative
    // path on every example build.
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
  compileAll();
  stageHtml();

  const server = cleanup.trackProcess(spawnHarnessProcess(process.execPath, [HTTP_SERVER_BIN, OUT_ROOT, '-p', String(PORT), '-s', '-c-1'], {
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
      // rf2-h9ut9 — propagate the orchestrator's filter (CLI or env)
      // to the runner so spec-file selection matches the build/stage
      // narrowing. Empty = full sweep, which matches the runner's
      // unset-env default.
      EXAMPLES_FILTER:       FILTER,
    },
  }));

  const code = await new Promise((resolve) => runner.on('exit', resolve));

  let finalCode = code == null ? 1 : code;

  // rf2-1w76p — opt-in panel-gallery smokes. Runs AFTER the main
  // runner so the spec.cjs sweep + smokes share one wall-clock
  // budget. The smokes spawn their own http-servers on ports
  // 8766 / 8767 (distinct from the orchestrator's :8030) so there
  // is no port collision with the still-running EXAMPLES server.
  // Smoke failures fail the whole run; the orchestrator's server
  // is still torn down via the cleanup hook in main()'s caller.
  if (GALLERY_SMOKES_ENABLED) {
    console.log('\nRF2_CAUSA_RUN_GALLERY_SMOKES=1 — running panel-gallery smokes');
    try {
      compileAndStagePanelGallery();
      const smokeCode = runPanelGallerySmokes();
      if (smokeCode !== 0) finalCode = smokeCode;
    } catch (err) {
      console.error('panel-gallery smokes failed to set up:', err.message);
      finalCode = 1;
    }
  }

  return finalCode;
}

main().then(async (code) => {
  await cleanup.cleanup();
  process.exit(code);
}).catch(async (err) => {
  console.error(err);
  await cleanup.cleanup();
  process.exit(1);
});
