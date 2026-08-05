#!/usr/bin/env node
'use strict';
//
// THE MINTED KEY WARNING'S PRODUCTION-ISOLATION PROBE (rf2-2rtt6.104).
//
//     node freehand/test/re_frame/bench/hicasso/keywarn_elision_run.cjs
//
// ## The claim
//
// Every line of the key warning — the carrier object, the owner seam's
// pairing check, the per-member scan and every message string — sits behind
// `^boolean js/goog.DEBUG`. Under `:advanced` with `goog.DEBUG=false` Closure
// folds each gate to `false`, the branches die, and the strings die with
// them. The ordinary render path is byte-identical to what it was.
//
// That is a claim about BYTES, so it is proven with bytes, the F3d way: a
// CONTROL BUILD rather than a grep of source. Two `:advanced` bundles of the
// SAME entry, differing ONLY in `goog.DEBUG`:
//
//   out/keywarn-elision-release   goog.DEBUG=false  — what ships
//   out/keywarn-elision-control   goog.DEBUG=true   — the twin
//
// A string reachable only through the gated branches is a DISCRIMINATOR:
//
//   ABSENT  in the release bundle  (the branch DCE'd — the warning does not ship)
//   PRESENT in the control bundle  (the branch is live — the grep has teeth)
//
// The control's PRESENT half is the positive control, and it is not
// ceremony: `scripts/check-freehand-evidence-elision.cjs` records why the
// naive oracles fail here (a debug flag survived 225 times inside prose;
// Closure inlines a small function's name, so grepping for the name can never
// go red). Without the control leg, a probe that stopped rooting the seam
// entirely — the call sites deleted, the fns orphaned — would pass
// vacuously. With it, that mistake goes red on the control.
//
// ## The entry has to REACH the codec, and the default one does not
//
// `:hicasso-bench`'s baked `:init-fn` is `p0-reagent-app/-main` — the Reagent
// arm, which never requires `front.codec`. A pair built on the default entry
// carries no sentinel in EITHER bundle: the control leg would fail loudly
// rather than pass vacuously, but it would still be a guaranteed red run for
// the wrong reason. Both legs therefore name `jsfb-hicasso-app/-main`, which
// requires `arm1.mount` -> `arm1.runtime` -> `front.codec`.
//
// ## NO EDIT TO shadow-cljs.edn
//
// The pair rides `:hicasso-bench` through `--config-merge`, exactly as
// `compile_gate.cjs` and `jsfb_build.cjs` do, supplying its own `:output-dir`
// and `:closure-defines` per leg. HD-017 makes a new build id a hot-zone edit;
// the lane was built so a sibling never has to pay that. The shared build
// cache is cleared before EACH leg (rf2-2rtt6.20): one build id and two
// configurations is precisely the shape that trap has.
//
// Two `:advanced` releases of one arm are ~2 minutes, which is too heavy for
// the recurring PR spine. This is a runnable-on-demand probe, run once in the
// PR that landed the warning with its output quoted in the PR body. Promoting
// it to a nightly is the mayor's call, not this file's.
//
// Exit 0 on PASS, 1 on FAIL.

const fs = require('node:fs');
const path = require('node:path');
const { shadowBuildVerdict, reportRefusal } = require('./lane_build.cjs');

const IMPL = path.resolve(__dirname, '../../../../..');
const BUILD_ID = 'hicasso-bench';
const INIT_FN = 're-frame.bench.hicasso.jsfb-hicasso-app/-main';
const TAG = 'keywarn-elision';

// DEV-ONLY sentinels. Each is an EXACT runtime string literal from inside a
// `goog.DEBUG` branch — never a fragment spanning a `(str …)` seam, because a
// fragment synthesised across an interpolation greps absent in both bundles
// and the probe would report a pass it never earned. Each must be ABSENT in
// release and PRESENT in the control.
const DEV_ONLY = [
  // `front.codec/warn-member-key!` — the missing-key message's fix clause.
  // One literal in the (str …) that builds the line.
  { source: 'front.codec/warn-member-key! (the missing-key fix clause)',
    sentinel: ' Give each one a :key in its props map —' },
  // The same fn's ENTITY-key branch — an independent gated string, so the
  // probe proves the whole warning is gone rather than one line of it.
  { source: 'front.codec/warn-member-key! (the entity-key coercion clause)',
    sentinel: ' React coerces a key to a string, so a collection keys' },
  // `front.codec/set-lowering-owner!` — the staleness pin. A THIRD independent
  // gated branch, and the WEAKEST of the three: it is protected twice over,
  // because the fn's only callers (`arm1.runtime/run-once`, the two mint
  // stamps) are themselves inside `goog.DEBUG` branches, so under :advanced
  // the fn is unreachable and Closure drops it whole regardless of its own
  // gate. Measured, not assumed — a mutation run that replaced this fn's
  // internal gate with `(when true …)` still came back ABSENT here, and only
  // the mutation at `expand-seq`'s CALL SITE went red. Kept because a third
  // independent string is still evidence the whole feature is gone rather
  // than one line of it, but the load-bearing sentinels are the two above.
  { source: 'front.codec/set-lowering-owner! (the unbalanced-pair pin)',
    sentinel: '[hicasso] A boundary body began lowering while `' },
];

// PROD-SURVIVING sentinels: strings this entry ships regardless of goog.DEBUG.
// The non-vacuity floor for the grep itself — proof the release bundle is a
// real, inspected artefact in which the absences above are a DCE result rather
// than an accident of an empty or broken build.
const PROD_SURVIVING = [
  // `front.codec/vec->element`'s bad-head refusal — a production error path,
  // ungated, in the very namespace the dev-only sentinels come from.
  { source: 'front.codec/vec->element (the bad-head refusal survives :advanced)',
    sentinel: 'is not a valid element head' },
];

function clearBuildCache() {
  const dir = path.join(IMPL, '.shadow-cljs', 'builds', BUILD_ID);
  fs.rmSync(dir, { recursive: true, force: true });
}

function build(leg, debug, outputDir) {
  clearBuildCache();
  // EDN, and ONE LINE: shadow-cljs's CLI re-splits `--config-merge` on
  // whitespace once the data contains a newline, then reports `EOF while
  // reading` from a fragment. JSON is not accepted at all.
  const merge =
    `{:output-dir "${outputDir}" :asset-path "." ` +
    `:compiler-options {:closure-defines {goog.DEBUG ${debug}}} ` +
    `:modules {:main {:init-fn ${INIT_FN}}}}`;
  console.log(`[${TAG}] building ${leg} (goog.DEBUG=${debug}) -> ${outputDir}`);
  const verdict = shadowBuildVerdict({
    impl: IMPL, mode: 'release', buildId: BUILD_ID, configMerge: merge,
  });
  if (!verdict.ok) {
    reportRefusal(TAG, verdict);
    process.exit(1);
  }
  const bundle = path.join(IMPL, outputDir, 'main.js');
  const blob = fs.readFileSync(bundle, 'utf8');
  // The FILE's size and not `blob.length` (rf2-2rtt6.121). `blob` is a
  // decoded string, so `.length` counts UTF-16 code units — and an
  // `:advanced` bundle carries non-ASCII in its string literals, so the two
  // differ. The bytes are already on disk; ask the file system rather than
  // re-derive them from the decoding.
  console.log(`[${TAG}]   ${bundle} — ${fs.statSync(bundle).size} bytes`);
  return blob;
}

function assertSentinels(leg, blob, sentinels, mustContain) {
  let ok = true;
  for (const { source, sentinel } of sentinels) {
    const present = blob.includes(sentinel);
    const pass = present === mustContain;
    if (!pass) ok = false;
    console.log(
      `[${TAG}]   ${pass ? 'ok  ' : 'FAIL'} ${leg}: ` +
      `${mustContain ? 'PRESENT' : 'ABSENT'} expected, ` +
      `${present ? 'present' : 'absent'} found — ${source}`,
    );
  }
  return ok;
}

function main() {
  const release = build('release', false, 'out/keywarn-elision-release');
  const control = build('control', true, 'out/keywarn-elision-control');

  console.log(`\n[${TAG}] the isolation contract`);
  let ok = true;
  ok = assertSentinels('release', release, DEV_ONLY, false) && ok;
  ok = assertSentinels('control', control, DEV_ONLY, true) && ok;
  ok = assertSentinels('release', release, PROD_SURVIVING, true) && ok;
  ok = assertSentinels('control', control, PROD_SURVIVING, true) && ok;

  console.log(
    ok
      ? `\n[${TAG}] PASS — the key warning carries no trace into production, ` +
        'and the control proves the grep has teeth.'
      : `\n[${TAG}] FAIL — see the rows above.`,
  );
  process.exit(ok ? 0 : 1);
}

main();
