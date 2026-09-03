// Pure, side-effect-free parsing + validation for the canonical
// `:rf.mcp/overflow` wire marker (rf2-3fc89f.20).
//
// ## Why this is a standalone helper
//
// The live gate `test/live-re-frame2-pair-overflow.cjs` used to carry the
// overflow parsing/validation inline. That file's top-level SKIP path
// (`runWithWatchdog.skip` when `$SHADOW_CLJS_NREPL_PORT` is unset) exits
// the process before any function is reachable, so Node's `--test` unit
// runner cannot exercise the parser directly. Extracting the pure logic
// here makes every branch unit-testable without booting a server — the
// live gate now imports from this module and adds only the on-the-wire
// ceremony (SDK spawn, the real over-budget eval, dual-slot reads).
//
// ## The contract this enforces
//
// The JVM wire-vocab schema pins the wrapper as
// `Overflow = [:map {:closed true} [:rf.mcp/overflow ReFrame2PairOverflowBody]]`
// (`wire-vocab/.../schemas.clj`). CLOSED and SINGLE-KEY is load-bearing:
// clients pattern-match on exactly one reserved top-level discriminator
// key, so a mixed envelope such as
// `{:rf.mcp/overflow {...} :unexpected "sibling"}` — which the pre-fix
// extraction-only parser accepted, returning the inner body regardless of
// siblings — is a contract break. The body itself stays OPEN/additive
// (`ReFrame2PairOverflowBody` is `{:closed false}`): future additive
// fields inside the marker are fine, extra keys OUTSIDE the wrapper are not.
//
// ## EDN vs structuredContent — one shape, two representations
//
// re-frame2-pair-mcp writes the SAME marker into BOTH result slots:
//   - `content[0].text` — the `pr-str` EDN string; `edn-data` with
//     `keywordAs: 'string'` parses `:rf.mcp/overflow` to the bare string
//     `'rf.mcp/overflow'` and keyword values to bare strings.
//   - `structuredContent` — the namespace-preserving `clj->js` projection
//     (`tools/wire.cljs qualify-keywords`), a JS object whose top-level
//     key is the same bare string `'rf.mcp/overflow'` and whose keyword
//     values print identically.
// Both therefore normalise to the SAME plain-object shape, so one
// validator covers both slots and the two bodies can be compared for
// semantic equality (`assertBodiesAgree`) to prove they cannot drift.

'use strict';

const { parseEDNString } = require('edn-data');

// The single reserved top-level wrapper key. Sourced cross-MCP from
// `re-frame.mcp-base.vocab/overflow-key`; on the JS side both slots
// present it as this bare string (see the module header).
const OVERFLOW_KEY = 'rf.mcp/overflow';

// `edn-data` parse opts pinned for the whole harness: map → plain JS
// object, keyword → bare string (no `:` prefix), set → array. The
// bare-string keyword form matches Node's JSON-native sensibilities and
// makes assertions read directly (`body.limit === 'reached'`).
const EDN_PARSE_OPTS = { mapAs: 'object', keywordAs: 'string', setAs: 'array' };

// Required-field table for `:rf.mcp/overflow` (re-frame2-pair-mcp shape). Each
// row pins one Malli `ReFrame2PairOverflowBody` field, the expected value
// shape, and a description for the error message. Adding / removing a
// row MUST stay in lockstep with the Malli schema in
// `wire-vocab/test/.../wire_vocab_test.clj` (`ReFrame2PairOverflowBody`); the
// cross-encoding grep gate (`js-assertOverflowBody-pins-every-re-frame2-pair-
// overflow-required-field`) reads THIS data table by name (it slurps this
// file — see `live-re-frame2-pair-overflow-js-rel`) so a drift in either
// direction trips the JVM-side test.
const REQUIRED_FIELDS = [
  ['limit',       (v) => v === 'reached',          'enum :reached'],
  ['cap-tokens',  (v) => typeof v === 'number',    'int'],
  ['token-count', (v) => typeof v === 'number',    'int'],
  ['tool',        (v) => typeof v === 'string',    'string|keyword'],
  ['hint',        (v) => typeof v === 'string',    'string|keyword'],
];

// A non-null, non-array object. EDN maps parse to plain objects; EDN
// vectors AND sets parse to arrays (`setAs: 'array'`), so this rejects
// both. `structuredContent` bodies are plain objects too.
function isPlainObject(v) {
  return v !== null && typeof v === 'object' && !Array.isArray(v);
}

// Validate the OUTER parsed value is the CLOSED single-key overflow
// wrapper — a non-array object whose OWN-KEY SET is EXACTLY
// `["rf.mcp/overflow"]`. Returns the inner body on success; throws on any
// other shape (missing key, a lookalike such as `rf.mcp/overflowed`,
// an array/scalar, or ANY sibling top-level key). This is the guard the
// pre-fix extraction-only parser lacked: it read `outer['rf.mcp/overflow']`
// without ever checking it was the sole top-level key.
function unwrapClosedOverflow(outer, ctx) {
  if (!isPlainObject(outer)) {
    throw new Error(
      ctx + ': overflow wrapper is not a map: ' + JSON.stringify(outer),
    );
  }
  const keys = Object.keys(outer);
  if (keys.length !== 1 || keys[0] !== OVERFLOW_KEY) {
    throw new Error(
      ctx + ': overflow wrapper MUST be the CLOSED single-key map ' +
        '{:' + OVERFLOW_KEY + ' ...}; got own-key set ' + JSON.stringify(keys) +
        '. The JVM contract pins `Overflow` = [:map {:closed true} ' +
        '[:rf.mcp/overflow ...]] — any sibling top-level key (or a ' +
        'lookalike wrapper key) is a mixed-envelope violation a client ' +
        'pattern-matching on the single reserved discriminator would ' +
        'misclassify.',
    );
  }
  return outer[OVERFLOW_KEY];
}

// Validate the parsed `:rf.mcp/overflow` body against the canonical
// `ReFrame2PairOverflowBody` shape. The body is OPEN (additive fields are
// permitted); only the required fields + the cross-field invariant are
// enforced. Returns the body on success; throws otherwise.
function assertOverflowBody(body, ctx) {
  if (!isPlainObject(body)) {
    throw new Error(ctx + ': overflow body is not a map: ' + JSON.stringify(body));
  }
  for (const [field, ok, desc] of REQUIRED_FIELDS) {
    if (!ok(body[field])) {
      throw new Error(
        ctx + ': :' + field + ' MUST be ' + desc +
          '; got ' + JSON.stringify(body[field]) +
          '. (Schema: ReFrame2PairOverflowBody.' + field + ' : ' + desc + ')',
      );
    }
  }
  // Cross-field invariant: a tripped cap MUST report token-count
  // strictly greater than cap-tokens. Malli's `[:map ...]` doesn't
  // model cross-field relationships; this is the only gate.
  if (body['token-count'] <= body['cap-tokens']) {
    throw new Error(
      ctx + ': :token-count MUST exceed :cap-tokens for a tripped cap; got ' +
        body['token-count'] + ' ≤ ' + body['cap-tokens'],
    );
  }
  return body;
}

// Validate an ALREADY-PARSED wrapper object (from either result slot) end
// to end: closed single-key wrapper + body shape + invariant. Returns the
// validated body.
function validateOverflowWrapper(outer, ctx) {
  const body = unwrapClosedOverflow(outer, ctx);
  return assertOverflowBody(body, ctx);
}

// Parse the `content[0].text` EDN string into its outer value. Throws with
// context on a non-string input or unparseable EDN — a real EDN reader
// (`edn-data`) handles nested-map / escape-sequence shapes robustly.
function parseOverflowText(text, ctx) {
  if (typeof text !== 'string') {
    throw new Error(ctx + ': overflow text is not a string: ' + JSON.stringify(text));
  }
  try {
    return parseEDNString(text, EDN_PARSE_OPTS);
  } catch (e) {
    throw new Error(
      ctx + ': overflow text is not parseable EDN: ' + e.message +
        '\n  First 200 chars: ' + text.slice(0, 200),
    );
  }
}

// Convenience: parse the text slot and validate it as a closed overflow
// wrapper, returning the validated body.
function validateOverflowText(text, ctx) {
  return validateOverflowWrapper(parseOverflowText(text, ctx), ctx);
}

// Recursively sort object keys so two structurally-equal bodies with
// different key insertion order (EDN parse order vs `clj->js` projection
// order) serialise identically for comparison.
function canonicalize(v) {
  if (Array.isArray(v)) return v.map(canonicalize);
  if (isPlainObject(v)) {
    const out = {};
    for (const k of Object.keys(v).sort()) out[k] = canonicalize(v[k]);
    return out;
  }
  return v;
}

// Structural equality of two marker bodies, order-insensitive.
function bodiesEqual(a, b) {
  return JSON.stringify(canonicalize(a)) === JSON.stringify(canonicalize(b));
}

// Assert the text-slot body and the structuredContent-slot body agree
// exactly. The two slots carry projections of the SAME marker; a drift
// (a field present in one but not the other, or a differing value) means
// the dual representations diverged — a client reading structuredContent
// would see a different marker than one reading the text. Throws on drift.
function assertBodiesAgree(textBody, structuredBody, ctx) {
  if (!bodiesEqual(textBody, structuredBody)) {
    throw new Error(
      ctx + ': overflow marker bodies DRIFTED between content[0].text and ' +
        'structuredContent — the two slots MUST project the same marker.\n' +
        '  content[0].text  : ' + JSON.stringify(canonicalize(textBody)) + '\n' +
        '  structuredContent: ' + JSON.stringify(canonicalize(structuredBody)),
    );
  }
}

// Exported: the surface the two importers actually consume —
// `test/overflow-marker.test.cjs` (the unit gate) and
// `test/live-re-frame2-pair-overflow.cjs` (the live gate). `OVERFLOW_KEY`,
// `REQUIRED_FIELDS`, `parseOverflowText` and `canonicalize` stay module-local:
// each is live INSIDE this file, and the JVM cross-encoding gate
// (`js-assertOverflowBody-pins-every-re-frame2-pair-overflow-required-field`)
// source-greps the `REQUIRED_FIELDS` rows rather than importing the table.
module.exports = {
  EDN_PARSE_OPTS,
  isPlainObject,
  unwrapClosedOverflow,
  assertOverflowBody,
  validateOverflowWrapper,
  validateOverflowText,
  bodiesEqual,
  assertBodiesAgree,
};
