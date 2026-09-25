#!/usr/bin/env node

'use strict';

/*
 * Source-policy gate: NO NAVIGATION INHERITS AN ANONYMOUS CEILING.
 *
 * The defect
 * ----------
 * `page.goto(url)` and `page.reload()` apply Playwright's default 30s timeout
 * whenever no `timeout:` is passed. That default is a SECOND budget on every
 * lane that navigates: invisible in the source, unreachable from the runner's
 * own knob, and — the expensive part — indistinguishable in a CI log from the
 * budget the runner does own. "Timeout 30000ms exceeded" reads like the lane's
 * own timeout, so the fix reached for is a bigger lane timeout, which cannot
 * move it.
 *
 * Against the exact document shape these runners serve — a page whose only
 * subresource is held 35s, so `load` cannot fire — a bare navigation dies at
 * 30s while a 90s lane budget sits unused, whereas
 * `{ waitUntil: 'commit', timeout: 90000 }` plus the lane's own poll passes at
 * 35s, and so does `{ waitUntil: 'load', timeout: 45000 }`. The lane budget
 * cannot move the bare navigation's 30s.
 *
 * Why a SWEEP and not per-file assertions
 * ---------------------------------------
 * The defect is a CLASS, not a site: any file that navigates can carry it, and
 * a site patched in isolation leaves its siblings carrying it. A class needs a
 * sweep: naming today's files would leave tomorrow's navigation free to
 * reintroduce it.
 *
 * Why it needs a STATIC pin
 * -------------------------
 * The ceiling is DORMANT in a healthy tree — navigation normally completes in
 * milliseconds — so no amount of ordinary CI would notice a new bare
 * navigation until a loaded runner turned it into a flake whose log line names
 * the wrong budget. Only reading the source finds it before then.
 *
 * WHAT THIS GATE DOES NOT DECIDE
 * ------------------------------
 * It does not require `waitUntil: 'commit'`. Whether a site should wait for
 * `load` is a per-site judgement — a static export, or a re-navigation followed
 * by short locator budgets, genuinely wants a loaded document, and forcing
 * `'commit'` there would push bundle boot onto those budgets and make the lane
 * FLAKIER (`check-story-static.cjs` is one such `'load'`-keeping site). The
 * rule is narrower and entirely
 * mechanical: whatever event you wait for, NAME THE NUMBER.
 *
 * Nor does it decide WHERE the number comes from. A site may give the
 * navigation its own literal (the two `docs/` sites)
 * or alias the lane's existing budget (`NAV_TIMEOUT_MS = TIMEOUT_MS` in
 * `run-browser-tests.cjs`). Both are sound. What the second gate below forbids
 * is DESCRIBING the second as the first — see its own header.
 *
 * Discovered by `npm run test:scripts`.
 */

const assert = require('assert/strict');
const fs = require('fs');
const path = require('path');
const { createPolicyTestSuite } = require('./_policy-test-util.cjs');
const {
  walkDir,
  assertWalkComplete,
} = require('../../examples/scripts/walk-tree.cjs');

const REPO_ROOT = path.resolve(__dirname, '..', '..');

const { test, run } = createPolicyTestSuite('navigation-ceiling-policy');

/*
 * Every Playwright API that accepts `{ timeout }` and silently defaults it to
 * 30s. `goBack` / `goForward` / `waitForNavigation` have no call in the repo;
 * they are listed because they are the same defect wearing a different name,
 * and the point of a sweep is to be there first.
 *
 * `waitForSelector` IS THAT ARGUMENT TOO. It is not a navigation, and the
 * file's name says navigation, but the defect this suite exists to remove is
 * not "a navigation with no ceiling" — it is "an anonymous 30s that no lane
 * knob can reach, whose failure line reads like the lane's own timeout so the
 * fix reached for is a bigger lane timeout, which cannot move it". A bare
 * element-wait is that, exactly — a webkit mount after a re-navigation in
 * `fresco/testbed/spec.cjs`, two lines under a `goto` that carries the
 * ceiling correctly, is the shape — so the sweep polices it too.
 *
 * The lookbehind demands a RECEIVER (`page.goto(`, `dev.goto(`), which is what
 * separates a call from the string literal `'.goto('` that a sibling policy
 * suite scans with. `\s*` inside it tolerates a receiver left on the previous
 * line.
 */
const NAV_METHODS = ['goto', 'reload', 'goBack', 'goForward', 'waitForNavigation',
  'waitForSelector'];
const NAV_CALL_RE = new RegExp(
  `(?<=[\\w$\\)\\]]\\s*)\\.(${NAV_METHODS.join('|')})\\s*\\(`,
  'g',
);

// Directories a source sweep must never descend into: dependency trees,
// compiler output, published/site output, and the local-only `ai/` tree. This
// is a POLICY prune (walk-tree records it as neither visited nor an error) —
// distinct from an unreadable directory, which fails the walk CLOSED below.
const PRUNED_DIRS = new Set([
  '.git', 'node_modules', '.shadow-cljs', '.cpcache', '.clj-kondo',
  'out', 'target', 'dist', 'site', 'coverage', 'test-results',
  'playwright-report', 'ai', '.beads', '.venv', '__pycache__',
]);

/*
 * THERE IS NO WAIVER TABLE, and that is deliberate.
 *
 * A per-path waiver with an upper bound (`max: 1`, chosen so that fixing the
 * site never reds the gate) is an open door: a sweep that compares
 * `bare.length > waiver.max` lets a FIRST regressed bare navigation in a
 * waived file satisfy `1 > 1 === false` and ride through green — exactly the
 * defect the gate exists to catch, in exactly the files most likely to
 * regress. And an empty exemption mechanism is an invitation to add a row.
 *
 * So there is no table and no per-file budget comparison. The paths below once
 * carried the defect; they are held to ZERO by the ordinary sweep above, and
 * named once — not to exempt them, but to prove the sweep still REACHES them.
 * That is the one thing a missing waiver table does not check by itself.
 */
const FORMERLY_WAIVED = [
  // Both wait on `networkidle`, which settles LATER than `load`, so the 30s
  // default bites harder here.
  { file: 'docs/scripts/generate-story-tutorial-screenshots.cjs', bead: 'rf2-rbyyx' },
  { file: 'docs/tools/playground/test/smoke.test.mjs', bead: 'rf2-rbyyx' },
];

// ---------------------------------------------------------------------------

/*
 * The argument list of the call whose `(` is at `open`, by BALANCED-PAREN scan.
 *
 * Deliberately not a fixed-size window. Reading a flat 300 characters after
 * the call and asking whether `timeout:` appears anywhere in them passes a
 * bare `dev.goto(url);` followed three lines later by a
 * `waitForFunction(..., { timeout: TIMEOUT })` that the window swallows — a
 * real defect read green. So this scan stops at the call's own closing paren,
 * and nothing beyond it can vouch for it.
 *
 * String literals are skipped so a `(` or `)` inside a URL cannot unbalance the
 * count. Like `stripComments`, this is a residue scanner over our own
 * first-party scripts, not a JS parser: it does not model `${}` interpolation
 * nesting, which no navigation call in this repo uses.
 */
function callArgs(src, open) {
  let depth = 0;
  for (let i = open; i < src.length; i += 1) {
    const c = src[i];
    if (c === '"' || c === "'" || c === '`') {
      i += 1;
      while (i < src.length && src[i] !== c) {
        if (src[i] === '\\') i += 1;
        i += 1;
      }
      continue;
    }
    if (c === '(') depth += 1;
    else if (c === ')') {
      depth -= 1;
      if (depth === 0) return src.slice(open + 1, i);
    }
  }
  // Unbalanced — return the tail rather than silently dropping a navigation
  // from the sweep. A malformed call should be reported, not excused.
  return src.slice(open + 1);
}

/*
 * Blank out comments so a policy assertion reads EXECUTABLE source only — the
 * doc-comments around these calls necessarily quote the very shape the gate
 * forbids.
 *
 * NOT `_policy-test-util.cjs`'s shared `stripComments`, deliberately. That one
 * tracks string state but not REGEX-LITERAL state, so a regex containing a
 * quote or a backtick desyncs it and every later comment survives as "string
 * content". `_impl-browser-runners-verdict-policy.test.cjs` contains exactly
 * such a regex, and the shared stripper leaves its `page.goto(...)` PROSE
 * standing — which this gate would then report as a defect in a file that has
 * none. (The shared stripper suffices for its own callers, which only ever ask
 * "must not contain".)
 *
 * Line-wise instead, and only WHOLE-line comments: a trailing `//` strip would
 * truncate the very `http://…` URLs these calls navigate to, taking the
 * `timeout:` after them with it. Block comments are blanked in place so line
 * structure survives.
 */
function codeOnly(src) {
  return src
    .replace(/\/\*[\s\S]*?\*\//g, (block) => block.replace(/[^\n]/g, ' '))
    .split('\n')
    .map((line) => (/^\s*(\/\/|\*)/.test(line) ? '' : line))
    .join('\n');
}

// Split a source into its navigations: how many there are, and which of them
// pass no `timeout:` of their own.
function navigationsIn(src) {
  const code = codeOnly(src);
  const all = [];
  const bare = [];
  for (const m of code.matchAll(NAV_CALL_RE)) {
    const open = m.index + m[0].length - 1;
    const args = callArgs(code, open);
    const rendered = `.${m[1]}(${args.replace(/\s+/g, ' ').trim().slice(0, 90)})`;
    all.push(rendered);
    if (!/\btimeout:/.test(args)) bare.push(rendered);
  }
  return { all, bare };
}

// Every first-party `.cjs` / `.mjs` in the repo, FAIL-CLOSED: an unreadable
// directory throws rather than quietly shrinking the scan (a partial sweep is
// indistinguishable from a clean one, which is the failure mode `walk-tree`
// exists to remove).
function scannedFiles() {
  const { items, walkErrors } = walkDir({
    roots: [REPO_ROOT],
    skipDir: (name) => PRUNED_DIRS.has(name),
    acceptFile: (name) => name.endsWith('.cjs') || name.endsWith('.mjs'),
  });
  assertWalkComplete(walkErrors, 'navigation-ceiling sweep');
  return items.sort();
}

// ---------------------------------------------------------------------------

test("every navigation passes an EXPLICIT timeout — none inherits Playwright's 30s default (rf2-dczpv, rf2-bhjzn, rf2-taj9b)", () => {
  const files = scannedFiles();
  assert.ok(
    files.length > 100,
    `the sweep must actually find scripts to read (found ${files.length})`,
  );

  const offenders = [];
  let navigations = 0;

  for (const file of files) {
    const { all, bare } = navigationsIn(fs.readFileSync(file, 'utf8'));
    if (all.length === 0) continue;
    navigations += all.length;
    const rel = path.relative(REPO_ROOT, file).replace(/\\/g, '/');
    for (const call of bare) offenders.push(`${rel}: ${call}`);
  }

  assert.ok(navigations > 0, 'the sweep must actually find navigations to check');
  assert.deepEqual(
    offenders,
    [],
    'A navigation must pass its OWN `timeout:`, tied to the budget that ' +
      'bounds it. Without one Playwright applies a 30s default that no lane ' +
      'budget can reach and whose CI failure line reads like the lane timeout ' +
      'it is not — so the fix reached for is a bigger lane budget, which ' +
      'moves nothing. Spec-side callers can use `navigate` / `reloadPage` ' +
      'from examples/scripts/spec-helpers.cjs, which require the number. ' +
      'There is no waiver table: fix the call.',
  );
});

/*
 * Gate 2: AN ALIASED CEILING MAY NOT BE DESCRIBED AS AN INDEPENDENT ONE.
 * ---------------------------------------------------------------------
 * The first gate makes every navigation name its number. A runner may name it
 * by ALIASING the budget it already has — `NAV_TIMEOUT_MS = TIMEOUT_MS` in
 * `run-browser-tests.cjs` — but must not then copy its failure wording from
 * the sites that pick a SEPARATE literal, where "raising the lane budget
 * cannot move this" is simply true. On an alias it is false: one configured
 * value bounds both phases, so raising it moves both.
 *
 * That is not a nitpick about prose. The whole point of naming the ceiling is
 * to stop a CI log sending the reader to the wrong knob, and a message that
 * disclaims the knob which DOES control the failed phase sends them nowhere at
 * all — the same defect wearing the opposite hat.
 *
 * The rule is mechanical and reads EXECUTABLE source only, so doc-comments
 * that describe the defect stay legal:
 *
 *   a file whose navigation ceiling is `= SOME_OTHER_CONSTANT;`
 *     - must NOT disclaim that constant ("raising X will not help", "a bigger
 *       X, which cannot touch it"), and
 *     - MUST carry the shared marker below, so every aliasing runner says it
 *       identically and the next one cannot invent another phrasing.
 *
 * A site that picks its own literal is untouched by this gate: its disclaimer
 * is true, and it should keep it.
 */

// The one sentence, stated here so the runners quote it rather than each
// paraphrasing. It is the model, not decoration: one knob, two phases, in that
// order.
const SHARED_BUDGET_MARKER = 'ONE configured value applied to TWO sequential phases';

// `const NAV…TIMEOUT… = OTHER_IDENT;` — an alias. A numeric literal, an
// arithmetic expression or a `parseInt(...)` is an independent budget and does
// not match, which is exactly the distinction the gate turns on.
const ALIAS_DECL_RE =
  /\bconst\s+([A-Z][A-Z0-9_]*)\s*=\s*([A-Za-z_$][\w$]*)\s*;/g;
const NAV_CONST_RE = /NAV/;
const TIMEOUT_CONST_RE = /TIMEOUT/;

/*
 * A claim that some named budget is powerless over this failure. Two subject
 * cues, the wordings such a disclaimer takes: "raising X …" and "a
 * bigger X …". The subject must be a SCREAMING_SNAKE constant or a `${…}`
 * interpolation of one — `raising it` and `raising the bench budget` are prose
 * about something else and are none of this gate's business.
 */
const DISCLAIMER_RE = new RegExp(
  String.raw`\b(?:[Rr]aising|[Aa] bigger)\s+(?:\$\{\s*([A-Za-z_$][\w$]*)\s*\}|\$?([A-Z][A-Z0-9_]{2,}))` +
    String.raw`[\s\S]{0,200}?([Ww]ill not help|cannot (?:move|touch|help)|does nothing)`,
  'g',
);

/*
 * Rejoin a failure message that the source breaks across concatenated
 * literals. `'… TWO sequential ' + 'phases: …'` is ONE sentence to the
 * operator reading the CI log and two string literals to a grep, and a gate
 * that searched the raw source would be asserting about where the author
 * happened to wrap the line. Seams are dropped and runs of whitespace
 * collapsed, so the scan below reads the text as printed.
 */
function joinedText(code) {
  return code.replace(/(['"`])\s*\+\s*(['"`])/g, '').replace(/\s+/g, ' ');
}

// Read one `const NAME = …;` initializer out of a source, or null.
function initializerOf(code, name) {
  const m = new RegExp(String.raw`\bconst\s+` + name + String.raw`\s*=([^;]*);`).exec(code);
  return m ? m[1] : null;
}

/*
 * The identifiers a file's navigation ceiling is made of, so a message that
 * disclaims `${BROWSER_TEST_TIMEOUT_ENV_VAR}` is caught alongside one that
 * disclaims `TIMEOUT_MS` directly. ONE hop through the alias source's own
 * initializer, plus the string a name-holding constant resolves to — that is
 * every shape these runners use, and stopping there keeps this a scanner
 * rather than a half-built resolver.
 */
function budgetTokens(code, aliasSource) {
  const tokens = new Set([aliasSource]);
  const init = initializerOf(code, aliasSource);
  if (!init) return tokens;
  for (const [ident] of init.matchAll(/\b[A-Z][A-Z0-9_]{2,}\b/g)) {
    tokens.add(ident);
    // `const BROWSER_TEST_TIMEOUT_ENV_VAR = 'BROWSER_TEST_TIMEOUT_MS';` — the
    // message interpolates the constant, the reader sees the env var name.
    // Both are the same knob, so both are the file's budget.
    const holder = initializerOf(code, ident);
    const literal = holder && /^\s*'([A-Z][A-Z0-9_]*)'\s*$/.exec(holder);
    if (literal) tokens.add(literal[1]);
  }
  return tokens;
}

test('a runner that ALIASES its navigation ceiling says so, and never disclaims the knob that moves it (rf2-dczpv, rf2-bhjzn)', () => {
  const aliasing = [];
  const untruthful = [];
  const unmarked = [];

  for (const file of scannedFiles()) {
    const code = codeOnly(fs.readFileSync(file, 'utf8'));
    const rel = path.relative(REPO_ROOT, file).replace(/\\/g, '/');
    let aliasSource = null;
    for (const m of code.matchAll(ALIAS_DECL_RE)) {
      if (NAV_CONST_RE.test(m[1]) && TIMEOUT_CONST_RE.test(m[1])) aliasSource = m[2];
    }
    if (!aliasSource) continue;
    aliasing.push(rel);

    const text = joinedText(code);
    const tokens = budgetTokens(code, aliasSource);
    for (const m of text.matchAll(DISCLAIMER_RE)) {
      const subject = m[1] || m[2];
      if (tokens.has(subject)) untruthful.push(`${rel}: "${m[0].trim()}"`);
    }
    if (!text.includes(SHARED_BUDGET_MARKER)) unmarked.push(rel);
  }

  // The gate must have something to be true ABOUT: if the aliasing runners are
  // ever renamed away, this assertion fails rather than passing vacuously over
  // an empty set.
  assert.ok(
    aliasing.length >= 1,
    `expected the aliasing runners to still alias (found ${aliasing.length}: ` +
      `${aliasing.join(', ')})`,
  );
  assert.deepEqual(
    untruthful,
    [],
    'A runner whose navigation ceiling IS the lane budget may not tell the ' +
      'reader that raising the lane budget cannot move it — one configured ' +
      'value bounds both phases, so raising it moves both. Name the phase that ' +
      'fired, then say what the knob does and what it cannot cure (a page that ' +
      'never answered fails at the navigation at any size).',
  );
  assert.deepEqual(
    unmarked,
    [],
    `an aliasing runner must state the model verbatim — "${SHARED_BUDGET_MARKER}" ` +
      '— so all of them say it identically and the next one cannot invent ' +
      'another phrasing of the same relationship',
  );
});

/*
 * A REACHABILITY check for the formerly-waived paths.
 *
 * The sweep above holds these paths to zero like everything else — but only if
 * it READS them, and a path that leaves the walk stops being read in silence. A
 * rename, a move out of the scanned extensions, a new entry in `PRUNED_DIRS`:
 * each turns "this file has no bare navigation" into "this file is not
 * examined", and the two are indistinguishable in a green run.
 *
 * So this asserts REACHABILITY, not exemption: the paths that carried the
 * defect are inside the sweep's own file list, and clean. Zero, by name.
 */
test('the six formerly-waived paths are still inside the sweep, and still at zero (rf2-p9fa3, rf2-rbyyx)', () => {
  const scanned = new Set(scannedFiles());
  const problems = [];

  for (const { file, bead } of FORMERLY_WAIVED) {
    const abs = path.resolve(REPO_ROOT, file);
    if (!fs.existsSync(abs)) {
      problems.push(
        `${file} (${bead}): no such file — if it moved, repoint this row; the ` +
          'sweep can only hold a path it can find',
      );
      continue;
    }
    if (!scanned.has(abs)) {
      problems.push(
        `${file} (${bead}): exists but the sweep does not read it — check the ` +
          'extension filter and PRUNED_DIRS. An unread file passes silently',
      );
      continue;
    }
    const { bare } = navigationsIn(fs.readFileSync(abs, 'utf8'));
    for (const call of bare) {
      problems.push(
        `${file} (${bead}): regained a bare navigation — ${call}. This path ` +
          'once carried the defect and is held at ZERO; there is no waiver to ' +
          'raise',
      );
    }
  }

  assert.deepEqual(
    problems,
    [],
    'the formerly-waived paths are held at zero and ' +
      'must stay inside the sweep that holds them',
  );
});

// The static sweep sees the navigation inside `spec-helpers.cjs` and stops
// there; it cannot follow a CALLER of `navigate` to check that it supplied a
// number. The helpers close that gap at runtime instead — and REFUSING beats
// defaulting, because a default would be the very ceiling this gate exists to
// remove, merely relocated into a shared file.
//
// Probed before `run()` because the policy-suite harness is synchronous: the
// result is computed here and asserted by the registered test below.
let helperContractFailures = null;

test('the shared spec helpers REFUSE a navigation with no timeout (rf2-taj9b)', () => {
  assert.notEqual(helperContractFailures, null, 'the helper probe did not run');
  assert.deepEqual(
    helperContractFailures,
    [],
    'navigate / reloadPage must reject a missing or non-numeric timeoutMs ' +
      'before touching the page — a defaulted one is the anonymous ceiling ' +
      'again, just written in our own source',
  );
});

async function probeHelperContract() {
  const { navigate, reloadPage } = require('../../examples/scripts/spec-helpers.cjs');
  // A page whose navigation methods THROW, so "the helper called through"
  // is distinguishable from "the helper refused".
  const page = {
    goto: () => { throw new Error('navigate must reject BEFORE touching the page'); },
    reload: () => { throw new Error('reloadPage must reject BEFORE touching the page'); },
  };
  const cases = [
    ['navigate, empty options', () => navigate(page, 'http://127.0.0.1/', {})],
    ['navigate, no options', () => navigate(page, 'http://127.0.0.1/')],
    ['navigate, non-numeric', () => navigate(page, 'http://127.0.0.1/', { timeoutMs: '30000' })],
    ['navigate, zero', () => navigate(page, 'http://127.0.0.1/', { timeoutMs: 0 })],
    ['reloadPage, empty options', () => reloadPage(page, {})],
    ['reloadPage, no options', () => reloadPage(page)],
  ];
  const failures = [];
  for (const [name, call] of cases) {
    try {
      await call();
      failures.push(`${name}: resolved instead of refusing`);
    } catch (err) {
      if (!/timeoutMs is REQUIRED/.test(err.message)) {
        failures.push(`${name}: rejected for the wrong reason — ${err.message}`);
      }
    }
  }
  return failures;
}

probeHelperContract()
  .then((failures) => { helperContractFailures = failures; })
  .catch((err) => { helperContractFailures = [`probe threw: ${err.message}`]; })
  .then(run);
