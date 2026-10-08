#!/usr/bin/env node
/*
 * Unit test for `.github/scripts/preflight-reagent-slim-package.sh`.
 *
 * That script is the LAST gate before `clojure -M:clein deploy` mutates
 * Clojars — an IRREVERSIBLE path (Clojars has no yank). A published pom with a
 * wrong or missing dependency set breaks on a CONSUMER's machine, so the gate's
 * own teeth need a positive control. The script checks the whole dependency
 * set, not merely the presence of a forbidden one: `clein pom` SKIPS
 * `:local/root` coordinates outright, emitting a pom with NO day8/re-frame2
 * dependency at all, and that pom must fail.
 *
 * Each fixture dir carries a `bin/` holding no-op `clojure` and `jar` stubs
 * prepended to PATH, plus a pre-placed `target/` tree holding the fixture pom
 * and a placeholder jar, so the script's jar globbing, pom location and every
 * invariant run for real. No build, no network, and NO DEPLOY. Discovered by
 * `npm run test:scripts`.
 */

'use strict';

const assert = require('assert/strict');
const { spawnSync } = require('child_process');
const fs = require('fs');
const path = require('path');

const IMPL_ROOT = path.resolve(__dirname, '..');
const REPO_ROOT = path.resolve(IMPL_ROOT, '..');

// Addressed RELATIVE to REPO_ROOT, which is `bash`'s cwd: relative POSIX paths
// are the only form every supported Bash flavour accepts unchanged.
const SCRIPT_REL = '.github/scripts/preflight-reagent-slim-package.sh';
const { makeScratchDir, cleanupScratchDirs } = require('./lib/scratch-fixtures.cjs');

const tests = [];
function test(name, fn) {
  tests.push({ name, fn });
}

// ── The genuine pom ─────────────────────────────────────────────────────
//
// The verbatim output of the real packaging path in
// implementation/adapters/reagent-slim (core `clein install`, the release
// workflow's `:local/root` → `:mvn/version` rewrite, then `clein pom`). Its
// dependency set is exactly the CLI's implicit org.clojure/clojure and the
// day8/re-frame2 core coordinate. Versions float with the runner's Clojure CLI,
// so the script asserts they are NON-EMPTY, never that they equal a literal.
const GENUINE_POM = `<?xml version="1.0" encoding="UTF-8"?>
<project xmlns="http://maven.apache.org/POM/4.0.0" xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance" xsi:schemaLocation="http://maven.apache.org/POM/4.0.0 http://maven.apache.org/xsd/maven-4.0.0.xsd">
  <modelVersion>4.0.0</modelVersion>
  <packaging>jar</packaging>
  <groupId>day8</groupId>
  <artifactId>reagent-slim</artifactId>
  <version>0.0.1.alpha</version>
  <name>reagent-slim</name>
  <dependencies>
    <dependency>
      <groupId>org.clojure</groupId>
      <artifactId>clojure</artifactId>
      <version>1.11.2</version>
    </dependency>
    <dependency>
      <groupId>day8</groupId>
      <artifactId>re-frame2</artifactId>
      <version>0.0.1.alpha</version>
    </dependency>
  </dependencies>
  <build>
    <sourceDirectory>src</sourceDirectory>
  </build>
  <repositories>
    <repository>
      <id>clojars</id>
      <url>https://repo.clojars.org/</url>
    </repository>
  </repositories>
  <scm>
    <tag>v0.0.1.alpha</tag>
    <url>https://github.com/day8/re-frame2</url>
  </scm>
  <licenses>
    <license>
      <name>MIT</name>
      <url>https://opensource.org/licenses/MIT</url>
    </license>
  </licenses>
</project>
`;

const CORE_DEP_BLOCK = `    <dependency>
      <groupId>day8</groupId>
      <artifactId>re-frame2</artifactId>
      <version>0.0.1.alpha</version>
    </dependency>
`;

// Genuine dependency set plus one extra <dependency> block.
function withExtraDependency({ groupId, artifactId, version }) {
  const block = [
    '    <dependency>',
    `      <groupId>${groupId}</groupId>`,
    `      <artifactId>${artifactId}</artifactId>`,
    `      <version>${version}</version>`,
    '    </dependency>',
    '',
  ].join('\n');
  return GENUINE_POM.replace('  </dependencies>\n', `${block}  </dependencies>\n`);
}

// What `jar tf` prints for a correctly-transformed slim jar: the canonical
// adapter ns present, no reagent_slim entry anywhere.
const GENUINE_JAR_ENTRIES = [
  'META-INF/MANIFEST.MF',
  'META-INF/maven/day8/reagent-slim/pom.xml',
  're_frame/',
  're_frame/adapter/',
  're_frame/adapter/reagent.cljs',
  'reagent2/',
  'reagent2/core.cljs',
].join('\n');

// ── Fixture construction ────────────────────────────────────────────────

function makeFixture({ pom = GENUINE_POM, jarEntries = GENUINE_JAR_ENTRIES, jars = ['reagent-slim-0.0.1.alpha.jar'] } = {}) {
  const dir = makeScratchDir(REPO_ROOT, 'rf2-slim-preflight');

  const targetDir = path.join(dir, 'target');
  fs.mkdirSync(targetDir, { recursive: true });
  for (const jarName of jars) {
    // Content is irrelevant — the stub `jar` reports the entry list.
    fs.writeFileSync(path.join(targetDir, jarName), '');
  }

  const pomDir = path.join(targetDir, 'classes', 'META-INF', 'maven', 'day8', 'reagent-slim');
  fs.mkdirSync(pomDir, { recursive: true });
  fs.writeFileSync(path.join(pomDir, 'pom.xml'), pom);

  fs.writeFileSync(path.join(dir, 'jar-entries.txt'), `${jarEntries}\n`);

  const binDir = path.join(dir, 'bin');
  fs.mkdirSync(binDir, { recursive: true });
  writeStub(path.join(binDir, 'clojure'), '#!/usr/bin/env sh\nexit 0\n');
  // FIXTURE_DIR is read HERE, inside the stub file, never in the runner's
  // `bash -lc` string — see `run`.
  writeStub(path.join(binDir, 'jar'), '#!/usr/bin/env sh\ncat "$FIXTURE_DIR/jar-entries.txt"\n');

  return { rel: path.relative(REPO_ROOT, dir).split(path.sep).join('/') };
}

// MSYS/Git Bash treats a file as executable when it carries a `#!` magic
// number OR the user exec bit; set both so PATH lookup resolves the stub on
// Windows and Linux alike.
function writeStub(file, body) {
  fs.writeFileSync(file, body, { mode: 0o755 });
  fs.chmodSync(file, 0o755);
}

function shQuote(s) {
  return `'${String(s).replace(/'/g, `'\\''`)}'`;
}

// Puts the fixture's stub bin on PATH and runs the real script. The command
// references only $PWD and $PATH, which already exist: when `bash` is WSL's
// bash.exe the `-c` string is expanded TWICE, so a variable the command
// assigned itself would read empty and drop the stubs off PATH.
function run(fixture) {
  const command = [
    'env',
    `FIXTURE_DIR="$PWD/${fixture.rel}"`,
    `PATH="$PWD/${fixture.rel}/bin:$PATH"`,
    `${shQuote(`./${SCRIPT_REL}`)} ${shQuote(fixture.rel)}`,
  ].join(' ');
  const res = spawnSync('bash', ['-lc', command], { cwd: REPO_ROOT, encoding: 'utf8' });
  return { status: res.status, out: `${res.stdout}\n${res.stderr}` };
}

function expectFail(fixture, what, messagePattern) {
  const { status, out } = run(fixture);
  assert.notEqual(status, 0, `${what}: expected a NON-ZERO exit — the gate waved a bad package through\n${out}`);
  assert.match(out, messagePattern, `${what}: expected a diagnostic matching ${messagePattern}\n${out}`);
}

// The over-tightening trap: a preflight that reds a CORRECT pom blocks
// legitimate releases and gets bypassed by whoever is trying to ship.
test('genuine pom + genuine jar → PASSED', () => {
  const { status, out } = run(makeFixture());
  assert.equal(status, 0, `genuine package: expected exit 0, got ${status}\n${out}`);
  assert.match(out, /verification PASSED/, `genuine package: expected a PASSED verdict\n${out}`);
});

// The real-world shape: what `clein pom` generates when the release rewrite did
// not take effect. An empty or absent <dependencies> parses to the same set.
test('required day8/re-frame2 dep missing (the clein :local/root skip) → FAILED', () => {
  expectFail(makeFixture({ pom: GENUINE_POM.replace(CORE_DEP_BLOCK, '') }), 'missing core dep', /re-frame2/);
});

test('day8/re-frame2 with an empty <version/> → FAILED', () => {
  const pom = GENUINE_POM.replace(
    '      <version>0.0.1.alpha</version>\n    </dependency>\n  </dependencies>',
    '      <version></version>\n    </dependency>\n  </dependencies>',
  );
  expectFail(makeFixture({ pom }), 'empty version', /version/i);
});

// Extras fall out of the script's ALLOWED-set check; stock reagent is the shape
// the slim adapter exists to avoid.
test('an unexpected extra dependency → FAILED, naming it (generic, DIRECT stock reagent)', () => {
  for (const [what, dep, pattern] of [
    ['extra dependency', { groupId: 'com.example', artifactId: 'surprise', version: '1.0.0' }, /com\.example\/surprise|unexpected/i],
    ['stock reagent dep', { groupId: 'reagent', artifactId: 'reagent', version: '1.2.0' }, /reagent\/reagent/],
  ]) {
    expectFail(makeFixture({ pom: withExtraDependency(dep) }), what, pattern);
  }
});

test('jar missing the canonical re_frame/adapter/reagent.cljs → FAILED', () => {
  const entries = GENUINE_JAR_ENTRIES.replace('re_frame/adapter/reagent.cljs', 're_frame/adapter/reagent_slim.cljs');
  expectFail(makeFixture({ jarEntries: entries }), 'missing canonical ns', /MISSING re_frame\/adapter\/reagent\.cljs/);
});

test('jar still carrying a reagent_slim entry → FAILED', () => {
  const entries = `${GENUINE_JAR_ENTRIES}\nre_frame/adapter/reagent_slim.cljs`;
  expectFail(makeFixture({ jarEntries: entries }), 'reagent_slim entry present', /reagent_slim/);
});

test('more than one jar → FAILED', () => {
  const fixture = makeFixture({ jars: ['reagent-slim-0.0.1.alpha.jar', 'reagent-slim-0.0.2.alpha.jar'] });
  expectFail(fixture, 'two jars', /more than one/);
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
  console.error(`preflight-reagent-slim-package tests: ${failed} of ${tests.length} failed.`);
  process.exit(1);
}

console.log(`preflight-reagent-slim-package tests: ${tests.length} passed.`);
