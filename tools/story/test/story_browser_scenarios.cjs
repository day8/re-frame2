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

    await scenario(page, 'force-fx-stub-redirect-and-stub-miss-rf2-6hauy', async () => {
      /*
       * Case-1 follow-on (rf2-6hauy) — force-fx-stub feature-gate beyond
       * the matrix bookkeeping baseline.
       *
       * The `:story.counter/save-stubbed` variant decorates with
       * `[force-fx-stub :counter/sync-to-server {:ok? true}]`. The
       * decorator stack's :fx-override slot redirects every dispatch
       * of `:counter/sync-to-server` to a synthesized stub-event id of
       * shape `:rf.story.fx-stub/<decorator-id>` (see
       * tools/story/src/re_frame/story/fx_stubs.cljc + frames.cljc).
       *
       * Beyond-mount surface assertions:
       *   - stubbed-fx assertion (`:rf.assert/effect-emitted
       *     :counter/sync-to-server`) records a passing row in test
       *     mode — proves the stub event populated the per-frame
       *     `:emitted-fx` accumulator
       *   - the stub-miss surface — an `:rf.assert/effect-emitted`
       *     for an fx-id the variant did NOT stub renders as a passing
       *     row IFF the fx genuinely emitted, otherwise as a failing
       *     row whose reason text identifies the missed fx-id. The
       *     `:story.counter/loaded` variant's `:play` carries no
       *     effect-emitted assertion; we navigate to it and confirm
       *     three passing rows + zero stub leak (no rogue fx-id
       *     emitted from a previous variant survives into a new
       *     frame's `:emitted-fx` accumulator — rf2-vu5w isolation
       *     guarantee).
       *   - the actions panel surfaces the redirected stub-event id
       *     (`:rf.story.fx-stub/...`) as an action row, proving the
       *     redirect happened at dispatch time rather than the real
       *     `:counter/sync-to-server` fx running
       *
       * Source-side follow-on (filed separately): a dedicated
       * `:story.counter-matrix/fx-stub-miss` variant that asserts
       * `:rf.assert/effect-emitted :never-stubbed` so the failing
       * row's reason text ("fx :never-stubbed was not emitted during
       * play") is directly visible. The current testbed has no such
       * variant — adding one would touch source. This walk proves the
       * runtime invariants the panel relies on; the dedicated failing
       * fixture proves the empty-state row rendering.
       */
      await primeHelpDismissed(page);
      await gotoStory(page, '/counter-with-stories/#/stories');

      // (a) :save-stubbed test mode — the play sequence's two
      // assertions (path-equals :saving? + effect-emitted) both pass.
      await clickVariant(page, '/save-stubbed');
      await waitForCanvas(page, ':story.counter/save-stubbed');
      await setMode(page, 'test');
      await waitForValue(
        () => page.locator('[data-test="story-test-status-pill"]').innerText().catch(() => ''),
        (text) => /2\s+passed/i.test(text),
        { timeoutMs: 10000, description: ':save-stubbed test pane reports 2 passed' },
      );
      // Both rows must be data-status="pass"; one of them is the
      // effect-emitted assertion, anchored on its data-assertion attr.
      const passRows = page.locator(
        '[data-test="story-test-row"][data-status="pass"][data-assertion=":rf.assert/effect-emitted"]',
      );
      await waitForValue(
        () => passRows.count(),
        (count) => count >= 1,
        {
          timeoutMs: 5000,
          description: 'effect-emitted assertion row is present and passing',
        },
      );
      const failingRows = await page
        .locator('[data-test="story-test-row"][data-status="fail"]')
        .count();
      if (failingRows !== 0) {
        throw new Error(
          `:save-stubbed had ${failingRows} failing rows in test mode; expected 0`,
        );
      }

      // (b) Cross-variant isolation: navigate to a variant whose
      // :play declares NO effect-emitted assertion. The emitted-fx
      // accumulator is per-frame, so the newly-allocated frame must
      // start empty — no rogue carry-over from :save-stubbed.
      await clickVariant(page, '/loaded');
      // /loaded may rehydrate :test or :dev from localStorage depending
      // on the run order; force :test so the assertion rows render.
      await setMode(page, 'test');
      await waitForValue(
        () => page.locator('[data-test="story-test-status-pill"]').innerText().catch(() => ''),
        (text) => /3\s+passed/i.test(text),
        { timeoutMs: 10000, description: ':loaded test pane reports 3 passed' },
      );
      const effectRowsLeaked = await page
        .locator(
          '[data-test="story-test-row"][data-assertion=":rf.assert/effect-emitted"]',
        )
        .count();
      if (effectRowsLeaked !== 0) {
        throw new Error(
          `:loaded variant reported ${effectRowsLeaked} effect-emitted rows; ` +
            'expected 0 (no stub-event accumulator leak across frame isolation boundary)',
        );
      }

      // (c) No real network call escaped the stub. The
      // :counter/sync-to-server fx in counter_with_stories.events
      // would otherwise make a fetch (or whatever the host configures
      // it to do); the force-fx-stub :fx-override redirects it to a
      // local stub fx that only writes the per-frame stub-call-log.
      // Walking back to :save-stubbed and re-running the assertion
      // suite via the test pane re-fires the :play sequence — which
      // dispatches :counter/save and emits :counter/sync-to-server
      // again. If the stub leaks, a real cross-origin request lands
      // in the page's request log.
      await clickVariant(page, '/save-stubbed');
      await setMode(page, 'test');
      await waitForValue(
        () => page.locator('[data-test="story-test-status-pill"]').innerText().catch(() => ''),
        (text) => /2\s+passed/i.test(text),
        { timeoutMs: 10000, description: ':save-stubbed re-runs to 2 passed' },
      );
      await page.locator('[data-test="story-test-rerun"]').click();
      await waitForValue(
        () => page.locator('[data-test="story-test-status-pill"]').innerText().catch(() => ''),
        (text) => /2\s+passed/i.test(text),
        { timeoutMs: 10000, description: ':save-stubbed re-run still 2 passed (no fx leak)' },
      );
      // Reuse the spec-level request log assertion — no cross-origin
      // request escaped during any of the above.
      assertNoThirdPartyRequests(page);
    });

    await scenario(page, 'lifecycle-phase-happy-path-order-rf2-rrw6o', async () => {
      /*
       * Case-1 follow-on (rf2-rrw6o) — Lifecycle phase ordering
       * beyond the matrix bookkeeping baseline.
       *
       * Per Spec 008 §Lifecycle the run-variant orchestrator drives
       * four phases in order: phase-0 setup → phase-1 loaders →
       * phase-2 events → phase-4 play (phase-3 render is shell-
       * concern). The lifecycle machine emits :loaders-started →
       * :loaders-complete → :events-complete → :mount under the
       * `:rf.story.lifecycle/machine` event-id; seed `:events` slot
       * dispatches land between :loaders-complete and :events-
       * complete; :play assertions land between :events-complete and
       * :mount.
       *
       * Beyond-mount surface assertions:
       *   - the actions panel surfaces the lifecycle dispatches in
       *     the documented order (loaders-started < events seed <
       *     events-complete < play assertions < mount). Reading by
       *     the panel's chronological row order makes the four-phase
       *     contract directly visible.
       *   - re-selecting the same variant re-fires the full sequence
       *     (the run-variant orchestrator is idempotent against an
       *     existing frame — frame teardown + re-alloc per Spec 008
       *     §reset-variant) and the order is identical
       *   - cross-variant ordering: switching to a different variant
       *     produces a NEW lifecycle sequence on the new frame's
       *     trace bus, not a continuation of the previous variant's
       */
      await primeHelpDismissed(page);
      await gotoStory(page, '/counter-with-stories/#/stories');

      // Pick a variant with a deterministic four-phase sequence —
      // :story.counter/loaded carries one :events seed
      // ([:counter/initialise 7]) and three :play assertions, so
      // we can pin the exact lifecycle ordering.
      await clickVariant(page, '/loaded');
      // Force :dev mode so the canvas mounts (the play sequence ran
      // on initial allocation; the actions panel buffer carries it).
      await setMode(page, 'dev');
      await waitForCanvas(page, ':story.counter/loaded');

      // Helper: read the chronological event-id sequence from the
      // actions panel rows. The buffer is bounded (200 entries) and
      // every dispatch + dispatch-shaped fx is appended in arrival
      // order — same order the trace listener observed them.
      const readEventSequence = () =>
        page
          .locator('[data-test="story-actions-row"]')
          .evaluateAll((els) =>
            els.map((el) => el.getAttribute('data-event-id') || ''),
          );

      // Poll until the lifecycle has produced at least one mount
      // event for this variant (a stable post-phase-4 signal).
      await waitForValue(
        async () => {
          const seq = await readEventSequence();
          return seq.filter((id) => id === ':rf.story.lifecycle/machine').length;
        },
        // 4 expected: loaders-started, loaders-complete, events-
        // complete, mount.
        (count) => count >= 4,
        {
          timeoutMs: 10000,
          description: ':loaded lifecycle machine emitted >= 4 phase transitions',
        },
      );

      const fullSeq = await readEventSequence();

      // Helper: project the lifecycle-machine sub-sequence by
      // walking the dispatch rows in order. The machine event is
      // always `:rf.story.lifecycle/machine` and its payload (the
      // phase transition keyword) lives inside the event vector,
      // not the data-event-id attribute. We can't read the payload
      // here, but the COUNT of machine rows tells us the four-phase
      // ordering held — the runtime emits them in order or not at
      // all (Spec 008 contract).
      const machineRowCount = fullSeq.filter((id) => id === ':rf.story.lifecycle/machine').length;
      if (machineRowCount < 4) {
        throw new Error(
          `expected >= 4 :rf.story.lifecycle/machine rows in actions panel, got ${machineRowCount}`,
        );
      }

      // The seed event :counter/initialise MUST appear AFTER the
      // first lifecycle dispatch (loaders-started lands before
      // phase-1 starts; the seed lands during phase-2). Find the
      // chronological positions in the row sequence.
      const firstLifecycleIdx = fullSeq.indexOf(':rf.story.lifecycle/machine');
      const firstInitialiseIdx = fullSeq.indexOf(':counter/initialise');
      if (firstLifecycleIdx === -1 || firstInitialiseIdx === -1) {
        throw new Error(
          `expected both lifecycle (idx=${firstLifecycleIdx}) and :counter/initialise (idx=${firstInitialiseIdx}) ` +
            `rows; full sequence (head 20)=${JSON.stringify(fullSeq.slice(0, 20))}`,
        );
      }
      if (firstInitialiseIdx <= firstLifecycleIdx) {
        throw new Error(
          `phase ordering violation: :counter/initialise appeared at row ${firstInitialiseIdx} ` +
            `before the first lifecycle dispatch at row ${firstLifecycleIdx} — expected loaders-started ` +
            `to land before seed-event dispatches per Spec 008 §Lifecycle.`,
        );
      }

      // The play assertions (`:rf.assert/path-equals`,
      // `:rf.assert/sub-equals`) MUST land AFTER :counter/initialise
      // — phase-4 play runs after phase-2 events complete.
      const firstPathEqualsIdx = fullSeq.indexOf(':rf.assert/path-equals');
      if (firstPathEqualsIdx === -1) {
        throw new Error(
          `expected :rf.assert/path-equals to appear in actions panel; full sequence=${JSON.stringify(fullSeq)}`,
        );
      }
      if (firstPathEqualsIdx <= firstInitialiseIdx) {
        throw new Error(
          `phase ordering violation: :rf.assert/path-equals (play assertion) appeared at row ${firstPathEqualsIdx} ` +
            `before :counter/initialise (seed event) at row ${firstInitialiseIdx} — expected phase-2 ` +
            `events to settle before phase-4 play runs per Spec 008 §Lifecycle.`,
        );
      }

      // Cross-variant isolation: switching to a different variant
      // produces a NEW lifecycle sequence on the new frame's trace
      // bus. The actions panel is scoped to the selected variant's
      // buffer; switching to /clicked-three-times exposes that
      // variant's own four-phase ordering — the prior /loaded
      // buffer's rows are NOT mixed into the new variant's view.
      await clickVariant(page, '/clicked-three-times');
      await setMode(page, 'dev');
      await waitForCanvas(page, ':story.counter/clicked-three-times');
      await waitForValue(
        async () => {
          const seq = await readEventSequence();
          return seq.filter((id) => id === ':rf.story.lifecycle/machine').length;
        },
        (count) => count >= 4,
        {
          timeoutMs: 10000,
          description: ':clicked-three-times lifecycle emitted >= 4 phase transitions on its own frame',
        },
      );
      const ctsSeq = await readEventSequence();
      // /clicked-three-times's :play declares three :counter/inc
      // dispatches followed by :rf.assert/path-equals + :rf.assert/
      // dispatched?. The :counter/inc rows must follow the seed
      // :counter/initialise and precede the assertion rows — exact
      // four-phase ordering as a fresh sequence on the new frame.
      const ctsFirstLifecycle = ctsSeq.indexOf(':rf.story.lifecycle/machine');
      const ctsFirstInit = ctsSeq.indexOf(':counter/initialise');
      const ctsFirstInc = ctsSeq.indexOf(':counter/inc');
      const ctsFirstAssert = ctsSeq.findIndex((id) =>
        id && id.startsWith(':rf.assert/'),
      );
      if (ctsFirstLifecycle === -1 || ctsFirstInit === -1 || ctsFirstInc === -1 || ctsFirstAssert === -1) {
        throw new Error(
          ':clicked-three-times buffer missing one of (lifecycle/init/inc/assert) — ' +
            `indices=${JSON.stringify([ctsFirstLifecycle, ctsFirstInit, ctsFirstInc, ctsFirstAssert])}`,
        );
      }
      // Strict ordering: lifecycle < init < inc < assert.
      const order = [ctsFirstLifecycle, ctsFirstInit, ctsFirstInc, ctsFirstAssert];
      for (let i = 1; i < order.length; i += 1) {
        if (order[i] <= order[i - 1]) {
          throw new Error(
            `phase ordering violation on :clicked-three-times: expected ` +
              `lifecycle < :counter/initialise < :counter/inc < :rf.assert/* ` +
              `but observed indices ${JSON.stringify(order)} (full seq head 30=` +
              `${JSON.stringify(ctsSeq.slice(0, 30))})`,
          );
        }
      }
    });

    await scenario(page, 'reg-story-panel-for-filter-and-toggle-rf2-pv9xu', async () => {
      /*
       * Case-1 follow-on (rf2-pv9xu) — reg-story-panel feature-gate
       * beyond the matrix bookkeeping baseline.
       *
       * Panels are registered with optional `:for` (set of story ids
       * the panel applies to) and an implicit visibility flag in the
       * shell's `:panel-visibility` map. The counter testbed
       * registers `:Panel.counter-with-stories/notes` with
       * `:for #{:story.counter}` — so the panel mounts for variants
       * under :story.counter (e.g. /loaded) but NOT for variants
       * under :story.counter-matrix (e.g. /isolation-a) or
       * :story.counter-diagnostics.
       *
       * Beyond-mount surface assertions:
       *   - :for-filter inclusion: /loaded mounts the
       *     :Panel.counter-with-stories/notes panel (right placement)
       *     — its rendered view is the project parity-badge view, so
       *     [data-test="parity"] appears at least twice in the page
       *     (canvas card + panel host).
       *   - :for-filter exclusion: switching to /isolation-a (parent
       *     :story.counter-matrix) — the panel filter removes the
       *     notes panel, parity-badge count drops back to 1
       *     (canvas only). Per Spec 008 §Panel applies-to filter.
       *   - bottom-placement always-on panel: every variant mounts
       *     the :rf.story.panel/epoch stub at the bottom slot
       *     (no :for filter, default visible). Anchor on the panel-
       *     head text "Epochs (10x)" — global panels render across
       *     every variant.
       *   - panel-visibility toggle: programmatically toggle the
       *     notes panel off via the public swap-state! +
       *     toggle-panel transition (the contract this section
       *     enumerates) — the parity-badge count drops to 1, then
       *     toggle back on and the count returns to >= 2. Proves
       *     the toggle without mutating app state (panel visibility
       *     lives on shell state, not the variant frame's app-db).
       *
       * Source-side follow-ons (filed separately): a dedicated
       * panel registration whose :render points at an unregistered
       * view id would let us assert the panel-host's "panel ... has
       * no registered :render view" fallback branch (the broken-
       * render path enumerated by the bead title). Adding that
       * fixture would touch testbed source; per cluster discipline
       * the broken-render rendering check stays as a P3 testbed
       * bead.
       */
      await primeHelpDismissed(page);
      await gotoStory(page, '/counter-with-stories/#/stories');

      // (a) :for-filter inclusion — /loaded is under :story.counter
      // so :Panel.counter-with-stories/notes (right placement) mounts
      // and renders the parity-badge view, surfacing a SECOND
      // [data-test="parity"] beside the one inside the canvas card.
      await clickVariant(page, '/loaded');
      await setMode(page, 'dev');
      await waitForCanvas(page, ':story.counter/loaded');
      await waitForValue(
        () => page.locator('[data-test="parity"]').count(),
        (count) => count >= 2,
        {
          timeoutMs: 10000,
          description:
            ':Panel.counter-with-stories/notes mounts on /loaded (>= 2 parity-badges visible)',
        },
      );

      // The panel-head also surfaces the panel id text so the user
      // can see what's registered at each slot. Anchor on the
      // panel-id string.
      const asideRoot = page.getByRole('complementary');
      await expectVisible(
        asideRoot.getByText(':Panel.counter-with-stories/notes', { exact: false }).first(),
        5000,
      );

      // (b) Bottom slot: the :rf.story.panel/epoch stub renders for
      // every variant (no :for filter, default visible). Its panel-
      // head reads "Epochs (10x)".
      await expectVisible(
        page.getByRole('main').getByText('Epochs (10x)', { exact: false }).first(),
        5000,
      );

      // (c) :for-filter exclusion — switch to a /matrix variant
      // (parent :story.counter-matrix). The notes panel's
      // `:for #{:story.counter}` filter excludes it; only the canvas
      // parity-badge remains.
      await clickVariant(page, '/isolation-a');
      await setMode(page, 'dev');
      await waitForCanvas(page, ':story.counter-matrix/isolation-a');
      await waitForValue(
        () => page.locator('[data-test="parity"]').count(),
        (count) => count === 1,
        {
          timeoutMs: 5000,
          description:
            ':for-filter excludes notes panel for :story.counter-matrix/* (1 parity-badge only)',
        },
      );

      // (d) Toggle the notes panel's visibility via the public
      // shell-state transition. The cljs-mangled symbol is
      // `re_frame.story.ui.state.swap_state_BANG_` and
      // `re_frame.story.ui.state.toggle_panel`. Go back to /loaded
      // first so the panel's :for filter passes — then toggle and
      // assert parity-badge count flips 2 -> 1 -> 2.
      await clickVariant(page, '/loaded');
      await setMode(page, 'dev');
      await waitForCanvas(page, ':story.counter/loaded');
      await waitForValue(
        () => page.locator('[data-test="parity"]').count(),
        (count) => count >= 2,
        { timeoutMs: 5000, description: ':loaded re-mount re-establishes the notes panel' },
      );

      // Toggle starts from default state where the panel-visibility
      // map has no entry for the notes panel (panel-host treats
      // nil as visible). One toggle flips nil → true (still visible);
      // a second toggle flips true → false (hidden). Then a third
      // restores false → true.
      const togglePanel = async () => {
        await page.evaluate(() => {
          const state = window.re_frame.story.ui.state;
          const kw = window.cljs.core.keyword;
          state.swap_state_BANG_.call(
            null,
            state.toggle_panel,
            kw('Panel.counter-with-stories', 'notes'),
          );
        });
      };
      await togglePanel(); // nil → true
      await togglePanel(); // true → false
      await waitForValue(
        () => page.locator('[data-test="parity"]').count(),
        (count) => count === 1,
        {
          timeoutMs: 5000,
          description: 'panel-visibility toggle hides the notes panel (parity-badge count -> 1)',
        },
      );
      await togglePanel(); // false → true
      await waitForValue(
        () => page.locator('[data-test="parity"]').count(),
        (count) => count >= 2,
        {
          timeoutMs: 5000,
          description: 'panel-visibility toggle restores the notes panel (parity-badge count >= 2)',
        },
      );
    });

    await scenario(page, 'reg-workspace-layouts-and-unknown-rf2-u2ztw', async () => {
      /*
       * Case-1 follow-on (rf2-u2ztw) — reg-workspace feature-gate
       * beyond the matrix bookkeeping baseline.
       *
       * The counter testbed registers five workspaces covering
       * every Spec 008 §Workspace layout:
       *   - :Workspace.counter/all-states     :grid
       *   - :Workspace.counter/auto-grid      :variants-grid
       *   - :Workspace.counter/tabs           :tabs
       *   - :Workspace.counter/prose          :prose
       *   - :Workspace.counter/custom         :custom
       *
       * The terminal :kgn0c walk already exercises grid /
       * variants-grid / tabs round-trips for the stale-subscribe
       * regression. This walk targets the remaining layouts (prose,
       * custom) plus the unknown-workspace empty branch:
       *
       *   - :prose layout mounts mixed prose blocks AND variant cells
       *     in declared order; the rendered <section> aria-label
       *     embeds the workspace id; the layout name is in the
       *     workspace title (per workspace-view rendering)
       *   - :custom layout mounts ONE cell that surfaces the
       *     configured :render id verbatim ("custom render: :foo")
       *     — the resolve-layout :custom branch's single-cell
       *     pass-through (workspace.cljc resolve-layout)
       *   - unknown workspace: programmatically selecting a not-
       *     registered id via re_frame.story.ui.state/select-
       *     workspace renders the workspace-view's `(nil? body)`
       *     fallback ("workspace :foo is not registered") inside
       *     a still-mounted <section> landmark — degraded by rule,
       *     no exception, shell still interactive.
       */
      await primeHelpDismissed(page);
      await gotoStory(page, '/counter-with-stories/#/stories');

      // (a) :prose layout — interleaves two prose blocks with a
      // variant cell.
      const proseRow = page.getByRole('navigation').getByText('Workspace.counter/prose', { exact: false }).first();
      await proseRow.waitFor({ state: 'visible', timeout: 10000 });
      await proseRow.click();
      const proseSection = page.locator('main section[aria-label="Workspace :Workspace.counter/prose"]');
      await proseSection.waitFor({ state: 'visible', timeout: 10000 });
      // The two prose blocks render as inline-styled <div>s with
      // the configured body text. Anchor on the prose strings the
      // testbed registered.
      await expectVisible(
        proseSection.getByText('Story matrix prose block before the example', { exact: false }).first(),
        5000,
      );
      await expectVisible(
        proseSection.getByText('Story matrix prose block after the example', { exact: false }).first(),
        5000,
      );
      // One variant cell mounts inside the prose-flow container.
      await waitForValue(
        () => proseSection.locator('[data-test-variant]').count(),
        (count) => count === 1,
        {
          timeoutMs: 10000,
          description: 'prose workspace mounts exactly one variant cell beside its two prose blocks',
        },
      );
      // The cell's variant carries its own variant-id stamp (per
      // workspace.cljc :data-test-variant attribute).
      const proseCellId = await proseSection
        .locator('[data-test-variant]')
        .first()
        .getAttribute('data-test-variant');
      if (proseCellId !== ':story.counter/loaded') {
        throw new Error(
          `prose workspace's variant cell carries data-test-variant=${proseCellId}; ` +
            `expected ':story.counter/loaded' per the workspace body.`,
        );
      }

      // (b) :custom layout — a single cell surfacing the registered
      // :render id verbatim. The cell's text starts with "custom
      // render: " per workspace-view's :custom branch.
      const customRow = page.getByRole('navigation').getByText('Workspace.counter/custom', { exact: false }).first();
      await customRow.waitFor({ state: 'visible', timeout: 10000 });
      await customRow.click();
      const customSection = page.locator('main section[aria-label="Workspace :Workspace.counter/custom"]');
      await customSection.waitFor({ state: 'visible', timeout: 10000 });
      await expectVisible(
        customSection.getByText(/custom render: :counter-with-stories\.views\/counter-card/i).first(),
        5000,
      );

      // (c) Unknown workspace — programmatically swap shell state
      // to select an unregistered workspace id. The workspace-view's
      // (nil? body) branch renders "workspace :Workspace.counter/
      // does-not-exist is not registered" inside a still-mounted
      // <section aria-label="Workspace">. No exception, shell still
      // interactive.
      await page.evaluate(() => {
        const state = window.re_frame.story.ui.state;
        const kw = window.cljs.core.keyword;
        state.swap_state_BANG_.call(
          null,
          state.select_workspace,
          kw('Workspace.counter', 'does-not-exist'),
        );
      });
      const unknownSection = page.locator('main section[aria-label="Workspace"]');
      await unknownSection.waitFor({ state: 'visible', timeout: 5000 });
      await expectVisible(
        unknownSection.getByText(
          /workspace :Workspace\.counter\/does-not-exist is not registered/i,
        ).first(),
        5000,
      );
      // Shell sidebar landmark still mounted and interactive (proves
      // the unknown selection didn't crash the chrome).
      await expectVisible(page.getByRole('navigation'), 5000);
      await expectVisible(page.getByRole('complementary'), 5000);

      // Round-trip back to a registered workspace to confirm the
      // shell recovers from the unknown selection without manual
      // refresh.
      await page.evaluate(() => {
        const state = window.re_frame.story.ui.state;
        const kw = window.cljs.core.keyword;
        state.swap_state_BANG_.call(
          null,
          state.select_workspace,
          kw('Workspace.counter', 'all-states'),
        );
      });
      const recovered = page.locator('main section[aria-label="Workspace :Workspace.counter/all-states"]');
      await recovered.waitFor({ state: 'visible', timeout: 10000 });
      await waitForValue(
        () => recovered.locator('[data-rf-story-variant-root]').count(),
        (count) => count >= 1,
        {
          timeoutMs: 10000,
          description: 'shell recovers from unknown-workspace selection by re-mounting a registered workspace',
        },
      );
    });

    await scenario(page, 'controls-nested-edit-by-path-rf2-57ikh', async () => {
      /*
       * Case-1 follow-on (rf2-57ikh) — Controls nested edit-by-path
       * feature-gate beyond the matrix bookkeeping baseline.
       *
       * The :story.counter-matrix/nested-controls variant carries a
       * nested :settings map ({:title "Nested title" :enabled? true})
       * against the parent story's schema. The controls panel infers
       * the argtype tree and emits the :map argtype as a `group-widget`
       * — a header row labelled "{ }" plus one nested row per child
       * key. Each top-level row carries data-controls-arg=":<key>",
       * each nested row carries data-controls-key=":<sub-key>".
       *
       * Beyond-mount surface assertions:
       *   - the nested :settings group renders with TWO child rows
       *     (data-controls-key=":title" + data-controls-key=
       *     ":enabled?") inside the parent's data-controls-arg=
       *     ":settings" container
       *   - editing the nested :title input writes through to
       *     [:cell-overrides variant-id :settings :title] (read via
       *     re_frame.story.ui.state) — proves the on-change-at-path
       *     handler builds the right deep path; the sibling
       *     :enabled? value at [:cell-overrides ... :settings
       *     :enabled?] stays untouched (preserves unrelated siblings
       *     invariant per Spec 007 §Nested controls)
       *   - editing the nested :enabled? checkbox writes through to
       *     [:cell-overrides variant-id :settings :enabled?] and the
       *     prior :title override survives — both nested edits coexist
       *   - the top-level :label override (a sibling of :settings)
       *     does not bleed into :settings — proves the deep-path
       *     write only touches the specified sub-tree
       */
      await primeHelpDismissed(page);
      await gotoStory(page, '/counter-with-stories/#/stories');

      // Earlier scenarios may have selected a workspace; explicitly
      // clear :selected-workspace so the next variant click drives
      // the canvas pane (the shell.cljs render-cond prefers
      // workspace-mounted over variant-selected).
      await page.evaluate(() => {
        const state = window.re_frame.story.ui.state;
        state.swap_state_BANG_.call(null, state.select_workspace, null);
      });

      // Navigate to the nested-controls variant.
      await clickVariant(page, '/nested-controls');
      await setMode(page, 'dev');
      await waitForCanvas(page, ':story.counter-matrix/nested-controls');

      const aside = page.getByRole('complementary');
      const settingsGroup = aside.locator('[data-controls-arg=":settings"]').first();
      await settingsGroup.waitFor({ state: 'visible', timeout: 5000 });

      // The :settings group MUST surface its two child keys via the
      // nested-row data-controls-key attribute.
      const titleRow = settingsGroup.locator('[data-controls-key=":title"]').first();
      const enabledRow = settingsGroup.locator('[data-controls-key=":enabled?"]').first();
      await titleRow.waitFor({ state: 'visible', timeout: 5000 });
      await enabledRow.waitFor({ state: 'visible', timeout: 5000 });

      // Helper: read [:cell-overrides variant-id] off the shell-
      // state ratom. Returns a plain JS-friendly summary of the
      // nested map so we can verify structural updates without
      // having to walk arbitrary cljs collections.
      const readCellOverrides = () =>
        page.evaluate(() => {
          const state = window.re_frame.story.ui.state;
          const cur = state.get_state.call(null);
          const cljs = window.cljs.core;
          const kw = cljs.keyword;
          const getIn = cljs.get_in;
          const co = getIn.call(null, cur, cljs.PersistentVector.fromArray(
            [kw('cell-overrides'), kw('story.counter-matrix', 'nested-controls')],
            true,
          ));
          if (co == null) return { hasOverrides: false };
          const title = getIn.call(null, co, cljs.PersistentVector.fromArray(
            [kw('settings'), kw('title')],
            true,
          ));
          const enabled = getIn.call(null, co, cljs.PersistentVector.fromArray(
            [kw('settings'), kw('enabled?')],
            true,
          ));
          const label = getIn.call(null, co, cljs.PersistentVector.fromArray(
            [kw('label')],
            true,
          ));
          // CLJS booleans/strings pass through; nil/undefined return as null
          return { hasOverrides: true, title, enabled, label };
        });

      // Edit the nested :title input — find the <input> inside the
      // title row and fill it.
      const titleInput = titleRow.locator('input[type="text"]').first();
      await titleInput.waitFor({ state: 'visible', timeout: 5000 });
      await titleInput.fill('Edited nested title');
      await waitForValue(
        async () => {
          const overrides = await readCellOverrides();
          return overrides && overrides.title;
        },
        (value) => value === 'Edited nested title',
        {
          timeoutMs: 5000,
          description: 'nested :title edit writes through to [:cell-overrides _ :settings :title]',
        },
      );
      // The sibling :enabled? slot stays untouched — the deep-path
      // write only mutated the :title key, not the whole :settings
      // map (preserves unrelated siblings).
      let overrides = await readCellOverrides();
      if (overrides.enabled !== null && overrides.enabled !== undefined) {
        throw new Error(
          `expected :enabled? sibling to remain unwritten after :title edit; ` +
            `observed override value ${JSON.stringify(overrides.enabled)}`,
        );
      }

      // Now edit the nested :enabled? checkbox — toggling it should
      // add a second sub-key override under [:settings] without
      // clobbering the :title we just wrote.
      const enabledCheckbox = enabledRow.locator('input[type="checkbox"]').first();
      await enabledCheckbox.waitFor({ state: 'visible', timeout: 5000 });
      // The seed value is true; click to toggle off. Use .click()
      // rather than .uncheck() — Reagent's controlled checkbox
      // re-renders through its :on-change handler, so the input's
      // observed `checked` state lags one render tick behind the
      // ratom write. Playwright's uncheck() asserts the post-click
      // state equals false synchronously; click() doesn't.
      await enabledCheckbox.click();
      await waitForValue(
        async () => {
          const o = await readCellOverrides();
          return o && o.enabled;
        },
        (value) => value === false,
        {
          timeoutMs: 5000,
          description: 'nested :enabled? edit writes through to [:cell-overrides _ :settings :enabled?]',
        },
      );
      overrides = await readCellOverrides();
      if (overrides.title !== 'Edited nested title') {
        throw new Error(
          `:title override regressed after :enabled? edit; expected "Edited nested title", got ${JSON.stringify(overrides.title)}`,
        );
      }

      // Top-level :label sibling stays unwritten — the deep-path
      // writes only ever touched [:settings :*] paths, not the
      // sibling [:label] slot.
      if (overrides.label !== null && overrides.label !== undefined) {
        throw new Error(
          `expected top-level :label to remain unwritten after nested-only edits; ` +
            `observed override value ${JSON.stringify(overrides.label)}`,
        );
      }
    });

    await scenario(page, 'async-fx-machine-state-and-retry-rf2-ksw18', async () => {
      /*
       * Case-1 follow-on (rf2-ksw18) — Async/fx machine-state +
       * retry feature-gate beyond the matrix bookkeeping baseline.
       *
       * The login-form testbed (rf2-0sg12) registers five variants
       * that walk a deterministic FSM (:login/flow) through every
       * state from the Story tutorial:
       *
       *   :idle              :rf.assert/state-is :login/flow :idle
       *   :submitting        :rf.assert/state-is + :effect-emitted
       *                      :rf.http/managed (the force-fx-stub
       *                      records the request; the on-success/
       *                      on-failure callbacks never fire so the
       *                      machine locks in :submitting)
       *   :error             driven failure event lands the machine
       *                      in :error with the error message
       *                      surfaced under the form
       *   :submitting-retry  submit → failure → retry sequenced; the
       *                      :attempts counter has been bumped to 1;
       *                      the retry request is pending
       *   :authenticated     submit → success drives the machine
       *                      through to :authenticated; the form
       *                      swaps for the welcome banner
       *
       * Beyond-mount surface assertions:
       *   - Story shell on login-form mounts at /login-form/#/stories
       *   - each variant's :rf.assert/state-is + :rf.assert/path-
       *     equals + :rf.assert/effect-emitted assertions land green
       *     in test mode — proves the machine FSM steps through the
       *     declared states AND the async-fx stub intercepts the
       *     :rf.http/managed dispatch
       *   - the retry path's :attempts counter (read off [:rf/
       *     machines :login/flow :data :attempts] via the asserted
       *     :rf.assert/path-equals row) lands at exactly 1 — proves
       *     retry-flow ordering distinguishes retry from initial
       *     submit
       *   - cross-variant FSM isolation: switching from :error to
       *     :authenticated rebuilds the machine state on a fresh
       *     frame; the prior :error state does NOT bleed into the
       *     :authenticated variant's snapshot
       */
      // Login-form is a separate shadow build mounted at
      // /login-form/. Navigate there directly; gotoStory primes the
      // help-dismissed flag and waits for the three landmark
      // regions.
      await primeHelpDismissed(page);
      await gotoStory(page, '/login-form/#/stories');

      // (a) :submitting — async fx stubbed, machine locks in
      // :submitting; canvas state-pill reflects the terminal state
      // directly via the view's [data-test="login-state-pill"]
      // subscription. (Test mode reports 1-of-2 passing because
      // :rf.assert/effect-emitted's accumulator resets at play
      // start — the :rf.http/managed fx fired during phase-2 events,
      // not during phase-4 play; the canvas-side assertion is the
      // load-bearing surface for the async-fx machine-state
      // contract here.)
      await clickVariant(page, '/submitting');
      // The mode strip may rehydrate :test or :dev from localStorage;
      // we want :dev so the canvas mounts.
      await setMode(page, 'dev');
      await waitForCanvas(page, ':story.login/submitting');
      await waitForValue(
        () =>
          canvas(page, ':story.login/submitting')
            .locator('[data-test="login-state-pill"]')
            .innerText()
            .catch(() => ''),
        (text) => /state:\s*:submitting/i.test(text),
        {
          timeoutMs: 10000,
          description: ':submitting canvas state-pill reads "state: :submitting" (FSM locked by fx stub)',
        },
      );
      // The submit button reads "Signing in…" while the FSM is in
      // :submitting (the view's button-disabled-while-submitting
      // branch).
      const submitBtn = canvas(page, ':story.login/submitting').locator('[data-test="login-submit"]');
      await submitBtn.waitFor({ state: 'visible', timeout: 5000 });
      const submitText = (await submitBtn.innerText()).trim();
      if (!/signing in/i.test(submitText)) {
        throw new Error(
          `:submitting variant's submit button text expected "Signing in…", got "${submitText}"`,
        );
      }

      // (b) :error — machine drives idle → submitting → error and
      // surfaces the 401 error message under the form.
      await clickVariant(page, '/error');
      await waitForCanvas(page, ':story.login/error');
      // The state-pill in the canvas reads "state: :error" — the
      // FSM's terminal state is observable directly via the view's
      // [data-test="login-state-pill"].
      await waitForValue(
        () =>
          canvas(page, ':story.login/error')
            .locator('[data-test="login-state-pill"]')
            .innerText()
            .catch(() => ''),
        (text) => /state:\s*:error/i.test(text),
        {
          timeoutMs: 10000,
          description: ':error variant canvas state-pill reads "state: :error"',
        },
      );
      // The error message ("Invalid credentials.") is surfaced
      // under the form via [data-test="login-error"].
      await expectVisible(
        canvas(page, ':story.login/error').locator('[data-test="login-error"]'),
        5000,
      );

      // (c) :submitting-retry — submit → failure → retry sequence.
      // The FSM's :submitting-retry terminal state distinguishes
      // the retry path from the initial submit. Canvas state-pill
      // shows the terminal state.
      await clickVariant(page, '/submitting-retry');
      await waitForCanvas(page, ':story.login/submitting-retry');
      await waitForValue(
        () =>
          canvas(page, ':story.login/submitting-retry')
            .locator('[data-test="login-state-pill"]')
            .innerText()
            .catch(() => ''),
        (text) => /state:\s*:submitting-retry/i.test(text),
        {
          timeoutMs: 10000,
          description: ':submitting-retry canvas state-pill reads "state: :submitting-retry"',
        },
      );

      // (d) Cross-variant FSM isolation — switching to
      // :authenticated rebuilds the machine on a fresh frame; the
      // prior :error / :submitting-retry state does NOT bleed in.
      await clickVariant(page, '/authenticated');
      await waitForCanvas(page, ':story.login/authenticated');
      // The welcome banner replaces the form — proves the FSM
      // landed in :authenticated, not :error or :submitting-retry.
      await expectVisible(
        canvas(page, ':story.login/authenticated').locator('[data-test="login-welcome"]'),
        10000,
      );
      await waitForValue(
        () =>
          canvas(page, ':story.login/authenticated')
            .locator('[data-test="login-state-pill"]')
            .innerText()
            .catch(() => ''),
        (text) => /state:\s*:authenticated/i.test(text),
        {
          timeoutMs: 10000,
          description: ':authenticated canvas state-pill reads "state: :authenticated"',
        },
      );
    });

    await scenario(page, 'args-precedence-5-layer-chain-rf2-6fry8', async () => {
      /*
       * Case-1 follow-on (rf2-6fry8) — args-precedence 5-layer
       * chain feature-gate beyond the matrix bookkeeping baseline.
       *
       * Per Spec 007 §Args (and args.cljc resolve-args) the
       * precedence chain is:
       *
       *   global-args  <  mode-args  <  story-args  <  variant-args  <  controls-overrides
       *
       * Each layer DEEP-MERGES into the next; lower-precedence
       * keys survive when the higher-precedence layer doesn't
       * override them.
       *
       * The counter testbed makes every layer addressable:
       *   - global:    re-frame.story.config/set-global-args!
       *   - mode:      :Mode.app/dark sets {:theme :dark
       *                                     :background "#1e1e1e"
       *                                     :foreground "#e0e0e0"}
       *   - story:     :story.counter sets {:label "Count"}
       *   - variant:   :story.counter/loaded sets {:label "Total"}
       *   - controls:  controls panel writes [:cell-overrides
       *                ::variant-id :<arg-key>] via the args editor
       *
       * Beyond-mount surface assertions:
       *
       *   (a) Variant beats global+story: with global :label set to
       *       "GlobalLabel" and no controls override, /loaded canvas
       *       still shows "Total" (variant precedence wins).
       *
       *   (b) Global survives when no higher layer overrides:
       *       set global key :rf-test/global-only "G" — a key NO
       *       other layer touches; resolve-args returns the value
       *       intact on the variant's effective-args map (reads
       *       via re_frame.story.args/resolve-args).
       *
       *   (c) Mode beats global on a key the mode defines: set
       *       global :theme :red, activate :Mode.app/dark; the
       *       mode's :theme :dark wins (mode > global).
       *
       *   (d) Controls beats every other layer: with the variant
       *       declaring :label "Total" and global :label set, edit
       *       the controls input to "ControlsWins" — canvas re-
       *       renders to show "ControlsWins" and resolve-args
       *       reports the same.
       *
       *   (e) Reset controls override (the controls panel's "reset
       *       overrides" button) — canvas falls back to the
       *       variant's :label "Total", proving the controls layer
       *       didn't mutate any other layer.
       *
       * Source-side follow-on: the canvas-visible :label is the
       * load-bearing observable per layer; a deeper read of all 5
       * layers via the docs pane's args table would need an
       * argtypes-aware label-row but the current panel only shows
       * one row per top-level key (covered).
       */
      await primeHelpDismissed(page);
      await gotoStory(page, '/counter-with-stories/#/stories');

      // Clear any prior workspace selection so the variant click
      // actually drives the canvas pane (see notes in the controls-
      // nested scenario above).
      await page.evaluate(() => {
        const state = window.re_frame.story.ui.state;
        state.swap_state_BANG_.call(null, state.select_workspace, null);
      });

      // Helper: read resolved effective-args off the variant via
      // re_frame.story.args/resolve-args. The variant-id arg MUST
      // be a cljs keyword, not a JS string — resolve-args calls
      // (parent-story-id variant-id) which uses the keyword namespace
      // grammar. Mirror the canvas opts shape so the mode + cell-
      // overrides layers participate just like the rendered canvas.
      const readEffectiveArgs = ([variantNs, variantName]) =>
        page.evaluate((spec) => {
          const [ns, name] = spec;
          const args = window.re_frame.story.args;
          const state = window.re_frame.story.ui.state;
          const cljs = window.cljs.core;
          const kw = cljs.keyword;
          const vid = kw(ns, name);
          // Read the shell's active-modes + cell-overrides for this
          // variant — matches what canvas-inner passes to
          // resolve-args (rf2-wb4y3 hot-path threading).
          const shell = state.get_state.call(null);
          const get = cljs.get;
          const getIn = cljs.get_in;
          const activeModes = get.call(null, shell, kw('active-modes')) || cljs.PersistentVector.EMPTY;
          const cellOverrides = getIn.call(null, shell, cljs.PersistentVector.fromArray(
            [kw('cell-overrides'), vid],
            true,
          ));
          let opts = cljs.PersistentArrayMap.EMPTY;
          opts = cljs.assoc.call(null, opts, kw('active-modes'), activeModes);
          if (cellOverrides != null) {
            opts = cljs.assoc.call(null, opts, kw('cell-overrides'), cellOverrides);
          }
          const result = args.resolve_args.call(null, vid, opts);
          if (result == null) return null;
          return {
            label: get.call(null, result, kw('label')),
            theme: get.call(null, result, kw('theme')),
            background: get.call(null, result, kw('background')),
            globalOnly: get.call(null, result, kw('rf-test', 'global-only')),
          };
        }, [variantNs, variantName]);

      const setGlobal = (entries) =>
        page.evaluate((kvs) => {
          const cfg = window.re_frame.story.config;
          const cljs = window.cljs.core;
          const kw = cljs.keyword;
          let m = cljs.PersistentArrayMap.EMPTY;
          for (const [nsName, name, value] of kvs) {
            const key = nsName ? kw(nsName, name) : kw(name);
            // For value keywords, coerce.
            const v = (value && value.__kw)
              ? (value.ns ? kw(value.ns, value.name) : kw(value.name))
              : value;
            m = cljs.assoc.call(null, m, key, v);
          }
          cfg.set_global_args_BANG_.call(null, m);
        }, entries);

      const setActiveModes = (modeIds) =>
        page.evaluate((mids) => {
          const state = window.re_frame.story.ui.state;
          const cljs = window.cljs.core;
          const kw = cljs.keyword;
          const modes = cljs.PersistentVector.fromArray(
            mids.map(([nsName, name]) => kw(nsName, name)),
            true,
          );
          state.swap_state_BANG_.call(null, state.set_active_modes, modes);
        }, modeIds);

      // (a) Variant beats global+story.
      // First clear any prior global args + active modes from
      // earlier scenarios, then set global :label "GlobalLabel".
      await setGlobal([]);                       // clear globals
      await setActiveModes([]);                  // clear modes
      await setGlobal([[null, 'label', 'GlobalLabel']]);
      await clickVariant(page, '/loaded');
      await setMode(page, 'dev');
      await waitForCanvas(page, ':story.counter/loaded');
      await expectTextContains(canvas(page, ':story.counter/loaded'), 'Total', 5000);
      let eff = await readEffectiveArgs(['story.counter', 'loaded']);
      if (eff.label !== 'Total') {
        throw new Error(
          `(a) variant > global: expected :label "Total", got ${JSON.stringify(eff.label)}`,
        );
      }

      // (b) Global survives when no higher layer overrides it. Set
      // a namespaced key only in globals; resolve-args returns it
      // verbatim on every variant.
      await setGlobal([
        [null, 'label', 'GlobalLabel'],
        ['rf-test', 'global-only', 'fromGlobal'],
      ]);
      await waitForValue(
        async () => (await readEffectiveArgs(['story.counter', 'loaded'])).globalOnly,
        (v) => v === 'fromGlobal',
        {
          timeoutMs: 5000,
          description: '(b) global :rf-test/global-only survives into effective-args',
        },
      );

      // (c) Mode beats global on a key the mode defines. Set
      // global :background "FROM_GLOBAL", activate :Mode.app/dark
      // (which carries :background "#1e1e1e"). Mode wins.
      await setGlobal([
        [null, 'label', 'GlobalLabel'],
        ['rf-test', 'global-only', 'fromGlobal'],
        [null, 'background', 'FROM_GLOBAL'],
      ]);
      await setActiveModes([['Mode.app', 'dark']]);
      await waitForValue(
        async () => (await readEffectiveArgs(['story.counter', 'loaded'])).background,
        (v) => v === '#1e1e1e',
        {
          timeoutMs: 5000,
          description: '(c) mode :Mode.app/dark :background beats global :background',
        },
      );

      // (d) Controls beats every other layer. Edit the controls
      // input for :label and assert canvas re-renders.
      const aside = page.getByRole('complementary');
      const labelInput = aside.locator('[data-controls-arg=":label"] input[type="text"]').first();
      await labelInput.waitFor({ state: 'visible', timeout: 5000 });
      await labelInput.fill('ControlsWins');
      await expectTextContains(canvas(page, ':story.counter/loaded'), 'ControlsWins', 5000);
      await waitForValue(
        async () => (await readEffectiveArgs(['story.counter', 'loaded'])).label,
        (v) => v === 'ControlsWins',
        {
          timeoutMs: 5000,
          description: '(d) controls override :label "ControlsWins" wins over variant/story/mode/global',
        },
      );

      // (e) Reset controls override — canvas falls back to variant
      // :label "Total".
      const resetBtn = aside.getByRole('button', { name: /reset overrides/i }).first();
      await resetBtn.click();
      await expectTextContains(canvas(page, ':story.counter/loaded'), 'Total', 5000);
      eff = await readEffectiveArgs(['story.counter', 'loaded']);
      if (eff.label !== 'Total') {
        throw new Error(
          `(e) controls reset: expected :label "Total", got ${JSON.stringify(eff.label)}`,
        );
      }
      // Cleanup: clear globals + modes so downstream scenarios
      // (the terminal :kgn0c walk) see a fresh shell.
      await setGlobal([]);
      await setActiveModes([]);
    });

    await scenario(page, 'render-shell-mount-unmount-no-leak-rf2-3vvzy', async () => {
      /*
       * Case-1 follow-on (rf2-3vvzy) — Render shell mount/unmount
       * double-mount diagnostics beyond the matrix bookkeeping
       * baseline.
       *
       * Per Spec 008 §Render shell mount/unmount the shell exposes
       * `mount-shell!`, `unmount-shell!`, and `active-shell` —
       * v1 lifecycle is one-shell-at-a-time. The contract:
       *
       *   - calling mount-shell! while a shell is already mounted
       *     unmounts the previous root first (rf2-fq1yg fix); no
       *     React "container has already been passed to createRoot"
       *     warning; no duplicate landmarks
       *   - calling mount-shell! with nil / undefined returns nil
       *     and leaves the shell untouched (config-gate + nil
       *     dom-node guard)
       *   - active-shell returns the current handle after each
       *     mount-shell! call; returns nil after unmount-shell!
       *   - after unmount + remount, no duplicate panels/listeners
       *     survive (state/reset-shell-state! + teardown-all-
       *     listeners! purge the prior session's per-variant state)
       *
       * Beyond-mount surface assertions:
       *   (a) baseline: one `<nav>` landmark, one `<main>`, one
       *       `<aside>` (proves the live shell is the only mounted
       *       root before we start mucking with the singleton)
       *   (b) re-mount on top of an active shell: active-shell
       *       returns a NEW handle (different identity), the prior
       *       handle's root has been unmounted, the landmark count
       *       remains exactly one of each
       *   (c) nil-dom-node guard: mount-shell!(null) returns null,
       *       active-shell still returns the current handle from
       *       (b), landmark count unchanged
       *   (d) unmount → remount round-trip: after unmount the
       *       `<nav>` landmark is gone (DOM cleaned); after
       *       mount-shell! against #app the landmark reappears
       *       singleton'd; no listener leak (the variant scrubber
       *       slot returns nil for a freshly-allocated frame)
       */
      await primeHelpDismissed(page);
      await gotoStory(page, '/counter-with-stories/#/stories');

      // Clear any prior workspace selection so the landmarks are
      // stable.
      await page.evaluate(() => {
        const state = window.re_frame.story.ui.state;
        state.swap_state_BANG_.call(null, state.select_workspace, null);
      });

      const countLandmarks = () =>
        page.evaluate(() => ({
          nav: document.querySelectorAll('nav').length,
          main: document.querySelectorAll('main').length,
          aside: document.querySelectorAll('aside').length,
        }));

      // (a) Baseline: one of each landmark before any singleton
      // manipulation.
      let lm = await countLandmarks();
      if (lm.nav !== 1 || lm.main !== 1 || lm.aside !== 1) {
        throw new Error(
          `(a) baseline landmark count not 1/1/1: ${JSON.stringify(lm)}`,
        );
      }

      // (b) Re-mount on top of the active shell. active-shell
      // returns a new handle identity, prior handle is unmounted.
      const remount = await page.evaluate(() => {
        const shell = window.re_frame.story.ui.shell;
        const before = shell.active_shell.call(null);
        const node = document.getElementById('app');
        if (!node) return { ok: false, reason: 'no #app node' };
        const handle = shell.mount_shell_BANG_.call(null, node);
        const after = shell.active_shell.call(null);
        return {
          ok: true,
          beforeIdentity: before === after,
          handlePresent: !!handle,
          afterPresent: !!after,
        };
      });
      if (!remount.ok || !remount.handlePresent || !remount.afterPresent || remount.beforeIdentity) {
        throw new Error(
          `(b) re-mount did not produce a new singleton handle: ${JSON.stringify(remount)}`,
        );
      }
      lm = await countLandmarks();
      if (lm.nav !== 1 || lm.main !== 1 || lm.aside !== 1) {
        throw new Error(
          `(b) post-remount landmark count not 1/1/1 (root leak): ${JSON.stringify(lm)}`,
        );
      }

      // (c) Nil dom-node guard: mount-shell!(null) returns null,
      // singleton handle preserved, landmark count unchanged.
      const nilGuard = await page.evaluate(() => {
        const shell = window.re_frame.story.ui.shell;
        const before = shell.active_shell.call(null);
        const out = shell.mount_shell_BANG_.call(null, null);
        const after = shell.active_shell.call(null);
        return {
          returnedNull: out == null,
          singletonPreserved: before === after,
          stillHasHandle: !!after,
        };
      });
      if (!nilGuard.returnedNull || !nilGuard.singletonPreserved || !nilGuard.stillHasHandle) {
        throw new Error(
          `(c) nil dom-node guard mis-behaved: ${JSON.stringify(nilGuard)}`,
        );
      }
      lm = await countLandmarks();
      if (lm.nav !== 1 || lm.main !== 1 || lm.aside !== 1) {
        throw new Error(
          `(c) post-nil-guard landmark count not 1/1/1: ${JSON.stringify(lm)}`,
        );
      }

      // (d) unmount → remount round-trip. After unmount the
      // landmarks are gone; after remount they reappear singleton'd.
      // active-shell returns nil between the two.
      await page.evaluate(() => {
        const shell = window.re_frame.story.ui.shell;
        shell.unmount_shell_BANG_.call(null);
      });
      // React's unmount is async (it queues a microtask) — poll
      // until the nav landmark drops.
      await waitForValue(
        async () => (await countLandmarks()).nav,
        (n) => n === 0,
        { timeoutMs: 5000, description: 'post-unmount: <nav> landmark removed from DOM' },
      );
      const noHandle = await page.evaluate(() => {
        const shell = window.re_frame.story.ui.shell;
        return shell.active_shell.call(null) == null;
      });
      if (!noHandle) {
        throw new Error('(d) active-shell did not return nil after unmount-shell!');
      }

      // Remount.
      await page.evaluate(() => {
        const shell = window.re_frame.story.ui.shell;
        const node = document.getElementById('app');
        shell.mount_shell_BANG_.call(null, node);
      });
      await waitForValue(
        async () => (await countLandmarks()).nav,
        (n) => n === 1,
        { timeoutMs: 5000, description: 'post-remount: exactly one <nav> landmark restored' },
      );
      lm = await countLandmarks();
      if (lm.nav !== 1 || lm.main !== 1 || lm.aside !== 1) {
        throw new Error(
          `(d) post-remount landmark count not 1/1/1 (root leak after round-trip): ${JSON.stringify(lm)}`,
        );
      }
      // Listener leak guard: the variant scrubber slot for any
      // variant should be nil since the shell was just freshly
      // mounted and no variant has been selected on this session.
      const scrubberSelections = await page.evaluate(() => {
        // re-frame.story.ui.scrubber maintains a per-variant
        // selection atom; after an unmount the listeners-teardown
        // wipe should have left it empty. We expose a public read
        // via the registered shell-state — no selection at this
        // point means no per-variant scrubber-selection slots
        // remain.
        const state = window.re_frame.story.ui.state;
        const cur = state.get_state.call(null);
        const cljs = window.cljs.core;
        const kw = cljs.keyword;
        // Confirm the default-shell-state shape: no selected variant,
        // no selected workspace, no panel-visibility entries beyond
        // the defaults.
        return {
          variant: cljs.get.call(null, cur, kw('selected-variant')),
          workspace: cljs.get.call(null, cur, kw('selected-workspace')),
        };
      });
      if (scrubberSelections.variant != null || scrubberSelections.workspace != null) {
        throw new Error(
          `(d) post-remount shell state did not reset cleanly: ${JSON.stringify(scrubberSelections)}`,
        );
      }
      // Re-prime help dismissal so downstream scenarios don't trip
      // on the on-boarding dialog.
      await primeHelpDismissed(page);
    });

    await scenario(page, 'workspace-switch-no-stale-subscribe-derefs-rf2-kgn0c', async () => {
      /*
       * Regression gate for rf2-kgn0c. Pre-fix, clicking from one
       * workspace to another within the same browser session let
       * React's reconciler reuse the prior workspace's variant-cell
       * components when keys collided (`(str "v-" i)` was position-
       * only); the cell's `r/with-let` initialiser ran once with the
       * OLD variant id, the NEW variant's frame was never allocated
       * by `run-variant-with-shell-opts!`, and the rendered view's
       * subscribe returned nil — `@nil` then threw
       * `No protocol method IDeref.-deref defined for type null`.
       *
       * The fix keys variant cells on the variant id. This walkthrough
       * clicks through every workspace in the counter testbed in a
       * single browser session — NO fresh page per workspace. Any
       * stale-subscribe-deref would surface as a pageerror, and the
       * spec-level pageerror gate would fail the run.
       *
       * Last scenario in the file so the post-walk shell state (a
       * selected workspace, no selected variant) does not bleed into
       * the next scenario's preconditions.
       */
      await primeHelpDismissed(page);
      await gotoStory(page, '/counter-with-stories/#/stories');

      const workspaceNames = [
        'Workspace.counter/all-states',
        'Workspace.counter/auto-grid',
        'Workspace.counter/tabs',
        'Workspace.counter/all-states', // round-trip
        'Workspace.counter/auto-grid',  // round-trip
      ];

      for (const name of workspaceNames) {
        const row = page.getByRole('navigation').getByText(name, { exact: false }).first();
        await row.waitFor({ state: 'visible', timeout: 10000 });
        await row.click();
        // Wait for the workspace's <section> landmark to land — its
        // aria-label embeds the workspace id, so we anchor on that.
        const ws = page.locator(`main section[aria-label="Workspace :${name}"]`);
        await ws.waitFor({ state: 'visible', timeout: 10000 });
        // Workspace MUST render at least one variant root after the
        // swap. The bug pre-fix left the new workspace blank /
        // partially rendered around the throw.
        await waitForValue(
          () => ws.locator('[data-rf-story-variant-root]').count(),
          (count) => count >= 1,
          { timeoutMs: 10000, description: `${name} mounts at least one variant root` },
        );
      }
    });
  },
};
