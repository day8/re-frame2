#!/usr/bin/env node
/*
 * Bundle-isolation verifier.
 *
 * The counter example is the canonical no-feature app: it imports zero
 * per-feature artefacts AND nothing under tools/. Three families of
 * leak are pinned absent from its production bundle:
 *   - per-feature splits (schemas / machines / routing / flows / http /
 *     ssr) — each ships as its own Maven jar; core MUST NOT
 *     `:require` any of them (the re-export wrappers late-bind at call
 *     time);
 *   - the tooling siblings + dev-only composers (trace.tooling,
 *     subs.tooling, the EP-0014 algebra-view siblings, derivation.graph,
 *     Story) — dev/inspection surfaces gated behind the
 *     Xray preload;
 *   - dev-only npm/Maven dependencies machines-viz + the Xray EDN widget
 *     pull in (xyflow / elkjs / zprint / editscript).
 * This script pins all three absent, so a
 * change that accidentally re-imports a split-out namespace into core
 * (e.g. `(:require [re-frame.flows])` slipping into `re-frame.core`) — or
 * drags a tools/ ns / dev dep into a production-reachable path — is caught
 * by CI rather than by a downstream consumer paying for the regression.
 *
 * Each claim is answered by the artefact that can answer it:
 *
 *   - A GRAPH claim — "the counter build does not load this namespace" — is
 *     read from the build's own manifest.edn, which shadow-cljs writes beside
 *     main.js: per module, the classpath-relative path of every source
 *     compiled into it. An entry names the sources it forbids
 *     (`re_frame/flows.cljc`, `re_frame/story/`, `node_modules/@xyflow/`), and
 *     each of those must resolve on the build's classpath, so a rename fails
 *     loud instead of leaving a path that can never match.
 *
 *   - An ELISION claim — "the build loads this namespace, but Closure drops
 *     these bodies" — cannot be read from the graph, because the namespace IS
 *     in it. Only the emitted bytes can answer, so the entry greps the
 *     `:advanced` output for string literals the bodies emit (Closure renames
 *     symbols but never rewrites string literals), and proves each literal
 *     live in the same compile with the bodies reached and their gate open,
 *     since a grep for a literal that exists nowhere passes vacuously.
 *     `trace-tooling` is the canonical case: core requires
 *     `re-frame.trace.tooling` statically, and its bodies reach no production
 *     bundle.
 *
 * One claim reads a different app. Story's `enabled?` false elision
 * (STORY_ELISION) needs an app that imports Story, so it reads
 * :examples/counter-with-stories released with Story compiled out, against
 * the same app released with Story on.
 *
 * The allow-list budgets are grep too: a small set of consumer-side keyword
 * strings (e.g. `flows/reg-flow`, `rf.http/managed`) intentionally remain in
 * the counter bundle even when the per-feature artefacts are NOT loaded —
 * they are the late-bind hook keys / fx keys that core publishes for the
 * artefact to populate at ns-load time — and each budget caps their count.
 *
 * Exit 0 on PASS, 1 on FAIL.
 */

'use strict';

const fs = require('fs');
const os = require('os');
const path = require('path');
const { createGateReporter } = require('./lib/gate-report.cjs');
const { EdnReadError, readEdn, isMap, mapGetKeyword } = require('./lib/edn.cjs');
const {
  classifyReleaseBundle,
  readBuildManifest,
  countMatches,
  countSubstring,
} = require('./lib/read-release-bundle.cjs');
const { assertSentinelSet } = require('./lib/sentinel-scan.cjs');
const { pathDeclaresBuildAlias, listPublishableRuntimes } = require('./lib/publishable-runtimes.cjs');

const ROOT = path.resolve(__dirname, '..');
const SCRIPTS_DIR = __dirname;
const report = createGateReporter();

// ----- the bundle-isolation contract ----------------------------------------

// Each artefact has a `name` and exactly one claim:
//   - `forbiddenSources`, a GRAPH claim: classpath-relative source paths the
//     counter build's manifest must not list. A path ending in `/` names a
//     directory and forbids everything under it; any other path names one
//     file. Each must resolve on the build's classpath
//     (assertForbiddenSourcesLive), or it could never match.
//   - `internalSentinels`, an ELISION claim: `{ source, sentinel }` pairs, each
//     `sentinel` a string literal the artefact's bodies emit on a live path. A
//     hit in the counter bundle means bodies Closure should have dropped
//     survived. Its POSITIVE_CONTROL entry proves every sentinel present where
//     those bodies are reached.
// Optionally:
//   - `relPath`: a publishable per-feature runtime's directory under
//     implementation/, which canonical coverage keys on (exact path, never
//     leaf name).
//   - `consumerAllowList` + `expectedAllowListHits`: a regex over consumer-side
//     keyword strings core publishes for this artefact even when it is NOT
//     loaded (late-bind hook keys, fx keys), and the ceiling on their count in
//     the counter bundle. The budget fails on EXCEEDS, not on DECREASE — a
//     refactor that shrinks the consumer-side surface is a strict win.
const ARTEFACTS = [
  // The per-feature runtimes. Each ships as its own Maven jar, and core
  // reaches each one through the late-bind hook table at call time rather than
  // a `:require`, so a consumer that never requires one never pays for it.
  {
    name: 'schemas',
    relPath: 'schemas',
    forbiddenSources: ['re_frame/schemas.cljc', 're_frame/schemas/'],
  },

  {
    name: 'machines',
    relPath: 'machines',
    forbiddenSources: ['re_frame/machines.cljc', 're_frame/machines/'],
  },

  {
    name: 'routing',
    relPath: 'routing',
    forbiddenSources: ['re_frame/routing.cljc', 're_frame/routing/'],
  },

  {
    name: 'flows',
    relPath: 'flows',
    forbiddenSources: ['re_frame/flows.cljc', 're_frame/flows/'],
    // One consumer-side string:
    //   `flows/reg-flow`     — late-bind hook key core publishes for
    //                          the flows artefact's reg-flow surface,
    //                          also the feature registry's probe key.
    consumerAllowList: /flows\/reg-flow/g,
    expectedAllowListHits: 2,
  },

  {
    name: 'http',
    relPath: 'http',
    forbiddenSources: ['re_frame/http/'],
    // Consumer-side strings (Spec 014):
    //   `rf.http/stub-only?` — the frame-config key core's :test/:story
    //                          presets set (frame.cljc); the HTTP artefact
    //                          reads it.
    //   `rf.http/managed`    — the fx-args classification walk's case key in
    //                          re-frame.classification/project-fx-args (a
    //                          keyword literal gating the
    //                          :http/project-managed-fx-args late-bind
    //                          consult — the blessed consumer-side pattern,
    //                          no :require).
    consumerAllowList: /rf\.http\/stub-only\?|rf\.http\/managed/g,
    expectedAllowListHits: 2,
  },

  // Epoch history and time-travel (re-frame.epoch, Tool-Pair §Time-travel) — an
  // ELISION claim, because `re-frame.core` requires `re-frame.epoch`, so the
  // namespace IS in the counter build's graph. The counter bundle stays clean
  // because epoch's load-time hook publication, its lifecycle contribution and
  // every core call into it sit behind `rf.interop/debug-enabled?`, so Closure
  // drops the bodies. `rf.epoch/snapshotted` is emitted by `settle!`, which only
  // that publication reaches; `rf.epoch/restored` by `restore-epoch!`.
  {
    name: 'epoch',
    internalSentinels: [
      { source: 're-frame.epoch/settle! (rf.epoch/snapshotted)',
        sentinel: 'rf.epoch/snapshotted' },
      { source: 're-frame.epoch/restore-epoch! (rf.epoch/restored)',
        sentinel: 'rf.epoch/restored' },
    ],
    // One consumer-side string: `epoch/settle!` — the late-bind hook key the
    // router looks up after each event (router.cljc). A production build
    // publishes nothing under it, so the lookup returns nil and the call is a
    // no-op.
    consumerAllowList: /epoch\/settle!/g,
    expectedAllowListHits: 1,
  },

  {
    name: 'ssr',
    relPath: 'ssr',
    forbiddenSources: ['re_frame/ssr.cljc', 're_frame/ssr/'],
  },

  // Resources runtime (the `day8/re-frame2-resources` artefact, Spec 016), a
  // separately published, browser-reachable OPTIONAL per-feature runtime
  // (`.github/scripts/verify-version-lockstep.sh` ships it in the release
  // inventory). `re-frame.core` MUST NOT `:require` it — the resources.cljc
  // header (§Optionality + bundle isolation) pins that, and the public surface
  // late-binds through the hook table so an app that omits the artefact sees a
  // clean `:rf.error/resources-artefact-missing`.
  {
    name: 'resources',
    relPath: 'resources',
    forbiddenSources: ['re_frame/resources.cljc', 're_frame/resources/'],
  },

  // Fresco, the lean-React view substrate (the `day8/re-frame2-fresco`
  // artefact), a separately published, browser-reachable OPTIONAL substrate:
  // nothing in core or the adapters `:require`s it, and the counter example is
  // a Reagent app that never names a `re-frame.fresco.*` namespace.
  {
    name: 'fresco',
    relPath: 'fresco',
    forbiddenSources: ['re_frame/fresco.cljc', 're_frame/fresco/'],
  },

  // re-frame.trace.tooling (the dev-tooling buffer + listener surface, kept
  // apart from re-frame.trace for production DCE) — an ELISION claim, because
  // the namespace IS in the counter build's graph: `re-frame.core` and
  // `re-frame.trace` both `:require` `re-frame.trace.tooling` statically on
  // both platforms, and the facade's `:trace` arm plus `rf/trace-buffer` /
  // `rf/clear-trace-buffer!` name `re-frame.trace.tooling/…` directly —
  // `re-frame.trace` publishes no listener/buffer surface of its own. The
  // counter bundle stays clean because those bodies sit behind
  // `rf.interop/debug-enabled?` and the counter never calls them, so Closure
  // drops them. The sentinel is the `:trace-events` slot of the tooling's
  // `trace-buffer` event-bundle projection; the only other code emitting it is
  // epoch, whose bodies the `epoch` elision claim pins out of this bundle too.
  // A non-zero hit means one of the two reached a production bundle. It is
  // the QUOTED name, the token a release emits for the keyword, because
  // `"trace-events-keep"` is in every core release: epoch's default config
  // literal rides the top-level atom allocations Closure keeps.
  {
    name: 'trace-tooling',
    internalSentinels: [
      { source: 're-frame.trace.tooling trace-buffer projection field',
        sentinel: '"trace-events"' },
    ],
  },

  // re-frame.subs.tooling (`sub-topology` and `sub-cache-snapshot`, kept apart
  // from re-frame.subs for production DCE). Xray, re-frame2-pair-mcp and
  // re-frame-10x `:require` it; counter never does. The JVM-side aliases in
  // `re-frame.subs` and `re-frame.core` are `#?(:clj ...)`-gated, so they
  // never appear in CLJS compilation.
  {
    name: 'subs-tooling',
    forbiddenSources: ['re_frame/subs/tooling.cljc'],
  },

  // The four EP-0014 algebra-view siblings (`flow-algebra-view`,
  // `resource-algebra-view`, `route-algebra-view`, `machine-algebra-view` and
  // their kin), each kept apart from its optional artefact for production DCE.
  // Every parent's require on its sibling is `#?@(:clj ...)`-gated, so the body
  // stays out of CLJS. The `flows`, `resources`, `routing` and `machines`
  // entries above forbid each parent's whole tree, so no no-feature app can
  // reach these; each keeps its own entry so a leak is named for the sibling
  // that leaked.
  {
    name: 'flows-tooling',
    forbiddenSources: ['re_frame/flows/tooling.cljc'],
  },

  {
    name: 'resources-tooling',
    forbiddenSources: ['re_frame/resources/tooling.cljc'],
  },

  {
    name: 'routing-tooling',
    forbiddenSources: ['re_frame/routing/tooling.cljc'],
  },

  {
    name: 'machines-tooling',
    forbiddenSources: ['re_frame/machines/tooling.cljc'],
  },

  // re-frame.derivation.graph (EP-0014: the internal graph-inspection COMPOSER
  // that stitches the five algebra-view siblings into one DerivationGraph
  // view). It lives in core/src but composes the four OPTIONAL siblings
  // through a runtime contributor seam (requiring-resolve on JVM; an explicit
  // contributor map on CLJS) rather than a static cross-artefact `:require`,
  // so it can never drag them into a core-only bundle. Only dev tools (Xray)
  // and the conformance fixtures `:require` it; the counter example never does.
  {
    name: 'derivation-graph',
    forbiddenSources: ['re_frame/derivation/graph.cljc'],
  },

  // re-frame.derivation.egress (the OFF-BOX graph-egress redaction ALGORITHM,
  // one copy shared by the Xray call site and the derivation-conformance
  // suite). It lives in core/src beside the composer but is TOOLING: it is not
  // exposed from the `re-frame.core` facade and no production-reachable ns
  // `:require`s it. A leak most likely means a stray `:require` from a
  // production-reachable ns, or an accidental re-export from the core facade.
  {
    name: 'derivation-egress',
    forbiddenSources: ['re_frame/derivation/egress.cljc'],
  },

  // Story (tools/story/). The tools/story/ jar must stay out of any production
  // bundle that `:require`s no re-frame.story.* namespace, per the
  // tools/ ↛ implementation/ bundle-isolation contract in tools/README.md.
  {
    name: 'story',
    forbiddenSources: ['re_frame/story.cljc', 're_frame/story/'],
  },

  // The dev-only third-party libraries machines-viz and the Xray EDN widget
  // pull in. Each is consumed only by tools/ and gated behind Xray's
  // `:devtools/preloads`, so one in a production bundle means a `:require`
  // slipped from a tools/* ns into a production-reachable one
  // (tools/README.md). The manifest names npm sources under `node_modules/`
  // and the two Maven libraries under their own namespace roots.
  //
  // xyflow (`@xyflow/react` and its `@xyflow/system` engine): the Machines
  // panel's render engine, Path B per spec/021 §6.0 + §17.4. A host that
  // doesn't install Xray should never pay for its ~50-80KB gzipped.
  {
    name: 'xyflow',
    forbiddenSources: ['node_modules/@xyflow/'],
  },

  // elkjs: xyflow's layout engine inside the MachineChart, ~1MB minified.
  {
    name: 'elkjs',
    forbiddenSources: ['node_modules/elkjs/'],
  },

  // zprint: the pretty-printer the Xray EDN widget's `code-block` uses to
  // pre-format handler-source strings; with its rewrite-clj dep it weighs
  // hundreds of kilobytes.
  {
    name: 'zprint',
    forbiddenSources: ['zprint/'],
  },

  // editscript: Xray's diff engine, ~25KB of source over several cljc files
  // plus a priority-map dep.
  {
    name: 'editscript',
    forbiddenSources: ['editscript/'],
  },
];

// ----- the elision claims' positive control ---------------------------------

// An elision claim proves its sentinels ABSENT from the counter bundle, and on
// its own that is half a contract: a grep for a literal that no longer exists
// ANYWHERE passes vacuously. Rename the literal in the artefact without
// updating the sentinel, and the entry stays green whether or not the bodies
// leak — exactly where it is supposed to bite.
//
// So each elision claim names one module of the dedicated
// :bundle-isolation-positive-control release, whose focused entrypoint reaches
// the bodies, and every sentinel must be PRESENT in that exact emitted module
// file. That release is the counter's :advanced compile with goog.DEBUG=true:
// the bodies sit behind `rf.interop/debug-enabled?`, so it is the ON half of
// the claim, the gate open, where the counter's goog.DEBUG=false release is
// the OFF half. Each module stands alone, because Closure hoists a constant
// several modules share into their common base module, where it would satisfy
// a claim its own body never emits. Comments, docstrings, JVM-only reader
// branches and DCE'd CLJS forms cannot satisfy it either, because the check
// reads emitted JavaScript only.
//
// assertPositiveControlComplete() requires every elision claim to appear here
// and nothing else to, so none can ship negative-only.
const POSITIVE_CONTROL = {
  'trace-tooling': { onModule: 'trace-tooling' },
  'epoch': { onModule: 'epoch' },
};

// The graph claims' on-control, taken from the target: every app loads
// re-frame.core, so a manifest that does not list it is not the counter
// build's, and no absence read from it means anything.
const MANIFEST_ON_CONTROL = 're_frame/core.cljc';

// ----- helpers ---------------------------------------------------------------

// Bundle reading + grep primitives (escapeRe / countSubstring / countMatches)
// and the manifest reader are shared with the sibling check-* scripts via
// scripts/lib/read-release-bundle.cjs. The bundle reader returns only
// top-level *.js — the release artefact — so a stale dev-build `cljs-runtime/`
// subdir from a prior `shadow-cljs compile` doesn't get grep-ed alongside.

// The emitted-bytes half of one artefact: its elision sentinels and its
// allow-list budget, against the counter bundle. A graph claim carries no
// sentinels, so for it this checks the budget alone.
function checkArtefact(blob, artefact) {
  const sentinels = artefact.internalSentinels || [];
  let internalOk = true;
  let internalFailures = 0;
  for (const { source, sentinel } of sentinels) {
    const hits = countSubstring(blob, sentinel);
    const ok   = hits === 0;
    const tag  = ok ? 'OK' : 'FAIL';
    report.detail(`    [${tag}] ${source}: sentinel ${JSON.stringify(sentinel)} ` +
                  `expected 0, was ${hits}`);
    if (!ok) {
      internalOk = false;
      internalFailures += 1;
    }
  }

  let allowListOk = true;
  let allowListHits = 0;
  const allowListChecked = artefact.consumerAllowList ? 1 : 0;
  if (artefact.consumerAllowList) {
    allowListHits = countMatches(blob, artefact.consumerAllowList);
    allowListOk   = allowListHits <= artefact.expectedAllowListHits;
    const tag     = allowListOk ? 'OK' : 'FAIL';
    report.detail(`    [${tag}] consumer allow-list ${artefact.consumerAllowList}: ` +
                  `${allowListHits} hit(s), expected <= ${artefact.expectedAllowListHits}`);
  }

  return {
    ok: internalOk && allowListOk,
    internalOk,
    allowListOk,
    allowListHits,
    internalChecked: sentinels.length,
    internalFailures,
    allowListChecked,
  };
}

// True iff manifest source `source` is what `forbidden` names: that file, or
// anything under that directory. Whole segments only, so `re_frame/flows/`
// does not catch core's own `re_frame/core_flows.cljc`.
function sourceMatches(source, forbidden) {
  return forbidden.endsWith('/') ? source.startsWith(forbidden) : source === forbidden;
}

// The graph half of one artefact: every manifest source its forbidden paths
// match. `sources` is a build manifest's `:sources`, across all its modules.
function checkGraphClaim(sources, artefact) {
  const leaked = [];
  for (const forbidden of artefact.forbiddenSources) {
    const hits = sources.filter((source) => sourceMatches(source, forbidden));
    const tag = hits.length === 0 ? 'OK' : 'FAIL';
    report.detail(`    [${tag}] manifest source ${JSON.stringify(forbidden)}: ` +
                  `expected 0, was ${hits.length}`);
    leaked.push(...hits);
  }
  return { ok: leaked.length === 0, leaked, checked: artefact.forbiddenSources.length };
}

// ----- positive control ------------------------------------------------------

// Lazy cache of the positive-control release's emitted modules, keyed by
// module name.
function makePositiveContext() {
  const moduleBlobs = new Map();
  return {
    onModule(moduleName) {
      if (!moduleBlobs.has(moduleName)) {
        const file = path.join(ROOT, 'out', 'bundle-isolation-positive-control', `${moduleName}.js`);
        let blob = '';
        let status = 'missing';
        try {
          blob = fs.readFileSync(file, 'utf8');
          status = blob.length === 0 ? 'empty' : 'ok';
        } catch (_e) {
          // Missing module: the presence assertion below fails loud.
        }
        moduleBlobs.set(moduleName, { status, blob, file });
      }
      return moduleBlobs.get(moduleName);
    },
  };
}

// Run the positive control for one elision claim. Appends per-sentinel lines to
// the report and returns { ok, checked, passed, ... }.
function checkPositiveControl(ctx, artefact) {
  const moduleName = POSITIVE_CONTROL[artefact.name].onModule;
  const sentinels = artefact.internalSentinels;
  const { status, blob, file } = ctx.onModule(moduleName);
  if (status !== 'ok') {
    report.detail(`    [FAIL] emitted module '${moduleName}' positive control: ${status} — ` +
                  'cannot prove sentinels present (would be vacuous)');
    return { ok: false, checked: sentinels.length, passed: 0, vacuous: true, module: moduleName, file };
  }
  const { ok, passed } = assertSentinelSet(blob, sentinels, {
    mustContain: true,
    count: true,
    emit: (line) => report.detail(line),
    formatLine: ({ source, sentinel, hits, tag }) =>
      `    [${tag}] emitted module '${moduleName}' present: ${source}: sentinel ` +
      `${JSON.stringify(sentinel)} expected >= 1, was ${hits}`,
  });
  return { ok, checked: sentinels.length, passed, module: moduleName, file };
}

// A forbidden source is a relative classpath path: segments of path-safe
// characters, ending in `/` when it names a directory. Anything else — an
// absolute path, a `..`, an empty segment, a bare `/` — resolves against the
// wrong root or matches nothing, so it is refused before it can pass.
function isWellFormedSource(source) {
  return typeof source === 'string' &&
    /^[A-Za-z0-9_@.+-]+(\/[A-Za-z0-9_@.+-]+)*\/?$/.test(source) &&
    !source.split('/').some((segment) => segment === '.' || segment === '..');
}

// The keys claimed by more than one owner, from `[key, owner]` pairs.
function sharedOwners(pairs) {
  const owners = new Map();
  for (const [key, owner] of pairs) owners.set(key, [...(owners.get(key) || []), owner]);
  return [...owners.entries()]
    .filter(([_key, names]) => names.length !== 1)
    .map(([key, names]) => ({ key, owners: names }));
}

// Startup completeness, fail-closed: every artefact declares exactly one
// well-formed claim, every elision claim has a positive control and nothing
// else does, and no emitted module, sentinel or forbidden source has two
// owners.
function assertPositiveControlComplete(artefacts = ARTEFACTS, controls = POSITIVE_CONTROL) {
  const isGraph = (a) => Array.isArray(a.forbiddenSources) && a.forbiddenSources.length > 0;
  const isElision = (a) => Array.isArray(a.internalSentinels) && a.internalSentinels.length > 0;
  const unclaimed = artefacts.filter((a) => isGraph(a) === isElision(a)).map((a) => a.name);
  const graph = artefacts.filter((a) => isGraph(a) && !isElision(a));
  const elision = artefacts.filter((a) => isElision(a) && !isGraph(a));
  const missing = elision.filter((a) => !controls[a.name]).map((a) => a.name);
  const extra = Object.keys(controls).filter((name) => !elision.some((a) => a.name === name));
  const malformed = Object.entries(controls)
    .filter(([_name, pc]) => !pc || typeof pc.onModule !== 'string' || pc.onModule === '')
    .map(([name]) => name);
  const malformedSources = graph.flatMap((a) => a.forbiddenSources
    .filter((source) => !isWellFormedSource(source))
    .map((source) => ({ name: a.name, source })));
  const sharedModules = sharedOwners(Object.entries(controls)
    .filter(([_name, pc]) => pc && pc.onModule)
    .map(([name, pc]) => [pc.onModule, name]))
    .map(({ key, owners }) => ({ moduleName: key, owners }));
  const sharedSentinels = sharedOwners(elision.flatMap((a) =>
    a.internalSentinels.map(({ sentinel }) => [sentinel, a.name])))
    .map(({ key, owners }) => ({ sentinel: key, owners }));
  const sharedSources = sharedOwners(graph.flatMap((a) =>
    a.forbiddenSources.map((source) => [source, a.name])))
    .map(({ key, owners }) => ({ source: key, owners }));
  const lists = { missing, extra, malformed, unclaimed, malformedSources,
    sharedModules, sharedSentinels, sharedSources };
  return { ...lists, ok: Object.values(lists).every((list) => list.length === 0) };
}

// ----- forbidden sources resolve on the classpath ----------------------------

// A graph claim naming a source that does not exist can never match, so it
// passes forever while guarding nothing — the graph-claim form of a renamed
// sentinel. So every forbidden source must resolve on the classpath the
// counter build compiles against, read from shadow-cljs.edn: under a
// `:source-paths` root; under the one node_modules this repo installs (the
// manifest names npm sources `node_modules/…`, relative to the project); or
// inside a `:dependencies` jar in the local Maven repository, where
// shadow-cljs resolves them.
const MAVEN_REPOSITORY = path.join(os.homedir(), '.m2', 'repository');

function readBuildClasspath(root = ROOT, mavenRepository = MAVEN_REPOSITORY) {
  const config = readEdn(fs.readFileSync(path.join(root, 'shadow-cljs.edn'), 'utf8'));
  const vectorAt = (key) => {
    const value = isMap(config) ? mapGetKeyword(config, key) : undefined;
    if (!value || value.edn !== 'vector') throw new EdnReadError(`shadow-cljs.edn has no :${key} vector`);
    return value.items;
  };
  const dirs = vectorAt('source-paths').map((entry) => {
    if (!entry || entry.edn !== 'string') throw new EdnReadError('a :source-paths entry is not a string');
    return path.resolve(root, entry.value);
  });
  const jars = vectorAt('dependencies').map((dep) => {
    const [lib, version] = dep && dep.edn === 'vector' ? dep.items : [];
    if (!lib || lib.edn !== 'symbol' || !version || version.edn !== 'string') {
      throw new EdnReadError('a :dependencies entry is not [lib "version" ...]');
    }
    const [group, artifact] = lib.name.includes('/') ? lib.name.split('/') : [lib.name, lib.name];
    return {
      lib: `${lib.name} ${version.value}`,
      file: path.join(mavenRepository, ...group.split('.'), artifact, version.value,
        `${artifact}-${version.value}.jar`),
    };
  });
  return { root, dirs, jars };
}

// A jar's entry names, read from the zip central directory; null when the file
// does not exist. Anything that is not a zip it can read throws, rather than
// reading as a jar that holds nothing.
function listJarEntries(file) {
  let buf;
  try {
    buf = fs.readFileSync(file);
  } catch (e) {
    if (e.code === 'ENOENT') return null;
    throw e;
  }
  // The end-of-central-directory record is the last 22 bytes, before an
  // archive comment of at most 64 KiB.
  let eocd = -1;
  for (let i = buf.length - 22; i >= Math.max(0, buf.length - 22 - 0xffff); i -= 1) {
    if (buf.readUInt32LE(i) === 0x06054b50) {
      eocd = i;
      break;
    }
  }
  if (eocd < 0) throw new Error(`not a zip archive: ${file}`);
  const count = buf.readUInt16LE(eocd + 10);
  let offset = buf.readUInt32LE(eocd + 16);
  const names = [];
  for (let n = 0; n < count; n += 1) {
    if (offset + 46 > buf.length || buf.readUInt32LE(offset) !== 0x02014b50) {
      throw new Error(`unreadable zip central directory: ${file}`);
    }
    const nameLength = buf.readUInt16LE(offset + 28);
    names.push(buf.toString('utf8', offset + 46, offset + 46 + nameLength));
    offset += 46 + nameLength + buf.readUInt16LE(offset + 30) + buf.readUInt16LE(offset + 32);
  }
  return names;
}

// Where `source` resolves on `classpath` — a label for the root it was found
// under — or null. A directory path must name a directory and a file path a
// file, so `re_frame/flows/` cannot pass on the strength of `flows.cljc`.
function resolveForbiddenSource(source, classpath, jarEntries = listJarEntries) {
  const isDir = source.endsWith('/');
  const exists = (p) => {
    try {
      const stat = fs.statSync(p);
      return isDir ? stat.isDirectory() : stat.isFile();
    } catch (_e) {
      return false;
    }
  };
  if (source.startsWith('node_modules/')) {
    return exists(path.join(classpath.root, source)) ? 'node_modules' : null;
  }
  for (const dir of classpath.dirs) {
    if (exists(path.join(dir, source))) return path.relative(classpath.root, dir).replace(/\\/g, '/');
  }
  for (const jar of classpath.jars) {
    const entries = jarEntries(jar.file);
    if (entries && entries.some((entry) => (isDir ? entry.startsWith(source) : entry === source))) {
      return jar.lib;
    }
  }
  return null;
}

// Every graph claim's forbidden sources resolve. Returns
// { ok, resolved, dead: [{ name, source }], absentJars, error }; a classpath or
// jar that cannot be read fails closed, with `error` saying which.
function assertForbiddenSourcesLive(artefacts = ARTEFACTS, readClasspath = readBuildClasspath) {
  const dead = [];
  const absentJars = new Set();
  let resolved = 0;
  try {
    const classpath = readClasspath();
    const jarCache = new Map();
    const jarEntries = (file) => {
      if (!jarCache.has(file)) jarCache.set(file, listJarEntries(file));
      if (jarCache.get(file) === null) absentJars.add(file);
      return jarCache.get(file);
    };
    for (const artefact of artefacts) {
      for (const source of artefact.forbiddenSources || []) {
        if (resolveForbiddenSource(source, classpath, jarEntries)) {
          resolved += 1;
        } else {
          dead.push({ name: artefact.name, source });
        }
      }
    }
  } catch (e) {
    return { ok: false, resolved, dead, absentJars: [...absentJars], error: e.message };
  }
  return { ok: dead.length === 0, resolved, dead, absentJars: [...absentJars], error: null };
}

// ----- canonical publishable-runtime coverage --------------------------------

// assertPositiveControlComplete only cross-checks this script's own two local
// tables (ARTEFACTS <-> POSITIVE_CONTROL): an artefact absent from BOTH is
// defined away, not detected — a separately published, browser-reachable
// optional runtime omitted from every internal table would stay green. This
// check closes that hole by
// deriving the REQUIRED set STRUCTURALLY from the real publishable surface: the
// shared EDN-aware authority (scripts/lib/publishable-runtimes.cjs) that reads
// each deps.edn's real `:aliases/:clein/build` KEY — the SAME parsed fact the
// release lockstep (.github/scripts/verify-version-lockstep.sh) consumes. Each
// browser-optional runtime among them MUST map — BY EXACT implementation-
// relative PATH — either to a generic ARTEFACTS entry (whose `relPath` equals
// the runtime's path) OR to a dedicated isolation gate whose descriptor
// VALIDATES (real checker file + invoked package command). A runtime mapped to
// NEITHER fails automatically (FAIL-CLOSED). The check does NOT compare two
// copies of its own local table.
//
// The enrolment is causal — the fail-closed claim cannot be discharged by text
// or names:
//   - Discovery is EDN-structural (via publishable-runtimes.cjs): a genuine
//     `:clein/build` survives `;` inside strings (so a real runtime is never
//     silently omitted), and a token inside a string / comment / `#_` discard is
//     NOT invented as an alias.
//   - Coverage is keyed by EXACT relative path, so a future
//     implementation/adapters/schemas can NOT inherit the flat
//     implementation/schemas generic gate via a colliding directory leaf.
//   - Dedicated coverage is a MACHINE-READABLE descriptor validated against real
//     wiring (validateDedicatedGate), so a fictional prose entry, a removed
//     checker, or an uninvoked command can NOT enrol a runtime.
//
// Discovery CONSUMES the shared authority's listPublishableRuntimes directly
// rather than maintaining a second, narrower traversal: the
// authority's bounded flat-plus-nested walk is the SAME inventory the release
// lockstep enrols, so a publishable runtime nested OUTSIDE adapters/ cannot
// reach release inventory while escaping bundle coverage. Bundle
// isolation then applies only its explicit browser/JVM exclusions below.
//
// Excluded from the REQUIRED set (published, but NOT a browser-optional client
// runtime — each with reason):
//   core     — always present (the lockstep root; every app loads it, so it is
//              never isolated from the counter bundle).
//   ssr-ring — JVM-only ring server adapter (all .clj; no CLJS runtime body can
//              reach a production client bundle).
// Keyed by exact implementation-relative PATH (both entries are flat, so path
// == leaf) so the exclusion is applied against the authority's relPath.
const NON_BROWSER_OPTIONAL = new Set(['core', 'ssr-ring']);

// Browser-optional runtimes covered by a REAL dedicated isolation gate rather
// than a generic ARTEFACTS entry. KEYED BY EXACT implementation-relative PATH
// (never leaf name) so a future implementation/adapters/<x> can not inherit a
// flat implementation/<x> generic gate through a colliding directory leaf. Each
// value is a MACHINE-READABLE descriptor: `checkers` are the real gate-script
// filenames under scripts/, and `command` is the package.json script that
// invokes them. validateDedicatedGate() binds it to the EXACT runtime AND the
// EXACT executable:
//   - every checker is a REGULAR FILE under scripts/;
//   - `command` is a real package script whose body RUNS each checker as a
//     directly-invoked, reachable `node scripts/<checker>` step — the checker
//     being the script operand Node ACTUALLY executes, immediately after `node`
//     (echo, comment, argument-only, name-substring, wrong-directory same-name,
//     `--require`/`-r` preload and other pre-script option positions, and
//     unreachable `false &&` mentions do NOT count as running it); and
//   - the runtime being covered (the map KEY / relPath) is OWNED by one of the
//     checkers — it appears in that checker's exported COVERS_RUNTIMES — so an
//     unrelated existing checker can NOT be reused for a new runtime it never
//     inspects.
// A truthy prose string, a removed checker, an uninvoked command, or a checker
// that does not declare the runtime all fail closed. Keep this to the set of
// EXISTING dedicated gates only — do not add a runtime here without a real,
// invoked gate script that declares (in COVERS_RUNTIMES) it isolates that path.
const DEDICATED_ISOLATION_GATES = {
  'adapters/reagent': {
    checkers: ['check-uix-reagent-free.cjs', 'check-login-bundle-isolation.cjs'],
    command: 'test:bundle-isolation',
    note: 'reagent is the counter/login reference substrate; its fingerprints are ' +
      'the positive control both gates assert present, and absent from uix',
  },
  'adapters/uix': {
    checkers: ['check-uix-reagent-free.cjs', 'check-login-bundle-isolation.cjs'],
    command: 'test:bundle-isolation',
    note: 'uix bundles must be reagent-free',
  },
};

// Package.json scripts map, read once. Used to prove a dedicated gate's declared
// command is a REAL invoked package script (not just a name in prose).
function readPackageScripts(root = ROOT) {
  try {
    const pkg = JSON.parse(fs.readFileSync(path.join(root, 'package.json'), 'utf8'));
    return pkg.scripts || {};
  } catch (_e) {
    return {};
  }
}

// Normalise a script operand as WRITTEN in a package script (whose cwd is the
// package root) to a comparable posix-relative path, so `scripts/x.cjs`,
// `./scripts/x.cjs` and `scripts\x.cjs` are one path and not three.
function normaliseScriptOperand(operand) {
  return path.posix.normalize(String(operand).replace(/\\/g, '/'));
}

// True iff `body` (a package.json script body, in this repo's bounded
// `&&`-chained grammar) RUNS `checker` as a DIRECTLY-INVOKED, REACHABLE step:
// a `node <path>` command whose script operand is EXACTLY the checker's
// package-root-relative path (`scripts/<checker>`). Substring / echo / comment /
// argument-only mentions, and any step guarded behind a statically-failing
// `false &&` short-circuit, do NOT count — so a checker's mere textual presence
// in the body can not launder coverage.
//
// Two identity rules make the operand the ACTUAL executed script:
//
//   - POSITION. Node's script operand is the token IMMEDIATELY after `node`.
//     Matching "the first token that does not start with `-`" instead is wrong
//     for Node's real CLI grammar, because several options CONSUME the next
//     token (`--require <preload>`, `-r <preload>`, `--import <x>`, …): in
//     `node --require scripts/<checker> other.cjs` the checker is a PRELOAD,
//     whose `require.main === module` guard deliberately runs no bundle work,
//     and `other.cjs` is what Node executes. Rather than carry a Node option
//     table (this is not a Node CLI parser), any `node` step with a pre-script
//     option is rejected outright: the repo writes its gates as plain
//     `node scripts/<checker>`, and an unrecognised shape fails CLOSED.
//
//   - PATH. The operand is compared as a whole normalised path, not by
//     `basename`, so `node elsewhere/<checker>` — a different file that merely
//     shares the descriptor's filename — does not bind coverage.
//
// This stays a BOUNDED recogniser (split on `&&`, drop trailing `#` comments,
// tokenise on whitespace), not a general shell or Node parser: it recognises
// exactly the `program arg…` steps the repo's isolation scripts are written in.
function commandRunsChecker(body, checker, { scriptsDir = SCRIPTS_DIR } = {}) {
  // The package root is the scripts dir's parent, so the operand a package
  // script must name is `scripts/<checker>` relative to it.
  const expected = normaliseScriptOperand(
    path.relative(path.dirname(scriptsDir), path.join(scriptsDir, checker)));
  for (const rawStep of String(body).split('&&')) {
    const step = rawStep.split('#')[0].trim();  // drop trailing shell comment
    if (step === '') continue;
    const tokens = step.split(/\s+/);
    // A literal `false` step always fails, so nothing AFTER it in the `&&` chain
    // runs — a checker sitting behind it is unreachable.
    if (tokens[0] === 'false') break;
    if (tokens[0] !== 'node') continue;
    const scriptArg = tokens[1];
    // A pre-script option (recognised or not) may consume the operand after it;
    // this step's executed script is not statically known, so it counts for
    // nothing.
    if (!scriptArg || scriptArg.startsWith('-')) continue;
    if (normaliseScriptOperand(scriptArg) === expected) return true;
  }
  return false;
}

// True iff `p` is a REGULAR file. Descriptors name checker SCRIPTS, so mere path
// existence is too weak — a directory (or any non-file entry) sharing a
// checker's name must not discharge enrolment.
function isRegularFile(p) {
  try {
    return fs.statSync(p).isFile();
  } catch (_e) {
    return false;
  }
}

// The implementation-relative runtimes a checker OWNS (its exported
// COVERS_RUNTIMES), or null if it declares none / can't be loaded. The checker
// is the source of truth for what it isolates, so a descriptor can not bind a
// checker to a runtime the checker never inspects. `require` is safe
// because the checkers guard their `main()` behind `require.main === module`, so
// loading one for its contract runs no bundle work.
function checkerCoversRuntimes(scriptsDir, checker) {
  let mod;
  try {
    mod = require(path.join(scriptsDir, checker));
  } catch (_e) {
    return null;
  }
  return mod && Array.isArray(mod.COVERS_RUNTIMES) ? mod.COVERS_RUNTIMES : null;
}

// A dedicated-gate descriptor is CAUSAL only when it is wired to real, invoked
// machinery AND bound to the exact runtime it claims to isolate. Returns
// `{ ok, reasons }`: ok iff `gate` is a `{ checkers: [...], command }` map whose
// every checker file EXISTS in scriptsDir, whose command is a package.json
// script whose body RUNS every checker as a directly-invoked reachable step,
// AND — when a `relPath` is supplied (the runtime it is mapped under) — one of
// its checkers OWNS that runtime in its COVERS_RUNTIMES. A truthy prose string,
// a nonexistent checker, an uninvoked/echoed/unreachable command, or an
// unrelated checker reused for a runtime it does not inspect all fail — so
// enrolment can not be discharged by editing this table.
function validateDedicatedGate(gate, { scriptsDir = SCRIPTS_DIR, scripts = readPackageScripts(), relPath = null } = {}) {
  if (!gate || typeof gate !== 'object' || Array.isArray(gate)) {
    return { ok: false, reasons: ['descriptor is not a { checkers, command } map (a prose string does not enrol a runtime)'] };
  }
  const reasons = [];
  const checkers = Array.isArray(gate.checkers) ? gate.checkers : [];
  const command = typeof gate.command === 'string' ? gate.command : null;
  if (checkers.length === 0) reasons.push('no checker script(s) declared');
  for (const checker of checkers) {
    if (!isRegularFile(path.join(scriptsDir, checker))) {
      reasons.push(`checker script is not a regular file: ${checker}`);
    }
  }
  if (!command) {
    reasons.push('no package/CI command declared');
  } else {
    const body = scripts[command];
    if (body === undefined) {
      reasons.push(`declared command is not an invoked package.json script: ${command}`);
    } else {
      for (const checker of checkers) {
        if (!commandRunsChecker(body, checker, { scriptsDir })) {
          reasons.push(`package command '${command}' does not RUN checker '${checker}' ` +
            'as a directly-invoked reachable step (echo / comment / argument-only / ' +
            'name-substring / wrong-directory / pre-script-option (e.g. --require ' +
            'preload) / unreachable false-and mentions do not count)');
        }
      }
    }
  }
  // Runtime binding: the descriptor is bound to the EXACT runtime it is mapped
  // under only when a checker declares (owns) that runtime. This is what stops
  // the login checker being assigned to a hypothetical adapters/newpub.
  if (relPath) {
    const owned = checkers.some((checker) => {
      const covers = checkerCoversRuntimes(scriptsDir, checker);
      return Array.isArray(covers) && covers.includes(relPath);
    });
    if (!owned) {
      reasons.push(`no declared checker OWNS the exact runtime '${relPath}' ` +
        '(a checker must list it in COVERS_RUNTIMES; reusing an unrelated checker does not bind coverage)');
    }
  }
  return { ok: reasons.length === 0, reasons };
}

// Discover every publishable browser-optional runtime under implementation/ by
// CONSUMING the shared authority's listPublishableRuntimes — the
// SAME parsed `:aliases/:clein/build` inventory, over the SAME bounded
// flat-plus-nested reach, that the release lockstep enrols — then dropping the
// runtimes that are not browser-optional client bundles (the always-present
// core; the JVM-only servers in NON_BROWSER_OPTIONAL). There is no second
// traversal to drift from the lockstep: a publishable runtime nested outside
// adapters/ is enrolled here exactly as it is in release inventory. Read faults
// propagate (the authority throws, fail-closed) rather than yielding a
// silently-shrunken set. Returns `{ name, relPath }` records; `name` is the
// directory leaf and `relPath` the implementation/-relative path — coverage
// keys on `relPath` so leaf collisions can't launder a runtime through the
// wrong gate.
function discoverBrowserOptionalRuntimes(root = ROOT) {
  return listPublishableRuntimes(root)
    .filter((rt) => !NON_BROWSER_OPTIONAL.has(rt.relPath))
    .map((rt) => ({ name: path.basename(rt.relPath), relPath: rt.relPath }));
}

// The exact implementation-relative PATHS a generic ARTEFACTS entry proves
// isolated — the per-feature publishable runtimes, each declaring `relPath`.
// Keyed by path, never leaf name: a generic entry covers exactly the path it
// declares, so a nested adapter with a colliding leaf is not silently absorbed.
function genericCoveragePaths(artefacts = ARTEFACTS) {
  return new Set(artefacts.filter((a) => a.relPath).map((a) => a.relPath));
}

// Every discovered browser-optional publishable runtime must map — BY EXACT
// implementation-relative PATH — to a real isolation gate: a generic ARTEFACTS
// entry whose `relPath` equals the runtime's path (per-feature runtimes, which
// must be ABSENT from the no-feature counter bundle) OR a dedicated gate whose
// descriptor VALIDATES (real checker file + invoked package command). A runtime
// mapped to NEITHER means a separately published optional runtime could leak
// into a production bundle with nothing to catch it — so the gate fails closed
// and names it (with the reason its would-be gate was rejected).
function assertCanonicalInventoryCovered(required = discoverBrowserOptionalRuntimes(), opts = {}) {
  const {
    dedicatedGates = DEDICATED_ISOLATION_GATES,
    scriptsDir = SCRIPTS_DIR,
    scripts = readPackageScripts(),
  } = opts;
  const generic = genericCoveragePaths();
  const covered = [];
  const missing = [];
  for (const rt of required) {
    if (generic.has(rt.relPath)) {
      covered.push({ ...rt, via: 'generic' });
      continue;
    }
    const gate = dedicatedGates[rt.relPath];
    const validation = validateDedicatedGate(gate, { scriptsDir, scripts, relPath: rt.relPath });
    if (gate && validation.ok) {
      covered.push({ ...rt, via: 'dedicated', gate });
    } else {
      missing.push({
        ...rt,
        reasons: gate ? validation.reasons : ['no isolation gate mapped for this exact path'],
      });
    }
  }
  return {
    required,
    covered,
    missing,
    genericCount: covered.filter((c) => c.via === 'generic').length,
    dedicatedCount: covered.filter((c) => c.via === 'dedicated').length,
    ok: missing.length === 0,
  };
}

// ----- example ns-load co-load isolation -------------------------------------

// Every check above this line asks whether an artefact's body reached a bundle
// it should be absent from. This one asks the ns-LOAD question underneath that,
// and it is the question the consolidated `:node-test` bundle structurally
// cannot answer about itself: does each example app load on its OWN, or only
// because a SIBLING app happened to be co-loaded beside it?
//
// THE DEFECT THIS EXISTS FOR. An example that calls `rf/reg-machine` while
// requiring neither `re-frame.machines` nor anything pulling it in only works
// by accident. Machines are an OPTIONAL artefact whose façade export
// resolves through the late-bind hook table, so the call succeeds exactly when
// some other namespace has already loaded the artefact. In the consolidated
// bundle one always has. Alone, the example's ns-load throws
// `:rf.error/machines-artefact-missing` before a single test runs. Every other
// gate stays GREEN across that defect, and would go green again the moment
// somebody deleted the require that fixes it. A single-app build per example
// would witness it, and is far too expensive to keep.
//
// WHY THIS IS A SOURCE CHECK AND NOT AN EMITTED-ARTEFACT ONE, since the roster
// comment above rightly insists on the emitted module for every claim it makes:
// the claim here is not about a bundle's CONTENTS, it is about an ns FORM. A
// missing `:require` is invisible in any bundle that contains the artefact for
// some other reason, which is precisely the co-load being ruled out — so the
// only artefact that could witness it is a single-app build, and that is the
// cost this check avoids. The ns form is the honest subject, so the ns form is
// what is read.
//
// THE RULE. An example that CALLS an optional artefact's registration façade
// must `:require` that artefact in its OWN ns form. Load-time hook registration
// is what the `:require` buys — not the Maven dep, and not a sibling's require
// (`skills/re-frame-migration/references/auto-cross-cutting.md` states the same
// rule for migrating apps: "Add the dep AND the `:require` in every namespace
// that uses the surface").
//
// REGISTRATION is the whole of the rule, and `defmachine` is the row that is
// deliberately ABSENT for that reason. `(rf/defmachine m {…})` is re-frame.core's
// own `def`-replacement: `expand-defmachine` walks the literal spec at expansion
// time and emits `(def m <stamped-spec>)` — nothing else. Its own docstring says
// so ("`def` is not a registration"), and the sibling expander makes the contrast
// exact: `expand-reg-machine` emits a real `(re-frame.core-machines/reg-machine
// …)` call, which is what needs the artefact's load-time hooks; `expand-defmachine`
// emits no call at all. So a namespace may legitimately DEFINE machine values
// while requiring only `re-frame.core`, and require `re-frame.machines` solely in
// the boot namespace that REGISTERS them — a split this roster must not forbid.
// Listing `defmachine` here would reject that shape and force a pointless
// side-effecting require into the definition namespace. The sibling self-test
// pins BOTH halves of the distinction (a defmachine-only ns passes; a
// `reg-machine` call in that same ns still fails, and the violation must name
// `reg-machine` alone), so adding the row fails the gate by name.
const OPTIONAL_ARTEFACT_FACADES = [
  { call: 'reg-machine',    artefact: 're-frame.machines',  absentError: 'rf.error/machines-artefact-missing' },
  { call: 'reg-route',      artefact: 're-frame.routing',   absentError: 'rf.error/routing-artefact-missing' },
  { call: 'reg-resource',   artefact: 're-frame.resources', absentError: 'rf.error/resources-artefact-missing' },
  { call: 'reg-mutation',   artefact: 're-frame.resources', absentError: 'rf.error/resources-artefact-missing' },
  { call: 'reg-flow',       artefact: 're-frame.flows',     absentError: 'rf.error/flows-artefact-missing' },
  { call: 'reg-app-schema', artefact: 're-frame.schemas',   absentError: 'rf.error/schemas-artefact-missing' },
];

const EXAMPLES_DIR = path.resolve(ROOT, '..', 'examples');

// Reduce a source file to the text that is CODE: line comments removed, string
// bodies blanked (delimiters and newlines kept, so paren balance and line
// structure survive). Both halves are load-bearing:
//
//   - a `;` inside a string does not start a comment, and `\;` is a character
//     literal, so a naive line strip mangles real code;
//   - a façade named in a comment or a docstring is PROSE, not a call.
//     examples/capabilities/resources/resources/core.cljs names `rf/reg-machine`
//     in a comment in its ns form as well as calling it, and blanking string
//     bodies is what stops `(def doc "call (rf/reg-flow …)")` from demanding a require.
//
// Blanking rather than deleting keeps the ns-form reader's paren balance intact
// through a docstring containing an unbalanced bracket.
function stripCommentsAndStringBodies(source) {
  let out = '';
  let inString = false;
  for (let i = 0; i < source.length; i += 1) {
    const ch = source[i];
    if (inString) {
      if (ch === '\\' && i + 1 < source.length) {
        out += source[i + 1] === '\n' ? '  \n' : '  ';
        i += 1;
        continue;
      }
      if (ch === '"') { out += ch; inString = false; continue; }
      out += ch === '\n' ? '\n' : ' ';
      continue;
    }
    if (ch === '"') { inString = true; out += ch; continue; }
    if (ch === '\\') {
      // character literal, e.g. \; or \" — copy it whole so it can neither open
      // a string nor start a comment.
      out += ch;
      if (i + 1 < source.length) { out += source[i + 1]; i += 1; }
      continue;
    }
    if (ch === ';') {
      while (i < source.length && source[i] !== '\n') i += 1;
      out += '\n';
      continue;
    }
    out += ch;
  }
  return out;
}

// The `(ns …)` form, read by paren balance over comment-stripped source (the
// form spans many lines and carries `;` comments between requires, so neither a
// line-oriented read nor a raw brace count survives it).
function nsForm(strippedSource) {
  const start = strippedSource.indexOf('(ns ');
  if (start === -1) return null;
  let depth = 0;
  let inString = false;
  for (let i = start; i < strippedSource.length; i += 1) {
    const ch = strippedSource[i];
    if (inString) {
      if (ch === '\\') { i += 1; } else if (ch === '"') { inString = false; }
      continue;
    }
    if (ch === '"') { inString = true; continue; }
    if (ch === '\\') { i += 1; continue; }
    if (ch === '(' || ch === '[' || ch === '{') depth += 1;
    else if (ch === ')' || ch === ']' || ch === '}') {
      depth -= 1;
      if (depth === 0) return strippedSource.slice(start, i + 1);
    }
  }
  return null;
}

// Namespaces this file requires ITSELF, in any of the shapes the tree uses:
// `[re-frame.machines]`, `[re-frame.routing :as r]`, or a bare symbol.
function requiredNamespaces(nsFormText) {
  if (!nsFormText) return new Set();
  const found = new Set();
  for (const m of nsFormText.matchAll(/[[\s]([a-z][a-z0-9.*+!_'?<>=-]*\.[a-z][a-z0-9.*+!_'?<>=-]*)/g)) {
    found.add(m[1]);
  }
  return found;
}

// Call sites of `<call>` in the file BODY (outside the ns form), as an actual
// call — a `(` immediately before the symbol, optionally alias-qualified. Prose
// naming the symbol in backticks is not a call and does not count.
function facadeCallSites(bodyText, call) {
  const escaped = call.replace(/[.*+?^${}()|[\]\\]/g, '\\$&');
  const re = new RegExp(`\\((?:[A-Za-z0-9.*+!_'?<>=-]+/)?${escaped}(?=[\\s()\\[\\]{}])`, 'g');
  return (bodyText.match(re) || []).length;
}

function listExampleSources(dir, acc = []) {
  let entries;
  try {
    entries = fs.readdirSync(dir, { withFileTypes: true });
  } catch (_e) {
    return acc;
  }
  for (const entry of entries) {
    const full = path.join(dir, entry.name);
    if (entry.isDirectory()) {
      if (entry.name === 'node_modules' || entry.name === 'out' || entry.name === '.shadow-cljs') continue;
      listExampleSources(full, acc);
    } else if (/\.clj[sc]$/.test(entry.name)) {
      acc.push(full);
    }
  }
  return acc;
}

// Returns { ok, violations, callSitesByCall, requireSitesByArtefact, filesScanned }.
//
// NON-VACUITY IS BUILT IN, on both halves and for the same reason the emitted
// positive controls above exist. A roster row whose façade was renamed would
// find zero call sites and silently stop checking anything; an ns-form reader
// that stopped working would report zero requires and turn every row into a
// vacuous pass. So the contract also requires each row to FIND its call sites
// and to find its artefact genuinely required somewhere — either reading zero
// fails the gate LOUD.
function assertExampleCoLoadIsolation(files = listExampleSources(EXAMPLES_DIR)) {
  const violations = [];
  const callSitesByCall = new Map();
  const requireSitesByArtefact = new Map();
  for (const row of OPTIONAL_ARTEFACT_FACADES) {
    callSitesByCall.set(row.call, 0);
    requireSitesByArtefact.set(row.artefact, 0);
  }

  for (const file of files) {
    let source;
    try {
      source = fs.readFileSync(file, 'utf8');
    } catch (_e) {
      continue;
    }
    const stripped = stripCommentsAndStringBodies(source);
    const ns = nsForm(stripped);
    const requires = requiredNamespaces(ns);
    const body = ns ? stripped.replace(ns, '') : stripped;

    for (const artefact of new Set(OPTIONAL_ARTEFACT_FACADES.map((r) => r.artefact))) {
      if (requires.has(artefact)) {
        requireSitesByArtefact.set(artefact, requireSitesByArtefact.get(artefact) + 1);
      }
    }

    for (const row of OPTIONAL_ARTEFACT_FACADES) {
      const hits = facadeCallSites(body, row.call);
      if (hits === 0) continue;
      callSitesByCall.set(row.call, callSitesByCall.get(row.call) + hits);
      if (!requires.has(row.artefact)) {
        violations.push({
          file: path.relative(path.resolve(ROOT, '..'), file).replace(/\\/g, '/'),
          call: row.call,
          artefact: row.artefact,
          absentError: row.absentError,
          hits,
        });
      }
    }
  }

  const deadRows = OPTIONAL_ARTEFACT_FACADES
    .filter((row) => callSitesByCall.get(row.call) === 0)
    .map((row) => row.call);
  const unprovenArtefacts = [...requireSitesByArtefact.entries()]
    .filter(([, count]) => count === 0)
    .map(([artefact]) => artefact);

  return {
    ok: violations.length === 0 && deadRows.length === 0 && unprovenArtefacts.length === 0,
    violations,
    deadRows,
    unprovenArtefacts,
    callSitesByCall,
    requireSitesByArtefact,
    filesScanned: files.length,
  };
}

// ----- Story's enabled?=false elision ----------------------------------------

// Story's contract (tools/story/spec/005-SOTA-Features.md §Production elision):
// a Story-importing `:advanced` build compiled with
// `{re-frame.story.config/enabled? false}` carries no Story shell. No claim
// above can see that, because the counter imports no Story, so this one reads
// a Story-importing app, `:examples/counter-with-stories`, released twice from
// the same source: OFF with the define false, ON at its default. Each sentinel
// is a string literal only a shell body emits. It must be ABSENT from the OFF
// release and PRESENT in the ON release, and the ON half is what proves the
// literal live, since a grep for a literal that exists nowhere passes vacuously.
//
// The claim refuses input it cannot classify: a release that is missing or
// empty, a manifest it cannot read, or a manifest that does not list Story's
// facade, because then the release is not the Story-importing app the claim is
// about and no absence read from it means anything.
//
// Xray is outside the claim. Story requires Xray statically, and Xray's
// load-time forms keep it in any build whose graph holds it; 005 states that
// residue with its measurement.
const STORY_ELISION = {
  offDir: path.join('out', 'bundle-isolation-story-off'),
  onDir: path.join('out', 'examples', 'counter-with-stories'),
  storySource: 're_frame/story.cljc',
  sentinels: [
    { source: 're-frame.story.ui.shell root class display name', sentinel: 'rf-story-shell' },
    { source: 're-frame.story.ui.help launcher title and aria-label', sentinel: 'Show playground help' },
    { source: 're-frame.story.ui.canvas class display name', sentinel: 'rf-story-canvas' },
    { source: 're-frame.story.ui.open-in-editor chip test id', sentinel: 'story-open-in-editor' },
  ],
};

// One release for the Story claim: its emitted JavaScript and its manifest.
function loadStoryRelease(dir) {
  const { status, blob } = classifyReleaseBundle(dir);
  const manifest = status === 'ok' ? readBuildManifest(dir) : { status: 'not read', sources: null };
  return { dir, status, blob, manifestStatus: manifest.status, sources: manifest.sources };
}

// The Story claim over the OFF and ON releases. Returns
// { ok, unclassified, leaked, dead, checked }: `unclassified` says why each
// input was refused, `leaked` names each sentinel the OFF release carries, and
// `dead` each sentinel the ON release lacks.
function checkStoryElision(off, on, spec = STORY_ELISION) {
  const unclassified = [];
  if (spec.sentinels.length === 0) unclassified.push('the claim names no sentinel');
  for (const [label, release] of [['OFF', off], ['ON', on]]) {
    if (release.status !== 'ok') {
      unclassified.push(`${label} release ${release.status}: ${release.dir}`);
    } else if (release.manifestStatus !== 'ok') {
      unclassified.push(`${label} release manifest ${release.manifestStatus}: ${release.dir}`);
    } else if (!release.sources.includes(spec.storySource)) {
      unclassified.push(`${label} release does not load Story (its manifest lists no ` +
        `${spec.storySource}): ${release.dir}`);
    }
  }
  const leaked = [];
  const dead = [];
  if (unclassified.length === 0) {
    for (const { source, sentinel } of spec.sentinels) {
      const offHits = countSubstring(off.blob, sentinel);
      const onHits = countSubstring(on.blob, sentinel);
      report.detail(`    [${offHits === 0 ? 'OK' : 'FAIL'}] OFF ${source}: sentinel ` +
                    `${JSON.stringify(sentinel)} expected 0, was ${offHits}`);
      report.detail(`    [${onHits > 0 ? 'OK' : 'FAIL'}] ON  ${source}: sentinel ` +
                    `${JSON.stringify(sentinel)} expected >= 1, was ${onHits}`);
      if (offHits !== 0) leaked.push({ source, sentinel, hits: offHits });
      if (onHits === 0) dead.push({ source, sentinel });
    }
  }
  return {
    ok: unclassified.length === 0 && leaked.length === 0 && dead.length === 0,
    unclassified,
    leaked,
    dead,
    checked: spec.sentinels.length,
  };
}

// ----- main ------------------------------------------------------------------

function main() {
  report.detail('=== Bundle isolation: counter example ===');

  const bundleDir = path.join(ROOT, 'out', 'examples', 'counter');
  const { status, blob } = classifyReleaseBundle(bundleDir);

  if (status !== 'ok') {
    report.flushDetails();
    if (status === 'empty') {
      // Non-vacuous floor: a present-but-empty output dir
      // satisfies every absence check and would false-GREEN. Reject it.
      console.error(`[bundle-isolation] bundle present but empty (zero top-level JS) — ${bundleDir}`);
      console.error('                   The release emitted no inspectable bundle, so the');
      console.error('                   sentinel-absence checks would pass vacuously. A clean');
      console.error('                   release must produce top-level *.js with real content.');
      console.error('                   Did "npm run test:bundle-isolation" actually build it,');
      console.error('                   or is this a stale empty out/ dir?');
    } else {
      console.error(`[bundle-isolation] bundle path missing — ${bundleDir}`);
      console.error('                   Build it with "npm run test:bundle-isolation".');
    }
    process.exit(1);
  }

  const manifest = readBuildManifest(bundleDir);
  if (manifest.status !== 'ok') {
    report.flushDetails();
    console.error(`[bundle-isolation] build manifest ${manifest.status} — ${manifest.file}`);
    if (manifest.reason) console.error(`                   ${manifest.reason}`);
    console.error('                   The graph claims are read from the manifest.edn that');
    console.error('                   release build of examples/counter writes beside main.js;');
    console.error('                   rebuild it with "npm run test:bundle-isolation".');
    process.exit(1);
  }

  report.detail(`bundle: ${bundleDir}`);
  report.detail(`bundle size: ${blob.length} chars`);
  report.detail(`manifest: ${manifest.sources.length} sources`);
  report.detail('');

  // Completeness: every artefact declares exactly one claim, and every elision
  // claim its positive control.
  const completeness = assertPositiveControlComplete();

  // Liveness: every forbidden source names something real on the classpath.
  const liveness = assertForbiddenSourcesLive();

  // The manifest is the counter build's own.
  const onControl = manifest.sources.includes(MANIFEST_ON_CONTROL);

  // Canonical coverage: every browser-optional publishable runtime
  // (deps.edn `:clein/build` authority) must carry a primary isolation entry,
  // so an artefact omitted from every internal table cannot be defined away.
  const coverage = assertCanonicalInventoryCovered();

  // ns-load co-load isolation: an example that CALLS an optional
  // artefact's façade must `:require` that artefact itself, rather than loading
  // only because a sibling app in the consolidated bundle already did.
  const coLoad = assertExampleCoLoadIsolation();

  const ctx = makePositiveContext();

  let allOk = completeness.ok && liveness.ok && onControl && coverage.ok && coLoad.ok;
  const failures = [];
  const positiveFailures = [];
  let graphChecked = 0;
  let internalChecked = 0;
  let allowListChecked = 0;
  let positiveChecked = 0;
  for (const artefact of ARTEFACTS) {
    report.detail(`  ${artefact.name}:`);
    const graph = artefact.forbiddenSources
      ? checkGraphClaim(manifest.sources, artefact)
      : { ok: true, leaked: [], checked: 0 };
    graphChecked += graph.checked;
    const res = checkArtefact(blob, artefact);
    internalChecked += res.internalChecked;
    allowListChecked += res.allowListChecked;
    if (!graph.ok || !res.ok) {
      allOk = false;
      failures.push({ name: artefact.name, leaked: graph.leaked, ...res });
    }

    // Positive control: prove each elision sentinel still exists where it
    // SHOULD, so a drifted / renamed sentinel fails LOUD instead of silently
    // turning the negative grep above into a vacuous pass. Skip artefacts with
    // no declared control — assertPositiveControlComplete already flagged any
    // elision claim missing one.
    if (POSITIVE_CONTROL[artefact.name]) {
      const pos = checkPositiveControl(ctx, artefact);
      positiveChecked += pos.checked;
      if (!pos.ok) {
        allOk = false;
        positiveFailures.push({ name: artefact.name, ...pos });
      }
    }
  }

  // Story's enabled?=false elision, over a Story-importing app released OFF and ON.
  report.detail('  story-elision (counter-with-stories, enabled? false against true):');
  const story = checkStoryElision(loadStoryRelease(path.join(ROOT, STORY_ELISION.offDir)),
                                  loadStoryRelease(path.join(ROOT, STORY_ELISION.onDir)));
  if (!story.ok) allOk = false;

  report.detail('');
  if (allOk) {
    report.pass(
      'bundle-isolation',
        `${ARTEFACTS.length} artefact entries; ` +
        `${graphChecked} forbidden sources absent from ${manifest.sources.length} manifest sources ` +
        `(${liveness.resolved} resolved on the classpath); ` +
        `${internalChecked} elision sentinels absent; ` +
        `${allowListChecked} allow-list budgets ok; ` +
        `${positiveChecked} emitted positive-control sentinels present; ` +
        `${story.checked} Story shell sentinels absent at enabled? false and present at true; ` +
        `${coverage.required.length} canonical browser-optional runtimes covered ` +
        `(${coverage.genericCount} generic, ${coverage.dedicatedCount} dedicated); ` +
        `${coLoad.filesScanned} example sources co-load isolated ` +
        `(${[...coLoad.callSitesByCall.values()].reduce((a, b) => a + b, 0)} optional-façade ` +
        `call sites, each backed by its own :require); ` +
        `bundle=${bundleDir} (${blob.length} chars)`
    );
    process.exit(0);
  } else {
    report.flushDetails();
    console.error('=== FAIL ===');
    console.error('');
    if (failures.length) {
      console.error('At least one artefact leaked into the counter bundle:');
      for (const f of failures) {
        if (f.leaked.length) {
          const more = f.leaked.length > 5 ? ` and ${f.leaked.length - 5} more` : '';
          console.error(`  - ${f.name}: the manifest lists ${f.leaked.slice(0, 5).join(', ')}${more}`);
        }
        if (!f.internalOk) {
          console.error(`  - ${f.name}: ${f.internalFailures} elision sentinel(s) present in the emitted bundle`);
        }
        if (!f.allowListOk) {
          console.error(`  - ${f.name}: ${f.allowListHits} consumer allow-list hit(s), over budget`);
        }
      }
      console.error('');
      console.error('Per the bundle-isolation contracts (per-feature artefacts, and');
      console.error('tools/ with its dev-only dependencies):');
      console.error('  - Counter imports zero per-feature artefacts.');
      console.error('  - Each artefact ships as its own Maven jar, so a');
      console.error('    consumer that never requires one never pays for it.');
      console.error('  - core/* MUST NOT `:require` any per-feature ns; the');
      console.error('    re-export wrappers look the API up through the');
      console.error('    late-bind hook table at call time.');
      console.error('  - implementation/ MUST NOT `:require` anything under');
      console.error('    tools/ (tools/README.md bundle-isolation contract).');
      console.error('');
      console.error('A forbidden source in the manifest means a namespace the counter');
      console.error('build loads `:require`s it — most likely a `:require` added to a');
      console.error('core/* namespace. An elision sentinel in the emitted bundle means a');
      console.error('call site reached bodies Closure should have dropped. A consumer-');
      console.error('allow-list count above the expected value means core gained another');
      console.error('reference to a consumer-side hook / fx key — verify it is intentional');
      console.error('and bump expectedAllowListHits in this script.');
      console.error('');
    }
    if (!onControl) {
      console.error(`Manifest on-control: ${manifest.file} does not list`);
      console.error(`${MANIFEST_ON_CONTROL}, which every app loads, so it is not the counter`);
      console.error('build\'s manifest and no absence read from it means anything.');
      console.error('');
    }
    if (!liveness.ok) {
      console.error('Forbidden-source liveness:');
      if (liveness.error) {
        console.error(`  The build classpath could not be read: ${liveness.error}`);
      }
      for (const { name, source } of liveness.dead) {
        console.error(`  - ${name}: ${JSON.stringify(source)} resolves nowhere on the build classpath`);
      }
      if (liveness.dead.length) {
        console.error('  A forbidden source that names nothing can never match, so its claim');
        console.error('  would pass whether or not the artefact leaks. Re-derive it from the');
        console.error('  artefact\'s source tree — most likely a rename moved it.');
      }
      for (const jar of liveness.absentJars) {
        console.error(`  (Not in the local Maven repository, so not searched: ${jar})`);
      }
      console.error('');
    }
    if (!completeness.ok) {
      console.error('Claim completeness:');
      if (completeness.unclaimed.length) {
        console.error('  Artefact(s) without exactly one claim (forbiddenSources OR ' +
          `internalSentinels): ${completeness.unclaimed.join(', ')}`);
      }
      if (completeness.missing.length) {
        console.error(`  Elision claim(s) with NO positive control declared: ${completeness.missing.join(', ')}`);
        console.error('  Add a POSITIVE_CONTROL entry ({ onModule }) so the negative');
        console.error('  sentinel check cannot ship as a half-contract.');
      }
      if (completeness.extra.length) {
        console.error(`  POSITIVE_CONTROL entries with no matching elision claim: ${completeness.extra.join(', ')}`);
        console.error('  Remove the stale entry or restore the claim it named.');
      }
      if (completeness.malformed.length) {
        console.error(`  Control(s) without an emitted module: ${completeness.malformed.join(', ')}`);
      }
      for (const { name, source } of completeness.malformedSources) {
        console.error(`  ${name}: forbidden source ${JSON.stringify(source)} is not a relative classpath path`);
      }
      for (const { moduleName, owners } of completeness.sharedModules) {
        console.error(`  Emitted module '${moduleName}' is assigned to multiple owners: ${owners.join(', ')}`);
      }
      for (const { sentinel, owners } of completeness.sharedSentinels) {
        console.error(`  Sentinel ${JSON.stringify(sentinel)} is assigned to multiple owners: ${owners.join(', ')}`);
      }
      for (const { source, owners } of completeness.sharedSources) {
        console.error(`  Forbidden source ${JSON.stringify(source)} is assigned to multiple owners: ${owners.join(', ')}`);
      }
      console.error('');
    }
    if (!coverage.ok) {
      console.error('Canonical inventory coverage:');
      console.error('  Browser-optional publishable runtime(s) with NO valid isolation gate:');
      for (const rt of coverage.missing) {
        console.error(`    - ${rt.relPath}: ${(rt.reasons || []).join('; ')}`);
      }
      console.error('  Each is a separately published day8/re-frame2-<name> artefact');
      console.error('  (implementation/<path>/deps.edn carries a real :aliases/:clein/build)');
      console.error('  with a CLJS runtime that could leak into a production bundle');
      console.error('  unguarded. Either add a primary ARTEFACTS entry keyed by its exact');
      console.error('  `relPath` (with live runtime sentinels + a positive control), or —');
      console.error('  for a substrate adapter proven by an adapter-specific gate — map its');
      console.error('  exact path in DEDICATED_ISOLATION_GATES with a REAL descriptor');
      console.error('  ({ checkers: [<existing gate script>], command: <invoked package');
      console.error('  script> }); a prose string, a nonexistent checker, or an uninvoked');
      console.error('  command is rejected. If it is genuinely not a browser-optional');
      console.error('  runtime (always-present or JVM-only), add it to NON_BROWSER_OPTIONAL');
      console.error('  with a reason.');
      console.error('');
    }
    if (!coLoad.ok) {
      console.error('Example ns-load co-load isolation:');
      for (const v of coLoad.violations) {
        console.error(`  - ${v.file} calls ${v.call} (${v.hits} site(s)) but does not`);
        console.error(`    :require ${v.artefact} in its own ns form.`);
        console.error(`    Optional artefacts resolve through the late-bind hook table, so`);
        console.error(`    that call succeeds ONLY while some co-loaded sibling namespace has`);
        console.error(`    already loaded ${v.artefact}. Built alone, this example's ns-load`);
        console.error(`    throws :${v.absentError} before a single test runs — and the`);
        console.error(`    consolidated bundle stays green either way, which is the whole`);
        console.error(`    reason this check reads the ns form. Fix: add [${v.artefact}] to`);
        console.error(`    this file's :require. Never delete the call to reach green.`);
      }
      if (coLoad.deadRows.length) {
        console.error(`  Façade row(s) with ZERO call sites anywhere under examples/: ${coLoad.deadRows.join(', ')}`);
        console.error('    The rule would silently check nothing. Either the façade was');
        console.error('    renamed (re-derive the row from the current re-frame.core export)');
        console.error('    or the last example using it went away (drop the row).');
      }
      if (coLoad.unprovenArtefacts.length) {
        console.error(`  Artefact(s) never seen REQUIRED by any example: ${coLoad.unprovenArtefacts.join(', ')}`);
        console.error('    The ns-form reader can no longer see requires it should see, so');
        console.error('    every row above it had degraded to a vacuous pass. Fix the reader,');
        console.error('    never the roster.');
      }
      console.error('');
    }
    if (!story.ok) {
      console.error('Story enabled?=false elision (tools/story/spec/005-SOTA-Features.md §Production elision):');
      for (const reason of story.unclassified) console.error(`  - cannot classify: ${reason}`);
      for (const { source, sentinel, hits } of story.leaked) {
        console.error(`  - ${source}: ${JSON.stringify(sentinel)} found ${hits} time(s) in the enabled? false release`);
      }
      if (story.leaked.length) {
        console.error('    A shell body survived the define, so something reaches it from a');
        console.error('    top-level form or an ungated call site. Gate that root on');
        console.error('    rf.story.config/enabled? tested on its own: inside an `and` it compiles');
        console.error('    to a cljs.core/truth_ call, which Closure does not fold.');
      }
      for (const { source, sentinel } of story.dead) {
        console.error(`  - ${source}: ${JSON.stringify(sentinel)} is absent from the enabled? true release,`);
        console.error('    so its absence from the OFF release proves nothing. Re-derive the');
        console.error('    sentinel from the shell source; never delete it to reach green.');
      }
      console.error('  `npm run test:bundle-isolation` builds both releases: the ON one is');
      console.error('  :examples/counter-with-stories, the OFF one the same build with');
      console.error('  scripts/bundle-isolation-story-off.edn merged in.');
      console.error('');
    }
    if (positiveFailures.length) {
      console.error('Positive control FAILED — a sentinel is no longer PRESENT where');
      console.error('it should be. The sentinel string has DRIFTED (most');
      console.error('likely an error-id / message literal was renamed in the artefact');
      console.error('source without updating this script), so the negative counter-');
      console.error('bundle check above has LOST ITS TEETH for that artefact: it would');
      console.error('pass whether or not the artefact actually leaked.');
      for (const p of positiveFailures) {
        if (p.vacuous) {
          console.error(`  - ${p.name}: emitted module '${p.module}' missing/empty — cannot prove presence`);
        } else {
          console.error(`  - ${p.name}: only ${p.passed}/${p.checked} sentinels present in emitted module '${p.module}'`);
        }
      }
      console.error('  Fix: re-derive the sentinel from the artefact source and update');
      console.error('  the matching internalSentinels entry (keep it a live, unique');
      console.error('  literal). Never delete the sentinel to make this pass — that');
      console.error('  restores the vacuous-green hole this control exists to close.');
    }
    process.exit(1);
  }
}

if (require.main === module) {
  main();
}

module.exports = {
  ARTEFACTS,
  POSITIVE_CONTROL,
  MANIFEST_ON_CONTROL,
  NON_BROWSER_OPTIONAL,
  DEDICATED_ISOLATION_GATES,
  assertPositiveControlComplete,
  checkArtefact,
  checkGraphClaim,
  assertForbiddenSourcesLive,
  readBuildClasspath,
  listJarEntries,
  resolveForbiddenSource,
  pathDeclaresBuildAlias,
  discoverBrowserOptionalRuntimes,
  genericCoveragePaths,
  validateDedicatedGate,
  readPackageScripts,
  assertCanonicalInventoryCovered,
  OPTIONAL_ARTEFACT_FACADES,
  stripCommentsAndStringBodies,
  nsForm,
  requiredNamespaces,
  facadeCallSites,
  listExampleSources,
  assertExampleCoLoadIsolation,
  EXAMPLES_DIR,
  STORY_ELISION,
  loadStoryRelease,
  checkStoryElision,
};
