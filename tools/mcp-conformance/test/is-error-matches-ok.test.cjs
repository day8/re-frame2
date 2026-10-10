// Unit tests for `_runner.cjs`'s `assertIsErrorMatchesOk`, the universal
// isError <-> `:ok?` cross-check (spec/003-Tool-Catalogue.md §381). A
// dedup-eligible tool's `:ok?` sits one layer down inside the cache, so the
// check reads it only after decoding the dedup envelope.

'use strict';

const test = require('node:test');
const assert = require('node:assert/strict');

const { assertIsErrorMatchesOk } = require('./_runner.cjs');
const { DEDUP_TABLE_KEY, ROOT_CACHE_ID } = require('../lib/dedup-envelope.cjs');

function dedupWrapped(payload) {
  return { [DEDUP_TABLE_KEY]: { [ROOT_CACHE_ID]: payload } };
}

test('assertIsErrorMatchesOk grades :ok? against isError after dedup decoding', () => {
  const FALSE_NOT_ERROR = /:ok\? false but isError is not true/;
  for (const [label, resp, expected] of [
    ['dedup-wrapped :ok? false beside isError false',
      { isError: false, structuredContent: dedupWrapped({ 'ok?': false }) }, FALSE_NOT_ERROR],
    ['dedup-wrapped :ok? true beside isError true',
      { isError: true, structuredContent: dedupWrapped({ 'ok?': true }) }, /:ok\? true but isError is true/],
    ['plain :ok? false beside isError false',
      { isError: false, structuredContent: { 'ok?': false } }, FALSE_NOT_ERROR],
    // Grading the decoded inner `ok? true` would erase the sibling `ok? false`.
    ['a dedup wrapper with a sibling key',
      { isError: false, structuredContent: { ...dedupWrapped({ 'ok?': true }), 'ok?': false } },
      /CLOSED single-key map/],
    ['dedup-wrapped :ok? false beside isError true',
      { isError: true, structuredContent: dedupWrapped({ 'ok?': false }) }, null],
    ['a result with no :ok? slot',
      { isError: false, structuredContent: { foo: 1 } }, null],
  ]) {
    if (expected) {
      assert.throws(() => assertIsErrorMatchesOk('probe', resp), expected, label);
    } else {
      assert.doesNotThrow(() => assertIsErrorMatchesOk('probe', resp), label);
    }
  }
});
