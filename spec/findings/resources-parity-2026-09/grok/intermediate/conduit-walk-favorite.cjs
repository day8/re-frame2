#!/usr/bin/env node
'use strict';
const { chromium } = require('../../../../../implementation/node_modules/playwright');
const BASE = process.env.CONDUIT_URL || 'http://127.0.0.1:8051';

(async () => {
  const browser = await chromium.launch({ headless: true });
  const page = await browser.newPage();
  page.setDefaultTimeout(15000);
  await page.goto(BASE + '/', { waitUntil: 'domcontentloaded', timeout: 30000 });
  await page.getByTestId('nav-signin').click();
  await page.getByTestId('login-email').fill('demo@conduit.dev');
  await page.getByTestId('login-password').fill('demo');
  await page.getByTestId('login-submit').click();
  await page.getByTestId('nav-username').waitFor();
  await page.getByTestId('article-list').waitFor();
  const before = await page.getByTestId('favorites-count-article-2').innerText();
  await page.getByTestId('favorite-article-2').click();
  await page.waitForTimeout(50);
  const midClass = await page.getByTestId('favorite-article-2').getAttribute('class');
  const midCount = await page.getByTestId('favorites-count-article-2').innerText();
  await page.waitForTimeout(100);
  const settledClass = await page.getByTestId('favorite-article-2').getAttribute('class');
  const settledCount = await page.getByTestId('favorites-count-article-2').innerText();
  await page.getByTestId('article-link-article-2').click();
  await page.getByTestId('article-title').waitFor();
  const detailClass = await page.getByTestId('article-favorite').getAttribute('class');
  const detailCount = await page.getByTestId('article-favorites-count').innerText();
  await page.getByTestId('nav-username').click();
  await page.getByTestId('profile-tab-favorited').click();
  await page.waitForTimeout(120);
  const onTab = await page.getByTestId('article-preview-article-2').count();
  console.log(JSON.stringify({
    before, midClass, midCount, settledClass, settledCount,
    detailClass, detailCount, onTab, url: page.url(),
  }, null, 2));
  await browser.close();
})().catch((e) => { console.error(e); process.exit(1); });
