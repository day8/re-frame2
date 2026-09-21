#!/usr/bin/env node
/**
 * Conduit (realworld_resources) browser walk for the Resources closeness test.
 * Fake backend: in-process demo_backend, 20ms delay. Not AbortController / ACL.
 *
 * Usage (from anywhere, node that can require implementation/node_modules/playwright):
 *   node ai/findings/Resources/grok/intermediate/conduit-walk.cjs
 */
'use strict';

const fs = require('fs');
const path = require('path');
const { chromium } = require('../../../../../implementation/node_modules/playwright');

const BASE = process.env.CONDUIT_URL || 'http://127.0.0.1:8051';
const OUT = path.join(__dirname, 'conduit-walk');
const LEDGER = [];

function log(step, extra) {
  const row = { t: new Date().toISOString(), step, ...extra };
  LEDGER.push(row);
  console.log(JSON.stringify(row));
}

async function shot(page, name) {
  const dest = path.join(OUT, name + '.png');
  await page.screenshot({ path: dest, fullPage: true });
  log('screenshot', { name, dest });
}

(async () => {
  fs.mkdirSync(OUT, { recursive: true });
  const browser = await chromium.launch({ headless: true });
  const page = await browser.newPage();
  page.setDefaultTimeout(15000);

  // ---- 1. Home: first cached read ----
  await page.goto(BASE + '/', { waitUntil: 'domcontentloaded', timeout: 30000 });
  await page.getByTestId('article-list').waitFor({ state: 'visible' });
  const homeTitles = await page.locator('[data-testid^="article-preview-"] h1').allTextContents();
  log('home-loaded', { n: homeTitles.length, first: homeTitles[0], titles: homeTitles.slice(0, 3) });
  await shot(page, '01-home');

  const page2 = page.getByTestId('page-2');
  const hasPagination = await page2.count();
  log('pagination', { hasPage2: hasPagination > 0 });

  // ---- 2. Page 2 then back (keep-previous + cache identity) ----
  if (hasPagination) {
    await page2.click();
    // 20ms stub; we may miss the keeping-previous flash. Record if seen.
    const keeping = await page.getByTestId('list-keeping-previous').count().catch(() => 0);
    await page.getByTestId('article-list').waitFor({ state: 'visible' });
    const p2Titles = await page.locator('[data-testid^="article-preview-"] h1').allTextContents();
    log('page-2', { keepingPreviousSeen: keeping > 0, n: p2Titles.length, first: p2Titles[0], url: page.url() });
    await shot(page, '02-page2');

    await page.getByTestId('page-1').click();
    await page.getByTestId('article-list').waitFor({ state: 'visible' });
    const backTitles = await page.locator('[data-testid^="article-preview-"] h1').allTextContents();
    log('back-page-1', {
      first: backTitles[0],
      matchesOriginal: backTitles[0] === homeTitles[0],
      url: page.url(),
    });
  }

  // ---- 3. Detail then return (J1/J2/J19-lite) ----
  await page.getByTestId('article-link-hello-conduit').click();
  await page.getByTestId('article-title').waitFor({ state: 'visible' });
  const detailTitle = await page.getByTestId('article-title').innerText();
  const comments = await page.getByTestId('comments-list').count();
  log('detail', { title: detailTitle, commentsList: comments > 0, url: page.url() });
  await shot(page, '03-detail');

  await page.getByTestId('back-home').click();
  await page.getByTestId('article-list').waitFor({ state: 'visible' });
  log('return-home', { url: page.url() });

  // ---- 4. Tag filter ----
  const tagIntro = page.getByTestId('tag-intro');
  if (await tagIntro.count()) {
    await tagIntro.click();
    await page.getByTestId('article-list').waitFor({ state: 'visible' });
    const tagged = await page.locator('[data-testid^="article-preview-"] h1').allTextContents();
    log('tag-intro', { n: tagged.length, first: tagged[0], url: page.url() });
    await page.getByTestId('global-feed-tab').click();
    await page.getByTestId('article-list').waitFor({ state: 'visible' });
  }

  // ---- 5. Logged-out favorite bounces to login (not a failed mutation) ----
  await page.getByTestId('favorite-hello-conduit').click();
  await page.getByTestId('login-page').waitFor({ state: 'visible' });
  log('logged-out-favorite-bounces-to-login', { url: page.url() });
  await shot(page, '04-login');

  // ---- 6. Sign in (demo: any credentials) ----
  await page.getByTestId('login-email').fill('demo@conduit.dev');
  await page.getByTestId('login-password').fill('demo');
  await page.getByTestId('login-submit').click();
  await page.getByTestId('nav-username').waitFor({ state: 'visible' });
  const who = await page.getByTestId('nav-username').innerText();
  log('signed-in', { who: who.trim() });
  await shot(page, '05-signed-in-home');

  // ---- 7. Favourite on the list (optimistic heart) ----
  const countBefore = await page.getByTestId('favorites-count-hello-conduit').innerText();
  await page.getByTestId('favorite-hello-conduit').click();
  // Optimistic: class "optimistic" and/or count change without waiting the 20ms.
  await page.waitForTimeout(50);
  const btnClass = await page.getByTestId('favorite-hello-conduit').getAttribute('class');
  const countAfterClick = await page.getByTestId('favorites-count-hello-conduit').innerText();
  log('favorite-list', { countBefore, countAfterClick, btnClass });
  await page.waitForTimeout(80); // let 20ms stub settle
  const countSettled = await page.getByTestId('favorites-count-hello-conduit').innerText();
  const btnClassSettled = await page.getByTestId('favorite-hello-conduit').getAttribute('class');
  log('favorite-list-settled', { countSettled, btnClassSettled });
  await shot(page, '06-favorited-list');

  // ---- 8. Same article on the detail (cross-view) ----
  await page.getByTestId('article-link-hello-conduit').click();
  await page.getByTestId('article-title').waitFor({ state: 'visible' });
  const detailFavClass = await page.getByTestId('article-favorite').getAttribute('class');
  const detailCount = await page.getByTestId('article-favorites-count').innerText();
  log('favorite-detail', { detailFavClass, detailCount });
  await shot(page, '07-favorited-detail');

  // ---- 9. Comment (J4 invalidate comments) ----
  await page.getByTestId('comment-body-input').fill('walk-comment-' + Date.now());
  await page.getByTestId('comment-submit').click();
  await page.waitForTimeout(120);
  const commentBodies = await page.getByTestId('comment-body').allTextContents();
  log('comment-posted', { n: commentBodies.length, last: commentBodies[commentBodies.length - 1] });
  await shot(page, '08-comment');

  // ---- 10. Favorited-articles tab membership (not only boolean) ----
  await page.getByTestId('nav-username').click();
  await page.getByTestId('profile-username').waitFor({ state: 'visible' });
  await page.getByTestId('profile-tab-favorited').click();
  await page.waitForTimeout(80);
  const favTabHasHello = await page.getByTestId('article-preview-hello-conduit').count();
  log('favorited-tab', { helloPresent: favTabHasHello > 0, url: page.url() });
  await shot(page, '09-favorited-tab');

  // ---- 11. Overlapping favorite/unfavorite on the list card if present ----
  if (favTabHasHello) {
    await page.getByTestId('favorite-hello-conduit').click();
    await page.getByTestId('favorite-hello-conduit').click();
    await page.waitForTimeout(150);
    const afterOverlap = await page.getByTestId('article-preview-hello-conduit').count();
    log('overlap-fav-unfav', { stillOnTab: afterOverlap > 0 });
  }

  // ---- 12. Your Feed tab ----
  await page.getByTestId('nav-signin').count(); // no-op if signed in
  await page.locator('.navbar-brand').click();
  await page.getByTestId('article-list').waitFor({ state: 'visible' });
  if (await page.getByTestId('your-feed-tab').count()) {
    await page.getByTestId('your-feed-tab').click();
    await page.waitForTimeout(80);
    const feedEmpty = await page.getByTestId('list-empty').count();
    const feedN = await page.locator('[data-testid="article-list"] [data-testid^="article-preview-"]').count();
    log('your-feed', { empty: feedEmpty > 0, n: feedN, url: page.url() });
    await shot(page, '10-your-feed');
  }

  // ---- 13. Logout (J7) — hearts should not keep demo's favorited flags as anonymous ----
  await page.getByTestId('nav-logout').click();
  await page.getByTestId('nav-signin').waitFor({ state: 'visible' });
  await page.getByTestId('article-list').waitFor({ state: 'visible' });
  const loggedOutFavClass = await page.getByTestId('favorite-hello-conduit').getAttribute('class');
  log('after-logout', { loggedOutFavClass, url: page.url() });
  await shot(page, '11-after-logout');

  // ---- 14. Leave-and-return mid-flight (J19): hammer pagination ----
  if (await page.getByTestId('page-2').count()) {
    await page.getByTestId('page-2').click();
    await page.getByTestId('page-1').click();
    await page.getByTestId('article-list').waitFor({ state: 'visible' });
    const afterHammer = await page.locator('[data-testid^="article-preview-"] h1').allTextContents();
    log('leave-return-pagination', { first: afterHammer[0], url: page.url() });
  }

  fs.writeFileSync(path.join(OUT, 'ledger.json'), JSON.stringify(LEDGER, null, 2));
  log('done', { steps: LEDGER.length, out: OUT });
  await browser.close();
  process.exit(0);
})().catch((err) => {
  console.error(err);
  try {
    fs.writeFileSync(path.join(OUT, 'ledger.json'), JSON.stringify(LEDGER.concat([{ error: String(err.stack || err) }]), null, 2));
  } catch (_) {}
  process.exit(1);
});
