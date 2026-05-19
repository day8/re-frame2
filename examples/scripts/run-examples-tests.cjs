#!/usr/bin/env node
/*
 * Playwright runner for the example apps.
 *
 * Each example is built by shadow-cljs into out/examples/<name>/main.js
 * and is paired with a hand-written index.html (staged into the same
 * directory by the orchestrator). This runner spins up a Chromium
 * browser and executes the *.spec.cjs files that sit alongside each
 * example's source (examples/<substrate>/<name>/<name>.spec.cjs, plus
 * examples/reagent/7Guis/<name>/<name>.spec.cjs for the per-example
 * 7GUIs sub-folders). Each spec navigates to the example's URL and
 * asserts a user-visible behaviour (initial render + an interaction +
 * post-interaction state).
 *
 * Why a hand-rolled runner rather than @playwright/test:
 *   - Mirrors the run-browser-tests.cjs pattern (PR #15) — same
 *     dependencies (`playwright`), same console-tap + pageerror
 *     discipline, same exit-code contract.
 *   - Avoids dragging in @playwright/test as a separate devDep when
 *     the spec surface is small and the orchestration is straightforward.
 *
 * Spec format: each spec exports an object
 *   {
 *     name:       string,                    // human-readable
 *     url:        string,                    // path under the static server root
 *     run:        async (page) => void,      // Playwright assertions
 *     skip:       string | false             // optional — when truthy, the
 *                                            //   spec is reported as SKIP
 *                                            //   with this string as the
 *                                            //   reason. Used when an
 *                                            //   example is shipped under
 *                                            //   construction (bead-tracked
 *                                            //   gap; rf2-5lbx introduced
 *                                            //   the convention for the
 *                                            //   slim adapter's pending
 *                                            //   interop seam).
 *     timeoutMs:  number | undefined         // optional — override the
 *                                            //   per-spec timeout (default
 *                                            //   `EXAMPLE_SPEC_TIMEOUT_MS`
 *                                            //   or 30000). Specs that
 *                                            //   exercise a long serial
 *                                            //   chain of waits on slow
 *                                            //   CI hardware (e.g.
 *                                            //   Story-Causa embed
 *                                            //   boot + multi-panel
 *                                            //   mount round-trip) can
 *                                            //   bump this with a
 *                                            //   rationale comment.
 *                                            //   Introduced for
 *                                            //   rf2-paskh.
 *   }
 *
 * Exit code: 0 if every spec's `run` resolves (skipped specs count as
 * non-failing); 1 if any spec throws or a pageerror fires during a spec.
 */

const path = require('path');
const fs = require('fs');

// playwright is a devDependency of implementation/package.json — there
// is no examples/package.json by design. Resolve playwright
// out of implementation/node_modules explicitly so the runner can be
// invoked from any cwd.
const IMPL_ROOT = path.resolve(__dirname, '..', '..', 'implementation');
const { chromium } = require(require.resolve('playwright', { paths: [IMPL_ROOT] }));

const BASE_URL = process.env.EXAMPLES_BASE_URL || 'http://127.0.0.1:8030';
// rf2-h9ut9 — narrow EXAMPLES_FILTER. When set, only spec files whose
// path includes the substring are loaded. Composes with the
// orchestrator's compile/stage filter so a narrow run only exercises
// the matched surface end-to-end. The substring is matched against
// the spec file's absolute path, so values like `realworld` match
// `examples/reagent/realworld/realworld.spec.cjs` cleanly. Unset (or
// empty) = the full sweep.
const FILTER = (process.env.EXAMPLES_FILTER || '').trim();
// rf2-mpa3x — normalize before substring-matching. Two cosmetic
// conventions diverge between build ids and spec paths:
//
//   1. Build ids use kebab-case (`examples/causa-rhs-smoke`); on-disk
//      dirs use snake_case (`tools/story/testbeds/causa_rhs_smoke/`).
//      A user naturally supplies the build-id form to EXAMPLES_FILTER,
//      so the literal substring `causa-rhs-smoke` failed to match the
//      underscore-bearing spec path. Map `_` → `-` on both sides.
//   2. Path separators differ across platforms (`\` on Windows, `/`
//      elsewhere). The saved-memory substring trap recommends the
//      path-separator-aware form (`testbeds/shop` or `testbeds\shop`)
//      so a bare `shop` isn't shadowed by a worktree-name substring.
//      Normalize `\` → `/` on both sides so `testbeds/shop` works on
//      every platform (Git Bash on Windows, Linux, macOS) without the
//      user remembering which slash to type.
//
// The substring-trap protection is preserved verbatim — the only
// change is collapsing two cosmetic variants before `.includes()`.
function normalizeForFilter(s) {
  return s.replace(/_/g, '-').replace(/\\/g, '/');
}
function matchesFilter(filePath) {
  if (FILTER === '') return true;
  return normalizeForFilter(filePath).includes(normalizeForFilter(FILTER));
}
// __dirname is <repo>/examples/scripts; the example tree sits at
// <repo>/examples (one level up). rf2-p8f2s broadened discovery to
// include tool-owned testbeds under <repo>/tools/<tool>/testbeds/ and
// the top-level <repo>/testbeds/ (Tier 1+ shared framework-behavior
// surfaces, future).
const REPO_ROOT = path.resolve(__dirname, '..', '..');
const SPEC_ROOTS = [
  path.join(REPO_ROOT, 'examples'),
  path.join(REPO_ROOT, 'tools'),
  path.join(REPO_ROOT, 'testbeds'),
  // rf2-eceuv — per-adapter testbeds live with their adapter under
  // implementation/adapters/<name>/testbed/. Each carries its own
  // spec.cjs (one per adapter); the orchestrator stages each bundle
  // under /adapter-testbeds/<name>/ on the static server.
  path.join(REPO_ROOT, 'implementation', 'adapters'),
];
const TIMEOUT_MS = parseInt(process.env.EXAMPLE_SPEC_TIMEOUT_MS || '30000', 10);
const { isVerboseTests } = require(path.join(
  IMPL_ROOT,
  'scripts',
  'lib',
  'browser-test-report.cjs',
));
const VERBOSE_TESTS = isVerboseTests();

// Specs live alongside the example or testbed they exercise. Under
// examples/ they sit two or three levels deep with file shape
// `<name>.spec.cjs` (e.g. examples/reagent/counter/counter.spec.cjs).
// Under tools/<tool>/testbeds/<scenario>/ the convention (rf2-p8f2s) is
// the unprefixed `spec.cjs` — one spec per scenario directory, no name
// repetition. Walk every root and pick up any file matching either
// `*.spec.cjs` (legacy / examples convention) or exactly `spec.cjs`
// (testbed convention).
function isSpecFile(name) {
  return name === 'spec.cjs' || name.endsWith('.spec.cjs');
}

function listSpecFiles(roots) {
  const out = [];
  for (const root of roots) {
    if (!fs.existsSync(root)) continue;       // testbeds/ is optional
    const stack = [root];
    while (stack.length > 0) {
      const dir = stack.pop();
      for (const entry of fs.readdirSync(dir, { withFileTypes: true })) {
        const full = path.join(dir, entry.name);
        if (entry.isDirectory()) {
          // Skip node_modules dirs that may sit under tools/ in the
          // future (none today, but the walker should be defensive).
          if (entry.name === 'node_modules') continue;
          stack.push(full);
        } else if (entry.isFile() && isSpecFile(entry.name)) {
          out.push(full);
        }
      }
    }
  }
  return out.sort();
}

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
  const allSpecFiles = listSpecFiles(SPEC_ROOTS);
  // rf2-h9ut9 + rf2-mpa3x — apply EXAMPLES_FILTER substring match
  // against the absolute spec path so narrow runs only execute matching
  // specs. The normalisation in `matchesFilter` bridges the kebab/snake
  // and `\`/`/` cosmetic gaps without weakening the substring trap.
  const specFiles = allSpecFiles.filter(matchesFilter);
  if (specFiles.length === 0) {
    if (FILTER) {
      console.error(
        `EXAMPLES_FILTER='${FILTER}' matched zero specs (scanned ${allSpecFiles.length} candidates under ${SPEC_ROOTS.join(', ')}).`,
      );
    } else {
      console.error(`No specs found under ${SPEC_ROOTS.join(', ')}`);
    }
    process.exit(1);
  }

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

  // Silent-on-success (rf2-try1x): buffer per-spec narration into
  // `lines` and only flush it for specs that FAIL. Green specs emit
  // nothing; the final summary is a single counts line. SKIP specs
  // still flush so the reason is visible.
  for (const spec of specs) {
    const label = spec.name || path.basename(spec.file);
    const lines = [];
    const log = (s) => lines.push(s);
    const flush = () => {
      for (const ln of lines) console.log(ln);
    };

    log(`\n=== ${label} ===`);

    if (spec.skip) {
      // Spec opted out at the spec-module level (truthy `skip`
      // value). Print a SKIP line with the reason and don't navigate
      // or run assertions. The orchestrator still compiled the
      // example's bundle (per its EXAMPLES entry) — so under-
      // construction examples remain compile-checked, just not
      // smoke-tested at the user-visible behaviour level.
      log(`SKIP  ${label}: ${spec.skip}`);
      flush();
      results.push({ label, passed: true, skipped: true, reason: spec.skip });
      continue;
    }

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
    // rf2-paskh — honour optional per-spec `timeoutMs` override. Specs
    // with a long serial chain of waits (e.g. causa-rhs-smoke's
    // Story-Causa embed boot + multi-panel mount round-trip) can lift
    // their own budget without inflating the global gate for every
    // lightweight smoke. EXAMPLES_BASE_URL env wins over both, so CI
    // can still throttle the whole sweep if needed.
    const specTimeoutMs =
      typeof spec.timeoutMs === 'number' && spec.timeoutMs > 0
        ? spec.timeoutMs
        : TIMEOUT_MS;
    log(`Navigating to ${fullUrl}`);

    let passed = false;
    let failure = null;
    try {
      await page.goto(fullUrl, { waitUntil: 'load', timeout: specTimeoutMs });
      await withTimeout(spec.run(page), specTimeoutMs, label);
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

  // Silent-on-success (rf2-try1x): green runs emit the canonical
  // single-line summary; red runs also list the failing labels for
  // quick triage.
  const passedCount = results.filter((r) => r.passed && !r.skipped).length;
  const skippedCount = results.filter((r) => r.skipped).length;
  const failedCount = results.filter((r) => !r.passed).length;
  if (anyFailed) {
    console.log('\n=== summary ===');
    for (const r of results) {
      if (r.skipped) {
        console.log(`SKIP  ${r.label}  (${r.reason})`);
      } else {
        console.log(`${r.passed ? 'PASS' : 'FAIL'}  ${r.label}`);
      }
    }
  }
  console.log(
    `Ran ${results.length} example specs. ${failedCount} failures, ${skippedCount} skipped.`,
  );

  process.exit(anyFailed ? 1 : 0);
})().catch((err) => {
  console.error(err);
  process.exit(1);
});
