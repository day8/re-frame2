#!/usr/bin/env node
'use strict';
// EVERY RIDER OF A SHARED BUILD ID CLEARS ITS CACHE.
//
//     node core/test/re_frame/bench/lane_cache_wiring.test.cjs
//
// A bench lane gives several programs ONE build id, each merging its own
// `:init-fn` and `:output-dir` through `--config-merge`. shadow-cljs keys the
// build cache on the build id alone, so those programs share one cache entry, and
// a program that does not clear it first runs a bundle it did not build. Both
// builds exit 0 and the stale bundle fails only when a page executes it, so this
// is a source-level gate. `lane_cache.cjs`, beside this file, carries the fault
// class and the rejected alternatives.
//
// THE ROSTER IS KEYED ON BUILD IDS, not on a directory: `SHARED_BUILD_IDS` is the
// one declared list, and every rider of those ids is discovered by scanning the
// implementation and bench trees, so a new rider is checked without an edit here.
// An id with fewer than two riders fails `really is shared`, so deleting a lane
// without its id is loud.
//
// A rider reaches a release build DIRECTLY (it spawns shadow-cljs's
// `cli/runner.js` with `'release', BUILD, '--config-merge', …`) or through a DOOR
// (a required local module that owns the spawn, called as
// `shadowBuild({ mode: 'release', buildId: BUILD_ID, … })`). The door is resolved
// through one level of local requires rather than named, so a second door is
// found the day it lands.
//
// NOT COVERED: compile-mode riders (listed in the roster, not checked: every
// measurement behind the rule was on release bundles), and a rider that neither
// names the id nor reaches `runner.js` — `every clear is accounted for` catches
// the half of that class that clears.
//
// THE FAIL-OPENS a naive scan admits: clearing one id and building another, and a
// clear that survives only in a comment or only in a string. So the build id is
// read from both sites and must be the same name, and each read runs over the
// projection that can answer it: `code` (comments blanked) for the spawn side,
// whose argv and require path are string literals, and `exec` (comments and
// strings blanked) for the clear. The projection is not a parser: a mis-scan
// blanks more than it should, which makes a check fire rather than pass, and an
// unterminated string or comment fails the run against its file.
//
// FIXTURES. `lane_cache_fixtures/` holds six drivers with known verdicts, read as
// text and never executed, so the gate cannot quietly stop firing. They stay in
// the ESLint path, held to the rules real drivers are.
//
// Discovered by `npm run test:scripts`.

const assert = require('node:assert');
const fs = require('node:fs');
const path = require('node:path');
const test = require('node:test');

// Found by content (the directory holding shadow-cljs.edn and package.json), so
// moving this file cannot silently rescope what it scans.
function findImplRoot(from) {
  let dir = from;
  for (;;) {
    if (
      fs.existsSync(path.join(dir, 'shadow-cljs.edn')) &&
      fs.existsSync(path.join(dir, 'package.json'))
    ) {
      return dir;
    }
    const up = path.dirname(dir);
    if (up === dir) return null;
    dir = up;
  }
}

const IMPL_ROOT = findImplRoot(__dirname);
const REPO_ROOT = IMPL_ROOT === null ? null : path.dirname(IMPL_ROOT);
// THE BENCH LANE LIVES OUTSIDE THE PACKAGE: every rider of `fresco-bench`
// sits under the bench project — its own `shadow-cljs.edn` and
// `package.json`, in `bench/fresco/` beside `implementation/`. Both trees are
// scanned. A root that is not found leaves `fresco-bench` with no riders, which
// `every declared shared build id really is shared` reports, so the gate cannot
// silently rescope itself to one tree.
const BENCH_ROOT = REPO_ROOT === null ? null : path.join(REPO_ROOT, 'bench', 'fresco');
const SCAN_ROOTS = [IMPL_ROOT, BENCH_ROOT].filter((r) => r !== null && fs.existsSync(r));
const FIXTURE_DIR = path.join(__dirname, 'lane_cache_fixtures');

// THE DECLARED LIST, and the only thing here that is not discovered. An id
// belongs on it when MORE THAN ONE program builds it while merging its own arm
// on top — that is what makes the cache entry shared and the clear obligatory.
// An id built by exactly one program is not shared and needs no rule.
//
// Adding a lane: add its id. Deleting a lane: delete its id, and the
// `really is shared` check below makes forgetting to loud.
const SHARED_BUILD_IDS = ['fresco-bench'];

const SKIP_DIRS = new Set(['node_modules', '.shadow-cljs', '.git', 'dist', 'out', 'target', 'public']);

function walkCjs(dir, acc) {
  for (const entry of fs.readdirSync(dir, { withFileTypes: true })) {
    if (entry.isDirectory()) {
      if (SKIP_DIRS.has(entry.name)) continue;
      walkCjs(path.join(dir, entry.name), acc);
    } else if (entry.isFile() && entry.name.endsWith('.cjs')) {
      acc.push(path.join(dir, entry.name));
    }
  }
  return acc;
}

// Two blanked views of one source, both the ORIGINAL LENGTH — blanked spans
// become spaces and newlines survive — so an offset taken from one compares
// directly with an offset taken from the other:
//
//   `code`  comments blanked, string bodies KEPT. What discovery and the
//           spawn-side reads need: `--config-merge`, `'release'` and the
//           `require` path are string literals in every correct driver.
//   `exec`  comments AND string bodies blanked; the quotes themselves stay, so
//           the lengths line up and an empty literal still reads as one. What
//           the clear call and its build-id argument are read from — a
//           `resetLaneBuildCache(IMPL, BUILD)` sitting in a log line clears no
//           more than the same words in a comment do.
//
// Throws rather than guessing when it runs off the end of a string or block.
function project(src, file) {
  const code = src.split('');
  const exec = src.split('');
  // A comment is absent from both views; only strings tell them apart.
  const blankBoth = (from, to) => {
    for (let j = from; j < to; j += 1) {
      if (src[j] !== '\n') {
        code[j] = ' ';
        exec[j] = ' ';
      }
    }
  };
  let i = 0;
  while (i < src.length) {
    const c = src[i];
    const next = src[i + 1];
    if (c === '/' && next === '/') {
      let end = i;
      while (end < src.length && src[end] !== '\n') end += 1;
      blankBoth(i, end);
      i = end;
    } else if (c === '/' && next === '*') {
      const end = src.indexOf('*/', i + 2);
      assert.notStrictEqual(end, -1, `${file}: unterminated block comment`);
      blankBoth(i, end + 2);
      i = end + 2;
    } else if (c === '"' || c === "'" || c === '`') {
      const open = i;
      i += 1;
      while (i < src.length && src[i] !== c) i += src[i] === '\\' ? 2 : 1;
      assert.ok(i < src.length, `${file}: unterminated string opened with ${c}`);
      for (let j = open + 1; j < i; j += 1) if (src[j] !== '\n') exec[j] = ' ';
      i += 1;
    } else {
      i += 1;
    }
  }
  return { code: code.join(''), exec: exec.join('') };
}

const SRC_CACHE = new Map();
function readSource(abs) {
  if (!SRC_CACHE.has(abs)) SRC_CACHE.set(abs, fs.readFileSync(abs, 'utf8'));
  return SRC_CACHE.get(abs);
}

function read(abs) {
  const file = path.relative(REPO_ROOT, abs).split(path.sep).join('/');
  return { file, abs, ...project(readSource(abs), file) };
}

// EVERY PATTERN BELOW IS QUOTE-AGNOSTIC AND TOLERATES WHITESPACE, because a
// scan keyed to single quotes walks past a double-quoted call site, and a
// "found nothing at all" fallback keyed the same way agrees with it because
// both are blind.
const RUNNER = /runner\.js/;
const CONFIG_MERGE = /['"]--config-merge['"]/;
const SPAWN_SLOT = /['"]release['"]\s*,/;
const DOOR_CALL = /shadowBuild(?:Verdict)?\s*\(/;
const DOOR_MODE_RELEASE = /mode\s*:[^,\n}]*['"]release['"]/;
const LOCAL_REQUIRE = /require\(\s*['"](\.[^'"]*\.cjs)['"]\s*\)/g;

const CLEAR_CALL = /resetLaneBuildCache\s*\(/;
const CLEAR_BUILD_ID = /resetLaneBuildCache\s*\(\s*[\w$.]+\s*,\s*([A-Za-z_$][\w$]*)\s*\)/;
const SPAWN_BUILD_ID = /['"]release['"]\s*,\s*([A-Za-z_$][\w$]*)/;
const DOOR_BUILD_ID = /buildId\s*:\s*([A-Za-z_$][\w$]*)/;

/** Does this file's own text reach shadow-cljs's CLI runner? */
function namesRunner({ code }) {
  return RUNNER.test(code);
}

// One level of require resolution, which is what turns "the door" from a name
// hard-coded here into a thing discovery finds. A candidate that requires a
// local `.cjs` naming `runner.js` reaches shadow-cljs through it.
function reachesRunner(rider) {
  if (namesRunner(rider)) return true;
  LOCAL_REQUIRE.lastIndex = 0;
  let m;
  while ((m = LOCAL_REQUIRE.exec(rider.code)) !== null) {
    const target = path.resolve(path.dirname(rider.abs), m[1]);
    if (!fs.existsSync(target)) continue;
    if (RUNNER.test(readSource(target))) return true;
  }
  return false;
}

// THE MATCH IS QUOTE-DELIMITED, never a bare substring. A shared id can be a
// PREFIX of other real build ids (`fresco-bench` of `fresco-bench-node`), so a
// substring test would read a driver of any of those as a rider of the shared
// id and hold it to a rule that is not its. Requiring the closing quote is what
// separates the id from the ids that start the same way.
const namesId = (src, id) => new RegExp(`['"]${id}['"]`).test(src);

/** The declared shared ids this file names as a string literal. */
function lanesOf({ code }) {
  return SHARED_BUILD_IDS.filter((id) => namesId(code, id));
}

// A DIRECT rider spawns the runner itself with its own `--config-merge`; a DOOR
// rider hands the build to a module that does. Both are required to look like a
// RELEASE, because that is the mode every measurement behind this rule used.
function shapeOf(rider) {
  const direct =
    RUNNER.test(rider.code) && CONFIG_MERGE.test(rider.code) && SPAWN_SLOT.test(rider.code);
  const door = DOOR_CALL.test(rider.exec) && DOOR_MODE_RELEASE.test(rider.code);
  if (direct) return 'direct';
  if (door) return 'door';
  return null;
}

// ## Discovery
//
// Candidates are every `.cjs` under the implementation root that NAMES a
// declared shared id as a string literal — minus this gate's own fixtures,
// which are rider-shaped on purpose. `.test.cjs` files are excluded because a
// test that mentions a build id is not riding it; the drivers are the subjects.
//
// Candidacy is decided on RAW source rather than the projection, so that a file
// whose only mention is in a comment still counts as a candidate and therefore
// still has to be READABLE. Deciding it after projection would let an
// unparseable file duck the `every candidate was readable` check by having its
// only occurrence blanked — a fail-open in the one place this gate cannot
// afford one.
const CANDIDATE_PATHS = SCAN_ROOTS.flatMap((root) => walkCjs(root, []))
  .filter((abs) => !abs.endsWith('.test.cjs'))
  .filter((abs) => !abs.startsWith(FIXTURE_DIR + path.sep))
  .filter((abs) => SHARED_BUILD_IDS.some((id) => namesId(readSource(abs), id)))
  .sort();

// A projection failure is a FAILURE, never a skip: a candidate this scanner
// cannot read is a candidate it has not checked.
const PARSE_FAILURES = [];
const CANDIDATES = [];
for (const abs of CANDIDATE_PATHS) {
  try {
    CANDIDATES.push(read(abs));
  } catch (e) {
    PARSE_FAILURES.push(`${path.relative(REPO_ROOT, abs)}: ${e.message}`);
  }
}

const BUILDERS = CANDIDATES.filter((c) => reachesRunner(c));
const RIDERS = BUILDERS.filter((c) => shapeOf(c) !== null);
// Listed in the roster, not checked — see NOT COVERED above.
const COMPILE_ONLY = BUILDERS.filter((c) => shapeOf(c) === null);

const CHECKS = {
  requires: {
    title: 'requires lane_cache.cjs',
    // Over `code`, string bodies and all: a genuine require's path is itself a
    // string literal, so this one cannot be read from executable text.
    //
    // THE PATH IS DELIBERATELY UNPINNED. `lane_cache.cjs` sits in
    // the shared bench-helper directory, which the rider trees reach at
    // different relative depths — and one rider composes the path with
    // `path.join` rather than writing it whole, which a pinned prefix would
    // read as a violation. What this check owns is THAT a rider imports the
    // cache rule; WHERE the rule sits, and how the path is spelled, is not its
    // business.
    //
    // AND IT IS NOT COSMETIC. A rider hand-rolling the clear as an inline
    // `fs.rmSync` — correct id, right moment — lacks the Windows retry loop
    // `resetLaneBuildCache` carries, so an antivirus scanner or a just-exited
    // JVM holding a handle turns the clear into a throw. One rule, one
    // implementation, is the point.
    run: ({ file, code }) =>
      /require\([^)]*lane_cache\.cjs[^)]*\)/.test(code)
        ? null
        : `${file} builds a shared build id and must require lane_cache.cjs — ` +
          'an inline rmSync is a second copy of the rule without its Windows retry guard',
  },

  clearsFirst: {
    title: 'calls resetLaneBuildCache BEFORE it builds',
    run: (rider) => {
      // The call comes from EXECUTABLE text, so neither a comment nor a string
      // can stand in for it. The build site comes from whichever projection can
      // see it: the argv slot is a string literal, the door call is executable.
      // Comparing offsets is sound because the projections are the same length
      // as the source and as each other.
      const { file, code, exec } = rider;
      const clear = exec.search(CLEAR_CALL);
      const sites = [code.search(SPAWN_SLOT), exec.search(DOOR_CALL)].filter((i) => i !== -1);
      if (clear === -1) return `${file} never calls resetLaneBuildCache (the name in a comment or a string is not a call)`;
      if (sites.length === 0) return `${file} has no release build to guard`;
      return clear < Math.min(...sites)
        ? null
        : `${file} clears the cache AFTER starting the build, which clears nothing`;
    },
  },

  noLiteralId: {
    title: 'names the build id once, not as a literal at the build site',
    run: ({ file, code, exec }) =>
      /['"]release['"]\s*,\s*['"]/.test(code) || /buildId\s*:\s*['"]/.test(exec)
        ? `${file} passes a literal build id to the build; hoist it to a const and ` +
          'pass that same const to resetLaneBuildCache'
        : null,
  },

  sameBuildId: {
    title: 'clears the SAME build id it builds',
    run: (rider) => {
      // The central claim of this gate: clearing one id and building another
      // is the defect, not a fix for it. Silent when there is no clear at all
      // — `clearsFirst` owns that fault and reports it better, and a clear
      // that exists only as comment or string text is no clear at all.
      const { file, code, exec } = rider;
      if (!CLEAR_CALL.test(exec)) return null;
      const cleared = exec.match(CLEAR_BUILD_ID);
      const released = shapeOf(rider) === 'direct' ? code.match(SPAWN_BUILD_ID) : exec.match(DOOR_BUILD_ID);
      if (!cleared) {
        return `${file} calls resetLaneBuildCache but its build-id argument is not a plain ` +
          'identifier, so it cannot be compared with the one it builds; pass the same const';
      }
      if (!released) {
        return `${file} does not name the build id with an identifier at the build site, so ` +
          'it cannot be compared with the one it clears';
      }
      return cleared[1] === released[1]
        ? null
        : `${file} clears \`${cleared[1]}\` but builds \`${released[1]}\` — one build id, ` +
          'two names: it empties a cache nobody builds into and builds into a cache nobody ' +
          'cleared. Pass one const to both.';
    },
  },
};

// ## The honesty checks
//
// Every one of these exists because a discovery that quietly finds nothing is
// the exact fail-open this lane is prone to: a scan whose pattern drifted
// reports zero riders and every assertion below vacuously passes.

test('every candidate was readable (a file the scanner cannot read is unchecked)', () => {
  assert.deepStrictEqual(
    PARSE_FAILURES, [],
    'the source projection refused these files, so they were NOT checked. ' +
      'Sharpen `project` — do not skip them:\n  ' + PARSE_FAILURES.join('\n  ')
  );
});

test('every declared shared build id really is shared (>= 2 riders)', () => {
  // An id with one rider is not shared and needs no rule; an id with none has
  // either lost its lane or lost its discovery. Both are edits somebody owes,
  // and this is where a deleted lane announces the second half of its deletion.
  const thin = SHARED_BUILD_IDS
    .map((id) => ({ id, riders: RIDERS.filter((r) => lanesOf(r).includes(id)) }))
    .filter(({ riders }) => riders.length < 2);
  assert.deepStrictEqual(
    thin.map(({ id, riders }) => `${id}: ${riders.length}`), [],
    'a declared shared build id has fewer than two riders. Either the lane was ' +
      'deleted and SHARED_BUILD_IDS still names its id, or discovery has drifted ' +
      'and the riders are no longer being found. Repair one or the other — do not ' +
      'delete the check.\n' +
      thin.map(({ id, riders }) => `  ${id}: ${riders.length} rider(s): ${riders.map((r) => r.file).join(', ') || '(none)'}`).join('\n')
  );
});

test('every clear of a shared id is accounted for (discovery has not drifted)', () => {
  // The independent cross-check, and the one that catches a build shape this
  // file does not know: a program that clears a shared id's cache is a rider by
  // its own admission, so if discovery did not find it, discovery is wrong.
  const clearing = CANDIDATES.filter((c) => CLEAR_CALL.test(c.exec) && lanesOf(c).length > 0);
  const found = new Set([...RIDERS, ...COMPILE_ONLY].map((r) => r.file));
  const orphans = clearing.filter((c) => !found.has(c.file)).map((c) => c.file);
  assert.deepStrictEqual(
    orphans, [],
    'these files clear a shared build id but discovery did not classify them as ' +
      'builders, so they are riding by a route this gate cannot see. Teach ' +
      '`reachesRunner`/`shapeOf` the new shape — do not delete the check.'
  );
});

for (const rider of RIDERS) {
  test(`${rider.file} passes every wiring check`, () => {
    const failures = Object.values(CHECKS).map((check) => check.run(rider)).filter((f) => f !== null);
    assert.deepStrictEqual(failures, []);
  });
}

// ## The gate's own regression net
//
// Each fixture carries the one check it must trip, or null for the correctly
// wired controls. The comment and string fixtures are not duplicates (different
// projections catch them), and the door pair covers the `buildId:` read that the
// direct pair does not.
const FIXTURES = {
  'agreeing_ids_run.cjs': null,
  'door_agreeing_ids_run.cjs': null,
  'mismatched_ids_run.cjs': 'sameBuildId',
  'door_mismatched_ids_run.cjs': 'sameBuildId',
  'commented_clear_run.cjs': 'clearsFirst',
  'string_clear_run.cjs': 'clearsFirst',
};

test('the fixture directory holds exactly the fixtures named here', () => {
  // Otherwise a fixture can be added and left unasserted, or deleted and its
  // entry left behind — either way the net has a hole it does not report.
  assert.deepStrictEqual(
    fs.readdirSync(FIXTURE_DIR).filter((f) => f.endsWith('.cjs')).sort(),
    Object.keys(FIXTURES).sort()
  );
});

for (const [file, expected] of Object.entries(FIXTURES)) {
  test(`fixture ${file} is rider-shaped, outside discovery, and trips exactly ${expected === null ? 'nothing' : expected}`, () => {
    const fixture = read(path.join(FIXTURE_DIR, file));
    assert.ok(reachesRunner(fixture), `${file} does not reach shadow-cljs, so its verdict proves nothing`);
    assert.ok(shapeOf(fixture) !== null, `${file} is not rider-shaped, so its verdict proves nothing`);
    assert.ok(
      lanesOf(fixture).length > 0,
      `${file} names no declared shared build id, so discovery would skip it on ` +
        'content and the path exclusion below would prove nothing'
    );
    assert.ok(
      !RIDERS.some((r) => r.file === fixture.file),
      `${file} was discovered as a real rider; fixtures must stay in ${path.basename(FIXTURE_DIR)}`
    );
    const fired = Object.entries(CHECKS)
      .filter(([, check]) => check.run(fixture) !== null)
      .map(([id]) => id);
    assert.deepStrictEqual(
      fired,
      expected === null ? [] : [expected],
      expected === null
        ? `${file} is correctly wired and must pass every check; it failed: ${fired.join(', ')}`
        : `${file} must fail ${expected} and nothing else; it failed: ${fired.join(', ') || 'nothing'}`
    );
  });
}

// The roster this run checked, printed so CI output shows the scope.
for (const id of SHARED_BUILD_IDS) {
  const riders = RIDERS.filter((r) => lanesOf(r).includes(id));
  console.log(`  ${id} — ${riders.length} release rider(s):`);
  for (const r of riders) console.log(`      ${shapeOf(r).padEnd(6)} ${r.file}`);
  const compile = COMPILE_ONLY.filter((r) => lanesOf(r).includes(id));
  for (const r of compile) console.log(`      (compile-mode, not checked) ${r.file}`);
}
