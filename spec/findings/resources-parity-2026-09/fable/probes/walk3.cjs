// Conduit walk 3: the favourite timeline. One page load, in-app navigation,
// then poll the favourite button text every ~8 ms for 1.2 s after ONE click to
// see when the optimistic flip becomes visible and when the reply settles.
// Then a NET change (one unfavourite) and a check on Home + profile.
const path = require('path');
const fs = require('fs');
const { chromium } = require('<HOME>/code/re-frame2/implementation/node_modules/playwright');
const BASE = process.env.CONDUIT_URL || 'http://127.0.0.1:8050/';
const OUT = process.argv[2] || path.join(__dirname, 'walk-out-3');
fs.mkdirSync(OUT, { recursive: true });
const log = []; const note = (k, v) => { log.push({ t: Date.now(), k, v }); console.log(k, JSON.stringify(v)); };
const norm = s => (s || '').replace(/\s+/g, ' ').trim();
(async () => {
  const browser = await chromium.launch({ headless: true });
  const page = await browser.newPage({ viewport: { width: 1200, height: 900 } });
  const clickLink = async (name) => { await page.getByRole('link', { name, exact: false }).first().click({ timeout: 10000 }); await page.waitForTimeout(700); };
  await page.goto(BASE, { waitUntil: 'load', timeout: 30000 });
  await page.waitForSelector('.article-preview', { timeout: 30000 });
  await clickLink('Sign in');
  await page.fill('input[type="email"], input[name="email"]', 'demo@conduit.dev');
  await page.fill('input[type="password"], input[name="password"]', 'anything');
  await page.click('button[type="submit"]');
  await page.waitForSelector('.article-preview', { timeout: 15000 });
  await page.waitForTimeout(500);
  const homeBefore = (await page.locator('.article-preview button').allTextContents()).map(norm);
  note('home.fav_buttons_before', homeBefore);
  await clickLink('Hello, Conduit');
  await page.waitForSelector('h1', { timeout: 15000 });
  await page.waitForTimeout(500);
  // timeline: click via the DOM inside one evaluate so the sampling starts on the same tick as the click
  const timeline = await page.evaluate(async () => {
    const btn = [...document.querySelectorAll('button')].find(b => /avorite Article/.test(b.textContent));
    const read = () => { const b = [...document.querySelectorAll('button')].find(x => /avorite Article/.test(x.textContent)); return b ? b.textContent.replace(/\s+/g, ' ').trim() : null; };
    const samples = []; const t0 = performance.now(); let last = read();
    samples.push({ ms: 0, text: last });
    btn.click();
    for (let i = 0; i < 150; i++) {
      await new Promise(r => setTimeout(r, 8));
      const cur = read();
      if (cur !== last) { samples.push({ ms: Math.round(performance.now() - t0), text: cur }); last = cur; }
    }
    samples.push({ ms: Math.round(performance.now() - t0), text: read(), final: true });
    return samples;
  });
  note('article.fav_timeline_after_one_click', timeline);
  await page.waitForTimeout(500);
  await clickLink('Home');
  await page.waitForSelector('.article-preview', { timeout: 15000 });
  await page.waitForTimeout(800);
  const homeAfter = (await page.locator('.article-preview button').allTextContents()).map(norm);
  note('home.fav_buttons_after_one_unfavourite', homeAfter);
  note('home.first_changed', { before: homeBefore[0], after: homeAfter[0] });
  await clickLink('demo');
  await page.waitForTimeout(800);
  await page.getByRole('link', { name: /Favorited/i }).first().click({ timeout: 5000 }).catch(() => {});
  await page.waitForTimeout(1000);
  note('profile.favorited_previews_after', await page.locator('.article-preview').count());
  await page.screenshot({ path: path.join(OUT, 'profile-favorited-after.png') });
  fs.writeFileSync(path.join(OUT, 'walk-log.json'), JSON.stringify(log, null, 2));
  await browser.close();
  console.log('DONE');
})().catch(e => { console.error('WALK FAILED', e); process.exit(1); });
