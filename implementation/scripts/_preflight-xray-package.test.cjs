/**
 * Unit tests for .github/scripts/preflight-xray-package.sh, and for the
 * rewrite roster it grades.
 *
 * The preflight is the last gate before an IRREVERSIBLE Clojars publish of
 * `day8/re-frame2-xray`, and it runs only on an `xray-v*` TAG PUSH, so the drift
 * it exists to catch — release-xray.yml's rewrite roster falling behind
 * tools/xray/deps.edn — is invisible in ordinary CI. `clein pom` skips
 * `:local/root` coordinates SILENTLY, so a workflow rewriting fewer coordinates
 * than deps.edn declares would publish a pom missing the rest.
 *
 *   1. ROSTER — every `:local/root` coordinate in tools/xray/deps.edn targets a
 *      publishable artefact (one carrying `:aliases -> :clein/build`), and
 *      release-xray.yml rewrites every one. Both sides are derived.
 *   2. VERDICT — the script's pom parsing and verdict, against fixture poms in
 *      a throwaway post-`clein pom` build tree with a stub `clojure` on PATH.
 *      Xray's preflight calls `clojure` TWICE — once to DERIVE its required set
 *      from the committed deps.edn, once for `clein pom` — so the stub branches
 *      on the alias and, for the derivation, writes a fixture coordinate list to
 *      the `(spit "…")` target the script's own `-e` form names.
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
const SCRIPT_REL = '.github/scripts/preflight-xray-package.sh';
const XRAY_DIR = path.join(REPO_ROOT, 'tools', 'xray');
const WORKFLOW = path.join(REPO_ROOT, '.github', 'workflows', 'release-xray.yml');

const { makeScratchDir, cleanupScratchDirs } = require('./lib/scratch-fixtures.cjs');
const { readEdn, isMap, mapGetKeyword } = require('./lib/edn.cjs');

const VERSION = '0.0.1.alpha';
const FRESCO = 'day8/re-frame2-fresco';

const tests = [];
function test(name, fn) {
  tests.push({ name, fn });
}

// ── Group 1: the rewrite roster, derived from both sides ────────────────

/** Every main-`:deps` `:local/root` coordinate in the deps.edn at `file`. */
function localRootCoords(file) {
  const top = readEdn(fs.readFileSync(file, 'utf8'));
  assert.ok(isMap(top), `${file}: top-level form is not a map`);
  const deps = mapGetKeyword(top, 'deps');
  assert.ok(isMap(deps), `${file}: :deps is not a map`);
  const out = [];
  for (const [lib, coord] of deps.entries) {
    if (!isMap(coord)) continue;
    const root = mapGetKeyword(coord, 'local/root');
    if (root === undefined) continue;
    assert.ok(root && root.edn === 'string', `${file}: :local/root is not a string literal`);
    out.push({ lib: lib.name, root: root.value });
  }
  return out;
}

/**
 * True when the artefact rooted at `dir` can be pinned to an `:mvn/version` at
 * all — i.e. it carries a real `:aliases -> :clein/build`, which is what
 * `verify-version-lockstep.sh` and `publishable-runtimes.cjs` both read.
 */
function publishable(dir) {
  const file = path.join(dir, 'deps.edn');
  assert.ok(fs.existsSync(file), `no deps.edn at ${file} — a :local/root coordinate points nowhere`);
  const top = readEdn(fs.readFileSync(file, 'utf8'));
  assert.ok(isMap(top), `${file}: top-level form is not a map`);
  const aliases = mapGetKeyword(top, 'aliases');
  if (aliases === undefined) return false;
  assert.ok(isMap(aliases), `${file}: :aliases is not a map`);
  return mapGetKeyword(aliases, 'clein/build') !== undefined;
}

/**
 * The workflow with its comment lines removed, so a coordinate DESCRIBED in a
 * YAML or shell comment cannot stand in for one that is actually rewritten.
 */
function workflowCode() {
  return fs.readFileSync(WORKFLOW, 'utf8')
    .split('\n')
    .filter((line) => !/^\s*#/.test(line))
    .join('\n');
}

function partitionedCoords() {
  const coords = localRootCoords(path.join(XRAY_DIR, 'deps.edn'));
  const rewritable = [];
  const unrewritable = [];
  for (const coord of coords) {
    (publishable(path.resolve(XRAY_DIR, coord.root)) ? rewritable : unrewritable).push(coord);
  }
  return { coords, rewritable, unrewritable };
}

test('release-xray.yml rewrites EVERY publishable in-repo coordinate', () => {
  const { coords, rewritable } = partitionedCoords();
  assert.ok(coords.length > 0, 'zero :local/root coordinates read out of tools/xray/deps.edn — no vacuous green');
  const code = workflowCode();
  const missing = rewritable.filter(({ root }) => !code.includes(`"${root}"`));
  assert.deepEqual(
    missing.map((c) => `${c.lib} (${c.root})`), [],
    'These coordinates are declared at :local/root in tools/xray/deps.edn and their target '
      + 'artefact IS publishable, but release-xray.yml does not rewrite them. `clein pom` skips '
      + ':local/root coordinates silently, so the published pom would omit them and Clojars has '
      + 'no yank. Add each to the rewrite step in .github/workflows/release-xray.yml '
      + 'AND to TOOLS_LOCAL_ROOTS in .github/scripts/verify-version-lockstep.sh.',
  );
});

test('NO coordinate is unpublishable — the ledger is empty (rf2-gra70)', () => {
  // A coordinate whose target carries no `:clein/build` has no Maven coordinate
  // to rewrite TO, so each one is an open operator decision; a NEW one reds
  // here rather than quietly joining a known-bad set.
  const { unrewritable } = partitionedCoords();
  assert.deepEqual(
    unrewritable.map((c) => c.lib), [],
    'An in-repo coordinate Xray declares targets an artefact with no :aliases -> :clein/build, '
      + 'so nothing can pin it: publish that artefact, vendor it, or move the edge to '
      + 'late-bind — and until one of those, release-xray.yml must NOT rewrite it and this '
      + 'preflight must refuse the deploy. A pom naming a GAV Clojars does not have moves the '
      + 'failure from our release job to the consumer\'s build, where there is no yank.',
  );
});

// ── Group 2: pom fixtures ───────────────────────────────────────────────

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
    '  <artifactId>re-frame2-xray</artifactId>',
    `  <version>${VERSION}</version>`,
    '  <dependencies>',
    deps.join('\n'),
    '  </dependencies>',
    '</project>',
    '',
  ].join('\n');
}

// The third-party artefacts `clojure -M:clein pom` writes in tools/xray.
const THIRD_PARTY = [
  dep('org.clojure', 'clojure', '1.11.2'),
  dep('zprint', 'zprint', '1.3.0'),
  dep('reagent', 'reagent', '2.0.1'),
  dep('juji', 'editscript', '0.6.5'),
];

// The ten coordinates as the script's derivation emits them, one per line,
// sorted. A literal, so the verdict fixtures are independent of deps.edn.
const DERIVED_ALL = [
  'day8/re-frame2',
  'day8/re-frame2-epoch',
  'day8/re-frame2-flows',
  FRESCO,
  'day8/re-frame2-machines',
  'day8/re-frame2-machines-viz',
  'day8/re-frame2-resources',
  'day8/re-frame2-routing',
  'day8/re-frame2-schemas',
  'day8/reagent-slim',
];

// Every in-repo coordinate, `overrides` mapping a lib to a different version.
function inRepoDeps(libs, overrides = {}) {
  return libs.map((lib) => {
    const [group, artifact] = lib.split('/');
    return dep(group, artifact, lib in overrides ? overrides[lib] : VERSION);
  });
}

const COMPLETE_POM = pomWith([...THIRD_PARTY, ...inRepoDeps(DERIVED_ALL)]);

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

/**
 * The stub `clojure`. Two calls to serve:
 *
 *   `-M:clein pom`  → no-op; target/ is pre-placed below.
 *   the derivation  → write `required` to the `(spit "…")` target the script's
 *                     own `-e` form names, one lib per line.
 */
function clojureStub(required) {
  return [
    '#!/usr/bin/env sh',
    'case "$*" in',
    '  *:clein*) exit 0 ;;',
    'esac',
    'target=$(printf \'%s\\n\' "$*" | sed -n \'s/.*(spit "\\([^"]*\\)".*/\\1/p\' | head -n 1)',
    'if [ -z "$target" ]; then',
    '  echo "stub clojure: no (spit \\"…\\") target in argv" >&2',
    '  exit 9',
    'fi',
    ": > \"$target\"",
    ...required.map((lib) => `printf '%s\\n' ${shQuote(lib)} >> "$target"`),
    'exit 0',
    '',
  ].join('\n');
}

function makeFixture({ pom = COMPLETE_POM, required = DERIVED_ALL } = {}) {
  const dir = makeScratchDir(REPO_ROOT, 'rf2-xray-preflight');
  const pomDir = path.join(
    dir, 'target', 'classes', 'META-INF', 'maven', 'day8', 're-frame2-xray',
  );
  fs.mkdirSync(pomDir, { recursive: true });
  fs.writeFileSync(path.join(pomDir, 'pom.xml'), pom);

  const binDir = path.join(dir, 'bin');
  fs.mkdirSync(binDir, { recursive: true });
  writeStub(path.join(binDir, 'clojure'), clojureStub(required));

  return { dir, rel: relPosix(dir) };
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

function expectFail(fixture, what, messagePattern) {
  const { status, out } = run(fixture);
  assert.notEqual(status, 0, `${what}: expected a NON-ZERO exit — the gate waved a bad package through\n${out}`);
  assert.match(out, messagePattern, `${what}: expected a diagnostic matching ${messagePattern}\n${out}`);
}

// The over-tightening trap: a preflight that reds a CORRECT pom blocks a
// legitimate release and gets bypassed by whoever is trying to ship.
test('a pom carrying every in-repo coordinate PASSES', () => {
  const { status, out } = run(makeFixture());
  assert.equal(status, 0, `complete pom: expected exit 0, got ${status}\n${out}`);
  assert.match(out, /verification PASSED/, `complete pom: expected a PASSED verdict\n${out}`);
});

test('the nine-coordinate rewrite fails — Fresco is a coordinate like any other', () => {
  expectFail(
    makeFixture({ pom: pomWith([...THIRD_PARTY, ...inRepoDeps(DERIVED_ALL.filter((lib) => lib !== FRESCO))]) }),
    'nine-of-ten pom',
    /1 of 10 in-repo coordinate\(s\) are absent from the pom: day8\/re-frame2-fresco/,
  );
});

test('an in-repo dep at the WRONG version fails', () => {
  expectFail(
    makeFixture({ pom: pomWith([...THIRD_PARTY, ...inRepoDeps(DERIVED_ALL, { 'day8/re-frame2-machines': '0.0.0.stale' })]) }),
    'stale in-repo version',
    /day8\/re-frame2-machines is at version '0\.0\.0\.stale', expected the lockstep/,
  );
});

test('an empty <version> fails — an incomplete GAV is unresolvable', () => {
  expectFail(
    makeFixture({ pom: pomWith([...THIRD_PARTY, ...inRepoDeps(DERIVED_ALL, { 'day8/re-frame2-flows': '' })]) }),
    'empty version',
    /has a missing or empty <version>/,
  );
});

test('a derivation that finds NO coordinates is refused, not passed vacuously', () => {
  // An empty required set would make every assertion pass over nothing.
  const { status, out } = run(makeFixture({ required: [] }));
  assert.equal(status, 2, `expected exit 2 on an empty derivation, got ${status}\n${out}`);
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
  console.error(`preflight-xray-package tests: ${failed} of ${tests.length} failed.`);
  process.exit(1);
}

console.log(`preflight-xray-package tests: ${tests.length} passed.`);
