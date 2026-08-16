#!/usr/bin/env node
/*
 * Story `:play-script` CI-as-test runner.
 *
 * Compiles every testbed in the ROSTER below, serves them, opens each
 * one's Story shell in Playwright, enumerates every registered variant
 * whose body carries a non-empty `:play-script` or `:plays` slot (via
 * the `window.__rf2_story_ci` global installed by
 * `re-frame.story.play.ci-runner/install-ci-hooks!`), navigates the
 * shell to each variant, waits for each play's terminal status (`:pass`,
 * `:fail`, or `:cannot-run`), and prints a per-play report. `:cannot-run` is
 * terminal but never expected, so it produces a failed row without a timeout.
 *
 * The roster (rf2-kttom)
 * ----------------------
 * This runner drove ONE hardcoded testbed until the Hicasso deck landed.
 * `TESTBEDS` is now the list, modelled on the roster/clean/compile/stage
 * loop the sibling `serve-and-run-story-feature-load-tests.cjs` already
 * keeps. Each entry owns its build id, HTML source, output dir, base path
 * AND ITS OWN NON-VACUITY FLOOR — the floors differ on purpose:
 *
 *   counter-with-stories  four rows, both sides. It owns proof of THIS
 *                         RUNNER's pass/fail semantics, so its seeded
 *                         expected-fail fixtures must stay discovered.
 *   hicasso-counter       one row, pass side only. It owns proof that a
 *                         view authored on the NATIVE substrate paints
 *                         and responds inside Story. Manufacturing a
 *                         failing hicasso variant would test the runner
 *                         twice without strengthening that claim, and
 *                         requiring four rows would pad the deck.
 *
 * Each testbed is served under its own base path and boots its own shell,
 * so one deck's variants can never satisfy another's floor.
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
 * Representative seeded fixtures in the testbed:
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
  startLocalHttpServer,
} = require('../../implementation/scripts/lib/local-browser-harness.cjs');
const { resolveStoryFeatureLoadPort } = require('./story-feature-load-port.cjs');
const { cleanStageDirs } = require('./examples-staging.cjs');
const { navigate, reloadPage } = require('./spec-helpers.cjs');

const REPO_ROOT = path.resolve(__dirname, '..', '..');
const IMPL_ROOT = path.join(REPO_ROOT, 'implementation');
const OUT_ROOT = path.join(IMPL_ROOT, 'out', 'examples');
// rf2-315cf: on a RED gate, persist the per-row run evidence the runner
// already computed to a stable path so the browser-gate workflow can
// upload it as a downloadable post-mortem (spec/017
// §Failed-run artifacts in CI). The substrate guarantees the low-level
// `:rf.test/run-artifact` (seed / scripted event stream / epoch tape)
// EXISTS and is canonicalizable, but the play-scripts CI hook surfaces only the
// projected run-state (status + per-step results) — that projection is
// the failure record this gate genuinely holds, and serialising the
// full replayable artifact is a substrate change, not CI mechanics.
const FAILURE_REPORT_DIR = path.join(IMPL_ROOT, 'out', 'story-play-scripts');
const FAILURE_REPORT_PATH = path.join(
  FAILURE_REPORT_DIR,
  'failure-report.json',
);
const STORY_FRAGMENT = '#/stories';
const READY_TIMEOUT_MS = 30000;
const TERMINAL_TIMEOUT_MS = Number(
  process.env.STORY_PLAY_SCRIPT_TERMINAL_TIMEOUT_MS || 30000,
);
// rf2-taj9b — the runner's three navigations carried no timeout and so took
// Playwright's 30s default. That made THREE budgets in this file print the
// same number — server readiness, per-play terminal wait, and an invisible
// navigation ceiling — so `Timeout 30000ms exceeded` in a CI log named none
// of them. Same number, now this file's own and distinct from its siblings;
// the helper's failure says which one fired.
//
// `waitUntil: 'load'` is KEPT (the helper's default) and is load-bearing
// here, not incidental: `bootShell` primes localStorage AFTER the document
// has loaded and then RELOADS so the shell re-mounts having read the prime,
// and `navigateToVariant` documents that each variant needs a full load so
// the auto-run fires deterministically. `'commit'` would race both.
const NAV_TIMEOUT_MS = 30000;
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

// Clean-stage boundary (rf2-bf4vdy): remove + recreate each testbed's output
// dir BEFORE shadow-cljs compiles into it, so every served file is produced
// from the current source this run — no stale file from a previous run can
// satisfy a browser request the current testbed no longer produces. Cleans
// only the roster's own dirs (not the shared OUT_ROOT); the helper
// path-guards every target to live strictly under OUT_ROOT.
function cleanTestbedOutDirs() {
  cleanStageDirs(TESTBEDS.map((t) => t.outDir), OUT_ROOT);
}

function compileTestbeds() {
  // Spawn the resolved shadow-cljs JS entry-point under THIS node binary,
  // shell-free (rf2-y9o5e3). Quiet-on-success: capture output and surface
  // it only on failure (or under RF2_VERBOSE_TESTS). ONE invocation for the
  // whole roster — shadow-cljs compiles the builds against a single JVM, so
  // the second testbed costs a fraction of the first.
  const builds = TESTBEDS.map((t) => t.build);
  const args = [shadowCljsRunner(), 'compile', ...builds];
  const result = spawnSync(process.execPath, args, {
    cwd: IMPL_ROOT,
    encoding: 'utf8',
  });
  const output = `${result.stdout || ''}${result.stderr || ''}`;
  if (VERBOSE || result.status !== 0) {
    process.stdout.write(output);
  }
  if (result.status !== 0) {
    console.error(`> shadow-cljs compile ${builds.join(' ')}`);
    throw new Error(`shadow-cljs compile failed (exit ${result.status})`);
  }
}

function stageTestbedHtml() {
  for (const testbed of TESTBEDS) {
    if (!fs.existsSync(testbed.outDir)) {
      throw new Error(`Build output dir missing: ${testbed.outDir}`);
    }
    if (!fs.existsSync(testbed.htmlSrc)) {
      throw new Error(`HTML source missing: ${testbed.htmlSrc}`);
    }
    fs.copyFileSync(testbed.htmlSrc, path.join(testbed.outDir, 'index.html'));
  }
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
 * Navigate the Story shell to `variantId` using the human shell's share-link
 * shape (`?variant=story.foo/bar#/stories`). The query is placed BEFORE the
 * hash route because shell hydration reads `window.location.search`.
 */
async function navigateToVariant(page, baseUrl, basePath, variantId) {
  const variantStr = variantIdToParam(variantId);
  // Share-link convention: search params BEFORE the hash route, so
  // `window.location.search` carries `?variant=...` and the share
  // hydrator picks it up on shell mount.
  const target = `${baseUrl}${basePath}?variant=${encodeURIComponent(variantStr)}${STORY_FRAGMENT}`;
  // Always full-load (not reload) — each variant gets a fresh shell
  // mount so the auto-run fires deterministically without inheriting
  // previous run-state.
  await navigate(page, target, { timeoutMs: NAV_TIMEOUT_MS });
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

async function bootShell(page, baseUrl, basePath) {
  await navigate(page, `${baseUrl}${basePath}${STORY_FRAGMENT}`, {
    timeoutMs: NAV_TIMEOUT_MS,
  });
  await page.evaluate(() => {
    try {
      localStorage.setItem('re-frame.story/seen-help-v1', '1');
    } catch (_) {
      /* ignore */
    }
  });
  // Reload so the localStorage prime takes effect before help dialog
  // logic decides whether to render the overlay.
  await reloadPage(page, { timeoutMs: NAV_TIMEOUT_MS });
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
    // The testbed prefix is what makes a row attributable once the roster
    // holds more than one deck: two testbeds may legitimately register
    // variants under similar ids, and a bare row would not say which shell
    // produced it.
    const bed = r.testbed ? `${r.testbed}  ` : '';
    lines.push(
      `${tag} ${bed}${r.variantId}${playLabel}  expected=${r.expected} actual=${actual}  steps=${r.runState ? r.runState.total : '?'}`,
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
 * low-level `:rf.test/run-artifact` (seed / scripted event stream /
 * epoch tape) — the play-scripts CI hook surfaces only the projected
 * state, and serialising the replayable artifact is a substrate change,
 * not CI mechanics. The play's `:script` (the scripted event stream)
 * lives in the variant body, so a maintainer can pair this report with the named
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
      testbed: r.testbed || null,
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
 * Console errors are intentionally NOT fatal in this runner: play status and
 * uncaught page errors own its verdict, while console messages are captured
 * under RF2_VERBOSE_TESTS for diagnostics. This differs from the broader Story
 * feature-load runner, which treats console errors as failures. Pure inputs ->
 * exit code, so this helper is unit-testable.
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

// rf2-kttom — THE ROSTER.
//
// One entry per Story testbed this gate drives. `vacuity` is the entry's
// own non-vacuity floor, and the two floors differ because the two
// testbeds prove different things (see this file's header). Nothing about
// the counter entry changed: it keeps `MIN_PLAY_ROWS` and both sides.
const TESTBEDS = [
  {
    label: 'counter-with-stories',
    build: 'examples/counter-with-stories',
    dirName: 'counter-with-stories',
    htmlSrc: path.join(
      REPO_ROOT, 'tools', 'story', 'testbeds', 'counter_with_stories', 'index.html',
    ),
    outDir: path.join(OUT_ROOT, 'counter-with-stories'),
    vacuity: {
      minRows: MIN_PLAY_ROWS,
      requireBothSides: true,
      seededBy:
        'the counter-with-stories testbed seeds eight rows across six ' +
        'variants (stories.cljs §`:script` CI fixtures), both sides included',
    },
  },
  {
    label: 'hicasso-counter',
    build: 'examples/hicasso-counter',
    dirName: 'hicasso-counter',
    htmlSrc: path.join(
      REPO_ROOT, 'tools', 'story', 'testbeds', 'hicasso_counter', 'index.html',
    ),
    outDir: path.join(OUT_ROOT, 'hicasso-counter'),
    vacuity: {
      minRows: 1,
      requireBothSides: false,
      seededBy:
        'the hicasso-counter deck seeds ONE meaningful play — mount the ' +
        'boundary, click it, assert the resulting DOM and frame state ' +
        '(rf2-kttom). Zero rows means the :hicasso registration, the view ' +
        'alias, or the deck has drifted',
    },
  },
];

// Path-only (no hash) so we can compose `?variant=...#/stories` URLs per
// the share-link convention — query params MUST come BEFORE the hash route
// or `window.location.search` will be empty and the share hydrator will
// skip the variant select.
function basePathFor(testbed) {
  return `/${testbed.dirName}/`;
}

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
 *   1. At least `minRows` discovered rows (default `MIN_PLAY_ROWS`; the
 *      counter's seeded fixtures produce eight, and a near-empty set is a
 *      drift signal, not success).
 *   2. At least one EXPECTED-PASS row AND at least one EXPECTED-FAIL row,
 *      so a drift that strips the expected-fail fixtures — leaving the
 *      runner's failure path uncovered — also trips, even if the floor
 *      is otherwise met.
 *
 * rf2-kttom made BOTH halves per-testbed, because a roster of decks that
 * prove different things cannot share one floor. `requireBothSides` is the
 * second half's switch and it defaults TRUE, so the counter entry — and
 * any caller that passes no opts, including every existing unit case —
 * keeps exactly the invariant rf2-54xbp wrote. A deck opts OUT only by
 * saying so in the roster, and only where it has a reason: the hicasso
 * deck's job is to prove the native substrate paints, not to re-prove the
 * runner's failure path, which the counter already holds under continuous
 * coverage. `minRows` still applies on the pass side, so an opted-out deck
 * that discovers ZERO rows is still a loud red.
 *
 * Pure (rows in → verdict out) so it is unit-testable without a browser:
 * a simulated empty / under-floor / one-sided discovery must yield
 * `{ ok: false }` with a clear diagnostic.
 *
 * @param {Array} rows discovered ci-rows (each `{ 'variant-id', 'play-key', ... }`)
 * @param {{ minRows?: number, requireBothSides?: boolean, seededBy?: string }} [opts]
 * @returns {{ ok: boolean, diagnostic: (string|null), rowCount: number,
 *             passRows: number, failRows: number }}
 */
function checkRowsNonVacuous(rows, opts = {}) {
  const minRows = opts.minRows == null ? MIN_PLAY_ROWS : opts.minRows;
  const requireBothSides = opts.requireBothSides !== false;
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
        `the non-vacuous floor of ${minRows}. ${
          opts.seededBy ||
          'the counter-with-stories testbed seeds eight rows across six variants'
        }; a near-empty set is ` +
        `a discovery/registration/lowering drift signal, not success. ` +
        `Refusing to pass a vacuous gate (rf2-54xbp).`,
    };
  }
  if (requireBothSides && (passRows === 0 || failRows === 0)) {
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
  if (!requireBothSides && passRows === 0) {
    // The opt-out drops the EXPECTED-FAIL requirement and only that. A deck
    // whose every discovered row is an expected-fail has no successful play
    // at all, which for an opted-out entry is the whole of what it was
    // rostered to prove — so it is still a red (rf2-kttom).
    return {
      ok: false,
      rowCount,
      passRows,
      failRows,
      diagnostic:
        `Discovered ${rowCount} row(s) and NONE of them is expected-pass. ` +
        `This entry waives the expected-fail requirement, not the pass one: ` +
        `${opts.seededBy || 'its floor is at least one successful play'}. ` +
        `Refusing to pass a gate with no successful play (rf2-kttom).`,
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

/**
 * Drive ONE roster entry: boot its shell, discover its rows, guard them
 * against ITS floor, and run every play.
 *
 * Returns `{ results, browserMessages, pageErrors }` for the caller to
 * aggregate; the verdict is computed once, over the whole roster, so a red
 * from either testbed reds the gate and neither can mask the other.
 *
 * Each entry gets its OWN browser context, which is what keeps the decks
 * independent: localStorage (the Story shell's help-dialog prime and its
 * shell state) and any in-page globals are per-context, so one testbed's
 * shell can neither hydrate from nor leak into the next one's.
 */
async function runTestbed(browser, baseUrl, testbed) {
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

  const basePath = basePathFor(testbed);

  try {
    log('');
    log(`--- ${testbed.label} (${testbed.build}) at ${basePath} ---`);
    await bootShell(page, baseUrl, basePath);
    const discovery = await discoverVariants(page);
    if (discovery.error || !Array.isArray(discovery.variants)) {
      throw new Error(
        `${testbed.label}: variant discovery failed: ${discovery.error || JSON.stringify(discovery)}`,
      );
    }
    const variants = discovery.variants;
    // Prefer the per-play `rows` enumeration; fall back to the
    // per-variant shape when the CI hook predates `rows`.
    const rows =
      discovery.context && Array.isArray(discovery.context.rows)
        ? discovery.context.rows
        : variants.map((vid) => ({ 'variant-id': vid, 'play-key': null, name: null }));
    log(
      `${testbed.label}: discovered ${variants.length} variant(s) with :play-script / :plays — ${rows.length} play row(s)`,
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
    const vacuity = checkRowsNonVacuous(rows, testbed.vacuity);
    if (!vacuity.ok) {
      log('');
      log(
        `Non-vacuous play-script guard FAILED for ${testbed.label}: ${vacuity.diagnostic}`,
      );
      throw new Error(
        `vacuous play-script gate (${testbed.label}): ${vacuity.diagnostic}`,
      );
    }

    const results = [];
    const grouped = groupRowsByVariant(rows);
    for (const [vid, variantRows] of grouped.entries()) {
      let navOk = true;
      try {
        await navigateToVariant(page, baseUrl, basePath, vid);
      } catch (err) {
        navOk = false;
        for (const row of variantRows) {
          results.push({
            testbed: testbed.label,
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
          testbed: testbed.label,
          variantId: vid,
          playKey,
          expected,
          matched: actual === expected,
          runState,
        });
      }
    }

    return { results, browserMessages, pageErrors };
  } finally {
    await context.close();
  }
}

/**
 * Drive the whole roster and compute the ONE verdict.
 *
 * Every testbed runs before the verdict is taken, so a red in the first
 * does not hide what the second would have said — the summary names both,
 * and a maintainer reading a failed CI log sees the full picture rather
 * than the first casualty.
 */
async function runAllVariants(browser, baseUrl) {
  const results = [];
  const browserMessages = [];
  const pageErrors = [];

  for (const testbed of TESTBEDS) {
    const run = await runTestbed(browser, baseUrl, testbed);
    results.push(...run.results);
    browserMessages.push(...run.browserMessages);
    pageErrors.push(...run.pageErrors);
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
}

async function main() {
  const port = await resolveStoryFeatureLoadPort({
    env: process.env,
    repoRoot: REPO_ROOT,
  });
  const baseUrl = `http://127.0.0.1:${port}`;

  cleanTestbedOutDirs();
  compileTestbeds();
  stageTestbedHtml();

  // Serve implementation/out/examples on loopback. The shared harness owns
  // the http-server spawn, teardown tracking, early-exit abort, bounded
  // output capture (surfaced as the failure tail), and the readiness +
  // unreachable diagnostics (rf2-slapfs). Bind 127.0.0.1 (not http-server's
  // 0.0.0.0 default): the runner only ever hits http://127.0.0.1:<port> and
  // resolveStoryFeatureLoadPort pre-flights loopback. rf2-wf5al(2).
  // `exiting` suppresses the forced-shutdown "exited unexpectedly" noise once
  // main() has already returned its own verdict.
  const { ready } = await startLocalHttpServer({
    cleanup,
    httpServerBin: httpServerBin(),
    root: OUT_ROOT,
    port,
    cwd: IMPL_ROOT,
    readyTimeoutMs: READY_TIMEOUT_MS,
    captureOutput: true,
    verbose: VERBOSE,
    suppressExitDiagnostic: () => exiting,
  });
  if (!ready) return 1;

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
  // rf2-kttom: the roster, exported so the script-policy gate can assert
  // it names BOTH testbeds and that each entry carries its own floor —
  // a deck silently dropped from the roster is a gate that stopped
  // running without going red.
  TESTBEDS,
};
