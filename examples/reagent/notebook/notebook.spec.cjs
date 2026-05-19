/*
 * notebook — Reagent design-led example smoke (rf2-t7t6f).
 *
 * Three-pane editorial notebook (documents tree + markdown editor +
 * live preview). The smoke proves:
 *
 *   1. Initial render lands the seeded :welcome document — the
 *      textarea carries the body, the preview renders parsed
 *      markdown.
 *   2. Selecting a different doc updates the preview heading
 *      (the chained sub :selected → :selected-body → :selected-hiccup
 *      fires).
 *   3. Editing the body updates the preview (sub re-runs).
 */
const { expectVisible, expectTextContains, waitForValue } =
  require('../../../examples/scripts/spec-helpers.cjs');

module.exports = {
  name: 'notebook smoke (Reagent · design-led)',
  url:  '/notebook/',
  run:  async (page) => {
    const textarea = page.locator('[data-testid="notebook-textarea"]');
    const preview  = page.locator('[data-testid="notebook-preview"]');
    await expectVisible(textarea, 10000);
    await expectVisible(preview, 10000);

    // 1. Initial render — :welcome body in textarea, parsed in preview.
    await waitForValue(
      async () => await textarea.inputValue(),
      (v) => v.includes('Welcome to the notebook'),
      { timeoutMs: 10000, description: 'seeded welcome body in textarea' },
    );
    await expectTextContains(preview, 'Welcome to the notebook', 10000);

    // 2. Selecting a different doc updates the preview.
    await page.locator('[data-testid="notebook-doc-six-dominoes"]').click();
    await expectTextContains(preview, 'The dominoes');

    // 3. Editing the textarea re-renders the preview. We `fill` the
    //    textarea with new content (cleanest cross-platform path —
    //    avoids the `\n` → newline-in-textarea-vs-typed-keypress
    //    discrepancy between OSes) and assert the new content lands.
    await textarea.fill('# Hello\n\nSENTINEL-RF2-MARKER');
    await expectTextContains(preview, 'SENTINEL-RF2-MARKER');
  },
};
