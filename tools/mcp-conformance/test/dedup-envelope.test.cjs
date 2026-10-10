// Unit tests for `lib/dedup-envelope.cjs`, the Node-side mirror of
// `re-frame.mcp-base.dedup/expand`. The reference grammar it decodes is
// stated in `tools/mcp-base/spec/dedup.md` §Reference grammar.

'use strict';

const test = require('node:test');
const assert = require('node:assert/strict');

const { decodeDedupEnvelope, DEDUP_TABLE_KEY, CACHE_NS_PREFIX } =
  require('../lib/dedup-envelope.cjs');

function cacheId(n) {
  return CACHE_NS_PREFIX + 'cache-' + n;
}

// What the encoder emits for a payload token that would otherwise read as a
// reference.
function escaped(name) {
  return CACHE_NS_PREFIX + '!' + name;
}

function envelope(cache) {
  return { [DEDUP_TABLE_KEY]: cache };
}

test('cyclic dedup cache THROWS loud (rf2-87h71e LOW), not silent undefined', () => {
  assert.throws(
    () => decodeDedupEnvelope(envelope({ [cacheId(0)]: { self: cacheId(0) } })),
    /cyclic dedup cache/,
  );
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

// `expand` decodes both halves of every map entry, so a de-duped key arrives
// as a reference too. A JS key must be a string: a structured key takes its
// JSON form, a string key is used as itself.
test('map keys decode like values: a de-duped key, structured or string, and an escaped key', () => {
  const cache = {
    [cacheId(0)]: {
      [cacheId(1)]: 'under-a-structured-key',
      [cacheId(2)]: 'under-a-string-key',
      [escaped('cache-1')]: 'under-a-look-alike-key',
    },
    [cacheId(1)]: ['a', 'b'],
    [cacheId(2)]: 'plain-string-key',
  };
  assert.deepEqual(decodeDedupEnvelope(envelope(cache)), {
    '["a","b"]': 'under-a-structured-key',
    'plain-string-key': 'under-a-string-key',
    'de-dupe.cache/cache-1': 'under-a-look-alike-key',
  });
});

// `decompress-cache` expands every entry before reading the root, so a real
// client rejects a malformed entry the root never reaches.
test('a malformed ORPHAN cache entry (unreachable from cache-0, dangling ref) still throws (rf2-6i2yi4 finding 7)', () => {
  const cache = {
    [cacheId(0)]: { fine: 1 },
    [cacheId(1)]: { missing: cacheId(9) },
  };
  assert.throws(() => decodeDedupEnvelope(envelope(cache)), /no matching entry/);
});

test('payload strings in the reference namespace stay data beside a real reference, shedding one escape marker', () => {
  const cache = {
    [cacheId(0)]: {
      literal: CACHE_NS_PREFIX + 'not-a-ref',
      'look-alike': escaped('cache-1'),
      twice: escaped('!cache-1'),
      a: cacheId(1),
      b: cacheId(1),
    },
    [cacheId(1)]: { big: ['repeat', 'me'] },
  };
  const out = decodeDedupEnvelope(envelope(cache));
  assert.deepEqual(out, {
    literal: 'de-dupe.cache/not-a-ref',
    'look-alike': 'de-dupe.cache/cache-1',
    twice: 'de-dupe.cache/!cache-1',
    a: { big: ['repeat', 'me'] },
    b: { big: ['repeat', 'me'] },
  });
  assert.equal(out.a, out.b, 'a shared subtree decodes to one object');
});

// Transcribed from a real `dedup-value` encode serialised with Cheshire. Both
// strings go through JSON.parse, as the SDK does: an object literal spelling
// `__proto__:` would set the prototype rather than carry the key.
const PROTO_KEY_ORIGINAL =
  '{"__proto__":{"marker":"root-payload"},' +
  '"nested":{"__proto__":{"marker":"nested-payload"}},' +
  '"a":{"items":["repeat","the","subtree"]},' +
  '"b":{"items":["repeat","the","subtree"]}}';

const PROTO_KEY_WIRE =
  '{"rf.mcp/dedup-table":{' +
  '"de-dupe.cache/cache-2":["repeat","the","subtree"],' +
  '"de-dupe.cache/cache-1":{"items":"de-dupe.cache/cache-2"},' +
  '"de-dupe.cache/cache-0":{"__proto__":{"marker":"root-payload"},' +
  '"nested":{"__proto__":{"marker":"nested-payload"}},' +
  '"a":"de-dupe.cache/cache-1","b":"de-dupe.cache/cache-1"}}}';

// Strict deep equality compares own keys and prototypes, so it fails if the
// key was assigned through the inherited `__proto__` setter.
test('an own "__proto__" payload key survives expansion, at the root and nested (rf2-gwye.36)', () => {
  assert.deepEqual(
    decodeDedupEnvelope(JSON.parse(PROTO_KEY_WIRE)),
    JSON.parse(PROTO_KEY_ORIGINAL),
  );
});

// The JVM contract pins `DedupTable` as a closed single-key map; expanding
// the cache would erase a sibling, grading the response on a sanitised value.
test('a dedup wrapper carrying a sibling key is rejected', () => {
  const cache = { [cacheId(0)]: { 'ok?': true, value: 42 } };
  assert.throws(
    () => decodeDedupEnvelope({ ...envelope(cache), 'ok?': false, reason: 'failure' }),
    /CLOSED single-key map/,
  );
});
