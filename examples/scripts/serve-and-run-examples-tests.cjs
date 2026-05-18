#!/usr/bin/env node
/*
 * Orchestrator for the examples Playwright suite.
 *
 * 1. Compiles every example shadow-cljs build (one per example).
 * 2. Stages each example's hand-written index.html into its
 *    out/examples/<name>/ directory next to main.js.
 * 3. Spawns http-server over out/examples on port 8030.
 * 4. Waits for it to be reachable, then runs the Playwright runner
 *    (run-examples-tests.cjs).
 * 5. Always tears the server down.
 *
 * Build list, mount paths, and HTML sources are declared in EXAMPLES
 * below. Adding a new example: add its shadow-cljs build target,
 * append an entry here, and add a Playwright spec.
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
const PORT = 8030;
// rf2-j3dlc — live-SSR JVM Ring server (Jetty) for the
// `ssr-live.spec.cjs` smoke. Side-port so it lives alongside the static
// http-server on PORT without contention. Launched by `main()` below
// via `clojure -X:live-ssr-server` against
// `examples/reagent/ssr/deps.edn`.
const LIVE_SSR_PORT = 8031;
// __dirname is <repo>/examples/scripts. IMPL_ROOT is <repo>/implementation
// (where shadow-cljs runs and node_modules lives); REPO_ROOT is <repo>.
const REPO_ROOT = path.resolve(__dirname, '..', '..');
const IMPL_ROOT = path.join(REPO_ROOT, 'implementation');
const OUT_ROOT = path.join(IMPL_ROOT, 'out', 'examples');
const RUNNER = path.resolve(__dirname, 'run-examples-tests.cjs');
// Live-SSR harness dir — deps.edn + server.clj. The `:examples/ssr`
// build's `:output-dir` (under OUT_ROOT) supplies the `main.js` the
// JVM server serves for browser hydration.
const LIVE_SSR_DIR = path.join(REPO_ROOT, 'examples', 'reagent', 'ssr');
const LIVE_SSR_STATIC_ROOT = path.join(OUT_ROOT, 'ssr');
// http-server is a devDependency of implementation/package.json. Resolve
// it from there explicitly so this script can be invoked from any cwd.
const HTTP_SERVER_BIN = require.resolve('http-server/bin/http-server', {
  paths: [IMPL_ROOT],
});
const READY_TIMEOUT_MS = 30000;
const cleanup = createHarnessCleanup();
cleanup.installSignalHandlers();

// Each example: shadow-cljs build id, the html source to stage, and the
// directory under out/examples it lands in. The url-path is what the
// Playwright spec navigates to under http://127.0.0.1:PORT/.
const EXAMPLES = [
  {
    build: 'examples/counter',
    htmlSrc: path.join(REPO_ROOT, 'examples', 'reagent', 'counter', 'index.html'),
    outDir: path.join(OUT_ROOT, 'counter'),
  },
  // counter-slim-and-fast (rf2-5lbx) — the same counter mounted on
  // day8/reagent-slim instead of the bridge. Paired with the
  // Reagent Slim bundle-isolation contract in
  // scripts/check-reagent-slim-bundle-isolation.cjs.
  {
    build: 'examples/counter-slim-and-fast',
    htmlSrc: path.join(REPO_ROOT, 'examples', 'reagent', 'counter_slim_and_fast', 'index.html'),
    outDir: path.join(OUT_ROOT, 'counter-slim-and-fast'),
  },
  // Performance-API instrumented variant of the counter.
  // rf2-p8f2s — the perf testbed is tool-owned (lives under
  // tools/causa/testbeds/perf_counter/) so the canonical tutorial
  // counter (examples/reagent/counter/) stays on the idiomatic
  // reg-event-db :initialise shape. The paired
  // tools/causa/testbeds/perf_counter/spec.cjs asserts a real
  // dispatch through the perf-on bundle produces `rf:event:*` /
  // `rf:sub:*` / `rf:fx:*` / `rf:render:*` measure entries on
  // `performance.getEntriesByType`.
  {
    build: 'examples/counter-perf',
    htmlSrc: path.join(REPO_ROOT, 'tools', 'causa', 'testbeds', 'perf_counter', 'index.html'),
    outDir: path.join(OUT_ROOT, 'counter-perf'),
  },
  // Cart-total testbed (rf2-0sg12) — Causa tutorial hero scenario as
  // runnable code. The companion spec at
  // tools/causa/testbeds/cart_total/spec.cjs walks the 3pm bug
  // (wrong-slot sub) end-to-end through the rendered UI.
  {
    build: 'examples/cart-total',
    htmlSrc: path.join(REPO_ROOT, 'tools', 'causa', 'testbeds', 'cart_total', 'index.html'),
    outDir: path.join(OUT_ROOT, 'cart-total'),
  },
  // Multi-frame Causa testbed (rf2-2vgog) — three named frames
  // (:cart-frame, :checkout-frame, :admin-frame) coexisting; one
  // cross-frame coordination scenario. The companion spec at
  // tools/causa/testbeds/multi_frame_causa/spec.cjs exercises frame
  // scoping, cascade isolation, the cross-frame hop, and Causa's
  // frame picker enumerating all three frames.
  {
    build: 'examples/multi-frame-causa',
    htmlSrc: path.join(REPO_ROOT, 'tools', 'causa', 'testbeds', 'multi_frame_causa', 'index.html'),
    outDir: path.join(OUT_ROOT, 'multi-frame-causa'),
  },
  {
    build: 'examples/temperature',
    htmlSrc: path.join(REPO_ROOT, 'examples', 'reagent', '7Guis', 'temperature', 'temperature.html'),
    outDir: path.join(OUT_ROOT, 'temperature'),
  },
  {
    build: 'examples/flight-booker',
    htmlSrc: path.join(REPO_ROOT, 'examples', 'reagent', '7Guis', 'flight_booker', 'flight_booker.html'),
    outDir: path.join(OUT_ROOT, 'flight-booker'),
  },
  {
    build: 'examples/timer',
    htmlSrc: path.join(REPO_ROOT, 'examples', 'reagent', '7Guis', 'timer', 'timer.html'),
    outDir: path.join(OUT_ROOT, 'timer'),
  },
  {
    build: 'examples/nine-states',
    htmlSrc: path.join(REPO_ROOT, 'examples', 'reagent', 'nine_states', 'index.html'),
    outDir: path.join(OUT_ROOT, 'nine-states'),
  },
  {
    build: 'examples/routing',
    htmlSrc: path.join(REPO_ROOT, 'examples', 'reagent', 'routing', 'index.html'),
    outDir: path.join(OUT_ROOT, 'routing'),
  },
  {
    build: 'examples/todomvc',
    htmlSrc: path.join(REPO_ROOT, 'examples', 'reagent', 'todomvc', 'index.html'),
    outDir: path.join(OUT_ROOT, 'todomvc'),
    extraFiles: [
      {
        src: path.join(IMPL_ROOT, 'node_modules', 'todomvc-common', 'base.css'),
        dest: 'base.css',
      },
      {
        src: path.join(IMPL_ROOT, 'node_modules', 'todomvc-app-css', 'index.css'),
        dest: 'todomvc-app.css',
      },
    ],
  },
  // Spec 014 — managed HTTP counter. Stages a static
  // /api/inc.json for the +1 button to fetch; a missing /api/does-not-exist
  // path naturally produces a 404 from http-server for the Fail button.
  {
    build: 'examples/managed-http-counter',
    htmlSrc: path.join(REPO_ROOT, 'examples', 'reagent', 'managed_http_counter', 'index.html'),
    outDir: path.join(OUT_ROOT, 'managed-http-counter'),
    extraFiles: [
      {
        src: path.join(REPO_ROOT, 'examples', 'reagent', 'managed_http_counter', 'api', 'inc.json'),
        dest: path.join('api', 'inc.json'),
      },
    ],
  },
  {
    build: 'examples/crud',
    htmlSrc: path.join(REPO_ROOT, 'examples', 'reagent', '7Guis', 'crud', 'crud.html'),
    outDir: path.join(OUT_ROOT, 'crud'),
  },
  {
    build: 'examples/circle-drawer',
    htmlSrc: path.join(REPO_ROOT, 'examples', 'reagent', '7Guis', 'circle_drawer', 'circle_drawer.html'),
    outDir: path.join(OUT_ROOT, 'circle-drawer'),
  },
  {
    build: 'examples/cells',
    htmlSrc: path.join(REPO_ROOT, 'examples', 'reagent', '7Guis', 'cells', 'cells.html'),
    outDir: path.join(OUT_ROOT, 'cells'),
  },
  {
    build: 'examples/login',
    htmlSrc: path.join(REPO_ROOT, 'examples', 'reagent', 'login', 'index.html'),
    outDir: path.join(OUT_ROOT, 'login'),
  },
  // UIx adapter smoke trio (counter + login, realworld skipped).
  // Different folders from the canonical Reagent versions so the
  // bundle-isolation grep can confirm UIx code does NOT appear in
  // Reagent-substrate examples and vice versa. The UIx tree lives
  // under examples/uix/.
  {
    build: 'examples/counter-uix',
    htmlSrc: path.join(REPO_ROOT, 'examples', 'uix', 'counter_uix', 'index.html'),
    outDir: path.join(OUT_ROOT, 'counter-uix'),
  },
  {
    build: 'examples/login-uix',
    htmlSrc: path.join(REPO_ROOT, 'examples', 'uix', 'login_uix', 'index.html'),
    outDir: path.join(OUT_ROOT, 'login-uix'),
  },
  // Helix adapter smoke trio (counter + login, realworld skipped —
  // the eight UIx decisions transfer unchanged). Different folders
  // from the canonical Reagent and UIx versions so the
  // bundle-isolation grep can confirm Helix code does NOT appear
  // in Reagent / UIx-substrate examples and vice versa. The Helix
  // tree lives under examples/helix/.
  {
    build: 'examples/counter-helix',
    htmlSrc: path.join(REPO_ROOT, 'examples', 'helix', 'counter_helix', 'index.html'),
    outDir: path.join(OUT_ROOT, 'counter-helix'),
  },
  {
    build: 'examples/login-helix',
    htmlSrc: path.join(REPO_ROOT, 'examples', 'helix', 'login_helix', 'index.html'),
    outDir: path.join(OUT_ROOT, 'login-helix'),
  },
  {
    build: 'examples/realworld',
    htmlSrc: path.join(REPO_ROOT, 'examples', 'reagent', 'realworld', 'index.html'),
    outDir: path.join(OUT_ROOT, 'realworld'),
  },
  // Pattern-Boot example (rf2-dsm2). The :app/boot state machine
  // owns the application's initialisation graph; the example's
  // four mocked endpoints are served entirely by an in-process
  // :rf.http/managed canned-success override (no static files
  // needed).
  {
    build: 'examples/boot',
    htmlSrc: path.join(REPO_ROOT, 'examples', 'reagent', 'boot', 'index.html'),
    outDir: path.join(OUT_ROOT, 'boot'),
  },
  // Runnable companion to docs/guide/09-state-machines.md.
  {
    build: 'examples/state-machine-walkthrough',
    htmlSrc: path.join(REPO_ROOT, 'examples', 'reagent', 'state_machine_walkthrough', 'index.html'),
    outDir: path.join(OUT_ROOT, 'state-machine-walkthrough'),
  },
  // SSR + hydration walkthrough.
  {
    build: 'examples/ssr',
    htmlSrc: path.join(REPO_ROOT, 'examples', 'reagent', 'ssr', 'index.html'),
    outDir: path.join(OUT_ROOT, 'ssr'),
  },
  // Story Stage 8 — the canonical counter app with the
  // seven `reg-*` Story macros wired up. URL-hash-routed: `#/`
  // renders the live counter; `#/stories` mounts the Story shell.
  // rf2-p8f2s — testbed-shaped (33× canonical counter); now lives
  // under tools/story/testbeds/counter_with_stories/.
  {
    build: 'examples/counter-with-stories',
    htmlSrc: path.join(REPO_ROOT, 'tools', 'story', 'testbeds', 'counter_with_stories', 'index.html'),
    outDir: path.join(OUT_ROOT, 'counter-with-stories'),
  },
  // Login-form testbed (rf2-0sg12) — Story tutorial five-state
  // scenario as runnable variants. URL-hash-routed: `#/` renders
  // the live login card; `#/stories` mounts the Story shell.
  {
    build: 'examples/login-form',
    htmlSrc: path.join(REPO_ROOT, 'tools', 'story', 'testbeds', 'login_form', 'index.html'),
    outDir: path.join(OUT_ROOT, 'login-form'),
  },
  // Pattern-LongRunningWork worked example (rf2-o9fg) —
  // :invoke-all spawn-and-join with progress reporting and a
  // cooperative cancellation cascade on parent-unmount.
  {
    build: 'examples/long-running-work',
    htmlSrc: path.join(REPO_ROOT, 'examples', 'reagent', 'long_running_work', 'index.html'),
    outDir: path.join(OUT_ROOT, 'long-running-work'),
  },
  // Pattern-WebSocket worked example (rf2-yf97). Connection machine
  // with hierarchical :active, :invoke'd socket actor, :after backoff,
  // :always queue-flush, :fsm/tags, request-reply correlation, and
  // connection-epoch staleness. The transport is an in-process mock
  // WebSocket so the example is standalone — no network needed.
  {
    build: 'examples/websocket',
    htmlSrc: path.join(REPO_ROOT, 'examples', 'reagent', 'websocket', 'index.html'),
    outDir: path.join(OUT_ROOT, 'websocket'),
  },
  // rf2-ik4io — SSR hydration baseline. Static index.html bakes
  // pre-rendered HTML + a :rf/hydration-payload script; the browser-
  // side run reads the payload, dispatches :rf/hydrate, and calls
  // verify-hydration!. The companion spec.cjs lives at
  // testbeds/ssr_basic/spec.cjs.
  //
  // These three SSR testbeds are top-level `testbeds/<surface>/`
  // siblings of `examples/<substrate>/` (per the testbeds split,
  // rf2-96nb3); the shadow-cljs build id uses the `testbeds/` prefix
  // and lands the bundle under `implementation/out/testbeds/<id>/`.
  // The orchestrator serves http://127.0.0.1:8030/<id>/ from OUT_ROOT
  // (out/examples/), so we redirect outDir into the served root with
  // a `testbed-` prefix to keep the URL path unambiguous.
  {
    build: 'testbeds/ssr-basic',
    htmlSrc: path.join(REPO_ROOT, 'testbeds', 'ssr_basic', 'index.html'),
    outDir: path.join(OUT_ROOT, 'testbed-ssr-basic'),
  },
  // rf2-ik4io — SSR hydration-mismatch surface. The payload bakes a
  // deliberately wrong :rf/render-hash so verify-hydration! emits
  // :rf.ssr/hydration-mismatch with :recovery :warned-and-replaced.
  {
    build: 'testbeds/ssr-hydration-mismatch',
    htmlSrc: path.join(REPO_ROOT, 'testbeds', 'ssr_hydration_mismatch', 'index.html'),
    outDir: path.join(OUT_ROOT, 'testbed-ssr-hydration-mismatch'),
  },
  // rf2-ik4io — SSR multi-frame surface. Three frames each hydrate
  // independently from their own payload slice; the three panels
  // prove per-frame state restoration through the hydration
  // boundary.
  {
    build: 'testbeds/ssr-multi-frame',
    htmlSrc: path.join(REPO_ROOT, 'testbeds', 'ssr_multi_frame', 'index.html'),
    outDir: path.join(OUT_ROOT, 'testbed-ssr-multi-frame'),
  },
  // rf2-fe84r cross-cutting six — shared framework-behavior testbed
  // surfaces. Unlike the SSR testbeds above (whose shadow-cljs
  // builds emit directly into `out/examples/testbed-<id>/`), the
  // rf2-kzcim testbeds emit into `out/testbeds/<id>/` and the
  // orchestrator copies the bundle into `OUT_ROOT/testbeds/<id>/`
  // so http-server serves it under the same origin as the examples.
  // The `bundleSrc` field opts a build into the bundle-copy step.
  // The two staging conventions coexist; rf2-ik4io and rf2-kzcim
  // each made a defensible decision at their time and the
  // orchestrator handles both.
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
function selectedExample(ex) {
  return FILTER === '' || ex.build.includes(FILTER);
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

// rf2-j3dlc — launch the live-SSR JVM Ring server. The Clojure CLI
// ships as `clojure` (POSIX) or `clojure.exe` / `deps.clj` (Windows).
// `shell: true` on Windows lets the OS resolve the right one against
// %PATHEXT% (.EXE, .CMD, .BAT) without us hard-coding the suffix.
//
// We DO NOT pass `:static-root` on the command line — Clojure `-X`
// args go through `read-string` once and then through the platform
// shell once, and Windows backslash-bearing paths survive neither
// cleanly. Instead we rely on the alias's `:exec-args :static-root`
// default (relative path baked into examples/reagent/ssr/deps.edn,
// resolved against the LIVE_SSR_DIR cwd). Port stays on the CLI
// because it's a plain integer with no shell-meaningful characters.
//
// The server stays alive until the orchestrator's cleanup kills it.
function spawnLiveSsrServer() {
  const isWin = process.platform === 'win32';
  const args = [
    '-X:live-ssr-server',
    ':port', String(LIVE_SSR_PORT),
  ];
  return cleanup.trackProcess(spawnHarnessProcess('clojure', args, {
    cwd:   LIVE_SSR_DIR,
    stdio: ['ignore', 'inherit', 'inherit'],
    shell: isWin,
  }));
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

  // rf2-j3dlc — bring up the live-SSR JVM server before the spec runner
  // starts. The :examples/ssr build was just compiled by `compileAll`
  // (above), so `LIVE_SSR_STATIC_ROOT/main.js` is on disk and the
  // server's static handler can serve it on the same origin as the
  // SSR-rendered HTML. The browser-driven `ssr-live.spec.cjs` reaches
  // it via http://127.0.0.1:LIVE_SSR_PORT/.
  //
  // rf2-h9ut9 — skip the JVM bring-up when EXAMPLES_FILTER excludes
  // the ssr build. Saves ~10-20s of JVM cold-start on narrow runs
  // (the realworld smoke is the canonical use-case and doesn't need
  // SSR live infrastructure).
  const needsLiveSsr = EXAMPLES.some(
    (ex) => selectedExample(ex) && ex.build === 'examples/ssr',
  );
  if (needsLiveSsr) {
    const liveSsr = spawnLiveSsrServer();
    let liveSsrDown = false;
    liveSsr.on('exit', (code, signal) => {
      liveSsrDown = true;
      if (code !== 0 && code !== null) {
        console.error(`live-ssr server exited unexpectedly (code=${code}, signal=${signal}).`);
      }
    });
    // JVM cold-start (Clojure + shadow-cljs's transitive deps + Jetty
    // bind) — well within READY_TIMEOUT_MS in CI but can take ~10-20s
    // on a cold local cache.
    const liveSsrReady = await waitForHttpReady(LIVE_SSR_PORT, Date.now() + READY_TIMEOUT_MS, {
      isAborted: () => liveSsrDown,
    });
    if (!liveSsrReady || liveSsrDown) {
      console.error(`live-ssr server did not become reachable on :${LIVE_SSR_PORT} within ${READY_TIMEOUT_MS}ms.`);
      return 1;
    }
  }

  const runner = cleanup.trackProcess(spawnHarnessProcess(process.execPath, [RUNNER], {
    stdio: 'inherit',
    env: {
      ...process.env,
      EXAMPLES_BASE_URL:    `http://127.0.0.1:${PORT}`,
      // rf2-j3dlc — exposed to `ssr-live.spec.cjs` so the spec can
      // build its `spec.url` against the JVM server's port without
      // hard-coding it in the spec module.
      EXAMPLES_LIVE_SSR_URL: `http://127.0.0.1:${LIVE_SSR_PORT}`,
      // rf2-h9ut9 — propagate the orchestrator's filter (CLI or env)
      // to the runner so spec-file selection matches the build/stage
      // narrowing. Empty = full sweep, which matches the runner's
      // unset-env default.
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
  console.error(err);
  await cleanup.cleanup();
  process.exit(1);
});
