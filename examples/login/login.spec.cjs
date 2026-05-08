/*
 * Login — smoke test (rf2-w3vn).
 *
 * Drives the login state machine end-to-end through the UI:
 *
 *   :idle -> :submitting -> :authed
 *
 * The example registers a stub :http effect that resolves :on-success
 * after a 50ms timeout when the password matches `correct-horse`.
 *
 * Flow:
 *   - Initial render: form visible, "Sign in" button enabled, no error.
 *   - Type credentials, submit.
 *   - During the request the button label becomes "Signing in…".
 *   - On success the banner switches to "Welcome!".
 */

const { expectVisible, expectTextContains } = require('../../implementation/scripts/spec-helpers.cjs');

module.exports = {
  name: 'login (state-machine demo)',
  url: '/login/',
  run: async (page) => {
    const form = page.locator('form.login-form');
    await expectVisible(form, 10000);

    const emailInput = page.locator('input[type="email"]');
    const passwordInput = page.locator('input[type="password"]');
    const submitBtn = page.locator('form.login-form button[type="submit"]');

    // Initial: button enabled, no error.
    await expectVisible(submitBtn, 5000);

    // Fill in a wrong password first to prove the error path runs.
    await emailInput.fill('user@example.com');
    await passwordInput.fill('wrongpass');
    await submitBtn.click();
    // After the stub resolves with on-error, the banner should still
    // show the form (not authenticated). The error <p.error> appears.
    await expectVisible(page.locator('p.error'), 5000);

    // Now submit valid credentials.
    await passwordInput.fill('correct-horse');
    await submitBtn.click();

    // Eventually banner shows "Welcome!".
    await expectTextContains(page.locator('.banner'), 'Welcome!', 10000);
  },
};
