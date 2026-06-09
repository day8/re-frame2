#!/usr/bin/env node
/*
 * Tests for `examples/scripts/check-examples-assets.cjs` — the STATIC
 * examples asset-contract gate (rf2-8r0mj.2 + rf2-8r0mj.3 + rf2-emvyd).
 *
 * Two jobs, both with teeth:
 *
 *  1. LIVE GATE — run the real scan over the actual repo tree and FAIL if it
 *     reports any violation. This is what gives the always-run
 *     `test:script-policy` gate its teeth: a missing/renamed _shared asset, a
 *     broken @import, or a non-exempt page dropping a required shared asset
 *     turns this gate RED in CI. (Today the real tree is clean, so the live
 *     scan passes — see the teeth-proof in the PR: break favicon.svg → RED →
 *     restore → GREEN.)
 *
 *  2. UNIT TEETH — pin the pure scan logic against synthetic in-memory
 *     fixtures so the behaviours the gate relies on (required-asset
 *     detection, staging-aware _shared resolution, @import resolution, the
 *     TodoMVC allowlist, external-URL/main.js skipping) cannot silently
 *     regress to a vacuous pass.
 *
 * Standalone node-runnable suite — no external test framework, mirroring
 * `_examples-filter.test.cjs` / `check-examples-compile.test.cjs`. Wired into
 * package.json via `test:script-policy`.
 */

'use strict';

const path = require('path');
const assert = require('assert');

const scanner = require('../../examples/scripts/check-examples-assets.cjs');
const {
  REQUIRED_SHARED_ASSETS,
  SOCIAL_PREVIEW_REQUIRED,
  ALLOWLIST,
  EXTERNAL_IMPORT_ALLOWLIST,
  isExternalRef,
  extractHtmlRefs,
  extractOgImageRefs,
  extractCssImports,
  resolveRef,
  scanPage,
  checkSharedTree,
  scanAll,
  listExampleIndexHtml,
  EXAMPLES_ROOT,
  validatePng,
  OG_PNG_WIDTH,
  OG_PNG_HEIGHT,
  contrastRatio,
  parseExTokens,
  sharedContrastContract,
  WCAG_AA_NORMAL_TEXT,
} = scanner;

// A real, decodable 1200x630 PNG (signature + IHDR + IDAT + IEND), used wherever
// a fixture's _shared tree must scan clean — the gate now validates the og.png
// BYTES, so an opaque 'PNGDATA' string no longer passes checkSharedTree
// (rf2-mon7tz). Stored latin1 so the synthetic io (which returns the stored
// value verbatim) round-trips the bytes; validatePng coerces it back to a
// Buffer the same way.
const VALID_OG_PNG = Buffer.from(
  'iVBORw0KGgoAAAANSUhEUgAABLAAAAJ2CAIAAADAIuwLAAAACUlEQVR4nGMAAAABAAFe/335AAAAAElFTkSuQmCC',
  'base64',
).toString('latin1');

// An AA-safe, focus-accessible style.css that satisfies the shared contrast +
// focus-indicator contracts (rf2-febmqu + rf2-mon7tz), for checkSharedTree
// fixtures whose tree must scan clean. Mirrors the shipped palette decisions.
const GOOD_SHARED_STYLE = [
  "@import url('structure.css');",
  ':root {',
  '  --ex-bg: #F7F3EC; --ex-bg-raised: #FFFFFF; --ex-bg-sunken: #ECE7DC;',
  '  --ex-bg-elevated: #F1ECE0;',
  '  --ex-ink: #1A1814; --ex-ink-muted: #5C5448; --ex-ink-faint: #6E6654;',
  '  --ex-accent: #C8741A; --ex-accent-deep: #9C4F0E; --ex-accent-soft: #E5A23D;',
  '  --ex-success: #4A7340; --ex-warn: #C49419; --ex-error: #B23A2E;',
  '}',
  'input:focus-visible { border-color: var(--ex-accent-deep);',
  '  box-shadow: 0 0 0 3px var(--ex-accent-deep); }',
].join('\n');

let failed = 0;
function it(label, fn) {
  try {
    fn();
    console.log(`  PASS  ${label}`);
  } catch (err) {
    failed++;
    console.error(`  FAIL  ${label}`);
    console.error(`        ${(err && err.message) || err}`);
  }
}

console.log('check-examples-assets tests (rf2-8r0mj.2 + rf2-8r0mj.3 + rf2-emvyd)');

// ---------------------------------------------------------------------------
// 1) LIVE GATE — the teeth in CI. Scan the real repo; any violation is RED.
// ---------------------------------------------------------------------------

const realIndexes = listExampleIndexHtml();

it('the real examples tree exposes a non-vacuous set of index.html pages', () => {
  assert.ok(
    realIndexes.length >= 10,
    `expected the full example set (>=10 index.html), got ` +
      `${realIndexes.length} — walk/layout drift; a vacuous gate is forbidden`,
  );
});

it('LIVE: every real example page resolves its assets + carries the contract', () => {
  const { errors } = scanAll({ indexes: realIndexes });
  assert.strictEqual(
    errors.length,
    0,
    `the live asset scan found ${errors.length} violation(s):\n` +
      errors.map((e) => `    - ${e}`).join('\n'),
  );
});

it('LIVE: the real _shared source tree is intact (style.css -> structure.css)', () => {
  const errors = checkSharedTree(require('fs'));
  assert.deepStrictEqual(
    errors,
    [],
    `_shared tree errors:\n` + errors.map((e) => `    - ${e}`).join('\n'),
  );
});

it('LIVE: the send-form text-input baseline is scoped, not a global input[type=text] (rf2-gv5xd)', () => {
  // The real structure.css must NOT carry a bare global `input[type="text"]`
  // rule (it leaks min-width:240px into the 7GUIs Cells inline editors and
  // blows the grid out), and `.cells-grid input` must keep width:56px.
  const errors = checkSharedTree(require('fs'));
  assert.deepStrictEqual(
    errors,
    [],
    `_shared CSS-cascade contract errors:\n` + errors.map((e) => `    - ${e}`).join('\n'),
  );
});

it('LIVE: TodoMVC is the encoded style.css opt-out (allowlist, not a regression)', () => {
  const key = 'examples/reagent/todomvc/index.html';
  const entry = ALLOWLIST[key];
  assert.ok(entry, 'TodoMVC must be present in ALLOWLIST');
  assert.ok(
    entry.assetExemptions.includes('_shared/css/style.css'),
    'TodoMVC must be exempt from the shared stylesheet',
  );
  // Still required to carry favicon + OG (the exemption is stylesheet-only).
  assert.ok(
    !entry.assetExemptions.includes('_shared/img/favicon.svg'),
    'TodoMVC must STILL be required to carry the shared favicon',
  );
  assert.ok(
    !entry.assetExemptions.includes('_shared/img/og.png'),
    'TodoMVC must STILL be required to carry the shared OG card',
  );
  assert.ok(
    entry.reason && entry.reason.length > 0,
    'the exemption must carry a human-readable reason',
  );
});

// ---------------------------------------------------------------------------
// 2) UNIT TEETH — synthetic in-memory fs so behaviour is pinned exactly.
// A tiny fake io: a map of absolute path -> file contents.
// ---------------------------------------------------------------------------

function makeIo(files) {
  // Keys are absolute paths. existsSync/readFileSync read from the map.
  const norm = (p) => path.resolve(p);
  const map = new Map(Object.entries(files).map(([k, v]) => [norm(k), v]));
  return {
    existsSync: (p) => map.has(norm(p)),
    readFileSync: (p) => {
      const v = map.get(norm(p));
      if (v == null) {
        const e = new Error(`ENOENT: ${p}`);
        e.code = 'ENOENT';
        throw e;
      }
      return v;
    },
  };
}

const PAGE = path.join(EXAMPLES_ROOT, 'reagent', 'demo', 'index.html');
const FAVICON = path.join(EXAMPLES_ROOT, '_shared', 'img', 'favicon.svg');
// The shipped social-preview target is the RASTER og.png (an SVG og:image
// renders no preview card — rf2-lr4am3); og.svg is kept only as source art.
const OG = path.join(EXAMPLES_ROOT, '_shared', 'img', 'og.png');
const OG_SVG = path.join(EXAMPLES_ROOT, '_shared', 'img', 'og.svg');
const STYLE = path.join(EXAMPLES_ROOT, '_shared', 'css', 'style.css');
const STRUCTURE = path.join(EXAMPLES_ROOT, '_shared', 'css', 'structure.css');
const SHARED_ROOT = path.join(EXAMPLES_ROOT, '_shared');

// A well-formed page that links all three shared assets, plus a style.css
// that @imports structure.css. The shared design system loads NO remote fonts
// (rf2-byf7y removed the Google-Fonts @import; rf2-vou5mm now REJECTS any
// re-introduced external @import) — so the clean fixture has only the local
// structure.css import.
function goodHtml() {
  return [
    '<!doctype html><html><head>',
    '<meta property="og:image" content="_shared/img/og.png">',
    '<link rel="icon" href="_shared/img/favicon.svg">',
    '<link rel="stylesheet" href="_shared/css/style.css">',
    '</head><body><script src="main.js"></script></body></html>',
  ].join('\n');
}
function goodStyleCss() {
  return [
    "@import url('structure.css');",
    'body { color: #1A1814; }',
  ].join('\n');
}
function fullIo(overrides = {}) {
  return makeIo({
    [PAGE]: goodHtml(),
    [STYLE]: goodStyleCss(),
    [STRUCTURE]: '/* structure */',
    [FAVICON]: '<svg/>',
    [OG]: 'PNGDATA', // og.png raster (content opaque to the scanner)
    ...overrides,
  });
}

// ---- extraction primitives ----------------------------------------------

it('extractHtmlRefs picks up link/script/og:image refs, de-duped', () => {
  const refs = extractHtmlRefs(goodHtml());
  assert.ok(refs.includes('_shared/img/favicon.svg'));
  assert.ok(refs.includes('_shared/css/style.css'));
  assert.ok(refs.includes('_shared/img/og.png'));
  assert.ok(refs.includes('main.js'));
});

it('extractHtmlRefs strips ?query and #hash for on-disk resolution', () => {
  const refs = extractHtmlRefs('<link href="a.css?v=2"><link href="b.css#x">');
  assert.ok(refs.includes('a.css'));
  assert.ok(refs.includes('b.css'));
});

it('extractCssImports handles url() and bare-string @import forms', () => {
  const imports = extractCssImports(
    "@import url('a.css'); @import \"b.css\"; @import url(c.css);",
  );
  assert.deepStrictEqual(imports.sort(), ['a.css', 'b.css', 'c.css']);
});

it('isExternalRef flags http(s)/protocol-relative/scheme refs, not local', () => {
  assert.ok(isExternalRef('https://fonts.googleapis.com/x'));
  assert.ok(isExternalRef('http://example.com'));
  assert.ok(isExternalRef('//cdn.example.com/x.css'));
  assert.ok(isExternalRef('data:image/svg+xml,...'));
  assert.ok(isExternalRef('#frag'));
  assert.ok(!isExternalRef('_shared/css/style.css'));
  assert.ok(!isExternalRef('base.css'));
});

// ---- staging-aware resolution -------------------------------------------

it('resolveRef maps _shared/* to the canonical examples/_shared tree', () => {
  const target = resolveRef('_shared/css/style.css', path.dirname(PAGE));
  assert.strictEqual(target, STYLE);
});

it('resolveRef maps a sibling ref relative to the page dir', () => {
  const target = resolveRef('base.css', path.dirname(PAGE));
  assert.strictEqual(target, path.join(path.dirname(PAGE), 'base.css'));
});

// ---- the happy path is clean --------------------------------------------

it('a well-formed page with all assets present scans clean', () => {
  const { errors } = scanPage(fullIo(), PAGE);
  assert.deepStrictEqual(errors, []);
});

// ---- TEETH: missing _shared asset => error ------------------------------

it('TEETH: a missing _shared favicon is reported', () => {
  const io = fullIo();
  // Drop the favicon from the io.
  const without = makeIo({
    [PAGE]: goodHtml(),
    [STYLE]: goodStyleCss(),
    [STRUCTURE]: '/* structure */',
    [OG]: 'PNGDATA',
    // FAVICON intentionally absent
  });
  const { errors } = scanPage(without, PAGE);
  assert.ok(
    errors.some((e) => e.includes('favicon.svg') && e.includes('does not resolve')),
    `expected a missing-favicon error, got: ${errors.join(' | ')}`,
  );
  void io;
});

// ---- TEETH: broken @import target => error ------------------------------

it('TEETH: a style.css @import to a missing structure.css is reported', () => {
  const without = makeIo({
    [PAGE]: goodHtml(),
    [STYLE]: goodStyleCss(),
    [FAVICON]: '<svg/>',
    [OG]: 'PNGDATA',
    // STRUCTURE intentionally absent
  });
  const { errors } = scanPage(without, PAGE);
  assert.ok(
    errors.some((e) => e.includes("@import 'structure.css'")),
    `expected a broken-@import error, got: ${errors.join(' | ')}`,
  );
});

// ---- TEETH: external @import is REJECTED unless allowlisted (rf2-vou5mm) ---
//
// rf2-byf7y found the scanner SKIPPED external CSS @imports, so a Google-Fonts
// network dependency stayed green. The contract is now fail-closed: an
// unallowlisted external @import (http/https/protocol-relative) in any scanned
// CSS fails the gate, while still NOT being checked on disk.

it('TEETH: an unallowlisted external Google-Fonts @import is REJECTED', () => {
  // Inject a style.css with a re-introduced external @import (the exact
  // rf2-byf7y regression) and confirm the gate fails — and never tries to
  // resolve the remote URL on disk.
  const io = fullIo({
    [STYLE]: [
      "@import url('https://fonts.googleapis.com/css2?family=Inter');",
      "@import url('structure.css');",
      'body { color: #1A1814; }',
    ].join('\n'),
  });
  const { errors } = scanPage(io, PAGE);
  assert.ok(
    errors.some(
      (e) =>
        e.includes('external @import') && e.includes('fonts.googleapis.com'),
    ),
    `expected the external @import to be rejected, got: ${errors.join(' | ')}`,
  );
  // It must be rejected as a policy violation, NOT mis-reported as a
  // missing-on-disk file (the URL is never resolved against the filesystem).
  assert.ok(
    !errors.some((e) => e.includes('does not resolve to a file')),
    `an external @import must never be checked on disk, got: ${errors.join(' | ')}`,
  );
});

it('TEETH: a protocol-relative external @import (//host/...) is REJECTED', () => {
  const io = fullIo({
    [STYLE]: [
      "@import url('//cdn.example.com/x.css');",
      "@import url('structure.css');",
    ].join('\n'),
  });
  const { errors } = scanPage(io, PAGE);
  assert.ok(
    errors.some(
      (e) => e.includes('external @import') && e.includes('//cdn.example.com/x.css'),
    ),
    `expected the protocol-relative @import to be rejected, got: ${errors.join(' | ')}`,
  );
});

it('an external @import whose exact URL is allowlisted (with reason) scans clean', () => {
  // The scanner normalises @import targets by stripping ?query/#hash before
  // the allowlist lookup, so the allowlist key is the query-stripped URL.
  const written = 'https://fonts.googleapis.com/css2?family=Inter';
  const allowKey = 'https://fonts.googleapis.com/css2';
  const io = fullIo({
    [STYLE]: [
      `@import url('${written}');`,
      "@import url('structure.css');",
    ].join('\n'),
  });
  const { errors } = scanPage(io, PAGE, {
    externalImportAllowlist: {
      [allowKey]: { reason: 'deliberate remote font for this test' },
    },
  });
  assert.deepStrictEqual(
    errors,
    [],
    `an allowlisted external @import should scan clean, got: ${errors.join(' | ')}`,
  );
});

it('the LIVE EXTERNAL_IMPORT_ALLOWLIST is empty (no remote CSS deps shipped)', () => {
  assert.deepStrictEqual(
    Object.keys(EXTERNAL_IMPORT_ALLOWLIST),
    [],
    'the shipped example CSS must declare NO remote @import; the external ' +
      'import allowlist is fail-closed and starts empty (rf2-vou5mm / rf2-byf7y)',
  );
});

it('TEETH: a data: @import is NOT treated as a network dep (not rejected)', () => {
  // data: URIs are inlined, not a third-party network request — they are
  // external (not resolved on disk) but must not trip the network-dep gate.
  const io = fullIo({
    [STYLE]: [
      "@import url('data:text/css,body{}');",
      "@import url('structure.css');",
    ].join('\n'),
  });
  const { errors } = scanPage(io, PAGE);
  assert.deepStrictEqual(
    errors,
    [],
    `a data: @import must not be rejected as a network dep, got: ${errors.join(' | ')}`,
  );
});

it('TEETH: an external @import in the _shared tree is rejected by checkSharedTree', () => {
  // checkSharedTree enforces the no-remote-CSS contract directly on the
  // _shared source, independent of any page's reference graph.
  const io = makeIo({
    [path.join(SHARED_ROOT, 'css', 'style.css')]:
      "@import url('https://fonts.googleapis.com/css2?family=Inter');\n" +
      "@import url('structure.css');",
    [path.join(SHARED_ROOT, 'css', 'structure.css')]:
      '.send-form input[type="text"] { min-width: 240px; }\n' +
      '.cells-grid input { width: 56px; }',
    [path.join(SHARED_ROOT, 'img', 'favicon.svg')]: '<svg/>',
    [path.join(SHARED_ROOT, 'img', 'og.png')]: VALID_OG_PNG,
    [path.join(SHARED_ROOT, 'img', 'og.svg')]: '<svg/>',
  });
  const errors = checkSharedTree(io, { sharedRoot: SHARED_ROOT });
  assert.ok(
    errors.some(
      (e) => e.includes('external @import') && e.includes('fonts.googleapis.com'),
    ),
    `expected checkSharedTree to reject the external @import, got: ${errors.join(' | ')}`,
  );
});

// ---- TEETH: required-asset contract -------------------------------------

it('TEETH: a non-exempt page dropping style.css is reported', () => {
  const htmlNoStyle = goodHtml().replace(
    '<link rel="stylesheet" href="_shared/css/style.css">',
    '',
  );
  const io = fullIo({ [PAGE]: htmlNoStyle });
  const { errors } = scanPage(io, PAGE);
  assert.ok(
    errors.some(
      (e) =>
        e.includes("missing required shared asset reference '_shared/css/style.css'"),
    ),
    `expected a missing-required-asset error, got: ${errors.join(' | ')}`,
  );
});

it('TEETH: a non-exempt page dropping favicon is reported', () => {
  const htmlNoFav = goodHtml().replace(
    '<link rel="icon" href="_shared/img/favicon.svg">',
    '',
  );
  const io = fullIo({ [PAGE]: htmlNoFav });
  const { errors } = scanPage(io, PAGE);
  assert.ok(
    errors.some((e) =>
      e.includes("missing required shared asset reference '_shared/img/favicon.svg'"),
    ),
    `expected a missing-favicon-reference error, got: ${errors.join(' | ')}`,
  );
});

// ---- TEETH: the TodoMVC allowlist exemption is honoured ------------------

it('a page allowlisted out of style.css with vendored CSS scans clean', () => {
  // A synthetic page modelling TodoMVC: links base.css + index.css (vendored,
  // not in repo source) instead of the shared stylesheet, but keeps favicon
  // + OG. With the right allowlist entry it must scan clean.
  const todoPage = path.join(EXAMPLES_ROOT, 'reagent', 'todomvc', 'index.html');
  const todoHtml = [
    '<meta property="og:image" content="_shared/img/og.png">',
    '<link rel="icon" href="_shared/img/favicon.svg">',
    '<link rel="stylesheet" href="base.css">',
    '<link rel="stylesheet" href="index.css">',
    '<script src="main.js"></script>',
  ].join('\n');
  const io = makeIo({
    [todoPage]: todoHtml,
    [FAVICON]: '<svg/>',
    [OG]: 'PNGDATA',
    // base.css / index.css intentionally absent on disk (npm-staged) — the
    // allowlist's localAssets must keep them from being flagged.
  });
  const allowlist = {
    'examples/reagent/todomvc/index.html': {
      reason: 'vendored TodoMVC CSS',
      assetExemptions: ['_shared/css/style.css'],
      localAssets: ['base.css', 'index.css'],
    },
  };
  const { errors } = scanPage(io, todoPage, { allowlist });
  assert.deepStrictEqual(
    errors,
    [],
    `allowlisted TodoMVC page should scan clean, got: ${errors.join(' | ')}`,
  );
});

it('TEETH: an allowlisted page still REQUIRES favicon + OG (opt-out is stylesheet-only)', () => {
  const todoPage = path.join(EXAMPLES_ROOT, 'reagent', 'todomvc', 'index.html');
  // Drops the favicon — even though style.css is exempt, favicon is not.
  const todoHtml = [
    '<meta property="og:image" content="_shared/img/og.png">',
    '<link rel="stylesheet" href="base.css">',
    '<link rel="stylesheet" href="index.css">',
    '<script src="main.js"></script>',
  ].join('\n');
  const io = makeIo({
    [todoPage]: todoHtml,
    [OG]: 'PNGDATA',
  });
  const allowlist = {
    'examples/reagent/todomvc/index.html': {
      reason: 'vendored TodoMVC CSS',
      assetExemptions: ['_shared/css/style.css'],
      localAssets: ['base.css', 'index.css'],
    },
  };
  const { errors } = scanPage(io, todoPage, { allowlist });
  assert.ok(
    errors.some((e) =>
      e.includes("missing required shared asset reference '_shared/img/favicon.svg'"),
    ),
    `a stylesheet-only opt-out must still require the favicon, got: ${errors.join(' | ')}`,
  );
});

it('TEETH: a stale exemption (page DOES reference the exempt asset) is flagged', () => {
  // The page references style.css but the allowlist claims it is exempt — an
  // allowlist that has rotted. The gate must surface it so the allowlist
  // cannot silently drift out of sync with the pages.
  const io = fullIo();
  const allowlist = {
    'examples/reagent/demo/index.html': {
      reason: 'stale',
      assetExemptions: ['_shared/css/style.css'],
    },
  };
  const { errors } = scanPage(io, PAGE, { allowlist });
  assert.ok(
    errors.some((e) => e.includes('stale exemption')),
    `expected a stale-exemption error, got: ${errors.join(' | ')}`,
  );
});

// ---- TEETH: social-preview RASTER contract (rf2-lr4am3) -----------------

it('extractOgImageRefs returns the og:image content value(s)', () => {
  const refs = extractOgImageRefs(goodHtml());
  assert.deepStrictEqual(refs, ['_shared/img/og.png']);
});

it('TEETH: an SVG og:image is flagged as a non-raster social-preview asset', () => {
  // The exact pre-fix failure mode: the file exists, every required-asset
  // check passes, but the og:image is an SVG that scrapers will not render.
  const svgOgHtml = goodHtml().replace(
    '<meta property="og:image" content="_shared/img/og.png">',
    '<meta property="og:image" content="_shared/img/og.svg">',
  );
  const io = fullIo({
    [PAGE]: svgOgHtml,
    [OG_SVG]: '<svg/>', // the SVG resolves on disk — existence is NOT the issue
  });
  const { errors } = scanPage(io, PAGE);
  assert.ok(
    errors.some(
      (e) => e.includes('og:image') && e.includes('not a raster'),
    ),
    `expected a non-raster og:image error, got: ${errors.join(' | ')}`,
  );
  // ...and because the page no longer references the required raster, the
  // required-asset contract ALSO fires — both teeth bite the SVG card.
  assert.ok(
    errors.some((e) =>
      e.includes("missing required shared asset reference '_shared/img/og.png'"),
    ),
    `expected the missing-raster error too, got: ${errors.join(' | ')}`,
  );
});

it('TEETH: a .jpg / .webp og:image scans clean (any raster is allowed)', () => {
  for (const ext of ['jpg', 'jpeg', 'webp', 'gif']) {
    const html = goodHtml().replace(
      '<meta property="og:image" content="_shared/img/og.png">',
      `<meta property="og:image" content="_shared/img/og.${ext}">`,
    );
    const io = fullIo({
      [PAGE]: html,
      [path.join(EXAMPLES_ROOT, '_shared', 'img', `og.${ext}`)]: 'RASTER',
    });
    const { errors } = scanPage(io, PAGE);
    // The page intentionally drops the required og.png, so the required-asset
    // contract fires — but the RASTER check must NOT add a non-raster error.
    assert.ok(
      !errors.some((e) => e.includes('not a raster')),
      `og.${ext} must be accepted as a raster, got: ${errors.join(' | ')}`,
    );
  }
});

it('TEETH: a missing og.png raster is reported by checkSharedTree', () => {
  const io = makeIo({
    // AA-safe + focus-accessible style.css so only the missing-raster error fires.
    [path.join(SHARED_ROOT, 'css', 'style.css')]: GOOD_SHARED_STYLE,
    // A cascade-clean structure.css so only the missing-raster error fires.
    [path.join(SHARED_ROOT, 'css', 'structure.css')]:
      '.send-form input[type="text"] { min-width: 240px; }\n' +
      '.cells-grid input { width: 56px; }',
    [path.join(SHARED_ROOT, 'img', 'favicon.svg')]: '<svg/>',
    [path.join(SHARED_ROOT, 'img', 'og.svg')]: '<svg/>',
    // og.png intentionally absent
  });
  const errors = checkSharedTree(io, { sharedRoot: SHARED_ROOT });
  assert.ok(
    errors.some((e) => e.includes('og.png')),
    `expected a missing-og.png error, got: ${errors.join(' | ')}`,
  );
});

// ---- TEETH: og.png raster BYTE validation (rf2-mon7tz) ------------------
//
// A bare "the file exists" check stayed green if og.png were replaced by
// non-PNG bytes or a wrong-size export — both break link-preview scrapers
// silently. The gate now decodes the signature + IHDR dimensions.

it('validatePng accepts a real 1200x630 PNG', () => {
  const v = validatePng(VALID_OG_PNG);
  assert.ok(v.ok, `expected the fixture PNG to validate, got: ${v.reason}`);
  assert.strictEqual(v.width, OG_PNG_WIDTH);
  assert.strictEqual(v.height, OG_PNG_HEIGHT);
});

it('TEETH: non-PNG bytes at og.png fail validatePng (signature)', () => {
  const v = validatePng('PNGDATA'); // the old opaque placeholder is NOT a PNG
  assert.ok(!v.ok, 'opaque non-PNG bytes must fail');
  assert.ok(/signature|too short/.test(v.reason), `expected a signature failure, got: ${v.reason}`);
});

it('TEETH: a wrong-dimension PNG fails validatePng', () => {
  // Same valid PNG bytes, but assert against a different expected size.
  const v = validatePng(VALID_OG_PNG, 800, 600);
  assert.ok(!v.ok, 'a wrong-size PNG must fail');
  assert.ok(/dimensions/.test(v.reason), `expected a dimensions failure, got: ${v.reason}`);
  assert.strictEqual(v.width, OG_PNG_WIDTH);
  assert.strictEqual(v.height, OG_PNG_HEIGHT);
});

it('TEETH: checkSharedTree rejects non-PNG bytes at og.png', () => {
  const io = sharedCssIo(
    '.send-form input[type="text"] { min-width: 240px; }\n' +
      '.cells-grid input { width: 56px; }',
  );
  // Overwrite the og.png with non-PNG bytes (a renamed SVG/text file).
  const ioBad = makeIo({
    [path.join(SHARED_ROOT, 'css', 'style.css')]: GOOD_SHARED_STYLE,
    [path.join(SHARED_ROOT, 'css', 'structure.css')]:
      '.send-form input[type="text"] { min-width: 240px; }\n' +
      '.cells-grid input { width: 56px; }',
    [path.join(SHARED_ROOT, 'img', 'favicon.svg')]: '<svg/>',
    [path.join(SHARED_ROOT, 'img', 'og.png')]: '<svg>not a png</svg>',
    [path.join(SHARED_ROOT, 'img', 'og.svg')]: '<svg/>',
  });
  const errors = checkSharedTree(ioBad, { sharedRoot: SHARED_ROOT });
  assert.ok(
    errors.some((e) => e.includes('og.png') && e.includes('not a valid')),
    `expected a bad-PNG-bytes error, got: ${errors.join(' | ')}`,
  );
  // Sanity: the real-PNG fixture (sharedCssIo) is clean of any PNG error.
  const cleanErrors = checkSharedTree(io, { sharedRoot: SHARED_ROOT });
  assert.ok(
    !cleanErrors.some((e) => e.includes('og.png')),
    `the valid-PNG fixture must not report a PNG error, got: ${cleanErrors.join(' | ')}`,
  );
});

it('TEETH: checkSharedTree rejects a wrong-dimension og.png', () => {
  // Build an 800x600 PNG and confirm the 1200x630 contract rejects it.
  const zlib = require('zlib');
  function crc32(buf) {
    let c = ~0;
    for (let i = 0; i < buf.length; i++) {
      c ^= buf[i];
      for (let k = 0; k < 8; k++) c = (c >>> 1) ^ (0xedb88320 & -(c & 1));
    }
    return (~c) >>> 0;
  }
  function chunk(type, data) {
    const len = Buffer.alloc(4);
    len.writeUInt32BE(data.length);
    const t = Buffer.from(type, 'latin1');
    const crc = Buffer.alloc(4);
    crc.writeUInt32BE(crc32(Buffer.concat([t, data])));
    return Buffer.concat([len, t, data, crc]);
  }
  const sig = Buffer.from([0x89, 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a]);
  const ihdr = Buffer.alloc(13);
  ihdr.writeUInt32BE(800, 0);
  ihdr.writeUInt32BE(600, 4);
  ihdr[8] = 8; ihdr[9] = 2;
  const png = Buffer.concat([
    sig,
    chunk('IHDR', ihdr),
    chunk('IDAT', zlib.deflateSync(Buffer.from([0]))),
    chunk('IEND', Buffer.alloc(0)),
  ]).toString('latin1');
  const io = makeIo({
    [path.join(SHARED_ROOT, 'css', 'style.css')]: GOOD_SHARED_STYLE,
    [path.join(SHARED_ROOT, 'css', 'structure.css')]:
      '.send-form input[type="text"] { min-width: 240px; }\n' +
      '.cells-grid input { width: 56px; }',
    [path.join(SHARED_ROOT, 'img', 'favicon.svg')]: '<svg/>',
    [path.join(SHARED_ROOT, 'img', 'og.png')]: png,
    [path.join(SHARED_ROOT, 'img', 'og.svg')]: '<svg/>',
  });
  const errors = checkSharedTree(io, { sharedRoot: SHARED_ROOT });
  assert.ok(
    errors.some((e) => e.includes('og.png') && e.includes('800x600')),
    `expected a wrong-dimension og.png error, got: ${errors.join(' | ')}`,
  );
});

// ---- TEETH: shared palette CONTRAST contract (rf2-febmqu) ---------------

it('LIVE: the shipped --ex-* palette clears its WCAG contrast contract', () => {
  const fs = require('fs');
  const style = fs.readFileSync(
    path.join(EXAMPLES_ROOT, '_shared', 'css', 'style.css'),
    'utf8',
  );
  const tokens = parseExTokens(style);
  const offenders = [];
  for (const row of sharedContrastContract(tokens)) {
    const fg = tokens[row.fg] || row.fg;
    for (const bg of row.bgs) {
      const bgHex = bg.startsWith('--ex-') ? tokens[bg] : bg;
      if (!fg || !bgHex) continue;
      const r = contrastRatio(fg, bgHex);
      if (r < row.min) offenders.push(`${row.role}: ${row.fg} on ${bg} = ${r.toFixed(2)} < ${row.min}`);
    }
  }
  assert.deepStrictEqual(
    offenders,
    [],
    `shipped palette contrast offenders:\n` + offenders.map((o) => `    - ${o}`).join('\n'),
  );
});

it('TEETH: a sub-AA accent foreground in style.css fails checkSharedTree', () => {
  // The exact rf2-febmqu regression: --ex-accent (#C8741A, 3.18:1 on paper)
  // used as a normal text foreground. Model it by making --ex-accent-deep
  // equal to the sub-AA --ex-accent value and confirm the gate fires.
  const badStyle = GOOD_SHARED_STYLE.replace(
    '--ex-accent-deep: #9C4F0E;',
    '--ex-accent-deep: #C8741A;', // dropped back to the sub-AA amber
  );
  const io = makeIo({
    [path.join(SHARED_ROOT, 'css', 'style.css')]: badStyle,
    [path.join(SHARED_ROOT, 'css', 'structure.css')]:
      '.send-form input[type="text"] { min-width: 240px; }\n' +
      '.cells-grid input { width: 56px; }',
    [path.join(SHARED_ROOT, 'img', 'favicon.svg')]: '<svg/>',
    [path.join(SHARED_ROOT, 'img', 'og.png')]: VALID_OG_PNG,
    [path.join(SHARED_ROOT, 'img', 'og.svg')]: '<svg/>',
  });
  const errors = checkSharedTree(io, { sharedRoot: SHARED_ROOT });
  assert.ok(
    errors.some((e) => e.includes('WCAG AA') && e.includes('rf2-febmqu')),
    `expected a sub-AA contrast error, got: ${errors.join(' | ')}`,
  );
});

it('contrastRatio matches a known pair (white on #9C4F0E ≈ 5.94)', () => {
  const r = contrastRatio('#FFFFFF', '#9C4F0E');
  assert.ok(Math.abs(r - 5.94) < 0.05, `expected ≈5.94, got ${r.toFixed(2)}`);
});

// ---- TEETH: focus-indicator contract (rf2-mon7tz) -----------------------

it('LIVE: the shipped style.css carries an AA-safe :focus-visible indicator', () => {
  const errors = checkSharedTree(require('fs'));
  assert.ok(
    !errors.some((e) => e.includes('focus-visible') || e.includes('focus ring')),
    `the shipped focus indicator must satisfy the contract, got: ${errors.join(' | ')}`,
  );
});

it('TEETH: a bare outline:none focus rule (no :focus-visible ring) fails', () => {
  // Strip the :focus-visible ring → the focus-indicator contract must fire.
  const badStyle = GOOD_SHARED_STYLE.replace(
    /input:focus-visible[^]*$/m,
    'input:focus { outline: none; }',
  );
  const io = makeIo({
    [path.join(SHARED_ROOT, 'css', 'style.css')]: badStyle,
    [path.join(SHARED_ROOT, 'css', 'structure.css')]:
      '.send-form input[type="text"] { min-width: 240px; }\n' +
      '.cells-grid input { width: 56px; }',
    [path.join(SHARED_ROOT, 'img', 'favicon.svg')]: '<svg/>',
    [path.join(SHARED_ROOT, 'img', 'og.png')]: VALID_OG_PNG,
    [path.join(SHARED_ROOT, 'img', 'og.svg')]: '<svg/>',
  });
  const errors = checkSharedTree(io, { sharedRoot: SHARED_ROOT });
  assert.ok(
    errors.some((e) => e.includes('focus-visible') && e.includes('rf2-mon7tz')),
    `expected a missing-focus-indicator error, got: ${errors.join(' | ')}`,
  );
});

it('TEETH: the old low-alpha amber focus ring rgba(200,116,26,0.18) is rejected', () => {
  const badStyle =
    GOOD_SHARED_STYLE +
    '\ninput:focus { outline: none; box-shadow: 0 0 0 3px rgba(200,116,26,0.18); }';
  const io = makeIo({
    [path.join(SHARED_ROOT, 'css', 'style.css')]: badStyle,
    [path.join(SHARED_ROOT, 'css', 'structure.css')]:
      '.send-form input[type="text"] { min-width: 240px; }\n' +
      '.cells-grid input { width: 56px; }',
    [path.join(SHARED_ROOT, 'img', 'favicon.svg')]: '<svg/>',
    [path.join(SHARED_ROOT, 'img', 'og.png')]: VALID_OG_PNG,
    [path.join(SHARED_ROOT, 'img', 'og.svg')]: '<svg/>',
  });
  const errors = checkSharedTree(io, { sharedRoot: SHARED_ROOT });
  assert.ok(
    errors.some((e) => e.includes('low-alpha amber') && e.includes('rf2-mon7tz')),
    `expected the low-alpha amber ring to be rejected, got: ${errors.join(' | ')}`,
  );
});

it('LIVE: no real example page ships an SVG (or otherwise non-raster) og:image', () => {
  const fs = require('fs');
  const offenders = [];
  for (const idx of realIndexes) {
    const html = fs.readFileSync(idx, 'utf8');
    for (const og of extractOgImageRefs(html)) {
      if (isExternalRef(og)) continue;
      const ext = path.extname(og).toLowerCase();
      if (!['.png', '.jpg', '.jpeg', '.webp', '.gif'].includes(ext)) {
        offenders.push(`${path.relative(EXAMPLES_ROOT, idx)} -> ${og}`);
      }
    }
  }
  assert.deepStrictEqual(
    offenders,
    [],
    `these real pages ship a non-raster og:image:\n` +
      offenders.map((o) => `    - ${o}`).join('\n'),
  );
});

// ---- TEETH: CSS-cascade contract (rf2-gv5xd) ----------------------------

// A minimal _shared/css io: style.css @imports structure.css; structure.css
// contents are supplied per-test so we can pin the cascade check both ways.
// (SHARED_ROOT is declared up with the other path constants.)
function sharedCssIo(structureCss) {
  return makeIo({
    // An AA-safe style.css so the contrast/focus contracts (rf2-febmqu +
    // rf2-mon7tz) are satisfied — this helper pins the CSS-CASCADE contract.
    [path.join(SHARED_ROOT, 'css', 'style.css')]: GOOD_SHARED_STYLE,
    [path.join(SHARED_ROOT, 'css', 'structure.css')]: structureCss,
    [path.join(SHARED_ROOT, 'img', 'favicon.svg')]: '<svg/>',
    // checkSharedTree requires both the shipped raster and its source art, and
    // now validates the og.png BYTES — supply a real 1200x630 PNG.
    [path.join(SHARED_ROOT, 'img', 'og.png')]: VALID_OG_PNG,
    [path.join(SHARED_ROOT, 'img', 'og.svg')]: '<svg/>',
  });
}
const SCOPED_SENDFORM =
  '.send-form input[type="text"] { padding: 8px 12px; flex: 1; min-width: 240px; }';
const CELLS_INPUT = '.cells-grid input { width: 56px; box-sizing: border-box; }';

it('TEETH: a bare global input[type="text"] rule is flagged (Cells blowout)', () => {
  const bad = 'input[type="text"] { min-width: 240px; }\n' + CELLS_INPUT;
  const errors = checkSharedTree(sharedCssIo(bad), { sharedRoot: SHARED_ROOT });
  assert.ok(
    errors.some((e) => e.includes('GLOBAL') && e.includes('input[type="text"]')),
    `expected a global-text-input error, got: ${errors.join(' | ')}`,
  );
});

it('TEETH: the scoped .send-form input[type="text"] form scans clean', () => {
  const good = SCOPED_SENDFORM + '\n' + CELLS_INPUT;
  const errors = checkSharedTree(sharedCssIo(good), { sharedRoot: SHARED_ROOT });
  assert.deepStrictEqual(
    errors,
    [],
    `scoped send-form CSS should scan clean, got: ${errors.join(' | ')}`,
  );
});

it('TEETH: a single-quoted bare global input[type=text] is also flagged', () => {
  const bad = "input[type='text'] { min-width: 240px; }\n" + CELLS_INPUT;
  const errors = checkSharedTree(sharedCssIo(bad), { sharedRoot: SHARED_ROOT });
  assert.ok(
    errors.some((e) => e.includes('GLOBAL')),
    `single-quoted bare rule must also be flagged, got: ${errors.join(' | ')}`,
  );
});

it('TEETH: dropping the compact .cells-grid input width:56px is flagged', () => {
  const bad = SCOPED_SENDFORM + '\n.cells-grid input { box-sizing: border-box; }';
  const errors = checkSharedTree(sharedCssIo(bad), { sharedRoot: SHARED_ROOT });
  assert.ok(
    errors.some((e) => e.includes('width: 56px')),
    `expected a missing-cells-width error, got: ${errors.join(' | ')}`,
  );
});

// ---- main.js / build output is never flagged ----------------------------

it('the build-output main.js is never resolved on disk', () => {
  // main.js is absent from the io (it is shadow-cljs output, not source) yet
  // the page must scan clean.
  const { errors } = scanPage(fullIo(), PAGE);
  assert.ok(
    !errors.some((e) => e.includes('main.js')),
    'main.js (build output) must never be flagged as a missing source file',
  );
});

// ---- contract constant sanity -------------------------------------------

it('REQUIRED_SHARED_ASSETS names favicon, the og.png raster, and style.css', () => {
  assert.deepStrictEqual([...REQUIRED_SHARED_ASSETS].sort(), [
    '_shared/css/style.css',
    '_shared/img/favicon.svg',
    '_shared/img/og.png',
  ]);
  // The social-preview target is a raster, never the SVG source art.
  assert.strictEqual(SOCIAL_PREVIEW_REQUIRED, '_shared/img/og.png');
  assert.ok(!REQUIRED_SHARED_ASSETS.includes('_shared/img/og.svg'));
});

if (failed > 0) {
  console.error(`\ncheck-examples-assets tests: ${failed} failed.`);
  process.exit(1);
}
console.log('\ncheck-examples-assets tests: all passed.');
