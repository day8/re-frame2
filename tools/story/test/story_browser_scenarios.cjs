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
const assert = require('assert/strict');

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

async function scrubTo(page, value, description) {
  const slider = page.locator('[data-test="story-scrubber-slider"]').first();
  await slider.waitFor({ state: 'visible', timeout: 5000 });
  const requiredMax = Math.max(value, 1);
  await waitForValue(
    () => slider.getAttribute('max').then((v) => Number.parseInt(v || '0', 10)),
    (max) => max >= requiredMax,
    { timeoutMs: 10000, description: `${description} scrubber max` },
  );
  await slider.evaluate((el, nextValue) => {
    el.value = String(nextValue);
    el.dispatchEvent(new Event('input', { bubbles: true }));
    el.dispatchEvent(new Event('change', { bubbles: true }));
    el.dispatchEvent(new MouseEvent('mouseup', { bubbles: true }));
  }, value);
}

async function snapshotHash(page, variantId) {
  return canvas(page, variantId).getAttribute('data-snapshot-hash');
}

function assertBrowserVisibleUnsignedHex(hash, description) {
  if (!/^[0-9a-f]{8}$/.test(hash || '')) {
    throw new Error(`${description}: expected browser-visible unsigned 8-char hex, got ${hash}`);
  }
  const n = Number.parseInt(hash, 16);
  if (!Number.isInteger(n) || n < 0 || n > 0xffffffff) {
    throw new Error(`${description}: hex was not an unsigned 32-bit value: ${hash}`);
  }
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

async function installDeterministicAxe(page) {
  await page.evaluate(() => {
    localStorage.setItem('rf.story.a11y/cdn-opt-in', 'true');
    window.axe = {
      run(context) {
        const bad = context && context.querySelector
          ? context.querySelector('[data-test="a11y-known-bad-image"]')
          : null;
        if (!bad) {
          return Promise.resolve({ violations: [] });
        }
        return Promise.resolve({
          violations: [
            {
              id: 'image-alt',
              impact: 'critical',
              help: 'Images must have alternate text',
              description: 'Fixture image intentionally omits alt text.',
              nodes: [
                {
                  target: ['[data-test="a11y-known-bad-image"]'],
                },
              ],
            },
          ],
        });
      },
    };
  });
}

async function runA11yScan(page, variantId) {
  await waitForCanvas(page, variantId);
  await page.locator(`[data-rf-story-variant-root="${variantId}"]`).waitFor({
    state: 'visible',
    timeout: 5000,
  });
  const runButton = page
    .getByRole('complementary')
    .getByRole('button', { name: /^(run|re-run|retry)$/i })
    .first();
  await runButton.waitFor({ state: 'visible', timeout: 5000 });
  await runButton.click();
}

async function a11yPanelSnapshot(page) {
  return waitForValue(
    () =>
      page.evaluate(() => {
        const panel = document.querySelector('aside');
        const rows = Array.from(document.querySelectorAll('[data-test="story-a11y-violation"]'))
          .map((el) => ({
            id: el.getAttribute('data-a11y-id') || '',
            impact: el.getAttribute('data-a11y-impact') || '',
            help: el.getAttribute('data-a11y-help') || '',
            target: el.getAttribute('data-a11y-target') || '',
            inVariantRoot: Boolean(el.closest('[data-rf-story-variant-root]')),
          }));
        return {
          text: panel ? panel.innerText : '',
          rows,
        };
      }),
    (snapshot) =>
      /\d+\s+violation\(s\) found in variant/i.test(snapshot.text) ||
      /axe-core failed to load|no variant mounted|needs your approval/i.test(snapshot.text),
    { timeoutMs: 10000, description: 'a11y panel reaches terminal state' },
  );
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

    await scenario(page, 'stale-share-url-degrades-without-selection-or-mode-leak', async () => {
      await primeHelpDismissed(page);
      await gotoStory(
        page,
        '/counter-with-stories/?variant=story.missing%2Fghost&modes=Mode.app%2Fghost&overrides=label%3A%22Ghost%22#/stories',
      );

      await expectVisible(
        page.getByText(/Select a variant or workspace from the sidebar|select a variant from the sidebar/i, { exact: false }).first(),
        10000,
      );
      const selectedVariants = await page.locator('[data-test-variant]').count();
      if (selectedVariants !== 0) {
        throw new Error(`stale share URL selected ${selectedVariants} variant canvas(es)`);
      }
      const activeModes = await page
        .locator('[data-test="story-toolbar"] [data-toolbar-mode][aria-pressed="true"]')
        .count();
      if (activeModes !== 0) {
        throw new Error(`unknown URL mode leaked into active toolbar modes; active=${activeModes}`);
      }
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
      assertBrowserVisibleUnsignedHex(firstHash, 'initial snapshot hash');

      await page.reload({ waitUntil: 'load' });
      await primeHelpDismissed(page);
      await waitForCanvas(page, ':story.counter/loaded');
      const secondHash = await waitForValue(
        () => snapshotHash(page, ':story.counter/loaded'),
        (value) => /^[0-9a-f]{8}$/.test(value || ''),
        { timeoutMs: 5000, description: 'snapshot hash after reload' },
      );
      assertBrowserVisibleUnsignedHex(secondHash, 'snapshot hash after reload');
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
      assertBrowserVisibleUnsignedHex(changedHash, 'changed snapshot hash');
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

    await scenario(page, 'share-url-follows-current-variant-and-per-variant-props', async () => {
      await clickVariant(page, '/empty');
      await waitForCanvas(page, ':story.counter/empty');
      const emptyCanvas = canvas(page, ':story.counter/empty');
      await emptyCanvas.locator('[data-test="story-share-button"]').click();
      const emptyShareUrl = await emptyCanvas.locator('[data-test="story-share-url"]').innerText();
      const emptyParsed = assertUrlParam(emptyShareUrl.trim(), 'variant', 'story.counter/empty');
      const emptyOverrides = emptyParsed.searchParams.get('overrides') || '';
      if (emptyOverrides.includes('Changed by browser scenario')) {
        throw new Error(`loaded variant control override leaked into empty variant share URL: ${emptyOverrides}`);
      }
      await emptyCanvas.getByRole('button', { name: /^close$/i }).first().click();

      await clickVariant(page, '/loaded');
      await waitForCanvas(page, ':story.counter/loaded');
      await expectTextContains(
        canvas(page, ':story.counter/loaded'),
        'Changed by browser scenario',
        5000,
      );
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
      await scrubTo(page, 0, 'isolation-a time travel');
      await waitForValue(
        () => page.locator('[data-test="story-trace-panel"]').getAttribute('data-scrubbed-epoch'),
        (value) => value != null && value !== '',
        { timeoutMs: 10000, description: 'isolation-a scrubbed epoch recorded' },
      );

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

    await scenario(page, 'a11y-known-good-and-known-bad-fixtures', async () => {
      await setMode(page, 'dev');
      await installDeterministicAxe(page);

      await clickVariant(page, '/a11y-known-good');
      await runA11yScan(page, ':story.counter-matrix/a11y-known-good');
      let snapshot = await a11yPanelSnapshot(page);
      if (!/0\s+violation\(s\) found in variant/i.test(snapshot.text)) {
        throw new Error(
          `expected known-good a11y fixture to report zero violations; ` +
            `panel=${JSON.stringify(snapshot)}`,
        );
      }

      await clickVariant(page, '/a11y-known-bad');
      await runA11yScan(page, ':story.counter-matrix/a11y-known-bad');
      snapshot = await a11yPanelSnapshot(page);
      const row = snapshot.rows[0];
      if (snapshot.rows.length !== 1) {
        throw new Error(
          `expected one known-bad a11y violation row; panel=${JSON.stringify(snapshot)}`,
        );
      }
      assert.deepEqual(row, {
        id: 'image-alt',
        impact: 'critical',
        help: 'Images must have alternate text',
        target: '[data-test="a11y-known-bad-image"]',
        inVariantRoot: false,
      });
      const decoratedTarget = await page
        .locator(
          '[data-rf-story-variant-root=":story.counter-matrix/a11y-known-bad"] [data-test="a11y-known-bad-image"][data-rf-a11y-violation="critical"]',
        )
        .count();
      if (decoratedTarget !== 1) {
        throw new Error(
          `expected exactly one bad fixture target decorated inside its variant root; ` +
            `count=${decoratedTarget}`,
        );
      }
    });
  },
};
