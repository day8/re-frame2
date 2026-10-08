/**
 * Unit tests for .github/scripts/preflight-story-package.sh.
 *
 * The preflight is the last gate before an IRREVERSIBLE Clojars publish of
 * `day8/re-frame2-story`. Its green side cannot be exercised end-to-end here,
 * because a genuinely rewritten tools/story/deps.edn resolves its in-repo
 * coordinates from Clojars, so the script's PARSING + VERDICT half is proved
 * against fixture poms: a throwaway post-`clein pom` build tree with a no-op
 * stub `clojure` on PATH, and the real script run against it. Run against the
 * unrewritten deps.edn, `clein pom` silently skips all five `:local/root`
 * coordinates; that is the pom this gate exists to stop.
 *
 * The `bash -lc` string references only $PWD and $PATH: WSL's bash.exe expands
 * it twice, so a variable it assigned itself would read empty.
 */

'use strict';

const assert = require('assert/strict');
const { spawnSync } = require('child_process');
const fs = require('fs');
const path = require('path');

const IMPL_ROOT = path.resolve(__dirname, '..');
const REPO_ROOT = path.resolve(IMPL_ROOT, '..');
const SCRIPT_REL = '.github/scripts/preflight-story-package.sh';
const { makeScratchDir, cleanupScratchDirs } = require('./lib/scratch-fixtures.cjs');

const VERSION = '0.0.1.alpha';

const tests = [];
function test(name, fn) {
  tests.push({ name, fn });
}

// ── Pom fixtures ────────────────────────────────────────────────────────

// `exclusions` is a list of [groupId, artifactId] pairs, emitted verbatim as
// `clein pom` writes a deps.edn `:exclusions` vector.
function dep(groupId, artifactId, version, exclusions = []) {
  return [
    '    <dependency>',
    `      <groupId>${groupId}</groupId>`,
    `      <artifactId>${artifactId}</artifactId>`,
    `      <version>${version}</version>`,
    ...(exclusions.length === 0 ? [] : [
      '      <exclusions>',
      ...exclusions.flatMap(([g, a]) => [
        '        <exclusion>',
        `          <groupId>${g}</groupId>`,
        `          <artifactId>${a}</artifactId>`,
        '        </exclusion>',
      ]),
      '      </exclusions>',
    ]),
    '    </dependency>',
  ].join('\n');
}

function pomWith(deps) {
  return [
    '<?xml version="1.0" encoding="UTF-8"?>',
    '<project xmlns="http://maven.apache.org/POM/4.0.0">',
    '  <modelVersion>4.0.0</modelVersion>',
    '  <groupId>day8</groupId>',
    '  <artifactId>re-frame2-story</artifactId>',
    `  <version>${VERSION}</version>`,
    '  <dependencies>',
    deps.join('\n'),
    '  </dependencies>',
    '</project>',
    '',
  ].join('\n');
}

const THIRD_PARTY = [
  dep('org.clojure', 'clojure', '1.11.2'),
  dep('reagent', 'reagent', '2.0.1'),
  dep('metosin', 'malli', '0.20.1'),
];

const IN_REPO_NAMES = [
  're-frame2',
  're-frame2-reagent',
  're-frame2-machines',
  're-frame2-http',
  're-frame2-xray',
];

// Story excludes reagent-slim from its Xray edge: without it a published Story
// consumer resolves TWO providers of re-frame.adapter.reagent, so the CORRECT
// fixture carries it.
const XRAY_EXCLUSIONS = [['day8', 'reagent-slim']];

// By default the Xray coordinate carries its exclusion and every other in-repo
// coordinate carries none; `{ exclusions }` overrides.
function inRepoDep(name, version = VERSION, { exclusions } = {}) {
  const excl = exclusions !== undefined
    ? exclusions
    : (name === 're-frame2-xray' ? XRAY_EXCLUSIONS : []);
  return dep('day8', name, version, excl);
}

// What a CORRECTLY rewritten deps.edn produces.
const REWRITTEN_POM = pomWith([...THIRD_PARTY, ...IN_REPO_NAMES.map((n) => inRepoDep(n))]);

// ── Fixture construction ────────────────────────────────────────────────

function shQuote(s) {
  return `'${String(s).replace(/'/g, `'\\''`)}'`;
}

function makeFixture({ pom = REWRITTEN_POM } = {}) {
  const dir = makeScratchDir(REPO_ROOT, 'rf2-story-preflight');
  const pomDir = path.join(
    dir, 'target', 'classes', 'META-INF', 'maven', 'day8', 're-frame2-story',
  );
  fs.mkdirSync(pomDir, { recursive: true });
  fs.writeFileSync(path.join(pomDir, 'pom.xml'), pom);

  const binDir = path.join(dir, 'bin');
  fs.mkdirSync(binDir, { recursive: true });
  // `clojure -M:clein pom` — no-op; target/ is pre-placed above.
  const stub = path.join(binDir, 'clojure');
  fs.writeFileSync(stub, '#!/usr/bin/env sh\nexit 0\n', { mode: 0o755 });
  fs.chmodSync(stub, 0o755);

  return { rel: path.relative(REPO_ROOT, dir).split(path.sep).join('/') };
}

function run(fixture) {
  const command = [
    'env',
    `PATH="$PWD/${fixture.rel}/bin:$PATH"`,
    `${shQuote(`./${SCRIPT_REL}`)} ${shQuote(VERSION)} ${shQuote(fixture.rel)}`,
  ].join(' ');
  const res = spawnSync('bash', ['-lc', command], { cwd: REPO_ROOT, encoding: 'utf8' });
  return { status: res.status, out: `${res.stdout}\n${res.stderr}` };
}

function expectFail(deps, what, messagePattern) {
  const { status, out } = run(makeFixture({ pom: pomWith(deps) }));
  assert.notEqual(status, 0, `${what}: expected a NON-ZERO exit — the gate waved a bad package through\n${out}`);
  assert.match(out, messagePattern, `${what}: expected a diagnostic matching ${messagePattern}\n${out}`);
}

// The over-tightening trap: a preflight that reds a CORRECT pom blocks a
// legitimate release and gets bypassed by whoever is trying to ship.
test('a correctly rewritten pom PASSES', () => {
  const { status, out } = run(makeFixture());
  assert.equal(status, 0, `rewritten pom: expected exit 0, got ${status}\n${out}`);
  assert.match(out, /verification PASSED/, `rewritten pom: expected a PASSED verdict\n${out}`);
});

test('a pom missing ONLY Xray fails — the rf2-r8trk edge at the package boundary', () => {
  // A rewrite loop that does not cover every in-repo coordinate produces this.
  expectFail(
    [...THIRD_PARTY, ...IN_REPO_NAMES.filter((n) => n !== 're-frame2-xray').map((n) => inRepoDep(n))],
    'pom missing only Xray',
    /MISSING the required DIRECT dependency day8\/re-frame2-xray/,
  );
});

test('an in-repo dep at the WRONG version fails', () => {
  expectFail(
    [...THIRD_PARTY, ...IN_REPO_NAMES.map((n) => inRepoDep(n, n === 're-frame2-http' ? '0.0.0.stale' : VERSION))],
    'stale in-repo version',
    /day8\/re-frame2-http is at version '0\.0\.0\.stale', expected the lockstep/,
  );
});

// Deleting the exclusion looks like tidy-up, and the failure it prevents is
// silent at build time: the consumer just gets the wrong adapter. Maven scopes
// <exclusions> to the dependency carrying them, so one parked on another edge
// excludes nothing that matters.
test('the exclusion must sit on the XRAY edge, not merely somewhere in the pom', () => {
  expectFail(
    [...THIRD_PARTY, ...IN_REPO_NAMES.map((n) => {
      if (n === 're-frame2-xray') return inRepoDep(n, VERSION, { exclusions: [] });
      if (n === 're-frame2-machines') return inRepoDep(n, VERSION, { exclusions: XRAY_EXCLUSIONS });
      return inRepoDep(n);
    })],
    'exclusion on the wrong edge',
    /day8\/re-frame2-xray does not EXCLUDE day8\/reagent-slim/,
  );
});

test('an empty <version> fails — an incomplete GAV is unresolvable', () => {
  expectFail(
    [...THIRD_PARTY, ...IN_REPO_NAMES.map((n) => inRepoDep(n, n === 're-frame2-machines' ? '' : VERSION))],
    'empty version',
    /has a missing or empty <version>/,
  );
});

test('a leaked test-only dependency fails with a pointed hint', () => {
  // re-frame2-epoch is test-only for Story, under its :test alias; in the
  // published pom it means the alias leaked into :deps.
  expectFail(
    [...THIRD_PARTY, ...IN_REPO_NAMES.map((n) => inRepoDep(n)), dep('day8', 're-frame2-epoch', VERSION)],
    'leaked test-only dep',
    /UNEXPECTED DIRECT dependency day8\/re-frame2-epoch.*test-only for Story/s,
  );
});

let failed = 0;
for (const { name, fn } of tests) {
  try {
    fn();
    console.log(`  ok   ${name}`);
  } catch (err) {
    failed += 1;
    console.error(`FAIL ${name}`);
    console.error(err && err.stack ? err.stack : err);
  }
}

cleanupScratchDirs();

if (failed > 0) {
  console.error(`preflight-story-package tests: ${failed} of ${tests.length} failed.`);
  process.exit(1);
}

console.log(`preflight-story-package tests: ${tests.length} passed.`);
