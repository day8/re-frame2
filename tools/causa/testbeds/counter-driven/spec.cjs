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
 * The shell now mounts by default into the app-provided inline host
 * (`[data-rf-causa-host]`) once the runtime is ready. Ctrl+Shift+C
 * toggles the already-mounted shell's visibility.
 *
 * This spec is the only test in the repo that exercises the full
 * mount → keybinding → substrate-adapter render → shell hiccup tree →
 * panel routing pipeline end-to-end. Node-test alone cannot drive it
 * (no DOM, no substrate render, no event loop). It covers:
 *
 *   1. Mount: Causa auto-mounts inline on page load.
 *   2. Sidebar: the default inline shell renders every panel entry.
 *   3. Panel handoff: clicking a sidebar entry switches the canvas to
 *      render that panel's distinctive `[data-testid]` element.
 *   4. Toggle off: a second Ctrl+Shift+C hides the shell's container
 *      (CSS-only display:none toggle per mount.cljs `set-visible!`).
 *   5. Live trace bus: with Causa visible, clicking the counter's '+'
 *      button produces a fresh trace row in the Trace panel — proves
 *      Causa is observing the framework's live trace bus.
 *   6. [● REDACTED N] indicator: calling `config/note-suppressed!`
 *      from the page console bumps the bottom-rail's counter and
 *      the indicator re-renders IMMEDIATELY — proves the reactive
 *      wiring through the sub-graph (rf2-0vxdn — the call
 *      dispatches `:rf.causa/note-sensitive-suppressed` so the
 *      sub fires on the standard app-db write path; no host-
 *      dispatch workaround is required).
 */

const { expectTextEquals, expectVisible } = require('../../../../examples/scripts/spec-helpers.cjs');

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
    // 1. Mount — counter is ready, Causa shell auto-mounted inline.
    // ----------------------------------------------------------------
    //
    // Wait for the counter to be interactive (asserts the page loaded
    // and the substrate adapter installed via (rf/init! ...)), then
    // assert Causa mounted into the explicit host beside the app.
    const span = page.locator('#app [data-testid="counter-value"]');
    await expectTextEquals(span, '5', 10000);

    const shell = page.locator(`[data-testid="${SHELL_TESTID}"]`);
    await expectVisible(shell, 5000);
    const inline = await page.evaluate(() => {
      const root = document.getElementById('rf-causa-root');
      const host = document.querySelector('[data-rf-causa-host]');
      const shell = document.querySelector('[data-testid="rf-causa-shell"]');
      return {
        rootMode: root && root.getAttribute('data-rf-causa-mode'),
        shellMode: shell && shell.getAttribute('data-mode'),
        rootParentIsHost: Boolean(root && host && root.parentElement === host),
      };
    });
    if (inline.rootMode !== 'inline' || inline.shellMode !== 'inline' || !inline.rootParentIsHost) {
      throw new Error(`Expected Causa to auto-mount in the inline host; got ${JSON.stringify(inline)}`);
    }

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
    // counter, then assert the total count grew.
    //
    // The Trace panel renders a <ul data-testid="rf-causa-trace-feed">
    // containing <li data-testid="rf-causa-trace-row-<id>">. The 'no
    // events' / 'no matches' empty-state branches do NOT render the
    // feed so its absence is a meaningful signal in its own right.
    //
    // Per the rigorous spec — we assert on the panel's `:total`
    // count (read off `[data-testid="rf-causa-trace-counts"]`)
    // rather than the DOM row count. The 200-row rendering cap +
    // the per-row sub-element testid scheme (`-time`, `-operation`,
    // etc all matching the `rf-causa-trace-row-` prefix) make the
    // DOM count a noisy signal; `total` reflects the underlying
    // buffer size which monotonically grows on every dispatch up
    // to the 1000-event buffer cap.
    await page.locator(`[data-testid="rf-causa-sidebar-item-trace"]`).click();
    await expectVisible(page.locator(`[data-testid="rf-causa-trace"]`), 5000);

    const parseTotal = (text) => {
      const m = /(\d+)\s*\/\s*(\d+)\s+in view/.exec(text || '');
      if (!m) throw new Error(`could not parse counts: ${JSON.stringify(text)}`);
      return Number(m[2]);
    };
    const readTotal = async () => parseTotal(
      (await page.locator('[data-testid="rf-causa-trace-counts"]').textContent()) || '',
    );

    const totalBefore = await readTotal();

    await page.locator('#app').getByRole('button', { name: '+' }).click();
    await expectTextEquals(span, '6');

    const start = Date.now();
    let totalAfter = totalBefore;
    while (Date.now() - start < 5000) {
      totalAfter = await readTotal();
      if (totalAfter !== totalBefore) break;
      await new Promise((r) => setTimeout(r, 50));
    }
    if (totalAfter === totalBefore) {
      throw new Error(
        `Trace panel total count did not change after dispatch (before=${totalBefore}, after=${totalAfter}).` +
        ' Expected the counter +1 click to land at least one new event in Causa\'s trace buffer.',
      );
    }

    // ----------------------------------------------------------------
    // 6. [● REDACTED N] indicator — config/note-suppressed! bumps
    //     the counter and the bottom-rail hint re-renders
    //     IMMEDIATELY (rf2-0vxdn).
    // ----------------------------------------------------------------
    //
    // The collector path that emits this call is exercised by the
    // node-test sensitive_trace_cljs_test.cljc; this browser-level
    // assertion proves the reactive wiring through Causa's app-db
    // slot, the subscription, and the shell's bottom-rail render.
    //
    // Per rf2-0vxdn `config/note-suppressed!` itself dispatches
    // `:rf.causa/note-sensitive-suppressed` into `:rf/causa` (the
    // atom-bump remains for JVM tests; the dispatch is the
    // reactive surface for CLJS). The sub reads `:suppressed-
    // counters` off Causa's app-db, so the indicator re-renders on
    // the next React commit without any extra host dispatch.
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

    // No host-dispatch workaround required — the bump itself
    // dispatched `:rf.causa/note-sensitive-suppressed`, so the
    // bottom-rail sub re-fired and the indicator is now visible.
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

    // Sanity — a second toggle re-opens the shell (CSS-only show, no
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
