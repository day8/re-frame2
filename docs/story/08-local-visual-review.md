# Local visual review

Story does not compare pixels, but it gives each variant a stable identity a
screenshot tool can key on ([Content identity](08-snapshot-identity-and-sharing.md#content-identity)).
This recipe reviews your variants' pixels with Playwright's
`toHaveScreenshot`, run against your dev server. It is local, not CI: run it
before you push, or after a change you want to see. The baselines live
outside the repository.

The cases are your `:test`-tagged variants, each under every mode registered on
the `:theme` axis. The sample reads both from the running page, so a new
variant joins the review without an edit. Each case is keyed by snapshot
identity, the variant id and its theme, plus the viewport and browser of the
capture: the error state in dark mode is
`story-login-form-error--dark--1280x800--chromium`. The capture is the variant's
own subtree, so Story's chrome never enters a baseline.

The content hash is not part of the key. It is recorded beside each baseline,
so a review can tell you whether a diff arrived with a declared change or
without one. Besides the variant's own body, the hash covers:

- the render inputs it receives from a fragment it composes, or from an
  ancestor it inherits through `:extends`;
- the `:fx-overrides` and `:interceptor-overrides` a run installs for it,
  whether they sit on its own body or on one of those;
- the behaviour `:images` it or its story declares. An ancestor's `:images`
  never reach the variants that extend it, so they are not covered.

So editing a shared fragment, or a parent's seed, setup, network stubs or
decorators, pointing an override at a different handler or interceptor, or
selecting a different behaviour image moves the hash of every variant the edit
reaches, and a review reports those hashes as moved even where the pixels
match. A moved hash on its own fails no case and asks for no new baseline:
baselines are keyed by variant, and only a pixel change needs your approval.

Put two files in a `story-visual/` directory in your app.

**1. `story-visual/playwright.config.cjs`** sends baselines and diff images to
the directory named by `STORY_VISUAL_DIR`, and refuses to run without one:

```js
const path = require('path');

// Baselines and diff images go to a directory OUTSIDE the repository.
const dir = process.env.STORY_VISUAL_DIR;
if (!dir) throw new Error('Set STORY_VISUAL_DIR to a directory outside the repository.');

module.exports = {
  testDir: __dirname,
  testMatch: 'visual-review.pw.cjs',
  snapshotPathTemplate: path.resolve(dir, 'baselines', '{arg}{ext}'),
  outputDir: path.resolve(dir, 'results'),
  updateSnapshots: 'none', // a plain run never writes a baseline; only -u does
  workers: 1,
  timeout: 5 * 60 * 1000, // one test walks every case
  expect: {
    timeout: 30 * 1000,
    toHaveScreenshot: { threshold: 0 }, // the 0.2 default passed a real colour change
  },
  use: { browserName: 'chromium', viewport: { width: 1280, height: 800 } },
};
```

**2. `story-visual/visual-review.pw.cjs`** walks the cases:

```js
const fs = require('fs');
const path = require('path');
const { test, expect } = require('@playwright/test');

const BASE = process.env.STORY_URL || 'http://localhost:8043/';

test('visual review of the :test-tagged variants', async ({ page, browserName }) => {
  // Keep Story's first-visit help overlay off every capture.
  await page.addInitScript(() => localStorage.setItem('re-frame.story/seen-help-v1', '1'));
  await page.goto(`${BASE}#/stories`);
  await page.waitForFunction(() => window.re_frame?.story?.registrations
    && cljs.core.count(re_frame.story.registrations(cljs.core.keyword('variant'))) > 0);

  // Ask the running page, through the dev build's globals, for the cases.
  const { variants, themes } = await page.evaluate(() => {
    const story = re_frame.story;
    const kw = cljs.core.keyword;
    const ids = (ks) => Array.from(cljs.core.to_array(ks)).map((k) => k.fqn).sort();
    const themeModes = Array.from(cljs.core.to_array(story.registrations(kw('mode'))))
      .filter((e) => cljs.core.get(cljs.core.val(e), kw('axis'))?.fqn === 'theme');
    return {
      variants: ids(story.variants_with_tags(cljs.core.vector(kw('test')))),
      themes: ids(themeModes.map((e) => cljs.core.key(e))),
    };
  });
  expect(variants.length, 'no :test-tagged variants to review').toBeGreaterThan(0);

  const { width, height } = page.viewportSize();
  const approving = ['all', 'changed'].includes(test.info().config.updateSnapshots);
  for (const variant of variants) {
    for (const theme of themes.length ? themes : [null]) {
      // The case key: variant id, theme, viewport and browser.
      const name = [variant, theme ? theme.split('/').pop() : 'no-theme', `${width}x${height}`, browserName]
        .join('--').replace(/[^a-zA-Z0-9-]+/g, '-'); // the spelling Playwright writes to disk
      const query = new URLSearchParams(theme ? { variant, modes: theme } : { variant });
      await page.goto(`${BASE}?${query}#/stories`);

      // Settle on the variant's run reaching its verdict, not on a sleep. A run
      // settles whether or not the variant has a play that runs on its own.
      const canvas = page.locator(`section[data-test-variant=":${variant}"]`);
      await expect(canvas).toHaveAttribute('data-run-status', /^(pass|fail|cannot-run|error)$/);
      await expect(canvas).toHaveAttribute('data-snapshot-hash', /^[0-9a-f]+$/);
      const hash = await canvas.getAttribute('data-snapshot-hash');

      // Capture and compare EVERY case on every run, whatever the hash says.
      const errorsBefore = test.info().errors.length;
      await expect.soft(page.locator(`[data-rf-story-variant-root=":${variant}"]`))
        .toHaveScreenshot(`${name}.png`);
      const pixels = test.info().errors.length > errorsBefore ? 'DIFFER' : 'match';

      // The content hash is provenance: recorded on approval, reported on review.
      const sidecar = test.info().snapshotPath(`${name}.hash.json`);
      if (approving) {
        fs.mkdirSync(path.dirname(sidecar), { recursive: true });
        fs.writeFileSync(sidecar, JSON.stringify({ hash }));
        console.log(`${name}: approved at hash ${hash}`);
      } else {
        const approved = fs.existsSync(sidecar) ? JSON.parse(fs.readFileSync(sidecar, 'utf8')).hash : null;
        console.log(`${name}: pixels ${pixels}, hash ${hash === approved ? 'unchanged' : `moved from ${approved}`}`);
      }
    }
  }
});
```

`STORY_URL` points it at your dev server's page; the default is the login-form
testbed from [chapter 1](01-first-variant.md).

With `@playwright/test` installed (`npm install --save-dev @playwright/test`,
then `npx playwright install chromium`) and your dev server running, approve the
first baselines from your app's root:

```bash
STORY_VISUAL_DIR=../my-app-visual npx playwright test -c story-visual/playwright.config.cjs -u
```

After that, review with the same command and no `-u`:

```bash
STORY_VISUAL_DIR=../my-app-visual npx playwright test -c story-visual/playwright.config.cjs
```

Each case prints a line such as
`story-login-form-error--dark--1280x800--chromium: pixels match, hash unchanged`.
A case whose pixels differ fails the run, and Playwright writes the expected,
actual and diff images under `../my-app-visual/results`. When the diff is the
change you meant, run the command with `-u` again to approve it.

The sample follows three rules:

- **Never skip a capture because the content hash is unchanged.** The hash
  covers declarations, not CSS, fonts or the view's implementation, so a
  CSS-only change moves pixels and leaves every hash as it was. The sample
  captures and compares every case on every run, and the hash only reports.
- **Pin `threshold: 0`.** Playwright's default per-pixel colour tolerance,
  0.2, is loose enough to pass a real change of heading colour. At 0 that
  change fails, and unchanged captures still compare clean.
- **Approve new baselines only with `-u`.** `updateSnapshots: 'none'` stops a
  plain run from quietly writing a missing baseline, so every baseline, and the
  hash beside it, is one you approved.

No gate runs this sample, so if Story changes one of the things it leans on,
nothing turns red until you run it. It depends on:

- the dev build's JavaScript globals for `re-frame.story` (`registrations` and
  `variants-with-tags`), so it needs a watch build, not the `:advanced`
  [static build](08-static-builds.md);
- Story's DOM test hooks: the canvas's `data-test-variant`, `data-snapshot-hash`
  and `data-run-status`, which carries the verdict of the variant's run once it
  settles, whether or not the variant has a play that runs on its own; and
  `data-rf-story-variant-root` on the variant's subtree;
- the [share URL](08-snapshot-identity-and-sharing.md#sharing)'s `variant` and `modes` parameters;
- the help overlay's `re-frame.story/seen-help-v1` localStorage key.

