/*
 * TodoMVC example — browser smoke/spec.
 *
 * Covers the official TodoMVC surface we care about here:
 * - focused new-todo input
 * - add + trim
 * - remaining count
 * - complete one item
 * - hash-filter routing + reload persistence
 * - edit save + escape cancel
 * - clear completed
 * - localStorage persistence shape
 */

const {
  expectAttribute,
  expectInputValue,
  expectTextContains,
  expectTextEquals,
} = require('../../implementation/scripts/spec-helpers.cjs');

async function expectCount(locator, expected, timeoutMs = 5000) {
  const start = Date.now();
  let last = null;
  while (Date.now() - start < timeoutMs) {
    last = await locator.count();
    if (last === expected) return;
    await new Promise((r) => setTimeout(r, 50));
  }
  throw new Error(`expected count ${expected} but got ${last} within ${timeoutMs}ms`);
}

async function expectFocusedNewTodo(page, timeoutMs = 5000) {
  const start = Date.now();
  let focused = false;
  while (Date.now() - start < timeoutMs) {
    focused = await page.evaluate(
      () => document.activeElement && document.activeElement.classList.contains('new-todo'),
    );
    if (focused) return;
    await new Promise((r) => setTimeout(r, 50));
  }
  throw new Error('expected the .new-todo input to be focused');
}

async function readTodos(page) {
  return page.evaluate(() => JSON.parse(localStorage.getItem('todos-reframe2') || '[]'));
}

module.exports = {
  name: 'todomvc',
  url: '/todomvc/',
  run: async (page) => {
    const newTodo = page.locator('.new-todo');
    const todoItems = page.locator('.todo-list li');
    const activeLink = page.locator('.filters a[href="#/active"]');
    const allLink = page.locator('.filters a[href="#/"]');
    const completedLink = page.locator('.filters a[href="#/completed"]');

    await newTodo.waitFor({ state: 'visible', timeout: 10000 });
    await expectFocusedNewTodo(page, 10000);

    if (await page.locator('.main').isVisible()) {
      throw new Error('expected .main to be hidden before any todos exist');
    }
    if (await page.locator('.footer').isVisible()) {
      throw new Error('expected .footer to be hidden before any todos exist');
    }

    await newTodo.fill('    buy oat milk    ');
    await newTodo.press('Enter');
    await expectInputValue(newTodo, '');

    await newTodo.fill('feed the cat');
    await newTodo.press('Enter');
    await newTodo.fill('book dentist appointment');
    await newTodo.press('Enter');

    await expectCount(todoItems, 3, 10000);
    await expectTextContains(todoItems.nth(0).locator('label'), 'buy oat milk');
    await expectTextContains(todoItems.nth(1).locator('label'), 'feed the cat');
    await expectTextContains(todoItems.nth(2).locator('label'), 'book dentist appointment');
    await expectTextEquals(page.locator('.todo-count'), '3 items left');

    await todoItems.nth(1).locator('.toggle').click({ force: true });
    await expectAttribute(todoItems.nth(1), 'class', 'completed');
    await expectTextEquals(page.locator('.todo-count'), '2 items left');

    const storedAfterToggle = await readTodos(page);
    if (!Array.isArray(storedAfterToggle) || storedAfterToggle.length !== 3) {
      throw new Error(`expected 3 todos in localStorage, got ${JSON.stringify(storedAfterToggle)}`);
    }
    if (storedAfterToggle.filter((todo) => todo.completed).length !== 1) {
      throw new Error(`expected 1 completed todo in localStorage, got ${JSON.stringify(storedAfterToggle)}`);
    }

    await activeLink.click();
    await expectAttribute(activeLink, 'class', 'selected');
    await expectCount(todoItems, 2);
    await expectTextContains(page.locator('.todo-list'), 'buy oat milk');
    await expectTextContains(page.locator('.todo-list'), 'book dentist appointment');

    await page.reload({ waitUntil: 'load' });
    await expectAttribute(activeLink, 'class', 'selected', 10000);
    await expectCount(todoItems, 2, 10000);
    await expectTextEquals(page.locator('.todo-count'), '2 items left');

    await allLink.click();
    await expectAttribute(allLink, 'class', 'selected');
    await expectCount(todoItems, 3);

    await todoItems.nth(0).locator('label').dblclick();
    const firstEdit = todoItems.nth(0).locator('.edit');
    await expectInputValue(firstEdit, 'buy oat milk');
    await firstEdit.fill('buy oat milk and bread');
    await firstEdit.press('Enter');
    await expectTextContains(todoItems.nth(0).locator('label'), 'buy oat milk and bread');

    await todoItems.nth(2).locator('label').dblclick();
    const thirdEdit = todoItems.nth(2).locator('.edit');
    await thirdEdit.fill('this should not persist');
    await thirdEdit.press('Escape');
    await expectTextContains(todoItems.nth(2).locator('label'), 'book dentist appointment');

    await completedLink.click();
    await expectAttribute(completedLink, 'class', 'selected');
    await expectCount(todoItems, 1);
    await expectTextContains(todoItems.nth(0).locator('label'), 'feed the cat');

    await page.locator('.clear-completed').click();
    await expectCount(todoItems, 0);
    await expectTextEquals(page.locator('.todo-count'), '2 items left');
    if (await page.locator('.clear-completed').isVisible()) {
      throw new Error('expected .clear-completed to be hidden after clearing completed items');
    }

    await allLink.click();
    await expectCount(todoItems, 2);
    await expectTextContains(todoItems.nth(0).locator('label'), 'buy oat milk and bread');
    await expectTextContains(todoItems.nth(1).locator('label'), 'book dentist appointment');

    const storedAfterClear = await readTodos(page);
    if (storedAfterClear.length !== 2 || storedAfterClear.some((todo) => todo.completed)) {
      throw new Error(`expected only 2 active todos after clear, got ${JSON.stringify(storedAfterClear)}`);
    }
  },
};
