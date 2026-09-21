const { chromium } = require('C:/Users/miket/code/re-frame2/implementation/node_modules/playwright');
const http = require('node:http'), fs = require('node:fs'), path = require('node:path');
const root = __dirname;
const mode = process.argv[2] || 'resources';
const dir = path.join(root, mode === 'tanstack' ? 'tanstack' : 'browser');
const server = http.createServer((req, res) => {
  const rel = decodeURIComponent(req.url.split('?')[0]);
  const file = path.resolve(dir, '.' + rel);
  if (!file.startsWith(dir + path.sep) && file !== dir) { res.writeHead(403); res.end(); return; }
  const target = fs.existsSync(file) && fs.statSync(file).isFile() ? file : path.join(dir, 'index.html');
  res.setHeader('Content-Type', target.endsWith('.js') ? 'application/javascript' : target.endsWith('.css') ? 'text/css' : 'text/html');
  fs.createReadStream(target).pipe(res);
});
(async () => {
  await new Promise(resolve => server.listen(0, '127.0.0.1', resolve));
  const browser = await chromium.launch({ headless: true });
  const page = await browser.newPage({ viewport: { width: 1280, height: 1000 } });
  const errors = []; page.on('pageerror', e => errors.push(e.message));
  await page.addInitScript(() => {
    window.researchErrors=[];
    window.addEventListener('error',event=>window.researchErrors.push({message:event.message,stack:event.error?.stack,
      data:typeof cljs!=='undefined'?cljs.core.pr_str(event.error?.data):null}));
  });
  try {
    await page.goto(`http://127.0.0.1:${server.address().port}/`);
    await page.waitForFunction(() => document.body.innerText.includes('Conduit') || document.body.innerText.includes('conduit'));
    if (mode === 'resources') {
      await page.getByTestId('article-list').waitFor();
      await page.getByTestId('nav-signin').click();
      await page.getByTestId('login-email').fill('demo@conduit.dev');
      await page.getByTestId('login-password').fill('research-synthetic-password');
      await page.getByTestId('login-submit').click();
      await page.getByTestId('nav-username').waitFor();
      await page.getByTestId('global-feed-tab').click();
      await page.getByTestId('article-list').waitFor();
      const countBefore = await page.getByTestId('favorites-count-hello-conduit').innerText();
      await page.getByTestId('favorite-hello-conduit').click();
      await page.waitForFunction(before => document.querySelector('[data-testid="favorites-count-hello-conduit"]')?.textContent !== before, countBefore);
      await page.getByTestId('article-link-hello-conduit').click();
      await page.getByTestId('article-title').waitFor();
      const state = await page.evaluate(() => ({ user: window.__conduit_debug__.getCurrentUser()?.username,
        body: document.body.innerText, globals: { cljsReader: typeof cljs.reader, resourceRuntime: typeof re_frame.resources } }));
      fs.writeFileSync(path.join(root, 'conduit-browser-smoke.json'), JSON.stringify({ state, countBefore, errors }, null, 2));
      await require('./conduit-cases.cjs')(page);
    } else {
      await page.waitForFunction(() => document.querySelector('[data-testid="detail"]')?.textContent.includes('false (0)'));
      const initial = await page.evaluate(() => window.research.ledger.slice());
      if (initial.filter(r => r.kind === 'detail').length !== 1) throw new Error('Two detail observers did not dedupe');
      await page.getByRole('button', { name: 'Favorite', exact: true }).click();
      await page.waitForFunction(() => document.querySelector('[data-testid="favorites"]')?.textContent.includes('count=1'));
      fs.writeFileSync(path.join(root, 'tanstack-browser-smoke.json'), JSON.stringify({ initial, final: await page.evaluate(() => ({ ledger: window.research.ledger, server: window.research.getServer(), body: document.body.innerText })), errors }, null, 2));
      await require('./tanstack-browser-cases.cjs')(page);
    }
    await page.screenshot({ path: path.join(root, `${mode}-browser.png`), fullPage: true });
    if (errors.length) throw new Error(errors.join('\n'));
    console.log(`${mode}: browser smoke passed`);
  } catch (error) {
    const diagnostic = await page.evaluate(() => ({ body: document.body.innerText,
      cache: typeof re_frame !== 'undefined' ? cljs.core.pr_str(re_frame.core.frame_state_value(cljs.core.keyword('rf/default'))) : null,
      ledger: window.resourceResearch?.ledger, runtimeErrors:window.researchErrors })).catch(e => ({ diagnosticError: String(e) }));
    fs.writeFileSync(path.join(root, `${mode}-browser-failure-${Date.now()}.json`), JSON.stringify({ error: String(error), errors, diagnostic }, null, 2));
    throw error;
  } finally { await browser.close(); await new Promise(resolve => server.close(resolve)); }
})().catch(error => { console.error(error); server.close(); process.exitCode = 1; });
