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

test('cyclic dedup cache THROWS loud, not silent undefined', () => {
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

// The encoder never pools a map key, so a key keeps the server's own JSON
// spelling — Cheshire's for a collection key — and sheds only an escape.
// Transcribed from a real `dedup-value` encode serialised with Cheshire: the
// repeated path-vector KEY rides verbatim while the equal values pool.
const PATH_KEY_ORIGINAL =
  '{"by-path":{"[:users 0 :name]":"ada"},"edits":{"[:users 0 :name]":3},' +
  '"sel":["users",0,"name"],"again":["users",0,"name"]}';

const PATH_KEY_WIRE =
  '{"rf.mcp/dedup-table":{' +
  '"de-dupe.cache/cache-1":["users",0,"name"],' +
  '"de-dupe.cache/cache-0":{"by-path":{"[:users 0 :name]":"ada"},' +
  '"edits":{"[:users 0 :name]":3},' +
  '"sel":"de-dupe.cache/cache-1","again":"de-dupe.cache/cache-1"}}}';

test('a map key decodes verbatim: a collection key keeps its raw JSON spelling, and an escaped key sheds one marker', () => {
  assert.deepEqual(
    decodeDedupEnvelope(JSON.parse(PATH_KEY_WIRE)),
    JSON.parse(PATH_KEY_ORIGINAL),
  );
  const cache = {
    [cacheId(0)]: { [escaped('cache-1')]: 'under-a-look-alike-key', a: cacheId(1), b: cacheId(1) },
    [cacheId(1)]: ['repeat', 'me'],
  };
  assert.deepEqual(decodeDedupEnvelope(envelope(cache)), {
    'de-dupe.cache/cache-1': 'under-a-look-alike-key',
    a: ['repeat', 'me'],
    b: ['repeat', 'me'],
  });
});

// A reference in key position could only decode to this decoder's spelling
// of the expanded key, which no server sends, so the table is malformed.
test('a cache reference in map-KEY position THROWS: the encoder never pools a key', () => {
  const cache = {
    [cacheId(0)]: { [cacheId(1)]: 'under-a-pooled-key' },
    [cacheId(1)]: ['a', 'b'],
  };
  assert.throws(() => decodeDedupEnvelope(envelope(cache)), /map key .* is a cache reference/);
});

// `decompress-cache` expands every entry before reading the root, so a real
// client rejects a malformed entry the root never reaches.
test('a malformed ORPHAN cache entry (unreachable from cache-0, dangling ref) still throws', () => {
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
test('an own "__proto__" payload key survives expansion, at the root and nested', () => {
  assert.deepEqual(
    decodeDedupEnvelope(JSON.parse(PROTO_KEY_WIRE)),
    JSON.parse(PROTO_KEY_ORIGINAL),
  );
});
