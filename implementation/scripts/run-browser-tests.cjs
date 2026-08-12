#!/usr/bin/env node
/*
 * Playwright runner for the shadow-cljs :browser-test build.
 *
 * Launches headless Chromium, navigates to the static-server URL serving
 * out/browser-test/index.html (the page that shadow-cljs's :browser-test
 * target generates with shadow.test.browser as the runner-ns), waits for
 * cljs.test to finish, parses the summary, and exits 0 on green / 1 on red.
 *
 * "Green" means BOTH halves of the summary: a clean failure tally AND at
 * least RF2_MIN_TESTS tests actually executed (default 1). The tally alone
 * is not enough — `Ran 0 tests containing 0 assertions. / 0 failures, 0
 * errors.` is what a lane whose `:ns-regexp` selected nothing prints, and
 * shadow-cljs's find-test-namespaces returns `[]` on no match silently
 * (rf2-qqzmf).
 *
 * Done-signal strategy: shadow.test.browser's default reporter writes the
 * cljs.test :summary report via `console.log` (default cljs.test prints
 * to *out*, which under the browser is the console). It does NOT render
 * results to the DOM body and does NOT set a window flag. We therefore
 * tap `page.on('console', ...)` and watch the captured stream for the
 * summary line. The DOM is also scraped as a belt-and-braces fallback
 * in case a custom reporter is wired up later, and `window.shadow$cljs_test_done`
 * is checked first in case a runner-ns sets it.
 *
 * Summary line format (cljs.test default reporter):
 *   "Ran N tests containing M assertions."
 *   "P failures, Q errors."
 */

const { chromium } = require('playwright');
const {
  createDiagnosticBuffer,
  findFixtureAbort,
  formatCompactSummary,
  isVerboseTests,
  parseFailureCounts,
  parseRanCounts,
  summaryPartsFromText,
  testingNamespaces,
} = require('./lib/browser-test-report.cjs');
// `sleep` is the shared poll primitive — verbatim the same
// `setTimeout`-backed Promise this runner used to define inline
// (rf2-j552l2 dedup; exported from lib/local-browser-harness.cjs).
const { sleep } = require('./lib/local-browser-harness.cjs');
// rf2-u0cy4: the env-var name and flag name are shared with
// serve-and-run-browser-tests.cjs (which sets the var) via one module, so
// the two sides cannot drift into different literals.
const {
  DRIFT_UNVERIFIABLE_ENV_VAR,
  DRIFT_UNVERIFIABLE_FLAG,
} = require('./lib/browser-runner-drift-env.cjs');

const URL = process.env.BROWSER_TEST_URL || 'http://localhost:8021';
// How long to wait for the cljs.test summary before calling it a timeout.
//
// This is a BUDGET, not a correctness bound: the suite either prints a
// summary or it does not, and the number only decides how long we let it
// try. It was 120s, chosen when the `:browser-test` bundle was a fast DOM
// suite. It is now the long pole of a growing control-witness corpus —
// mounted rows driven through real-time settles with 2-4s poll budgets, so
// each witness costs ~35-40s of mostly WALL-CLOCK (not CPU) for ~5-12
// tests. Measured on the same bundle: 843 tests / 58s on the pre-witness
// main, 855 tests / 99s once the splitter witness landed. Three more
// witnesses were queued behind that, i.e. ~+120s, which exceeds 120s on
// arithmetic alone — and CI is slower than a dev box on the compute half,
// so a lane that passes locally at ~95s times out there (rf2-15047).
//
// 360s restores roughly the headroom the lane had before the witnesses
// (~3.6x the current green run) and leaves room for a few more of them
// before this recurs. It is deliberately NOT larger: a genuine hang still
// fails inside 6 minutes, well under the job's own `timeout-minutes: 45`,
// and it fails through THIS runner — which dumps the console trail and the
// namespaces reached — rather than as an opaque runner kill.
//
// rf2-15047 CALIBRATION, 2026-07-28 — MEASURED, AND 360s STANDS.
//
// rf2-mf4uy moved the benches to their own build, and the arithmetic above
// changed underneath this constant, so it was re-measured rather than left
// to drift. Two consecutive local runs of the built `:browser-test` bundle:
// 841 tests / 5459 assertions / 0 failures, 25.9s and 25.2s wall for
// serve+launch+navigate+suite+teardown, of which the summary wait proper —
// the only thing TIMEOUT_MS bounds — is ~22.5s. (The 41.5s shadow-cljs
// compile is NOT on this clock; it happens before the runner starts.) That
// confirms rf2-mf4uy's 73s -> 22.5s: one namespace really had been half the
// lane.
//
// So 360s is ~16x the local green run and ~9.5x at the measured ~1.7x CI
// factor, against the ~3.6x rule that produced it. By that rule alone the
// number wants to be ~140-180s. It is NOT being lowered, for two reasons.
//
// FIRST, the growth this budget exists to absorb is WALL-CLOCK, not compute.
// Each control witness costs ~35-40s of mounted rows driven through
// real-time settles with 2-4s poll budgets — `setTimeout`, which neither a
// faster runner nor another bundle split can shrink. The 22.5s figure is the
// result of a one-off structural change, not a trend; the witness corpus
// that motivated this bead is still landing steadily. Three more puts the
// lane near ~135s, where a 180s budget has ~1.3x headroom and needs raising
// again within weeks.
//
// SECOND, the two failure modes are asymmetric. Over-large costs only a
// slower failure on a genuine hang — still 6 minutes, still far inside
// `timeout-minutes: 45`, and still through THIS runner with the console
// trail and the rf2-76lhy per-namespace duration profile. Under-sized costs
// a FALSE RED on a loaded runner, which is precisely the failure rf2-15047
// was filed to stop, and it costs a full lane re-run plus an investigation
// that starts by suspecting the code.
//
// Revisit when the lane's own green run passes ~100s, not before.
//
// Per-lane needs differ; $BROWSER_TEST_TIMEOUT_MS remains the override.
// check-ui-mounted-prod-elision.cjs still pins 120000 for its negative-
// control mutant run — left alone deliberately: that run is EXPECTED to
// fail, so it wants a short leash, not this budget.
const DEFAULT_TIMEOUT_MS = 360000;
const BROWSER_TEST_TIMEOUT_ENV_VAR = 'BROWSER_TEST_TIMEOUT_MS';
const TIMEOUT_MS = parseInt(
  process.env[BROWSER_TEST_TIMEOUT_ENV_VAR] || String(DEFAULT_TIMEOUT_MS), 10);
const POLL_MS = 200;

// rf2-dczpv — the lane's second PHASE, now sharing the lane's one budget.
//
// WHAT IT USED TO BE. `page.goto(URL, { waitUntil: 'load' })` with no explicit
// timeout took Playwright's default 30s. That WAS a second budget on this lane,
// entirely separate from TIMEOUT_MS above and unreachable from it, and it fired
// for real: a local run of the `:browser-test` bundle died with `page.goto:
// Timeout 30000ms exceeded` after 21 namespaces had already run and printed,
// and two immediately-following runs of the identical bundle passed. Worse than
// the flake was the READING — in a CI log that line is nearly indistinguishable
// from this runner's own summary timeout, so the fix reached for was a bigger
// BROWSER_TEST_TIMEOUT_MS, which back then could not touch it.
//
// WHAT IT IS NOW, and this is the part the first cut of this comment got wrong
// (audit of PR #7193). `NAV_TIMEOUT_MS = TIMEOUT_MS`, so the lane is ONE
// configured value applied to TWO sequential phases: $BROWSER_TEST_TIMEOUT_MS
// bounds the navigation AND the summary wait that follows it. Raising it moves
// BOTH ceilings. Saying otherwise sends the reader away from the only knob the
// failed phase has — which is the same navigate-by-the-wrong-budget mistake
// this bead was filed to stop, wearing the opposite hat.
//
// The two phases stay distinguishable by what the failure line SAYS, not by
// carrying different numbers. One number is the point: a lane with two knobs is
// a lane where one of them is stale.
//
// WHY 'load' WAS ALWAYS WRONG HERE, not merely tight. `shadow.test.browser`
// starts running the suite during page load, so the `load` event cannot fire
// until the whole suite yields to the event loop. Waiting for it means waiting
// for the tests to finish — which is the job of the poll loop below, against
// the budget that is documented for it. A slow first namespace (before
// rf2-mf4uy moved them out, `bench.b10-two-clock-dom-cljs-test` began its ~36s
// synchronous burn at +8s, directly under the 30s ceiling) is enough to lose
// the navigation on a loaded box.
//
// `'commit'` resolves as soon as the response is received and the document
// starts loading, which no page script can delay: everything this runner needs
// afterwards (the console tap, `page.evaluate`, the DOM scrape) auto-waits on
// the execution context by itself. The explicit timeout is the same budget as
// the summary wait, so the lane now has ONE number, and a genuine
// server-not-listening failure still fails — at the navigation, naming itself.
const NAV_WAIT_UNTIL = 'commit';
const NAV_TIMEOUT_MS = TIMEOUT_MS;
const VERBOSE_TESTS = isVerboseTests();

// rf2-76lhy: stamp diagnostics at CAPTURE time, not at flush time.
//
// The buffer is flushed in a single burst, so every line lands on the log
// within the same millisecond and the surrounding log's own timestamps say
// nothing about when the line was produced — verified on a real CI timeout,
// where all 66 buffered `Testing <ns>` lines carried the same flush stamp.
// Without a capture-time offset, "namespace 66 was still running when the
// clock expired" is indistinguishable from "namespace 66 never returned".
// With one, the trail a timeout already dumps IS a per-namespace duration
// profile: consecutive `Testing <ns>` offsets subtract to that namespace's
// wall-clock cost, and the outlier names itself.
const RUN_STARTED_AT = process.hrtime.bigint();
const capturedAt = () =>
  `[+${(Number(process.hrtime.bigint() - RUN_STARTED_AT) / 1e9).toFixed(2)}s]`;

// rf2-u0cy4: the one console line that is a DEFECT, not noise.
//
// Console output is diagnostic-only here by deliberate design (rf2-mwx08) —
// only `pageerror` is fatal. That policy has one hole. When a cljs.test async
// row calls `done` twice, `run-block` finds its continuation already realized
// and degrades the second call to a `println`:
//
//     (if (realized? d) (println "WARNING: Async test called done more than one time.") @d)
//
// So the defect cannot reach the failure tally — it is structurally incapable
// of failing an assertion. It shipped to main exactly once already (PR #7162
// swapped in a teardown helper that calls `done`, leaving five pre-existing
// trailing `(done)` calls, so every async row in that suite double-fired); the
// lane was green and a human reading the diff caught it (rf2-0ke4x, #7164).
//
// Promoting the literal is general across the whole mounted tier rather than a
// textual lint over one suite's source, which is why it belongs here.
const FATAL_CONSOLE_RE = /Async test called done more than one time/;

// rf2-u0cy4 (merged-PR audit of #7190): the matcher above is a COPY of a
// literal that lives in ClojureScript, not in this repo. Nothing in this
// tree changes when cljs.test rewords it — so on a dependency bump the
// regex would quietly stop matching, `fatalConsole` would stay empty, and
// the gate would report green while guarding nothing. The committed policy
// test could not catch that either: it asserts this file still contains
// the regex, which a reworded upstream leaves perfectly true.
//
// Close it by comparing the matcher against the INSTALLED emission rather
// than against our own copy of it. `cljs.test/run-block` is where the
// warning is printed:
//
//     (if (realized? d) (println "WARNING: Async test called done more
//                                 than one time.") @d)
//
// and that `println` compiles inline into `cljs.test.run_block`'s body, so
// the live function's source carries the exact string the browser would
// emit. `:browser-test` builds are never `:advanced` (shadow's
// `:browser-test` target cannot be advanced-compiled), so the namespace
// object is reachable and the source is unmunged.
//
// Deliberately fail-CLOSED: an unreachable `run_block` is reported, never
// skipped. A drift check that goes quiet when it cannot look is worth
// nothing, and this whole bead exists because a fail-open gate shipped.
// Namespaces are reachable either directly on the global object or under
// shadow-cljs's `$CLJS` holder depending on the target; check both rather
// than assume one, so this reports genuine drift and not a build-shape
// difference.
// Set by serve-and-run-browser-tests.cjs from the lane's OWN command line
// (`--duplicate-done-drift-unverifiable`) — see the note on `source == null`
// in duplicateDoneMatcherDrift for why an `:advanced` lane needs it and why
// it is a declaration rather than something this runner infers.
// (DRIFT_UNVERIFIABLE_ENV_VAR / DRIFT_UNVERIFIABLE_FLAG imported above.)

const RUN_BLOCK_PROBE = () => {
  const roots = [globalThis, globalThis.$CLJS].filter(
    (r) => r && typeof r === 'object',
  );
  for (const root of roots) {
    const test = root.cljs && root.cljs.test;
    const runBlock = test && test.run_block;
    if (typeof runBlock === 'function') return String(runBlock);
  }
  return null;
};

// Compare FATAL_CONSOLE_RE with what the installed ClojureScript actually
// emits. Returns null when they agree, else the reason they do not.
async function duplicateDoneMatcherDrift(page) {
  let source;
  try {
    source = await page.evaluate(RUN_BLOCK_PROBE);
  } catch (err) {
    return `could not introspect \`cljs.test.run_block\` in the page: ${err.message}`;
  }
  if (source == null) {
    // An `:advanced`-compiled lane renames `cljs.test.run_block`, so the check
    // is not failing here — it is BLIND. That is a different thing and must be
    // handled differently, but never silently: only a lane that declares
    // itself unverifiable on its own command line may proceed, and it says so.
    if (process.env[DRIFT_UNVERIFIABLE_ENV_VAR] === '1') {
      return { unverifiable: true };
    }
    return (
      '`cljs.test.run_block` was not reachable as a function on the page. The ' +
      'duplicate-`done` guard is a copy of a ClojureScript string literal, and ' +
      'this check is what proves it still corresponds to the installed ' +
      'ClojureScript. If this lane is `:advanced`-compiled the symbol is ' +
      'renamed and the check cannot run — declare that on the lane itself with ' +
      `\`${DRIFT_UNVERIFIABLE_FLAG}\` in its npm script. It cannot be skipped ` +
      'silently.'
    );
  }
  if (!FATAL_CONSOLE_RE.test(source)) {
    return (
      'the installed ClojureScript no longer emits the duplicate-`done` warning ' +
      `this runner matches on (${FATAL_CONSOLE_RE}). cljs.test reworded it, so ` +
      'the guard would silently stop catching double-fired `done` calls. Update ' +
      "FATAL_CONSOLE_RE to the new wording — read it out of `cljs.test/run-block`'s " +
      '`realized?` branch.'
    );
  }
  return null;
}

// The test-count floor (rf2-qqzmf). ONE name across every whole-suite lane:
// the JVM runner (re-frame.test-quiet.runner) and the CLJS node runner
// (re-frame.test-quiet.shadow-node) read the same variable. Because it is
// one name, scope it to the lane you are running — a value calibrated for
// the 30-file :browser-test build will red :browser-test-prod-elision.
const MIN_TESTS_ENV_VAR = 'RF2_MIN_TESTS';
// Default 1, not a per-lane calibrated number: 1 is the bound that can never
// go stale (no lane legitimately runs zero tests), and it is exactly what
// three shadow-cljs `:browser-test` builds selected by a single `:ns-regexp`
// suffix need — a one-character suffix drift or a dropped `:source-paths`
// entry empties a lane silently and shadow-cljs says nothing.
const DEFAULT_MIN_TESTS = 1;

// Resolve the floor, or return null after explaining a malformed value. A
// malformed floor must NOT fall back to the default: `RF2_MIN_TESTS=1O`
// (letter O) quietly disabling the gate that catches silent non-execution
// would be this bug wearing a hat.
function resolveMinTests(raw) {
  if (raw == null || String(raw).trim() === '') return DEFAULT_MIN_TESTS;
  const n = Number(String(raw).trim());
  if (!Number.isInteger(n) || n < 0) {
    console.error(
      `${MIN_TESTS_ENV_VAR}="${raw}" is not a non-negative integer. It is the ` +
        `minimum number of tests this lane must run; leave it unset for the ` +
        `default of ${DEFAULT_MIN_TESTS}.`,
    );
    return null;
  }
  return n;
}

// One-purpose bridge for the ViewCell evidence retention fixture. Browser
// JavaScript has no deterministic GC primitive, while Playwright exposes the
// engine-supported `page.requestGC()` control. The cljs.test publishes a
// token + in-page acknowledgement callback under this key; the runner serves
// exactly that request and nothing else. Engines without requestGC receive a
// supported=false acknowledgement so the fixture can skip cleanly instead of
// hanging. This is intentionally NOT a general page RPC channel.
const EVIDENCE_GC_REQUEST = '__RF2_TOOL_EVIDENCE_GC_REQUEST__';
const EVIDENCE_GC_CANONICAL = '__RF2_TOOL_EVIDENCE_GC_CANONICAL__';

async function serviceEvidenceGcRequest(page, diagnostics) {
  const request = await page.evaluate((key) => {
    const value = globalThis[key];
    return value && typeof value.token === 'string'
      ? { token: value.token }
      : null;
  }, EVIDENCE_GC_REQUEST);
  if (!request) return;

  let supported = typeof page.requestGC === 'function';
  let error = null;
  if (supported) {
    try {
      await page.requestGC();
    } catch (err) {
      supported = false;
      error = err && err.message ? err.message : String(err);
    }
  }
  diagnostics.add(
    `[browser:gc] tool-evidence token=${request.token} ` +
      `${supported ? 'collected' : `unsupported${error ? ` (${error})` : ''}`}`,
  );

  await page.evaluate(({ key, token, supported: ok, error: message }) => {
    const value = globalThis[key];
    if (!value || value.token !== token) return;
    delete globalThis[key];
    if (typeof value.ack === 'function') value.ack({ supported: ok, error: message });
  }, { key: EVIDENCE_GC_REQUEST, token: request.token, supported, error });
}

// rf2-mwx08: capture the `ran` + `failErr` lines as an ATOMIC pair from
// a single source. summaryPartsFromText now only ever yields a non-null
// `failErr` together with the `Ran ...` line it directly follows, so the
// failure verdict can never be assembled from a `ran` line in one source
// and a noise-shaped `failures, errors` line in another. We lock the
// summary the moment ONE source produces a complete pair. The `ran`-only
// half is still remembered (best-effort, for a meaningful timeout
// message) but never combined cross-source into a green verdict.
function rememberSummary(summary, hit, sourceName) {
  if (hit.ran && hit.failErr) {
    summary.ran = hit.ran;
    summary.failErr = hit.failErr;
    summary.ranSource = sourceName;
    summary.failErrSource = sourceName;
    summary.source = sourceName;
    return true;
  }
  // Partial: a `Ran ...` line with no failures/errors pair yet. Keep it
  // for diagnostics only — do NOT pair it with any prior `failErr`.
  if (hit.ran && !summary.failErr) {
    summary.ran = hit.ran;
    summary.ranSource = sourceName;
  }
  return false;
}

function printSummaryDetails(summary) {
  console.error('--- cljs.test summary ---');
  console.error(`(source: ${summary.source || 'not found'})`);
  console.error(summary.ran || '(missing "Ran ... assertions." line)');
  console.error(summary.failErr || '(missing "failures, errors" line)');
  console.error('-------------------------');
}

function flushDiagnostics(diagnostics) {
  if (diagnostics.isEmpty()) return;
  console.error('--- browser test diagnostics ---');
  diagnostics.flush({
    stdout: (line) => console.error(line),
    stderr: (line) => console.error(line),
  });
  console.error('--------------------------------');
}

async function main() {
  const diagnostics = createDiagnosticBuffer();
  let browser = null;

  // Resolved before Chromium launches: a malformed floor is a configuration
  // error, not something to discover after a two-minute browser run.
  const minTests = resolveMinTests(process.env[MIN_TESTS_ENV_VAR]);
  if (minTests === null) return 2;

  try {
    browser = await chromium.launch({ headless: true });
    const context = await browser.newContext();
    const page = await context.newPage();
    // The canonical runner must never silently take the manual/no-control
    // branch in the evidence GC fixture. Installed before any page script.
    await page.addInitScript((key) => {
      globalThis[key] = true;
    }, EVIDENCE_GC_CANONICAL);

    // Capture every console line so we can scan for the cljs.test summary.
    // Flush the buffer only on failure or RF2_VERBOSE_TESTS=1.
    const consoleLines = [];
    // rf2-mwx08: track uncaught browser/runtime exceptions SEPARATELY from
    // console noise. A green cljs.test summary must NOT exit 0 if Chromium
    // observed an uncaught `pageerror` (a real runtime regression the
    // suite happened not to assert on). Console output stays diagnostic-
    // only; only `pageerror` is fatal. Mirrors the rf2-wf5al fix.
    const pageErrors = [];
    // rf2-u0cy4: tracked separately from `consoleLines` for the same reason
    // `pageErrors` is — a fatal signal must not be recoverable only by
    // re-scanning diagnostics that a green run never flushes.
    const fatalConsole = [];
    // rf2-u0j8: the subset of `pageErrors` that is TERMINAL for the lane —
    // cljs.test's refusal of an `async` row under a positional fixture, which
    // unwinds every remaining namespace and the closing summary with it.
    // Dedicated array for the same reason as the two above, and consulted in
    // the poll loop rather than after it: there is no summary coming, so
    // waiting for one only converts a diagnosable failure into a timeout.
    const fixtureAborts = [];
    diagnostics.add(`URL: ${URL}`);
    page.on('console', (msg) => {
      const text = msg.text();
      consoleLines.push(text);
      if (FATAL_CONSOLE_RE.test(text)) fatalConsole.push(text);
      diagnostics.add(`${capturedAt()} [browser:${msg.type()}] ${text}`);
    });
    page.on('pageerror', (err) => {
      pageErrors.push(err.stack || err.message);
      // rf2-u0j8: the cljs.test abort is thrown as a bare STRING, so the
      // sentence lands in `err.message` and `err.stack` may not carry it.
      // Classify on both.
      if (findFixtureAbort([err.message, err.stack])) {
        fixtureAborts.push(err.message || err.stack);
      }
      diagnostics.add(`${capturedAt()} [browser:pageerror] ${err.message}`, 'stderr');
      if (err.stack) diagnostics.add(err.stack, 'stderr');
    });
    page.on('framenavigated', (frame) => {
      if (frame === page.mainFrame()) {
        diagnostics.add(`${capturedAt()} [browser:navigation] ${frame.url()}`);
      }
    });

    diagnostics.add(`Navigating to ${URL} ...`);
    try {
      await page.goto(URL, { waitUntil: NAV_WAIT_UNTIL, timeout: NAV_TIMEOUT_MS });
    } catch (err) {
      // rf2-dczpv: say WHICH PHASE fired, and tell the truth about the knob.
      // A `page.goto: Timeout …ms exceeded` line and a `Timed out … waiting
      // for cljs.test summary` line are near-indistinguishable in a CI log, so
      // the phase has to name itself. But the two phases share one number, and
      // the first cut of this message denied that — see the NAV_TIMEOUT_MS
      // comment above.
      console.error(
        `NAVIGATION FAILED — the page.goto ceiling fired (waitUntil: ` +
          `'${NAV_WAIT_UNTIL}', timeout: ${NAV_TIMEOUT_MS}ms). The suite was ` +
          `never observed, so no cljs.test summary and no failing assertion ` +
          `exist to read.\n` +
          `  This lane is ONE configured value applied to TWO sequential ` +
          `phases: ${BROWSER_TEST_TIMEOUT_ENV_VAR} (${TIMEOUT_MS}ms) bounds ` +
          `this navigation AND the summary wait that follows it, so a larger ` +
          `value moves both ceilings. It cannot cure a page that never ` +
          `answered, though: a server that is not listening, or a bundle that ` +
          `was never built, fails HERE at any size (rf2-dczpv).`,
      );
      throw err;
    }

    // Look for the cljs.test summary in one of three places, in order of preference:
    //   1. window.shadow$cljs_test_done   (custom hook a future runner may set)
    //   2. console output                 (the default place shadow.test.browser logs)
    //   3. document.body.innerText        (custom DOM reporter, if any)
    const start = Date.now();
    const summary = {
      ran: null,
      failErr: null,
      source: null,
      ranSource: null,
      failErrSource: null,
    };

    while (Date.now() - start < TIMEOUT_MS) {
      // rf2-u0j8: stop waiting for a summary that cannot arrive. This is the
      // ONLY page error that short-circuits the loop — an ordinary uncaught
      // exception mid-suite is not terminal (the run usually finishes and the
      // rf2-mwx08 arm below fails it on the summary), and breaking on those
      // would truncate a run that was about to report and mislabel it.
      if (fixtureAborts.length > 0) break;

      // Serve a pending deterministic GC request before looking for the test
      // summary: the requesting cljs.test is async and cannot finish until
      // this acknowledgement arrives.
      await serviceEvidenceGcRequest(page, diagnostics);

      // 1. window flag
      const winPayload = await page.evaluate(() => {
        return (typeof window !== 'undefined' && window.shadow$cljs_test_done) || null;
      });
      if (winPayload) {
        const text = typeof winPayload === 'string' ? winPayload : JSON.stringify(winPayload);
        if (rememberSummary(summary, summaryPartsFromText(text), 'window.shadow$cljs_test_done')) {
          break;
        }
      }

      // 2. console buffer
      const consoleBlob = consoleLines.join('\n');
      if (rememberSummary(summary, summaryPartsFromText(consoleBlob), 'browser console')) {
        break;
      }

      // 3. DOM body
      const bodyText = await page.evaluate(() => (document.body ? document.body.innerText : ''));
      if (rememberSummary(summary, summaryPartsFromText(bodyText), 'document.body')) {
        break;
      }

      await sleep(POLL_MS);
    }

    if (!summary.ran || !summary.failErr) {
      // rf2-u0j8: a missing summary has THREE causes and they are not read
      // the same way. Say which one this is, and — when the page threw — say
      // where the lane stopped, because "no summary" alone sends the reader
      // hunting a hang or a timeout budget.
      const reached = testingNamespaces(consoleLines);
      const stoppedAt = reached.length > 0 ? reached[reached.length - 1] : null;
      const where =
        `The run announced ${reached.length} namespace(s) before it stopped` +
        (stoppedAt ? `, the last being \`${stoppedAt}\`` : '') +
        `. Every namespace scheduled after that point never executed — ` +
        `shadow.test runs the whole lane, and the closing summary, inside ONE ` +
        `\`cljs.test/run-block\`, which has no try/catch.`;

      if (fixtureAborts.length > 0) {
        console.error(
          `THE BROWSER RUN ABORTED — it did not time out, and it did not ` +
            `fail an assertion (rf2-u0j8).\n` +
            `  cljs.test refused an \`async\` row in a namespace whose fixtures ` +
            `are POSITIONAL. \`(use-fixtures :once (fn [f] … (f)))\` selects the ` +
            `\`:sync\` execution strategy, and the \`:sync\` strategy throws the ` +
            `moment a test body returns an async object. The throw is a bare ` +
            `string and nothing catches it.\n` +
            `  ${where}\n` +
            `  FIX: give that namespace MAP fixtures — ` +
            `\`(use-fixtures :once {:before (fn [] …) :after (fn [] …)})\` — ` +
            `which is the only form cljs.test runs async rows under. Positional ` +
            `fixtures remain fine in a namespace with no async rows.`,
        );
        for (const line of fixtureAborts) console.error(`  ${line}`);
      } else if (pageErrors.length > 0) {
        // Same shape, unknown cause: the page threw and no summary followed.
        // This arm is what keeps the guard correct if the abort literal above
        // is ever reworded upstream — it is matcher-free.
        console.error(
          `NO cljs.test SUMMARY, and the page emitted ${pageErrors.length} ` +
            `uncaught error(s) — the suite did not finish (rf2-u0j8). This is an ` +
            `ABORT, not a slow run: raising ${BROWSER_TEST_TIMEOUT_ENV_VAR} ` +
            `(${TIMEOUT_MS}ms) will not help.\n` +
            `  ${where}`,
        );
        for (const line of pageErrors) console.error(`  ${line}`);
      } else {
        console.error(`Timed out after ${TIMEOUT_MS}ms waiting for cljs.test summary.`);
      }
      printSummaryDetails(summary);
      flushDiagnostics(diagnostics);
      return 1;
    }

    // rf2-u0cy4: verify the duplicate-`done` matcher still corresponds to the
    // ClojureScript this run actually loaded. Checked here — after the suite
    // has demonstrably executed, before any verdict returns — so a red tally
    // cannot mask a guard that has stopped guarding. Exit 2 (configuration),
    // not 1 (red): the fix is to update the matcher, not a test.
    const matcherDrift = await duplicateDoneMatcherDrift(page);
    if (matcherDrift && matcherDrift.unverifiable) {
      // Blind, not broken — and said out loud rather than passed over. The
      // duplicate-`done` guard ITSELF still works on this lane (it matches
      // console output, and string literals survive `:advanced`); what cannot
      // run here is the check that the matcher still tracks upstream. That is
      // a repo-global property, so the non-advanced lane carrying it is
      // sufficient — and `_impl-browser-runners-verdict-policy.test.cjs`
      // pins that the default `test:browser` lane never declares itself
      // unverifiable, so the verification cannot quietly stop happening
      // everywhere at once.
      console.warn(
        `NOTE: the duplicate-\`done\` drift check did NOT run on this lane — it ` +
          `declares itself unverifiable (${DRIFT_UNVERIFIABLE_FLAG}), because an ` +
          `\`:advanced\` build renames \`cljs.test.run_block\` and the matcher ` +
          `cannot be compared against the installed ClojureScript. The ` +
          `duplicate-\`done\` guard itself is still active here. The drift check ` +
          `is carried by the default \`test:browser\` lane (rf2-u0cy4).`,
      );
    } else if (matcherDrift) {
      console.error(
        `The duplicate-\`done\` guard (rf2-u0cy4) can no longer be trusted: ${matcherDrift}`,
      );
      printSummaryDetails(summary);
      flushDiagnostics(diagnostics);
      return 2;
    }

    const counts = parseFailureCounts(summary.failErr);
    if (!counts) {
      console.error('Could not parse failures/errors counts; failing the run.');
      printSummaryDetails(summary);
      flushDiagnostics(diagnostics);
      return 1;
    }

    if (counts.failures > 0 || counts.errors > 0) {
      printSummaryDetails(summary);
      flushDiagnostics(diagnostics);
      return 1;
    }

    // rf2-mwx08: even a green cljs.test summary fails the run if Chromium
    // observed an uncaught `pageerror`. The suite may simply not assert on
    // the regression that threw; a passing summary is necessary but not
    // sufficient for a green verdict.
    if (pageErrors.length > 0) {
      console.error(
        `cljs.test summary was green, but the browser emitted ` +
          `${pageErrors.length} uncaught pageerror(s) — failing the run (rf2-mwx08).`,
      );
      printSummaryDetails(summary);
      flushDiagnostics(diagnostics);
      return 1;
    }

    // rf2-u0cy4: likewise for a double-fired cljs.test `done`. cljs.test
    // degrades it to a console warning, so the tally above can never see it.
    if (fatalConsole.length > 0) {
      console.error(
        `cljs.test summary was green, but ${fatalConsole.length} async ` +
          `row(s) called \`done\` more than once — failing the run (rf2-u0cy4). ` +
          `A duplicate \`done\` means the row's continuation was already ` +
          `realized: usually a teardown fixture that calls \`done\` alongside ` +
          `a trailing \`(done)\` the test still makes itself. cljs.test cannot ` +
          `fail this, so the lane must.`,
      );
      for (const line of fatalConsole) console.error(`  ${line}`);
      printSummaryDetails(summary);
      flushDiagnostics(diagnostics);
      return 1;
    }

    // rf2-qqzmf: the verdict above is derived from the FAILURE tally alone,
    // which `Ran 0 tests containing 0 assertions. / 0 failures, 0 errors.`
    // satisfies. A lane whose `:ns-regexp` selected nothing therefore looked
    // identical to a green suite. The `Ran N` count this runner has already
    // parsed is the missing half of the verdict.
    const ranCounts = parseRanCounts(summary.ran);
    if (!ranCounts) {
      console.error('Could not parse the "Ran N tests" count; failing the run.');
      printSummaryDetails(summary);
      flushDiagnostics(diagnostics);
      return 1;
    }
    if (ranCounts.tests < minTests) {
      console.error(
        `cljs.test summary was green, but the lane ran ${ranCounts.tests} ` +
          `test(s) — below the floor of ${minTests} (${MIN_TESTS_ENV_VAR}) — ` +
          `failing the run (rf2-qqzmf). A browser lane that discovered no ` +
          `tests is a configuration error: a renamed test namespace, a ` +
          `one-character :ns-regexp suffix drift, or a dropped :source-paths ` +
          `entry empties the build silently.`,
      );
      printSummaryDetails(summary);
      flushDiagnostics(diagnostics);
      return 1;
    }

    console.log(formatCompactSummary({
      ran: summary.ran,
      failErr: summary.failErr,
      source: summary.source,
    }));
    if (VERBOSE_TESTS) flushDiagnostics(diagnostics);
    return 0;
  } catch (err) {
    console.error(err && err.stack ? err.stack : err);
    flushDiagnostics(diagnostics);
    return 1;
  } finally {
    if (browser) await browser.close();
  }
}

main()
  .then((exitCode) => process.exit(exitCode))
  .catch((err) => {
    console.error(err && err.stack ? err.stack : err);
    process.exit(1);
  });
