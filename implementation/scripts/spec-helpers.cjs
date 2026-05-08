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

module.exports = {
  expectTextEquals,
  expectTextContains,
  expectInputValue,
  expectVisible,
  expectAttribute,
};
