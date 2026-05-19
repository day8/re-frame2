/*
 * Causa-as-Story-RHS regression scenarios (rf2-drprn).
 *
 * PR #1478 (rf2-sgdd3) retired Story's RHS scrubber / trace / actions
 * panels; the RHS is now a `[data-rf-causa-host]` slot into which the
 * Story shell auto-mounts the Causa 4-layer chrome on every variant-
 * selection edge (see `causa-preset/ensure-causa-mounted!`). The four
 * scenarios below are Causa-side replacements for the four retired
 * Story-side scenarios:
 *
 *   retired (Story RHS)             →  replacement (Causa-in-Story RHS)
 *   --------------------------------    -------------------------------
 *   story-actions-row                →  variant dispatch surfaces in
 *                                       Causa Trace tab counts
 *   story-scrubber-slider            →  Causa L1 nav `[◀]`/`[▶]` steps
 *                                       update focus (Event-detail
 *                                       dispatch-id)
 *   story-trace-cascade-row          →  clicking an L2 event row renders
 *                                       Event-tab cascade detail
 *   story-trace-scrub-note           →  (no replacement — the L1 mode
 *                                       pill that previously carried
 *                                       LIVE / RETRO was dropped in
 *                                       PR #1509 / rf2-g9pee; mode is
 *                                       derivable from spine focus +
 *                                       `[◀ ▶ ⏭]` cluster, not from a
 *                                       dedicated DOM surface)
 *
 * Each scenario is a thin regression smoke — one assertion per
 * contract, not an exhaustive walk. Causa's own deep coverage lives in
 * tools/causa/testbeds/.
 *
 * The dedicated testbed exists rather than reusing
 * tools/story/testbeds/counter_with_stories/ because adding the Causa
 * preload to that build surfaces real embedding collisions (Story's
 * <nav> sidebar vs. Causa's <nav> tab bar; Story's Ctrl+K command
 * palette vs. Causa's Ctrl+K palette; getByText('Schema') matching
 * Causa's frame-picker <option> for the schema-invalid variant). The
 * minimal testbed sidesteps all three by under-featuring the Story
 * surface (no command palette, no schema panel, no mode tabs).
 */

const {
  expectVisible,
  waitForValue,
} = require('../../../../examples/scripts/spec-helpers.cjs');

function urlFor(page, p) {
  return new URL(p, page.url()).href;
}

async function primeHelpDismissed(page) {
  await page.evaluate(() => {
    try {
      localStorage.setItem('re-frame.story/seen-help-v1', '1');
    } catch (_) {
      /* ignore */
    }
  });
}

async function dismissHelpIfOpen(page) {
  const help = page.getByRole('dialog', { name: /Story playground help/i });
  if (await help.isVisible().catch(() => false)) {
    await help.getByRole('button', { name: /Got it/i }).click();
  }
}

// Story sidebar landmark — anchor through `data-test="story-sidebar"`
// rather than the bare `<nav>` role because the embedded Causa shell
// renders its own `<nav data-testid="rf-causa-tab-bar">` inside the
// RHS aside, which would make `getByRole('navigation')` ambiguous
// under Playwright's strict-mode locator API.
function storySidebar(page) {
  return page.locator('[data-test="story-sidebar"]');
}

async function gotoStory(page, p) {
  await page.goto(urlFor(page, p), { waitUntil: 'load' });
  await primeHelpDismissed(page);
  await expectVisible(storySidebar(page), 10000);
  await expectVisible(page.getByRole('main'), 10000);
  await dismissHelpIfOpen(page);
}

async function clickVariant(page, slashName) {
  const row = storySidebar(page).getByText(slashName, { exact: false }).first();
  await row.waitFor({ state: 'visible', timeout: 10000 });
  await row.click();
}

function canvas(page, variantId) {
  return page.locator(
    `main section[aria-label="Variant canvas"][data-test-variant="${variantId}"]`,
  );
}

async function waitForCanvas(page, variantId) {
  await canvas(page, variantId).waitFor({ state: 'visible', timeout: 10000 });
}

async function waitForCausaShell(page) {
  const host = page.locator('[data-test="story-rhs-causa-host"]');
  await host.waitFor({ state: 'visible', timeout: 10000 });
  const shell = page.locator('[data-testid="rf-causa-shell"]');
  await shell.waitFor({ state: 'visible', timeout: 10000 });
  return shell;
}

async function selectVariant(page) {
  await primeHelpDismissed(page);
  await gotoStory(page, '/causa-rhs-smoke/');
  await clickVariant(page, '/loaded');
  await waitForCanvas(page, ':story.counter/loaded');
  await waitForCausaShell(page);
}

module.exports = {
  name: 'causa-rhs-smoke (rf2-drprn)',
  url: '/causa-rhs-smoke/',
  run: async (page) => {
    // ----- Scenario 1: variant dispatch surfaces in Causa Trace ----------
    //
    // Replacement for retired `story-actions-row`. The Story-side
    // Actions panel polled `[data-test="story-actions-row"]` rows in
    // chronological order; the Causa-side equivalent reads the Trace
    // tab's `[data-testid="rf-causa-trace-counts"]` `N / M in view`
    // total and proves it ticks up after the variant fires
    // `:counter/inc` on its frame.

    await selectVariant(page);
    await page.locator('[data-testid="rf-causa-tab-trace"]').click();
    await expectVisible(page.locator('[data-testid="rf-causa-trace"]'), 5000);

    const parseTotal = (text) => {
      const m = /(\d+)\s*\/\s*(\d+)\s+in view/.exec(text || '');
      if (!m) throw new Error(`could not parse trace counts: ${JSON.stringify(text)}`);
      return Number(m[2]);
    };
    const readTotal = async () => parseTotal(
      (await page.locator('[data-testid="rf-causa-trace-counts"]').textContent()) || '',
    );

    const before = await readTotal();

    const loadedCanvas = canvas(page, ':story.counter/loaded');
    await loadedCanvas.locator('[data-test="inc"]').first().click();

    await waitForValue(
      () => readTotal(),
      (total) => total > before,
      {
        timeoutMs: 5000,
        description: 'Causa Trace tab total ticks up after variant :counter/inc',
      },
    );

    // ----- Scenario 2: L2 row click renders Event-detail cascade ---------
    //
    // Replacement for retired `story-trace-cascade-row`. The Story-side
    // trace panel rendered six-column cascade rows; clicking one
    // selected that cascade. The Causa-side equivalent: clicking an L2
    // event row (`rf-causa-event-row-<dispatch-id>`) dispatches
    // `:rf.causa/focus-cascade` and the L4 Event-detail panel rebinds
    // to that cascade, exposing `rf-causa-event-detail-cascade`.

    await page.locator('[data-testid="rf-causa-tab-event"]').click();
    await expectVisible(page.locator('[data-testid="rf-causa-detail-panel-event"]'), 5000);

    await loadedCanvas.locator('[data-test="inc"]').first().click();

    const rows = page.locator('[data-testid^="rf-causa-event-row-"]');
    await waitForValue(
      () => rows.count(),
      (count) => count >= 1,
      { timeoutMs: 5000, description: 'L2 event list populates after :counter/inc' },
    );
    await rows.first().click();

    await expectVisible(
      page.locator('[data-testid="rf-causa-event-detail-cascade"]'),
      5000,
    );

    // ----- Scenario 3: L1 nav steps focus through the spine --------------
    //
    // Replacement for retired `story-scrubber-slider`. The Story-side
    // scrubber drove per-variant epoch nav; the Causa-side equivalent
    // is the L1 ribbon's `[◀]` / `[▶]` cluster
    // (`rf-causa-nav-prev` / `-next`). With ≥1 cascade in the bus the
    // prev button is enabled (`aria-disabled="false"`); clicking it
    // pulls focus off the head and the Event-detail panel rebinds.
    // The L2 row click above already moved focus into RETRO; we
    // re-snap to LIVE first so the prev-step assertion measures the
    // ribbon nav specifically.

    await page.locator('[data-testid="rf-causa-nav-head"]').click();

    const prev = page.locator('[data-testid="rf-causa-nav-prev"]');
    await prev.waitFor({ state: 'visible', timeout: 5000 });
    await waitForValue(
      () => prev.getAttribute('aria-disabled'),
      (value) => value === 'false',
      { timeoutMs: 5000, description: 'L1 nav-prev becomes enabled once cascades land' },
    );
    await prev.click();

    // The cascade detail surface MUST render after a step — proves
    // the spine step reached the Event-detail panel through
    // `:rf.causa/focus`.
    await expectVisible(
      page.locator('[data-testid="rf-causa-event-detail-cascade"]'),
      5000,
    );

    // ----- (Retired Scenario 4: mode pill reflected LIVE ↔ RETRO) --------
    //
    // The Story-side `story-trace-scrub-note` originally mapped to the
    // L1 `rf-causa-mode-pill` chip. PR #1509 / rf2-g9pee dropped the
    // mode-pill entirely: LIVE / RETRO state is derivable from spine
    // focus (`:focus :mode :head? :paused?`) + the existing
    // `[◀ ▶ ⏭]` cluster, and Space / L / G keybindings cover the
    // toggle. No replacement DOM surface exists, so the assertion is
    // intentionally absent here.
  },
};
