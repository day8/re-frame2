#!/usr/bin/env node
'use strict';

const assert = require('assert');
const fs = require('fs');
const os = require('os');
const path = require('path');
const {
  ARTEFACTS,
  POSITIVE_CONTROL,
  DEDICATED_ISOLATION_GATES,
  assertPositiveControlComplete,
  checkArtefact,
  discoverBrowserOptionalRuntimes,
  assertCanonicalInventoryCovered,
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

// ----- coverage enrollment mutations (rf2-klyw5) -----------------------------
// The sentinel + positive-control mutations above prove per-feature artefacts
// don't LEAK. This section proves the ENROLLMENT is FAIL-CLOSED: a newly
// publishable UI or adapter artefact mapped to no isolation gate turns the gate
// RED rather than being silently skipped. Executable + permanent, exercising the
// real filesystem discovery path (not injected list membership) so a regression
// in either the flat-plus-adapters traversal or the ui exclusion is caught.

let coverageMutations = 0;

// A real deps.edn declaring a genuine :clein/build alias.
const PUBLISHABLE_DEPS =
  '{:paths ["src"]\n :aliases {:clein/build {:lib day8/re-frame2-fixture}}}\n';
// A deps.edn that only MENTIONS :clein/build inside a comment — the exact shape
// of implementation/ui/deps.edn today. Must NOT be discovered as publishable.
const COMMENT_ONLY_DEPS =
  ';; NO :clein deploy aliases yet — deliberate; mentions :clein/build in prose.\n' +
  '{:paths ["src"] :deps {day8/re-frame2 {:local/root "../core"}}}\n';

function writeArtefact(root, relPath, contents) {
  const dir = path.join(root, relPath);
  fs.mkdirSync(dir, { recursive: true });
  fs.writeFileSync(path.join(dir, 'deps.edn'), contents);
}

// Minimal implementation/-shaped fixture: an excluded core + ssr-ring, a
// generic-gated per-feature artefact (schemas), a comment-only pre-publication
// ui, and a dedicated-gated adapter (reagent) — all correctly accounted for —
// plus whatever `extra` mutation the caller injects.
function withFixture(extra, body) {
  const root = fs.mkdtempSync(path.join(os.tmpdir(), 'bundle-iso-cov-'));
  try {
    writeArtefact(root, 'core', PUBLISHABLE_DEPS);            // excluded (lockstep root)
    writeArtefact(root, 'ssr-ring', PUBLISHABLE_DEPS);        // excluded (JVM-only)
    writeArtefact(root, 'schemas', PUBLISHABLE_DEPS);         // generic (in ARTEFACTS)
    writeArtefact(root, 'ui', COMMENT_ONLY_DEPS);             // pre-publication: not discovered
    writeArtefact(root, 'adapters/reagent', PUBLISHABLE_DEPS); // dedicated gate
    extra(root);
    body(root);
  } finally {
    fs.rmSync(root, { recursive: true, force: true });
  }
}

// Baseline: core/ssr-ring excluded, comment-only ui NOT discovered, the nested
// adapter IS descended into, and everything discovered maps to a gate.
withFixture(() => {}, (root) => {
  const required = discoverBrowserOptionalRuntimes(root);
  assert.deepStrictEqual(required.map((r) => r.name).sort(), ['reagent', 'schemas'],
    'baseline fixture: excluded core/ssr-ring, comment-only ui skipped, adapter descended into');
  const cov = assertCanonicalInventoryCovered(required);
  assert(cov.ok, 'baseline fixture must be fully covered');
  assert.strictEqual(cov.genericCount, 1, 'schemas covered by generic ARTEFACTS gate');
  assert.strictEqual(cov.dedicatedCount, 1, 'reagent covered by dedicated gate');
});

// Mutation 1 — hypothetical PUBLISHABLE UI (real :clein/build added to
// implementation/ui/deps.edn) while no UI isolation entry/gate exists: coverage
// must fail and NAME ui. Proves ui is no longer permanently defined away.
coverageMutations += 1;
withFixture((root) => writeArtefact(root, 'ui', PUBLISHABLE_DEPS), (root) => {
  const required = discoverBrowserOptionalRuntimes(root);
  assert(required.some((rt) => rt.name === 'ui'),
    'publishable ui must now be DISCOVERED (not permanently excluded)');
  const cov = assertCanonicalInventoryCovered(required);
  assert(!cov.ok, 'publishable ui with no isolation gate must FAIL coverage');
  assert(cov.missing.some((rt) => rt.name === 'ui' && rt.relPath === 'ui'),
    'coverage failure must NAME ui');
});

// Mutation 2 — hypothetical NEW PUBLISHABLE ADAPTER under
// implementation/adapters/ while no generic entry or dedicated gate exists:
// coverage must fail and NAME it. Proves nested adapter paths are not silently
// skipped and a new adapter fails closed.
coverageMutations += 1;
withFixture((root) => writeArtefact(root, 'adapters/newfangled', PUBLISHABLE_DEPS), (root) => {
  const required = discoverBrowserOptionalRuntimes(root);
  assert(required.some((rt) => rt.relPath === 'adapters/newfangled'),
    'new nested adapter must be DISCOVERED (not silently skipped)');
  const cov = assertCanonicalInventoryCovered(required);
  assert(!cov.ok, 'new publishable adapter with no isolation gate must FAIL coverage');
  assert(cov.missing.some((rt) => rt.name === 'newfangled' && rt.relPath === 'adapters/newfangled'),
    'coverage failure must NAME the new adapter');
});

// Real-tree floor: the current implementation/ tree must be fully covered, every
// publishable adapter discovered under adapters/ and resolved by its real
// dedicated gate, and pre-publication ui NOT required (it has no real
// :clein/build today, so it stays green naturally).
const realCoverage = assertCanonicalInventoryCovered();
assert(realCoverage.ok,
  `real implementation/ tree must be fully covered; missing: ${realCoverage.missing.map((rt) => rt.relPath).join(', ')}`);
for (const adapter of Object.keys(DEDICATED_ISOLATION_GATES)) {
  const rt = realCoverage.covered.find((c) => c.name === adapter);
  assert(rt && rt.via === 'dedicated' && rt.relPath.startsWith('adapters/'),
    `${adapter}: must be discovered under adapters/ and covered by its dedicated gate`);
}
assert(!realCoverage.required.some((rt) => rt.name === 'ui'),
  'pre-publication ui must not be in the required set (no real :clein/build yet)');

console.log(
  `PASS check-bundle-isolation self-test: ${ARTEFACTS.length} artefacts; ` +
  `${sentinelMutations} sentinel-removal mutations; ` +
  `${sentinelMutations} deliberate production leaks; ` +
  `${thirdParty.size} emitted third-party owners; ` +
  `${coverageMutations} coverage enrollment mutations (publishable ui + new adapter fail closed); ` +
  'module-ownership and sentinel-ownership confusion rejected'
);
