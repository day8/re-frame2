// Unit tests for `lib/token-match.cjs` (rf2-6i2yi4).
//
// ## The bug this pins
//
// `test/live-re-frame2-pair-cofx.cjs`'s malformed-cofx assertion used to
// be `if (!text.includes(m.reason)) throw`. `:invalid-cofx` is a strict
// substring of `:invalid-cofx-time-ms`, so a server returning
// `:invalid-cofx-time-ms` for what should have been a plain
// `:invalid-cofx` case (a non-map cofx, or unreadable EDN) still
// satisfies `text.includes(':invalid-cofx')` — 2 of the 3 malformed-cofx
// refusal reasons were never actually distinguished.
//
// FIX: `includesToken` anchors the match so a token only counts as
// present when it appears as a COMPLETE keyword (no word/hyphen
// character immediately before or after), not merely as a prefix of a
// longer one.
//
// ## What this proves
//
// Test 1 is the RED-then-GREEN proof: it first shows plain
// `String#includes` is fooled by the exact wire text this bug allowed
// through (the reason `:invalid-cofx` "matching" text that actually
// carries `:invalid-cofx-time-ms`), then shows `includesToken` correctly
// rejects that same input — i.e. reverting `live-re-frame2-pair-cofx.cjs`
// to `.includes` would make the tightened check pass on a case it must
// reject, and this test documents exactly why.

'use strict';

const test = require('node:test');
const assert = require('node:assert/strict');
const { includesToken } = require('../lib/token-match.cjs');

test('includesToken: RED-then-GREEN — plain .includes is fooled by a longer sibling reason, includesToken is not', () => {
  const text = 'dispatch refused: :invalid-cofx-time-ms (non-integer :rf/time-ms)';
  // RED: this is the exact false-pass the pre-fix assertion allowed —
  // `.includes(':invalid-cofx')` is true against text that actually
  // carries the DIFFERENT reason `:invalid-cofx-time-ms`.
  assert.equal(
    text.includes(':invalid-cofx'),
    true,
    'sanity: plain .includes conflates the two reasons (the bug)',
  );
  // GREEN: the tightened check correctly rejects it — this token is
  // absent as a COMPLETE keyword.
  assert.equal(includesToken(text, ':invalid-cofx'), false);
  // And the longer reason IS correctly found as a complete keyword.
  assert.equal(includesToken(text, ':invalid-cofx-time-ms'), true);
});

test('includesToken: matches the short reason when it genuinely stands alone', () => {
  const text = 'dispatch refused: :invalid-cofx (non-map cofx arg)';
  assert.equal(includesToken(text, ':invalid-cofx'), true);
  assert.equal(includesToken(text, ':invalid-cofx-time-ms'), false);
});

test('includesToken: token at the very start/end of text still matches', () => {
  assert.equal(includesToken(':invalid-cofx', ':invalid-cofx'), true);
  assert.equal(includesToken('prefix :invalid-cofx', ':invalid-cofx'), true);
  assert.equal(includesToken(':invalid-cofx suffix', ':invalid-cofx'), true);
});

test('includesToken: does not match when the token is a prefix/suffix of a surrounding keyword', () => {
  // Literal substring IS present in both, but each time as part of a
  // longer keyword — the anchoring must reject both.
  assert.equal(includesToken('reason: :invalid-cofxy', ':invalid-cofx'), false); // suffix-continued
  assert.equal(includesToken('reason: x:invalid-cofx', ':invalid-cofx'), false); // prefix-continued
});
