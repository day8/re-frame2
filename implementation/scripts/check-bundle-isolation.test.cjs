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
  pathDeclaresBuildAlias,
  discoverBrowserOptionalRuntimes,
  genericCoveragePaths,
  validateDedicatedGate,
  assertCanonicalInventoryCovered,
} = require('./check-bundle-isolation.cjs');
const { assertSentinelSet } = require('./lib/sentinel-scan.cjs');

// Repo root, for cross-checking the lockstep script + real implementation/ tree.
const REPO_ROOT = path.resolve(__dirname, '..', '..');

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

// ----- structural + causal enrollment mutations (rf2-klyw5 / rf2-zef0e) ------
// The sentinel + positive-control mutations above prove per-feature artefacts
// don't LEAK. This section proves the ENROLLMENT is FAIL-CLOSED and can no
// longer be DISCHARGED by text or names: a newly publishable runtime mapped to
// no valid isolation gate turns the gate RED. Executable + permanent, exercising
// the real EDN-aware discovery + gate-validation path (not injected list
// membership) so a regression in the flat-plus-adapters traversal, the EDN
// structural predicate, the path-keyed coverage, or the dedicated-gate
// validation is caught.

let coverageMutations = 0;

// A real deps.edn declaring a genuine :aliases/:clein/build alias.
const PUBLISHABLE_DEPS =
  '{:paths ["src"]\n :aliases {:clein/build {:lib day8/re-frame2-fixture}}}\n';
// A deps.edn that only MENTIONS :clein/build inside a comment — the exact shape
// of implementation/ui/deps.edn today. Must NOT be discovered as publishable.
const COMMENT_ONLY_DEPS =
  ';; NO :clein deploy aliases yet — deliberate; mentions :clein/build in prose.\n' +
  '{:paths ["src"] :deps {day8/re-frame2 {:local/root "../core"}}}\n';
// Genuine :aliases/:clein/build, but with a `;` inside an EDN STRING before it.
// The old stripEdnComments regex truncated the line at the first `;`, dropping
// the real alias — a publishable runtime silently escaped the gate. The
// EDN-aware authority must still discover it.
const SEMICOLON_IN_STRING_DEPS =
  '{:note "a ; semicolon inside a string"\n' +
  ' :aliases {:clein/build {:lib day8/re-frame2-fixture}}}\n';
// :clein/build appears only as a STRING VALUE — not a build alias. Must NOT be
// discovered (the old `/:clein\\/build/` search invented an alias from prose).
const TOKEN_IN_STRING_DEPS =
  '{:note ":clein/build is a deploy alias, described here"\n' +
  ' :aliases {:test {}}}\n';
// :clein/build appears only inside a `#_` reader-discarded top-level form — not
// a live alias. The live form's :aliases has no :clein/build. Must NOT be
// discovered (the reader skips the discard; a naive grep would false-match).
const DISCARD_FORM_DEPS =
  '#_{:aliases {:clein/build {:lib day8/x}}}\n' +
  '{:paths ["src"] :aliases {:test {}}}\n';

function writeArtefact(root, relPath, contents) {
  const dir = path.join(root, relPath);
  fs.mkdirSync(dir, { recursive: true });
  fs.writeFileSync(path.join(dir, 'deps.edn'), contents);
}

// Minimal implementation/-shaped fixture: an excluded core + ssr-ring, a
// generic-gated per-feature artefact (schemas), a comment-only pre-publication
// ui, and a dedicated-gated adapter (reagent) — all correctly accounted for —
// plus whatever `extra` mutation the caller injects. Gate VALIDATION resolves
// against the REAL scripts/ + package.json (the four dedicated gates are a
// property of this repo, not the temp fixture), so the temp fixture drives only
// DISCOVERY while coverage validation stays authentic.
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
  assert.deepStrictEqual(required.map((r) => r.relPath).sort(), ['adapters/reagent', 'schemas'],
    'baseline fixture: excluded core/ssr-ring, comment-only ui skipped, adapter descended into');
  const cov = assertCanonicalInventoryCovered(required);
  assert(cov.ok, 'baseline fixture must be fully covered');
  assert.strictEqual(cov.genericCount, 1, 'schemas covered by generic ARTEFACTS gate (by relPath)');
  assert.strictEqual(cov.dedicatedCount, 1, 'adapters/reagent covered by its validated dedicated gate');
});

// Mutation 1 — hypothetical PUBLISHABLE UI (real :clein/build added to
// implementation/ui/deps.edn) while no UI isolation entry/gate exists: coverage
// must fail and NAME ui. Proves ui is no longer permanently defined away.
coverageMutations += 1;
withFixture((root) => writeArtefact(root, 'ui', PUBLISHABLE_DEPS), (root) => {
  const required = discoverBrowserOptionalRuntimes(root);
  assert(required.some((rt) => rt.relPath === 'ui'),
    'publishable ui must now be DISCOVERED (not permanently excluded)');
  const cov = assertCanonicalInventoryCovered(required);
  assert(!cov.ok, 'publishable ui with no isolation gate must FAIL coverage');
  assert(cov.missing.some((rt) => rt.relPath === 'ui'),
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
  assert(cov.missing.some((rt) => rt.relPath === 'adapters/newfangled'),
    'coverage failure must NAME the new adapter');
});

// Mutation 3 — COLLIDING LEAF NAMES: a publishable implementation/adapters/schemas
// must NOT inherit the flat implementation/schemas generic gate through the
// shared leaf `schemas`. Coverage is keyed by exact relPath, so adapters/schemas
// fails closed even though the flat `schemas` generic gate is present.
coverageMutations += 1;
withFixture((root) => writeArtefact(root, 'adapters/schemas', PUBLISHABLE_DEPS), (root) => {
  const required = discoverBrowserOptionalRuntimes(root);
  assert(required.some((rt) => rt.relPath === 'schemas'),
    'flat schemas still discovered');
  assert(required.some((rt) => rt.relPath === 'adapters/schemas'),
    'nested adapters/schemas discovered as its own runtime');
  const cov = assertCanonicalInventoryCovered(required);
  assert(!cov.ok, 'adapters/schemas must NOT ride the flat schemas gate — coverage fails closed');
  assert(cov.missing.some((rt) => rt.relPath === 'adapters/schemas'),
    'coverage failure must NAME adapters/schemas');
  // The flat schemas is still covered generically (by its own relPath).
  assert(cov.covered.some((c) => c.relPath === 'schemas' && c.via === 'generic'),
    'flat schemas still covered by the generic gate keyed on its exact relPath');
});

// Mutation 4 — DISCOVERY is EDN-STRUCTURAL: a genuine alias survives a `;`
// inside an EDN string, and a token inside a string / comment / discard form is
// NOT invented as an alias.
coverageMutations += 1;
withFixture((root) => {
  writeArtefact(root, 'semicolonpub', SEMICOLON_IN_STRING_DEPS);
  writeArtefact(root, 'strmention', TOKEN_IN_STRING_DEPS);
  writeArtefact(root, 'discardform', DISCARD_FORM_DEPS);
}, (root) => {
  assert(pathDeclaresBuildAlias(root, 'semicolonpub'),
    'a genuine :aliases/:clein/build must survive a `;` inside an EDN string');
  assert(!pathDeclaresBuildAlias(root, 'strmention'),
    'a :clein/build token inside a STRING must NOT be treated as a build alias');
  assert(!pathDeclaresBuildAlias(root, 'discardform'),
    'a :clein/build inside a `#_` discard form must NOT be treated as a build alias');
  const required = discoverBrowserOptionalRuntimes(root);
  assert(required.some((rt) => rt.relPath === 'semicolonpub'),
    'the semicolon-in-string runtime is enrolled (not silently omitted)');
  assert(!required.some((rt) => rt.relPath === 'strmention' || rt.relPath === 'discardform'),
    'string / discard mentions are not enrolled');
});

// Mutation 5 — DEDICATED GATES ARE CAUSAL: a dedicated descriptor enrols a
// runtime only when its checker script EXISTS and its command is an INVOKED
// package script. A prose string, a nonexistent checker, or an uninvoked/absent
// command all fail closed. A well-formed descriptor pointing at a real, invoked
// gate passes.
coverageMutations += 1;
{
  // Unit-level: validateDedicatedGate directly.
  assert(!validateDedicatedGate('isolated by the vibes').ok,
    'a truthy prose string is not a valid dedicated gate');
  assert(!validateDedicatedGate({ checkers: ['check-does-not-exist.cjs'], command: 'test:bundle-isolation' }).ok,
    'a nonexistent checker script fails validation');
  assert(!validateDedicatedGate({ checkers: ['check-login-bundle-isolation.cjs'], command: 'test:no-such-script' }).ok,
    'a command that is not a package.json script fails validation');
  assert(!validateDedicatedGate({ checkers: ['check-login-bundle-isolation.cjs'], command: 'test:reagent-slim:bundle-isolation' }).ok,
    'a real command that does NOT invoke the declared checker fails validation');
  assert(validateDedicatedGate({ checkers: ['check-login-bundle-isolation.cjs'], command: 'test:bundle-isolation' }).ok,
    'a real checker invoked by its real package command validates');

  // Integration: a discovered adapter routed through each bad descriptor fails
  // coverage; the valid descriptor covers it. Each override keeps the real
  // dedicated gates (so the fixture's adapters/reagent stays covered) and varies
  // only adapters/newpub.
  withFixture((root) => writeArtefact(root, 'adapters/newpub', PUBLISHABLE_DEPS), (root) => {
    const required = discoverBrowserOptionalRuntimes(root);
    const withNewpub = (descriptor) => assertCanonicalInventoryCovered(required, {
      dedicatedGates: { ...DEDICATED_ISOLATION_GATES, 'adapters/newpub': descriptor },
    });

    const prose = withNewpub('isolated, trust me');
    assert(!prose.ok && prose.missing.some((rt) => rt.relPath === 'adapters/newpub'),
      'a prose dedicated entry must NOT enrol a runtime');

    const ghostChecker = withNewpub({ checkers: ['check-ghost.cjs'], command: 'test:bundle-isolation' });
    assert(!ghostChecker.ok && ghostChecker.missing.some((rt) => rt.relPath === 'adapters/newpub'),
      'a dedicated entry whose checker script does not exist must fail closed');

    const uninvoked = withNewpub({ checkers: ['check-login-bundle-isolation.cjs'], command: 'test:phantom' });
    assert(!uninvoked.ok && uninvoked.missing.some((rt) => rt.relPath === 'adapters/newpub'),
      'a dedicated entry whose command is not an invoked package script must fail closed');

    const valid = withNewpub({ checkers: ['check-login-bundle-isolation.cjs'], command: 'test:bundle-isolation' });
    assert(valid.ok, 'a real checker invoked by a real package command enrols the runtime');
    assert(valid.covered.some((c) => c.relPath === 'adapters/newpub' && c.via === 'dedicated'),
      'the validly-gated adapter is covered via its dedicated gate');
  });
}

// Real-tree floor: the current implementation/ tree must be fully covered, every
// publishable adapter discovered under adapters/ and resolved by its real,
// validated dedicated gate, and pre-publication ui NOT required (it has no real
// :clein/build today, so it stays green naturally).
const realCoverage = assertCanonicalInventoryCovered();
assert(realCoverage.ok,
  `real implementation/ tree must be fully covered; missing: ${realCoverage.missing.map((rt) => `${rt.relPath} (${(rt.reasons || []).join('; ')})`).join(', ')}`);
for (const relPath of Object.keys(DEDICATED_ISOLATION_GATES)) {
  const rt = realCoverage.covered.find((c) => c.relPath === relPath);
  assert(rt && rt.via === 'dedicated' && rt.relPath.startsWith('adapters/'),
    `${relPath}: must be discovered under adapters/ and covered by its validated dedicated gate`);
}
assert(!realCoverage.required.some((rt) => rt.relPath === 'ui'),
  'pre-publication ui must not be in the required set (no real :clein/build yet)');

// Every generic-coverage path must correspond to a real publishable per-feature
// runtime on disk (so a stale relPath can't paper over a missing runtime).
for (const relPath of genericCoveragePaths()) {
  assert(pathDeclaresBuildAlias(path.join(REPO_ROOT, 'implementation'), relPath),
    `generic-coverage relPath '${relPath}' must be a real publishable implementation/ artefact`);
}

// ----- lockstep consumes the SAME structural authority (rf2-zef0e) -----------
// The release lockstep (.github/scripts/verify-version-lockstep.sh) must derive
// its publishable-artefact inventory from the shared EDN-aware authority, NOT a
// duplicated textual grep — so bundle-isolation and lockstep prove the identical
// parsed :aliases/:clein/build fact and cannot drift.
const LOCKSTEP = fs.readFileSync(
  path.join(REPO_ROOT, '.github', 'scripts', 'verify-version-lockstep.sh'), 'utf8');
assert(/publishable-runtimes\.cjs/.test(LOCKSTEP),
  'lockstep must consume the shared publishable-runtimes.cjs authority for inventory discovery');
assert(!/grep\s+-qF\s+':clein\/build'/.test(LOCKSTEP),
  'lockstep must NOT rediscover publishability via a textual grep for the clein/build token (duplicated grep removed)');

console.log(
  `PASS check-bundle-isolation self-test: ${ARTEFACTS.length} artefacts; ` +
  `${sentinelMutations} sentinel-removal mutations; ` +
  `${sentinelMutations} deliberate production leaks; ` +
  `${thirdParty.size} emitted third-party owners; ` +
  `${coverageMutations} structural+causal enrollment mutations ` +
  '(publishable ui + new adapter fail closed; leaf-collision, EDN string/comment/discard, ' +
  'prose/absent/uninvoked dedicated gate all rejected); ' +
  'lockstep consumes the shared EDN authority; ' +
  'module-ownership and sentinel-ownership confusion rejected'
);
