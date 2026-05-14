/*
 * Cross-cutting scenario #2 of 6 from rf2-fe84r §Section B.
 *
 *   "HTTP failure cascade visible as ordered `:rf.http/*` with
 *    category attribution."
 *
 * Exercises `testbeds/http_toggle/` (rf2-kzcim). The surface ships a
 * single button + outcome dropdown enumerating the eight `:rf.http/*`
 * failure categories from Spec 014 (plus the success path). One click
 * + one selection drives the configured outcome through the framework
 * and the reply envelope lands at the dispatched handler's `:rf/reply`
 * slot carrying the canonical `:failure :kind` category keyword.
 *
 * Observation surface — the testbed routes seven of eight failure
 * categories through `:http-toggle/canned-failure-with-trace` (a
 * per-testbed wrapper around `:rf.http/managed-canned-failure` that
 * replays the live failure path's `trace/emit-error!` emit; see
 * rf2-3g16l). Every failure click produces a category-attributed
 * `:operation :rf.http/<kind>` error-trace event whose `:tags :kind`
 * matches the dropdown selection — the same shape the live path's
 * `re-frame.http-transport/finalise-failure!` emits before
 * dispatching the reply. The reply payload also rides through to the
 * dispatched handler, which writes the category keyword onto app-db;
 * the substrate re-renders the `[data-testid="failure-kind"]`
 * mirror.
 *
 * Categories exercised — drive THREE categories that span the
 * classification surface: `:rf.http/http-4xx` (status-before-decode,
 * raw body retained), `:rf.http/timeout` (per-attempt timeout
 * synthesis), `:rf.http/decode-failure` (2xx → bad JSON →
 * decode-failure path). Walking all eight would add minutes for no
 * additional information — the contract is "each category survives
 * the canned-stub → reply round-trip with its `:kind` intact"; three
 * representative categories prove the wiring is per-category, not
 * collapsed to a single generic failure.
 */

const path = require('path');
const { expectVisible, expectTextEquals } = require(
  path.join('..', '..', 'examples', 'scripts', 'spec-helpers.cjs'),
);
const { readTraceEventsAsEdn, clearTraceBus, pollUntil } = require(
  path.join('..', 'spec-helpers.cjs'),
);

const CATEGORIES = [
  { optionValue: ':rf.http/http-4xx',       failureKind: ':rf.http/http-4xx' },
  { optionValue: ':rf.http/timeout',        failureKind: ':rf.http/timeout' },
  { optionValue: ':rf.http/decode-failure', failureKind: ':rf.http/decode-failure' },
];

module.exports = {
  name: 'cross-cutting #2 — HTTP failure cascade category attribution',
  url: '/testbeds/http-toggle/',
  run: async (page) => {
    await expectVisible(page.locator('[data-testid="http-toggle"]'), 10000);

    const outcomeSelect = page.locator('[data-testid="outcome-select"]');
    const goButton = page.locator('[data-testid="go"]');
    const statusSpan = page.locator('[data-testid="status"]');
    const failureKindSpan = page.locator('[data-testid="failure-kind"]');

    for (const { optionValue, failureKind } of CATEGORIES) {
      // Clear the trace bus so the find() below scopes to events
      // emitted by THIS click.
      const cleared = await clearTraceBus(page);
      if (!cleared.ok) {
        throw new Error(`could not clear trace bus: ${cleared.reason}`);
      }

      // Set the dropdown — the <select>'s `value=` attribute carries
      // the pr-str of the keyword (`":rf.http/http-4xx"`); match it
      // exactly so Playwright selects the right `<option>`.
      await outcomeSelect.selectOption({ value: optionValue });
      await goButton.click();

      // ---- Trace-bus contract — category-attributed error trace ----
      // The per-testbed wrapper fx
      // (`:http-toggle/canned-failure-with-trace`) emits a single
      // `:operation :rf.http/<kind>` error-trace event before
      // delegating to the framework canned stub — same shape the live
      // failure path's `finalise-failure!` emits. Asserting on this
      // event directly proves end-to-end category attribution on the
      // trace bus (the canonical Spec 009 observable), not via the
      // generic `:rf.fx/handled` proxy.
      const expectedOperation = new RegExp(
        `:operation\\s+${failureKind.replace(/\./g, '\\.')}`,
      );
      await pollUntil(async () => {
        const probe = await readTraceEventsAsEdn(page);
        if (!probe.ok) throw new Error(`could not read trace bus: ${probe.reason}`);
        return probe.events.find((s) =>
          /:op-type\s+:error/.test(s) &&
          expectedOperation.test(s) &&
          new RegExp(`:kind\\s+${failureKind.replace(/\./g, '\\.')}`).test(s),
        );
      }, `:operation ${failureKind} error trace after ${optionValue} Go click`, 10000);

      // ---- Reply contract — DOM mirror lands at the same category ---
      // The handler's `(:rf/reply msg)` branch reads
      // `(:kind (:failure msg))` off the reply payload and writes the
      // category keyword to `[:failure-kind]` in app-db. The substrate
      // re-renders the mirror; reading its text confirms the category
      // survived the canned-stub → reply → dispatch → sub → DOM
      // round-trip — the end-to-end "category attribution" contract.
      await expectTextEquals(statusSpan, 'error', 5000);
      await expectTextEquals(failureKindSpan, failureKind, 5000);
    }
  },
};
