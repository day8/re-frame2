/*
 * Causa shell — browser smoke (rf2-s2bhn).
 *
 * The :examples/counter shadow-cljs build pulls
 * day8.re-frame2-causa.preload into its :devtools/:preloads block (see
 * implementation/shadow-cljs.edn). That preload:
 *
 *   1. Registers Causa's :rf.causa/* handlers.
 *   2. Registers the trace-collector callback under
 *      :rf.causa/trace-collector.
 *   3. Attaches a global Ctrl+Shift+C keydown listener.
 *
 * The shell does NOT mount at preload time — first paint is gated on
 * the toggle keypress per spec/007-UX-IA.md §The default landing view
 * (the <80ms first-paint target relies on lazy substrate render).
 *
 * This spec is the only test in the repo that exercises the full
 * mount → keybinding → substrate-adapter render → shell hiccup tree →
 * panel routing pipeline end-to-end. Node-test alone cannot drive it
 * (no DOM, no substrate render, no event loop). It covers:
 *
 *   1. Mount: Causa is NOT in the DOM at page-load time.
 *   2. Toggle on: Ctrl+Shift+C mounts the shell and renders the
 *      sidebar with every panel entry.
 *   3. Panel handoff: clicking a sidebar entry switches the canvas to
 *      render that panel's distinctive `[data-testid]` element.
 *   4. Toggle off: a second Ctrl+Shift+C hides the shell's container
 *      (CSS-only display:none toggle per mount.cljs `set-visible!`).
 *   5. Live trace bus: with Causa visible, clicking the counter's '+'
 *      button produces a fresh trace row in the Trace panel — proves
 *      Causa is observing the framework's live trace bus.
 *   6. [● REDACTED N] indicator: firing a sensitive event via the
 *      page console bumps the bottom-rail's counter — proves
 *      config/note-suppressed! → :rf.causa/suppressed-sensitive-count
 *      sub → bottom-rail hint is wired through.
 */

const { expectTextEquals, expectVisible } = require('../../scripts/spec-helpers.cjs');

const SHELL_TESTID = 'rf-causa-shell';
const REDACTED_TESTID = 'rf-causa-redacted-indicator';

// Sidebar entry id → the distinctive top-level testid the canvas
// switches to when that entry is selected. Picked from each panel
// view's outermost [:section {:data-testid ...}] wrapper.
const PANEL_HANDOFFS = [
  { id: 'event-detail', canvas: 'rf-causa-event-detail' },
  { id: 'time-travel',  canvas: 'rf-causa-time-travel' },
  { id: 'app-db',       canvas: 'rf-causa-app-db-diff' },
  { id: 'causality',    canvas: 'rf-causa-causality-graph' },
  { id: 'trace',        canvas: 'rf-causa-trace' },
  { id: 'machines',     canvas: 'rf-causa-machine-inspector' },
];

module.exports = {
  name: 'causa',
  url: '/counter/',
  run: async (page) => {
    // ----------------------------------------------------------------
    // 1. Mount — counter is ready, Causa shell is NOT in the DOM yet.
    // ----------------------------------------------------------------
    //
    // The preload runs at app boot but defers shell mount until the
    // first toggle. Wait for the counter to be interactive (asserts the
    // page loaded and the substrate adapter installed via
    // (rf/init! ...) before we send keypresses), then assert Causa is
    // absent.
    const span = page.locator('span').first();
    await expectTextEquals(span, '5', 10000);

    if ((await page.locator(`[data-testid="${SHELL_TESTID}"]`).count()) !== 0) {
      throw new Error('Causa shell rendered at page-load; expected lazy mount on first Ctrl+Shift+C.');
    }

    // ----------------------------------------------------------------
    // 2. Toggle on — Ctrl+Shift+C mounts the shell + renders sidebar.
    // ----------------------------------------------------------------
    //
    // The keybinding listener is attached on document with capture
    // (see keybinding.cljs `attach!`); a top-level page.keyboard.press
    // dispatches the keydown against the page's focused element and
    // bubbles to document.
    await page.keyboard.press('Control+Shift+C');

    const shell = page.locator(`[data-testid="${SHELL_TESTID}"]`);
    await expectVisible(shell, 5000);

    // Every sidebar entry from shell.cljs's `sidebar-items` should be
    // present in the DOM — the sidebar is rendered eagerly even though
    // only the selected panel's canvas hydrates.
    const expectedSidebarIds = [
      'event-detail', 'time-travel', 'app-db', 'causality', 'subs',
      'fx', 'trace', 'machines', 'flows', 'routes', 'performance',
      'issues', 'schemas', 'hydration', 'mcp-server', 'copilot',
    ];
    for (const id of expectedSidebarIds) {
      const item = page.locator(`[data-testid="rf-causa-sidebar-item-${id}"]`);
      if ((await item.count()) === 0) {
        throw new Error(`Sidebar entry for "${id}" did not render`);
      }
    }

    // ----------------------------------------------------------------
    // 3. Panel handoff — clicking a sidebar entry swaps the canvas.
    // ----------------------------------------------------------------
    //
    // The default landing panel is :event-detail (registry.cljs
    // default-panel-id, per spec/007-UX-IA.md §Lock 7). Asserting it
    // is visible without clicking covers the default path; the loop
    // then clicks each remaining entry and verifies the canvas swap.
    await expectVisible(
      page.locator(`[data-testid="rf-causa-event-detail"]`),
      5000,
    );

    for (const { id, canvas } of PANEL_HANDOFFS) {
      await page.locator(`[data-testid="rf-causa-sidebar-item-${id}"]`).click();
      await expectVisible(page.locator(`[data-testid="${canvas}"]`), 5000);
    }

    // ----------------------------------------------------------------
    // 5. Live trace bus — counter click produces a Trace row.
    // ----------------------------------------------------------------
    //
    // Done before the toggle-off step so the shell is still visible
    // for the assertion. Select the Trace panel, snapshot the row
    // count (the buffer is non-empty after init), click '+' on the
    // counter, then assert the row count grew.
    //
    // The Trace panel renders a <ul data-testid="rf-causa-trace-feed">
    // containing <li data-testid="rf-causa-trace-row-<id>">. The 'no
    // events' / 'no matches' empty-state branches do NOT render the
    // feed so its absence is a meaningful signal in its own right.
    await page.locator(`[data-testid="rf-causa-sidebar-item-trace"]`).click();
    await expectVisible(page.locator(`[data-testid="rf-causa-trace"]`), 5000);

    const beforeRows = await page.locator('[data-testid^="rf-causa-trace-row-"]').count();

    await page.getByRole('button', { name: '+' }).click();
    await expectTextEquals(span, '6');

    const start = Date.now();
    let afterRows = beforeRows;
    while (Date.now() - start < 5000) {
      afterRows = await page.locator('[data-testid^="rf-causa-trace-row-"]').count();
      if (afterRows > beforeRows) break;
      await new Promise((r) => setTimeout(r, 50));
    }
    if (afterRows <= beforeRows) {
      throw new Error(
        `Trace panel row count did not grow after dispatch (before=${beforeRows}, after=${afterRows}).` +
        ' Expected the counter +1 click to land at least one new event in Causa\'s trace buffer.',
      );
    }

    // ----------------------------------------------------------------
    // 6. [● REDACTED N] indicator — config/note-suppressed! bumps the
    //     counter, the bottom-rail hint appears.
    // ----------------------------------------------------------------
    //
    // The collector path that bumps the counter is exercised by the
    // node-test sensitive_trace_cljs_test.cljc; this browser-level
    // assertion proves the wiring through the subscription and the
    // shell's bottom-rail render.
    //
    // We dial the counter directly via the exposed CLJS namespace
    // (shadow-cljs dev compilation preserves the namespace path on
    // goog.global). Then we dispatch a no-op counter click to force
    // the subscription graph to recompute — the
    // :rf.causa/suppressed-sensitive-count sub thunks a plain atom
    // and only re-fires when the surrounding sub-graph recomputes.
    const noted = await page.evaluate(() => {
      const cfg =
        window.day8 &&
        window.day8.re_frame2_causa &&
        window.day8.re_frame2_causa.config;
      if (!cfg || typeof cfg.note_suppressed_BANG_ !== 'function') {
        return { ok: false, reason: 'day8.re_frame2_causa.config.note-suppressed! not on window' };
      }
      cfg.note_suppressed_BANG_(null);
      cfg.note_suppressed_BANG_(null);
      cfg.note_suppressed_BANG_(null);
      return { ok: true, count: typeof cfg.suppressed_count === 'function' ? cfg.suppressed_count() : null };
    });
    if (!noted.ok) {
      throw new Error(`Could not bump suppressed counter: ${noted.reason}`);
    }

    // Force a host dispatch so the Causa subscribe-graph recomputes
    // and the bottom-rail re-renders against the bumped counter.
    await page.getByRole('button', { name: '+' }).click();
    await expectTextEquals(span, '7');

    const redacted = page.locator(`[data-testid="${REDACTED_TESTID}"]`);
    await expectVisible(redacted, 5000);
    const redactedText = (await redacted.textContent()) || '';
    if (!redactedText.includes('REDACTED 3')) {
      throw new Error(
        `Expected redaction hint to read "● REDACTED 3" — got "${redactedText.trim()}"`,
      );
    }

    // ----------------------------------------------------------------
    // 4. Toggle off — Ctrl+Shift+C hides the shell container.
    // ----------------------------------------------------------------
    //
    // mount.cljs `close!` sets the container's display to 'none' rather
    // than unmounting React, so the [data-testid="rf-causa-shell"]
    // element still exists in the DOM tree but its enclosing
    // <div id="rf-causa-root"> has display:none. We assert visibility
    // is false on the root container.
    await page.keyboard.press('Control+Shift+C');

    // Poll until the root container reports display:none — the
    // toggle handler runs synchronously but the CSS update settles
    // on the next paint.
    const start2 = Date.now();
    let display = null;
    while (Date.now() - start2 < 5000) {
      display = await page.evaluate(() => {
        const root = document.getElementById('rf-causa-root');
        return root ? getComputedStyle(root).display : 'missing';
      });
      if (display === 'none') break;
      await new Promise((r) => setTimeout(r, 50));
    }
    if (display !== 'none') {
      throw new Error(`Expected #rf-causa-root display:none after second toggle; got "${display}".`);
    }

    // Sanity — a third toggle re-opens the shell (CSS-only show, no
    // re-mount per spec/007-UX-IA.md §The default landing view).
    await page.keyboard.press('Control+Shift+C');
    const display2 = await page.evaluate(() => {
      const root = document.getElementById('rf-causa-root');
      return root ? getComputedStyle(root).display : 'missing';
    });
    if (display2 === 'none' || display2 === 'missing') {
      throw new Error(`Expected #rf-causa-root visible after third toggle; got "${display2}".`);
    }
  },
};
