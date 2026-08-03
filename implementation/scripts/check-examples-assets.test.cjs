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
  isStandaloneExampleProject,
  isExternalRef,
  isNetworkRef,
  extractHtmlReferenceInventory,
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
  PNG_SIGNATURE,
  validatePng,
  pngCrc32,
  checkSvgWellFormed,
  OG_PNG_WIDTH,
  OG_PNG_HEIGHT,
  contrastRatio,
  colorToHex,
  parseExTokens,
  sharedContrastContract,
  RETIRED_OG_SOURCE_COLORS,
} = scanner;

// The shared examples asset/exception manifest — the single owner the scanner's
// ALLOWLIST is now the page-opt-out projection of (rf2-phpbo8). Imported here so
// the scanner-consumer tests can inject a SYNTHETIC manifest and pin the real
// manifest's cross-consistency, rather than hand-writing allowlist literals.
const manifest = require('../../examples/scripts/examples-asset-manifest.cjs');
const { pageExemptions, stagedAssetsByBuild, EXAMPLE_ASSET_MANIFEST } = manifest;

// A SYNTHETIC manifest with a vendored-CSS page (both assets html-linked, so the
// scanner must accept them as page-local refs) and a staging-only fixture entry
// (not html-linked and no shared-asset exemption, so it must NOT surface in the
// scanner projection). Keyed to a `examples/reagent/todomvc/...` page so it lines
// up with the synthetic TodoMVC page the scanPage tests below build.
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

// Build a STRUCTURALLY + SEMANTICALLY complete PNG (signature + IHDR + a full
// raster IDAT + IEND) at the given dimensions, as a Buffer, for the decode teeth
// (rf2-3fc89f.27 + rf2-j538f7.24). Uses the scanner's own pngCrc32 to write
// correct chunk CRCs and deflates the WHOLE declared raster — `height` scanlines
// of a 0/None filter tag + a width*3-byte 8-bit RGB pixel row (matching the
// shipped og.png's truecolour encoding) — so validatePng's CRC, inflate,
// IHDR-semantics, exact-scanline-geometry, and filter-byte checks all see a
// genuinely valid image. The corrupt/truncated/oversized fixtures below are
// derived by mutating this baseline, isolating exactly one defect each.
const zlibForTests = require('zlib');
function pngChunk(type, data) {
  const len = Buffer.alloc(4);
  len.writeUInt32BE(data.length);
  const typeBuf = Buffer.from(type, 'latin1');
  const crc = Buffer.alloc(4);
  crc.writeUInt32BE(pngCrc32(Buffer.concat([typeBuf, data])));
  return Buffer.concat([len, typeBuf, data, crc]);
}
function buildPng(width = OG_PNG_WIDTH, height = OG_PNG_HEIGHT) {
  const ihdr = Buffer.alloc(13);
  ihdr.writeUInt32BE(width, 0);
  ihdr.writeUInt32BE(height, 4);
  ihdr[8] = 8; // bit depth
  ihdr[9] = 2; // colour type (2 = truecolour RGB → 3 channels)
  ihdr[10] = 0; // compression method (deflate)
  ihdr[11] = 0; // filter method
  ihdr[12] = 0; // interlace method (none)
  // A FULL raster: height scanlines, each a 1-byte filter tag (0/None, from the
  // zero-fill) + a width*3-byte RGB pixel row. Buffer.alloc zero-fills, so every
  // scanline's leading filter byte is 0 (legal) and the pixels are opaque black.
  const raster = Buffer.alloc(height * (1 + width * 3));
  return Buffer.concat([
    Buffer.from(PNG_SIGNATURE),
    pngChunk('IHDR', ihdr),
    pngChunk('IDAT', zlibForTests.deflateSync(raster)),
    pngChunk('IEND', Buffer.alloc(0)),
  ]);
}

// A real, decodable 1200x630 PNG used wherever a fixture's _shared tree must scan
// clean. The gate now validates the og.png BYTES down to the full raster geometry
// (rf2-mon7tz + rf2-j538f7.24), so the old base64 blob — a 1200x630 header over a
// 1-byte IDAT payload — no longer passes; buildPng() supplies a genuinely
// complete raster instead. Stored latin1 so the synthetic io (which returns the
// stored value verbatim) round-trips the bytes; validatePng coerces it back to a
// Buffer the same way.
const VALID_OG_PNG = buildPng().toString('latin1');

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

// ---- STANDALONE example projects are pruned from the walk (rf2-vxgfnd.281) --
//
// A standalone scaffold (its own shadow-cljs.edn / package.json / deps.edn,
// serving its own host page from resources/public/) is NOT a monorepo-staged
// gallery example, so the shared-design-system contract (favicon + OG + style)
// does not apply and its host-page contract is owned by its own scaffold smoke.
// A project root bearing shadow-cljs.edn is pruned from the host-page walk.

it('isStandaloneExampleProject is true iff the dir carries its own shadow-cljs.edn', () => {
  const projectRoot = path.join(EXAMPLES_ROOT, 'ui', 'demo-scaffold');
  const galleryDir = path.join(EXAMPLES_ROOT, 'core', 'counter');
  const io = makeIo({
    [path.join(projectRoot, 'shadow-cljs.edn')]: '{}',
    // galleryDir has NO shadow-cljs.edn in the io — a staged gallery example.
  });
  assert.ok(
    isStandaloneExampleProject(projectRoot, io),
    'a dir with its own shadow-cljs.edn must be recognised as a standalone project',
  );
  assert.ok(
    !isStandaloneExampleProject(galleryDir, io),
    'a monorepo-staged gallery dir (no own shadow-cljs.edn) must NOT be standalone',
  );
});

// Discover the standalone project roots on disk, and the host pages each one
// actually serves. This is the PRECONDITION half of the prune assertion
// (rf2-72gaq): the old LIVE test was a bare negative naming one path
// (`ui/minimal-counter/`), so the day that scaffold is deleted — or merely
// relocated, its ledger row being MOVE — it would assert 0 of 0 and stay green,
// indistinguishable from a real pass by exit code. Discovering the subjects
// instead of naming one keeps the assertion alive across the relocation, and
// makes the day the LAST standalone project leaves examples/ a loud failure
// rather than a quiet one. The walk deliberately does NOT prune standalone
// roots (that is the behaviour under test) — it stops AT one, since the whole
// subtree belongs to that project.
function findStandaloneProjects(root) {
  const fs = require('fs');
  const found = [];
  const hostPagesUnder = (dir) => {
    const out = [];
    for (const entry of fs.readdirSync(dir, { withFileTypes: true })) {
      const full = path.join(dir, entry.name);
      if (entry.isDirectory()) {
        if (entry.name === 'node_modules') continue;
        out.push(...hostPagesUnder(full));
      } else if (entry.isFile() && isExampleHostPage(entry.name)) {
        out.push(full);
      }
    }
    return out;
  };
  const walk = (dir) => {
    if (dir !== root && isStandaloneExampleProject(dir)) {
      found.push({ root: dir, hostPages: hostPagesUnder(dir) });
      return;
    }
    for (const entry of fs.readdirSync(dir, { withFileTypes: true })) {
      if (!entry.isDirectory()) continue;
      if (entry.name === 'node_modules' || entry.name === '_shared') continue;
      walk(path.join(dir, entry.name));
    }
  };
  walk(root);
  return found;
}

const standaloneProjects = findStandaloneProjects(EXAMPLES_ROOT);

it('LIVE: every standalone example project is pruned from the gallery host-page walk (rf2-vxgfnd.281)', () => {
  assert.ok(
    standaloneProjects.length >= 1,
    'no standalone example project (a dir bearing its own shadow-cljs.edn) exists ' +
      'under examples/, so this negative assertion has no subject and would pass ' +
      'vacuously — 0 of 0. Either a standalone scaffold is staged and the walk lost ' +
      'sight of it, or the last one is gone and the prune rule should be retired ' +
      'together with its final subject.',
  );
  const rel = realIndexes.map((p) => path.relative(EXAMPLES_ROOT, p).split(path.sep).join('/'));
  let prunedPages = 0;
  for (const project of standaloneProjects) {
    const projectRel = path.relative(EXAMPLES_ROOT, project.root).split(path.sep).join('/');
    assert.ok(
      project.hostPages.length >= 1,
      `the standalone project '${projectRel}' serves NO host page, so pruning it ` +
        `proves nothing — the walk had no candidate to drop. A standalone scaffold ` +
        `without a host page cannot witness this rule.`,
    );
    for (const page of project.hostPages) {
      const pageRel = path.relative(EXAMPLES_ROOT, page).split(path.sep).join('/');
      assert.ok(
        !rel.includes(pageRel),
        `the standalone project '${projectRel}' must be pruned from the gallery ` +
          `asset walk (its host-page contract is owned by its own scaffold smoke), ` +
          `but '${pageRel}' was enumerated`,
      );
      prunedPages++;
    }
  }
  console.log(
    `        (checked ${standaloneProjects.length} standalone project(s), ` +
      `${prunedPages} candidate host page(s) proven pruned)`,
  );
});

// ---- FAIL-CLOSED host-page enumeration (rf2-3fc89f.31) -------------------
//
// A directory-read failure under examples/ must FAIL CLOSED (throw, naming the
// unreadable path) rather than silently drop that subtree — the old
// catch-and-continue returned an ordinary smaller array that could stay above
// the CLI floor of 10 and green an INCOMPLETE scan (measured on origin/main: a
// synthetic EACCES on examples/core dropped 13 of 35 host pages, still >10). On
// the OLD code listExampleIndexHtml ignored the injected io and returned an
// array, so `assert.throws` here fails on old code — the regression teeth.

// An io that delegates to the real fs but throws EACCES for ONE subtree,
// reproducing a torn checkout / permissions fault without touching real disk.
function failingReaddirIo(badDir) {
  const realFs = require('fs');
  const bad = path.resolve(badDir);
  return {
    readdirSync: (dir, opts) => {
      if (path.resolve(dir) === bad) {
        const e = new Error(`EACCES: permission denied, scandir '${dir}'`);
        e.code = 'EACCES';
        throw e;
      }
      return realFs.readdirSync(dir, opts);
    },
  };
}

it('TEETH: listExampleIndexHtml FAILS CLOSED on an unreadable subtree (rf2-3fc89f.31)', () => {
  const badDir = path.join(EXAMPLES_ROOT, 'core');
  assert.throws(
    () => listExampleIndexHtml(EXAMPLES_ROOT, { io: failingReaddirIo(badDir) }),
    (err) =>
      /enumeration FAILED/.test(err.message) &&
      err.message.includes(badDir) &&
      Array.isArray(err.walkErrors),
    'a directory-read failure must throw (naming the path), not silently drop the subtree',
  );
});

it('listExampleIndexHtml returns the full set with a clean io (no false failure) (rf2-3fc89f.31)', () => {
  // The intentional node_modules/_shared skips are POLICY, not errors — a clean
  // walk still recovers the full host-page set.
  const clean = listExampleIndexHtml(EXAMPLES_ROOT, { io: require('fs') });
  assert.deepStrictEqual(clean, realIndexes, 'the injected-fs walk equals the default walk');
  assert.ok(clean.length >= 10, 'the clean walk is non-vacuous');
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

// ---- manifest projection: scanner consumer (rf2-phpbo8) ------------------
//
// ALLOWLIST is no longer a literal — it is the page-opt-out projection of the
// single examples asset/exception manifest, the shared owner examples-staging.cjs
// also consumes. These pin the projection against a SYNTHETIC manifest (exact
// logic, no restated production data) plus a real-manifest cross-consistency
// check that the very base.css/index.css TodoMVC STAGES are the same refs the
// scanner accepts as page-local — the drift the two independent declarations
// used to permit.

it('LIVE: the scanner ALLOWLIST IS the real manifest page-opt-out projection (rf2-phpbo8)', () => {
  assert.deepStrictEqual(
    ALLOWLIST,
    pageExemptions(EXAMPLE_ASSET_MANIFEST),
    'ALLOWLIST must be the manifest projection, not an independent copy',
  );
});

it('LIVE: TodoMVC staged CSS destinations == the scanner-accepted page-local refs (one owner) (rf2-phpbo8)', () => {
  // The whole point of the unification: base.css + index.css are declared ONCE
  // and appear in BOTH projections. What staging COPIES is exactly what the
  // scanner ACCEPTS as page-local — they cannot diverge.
  const staged = stagedAssetsByBuild(EXAMPLE_ASSET_MANIFEST)['examples/todomvc']
    .map((a) => a.dest)
    .sort();
  const scannerLocal = [
    ...ALLOWLIST['examples/core/todomvc/index.html'].localAssets,
  ].sort();
  assert.deepStrictEqual(staged, ['base.css', 'index.css']);
  assert.deepStrictEqual(scannerLocal, staged);
});

it('pageExemptions projects a synthetic manifest: exemption + derived localAssets, staging-only entry excluded (rf2-phpbo8)', () => {
  const projected = pageExemptions(SYNTHETIC_MANIFEST);
  // The vendored-CSS page surfaces with its exemption + html-linked localAssets.
  assert.deepStrictEqual(projected['examples/reagent/todomvc/index.html'], {
    reason: 'synthetic: vendored TodoMVC CSS instead of the shared stylesheet',
    assetExemptions: ['_shared/css/style.css'],
    localAssets: ['base.css', 'index.css'],
  });
  // The staging-only fixture entry (no exemption, no html-linked asset) is NOT
  // projected into the scanner allowlist — it adds no empty ALLOWLIST noise.
  assert.ok(
    !('examples/reagent/fixture/index.html' in projected),
    'a staging-only entry must not surface as a scanner exemption',
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

// ---------------------------------------------------------------------------
// HTML reference inventory (rf2-6a3rgx) — the SINGLE extraction pass. ONE
// table-driven suite replaces the three former per-extractor matrices
// (extractHtmlRefs / extractAssetRefs / extractOgImageRefs), each of which had
// to be taught every new shape independently. Every supported shape is asserted
// once against extractHtmlReferenceInventory's three views:
//   - localRefs : broad on-disk-resolution candidates (generic href handling)
//   - assets    : the tagged LOAD-TIME fetch subset (network policy input)
//   - ogImages  : the social-preview targets (raster contract input)
// Coverage: quoting (double/single/unquoted), attribute order, query/hash
// stripping, srcset, imagesrcset, <video> poster, navigation-vs-asset, og:image
// (incl. content-before-property), and de-duplication.
// ---------------------------------------------------------------------------

// All `source` strings an asset ref was tagged with (a ref carried by more than
// one origin — e.g. an icon <link> AND an og:image meta — keeps each).
const assetSources = (assets, ref) => assets.filter((a) => a.ref === ref).map((a) => a.source);

const INVENTORY_CASES = [
  {
    name: 'quoting/order/OG — link + script + og:image in document bucket order',
    html: goodHtml(),
    localExact: [
      '_shared/img/favicon.svg',
      '_shared/css/style.css',
      'main.js',
      '_shared/img/og.png',
    ],
    assetSourceIncludes: {
      'main.js': 'script',
      '_shared/img/favicon.svg': 'link',
      '_shared/css/style.css': 'link',
    },
    assetSourceExact: { '_shared/img/og.png': 'og:image' },
    ogExact: ['_shared/img/og.png'],
  },
  {
    name: 'query/hash stripped for on-disk resolution',
    html: '<link rel="stylesheet" href="a.css?v=2"><link rel="icon" href="b.css#x">',
    localExact: ['a.css', 'b.css'],
    localExcludes: ['a.css?v=2', 'b.css#x'],
  },
  {
    name: 'single-quoted attribute values',
    html: "<link rel='icon' href='_shared/img/favicon.svg'>",
    localIncludes: ['_shared/img/favicon.svg'],
    assetSourceIncludes: { '_shared/img/favicon.svg': 'link' },
  },
  {
    name: 'unquoted values (HTML5, rf2-3dzb6h) — script src + rel=stylesheet href',
    html: '<script src=main.js></script><link rel=stylesheet href=base.css>',
    localIncludes: ['main.js', 'base.css'],
    assetSourceIncludes: { 'main.js': 'script', 'base.css': 'link' },
  },
  {
    name: 'unquoted REMOTE script src + protocol-relative link href tagged (rf2-3dzb6h)',
    html:
      '<script src=https://cdn.evil/x.js></script>\n' +
      '<link rel=stylesheet href=//cdn.evil/x.css>',
    assetSourceIncludes: { 'https://cdn.evil/x.js': 'script', '//cdn.evil/x.css': 'link' },
  },
  {
    name: 'srcset / imagesrcset candidates + <video> poster, local (rf2-arkvq8)',
    html: [
      '<img srcset="_shared/img/hero-320.png 320w, _shared/img/hero-640.png 640w">',
      '<source srcset="_shared/img/pic@2x.png 2x">',
      '<link rel="preload" as="image" imagesrcset="_shared/img/pre-1.png 1x">',
      '<video poster="_shared/img/poster.png"><source src="clip.mp4"></video>',
    ].join('\n'),
    localIncludes: [
      '_shared/img/hero-320.png',
      '_shared/img/hero-640.png',
      '_shared/img/pic@2x.png',
      '_shared/img/pre-1.png',
      '_shared/img/poster.png',
    ],
    assetSourceIncludes: {
      '_shared/img/hero-320.png': 'srcset',
      '_shared/img/pre-1.png': 'imagesrcset',
    },
    assetSourceExact: { '_shared/img/poster.png': '<video poster>' },
  },
  {
    name: 'remote srcset / imagesrcset / poster tagged with their origin (rf2-arkvq8)',
    html: [
      '<img srcset="https://img.example.com/a-320.png 320w, https://img.example.com/a-640.png 640w">',
      '<link rel="preload" as="image" imagesrcset="https://img.example.com/pre.png 1x">',
      '<video poster="https://img.example.com/still.png"></video>',
    ].join('\n'),
    assetSourceIncludes: {
      'https://img.example.com/a-320.png': 'srcset',
      'https://img.example.com/a-640.png': 'srcset',
      'https://img.example.com/pre.png': 'imagesrcset',
    },
    assetSourceExact: { 'https://img.example.com/still.png': '<video poster>' },
  },
  {
    name: 'data-src / data-srcset / data-poster lazy-load hooks are NOT references',
    html: '<img data-src="lazy.jpg" data-srcset="lazy.png 2x" data-poster="lazy-poster.png">',
    localExact: [],
    assetExcludes: ['lazy.jpg', 'lazy.png', 'lazy-poster.png'],
  },
  {
    name: 'navigation — <a href> + rel=canonical/alternate are local refs but NOT assets',
    html: [
      '<a href="https://example.com/docs">docs</a>',
      '<link rel="canonical" href="https://example.com/page">',
      '<link rel="alternate" href="https://example.com/rss">',
    ].join('\n'),
    localIncludes: [
      'https://example.com/docs',
      'https://example.com/page',
      'https://example.com/rss',
    ],
    assetExcludes: [
      'https://example.com/docs',
      'https://example.com/page',
      'https://example.com/rss',
    ],
  },
  {
    name: 'og:image content-BEFORE-property is order-independent (rf2-cnu7qy)',
    html: '<meta content="_shared/img/og.png" property="og:image">',
    ogExact: ['_shared/img/og.png'],
    localIncludes: ['_shared/img/og.png'],
    assetSourceExact: { '_shared/img/og.png': 'og:image' },
  },
  {
    name: 'og:image content-first REMOTE card is tagged (rf2-cnu7qy)',
    html: '<meta content="https://og.example.com/card.png" property="og:image">',
    ogExact: ['https://og.example.com/card.png'],
    localIncludes: ['https://og.example.com/card.png'],
    assetSourceExact: { 'https://og.example.com/card.png': 'og:image' },
  },
  {
    name: 'dedup — a repeated src collapses to one local ref and one asset',
    html: '<img src="x.png"><img src="x.png">',
    localExact: ['x.png'],
    assetSourceExact: { 'x.png': '<img src>' },
  },
  {
    name: 'dedup — same ref from distinct origins keeps BOTH tagged assets',
    html:
      '<link rel="icon" href="_shared/img/og.png">' +
      '<meta property="og:image" content="_shared/img/og.png">' +
      '<meta property="og:image" content="_shared/img/og.png">',
    localExact: ['_shared/img/og.png'],
    ogExact: ['_shared/img/og.png'],
    // (source, ref) de-dup: the icon-link and og:image origins are distinct, so
    // both survive; the duplicate og meta collapses.
    assetSourceSet: { '_shared/img/og.png': ['<link rel="icon" href>', 'og:image'] },
  },
  {
    // Inert markup: a link/meta/script/img that lives ONLY inside a closed HTML
    // comment is never fetched nor exposed as metadata by the browser, so it
    // contributes ZERO references to any of the three views (rf2-j538f7.28).
    name: 'inert HTML comment — commented link/meta/script/img contribute no references (rf2-j538f7.28)',
    html: [
      '<script src="main.js"></script>',
      '<!--',
      '  <link rel="icon" href="_shared/img/favicon.svg">',
      '  <meta property="og:image" content="_shared/img/og.png">',
      '  <link rel="stylesheet" href="_shared/css/style.css">',
      '  <img src="commented.png">',
      '-->',
    ].join('\n'),
    localExact: ['main.js'],
    ogExact: [],
    assetExcludes: [
      '_shared/img/favicon.svg',
      '_shared/img/og.png',
      '_shared/css/style.css',
      'commented.png',
    ],
  },
  {
    // Live references immediately before and after a comment retain their order
    // and classification, and a tag following a CLOSED comment is still
    // discovered — the strip removes only the inert span (rf2-j538f7.28).
    name: 'inert HTML comment — live refs before/after keep order; post-comment tag still seen (rf2-j538f7.28)',
    html: [
      '<link rel="stylesheet" href="before.css">',
      '<!-- <link rel="icon" href="commented.svg"> -->',
      '<script src="after.js"></script>',
    ].join('\n'),
    localExact: ['before.css', 'after.js'],
    assetSourceIncludes: { 'before.css': 'link', 'after.js': 'script' },
    assetExcludes: ['commented.svg'],
  },
  {
    // An UNTERMINATED `<!--` comments out the remainder of the document, so the
    // would-be tags after it are inert rather than satisfying the gate
    // (rf2-j538f7.28).
    name: 'inert HTML comment — an unterminated <!-- makes the rest of the document inert (rf2-j538f7.28)',
    html: [
      '<script src="live.js"></script>',
      '<!-- unterminated comment swallows the rest',
      '<link rel="stylesheet" href="never.css">',
      '<meta property="og:image" content="never-og.png">',
    ].join('\n'),
    localExact: ['live.js'],
    ogExact: [],
    assetExcludes: ['never.css', 'never-og.png'],
  },
];

for (const c of INVENTORY_CASES) {
  it(`inventory — ${c.name}`, () => {
    const { localRefs, assets, ogImages } = extractHtmlReferenceInventory(c.html);
    if (c.localExact) assert.deepStrictEqual(localRefs, c.localExact, 'localRefs');
    for (const r of c.localIncludes || []) {
      assert.ok(localRefs.includes(r), `localRefs must include '${r}', got: ${localRefs.join(', ')}`);
    }
    for (const r of c.localExcludes || []) {
      assert.ok(!localRefs.includes(r), `localRefs must NOT include '${r}', got: ${localRefs.join(', ')}`);
    }
    if (c.ogExact) assert.deepStrictEqual(ogImages, c.ogExact, 'ogImages');
    for (const [ref, sub] of Object.entries(c.assetSourceIncludes || {})) {
      const srcs = assetSources(assets, ref);
      assert.ok(
        srcs.length === 1 && srcs[0].includes(sub),
        `asset '${ref}' must be tagged with a single source containing '${sub}', got: ${JSON.stringify(srcs)}`,
      );
    }
    for (const [ref, src] of Object.entries(c.assetSourceExact || {})) {
      assert.deepStrictEqual(assetSources(assets, ref), [src], `asset '${ref}' exact source`);
    }
    for (const [ref, srcs] of Object.entries(c.assetSourceSet || {})) {
      assert.deepStrictEqual(
        assetSources(assets, ref).sort(),
        [...srcs].sort(),
        `asset '${ref}' source set`,
      );
    }
    for (const r of c.assetExcludes || []) {
      assert.ok(
        !assets.some((a) => a.ref === r),
        `assets must NOT include '${r}', got: ${JSON.stringify(assets)}`,
      );
    }
  });
}

it('TEETH: required shared refs that exist ONLY inside an HTML comment are reported missing (rf2-j538f7.28)', () => {
  // The false-GREEN case this bead closes: a maintainer comments out the three
  // required refs while debugging. The files still exist on disk (fullIo), but
  // the live page ships without its stylesheet, favicon, and social-preview —
  // so the gate must report all three missing. A commented tag is inert.
  const html = [
    '<!doctype html><html><head>',
    '<!--',
    '<meta property="og:image" content="_shared/img/og.png">',
    '<link rel="icon" href="_shared/img/favicon.svg">',
    '<link rel="stylesheet" href="_shared/css/style.css">',
    '-->',
    '</head><body><script src="main.js"></script></body></html>',
  ].join('\n');
  const { errors } = scanPage(fullIo({ [PAGE]: html }), PAGE);
  for (const need of [
    '_shared/img/og.png',
    '_shared/img/favicon.svg',
    '_shared/css/style.css',
  ]) {
    assert.ok(
      errors.some((e) =>
        e.includes(`missing required shared asset reference '${need}'`),
      ),
      `commented-out required ref '${need}' must be reported missing, got: ${errors.join(' | ')}`,
    );
  }
});

it('TEETH: a commented remote ref does not trip the network policy and a commented missing local ref is not resolved (rf2-j538f7.28)', () => {
  // The false-FAIL polarity: inert markup must be absent from BOTH verdicts. A
  // commented remote <script> is not a direct-network violation, and a
  // commented missing local <link> is not a disk-resolution failure. goodHtml
  // keeps the three required refs live, so the page is otherwise clean.
  const html = goodHtml().replace(
    '</head>',
    '<!-- <script src="https://cdn.evil/x.js"></script>' +
      '<link rel="stylesheet" href="missing-local.css"> -->\n</head>',
  );
  const { errors } = scanPage(fullIo({ [PAGE]: html }), PAGE);
  assert.ok(
    !errors.some((e) => e.includes('cdn.evil')),
    `a commented remote ref must not trip the network policy, got: ${errors.join(' | ')}`,
  );
  assert.ok(
    !errors.some((e) => e.includes('missing-local.css')),
    `a commented missing local ref must not be resolved on disk, got: ${errors.join(' | ')}`,
  );
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
  // Build the allowlist the scanner consumes from a SYNTHETIC manifest — the
  // same projection production uses — so this pins the manifest-consuming path,
  // not a hand-written allowlist literal (rf2-phpbo8).
  const allowlist = pageExemptions(SYNTHETIC_MANIFEST);
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
  // Build the allowlist the scanner consumes from a SYNTHETIC manifest — the
  // same projection production uses — so this pins the manifest-consuming path,
  // not a hand-written allowlist literal (rf2-phpbo8).
  const allowlist = pageExemptions(SYNTHETIC_MANIFEST);
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

it('TEETH: a valid signature + >=24 bytes but a non-IHDR first chunk fails validatePng (rf2-bdamni)', () => {
  // Signature (8) + chunk-length (4) + a first chunk type of 'IDAT' (4) + pad to
  // >=24 bytes. The signature and length gates pass, so this exercises the
  // non-IHDR branch (bytes 12..16 !== 'IHDR') that valid / bad-signature /
  // too-short / wrong-dimension cases never reached.
  const notIhdr = Buffer.concat([
    PNG_SIGNATURE, // bytes 0..8 — a valid PNG signature
    Buffer.from([0, 0, 0, 13]), // bytes 8..12 — chunk length
    Buffer.from('IDAT', 'latin1'), // bytes 12..16 — first chunk type (NOT IHDR)
    Buffer.alloc(8), // pad so buf.length >= 24
  ]).toString('latin1');
  const v = validatePng(notIhdr);
  assert.ok(!v.ok, 'a non-IHDR first chunk must fail');
  assert.ok(/IHDR/.test(v.reason), `expected a non-IHDR failure, got: ${v.reason}`);
});

it('TEETH: >=24 bytes with the wrong signature fails validatePng at the signature gate (rf2-bdamni)', () => {
  // The existing 'PNGDATA' case is only 7 bytes, so it trips the too-short gate
  // and never reaches the signature check. A 24-byte non-PNG buffer exercises
  // the signature branch itself.
  const notPng = Buffer.alloc(24, 0x20).toString('latin1'); // 24 spaces
  const v = validatePng(notPng);
  assert.ok(!v.ok, '24 bytes of non-PNG data must fail');
  assert.ok(/signature/.test(v.reason), `expected a signature failure, got: ${v.reason}`);
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

// ---- TEETH: og.png FULL STRUCTURAL DECODE (rf2-3fc89f.27) ----------------
//
// The pre-fix validatePng read only the first 24 bytes (signature + IHDR dims),
// so a 24-byte header prefix, a byte-flipped IDAT, or a mid-stream truncation
// all reported ok:true — a FALSE-GREEN over a structurally-broken raster. The
// gate now walks the ENTIRE chunk stream: bounded chunks, per-chunk CRC-32, a
// real IDAT zlib inflate, and a terminal IEND. Each fixture below isolates one
// real corruption the old header sniff missed.

it('validatePng accepts a freshly-built structurally-complete PNG', () => {
  const v = validatePng(buildPng());
  assert.ok(v.ok, `expected the built PNG to validate, got: ${v.reason}`);
  assert.strictEqual(v.width, OG_PNG_WIDTH);
  assert.strictEqual(v.height, OG_PNG_HEIGHT);
});

it('LIVE: the shipped og.png fully decodes (signature + chunks + CRC + IDAT inflate + IEND)', () => {
  const fs = require('fs');
  const bytes = fs.readFileSync(path.join(EXAMPLES_ROOT, '_shared', 'img', 'og.png'));
  const v = validatePng(bytes);
  assert.ok(v.ok, `the real og.png must fully decode, got: ${v.reason}`);
  assert.strictEqual(v.width, OG_PNG_WIDTH);
  assert.strictEqual(v.height, OG_PNG_HEIGHT);
});

it('TEETH: a 24-byte header-only prefix is REJECTED (the pre-fix false-green)', () => {
  // Exactly the false-green the old header-sniff let through: signature + IHDR
  // header + dimensions and nothing else. It must now fail (chunk runs past EOF).
  const headerOnly = buildPng().subarray(0, 24);
  const v = validatePng(headerOnly);
  assert.ok(!v.ok, 'a 24-byte header prefix is not a complete PNG and must fail');
  assert.ok(
    /past end of file|truncated/.test(v.reason),
    `expected a truncation failure, got: ${v.reason}`,
  );
});

it('TEETH: a mid-IDAT truncation is REJECTED (declared chunk length runs past EOF)', () => {
  // Cut the file INSIDE the IDAT chunk data (byte 45 is a few bytes into the
  // IDAT payload, which starts at byte 41 = sig 8 + IHDR 25 + IDAT header 8).
  // The IDAT chunk still declares its full length, which now runs past EOF. The
  // old header-sniff stayed green on any prefix >= 24 bytes.
  const truncated = buildPng().subarray(0, 45);
  const v = validatePng(truncated);
  assert.ok(!v.ok, 'a mid-stream truncation must fail');
  assert.ok(
    /past end of file|truncated/.test(v.reason),
    `expected a truncation failure, got: ${v.reason}`,
  );
});

it('TEETH: a byte-flipped IDAT (bad CRC) is REJECTED', () => {
  // Flip a byte INSIDE the IDAT data without recomputing its CRC — the stored
  // CRC no longer matches the computed CRC, so the chunk is corrupt. (The old
  // sniff never read past byte 24, so a corrupt IDAT passed.)
  const full = buildPng();
  const corrupt = Buffer.from(full);
  // IDAT data sits after sig(8) + IHDR(25) + IDAT length(4) + 'IDAT'(4) = 41.
  corrupt[43] ^= 0xff;
  const v = validatePng(corrupt);
  assert.ok(!v.ok, 'a byte-flipped IDAT must fail the CRC check');
  assert.ok(/CRC mismatch/.test(v.reason), `expected a CRC failure, got: ${v.reason}`);
});

it('TEETH: a PNG missing its terminal IEND is REJECTED', () => {
  // Signature + IHDR + IDAT but no IEND chunk (the trailing 12-byte IEND chunk
  // dropped). A conformant PNG always ends with IEND; its absence is a truncated
  // raster. Every remaining chunk still has a valid CRC, so this isolates the
  // missing-terminator defect rather than a bounds/CRC symptom.
  const full = buildPng();
  const noIend = full.subarray(0, full.length - 12); // drop the IEND chunk
  const v = validatePng(noIend);
  assert.ok(!v.ok, 'a PNG with no IEND must fail');
  assert.ok(/IEND/.test(v.reason), `expected a missing-IEND failure, got: ${v.reason}`);
});

it('TEETH: checkSharedTree rejects a header-only (truncated) og.png', () => {
  const io = makeIo({
    [path.join(SHARED_ROOT, 'css', 'style.css')]: GOOD_SHARED_STYLE,
    [path.join(SHARED_ROOT, 'css', 'structure.css')]:
      '.send-form input[type="text"] { min-width: 240px; }\n' +
      '.cells-grid input { width: 56px; }\n' +
      RESPONSIVE_SHELL,
    [path.join(SHARED_ROOT, 'img', 'favicon.svg')]: '<svg/>',
    // A header-only prefix — the exact false-green the pre-fix gate passed.
    [path.join(SHARED_ROOT, 'img', 'og.png')]: buildPng().subarray(0, 24).toString('latin1'),
    [path.join(SHARED_ROOT, 'img', 'og.svg')]: '<svg/>',
  });
  const errors = checkSharedTree(io, { sharedRoot: SHARED_ROOT });
  assert.ok(
    errors.some((e) => e.includes('og.png') && e.includes('not a valid')),
    `expected checkSharedTree to reject the header-only og.png, got: ${errors.join(' | ')}`,
  );
});

// ---- TEETH: og.png RASTER SEMANTICS — zlib inflation is not PNG decoding
// (rf2-j538f7.24) -----------------------------------------------------------
//
// The full-structural-decode gate (above) still called a PNG "decodable" once
// its IDAT merely zlib-INFLATED, so a raster with a forbidden IHDR colour type,
// an unsupported interlace method, or an IDAT that expands to the wrong number
// of bytes / carries an illegal per-row filter byte all passed — every social
// preview would then serve unreadable bytes while the all-35-host gate stayed
// green. The gate now validates the IHDR SEMANTICS + the exact scanline geometry
// + the per-row filter tags. Each fixture below keeps a valid signature, bounded
// chunks, correct CRCs, an inflatable zlib stream, and a terminal IEND —
// isolating exactly ONE raster-semantic defect the pre-fix zlib-only check waved
// through.

// Build a PNG with explicit IHDR fields and an explicit (already-inflated) raster
// payload, so a fixture can hold every envelope invariant while varying exactly
// one raster-semantic field. `raster` is deflated into the IDAT and all CRCs are
// recomputed, so the ONLY defect is the one the fixture injects (e.g. a valid
// IHDR CRC over a forbidden colour type). Defaults to a full 8-bit RGB raster.
function buildPngWith({
  width = OG_PNG_WIDTH,
  height = OG_PNG_HEIGHT,
  bitDepth = 8,
  colourType = 2,
  compression = 0,
  filter = 0,
  interlace = 0,
  raster,
} = {}) {
  const ihdr = Buffer.alloc(13);
  ihdr.writeUInt32BE(width, 0);
  ihdr.writeUInt32BE(height, 4);
  ihdr[8] = bitDepth;
  ihdr[9] = colourType;
  ihdr[10] = compression;
  ihdr[11] = filter;
  ihdr[12] = interlace;
  const body = raster !== undefined ? raster : Buffer.alloc(height * (1 + width * 3));
  return Buffer.concat([
    Buffer.from(PNG_SIGNATURE),
    pngChunk('IHDR', ihdr),
    pngChunk('IDAT', zlibForTests.deflateSync(body)),
    pngChunk('IEND', Buffer.alloc(0)),
  ]);
}

it('validatePng accepts buildPngWith defaults (full RGB raster)', () => {
  const v = validatePng(buildPngWith());
  assert.ok(v.ok, `expected the default full-raster PNG to validate, got: ${v.reason}`);
  assert.strictEqual(v.width, OG_PNG_WIDTH);
  assert.strictEqual(v.height, OG_PNG_HEIGHT);
});

it('TEETH: a forbidden IHDR colour type (1) is REJECTED with an IHDR-semantic error', () => {
  // colour type 1 is not a legal PNG colour type (0/2/3/4/6). pngChunk recomputes
  // the IHDR CRC, so the envelope is valid and this isolates the SEMANTIC defect —
  // the bead's reproduction (Pillow rejects it; the pre-fix gate returned ok:true).
  const v = validatePng(buildPngWith({ colourType: 1, raster: Buffer.from([0, 0, 0, 0]) }));
  assert.ok(!v.ok, 'a forbidden colour type must fail');
  assert.ok(/colour type 1/.test(v.reason), `expected an IHDR colour-type error, got: ${v.reason}`);
});

it('TEETH: an illegal bit-depth/colour-type pairing (RGB @ 1-bit) is REJECTED', () => {
  // Colour type 2 (truecolour) is legal only at bit depth 8 or 16; 1-bit RGB is
  // forbidden by the PNG spec even though the chunk envelope is intact.
  const v = validatePng(buildPngWith({ colourType: 2, bitDepth: 1, raster: Buffer.from([0]) }));
  assert.ok(!v.ok, 'an illegal bit-depth/colour-type pairing must fail');
  assert.ok(
    /bit depth 1 is illegal for colour type 2/.test(v.reason),
    `expected an IHDR bit-depth error, got: ${v.reason}`,
  );
});

it('TEETH: a nonzero IHDR compression method is REJECTED', () => {
  const v = validatePng(buildPngWith({ compression: 1, raster: Buffer.from([0]) }));
  assert.ok(!v.ok, 'an unsupported compression method must fail');
  assert.ok(/compression method 1/.test(v.reason), `got: ${v.reason}`);
});

it('TEETH: a nonzero IHDR filter method is REJECTED', () => {
  const v = validatePng(buildPngWith({ filter: 1, raster: Buffer.from([0]) }));
  assert.ok(!v.ok, 'an unsupported filter method must fail');
  assert.ok(/filter method 1/.test(v.reason), `got: ${v.reason}`);
});

it('TEETH: an unsupported interlace method (Adam7) is REJECTED explicitly', () => {
  // Interlace method 1 (Adam7) is a legal PNG but this gate accepts only the
  // non-interlaced social card (method 0); it must fail explicitly, not decode.
  const v = validatePng(buildPngWith({ interlace: 1, raster: Buffer.from([0]) }));
  assert.ok(!v.ok, 'an unsupported interlace method must fail');
  assert.ok(/interlace method 1/.test(v.reason), `got: ${v.reason}`);
});

it('TEETH: a valid-zlib IDAT expanding to only 4 bytes over a 1200x630 IHDR is REJECTED (the bead reproduction)', () => {
  // The exact false-green: a real 1200x630 truecolour IHDR, valid CRCs, a terminal
  // IEND, and an IDAT that IS a well-formed zlib stream — but one inflating to just
  // 4 bytes, nowhere near the 630 x (1 + 1200*3) = 2,268,630-byte raster. zlib-only
  // validation passed this; the scanline-geometry check now rejects it.
  const v = validatePng(buildPngWith({ raster: Buffer.from([0, 0, 0, 0]) }));
  assert.ok(!v.ok, 'a 4-byte pixel payload for a 1200x630 raster must fail');
  assert.ok(
    /decompressed image data is 4 byte\(s\), expected 2268630/.test(v.reason),
    `expected an incomplete-payload error, got: ${v.reason}`,
  );
});

it('TEETH: an OVER-LONG raster payload (one byte too many) is REJECTED', () => {
  const oversize = Buffer.alloc(OG_PNG_HEIGHT * (1 + OG_PNG_WIDTH * 3) + 1); // one extra byte
  const v = validatePng(buildPngWith({ raster: oversize }));
  assert.ok(!v.ok, 'an oversized pixel payload must fail');
  assert.ok(/expected 2268630/.test(v.reason), `expected a geometry error, got: ${v.reason}`);
});

it('TEETH: a scanline with an illegal filter byte (5) is REJECTED', () => {
  // A COMPLETE 1200x630 raster (correct byte count) whose first scanline's leading
  // filter tag is 5 — not a legal PNG filter type (0..4). The length check passes;
  // the per-row filter check catches it.
  const stride = 1 + OG_PNG_WIDTH * 3;
  const raster = Buffer.alloc(OG_PNG_HEIGHT * stride);
  raster[0] = 5; // first scanline filter tag = 5 (illegal)
  const v = validatePng(buildPngWith({ raster }));
  assert.ok(!v.ok, 'an illegal filter byte must fail');
  assert.ok(
    /scanline 0/.test(v.reason) && /filter byte 5/.test(v.reason),
    `expected a filter-byte error, got: ${v.reason}`,
  );
});

it('a complete raster with legal filter bytes (0..4) still validates', () => {
  // Guard against over-strict rejection: cycling filter tags 0..4 down the
  // scanlines is legal and must pass (the real og.png uses tags 1/2/4).
  const stride = 1 + OG_PNG_WIDTH * 3;
  const raster = Buffer.alloc(OG_PNG_HEIGHT * stride);
  for (let r = 0; r < OG_PNG_HEIGHT; r++) raster[r * stride] = r % 5; // 0..4
  const v = validatePng(buildPngWith({ raster }));
  assert.ok(v.ok, `a raster with legal filter bytes 0..4 must validate, got: ${v.reason}`);
});

it('TEETH: checkSharedTree rejects a raster-broken (zlib-valid) og.png (rf2-j538f7.24)', () => {
  // og.png: valid signature + 1200x630 IHDR + valid-CRC chunks + a valid zlib IDAT
  // that inflates to 4 bytes + IEND. The pre-fix gate passed this; the raster
  // geometry check now turns the gate RED end-to-end via checkSharedTree, proving
  // the wiring has teeth (not just validatePng in isolation).
  const brokenRaster = buildPngWith({ raster: Buffer.from([0, 0, 0, 0]) }).toString('latin1');
  const io = makeIo({
    [path.join(SHARED_ROOT, 'css', 'style.css')]: GOOD_SHARED_STYLE,
    [path.join(SHARED_ROOT, 'css', 'structure.css')]:
      '.send-form input[type="text"] { min-width: 240px; }\n' +
      '.cells-grid input { width: 56px; }\n' +
      RESPONSIVE_SHELL,
    [path.join(SHARED_ROOT, 'img', 'favicon.svg')]: '<svg/>',
    [path.join(SHARED_ROOT, 'img', 'og.png')]: brokenRaster,
    [path.join(SHARED_ROOT, 'img', 'og.svg')]: '<svg/>',
  });
  const errors = checkSharedTree(io, { sharedRoot: SHARED_ROOT });
  assert.ok(
    errors.some((e) => e.includes('og.png') && e.includes('not a valid')),
    `expected checkSharedTree to reject the raster-broken og.png, got: ${errors.join(' | ')}`,
  );
});

// ---- TEETH: SVG WELL-FORMEDNESS (rf2-3fc89f.27) --------------------------
//
// The pre-fix gate checked favicon.svg / og.svg only for existence and selected
// palette literals — never XML well-formedness. Both shipped SVGs actually
// contained an illegal '--' inside a comment (XML forbids it; strict parsers AND
// Chrome render a <parsererror>), so the favicon could not render and the OG
// source could not be re-exported, all while the gate stayed green. The gate now
// validates the markup.

it('checkSvgWellFormed accepts a well-formed SVG (prolog + comment + nesting + text)', () => {
  const svg =
    '<?xml version="1.0" encoding="UTF-8"?>\n' +
    '<!-- a legal comment: em-dash — and single-hyphen ex-bg are fine -->\n' +
    '<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 32 32">' +
    '<rect width="32" height="32"/><g><text font-family="\'Inter\', sans-serif">re-frame2</text></g>' +
    '</svg>';
  assert.deepStrictEqual(
    checkSvgWellFormed(svg, 'ok.svg'),
    [],
    'a well-formed SVG must produce no errors',
  );
});

it('TEETH: checkSvgWellFormed flags an illegal double-hyphen inside a comment', () => {
  const svg = '<svg><!-- mirror the --ex-* palette --><rect/></svg>';
  const errors = checkSvgWellFormed(svg, 'bad.svg');
  assert.ok(
    errors.some((e) => e.includes("illegal '--'") && e.includes('bad.svg')),
    `expected an illegal-comment error, got: ${errors.join(' | ')}`,
  );
});

it('TEETH: checkSvgWellFormed flags a mismatched / unclosed tag', () => {
  assert.ok(
    checkSvgWellFormed('<svg><rect></svg>', 'x.svg').some((e) => e.includes('mismatched closing tag')),
    'a mismatched closing tag must be flagged',
  );
  assert.ok(
    checkSvgWellFormed('<svg><g><rect/>', 'y.svg').some((e) => e.includes('unclosed element')),
    'an unclosed element must be flagged',
  );
});

it('TEETH: checkSvgWellFormed flags an unterminated comment and a non-SVG document', () => {
  assert.ok(
    checkSvgWellFormed('<svg><!-- never closed </svg>', 'u.svg').some((e) =>
      e.includes('unterminated XML comment'),
    ),
    'an unterminated comment must be flagged',
  );
  assert.ok(
    checkSvgWellFormed('   just text, no markup   ', 'n.svg').some((e) =>
      e.includes('no element found'),
    ),
    'a document with no element must be flagged',
  );
});

it('TEETH: checkSharedTree reports a targeted failure for a favicon.svg with a "--" comment', () => {
  const io = makeIo({
    [path.join(SHARED_ROOT, 'css', 'style.css')]: GOOD_SHARED_STYLE,
    [path.join(SHARED_ROOT, 'css', 'structure.css')]:
      '.send-form input[type="text"] { min-width: 240px; }\n' +
      '.cells-grid input { width: 56px; }\n' +
      RESPONSIVE_SHELL,
    // The exact pre-fix defect: a '--' sequence inside the favicon comment.
    [path.join(SHARED_ROOT, 'img', 'favicon.svg')]:
      '<svg xmlns="http://www.w3.org/2000/svg"><!-- mirror the --ex-* palette --><rect/></svg>',
    [path.join(SHARED_ROOT, 'img', 'og.png')]: VALID_OG_PNG,
    [path.join(SHARED_ROOT, 'img', 'og.svg')]: '<svg/>',
  });
  const errors = checkSharedTree(io, { sharedRoot: SHARED_ROOT });
  assert.ok(
    errors.some((e) => e.includes('favicon.svg') && e.includes("illegal '--'")),
    `expected a targeted favicon well-formedness error, got: ${errors.join(' | ')}`,
  );
});

it('TEETH: checkSharedTree reports a targeted failure for a malformed og.svg structure', () => {
  const io = makeIo({
    [path.join(SHARED_ROOT, 'css', 'style.css')]: GOOD_SHARED_STYLE,
    [path.join(SHARED_ROOT, 'css', 'structure.css')]:
      '.send-form input[type="text"] { min-width: 240px; }\n' +
      '.cells-grid input { width: 56px; }\n' +
      RESPONSIVE_SHELL,
    [path.join(SHARED_ROOT, 'img', 'favicon.svg')]: '<svg/>',
    [path.join(SHARED_ROOT, 'img', 'og.png')]: VALID_OG_PNG,
    // Broken structure: <rect> is never closed, then </svg> mismatches.
    [path.join(SHARED_ROOT, 'img', 'og.svg')]:
      '<svg xmlns="http://www.w3.org/2000/svg"><rect></svg>',
  });
  const errors = checkSharedTree(io, { sharedRoot: SHARED_ROOT });
  assert.ok(
    errors.some((e) => e.includes('og.svg') && e.includes('mismatched closing tag')),
    `expected a targeted og.svg structural error, got: ${errors.join(' | ')}`,
  );
});

it('LIVE: the shipped favicon.svg and og.svg are well-formed XML', () => {
  const fs = require('fs');
  for (const name of ['favicon.svg', 'og.svg']) {
    const svg = fs.readFileSync(path.join(EXAMPLES_ROOT, '_shared', 'img', name), 'utf8');
    assert.deepStrictEqual(
      checkSvgWellFormed(svg, `examples/_shared/img/${name}`),
      [],
      `the shipped ${name} must be well-formed XML`,
    );
  }
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
    for (const og of extractHtmlReferenceInventory(html).ogImages) {
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
    `<text fill="${RETIRED_INK_FAINT}">REAGENT - UIX - SLIM</text></svg>`;
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

it('rf2-bdamni: colorToHex handles percentage rgb(), 4/8-digit alpha hex, and out-of-range', () => {
  // Percentage rgb form — the docstring explicitly promises support. 50% of 255
  // is 127.5, which rounds to 128 (0x80). If percent parsing broke, the token
  // would silently drop and its WCAG contrast check would be disabled.
  assert.strictEqual(colorToHex('rgb(50%,50%,50%)'), '#808080');
  // 4-digit (#rgba) and 8-digit (#rrggbbaa) hex: the alpha pair is dropped, so
  // both normalise to the opaque #rrggbb the contrast gate operates on.
  assert.strictEqual(colorToHex('#abcd'), '#aabbcc'); // #rgba -> expand each nibble -> drop alpha
  assert.strictEqual(colorToHex('#aabbccdd'), '#aabbcc'); // #rrggbbaa -> drop the trailing alpha pair
  // An out-of-range channel (>255 or <0) is not a computable opaque colour.
  assert.strictEqual(colorToHex('rgb(300,0,0)'), null);
  assert.strictEqual(colorToHex('rgb(-5,0,0)'), null);
  // A stray 5-digit hex form is not a valid colour either.
  assert.strictEqual(colorToHex('#abcde'), null);
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
