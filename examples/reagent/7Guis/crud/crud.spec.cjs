/*
 * 7GUIs #5 — CRUD — smoke test.
 *
 * Drives the master/detail pattern:
 *
 * - Initial render: list shows the 3 seeded people; Update/Delete are
 *   disabled (no selection); Create is enabled.
 * - Filter by surname prefix: list shrinks to just Mustermann when "M"
 *   is typed into the filter input.
 * - Create: type a name + surname, click Create, list grows by one.
 * - Update: select the new entry, change the surname, click Update,
 *   the list reflects the new value.
 * - Delete: click Delete, list shrinks back to the prior count.
 *
 * The Playwright Locator API is used to count <option> children of the
 * size-6 multi-row <select> that backs the list.
 */

const {
  expectCount,
  expectVisible,
  waitForValue,
} = require('../../../scripts/spec-helpers.cjs');

module.exports = {
  name: 'crud (7guis #5)',
  url: '/crud/',
  run: async (page) => {
    // Anchor on data-testid attrs (rf2-0gdsb) — the `inputs.nth(N)`
    // selectors were brittle against any sibling text-input landing
    // in the view.
    const selectList = page.getByTestId('crud-list');
    const options = selectList.locator('option');
    const filterInput = page.getByTestId('crud-filter');
    const nameInput = page.getByTestId('crud-name');
    const surnameInput = page.getByTestId('crud-surname');
    const createBtn = page.getByTestId('crud-create');
    const updateBtn = page.getByTestId('crud-update');
    const deleteBtn = page.getByTestId('crud-delete');

    await expectVisible(selectList, 10000);

    // Initial: 3 seeded people.
    await expectCount(options, 3, 10000);

    // Filter: prefix "M" -> 1 entry (Mustermann).
    await filterInput.fill('M');
    await expectCount(options, 1, 5000);

    // Clear filter, list back to 3.
    await filterInput.fill('');
    await expectCount(options, 3, 5000);

    // Create: type Anna Sonnen, click Create -> list grows to 4.
    await nameInput.fill('Anna');
    await surnameInput.fill('Sonnen');
    await createBtn.click();
    await expectCount(options, 4, 5000);

    // Update: select the Anna Sonnen entry by its visible label, change
    // surname, click Update; the list option's text reflects the new value.
    await selectList.selectOption({ label: 'Sonnen, Anna' });
    await waitForValue(
      () => selectList.inputValue(),
      (v) => v !== '',
      { timeoutMs: 5000, description: 'crud-list selection committed' },
    );
    await surnameInput.fill('Sunnybaum');
    await updateBtn.click();
    await waitForValue(
      () => options.allTextContents(),
      (texts) => texts.some((t) => t === 'Sunnybaum, Anna'),
      { timeoutMs: 5000, description: 'option text "Sunnybaum, Anna" present' },
    );

    // Delete: list shrinks back to 3.
    await deleteBtn.click();
    await expectCount(options, 3, 5000);
  },
};
