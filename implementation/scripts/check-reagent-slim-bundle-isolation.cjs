#!/usr/bin/env node
/*
 * Reagent Slim bundle-isolation verifier (bead rf2-5lbx / rf2-2ppcv).
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
 *   - That react-dom/server-absence claim is only a NON-vacuous S3-005
 *     proof if the SSR path is actually compiled into the bundle.
 *     Contract 4 (rf2-t5xu3u) closes the gap with a POSITIVE presence
 *     check: the slim SSR boot exercise + a `reagent2.dom.server`
 *     serializer-owned literal must both be present, so removing (or
 *     DCE-eliminating) the `render-to-static-markup` exercise FAILS the
 *     gate instead of letting the absence check pass against a
 *     no-longer-SSR bundle.
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

const path = require('path');
const { createGateReporter } = require('./lib/gate-report.cjs');
const { assertSentinelSet, classifyOrFail } = require('./lib/sentinel-scan.cjs');

const ROOT = path.resolve(__dirname, '..');
const report = createGateReporter();

// ----- the adapter-owned bundle-isolation contract ---------------------------

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

// Pure-CLJS-SSR presence sentinels (rf2-t5xu3u). Contract 3 above proves
// `react-dom/server` is ABSENT, but absence alone is vacuous: if the SSR
// exercise in counter_slim_and_fast/core.cljs were removed (or DCE'd
// away), `react-dom/server` would STILL be absent, and Contract 3 would
// keep passing against a bundle that no longer exercises SSR at all — a
// silent non-proof of S3-005. These sentinels turn the proof
// non-vacuous: they assert the slim SSR path is POSITIVELY present in the
// bundle, so removing the exercise FAILS the gate instead of slipping
// through.
//
// Two complementary sentinels, both required (checkPresent):
//
//   1. `counterSlimPrerender` — the host-global the example boot writes
//      the prerendered markup onto. This is the DCE-protection anchor
//      (writes to extern-shaped `globalThis` props are side effects the
//      closure compiler keeps under :advanced). It proves the boot still
//      performs the SSR exercise.
//
//        NB: this sentinel alone is INSUFFICIENT. The host-global write
//        survives even if the value written is a plain literal rather
//        than `(rds/render-to-static-markup …)` — i.e. it does NOT by
//        itself prove the serializer is compiled in. (Verified: stub the
//        write to a literal string and `counterSlimPrerender` stays at 1
//        while every serializer sentinel drops to 0.) Hence sentinel 2.
//
//   2. `static-markup-bad-tag` — a string literal `ex-info` type owned
//      exclusively by `reagent2.dom.server`'s `emit-vector` walker. It is
//      reachable ONLY when the SSR serializer namespace is in the bundle;
//      removing the `render-to-static-markup` call DCEs the whole
//      namespace and drops this to 0. It survives :advanced (string
//      literals are not renamed) and is absent from the stock bundle
//      (stock SSR goes through react-dom/server, not the pure-CLJS
//      serializer). This is the sentinel that actually binds "the
//      pure-CLJS SSR path is compiled in".
//
// Sentinel counts in the current release bundle (recorded for the
// re-derivation contract — if these drop to 0 in a slim build that DOES
// exercise SSR, re-pick from another serializer-owned literal, e.g.
// 'static-markup-empty-vector', 'static-markup-bad-element', or the
// 'reagent-react-component' opaque-subtree placeholder):
//   counterSlimPrerender   : 1
//   static-markup-bad-tag  : 3
const SLIM_SSR_PRESENCE_SENTINELS = [
  { source: 'counter_slim_and_fast SSR boot exercise (host-global write, DCE anchor)',
    sentinel: 'counterSlimPrerender' },
  { source: 'reagent2.dom.server emit-vector serializer (ex-info type string)',
    sentinel: 'static-markup-bad-tag' },
];

// ----- helpers ---------------------------------------------------------------
//
// Bundle reading is shared with the sibling check-* scripts via
// scripts/lib/read-release-bundle.cjs (rf2-jkake.15): `readReleaseBlob`
// reads only top-level *.js — the release artefact — so a stale dev-build
// `cljs-runtime/` subdir from a prior `shadow-cljs compile` doesn't get
// grep-ed alongside (rf2-z9a06); that trap, first documented inline here,
// was the reason the reader was factored out (rf2-qlk4w) and is now the
// shared default for the whole check-*-bundle family. The per-sentinel
// scan loop + tally is the shared assertSentinelSet (scripts/lib/
// sentinel-scan.cjs, rf2-j552l2); checkAbsent / checkPresent below supply
// this gate's exact diagnostic line format.

// ----- the four contract checks ---------------------------------------------

function checkAbsent(blob, sentinels, blobLabel) {
  // Assert each sentinel's hit-count is 0. Used for the slim build's
  // "no stock-Reagent / no react-dom/server" assertion.
  const { ok, passed } = assertSentinelSet(blob, sentinels, {
    mustContain: false,
    count: true,
    emit: (line) => report.detail(line),
    formatLine: ({ source, sentinel, hits, tag }) =>
      `    [${tag}] ${source}: ` +
      `${JSON.stringify(sentinel)} expected 0 in ${blobLabel}, was ${hits}`,
  });
  return { ok, checked: sentinels.length, passed };
}

function checkPresent(blob, sentinels, blobLabel) {
  // Assert each sentinel's hit-count is >=1. Used as the methodology
  // sanity check: the same sentinels must appear in the stock-Reagent
  // bundle, proving the grep has signal. If a sentinel goes to 0 in the
  // stock build too (e.g. a future Reagent rev DCEs it), we re-derive
  // the sentinel set; this script then prevents silent vacuous passes.
  const { ok, passed } = assertSentinelSet(blob, sentinels, {
    mustContain: true,
    count: true,
    emit: (line) => report.detail(line),
    formatLine: ({ source, sentinel, hits, tag }) =>
      `    [${tag}] ${source}: ` +
      `${JSON.stringify(sentinel)} expected >=1 in ${blobLabel}, was ${hits}`,
  });
  return { ok, checked: sentinels.length, passed };
}

// ----- main ------------------------------------------------------------------

function main() {
  report.detail('=== Reagent Slim bundle isolation (rf2-5lbx / rf2-2ppcv) ===');
  report.detail('');

  const slimDir  = path.join(ROOT, 'out', 'examples', 'counter-slim-and-fast');
  const stockDir = path.join(ROOT, 'out', 'examples', 'counter');
  // classifyOrFail (scripts/lib/sentinel-scan.cjs, rf2-j552l2) shares the
  // missing/empty two-arm guard (rf2-utvst non-vacuous floor); each reject
  // callback owns this gate's actionable stderr + the exit.
  const slimCls = classifyOrFail(slimDir, {
    onMissing: (dir) => {
      report.flushDetails();
      console.error(`[reagent-slim-bundle-isolation] slim bundle missing — ${dir}`);
      console.error('                    Did you run "shadow-cljs release examples/counter-slim-and-fast"?');
      process.exit(1);
    },
    onEmpty: (dir) => {
      report.flushDetails();
      // Non-vacuous floor (rf2-utvst): the slim bundle is checked
      // negative-only (Contracts 2+3); a present-but-empty slim dir
      // satisfies both absence checks and would false-GREEN.
      console.error(`[reagent-slim-bundle-isolation] slim bundle present but empty (zero top-level JS) — ${dir}`);
      console.error('                    The release emitted no bundle; the stock-Reagent and');
      console.error('                    react-dom/server absence checks would pass vacuously.');
      console.error('                    Rebuild "shadow-cljs release examples/counter-slim-and-fast".');
      process.exit(1);
    },
  });
  const stockCls = classifyOrFail(stockDir, {
    onMissing: (dir) => {
      report.flushDetails();
      console.error(`[reagent-slim-bundle-isolation] stock bundle missing — ${dir}`);
      console.error('                    Did you run "shadow-cljs release examples/counter"?');
      console.error('                    The stock bundle is required as the methodology control:');
      console.error('                    its presence of the stock-Reagent sentinels proves the');
      console.error('                    grep has signal.');
      process.exit(1);
    },
    onEmpty: (dir) => {
      report.flushDetails();
      console.error(`[reagent-slim-bundle-isolation] stock bundle present but empty (zero top-level JS) — ${dir}`);
      console.error('                    The methodology control emitted no bundle; the stock-');
      console.error('                    Reagent present-check would have no signal.');
      console.error('                    Rebuild "shadow-cljs release examples/counter".');
      process.exit(1);
    },
  });
  const slim  = slimCls.blob;
  const stock = stockCls.blob;

  report.detail(`slim  bundle: ${slimDir}  (${slim.length} chars)`);
  report.detail(`stock bundle: ${stockDir} (${stock.length} chars)`);
  report.detail('');

  // Contract 1: every stock-Reagent sentinel appears in the stock
  // bundle. Methodology sanity. If this fails the test has lost signal
  // — pick a fresh sentinel set from the new stock build.
  report.detail('Contract 1 (methodology): stock-Reagent sentinels are reachable from');
  report.detail('                          the stock-Reagent counter (must be PRESENT).');
  const c1 = checkPresent(stock, STOCK_REAGENT_SENTINELS, 'STOCK');
  report.detail('');

  // Contract 2 (the binding S3-008 claim): NONE of the stock-Reagent
  // sentinels appears in the slim bundle.
  report.detail('Contract 2 (S3-008, stock-Reagent isolation): stock-Reagent impl is');
  report.detail('                          ABSENT from the slim bundle.');
  const c2 = checkAbsent(slim, STOCK_REAGENT_SENTINELS, 'SLIM');
  report.detail('');

  // Contract 3 (the binding pure-CLJS-SSR claim): NONE of the
  // react-dom/server sentinels appears in the slim bundle. Note this
  // is a stronger contract than the stock-bundle's react-dom/server
  // count happening to be 0: the slim build deliberately exercises
  // reagent2.dom.server/render-to-static-markup at boot
  // (counter_slim_and_fast/core.cljs's `run`), so the SSR path IS in
  // the bundle. The assertion is: the SSR path is pure-CLJS, meaning
  // no react-dom/server symbols even with SSR pulled in.
  report.detail('Contract 3 (S3-005, pure-CLJS SSR): react-dom/server is ABSENT from');
  report.detail('                          the slim bundle (even with reagent2.dom.server');
  report.detail('                          exercised at boot).');
  const c3 = checkAbsent(slim, REACT_DOM_SERVER_SENTINELS, 'SLIM');
  report.detail('');

  // Contract 4 (S3-005 non-vacuity, rf2-t5xu3u): the slim SSR path is
  // POSITIVELY present in the bundle. Contract 3's react-dom/server
  // absence is only a meaningful S3-005 proof if the bundle actually
  // exercises SSR; without this check, removing the example's
  // `render-to-static-markup` call (or letting it get DCE'd) would leave
  // Contract 3 passing vacuously. Asserts the host-global boot anchor AND
  // a serializer-owned literal are both present.
  report.detail('Contract 4 (S3-005 non-vacuity): the pure-CLJS SSR path is PRESENT in');
  report.detail('                          the slim bundle (the boot exercise + the');
  report.detail('                          reagent2.dom.server serializer are compiled in).');
  const c4 = checkPresent(slim, SLIM_SSR_PRESENCE_SENTINELS, 'SLIM');
  report.detail('');

  const allOk = c1.ok && c2.ok && c3.ok && c4.ok;
  if (allOk) {
    const checked = c1.checked + c2.checked + c3.checked + c4.checked;
    report.pass(
      'reagent-slim-bundle-isolation',
      `4 contracts passed; ${checked} sentinel checks; slim=${slimDir} (${slim.length} chars); ` +
        `stock=${stockDir} (${stock.length} chars)`
    );
    process.exit(0);
  } else {
    report.flushDetails();
    console.error('=== FAIL ===');
    console.error('');
    if (!c1.ok) {
      console.error('Contract 1 failed: the stock-Reagent sentinels did not appear in the');
      console.error('stock-Reagent counter bundle. The grep has lost signal — re-derive a');
      console.error('fresh sentinel set from the new stock-Reagent bundle (compare against');
      console.error('reagent.impl.{template,component,input} class-name strings, the');
      console.error('cljsRatom property key, etc.).');
    }
    if (!c2.ok) {
      console.error('Contract 2 failed: stock-Reagent impl is leaking into the slim bundle.');
      console.error('The S3-008 framing is broken. Likely cause: a re-frame.* core ns is');
      console.error('`:require`ing stock `reagent.*` instead of routing through the slim');
      console.error('adapter\'s late-bind hooks. See implementation/adapters/reagent-slim/');
      console.error('IMPL-SPEC.md §1.4 + §1.8.');
      console.error('Reproduce with: cd implementation && npm run test:reagent-slim:bundle-isolation');
    }
    if (!c3.ok) {
      console.error('Contract 3 failed: react-dom/server is in the slim bundle. The');
      console.error('pure-CLJS SSR claim from IMPL-SPEC §8 + S3-005 is broken. Likely');
      console.error('cause: a `:require ["react-dom/server" ...]` slipped into a slim');
      console.error('namespace, or a downstream consumer ns imports stock');
      console.error('reagent.dom.server.');
      console.error('Reproduce with: cd implementation && npm run test:reagent-slim:bundle-isolation');
    }
    if (!c4.ok) {
      console.error('Contract 4 failed: the slim SSR path is NOT present in the bundle, so');
      console.error('Contract 3 (react-dom/server absence) is a VACUOUS S3-005 proof — the');
      console.error('bundle no longer exercises SSR at all. Likely cause: the');
      console.error('`(rds/render-to-static-markup [counter-app])` boot exercise in');
      console.error('examples/reagent-slim/counter_slim_and_fast/core.cljs was removed or');
      console.error('let get DCE-eliminated (the host-global write is the DCE anchor — keep');
      console.error('it writing the prerender result, not a literal). If the SSR exercise IS');
      console.error('still present and intentional, a serializer sentinel string may have');
      console.error('changed: re-derive from reagent2.dom.server (the ex-info type literals');
      console.error('or the reagent-react-component placeholder). See IMPL-SPEC §8 + S3-005.');
      console.error('Reproduce with: cd implementation && npm run test:reagent-slim:bundle-isolation');
    }
    process.exit(1);
  }
}

main();
