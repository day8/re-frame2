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
 *     pull in (xyflow / elkjs / cljs-devtools / zprint / editscript).
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

const path = require('path');
const { createGateReporter } = require('./lib/gate-report.cjs');
const {
  classifyReleaseBundle,
  countMatches,
  countSubstring,
} = require('./lib/read-release-bundle.cjs');

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
      { source: 're-frame.schemas/reg-app-schemas (bad-app-schemas-arg)',
        sentinel: 'rf.error/bad-app-schemas-arg' },
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
    // Two consumer-side strings (Spec 014, rf2-5kpd split):
    //   `rf.http/managed`                — fx-name core's preset map
    //                                      maps to in :test/:story
    //                                      modes (frame.cljc).
    //   `rf.http/managed-canned-success` — canned-stub fx-name the
    //                                      preset map redirects to.
    // The pattern uses a negative lookahead so `rf.http/managed-canned-
    // success` and `rf.http/managed` (without the suffix) each match
    // exactly once, not three times by substring overlap.
    consumerAllowList: /rf\.http\/managed-canned-success|rf\.http\/managed(?!-canned)/g,
    expectedAllowListHits: 2,
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
  // audit rf2-i0veg §5c). The trace-op keywords below are emitted from
  // epoch.cljc's function bodies as keyword data and survive `:advanced`
  // (string literals are not renamed) regardless of whether
  // `interop/debug-enabled?` gates the emission callsite.
  {
    name: 'epoch',
    internalSentinels: [
      // epoch.cljc — restore-schema-mismatch trace op-name (a string
      // literal inside `enabled-ops` and emitted from the restore
      // path). Unique to epoch.cljc.
      { source: 're-frame.epoch (rf.epoch/restore-schema-mismatch)',
        sentinel: 'rf.epoch/restore-schema-mismatch' },
      // epoch.cljc — restore-during-drain trace op-name. Second
      // sentinel guards against a future rename of one but not the other.
      { source: 're-frame.epoch (rf.epoch/restore-during-drain)',
        sentinel: 'rf.epoch/restore-during-drain' },
      // epoch.cljc — on-frame-destroyed! emits this per silenced cb.
      { source: 're-frame.epoch on-frame-destroyed! (rf.epoch.cb/silenced-on-frame-destroy)',
        sentinel: 'rf.epoch.cb/silenced-on-frame-destroy' },
      // epoch.cljc — replace-app-db! success trace.
      { source: 're-frame.epoch replace-app-db! (rf.epoch/db-replaced)',
        sentinel: 'rf.epoch/db-replaced' },
      // epoch.cljc — replace-app-db! drain-in-flight rejection.
      { source: 're-frame.epoch replace-app-db! (rf.epoch/replace-during-drain)',
        sentinel: 'rf.epoch/replace-during-drain' },
      // epoch.cljc — replace-app-db! schema-mismatch rejection.
      { source: 're-frame.epoch replace-app-db! (rf.epoch/replace-schema-mismatch)',
        sentinel: 'rf.epoch/replace-schema-mismatch' },
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
      // trace/tooling.cljc — explicit sentinel string planted at the
      // bottom of the namespace body (`bundle-isolation-sentinel`).
      // Survives `:advanced` (string literals are not renamed) and
      // sits OUTSIDE any `interop/debug-enabled?` gate so DCE cannot
      // drop the literal independently of the surrounding ns body.
      // The string deliberately includes the bead id and the date it
      // was planted so a future grep can trace it back to the split.
      { source: 're-frame.trace.tooling (bundle-isolation-sentinel)',
        sentinel: 'rf.trace.tooling/sentinel:rf2-qwm0a-2026-05-16:do-not-rename' },
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
  // they never appear in CLJS compilation. Sentinel mirrors the
  // trace-tooling shape (rf2-qwm0a): a unique string planted at the
  // bottom of the namespace body.
  {
    name: 'subs-tooling',
    internalSentinels: [
      // subs/tooling.cljc — explicit sentinel planted at the bottom
      // of the namespace body (`bundle-isolation-sentinel`).
      { source: 're-frame.subs.tooling (bundle-isolation-sentinel)',
        sentinel: 'rf.subs.tooling/sentinel:rf2-bmzq0-2026-05-16:do-not-rename' },
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
  // gated so the body stays out of CLJS). Sentinel mirrors the
  // subs-tooling / trace-tooling shape: a unique string planted at the
  // bottom of the namespace body, surviving `:advanced` (string literals
  // are not renamed) and outside any DCE gate.
  {
    name: 'flows-tooling',
    internalSentinels: [
      // flows/tooling.cljc — explicit sentinel planted at the bottom
      // of the namespace body (`bundle-isolation-sentinel`).
      { source: 're-frame.flows.tooling (bundle-isolation-sentinel)',
        sentinel: 'rf.flows.tooling/sentinel:rf2-s8w3nw-2026-06-12:do-not-rename' },
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
  // stays out of CLJS). Sentinel mirrors the flows-tooling / subs-tooling /
  // trace-tooling shape: a unique string planted at the bottom of the
  // namespace body, surviving `:advanced` (string literals are not renamed)
  // and outside any DCE gate.
  {
    name: 'resources-tooling',
    internalSentinels: [
      // resources/tooling.cljc — explicit sentinel planted at the bottom
      // of the namespace body (`bundle-isolation-sentinel`).
      { source: 're-frame.resources.tooling (bundle-isolation-sentinel)',
        sentinel: 'rf.resources.tooling/sentinel:rf2-gn9juw-2026-06-12:do-not-rename' },
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
  // stays out of CLJS). Sentinel mirrors the flows-tooling / subs-tooling /
  // trace-tooling shape: a unique string planted at the bottom of the
  // namespace body, surviving `:advanced` (string literals are not renamed)
  // and outside any DCE gate.
  {
    name: 'routing-tooling',
    internalSentinels: [
      // routing/tooling.cljc — explicit sentinel planted at the bottom
      // of the namespace body (`bundle-isolation-sentinel`).
      { source: 're-frame.routing.tooling (bundle-isolation-sentinel)',
        sentinel: 'rf.routing.tooling/sentinel:rf2-eiiifu-2026-06-12:do-not-rename' },
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
  // `#?@(:clj ...)`-gated so the body stays out of CLJS). Sentinel mirrors
  // the flows-tooling / subs-tooling / trace-tooling shape: a unique string
  // planted at the bottom of the namespace body, surviving `:advanced`
  // (string literals are not renamed) and outside any DCE gate.
  {
    name: 'machines-tooling',
    internalSentinels: [
      // machines/tooling.cljc — explicit sentinel planted at the bottom
      // of the namespace body (`bundle-isolation-sentinel`).
      { source: 're-frame.machines.tooling (bundle-isolation-sentinel)',
        sentinel: 'rf.machines.tooling/sentinel:rf2-2axssk-2026-06-12:do-not-rename' },
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
  // from the counter bundle entirely. Sentinel mirrors the five
  // algebra-view siblings / trace-tooling shape: a unique string planted at
  // the bottom of the namespace body, surviving `:advanced` (string
  // literals are not renamed) and outside any DCE gate.
  {
    name: 'derivation-graph',
    internalSentinels: [
      // derivation/graph.cljc — explicit sentinel planted at the bottom
      // of the namespace body (`bundle-isolation-sentinel`).
      { source: 're-frame.derivation.graph (bundle-isolation-sentinel)',
        sentinel: 'rf.derivation.graph/sentinel:rf2-6xm07h-2026-06-12:do-not-rename' },
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
      // trace/cascade.cljc — explicit sentinel planted at the bottom
      // of the namespace body (`bundle-isolation-sentinel`).
      { source: 're-frame.trace.cascade (bundle-isolation-sentinel)',
        sentinel: 'rf.trace.cascade/sentinel:rf2-931pm:do-not-rename' },
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
      { source: 're-frame.story.decorators (rf.error/decorator-unknown)',
        sentinel: 'rf.error/decorator-unknown' },
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

  // binaryage/cljs-devtools — the Chrome custom-formatters library
  // Xray's EDN widget adopts for value-rendering
  // (tools/xray/src/.../views/edn_widget/cljs_devtools_render.cljs).
  // Same posture as xyflow + elkjs: dev-only, consumed only by tools/
  // (Xray), gated behind the Xray `:devtools/preloads`. Production
  // bundles MUST NOT pull cljs-devtools — the formatters body weighs
  // tens of kilobytes and gives consumers no runtime benefit (Chrome's
  // console isn't relevant to end users).
  //
  // Sentinels are distinctive identifiers from cljs-devtools' internal
  // namespaces. `devtools.formatters.markup` is the ns that emits
  // JSONML; `devtools_formatters$markup` is its munged form under
  // :advanced. The literal namespace-name string appears in
  // cljs-devtools' source body (in ex-info contexts, in goog.provide
  // calls, in the prefs map). A non-zero hit means cljs-devtools' body
  // got pulled into the bundle — most likely a `:require` slipped from
  // tools/xray/* into implementation/*, or the EDN widget got
  // referenced outside the Xray preload-gated tree.
  {
    name: 'cljs-devtools',
    internalSentinels: [
      // The namespace name as a literal string — appears in
      // cljs-devtools' goog.provide-equivalent and prefs metadata.
      { source: 'binaryage/cljs-devtools formatters namespace (devtools.formatters)',
        sentinel: 'devtools.formatters' },
    ],
    consumerAllowList: null,
    expectedAllowListHits: 0,
  },

  // zprint — the canonical pretty-printer Xray's EDN widget
  // `code-block` uses to pre-format handler-source strings before the
  // in-bundle tokenizer renders them
  // (tools/xray/src/.../views/edn_widget.cljs). Same posture
  // as cljs-devtools: dev-only, consumed only by tools/ (Xray), gated
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
  // leaf-walker classifier. Same posture as zprint and cljs-devtools:
  // dev-only, consumed only by tools/ (Xray), gated behind Xray's
  // `:devtools/preloads`. Production bundles MUST NOT pull editscript
  // — the engine ships ~25KB of source over multiple cljc files +
  // pulls in a priority-map dep; the operator inspection UX is a dev-
  // only concern, never a production one.
  //
  // Sentinels are distinctive identifiers from editscript's source —
  // the `editscript.core` + `editscript.diff.a-star` namespaces both
  // appear in editscript's goog.provide-equivalent + internal
  // references. A non-zero hit means a `:require` slipped from
  // tools/xray/* into implementation/*, or the EDN widget's diff path
  // got referenced outside the Xray preload-gated tree.
  {
    name: 'editscript',
    internalSentinels: [
      // editscript's core namespace name as a literal string.
      { source: 'editscript diff engine core namespace (editscript.core)',
        sentinel: 'editscript.core' },
      // editscript's A* diff algorithm namespace.
      { source: 'editscript A* diff algorithm namespace (editscript.diff.a_star)',
        sentinel: 'editscript.diff.a_star' },
    ],
    consumerAllowList: null,
    expectedAllowListHits: 0,
  },
];

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

  let allOk = true;
  const failures = [];
  let internalChecked = 0;
  let allowListChecked = 0;
  for (const artefact of ARTEFACTS) {
    const res = checkArtefact(blob, artefact);
    internalChecked += res.internalChecked;
    allowListChecked += res.allowListChecked;
    if (!res.ok) {
      allOk = false;
      failures.push({ name: artefact.name, ...res });
    }
  }

  report.detail('');
  if (allOk) {
    report.pass(
      'bundle-isolation',
      `${ARTEFACTS.length} artefact entries; ${internalChecked} internal sentinels absent; ` +
        `${allowListChecked} allow-list budgets ok; bundle=${bundleDir} (${blob.length} chars)`
    );
    process.exit(0);
  } else {
    report.flushDetails();
    console.error('=== FAIL ===');
    console.error('');
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
    process.exit(1);
  }
}

main();
