#!/usr/bin/env node
/*
 * Login cross-view-layer bundle-isolation verifier (bead rf2-ppbvav; the
 * Helix login arm left with the Helix adapter at S7/W13, rf2-d6epb; the
 * Hicasso arm arrived with rf2-fmns2).
 *
 * The three login examples — Reagent (`login.core`), UIx
 * (`uix.login.core`) and Hicasso (`hicasso.login.core`) — share ONE
 * substrate-free model owner,
 * `login.model` (examples/core/login/model.cljc): the single source for every
 * `auth.login` schema, fx, machine, event, sub, and the frame config. Each
 * example `:require`s that model and adds only its own views + mount.
 *
 * This gate proves the model stayed substrate-free. Because all three builds
 * import `login.model`, if that namespace ever `:require`d a view library or
 * adapter (Reagent, UIx or Hicasso), the foreign runtime's fingerprint would
 * appear in the login bundles it doesn't belong in. So the binding claim is
 * cross-view-layer absence: each login bundle carries EXACTLY its own view
 * runtime and neither of the other two.
 *
 *   - the Reagent login bundle contains stock-Reagent's reactive-atom
 *     fingerprints and NO UIx spine or Hicasso strings;
 *   - the UIx login bundle contains the UIx spine's gensym-prefix strings and
 *     NO Reagent or Hicasso fingerprints;
 *   - the Hicasso login bundle contains the Hicasso codec's AND adapter's own
 *     markers and NO Reagent or UIx fingerprints.
 *
 * A regression — e.g. a stray `[re-frame.adapter.reagent]` slipping into
 * `login.model` — drags Reagent into all three bundles, so the UIx and
 * Hicasso ABSENT checks fail. That is the substrate-free proof.
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
 *   Hicasso — TWO TIERS, because the artefact ships a view runtime and an
 *     adapter and a bundle can carry either without the other.
 *     `hicassoBoundary` / `rf.error/hicasso-empty-vector` are the SAME two
 *     literals scripts/check-bundle-isolation.cjs already carries for the
 *     `hicasso` artefact, and the choice matters more here than the others'.
 *     Most Hicasso strings would make a FALSE-GREEN sentinel: the package's
 *     complaint machinery folds away under `:advanced` with `goog.DEBUG`
 *     false (hicasso/scripts/check_production_erasure.cjs asserts exactly
 *     that), so a sentinel taken from a dev-guarded refusal is absent from
 *     every bundle INCLUDING one that ships the whole runtime. These two are
 *     that script's own positive controls — proved PRESENT in the
 *     `:hicasso-release` `:advanced` bundle — so they are absent only when
 *     the code is absent. `hicassoBoundary` is the own-property marker
 *     `mark-boundary!` stamps via `unchecked-set` with a literal string key,
 *     ungated; `rf.error/hicasso-empty-vector` is a refusal id minted by
 *     `fail!` on the path every build keeps.
 *     But BOTH live in `re-frame.hicasso.impl.codec` alone, and the codec is
 *     not what a Hicasso application installs as its SUBSTRATE. So the set
 *     also carries `rf-hic-sub-` / `rf-hic-use-sub-`, the gensym prefixes
 *     `re-frame.hicasso.substrate` parameterises the shared spine on —
 *     the exact analogue of the UIx pair. Without them a leak of the
 *     adapter ALONE into the Reagent / UIx login bundles answered ABSENT on
 *     every Hicasso sentinel and the gate passed (rf2-fmns2 audit of #8954).
 *
 * Each view layer's set is checked PRESENT in its own bundle (methodology
 * sanity — proves the grep has signal + the model and views actually compiled
 * in) and ABSENT in the other two (the isolation proof). If a future refactor
 * displaces a sentinel, its own-bundle PRESENT check fails fast rather than
 * letting the cross-bundle ABSENT greps go silently vacuous — re-derive from a
 * sibling literal (Reagent: `Compiler.parse-tag` / `ReagentInput`; UIx: the
 * `-use-sub-` / `-derived-` prefixes, or the substrate-name warning text;
 * Hicasso: for the codec tier, re-read check_production_erasure.cjs's own
 * positive controls, which is where those two came from; for the adapter tier,
 * re-read `re-frame.hicasso.substrate`'s `make-react-spine` call — and keep
 * ONE marker from EACH tier, or the gap this set closed reopens).
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

const HICASSO_SENTINELS = [
  // --- VIEW tier: re-frame.hicasso.impl.codec (the interpreted Hiccup runtime)
  // re-frame.hicasso.impl.codec — mark-boundary!'s own-property marker, set
  // with a literal string key via `unchecked-set` and never goog.DEBUG-gated.
  { source: 're-frame.hicasso.impl.codec mark-boundary! (hicassoBoundary)',
    sentinel: 'hicassoBoundary' },
  // re-frame.hicasso.impl.codec — vector-kind's empty-vector refusal id,
  // minted by `fail!` on the path every build keeps.
  { source: 're-frame.hicasso.impl.codec vector-kind (hicasso-empty-vector)',
    sentinel: 'rf.error/hicasso-empty-vector' },

  // --- ADAPTER tier: re-frame.hicasso.substrate (the standalone adapter)
  // The two above are BOTH codec-only, and the codec is NOT what the login arm
  // installs. `re-frame.hicasso.substrate` — the adapter `hicasso.login.core`
  // passes to `rf/init!` — `:require`s only `react` plus core's
  // `adapter.context` / `frame` / `substrate.spine` / `views.frame-boundary`;
  // it never names the codec or the public `re-frame.hicasso` door. So a
  // codec-only sentinel set answers ABSENT for a bundle carrying the whole
  // Hicasso ADAPTER, and the two ABSENT arms below would have passed while a
  // foreign adapter sat in the Reagent and UIx login bundles. The own-bundle
  // PRESENT arm could not reveal it either: `hicasso.login.core` requires the
  // public door as well, so its bundle carries both tiers regardless.
  //
  // These two close that half, and they are the DIRECT ANALOGUE of the UIx
  // pair above — the same spine, the same key, one substrate over. Same
  // production-stability argument, too: `re-frame.substrate.spine` calls
  // `(gensym gensym-prefix-sub)` per subscription and derives the
  // `use-subscribe` watch-key keyword namespace from `gensym-prefix-use-sub`
  // by `subs` at adapter-construction time, so both reach the `:advanced`
  // bundle as string literals on paths no `goog.DEBUG` guards. The sibling
  // gate hicasso/scripts/check_bundle_isolation.cjs already leans on exactly
  // this for the UIx twin: `rf-uix-sub-` is one of its POSITIVE CONTROLS,
  // proved PRESENT in an `:advanced` release bundle.
  //
  // `rf-hic-` is unique to that one namespace repo-wide, so an occurrence in a
  // Reagent or UIx login bundle can only have come from the Hicasso adapter.
  { source: 're-frame.hicasso.substrate spine subscribe gensym prefix',
    sentinel: 'rf-hic-sub-' },
  { source: 're-frame.hicasso.substrate spine use-subscribe gensym prefix',
    sentinel: 'rf-hic-use-sub-' },
];

// Each login bundle: the view runtime it MUST contain (own) + the two it must NOT.
const BUNDLES = [
  {
    name: 'Reagent login',
    dir: 'login',
    own: { label: 'Reagent', sentinels: REAGENT_SENTINELS },
    foreign: [
      { label: 'UIx',     sentinels: UIX_SENTINELS },
      { label: 'Hicasso', sentinels: HICASSO_SENTINELS },
    ],
  },
  {
    name: 'UIx login',
    dir: 'login-uix',
    own: { label: 'UIx', sentinels: UIX_SENTINELS },
    foreign: [
      { label: 'Reagent', sentinels: REAGENT_SENTINELS },
      { label: 'Hicasso', sentinels: HICASSO_SENTINELS },
    ],
  },
  {
    name: 'Hicasso login',
    dir: 'login-hicasso',
    own: { label: 'Hicasso', sentinels: HICASSO_SENTINELS },
    foreign: [
      { label: 'Reagent', sentinels: REAGENT_SENTINELS },
      { label: 'UIx',     sentinels: UIX_SENTINELS },
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
  report.detail('=== Login cross-view-layer bundle isolation (rf2-ppbvav) ===');
  report.detail('One substrate-free login.model, three builds; each login bundle');
  report.detail('must carry ONLY its own view runtime.');
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
      console.error(`${r.name}: a FOREIGN view runtime leaked into the bundle.`);
      console.error('  The shared substrate-free login.model (examples/core/login/model.cljc)');
      console.error('  appears to have pulled in a view library / adapter — the isolation');
      console.error('  claim (rf2-ppbvav) is broken. Likely cause: a `:require` on');
      console.error('  `reagent.*` / `uix.*` / `re-frame.hicasso.*` or `re-frame.adapter.*`');
      console.error('  slipped into login.model (which every login build imports) or into');
      console.error('  another substrate-agnostic ns it pulls. Keep login.model');
      console.error('  substrate-free — views, roots, adapter init, and mounts belong ONLY');
      console.error('  in each core.cljs.');
    }
    if (!r.ownOk) {
      console.error(`${r.name}: the OWN view-runtime present-check failed — the grep would`);
      console.error('  be vacuous. Either the sentinel strings have moved (a spine / adapter');
      console.error('  / codec refactor) or the build stopped depending on its view layer.');
      console.error('  Re-derive the sentinel set in this script (Reagent: Compiler.parse-tag /');
      console.error('  ReagentInput; UIx: the -use-sub- / -derived- gensym prefixes; Hicasso:');
      console.error('  codec tier — hicasso/scripts/check_production_erasure.cjs\'s own positive');
      console.error('  controls; adapter tier — re-frame.hicasso.substrate\'s make-react-spine');
      console.error('  gensym prefixes. Keep one marker from EACH Hicasso tier: a codec-only');
      console.error('  set answers ABSENT for a bundle carrying the adapter alone.');
    }
  }
  console.error('');
  console.error('Reproduce with:');
  console.error('  cd implementation && shadow-cljs release examples/login \\');
  console.error('    examples/login-uix examples/login-hicasso \\');
  console.error('    && node scripts/check-login-bundle-isolation.cjs');
  process.exit(1);
}

// Checker-owned target contract (rf2-kfn9q): the exact implementation-relative
// runtimes this gate actually isolates. The dedicated-gate binding in
// check-bundle-isolation.cjs requires a runtime's descriptor to name a checker
// whose COVERS_RUNTIMES includes it, so an unrelated existing checker can NOT be
// reused for a new runtime it never inspects. This gate proves the shared
// login.model stays substrate-free across the Reagent / UIx / Hicasso login
// bundles, so it isolates all three view runtimes. (`hicasso` is additionally
// covered by the GENERIC counter-bundle gate in check-bundle-isolation.cjs —
// it is listed here because this checker really does inspect it, not to claim
// a dedicated-gate descriptor it does not have.)
const COVERS_RUNTIMES = ['adapters/reagent', 'adapters/uix', 'hicasso'];

module.exports = { COVERS_RUNTIMES };

// Run only when invoked directly (`node scripts/check-login-bundle-isolation.cjs`),
// not when required as the checker-owned target contract above.
if (require.main === module) {
  main();
}
