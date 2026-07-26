#!/usr/bin/env node
/*
 * SCAFFOLDING for rf2-xu6rx — deleted before the PR.
 *
 * Bucket every sample of each alternated profile by whose code the frame is,
 * and print the substrate share per arm — plus the substrate share expressed
 * against the React/host work no change in this bead can touch, which is the
 * only denominator here that is the same job in every capture.
 *
 *   node scripts/studio-profile-report.cjs [dir]
 */

'use strict';
const fs = require('fs');
const path = require('path');

const DIR = process.argv[2] || path.join(__dirname, '..', 'out', 'ab', 'prof');

function buckets(profile) {
  const byId = new Map(profile.nodes.map((n) => [n.id, n]));
  const total = profile.samples.length;
  const b = {};
  const add = (k) => { b[k] = (b[k] || 0) + 1; };
  for (const id of profile.samples) {
    const n = byId.get(id);
    const fn = n ? (n.callFrame.functionName || '(anonymous)') : '(unknown)';
    const url = n ? (n.callFrame.url || '') : '';
    if (/^\(/.test(fn)) add(fn);
    else if (/freehand|re_frame/.test(fn)) add('substrate');
    else if (/^\$cljs\$core|^\$clojure\$/.test(fn)) add('substrate');
    else if (/^\$goog\$/.test(fn)) add('substrate');
    else if (url === '') add('host');
    else add('react');
  }
  const pc = (k) => (100 * (b[k] || 0) / total);
  return { total, substrate: pc('substrate'), react: pc('react'), host: pc('host'),
           ratio: pc('substrate') / (pc('react') + pc('host')) };
}

const rows = [];
for (const f of fs.readdirSync(DIR).filter((x) => /^raw-\d+-(base|tuned)\.json$/.test(x))) {
  const [, round, arm] = f.match(/^raw-(\d+)-(base|tuned)\.json$/);
  rows.push({ round: Number(round), arm, ...buckets(JSON.parse(fs.readFileSync(path.join(DIR, f), 'utf8'))) });
}
rows.sort((a, b) => a.round - b.round || a.arm.localeCompare(b.arm));

const f2 = (x) => x.toFixed(2);
console.log(['round', 'arm', 'substrate %', 'react %', 'host %', 'substrate/(react+host)', 'samples'].join('\t'));
for (const r of rows) {
  console.log([r.round, r.arm, f2(r.substrate), f2(r.react), f2(r.host), r.ratio.toFixed(4), r.total].join('\t'));
}
for (const arm of ['base', 'tuned']) {
  const g = rows.filter((r) => r.arm === arm);
  if (!g.length) continue;
  const m = (k) => g.reduce((a, r) => a + r[k], 0) / g.length;
  const rng = (k) => `[${f2(Math.min(...g.map((r) => r[k])))}-${f2(Math.max(...g.map((r) => r[k])))}]`;
  console.log(`\n${arm}: substrate ${f2(m('substrate'))}% ${rng('substrate')}   ratio ${m('ratio').toFixed(4)}`);
}
