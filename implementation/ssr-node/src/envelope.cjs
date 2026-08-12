'use strict';
// THE PRE-REGISTERED CALLER LATENCY ENVELOPE (rf2-hic-056).
//
// These numbers are STATED BEFORE THEY ARE MEASURED, and the commit that
// introduces this file carries no measurement code at all — that is the
// point of the file existing on its own. A ceiling chosen after seeing the
// run is not a ceiling; it is a description of the run wearing a ceiling's
// shape, and the difference is invisible in the final tree. It is visible
// in `git log --follow` on this file, which is the witness offered.
//
// ## What is being bounded, and what deliberately is not
//
// **Bounded: the SERVICE's own overhead.** For one request, that is
//
//     overhead = (wall time from `service.render(req)` to the last frame
//                 of its response being delivered to the caller)
//              - (the render module's own render duration, measured
//                 inside the isolate around the module's `render` call)
//
// so it is admission, validation, the structured-clone crossing in both
// directions, the isolate handshake, and the service's own bookkeeping.
// Everything the service is.
//
// **NOT bounded: the render.** SSR speed is off the bar for this
// programme — HD-012, and `validation.md`'s "never SSR or test-lane
// speed" line — and this file does not quietly re-open it. `renderMs` is
// SUBTRACTED rather than budgeted, so a slow render cannot breach this
// envelope and a fast one cannot pay for a slow service.
//
// ## The conditions the numbers are stated under
//
// A ceiling with no conditions is unfalsifiable, so these are part of the
// registration and not commentary:
//
//   - a WARM pool — every isolate booted and idle before the first sample,
//     because worker startup is a deployment cost and not a per-request one;
//   - a FREE isolate at dispatch, so no sample includes admission queueing
//     behind another request (bounded concurrency is guarantee 3's subject,
//     measured there, and would otherwise be double-counted here);
//   - a request whose `state` is at most 64 KiB of EDN text in total;
//   - sequential requests — one in flight at a time;
//   - Node 24 on one box.
//
// ## Why the ceilings are where they are
//
// The mechanism costs one structured clone of a small object each way plus
// a message hop, which is sub-millisecond work; p50 is set at 5 ms so that
// a breach means something structural changed rather than that the box was
// busy. The upper two are deliberately loose. This repo has already
// measured what a shared developer box does to a millisecond-scale figure:
// the X3 adoption witness published two runs at one commit whose phase
// maxima differed by more than 2x, and recorded that a tenth of a
// millisecond is not reproducible here. A p95 or a max tight enough to be
// impressive would be a gate that reds on other people's compiles, and a
// gate that reds for reasons unrelated to its subject teaches its readers
// to re-run it.
//
// So read these as a SHAPE claim — the service's overhead is single-digit
// milliseconds at the median and never runs away — and never as a
// benchmark to diff a future run against.

const ENVELOPE = Object.freeze({
  /** Median service overhead, milliseconds. */
  p50Ms: 5,
  /** 95th-percentile service overhead, milliseconds. */
  p95Ms: 25,
  /** Worst single sample, milliseconds. */
  maxMs: 250,
  /** How many samples the witness takes. */
  samples: 200,
  /** The total `state` EDN budget the ceilings are stated under. */
  stateBudgetBytes: 64 * 1024,
});

/**
 * Percentile of an ARRAY OF NUMBERS, nearest-rank on the sorted sample.
 *
 * Nearest-rank rather than an interpolating definition because the sample
 * is small and an interpolated p95 of 200 points is a number no single
 * request ever took. A reader who wants to know whether the service was
 * ever slow is better served by a figure that some request actually was.
 */
function percentile(xs, p) {
  if (xs.length === 0) throw new Error('percentile of an empty sample');
  const sorted = [...xs].sort((a, b) => a - b);
  const rank = Math.ceil((p / 100) * sorted.length);
  return sorted[Math.min(sorted.length - 1, Math.max(0, rank - 1))];
}

/**
 * Judge a sample of per-request overheads against the envelope above.
 *
 * Returns `{ ok, p50, p95, max, breaches }` — never throws, because the
 * caller (a witness) wants the figures printed whichever way the verdict
 * went. A breach that prints no number is not a witness.
 */
function judge(overheadsMs, envelope = ENVELOPE) {
  const p50 = percentile(overheadsMs, 50);
  const p95 = percentile(overheadsMs, 95);
  const max = Math.max(...overheadsMs);
  const breaches = [];
  if (p50 > envelope.p50Ms) breaches.push(`p50 ${p50.toFixed(2)} ms > ${envelope.p50Ms} ms`);
  if (p95 > envelope.p95Ms) breaches.push(`p95 ${p95.toFixed(2)} ms > ${envelope.p95Ms} ms`);
  if (max > envelope.maxMs) breaches.push(`max ${max.toFixed(2)} ms > ${envelope.maxMs} ms`);
  return { ok: breaches.length === 0, p50, p95, max, n: overheadsMs.length, breaches };
}

module.exports = { ENVELOPE, percentile, judge };
