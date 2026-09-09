#!/usr/bin/env node
// Do the two floor-arm modes climb the SAME heap, or only CLOSE at the same one?
//
// Bead rf2-9jrhi, reopened by the merged-PR audit of PR #8457.  Record:
//   docs/design/hicasso/studio/the-bisect-is-flat-and-the-floor-has-a-second-mode.md
//
// WHAT THE AUDIT FOUND.  That record excluded the heap trajectory as the carrier
// of the 3,792 B second mode on this evidence: the absolute opening heap level
// "tracks within 8 KB between the high run and a low one across all eighteen
// rounds, ending at 6,191,823 B against 6,184,236 B".  The two endpoints are
// real and they are reproduced below -- but they are a CLOSING-level reading,
// and the sentence generalised them to the whole trajectory.  Round by round the
// same pair diverges by up to 40,127 B, an order of magnitude past the 3,792 B
// step the exclusion is about.  The pair is also cross-revision: 6,184,236 B is
// bisect-2-m, taken at a158c40288, not a replicate of the high run's own commit.
//
// WHAT THIS SCRIPT DOES.  It re-derives every figure the corrected passage
// publishes, from committed datasets only.  It launches no browser, reads no rig
// file and writes nothing.  Run it and diff the output against the page.
//
//   node implementation/hicasso/test/re_frame/bench/hicasso/alloc_heap_trajectory.cjs
//
// WHAT IT CANNOT DO.  There is exactly ONE high-mode run in the corpus, so every
// comparison here has n = 1 on one side.  Nothing below establishes a mechanism;
// it bounds what the retained samples can and cannot exclude.

const fs = require('fs');
const path = require('path');

const DATA = path.join(__dirname, 'data', 'alloc-9jrhi');
const SEGS = ['reagent-subs', 'uix-subs'];
const KEY = (seg) => `${seg}|grid/floor`;

const n0 = (v) => Math.round(v).toLocaleString('en-US');
const sgn = (v) => (v >= 0 ? '+' : '') + n0(v);

const load = (f) => JSON.parse(fs.readFileSync(path.join(DATA, f), 'utf8')).alloc;

// samples[0] is the absolute used-heap level at the window's opening, retained
// for the first time by rf2-erre5 (PR #8452).
const opens = (a, seg) => a.perRound.map((r) => r.arms[KEY(seg)].samples[0]);
const legs = (a, seg) => a.perRound.map((r) => r.arms[KEY(seg)].legMedian);

const RUNS = fs
  .readdirSync(DATA)
  .filter((f) => f.startsWith('bisect-') && f.endsWith('.json'))
  .sort();

const out = [];
const say = (s) => out.push(s);

const HIGH = 'bisect-1-a-4a1537cb71.json';
const CROSS = 'bisect-2-m-a158c40288.json'; // the run the withdrawn sentence used
const SAME = 'bisect-6-a-4a1537cb71-replicate2.json'; // same revision, low, control-passing

say('=== 1. THE ENDPOINTS THE WITHDRAWN SENTENCE QUOTED ===');
for (const f of [HIGH, CROSS, SAME]) {
  const a = load(f);
  const o = opens(a, 'reagent-subs');
  say(`    ${f.padEnd(40)} reagent samples[0] r17 = ${n0(o[o.length - 1])}`);
}
say('');
say('    6,191,823 / 6,184,236 identify bisect-1-a and bisect-2-m.  bisect-2-m is');
say('    at a158c40288 -- a DIFFERENT commit, not the high run\'s own replicate.');

say('');
say('=== 2. ROUNDWISE DIVERGENCE OF THE OPENING HEAP ===');
for (const [label, other] of [['cross-revision (bisect-2-m, a158c40288)', CROSS],
                              ['same-revision low replicate (bisect-6)', SAME]]) {
  const A = load(HIGH);
  const B = load(other);
  say(`    bisect-1-a vs ${label}`);
  for (const seg of SEGS) {
    const a = opens(A, seg);
    const b = opens(B, seg);
    const d = a.map((v, i) => Math.abs(v - b[i]));
    const max = Math.max(...d);
    say(`      ${seg.padEnd(13)} max ${n0(max).padStart(7)} B at round ${d.indexOf(max)}` +
        `   closing ${n0(d[d.length - 1]).padStart(7)} B`);
    say(`        ${d.map((v, i) => `r${i}:${v}`).join('  ')}`);
  }
  say('');
}
say('    The step being explained is 3,792 B.  Neither pair tracks within 8 KB');
say('    across all eighteen rounds; both CLOSE far nearer than they travel.');

say('');
say('=== 3. WHERE THE MODE STARTS, ACROSS EVERY EIGHTEEN-ROUND RUN ===');
say('    round  is the round-3 -> round-4 boundary; the high run steps here.');
say('');
say('    run                                          r3 open     r4 open      step    legMed r3   r4');
for (const f of RUNS) {
  const a = load(f);
  if (a.rounds !== 18) continue;
  const o = opens(a, 'reagent-subs');
  const l = legs(a, 'reagent-subs');
  say(`    ${f.padEnd(42)} ${n0(o[3]).padStart(9)} ${n0(o[4]).padStart(11)} ${sgn(o[4] - o[3]).padStart(9)}` +
      `   ${String(l[3]).padStart(7)} ${String(l[4]).padStart(7)}`);
}

say('');
say('=== 4. THE HIGH RUN\'S ROUND-4 OPENING HEAP AGAINST EVERY OTHER RUN ===');
{
  const A = load(HIGH);
  for (const seg of SEGS) {
    const a = opens(A, seg);
    say(`    ${seg}`);
    for (const f of RUNS) {
      if (f === HIGH) continue;
      const b = load(f);
      if (b.rounds !== 18) continue;
      say(`      vs ${f.padEnd(42)} ${sgn(a[4] - opens(b, seg)[4]).padStart(9)} B`);
    }
  }
}
say('');
say('    The high-mode run opens round 4 some 23.5 KB BELOW both of its own');
say('    same-revision replicates, and takes a round-3 -> round-4 heap step');
say('    ~25.5 KB SMALLER than either of them (+137,736 against +163,260 and');
say('    +163,428), while allocating 3,792 B MORE per write from that round on.');
say('    So the sign does not run the way a "higher heap costs more per write"');
say('    mechanism would need it to.  n = 1 high-mode run: this bounds the');
say('    monotone form of the candidate, it does not exclude the trajectory.');

process.stdout.write(out.join('\n') + '\n');
