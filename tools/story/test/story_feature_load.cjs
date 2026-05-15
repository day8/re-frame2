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

    const shareButton = page.getByRole('button', { name: /^share$/i }).first();
    await shareButton.waitFor({ state: 'visible', timeout: 5000 });
    await shareButton.click();
    await expectVisible(page.getByRole('img', { name: /QR code for variant URL/i }), 5000);
    await page.getByRole('button', { name: /^close$/i }).first().click();

    await expectVisible(page.locator('[data-test="story-open-in-editor"]').first(), 5000);
    await page.getByRole('button', { name: /Show playground help/i }).click();
    await expectVisible(page.getByRole('dialog', { name: /Story playground help/i }), 5000);
    await page.getByRole('button', { name: /Got it/i }).click();
  });
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
    await runButton.waitFor({ state: 'visible', timeout: 5000 });
    await runButton.click();
    await waitForValue(
      () => page.getByRole('complementary').innerText(),
      (text) => !/click run to scan the variant/i.test(text),
      { timeoutMs: 15000, description: 'a11y panel leaves idle state' },
    );
  });
}

async function assertToolbarRecorder(page, phase) {
  await step(page, phase, 'toolbar/recorder/review-dialog', async () => {
    await clickVariant(page, '/loaded');
    await waitForCanvasVariant(page, ':story.counter/loaded');

    const toolbar = page.locator('[data-test="story-toolbar"]');
    await toolbar.waitFor({ state: 'visible', timeout: 5000 });
    const dark = toolbar.locator('[data-toolbar-mode=":Mode.app/dark"]');
    await dark.click();
    await waitForValue(
      () => dark.getAttribute('aria-pressed'),
      (value) => value === 'true',
      { timeoutMs: 5000, description: 'dark toolbar mode active' },
    );
    await toolbar.locator('[data-test="story-toolbar-reset"]').click();
    await waitForValue(
      () => dark.getAttribute('aria-pressed'),
      (value) => value === 'false',
      { timeoutMs: 5000, description: 'dark toolbar mode reset' },
    );

    const rec = toolbar.locator('[data-test="story-toolbar-rec"]');
    await rec.waitFor({ state: 'visible', timeout: 5000 });
    await rec.click();
    await expectVisible(page.locator('[data-test="story-recorder-overlay"]'), 5000);
    await page
      .locator('[data-test-variant=":story.counter/loaded"]')
      .locator('[data-test="inc"]')
      .first()
      .click();
    await waitForValue(
      () => page.locator('[data-test="story-recorder-overlay"]').innerText(),
      (text) => /1 event/.test(text),
      { timeoutMs: 5000, description: 'recorder captured one event' },
    );
    await page.locator('[data-test="story-recorder-stop"]').click();
    await expectVisible(page.locator('[data-test="story-recorder-dialog"]'), 5000);
    await expectTextContains(
      page.locator('[data-test="story-recorder-snippet"]'),
      ':counter/inc',
      5000,
    );
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
    await primeHelpDismissed(page);
    await assertFeatureSet(page, 'before-load');
    const events = await step(page, 'load-burst', '20-meaningful-events', () =>
      runTwentyEventBurst(page),
    );
    if (events < 20) {
      throw new Error(`load burst executed ${events} events; expected at least 20`);
    }
    await sleep(0);
    await assertFeatureSet(page, 'after-20-events');
  },
};
