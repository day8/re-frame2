#!/usr/bin/env node
/*
 * Playwright runner for the adapter smokes.
 *
 * Each surface is built by shadow-cljs into out/examples/<name>/main.js
 * and is paired with a hand-written index.html (staged into the same
 * directory by the orchestrator). This runner spins up a Chromium
 * browser and executes the spec.cjs files declared in the shared
 * ADAPTER_SMOKES manifest (adapter-smoke-filter.cjs) — the same manifest
 * + the same `selectEntries` the orchestrator used to compile/stage, so
 * the selected set is identical across both phases for any
 * ADAPTER_SMOKE_FILTER shape (rf2-l72e2). Before running, the runner
 * reconciles that manifest against the spec.cjs files actually on disk
 * under ADAPTER_SMOKE_SPEC_ROOTS, failing loudly on drift in either
 * direction. Each spec navigates to the surface's URL and asserts a
 * user-visible behaviour (initial render + an interaction +
 * post-interaction state).
 *
 * Test surface inventory (the `examples/` tree is TEST-FREE; framework-
 * testbed assertions live as CLJS/JVM unit tests):
 *
 *   - 3 adapter smokes   (implementation/adapters/<name>/testbed/spec.cjs)
 *     Reagent / UIx / Helix mount + dispatch + render.
 *
 * Real-regression coverage for everything else lives in: substrate
 * contract tests under `npm run test:cljs`, the Xray feature-matrix
 * gate (`npm run test:xray-feature-gate`), bundle-isolation
 * (`npm run test:bundle-isolation`), the perf-bundle gate
 * (`npm run test:perf-bundle`), and mcp-conformance.
 *
 * Why a hand-rolled runner rather than @playwright/test:
 *   - Mirrors the run-browser-tests.cjs pattern — same dependencies
 *     (`playwright`), same console-tap + pageerror discipline, same
 *     exit-code contract.
 *   - Avoids dragging in @playwright/test as a separate devDep when
 *     the spec surface is small and the orchestration is straightforward.
 *
 * Spec format: each spec exports an object
 *   {
 *     name:  string,                         // human-readable
 *     url:   string,                         // path under the static server root
 *     run:   async (page) => void,           // Playwright assertions
 *   }
 *
 * There is no opt-out: every selected spec runs. Opting an example out of
 * the smoke surface means removing it from the ADAPTER_SMOKES manifest
 * (adapter-smoke-filter.cjs) / deleting its spec, not flagging the spec.
 *
 * Exit code: 0 if every selected spec's `run` resolves; 1 if any spec
 * throws or a pageerror fires during a spec.
 */

const path = require('path');
const {
  ADAPTER_SMOKES,
  ADAPTER_SMOKE_SPEC_ROOTS,
  parseFilterPatterns,
  selectEntries,
  listSpecFiles,
  reconcile,
} = require('./adapter-smoke-filter.cjs');

// playwright is a devDependency of implementation/package.json — there
// is no examples/package.json by design. Resolve playwright
// out of implementation/node_modules explicitly so the runner can be
// invoked from any cwd.
// __dirname is <repo>/implementation/adapters/scripts, so IMPL_ROOT is two
// levels up.
const IMPL_ROOT = path.resolve(__dirname, '..', '..');
const { chromium } = require(require.resolve('playwright', { paths: [IMPL_ROOT] }));

// ADAPTER_SMOKE_BASE_URL is always set by the orchestrator
// (serve-and-run-adapter-smokes.cjs) to the port it actually resolved.
// The fallback below only applies when this runner is invoked
// standalone; it tracks the orchestrator's default port (8050 — in the
// examples orchestrator's owned 805x band, clear of the top-level
// :dev-http set; see examples-port.cjs and the OWNED-RANGE PORT MAP in
// implementation/scripts/dev-testbed.cjs).
const BASE_URL = process.env.ADAPTER_SMOKE_BASE_URL || 'http://127.0.0.1:8050';
// `ADAPTER_SMOKE_FILTER` narrows the spec set. When set, the runner
// executes exactly the specs of the ADAPTER_SMOKES entries the shared
// `selectEntries` picks. Composes with the orchestrator's compile/stage
// filter — both phases call the SAME `selectEntries` over the SAME
// ADAPTER_SMOKES manifest, so a narrow run exercises identical surfaces
// end-to-end regardless of
// whether the filter is build-id-shaped (`adapters/reagent-testbed`,
// `reagent-testbed`) or path-shaped (`adapters/reagent/testbed`,
// `reagent/testbed`). Unset (or empty) = the full sweep.
//
// Comma-separated alternatives are OR-matched. The previous design
// substring-matched the filter against re-discovered absolute spec paths
// in a string space that didn't bridge the build-id `<name>-testbed`
// segment to the path `<name>/testbed` segments, so a build-id-shaped
// filter matched zero specs after the orchestrator had already staged
// the surface (rf2-l72e2). Selecting from the shared manifest removes
// that asymmetry.
//
// The substring-trap protection (a bare `shop` must not be shadowed by a
// worktree-name substring — see the saved-memory note) is preserved:
// `selectEntries` still substring-matches, and patterns are matched only
// against an entry's REPO-STABLE identities — its build id and its
// repo-relative spec path — never the absolute spec path or any other
// filesystem prefix (rf2-n4nc2o).
const FILTER = (process.env.ADAPTER_SMOKE_FILTER || '').trim();
const FILTER_PATTERNS = parseFilterPatterns(FILTER);
const TIMEOUT_MS = parseInt(process.env.EXAMPLE_SPEC_TIMEOUT_MS || '30000', 10);
const { isVerboseTests } = require(path.join(
  IMPL_ROOT,
  'scripts',
  'lib',
  'browser-test-report.cjs',
));
const VERBOSE_TESTS = isVerboseTests();

// Spec discovery (isSpecFile/listSpecFiles) and the manifest-vs-disk
// reconciliation (reconcile) live in the shared adapter-smoke-filter.cjs
// module — the one that already owns the ADAPTER_SMOKES manifest — so this
// runner and the fast-tier _adapter-smoke-filter.test.cjs exercise the SAME
// real code instead of two copies that could drift (rf2-qf45gu). Specs live
// alongside the adapter testbed they exercise: each adapter (Reagent / UIx /
// Helix) ships `implementation/adapters/<name>/testbed/spec.cjs`.

function withTimeout(promise, ms, label) {
  let timer;
  const timeout = new Promise((_, reject) => {
    timer = setTimeout(
      () => reject(new Error(`Spec '${label}' timed out after ${ms}ms`)),
      ms,
    );
  });
  return Promise.race([promise, timeout]).finally(() => clearTimeout(timer));
}

(async () => {
  // Reconcile the ADAPTER_SMOKES manifest against the spec.cjs files
  // actually on disk under ADAPTER_SMOKE_SPEC_ROOTS. This catches drift in
  // either direction — a renamed/removed smoke still listed in the
  // manifest, or a new spec.cjs added under implementation/adapters/
  // without a matching manifest entry — instead of silently exercising the
  // wrong set.
  const discovered = listSpecFiles(ADAPTER_SMOKE_SPEC_ROOTS);
  const declared = ADAPTER_SMOKES.map((e) => e.specPath);
  const { missing, undeclared } = reconcile(declared, discovered);
  if (missing.length > 0) {
    console.error(
      `ADAPTER_SMOKES manifest references spec(s) not on disk:\n  ${missing.join('\n  ')}\n` +
        `Fix implementation/adapters/scripts/adapter-smoke-filter.cjs (a renamed/removed smoke?).`,
    );
    process.exit(1);
  }
  if (undeclared.length > 0) {
    console.error(
      `Found spec(s) under ${ADAPTER_SMOKE_SPEC_ROOTS.join(', ')} not declared in the ADAPTER_SMOKES manifest:\n  ${undeclared.join('\n  ')}\n` +
        `Add an entry to implementation/adapters/scripts/adapter-smoke-filter.cjs (or remove the stray spec).`,
    );
    process.exit(1);
  }

  // Select the spec set from the SAME manifest + SAME `selectEntries`
  // the orchestrator used to compile/stage, so the selected set is
  // identical in both phases for every filter shape (rf2-l72e2).
  const selected = selectEntries(FILTER_PATTERNS);
  if (selected.length === 0) {
    if (FILTER) {
      console.error(
        `ADAPTER_SMOKE_FILTER='${FILTER}' matched zero smokes (of ${ADAPTER_SMOKES.length} declared).`,
      );
    } else {
      console.error(`No smokes declared in the ADAPTER_SMOKES manifest.`);
    }
    process.exit(1);
  }

  const specFiles = selected.map((e) => e.specPath);
  const specs = specFiles.map((file) => {
    const mod = require(file);
    if (!mod || typeof mod.run !== 'function' || typeof mod.url !== 'string') {
      throw new Error(`Bad spec module at ${file}: must export {name, url, run}`);
    }
    return { ...mod, file };
  });

  const browser = await chromium.launch({ headless: true });
  const results = [];
  let anyFailed = false;

  // Silent-on-success: buffer per-spec narration into `lines` and
  // only flush it for specs that FAIL. Green specs emit nothing; the
  // final summary is a single counts line.
  for (const spec of specs) {
    const label = spec.name || path.basename(spec.file);
    const lines = [];
    const log = (s) => lines.push(s);
    const flush = () => {
      for (const ln of lines) console.log(ln);
    };

    log(`\n=== ${label} ===`);

    const context = await browser.newContext();
    const page = await context.newPage();

    page.on('console', (msg) => {
      const text = msg.text();
      log(`[browser:${msg.type()}] ${text}`);
    });
    let pageErrored = null;
    page.on('pageerror', (err) => {
      pageErrored = err;
      log(`[browser:pageerror] ${err.message}`);
      if (err.stack) log(err.stack);
    });

    const fullUrl = spec.url.startsWith('http') ? spec.url : BASE_URL + spec.url;
    log(`Navigating to ${fullUrl}`);

    let passed = false;
    let failure = null;
    try {
      await page.goto(fullUrl, { waitUntil: 'load', timeout: TIMEOUT_MS });
      await withTimeout(spec.run(page), TIMEOUT_MS, label);
      if (pageErrored) {
        throw new Error(`Page emitted an uncaught error: ${pageErrored.message}`);
      }
      passed = true;
    } catch (err) {
      failure = err;
      anyFailed = true;
      log(`FAIL ${label}: ${err.message}`);
      if (err.stack) log(err.stack);
    } finally {
      await context.close();
    }

    if (!passed || VERBOSE_TESTS) flush();
    results.push({ label, passed, failure });
  }

  await browser.close();

  // Silent-on-success: green runs emit the canonical single-line
  // summary; red runs also list the failing labels for quick triage.
  const failedCount = results.filter((r) => !r.passed).length;
  if (anyFailed) {
    console.log('\n=== summary ===');
    for (const r of results) {
      console.log(`${r.passed ? 'PASS' : 'FAIL'}  ${r.label}`);
    }
  }
  console.log(
    `Ran ${results.length} adapter-smoke specs. ${failedCount} failures.`,
  );

  process.exit(anyFailed ? 1 : 0);
})().catch((err) => {
  console.error(err);
  process.exit(1);
});
