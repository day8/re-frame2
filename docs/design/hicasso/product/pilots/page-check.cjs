#!/usr/bin/env node
//
// page-check - load a URL in a headless browser and report what rendered.
//
// A compile that succeeds says nothing about whether the page boots, and a
// page that boots says nothing about whether the screen you care about has
// anything on it. This prints both, and exits non-zero when either is false.
//
//   node page-check.cjs <url> [options]
//
//   --expect <text>    text that must appear in the page's visible text.
//                      Repeat for several. The script waits for all of them
//                      to appear (see --wait) before it reports.
//   --mount <selector> the element the application renders into.
//                      Default "#app".
//   --wait <ms>        how long to keep waiting for the --expect strings.
//                      Default 10000. A healthy page returns as soon as they
//                      are all present, so a generous value costs nothing.
//   --timeout <ms>     navigation timeout. Default 30000.
//   --text <n>         how much visible text to print. Default 2000 chars.
//   --headed           run with a visible browser window.
//
// Exit status
//   0  the mount rendered something, no page or console errors, and every
//      --expect string is on the page.
//   1  one of those is false. The report says which.
//   2  the check could not run at all - bad arguments, no browser, the
//      server refused the connection.
//
// Requires the "playwright" dev dependency and its Chromium:
//
//   npm install
//   npx playwright install chromium
//
// This is an ordinary script in your project. Read it, change it, extend it.

const DEFAULTS = { mount: '#app', wait: 10000, timeout: 30000, text: 2000 };

function usage(message) {
  if (message) console.error('page-check: ' + message + '\n');
  console.error(
    'usage: node page-check.cjs <url> [--expect <text>]... [--mount <sel>]\n' +
    '                           [--wait <ms>] [--timeout <ms>] [--text <n>] [--headed]');
  process.exit(2);
}

function parseArgs(argv) {
  const opts = Object.assign({ url: null, expect: [], headed: false }, DEFAULTS);
  const takesValue = ['--expect', '--mount', '--wait', '--timeout', '--text'];
  for (let i = 0; i < argv.length; i++) {
    const a = argv[i];
    if (a === '--help' || a === '-h') usage(null);
    else if (a === '--headed') opts.headed = true;
    else if (takesValue.includes(a)) {
      const v = argv[++i];
      if (v === undefined) usage(a + ' needs a value');
      if (a === '--expect') opts.expect.push(v);
      else if (a === '--mount') opts.mount = v;
      else {
        const n = Number(v);
        if (!Number.isFinite(n) || n < 0) usage(a + ' needs a number, got ' + JSON.stringify(v));
        opts[a.slice(2)] = n;
      }
    } else if (a.startsWith('-')) usage('unknown option ' + a);
    else if (opts.url === null) opts.url = a;
    else usage('more than one url given: ' + opts.url + ' and ' + a);
  }
  if (!opts.url) usage('no url given');
  return opts;
}

function truncate(s, n) {
  const flat = s.replace(/\s+/g, ' ').trim();
  return flat.length <= n ? flat : flat.slice(0, n) + ' ... [' + (flat.length - n) + ' more chars]';
}

async function main() {
  const opts = parseArgs(process.argv.slice(2));

  let chromium;
  try {
    ({ chromium } = require('playwright'));
  } catch (e) {
    console.error('page-check: cannot load playwright (' + e.message + ').');
    console.error('            run "npm install" and then "npx playwright install chromium".');
    process.exit(2);
  }

  const consoleErrors = [];
  const consoleWarnings = [];
  const pageErrors = [];
  const badResponses = [];

  let browser;
  try {
    browser = await chromium.launch({ headless: !opts.headed });
  } catch (e) {
    console.error('page-check: cannot start Chromium (' + e.message + ').');
    console.error('            run "npx playwright install chromium".');
    process.exit(2);
  }

  const page = await browser.newPage();
  page.on('console', (m) => {
    if (m.type() === 'error') consoleErrors.push(m.text());
    else if (m.type() === 'warning') consoleWarnings.push(m.text());
  });
  page.on('pageerror', (e) => pageErrors.push(e && e.stack ? e.stack.split('\n')[0] : String(e)));
  page.on('requestfailed', (r) => badResponses.push('FAILED ' + r.url()));
  page.on('response', (r) => { if (r.status() >= 400) badResponses.push(r.status() + ' ' + r.url()); });

  try {
    await page.goto(opts.url, { waitUntil: 'load', timeout: opts.timeout });
  } catch (e) {
    await browser.close();
    console.error('page-check: could not load ' + opts.url + ' (' + e.message.split('\n')[0] + ').');
    console.error('            is the dev server running?');
    process.exit(2);
  }

  // Wait for every expectation, then stop. An empty --expect list still waits
  // for the mount to have something in it, which is the weakest honest form
  // of the same question.
  const deadline = Date.now() + opts.wait;
  let visible = '';
  let mountHtml = null;
  for (;;) {
    const state = await page.evaluate((sel) => {
      const el = document.querySelector(sel);
      return { html: el ? el.innerHTML : null, text: document.body ? document.body.innerText : '' };
    }, opts.mount);
    mountHtml = state.html;
    visible = state.text || '';
    const stillMissing = opts.expect.filter((t) => !visible.includes(t));
    const settled = mountHtml !== null && mountHtml.length > 0 && stillMissing.length === 0;
    if (settled || Date.now() >= deadline) break;
    await page.waitForTimeout(250);
  }

  const missing = opts.expect.filter((t) => !visible.includes(t));
  const found = opts.expect.filter((t) => visible.includes(t));

  console.log('url            ' + opts.url);
  console.log('mount          ' + opts.mount + ' -> ' +
    (mountHtml === null ? 'NOT FOUND IN THE PAGE' : mountHtml.length + ' chars of innerHTML'));
  console.log('page errors    ' + pageErrors.length);
  pageErrors.forEach((e) => console.log('               ! ' + e));
  console.log('console errors ' + consoleErrors.length);
  consoleErrors.forEach((e) => console.log('               ! ' + truncate(e, 300)));
  if (consoleWarnings.length) {
    console.log('console warns  ' + consoleWarnings.length + ' (reported, not failed on)');
    consoleWarnings.slice(0, 5).forEach((e) => console.log('               - ' + truncate(e, 200)));
  }
  if (badResponses.length) {
    console.log('bad responses  ' + badResponses.length + ' (reported, not failed on)');
    badResponses.slice(0, 10).forEach((r) => console.log('               - ' + r));
  }
  if (opts.expect.length) {
    console.log('expected text  ' + found.length + '/' + opts.expect.length + ' present');
    opts.expect.forEach((t) =>
      console.log('               ' + (visible.includes(t) ? 'ok      ' : 'MISSING ') + JSON.stringify(t)));
  }
  console.log('visible text   ' + truncate(visible, opts.text));

  await browser.close();

  const reasons = [];
  if (mountHtml === null) reasons.push('the mount element ' + opts.mount + ' is not in the page');
  else if (mountHtml.length === 0) reasons.push('the mount element ' + opts.mount + ' is empty');
  if (pageErrors.length) reasons.push(pageErrors.length + ' page error(s)');
  if (consoleErrors.length) reasons.push(consoleErrors.length + ' console error(s)');
  if (missing.length) {
    reasons.push(missing.length + ' expected string(s) not on the page: ' +
      missing.map((t) => JSON.stringify(t)).join(', '));
  }

  if (reasons.length === 0) {
    console.log('');
    console.log('PASS');
    process.exit(0);
  }
  console.log('');
  console.log('FAIL');
  reasons.forEach((r) => console.log('  - ' + r));
  process.exit(1);
}

main().catch((e) => {
  console.error('page-check: ' + (e && e.stack ? e.stack : e));
  process.exit(2);
});
