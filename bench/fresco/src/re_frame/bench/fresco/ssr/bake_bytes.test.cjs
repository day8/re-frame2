#!/usr/bin/env node
'use strict';
// Byte, code-unit and codepoint counts differ on this input.

const assert = require('node:assert');

const { utf8Bytes } = require('./driver.cjs');

const tests = [];
const test = (name, fn) => tests.push([name, fn]);

test('utf8Bytes counts UTF-8 bytes, not code units or codepoints', () => {
  assert.strictEqual(utf8Bytes('a\u2014\u2026\u{1D11E}'), 11);
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
