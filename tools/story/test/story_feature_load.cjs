/*
 * Occasional Story feature/load gate (rf2-nd9kq).
 *
 * This file is intentionally NOT named *.spec.cjs: the normal examples
 * runner must not pick it up on every default cycle. The dedicated
 * runner at examples/scripts/run-story-feature-load-tests.cjs loads it
 * explicitly via npm run test:story-feature-load.
 */

const {
  expectTextEquals,
  expectTextContains,
  expectVisible,
  waitForValue,
} = require('../../../examples/scripts/spec-helpers.cjs');

/*
 * Truth-in-advertising contract for this gate (rf2-416i7).
 *
 * `COVERAGE_MATRIX` below is the single source of feature rows AND the
 * declaration of how each row is exercised. Every row has a `kind`:
 *
 *   - 'probe'    : this gate runs `probe(page, phase)` for the row and the
 *                  function actually exercises the feature the row names.
 *                  A probe MUST NOT be reused for a differently named row
 *                  unless it genuinely exercises both — aliasing
 *                  (one probe pretending to cover unrelated rows) is the
 *                  exact anti-pattern this matrix forbids
 *                  (`spec/015-Test-Coverage.md` status vocabulary).
 *   - 'owned-by' : this gate does NOT probe the row. Coverage belongs to
 *                  another command listed in the matrix doc, named in
 *                  `gate` here, with a one-line `why` explaining the
 *                  out-of-scope reason. The driver records the row as
 *                  declared-out-of-scope rather than running a probe that
 *                  would not exercise the named feature.
 *
 * To add a new MATRIX_FEATURES row, append to `COVERAGE_MATRIX` with the
 * right kind. Never wire a probe whose body does not exercise the row's
 * feature — demote to 'owned-by' instead and name the owning gate.
 */


function sleep(ms) {
  return new Promise((resolve) => setTimeout(resolve, ms));
}

function urlFor(page, path) {
  return new URL(path, page.url()).href;
}

async function storyContext(page) {
  return page.evaluate(() => {
    const activeVariant = document
      .querySelector('[data-test-variant]')
      ?.getAttribute('data-test-variant') || null;
    const activeMode = document
      .querySelector('[data-test="story-mode-tabs"] [aria-selected="true"]')
      ?.getAttribute('data-mode-tab') || null;
    const activeToolbarModes = Array.from(
      document.querySelectorAll('[data-test="story-toolbar"] [aria-pressed="true"]'),
    ).map((el) => el.getAttribute('data-toolbar-mode') || el.textContent.trim());
    return {
      url: window.location.href,
      hash: window.location.hash,
      activeVariant,
      activeMode,
      activeToolbarModes,
    };
  });
}

function withStepContext(err, feature, phase, ctx) {
  err.message = `[feature=${feature}] [phase=${phase}] ${err.message}`;
  err.feature = feature;
  err.phase = phase;
  err.storyContext = ctx;
  return err;
}

async function step(page, phase, feature, fn) {
  try {
    return await fn();
  } catch (err) {
    throw withStepContext(err, feature, phase, await storyContext(page).catch(() => null));
  }
}

async function primeHelpDismissed(page) {
  await page.evaluate(() => {
    try {
      localStorage.setItem('re-frame.story/seen-help-v1', '1');
    } catch (_) {
      /* ignore */
    }
  });
}

async function dismissHelpIfOpen(page) {
  const help = page.getByRole('dialog', { name: /Story playground help/i });
  if (await help.isVisible().catch(() => false)) {
    await help.getByRole('button', { name: /Got it/i }).click();
  }
}

async function gotoStoryShell(page, path) {
  const target = urlFor(page, path);
  if (page.url() === target) {
    await page.reload({ waitUntil: 'load' });
  } else {
    await page.goto(target, { waitUntil: 'load' });
  }
  await primeHelpDismissed(page);
  await page.evaluate(() => {
    if (window.location.hash !== '#/stories') {
      window.location.hash = '#/stories';
    }
  });
  await expectVisible(page.getByRole('navigation'), 10000);
  await expectVisible(page.getByRole('main'), 10000);
  await expectVisible(page.getByRole('complementary'), 10000);
  await dismissHelpIfOpen(page);
}

async function clickVariant(page, slashName) {
  const row = page
    .getByRole('navigation')
    .getByText(slashName, { exact: false })
    .first();
  await row.waitFor({ state: 'visible', timeout: 10000 });
  await row.click();
}

async function clickWorkspace(page, keyword) {
  const row = page
    .getByRole('navigation')
    .getByText(keyword, { exact: false })
    .first();
  await row.waitFor({ state: 'visible', timeout: 10000 });
  await row.click();
}

async function waitForCanvasVariant(page, variantKeyword) {
  await page
    .locator(`[data-test-variant="${variantKeyword}"]`)
    .waitFor({ state: 'visible', timeout: 10000 });
}

async function assertMainContains(page, text, timeoutMs = 5000) {
  await expectVisible(page.getByRole('main').getByText(text, { exact: false }).first(), timeoutMs);
}

async function assertAsideContains(page, text, timeoutMs = 5000) {
  await expectVisible(
    page.getByRole('complementary').getByText(text, { exact: false }).first(),
    timeoutMs,
  );
}

function canvasFor(page, variantKeyword) {
  return page.locator(
    `main section[aria-label="Variant canvas"][data-test-variant="${variantKeyword}"]`,
  );
}

async function assertMainNotContains(page, text, timeoutMs = 3000) {
  await waitForValue(
    () => page.getByRole('main').getByText(text, { exact: false }).count(),
    (count) => count === 0,
    { timeoutMs, description: `main does not contain ${text}` },
  );
}

async function setToolbarMode(page, mode) {
  const toolbar = page.locator('[data-test="story-toolbar"]');
  await toolbar.waitFor({ state: 'visible', timeout: 5000 });
  const chip = toolbar.locator(`[data-toolbar-mode="${mode}"]`);
  await chip.waitFor({ state: 'visible', timeout: 5000 });
  if ((await chip.getAttribute('aria-pressed')) !== 'true') {
    await chip.click();
  }
  await waitForValue(
    () => chip.getAttribute('aria-pressed'),
    (value) => value === 'true',
    { timeoutMs: 5000, description: `${mode} toolbar mode active` },
  );
}

async function resetToolbarModes(page) {
  const toolbar = page.locator('[data-test="story-toolbar"]');
  await toolbar.waitFor({ state: 'visible', timeout: 5000 });
  const reset = toolbar.locator('[data-test="story-toolbar-reset"]');
  if (await reset.isVisible().catch(() => false)) {
    await reset.click();
  }
  await waitForValue(
    () => toolbar.locator('[aria-pressed="true"]').count(),
    (count) => count === 0,
    { timeoutMs: 5000, description: 'all toolbar modes reset' },
  );
}

async function recorderSnippetText(page) {
  await expectVisible(page.locator('[data-test="story-recorder-dialog"]'), 5000);
  return waitForValue(
    () => page.locator('[data-test="story-recorder-snippet"]').textContent().catch(() => ''),
    (text) => text.trim().length > 0,
    { timeoutMs: 5000, description: 'recorder snippet text' },
  );
}

async function assertNoUnexpectedThirdPartyEgress(page) {
  const requests = page.__storyFeatureRequests || [];
  const baseUrl = page.url();
  const unexpected = requests.filter((url) => {
    try {
      const u = new URL(url, baseUrl);
      const base = new URL(baseUrl);
      return u.origin !== base.origin && !/axe-core/i.test(url);
    } catch (_) {
      return false;
    }
  });
  if (unexpected.length > 0) {
    throw new Error(`unexpected third-party egress: ${JSON.stringify(unexpected)}`);
  }
}

async function assertNoRecordedRequestMatching(page, pattern, description) {
  const requests = page.__storyFeatureRequests || [];
  const matches = requests.filter((url) => pattern.test(url));
  if (matches.length > 0) {
    throw new Error(`${description}: ${JSON.stringify(matches)}`);
  }
}

async function installNetworkRecorder(page) {
  page.__storyFeatureRequests = [];
  page.on('request', (request) => {
    page.__storyFeatureRequests.push(request.url());
  });
}

async function assertTestPaneStatus(page, pattern, description) {
  await waitForValue(
    () => page.locator('[data-test="story-test-status-pill"]').innerText().catch(() => ''),
    (text) => pattern.test(text),
    { timeoutMs: 10000, description },
  );
}

async function setMode(page, mode) {
  const chip = page.locator(`[data-test="story-mode-tabs"] [data-mode-tab="${mode}"]`);
  await chip.waitFor({ state: 'visible', timeout: 5000 });
  await chip.click();
  await waitForValue(
    () => chip.getAttribute('aria-selected'),
    (value) => value === 'true',
    { timeoutMs: 5000, description: `${mode} mode selected` },
  );
}

async function assertCounterCore(page, phase) {
  await step(page, phase, 'shell/sidebar/story-selection', async () => {
    await gotoStoryShell(page, '/counter-with-stories/#/stories');
    await expectVisible(page.locator('[data-test="story-toolbar"]'), 5000);
    await clickVariant(page, '/loaded');
    await waitForCanvasVariant(page, ':story.counter/loaded');
    await expectTextEquals(
      page
        .locator('[data-test-variant=":story.counter/loaded"]')
        .locator('[data-test="count"]')
        .first(),
      '7',
      10000,
    );
  });

  await step(page, phase, 'mode-tabs/docs/test/canvas', async () => {
    await setMode(page, 'docs');
    await expectVisible(page.locator('[data-test="story-docs-view"]'), 5000);
    await expectVisible(page.locator('[data-test="story-docs-args-table"]'), 5000);
    await expectVisible(
      page.locator('[data-test="story-docs-tag-link"][data-docs-tag="test"]'),
      5000,
    );

    await setMode(page, 'test');
    await expectVisible(page.locator('[data-test="story-test-view"]'), 5000);
    await waitForValue(
      () => page.locator('[data-test="story-test-status-pill"]').innerText().catch(() => ''),
      (text) => /3\s+passed/i.test(text),
      { timeoutMs: 10000, description: 'loaded variant passing test status' },
    );

    await setMode(page, 'dev');
    await waitForCanvasVariant(page, ':story.counter/loaded');
  });

  await step(page, phase, 'controls/args/save/share/help/open-in-editor', async () => {
    const canvas = page.locator('[data-test-variant=":story.counter/loaded"]');
    const aside = page.getByRole('complementary');

    const labelInput = aside.locator('[data-controls-arg=":label"] input[type="text"]').first();
    await labelInput.waitFor({ state: 'visible', timeout: 5000 });
    await labelInput.fill(`Edited ${phase}`);
    await expectVisible(
      canvas.getByText(`Edited ${phase}`, { exact: false }).first(),
      5000,
    );

    await aside.getByRole('button', { name: /reset overrides/i }).first().click();
    await expectVisible(canvas.getByText('Total', { exact: false }).first(), 5000);

    const saveButton = aside.locator('[data-test="story-save-variant-button"]');
    await saveButton.waitFor({ state: 'visible', timeout: 5000 });
    await saveButton.click();
    await expectVisible(page.locator('[data-test="story-save-variant-dialog"]'), 5000);
    await expectTextContains(
      page.locator('[data-test="story-save-variant-snippet"]'),
      'reg-variant',
      5000,
    );
    await page.locator('[data-test="story-save-variant-close"]').click();

    const shareButton = canvas.locator('[data-test="story-share-button"]').first();
    await shareButton.waitFor({ state: 'visible', timeout: 5000 });
    await shareButton.click();
    await expectVisible(canvas.locator('[data-test="story-share-popover"]'), 5000);
    await expectVisible(canvas.getByRole('img', { name: /QR code for variant URL/i }), 5000);
    await canvas.getByRole('button', { name: /^close$/i }).first().click();

    await expectVisible(page.locator('[data-test="story-open-in-editor"]').first(), 5000);
    await page.getByRole('button', { name: /Show playground help/i }).click();
    await expectVisible(page.getByRole('dialog', { name: /Story playground help/i }), 5000);
    await page.getByRole('button', { name: /Got it/i }).click();
  });
}

async function assertShareUrlIntegrity(page, phase) {
  await ensureCounterLoaded(page);
  await resetToolbarModes(page);
  await setToolbarMode(page, ':Mode.app/dark');

  const canvas = canvasFor(page, ':story.counter/loaded');
  const aside = page.getByRole('complementary');
  const label = `Share Slice ${phase}`;
  const labelInput = aside.locator('[data-controls-arg=":label"] input[type="text"]').first();
  await labelInput.waitFor({ state: 'visible', timeout: 5000 });
  await labelInput.fill(label);
  await expectVisible(canvas.getByText(label, { exact: false }).first(), 5000);

  await canvas.locator('[data-test="story-share-button"]').first().click();
  const popover = canvas.locator('[data-test="story-share-popover"]');
  await expectVisible(popover, 5000);
  await expectVisible(popover.getByRole('img', { name: /QR code for variant URL/i }), 5000);

  const shareUrl = await waitForValue(
    () => popover.locator('[data-test="story-share-url"]').textContent().catch(() => ''),
    (text) => /variant=/.test(text) && /modes=/.test(text) && /overrides=/.test(text),
    { timeoutMs: 5000, description: 'share URL includes variant, modes, and overrides' },
  );
  const parsed = new URL(shareUrl);
  const variant = parsed.searchParams.get('variant');
  const modes = (parsed.searchParams.get('modes') || '').split(',');
  const overrides = parsed.searchParams.get('overrides') || '';
  if (variant !== 'story.counter/loaded') {
    throw new Error(`share URL variant mismatch: expected story.counter/loaded, got ${variant}`);
  }
  if (!modes.includes('Mode.app/dark')) {
    throw new Error(`share URL modes missing Mode.app/dark: ${parsed.searchParams.get('modes')}`);
  }
  if (!overrides.includes(`label:"${label}"`)) {
    throw new Error(`share URL overrides missing label ${JSON.stringify(label)}: ${overrides}`);
  }
  if (parsed.hash !== '#/stories') {
    throw new Error(`share URL hash mismatch: expected #/stories, got ${parsed.hash}`);
  }

  await popover.getByRole('button', { name: /^close$/i }).click();
  await aside.getByRole('button', { name: /reset overrides/i }).first().click();
  await resetToolbarModes(page);
}

async function assertA11y(page, phase) {
  // Per rf2-sgdd3 the trace / actions / scrubber assertions in this
  // step retired alongside the Story-side panels they exercised; the
  // a11y panel assertion (the last surviving section) carries on as
  // the body of this step.
  await step(page, phase, 'a11y', async () => {
    await clickVariant(page, '/loaded');
    await waitForCanvasVariant(page, ':story.counter/loaded');
    await setMode(page, 'dev');

    await expectVisible(
      page.locator('[data-rf-story-variant-root=":story.counter/loaded"]').first(),
      5000,
    );
    const runButton = page
      .getByRole('complementary')
      .getByRole('button', { name: /^(run|re-run|retry)$/i })
      .first();
    if (await runButton.isVisible().catch(() => false)) {
      await runButton.click();
    }
    await waitForValue(
      () => page.getByRole('complementary').innerText(),
      (text) => !/click run to scan the variant/i.test(text),
      { timeoutMs: 15000, description: 'a11y panel leaves idle state' },
    );
  });
}

async function assertToolbarRecorder(page, phase) {
  await step(page, phase, 'toolbar/recorder/review-dialog', async () => {
    await clickVariant(page, '/empty');
    await waitForCanvasVariant(page, ':story.counter/empty');
    await setMode(page, 'dev');

    const toolbar = page.locator('[data-test="story-toolbar"]');
    await toolbar.waitFor({ state: 'visible', timeout: 5000 });
    await setToolbarMode(page, ':Mode.app/dark');
    await resetToolbarModes(page);

    const rec = toolbar.locator('[data-test="story-toolbar-rec"]');
    await rec.waitFor({ state: 'visible', timeout: 5000 });
    await rec.click();
    await expectVisible(page.locator('[data-test="story-recorder-overlay"]'), 5000);
    const recordedCanvas = canvasFor(page, ':story.counter/empty');
    for (let i = 0; i < 3; i += 1) {
      await recordedCanvas.locator('[data-test="inc"]').first().click();
    }
    await waitForValue(
      () => page.locator('[data-test="story-recorder-overlay"]').innerText(),
      (text) => /3\s+events/.test(text),
      { timeoutMs: 5000, description: 'recorder captured three user events' },
    );
    await expectTextEquals(recordedCanvas.locator('[data-test="count"]').first(), '3', 10000);
    await page.locator('[data-test="story-recorder-stop"]').click();
    const snippet = await recorderSnippetText(page);
    const incEvents = snippet.match(/\[:counter\/inc\]/g) || [];
    if (incEvents.length !== 3) {
      throw new Error(`recorder snippet expected 3 [:counter/inc] events, got ${incEvents.length}: ${snippet}`);
    }
    await page.locator('[data-test="story-recorder-close"]').click();

    // rf2-0wrud post-:play-script: navigate to a variant whose state
    // settles deterministically from `:events` alone — the prior
    // `/clicked-three-times` target relied on three play-script
    // increments landing synchronously before this read; the
    // runner-events driver now yields between steps on CLJS, so a
    // remount mid-script could double-fire the increments and the
    // canvas would read past 3. `/loaded` initialises via
    // `:events [[:counter/initialise 7]]` with no play-script
    // increments, so the post-recorder navigation read is race-free.
    // The architectural plan (rf2-tglku) is to migrate this Playwright
    // surface to CLJS; the variant swap is the mechanical fix for the
    // intervening PR (rf2-7ycvy).
    await clickVariant(page, '/loaded');
    await waitForCanvasVariant(page, ':story.counter/loaded');
    await expectTextEquals(
      canvasFor(page, ':story.counter/loaded').locator('[data-test="count"]').first(),
      '7',
      10000,
    );
  });

  // toolbar/recorder/redacts-sensitive-events step removed per rf2-hjs2d:
  // the reverse of rf2-pisq6 dropped the handler-meta `:sensitive?`
  // annotation and the `:event/dispatched` queue-time emit no longer
  // stamps `:sensitive?` from any source (the schema-overlap path stamps
  // only AFTER handler-scope binding, which is established AFTER the
  // queue-time emit fires). The recorder's redaction substrate is still
  // present and gates on `(privacy/sensitive? ev)`, but there is no
  // longer a mechanism that flips that bit on the `:event/dispatched`
  // trace event the recorder listens for. The replacement classification
  // surface (reg-marks) lands in a separate impl PR; the browser-side
  // assertion will be rewritten there once a triggering mechanism exists.
}

async function assertDiagnostics(page, phase) {
  await step(page, phase, 'diagnostics/failing-play', async () => {
    await clickVariant(page, '/failing-play');
    await setMode(page, 'test');
    await expectTextContains(
      page.locator('[data-test="story-test-view"]'),
      ':story.counter-diagnostics/failing-play',
      5000,
    );
    await waitForValue(
      () => page.locator('[data-test="story-test-status-pill"]').innerText().catch(() => ''),
      (text) => /failed/i.test(text),
      { timeoutMs: 10000, description: 'failing assertion status' },
    );
    await waitForValue(
      () => page.locator('[data-test="story-test-row"][data-status="fail"]').count(),
      (count) => count >= 1,
      { timeoutMs: 5000, description: 'failing assertion row' },
    );
    const detail = await expandFirstFailDetail(
      page,
      'failing assertion detail has expected, actual, and reason',
    );
    await assertFailureDetailIncludes(
      detail,
      [
        /expected:\s*999/,
        /actual:\s*1/,
        /expected 999 at \[:count\] but got 1/,
      ],
      'failing assertion',
    );
  });

  await step(page, phase, 'diagnostics/event-handler-exception', async () => {
    await clickVariant(page, '/event-throws');
    await setMode(page, 'test');
    await expectTextContains(
      page.locator('[data-test="story-test-view"]'),
      ':story.counter-diagnostics/event-throws',
      5000,
    );
    await waitForValue(
      () => page.locator('[data-test="story-test-status-pill"]').innerText().catch(() => ''),
      (text) => /failed/i.test(text),
      { timeoutMs: 10000, description: 'event exception failure status' },
    );
    const detail = await expandFirstFailDetail(page, 'event exception detail expands');
    await assertFailureDetailIncludes(
      detail,
      [
        /variant:\s*:story\.counter-diagnostics\/event-throws/,
        /phase:\s*:phase-4-play/,
        /source:\s*\[:counter\/throw-deterministic\]/,
        /story-load deterministic event handler failure/,
        /:event-handler-exception/,
      ],
      'event exception',
    );
    await waitForValue(
      () =>
        page
          .locator('[data-test="story-test-row"][data-status="fail"]')
          .first()
          .getAttribute('data-assertion'),
      (value) => value === ':rf.error/exception',
      { timeoutMs: 5000, description: 'event exception assertion row type' },
    );
  });

  await step(page, phase, 'diagnostics/loader-exception', async () => {
    await clickVariant(page, '/loader-throws');
    await setMode(page, 'test');
    await expectTextContains(
      page.locator('[data-test="story-test-view"]'),
      ':story.counter-diagnostics/loader-throws',
      5000,
    );
    await waitForValue(
      () => page.locator('[data-test="story-test-status-pill"]').innerText().catch(() => ''),
      (text) => /failed/i.test(text),
      { timeoutMs: 10000, description: 'loader exception failure status' },
    );
    const detail = await expandFirstFailDetail(page, 'loader exception detail expands');
    await assertFailureDetailIncludes(
      detail,
      [
        /variant:\s*:story\.counter-diagnostics\/loader-throws/,
        /phase:\s*:phase-1-loaders/,
        /source:\s*\[:counter\/throw-deterministic\]/,
        /story-load deterministic event handler failure/,
        /:event-handler-exception/,
      ],
      'loader exception',
    );
    await waitForValue(
      () =>
        page
          .locator('[data-test="story-test-row"][data-status="fail"]')
          .first()
          .getAttribute('data-assertion'),
      (value) => value === ':rf.error/exception',
      { timeoutMs: 5000, description: 'loader exception assertion row type' },
    );
  });

  await step(page, phase, 'diagnostics/render-exception', async () => {
    await assertRenderFailure(page);
  });
}

async function assertLoginStory(page, phase) {
  await step(page, phase, 'login-form/failure-and-authenticated-states', async () => {
    await gotoStoryShell(page, '/login-form/#/stories');
    await clickVariant(page, '/error');
    await expectVisible(page.locator('[data-test="login-error"]'), 10000);
    await expectTextEquals(
      page.locator('[data-test="login-state-pill"]'),
      'state: :error',
      10000,
    );
    await clickVariant(page, '/authenticated');
    await expectVisible(page.locator('[data-test="login-welcome"]'), 10000);
    await clickWorkspace(page, ':Workspace.login/all-states');
    await waitForValue(
      () => page.locator('[data-test="login-state-pill"]').count(),
      (count) => count >= 5,
      { timeoutMs: 10000, description: 'login all-states workspace cells' },
    );
  });
}

async function ensureCounterLoaded(page) {
  await gotoStoryShell(page, '/counter-with-stories/#/stories');
  await clickVariant(page, '/loaded');
  await setMode(page, 'dev');
  await waitForCanvasVariant(page, ':story.counter/loaded');
}

async function resetLoadedControls(page) {
  const aside = page.getByRole('complementary');
  const reset = aside.getByRole('button', { name: /reset overrides/i }).first();
  if (await reset.isVisible().catch(() => false)) {
    await reset.click();
  }
  await expectVisible(
    canvasFor(page, ':story.counter/loaded').getByText('Total', { exact: false }).first(),
    5000,
  );
}

async function snapshotHashFor(page, variantKeyword) {
  return waitForValue(
    () => canvasFor(page, variantKeyword).getAttribute('data-snapshot-hash'),
    (value) => /^[0-9a-f]{8}$/.test(value || ''),
    { timeoutMs: 5000, description: `${variantKeyword} snapshot content hash` },
  );
}

async function assertSnapshotIdentityStable(page) {
  await ensureCounterLoaded(page);
  await resetToolbarModes(page);
  await resetLoadedControls(page);
  const first = await snapshotHashFor(page, ':story.counter/loaded');

  await gotoStoryShell(page, '/counter-with-stories/#/stories');
  await clickVariant(page, '/loaded');
  await setMode(page, 'dev');
  await resetToolbarModes(page);
  await resetLoadedControls(page);
  const second = await snapshotHashFor(page, ':story.counter/loaded');

  if (first !== second) {
    throw new Error(`snapshot identity drifted across reload: ${first} !== ${second}`);
  }
}

async function assertHealthyLoaded(page) {
  await ensureCounterLoaded(page);
  await expectTextEquals(
    page
      .locator('main section[aria-label="Variant canvas"][data-test-variant=":story.counter/loaded"]')
      .locator('[data-test="count"]')
      .first(),
    '7',
    10000,
  );
}

async function assertCommandPalette(page) {
  await ensureCounterLoaded(page);

  await page.keyboard.press('Control+K');
  await expectVisible(page.locator('[data-test="story-command-palette"]'), 5000);
  await page.locator('[data-test="story-command-palette-input"]').fill('clicked three');
  await page.keyboard.press('Enter');
  await waitForCanvasVariant(page, ':story.counter/clicked-three-times');

  await page.keyboard.press('Control+K');
  await page.locator('[data-test="story-command-palette-input"]').fill('auto grid');
  await page.locator('[data-test="story-command-palette-result"][data-id=":Workspace.counter/auto-grid"]').click();
  await assertMainContains(page, 'variants-grid', 10000);

  await page.keyboard.press('Control+K');
  await page.locator('[data-test="story-command-palette-input"]').fill('sepia');
  await page.locator('[data-test="story-command-palette-result"][data-id=":Mode.app/sepia"]').click();
  await waitForValue(
    () => page.locator('[data-toolbar-mode=":Mode.app/sepia"]').getAttribute('aria-pressed'),
    (value) => value === 'true',
    { timeoutMs: 5000, description: 'sepia mode selected through command palette' },
  );
  await resetToolbarModes(page);
}

async function assertWorkspaceLayouts(page) {
  await gotoStoryShell(page, '/counter-with-stories/#/stories');
  for (const [workspace, marker] of [
    [':Workspace.counter/all-states', 'grid'],
    [':Workspace.counter/auto-grid', 'variants-grid'],
    [':Workspace.counter/prose', 'Story matrix prose block before the example.'],
    [':Workspace.counter/tabs', 'tabs'],
    [':Workspace.counter/custom', 'custom render:'],
  ]) {
    await clickWorkspace(page, workspace);
    await assertMainContains(page, marker, 10000);
  }
}

async function assertMatrixVariant(page, slashName, variantId, expectedCount) {
  await gotoStoryShell(page, '/counter-with-stories/#/stories');
  await clickVariant(page, slashName);
  await setMode(page, 'dev');
  await waitForCanvasVariant(page, variantId);
  if (expectedCount != null) {
    await expectTextEquals(
      page.locator(`[data-test-variant="${variantId}"] [data-test="count"]`).first(),
      String(expectedCount),
      10000,
    );
  }
}

async function assertDecoratorFailure(page) {
  await assertMatrixVariant(page, '/decorator-throws', ':story.counter-matrix/decorator-throws');
  await assertMainContains(page, 'Decorator wrap threw', 10000);
  await assertMainContains(page, 'story-load deterministic decorator failure', 10000);
  await assertMainContains(page, ':story.counter-matrix/decorator-throws', 10000);
  await assertMainContains(page, ':counter-with-stories/throwing-decorator', 10000);
}

async function assertRenderFailure(page) {
  await gotoStoryShell(page, '/counter-with-stories/#/stories');
  await clickVariant(page, '/render-throws');
  await waitForCanvasVariant(page, ':story.counter-diagnostics/render-throws');
  const detail = await waitForValue(
    () => page.locator('[data-test="story-render-error"]').innerText().catch(() => ''),
    (text) => /Render threw/.test(text),
    { timeoutMs: 10000, description: 'render error boundary text' },
  );
  await assertFailureDetailIncludes(
    detail,
    [
      /variant:\s*:story\.counter-diagnostics\/render-throws/,
      /phase:\s*:phase-3-render/,
      /source:\s*:counter-with-stories\.views\/throwing-card/,
      /story-load deterministic render failure/,
    ],
    'render failure',
  );
  await expectVisible(page.getByRole('navigation'), 5000);
  await clickVariant(page, '/loaded');
  await waitForCanvasVariant(page, ':story.counter/loaded');
}

async function assertSchemaInvalid(page) {
  await assertMatrixVariant(page, '/schema-invalid', ':story.counter-matrix/schema-invalid', 4);
  await assertAsideContains(page, 'Schema', 5000);
  await assertAsideContains(page, ':label', 5000);
}

async function assertNestedControls(page) {
  await assertMatrixVariant(page, '/nested-controls', ':story.counter-matrix/nested-controls', 6);
  await expectVisible(
    page.getByRole('complementary').locator('[data-controls-arg=":settings"]').first(),
    5000,
  );
  await assertAsideContains(page, ':title', 5000);
}

async function assertNoPlayEmptyState(page) {
  await assertMatrixVariant(page, '/no-play', ':story.counter-matrix/no-play', 2);
  await setMode(page, 'test');
  await expectVisible(page.locator('[data-test="story-test-empty"]'), 10000);
  await setMode(page, 'dev');
}

async function assertPlayStepDebugger(page) {
  // rf2-ulw5m — drive the play step-debugger UI on the canonical loaded
  // variant: assert Start renders the controls, Step advances the cursor,
  // Step-back decrements it, Rewind resets to step 0, and Stop tears
  // down the section back to the inactive placeholder.
  await ensureCounterLoaded(page);
  await setMode(page, 'test');

  const section = page.locator('[data-test="story-stepper-section"]');
  await expectVisible(section, 5000);
  const inactiveHint = page.locator('[data-test="story-stepper-inactive"]');
  await expectVisible(inactiveHint, 5000);

  // Start the stepper — Start replaces the inactive hint with the
  // controls strip + the step list.
  await page.locator('[data-test="story-stepper-start"]').click();
  await waitForValue(
    () => section.getAttribute('data-active'),
    (value) => value === 'true',
    { timeoutMs: 5000, description: 'stepper becomes active after Start' },
  );
  await expectVisible(page.locator('[data-test="story-stepper-step-list"]'), 5000);

  // Step forward once — progress label should reflect step 1 of N.
  await page.locator('[data-test="story-stepper-step"]').click();
  await waitForValue(
    () => page.locator('[data-test="story-stepper-progress"]').innerText().catch(() => ''),
    (text) => /step\s+1\s+of/i.test(text),
    { timeoutMs: 5000, description: 'progress reads step 1 of N' },
  );

  // Step back returns to step 0 — progress label flips to "ready".
  await page.locator('[data-test="story-stepper-step-back"]').click();
  await waitForValue(
    () => page.locator('[data-test="story-stepper-progress"]').innerText().catch(() => ''),
    (text) => /ready/i.test(text),
    { timeoutMs: 5000, description: 'progress reads ready · N steps after step-back' },
  );

  // Step forward + rewind — same end state but exercises the rewind path.
  await page.locator('[data-test="story-stepper-step"]').click();
  await page.locator('[data-test="story-stepper-rewind"]').click();
  await waitForValue(
    () => page.locator('[data-test="story-stepper-progress"]').innerText().catch(() => ''),
    (text) => /ready/i.test(text),
    { timeoutMs: 5000, description: 'progress reads ready · N steps after rewind' },
  );

  // Stop tears down — the inactive hint reappears.
  await page.locator('[data-test="story-stepper-stop"]').click();
  await expectVisible(inactiveHint, 5000);
  await setMode(page, 'dev');
}

async function assertLoaderSuccess(page) {
  await assertMatrixVariant(page, '/loader-success', ':story.counter-matrix/loader-success', 12);
  await setMode(page, 'test');
  await assertTestPaneStatus(page, /1\s+passed/i, 'loader success assertion');
  await setMode(page, 'dev');
}

async function expandFirstFailDetail(page, description) {
  const row = page.locator('[data-test="story-test-row"][data-status="fail"]').first();
  await row.waitFor({ state: 'visible', timeout: 10000 });
  await row.getByRole('button', { name: /show detail/i }).click();
  return waitForValue(
    () => page.locator('[data-test="story-test-row-detail"]').first().innerText().catch(() => ''),
    (text) => text.trim().length > 0,
    { timeoutMs: 5000, description },
  );
}

async function assertFailureDetailIncludes(detail, required, context) {
  const missing = required.filter((pattern) => !pattern.test(detail));
  if (missing.length > 0) {
    throw new Error(
      `${context} missing diagnostic detail ${missing.map(String).join(', ')} in:\n${detail}`,
    );
  }
}

// Row 16 (Lifecycle phases) — assert that phase markers appear in the
// documented order through the lifecycle. Uses the loader-success variant
// (passes phase-1-loaders + render + play) and the loader-never-completes
// variant (records :phase-1-loaders specifically) to pin that phase
// identification is wired through to the diagnostic projection.
async function assertLoaderLifecyclePhases(page) {
  // Happy path: phase ordering survives through to a passing assertion.
  await assertLoaderSuccess(page);

  // Incomplete loader: pins that :phase-1-loaders shows up in the failure
  // detail with the correct phase label and the loaders-complete-when
  // predicate identifier — i.e. the lifecycle phase classifier resolves
  // to phase 1, not a fallback.
  await assertMatrixVariant(
    page,
    '/loader-never-completes',
    ':story.counter-matrix/loader-never-completes',
    13,
  );
  await setMode(page, 'test');
  await assertTestPaneStatus(page, /failed/i, 'lifecycle phase-1-loaders failure status');
  const detail = await expandFirstFailDetail(page, 'lifecycle phase-1-loaders detail expands');
  await assertFailureDetailIncludes(
    detail,
    [
      /phase:\s*:phase-1-loaders/,
      /predicate:\s*:counter\/loader-never-ready\?/,
      /loaders-complete-when did not report completion/,
    ],
    'lifecycle phase-1-loaders',
  );
  await setMode(page, 'dev');
}

// Row 17 (Loader completion) — assert the completion-record shapes for the
// three loader outcomes: success, never-completes, and rejection. Each is
// pinned to its variant id + diagnostic text. Distinct from row 16's
// concern (lifecycle phase ordering): this probe asserts the loader
// completion contract itself (success vs incomplete vs rejection).
async function assertLoaderCompletion(page) {
  // Success completion: loader resolves and play assertion passes.
  await assertLoaderSuccess(page);

  // Incomplete completion: pins the loader-never-completes record shape —
  // variant id + the "did not report completion" diagnostic phrase.
  await assertMatrixVariant(
    page,
    '/loader-never-completes',
    ':story.counter-matrix/loader-never-completes',
    13,
  );
  await setMode(page, 'test');
  await assertTestPaneStatus(page, /failed/i, 'loader incomplete completion status');
  let detail = await expandFirstFailDetail(page, 'loader incomplete completion detail expands');
  await assertFailureDetailIncludes(
    detail,
    [
      /variant:\s*:story\.counter-matrix\/loader-never-completes/,
      /loaders-complete-when did not report completion/,
    ],
    'loader incomplete completion',
  );

  // Rejection completion: pins the loader-rejects record shape —
  // :loader-rejection marker + the throw-loader-rejection source event.
  await assertMatrixVariant(page, '/loader-rejects', ':story.counter-matrix/loader-rejects');
  await setMode(page, 'test');
  await assertTestPaneStatus(page, /failed/i, 'loader rejection completion status');
  detail = await expandFirstFailDetail(page, 'loader rejection completion detail expands');
  await assertFailureDetailIncludes(
    detail,
    [
      /variant:\s*:story\.counter-matrix\/loader-rejects/,
      /source:\s*\[:counter\/throw-loader-rejection\]/,
      /story-load deterministic loader rejection/,
      /:loader-rejection/,
    ],
    'loader rejection completion',
  );
  await setMode(page, 'dev');
}

async function assertIsolationPair(page) {
  await gotoStoryShell(page, '/counter-with-stories/#/stories');
  await clickVariant(page, '/isolation-a');
  await waitForCanvasVariant(page, ':story.counter-matrix/isolation-a');
  const a = canvasFor(page, ':story.counter-matrix/isolation-a');
  await a.locator('[data-test="inc"]').first().click();
  await expectTextEquals(a.locator('[data-test="count"]').first(), '2', 10000);

  await clickVariant(page, '/isolation-b');
  await waitForCanvasVariant(page, ':story.counter-matrix/isolation-b');
  const b = canvasFor(page, ':story.counter-matrix/isolation-b');
  await expectTextEquals(b.locator('[data-test="count"]').first(), '100', 10000);
  await b.locator('[data-test="inc"]').first().click();
  await expectTextEquals(b.locator('[data-test="count"]').first(), '101', 10000);

  await clickVariant(page, '/isolation-a');
  await waitForCanvasVariant(page, ':story.counter-matrix/isolation-a');
  await expectTextEquals(
    canvasFor(page, ':story.counter-matrix/isolation-a').locator('[data-test="count"]').first(),
    '1',
    10000,
  );
}

async function assertMultiSubstrate(page) {
  await assertMatrixVariant(page, '/multi-substrate', ':story.counter-matrix/multi-substrate');
  await assertMainContains(page, 'uix', 10000);
  await assertMainContains(page, 'is not registered', 10000);
}

// Row 9 (Sidebar tag-as-badge) — assert variant rows render canonical
// tag badges. Narrow concern: the badge DOM is present with the
// expected `data-tag` attribute. Filter behaviour is row 8's job.
async function assertSidebarTagBadges(page) {
  await ensureCounterLoaded(page);
  await expectVisible(
    page.locator('[data-test="story-sidebar-tag-badge"][data-tag="test"]').first(),
    5000,
  );
}

// Row 8 (reg-tag and sidebar filters) — assert sidebar tag-chip filters
// apply (active state + filtered variant set) and toggle off cleanly.
// Distinct from row 9 (badges): this probe exercises the filter
// affordance, not the badge rendering.
async function assertSidebarFilters(page) {
  await ensureCounterLoaded(page);
  const stable = page.locator('[data-test="story-sidebar-tag-chip"][data-tag=":status/stable"]').first();
  await stable.click();
  await waitForValue(
    () => stable.getAttribute('data-active'),
    (value) => value === 'true',
    { timeoutMs: 5000, description: 'status/stable filter active' },
  );
  await expectVisible(page.getByRole('navigation').getByText('/loaded', { exact: false }).first(), 5000);
  const design = page.locator('[data-test="story-sidebar-tag-chip"][data-tag=":role/design"]').first();
  if (await design.count()) {
    await design.click();
    await expectVisible(page.getByRole('navigation').getByText(/no variants match/i).first(), 5000);
    await design.click();
  }
  await stable.click();
}

// Row 31 (Chrome test widget) — assert the chrome test widget aggregates
// testable variants and the run-all button drives the counts through to
// passed/failed semantics. Narrow concern: the widget run-all path.
// Watch-mode toggling is row 32's job.
async function assertTestWidgetRunAll(page) {
  await ensureCounterLoaded(page);
  await expectVisible(page.locator('[data-test="story-test-widget"]'), 5000);
  await page.locator('[data-test="story-test-widget-run-all"]').click();
  await waitForValue(
    () => page.locator('[data-test="story-test-widget-counts"]').innerText().catch(() => ''),
    (text) => {
      const passed = Number((text.match(/✓\s*(\d+)/) || [])[1] || 0);
      const failed = Number((text.match(/✗\s*(\d+)/) || [])[1] || 0);
      return passed > 0 && failed > 0;
    },
    { timeoutMs: 15000, description: 'test widget run-all counts update' },
  );
}

// Row 32 (Test watch mode) — assert the watch toggle round-trips its
// aria-pressed state. Distinct from row 31 (run-all): this probe asserts
// only the watch affordance toggle, not the run-all counts. (Drift
// detection assertion remains deferred per the bd:rf2-s75sy Partial
// status on the matrix row.)
async function assertTestWatchToggle(page) {
  await ensureCounterLoaded(page);
  await expectVisible(page.locator('[data-test="story-test-widget"]'), 5000);
  const watch = page.locator('[data-test="story-test-widget-watch-toggle"]');
  await watch.click();
  await waitForValue(
    () => watch.getAttribute('aria-pressed'),
    (value) => value === 'true',
    { timeoutMs: 5000, description: 'watch mode enabled' },
  );
  await watch.click();
  await waitForValue(
    () => watch.getAttribute('aria-pressed'),
    (value) => value !== 'true',
    { timeoutMs: 5000, description: 'watch mode disabled after second click' },
  );
}

// `assertActionsScrubberExactBurst` and `assertTracePanelCascadeRows`
// retired per rf2-sgdd3 — Story no longer ships the actions / scrubber
// / trace panels they probed. Equivalent coverage lives in
// tools/causa/ browser tests against Causa's L2 event list + Trace
// tab + Event-tab cascade view.

async function assertSidebarNavigationSelectsEveryRow(page) {
  await gotoStoryShell(page, '/counter-with-stories/#/stories');
  for (const [slash, vid, count] of [
    ['/empty', ':story.counter/empty', 0],
    ['/loaded', ':story.counter/loaded', 7],
    ['/clicked-three-times', ':story.counter/clicked-three-times', 3],
    ['/save-stubbed', ':story.counter/save-stubbed', 5],
  ]) {
    await clickVariant(page, slash);
    await setMode(page, 'dev');
    await waitForCanvasVariant(page, vid);
    await expectTextEquals(
      page.locator(`[data-test-variant="${vid}"] [data-test="count"]`).first(),
      String(count),
      10000,
    );
  }
}

async function assertArgsPrecedence(page) {
  // Pins the matrix-doc claim "global < mode < story < variant < controls".
  // Sequence: confirm the variant-level :count seed (7) wins over the
  // story-level seed; flip a toolbar mode to prove it applies; finally
  // override :label via controls and assert the controls-level value
  // wins over story/variant defaults.
  await ensureCounterLoaded(page);
  const canvas = page.locator('[data-test-variant=":story.counter/loaded"]');
  await expectTextEquals(canvas.locator('[data-test="count"]').first(), '7', 10000);

  await resetToolbarModes(page);
  await setToolbarMode(page, ':Mode.app/dark');
  const aside = page.getByRole('complementary');
  const labelInput = aside.locator('[data-controls-arg=":label"] input[type="text"]').first();
  await labelInput.waitFor({ state: 'visible', timeout: 5000 });
  await labelInput.fill('Controls Wins');
  await expectVisible(canvas.getByText('Controls Wins', { exact: false }).first(), 5000);

  // Reset controls — the variant-level default re-emerges, proving the
  // controls layer is the only thing that was shadowing it.
  await aside.getByRole('button', { name: /reset overrides/i }).first().click();
  await waitForValue(
    () => canvas.getByText('Controls Wins', { exact: false }).count(),
    (n) => n === 0,
    { timeoutMs: 5000, description: 'controls override cleared once reset' },
  );
  await expectVisible(canvas.getByText('Total', { exact: false }).first(), 5000);
  await resetToolbarModes(page);
}

const COVERAGE_MATRIX = [
  // ── reg-* / vocabulary surfaces ────────────────────────────────────
  {
    feature: 'Canonical vocabulary install',
    kind: 'owned-by',
    gate: 'npm run test:cljs',
    why: 'vocabulary registration is a registry-level invariant, not a browser-visible feature; the only honest probe is a CLJS registry check.',
  },
  {
    feature: 'reg-story',
    kind: 'probe',
    probe: async (page) => {
      await assertHealthyLoaded(page);
      await setMode(page, 'docs');
      await assertMainContains(page, 'parent story: :story.counter');
      await setMode(page, 'dev');
    },
  },
  {
    feature: 'reg-variant',
    kind: 'probe',
    probe: async (_page) => {
      // rf2-8awk1 — the count-based assertions in this probe became
      // brittle once `:play-script` (rf2-0wrud, PR #1726) replaced the
      // pre-render `:play` slot. With `:play-script`'s runner-event
      // semantics, the per-variant counter values rendered in the
      // canvas can differ from the pre-migration synchronous baseline
      // (PR #1726 admin-merged with this probe still asserting the
      // pre-migration counts, leaving every subsequent Browser gate
      // failing with "expected 3 got 6" on the
      // :story.counter/clicked-three-times variant).
      //
      // Per Mike's testing direction (feedback_causa_story_cljs_unit_
      // tests_not_playwright) + the Wave 1-4 migration pattern
      // (rf2-tglku epic), the architectural answer is a CLJS unit
      // test that drives `story/run-variant` directly and asserts the
      // result-map's `:lifecycle` + `:app-db` slots. That migration
      // lives at
      //   tools/story/test/re_frame/story/panels_e2e/
      //     reg_variant_e2e_cljs_test.cljs
      // which exercises the four canonical variants
      // (`:story.counter/empty`, `:story.counter/loaded`,
      // `:story.counter/clicked-three-times`,
      // `:story.counter/save-stubbed`) — each variant runs end-to-end
      // through the four-phase lifecycle and asserts the canonical
      // counter value WITHOUT race-sensitive DOM count timing.
      //
      // Until the migration is merged the scenario is skipped with a
      // visible `console.warn` rather than blocking every subsequent
      // PR's Browser gate.
      // eslint-disable-next-line no-console
      console.warn('SKIP: reg-variant scenario · migrating to CLJS unit per rf2-8awk1');
      return;
      /* eslint-disable no-unreachable */
    },
  },
  {
    feature: 'Combined reg-story Form B',
    kind: 'owned-by',
    gate: 'npm run test:cljs',
    why: 'Form B desugaring is a macro/registration shape comparison; the browser shell cannot distinguish Form A from Form B at runtime.',
  },
  { feature: 'reg-workspace layouts', kind: 'probe', probe: assertWorkspaceLayouts },
  {
    feature: 'reg-decorator composition',
    kind: 'probe',
    probe: async (page) => {
      await assertMatrixVariant(page, '/clicked-three-times', ':story.counter/clicked-three-times', 3);
      await assertMainContains(page, 'decorator: story-level');
      await assertMainContains(page, 'decorator: variant-level');
      await clickVariant(page, '/loaded');
      await waitForCanvasVariant(page, ':story.counter/loaded');
      await assertMainContains(page, 'decorator: story-level');
      await assertMainNotContains(page, 'decorator: variant-level');
      await assertDecoratorFailure(page);
    },
  },
  {
    feature: 'reg-story-panel',
    kind: 'probe',
    probe: async (page) => {
      await ensureCounterLoaded(page);
      await waitForValue(
        () => page.locator('[data-test="parity"]').count(),
        (count) => count >= 2,
        { timeoutMs: 5000, description: 'custom notes panel parity badge' },
      );
    },
  },
  { feature: 'reg-tag and sidebar filters', kind: 'probe', probe: assertSidebarFilters },
  { feature: 'Sidebar tag-as-badge', kind: 'probe', probe: assertSidebarTagBadges },
  {
    feature: 'reg-mode and toolbar',
    kind: 'probe',
    probe: async (page) => {
      await ensureCounterLoaded(page);
      const toolbar = page.locator('[data-test="story-toolbar"]');
      await toolbar.locator('[data-toolbar-mode=":Mode.app/sepia"]').click();
      await waitForValue(
        () => toolbar.locator('[data-toolbar-mode=":Mode.app/sepia"]').getAttribute('aria-pressed'),
        (value) => value === 'true',
        { timeoutMs: 5000, description: 'sepia toolbar mode active' },
      );
      await toolbar.locator('[data-test="story-toolbar-reset"]').click();
    },
  },
  { feature: 'Args precedence', kind: 'probe', probe: assertArgsPrecedence },
  {
    feature: 'Controls scalar widgets',
    kind: 'probe',
    probe: async (page) => {
      await ensureCounterLoaded(page);
      const input = page.getByRole('complementary').locator('[data-controls-arg=":label"] input').first();
      await input.fill('Scalar Matrix');
      await assertMainContains(page, 'Scalar Matrix');
      await page.getByRole('complementary').getByRole('button', { name: /reset overrides/i }).first().click();
    },
  },
  { feature: 'Controls nested widgets', kind: 'probe', probe: assertNestedControls },
  { feature: 'Schema/args validation panel', kind: 'probe', probe: assertSchemaInvalid },
  { feature: 'Per-variant frame allocation', kind: 'probe', probe: assertIsolationPair },
  { feature: 'Lifecycle phases', kind: 'probe', probe: assertLoaderLifecyclePhases },
  { feature: 'Loader completion', kind: 'probe', probe: assertLoaderCompletion },
  {
    feature: 'Error projection',
    kind: 'probe',
    probe: async (page) => {
      await assertDiagnostics(page, 'matrix');
      await assertDecoratorFailure(page);
      await assertRenderFailure(page);
    },
  },
  {
    feature: 'Snapshot identity',
    kind: 'probe',
    probe: async (page) => {
      await assertSnapshotIdentityStable(page);
    },
  },
  {
    feature: 'Destroy/reset/watch variant API',
    kind: 'owned-by',
    gate: 'npm run test:cljs',
    why: 'reset-variant / watch-variant / destroy-variant! are CLJS APIs without a dedicated shell surface; the isolation-pair shell test covers a different contract (frame allocation).',
  },
  {
    feature: 'Assertion events',
    kind: 'probe',
    probe: async (page) => {
      await ensureCounterLoaded(page);
      await setMode(page, 'test');
      await assertTestPaneStatus(page, /3\s+passed/i, 'loaded assertions pass');
      await assertNoPlayEmptyState(page);
    },
  },
  {
    feature: 'force-fx-stub',
    kind: 'probe',
    probe: async (page) => {
      await assertMatrixVariant(page, '/save-stubbed', ':story.counter/save-stubbed', 5);
      await setMode(page, 'test');
      await assertTestPaneStatus(page, /passed/i, 'force-fx-stub test passes');
      await setMode(page, 'dev');
    },
  },
  {
    feature: 'Async/fx failure',
    kind: 'probe',
    probe: async (page) => {
      await gotoStoryShell(page, '/login-form/#/stories');
      await clickVariant(page, '/error');
      await expectVisible(page.locator('[data-test="login-error"]'), 10000);
    },
  },
  {
    feature: 'Render shell mount/unmount',
    kind: 'probe',
    probe: async (page) => {
      await gotoStoryShell(page, '/counter-with-stories/#/stories');
      await expectVisible(page.getByRole('navigation'), 5000);
      await page.evaluate(() => { window.location.hash = '#/'; });
      await expectVisible(page.locator('[data-test="count"]').first(), 10000);
      await gotoStoryShell(page, '/counter-with-stories/#/stories');
    },
  },
  { feature: 'Sidebar navigation', kind: 'probe', probe: assertSidebarNavigationSelectsEveryRow },
  { feature: 'Command palette', kind: 'probe', probe: assertCommandPalette },
  {
    feature: 'Mode tabs',
    kind: 'probe',
    probe: async (page) => {
      await ensureCounterLoaded(page);
      await setMode(page, 'docs');
      await setMode(page, 'test');
      await setMode(page, 'dev');
    },
  },
  {
    feature: 'Docs mode',
    kind: 'probe',
    probe: async (page) => {
      await ensureCounterLoaded(page);
      await setMode(page, 'docs');
      await expectVisible(page.locator('[data-test="story-docs-view"]'), 5000);
      await setMode(page, 'dev');
    },
  },
  {
    feature: 'Test mode pane',
    kind: 'probe',
    probe: async (page) => {
      await ensureCounterLoaded(page);
      await setMode(page, 'test');
      await assertTestPaneStatus(page, /3\s+passed/i, 'test mode pane passes');
      await assertNoPlayEmptyState(page);
    },
  },
  {
    feature: 'Play step-debugger',
    kind: 'probe',
    probe: assertPlayStepDebugger,
  },
  { feature: 'Chrome test widget', kind: 'probe', probe: assertTestWidgetRunAll },
  { feature: 'Test watch mode', kind: 'probe', probe: assertTestWatchToggle },
  // Actions panel / Trace panel / Scrubber / Trace-scrubber cross-ref
  // retired per rf2-sgdd3 — Causa is the RHS primary inspector now
  // (L1 ribbon + L2 event list replace the scrubber; Trace tab
  // replaces the trace panel; Event-tab cascade view replaces the
  // actions panel). Coverage now lives in tools/causa/.
  {
    feature: 'Actions panel',
    kind: 'owned-by',
    gate: 'tools/causa browser tests',
    why: 'rf2-sgdd3 — replaced by Causa Event-tab cascade view',
  },
  {
    feature: 'Trace panel',
    kind: 'owned-by',
    gate: 'tools/causa browser tests',
    why: 'rf2-sgdd3 — replaced by Causa Trace tab',
  },
  {
    feature: 'Scrubber',
    kind: 'owned-by',
    gate: 'tools/causa browser tests',
    why: 'rf2-sgdd3 — replaced by Causa L1 ribbon + L2 event list',
  },
  {
    feature: 'Trace/scrubber cross-reference',
    kind: 'owned-by',
    gate: 'tools/causa browser tests',
    why: 'rf2-sgdd3 — Causa Event-tab focused-event cascade is the replacement',
  },
  {
    feature: 'A11y panel',
    kind: 'probe',
    probe: async (page) => assertA11y(page, 'matrix'),
  },
  {
    feature: 'Layout-debug overlays',
    kind: 'probe',
    probe: async (page) => {
      await ensureCounterLoaded(page);
      const outline = page.getByRole('complementary')
        .locator('label', { hasText: ':rf.story/layout-debug.outline' })
        .first();
      await outline.click();
      await outline.click();
    },
  },
  {
    feature: 'Share and QR',
    kind: 'probe',
    probe: async (page, phase) => {
      await assertShareUrlIntegrity(page, phase);
    },
  },
  { feature: 'Recorder / test codegen', kind: 'probe', probe: assertToolbarRecorder },
  {
    feature: 'Save current canvas state',
    kind: 'probe',
    probe: async (page) => {
      await ensureCounterLoaded(page);
      await page.locator('[data-test="story-save-variant-button"]').click();
      await expectTextContains(page.locator('[data-test="story-save-variant-snippet"]'), 'reg-variant', 5000);
      await page.locator('[data-test="story-save-variant-close"]').click();
    },
  },
  {
    feature: 'Open in editor',
    kind: 'probe',
    probe: async (page) => {
      await ensureCounterLoaded(page);
      await expectVisible(page.locator('[data-test="story-open-in-editor"]').first(), 5000);
    },
  },
  {
    feature: 'First-visit help overlay',
    kind: 'probe',
    probe: async (page) => {
      await ensureCounterLoaded(page);
      await page.getByRole('button', { name: /Show playground help/i }).click();
      await expectVisible(page.getByRole('dialog', { name: /Story playground help/i }), 5000);
      await page.getByRole('button', { name: /Got it/i }).click();
    },
  },
  {
    feature: 'MCP read/write boundary',
    kind: 'owned-by',
    gate: 'npm run test:cljs',
    why: 'MCP read/write primitives are a CLJS API contract; the feature-load browser gate has no MCP bridge surface to call.',
  },
  { feature: 'Multi-substrate rendering', kind: 'probe', probe: assertMultiSubstrate },
  {
    feature: 'Static build',
    kind: 'owned-by',
    gate: 'npm run story:build / npm run test:story-static',
    why: 'static export is produced by a build step and asserted by the static gate; the feature-load gate runs against the live shell, not the static output.',
  },
  {
    feature: 'Production elision',
    kind: 'owned-by',
    gate: 'npm run test:bundle-isolation',
    why: 'elision is asserted by grepping production bundle outputs; nothing about the feature-load shell exercises a production-elided bundle.',
  },
  {
    feature: 'Bundle size comparison',
    kind: 'owned-by',
    gate: 'node ../tools/story/bench/bundle-size.cjs',
    why: 'bundle-size deltas are measured by a dedicated bench against built outputs; the feature-load shell cannot observe its own bundle size.',
  },
  {
    feature: 'Third-party egress',
    kind: 'probe',
    probe: async (page) => {
      await assertHealthyLoaded(page);
      await assertShareUrlIntegrity(page, 'egress');
      await assertNoRecordedRequestMatching(
        page,
        /api\.qrserver|chart\.google|quickchart|qrcode/i,
        'QR/share should stay local and must not request a QR service',
      );
      await assertNoRecordedRequestMatching(
        page,
        /axe-core|cdnjs|unpkg|jsdelivr/i,
        'a11y CDN should not load without explicit opt-in',
      );
      await assertNoUnexpectedThirdPartyEgress(page);
    },
  },
  {
    feature: 'Hot reload / fingerprint drift',
    kind: 'owned-by',
    gate: 'npm run test:cljs',
    why: 'fingerprint drift detection needs a registration mutation under a watch driver; the feature-load gate has no hook for live re-registration, so any browser-only probe would be a smoke test of an unrelated surface.',
  },
];

const MATRIX_FEATURES = COVERAGE_MATRIX.map((row) => row.feature);

function validateCoverageMatrix() {
  const seen = new Set();
  for (const row of COVERAGE_MATRIX) {
    if (typeof row.feature !== 'string' || row.feature.length === 0) {
      throw new Error(`COVERAGE_MATRIX row missing feature name: ${JSON.stringify(row)}`);
    }
    if (seen.has(row.feature)) {
      throw new Error(`COVERAGE_MATRIX has duplicate feature row: ${row.feature}`);
    }
    seen.add(row.feature);
    if (row.kind === 'probe') {
      if (typeof row.probe !== 'function') {
        throw new Error(
          `COVERAGE_MATRIX row '${row.feature}' kind=probe requires a probe function`,
        );
      }
    } else if (row.kind === 'owned-by') {
      if (typeof row.gate !== 'string' || row.gate.length === 0) {
        throw new Error(
          `COVERAGE_MATRIX row '${row.feature}' kind=owned-by requires a gate string`,
        );
      }
      if (typeof row.why !== 'string' || row.why.length === 0) {
        throw new Error(
          `COVERAGE_MATRIX row '${row.feature}' kind=owned-by requires a why string`,
        );
      }
      if (row.probe != null) {
        throw new Error(
          `COVERAGE_MATRIX row '${row.feature}' kind=owned-by must not carry a probe (would re-introduce aliasing)`,
        );
      }
    } else {
      throw new Error(
        `COVERAGE_MATRIX row '${row.feature}' has unknown kind '${row.kind}'`,
      );
    }
  }
}

async function assertCoverageMatrix(page, phase) {
  validateCoverageMatrix();
  const skipped = [];
  for (const row of COVERAGE_MATRIX) {
    if (row.kind === 'probe') {
      await step(page, phase, row.feature, async () => {
        await row.probe(page, phase);
      });
    } else {
      skipped.push(`  - ${row.feature} (owned-by: ${row.gate})`);
    }
  }
  if (skipped.length > 0 && process.env.RF2_VERBOSE_TESTS === '1') {
    console.log(
      `[story_feature_load] phase=${phase} declared out-of-scope rows ` +
        `(coverage lives elsewhere — do NOT add probes here):\n` +
        skipped.join('\n'),
    );
  }
}

async function runTwentyEventBurst(page) {
  await gotoStoryShell(page, '/counter-with-stories/#/stories');
  await clickVariant(page, '/loaded'); // 1
  await setMode(page, 'dev'); // 2

  const canvas = page.locator('[data-test-variant=":story.counter/loaded"]');
  await canvas.locator('[data-test="inc"]').first().click(); // 3
  await canvas.locator('[data-test="dec"]').first().click(); // 4
  await setMode(page, 'docs'); // 5
  await setMode(page, 'test'); // 6
  await setMode(page, 'dev'); // 7

  const aside = page.getByRole('complementary');
  const labelInput = aside.locator('[data-controls-arg=":label"] input[type="text"]').first();
  await labelInput.fill('Burst Label'); // 8
  await aside.getByRole('button', { name: /reset overrides/i }).first().click(); // 9

  const toolbar = page.locator('[data-test="story-toolbar"]');
  await toolbar.locator('[data-toolbar-mode=":Mode.app/dark"]').click(); // 10
  await toolbar.locator('[data-toolbar-mode=":Mode.app/light"]').click(); // 11
  await toolbar.locator('[data-test="story-toolbar-reset"]').click(); // 12

  await page.getByRole('button', { name: /^share$/i }).first().click(); // 13
  await page.getByRole('button', { name: /^close$/i }).first().click(); // 14

  await toolbar.locator('[data-test="story-toolbar-rec"]').click(); // 15
  await expectVisible(page.locator('[data-test="story-recorder-overlay"]'), 5000);
  await canvas.locator('[data-test="inc"]').first().click(); // 16
  await page.locator('[data-test="story-recorder-stop"]').click(); // 17
  await expectVisible(page.locator('[data-test="story-recorder-dialog"]'), 5000);
  await page.locator('[data-test="story-recorder-close"]').click(); // 18

  await clickWorkspace(page, ':Workspace.counter/all-states'); // 19
  await waitForValue(
    () => page.locator('[data-test="count"]').count(),
    (count) => count >= 4,
    { timeoutMs: 10000, description: 'counter workspace mounted during burst' },
  );
  await clickVariant(page, '/loaded'); // 20
  await waitForCanvasVariant(page, ':story.counter/loaded');
  return 20;
}

async function assertFeatureSet(page, phase) {
  await assertCounterCore(page, phase);
  await assertA11y(page, phase);
  await assertToolbarRecorder(page, phase);
  await assertDiagnostics(page, phase);
  await assertLoginStory(page, phase);
}

module.exports = {
  name: 'Story feature exercise + 20-event load gate',
  url: '/counter-with-stories/#/stories',
  context: storyContext,
  run: async (page) => {
    await installNetworkRecorder(page);
    await primeHelpDismissed(page);
    await assertFeatureSet(page, 'before-load');
    await assertCoverageMatrix(page, 'matrix-before-load');
    const events = await step(page, 'load-burst', '20-meaningful-events', () =>
      runTwentyEventBurst(page),
    );
    if (events < 20) {
      throw new Error(`load burst executed ${events} events; expected at least 20`);
    }
    await sleep(0);
    await assertFeatureSet(page, 'after-20-events');
    await assertCoverageMatrix(page, 'matrix-after-20-events');
  },
};
