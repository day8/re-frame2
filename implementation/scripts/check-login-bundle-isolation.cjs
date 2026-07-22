#!/usr/bin/env node
/*
 * Login cross-substrate bundle-isolation verifier (bead rf2-ppbvav; the
 * Helix login arm left with the Helix adapter at S7/W13, rf2-d6epb).
 *
 * The two login examples — Reagent (`login.core`) and UIx
 * (`uix.login.core`) — share ONE substrate-free model owner,
 * `login.model` (examples/core/login/model.cljs): the single source for every
 * `auth.login` schema, fx, machine, event, sub, and the frame config. Each
 * example `:require`s that model and adds only its own views + mount.
 *
 * This gate proves the model stayed substrate-free. Because both builds
 * import `login.model`, if that namespace ever `:require`d a view library or
 * adapter (Reagent or UIx), the foreign substrate's fingerprint would
 * appear in the login bundle it doesn't belong in. So the binding claim is
 * cross-substrate absence: each login bundle carries EXACTLY its own substrate
 * and not the other.
 *
 *   - the Reagent login bundle contains stock-Reagent's reactive-atom
 *     fingerprints and NO UIx spine strings;
 *   - the UIx login bundle contains the UIx spine's gensym-prefix strings and
 *     NO Reagent fingerprints.
 *
 * A regression — e.g. a stray `[re-frame.adapter.reagent]` slipping into
 * `login.model` — drags Reagent into both bundles, so the UIx
 * ABSENT checks fail. That is the substrate-free proof.
 *
 * Strategy mirrors scripts/check-bundle-isolation.cjs (rf2-51x5) and
 * scripts/check-uix-reagent-free.cjs (rf2-jicu2): grep, not parse. Closure
 * `:advanced` renames symbols / namespaces but NOT string literals. Each
 * sentinel is a literal a substrate emits from its own body:
 *
 *   Reagent — `cljsRatom` / `cljsIsDirty`: interop property names stock Reagent
 *     sets via `set!` on React components (reagent.ratom / reagent.impl.batching).
 *     Same sentinels the counter-side rf2-jicu2 gate uses.
 *   UIx — `rf-uix-sub-` …: the per-substrate gensym
 *     prefixes the shared React spine (`re-frame.substrate.spine`) is
 *     parameterised on (re-frame.adapter.uix). They reach the bundle as
 *     string literals (the spine derives a watch-key keyword namespace from
 *     them), unique to the substrate's adapter, and reachable on every mount.
 *
 * Each substrate set is checked PRESENT in its own bundle (methodology sanity —
 * proves the grep has signal + the model and views actually compiled in) and
 * ABSENT in the other (the isolation proof). If a future refactor displaces
 * a sentinel, its own-bundle PRESENT check fails fast rather than letting the
 * cross-bundle ABSENT greps go silently vacuous — re-derive from a sibling
 * literal (Reagent: `Compiler.parse-tag` / `ReagentInput`; UIx: the
 * `-use-sub-` / `-derived-` prefixes, or the substrate-name warning text).
 *
 * Exit 0 on PASS, 1 on FAIL.
 */

'use strict';

const path = require('path');
const { createGateReporter } = require('./lib/gate-report.cjs');
const { assertSentinelSet, classifyOrFail } = require('./lib/sentinel-scan.cjs');

const ROOT = path.resolve(__dirname, '..');
const report = createGateReporter();

// ----- per-substrate sentinels -----------------------------------------------

const REAGENT_SENTINELS = [
  // reagent.ratom — set as a JS property on React components; survives
  // :advanced (interop string, not a CLJS field). Same sentinel the counter
  // rf2-jicu2 gate uses.
  { source: 'reagent.ratom cljsRatom field (set on React component)',
    sentinel: 'cljsRatom' },
  // reagent.impl.batching — RenderQueue.run-queue interop property.
  { source: 'reagent.impl.batching cljsIsDirty (RenderQueue.run-queue interop)',
    sentinel: 'cljsIsDirty' },
];

const UIX_SENTINELS = [
  // re-frame.adapter.uix — the UIx spine's subscribe-container gensym prefix.
  { source: 're-frame.adapter.uix spine subscribe gensym prefix',
    sentinel: 'rf-uix-sub-' },
  // re-frame.adapter.uix — the UIx spine's use-subscribe gensym prefix (its
  // stripped form seeds the use-sub watch-key keyword namespace).
  { source: 're-frame.adapter.uix spine use-subscribe gensym prefix',
    sentinel: 'rf-uix-use-sub-' },
];

// Each login bundle: the substrate it MUST contain (own) + the one it must NOT.
const BUNDLES = [
  {
    name: 'Reagent login',
    dir: 'login',
    own: { label: 'Reagent', sentinels: REAGENT_SENTINELS },
    foreign: [
      { label: 'UIx',   sentinels: UIX_SENTINELS },
    ],
  },
  {
    name: 'UIx login',
    dir: 'login-uix',
    own: { label: 'UIx', sentinels: UIX_SENTINELS },
    foreign: [
      { label: 'Reagent', sentinels: REAGENT_SENTINELS },
    ],
  },
];

// ----- helpers ---------------------------------------------------------------
//
// Bundle reading + the missing/empty non-vacuous-floor guard (rf2-utvst) are
// shared via scripts/lib/read-release-bundle.cjs + scripts/lib/sentinel-scan.cjs
// (classifyOrFail); the per-sentinel present/absent scan loop is the shared
// assertSentinelSet.

function scan(blob, sentinels, mustContain, blobLabel) {
  return assertSentinelSet(blob, sentinels, {
    mustContain,
    count: true,
    emit: (line) => report.detail(line),
    formatLine: ({ source, sentinel, present, hits, tag }) => {
      const expected = mustContain ? 'PRESENT (>=1)' : 'ABSENT (0)';
      const actual   = present     ? `PRESENT (${hits})` : 'ABSENT (0)';
      return `      [${tag}] ${source}: ${JSON.stringify(sentinel)} expected ${expected}, was ${actual}`;
    },
  });
}

function checkBundle(spec) {
  const dir = path.join(ROOT, 'out', 'examples', spec.dir);
  const cls = classifyOrFail(dir, {
    onMissing: (d) => {
      report.flushDetails();
      console.error(`[login-bundle-isolation] ${spec.name}: bundle missing — ${d}`);
      console.error('                    Did you run "shadow-cljs release examples/' + spec.dir + '"?');
      process.exit(1);
    },
    onEmpty: (d) => {
      report.flushDetails();
      // Non-vacuous floor (rf2-utvst): a present-but-empty dir satisfies every
      // ABSENT check and would false-GREEN.
      console.error(`[login-bundle-isolation] ${spec.name}: bundle present but empty (zero top-level JS) — ${d}`);
      console.error('                    The release emitted no inspectable bundle; the');
      console.error('                    cross-substrate absence checks would pass vacuously.');
      console.error('                    Rebuild "shadow-cljs release examples/' + spec.dir + '".');
      process.exit(1);
    },
  });
  const blob = cls.blob;

  report.detail(`  ${spec.name}: ${dir}`);
  report.detail(`    bundle size: ${blob.length} chars`);

  // Own substrate PRESENT (methodology sanity).
  report.detail(`    own substrate ${spec.own.label} (must be PRESENT):`);
  const ownRes = scan(blob, spec.own.sentinels, true, spec.name);

  // Foreign substrates ABSENT (the isolation proof).
  let foreignOk = true;
  let foreignChecked = 0;
  for (const f of spec.foreign) {
    report.detail(`    foreign substrate ${f.label} (must be ABSENT):`);
    const r = scan(blob, f.sentinels, false, spec.name);
    foreignOk = foreignOk && r.ok;
    foreignChecked += r.checked;
  }
  report.detail('');

  return {
    ok: ownRes.ok && foreignOk,
    ownOk: ownRes.ok,
    foreignOk,
    checked: ownRes.checked + foreignChecked,
    bytes: blob.length,
    dir,
    name: spec.name,
  };
}

// ----- main ------------------------------------------------------------------

function main() {
  report.detail('=== Login cross-substrate bundle isolation (rf2-ppbvav) ===');
  report.detail('One substrate-free login.model, two builds; each login bundle');
  report.detail('must carry ONLY its own substrate.');
  report.detail('');

  const results = BUNDLES.map(checkBundle);
  const allOk = results.every((r) => r.ok);

  if (allOk) {
    const checked = results.reduce((n, r) => n + r.checked, 0);
    report.pass(
      'login-bundle-isolation',
      `${results.length} login bundles checked; ${checked} sentinel checks; ` +
        results.map((r) => `${r.dir}=${r.bytes}c`).join('; ')
    );
    process.exit(0);
  }

  report.flushDetails();
  console.error('');
  console.error('=== FAIL ===');
  console.error('');
  for (const r of results) {
    if (r.ok) continue;
    if (!r.foreignOk) {
      console.error(`${r.name}: a FOREIGN substrate leaked into the bundle.`);
      console.error('  The shared substrate-free login.model (examples/core/login/model.cljs)');
      console.error('  appears to have pulled in a view library / adapter — the isolation');
      console.error('  claim (rf2-ppbvav) is broken. Likely cause: a `:require` on');
      console.error('  `reagent.*` / `uix.*` or `re-frame.adapter.*` slipped');
      console.error('  into login.model (which every login build imports) or into another');
      console.error('  substrate-agnostic ns it pulls. Keep login.model substrate-free —');
      console.error('  views, roots, adapter init, and mounts belong ONLY in each core.cljs.');
    }
    if (!r.ownOk) {
      console.error(`${r.name}: the OWN-substrate present-check failed — the grep would`);
      console.error('  be vacuous. Either the sentinel strings have moved (a spine / adapter');
      console.error('  refactor) or the build stopped depending on its adapter. Re-derive the');
      console.error('  substrate sentinel set in this script (Reagent: Compiler.parse-tag /');
      console.error('  ReagentInput; UIx: the -use-sub- / -derived- gensym prefixes).');
    }
  }
  console.error('');
  console.error('Reproduce with:');
  console.error('  cd implementation && shadow-cljs release examples/login \\');
  console.error('    examples/login-uix \\');
  console.error('    && node scripts/check-login-bundle-isolation.cjs');
  process.exit(1);
}

// Checker-owned target contract (rf2-kfn9q): the exact implementation-relative
// runtimes this gate actually isolates. The dedicated-gate binding in
// check-bundle-isolation.cjs requires a runtime's descriptor to name a checker
// whose COVERS_RUNTIMES includes it, so an unrelated existing checker can NOT be
// reused for a new runtime it never inspects. This gate proves the shared
// login.model stays substrate-free across the Reagent / UIx login
// bundles, so it isolates both adapter runtimes.
const COVERS_RUNTIMES = ['adapters/reagent', 'adapters/uix'];

module.exports = { COVERS_RUNTIMES };

// Run only when invoked directly (`node scripts/check-login-bundle-isolation.cjs`),
// not when required as the checker-owned target contract above.
if (require.main === module) {
  main();
}
