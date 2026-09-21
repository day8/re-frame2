// Conduit walk, attempt 2: ONE page load, then in-app navigation only (the demo
// backend fails session restore on a cold boot by design, so page.goto after
// login logs the walker out). Evidence: DOM reads + screenshots + console.
const path = require('path');
const fs = require('fs');
const { chromium } = require('C:/Users/miket/code/re-frame2/implementation/node_modules/playwright');
const BASE = process.env.CONDUIT_URL || 'http://127.0.0.1:8050/';
const OUT = process.argv[2] || path.join(__dirname, 'walk-out-2');
fs.mkdirSync(OUT, { recursive: true });
const log = [];
const note = (k, v) => { log.push({ t: Date.now(), k, v }); console.log(k, JSON.stringify(v)); };
const norm = s => (s || '').replace(/\s+/g, ' ').trim();

(async () => {
  const browser = await chromium.launch({ headless: true });
  const page = await browser.newPage({ viewport: { width: 1200, height: 900 } });
  const consoleErrors = [];
  page.on('console', m => { if (m.type() === 'error') consoleErrors.push(m.text().slice(0, 200)); });
  page.on('pageerror', e => consoleErrors.push('pageerror ' + String(e).slice(0, 200)));
  const shot = async (name) => page.screenshot({ path: path.join(OUT, name + '.png') });
  const nav = async () => norm(await page.locator('nav').first().textContent().catch(() => ''));
  const favTexts = async () => (await page.locator('.article-preview button').allTextContents()).map(norm);
  const clickLink = async (name) => { await page.getByRole('link', { name, exact: false }).first().click({ timeout: 10000 }); await page.waitForTimeout(700); };

  const t0 = Date.now();
  await page.goto(BASE, { waitUntil: 'load', timeout: 30000 });
  await page.waitForSelector('.article-preview', { timeout: 30000 });
  note('home.load_ms', Date.now() - t0);
  note('home.previews', await page.locator('.article-preview').count());
  note('home.fav_buttons', await favTexts());
  note('home.nav', await nav());
  await shot('01-home');

  // sign in via the app's own link
  await clickLink('Sign in');
  await page.waitForSelector('input[type="email"], input[name="email"]', { timeout: 15000 });
  await page.fill('input[type="email"], input[name="email"]', 'demo@conduit.dev');
  await page.fill('input[type="password"], input[name="password"]', 'anything');
  const tLogin = Date.now();
  await page.click('button[type="submit"]');
  await page.waitForSelector('.article-preview', { timeout: 15000 });
  await page.waitForTimeout(700);
  note('login.ms_to_home_list', Date.now() - tLogin);
  note('login.nav', await nav());
  note('home.fav_buttons_logged_in', await favTexts());
  note('home.tabs', (await page.locator('.feed-toggle a, .nav-pills a, ul.nav a').allTextContents()).map(norm));
  await shot('02-home-logged-in');

  // open the seeded article through its preview link
  await clickLink('Hello, Conduit');
  await page.waitForSelector('h1', { timeout: 15000 });
  await page.waitForTimeout(500);
  note('article.h1', norm(await page.locator('h1').first().textContent()));
  note('article.nav', await nav());
  const buttons = async () => (await page.locator('button').allTextContents()).map(norm).filter(Boolean);
  note('article.buttons', await buttons());
  await shot('03-article');

  // J2: favourite (or unfavourite) — read the button before, immediately after, and settled
  const fav = page.locator('button', { hasText: /avorite Article/ }).first();
  const before = norm(await fav.textContent());
  const tClick = Date.now();
  await fav.click();
  const immediate = norm(await page.locator('button', { hasText: /avorite Article/ }).first().textContent());
  const msImmediate = Date.now() - tClick;
  await page.waitForTimeout(1500);
  const settled = norm(await page.locator('button', { hasText: /avorite Article/ }).first().textContent());
  note('article.fav_click', { before, immediate, ms_immediate: msImmediate, settled });
  await shot('04-article-after-fav');
  // click it back so the state returns to the seeded one, then forward again (two overlapping clicks: optimistic)
  const t2 = Date.now();
  await page.locator('button', { hasText: /avorite Article/ }).first().click();
  const afterSecondImmediate = norm(await page.locator('button', { hasText: /avorite Article/ }).first().textContent());
  await page.waitForTimeout(1500);
  note('article.fav_click_2', { immediate: afterSecondImmediate, settled: norm(await page.locator('button', { hasText: /avorite Article/ }).first().textContent()), ms: Date.now() - t2 });

  // follow the author
  const follow = page.locator('button', { hasText: /ollow/ }).first();
  const followBefore = norm(await follow.textContent().catch(() => ''));
  await follow.click({ timeout: 5000 }).catch(e => note('article.follow_error', String(e).slice(0, 100)));
  await page.waitForTimeout(1200);
  note('article.follow', { before: followBefore, after: norm(await page.locator('button', { hasText: /ollow/ }).first().textContent().catch(() => '')) });

  // post a comment
  const commentsBefore = await page.locator('.card').count();
  await page.locator('textarea').first().fill('probe comment ' + Date.now()).catch(e => note('comment.fill_error', String(e).slice(0, 100)));
  await page.locator('button', { hasText: /Post Comment/i }).first().click({ timeout: 5000 }).catch(e => note('comment.click_error', String(e).slice(0, 100)));
  await page.waitForTimeout(1500);
  note('comment', { cards_before: commentsBefore, cards_after: await page.locator('.card').count(), body_present: (await page.locator('text=probe comment').count()) > 0 });
  await shot('05-article-after-comment');

  // back home via the nav: list must reflect the favourite (list was not mounted during the write)
  await clickLink('Home');
  await page.waitForSelector('.article-preview', { timeout: 15000 });
  await page.waitForTimeout(700);
  note('home.fav_buttons_after_article_actions', await favTexts());
  await shot('06-home-after');

  // Your Feed tab (follow -> feed has the author's articles)
  const feedTab = page.getByRole('link', { name: /Your Feed/i }).first();
  await feedTab.click({ timeout: 5000 }).catch(e => note('feed.tab_error', String(e).slice(0, 100)));
  await page.waitForTimeout(1200);
  note('feed.previews', await page.locator('.article-preview').count());
  note('feed.first', norm(await page.locator('.article-preview').first().textContent().catch(() => '')).slice(0, 120));
  await shot('07-your-feed');

  // profile -> Favorited Articles
  await clickLink('demo');
  await page.waitForTimeout(1000);
  note('profile.nav', await nav());
  const favTab = page.getByRole('link', { name: /Favorited/i }).first();
  await favTab.click({ timeout: 5000 }).catch(e => note('profile.fav_tab_error', String(e).slice(0, 100)));
  await page.waitForTimeout(1200);
  note('profile.favorited_previews', await page.locator('.article-preview').count());
  note('profile.favorited_titles', (await page.locator('.article-preview h1, .article-preview h2, .article-preview a.preview-link').allTextContents()).map(norm).slice(0, 12));
  await shot('08-profile-favorited');

  // settings -> logout
  await clickLink('Settings');
  await page.waitForTimeout(800);
  const logoutBtn = page.locator('button', { hasText: /logout/i }).first();
  await logoutBtn.click({ timeout: 5000 }).catch(e => note('logout.error', String(e).slice(0, 100)));
  await page.waitForTimeout(1200);
  note('logout.nav', await nav());
  note('logout.url', page.url());
  await clickLink('Home').catch(() => {});
  await page.waitForSelector('.article-preview', { timeout: 15000 }).catch(() => {});
  await page.waitForTimeout(700);
  note('home.fav_buttons_after_logout', await favTexts());
  await shot('09-home-after-logout');

  note('console.errors', consoleErrors.filter(e => !/shadow-cljs watch/.test(e)).slice(0, 10));
  fs.writeFileSync(path.join(OUT, 'walk-log.json'), JSON.stringify(log, null, 2));
  await browser.close();
  console.log('DONE');
})().catch(e => { console.error('WALK FAILED', e); process.exit(1); });
