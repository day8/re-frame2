/*
 * Focused Story browser scenarios (rf2-dczqg).
 *
 * This spec is intentionally narrower than story_feature_load.cjs. It
 * exercises real Story features that need a live browser DOM and keeps
 * every failure anchored to a feature name plus the active Story route.
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
    const activeVariant =
      document.querySelector('[data-test-variant]')?.getAttribute('data-test-variant') ||
      null;
    const canvas = activeVariant
      ? document.querySelector(`[data-test-variant="${activeVariant}"]`)
      : null;
    const activeMode =
      document
        .querySelector('[data-test="story-mode-tabs"] [aria-selected="true"]')
        ?.getAttribute('data-mode-tab') || null;
    const activeToolbarModes = Array.from(
      document.querySelectorAll('[data-test="story-toolbar"] [aria-pressed="true"]'),
    ).map((el) => el.getAttribute('data-toolbar-mode') || el.textContent.trim());
    return {
      url: window.location.href,
      search: window.location.search,
      hash: window.location.hash,
      activeVariant,
      activeMode,
      activeToolbarModes,
      snapshotHash: canvas?.getAttribute('data-snapshot-hash') || null,
    };
  });
}

function withContext(err, feature, ctx) {
  err.message = `[story-browser-scenario=${feature}] ${err.message}`;
  err.feature = feature;
  err.phase = 'story-browser-scenarios';
  err.storyContext = ctx;
  return err;
}

async function scenario(page, feature, fn) {
  try {
    return await fn();
  } catch (err) {
    throw withContext(err, feature, await storyContext(page).catch(() => null));
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

async function gotoStory(page, path) {
  await page.goto(urlFor(page, path), { waitUntil: 'load' });
  await primeHelpDismissed(page);
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

function canvas(page, variantId) {
  return page.locator(
    `main section[aria-label="Variant canvas"][data-test-variant="${variantId}"]`,
  );
}

async function waitForCanvas(page, variantId) {
  await canvas(page, variantId).waitFor({ state: 'visible', timeout: 10000 });
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

async function snapshotHash(page, variantId) {
  return canvas(page, variantId).getAttribute('data-snapshot-hash');
}

function assertUrlParam(url, key, expected) {
  const parsed = new URL(url);
  const actual = parsed.searchParams.get(key);
  if (actual !== expected) {
    throw new Error(`expected share URL ${key}=${expected}, got ${actual} in ${url}`);
  }
  return parsed;
}

function assertNoThirdPartyRequests(page) {
  const requests = page.__storyBrowserScenarioRequests || [];
  const base = new URL(page.url());
  const unexpected = requests.filter((requestUrl) => {
    try {
      const u = new URL(requestUrl, base.href);
      return u.origin !== base.origin;
    } catch (_) {
      return false;
    }
  });
  if (unexpected.length > 0) {
    throw new Error(`unexpected third-party requests: ${JSON.stringify(unexpected)}`);
  }
}

module.exports = {
  name: 'Story browser scenarios (rf2-dczqg)',
  url: '/counter-with-stories/#/stories',
  context: storyContext,
  run: async (page) => {
    page.__storyBrowserScenarioRequests = [];
    page.on('request', (request) => {
      page.__storyBrowserScenarioRequests.push(request.url());
    });

    await scenario(page, 'share-url-hydrates-variant-modes-and-props', async () => {
      await primeHelpDismissed(page);
      await gotoStory(
        page,
        '/counter-with-stories/?variant=story.counter%2Floaded&modes=Mode.app%2Fdark&overrides=label%3A%22Shared%20Label%22#/stories',
      );

      await waitForCanvas(page, ':story.counter/loaded');
      const loaded = canvas(page, ':story.counter/loaded');
      await expectTextContains(loaded, 'Shared Label', 10000);
      await expectTextEquals(loaded.locator('[data-test="count"]').first(), '7', 10000);

      const dark = page.locator('[data-toolbar-mode=":Mode.app/dark"]');
      await waitForValue(
        () => dark.getAttribute('aria-pressed'),
        (value) => value === 'true',
        { timeoutMs: 5000, description: 'dark mode hydrated from URL' },
      );
    });

    await scenario(page, 'snapshot-identity-reproducible-and-input-sensitive', async () => {
      const firstHash = await waitForValue(
        () => snapshotHash(page, ':story.counter/loaded'),
        (value) => /^[0-9a-f]{8}$/.test(value || ''),
        { timeoutMs: 5000, description: 'initial snapshot hash' },
      );

      await page.reload({ waitUntil: 'load' });
      await primeHelpDismissed(page);
      await waitForCanvas(page, ':story.counter/loaded');
      const secondHash = await waitForValue(
        () => snapshotHash(page, ':story.counter/loaded'),
        (value) => /^[0-9a-f]{8}$/.test(value || ''),
        { timeoutMs: 5000, description: 'snapshot hash after reload' },
      );
      if (secondHash !== firstHash) {
        throw new Error(`snapshot hash was not reproducible: ${firstHash} -> ${secondHash}`);
      }

      const labelInput = page
        .getByRole('complementary')
        .locator('[data-controls-arg=":label"] input[type="text"]')
        .first();
      await labelInput.waitFor({ state: 'visible', timeout: 5000 });
      await labelInput.fill('Changed by browser scenario');
      await expectTextContains(
        canvas(page, ':story.counter/loaded'),
        'Changed by browser scenario',
        5000,
      );

      const changedHash = await waitForValue(
        () => snapshotHash(page, ':story.counter/loaded'),
        (value) => /^[0-9a-f]{8}$/.test(value || '') && value !== firstHash,
        { timeoutMs: 5000, description: 'snapshot hash changes after arg override' },
      );
      if (changedHash === firstHash) {
        throw new Error('snapshot hash did not change after label override');
      }
    });

    await scenario(page, 'share-popover-and-qr-preserve-variant-id', async () => {
      await page.getByRole('button', { name: /^share$/i }).first().click();
      const shareUrl = await page.locator('[data-test="story-share-url"]').innerText();
      const parsed = assertUrlParam(shareUrl.trim(), 'variant', 'story.counter/loaded');
      if (parsed.hash !== '#/stories') {
        throw new Error(`expected share URL to route back to #/stories, got ${parsed.hash}`);
      }
      const modes = parsed.searchParams.get('modes') || '';
      if (!modes.includes('Mode.app/dark')) {
        throw new Error(`expected share URL modes to include Mode.app/dark, got ${modes}`);
      }
      const overrides = parsed.searchParams.get('overrides') || '';
      if (!overrides.includes('label:"Changed by browser scenario"')) {
        throw new Error(`expected share URL overrides to include edited label, got ${overrides}`);
      }

      const qr = page.locator('[data-test="story-share-qr"]');
      await expectVisible(qr, 5000);
      const qrUrl = await qr.getAttribute('data-share-url');
      if (qrUrl !== shareUrl.trim()) {
        throw new Error(`QR data-share-url differed from visible share URL: ${qrUrl}`);
      }
      assertNoThirdPartyRequests(page);
      await page.getByRole('button', { name: /^close$/i }).first().click();
    });

    await scenario(page, 'assertions-pass-fail-structured-output', async () => {
      await setMode(page, 'test');
      await waitForValue(
        () => page.locator('[data-test="story-test-status-pill"]').innerText().catch(() => ''),
        (text) => /3\s+passed/i.test(text),
        { timeoutMs: 10000, description: 'loaded variant passing assertions' },
      );
      await waitForValue(
        () => page.locator('[data-test="story-test-row"][data-status="pass"]').count(),
        (count) => count >= 3,
        { timeoutMs: 5000, description: 'three passing assertion rows' },
      );

      await clickVariant(page, '/failing-play');
      await waitForCanvas(page, ':story.counter-diagnostics/failing-play');
      await setMode(page, 'test');
      await waitForValue(
        () => page.locator('[data-test="story-test-status-pill"]').innerText().catch(() => ''),
        (text) => /failed/i.test(text),
        { timeoutMs: 10000, description: 'failing variant status' },
      );
      const failRow = page.locator('[data-test="story-test-row"][data-status="fail"]').first();
      await failRow.waitFor({ state: 'visible', timeout: 5000 });
      const assertionId = await failRow.getAttribute('data-assertion');
      if (!assertionId || !assertionId.startsWith(':rf.assert/')) {
        throw new Error(`expected structured failing assertion id, got ${assertionId}`);
      }
    });

    await scenario(page, 'substrate-decorator-and-frame-isolation', async () => {
      await setMode(page, 'dev');
      await clickVariant(page, '/clicked-three-times');
      await waitForCanvas(page, ':story.counter/clicked-three-times');
      await expectTextContains(page.getByRole('main'), 'decorator: story-level', 5000);
      await expectTextContains(page.getByRole('main'), 'decorator: variant-level', 5000);

      await clickVariant(page, '/decorator-throws');
      await waitForCanvas(page, ':story.counter-matrix/decorator-throws');
      await expectTextContains(page.getByRole('main'), 'Decorator wrap threw', 10000);
      await expectTextContains(
        page.getByRole('main'),
        'story-load deterministic decorator failure',
        10000,
      );
      await expectTextEquals(
        canvas(page, ':story.counter-matrix/decorator-throws')
          .locator('[data-test="count"]')
          .first(),
        '8',
        10000,
      );

      await clickVariant(page, '/multi-substrate');
      await waitForCanvas(page, ':story.counter-matrix/multi-substrate');
      await expectTextContains(page.getByRole('main'), 'uix', 10000);
      await expectTextContains(page.getByRole('main'), 'is not registered', 10000);
      await expectTextEquals(
        canvas(page, ':story.counter-matrix/multi-substrate')
          .locator('[data-test="count"]')
          .first(),
        '10',
        10000,
      );

      await clickVariant(page, '/isolation-a');
      await waitForCanvas(page, ':story.counter-matrix/isolation-a');
      const a = canvas(page, ':story.counter-matrix/isolation-a');
      await a.locator('[data-test="inc"]').first().click();
      await expectTextEquals(a.locator('[data-test="count"]').first(), '2', 10000);

      await clickVariant(page, '/isolation-b');
      await waitForCanvas(page, ':story.counter-matrix/isolation-b');
      const b = canvas(page, ':story.counter-matrix/isolation-b');
      await expectTextEquals(b.locator('[data-test="count"]').first(), '100', 10000);
    });

    await scenario(page, 'recorder-redacts-sensitive-events', async () => {
      await clickVariant(page, '/recorder-redaction');
      await waitForCanvas(page, ':story.counter-matrix/recorder-redaction');

      const rec = page.locator('[data-test="story-toolbar-rec"]');
      await rec.waitFor({ state: 'visible', timeout: 5000 });
      await rec.click();
      await expectVisible(page.locator('[data-test="story-recorder-overlay"]'), 5000);

      await canvas(page, ':story.counter-matrix/recorder-redaction')
        .locator('[data-test="story-recorder-sensitive-action"]')
        .click();
      await waitForValue(
        () => page.locator('[data-test="story-recorder-overlay"]').innerText(),
        (text) => /\d+\s+events?/.test(text) && !/0\s+events/.test(text),
        { timeoutMs: 5000, description: 'recorder captured redacted sensitive event' },
      );

      await page.locator('[data-test="story-recorder-stop"]').click();
      await expectVisible(page.locator('[data-test="story-recorder-dialog"]'), 5000);
      const snippet = await page.locator('[data-test="story-recorder-snippet"]').innerText();
      if (!snippet.includes('[:rf/redacted]')) {
        throw new Error(`expected recorder snippet to contain [:rf/redacted], got ${snippet}`);
      }
      if (snippet.includes('browser-secret')) {
        throw new Error(`recorder snippet leaked the sensitive password: ${snippet}`);
      }
      await page.locator('[data-test="story-recorder-close"]').click();
      await sleep(0);
    });
  },
};
