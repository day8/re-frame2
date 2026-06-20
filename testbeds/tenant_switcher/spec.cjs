/*
 * tenant-switcher testbed — browser smoke (rf2-5e22yc, EP-0013/resources
 * Part-2 leak-boundary scenario 5).
 *
 * The LIVE demonstration of the scoped-cache leak boundary as a multi-SCOPE
 * consumer: one frame, two tenant scopes simultaneously live in one
 * resource cache, with the active scope deciding which entry a read can address. The
 * executable leak-boundary GUARANTEES (cross-user logout leak, wrong-scope
 * fail-closed read, scoped-invalidation isolation, lease-lifecycle
 * non-interference) are pinned by the CLJS unit suites
 * (resources_scope_leak_boundary_cljs_test.cljc +
 * resources_scoped_lease_lifecycle_cljs_test.cljc); this smoke asserts the
 * surface itself behaves — mount + switch + assert simultaneous isolation.
 *
 * Run via `npm run test:testbed-tenant-switcher` (the colocated runner
 * compiles :testbeds/tenant-switcher, serves it, and drives this spec).
 */
const {
  expectTextEquals,
  expectVisible,
} = require('../../examples/scripts/spec-helpers.cjs');

module.exports = {
  name: 'tenant-switcher scoped-cache isolation smoke',
  url: '/',
  run: async (page) => {
    const root = page.locator('[data-testid="tenant-switcher"]');
    await expectVisible(root, 10000);

    // The active panel + per-tenant cache witnesses.
    const activeTenant = page.locator('[data-testid="active-tenant"]');
    const activeMotto = page.locator('[data-testid="motto"]');
    const activeStatus = page.locator('[data-testid="status"]');
    const acmeWitnessMotto = page.locator('[data-testid="witness-motto-acme"]');
    const acmeWitnessStatus = page.locator('[data-testid="witness-status-acme"]');
    const globexWitnessMotto = page.locator('[data-testid="witness-motto-globex"]');
    const globexWitnessStatus = page.locator('[data-testid="witness-status-globex"]');

    const load = page.locator('[data-testid="load"]');
    const switchAcme = page.locator('[data-testid="switch-acme"]');
    const switchGlobex = page.locator('[data-testid="switch-globex"]');

    // --- Step 1: boot as acme, nothing loaded yet. ---
    await expectTextEquals(activeTenant, 'acme');
    await expectTextEquals(activeStatus, 'idle');
    await expectTextEquals(activeMotto, '(no data)'); // sentinel when no entry

    // Load acme's dashboard — the active scope is derived as acme.
    await load.click();
    await expectTextEquals(activeStatus, 'loaded');
    await expectTextEquals(activeMotto, 'acme-only secret');
    // The acme cache witness (explicit scope) agrees; globex has no entry.
    await expectTextEquals(acmeWitnessStatus, 'loaded');
    await expectTextEquals(acmeWitnessMotto, 'acme-only secret');
    await expectTextEquals(globexWitnessStatus, 'idle');
    await expectTextEquals(globexWitnessMotto, '(no data)'); // sentinel — no entry

    // --- Step 2: switch to globex. ---
    // CRITICAL leak assertion: the active panel immediately reads globex's
    // idle empty-state — NEVER acme's cached motto. The resolved scope is in
    // the key, so the active read structurally cannot address acme's entry.
    await switchGlobex.click();
    await expectTextEquals(activeTenant, 'globex');
    await expectTextEquals(activeStatus, 'idle');
    // Sentinel empty-state (never "acme-only secret"): the active read
    // resolves globex's scope and finds no entry, so it reads the sentinel
    // — it structurally cannot address acme's cached entry.
    await expectTextEquals(activeMotto, '(no data)');

    // Load globex's dashboard.
    await load.click();
    await expectTextEquals(activeStatus, 'loaded');
    await expectTextEquals(activeMotto, 'globex-only secret');

    // --- Step 3: BOTH tenants simultaneously live + isolated (scenario 5). ---
    // Both cache witnesses read :loaded with their OWN distinct motto at the
    // same time — two entries coexist in one cache, each addressable only by
    // its own scope.
    await expectTextEquals(acmeWitnessStatus, 'loaded');
    await expectTextEquals(acmeWitnessMotto, 'acme-only secret');
    await expectTextEquals(globexWitnessStatus, 'loaded');
    await expectTextEquals(globexWitnessMotto, 'globex-only secret');

    // --- Step 4: switch back to acme — its data is still cached. ---
    // The entry was never evicted; switching back reads it straight from
    // cache (no refetch), and it is acme's data, never globex's.
    await switchAcme.click();
    await expectTextEquals(activeTenant, 'acme');
    await expectTextEquals(activeStatus, 'loaded');
    await expectTextEquals(activeMotto, 'acme-only secret');
  },
};
