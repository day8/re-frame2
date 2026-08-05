#!/usr/bin/env node
'use strict';
// THE PAGE-SIDE BYTE REPAIR IS WIRED — rf2-2rtt6.121.
//
//     node freehand/test/re_frame/bench/hicasso/bench_bytes.test.cjs
//
// Runs in `test:script-helpers`, beside `ssr/bake_bytes.test.cjs` — which is
// this file's sibling and its precedent. `rf2-2rtt6.114` repaired five sites
// in `ssr/driver.cjs` that published `String.prototype.length` as bytes, and
// pinned the repair on the driver's source text for a reason it stated: a
// correct helper nobody calls repairs nothing, so the wiring has to be the
// thing asserted, not just the arithmetic.
//
// ## Why the wiring pins are HERE and the arithmetic is in CLJS
//
// Because they need `fs`, and A LANE NAMESPACE MAY NOT REQUIRE `fs`. Every
// `.cljs` in this directory is compiled by `npm run test:hicasso-compile`,
// which rides `:hicasso-bench` — a BROWSER build — so a Node module in a lane
// namespace refuses all 129 of them. (That is not a hypothetical: this file
// exists because the first draft of `lane_bytes_cljs_test.cljs` carried the
// pins itself and the compile gate refused the lane. The gate was right.
// `ssr/node.cljs` states the rule and `ssr/spike_cljs_test/sha256-hex`
// documents living within it — it reaches for `crypto.subtle` rather than
// `node:crypto` for exactly this reason.)
//
// So the split is by RUNTIME and not by taste:
//
//   lane_bytes_cljs_test.cljs   what `lane/utf8-bytes` computes — browser-safe,
//                               discriminating fixtures, both directions
//   THIS FILE                   that every repaired site calls it — Node, source
//                               text, both polarities
//
// ## What each assertion is defending against
//
// A units repair has two characteristic ways of rotting. The first is a site
// that never got converted, or got converted back. The second is subtler and
// is the one a positive-only pin misses: the new expression added BESIDE the
// old one rather than in place of it, leaving a second `bytes`-labelled
// `count` alive in the same file. So every file is asserted in both
// polarities — the new expression present, AND no line anywhere in the file
// pairing a byte label with a bare `count`.

const fs = require('node:fs');
const path = require('node:path');

const HICASSO = __dirname;

let failed = 0;
let passed = 0;

function test(what, fn) {
  try {
    fn();
    passed++;
  } catch (e) {
    failed++;
    console.error(`FAIL ${what}`);
    console.error(`     ${e && e.message ? e.message : e}`);
  }
}

function assert(cond, msg) {
  if (!cond) throw new Error(msg);
}

/**
 * The source of a lane file, with its existence asserted rather than assumed.
 * A moved file must fail loudly here; reading it as an empty string would turn
 * every pin below into a pin over nothing — the exact fail-open shape this
 * bead is about.
 */
function src(rel) {
  const p = path.join(HICASSO, rel);
  assert(fs.existsSync(p), `the repaired source must be at ${p}`);
  const text = fs.readFileSync(p, 'utf8');
  assert(text.length > 0, `${rel} is empty`);
  return text;
}

// ---------------------------------------------------------------------------
// The converted sites — where the figure is a SIZE
// ---------------------------------------------------------------------------

// file -> the expression that must now be there. Spelled in full, including
// the alignment, so a half-edit that left `(count …)` in one arm of a `#js`
// literal cannot satisfy it.
const CONVERTED = {
  'clock_app.cljs': ':bytes (lane/utf8-bytes s)',
  'hd8_clock_app.cljs': ':bytes   (lane/utf8-bytes s)',
  'shapes/census_clock_app.cljs': ':bytes   (lane/utf8-bytes s)',
  'walk_profile_app.cljs': ':bytes (lane/utf8-bytes canon-real)',
  'walk_vs_reagent_app.cljs': ':bytes    (lane/utf8-bytes canon)',
  'ssr/spike_cljs_test.cljs': ':bytes        (lane/utf8-bytes (:document a))',
  'ssr/spike_dom_cljs_test.cljs': ':canonical-bytes  (lane/utf8-bytes hydrated-dom)',
  'ssr/instance_key_payload_dom_cljs_test.cljs':
    ':green-edn-bytes (lane/utf8-bytes (:payload-edn green))',
};

for (const [file, expr] of Object.entries(CONVERTED)) {
  test(`${file} publishes its byte figure through lane/utf8-bytes`, () => {
    assert(src(file).includes(expr), `${file} must read \`${expr}\``);
  });
}

test('instance_key_payload also converts the RED arm, not just the green one', () => {
  assert(
    src('ssr/instance_key_payload_dom_cljs_test.cljs').includes(
      ':red-edn-bytes   (lane/utf8-bytes (:payload-edn red))',
    ),
    'the red row is half of the obligation witness and is measured the same way',
  );
});

test('NO line in a converted file pairs a bytes label with a bare `count`', () => {
  // The other polarity. `count` is everywhere in this lane and legitimately so
  // — `str-hash` bounds a `charCodeAt` walk with it, and that is correct — so
  // what is banned is narrow: `count` sitting on the SAME LINE as a byte
  // label. That is the shape every one of the eleven repaired sites had.
  const offences = [];
  for (const file of Object.keys(CONVERTED)) {
    src(file)
      .split(/\r?\n/)
      .forEach((line, i) => {
        if (/bytes/i.test(line) && /\(count\b/.test(line)) {
          offences.push(`${file}:${i + 1}: ${line.trim()}`);
        }
      });
  }
  assert(offences.length === 0, `a bytes label over \`count\`:\n  ${offences.join('\n  ')}`);
});

// ---------------------------------------------------------------------------
// The relabelled sites — where CODE UNITS are what was actually wanted
// ---------------------------------------------------------------------------

test('parity_probe states code units, beside the code-unit offset it prints', () => {
  // Its two lengths are read next to `first diff at i`, and `i` is a `.charAt`
  // index. Converting these to bytes would put the length and the offset that
  // locates it on two different rulers — worse than either alone. A true value
  // under a true name is the repair here.
  const probe = src('parity_probe_app.cljs');
  assert(probe.includes('uix-code-units'), 'uix arm relabelled');
  assert(probe.includes('hicasso-code-units'), 'hicasso arm relabelled');
  assert(!probe.includes('uix-bytes'), 'the old bytes claim is gone');
  assert(!probe.includes('hicasso-bytes'), 'the old bytes claim is gone');
});

test('inpage_ladder states code units for its same-against-same refusal', () => {
  // Only ever read as ours-versus-reference on one refusal, and published
  // nowhere. Code units are the honest unit for a difference between two
  // strings.
  const ladder = src('inpage_ladder_app.cljs');
  assert(ladder.includes(':code-units-ours'), 'ours relabelled');
  assert(ladder.includes(':code-units-reference'), 'reference relabelled');
  assert(!ladder.includes(':bytes-ours'), 'the old bytes claim is gone');
  assert(!ladder.includes(':bytes-reference'), 'the old bytes claim is gone');
});

// ---------------------------------------------------------------------------
// The driver-side site
// ---------------------------------------------------------------------------

test('keywarn_elision asks the FILE for its size, not the decoded string', () => {
  // Node, not the page — and the string had just been read off disk, so the
  // file's own size is both the true answer and a second derivation rather
  // than a re-reading of the first. This is `rf2-2rtt6.114`'s bake
  // cross-check, applied to the one driver-side sibling.
  const run = src('keywarn_elision_run.cjs');
  assert(run.includes('${fs.statSync(bundle).size} bytes'), 'the file system answers');
  assert(!run.includes('${blob.length} bytes'), 'the code-unit claim is gone');
});

// ---------------------------------------------------------------------------
// The helper itself
// ---------------------------------------------------------------------------

test('lane/utf8-bytes is TextEncoder, which carries no encoding to drop', () => {
  // `driver.cjs`'s `utf8Bytes` has to name `'utf8'` explicitly and
  // `bake_bytes.test.cjs` pins that spelling, because `Buffer.byteLength`
  // takes an encoding a later edit could silently change. `TextEncoder`
  // cannot be given one: it is UTF-8 or it is nothing. So there is no
  // argument here to pin — and no way to switch the ruler without deleting
  // the call.
  const lane = src('lane.cljs');
  assert(lane.includes('(defn utf8-bytes'), 'the lane exports the helper');
  assert(lane.includes('(js/TextEncoder.)'), 'and builds it from TextEncoder');
  // The CALL, not the word: the docstring names `Buffer.byteLength` in order
  // to say why it is NOT used, so a bare substring search reds the prose.
  assert(!lane.includes('(.byteLength'), 'no Buffer.byteLength call in the lane');
});

test('the lane helper is reachable from every file that claims to use it', () => {
  // A require check, so a converted site cannot read `lane/utf8-bytes` while
  // aliasing some other namespace to `lane`.
  for (const file of Object.keys(CONVERTED)) {
    assert(
      /\[re-frame\.bench\.hicasso\.lane :as lane\]/.test(src(file)),
      `${file} must alias the lane namespace as \`lane\``,
    );
  }
});

// ---------------------------------------------------------------------------

if (failed > 0) {
  console.error(`\nbench_bytes.test.cjs: ${failed}/${failed + passed} failed`);
  process.exit(1);
}
console.log(`bench_bytes.test.cjs: ${passed} passed`);
