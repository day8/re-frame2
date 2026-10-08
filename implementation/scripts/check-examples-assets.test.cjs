#!/usr/bin/env node
/*
 * Tests for `examples/scripts/check-examples-assets.cjs`, the static examples
 * asset-contract gate: a LIVE scan of the real tree (the gate's teeth in
 * `npm run test:scripts`) plus synthetic in-memory fixtures pinning each
 * contract the scan enforces. A standalone node script, no test framework.
 */

'use strict';

const path = require('path');
const assert = require('assert');
const zlib = require('zlib');

const scanner = require('../../examples/scripts/check-examples-assets.cjs');
const {
  ALLOWLIST,
  EXTERNAL_IMPORT_ALLOWLIST,
  EXTERNAL_HTML_REF_ALLOWLIST,
  isExampleHostPage,
  isExternalRef,
  isNetworkRef,
  extractHtmlReferenceInventory,
  parseSrcset,
  extractCssImports,
  extractCssUrls,
  checkCssImports,
  scanPage,
  checkSharedTree,
  scanAll,
  listExampleIndexHtml,
  EXAMPLES_ROOT,
  PNG_SIGNATURE,
  validatePng,
  pngCrc32,
  checkSvgWellFormed,
  OG_PNG_WIDTH,
  OG_PNG_HEIGHT,
  contrastRatio,
  colorToHex,
} = scanner;
const { pageExemptions, stagedDestsByPage } = require('../../examples/scripts/examples-asset-manifest.cjs');

// A vendored-CSS page (both assets html-linked) and a staging-only entry (no
// html-linked asset, no exemption), keyed to synthetic pages.
const SYNTHETIC_MANIFEST = [
  {
    build: 'examples/synth-todomvc',
    page: 'examples/reagent/todomvc/index.html',
    reason: 'synthetic: vendored TodoMVC CSS instead of the shared stylesheet',
    assetExemptions: ['_shared/css/style.css'],
    assets: [
      { from: 'node-modules', src: 'todomvc-common/base.css', dest: 'base.css', htmlLinked: true },
      { from: 'node-modules', src: 'todomvc-app-css/index.css', dest: 'index.css', htmlLinked: true },
    ],
  },
  {
    build: 'examples/synth-fixture',
    page: 'examples/reagent/fixture/index.html',
    reason: 'synthetic: staging-only fixture (fetched from app code, not the HTML)',
    assetExemptions: [],
    assets: [{ from: 'src', src: 'api/data.json', dest: 'api/data.json', htmlLinked: false }],
  },
];

// A PNG with correct chunk CRCs whose IDAT deflates `raster`, by default the
// complete 8-bit RGB raster (zero filter tags) its IHDR declares.
function pngChunk(type, data) {
  const len = Buffer.alloc(4);
  len.writeUInt32BE(data.length);
  const typeBuf = Buffer.from(type, 'latin1');
  const crc = Buffer.alloc(4);
  crc.writeUInt32BE(pngCrc32(Buffer.concat([typeBuf, data])));
  return Buffer.concat([len, typeBuf, data, crc]);
}
function buildPng({ width = OG_PNG_WIDTH, height = OG_PNG_HEIGHT, raster } = {}) {
  const ihdr = Buffer.alloc(13);
  ihdr.writeUInt32BE(width, 0);
  ihdr.writeUInt32BE(height, 4);
  ihdr[8] = 8; // bit depth
  ihdr[9] = 2; // colour type: truecolour RGB
  return Buffer.concat([
    PNG_SIGNATURE,
    pngChunk('IHDR', ihdr),
    pngChunk('IDAT', zlib.deflateSync(raster || Buffer.alloc(height * (1 + width * 3)))),
    pngChunk('IEND', Buffer.alloc(0)),
  ]);
}
const VALID_OG_PNG = buildPng();

// A file map; a null value leaves that path absent.
function makeIo(files) {
  const map = new Map(
    Object.entries(files)
      .filter(([, v]) => v != null)
      .map(([k, v]) => [path.resolve(k), v]),
  );
  return {
    existsSync: (p) => map.has(path.resolve(p)),
    readFileSync: (p) => {
      if (!map.has(path.resolve(p))) {
        throw Object.assign(new Error(`ENOENT: ${p}`), { code: 'ENOENT' });
      }
      return map.get(path.resolve(p));
    },
  };
}

const PAGE = path.join(EXAMPLES_ROOT, 'reagent', 'demo', 'index.html');
const PAGE_DIR = path.dirname(PAGE);
const FAVICON = path.join(EXAMPLES_ROOT, '_shared', 'img', 'favicon.svg');
const OG = path.join(EXAMPLES_ROOT, '_shared', 'img', 'og.png');
const OG_SVG = path.join(EXAMPLES_ROOT, '_shared', 'img', 'og.svg');
const STYLE = path.join(EXAMPLES_ROOT, '_shared', 'css', 'style.css');
const STRUCTURE = path.join(EXAMPLES_ROOT, '_shared', 'css', 'structure.css');

// A page carrying all three shared assets and the boot script.
const GOOD_HTML = [
  '<!doctype html><html><head>',
  '<meta property="og:image" content="_shared/img/og.png">',
  '<link rel="icon" href="_shared/img/favicon.svg">',
  '<link rel="stylesheet" href="_shared/css/style.css">',
  '</head><body><script src="main.js"></script></body></html>',
].join('\n');
function fullIo(overrides = {}) {
  return makeIo({
    [PAGE]: GOOD_HTML,
    [STYLE]: "@import url('structure.css');\nbody { color: #1A1814; }",
    [STRUCTURE]: '/* structure */',
    [FAVICON]: '<svg/>',
    [OG]: 'PNGDATA',
    ...overrides,
  });
}

// A _shared tree that satisfies every checkSharedTree contract; a test
// overrides one file (null removes it) so only that file's contract can fail.
const FOCUS_RING =
  'input:focus-visible { border-color: var(--ex-accent-deep);\n  box-shadow: 0 0 0 3px var(--ex-accent-deep); }';
const GOOD_SHARED_STYLE = [
  "@import url('structure.css');",
  ':root {',
  '  --ex-bg: #F7F3EC; --ex-bg-raised: #FFFFFF; --ex-bg-sunken: #ECE7DC;',
  '  --ex-ink: #1A1814; --ex-ink-muted: #5C5448; --ex-ink-faint: #6E6654;',
  '  --ex-accent-deep: #9C4F0E; --ex-success: #4A7340; --ex-error: #B23A2E;',
  '}',
  FOCUS_RING,
].join('\n');
const SCOPED_SENDFORM =
  '.send-form input[type="text"] { padding: 8px 12px; flex: 1; min-width: 240px; }';
const CELLS_INPUT = '.cells-grid input { width: 56px; box-sizing: border-box; }';
const RESPONSIVE_SHELL =
  '@media (max-width: 900px) { .rf2-testbed-shell { flex-direction: column; } }';
const GOOD_STRUCTURE = [SCOPED_SENDFORM, CELLS_INPUT, RESPONSIVE_SHELL].join('\n');
const sharedErrors = (overrides = {}) =>
  checkSharedTree(
    makeIo({
      [STYLE]: GOOD_SHARED_STYLE,
      [STRUCTURE]: GOOD_STRUCTURE,
      [FAVICON]: '<svg/>',
      [OG]: VALID_OG_PNG,
      [OG_SVG]: '<svg/>',
      ...overrides,
    }),
  );
const commentOut = (rule) => `/* ${rule} */`;

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

console.log('check-examples-assets tests');

// ---- LIVE gate: scan the real repo; any violation is RED -------------------

const realIndexes = listExampleIndexHtml();

it('the real examples tree exposes a non-vacuous set of host pages', () => {
  assert.ok(
    realIndexes.length >= 10,
    `expected the full example set (>=10 host pages), got ` +
      `${realIndexes.length} — walk/layout drift; a vacuous gate is forbidden`,
  );
});

it('isExampleHostPage accepts index.html AND *.index.html, not arbitrary html', () => {
  assert.deepStrictEqual(
    ['index.html', 'stories.index.html', 'about.html'].map(isExampleHostPage),
    [true, true, false],
  );
});

// A dropped subtree would leave an ordinary, smaller array that can stay above
// the CLI floor and green an incomplete scan.
it('TEETH: listExampleIndexHtml FAILS CLOSED on an unreadable subtree (rf2-3fc89f.31)', () => {
  const realFs = require('fs');
  const badDir = path.join(EXAMPLES_ROOT, 'core');
  const io = {
    readdirSync: (dir, opts) => {
      if (path.resolve(dir) === badDir) {
        throw Object.assign(new Error(`EACCES: permission denied, scandir '${dir}'`), { code: 'EACCES' });
      }
      return realFs.readdirSync(dir, opts);
    },
  };
  assert.throws(
    () => listExampleIndexHtml(EXAMPLES_ROOT, { io }),
    (err) =>
      /enumeration FAILED/.test(err.message) &&
      err.message.includes(badDir) &&
      Array.isArray(err.walkErrors),
    'a directory-read failure must throw (naming the path), not silently drop the subtree',
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

// examples/_shared/README.md: every page carries the favicon and OG card; only
// TodoMVC skips the shared stylesheet.
it('LIVE: TodoMVC is the encoded style.css opt-out (allowlist, not a regression)', () => {
  assert.deepStrictEqual(ALLOWLIST['examples/core/todomvc/index.html'].assetExemptions, [
    '_shared/css/style.css',
  ]);
});

// ---- manifest projections --------------------------------------------------

it('pageExemptions projects a synthetic manifest: exemption + derived localAssets, staging-only entry excluded (rf2-phpbo8)', () => {
  assert.deepStrictEqual(pageExemptions(SYNTHETIC_MANIFEST), {
    'examples/reagent/todomvc/index.html': {
      reason: 'synthetic: vendored TodoMVC CSS instead of the shared stylesheet',
      assetExemptions: ['_shared/css/style.css'],
      localAssets: ['base.css', 'index.css'],
    },
  });
});

it('stagedDestsByPage projects every staged dest per page, staging-only entries included (rf2-3x7nj.44.2)', () => {
  assert.deepStrictEqual(stagedDestsByPage(SYNTHETIC_MANIFEST), {
    'examples/reagent/todomvc/index.html': ['base.css', 'index.css'],
    'examples/reagent/fixture/index.html': ['api/data.json'],
  });
});

// ---- extraction primitives -------------------------------------------------

it('extractCssImports reads url() and bare-string @import forms and ignores a commented-out @import', () => {
  assert.deepStrictEqual(
    extractCssImports(
      '/* @import url(https://fonts.googleapis.com/css2); */\n' +
        "@import url('a.css'); @import \"b.css\"; @import url(c.css);",
    ),
    ['a.css', 'b.css', 'c.css'],
  );
});

it('extractCssUrls returns raw url() targets, skipping @import url() and commented-out url()', () => {
  const css = [
    '/* background: url(https://cdn.evil/x.png); */',
    "@import url('structure.css');",
    "@font-face { src: url('https://fonts.example.com/a.woff2'); }",
    'body { background-image: url(//cdn.example.com/bg.png); }',
    '.x { cursor: url("img/cursor.png"), auto; }',
    '.y { mask-image: url(#grain); }',
    '.z { background: url(data:image/png;base64,AAAA); }',
  ].join('\n');
  assert.deepStrictEqual(extractCssUrls(css), [
    'https://fonts.example.com/a.woff2',
    '//cdn.example.com/bg.png',
    'img/cursor.png',
    '#grain',
    'data:image/png;base64,AAAA',
  ]);
});

it('isExternalRef / isNetworkRef: only http(s) and //host refs are network fetches; data: and #fragment are external but not network', () => {
  const refs = ['https://x.example/a.css', 'http://x.example/a.css', '//cdn.example.com/x.css', 'data:image/png,AA', '#frag', 'base.css'];
  assert.deepStrictEqual(refs.map((r) => [isExternalRef(r), isNetworkRef(r)]), [
    [true, true],
    [true, true],
    [true, true],
    [true, false],
    [true, false],
    [false, false],
  ]);
});

it('parseSrcset returns candidate URLs: descriptors dropped, a trailing comma splits, a data: URI keeps its commas', () => {
  assert.deepStrictEqual(
    [
      'hero-320.png 320w, hero-640.png 640w',
      'only.png',
      'a.png, b.png 2x',
      'data:image/svg+xml,%3Csvg%3E%3C/svg%3E 1x, next.png 2x',
    ].map((v) => parseSrcset(v)),
    [
      ['hero-320.png', 'hero-640.png'],
      ['only.png'],
      ['a.png', 'b.png'],
      ['data:image/svg+xml,%3Csvg%3E%3C/svg%3E', 'next.png'],
    ],
  );
});

// localRefs feed on-disk resolution, `assets` (the load-time fetches) feed the
// network and staging policies, ogImages feed the raster contract.
const inventoryOf = (html) => {
  const { localRefs, assets, ogImages } = extractHtmlReferenceInventory(html);
  return { localRefs: [...localRefs].sort(), assets: assets.map((a) => a.ref).sort(), ogImages };
};

it('inventory — srcset / imagesrcset candidates + <video> poster, local (rf2-arkvq8)', () => {
  const html = [
    '<img srcset="_shared/img/hero-320.png 320w, _shared/img/hero-640.png 640w">',
    '<source srcset="_shared/img/pic@2x.png 2x">',
    '<link rel="preload" as="image" imagesrcset="_shared/img/pre-1.png 1x">',
    '<video poster="_shared/img/poster.png"><source src="clip.mp4"></video>',
  ].join('\n');
  const refs = [
    '_shared/img/hero-320.png',
    '_shared/img/hero-640.png',
    '_shared/img/pic@2x.png',
    '_shared/img/poster.png',
    '_shared/img/pre-1.png',
    'clip.mp4',
  ];
  assert.deepStrictEqual(inventoryOf(html), { localRefs: refs, assets: refs, ogImages: [] });
});

it('inventory — inert HTML comment — an unterminated <!-- makes the rest of the document inert (rf2-j538f7.28)', () => {
  const html = [
    '<script src="live.js"></script>',
    '<!-- unterminated comment swallows the rest',
    '<link rel="stylesheet" href="never.css">',
    '<meta property="og:image" content="never-og.png">',
  ].join('\n');
  assert.deepStrictEqual(inventoryOf(html), { localRefs: ['live.js'], assets: ['live.js'], ogImages: [] });
});

// ---- scanPage: resolution, required assets, staging ------------------------

it('a well-formed page with all assets present scans clean', () => {
  assert.deepStrictEqual(scanPage(fullIo(), PAGE).errors, []);
});

it('TEETH: a missing _shared favicon is reported', () => {
  const { errors } = scanPage(fullIo({ [FAVICON]: null }), PAGE);
  assert.deepStrictEqual(
    errors.map((e) =>
      e.includes("asset '_shared/img/favicon.svg' does not resolve to a file (looked for examples/_shared/img/favicon.svg)"),
    ),
    [true],
    errors.join(' | '),
  );
});

it('TEETH: required shared refs that exist ONLY inside an HTML comment are reported missing (rf2-j538f7.28)', () => {
  const html = [
    '<!--',
    '<meta property="og:image" content="_shared/img/og.png">',
    '<link rel="icon" href="_shared/img/favicon.svg">',
    '<link rel="stylesheet" href="_shared/css/style.css">',
    '-->',
    '<script src="main.js"></script>',
  ].join('\n');
  const { errors } = scanPage(fullIo({ [PAGE]: html }), PAGE);
  assert.deepStrictEqual(
    errors.map((e) => (e.match(/missing required shared asset reference '([^']+)'/) || [])[1]).sort(),
    ['_shared/css/style.css', '_shared/img/favicon.svg', '_shared/img/og.png'],
    errors.join(' | '),
  );
});

// dev:example serves only index.html, _shared/ and the manifest's dests, so a
// colocated asset that resolves in source still 404s unless declared.
it('TEETH: a colocated page-local asset present in source but never staged is reported (rf2-3x7nj.44.2)', () => {
  const html = GOOD_HTML.replace(
    '</head>',
    '<link rel="stylesheet" href="notebook.css">\n<img src="img/diagram.png">\n</head>',
  );
  const io = fullIo({
    [PAGE]: html,
    [path.join(PAGE_DIR, 'notebook.css')]: 'body {}',
    [path.join(PAGE_DIR, 'img', 'diagram.png')]: 'PNGDATA',
  });
  const { errors } = scanPage(io, PAGE);
  assert.deepStrictEqual(
    errors.map((e) => (e.match(/page-local asset '([^']+)' exists in source but dev:example never stages it/) || [])[1]).sort(),
    ['img/diagram.png', 'notebook.css'],
    errors.join(' | '),
  );
});

it('TEETH: an allowlisted page still REQUIRES favicon + OG (opt-out is stylesheet-only)', () => {
  const todoPage = path.join(EXAMPLES_ROOT, 'reagent', 'todomvc', 'index.html');
  const todoHtml = [
    '<meta property="og:image" content="_shared/img/og.png">',
    '<link rel="stylesheet" href="base.css">',
    '<link rel="stylesheet" href="index.css">',
    '<script src="main.js"></script>',
  ].join('\n');
  const { errors } = scanPage(makeIo({ [todoPage]: todoHtml, [OG]: 'PNGDATA' }), todoPage, {
    allowlist: pageExemptions(SYNTHETIC_MANIFEST),
  });
  assert.ok(
    errors.some((e) => e.includes("missing required shared asset reference '_shared/img/favicon.svg'")),
    `a stylesheet-only opt-out must still require the favicon, got: ${errors.join(' | ')}`,
  );
});

it('TEETH: a stale exemption (page DOES reference the exempt asset) is flagged', () => {
  const allowlist = {
    'examples/reagent/demo/index.html': { reason: 'stale', assetExemptions: ['_shared/css/style.css'] },
  };
  const { errors } = scanPage(fullIo(), PAGE, { allowlist });
  assert.ok(
    errors.some((e) => e.includes('stale exemption')),
    `expected a stale-exemption error, got: ${errors.join(' | ')}`,
  );
});

it('TEETH: an SVG og:image is flagged as a non-raster social-preview asset', () => {
  const html = GOOD_HTML.replace(
    '<meta property="og:image" content="_shared/img/og.png">',
    '<meta property="og:image" content="_shared/img/og.svg">',
  );
  const { errors } = scanPage(fullIo({ [PAGE]: html, [OG_SVG]: '<svg/>' }), PAGE);
  assert.ok(
    errors.some((e) => e.includes('og:image') && e.includes('not a raster')),
    `expected a non-raster og:image error, got: ${errors.join(' | ')}`,
  );
});

// ---- CSS: @import resolution, recursion, remote and local url() ------------

const cssPath = (name) => path.join(path.dirname(STYLE), name);

it('TEETH: a style.css @import to a missing structure.css is reported', () => {
  const { errors } = scanPage(fullIo({ [STRUCTURE]: null }), PAGE);
  assert.deepStrictEqual(
    errors.map((e) =>
      e.includes("@import 'structure.css' does not resolve to a file (looked for examples/_shared/css/structure.css)"),
    ),
    [true],
    errors.join(' | '),
  );
});

it('TEETH: a broken @import two levels deep is reported once (multi-level recursion) (rf2-2l5mav)', () => {
  const io = makeIo({
    [cssPath('style.css')]: "@import url('a.css');",
    [cssPath('a.css')]: "@import url('b.css');",
    [cssPath('b.css')]: "@import url('missing-deep.css');",
  });
  const errors = [];
  checkCssImports(io, cssPath('style.css'), '_shared/css/style.css', errors, new Set());
  assert.deepStrictEqual(errors, [
    "_shared/css/style.css -> a.css -> b.css: @import 'missing-deep.css' does not resolve " +
      'to a file (looked for examples/_shared/css/missing-deep.css)',
  ]);
});

// The read counter turns a dropped `seen` guard into a prompt failure instead
// of an unbounded recursion; the cap keeps that failure from hanging.
it('TEETH: a circular @import pair terminates via the cycle guard (bounded, no hang) (rf2-2l5mav)', () => {
  const files = makeIo({
    [cssPath('a.css')]: "@import url('b.css');",
    [cssPath('b.css')]: "@import url('a.css');",
  });
  let reads = 0;
  const io = {
    existsSync: files.existsSync,
    readFileSync: (p) => {
      if ((reads += 1) > 50) throw new Error('@import recursion exceeded 50 reads');
      return files.readFileSync(p);
    },
  };
  const errors = [];
  checkCssImports(io, cssPath('a.css'), '_shared/css/a.css', errors, new Set());
  assert.deepStrictEqual(errors, [], `a circular import graph must terminate cleanly, got: ${errors.join(' | ')}`);
  assert.ok(reads <= 2, `the cycle guard must read each file in the pair at most once (got ${reads} reads)`);
});

it('TEETH: an unallowlisted external Google-Fonts @import is REJECTED', () => {
  const io = fullIo({
    [STYLE]: "@import url('https://fonts.googleapis.com/css2?family=Inter');\n@import url('structure.css');",
  });
  const { errors } = scanPage(io, PAGE);
  assert.deepStrictEqual(
    errors.map((e) => e.includes("external @import 'https://fonts.googleapis.com/css2'")),
    [true],
    errors.join(' | '),
  );
});

it('TEETH: a remote @font-face src: url(https://…) is REJECTED', () => {
  const io = fullIo({
    [STYLE]: [
      "@import url('structure.css');",
      "@font-face { font-family: 'Inter';",
      "  src: url('https://fonts.example.com/inter.woff2') format('woff2'); }",
    ].join('\n'),
  });
  const { errors } = scanPage(io, PAGE);
  assert.deepStrictEqual(
    errors.map(
      (e) =>
        e.includes("CSS url('https://fonts.example.com/inter.woff2')") &&
        e.includes('Remote CSS url() fetches are forbidden'),
    ),
    [true],
    errors.join(' | '),
  );
});

it('TEETH: a missing LOCAL background-image: url() is REJECTED', () => {
  const io = fullIo({
    [STYLE]: "@import url('structure.css');\n.hero { background-image: url('missing-local.png'); }",
  });
  const { errors } = scanPage(io, PAGE);
  assert.deepStrictEqual(
    errors.map((e) =>
      e.includes("CSS url('missing-local.png') does not resolve to a file (looked for examples/_shared/css/missing-local.png)"),
    ),
    [true],
    errors.join(' | '),
  );
});

// examples/_shared/README.md: examples make no third-party requests.
it('the LIVE external-ref allowlists are empty (no remote CSS or HTML asset deps shipped)', () => {
  assert.deepStrictEqual(
    [Object.keys(EXTERNAL_IMPORT_ALLOWLIST), Object.keys(EXTERNAL_HTML_REF_ALLOWLIST)],
    [[], []],
  );
});

// ---- direct-HTML network policy --------------------------------------------

it('TEETH: a direct external <script src> (CDN) is REJECTED', () => {
  const html = GOOD_HTML.replace(
    '<script src="main.js"></script>',
    '<script src="https://cdn.example.com/sdk.js"></script>\n<script src="main.js"></script>',
  );
  const { errors } = scanPage(fullIo({ [PAGE]: html }), PAGE);
  assert.deepStrictEqual(
    errors.map(
      (e) =>
        e.includes("<script src> 'https://cdn.example.com/sdk.js'") &&
        e.includes('Direct-HTML external asset refs'),
    ),
    [true],
    errors.join(' | '),
  );
});

it('TEETH: a direct external stylesheet <link href> (hosted CSS/font) is REJECTED', () => {
  const html = GOOD_HTML.replace(
    '<link rel="stylesheet" href="_shared/css/style.css">',
    '<link rel="stylesheet" href="_shared/css/style.css">\n' +
      '<link rel="stylesheet" href="https://fonts.googleapis.com/css2?family=Inter">',
  );
  const { errors } = scanPage(fullIo({ [PAGE]: html }), PAGE);
  assert.deepStrictEqual(
    errors.map((e) => e.includes('link') && e.includes("'https://fonts.googleapis.com/css2'")),
    [true],
    errors.join(' | '),
  );
});

it('TEETH rf2-cnu7qy: a content-first REMOTE og:image is REJECTED (was invisible)', () => {
  const html = GOOD_HTML.replace(
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

// ---- the boot-script contract ----------------------------------------------
//
// main.js is exempt from on-disk resolution (it is build output), so only this
// contract makes its absence observable; a mere mention of main.js is not a boot.

const BOOT_ERROR = /no live '<script src="main\.js">'/;
const bootScriptReplacedBy = (markup) => GOOD_HTML.replace('<script src="main.js"></script>', markup);
const relOf = (abs) => path.relative(scanner.REPO_ROOT, abs).split(path.sep).join('/');

it('TEETH rf2-y1kbf: an otherwise-valid page with NO boot script is REJECTED, naming page + main.js', () => {
  const { errors, relIndex } = scanPage(fullIo({ [PAGE]: bootScriptReplacedBy('') }), PAGE);
  assert.deepStrictEqual(
    errors.map((e) => BOOT_ERROR.test(e) && e.startsWith(`${relIndex}: `)),
    [true],
    errors.join(' | '),
  );
});

it('TEETH: a preload <link> or an <a href> naming main.js does NOT satisfy the boot contract', () => {
  for (const impostor of ['<link rel="preload" as="script" href="main.js">', '<a href="main.js">the bundle</a>']) {
    const { errors } = scanPage(fullIo({ [PAGE]: bootScriptReplacedBy(impostor) }), PAGE);
    assert.deepStrictEqual(errors.map((e) => BOOT_ERROR.test(e)), [true], `${impostor}: ${errors.join(' | ')}`);
  }
});

it('a single-quoted or unquoted boot script satisfies the contract (page scans clean)', () => {
  for (const live of ["<script src='main.js'></script>", '<script src=main.js></script>']) {
    const { errors } = scanPage(fullIo({ [PAGE]: bootScriptReplacedBy(live) }), PAGE);
    assert.deepStrictEqual(errors, [], `${live}: ${errors.join(' | ')}`);
  }
});

// The full `<script … src=main.js …></script>` element in any HTML5 quoting.
const bootScriptElements = (html) =>
  html.match(
    /<script\b[^>]*(?<![-\w])src\s*=\s*(?:"main\.js"|'main\.js'|main\.js)[^>]*>[\s\S]*?<\/script>/gi,
  ) || [];

// The live sweep must reach both host shapes, so neither is pruned unseen.
const hostShape = (rel) => (rel.split('/').includes('ssr') ? 'ssr' : 'ordinary');
const HOST_SHAPES = ['ordinary', 'ssr'];

// Serves one real host page with a mutated body; nothing on disk is touched.
function mutatedPageIo(pageAbsPath, mutatedHtml) {
  const realFs = require('fs');
  const target = path.resolve(pageAbsPath);
  return {
    existsSync: (p) => realFs.existsSync(p),
    readFileSync: (p, ...rest) =>
      path.resolve(p) === target ? mutatedHtml : realFs.readFileSync(p, ...rest),
  };
}

// The non-vacuity control: the production scan over the real tree, with one
// real host's boot script removed, must go red for that reason alone.
it('TEETH rf2-y1kbf: deleting a REAL host\'s live boot script IN MEMORY turns the PRODUCTION scan RED (every live host shape)', () => {
  for (const shape of HOST_SHAPES) {
    const abs = realIndexes.find((p) => hostShape(relOf(p)) === shape);
    assert.ok(abs, `no '${shape}' host enumerated — this control would be vacuous`);
    const rel = relOf(abs);
    const original = require('fs').readFileSync(abs, 'utf8');
    const mutated = original.replace(bootScriptElements(original)[0], '');
    const { errors } = scanPage(mutatedPageIo(abs, mutated), abs);
    assert.deepStrictEqual(
      errors.map((e) => BOOT_ERROR.test(e) && e.includes(rel)),
      [true],
      `${rel} (${shape}): a real host with its boot script deleted must fail the gate for that ` +
        `reason alone, got: ${errors.length === 0 ? '(no errors)' : errors.join(' | ')}`,
    );
  }
});

// ---- og.png raster validation ----------------------------------------------

it('TEETH: a missing og.png raster is reported by checkSharedTree', () => {
  const errors = sharedErrors({ [OG]: null });
  assert.ok(errors.some((e) => e.includes('og.png')), `expected a missing-og.png error, got: ${errors.join(' | ')}`);
});

it('TEETH: checkSharedTree rejects a wrong-dimension og.png', () => {
  const errors = sharedErrors({ [OG]: buildPng({ width: 800, height: 600 }) });
  assert.ok(
    errors.some((e) => e.includes('og.png') && e.includes('800x600')),
    `expected a wrong-dimension og.png error, got: ${errors.join(' | ')}`,
  );
});

it('TEETH: >=24 bytes with the wrong signature fails validatePng at the signature gate (rf2-bdamni)', () => {
  assert.match(validatePng(Buffer.alloc(24, 0x20)).reason, /signature/);
});

it('TEETH: a 24-byte header-only prefix is REJECTED (the pre-fix false-green)', () => {
  assert.match(validatePng(buildPng().subarray(0, 24)).reason, /past end of file|truncated/);
});

it('TEETH: a byte-flipped IDAT (bad CRC) is REJECTED', () => {
  const corrupt = Buffer.from(buildPng());
  corrupt[43] ^= 0xff; // inside the IDAT data: sig(8) + IHDR(25) + IDAT length + type(8)
  assert.match(validatePng(corrupt).reason, /CRC mismatch/);
});

it('TEETH: a PNG missing its terminal IEND is REJECTED', () => {
  const full = buildPng();
  assert.match(validatePng(full.subarray(0, full.length - 12)).reason, /IEND/);
});

// zlib inflation is not PNG decoding: a well-formed stream must still expand to
// exactly height x (1 + width*3) = 2268630 bytes.
it('TEETH: a zlib-valid IDAT inflating to the wrong raster size (4 bytes, or one byte over) is REJECTED', () => {
  for (const size of [4, 2268630 + 1]) {
    const v = validatePng(buildPng({ raster: Buffer.alloc(size) }));
    assert.ok(
      String(v.reason).startsWith(`decompressed image data is ${size} byte(s), expected 2268630 `),
      `got: ${v.reason}`,
    );
  }
});

// ---- SVG well-formedness ---------------------------------------------------

it('TEETH: checkSharedTree reports a targeted failure for a favicon.svg with a "--" comment', () => {
  const errors = sharedErrors({
    [FAVICON]: '<svg xmlns="http://www.w3.org/2000/svg"><!-- mirror the --ex-* palette --><rect/></svg>',
  });
  assert.ok(
    errors.some((e) => e.includes('favicon.svg') && e.includes("illegal '--'")),
    `expected a targeted favicon well-formedness error, got: ${errors.join(' | ')}`,
  );
});

it('TEETH: checkSvgWellFormed flags a mismatched tag, an unclosed element, an unterminated comment and a markup-free document', () => {
  for (const [svg, defect] of [
    ['<svg><rect></svg>', 'mismatched closing tag'],
    ['<svg><g><rect/>', 'unclosed element'],
    ['<svg><!-- never closed </svg>', 'unterminated XML comment'],
    ['   just text, no markup   ', 'no element found'],
  ]) {
    const errors = checkSvgWellFormed(svg, 'x.svg');
    assert.ok(errors.some((e) => e.includes(defect)), `${svg}: expected '${defect}', got: ${errors.join(' | ')}`);
  }
});

// ---- shared palette contrast + focus indicator ----------------------------

it('TEETH: a sub-AA accent foreground in style.css fails checkSharedTree', () => {
  // #C8741A is the 3.18:1 amber.
  const style = GOOD_SHARED_STYLE.replace('--ex-accent-deep: #9C4F0E;', '--ex-accent-deep: #C8741A;');
  const errors = sharedErrors({ [STYLE]: style });
  assert.ok(
    errors.some((e) => e.includes('WCAG AA') && e.includes('Use an AA-safe token')),
    `expected a sub-AA contrast error, got: ${errors.join(' | ')}`,
  );
});

it('contrastRatio matches a known pair (white on #9C4F0E ≈ 5.94)', () => {
  const r = contrastRatio('#FFFFFF', '#9C4F0E');
  assert.ok(Math.abs(r - 5.94) < 0.05, `expected ≈5.94, got ${r.toFixed(2)}`);
});

it('colorToHex normalises #hex / rgb() / hsl() to opaque #rrggbb and returns null for what it cannot compute', () => {
  const rows = [
    ['#C8741A', '#C8741A'],
    ['#abc', '#aabbcc'],
    ['#aabbccdd', '#aabbcc'],
    ['#abcde', null],
    ['rgb(200,116,26)', '#c8741a'],
    ['rgb(50%,50%,50%)', '#808080'],
    ['rgb(300,0,0)', null],
    ['hsl(120,100%,50%)', '#00ff00'],
    ['var(--ex-accent)', null],
  ];
  assert.deepStrictEqual(rows.map(([v]) => colorToHex(v)), rows.map(([, hex]) => hex));
});

it('TEETH rf2-nrieg0: a declared-but-unparseable var() contrast token FAILS LOUD', () => {
  const style = GOOD_SHARED_STYLE.replace('--ex-accent-deep: #9C4F0E;', '--ex-accent-deep: var(--ex-accent);');
  const errors = sharedErrors({ [STYLE]: style });
  assert.ok(
    errors.some((e) => e.includes('is declared as') && e.includes('cannot evaluate')),
    `expected a fail-loud unverifiable-token error, got: ${errors.join(' | ')}`,
  );
});

it('TEETH: the old low-alpha amber focus ring rgba(200,116,26,0.18) is rejected', () => {
  const style = GOOD_SHARED_STYLE + '\ninput:focus { outline: none; box-shadow: 0 0 0 3px rgba(200,116,26,0.18); }';
  const errors = sharedErrors({ [STYLE]: style });
  assert.ok(
    errors.some((e) => e.includes('low-alpha amber') && e.includes('below the 3:1 focus-indicator bar')),
    `expected the low-alpha amber ring to be rejected, got: ${errors.join(' | ')}`,
  );
});

// A commented-out rule is gone as far as the browser is concerned, so the
// presence checks read comment-stripped CSS.
it('TEETH: a commented-out :focus-visible ring reads red (rf2-3x7nj.44.3)', () => {
  const errors = sharedErrors({ [STYLE]: GOOD_SHARED_STYLE.replace(FOCUS_RING, commentOut(FOCUS_RING)) });
  assert.ok(
    errors.some((e) => e.includes(':focus-visible') && e.includes('must carry a visible')),
    `a commented-out focus ring must read red, got: ${errors.join(' | ')}`,
  );
});

// ---- shared CSS cascade + responsive shell ---------------------------------

it('a clean _shared tree (scoped send-form, cells width, stacked shell) scans clean', () => {
  const errors = sharedErrors();
  assert.deepStrictEqual(errors, [], `the clean fixture tree should scan clean, got: ${errors.join(' | ')}`);
});

it('TEETH: a bare global input[type="text"] rule is flagged (Cells blowout)', () => {
  const errors = sharedErrors({ [STRUCTURE]: 'input[type="text"] { min-width: 240px; }\n' + GOOD_STRUCTURE });
  assert.ok(
    errors.some((e) => e.includes('GLOBAL') && e.includes('input[type="text"]')),
    `expected a global-text-input error, got: ${errors.join(' | ')}`,
  );
});

it('TEETH: a commented-out .cells-grid input width:56px reads red (rf2-3x7nj.44.3)', () => {
  const errors = sharedErrors({ [STRUCTURE]: GOOD_STRUCTURE.replace(CELLS_INPUT, commentOut(CELLS_INPUT)) });
  assert.ok(
    errors.some((e) => e.includes('width: 56px') && e.includes('must pin the compact')),
    `a commented-out cells-grid width must read red, got: ${errors.join(' | ')}`,
  );
});

it('TEETH: a max-width media query that does NOT stack the shell is still flagged', () => {
  const nonStacking = '@media (max-width: 900px) { .rf2-testbed-shell #app { padding: 1em; } }';
  const errors = sharedErrors({ [STRUCTURE]: GOOD_STRUCTURE.replace(RESPONSIVE_SHELL, nonStacking) });
  assert.ok(
    errors.some((e) => e.includes("'.rf2-testbed-shell'") && e.includes('no responsive fallback')),
    `a non-stacking media query must not satisfy the contract, got: ${errors.join(' | ')}`,
  );
});

// ---- OG source-art palette -------------------------------------------------

it('TEETH: the retired #8A8270 used as an og.svg fill is flagged', () => {
  const errors = sharedErrors({
    [OG_SVG]: '<svg xmlns="http://www.w3.org/2000/svg"><text fill="#8A8270">REAGENT</text></svg>',
  });
  assert.ok(
    errors.some((e) => e.includes('og.svg') && e.includes('#8A8270') && e.includes('source art uses the retired')),
    `expected a retired-source-colour error, got: ${errors.join(' | ')}`,
  );
});

if (failed > 0) {
  console.error(`\ncheck-examples-assets tests: ${failed} failed.`);
  process.exit(1);
}
console.log('\ncheck-examples-assets tests: all passed.');
