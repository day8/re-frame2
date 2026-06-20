// Unit test for the SDK callTool() coverage ratchet.
//
// `assertCallCoverageRatchet` is itself a conformance gate — it is the
// teeth that turn a descriptor-only advertised tool (LISTED but never
// SDK-CALLED) RED. A gate with no test is a gate that can silently lose
// its teeth, so this pins its contract directly, in-process, with no
// child / no MCP server (it is a pure data assertion). Uses Node's
// built-in `node:test` — same zero-dependency posture as the sibling
// runner tests.
//
// The end-to-end harnesses (`end-to-end-story.cjs` /
// `end-to-end-re-frame2-pair.cjs`) exercise the GREEN path live (every
// advertised tool is SDK-called, exclusion tables empty). This test pins
// the RED paths the green runs never hit:
//
//   - an advertised tool neither called nor excluded ⇒ throws.
//   - a blank / non-string exclusion rationale ⇒ throws.
//   - a stale exclusion row (not advertised) ⇒ throws.
//   - a contradictory exclusion row (excluded yet also called) ⇒ throws.
//   - the fully-covered / reviewed-excluded case ⇒ does NOT throw.

const test = require('node:test');
const assert = require('node:assert/strict');

const { assertCallCoverageRatchet } = require('./_runner.cjs');

test('GREEN: every advertised tool SDK-called ⇒ no throw', () => {
  assert.doesNotThrow(() =>
    assertCallCoverageRatchet({
      advertised: ['a', 'b', 'c'],
      called: new Set(['a', 'b', 'c']),
      exclusions: {},
    }),
  );
});

test('GREEN: a mix of called + reviewed-excluded ⇒ no throw', () => {
  assert.doesNotThrow(() =>
    assertCallCoverageRatchet({
      advertised: ['a', 'b', 'live-only'],
      called: new Set(['a', 'b']),
      exclusions: { 'live-only': 'covered by live-x.cjs under a real nREPL' },
    }),
  );
});

test('GREEN: `called` accepts a plain array too', () => {
  assert.doesNotThrow(() =>
    assertCallCoverageRatchet({
      advertised: ['a', 'b'],
      called: ['a', 'b'],
      exclusions: {},
    }),
  );
});

test('RED: an advertised tool neither called nor excluded ⇒ throws + names it', () => {
  assert.throws(
    () =>
      assertCallCoverageRatchet({
        advertised: ['a', 'b', 'forgotten'],
        called: new Set(['a', 'b']),
        exclusions: {},
      }),
    /NEITHER invoked through Client\.callTool\(\)[\s\S]*"forgotten"/,
  );
});

test('RED: a blank exclusion rationale ⇒ throws', () => {
  assert.throws(
    () =>
      assertCallCoverageRatchet({
        advertised: ['a', 'b'],
        called: new Set(['a']),
        exclusions: { b: '   ' },
      }),
    /MUST[\s\S]*carry a non-empty rationale[\s\S]*"b"/,
  );
});

test('RED: a non-string exclusion rationale ⇒ throws', () => {
  assert.throws(
    () =>
      assertCallCoverageRatchet({
        advertised: ['a', 'b'],
        called: new Set(['a']),
        exclusions: { b: true },
      }),
    /non-empty rationale/,
  );
});

test('RED: a stale exclusion row (not advertised) ⇒ throws', () => {
  assert.throws(
    () =>
      assertCallCoverageRatchet({
        advertised: ['a', 'b'],
        called: new Set(['a', 'b']),
        exclusions: { 'ghost-tool': 'rationale for a tool that no longer exists' },
      }),
    /stale rows[\s\S]*no longer advertised[\s\S]*"ghost-tool"/,
  );
});

test('RED: a contradictory exclusion row (excluded yet also called) ⇒ throws', () => {
  assert.throws(
    () =>
      assertCallCoverageRatchet({
        advertised: ['a', 'b'],
        called: new Set(['a', 'b']),
        exclusions: { b: 'claims not-covered-here but the workflow drove it' },
      }),
    /excluded yet ALSO SDK-called[\s\S]*"b"/,
  );
});
