#!/usr/bin/env node
/*
 * counter-slim-and-fast bundle-comparison verifier (bead rf2-5lbx).
 *
 * The S3-008 contract from
 * implementation/adapters/reagent-slim/IMPL-SPEC.md §1.8 + §12.3:
 *
 *   - The `examples/counter-slim-and-fast` build mounts the same
 *     counter dataflow as `examples/counter`, but every Reagent import
 *     points at the `reagent2.*` rewrite (day8/reagent-slim) instead
 *     of stock `reagent.*` (day8/re-frame2-reagent, the thin bridge).
 *   - The :advanced-compiled bundle for that example MUST contain
 *     none of stock Reagent's impl-namespace fingerprints. That is the
 *     binding claim behind the "slim" framing: a re-implementation, not
 *     a thin wrapper around the stock Reagent.
 *   - The bundle MUST also contain no `react-dom/server` symbols, even
 *     though the example exercises `reagent2.dom.server/render-to-
 *     static-markup` at boot. That's the binding claim behind the
 *     pure-CLJS SSR seam from IMPL-SPEC §8 + S3-005 (the biggest single
 *     bundle win for SSR-using apps).
 *
 * Strategy mirrors scripts/check-bundle-isolation.cjs (rf2-51x5): grep,
 * not parse. The closure compiler under :advanced rewrites ns / Var
 * names, but it does NOT rewrite string literals. The sentinels chosen
 * below are string literals stock Reagent emits from its impl-namespace
 * bodies (Compiler class-name strings, ReagentInput class-name,
 * cljsRatom / cljsLegacyRender property keys). Each appears in the
 * `examples/counter` :advanced bundle and is absent from
 * `examples/counter-slim-and-fast` after slim's reimplementation
 * displaces stock Reagent's impl tree.
 *
 * Methodology validation (sanity): the same sentinels MUST appear in
 * the stock-Reagent `examples/counter` bundle. If a future stock-
 * Reagent upgrade DCEs them out of that bundle too, this test goes
 * silent — re-derive a fresh sentinel set from the new stock build.
 * The script enforces the methodology check by requiring the stock
 * bundle to be present AND to contain every sentinel at least once.
 *
 * Exit 0 on PASS, 1 on FAIL.
 */

'use strict';

const fs   = require('fs');
const path = require('path');

const ROOT = path.resolve(__dirname, '..');

// ----- the bundle-comparison contract ---------------------------------------

// Stock-Reagent impl-namespace sentinels. Each is a string literal that
// appears in the :advanced bundle of `examples/counter` exactly because
// stock Reagent's impl tree (reagent.impl.template / reagent.impl.component
// / reagent.impl.input / reagent.ratom) gets pulled in via re-frame.interop's
// `(:require [reagent.core ...] [reagent.ratom ...])`. Under slim, the
// adapter's `re-frame.adapter.reagent-slim` and re-frame.interop's slim-side
// substrate route the same surfaces through reagent2.*, displacing every
// one of these.
//
// Sentinels chosen for:
//   - uniqueness to stock Reagent's source bodies (not produced from
//     keywords at runtime; no risk of slim emitting them coincidentally),
//   - survival under :advanced (string literals are not renamed),
//   - presence in the counter bundle (i.e. they're reachable from the
//     counter example, not deep in unreached error branches).
const STOCK_REAGENT_SENTINELS = [
  // reagent.impl.template — the Compiler protocol's class methods.
  // Reagent's CompilerImpl deftype names its methods 'Compiler.parse-tag',
  // 'Compiler.as-element', etc.; the closure compiler keeps the method
  // names as identifier strings on the deftype protocol-method table.
  { source: 'reagent.impl.template/CompilerImpl.parse-tag',
    sentinel: 'Compiler.parse-tag' },
  { source: 'reagent.impl.template/CompilerImpl.as-element',
    sentinel: 'Compiler.as-element' },
  { source: 'reagent.impl.template/CompilerImpl.get-id',
    sentinel: 'Compiler.get-id' },
  { source: 'reagent.impl.template/CompilerImpl.make-element',
    sentinel: 'Compiler.make-element' },
  // reagent.impl.input — the input wrapper component class.
  { source: 'reagent.impl.input/ReagentInput display-name',
    sentinel: 'ReagentInput' },
  // reagent.ratom — Reagent's RAtom/Reaction carry a `cljsRatom` field
  // on their React-component link; the closure-name survives :advanced
  // because Reagent declares it via `set!` on the React component
  // instance (an external JS property, not an internal CLJS field).
  { source: 'reagent.ratom RAtom/Reaction cljsRatom field on React component',
    sentinel: 'cljsRatom' },
  // reagent.dom — the legacy-render path (reagent.dom/render, vs
  // the modern reagent.dom.client/render which delegates to React 18's
  // createRoot). The legacy-render sentinel only appears if stock
  // Reagent's reagent.dom ns is in the bundle.
  { source: 'reagent.dom legacy render path (cljsLegacyRender flag)',
    sentinel: 'cljsLegacyRender' },
];

// react-dom/server sentinels. Closure-imported npm modules become
// goog-namespace identifiers under :advanced — the import string
// `"react-dom/server"` survives in the runtime module map. The
// renderTo{StaticMarkup,String} method names are wrapped at call sites
// and likewise survive (Reagent and other consumers call them as
// JS interop). If any of these appears in the slim bundle, the
// pure-CLJS-SSR claim from IMPL-SPEC §8 is broken.
const REACT_DOM_SERVER_SENTINELS = [
  { source: 'react-dom/server module specifier',
    sentinel: 'react-dom/server' },
  { source: 'react-dom/server renderToStaticMarkup method',
    sentinel: 'renderToStaticMarkup' },
  { source: 'react-dom/server renderToString method',
    sentinel: 'renderToString' },
  { source: 'react-dom/server renderToPipeableStream method',
    sentinel: 'renderToPipeableStream' },
  { source: 'react-dom/server renderToReadableStream method',
    sentinel: 'renderToReadableStream' },
];

// ----- helpers ---------------------------------------------------------------

function readAllJs(dir) {
  // Concatenate every top-level *.js file in dir into a single blob.
  // For shadow-cljs :browser :release builds the closure-compiled
  // output lives in top-level files (main.js, plus any sibling module
  // files like cljs_base.js); the only subdirectory that appears in
  // the output dir is `cljs-runtime/`, which is dev-build debug source
  // left behind by a prior `shadow-cljs compile` and NOT cleaned by a
  // subsequent `shadow-cljs release` (rf2-z9a06).
  //
  // Walking recursively (as the sibling readAllJs in
  // check-bundle-isolation.cjs does) means a stale `cljs-runtime/`
  // dir gets grepped alongside the release main.js — producing false
  // FAILs on sentinels that only appear in the unoptimised dev
  // sources (e.g. cljsLegacyRender, react-dom/server). CI is unaffected
  // because every CI run is on a clean dir, but local repros after a
  // dev `compile` trip the trap; the contract should measure the
  // release artefact regardless.
  //
  // The release artefact IS exactly the top-level *.js — that's what a
  // consumer ships. Filtering to it makes the contract robust against
  // dev-build leftovers in the output dir.
  if (!fs.existsSync(dir)) {
    return null;
  }
  const out = [];
  for (const entry of fs.readdirSync(dir, { withFileTypes: true })) {
    if (entry.isFile() && entry.name.endsWith('.js')) {
      out.push(fs.readFileSync(path.join(dir, entry.name), 'utf8'));
    }
  }
  return out.join('\n');
}

function escapeRe(s) {
  return s.replace(/[.*+?^${}()|[\]\\]/g, '\\$&');
}

function countSubstring(blob, needle) {
  const re = new RegExp(escapeRe(needle), 'g');
  const m  = blob.match(re);
  return m ? m.length : 0;
}

// ----- the three contract checks --------------------------------------------

function checkAbsent(blob, sentinels, blobLabel) {
  // Assert each sentinel's hit-count is 0. Used for the slim build's
  // "no stock-Reagent / no react-dom/server" assertion.
  let ok = true;
  for (const { source, sentinel } of sentinels) {
    const hits = countSubstring(blob, sentinel);
    const passed = hits === 0;
    const tag = passed ? 'OK' : 'FAIL';
    console.log(`    [${tag}] ${source}: ` +
                `${JSON.stringify(sentinel)} expected 0 in ${blobLabel}, was ${hits}`);
    if (!passed) ok = false;
  }
  return ok;
}

function checkPresent(blob, sentinels, blobLabel) {
  // Assert each sentinel's hit-count is >=1. Used as the methodology
  // sanity check: the same sentinels must appear in the stock-Reagent
  // bundle, proving the grep has signal. If a sentinel goes to 0 in the
  // stock build too (e.g. a future Reagent rev DCEs it), we re-derive
  // the sentinel set; this script then prevents silent vacuous passes.
  let ok = true;
  for (const { source, sentinel } of sentinels) {
    const hits = countSubstring(blob, sentinel);
    const passed = hits >= 1;
    const tag = passed ? 'OK' : 'FAIL';
    console.log(`    [${tag}] ${source}: ` +
                `${JSON.stringify(sentinel)} expected >=1 in ${blobLabel}, was ${hits}`);
    if (!passed) ok = false;
  }
  return ok;
}

// ----- main ------------------------------------------------------------------

function main() {
  console.log('=== Bundle comparison: counter-slim-and-fast (rf2-5lbx) ===');
  console.log('');

  const slimDir  = path.join(ROOT, 'out', 'examples', 'counter-slim-and-fast');
  const stockDir = path.join(ROOT, 'out', 'examples', 'counter');
  const slim  = readAllJs(slimDir);
  const stock = readAllJs(stockDir);

  if (slim == null) {
    console.error(`[bundle-comparison] slim bundle missing — ${slimDir}`);
    console.error('                    Did you run "shadow-cljs release examples/counter-slim-and-fast"?');
    process.exit(1);
  }
  if (stock == null) {
    console.error(`[bundle-comparison] stock bundle missing — ${stockDir}`);
    console.error('                    Did you run "shadow-cljs release examples/counter"?');
    console.error('                    The stock bundle is required as the methodology control:');
    console.error('                    its presence of the stock-Reagent sentinels proves the');
    console.error('                    grep has signal.');
    process.exit(1);
  }

  console.log(`slim  bundle: ${slimDir}  (${slim.length} chars)`);
  console.log(`stock bundle: ${stockDir} (${stock.length} chars)`);
  console.log('');

  // Contract 1: every stock-Reagent sentinel appears in the stock
  // bundle. Methodology sanity. If this fails the test has lost signal
  // — pick a fresh sentinel set from the new stock build.
  console.log('Contract 1 (methodology): stock-Reagent sentinels are reachable from');
  console.log('                          the stock-Reagent counter (must be PRESENT).');
  const c1 = checkPresent(stock, STOCK_REAGENT_SENTINELS, 'STOCK');
  console.log('');

  // Contract 2 (the binding S3-008 claim): NONE of the stock-Reagent
  // sentinels appears in the slim bundle.
  console.log('Contract 2 (S3-008, stock-Reagent isolation): stock-Reagent impl is');
  console.log('                          ABSENT from the slim bundle.');
  const c2 = checkAbsent(slim, STOCK_REAGENT_SENTINELS, 'SLIM');
  console.log('');

  // Contract 3 (the binding pure-CLJS-SSR claim): NONE of the
  // react-dom/server sentinels appears in the slim bundle. Note this
  // is a stronger contract than the stock-bundle's react-dom/server
  // count happening to be 0: the slim build deliberately exercises
  // reagent2.dom.server/render-to-static-markup at boot
  // (counter_slim_and_fast/core.cljs's `run`), so the SSR path IS in
  // the bundle. The assertion is: the SSR path is pure-CLJS, meaning
  // no react-dom/server symbols even with SSR pulled in.
  console.log('Contract 3 (S3-005, pure-CLJS SSR): react-dom/server is ABSENT from');
  console.log('                          the slim bundle (even with reagent2.dom.server');
  console.log('                          exercised at boot).');
  const c3 = checkAbsent(slim, REACT_DOM_SERVER_SENTINELS, 'SLIM');
  console.log('');

  const allOk = c1 && c2 && c3;
  if (allOk) {
    console.log('=== PASS ===');
    process.exit(0);
  } else {
    console.error('=== FAIL ===');
    console.error('');
    if (!c1) {
      console.error('Contract 1 failed: the stock-Reagent sentinels did not appear in the');
      console.error('stock-Reagent counter bundle. The grep has lost signal — re-derive a');
      console.error('fresh sentinel set from the new stock-Reagent bundle (compare against');
      console.error('reagent.impl.{template,component,input} class-name strings, the');
      console.error('cljsRatom property key, etc.).');
    }
    if (!c2) {
      console.error('Contract 2 failed: stock-Reagent impl is leaking into the slim bundle.');
      console.error('The S3-008 framing is broken. Likely cause: a re-frame.* core ns is');
      console.error('`:require`ing stock `reagent.*` instead of routing through the slim');
      console.error('adapter\'s late-bind hooks. See implementation/adapters/reagent-slim/');
      console.error('IMPL-SPEC.md §1.4 + §1.8.');
    }
    if (!c3) {
      console.error('Contract 3 failed: react-dom/server is in the slim bundle. The');
      console.error('pure-CLJS SSR claim from IMPL-SPEC §8 + S3-005 is broken. Likely');
      console.error('cause: a `:require ["react-dom/server" ...]` slipped into a slim');
      console.error('namespace, or a downstream consumer ns imports stock');
      console.error('reagent.dom.server.');
    }
    process.exit(1);
  }
}

main();
