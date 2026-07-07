// Unit tests for `_runner.cjs`'s `assertIsErrorMatchesOk` (rf2-6i2yi4
// finding 1).
//
// ## The bug this pins
//
// `assertIsErrorMatchesOk` is the universal isError <-> `:ok?`
// cross-check (spec/003-Tool-Catalogue.md §381) run against every
// `tools/call` response in `end-to-end-re-frame2-pair.cjs`. It used to
// read `resp.structuredContent` directly. A dedup-eligible tool ships
// its structured payload wrapped in `{:rf.mcp/dedup-table {...}}` — the
// raw field then has NO top-level `:ok?` slot at all (it is one layer
// down, inside the cache), so the pre-fix check's
// `!Object.prototype.hasOwnProperty.call(sc, 'ok?')` guard treated the
// envelope as "not `:ok?`-shaped" and silently no-op'd — exactly the
// dedup-wrapped envelope the check exists to grade. A real MCP client
// always expands the dedup table before reading semantic slots, so this
// was a genuine blind spot: a dedup-eligible tool's `:ok? false` result
// with `isError` decoupled would pass unobserved.
//
// FIX: route `resp` through the shared `structured()` dedup-decoder
// before reading `:ok?`.
//
// ## What this proves
//
// Test 1 is the RED-then-GREEN proof: it first shows the RAW wire field
// has no top-level `:ok?` (sanity — this is what the pre-fix code read),
// then shows `assertIsErrorMatchesOk` still catches the violation because
// it decodes first. Reverting to reading `resp.structuredContent`
// directly would make this test's `assert.throws` fail (silently
// pass/no-op instead).

'use strict';

const test = require('node:test');
const assert = require('node:assert/strict');

const { assertIsErrorMatchesOk } = require('./_runner.cjs');
const { DEDUP_TABLE_KEY, ROOT_CACHE_ID } = require('../lib/dedup-envelope.cjs');

function dedupWrapped(payload) {
  return { [DEDUP_TABLE_KEY]: { [ROOT_CACHE_ID]: payload } };
}

test('assertIsErrorMatchesOk: RED-then-GREEN — a dedup-wrapped :ok? false + isError:false is caught (rf2-6i2yi4 finding 1)', () => {
  const resp = {
    isError: false,
    structuredContent: dedupWrapped({ 'ok?': false, reason: 'boom' }),
  };
  // Sanity: the RAW wire field (what the pre-fix code read directly)
  // carries NO top-level `:ok?` slot at all — it's one layer down,
  // inside the dedup cache.
  assert.equal(
    Object.prototype.hasOwnProperty.call(resp.structuredContent, 'ok?'),
    false,
    'sanity: the raw wire structuredContent has no top-level :ok? slot',
  );
  assert.throws(
    () => assertIsErrorMatchesOk('probe', resp),
    /:ok\? false but isError is not true/,
    'must decode the dedup envelope to find :ok? false and catch the violation',
  );
});

test('assertIsErrorMatchesOk: converse — a dedup-wrapped :ok? true + isError:true is caught', () => {
  const resp = { isError: true, structuredContent: dedupWrapped({ 'ok?': true }) };
  assert.throws(() => assertIsErrorMatchesOk('probe', resp), /:ok\? true but isError is true/);
});

test('assertIsErrorMatchesOk: a legitimate dedup-wrapped :ok? false + isError:true does not throw', () => {
  const resp = { isError: true, structuredContent: dedupWrapped({ 'ok?': false }) };
  assert.doesNotThrow(() => assertIsErrorMatchesOk('probe', resp));
});

test('assertIsErrorMatchesOk: a legitimate dedup-wrapped :ok? true + isError:false does not throw', () => {
  const resp = { isError: false, structuredContent: dedupWrapped({ 'ok?': true }) };
  assert.doesNotThrow(() => assertIsErrorMatchesOk('probe', resp));
});

test('assertIsErrorMatchesOk: a non-dedup (plain) envelope still cross-checks as before', () => {
  assert.throws(
    () =>
      assertIsErrorMatchesOk('probe', {
        isError: false,
        structuredContent: { 'ok?': false },
      }),
    /:ok\? false but isError is not true/,
  );
  assert.doesNotThrow(() =>
    assertIsErrorMatchesOk('probe', {
      isError: false,
      structuredContent: { 'ok?': true },
    }),
  );
});

test('assertIsErrorMatchesOk: a non-:ok?-shaped result is out of scope (no throw)', () => {
  assert.doesNotThrow(() =>
    assertIsErrorMatchesOk('probe', { isError: false, structuredContent: { foo: 1 } }),
  );
  assert.doesNotThrow(() => assertIsErrorMatchesOk('probe', {}));
});
