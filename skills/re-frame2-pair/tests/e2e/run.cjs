/*
 * tests/e2e/run.cjs — end-to-end runner for re-frame2-pair against the live
 * fixture app (rf2-cxik).
 *
 * Sequenced:
 *
 *   1. Skip if RE_FRAME2_PAIR_FIXTURE_URL is unset and the default localhost
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
const {
  createDiagnostics,
  flushDiagnostics,
  isVerboseTests,
} = require('./_helpers.cjs');

const HERE = __dirname;
const SKILL_ROOT = path.resolve(HERE, '..', '..');
const VERBOSE_TESTS = isVerboseTests();

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
  if (process.env.RE_FRAME2_PAIR_FIXTURE_DIR) return process.env.RE_FRAME2_PAIR_FIXTURE_DIR;
  const guess = path.join(SKILL_ROOT, 'tests', 'fixture');
  return fs.existsSync(path.join(guess, 'shadow-cljs.edn')) ? guess : null;
}

function pickFixtureUrl() {
  return process.env.RE_FRAME2_PAIR_FIXTURE_URL || 'http://localhost:8030';
}

async function main() {
  const fixtureDir = pickFixtureDir();
  const fixtureUrl = pickFixtureUrl();
  const diagnostics = createDiagnostics({ verbose: VERBOSE_TESTS });
  diagnostics.add(`[re-frame2-pair-e2e] fixture URL: ${fixtureUrl}`);
  diagnostics.add(`[re-frame2-pair-e2e] fixture dir: ${fixtureDir || '(not found)'}`);

  const fixtureUp = await pingFixture(fixtureUrl);
  if (!fixtureUp) {
    // Soft-skip — not a CI failure. The e2e gate is opt-in.
    console.log(
      '[re-frame2-pair-e2e] skipped — fixture not available at ' +
        fixtureUrl +
        ' (set RE_FRAME2_PAIR_FIXTURE_URL to override; start the fixture with '
        + '`cd tests/fixture && npx shadow-cljs watch app`).',
    );
    process.exit(0);
  }
  diagnostics.add('[re-frame2-pair-e2e] fixture reachable');

  const ctx = { fixtureDir, fixtureUrl, skillRoot: SKILL_ROOT, diagnostics };

  const specs = fs
    .readdirSync(HERE)
    .filter((f) => f.endsWith('.e2e.cjs'))
    .sort();

  if (specs.length === 0) {
    console.error('[re-frame2-pair-e2e] no spec files found in ' + HERE);
    flushDiagnostics(diagnostics);
    process.exit(1);
  }

  let failures = 0;
  for (const spec of specs) {
    const specPath = path.join(HERE, spec);
    diagnostics.add('[re-frame2-pair-e2e] running ' + spec);
    try {
      const mod = require(specPath);
      if (typeof mod.run !== 'function') {
        throw new Error('spec did not export `run(ctx)`');
      }
      await mod.run({ ...ctx, spec });
      diagnostics.add('[re-frame2-pair-e2e]   ' + spec + ' PASS');
    } catch (err) {
      failures += 1;
      console.error('[re-frame2-pair-e2e]   ' + spec + ' FAIL: ' + (err.message || err));
      diagnostics.add('[re-frame2-pair-e2e]   ' + spec + ' FAIL: ' + (err.stack || err), 'stderr');
    }
  }

  if (failures > 0) {
    console.error('[re-frame2-pair-e2e] ' + failures + ' failed');
    flushDiagnostics(diagnostics);
    process.exit(1);
  }
  console.log('[re-frame2-pair-e2e] ' + specs.length + ' passed');
}

main().catch((err) => {
  console.error(err);
  process.exit(1);
});
