/*
 * Counter-with-stories example — Playwright spec.
 *
 * Two surfaces in one bundle, hash-routed:
 *
 *   - #/        — the live counter app (counter-card view). The
 *                 same assertions as examples/reagent/counter, scoped
 *                 to a different starting value (5) and the parity
 *                 badge.
 *   - #/stories — the Story shell. The original Stage-8 smoke verified
 *                 the shell mounts and a single variant renders. This
 *                 spec also exercises every interactive surface of the
 *                 playground — sidebar navigation, mode-tag filtering,
 *                 workspace cell mounting, time-travel scrubber, trace
 *                 panel, a11y panel, layout-debug toggles, args editor,
 *                 mode picker, decorator list, save-layout button —
 *                 one assertion per surface so each one is exercised
 *                 by the smoke (rf2-kljn).
 *
 * The deep CLJS assertions on the Story registry (every reg-* macro
 * landed, play sequences pass, mount/unmount round-trip) live in
 * counter_with_stories.stories-cljs-test under the top-level
 * `npm run test:cljs` build — this spec is the browser-side
 * end-to-end on a real Chromium.
 */

const {
  expectTextEquals,
  expectVisible,
} = require('../../scripts/spec-helpers.cjs');

/* ------------------------------------------------------------------
 * Helpers — Story-shell selectors are stable across versions because
 * the shell renders inline-styled <div>s, not CSS-class-named nodes,
 * so we anchor on stable text content (variant ids, panel titles, the
 * canvas's `:variant.x/y → :view-id` header) and `data-test` attributes
 * the counter views emit.
 * ------------------------------------------------------------------ */

/** Click a sidebar variant row by its leading-slash label (e.g. "/loaded"). */
async function clickVariant(page, slashName) {
  // The sidebar emits one `text=/<name>` row per variant; the canvas
  // emits the same name inside the variant-id-as-keyword title
  // ("/clicked-three-times" appears inside ":story.counter/clicked-three-times"),
  // so we scope to the <nav> landmark.
  const row = page.getByRole('navigation').getByText(slashName, { exact: false }).first();
  await row.waitFor({ state: 'visible', timeout: 10000 });
  await row.click();
}

/** Click a sidebar workspace row by its keyword id (e.g. ":Workspace.counter/auto-grid"). */
async function clickWorkspace(page, wsKeyword) {
  const row = page.getByRole('navigation').getByText(wsKeyword, { exact: false }).first();
  await row.waitFor({ state: 'visible', timeout: 10000 });
  await row.click();
}

/** Wait for the canvas to title itself with a particular variant id. */
async function waitForVariantTitle(page, variantKeyword) {
  await page
    .getByText(variantKeyword, { exact: false })
    .first()
    .waitFor({ state: 'visible', timeout: 10000 });
}

/** Wait until the page contains at least one counter `[data-test="count"]` cell. */
async function waitForCounterCell(page) {
  await page.locator('[data-test="count"]').first().waitFor({
    state: 'visible',
    timeout: 10000,
  });
}

module.exports = {
  name: 'counter-with-stories',
  url: '/counter-with-stories/',
  run: async (page) => {
    // The Story shell's first-visit help overlay (rf2-381i) auto-opens
    // on first visit to #/stories. It carries `role="dialog"
    // aria-modal="true"` and intercepts pointer events from the
    // underlying shell — every sidebar / panel / canvas click waits
    // indefinitely for the dialog to be dismissed. Tests run in a
    // fresh Playwright context (no persistent localStorage), so the
    // overlay is ALWAYS present on the first stories-shell mount in a
    // test run unless the spec dismisses it.
    //
    // Persist the "seen" flag now so any subsequent navigation to
    // #/stories during this run sees the dismissed state at mount
    // time. The current page is the live counter app under #/, so the
    // overlay isn't yet rendered — the dismiss-on-mount path takes
    // over once we navigate to #/stories at step 2.
    await page.evaluate(() => {
      try {
        localStorage.setItem('re-frame.story/seen-help-v1', '1');
      } catch (_) {
        /* ignore — private mode, etc. */
      }
    });

    // ====================================================================
    // 1. Live app at #/ — the original Stage-8 smoke
    // ====================================================================
    //
    // The counter renders with :count 5 (seeded by the run fn's
    // dispatch-sync of [:counter/initialise 5]). The parity badge
    // reports :odd for 5.
    const count = page.locator('[data-test="count"]').first();
    await expectTextEquals(count, '5', 10000);

    const parity = page.locator('[data-test="parity"]').first();
    await expectTextEquals(parity, 'odd');

    // +1 brings the count to 6 and the parity to :even.
    await page.locator('[data-test="inc"]').first().click();
    await expectTextEquals(count, '6');
    await expectTextEquals(parity, 'even');

    // ====================================================================
    // 2. Switch to the Story shell at #/stories
    // ====================================================================
    //
    // Navigate to #/stories. The Story shell mounts a fresh React
    // root over the #app node. We verify by waiting for the placeholder
    // text the shell renders when no variant is selected.
    await page.evaluate(() => {
      window.location.hash = '#/stories';
    });

    await page
      .getByText(/Select a variant or workspace from the sidebar/i, { exact: false })
      .first()
      .waitFor({ state: 'visible', timeout: 10000 });

    // Sanity: the three landmarks the shell ships are reachable by role.
    // (rf2-xc65: <nav> sidebar, <main> canvas, <aside> inspectors.) These
    // also anchor the helpers above.
    await expectVisible(page.getByRole('navigation'), 5000);
    await expectVisible(page.getByRole('main'), 5000);
    await expectVisible(page.getByRole('complementary'), 5000);

    // ====================================================================
    // 3. Sidebar navigation — variants (rf2-zme7 + rf2-kljn)
    // ====================================================================
    //
    // Click each variant in turn and verify the canvas re-renders with
    // that variant's title. Regression for rf2-zme7 lives here: clicking
    // a variant directly (without first selecting a workspace) used to
    // blank the page because the canvas's `decorated-view` propagated
    // any `:wrap` exception up the Reagent tree.
    await clickVariant(page, '/clicked-three-times');
    await waitForVariantTitle(page, ':story.counter/clicked-three-times');

    // The variant's log-decorator wraps the body and surfaces both
    // story-level + variant-level label strings (rf2-zme7).
    await expectVisible(page.getByText(/decorator: story-level/i).first(), 5000);
    await expectVisible(page.getByText(/decorator: variant-level/i).first(), 5000);

    // Switching to a second variant keeps the shell mounted.
    await clickVariant(page, '/loaded');
    await waitForVariantTitle(page, ':story.counter/loaded');

    // ====================================================================
    // 3b. Mode-tabs strip — Canvas | Docs | Tests (rf2-9hc8)
    // ====================================================================
    //
    // The render shell ships a per-variant mode-tabs strip at the top
    // of the canvas pane (rf2-9hc8). Three chips: Canvas (:dev, default)
    // | Docs (:docs, rf2-rodx placeholder) | Tests (:test, rf2-qmjo
    // placeholder). Selection is per-variant and persists across reload
    // in localStorage under `re-frame.story/active-mode-tab/<variant-id>`.
    //
    // The strip renders inside <main>, carries data-test="story-mode-tabs"
    // and each chip carries data-mode-tab="<id>". Active chips set
    // aria-selected="true".
    const tabsStrip = page.locator('[data-test="story-mode-tabs"]');
    await tabsStrip.waitFor({ state: 'visible', timeout: 5000 });

    const devChip = tabsStrip.locator('[data-mode-tab="dev"]');
    const docsChip = tabsStrip.locator('[data-mode-tab="docs"]');
    const testChip = tabsStrip.locator('[data-mode-tab="test"]');

    // All three chips must render.
    await devChip.waitFor({ state: 'visible', timeout: 2000 });
    await docsChip.waitFor({ state: 'visible', timeout: 2000 });
    await testChip.waitFor({ state: 'visible', timeout: 2000 });

    // Default mode-tab is :dev — the canvas is up. The :dev chip is
    // active, the other two are not.
    if ((await devChip.getAttribute('aria-selected')) !== 'true') {
      throw new Error('expected :dev chip to be aria-selected="true" by default');
    }
    if ((await docsChip.getAttribute('aria-selected')) !== 'false') {
      throw new Error('expected :docs chip to be aria-selected="false" by default');
    }

    // Click Docs → the docs placeholder appears and the :docs chip
    // becomes active. The canvas (data-test="count") disappears from
    // <main> because the docs pane replaces it.
    await docsChip.click();
    await page
      .locator('[data-test="story-docs-placeholder"]')
      .waitFor({ state: 'visible', timeout: 5000 });
    if ((await docsChip.getAttribute('aria-selected')) !== 'true') {
      throw new Error('expected :docs chip to be aria-selected="true" after click');
    }
    if ((await devChip.getAttribute('aria-selected')) !== 'false') {
      throw new Error('expected :dev chip to flip aria-selected="false" after switching');
    }

    // Click Tests → the tests placeholder appears, :test chip active.
    await testChip.click();
    await page
      .locator('[data-test="story-tests-placeholder"]')
      .waitFor({ state: 'visible', timeout: 5000 });
    if ((await testChip.getAttribute('aria-selected')) !== 'true') {
      throw new Error('expected :test chip to be aria-selected="true" after click');
    }

    // Verify the persisted localStorage record. The mode-tabs primitive
    // writes under `re-frame.story/active-mode-tab/:story.counter/loaded`.
    const persistedTab = await page.evaluate(() =>
      localStorage.getItem('re-frame.story/active-mode-tab/:story.counter/loaded'),
    );
    if (persistedTab !== 'test') {
      throw new Error(
        `expected localStorage to persist :test after click, got "${persistedTab}"`,
      );
    }

    // Reload — the persisted selection must re-hydrate. Re-prime the
    // help-overlay dismiss flag first so the reloaded shell doesn't
    // block on the on-boarding overlay (same rationale as the top-of-
    // run priming).
    await page.evaluate(() => {
      localStorage.setItem('re-frame.story/seen-help-v1', '1');
    });
    await page.reload();
    await page.evaluate(() => {
      window.location.hash = '#/stories';
    });
    // Re-select /loaded — selection is in-memory only, doesn't persist.
    await clickVariant(page, '/loaded');
    await waitForVariantTitle(page, ':story.counter/loaded');

    // After reload + re-select, the :test chip should be re-hydrated as
    // active and the tests placeholder visible. Poll: hydration from
    // localStorage runs on the first render of the strip for this
    // variant, which is the tick after `clickVariant` settles.
    {
      const start = Date.now();
      let restored = false;
      while (Date.now() - start < 5000) {
        const sel = await page
          .locator('[data-test="story-mode-tabs"] [data-mode-tab="test"]')
          .getAttribute('aria-selected')
          .catch(() => null);
        if (sel === 'true') {
          restored = true;
          break;
        }
        await new Promise((r) => setTimeout(r, 50));
      }
      if (!restored) {
        throw new Error(
          'mode-tabs primitive did not re-hydrate :test from localStorage after reload',
        );
      }
    }
    await page
      .locator('[data-test="story-tests-placeholder"]')
      .waitFor({ state: 'visible', timeout: 5000 });

    // Switch back to Canvas so the remaining sub-tests see the live
    // variant render (the args editor / trace / scrubber / a11y panel
    // / layout-debug assertions all need the canvas mounted).
    await page
      .locator('[data-test="story-mode-tabs"] [data-mode-tab="dev"]')
      .click();
    await waitForVariantTitle(page, ':story.counter/loaded');

    // ====================================================================
    // 4. Mode-tag filter — :dev / :docs / :test chips
    // ====================================================================
    //
    // The sidebar's tag-filter row exposes every registered Story tag
    // as a clickable chip; the seven canonical tags (:dev :docs :test
    // :screenshot :experimental :internal :agent) plus this example's
    // custom :counter-with-stories/canonical are present. Clicking a
    // chip constrains the tree to variants whose :tags set intersects
    // the active set. The :save-stubbed variant carries #{:dev :test};
    // the :loaded variant carries the canonical tag.
    //
    // We exercise the filter by toggling :test on, asserting that a
    // :dev-only variant (none here — every variant tags :dev) and a
    // :screenshot-tagged variant disappear, then toggling back off.
    // The pragmatic surface assertion is that flipping a chip changes
    // which sidebar rows render — and re-flipping restores them.
    const navRoot = page.getByRole('navigation');

    // Capture the row count for ":docs"-tagged variants (every Story
    // variant tags :docs except :save-stubbed). Toggle :docs ON; the
    // :save-stubbed row must disappear.
    const docsChip = navRoot.locator('text=":docs"').first();
    await docsChip.waitFor({ state: 'visible', timeout: 5000 });
    await docsChip.click();

    // After clicking, only :docs-tagged variants remain. The
    // :save-stubbed variant carries #{:dev :test} (no :docs) so its
    // sidebar row should no longer be visible.
    //
    // We tolerate a race: the filter may take a tick to apply, so use
    // a small polling loop.
    {
      const start = Date.now();
      let visible = true;
      while (Date.now() - start < 5000) {
        visible = await navRoot
          .getByText('/save-stubbed', { exact: false })
          .first()
          .isVisible()
          .catch(() => false);
        if (!visible) break;
        await new Promise((r) => setTimeout(r, 50));
      }
      if (visible) {
        throw new Error(
          ':docs tag filter did not hide /save-stubbed (tags-mismatch surface)',
        );
      }
    }

    // Toggle the chip back OFF so subsequent assertions see every row.
    await docsChip.click();
    await navRoot.getByText('/save-stubbed', { exact: false }).first().waitFor({
      state: 'visible',
      timeout: 5000,
    });

    // ====================================================================
    // 5. Workspace mounting — :Workspace.counter/all-states (rf2-zme7)
    // ====================================================================
    //
    // Second half of rf2-zme7: clicking a workspace mounts every
    // variant cell with a working counter inside (each cell scoped to
    // its own per-variant frame). The auto-grid workspace renders four
    // counter-card views — we assert at least four [data-test="count"]
    // spans appear under the workspace pane.
    await clickWorkspace(page, ':Workspace.counter/all-states');
    await waitForVariantTitle(page, ':Workspace.counter/all-states');
    await waitForCounterCell(page);

    // Each of the four variants renders a counter-card → at least four
    // data-test="count" cells. We can't assert exactly four because
    // when the live app's counter-card also bled into the DOM via a
    // prior route the locator picks up extras; >=4 keeps the assertion
    // load-bearing without being brittle.
    //
    // The workspace mounts each cell asynchronously (run-variant
    // resolves through a microtask + a hot-reload tick before the
    // cell's first render). Poll until the cell count settles at >=4.
    {
      const start = Date.now();
      let cellCount = 0;
      while (Date.now() - start < 10000) {
        cellCount = await page.locator('[data-test="count"]').count();
        if (cellCount >= 4) break;
        await new Promise((r) => setTimeout(r, 100));
      }
      if (cellCount < 4) {
        throw new Error(
          `expected >= 4 counter cells in :Workspace.counter/all-states, got ${cellCount}`,
        );
      }
    }

    // ====================================================================
    // 6. Workspace switching — :Workspace.counter/auto-grid
    // ====================================================================
    //
    // Switching workspaces refreshes the cells (different layout id but
    // the same four variants enumerated automatically). We re-assert
    // the cell count and the workspace title.
    await clickWorkspace(page, ':Workspace.counter/auto-grid');
    await waitForVariantTitle(page, ':Workspace.counter/auto-grid');
    await waitForCounterCell(page);

    // ====================================================================
    // 7. Args editor — overriding :label on /loaded
    // ====================================================================
    //
    // Re-select the /loaded variant so the right-pane controls panel
    // shows its args editor. The /loaded variant overrides :label to
    // "Total"; we'll change it from the editor and confirm the canvas
    // re-renders with the new value.
    //
    // The controls panel renders a row per arg-key; each row is a
    // <span> label + a widget. For :string-shaped args (label is
    // inferred :string) the widget is a text input. We grab the input
    // adjacent to the ":label" label.
    await clickVariant(page, '/loaded');
    await waitForVariantTitle(page, ':story.counter/loaded');

    // The args editor sits inside the right-side <aside>. There is one
    // ":label" row exposing an <input type="text"> with the current
    // value "Total" (variant-level override).
    const aside = page.getByRole('complementary');
    const labelInput = aside.locator('input[type="text"]').first();
    await labelInput.waitFor({ state: 'visible', timeout: 5000 });

    // Edit the value and confirm the canvas re-renders with the new
    // label text. The :counter-label view inside counter-card prints
    // the resolved label verbatim.
    await labelInput.fill('Edited');
    await expectVisible(
      page.getByRole('main').getByText('Edited', { exact: false }).first(),
      5000,
    );

    // A "reset overrides" button appears once :cell-overrides has an
    // entry; clicking it restores the variant's declared :args.
    const resetBtn = aside.getByRole('button', { name: /reset overrides/i }).first();
    await resetBtn.click();
    await expectVisible(
      page.getByRole('main').getByText('Total', { exact: false }).first(),
      5000,
    );

    // ====================================================================
    // 8. Mode picker — :Mode.app/dark and :Mode.app/light chips
    // ====================================================================
    //
    // The mode picker exposes every registered mode as a toggleable
    // chip in the right pane. Toggling a mode flips its entry in
    // `:active-modes`; the chip swaps to the active style.
    //
    // We click `:Mode.app/dark`, confirm the chip is now styled active,
    // then click again to deactivate. The DOM doesn't expose the
    // active-state via class (inline-styled), so we instead verify the
    // mode appears as a clickable chip and that clicking it does not
    // crash the shell (the canvas stays mounted with its variant title).
    const darkModeChip = aside.getByText(':Mode.app/dark', { exact: false }).first();
    await darkModeChip.waitFor({ state: 'visible', timeout: 5000 });
    await darkModeChip.click();
    // Canvas should still be mounted and titled with the same variant.
    await waitForVariantTitle(page, ':story.counter/loaded');
    // Toggle back off so subsequent assertions get a clean active-modes set.
    await darkModeChip.click();

    // ====================================================================
    // 9. Decorator list — read-only listing in the controls panel
    // ====================================================================
    //
    // The decorator list section enumerates the variant's resolved
    // decorator stack. The :loaded variant inherits :counter-with-
    // stories/log-decorator from its parent story; the list must
    // surface that id. (The Stage-4 list is read-only — Stage 6 adds
    // the disable-by-name toggle; we just assert the list rendered.)
    await expectVisible(
      aside.getByText(':counter-with-stories/log-decorator', { exact: false }).first(),
      5000,
    );

    // ====================================================================
    // 10. Trace panel — six-domino capture on dispatch
    // ====================================================================
    //
    // The trace panel registers a listener against the variant's frame
    // on selection (see shell.cljs `ensure-listeners-for-variant!`).
    // Clicking the canvas's "+" button dispatches `:counter/inc` —
    // the trace listener buckets it into a cascade and the panel's
    // title bumps its event count above zero.
    const main = page.getByRole('main');
    await main.locator('[data-test="inc"]').first().click();

    // The trace panel's title is "Trace <variant-id> — N events, M cascades".
    // We wait until the count is non-zero.
    await page
      .getByText(/Trace .* — [1-9][0-9]* events/i)
      .first()
      .waitFor({ state: 'visible', timeout: 5000 });

    // ====================================================================
    // 11. Time-travel panel — slider scrub reverts state
    // ====================================================================
    //
    // The scrubber panel reads the variant frame's epoch-history. After
    // a click on "+" there are at least two epochs (initial + inc), so
    // the slider has a non-trivial range. Scrubbing to the first epoch
    // reverts :count visibly inside the canvas.
    //
    // The scrubber's title is "Time travel <variant-id> — N epochs".
    await page
      .getByText(/Time travel .* — [1-9][0-9]* epochs/i)
      .first()
      .waitFor({ state: 'visible', timeout: 5000 });

    // Grab the slider — there's exactly one <input type="range"> in the
    // right pane (the scrubber's), so this selector is unambiguous.
    const slider = aside.locator('input[type="range"]').first();
    await slider.waitFor({ state: 'visible', timeout: 5000 });

    // Record the current displayed count, then drive the slider to its
    // min and confirm the visible count changed. The slider commits on
    // mouseup via :on-mouse-up; Playwright's fill() doesn't fire that,
    // so we set the value via DOM and dispatch the events the panel
    // listens for explicitly.
    const canvasCount = main.locator('[data-test="count"]').first();
    const beforeText = (await canvasCount.textContent()) || '';

    await slider.evaluate((el) => {
      const native = el;
      native.value = String(native.min || 0);
      native.dispatchEvent(new Event('input', { bubbles: true }));
      native.dispatchEvent(new Event('change', { bubbles: true }));
      // The scrubber commits on mouseup, not change — fire it directly.
      native.dispatchEvent(new MouseEvent('mouseup', { bubbles: true }));
    });

    // After scrubbing back, the count should differ from `beforeText`
    // (the variant initialised at 7; after one inc it's 8; scrubbing
    // back to epoch 0 returns it to 7). Poll a brief window so any
    // re-render delay smooths out.
    {
      const start = Date.now();
      let afterText = beforeText;
      while (Date.now() - start < 5000) {
        afterText = (await canvasCount.textContent()) || '';
        if (afterText !== beforeText) break;
        await new Promise((r) => setTimeout(r, 50));
      }
      if (afterText === beforeText) {
        throw new Error(
          `time-travel scrub did not visibly change :count (still "${afterText}")`,
        );
      }
    }

    // ====================================================================
    // 12. A11y panel — clicking "run" produces a status update
    // ====================================================================
    //
    // The a11y panel exposes a "run" button. Clicking it kicks off the
    // axe-core lazy-load + scan. The panel's status line transitions
    // from "click run to scan the rendered output" to "fetching
    // axe-core…" → "scanning…" → "N violation(s) found". We don't
    // assert which violations land (varies by environment); we assert
    // that the run was kicked off — the status text changes away from
    // the idle copy.
    //
    // The panel is registered under :rf.story.panel/a11y with title
    // "a11y" rendered in the panel-head. Its run button has accessible
    // name "run" (transitions to "re-run" / "retry" after a scan).
    const runBtn = aside.getByRole('button', { name: /^(run|re-run|retry)$/i }).first();
    await runBtn.waitFor({ state: 'visible', timeout: 5000 });
    await runBtn.click();

    // The status copy starts as "click run to scan the rendered output"
    // and transitions through "fetching axe-core…" / "scanning…" /
    // "N violation(s) found". We poll for the transition off the idle
    // copy — if the status remains "click run …" the click was
    // ineffective.
    {
      const start = Date.now();
      let stillIdle = true;
      while (Date.now() - start < 15000) {
        stillIdle = await aside
          .getByText(/click run to scan the rendered output/i)
          .first()
          .isVisible()
          .catch(() => false);
        if (!stillIdle) break;
        await new Promise((r) => setTimeout(r, 100));
      }
      if (stillIdle) {
        throw new Error('a11y panel run did not transition off idle status');
      }
    }

    // ====================================================================
    // 13. Layout-debug toggles — flipping a toggle updates :checked
    // ====================================================================
    //
    // The layout-debug panel ships three toggles (measure / outline /
    // pseudo); each is a <label> containing a <input type="checkbox">.
    // Per rf2-4t5u the panel is now form-2 with `:on-change` wired on
    // the input (not `:on-click` on the label), so each user click
    // flips the toggle state exactly once and the rendered :checked
    // attribute reflects the post-click state.
    //
    // The state itself is informational at v1 — the canvas-side
    // render-time merge that turns the preference into an applied
    // decorator is a Stage-6-or-later refinement. The surface
    // assertion here is purely the round-trip: click → :checked flips
    // → click again → :checked flips back.
    const outlineLabel = aside
      .locator('label', { hasText: ':rf.story/layout-debug.outline' })
      .first();
    const outlineToggle = outlineLabel.locator('input[type="checkbox"]');
    await outlineToggle.waitFor({ state: 'visible', timeout: 5000 });

    // All three layout-debug toggles must render (measure / outline /
    // pseudo). The aside hosts exactly three <label>s — one per toggle.
    const labelCount = await aside.locator('label').count();
    if (labelCount !== 3) {
      throw new Error(
        `expected 3 layout-debug toggle <label>s in the aside, got ${labelCount}`,
      );
    }

    // Initially every layout-debug toggle is off.
    if (await outlineToggle.isChecked()) {
      throw new Error('outline toggle started checked (expected unchecked)');
    }

    // Click the label — the contained input toggles via its own
    // :on-change handler. Poll until :checked flips on, in case the
    // r/atom commit batches through a microtask before React re-renders.
    await outlineLabel.click();
    {
      const start = Date.now();
      let checked = false;
      while (Date.now() - start < 5000) {
        checked = await outlineToggle.isChecked();
        if (checked) break;
        await new Promise((r) => setTimeout(r, 50));
      }
      if (!checked) {
        throw new Error(
          'outline toggle did not flip to :checked after clicking its label (rf2-4t5u regression)',
        );
      }
    }

    // Click again — :checked should flip back off.
    await outlineLabel.click();
    {
      const start = Date.now();
      let checked = true;
      while (Date.now() - start < 5000) {
        checked = await outlineToggle.isChecked();
        if (!checked) break;
        await new Promise((r) => setTimeout(r, 50));
      }
      if (checked) {
        throw new Error(
          'outline toggle did not flip back to unchecked after second click',
        );
      }
    }

    // Shell still alive — variant title still visible.
    await waitForVariantTitle(page, ':story.counter/loaded');

    // ====================================================================
    // 14. Save-layout button — clicks without crashing
    // ====================================================================
    //
    // The save-layout button is a Stage-4 stub: clicking emits a
    // console.log only. We confirm the button exists, click it, and
    // afterwards the shell is still mounted (the canvas variant title
    // and the run-button both stay visible).
    const saveBtn = aside.getByRole('button', { name: /save layout as :Workspace/i }).first();
    await saveBtn.waitFor({ state: 'visible', timeout: 5000 });
    await saveBtn.click();
    // Shell still alive — variant title still on the page.
    await waitForVariantTitle(page, ':story.counter/loaded');

    // ====================================================================
    // 15. Project-custom :Panel.counter-with-stories/notes panel
    // ====================================================================
    //
    // Stage 6's panel-host renders every registered :story-panel whose
    // placement matches the slot. The project's :Panel.counter-with-
    // stories/notes registration sets :render to :counter-with-stories.
    // views/parity-badge — so the rendered output is the parity badge
    // (data-test="parity"). One parity badge already renders inside the
    // canvas-card; the panel-host produces a SECOND parity-badge node
    // for the panel slot. We assert there are at least two.
    {
      const start = Date.now();
      let parityCount = 0;
      while (Date.now() - start < 5000) {
        parityCount = await page.locator('[data-test="parity"]').count();
        if (parityCount >= 2) break;
        await new Promise((r) => setTimeout(r, 50));
      }
      if (parityCount < 2) {
        throw new Error(
          `expected the :Panel.counter-with-stories/notes panel to render an extra parity-badge (got ${parityCount} total)`,
        );
      }
    }

    // ====================================================================
    // 16. Switch back to the live app — counter survives
    // ====================================================================
    //
    // Hash-change back to the live app; counter should reappear.
    // The live app re-creates its React root inside `mount-app!` and
    // re-renders against the surviving app-db (which still says
    // :count 6 after the click at step 1).
    await page.evaluate(() => {
      window.location.hash = '#/';
    });

    const countAfter = page.locator('[data-test="count"]').first();
    await expectTextEquals(countAfter, '6', 10000);
  },
};
