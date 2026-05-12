/*
 * Login (Helix) — smoke test.
 *
 * Same machine + HTTP stub as examples/reagent/login and
 * examples/uix/login_uix; views go through the Helix adapter. Asserts
 * the same end-to-end flow:
 *
 *   :idle -> :submitting -> :authed (or :error-shown on bad creds)
 */

const { expectVisible, expectTextContains } = require('../../scripts/spec-helpers.cjs');

module.exports = {
  name: 'login-helix (state-machine demo)',
  url: '/login-helix/',
  run: async (page) => {
    // Anchor on data-testid attrs (rf2-0gdsb).
    const form = page.getByTestId('login-form');
    await expectVisible(form, 10000);

    const emailInput = page.getByTestId('login-email');
    const passwordInput = page.getByTestId('login-password');
    const submitBtn = page.getByTestId('login-submit');

    await expectVisible(submitBtn, 5000);

    await emailInput.fill('user@example.com');
    await passwordInput.fill('wrongpass');
    await submitBtn.click();
    await expectVisible(page.getByTestId('login-error'), 5000);

    await passwordInput.fill('correct-horse');
    await submitBtn.click();

    await expectTextContains(page.getByTestId('login-banner'), 'Welcome!', 10000);
  },
};
