'use strict';
/*
 * journey-driver.cjs — drive a long-lived headless Chromium one gesture at a time.
 *
 * The advantage journeys (rf2-a1v8a) are measured gesture by gesture, so the
 * browser has to outlive each command: `launch` starts Chromium with a CDP port,
 * `step` connects, runs ONE gesture's code against the open page, times it,
 * appends a JSON line to a transcript, optionally screenshots, and disconnects
 * without closing the browser; `stop` kills only the process `launch` started.
 *
 *   node journey-driver.cjs launch <cdp-port> <profile-dir> <pid-file>
 *   node journey-driver.cjs step <cdp-port> <label> <code-file> <transcript.jsonl> [screenshot.png]
 *   node journey-driver.cjs stop <pid-file>
 *
 * The step code is the body of an async function receiving (page, expect, ctx);
 * ctx.aria() returns the page's ARIA snapshot and ctx.text(sel) a locator's text.
 * Every navigation the step code performs must name its own timeout.
 * JOURNEY_PLAYWRIGHT names the playwright package directory to load.
 */
const fs = require('fs');
const path = require('path');
const { spawn, execFileSync } = require('child_process');

const pwDir = process.env.JOURNEY_PLAYWRIGHT;
if (!pwDir) {
  console.error('set JOURNEY_PLAYWRIGHT to a playwright package directory');
  process.exit(2);
}
const { chromium } = require(pwDir);
const { expect } = require(path.join(pwDir, 'test'));

const [cmd, ...args] = process.argv.slice(2);

async function launch([port, profileDir, pidFile]) {
  fs.mkdirSync(profileDir, { recursive: true });
  const child = spawn(chromium.executablePath(), [
    '--headless=new',
    `--remote-debugging-port=${port}`,
    `--user-data-dir=${profileDir}`,
    '--no-first-run',
    '--no-default-browser-check',
    '--window-size=1440,1000',
    'about:blank',
  ], { detached: true, stdio: 'ignore' });
  child.unref();
  fs.writeFileSync(pidFile, String(child.pid));
  console.log(JSON.stringify({ launched: true, pid: child.pid, port: Number(port) }));
}

async function step([port, label, codeFile, transcript, shot]) {
  const code = fs.readFileSync(codeFile, 'utf8');
  const browser = await chromium.connectOverCDP(`http://127.0.0.1:${port}`);
  const context = browser.contexts()[0];
  // JOURNEY_PAGE_MATCH picks the tab whose URL starts with it, so two tools can
  // keep their own page state; with no match a fresh tab is opened.
  const match = process.env.JOURNEY_PAGE_MATCH;
  const pages = context.pages();
  const page = (match
    ? pages.find((p) => p.url().startsWith(match)) || pages.find((p) => p.url() === 'about:blank')
    : pages[0]) || await context.newPage();
  await page.setViewportSize({ width: 1440, height: 1000 });
  const ctx = {
    aria: (sel = 'body') => page.locator(sel).ariaSnapshot(),
    text: (sel) => page.locator(sel).innerText(),
  };
  const AsyncFunction = Object.getPrototypeOf(async () => {}).constructor;
  const run = new AsyncFunction('page', 'expect', 'ctx', code);
  const startedAt = new Date().toISOString();
  const t0 = Date.now();
  const entry = { label, startedAt, code };
  try {
    entry.result = await run(page, expect, ctx);
    entry.ok = true;
  } catch (e) {
    entry.ok = false;
    entry.error = String(e && e.stack ? e.stack : e);
  }
  entry.ms = Date.now() - t0;
  entry.url = page.url();
  if (shot) {
    await page.screenshot({ path: shot, fullPage: false });
    entry.screenshot = path.basename(shot);
  }
  fs.appendFileSync(transcript, JSON.stringify(entry) + '\n');
  console.log(JSON.stringify({ label, ok: entry.ok, ms: entry.ms, url: entry.url, result: entry.result, error: entry.error }, null, 2));
  process.exit(entry.ok ? 0 : 1);
}

function stop([pidFile]) {
  const pid = fs.readFileSync(pidFile, 'utf8').trim();
  execFileSync('taskkill', ['/PID', pid, '/T', '/F'], { stdio: 'inherit' });
  console.log(JSON.stringify({ stopped: true, pid: Number(pid) }));
}

const commands = { launch, step, stop };
if (!commands[cmd]) {
  console.error('usage: launch | step | stop');
  process.exit(2);
}
Promise.resolve(commands[cmd](args)).catch((e) => {
  console.error(e);
  process.exit(1);
});
