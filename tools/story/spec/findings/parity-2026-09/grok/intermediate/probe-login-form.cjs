'use strict';
// Playwright probe of the login-form Story shell. Read-only.
const { chromium } = require(require('path').join(
  '<HOME>/code/re-frame2/implementation/node_modules/playwright'
));
const fs = require('fs');
const path = require('path');
const url = process.env.STORY_URL || 'http://127.0.0.1:8766/#/stories';
const outDir = __dirname;
(async () => {
  const browser = await chromium.launch({ headless: true });
  const page = await browser.newPage({ viewport: { width: 1440, height: 900 } });
  const notes = [];
  page.on('pageerror', (e) => notes.push('PAGEERROR ' + e.message));
  page.on('console', (msg) => {
    if (msg.type() === 'error') notes.push('CONSOLE ' + msg.text());
  });
  await page.goto(url, { waitUntil: 'load', timeout: 90000 });
  await page.waitForTimeout(12000);
  const title = await page.title();
  const bodyText = await page.locator('body').innerText().catch(() => '');
  const sidebar = await page.locator('[data-test]').evaluateAll((els) =>
    els.slice(0, 40).map((e) => e.getAttribute('data-test'))
  ).catch(() => []);
  const htmlLen = (await page.content()).length;
  await page.screenshot({ path: path.join(outDir, 'probe-login-form-stories.png'), fullPage: true });
  const gotIt = page.getByRole('button', { name: /got it/i });
  if (await gotIt.count()) await gotIt.click();
  await page.waitForTimeout(500);
  const idle = page.locator('[data-test="story-sidebar-variant-row"]', { hasText: '/idle' }).first();
  if (await idle.count()) await idle.click();
  await page.waitForTimeout(2500);
  await page.screenshot({ path: path.join(outDir, 'probe-login-form-idle.png'), fullPage: true });
  const idleBody = await page.locator('body').innerText().catch(() => '');
  const ws = page.locator('text=:Workspace.login-form/all-states').first();
  if (await ws.count()) await ws.click();
  await page.waitForTimeout(2500);
  await page.screenshot({ path: path.join(outDir, 'probe-login-form-workspace.png'), fullPage: true });
  const wsBody = await page.locator('body').innerText().catch(() => '');
  fs.writeFileSync(
    path.join(outDir, 'probe-login-form.txt'),
    JSON.stringify({
      title, htmlLen, sidebar,
      bodyPreview: bodyText.slice(0, 2500),
      idlePreview: idleBody.slice(0, 1800),
      workspacePreview: wsBody.slice(0, 1800),
      notes
    }, null, 2)
  );
  await browser.close();
  console.log('wrote probe-login-form.txt sidebar=' + sidebar.length + ' htmlLen=' + htmlLen);
})().catch((e) => {
  console.error(e);
  process.exit(1);
});
