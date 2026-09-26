#!/usr/bin/env node
/*
 * Story tutorial screenshot generator.
 *
 * Run after compiling the Story testbed bundles:
 *
 *   cd implementation
 *   npm install
 *   npx shadow-cljs compile :examples/login-form \
                             :examples/counter-with-stories \
                             :examples/nine-states-with-stories
 *   cd ..
 *   node docs/scripts/generate-story-tutorial-screenshots.cjs
 *
 * Name one or more output files to capture only those shots:
 *
 *   node docs/scripts/generate-story-tutorial-screenshots.cjs \
 *     story-tutorial-09-failing-run.png
 *
 * The script serves the compiled bundles from implementation/out and captures
 * the current Story shell with Playwright. The generated PNGs are committed so
 * the MkDocs site can render without needing Playwright at build time.
 *
 * Annotations reuse the Xray tutorial's overlay: `resolveRegion` and
 * `inPageAnnotateSvg` from `generate-tutorial-screenshots.cjs`. The regions
 * for each shot live in `story-tutorial-annotation-spec.json`, keyed by the
 * shot's file name. The tutorial's prose refers to the numbers in the labels,
 * so a region that does not resolve fails the run instead of shipping an
 * image without its marker.
 */

const fs = require('fs');
const http = require('http');
const path = require('path');

const REPO_ROOT = path.resolve(__dirname, '..', '..');
const IMPL_ROOT = path.join(REPO_ROOT, 'implementation');
const OUT_STORY = path.join(REPO_ROOT, 'docs', 'images', 'story');
const ANNOTATION_SPEC = path.join(__dirname, 'story-tutorial-annotation-spec.json');

const { chromium } = require(require.resolve('playwright', { paths: [IMPL_ROOT] }));
const {
  loadAnnotationSpec,
  resolveRegion,
  inPageAnnotateSvg,
  inPageClearAnnotations,
} = require('./generate-tutorial-screenshots.cjs');

const VIEWPORT = { width: 1440, height: 900 };

/*
 * The two ceilings `captureShot` names.
 *
 * `page.goto(url, { waitUntil: 'networkidle' })` and `locator.waitFor(...)`
 * both apply Playwright's 30s default when no `timeout:` is passed. Left
 * unnamed, a stall would print `Timeout 30000ms exceeded` without saying
 * which of the two it was — or that a NAVIGATION, rather than a missing
 * element, is what failed. The sibling generator beside this one
 * (`generate-tutorial-screenshots.cjs`) passes `{ waitUntil: 'load',
 * timeout: 30000 }` and an explicit locator timeout.
 *
 * `networkidle` is deliberate. A screenshot generator wants a
 * SETTLED page — that is the artefact, and the PNGs it writes are committed.
 * `'load'` or `'commit'` would shoot a page whose bundle may still be
 * mounting, quietly changing the images this repo ships. So the event stays
 * and only the number is named.
 *
 * The number is 60s rather than the sibling's 30s, because `networkidle`
 * settles strictly LATER than the `load` that sibling waits for; 30s here
 * would re-enact the default. Against a page whose subresource is held 35s,
 * a bare `goto{waitUntil:'networkidle'}` dies at the 30s default, and the
 * same call with `timeout: 60000` resolves.
 */
const NAV_TIMEOUT_MS = 60000;
const SHOT_VISIBLE_TIMEOUT_MS = 30000;

const APPS = {
  '/login': {
    html: path.join(REPO_ROOT, 'tools', 'story', 'testbeds', 'login_form', 'index.html'),
    out: path.join(IMPL_ROOT, 'out', 'examples', 'login-form'),
  },
  '/counter': {
    html: path.join(REPO_ROOT, 'tools', 'story', 'testbeds', 'counter_with_stories', 'index.html'),
    out: path.join(IMPL_ROOT, 'out', 'examples', 'counter-with-stories'),
  },
  '/nine': {
    html: path.join(REPO_ROOT, 'examples', 'patterns', 'nine_states', 'index.html'),
    out: path.join(IMPL_ROOT, 'out', 'examples', 'nine-states-with-stories'),
  },
};

/*
 * Chapter 6's failure path. The login testbed has no failing variant, so this
 * registers the chapter's `wrong-expectation` variant in the running page,
 * selects it, opens the Tests tab and follows the failed row's
 * "open in Evidence →" link. The shots that use it wait for the sidebar rather
 * than the canvas: Story remembers each variant's last mode tab in
 * localStorage, so `:story.login-form/error` may open in Docs, where the
 * canvas is not shown.
 */
async function openFailingRun(page) {
  await page.evaluate(() => {
    const body = cljs.reader.read_string(
      '{:extends :story.login-form/error ' +
        ':script [[:assert [:rf.assert/state-is :login/flow :idle]]] ' +
        ':tags #{:dev :test}}'
    );
    re_frame.story.reg_variant_STAR_(cljs.core.keyword('story.login-form', 'wrong-expectation'), body);
  });
  const row = page.locator(
    '[data-test="story-sidebar-variant-row"][data-variant=":story.login-form/wrong-expectation"]'
  );
  await row.waitFor({ state: 'visible', timeout: SHOT_VISIBLE_TIMEOUT_MS });
  await row.click();
  await page.getByText('Tests', { exact: true }).click();
  await page
    .locator('[data-test="story-test-row"][data-status="fail"]')
    .first()
    .waitFor({ state: 'visible', timeout: SHOT_VISIBLE_TIMEOUT_MS });
  await page.locator('[data-test="story-test-row-evidence-link"]').first().click();
  await page
    .locator('[data-test="story-evidence-beat"][data-selected="true"]')
    .first()
    .waitFor({ state: 'visible', timeout: SHOT_VISIBLE_TIMEOUT_MS });
}

const SHOTS = [
  {
    file: 'story-tutorial-00-shell.png',
    app: '/login',
    query: '?variant=story.login-form%2Fauthenticated',
    hash: '#/stories',
    waitFor: '[data-test="login-welcome"]',
  },
  {
    file: 'story-tutorial-01-first-variant.png',
    app: '/login',
    query: '?variant=story.login-form%2Fidle',
    hash: '#/stories',
    waitFor: '[data-test="login-form"]',
  },
  {
    file: 'story-tutorial-02-workspace-grid.png',
    app: '/login',
    query: '?workspace=Workspace.login-form%2Fall-states',
    hash: '#/stories',
    waitFor: '[data-test="login-error"]',
  },
  {
    file: 'story-tutorial-03-controls-and-fidelity.png',
    app: '/login',
    query: '?variant=story.login-form%2Ferror',
    hash: '#/stories',
    waitFor: '[data-test="story-view-state-section"]',
    before: async (page) => {
      await page.locator('[data-test="story-view-state-section"]').scrollIntoViewIfNeeded();
    },
  },
  {
    file: 'story-tutorial-04-test-mode.png',
    app: '/login',
    query: '?variant=story.login-form%2Ferror',
    hash: '#/stories',
    waitFor: '[data-test="story-mode-tabs"]',
    before: async (page) => {
      await page.getByText('Tests', { exact: true }).click();
      await page.locator('[data-test="story-test-view"]').waitFor({ state: 'visible' });
    },
  },
  {
    file: 'story-tutorial-05-docs-mode.png',
    app: '/login',
    query: '?variant=story.login-form%2Ferror',
    hash: '#/stories',
    waitFor: '[data-test="story-mode-tabs"]',
    before: async (page) => {
      await page.getByText('Docs', { exact: true }).click();
      await page.locator('[data-test="story-docs-view"]').waitFor({ state: 'visible' });
    },
  },
  {
    file: 'story-tutorial-06-share-dialog.png',
    app: '/login',
    query: '?variant=story.login-form%2Ferror',
    hash: '#/stories',
    waitFor: '[data-test="story-toolbar-share"]',
    before: async (page) => {
      await page.locator('[data-test="story-toolbar-share"]').click();
      await page.locator('[data-test="story-share-export-dialog"]').waitFor({ state: 'visible' });
    },
  },
  {
    // The end of chapter 6's failure path: the setup beat's "Xray: App-db"
    // link switches the rail's Xray panel to App-db at that beat's epoch.
    file: 'story-tutorial-07-xray-embed.png',
    app: '/login',
    query: '?variant=story.login-form%2Ferror',
    hash: '#/stories',
    waitFor: '[data-test="story-sidebar"]',
    before: async (page) => {
      await openFailingRun(page);
      await page
        .locator('[data-test="story-evidence-beat"][data-beat-idx="1"] ' +
                 '[data-test="story-evidence-focus-link"][data-panel="app-db"]')
        .click();
      await page
        .locator('[data-test="story-xray-panel-chip"][aria-pressed="true"]', { hasText: 'App-db' })
        .waitFor({ state: 'attached', timeout: SHOT_VISIBLE_TIMEOUT_MS });
      await page.evaluate(() => {
        document.querySelector('[data-rf-rhs-section="xray"]').scrollIntoView({ block: 'start' });
      });
    },
  },
  {
    file: 'story-tutorial-08-nine-states.png',
    app: '/nine',
    query: '?workspace=Workspace.nine-states%2Fall-states',
    hash: '#/stories',
    waitFor: '[data-test="story-sidebar"]',
  },
  {
    // Chapter 6's failure path: the failed row in the Tests tab, and the
    // Evidence panel it opens on the failing beat.
    file: 'story-tutorial-09-failing-run.png',
    app: '/login',
    query: '?variant=story.login-form%2Ferror',
    hash: '#/stories',
    waitFor: '[data-test="story-sidebar"]',
    before: openFailingRun,
  },
];

function contentType(filePath) {
  if (filePath.endsWith('.js')) return 'application/javascript';
  if (filePath.endsWith('.css')) return 'text/css';
  if (filePath.endsWith('.svg')) return 'image/svg+xml';
  if (filePath.endsWith('.png')) return 'image/png';
  if (filePath.endsWith('.map')) return 'application/json';
  return 'text/html';
}

function assertCompiled() {
  for (const { out } of Object.values(APPS)) {
    if (!fs.existsSync(path.join(out, 'main.js'))) {
      throw new Error(`Missing compiled Story bundle: ${path.join(out, 'main.js')}`);
    }
  }
}

function makeServer() {
  return http.createServer((req, res) => {
    try {
      const url = new URL(req.url, 'http://127.0.0.1');
      const base = Object.keys(APPS).find((candidate) =>
        url.pathname === candidate || url.pathname.startsWith(candidate + '/')
      );

      if (!base) {
        res.writeHead(404);
        res.end('No Story testbed for this path.');
        return;
      }

      const app = APPS[base];
      let rel = url.pathname.slice(base.length);
      if (rel === '' || rel === '/' || rel === '/index.html') {
        res.writeHead(200, { 'content-type': 'text/html' });
        res.end(fs.readFileSync(app.html));
        return;
      }

      rel = decodeURIComponent(rel.replace(/^\//, ''));
      const filePath = path.normalize(path.join(app.out, rel));
      if (!filePath.startsWith(app.out)) {
        res.writeHead(403);
        res.end('Bad path.');
        return;
      }
      if (!fs.existsSync(filePath)) {
        res.writeHead(404);
        res.end(`Missing file: ${rel}`);
        return;
      }

      res.writeHead(200, { 'content-type': contentType(filePath) });
      fs.createReadStream(filePath).pipe(res);
    } catch (err) {
      res.writeHead(500);
      res.end(String(err.stack || err));
    }
  });
}

async function withServer(fn) {
  const server = makeServer();
  await new Promise((resolve) => server.listen(0, '127.0.0.1', resolve));
  const port = server.address().port;
  try {
    await fn(`http://127.0.0.1:${port}`);
  } finally {
    await new Promise((resolve) => server.close(resolve));
  }
}

async function captureShot(page, baseUrl, shot, annotations) {
  const url = `${baseUrl}${shot.app}/${shot.query || ''}${shot.hash || ''}`;
  try {
    await page.goto(url, { waitUntil: 'networkidle', timeout: NAV_TIMEOUT_MS });
  } catch (err) {
    throw new Error(
      `NAVIGATION FAILED for ${shot.file} — this is the page.goto ceiling ` +
        `(waitUntil: 'networkidle', timeout: ${NAV_TIMEOUT_MS}ms), NOT the ` +
        `${SHOT_VISIBLE_TIMEOUT_MS}ms wait for \`${shot.waitFor}\`, which had ` +
        `not yet started. The page never settled, so no screenshot was taken ` +
        `and the committed PNG is unchanged. Underlying: ` +
        `${err.message}`
    );
  }
  await page
    .locator(shot.waitFor)
    .first()
    .waitFor({ state: 'visible', timeout: SHOT_VISIBLE_TIMEOUT_MS });
  if (shot.before) {
    await shot.before(page);
  }
  await page.waitForTimeout(700);
  // Focusing a tab or link can scroll the document itself by a few pixels,
  // which clips the toolbar. The shell's panes scroll on their own, so the
  // document belongs at the top.
  await page.evaluate(() => window.scrollTo(0, 0));

  const regionSpecs = annotations[shot.file] || [];
  const resolved = [];
  for (const region of regionSpecs) {
    const r = await resolveRegion(page, region);
    if (!r) {
      throw new Error(`${shot.file}: annotation region did not resolve: ${region.selector || JSON.stringify(region.xy)}`);
    }
    resolved.push(r);
  }
  if (resolved.length > 0) {
    await page.evaluate(inPageAnnotateSvg, [resolved, VIEWPORT]);
  }

  const out = path.join(OUT_STORY, shot.file);
  await page.screenshot({ path: out, fullPage: false });
  if (resolved.length > 0) {
    await page.evaluate(inPageClearAnnotations);
  }
  console.log(`wrote ${path.relative(REPO_ROOT, out)} (annotations ${resolved.length})`);
}

async function main() {
  assertCompiled();
  fs.mkdirSync(OUT_STORY, { recursive: true });
  const annotations = loadAnnotationSpec(ANNOTATION_SPEC);

  const wanted = process.argv.slice(2);
  const unknown = wanted.filter((file) => !SHOTS.some((shot) => shot.file === file));
  if (unknown.length > 0) {
    throw new Error(`No shot named ${unknown.join(', ')}`);
  }
  const shots = wanted.length ? SHOTS.filter((shot) => wanted.includes(shot.file)) : SHOTS;

  await withServer(async (baseUrl) => {
    const browser = await chromium.launch({ headless: true });
    try {
      const page = await browser.newPage({
        viewport: VIEWPORT,
        deviceScaleFactor: 1,
      });
      page.on('pageerror', (err) => {
        throw err;
      });
      await page.addInitScript(() => {
        localStorage.setItem('re-frame.story/seen-help-v1', 'true');
      });

      for (const shot of shots) {
        await captureShot(page, baseUrl, shot, annotations);
      }
    } finally {
      await browser.close();
    }
  });
}

main().catch((err) => {
  console.error(err);
  process.exit(1);
});
