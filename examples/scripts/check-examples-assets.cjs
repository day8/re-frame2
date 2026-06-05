#!/usr/bin/env node
/*
 * `check-examples-assets` — STATIC asset-contract gate over every example
 * index.html (rf2-8r0mj.2 + rf2-8r0mj.3 + rf2-emvyd).
 *
 * The gap this closes
 * -------------------
 * examples/_shared/ ships a shared design system (stylesheet + favicon +
 * Open Graph card) that examples/_shared/README.md declares is "consumed by
 * every example index.html". The only automated gate that touches _shared
 * is the adapter-smoke harness (npm run test:examples), and that harness
 * compiles + serves ONLY the three adapter testbeds at
 * implementation/adapters/<name>/testbed/. Those testbed pages link NONE of
 * _shared, so a broken shared stylesheet, a missing/renamed _shared asset, a
 * bad @import target, or a stageShared regression all pass test:examples —
 * the staged copy is never loaded by any page the runner navigates to. The
 * shared design system had ZERO automated coverage.
 *
 * On top of that, the "every example page references the shared assets"
 * contract was review-only: nothing failed when a non-exempt page omitted an
 * asset, and the one real exception (TodoMVC uses the official TodoMVC CSS
 * packages instead of the shared stylesheet) was not encoded as an explicit
 * allowlisted exception anywhere.
 *
 * What this gate does (STATIC — no browser, no Playwright)
 * -------------------------------------------------------
 * It walks every tracked example index.html and, for each:
 *
 *   1. RESOLVES every local asset reference (link href, script src,
 *      og:image meta, and transitively the @import targets inside any
 *      referenced local CSS) to a real file in the repo source tree. A
 *      reference to a missing/renamed/typo'd local asset fails the gate.
 *      External URLs (http(s):// + protocol-relative //) and the build
 *      output main.js (produced by shadow-cljs, not source) are skipped.
 *
 *      Staging-aware resolution: a page references _shared assets at the
 *      relative path `_shared/...`, but in the SOURCE tree _shared lives
 *      ONCE at examples/_shared/ (not next to each page) — the orchestrator
 *      copies it into every page's output dir at stage time. So a
 *      `_shared/...` reference is resolved against the canonical
 *      examples/_shared/ source (exactly what gets staged), while a
 *      non-_shared sibling reference (e.g. TodoMVC's base.css) is resolved
 *      relative to the page's own directory.
 *
 *   2. Asserts the REQUIRED _shared asset contract: every example must
 *      reference _shared/img/favicon.svg, _shared/img/og.svg, and
 *      _shared/css/style.css — UNLESS the page is allowlisted out of a
 *      specific asset (see ALLOWLIST). The TodoMVC stylesheet opt-out is
 *      encoded there: it is exempt from style.css (it links the vendored
 *      TodoMVC base.css + index.css) but STILL required to carry the shared
 *      favicon + OG card.
 *
 *   3. Asserts the _shared source tree itself is intact: the required
 *      _shared files exist on disk and structure.css remains reachable from
 *      style.css's @import.
 *
 * This is NOT a per-example *.spec.cjs — examples/ stays test-free
 * (rf2-8cevm). It is a pure static scanner, wired into the always-run
 * `test:script-policy` gate (see implementation/scripts/
 * check-examples-assets.test.cjs) so a missing/broken _shared asset turns
 * that gate RED in CI without a new .github workflow job.
 *
 * CLI
 * ---
 *   node examples/scripts/check-examples-assets.cjs           # scan, exit 0/1
 *   node examples/scripts/check-examples-assets.cjs --list    # print findings, exit 0
 *
 * The pure scan logic is exported for check-examples-assets.test.cjs, which
 * pins the contract (required-asset detection, @import resolution, the
 * TodoMVC allowlist) AND gives the gate teeth by running the real scan over
 * the repo.
 */

'use strict';

const fs = require('fs');
const path = require('path');

// __dirname is <repo>/examples/scripts. REPO_ROOT is <repo>.
const REPO_ROOT = path.resolve(__dirname, '..', '..');
const EXAMPLES_ROOT = path.join(REPO_ROOT, 'examples');
const SHARED_ROOT = path.join(EXAMPLES_ROOT, '_shared');

// The shared design-system assets every example page is expected to carry,
// keyed by the canonical relative href every index.html uses. `required`
// marks the universal contract; a page can be allowlisted out of an
// individual asset via ALLOWLIST below.
const REQUIRED_SHARED_ASSETS = [
  '_shared/img/favicon.svg',
  '_shared/img/og.svg',
  '_shared/css/style.css',
];

// ---------------------------------------------------------------------------
// Allowlisted exceptions — the ONE encoded place that names a page's opt-out
// from a required shared asset, with the reason. A reviewer (human or the
// gate) reads this to know an omission is intentional, not a regression.
//
// `assetExemptions` lists the required-asset hrefs a page is allowed to
// OMIT. `localAssets` lists the page's NON-_shared local references that are
// staged from somewhere other than the repo source tree (so the resolver
// must not flag them as missing files on disk).
// ---------------------------------------------------------------------------
const ALLOWLIST = {
  // TodoMVC links the official TodoMVC CSS packages (todomvc-common's
  // base.css + todomvc-app-css's index.css, pinned in
  // implementation/package.json and staged from node_modules/) instead of
  // the shared stylesheet — see examples/reagent/todomvc/README.md
  // §Official assets. It is therefore exempt from _shared/css/style.css but
  // STILL required to carry the shared favicon + OG card, and its two
  // vendored CSS links are not repo-source files (they're npm-staged), so
  // they're declared here rather than flagged as missing.
  'examples/reagent/todomvc/index.html': {
    reason:
      'TodoMVC uses the official TodoMVC CSS packages (base.css + ' +
      'index.css, staged from node_modules/) instead of the shared ' +
      'stylesheet — see examples/reagent/todomvc/README.md §Official assets.',
    assetExemptions: ['_shared/css/style.css'],
    localAssets: ['base.css', 'index.css'],
  },
};

// Build output produced by shadow-cljs at stage time — never a repo-source
// file, so the resolver skips it (every index.html ships <script src=main.js>).
const BUILD_OUTPUTS = new Set(['main.js']);

// ---------------------------------------------------------------------------
// index.html enumeration. Walk the examples/ tree for index.html files,
// skipping the _shared tree itself (it holds assets, not example pages) and
// node_modules / build-output dirs.
// ---------------------------------------------------------------------------

function listExampleIndexHtml(root = EXAMPLES_ROOT) {
  const out = [];
  const stack = [root];
  while (stack.length > 0) {
    const dir = stack.pop();
    let entries;
    try {
      entries = fs.readdirSync(dir, { withFileTypes: true });
    } catch {
      continue;
    }
    for (const entry of entries) {
      const full = path.join(dir, entry.name);
      if (entry.isDirectory()) {
        if (entry.name === 'node_modules' || entry.name === '_shared') continue;
        stack.push(full);
      } else if (entry.isFile() && entry.name === 'index.html') {
        out.push(full);
      }
    }
  }
  return out.sort();
}

// ---------------------------------------------------------------------------
// Reference extraction. We hand-roll focused regexes rather than pull in an
// HTML/CSS parser — the only shapes we read are attribute-quoted hrefs/srcs,
// the og:image meta content, and CSS @import url(...) targets.
// ---------------------------------------------------------------------------

// True for references the resolver does not check on disk: absolute URLs
// (http://, https://, protocol-relative //), data: URIs, and in-page
// fragments / mailto etc.
function isExternalRef(ref) {
  return (
    /^[a-z][a-z0-9+.-]*:/i.test(ref) || // any scheme: http:, https:, data:, mailto:
    ref.startsWith('//') ||             // protocol-relative
    ref.startsWith('#')                 // in-page fragment
  );
}

// Extract every asset reference from an index.html's source: <link href>,
// <script src>, and the og:image <meta content>. Order-preserving,
// de-duplicated. Query/hash suffixes are stripped for on-disk resolution.
function extractHtmlRefs(html) {
  const refs = [];
  const push = (raw) => {
    if (!raw) return;
    const clean = raw.split(/[?#]/)[0].trim();
    if (clean && !refs.includes(clean)) refs.push(clean);
  };
  // href="..." / href='...' on <link> (and any element — anchors are
  // in-page or external and get filtered by isExternalRef downstream).
  for (const m of html.matchAll(/\b(?:href|src)\s*=\s*("([^"]*)"|'([^']*)')/gi)) {
    push(m[2] != null ? m[2] : m[3]);
  }
  // <meta property="og:image" content="...">
  for (const m of html.matchAll(
    /<meta\b[^>]*\bproperty\s*=\s*["']og:image["'][^>]*\bcontent\s*=\s*("([^"]*)"|'([^']*)')[^>]*>/gi,
  )) {
    push(m[2] != null ? m[2] : m[3]);
  }
  return refs;
}

// Extract local @import targets from a CSS source. Handles both
// `@import url('x')` / `@import url("x")` / `@import url(x)` and the bare
// `@import 'x'` / `@import "x"` forms. External targets are returned too so
// the caller can filter them uniformly via isExternalRef.
function extractCssImports(css) {
  const out = [];
  const push = (raw) => {
    if (!raw) return;
    const clean = raw.split(/[?#]/)[0].trim();
    if (clean && !out.includes(clean)) out.push(clean);
  };
  for (const m of css.matchAll(
    /@import\s+(?:url\(\s*("([^"]*)"|'([^']*)'|([^)'"]*))\s*\)|("([^"]*)"|'([^']*)'))/gi,
  )) {
    push(m[2] || m[3] || m[4] || m[6] || m[7]);
  }
  return out;
}

// ---------------------------------------------------------------------------
// The pure scan. Returns { errors: string[], pages: [...] }; the CLI and the
// test both consume this. Filesystem reads are injected so the test can pin
// behaviour without touching disk; the CLI passes the real fs.
// ---------------------------------------------------------------------------

function readFileSafe(io, p) {
  try {
    return io.readFileSync(p, 'utf8');
  } catch {
    return null;
  }
}

// Resolve a local reference from `pageDir` to its real SOURCE location,
// modelling the orchestrator's staging. A `_shared/...` reference is staged
// from the single canonical examples/_shared/ tree (copied into every page's
// output dir at the same relative path), so it resolves against
// `sharedParent` (examples/), NOT relative to the page. Every other relative
// reference is a true page-local sibling and resolves relative to `pageDir`.
function resolveRef(ref, pageDir, sharedParent = EXAMPLES_ROOT) {
  if (ref === '_shared' || ref.startsWith('_shared/')) {
    return path.resolve(sharedParent, ref);
  }
  return path.resolve(pageDir, ref);
}

// Resolve + check a single local CSS file's @import targets, recursively.
// Records an error for any local import that does not resolve to a real file.
function checkCssImports(io, cssAbsPath, displayRef, errors, seen) {
  if (seen.has(cssAbsPath)) return;
  seen.add(cssAbsPath);
  const css = readFileSafe(io, cssAbsPath);
  if (css == null) return; // a missing CSS file is reported by its referrer
  for (const imp of extractCssImports(css)) {
    if (isExternalRef(imp)) continue;
    const target = path.resolve(path.dirname(cssAbsPath), imp);
    if (!io.existsSync(target)) {
      errors.push(
        `${displayRef}: @import '${imp}' does not resolve to a file ` +
          `(looked for ${path.relative(REPO_ROOT, target).split(path.sep).join('/')})`,
      );
      continue;
    }
    if (target.toLowerCase().endsWith('.css')) {
      checkCssImports(io, target, `${displayRef} -> ${imp}`, errors, seen);
    }
  }
}

function scanPage(io, indexAbsPath, opts = {}) {
  const allowlist = opts.allowlist || ALLOWLIST;
  const required = opts.required || REQUIRED_SHARED_ASSETS;
  const errors = [];
  const relIndex = path.relative(REPO_ROOT, indexAbsPath).split(path.sep).join('/');
  const exempt = allowlist[relIndex] || {};
  const exemptAssets = new Set(exempt.assetExemptions || []);
  const allowedLocal = new Set(exempt.localAssets || []);

  const html = readFileSafe(io, indexAbsPath);
  if (html == null) {
    errors.push(`${relIndex}: could not read index.html`);
    return { relIndex, errors, refs: [] };
  }

  const refs = extractHtmlRefs(html);
  const pageDir = path.dirname(indexAbsPath);
  const seenCss = new Set();

  // 1) Resolve every local reference to a real file in the source tree.
  for (const ref of refs) {
    if (isExternalRef(ref)) continue;
    if (BUILD_OUTPUTS.has(ref)) continue; // main.js — build output, not source
    // Allowlisted non-_shared local assets (e.g. TodoMVC's npm-vendored CSS)
    // are staged from outside the repo source tree; do not flag as missing,
    // but still note them so they're visible in --list output.
    if (allowedLocal.has(ref)) continue;
    const target = resolveRef(ref, pageDir, opts.sharedParent || EXAMPLES_ROOT);
    if (!io.existsSync(target)) {
      errors.push(
        `${relIndex}: asset '${ref}' does not resolve to a file ` +
          `(looked for ${path.relative(REPO_ROOT, target).split(path.sep).join('/')})`,
      );
      continue;
    }
    // Transitively check @import targets inside any referenced local CSS.
    if (target.toLowerCase().endsWith('.css')) {
      checkCssImports(io, target, `${relIndex} (${ref})`, errors, seenCss);
    }
  }

  // 2) Required shared-asset contract: every page references each required
  //    asset unless explicitly exempt (allowlist). An exemption that is not
  //    actually exercised (the page DOES reference the "exempt" asset) is a
  //    stale allowlist entry — flag it so the allowlist can't rot.
  const refSet = new Set(refs);
  for (const need of required) {
    const isExempt = exemptAssets.has(need);
    const present = refSet.has(need);
    if (!present && !isExempt) {
      errors.push(
        `${relIndex}: missing required shared asset reference '${need}'. ` +
          `Either add it to the page's <head>, or (if intentional) encode ` +
          `the exception in ALLOWLIST in check-examples-assets.cjs with a reason.`,
      );
    }
    if (present && isExempt) {
      errors.push(
        `${relIndex}: allowlisted as exempt from '${need}' but the page ` +
          `DOES reference it — remove the stale exemption from ALLOWLIST ` +
          `in check-examples-assets.cjs.`,
      );
    }
  }

  return { relIndex, errors, refs };
}

// Verify the _shared SOURCE tree is intact: the required files exist and
// structure.css remains reachable from style.css. Independent of any page so
// a delete/rename of a shared asset is caught even if every page were
// (wrongly) allowlisted out of it.
function checkSharedTree(io, opts = {}) {
  const sharedRoot = opts.sharedRoot || SHARED_ROOT;
  const errors = [];
  const mustExist = [
    path.join(sharedRoot, 'css', 'style.css'),
    path.join(sharedRoot, 'css', 'structure.css'),
    path.join(sharedRoot, 'img', 'favicon.svg'),
    path.join(sharedRoot, 'img', 'og.svg'),
  ];
  for (const f of mustExist) {
    if (!io.existsSync(f)) {
      errors.push(
        `examples/_shared: required shared asset missing: ` +
          `${path.relative(REPO_ROOT, f).split(path.sep).join('/')}`,
      );
    }
  }
  // structure.css reachable from style.css's @import.
  const stylePath = path.join(sharedRoot, 'css', 'style.css');
  const style = readFileSafe(io, stylePath);
  if (style != null) {
    const imports = extractCssImports(style).filter((i) => !isExternalRef(i));
    const reachesStructure = imports.some(
      (i) => path.basename(i).toLowerCase() === 'structure.css',
    );
    if (!reachesStructure) {
      errors.push(
        `examples/_shared/css/style.css: no @import resolving to ` +
          `structure.css — the structural baseline is unreachable.`,
      );
    }
  }
  return errors;
}

// Run the full scan over every example index.html plus the _shared tree.
function scanAll(opts = {}) {
  const io = opts.io || fs;
  const indexes = opts.indexes || listExampleIndexHtml();
  const pages = indexes.map((p) => scanPage(io, p, opts));
  const sharedErrors = checkSharedTree(io, opts);
  const errors = [...sharedErrors, ...pages.flatMap((p) => p.errors)];
  return { pages, sharedErrors, errors };
}

module.exports = {
  REPO_ROOT,
  EXAMPLES_ROOT,
  SHARED_ROOT,
  REQUIRED_SHARED_ASSETS,
  ALLOWLIST,
  BUILD_OUTPUTS,
  listExampleIndexHtml,
  isExternalRef,
  extractHtmlRefs,
  extractCssImports,
  resolveRef,
  checkCssImports,
  scanPage,
  checkSharedTree,
  scanAll,
};

// ---------------------------------------------------------------------------
// CLI entry-point (skipped when require()'d by the test suite).
// ---------------------------------------------------------------------------
if (require.main === module) {
  const listOnly = process.argv.slice(2).includes('--list');
  const indexes = listExampleIndexHtml();

  // Non-vacuous guard: a walk that silently recovers nothing must NOT let the
  // gate pass green having scanned zero pages. The repo ships well over a
  // dozen example pages; require a sane floor.
  if (indexes.length < 10) {
    console.error(
      `check-examples-assets: only ${indexes.length} example index.html ` +
        `page(s) found under examples/ — expected the full set. Walk or ` +
        `layout drift; refusing to pass a vacuous gate.`,
    );
    process.exit(1);
  }

  const { pages, errors } = scanAll({ indexes });

  console.log(
    `check-examples-assets: scanning ${pages.length} example index.html ` +
      `page(s) + the _shared tree.`,
  );

  if (listOnly) {
    for (const p of pages) {
      console.log(`  ${p.relIndex}`);
      for (const r of p.refs) console.log(`      ${r}`);
    }
    process.exit(0);
  }

  if (errors.length > 0) {
    console.error(
      `\ncheck-examples-assets: ${errors.length} asset-contract violation(s):`,
    );
    for (const e of errors) console.error(`  - ${e}`);
    console.error(
      `\nA missing/renamed _shared asset, a broken @import, or a page that ` +
        `drops a required shared asset without an encoded ALLOWLIST ` +
        `exception fails this gate. Fix the reference, restore the asset, or ` +
        `encode the exception (with a reason) in ` +
        `examples/scripts/check-examples-assets.cjs.`,
    );
    process.exit(1);
  }

  console.log(
    `check-examples-assets: all ${pages.length} pages resolve their assets ` +
      `and carry the required _shared contract (allowlisted exceptions ` +
      `honoured). _shared tree intact.`,
  );
}
