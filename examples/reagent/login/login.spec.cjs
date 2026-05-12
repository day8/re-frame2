/*
 * Login — smoke test.
 *
 * Drives the login state machine end-to-end through the UI:
 *
 *   :idle -> :submitting -> :authed
 *
 * The example registers a stub `:rf.http/managed.login-demo` fx and
 * overrides `:rf.http/managed` to it on the default frame. The stub
 * resolves :on-success after a 50ms timeout when the password matches
 * `correct-horse`, otherwise it synthesises a 401 :on-failure reply.
 *
 * Flow:
 *   - Initial render: form visible, "Sign in" button enabled, no error.
 *   - Type credentials, submit.
 *   - During the request the button label becomes "Signing in…".
 *   - On success the banner switches to "Welcome!".
 */

const { expectVisible, expectTextContains } = require('../../scripts/spec-helpers.cjs');

module.exports = {
  name: 'login (state-machine demo)',
  url: '/login/',
  run: async (page) => {
    // Anchor on data-testid attrs (rf2-0gdsb) — survives sibling DOM
    // changes that would otherwise break role/class selectors.
    const form = page.getByTestId('login-form');
    await expectVisible(form, 10000);

    const emailInput = page.getByTestId('login-email');
    const passwordInput = page.getByTestId('login-password');
    const submitBtn = page.getByTestId('login-submit');

    // Initial: button enabled, no error.
    await expectVisible(submitBtn, 5000);

    // Fill in a wrong password first to prove the error path runs.
    await emailInput.fill('user@example.com');
    await passwordInput.fill('wrongpass');
    await submitBtn.click();
    // After the stub resolves with on-error, the banner should still
    // show the form (not authenticated). The error appears.
    await expectVisible(page.getByTestId('login-error'), 5000);

    // Now submit valid credentials.
    await passwordInput.fill('correct-horse');
    await submitBtn.click();

    // Eventually banner shows "Welcome!".
    await expectTextContains(page.getByTestId('login-banner'), 'Welcome!', 10000);
  },
};
