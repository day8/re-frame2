#!/usr/bin/env node
'use strict';
// NO PAGE-SIDE BYTE FIGURE IS A CODE-UNIT COUNT.
//
//     node src/re_frame/bench/fresco/bench_bytes.test.cjs   (from bench/fresco/)
//
// Runs in the lane's `npm run check`. What `rf.bench.fresco.lane/utf8-bytes`
// computes is pinned by `lane_bytes_cljs_test.cljs`; this file holds the
// converted sites to it, on their source text, because a lane namespace may not
// require `fs` (every `.cljs` here rides the `:fresco-bench` BROWSER build).
//
// The rot it refuses is a code-unit `count` wearing a byte label — a site
// converted back, or a new `count` added BESIDE the converted expression rather
// than in place of it. `count` is everywhere in this lane and legitimately so,
// so what is banned is narrow: `count` on the SAME LINE as a byte label.

const fs = require('node:fs');
const path = require('node:path');

const CONVERTED = [
  'clock_app.cljs',
  'hd8_clock_app.cljs',
  'shapes/census_clock_app.cljs',
  'walk_profile_app.cljs',
  'walk_vs_reagent_app.cljs',
  'ssr/spike_cljs_test.cljs',
  'ssr/spike_dom_cljs_test.cljs',
  'ssr/instance_key_payload_dom_cljs_test.cljs',
];

const offences = [];
for (const file of CONVERTED) {
  fs.readFileSync(path.join(__dirname, file), 'utf8')
    .split(/\r?\n/)
    .forEach((line, i) => {
      if (/bytes/i.test(line) && /\(count\b/.test(line)) offences.push(`${file}:${i + 1}: ${line.trim()}`);
    });
}

if (offences.length > 0) {
  console.error('FAIL NO line in a converted file pairs a bytes label with a bare `count`');
  console.error(`     a bytes label over \`count\`:\n       ${offences.join('\n       ')}`);
  console.error('\nbench_bytes.test.cjs: 1/1 failed');
  process.exit(1);
}
console.log('bench_bytes.test.cjs: 1 passed');
