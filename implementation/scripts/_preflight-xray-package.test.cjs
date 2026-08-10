/**
 * Unit tests for .github/scripts/preflight-xray-package.sh, and for the
 * rewrite roster it grades (rf2-5dut1).
 *
 * # Why these exist
 *
 * The preflight is the last gate before an IRREVERSIBLE Clojars publish of
 * `day8/re-frame2-xray`, and it was itself untested — the one script standing
 * between a pom with holes in it and a registry with no yank. Its Story and
 * reagent-slim siblings both carry a suite; this is Xray's.
 *
 * It also closes the gap those siblings leave. The preflight only runs on an
 * `xray-v*` TAG PUSH, so the drift it exists to catch — release-xray.yml's
 * rewrite roster falling behind tools/xray/deps.edn — is invisible in ordinary
 * CI. That is exactly how the defect shipped: deps.edn grew from one in-repo
 * `:local/root` coordinate to ELEVEN while the workflow kept rewriting two, and
 * `clein pom` skips `:local/root` coordinates SILENTLY, so a jar cut from that
 * workflow would have published a pom declaring two of Xray's eleven in-repo
 * runtime dependencies. Every gate was green throughout. The roster tests
 * below derive BOTH sides — the coordinates from deps.edn, the roster from the
 * workflow — so neither can fall behind the other again without reddening a PR.
 *
 * # The three groups
 *
 *   1. ROSTER — read tools/xray/deps.edn structurally, partition its
 *      `:local/root` coordinates by whether the target artefact is publishable
 *      (carries an `:aliases -> :clein/build`), and assert release-xray.yml
 *      rewrites every publishable one and NO unpublishable one.
 *   2. THE UNPUBLISHABLE EDGES — pin the operator decisions that are
 *      deliberately open (rf2-5dut1 for Freehand, rf2-hic-023 for Hicasso), so
 *      neither can be closed by accident in either direction.
 *   3. VERDICT — the script's pom parsing and verdict, against fixture poms.
 *
 * # Mechanism for group 3
 *
 * Same shape as _preflight-story-package.test.cjs: build a throwaway dir that
 * looks like a post-`clein pom` build tree, put a stub `clojure` on PATH, and
 * run the real script against it. Xray's preflight calls `clojure` TWICE — once
 * to DERIVE its required set from the committed deps.edn, once for
 * `clein pom` — so the stub branches on the alias and, for the derivation call,
 * writes a fixture coordinate list to the `(spit "…")` target named in the
 * script's own `-e` form. The derivation itself is not stubbed away
 * unexamined: group 1 exercises it for real against the committed deps.edn,
 * through the repo's own EDN authority.
 *
 * See _preflight-reagent-slim-package.test.cjs's `buildCommand` comment for the
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
const SCRIPT_REL = '.github/scripts/preflight-xray-package.sh';
const XRAY_DIR = path.join(REPO_ROOT, 'tools', 'xray');
const WORKFLOW = path.join(REPO_ROOT, '.github', 'workflows', 'release-xray.yml');

const { makeScratchDir, cleanupScratchDirs } = require('./lib/scratch-fixtures.cjs');
const { readEdn, isMap, mapGetKeyword } = require('./lib/edn.cjs');

const VERSION = '0.0.1.alpha';

// The two coordinates that are deliberately NOT rewritten, because neither
// artefact carries a `:clein/build` and so neither has a Maven coordinate to
// rewrite TO. Publication of day8/re-frame2-freehand is EP-0036 F6 territory;
// day8/re-frame2-hicasso arrived with the Hicasso evidence tab (rf2-hic-023)
// and its release wiring is rf2-hic-008's.
const FREEHAND = 'day8/re-frame2-freehand';
const HICASSO = 'day8/re-frame2-hicasso';
const UNPUBLISHABLE = [FREEHAND, HICASSO];

const tests = [];
function test(name, fn) {
  tests.push({ name, fn });
}

// ── Group 1 + 2: the rewrite roster, derived from both sides ────────────

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
 * The workflow with its comment lines removed. A YAML `#` comment and a shell
 * `#` comment inside a `run:` block are the same token, and the header prose
 * quotes coordinates in both. Matching raw text would let a coordinate DESCRIBED
 * in a comment stand in for one that is actually rewritten — the false PASS
 * that is this gate's whole failure mode.
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

test('tools/xray declares in-repo coordinates at all — no vacuous green', () => {
  const { coords } = partitionedCoords();
  assert.ok(
    coords.length > 0,
    'zero :local/root coordinates read out of tools/xray/deps.edn. Every assertion below '
      + 'would then pass over an empty set, which is indistinguishable from a correct roster.',
  );
});

test('release-xray.yml rewrites EVERY publishable in-repo coordinate', () => {
  const { rewritable } = partitionedCoords();
  const code = workflowCode();
  const missing = rewritable.filter(({ root }) => !code.includes(`"${root}"`));
  assert.deepEqual(
    missing.map((c) => `${c.lib} (${c.root})`), [],
    'These coordinates are declared at :local/root in tools/xray/deps.edn and their target '
      + 'artefact IS publishable, but release-xray.yml does not rewrite them. `clein pom` skips '
      + ':local/root coordinates silently, so the published pom would omit them and Clojars has '
      + 'no yank (rf2-5dut1). Add each to the rewrite step in .github/workflows/release-xray.yml '
      + 'AND to TOOLS_LOCAL_ROOTS in .github/scripts/verify-version-lockstep.sh.',
  );
});

test('release-xray.yml rewrites NO unpublishable coordinate', () => {
  const { unrewritable } = partitionedCoords();
  const code = workflowCode();
  const rewritten = unrewritable.filter(({ root }) => code.includes(`"${root}"`));
  assert.deepEqual(
    rewritten.map((c) => `${c.lib} (${c.root})`), [],
    'These coordinates target an artefact with NO :aliases -> :clein/build, so there is no '
      + 'published version to pin them to — but release-xray.yml rewrites them anyway. That is '
      + 'WORSE than omitting them: the pom names a GAV that does not and cannot exist, the '
      + 'presence-based preflight passes it, and the failure lands in the consumer\'s build '
      + 'instead of our release job. Publish the artefact, vendor it, or move the edge to '
      + 'late-bind (rf2-5dut1).',
  );
});

test('exactly two coordinates are unpublishable, and a bead holds each open', () => {
  // Pinned in BOTH directions on purpose. If either becomes publishable this
  // reds and the rewrite roster gains an entry; if a THIRD unpublishable
  // coordinate appears it reds too, rather than quietly joining a known-bad
  // set. rf2-hic-023 added the second entry and this line with it — the pin is
  // a ledger of open operator decisions, not a tolerance for accumulating them.
  const { unrewritable } = partitionedCoords();
  assert.deepEqual(
    unrewritable.map((c) => c.lib), UNPUBLISHABLE,
    'The set of unpublishable in-repo coordinates Xray declares has changed. Two are open. '
      + `${FREEHAND} (rf2-5dut1): either Xray waits for Freehand's EP-0036 F6 publication, or `
      + 'the Freehand edge moves to late-bind — Xray\'s only production require on it is '
      + `day8.re-frame2-xray.mounted-views. ${HICASSO} (rf2-hic-023): the Hicasso tab reads the `
      + 'tool door at day8.re-frame2-xray.panels.hicasso-reads; the artefact carries no '
      + ':clein/build and rf2-hic-008 owns its release wiring. Update this pin with the ruling.',
  );
});

// ── Group 3: pom fixtures ───────────────────────────────────────────────

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

// Verbatim shape of the pom `clojure -M:clein pom` writes in tools/xray with
// NO rewrite applied: four third-party artefacts and nothing else, alongside
// ten `Skipping coordinate` lines on stdout. This is the pom the bead exists
// to stop reaching Clojars.
const THIRD_PARTY = [
  dep('org.clojure', 'clojure', '1.11.2'),
  dep('zprint', 'zprint', '1.3.0'),
  dep('reagent', 'reagent', '2.0.1'),
  dep('juji', 'editscript', '0.6.5'),
];

// The eleven coordinates as the script's derivation emits them:
// `group/artifact`, one per line, sorted. Kept as a literal so the verdict
// fixtures below are independent of what deps.edn happens to say today —
// group 1 is what asserts the two agree.
const DERIVED_ALL = [
  'day8/re-frame2',
  'day8/re-frame2-epoch',
  'day8/re-frame2-flows',
  FREEHAND,
  HICASSO,
  'day8/re-frame2-machines',
  'day8/re-frame2-machines-viz',
  'day8/re-frame2-resources',
  'day8/re-frame2-routing',
  'day8/re-frame2-schemas',
  'day8/reagent-slim',
];

function inRepoDeps(libs, version = VERSION) {
  return libs.map((lib) => {
    const [group, artifact] = lib.split('/');
    return dep(group, artifact, version);
  });
}

const REWRITTEN = DERIVED_ALL.filter((lib) => !UNPUBLISHABLE.includes(lib));

// What the rewrite step produces today: nine in-repo coordinates at the
// lockstep version, Freehand and Hicasso still skipped by `clein pom`.
// Verified by running the real rewrite roster + `clojure -M:clein pom`
// locally.
const REWRITTEN_POM = pomWith([...THIRD_PARTY, ...inRepoDeps(REWRITTEN)]);

// What rulings on BOTH unpublishable coordinates would have to produce for a
// release to go out.
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

function makeFixture({ pom = REWRITTEN_POM, required = DERIVED_ALL } = {}) {
  const dir = makeScratchDir(REPO_ROOT, 'rf2-xray-preflight');

  if (pom !== null) {
    const pomDir = path.join(
      dir, 'target', 'classes', 'META-INF', 'maven', 'day8', 're-frame2-xray',
    );
    fs.mkdirSync(pomDir, { recursive: true });
    fs.writeFileSync(path.join(pomDir, 'pom.xml'), pom);
  }

  const binDir = path.join(dir, 'bin');
  fs.mkdirSync(binDir, { recursive: true });
  writeStub(path.join(binDir, 'clojure'), clojureStub(required));

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

function output(res) {
  return `${res.stdout}\n${res.stderr}`;
}

function expectPass(fixture, what, version = VERSION) {
  const res = run(fixture, version);
  const out = output(res);
  assert.equal(res.status, 0, `${what}: expected exit 0 (PASSED), got ${res.status}\n${out}`);
  assert.match(out, /verification PASSED/, `${what}: expected a PASSED verdict\n${out}`);
}

function expectFail(fixture, what, messagePattern, version = VERSION) {
  const res = run(fixture, version);
  const out = output(res);
  assert.notEqual(
    res.status, 0,
    `${what}: expected a NON-ZERO exit, got ${res.status} — the gate waved a bad package through\n${out}`,
  );
  assert.doesNotMatch(out, /verification PASSED/, `${what}: must not print a PASSED verdict\n${out}`);
  if (messagePattern) {
    assert.match(out, messagePattern, `${what}: expected a diagnostic matching ${messagePattern}\n${out}`);
  }
  return out;
}

// ── The correct pom must pass ───────────────────────────────────────────
//
// The over-tightening trap: a preflight that reds a CORRECT pom blocks a
// legitimate release and gets bypassed by whoever is trying to ship.

test('a pom carrying every in-repo coordinate PASSES', () => {
  expectPass(makeFixture({ pom: COMPLETE_POM }), 'complete pom');
});

// ── The bug this bead was filed for ─────────────────────────────────────

test('the UNREWRITTEN pom fails, naming every skipped in-repo coordinate', () => {
  const fixture = makeFixture({ pom: pomWith(THIRD_PARTY) });
  const out = expectFail(fixture, 'unrewritten pom', /11 of 11 in-repo coordinate\(s\) are absent/);
  for (const lib of DERIVED_ALL) {
    assert.match(
      out, new RegExp(`MISSING the in-repo dependency ${lib.replace('/', '\\/')},`),
      `unrewritten pom: expected ${lib} to be reported missing\n${out}`,
    );
  }
});

test('the two-coordinate rewrite fails — the shipping state rf2-5dut1 found', () => {
  const two = ['day8/re-frame2', 'day8/reagent-slim'];
  const out = expectFail(
    makeFixture({ pom: pomWith([...THIRD_PARTY, ...inRepoDeps(two)]) }),
    'two-of-eleven pom',
    /9 of 11 in-repo coordinate\(s\) are absent/,
  );
  assert.match(out, /day8\/re-frame2-epoch/, `expected epoch among the eight\n${out}`);
});

// ── Today's state: nine rewritten, two refusals ─────────────────────────

test('the nine-coordinate rewrite fails on the two unpublishable coordinates', () => {
  const out = expectFail(
    makeFixture(),
    'nine-of-eleven pom',
    /2 of 11 in-repo coordinate\(s\) are absent from the pom: day8\/re-frame2-freehand, day8\/re-frame2-hicasso/,
  );
  assert.match(
    out, /NOT A MECHANICAL FIX/,
    `the Freehand refusal must carry its operator-decision hint, not the generic one\n${out}`,
  );
  for (const lib of UNPUBLISHABLE) {
    assert.match(
      out, new RegExp(`MISSING the in-repo dependency ${lib.replace('/', '\\/')},`),
      `${lib} carries no :clein/build, so the preflight must report it missing\n${out}`,
    );
  }
  assert.doesNotMatch(
    out, /MISSING the in-repo dependency day8\/re-frame2-epoch/,
    `no publishable coordinate may be reported missing\n${out}`,
  );
});

// ── Lockstep ────────────────────────────────────────────────────────────

test('an in-repo dep at the WRONG version fails', () => {
  const deps = [...THIRD_PARTY, ...DERIVED_ALL.map((lib) => {
    const [group, artifact] = lib.split('/');
    return dep(group, artifact, lib === 'day8/re-frame2-machines' ? '0.0.0.stale' : VERSION);
  })];
  expectFail(
    makeFixture({ pom: pomWith(deps) }),
    'stale in-repo version',
    /day8\/re-frame2-machines is at version '0\.0\.0\.stale', expected the lockstep/,
  );
});

// ── Incomplete coordinates ──────────────────────────────────────────────

test('an empty <version> fails — an incomplete GAV is unresolvable', () => {
  const deps = [...THIRD_PARTY, ...DERIVED_ALL.map((lib) => {
    const [group, artifact] = lib.split('/');
    return dep(group, artifact, lib === 'day8/re-frame2-flows' ? '' : VERSION);
  })];
  expectFail(
    makeFixture({ pom: pomWith(deps) }),
    'empty version',
    /has a missing or empty <version>/,
  );
});

// ── Structural failure modes ────────────────────────────────────────────

test('a derivation that finds NO coordinates is refused, not passed vacuously', () => {
  // The gate's required set is derived, so an empty derivation would make every
  // assertion pass over an empty set. The script exits 2 instead.
  const res = run(makeFixture({ pom: COMPLETE_POM, required: [] }));
  assert.equal(res.status, 2, `expected exit 2 on an empty derivation, got ${res.status}\n${output(res)}`);
  assert.match(output(res), /found ZERO :local\/root coordinates/, output(res));
});

test('an absent <dependencies> block fails rather than passing vacuously', () => {
  const pom = [
    '<?xml version="1.0" encoding="UTF-8"?>',
    '<project xmlns="http://maven.apache.org/POM/4.0.0">',
    '  <groupId>day8</groupId>',
    '  <artifactId>re-frame2-xray</artifactId>',
    '</project>',
    '',
  ].join('\n');
  expectFail(makeFixture({ pom }), 'no dependencies block', /MISSING the in-repo dependency/);
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

cleanupScratchDirs();

if (failed > 0) {
  console.error(`preflight-xray-package tests: ${failed} of ${tests.length} failed.`);
  process.exit(1);
}

console.log(`preflight-xray-package tests: ${tests.length} passed.`);
