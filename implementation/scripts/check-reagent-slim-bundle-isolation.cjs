#!/usr/bin/env node
/*
 * Reagent Slim bundle-isolation verifier (bead rf2-5lbx / rf2-2ppcv;
 * classic-clean mirror rf2-81ndde).
 *
 * Two isolation directions between the coexisting adapter trees:
 *   - the SLIM bundles must be clean of stock Reagent's impl +
 *     react-dom/server (Contracts 1-4, the S3-008 / S3-005 claims below);
 *   - the CLASSIC (`examples/counter`, stock-Reagent) bundle must be clean of
 *     the slim `reagent2.*` rewrite (Contracts 5-6, rf2-81ndde — the mirror
 *     that closes the IMPL-SPEC §12.3:1070 follow-up).
 *
 * THREE bundles, not two (rf2-kjx1). The gate needs an SSR exercise compiled
 * in to make its react-dom/server-absence claim non-vacuous (Contract 4), and
 * for a long time it got one by pointing the RUNNABLE example build's
 * `:init-fn` at the fixture entry. That made the artefact a reader serves —
 * and the bundle the substrates catalogue invites them to weigh against
 * stock — carry a CI harness the stock side does not, so the "same app,
 * different package" comparison silently moved two variables. The fixture now
 * has a build of its own:
 *
 *   out/examples/counter-slim-and-fast      RUNNABLE. Boots
 *                                           reagent-slim.counter.core/run and
 *                                           stops. This is what ships, what
 *                                           `dev:example` serves, and what a
 *                                           size comparison should weigh.
 *   out/reagent-slim-ssr-isolation-fixture  GATE-ONLY. The same boot PLUS the
 *                                           pure-CLJS SSR exercise. Never
 *                                           served, never staged.
 *   out/examples/counter                    CLASSIC control (stock Reagent).
 *
 * Because the fixture entry calls `core/run` and then adds the exercise, the
 * gate bundle is the runnable bundle plus one thing — so Contracts 2/3/5 hold
 * over BOTH slim bundles, Contract 4 names what the fixture adds, and
 * Contract 7 asserts the runnable bundle does not have it. Contract 0 pins the
 * two `:init-fn`s in shadow-cljs.edn so the split cannot be quietly undone.
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

const fs = require('fs');
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
// exercise in bundle_isolation_fixture.cljs were removed (or DCE'd
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
//      exclusively by `reagent2.dom.server`'s `emit-hiccup-vector` walker. It is
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
//
// rf2-kjx1: this set now does DOUBLE duty, and the two duties are opposite.
// It is checked PRESENT in the gate-only fixture bundle (Contract 4, above)
// and ABSENT from the runnable example bundle (Contract 7). One set, both
// directions, which is what makes the separation checkable rather than merely
// intended: the fixture entry boots `core/run` and then adds the exercise, so
// these two strings are the entire difference between the two slim bundles.
// If a future edit re-points the runnable build at the fixture entry, or
// reaches into the fixture from the teaching namespace, Contract 7 goes red.
const SLIM_SSR_PRESENCE_SENTINELS = [
  { source: 'bundle-isolation fixture SSR exercise (host-global write, DCE anchor)',
    sentinel: 'counterSlimPrerender' },
  { source: 'reagent2.dom.server emit-hiccup-vector serializer (ex-info type string)',
    sentinel: 'static-markup-bad-tag' },
];

// Slim `reagent2.*` impl-namespace sentinels — the MIRROR direction
// (rf2-81ndde, closing the IMPL-SPEC §12.3:1070 follow-up). Contracts 2+3
// prove the SLIM bundle is clean of stock Reagent + react-dom/server; this
// set proves the COMPLEMENT: the CLASSIC (stock-Reagent) `examples/counter`
// bundle is clean of the slim `reagent2.*` rewrite. The two adapter trees
// coexist on the same in-tree shadow-cljs classpath, so the binding claim
// is that Closure :advanced DCE drops every slim namespace from a build that
// only `:require`s the classic adapter — the classic thin-bridge adapter and
// the shared `re-frame.substrate.spine` confine their `reagent2.*` names to
// runtime late-bind lookups / doc comments, never a static CLJS `:require`.
//
// Each sentinel is a `:rf.error/<id>` string literal emitted from a
// `reagent2.*` function body. They are chosen for:
//   - uniqueness to the slim tree — a repo-wide grep for each hits ONLY
//     implementation/adapters/reagent-slim/ (src + test + docs), so a
//     classic-bundle hit unambiguously means a slim ns body was compiled in,
//   - survival under :advanced (string literals are not renamed),
//   - reachability on the slim render path (NOT SSR-only): both live in
//     `reagent2.impl.template`'s `vec-to-elem`/`as-element` hiccup dispatch,
//     the interpreter every slim mount drives — so they are PRESENT in the
//     slim counter bundle (Contract 5's methodology present-check), reachable
//     independently of the SSR exercise the SLIM_SSR_PRESENCE_SENTINELS anchor.
//
// If a future refactor renames these ex-info ids, BOTH the slim present-check
// (Contract 5) and the classic absent-check (Contract 6) go silent together;
// Contract 5 fails fast on the lost signal — re-derive from another
// `reagent2.*` body literal (e.g. `create-class-key-unsupported` in
// reagent2.core / reagent2.impl.component, or `as-element-fn-unregistered`).
const SLIM_REAGENT2_SENTINELS = [
  // reagent2.impl.template/vec-to-elem — empty-hiccup-vector throw. On the
  // core `as-element` dispatch path, reached by every slim render.
  { source: 'reagent2.impl.template vec-to-elem (rf.error/template-empty-vector)',
    sentinel: 'rf.error/template-empty-vector' },
  // reagent2.impl.template/vec-to-elem — bad-head throw. Second sentinel
  // guards against a future rename of one but not the other.
  { source: 'reagent2.impl.template vec-to-elem (rf.error/template-bad-tag)',
    sentinel: 'rf.error/template-bad-tag' },
];

// ----- the entrypoint contract (Contract 0, rf2-kjx1) ------------------------
//
// The bundle greps below can only judge the artefacts a release produced; they
// cannot say which SOURCE each build was told to boot, and that is precisely
// where the defect this contract closes used to live. A one-token edit in
// shadow-cljs.edn is enough to point the runnable example back at the fixture
// entry, and every remaining contract would keep passing — Contract 7 would go
// red, but with a diagnostic about a stray sentinel rather than about the
// wiring that put it there. So assert the wiring itself, in the file that owns
// it, and assert it FIRST: a config break should read as a config break.
//
// Deliberately a targeted read rather than a full EDN parse. shadow-cljs.edn
// puts a build's map immediately after its key with comments only ABOVE keys,
// so "the text between this key and the next blank line" is exactly one build
// map; a parser would buy nothing here and would couple this gate to the whole
// 2000-line config.
const SHADOW_EDN = path.join(ROOT, 'shadow-cljs.edn');

const EXPECTED_INIT_FNS = [
  {
    build: ':examples/counter-slim-and-fast',
    initFn: 'reagent-slim.counter.core/run',
    why: 'the RUNNABLE example must boot the code it teaches — a reader who ' +
      'reads core/run must be reading what the build actually starts',
  },
  {
    build: ':reagent-slim-ssr-isolation-fixture',
    initFn: 'reagent-slim.counter.bundle-isolation-entry/run',
    why: 'the SSR fixture must keep a non-runnable build of its own, so ' +
      'Contract 4 has a bundle to be non-vacuous in',
  },
];

// Return the `:init-fn` symbol declared by `buildId`, or null when the build
// (or its `:init-fn`) is absent. `block` is returned for diagnostics.
function readInitFn(edn, buildId) {
  const escaped = buildId.replace(/[.*+?^${}()|[\]\\/-]/g, '\$&');
  const keyRe = new RegExp('^[ \t]*' + escaped + '[ \t]*$', 'm');
  const m = keyRe.exec(edn);
  if (!m) return { found: false, initFn: null };
  const rest = edn.slice(m.index + m[0].length);
  const blank = rest.search(/\r?\n[ \t]*\r?\n/);
  const block = blank === -1 ? rest : rest.slice(0, blank);
  const im = /:init-fn\s+([^\s{}[\]]+)/.exec(block);
  return { found: true, initFn: im ? im[1] : null, block };
}

function checkEntrypoints() {
  let edn;
  try {
    edn = fs.readFileSync(SHADOW_EDN, 'utf8');
  } catch (err) {
    report.detail(`    [FAIL] could not read ${SHADOW_EDN}: ${err.message}`);
    return { ok: false, checked: EXPECTED_INIT_FNS.length, passed: 0 };
  }
  let passed = 0;
  for (const want of EXPECTED_INIT_FNS) {
    const got = readInitFn(edn, want.build);
    const ok = got.found && got.initFn === want.initFn;
    if (ok) passed += 1;
    report.detail(
      `    [${ok ? 'ok' : 'FAIL'}] ${want.build}: :init-fn expected ` +
        `${want.initFn}, was ${got.found ? String(got.initFn) : '<build absent>'}`
    );
  }
  return { ok: passed === EXPECTED_INIT_FNS.length, checked: EXPECTED_INIT_FNS.length, passed };
}

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

// ----- the six contract checks ----------------------------------------------

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
  report.detail('=== Reagent Slim bundle isolation (rf2-5lbx / rf2-2ppcv; classic-clean rf2-81ndde) ===');
  report.detail('');

  // Contract 0 first, and off the config rather than the bundles: if the
  // wiring moved, say so in those terms before any grep gets a chance to
  // report the symptom instead of the cause.
  report.detail('Contract 0 (rf2-kjx1, entrypoints): shadow-cljs.edn points the runnable');
  report.detail('                          example at the teaching run and the SSR fixture');
  report.detail('                          at its own non-runnable build.');
  const c0 = checkEntrypoints();
  report.detail('');

  const slimDir    = path.join(ROOT, 'out', 'examples', 'counter-slim-and-fast');
  const fixtureDir = path.join(ROOT, 'out', 'reagent-slim-ssr-isolation-fixture');
  const stockDir   = path.join(ROOT, 'out', 'examples', 'counter');
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
  // The gate-only fixture bundle (rf2-kjx1). It gets the same two-arm guard:
  // a missing or empty fixture output would make Contract 4's presence check
  // impossible AND leave Contract 7 asserting absence against nothing.
  const fixtureCls = classifyOrFail(fixtureDir, {
    onMissing: (dir) => {
      report.flushDetails();
      console.error(`[reagent-slim-bundle-isolation] SSR fixture bundle missing — ${dir}`);
      console.error('                    Did you run "shadow-cljs release reagent-slim-ssr-isolation-fixture"?');
      console.error('                    This is the gate-only build that compiles the pure-CLJS');
      console.error('                    SSR exercise; without it Contract 4 has nothing to prove');
      console.error('                    non-vacuity against.');
      process.exit(1);
    },
    onEmpty: (dir) => {
      report.flushDetails();
      console.error(`[reagent-slim-bundle-isolation] SSR fixture bundle present but empty (zero top-level JS) — ${dir}`);
      console.error('                    Contract 4 would have no signal and Contract 7 would');
      console.error('                    compare the runnable bundle against nothing.');
      console.error('                    Rebuild "shadow-cljs release reagent-slim-ssr-isolation-fixture".');
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
  const slim    = slimCls.blob;
  const fixture = fixtureCls.blob;
  const stock   = stockCls.blob;

  report.detail(`slim (runnable) bundle: ${slimDir}  (${slim.length} chars)`);
  report.detail(`slim (fixture)  bundle: ${fixtureDir}  (${fixture.length} chars)`);
  report.detail(`stock bundle          : ${stockDir} (${stock.length} chars)`);
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
  report.detail('                          ABSENT from BOTH slim bundles.');
  const c2a = checkAbsent(slim, STOCK_REAGENT_SENTINELS, 'SLIM');
  const c2b = checkAbsent(fixture, STOCK_REAGENT_SENTINELS, 'SLIM-FIXTURE');
  const c2 = { ok: c2a.ok && c2b.ok, checked: c2a.checked + c2b.checked };
  report.detail('');

  // Contract 3 (the binding pure-CLJS-SSR claim): NONE of the
  // react-dom/server sentinels appears in either slim bundle. On the FIXTURE
  // bundle this is a stronger contract than the stock-bundle's
  // react-dom/server count happening to be 0: that build deliberately
  // exercises reagent2.dom.server/render-to-static-markup at boot (via
  // bundle_isolation_entry), so the SSR path IS in it. The assertion is: the
  // SSR path is pure-CLJS, meaning no react-dom/server symbols even with SSR
  // pulled in. The runnable bundle is checked too — cheap, and it closes the
  // route by which a stock reagent.dom.server import could reach the shipped
  // artefact without touching the fixture.
  report.detail('Contract 3 (S3-005, pure-CLJS SSR): react-dom/server is ABSENT from both');
  report.detail('                          slim bundles (including the fixture, where');
  report.detail('                          reagent2.dom.server IS exercised at boot).');
  const c3a = checkAbsent(slim, REACT_DOM_SERVER_SENTINELS, 'SLIM');
  const c3b = checkAbsent(fixture, REACT_DOM_SERVER_SENTINELS, 'SLIM-FIXTURE');
  const c3 = { ok: c3a.ok && c3b.ok, checked: c3a.checked + c3b.checked };
  report.detail('');

  // Contract 4 (S3-005 non-vacuity, rf2-t5xu3u): the slim SSR path is
  // POSITIVELY present in the bundle. Contract 3's react-dom/server
  // absence is only a meaningful S3-005 proof if the bundle actually
  // exercises SSR; without this check, removing the example's
  // `render-to-static-markup` call (or letting it get DCE'd) would leave
  // Contract 3 passing vacuously. Asserts the host-global boot anchor AND
  // a serializer-owned literal are both present.
  report.detail('Contract 4 (S3-005 non-vacuity): the pure-CLJS SSR path is PRESENT in');
  report.detail('                          the FIXTURE bundle (the boot exercise + the');
  report.detail('                          reagent2.dom.server serializer are compiled in).');
  const c4 = checkPresent(fixture, SLIM_SSR_PRESENCE_SENTINELS, 'SLIM-FIXTURE');
  report.detail('');

  // Contract 5 (rf2-81ndde methodology): the slim `reagent2.*` sentinels are
  // reachable from the slim counter (must be PRESENT). Mirror of Contract 1 —
  // proves the classic-clean grep in Contract 6 has signal. If these go to 0
  // in a slim build, the sentinel set has lost signal — re-derive from another
  // reagent2.* body literal.
  report.detail('Contract 5 (rf2-81ndde methodology): slim reagent2.* sentinels are');
  report.detail('                          reachable from the slim counter (must be PRESENT');
  report.detail('                          in BOTH slim bundles).');
  const c5a = checkPresent(slim, SLIM_REAGENT2_SENTINELS, 'SLIM');
  const c5b = checkPresent(fixture, SLIM_REAGENT2_SENTINELS, 'SLIM-FIXTURE');
  const c5 = { ok: c5a.ok && c5b.ok, checked: c5a.checked + c5b.checked };
  report.detail('');

  // Contract 6 (the binding rf2-81ndde claim, IMPL-SPEC §12.3:1070): NONE of
  // the slim `reagent2.*` sentinels appears in the CLASSIC (stock-Reagent)
  // bundle. The complement of Contract 2 (stock ABSENT from slim): slim is
  // ABSENT from classic. Both adapter trees live on the same in-tree
  // classpath; this proves :advanced DCE keeps the slim rewrite out of a
  // build that only `:require`s the classic thin-bridge adapter.
  report.detail('Contract 6 (rf2-81ndde, slim isolation): the slim reagent2.* rewrite is');
  report.detail('                          ABSENT from the classic stock-Reagent bundle.');
  const c6 = checkAbsent(stock, SLIM_REAGENT2_SENTINELS, 'STOCK');
  report.detail('');

  // Contract 7 (rf2-kjx1): the RUNNABLE example bundle is free of the gate
  // fixture. This is the contract the whole three-build split exists to make
  // statable. Contract 4 proves the SSR exercise is compiled into the fixture
  // build; this proves it is compiled into nothing else — so the artefact a
  // reader serves, and the bundle they weigh against `examples/counter`, is
  // the counter and the slim substrate, with no CI harness riding along.
  //
  // Its non-vacuity comes from Contract 5's present-check on the same blob:
  // an empty or wrong-build slim bundle would satisfy this absence trivially,
  // and Contract 5 refuses to let that pass silently — the ordinary slim
  // client sentinels must be there. Absence proved over a bundle already
  // proved to be the slim counter.
  report.detail('Contract 7 (rf2-kjx1, runnable purity): the gate fixture is ABSENT from');
  report.detail('                          the runnable slim bundle (non-vacuous via');
  report.detail('                          Contract 5 over the same blob).');
  const c7 = checkAbsent(slim, SLIM_SSR_PRESENCE_SENTINELS, 'SLIM');
  report.detail('');

  const allOk = c0.ok && c1.ok && c2.ok && c3.ok && c4.ok && c5.ok && c6.ok && c7.ok;
  if (allOk) {
    const checked =
      c1.checked + c2.checked + c3.checked + c4.checked + c5.checked + c6.checked + c7.checked;
    report.pass(
      'reagent-slim-bundle-isolation',
      `8 contracts passed; ${checked} sentinel checks + ${c0.checked} entrypoint checks; ` +
        `slim=${slimDir} (${slim.length} chars); fixture=${fixtureDir} (${fixture.length} chars); ` +
        `stock=${stockDir} (${stock.length} chars)`
    );
    process.exit(0);
  } else {
    report.flushDetails();
    console.error('=== FAIL ===');
    console.error('');
    if (!c0.ok) {
      console.error('Contract 0 failed: shadow-cljs.edn does not wire the two builds the way');
      console.error('this gate requires. The runnable :examples/counter-slim-and-fast must');
      console.error('boot reagent-slim.counter.core/run — the code its README teaches and its');
      console.error('bundle is compared on — and :reagent-slim-ssr-isolation-fixture must own');
      console.error('reagent-slim.counter.bundle-isolation-entry/run. Pointing the runnable');
      console.error('build at the fixture entry is the regression rf2-kjx1 fixed: it puts a CI');
      console.error('SSR harness into the artefact readers serve and compare, so the');
      console.error('"same app, different package" comparison moves two variables at once.');
      console.error('Fix the :init-fn in implementation/shadow-cljs.edn, not this check.');
      console.error('Reproduce with: cd implementation && npm run test:reagent-slim:bundle-isolation');
    }
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
      console.error('Contract 4 failed: the slim SSR path is NOT present in the FIXTURE');
      console.error('bundle, so Contract 3 (react-dom/server absence) is a VACUOUS S3-005');
      console.error('proof — nothing exercises SSR at all. Likely cause: the');
      console.error('`(rds/render-to-static-markup ...)` SSR exercise in');
      console.error('examples/substrates/reagent_slim/counter/bundle_isolation_fixture.cljs');
      console.error('(prove-pure-cljs-ssr!, called by bundle_isolation_entry/run) was removed or');
      console.error('let get DCE-eliminated (the host-global write is the DCE anchor — keep');
      console.error('it writing the prerender result, not a literal). If the SSR exercise IS');
      console.error('still present and intentional, a serializer sentinel string may have');
      console.error('changed: re-derive from reagent2.dom.server (the ex-info type literals');
      console.error('or the reagent-react-component placeholder). See IMPL-SPEC §8 + S3-005.');
      console.error('Reproduce with: cd implementation && npm run test:reagent-slim:bundle-isolation');
    }
    if (!c5.ok) {
      console.error('Contract 5 failed: the slim reagent2.* sentinels did not appear in the');
      console.error('slim counter bundle. The classic-clean grep (Contract 6) has lost signal');
      console.error('— re-derive a fresh sentinel set from a reagent2.* body literal (e.g.');
      console.error('rf.error/create-class-key-unsupported in reagent2.core / reagent2.impl.');
      console.error('component, or rf.error/as-element-fn-unregistered). See IMPL-SPEC §12.3.');
      console.error('Reproduce with: cd implementation && npm run test:reagent-slim:bundle-isolation');
    }
    if (!c6.ok) {
      console.error('Contract 6 failed: the slim reagent2.* rewrite is leaking into the');
      console.error('CLASSIC (stock-Reagent) examples/counter bundle. The complementary');
      console.error('isolation claim (IMPL-SPEC §12.3:1070) is broken: the classic thin-bridge');
      console.error('adapter must not pull slim. Likely cause: a static CLJS `:require` on a');
      console.error('`reagent2.*` ns slipped into a classic-reachable path (a core/* ns, the');
      console.error('shared re-frame.substrate.spine, or the classic re-frame.adapter.reagent)');
      console.error('— those must confine reagent2.* to runtime late-bind lookups, never a');
      console.error('static require. See implementation/adapters/reagent-slim/IMPL-SPEC.md §12.3.');
      console.error('Reproduce with: cd implementation && npm run test:reagent-slim:bundle-isolation');
    }
    if (!c7.ok) {
      console.error('Contract 7 failed: the bundle-isolation SSR fixture is present in the');
      console.error('RUNNABLE examples/counter-slim-and-fast bundle. That bundle is what');
      console.error('`npm run dev:example -- examples/counter-slim-and-fast` serves and what');
      console.error('examples/substrates/README.md invites a reader to weigh against');
      console.error('examples/counter, so a CI harness inside it silently adds a second');
      console.error('variable to a comparison whose whole claim is that only the package');
      console.error('changed (rf2-kjx1). Likely causes, in order of likelihood: the runnable');
      console.error('build\'s :init-fn was re-pointed at bundle-isolation-entry (Contract 0');
      console.error('names that case directly); or reagent-slim.counter.core — or something');
      console.error('it requires — now reaches bundle_isolation_fixture / reagent2.dom.server');
      console.error('on the client path. Keep the fixture reachable ONLY from');
      console.error('examples/substrates/reagent_slim/counter/bundle_isolation_entry.cljs,');
      console.error('which belongs to the :reagent-slim-ssr-isolation-fixture build alone.');
      console.error('Reproduce with: cd implementation && npm run test:reagent-slim:bundle-isolation');
    }
    process.exit(1);
  }
}

// Checker-owned target contract (rf2-kfn9q): the exact implementation-relative
// runtime this gate isolates — the slim adapter. It proves the slim bundle is
// free of stock Reagent + react-dom/server (and the classic bundle free of the
// reagent2.* rewrite), so it is the isolation authority for adapters/reagent-slim.
// See the binding in check-bundle-isolation.cjs (validateDedicatedGate).
const COVERS_RUNTIMES = ['adapters/reagent-slim'];

module.exports = { COVERS_RUNTIMES };

// Run only when invoked directly, not when required for its target contract.
if (require.main === module) {
  main();
}
