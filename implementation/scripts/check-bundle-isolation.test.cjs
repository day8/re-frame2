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
  OPTIONAL_ARTEFACT_FACADES,
  assertExampleCoLoadIsolation,
} = require('./check-bundle-isolation.cjs');
const { assertSentinelSet } = require('./lib/sentinel-scan.cjs');
const { listPublishableRuntimes } = require('./lib/publishable-runtimes.cjs');

const REPO_ROOT = path.resolve(__dirname, '..', '..');

const thirdParty = new Set(['xyflow', 'elkjs', 'zprint', 'editscript']);

const completeness = assertPositiveControlComplete();
assert.deepStrictEqual(completeness, {
  missing: [],
  extra: [],
  malformed: [],
  sharedModules: [],
  sharedSentinels: [],
  ok: true,
});

// Every sentinel is caught on its own, both missing from the positive control
// and leaked into a production bundle.
for (const artefact of ARTEFACTS) {
  const sentinels = artefact.internalSentinels;
  assert(sentinels.length > 0, `${artefact.name}: positive control must inspect at least one sentinel`);

  const completeBlob = sentinels.map(({ sentinel }) => sentinel).join('\n');
  const present = assertSentinelSet(completeBlob, sentinels, { mustContain: true, count: true });
  assert.strictEqual(present.passed, sentinels.length, `${artefact.name}: complete emitted fixture should pass`);

  for (const removed of sentinels) {
    const withoutOne = sentinels
      .filter(({ sentinel }) => sentinel !== removed.sentinel)
      .map(({ sentinel }) => sentinel)
      .join('\n');
    const drifted = assertSentinelSet(withoutOne, sentinels, { mustContain: true, count: true });
    assert(!drifted.ok,
      `${artefact.name}: removing ${JSON.stringify(removed.sentinel)} must fail the positive control`);

    const leaked = checkArtefact(`deliberate-production-leak:${removed.sentinel}`, artefact);
    assert.strictEqual(leaked.internalFailures, 1,
      `${artefact.name}: leaking ${JSON.stringify(removed.sentinel)} must fail the negative control on exactly that sentinel`);
  }

  if (thirdParty.has(artefact.name)) {
    assert(POSITIVE_CONTROL[artefact.name].onModule,
      `${artefact.name}: third-party owner must use a real emitted module`);
  }
}

// Ownership confusion: two artefacts may not claim one emitted module, and one
// literal may not be attributed to two owners.
const moduleControls = Object.entries(POSITIVE_CONTROL).filter(([_name, pc]) => pc.onModule);
assert(moduleControls.length >= 2, 'ownership mutation needs at least two module controls');
const confusedControls = Object.fromEntries(
  Object.entries(POSITIVE_CONTROL).map(([name, pc]) => [name, { ...pc }])
);
confusedControls[moduleControls[1][0]].onModule = moduleControls[0][1].onModule;
const confused = assertPositiveControlComplete(ARTEFACTS, confusedControls);
assert(!confused.ok && confused.sharedModules.length === 1,
  'two owners sharing one emitted module must fail completeness');

const confusedArtefacts = ARTEFACTS.map((artefact) => ({
  ...artefact,
  internalSentinels: artefact.internalSentinels.map((entry) => ({ ...entry })),
}));
confusedArtefacts[1].internalSentinels[0].sentinel =
  confusedArtefacts[0].internalSentinels[0].sentinel;
const duplicated = assertPositiveControlComplete(confusedArtefacts, POSITIVE_CONTROL);
assert(!duplicated.ok && duplicated.sharedSentinels.length === 1,
  'one sentinel attributed to two owners must fail completeness');

// ----- enrolment is fail-closed ----------------------------------------------
// A newly publishable runtime mapped to no valid isolation gate turns the gate
// RED, through the real EDN-aware discovery and gate validation rather than
// injected list membership.

// A real deps.edn declaring a genuine :aliases/:clein/build alias.
const PUBLISHABLE_DEPS =
  '{:paths ["src"]\n :aliases {:clein/build {:lib day8/re-frame2-fixture}}}\n';
// Mentions :clein/build only inside a comment: not publishable.
const COMMENT_ONLY_DEPS =
  ';; NO :clein deploy aliases yet — deliberate; mentions :clein/build in prose.\n' +
  '{:paths ["src"] :deps {day8/re-frame2 {:local/root "../core"}}}\n';
// A `;` inside an EDN STRING before a genuine alias: a regex comment-strip would
// truncate the line there and let a publishable runtime escape the gate.
const SEMICOLON_IN_STRING_DEPS =
  '{:note "a ; semicolon inside a string"\n' +
  ' :aliases {:clein/build {:lib day8/re-frame2-fixture}}}\n';
// :clein/build only as a STRING VALUE — not a build alias.
const TOKEN_IN_STRING_DEPS =
  '{:note ":clein/build is a deploy alias, described here"\n' +
  ' :aliases {:test {}}}\n';
// :clein/build only inside a `#_` reader-discarded form — not a live alias.
const DISCARD_FORM_DEPS =
  '#_{:aliases {:clein/build {:lib day8/x}}}\n' +
  '{:paths ["src"] :aliases {:test {}}}\n';

function writeArtefact(root, relPath, contents) {
  const dir = path.join(root, relPath);
  fs.mkdirSync(dir, { recursive: true });
  fs.writeFileSync(path.join(dir, 'deps.edn'), contents);
}

// Minimal implementation/-shaped fixture: an excluded core + ssr-ring, a
// generic-gated artefact (schemas), a comment-only artefact that must not be
// discovered, and a dedicated-gated adapter (reagent), plus the caller's
// `extra` mutation. Gate VALIDATION resolves against the REAL scripts/ +
// package.json, so the fixture drives only DISCOVERY.
function withFixture(extra, body) {
  const root = fs.mkdtempSync(path.join(os.tmpdir(), 'bundle-iso-cov-'));
  try {
    writeArtefact(root, 'core', PUBLISHABLE_DEPS);
    writeArtefact(root, 'ssr-ring', PUBLISHABLE_DEPS);
    writeArtefact(root, 'schemas', PUBLISHABLE_DEPS);
    writeArtefact(root, 'commentonly', COMMENT_ONLY_DEPS);
    writeArtefact(root, 'adapters/reagent', PUBLISHABLE_DEPS);
    extra(root);
    body(root);
  } finally {
    fs.rmSync(root, { recursive: true, force: true });
  }
}

withFixture(() => {}, (root) => {
  const required = discoverBrowserOptionalRuntimes(root);
  assert.deepStrictEqual(required.map((r) => r.relPath).sort(), ['adapters/reagent', 'schemas'],
    'baseline fixture: excluded core/ssr-ring, comment-only artefact skipped, adapter descended into');
  assert(assertCanonicalInventoryCovered(required).ok, 'baseline fixture must be fully covered');
});

// A new flat runtime, a new adapter, and a nested non-adapter runtime are each
// discovered and, ungated, fail coverage by name.
for (const relPath of ['newpub', 'adapters/newfangled', 'plugins/widgets']) {
  withFixture((root) => writeArtefact(root, relPath, PUBLISHABLE_DEPS), (root) => {
    const cov = assertCanonicalInventoryCovered(discoverBrowserOptionalRuntimes(root));
    assert(!cov.ok && cov.missing.some((rt) => rt.relPath === relPath),
      `an ungated publishable runtime must FAIL coverage and be named: ${relPath}`);
  });
}

// COLLIDING LEAF NAMES: coverage is keyed by exact relPath, so adapters/schemas
// does not ride the flat schemas generic gate.
withFixture((root) => writeArtefact(root, 'adapters/schemas', PUBLISHABLE_DEPS), (root) => {
  const cov = assertCanonicalInventoryCovered(discoverBrowserOptionalRuntimes(root));
  assert(!cov.ok && cov.missing.some((rt) => rt.relPath === 'adapters/schemas'),
    'adapters/schemas must NOT ride the flat schemas gate — coverage fails closed');
  assert(cov.covered.some((c) => c.relPath === 'schemas' && c.via === 'generic'),
    'flat schemas still covered by the generic gate keyed on its exact relPath');
});

// DISCOVERY is EDN-STRUCTURAL.
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
});

// DEDICATED GATES ARE CAUSAL: a descriptor enrols a runtime only when its checker
// is a regular file, its command RUNS that checker as a directly-invoked,
// reachable step, and the checker OWNS the exact runtime in its COVERS_RUNTIMES.
const LOGIN = 'check-login-bundle-isolation.cjs';
for (const [gate, want, why] of [
  ['isolated by the vibes', false, 'a truthy prose string'],
  [{ checkers: ['check-does-not-exist.cjs'], command: 'test:bundle-isolation' }, false, 'a nonexistent checker'],
  [{ checkers: [LOGIN], command: 'test:no-such-script' }, false, 'a command that is not a package.json script'],
  [{ checkers: [LOGIN], command: 'test:reagent-slim:bundle-isolation' }, false, 'a real command that does not run the checker'],
  [{ checkers: [LOGIN], command: 'test:bundle-isolation' }, true, 'a real checker run by its real package command'],
]) {
  assert.strictEqual(validateDedicatedGate(gate).ok, want, why);
}

// The operand that counts is the one Node ACTUALLY executes. Any pre-script
// option fails closed, because several consume the next token: in
// `node --require scripts/<checker> other.cjs` the checker is a preload and
// other.cjs runs. The operand is compared as a whole normalised path, not by
// basename. A synthetic scripts map isolates this grammar from package.json.
const commandGate = (body, opts = {}) => validateDedicatedGate(
  { checkers: [LOGIN], command: 'gate' }, { scripts: { gate: body }, ...opts });
for (const [body, want] of [
  ['echo check-login-bundle-isolation.cjs', false],
  ['false && node scripts/check-login-bundle-isolation.cjs', false],
  ['node scripts/check-uix-reagent-free.cjs && # node scripts/check-login-bundle-isolation.cjs', false],
  ['node scripts/check-uix-reagent-free.cjs check-login-bundle-isolation.cjs', false],
  ['node scripts/xcheck-login-bundle-isolation.cjs', false],
  ['node --require scripts/check-login-bundle-isolation.cjs scripts/check-bundle-isolation.test.cjs', false],
  ['node --frobnicate scripts/check-login-bundle-isolation.cjs scripts/check-bundle-isolation.test.cjs', false],
  ['node --enable-source-maps scripts/check-login-bundle-isolation.cjs', false],
  ['node elsewhere/check-login-bundle-isolation.cjs', false],
  ['shadow-cljs release x && node scripts/check-login-bundle-isolation.cjs', true],
  ['node ./scripts/check-login-bundle-isolation.cjs', true],
  ['node scripts\\check-login-bundle-isolation.cjs', true],
  ['node scripts/check-login-bundle-isolation.cjs --verbose', true],
]) {
  assert.strictEqual(commandGate(body).ok, want, `${want ? 'must' : 'must not'} RUN the checker: ${body}`);
}

// A DIRECTORY sharing a checker's name is not a checker script, which a mere
// existsSync would allow. The reason is asserted, not just `ok`: moving
// scriptsDir also moves the operand the command must name.
{
  const fakeScripts = fs.mkdtempSync(path.join(os.tmpdir(), 'bundle-iso-scripts-'));
  try {
    fs.mkdirSync(path.join(fakeScripts, LOGIN));
    const dirShaped = commandGate('node scripts/check-login-bundle-isolation.cjs', { scriptsDir: fakeScripts });
    assert(dirShaped.reasons.some((r) => /regular file/.test(r)),
      'a directory sharing a checker name is not a checker script');
  } finally {
    fs.rmSync(fakeScripts, { recursive: true, force: true });
  }
}

// RUNTIME binding: the login checker owns adapters/reagent, not adapters/newpub.
const realStep = 'node scripts/check-login-bundle-isolation.cjs';
assert(commandGate(realStep, { relPath: 'adapters/reagent' }).ok,
  'the login checker OWNS adapters/reagent → binds');
assert(!commandGate(realStep, { relPath: 'adapters/newpub' }).ok,
  'the login checker does NOT own adapters/newpub → does not bind');

// Coverage routes dedicated descriptors through that validation: reusing the
// login checker, which really RUNS under test:bundle-isolation, for a runtime it
// never inspects fails, while the real adapters/reagent gate still binds.
withFixture((root) => writeArtefact(root, 'adapters/newpub', PUBLISHABLE_DEPS), (root) => {
  const cov = assertCanonicalInventoryCovered(discoverBrowserOptionalRuntimes(root), {
    dedicatedGates: {
      ...DEDICATED_ISOLATION_GATES,
      'adapters/newpub': { checkers: [LOGIN], command: 'test:bundle-isolation' },
    },
  });
  assert(!cov.ok && cov.missing.some((rt) => rt.relPath === 'adapters/newpub'),
    'reusing the login checker for adapters/newpub must fail — checker does not isolate newpub');
  assert(cov.covered.some((c) => c.relPath === 'adapters/reagent' && c.via === 'dedicated'),
    'the real adapters/reagent gate (whose checkers own its coverage) still binds');
});

// ----- fail-closed read faults -----------------------------------------------
// A missing deps.edn is a normal non-candidate; a nonexistent root, an
// unreadable subtree, or an unreadable deps.edn throws naming the path, rather
// than proving a shrunken inventory.

withFixture((root) => fs.mkdirSync(path.join(root, 'nodeps')), (root) => {
  assert.strictEqual(pathDeclaresBuildAlias(root, 'nodeps'), false,
    'a directory with no deps.edn is a normal non-candidate (not an error)');
});

{
  const ghostRoot = path.join(os.tmpdir(), `bundle-iso-nonexistent-${process.pid}-${Date.now()}`);
  assert.throws(() => listPublishableRuntimes(ghostRoot), /cannot read implementation root/,
    'a nonexistent implementation root must throw, not return an empty inventory');
}

// A deps.edn that is itself a DIRECTORY reproduces an unexpected read fault
// (EISDIR) on every platform, where POSIX chmod is a no-op on Windows.
withFixture(
  (root) => fs.mkdirSync(path.join(root, 'baddeps', 'deps.edn'), { recursive: true }),
  (root) => {
    assert.throws(() => pathDeclaresBuildAlias(root, 'baddeps'), /cannot read .*deps\.edn/,
      'an unreadable deps.edn (not ENOENT) must throw, not be silently skipped');
    assert.throws(() => listPublishableRuntimes(root), /cannot read .*deps\.edn/,
      'the unreadable deps.edn makes the whole enumeration fail closed');
  });

// A subtree listed as a directory but then unreadable must throw. Simulated with
// a scoped fs.readdirSync stub, since POSIX chmod does not block reads on Windows.
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

// Real-tree floor: the current implementation/ tree is fully covered, every
// dedicated gate resolves a discovered adapter, and every generic-coverage path
// passes the structural publishable test (no waiver for mere directory existence).
const realCoverage = assertCanonicalInventoryCovered();
assert(realCoverage.ok,
  `real implementation/ tree must be fully covered; missing: ${realCoverage.missing.map((rt) => `${rt.relPath} (${(rt.reasons || []).join('; ')})`).join(', ')}`);
for (const relPath of Object.keys(DEDICATED_ISOLATION_GATES)) {
  const rt = realCoverage.covered.find((c) => c.relPath === relPath);
  assert(rt && rt.via === 'dedicated' && rt.relPath.startsWith('adapters/'),
    `${relPath}: must be discovered under adapters/ and covered by its validated dedicated gate`);
}
for (const relPath of genericCoveragePaths()) {
  assert(pathDeclaresBuildAlias(path.join(REPO_ROOT, 'implementation'), relPath),
    `generic-coverage relPath '${relPath}' must be a real publishable implementation/ artefact`);
}

// ----- example ns-load co-load isolation -------------------------------------
// An example calling an OPTIONAL artefact's registration façade must `:require`
// that artefact ITSELF, rather than loading only because a sibling app in the
// consolidated `:node-test` bundle already did. Every assertion is a mutation:
// a rule only ever seen green is indistinguishable from one that reads nothing.
const CO_LOAD_FIXTURE_DIR = fs.mkdtempSync(path.join(os.tmpdir(), 'rf2-coload-'));

function coLoad(name, source) {
  const file = path.join(CO_LOAD_FIXTURE_DIR, name);
  fs.writeFileSync(file, source, 'utf8');
  return assertExampleCoLoadIsolation([file]);
}

const CO_LOAD_COMPLIANT = `(ns fixture.app
  (:require [re-frame.core :as rf]
            [re-frame.routing]
            [re-frame.resources]
            [re-frame.flows]
            [re-frame.schemas]
            ;; the reader machine below is why this require is here
            [re-frame.machines]))
(rf/reg-machine :fixture.app/reader {})
;; Present deliberately, and deliberately NOT a roster row: a defmachine
;; alongside a reg-machine must not raise a second violation of its own.
(rf/defmachine fixture-machine {})
(rf/reg-route :fixture.app/home {} "/")
(rf/reg-resource :fixture/thing {})
(rf/reg-mutation :fixture/write {})
(rf/reg-flow {:id :fixture/flow})
(rf/reg-app-schema :fixture/schema {})
`;

// The compliant fixture passes, and is seen to EXERCISE every roster row and
// every artefact — the half that makes the mutations below meaningful.
assert.deepStrictEqual(
  (({ violations, deadRows, unprovenArtefacts }) => [violations, deadRows, unprovenArtefacts])(
    coLoad('compliant.cljs', CO_LOAD_COMPLIANT)),
  [[], [], []],
  'compliant fixture must pass, every façade row finding a call site and every artefact seen REQUIRED');

// Dropping any ONE require, leaving its call site in place, is caught by NAME.
for (const row of OPTIONAL_ARTEFACT_FACADES) {
  const dropped = CO_LOAD_COMPLIANT
    .split('\n')
    .filter((line) => !new RegExp(`\\[${row.artefact.replace(/\./g, '\\.')}\\]`).test(line))
    .join('\n');
  const res = coLoad(`missing-${row.call}.cljs`, dropped);
  assert(res.violations.some((v) => v.call === row.call && v.artefact === row.artefact),
    `${row.call}: a call site with no [${row.artefact}] require must FAIL, naming the call and the artefact`);
}

// REGISTRATION vs DEFINITION, pinned both ways. `defmachine` expands to a plain
// `def` and fires no load-time hook, while `reg-machine` needs re-frame.machines'
// hooks — so a namespace may DEFINE machines requiring only re-frame.core. One
// fixture carrying both calls over one require cannot tell these apart, so each
// gets its own namespace. These read `violations`, not `ok`: `ok` also folds in
// the corpus-level non-vacuity guards, which a one-file fixture never satisfies.
assert.deepStrictEqual(coLoad('defmachine-only.cljs', `(ns fixture.machine-defs
  (:require [re-frame.core :as rf]))
(rf/defmachine door-machine
  {:initial :locked
   :states  {:locked {} :open {}}})
`).violations, [],
  'a defmachine-only namespace requiring only re-frame.core is VALID');
assert.deepStrictEqual(coLoad('defmachine-then-reg.cljs', `(ns fixture.machine-boot
  (:require [re-frame.core :as rf]))
(rf/defmachine door-machine {:initial :locked})
(rf/reg-machine :door/main door-machine)
`).violations.map((v) => [v.call, v.artefact]), [['reg-machine', 're-frame.machines']],
  'REGISTERING a machine still requires [re-frame.machines], and the violation names reg-machine ALONE');

// A façade named only in PROSE is not a call site; a REAL call followed by a
// trailing comment still is.
assert.deepStrictEqual(coLoad('prose-only.cljs', `(ns fixture.prose
  (:require [re-frame.core :as rf]))
;; This example does not use machines; \`rf/reg-machine\` and (rf/reg-route …)
;; are named here only to explain what it does NOT do.
(def doc "call (rf/reg-flow …) to register a flow")
(rf/reg-event-db :fixture/noop (fn [db _] db))
`).violations, [],
  'a façade named in a comment or a string is not a call site');
assert(coLoad('trailing-comment.cljs', `(ns fixture.trailing
  (:require [re-frame.core :as rf]))
(rf/reg-machine :fixture/m {}) ; the reader machine
`).violations.some((v) => v.call === 'reg-machine'),
  'a real call followed by a trailing comment must still be a call site');

// The two NON-VACUITY guards, each in the direction that would otherwise degrade
// the whole rule to a silent pass.
{
  const res = coLoad('no-calls.cljs', `(ns fixture.empty
  (:require [re-frame.core :as rf]
            [re-frame.machines]
            [re-frame.routing]
            [re-frame.resources]
            [re-frame.flows]
            [re-frame.schemas]))
(rf/reg-event-db :fixture/noop (fn [db _] db))
`);
  assert.deepStrictEqual(
    { ok: res.ok, deadRows: res.deadRows.length, unproven: res.unprovenArtefacts },
    { ok: false, deadRows: OPTIONAL_ARTEFACT_FACADES.length, unproven: [] },
    'a corpus in which NO façade row finds a call site must fail, not pass vacuously');
}
{
  const res = coLoad('no-requires.cljs', `(ns fixture.bare)
(fixture/reg-machine :x {})
(fixture/defmachine y {})
(fixture/reg-route :z {} "/")
(fixture/reg-resource :r {})
(fixture/reg-mutation :m {})
(fixture/reg-flow {:id :f})
(fixture/reg-app-schema :s {})
`);
  assert.deepStrictEqual(
    { ok: res.ok, unproven: res.unprovenArtefacts.length },
    { ok: false, unproven: new Set(OPTIONAL_ARTEFACT_FACADES.map((r) => r.artefact)).size },
    'a corpus in which no artefact is ever required must fail loud');
}

// The live tree satisfies the rule, and is seen to be a real corpus.
{
  const live = assertExampleCoLoadIsolation();
  assert(live.ok, `examples/ must satisfy co-load isolation: ${JSON.stringify(live.violations)}`);
  assert(live.filesScanned > 20,
    `co-load walk must reach the real examples corpus (scanned ${live.filesScanned})`);
}

fs.rmSync(CO_LOAD_FIXTURE_DIR, { recursive: true, force: true });

console.log(`PASS check-bundle-isolation self-test: ${ARTEFACTS.length} artefacts, enrolment and co-load mutations`);
