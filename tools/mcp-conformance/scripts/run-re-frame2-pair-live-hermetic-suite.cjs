#!/usr/bin/env node
/*
 * Hermetic orchestrator for the re-frame2-pair LIVE conformance SUITE.
 *
 * This is the CI entry point for the WHOLE hermetic live suite — it runs
 * EVERY live inner test in the `INNER_TESTS` inventory (see ~:200), not
 * just the overflow gate. Overflow is one member of that inventory
 * alongside subscribe/progress, redaction, isError, EP-0017 cofx, and
 * EP-0018 event-metadata. The runner is named for the suite, not for the
 * overflow gate, so test selection / CI triage / future conformance work
 * find a suite-shaped name.
 *
 * Each live inner test (e.g. `test/live-re-frame2-pair-overflow.cjs`) is
 * gated on $SHADOW_CLJS_NREPL_PORT. Without that env var it exits 0 with
 * a SKIP marker because the re-frame2-pair-mcp server runs degraded — no
 * real eval, no cap-trigger, no overflow marker.
 *
 * This script makes the live path *actually* fire on CI by:
 *
 *   1. Spawning `shadow-cljs watch app` against the re-frame2-pair fixture at
 *      `skills/re-frame2-pair/tests/fixture/` — a tiny re-frame2 counter
 *      with `re-frame2-pair.runtime` already wired as a `:devtools
 *      :preloads` entry. The shadow-cljs build also serves an
 *      http-server on :8030.
 *   2. Waiting for the nREPL port file to land (shadow-cljs 3.x writes
 *      to `.shadow-cljs/nrepl.port`; we also probe the legacy
 *      `target/shadow-cljs/nrepl.port` and `.nrepl-port` fallbacks).
 *   3. Waiting for the `:app` bundle to actually compile (not just the
 *      nREPL port to bind — dev-http serves a SPA-style fallback HTML
 *      for `/out/main.js` 200 BEFORE the first compile completes; we
 *      gate on the Content-Type being JS).
 *   4. Launching headless Chromium (Playwright) at
 *      http://localhost:8030 so the bundle loads and the runtime
 *      preload sets `window.__re_frame2_pair_runtime`.
 *   5. Waiting for the runtime sentinel to be present in the browser
 *      AND for shadow-cljs's `:app` runtime to be addressable via
 *      `cljs-eval` over nREPL (so re-frame2-pair-mcp's `ensure-runtime!` probe
 *      sees the runtime — that probe `.catch`es shadow's
 *      "no-runtime-connected" error to `false`, surfacing as a false
 *      `:runtime-not-preloaded`).
 *   6. Setting SHADOW_CLJS_NREPL_PORT=<port> and spawning
 *      `node test/live-re-frame2-pair-overflow.cjs`.
 *   7. Tearing down browser + shadow-cljs cleanly on success, failure,
 *      or signal.
 *
 * All six boot gates (port file at the right path, TCP listener, fixture
 * HTTP, bundle compile, browser sentinel, shadow-runtime addressable) are
 * required: each one closes a window in which the live path would fire
 * before the runtime is genuinely ready, which would surface as a long CI
 * timeout rather than a clean conformance result.
 *
 * Exit codes:
 *   0  hermetic conformance passed
 *   1  conformance failure (overflow marker / SDK / etc.)
 *   2  orchestration failure (shadow-cljs didn't boot, port file
 *      missing, runtime preload didn't land, watchdog elapsed)
 *
 * Hard time-cap of HERMETIC_TIMEOUT_MS guards against runaway compiles
 * on a cold CI runner. The shadow-cljs `watch` child + Playwright
 * browser are killed in the `finally`; SIGINT/SIGTERM also wire to the
 * same cleanup.
 *
 * The fixture `npm install` setup runs under an ASYNC spawn with its own
 * `SETUP_COMMAND_TIMEOUT_MS` cap (see `runTrusted`). A synchronous
 * `crossSpawn.sync` there would block the event loop for the install's
 * whole lifetime — during which the HERMETIC_TIMEOUT_MS watchdog (and
 * signal handlers) physically cannot fire — so a hung `npm install` would
 * wedge the run past the outer CI job timeout. The async spawn keeps the
 * loop live so BOTH the per-command timeout AND the whole-run watchdog
 * stay armed across setup.
 */
'use strict';

const fs = require('node:fs');
const http = require('node:http');
const net = require('node:net');
const path = require('node:path');
const os = require('node:os');
const crossSpawn = require('cross-spawn');
const {
  resolveTrustedExe,
  safeUnlinkInside,
  safeReadFileInside,
} = require('../lib/exec-safety.cjs');

// We use `cross-spawn` instead of Node's `child_process.spawn` for the
// two Windows-toolchain spawn sites (`npm` install + `npx shadow-cljs
// watch`) because Node's spawn refuses to execute `.cmd` / `.bat` with
// `shell: false` since the CVE-2024-27980 fix (EINVAL). cross-spawn
// dispatches to cmd.exe under the hood when the resolved target ends
// in `.cmd`, escapes arguments correctly, and — load-bearing for the
// command-hijack accident-gating — does NOT do a cwd-prefixed PATH walk
// when the command argument is an absolute path (see `which`'s
// `cmd.match(/\//)` short-circuit). The accident-gating contract is
// preserved by passing an absolute path resolved via the host's
// PATH-only scan (see `lib/exec-safety.cjs`).

const HERE = __dirname;
const MCP_CONFORMANCE_ROOT = path.resolve(HERE, '..');
const REPO_ROOT = path.resolve(MCP_CONFORMANCE_ROOT, '..', '..');
const FIXTURE_DIR = path.join(
  REPO_ROOT,
  'skills',
  're-frame2-pair',
  'tests',
  'fixture',
);
const RE_FRAME2_PAIR_MCP_DIR = path.join(REPO_ROOT, 'tools', 're-frame2-pair-mcp');
const {
  createDiagnosticBuffer,
  isVerboseTests,
} = require(path.join(
  REPO_ROOT,
  'implementation',
  'scripts',
  'lib',
  'browser-test-report.cjs',
));
const VERBOSE_TESTS = isVerboseTests();
const DIAGNOSTICS = createDiagnosticBuffer();

// Module-scope handle to the in-flight teardown closure.
// `main()` assigns this once it has spawned shadow-cljs / Chromium so
// the hard watchdog below can tear those children down BEFORE
// `process.exit` — without it, a watchdog-elapse would orphan the
// shadow-cljs JVM (and a launched Chromium), and those orphans can keep
// the CI step's inherited log pipes open past the node exit. Stays null
// until the children exist; the watchdog null-guards it.
let activeCleanup = null;

// Shadow-cljs writes its nREPL port file under whichever cache-root
// the build is configured for. Default in 3.x is `.shadow-cljs/`; older
// configs used `target/shadow-cljs/`; nrepl itself drops `.nrepl-port`.
// re-frame2-pair-mcp's runtime probe (`re_frame2_pair_mcp/nrepl.cljs`
// `port-file-candidates`) walks the same list — keep them in lockstep.
// The orchestrator watches every candidate path so it binds to whichever
// one the active shadow-cljs version writes (shadow-cljs 3.x defaults to
// `.shadow-cljs/nrepl.port`), rather than gambling on a single location.
const NREPL_PORT_FILE_CANDIDATES = [
  path.join(FIXTURE_DIR, '.shadow-cljs', 'nrepl.port'),
  path.join(FIXTURE_DIR, 'target', 'shadow-cljs', 'nrepl.port'),
  path.join(FIXTURE_DIR, '.nrepl-port'),
];

const FIXTURE_HTTP_PORT = 8030; // hard-coded in fixture's shadow-cljs.edn
const FIXTURE_URL = `http://127.0.0.1:${FIXTURE_HTTP_PORT}/`;
const FIXTURE_BUNDLE_PATH = path.join(FIXTURE_DIR, 'public', 'out', 'main.js');

// Wall-clock caps. shadow-cljs cold-start with a warm Maven cache is
// typically 30-60s on GHA; warm restart is much faster. We give the
// boot 360s so the first cold-cache run of the day (no `~/.m2`
// restore hit at all) still has headroom while Maven resolves the
// fixture's :local/root deps (core + reagent + epoch + schemas +
// machines + Reagent/Malli trees). The CI workflow's
// `mcp-conformance-re-frame2-pair` job hashes those
// inputs into its actions/cache key, so this headroom only
// kicks in on the truly cold path; warm-cache runs still bind the
// nREPL port in <60s.
const SHADOW_BOOT_TIMEOUT_MS = 360_000;
const RUNTIME_PRELOAD_TIMEOUT_MS = 60_000;
const HERMETIC_TIMEOUT_MS = 540_000;
// Bounded waits for the async teardown. `cleanup`
// awaits Playwright's promise-returning `browser.close()` (capped so a
// wedged browser-close can't hang the teardown) and SIGTERM→exit of the
// shadow-cljs child (escalating to SIGKILL after the grace, then observing
// the final exit). `$HERMETIC_CLEANUP_*_MS` shrink these caps for the
// teardown regression harness only (`runner-cleanup.test.cjs`); production
// CI never sets them.
const CLEANUP_BROWSER_CLOSE_TIMEOUT_MS =
  Number(process.env.HERMETIC_CLEANUP_BROWSER_MS) || 15_000;
const CLEANUP_SHADOW_SIGTERM_GRACE_MS =
  Number(process.env.HERMETIC_CLEANUP_SHADOW_GRACE_MS) || 5_000;
const CLEANUP_SHADOW_SIGKILL_GRACE_MS =
  Number(process.env.HERMETIC_CLEANUP_SHADOW_KILL_MS) || 5_000;
// Hard cap the signal/watchdog paths race the async cleanup against, so an
// interrupted run still exits promptly even if a child refuses to die. The
// cleanup is awaited up to this bound, then the process exits regardless.
const CLEANUP_HARD_CAP_MS =
  Number(process.env.HERMETIC_CLEANUP_HARD_CAP_MS) || 30_000;
// Poll cadence for the four sequential boot gates (port file, TCP
// listener, fixture HTTP, bundle compile, runtime sentinel, shadow
// runtime addressable). Each gate latches as soon as it flips, so the
// poll interval is pure resolution latency on the critical path: a warm
// cache boot is gated by `~6 * POLL_MS` of cumulative wait. A tight 100ms
// cadence keeps warm-cache CI runs fast (the gates check cheap
// filesystem/TCP probes, so the high poll rate doesn't meaningfully raise
// cold-cache load).
const POLL_MS = 100;

// Inner tests the orchestrator boots shadow-cljs for. Each runs with
// the spawned `SHADOW_CLJS_NREPL_PORT` set so its SKIP gate flips off
// and the live path fires. Run sequentially against the same booted
// runtime — the cold-boot cost amortises across every test.
//
// `live-re-frame2-pair-subscribe.cjs` pins the `notifications/progress`
// streaming wire surface. The orchestrator's name keeps `overflow` to
// match the workflow YAML's script reference; the `INNER_TESTS` list IS
// the authoritative inventory.
//
// Each entry carries the test's success `sentinel` — the
// `... CONFORMANCE GREEN` line it prints ONLY on a real (non-skipped)
// pass. The hermetic env guarantees `$SHADOW_CLJS_NREPL_PORT` is set, so
// the inner test's SKIP gate MUST NOT fire here — yet a SKIP still
// exits 0. Without a sentinel check, a regression that broke the
// orchestrator's SETUP path (port probe, bundle compile, runtime
// sentinel) short of an inner-test FAILURE could leave the load-bearing
// redaction gate SKIPping (exit 0) while CI shows the hermetic job green
// via the OTHER inner tests. Asserting the sentinel (and the absence of
// a `SKIP ` banner) makes a silent in-hermetic SKIP turn the gate RED.
const INNER_TESTS = [
  {
    name: 'live overflow conformance',
    path: path.join(MCP_CONFORMANCE_ROOT, 'test', 'live-re-frame2-pair-overflow.cjs'),
    sentinel: 'RE-FRAME2-PAIR-MCP LIVE OVERFLOW CONFORMANCE GREEN',
  },
  {
    name: 'live subscribe / notifications/progress conformance',
    path: path.join(MCP_CONFORMANCE_ROOT, 'test', 'live-re-frame2-pair-subscribe.cjs'),
    sentinel: 'RE-FRAME2-PAIR-MCP LIVE SUBSCRIBE CONFORMANCE GREEN',
  },
  {
    // Egress-protection regression net for the pull-mode epoch tools
    // (trace-window / watch-epochs). Drives a declared-sensitive app-db
    // slot through both tools across the MCP wire and asserts the
    // sensitive epoch is WHOLE-DROPPED gate-OFF (the default) and shipped
    // gate-ON. This is the gate that pins the whole-drop behaviour.
    name: 'live egress-protection conformance (pull-mode epoch tools)',
    path: path.join(MCP_CONFORMANCE_ROOT, 'test', 'live-re-frame2-pair-redaction.cjs'),
    sentinel: 'RE-FRAME2-PAIR-MCP LIVE EGRESS-PROTECTION CONFORMANCE GREEN',
  },
  {
    // Pin the universal `:ok? false ⇒ isError:true` contract
    // on the read-family GENUINE error envelopes (read-dom/read-ui
    // bad-selector). Drives a malformed CSS selector against the live
    // runtime so `querySelectorAll` throws and the runtime fn returns a
    // structured `{:ok? false :reason :rf.error/...-bad-selector}` — then
    // asserts the SDK response is isError. This is the live SDK-boundary
    // gate for the error path; the degraded walk only exercises the
    // :nrepl-port-not-found shape, so the live runtime is required here.
    name: 'live isError-on-:ok?-false conformance (read-dom/read-ui bad selector)',
    path: path.join(MCP_CONFORMANCE_ROOT, 'test', 'live-re-frame2-pair-iserror.cjs'),
    sentinel: 'RE-FRAME2-PAIR-MCP LIVE ISERROR-ON-OK-FALSE CONFORMANCE GREEN',
  },
  {
    // EP-0017 recordable-coeffects (`cofx`) over the live MCP
    // wire. Dispatches the fixture's [:counter/stamp] (a reg-event
    // declaring `:rf.cofx/requires [:rf/time-ms]`) with a scripted
    // `cofx "{:rf/time-ms <N>}"` and proves the supplied fact reaches the
    // resulting app-db state (reproducible-dispatch determinism), the
    // malformed-cofx refusals the degraded handler hides, and the EP-0017
    // tooling visibility (`list-handlers`/`handler-meta` for the `cofx`
    // kind + authored `:rf.cofx/requires` on the event). The degraded
    // end-to-end only proves the `cofx` arg is ACCEPTED — this is the
    // behaviour gate.
    name: 'live EP-0017 cofx conformance (reproducible dispatch + cofx tooling)',
    path: path.join(MCP_CONFORMANCE_ROOT, 'test', 'live-re-frame2-pair-cofx.cjs'),
    sentinel: 'RE-FRAME2-PAIR-MCP LIVE COFX CONFORMANCE GREEN',
  },
  {
    // EP-0018 unified event-registration metadata over the
    // live MCP wire. Inspects a fixture `rf/reg-event` id (`:counter/inc`)
    // via `list-handlers`/`handler-meta {kind "event"}` and pins the
    // unified shape (`:ok? true`, `:kind :event`, `:id`, the
    // `:rf/event-handler` wrapper) AND the absence of any of the markers
    // that must not appear (`:event/kind`,
    // `:rf/db-handler`/`:rf/fx-handler`/`:rf/ctx-handler`).
    // The degraded gate only proves the descriptor/CallToolResult wiring —
    // this proves the live event-metadata wire reflects EP-0018.
    name: 'live EP-0018 event-metadata conformance (unified reg-event shape)',
    path: path.join(MCP_CONFORMANCE_ROOT, 'test', 'live-re-frame2-pair-event-meta.cjs'),
    sentinel: 'RE-FRAME2-PAIR-MCP LIVE EVENT-METADATA CONFORMANCE GREEN',
  },
];

function recordLine(line, stream = 'stdout') {
  DIAGNOSTICS.add(line, stream);
  if (VERBOSE_TESTS) {
    const write = stream === 'stderr' ? console.error : console.log;
    write(line);
  }
}

function recordChunk(prefix, chunk, stream = 'stdout') {
  const normalized = String(chunk || '').replace(/\r\n/g, '\n');
  for (const line of normalized.split('\n')) {
    if (line.length === 0) continue;
    recordLine(`${prefix}${line}`, stream);
  }
}

function flushDiagnostics() {
  if (VERBOSE_TESTS || DIAGNOSTICS.isEmpty()) return;
  console.error('--- re-frame2-pair hermetic diagnostics ---');
  DIAGNOSTICS.flush({
    stdout: (line) => console.error(line),
    stderr: (line) => console.error(line),
  });
  console.error('----------------------------------');
}

function log(msg) {
  recordLine(`[hermetic] ${msg}`);
}
function logErr(msg) {
  recordLine(`[hermetic] ${msg}`, 'stderr');
}

function exists(p) {
  try {
    fs.statSync(p);
    return true;
  } catch {
    return false;
  }
}

function sleep(ms) {
  return new Promise((r) => setTimeout(r, ms));
}

// Race `promise` against a `ms` cap. Resolves `true` if the promise
// settled first (success OR rejection — a rejected `browser.close()` still
// means we waited for it), `false` if the cap elapsed first. Never throws:
// the caller's contract is "did the awaited thing finish in time", and a
// teardown step that rejects is treated as "settled" (we tried, we move
// on), not as a reason to abandon the rest of cleanup. The timeout timer is
// unref'd so it can't itself keep the loop alive past a clean exit.
function settledWithin(promise, ms) {
  return new Promise((resolve) => {
    let done = false;
    const t = setTimeout(() => {
      if (done) return;
      done = true;
      resolve(false);
    }, ms);
    t.unref();
    Promise.resolve(promise).then(
      () => { if (!done) { done = true; clearTimeout(t); resolve(true); } },
      () => { if (!done) { done = true; clearTimeout(t); resolve(true); } },
    );
  });
}

// Resolve when `child` has emitted `exit` (or already has). Returns a
// promise that never rejects; pair it with `settledWithin` for a bounded
// wait. `hasExited` is the caller's already-tracked exit flag so a child
// that exited before we attach still resolves immediately.
function waitForChildExit(child, hasExited) {
  if (hasExited()) return Promise.resolve();
  return new Promise((resolve) => {
    child.once('exit', () => resolve());
  });
}

// Build the idempotent async teardown. Lives at
// module scope so the teardown regression harness
// (`runner-cleanup.test.cjs`) can drive the REAL teardown logic against a
// fake promise-returning browser and a slow-exiting fake child — proving
// the awaited-close + SIGTERM→exit→SIGKILL contract holds without booting
// shadow-cljs + Playwright.
//
// `deps`:
//   getBrowser()      -> the Playwright Browser or null (promise-returning `close()`)
//   getShadow()       -> the shadow-cljs child or null (`kill(sig)` + `'exit'` event)
//   hasShadowExited() -> boolean: has the shadow child already emitted `exit`
//   log / logErr      -> structured loggers (default to the module ones)
//   timeouts          -> overridable caps (default to the module constants;
//                        the harness shrinks them so the test runs fast)
//
// Returns a `cleanup()` function whose promise:
//   1. awaits `browser.close()`, bounded by `browserCloseMs`,
//   2. SIGTERMs shadow then awaits its `exit` (or `shadowTermGraceMs`),
//   3. SIGKILLs if still alive then awaits the final exit (or `shadowKillGraceMs`).
// Idempotent: repeat/concurrent calls return the SAME in-flight promise.
function makeCleanup(deps) {
  const {
    getBrowser,
    getShadow,
    hasShadowExited,
    log: logFn = log,
    logErr: logErrFn = logErr,
    browserCloseMs = CLEANUP_BROWSER_CLOSE_TIMEOUT_MS,
    shadowTermGraceMs = CLEANUP_SHADOW_SIGTERM_GRACE_MS,
    shadowKillGraceMs = CLEANUP_SHADOW_SIGKILL_GRACE_MS,
  } = deps;
  let cleanupPromise = null;
  return function cleanup() {
    if (cleanupPromise) return cleanupPromise;
    cleanupPromise = (async () => {
      logFn('cleanup requested');
      // (1) Await the promise-returning browser close, bounded.
      const browser = getBrowser();
      if (browser) {
        const closed = await settledWithin(
          (async () => {
            try { await browser.close(); }
            catch (e) { logFn(`browser.close() rejected during cleanup: ${e && e.message}`); }
          })(),
          browserCloseMs,
        );
        if (!closed) {
          logErrFn(
            `browser.close() did not settle within ${browserCloseMs}ms — ` +
              'continuing teardown',
          );
        }
      }
      // (2)+(3) SIGTERM shadow, await exit / grace, then SIGKILL + await.
      const shadow = getShadow();
      if (shadow && !hasShadowExited()) {
        try { shadow.kill('SIGTERM'); } catch {}
        const exitedOnTerm = await settledWithin(
          waitForChildExit(shadow, hasShadowExited),
          shadowTermGraceMs,
        );
        if (!exitedOnTerm && !hasShadowExited()) {
          logErrFn(
            `shadow-cljs did not exit ${shadowTermGraceMs}ms after SIGTERM ` +
              '— escalating to SIGKILL',
          );
          try { shadow.kill('SIGKILL'); } catch {}
          const exitedOnKill = await settledWithin(
            waitForChildExit(shadow, hasShadowExited),
            shadowKillGraceMs,
          );
          if (!exitedOnKill && !hasShadowExited()) {
            logErrFn(
              'shadow-cljs still has not reported exit after SIGKILL + ' +
                `${shadowKillGraceMs}ms — abandoning (the OS will reap it; ` +
                'we have done all we can within the cap)',
            );
          }
        }
      }
      logFn('cleanup complete');
    })();
    return cleanupPromise;
  };
}

// Discriminate a containment-escape refusal (a candidate whose realpath
// resolves OUTSIDE FIXTURE_DIR) from a benign fs failure (EACCES, EBUSY
// — a Windows lock, a permission quirk). The exec-safety helpers tag
// every escape with the `symlink-escape accident-gating` marker; benign
// failures carry an errno `code` and no such marker. Only an
// escape is fatal — a transient lock must not abort the whole run.
function isContainmentEscape(e) {
  return !!(e && typeof e.message === 'string' &&
    e.message.includes('symlink-escape accident-gating'));
}

// `candidates` + `fixtureDir` are parameterised (defaulting to the
// module constants) so the call-site regression test can drive
// this exact function against a temp fixture with a symlinked
// `.shadow-cljs` — proving the poller refuses an external port file
// without booting shadow-cljs + Chromium.
function readPortFile(
  candidates = NREPL_PORT_FILE_CANDIDATES,
  fixtureDir = FIXTURE_DIR,
) {
  // Walk every candidate path; return `{port, source}` for the first
  // file that parses to a finite integer. The `source` string is used
  // for diagnostics so a successful read tells you *which* path
  // satisfied the wait — useful when shadow-cljs's default cache-root
  // moves between versions.
  //
  // Route the read through `safeReadFileInside` — the
  // SAME containment check the cleanup loop's `safeUnlinkInside` uses.
  // This refuses any candidate (or candidate parent) symlinked OUTSIDE
  // FIXTURE_DIR: such a file is exactly what the cleanup step refuses to
  // delete, and trusting it as the live nREPL source would let a stale
  // external `nrepl.port` satisfy the port-file wait and steer the inner
  // conformance tests at an unrelated runtime (false-red / false-green).
  // `safeReadFileInside` THROWS on a containment escape; we let that
  // propagate so an escaped candidate is a FATAL orchestration error, not
  // a silently-trusted read. A candidate that simply doesn't exist yet
  // returns `null` — try the next.
  for (const p of candidates) {
    let txt;
    try {
      const contents = safeReadFileInside(p, fixtureDir);
      if (contents == null) continue; // not present yet — try next
      txt = contents.trim();
    } catch (e) {
      // A containment escape (symlinked candidate / parent that resolves
      // outside FIXTURE_DIR) is a fatal orchestration error — DO NOT fall
      // through and trust the file. Re-throw with context so the caller's
      // catch surfaces it as exit 2.
      throw new Error(
        `refusing to read nREPL port candidate ${p}: ${e.message} ` +
          '(rf2-khav7l: an escaped/refused port file must not be trusted ' +
          'as the live nREPL source).',
      );
    }
    const n = parseInt(txt, 10);
    if (Number.isFinite(n)) return { port: n, source: p };
  }
  return null;
}

function probeHttp(port, hostname = '127.0.0.1') {
  return new Promise((resolve) => {
    const req = http.get(
      { host: hostname, port, path: '/', timeout: 1000 },
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

// Probe `/out/main.js` and confirm it is the actual compiled bundle —
// not shadow-cljs's SPA-style fallback HTML that dev-http returns 200
// for unknown paths. Used to wait for the bundle to compile before
// Chromium navigates; without this gate the page loads while shadow is
// still on its first compile, the runtime preload never runs, and the
// sentinel-wait times out.
//
// We accept the response iff the Content-Type starts with
// `application/javascript` (shadow's dev-http sets this for .js files
// it actually serves) AND the body begins with a known shadow-cljs
// preamble byte. The preamble guard is belt-and-braces — a partial
// write during compile would also fail the content-type check.
function probeBundleReady(port, hostname = '127.0.0.1') {
  return new Promise((resolve) => {
    const req = http.get(
      { host: hostname, port, path: '/out/main.js', timeout: 2000 },
      (res) => {
        const ct = (res.headers['content-type'] || '').toLowerCase();
        if (res.statusCode !== 200 || !ct.startsWith('application/javascript')) {
          res.resume();
          resolve(false);
          return;
        }
        // Read up to ~256 bytes to confirm the body is actually JS.
        // shadow-cljs's first-line preamble starts with `var $CLJS` or
        // a `SHADOW_ENV.setLoaded`/`var shadow=...` — anything beginning
        // with `<` is the SPA fallback HTML.
        let prefix = '';
        res.on('data', (chunk) => {
          prefix += chunk.toString('utf8');
          if (prefix.length >= 64) {
            req.destroy();
            const head = prefix.slice(0, 64).trimStart();
            resolve(!head.startsWith('<'));
          }
        });
        res.on('end', () => {
          const head = prefix.slice(0, 64).trimStart();
          resolve(prefix.length > 0 && !head.startsWith('<'));
        });
        res.on('error', () => resolve(false));
      },
    );
    req.on('error', () => resolve(false));
    req.on('timeout', () => {
      req.destroy();
      resolve(false);
    });
  });
}

// Probe shadow-cljs's `:app` CLJS runtime by attempting a trivial
// `cljs-eval` over nREPL. Returns true iff the eval round-trips
// successfully — which only happens once the browser-side runtime has
// registered with shadow via the devtools WebSocket.
//
// Why this is necessary: re-frame2-pair-mcp's `runtime-preloaded?` (in
// `tools/probe.cljs`) wraps `cljs-eval` in a `.catch` that swallows
// every error to `false`, including the transient "No application has
// connected to the REPL server" error that shadow throws between
// page-load and runtime-registration. Without this gate the live-test
// fires while the runtime isn't yet addressable and re-frame2-pair-mcp's first
// `eval-cljs` call surfaces as `:runtime-not-preloaded` — a false
// negative on the actual hermetic conformance.
//
// One bencode round-trip on a fresh socket; we don't try to share the
// connection with the live test because the live test fork-execs the
// re-frame2-pair-mcp server (which opens its own nREPL connection).
function probeShadowRuntimeReady(nreplPort, hostname = '127.0.0.1') {
  return new Promise((resolve) => {
    const sock = net.connect({ host: hostname, port: nreplPort, timeout: 2000 });
    let buf = Buffer.alloc(0);
    let done = false;
    const finish = (ok) => {
      if (done) return;
      done = true;
      try { sock.end(); } catch {}
      resolve(ok);
    };
    sock.on('connect', () => {
      // Bencode-encoded nREPL eval op: route through
      // `shadow.cljs.devtools.api/cljs-eval` on build `:app`. Probe the
      // same runtime sentinel re-frame2-pair-mcp checks so this gate cannot pass
      // before the preload is visible through the nREPL eval path.
      const code =
        '(shadow.cljs.devtools.api/cljs-eval :app "(some? (and (exists? js/globalThis) (.-__re_frame2_pair_runtime js/globalThis)))" {})';
      // Minimal bencode hand-encode (avoid adding a dep). Op fields:
      // {"op": "eval", "code": <code>, "id": "rt-probe"}
      const dict =
        'd' +
        '2:id' + '8:rt-probe' +
        '2:op' + '4:eval' +
        '4:code' + code.length + ':' + code +
        'e';
      sock.write(dict, 'utf8');
    });
    sock.on('data', (chunk) => {
      buf = Buffer.concat([buf, chunk]);
      const txt = buf.toString('utf8');
      // Look for a `:results` frame with the sentinel expression's
      // boolean `true`. A successful trivial eval is not enough: the
      // runtime can be addressable before the preload marker is visible,
      // which makes re-frame2-pair-mcp's first `ensure-runtime!` fail with a
      // false `:runtime-not-preloaded`.
      if (txt.includes(':results') && txt.includes('true') && !txt.includes('No application')) {
        finish(true);
      } else if (txt.includes('"status"') && txt.includes('done')) {
        // Op completed but no `:results` — either an error or empty.
        finish(false);
      }
    });
    sock.on('error', () => finish(false));
    sock.on('timeout', () => finish(false));
    sock.on('close', () => finish(false));
  });
}

function probeTcp(port, hostname = '127.0.0.1') {
  return new Promise((resolve) => {
    const sock = net.connect({ host: hostname, port, timeout: 1000 }, () => {
      sock.end();
      resolve(true);
    });
    sock.on('error', () => resolve(false));
    sock.on('timeout', () => {
      sock.destroy();
      resolve(false);
    });
  });
}

async function waitUntil(label, predicate, timeoutMs) {
  const deadline = Date.now() + timeoutMs;
  while (Date.now() < deadline) {
    if (await predicate()) return true;
    await sleep(POLL_MS);
  }
  throw new Error(`timeout after ${timeoutMs}ms waiting for: ${label}`);
}

// Resolve `npm` / `npx` / etc. to a single trusted absolute path via
// PATH search, refusing any candidate that resolves under REPO_ROOT.
// Cached per-name so the PATH walk runs once. See `lib/exec-safety.cjs`
// for the rationale (Windows command-hijack accident-gating).
const TRUSTED_EXE_CACHE = new Map();
function trustedExe(name) {
  if (TRUSTED_EXE_CACHE.has(name)) return TRUSTED_EXE_CACHE.get(name);
  const resolved = resolveTrustedExe(name, { workspaceRoot: REPO_ROOT });
  TRUSTED_EXE_CACHE.set(name, resolved);
  return resolved;
}

// Hard cap on any trusted SETUP command (fixture `npm install`). Generous
// enough for a cold-cache install of the tiny fixture's deps on a slow CI
// runner, but bounded so a wedged package-manager child can't wedge the
// whole hermetic run. Distinct from
// `HERMETIC_TIMEOUT_MS` (the whole-run cap) — this is the per-setup-command
// cap that the whole-run watchdog physically CANNOT enforce while a
// synchronous child blocks the event loop.
//
// `$HERMETIC_SETUP_TIMEOUT_MS` overrides the cap for the
// setup-timeout regression harness ONLY (`hermetic-setup-timeout.test.cjs`
// drives `runTrusted` against a never-exiting child under a tiny cap to
// prove the timeout/kill path fires and the loop stays live). Production
// CI never sets it, so the 300s cap stands.
const SETUP_COMMAND_TIMEOUT_MS =
  Number(process.env.HERMETIC_SETUP_TIMEOUT_MS) || 300_000;

// Grace between the timeout SIGTERM and the SIGKILL escalation for a hung
// setup child. A well-behaved child reaps on SIGTERM within
// this window; a SIGTERM-ignoring child gets SIGKILLed after it. This grace
// is AWAITED (not a fire-and-forget `setTimeout` that the reject could
// cancel), so a setup child that ignores SIGTERM is GUARANTEED to receive
// SIGKILL before `runTrusted` rejects. `$HERMETIC_SETUP_SIGKILL_GRACE_MS`
// shrinks it for the regression harness (`hermetic-setup-timeout.test.cjs`)
// so the SIGTERM-ignoring arm runs fast; production CI never sets it.
const SETUP_SIGKILL_GRACE_MS =
  Number(process.env.HERMETIC_SETUP_SIGKILL_GRACE_MS) || 5_000;

// Run a trusted setup command (resolved to an absolute path outside the
// workspace via `trustedExe`) under an ASYNC spawn with an explicit
// child-level timeout/kill.
//
// The spawn is ASYNC (not `crossSpawn.sync`): a synchronous spawn would
// SYNCHRONOUSLY block the Node event loop for the child's entire lifetime.
// While blocked, Node delivers no signals and runs no timers — so the
// `HERMETIC_TIMEOUT_MS` `setTimeout` watchdog (and the SIGINT/SIGTERM
// handlers) could NOT fire, and a hung `npm install` (stuck registry
// fetch, a package-manager prompt, a lock contention) would wedge the
// whole hermetic job until the OUTER CI job timeout, bypassing this
// script's own hard time-cap and diagnostics. The inner-test spawn uses
// the same async shape for the same reason.
//
// The async spawn keeps the event loop live, so both the per-command
// timeout below AND the whole-run watchdog stay armed. On timeout we
// SIGTERM the child, AWAIT its exit (bounded by `SETUP_SIGKILL_GRACE_MS`),
// SIGKILL it if it ignored SIGTERM, then reject with an orchestration error
// — surfacing as exit 2 (orchestration failure) shortly after
// `SETUP_COMMAND_TIMEOUT_MS` rather than the multi-minute CI job timeout.
//
// The SIGTERM→SIGKILL escalation is an AWAITED sequence (the same shape
// the teardown `makeCleanup` uses): the child is reaped (or has
// demonstrably received SIGKILL) BEFORE the timeout promise rejects, and
// the reject does not cancel the kill. A setup child that ignores SIGTERM
// is therefore guaranteed to receive SIGKILL — the orchestrator never
// reports "setup command hung — killed" while leaking a still-alive
// npm/node child that holds locks/pipes. The happy path still settles
// promptly the moment the child exits normally.
function runTrusted(name, args, cwd) {
  const bin = trustedExe(name);
  // cross-spawn (async) handles the `.cmd` -> cmd.exe dispatch on Windows
  // without re-introducing PATH/cwd lookup ambiguity — see the module
  // comment at the top of this file for the contract. Passing the
  // absolute path is what keeps cross-spawn's `which` from doing its own
  // cwd-relative walk.
  log(`running ${name} ${args.join(' ')} in ${cwd} (bin=${bin})`);
  return new Promise((resolve, reject) => {
    const child = crossSpawn(bin, args, {
      cwd,
      stdio: ['ignore', 'pipe', 'pipe'],
      env: process.env,
    });
    let stdout = '';
    let stderr = '';
    let settled = false;
    let childExited = false;
    let timedOut = false;

    const settle = (fn, arg) => {
      if (settled) return;
      settled = true;
      if (timer) clearTimeout(timer);
      fn(arg);
    };

    // Per-command hard cap, enforced by the live event loop (the whole
    // point of the async spawn). On elapse: SIGTERM, AWAIT the child's exit
    // bounded by a grace, SIGKILL if it ignored SIGTERM, then reject as an
    // orchestration failure.
    //
    // The escalation is an AWAITED sequence, NOT a fire-and-
    // forget `setTimeout` that `settle(reject)` could cancel. A child that
    // ignores SIGTERM is therefore GUARANTEED to receive SIGKILL before the
    // promise rejects; we never report "killed" while the child is still
    // alive and un-SIGKILLed.
    const timer = setTimeout(() => {
      logErr(
        `${name} ${args.join(' ')} exceeded SETUP_COMMAND_TIMEOUT_MS ` +
          `(${SETUP_COMMAND_TIMEOUT_MS}ms) — killing the setup child`,
      );
      // The timeout owns the rejection attribution from here: the `exit`
      // handler observes `timedOut` and defers, so the message is "timed
      // out", not "killed by <signal>" from our own SIGKILL.
      timedOut = true;
      // `void`: the IIFE owns the kill/await; it ends by `settle(reject)`ing.
      void (async () => {
        try { child.kill('SIGTERM'); } catch {}
        const exitedOnTerm = await settledWithin(
          waitForChildExit(child, () => childExited),
          SETUP_SIGKILL_GRACE_MS,
        );
        if (!exitedOnTerm && !childExited) {
          logErr(
            `${name} ${args.join(' ')} did not exit ${SETUP_SIGKILL_GRACE_MS}ms ` +
              'after SIGTERM — escalating to SIGKILL',
          );
          try { child.kill('SIGKILL'); } catch {}
          // Best-effort observe the post-SIGKILL exit so we don't reject
          // while the OS reap is still in flight. Bounded so a truly
          // unkillable child (PID-namespace edge) can't wedge the run.
          await settledWithin(
            waitForChildExit(child, () => childExited),
            SETUP_SIGKILL_GRACE_MS,
          );
        }
        // Reject with the timeout attribution. `settle` is first-wins, so if
        // the `exit` handler already rejected with "killed by SIGKILL" this
        // is a no-op; either way the child has been SIGTERM'd→SIGKILL'd, not
        // left alive.
        settle(
          reject,
          new Error(
            `${name} ${args.join(' ')} in ${cwd} timed out after ` +
              `${SETUP_COMMAND_TIMEOUT_MS}ms (setup command hung — killed)`,
          ),
        );
      })();
    }, SETUP_COMMAND_TIMEOUT_MS);
    timer.unref();

    child.stdout.on('data', (d) => {
      stdout += String(d);
      recordChunk(`[${name}:stdout] `, d);
    });
    child.stderr.on('data', (d) => {
      stderr += String(d);
      recordChunk(`[${name}:stderr] `, d, 'stderr');
    });
    child.on('error', (err) => settle(reject, err));
    child.on('exit', (code, signal) => {
      // Record the exit so the timeout path's awaited grace
      // (`waitForChildExit(child, () => childExited)`) sees a child that
      // exited before/while we waited and stops escalating.
      childExited = true;
      if (timedOut) {
        // Our own timeout SIGTERM/SIGKILL reaped it. Defer the rejection to
        // the timeout IIFE so the attribution is the timeout message, and so
        // the awaited escalation observes this exit (it `await`s
        // `waitForChildExit`, which resolves on this event).
        return;
      }
      if (signal) {
        // Signal-terminated by something OTHER than our timeout path (an
        // external SIGTERM/SIGKILL, an OOM kill). Treat as a failure so a
        // killed setup child never reads as success.
        settle(
          reject,
          new Error(
            `${name} ${args.join(' ')} in ${cwd} killed by ${signal}`,
          ),
        );
        return;
      }
      if (code !== 0) {
        settle(
          reject,
          new Error(`${name} ${args.join(' ')} in ${cwd} exited ${code}`),
        );
        return;
      }
      log(`${name} exited ${code}`);
      settle(resolve, undefined);
    });
  });
}

function resolvePlaywright() {
  // Resolve playwright either from local mcp-conformance deps or from
  // the implementation/ tree (which already lists it as a devDep). The
  // CI job installs both; locally Mike's machine likely has at least
  // one path. require.resolve throws if neither has it.
  const candidates = [MCP_CONFORMANCE_ROOT, path.join(REPO_ROOT, 'implementation')];
  for (const root of candidates) {
    try {
      const pwPath = require.resolve('playwright', { paths: [root] });
      return require(pwPath);
    } catch {
      // try next
    }
  }
  throw new Error(
    'playwright is not resolvable from tools/mcp-conformance or implementation/. ' +
      'Run `npm install` in one of those directories first.',
  );
}

async function main() {
  // ---- Sanity: required artefacts on disk -------------------------------
  if (!exists(path.join(FIXTURE_DIR, 'shadow-cljs.edn'))) {
    throw new Error(`fixture missing: ${FIXTURE_DIR}`);
  }
  for (const test of INNER_TESTS) {
    if (!exists(test.path)) {
      throw new Error(`live test missing: ${test.path}`);
    }
  }
  if (!exists(path.join(RE_FRAME2_PAIR_MCP_DIR, 'out', 'server.js'))) {
    throw new Error(
      `re-frame2-pair-mcp server bundle missing: ${path.join(RE_FRAME2_PAIR_MCP_DIR, 'out', 'server.js')}. ` +
        'Compile with `npx shadow-cljs compile server` in tools/re-frame2-pair-mcp first.',
    );
  }

  // ---- Wipe any stale port files ---------------------------------------
  // A leftover port file from a previous run could otherwise satisfy
  // the poll-loop before shadow-cljs has actually re-bound to the port,
  // and the subsequent nREPL connect would race. The shadow-cljs watch
  // child will rewrite the appropriate file as part of its boot. Wipe
  // ALL candidate paths so a stale entry at one location can't shadow
  // the fresh file at another.
  //
  // Route every unlink through `safeUnlinkInside` so a
  // symlinked candidate (or symlinked parent directory) that escapes
  // FIXTURE_DIR can't be coerced into deleting a file outside the
  // fixture tree.
  //
  // A symlink-ESCAPE refusal on a load-bearing stale
  // port candidate is FATAL — we do NOT log-and-continue and then later
  // read that same escaped file as the live nREPL source. A BENIGN unlink
  // failure (EACCES / EBUSY — a Windows file lock, a permission quirk)
  // is tolerated: it doesn't widen trust, and the read path
  // re-checks containment regardless. `isContainmentEscape` discriminates
  // the two so a transient lock doesn't abort the whole run while a real
  // escape does.
  for (const p of NREPL_PORT_FILE_CANDIDATES) {
    try {
      const removed = safeUnlinkInside(p, FIXTURE_DIR);
      if (removed) log(`removed stale port file ${p}`);
    } catch (e) {
      if (isContainmentEscape(e)) {
        throw new Error(
          `stale nREPL port candidate ${p} escapes FIXTURE_DIR ` +
            `(${e.message}); aborting — the runner must not continue and ` +
            'later trust a port file it refused to clean (rf2-khav7l).',
        );
      }
      log(`could not remove stale port file ${p} (${e.message}); continuing`);
    }
  }
  try {
    const removed = safeUnlinkInside(FIXTURE_BUNDLE_PATH, FIXTURE_DIR);
    if (removed) log(`removed stale fixture bundle ${FIXTURE_BUNDLE_PATH}`);
  } catch (e) {
    if (isContainmentEscape(e)) {
      throw new Error(
        `stale fixture bundle ${FIXTURE_BUNDLE_PATH} escapes FIXTURE_DIR ` +
          `(${e.message}); aborting (rf2-khav7l).`,
      );
    }
    log(`could not remove stale fixture bundle ${FIXTURE_BUNDLE_PATH} (${e.message}); continuing`);
  }

  // ---- Install fixture deps --------------------------------------------
  // `await` the async spawn: the setup command runs under a live event
  // loop with its own hard timeout, so a hung `npm install` is bounded by
  // `SETUP_COMMAND_TIMEOUT_MS` instead of wedging the whole run past the
  // outer CI job cap.
  if (!exists(path.join(FIXTURE_DIR, 'node_modules'))) {
    log(`installing fixture deps in ${FIXTURE_DIR}`);
    await runTrusted('npm', ['install', '--no-audit', '--no-fund'], FIXTURE_DIR);
  }

  // ---- Boot shadow-cljs watch ------------------------------------------
  // Resolve `npx` to a trusted absolute path (rejected if it lives
  // inside REPO_ROOT) and route the spawn through cross-spawn so the
  // `.cmd`-on-Windows shape works without re-introducing the shell.
  // Passing the trusted absolute path (rather than a bare `npx.cmd` with
  // `shell: true` + `cwd = FIXTURE_DIR`) means a fixture-local `npx.cmd`
  // that ever landed in the checkout can never be executed.
  const npxBin = trustedExe('npx');
  log(`spawning shadow-cljs watch app in ${FIXTURE_DIR} (npx=${npxBin})`);
  const shadow = crossSpawn(npxBin, ['shadow-cljs', 'watch', 'app'], {
    cwd: FIXTURE_DIR,
    stdio: ['ignore', 'pipe', 'pipe'],
    env: { ...process.env, FORCE_COLOR: '0' },
  });
  let shadowExited = false;
  shadow.on('exit', (code, sig) => {
    shadowExited = true;
    log(`shadow-cljs exited code=${code} sig=${sig}`);
  });
  shadow.stdout.on('data', (d) => {
    recordChunk('[shadow:stdout] ', d);
  });
  shadow.stderr.on('data', (d) => {
    recordChunk('[shadow:stderr] ', d, 'stderr');
  });

  let browser = null;

  // Idempotent async teardown. `makeCleanup` (module scope, unit-tested by
  // `runner-cleanup.test.cjs`) returns an idempotent promise that AWAITS
  // the browser close (bounded) and SIGTERM→exit→SIGKILL of shadow, so
  // every caller (`finally`, signal handlers, the hard watchdog) waits for
  // the browser-close promise to settle and for shadow-cljs to actually
  // exit before `process.exit` — the children the teardown exists to reap
  // are reaped, not abandoned. `getBrowser`/`getShadow` read the live
  // closure vars so the teardown sees `browser` even though it's assigned
  // later in `main()`.
  const cleanup = makeCleanup({
    getBrowser: () => browser,
    getShadow: () => shadow,
    hasShadowExited: () => shadowExited,
  });
  // Expose the teardown to the module-scope hard watchdog
  // so a watchdog-elapse kills shadow-cljs + Chromium rather than
  // orphaning them.
  activeCleanup = cleanup;
  for (const sig of ['SIGINT', 'SIGTERM', 'SIGHUP']) {
    process.on(sig, () => {
      logErr(`caught ${sig} — tearing down`);
      // Race the async cleanup against a hard cap: an interrupted run still
      // exits promptly (CLEANUP_HARD_CAP_MS) even if a child refuses to die,
      // while still giving the teardown a real chance to settle before the
      // process exits.
      settledWithin(cleanup(), CLEANUP_HARD_CAP_MS).then((settled) => {
        if (!settled) {
          logErr(
            `cleanup did not complete within ${CLEANUP_HARD_CAP_MS}ms of ` +
              `${sig} — exiting anyway`,
          );
        }
        flushDiagnostics();
        process.exit(130);
      });
    });
  }

  try {
    // ---- Wait for nREPL port file ---------------------------------------
    log(
      `waiting for shadow-cljs nREPL port file; candidates: ${
        NREPL_PORT_FILE_CANDIDATES.join(', ')
      }`,
    );
    let port = null;
    await waitUntil(
      'nREPL port file',
      async () => {
        if (shadowExited) {
          throw new Error('shadow-cljs exited before binding nREPL port');
        }
        const hit = readPortFile();
        if (hit) {
          port = hit.port;
          log(`nREPL port file appeared at ${hit.source}`);
          return true;
        }
        return false;
      },
      SHADOW_BOOT_TIMEOUT_MS,
    );
    log(`nREPL bound to port ${port}`);

    // ---- Wait for nREPL TCP listener actually accepting -----------------
    await waitUntil(
      `nREPL TCP listener on :${port}`,
      () => probeTcp(port),
      SHADOW_BOOT_TIMEOUT_MS,
    );
    log(`nREPL TCP accepting on :${port}`);

    // ---- Wait for http server -------------------------------------------
    await waitUntil(
      `fixture http on :${FIXTURE_HTTP_PORT}`,
      () => probeHttp(FIXTURE_HTTP_PORT),
      SHADOW_BOOT_TIMEOUT_MS,
    );
    log(`fixture http reachable at ${FIXTURE_URL}`);

    // ---- Wait for the :app bundle to actually compile -------------------
    // shadow-cljs `watch` writes the nREPL port file and starts the
    // dev-http server BEFORE the first compile completes. The fixture's
    // public/index.html references `/out/main.js`; if Chromium navigates
    // before that file exists the bundle 404s, no CLJS runs, and the
    // preload sentinel never lands. We poll the asset URL until it
    // returns 200, then navigate — so navigation always happens against a
    // compiled bundle, and the sentinel wait can succeed. The first cold
    // compile on CI runs 10–20s after the watch is up; rebuilds on a warm
    // cache are <1s.
    await waitUntil(
      `fixture bundle at ${FIXTURE_URL}out/main.js`,
      () => probeBundleReady(FIXTURE_HTTP_PORT),
      SHADOW_BOOT_TIMEOUT_MS,
    );
    log('fixture bundle compiled and served');

    // ---- Launch headless Chromium + load page ---------------------------
    const playwright = resolvePlaywright();
    log('launching headless Chromium');
    browser = await playwright.chromium.launch({ headless: true });
    const context = await browser.newContext();
    const page = await context.newPage();
    log(`browser URL ${FIXTURE_URL}`);
    page.on('console', (msg) => {
      recordLine(`[browser:${msg.type()}] ${msg.text()}`);
    });
    page.on('pageerror', (err) => {
      recordLine(`[browser:pageerror] ${err.message}`, 'stderr');
      if (err.stack) recordLine(err.stack, 'stderr');
    });
    page.on('framenavigated', (frame) => {
      if (frame === page.mainFrame()) {
        recordLine(`[browser:navigation] ${frame.url()}`);
      }
    });
    await page.goto(FIXTURE_URL, { waitUntil: 'load' });
    log('page loaded');

    // ---- Wait for the runtime sentinel ----------------------------------
    // The preload mirrors itself onto js/globalThis.__re_frame2_pair_runtime
    // at load time. This is exactly what re-frame2-pair-mcp probes via
    // ensure-runtime!; if it's not present, eval-cljs returns
    // :reason :runtime-not-preloaded and the overflow path never trips.
    await page.waitForFunction(
      () => typeof window.__re_frame2_pair_runtime !== 'undefined',
      undefined,
      { timeout: RUNTIME_PRELOAD_TIMEOUT_MS },
    );
    const sentinel = await page.evaluate(() => window.__re_frame2_pair_runtime);
    log(`runtime preload sentinel = ${JSON.stringify(sentinel)}`);

    // ---- Wait for shadow to register the browser runtime ----------------
    // re-frame2-pair-mcp routes its preload-probe through `shadow.cljs.devtools.api/
    // cljs-eval :app ...` over the nREPL. Shadow dispatches that to
    // whichever CLJS runtime is currently connected for the build. The
    // browser's runtime registers via the shadow devtools WebSocket on
    // page load, but there's a brief window between page-load and the
    // websocket handshake during which `cljs-eval` returns
    // "No application has connected to the REPL server. Make sure your
    // JS environment has loaded your compiled ClojureScript code." —
    // which `runtime-preloaded?` catches and surfaces as
    // `:runtime-not-preloaded` (the .catch in `tools/probe.cljs`
    // swallows the underlying nREPL error). Poll the same probe re-frame2-pair-mcp
    // uses until it returns true, so we hand off to the live test only
    // after the runtime is actually addressable.
    log('waiting for shadow :app runtime to register');
    await waitUntil(
      'shadow :app runtime addressable via cljs-eval',
      () => probeShadowRuntimeReady(port),
      RUNTIME_PRELOAD_TIMEOUT_MS,
    );
    log('shadow :app runtime addressable');

    // ---- Run each inner test sequentially --------------------------------
    // Each test inherits the spawned `SHADOW_CLJS_NREPL_PORT` so its
    // SKIP gate flips off and the live path fires against the same
    // booted runtime. Sequential execution keeps cold-boot cost
    // amortised; tests are short relative to shadow-cljs boot.
    //
    // Spawn ASYNC, not via `crossSpawn.sync`.
    // The sync form synchronously blocks the event loop for the inner
    // test's entire watchdog window — during which the
    // `SIGINT`/`SIGTERM`/`SIGHUP` handlers wired above CANNOT fire (Node
    // delivers signals only between event-loop iterations) and the
    // outer `HERMETIC_TIMEOUT_MS` watchdog `setTimeout` CANNOT trip. A
    // hang inside an inner test would wedge the orchestrator
    // unresponsive for ~30-60s before any outer cleanup gets control.
    // The async-spawn shape preserves signal responsiveness end-to-end.
    //
    // `process.execPath` is always an absolute path to the currently-
    // running node binary; it's outside the workspace by construction.
    // No PATH walk is performed by cross-spawn for absolute paths
    // (see `which`'s separator short-circuit), so this stays
    // accident-safe.
    const testEnv = {
      ...process.env,
      SHADOW_CLJS_NREPL_PORT: String(port),
    };
    for (const test of INNER_TESTS) {
      const testFile = path.basename(test.path);
      log(`running ${testFile} - ${test.name}`);
      // Capture the inner test's stdout so we can assert it actually RAN
      // (printed its GREEN sentinel) — not merely exited 0 (a SKIP also
      // exits 0).
      let stdoutText = '';
      const testStatus = await new Promise((resolve, reject) => {
        const child = crossSpawn(process.execPath, [test.path], {
          cwd: MCP_CONFORMANCE_ROOT,
          stdio: ['ignore', 'pipe', 'pipe'],
          env: testEnv,
        });
        child.stdout.on('data', (d) => {
          stdoutText += String(d);
          recordChunk(`[${testFile}:stdout] `, d);
        });
        child.stderr.on('data', (d) => recordChunk(`[${testFile}:stderr] `, d, 'stderr'));
        child.on('error', reject);
        child.on('exit', (code, signal) => {
          // signal-terminated children report null exit codes; treat
          // the signal as a non-zero status so the conformance gate
          // fails loud rather than silently passing.
          if (code === null) {
            log(`${testFile} killed by ${signal}`);
            reject(new Error(
              `${path.basename(test.path)} killed by ${signal}`,
            ));
            return;
          }
          log(`${testFile} exited ${code}`);
          resolve(code);
        });
      });
      if (testStatus !== 0) {
        // Surface the inner test's exit code verbatim so CI sees a
        // conformance failure as exit 1 (the test's own code) rather
        // than 2 (which we reserve for orchestration failures —
        // shadow-cljs didn't boot, runtime didn't preload, etc.).
        const err = new Error(
          `${path.basename(test.path)} exited ${testStatus}`,
        );
        err.exitCode = testStatus;
        throw err;
      }
      // ---- Observable-SKIP guard ------------------------------------------
      // The inner test exited 0 — but a SKIP also exits 0. The hermetic
      // env sets $SHADOW_CLJS_NREPL_PORT, so the inner test's SKIP gate
      // MUST NOT have fired. Assert it printed its success sentinel AND
      // did NOT print a SKIP banner. A broken setup path that left the
      // test SKIPping (port-file probe / bundle-compile / runtime-sentinel
      // wait silently regressed in a way that didn't propagate the env)
      // would otherwise leave this load-bearing gate UN-EXERCISED while
      // the hermetic job stayed green on the other inner tests. This is
      // an ORCHESTRATION failure (exit 2): the contract didn't fail, the
      // setup did.
      if (stdoutText.includes('\nSKIP ') || stdoutText.startsWith('SKIP ')) {
        throw new Error(
          `${testFile} SKIPped inside the hermetic orchestrator (exit 0) — ` +
            'but the hermetic env guarantees $SHADOW_CLJS_NREPL_PORT is set, ' +
            'so a SKIP here means the setup path regressed and left this ' +
            'load-bearing live gate UN-EXERCISED (a silent SKIP would ship ' +
            'green via the other inner tests). rf2-ybiz0. Inner stdout tail: ' +
            stdoutText.slice(-400),
        );
      }
      if (test.sentinel && !stdoutText.includes(test.sentinel)) {
        throw new Error(
          `${testFile} exited 0 but did NOT print its success sentinel ` +
            `("${test.sentinel}"). The live gate did not actually run to ` +
            'completion (a SKIP, an early return, or a truncated run). ' +
            'rf2-ybiz0 requires each inner test to PROVE it ran, not just ' +
            'exit 0. Inner stdout tail: ' + stdoutText.slice(-400),
        );
      }
      log(`${testFile} ran to completion (sentinel observed)`);
    }
    log('RE-FRAME2-PAIR-MCP LIVE HERMETIC CONFORMANCE GREEN (' +
      INNER_TESTS.length + ' inner tests)');
  } finally {
    // Await the async teardown before `main()` resolves/rejects: the
    // normal-exit path must not report success and `process.exit(0)` while
    // the browser-close promise is still settling or shadow-cljs has not
    // yet exited.
    await cleanup();
  }
}

// Hard watchdog: if the orchestrator hangs past this, kill the process
// so CI gets a deterministic failure instead of waiting on the job
// timeout. Length set to cover cold Maven cache + cold chromium boot.
//
// On elapse it tears down shadow-cljs + Chromium BEFORE exiting, rather
// than calling `process.exit(2)` directly: a direct exit would orphan the
// spawned JVM (and any launched Chromium), and orphans that inherited the
// step's stdio can keep the CI step's log pipes open past the node exit,
// making the gate appear to hang well past the orchestrator's own cap.
//
// `activeCleanup` is async (awaited browser.close + SIGTERM→exit→SIGKILL
// of shadow). The watchdog RACES the async cleanup against
// `CLEANUP_HARD_CAP_MS`: the teardown gets a real chance to reap the
// children, but the process still exits within the cap if a child refuses
// to die. `activeCleanup` is null only if the watchdog fires before
// `main()` has spawned the children (nothing to reap), so we exit straight
// away in that case.
const watchdog = setTimeout(() => {
  logErr(`watchdog timeout (${HERMETIC_TIMEOUT_MS}ms) — bailing`);
  const bail = () => {
    flushDiagnostics();
    process.exit(2);
  };
  if (activeCleanup) {
    settledWithin(
      (async () => { try { await activeCleanup(); } catch {} })(),
      CLEANUP_HARD_CAP_MS,
    ).then((settled) => {
      if (!settled) {
        logErr(
          `cleanup did not complete within ${CLEANUP_HARD_CAP_MS}ms of the ` +
            'watchdog elapse — exiting anyway',
        );
      }
      bail();
    });
  } else {
    bail();
  }
}, HERMETIC_TIMEOUT_MS);
watchdog.unref();

// Only auto-run the orchestrator when invoked as the entry-point. Required
// as a module (by the `runTrusted` regression test),
// it exports the unit under test WITHOUT kicking off the whole hermetic run
// (which would spawn shadow-cljs + Chromium). Guarding the run here keeps
// the watchdog timer from arming on `require` too.
if (require.main === module) {
  main()
    .then(() => {
      clearTimeout(watchdog);
      console.log(
        `RE-FRAME2-PAIR-MCP live hermetic conformance passed (${INNER_TESTS.length} inner tests).`,
      );
      process.exit(0);
    })
    .catch((err) => {
      clearTimeout(watchdog);
      logErr('FAIL: ' + (err && err.message ? err.message : err));
      if (err && err.stack) logErr(err.stack);
      flushDiagnostics();
      // err.exitCode is set when the inner live-re-frame2-pair-overflow.cjs itself
      // exited non-zero — surface it so CI distinguishes conformance
      // failure (1) from orchestration failure (2).
      process.exit(err && typeof err.exitCode === 'number' ? err.exitCode : 2);
    });
} else {
  // Required as a module — don't arm the whole-run watchdog.
  clearTimeout(watchdog);
}

// Exported for the setup-command regression harness.
// `runTrusted` is the async, timeout-bounded setup-command spawn; the test
// drives it against (a) a never-exiting child to prove a hung setup command
// is killed within `SETUP_COMMAND_TIMEOUT_MS` instead of wedging the event
// loop, and (b) a SIGTERM-IGNORING child to prove the timeout path AWAITS
// the SIGTERM grace then SIGKILLs it (the reject does not cancel the
// fallback) so the child is actually reaped, not leaked.
//
// `readPortFile` + `isContainmentEscape` are exported for the
// call-site regression harness (`port-file-escape.test.cjs`): it drives
// `readPortFile` against a temp fixture whose `.shadow-cljs` is symlinked
// outside, proving the poller REFUSES an external port file (throws)
// rather than raw-reading it — the read-side guarantee that lets the
// cleanup loop's escape-refusal be safe.
//
// `makeCleanup` + `settledWithin` + `waitForChildExit` are exported for the
// teardown regression harness (`runner-cleanup.test.cjs`): it
// drives the REAL teardown against a fake promise-returning browser and a
// slow-exiting fake child, proving the awaited browser-close + the
// SIGTERM→exit→SIGKILL escalation are WAITED for (or hard-capped), never
// fire-and-forgotten.
module.exports = {
  runTrusted,
  SETUP_COMMAND_TIMEOUT_MS,
  readPortFile,
  isContainmentEscape,
  makeCleanup,
  settledWithin,
  waitForChildExit,
};
