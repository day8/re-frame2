/*
 * Managed HTTP counter — Spec 014 §`:rf.http/managed` smoke (rf2-cfig).
 *
 * - Initial render: count=0, status=idle.
 * - +1 button: real round-trip via Fetch to /api/inc.json (a static
 *   asset staged into out/examples/managed-http-counter/api/). The
 *   response decodes as JSON, the default reply addresses re-dispatches
 *   [:counter/+1 (assoc msg :rf/reply ...)], and the count advances by
 *   the server-returned :delta.
 * - Fail button: real 404 from http-server (path /api/does-not-exist).
 *   Classifies as :rf.http/http-4xx; the failure branch records the
 *   error kind and surfaces it in the UI.
 * - Retry-recover button: canned-stub seam — :rf.http/managed-canned-success
 *   synthesises a {:kind :success :value {:delta 5}} reply, exactly the
 *   shape the live fx would dispatch after a retry-recover round-trip.
 * - Cancel button: dispatches :rf.http/managed-abort by :request-id.
 *   The example uses the request-id surface to demonstrate the API;
 *   the JVM smoke covers the live round-trip cancellation contract.
 *
 * What this exercises:
 *   - The :rf.http/managed fx wires up Fetch correctly under Reagent.
 *   - The default reply-addressing path dispatches back to the originator
 *     with :rf/reply merged into the message.
 *   - The failure-category contract: a 4xx classifies as :rf.http/http-4xx.
 *   - The canned-stub fx-id resolves under Reagent the same as on the JVM.
 */

const { expectTextEquals, expectVisible } = require('../scripts/spec-helpers.cjs');

module.exports = {
  name: 'managed-http-counter',
  url: '/managed-http-counter/',
  run: async (page) => {
    const count  = page.locator('[data-testid="count"]');
    const status = page.locator('[data-testid="status"]');

    // Initial render: count=0, status=idle.
    await expectTextEquals(count, '0', 10000);
    await expectTextEquals(status, 'idle');

    // --- +1 (real Fetch round-trip via /api/inc.json) -----------------------
    await page.getByRole('button', { name: '+1' }).click();
    await expectTextEquals(count, '1');
    await expectTextEquals(status, 'idle');

    // The +1 button is idempotent against the static endpoint — clicking
    // again advances by another delta:1 the server returned.
    await page.getByRole('button', { name: '+1' }).click();
    await expectTextEquals(count, '2');

    // --- Fail (real 404 from http-server) -----------------------------------
    await page.getByRole('button', { name: 'Fail' }).click();
    await expectTextEquals(status, 'error');
    const error = page.locator('[data-testid="error"]');
    await expectVisible(error);
    // The failure category for a real 404 is :rf.http/http-4xx.
    const errorText = (await error.textContent()) || '';
    if (!errorText.includes(':rf.http/http-4xx')) {
      throw new Error(
        `expected error kind to be :rf.http/http-4xx, got "${errorText}"`,
      );
    }

    // --- Retry-recover (canned-stub seam, :delta 5 reply) -------------------
    // Count was 2 before the Fail click (which doesn't change the count).
    await page.getByRole('button', { name: 'Retry-recover' }).click();
    await expectTextEquals(count, '7');
    await expectTextEquals(status, 'idle');

    // --- Cancel — clicking the Cancel button dispatches the abort fx --------
    // The example uses :rf.http/managed-abort with a :request-id; the live
    // fx fires the abort handle on the in-flight request. We just assert
    // the UI returns to :idle (the abort fx itself is a noop for app state
    // beyond clearing :status; the JVM smoke covers the live abort contract).
    await page.getByRole('button', { name: 'Start long' }).click();
    await page.getByRole('button', { name: 'Cancel' }).click();
    await expectTextEquals(status, 'idle');
  },
};
