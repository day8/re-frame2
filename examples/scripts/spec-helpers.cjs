/*
 * Shared assertion helpers for the repo's hand-rolled Playwright specs.
 *
 * `examples/` is test-free, so there are no example specs anymore; the
 * consumers are the per-adapter testbed smokes
 * (`implementation/adapters/{reagent,uix}/testbed/spec.cjs`) plus the
 * Story browser scenarios (`tools/story/test/`), the Xray feature-matrix
 * scenarios (`tools/xray/testbeds/feature_matrix/scenarios.cjs`), and — for
 * the navigation helpers only — the sibling play-scripts runner
 * (`serve-and-run-story-play-scripts.cjs`). They keep requiring this module
 * across-tree because the helpers are substrate- and surface-agnostic; the
 * file stays here as their single shared home.
 *
 * Those specs drive raw `playwright` (not `@playwright/test`), so we don't
 * get the `expect()` matcher API for free. These helpers fill the gap with
 * just the matchers we need: text, attribute, input-value, visibility, and
 * count checks, plus generic wait helpers. Condition-based helpers poll until
 * success or timeout; expectVisible delegates to Playwright's own wait.
 */

async function expectTextEquals(locator, expected, timeoutMs = 5000) {
  const start = Date.now();
  let last = null;
  while (Date.now() - start < timeoutMs) {
    last = (await locator.textContent()) || '';
    if (last.trim() === expected) return;
    await new Promise((r) => setTimeout(r, 50));
  }
  throw new Error(`expected text "${expected}" but got "${last}" within ${timeoutMs}ms`);
}

async function expectTextContains(locator, expected, timeoutMs = 5000) {
  const start = Date.now();
  let last = null;
  while (Date.now() - start < timeoutMs) {
    last = (await locator.textContent()) || '';
    if (last.includes(expected)) return;
    await new Promise((r) => setTimeout(r, 50));
  }
  throw new Error(`expected text to contain "${expected}" but got "${last}" within ${timeoutMs}ms`);
}

async function expectInputValue(locator, expected, timeoutMs = 5000) {
  const start = Date.now();
  let last = null;
  while (Date.now() - start < timeoutMs) {
    last = await locator.inputValue();
    if (last === expected) return;
    await new Promise((r) => setTimeout(r, 50));
  }
  throw new Error(`expected input value "${expected}" but got "${last}" within ${timeoutMs}ms`);
}

async function expectVisible(locator, timeoutMs = 5000) {
  await locator.waitFor({ state: 'visible', timeout: timeoutMs });
}

async function expectAttribute(locator, attr, expected, timeoutMs = 5000) {
  const start = Date.now();
  let last = null;
  while (Date.now() - start < timeoutMs) {
    last = await locator.getAttribute(attr);
    if (last === expected) return;
    await new Promise((r) => setTimeout(r, 50));
  }
  throw new Error(
    `expected attribute ${attr}="${expected}" but got "${last}" within ${timeoutMs}ms`,
  );
}

async function expectCount(locator, expected, timeoutMs = 5000) {
  const start = Date.now();
  let last = null;
  while (Date.now() - start < timeoutMs) {
    last = await locator.count();
    if (last === expected) return;
    await new Promise((r) => setTimeout(r, 50));
  }
  throw new Error(
    `expected count ${expected} but got ${last} within ${timeoutMs}ms`,
  );
}

/*
 * Poll `readFn` until it returns a value satisfying `predicate`, then
 * return that value. Use this in preference to hand-rolled
 * `while (Date.now() - start < N) { await new Promise(r => setTimeout(r, 50)); }`
 * loops in specs — same poll-until-condition shape, single source of truth
 * for the interval / timeout / error message.
 *
 * Options:
 *   intervalMs (default 50)   — gap between reads.
 *   timeoutMs  (default 5000) — bail with throw if predicate never holds.
 *   description (default "value satisfying predicate") — context for the
 *     error message ("expected <description> within <timeout>ms, last=<value>").
 */
async function waitForValue(readFn, predicate, options = {}) {
  const {
    intervalMs = 50,
    timeoutMs = 5000,
    description = 'value satisfying predicate',
  } = options;
  const start = Date.now();
  let last;
  while (Date.now() - start < timeoutMs) {
    last = await readFn();
    if (predicate(last)) return last;
    await new Promise((r) => setTimeout(r, intervalMs));
  }
  throw new Error(
    `waitForValue: expected ${description} within ${timeoutMs}ms (last=${JSON.stringify(last)})`,
  );
}

/*
 * Poll `readFn` until `samples` consecutive reads agree, then return the
 * stable value. This replaces fixed-duration sleeps in specs that need to
 * wait for a value to *settle* (e.g. animated progress counters that have
 * already advanced past zero and now must stop).
 *
 * readFn is an async function returning the current value. Stability uses
 * strict `===`, so primitives compare by value and objects only compare when
 * readFn returns the same reference. For structural object equality, have
 * readFn return a stable primitive representation such as JSON.stringify(...).
 *
 * Options:
 *   intervalMs (default 100) — gap between reads.
 *   samples    (default 2)   — number of consecutive reads that must agree.
 *   timeoutMs  (default 5000) — bail with throw if no stable read is reached.
 *
 * Throws on timeout. Use `waitForStableValue` over `waitForTimeout(N)` whenever
 * the observable is "value X stops changing" rather than "wait N ms" — the
 * poll-until-settled pattern is robust to slow CI hardware and to fast
 * developer laptops alike.
 */
async function waitForStableValue(readFn, options = {}) {
  const { intervalMs = 100, samples = 2, timeoutMs = 5000 } = options;
  const start = Date.now();
  let previous = await readFn();
  let stableCount = 1;
  while (Date.now() - start < timeoutMs) {
    await new Promise((r) => setTimeout(r, intervalMs));
    const next = await readFn();
    if (next === previous) {
      stableCount += 1;
      if (stableCount >= samples) return next;
    } else {
      previous = next;
      stableCount = 1;
    }
  }
  throw new Error(
    `waitForStableValue: value did not settle within ${timeoutMs}ms (last="${previous}")`,
  );
}

/*
 * Navigation whose ceiling is EXPLICIT and NAMES ITSELF (rf2-taj9b).
 *
 * `page.goto(url)` / `page.reload()` apply Playwright's 30s default whenever
 * no `timeout:` is passed. That default is a SECOND budget: invisible in the
 * source, unreachable from the runner's own knob, and — this is the part that
 * costs money — indistinguishable in a CI log from the budget the runner does
 * own. `page.goto: Timeout 30000ms exceeded` reads like the spec timeout, so
 * the fix reached for is a bigger spec timeout, which cannot move it.
 * Demonstrated (rf2-bhjzn, re-run for rf2-taj9b): against a page whose `load`
 * is held 35s, a bare `page.goto` dies at 30013ms while a 90000ms lane budget
 * sits unused.
 *
 * So `timeoutMs` is REQUIRED here, not defaulted. A default would be one more
 * anonymous ceiling — the exact defect — merely relocated into this file.
 * Callers name their own budget, tied to whatever bounds them.
 *
 * `waitUntil` defaults to `'load'` because that is what every current caller
 * wants: each one follows its navigation with short locator budgets that
 * assume a loaded document. A caller whose page runs work DURING load (a
 * shadow-cljs suite, a bench fixture) should pass `'commit'` instead and let
 * its own poll own the wait — see `implementation/scripts/run-ui-g8.cjs`.
 */
function assertNavTimeout(timeoutMs, api) {
  if (typeof timeoutMs !== 'number' || !Number.isFinite(timeoutMs) || timeoutMs <= 0) {
    throw new Error(
      `${api}: timeoutMs is REQUIRED and must be a positive number (got ` +
        `${JSON.stringify(timeoutMs)}). Omitting it hands the navigation ` +
        "Playwright's 30s default — a budget no runner can reach and whose " +
        'failure line reads like the runner\'s own timeout (rf2-taj9b).',
    );
  }
}

function navigationCeilingError(api, waitUntil, timeoutMs, err) {
  return new Error(
    `NAVIGATION FAILED — this is the ${api} ceiling (waitUntil: ` +
      `'${waitUntil}', timeout: ${timeoutMs}ms), NOT any assertion budget ` +
      'that follows it. No assertion ran, so nothing about the page under ' +
      `test has been observed (rf2-taj9b). Underlying: ${err.message}`,
  );
}

async function navigate(page, url, { timeoutMs, waitUntil = 'load' } = {}) {
  assertNavTimeout(timeoutMs, 'navigate');
  try {
    await page.goto(url, { waitUntil, timeout: timeoutMs });
  } catch (err) {
    throw navigationCeilingError('page.goto', waitUntil, timeoutMs, err);
  }
}

async function reloadPage(page, { timeoutMs, waitUntil = 'load' } = {}) {
  assertNavTimeout(timeoutMs, 'reloadPage');
  try {
    await page.reload({ waitUntil, timeout: timeoutMs });
  } catch (err) {
    throw navigationCeilingError('page.reload', waitUntil, timeoutMs, err);
  }
}

module.exports = {
  expectTextEquals,
  expectTextContains,
  expectInputValue,
  expectVisible,
  expectAttribute,
  expectCount,
  navigate,
  reloadPage,
  waitForStableValue,
  waitForValue,
};
