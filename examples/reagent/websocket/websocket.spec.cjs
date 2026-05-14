/*
 * Pattern-WebSocket worked example (rf2-yf97).
 *
 * The canonical re-frame2 demo for spec/Pattern-WebSocket.md. A small
 * UI drives a `:ws/connection` state machine through every key
 * Pattern-WebSocket lifecycle event — connect, reconnect, request-reply
 * correlation, server-pushed events — against an in-process mock
 * WebSocket server (no real ws endpoint needed).
 *
 * Coverage:
 *   - App shell mounts; status is DISCONNECTED.
 *   - Connect → status cascades through CONNECTING / AUTHENTICATING →
 *     CONNECTED. (The mock's `setTimeout`-microtask delivery means
 *     we land on CONNECTED within a few hundred ms.)
 *   - Send a request-reply — the correlated reply lands in the
 *     "Last correlated reply" panel.
 *   - Trigger a server push — the inbox grows.
 *   - Drop the connection (simulated transport error) — status
 *     becomes RECONNECTING then CONNECTED again after the :after
 *     backoff fires.
 */

const {
  expectVisible,
  expectTextContains,
  waitForValue,
} = require('../../scripts/spec-helpers.cjs');

module.exports = {
  name: 'pattern-websocket — connection machine demo',
  url: '/websocket/',
  run: async (page) => {
    // App shell mounts.
    await expectVisible(page.locator('h1:has-text("Pattern-WebSocket")'), 15000);
    await expectTextContains(
      page.locator('[data-testid="ws-status"]'),
      'DISCONNECTED',
      5000,
    );

    // Connect — wait through CONNECTING / AUTHENTICATING to CONNECTED.
    await page.locator('[data-testid="ws-connect"]').click();
    await expectTextContains(
      page.locator('[data-testid="ws-status"]'),
      'CONNECTED',
      10000,
    );

    // Issue a request-reply. The mock auto-echoes; the correlated
    // reply lands at [:messages :last-reply] and renders in the
    // "Last correlated reply" panel.
    await page.locator('[data-testid="ws-request"]').click();
    await expectVisible(
      page.locator('[data-testid="ws-last-reply"]'),
      5000,
    );
    await expectTextContains(
      page.locator('[data-testid="ws-last-reply"]'),
      ':ok true',
      5000,
    );

    // Trigger a manual server push. The inbox grows by one.
    const inbox = page.locator('[data-testid="ws-inbox"]');
    const before = await inbox.locator('li').count();
    await page.locator('[data-testid="ws-server-push"]').click();
    await page.waitForFunction(
      (prev) => document.querySelectorAll('[data-testid="ws-inbox"] li').length > prev,
      before,
      { timeout: 5000 },
    );

    // Drop the connection (simulated transport error). The status
    // pill flips to RECONNECTING; the :after backoff (base 100ms,
    // 2^retries up to 5000ms) re-enters :active; the cascade lands
    // back at CONNECTED within a few hundred ms.
    //
    // Read the reconnect-attempts counter BEFORE the drop so the
    // assertion below tests that it advanced (rf2-4dfjv). The
    // counter sources from the machine's :retries slot directly —
    // it's a load-bearing observable independent of the transient
    // RECONNECTING pill, which the cascade may resolve through too
    // fast for the spec to catch.
    const attemptsCounter = page.locator(
      '[data-testid="ws-reconnect-attempts"]',
    );
    const attemptsBefore = parseInt(
      (await attemptsCounter.textContent()) || '0',
      10,
    );
    await page.locator('[data-testid="ws-drop"]').click();
    // The canonical observable is the final CONNECTED state.
    await expectTextContains(
      page.locator('[data-testid="ws-status"]'),
      'CONNECTED',
      10000,
    );
    // Load-bearing assertion: the reconnect counter MUST advance.
    // Polled because the cascade re-enters :active synchronously but
    // :bump-retry runs as the :ws/closed action — its commit goes
    // through the dispatch queue.
    await waitForValue(
      async () =>
        parseInt((await attemptsCounter.textContent()) || '0', 10),
      (n) => n > attemptsBefore,
      {
        timeoutMs: 5000,
        description: `reconnect-attempts to advance past ${attemptsBefore}`,
      },
    );
  },
};
