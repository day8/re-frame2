#!/usr/bin/env node
/*
 * Bundle-isolation verifier (bead rf2-51x5, discovered-from rf2-o423
 * test-coverage audit; scope since broadened to tools/ + dev-only deps).
 *
 * The counter example is the canonical no-feature app: it imports zero
 * per-feature artefacts AND nothing under tools/. Three families of
 * leak are pinned absent from its production bundle:
 *   - per-feature splits (schemas / machines / routing / flows / http /
 *     ssr / epoch) — each ships as its own Maven jar; core MUST NOT
 *     `:require` any of them (the re-export wrappers late-bind at call
 *     time);
 *   - the tooling siblings + dev-only composers (trace.tooling,
 *     subs.tooling, the EP-0014 algebra-view siblings, derivation.graph,
 *     trace.cascade, Story) — dev/inspection surfaces gated behind the
 *     Xray preload;
 *   - dev-only npm/Maven dependencies machines-viz + the Xray EDN widget
 *     pull in (xyflow / elkjs / zprint / editscript).
 * Every one of these was verified at PR-time with a one-shot grep against
 * this same bundle; this script makes the assertion permanent so a future
 * change that accidentally re-imports a split-out namespace into core
 * (e.g. `(:require [re-frame.flows])` slipping into `re-frame.core`) — or
 * drags a tools/ ns / dev dep into a production-reachable path — is caught
 * by CI rather than by a downstream consumer paying for the regression.
 *
 * Strategy: grep, not parse — the same shape as scripts/check-elision.cjs
 * and scripts/check-perf-bundle.cjs. The closure compiler may rename
 * symbols and namespaces under :advanced, but it does NOT rewrite
 * string literals. Each per-feature artefact emits a small set of
 * `:rf.error/<feature>-*` ex-info / trace strings from its function
 * bodies; those strings appear in the bundle if and only if the
 * artefact's namespace contributes its body forms to the build.
 *
 * Per-artefact sentinels are chosen because they:
 *   - appear ONLY inside the per-feature artefact's namespace
 *   - are textual fragments (ex-info reason strings, trace op names)
 *     not synthesised from keywords at runtime
 *   - are unique enough that a global grep is unambiguous
 *   - sit OUTSIDE elision-gated branches (so :advanced + goog.DEBUG=false
 *     does not DCE them — they only disappear when the whole namespace
 *     is absent from the build).
 *
 * Allow-list contract: a small set of consumer-side keyword strings
 * (e.g. `flows/reg-flow-fx!`, `rf.http/managed`) intentionally remain
 * in the counter bundle even when the per-feature artefacts are NOT
 * loaded — they are the late-bind hook keys / fx-case keys that core
 * publishes for the artefact to populate at ns-load time. The
 * isolation contract distinguishes those (consumer-side, expected) from
 * the implementation-internal sentinels (must be absent).
 *
 * Exit 0 on PASS, 1 on FAIL.
 */

'use strict';

const fs = require('fs');
const path = require('path');
const { createGateReporter } = require('./lib/gate-report.cjs');
const {
  classifyReleaseBundle,
  countMatches,
  countSubstring,
} = require('./lib/read-release-bundle.cjs');
const { assertSentinelSet } = require('./lib/sentinel-scan.cjs');

const ROOT = path.resolve(__dirname, '..');
const report = createGateReporter();

// ----- the bundle-isolation contract ----------------------------------------

// Each artefact has:
//   - `name`: human-readable artefact label (matches the per-feature
//     split's directory name under implementation/).
//   - `internalSentinels`: an array of `{ source, sentinel }` pairs.
//     Each `sentinel` is a string literal that lives in the artefact's
//     own source body (an `ex-info` reason or `trace/emit-error!` op).
//     If any sentinel appears in the counter bundle, the artefact's
//     body has been pulled in — bundle isolation is broken.
//   - `consumerAllowList`: a single regex matching consumer-side
//     keyword strings core publishes for this artefact even when the
//     artefact is NOT loaded (late-bind hook keys, fx-case keys). Used
//     to distinguish 'expected' counter-bundle hits from the
//     implementation-internal sentinels above.
//   - `expectedAllowListHits`: the count established at PR-time for
//     the per-feature split that introduced the consumer-side surface.
//     Captured against examples/counter on origin/main, 2026-05-09.
//     The contract fails on EXCEEDS, not on DECREASE — a refactor that
//     shrinks the consumer-side surface is a strict win.
const ARTEFACTS = [
  {
    name: 'schemas',
    internalSentinels: [
      // schemas.cljc — `reg-app-schemas` validates its arg is a map.
      { source: 're-frame.schemas/reg-app-schemas (app-schemas-bad-arg)',
        sentinel: 'rf.error/app-schemas-bad-arg' },
    ],
    consumerAllowList: null,
    expectedAllowListHits: 0,
  },

  {
    name: 'machines',
    internalSentinels: [
      // machines.cljc — `resolve-guard` ex-info on unresolved keyword.
      { source: 're-frame.machines/resolve-guard (machine-unresolved-guard)',
        sentinel: 'rf.error/machine-unresolved-guard' },
      // machines.cljc — `resolve-guard` ex-info on bad form.
      { source: 're-frame.machines/resolve-guard (machine-bad-guard-form)',
        sentinel: 'rf.error/machine-bad-guard-form' },
      // machines.cljc — :on clause shape validator.
      { source: 're-frame.machines machine-bad-on-clause',
        sentinel: 'rf.error/machine-bad-on-clause' },
    ],
    consumerAllowList: null,
    expectedAllowListHits: 0,
  },

  {
    name: 'routing',
    internalSentinels: [
      // routing.cljc — `route` lookup ex-info.
      { source: 're-frame.routing route-by-id (no-such-route)',
        sentinel: 'rf.error/no-such-route' },
      // routing.cljc — required-param missing on path build.
      { source: 're-frame.routing build-path (missing-route-param)',
        sentinel: 'rf.error/missing-route-param' },
    ],
    consumerAllowList: null,
    expectedAllowListHits: 0,
  },

  {
    name: 'flows',
    internalSentinels: [
      // flows.cljc — topological-sort cycle detection.
      { source: 're-frame.flows topo-sort (flow-cycle)',
        sentinel: 'rf.error/flow-cycle' },
      // flows.cljc — reg-flow form validation: missing :id.
      { source: 're-frame.flows reg-flow (flow-missing-id)',
        sentinel: 'rf.error/flow-missing-id' },
      // flows.cljc — reg-flow form validation: bad :inputs.
      { source: 're-frame.flows reg-flow (flow-bad-inputs)',
        sentinel: 'rf.error/flow-bad-inputs' },
    ],
    // Two consumer-side strings:
    //   `flows/reg-flow`     — late-bind hook key core publishes for
    //                          the flows artefact's reg-flow surface
    //                          (rf2-tfw3 split; rf2-7ppmo consolidated
    //                          the fx-side path onto the same hook).
    //   `rf.fx/reg-flow`     — fx-case key core's case-block dispatches
    //                          on; the flows artefact registers its
    //                          handler against this key.
    consumerAllowList: /flows\/reg-flow|rf\.fx\/reg-flow/g,
    expectedAllowListHits: 2,
  },

  {
    name: 'http',
    internalSentinels: [
      // http/managed.cljc — managed-abort fx (registered only when the
      // ns is loaded; the keyword string survives :advanced).
      { source: 're-frame.http.managed reg-fx (rf.http/managed-abort)',
        sentinel: 'rf.http/managed-abort' },
      // http/test_support.cljc — canned-failure stub fx (rf2-cdmle: the
      // canned-stub fx registrations moved out of http/managed.cljc to a
      // sibling test-support namespace; the keyword string still lives
      // in the http artefact's source tree, just under a different .cljc
      // file. examples/counter never requires either ns, so the sentinel
      // continues to assert the http artefact's bodies aren't pulled in).
      { source: 're-frame.http.test-support reg-fx (rf.http/managed-canned-failure)',
        sentinel: 'rf.http/managed-canned-failure' },
      // http/managed.cljc — failure taxonomy: decode failure.
      { source: 're-frame.http.managed classify-failure (decode-failure)',
        sentinel: 'rf.http/decode-failure' },
    ],
    // Three consumer-side strings (Spec 014, rf2-5kpd split):
    //   `rf.http/managed`                — fx-name core's preset map
    //                                      maps to in :test/:story
    //                                      modes (frame.cljc).
    //   `rf.http/managed-canned-success` — canned-stub fx-name the
    //                                      preset map redirects to.
    //   `rf.http/managed`                — rf2-32ffq1: the fx-args
    //                                      classification walk's case key in
    //                                      re-frame.classification/project-fx-args
    //                                      (a keyword literal gating the
    //                                      :http/project-managed-fx-args
    //                                      late-bind consult — the blessed
    //                                      consumer-side pattern, no :require).
    // The pattern uses a negative lookahead so `rf.http/managed-canned-
    // success` and `rf.http/managed` (without the suffix) each match
    // exactly once, not by substring overlap.
    consumerAllowList: /rf\.http\/managed-canned-success|rf\.http\/managed(?!-canned)/g,
    expectedAllowListHits: 3,
  },

  // Epoch artefact (rf2-69ad2 / rf2-lt4e split — re-frame.epoch lives
  // at implementation/epoch/; Tool-Pair §Time-travel surface, the
  // seventh per-feature split per rf2-5vjj Strategy B). Counter imports
  // zero epoch symbols — the `day8/re-frame2-epoch` artefact must DCE
  // entirely when the consuming app doesn't `:require` re-frame.epoch.
  // Xray's preload.cljs `:requires` re-frame.epoch to anchor it onto the
  // dev classpath so every Xray-enabled build has working time-travel;
  // the preload is dev-only (gated by shadow-cljs `:devtools/preloads`)
  // so the anchor must NOT pull epoch into a production bundle. Core's
  // public re-exports (`rf/epoch-history`, `rf/restore-epoch!`, …) look
  // the producing fns up through the late-bind hook table at call time;
  // a non-zero internal-sentinel hit means the epoch namespace got
  // dragged in (most likely a stray `:require` in a core/* ns — per
  // audit rf2-i0veg §5c). These two literals survive the real
  // goog.DEBUG=false owner module: listener-failure reporting and record
  // assembly remain reachable while the debug-only restore trace ids DCE.
  {
    name: 'epoch',
    internalSentinels: [
      { source: 're-frame.epoch.listeners listener failure diagnostic',
        sentinel: 'rf.epoch.cb/listener-exception' },
      { source: 're-frame.epoch.assembly redacted-path count',
        sentinel: 'rf.epoch/redacted-modified-paths-count' },
    ],
    // One consumer-side string: `epoch/settle!` — the late-bind hook
    // key the router calls into at drain-empty (router.cljc). Core
    // publishes the call site so the epoch artefact can populate the
    // hook at ns-load time; when the artefact is absent the lookup
    // returns nil and the call is a no-op. Sibling `:epoch/*` hook
    // keys (epoch-history, restore-epoch!, …) get DCE'd in counter
    // because their wrappers in core_epoch.cljc are unreachable from
    // the example's entry points.
    consumerAllowList: /epoch\/settle!/g,
    expectedAllowListHits: 1,
  },

  {
    name: 'ssr',
    internalSentinels: [
      // ssr.cljc — namespace-prefix on every server-only fx and trace
      // op (`:rf.server/respond`, `:rf.server/redirect`, ...). The
      // prefix appears in the bundle only when ssr.cljc's body is
      // compiled in.
      { source: 're-frame.ssr server-only fx prefix (rf.server/)',
        sentinel: 'rf.server/' },
      // ssr.cljc — :rf/hydrate event-id (the CLJS hydration entry
      // point registered by ssr.cljc).
      { source: 're-frame.ssr hydrate event (rf/hydrate)',
        sentinel: 'rf/hydrate' },
    ],
    consumerAllowList: null,
    expectedAllowListHits: 0,
  },

  // Resources runtime (rf2-sh0sp4 — the `day8/re-frame2-resources`
  // artefact, Spec 016). A separately published, browser-reachable OPTIONAL
  // per-feature runtime (`.github/scripts/verify-version-lockstep.sh` ships it
  // in the release inventory); `re-frame.core` MUST NOT `:require` it — the
  // resources.cljc header (§Optionality + bundle isolation) pins that, and the
  // public surface late-binds through the hook table so an app that omits the
  // artefact sees a clean `:rf.error/resources-artefact-missing`. Counter
  // imports zero resource symbols, so the whole `re-frame.resources.*` tree
  // must DCE from its production bundle. A non-zero internal-sentinel hit means
  // the resources runtime got dragged in (most likely a stray `:require
  // [re-frame.resources]` in a core/* ns, or a production-reachable path
  // reaching a resources sibling). The `resources-tooling` entry below is a
  // SEPARATE, JVM-only tooling sibling (its CLJS require is `#?@(:clj ...)`-
  // gated). Its positive-control module reaches a real tooling projection;
  // the two entries guard different bodies and cannot satisfy each other.
  //
  // Sentinels are `ex-info` reason-id keywords thrown UNCONDITIONALLY from the
  // registration validators (`reg-resource` / `reg-mutation`) — the same
  // proven shape as the schemas / machines / http error-ids the `login`
  // on-bundle already validates present. They are keyword literals emitted from
  // function bodies on the boot path (every resources app registers resources +
  // mutations), so they survive `:advanced` (string literals are not renamed)
  // and are NOT gated by `interop/debug-enabled?`. Both are unique to the
  // resources artefact's own src tree (verified repo-wide).
  {
    name: 'resources',
    internalSentinels: [
      // registry.cljc — `reg-resource` spec validator ex-info reason.
      { source: 're-frame.resources.registry reg-resource (resource-bad-spec)',
        sentinel: 'rf.error/resource-bad-spec' },
      // mutation_registry.cljc — `reg-mutation` spec validator ex-info reason.
      { source: 're-frame.resources.mutation-registry reg-mutation (mutation-bad-spec)',
        sentinel: 'rf.error/mutation-bad-spec' },
    ],
    consumerAllowList: null,
    expectedAllowListHits: 0,
  },

  // re-frame.trace.tooling (rf2-qwm0a — dev-tooling buffer + listener
  // surface split off from re-frame.trace for production DCE). The
  // counter example never `:require`s `re-frame.trace.tooling`
  // (test-support / Xray preload / Story / re-frame2-pair-mcp do, but counter
  // is the no-feature reference app). When this contract holds, the
  // tooling sibling's body is absent from the bundle entirely — the
  // `re-frame.trace/register-listener!` etc. wrappers are thin
  // late-bind shells whose `:trace.tooling/*` lookups resolve to nil
  // and no-op. The sentinel below is a distinctive string fragment
  // from the tooling's `trace-buffer` filter-predicate body that does
  // NOT appear anywhere else in the framework source. A non-zero hit
  // means the tooling ns slipped into the bundle (most likely a
  // `:require [re-frame.trace.tooling]` was added to a core/* ns).
  {
    name: 'trace-tooling',
    internalSentinels: [
      { source: 're-frame.trace.tooling trace-buffer projection field',
        sentinel: 'trace-events' },
    ],
    consumerAllowList: null,
    expectedAllowListHits: 0,
  },

  // re-frame.subs.tooling (rf2-bmzq0 — `sub-topology` and
  // `sub-cache-snapshot` split off from re-frame.subs for production
  // DCE). Counter never `:require`s `re-frame.subs.tooling` (Xray /
  // re-frame2-pair-mcp / re-frame-10x do, but counter is the no-feature
  // reference app). When this contract holds, the tooling sibling's
  // body is absent from the bundle entirely — the JVM-side aliases in
  // `re-frame.subs` and `re-frame.core` are `#?(:clj ...)`-gated so
  // they never appear in CLJS compilation. The sentinel is a live node-kind
  // value emitted while projecting a subscription-cache entry.
  {
    name: 'subs-tooling',
    internalSentinels: [
      { source: 're-frame.subs.tooling live node kind',
        sentinel: 'subscription-cache-entry' },
    ],
    consumerAllowList: null,
    expectedAllowListHits: 0,
  },

  // re-frame.flows.tooling (rf2-s8w3nw — EP-0014 slice-3
  // `flow-algebra-view` split off from the flows artefact for production
  // DCE). The whole flows artefact is ALREADY bundle-isolated from
  // counter (counter never `:require`s `re-frame.flows` — the `flows`
  // entry above pins that), so this sibling can never reach a no-flows
  // app's bundle; this entry is the belt-and-braces guard the
  // tooling-sibling pattern standardises (it also fires if a flows-using
  // CLJS app's facade ever `:require`s the tooling sibling — the
  // `re-frame.flows` → `re-frame.flows.tooling` require is `#?@(:clj ...)`-
  // gated so the body stays out of CLJS). The sentinel is a live evaluation
  // classification reached by the focused positive-control module.
  {
    name: 'flows-tooling',
    internalSentinels: [
      { source: 're-frame.flows.tooling evaluation classification',
        sentinel: 'after-event' },
    ],
    consumerAllowList: null,
    expectedAllowListHits: 0,
  },

  // re-frame.resources.tooling (rf2-gn9juw — EP-0014 slice-4
  // `resource-algebra-view` / `resource-cache-algebra-view` split off from
  // the resources artefact for production DCE). The whole resources artefact
  // is ALREADY bundle-isolated from counter (counter never `:require`s
  // `re-frame.resources`), so this sibling can never reach a no-resources
  // app's bundle; this entry is the belt-and-braces guard the tooling-sibling
  // pattern standardises (it also fires if a resources-using CLJS app's facade
  // ever `:require`s the tooling sibling — the `re-frame.resources` →
  // `re-frame.resources.tooling` require is `#?@(:clj ...)`-gated so the body
  // stays out of CLJS). The sentinel is a live node-kind value reached by the
  // focused positive-control module.
  {
    name: 'resources-tooling',
    internalSentinels: [
      { source: 're-frame.resources.tooling live node kind',
        sentinel: 'resource-process' },
    ],
    consumerAllowList: null,
    expectedAllowListHits: 0,
  },

  // re-frame.routing.tooling (rf2-eiiifu — EP-0014 slice-5
  // `route-algebra-view` / `route-slice-algebra-view` split off from the
  // routing artefact for production DCE). The whole routing artefact is
  // ALREADY bundle-isolated from counter (counter never `:require`s
  // `re-frame.routing`), so this sibling can never reach a no-routing app's
  // bundle; this entry is the belt-and-braces guard the tooling-sibling
  // pattern standardises (it also fires if a routing-using CLJS app's facade
  // ever `:require`s the tooling sibling — the `re-frame.routing` →
  // `re-frame.routing.tooling` require is `#?@(:clj ...)`-gated so the body
  // stays out of CLJS). The sentinel is a live route-fact node-kind reached by
  // the focused positive-control module.
  {
    name: 'routing-tooling',
    internalSentinels: [
      { source: 're-frame.routing.tooling node kind',
        sentinel: 'route-fact' },
    ],
    consumerAllowList: null,
    expectedAllowListHits: 0,
  },

  // re-frame.machines.tooling (rf2-2axssk — EP-0014 slice-6
  // `machine-algebra-view` / `machine-instance-algebra-view` /
  // `machine-selector?` split off from the machines artefact for production
  // DCE). The whole machines artefact is ALREADY bundle-isolated from
  // counter (counter never `:require`s `re-frame.machines` — the `machines`
  // entry above pins that), so this sibling can never reach a no-machines
  // app's bundle; this entry is the belt-and-braces guard the
  // tooling-sibling pattern standardises (it also fires if a machines-using
  // CLJS app's facade ever `:require`s the tooling sibling — the
  // `re-frame.machines` → `re-frame.machines.tooling` require is
  // `#?@(:clj ...)`-gated so the body stays out of CLJS). The sentinel is a
  // live machine-process node-kind reached by the focused positive control.
  {
    name: 'machines-tooling',
    internalSentinels: [
      { source: 're-frame.machines.tooling live node kind',
        sentinel: 'machine-process' },
    ],
    consumerAllowList: null,
    expectedAllowListHits: 0,
  },

  // re-frame.derivation.graph (rf2-6xm07h — EP-0014 slice-7: the internal
  // graph-inspection COMPOSER that stitches the five algebra-view siblings
  // (subs / flows / resources / routes / machines) into one DerivationGraph
  // view). It lives in core/src but composes the four OPTIONAL siblings
  // through a runtime contributor seam (requiring-resolve on JVM; an
  // explicit contributor map on CLJS) rather than a static cross-artefact
  // `:require`, so it carries no static dep on the optional artefacts and
  // can never drag them into a core-only bundle. The composer is consumed
  // only by dev tools (Xray) + the conformance fixtures, which `:require`
  // it directly; the counter example never does, so the body is absent
  // from the counter bundle entirely. The sentinel is a live family marker
  // reached while projecting the focused positive-control graph.
  {
    name: 'derivation-graph',
    internalSentinels: [
      { source: 're-frame.derivation.graph family marker',
        sentinel: 'rf/family' },
    ],
    consumerAllowList: null,
    expectedAllowListHits: 0,
  },

  // re-frame.derivation.egress (rf2-mm3y49 — the OFF-BOX graph-egress
  // redaction ALGORITHM centralized out of the two drifting copies: the Xray
  // call site (`derivation-graph-helpers/redact-graph-for-egress`) and the
  // derivation-conformance suite's in-tree mirror. It lives in core/src beside
  // the derivation-graph composer but is TOOLING: it is NOT exposed from the
  // `re-frame.core` facade and NOT `:require`d by any production-reachable ns
  // — only Xray (whose `redact-graph-for-egress` delegates to it) and the
  // conformance fixtures `:require` it directly, so the counter example never
  // loads it and Closure `:advanced` + goog.DEBUG=false DCE its body
  // wholesale. A non-zero hit means the egress ns got dragged into the counter
  // bundle (most likely a stray `:require` from a production-reachable ns, or
  // an accidental re-export from the core facade). The sentinel is the live
  // missing-frame marker produced by the egress projection.
  {
    name: 'derivation-egress',
    internalSentinels: [
      { source: 're-frame.derivation.egress missing-frame marker',
        sentinel: 're-frame.derivation.egress/no-egress-frame' },
    ],
    consumerAllowList: null,
    expectedAllowListHits: 0,
  },

  // re-frame.trace.cascade (rf2-931pm — focused-event-only cascade-DAG
  // aggregator). Same posture as `trace.tooling`: the namespace is
  // autoloaded from `re-frame.core` only via the JVM-only conditional
  // `#?@(:clj [[re-frame.trace.cascade]])` require; CLJS production
  // bundles deliberately omit the body so Closure DCE keeps the
  // aggregator + per-fn keyword interns + atoms out. A `:require` on
  // `re-frame.trace.cascade` from a CLJS-reachable core path would
  // surface the sentinel below in the counter bundle and fail this
  // gate.
  {
    name: 'trace-cascade',
    internalSentinels: [
      { source: 're-frame.trace.cascade late-bind registration',
        sentinel: 'trace.cascade/set-focus-predicate!' },
    ],
    consumerAllowList: null,
    expectedAllowListHits: 0,
  },

  // Story Stage 8 (rf2-c9mm) per IMPL-SPEC §6.5. The plain
  // examples/counter bundle imports zero Story symbols — the
  // tools/story/ jar must DCE entirely when the consuming app
  // doesn't `:require` any re-frame.story.* namespace. The sentinels
  // below are ex-info reason strings emitted from
  // tools/story/src/re_frame/story/registrar.cljc and
  // tools/story/src/re_frame/story/decorators.cljc — they live in
  // function bodies (not gated by the `enabled?` flag, which only
  // gates registration callsites). A non-zero count means a
  // re-frame.story.* namespace got dragged into the counter
  // bundle's classpath — the bundle-isolation contract for
  // tools/ ↛ implementation/ is broken.
  {
    name: 'story',
    internalSentinels: [
      // registrar.cljc — unknown-tag rejection on a project tag
      // that hasn't been registered. Only emitted when the
      // registrar's body is in the bundle.
      { source: 're-frame.story.registrar (rf.error/unknown-tag)',
        sentinel: 'rf.error/unknown-tag' },
      // decorators.cljc — decorator-ref taxonomy. Three closely-
      // related sentinels; any one in the bundle indicates Story
      // internals were pulled in.
      { source: 're-frame.story.decorators (rf.error/decorator-bad-ref)',
        sentinel: 'rf.error/decorator-bad-ref' },
      { source: 're-frame.story.decorators unknown-decorator diagnostic',
        sentinel: 'no decorator registered under' },
      { source: 're-frame.story.decorators (rf.error/decorator-unknown-kind)',
        sentinel: 'rf.error/decorator-unknown-kind' },
    ],
    consumerAllowList: null,
    expectedAllowListHits: 0,
  },

  // xyflow / @xyflow/react (rf2-uwvyj — Machines panel render-engine
  // Path B per spec/021 §6.0 + §17.4). The xyflow library is a
  // `devDependency` of `implementation/package.json` consumed only by
  // tools/machines-viz/ (the chart engine under
  // tools/machines-viz/src/day8/re_frame2_machines_viz/chart/);
  // Xray's Machines panel reaches xyflow through machines-viz.
  // Counter (and the UIx + Helix counter variants) MUST NOT pull
  // xyflow into their production bundles — Xray + machines-viz are
  // dev-only (gated by `:devtools/preloads` in shadow-cljs), and a
  // host that doesn't install Xray should never pay for the
  // ~50-80KB gzipped xyflow render engine.
  //
  // Sentinels are CSS class strings that survive `:advanced` because
  // they appear as string literals in xyflow's source. The class
  // `react-flow__pane` is xyflow's canvas-background DOM class —
  // unique to the package; a global grep returns hits only when the
  // xyflow module body is in the bundle. `@xyflow/react` is the npm
  // package name as it appears in re-export keys; same posture.
  //
  // A non-zero hit means `@xyflow/react` got dragged into a
  // production bundle (most likely a `:require` slipped from a
  // tools/* ns into an implementation/* ns, or a chart ns got moved
  // out of the Xray preload-gated tree). Tools/ MUST NOT be reachable
  // from `implementation/` per the bundle-isolation contract in
  // `tools/README.md`.
  {
    name: 'xyflow',
    internalSentinels: [
      // xyflow's canvas-pane CSS class. Distinctive substring;
      // survives Closure :advanced (literal strings are not renamed).
      { source: '@xyflow/react canvas pane CSS class (react-flow__pane)',
        sentinel: 'react-flow__pane' },
      // xyflow's node-renderer CSS class. Second sentinel guards
      // against a future xyflow rename of one but not the other.
      { source: '@xyflow/react node renderer CSS class (react-flow__node)',
        sentinel: 'react-flow__node' },
    ],
    consumerAllowList: null,
    expectedAllowListHits: 0,
  },

  // elkjs (rf2-gpzb4 — Mike's 2026-05-21 xyflow override; elk.js
  // runs as xyflow's layout engine inside the MachineChart). Same
  // posture as xyflow: dev-only, used only by
  // `tools/machines-viz/src/.../chart.cljs` and gated behind the
  // Xray preload. Production bundles MUST NOT pull elkjs — it's
  // ~1MB minified, ~250KB gzipped.
  //
  // Sentinel is a distinctive elk.js internal symbol that survives
  // `:advanced`. `elk.algorithm` appears in elk's options-handling
  // code as a literal string and in the layoutOptions keys the
  // chart emits; presence in a production bundle means elk.js got
  // dragged in.
  {
    name: 'elkjs',
    internalSentinels: [
      { source: 'elk.js algorithm-key string literal (elk.algorithm)',
        sentinel: 'elk.algorithm' },
      { source: 'elk.js layered-spacing-key string literal (elk.layered.spacing)',
        sentinel: 'elk.layered.spacing' },
    ],
    consumerAllowList: null,
    expectedAllowListHits: 0,
  },

  // zprint — the canonical pretty-printer Xray's EDN widget
  // `code-block` uses to pre-format handler-source strings before the
  // in-bundle tokenizer renders them
  // (tools/xray/src/.../views/edn_widget.cljs). Same posture
  // as the other Xray-only dependencies: consumed only by tools/ and gated
  // behind the Xray `:devtools/preloads`. Production bundles MUST
  // NOT pull zprint — the formatter body + its rewrite-clj dep weigh
  // hundreds of kilobytes and give consumers no runtime benefit.
  //
  // Sentinels are distinctive identifiers from zprint's source body —
  // the `zprint.core` namespace string appears in zprint's
  // goog.provide-equivalent + namespace registrations. A non-zero hit
  // means zprint's body got pulled into the bundle (most likely a
  // `:require` slipped from tools/xray/* into implementation/*, or
  // the EDN widget got referenced outside the Xray preload-gated
  // tree).
  {
    name: 'zprint',
    internalSentinels: [
      // zprint's core namespace name as a literal string — appears in
      // zprint's goog.provide-equivalent and internal references.
      { source: 'zprint pretty-printer core namespace (zprint.core)',
        sentinel: 'zprint.core' },
    ],
    consumerAllowList: null,
    expectedAllowListHits: 0,
  },

  // editscript — Xray's diff engine (rf2-n2jig). A* algorithm
  // producing optimally-small EDN edit scripts; replaces the home-grown
  // leaf-walker classifier. Same posture as zprint:
  // dev-only, consumed only by tools/ (Xray), gated behind Xray's
  // `:devtools/preloads`. Production bundles MUST NOT pull editscript
  // — the engine ships ~25KB of source over multiple cljc files +
  // pulls in a priority-map dep; the operator inspection UX is a dev-
  // only concern, never a production one.
  //
  // Sentinels are live identifiers from editscript's quick-diff and A*
  // implementations. A non-zero hit means a `:require` slipped from
  // tools/xray/* into implementation/*, or the EDN widget's diff path
  // got referenced outside the Xray preload-gated tree.
  {
    name: 'editscript',
    internalSentinels: [
      { source: 'editscript quick diff-map protocol entry',
        sentinel: 'editscript.diff.quick/diff-map' },
      { source: 'editscript A* failure diagnostic',
        sentinel: 'A* diff fails to find a solution' },
    ],
    consumerAllowList: null,
    expectedAllowListHits: 0,
  },
];

// ----- the positive control (rf2-e6qmxk) -------------------------------------

// The checks above prove each internal sentinel is ABSENT from the counter
// (no-feature) production bundle. On their own they are only HALF a contract:
// a negative grep for a string that no longer exists ANYWHERE is a vacuous
// pass. If a maintainer renames an error id (say `rf.error/flow-cycle` →
// `rf.error/flow-cyclic` in the flows artefact) WITHOUT updating the sentinel
// here, the old literal is absent from every bundle — so this gate stays green
// whether or not the artefact actually leaks, and its teeth silently vanish
// exactly where it is supposed to bite. Later, a stray `(:require
// [re-frame.flows])` slipping into a core ns — the precise regression this gate
// exists to catch — lands flows bodies in the counter bundle and the gate
// STILL passes.
//
// The sibling check-perf-bundle.cjs solved this with an ON-bundle POSITIVE
// CONTROL: it also greps a build that DOES load the instrumented code and
// asserts the sentinels are PRESENT (onCount > 0) there, so a moved / renamed
// string fails LOUD rather than degrading to a vacuous pass. This map mirrors
// that idea per artefact. Each artefact declares exactly one positive control:
//
//   { onBundle: '<example-dir>' }
//       Grep an example release THIS gate already builds and that DOES load
//       the artefact; assert every sentinel is PRESENT (count > 0). The
//       strongest form (the perf-bundle template): it proves the sentinel is a
//       live literal that survives `:advanced` compilation into a real
//       production bundle. `login` (examples/core/login/model.cljs) requires
//       the schemas / machines / http artefacts, so its bundle carries their
//       sentinels — empirically 1+ each (rf2-e6qmxk validation).
//
//   { onModule: '<module-name>' }
//       Grep exactly one module from the dedicated
//       :bundle-isolation-positive-control release. Every module has a focused
//       entrypoint that reaches the declared owner, and is built in the same
//       :advanced mode with goog.DEBUG=false as the negative counter bundle.
//       Exact module ownership is load-bearing: an occurrence emitted by a
//       sibling module cannot satisfy this entry. Comments, docstrings,
//       JVM-only reader branches, and DCE'd CLJS forms cannot satisfy it either
//       because the checker reads emitted JavaScript only.
//
// assertPositiveControlComplete() requires EVERY artefact to appear here, so
// adding a new artefact without declaring its positive control fails fast
// rather than shipping another negative-only half-contract.
const POSITIVE_CONTROL = {
  // On-bundle (perf-bundle template): login loads these three artefacts.
  schemas:  { onBundle: 'login' },
  machines: { onBundle: 'login' },
  http:     { onBundle: 'login' },

  // On-bundle (rf2-sh0sp4): realworld-resources `:require`s re-frame.resources
  // and registers 20 resources + 11 mutations, so its `:advanced` release
  // carries both registration-validator reason-ids. The strongest control
  // (a real same-options production bundle), matching the schemas/machines/http
  // login template — it proves the sentinels are live literals that survive
  // `:advanced`, not just source text.
  resources: { onBundle: 'realworld-resources' },

  routing:             { onModule: 'routing' },
  flows:               { onModule: 'flows' },
  epoch:               { onModule: 'epoch' },
  ssr:                 { onModule: 'ssr' },
  'trace-tooling':     { onModule: 'trace-tooling' },
  'subs-tooling':      { onModule: 'subs-tooling' },
  'flows-tooling':     { onModule: 'flows-tooling' },
  'resources-tooling': { onModule: 'resources-tooling' },
  'routing-tooling':   { onModule: 'routing-tooling' },
  'machines-tooling':  { onModule: 'machines-tooling' },
  'derivation-graph':  { onModule: 'derivation-graph' },
  'derivation-egress': { onModule: 'derivation-egress' },
  'trace-cascade':     { onModule: 'trace-cascade' },
  story:               { onModule: 'story' },
  xyflow:              { onModule: 'xyflow' },
  elkjs:               { onModule: 'elkjs' },
  zprint:              { onModule: 'zprint' },
  editscript:          { onModule: 'editscript' },
};

// ----- helpers ---------------------------------------------------------------

// Bundle reading + grep primitives (escapeRe / countSubstring /
// countMatches) are shared with the sibling check-* scripts via
// scripts/lib/read-release-bundle.cjs (rf2-qlk4w bundle reader;
// rf2-jkake.15 folded the grep primitives in alongside it). The reader
// returns only top-level *.js — the release artefact — so a stale
// dev-build `cljs-runtime/` subdir from a prior `shadow-cljs compile`
// doesn't get grep-ed alongside.

function checkArtefact(blob, artefact) {
  report.detail(`  ${artefact.name}:`);

  let internalOk = true;
  let internalFailures = 0;
  for (const { source, sentinel } of artefact.internalSentinels) {
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
    internalChecked: artefact.internalSentinels.length,
    internalFailures,
    allowListChecked,
  };
}

// ----- positive control (rf2-e6qmxk) -----------------------------------------

// Lazy blob cache: real example bundles are keyed by directory, while focused
// controls are keyed by their exact emitted module file.
function makePositiveContext() {
  const bundleBlobs = new Map();
  const moduleBlobs = new Map();
  return {
    onBundle(dir) {
      if (!bundleBlobs.has(dir)) {
        bundleBlobs.set(dir, classifyReleaseBundle(path.join(ROOT, 'out', 'examples', dir)));
      }
      return bundleBlobs.get(dir);
    },
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

// Run the positive control for one artefact. Appends per-sentinel lines to the
// report and returns { kind, ok, checked, passed, ... }.
function checkPositiveControl(ctx, artefact) {
  const pc = POSITIVE_CONTROL[artefact.name];
  const sentinels = artefact.internalSentinels;

  if (pc.onBundle) {
    const dir = pc.onBundle;
    const { status, blob } = ctx.onBundle(dir);
    if (status !== 'ok') {
      report.detail(`    [FAIL] on-bundle '${dir}' positive control: bundle ${status} — ` +
                    'cannot prove sentinels present (would be vacuous)');
      return { kind: 'onBundle', ok: false, checked: sentinels.length, passed: 0, vacuous: true, bundle: dir };
    }
    const { ok, passed } = assertSentinelSet(blob, sentinels, {
      mustContain: true,
      count: true,
      emit: (line) => report.detail(line),
      formatLine: ({ source, sentinel, hits, tag }) =>
        `    [${tag}] on-bundle '${dir}' present: ${source}: sentinel ` +
        `${JSON.stringify(sentinel)} expected >= 1, was ${hits}`,
    });
    return { kind: 'onBundle', ok, checked: sentinels.length, passed, bundle: dir };
  }

  const moduleName = pc.onModule;
  const { status, blob, file } = ctx.onModule(moduleName);
  if (status !== 'ok') {
    report.detail(`    [FAIL] emitted module '${moduleName}' positive control: ${status} — ` +
                  'cannot prove sentinels present (would be vacuous)');
    return { kind: 'onModule', ok: false, checked: sentinels.length, passed: 0,
      vacuous: true, module: moduleName, file };
  }
  const { ok, passed } = assertSentinelSet(blob, sentinels, {
    mustContain: true,
    count: true,
    emit: (line) => report.detail(line),
    formatLine: ({ source, sentinel, hits, tag }) =>
      `    [${tag}] emitted module '${moduleName}' present: ${source}: sentinel ` +
      `${JSON.stringify(sentinel)} expected >= 1, was ${hits}`,
  });
  return { kind: 'onModule', ok, checked: sentinels.length, passed,
    module: moduleName, file };
}

// Startup completeness: every ARTEFACT must declare a positive control (and no
// stale entries), so a new artefact cannot ship a negative-only half-contract.
function assertPositiveControlComplete(artefacts = ARTEFACTS, controls = POSITIVE_CONTROL) {
  const missing = artefacts.filter((a) => !controls[a.name]).map((a) => a.name);
  const extra = Object.keys(controls).filter(
    (n) => !artefacts.some((a) => a.name === n)
  );
  const malformed = Object.entries(controls)
    .filter(([_name, pc]) => Number(Boolean(pc.onBundle)) + Number(Boolean(pc.onModule)) !== 1)
    .map(([name]) => name);
  const moduleOwners = new Map();
  for (const [name, pc] of Object.entries(controls)) {
    if (!pc.onModule) continue;
    const owners = moduleOwners.get(pc.onModule) || [];
    owners.push(name);
    moduleOwners.set(pc.onModule, owners);
  }
  const sharedModules = [...moduleOwners.entries()]
    .filter(([_moduleName, owners]) => owners.length !== 1)
    .map(([moduleName, owners]) => ({ moduleName, owners }));
  const sentinelOwners = new Map();
  for (const artefact of artefacts) {
    for (const { sentinel } of artefact.internalSentinels) {
      const owners = sentinelOwners.get(sentinel) || [];
      owners.push(artefact.name);
      sentinelOwners.set(sentinel, owners);
    }
  }
  const sharedSentinels = [...sentinelOwners.entries()]
    .filter(([_sentinel, owners]) => owners.length !== 1)
    .map(([sentinel, owners]) => ({ sentinel, owners }));
  return {
    missing,
    extra,
    malformed,
    sharedModules,
    sharedSentinels,
    ok: missing.length === 0 && extra.length === 0 && malformed.length === 0 &&
      sharedModules.length === 0 && sharedSentinels.length === 0,
  };
}

// ----- canonical publishable-runtime coverage (rf2-sh0sp4 / rf2-klyw5) -------

// assertPositiveControlComplete only cross-checks this script's own two local
// tables (ARTEFACTS <-> POSITIVE_CONTROL): an artefact absent from BOTH is
// defined away, not detected. That is exactly how the resources runtime slipped
// the gate — a separately published, browser-reachable optional runtime omitted
// from every internal table stayed green. This check closes that hole by
// deriving the REQUIRED set from the SAME canonical authority the release
// lockstep uses (.github/scripts/verify-version-lockstep.sh): every publishable
// artefact deps.edn carrying a `:clein/build` alias is a published artefact.
// Each browser-optional runtime among them MUST map either to a primary generic
// ARTEFACTS entry here OR to a real dedicated isolation gate
// (DEDICATED_ISOLATION_GATES below). It is filesystem-derived, so a NEW
// browser-optional artefact — a new implementation/<name>/deps.edn OR a new
// implementation/adapters/<name>/deps.edn with :clein/build, not in the
// exclusion set — that is neither generic-gated nor dedicated-gated fails
// automatically (FAIL-CLOSED). The check does NOT compare two copies of its own
// local table.
//
// Traversal mirrors the release lockstep's bounded FLAT-plus-ADAPTERS shape:
// per-feature + core-tier artefacts live at implementation/<name>/; substrate
// adapters live one directory deeper at implementation/adapters/<name>/. The
// pre-rf2-klyw5 scan read only the top level, so a newly publishable adapter was
// never enrolled (rf2-klyw5 NOTES — a second counterexample to rf2-sh0sp4 AC6).
//
// Excluded from the REQUIRED set (published, but NOT a browser-optional client
// runtime — each with reason):
//   core     — always present (the lockstep root; every app loads it, so it is
//              never isolated from the counter bundle).
//   ssr-ring — JVM-only ring server adapter (all .clj; no CLJS runtime body can
//              reach a production client bundle).
// `ui` is deliberately NOT excluded (rf2-klyw5): day8/re-frame2-ui is a separate
// browser-capable substrate artefact that the adapters do NOT depend on, so the
// old "always present, every adapter loads it" premise was false and let a
// future publishable UI stay unguarded. Because implementation/ui/deps.edn
// carries no real :clein/build today (only a comment mentioning the alias), UI
// is not discovered and pre-publication stays green NATURALLY; when
// rf2-vxgfnd.99.2 makes UI publishable, it must land a UI isolation entry/gate
// in that same slice or this coverage check fails and names `ui`.
const NON_BROWSER_OPTIONAL = new Set(['core', 'ssr-ring']);

// Nested substrate-adapter tier, relative to implementation/ (matches the
// lockstep authority's implementation/adapters/<name>/ layout).
const ADAPTERS_DIR = 'adapters';

// Browser-optional runtimes covered by a REAL dedicated isolation gate rather
// than a generic ARTEFACTS entry. Each substrate adapter ships in the counter/
// login example matrix and is proven isolated by an adapter-specific gate (the
// per-feature artefacts, by contrast, must be ABSENT from the no-feature counter
// bundle, which the generic ARTEFACTS sentinels prove). A discovered runtime
// mapped here is accounted for; a discovered runtime mapped NOWHERE fails closed.
// Keep this to the set of EXISTING dedicated gates only — do not add a runtime
// here without a real gate script proving its isolation.
const DEDICATED_ISOLATION_GATES = {
  reagent:
    'check-uix-helix-reagent-free.cjs + check-login-bundle-isolation.cjs ' +
    '(reagent is the counter/login reference substrate; its fingerprints are ' +
    'the positive control both gates assert present, and absent from uix/helix)',
  uix:
    'check-uix-helix-reagent-free.cjs + check-login-bundle-isolation.cjs ' +
    '(uix bundles must be reagent- and helix-free)',
  helix:
    'check-uix-helix-reagent-free.cjs + check-login-bundle-isolation.cjs ' +
    '(helix bundles must be reagent- and uix-free)',
  'reagent-slim':
    'check-reagent-slim-bundle-isolation.cjs (test:reagent-slim:bundle-isolation ' +
    '— slim bundle free of stock reagent + react-dom/server; classic bundle ' +
    'free of the reagent2.* rewrite)',
};

// Strip deps.edn line comments (`;` to EOL) so a header comment MENTIONING
// `:clein/build` (several artefact deps.edn headers do — implementation/ui's is
// exactly such a comment) is not mistaken for a real build alias.
function stripEdnComments(edn) {
  return edn.replace(/;[^\n]*/g, '');
}

// True when <root>/<relPath>/deps.edn declares a REAL (non-comment) :clein/build
// alias — i.e. the directory is a separately publishable artefact.
function hasRealBuildAlias(root, relPath) {
  let deps;
  try {
    deps = fs.readFileSync(path.join(root, relPath, 'deps.edn'), 'utf8');
  } catch (_e) {
    return false; // no deps.edn — not a publishable artefact directory
  }
  return /:clein\/build/.test(stripEdnComments(deps));
}

// Discover every publishable browser-optional runtime under implementation/,
// using the lockstep authority's bounded flat-plus-adapters traversal. Returns
// `{ name, relPath }` records; `name` is the directory leaf (unique across both
// tiers) and `relPath` is the implementation/-relative path for diagnostics.
function discoverBrowserOptionalRuntimes(root = ROOT) {
  const out = [];

  // Flat per-feature + core-tier artefacts: implementation/<name>/deps.edn.
  let flat;
  try {
    flat = fs.readdirSync(root, { withFileTypes: true });
  } catch (_e) {
    return out; // implementation/ unreadable — nothing to require (fails safe elsewhere)
  }
  for (const ent of flat) {
    if (!ent.isDirectory()) continue;
    if (ent.name === ADAPTERS_DIR) continue;         // descended into below
    if (NON_BROWSER_OPTIONAL.has(ent.name)) continue;
    if (hasRealBuildAlias(root, ent.name)) {
      out.push({ name: ent.name, relPath: ent.name });
    }
  }

  // Nested substrate adapters: implementation/adapters/<name>/deps.edn. The
  // pre-rf2-klyw5 top-level-only scan skipped these; the lockstep authority
  // scans them, so a newly publishable adapter must be enrolled here too.
  let adapters;
  try {
    adapters = fs.readdirSync(path.join(root, ADAPTERS_DIR), { withFileTypes: true });
  } catch (_e) {
    adapters = []; // no adapters/ dir — nothing nested to require
  }
  for (const ent of adapters) {
    if (!ent.isDirectory()) continue;
    const relPath = `${ADAPTERS_DIR}/${ent.name}`;
    if (hasRealBuildAlias(root, relPath)) {
      out.push({ name: ent.name, relPath });
    }
  }
  return out;
}

// Every discovered browser-optional publishable runtime must map to a real
// isolation gate — either a generic ARTEFACTS entry (per-feature runtimes, which
// must be ABSENT from the no-feature counter bundle) or an explicit existing
// dedicated gate (substrate adapters, proven isolated by adapter-specific gates).
// A discovered runtime mapped to NEITHER means a separately published optional
// runtime could leak into a production bundle with nothing to catch it — so the
// gate fails closed and names it.
function assertCanonicalInventoryCovered(required = discoverBrowserOptionalRuntimes()) {
  const generic = new Set(ARTEFACTS.map((a) => a.name));
  const covered = [];
  const missing = [];
  for (const rt of required) {
    if (generic.has(rt.name)) {
      covered.push({ ...rt, via: 'generic' });
    } else if (DEDICATED_ISOLATION_GATES[rt.name]) {
      covered.push({ ...rt, via: 'dedicated', gate: DEDICATED_ISOLATION_GATES[rt.name] });
    } else {
      missing.push(rt);
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

// ----- main ------------------------------------------------------------------

function main() {
  report.detail('=== Bundle isolation: counter example (rf2-51x5) ===');

  const bundleDir = path.join(ROOT, 'out', 'examples', 'counter');
  const { status, blob } = classifyReleaseBundle(bundleDir);

  if (status !== 'ok') {
    report.flushDetails();
    if (status === 'empty') {
      // Non-vacuous floor (rf2-utvst): a present-but-empty output dir
      // satisfies every absence check and would false-GREEN. Reject it.
      console.error(`[bundle-isolation] bundle present but empty (zero top-level JS) — ${bundleDir}`);
      console.error('                   The release emitted no inspectable bundle, so the');
      console.error('                   sentinel-absence checks would pass vacuously. A clean');
      console.error('                   release must produce top-level *.js with real content.');
      console.error('                   Did "shadow-cljs release examples/counter" actually build,');
      console.error('                   or is this a stale empty out/ dir?');
    } else {
      console.error(`[bundle-isolation] bundle path missing — ${bundleDir}`);
      console.error('                   Did you run "shadow-cljs release examples/counter"?');
    }
    process.exit(1);
  }

  report.detail(`bundle: ${bundleDir}`);
  report.detail(`bundle size: ${blob.length} chars`);
  report.detail('');

  // Completeness (rf2-e6qmxk): every artefact must declare a positive control.
  const completeness = assertPositiveControlComplete();

  // Canonical coverage (rf2-sh0sp4): every browser-optional publishable runtime
  // (deps.edn `:clein/build` authority) must carry a primary isolation entry,
  // so an artefact omitted from every internal table cannot be defined away.
  const coverage = assertCanonicalInventoryCovered();

  const ctx = makePositiveContext();

  let allOk = completeness.ok && coverage.ok;
  const failures = [];
  const positiveFailures = [];
  let internalChecked = 0;
  let allowListChecked = 0;
  let positiveChecked = 0;
  for (const artefact of ARTEFACTS) {
    const res = checkArtefact(blob, artefact);
    internalChecked += res.internalChecked;
    allowListChecked += res.allowListChecked;
    if (!res.ok) {
      allOk = false;
      failures.push({ name: artefact.name, ...res });
    }

    // Positive control (rf2-e6qmxk): prove the sentinels still exist where they
    // SHOULD, so a drifted / renamed sentinel fails LOUD instead of silently
    // turning the negative grep above into a vacuous pass. Skip artefacts with
    // no declared control — assertPositiveControlComplete already flagged them.
    if (POSITIVE_CONTROL[artefact.name]) {
      const pos = checkPositiveControl(ctx, artefact);
      positiveChecked += pos.checked;
      if (!pos.ok) {
        allOk = false;
        positiveFailures.push({ name: artefact.name, ...pos });
      }
    }
  }

  report.detail('');
  if (allOk) {
    report.pass(
      'bundle-isolation',
        `${ARTEFACTS.length} artefact entries; ${internalChecked} internal sentinels absent; ` +
        `${allowListChecked} allow-list budgets ok; ` +
        `${positiveChecked} emitted positive-control sentinels present; ` +
        `${coverage.required.length} canonical browser-optional runtimes covered ` +
        `(${coverage.genericCount} generic, ${coverage.dedicatedCount} dedicated); ` +
        `bundle=${bundleDir} (${blob.length} chars)`
    );
    process.exit(0);
  } else {
    report.flushDetails();
    console.error('=== FAIL ===');
    console.error('');
    if (failures.length) {
      console.error('At least one per-feature artefact (or tools/story) leaked');
      console.error('into the counter bundle. Per the bundle-isolation contracts');
      console.error('(rf2-51x5 per-feature, rf2-c9mm story tools):');
      console.error('  - Counter imports zero per-feature artefacts.');
      console.error('  - Each artefact ships as its own Maven jar (rf2-p7va,');
      console.error('    rf2-xbtj, rf2-k682, rf2-tfw3, rf2-5kpd, rf2-uo7v).');
      console.error('  - core/* MUST NOT `:require` any per-feature ns; the');
      console.error('    re-export wrappers look the API up through the');
      console.error('    late-bind hook table at call time.');
      console.error('  - implementation/ MUST NOT `:require` anything under');
      console.error('    tools/ (tools/README.md bundle-isolation contract).');
      console.error('');
      console.error('A non-zero internal-sentinel hit means a per-feature ns');
      console.error('got pulled into the bundle (most likely a `:require` was');
      console.error('added to a core/* namespace). A consumer-allow-list count');
      console.error('above the expected value means core grew a new preset-map');
      console.error('/ case-block reference — verify the change is intentional');
      console.error('and bump expectedAllowListHits in this script.');
      console.error('');
    }
    if (!completeness.ok) {
      console.error('Positive-control completeness (rf2-e6qmxk):');
      if (completeness.missing.length) {
        console.error(`  Artefact(s) with NO positive control declared: ${completeness.missing.join(', ')}`);
        console.error('  Add a POSITIVE_CONTROL entry (onBundle / onModule) so the');
        console.error('  negative sentinel check cannot ship as a half-contract.');
      }
      if (completeness.extra.length) {
        console.error(`  POSITIVE_CONTROL entries with no matching artefact: ${completeness.extra.join(', ')}`);
        console.error('  Remove the stale entry or restore the artefact it named.');
      }
      if (completeness.malformed.length) {
        console.error(`  Control(s) without exactly one emitted target: ${completeness.malformed.join(', ')}`);
      }
      for (const { moduleName, owners } of completeness.sharedModules) {
        console.error(`  Emitted module '${moduleName}' is assigned to multiple owners: ${owners.join(', ')}`);
      }
      for (const { sentinel, owners } of completeness.sharedSentinels) {
        console.error(`  Sentinel ${JSON.stringify(sentinel)} is assigned to multiple owners: ${owners.join(', ')}`);
      }
      console.error('');
    }
    if (!coverage.ok) {
      console.error('Canonical inventory coverage (rf2-sh0sp4 / rf2-klyw5):');
      console.error(`  Browser-optional publishable runtime(s) with NO isolation gate: ${coverage.missing.map((rt) => rt.relPath).join(', ')}`);
      console.error('  Each is a separately published day8/re-frame2-<name> artefact');
      console.error('  (implementation/<path>/deps.edn carries :clein/build) with a CLJS');
      console.error('  runtime that could leak into a production bundle unguarded. Either');
      console.error('  add a primary ARTEFACTS entry with live runtime sentinels + a');
      console.error('  positive control, or — for a substrate adapter proven by an adapter-');
      console.error('  specific gate — map it in DEDICATED_ISOLATION_GATES. If it is');
      console.error('  genuinely not a browser-optional runtime (always-present or JVM-');
      console.error('  only), add it to NON_BROWSER_OPTIONAL with a reason.');
      console.error('');
    }
    if (positiveFailures.length) {
      console.error('Positive control FAILED — a sentinel is no longer PRESENT where');
      console.error('it should be (rf2-e6qmxk). The sentinel string has DRIFTED (most');
      console.error('likely an error-id / message literal was renamed in the artefact');
      console.error('source without updating this script), so the negative counter-');
      console.error('bundle check above has LOST ITS TEETH for that artefact: it would');
      console.error('pass whether or not the artefact actually leaked.');
      for (const p of positiveFailures) {
        if (p.vacuous && p.kind === 'onBundle') {
          console.error(`  - ${p.name}: on-bundle '${p.bundle}' missing/empty — cannot prove presence`);
        } else if (p.vacuous) {
          console.error(`  - ${p.name}: emitted module '${p.module}' missing/empty — cannot prove presence`);
        } else if (p.kind === 'onBundle') {
          console.error(`  - ${p.name}: only ${p.passed}/${p.checked} sentinels present in on-bundle '${p.bundle}'`);
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
  NON_BROWSER_OPTIONAL,
  DEDICATED_ISOLATION_GATES,
  ADAPTERS_DIR,
  assertPositiveControlComplete,
  checkArtefact,
  stripEdnComments,
  hasRealBuildAlias,
  discoverBrowserOptionalRuntimes,
  assertCanonicalInventoryCovered,
};
