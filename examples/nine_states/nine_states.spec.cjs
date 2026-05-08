/*
 * Nine-states example — smoke test (rf2-lyj0).
 *
 * The example dispatches :app/initialise on startup, putting the app in
 * State 1 (Nothing). The control panel has buttons that drive it through
 * the other states. Smoke test transitions through a few:
 *
 * - Initial render: State 1 ("Welcome" / "Nothing" / "Get started" CTA).
 * - Click "5. Some": list shows multiple todos.
 * - Click "9. Archive (Done)": archived state, control buttons disabled.
 */

const { expectTextContains, expectAttribute } = require('../../implementation/scripts/spec-helpers.cjs');

module.exports = {
  name: 'nine-states',
  url: '/nine-states/',
  run: async (page) => {
    // State 1 (Nothing): the welcome heading is shown.
    await expectTextContains(
      page.locator('.state-nothing'),
      "haven't loaded any todos",
      10000,
    );

    // Drive into State 5 (Some).
    await page.getByRole('button', { name: '5. Some' }).click();
    await expectTextContains(page.locator('.state-some'), 'todos');

    // Archive (State 9 — Done). The archive button disables itself.
    await page.getByRole('button', { name: /Archive/ }).click();
    await expectTextContains(page.locator('.state-done'), 'archived');

    // After archive, the "5. Some" button is now disabled.
    await expectAttribute(page.getByRole('button', { name: '5. Some' }), 'disabled', '');
  },
};
