/*
 * 7GUIs #3 — Flight Booker — smoke test (rf2-lyj0).
 *
 * Drives constrained-input UI:
 *
 * - Initial render: trip-type combo defaults to "one-way", start-date input
 *   has the seeded date, and the Book button is enabled.
 * - Switch to "return": both date inputs are enabled.
 * - Set return < start: Book button becomes disabled.
 * - Set return > start: Book button becomes enabled again.
 *
 * The 7GUIs spec calls this out as a test of derived button state. The
 * re-frame2 architecture: book-enabled? is a sub layered over the
 * trip-type and date-text subs. Smoke test exercises the subscription
 * graph rather than poking app-db directly.
 */

const { expectAttribute, expectInputValue } = require('../../../scripts/spec-helpers.cjs');

module.exports = {
  name: 'flight-booker (7guis #3)',
  url: '/flight-booker/',
  run: async (page) => {
    const select = page.locator('select');
    const inputs = page.locator('input[type="text"]');
    const start = inputs.nth(0);
    const ret = inputs.nth(1);
    const book = page.getByRole('button', { name: /book/i });

    // Initial render: one-way; book enabled.
    await expectInputValue(start, '2026-05-06', 10000);
    await expectAttribute(book, 'disabled', null);

    // Switch to return: book still enabled (return defaults equal to start).
    await select.selectOption('return');
    await expectAttribute(book, 'disabled', null);

    // Make return earlier than start: book disabled.
    await ret.fill('2026-04-01');
    await expectAttribute(book, 'disabled', '');

    // Push return after start: book enabled again.
    await ret.fill('2026-05-10');
    await expectAttribute(book, 'disabled', null);
  },
};
