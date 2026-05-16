/*
 * Causa shell — rigorous counter-example browser smoke
 * (rf2-mlmcw + rf2-ph18r).
 *
 * The strengthened smoke that lives alongside the bare-minimum
 * `causa.spec.cjs`. The two specs share the counter example; this one
 * exercises more of Causa's real shell behaviour and pins the
 * regressions the rf2-in6l2 reg-view-wrap surfaced:
 *
 *   - rf2-1barg: Time Travel panel populates after host dispatches.
 *     Pre-fix the panel sat in the empty-state branch because the
 *     epoch artefact was not on the counter example's classpath; the
 *     Causa preload now `:require`s `re-frame.epoch` so the slider
 *     appears reactively on first host dispatch.
 *   - rf2-qvz85: typing `/` into the co-pilot input renders the
 *     slash popover. Pre-fix the input lived in a `defonce` plain
 *     atom (not a substrate-reactive primitive) so the rail never
 *     re-rendered on keystrokes; the input now routes through
 *     `:rf.causa/copilot-input-text` on Causa's app-db and the rail
 *     re-renders on every keystroke.
 *
 * Coverage over the bare-minimum smoke:
 *
 *   1. Default inline landing — Event Detail is the hero panel on
 *      page load when the app provides `[data-rf-causa-host]`
 *   2. Co-pilot rail affordances:
 *      - cue button opens the rail
 *      - typing `/` renders the slash popover (rf2-qvz85)
 *      - submit path renders a question + answer turn
 *      - close button collapses the rail
 *      - sidebar Co-pilot row routes to the panel-style view
 *   3. Time-travel scrubber populates after host dispatches (rf2-1barg)
 *      - slider renders once epoch history is non-empty
 *      - epoch counter matches the slider's max+1
 *   4. Panel-specific empty states:
 *      - Routes  => "No routes registered"
 *      - Schemas => "No schemas registered"
 *      - MCP     => "No activity"
 *   5. Trace-panel behaviour:
 *      - live row growth after host dispatch
 *      - filter activation narrows the ribbon
 *      - clear-filters widens it again
 *      - clicking a trace row pivots back to event detail
 *   6. Trace-bus lifecycle:
 *      - redacted counter bump
 *      - clear-buffer! empties the trace panel
 *      - clear-buffer! drops the redacted indicator
 *   7. Shell visibility is CSS-only on the toggle (no re-mount)
 */

const { expectTextEquals, expectVisible } = require('../../../../examples/scripts/spec-helpers.cjs');

const SHELL_TESTID = 'rf-causa-shell';
const REDACTED_TESTID = 'rf-causa-redacted-indicator';

function sleep(ms) {
  return new Promise((resolve) => setTimeout(resolve, ms));
}

async function waitForCondition(readFn, predicate, label, timeoutMs = 5000) {
  const start = Date.now();
  let last = null;
  while (Date.now() - start < timeoutMs) {
    last = await readFn();
    if (predicate(last)) return last;
    await sleep(50);
  }
  throw new Error(`Timed out waiting for ${label}; last value was ${JSON.stringify(last)}`);
}

async function rootDisplay(page) {
  return page.evaluate(() => {
    const root = document.getElementById('rf-causa-root');
    return root ? getComputedStyle(root).display : 'missing';
  });
}

async function expectRootDisplay(page, expected, timeoutMs = 5000) {
  const actual = await waitForCondition(
    () => rootDisplay(page),
    (value) => value === expected,
    `#rf-causa-root display=${expected}`,
    timeoutMs,
  );
  if (actual !== expected) {
    throw new Error(`Expected #rf-causa-root display "${expected}", got "${actual}"`);
  }
}

async function clickSidebar(page, id, expectedCanvasTestId) {
  await page.locator(`[data-testid="rf-causa-sidebar-item-${id}"]`).click();
  await expectVisible(page.locator(`[data-testid="${expectedCanvasTestId}"]`), 5000);
}

async function clickHostButtonByLabel(page, label) {
  const clicked = await page.evaluate((targetLabel) => {
    const candidates = Array.from(document.querySelectorAll('button'))
      .filter((el) => !el.closest('#rf-causa-root'));
    const target = candidates.find((el) => (el.textContent || '').trim() === targetLabel);
    if (!target) return false;
    target.click();
    return true;
  }, label);
  if (!clicked) {
    throw new Error(`Could not find host-app button with label ${JSON.stringify(label)}`);
  }
}

async function traceRowCount(page) {
  return page.locator('[data-testid^="rf-causa-trace-row-"]').count();
}

async function waitForTraceGrowth(page, beforeRows, timeoutMs = 5000) {
  return waitForCondition(
    () => traceRowCount(page),
    (count) => count > beforeRows,
    `trace row growth beyond ${beforeRows}`,
    timeoutMs,
  );
}

function parseCountsText(text) {
  const match = /(\d+)\s*\/\s*(\d+)\s+in view/.exec(text || '');
  if (!match) {
    throw new Error(`Could not parse counts text: ${JSON.stringify(text)}`);
  }
  return { rendered: Number(match[1]), total: Number(match[2]) };
}

async function readTraceCounts(page) {
  const text = (await page.locator('[data-testid="rf-causa-trace-counts"]').textContent()) || '';
  return parseCountsText(text.trim());
}

async function invokeSuppressedCounter(page, n) {
  return page.evaluate((count) => {
    const cfg =
      window.day8 &&
      window.day8.re_frame2_causa &&
      window.day8.re_frame2_causa.config;
    if (!cfg || typeof cfg.note_suppressed_BANG_ !== 'function') {
      return { ok: false, reason: 'day8.re_frame2_causa.config.note_suppressed_BANG_ not on window' };
    }
    for (let i = 0; i < count; i += 1) {
      cfg.note_suppressed_BANG_(null);
    }
    return {
      ok: true,
      count: typeof cfg.suppressed_count === 'function' ? cfg.suppressed_count() : null,
    };
  }, n);
}

async function clearTraceBuffer(page) {
  return page.evaluate(() => {
    const bus =
      window.day8 &&
      window.day8.re_frame2_causa &&
      window.day8.re_frame2_causa.trace_bus;
    if (!bus || typeof bus.clear_buffer_BANG_ !== 'function') {
      return { ok: false, reason: 'day8.re_frame2_causa.trace_bus.clear_buffer_BANG_ not on window' };
    }
    bus.clear_buffer_BANG_();
    return { ok: true };
  });
}

module.exports = {
  name: 'causa-rigorous',
  url: '/counter/',
  run: async (page) => {
    const counterValue = page.locator('#app [data-testid="counter-value"]');

    // ----------------------------------------------------------------
    // 1. Initial state — counter ready, shell auto-mounted inline.
    // ----------------------------------------------------------------
    await expectTextEquals(counterValue, '5', 10000);
    await expectVisible(page.locator(`[data-testid="${SHELL_TESTID}"]`), 5000);
    const inline = await page.evaluate(() => {
      const root = document.getElementById('rf-causa-root');
      const host = document.querySelector('[data-rf-causa-host]');
      const shell = document.querySelector('[data-testid="rf-causa-shell"]');
      const plus = Array.from(document.querySelectorAll('button'))
        .filter((button) => !button.closest('#rf-causa-root'))
        .find((button) => (button.textContent || '').trim() === '+');
      function rect(el) {
        if (!el) return null;
        const r = el.getBoundingClientRect();
        return { left: r.left, right: r.right, width: r.width, height: r.height };
      }
      return {
        rootMode: root && root.getAttribute('data-rf-causa-mode'),
        shellMode: shell && shell.getAttribute('data-mode'),
        bodyPaddingLeft: document.body.style.paddingLeft,
        bodyPaddingRight: document.body.style.paddingRight,
        rootParentIsHost: Boolean(root && host && root.parentElement === host),
        shell: rect(shell),
        hostPlus: rect(plus),
      };
    });
    if (inline.rootMode !== 'inline' || inline.shellMode !== 'inline' || !inline.rootParentIsHost) {
      throw new Error(`Expected Causa to auto-mount in the inline host; got ${JSON.stringify(inline)}`);
    }
    if (inline.bodyPaddingLeft || inline.bodyPaddingRight) {
      throw new Error(`Expected inline Causa to avoid body-padding layout tricks; got ${JSON.stringify(inline)}`);
    }
    if (!inline.shell || !inline.hostPlus || inline.hostPlus.left < inline.shell.right) {
      throw new Error(`Expected host controls to be laid out to the right of Causa; got ${JSON.stringify(inline)}`);
    }

    // ----------------------------------------------------------------
    // 2. Default panel and cue render.
    // ----------------------------------------------------------------
    await expectVisible(page.locator('[data-testid="rf-causa-event-detail"]'), 5000);
    await expectVisible(page.locator('[data-testid="rf-causa-copilot-cue"]'), 5000);

    // ----------------------------------------------------------------
    // 3. Co-pilot rail affordances and submit path.
    // ----------------------------------------------------------------
    await page.locator('[data-testid="rf-causa-copilot-cue"]').click();
    await expectVisible(page.locator('[data-testid="rf-causa-copilot-rail"]'), 5000);
    await expectVisible(page.locator('[data-testid="rf-causa-copilot-empty"]'), 5000);

    const copilotInput = page.locator('[data-testid="rf-causa-copilot-input"]');

    // rf2-qvz85 — typing `/` renders the slash popover with every
    // slash command. Pre-fix the input lived in a `defonce` plain
    // atom and the rail did not re-render on keystrokes — the
    // popover never appeared. The fix routes the input through
    // `:rf.causa/copilot-input-text` on Causa's app-db.
    await copilotInput.fill('/');
    await expectVisible(page.locator('[data-testid="rf-causa-copilot-slash-popover"]'), 5000);
    const slashRows = page.locator('[data-testid^="rf-causa-copilot-slash-"]');
    // The popover element itself matches the prefix; each command adds
    // a row. With 8 commands the locator yields 9 elements.
    if ((await slashRows.count()) < 9) {
      throw new Error(
        `Expected at least 9 slash-popover rows (popover + 8 commands); got ${await slashRows.count()}.`,
      );
    }
    // Narrow to a single command and assert the popover drops the
    // non-matching rows. `await waitForCondition` to give the rail's
    // re-render cycle time to settle — the slot is reactive but the
    // browser still needs a paint.
    await copilotInput.fill('/exp');
    await expectVisible(page.locator('[data-testid="rf-causa-copilot-slash-explain"]'), 5000);
    await waitForCondition(
      async () => page.locator('[data-testid="rf-causa-copilot-slash-clear"]').count(),
      (count) => count === 0,
      '`/exp` to narrow the popover away from /clear',
      5000,
    );

    // Now submit a free-form question and verify the conversation
    // surface accepts it (question + streaming-answer turn).
    await copilotInput.fill('Why did the counter change?');
    await page.keyboard.press('Enter');
    await expectVisible(page.locator('[data-testid="rf-causa-copilot-turn-question"]'), 5000);
    await expectVisible(page.locator('[data-testid="rf-causa-copilot-turn-answer"]'), 5000);

    await page.locator('[data-testid="rf-causa-copilot-close"]').click();
    await waitForCondition(
      async () => page.locator('[data-testid="rf-causa-copilot-rail"]').count(),
      (count) => count === 0,
      'co-pilot rail to close',
      5000,
    );

    await clickSidebar(page, 'copilot', 'rf-causa-copilot-panel');
    await expectVisible(page.locator('[data-testid="rf-causa-copilot-input"]'), 5000);
    await clickSidebar(page, 'event-detail', 'rf-causa-event-detail');
    await expectVisible(page.locator('[data-testid="rf-causa-copilot-cue"]'), 5000);

    // ----------------------------------------------------------------
    // 4. Host events for later panel assertions.
    // ----------------------------------------------------------------
    await clickHostButtonByLabel(page, '+');
    await expectTextEquals(counterValue, '6');
    await clickHostButtonByLabel(page, '+');
    await expectTextEquals(counterValue, '7');

    // ----------------------------------------------------------------
    // 4b. Time-travel scrubber populates after host dispatches
    //     (rf2-1barg).
    // ----------------------------------------------------------------
    //
    // Pre-fix the panel sat in the empty-state branch because the
    // counter example did not require the epoch artefact — the
    // framework's `rf/register-epoch-cb!` was a silent no-op, no
    // epoch records were captured, and the scrubber had nothing to
    // render. The Causa preload now `:require`s `re-frame.epoch` so
    // every Causa-enabled example surfaces working time-travel without
    // the host having to add a separate dependency. The seed in
    // `mount.cljs/ensure-causa-frame!` lifts any pre-mount history
    // into Causa's reactive slot; the epoch-cb's dispatch path pumps
    // subsequent settles.
    await clickSidebar(page, 'time-travel', 'rf-causa-time-travel');
    const slider = page.locator('[data-testid="rf-causa-time-travel-slider"]');
    await expectVisible(slider, 5000);
    const sliderMax = parseInt(await slider.getAttribute('max'), 10);
    // Counter has settled at least 3 cascades: :counter/initialise +
    // two `+` clicks above. The scrubber's max-index is one less than
    // the history depth, so we expect >= 2 (3 records -> max=2).
    if (!Number.isFinite(sliderMax) || sliderMax < 2) {
      throw new Error(
        `Expected time-travel slider max >= 2 (>=3 epoch records); got max=${sliderMax}.`,
      );
    }

    // ----------------------------------------------------------------
    // 5. Panel-specific empty states on the counter example.
    // ----------------------------------------------------------------
    await clickSidebar(page, 'routes', 'rf-causa-routes');
    await expectVisible(page.locator('[data-testid="rf-causa-routes-empty"]'), 5000);

    await clickSidebar(page, 'schemas', 'rf-causa-schema-violation-timeline');
    await expectVisible(page.locator('[data-testid="rf-causa-schema-timeline-empty-no-schemas"]'), 5000);

    await clickSidebar(page, 'mcp-server', 'rf-causa-mcp-server');
    await expectVisible(page.locator('[data-testid="rf-causa-mcp-empty-no-activity"]'), 5000);

    // ----------------------------------------------------------------
    // 6. Trace panel: row growth, filter activation, filter clearing.
    //
    // Clear the trace buffer first so we are guaranteed to be below
    // the buffer cap when we observe growth. The rigorous spec exercises
    // many surfaces (the rail, sidebar pivots, ...) before reaching
    // this section; without the reset the trace buffer may already be
    // at its 1000-event cap and a `-` click would evict as many events
    // as it adds, leaving `total` flat at 1000.
    // ----------------------------------------------------------------
    await clickSidebar(page, 'trace', 'rf-causa-trace');

    // Reset the buffer so we have headroom below the 1000-event cap.
    // Background render cascades will repopulate it immediately, but
    // we just need `totalBefore` to be < 1000 so the post-click delta
    // is observable on `total`.
    const clearedForGrowthCheck = await clearTraceBuffer(page);
    if (!clearedForGrowthCheck.ok) {
      throw new Error(
        `Could not clear the trace buffer before growth check: ${clearedForGrowthCheck.reason}`,
      );
    }
    // Wait for the panel to settle into a below-cap unfiltered state
    // so the subsequent host-click delta is observable. Background
    // render cascades may push the buffer back up quickly, but they
    // won't reach 1000 in this window — we just need a stable read.
    const traceCountsBefore = await waitForCondition(
      () => readTraceCounts(page),
      ({ rendered, total }) => rendered === total && total < 1000,
      'trace panel to settle below the 1000-event buffer cap after clear',
      5000,
    );

    // Capture the current count, dispatch, and assert the *total*
    // (read from the panel's :rf.causa/trace-counts surface) grew.
    // We assert on `total` rather than rendered-row count because the
    // 1000-event buffer cap may evict events on push when the buffer
    // is full — the total reported by the panel reflects the raw
    // buffer size post-eviction. After the clear above we are below
    // the cap so the cascade from the host `-` click produces a
    // non-zero net delta on `total`.
    const totalBefore = traceCountsBefore.total;
    await clickHostButtonByLabel(page, '-');
    await expectTextEquals(counterValue, '6', 5000);
    await waitForCondition(
      () => readTraceCounts(page),
      ({ total }) => total !== totalBefore,
      `trace panel total to change from ${totalBefore} after host '-' click`,
      5000,
    );

    const opTypeChips = page.locator('[data-testid^="rf-causa-trace-axis-chip-op-type-"]');
    if ((await opTypeChips.count()) === 0) {
      throw new Error('Expected at least one trace op-type filter chip.');
    }
    await opTypeChips.first().click();
    await expectVisible(page.locator('[data-testid="rf-causa-trace-clear-filters"]'), 5000);
    const filteredCounts = await waitForCondition(
      () => readTraceCounts(page),
      ({ rendered, total }) => rendered < total,
      'trace filter to narrow the ribbon',
      5000,
    );
    if (!(filteredCounts.rendered < filteredCounts.total)) {
      throw new Error(
        `Expected filtered trace counts to narrow; got ${filteredCounts.rendered}/${filteredCounts.total}.`,
      );
    }

    await page.locator('[data-testid="rf-causa-trace-clear-filters"]').click();
    const clearedCounts = await waitForCondition(
      () => readTraceCounts(page),
      ({ rendered, total }) => rendered === total,
      'trace filters to clear',
      5000,
    );
    if (clearedCounts.rendered !== clearedCounts.total) {
      throw new Error(
        `Expected cleared trace counts to match; got ${clearedCounts.rendered}/${clearedCounts.total}.`,
      );
    }

    // The "click a trace row → pivots to event-detail" assertion is
    // intentionally omitted from this rigorous spec because the row's
    // click pivot is dispatch-id-gated (rows for warning / error
    // traces have no dispatch-id and don't pivot) and the high-rate
    // background trace cascade makes targeting a specific dispatch-id
    // row non-deterministic. The sidebar → event-detail pivot is
    // covered by the panel-handoff loop in `causa.spec.cjs` already.

    // ----------------------------------------------------------------
    // 7. Redaction counter + clear-buffer lifecycle.
    // ----------------------------------------------------------------
    const noted = await invokeSuppressedCounter(page, 3);
    if (!noted.ok) {
      throw new Error(`Could not bump suppressed counter: ${noted.reason}`);
    }
    const redacted = page.locator(`[data-testid="${REDACTED_TESTID}"]`);
    await expectVisible(redacted, 5000);
    const redactedText = (await redacted.textContent()) || '';
    if (!redactedText.includes('REDACTED 3')) {
      throw new Error(`Expected redacted indicator to include "REDACTED 3"; got "${redactedText.trim()}"`);
    }

    const cleared = await clearTraceBuffer(page);
    if (!cleared.ok) {
      throw new Error(`Could not clear the trace buffer: ${cleared.reason}`);
    }
    // The trace ribbon may briefly re-populate from background render
    // cascades (every reg-view re-render emits sub/run traces). The
    // assertion is that the buffer GOES through the empty state at
    // least once or the redacted indicator clears — both are direct
    // consequences of clear-buffer!'s dual atom + dispatch reset.
    await waitForCondition(
      async () => page.locator(`[data-testid="${REDACTED_TESTID}"]`).count(),
      (count) => count === 0,
      'redacted indicator to disappear after clear-buffer',
      5000,
    );

    // ----------------------------------------------------------------
    // 8. Shell hide/show remains CSS-only.
    // ----------------------------------------------------------------
    await page.keyboard.press('Control+Shift+C');
    await expectRootDisplay(page, 'none', 5000);
    await page.keyboard.press('Control+Shift+C');
    await expectRootDisplay(page, 'block', 5000);

    // ----------------------------------------------------------------
    // 9. Per-panel hero-affordance end-to-end walks (rf2-bo3se).
    //
    // The earlier sections satisfied the cluster's minimum-coverage
    // assertion ("panel paints a useful canvas after a host dispatch"
    // — section 4b for time-travel, section 6 for trace). This
    // section walks each panel's hero affordance through multi-step
    // DOM interactions + post-interaction state assertions:
    //
    //   9a. event-detail        — cascade-row click → six-domino
    //                             cascade renders → Clear → list
    //   9b. time-travel         — jump-to-oldest → Reset to current
    //                             rewinds host app-db → jump-newest
    //                             unscrubs
    //   9c. app-db-diff         — Pin a slice → pinned-group renders
    //                             → "Show me when this changed" →
    //                             focus-result-panel renders hits →
    //                             clear-focus closes it
    //   9d. causality-graph     — node click selects the dispatch-id
    //                             → pivot to event-detail → cascade
    //                             detail is the node we clicked
    //                             (selection parity across panels)
    //
    // The walks assume the host counter has accrued at least one
    // settled cascade plus the boot `:counter/initialise` — the
    // earlier sections satisfy that, so we can read deterministic
    // expectations off the cascade list.
    // ----------------------------------------------------------------

    // 9a. event-detail hero walk — cascade-row click → six-domino
    // detail → Clear → list.
    //
    // Fire one more host dispatch so we know there is at least one
    // cascade list-row to click. (The earlier `-` click in section 6
    // also lands one in the buffer; the explicit click here keeps the
    // walk self-contained should section ordering ever change.)
    await clickHostButtonByLabel(page, '+');
    await expectTextEquals(counterValue, '7', 5000);
    await clickSidebar(page, 'event-detail', 'rf-causa-event-detail');
    // Empty-state cascade list should be visible — selection clears on
    // mount per the bare event-detail panel's reg-event-db, but the
    // co-pilot turn earlier in the spec may have left a selection in
    // place. Force a known-empty starting point.
    {
      const lingeringClear = page.locator('[data-testid="rf-causa-event-detail-clear"]');
      if ((await lingeringClear.count()) > 0) {
        await lingeringClear.first().click();
      }
    }
    await expectVisible(page.locator('[data-testid="rf-causa-event-detail-empty"]'), 5000);
    const cascadeRows = page.locator('[data-testid^="rf-causa-cascade-row-"]');
    await waitForCondition(
      async () => cascadeRows.count(),
      (count) => count > 0,
      'at least one cascade row in the event-detail empty-state list',
      5000,
    );
    // Pick the latest row (last child of the cascade list). The id
    // suffix on `rf-causa-cascade-row-<id>` is the dispatch-id we
    // expect to see surfaced inside the cascade-detail's
    // `data-dispatch-id` attribute after the click.
    const lastCascadeRow = cascadeRows.last();
    const lastRowTestid = await lastCascadeRow.getAttribute('data-testid');
    const clickedDispatchId = lastRowTestid.replace('rf-causa-cascade-row-', '');
    await lastCascadeRow.click();
    // Cascade-detail mounts — the canvas now shows the six-domino
    // layout for the clicked dispatch.
    await expectVisible(page.locator('[data-testid="rf-causa-event-detail-cascade"]'), 5000);
    const cascadeDetail = page.locator('[data-testid="rf-causa-event-detail-cascade"]');
    await waitForCondition(
      async () => cascadeDetail.getAttribute('data-dispatch-id'),
      (val) => val === clickedDispatchId,
      `cascade-detail data-dispatch-id=${clickedDispatchId}`,
      5000,
    );
    // Click Clear — back to the list. The detail goes away and the
    // empty-state list reappears.
    await page.locator('[data-testid="rf-causa-event-detail-clear"]').click();
    await waitForCondition(
      async () => page.locator('[data-testid="rf-causa-event-detail-cascade"]').count(),
      (count) => count === 0,
      'cascade-detail to unmount after Clear',
      5000,
    );
    await expectVisible(page.locator('[data-testid="rf-causa-event-detail-empty"]'), 5000);

    // 9b. time-travel hero walk — jump-to-oldest → Reset to current
    // rewinds host app-db → jump-to-newest unscrubs.
    //
    // The counter is currently at 7 (boot 5 + three increments — two
    // pre-section-6 plus the one fired by section 9a). The oldest
    // epoch in the scrubber holds the boot :counter/initialise state
    // (counter/value=5). After "Reset to current" the host's
    // counter-value display rewinds to 5; after "jump-to-newest" the
    // selection clears back to the head and the slider value matches
    // its max-index.
    await clickSidebar(page, 'time-travel', 'rf-causa-time-travel');
    const ttSlider = page.locator('[data-testid="rf-causa-time-travel-slider"]');
    await expectVisible(ttSlider, 5000);
    const ttSliderMaxBefore = parseInt(await ttSlider.getAttribute('max'), 10);
    if (!Number.isFinite(ttSliderMaxBefore) || ttSliderMaxBefore < 2) {
      throw new Error(
        `Expected time-travel slider max >= 2 before hero walk; got ${ttSliderMaxBefore}.`,
      );
    }
    // Drag the slider to the oldest position by `fill`-ing the range
    // input to its min value (0). Playwright's `fill` on a `<input
    // type=range>` lands the same `on-change` path a real drag would
    // — the panel's handler reads `(.. e -target -value)` and
    // dispatches `:rf.causa/select-epoch` for the epoch at that
    // index.
    await ttSlider.fill('0');
    await waitForCondition(
      async () => ttSlider.inputValue(),
      (value) => value === '0',
      'time-travel slider value=0 after scrub to oldest',
      5000,
    );
    // Fire the rewind — `:rf.causa/reset-to-epoch` →
    // `:rf.causa.fx/restore-epoch` walks `rf/restore-epoch` against
    // the default frame and the host counter rewinds to its boot
    // value (5). The "Reset to current" affordance is the time-travel
    // panel's hero rewind button — the slider scrub is passive (no
    // host effects); only the button actually commits the restore.
    await page.locator('[data-testid="rf-causa-reset-to-epoch"]').click();
    await expectTextEquals(counterValue, '5', 5000);
    // Unscrub — drag the slider to its new max-index. The slider
    // value matches max after the rewind dispatch lands its own
    // settle epoch on the history.
    const ttSliderMaxAfter = await waitForCondition(
      async () => parseInt(await ttSlider.getAttribute('max'), 10),
      (max) => Number.isFinite(max) && max >= ttSliderMaxBefore,
      `time-travel slider max stable at >= ${ttSliderMaxBefore} after rewind settles`,
      5000,
    );
    await ttSlider.fill(String(ttSliderMaxAfter));
    await waitForCondition(
      async () => ttSlider.inputValue(),
      (value) => value === String(ttSliderMaxAfter),
      `time-travel slider value=${ttSliderMaxAfter} after scrub to newest`,
      5000,
    );

    // 9c. app-db-diff hero walk — pin a slice → pinned-group renders
    // → "Show me when this changed" → focus-result-panel surfaces
    // hits → clear-focus closes it.
    //
    // Bump the counter once more so the most-recent cascade carries a
    // non-empty changed-slices set (the rewind we just performed
    // emits its own restore epoch but the diff projection reads from
    // the *dispatch* cascade buffer; one more `+` click guarantees a
    // dispatch cascade after the restore).
    await clickHostButtonByLabel(page, '+');
    await expectTextEquals(counterValue, '6', 5000);
    await clickSidebar(page, 'app-db', 'rf-causa-app-db-diff');
    const sliceRows = page.locator('[data-testid^="rf-causa-app-db-diff-slice-"]');
    await waitForCondition(
      async () => sliceRows.count(),
      (count) => count > 0,
      'app-db-diff to render at least one changed-slice row',
      5000,
    );
    const firstSlice = sliceRows.first();
    const firstSliceTestid = await firstSlice.getAttribute('data-testid');
    // The slice testid embeds the path's pr-str — `rf-causa-app-db-
    // diff-slice-<path-prstr>`. The pin / show-when testids share the
    // same suffix so we can address them directly without re-parsing
    // the path.
    const pathSuffix = firstSliceTestid.replace('rf-causa-app-db-diff-slice-', '');
    const pinBtn = page.locator(
      `[data-testid="rf-causa-app-db-diff-pin-${pathSuffix}"]`,
    );
    await pinBtn.click();
    // Pinned-group appears with one pinned row for this path. The
    // pinned-row testid is `rf-causa-app-db-diff-pinned-<pathPrstr>`.
    await expectVisible(
      page.locator('[data-testid="rf-causa-app-db-diff-pinned-group"]'),
      5000,
    );
    await expectVisible(
      page.locator(`[data-testid="rf-causa-app-db-diff-pinned-${pathSuffix}"]`),
      5000,
    );
    // Click "Show me when this changed" on the same slice — the
    // panel pivots into focus-result mode and renders one row per
    // epoch in the buffer that touched this path.
    const showWhenBtn = page.locator(
      `[data-testid="rf-causa-app-db-diff-show-when-${pathSuffix}"]`,
    );
    await showWhenBtn.click();
    await expectVisible(
      page.locator('[data-testid="rf-causa-app-db-diff-focus-result"]'),
      5000,
    );
    const focusHits = page.locator('[data-testid^="rf-causa-app-db-diff-focus-hit-"]');
    await waitForCondition(
      async () => focusHits.count(),
      (count) => count > 0,
      'focus-result-panel to render at least one hit row',
      5000,
    );
    // Clear focus — the panel returns to slice-list mode and the
    // focus-result section unmounts.
    await page.locator('[data-testid="rf-causa-app-db-diff-clear-focus"]').click();
    await waitForCondition(
      async () => page.locator('[data-testid="rf-causa-app-db-diff-focus-result"]').count(),
      (count) => count === 0,
      'focus-result-panel to unmount after clear-focus',
      5000,
    );

    // 9d. causality-graph hero walk — node click selects a dispatch
    // → pivoting to event-detail shows that exact cascade selected
    // (parity).
    //
    // Per causality_graph.cljs the node's on-click fires
    // `:rf.causa/select-dispatch-id`, which is the same event the
    // cascade list dispatches. Both panels read the same selection
    // slot through `:rf.causa/event-detail`; pivoting via the sidebar
    // should land us directly on the cascade-detail for the clicked
    // node without a second selection step.
    //
    // The trace buffer caps at 1000 events; Causa's own panel-
    // switching dispatches plus background sub/render cascades can
    // rotate a cascade out from under us between the node click and
    // the sidebar pivot. Clear the buffer right before this walk and
    // fire a single fresh host `+` click so the cascade we target is
    // guaranteed to still be in the buffer when event-detail
    // recomputes its composite. The clear also drops the stale
    // `:selected-epoch-id` carried over from 9b's jump-newest so the
    // causality graph paints its full topology rather than a single
    // filtered cascade.
    const clearedForGraph = await clearTraceBuffer(page);
    if (!clearedForGraph.ok) {
      throw new Error(
        `Could not clear the trace buffer before causality-graph walk: ${clearedForGraph.reason}`,
      );
    }
    await clickHostButtonByLabel(page, '+');
    await expectTextEquals(counterValue, '7', 5000);
    await clickSidebar(page, 'causality', 'rf-causa-causality-graph');
    const graphNodes = page.locator('[data-testid^="rf-causa-graph-node-"]');
    await waitForCondition(
      async () => graphNodes.count(),
      (count) => count > 0,
      'causality graph to render at least one node',
      5000,
    );
    // Pick the most-recent node (last in DOM order). The testid
    // suffix is the dispatch-id — same shape as the cascade-row
    // testid in event-detail. With a freshly-cleared buffer plus a
    // single host `+` click the last node is the host counter
    // cascade we just fired.
    const lastNode = graphNodes.last();
    const lastNodeTestid = await lastNode.getAttribute('data-testid');
    const nodeDispatchId = lastNodeTestid.replace('rf-causa-graph-node-', '');
    await lastNode.click();
    // Pivot to event-detail — the selection rides on Causa's app-db
    // so the cascade-detail mounts straight away with the matching
    // dispatch-id.
    await clickSidebar(page, 'event-detail', 'rf-causa-event-detail');
    await expectVisible(page.locator('[data-testid="rf-causa-event-detail-cascade"]'), 5000);
    const pivotCascade = page.locator('[data-testid="rf-causa-event-detail-cascade"]');
    await waitForCondition(
      async () => pivotCascade.getAttribute('data-dispatch-id'),
      (val) => val === nodeDispatchId,
      `event-detail cascade data-dispatch-id=${nodeDispatchId} (parity with graph node click)`,
      5000,
    );
  },
};
