#!/usr/bin/env node
/*
 * Story `:play-script` CI-as-test runner (rf2-3qcxk + rf2-tl7zk).
 *
 * Compiles the counter-with-stories Story testbed, serves it, opens
 * the Story shell in Playwright, enumerates every registered variant
 * whose body carries a non-empty `:play-script` or `:plays` slot (via
 * the `window.__rf2_story_ci` global installed by
 * `re-frame.story.play.ci-runner/install-ci-hooks!`), navigates the
 * shell to each variant, waits for each play's terminal status
 * (`:pass` / `:fail`), and prints a per-play report.
 *
 * rf2-tl7zk multi-play
 * --------------------
 * The runner consumes `ciContext().rows` — one row per PLAY. A variant
 * declaring `:plays` of size N produces N rows; a single-script
 * `:play-script` variant produces ONE row. The runner navigates once
 * per variant, then triggers each non-auto-run play via the CI hook's
 * `runPlay(variantId, playKey)` entry-point. Per-play terminal state
 * is read via `readPlayRunState(variantId, playKey)`.
 *
 * EXPECTED-FAIL contract
 * ----------------------
 * Authoring a deliberately-failing variant / play is the normal way
 * to gate Story's failure path under CI. The runner reads the expected
 * status from the variant id AND the play key — either token can mark
 * the row as expected-fail:
 *
 *   - any variant id or play-key containing `failing` / `expected-fail`
 *     is treated as expected-fail; the runner asserts `:fail`
 *   - everything else asserts `:pass`
 *
 * The seeded fixtures in the testbed:
 *   :story.counter-play-script/passing            → expects :pass
 *   :story.counter-play-script/failing            → expects :fail
 *
 * Exit code: 0 if every play matched its expected status; 1 if any
 * play deviated.
 */

'use strict';

const { spawnSync } = require('child_process');
const fs = require('fs');
const path = require('path');
const {
  createHarnessCleanup,
  spawnHarnessProcess,
  waitForHttpReady,
} = require('../../implementation/scripts/lib/local-browser-harness.cjs');
const { resolveStoryFeatureLoadPort } = require('./story-feature-load-port.cjs');

const REPO_ROOT = path.resolve(__dirname, '..', '..');
const IMPL_ROOT = path.join(REPO_ROOT, 'implementation');
const OUT_ROOT = path.join(IMPL_ROOT, 'out', 'examples');
const TESTBED_BUILD = 'examples/counter-with-stories';
const TESTBED_DIR_NAME = 'counter-with-stories';
const TESTBED_HTML = path.join(
  REPO_ROOT,
  'tools',
  'story',
  'testbeds',
  'counter_with_stories',
  'index.html',
);
const TESTBED_OUT = path.join(OUT_ROOT, TESTBED_DIR_NAME);
// Path-only (no hash) so we can compose `?variant=...#/stories` URLs
// per the share-link convention — query params MUST come BEFORE the
// hash route or `window.location.search` will be empty and the share
// hydrator will skip the variant select.
const TESTBED_BASE = `/${TESTBED_DIR_NAME}/`;
const STORY_FRAGMENT = '#/stories';
const READY_TIMEOUT_MS = 30000;
const TERMINAL_TIMEOUT_MS = Number(
  process.env.STORY_PLAY_SCRIPT_TERMINAL_TIMEOUT_MS || 30000,
);
const VERBOSE = process.env.RF2_VERBOSE_TESTS === '1';

const HTTP_SERVER_BIN = require.resolve('http-server/bin/http-server', {
  paths: [IMPL_ROOT],
});
const { chromium } = require(require.resolve('playwright', { paths: [IMPL_ROOT] }));

const cleanup = createHarnessCleanup();
cleanup.installSignalHandlers();

// Toggled true once `main()` resolves to its exit code, so the
// http-server's forced-shutdown `exit` event doesn't print a noisy
// "exited unexpectedly" line during normal teardown.
let exiting = false;

function log(line) {
  process.stdout.write(`${line}\n`);
}

function compileTestbed() {
  const isWin = process.platform === 'win32';
  const npx = isWin ? 'npx.cmd' : 'npx';
  const shadowArgs = ['shadow-cljs', 'compile', TESTBED_BUILD];
  const command = isWin ? 'cmd.exe' : npx;
  const args = isWin
    ? ['/d', '/s', '/c', [npx, ...shadowArgs].join(' ')]
    : shadowArgs;
  const result = spawnSync(command, args, {
    cwd: IMPL_ROOT,
    encoding: 'utf8',
  });
  const output = `${result.stdout || ''}${result.stderr || ''}`;
  if (VERBOSE || result.status !== 0) {
    process.stdout.write(output);
  }
  if (result.status !== 0) {
    console.error(`> ${npx} ${shadowArgs.join(' ')}`);
    throw new Error(`shadow-cljs compile failed (exit ${result.status})`);
  }
}

function stageTestbedHtml() {
  if (!fs.existsSync(TESTBED_OUT)) {
    throw new Error(`Build output dir missing: ${TESTBED_OUT}`);
  }
  if (!fs.existsSync(TESTBED_HTML)) {
    throw new Error(`HTML source missing: ${TESTBED_HTML}`);
  }
  fs.copyFileSync(TESTBED_HTML, path.join(TESTBED_OUT, 'index.html'));
}

/**
 * Classify the expected terminal status for a (variant-id, play-key)
 * pair. Authors mark a fixture as expected-fail by including `failing`
 * or `expected-fail` in the variant id OR the play's :name; everything
 * else defaults to expected-pass.
 *
 * Symmetric with re-frame.story.play.ci-runner/play-script-summary:
 * the classification is over identifiers (not body content), so the
 * runner can pre-compute the verdict before the Story shell boots.
 *
 * rf2-tl7zk: accepts an optional `playKey` so multi-play rows can
 * mark a specific play as expected-fail without forcing the entire
 * variant id to carry the marker.
 */
function expectedStatusFor(variantId, playKey) {
  const re = /(?:failing|expected[-_]fail)/;
  if (re.test(String(variantId).toLowerCase())) return 'fail';
  if (playKey && re.test(String(playKey).toLowerCase())) return 'fail';
  return 'pass';
}

function variantIdToKw(idStr) {
  // The CI hook serialises `:story.foo/bar` as `"story.foo/bar"` (no
  // leading colon); the Playwright runner reads that string back to
  // build the URL search-param value the Story shell expects.
  return String(idStr);
}

/**
 * Navigate the Story shell to `variantId` using the same hash route
 * the human shell uses (`#/stories?variant=story.foo/bar`). The
 * search params are placed AFTER the hash because the shell parses
 * them out of `window.location.hash`.
 *
 * Falls back to the share-link affordance (`select-variant` event)
 * if the URL parse path is unreachable.
 */
async function navigateToVariant(page, baseUrl, variantId) {
  const variantStr = variantIdToKw(variantId);
  // Share-link convention: search params BEFORE the hash route, so
  // `window.location.search` carries `?variant=...` and the share
  // hydrator picks it up on shell mount.
  const target = `${baseUrl}${TESTBED_BASE}?variant=${encodeURIComponent(variantStr)}${STORY_FRAGMENT}`;
  // Always full-load (not reload) — each variant gets a fresh shell
  // mount so the auto-run fires deterministically without inheriting
  // previous run-state.
  await page.goto(target, { waitUntil: 'load' });
  await page.evaluate(() => {
    try {
      localStorage.setItem('re-frame.story/seen-help-v1', '1');
    } catch (_) {
      /* ignore */
    }
  });
}

async function readRunStateOnce(page, variantId) {
  return page.evaluate((vid) => {
    const ci = window.__rf2_story_ci;
    if (!ci || typeof ci.readRunState !== 'function') return null;
    try {
      return ci.readRunState(vid);
    } catch (e) {
      return { error: String(e && e.message ? e.message : e) };
    }
  }, variantIdToKw(variantId));
}

/**
 * rf2-tl7zk: per-play state read for multi-play rows.
 */
async function readPlayRunStateOnce(page, variantId, playKey) {
  return page.evaluate(
    ({ vid, pk }) => {
      const ci = window.__rf2_story_ci;
      if (!ci || typeof ci.readPlayRunState !== 'function') return null;
      try {
        return ci.readPlayRunState(vid, pk || '');
      } catch (e) {
        return { error: String(e && e.message ? e.message : e) };
      }
    },
    { vid: variantIdToKw(variantId), pk: playKey || '' },
  );
}

async function waitForTerminalState(page, variantId) {
  const deadline = Date.now() + TERMINAL_TIMEOUT_MS;
  let last = null;
  while (Date.now() < deadline) {
    last = await readRunStateOnce(page, variantId);
    if (last && (last.status === 'pass' || last.status === 'fail')) {
      return last;
    }
    await new Promise((r) => setTimeout(r, 100));
  }
  return last;
}

/**
 * rf2-tl7zk: wait for the per-(variantId, playKey) terminal state.
 */
async function waitForPlayTerminalState(page, variantId, playKey) {
  const deadline = Date.now() + TERMINAL_TIMEOUT_MS;
  let last = null;
  while (Date.now() < deadline) {
    last = await readPlayRunStateOnce(page, variantId, playKey);
    if (last && (last.status === 'pass' || last.status === 'fail')) {
      return last;
    }
    await new Promise((r) => setTimeout(r, 100));
  }
  return last;
}

/**
 * rf2-tl7zk: trigger a specific play to run. The CI hook's `runPlay`
 * picks the spec off the variant body and drives the runner.
 */
async function triggerPlay(page, variantId, playKey) {
  return page.evaluate(
    ({ vid, pk }) => {
      const ci = window.__rf2_story_ci;
      if (!ci || typeof ci.runPlay !== 'function') return false;
      try {
        ci.runPlay(vid, pk || '');
        return true;
      } catch (e) {
        return false;
      }
    },
    { vid: variantIdToKw(variantId), pk: playKey || '' },
  );
}

async function discoverVariants(page) {
  return page.evaluate(() => {
    const ci = window.__rf2_story_ci;
    if (!ci || typeof ci.listVariants !== 'function') {
      return { error: '__rf2_story_ci global not installed' };
    }
    try {
      return { variants: ci.listVariants(), context: ci.ciContext() };
    } catch (e) {
      return { error: String(e && e.message ? e.message : e) };
    }
  });
}

async function bootShell(page, baseUrl) {
  await page.goto(`${baseUrl}${TESTBED_BASE}${STORY_FRAGMENT}`, { waitUntil: 'load' });
  await page.evaluate(() => {
    try {
      localStorage.setItem('re-frame.story/seen-help-v1', '1');
    } catch (_) {
      /* ignore */
    }
  });
  // Reload so the localStorage prime takes effect before help dialog
  // logic decides whether to render the overlay.
  await page.reload({ waitUntil: 'load' });
  // Wait for the CI hook to install. The hook fires at `core/run`
  // time, which is before the first `on-hash-change!` mounts the
  // shell — so the global is reliably present by the time the page
  // is fully loaded.
  const deadline = Date.now() + 10000;
  while (Date.now() < deadline) {
    const ready = await page.evaluate(() => Boolean(window.__rf2_story_ci));
    if (ready) return;
    await new Promise((r) => setTimeout(r, 100));
  }
  throw new Error('CI hook (window.__rf2_story_ci) did not install within 10s');
}

function summariseResults(results) {
  const failures = results.filter((r) => !r.matched);
  const lines = [];
  lines.push('');
  lines.push('=== Story :play-script CI summary ===');
  for (const r of results) {
    const tag = r.matched ? 'OK  ' : 'MISS';
    const actual = (r.runState && r.runState.status) || 'no-state';
    const playLabel = r.playKey ? `[${r.playKey}]` : '';
    lines.push(
      `${tag} ${r.variantId}${playLabel}  expected=${r.expected} actual=${actual}  steps=${r.runState ? r.runState.total : '?'}`,
    );
    if (!r.matched) {
      // The CLJS `project-state` serialises `:passed?` as the literal
      // string key `"passed?"` (keyword → name preservation); read it
      // via bracket-access so the `?` survives the JS identifier rules.
      // Older drafts read `.passed` and silently dropped every failure
      // line — leaving only the row-level MISS with no step diagnostic.
      const fails = (r.runState && r.runState.results) || [];
      for (const stepR of fails) {
        if (stepR['passed?'] === false) {
          const expected = stepR.expected ? ` expected=${stepR.expected}` : '';
          const actual   = stepR.actual   ? ` actual=${stepR.actual}`     : '';
          lines.push(
            `     step ${stepR.idx} ${stepR.type} — ${stepR.message || '(no message)'}${expected}${actual}`,
          );
        }
      }
    }
  }
  lines.push('');
  lines.push(
    `Ran ${results.length} :play-script row(s). ${failures.length} unexpected outcomes.`,
  );
  return { lines: lines.join('\n'), failures };
}

/**
 * rf2-tl7zk: group ci-rows by variant id so we navigate ONCE per
 * variant and then drive each row's play locally.
 */
function groupRowsByVariant(rows) {
  const grouped = new Map();
  for (const row of rows) {
    const vid = row['variant-id'];
    if (!grouped.has(vid)) grouped.set(vid, []);
    grouped.get(vid).push(row);
  }
  return grouped;
}

async function runAllVariants(browser, baseUrl) {
  const context = await browser.newContext();
  const page = await context.newPage();
  const browserMessages = [];
  page.on('console', (msg) => {
    if (VERBOSE) browserMessages.push(`[browser:${msg.type()}] ${msg.text()}`);
  });
  page.on('pageerror', (err) => {
    browserMessages.push(`[browser:pageerror] ${err.message}`);
  });

  try {
    await bootShell(page, baseUrl);
    const discovery = await discoverVariants(page);
    if (discovery.error || !Array.isArray(discovery.variants)) {
      throw new Error(`variant discovery failed: ${discovery.error || JSON.stringify(discovery)}`);
    }
    const variants = discovery.variants;
    // rf2-tl7zk: prefer the per-play `rows` enumeration; fall back to
    // the legacy per-variant shape when the CI hook is older.
    const rows =
      discovery.context && Array.isArray(discovery.context.rows)
        ? discovery.context.rows
        : variants.map((vid) => ({ 'variant-id': vid, 'play-key': null, name: null }));
    log(
      `Discovered ${variants.length} variant(s) with :play-script / :plays — ${rows.length} play row(s) total`,
    );
    if (VERBOSE) {
      for (const r of rows) {
        log(
          `  - ${r['variant-id']}  play=${r['play-key'] || '(default)'}  steps=${r['script-len']}  auto=${r['auto-run?']}`,
        );
      }
    }
    if (rows.length === 0) {
      // No fixtures with :play-script / :plays — exit clean. The npm gate
      // is fail-loud only on UNEXPECTED outcomes; zero rows means no
      // signal either way.
      log('No :play-script / :plays rows registered. Nothing to assert.');
      return 0;
    }

    const results = [];
    const grouped = groupRowsByVariant(rows);
    for (const [vid, variantRows] of grouped.entries()) {
      let navOk = true;
      try {
        await navigateToVariant(page, baseUrl, vid);
      } catch (err) {
        navOk = false;
        for (const row of variantRows) {
          results.push({
            variantId: vid,
            playKey: row['play-key'],
            expected: expectedStatusFor(vid, row['play-key']),
            matched: false,
            runState: { status: 'navigate-error', message: err.message },
          });
        }
      }
      if (!navOk) continue;

      // For each play row on this variant: if it auto-ran, just wait
      // for the terminal state; otherwise, trigger it then wait.
      for (const row of variantRows) {
        const playKey = row['play-key'];
        const expected = expectedStatusFor(vid, playKey);

        if (!row['auto-run?']) {
          // Manual play — trigger it explicitly. The CI hook routes
          // through runner-events/run-play! which sets the active
          // play + drives the runner; we read per-play state below.
          await triggerPlay(page, vid, playKey);
        }

        const runState = playKey
          ? await waitForPlayTerminalState(page, vid, playKey)
          : await waitForTerminalState(page, vid);
        const actual = (runState && runState.status) || null;
        results.push({
          variantId: vid,
          playKey,
          expected,
          matched: actual === expected,
          runState,
        });
      }
    }

    const { lines, failures } = summariseResults(results);
    log(lines);
    if (failures.length > 0 && browserMessages.length > 0) {
      log('--- browser diagnostics ---');
      for (const msg of browserMessages.slice(-40)) log(msg);
    }
    return failures.length === 0 ? 0 : 1;
  } finally {
    await context.close();
  }
}

async function main() {
  const port = await resolveStoryFeatureLoadPort({
    env: process.env,
    repoRoot: REPO_ROOT,
  });
  const baseUrl = `http://127.0.0.1:${port}`;

  compileTestbed();
  stageTestbedHtml();

  const server = cleanup.trackProcess(
    spawnHarnessProcess(
      process.execPath,
      [HTTP_SERVER_BIN, OUT_ROOT, '-p', String(port), '-s', '-c-1'],
      {
        cwd: IMPL_ROOT,
        stdio: ['ignore', 'pipe', 'pipe'],
      },
    ),
  );

  let serverDown = false;
  const serverOutput = [];
  const captureServerOutput = (chunk, stream) => {
    const text = chunk.toString();
    serverOutput.push(
      ...text
        .split(/\r?\n/)
        .filter(Boolean)
        .map((line) => `[http-server:${stream}] ${line}`),
    );
    if (VERBOSE) process[stream].write(text);
  };
  server.stdout.on('data', (chunk) => captureServerOutput(chunk, 'stdout'));
  server.stderr.on('data', (chunk) => captureServerOutput(chunk, 'stderr'));
  server.on('exit', (code, signal) => {
    serverDown = true;
    // The server is forcefully terminated during cleanup, which makes
    // this `exit` fire with a non-zero code after `main()` has already
    // returned its own exit code. Suppress the noise in that path;
    // surface real mid-run crashes loudly.
    if (code !== 0 && code !== null && !exiting) {
      console.error(
        `http-server exited unexpectedly (code=${code}, signal=${signal}).`,
      );
    }
  });

  const ready = await waitForHttpReady(port, Date.now() + READY_TIMEOUT_MS, {
    isAborted: () => serverDown,
  });
  if (!ready || serverDown) {
    console.error(
      `http-server did not become reachable on :${port} within ${READY_TIMEOUT_MS}ms.`,
    );
    for (const line of serverOutput.slice(-40)) console.error(line);
    return 1;
  }

  const browser = await chromium.launch({ headless: true });
  cleanup.addCleanup(async () => {
    try {
      await browser.close();
    } catch (_) {
      /* ignore */
    }
  });

  try {
    return await runAllVariants(browser, baseUrl);
  } finally {
    await browser.close();
  }
}

main()
  .then(async (code) => {
    exiting = true;
    await cleanup.cleanup();
    process.exit(code == null ? 1 : code);
  })
  .catch(async (err) => {
    exiting = true;
    console.error(err && err.stack ? err.stack : err);
    await cleanup.cleanup();
    process.exit(1);
  });

module.exports = {
  expectedStatusFor,
};
