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

function report() {
  const rows = corpus();
  const all = cellsOf(rows);
  const pooled = ladder(all);
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
  L('WINDOW AND SESSION ARE THE SAME PARTITION HERE. The bead asks for the ladder per window AND');
  L('per session; in this corpus each window is exactly one session, so the two tables would be');
  L('the same table. That is not a convenience — it is the reason nothing below can attribute the');
  L('movement to the session rather than to the design, the round count or the date.');
  L();

  L('A. THE SHAPE, WHICH GOVERNS EVERY LADDER BELOW: TWO POPULATIONS, NOT ONE TAIL.');
  const gap = all.filter((c) => c.abs > MODE1_CEIL_B && c.abs < MODE2_FLOOR_B);
  const m1 = all.filter((c) => c.abs <= MODE1_CEIL_B);
  const m2 = all.filter((c) => c.abs >= MODE2_FLOOR_B);
  L(`  mode 1, at or below ${MODE1_CEIL_B} B/boundary : ${m1.length} cells`);
  L(`  the gap, (${MODE1_CEIL_B}, ${MODE2_FLOOR_B})              : ${gap.length} cells`);
  L(`  mode 2, at or above ${MODE2_FLOOR_B} B/boundary : ${m2.length} cells`);
  L(`  Both edges are observed, not chosen: ${MODE1_CEIL_B} is the largest cell below the gap and`);
  L(`  ${MODE2_FLOOR_B} the smallest above it, over all ${all.length}.`);
  L(`  THE PUBLISHED BAR AT ${PUBLISHED.barB} SITS HALF A BYTE ABOVE THAT SPAN. Any bar in`);
  L(`  (${MODE1_CEIL_B}, ${MODE2_FLOOR_B}] classifies all ${all.length} cells identically; the published one differs`);
  L(`  from that partition on EXACTLY ONE cell of ${all.length}, the one at ${MODE2_FLOOR_B}, which it counts`);
  L('  below the bar. So the bar\'s VALUE is robust however its derivation behaved — and so are');
  L('  the two counts below, which differ by that one cell and by nothing else:');
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

  L('C. THE LADDER, PER WINDOW — which is also per session, see above.');
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
  L(`  p90 of ${PUBLISHED.p90} and phase 3's p90 of 61 are therefore the two MODES, read by an index that`);
  L('  crossed the gap when occupancy passed roughly one in ten — not two points on a continuum.');
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
  L('  THE CENTRE HELD AND THE OCCUPANCY MOVED. Mode 1\'s median is 1.5 B/boundary in the first');
  L('  window and 1.5 in the last; its p90 rises from 3 to 9, which is real and is a twentieth of');
  L('  the movement the pooled p90 reports.');
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
  L('   every window to rounds 0-5, which all eight runs have, removes it.');
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
  L(`  Ten times MODE-1's p90 is ${10 * pooled.mode1.p90} B/boundary over the whole corpus and ` +
    `${10 * w3.mode1.p90} on phase 3's own null`);
  L(`  arm. rf2-fk6pj refused a term whose implied delta was ${FK6PJ_IMPLIED_DELTA_B}, which is below both, SO`);
  L('  THAT REFUSAL STANDS EITHER WAY. The correction changes the band\'s scale, not its verdict.');
  L();

  L('THE VERDICT, in the three parts the bead asks for.');
  L(`  THE MEDIAN, ${PUBLISHED.median} — HOLDS. Mode 1's median is 1.5 in the first window and 1.5 in the last.`);
  L(`  THE BAR, ${PUBLISHED.barB} — DOES NOT MOVE. It lands inside a ${MODE2_FLOOR_B - MODE1_CEIL_B} B/boundary span that holds`);
  L(`    zero of ${all.length} cells, and it separates the two populations correctly.`);
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

  out.push('<!-- TABLE 3: the ladder, per window (= per session) -->');
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
  ck('the corpus is eight runs', pinned.length, 8);

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
  ck('the whole committed corpus is 242 cells', all.length, 242);
  ck('over three sessions', groupBy(rows, (r) => r.session).size, 3);
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
  ck('no cell lies strictly between the two modes',
    all.filter((c) => c.abs > MODE1_CEIL_B && c.abs < MODE2_FLOOR_B).length, 0);
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
  ck('the three windows reproduce the bead\'s published fractions',
    occ.map((x) => Number(x.toFixed(1))), [5.3, 10.0, 23.2]);
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
  ck('rounds 0-2 carry two mode-2 cells of 73', [inMode2(early), early.length], [2, 73]);
  ck('rounds 3+ carry 43 of 169', [inMode2(late), late.length], [43, 169]);
  ck('rounds 0 and 1 carry none at all',
    [inMode2(all.filter((c) => c.round <= 1)), all.filter((c) => c.round <= 1).length], [0, 43]);
  const r12 = all.filter((c) => c.round === 1 || c.round === 2);
  ck('rounds 1-2 are a near-complete sample of the possible 64', r12.length, 61);
  ck('round 0 is not, and is reported separately for that reason',
    all.filter((c) => c.round === 0).length, 12);

  // THE REPORT STATES WHAT THE DERIVATION FOUND, so a figure cannot drift from
  // the reasoning behind it.
  const rep = report();
  ck('the report states the corpus total', /TOTAL 242 cells over 8 runs, 3 windows, 3 sessions\./.test(rep), true);
  ck('the report states the gap is empty', /the gap, \(21, 44\.5\)\s+: 0 cells/.test(rep), true);
  ck('the report refuses to move the bar', /THE BAR, 45 — DOES NOT MOVE\./.test(rep), true);
  ck('the report does not claim the bar lies inside the empty span',
    /SITS HALF A BYTE ABOVE THAT SPAN/.test(rep) && !/LANDS INSIDE THAT GAP/.test(rep), true);
  ck('the report names the basis of its cross-window comparisons',
    /Every cross-window comparison below is stated OVER THE BAR/.test(rep), true);
  ck('the report leaves the p90 to a ruling', /THAT IS A RULING, NOT THIS READER'S CALL/.test(rep), true);
  ck('the report says the session question is not decidable here',
    /IS THE TAIL SESSION-CARRIED\? NOT DECIDABLE HERE\./.test(rep), true);
  ck('the report proposes no mechanism for the round structure',
    /THIS READER HAS NOT\n;; {3}TESTED WHETHER THOSE ARE THE SAME THREE/.test(rep), true);
  ck('the report records that fk6pj\'s refusal stands under the correction',
    /THAT REFUSAL STANDS EITHER WAY\./.test(rep), true);

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

  if (fail.length) {
    console.error('SELF-TEST FAILED:\n  ' + fail.join('\n  '));
    process.exit(1);
  }
  console.log(`self-test OK (${checks} checks) — 242 cells, 8 runs, 3 windows, 3 sessions; the gap holds 0`);
}

if (require.main === module) {
  if (process.argv.includes('--self-test')) selfTest();
  else if (process.argv.includes('--tables')) console.log(tables());
  else console.log(report());
}

module.exports = {
  PUBLISHED, MODE1_CEIL_B, MODE2_FLOOR_B, WINDOWS, LADDER,
  allRecords, discover, nullCells, median, quantile, ladder, step, corpus, cellsOf,
  report, tables, selfTest,
};
