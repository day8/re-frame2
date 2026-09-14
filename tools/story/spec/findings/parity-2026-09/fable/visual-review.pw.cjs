'use strict';

/* global cljs, re_frame */

/*
 * Local visual review over Story's :test-tagged variants (rf2-ia2if experiment).
 *
 * One `toHaveScreenshot` per case `<variant-id>--<theme>--<viewport>--<browser>`,
 * taken on the variant root after the variant's play reaches a terminal status.
 * Variants come from the registrar (every :test-tagged variant) and themes from
 * the registered modes on the `:theme` axis, so a new variant joins without an
 * edit here.
 *
 * THE ONE RULE: every case is captured and compared on every run. The
 * snapshot-identity content hash is the case's PROVENANCE: it is printed, and
 * compared with the sidecar written at approval, and it is never a reason to
 * skip a capture. It does not see CSS, fonts or the view implementation, so an
 * unchanged hash says nothing about pixels.
 */

const fs = require('fs');
const path = require('path');

const IMPLEMENTATION = path.resolve(__dirname, '..', '..', '..', '..', '..', '..', 'implementation');
const { test, expect } = require(require.resolve('playwright/test', { paths: [IMPLEMENTATION] }));

const BASE = process.env.STORY_URL || 'http://localhost:8043/index.html';
const WAIT_MS = 30000;

test('visual review of the :test-tagged variants', async ({ page, browserName }) => {
  const pageErrors = [];
  page.on('pageerror', (e) => pageErrors.push(String(e)));
  // Pre-dismiss the first-visit help overlay so it never sits over a capture.
  await page.addInitScript(() => localStorage.setItem('re-frame.story/seen-help-v1', '1'));

  await page.goto(`${BASE}#/stories`, { waitUntil: 'load', timeout: WAIT_MS });
  await page.waitForFunction(
    () => window.re_frame?.story?.registrations
      && cljs.core.count(re_frame.story.registrations(cljs.core.keyword('variant'))) > 0,
    null, { timeout: WAIT_MS });
  const { variants, themes } = await page.evaluate(() => {
    const story = re_frame.story; // the public re-frame.story query API, via the dev build's globals
    const kw = cljs.core.keyword;
    const fqns = (ks) => Array.from(cljs.core.to_array(ks)).map((k) => k.fqn).sort();
    const themeModes = Array.from(cljs.core.to_array(story.registrations(kw('mode'))))
      .filter((e) => cljs.core.get(cljs.core.val(e), kw('axis'))?.fqn === 'theme')
      .map((e) => cljs.core.key(e));
    return { variants: fqns(story.variants_with_tags(cljs.core.vector(kw('test')))), themes: fqns(themeModes) };
  });
  console.log(`VISUAL enumerated ${variants.length} :test-tagged variants x themes [${themes.join(', ')}]`);
  expect(variants.length, 'no :test-tagged variants, so nothing was reviewed').toBeGreaterThan(0);

  const { width, height } = page.viewportSize();
  const updating = ['all', 'changed'].includes(test.info().config.updateSnapshots);
  for (const variant of variants) {
    for (const theme of (themes.length ? themes : [null])) {
      const name = [variant, theme ? theme.split('/').pop() : 'no-theme', `${width}x${height}`, browserName]
        .join('--').replace(/[^a-zA-Z0-9-]+/g, '-'); // the spelling Playwright writes to disk
      const started = Date.now();
      const query = new URLSearchParams(theme ? { variant, modes: theme } : { variant });
      await page.goto(`${BASE}?${query}#/stories`, { waitUntil: 'load', timeout: WAIT_MS });

      // Settle on the play's terminal status, not on a sleep.
      const chip = page.locator(`[data-test="story-play-status"][data-variant=":${variant}"]`);
      await expect(chip).toHaveAttribute('data-status', /^(pass|fail|cannot-run)$/, { timeout: WAIT_MS });
      const canvas = page.locator(`section[data-test-variant=":${variant}"]`);
      await expect(canvas).toHaveAttribute('data-snapshot-hash', /^[0-9a-f]+$/, { timeout: WAIT_MS });
      const hash = await canvas.getAttribute('data-snapshot-hash');

      const errorsBefore = test.info().errors.length;
      await expect.soft(page.locator(`[data-rf-story-variant-root=":${variant}"]`))
        .toHaveScreenshot(`${name}.png`, { timeout: WAIT_MS });
      const pixelsDiffer = test.info().errors.length > errorsBefore;

      const sidecar = test.info().snapshotPath(`${name}.hash.json`);
      const approved = fs.existsSync(sidecar) ? JSON.parse(fs.readFileSync(sidecar, 'utf8')).hash : null;
      if (updating) {
        fs.mkdirSync(path.dirname(sidecar), { recursive: true });
        fs.writeFileSync(sidecar, `${JSON.stringify({ variant, theme, viewport: `${width}x${height}`, browser: browserName, hash })}\n`);
      }
      console.log(`VISUAL ${JSON.stringify({
        name, play: await chip.getAttribute('data-status'), hash, approvedHash: approved,
        hashMoved: approved !== null && approved !== hash, pixelsDiffer, ms: Date.now() - started,
      })}`);
    }
  }
  expect(pageErrors, 'uncaught page errors').toEqual([]);
});
