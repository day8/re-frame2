// Unit tests for `assertCallCoverageRatchet`, the gate that turns a tool
// advertised but never SDK-called RED. The end-to-end harnesses run its
// green path against both servers.

'use strict';

const test = require('node:test');
const assert = require('node:assert/strict');

const { assertCallCoverageRatchet } = require('./_runner.cjs');

test('the callTool coverage ratchet turns each kind of coverage hole RED and names the tool', () => {
  for (const [label, exclusions, pattern] of [
    ['an advertised tool neither called nor excluded', {},
      /NEITHER invoked through Client\.callTool\(\)[\s\S]*"forgotten"/],
    ['a blank exclusion rationale', { forgotten: '   ' },
      /MUST[\s\S]*carry a non-empty rationale[\s\S]*"forgotten"/],
    ['a stale exclusion row (not advertised)', { 'ghost-tool': 'covered elsewhere' },
      /stale rows[\s\S]*no longer advertised[\s\S]*"ghost-tool"/],
    ['a contradictory exclusion row (excluded yet also called)', { a: 'covered elsewhere' },
      /excluded yet ALSO SDK-called[\s\S]*"a"/],
  ]) {
    assert.throws(
      () => assertCallCoverageRatchet({
        advertised: ['a', 'forgotten'],
        called: new Set(['a']),
        exclusions,
      }),
      pattern,
      label,
    );
  }
});
