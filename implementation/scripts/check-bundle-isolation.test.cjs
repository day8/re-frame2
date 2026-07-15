#!/usr/bin/env node
'use strict';

const assert = require('assert');
const {
  ARTEFACTS,
  POSITIVE_CONTROL,
  assertPositiveControlComplete,
  checkArtefact,
} = require('./check-bundle-isolation.cjs');
const { assertSentinelSet } = require('./lib/sentinel-scan.cjs');

const thirdParty = new Set(['xyflow', 'elkjs', 'zprint', 'editscript']);
let sentinelMutations = 0;

const completeness = assertPositiveControlComplete();
assert.deepStrictEqual(completeness, {
  missing: [],
  extra: [],
  malformed: [],
  sharedModules: [],
  sharedSentinels: [],
  ok: true,
});

for (const artefact of ARTEFACTS) {
  const sentinels = artefact.internalSentinels;
  assert(sentinels.length > 0, `${artefact.name}: positive control must inspect at least one sentinel`);

  const completeBlob = sentinels.map(({ sentinel }) => sentinel).join('\n');
  const present = assertSentinelSet(completeBlob, sentinels, {
    mustContain: true,
    count: true,
  });
  assert(present.ok, `${artefact.name}: complete emitted fixture should pass`);
  assert.strictEqual(present.passed, sentinels.length);

  for (const removed of sentinels) {
    sentinelMutations += 1;
    const withoutOne = sentinels
      .filter(({ sentinel }) => sentinel !== removed.sentinel)
      .map(({ sentinel }) => sentinel)
      .join('\n');
    const drifted = assertSentinelSet(withoutOne, sentinels, {
      mustContain: true,
      count: true,
    });
    assert(!drifted.ok,
      `${artefact.name}: removing ${JSON.stringify(removed.sentinel)} must fail the positive control`);

    const leaked = checkArtefact(`deliberate-production-leak:${removed.sentinel}`, artefact);
    assert(!leaked.ok,
      `${artefact.name}: leaking ${JSON.stringify(removed.sentinel)} must fail the negative control`);
    assert.strictEqual(leaked.internalFailures, 1,
      `${artefact.name}: deliberate leak should identify exactly its injected sentinel`);
  }

  if (thirdParty.has(artefact.name)) {
    assert(POSITIVE_CONTROL[artefact.name].onModule,
      `${artefact.name}: third-party owner must use a real emitted module`);
  }
}

// Ownership-confusion mutation: two artefacts may not claim one emitted module.
const moduleControls = Object.entries(POSITIVE_CONTROL).filter(([_name, pc]) => pc.onModule);
assert(moduleControls.length >= 2, 'ownership mutation needs at least two module controls');
const confusedControls = Object.fromEntries(
  Object.entries(POSITIVE_CONTROL).map(([name, pc]) => [name, { ...pc }])
);
confusedControls[moduleControls[1][0]].onModule = moduleControls[0][1].onModule;
const confused = assertPositiveControlComplete(ARTEFACTS, confusedControls);
assert(!confused.ok, 'two owners sharing one emitted module must fail completeness');
assert.strictEqual(confused.sharedModules.length, 1);

// Sentinel-ownership mutation: one literal may not be attributed to two owners.
const confusedArtefacts = ARTEFACTS.map((artefact) => ({
  ...artefact,
  internalSentinels: artefact.internalSentinels.map((entry) => ({ ...entry })),
}));
confusedArtefacts[1].internalSentinels[0].sentinel =
  confusedArtefacts[0].internalSentinels[0].sentinel;
const duplicated = assertPositiveControlComplete(confusedArtefacts, POSITIVE_CONTROL);
assert(!duplicated.ok, 'one sentinel attributed to two owners must fail completeness');
assert.strictEqual(duplicated.sharedSentinels.length, 1);

console.log(
  `PASS check-bundle-isolation self-test: ${ARTEFACTS.length} artefacts; ` +
  `${sentinelMutations} sentinel-removal mutations; ` +
  `${sentinelMutations} deliberate production leaks; ` +
  `${thirdParty.size} emitted third-party owners; ` +
  'module-ownership and sentinel-ownership confusion rejected'
);
