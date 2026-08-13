#!/usr/bin/env node

'use strict';

/*
 * Policy gate for the VERDICT wiring of the implementation-side browser
 * runners — the places a runner can ship green while a fatal signal is
 * masked, or fail while naming the wrong cause. These classes are pinned
 * here:
 *
 *   1. an uncaught Chromium `pageerror` the suite happened not to assert on
 *      (rf2-mwx08);
 *   2. a lane that ran ZERO tests, whose `Ran 0 tests containing 0
 *      assertions. / 0 failures, 0 errors.` summary satisfies a
 *      failure-tally-only verdict (rf2-qqzmf);
 *   3. a cljs.test async row that called `done` twice, which `run-block`
 *      degrades to a `println` on the already-realized continuation and so
 *      can never reach the failure tally (rf2-u0cy4);
 *   4. a navigation that inherited Playwright's default 30s `load` ceiling —
 *      a SECOND budget BROWSER_TEST_TIMEOUT_MS cannot reach, whose CI log
 *      line reads like the summary timeout it is not (rf2-dczpv), and which
 *      turned out to be a CLASS rather than one site: run-ui-g8.cjs had the
 *      same defect one file over (rf2-bhjzn), and thirteen more sites in five
 *      other trees (rf2-taj9b). The SWEEP that pins the class therefore lives
 *      in `_navigation-ceiling-policy.test.cjs` and reads the whole
 *      repository; what stays here is each runner's per-file JUDGEMENT —
 *      which `waitUntil`, whose budget, what the failure says.
 *
 * Both are pinned statically for the same reason: these runners drive a
 * headless Chromium end-to-end, so their verdict paths are not cleanly
 * unit-testable without launching a browser. The test-count floor also
 * needs a static pin because it is DORMANT in a healthy tree — every lane
 * runs well above its floor, so ordinary CI would never notice the check
 * being refactored away.
 *
 * The pageerror class (1) was the rf2-wf5al correctness class fixed for the
 * examples/scripts Story play runner, here applied to:
 *
 *   - run-browser-tests.cjs           (a green cljs.test summary ignored
 *                                      pageerrors)
 *   - serve-and-run-xray-feature-gate.cjs
 *                                     (scenario marked passed even when a
 *                                      pageerror was captured)
 *   - check-story-static.cjs          (static smoke passed on visible
 *                                      assertions, ignoring pageerrors)
 *   - serve-and-run-tenant-switcher-testbed.cjs (rf2-h5e3v7 — joined the
 *                                      pinned set when CI-wired)
 *
 * Following the rf2-wf5al precedent (_story-script-runners-policy.test.cjs),
 * we pin the verdict wiring STATICALLY: each runner must (a) record
 * pageerrors into a separately-tracked array — NOT merely into the
 * diagnostic buffer — and (b) flip its verdict to fail when that array is
 * non-empty. A refactor that drops the pageerror signal back to an
 * assertions-only / summary-only verdict trips this gate.
 *
 * Console noise stays diagnostic-only by design; only `pageerror` is
 * fatal — matching every adjacent runner's policy.
 *
 * Wired into package.json via `test:script-policy`.
 */

const assert = require('assert/strict');
const fs = require('fs');
const path = require('path');

const SCRIPTS_DIR = path.resolve(__dirname);

const tests = [];
function test(name, fn) {
  tests.push({ name, fn });
}

function read(name) {
  return fs.readFileSync(path.join(SCRIPTS_DIR, name), 'utf8');
}

// ---- run-browser-tests.cjs ----

test('run-browser-tests: pageerrors are tracked in a dedicated array, not just diagnostics (rf2-mwx08)', () => {
  const src = read('run-browser-tests.cjs');
  assert.match(
    src,
    /const\s+pageErrors\s*=\s*\[\]/,
    'must declare a dedicated pageErrors array',
  );
  assert.match(
    src,
    /page\.on\(\s*['"]pageerror['"][\s\S]{0,160}?pageErrors\.push\(/,
    'the pageerror handler must push into the dedicated pageErrors array',
  );
});

test('run-browser-tests: a green cljs.test summary still fails when pageErrors were captured (rf2-mwx08)', () => {
  const src = read('run-browser-tests.cjs');
  // After the failures/errors-count guard, there must be a pageErrors
  // length check that returns the failure exit code (1) before the
  // green/compact-summary return.
  assert.match(
    src,
    /if\s*\(\s*pageErrors\.length\s*>\s*0\s*\)\s*\{[\s\S]{0,400}?return\s+1\s*;/,
    'must fail the run (return 1) when pageErrors.length > 0, even on a green summary',
  );
  // And the fatal check must sit BEFORE the green compact-summary print.
  const fatalIdx = src.search(/if\s*\(\s*pageErrors\.length\s*>\s*0\s*\)/);
  const greenIdx = src.search(/formatCompactSummary\(/);
  assert.ok(fatalIdx > -1 && greenIdx > -1, 'both call-sites must exist');
  assert.ok(
    fatalIdx < greenIdx,
    'the pageerror fatal check must precede the green compact-summary return',
  );
});

test('run-browser-tests: the executed-test count is part of the verdict, not just the failure tally (rf2-qqzmf)', () => {
  const src = read('run-browser-tests.cjs');
  // The runner has always PARSED `Ran N tests containing M assertions.`
  // (RAN_RE captures both integers) and never read N. Pin that it now does,
  // through the shared library rather than a second local regex.
  assert.match(
    src,
    /parseRanCounts/,
    'must read the `Ran N` count via the shared browser-test-report helper',
  );
  assert.match(
    src,
    /ranCounts\.tests\s*<\s*minTests[\s\S]{0,700}?return\s+1\s*;/,
    'must fail the run (return 1) when the executed-test count is below the floor',
  );
  // And the floor check must sit BEFORE the green compact-summary return —
  // otherwise a zero-test lane still prints a green verdict and exits 0.
  const floorIdx = src.search(/ranCounts\.tests\s*<\s*minTests/);
  const greenIdx = src.search(/formatCompactSummary\(/);
  assert.ok(floorIdx > -1 && greenIdx > -1, 'both call-sites must exist');
  assert.ok(
    floorIdx < greenIdx,
    'the test-count floor must precede the green compact-summary return',
  );
});

test('run-browser-tests: a malformed floor is refused, never a silent default (rf2-qqzmf)', () => {
  const src = read('run-browser-tests.cjs');
  // A floor that silently falls back to the default on `RF2_MIN_TESTS=1O`
  // would disable the gate that catches silent non-execution.
  assert.match(
    src,
    /Number\.isInteger\(\s*n\s*\)/,
    'must validate the floor as an integer',
  );
  assert.match(
    src,
    /if\s*\(\s*minTests\s*===\s*null\s*\)\s*return\s+2\s*;/,
    'a malformed floor must exit 2 (configuration error), distinct from 1 (red)',
  );
});

test('run-browser-tests: a double-fired cljs.test `done` is fatal, not diagnostic noise (rf2-u0cy4)', () => {
  const src = read('run-browser-tests.cjs');
  // The literal cljs.test emits from run-block's realized? branch. If this
  // string drifts in a ClojureScript upgrade the guard silently stops
  // matching, so pin the literal itself as well as the wiring.
  assert.match(
    src,
    /Async test called done more than one time/,
    'must match the cljs.test duplicate-done warning literal',
  );
  assert.match(
    src,
    /const\s+fatalConsole\s*=\s*\[\]/,
    'must track fatal console lines in a dedicated array, not merely in diagnostics',
  );
  assert.match(
    src,
    /page\.on\(\s*['"]console['"][\s\S]{0,320}?fatalConsole\.push\(/,
    'the console handler must push matching lines into the dedicated array',
  );
  assert.match(
    src,
    /if\s*\(\s*fatalConsole\.length\s*>\s*0\s*\)\s*\{[\s\S]{0,900}?return\s+1\s*;/,
    'must fail the run (return 1) when a duplicate `done` was captured, even on a green summary',
  );
  // Like the pageerror arm, the check is worthless after the green return.
  const fatalIdx = src.search(/if\s*\(\s*fatalConsole\.length\s*>\s*0\s*\)/);
  const greenIdx = src.search(/formatCompactSummary\(/);
  assert.ok(fatalIdx > -1 && greenIdx > -1, 'both call-sites must exist');
  assert.ok(
    fatalIdx < greenIdx,
    'the duplicate-done fatal check must precede the green compact-summary return',
  );
});

test('run-browser-tests: the duplicate-`done` matcher is checked against the INSTALLED cljs.test (rf2-u0cy4 audit)', () => {
  // The audit of PR #7190: asserting that this runner still contains its own
  // regex is not drift protection. The literal belongs to ClojureScript, so a
  // reworded upstream changes NEITHER this runner nor a test that reads it —
  // the gate stays green while FATAL_CONSOLE_RE matches nothing. The runner
  // must therefore compare the matcher against what the loaded ClojureScript
  // actually emits.
  const src = read('run-browser-tests.cjs');
  assert.match(
    src,
    /\[\s*globalThis\s*,\s*globalThis\.\$CLJS\s*\]/,
    'must consider both namespace roots (bare global and shadow-cljs `$CLJS`), so a '
      + 'build-shape difference is not mistaken for upstream drift',
  );
  assert.match(
    src,
    /root\.cljs\s*&&\s*root\.cljs\.test/,
    'must reach the live cljs.test namespace object in the page',
  );
  assert.match(
    src,
    /test\s*&&\s*test\.run_block/,
    'must introspect `cljs.test/run-block` — the fn whose body carries the warning literal',
  );
  assert.match(
    src,
    /FATAL_CONSOLE_RE\.test\(\s*source\s*\)/,
    'must test the matcher against the live run-block source, not against its own text',
  );
  // Fail-closed. A drift check that returns "fine" when it cannot look is the
  // same fail-open shape this bead exists to remove.
  // An unreachable `run_block` must be REPORTED. The one exception — an
  // `:advanced` lane that declares itself blind — is pinned separately below;
  // what must never exist is a silent pass, so the branch has to end in a
  // returned reason.
  assert.match(
    src,
    /if\s*\(\s*source\s*==\s*null\s*\)\s*\{[\s\S]{0,1400}?It cannot be skipped/,
    'an unreachable `run_block` must be REPORTED, never silently skipped',
  );
  // And the verdict must actually consume it, before any tally-based return.
  assert.match(
    src,
    /const\s+matcherDrift\s*=\s*await\s+duplicateDoneMatcherDrift\(\s*page\s*\)/,
    'the verdict path must call the drift check',
  );
  const driftIdx = src.search(/const\s+matcherDrift\s*=\s*await\s+duplicateDoneMatcherDrift/);
  const countsIdx = src.search(/const\s+counts\s*=\s*parseFailureCounts/);
  assert.ok(driftIdx > -1 && countsIdx > -1, 'both call-sites must exist');
  assert.ok(
    driftIdx < countsIdx,
    'the drift check must run before the failure-tally verdict, so a red run cannot mask a guard that stopped guarding',
  );
});

test('run-browser-tests: an `:advanced` lane may only skip the drift check by DECLARING it (rf2-u0cy4)', () => {
  // Closure renames `cljs.test.run_block`, so on an `:advanced` bundle the
  // drift check is BLIND rather than failing. That must not be inferred by the
  // runner (an inference would silently spread to a lane that merely broke),
  // and must not be silent. It is a per-lane declaration, made on the lane's
  // own command line, and announced when used.
  const src = read('run-browser-tests.cjs');
  // The literal itself lives in the shared lib module (rf2-u0cy4: both
  // run-browser-tests.cjs, which reads the var, and
  // serve-and-run-browser-tests.cjs, which sets it, import the SAME
  // constant from lib/browser-runner-drift-env.cjs — one name, so the two
  // sides cannot drift into different literals). Pin the literal there, and
  // pin that this runner actually imports it (an explicit, named channel,
  // not a re-typed copy).
  const libSrc = read('lib/browser-runner-drift-env.cjs');
  assert.match(
    libSrc,
    /DRIFT_UNVERIFIABLE_ENV_VAR\s*=\s*'RF2_DUPLICATE_DONE_DRIFT_UNVERIFIABLE'/,
    'the env-var literal must be declared in the shared lib module',
  );
  assert.match(
    src,
    /DRIFT_UNVERIFIABLE_ENV_VAR[\s\S]{0,80}\brequire\(\s*['"]\.\/lib\/browser-runner-drift-env\.cjs['"]\s*\)/,
    'run-browser-tests.cjs must import the constant from the shared lib module, not re-declare it',
  );
  // Only the unreachable branch may honour it. If introspection SUCCEEDS the
  // check runs regardless, so a stale declaration cannot disable a working
  // check.
  const nullBranch = src.slice(src.search(/if\s*\(\s*source\s*==\s*null\s*\)/));
  const driftBranch = nullBranch.slice(nullBranch.search(/if\s*\(\s*!FATAL_CONSOLE_RE\.test/));
  assert.match(
    nullBranch.slice(0, nullBranch.search(/if\s*\(\s*!FATAL_CONSOLE_RE\.test/)),
    /DRIFT_UNVERIFIABLE_ENV_VAR\]\s*===\s*'1'/,
    'the declaration may only be honoured where the symbol is unreachable',
  );
  assert.doesNotMatch(
    driftBranch,
    /DRIFT_UNVERIFIABLE_ENV_VAR/,
    'a REAL drift (matcher present but not matching) must fail even on a lane that '
      + 'declares itself unverifiable — the declaration is about blindness, not about drift',
  );
  assert.match(
    src,
    /console\.warn\([\s\S]{0,400}?did NOT run on this lane/,
    'a skipped drift check must announce itself, not pass in silence',
  );
});

test('the default `test:browser` lane CARRIES the drift check; only `:advanced` lanes waive it (rf2-u0cy4)', () => {
  // The load-bearing assertion of the pair above: the waiver is only safe
  // because at least one lane still verifies. If the default lane ever
  // acquired the flag, the verification would stop happening anywhere and
  // every lane would still be green.
  const pkg = JSON.parse(
    fs.readFileSync(path.join(path.resolve(SCRIPTS_DIR, '..'), 'package.json'), 'utf8'),
  );
  const FLAG = '--duplicate-done-drift-unverifiable';
  assert.ok(
    !pkg.scripts['test:browser'].includes(FLAG),
    'the default `test:browser` lane must NEVER declare itself unverifiable — it is '
      + 'the lane that carries the duplicate-`done` drift verification (rf2-u0cy4)',
  );
  // And the lanes that DO waive it must be exactly the advanced-compiled ones,
  // each of which is a `shadow-cljs release` of a prod build.
  const waivers = Object.entries(pkg.scripts)
    .filter(([, cmd]) => cmd.includes(FLAG))
    .map(([name]) => name)
    .sort();
  assert.deepEqual(
    waivers,
    ['test:browser-prod-elision', 'test:browser-schemas-boundary-prod'],
    'exactly the two `:advanced` + goog.DEBUG=false production lanes may waive the '
      + 'drift check; a new waiver is a decision, not a default',
  );
  for (const lane of waivers) {
    assert.match(
      pkg.scripts[lane],
      /shadow-cljs release/,
      `${lane} waives the drift check, so it must actually be a release (\`:advanced\`) `
        + 'build — the waiver exists only because Closure renames `cljs.test.run_block`',
    );
  }
});

test('run-browser-tests: diagnostics are stamped at capture time, not flush time (rf2-76lhy)', () => {
  const src = read('run-browser-tests.cjs');
  // The buffer flushes in one burst, so a timeout trail without capture-time
  // offsets records WHICH namespaces a run reached and nothing about how long
  // any of them took. This is dormant in a healthy tree — only a timeout ever
  // flushes it — so it needs a static pin exactly like the test-count floor.
  assert.match(
    src,
    /process\.hrtime\.bigint\(\)/,
    'must take a monotonic clock reading, not Date.now()',
  );
  assert.match(
    src,
    /page\.on\(\s*['"]console['"][\s\S]{0,320}?diagnostics\.add\(\s*`\$\{capturedAt\(\)\}/,
    'the console handler must stamp each captured line as it arrives',
  );
});

test('run-browser-tests: navigation has its own explicit, named ceiling (rf2-dczpv)', () => {
  const src = read('run-browser-tests.cjs');
  // A bare `page.goto(URL, { waitUntil: 'load' })` takes Playwright's default
  // 30s — a SECOND budget on this lane that BROWSER_TEST_TIMEOUT_MS cannot
  // reach, and one whose failure line reads in CI like the summary timeout it
  // is not. Dormant in a healthy tree (navigation normally commits in
  // milliseconds), so it needs a static pin like the test-count floor.
  assert.match(
    src,
    /page\.goto\(\s*URL\s*,\s*\{\s*waitUntil:[^}]*\btimeout:/,
    'page.goto must pass an EXPLICIT timeout, never inherit Playwright\'s 30s default',
  );
  assert.doesNotMatch(
    src,
    /NAV_WAIT_UNTIL\s*=\s*['"]load['"]/,
    "waitUntil 'load' cannot fire until the suite yields — the poll loop owns that wait",
  );
  assert.match(
    src,
    /page\.goto\([\s\S]{0,600}?catch[\s\S]{0,600}?NAVIGATION FAILED/,
    'a failed navigation must name itself as the navigation ceiling, not the summary timeout',
  );
});

test('run-browser-tests: an aborted run is named as an abort, not waited out as a timeout (rf2-u0j8)', () => {
  const src = read('run-browser-tests.cjs');
  // cljs.test refuses an `async` row under a POSITIONAL fixture by throwing a
  // bare string out of `test-var-block*`. Nothing catches it, shadow.test runs
  // the whole lane inside ONE `run-block`, so every remaining namespace AND the
  // closing summary are lost. The runner already held the decisive signal — a
  // `pageerror` — and consulted it only after a summary it was never going to
  // get, so the observed cost was a full BROWSER_TEST_TIMEOUT_MS burn ending in
  // "Timed out ... waiting for cljs.test summary. (source: not found)".
  //
  // Dormant in a healthy tree, like the floor and the navigation ceiling: no
  // green run reaches any of this, so it needs a static pin.
  assert.match(
    src,
    /const\s+fixtureAborts\s*=\s*\[\]/,
    'must track the terminal fixture abort in a dedicated array, not merely in diagnostics',
  );
  assert.match(
    src,
    /page\.on\(\s*['"]pageerror['"][\s\S]{0,400}?fixtureAborts\.push\(/,
    'the pageerror handler must classify and record the terminal abort as it arrives',
  );
  // The break must be INSIDE the poll loop. After it, the abort has already
  // cost the whole budget, which is the defect.
  assert.match(
    src,
    /while\s*\([\s\S]{0,80}?TIMEOUT_MS\s*\)\s*\{[\s\S]{0,700}?if\s*\(\s*fixtureAborts\.length\s*>\s*0\s*\)\s*break\s*;/,
    'the poll loop must stop the moment a terminal abort is seen',
  );
  // Narrowness is the point: only the terminal class short-circuits. An
  // ordinary mid-suite `pageerror` is NOT terminal — the run usually finishes
  // and the rf2-mwx08 arm fails it on the summary — so breaking on those would
  // truncate a run that was about to report and mislabel it.
  assert.doesNotMatch(
    src,
    /while\s*\([\s\S]{0,80}?TIMEOUT_MS\s*\)\s*\{[\s\S]{0,700}?if\s*\(\s*pageErrors\.length\s*>\s*0\s*\)\s*break\s*;/,
    'an ordinary pageerror must NOT short-circuit the poll loop',
  );
  // The verdict path must SAY what happened, and name the fix.
  assert.match(
    src,
    /THE BROWSER RUN ABORTED/,
    'the abort must name itself rather than presenting as a summary timeout',
  );
  assert.match(
    src,
    /use-fixtures[\s\S]{0,400}?:before/,
    'the message must name the map-fixture remedy, not merely report the abort',
  );
  // And the matcher-free backstop: if the cljs.test literal is ever reworded,
  // a summary-less run that emitted page errors must STILL be reported as an
  // abort rather than as a timeout. This is what keeps the guard correct
  // without pinning it to a string that lives in another repository.
  assert.match(
    src,
    /else if\s*\(\s*pageErrors\.length\s*>\s*0\s*\)\s*\{[\s\S]{0,600}?THE BROWSER RUN ABORTED/,
    'a summary-less run with page errors must be reported as an abort even when '
      + 'the abort literal did not match',
  );
});

test('run-browser-tests: an unanswerable trusted-input request stops the run instead of hanging it (rf2-il7b)', () => {
  const src = read('run-browser-tests.cjs');
  // The bridge ITSELF needs no static pin, and deliberately does not get one:
  // three cljs.test suites press a real Tab and a real Escape through it on
  // every `npm run test:browser`, so it is a fact the runner observes directly
  // rather than one a source regex would stand in for. (rf2-u0j8's worker
  // rejected a lint on exactly that ground; the same test applies here.)
  //
  // What IS pinned is the arm those suites cannot reach. The page publishes a
  // request and SUSPENDS until it is acknowledged, so a request the runner
  // cannot answer takes the whole lane down — every namespace after it, and
  // the closing summary with it — and presents as a six-minute hang. Dormant
  // in a healthy tree, exactly like the fixture abort above.
  assert.match(
    src,
    /const\s+trustedInput\s*=\s*\{[^}]*faults:\s*\[\]/,
    'must track unanswerable bridge requests in a dedicated ledger, not merely in diagnostics',
  );
  // Inside the poll loop, and AFTER the press attempt that produces it: the
  // whole point is not to wait out a budget for a row that cannot resume.
  assert.match(
    src,
    /while\s*\([\s\S]{0,80}?TIMEOUT_MS\s*\)\s*\{[\s\S]{0,1600}?serviceTrustedInputRequest\([\s\S]{0,200}?if\s*\(\s*trustedInput\.faults\.length\s*>\s*0\s*\)\s*break\s*;/,
    'the poll loop must stop the moment a request it cannot answer is seen',
  );
  // Narrowness, as above: a press that THREW is acknowledged with its error so
  // the row resumes and reds on the spot. Only an unacknowledgeable request is
  // terminal — a runner that broke on every failed press would convert an
  // ordinary red assertion into a truncated lane.
  assert.doesNotMatch(
    src,
    /while\s*\([\s\S]{0,80}?TIMEOUT_MS\s*\)\s*\{[\s\S]{0,1600}?if\s*\(\s*trustedInput\.presses\s*[=<>!]/,
    'a failed press must not short-circuit the poll loop',
  );
  // And it must SAY what happened, and name both halves of the protocol —
  // the drift is always between two files, and a message naming one of them
  // sends the reader to the wrong side.
  assert.match(
    src,
    /THE BROWSER RUN STALLED ON THE TRUSTED-INPUT BRIDGE/,
    'the stall must name itself rather than presenting as a summary timeout',
  );
  assert.match(
    src,
    /trusted-input-support[\s\S]{0,300}?serviceTrustedInputRequest/,
    'the message must name the page half and the runner half, not merely report the stall',
  );
});

// ---- serve-and-run-xray-feature-gate.cjs ----

test('xray-feature-gate: a scenario with a captured pageerror is not marked passed (rf2-mwx08)', () => {
  const src = read('serve-and-run-xray-feature-gate.cjs');
  // The handler already records into browserState.pageErrors; pin that
  // the run path THROWS (→ passed stays false / anyFailed flips) when
  // that array is non-empty, BEFORE `passed = true`.
  assert.match(
    src,
    /if\s*\(\s*browserState\.pageErrors\.length\s*>\s*0\s*\)\s*\{[\s\S]{0,300}?throw\s+new\s+Error/,
    'must throw when browserState.pageErrors is non-empty so the scenario fails',
  );
  const throwIdx = src.search(/browserState\.pageErrors\.length\s*>\s*0/);
  const passedIdx = src.search(/\bpassed\s*=\s*true\s*;/);
  assert.ok(throwIdx > -1 && passedIdx > -1, 'both call-sites must exist');
  assert.ok(
    throwIdx < passedIdx,
    'the pageerror fatal check must precede `passed = true`',
  );
});

// ---- check-story-static.cjs ----

test('check-story-static: pageerrors are tracked in a dedicated array, not just diagnostics (rf2-mwx08)', () => {
  const src = read('check-story-static.cjs');
  assert.match(
    src,
    /const\s+pageErrors\s*=\s*\[\]/,
    'must declare a dedicated pageErrors array',
  );
  assert.match(
    src,
    /page\.on\(\s*['"]pageerror['"][\s\S]{0,160}?pageErrors\.push\(/,
    'the pageerror handler must push into the dedicated pageErrors array',
  );
});

test('check-story-static: the smoke throws on a captured pageerror even when assertions passed (rf2-mwx08)', () => {
  const src = read('check-story-static.cjs');
  assert.match(
    src,
    /if\s*\(\s*pageErrors\.length\s*>\s*0\s*\)\s*\{[\s\S]{0,300}?throw\s+new\s+Error/,
    'must throw when pageErrors is non-empty so the smoke fails (exit 1)',
  );
});

// ---- serve-and-run-tenant-switcher-testbed.cjs (rf2-h5e3v7) ----
// The tenant-switcher testbed smoke is now CI-wired
// (tenant-switcher-testbed-smoke job), so its verdict path joins the
// pinned set: pageerror handling is specific
// to this runner as a maskable failure. Same discipline as the siblings —
// pageerrors go into a dedicated array and flip the verdict to fail before
// `passed = true`.

test('tenant-switcher-testbed: pageerrors are tracked in a dedicated array, not just diagnostics (rf2-h5e3v7)', () => {
  const src = read('serve-and-run-tenant-switcher-testbed.cjs');
  assert.match(
    src,
    /const\s+pageErrors\s*=\s*\[\]/,
    'must declare a dedicated pageErrors array',
  );
  assert.match(
    src,
    /page\.on\(\s*['"]pageerror['"][\s\S]{0,160}?pageErrors\.push\(/,
    'the pageerror handler must push into the dedicated pageErrors array',
  );
});

test('tenant-switcher-testbed: a captured pageerror fails the spec even when assertions passed (rf2-h5e3v7)', () => {
  const src = read('serve-and-run-tenant-switcher-testbed.cjs');
  assert.match(
    src,
    /if\s*\(\s*pageErrors\.length\s*>\s*0\s*\)\s*\{[\s\S]{0,300}?throw\s+new\s+Error/,
    'must throw when pageErrors is non-empty so the spec fails',
  );
  const throwIdx = src.search(/pageErrors\.length\s*>\s*0/);
  const passedIdx = src.search(/\bpassed\s*=\s*true\s*;/);
  assert.ok(throwIdx > -1 && passedIdx > -1, 'both call-sites must exist');
  assert.ok(
    throwIdx < passedIdx,
    'the pageerror fatal check must precede `passed = true`',
  );
});

// ---- the navigation-ceiling sweep: MOVED (rf2-dczpv → rf2-bhjzn → rf2-taj9b) ----
//
// rf2-dczpv fixed one `page.goto` that inherited Playwright's 30s default;
// rf2-bhjzn found the identical defect in run-ui-g8.cjs and pinned it with a
// sweep over THIS directory. rf2-taj9b then found thirteen more sites across
// five other trees, so the sweep now lives at
// `implementation/scripts/_navigation-ceiling-policy.test.cjs` and reads the
// whole repository. Both reasons to move it were load-bearing:
//
//   1. SCOPE. A directory sweep cannot see `tools/`, `examples/`,
//      `implementation/freehand/` or the adapter testbeds, which is where most
//      of the class actually lived.
//
//   2. SOUNDNESS. This sweep bounded a call by reading a flat 300 characters
//      after `.goto(`, which reaches PAST the call's own closing paren. Both
//      bare navigations in run-ui-g13.cjs — in this very directory — were
//      followed within that window by a `waitForFunction(..., { timeout:
//      TIMEOUT })`, so the window found a `timeout:` that belonged to a
//      different call and reported the file green. Measured, both sites, on
//      the commit this comment replaces. The replacement scans the call's own
//      balanced parens, so nothing outside the call can vouch for it.
//
// The per-file assertions below stay here: they pin the JUDGEMENT each runner
// made (which `waitUntil`, whose budget, what the failure says), which is not
// mechanical and does not belong in a sweep.

// ---- run-ui-g8.cjs (rf2-bhjzn) ----

test('run-ui-g8: navigation has its own named ceiling, and does not wait on `load` (rf2-bhjzn)', () => {
  const src = read('run-ui-g8.cjs');
  // `load` cannot fire until the un-optimized :ui-g8 bundle has arrived AND
  // run its synchronous portion — which is where the fixture's warm-up and
  // sample collection live. Waiting for it is waiting for the fixture, which
  // is the waitForFunction poll's job against TIMEOUT.
  assert.match(
    src,
    /NAV_WAIT_UNTIL\s*=\s*'commit'/,
    "the G-8 navigation must commit, not wait on `load` — `load` waits for the fixture",
  );
  assert.match(
    src,
    /NAV_TIMEOUT\s*=\s*TIMEOUT\b/,
    'the navigation budget must be the gate\'s own TIMEOUT, so the lane has ONE number',
  );
  assert.match(
    src,
    /page\.goto\([\s\S]{0,400}?catch[\s\S]{0,600}?NAVIGATION FAILED/,
    'a failed navigation must name itself as the navigation ceiling, not the fixture-result budget',
  );
});

// ---- check-story-static.cjs navigation ceiling (rf2-bhjzn) ----

test('check-story-static: the navigation budget is named, not a bare literal (rf2-bhjzn)', () => {
  const src = read('check-story-static.cjs');
  // This smoke always passed an explicit timeout, so it never INHERITED the
  // default — but the literal 30000 sat beside a READY_TIMEOUT_MS of 30000,
  // two different budgets printing one number.
  assert.match(
    src,
    /const\s+NAV_TIMEOUT_MS\s*=/,
    'the navigation budget must be a named constant, distinct from READY_TIMEOUT_MS',
  );
  assert.doesNotMatch(
    src,
    /page\.goto\([^)]*timeout:\s*\d/,
    'the navigation timeout must not be an anonymous numeric literal',
  );
  assert.match(
    src,
    /page\.goto\([\s\S]{0,300}?catch[\s\S]{0,600}?NAVIGATION FAILED/,
    'a failed navigation must say which of the two 30000ms budgets fired',
  );
});

let failed = 0;
for (const { name, fn } of tests) {
  try {
    fn();
  } catch (err) {
    failed += 1;
    console.error(`FAIL ${name}`);
    console.error(err && err.stack ? err.stack : err);
  }
}

if (failed > 0) {
  console.error(`impl-browser-runners-verdict-policy tests: ${failed} failed.`);
  process.exit(1);
}

console.log(`impl-browser-runners-verdict-policy tests: ${tests.length} passed.`);
