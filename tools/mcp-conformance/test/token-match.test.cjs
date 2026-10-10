// Unit tests for `lib/token-match.cjs`. `:invalid-cofx` is a substring of
// `:invalid-cofx-time-ms`, so a plain `.includes(reason)` check accepts the
// wrong refusal reason; `includesToken` matches a complete keyword only.

'use strict';

const test = require('node:test');
const assert = require('node:assert/strict');
const { includesToken } = require('../lib/token-match.cjs');

test('includesToken matches a complete keyword only', () => {
  const text = 'dispatch refused: :invalid-cofx-time-ms (non-integer :rf/time-ms)';
  for (const [haystack, token, expected] of [
    [text, ':invalid-cofx', false],
    [text, ':invalid-cofx-time-ms', true],
    [':invalid-cofx', ':invalid-cofx', true],
    ['reason: x:invalid-cofx', ':invalid-cofx', false],
  ]) {
    assert.equal(includesToken(haystack, token), expected, `${token} in ${JSON.stringify(haystack)}`);
  }
});
