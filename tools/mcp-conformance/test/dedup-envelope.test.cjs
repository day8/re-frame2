// Unit tests for the Node-side dedup-envelope decoder.
//
// Uses Node's built-in `node:test` (same posture as the other
// `*.test.cjs` unit suites — no extra dev-dependency).
//
// ## The contract this pins
//
// `lib/dedup-envelope.cjs` `expandCache` reconstructs the
// structural-dedup cache (`:rf.mcp/dedup-table`) the MCP servers emit on
// the wire. The cycle-guard installs a distinct in-progress sentinel and
// throws `cyclic dedup cache` when a ref resolves to it, so a cache ref
// that re-enters an in-progress entry (a CYCLE) fails LOUD rather than
// resolving to `undefined` (silent corruption — a stray `undefined` hole
// in the reconstruction). Real de-dupe caches are acyclic (refs always
// point at strictly-smaller subtrees), so a cycle is adversarial input —
// and a conformance decoder MUST fail loud on malformed / adversarial
// input, not silently emit garbage. These tests pin:
//
//   1. a cyclic cache table THROWS (with a `cyclic` message) — the
//      decoder rejects it rather than returning an `undefined` hole.
//   2. the acyclic happy path (shared subtrees, nested refs)
//      reconstructs correctly — real caches decode cleanly.
//   3. a dangling ref (no matching entry) throws its own distinct
//      error, pinned alongside.

'use strict';

const test = require('node:test');
const assert = require('node:assert/strict');

const { decodeDedupEnvelope, DEDUP_TABLE_KEY, CACHE_NS_PREFIX } =
  require('../lib/dedup-envelope.cjs');

// Build a cache id string for entry N (`de-dupe.cache/cache-N`).
function cacheId(n) {
  return CACHE_NS_PREFIX + 'cache-' + n;
}

function envelope(cache) {
  return { [DEDUP_TABLE_KEY]: cache };
}

test('cyclic dedup cache THROWS loud (rf2-87h71e LOW), not silent undefined', () => {
  // cache-0 -> { :self <ref to cache-0> } — a self-referential cycle.
  const cache = {
    [cacheId(0)]: { self: cacheId(0) },
  };
  assert.throws(
    () => decodeDedupEnvelope(envelope(cache)),
    (err) => {
      assert.ok(err instanceof Error, 'throws an Error');
      assert.match(
        err.message,
        /cyclic dedup cache/i,
        'the error names the cyclic cache (not a generic failure)',
      );
      return true;
    },
    'a self-referential cache must throw, not return an `undefined` hole',
  );
});

test('mutually-recursive (two-entry) cycle THROWS', () => {
  // cache-0 -> { :b <ref cache-1> }, cache-1 -> { :a <ref cache-0> }.
  const cache = {
    [cacheId(0)]: { b: cacheId(1) },
    [cacheId(1)]: { a: cacheId(0) },
  };
  assert.throws(
    () => decodeDedupEnvelope(envelope(cache)),
    /cyclic dedup cache/i,
    'a mutually-recursive cache must throw',
  );
});

test('acyclic cache with a shared subtree reconstructs correctly (happy path intact)', () => {
  // cache-0 references cache-1 TWICE (a shared, strictly-smaller subtree
  // — the legitimate de-dupe shape). Must expand without throwing and
  // share the subtree structurally.
  const cache = {
    [cacheId(0)]: { left: cacheId(1), right: cacheId(1) },
    [cacheId(1)]: { leaf: 42 },
  };
  const out = decodeDedupEnvelope(envelope(cache));
  assert.deepEqual(out, { left: { leaf: 42 }, right: { leaf: 42 } });
  // Structural sharing: both refs resolve to the SAME memoised object.
  assert.equal(out.left, out.right, 'shared subtree is structurally shared');
});

test('nested acyclic refs through arrays + objects reconstruct correctly', () => {
  const cache = {
    [cacheId(0)]: { items: [cacheId(1), cacheId(2)] },
    [cacheId(1)]: { id: 'a' },
    [cacheId(2)]: { id: 'b', nested: cacheId(1) },
  };
  const out = decodeDedupEnvelope(envelope(cache));
  assert.deepEqual(out, {
    items: [{ id: 'a' }, { id: 'b', nested: { id: 'a' } }],
  });
});

test('dangling ref (no matching entry) still throws its own distinct error', () => {
  const cache = {
    [cacheId(0)]: { missing: cacheId(9) },
  };
  assert.throws(
    () => decodeDedupEnvelope(envelope(cache)),
    /no matching entry/i,
    'a ref with no cache entry throws the missing-entry error (unchanged)',
  );
});

test('non-dedup envelope passes through untouched', () => {
  const plain = { 'ok?': true, value: [1, 2, 3] };
  assert.equal(decodeDedupEnvelope(plain), plain);
});

// ---------------------------------------------------------------------
// rf2-6i2yi4 finding 5: a de-duped map KEY (not just VALUE) is expanded.
//
// The real `re-frame.mcp-base.dedup/expand` dispatches on `map-entry?` before
// `coll?` and expands BOTH halves of every map-entry; `cachable?`
// permits a de-duped map key. A decoder that only recurses into `v[k]`
// while using `k` verbatim leaves a de-duped key as the raw
// "de-dupe.cache/cache-N" placeholder string — before this fix, the
// assertion below would have found `Object.keys(out)` still carrying
// that raw placeholder instead of the expanded key.
// ---------------------------------------------------------------------

test('a de-duped map KEY is expanded, not left as the raw cache-ref placeholder (rf2-6i2yi4 finding 5)', () => {
  // cache-0's single entry has a KEY that is itself a cache reference to
  // a de-duped vector — the "path-keyed structure" shape the finding
  // describes.
  const cache = {
    [cacheId(0)]: { [cacheId(1)]: 'value-for-vector-key' },
    [cacheId(1)]: ['a', 'b'],
  };
  const out = decodeDedupEnvelope(envelope(cache));
  // JS object keys must be strings; a non-string expanded key (here an
  // array) is rendered via its canonical JSON form rather than left
  // as the unexpanded ref.
  const expectedKey = JSON.stringify(['a', 'b']);
  assert.deepEqual(Object.keys(out), [expectedKey]);
  assert.equal(out[expectedKey], 'value-for-vector-key');
});

test('a de-duped map key that expands to a plain string is used directly (no JSON-quoting)', () => {
  const cache = {
    [cacheId(0)]: { [cacheId(1)]: 'v' },
    [cacheId(1)]: 'plain-string-key',
  };
  const out = decodeDedupEnvelope(envelope(cache));
  assert.deepEqual(out, { 'plain-string-key': 'v' });
});

// ---------------------------------------------------------------------
// rf2-6i2yi4 finding 7: eagerly validate EVERY cache entry, not just
// those reachable from cache-0.
//
// The real `re-frame.mcp-base.dedup/decompress-cache` expands every key before
// picking cache-0 off the result. Before this fix, `expandCache` called
// only `expandEntry(ROOT_CACHE_ID)`, so a malformed entry unreachable
// from the root (an "orphan") was never visited and decoded cleanly —
// grading GREEN a wire payload a real client rejects outright. These
// tests would NOT have thrown before the fix (the malformed orphan was
// simply never visited).
// ---------------------------------------------------------------------

test('a malformed ORPHAN cache entry (unreachable from cache-0, dangling ref) still throws (rf2-6i2yi4 finding 7)', () => {
  const cache = {
    [cacheId(0)]: { fine: 1 }, // well-formed, does not reference cache-1
    [cacheId(1)]: { missing: cacheId(9) }, // orphan: dangling ref
  };
  assert.throws(
    () => decodeDedupEnvelope(envelope(cache)),
    /no matching entry/i,
    'an orphaned entry with a dangling ref must still be rejected',
  );
});

test('a malformed ORPHAN cache entry (unreachable from cache-0, self-cyclic) still throws', () => {
  const cache = {
    [cacheId(0)]: { fine: 1 },
    [cacheId(1)]: { self: cacheId(1) }, // orphan: self-cyclic
  };
  assert.throws(
    () => decodeDedupEnvelope(envelope(cache)),
    /cyclic dedup cache/i,
    'an orphaned self-cyclic entry must still be rejected',
  );
});

test('a well-formed orphan entry is harmless — root value unaffected', () => {
  const cache = {
    [cacheId(0)]: { fine: 1 },
    [cacheId(1)]: { unused: 'harmless' }, // orphan but well-formed
  };
  const out = decodeDedupEnvelope(envelope(cache));
  assert.deepEqual(out, { fine: 1 });
});
