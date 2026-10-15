#!/usr/bin/env node
'use strict';
// The CLJS byte-count suite checks arithmetic; this gate keeps callers wired to it.

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
