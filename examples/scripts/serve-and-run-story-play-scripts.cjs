#!/usr/bin/env node
/*
 * Story `:play-script` CI-as-test runner.
 *
 * Compiles the counter-with-stories Story testbed, serves it, opens
 * the Story shell in Playwright, enumerates every registered variant
 * whose body carries a non-empty `:play-script` or `:plays` slot (via
 * the `window.__rf2_story_ci` global installed by
 * `re-frame.story.play.ci-runner/install-ci-hooks!`), navigates the
 * shell to each variant, waits for each play's terminal status
 * (`:pass` / `:fail`), and prints a per-play report.
 *
 * Multi-play
 * ----------
 * The runner consumes `ciContext().rows` — one row per PLAY. A variant
 * declaring `:plays` of size N produces N rows; a single-script
 * `:play-script` variant produces ONE row. The runner navigates once
 * per variant, then triggers each non-auto-run play via the CI hook's
 * `runPlay(variantId, playKey)` entry-point. Per-play terminal state
 * is read via `readPlayRunState(variantId, playKey)`.
 *
 * EXPECTED-FAIL contract
 * ----------------------
 * Authoring a deliberately-failing variant / play is the normal way
 * to gate Story's failure path under CI. The runner reads the expected
 * status from the variant id AND the play key — either token can mark
 * the row as expected-fail:
 *
 *   - any variant id or play-key containing `failing` / `expected-fail`
 *     is treated as expected-fail; the runner asserts `:fail`
 *   - everything else asserts `:pass`
 *
 * The seeded fixtures in the testbed:
 *   :story.counter-play-script/passing            → expects :pass
 *   :story.counter-play-script/failing            → expects :fail
 *
 * Exit code: 0 if every play matched its expected status; 1 if any
 * play deviated, if an uncaught browser `pageerror` fired (rf2-wf5al),
 * OR if play-script discovery was VACUOUS (rf2-54xbp) — zero / a
 * near-empty / a one-sided (no expected-fail) row set fails closed,
 * because the testbed seeds fixtures precisely to keep both the pass and
 * the expected-fail paths under continuous coverage, so an empty gate is
 * a discovery/registration/lowering drift signal, not "nothing to
 * assert".
 */

'use strict';

const { spawnSync } = require('child_process');
const fs = require('fs');
const path = require('path');
const {
  createHarnessCleanup,
  spawnHarnessProcess,
  waitForHttpReady,
} = require('../../implementation/scripts/lib/local-browser-harness.cjs');
const { resolveStoryFeatureLoadPort } = require('./story-feature-load-port.cjs');
const { cleanStageDirs } = require('./examples-staging.cjs');

const REPO_ROOT = path.resolve(__dirname, '..', '..');
const IMPL_ROOT = path.join(REPO_ROOT, 'implementation');
const OUT_ROOT = path.join(IMPL_ROOT, 'out', 'examples');
// rf2-315cf: on a RED gate, persist the per-row run evidence the runner
// already computed to a stable path so the browser-gate workflow can
// upload it as a downloadable post-mortem (spec/017
// §Failed-run artifacts in CI). The substrate guarantees the low-level
// `:rf.test/run-artifact` (seed / event-program / epoch tape) EXISTS and
// is canonicalizable, but the play-scripts CI hook surfaces only the
// projected run-state (status + per-step results) — that projection is
// the failure record this gate genuinely holds, and serialising the
// full replayable artifact is a substrate change, not CI mechanics.
const FAILURE_REPORT_DIR = path.join(IMPL_ROOT, 'out', 'story-play-scripts');
const FAILURE_REPORT_PATH = path.join(
  FAILURE_REPORT_DIR,
  'failure-report.json',
);
const TESTBED_BUILD = 'examples/counter-with-stories';
const TESTBED_DIR_NAME = 'counter-with-stories';
const TESTBED_HTML = path.join(
  REPO_ROOT,
  'tools',
  'story',
  'testbeds',
  'counter_with_stories',
  'index.html',
);
const TESTBED_OUT = path.join(OUT_ROOT, TESTBED_DIR_NAME);
// Path-only (no hash) so we can compose `?variant=...#/stories` URLs
// per the share-link convention — query params MUST come BEFORE the
// hash route or `window.location.search` will be empty and the share
// hydrator will skip the variant select.
const TESTBED_BASE = `/${TESTBED_DIR_NAME}/`;
const STORY_FRAGMENT = '#/stories';
const READY_TIMEOUT_MS = 30000;
const TERMINAL_TIMEOUT_MS = Number(
  process.env.STORY_PLAY_SCRIPT_TERMINAL_TIMEOUT_MS || 30000,
);
const VERBOSE = process.env.RF2_VERBOSE_TESTS === '1';

// Resolve the browser toolchain lazily (inside main()) rather than at
// module top-level, so the script-policy unit test can `require(...)`
// this module for its pure helpers without http-server / playwright
// installed (rf2-wf5al). These are only ever used by the harness path.
function httpServerBin() {
  return require.resolve('http-server/bin/http-server', { paths: [IMPL_ROOT] });
}
function loadChromium() {
  return require(require.resolve('playwright', { paths: [IMPL_ROOT] })).chromium;
}
// Resolve shadow-cljs's JS entry-point lazily (inside compileTestbed)
// for the same reason as httpServerBin/loadChromium: the script-policy
// unit test require()s this module for its pure helpers without the
// browser/build toolchain installed. compileTestbed() spawns the
// resolved runner shell-free under process.execPath — never
// npx/npx.cmd/cmd.exe (the Windows command-hijack accident class).
// rf2-y9o5e3; matches story-build.cjs / dev-testbed.cjs.
function shadowCljsRunner() {
  try {
    return require.resolve('shadow-cljs/cli/runner.js', { paths: [IMPL_ROOT] });
  } catch {
    throw new Error(
      'serve-and-run-story-play-scripts: could not resolve shadow-cljs. ' +
        `Run \`npm install\` in ${IMPL_ROOT} first.`,
    );
  }
}

const cleanup = createHarnessCleanup();
cleanup.installSignalHandlers();

// Toggled true once `main()` resolves to its exit code, so the
// http-server's forced-shutdown `exit` event doesn't print a noisy
// "exited unexpectedly" line during normal teardown.
let exiting = false;

function log(line) {
  process.stdout.write(`${line}\n`);
}

// Clean-stage boundary (rf2-bf4vdy): remove + recreate the testbed's output
// dir BEFORE shadow-cljs compiles into it, so every served file is produced
// from the current source this run — no stale file from a previous run can
// satisfy a browser request the current testbed no longer produces. Cleans
// only this one dir (not the shared OUT_ROOT); the helper path-guards the
// target to live strictly under OUT_ROOT.
function cleanTestbedOutDir() {
  cleanStageDirs([TESTBED_OUT], OUT_ROOT);
}

function compileTestbed() {
  // Spawn the resolved shadow-cljs JS entry-point under THIS node binary,
  // shell-free (rf2-y9o5e3). Quiet-on-success: capture output and surface
  // it only on failure (or under RF2_VERBOSE_TESTS).
  const args = [shadowCljsRunner(), 'compile', TESTBED_BUILD];
  const result = spawnSync(process.execPath, args, {
    cwd: IMPL_ROOT,
    encoding: 'utf8',
  });
  const output = `${result.stdout || ''}${result.stderr || ''}`;
  if (VERBOSE || result.status !== 0) {
    process.stdout.write(output);
  }
  if (result.status !== 0) {
    console.error(`> shadow-cljs compile ${TESTBED_BUILD}`);
    throw new Error(`shadow-cljs compile failed (exit ${result.status})`);
  }
}

function stageTestbedHtml() {
  if (!fs.existsSync(TESTBED_OUT)) {
    throw new Error(`Build output dir missing: ${TESTBED_OUT}`);
  }
  if (!fs.existsSync(TESTBED_HTML)) {
    throw new Error(`HTML source missing: ${TESTBED_HTML}`);
  }
  fs.copyFileSync(TESTBED_HTML, path.join(TESTBED_OUT, 'index.html'));
}

/**
 * Classify the expected terminal status for a (variant-id, play-key)
 * pair. Authors mark a fixture as expected-fail by including `failing`
 * or `expected-fail` in the variant id OR the play's :name; everything
 * else defaults to expected-pass.
 *
 * Symmetric with re-frame.story.play.ci-runner/play-script-summary:
 * the classification is over identifiers (not body content), so the
 * runner can pre-compute the verdict before the Story shell boots.
 *
 * Accepts an optional `playKey` so multi-play rows can mark a
 * specific play as expected-fail without forcing the entire variant
 * id to carry the marker.
 */
function expectedStatusFor(variantId, playKey) {
  const re = /(?:failing|expected[-_]fail)/;
  if (re.test(String(variantId).toLowerCase())) return 'fail';
  if (playKey && re.test(String(playKey).toLowerCase())) return 'fail';
  return 'pass';
}

function variantIdToParam(idStr) {
  // The CI hook serialises `:story.foo/bar` as `"story.foo/bar"` (no
  // leading colon); the Playwright runner reads that string back to
  // build the URL search-param value the Story shell expects. This is
  // a defensive String() coercion — NOT a keyword conversion — so the
  // already-string id flows straight into encodeURIComponent below.
  return String(idStr);
}

/**
 * Navigate the Story shell to `variantId` using the same hash route
 * the human shell uses (`#/stories?variant=story.foo/bar`). The
 * search params are placed AFTER the hash because the shell parses
 * them out of `window.location.hash`.
 *
 * Falls back to the share-link affordance (`select-variant` event)
 * if the URL parse path is unreachable.
 */
async function navigateToVariant(page, baseUrl, variantId) {
  const variantStr = variantIdToParam(variantId);
  // Share-link convention: search params BEFORE the hash route, so
  // `window.location.search` carries `?variant=...` and the share
  // hydrator picks it up on shell mount.
  const target = `${baseUrl}${TESTBED_BASE}?variant=${encodeURIComponent(variantStr)}${STORY_FRAGMENT}`;
  // Always full-load (not reload) — each variant gets a fresh shell
  // mount so the auto-run fires deterministically without inheriting
  // previous run-state.
  await page.goto(target, { waitUntil: 'load' });
  await page.evaluate(() => {
    try {
      localStorage.setItem('re-frame.story/seen-help-v1', '1');
    } catch (_) {
      /* ignore */
    }
  });
}

async function readRunStateOnce(page, variantId) {
  return page.evaluate((vid) => {
    const ci = window.__rf2_story_ci;
    if (!ci || typeof ci.readRunState !== 'function') return null;
    try {
      return ci.readRunState(vid);
    } catch (e) {
      return { error: String(e && e.message ? e.message : e) };
    }
  }, variantIdToParam(variantId));
}

/**
 * Per-play state read for multi-play rows.
 */
async function readPlayRunStateOnce(page, variantId, playKey) {
  return page.evaluate(
    ({ vid, pk }) => {
      const ci = window.__rf2_story_ci;
      if (!ci || typeof ci.readPlayRunState !== 'function') return null;
      try {
        return ci.readPlayRunState(vid, pk || '');
      } catch (e) {
        return { error: String(e && e.message ? e.message : e) };
      }
    },
    { vid: variantIdToParam(variantId), pk: playKey || '' },
  );
}

/**
 * Shared terminal-status predicate. Symmetric with the CLJS
 * `re-frame.story.play.ci-runner/terminal?` (spec/017 §`:cannot-run`):
 * a run is terminal once its status is `pass`, `fail`, OR `cannot-run`.
 *
 * rf2-wf5al(3): `cannot-run` is a genuine terminal run status (the
 * runner core finishes a play as cannot-run when every unmet step is a
 * capability/refusal row). Treating only pass/fail as terminal made an
 * honest cannot-run look like a hang — the wait loops below burned the
 * full STORY_PLAY_SCRIPT_TERMINAL_TIMEOUT_MS per affected row before
 * the (no-state-change) timeout surfaced the failure. Accepting it here
 * returns immediately so `expectedStatusFor` can mark it as an
 * unexpected outcome (it never equals the expected pass/fail) and the
 * refusal is reported at once.
 */
function isTerminalStatus(status) {
  return status === 'pass' || status === 'fail' || status === 'cannot-run';
}

async function waitForTerminalState(page, variantId) {
  const deadline = Date.now() + TERMINAL_TIMEOUT_MS;
  let last = null;
  while (Date.now() < deadline) {
    last = await readRunStateOnce(page, variantId);
    if (last && isTerminalStatus(last.status)) {
      return last;
    }
    await new Promise((r) => setTimeout(r, 100));
  }
  return last;
}

/**
 * Wait for the per-(variantId, playKey) terminal state.
 */
async function waitForPlayTerminalState(page, variantId, playKey) {
  const deadline = Date.now() + TERMINAL_TIMEOUT_MS;
  let last = null;
  while (Date.now() < deadline) {
    last = await readPlayRunStateOnce(page, variantId, playKey);
    if (last && isTerminalStatus(last.status)) {
      return last;
    }
    await new Promise((r) => setTimeout(r, 100));
  }
  return last;
}

/**
 * Trigger a specific play to run. The CI hook's `runPlay` picks the
 * spec off the variant body and drives the runner.
 */
async function triggerPlay(page, variantId, playKey) {
  return page.evaluate(
    ({ vid, pk }) => {
      const ci = window.__rf2_story_ci;
      if (!ci || typeof ci.runPlay !== 'function') return false;
      try {
        ci.runPlay(vid, pk || '');
        return true;
      } catch (e) {
        return false;
      }
    },
    { vid: variantIdToParam(variantId), pk: playKey || '' },
  );
}

async function discoverVariants(page) {
  return page.evaluate(() => {
    const ci = window.__rf2_story_ci;
    if (!ci || typeof ci.listVariants !== 'function') {
      return { error: '__rf2_story_ci global not installed' };
    }
    try {
      return { variants: ci.listVariants(), context: ci.ciContext() };
    } catch (e) {
      return { error: String(e && e.message ? e.message : e) };
    }
  });
}

async function bootShell(page, baseUrl) {
  await page.goto(`${baseUrl}${TESTBED_BASE}${STORY_FRAGMENT}`, { waitUntil: 'load' });
  await page.evaluate(() => {
    try {
      localStorage.setItem('re-frame.story/seen-help-v1', '1');
    } catch (_) {
      /* ignore */
    }
  });
  // Reload so the localStorage prime takes effect before help dialog
  // logic decides whether to render the overlay.
  await page.reload({ waitUntil: 'load' });
  // Wait for the CI hook to install. The hook fires at `core/run`
  // time, which is before the first `on-hash-change!` mounts the
  // shell — so the global is reliably present by the time the page
  // is fully loaded.
  const deadline = Date.now() + 10000;
  while (Date.now() < deadline) {
    const ready = await page.evaluate(() => Boolean(window.__rf2_story_ci));
    if (ready) return;
    await new Promise((r) => setTimeout(r, 100));
  }
  throw new Error('CI hook (window.__rf2_story_ci) did not install within 10s');
}

function summariseResults(results) {
  const failures = results.filter((r) => !r.matched);
  const lines = [];
  lines.push('');
  lines.push('=== Story :play-script CI summary ===');
  for (const r of results) {
    const tag = r.matched ? 'OK  ' : 'MISS';
    const actual = (r.runState && r.runState.status) || 'no-state';
    const playLabel = r.playKey ? `[${r.playKey}]` : '';
    lines.push(
      `${tag} ${r.variantId}${playLabel}  expected=${r.expected} actual=${actual}  steps=${r.runState ? r.runState.total : '?'}`,
    );
    if (!r.matched) {
      // The CLJS `project-state` serialises `:passed?` as the literal
      // string key `"passed?"` (keyword → name preservation); read it
      // via bracket-access so the `?` survives the JS identifier rules.
      // Older drafts read `.passed` and silently dropped every failure
      // line — leaving only the row-level MISS with no step diagnostic.
      const fails = (r.runState && r.runState.results) || [];
      for (const stepR of fails) {
        if (stepR['passed?'] === false) {
          const expected = stepR.expected ? ` expected=${stepR.expected}` : '';
          const actual   = stepR.actual   ? ` actual=${stepR.actual}`     : '';
          lines.push(
            `     step ${stepR.idx} ${stepR.type} — ${stepR.message || '(no message)'}${expected}${actual}`,
          );
        }
      }
    }
  }
  lines.push('');
  lines.push(
    `Ran ${results.length} :play-script row(s). ${failures.length} unexpected outcomes.`,
  );
  return { lines: lines.join('\n'), failures };
}

/**
 * rf2-315cf — persist the run evidence for a RED gate to a stable path
 * so the browser-gate workflow's `if: failure()` upload step can surface
 * it as a downloadable post-mortem (spec/017 §Failed-run artifacts in CI).
 *
 * This is the projected run-state evidence the runner already computed:
 * per-row expected/actual status plus the per-step diagnostics
 * (idx / type / message / expected / actual). It is NOT the full
 * low-level `:rf.test/run-artifact` (seed / event-program / epoch tape) —
 * the play-scripts CI hook surfaces only the projected state, and
 * serialising the replayable artifact is a substrate change, not CI
 * mechanics. The play's `:script` (the event program) lives in the
 * variant body, so a maintainer can pair this report with the named
 * variant/play to reproduce off-CI.
 *
 * Best-effort: a write failure here must never mask the real gate verdict,
 * so it is logged and swallowed.
 */
function writeFailureReport(failures, allResults, browserMessages, pageErrors = []) {
  const report = {
    gate: 'story-play-scripts',
    bead: 'rf2-5x1wt.7 / rf2-315cf',
    capturedAt: new Date().toISOString(),
    totalRows: allResults.length,
    unexpectedOutcomes: failures.length,
    // rf2-wf5al(1): record uncaught browser exceptions explicitly so a
    // pageerror-only RED gate (all play rows matched, but the shell
    // threw) still produces an actionable post-mortem.
    uncaughtPageErrors: pageErrors.length,
    pageErrors: pageErrors.slice(-40),
    failures: failures.map((r) => ({
      variantId: r.variantId,
      playKey: r.playKey || null,
      expected: r.expected,
      actual: (r.runState && r.runState.status) || 'no-state',
      steps: (r.runState && r.runState.results) || [],
    })),
    browserDiagnostics: browserMessages.slice(-40),
  };
  try {
    fs.mkdirSync(FAILURE_REPORT_DIR, { recursive: true });
    fs.writeFileSync(FAILURE_REPORT_PATH, `${JSON.stringify(report, null, 2)}\n`);
    log(`Wrote failed-run evidence to ${FAILURE_REPORT_PATH}`);
  } catch (err) {
    log(`(warning) could not write failure report: ${err.message}`);
  }
}

/**
 * rf2-wf5al(1): compute the gate verdict from BOTH signals — the
 * per-row play-status matches AND any uncaught browser `pageerror`.
 *
 * Previously the runner returned success solely from `failures.length`
 * (derived from play-status expectation matches), so an uncaught
 * runtime/browser exception could FALSE-GREEN the :play-script gate as
 * long as every play row still reported its expected pass/fail status.
 * That contradicted the pageerror discipline the adjacent example and
 * Story feature-load runners already keep. Any pageerror is now fatal.
 *
 * Console errors are intentionally NOT fatal: they are captured (under
 * RF2_VERBOSE_TESTS) for diagnostics only, matching the adjacent
 * runners, which likewise gate on crashes/`pageerror` and not on
 * console noise. Pure inputs → exit code, so it is unit-testable.
 */
function computeExitCode({ failures, pageErrors }) {
  const unexpectedOutcomes = (failures && failures.length) || 0;
  const uncaughtErrors = (pageErrors && pageErrors.length) || 0;
  return unexpectedOutcomes === 0 && uncaughtErrors === 0 ? 0 : 1;
}

// rf2-54xbp: the non-vacuous floor for play-script discovery.
//
// The counter-with-stories testbed INTENTIONALLY seeds play-script
// fixtures (stories.cljs §`:script` CI fixtures) so this gate keeps both
// the pass path AND the expected-fail path of the runner contract under
// continuous browser coverage. The seeded inventory is six variants /
// eight play rows:
//
//   passing            (1 row,  pass)
//   failing            (1 row,  fail)
//   pred-fn-direct     (1 row,  pass)
//   dom                (1 row,  pass)
//   dom-expected-fail  (1 row,  fail)
//   multi              (3 rows: 2 pass + 1 fail)
//
// `MIN_PLAY_ROWS` is a conservative floor (below the seeded eight) so an
// authored fixture can be retired without tripping the gate, while a
// discovery/registration/lowering DRIFT that strips the fixtures to a
// near-empty set still fails loud. The pass/fail-coverage requirement is
// the structural half of the invariant: a vacuous gate that exercises
// only one side of the contract is still a trust hole.
const MIN_PLAY_ROWS = 4;

/**
 * rf2-54xbp: NON-VACUOUS guard over the discovered play-script rows.
 *
 * The :play-script gate previously treated an EMPTY row set as success —
 * `runAllVariants` logged "Nothing to assert" and returned 0. So if the
 * Story CI hook, the `:script` → `:play-script` lowering, the testbed
 * registration, or fixture discovery drifted to expose zero rows, the
 * gate FALSE-GREENED while exercising NONE of the runner contract (and
 * silently skipped the failure-report artifact upload, which only fires
 * on a non-zero exit). The adjacent static gates already fail closed on a
 * vacuous scan (see check-examples-assets.cjs's `indexes.length < 10`
 * floor) — this brings the play-script gate in line.
 *
 * The invariant is deliberately stronger than `rows.length > 0`:
 *
 *   1. At least `MIN_PLAY_ROWS` discovered rows (the seeded fixtures
 *      produce eight; a near-empty set is a drift signal, not success).
 *   2. At least one EXPECTED-PASS row AND at least one EXPECTED-FAIL row,
 *      so a drift that strips the expected-fail fixtures — leaving the
 *      runner's failure path uncovered — also trips, even if the floor
 *      is otherwise met.
 *
 * Pure (rows in → verdict out) so it is unit-testable without a browser:
 * a simulated empty / under-floor / one-sided discovery must yield
 * `{ ok: false }` with a clear diagnostic.
 *
 * @param {Array} rows discovered ci-rows (each `{ 'variant-id', 'play-key', ... }`)
 * @param {{ minRows?: number }} [opts]
 * @returns {{ ok: boolean, diagnostic: (string|null), rowCount: number,
 *             passRows: number, failRows: number }}
 */
function checkRowsNonVacuous(rows, opts = {}) {
  const minRows = opts.minRows == null ? MIN_PLAY_ROWS : opts.minRows;
  const list = Array.isArray(rows) ? rows : [];
  let passRows = 0;
  let failRows = 0;
  for (const row of list) {
    const expected = expectedStatusFor(row['variant-id'], row['play-key']);
    if (expected === 'fail') failRows += 1;
    else passRows += 1;
  }
  const rowCount = list.length;

  if (rowCount === 0) {
    return {
      ok: false,
      rowCount,
      passRows,
      failRows,
      diagnostic:
        'No :play-script / :plays rows were discovered. The ' +
        'counter-with-stories testbed seeds play-script fixtures on ' +
        'purpose to keep this gate non-vacuous, so ZERO rows means the ' +
        'Story CI hook (window.__rf2_story_ci), the :script -> :play-script ' +
        'lowering, the testbed registration, or fixture discovery has ' +
        'DRIFTED — not that there is nothing to assert. Refusing to ' +
        'false-green an empty gate (rf2-54xbp).',
    };
  }
  if (rowCount < minRows) {
    return {
      ok: false,
      rowCount,
      passRows,
      failRows,
      diagnostic:
        `Only ${rowCount} :play-script / :plays row(s) discovered — below ` +
        `the non-vacuous floor of ${minRows}. The counter-with-stories ` +
        `testbed seeds eight rows across six variants; a near-empty set is ` +
        `a discovery/registration/lowering drift signal, not success. ` +
        `Refusing to pass a vacuous gate (rf2-54xbp).`,
    };
  }
  if (passRows === 0 || failRows === 0) {
    return {
      ok: false,
      rowCount,
      passRows,
      failRows,
      diagnostic:
        `Discovered ${rowCount} row(s) but expected-pass=${passRows} / ` +
        `expected-fail=${failRows}: the gate must exercise BOTH the pass ` +
        `path AND the expected-fail path of the runner contract. The ` +
        `counter-with-stories testbed seeds both (e.g. .../passing and ` +
        `.../failing); a one-sided discovery means the failure path is no ` +
        `longer under coverage. Refusing to pass a one-sided gate (rf2-54xbp).`,
    };
  }
  return { ok: true, rowCount, passRows, failRows, diagnostic: null };
}

/**
 * Group ci-rows by variant id so we navigate ONCE per variant and
 * then drive each row's play locally.
 */
function groupRowsByVariant(rows) {
  const grouped = new Map();
  for (const row of rows) {
    const vid = row['variant-id'];
    if (!grouped.has(vid)) grouped.set(vid, []);
    grouped.get(vid).push(row);
  }
  return grouped;
}

async function runAllVariants(browser, baseUrl) {
  const context = await browser.newContext();
  const page = await context.newPage();
  const browserMessages = [];
  // rf2-wf5al(1): pageErrors are tracked SEPARATELY from browserMessages
  // so an uncaught browser/runtime exception can flip the gate verdict
  // regardless of RF2_VERBOSE_TESTS. browserMessages stays diagnostics
  // -only (and only populated when VERBOSE); pageErrors is the fatal
  // signal fed to computeExitCode below.
  const pageErrors = [];
  page.on('console', (msg) => {
    if (VERBOSE) browserMessages.push(`[browser:${msg.type()}] ${msg.text()}`);
  });
  page.on('pageerror', (err) => {
    const message = `[browser:pageerror] ${err.message}`;
    pageErrors.push(message);
    // Mirror into browserMessages so the existing diagnostics dump and
    // the failure-report's `browserDiagnostics` slice still surface it.
    browserMessages.push(message);
  });

  try {
    await bootShell(page, baseUrl);
    const discovery = await discoverVariants(page);
    if (discovery.error || !Array.isArray(discovery.variants)) {
      throw new Error(`variant discovery failed: ${discovery.error || JSON.stringify(discovery)}`);
    }
    const variants = discovery.variants;
    // Prefer the per-play `rows` enumeration; fall back to the
    // per-variant shape when the CI hook predates `rows`.
    const rows =
      discovery.context && Array.isArray(discovery.context.rows)
        ? discovery.context.rows
        : variants.map((vid) => ({ 'variant-id': vid, 'play-key': null, name: null }));
    log(
      `Discovered ${variants.length} variant(s) with :play-script / :plays — ${rows.length} play row(s) total`,
    );
    if (VERBOSE) {
      for (const r of rows) {
        log(
          `  - ${r['variant-id']}  play=${r['play-key'] || '(default)'}  steps=${r['script-len']}  auto=${r['auto-run?']}`,
        );
      }
    }
    // rf2-54xbp: NON-VACUOUS guard. A play-script gate that exercises
    // zero rows (or a one-sided / near-empty set) is a trust hole — the
    // testbed seeds fixtures precisely to keep both the pass and the
    // expected-fail paths under continuous coverage, so a drift to no
    // rows is a discovery/registration/lowering regression, NOT "nothing
    // to assert". Fail closed with a clear diagnostic, mirroring the
    // adjacent static gates' vacuous-scan guards.
    const vacuity = checkRowsNonVacuous(rows);
    if (!vacuity.ok) {
      log('');
      log(`Non-vacuous play-script guard FAILED: ${vacuity.diagnostic}`);
      throw new Error(`vacuous play-script gate: ${vacuity.diagnostic}`);
    }

    const results = [];
    const grouped = groupRowsByVariant(rows);
    for (const [vid, variantRows] of grouped.entries()) {
      let navOk = true;
      try {
        await navigateToVariant(page, baseUrl, vid);
      } catch (err) {
        navOk = false;
        for (const row of variantRows) {
          results.push({
            variantId: vid,
            playKey: row['play-key'],
            expected: expectedStatusFor(vid, row['play-key']),
            matched: false,
            runState: { status: 'navigate-error', message: err.message },
          });
        }
      }
      if (!navOk) continue;

      // For each play row on this variant: if it auto-ran, just wait
      // for the terminal state; otherwise, trigger it then wait.
      for (const row of variantRows) {
        const playKey = row['play-key'];
        const expected = expectedStatusFor(vid, playKey);

        if (!row['auto-run?']) {
          // Manual play — trigger it explicitly. The CI hook routes
          // through runner-events/run-play! which sets the active
          // play + drives the runner; we read per-play state below.
          await triggerPlay(page, vid, playKey);
        }

        const runState = playKey
          ? await waitForPlayTerminalState(page, vid, playKey)
          : await waitForTerminalState(page, vid);
        const actual = (runState && runState.status) || null;
        results.push({
          variantId: vid,
          playKey,
          expected,
          matched: actual === expected,
          runState,
        });
      }
    }

    const { lines, failures } = summariseResults(results);
    log(lines);
    // rf2-wf5al(1): an uncaught browser `pageerror` is fatal even when
    // every play row matched its expected status — otherwise a runtime
    // regression (shell/hydration/dispatch exception) false-greens the
    // gate behind a clean play-status summary.
    if (pageErrors.length > 0) {
      log('');
      log(
        `Detected ${pageErrors.length} uncaught browser pageerror(s) — failing the gate (rf2-wf5al).`,
      );
    }
    const gateFailed = failures.length > 0 || pageErrors.length > 0;
    if (gateFailed && browserMessages.length > 0) {
      log('--- browser diagnostics ---');
      for (const msg of browserMessages.slice(-40)) log(msg);
    }
    if (gateFailed) {
      writeFailureReport(failures, results, browserMessages, pageErrors);
    }
    return computeExitCode({ failures, pageErrors });
  } finally {
    await context.close();
  }
}

async function main() {
  const port = await resolveStoryFeatureLoadPort({
    env: process.env,
    repoRoot: REPO_ROOT,
  });
  const baseUrl = `http://127.0.0.1:${port}`;

  cleanTestbedOutDir();
  compileTestbed();
  stageTestbedHtml();

  // Bind 127.0.0.1 (not the http-server 0.0.0.0 default): the runner
  // only ever hits http://127.0.0.1:<port>, and resolveStoryFeatureLoadPort
  // pre-flights loopback — so exposing implementation/out/examples on
  // every interface is avoidable local-network surface. Matches the
  // adapter-smoke orchestrator (serve-and-run-adapter-smokes.cjs).
  // rf2-wf5al(2).
  const server = cleanup.trackProcess(
    spawnHarnessProcess(
      process.execPath,
      [httpServerBin(), OUT_ROOT, '-a', '127.0.0.1', '-p', String(port), '-s', '-c-1'],
      {
        cwd: IMPL_ROOT,
        stdio: ['ignore', 'pipe', 'pipe'],
      },
    ),
  );

  let serverDown = false;
  const serverOutput = [];
  const captureServerOutput = (chunk, stream) => {
    const text = chunk.toString();
    serverOutput.push(
      ...text
        .split(/\r?\n/)
        .filter(Boolean)
        .map((line) => `[http-server:${stream}] ${line}`),
    );
    if (VERBOSE) process[stream].write(text);
  };
  server.stdout.on('data', (chunk) => captureServerOutput(chunk, 'stdout'));
  server.stderr.on('data', (chunk) => captureServerOutput(chunk, 'stderr'));
  server.on('exit', (code, signal) => {
    serverDown = true;
    // The server is forcefully terminated during cleanup, which makes
    // this `exit` fire with a non-zero code after `main()` has already
    // returned its own exit code. Suppress the noise in that path;
    // surface real mid-run crashes loudly.
    if (code !== 0 && code !== null && !exiting) {
      console.error(
        `http-server exited unexpectedly (code=${code}, signal=${signal}).`,
      );
    }
  });

  const ready = await waitForHttpReady(port, Date.now() + READY_TIMEOUT_MS, {
    isAborted: () => serverDown,
  });
  if (!ready || serverDown) {
    console.error(
      `http-server did not become reachable on :${port} within ${READY_TIMEOUT_MS}ms.`,
    );
    for (const line of serverOutput.slice(-40)) console.error(line);
    return 1;
  }

  const browser = await loadChromium().launch({ headless: true });
  cleanup.addCleanup(async () => {
    try {
      await browser.close();
    } catch (_) {
      /* ignore */
    }
  });

  try {
    return await runAllVariants(browser, baseUrl);
  } finally {
    await browser.close();
  }
}

// Only launch the harness when run directly (`node serve-and-run-...`).
// Guarding on `require.main` lets the script-policy unit test
// (_story-play-scripts-policy.test.cjs) `require(...)` this module to
// exercise the pure helpers below WITHOUT spawning the browser harness
// or calling process.exit (rf2-wf5al).
if (require.main === module) {
  main()
    .then(async (code) => {
      exiting = true;
      await cleanup.cleanup();
      process.exit(code == null ? 1 : code);
    })
    .catch(async (err) => {
      exiting = true;
      console.error(err && err.stack ? err.stack : err);
      await cleanup.cleanup();
      process.exit(1);
    });
}

module.exports = {
  expectedStatusFor,
  isTerminalStatus,
  computeExitCode,
  // rf2-54xbp: the non-vacuous discovery guard + its floor, exported so
  // the script-policy unit gate can simulate empty / under-floor /
  // one-sided discovery and assert a fail-closed verdict without a
  // browser (symmetric with the computeExitCode unit coverage).
  checkRowsNonVacuous,
  MIN_PLAY_ROWS,
};
