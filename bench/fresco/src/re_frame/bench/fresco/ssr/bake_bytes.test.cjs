#!/usr/bin/env node
'use strict';
// THE BAKE MANIFEST COUNTS BYTES, NOT CODE UNITS.
//
//     node bench/fresco/src/re_frame/bench/fresco/ssr/bake_bytes.test.cjs
//
// `driver.cjs bake` reports `documentBytes`, `bodyBytes` and `payloadBytes`
// through `utf8Bytes`, and this corpus is not ASCII: every row's title carries
// an em dash and the `defhost` fallback an ellipsis, so a code-unit count reads
// `dogfood-snapshot` as 3101 for a document of 3119 bytes. `bake` itself checks
// each column against `fs.statSync` of the file it wrote, but that costs a
// shadow-cljs compile, so the arithmetic is pinned here over one input that
// DISCRIMINATES: U+2014 and U+2026 are 1 code unit and 3 bytes, and the
// astral-plane U+1D11E is 1 codepoint, TWO code units and FOUR bytes, so code
// units (58), codepoints (57) and bytes (64) all differ.
//
// Every non-ASCII character is written as a `\u` escape rather than a
// literal. A literal would let an encoding-normalising editor quietly
// ASCII-fy this file and leave the assertion below passing over an input
// that no longer discriminates — a green gate measuring nothing, which is
// the failure mode of the defect it exists to pin.
//
// Run by `npm run check` in bench/fresco/.

const assert = require('node:assert');

const { utf8Bytes } = require('./driver.cjs');

const EM_DASH = '\u2014';   // U+2014 EM DASH — every corpus row's title carries one
const ELLIPSIS = '\u2026';  // U+2026 HORIZONTAL ELLIPSIS — the `defhost` fallback's "loading…"
const CLEF = '\u{1D11E}'; // U+1D11E MUSICAL SYMBOL G CLEF — astral plane

const tests = [];
const test = (name, fn) => tests.push([name, fn]);

test('utf8Bytes counts UTF-8 bytes, not code units or codepoints', () => {
  // The title carries the product name, so a rename moves the figure.
  const s = `<title>Fresco SSR ${EM_DASH} defhost</title><span>loading${ELLIPSIS}</span>${CLEF}`;
  assert.strictEqual(utf8Bytes(s), 64);
});

let failed = 0;
for (const [name, fn] of tests) {
  try {
    fn();
  } catch (err) {
    failed += 1;
    console.error(`FAIL  ${name}\n      ${err.message}`);
  }
}

if (failed > 0) {
  console.error(`\nbake_bytes.test.cjs: ${failed}/${tests.length} failed`);
  process.exit(1);
}
console.log(`bake_bytes.test.cjs: ${tests.length} passed`);
