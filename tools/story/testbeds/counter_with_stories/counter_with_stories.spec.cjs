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
} = require('../../../../examples/scripts/spec-helpers.cjs');

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

    // Rename the mode-tab chip handles `modeXxxChip` so they don't
    // collide with the sidebar tag-filter chips (a `docsChip` variable
    // re-declared further down in the same arrow function scope is a
    // JS syntax error — keep the mode-tab handles distinct).
    const modeDevChip = tabsStrip.locator('[data-mode-tab="dev"]');
    const modeDocsChip = tabsStrip.locator('[data-mode-tab="docs"]');
    const modeTestChip = tabsStrip.locator('[data-mode-tab="test"]');

    // All three chips must render.
    await modeDevChip.waitFor({ state: 'visible', timeout: 2000 });
    await modeDocsChip.waitFor({ state: 'visible', timeout: 2000 });
    await modeTestChip.waitFor({ state: 'visible', timeout: 2000 });

    // Default mode-tab is :dev — the canvas is up. The :dev chip is
    // active, the other two are not.
    if ((await modeDevChip.getAttribute('aria-selected')) !== 'true') {
      throw new Error('expected :dev chip to be aria-selected="true" by default');
    }
    if ((await modeDocsChip.getAttribute('aria-selected')) !== 'false') {
      throw new Error('expected :docs chip to be aria-selected="false" by default');
    }

    // Click Docs → the docs pane (rf2-rodx — `data-test="story-docs-
    // view"`) appears and the :docs chip becomes active. The canvas
    // (data-test="count") disappears from <main> because the docs
    // pane replaces it.
    await modeDocsChip.click();
    const docsView = page.locator('[data-test="story-docs-view"]');
    await docsView.waitFor({ state: 'visible', timeout: 5000 });
    if ((await modeDocsChip.getAttribute('aria-selected')) !== 'true') {
      throw new Error('expected :docs chip to be aria-selected="true" after click');
    }
    if ((await modeDevChip.getAttribute('aria-selected')) !== 'false') {
      throw new Error('expected :dev chip to flip aria-selected="false" after switching');
    }

    // ----------------------------------------------------------------
    // 3b-i. :docs mode — AutoDocs view sections (rf2-rodx)
    // ----------------------------------------------------------------
    //
    // The :docs pane composes six sections — header / prose / args /
    // decorators / parameters / tags — per spec/008. The header MUST
    // render the parent story id and at least one tag chip; the args
    // table MUST list every resolved arg with a key/default/doc row;
    // the tags section MUST forward-link the per-tag chip to the
    // sidebar tag-filter.
    await page
      .locator('[data-test="story-docs-parent-story"]')
      .waitFor({ state: 'visible', timeout: 2000 });
    await page
      .locator('[data-test="story-docs-args-table"]')
      .waitFor({ state: 'visible', timeout: 2000 });
    await page
      .locator('[data-test="story-docs-decorators-table"]')
      .waitFor({ state: 'visible', timeout: 2000 });
    await page
      .locator('[data-test="story-docs-tags-section"]')
      .waitFor({ state: 'visible', timeout: 2000 });

    // The args table for :story.counter/loaded carries the :label
    // arg (overridden to "Total" on the variant) — the canonical
    // assertion that the args walk reflects the resolved precedence.
    const labelRow = page
      .locator('[data-test="story-docs-args-row"][data-arg-key=":label"]');
    await labelRow.waitFor({ state: 'visible', timeout: 2000 });
    const labelRowText = await labelRow.innerText();
    if (!/Total/.test(labelRowText)) {
      throw new Error(
        `expected :label arg row to show "Total" as the resolved default, got: ${labelRowText}`,
      );
    }

    // The decorators table MUST include the story-level log-decorator
    // entry. resolve-decorators surfaces it under :hiccup.
    const hiccupRow = page
      .locator('[data-test="story-docs-decorator-row"][data-section="hiccup"]')
      .first();
    await hiccupRow.waitFor({ state: 'visible', timeout: 2000 });

    // The tags-section chips MUST forward-link to the sidebar filter.
    // Click the bottom :test chip; the shell's :tag-filter state slot
    // gains :test, the chip flips aria-pressed="true".
    const docsTestTagChip = page
      .locator('[data-test="story-docs-tag-link"][data-docs-tag="test"]');
    await docsTestTagChip.waitFor({ state: 'visible', timeout: 2000 });
    await docsTestTagChip.click();
    {
      const start = Date.now();
      let pressed = null;
      while (Date.now() - start < 2000) {
        pressed = await docsTestTagChip.getAttribute('aria-pressed').catch(() => null);
        if (pressed === 'true') break;
        await new Promise((r) => setTimeout(r, 50));
      }
      if (pressed !== 'true') {
        throw new Error(
          `expected :test docs tag chip to flip aria-pressed="true" after click, got ${pressed}`,
        );
      }
    }
    // Toggle back off so the sidebar tag-filter assertions in §4
    // start from a clean slate (the :tag-filter is shared state).
    await docsTestTagChip.click();
    {
      const start = Date.now();
      let pressed = null;
      while (Date.now() - start < 2000) {
        pressed = await docsTestTagChip.getAttribute('aria-pressed').catch(() => null);
        if (pressed === 'false') break;
        await new Promise((r) => setTimeout(r, 50));
      }
      if (pressed !== 'false') {
        throw new Error(
          `expected :test docs tag chip to flip aria-pressed="false" after second click, got ${pressed}`,
        );
      }
    }

    // Click Tests → the test pane (rf2-qmjo — `data-test="story-test-
    // view"`) appears, :test chip active. The pane auto-runs the
    // variant on first mount; the :loaded variant carries three
    // canonical `:rf.assert/*` events in its :play slot, all passing.
    await modeTestChip.click();
    const testView = page.locator('[data-test="story-test-view"]');
    await testView.waitFor({ state: 'visible', timeout: 5000 });
    if ((await modeTestChip.getAttribute('aria-selected')) !== 'true') {
      throw new Error('expected :test chip to be aria-selected="true" after click');
    }

    // ----------------------------------------------------------------
    // 3b-ii. :test mode — in-canvas aggregated test runner (rf2-qmjo)
    // ----------------------------------------------------------------
    //
    // The :test pane composes four sections — header / summary /
    // per-test rows / empty-state — per spec/009. For :story.counter/
    // loaded the :play slot declares three passing assertions, so the
    // status pill MUST read "3 passed" and the row table MUST list
    // three rows all with data-status="pass".
    await page
      .locator('[data-test="story-test-parent-story"]')
      .waitFor({ state: 'visible', timeout: 2000 });
    await page
      .locator('[data-test="story-test-rerun"]')
      .waitFor({ state: 'visible', timeout: 2000 });

    // The status pill renders after the auto-run resolves — poll
    // because the run is asynchronous.
    const statusPill = page.locator('[data-test="story-test-status-pill"]');
    await statusPill.waitFor({ state: 'visible', timeout: 5000 });
    {
      const start = Date.now();
      let pillText = '';
      while (Date.now() - start < 5000) {
        pillText = await statusPill.innerText().catch(() => '');
        if (/passed/i.test(pillText)) break;
        await new Promise((r) => setTimeout(r, 50));
      }
      if (!/3\s+passed/i.test(pillText)) {
        throw new Error(
          `expected the :test pane's status pill to read "3 passed" for :story.counter/loaded, got: "${pillText}"`,
        );
      }
    }

    // The counts row carries the green / red / grey tallies.
    const counts = page.locator('[data-test="story-test-counts"]');
    await counts.waitFor({ state: 'visible', timeout: 2000 });
    const countsText = await counts.innerText();
    if (!/3\s+passed/.test(countsText) || !/0\s+failed/.test(countsText)) {
      throw new Error(
        `expected counts to read "3 passed / 0 failed", got: "${countsText}"`,
      );
    }

    // The per-test rows: three rows, all data-status="pass". Each row
    // labels its canonical :rf.assert/* id.
    const passRows = page.locator('[data-test="story-test-row"][data-status="pass"]');
    {
      const start = Date.now();
      let n = 0;
      while (Date.now() - start < 5000) {
        n = await passRows.count();
        if (n >= 3) break;
        await new Promise((r) => setTimeout(r, 50));
      }
      if (n < 3) {
        throw new Error(
          `expected at least 3 passing assertion rows for :story.counter/loaded, got ${n}`,
        );
      }
    }
    // No failing rows for /loaded.
    const failRows = await page
      .locator('[data-test="story-test-row"][data-status="fail"]')
      .count();
    if (failRows !== 0) {
      throw new Error(
        `expected 0 failing rows for :story.counter/loaded, got ${failRows}`,
      );
    }

    // The Re-run button re-fires the lifecycle. Click it; the pill
    // must still read "3 passed" afterwards. Polling because the run
    // is async; we check both that the button reappears (running?
    // toggles off) and the pill stays green.
    await page.locator('[data-test="story-test-rerun"]').click();
    {
      const start = Date.now();
      let recovered = false;
      while (Date.now() - start < 5000) {
        const txt = await statusPill.innerText().catch(() => '');
        if (/3\s+passed/i.test(txt)) {
          recovered = true;
          break;
        }
        await new Promise((r) => setTimeout(r, 50));
      }
      if (!recovered) {
        throw new Error(
          'expected status pill to still read "3 passed" after Re-run click',
        );
      }
    }

    // Last-run elapsed badge MUST render once a run has completed.
    await page
      .locator('[data-test="story-test-elapsed"]')
      .waitFor({ state: 'visible', timeout: 2000 });

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
    // active and the test pane visible. Poll: hydration from
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
      .locator('[data-test="story-test-view"]')
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
    // 8. Chrome-level toolbar — chip render, axis selection, reset (rf2-xi9zk)
    // ====================================================================
    //
    // Per spec/010 the toolbar lives above the three-pane row (chrome-
    // wide, NOT inside the controls aside). It exposes every registered
    // `:mode` as a clickable chip — toggling a chip flips its entry in
    // `:active-modes`; `:axis`-tagged modes single-select-within-axis
    // (toggling :Mode.app/sepia evicts any other `:axis :theme` mode).
    //
    // counter_with_stories registers three theme modes:
    //   - :Mode.app/dark   (no axis — multi-select)
    //   - :Mode.app/light  (no axis — multi-select)
    //   - :Mode.app/sepia  (:axis :theme — single-select-within-axis)
    const toolbar = page.locator('[data-test="story-toolbar"]');
    await toolbar.waitFor({ state: 'visible', timeout: 5000 });

    const darkChip = toolbar.locator('[data-toolbar-mode=":Mode.app/dark"]');
    const lightChip = toolbar.locator('[data-toolbar-mode=":Mode.app/light"]');
    const sepiaChip = toolbar.locator('[data-toolbar-mode=":Mode.app/sepia"]');
    await darkChip.waitFor({ state: 'visible', timeout: 5000 });
    await lightChip.waitFor({ state: 'visible', timeout: 5000 });
    await sepiaChip.waitFor({ state: 'visible', timeout: 5000 });

    // Initial: every chip is aria-pressed="false".
    if ((await darkChip.getAttribute('aria-pressed')) !== 'false') {
      throw new Error('expected :Mode.app/dark chip to start aria-pressed="false"');
    }

    // Click :dark → aria-pressed="true"; :light + :sepia stay "false".
    await darkChip.click();
    {
      const start = Date.now();
      let pressed = null;
      while (Date.now() - start < 2000) {
        pressed = await darkChip.getAttribute('aria-pressed').catch(() => null);
        if (pressed === 'true') break;
        await new Promise((r) => setTimeout(r, 50));
      }
      if (pressed !== 'true') {
        throw new Error(`expected :dark chip aria-pressed="true" after click, got ${pressed}`);
      }
    }
    if ((await lightChip.getAttribute('aria-pressed')) !== 'false') {
      throw new Error('expected :light chip to remain aria-pressed="false" after :dark click (multi-select)');
    }

    // Click :light (un-axis-tagged) → both :dark + :light active (multi-select).
    await lightChip.click();
    {
      const start = Date.now();
      let bothActive = false;
      while (Date.now() - start < 2000) {
        const a = await darkChip.getAttribute('aria-pressed').catch(() => null);
        const b = await lightChip.getAttribute('aria-pressed').catch(() => null);
        if (a === 'true' && b === 'true') {
          bothActive = true;
          break;
        }
        await new Promise((r) => setTimeout(r, 50));
      }
      if (!bothActive) {
        throw new Error('expected :dark + :light to coexist (no axis tag = multi-select)');
      }
    }

    // Click :sepia (:axis :theme) — single-select-within-axis. :sepia
    // is the only :axis :theme-tagged mode in the example; :dark + :light
    // are NOT axis-tagged so they're unaffected (axis-tagged toggles
    // only evict siblings sharing the SAME axis).
    await sepiaChip.click();
    {
      const start = Date.now();
      let ok = false;
      while (Date.now() - start < 2000) {
        const sepia = await sepiaChip.getAttribute('aria-pressed').catch(() => null);
        if (sepia === 'true') {
          ok = true;
          break;
        }
        await new Promise((r) => setTimeout(r, 50));
      }
      if (!ok) {
        throw new Error('expected :sepia chip to flip aria-pressed="true" after click');
      }
    }

    // Reset button appears once at least one mode is active; clicking
    // it clears every chip back to aria-pressed="false".
    const toolbarResetBtn = toolbar.locator('[data-test="story-toolbar-reset"]');
    await toolbarResetBtn.waitFor({ state: 'visible', timeout: 2000 });
    await toolbarResetBtn.click();
    {
      const start = Date.now();
      let cleared = false;
      while (Date.now() - start < 2000) {
        const a = await darkChip.getAttribute('aria-pressed').catch(() => null);
        const b = await lightChip.getAttribute('aria-pressed').catch(() => null);
        const c = await sepiaChip.getAttribute('aria-pressed').catch(() => null);
        if (a === 'false' && b === 'false' && c === 'false') {
          cleared = true;
          break;
        }
        await new Promise((r) => setTimeout(r, 50));
      }
      if (!cleared) {
        throw new Error('expected reset-button click to flip every chip back to aria-pressed="false"');
      }
    }
    // Canvas should still be mounted and titled with the same variant.
    await waitForVariantTitle(page, ':story.counter/loaded');

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
    //
    // Per rf2-9la06: scope all main-pane selectors via the
    // `data-test-variant` anchor that canvas.cljs stamps on the active
    // variant's <section>. Without that anchor, `main.locator(...)`
    // can resolve to a stale workspace cell that's still mounted under
    // <main> because §§5/6 selected a workspace and §7's variant click
    // doesn't clear `:selected-workspace` (selection slots are
    // independent — see tools/story state.cljc). When the workspace
    // is still mounted, `main.locator('[data-test="inc"]').first()`
    // picks the alphabetically-first cell (`:story.counter/clicked-
    // three-times`, count=3 after :play), the click bumps it to 4,
    // and the §11 scrubber assertion (driven against the right-pane
    // slider, which IS scoped to the active variant) reports "still
    // 4" — the canonical signature of this flake.
    const main = page.getByRole('main');
    const loadedCanvas = main.locator(
      '[data-test-variant=":story.counter/loaded"]',
    );
    await loadedCanvas.waitFor({ state: 'visible', timeout: 5000 });
    await loadedCanvas.locator('[data-test="inc"]').first().click();

    // The trace panel's title is "Trace <variant-id> — N events, M cascades".
    // We wait until the count is non-zero.
    await page
      .getByText(/Trace .* — [1-9][0-9]* events/i)
      .first()
      .waitFor({ state: 'visible', timeout: 5000 });

    // ====================================================================
    // 10b. Actions panel — captures the dispatch chronologically (rf2-5yriz)
    // ====================================================================
    //
    // The Actions panel filters the same per-variant trace buffer the
    // six-domino trace panel reads, projecting it down to the
    // user-action subset (`:event/dispatched` + dispatch-shaped
    // `:rf.fx/handled` emits). After the `:counter/inc` click at
    // section 10 the buffer carries the dispatch; the Actions panel
    // must render at least one row whose `data-event-id` matches the
    // dispatched event-id `:counter/inc`.
    const actionsPanel = aside.locator('[data-test="story-actions-panel"]');
    await actionsPanel.waitFor({ state: 'visible', timeout: 5000 });

    // The panel renders one row per action-emit trace event.  By this
    // point in the spec the variant's seed events (`:counter/initialise`,
    // play-sequence dispatches, lifecycle-machine dispatches) have
    // already streamed into the trace buffer through the various
    // workspace + variant-switch + run-variant cycles in §§5-9.
    //
    // Surface assertion: the panel renders at least one row (the
    // buffer is non-empty for the active variant — proving the panel
    // is wired to the same trace bus the six-domino panel reads).
    // We don't pin a specific event-id because re-runs from §§5-9 may
    // have evicted earlier `:counter/inc` rows from the 200-entry
    // ring buffer.
    const rowLocator = actionsPanel.locator('[data-test="story-actions-row"]');
    {
      const start = Date.now();
      let rowCount = 0;
      while (Date.now() - start < 5000) {
        rowCount = await rowLocator.count();
        if (rowCount >= 1) break;
        await new Promise((r) => setTimeout(r, 50));
      }
      if (rowCount < 1) {
        throw new Error(
          `expected the Actions panel to render >= 1 row for the active variant, got ${rowCount}`,
        );
      }
    }

    // Header carries the pause + clear buttons.  Surface assertion:
    // both render with the canonical `data-test` attributes so the
    // panel exposes the documented interaction shape.  The deep
    // pause / clear semantics (snapshot capture, idempotent toggle,
    // ratom cleanup) live in the CLJS-test layer at
    // `tools/story/test/re_frame/story/ui/actions_cljs_test.cljc`;
    // exercising them inline here would mutate trace + paused state
    // mid-spec and confuse the downstream time-travel assertion.
    const pauseBtn = actionsPanel.locator('[data-test="story-actions-pause"]');
    await pauseBtn.waitFor({ state: 'visible', timeout: 2000 });
    const clearBtn = actionsPanel.locator('[data-test="story-actions-clear"]');
    await clearBtn.waitFor({ state: 'visible', timeout: 2000 });

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
    //
    // Per rf2-9la06: read the count from the active variant's canvas
    // via the `data-test-variant` anchor — `main.locator(...).first()`
    // could land on a workspace cell from §§5/6 whose `:counter/
    // initialise` settled to a different value (the scrubber here is
    // the right-pane slider, which IS scoped to the active variant;
    // mismatched canvas + slider was the original "still 4" failure).
    //
    // Per the §10/§11 race fix: the §10 inc click dispatches the event
    // synchronously into re-frame's queue, but the React render that
    // flips the visible count from "7" to "8" lands a microtask later.
    // Reading `beforeText` straight after the click can therefore
    // snapshot the pre-render "7" — and a subsequent scrub-to-epoch-0
    // restore (which puts :count back at 7) lands on the SAME visible
    // value, so the test fails with `still "7"`. Poll until the canvas
    // reflects the post-inc value before establishing the pivot. The
    // /loaded variant initialised to 7 and §10 fired exactly one
    // :counter/inc, so the expected pivot is "8".
    const canvasCount = loadedCanvas.locator('[data-test="count"]').first();
    {
      const start = Date.now();
      let txt = '';
      while (Date.now() - start < 5000) {
        txt = (await canvasCount.textContent()) || '';
        if (txt === '8') break;
        await new Promise((r) => setTimeout(r, 50));
      }
      if (txt !== '8') {
        throw new Error(
          `§10 inc click did not bump :count on :story.counter/loaded — expected canvas to read "8" (initialised 7 + one inc), got "${txt}" after 5s`,
        );
      }
    }
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
    // 11b. Trace × scrubber cross-reference (rf2-sxwvf)
    // ====================================================================
    //
    // The trace panel cross-references the scrubber's selection:
    //   - data-scrubbed-epoch attribute set when a scrub is in flight;
    //   - a scrub-note line surfaces above the cascade table;
    //   - the cascade row whose post-effects produced the selected
    //     epoch carries `data-selected="true"`;
    //   - a "release" button clears the selection.
    //
    // The scrub at step 11 targeted epoch 0 — the variant's
    // `:counter/initialise` settle, which fires BEFORE the trace
    // listener is wired (the listener mounts on variant *selection*,
    // not on frame allocation). So epoch 0's cascade is not in the
    // trace buffer and no row is highlighted from that scrub.
    //
    // To exercise the highlight path we drive a *second* inc on top of
    // the first (now we have epoch 1 = first inc, epoch 2 = second
    // inc; both observed by the trace listener), then scrub to the
    // slider's midpoint (epoch 1) — which IS in the trace buffer →
    // the cascade row for epoch 1 is highlighted; the cascade row for
    // epoch 2 is filtered out (emitted after the selected epoch
    // settled). Drives both halves of the cross-reference: filter +
    // highlight.

    // Anchor: the trace panel's `[data-test]` attribute (rf2-sxwvf
    // added this hook). The panel was already visible at §10 / §11,
    // but capture the locator up-front for clarity.
    const tracePanel = page.locator('[data-test="story-trace-panel"]');

    // Clear the scrubber's selection first (the prior scrub at §11
    // left selection = epoch at slider min — which would filter the
    // trace panel to events at-or-before that epoch, hiding everything
    // the listener captured since). Release returns the panel to
    // showing the full buffer so we can re-scrub against a fresh
    // pivot.
    {
      const rel = aside.locator('[data-test="story-scrubber-release"]');
      if ((await rel.count()) > 0) {
        await rel.click();
      }
    }

    // Drive a "+" click so an inc cascade lands in the trace listener's
    // buffer (the listener filters by `:tags :frame`; events whose
    // frame is the active variant accumulate). We'll scrub to the
    // LATEST epoch (slider max) so we know its cascade is in the
    // buffer. The epoch-history ring buffer's default depth is 50, so
    // the slider's `max` may already be saturated at 49 from earlier
    // activity — the bump-after-inc check would be racy. We simply
    // record the slider's current `max` and scrub to it; the latest
    // record IS the inc we just clicked because every dispatch settles
    // a new epoch at the tail.
    //
    // Per rf2-9la06: scope the click to the active variant's canvas
    // (same rationale as the §10 click above — `.first()` under raw
    // <main> could land on a workspace cell whose state is unrelated
    // to the right-pane scrubber's variant).
    await loadedCanvas.locator('[data-test="inc"]').first().click();

    // Wait for the slider's max to advance once the inc settles in the
    // framework's epoch ring buffer (the listener wires synchronous
    // emission but the epoch record commits on drain-settle, which is
    // async). Poll until the value stops moving rather than budgeting a
    // fixed sleep (rf2-u3amn).
    let sliderMax = 0;
    {
      const start = Date.now();
      let prev = -1;
      let stableCount = 0;
      while (Date.now() - start < 5000) {
        const current = parseInt((await slider.getAttribute('max')) || '0', 10);
        if (current >= 1 && current === prev) {
          stableCount += 1;
          if (stableCount >= 2) {
            sliderMax = current;
            break;
          }
        } else {
          prev = current;
          stableCount = 1;
        }
        await new Promise((r) => setTimeout(r, 50));
      }
    }
    if (sliderMax < 1) {
      throw new Error(
        `expected scrubber slider max >= 1 after an inc, got ${sliderMax}`,
      );
    }

    // Drive the slider to its MAX (the latest epoch = the inc we just
    // clicked). The cascade for that inc IS in the trace buffer (the
    // listener was wired before the click), so the cross-reference
    // must highlight it.
    await slider.evaluate((el, mx) => {
      const native = el;
      native.value = String(mx);
      native.dispatchEvent(new Event('input', { bubbles: true }));
      native.dispatchEvent(new Event('change', { bubbles: true }));
      native.dispatchEvent(new MouseEvent('mouseup', { bubbles: true }));
    }, sliderMax);

    // data-scrubbed-epoch is set after the slider commit. Poll because
    // the ratom commit + Reagent re-render cycle takes a couple of
    // ticks. Anchored on `count()` rather than `visible` because the
    // trace panel sits inside an overflow-auto scroll container — its
    // visibility flag is racy with the right-pane's scroll position
    // after the prior step's interactions, but its presence in the
    // DOM is the load-bearing assertion.
    {
      const start = Date.now();
      let attr = null;
      while (Date.now() - start < 10000) {
        if ((await tracePanel.count()) > 0) {
          attr = await tracePanel.getAttribute('data-scrubbed-epoch').catch(() => null);
          if (attr && attr.length > 0) break;
        }
        await new Promise((r) => setTimeout(r, 50));
      }
      if (!attr) {
        throw new Error(
          `expected story-trace-panel data-scrubbed-epoch to be set after scrub commit (rf2-sxwvf); panel count=${await tracePanel.count()}`,
        );
      }
    }

    // The scrub-note surfaces the selected epoch id. Anchor on
    // count() because the panel sits inside an overflow-auto
    // container (rf2-xc65) that intermittently considers nested
    // nodes "not visible" while React re-renders.
    {
      const noteSel = '[data-test="story-trace-scrub-note"]';
      const start = Date.now();
      let ok = false;
      while (Date.now() - start < 5000) {
        if ((await page.locator(noteSel).count()) > 0) {
          ok = true;
          break;
        }
        await new Promise((r) => setTimeout(r, 50));
      }
      if (!ok) {
        throw new Error(
          'expected story-trace-scrub-note element to render after scrub commit (rf2-sxwvf)',
        );
      }
    }

    // At least one cascade row carries data-selected="true" — the
    // cascade whose post-effects produced the scrubbed-to epoch.
    {
      const start = Date.now();
      let selectedCount = 0;
      while (Date.now() - start < 5000) {
        selectedCount = await page
          .locator('[data-test="story-trace-cascade-row"][data-selected="true"]')
          .count();
        if (selectedCount >= 1) break;
        await new Promise((r) => setTimeout(r, 50));
      }
      if (selectedCount < 1) {
        throw new Error(
          'expected >=1 trace cascade row with data-selected="true" after scrub (rf2-sxwvf cross-reference did not fire)',
        );
      }
    }

    // The "release" button appears under the slider; clicking it
    // clears the selection. Afterwards the panel's data-scrubbed-epoch
    // is absent (DOM attribute removed ⇒ getAttribute returns null)
    // and no row carries data-selected="true".
    const releaseBtn = aside.locator('[data-test="story-scrubber-release"]');
    await releaseBtn.waitFor({ state: 'visible', timeout: 5000 });
    await releaseBtn.click();
    {
      const start = Date.now();
      let cleared = false;
      while (Date.now() - start < 5000) {
        const attr = await tracePanel
          .getAttribute('data-scrubbed-epoch')
          .catch(() => null);
        const sel = await page
          .locator('[data-test="story-trace-cascade-row"][data-selected="true"]')
          .count();
        if (!attr && sel === 0) {
          cleared = true;
          break;
        }
        await new Promise((r) => setTimeout(r, 50));
      }
      if (!cleared) {
        throw new Error(
          'expected scrub-release to clear data-scrubbed-epoch and drop the highlight (rf2-sxwvf)',
        );
      }
    }

    // ====================================================================
    // 12. A11y panel — clicking "run" produces a status update
    // ====================================================================
    //
    // The a11y panel exposes a "run" button. Clicking it kicks off the
    // axe-core lazy-load + scan. The panel's status line transitions
    // from the idle copy ("click run to scan the variant …") through
    // "fetching axe-core…" / "scanning…" / "N violation(s) found in
    // variant". We don't assert which violations land (varies by
    // environment); we assert that the run was kicked off — the status
    // text changes away from the idle copy.
    //
    // Per rf2-qgms1: axe-core MUST scan only the variant's tree, NOT
    // Story's surrounding chrome (sidebar, toolbar, panels, title bar).
    // The fix stamps `data-rf-story-variant-root` on the wrapper around
    // the user-authored decorated view; `run-axe!` finds that node via
    // querySelector and passes it as axe-core's `context` arg. We
    // assert that (a) the variant root exists in the DOM, and (b) any
    // violation overlays land INSIDE the variant root — never on Story
    // chrome nodes.
    //
    // The panel is registered under :rf.story.panel/a11y with title
    // "a11y" rendered in the panel-head. Its run button has accessible
    // name "run" (transitions to "re-run" / "retry" after a scan).

    // 12a. Variant-root marker is present before any scan kicks off —
    // canvas.cljs / workspace.cljc stamp it whenever a variant is
    // mounted. Without this marker the a11y panel would default to
    // scanning the whole document body (the original rf2-qgms1 bug).
    const variantRoots = page.locator(
      '[data-rf-story-variant-root=":story.counter/loaded"]',
    );
    {
      const start = Date.now();
      let n = 0;
      while (Date.now() - start < 5000) {
        n = await variantRoots.count();
        if (n >= 1) break;
        await new Promise((r) => setTimeout(r, 50));
      }
      if (n < 1) {
        throw new Error(
          'rf2-qgms1: expected [data-rf-story-variant-root=":story.counter/loaded"] to be stamped on the mounted variant wrapper',
        );
      }
    }

    const runBtn = aside.getByRole('button', { name: /^(run|re-run|retry)$/i }).first();
    await runBtn.waitFor({ state: 'visible', timeout: 5000 });
    await runBtn.click();

    // The status copy starts as the idle string ("click run to scan
    // the variant …") and transitions through "fetching axe-core…"
    // / "scanning…" / "N violation(s) found in variant". We poll for
    // the transition off the idle copy — if the status remains the
    // idle copy the click was ineffective.
    {
      const start = Date.now();
      let stillIdle = true;
      while (Date.now() - start < 15000) {
        stillIdle = await aside
          .getByText(/click run to scan the variant/i)
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

    // 12b. rf2-qgms1: any `[data-rf-a11y-violation]` overlay decoration
    // MUST land INSIDE the variant root, never on a Story-chrome node
    // (sidebar, toolbar, panel, shell button). The overlay is the
    // user-visible projection of axe-core's violations — if axe-core
    // had been called with `document.body` as context (the old bug),
    // chrome nodes like the sidebar's tag-filter chips or the
    // toolbar's mode chips would frequently pick up the overlay
    // attribute. Asserting "every overlay is inside a variant root"
    // is equivalent to asserting "the scan was correctly scoped".
    //
    // Wait briefly for any decoration to land (axe-core's scan is
    // async; on offline CI it may resolve to :error with zero
    // violations, in which case the loop's zero-decorations is still
    // a passing assertion — no false-positive overlays = correct
    // scoping by trivial vacuity).
    await new Promise((r) => setTimeout(r, 250));
    const leakedDecorations = await page.evaluate(() => {
      const all = Array.from(document.querySelectorAll('[data-rf-a11y-violation]'));
      // An overlay node "leaks" iff none of its ancestors carry the
      // `data-rf-story-variant-root` attribute.
      const leaked = all.filter((el) => !el.closest('[data-rf-story-variant-root]'));
      return leaked.map((el) => ({
        tag: el.tagName,
        cls: el.className && el.className.toString ? el.className.toString() : '',
        text: (el.textContent || '').slice(0, 80),
      }));
    });
    if (leakedDecorations.length > 0) {
      throw new Error(
        `rf2-qgms1: ${leakedDecorations.length} a11y overlay(s) leaked onto Story chrome — ` +
          `axe-core scan should be scoped to [data-rf-story-variant-root], not document.body. ` +
          `Leaked nodes: ${JSON.stringify(leakedDecorations).slice(0, 400)}`,
      );
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
    // 16. Switch back to the live app — counter reappears + inc still wired
    // ====================================================================
    //
    // Hash-change back to the live app; counter should reappear. The
    // live app re-creates its React root inside `mount-app!` and
    // re-renders against the current app-db.
    //
    // Note on the expected starting value: step 3b-ii calls
    // `page.reload()` to verify the mode-tabs primitive re-hydrates its
    // selection from localStorage. That reload throws away the live-app
    // JS context, so the +1 click at step 1 (which bumped :count to 6)
    // does NOT survive into this final section — `core.cljs` re-runs
    // `(rf/dispatch-sync [:counter/initialise 5])` on the reloaded page
    // and the count is back at 5. The trace-panel click at step 10
    // operates on the Story shell's variant frame, not the live app's
    // frame, so it doesn't move the live app's :count either.
    //
    // We therefore assert the post-reload starting value (5), then click
    // +1 to confirm the inc handler is still wired in the re-mounted
    // live app and the canvas re-renders against the bumped app-db
    // (the original Stage-8 invariant — the SPA round-trip preserves
    // event wiring + render path).
    await page.evaluate(() => {
      window.location.hash = '#/';
    });

    const countAfter = page.locator('[data-test="count"]').first();
    await expectTextEquals(countAfter, '5', 10000);

    await page.locator('[data-test="inc"]').first().click();
    await expectTextEquals(countAfter, '6');
  },
};
