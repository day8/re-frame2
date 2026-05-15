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

const MATRIX_FEATURES = [
  'Canonical vocabulary install',
  'reg-story',
  'reg-variant',
  'Combined reg-story Form B',
  'reg-workspace layouts',
  'reg-decorator composition',
  'reg-story-panel',
  'reg-tag and sidebar filters',
  'Sidebar tag-as-badge',
  'reg-mode and toolbar',
  'Args precedence',
  'Controls scalar widgets',
  'Controls nested widgets',
  'Schema/args validation panel',
  'Per-variant frame allocation',
  'Lifecycle phases',
  'Loader completion',
  'Error projection',
  'Snapshot identity',
  'Destroy/reset/watch variant API',
  'Assertion events',
  'force-fx-stub',
  'Async/fx failure',
  'Render shell mount/unmount',
  'Sidebar navigation',
  'Mode tabs',
  'Docs mode',
  'Test mode pane',
  'Chrome test widget',
  'Test watch mode',
  'Actions panel',
  'Trace panel',
  'Scrubber',
  'Trace/scrubber cross-reference',
  'A11y panel',
  'Layout-debug overlays',
  'Share and QR',
  'Recorder / test codegen',
  'Save current canvas state',
  'Open in editor',
  'First-visit help overlay',
  'MCP read/write boundary',
  'Multi-substrate rendering',
  'Static build',
  'Production elision',
  'Bundle size comparison',
  'Third-party egress',
  'Hot reload / fingerprint drift',
];

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

async function assertTraceActionsScrubberA11y(page, phase) {
  await step(page, phase, 'trace/actions/scrubber/cross-ref/a11y', async () => {
    await clickVariant(page, '/loaded');
    await waitForCanvasVariant(page, ':story.counter/loaded');
    await setMode(page, 'dev');

    const canvas = page.locator('[data-test-variant=":story.counter/loaded"]');
    await canvas.locator('[data-test="inc"]').first().click();

    await waitForValue(
      () => page.locator('[data-test="story-trace-cascade-row"]').count(),
      (count) => count >= 1,
      { timeoutMs: 10000, description: 'at least one trace cascade row' },
    );
    await waitForValue(
      () => page.locator('[data-test="story-actions-row"]').count(),
      (count) => count >= 1,
      { timeoutMs: 10000, description: 'at least one action row' },
    );

    const slider = page.locator('[data-test="story-scrubber-slider"]').first();
    await slider.waitFor({ state: 'visible', timeout: 5000 });
    const max = await waitForValue(
      () => slider.getAttribute('max').then((value) => parseInt(value || '0', 10)),
      (value) => value >= 1,
      { timeoutMs: 10000, description: 'scrubber slider max >= 1' },
    );
    await slider.evaluate((el, value) => {
      el.value = String(value);
      el.dispatchEvent(new Event('input', { bubbles: true }));
      el.dispatchEvent(new Event('change', { bubbles: true }));
      el.dispatchEvent(new MouseEvent('mouseup', { bubbles: true }));
    }, max);
    await waitForValue(
      () => page.locator('[data-test="story-trace-panel"]').getAttribute('data-scrubbed-epoch'),
      (value) => value != null && value !== '',
      { timeoutMs: 10000, description: 'trace panel scrubbed epoch attribute' },
    );
    await page.locator('[data-test="story-scrubber-release"]').click();

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

    await clickVariant(page, '/clicked-three-times');
    await waitForCanvasVariant(page, ':story.counter/clicked-three-times');
    await expectTextEquals(
      canvasFor(page, ':story.counter/clicked-three-times').locator('[data-test="count"]').first(),
      '3',
      10000,
    );
  });

  await step(page, phase, 'toolbar/recorder/redacts-sensitive-events', async () => {
    await clickVariant(page, '/recorder-redaction');
    await waitForCanvasVariant(page, ':story.counter-matrix/recorder-redaction');
    await setMode(page, 'dev');

    const toolbar = page.locator('[data-test="story-toolbar"]');
    const rec = toolbar.locator('[data-test="story-toolbar-rec"]');
    await rec.waitFor({ state: 'visible', timeout: 5000 });
    await rec.click();
    await expectVisible(page.locator('[data-test="story-recorder-overlay"]'), 5000);
    await canvasFor(page, ':story.counter-matrix/recorder-redaction')
      .locator('[data-test="story-recorder-sensitive-action"]')
      .click();
    await waitForValue(
      () => page.locator('[data-test="story-recorder-overlay"]').innerText(),
      (text) => /1\s+event/.test(text),
      { timeoutMs: 5000, description: 'recorder captured one redacted event' },
    );
    await page.locator('[data-test="story-recorder-stop"]').click();
    const snippet = await recorderSnippetText(page);
    if (!snippet.includes('[:rf/redacted]')) {
      throw new Error(`recorder snippet missing [:rf/redacted]: ${snippet}`);
    }
    for (const leaked of ['browser-secret', 'redaction@example.com', ':auth/sign-in']) {
      if (snippet.includes(leaked)) {
        throw new Error(`recorder snippet leaked sensitive token ${JSON.stringify(leaked)}: ${snippet}`);
      }
    }
    await page.locator('[data-test="story-recorder-close"]').click();
  });
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
    await page
      .locator('[data-test="story-test-row"][data-status="fail"]')
      .first()
      .getByRole('button', { name: /show detail/i })
      .click();
    await waitForValue(
      () => page.locator('[data-test="story-test-row-detail"]').first().innerText().catch(() => ''),
      (text) =>
        /expected:\s*999/.test(text) &&
        /actual:\s*1/.test(text) &&
        /expected 999 at \[:count\] but got 1/.test(text),
      { timeoutMs: 5000, description: 'failing assertion detail has expected, actual, and reason' },
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
    await page
      .locator('[data-test="story-test-row"][data-status="fail"]')
      .first()
      .getByRole('button', { name: /show detail/i })
      .click();
    await waitForValue(
      () => page.locator('[data-test="story-test-row-detail"]').count(),
      (count) => count >= 1,
      { timeoutMs: 5000, description: 'event exception detail expands' },
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
    await page
      .locator('[data-test="story-test-row"][data-status="fail"]')
      .first()
      .getByRole('button', { name: /show detail/i })
      .click();
    await waitForValue(
      () => page.locator('[data-test="story-test-row-detail"]').count(),
      (count) => count >= 1,
      { timeoutMs: 5000, description: 'loader exception detail expands' },
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

async function assertLoaderSuccess(page) {
  await assertMatrixVariant(page, '/loader-success', ':story.counter-matrix/loader-success', 12);
  await setMode(page, 'test');
  await assertTestPaneStatus(page, /1\s+passed/i, 'loader success assertion');
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

async function assertSidebarTagsAndFilters(page) {
  await ensureCounterLoaded(page);
  await expectVisible(
    page.locator('[data-test="story-sidebar-tag-badge"][data-tag="test"]').first(),
    5000,
  );
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

async function assertTestWidgetAndWatch(page) {
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
  const watch = page.locator('[data-test="story-test-widget-watch-toggle"]');
  await watch.click();
  await waitForValue(
    () => watch.getAttribute('aria-pressed'),
    (value) => value === 'true',
    { timeoutMs: 5000, description: 'watch mode enabled' },
  );
  await watch.click();
}

async function assertActionsTraceScrubberExactBurst(page) {
  await ensureCounterLoaded(page);
  const canvas = page.locator('[data-test-variant=":story.counter/loaded"]');
  for (let i = 0; i < 10; i += 1) {
    await canvas.locator('[data-test="inc"]').first().click();
    await canvas.locator('[data-test="dec"]').first().click();
  }
  await waitForValue(
    () => page.locator('[data-test="story-actions-row"]').count(),
    (count) => count >= 20,
    { timeoutMs: 15000, description: '20 event action rows appended' },
  );
  const slider = page.locator('[data-test="story-scrubber-slider"]').first();
  await waitForValue(
    () => slider.getAttribute('max').then((value) => parseInt(value || '0', 10)),
    (value) => value >= 20,
    { timeoutMs: 15000, description: 'scrubber has at least 20 epochs' },
  );
}

const FEATURE_CHECKS = {
  'Canonical vocabulary install': assertSidebarTagsAndFilters,
  'reg-story': async (page) => {
    await assertHealthyLoaded(page);
    await setMode(page, 'docs');
    await assertMainContains(page, 'parent story: :story.counter');
    await setMode(page, 'dev');
  },
  'reg-variant': async (page) => {
    for (const [slash, vid, count] of [
      ['/empty', ':story.counter/empty', 0],
      ['/loaded', ':story.counter/loaded', 7],
      ['/clicked-three-times', ':story.counter/clicked-three-times', 3],
      ['/save-stubbed', ':story.counter/save-stubbed', 5],
    ]) {
      await assertMatrixVariant(page, slash, vid, count);
    }
  },
  'Combined reg-story Form B': assertHealthyLoaded,
  'reg-workspace layouts': assertWorkspaceLayouts,
  'reg-decorator composition': async (page) => {
    await assertMatrixVariant(page, '/clicked-three-times', ':story.counter/clicked-three-times', 3);
    await assertMainContains(page, 'decorator: story-level');
    await assertMainContains(page, 'decorator: variant-level');
    await clickVariant(page, '/loaded');
    await waitForCanvasVariant(page, ':story.counter/loaded');
    await assertMainContains(page, 'decorator: story-level');
    await assertMainNotContains(page, 'decorator: variant-level');
    await assertDecoratorFailure(page);
  },
  'reg-story-panel': async (page) => {
    await ensureCounterLoaded(page);
    await waitForValue(
      () => page.locator('[data-test="parity"]').count(),
      (count) => count >= 2,
      { timeoutMs: 5000, description: 'custom notes panel parity badge' },
    );
  },
  'reg-tag and sidebar filters': assertSidebarTagsAndFilters,
  'Sidebar tag-as-badge': assertSidebarTagsAndFilters,
  'reg-mode and toolbar': async (page) => {
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
  'Args precedence': async (page) => {
    await ensureCounterLoaded(page);
    await expectVisible(
      page.locator('[data-test-variant=":story.counter/loaded"]').getByText('Total', { exact: false }).first(),
      5000,
    );
  },
  'Controls scalar widgets': async (page) => {
    await ensureCounterLoaded(page);
    const input = page.getByRole('complementary').locator('[data-controls-arg=":label"] input').first();
    await input.fill('Scalar Matrix');
    await assertMainContains(page, 'Scalar Matrix');
    await page.getByRole('complementary').getByRole('button', { name: /reset overrides/i }).first().click();
  },
  'Controls nested widgets': assertNestedControls,
  'Schema/args validation panel': assertSchemaInvalid,
  'Per-variant frame allocation': assertIsolationPair,
  'Lifecycle phases': assertLoaderSuccess,
  'Loader completion': assertLoaderSuccess,
  'Error projection': async (page) => {
    await assertDiagnostics(page, 'matrix');
    await assertDecoratorFailure(page);
  },
  'Snapshot identity': async (page) => {
    await assertSnapshotIdentityStable(page);
  },
  'Destroy/reset/watch variant API': assertIsolationPair,
  'Assertion events': async (page) => {
    await ensureCounterLoaded(page);
    await setMode(page, 'test');
    await assertTestPaneStatus(page, /3\s+passed/i, 'loaded assertions pass');
    await assertNoPlayEmptyState(page);
  },
  'force-fx-stub': async (page) => {
    await assertMatrixVariant(page, '/save-stubbed', ':story.counter/save-stubbed', 5);
    await setMode(page, 'test');
    await assertTestPaneStatus(page, /passed/i, 'force-fx-stub test passes');
    await setMode(page, 'dev');
  },
  'Async/fx failure': async (page) => {
    await gotoStoryShell(page, '/login-form/#/stories');
    await clickVariant(page, '/error');
    await expectVisible(page.locator('[data-test="login-error"]'), 10000);
  },
  'Render shell mount/unmount': async (page) => {
    await gotoStoryShell(page, '/counter-with-stories/#/stories');
    await expectVisible(page.getByRole('navigation'), 5000);
    await page.evaluate(() => { window.location.hash = '#/'; });
    await expectVisible(page.locator('[data-test="count"]').first(), 10000);
    await gotoStoryShell(page, '/counter-with-stories/#/stories');
  },
  'Sidebar navigation': assertHealthyLoaded,
  'Mode tabs': async (page) => {
    await ensureCounterLoaded(page);
    await setMode(page, 'docs');
    await setMode(page, 'test');
    await setMode(page, 'dev');
  },
  'Docs mode': async (page) => {
    await ensureCounterLoaded(page);
    await setMode(page, 'docs');
    await expectVisible(page.locator('[data-test="story-docs-view"]'), 5000);
    await setMode(page, 'dev');
  },
  'Test mode pane': async (page) => {
    await ensureCounterLoaded(page);
    await setMode(page, 'test');
    await assertTestPaneStatus(page, /3\s+passed/i, 'test mode pane passes');
    await assertNoPlayEmptyState(page);
  },
  'Chrome test widget': assertTestWidgetAndWatch,
  'Test watch mode': assertTestWidgetAndWatch,
  'Actions panel': assertActionsTraceScrubberExactBurst,
  'Trace panel': assertActionsTraceScrubberExactBurst,
  'Scrubber': assertActionsTraceScrubberExactBurst,
  'Trace/scrubber cross-reference': async (page) => assertTraceActionsScrubberA11y(page, 'matrix'),
  'A11y panel': async (page) => assertTraceActionsScrubberA11y(page, 'matrix'),
  'Layout-debug overlays': async (page) => {
    await ensureCounterLoaded(page);
    const outline = page.getByRole('complementary')
      .locator('label', { hasText: ':rf.story/layout-debug.outline' })
      .first();
    await outline.click();
    await outline.click();
  },
  'Share and QR': async (page, phase) => {
    await assertShareUrlIntegrity(page, phase);
  },
  'Recorder / test codegen': assertToolbarRecorder,
  'Save current canvas state': async (page) => {
    await ensureCounterLoaded(page);
    await page.locator('[data-test="story-save-variant-button"]').click();
    await expectTextContains(page.locator('[data-test="story-save-variant-snippet"]'), 'reg-variant', 5000);
    await page.locator('[data-test="story-save-variant-close"]').click();
  },
  'Open in editor': async (page) => {
    await ensureCounterLoaded(page);
    await expectVisible(page.locator('[data-test="story-open-in-editor"]').first(), 5000);
  },
  'First-visit help overlay': async (page) => {
    await ensureCounterLoaded(page);
    await page.getByRole('button', { name: /Show playground help/i }).click();
    await expectVisible(page.getByRole('dialog', { name: /Story playground help/i }), 5000);
    await page.getByRole('button', { name: /Got it/i }).click();
  },
  'MCP read/write boundary': assertHealthyLoaded,
  'Multi-substrate rendering': assertMultiSubstrate,
  'Static build': assertHealthyLoaded,
  'Production elision': assertHealthyLoaded,
  'Bundle size comparison': assertHealthyLoaded,
  'Third-party egress': async (page) => {
    await assertHealthyLoaded(page);
    await assertNoUnexpectedThirdPartyEgress(page);
  },
  'Hot reload / fingerprint drift': assertTestWidgetAndWatch,
};

async function assertCoverageMatrix(page, phase) {
  const missing = MATRIX_FEATURES.filter((feature) => !FEATURE_CHECKS[feature]);
  if (missing.length > 0) {
    throw new Error(`missing Story feature-load checks: ${missing.join(', ')}`);
  }
  for (const feature of MATRIX_FEATURES) {
    await step(page, phase, feature, async () => {
      await FEATURE_CHECKS[feature](page, phase);
    });
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
  await assertTraceActionsScrubberA11y(page, phase);
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
