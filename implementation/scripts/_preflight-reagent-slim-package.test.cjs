#!/usr/bin/env node
/*
 * Unit test for `.github/scripts/preflight-reagent-slim-package.sh` (rf2-do3m2).
 *
 * That script is the LAST gate before `clojure -M:clein deploy` mutates
 * Clojars — an IRREVERSIBLE path (Clojars has no yank; recovery is
 * bump-and-supersede only). A published pom with a wrong or missing
 * dependency set breaks on a CONSUMER's machine, not ours, and cannot be
 * withdrawn. So the gate's own teeth need a positive control.
 *
 * THE DEFECT THIS TEST PINS (rf2-do3m2): the pom invariants used to fire
 * only on the PRESENCE of a forbidden dependency (two `grep -q
 * "<artifactId>…"` absence checks). An empty `<dependencies/>`, an absent
 * `<dependencies>` block, or a dependency carrying an empty `<version/>`
 * therefore all printed PASSED.
 *
 * That is not a hypothetical shape. `clein pom` SKIPS `:local/root`
 * coordinates outright ("Skipping coordinate: {:local/root …}"), emitting a
 * pom with NO day8/re-frame2 dependency at all. The release workflow's
 * rewrite step (`:local/root` → `:mvn/version`) is the only thing standing
 * between that and a published artefact that resolves to nothing — and
 * before this change the preflight would have waved it straight through.
 * The `missing-required-core-dep` fixture below is that exact pom.
 *
 * Pattern mirrors `_transform-reagent-slim-ns.test.cjs` (the sibling
 * release-script test): spawn the real shell script via `bash`, assert on
 * exit code + stdout/stderr. Wired into `test:script-policy`.
 *
 * HOW THE FIXTURES AVOID A REAL BUILD: the script shells out to `clojure
 * -M:clein jar` / `clojure -M:clein pom` and to `jar tf`. Each fixture dir
 * carries a `bin/` holding no-op `clojure` and `jar` stubs which the test
 * prepends to PATH, plus a pre-placed `target/` tree holding the fixture
 * pom and a placeholder jar. The script's own logic — jar globbing, pom
 * location, and every invariant — then runs for real against fixture
 * inputs. No build, no network, and emphatically NO DEPLOY.
 */

'use strict';

const assert = require('assert/strict');
const { spawnSync } = require('child_process');
const fs = require('fs');
const path = require('path');

const IMPL_ROOT = path.resolve(__dirname, '..');
const REPO_ROOT = path.resolve(IMPL_ROOT, '..');

// Addressed RELATIVE to REPO_ROOT, which is handed to `bash` as its `cwd`.
// Relative POSIX paths are the only form every supported Bash flavour
// accepts unchanged (Git Bash mounts C: at /c, WSL at /mnt/c, Linux has no
// drive letters) — see the long rationale in the sibling
// `_transform-reagent-slim-ns.test.cjs` `runRel()` comment (rf2-6m7pn4).
const SCRIPT_REL = '.github/scripts/preflight-reagent-slim-package.sh';
// Fixtures kept INSIDE the repo (not os.tmpdir()) so a repo-relative path
// reaches them; `.scratch/` is gitignored. Lanes are process-scoped and the
// shared root is never removed, so a concurrent suite cannot delete this
// one's fixtures mid-run (rf2-2i1ay).
const { makeScratchDir, cleanupScratchDirs } = require('./lib/scratch-fixtures.cjs');

const tests = [];
function test(name, fn) {
  tests.push({ name, fn });
}

// ── The genuine pom ─────────────────────────────────────────────────────
//
// PROVENANCE — this is NOT hand-written. It is the verbatim output of the
// real packaging path, captured by running, in
// implementation/adapters/reagent-slim:
//
//   1. (in implementation/core)  clojure -M:clein install
//   2. rewrite deps.edn's day8/re-frame2 coordinate
//      :local/root "../../core" → :mvn/version "0.0.1.alpha"
//      (exactly what release.yml's "Rewrite … :local/root → :mvn/version"
//      step does on the runner, immediately before the preflight)
//   3. clojure -M:clein pom
//
// The dependency set is therefore authoritative, and it is exactly two
// members: the implicit org.clojure/clojure root dep that the Clojure CLI
// contributes, and the day8/re-frame2 core coordinate the artefact
// declares. Versions float with the runner's Clojure CLI (which
// install-clojure-cli.sh does not pin), so the script asserts they are
// NON-EMPTY, never that they equal a literal.
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

// Replace the whole <dependencies>…</dependencies> element with `inner`
// (or drop it entirely when `inner` is null).
function withDependencies(inner) {
  const re = /  <dependencies>[\s\S]*?<\/dependencies>\n/;
  assert.match(GENUINE_POM, re, 'genuine pom must carry a <dependencies> element');
  if (inner === null) return GENUINE_POM.replace(re, '');
  return GENUINE_POM.replace(re, `  <dependencies>\n${inner}  </dependencies>\n`);
}

// Genuine dependency set plus one extra <dependency> block.
function withExtraDependency(block) {
  return GENUINE_POM.replace('  </dependencies>\n', `${block}  </dependencies>\n`);
}

function depBlock({ groupId = null, artifactId = null, version = null } = {}) {
  const lines = ['    <dependency>'];
  if (groupId !== null) lines.push(`      <groupId>${groupId}</groupId>`);
  if (artifactId !== null) lines.push(`      <artifactId>${artifactId}</artifactId>`);
  if (version !== null) lines.push(`      <version>${version}</version>`);
  lines.push('    </dependency>', '');
  return lines.join('\n');
}

// ── Jar entry list ──────────────────────────────────────────────────────
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
//
// Builds a throwaway "adapter dir" that looks, to the script, exactly like
// a post-`clein jar`/`clein pom` build tree, plus PATH stubs standing in
// for the two external tools.
function makeFixture({ pom = GENUINE_POM, jarEntries = GENUINE_JAR_ENTRIES, jars = ['reagent-slim-0.0.1.alpha.jar'] } = {}) {
  const dir = makeScratchDir(REPO_ROOT, 'rf2-slim-preflight');

  const targetDir = path.join(dir, 'target');
  fs.mkdirSync(targetDir, { recursive: true });
  for (const jarName of jars) {
    // Content is irrelevant — the stub `jar` reports the entry list. The
    // file must merely EXIST so the script's `[ -f ]` glob check finds it.
    fs.writeFileSync(path.join(targetDir, jarName), '');
  }

  if (pom !== null) {
    const pomDir = path.join(targetDir, 'classes', 'META-INF', 'maven', 'day8', 'reagent-slim');
    fs.mkdirSync(pomDir, { recursive: true });
    fs.writeFileSync(path.join(pomDir, 'pom.xml'), pom);
  }

  // `jar tf <jar>` → the fixture's entry list, verbatim.
  fs.writeFileSync(path.join(dir, 'jar-entries.txt'), `${jarEntries}\n`);

  const binDir = path.join(dir, 'bin');
  fs.mkdirSync(binDir, { recursive: true });
  // `clojure -M:clein jar|pom` — no-op; target/ is pre-placed above.
  writeStub(path.join(binDir, 'clojure'), '#!/usr/bin/env sh\nexit 0\n');
  // `jar tf <path>` — print the fixture entry list. FIXTURE_DIR is set in
  // the stub's environment by the runner below. Reading it HERE, inside the
  // stub file, rather than in the runner's `bash -lc` string is what keeps
  // the runner free of self-referential expansion (rf2-sefx0).
  writeStub(path.join(binDir, 'jar'), '#!/usr/bin/env sh\ncat "$FIXTURE_DIR/jar-entries.txt"\n');

  return { dir, rel: relPosix(dir) };
}

// MSYS/Git Bash treats a file as executable when it carries a `#!` magic
// number OR the user exec bit; set both so PATH lookup resolves the stub on
// Windows and Linux alike.
function writeStub(file, body) {
  fs.writeFileSync(file, body, { mode: 0o755 });
  fs.chmodSync(file, 0o755);
}

// Forward-slashed path of `abs` relative to REPO_ROOT.
function relPosix(abs) {
  return path.relative(REPO_ROOT, abs).split(path.sep).join('/');
}

function shQuote(s) {
  return `'${String(s).replace(/'/g, `'\\''`)}'`;
}

// Variables any POSIX shell already has set before our command runs. See
// the portability contract in `buildCommand` — these are the ONLY ones the
// command string may reference.
const PRE_EXISTING_SHELL_VARS = new Set(['PWD', 'PATH']);

// Build the `bash -lc` command that puts the fixture's stub bin on PATH
// (in POSIX form, whatever the host) and runs the real script against the
// fixture.
//
// PORTABILITY CONTRACT (rf2-sefx0): every `$NAME` below must name a
// variable that ALREADY EXISTS in the shell's environment — $PWD and $PATH.
// The command must never read back a variable it assigns itself.
//
// Why: when `bash` resolves to WSL's C:\Windows\System32\bash.exe — the
// default on a stock Windows box, where Git Bash is not first on PATH —
// the launcher relays the `-c` string to the Linux side as a command line
// that is expanded TWICE. Proof:
//
//   bash -lc 'FOO=hello; printf %s "\$FOO"'   # prints: hello
//
// A single-pass POSIX shell must print the literal `$FOO` there; printing
// `hello` means an outer pass already substituted it. So in the ORIGINAL
// form of this runner —
//
//   FIXTURE_DIR="$PWD/<rel>"; PATH="$FIXTURE_DIR/bin:$PATH"; export …
//
// — that outer pass expanded `$FIXTURE_DIR`, undefined at that point, to
// the empty string BEFORE the assignment to its left ever ran. PATH became
// "/bin:<real PATH>", the stub `clojure`/`jar` were never found, and all 15
// cases failed on stock Windows while passing under Git Bash. `$PWD` and
// `$PATH` survive both passes precisely because they are already set.
//
// The fix is a single `env`-prefixed command with no self-reference. The
// stub `jar` reads $FIXTURE_DIR from its own environment — that read lives
// inside the stub FILE, which the command string never expands.
function buildCommand(rel) {
  return [
    'env',
    `FIXTURE_DIR="$PWD/${rel}"`,
    `PATH="$PWD/${rel}/bin:$PATH"`,
    `${shQuote(`./${SCRIPT_REL}`)} ${shQuote(rel)}`,
  ].join(' ');
}

function run(fixture) {
  return spawnSync('bash', ['-lc', buildCommand(fixture.rel)], {
    cwd: REPO_ROOT,
    encoding: 'utf8',
  });
}

function cleanup() {
  cleanupScratchDirs();
}

// ── Assertion helpers ───────────────────────────────────────────────────

function expectPass(fixture, what) {
  const res = run(fixture);
  const out = `${res.stdout}\n${res.stderr}`;
  assert.equal(res.status, 0, `${what}: expected exit 0 (PASSED), got ${res.status}\n${out}`);
  assert.match(out, /verification PASSED/, `${what}: expected a PASSED verdict\n${out}`);
}

function expectFail(fixture, what, messagePattern) {
  const res = run(fixture);
  const out = `${res.stdout}\n${res.stderr}`;
  assert.notEqual(res.status, 0, `${what}: expected a NON-ZERO exit (FAILED), got ${res.status} — the gate waved a bad package through\n${out}`);
  assert.doesNotMatch(out, /verification PASSED/, `${what}: must not print a PASSED verdict\n${out}`);
  if (messagePattern) {
    assert.match(out, messagePattern, `${what}: expected a diagnostic matching ${messagePattern}\n${out}`);
  }
}

// ── The genuine pom must keep passing ───────────────────────────────────
//
// The over-tightening trap: a preflight that reds a CORRECT pom blocks
// legitimate releases and gets bypassed by whoever is trying to ship. This
// is the single most important case in the file.
test('genuine pom + genuine jar → PASSED', () => {
  try {
    expectPass(makeFixture(), 'genuine package');
  } finally {
    cleanup();
  }
});

// ── The rf2-do3m2 defect: dependency-set holes ──────────────────────────

test('empty <dependencies/> → FAILED', () => {
  try {
    expectFail(makeFixture({ pom: withDependencies('') }), 'empty <dependencies>', /re-frame2/);
  } finally {
    cleanup();
  }
});

test('absent <dependencies> block → FAILED', () => {
  try {
    const pom = withDependencies(null);
    assert.doesNotMatch(pom, /<dependencies>/, 'fixture must carry no <dependencies> element');
    expectFail(makeFixture({ pom }), 'absent <dependencies>', /re-frame2/);
  } finally {
    cleanup();
  }
});

// The real-world shape: `clein pom` SKIPS :local/root coordinates, so this
// is precisely what gets generated if release.yml's rewrite step did not
// take effect. Published, it resolves to nothing on the consumer's machine.
test('required day8/re-frame2 dep missing (the clein :local/root skip) → FAILED', () => {
  try {
    const pom = GENUINE_POM.replace(CORE_DEP_BLOCK, '');
    // NB: the scm <url> legitimately contains "re-frame2"; scope the sanity
    // check to the dependency coordinate itself.
    assert.doesNotMatch(pom, /<artifactId>re-frame2<\/artifactId>/, 'fixture must carry no re-frame2 dependency coordinate');
    expectFail(makeFixture({ pom }), 'missing core dep', /re-frame2/);
  } finally {
    cleanup();
  }
});

test('day8/re-frame2 with an empty <version/> → FAILED', () => {
  try {
    const pom = GENUINE_POM.replace(
      '      <version>0.0.1.alpha</version>\n    </dependency>\n  </dependencies>',
      '      <version></version>\n    </dependency>\n  </dependencies>',
    );
    assert.match(pom, /<version><\/version>/, 'fixture must carry an empty version');
    expectFail(makeFixture({ pom }), 'empty version', /version/i);
  } finally {
    cleanup();
  }
});

test('dependency with a missing <groupId> → FAILED', () => {
  try {
    const pom = withDependencies(depBlock({ artifactId: 're-frame2', version: '0.0.1.alpha' }));
    expectFail(makeFixture({ pom }), 'missing groupId', /groupId/i);
  } finally {
    cleanup();
  }
});

test('dependency with a missing <artifactId> → FAILED', () => {
  try {
    const pom = withDependencies(depBlock({ groupId: 'day8', version: '0.0.1.alpha' }));
    expectFail(makeFixture({ pom }), 'missing artifactId', /artifactId/i);
  } finally {
    cleanup();
  }
});

// ── Unexpected extras (see the script's ALLOWED-set rationale) ──────────

test('unexpected extra dependency → FAILED', () => {
  try {
    const pom = withExtraDependency(depBlock({ groupId: 'com.example', artifactId: 'surprise', version: '1.0.0' }));
    expectFail(makeFixture({ pom }), 'extra dependency', /com\.example\/surprise|unexpected/i);
  } finally {
    cleanup();
  }
});

// Regression guards for the two invariants the script already named. Both
// now fall out of the ALLOWED-set check rather than being special-cased
// greps — but they are the historically dangerous shapes, so pin them.
test('DIRECT day8/re-frame2-reagent bridge dep → FAILED', () => {
  try {
    const pom = withExtraDependency(depBlock({ groupId: 'day8', artifactId: 're-frame2-reagent', version: '0.0.1.alpha' }));
    expectFail(makeFixture({ pom }), 'bridge adapter dep', /re-frame2-reagent/);
  } finally {
    cleanup();
  }
});

test('DIRECT stock-reagent dep → FAILED', () => {
  try {
    const pom = withExtraDependency(depBlock({ groupId: 'reagent', artifactId: 'reagent', version: '1.2.0' }));
    expectFail(makeFixture({ pom }), 'stock reagent dep', /reagent\/reagent/);
  } finally {
    cleanup();
  }
});

// ── Jar-side invariants (pre-existing; pinned so the harness has teeth) ──

test('jar missing the canonical re_frame/adapter/reagent.cljs → FAILED', () => {
  try {
    const entries = GENUINE_JAR_ENTRIES.replace('re_frame/adapter/reagent.cljs', 're_frame/adapter/reagent_slim.cljs');
    expectFail(makeFixture({ jarEntries: entries }), 'missing canonical ns', /MISSING re_frame\/adapter\/reagent\.cljs/);
  } finally {
    cleanup();
  }
});

test('jar still carrying a reagent_slim entry → FAILED', () => {
  try {
    const entries = `${GENUINE_JAR_ENTRIES}\nre_frame/adapter/reagent_slim.cljs`;
    expectFail(makeFixture({ jarEntries: entries }), 'reagent_slim entry present', /reagent_slim/);
  } finally {
    cleanup();
  }
});

// ── Structural preconditions (pre-existing; exit 2 paths) ───────────────

test('no jar produced → FAILED', () => {
  try {
    expectFail(makeFixture({ jars: [] }), 'no jar', /no target\/reagent-slim-\*\.jar/);
  } finally {
    cleanup();
  }
});

test('more than one jar → FAILED', () => {
  try {
    const fixture = makeFixture({ jars: ['reagent-slim-0.0.1.alpha.jar', 'reagent-slim-0.0.2.alpha.jar'] });
    expectFail(fixture, 'two jars', /more than one/);
  } finally {
    cleanup();
  }
});

test('pom absent entirely → FAILED', () => {
  try {
    expectFail(makeFixture({ pom: null }), 'no pom', /expected pom not found/);
  } finally {
    cleanup();
  }
});

// ── Shell portability (rf2-sefx0) ───────────────────────────────────────
//
// Regression for the WSL double-expansion defect described in
// `buildCommand`. Structural, not behavioural, and deliberately so: on Git
// Bash and on the Ubuntu runner the `-c` string is expanded ONCE, so a
// reintroduced self-reference would work there and this suite would stay
// green while silently reverting to red for every contributor whose `bash`
// resolves to C:\Windows\System32\bash.exe. A string assertion fires on
// every host, including the ones that cannot observe the bug.
test('bash command references only pre-existing shell variables (rf2-sefx0)', () => {
  const command = buildCommand('.scratch/rf2-slim-preflight-probe');
  const referenced = [
    ...new Set(
      [...command.matchAll(/\$\{?([A-Za-z_][A-Za-z0-9_]*)\}?/g)].map((m) => m[1]),
    ),
  ].sort();
  // Sanity: if the regex stops matching, the assertion below goes vacuously
  // green and the guard silently stops guarding.
  assert.ok(
    referenced.length > 0,
    'sanity: the command must reference at least one shell variable',
  );
  const notPreExisting = referenced.filter((name) => !PRE_EXISTING_SHELL_VARS.has(name));
  assert.deepEqual(
    notPreExisting,
    [],
    `the bash -lc command may reference only variables that already exist in the shell `
      + `environment (${[...PRE_EXISTING_SHELL_VARS].sort().join(', ')}), but it references `
      + `${notPreExisting.join(', ')}. WSL's bash.exe expands the -c string TWICE, so a `
      + `variable this command assigns itself resolves to EMPTY before the assignment runs — `
      + `dropping the fixture stubs off PATH and failing every case on stock Windows while `
      + `passing under Git Bash (rf2-sefx0).`,
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
  console.error(`preflight-reagent-slim-package tests: ${failed} of ${tests.length} failed.`);
  process.exit(1);
}

console.log(`preflight-reagent-slim-package tests: ${tests.length} passed.`);
