#!/usr/bin/env node
/*
 * Per-feature artefact bundle-isolation verifier (bead rf2-51x5,
 * discovered-from rf2-o423 test-coverage audit).
 *
 * The counter example is the canonical no-feature app: it imports zero
 * per-feature artefacts. Every per-feature split (schemas / machines /
 * routing / flows / http / ssr) was verified at PR-time with a one-shot
 * grep against this same bundle; this script makes the same assertion
 * permanent so a future change that accidentally re-imports a split-out
 * namespace into core (e.g. `(:require [re-frame.flows])` slipping into
 * `re-frame.core`) is caught by CI rather than by a downstream consumer
 * paying for the regression.
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
const { readReleaseBlob } = require('./lib/read-release-bundle.cjs');

const ROOT = path.resolve(__dirname, '..');

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
      // http_managed.cljc — managed-abort fx (registered only when the
      // ns is loaded; the keyword string survives :advanced).
      { source: 're-frame.http-managed reg-fx (rf.http/managed-abort)',
        sentinel: 'rf.http/managed-abort' },
      // http_managed.cljc — canned-failure stub fx.
      { source: 're-frame.http-managed reg-fx (rf.http/managed-canned-failure)',
        sentinel: 'rf.http/managed-canned-failure' },
      // http_managed.cljc — failure taxonomy: decode failure.
      { source: 're-frame.http-managed classify-failure (decode-failure)',
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

  // Epoch artefact (rf2-69ad2 split — re-frame.epoch lives at
  // implementation/epoch/). Causa's preload.cljs `:requires`
  // re-frame.epoch to anchor it onto the dev classpath so every
  // Causa-enabled build has working time-travel; the preload is
  // dev-only (gated by shadow-cljs `:devtools/preloads`) so the
  // anchor must NOT pull epoch into a production bundle. This entry
  // pins that contract — if a host or a refactor accidentally
  // `:requires` re-frame.epoch from production-classpath code, the
  // sentinel below will appear in the counter bundle and this check
  // fails (per audit rf2-i0veg §5c).
  {
    name: 'epoch',
    internalSentinels: [
      // epoch.cljc — restore-schema-mismatch trace op-name (a string
      // literal inside `enabled-ops` and emitted from the restore
      // path). Unique to epoch.cljc; survives :advanced (string
      // literals are not renamed).
      { source: 're-frame.epoch (rf.epoch/restore-schema-mismatch)',
        sentinel: 'rf.epoch/restore-schema-mismatch' },
      // epoch.cljc — restore-during-drain trace op-name. Same
      // contract; second sentinel guards against a future rename
      // of one but not the other.
      { source: 're-frame.epoch (rf.epoch/restore-during-drain)',
        sentinel: 'rf.epoch/restore-during-drain' },
    ],
    consumerAllowList: null,
    expectedAllowListHits: 0,
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
];

// ----- helpers ---------------------------------------------------------------

// Bundle reading is shared with the sibling check-* scripts via
// scripts/lib/read-release-bundle.cjs (rf2-qlk4w). The helper reads
// only top-level *.js — the release artefact — so a stale dev-build
// `cljs-runtime/` subdir from a prior `shadow-cljs compile` doesn't
// get grep-ed alongside.

function escapeRe(s) {
  return s.replace(/[.*+?^${}()|[\]\\]/g, '\\$&');
}

function countSubstring(blob, needle) {
  const re = new RegExp(escapeRe(needle), 'g');
  const m  = blob.match(re);
  return m ? m.length : 0;
}

function countPattern(blob, re) {
  // Caller-supplied RegExp — reset lastIndex defensively in case the
  // RegExp object has been used before (RegExp literals carry state
  // when /g is set).
  re.lastIndex = 0;
  const m = blob.match(re);
  return m ? m.length : 0;
}

function checkArtefact(blob, art) {
  console.log(`  ${art.name}:`);

  let internalOk = true;
  for (const { source, sentinel } of art.internalSentinels) {
    const hits = countSubstring(blob, sentinel);
    const ok   = hits === 0;
    const tag  = ok ? 'OK' : 'FAIL';
    console.log(`    [${tag}] ${source}: sentinel ${JSON.stringify(sentinel)} ` +
                `expected 0, was ${hits}`);
    if (!ok) internalOk = false;
  }

  let allowListOk = true;
  let allowListHits = 0;
  if (art.consumerAllowList) {
    allowListHits = countPattern(blob, art.consumerAllowList);
    allowListOk   = allowListHits <= art.expectedAllowListHits;
    const tag     = allowListOk ? 'OK' : 'FAIL';
    console.log(`    [${tag}] consumer allow-list ${art.consumerAllowList}: ` +
                `${allowListHits} hit(s), expected <= ${art.expectedAllowListHits}`);
  }

  return { ok: internalOk && allowListOk, internalOk, allowListOk, allowListHits };
}

// ----- main ------------------------------------------------------------------

function main() {
  console.log('=== Bundle isolation: counter example (rf2-51x5) ===');

  const bundleDir = path.join(ROOT, 'out', 'examples', 'counter');
  const blob = readReleaseBlob(bundleDir);

  if (blob == null) {
    console.error(`[bundle-isolation] bundle path missing — ${bundleDir}`);
    console.error('                   Did you run "shadow-cljs release examples/counter"?');
    process.exit(1);
  }

  console.log(`bundle: ${bundleDir}`);
  console.log(`bundle size: ${blob.length} chars`);
  console.log('');

  let allOk = true;
  const failures = [];
  for (const art of ARTEFACTS) {
    const res = checkArtefact(blob, art);
    if (!res.ok) {
      allOk = false;
      failures.push({ name: art.name, ...res });
    }
  }

  console.log('');
  if (allOk) {
    console.log('=== PASS ===');
    process.exit(0);
  } else {
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
