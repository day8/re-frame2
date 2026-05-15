/*
 * dispatch-trace.e2e.cjs — pair2 dispatches [:counter/inc] against the
 * live fixture, observes the resulting epoch via trace-window.
 *
 * The counter starts at 5 (per `:counter/initialise`); after one
 * `[:counter/inc]` dispatch, the on-screen value reads "6" and a
 * matching :event-id :counter/inc epoch appears in the trace window.
 */
'use strict';

const { runShim, parseEdn, openPage } = require('./_helpers.cjs');

async function run(ctx) {
  const { browser, page } = await openPage(ctx);
  try {
    // Wait for the fixture to render (initial value is 5).
    await page.waitForSelector('#value');
    const before = (await page.textContent('#value')) || '';
    if (before.trim() !== '5') {
      throw new Error('fixture did not initialise to 5: got "' + before + '"');
    }

    // Dispatch through pair2's sync shim. --sync gives us a deterministic
    // epoch id once drain settles.
    const d = runShim(ctx, 'dispatch', [
      '[:counter/inc]',
      '--sync',
    ]);
    if (d.exit !== 0) {
      throw new Error('dispatch exit ' + d.exit + ': ' + d.stderr);
    }
    const dv = parseEdn(d.stdout);
    if (!dv || dv['ok?'] !== true) {
      throw new Error('dispatch did not return ok?: ' + d.stdout);
    }
    if (!dv['epoch-id']) {
      throw new Error('dispatch did not surface :epoch-id: ' + d.stdout);
    }

    // On-screen value should reflect the new state.
    const after = (await page.textContent('#value')) || '';
    if (after.trim() !== '6') {
      throw new Error('expected counter "6" after :counter/inc, got "' + after + '"');
    }

    // Trace window should carry the epoch.
    const t = runShim(ctx, 'trace-recent', ['10000']);
    if (t.exit !== 0) throw new Error('trace exit ' + t.exit + ': ' + t.stderr);
    const tv = parseEdn(t.stdout);
    if (!tv || tv['ok?'] !== true) throw new Error('trace !ok?: ' + t.stdout);
    const epochs = tv['epochs'];
    if (!Array.isArray(epochs) || epochs.length === 0) {
      throw new Error('no epochs in trace window: ' + t.stdout);
    }
    const sawInc = epochs.some(
      (e) => e && (e['event-id'] === ':counter/inc' || e.eventId === ':counter/inc'),
    );
    if (!sawInc) {
      throw new Error(
        ':counter/inc not in last-10s epochs: ' + JSON.stringify(epochs),
      );
    }
  } finally {
    await browser.close();
  }
}

module.exports = { run };
