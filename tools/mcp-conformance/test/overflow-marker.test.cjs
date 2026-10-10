// Unit tests for `lib/overflow-marker.cjs`. The JVM contract pins
// `Overflow = [:map {:closed true} [:rf.mcp/overflow ...]]`: clients
// pattern-match on the one reserved key, so the wrapper is closed and
// single-keyed while the body stays open. The live gate reads the marker from
// both result slots and requires the two bodies to agree.

'use strict';

const test = require('node:test');
const assert = require('node:assert/strict');
const {
  unwrapClosedOverflow,
  assertOverflowBody,
  validateOverflowWrapper,
  validateOverflowText,
  assertBodiesAgree,
} = require('../lib/overflow-marker.cjs');

function validBody(overrides) {
  return {
    limit: 'reached',
    'cap-tokens': 5000,
    'token-count': 6250,
    tool: 'eval-cljs',
    hint: 'raise the cap',
    ...overrides,
  };
}

function overflowText(cap, count) {
  return `{:rf.mcp/overflow {:limit :reached :cap-tokens ${cap} :token-count ${count} ` +
    ':tool "eval-cljs" :hint "raise the cap"}}';
}

test('unwrapClosedOverflow rejects everything but the closed single-key wrapper', () => {
  for (const [outer, pattern] of [
    [{ 'rf.mcp/overflowed': validBody() }, /CLOSED single-key map/],
    [{ 'rf.mcp/overflow': validBody(), 'rf.mcp/summary': { type: 'map' } }, /CLOSED single-key map/],
    [[validBody()], /not a map/],
    [null, /not a map/],
    ['rf.mcp/overflow', /not a map/],
  ]) {
    assert.throws(() => unwrapClosedOverflow(outer, 'test'), pattern, JSON.stringify(outer));
  }
});

test('validateOverflowWrapper: accepts additive fields inside the (open) body', () => {
  const body = validBody({ 'extra-field': 'ok', nested: { a: 1 } });
  assert.deepEqual(validateOverflowWrapper({ 'rf.mcp/overflow': body }, 'test'), body);
});

test('assertOverflowBody rejects a missing field, a wrong :limit and a token-count not above the cap', () => {
  const hintless = validBody();
  delete hintless.hint;
  for (const [body, pattern] of [
    [hintless, /:hint MUST be/],
    [validBody({ limit: 'exceeded' }), /:limit MUST be/],
    [validBody({ 'token-count': 5000 }), /:token-count MUST exceed :cap-tokens/],
  ]) {
    assert.throws(() => assertOverflowBody(body, 'test'), pattern);
  }
});

test('fractional :cap-tokens / :token-count are rejected through BOTH slots (rf2-gwye.41)', () => {
  for (const [cap, count, field] of [[5000.5, 6250, 'cap-tokens'], [5000, 6250.5, 'token-count']]) {
    const want = new RegExp(':' + field + ' MUST be int');
    assert.throws(() => validateOverflowText(overflowText(cap, count), 'text-slot'), want);
    assert.throws(
      () => validateOverflowWrapper(
        { 'rf.mcp/overflow': validBody({ 'cap-tokens': cap, 'token-count': count }) },
        'structured',
      ),
      want,
    );
  }
  // The canonical integer body parses from the text slot and agrees with the
  // structured slot.
  assert.doesNotThrow(() => assertBodiesAgree(
    validateOverflowText(overflowText(5000, 6250), 'text-slot'),
    validateOverflowWrapper({ 'rf.mcp/overflow': validBody() }, 'structured'),
    'dual-slot',
  ));
});

// The EDN text and the `clj->js` projection list the same keys in different
// orders.
test('assertBodiesAgree: accepts two order-different but structurally-equal bodies', () => {
  const structuredBody = {
    hint: 'raise the cap', tool: 'eval-cljs', 'token-count': 6250, 'cap-tokens': 5000, limit: 'reached',
  };
  assert.doesNotThrow(() => assertBodiesAgree(validBody(), structuredBody, 'dual-slot'));
});

test('assertBodiesAgree rejects dual-slot drift: a differing value or a field in one slot only', () => {
  for (const drifted of [validBody({ tool: 'snapshot' }), validBody({ 'leaked-sibling': 'only-in-structured' })]) {
    assert.throws(() => assertBodiesAgree(validBody(), drifted, 'dual-slot'), /DRIFTED/);
  }
});
