/*
 * 7GUIs #7 — Cells — smoke test (rf2-w3vn).
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

const { expectVisible } = require('./_helpers.cjs');

async function commitCell(page, row, col, value) {
  // row and col are zero-based, where row 0 is the header row in <thead>.
  // <tbody> rows start at row 1 (which is row 0 inside tbody). Each row
  // has one <th> for the row label, then COLS <td> cells. col is the
  // column index (A=0, B=1, ...) so the cell selector is nth(col) within
  // that row's <td> selector.
  const cell = page
    .locator('tbody tr')
    .nth(row)
    .locator('td.cell')
    .nth(col);
  await cell.click();
  const input = cell.locator('input');
  await input.waitFor({ state: 'visible', timeout: 5000 });
  await input.fill(value);
  await input.press('Enter');
}

async function readCell(page, row, col) {
  const cell = page
    .locator('tbody tr')
    .nth(row)
    .locator('td.cell')
    .nth(col);
  return (await cell.textContent()) || '';
}

module.exports = {
  name: 'cells (7guis #7)',
  url: '/cells/',
  run: async (page) => {
    await expectVisible(page.locator('table.cells-grid'), 15000);

    // Set A1 = 5  (row 0 is the row containing the cell labelled "1";
    // tbody's first <tr> has <th>1</th> then 26 cells).
    await commitCell(page, 0, 0, '5');
    await page.waitForFunction(
      () => {
        const cell = document.querySelectorAll('tbody tr')[0]
          .querySelectorAll('td.cell')[0];
        return cell && cell.textContent.trim() === '5';
      },
      null,
      { timeout: 5000 },
    );

    // Set B1 = =(+ A1 5)
    await commitCell(page, 0, 1, '=(+ A1 5)');
    await page.waitForFunction(
      () => {
        const cell = document.querySelectorAll('tbody tr')[0]
          .querySelectorAll('td.cell')[1];
        return cell && cell.textContent.trim() === '10';
      },
      null,
      { timeout: 5000 },
    );

    // Update A1 = 100; B1 should propagate to 105.
    await commitCell(page, 0, 0, '100');
    await page.waitForFunction(
      () => {
        const cell = document.querySelectorAll('tbody tr')[0]
          .querySelectorAll('td.cell')[1];
        return cell && cell.textContent.trim() === '105';
      },
      null,
      { timeout: 5000 },
    );
  },
};
