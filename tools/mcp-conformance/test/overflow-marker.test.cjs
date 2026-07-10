// Unit tests for `lib/overflow-marker.cjs` (rf2-3fc89f.20).
//
// ## The bug this pins
//
// The live gate `live-re-frame2-pair-overflow.cjs` used to parse the EDN and
// return `outer['rf.mcp/overflow']` WITHOUT checking that key was the sole
// top-level key. A mixed envelope
// `{:rf.mcp/overflow {...valid body...} :unexpected "sibling"}` therefore
// reached the body assertions and PASSED, even though the JVM contract pins
// `Overflow = [:map {:closed true} [:rf.mcp/overflow ...]]` — CLOSED and
// SINGLE-KEY, because clients pattern-match on exactly one reserved
// discriminator key. The live gate also validated only `content[0].text`
// and never `structuredContent`, so the two slots could drift silently.
//
// ## What this proves
//
// Test 1 is the RED-then-GREEN proof: it first shows the pre-fix
// extraction-only logic (replicated inline) ACCEPTS the malformed mixed
// envelope, then shows the new closed-wrapper validator REJECTS it. The
// remaining tests cover the lookalike-key / array / multi-key / missing
// rejections, additive-body acceptance, the required-field + token-count
// invariants, and the dual-slot agreement check.

'use strict';

const test = require('node:test');
const assert = require('node:assert/strict');
const { parseEDNString } = require('edn-data');
const {
  EDN_PARSE_OPTS,
  isPlainObject,
  unwrapClosedOverflow,
  assertOverflowBody,
  validateOverflowWrapper,
  validateOverflowText,
  bodiesEqual,
  assertBodiesAgree,
} = require('../lib/overflow-marker.cjs');

// A canonical, well-formed body reused across cases.
function validBody(overrides) {
  return Object.assign(
    {
      limit: 'reached',
      'cap-tokens': 5000,
      'token-count': 6250,
      tool: 'eval-cljs',
      hint: 'Slice the value at the call-site before returning.',
    },
    overrides,
  );
}

// The exact malformed shape from the bead reproduction.
const MALFORMED_MIXED_ENVELOPE =
  '{:rf.mcp/overflow {:limit :reached :cap-tokens 5000 :token-count 6250 ' +
  ':tool "eval-cljs" :hint "Slice"} :unexpected "sibling"}';

// The pre-fix extraction-only logic, replicated so the RED half of test 1
// can demonstrate the exact false-pass this bug allowed.
function legacyExtractionOnly(text) {
  let outer;
  try {
    outer = parseEDNString(text, EDN_PARSE_OPTS);
  } catch (_e) {
    return null;
  }
  if (!outer || typeof outer !== 'object') return null;
  return outer['rf.mcp/overflow'] || null;
}

test('RED-then-GREEN: legacy extraction-only accepts an extra top-level sibling; the closed-wrapper validator rejects it', () => {
  // RED: the pre-fix logic returns the valid inner body even though the
  // envelope carries an unexpected sibling key — the exact false-green.
  const legacy = legacyExtractionOnly(MALFORMED_MIXED_ENVELOPE);
  assert.ok(
    legacy && legacy.limit === 'reached',
    'sanity: the extraction-only parser accepts the mixed envelope (the bug)',
  );
  // The outer genuinely has two top-level keys.
  const outer = parseEDNString(MALFORMED_MIXED_ENVELOPE, EDN_PARSE_OPTS);
  assert.deepEqual(Object.keys(outer).sort(), ['rf.mcp/overflow', 'unexpected']);
  // GREEN: the closed-wrapper validator rejects the sibling key.
  assert.throws(
    () => validateOverflowText(MALFORMED_MIXED_ENVELOPE, 'test'),
    /CLOSED single-key map/,
    'closed-wrapper validator must reject a wrapper with a sibling top-level key',
  );
});

test('unwrapClosedOverflow: rejects a lookalike wrapper key (rf.mcp/overflowed)', () => {
  const outer = { 'rf.mcp/overflowed': validBody() };
  assert.throws(() => unwrapClosedOverflow(outer, 'test'), /CLOSED single-key map/);
});

test('unwrapClosedOverflow: rejects a missing marker (empty map and a non-overflow envelope)', () => {
  assert.throws(() => unwrapClosedOverflow({}, 'test'), /CLOSED single-key map/);
  assert.throws(
    () => unwrapClosedOverflow({ 'ok?': true, value: 'xxxxx' }, 'test'),
    /CLOSED single-key map/,
  );
});

test('unwrapClosedOverflow: rejects arrays and non-map scalars', () => {
  assert.throws(() => unwrapClosedOverflow([validBody()], 'test'), /not a map/);
  assert.throws(() => unwrapClosedOverflow(null, 'test'), /not a map/);
  assert.throws(() => unwrapClosedOverflow('rf.mcp/overflow', 'test'), /not a map/);
  assert.throws(() => unwrapClosedOverflow(42, 'test'), /not a map/);
});

test('unwrapClosedOverflow: rejects multiple top-level keys even when both look plausible', () => {
  const outer = { 'rf.mcp/overflow': validBody(), 'rf.mcp/summary': { type: 'map' } };
  assert.throws(() => unwrapClosedOverflow(outer, 'test'), /CLOSED single-key map/);
});

test('validateOverflowWrapper: accepts the canonical closed wrapper and returns the body', () => {
  const body = validateOverflowWrapper({ 'rf.mcp/overflow': validBody() }, 'test');
  assert.equal(body.tool, 'eval-cljs');
  assert.equal(body['token-count'], 6250);
});

test('validateOverflowWrapper: accepts additive fields inside the (open) body', () => {
  const body = validBody({ 'extra-field': 'ok', nested: { a: 1 } });
  const out = validateOverflowWrapper({ 'rf.mcp/overflow': body }, 'test');
  // The body is OPEN — additive keys survive validation untouched.
  assert.equal(out['extra-field'], 'ok');
  assert.deepEqual(out.nested, { a: 1 });
});

test('assertOverflowBody: rejects a body missing each required field', () => {
  for (const field of ['limit', 'cap-tokens', 'token-count', 'tool', 'hint']) {
    const body = validBody();
    delete body[field];
    assert.throws(
      () => assertOverflowBody(body, 'test'),
      new RegExp(':' + field + ' MUST be'),
      'missing :' + field + ' must be rejected',
    );
  }
});

test('assertOverflowBody: rejects a wrong :limit enum and non-numeric token fields', () => {
  assert.throws(() => assertOverflowBody(validBody({ limit: 'exceeded' }), 'test'), /:limit MUST be/);
  assert.throws(() => assertOverflowBody(validBody({ 'cap-tokens': '5000' }), 'test'), /:cap-tokens MUST be/);
  assert.throws(() => assertOverflowBody(validBody({ 'token-count': null }), 'test'), /:token-count MUST be/);
});

test('assertOverflowBody: rejects token-count <= cap-tokens (degenerate tripped cap)', () => {
  // Equal is a violation: a tripped cap MUST strictly exceed the budget.
  assert.throws(
    () => assertOverflowBody(validBody({ 'cap-tokens': 5000, 'token-count': 5000 }), 'test'),
    /token-count MUST exceed :cap-tokens/,
  );
  // Below the cap is a violation too.
  assert.throws(
    () => assertOverflowBody(validBody({ 'cap-tokens': 5000, 'token-count': 4999 }), 'test'),
    /token-count MUST exceed :cap-tokens/,
  );
});

test('validateOverflowText: parses a canonical EDN text slot and validates it', () => {
  const text =
    '{:rf.mcp/overflow {:limit :reached :cap-tokens 5000 :token-count 6250 ' +
    ':tool "eval-cljs" :hint "Slice the value."}}';
  const body = validateOverflowText(text, 'text-slot');
  assert.equal(body.limit, 'reached');
  assert.equal(body.tool, 'eval-cljs');
});

test('validateOverflowText: rejects garbage text and non-string input', () => {
  // `edn-data` is lenient — most garbage parses to null/partial rather
  // than throwing — so a truncated/garbage marker is rejected downstream
  // as a non-overflow-wrapper. Either way the text is REJECTED, never
  // accepted as a valid marker (the property that matters).
  assert.throws(
    () => validateOverflowText('{:rf.mcp/overflow', 'text-slot'),
    /not a map|not parseable EDN|CLOSED single-key map/,
  );
  assert.throws(
    () => validateOverflowText('}{ garbage', 'text-slot'),
    /not a map|not parseable EDN|CLOSED single-key map/,
  );
  // A non-string text slot is rejected up front with clear context.
  assert.throws(() => validateOverflowText(42, 'text-slot'), /not a string/);
});

test('assertBodiesAgree: accepts two order-different but structurally-equal bodies', () => {
  const textBody = { limit: 'reached', 'cap-tokens': 5000, 'token-count': 6250, tool: 'eval-cljs', hint: 'x' };
  // structuredContent projection would carry the same data in a different
  // key order — bodiesEqual/assertBodiesAgree must be order-insensitive.
  const structuredBody = { hint: 'x', tool: 'eval-cljs', 'token-count': 6250, 'cap-tokens': 5000, limit: 'reached' };
  assert.equal(bodiesEqual(textBody, structuredBody), true);
  assert.doesNotThrow(() => assertBodiesAgree(textBody, structuredBody, 'dual-slot'));
});

test('assertBodiesAgree: rejects dual-slot drift (differing value)', () => {
  const textBody = validBody();
  const structuredBody = validBody({ tool: 'snapshot' }); // drifted tool name
  assert.equal(bodiesEqual(textBody, structuredBody), false);
  assert.throws(() => assertBodiesAgree(textBody, structuredBody, 'dual-slot'), /DRIFTED/);
});

test('assertBodiesAgree: rejects dual-slot drift (extra field in one slot only)', () => {
  const textBody = validBody();
  const structuredBody = validBody({ 'leaked-sibling': 'only-in-structured' });
  assert.equal(bodiesEqual(textBody, structuredBody), false);
  assert.throws(() => assertBodiesAgree(textBody, structuredBody, 'dual-slot'), /DRIFTED/);
});

test('isPlainObject: distinguishes maps from arrays/null/scalars', () => {
  assert.equal(isPlainObject({}), true);
  assert.equal(isPlainObject([]), false);
  assert.equal(isPlainObject(null), false);
  assert.equal(isPlainObject('s'), false);
  assert.equal(isPlainObject(1), false);
});
