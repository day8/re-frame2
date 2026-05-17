/*
 * Causa shell — browser smoke for the 4-layer-chrome refactor (rf2-xy4yb).
 *
 * Per `tools/causa/spec/018-Event-Spine.md` §2 the shell mounts four
 * stacked layers — L1 ribbon, L2 event list, L3 tab bar, L4 detail
 * panel. The legacy 16-panel sidebar + bottom rail is gone (spec/018
 * §1 Non-goals).
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
 * The shell mounts by default into the app-provided inline host
 * (`[data-rf-causa-host]`) once the runtime is ready. Ctrl+Shift+C
 * toggles the already-mounted shell's visibility.
 *
 * This spec covers the new 4-layer-chrome surface end-to-end:
 *
 *   1. Mount: Causa auto-mounts inline on page load.
 *   2. Chrome: all four layers (ribbon, event list, tab bar, detail
 *      panel) and the palette modal mount; legacy sidebar testids
 *      are absent.
 *   3. Ribbon: nav cluster, frame picker, filter pills, mode pill,
 *      right-icons cluster all render.
 *   4. Tab bar: all six tabs (Event / App-db / Views / Trace /
 *      Machines / Issues) render and clicking each tab updates the
 *      L4 detail panel's testid (`rf-causa-detail-panel-<tab>`).
 *   5. Live trace bus: with Causa visible, clicking the counter's '+'
 *      button produces a fresh trace row visible in the Trace tab.
 *   6. [● REDACTED N] indicator: calling `config/note-suppressed!`
 *      from the page console bumps the counter and the indicator
 *      re-renders IMMEDIATELY (per rf2-0vxdn).
 *   7. Toggle off: a second Ctrl+Shift+C hides the shell's container.
 */

const { expectTextEquals, expectVisible } = require('../../../../examples/scripts/spec-helpers.cjs');

const SHELL_TESTID = 'rf-causa-shell';
const REDACTED_TESTID = 'rf-causa-redacted-indicator';

// Tab id → the L4 detail panel testid the panel renders when the tab
// is active. The shell's `detail-panel` writes
// `rf-causa-detail-panel-<tab-id>` per spec/018 §5.
const TAB_HANDOFFS = [
  { id: 'event',    panel: 'rf-causa-detail-panel-event' },
  { id: 'app-db',   panel: 'rf-causa-detail-panel-app-db' },
  { id: 'views',    panel: 'rf-causa-detail-panel-views' },
  { id: 'trace',    panel: 'rf-causa-detail-panel-trace' },
  { id: 'machines', panel: 'rf-causa-detail-panel-machines' },
  { id: 'issues',   panel: 'rf-causa-detail-panel-issues' },
];

module.exports = {
  name: 'causa',
  url: '/counter/',
  run: async (page) => {
    // ----------------------------------------------------------------
    // 1. Mount — counter is ready, Causa shell auto-mounted inline.
    // ----------------------------------------------------------------
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
        shellMode: shell && shell.getAttribute('data-rf-causa-mode'),
        rootParentIsHost: Boolean(root && host && root.parentElement === host),
      };
    });
    if (inline.rootMode !== 'inline' || inline.shellMode !== 'inline' || !inline.rootParentIsHost) {
      throw new Error(`Expected Causa to auto-mount in the inline host; got ${JSON.stringify(inline)}`);
    }

    // ----------------------------------------------------------------
    // 2. Chrome — the four layers + palette mount; legacy gone.
    // ----------------------------------------------------------------
    await expectVisible(page.locator(`[data-testid="rf-causa-ribbon"]`), 5000);
    await expectVisible(page.locator(`[data-testid="rf-causa-event-list"]`), 5000);
    await expectVisible(page.locator(`[data-testid="rf-causa-tab-bar"]`), 5000);
    await expectVisible(page.locator(`[data-testid="rf-causa-detail-panel-event"]`), 5000);

    const legacySidebarCount = await page.locator('[data-testid^="rf-causa-sidebar-item-"]').count();
    if (legacySidebarCount !== 0) {
      throw new Error(
        `Expected no legacy sidebar rows post-rf2-xy4yb; got ${legacySidebarCount}. ` +
        `spec/018 §2 removed the 16-panel sidebar.`,
      );
    }

    // ----------------------------------------------------------------
    // 3. Ribbon clusters — five fixed-order regions per spec/018 §3.
    // ----------------------------------------------------------------
    await expectVisible(page.locator(`[data-testid="rf-causa-ribbon-nav"]`), 5000);
    // frame cluster is either a label OR a select depending on count
    const frameLabel = page.locator(`[data-testid="rf-causa-ribbon-frame"]`);
    const framePicker = page.locator(`[data-testid="rf-causa-ribbon-frame-picker"]`);
    const frameCount = (await frameLabel.count()) + (await framePicker.count());
    if (frameCount === 0) {
      throw new Error('Expected ribbon frame cluster (label or dropdown) to render');
    }
    await expectVisible(page.locator(`[data-testid="rf-causa-ribbon-filters"]`), 5000);
    await expectVisible(page.locator(`[data-testid="rf-causa-mode-pill"]`), 5000);
    await expectVisible(page.locator(`[data-testid="rf-causa-ribbon-icons"]`), 5000);

    // ----------------------------------------------------------------
    // 4. Tab bar — six tabs + L4 detail panel rebinding.
    // ----------------------------------------------------------------
    for (const { id, panel } of TAB_HANDOFFS) {
      const tab = page.locator(`[data-testid="rf-causa-tab-${id}"]`);
      if ((await tab.count()) === 0) {
        throw new Error(`Tab button for "${id}" did not render`);
      }
    }

    // Default landing tab is :event — already covered above; iterate
    // through the others and confirm the L4 detail panel testid
    // rebinds each time.
    for (const { id, panel } of TAB_HANDOFFS) {
      await page.locator(`[data-testid="rf-causa-tab-${id}"]`).click();
      await expectVisible(page.locator(`[data-testid="${panel}"]`), 5000);
    }

    // Snap back to the Event tab so subsequent steps land on a known
    // surface.
    await page.locator(`[data-testid="rf-causa-tab-event"]`).click();
    await expectVisible(page.locator(`[data-testid="rf-causa-detail-panel-event"]`), 5000);

    // ----------------------------------------------------------------
    // 5. Live trace bus — counter click produces a Trace row.
    // ----------------------------------------------------------------
    //
    // Switch to the Trace tab so the Panel mounts, then count rows
    // before / after a counter dispatch.
    await page.locator(`[data-testid="rf-causa-tab-trace"]`).click();
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
        `Trace panel total count did not change after dispatch (before=${totalBefore}, after=${totalAfter}).`,
      );
    }

    // ----------------------------------------------------------------
    // 6. [● REDACTED N] indicator — relocated to L1 ribbon next to
    //     the mode pill per rf2-xy4yb (was on the dead bottom rail).
    // ----------------------------------------------------------------
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

    const redacted = page.locator(`[data-testid="${REDACTED_TESTID}"]`);
    await expectVisible(redacted, 5000);
    const redactedText = (await redacted.textContent()) || '';
    if (!redactedText.includes('REDACTED 3')) {
      throw new Error(
        `Expected redaction hint to read "● REDACTED 3" — got "${redactedText.trim()}"`,
      );
    }

    // ----------------------------------------------------------------
    // 7. Toggle off — Ctrl+Shift+C hides the shell container.
    // ----------------------------------------------------------------
    await page.keyboard.press('Control+Shift+C');

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
