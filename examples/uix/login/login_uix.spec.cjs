/*
 * Login (UIx) — smoke test (rf2-3yij Decision 7).
 *
 * Same machine + HTTP stub as examples/reagent/login; views go through the
 * UIx adapter. Asserts the same end-to-end flow:
 *
 *   :idle -> :submitting -> :authed (or :error-shown on bad creds)
 */

const { expectVisible, expectTextContains } = require('../../scripts/spec-helpers.cjs');

module.exports = {
  name: 'login-uix (state-machine demo)',
  url: '/login-uix/',
  run: async (page) => {
    const form = page.locator('form.login-form');
    await expectVisible(form, 10000);

    const emailInput = page.locator('input[type="email"]');
    const passwordInput = page.locator('input[type="password"]');
    const submitBtn = page.locator('form.login-form button[type="submit"]');

    await expectVisible(submitBtn, 5000);

    await emailInput.fill('user@example.com');
    await passwordInput.fill('wrongpass');
    await submitBtn.click();
    await expectVisible(page.locator('p.error'), 5000);

    await passwordInput.fill('correct-horse');
    await submitBtn.click();

    await expectTextContains(page.locator('.banner'), 'Welcome!', 10000);
  },
};
