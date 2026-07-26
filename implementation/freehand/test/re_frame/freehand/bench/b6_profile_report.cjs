#!/usr/bin/env node
// Offline attribution over a saved `.cpuprofile`.
//
//   node b6_profile_report.cjs <file.cpuprofile> [--top 30] [--callers RE]...
//
// SELF TIME NAMES THE FUNCTION; ONLY CALLER ATTRIBUTION NAMES THE FIX.
// That is the lesson `rf2-lnecd` recorded and `rf2-xu6rx` re-used: self
// time said `clojure.string/replace`, memoising the obvious caller moved
// nothing, and walking the parent chain found the real one in a step.
// So this tool reports three things, and the third is the useful one:
//
//   1. self  — samples whose LEAF is this frame.
//   2. incl  — samples whose ancestry PASSES THROUGH this frame, counted
//              once per sample even when the frame recurs on the chain.
//   3. callers of <pattern> — for every sample under a matching frame,
//              the chain of named ancestors above the NEAREST match,
//              aggregated. This is what says where a cost comes FROM.
//
// Run offline over ONE saved capture rather than one browser run per
// question: a frame re-profiled is a frame compared against a different
// profile.

const fs = require('node:fs');

const file = process.argv[2];
if (!file) {
  console.error('usage: b6_profile_report.cjs <file.cpuprofile> [--top N] [--callers RE]...');
  process.exit(2);
}
const TOP = Number((process.argv.includes('--top') && process.argv[process.argv.indexOf('--top') + 1]) || 30);
const PATTERNS = process.argv.reduce(
  (acc, a, i) => (a === '--callers' ? acc.concat([process.argv[i + 1]]) : acc),
  []
);
const UNDER = process.argv.reduce(
  (acc, a, i) => (a === '--under' ? acc.concat([process.argv[i + 1]]) : acc),
  []
);
const DEPTH = Number((process.argv.includes('--depth') && process.argv[process.argv.indexOf('--depth') + 1]) || 4);

const profile = JSON.parse(fs.readFileSync(file, 'utf8'));
const byId = new Map();
for (const n of profile.nodes) byId.set(n.id, n);
const parent = new Map();
for (const n of profile.nodes) for (const c of n.children || []) parent.set(c, n.id);

const nameOf = (n) => {
  const f = n.callFrame;
  const nm = f.functionName || '(anonymous)';
  return nm;
};

const total = profile.samples.length;
const selfByNode = new Map();
for (const s of profile.samples) selfByNode.set(s, (selfByNode.get(s) || 0) + 1);

// --- self / inclusive by name ---------------------------------------------
const self = new Map();
const incl = new Map();
const add = (m, k, v) => m.set(k, (m.get(k) || 0) + v);

const chainCache = new Map();
function chain(id) {
  if (chainCache.has(id)) return chainCache.get(id);
  const out = [];
  let cur = id;
  while (cur !== undefined) {
    out.push(cur);
    cur = parent.get(cur);
  }
  chainCache.set(id, out);
  return out;
}

for (const [id, count] of selfByNode) {
  const node = byId.get(id);
  if (!node) continue;
  add(self, nameOf(node), count);
  const seen = new Set();
  for (const a of chain(id)) {
    const nm = nameOf(byId.get(a));
    if (!seen.has(nm)) {
      seen.add(nm);
      add(incl, nm, count);
    }
  }
}

const pct = (n) => ((100 * n) / total).toFixed(2).padStart(6) + '%';
const sorted = (m) => [...m.entries()].sort((a, b) => b[1] - a[1]);

console.log(`=== ${file}`);
console.log(`total samples ${total}  (interval ${profile.timeDeltas ? 'variable' : 'n/a'})`);
const wall = (profile.endTime - profile.startTime) / 1000;
console.log(`capture wall ${wall.toFixed(1)} ms\n`);

const SYNTH = new Set(['(idle)', '(program)', '(garbage collector)', '(root)']);
let synth = 0;
for (const [k, v] of self) if (SYNTH.has(k)) synth += v;
console.log(`--- synthetic frames (idle/program/GC): ${pct(synth)} of samples`);
for (const [k, v] of sorted(self)) if (SYNTH.has(k)) console.log(`  ${pct(v)}  ${k}`);
console.log();

// React and react-dom arrive as ALREADY-MINIFIED npm JavaScript, so
// `:pseudo-names` cannot name them — it renames ClojureScript symbols, and
// React's `nl`/`Gl`/`Kh` were minified by React's own build long before
// Closure saw them. Their `url:line` is the only handle there is, so the
// self listing carries it and the report identifies React frames by
// position rather than pretending to a name.
const locOf = (n) => {
  const f = n.callFrame;
  const u = (f.url || '').split('/').pop();
  return u ? `${u}:${f.lineNumber + 1}:${f.columnNumber + 1}` : '';
};
const selfLoc = new Map();
for (const [id, count] of selfByNode) {
  const node = byId.get(id);
  if (!node) continue;
  add(selfLoc, `${nameOf(node)}   @${locOf(node)}`, count);
}

console.log(`--- TOP ${TOP} SELF (with location; React frames are pre-minified)`);
for (const [k, v] of sorted(selfLoc).slice(0, TOP)) console.log(`  ${pct(v)}  ${k}`);
console.log();

console.log(`--- TOP ${TOP} INCLUSIVE`);
for (const [k, v] of sorted(incl).slice(0, TOP)) console.log(`  ${pct(v)}  ${k}`);
console.log();

// --- what a subtree SPENDS ITSELF ON ---------------------------------------
// The mirror of caller attribution. `--callers` says where a cost comes
// from; `--under` says what an inclusive share is actually made of, by
// bucketing every sample beneath a named frame by its own LEAF. Both are
// needed: a 29% inclusive frame that is 29% one callee is a hot spot, and
// the same 29% spread across twenty callees is a structure.
for (const p of UNDER) {
  const re = new RegExp(p);
  const leaves = new Map();
  let hit = 0;
  for (const [id, count] of selfByNode) {
    const ch = chain(id);
    if (!ch.some((a) => re.test(nameOf(byId.get(a))))) continue;
    hit += count;
    add(leaves, `${nameOf(byId.get(id))}   @${locOf(byId.get(id))}`, count);
  }
  console.log(`--- SELF TIME UNDER /${p}/ : ${pct(hit)} inclusive of ${total} samples`);
  for (const [k, v] of sorted(leaves).slice(0, TOP)) console.log(`  ${pct(v)}  ${k}`);
  console.log();
}

// --- caller attribution ----------------------------------------------------
for (const p of PATTERNS) {
  const re = new RegExp(p);
  const chains = new Map();
  let hit = 0;
  for (const [id, count] of selfByNode) {
    const ch = chain(id); // leaf -> root
    // NEAREST matching frame: the first on the leaf->root walk.
    const idx = ch.findIndex((a) => re.test(nameOf(byId.get(a))));
    if (idx === -1) continue;
    hit += count;
    const above = ch.slice(idx + 1, idx + 1 + DEPTH).map((a) => nameOf(byId.get(a)));
    const key = nameOf(byId.get(ch[idx])) + ' <- ' + (above.join(' <- ') || '(root)');
    add(chains, key, count);
  }
  console.log(`--- callers of /${p}/ : ${pct(hit)} inclusive of ${total} samples`);
  for (const [k, v] of sorted(chains).slice(0, TOP)) console.log(`  ${pct(v)}  ${k}`);
  console.log();
}
