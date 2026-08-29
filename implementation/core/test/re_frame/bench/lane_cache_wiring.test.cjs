#!/usr/bin/env node
'use strict';
// EVERY RIDER OF A SHARED BUILD ID CLEARS ITS CACHE — rf2-t4j7c, rf2-d19nf.
//
//     node core/test/re_frame/bench/lane_cache_wiring.test.cjs
//
// THE DEFECT THIS PINS. A bench lane gives N programs ONE build id and lets
// each merge its own `:init-fn` and `:output-dir` onto it through
// `--config-merge`. shadow-cljs derives the build cache directory from the
// build id ALONE — `<cache-root>/builds/<build-id>/<mode>`, fixed before any
// `--config-merge` data is applied — so the arm is invisible to the cache key
// and N different programs share ONE cache entry. A program that does not
// clear that entry first runs a bundle it did not build. `lane_cache.cjs`,
// beside this file, carries the fault class, the isolation that found the
// carrier (`shadow-js/index.json.transit`) and the alternatives that were
// rejected with reasons (rf2-2rtt6.20).
//
// MEASURED, on unmodified `main`, at the commit the first version of this test
// landed against:
//
//     cold  -> b7's config-merge      204 files, 149 compiled, exit 0
//     warm  -> ladder's config-merge  160 files,  11 compiled, exit 0
//     cold  -> ladder's config-merge  160 files, 105 compiled, exit 0
//
// The two ladder bundles differ (`bfc7abfe…` against `fae4cd71…`, 649,134 B
// against 649,245 B) and only the cold one runs. Loaded in headless Chromium
// and left 3s to settle, the 11-compiled bundle raises
//
//     Cannot read properties of undefined (reading 'd')
//
// before its entry executes, while the 105-compiled bundle raises nothing and
// reaches its own application code. **Both builds exit 0**, which is the whole
// reason this has to be a source-level gate: no build-time signal exists to
// check, and the failure only appears when a page executes the bundle.
//
// WHY A GATE AND NOT JUST THE FIX. The invariant was written down in
// `lane_cache.cjs` and enforced nowhere, so drivers kept landing armed and four
// had accumulated by the time anyone counted.
//
// ## Where the roster comes from, and why not a directory (rf2-d19nf)
//
// THE PREVIOUS VERSION OF THIS FILE DISCOVERED ITS SUBJECTS WITH
// `readdirSync(__dirname)`. That coupled the gate to the directory it happened
// to sit in rather than to the invariant it checks, with two consequences, both
// of them realised:
//
//   * It only ever saw ONE lane. It lived in Freehand's bench directory and
//     asserted "at least the 7 known freehand-release riders". The entire
//     `hicasso-bench` lane — more riders than Freehand ever had, spread across
//     three trees — was never in its scope at all.
//   * It died with its tree. When `implementation/freehand/` is deleted the
//     gate goes with it, silently, taking the only enforcement of a rule whose
//     violation exits 0 twice and only shows up as a blank page.
//
// SO THE ROSTER IS NOW KEYED ON THE BUILD IDS, which is the thing the invariant
// is actually about. `SHARED_BUILD_IDS` below is an explicit, short, declared
// list — the ids that more than one program rides. Everything else is
// discovered: the riders of each id are found by scanning the tree, not by
// being remembered.
//
// WHAT HAPPENS WHEN A RIDER IS ADDED AND NOTHING HERE IS UPDATED. It is picked
// up automatically and checked, because riding the id means naming it, and
// naming it is what discovery keys on. Nobody has to remember this file exists.
// That is the property `readdir` was reaching for and got in too small a scope.
//
// AND WHEN AN ID STOPS BEING SHARED, this file goes RED rather than quiet: a
// declared id with fewer than two riders fails `every declared shared build id
// really is shared`. Deleting a lane is therefore a two-line change — the tree
// and the entry here — and forgetting the second half is loud. That is the
// exact failure the previous version could not produce.
//
// ## The two ways a program reaches a release build
//
// Discovery has to know both, because they look nothing alike in source and the
// old scan only knew the first:
//
//   DIRECT  the program spawns shadow-cljs's own `cli/runner.js` itself, with
//           `'release', BUILD, '--config-merge', …` in the argv. Every
//           `freehand-release` rider, and three riders of `hicasso-bench`.
//   DOOR    the program hands the build to a module that owns the spawn —
//           `lane_build.cjs`, the hicasso lane's one build door (rf2-2rtt6.73)
//           — as `shadowBuild({ mode: 'release', buildId: BUILD_ID, … })`. Most
//           of `hicasso-bench`.
//
// THE DOOR IS RESOLVED, NOT NAMED. A candidate counts as spawning shadow-cljs
// if its own text names `runner.js`, or if any local `.cjs` it requires does.
// So a second build door added tomorrow is discovered the day it lands, without
// an edit here — the same reason the riders are discovered rather than listed.
//
// ## What this gate does NOT cover, said out loud
//
//   * COMPILE-MODE BUILDS. `<build-id>/<mode>` means `release` and `dev` are
//     separate cache entries, and every measurement behind this rule was taken
//     on `:advanced` release bundles. Compile-mode riders of a shared id are
//     found by discovery and reported by name in `RIDERS BY LANE` below, but
//     they are not held to the checks: widening the rule to a mode no evidence
//     covers would be a guess wearing a gate's clothes.
//   * A NEW RIDER THAT NEITHER NAMES THE ID NOR REACHES `runner.js`. A program
//     that computes its build id from fragments, or shells out to shadow-cljs
//     by some path with `runner.js` nowhere in it, is invisible here. The
//     `every clear is accounted for` check below catches the half of that class
//     that does the right thing and clears; the half that does not is the
//     residual hole, and closing it needs a build-graph checker rather than a
//     source scan. That trade is deliberate.
//
// ## The fail-opens this gate shipped with, and what closed them
//
// Merged-PR audits found the first version did not enforce its own central
// claim. Every hole below was REPRODUCED against the unmodified gate before
// anything changed, each by a synthetic rider dropped into discovery's path:
//
//   * DIFFERENT IDS. A rider calling `resetLaneBuildCache(IMPL, CLEAR_BUILD)`
//     and building `RELEASE_BUILD`, with the two constants naming different
//     builds, passed every check — it empties a cache nobody builds into and
//     builds into a cache nobody cleared, which is the defect itself. The old
//     check only rejected a string LITERAL sitting in the argv slot; it never
//     read either identifier back.
//   * A COMMENT. The clear search was a text search, so `resetLaneBuildCache(`
//     appearing in a comment satisfied it. That is the exact shape left behind
//     when a refactor deletes the call and keeps the explanation.
//   * A STRING. Blanking comments left string BODIES standing, so the same
//     text in a log line or an error message stood in for the call exactly as
//     a comment had: a rider whose only occurrences were
//     `const POLICY = 'resetLaneBuildCache(IMPL, BUILD)'` and a template
//     repeating it passed all four checks, build-id comparison included, with
//     nothing executable in the file. Comment text and string text are one
//     fault wearing different delimiters, and closing either alone closes half
//     a door.
//
// So the gate reads the build id out of BOTH sites and requires them to be the
// same name, and each scan runs over the projection that can answer it:
// discovery and the spawn-side reads keep string text, because the argv slot
// and the `require` path ARE string literals in a correct driver; the clear
// call and its build-id argument are read from executable text alone.
//
// TWO PROJECTIONS, NOT A JS PARSER, deliberately. `project` models line
// comments, block comments and the three string forms; it does not model regex
// literals or nested template interpolation. The failure direction is what
// makes that acceptable: a mis-scan blanks MORE than it should, and blanked
// text can only take away a call the checks are hunting for, so the assertion
// FIRES. It also refuses outright on an unterminated string or block comment
// rather than returning a plausible-looking result — and because discovery now
// runs over a whole tree rather than one directory, that refusal is REPORTED
// AGAINST THE FILE and fails the run, never skipped past. A candidate this
// scanner cannot read is a candidate it has not checked.
//
// WHAT THE SPLIT DOES NOT COVER, said out loud because an edge that is known
// is an edge somebody can price. The spawn-side reads and the `require` path
// have to read string text, since in a correct driver both ARE string
// literals, so a string that happens to spell `'release', BUILD` is still
// visible to them: a rider that clears one id, releases another, and carries
// such a string ahead of its real spawn would be compared against the string.
// That is camouflage, not the accident this gate exists to catch — the riders
// that arrived armed did so by omission — and pricing it out needs the scanner
// to hand back string TOKENS rather than blanked spans. Left undone on
// purpose: what this gate needs is lexical discrimination, not a parser.
//
// FIXTURES. `lane_cache_fixtures/` holds six drivers with known verdicts — for
// each of the two rider shapes a correctly wired control and a mismatched-id
// case, plus one whose clear is only a comment and one whose clear is only
// string text — so the gate has a regression net of its own and cannot quietly
// stop firing. They ride a real shared id and are rider-shaped in every
// respect; discovery skips them ON PATH, and that skip is asserted rather than
// assumed. They are read as TEXT and never executed.
//
// The fixtures stay IN the ESLint path rather than being ignored out of it, and
// are held to the same rules as real driver source, because being faithful
// driver source is the whole of their value — an ignore would let them drift
// into shapes no driver could take. That constrains the two clear-less fixtures
// in a way worth knowing: each requires `lane_cache.cjs` and binds nothing,
// because a deleted clear that LEAVES its import binding behind is already
// caught one gate earlier by `no-unused-vars`. The variant that binds nothing is
// the one no linter can see, so it is the one this gate has to own.
//
// Wired into implementation/package.json via `test:script-helpers`.

const assert = require('node:assert');
const fs = require('node:fs');
const path = require('node:path');
const test = require('node:test');

// THE ANCHOR IS FOUND BY CONTENT, NOT BY COUNTING `..` (rf2-d19nf). Walking up
// to whichever directory holds `shadow-cljs.edn` and `package.json` means this
// file can be moved without silently rescoping what it scans — which is the
// whole mistake being corrected. A depth-counted `path.resolve(__dirname, '..',
// '..', …)` reintroduces it in a quieter form.
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
// THE BENCH LANE LEFT THE PACKAGE (rf2-6c12m.1) and took every rider of
// `hicasso-bench` with it, so the riders this gate exists to hold now live
// under the bench project — its own `shadow-cljs.edn` and `package.json`,
// one level up from the implementation root. Both trees are scanned, and the
// bench root is asserted found below for the same reason the implementation
// root is: a gate that scanned one tree while its subjects sat in the other
// would be the rescoping rf2-d19nf corrected, in a quieter form.
const BENCH_ROOT = REPO_ROOT === null ? null : path.join(REPO_ROOT, 'bench', 'hicasso');
const SCAN_ROOTS = [IMPL_ROOT, BENCH_ROOT].filter((r) => r !== null && fs.existsSync(r));
const FIXTURE_DIR = path.join(__dirname, 'lane_cache_fixtures');

// THE DECLARED LIST, and the only thing here that is not discovered. An id
// belongs on it when MORE THAN ONE program builds it while merging its own arm
// on top — that is what makes the cache entry shared and the clear obligatory.
// An id built by exactly one program is not shared and needs no rule.
//
// Adding a lane: add its id. Deleting a lane: delete its id, and the
// `really is shared` check below makes forgetting to loud.
const SHARED_BUILD_IDS = ['hicasso-bench'];

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

// EVERY PATTERN BELOW IS QUOTE-AGNOSTIC AND TOLERATES WHITESPACE, because the
// nearest sibling gate to this one was defeated by exactly that — a scan keyed
// to single quotes walked past a double-quoted call site, and its "found
// nothing at all" fallback was keyed the same way, so two checks agreed
// because both were blind.
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

// THE MATCH IS QUOTE-DELIMITED, never a bare substring. `freehand-release` is a
// PREFIX of four other real build ids in `implementation/shadow-cljs.edn` —
// `-interpreted`, `-compiled`, `-control`, `-reachability-control` — so a
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
// cannot read is a candidate it has not checked, and the old one-directory
// version never had to make that distinction.
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
// Reported, not checked — see "What this gate does NOT cover" above.
const COMPILE_ONLY = BUILDERS.filter((c) => shapeOf(c) === null);

const CHECKS = {
  requires: {
    title: 'requires lane_cache.cjs',
    // Over `code`, string bodies and all: a genuine require's path is itself a
    // string literal, so this one cannot be read from executable text.
    //
    // THE PATH IS DELIBERATELY UNPINNED (rf2-it4y5). `lane_cache.cjs` sits in
    // the shared bench-helper directory, which the rider trees reach at
    // different relative depths — and one rider composes the path with
    // `path.join` rather than writing it whole, which a pinned prefix would
    // read as a violation. What this check owns is THAT a rider imports the
    // cache rule; WHERE the rule sits, and how the path is spelled, is not its
    // business.
    //
    // AND IT IS NOT COSMETIC. Two riders were hand-rolling the clear as an
    // inline `fs.rmSync` (rf2-d19nf) — correct id, right moment, but without
    // the Windows retry loop `resetLaneBuildCache` carries, so an antivirus
    // scanner or a just-exited JVM holding a handle turns the clear into a
    // throw. One rule, one implementation, is the point.
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
      // The central claim of this gate, and what it did not check until
      // rf2-t4j7c: clearing one id and building another is the defect, not a
      // fix for it. Silent when there is no clear at all — `clearsFirst` owns
      // that fault and reports it better, and a clear that exists only as
      // comment or string text is no clear at all.
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
// the exact fail-open this lane keeps re-learning: a scan whose pattern drifted
// reports zero riders and every assertion below vacuously passes.

test('the implementation root was found (a gate that scanned nothing is not a pass)', () => {
  assert.ok(
    IMPL_ROOT !== null,
    'walked up from this file and never found a directory holding both ' +
      'shadow-cljs.edn and package.json, so discovery had no tree to scan. ' +
      'Repair the anchor — do not fall back to a relative depth.'
  );
});

test('the bench project root was found (the riders live there, rf2-6c12m.1)', () => {
  assert.ok(
    BENCH_ROOT !== null &&
      fs.existsSync(path.join(BENCH_ROOT, 'shadow-cljs.edn')) &&
      fs.existsSync(path.join(BENCH_ROOT, 'package.json')),
    'bench/hicasso/ beside implementation/ does not hold a shadow-cljs.edn and ' +
      'package.json, so the tree carrying every `hicasso-bench` rider was not ' +
      'scanned. Repair BENCH_ROOT — do not let discovery fall back to one tree.'
  );
});

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
  for (const check of Object.values(CHECKS)) {
    test(`${rider.file} ${check.title}`, () => {
      const failure = check.run(rider);
      assert.ok(failure === null, failure);
    });
  }
}

// ## The gate's own regression net
//
// Each fixture is rider-shaped and carries the one check it must trip — or
// null, for the correctly-wired controls. A gate that rejects valid wiring is
// worse than one that admits invalid wiring, because everyone routes around
// it, so the controls are not optional.
//
// Two of them trip `clearsFirst` for what looks like the same reason and is
// not: one hides the call in a comment, the other in a string, and the
// projections that catch them are different. Deleting either as a duplicate
// re-opens the hole the other never covered. The `door_` pair is the same
// argument one level up — the door reads its build id from a `buildId:`
// property rather than an argv slot, so the comparison that covers the direct
// riders does not cover it until this fixture says so.
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
  test(`fixture ${file} is rider-shaped and outside discovery`, () => {
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
  });

  test(`fixture ${file} trips exactly ${expected === null ? 'nothing' : expected}`, () => {
    const fixture = read(path.join(FIXTURE_DIR, file));
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

// The roster this run actually checked, printed so a reader of CI output can
// see the scope rather than infer it from a count. A lane that vanishes from
// this list without its id leaving SHARED_BUILD_IDS fails the `really is
// shared` check above; a lane that vanishes WITH it is a deliberate deletion.
test('RIDERS BY LANE', () => {
  for (const id of SHARED_BUILD_IDS) {
    const riders = RIDERS.filter((r) => lanesOf(r).includes(id));
    console.log(`  ${id} — ${riders.length} release rider(s):`);
    for (const r of riders) console.log(`      ${shapeOf(r).padEnd(6)} ${r.file}`);
    const compile = COMPILE_ONLY.filter((r) => lanesOf(r).includes(id));
    for (const r of compile) console.log(`      (compile-mode, not checked) ${r.file}`);
  }
});
