#!/usr/bin/env node
/*
 * SCAFFOLDING for rf2-xu6rx — deleted before the PR.
 *
 * Caller attribution over a saved CDP CPU profile.
 *
 * rf2-lnecd's finding, in its own words: "self time names the function; only
 * caller attribution names the fix." Self time said `clojure.string/replace`
 * and the obvious reading of that was wrong — memoising the function self time
 * pointed at moved nothing, and walking the profile's PARENT CHAIN instead
 * found the real caller in one step. This is that walk, run offline over one
 * capture so that every frame is attributed against the SAME profile rather
 * than against a fresh browser run per question.
 *
 *   node scripts/studio-attrib.cjs <profile.json> <substring> [depth] [top]
 *   node scripts/studio-attrib.cjs <profile.json> --self [top]
 *   node scripts/studio-attrib.cjs <profile.json> --substrate [top]
 */

'use strict';
const fs = require('fs');

const [file, want, ...rest] = process.argv.slice(2);
const profile = JSON.parse(fs.readFileSync(file, 'utf8'));
const byId = new Map(profile.nodes.map((n) => [n.id, n]));
const parent = new Map();
for (const n of profile.nodes) for (const c of n.children || []) parent.set(c, n.id);
const total = profile.samples.length;
const fname = (id) => {
  const n = byId.get(id);
  return n ? (n.callFrame.functionName || '(anon)') : '?';
};
const pct = (n) => `${(100 * n / total).toFixed(2)}%`;

if (want === '--self' || want === '--substrate') {
  const only = want === '--substrate';
  const self = new Map();
  for (const id of profile.samples) {
    const f = fname(id);
    if (only && !/^\$(cljs\$core|clojure\$|re_frame|goog)/.test(f)) continue;
    self.set(f, (self.get(f) || 0) + 1);
  }
  const top = Number(rest[0] || 40);
  for (const [k, n] of [...self.entries()].sort((a, b) => b[1] - a[1]).slice(0, top)) {
    console.log(`${pct(n).padStart(7)}  ${String(n).padStart(6)}  ${k}`);
  }
  console.log(`total samples: ${total}`);
  process.exit(0);
}

const depth = Number(rest[0] || 4);
const top = Number(rest[1] || 14);
const hits = new Map();
let matched = 0;
for (const id of profile.samples) {
  if (!fname(id).includes(want)) continue;
  matched += 1;
  const chain = [];
  let p = parent.get(id);
  for (let d = 0; d < depth && p !== undefined; d += 1) { chain.push(fname(p)); p = parent.get(p); }
  const key = chain.join('  <-  ');
  hits.set(key, (hits.get(key) || 0) + 1);
}
console.log(`--- callers of frames matching ${JSON.stringify(want)}: ${pct(matched)} of ${total} samples ---`);
for (const [k, n] of [...hits.entries()].sort((a, b) => b[1] - a[1]).slice(0, top)) {
  console.log(`${pct(n).padStart(7)}  ${String(n).padStart(6)}  ${k}`);
}

// Inclusive cost: every sample whose ANCESTRY passes through a matching frame.
// Self time under-reports a function that spends its life in callees; a
// memoisation removes the whole subtree, not the self time.
let incl = 0;
for (const id of profile.samples) {
  let p = id;
  for (let d = 0; d < 40 && p !== undefined; d += 1) {
    if (fname(p).includes(want)) { incl += 1; break; }
    p = parent.get(p);
  }
}
console.log(`inclusive (any ancestor matches): ${pct(incl)}`);
