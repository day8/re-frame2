/*
 * 7GUIs #5 — CRUD — smoke test (rf2-w3vn).
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

const { expectVisible } = require('../../../scripts/spec-helpers.cjs');

module.exports = {
  name: 'crud (7guis #5)',
  url: '/crud/',
  run: async (page) => {
    const selectList = page.locator('select.list');
    const inputs = page.locator('input[type="text"]');
    const filterInput = inputs.nth(0);
    const nameInput = inputs.nth(1);
    const surnameInput = inputs.nth(2);
    const createBtn = page.getByRole('button', { name: /create/i });
    const updateBtn = page.getByRole('button', { name: /update/i });
    const deleteBtn = page.getByRole('button', { name: /delete/i });

    await expectVisible(selectList, 10000);

    // Initial: 3 seeded people.
    await page.waitForFunction(
      () => document.querySelectorAll('select.list option').length === 3,
      null,
      { timeout: 10000 },
    );

    // Filter: prefix "M" -> 1 entry (Mustermann).
    await filterInput.fill('M');
    await page.waitForFunction(
      () => document.querySelectorAll('select.list option').length === 1,
      null,
      { timeout: 5000 },
    );

    // Clear filter, list back to 3.
    await filterInput.fill('');
    await page.waitForFunction(
      () => document.querySelectorAll('select.list option').length === 3,
      null,
      { timeout: 5000 },
    );

    // Create: type Anna Sonnen, click Create -> list grows to 4.
    await nameInput.fill('Anna');
    await surnameInput.fill('Sonnen');
    await createBtn.click();
    await page.waitForFunction(
      () => document.querySelectorAll('select.list option').length === 4,
      null,
      { timeout: 5000 },
    );

    // Update: select the Anna Sonnen entry by its visible label, change
    // surname, click Update; the list option's text reflects the new value.
    await selectList.selectOption({ label: 'Sonnen, Anna' });
    await page.waitForFunction(
      () => {
        const sel = document.querySelector('select.list');
        return sel && sel.value !== '';
      },
      null,
      { timeout: 5000 },
    );
    await surnameInput.fill('Sunnybaum');
    await updateBtn.click();
    await page.waitForFunction(
      () => Array.from(document.querySelectorAll('select.list option'))
        .some((o) => o.textContent === 'Sunnybaum, Anna'),
      null,
      { timeout: 5000 },
    );

    // Delete: list shrinks back to 3.
    await deleteBtn.click();
    await page.waitForFunction(
      () => document.querySelectorAll('select.list option').length === 3,
      null,
      { timeout: 5000 },
    );
  },
};
