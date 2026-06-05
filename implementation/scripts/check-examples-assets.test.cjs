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
  ALLOWLIST,
  isExternalRef,
  extractHtmlRefs,
  extractCssImports,
  resolveRef,
  scanPage,
  checkSharedTree,
  extractCssRuleBody,
  blockDeclares,
  checkCellsInputCompact,
  scanAll,
  listExampleIndexHtml,
  EXAMPLES_ROOT,
} = scanner;

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

it('LIVE: the real structure.css keeps the Cells cascade invariant (rf2-gv5xd)', () => {
  const errors = checkCellsInputCompact(require('fs'));
  assert.deepStrictEqual(
    errors,
    [],
    `Cells-input cascade errors:\n` + errors.map((e) => `    - ${e}`).join('\n'),
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
    !entry.assetExemptions.includes('_shared/img/og.svg'),
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
const OG = path.join(EXAMPLES_ROOT, '_shared', 'img', 'og.svg');
const STYLE = path.join(EXAMPLES_ROOT, '_shared', 'css', 'style.css');
const STRUCTURE = path.join(EXAMPLES_ROOT, '_shared', 'css', 'structure.css');

// A well-formed page that links all three shared assets, plus a style.css
// that @imports structure.css and an external Google-Fonts URL.
function goodHtml() {
  return [
    '<!doctype html><html><head>',
    '<meta property="og:image" content="_shared/img/og.svg">',
    '<link rel="icon" href="_shared/img/favicon.svg">',
    '<link rel="stylesheet" href="_shared/css/style.css">',
    '</head><body><script src="main.js"></script></body></html>',
  ].join('\n');
}
function goodStyleCss() {
  return [
    "@import url('https://fonts.googleapis.com/css2?family=Inter');",
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
    [OG]: '<svg/>',
    ...overrides,
  });
}

// ---- extraction primitives ----------------------------------------------

it('extractHtmlRefs picks up link/script/og:image refs, de-duped', () => {
  const refs = extractHtmlRefs(goodHtml());
  assert.ok(refs.includes('_shared/img/favicon.svg'));
  assert.ok(refs.includes('_shared/css/style.css'));
  assert.ok(refs.includes('_shared/img/og.svg'));
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
    [OG]: '<svg/>',
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
    [OG]: '<svg/>',
    // STRUCTURE intentionally absent
  });
  const { errors } = scanPage(without, PAGE);
  assert.ok(
    errors.some((e) => e.includes("@import 'structure.css'")),
    `expected a broken-@import error, got: ${errors.join(' | ')}`,
  );
});

it('TEETH: the external Google-Fonts @import is NOT flagged as missing', () => {
  const { errors } = scanPage(fullIo(), PAGE);
  assert.ok(
    !errors.some((e) => e.includes('fonts.googleapis.com')),
    'an external @import URL must never be checked on disk',
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
    '<meta property="og:image" content="_shared/img/og.svg">',
    '<link rel="icon" href="_shared/img/favicon.svg">',
    '<link rel="stylesheet" href="base.css">',
    '<link rel="stylesheet" href="index.css">',
    '<script src="main.js"></script>',
  ].join('\n');
  const io = makeIo({
    [todoPage]: todoHtml,
    [FAVICON]: '<svg/>',
    [OG]: '<svg/>',
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
    '<meta property="og:image" content="_shared/img/og.svg">',
    '<link rel="stylesheet" href="base.css">',
    '<link rel="stylesheet" href="index.css">',
    '<script src="main.js"></script>',
  ].join('\n');
  const io = makeIo({
    [todoPage]: todoHtml,
    [OG]: '<svg/>',
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

// ---------------------------------------------------------------------------
// 3) CSS-CASCADE TEETH (rf2-gv5xd) — pin the Cells-input compactness guard.
// The static resolver can't see the cascade, so checkCellsInputCompact pins
// one load-bearing invariant: `.cells-grid input { min-width: 0; width: 56px }`
// resets the shared `input[type="text"]` baseline (which keeps min-width:240px
// for the WebSocket/CRUD/etc. forms). These fixtures prove the guard has teeth.
// ---------------------------------------------------------------------------

// A structure.css with both the shared baseline and the Cells override.
// `cells` and `base` are spliced in so each direction can be broken in turn.
function structureCss({ cells, base } = {}) {
  const baseBlock =
    base != null
      ? base
      : 'input[type="text"] { padding: 8px 12px; flex: 1; min-width: 240px; }';
  const cellsBlock =
    cells != null
      ? cells
      : '.cells-grid input { width: 56px; min-width: 0; box-sizing: border-box; }';
  return [
    '/* a comment mentioning .cells-grid input must NOT be matched as a rule */',
    baseBlock,
    '.queue-depth { font-style: italic; }',
    cellsBlock,
  ].join('\n');
}

function cascadeIo(structure) {
  return makeIo({ [STRUCTURE]: structure });
}

it('extractCssRuleBody pulls a rule body by exact selector, ignoring comments', () => {
  const css = structureCss();
  const cell = extractCssRuleBody(css, '.cells-grid input');
  assert.ok(cell && cell.includes('min-width: 0'), `got: ${cell}`);
  const base = extractCssRuleBody(css, 'input[type="text"]');
  assert.ok(base && base.includes('min-width: 240px'), `got: ${base}`);
  // Whitespace in the queried selector is normalised.
  assert.ok(extractCssRuleBody(css, '.cells-grid    input') != null);
  // A selector that only appears inside a comment is not a rule.
  assert.strictEqual(extractCssRuleBody('/* .foo bar */ .baz {x:1}', '.foo bar'), null);
});

it('blockDeclares matches a declared prop value, up to the next semicolon', () => {
  assert.ok(blockDeclares('width: 56px; min-width: 0;', 'min-width', /^0$/));
  assert.ok(blockDeclares('min-width:0px', 'min-width', /^0(px)?$/));
  assert.ok(!blockDeclares('width: 56px;', 'min-width', /^0$/));
  assert.ok(!blockDeclares('min-width: 240px;', 'min-width', /^0$/));
});

it('a well-formed structure.css passes the Cells cascade guard', () => {
  const errors = checkCellsInputCompact(cascadeIo(structureCss()));
  assert.deepStrictEqual(errors, [], `expected clean, got: ${errors.join(' | ')}`);
});

it('TEETH: dropping `min-width: 0` from `.cells-grid input` fails the guard', () => {
  // The exact regression rf2-gv5xd guards against: the cell input keeps only
  // width:56px, so the shared baseline's min-width:240px re-wins on edit.
  const errors = checkCellsInputCompact(
    cascadeIo(structureCss({ cells: '.cells-grid input { width: 56px; box-sizing: border-box; }' })),
  );
  assert.ok(
    errors.some((e) => e.includes('min-width: 0') && e.includes('.cells-grid input')),
    `expected a missing min-width:0 error, got: ${errors.join(' | ')}`,
  );
});

it('TEETH: dropping `width: 56px` from `.cells-grid input` fails the guard', () => {
  const errors = checkCellsInputCompact(
    cascadeIo(structureCss({ cells: '.cells-grid input { min-width: 0; box-sizing: border-box; }' })),
  );
  assert.ok(
    errors.some((e) => e.includes('width: 56px')),
    `expected a missing width:56px error, got: ${errors.join(' | ')}`,
  );
});

it('TEETH: removing the `.cells-grid input` rule entirely fails the guard', () => {
  const errors = checkCellsInputCompact(
    cascadeIo(structureCss({ cells: '/* cells override deleted */' })),
  );
  assert.ok(
    errors.some((e) => e.includes('no `.cells-grid input` rule found')),
    `expected a missing-rule error, got: ${errors.join(' | ')}`,
  );
});

it('TEETH: gutting the shared baseline min-width fails the guard (scope, do not gut)', () => {
  // Modelling the WRONG fix (Option A taken too far): scoping the baseline so
  // hard it loses min-width would shrink the WebSocket/CRUD/etc. inputs.
  const errors = checkCellsInputCompact(
    cascadeIo(structureCss({ base: 'input[type="text"] { padding: 8px 12px; }' })),
  );
  assert.ok(
    errors.some((e) => e.includes('min-width') && e.includes('baseline')),
    `expected a gutted-baseline error, got: ${errors.join(' | ')}`,
  );
});

it('TEETH: removing the shared baseline rule entirely fails the guard', () => {
  const errors = checkCellsInputCompact(
    cascadeIo(structureCss({ base: '/* baseline deleted */' })),
  );
  assert.ok(
    errors.some((e) => e.includes('baseline is gone')),
    `expected a baseline-gone error, got: ${errors.join(' | ')}`,
  );
});

// ---- contract constant sanity -------------------------------------------

it('REQUIRED_SHARED_ASSETS names favicon, og, and style.css', () => {
  assert.deepStrictEqual([...REQUIRED_SHARED_ASSETS].sort(), [
    '_shared/css/style.css',
    '_shared/img/favicon.svg',
    '_shared/img/og.svg',
  ]);
});

if (failed > 0) {
  console.error(`\ncheck-examples-assets tests: ${failed} failed.`);
  process.exit(1);
}
console.log('\ncheck-examples-assets tests: all passed.');
