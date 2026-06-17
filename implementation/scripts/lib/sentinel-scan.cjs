// Shared sentinel-scan helpers for the bundle check-* gates (rf2-j552l2).
//
// The bundle-isolation / elision / perf / reagent-slim gates all share the
// same two micro-shapes, which had drifted into four near-identical inline
// copies (rf2-u4vtlh / rf2-b4d7o1 review):
//
//   1. A present/absent assertion over a SET of sentinels against an
//      already-read bundle blob. The loop, the per-sentinel pass test, the
//      `{ ok, passed, checked }` tally, and the choice between substring-
//      count detection (countSubstring) and `blob.includes` boolean
//      detection are identical across the four gates. ONLY the per-sentinel
//      diagnostic line differs (indent width, whether it says "sentinel",
//      whether it names a blob label, whether it shows the raw hit-count or
//      a PRESENT/ABSENT word). So `assertSentinelSet` owns the loop + tally
//      and delegates the exact line text to a caller-supplied `formatLine`,
//      keeping every gate's diagnostic output byte-identical while sharing
//      the load-bearing logic.
//
//   2. The classify-or-fail guard each gate runs before scanning: a
//      `classifyReleaseBundle` whose status is anything but 'ok' must reject
//      the run (a 'missing' dir AND a present-but-empty 'empty' dir both
//      false-GREEN an absence-only check — the rf2-utvst non-vacuous floor).
//      `classifyOrFail` centralises that two-arm branch; the actionable
//      stderr text stays caller-supplied (each gate names its own rebuild
//      command).
//
// Neither helper prints anything itself except through the sinks the caller
// passes (`emit` for detail lines, the onMissing/onEmpty callbacks for the
// reject path), so the gate reporters keep full control of buffering /
// flush ordering.

'use strict';

const { classifyReleaseBundle, countSubstring } = require('./read-release-bundle.cjs');

// Assert a set of `{ source, sentinel }` entries against an already-read
// bundle `blob`. Shared by the four bundle check-* gates (rf2-j552l2).
//
//   opts.mustContain : true  ⇒ each sentinel must be PRESENT to pass;
//                      false ⇒ each sentinel must be ABSENT to pass.
//   opts.count       : true  ⇒ detect via countSubstring (exposes the raw
//                              hit-count to the formatter); false ⇒ detect
//                              via blob.includes (boolean present only).
//   opts.emit(line)  : sink for each per-sentinel diagnostic line (e.g. a
//                      gate reporter's `report.detail`). Omit to stay silent.
//   opts.formatLine({ source, sentinel, present, hits, passed, tag })
//                    : returns the EXACT diagnostic string for one sentinel.
//                      `hits` is null in boolean (count:false) mode. `tag`
//                      is 'OK' | 'FAIL'. Omit to emit no per-line text.
//
// Returns `{ ok, passed, checked }` — `ok` is true iff every sentinel
// passed; `passed` is the count that passed; `checked` is sentinels.length.
function assertSentinelSet(blob, sentinels, opts = {}) {
  const { mustContain = false, count = false, emit, formatLine } = opts;
  let ok = true;
  let passed = 0;
  for (const { source, sentinel } of sentinels) {
    const hits = count ? countSubstring(blob, sentinel) : null;
    const present = count ? hits > 0 : blob.includes(sentinel);
    const passed1 = present === mustContain;
    const tag = passed1 ? 'OK' : 'FAIL';
    if (emit && formatLine) {
      emit(formatLine({ source, sentinel, present, hits, passed: passed1, tag }));
    }
    if (passed1) passed += 1;
    else ok = false;
  }
  return { ok, passed, checked: sentinels.length };
}

// Classify a release output dir and branch on the non-'ok' states
// (rf2-utvst non-vacuous floor). On 'missing' calls `onMissing(dir)`; on
// 'empty' calls `onEmpty(dir)` (falling back to onMissing if onEmpty is
// omitted). Returns the classification `{ status, files, blob }` so an 'ok'
// caller can use the already-read blob without re-reading. The reject-path
// callbacks own the actionable stderr text + the exit/return decision.
function classifyOrFail(dir, { onMissing, onEmpty } = {}) {
  const cls = classifyReleaseBundle(dir);
  if (cls.status === 'missing') {
    if (onMissing) onMissing(dir);
  } else if (cls.status === 'empty') {
    if (onEmpty) onEmpty(dir);
    else if (onMissing) onMissing(dir);
  }
  return cls;
}

module.exports = {
  assertSentinelSet,
  classifyOrFail,
};
