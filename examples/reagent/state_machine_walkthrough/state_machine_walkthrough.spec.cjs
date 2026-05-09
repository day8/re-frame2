/*
 * State-machines walkthrough — lockout scenario (rf2-vq2s).
 *
 * Walks the login flow through the chapter's canonical lockout path:
 *
 *   :idle -> :submitting -> :error-shown -> :idle  (×3)
 *   :idle -> :submitting -> :locked-out
 *
 * The example installs a per-frame `:fx-overrides` that redirects
 * `:rf.http/managed` to the `:auth.login/canned-failure` stub registered
 * in `core.cljc`, so every submit resolves :on-failure. After three
 * failures the `:under-retry-limit` guard fails and the second
 * `:auth.login/failure` clause's `:locked-out` target wins.
 *
 * Initial render: the status banner shows "idle"; the form is visible.
 * Each submit cycle drives the banner through "submitting" → an error
 * banner appears → "Dismiss" returns to "idle". The fourth submit
 * lands at "locked-out", swaps the form for the locked-out panel, and
 * the banner reads "locked-out".
 */

const {
  expectTextEquals,
  expectTextContains,
  expectVisible,
} = require('../../scripts/spec-helpers.cjs');

module.exports = {
  name: 'state-machines walkthrough (lockout)',
  url: '/state-machine-walkthrough/',
  run: async (page) => {
    const banner = page.locator('strong.state');
    const emailInput = page.locator('input[type="email"]');
    const passwordInput = page.locator('input[type="password"]');
    const submitBtn = page.locator('form.login-form button[type="submit"]');

    // Initial state: machine is idle (the snapshot is created on first
    // dispatch, so a freshly-mounted page reads (uninitialised) for
    // :auth.login/state — which is fine; the banner just shows
    // "(uninitialised)" until we submit. Once we submit, the chapter's
    // self-initialising machine semantics kick in (Spec 005 §Restore
    // semantics) and :state lands at :idle → :submitting in one beat.
    await expectVisible(submitBtn, 10000);

    const submitOnce = async () => {
      await emailInput.fill('user@example.com');
      await passwordInput.fill('wrongpass');
      await submitBtn.click();
    };

    const dismissOnce = async () => {
      await page.locator('.error-row button').click();
      await expectTextEquals(banner, 'idle', 5000);
    };

    // Three failed attempts cycle :submitting → :error-shown → :idle.
    for (let i = 0; i < 3; i++) {
      await submitOnce();
      // Eventually the failure resolves and the banner reflects :error-shown.
      await expectTextEquals(banner, 'error-shown', 10000);
      await dismissOnce();
    }

    // Fourth attempt: guard fails, transition lands at :locked-out.
    await submitOnce();
    await expectTextEquals(banner, 'locked-out', 10000);

    // The form is replaced by the locked-out panel.
    await expectTextContains(
      page.locator('.locked'),
      'Account locked',
      5000,
    );
  },
};
