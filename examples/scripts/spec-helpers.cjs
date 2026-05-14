/*
 * Shared assertion helpers for the example Playwright specs.
 *
 * Specs use a hand-rolled runner (run-examples-tests.cjs) that drives
 * raw `playwright` (not `@playwright/test`), so we don't get the
 * `expect()` matcher API for free. These helpers fill the gap with
 * just the matchers we need: text equality, attribute checks, value
 * checks. Each polls until success or timeout.
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
 * readFn is an async function returning the current value (anything that
 * survives a strict `===` check between two reads — strings, numbers, or
 * primitive-equal objects via JSON-stringify if you need it).
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

module.exports = {
  expectTextEquals,
  expectTextContains,
  expectInputValue,
  expectVisible,
  expectAttribute,
  expectCount,
  waitForStableValue,
  waitForValue,
};
