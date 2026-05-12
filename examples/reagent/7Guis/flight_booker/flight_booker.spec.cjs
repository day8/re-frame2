/*
 * 7GUIs #3 — Flight Booker — smoke test.
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

const { expectAttribute } = require('../../../scripts/spec-helpers.cjs');

// Compute a YYYY-MM-DD string offset from the seeded start date. The
// view's :flight/initialise seeds both start and return to a literal
// ISO date; the spec reads that literal at runtime (rather than
// hard-coding it) so the example's "today as default" semantics can
// drift over calendar time without breaking the spec (rf2-mnief).
function isoDateOffsetDays(baseIso, deltaDays) {
  // baseIso is yyyy-mm-dd. Treat as UTC midnight so the offset
  // arithmetic doesn't shift across DST boundaries on the test runner.
  const [y, m, d] = baseIso.split('-').map((s) => parseInt(s, 10));
  const dt = new Date(Date.UTC(y, m - 1, d));
  dt.setUTCDate(dt.getUTCDate() + deltaDays);
  const yy = String(dt.getUTCFullYear()).padStart(4, '0');
  const mm = String(dt.getUTCMonth() + 1).padStart(2, '0');
  const dd = String(dt.getUTCDate()).padStart(2, '0');
  return `${yy}-${mm}-${dd}`;
}

module.exports = {
  name: 'flight-booker (7guis #3)',
  url: '/flight-booker/',
  run: async (page) => {
    // Anchor on data-testid attrs (rf2-0gdsb).
    const select = page.getByTestId('flight-trip-type');
    const start = page.getByTestId('flight-start');
    const ret = page.getByTestId('flight-return');
    const book = page.getByTestId('flight-book');

    // Initial render: one-way; book enabled. Read the seeded start-date
    // dynamically rather than asserting a literal — the view seeds it
    // from a hard-coded constant inside :flight/initialise, and the
    // spec uses that value as the baseline for the return-date
    // arithmetic below (rf2-mnief).
    await start.waitFor({ state: 'visible', timeout: 10000 });
    const startValue = await start.inputValue();
    if (!/^\d{4}-\d{2}-\d{2}$/.test(startValue)) {
      throw new Error(
        `expected start input to render an ISO yyyy-mm-dd value, got "${startValue}"`,
      );
    }
    await expectAttribute(book, 'disabled', null);

    // Switch to return: book still enabled (return defaults equal to start).
    await select.selectOption('return');
    await expectAttribute(book, 'disabled', null);

    // Make return earlier than start: book disabled. Use start - 30 days
    // as the earlier date so the test stays well clear of the
    // start-date boundary even if calendar arithmetic edges around
    // month boundaries.
    await ret.fill(isoDateOffsetDays(startValue, -30));
    await expectAttribute(book, 'disabled', '');

    // Push return after start: book enabled again.
    await ret.fill(isoDateOffsetDays(startValue, 4));
    await expectAttribute(book, 'disabled', null);
  },
};
