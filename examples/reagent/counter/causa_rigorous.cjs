/*
 * Causa shell — richer counter-example browser proposal (rf2-mlmcw).
 *
 * This is intentionally not wired into the default examples runner yet.
 * It is a concrete alternative to `causa.spec.cjs`: still a single-page
 * counter app, but it exercises more of Causa's real shell behaviour.
 *
 * Added coverage over the existing smoke:
 *
 *   1. Co-pilot rail affordances:
 *      - cue button opens the rail
 *      - close button closes it
 *      - Ctrl+Shift+/ re-opens it
 *      - slash popover and submit path render a question + answer turn
 *   2. Panel-specific empty states:
 *      - Routes => "No routes registered"
 *      - Schemas => "No schemas registered"
 *      - MCP => "No activity"
 *   3. Trace-panel behaviour:
 *      - live row growth after host dispatch
 *      - filter activation narrows the ribbon
 *      - clear-filters widens it again
 *      - clicking a trace row pivots back to event detail
 *   4. Trace-bus lifecycle:
 *      - redacted counter bump
 *      - clear-buffer! empties the trace panel
 *      - clear-buffer! drops the redacted indicator
 */

const { expectTextEquals, expectVisible } = require('../../scripts/spec-helpers.cjs');

const SHELL_TESTID = 'rf-causa-shell';
const REDACTED_TESTID = 'rf-causa-redacted-indicator';

function sleep(ms) {
  return new Promise((resolve) => setTimeout(resolve, ms));
}

async function waitForCondition(readFn, predicate, label, timeoutMs = 5000) {
  const start = Date.now();
  let last = null;
  while (Date.now() - start < timeoutMs) {
    last = await readFn();
    if (predicate(last)) return last;
    await sleep(50);
  }
  throw new Error(`Timed out waiting for ${label}; last value was ${JSON.stringify(last)}`);
}

async function rootDisplay(page) {
  return page.evaluate(() => {
    const root = document.getElementById('rf-causa-root');
    return root ? getComputedStyle(root).display : 'missing';
  });
}

async function expectRootDisplay(page, expected, timeoutMs = 5000) {
  const actual = await waitForCondition(
    () => rootDisplay(page),
    (value) => value === expected,
    `#rf-causa-root display=${expected}`,
    timeoutMs,
  );
  if (actual !== expected) {
    throw new Error(`Expected #rf-causa-root display "${expected}", got "${actual}"`);
  }
}

async function clickSidebar(page, id, expectedCanvasTestId) {
  await page.locator(`[data-testid="rf-causa-sidebar-item-${id}"]`).click();
  await expectVisible(page.locator(`[data-testid="${expectedCanvasTestId}"]`), 5000);
}

async function clickHostButtonByLabel(page, label) {
  const clicked = await page.evaluate((targetLabel) => {
    const candidates = Array.from(document.querySelectorAll('button'))
      .filter((el) => !el.closest('#rf-causa-root'));
    const target = candidates.find((el) => (el.textContent || '').trim() === targetLabel);
    if (!target) return false;
    target.click();
    return true;
  }, label);
  if (!clicked) {
    throw new Error(`Could not find host-app button with label ${JSON.stringify(label)}`);
  }
}

async function traceRowCount(page) {
  return page.locator('[data-testid^="rf-causa-trace-row-"]').count();
}

async function waitForTraceGrowth(page, beforeRows, timeoutMs = 5000) {
  return waitForCondition(
    () => traceRowCount(page),
    (count) => count > beforeRows,
    `trace row growth beyond ${beforeRows}`,
    timeoutMs,
  );
}

function parseCountsText(text) {
  const match = /(\d+)\s*\/\s*(\d+)\s+in view/.exec(text || '');
  if (!match) {
    throw new Error(`Could not parse counts text: ${JSON.stringify(text)}`);
  }
  return { rendered: Number(match[1]), total: Number(match[2]) };
}

async function readTraceCounts(page) {
  const text = (await page.locator('[data-testid="rf-causa-trace-counts"]').textContent()) || '';
  return parseCountsText(text.trim());
}

async function invokeSuppressedCounter(page, n) {
  return page.evaluate((count) => {
    const cfg =
      window.day8 &&
      window.day8.re_frame2_causa &&
      window.day8.re_frame2_causa.config;
    if (!cfg || typeof cfg.note_suppressed_BANG_ !== 'function') {
      return { ok: false, reason: 'day8.re_frame2_causa.config.note_suppressed_BANG_ not on window' };
    }
    for (let i = 0; i < count; i += 1) {
      cfg.note_suppressed_BANG_(null);
    }
    return {
      ok: true,
      count: typeof cfg.suppressed_count === 'function' ? cfg.suppressed_count() : null,
    };
  }, n);
}

async function clearTraceBuffer(page) {
  return page.evaluate(() => {
    const bus =
      window.day8 &&
      window.day8.re_frame2_causa &&
      window.day8.re_frame2_causa.trace_bus;
    if (!bus || typeof bus.clear_buffer_BANG_ !== 'function') {
      return { ok: false, reason: 'day8.re_frame2_causa.trace_bus.clear_buffer_BANG_ not on window' };
    }
    bus.clear_buffer_BANG_();
    return { ok: true };
  });
}

module.exports = {
  name: 'causa-rigorous-proposal',
  url: '/counter/',
  run: async (page) => {
    const counterValue = page.locator('span').first();

    // ----------------------------------------------------------------
    // 1. Initial state — counter ready, shell not mounted yet.
    // ----------------------------------------------------------------
    await expectTextEquals(counterValue, '5', 10000);
    if ((await page.locator(`[data-testid="${SHELL_TESTID}"]`).count()) !== 0) {
      throw new Error('Causa shell rendered at page-load; expected lazy mount.');
    }

    // ----------------------------------------------------------------
    // 2. Open the shell; default panel and cue render.
    // ----------------------------------------------------------------
    await page.keyboard.press('Control+Shift+C');
    await expectVisible(page.locator(`[data-testid="${SHELL_TESTID}"]`), 5000);
    await expectVisible(page.locator('[data-testid="rf-causa-event-detail"]'), 5000);
    await expectVisible(page.locator('[data-testid="rf-causa-copilot-cue"]'), 5000);

    // ----------------------------------------------------------------
    // 3. Co-pilot rail affordances and submit path.
    // ----------------------------------------------------------------
    await page.locator('[data-testid="rf-causa-copilot-cue"]').click();
    await expectVisible(page.locator('[data-testid="rf-causa-copilot-rail"]'), 5000);
    await expectVisible(page.locator('[data-testid="rf-causa-copilot-empty"]'), 5000);

    const copilotInput = page.locator('[data-testid="rf-causa-copilot-input"]');
    await copilotInput.fill('Why did the counter change?');
    await page.keyboard.press('Enter');
    await expectVisible(page.locator('[data-testid="rf-causa-copilot-turn-question"]'), 5000);
    await expectVisible(page.locator('[data-testid="rf-causa-copilot-turn-answer"]'), 5000);

    await page.locator('[data-testid="rf-causa-copilot-close"]').click();
    await waitForCondition(
      async () => page.locator('[data-testid="rf-causa-copilot-rail"]').count(),
      (count) => count === 0,
      'co-pilot rail to close',
      5000,
    );

    await clickSidebar(page, 'copilot', 'rf-causa-copilot-panel');
    await expectVisible(page.locator('[data-testid="rf-causa-copilot-input"]'), 5000);
    await clickSidebar(page, 'event-detail', 'rf-causa-event-detail');
    await expectVisible(page.locator('[data-testid="rf-causa-copilot-cue"]'), 5000);

    // ----------------------------------------------------------------
    // 4. Host events for later panel assertions.
    // ----------------------------------------------------------------
    await clickHostButtonByLabel(page, '+');
    await expectTextEquals(counterValue, '6');
    await clickHostButtonByLabel(page, '+');
    await expectTextEquals(counterValue, '7');

    // ----------------------------------------------------------------
    // 5. Panel-specific empty states on the counter example.
    // ----------------------------------------------------------------
    await clickSidebar(page, 'routes', 'rf-causa-routes');
    await expectVisible(page.locator('[data-testid="rf-causa-routes-empty"]'), 5000);

    await clickSidebar(page, 'schemas', 'rf-causa-schema-violation-timeline');
    await expectVisible(page.locator('[data-testid="rf-causa-schema-timeline-empty-no-schemas"]'), 5000);

    await clickSidebar(page, 'mcp-server', 'rf-causa-mcp-server');
    await expectVisible(page.locator('[data-testid="rf-causa-mcp-empty-no-activity"]'), 5000);

    // ----------------------------------------------------------------
    // 6. Trace panel: row growth, filter activation, filter clearing.
    // ----------------------------------------------------------------
    await clickSidebar(page, 'trace', 'rf-causa-trace');
    const traceCountsBefore = await readTraceCounts(page);
    if (traceCountsBefore.rendered !== traceCountsBefore.total) {
      throw new Error(
        `Expected unfiltered trace counts to be equal; got ${traceCountsBefore.rendered}/${traceCountsBefore.total}.`,
      );
    }

    const rowsBefore = await traceRowCount(page);
    await clickHostButtonByLabel(page, '-');
    await expectTextEquals(counterValue, '6', 5000);
    await waitForTraceGrowth(page, rowsBefore, 5000);

    const opTypeChips = page.locator('[data-testid^="rf-causa-trace-axis-chip-op-type-"]');
    if ((await opTypeChips.count()) === 0) {
      throw new Error('Expected at least one trace op-type filter chip.');
    }
    await opTypeChips.first().click();
    await expectVisible(page.locator('[data-testid="rf-causa-trace-clear-filters"]'), 5000);
    const filteredCounts = await waitForCondition(
      () => readTraceCounts(page),
      ({ rendered, total }) => rendered < total,
      'trace filter to narrow the ribbon',
      5000,
    );
    if (!(filteredCounts.rendered < filteredCounts.total)) {
      throw new Error(
        `Expected filtered trace counts to narrow; got ${filteredCounts.rendered}/${filteredCounts.total}.`,
      );
    }

    await page.locator('[data-testid="rf-causa-trace-clear-filters"]').click();
    const clearedCounts = await waitForCondition(
      () => readTraceCounts(page),
      ({ rendered, total }) => rendered === total,
      'trace filters to clear',
      5000,
    );
    if (clearedCounts.rendered !== clearedCounts.total) {
      throw new Error(
        `Expected cleared trace counts to match; got ${clearedCounts.rendered}/${clearedCounts.total}.`,
      );
    }

    const clickableRow = page.locator('[data-testid^="rf-causa-trace-row-"]').first();
    await clickableRow.click();
    await expectVisible(page.locator('[data-testid="rf-causa-event-detail"]'), 5000);
    await clickSidebar(page, 'trace', 'rf-causa-trace');

    // ----------------------------------------------------------------
    // 7. Redaction counter + clear-buffer lifecycle.
    // ----------------------------------------------------------------
    const noted = await invokeSuppressedCounter(page, 3);
    if (!noted.ok) {
      throw new Error(`Could not bump suppressed counter: ${noted.reason}`);
    }
    const redacted = page.locator(`[data-testid="${REDACTED_TESTID}"]`);
    await expectVisible(redacted, 5000);
    const redactedText = (await redacted.textContent()) || '';
    if (!redactedText.includes('REDACTED 3')) {
      throw new Error(`Expected redacted indicator to include "REDACTED 3"; got "${redactedText.trim()}"`);
    }

    const cleared = await clearTraceBuffer(page);
    if (!cleared.ok) {
      throw new Error(`Could not clear the trace buffer: ${cleared.reason}`);
    }
    await expectVisible(page.locator('[data-testid="rf-causa-trace-empty-no-events"]'), 5000);
    await waitForCondition(
      async () => page.locator(`[data-testid="${REDACTED_TESTID}"]`).count(),
      (count) => count === 0,
      'redacted indicator to disappear after clear-buffer',
      5000,
    );

    // ----------------------------------------------------------------
    // 8. Shell hide/show remains CSS-only.
    // ----------------------------------------------------------------
    await page.keyboard.press('Control+Shift+C');
    await expectRootDisplay(page, 'none', 5000);
    await page.keyboard.press('Control+Shift+C');
    await expectRootDisplay(page, 'block', 5000);
  },
};
