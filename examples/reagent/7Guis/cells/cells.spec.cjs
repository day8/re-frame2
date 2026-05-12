/*
 * 7GUIs #7 — Cells — smoke test.
 *
 * Exercises change propagation through the spreadsheet sub-graph:
 *
 *   A1 = 5
 *   B1 = =(+ A1 5)
 *   observe B1 = 10
 *
 *   Update A1 = 100
 *   observe B1 = 105 (propagated through the sub-graph)
 *
 * Cells are <td.cell> elements; clicking enters edit mode (an <input>);
 * Enter or blur commits.
 *
 * Cell coordinates: each row's <th> is the row number, each column's
 * <th> is the column letter (A..Z). Cells are <td> with no per-id
 * marker, so we locate them by row index + column index using nth().
 */

const { expectVisible } = require('../../../scripts/spec-helpers.cjs');

// Anchor on the per-cell `data-cell` attribute (rf2-0gdsb) — the
// previous nth(row)/nth(col) DOM-order selector broke whenever any
// non-cell node landed in the grid.
function cellLocator(page, id) {
  return page.locator(`td.cell[data-cell="${id}"]`);
}

async function commitCell(page, id, value) {
  const cell = cellLocator(page, id);
  await cell.click();
  const input = cell.locator('input');
  await input.waitFor({ state: 'visible', timeout: 5000 });
  await input.fill(value);
  await input.press('Enter');
}

async function waitForCellText(page, id, expected, timeoutMs = 5000) {
  const cell = cellLocator(page, id);
  const start = Date.now();
  let last = '';
  while (Date.now() - start < timeoutMs) {
    last = ((await cell.textContent()) || '').trim();
    if (last === expected) return;
    await new Promise((r) => setTimeout(r, 50));
  }
  throw new Error(
    `expected cell ${id} to read "${expected}" within ${timeoutMs}ms, got "${last}"`,
  );
}

module.exports = {
  name: 'cells (7guis #7)',
  url: '/cells/',
  run: async (page) => {
    await expectVisible(page.locator('table.cells-grid'), 15000);

    // Set A1 = 5.
    await commitCell(page, 'A1', '5');
    await waitForCellText(page, 'A1', '5');

    // Set B1 = =(+ A1 5)
    await commitCell(page, 'B1', '=(+ A1 5)');
    await waitForCellText(page, 'B1', '10');

    // Update A1 = 100; B1 should propagate to 105.
    await commitCell(page, 'A1', '100');
    await waitForCellText(page, 'B1', '105');
  },
};
