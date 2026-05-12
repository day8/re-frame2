/*
 * tests/e2e/run.cjs — end-to-end runner for pair2 against the live
 * fixture app (rf2-cxik).
 *
 * Sequenced:
 *
 *   1. Skip if PAIR2_FIXTURE_URL is unset and the default localhost
 *      probe fails — the suite is e2e-only by design; the runtime/shim
 *      surfaces cover everything that doesn't need a browser.
 *   2. For each *.e2e.cjs spec next to this file: spawn it as a child,
 *      forward env vars, collect exit code.
 *   3. Aggregate; exit non-zero on any failure.
 *
 * Spec contract: each *.e2e.cjs exports `async function run(ctx)` where
 * ctx = { fixtureDir, fixtureUrl, skillRoot }. Specs throw to signal
 * failure; resolving cleanly = pass.
 */
'use strict';

const fs = require('fs');
const path = require('path');
const http = require('http');

const HERE = __dirname;
const SKILL_ROOT = path.resolve(HERE, '..', '..');

async function pingFixture(url, timeoutMs = 1500) {
  return new Promise((resolve) => {
    const req = http.get(url, (res) => {
      res.resume();
      resolve(res.statusCode >= 200 && res.statusCode < 500);
    });
    req.on('error', () => resolve(false));
    req.setTimeout(timeoutMs, () => {
      req.destroy();
      resolve(false);
    });
  });
}

function pickFixtureDir() {
  if (process.env.PAIR2_FIXTURE_DIR) return process.env.PAIR2_FIXTURE_DIR;
  const guess = path.join(SKILL_ROOT, 'tests', 'fixture');
  return fs.existsSync(path.join(guess, 'shadow-cljs.edn')) ? guess : null;
}

function pickFixtureUrl() {
  return process.env.PAIR2_FIXTURE_URL || 'http://localhost:8030';
}

async function main() {
  const fixtureDir = pickFixtureDir();
  const fixtureUrl = pickFixtureUrl();

  const fixtureUp = await pingFixture(fixtureUrl);
  if (!fixtureUp) {
    // Soft-skip — not a CI failure. The e2e gate is opt-in.
    console.log(
      '[pair2-e2e] skipped — fixture not available at ' +
        fixtureUrl +
        ' (set PAIR2_FIXTURE_URL to override; start the fixture with '
        + '`cd tests/fixture && npx shadow-cljs watch app`).',
    );
    process.exit(0);
  }

  const ctx = { fixtureDir, fixtureUrl, skillRoot: SKILL_ROOT };

  const specs = fs
    .readdirSync(HERE)
    .filter((f) => f.endsWith('.e2e.cjs'))
    .sort();

  if (specs.length === 0) {
    console.error('[pair2-e2e] no spec files found in ' + HERE);
    process.exit(1);
  }

  let failures = 0;
  for (const spec of specs) {
    const specPath = path.join(HERE, spec);
    console.log('[pair2-e2e] running ' + spec);
    try {
      const mod = require(specPath);
      if (typeof mod.run !== 'function') {
        throw new Error('spec did not export `run(ctx)`');
      }
      await mod.run(ctx);
      console.log('[pair2-e2e]   ' + spec + ' PASS');
    } catch (err) {
      failures += 1;
      console.error('[pair2-e2e]   ' + spec + ' FAIL: ' + (err.stack || err));
    }
  }

  if (failures > 0) {
    console.error('[pair2-e2e] ' + failures + ' failed');
    process.exit(1);
  }
  console.log('[pair2-e2e] ' + specs.length + ' passed');
}

main().catch((err) => {
  console.error(err);
  process.exit(1);
});
