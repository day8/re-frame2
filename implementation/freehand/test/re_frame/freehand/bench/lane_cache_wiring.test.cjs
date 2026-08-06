#!/usr/bin/env node
'use strict';
// EVERY RIDER OF A SHARED BUILD ID CLEARS ITS CACHE — rf2-t4j7c.
//
//     node freehand/test/re_frame/freehand/bench/lane_cache_wiring.test.cjs
//
// THE DEFECT THIS PINS. Seven drivers in this directory compile by merging
// their OWN `:init-fn` and `:output-dir` onto the ONE `freehand-release`
// build id. shadow-cljs derives the build cache directory from the build id
// ALONE — `<cache-root>/builds/<build-id>/<mode>`, fixed before any
// `--config-merge` data is applied — so the arm is invisible to the cache key
// and seven different programs shared one cache entry. None of the seven
// cleared it. `lane_cache.cjs` carries the fault class, the isolation that
// found the carrier (`shadow-js/index.json.transit`) and the alternatives that
// were rejected with reasons (rf2-2rtt6.20).
//
// MEASURED, on unmodified `main`, at the commit this test first landed against:
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
// WHY A GATE AND NOT JUST THE FIX. The three drivers a bead named "predate
// [the rule] or were never brought onto it" — the invariant was written down
// in `lane_cache.cjs` and enforced nowhere, so drivers kept landing armed and
// four more had accumulated by the time anyone counted. This file DISCOVERS
// the riders rather than listing them, so the eighth fails here instead of in
// a published table.
//
// ## The fail-opens this gate shipped with, and what closed them
//
// Merged-PR audits found this file did not enforce its own central claim.
// Every hole below was REPRODUCED against the unmodified gate before anything
// changed, each by a synthetic rider dropped into this directory:
//
//   * DIFFERENT IDS. A rider calling `resetLaneBuildCache(IMPL, CLEAR_BUILD)`
//     and spawning `[runner, 'release', RELEASE_BUILD, …]`, with the two
//     constants naming different builds, passed every check — it empties a
//     cache nobody builds into and builds into a cache nobody cleared, which
//     is the defect itself. The old check only rejected a string LITERAL
//     sitting in the argv slot; it never read either identifier back.
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
// So the gate now reads the build id out of BOTH sites and requires them to be
// the same name, and each scan runs over the projection that can answer it:
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
// rather than returning a plausible-looking result. That is a direction and
// not a proof — a file adversarial enough to leave a real string outside a
// mis-detected span could still hide in one — and the answer if that ever
// appears is a sharper scanner here, never a check deleted.
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
// FIXTURES. `lane_cache_fixtures/` holds four drivers with known verdicts —
// one correctly wired, one naming two different ids, one whose clear is only a
// comment, one whose clear is only string text — so the gate has a regression
// net of its own and cannot quietly stop firing. They live in a subdirectory
// because discovery reads THIS directory only, and they are read as TEXT and
// never executed; the `require` path in them is part of the source shape under
// test, not a live path.
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

const DIR = __dirname;
const FIXTURE_DIR = path.join(DIR, 'lane_cache_fixtures');

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

function read(dir, file) {
  const src = fs.readFileSync(path.join(dir, file), 'utf8');
  return { file, ...project(src, file) };
}

// A rider is any file here that spawns a shadow-cljs release build with its
// own `--config-merge`. Discovered, never listed: a new driver is caught by
// existing, not by someone remembering to add it.
//
// EVERY PATTERN BELOW IS QUOTE-AGNOSTIC AND TOLERATES WHITESPACE, because the
// nearest sibling gate to this one was defeated by exactly that — a scan keyed
// to single quotes walked past a double-quoted call site, and its "found
// nothing at all" fallback was keyed the same way, so two checks agreed
// because both were blind. The two predicates here are therefore INDEPENDENT
// and are required to agree; disagreement is a failure, not a silence.
const SOURCES = fs.readdirSync(DIR)
  .filter((f) => f.endsWith('.cjs') && !f.endsWith('.test.cjs') && f !== 'lane_cache.cjs')
  .map((f) => read(DIR, f));

// Signal 1: it hands `--config-merge` to shadow-cljs's own CLI runner.
const byRunner = SOURCES.filter(
  ({ code }) => /runner\.js/.test(code) && /['"]--config-merge['"]/.test(code)
);
// Signal 2: it passes `release` as a spawn argument.
const bySpawn = SOURCES.filter(
  ({ code }) => /['"]release['"]\s*,/.test(code) && /['"]--config-merge['"]/.test(code)
);

const RIDERS = byRunner;

const IS_RIDER = ({ code }) =>
  /runner\.js/.test(code) && /['"]--config-merge['"]/.test(code) && /['"]release['"]\s*,/.test(code);

// The build id as each site NAMES it. Identifiers only, on purpose: a literal
// in either slot is a separate, separately-reported fault.
const CLEAR_CALL = /resetLaneBuildCache\s*\(/;
const CLEAR_BUILD_ID = /resetLaneBuildCache\s*\(\s*[\w$.]+\s*,\s*([A-Za-z_$][\w$]*)\s*\)/;
const SPAWN_SLOT = /['"]release['"]\s*,/;
const SPAWN_BUILD_ID = /['"]release['"]\s*,\s*([A-Za-z_$][\w$]*)/;

// Each check answers with a failure message or null, so a fixture can assert
// WHICH one fires rather than merely that something did.
const CHECKS = {
  requires: {
    title: 'requires lane_cache.cjs',
    // Over `code`, string bodies and all: a genuine require's path is itself a
    // string literal, so this one cannot be read from executable text.
    run: ({ file, code }) =>
      /require\(\s*['"]\.\/lane_cache\.cjs['"]\s*\)/.test(code)
        ? null
        : `${file} spawns a shared-build-id release and must require ./lane_cache.cjs`,
  },

  clearsFirst: {
    title: 'calls resetLaneBuildCache BEFORE it spawns the build',
    run: ({ file, code, exec }) => {
      // The call comes from EXECUTABLE text, so neither a comment nor a string
      // can stand in for it; the argv slot comes from `code`, because it is a
      // string literal. Comparing the two offsets is sound because the
      // projections are the same length as the source and as each other.
      const clear = exec.search(CLEAR_CALL);
      const spawn = code.search(SPAWN_SLOT);
      if (clear === -1) return `${file} never calls resetLaneBuildCache (the name in a comment or a string is not a call)`;
      if (spawn === -1) return `${file} has no release spawn to guard`;
      return clear < spawn
        ? null
        : `${file} clears the cache AFTER spawning the build, which clears nothing`;
    },
  },

  noLiteralId: {
    title: 'names the build id once, not as a literal in the spawn argv',
    run: ({ file, code }) =>
      /['"]release['"]\s*,\s*['"]/.test(code)
        ? `${file} passes a literal build id to spawnSync; hoist it to a const and ` +
          'pass that same const to resetLaneBuildCache'
        : null,
  },

  sameBuildId: {
    title: 'clears the SAME build id it releases',
    run: ({ file, code, exec }) => {
      // The central claim of this gate, and what it did not check until
      // rf2-t4j7c: clearing one id and building another is the defect, not a
      // fix for it. Silent when there is no clear at all — `clearsFirst` owns
      // that fault and reports it better, and a clear that exists only as
      // comment or string text is no clear at all.
      if (!CLEAR_CALL.test(exec)) return null;
      const cleared = exec.match(CLEAR_BUILD_ID);
      const released = code.match(SPAWN_BUILD_ID);
      if (!cleared) {
        return `${file} calls resetLaneBuildCache but its build-id argument is not a plain ` +
          'identifier, so it cannot be compared with the one it releases; pass the same const';
      }
      if (!released) {
        return `${file} does not name the build id with an identifier in the spawn argv, so ` +
          'it cannot be compared with the one it clears';
      }
      return cleared[1] === released[1]
        ? null
        : `${file} clears \`${cleared[1]}\` but releases \`${released[1]}\` — one build id, ` +
          'two names: it empties a cache nobody builds into and builds into a cache nobody ' +
          'cleared. Pass one const to both.';
    },
  },
};

test('the two independent rider scans agree (neither pattern has drifted)', () => {
  const a = byRunner.map((r) => r.file).sort();
  const b = bySpawn.map((r) => r.file).sort();
  assert.deepStrictEqual(
    a, b,
    'the runner-path scan and the release-argv scan disagree, so one of them is ' +
      'stale. Repair the pattern — do not delete the check.'
  );
});

test('the rider set is non-empty (a discovery that finds nothing is not a pass)', () => {
  // The exact fail-open this lane keeps finding: a scan whose pattern drifted
  // reports zero riders and every assertion below vacuously passes.
  assert.ok(
    RIDERS.length >= 7,
    `expected at least the 7 known freehand-release riders, found ${RIDERS.length}: ` +
      RIDERS.map((r) => r.file).join(', ')
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
// null, for the correctly-wired control. A gate that rejects valid wiring is
// worse than one that admits invalid wiring, because everyone routes around
// it, so the control is not optional.
// Two of them trip `clearsFirst` for what looks like the same reason and is
// not: one hides the call in a comment, the other in a string, and the
// projections that catch them are different. Deleting either as a duplicate
// re-opens the hole the other never covered.
const FIXTURES = {
  'agreeing_ids_run.cjs': null,
  'mismatched_ids_run.cjs': 'sameBuildId',
  'commented_clear_run.cjs': 'clearsFirst',
  'string_clear_run.cjs': 'clearsFirst',
};

for (const [file, expected] of Object.entries(FIXTURES)) {
  test(`fixture ${file} is rider-shaped and outside discovery`, () => {
    const fixture = read(FIXTURE_DIR, file);
    assert.ok(IS_RIDER(fixture), `${file} is not rider-shaped, so its verdict proves nothing`);
    assert.ok(
      !RIDERS.some((r) => r.file === file),
      `${file} was discovered as a real rider; fixtures must stay in ${path.basename(FIXTURE_DIR)}`
    );
  });

  test(`fixture ${file} trips exactly ${expected === null ? 'nothing' : expected}`, () => {
    const fixture = read(FIXTURE_DIR, file);
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
