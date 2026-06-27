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
 * The script serves the compiled bundles from implementation/out and captures
 * the current Story shell with Playwright. The generated PNGs are committed so
 * the MkDocs site can render without needing Playwright at build time.
 */

const fs = require('fs');
const http = require('http');
const path = require('path');

const REPO_ROOT = path.resolve(__dirname, '..', '..');
const IMPL_ROOT = path.join(REPO_ROOT, 'implementation');
const OUT_STORY = path.join(REPO_ROOT, 'docs', 'images', 'story');

const { chromium } = require(require.resolve('playwright', { paths: [IMPL_ROOT] }));

const VIEWPORT = { width: 1440, height: 900 };

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

const SHOTS = [
  {
    file: 'story-tutorial-00-shell.png',
    app: '/login',
    query: '?variant=story.login%2Fauthenticated',
    hash: '#/stories',
    waitFor: '[data-test="login-welcome"]',
  },
  {
    file: 'story-tutorial-01-first-variant.png',
    app: '/login',
    query: '?variant=story.login%2Fidle',
    hash: '#/stories',
    waitFor: '[data-test="login-form"]',
  },
  {
    file: 'story-tutorial-02-workspace-grid.png',
    app: '/login',
    query: '?workspace=Workspace.login%2Fall-states',
    hash: '#/stories',
    waitFor: '[data-test="login-error"]',
  },
  {
    file: 'story-tutorial-03-controls-and-fidelity.png',
    app: '/login',
    query: '?variant=story.login%2Ferror',
    hash: '#/stories',
    waitFor: '[data-test="story-view-state-section"]',
    before: async (page) => {
      await page.locator('[data-test="story-view-state-section"]').scrollIntoViewIfNeeded();
    },
  },
  {
    file: 'story-tutorial-04-test-mode.png',
    app: '/login',
    query: '?variant=story.login%2Ferror',
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
    query: '?variant=story.login%2Ferror',
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
    query: '?variant=story.login%2Ferror',
    hash: '#/stories',
    waitFor: '[data-test="story-toolbar-share"]',
    before: async (page) => {
      await page.locator('[data-test="story-toolbar-share"]').click();
      await page.locator('[data-test="story-share-export-dialog"]').waitFor({ state: 'visible' });
    },
  },
  {
    file: 'story-tutorial-07-xray-embed.png',
    app: '/login',
    query: '?variant=story.login%2Fauthenticated',
    hash: '#/stories',
    waitFor: '[data-test="story-xray-embed"]',
    before: async (page) => {
      await page.locator('[data-test="story-xray-panel-chip"]').getByText('App-db').click();
      await page.locator('[data-test="story-xray-panel-host"]').waitFor({ state: 'visible' });
    },
  },
  {
    file: 'story-tutorial-08-nine-states.png',
    app: '/nine',
    query: '?workspace=Workspace.nine-states%2Fall-states',
    hash: '#/stories',
    waitFor: '[data-test="story-sidebar"]',
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

async function captureShot(page, baseUrl, shot) {
  const url = `${baseUrl}${shot.app}/${shot.query || ''}${shot.hash || ''}`;
  await page.goto(url, { waitUntil: 'networkidle' });
  await page.locator(shot.waitFor).first().waitFor({ state: 'visible' });
  if (shot.before) {
    await shot.before(page);
  }
  await page.waitForTimeout(700);
  const out = path.join(OUT_STORY, shot.file);
  await page.screenshot({ path: out, fullPage: false });
  console.log(`wrote ${path.relative(REPO_ROOT, out)}`);
}

async function main() {
  assertCompiled();
  fs.mkdirSync(OUT_STORY, { recursive: true });

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

      for (const shot of SHOTS) {
        await captureShot(page, baseUrl, shot);
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
