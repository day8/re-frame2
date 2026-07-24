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
const { listPublishableRuntimes } = require('./lib/publishable-runtimes.cjs');

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
// A deps.edn that only MENTIONS :clein/build inside a comment (a pre-publication
// shape — this was implementation/ui/deps.edn's shape before rf2-vxgfnd.99.2 made
// ui publishable). Must NOT be discovered as publishable.
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
// generic-gated per-feature artefact (schemas), a comment-only (pre-publication)
// artefact that must not be discovered, and a dedicated-gated adapter (reagent) —
// all correctly accounted for — plus whatever `extra` mutation the caller
// injects. Gate VALIDATION resolves against the REAL scripts/ + package.json (the
// four dedicated gates are a property of this repo, not the temp fixture), so the
// temp fixture drives only DISCOVERY while coverage validation stays authentic.
function withFixture(extra, body) {
  const root = fs.mkdtempSync(path.join(os.tmpdir(), 'bundle-iso-cov-'));
  try {
    writeArtefact(root, 'core', PUBLISHABLE_DEPS);            // excluded (lockstep root)
    writeArtefact(root, 'ssr-ring', PUBLISHABLE_DEPS);        // excluded (JVM-only)
    writeArtefact(root, 'schemas', PUBLISHABLE_DEPS);         // generic (in ARTEFACTS)
    writeArtefact(root, 'commentonly', COMMENT_ONLY_DEPS);    // pre-publication: not discovered
    writeArtefact(root, 'adapters/reagent', PUBLISHABLE_DEPS); // dedicated gate
    extra(root);
    body(root);
  } finally {
    fs.rmSync(root, { recursive: true, force: true });
  }
}

// Baseline: core/ssr-ring excluded, the comment-only artefact NOT discovered, the
// nested adapter IS descended into, and everything discovered maps to a gate.
withFixture(() => {}, (root) => {
  const required = discoverBrowserOptionalRuntimes(root);
  assert.deepStrictEqual(required.map((r) => r.relPath).sort(), ['adapters/reagent', 'schemas'],
    'baseline fixture: excluded core/ssr-ring, comment-only artefact skipped, adapter descended into');
  const cov = assertCanonicalInventoryCovered(required);
  assert(cov.ok, 'baseline fixture must be fully covered');
  assert.strictEqual(cov.genericCount, 1, 'schemas covered by generic ARTEFACTS gate (by relPath)');
  assert.strictEqual(cov.dedicatedCount, 1, 'adapters/reagent covered by its validated dedicated gate');
});

// Mutation 1 — a hypothetical NEW flat publishable non-adapter runtime while no
// generic ARTEFACTS entry or dedicated gate exists: coverage must fail and NAME
// it. Proves a future-publishable flat runtime is not permanently defined away.
// (Real `ui` was this case until rf2-vxgfnd.99.2 made it publishable AND enrolled
// its generic gate; the real-tree floor below now asserts ui IS covered, so the
// mechanism is exercised here with a fresh fictional path instead.)
coverageMutations += 1;
withFixture((root) => writeArtefact(root, 'newpub', PUBLISHABLE_DEPS), (root) => {
  const required = discoverBrowserOptionalRuntimes(root);
  assert(required.some((rt) => rt.relPath === 'newpub'),
    'a new flat publishable runtime must be DISCOVERED (not permanently excluded)');
  const cov = assertCanonicalInventoryCovered(required);
  assert(!cov.ok, 'a flat publishable runtime with no isolation gate must FAIL coverage');
  assert(cov.missing.some((rt) => rt.relPath === 'newpub'),
    'coverage failure must NAME the ungated runtime');
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

// Mutation 5 — DEDICATED GATES ARE CAUSAL, bound to the EXACT runtime AND
// executable (rf2-klyw5 / rf2-kfn9q): a dedicated descriptor enrols a runtime
// only when its checker script EXISTS, its command RUNS that checker as a
// directly-invoked reachable step, AND a checker OWNS the exact runtime in its
// COVERS_RUNTIMES. A prose string, a nonexistent checker, an uninvoked / echoed /
// argument-only / substring / unreachable-false-and command, or an unrelated
// checker reused for a runtime it never inspects all fail closed.
coverageMutations += 1;
{
  // Unit-level: validateDedicatedGate directly (no relPath → runtime binding
  // not exercised; exercises descriptor shape + executable binding).
  assert(!validateDedicatedGate('isolated by the vibes').ok,
    'a truthy prose string is not a valid dedicated gate');
  assert(!validateDedicatedGate({ checkers: ['check-does-not-exist.cjs'], command: 'test:bundle-isolation' }).ok,
    'a nonexistent checker script fails validation');
  assert(!validateDedicatedGate({ checkers: ['check-login-bundle-isolation.cjs'], command: 'test:no-such-script' }).ok,
    'a command that is not a package.json script fails validation');
  assert(!validateDedicatedGate({ checkers: ['check-login-bundle-isolation.cjs'], command: 'test:reagent-slim:bundle-isolation' }).ok,
    'a real command that does NOT run the declared checker fails validation');
  assert(validateDedicatedGate({ checkers: ['check-login-bundle-isolation.cjs'], command: 'test:bundle-isolation' }).ok,
    'a real checker run by its real package command validates (executable binding)');

  // rf2-kfn9q — EXECUTABLE binding: a checker counts only when a package-script
  // step DIRECTLY runs it. echo / comment / argument-only / name-substring /
  // unreachable false-and mentions do NOT. A synthetic scripts map isolates the
  // grammar from the real package.json.
  const grammarScripts = {
    'gate:echo':      'echo check-login-bundle-isolation.cjs',
    'gate:false-and': 'false && node scripts/check-login-bundle-isolation.cjs',
    'gate:comment':   'node scripts/check-uix-reagent-free.cjs && # node scripts/check-login-bundle-isolation.cjs',
    'gate:arg-only':  'node scripts/check-uix-reagent-free.cjs check-login-bundle-isolation.cjs',
    'gate:substring': 'node scripts/xcheck-login-bundle-isolation.cjs',
    'gate:real':      'shadow-cljs release x && node scripts/check-login-bundle-isolation.cjs',
  };
  const grammarGate = (command) => validateDedicatedGate(
    { checkers: ['check-login-bundle-isolation.cjs'], command },
    { scripts: grammarScripts });
  assert(!grammarGate('gate:echo').ok, 'echo mention does not RUN the checker');
  assert(!grammarGate('gate:false-and').ok, 'unreachable false-and mention does not RUN the checker');
  assert(!grammarGate('gate:comment').ok, 'a commented-out mention does not RUN the checker');
  assert(!grammarGate('gate:arg-only').ok, 'checker as an argument to another script does not RUN it');
  assert(!grammarGate('gate:substring').ok, 'a longer filename containing the checker name as a substring does not RUN it');
  assert(grammarGate('gate:real').ok, 'a real `node scripts/<checker>` step RUNS the checker');

  // rf2-n36v6 — SCRIPT IDENTITY: the operand that counts is the one Node
  // ACTUALLY executes. Two families of false-green are closed here.
  //
  // (a) PRE-SCRIPT OPTIONS. Several Node options consume the following token, so
  //     "first token not starting with `-`" credits a PRELOAD as the script: in
  //     `node --require scripts/<checker> other.cjs`, the checker is preloaded
  //     (its `require.main === module` guard runs no bundle work) and other.cjs
  //     is what executes. Every pre-script option position — value-taking,
  //     bare, or unrecognised — fails CLOSED rather than being guessed at.
  const identityScripts = {
    'gate:require-preload':  'node --require scripts/check-login-bundle-isolation.cjs scripts/check-bundle-isolation.test.cjs',
    'gate:r-preload':        'node -r scripts/check-login-bundle-isolation.cjs scripts/check-bundle-isolation.test.cjs',
    'gate:import-preload':   'node --import scripts/check-login-bundle-isolation.cjs scripts/check-bundle-isolation.test.cjs',
    'gate:loader-preload':   'node --experimental-loader scripts/check-login-bundle-isolation.cjs scripts/check-bundle-isolation.test.cjs',
    'gate:env-file':         'node --env-file scripts/check-login-bundle-isolation.cjs scripts/check-bundle-isolation.test.cjs',
    'gate:unlisted-option':  'node --frobnicate scripts/check-login-bundle-isolation.cjs scripts/check-bundle-isolation.test.cjs',
    'gate:bare-flag':        'node --enable-source-maps scripts/check-login-bundle-isolation.cjs',
    'gate:wrong-dir':        'node elsewhere/check-login-bundle-isolation.cjs',
    'gate:parent-escape':    'node ../scripts/check-login-bundle-isolation.cjs',
    'gate:dot-slash':        'node ./scripts/check-login-bundle-isolation.cjs',
    'gate:win-sep':          'node scripts\\check-login-bundle-isolation.cjs',
    'gate:trailing-args':    'node scripts/check-login-bundle-isolation.cjs --verbose',
  };
  const identityGate = (command) => validateDedicatedGate(
    { checkers: ['check-login-bundle-isolation.cjs'], command },
    { scripts: identityScripts });
  for (const option of ['require', 'r', 'import', 'loader']) {
    assert(!identityGate(`gate:${option}-preload`).ok,
      `a --${option} PRELOAD operand is not the executed script and does not RUN the checker`);
  }
  assert(!identityGate('gate:env-file').ok,
    'an --env-file option operand is not the executed script and does not RUN the checker');
  assert(!identityGate('gate:unlisted-option').ok,
    'an UNLISTED pre-script option fails closed (no Node option table is guessed at)');
  assert(!identityGate('gate:bare-flag').ok,
    'a pre-script option position fails closed even when the checker would be the real script');

  // (b) PATH IDENTITY. The operand is compared whole, not by basename, so a
  //     different file sharing the checker's filename can not bind coverage.
  assert(!identityGate('gate:wrong-dir').ok,
    'a same-basename script in another directory does not RUN the declared checker');
  assert(!identityGate('gate:parent-escape').ok,
    'a path escaping the package root does not RUN the declared checker');

  // The real written forms still bind (separator- and argument-insensitive).
  assert(identityGate('gate:dot-slash').ok, '`./scripts/<checker>` RUNS the checker');
  assert(identityGate('gate:win-sep').ok, 'a Windows-separator operand RUNS the checker');
  assert(identityGate('gate:trailing-args').ok, 'trailing arguments after the script operand are fine');

  // rf2-n36v6 — a descriptor names a checker SCRIPT: a non-regular file (here a
  // DIRECTORY sharing a checker's name) must not discharge enrolment, which mere
  // existsSync path existence would have allowed.
  {
    const fakeScripts = fs.mkdtempSync(path.join(os.tmpdir(), 'bundle-iso-scripts-'));
    try {
      fs.mkdirSync(path.join(fakeScripts, 'check-login-bundle-isolation.cjs'));
      const dirShaped = validateDedicatedGate(
        { checkers: ['check-login-bundle-isolation.cjs'], command: 'gate:real' },
        { scriptsDir: fakeScripts, scripts: grammarScripts });
      assert(!dirShaped.ok, 'a directory sharing a checker name is not a checker script');
      assert(dirShaped.reasons.some((r) => /regular file/.test(r)),
        'the non-regular-file failure says so');
    } finally {
      fs.rmSync(fakeScripts, { recursive: true, force: true });
    }
  }

  // rf2-kfn9q — RUNTIME binding: with a relPath, a checker must OWN that exact
  // runtime (COVERS_RUNTIMES). The login checker owns adapters/reagent, not
  // adapters/newpub.
  const boundReagent = validateDedicatedGate(
    { checkers: ['check-login-bundle-isolation.cjs'], command: 'gate:real' },
    { scripts: grammarScripts, relPath: 'adapters/reagent' });
  assert(boundReagent.ok, 'the login checker OWNS adapters/reagent → binds');
  const unboundNewpub = validateDedicatedGate(
    { checkers: ['check-login-bundle-isolation.cjs'], command: 'gate:real' },
    { scripts: grammarScripts, relPath: 'adapters/newpub' });
  assert(!unboundNewpub.ok, 'the login checker does NOT own adapters/newpub → does not bind');
  assert(unboundNewpub.reasons.some((r) => /adapters\/newpub/.test(r) && /COVERS_RUNTIMES|OWNS/.test(r)),
    'the runtime-binding failure names the unbound runtime');

  // Integration: a discovered adapter routed through each bad descriptor fails
  // coverage; a genuinely-bound one covers it. Overrides keep the real dedicated
  // gates (so the fixture's adapters/reagent stays covered) and vary newpub.
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

    // rf2-kfn9q — reusing an UNRELATED existing checker for a new runtime fails:
    // the login checker really RUNS under test:bundle-isolation, but it does NOT
    // own adapters/newpub, so the descriptor is not causally bound to newpub.
    const unrelated = withNewpub({ checkers: ['check-login-bundle-isolation.cjs'], command: 'test:bundle-isolation' });
    assert(!unrelated.ok && unrelated.missing.some((rt) => rt.relPath === 'adapters/newpub'),
      'reusing the login checker for adapters/newpub must fail — checker does not isolate newpub');

    // The genuinely-gated fixture adapter stays covered: adapters/reagent maps to
    // its real checkers, which DO own adapters/reagent in COVERS_RUNTIMES.
    assert(unrelated.covered.some((c) => c.relPath === 'adapters/reagent' && c.via === 'dedicated'),
      'the real adapters/reagent gate (whose checkers own its coverage) still binds');
  });
}

// ----- inventory completeness + fail-closed read faults (rf2-o58c2) ----------
// Bundle isolation now CONSUMES the shared authority's listPublishableRuntimes
// (no second, narrower traversal), so a publishable runtime nested OUTSIDE
// adapters/ — which the release lockstep enrols — is discovered and fails
// closed if ungated. And an unexpected root / subtree / deps.edn read fault
// THROWS (naming the path) instead of silently shrinking the inventory, while a
// MISSING deps.edn stays a normal non-candidate.

// Mutation 6 — NESTED NON-ADAPTER runtime (implementation/<x>/<y>/deps.edn with
// a real :clein/build, x != adapters). The pre-rf2-o58c2 bundle-isolation walk
// descended only into adapters/, so this reached release inventory (via the
// authority) while escaping bundle coverage. Now both consumers enrol it by
// exact relPath, and — ungated — it fails coverage closed.
coverageMutations += 1;
withFixture((root) => writeArtefact(root, 'plugins/widgets', PUBLISHABLE_DEPS), (root) => {
  assert(listPublishableRuntimes(root).some((r) => r.relPath === 'plugins/widgets'),
    'the release-lockstep authority enrols the nested non-adapter runtime');
  const required = discoverBrowserOptionalRuntimes(root);
  assert(required.some((rt) => rt.relPath === 'plugins/widgets'),
    'bundle isolation must ALSO discover the nested non-adapter runtime (no narrower second traversal)');
  const cov = assertCanonicalInventoryCovered(required);
  assert(!cov.ok && cov.missing.some((rt) => rt.relPath === 'plugins/widgets'),
    'an ungated nested non-adapter runtime fails coverage closed and is named');
});

// Mutation 7 — FAIL-CLOSED read faults. A missing deps.edn is a normal
// non-candidate; a nonexistent root, an unreadable subtree, or an unreadable
// deps.edn throws and names the path (so the lockstep CLI exits non-zero and the
// bundle gate fails, rather than proving a shrunken inventory).
coverageMutations += 1;

// (a) Missing deps.edn: a directory with no deps.edn is NOT an error.
withFixture((root) => fs.mkdirSync(path.join(root, 'nodeps')), (root) => {
  assert.strictEqual(pathDeclaresBuildAlias(root, 'nodeps'), false,
    'a directory with no deps.edn is a normal non-candidate (not an error)');
});

// (b) Nonexistent implementation root: throws (was a silent empty result → the
// CLI exited 0 for a torn checkout).
{
  const ghostRoot = path.join(os.tmpdir(), `bundle-iso-nonexistent-${process.pid}-${Date.now()}`);
  assert.throws(() => listPublishableRuntimes(ghostRoot), /cannot read implementation root/,
    'a nonexistent implementation root must throw, not return an empty inventory');
}

// (c) Unreadable deps.edn: a deps.edn that is itself a DIRECTORY reproduces an
// unexpected read fault (EISDIR) deterministically on every platform (POSIX
// chmod is a no-op on Windows). Not an absence → throws naming the path, and
// the whole enumeration fails closed.
withFixture(
  (root) => fs.mkdirSync(path.join(root, 'baddeps', 'deps.edn'), { recursive: true }),
  (root) => {
    assert.throws(() => pathDeclaresBuildAlias(root, 'baddeps'), /cannot read .*deps\.edn/,
      'an unreadable deps.edn (not ENOENT) must throw, not be silently skipped');
    assert.throws(() => listPublishableRuntimes(root), /cannot read .*deps\.edn/,
      'the unreadable deps.edn makes the whole enumeration fail closed');
  });

// (d) Partial subtree EACCES: a subtree we listed as a directory but then can't
// read must throw. Simulated with a scoped fs.readdirSync stub (deterministic +
// cross-platform, since POSIX chmod does not block reads on Windows).
{
  const realReaddir = fs.readdirSync;
  const root = fs.mkdtempSync(path.join(os.tmpdir(), 'bundle-iso-eacces-'));
  try {
    writeArtefact(root, 'core', PUBLISHABLE_DEPS);
    fs.mkdirSync(path.join(root, 'locked'), { recursive: true });
    fs.readdirSync = (p, opts) => {
      if (typeof p === 'string' && path.basename(p) === 'locked') {
        const err = new Error('EACCES: permission denied');
        err.code = 'EACCES';
        throw err;
      }
      return realReaddir(p, opts);
    };
    assert.throws(() => listPublishableRuntimes(root), /cannot read subtree .*locked/,
      'an EACCES on a partial subtree must fail closed and name the path');
  } finally {
    fs.readdirSync = realReaddir;
    fs.rmSync(root, { recursive: true, force: true });
  }
}

// Real-tree floor: the current implementation/ tree must be fully covered and
// every publishable adapter discovered under adapters/ and resolved by its real,
// validated dedicated gate.
const realCoverage = assertCanonicalInventoryCovered();
assert(realCoverage.ok,
  `real implementation/ tree must be fully covered; missing: ${realCoverage.missing.map((rt) => `${rt.relPath} (${(rt.reasons || []).join('; ')})`).join(', ')}`);
for (const relPath of Object.keys(DEDICATED_ISOLATION_GATES)) {
  const rt = realCoverage.covered.find((c) => c.relPath === relPath);
  assert(rt && rt.via === 'dedicated' && rt.relPath.startsWith('adapters/'),
    `${relPath}: must be discovered under adapters/ and covered by its validated dedicated gate`);
}
// rf2-a32r7 — ui is NOT publishable (re-frame.ui is donor-only and is never
// published), so it is not discovered as a required browser-optional runtime and
// carries no coverage obligation here. Its ARTEFACTS entry stays: the gate's own
// loop scans every entry, so the sentinel check that keeps re-frame.ui bodies out
// of the no-feature counter bundle still runs, and its positive control still
// proves the sentinel is real. Coverage is what publication compels; scanning an
// in-tree browser-capable runtime is free and remains worth doing.
assert(!realCoverage.required.some((rt) => rt.relPath === 'ui'),
  'ui must NOT be in the required browser-optional set — it declares no :clein/build (rf2-a32r7)');

// Every generic-coverage path must be a real implementation/ runtime on disk (so
// a stale relPath can't paper over a missing runtime). Publishable ones are held
// to the stronger structural test; `ui` is the one in-tree-only entry.
const NON_PUBLISHABLE_GENERIC = new Set(['ui']);
for (const relPath of genericCoveragePaths()) {
  if (NON_PUBLISHABLE_GENERIC.has(relPath)) {
    assert(fs.existsSync(path.join(REPO_ROOT, 'implementation', relPath, 'deps.edn')),
      `generic-coverage relPath '${relPath}' must be a real implementation/ artefact directory`);
    continue;
  }
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
  '(publishable ui + new adapter + nested non-adapter fail closed; leaf-collision, ' +
  'EDN string/comment/discard rejected; dedicated gate bound to exact runtime + ' +
  'executable — prose/absent/uninvoked/echo/false-and/arg-only/substring command + ' +
  'unrelated-checker-for-new-runtime all rejected; nonexistent-root / subtree-EACCES / ' +
  'unreadable-deps.edn fail closed, missing-deps.edn is a normal non-candidate); ' +
  'lockstep consumes the shared EDN authority; ' +
  'module-ownership and sentinel-ownership confusion rejected'
);
