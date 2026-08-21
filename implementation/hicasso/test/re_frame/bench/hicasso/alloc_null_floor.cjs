'use strict';
// THE NON-CANCELLATION FLOOR, RE-DERIVED OVER THE WHOLE COMMITTED NULL-ARM
// CORPUS — rf2-0eu1s.
//
//     node hicasso/test/re_frame/bench/hicasso/alloc_null_floor.cjs
//     node hicasso/test/re_frame/bench/hicasso/alloc_null_floor.cjs --tables
//     node hicasso/test/re_frame/bench/hicasso/alloc_null_floor.cjs --self-test
//
// Record: docs/design/hicasso/studio/the-floor-is-two-populations.md
//
// ## WHAT THIS IS AND IS NOT
//
// It is a READER. It launches no browser, builds no bundle, takes no window and
// writes nothing. It reads the committed run records and prints every figure the
// record above publishes; run it and diff the output against them. `--tables`
// prints that page's tables as markdown, so the page is GENERATED rather than
// transcribed.
//
// ## THE QUESTION
//
// The R = 0 arm reads nothing, so `arm − floor` must be zero under either write.
// It is the only allocation population whose true value is known in advance, and
// `rf2-2rtt6.140` published it as the instrument's NON-CANCELLATION FLOOR: a
// median of 1.5 B/boundary, a 90th percentile of 4.5, and a REFUSAL BAR at 45 —
// ten times that p90. `rf2-0eu1s` observes that the fraction of that zero-signal
// population sitting above the bar has risen across three windows, 5.3% then
// 10.0% then 23.2%, and asks for a re-derivation over the WHOLE committed corpus
// rather than one window's 38 cells: the ladder per window and per session, and
// a statement on whether the published triple should be re-cut or whether the
// tail is session-carried.
//
// ## THE ANSWER THIS READER RETURNS, IN ONE LINE
//
// THERE IS NO TAIL. There are TWO DISJOINT POPULATIONS separated by an EMPTY
// GAP, and the published p90 does not measure a magnitude — it reports WHICH OF
// THE TWO the 90th-percentile index happens to land in. Every figure below is a
// consequence of that.
//
// ## AMENDMENT — WHAT rf2-fk6pj's PHASE-4 WINDOW ADDED, 2026-08-21
//
// Everything from here to the end of this header was written over the corpus as
// it stood at 242 cells across 3 windows and 3 sessions, and it is left as
// written because it is the record of that derivation. The corpus is now 569
// cells across 4 windows and 5 sessions. Three things moved, and the self-test
// pins all three at their measured values rather than at these:
//
//   1. THE GAP IS NO LONGER EMPTY. It holds ONE cell of 569 — 38.5 B/boundary,
//      phase 4's run 4 round 5, `reagent-subs | lad/reagent`. At 0.18% the
//      two-population SHAPE is NARROWED AND NOT OVERTURNED (466 below, 102
//      above), but "the gap holds zero" was load-bearing prose here and now has
//      a named exception. The report prints the occupancy and, when it is
//      non-zero, stops claiming that any bar inside the span classifies alike.
//   2. THE OVER-BAR FRACTION STOPPED RISING. The published sequence was
//      5.3% -> 10.0% -> 23.2%; phase 4 reads 17.4%, BELOW phase 3. That is the
//      first datum this corpus has had against a monotone reading, and it
//      weakens "the fraction has risen across windows" to "it varies".
//   3. THE SESSION QUESTION IS NOW PARTLY ANSWERED, and this is the measurement
//      the bead named as missing. Phase 4 is ONE DESIGN HELD STILL ACROSS TWO
//      SESSIONS — the first corpus here where session is not confounded with
//      design and date — and the over-bar fraction reads 17.7% in its first
//      session against 17.2% in its second. ON THIS EVIDENCE THE SECOND
//      POPULATION IS NOT SESSION-CARRIED.
//
// WHAT DID NOT MOVE, and is why the bead's verdicts are untouched here: the
// pooled median is still 0, mode 1's absolute median still 1.5 B/boundary, and
// rounds 0 and 1 still carry NO mode-2 cell at all — now over 85 cells rather
// than 43, so the one internally controlled comparison in this corpus is
// STRENGTHENED. The 1.5 / 4.5 / 45 triple is untouched and the ruling this bead
// waits on is unaffected: nothing above re-cuts a percentile or moves the bar.
// Record: docs/design/hicasso/studio/the-band-on-the-aggregate-and-the-second-session.md
//
// ## WHY THE POOLED p90 CANNOT BE READ AS A MAGNITUDE HERE
//
// Sorted, the 242 committed cells run 0 … 21, then NOTHING AT ALL, then
// 44.5 … 135.5. The gap `[21.5, 44.5)` holds zero cells of 242. So every
// window's quantile ladder is a STEP FUNCTION with exactly one step, and the
// only thing that varies between windows is WHERE the step falls — which is
// `1 − occupancy` and nothing else:
//
//     rf2-0gjqi   p85 = 3      p90 = 4.5    p95 = 56.5   <- step between p90 and p95
//     phase 2     p85 = 7.5    p90 = 56.5                <- step between p85 and p90
//     phase 3     p75 = 19.5   p80 = 54.5                <- step between p75 and p80
//
// No window's ladder has an intermediate rung, because there is no intermediate
// population to have one. `4.5` and `61` are therefore not two points on a
// continuum that "rose": they are the two modes, read by an index that crossed
// the gap between them when occupancy passed roughly one in ten.
//
// ## WHAT ACTUALLY MOVED, AND WHAT DID NOT
//
// The quantity that IS comparable across windows is the FRACTION OVER THE BAR —
// a count, not a percentile — and it moves 5.3% → 10.0% → 23.2%. It is not an
// artefact of the longer runs: phase 3 ran twelve rounds where the earlier two
// ran six, but restricted to the rounds every run has — 0 through 5 — the
// ordering survives at 5.3% → 10.0% → 18.8%.
//
// The CENTRE did not move much. Mode-1's median holds at 1.5 B/boundary and its
// dispersion rises from p90 = 3 to p90 = 9. The instrument still cancels, and
// the mid-rung figures — thousands of bytes per boundary — are read far above
// either mode.
//
// ## THE ONE COMPARISON HERE THAT IS INTERNALLY CONTROLLED
//
// Window, session, calendar date and design are perfectly confounded in this
// corpus: three windows, three sessions, one each. Nothing below can separate
// them, and nothing below tries.
//
// ROUND INDEX is the exception, because every run contributes both early and
// late rounds. Rounds 0–2 carry 2 mode-2 cells of 73; rounds 3 and later carry
// 43 of 169. Rounds 0 and 1 carry NONE AT ALL, over 43 cells. Rounds 1 and 2 are
// near-complete samples — 61 of a possible 64 cells survive certification — so
// this is not the certification gate doing the filtering. Round 0 IS heavily
// decimated (12 of a possible 32) and is reported separately below for that
// reason.
//
// NO MECHANISM IS PROPOSED AND NONE IS EXCLUDED. In particular this reader
// observes that the clean prefix is three rounds long and that `warmups` is 3,
// says that it has not tested whether those are the same 3, and stops there.
//
// ## WHAT THIS MEANS FOR A BAND, WHICH IS THE LIVE CONSUMER
//
// A band built at TEN TIMES THE POOLED NULL-ARM p90 is bistable by construction:
// it returns roughly 45 B/boundary when that window's occupancy is under about
// one in ten and roughly 610 when it is over, with nothing in between available
// for it to return. `rf2-fk6pj`'s phase-3 window built exactly that band, got
// 610 B/boundary, and refused a term whose implied delta was 37.8.
//
// THE REFUSAL STANDS EITHER WAY, and this reader is not an argument for reading
// past it. Ten times MODE-1's p90 is 75 B/boundary over the whole corpus and 90
// on phase 3's own null arm; 37.8 is below both. What the correction changes is
// the band's SCALE — a factor of eight — not that window's verdict.

const fs = require('fs');
const path = require('path');
const { roundCells, NULL_RUNG } = require('./alloc_pass_position.cjs');

const DATA = path.join(__dirname, 'data');

// The published triple, `rf2-2rtt6.140`.
const PUBLISHED = { median: 1.5, p90: 4.5, barB: 45 };

// The gap. Both edges are OBSERVED rather than chosen: 21 is the largest cell
// below it and 44.5 the smallest above, over all 242. `MODE2_FLOOR` is what
// splits the populations; `PUBLISHED.barB` is what the floor's consumers refuse
// on. They are deliberately separate constants — the whole finding below is that
// the second lands inside the empty span around the first, so a reader that
// collapsed them could not state it.
const MODE1_CEIL_B = 21;
const MODE2_FLOOR_B = 44.5;

// The term `rf2-fk6pj`'s phase-3 window refused, quoted from its record so that
// section G's "the refusal stands either way" is checked arithmetic rather than
// a recollection. It is that window's figure, not one derived here.
const FK6PJ_IMPLIED_DELTA_B = 37.8;

// THE CORPUS, PINNED. Discovery finds every committed run carrying a null-arm
// cell; this list says which ones were READ when the record was written, and the
// self-test fails if discovery and this list disagree.
//
// THAT FAILURE IS THE POINT AND IT IS NOT A NAG. This bead exists because a
// published floor stopped describing the corpus and no gate noticed for two
// windows. A new null-arm window is a one-line addition here plus a re-read of
// the ladder, which is exactly the ten seconds nobody spent.
const WINDOWS = [
  {
    window: 'rf2-0gjqi',
    design: 'parity, 2 x 6 rounds',
    runs: ['alloc-0gjqi/paired-run1.json', 'alloc-0gjqi/paired-run2.json'],
  },
  {
    window: 'phase 2',
    design: 'seeded, 2 x 6 rounds',
    runs: ['alloc-fk6pj/seeded-run1.json', 'alloc-fk6pj/seeded-run2.json'],
  },
  {
    window: 'phase 3',
    design: 'seeded, 4 x 12 rounds',
    runs: [
      'alloc-passterm/run1.json',
      'alloc-passterm/run2.json',
      'alloc-passterm/run3.json',
      'alloc-passterm/run4.json',
    ],
  },
  {
    // ADDED BY rf2-fk6pj's PHASE-4 WINDOW, which is the first corpus here to
    // hold ONE DESIGN ACROSS TWO SESSIONS — the measurement this bead named as
    // missing, since every earlier window changed design and session together.
    // It roughly triples the corpus and it moves one pinned finding, which is
    // recorded at the check rather than smoothed over: see the empty-span
    // check in `selfTest`.
    window: 'phase 4',
    design: 'seeded, 8 x 12 rounds, 2 sessions',
    runs: [
      'alloc-legorder/run1.json',
      'alloc-legorder/run2.json',
      'alloc-legorder/run3.json',
      'alloc-legorder/run4.json',
      'alloc-legorder/run5.json',
      'alloc-legorder/run6.json',
      'alloc-legorder/run7.json',
      'alloc-legorder/run8.json',
    ],
  },
];

// --- the corpus, discovered rather than assumed ------------------------------

// Every committed run record under `data/`, as `{ rel, doc }`.
function allRecords() {
  const out = [];
  for (const d of fs.readdirSync(DATA).sort()) {
    const dir = path.join(DATA, d);
    if (!fs.statSync(dir).isDirectory()) continue;
    for (const f of fs.readdirSync(dir).sort()) {
      if (!f.endsWith('.json')) continue;
      let doc;
      try {
        doc = JSON.parse(fs.readFileSync(path.join(dir, f), 'utf8'));
      } catch {
        continue;
      }
      if (doc && doc.alloc) out.push({ rel: `${d}/${f}`, doc });
    }
  }
  return out;
}

// A run carries null-arm cells only if it has the R = 0 LADDER ARMS — which is
// what the full plan records and the floor plan does not — AND two write legs,
// because `Δ = d_page − d_all` is a difference and a single-leg run has nothing
// to difference against. Every excluded run in this corpus fails one of those
// two, BY CONSTRUCTION rather than by any judgement here, and `discover` names
// which so the self-test can insist on it:
//
//   `floor-plan`  — records the `grid/floor` arm alone, so there is no R = 0 arm.
//   `single-leg`  — has the R = 0 arms but writes them under one leg, its window
//                   keys carrying no `@leg` suffix at all. `alloc-2rtt6-138` is
//                   the corpus's one such run, from before the paired write
//                   existed. THE R = 0 ARM IS THEREFORE OLDER THAN THE Δ
//                   STATISTIC PUBLISHED OVER IT, and that run is not evidence
//                   about this floor either way.
function exclusionReason(alloc) {
  const keys = new Set();
  for (const r of alloc.perRound || []) for (const k of Object.keys(r.arms || {})) keys.add(k);
  const hasNullArm = [...keys].some((k) => k.includes(`#${NULL_RUNG}`));
  if (!hasNullArm) return 'floor-plan';
  if (![...keys].some((k) => k.includes('@'))) return 'single-leg';
  return null;
}

function discover() {
  const carrying = [];
  const excluded = [];
  for (const { rel, doc } of allRecords()) {
    const a = doc.alloc;
    const cells = nullCells(a);
    if (cells.length) carrying.push({ rel, doc, n: cells.length });
    else excluded.push({ rel, reason: exclusionReason(a) });
  }
  return { carrying, excluded };
}

// --- the population ----------------------------------------------------------

// Every certified null-arm cell of one run, tagged with its round. `roundCells`
// is imported rather than reimplemented so that this reader measures the same
// population `alloc_pass_position.cjs` does, and rots with it if it changes.
function nullCells(alloc) {
  const out = [];
  for (const round of alloc.perRound || []) {
    for (const c of roundCells(round, alloc.boundaries, [NULL_RUNG])) {
      out.push({ round: round.round, abs: Math.abs(c.delta), delta: c.delta, segment: c.segment, arm: c.arm });
    }
  }
  return out;
}

const median = (xs) => {
  if (!xs.length) return null;
  const s = [...xs].sort((a, b) => a - b);
  const m = s.length >> 1;
  return s.length % 2 ? s[m] : (s[m - 1] + s[m]) / 2;
};

// THE SHIPPED QUANTILE CONVENTION, copied deliberately from `nullArm` in
// `alloc_pass_position.cjs`: the value at 0-indexed rank `floor(n·p)`, clamped.
// Comparability with the published figures is the entire point of this reader,
// so a better-behaved interpolating quantile would make every number below
// incomparable with the one it is being read against.
const quantile = (xs, p) => {
  if (!xs.length) return null;
  const s = [...xs].sort((a, b) => a - b);
  return s[Math.min(s.length - 1, Math.floor(s.length * p))];
};

const LADDER = [0.5, 0.75, 0.8, 0.85, 0.9, 0.95];

// The ladder for one population of cells.
function ladder(cells) {
  const abs = cells.map((c) => c.abs);
  const over = abs.filter((x) => x > PUBLISHED.barB);
  const mode1 = abs.filter((x) => x < MODE2_FLOOR_B);
  const mode2 = abs.filter((x) => x >= MODE2_FLOOR_B);
  return {
    n: abs.length,
    median: median(cells.map((c) => c.delta)),
    absMedian: median(abs),
    q: LADDER.map((p) => quantile(abs, p)),
    max: abs.length ? Math.max(...abs) : null,
    overBar: over.length,
    overBarPct: abs.length ? (100 * over.length) / abs.length : null,
    // Occupancy is stated against the OBSERVED gap rather than against the
    // published bar, so the shape statistic does not inherit the bar's
    // derivation. The two differ by the single cell at 44.5.
    mode2n: mode2.length,
    occupancyPct: abs.length ? (100 * mode2.length) / abs.length : null,
    mode1: {
      n: mode1.length,
      median: median(mode1),
      p90: quantile(mode1, 0.9),
      p95: quantile(mode1, 0.95),
      max: mode1.length ? Math.max(...mode1) : null,
    },
  };
}

// The step: the lowest ladder rung whose value is already in mode 2, or null
// where the ladder never crosses. This is the statistic that makes the published
// p90 legible — it is a rank, not a magnitude.
function step(l) {
  for (let i = 0; i < LADDER.length; i++) if (l.q[i] !== null && l.q[i] >= MODE2_FLOOR_B) return LADDER[i];
  return null;
}

// THE SHAPE OF THE CORPUS AGAINST THE BAR — DERIVED ONCE, PRINTED TWICE.
// Section A and THE VERDICT four hundred lines of output below it both state
// the span's occupancy, the bar's position relative to the span, and how far
// the bar's partition is from the two-population one. They used to state them
// SEPARATELY: A read the cells, the verdict carried literals written when the
// corpus was 242 cells and the span really did hold none. When phase 4 put a
// cell in the span, A said `1 cells` and the verdict went on saying
// `zero of ${all.length}` — the wrong count wearing the right corpus size — so
// one run of one program contradicted itself in two places (rf2-2iaph). Both
// now read this object, and can no longer disagree without disagreeing with
// the cells.
function shape(all) {
  const pooled = ladder(all);
  return {
    n: all.length,
    // STRICTLY BELOW THE SPAN, which is not `ladder`'s mode-1 population: that
    // one is everything under the mode-2 floor and so includes the span's own
    // cell. Section A's three counts must partition the corpus, so this is the
    // narrow one.
    mode1n: all.filter((c) => c.abs <= MODE1_CEIL_B).length,
    spanN: all.filter((c) => c.abs > MODE1_CEIL_B && c.abs < MODE2_FLOOR_B).length,
    mode2n: pooled.mode2n,
    overBar: pooled.overBar,
    spanWidth: MODE2_FLOOR_B - MODE1_CEIL_B,
    // SIGNED, so that no sentence below has to carry the direction as a word:
    // positive is above the span's upper edge, negative is inside the span.
    barOffset: PUBLISHED.barB - MODE2_FLOOR_B,
    // The cells the two partitions disagree about, counted DIRECTLY rather than
    // by differencing the two totals — which gives the self-test a second and
    // independent derivation of the same number to hold this one against.
    splitDiff: all.filter((c) => (c.abs > PUBLISHED.barB) !== (c.abs >= MODE2_FLOOR_B)).length,
    splitDiffAt: [...new Set(all.filter((c) => (c.abs > PUBLISHED.barB) !== (c.abs >= MODE2_FLOOR_B))
      .map((c) => c.abs))].sort((a, b) => a - b),
  };
}

// --- the corpus, assembled ---------------------------------------------------

// `{ window, design, run, rel, session, cells }` per committed run, in the
// pinned order. A run's SESSION is read from `box.session.sessionStartedAt`
// where the record carries one. `rf2-0gjqi` predates that field entirely, so its
// session is INFERRED from the window and marked as inferred — a reader that
// silently invented a session id for it would be asserting exactly the thing
// this bead cannot establish.
function corpus() {
  const out = [];
  for (const w of WINDOWS) {
    w.runs.forEach((rel, i) => {
      const doc = JSON.parse(fs.readFileSync(path.join(DATA, rel), 'utf8'));
      const a = doc.alloc;
      const recorded = ((doc.box || {}).session || {}).sessionStartedAt || null;
      out.push({
        window: w.window,
        design: w.design,
        run: `run ${i + 1}`,
        rel,
        rounds: a.rounds,
        generatedAt: doc.generatedAt,
        session: recorded || `(inferred: ${w.window})`,
        sessionRecorded: Boolean(recorded),
        controlOk: ((a.controlVerdict || {}).ok) === true,
        perDouble: (a.controlVerdict || {}).perDouble,
        unverified: (a.verification || {}).unverified,
        cells: nullCells(a),
      });
    });
  }
  return out;
}

const cellsOf = (rows) => [].concat(...rows.map((r) => r.cells));

// Group the corpus by a key, preserving first-seen order.
function groupBy(rows, key) {
  const m = new Map();
  for (const r of rows) {
    const k = key(r);
    if (!m.has(k)) m.set(k, []);
    m.get(k).push(r);
  }
  return m;
}

// --- the report --------------------------------------------------------------

const f1 = (x) => (x === null || x === undefined ? 'n/a' : x.toFixed(1));
const num = (x) => (x === null || x === undefined ? 'n/a' : String(x));

// WHERE THE BAR SITS RELATIVE TO THE SPAN, IN WORDS RENDERED FROM THE OFFSET.
// "half a byte above" is the wording the record settled on, and it is
// ARITHMETIC rather than a figure of speech — 45 − 44.5 = 0.5 — so it is
// rendered here rather than typed. The third rendering is the point: a bar that
// fell inside the span would SAY SO, in this reader's own voice. The check this
// replaced forbade the literal `LANDS INSIDE THAT GAP` in a program that had no
// way to emit it, and so could only ever agree with itself.
const barPosition = (d) => (d > 0
  ? `sits ${d === 0.5 ? 'half a byte' : `${f1(d)} B`} above`
  : d < 0 ? `lands ${f1(-d)} B inside`
    : 'sits on the upper edge of');

function report() {
  const rows = corpus();
  const all = cellsOf(rows);
  const pooled = ladder(all);
  // Section A and THE VERDICT both print from this. See `shape`.
  const sh = shape(all);
  const out = [];
  const L = (s) => out.push(s === undefined ? ';;' : `;; ${s}`.trimEnd());

  L('THE NON-CANCELLATION FLOOR, RE-DERIVED OVER THE WHOLE COMMITTED NULL-ARM CORPUS (rf2-0eu1s)');
  L();
  L(`PUBLISHED, rf2-2rtt6.140: median ${PUBLISHED.median} B/boundary, p90 ${PUBLISHED.p90}, ` +
    `refusal bar ${PUBLISHED.barB} at ten times that p90.`);
  L();

  L('THE CORPUS. Every committed run that carries a null-arm cell, and there are no others.');
  L('  window | design | run | rounds | session | control | B/double | unverified | cells');
  for (const r of rows) {
    L(`  ${r.window} | ${r.design} | ${r.run} | ${r.rounds} | ${r.session} | ` +
      `${r.controlOk ? 'OK' : 'FAILED'} | ${r.perDouble === undefined ? 'n/a' : r.perDouble.toFixed(2)} | ` +
      `${num(r.unverified)} | ${r.cells.length}`);
  }
  L(`  TOTAL ${all.length} cells over ${rows.length} runs, ${WINDOWS.length} windows, ` +
    `${groupBy(rows, (r) => r.session).size} sessions.`);
  L();
  // WINDOW AND SESSION, COUNTED OFF THE RUNS RATHER THAN ASSERTED — rf2-oflj7.
  // This paragraph said `each window is exactly one session, so the two tables
  // would be the same table` from three lines below a TOTAL that had been
  // printing `4 windows, 5 sessions` ever since phase 4 folded in. It was true
  // of the corpus it was written on — 3 windows, 3 sessions, one each — and
  // nothing re-read it afterwards, because nothing was watching it: the claim
  // carried no check of any kind, so one run of one program stated a partition
  // and then contradicted it three lines up. The counts below come off the same
  // `rows` the TOTAL line counts, and the sentence is SELECTED by them rather
  // than typed, so the two cannot part company again.
  const windowSessions = [...groupBy(rows, (r) => r.window)]
    .map(([w, rs]) => [w, groupBy(rs, (r) => r.session).size]);
  const spanning = windowSessions.filter(([, n]) => n > 1);
  const spanningPhrase = spanning.map(([w, n]) => `${w} carries ${n}`).join(', ');
  if (spanning.length === 0) {
    L(`WINDOW AND SESSION ARE THE SAME PARTITION HERE: ${windowSessions.length} windows, one session each. The bead`);
    L('asks for the ladder per window AND per session; in this corpus the two tables would be the');
    L('same table. That is not a convenience — it is the reason nothing below can attribute the');
    L('movement to the session rather than to the design, the round count or the date.');
  } else {
    L(`WINDOW AND SESSION ARE NO LONGER THE SAME PARTITION: ${windowSessions.length} windows over ` +
      `${groupBy(rows, (r) => r.session).size} sessions. The bead`);
    L(`asks for the ladder per window AND per session; ${windowSessions.length - spanning.length} of the ` +
      `${windowSessions.length} windows carry one session each, so`);
    L(`for those the two tables would be the same table, and ${spanningPhrase}. Every table below`);
    L('groups by WINDOW and pools the sessions of any window holding more than one. Where a window');
    L('is one session, nothing below can tell the session from the design, the round count or the date.');
  }
  L();

  L('A. THE SHAPE, WHICH GOVERNS EVERY LADDER BELOW: TWO POPULATIONS, NOT ONE TAIL.');
  L(`  mode 1, at or below ${MODE1_CEIL_B} B/boundary : ${sh.mode1n} cells`);
  L(`  the gap, (${MODE1_CEIL_B}, ${MODE2_FLOOR_B})              : ${sh.spanN} cells`);
  L(`  mode 2, at or above ${MODE2_FLOOR_B} B/boundary : ${sh.mode2n} cells`);
  L(`  Both edges are observed, not chosen: ${MODE1_CEIL_B} is the largest cell below the gap and`);
  L(`  ${MODE2_FLOOR_B} the smallest above it, over all ${all.length}.`);
  L(`  THE PUBLISHED BAR AT ${PUBLISHED.barB} ${barPosition(sh.barOffset).toUpperCase()} THAT SPAN.`);
  if (sh.spanN === 0) {
    L(`  Any bar in (${MODE1_CEIL_B}, ${MODE2_FLOOR_B}] classifies all ${all.length} cells identically; the published one differs`);
  } else {
    // PHASE 4 PUT A CELL IN THE SPAN, so the "any bar in the span is the same
    // bar" sentence is no longer true and is not printed. It was true of the
    // 242 cells this reader was written on and is false of the corpus now:
    // a bar below a cell sitting inside the span classifies that cell into
    // mode 2, and a bar above it does not. The two-population SHAPE is
    // narrowed rather than overturned — the span still holds a fraction of a
    // percent of the corpus — but "identically" was an absolute claim and it
    // has an exception, so the reader states the exception instead.
    const pct = ((sh.spanN / all.length) * 100).toFixed(2);
    L(`  THE SPAN IS NO LONGER EMPTY: ${sh.spanN} of ${all.length} cells (${pct}%) sit inside it, so bars within`);
    L(`  (${MODE1_CEIL_B}, ${MODE2_FLOOR_B}] no longer all classify alike. The shape is narrowed, not overturned —`);
    L(`  ${sh.mode1n} cells below and ${sh.mode2n} above against ${sh.spanN} between. The published one differs`);
  }
  L(`  from that partition on EXACTLY ${sh.splitDiff} of ${all.length} cells, at ${sh.splitDiffAt.join(', ')} B/boundary,`);
  L(`  which it counts ${sh.barOffset > 0 ? 'below' : 'above'} the bar. So the bar's VALUE is robust however its derivation`);
  L('  behaved — and so are the two counts below, which differ by those cells and by nothing else:');
  L(`    over the ${PUBLISHED.barB} B bar : ${pooled.overBar}/${all.length}      in mode 2 : ${pooled.mode2n}/${all.length}`);
  L('  Every cross-window comparison below is stated OVER THE BAR, the basis the published');
  L('  figures use. Mode-2 membership is used only for the shape and for mode 1\'s own ladder.');
  L();

  L('B. THE LADDER, PER RUN. Absolute Δ in B/boundary. `step` is the lowest rung already in mode 2.');
  L('  window | run | n | med Δ | |med| | p50 | p75 | p80 | p85 | p90 | p95 | max | step | over bar');
  for (const r of rows) {
    const l = ladder(r.cells);
    L(`  ${r.window} | ${r.run} | ${l.n} | ${num(l.median)} | ${num(l.absMedian)} | ` +
      `${l.q.map(num).join(' | ')} | ${num(l.max)} | ${step(l) === null ? '—' : 'p' + step(l) * 100} | ` +
      `${l.overBar}/${l.n} (${f1(l.overBarPct)}%)`);
  }
  L();

  // THE SAME CLAIM AS THE PARAGRAPH ABOVE, IN FOUR WORDS, AND IT WENT STALE
  // WITH IT — rf2-oflj7. Rendered from the same grouping, so the heading and the
  // paragraph cannot disagree, and when a window does span it is NAMED here
  // rather than glossed: a reader looking at this table needs to know which row
  // pools two sessions.
  L(`C. THE LADDER, PER WINDOW — which is ${spanning.length === 0 ? 'also per session'
    : `NOT also per session: ${spanningPhrase}`}, see above.`);
  L('  window | n | med Δ | |med| | p50 | p75 | p80 | p85 | p90 | p95 | max | step | over bar');
  for (const [w, rs] of groupBy(rows, (r) => r.window)) {
    const l = ladder(cellsOf(rs));
    L(`  ${w} | ${l.n} | ${num(l.median)} | ${num(l.absMedian)} | ${l.q.map(num).join(' | ')} | ` +
      `${num(l.max)} | ${step(l) === null ? '—' : 'p' + step(l) * 100} | ${l.overBar}/${l.n} (${f1(l.overBarPct)}%)`);
  }
  L(`  POOLED | ${pooled.n} | ${num(pooled.median)} | ${num(pooled.absMedian)} | ` +
    `${pooled.q.map(num).join(' | ')} | ${num(pooled.max)} | p${step(pooled) * 100} | ` +
    `${pooled.overBar}/${pooled.n} (${f1(pooled.overBarPct)}%)`);
  L();
  L('  NO WINDOW HAS AN INTERMEDIATE RUNG. Every ladder steps straight from a mode-1 value to a');
  L('  mode-2 one, because there is no intermediate population for a rung to sit in. The published');
  // PHASE 3'S p90, READ OFF PHASE 3 — rf2-oflj7. `61` was TYPED here, and it is
  // the one figure in this paragraph the corpus can move: a window's percentile
  // rather than a published constant. It still reads 61, which is exactly how a
  // hardcoded figure survives a fold-in unnoticed — phase 4's arrival moved the
  // pooled p90 two rows above and left this one where it was, so nothing on
  // screen distinguished a number that had been re-read from one merely
  // re-printed. The paragraph's `therefore` rests on the two figures lying in
  // DIFFERENT modes, so that comparison selects the sentence now, and the arm
  // for a corpus that put them on the same side of the floor is emittable
  // rather than unthinkable.
  const w3p90 = ladder(cellsOf(rows.filter((r) => r.window === 'phase 3'))).q[4];
  if (PUBLISHED.p90 < MODE2_FLOOR_B && w3p90 >= MODE2_FLOOR_B) {
    L(`  p90 of ${PUBLISHED.p90} and phase 3's p90 of ${num(w3p90)} are therefore the two MODES, read by an index that`);
    L('  crossed the gap when occupancy passed roughly one in ten — not two points on a continuum.');
  } else {
    L(`  p90 of ${PUBLISHED.p90} and phase 3's p90 of ${num(w3p90)} are NO LONGER ONE MODE EACH: both now fall on the`);
    L(`  same side of the ${MODE2_FLOOR_B} B/boundary floor, so this pair no longer shows the published p90 to be`);
    L('  a RANK rather than a magnitude, and the reading above wants re-deriving before it is quoted.');
  }
  L();

  L('D. WHAT IS COMPARABLE ACROSS WINDOWS: THE OVER-BAR FRACTION, not any percentile.');
  L('  window | over the bar | mode-1 n | mode-1 median | mode-1 p90 | mode-1 p95 | mode-1 max');
  for (const [w, rs] of groupBy(rows, (r) => r.window)) {
    const l = ladder(cellsOf(rs));
    L(`  ${w} | ${l.overBar}/${l.n} (${f1(l.overBarPct)}%) | ${l.mode1.n} | ${num(l.mode1.median)} | ` +
      `${num(l.mode1.p90)} | ${num(l.mode1.p95)} | ${num(l.mode1.max)}`);
  }
  L(`  POOLED | ${pooled.overBar}/${pooled.n} (${f1(pooled.overBarPct)}%) | ${pooled.mode1.n} | ` +
    `${num(pooled.mode1.median)} | ${num(pooled.mode1.p90)} | ${num(pooled.mode1.p95)} | ${num(pooled.mode1.max)}`);
  L();
  // FIRST WINDOW TO LAST, TAKEN FROM THE FIRST AND LAST WINDOWS — rf2-oflj7.
  // This sentence named its own frame as first-window-to-last in one clause and
  // then read `its p90 rises from 3 to 9` in the next. 9 is PHASE 3's, which was
  // the last window when the sentence was written; the last window is phase 4
  // and its mode-1 p90 is 4.5, two rows above in the very table this sentence
  // summarises. The endpoints come off the ladders now, and so does the
  // DIRECTION — a sentence that says `rises` about a fall is the same defect one
  // step on, and `a twentieth of the movement the pooled p90 reports` is gone in
  // favour of the two movements themselves, which are numbers a reader can check
  // against the table rather than a ratio nobody can reconstruct.
  const byWindow = [...groupBy(rows, (r) => r.window)].map(([, rs]) => ladder(cellsOf(rs)));
  const wFirst = byWindow[0];
  const wLast = byWindow[byWindow.length - 1];
  const moved = (a, b) => (b > a ? 'rises' : b < a ? 'falls' : 'holds');
  L(`  THE CENTRE HELD AND THE OCCUPANCY MOVED. Mode 1's median is ${num(wFirst.mode1.median)} B/boundary in the first`);
  L(`  window and ${num(wLast.mode1.median)} in the last; its p90 ${moved(wFirst.mode1.p90, wLast.mode1.p90)} from ` +
    `${num(wFirst.mode1.p90)} to ${num(wLast.mode1.p90)} over the same two`);
  L(`  windows, a movement of ${num(Math.abs(wLast.mode1.p90 - wFirst.mode1.p90))} against the ` +
    `${num(Math.abs(wLast.q[4] - wFirst.q[4]))} the per-window p90 reports across them.`);
  L();

  L('E. THE ROUND-INDEX STRUCTURE — the one comparison here that is INTERNALLY CONTROLLED,');
  L('   because every run contributes both early and late rounds.');
  L('  round | cells | in mode 2 | share');
  const byRound = groupBy(all.map((c) => ({ c, k: c.round })), (r) => r.k);
  for (const [rd, cs] of [...byRound.entries()].sort((a, b) => a[0] - b[0])) {
    const cells = cs.map((x) => x.c);
    const o = cells.filter((c) => c.abs >= MODE2_FLOOR_B).length;
    L(`  ${String(rd).padStart(2)} | ${cells.length} | ${o} | ${f1((100 * o) / cells.length)}%`);
  }
  const seg = (pred) => {
    const cs = all.filter(pred);
    const o = cs.filter((c) => c.abs >= MODE2_FLOOR_B).length;
    return `${o}/${cs.length} (${f1((100 * o) / cs.length)}%)`;
  };
  L(`  rounds 0-2 : ${seg((c) => c.round <= 2)}      rounds 3+ : ${seg((c) => c.round >= 3)}`);
  L(`  rounds 1-2 alone, a near-complete sample : ${seg((c) => c.round === 1 || c.round === 2)}`);
  L(`  round 0 alone, heavily decimated by certification : ${seg((c) => c.round === 0)}`);
  L('  The clean prefix is three rounds long and `warmups` is 3 in every run. THIS READER HAS NOT');
  L('  TESTED WHETHER THOSE ARE THE SAME THREE, and proposes no mechanism for the structure.');
  L();

  L('F. THE COMMON-SUPPORT CONTROL. Phase 3 ran twelve rounds where the earlier windows ran six,');
  L('   so the round structure in E is a candidate explanation for the window ordering. Restricting');
  // `WHICH ALL EIGHT RUNS HAVE` OUTLIVED THE FOLD-IN TO SIXTEEN — rf2-oflj7, and
  // the report prints the true count itself, forty lines up. Both the count and
  // the word `all` are derived now: `all` is warranted only while every run in
  // the corpus really does carry rounds 0-5, and if one does not the sentence
  // says how many do instead of overstating its own support.
  const with05 = rows.filter((r) => r.rounds >= 6).length;
  L(`   every window to rounds 0-5, which ${with05 === rows.length ? `all ${rows.length} runs`
    : `${with05} of the ${rows.length} runs`} have, removes it.`);
  L('  window | over the bar, all rounds | over the bar, rounds 0-5 only');
  for (const [w, rs] of groupBy(rows, (r) => r.window)) {
    const cs = cellsOf(rs);
    const l = ladder(cs);
    const lc = ladder(cs.filter((c) => c.round <= 5));
    L(`  ${w} | ${l.overBar}/${l.n} (${f1(l.overBarPct)}%) | ${lc.overBar}/${lc.n} (${f1(lc.overBarPct)}%)`);
  }
  L('  THE ORDERING SURVIVES, so the longer runs do not explain it. What remains is confounded');
  L('  four ways at once — window, session, design and date — and this corpus cannot apportion it.');
  L();

  const w0 = ladder(cellsOf(rows.filter((r) => r.window === 'rf2-0gjqi')));
  const w3 = ladder(cellsOf(rows.filter((r) => r.window === 'phase 3')));
  L('G. WHAT A BAND SHOULD REST ON, which is the live consumer.');
  L(`  Ten times the POOLED p90 is bistable: ${10 * w0.q[4]} B/boundary on the window the published`);
  L(`  bar came from, ${10 * w3.q[4]} on phase 3, ${10 * pooled.q[4]} over the whole corpus — with nothing in between`);
  L('  available for it to return, because the statistic it multiplies has nothing in between to');
  L('  take. A band built that way records which mode its own null arm landed in, and no more.');
  // THE REFUSAL CLAUSE IS SELECTED BY THE COMPARISON IT ASSERTS — rf2-oflj7.
  // `which is below both, SO THAT REFUSAL STANDS EITHER WAY` was unconditional
  // prose about two DERIVED bands, and the only thing standing behind it was a
  // self-test asking whether that string was present — which it was, and would
  // have gone on being had a window taken either band under the refused delta.
  // It has already survived one change in the numbers it talks about without
  // anyone checking that it had: the bands read 75 and 90 when it was written
  // and read 60 and 90 now. Both arms below are emittable, and the count of
  // bands above the delta chooses between them.
  const bands = [10 * pooled.mode1.p90, 10 * w3.mode1.p90];
  const bandsAbove = bands.filter((b) => b > FK6PJ_IMPLIED_DELTA_B).length;
  L(`  Ten times MODE-1's p90 is ${bands[0]} B/boundary over the whole corpus and ` +
    `${bands[1]} on phase 3's own null`);
  if (bandsAbove === bands.length) {
    L(`  arm. rf2-fk6pj refused a term whose implied delta was ${FK6PJ_IMPLIED_DELTA_B}, which is below both, SO`);
    L('  THAT REFUSAL STANDS EITHER WAY. The correction changes the band\'s scale, not its verdict.');
  } else {
    L(`  arm. rf2-fk6pj refused a term whose implied delta was ${FK6PJ_IMPLIED_DELTA_B}, which is below ${bandsAbove} of those`);
    L('  two bands and not both, SO THAT REFUSAL NO LONGER STANDS EITHER WAY — which band is taken');
    // Deliberately NOT the p90 verdict's `THAT IS A RULING, NOT THIS READER'S
    // CALL` wording: that literal is pinned elsewhere in the self-test and a
    // second copy of it would make the pin ambiguous about which line it read.
    L('  now decides it, and that is a ruling rather than this reader\'s call.');
  }
  L();

  L('THE VERDICT, in the three parts the bead asks for.');
  // THE VERDICT'S MEDIAN CLAUSE, OFF THE TWO WINDOWS SECTION D ALREADY READS —
  // rf2-oflj7. D says `Mode 1's median is 1.5 B/boundary in the first window and
  // 1.5 in the last` and takes both endpoints off the ladders; this line said
  // the same sentence in literals a hundred lines below it. That is the shape
  // rf2-2iaph found between section A and the verdict's span clause — one
  // derivation printed twice, once derived and once typed — sitting in the
  // clause directly above the one it repaired. Both endpoints come off
  // `wFirst`/`wLast` now, and HOLDS is a verdict about the PUBLISHED median, so
  // it is EARNED by both endpoints reading it rather than asserted beside them.
  const medHolds = wFirst.mode1.median === PUBLISHED.median && wLast.mode1.median === PUBLISHED.median;
  L(`  THE MEDIAN, ${PUBLISHED.median} — ${medHolds ? 'HOLDS' : 'NO LONGER HOLDS AT BOTH ENDS'}. ` +
    `Mode 1's median is ${num(wFirst.mode1.median)} in the first window and ${num(wLast.mode1.median)} in the last.`);
  L(`  THE BAR, ${PUBLISHED.barB} — DOES NOT MOVE. It ${barPosition(sh.barOffset)} a ${sh.spanWidth} B/boundary span`);
  L(`    that holds ${sh.spanN} of ${sh.n} cells, which is what makes its VALUE robust however its`);
  L('    derivation behaved. ROBUST IS NOT THE SAME AS EXACT, and A states the difference:');
  L(`    its partition differs from the two-population one on ${sh.splitDiff} of ${sh.n} cells.`);
  L(`  THE p90, ${PUBLISHED.p90} — CANNOT BE REPAIRED BY RE-CUTTING IT, because a pooled percentile is not a`);
  L('    magnitude on a two-population mixture. It should be RETIRED and replaced by two figures');
  L(`    that are: mode 1's own dispersion (p90 ${pooled.mode1.p90} over the corpus) and the fraction over the`);
  L('    bar. THAT IS A RULING, NOT THIS READER\'S CALL — the triple is cited by other windows\' bands.');
  L('  IS THE TAIL SESSION-CARRIED? NOT DECIDABLE HERE. Three windows are three sessions, and the');
  L('    session is confounded with the design, the round count and the date in all three. What is');
  L('    ruled out is the round count (F); what is established is a within-run component (E).');
  L('  WHAT IS MISSING: two sessions on ONE design. Every window in this corpus changed the design');
  L('    and the session together, so re-running phase 3\'s twelve-round design in a fresh session is');
  L('    the cheapest measurement that would separate them.');

  return out.join('\n');
}

// --- the tables, for the record ----------------------------------------------

// The record's tables, emitted as markdown so the page is GENERATED rather than
// transcribed. Every table here is 1 header row, 1 delimiter row and n body
// rows, and the self-test checks that all three have the same column count.
function tables() {
  const rows = corpus();
  const all = cellsOf(rows);
  const pooled = ladder(all);
  const out = [];
  const T = (head, body) => {
    out.push(`| ${head.join(' | ')} |`);
    out.push(`|${head.map(() => '---').join('|')}|`);
    for (const r of body) out.push(`| ${r.join(' | ')} |`);
    out.push('');
  };

  out.push('<!-- TABLE 1: the corpus -->');
  T(['window', 'design', 'run', 'rounds', 'session recorded', 'control', 'B/double', 'unverified', 'cells'],
    rows.map((r) => [r.window, r.design, r.run, r.rounds, r.sessionRecorded ? 'yes' : 'no (predates the field)',
      r.controlOk ? 'OK' : 'FAILED', r.perDouble === undefined ? 'n/a' : r.perDouble.toFixed(2),
      num(r.unverified), r.cells.length]));

  const lad = (l) => [num(l.median), num(l.absMedian), ...l.q.map(num), num(l.max),
    step(l) === null ? '—' : `**p${step(l) * 100}**`, `${l.overBar}/${l.n} (${f1(l.overBarPct)}%)`];
  const LH = ['median Δ', 'abs median', 'p50', 'p75', 'p80', 'p85', 'p90', 'p95', 'max', 'step', 'over the 45 B bar'];

  out.push('<!-- TABLE 2: the ladder, per run -->');
  T(['window', 'run', 'n', ...LH],
    rows.map((r) => { const l = ladder(r.cells); return [r.window, r.run, l.n, ...lad(l)]; }));

  // `(= per session)` IS THE WINDOW/SESSION CLAIM A THIRD TIME — rf2-oflj7, and
  // it is the copy that LEAVES this program: the marker labels the generated
  // markdown a reader takes away, where `report()`'s paragraph and section C
  // heading do not follow it. Both of those were repaired to count the grouping
  // and this one was not, which would have left one run of one program labelling
  // the same table two ways. Same grouping, same selection, so the three move
  // together or none of them do.
  const spanning3 = [...groupBy(rows, (r) => r.window)]
    .map(([w, rs]) => [w, groupBy(rs, (r) => r.session).size]).filter(([, n]) => n > 1);
  out.push(`<!-- TABLE 3: the ladder, per window${spanning3.length === 0 ? ' (= per session)'
    : ` (NOT per session: ${spanning3.map(([w, n]) => `${w} carries ${n}`).join(', ')})`} -->`);
  T(['window', 'n', ...LH],
    [...[...groupBy(rows, (r) => r.window)].map(([w, rs]) => { const l = ladder(cellsOf(rs)); return [w, l.n, ...lad(l)]; }),
      ['**pooled**', pooled.n, ...lad(pooled)]]);

  out.push('<!-- TABLE 4: the over-bar fraction and the mode-1 population -->');
  T(['window', 'over the 45 B bar', 'mode-1 n', 'mode-1 median', 'mode-1 p90', 'mode-1 p95', 'mode-1 max'],
    [...[...groupBy(rows, (r) => r.window)].map(([w, rs]) => {
      const l = ladder(cellsOf(rs));
      return [w, `${l.overBar}/${l.n} (${f1(l.overBarPct)}%)`, l.mode1.n, num(l.mode1.median),
        num(l.mode1.p90), num(l.mode1.p95), num(l.mode1.max)];
    }),
    ['**pooled**', `${pooled.overBar}/${pooled.n} (${f1(pooled.overBarPct)}%)`, pooled.mode1.n,
      num(pooled.mode1.median), num(pooled.mode1.p90), num(pooled.mode1.p95), num(pooled.mode1.max)]]);

  out.push('<!-- TABLE 5: the round-index structure -->');
  const byRound = groupBy(all.map((c) => ({ c, k: c.round })), (r) => r.k);
  T(['round', 'cells', 'in mode 2', 'share'],
    [...byRound.entries()].sort((a, b) => a[0] - b[0]).map(([rd, cs]) => {
      const cells = cs.map((x) => x.c);
      const o = cells.filter((c) => c.abs >= MODE2_FLOOR_B).length;
      return [rd, cells.length, o, `${f1((100 * o) / cells.length)}%`];
    }));

  out.push('<!-- TABLE 6: the common-support control -->');
  T(['window', 'over the bar, all rounds', 'over the bar, rounds 0-5 only'],
    [...groupBy(rows, (r) => r.window)].map(([w, rs]) => {
      const cs = cellsOf(rs);
      const l = ladder(cs); const lc = ladder(cs.filter((c) => c.round <= 5));
      return [w, `${l.overBar}/${l.n} (${f1(l.overBarPct)}%)`, `${lc.overBar}/${lc.n} (${f1(lc.overBarPct)}%)`];
    }));

  return out.join('\n');
}

// --- the self-test -----------------------------------------------------------

function selfTest() {
  const assert = require('assert');
  const fail = [];
  let checks = 0;
  const ck = (what, got, want) => {
    checks++;
    try {
      assert.deepStrictEqual(got, want);
    } catch {
      fail.push(`${what}: got ${JSON.stringify(got)}, want ${JSON.stringify(want)}`);
    }
  };

  // THE CORPUS IS WHAT WAS READ. Discovery must agree with the pinned list, in
  // both directions. A new null-arm window reds here on purpose — see WINDOWS.
  const { carrying, excluded } = discover();
  const pinned = WINDOWS.flatMap((w) => w.runs).sort();
  ck('discovery finds exactly the pinned corpus', carrying.map((c) => c.rel).sort(), pinned);
  ck('the corpus is sixteen runs', pinned.length, 16);

  // AND THE EXCLUSIONS ARE BY CONSTRUCTION, which is the positive control on
  // that discovery: a run with no null-arm cell must be a floor-plan run, not a
  // full-plan one this reader failed to parse. Without this, a parse that
  // returned nothing for every file would satisfy the check above by returning
  // nothing for the pinned files too — and the empty result would read as a
  // clean corpus rather than as a broken reader.
  ck('every excluded run is excluded by construction, with a reason named',
    excluded.filter((e) => e.reason === null).map((e) => e.rel), []);
  ck('the single-leg exclusion is exactly the one pre-paired-write run',
    excluded.filter((e) => e.reason === 'single-leg').map((e) => e.rel), ['alloc-2rtt6-138/run1.json']);
  ck('and there are plenty of floor-plan ones, so the exclusion path is exercised',
    excluded.filter((e) => e.reason === 'floor-plan').length > 100, true);

  const rows = corpus();
  const all = cellsOf(rows);
  ck('the whole committed corpus is 569 cells', all.length, 569);
  ck('over five sessions', groupBy(rows, (r) => r.session).size, 5);
  ck('one of which predates the session field', rows.filter((r) => !r.sessionRecorded).length, 2);
  ck('every run\'s positive control passed', rows.filter((r) => !r.controlOk).length, 0);
  ck('with no unverified read-backs anywhere', rows.filter((r) => r.unverified !== 0).length, 0);

  // THE PUBLISHED FIGURES, REPRODUCED. These pin `rf2-2rtt6.140`'s floor and the
  // two windows read against it. If this reader stops reproducing them it is
  // measuring a different population and nothing below it means anything.
  const byWindow = (w) => ladder(cellsOf(rows.filter((r) => r.window === w)));
  const g = byWindow('rf2-0gjqi');
  ck('rf2-0gjqi: published n', g.n, 38);
  ck('rf2-0gjqi: published median', g.median, 0);
  ck('rf2-0gjqi: published absolute median IS the published floor', g.absMedian, PUBLISHED.median);
  ck('rf2-0gjqi: published p90 IS the published p90', g.q[4], PUBLISHED.p90);
  ck('rf2-0gjqi: published max', g.max, 96.5);
  ck('rf2-0gjqi: over the bar', [g.overBar, g.n], [2, 38]);

  const p2 = byWindow('phase 2');
  ck('phase 2: published n and p90', [p2.n, p2.q[4]], [40, 56.5]);
  ck('phase 2: over the bar', [p2.overBar, p2.n], [4, 40]);

  const p3 = byWindow('phase 3');
  ck('phase 3: published n', p3.n, 164);
  ck('phase 3: published ladder', p3.q, [3, 19.5, 54.5, 59.5, 61, 64]);
  ck('phase 3: published max', p3.max, 135.5);
  ck('phase 3: over the bar', [p3.overBar, p3.n], [38, 164]);
  ck('phase 3: published per-run n', rows.filter((r) => r.window === 'phase 3').map((r) => r.cells.length),
    [37, 42, 42, 43]);
  ck('phase 3: published per-run p90',
    rows.filter((r) => r.window === 'phase 3').map((r) => ladder(r.cells).q[4]), [59.5, 64, 53.5, 62.5]);

  // THE GAP IS EMPTY, which is the finding everything else rests on.
  // THE SPAN WAS EMPTY OVER 242 CELLS AND IS NOT OVER 569. rf2-fk6pj's phase-4
  // window put ONE cell in it — 38.5 B/boundary, its run 4 round 5,
  // `reagent-subs | lad/reagent`. Pinned at the measured value rather than
  // relaxed to an inequality, because the exact count is the finding: 1 of 569
  // is 0.18%, so the two-population SHAPE is narrowed and not overturned
  // (466 below, 102 above), but "the gap holds zero" was load-bearing prose in
  // rf2-0eu1s's argument and it now has a named exception.
  ck('the span between the two modes holds exactly the one phase-4 cell',
    all.filter((c) => c.abs > MODE1_CEIL_B && c.abs < MODE2_FLOOR_B).length, 1);
  ck('and both edges are occupied, so the gap is observed rather than assumed',
    [all.filter((c) => c.abs === MODE1_CEIL_B).length > 0, all.filter((c) => c.abs === MODE2_FLOOR_B).length > 0],
    [true, true]);
  // THE BAR'S VALUE IS ROBUST, stated precisely rather than loosely. It does NOT
  // lie inside the empty span — it sits half a byte above its upper edge — so
  // the claim that is true is the classification one: it agrees with the
  // two-population split on every cell but the one at the mode-2 floor.
  const pooled = ladder(all);
  ck('the bar is above the empty span rather than inside it',
    [PUBLISHED.barB > MODE1_CEIL_B, PUBLISHED.barB > MODE2_FLOOR_B], [true, true]);
  ck('and so it differs from the two-population split on exactly one cell',
    pooled.mode2n - pooled.overBar, 1);
  ck('that cell being the one sitting on the mode-2 floor',
    all.filter((c) => c.abs > PUBLISHED.barB).length + all.filter((c) => c.abs === MODE2_FLOOR_B).length,
    pooled.mode2n);

  // NO LADDER HAS AN INTERMEDIATE RUNG — the step-function claim, checked on
  // every window rather than asserted from the three the report prints.
  for (const [w, rs] of groupBy(rows, (r) => r.window)) {
    const l = ladder(cellsOf(rs));
    ck(`${w}: every ladder rung is in one mode or the other`,
      l.q.filter((v) => v > MODE1_CEIL_B && v < MODE2_FLOOR_B), []);
  }

  // THE OVER-BAR FRACTION IS ORDERED, AND SURVIVES THE COMMON-SUPPORT CONTROL.
  // Stated on the published basis, so these are the bead's own three figures.
  const occ = [...groupBy(rows, (r) => r.window)].map(([, rs]) => ladder(cellsOf(rs)).overBarPct);
  // The first three are rf2-0eu1s's published figures and must not move; the
  // fourth is rf2-fk6pj's phase 4 and is new evidence rather than a
  // re-derivation. It sits BELOW phase 3 rather than continuing the rise, which
  // is the first datum this corpus has had against a monotone reading.
  ck('the three published windows reproduce, and phase 4 joins them',
    occ.map((x) => Number(x.toFixed(1))), [5.3, 10.0, 23.2, 17.4]);
  const occ05 = [...groupBy(rows, (r) => r.window)]
    .map(([, rs]) => ladder(cellsOf(rs).filter((c) => c.round <= 5)).overBarPct);
  ck('and are still strictly increasing on rounds 0-5 alone',
    occ05[0] < occ05[1] && occ05[1] < occ05[2], true);
  ck('the control has bite: it does move phase 3\'s figure', occ05[2] < occ[2], true);
  ck('phase 3 on the common support', Number(occ05[2].toFixed(1)), 18.8);

  // THE ROUND STRUCTURE, and that it is not the certification gate.
  const inMode2 = (cs) => cs.filter((c) => c.abs >= MODE2_FLOOR_B).length;
  const early = all.filter((c) => c.round <= 2);
  const late = all.filter((c) => c.round >= 3);
  ck('rounds 0-2 carry seven mode-2 cells of 142', [inMode2(early), early.length], [7, 142]);
  ck('rounds 3+ carry 95 of 427', [inMode2(late), late.length], [95, 427]);
  // THE WITHIN-RUN STRUCTURE REPLICATES AT DOUBLE THE SAMPLE. rf2-0eu1s found
  // rounds 0 and 1 carrying no mode-2 cell over 43; they still carry none over
  // 85. That is the one internally controlled comparison in this corpus and
  // phase 4 strengthens it rather than moving it.
  ck('rounds 0 and 1 carry none at all',
    [inMode2(all.filter((c) => c.round <= 1)), all.filter((c) => c.round <= 1).length], [0, 85]);
  const r12 = all.filter((c) => c.round === 1 || c.round === 2);
  ck('rounds 1-2 are a near-complete sample of the possible 128', r12.length, 120);
  ck('round 0 is not, and is reported separately for that reason',
    all.filter((c) => c.round === 0).length, 22);

  // THE REPORT STATES WHAT THE DERIVATION FOUND, so a figure cannot drift from
  // the reasoning behind it.
  const rep = report();
  ck('the report states the corpus total', /TOTAL 569 cells over 16 runs, 4 windows, 5 sessions\./.test(rep), true);
  ck('the report states the span occupancy, which is now one rather than zero',
    /the gap, \(21, 44\.5\)\s+: 1 cells/.test(rep), true);
  ck('the report refuses to move the bar', /THE BAR, 45 — DOES NOT MOVE\./.test(rep), true);

  // ---------------------------------------------------------------------------
  // SECTION A AND THE VERDICT, READ BACK AND COMPARED AS NUMBERS — rf2-2iaph.
  //
  // What stood here asserted the ABSENCE of the literal `LANDS INSIDE THAT GAP`
  // from a program that had never had a way to emit that string, while the
  // verdict said `lands inside a 23.5 B/boundary span that holds zero of
  // ${all.length} cells` — a hardcoded count wearing the right corpus size,
  // false since phase 4 put a cell in the span, and flatly contradicting
  // section A four hundred lines above it. The suite was green through every
  // run of that. It had to be: a phrase the program cannot print is not a
  // control, and the check could only ever see the words.
  //
  // The demonstration is one line long. Leave the claim exactly as it was and
  // change only its WORDING to contain the forbidden phrase, and the old check
  // goes red — same fault, opposite verdict. It was measuring the sentence.
  //
  // So nothing below tests for a phrase. Each check EXTRACTS A NUMBER from the
  // rendered report — once from section A, once from the verdict — and holds
  // both against a third derived here from the cells. Three consequences, and
  // they are why this cannot go hollow the way its predecessor did:
  //
  //   * a hardcoded figure in either section disagrees with the cells and reds,
  //     whatever words surround it;
  //   * `above` and `inside` are ONE check with opposite signs rather than two
  //     literals, so the report cannot claim a position the arithmetic denies;
  //   * a wording change that stops a number being found yields `NO MATCH` and
  //     FAILS. A pattern that matches nothing is the failure mode this whole
  //     bead is about, so it must never be the quiet answer.
  const sh = shape(all);
  const grab = (re, f) => { const m = re.exec(rep); return m === null ? 'NO MATCH' : f(m); };
  // AND EACH PATTERN MUST FIND ITS LINE EXACTLY ONCE. `exec` stops at the first
  // match, so without this a pattern loose enough to hit both sections would
  // compare one section's number with itself and pass. That is the trap a
  // permanent test walks straight past when it only ever plants one thing to
  // find, and it is worth more here than anywhere: the checks below exist
  // precisely to make two sections disagree out loud.
  const hits = (re) => (rep.match(new RegExp(re.source, 'g')) || []).length;

  const A_SPAN = /the gap, \([\d.]+, [\d.]+\)\s+: (\d+) cells/;
  const A_POS = /THE PUBLISHED BAR AT ([\d.]+) (?:SITS|LANDS) (HALF A BYTE|[\d.]+ B) (ABOVE|INSIDE) THAT SPAN\./;
  const A_DIFF = /from that partition on EXACTLY (\d+) of (\d+) cells, at ([\d., ]+) B\/boundary/;
  const V_POS = /DOES NOT MOVE\. It (?:sits|lands) (half a byte|[\d.]+ B) (above|inside) a ([\d.]+) B\/boundary span/;
  const V_SPAN = /that holds (\d+) of (\d+) cells, which is what makes its VALUE robust/;
  const V_DIFF = /its partition differs from the two-population one on (\d+) of (\d+) cells\./;

  ck('each figure the two sections are compared on is located exactly once',
    [A_SPAN, A_POS, A_DIFF, V_POS, V_SPAN, V_DIFF].map(hits), [1, 1, 1, 1, 1, 1]);

  // THE SPAN'S OCCUPANCY, three ways: what A prints, what the verdict prints,
  // and what the cells say. This is the bead's own contradiction, as a check.
  ck('the span occupancy agrees between section A, the verdict and the cells',
    [grab(A_SPAN, (m) => Number(m[1])), grab(V_SPAN, (m) => Number(m[1])), grab(V_SPAN, (m) => Number(m[2]))],
    [sh.spanN, sh.spanN, sh.n]);

  // THE BAR'S POSITION, read back as a SIGNED OFFSET rather than as a direction
  // word. `sits on the upper edge of` is deliberately unmatched by both
  // patterns: a corpus that put the bar exactly on the mode-2 floor reds here
  // and gets looked at, rather than sliding through on a third wording.
  const offset = (mag, dir) => (mag.toLowerCase() === 'half a byte' ? 0.5 : Number(mag.split(' ')[0])) *
    (dir.toLowerCase() === 'above' ? 1 : -1);
  ck('section A states the bar and its offset from the span, and both are derived',
    grab(A_POS, (m) => [Number(m[1]), offset(m[2], m[3])]), [PUBLISHED.barB, sh.barOffset]);
  ck('the verdict states the same offset, and the span\'s width with it',
    grab(V_POS, (m) => [offset(m[1], m[2]), Number(m[3])]), [sh.barOffset, sh.spanWidth]);

  // HOW FAR THE BAR'S PARTITION IS FROM THE TWO-POPULATION ONE — the verdict's
  // third clause, which said `separates the two populations correctly` while A
  // said it differs on one cell. Both sections now print the count, and A
  // prints the cells it is.
  ck('the partition difference agrees between section A, the verdict and the cells',
    [grab(A_DIFF, (m) => [Number(m[1]), Number(m[2]), m[3]]), grab(V_DIFF, (m) => [Number(m[1]), Number(m[2])])],
    [[sh.splitDiff, sh.n, sh.splitDiffAt.join(', ')], [sh.splitDiff, sh.n]]);
  // ...and that count is derived a SECOND way here, by differencing the two
  // totals rather than by counting the cells the partitions disagree about, so
  // the number the report is held to is not the report's own arithmetic.
  ck('and differencing the two totals gives the same count',
    pooled.mode2n - pooled.overBar, sh.splitDiff);

  // THE BRANCH IS THE ONE THE OCCUPANCY CALLS FOR. Both of these sentences are
  // emittable — unlike the literal the old guard forbade — and exactly one of
  // them belongs in any given run.
  ck('the empty-span sentence is printed exactly when the span is empty',
    [/classifies all \d+ cells identically/.test(rep), /THE SPAN IS NO LONGER EMPTY/.test(rep)],
    [sh.spanN === 0, sh.spanN > 0]);
  // ---------------------------------------------------------------------------
  ck('the report names the basis of its cross-window comparisons',
    /Every cross-window comparison below is stated OVER THE BAR/.test(rep), true);
  ck('the report leaves the p90 to a ruling', /THAT IS A RULING, NOT THIS READER'S CALL/.test(rep), true);
  ck('the report says the session question is not decidable here',
    /IS THE TAIL SESSION-CARRIED\? NOT DECIDABLE HERE\./.test(rep), true);
  ck('the report proposes no mechanism for the round structure',
    /THIS READER HAS NOT\n;; {3}TESTED WHETHER THOSE ARE THE SAME THREE/.test(rep), true);

  // ---------------------------------------------------------------------------
  // THE NARRATION, HELD AGAINST THE COUNTS IT NARRATES — rf2-oflj7.
  //
  // Four sentences in the body and one in G were true of the 242-cell corpus and
  // false or unguarded on this one, and each sat within a few lines of the
  // derived figure that contradicted it: `each window is exactly one session`
  // three lines under `4 windows, 5 sessions`; `which all eight runs have` forty
  // lines under `over 16 runs`; `its p90 rises from 3 to 9` in a sentence whose
  // own clause before it says first-window-to-last, two rows under a table
  // reading 4.5 for the last window. Nothing red, because nothing was looking:
  // none of the four carried a check of ANY kind, and G's carried one that asked
  // only whether `THAT REFUSAL STANDS EITHER WAY.` appeared — the same hollow
  // shape rf2-2iaph replaced above, which is why the replacement here follows
  // that block rather than inventing a second style beside it.
  //
  // So each check below EXTRACTS NUMBERS from the rendered report and holds them
  // against a derivation made here from `rows` and the cells; a pattern that
  // finds nothing yields `NO MATCH` and FAILS rather than passing for want of
  // anything to read; and every pattern must locate its line exactly once, so no
  // check can end up reading one sentence against itself.
  const P_SESSIONS = /WINDOW AND SESSION ARE (?:THE SAME PARTITION HERE: (\d+) windows, one session each|NO LONGER THE SAME PARTITION: (\d+) windows over (\d+) sessions)\./;
  const P_LADDER_C = /C\. THE LADDER, PER WINDOW — which is (also per session|NOT also per session: .+?), see above\./;
  const P_SUPPORT = /every window to rounds 0-5, which (?:all (\d+) runs|(\d+) of the (\d+) runs) have, removes it\./;
  const P_MEDIAN = /Mode 1's median is ([\d.]+) B\/boundary in the first\n;; {3}window and ([\d.]+) in the last;/;
  const P_CENTRE = /its p90 (rises|falls|holds) from ([\d.]+) to ([\d.]+) over the same two\n;; {3}windows, a movement of ([\d.]+) against the ([\d.]+) the per-window p90 reports/;
  const P_BANDS = /Ten times MODE-1's p90 is ([\d.]+) B\/boundary over the whole corpus and ([\d.]+) on phase 3's own null/;
  const P_DELTA = /refused a term whose implied delta was ([\d.]+), which is below/;
  const P_MODES = /p90 of ([\d.]+) and phase 3's p90 of ([\d.]+) are (therefore the two MODES|NO LONGER ONE MODE EACH)/;
  const P_VERDICT_MED = /THE MEDIAN, ([\d.]+) — (HOLDS|NO LONGER HOLDS AT BOTH ENDS)\. Mode 1's median is ([\d.]+) in the first window and ([\d.]+) in the last\./;

  ck('each narrated figure is located exactly once as well',
    [P_SESSIONS, P_LADDER_C, P_SUPPORT, P_MEDIAN, P_CENTRE, P_BANDS, P_DELTA,
      P_MODES, P_VERDICT_MED].map(hits),
    [1, 1, 1, 1, 1, 1, 1, 1, 1]);

  // WINDOW AGAINST SESSION. The paragraph and the TOTAL line three above it are
  // the pair the bead names, so the paragraph's own two counts are read back and
  // held against the corpus rather than against that line's wording.
  const wSess = [...groupBy(rows, (r) => r.window)].map(([w, rs]) => [w, groupBy(rs, (r) => r.session).size]);
  const nSpanning = wSess.filter(([, n]) => n > 1).length;
  ck('the window/session paragraph counts the windows and sessions the corpus has',
    grab(P_SESSIONS, (m) => (m[1] !== undefined ? [Number(m[1]), Number(m[1])] : [Number(m[2]), Number(m[3])])),
    [WINDOWS.length, groupBy(rows, (r) => r.session).size]);
  // AND THE SENTENCE IS THE ONE THE GROUPING CALLS FOR. Both are emittable, so
  // this is one check with opposite booleans rather than two literals — the same
  // shape as the empty-span branch above.
  ck('and says they are the same partition exactly when no window spans two sessions',
    /WINDOW AND SESSION ARE THE SAME PARTITION HERE/.test(rep), nSpanning === 0);

  // SECTION C'S HEADING is that claim again in four words, and it went stale with
  // it. Held against the same grouping, and required to NAME any window that
  // spans rather than to gloss it.
  ck('section C\'s heading agrees with the grouping, and names any window that spans',
    grab(P_LADDER_C, (m) => m[1]),
    nSpanning === 0 ? 'also per session'
      : `NOT also per session: ${wSess.filter(([, n]) => n > 1).map(([w, n]) => `${w} carries ${n}`).join(', ')}`);

  // THE COMMON-SUPPORT CONTROL'S RUN COUNT, read back against the corpus size...
  ck('F states how many runs carry rounds 0-5, and it is the corpus it just read',
    grab(P_SUPPORT, (m) => (m[1] !== undefined ? [Number(m[1]), Number(m[1])] : [Number(m[2]), Number(m[3])])),
    [rows.filter((r) => r.rounds >= 6).length, rows.length]);
  // ...and the word `all` earns itself: it is warranted only while no run in the
  // corpus is short of those rounds, which is checked by NAMING the ones that
  // are. An empty list here is the claim, not the absence of one.
  ck('and `all` is warranted, because no run in the corpus is short of them',
    rows.filter((r) => r.rounds < 6).map((r) => `${r.window} ${r.run}`), []);

  // SECTION D, FIRST WINDOW TO LAST. `3 to 9` was phase 3's pair, left behind
  // when phase 4 became the last window. Four numbers and a direction word, read
  // back against ladders taken here.
  const lads = [...groupBy(rows, (r) => r.window)].map(([, rs]) => ladder(cellsOf(rs)));
  const lFirst = lads[0];
  const lLast = lads[lads.length - 1];
  ck('section D\'s median endpoints are the first and last windows\' own',
    grab(P_MEDIAN, (m) => [Number(m[1]), Number(m[2])]), [lFirst.mode1.median, lLast.mode1.median]);
  ck('and so are its p90 endpoints, with the direction word the two of them imply',
    grab(P_CENTRE, (m) => [m[1], Number(m[2]), Number(m[3])]),
    [lLast.mode1.p90 > lFirst.mode1.p90 ? 'rises'
      : lLast.mode1.p90 < lFirst.mode1.p90 ? 'falls' : 'holds',
    lFirst.mode1.p90, lLast.mode1.p90]);
  // THE TWO MOVEMENTS IT COMPARES are differenced HERE rather than taken from the
  // sentence's own endpoints, so the figures the sentence is held to are not its
  // own arithmetic restated.
  ck('and the two movements it sets against each other are the windows\' own',
    grab(P_CENTRE, (m) => [Number(m[4]), Number(m[5])]),
    [Math.abs(lLast.mode1.p90 - lFirst.mode1.p90), Math.abs(lLast.q[4] - lFirst.q[4])]);

  // SECTION G'S REFUSAL. The bands are read back, the delta with them, and the
  // clause is required to be the one the comparison calls for — in BOTH
  // directions, which is the whole of what the presence check could not do.
  const bandsD = [10 * pooled.mode1.p90,
    10 * ladder(cellsOf(rows.filter((r) => r.window === 'phase 3'))).mode1.p90];
  ck('G states the two mode-1 bands, and they are ten times those p90s',
    grab(P_BANDS, (m) => [Number(m[1]), Number(m[2])]), bandsD);
  ck('and the delta it holds them against is the one the record pins',
    grab(P_DELTA, (m) => Number(m[1])), FK6PJ_IMPLIED_DELTA_B);
  ck('and the refusal clause is the one that comparison calls for',
    [/THAT REFUSAL STANDS EITHER WAY\./.test(rep), /THAT REFUSAL NO LONGER STANDS EITHER WAY/.test(rep)],
    [bandsD.every((b) => b > FK6PJ_IMPLIED_DELTA_B), !bandsD.every((b) => b > FK6PJ_IMPLIED_DELTA_B)]);

  // SECTION C'S TWO MODES. `phase 3's p90 of 61` was the last figure in the body
  // still typed, and it survived the fold-in by being RIGHT — the pooled p90 two
  // rows above it moved and this one did not, so a reader had no way to tell a
  // re-read number from a re-printed one. Read off phase 3 here, and the
  // paragraph's `therefore` is held to the comparison it rests on rather than to
  // its own wording.
  const w3p90D = ladder(cellsOf(rows.filter((r) => r.window === 'phase 3'))).q[4];
  ck('section C names the two modes with the published p90 and phase 3\'s own',
    grab(P_MODES, (m) => [Number(m[1]), Number(m[2])]), [PUBLISHED.p90, w3p90D]);
  ck('and calls them one mode each exactly when they straddle the mode-2 floor',
    grab(P_MODES, (m) => m[3] === 'therefore the two MODES'),
    PUBLISHED.p90 < MODE2_FLOOR_B && w3p90D >= MODE2_FLOOR_B);

  // THE VERDICT'S MEDIAN CLAUSE. Section D derives these same two endpoints and
  // the verdict typed them, which is rf2-2iaph's A-against-the-verdict defect in
  // the clause immediately above the one it repaired. Both endpoints are held to
  // the ladders, and the verdict WORD is held to what those endpoints imply
  // about the PUBLISHED median — in both directions, so `HOLDS` has to be earned.
  ck('the verdict\'s median clause reads the published median and the two windows\' own',
    grab(P_VERDICT_MED, (m) => [Number(m[1]), Number(m[3]), Number(m[4])]),
    [PUBLISHED.median, lFirst.mode1.median, lLast.mode1.median]);
  ck('and says HOLDS exactly when both endpoints are the published median',
    grab(P_VERDICT_MED, (m) => m[2]),
    lFirst.mode1.median === PUBLISHED.median && lLast.mode1.median === PUBLISHED.median
      ? 'HOLDS' : 'NO LONGER HOLDS AT BOTH ENDS');
  // ---------------------------------------------------------------------------

  // THE TABLES ARE RECTANGULAR. The record is largely tables and no gate in this
  // repository reads a markdown table, so the column count is checked here.
  const tbl = tables();
  const lines = tbl.split('\n').filter((l) => l.startsWith('|'));
  ck('the tables emit some rows', lines.length > 40, true);
  let head = null;
  let bad = [];
  for (const l of tbl.split('\n')) {
    if (!l.startsWith('|')) { head = null; continue; }
    const cols = l.split('|').length - 2;
    if (head === null) head = cols;
    else if (cols !== head) bad.push(l);
  }
  ck('every table row has its header\'s column count', bad, []);
  ck('there are six tables', (tbl.match(/<!-- TABLE /g) || []).length, 6);

  // AND TABLE 3'S MARKER SAYS WHAT THE GROUPING SAYS — rf2-oflj7. It carried
  // `(= per session)`, the window/session equality a third time, in the one copy
  // that leaves this program with the generated markdown. Held two ways: against
  // the grouping, and against SECTION C'S HEADING, which is the same claim
  // rendered by a different function over the same runs. Two renderings that can
  // drift is exactly the pair this bead and rf2-2iaph are both made of, so they
  // are compared here rather than each checked alone.
  const t3 = tbl.split('\n').filter((l) => l.startsWith('<!-- TABLE 3'));
  ck('the table-3 marker occurs exactly once', t3.length, 1);
  const T3_SPAN = /<!-- TABLE 3: the ladder, per window \(NOT per session: (.+?)\) -->/;
  const wSessT = [...groupBy(rows, (r) => r.window)].map(([w, rs]) => [w, groupBy(rs, (r) => r.session).size]);
  const spanT = wSessT.filter(([, n]) => n > 1);
  const spanPhraseT = spanT.length === 0 ? null : spanT.map(([w, n]) => `${w} carries ${n}`).join(', ');
  ck('the marker, section C\'s heading and the grouping all name the same spanning windows',
    [(T3_SPAN.exec(t3[0] || '') || [null, null])[1],
      grab(P_LADDER_C, (m) => (m[1] === 'also per session' ? null : m[1].replace('NOT also per session: ', '')))],
    [spanPhraseT, spanPhraseT]);
  // AND THE MARKER'S BRANCH IS THE ONE THE GROUPING CALLS FOR. Both arms are
  // emittable, so this is one check with opposite booleans rather than a literal
  // the run happens to satisfy.
  ck('and the marker says `= per session` exactly when no window spans two sessions',
    [/ \(= per session\)/.test(t3[0] || ''), / \(NOT per session: /.test(t3[0] || '')],
    [spanT.length === 0, spanT.length > 0]);

  if (fail.length) {
    console.error('SELF-TEST FAILED:\n  ' + fail.join('\n  '));
    process.exit(1);
  }
  // DERIVED, NOT TRANSCRIBED. This line was hardcoded at 242 cells / 8 runs /
  // 3 windows / 3 sessions / gap 0, so it went on printing the corpus the
  // reader was WRITTEN on rather than the one it just read — a green summary
  // asserting figures no check had verified. It reads them off the corpus now.
  console.log(
    `self-test OK (${checks} checks) — ${all.length} cells, ${pinned.length} runs, ` +
      `${WINDOWS.length} windows, ${groupBy(rows, (r) => r.session).size} sessions; ` +
      `the gap holds ${all.filter((c) => c.abs > MODE1_CEIL_B && c.abs < MODE2_FLOOR_B).length}`
  );
}

if (require.main === module) {
  if (process.argv.includes('--self-test')) selfTest();
  else if (process.argv.includes('--tables')) console.log(tables());
  else console.log(report());
}

module.exports = {
  PUBLISHED, MODE1_CEIL_B, MODE2_FLOOR_B, WINDOWS, LADDER,
  allRecords, discover, nullCells, median, quantile, ladder, step, shape, corpus, cellsOf,
  report, tables, selfTest,
};
