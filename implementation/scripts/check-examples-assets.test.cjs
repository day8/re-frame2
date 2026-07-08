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
 * `_adapter-smoke-filter.test.cjs` / `check-examples-compile.test.cjs`. Wired into
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
  EXTERNAL_HTML_REF_ALLOWLIST,
  isExampleHostPage,
  isExternalRef,
  isNetworkRef,
  extractHtmlRefs,
  extractAssetRefs,
  extractOgImageRefs,
  parseSrcset,
  extractCssImports,
  extractCssUrls,
  resolveRef,
  checkCssImports,
  scanPage,
  checkSharedTree,
  scanAll,
  listExampleIndexHtml,
  EXAMPLES_ROOT,
  validatePng,
  OG_PNG_WIDTH,
  OG_PNG_HEIGHT,
  contrastRatio,
  colorToHex,
  parseExTokens,
  sharedContrastContract,
  WCAG_AA_NORMAL_TEXT,
  RETIRED_OG_SOURCE_COLORS,
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

// The responsive Xray-host shell rule (rf2-y82dk9): a max-width media query
// that stacks .rf2-testbed-shell to a column. checkSharedTree now requires it,
// so fixtures whose structure.css must scan CLEAN append it. Declared up here
// (not beside sharedCssIo) because the `it` helper runs each test synchronously
// as the module evaluates — earlier tests call sharedCssIo before a const
// declared further down would leave the temporal dead zone.
const RESPONSIVE_SHELL =
  '@media (max-width: 900px) { .rf2-testbed-shell { flex-direction: column; } }';

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

it('the real examples tree exposes a non-vacuous set of host pages', () => {
  assert.ok(
    realIndexes.length >= 10,
    `expected the full example set (>=10 host pages), got ` +
      `${realIndexes.length} — walk/layout drift; a vacuous gate is forbidden`,
  );
});

// ---- host-page enumeration includes *.index.html showcase pages (rf2-x48bp4)

it('isExampleHostPage accepts index.html AND *.index.html, not arbitrary html', () => {
  assert.ok(isExampleHostPage('index.html'));
  assert.ok(isExampleHostPage('stories.index.html'));
  assert.ok(isExampleHostPage('foo.index.html'));
  assert.ok(!isExampleHostPage('about.html'));
  assert.ok(!isExampleHostPage('index.htm'));
  assert.ok(!isExampleHostPage('stories.html'));
});

it('LIVE: the two Story showcase host pages are enumerated by the gate (rf2-x48bp4)', () => {
  const rel = realIndexes.map((p) => path.relative(EXAMPLES_ROOT, p).split(path.sep).join('/'));
  for (const page of [
    'core/login/stories.index.html',
    'patterns/nine_states/stories.index.html',
  ]) {
    assert.ok(
      rel.includes(page),
      `expected the gate to enumerate the showcase host page '${page}' so it ` +
        `is held to the shared-asset contract, got: ${rel.join(', ')}`,
    );
  }
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
  const key = 'examples/core/todomvc/index.html';
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

it('isNetworkRef flags only http(s)/protocol-relative — not data:/mailto:/#frag', () => {
  assert.ok(isNetworkRef('https://cdn.example.com/lib.js'));
  assert.ok(isNetworkRef('http://example.com/a.css'));
  assert.ok(isNetworkRef('//cdn.example.com/x.css'));
  assert.ok(!isNetworkRef('data:image/svg+xml,...'));
  assert.ok(!isNetworkRef('mailto:x@y.z'));
  assert.ok(!isNetworkRef('tel:+123'));
  assert.ok(!isNetworkRef('#frag'));
  assert.ok(!isNetworkRef('_shared/css/style.css'));
});

// ---- direct-HTML asset-ref tagging (rf2-bf4vdy) -------------------------

it('extractAssetRefs tags script src, asset link href, img/media src, og:image', () => {
  const html = [
    '<script src="https://cdn.example.com/sdk.js"></script>',
    '<link rel="stylesheet" href="https://cdn.example.com/x.css">',
    '<link rel="icon" href="_shared/img/favicon.svg">',
    '<img src="https://img.example.com/hero.png">',
    '<video src="//media.example.com/clip.mp4"></video>',
    '<meta property="og:image" content="https://og.example.com/card.png">',
  ].join('\n');
  const tagged = extractAssetRefs(html);
  const byRef = Object.fromEntries(tagged.map((t) => [t.ref, t.source]));
  assert.ok(byRef['https://cdn.example.com/sdk.js'].includes('script'));
  assert.ok(byRef['https://cdn.example.com/x.css'].includes('link'));
  assert.ok(byRef['_shared/img/favicon.svg'].includes('link'));
  assert.ok(byRef['https://img.example.com/hero.png'].includes('img'));
  assert.ok(byRef['//media.example.com/clip.mp4'].includes('video'));
  assert.strictEqual(byRef['https://og.example.com/card.png'], 'og:image');
});

it('extractAssetRefs does NOT treat an <a href> or rel=canonical as an asset', () => {
  const html = [
    '<a href="https://example.com/docs">docs</a>',
    '<link rel="canonical" href="https://example.com/page">',
    '<link rel="alternate" href="https://example.com/rss">',
  ].join('\n');
  const refs = extractAssetRefs(html).map((t) => t.ref);
  assert.ok(!refs.includes('https://example.com/docs'), 'anchors are navigation, not assets');
  assert.ok(!refs.includes('https://example.com/page'), 'rel=canonical is metadata, not an asset');
  assert.ok(!refs.includes('https://example.com/rss'), 'rel=alternate is metadata, not an asset');
});

// ---- srcset + video poster refs (rf2-arkvq8) ----------------------------
//
// Before rf2-arkvq8 the scanner only read href/src-style refs, so a `srcset`
// candidate (`<img srcset="a-320.png 320w, a-640.png 640w">`) and a
// `<video poster="still.png">` were invisible to BOTH the network policy (a
// remote candidate/poster slipped through) and the missing-file check (a
// broken local candidate/poster stayed green). srcset/imagesrcset candidates
// and the poster still are now extracted for both gates.

it('parseSrcset returns candidate URLs, dropping width/density descriptors', () => {
  assert.deepStrictEqual(
    parseSrcset('hero-320.png 320w, hero-640.png 640w, hero-960.png 960w'),
    ['hero-320.png', 'hero-640.png', 'hero-960.png'],
  );
  assert.deepStrictEqual(
    parseSrcset('logo.png 1x, logo@2x.png 2x'),
    ['logo.png', 'logo@2x.png'],
  );
});

it('parseSrcset handles a single descriptor-less candidate', () => {
  assert.deepStrictEqual(parseSrcset('only.png'), ['only.png']);
  // A trailing comma on a descriptor-less candidate is a separator, not URL.
  assert.deepStrictEqual(parseSrcset('a.png, b.png 2x'), ['a.png', 'b.png']);
});

it('parseSrcset keeps a comma-bearing data: URI candidate intact', () => {
  // A data: URI has no internal whitespace, so it is read as ONE candidate URL
  // even though it contains commas — a naive comma-split would shred it.
  assert.deepStrictEqual(
    parseSrcset('data:image/svg+xml,%3Csvg%3E%3C/svg%3E 1x, next.png 2x'),
    ['data:image/svg+xml,%3Csvg%3E%3C/svg%3E', 'next.png'],
  );
});

it('parseSrcset tolerates extra whitespace and stray commas', () => {
  assert.deepStrictEqual(
    parseSrcset('  a.png   320w ,  b.png  2x  '),
    ['a.png', 'b.png'],
  );
});

it('extractHtmlRefs picks up srcset / imagesrcset candidates + video poster', () => {
  const refs = extractHtmlRefs(
    [
      '<img srcset="_shared/img/hero-320.png 320w, _shared/img/hero-640.png 640w">',
      '<source srcset="_shared/img/pic@2x.png 2x">',
      '<link rel="preload" as="image" imagesrcset="_shared/img/pre-1.png 1x">',
      '<video poster="_shared/img/poster.png"><source src="clip.mp4"></video>',
    ].join('\n'),
  );
  for (const ref of [
    '_shared/img/hero-320.png',
    '_shared/img/hero-640.png',
    '_shared/img/pic@2x.png',
    '_shared/img/pre-1.png',
    '_shared/img/poster.png',
  ]) {
    assert.ok(refs.includes(ref), `expected ${ref} in refs, got: ${refs.join(', ')}`);
  }
});

it('extractHtmlRefs does NOT treat data-srcset / data-poster (lazy-load) as refs', () => {
  const refs = extractHtmlRefs(
    '<img data-srcset="lazy.png 2x" data-poster="lazy-poster.png">',
  );
  assert.ok(!refs.includes('lazy.png'), 'data-srcset must not be read as a srcset');
  assert.ok(!refs.includes('lazy-poster.png'), 'data-poster must not be read as a poster');
});

it('extractAssetRefs tags srcset candidates, imagesrcset, and the video poster', () => {
  const tagged = extractAssetRefs(
    [
      '<img srcset="https://img.example.com/a-320.png 320w, https://img.example.com/a-640.png 640w">',
      '<link rel="preload" as="image" imagesrcset="https://img.example.com/pre.png 1x">',
      '<video poster="https://img.example.com/still.png"></video>',
    ].join('\n'),
  );
  const byRef = Object.fromEntries(tagged.map((t) => [t.ref, t.source]));
  assert.ok(byRef['https://img.example.com/a-320.png'].includes('srcset'));
  assert.ok(byRef['https://img.example.com/a-640.png'].includes('srcset'));
  assert.ok(byRef['https://img.example.com/pre.png'].includes('imagesrcset'));
  assert.strictEqual(byRef['https://img.example.com/still.png'], '<video poster>');
});

it('TEETH: a missing LOCAL srcset candidate is reported (rf2-arkvq8)', () => {
  // The page references two responsive candidates; the 640w one is absent on
  // disk — the missing-file check must fire on the srcset candidate.
  const html = goodHtml().replace(
    '</head>',
    '<img srcset="_shared/img/hero-320.png 320w, _shared/img/hero-640.png 640w">\n</head>',
  );
  const HERO320 = path.join(EXAMPLES_ROOT, '_shared', 'img', 'hero-320.png');
  const io = fullIo({ [PAGE]: html, [HERO320]: 'PNGDATA' });
  const { errors } = scanPage(io, PAGE);
  assert.ok(
    errors.some(
      (e) => e.includes('_shared/img/hero-640.png') && e.includes('does not resolve'),
    ),
    `expected the missing srcset candidate to be reported, got: ${errors.join(' | ')}`,
  );
  // The present candidate must NOT be flagged.
  assert.ok(
    !errors.some((e) => e.includes('hero-320.png')),
    `the present srcset candidate must not be flagged, got: ${errors.join(' | ')}`,
  );
});

it('TEETH: a missing LOCAL <video poster> is reported (rf2-arkvq8)', () => {
  const html = goodHtml().replace(
    '</head>',
    '<video poster="_shared/img/poster.png"></video>\n</head>',
  );
  const { errors } = scanPage(fullIo({ [PAGE]: html }), PAGE);
  assert.ok(
    errors.some(
      (e) => e.includes('_shared/img/poster.png') && e.includes('does not resolve'),
    ),
    `expected the missing poster to be reported, got: ${errors.join(' | ')}`,
  );
});

it('TEETH: a remote srcset candidate is REJECTED by the network policy (rf2-arkvq8)', () => {
  const html = goodHtml().replace(
    '</head>',
    '<img srcset="_shared/img/hero-320.png 320w, https://cdn.example.com/hero-2x.png 2x">\n</head>',
  );
  const HERO320 = path.join(EXAMPLES_ROOT, '_shared', 'img', 'hero-320.png');
  const { errors } = scanPage(fullIo({ [PAGE]: html, [HERO320]: 'PNGDATA' }), PAGE);
  assert.ok(
    errors.some(
      (e) => e.includes('srcset') && e.includes('cdn.example.com') && e.includes('rf2-bf4vdy'),
    ),
    `expected the remote srcset candidate to be rejected, got: ${errors.join(' | ')}`,
  );
  // A network candidate is a policy rejection, never checked on disk.
  assert.ok(
    !errors.some((e) => e.includes('cdn.example.com') && e.includes('does not resolve')),
    `a remote srcset candidate must never be resolved on disk, got: ${errors.join(' | ')}`,
  );
});

it('TEETH: a remote <video poster> is REJECTED by the network policy (rf2-arkvq8)', () => {
  const html = goodHtml().replace(
    '</head>',
    '<video poster="//cdn.example.com/still.png"></video>\n</head>',
  );
  const { errors } = scanPage(fullIo({ [PAGE]: html }), PAGE);
  assert.ok(
    errors.some(
      (e) => e.includes('<video poster>') && e.includes('//cdn.example.com/still.png'),
    ),
    `expected the remote poster to be rejected, got: ${errors.join(' | ')}`,
  );
});

it('a page with all-local srcset candidates + poster present scans clean (rf2-arkvq8)', () => {
  const html = goodHtml().replace(
    '</head>',
    [
      '<img srcset="_shared/img/hero-320.png 320w, _shared/img/hero-640.png 640w">',
      '<video poster="_shared/img/poster.png"></video>',
      '</head>',
    ].join('\n'),
  );
  const HERO320 = path.join(EXAMPLES_ROOT, '_shared', 'img', 'hero-320.png');
  const HERO640 = path.join(EXAMPLES_ROOT, '_shared', 'img', 'hero-640.png');
  const POSTER = path.join(EXAMPLES_ROOT, '_shared', 'img', 'poster.png');
  const io = fullIo({
    [PAGE]: html,
    [HERO320]: 'PNGDATA',
    [HERO640]: 'PNGDATA',
    [POSTER]: 'PNGDATA',
  });
  const { errors } = scanPage(io, PAGE);
  assert.deepStrictEqual(
    errors,
    [],
    `all-local srcset + poster should scan clean, got: ${errors.join(' | ')}`,
  );
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

// ---- TEETH: checkCssImports multi-level recursion + cycle guard (rf2-2l5mav) --
//
// checkCssImports recurses into nested local .css @imports behind a `seen`-set
// cycle guard. Neither the DEEP recursion (a broken import two levels down) nor
// the guard (a circular a.css <-> b.css pair) was exercised — so a regression
// that dropped the `seen` guard would infinite-loop uncaught, and a broken deep
// import could go unreported (or double-reported). These pin both directly on
// the exported checkCssImports (@import targets resolve relative to the CSS file
// that declares them).

const CSS_DIR = path.join(SHARED_ROOT, 'css');
const cssPath = (name) => path.join(CSS_DIR, name);

it('TEETH: a broken @import two levels deep is reported once (multi-level recursion) (rf2-2l5mav)', () => {
  // style.css -> a.css -> b.css, where b.css @imports a MISSING deep.css. The
  // recursion must descend all the way and report the DEEP broken import.
  const io = makeIo({
    [cssPath('style.css')]: "@import url('a.css');",
    [cssPath('a.css')]: "@import url('b.css');",
    [cssPath('b.css')]: "@import url('missing-deep.css');",
    // missing-deep.css intentionally absent on disk
  });
  const errors = [];
  checkCssImports(io, cssPath('style.css'), '_shared/css/style.css', errors, new Set());
  assert.ok(
    errors.some(
      (e) => e.includes("@import 'missing-deep.css'") && e.includes('does not resolve'),
    ),
    `expected the deep broken import to be reported, got: ${errors.join(' | ')}`,
  );
  // Reported exactly once — the recursion must not re-visit a resolved file.
  assert.strictEqual(
    errors.filter((e) => e.includes('missing-deep.css')).length,
    1,
    `the deep broken import must be reported once, got: ${errors.join(' | ')}`,
  );
  // The chained displayRef records the full import path down to the offender.
  assert.ok(
    errors.some((e) => e.includes('style.css -> a.css -> b.css')),
    `expected the chained displayRef, got: ${errors.join(' | ')}`,
  );
});

// A cycle-safe io whose readFileSync throws once reads exceed `cap`, so an
// unbounded-recursion regression (a dropped `seen` guard) is caught
// DETERMINISTICALLY. Relying on a V8 stack overflow is unreliable — the JIT may
// optimise the mutual recursion so it never raises RangeError, and a
// non-overflowing infinite loop would HANG the suite instead of failing it. The
// bounded reader turns "the guard stopped terminating" into a loud, prompt
// throw regardless.
function boundedReadIo(files, cap = 50) {
  const norm = (p) => path.resolve(p);
  const map = new Map(Object.entries(files).map(([k, v]) => [norm(k), v]));
  let reads = 0;
  return {
    reads: () => reads,
    existsSync: (p) => map.has(norm(p)),
    readFileSync: (p) => {
      if ((reads += 1) > cap) {
        throw new Error(
          `cycle-guard regression: @import recursion exceeded ${cap} reads ` +
            `(unbounded traversal — the seen-set guard is not terminating)`,
        );
      }
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

it('TEETH: a circular @import pair terminates via the cycle guard (bounded, no hang) (rf2-2l5mav)', () => {
  // a.css <-> b.css @import each other. WITH the `seen` guard the traversal
  // reads each file at most once and terminates; WITHOUT it the mutual recursion
  // runs forever. The bounded reader trips on an unbounded traversal, so a
  // dropped guard fails this test promptly and deterministically.
  const io = boundedReadIo({
    [cssPath('a.css')]: "@import url('b.css');",
    [cssPath('b.css')]: "@import url('a.css');",
  });
  const errors = [];
  checkCssImports(io, cssPath('a.css'), '_shared/css/a.css', errors, new Set());
  assert.deepStrictEqual(
    errors,
    [],
    `a circular but all-present import graph must terminate cleanly, got: ${errors.join(' | ')}`,
  );
  assert.ok(
    io.reads() <= 2,
    `the cycle guard must read each file in the pair at most once (got ${io.reads()} reads)`,
  );
});

it('TEETH: a self-referential @import terminates via the cycle guard (bounded, no hang) (rf2-2l5mav)', () => {
  // The degenerate cycle — a.css @imports itself. The guard must short-circuit
  // the immediate re-entry rather than recursing forever (bounded-read detected).
  const io = boundedReadIo({ [cssPath('a.css')]: "@import url('a.css');" });
  const errors = [];
  checkCssImports(io, cssPath('a.css'), '_shared/css/a.css', errors, new Set());
  assert.deepStrictEqual(
    errors,
    [],
    `a self-referential @import must terminate cleanly, got: ${errors.join(' | ')}`,
  );
  assert.ok(
    io.reads() <= 1,
    `a self-import must read the file exactly once (got ${io.reads()} reads)`,
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

// ---- TEETH: remote CSS url() fetches are REJECTED (rf2-o18ava) -----------
//
// rf2-o18ava found the gate enforced external CSS @import but SKIPPED remote
// `url(...)` fetches — a `@font-face { src: url(https://…) }` or a
// `background-image: url(//cdn…)` could re-introduce a third-party font/image
// request and stay green, despite the no-remote-styling contract. The contract
// is now fail-closed for url() too: an unallowlisted network url() in any
// scanned CSS fails the gate, while data: URIs and url(#fragment) stay exempt.

it('extractCssUrls extracts url() targets but SKIPS @import url() (owned by @import)', () => {
  const urls = extractCssUrls(
    [
      "@import url('structure.css');",
      "@font-face { src: url('https://fonts.example.com/a.woff2'); }",
      'body { background-image: url(//cdn.example.com/bg.png); }',
      '.x { cursor: url("img/cursor.png"), auto; }',
      '.y { mask-image: url(#grain); }',
      '.z { background: url(data:image/png;base64,AAAA); }',
    ].join('\n'),
  );
  // The @import target is NOT collected here.
  assert.ok(!urls.includes('structure.css'), 'extractCssUrls must skip @import url()');
  assert.ok(urls.includes('https://fonts.example.com/a.woff2'));
  assert.ok(urls.includes('//cdn.example.com/bg.png'));
  assert.ok(urls.includes('img/cursor.png'));
  // Fragment + data refs are returned RAW (so the caller can classify them).
  assert.ok(urls.includes('#grain'));
  assert.ok(urls.includes('data:image/png;base64,AAAA'));
});

it('TEETH: a remote @font-face src: url(https://…) is REJECTED', () => {
  // The exact rf2-o18ava repro: a remote web-font pulled in via url() rather
  // than an @import. The gate must fail it (and never resolve it on disk).
  const io = fullIo({
    [STYLE]: [
      "@import url('structure.css');",
      "@font-face { font-family: 'Inter';",
      "  src: url('https://fonts.example.com/inter.woff2') format('woff2'); }",
    ].join('\n'),
  });
  const { errors } = scanPage(io, PAGE);
  assert.ok(
    errors.some(
      (e) =>
        e.includes("CSS url('https://fonts.example.com/inter.woff2')") &&
        e.includes('rf2-o18ava'),
    ),
    `expected a rejected remote @font-face url(), got: ${errors.join(' | ')}`,
  );
  assert.ok(
    !errors.some((e) => e.includes('does not resolve to a file')),
    `a remote url() must never be checked on disk, got: ${errors.join(' | ')}`,
  );
});

it('TEETH: a protocol-relative background-image: url(//cdn…) is REJECTED', () => {
  const io = fullIo({
    [STYLE]: [
      "@import url('structure.css');",
      'body { background-image: url(//cdn.example.com/bg.png); }',
    ].join('\n'),
  });
  const { errors } = scanPage(io, PAGE);
  assert.ok(
    errors.some(
      (e) => e.includes("CSS url('//cdn.example.com/bg.png')") && e.includes('rf2-o18ava'),
    ),
    `expected a rejected protocol-relative url(), got: ${errors.join(' | ')}`,
  );
});

it('a data: url() and a url(#fragment) paint ref are NOT rejected (no network fetch)', () => {
  const io = fullIo({
    [STYLE]: [
      "@import url('structure.css');",
      '.icon { background: url(data:image/svg+xml,%3Csvg/%3E) no-repeat; }',
      '.grain { fill: url(#grain); }',
    ].join('\n'),
  });
  const { errors } = scanPage(io, PAGE);
  assert.deepStrictEqual(
    errors,
    [],
    `data: + url(#fragment) refs must not trip the network gate, got: ${errors.join(' | ')}`,
  );
});

// ---- TEETH: missing LOCAL CSS url() assets are REJECTED (rf2-35lfqo) -----
//
// rf2-35lfqo found the gate resolved local HTML asset refs and CSS @import
// targets, but NEVER resolved local `url(...)` references in a CSS body — a
// broken `background-image: url('missing-local.png')` / `cursor: url(...)` /
// `@font-face { src: url(...) }` referencing an absent file passed silently
// (the network-url policy skipped it because it fires no network request, and
// nothing else checked it on disk). Local CSS url() targets are now resolved
// against the declaring stylesheet's directory and a missing one fails the
// gate, while data:/url(#fragment)/network refs stay handled by their own
// policies.

it('TEETH: a missing LOCAL background-image: url() is REJECTED', () => {
  // url() resolves relative to the STYLESHEET (examples/_shared/css/), not the
  // page — so a bare 'missing-local.png' is looked for next to style.css.
  const io = fullIo({
    [STYLE]: [
      "@import url('structure.css');",
      ".hero { background-image: url('missing-local.png'); }",
    ].join('\n'),
  });
  const { errors } = scanPage(io, PAGE);
  assert.ok(
    errors.some(
      (e) =>
        e.includes("CSS url('missing-local.png')") &&
        e.includes('does not resolve to a file'),
    ),
    `expected a missing local CSS url() to be rejected, got: ${errors.join(' | ')}`,
  );
});

it('a present LOCAL CSS url() (font/cursor) resolves and scans clean', () => {
  // Two local url() targets that DO exist next to style.css must pass: a
  // @font-face src and a cursor image. Query/hash suffixes are stripped before
  // on-disk resolution (a cache-busting ?v=2 must not break the lookup).
  const FONT = path.join(EXAMPLES_ROOT, '_shared', 'css', 'inter.woff2');
  const CURSOR = path.join(EXAMPLES_ROOT, '_shared', 'css', 'img', 'cursor.png');
  const io = fullIo({
    [STYLE]: [
      "@import url('structure.css');",
      "@font-face { font-family: 'Inter'; src: url('inter.woff2?v=2') format('woff2'); }",
      '.x { cursor: url("img/cursor.png"), auto; }',
    ].join('\n'),
    [FONT]: 'WOFF2',
    [CURSOR]: 'PNGDATA',
  });
  const { errors } = scanPage(io, PAGE);
  assert.deepStrictEqual(
    errors,
    [],
    `present local CSS url() assets must scan clean, got: ${errors.join(' | ')}`,
  );
});

it('a remote CSS url() whose exact URL is allowlisted (with reason) scans clean', () => {
  // The scanner strips ?query/#hash before the allowlist lookup, so the key is
  // the query-stripped URL — sharing EXTERNAL_IMPORT_ALLOWLIST with @import.
  const written = 'https://fonts.example.com/inter.woff2?v=3';
  const allowKey = 'https://fonts.example.com/inter.woff2';
  const io = fullIo({
    [STYLE]: [
      "@import url('structure.css');",
      `@font-face { src: url('${written}') format('woff2'); }`,
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
    `an allowlisted remote url() should scan clean, got: ${errors.join(' | ')}`,
  );
});

it('TEETH: a remote url() in the _shared tree is rejected by checkSharedTree', () => {
  // checkSharedTree enforces the no-remote-styling contract directly on the
  // _shared source, independent of any page's reference graph.
  const io = makeIo({
    [path.join(SHARED_ROOT, 'css', 'style.css')]:
      GOOD_SHARED_STYLE +
      "\n@font-face { src: url('https://fonts.example.com/x.woff2'); }",
    [path.join(SHARED_ROOT, 'css', 'structure.css')]:
      '.send-form input[type="text"] { min-width: 240px; }\n' +
      '.cells-grid input { width: 56px; }\n' +
      RESPONSIVE_SHELL,
    [path.join(SHARED_ROOT, 'img', 'favicon.svg')]: '<svg/>',
    [path.join(SHARED_ROOT, 'img', 'og.png')]: VALID_OG_PNG,
    [path.join(SHARED_ROOT, 'img', 'og.svg')]: '<svg/>',
  });
  const errors = checkSharedTree(io, { sharedRoot: SHARED_ROOT });
  assert.ok(
    errors.some(
      (e) =>
        e.includes('style.css') &&
        e.includes('fonts.example.com') &&
        e.includes('rf2-o18ava'),
    ),
    `expected checkSharedTree to reject the remote url(), got: ${errors.join(' | ')}`,
  );
});

// ---- TEETH: direct-HTML network policy (rf2-bf4vdy) ---------------------
//
// Before rf2-bf4vdy the scanner SKIPPED every external HTML ref, so a CDN
// <script>, a hosted stylesheet/font <link>, an external <img>, or a hosted
// og:image stayed green. The contract is now fail-closed: an unallowlisted
// asset-bearing external HTML ref (http/https/protocol-relative) fails the
// gate, while NAVIGATION refs (anchors, #fragments, data: URIs) stay exempt.

it('TEETH: a direct external <script src> (CDN) is REJECTED', () => {
  const html = goodHtml().replace(
    '<script src="main.js"></script>',
    '<script src="https://cdn.example.com/sdk.js"></script>\n<script src="main.js"></script>',
  );
  const { errors } = scanPage(fullIo({ [PAGE]: html }), PAGE);
  assert.ok(
    errors.some(
      (e) => e.includes('<script src>') && e.includes('cdn.example.com') && e.includes('rf2-bf4vdy'),
    ),
    `expected a rejected external script, got: ${errors.join(' | ')}`,
  );
  // It is a policy rejection, NOT a missing-on-disk file (never resolved).
  assert.ok(
    !errors.some((e) => e.includes('cdn.example.com') && e.includes('does not resolve')),
    `an external script must never be checked on disk, got: ${errors.join(' | ')}`,
  );
});

it('TEETH: a direct external stylesheet <link href> (hosted CSS/font) is REJECTED', () => {
  const html = goodHtml().replace(
    '<link rel="stylesheet" href="_shared/css/style.css">',
    '<link rel="stylesheet" href="_shared/css/style.css">\n' +
      '<link rel="stylesheet" href="https://fonts.googleapis.com/css2?family=Inter">',
  );
  const { errors } = scanPage(fullIo({ [PAGE]: html }), PAGE);
  assert.ok(
    errors.some((e) => e.includes('link') && e.includes('fonts.googleapis.com')),
    `expected a rejected hosted stylesheet/font link, got: ${errors.join(' | ')}`,
  );
});

it('TEETH: a protocol-relative external <img src> is REJECTED', () => {
  const html = goodHtml().replace(
    '</head>',
    '</head>\n<img src="//img.example.com/hero.png">',
  );
  const { errors } = scanPage(fullIo({ [PAGE]: html }), PAGE);
  assert.ok(
    errors.some((e) => e.includes('<img src>') && e.includes('//img.example.com/hero.png')),
    `expected a rejected protocol-relative image, got: ${errors.join(' | ')}`,
  );
});

it('TEETH: an external (hosted) og:image is REJECTED as a network dep', () => {
  // An external og:image is the page's social card hosted off-site — it is a
  // load-time fetch from the scraper's view, so the network policy fires.
  const html = goodHtml().replace(
    '<meta property="og:image" content="_shared/img/og.png">',
    '<meta property="og:image" content="https://og.example.com/card.png">\n' +
      '<meta property="og:image" content="_shared/img/og.png">',
  );
  const { errors } = scanPage(fullIo({ [PAGE]: html }), PAGE);
  assert.ok(
    errors.some((e) => e.includes('og:image') && e.includes('og.example.com')),
    `expected a rejected hosted og:image, got: ${errors.join(' | ')}`,
  );
});

it('an external HTML ref whose exact URL is allowlisted (with reason) scans clean', () => {
  const written = 'https://cdn.example.com/sdk.js?v=2';
  const allowKey = 'https://cdn.example.com/sdk.js';
  const html = goodHtml().replace(
    '<script src="main.js"></script>',
    `<script src="${written}"></script>\n<script src="main.js"></script>`,
  );
  const { errors } = scanPage(fullIo({ [PAGE]: html }), PAGE, {
    externalHtmlRefAllowlist: {
      [allowKey]: { reason: 'deliberate remote SDK for this test' },
    },
  });
  assert.deepStrictEqual(
    errors,
    [],
    `an allowlisted external HTML ref should scan clean, got: ${errors.join(' | ')}`,
  );
});

it('a navigation <a href> to an external URL is NOT rejected (only assets are gated)', () => {
  // Inject the anchor at a REAL anchor point present in goodHtml() (just before
  // </body>) so it is actually in the scanned HTML. The old injection replaced
  // '<div id="app"></div>' — a string goodHtml() does NOT contain — so the
  // replace was a no-op, the anchor never appeared, and the assertion was
  // vacuous: it merely re-asserted goodHtml scans clean and would have stayed
  // green even if scanPage regressed to gate an external <a href> as an asset
  // (rf2-spaiyd). Guard against re-vacuating with an explicit presence check.
  const html = goodHtml().replace(
    '</body>',
    '<a href="https://re-frame2.org/docs">docs</a>\n</body>',
  );
  assert.ok(
    html.includes('<a href="https://re-frame2.org/docs">'),
    'the external anchor must be present in the scanned HTML — a no-op replace ' +
      'would leave it absent and re-vacuate this test (rf2-spaiyd)',
  );
  const { errors } = scanPage(fullIo({ [PAGE]: html }), PAGE);
  assert.deepStrictEqual(
    errors,
    [],
    `an external <a href> is navigation, not an asset fetch, got: ${errors.join(' | ')}`,
  );
});

it('the LIVE EXTERNAL_HTML_REF_ALLOWLIST is empty (no remote HTML asset deps shipped)', () => {
  assert.deepStrictEqual(
    Object.keys(EXTERNAL_HTML_REF_ALLOWLIST),
    [],
    'no shipped example page may load a remote script/stylesheet/font/image; ' +
      'the direct-HTML ref allowlist is fail-closed and starts empty (rf2-bf4vdy)',
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

// ---- TEETH: stories.index.html showcase page enforcement (rf2-x48bp4) ---
//
// The two Story showcase host pages (login/stories.index.html +
// nine_states/stories.index.html) carry the shared assets, but before the gate
// ENUMERATED them, a future edit could silently drop a required asset and stay
// green. The negative control: a stories.index.html missing style.css must
// fail the SAME required-asset contract as an index.html — proving enumeration
// gives the showcase pages teeth.

it('TEETH: a stories.index.html missing a required shared asset fails the gate (rf2-x48bp4)', () => {
  const showcase = path.join(
    EXAMPLES_ROOT, 'reagent', 'nine_states', 'stories.index.html',
  );
  const showcaseHtml = [
    '<!doctype html><html><head>',
    '<meta property="og:image" content="_shared/img/og.png">',
    '<link rel="icon" href="_shared/img/favicon.svg">',
    // style.css intentionally dropped — a regression a future edit could land.
    '</head><body><script src="main.js"></script></body></html>',
  ].join('\n');
  const io = makeIo({
    [showcase]: showcaseHtml,
    [FAVICON]: '<svg/>',
    [OG]: 'PNGDATA',
  });
  const { errors } = scanPage(io, showcase);
  assert.ok(
    errors.some(
      (e) =>
        e.includes('stories.index.html') &&
        e.includes("missing required shared asset reference '_shared/css/style.css'"),
    ),
    `expected the showcase page to be held to the shared-asset contract, got: ${errors.join(' | ')}`,
  );
});

it('a well-formed stories.index.html with all shared assets scans clean (rf2-x48bp4)', () => {
  const showcase = path.join(
    EXAMPLES_ROOT, 'reagent', 'login', 'stories.index.html',
  );
  const showcaseHtml = [
    '<!doctype html><html><head>',
    '<meta property="og:image" content="_shared/img/og.png">',
    '<link rel="icon" href="_shared/img/favicon.svg">',
    '<link rel="stylesheet" href="_shared/css/style.css">',
    '</head><body><script src="main.js"></script></body></html>',
  ].join('\n');
  const io = makeIo({
    [showcase]: showcaseHtml,
    [STYLE]: goodStyleCss(),
    [STRUCTURE]: '/* structure */',
    [FAVICON]: '<svg/>',
    [OG]: 'PNGDATA',
  });
  const { errors } = scanPage(io, showcase);
  assert.deepStrictEqual(
    errors,
    [],
    `a complete showcase page should scan clean, got: ${errors.join(' | ')}`,
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
// (SHARED_ROOT is declared up with the other path constants.) The structure.css
// always carries the responsive-shell rule so these cascade fixtures isolate
// their own concern rather than tripping the rf2-y82dk9 shell contract.
function sharedCssIo(structureCss) {
  return makeIo({
    // An AA-safe style.css so the contrast/focus contracts (rf2-febmqu +
    // rf2-mon7tz) are satisfied — this helper pins the CSS-CASCADE contract.
    [path.join(SHARED_ROOT, 'css', 'style.css')]: GOOD_SHARED_STYLE,
    [path.join(SHARED_ROOT, 'css', 'structure.css')]:
      structureCss + '\n' + RESPONSIVE_SHELL,
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

// ---- TEETH: responsive Xray-host shell contract (rf2-y82dk9) ------------
//
// The .rf2-testbed-shell is a fixed two-column flex (host flex-shrink:0 at
// ~560px + 320px min-width) — it overflows narrow viewports. The shared shell
// must encode a deliberate stack-below-breakpoint behaviour; a structure.css
// with NO responsive media query turns the gate RED.

const CASCADE_BASELINE = SCOPED_SENDFORM + '\n' + CELLS_INPUT;

// sharedCssIo appends RESPONSIVE_SHELL, so it always carries the responsive
// rule. To pin the NEGATIVE case (no responsive fallback) we build the io
// directly with a structure.css that omits it.
function noResponsiveIo(structureCss) {
  return makeIo({
    [path.join(SHARED_ROOT, 'css', 'style.css')]: GOOD_SHARED_STYLE,
    [path.join(SHARED_ROOT, 'css', 'structure.css')]: structureCss,
    [path.join(SHARED_ROOT, 'img', 'favicon.svg')]: '<svg/>',
    [path.join(SHARED_ROOT, 'img', 'og.png')]: VALID_OG_PNG,
    [path.join(SHARED_ROOT, 'img', 'og.svg')]: '<svg/>',
  });
}

it('LIVE: the shipped structure.css stacks the Xray-host shell below a breakpoint', () => {
  const errors = checkSharedTree(require('fs'));
  assert.ok(
    !errors.some((e) => e.includes('rf2-testbed-shell') && e.includes('rf2-y82dk9')),
    `the shipped shell must carry a responsive fallback, got: ${errors.join(' | ')}`,
  );
});

it('TEETH: a structure.css with no responsive shell media query is flagged', () => {
  const errors = checkSharedTree(noResponsiveIo(CASCADE_BASELINE), {
    sharedRoot: SHARED_ROOT,
  });
  assert.ok(
    errors.some(
      (e) => e.includes("'.rf2-testbed-shell'") && e.includes('rf2-y82dk9'),
    ),
    `expected a missing-responsive-shell error, got: ${errors.join(' | ')}`,
  );
});

it('TEETH: a max-width media query that does NOT stack the shell is still flagged', () => {
  // A media query that only tweaks padding (no flex-direction: column) does not
  // satisfy the stack contract.
  const bad =
    CASCADE_BASELINE +
    '\n@media (max-width: 900px) { .rf2-testbed-shell #app { padding: 1em; } }';
  const errors = checkSharedTree(noResponsiveIo(bad), { sharedRoot: SHARED_ROOT });
  assert.ok(
    errors.some(
      (e) => e.includes("'.rf2-testbed-shell'") && e.includes('rf2-y82dk9'),
    ),
    `a non-stacking media query must not satisfy the contract, got: ${errors.join(' | ')}`,
  );
});

it('TEETH: the responsive stacked-shell media query scans clean', () => {
  const good = CASCADE_BASELINE + '\n' + RESPONSIVE_SHELL;
  const errors = checkSharedTree(noResponsiveIo(good), { sharedRoot: SHARED_ROOT });
  assert.deepStrictEqual(
    errors,
    [],
    `the stacked-shell media query should scan clean, got: ${errors.join(' | ')}`,
  );
});

// ---- TEETH: OG source-art palette conformance (rf2-y82dk9) --------------
//
// og.svg is the editable master the shipped og.png is re-exported from; its
// colour literals mirror the --ex-* CSS tokens. The og.png byte-check is opaque
// to colour, so a shared-palette darkening (e.g. --ex-ink-faint #8A8270 →
// #6E6654, rf2-febmqu) can leave the source art stale and still pass. A retired
// /sub-AA literal used as a PAINT value in og.svg turns the gate RED — while a
// doc comment that NAMES the retired value (the migration note) does not.

// A full _shared tree with an overridable og.svg, so og.svg-only contracts can
// be pinned in isolation. structure.css carries the responsive rule + cascade
// baseline so only the og.svg concern can fail.
function ogSvgIo(ogSvg) {
  return makeIo({
    [path.join(SHARED_ROOT, 'css', 'style.css')]: GOOD_SHARED_STYLE,
    [path.join(SHARED_ROOT, 'css', 'structure.css')]:
      CASCADE_BASELINE + '\n' + RESPONSIVE_SHELL,
    [path.join(SHARED_ROOT, 'img', 'favicon.svg')]: '<svg/>',
    [path.join(SHARED_ROOT, 'img', 'og.png')]: VALID_OG_PNG,
    [path.join(SHARED_ROOT, 'img', 'og.svg')]: ogSvg,
  });
}

const RETIRED_INK_FAINT = '#8A8270';
const AA_SAFE_INK_FAINT = '#6E6654';

it('RETIRED_OG_SOURCE_COLORS names the retired #8A8270 → #6E6654 ink-faint migration', () => {
  const row = RETIRED_OG_SOURCE_COLORS.find((c) => c.retired === RETIRED_INK_FAINT);
  assert.ok(row, 'the retired #8A8270 ink-faint value must be registered');
  assert.strictEqual(row.replacement, AA_SAFE_INK_FAINT);
  assert.ok(row.reason && row.reason.length > 0, 'each retired colour carries a reason');
});

it('TEETH: the retired #8A8270 used as an og.svg fill is flagged', () => {
  const badSvg =
    '<svg xmlns="http://www.w3.org/2000/svg">' +
    `<text fill="${RETIRED_INK_FAINT}">REAGENT - UIX - HELIX</text></svg>`;
  const errors = checkSharedTree(ogSvgIo(badSvg), { sharedRoot: SHARED_ROOT });
  assert.ok(
    errors.some(
      (e) => e.includes('og.svg') && e.includes(RETIRED_INK_FAINT) && e.includes('rf2-y82dk9'),
    ),
    `expected a retired-source-colour error, got: ${errors.join(' | ')}`,
  );
});

it('TEETH: the retired colour as a stroke / stop-color is also flagged', () => {
  for (const attr of ['stroke', 'stop-color']) {
    const badSvg =
      '<svg xmlns="http://www.w3.org/2000/svg">' +
      `<line ${attr}="${RETIRED_INK_FAINT}"/></svg>`;
    const errors = checkSharedTree(ogSvgIo(badSvg), { sharedRoot: SHARED_ROOT });
    assert.ok(
      errors.some((e) => e.includes('og.svg') && e.includes(RETIRED_INK_FAINT)),
      `expected a retired-colour error for ${attr}, got: ${errors.join(' | ')}`,
    );
  }
});

it('a doc COMMENT naming the retired colour (migration note) does NOT trip the gate', () => {
  // The source-art header documents the #8A8270 → #6E6654 migration by naming
  // the retired value — that is prose, not a live paint, so it must scan clean.
  const docSvg =
    '<svg xmlns="http://www.w3.org/2000/svg">' +
    `<!-- faint ink ${AA_SAFE_INK_FAINT}: the old ${RETIRED_INK_FAINT} was sub-AA -->` +
    `<text fill="${AA_SAFE_INK_FAINT}">REAGENT</text></svg>`;
  const errors = checkSharedTree(ogSvgIo(docSvg), { sharedRoot: SHARED_ROOT });
  assert.deepStrictEqual(
    errors,
    [],
    `a doc-comment mention must not trip the gate, got: ${errors.join(' | ')}`,
  );
});

it('LIVE: the shipped og.svg uses no retired/sub-AA palette literal', () => {
  const errors = checkSharedTree(require('fs'));
  assert.ok(
    !errors.some((e) => e.includes('og.svg') && e.includes('rf2-y82dk9')),
    `the shipped source art must use the AA-safe palette, got: ${errors.join(' | ')}`,
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

// ---- rf2-cnu7qy: og:image content-BEFORE-property order -------------------
//
// HTML attribute order is insignificant, so a `content`-first og:image meta is
// valid. The old ordered regex only saw property-first metas, so a content-first
// SVG/remote card was INVISIBLE to the raster contract, disk resolution, and the
// network policy.

it('rf2-cnu7qy: extractOgImageRefs sees a content-BEFORE-property og:image', () => {
  assert.deepStrictEqual(
    extractOgImageRefs('<meta content="_shared/img/og.png" property="og:image">'),
    ['_shared/img/og.png'],
  );
});

it('rf2-cnu7qy: extractHtmlRefs + extractAssetRefs see a content-first og:image', () => {
  const html = '<meta content="https://og.example.com/card.png" property="og:image">';
  assert.ok(extractHtmlRefs(html).includes('https://og.example.com/card.png'));
  const tagged = extractAssetRefs(html);
  assert.strictEqual(
    Object.fromEntries(tagged.map((t) => [t.ref, t.source]))[
      'https://og.example.com/card.png'
    ],
    'og:image',
  );
});

it('TEETH rf2-cnu7qy: a content-first REMOTE og:image is REJECTED (was invisible)', () => {
  const html = goodHtml().replace(
    '<meta property="og:image" content="_shared/img/og.png">',
    '<meta property="og:image" content="_shared/img/og.png">\n' +
      '<meta content="https://og.example.com/card.png" property="og:image">',
  );
  const { errors } = scanPage(fullIo({ [PAGE]: html }), PAGE);
  assert.ok(
    errors.some((e) => e.includes('og:image') && e.includes('og.example.com')),
    `expected the content-first remote og:image to be rejected, got: ${errors.join(' | ')}`,
  );
});

it('TEETH rf2-cnu7qy: a content-first SVG og:image trips the raster contract', () => {
  const html = goodHtml().replace(
    '<meta property="og:image" content="_shared/img/og.png">',
    '<meta content="_shared/img/og.svg" property="og:image">',
  );
  const io = fullIo({ [PAGE]: html, [OG_SVG]: '<svg/>' });
  const { errors } = scanPage(io, PAGE);
  assert.ok(
    errors.some((e) => e.includes('og:image') && e.includes('not a raster')),
    `expected a non-raster og:image error, got: ${errors.join(' | ')}`,
  );
});

// ---- rf2-3dzb6h: unquoted HTML attribute values ---------------------------
//
// HTML5 permits unquoted attribute values. The old quoted-only regexes missed
// `<script src=main.js>` / `<link href=//cdn…>`, so an unquoted forbidden remote
// dep shipped green and an unquoted broken local ref was never resolved on disk.

it('rf2-3dzb6h: extractHtmlRefs reads an unquoted src/href', () => {
  const refs = extractHtmlRefs('<script src=main.js></script><link href=base.css>');
  assert.ok(refs.includes('main.js'));
  assert.ok(refs.includes('base.css'));
});

it('rf2-3dzb6h: extractAssetRefs tags an unquoted remote script src + link href', () => {
  const tagged = extractAssetRefs(
    '<script src=https://cdn.evil/x.js></script>\n' +
      '<link rel=stylesheet href=//cdn.evil/x.css>',
  );
  const byRef = Object.fromEntries(tagged.map((t) => [t.ref, t.source]));
  assert.ok(byRef['https://cdn.evil/x.js'] && byRef['https://cdn.evil/x.js'].includes('script'));
  assert.ok(byRef['//cdn.evil/x.css'] && byRef['//cdn.evil/x.css'].includes('link'));
});

it('TEETH rf2-3dzb6h: an unquoted remote <script src> is REJECTED (was invisible)', () => {
  const html = goodHtml().replace(
    '<script src="main.js"></script>',
    '<script src=https://cdn.example.com/sdk.js></script>\n<script src="main.js"></script>',
  );
  const { errors } = scanPage(fullIo({ [PAGE]: html }), PAGE);
  assert.ok(
    errors.some(
      (e) => e.includes('<script src>') && e.includes('cdn.example.com') && e.includes('rf2-bf4vdy'),
    ),
    `expected the unquoted external script to be rejected, got: ${errors.join(' | ')}`,
  );
});

// ---- rf2-lvw3z9: CSS block comments stripped before extraction ------------
//
// Commented-out CSS is inert. The extractors used to read a commented @import /
// url() as live, turning the gate RED on a maintainer's debug comment — and
// inconsistently with the og.svg check in the same file, which strips comments.

// A checkSharedTree fixture whose style.css is under test; the rest of the tree
// is valid so the ONLY thing that can turn it RED is the style.css itself.
// Shared by the CSS-comment (rf2-lvw3z9) and contrast-token (rf2-nrieg0) teeth.
const paletteIo = (styleCss) =>
  makeIo({
    [path.join(SHARED_ROOT, 'css', 'style.css')]: styleCss,
    [path.join(SHARED_ROOT, 'css', 'structure.css')]: CASCADE_BASELINE + '\n' + RESPONSIVE_SHELL,
    [path.join(SHARED_ROOT, 'img', 'favicon.svg')]: '<svg/>',
    [path.join(SHARED_ROOT, 'img', 'og.png')]: VALID_OG_PNG,
    [path.join(SHARED_ROOT, 'img', 'og.svg')]: '<svg/>',
  });

it('rf2-lvw3z9: extractCssImports ignores a commented-out @import', () => {
  const imports = extractCssImports(
    '/* @import url(https://fonts.googleapis.com/css2); */\n@import url("structure.css");',
  );
  assert.deepStrictEqual(imports, ['structure.css']);
});

it('rf2-lvw3z9: extractCssUrls ignores a commented-out url()', () => {
  const urls = extractCssUrls(
    '/* background: url(https://cdn.evil/x.png); */\n.a { background: url("real.png"); }',
  );
  assert.ok(urls.includes('real.png'));
  assert.ok(!urls.includes('https://cdn.evil/x.png'), 'a commented url() must not be read as live');
});

it('TEETH rf2-lvw3z9: a commented-out remote @import does NOT false-fail the gate', () => {
  const styleCss = '/* @import url(https://fonts.googleapis.com/css2?family=Inter); */\n' + GOOD_SHARED_STYLE;
  const errors = checkSharedTree(paletteIo(styleCss), { sharedRoot: SHARED_ROOT });
  assert.deepStrictEqual(
    errors,
    [],
    `an inert commented-out remote @import must not fail the gate, got: ${errors.join(' | ')}`,
  );
});

// ---- rf2-nrieg0: WCAG contrast on non-hex --ex-* tokens -------------------
//
// parseExTokens only read #hex, and the contrast loop silently `continue`d on a
// non-hex token, disabling the WCAG gate under an rgb()/hsl() palette refactor.
// Now rgb()/hsl() are parsed (and checked); a genuinely-opaque form (var()) is
// fail-loud rather than silently skipped.

it('rf2-nrieg0: colorToHex normalises #hex / rgb() / hsl(), null for var()', () => {
  assert.strictEqual(colorToHex('#C8741A'), '#C8741A');
  assert.strictEqual(colorToHex('#abc'), '#aabbcc');
  assert.strictEqual(colorToHex('rgb(200,116,26)'), '#c8741a');
  assert.strictEqual(colorToHex('rgba(200, 116, 26, 0.5)'), '#c8741a'); // alpha dropped
  assert.strictEqual(colorToHex('hsl(120,100%,50%)'), '#00ff00');
  assert.strictEqual(colorToHex('var(--ex-accent)'), null);
  assert.strictEqual(colorToHex('rebeccapurple'), null);
});

it('rf2-nrieg0: parseExTokens now captures rgb()/hsl() tokens (was hex-only)', () => {
  const tokens = parseExTokens(
    ':root{--ex-bg:#F7F3EC; --ex-accent-deep: rgb(156,79,14); --ex-warn: hsl(43,79%,43%);}',
  );
  assert.strictEqual(tokens['--ex-bg'], '#F7F3EC');
  assert.strictEqual(tokens['--ex-accent-deep'], '#9c4f0e'); // was DROPPED before
  assert.ok(tokens['--ex-warn'], 'an hsl() token must be captured, not dropped');
});

it('TEETH rf2-nrieg0: a sub-AA rgb() accent foreground now FAILS (was skipped)', () => {
  // The sub-AA amber #C8741A expressed as rgb() — previously invisible to the
  // gate (silent `continue`), now parsed and caught.
  const badStyle = GOOD_SHARED_STYLE.replace(
    '--ex-accent-deep: #9C4F0E;',
    '--ex-accent-deep: rgb(200,116,26);',
  );
  const errors = checkSharedTree(paletteIo(badStyle), { sharedRoot: SHARED_ROOT });
  assert.ok(
    errors.some((e) => e.includes('WCAG AA') && e.includes('rf2-febmqu')),
    `expected the rgb() sub-AA foreground to fail, got: ${errors.join(' | ')}`,
  );
});

it('TEETH rf2-nrieg0: a declared-but-unparseable var() contrast token FAILS LOUD', () => {
  const badStyle = GOOD_SHARED_STYLE.replace(
    '--ex-accent-deep: #9C4F0E;',
    '--ex-accent-deep: var(--ex-accent);',
  );
  const errors = checkSharedTree(paletteIo(badStyle), { sharedRoot: SHARED_ROOT });
  assert.ok(
    errors.some((e) => e.includes('rf2-nrieg0') && e.includes('cannot evaluate')),
    `expected a fail-loud unverifiable-token error, got: ${errors.join(' | ')}`,
  );
});

it('rf2-nrieg0: an AA-safe rgb() palette scans CLEAN (no false-fail)', () => {
  // #9C4F0E expressed as rgb() — parsed identically, must NOT false-fail.
  const rgbStyle = GOOD_SHARED_STYLE.replace(
    '--ex-accent-deep: #9C4F0E;',
    '--ex-accent-deep: rgb(156,79,14);',
  );
  const errors = checkSharedTree(paletteIo(rgbStyle), { sharedRoot: SHARED_ROOT });
  assert.deepStrictEqual(
    errors,
    [],
    `an AA-safe rgb() palette must scan clean, got: ${errors.join(' | ')}`,
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
