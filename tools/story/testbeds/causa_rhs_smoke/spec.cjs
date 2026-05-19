/*
 * Causa-as-Story-RHS regression scenarios (rf2-drprn).
 *
 * PR #1478 (rf2-sgdd3) retired Story's RHS scrubber / trace / actions
 * panels in favour of an embedded Causa surface. PR #1566 (rf2-v1ach)
 * then replaced the original "whole-shell mount into
 * [data-rf-causa-host]" with a per-panel embed: Story's RHS hosts ONE
 * Causa panel at a time (default `:event-detail`) under a chip-row
 * picker, with a popout chip for the full 4-layer shell. See
 * `tools/story/src/re_frame/story/ui/causa_embed.cljs` for the
 * embed contract.
 *
 * Two scenarios cover the embed at the Story / Causa seam:
 *
 *   §1 Embed surface mounts. After a variant becomes focused the
 *      Story RHS renders the per-panel embed wrapper
 *      `[data-test="story-causa-embed"]` with `data-active-panel`
 *      reporting the resolved panel, the chip-row picker exposing one
 *      chip per Causa panel, and the panel-host mount target. Proves
 *      the rf2-v1ach Story-side seam is wired end-to-end.
 *
 *   §2 Chip-row picker retargets the embed. Clicking the App-db chip
 *      flips `data-active-panel` to `app-db`. Proves the chip-row
 *      runtime override (`state/swap-state!`) reaches `effective-panel`
 *      and re-renders the embed wrapper with the new selection.
 *
 * Why these two and not also a "panel actually renders" assertion:
 * the deeper "the mounted Causa panel paints its DOM" is covered by
 * Causa's own testbeds under tools/causa/testbeds/ — those run the
 * panel views in isolation against their mount-fns. From the Story
 * seam's perspective the contract is "Story drives Causa's
 * mount-<panel>! at the right place with the right panel-id"; the
 * embed wrapper + the resolved panel-id (data-active-panel attribute)
 * is the public surface Story owns. (See rf2-senbl for the separate
 * mount-fn-resolution bug surfaced during this work.)
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
  expectAttribute,
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

// rf2-v1ach: the per-panel embed surface. The embed wrapper carries
// `data-test="story-causa-embed"` + a `data-active-panel` attribute
// reflecting which panel is currently mounted; the panel itself
// mounts inside `[data-test="story-causa-panel-host"]`. We wait on
// both to confirm the Story-side seam is wired through.
async function waitForCausaEmbed(page) {
  const embed = page.locator('[data-test="story-causa-embed"]');
  await embed.waitFor({ state: 'visible', timeout: 10000 });
  const host = page.locator('[data-test="story-causa-panel-host"]');
  await host.waitFor({ state: 'visible', timeout: 10000 });
  return embed;
}

async function selectVariant(page) {
  await primeHelpDismissed(page);
  await gotoStory(page, '/causa-rhs-smoke/');
  await clickVariant(page, '/loaded');
  await waitForCanvas(page, ':story.counter/loaded');
  await waitForCausaEmbed(page);
}

module.exports = {
  name: 'causa-rhs-smoke (rf2-drprn)',
  url: '/causa-rhs-smoke/',
  run: async (page) => {
    // ----- Scenario 1: embed surface mounts at the Story RHS ------------
    //
    // After variant focus the RHS renders the rf2-v1ach per-panel embed:
    //   - wrapper `[data-test="story-causa-embed"]`
    //   - resolved panel-id on the wrapper's `data-active-panel` attr
    //   - chip-row picker with one chip per Causa panel
    //   - mount target `[data-test="story-causa-panel-host"]`
    //
    // No story-side `:causa-panel` slot is declared on the testbed
    // story/variant, so the resolved panel is the embed's
    // `default-panel` (`:event-detail`).

    await selectVariant(page);

    const embed = page.locator('[data-test="story-causa-embed"]');
    await expectAttribute(embed, 'data-active-panel', 'event-detail', 5000);

    const chips = page.locator('[data-test="story-causa-panel-chip"]');
    await waitForValue(
      () => chips.count(),
      (count) => count >= 7,
      {
        timeoutMs: 5000,
        description: 'chip-row exposes one chip per Causa panel (>=7)',
      },
    );

    // ----- Scenario 2: chip-row picker retargets the embed --------------
    //
    // Clicking the App-db chip dispatches `state/swap-state!` to set
    // `:causa-panel :app-db`; `effective-panel` then beats the
    // story/variant slot with the user override and the wrapper
    // re-renders with `data-active-panel="app-db"`.

    const appDbChip = page.locator(
      '[data-test="story-causa-panel-chip"][data-causa-panel="app-db"]',
    );
    await appDbChip.waitFor({ state: 'visible', timeout: 5000 });
    await appDbChip.click();

    await expectAttribute(embed, 'data-active-panel', 'app-db', 5000);

    // ----- Variant dispatch sanity (best-effort) ------------------------
    //
    // Click the variant's `:counter/inc` to prove the canvas + Causa's
    // trace-bus collectors are wired without errors. The smoke
    // intentionally stops short of asserting the resulting cascade
    // surfaces in Causa's DOM — that's covered by Causa's own
    // testbeds against the panel views in isolation.

    const loadedCanvas = canvas(page, ':story.counter/loaded');
    await loadedCanvas.locator('[data-test="inc"]').first().click();
  },
};
