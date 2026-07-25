/**
 * Unit tests for .github/scripts/preflight-story-package.sh (rf2-wht9a).
 *
 * # Why these exist
 *
 * The preflight is the last gate before an IRREVERSIBLE Clojars publish of
 * `day8/re-frame2-story`. Its green side cannot be exercised end-to-end in
 * this repo: a genuinely rewritten tools/story/deps.edn resolves the five
 * in-repo coordinates from Clojars, and none of them are published yet
 * (`clojure -M:clein pom` fails at classpath resolution — which is itself
 * the "cannot deploy before its dependencies exist" gate working). So the
 * script's PARSING + VERDICT half is proved here against fixture poms,
 * exactly as the reagent-slim sibling does.
 *
 * The failure this pins is not hypothetical. Run against today's
 * unrewritten tools/story/deps.edn, `clein pom` emits a pom carrying only
 * {org.clojure/clojure, reagent/reagent, metosin/malli} — all five
 * `:local/root` coordinates (core, reagent adapter, machines, HTTP, Xray)
 * are silently skipped. That pom is UNREWRITTEN_POM below.
 *
 * # Mechanism
 *
 * Same shape as _preflight-reagent-slim-package.test.cjs: build a throwaway
 * dir that looks like a post-`clein pom` build tree, put a stub `clojure` on
 * PATH so the script's own `clojure -M:clein pom` is a no-op, and run the
 * real script against it. See that file's `buildCommand` comment for the
 * WSL double-expansion portability contract this runner also honours
 * (rf2-sefx0): the `bash -lc` string may reference only $PWD and $PATH.
 */

'use strict';

const assert = require('assert/strict');
const { spawnSync } = require('child_process');
const fs = require('fs');
const path = require('path');

const IMPL_ROOT = path.resolve(__dirname, '..');
const REPO_ROOT = path.resolve(IMPL_ROOT, '..');
const SCRIPT_REL = '.github/scripts/preflight-story-package.sh';
const SCRATCH_ROOT = path.join(REPO_ROOT, '.scratch');

const VERSION = '0.0.1.alpha';

const tests = [];
function test(name, fn) {
  tests.push({ name, fn });
}

// ── Pom fixtures ────────────────────────────────────────────────────────

function dep(groupId, artifactId, version) {
  return [
    '    <dependency>',
    `      <groupId>${groupId}</groupId>`,
    `      <artifactId>${artifactId}</artifactId>`,
    `      <version>${version}</version>`,
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

function inRepoDeps(version = VERSION) {
  return IN_REPO_NAMES.map((name) => dep('day8', name, version));
}

// What a CORRECTLY rewritten deps.edn produces: all five in-repo
// coordinates at the lockstep version, plus the three third-party deps.
const REWRITTEN_POM = pomWith([...THIRD_PARTY, ...inRepoDeps()]);

// What today's UNREWRITTEN deps.edn produces — verbatim shape captured by
// running `clojure -M:clein pom` in tools/story on main. This is the pom
// the bead exists to stop reaching Clojars.
const UNREWRITTEN_POM = pomWith(THIRD_PARTY);

// ── Fixture construction ────────────────────────────────────────────────

function writeStub(file, body) {
  fs.writeFileSync(file, body, { mode: 0o755 });
  fs.chmodSync(file, 0o755);
}

function relPosix(abs) {
  return path.relative(REPO_ROOT, abs).split(path.sep).join('/');
}

function shQuote(s) {
  return `'${String(s).replace(/'/g, `'\\''`)}'`;
}

function makeFixture({ pom = REWRITTEN_POM } = {}) {
  fs.mkdirSync(SCRATCH_ROOT, { recursive: true });
  const dir = fs.mkdtempSync(path.join(SCRATCH_ROOT, 'rf2-story-preflight-'));

  if (pom !== null) {
    const pomDir = path.join(
      dir, 'target', 'classes', 'META-INF', 'maven', 'day8', 're-frame2-story',
    );
    fs.mkdirSync(pomDir, { recursive: true });
    fs.writeFileSync(path.join(pomDir, 'pom.xml'), pom);
  }

  const binDir = path.join(dir, 'bin');
  fs.mkdirSync(binDir, { recursive: true });
  // `clojure -M:clein pom` — no-op; target/ is pre-placed above.
  writeStub(path.join(binDir, 'clojure'), '#!/usr/bin/env sh\nexit 0\n');

  return { dir, rel: relPosix(dir) };
}

// Only $PWD and $PATH — see the header note on WSL double expansion.
function buildCommand(rel, version) {
  return [
    'env',
    `PATH="$PWD/${rel}/bin:$PATH"`,
    `${shQuote(`./${SCRIPT_REL}`)} ${shQuote(version)} ${shQuote(rel)}`,
  ].join(' ');
}

function run(fixture, version = VERSION) {
  return spawnSync('bash', ['-lc', buildCommand(fixture.rel, version)], {
    cwd: REPO_ROOT,
    encoding: 'utf8',
  });
}

function cleanup() {
  fs.rmSync(SCRATCH_ROOT, { recursive: true, force: true });
}

function expectPass(fixture, what, version = VERSION) {
  const res = run(fixture, version);
  const out = `${res.stdout}\n${res.stderr}`;
  assert.equal(res.status, 0, `${what}: expected exit 0 (PASSED), got ${res.status}\n${out}`);
  assert.match(out, /verification PASSED/, `${what}: expected a PASSED verdict\n${out}`);
}

function expectFail(fixture, what, messagePattern, version = VERSION) {
  const res = run(fixture, version);
  const out = `${res.stdout}\n${res.stderr}`;
  assert.notEqual(
    res.status, 0,
    `${what}: expected a NON-ZERO exit, got ${res.status} — the gate waved a bad package through\n${out}`,
  );
  assert.doesNotMatch(out, /verification PASSED/, `${what}: must not print a PASSED verdict\n${out}`);
  if (messagePattern) {
    assert.match(out, messagePattern, `${what}: expected a diagnostic matching ${messagePattern}\n${out}`);
  }
}

// ── The correct pom must pass ───────────────────────────────────────────
//
// The over-tightening trap: a preflight that reds a CORRECT pom blocks a
// legitimate release and gets bypassed by whoever is trying to ship.

test('a correctly rewritten pom PASSES', () => {
  expectPass(makeFixture(), 'rewritten pom');
});

// ── The bug this bead fixes ─────────────────────────────────────────────

test('the UNREWRITTEN pom fails, naming every skipped in-repo coordinate', () => {
  const fixture = makeFixture({ pom: UNREWRITTEN_POM });
  expectFail(fixture, 'unrewritten pom', /MISSING the required DIRECT dependency day8\/re-frame2\b/);
  // Every one of the five must be reported, not just the first.
  const out = (() => {
    const res = run(fixture);
    return `${res.stdout}\n${res.stderr}`;
  })();
  for (const name of IN_REPO_NAMES) {
    assert.match(
      out, new RegExp(`MISSING the required DIRECT dependency day8/${name}\\.`),
      `unrewritten pom: expected day8/${name} to be reported missing\n${out}`,
    );
  }
});

test('a pom missing ONLY Xray fails — the rf2-r8trk edge at the package boundary', () => {
  // Xray became a required Story dep in #6435. A rewrite loop that was
  // never extended to the new coordinate produces exactly this pom.
  const deps = [...THIRD_PARTY, ...IN_REPO_NAMES
    .filter((n) => n !== 're-frame2-xray')
    .map((n) => dep('day8', n, VERSION))];
  expectFail(
    makeFixture({ pom: pomWith(deps) }),
    'pom missing only Xray',
    /MISSING the required DIRECT dependency day8\/re-frame2-xray/,
  );
});

// ── Lockstep ────────────────────────────────────────────────────────────

test('an in-repo dep at the WRONG version fails', () => {
  const deps = [...THIRD_PARTY, ...IN_REPO_NAMES.map(
    (n) => dep('day8', n, n === 're-frame2-http' ? '0.0.0.stale' : VERSION),
  )];
  expectFail(
    makeFixture({ pom: pomWith(deps) }),
    'stale in-repo version',
    /day8\/re-frame2-http is at version '0\.0\.0\.stale', expected the lockstep/,
  );
});

// ── Incomplete + unexpected coordinates ─────────────────────────────────

test('an empty <version> fails — an incomplete GAV is unresolvable', () => {
  const deps = [...THIRD_PARTY, ...IN_REPO_NAMES.map(
    (n) => dep('day8', n, n === 're-frame2-machines' ? '' : VERSION),
  )];
  expectFail(
    makeFixture({ pom: pomWith(deps) }),
    'empty version',
    /has a missing or empty <version>/,
  );
});

test('a leaked test-only dependency fails with a pointed hint', () => {
  // re-frame2-ui NEVER PUBLISHES (in-tree donor code) and lives under Story's
  // :test alias. Seeing it in the published pom means the alias leaked into :deps.
  const deps = [...THIRD_PARTY, ...inRepoDeps(), dep('day8', 're-frame2-ui', VERSION)];
  expectFail(
    makeFixture({ pom: pomWith(deps) }),
    'leaked test-only dep',
    /UNEXPECTED DIRECT dependency day8\/re-frame2-ui.*NEVER PUBLISHES/s,
  );
});

// ── Structural failure modes ────────────────────────────────────────────

test('an absent <dependencies> block fails rather than passing vacuously', () => {
  const pom = [
    '<?xml version="1.0" encoding="UTF-8"?>',
    '<project xmlns="http://maven.apache.org/POM/4.0.0">',
    '  <groupId>day8</groupId>',
    '  <artifactId>re-frame2-story</artifactId>',
    '</project>',
    '',
  ].join('\n');
  expectFail(makeFixture({ pom }), 'no dependencies block', /MISSING the required DIRECT dependency/);
});

test('a missing pom file fails', () => {
  expectFail(makeFixture({ pom: null }), 'missing pom', /expected pom not found/);
});

test('a malformed pom fails rather than parsing to an empty dep set', () => {
  expectFail(makeFixture({ pom: '<project><dependencies>' }), 'malformed pom', /not well-formed XML/);
});

// ── Portability contract (rf2-sefx0) ────────────────────────────────────

test('the bash -lc command references only pre-existing shell variables', () => {
  const referenced = [...buildCommand('some/rel', VERSION).matchAll(/\$([A-Za-z_][A-Za-z0-9_]*)/g)]
    .map((m) => m[1]);
  const notPreExisting = referenced.filter((n) => !['PWD', 'PATH'].includes(n));
  assert.deepEqual(
    notPreExisting, [],
    'WSL\'s bash.exe expands the -c string TWICE, so a variable this command assigns '
      + 'itself resolves to EMPTY before the assignment runs — dropping the fixture stub off '
      + `PATH. Offending: ${notPreExisting.join(', ')} (rf2-sefx0).`,
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

cleanup();

if (failed > 0) {
  console.error(`preflight-story-package tests: ${failed} of ${tests.length} failed.`);
  process.exit(1);
}

console.log(`preflight-story-package tests: ${tests.length} passed.`);
