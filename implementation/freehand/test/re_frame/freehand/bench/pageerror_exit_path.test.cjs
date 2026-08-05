#!/usr/bin/env node
'use strict';
// THE BENCH LANE'S PAGE-ERROR EXIT PATH — a page that threw cannot exit 0.
// rf2-sib23, from rf2-jvheq's audit (#7494).
//
//     node freehand/test/re_frame/freehand/bench/pageerror_exit_path.test.cjs
//
// THE DEFECT THIS PINS. Nine drivers installed a bare handler:
//
//     page.on('pageerror', (e) => console.error(`[b8] page error: ${e.message}`))
//
// with no array and no reference from the exit block. The throw was printed
// on stderr and the run exited 0 underneath it, publishing a precise number
// for a page that is not the page under test.
//
// WHY THE APP CANNOT CLOSE IT, which is why this is a driver-side pin rather
// than a `(catch :default ...)` somewhere in CLJS. React 19.2.0 hands an
// uncaught render error to `reportGlobalError` -> `reportError` instead of
// rethrowing it to the caller of `flushSync`/`render`. Measured live under
// this repo's own pins: `pageerror` fires, THE SURROUNDING CATCH NEVER RUNS,
// no `window.*_ERROR` is set, the driving `page.evaluate` does not reject,
// and the app carries on and sets its completion sentinel. All nine mount
// through `flushSync`. `sentinel.cjs` carries the whole finding.
//
// WHY IT IS PINNED HERE AND NOT END-TO-END. Every one of these drivers needs
// an `:advanced` release build and a headless Chromium, so nine end-to-end
// runs are not a unit test — the same reason `b8_exit_path.test.cjs`,
// `clock_exit_path.test.cjs` and `hicasso_narrow_exit_path.test.cjs` exist.
// The repair therefore put each decision somewhere a test can reach it, and
// this file asks the two questions that separates a wired gate from an armed
// one:
//
//   1. DOES THE REFUSAL FIRE? — the pure `verdict` of every driver that has
//      one, driven directly, in both directions.
//   2. IS IT WIRED? — that the collected failures reach the exit code in all
//      nine, and reach it BEFORE the line that says the run was fine.
//
// ...AND ONE THE PER-FILE PINS CANNOT ASK. The roster below is DERIVED — every
// bench driver that launches Chromium, found by walking the bench trees — not
// copied from a bead. Both beads' rosters disagreed about which drivers were
// affected, and a hand-kept list would leave the twenty-third driver free to
// reintroduce the bare handler tomorrow.
//
// Wired into implementation/package.json via `test:script-helpers`.

const assert = require('node:assert');
const fs = require('node:fs');
const path = require('node:path');

const BENCH = __dirname;
const IMPL = path.resolve(__dirname, '../../../../..');
const CORE_BENCH = path.join(IMPL, 'core/test/re_frame/bench');
const REAGENT_BENCH = path.join(IMPL, 'adapters/reagent/test/re_frame/bench');
const HICASSO_BENCH = path.join(IMPL, 'freehand/test/re_frame/bench/hicasso');

// Requiring a driver must NOT drive it: the `require.main === module` guard is
// itself part of what is under test. `spine_ablation_run.cjs` has no such
// guard — it drives from a top-level IIFE — so it is read and never required.
const b6prod = require('./b6_prod_run.cjs');
const b6profile = require('./b6_profile_run.cjs');
const b10prod = require('./b10_prod_run.cjs');
const b8 = require('./b8_run.cjs');
const hn = require(path.join(REAGENT_BENCH, 'hicasso_narrow_run.cjs'));

const tests = [];
const test = (name, fn) => tests.push([name, fn]);

const src = (p) => fs.readFileSync(p, 'utf8');

/**
 * The file with its comment-only lines blanked out, line numbering preserved.
 *
 * These drivers explain themselves at length, and several now quote the very
 * shape they replaced — `page.on('pageerror', ...)` — inside the note saying
 * it is gone. Reading prose as code would make this file fail on the fix, and
 * make it pass on a driver that describes a refusal it does not perform.
 * Blanking rather than deleting keeps every index comparison below honest.
 */
const code = (s) =>
  s
    .split('\n')
    .map((l) => (/^\s*(\/\/|\*\/?|\/\*)/.test(l) ? '' : l))
    .join('\n');

// ===========================================================================
// 1. DOES THE REFUSAL FIRE? — the five drivers whose decision is a pure
//    function, driven directly.
// ===========================================================================
//
// Each is asked three things, and the FIRST matters as much as the second: a
// gate that refuses everything is not a gate either, and the audit's own
// objection to shipping this unproven was that an always-refusing wiring
// would be "this same fault in reverse".

const PAGE_ERROR = 'pageerror: Cannot read properties of undefined (reading \'call\')';

test('b6_prod: a clean run exits 0 and says nothing', () => {
  assert.deepStrictEqual(b6prod.verdict({ err: null, results: {}, pageErrors: [] }), {
    code: 0,
    lines: [],
  });
  assert.strictEqual(b6prod.verdict(undefined).code, 0);
});

test('b6_prod: a page error alone is a NONZERO exit — the case that used to be green', () => {
  const v = b6prod.verdict({ err: null, results: {}, pageErrors: [PAGE_ERROR] });
  assert.strictEqual(v.code, 1);
  assert.match(v.lines.join('\n'), /threw and kept going/);
  assert.match(v.lines.join('\n'), /Cannot read properties of undefined/);
});

test('b6_prod: `B6_ERROR` still exits 1, exactly as before', () => {
  const v = b6prod.verdict({ err: 'parity gate failed', pageErrors: [] });
  assert.strictEqual(v.code, 1);
  assert.match(v.lines[0], /parity gate failed/);
});

test('b6_prod: a run that fails both is named for both — neither masks the other', () => {
  const v = b6prod.verdict({ err: 'parity gate failed', pageErrors: [PAGE_ERROR] });
  assert.strictEqual(v.code, 1);
  assert.strictEqual(v.lines.length, 2);
});

test('b6_profile: a clean profile exits 0', () => {
  assert.deepStrictEqual(b6profile.verdict({ unverified: 0, of: 400, pageErrors: [] }), {
    code: 0,
    lines: [],
  });
  assert.strictEqual(b6profile.verdict().code, 0);
});

test('b6_profile: a page error alone is a NONZERO exit', () => {
  const v = b6profile.verdict({ unverified: 0, of: 400, pageErrors: [PAGE_ERROR] });
  assert.strictEqual(v.code, 1);
  assert.match(v.lines.join('\n'), /the profile above is of a page that is not the page under test/);
});

test('b6_profile: the unverified-writes gate is unchanged', () => {
  const v = b6profile.verdict({ unverified: 3, of: 400, pageErrors: [] });
  assert.strictEqual(v.code, 1);
  assert.match(v.lines[0], /3 of 400 writes did not reach the DOM/);
});

test('b10_prod: a clean run exits 0', () => {
  assert.deepStrictEqual(b10prod.verdict({ err: null, results: {}, pageErrors: [] }), {
    code: 0,
    lines: [],
  });
  assert.strictEqual(b10prod.verdict(undefined).code, 0);
});

test('b10_prod: a page error alone is a NONZERO exit — the WORST of the nine', () => {
  // Worst because its second path needs no React at all: `b10_two_clock.cljs`
  // drives the run from two `setInterval`s and a live `MutationObserver`, all
  // detached tasks that escape both `-main`'s try and the promise chain's
  // catch — and `setInterval` keeps firing after a throwing callback, so the
  // run completes and sets `B10_DONE` regardless.
  const v = b10prod.verdict({ err: null, results: {}, pageErrors: [PAGE_ERROR] });
  assert.strictEqual(v.code, 1);
  assert.match(v.lines.join('\n'), /interval and MutationObserver tasks/);
});

test('b10_prod: `B10_ERROR` still exits 1, exactly as before', () => {
  assert.strictEqual(b10prod.verdict({ err: 'boom', pageErrors: [] }).code, 1);
});

test('b8: a clean summary exits 0, and an absent one is not a refusal', () => {
  assert.deepStrictEqual(
    b8.verdict({ warmupUnsettled: [], orderRefusals: [], pageErrors: [] }),
    { code: 0, lines: [] }
  );
  assert.strictEqual(b8.verdict(undefined).code, 0);
});

test('b8: a page error alone is a NONZERO exit, and it is 1', () => {
  const v = b8.verdict({ warmupUnsettled: [], orderRefusals: [], pageErrors: [PAGE_ERROR] });
  assert.strictEqual(v.code, 1);
  assert.match(v.lines.join('\n'), /threw and kept going/);
});

test('b8: the arm-order guard still exits 2 and the warm-up ceiling still exits 3', () => {
  assert.strictEqual(b8.verdict({ orderRefusals: ['narrow/reagent'] }).code, 2);
  assert.strictEqual(b8.verdict({ warmupUnsettled: ['narrow/reagent/D=32'] }).code, 3);
});

test('b8: a page error OUTRANKS the two refusals, and every one is still named', () => {
  // Precedence is deliberate: 2 and 3 are judgements about whether a figure
  // may be QUOTED. A page error says the figures are not of the page they
  // name, which is the stronger statement and the driver's existing code 1.
  const v = b8.verdict({
    warmupUnsettled: ['narrow/reagent/D=32'],
    orderRefusals: ['narrow/reagent'],
    pageErrors: [PAGE_ERROR],
  });
  assert.strictEqual(v.code, 1);
  assert.strictEqual(v.lines.length, 3);
});

test('hicasso_narrow: a clean run is reportable and exits 0', () => {
  const v = hn.verdict({ pageErrors: [], warmupUnsettled: [], clamped: [], identityOk: true });
  assert.strictEqual(v.code, 0);
  assert.match(v.lines.join('\n'), /VERDICT: reportable\./);
});

test('hicasso_narrow: a page error alone is a NONZERO exit', () => {
  const v = hn.verdict({
    pageErrors: [PAGE_ERROR],
    warmupUnsettled: [],
    clamped: [],
    identityOk: true,
  });
  assert.strictEqual(v.code, 1);
  assert.match(v.lines.join('\n'), /the page threw and kept going/);
  assert.doesNotMatch(v.lines.join('\n'), /VERDICT: reportable\./);
});

test('hicasso_narrow: every code it already had is unchanged', () => {
  const base = { pageErrors: [], warmupUnsettled: [], clamped: [], identityOk: true };
  assert.strictEqual(hn.verdict({ ...base, positionsLost: true }).code, 1);
  assert.strictEqual(hn.verdict({ ...base, orderRefuse: true }).code, 2);
  assert.strictEqual(hn.verdict({ ...base, warmupUnsettled: ['freehand'] }).code, 3);
  assert.strictEqual(hn.verdict({ ...base, clamped: ['reagent write'] }).code, 4);
});

// ===========================================================================
// 2. IS IT WIRED? — that what the collector recorded reaches the exit code,
//    in all nine, and reaches it before the line that says the run was fine.
// ===========================================================================
//
// The pure-function tests above cannot see this half: a `verdict` that
// refuses correctly on a record nobody fills is the same fail-open wearing a
// green hat. Each driver is therefore also read.

const WIRED = [
  {
    id: 'b6_prod_run.cjs',
    file: path.join(BENCH, 'b6_prod_run.cjs'),
    // The collection, and the one place it is read.
    collects: /const watch = watchPage\(page, 'b6'\)/,
    carries: /const pageErrors = watch\.failures\.map\(/,
    decides: /const v = verdict\(outcome\);/,
    green: /console\.error\('\[b6\] ok'\)/,
  },
  {
    id: 'b6_profile_run.cjs',
    file: path.join(BENCH, 'b6_profile_run.cjs'),
    collects: /const watch = watchPage\(page, 'b6p'\)/,
    carries: /const pageErrors = watch\.failures\.map\(/,
    decides: /pageErrors: out\.pageErrors/,
    green: /console\.error\('\[b6p\] ok'\)/,
  },
  {
    id: 'b10_prod_run.cjs',
    file: path.join(BENCH, 'b10_prod_run.cjs'),
    collects: /const watch = watchPage\(page, 'b10'\)/,
    carries: /const pageErrors = watch\.failures\.map\(/,
    decides: /const v = verdict\(outcome\);/,
    green: /console\.error\('\[b10\] ok'\)/,
  },
  {
    id: 'b7_run.cjs',
    file: path.join(BENCH, 'b7_run.cjs'),
    // The collector is NAMED as well as collected since rf2-qv761, because
    // each row races its own sentinel against this same watch.
    collects: /const watch = watchPage\(page, 'b7'\);\s*PAGE_WATCHES\.push\(watch\);/,
    carries: /const pageErrors = pageFailures\(\);/,
    // b7 has no pure verdict: its exit reads one `failed` slot, so the
    // refusal joins that slot rather than growing a second reading.
    decides: /failed = \[failed, `the page threw and kept going/,
    green: /console\.error\('\[b7\] ok'\)/,
  },
  {
    id: 'b8_run.cjs',
    file: path.join(BENCH, 'b8_run.cjs'),
    collects: /const watch = watchPage\(page, 'b8'\)/,
    carries: /summary\.pageErrors = out\.pageErrors \|\| \[\];/,
    decides: /const pageErrors = \(summary && summary\.pageErrors\) \|\| \[\];/,
    green: /console\.error\('\[b8\] ok'\)/,
  },
  {
    id: 'reads_ladder_run.cjs',
    file: path.join(BENCH, 'reads_ladder_run.cjs'),
    collects: /const watch = watchPage\(page, 'ladder'\);\s*PAGE_WATCHES\.push\(watch\);/,
    carries: /const pageErrors = pageFailures\(\);/,
    decides: /if \(pageErrors\.length\) \{[\s\S]{0,900}?process\.exit\(1\);/,
    green: /console\.error\('\[ladder\] done'\)/,
  },
  {
    id: 'spine_ablation_run.cjs',
    file: path.join(BENCH, 'spine_ablation_run.cjs'),
    collects: /const watch = watchPage\(page, 'abl'\);\s*PAGE_WATCHES\.push\(watch\);/,
    carries: /const pageErrors = pageFailures\(\);/,
    decides: /if \(pageErrors\.length\) \{[\s\S]{0,900}?process\.exit\(1\);/,
    green: /console\.error\('\[abl\] done'\)/,
  },
  {
    id: 'p0_run.cjs',
    file: path.join(CORE_BENCH, 'p0_run.cjs'),
    collects: /const watch = watchPage\(page, 'p0'\);\s*PAGE_WATCHES\.push\(watch\);/,
    carries: /for \(const e of pageFailures\(\)\) \{/,
    // p0 already collected EVERY failed gate into one list rather than one
    // slot, precisely so a later gate's silence could not overwrite an
    // earlier gate's refusal. The page's failures join that list.
    decides: /failures\.push\(\s*`the page threw and kept going/,
    green: /console\.error\('\[p0\] done'\)/,
  },
  {
    id: 'hicasso_narrow_run.cjs',
    file: path.join(REAGENT_BENCH, 'hicasso_narrow_run.cjs'),
    collects: /const watch = watchPage\(page, 'hn'\)/,
    carries: /const pageErrors = watch\.failures\.map\(/,
    decides: /const pageErrors = s\.pageErrors \|\| \[\];/,
    green: /VERDICT: reportable\./,
  },
];

test('all nine drivers are covered by a wiring pin, and the nine are the nine', () => {
  assert.strictEqual(WIRED.length, 9);
  for (const w of WIRED) {
    assert.ok(fs.existsSync(w.file), `${w.id} must exist at ${w.file}`);
  }
});

for (const w of WIRED) {
  test(`${w.id}: the handler no longer merely prints — it collects`, () => {
    const s = code(src(w.file));
    assert.match(s, w.collects, `${w.id} must install the lane's collector`);
    // And the bare handler is GONE. Not relaxed, not duplicated: replaced.
    // (Prose about the old shape is allowed; a live registration is not.)
    assert.doesNotMatch(
      s,
      /page\.on\(\s*['"]pageerror['"]/,
      `${w.id} must not register a second, bare pageerror handler`
    );
  });

  test(`${w.id}: what was collected is carried to the decision`, () => {
    assert.match(code(src(w.file)), w.carries, `${w.id} must read the collector's failures`);
  });

  test(`${w.id}: the decision refuses on it, BEFORE it says the run was fine`, () => {
    const s = code(src(w.file));
    assert.match(s, w.decides, `${w.id}'s exit must consult the page's failures`);
    const decideAt = s.search(w.decides);
    const greenAt = s.search(w.green);
    assert.ok(decideAt > -1 && greenAt > -1, `${w.id}: both sites must exist`);
    assert.ok(
      decideAt < greenAt,
      `${w.id}: the refusal is worthless after the line that announces a clean run`
    );
  });
}

// ===========================================================================
// 3. THE CLASS, DERIVED — no bench driver may print a page error and move on.
// ===========================================================================
//
// The roster is walked rather than listed. rf2-jvheq's mayor note records why:
// both beads' rosters were wrong in opposite directions, one overstating the
// affected family and the other exactly right, and the instruction that
// produced the truth was "derive the roster, do not take it from either bead".
// A hand-kept list would also leave the next driver free to be born fail-open.
//
// The rule is narrow and mechanical, and deliberately does NOT require
// `watchPage`: the drivers in the hicasso tree that collect into a local array
// and refuse are sound, and forcing them onto the shared collector is a fleet-
// wide refactor nobody asked for. What is forbidden is a handler that only
// PRINTS — and, since rf2-sib23's second obligation, one whose array is never
// read, which is the same thing with an extra line. Both halves are checked
// below, so "every fleet member refuses" is derived on both shapes rather than
// derived on one and hand-checked on the other.

function walk(dir) {
  const out = [];
  if (!fs.existsSync(dir)) return out;
  for (const e of fs.readdirSync(dir, { withFileTypes: true })) {
    const p = path.join(dir, e.name);
    if (e.isDirectory()) out.push(...walk(p));
    else if (e.name.endsWith('.cjs') && !e.name.endsWith('.test.cjs')) out.push(p);
  }
  return out;
}

const BENCH_TREES = [BENCH, HICASSO_BENCH, CORE_BENCH, REAGENT_BENCH];

function benchDrivers() {
  const seen = new Set();
  const drivers = [];
  for (const tree of BENCH_TREES) {
    for (const p of walk(tree)) {
      if (seen.has(p)) continue;
      seen.add(p);
      const s = code(src(p));
      // A driver is a file that opens a Chromium of its own. `sentinel.cjs`
      // and `navigate.cjs` take a page they were handed and are not drivers.
      if (/chromium\.launch\(/.test(s)) drivers.push({ file: p, src: s });
    }
  }
  return drivers;
}

test('the derived roster finds the whole bench fleet, not a subset', () => {
  const drivers = benchDrivers();
  // A floor, not an exact count: the point of deriving is that tomorrow's
  // driver is included without anyone remembering to add it. The floor is
  // what stops a broken walk from passing this file vacuously green.
  assert.ok(
    drivers.length >= 20,
    `expected the bench trees to yield at least 20 Chromium drivers, found ${drivers.length}`
  );
  // And every one of this bead's nine is among them, by derivation.
  const found = new Set(drivers.map((d) => path.resolve(d.file)));
  for (const w of WIRED) {
    assert.ok(found.has(path.resolve(w.file)), `${w.id} must be found by the walk`);
  }
});

/** The registration itself, spelled the way every other predicate here spells it. */
const HANDLER = () => /page\.on\(\s*['"]pageerror['"]/g;

/**
 * Every `pageerror` registration in a driver, with its body generously bounded.
 *
 * THIS USED TO BE `s.indexOf("page.on('pageerror'", i)`, AND THAT WAS A FALSE
 * GREEN. A live `page.on("pageerror", (e) => console.error(e))` — double quotes,
 * or merely a space after the paren — was walked straight past by the offender
 * loop; and the "no signal at all" fallback below, being quote-agnostic, saw
 * that a handler *existed* and so declined to report it either. Reproduced
 * against these exact predicates: `handlerDetectedByFallback = true`,
 * `offenders = []`, the class test green over a print-only handler. Nothing in
 * this repo's ESLint config carries a quote rule for these files, so the other
 * spelling is one keystroke away rather than an impossible one.
 *
 * A check that recognises only one spelling of the thing it hunts stops working
 * the moment someone writes the other one — which is this whole file's subject
 * matter, wearing the gate's own clothes.
 */
function handlerSites(s) {
  const sites = [];
  const re = HANDLER();
  for (let m; (m = re.exec(s)); ) {
    // Long enough for an inline arrow body, short enough that an unrelated
    // `push(` further down the file cannot vouch for this handler.
    sites.push({ at: m.index, body: s.slice(m.index, m.index + 220) });
  }
  return sites;
}

/**
 * The source with every comment and string/template literal blanked to spaces,
 * LENGTH AND EVERY INDEX PRESERVED, so it can be compared against positions
 * taken from the unmasked text.
 *
 * WHY A READER CHECK NEEDS THIS. `sinkIsRead` below asks whether an identifier
 * occurs anywhere that is not a write. A mention of the array in a trailing
 * comment (`const errors = []; // errors collector only`) or inside a printed
 * message is not a read of it, and counting one as a read passes a driver whose
 * array nobody consults — the exact fail-open this file exists to forbid, this
 * time hiding in the gate. `code()` above blanks comment-ONLY lines, which is
 * the right scrub for finding registrations but leaves both of those standing.
 *
 * A TEMPLATE'S `${...}` IS THE OTHER HALF OF THAT SENTENCE. Its text is a
 * string, but its substitutions are code — `` `${errors.length} page errors` ``
 * genuinely reads the sink — so the runs of text are blanked and the
 * substitutions are left standing.
 *
 * THE ONE THING IT DOES NOT LEX IS A REGEX LITERAL, and the omission is bounded
 * two ways rather than hoped about. It cannot run away: a `'` or `"` that finds
 * no partner before the newline is put back as a lone character, so a character
 * class like `/^"|"$/` — which three drivers use to parse CSV a few lines from
 * their handler — blanks at most a few characters of its own line and never
 * reaches the next. And it cannot fail QUIETLY: masking only ever DELETES
 * candidate reads, so its worst case is `sinkIsRead` saying UNREAD about a
 * driver that does read its sink — a loud red naming the file, never a silent
 * pass. The fixtures below pin the behaviour rather than assuming it.
 */
function maskLiterals(s) {
  const out = s.split('');
  const blank = (from, to) => {
    for (let k = from; k < to && k < out.length; k += 1) if (out[k] !== '\n') out[k] = ' ';
  };
  let i = 0;
  while (i < s.length) {
    const c = s[i];
    const d = s[i + 1];
    if (c === '/' && d === '/') {
      const nl = s.indexOf('\n', i);
      const end = nl === -1 ? s.length : nl;
      blank(i, end);
      i = end;
    } else if (c === '/' && d === '*') {
      const close = s.indexOf('*/', i + 2);
      const end = close === -1 ? s.length : close + 2;
      blank(i, end);
      i = end;
    } else if (c === '`') {
      // A TEMPLATE'S TEXT IS A STRING; ITS `${...}` SUBSTITUTIONS ARE CODE.
      // `console.error(`${errors.length} page errors`)` reads the sink, and
      // blanking the whole literal would lose that read. Text runs are blanked,
      // substitutions are left standing, and nothing is written until the
      // closing backtick is actually found.
      const runs = [];
      let j = i + 1;
      let runFrom = i;
      let closed = false;
      while (j < s.length) {
        if (s[j] === '\\') {
          j += 2;
        } else if (s[j] === '`') {
          closed = true;
          break;
        } else if (s[j] === '$' && s[j + 1] === '{') {
          const close = s.indexOf('}', j + 2);
          if (close === -1) break;
          runs.push([runFrom, j + 2], [close, close + 1]);
          j = close + 1;
          runFrom = j;
        } else {
          j += 1;
        }
      }
      if (!closed) {
        i += 1; // a lone backtick consumes nothing
      } else {
        runs.push([runFrom, j + 1]);
        for (const [from, to] of runs) blank(from, to);
        i = j + 1;
      }
    } else if (c === "'" || c === '"') {
      let j = i + 1;
      let closed = false;
      while (j < s.length) {
        if (s[j] === '\\') {
          j += 2;
          continue;
        }
        if (s[j] === c) {
          closed = true;
          break;
        }
        if (s[j] === '\n') break; // not a string: `'` and `"` cannot span lines
        j += 1;
      }
      if (!closed) {
        i += 1; // a lone quote — a regex class, an apostrophe — consumes nothing
      } else {
        blank(i, j + 1);
        i = j + 1;
      }
    } else {
      i += 1;
    }
  }
  return out.join('');
}

/** A driver read once: its code-only text, and the same text with literals gone. */
const scan = (s) => ({ s, masked: maskLiterals(s) });

/**
 * The array a handler pushes into: the sink whose reader is checked below.
 *
 * Inline handlers name it directly. A handler registered BY NAME is followed
 * once to its declaration — the same single hop the offender loop already
 * makes — and the sink is read from there.
 *
 * `pushAt` IS THE EXACT SPAN OF THE PUSH THIS FOLLOWED, not the window it was
 * found in. The two used to be the same thing — `{from: site.at, to: site.at +
 * 220}` — and that is the defect this replaces: the reader below excluded every
 * occurrence in those 220 characters as "the push itself", so a genuine
 * `if (errors.length) process.exit(1)` sitting one line under the registration
 * was thrown away with it and the driver reported UNREAD. Both halves of a
 * handler and its refusal fit inside 220 characters comfortably; that is how
 * they are usually written.
 */
function sinkOf({ masked }, site) {
  // The PUSH is looked for in the masked text and the REGISTRATION in the raw:
  // a push written inside a comment must not nominate a sink, and `'pageerror'`
  // is a string literal, so the mask has eaten it.
  const inline = /([A-Za-z_$][\w$]*)\s*\.push\(/.exec(masked.slice(site.at, site.at + 220));
  if (inline) return { name: inline[1], pushAt: site.at + inline.index };
  const named = /page\.on\(\s*['"]pageerror['"]\s*,\s*([A-Za-z_$][\w$]*)\s*\)/.exec(site.body);
  if (!named) return null;
  const declAt = masked.search(new RegExp(`\\b${named[1]}\\s*=\\s*\\(`));
  if (declAt === -1) return null;
  const push = /([A-Za-z_$][\w$]*)\s*\.push\(/.exec(masked.slice(declAt, declAt + 400));
  return push ? { name: push[1], pushAt: declAt + push.index } : null;
}

/**
 * Is the sink consulted anywhere that is neither a write nor its declaration?
 *
 * Every exclusion is an EXACT test on one occurrence — the span of the push
 * this sink was followed from, an occurrence written into by another `.push(`,
 * an occurrence being declared or reset with `= []` — so nothing is discarded
 * for the crime of being near the handler. Occurrences are taken from the
 * masked text, so a comment or a message that happens to spell the array's name
 * is not mistaken for a use of it.
 */
function sinkIsRead({ masked }, sink) {
  const ref = new RegExp(`\\b${sink.name}\\b`, 'g');
  for (let m; (m = ref.exec(masked)); ) {
    if (m.index === sink.pushAt) continue; // the push this sink was found by
    const after = masked.slice(m.index + sink.name.length);
    if (/^\s*\.push\(/.test(after)) continue; // another write
    if (/^\s*=\s*\[\s*\]/.test(after)) continue; // the declaration, or a reset
    return { read: true, at: m.index };
  }
  return { read: false, at: -1 };
}

test('NO bench driver installs a pageerror handler that only prints (rf2-sib23)', () => {
  const offenders = [];
  for (const { file, src: s } of benchDrivers()) {
    for (const { body } of handlerSites(s)) {
      // A recording handler names a sink: `push(` into an array, or `record(`
      // — the shared collector's own verb.
      if (/push\(/.test(body) || /record\(/.test(body)) continue;
      // A handler registered by NAME (`page.on('pageerror', onError)`) is
      // sound too — `z3vlz_run.cjs` does exactly that — provided the named
      // function records. Follow the name once rather than demanding an
      // inline arrow, which would be a style rule wearing a gate's clothes.
      const named = /page\.on\(\s*['"]pageerror['"]\s*,\s*([A-Za-z_$][\w$]*)\s*\)/.exec(body);
      const decl = named && new RegExp(`${named[1]}\\s*=\\s*[\\s\\S]{0,400}?push\\(`).exec(s);
      if (decl) continue;
      offenders.push(`${path.relative(IMPL, file)}: ${body.split('\n')[0].trim()}`);
    }
    // A driver that registers no handler at all must be reaching the event
    // some other way — the shared collector. Anything else is a driver with
    // no page-error signal, which is the same fail-open with fewer lines.
    if (!/page\.on\(\s*['"]pageerror['"]/.test(s) && !/watchPage\(/.test(s)) {
      offenders.push(`${path.relative(IMPL, file)}: no pageerror signal at all`);
    }
  }
  assert.deepStrictEqual(
    offenders,
    [],
    'a bench driver that prints a page error and moves on publishes a precise number for a '
      + 'page that is not the page under test — collect it and refuse, as sentinel.cjs says'
  );
});

test("every LOCAL collector's sink is READ, not merely pushed into (rf2-sib23)", () => {
  // THE HALF OF THE CLASS CLAIM THAT WAS MISSING. The offender loop above is
  // satisfied by any `push(` within 220 characters of the registration — so a
  // handler that faithfully filled an array NOBODY EVER LOOKS AT passed it,
  // while the file's headline claim is that no fleet member prints and moves
  // on. An unread array prints and moves on; it just takes one more line to
  // say so.
  //
  // That is not a hypothetical shape: it is precisely the defect rf2-x6g04
  // found in `hd8_run.cjs` — the collector armed, the exit consulting nobody —
  // and the shared half below has had a reader check ever since. The local
  // half did not, so "every fleet member refuses" rested on seven drivers
  // being read by hand once. This derives it instead.
  //
  // The bar is the shared check's bar, deliberately: the sink must be READ
  // somewhere that is neither its declaration nor another write. Following it
  // the rest of the way to a `process.exit` would need real dataflow — three
  // hops in `jsfb_ours_run.cjs`, which funnels per-arm `errors` into an
  // aggregate before refusing on it — and a dataflow engine to police twenty-
  // two operator scripts is the fleet-wide abstraction rf2-jvheq's mayor note
  // argued against.
  const offenders = [];
  for (const { file, src: s } of benchDrivers()) {
    const sc = scan(s);
    for (const site of handlerSites(s)) {
      const sink = sinkOf(sc, site);
      // No sink at all is the offender check's business, not this one's.
      if (!sink) continue;
      if (!sinkIsRead(sc, sink).read) offenders.push(`${path.relative(IMPL, file)}: ${sink.name}`);
    }
  }
  assert.deepStrictEqual(
    offenders,
    [],
    'a local pageerror array that nothing reads is the same fail-open as a bare handler with '
      + 'one extra line — collect it AND refuse on it (sentinel.cjs, rf2-sib23)'
  );
});

// ---------------------------------------------------------------------------
// 3c. THE READER CHECK, CHECKED — fixtures the fleet cannot supply.
// ---------------------------------------------------------------------------
//
// A green fleet says nothing about whether the reader check WORKS. The whole
// fleet passed while both of its answers were wrong in opposite directions, and
// it passed for the ordinary reason a derived check passes: no driver in the
// tree happened to wear either shape. That is luck, and luck expires the next
// time somebody writes a driver.
//
// So the predicate is driven directly, on sources written to be exactly the two
// classes that were misjudged plus the neighbours that must keep working. These
// are executable: each string goes through the real `handlerSites`, the real
// `sinkOf` and the real `sinkIsRead`, so a regression in any of the three shows
// up here even when every driver on disk stays sound.
//
// FIVE OF THE NINE FAIL ON THE PREDICATE THIS REPLACED — the two the audit
// named, and three nobody was looking for: a handler registered by name whose
// array IS read (the by-name path had a 400-character window with the same
// defect), a double-quoted registration, and the regex-class case. They were
// found by writing the neighbours down. That is why they are fixtures and not
// a note.

const READER_FIXTURES = [
  {
    id: 'the refusal sits one line under the registration',
    read: true,
    // #7517's first misjudgement: within 220 characters of `page.on`, so the
    // old window swallowed it and called a refusing driver an offender.
    src: [
      'const errors = [];',
      "page.on('pageerror', (e) => errors.push(e));",
      'if (errors.length) {',
      "  console.error('[x] the page threw and kept going');",
      '  process.exit(1);',
      '}',
    ].join('\n'),
  },
  {
    id: 'a trailing comment names the array and nothing else does',
    read: false,
    // #7517's second misjudgement, and the dangerous direction: the comment's
    // `errors` fell outside the excluded window, so it counted as a read and
    // an unread collector passed the class check.
    src: [
      'const errors = []; // errors collector only',
      "page.on('pageerror', (e) => errors.push(e));",
      "console.error('[x] ok');",
      'process.exit(0);',
    ].join('\n'),
  },
  {
    id: 'a printed message names the array and nothing else does',
    read: false,
    src: [
      'const errors = [];',
      "page.on('pageerror', (e) => errors.push(e));",
      "console.error('[x] errors are recorded');",
      'process.exit(0);',
    ].join('\n'),
  },
  {
    id: 'the refusal is far below the registration',
    read: true,
    // The shape the old predicate got right, kept so the repair cannot be a
    // swap of one blind spot for another.
    src: [
      'const errors = [];',
      "page.on('pageerror', (e) => errors.push(e));",
      // Real statements, not comments: `code()` would blank comment lines and
      // the read would land back inside 220 characters, testing nothing.
      'const filler = [0, 1, 2, 3, 4, 5, 6, 7, 8, 9];\n'.repeat(8),
      'if (errors.length) process.exit(1);',
    ].join('\n'),
  },
  {
    id: 'a second handler writing the same array is not a read',
    read: false,
    src: [
      'const errors = [];',
      "page.on('pageerror', (e) => errors.push(e));",
      "page.on('console', (m) => errors.push(m.text()));",
      'process.exit(0);',
    ].join('\n'),
  },
  {
    id: 'a handler registered BY NAME whose array is read',
    read: true,
    // `z3vlz_run.cjs`'s shape: the sink is reached by following the name once.
    src: [
      'const pageErrors = [];',
      'const onError = (e) => {',
      '  pageErrors.push(e.message);',
      '};',
      "page.on('pageerror', onError);",
      'if (pageErrors.length) process.exit(1);',
    ].join('\n'),
  },
  {
    id: 'a handler registered BY NAME whose array is never read',
    read: false,
    src: [
      'const pageErrors = [];',
      'const onError = (e) => {',
      '  pageErrors.push(e.message);',
      '};',
      "page.on('pageerror', onError);",
      'process.exit(0);',
    ].join('\n'),
  },
  {
    id: 'a DOUBLE-QUOTED registration is judged the same as a single-quoted one',
    read: true,
    // The quote-agnostic repair reaches the sink path too, not just the
    // offender loop it was written for.
    src: [
      'const errors = [];',
      'page.on( "pageerror", (e) => errors.push(e));',
      'if (errors.length) process.exit(1);',
    ].join('\n'),
  },
  {
    id: 'the only read is inside a template substitution',
    read: true,
    // `${errors.length}` is code wearing a string's clothes. Blanking the whole
    // literal would lose the read and report a refusing driver as an offender.
    src: [
      'const errors = [];',
      "page.on('pageerror', (e) => errors.push(e));",
      'if (done) {',
      '  console.error(`[x] ${errors.length} page error(s) — the run is not reportable`);',
      '  process.exit(1);',
      '}',
    ].join('\n'),
  },
  {
    id: 'a template TEXT naming the array is still not a read',
    read: false,
    src: [
      'const errors = [];',
      "page.on('pageerror', (e) => errors.push(e));",
      'console.error(`[x] errors are recorded, ${count} of them`);',
      'process.exit(0);',
    ].join('\n'),
  },
  {
    id: "a regex character class full of quotes does not blind the reader",
    read: true,
    // The masker's documented limit, pinned rather than assumed. `b7_run.cjs`,
    // `reads_ladder_run.cjs` and `spine_ablation_run.cjs` all parse CSV with
    // this exact expression a few lines from their handler.
    src: [
      'const errors = [];',
      "page.on('pageerror', (e) => errors.push(e));",
      'const fields = line.split(\',\').map((s) => s.trim().replace(/^"|"$/g, \'\'));',
      'if (errors.length) process.exit(1);',
    ].join('\n'),
  },
];

test('the local-sink reader check answers the two shapes that fooled it (rf2-sib23)', () => {
  for (const f of READER_FIXTURES) {
    const sc = scan(code(f.src));
    const sites = handlerSites(sc.s);
    assert.strictEqual(sites.length, 1, `fixture "${f.id}": expected one handler site`);
    const sink = sinkOf(sc, sites[0]);
    assert.ok(sink, `fixture "${f.id}": expected a sink to be found`);
    assert.strictEqual(
      sinkIsRead(sc, sink).read,
      f.read,
      `fixture "${f.id}": the sink \`${sink.name}\` is ${f.read ? 'READ' : 'UNREAD'}, `
        + `and the check said otherwise`
    );
  }
});

test('the mask blanks comments and strings, and preserves every index', () => {
  // `sinkIsRead` compares positions taken from the unmasked text against the
  // masked text. If the mask ever changed a length the comparison would be
  // silently wrong rather than loudly broken, so the property is pinned.
  const s = [
    "const a = 'one'; // two",
    '/* three */ const b = `four',
    'five`;',
    "const re = /^\"|\"$/g;",
    'const c = `six ${seven} eight`;',
  ].join('\n');
  const m = maskLiterals(s);
  assert.strictEqual(m.length, s.length, 'the mask must preserve length');
  assert.strictEqual(
    m.split('\n').length,
    s.split('\n').length,
    'the mask must preserve line count'
  );
  for (const gone of ['one', 'two', 'three', 'four', 'five', 'six', 'eight']) {
    assert.ok(!m.includes(gone), `\`${gone}\` is inside a comment or string and must be blanked`);
  }
  for (const kept of ['const a', 'const b', 'const re', 'const c', 'seven']) {
    assert.ok(m.includes(kept), `\`${kept}\` is code and must survive the mask`);
  }
  // The documented limit: a lone quote inside a regex class blanks a little of
  // its OWN line and never runs on. `const re` above survives, and so does the
  // line after it.
  assert.strictEqual(m.split('\n').length, 5);
});

test('every driver that uses the shared collector also READS its failures', () => {
  // Installing `watchPage` and never reading `.failures` is exactly the
  // defect rf2-x6g04 found in `hd8_run.cjs`: the collector armed, the exit
  // consulting nobody. `race()` counts as a read — it throws on a failure it
  // can order — so either reference satisfies this.
  const offenders = [];
  for (const { file, src: s } of benchDrivers()) {
    if (!/watchPage\(/.test(s)) continue;
    if (!/\.failures/.test(s) && !/\.race\(/.test(s)) {
      offenders.push(path.relative(IMPL, file));
    }
  }
  assert.deepStrictEqual(offenders, [], 'a watcher nobody reads is not a watcher');
});

// ===========================================================================
// 4. THE END-TO-END PROOFS — the probes that made two of the four watchable,
//    and the two properties that keep them honest.
// ===========================================================================
//
// rf2-sib23 could watch five of the nine refuse and named the four it could
// not: no pure exported verdict, so no unit test could reach the decision,
// and no way to make the page throw, so no end-to-end run could either.
// rf2-va5nm took three of those four. `b7_run.cjs` and `p0_run.cjs` have both
// been watched exiting non-zero in their own processes, against a real
// `:advanced` build and a real headless Chromium, and both exiting 0 on the
// same row with the throw removed.
//
// The lever was each driver's OWN published seam — `B7_INIT_FN`, `P0_INIT_FN`
// — pointed at a namespace that requires the REAL app and adds one detached
// throw. rf2-va5nm is explicit that the alternative was worse: *a wrong stub
// is not a cheaper proof, it is a fiction that can pass or fail for reasons
// that have nothing to do with the gate.* And `--no-build` was refused
// outright — a knob that serves a stale bundle beside a published figure is a
// new fail-open in the family this whole line of work exists to close.
//
// THE THIRD WAS NOT ARRANGED AT ALL, and it is the better evidence.
// `reads_ladder_run.cjs` refused a REAL `pageerror` and exited 1 during this
// work — `Cannot read properties of undefined (reading 'd')` — because `b7`,
// the ladder and the ablation all merge a different `:init-fn` onto the one
// `freehand-release` build id and none of them resets its cache the way
// `p0_run.cjs` does (rf2-t4j7c, and `lane_cache.cjs` for the fault class).
// A cleared cache and the identical command gave `[ladder] done`. So the
// refusal fired on exactly the environmental fault `sentinel.cjs`'s header
// names as the common one — and, because of section 5 below, said so in
// seconds instead of after the 120s wait.
//
// SINCE FIXED, and the count was higher than it looked: rf2-t4j7c found SEVEN
// drivers riding `freehand-release` with their own `:init-fn`, not three, and
// gave each of them `resetLaneBuildCache`. `lane_cache_wiring.test.cjs` now
// discovers the riders and fails if one lands without the call, so the
// paragraph above is history rather than current state.
//
// `spine_ablation_run.cjs` alone remains unwatched: it has no `:init-fn`
// seam, and adding one purely so a test could reach it would be a knob that
// lets a published figure be measured on an entry nobody built on purpose.
// rf2-va5nm stays open for it rather than the roster being padded.
//
// So the two things worth pinning are that the probe is not a stub, and that
// nothing ships it.

const PROBES = [
  {
    id: 'b7_pageerror_probe.cljs',
    file: path.join(BENCH, 'b7_pageerror_probe.cljs'),
    ns: 're-frame.freehand.bench.b7-pageerror-probe',
    app: 're-frame.freehand.bench.b7-app',
    call: /\(b7-app\/-main\)/,
    driver: path.join(BENCH, 'b7_run.cjs'),
    seam: /const INIT_FN = process\.env\.B7_INIT_FN \|\|/,
  },
  {
    id: 'p0_pageerror_probe.cljs',
    file: path.join(CORE_BENCH, 'p0_pageerror_probe.cljs'),
    ns: 're-frame.bench.p0-pageerror-probe',
    app: 're-frame.bench.p0-app',
    call: /\(p0-app\/-main\)/,
    driver: path.join(CORE_BENCH, 'p0_run.cjs'),
    seam: /const INIT_FN = process\.env\.P0_INIT_FN \|\|/,
  },
];

for (const p of PROBES) {
  test(`${p.id}: it is the REAL app plus a throw, not a stub`, () => {
    assert.ok(fs.existsSync(p.file), `${p.id} must exist at ${p.file}`);
    const s = src(p.file);
    assert.match(s, new RegExp(`\\(ns ${p.ns.replace(/[.*+?^${}()|[\]\\]/g, '\\$&')}`));
    // It REQUIRES the published entry and CALLS it. A probe that reimplemented
    // the page would be the stub rf2-va5nm refused, and it would prove nothing
    // about the driver's gate.
    assert.match(s, new RegExp(`:require \\[${p.app.replace(/[.*+?^${}()|[\]\\]/g, '\\$&')} :as`));
    assert.match(s, p.call, `${p.id} must call the real app's -main`);
    // And the throw is DETACHED — a task the app has already returned from.
    // A throw inside `-main` would be caught by the app's own `catch`, set
    // `window.*_ERROR`, and exercise a gate that was never broken.
    assert.match(s, /js\/setTimeout/, `${p.id}'s throw must be detached`);
    assert.match(s, /\(throw \(js\/Error\./, `${p.id} must actually throw`);
  });

  test(`${p.id}: NOTHING SHIPS IT — no driver default and no build points here`, () => {
    // The probe is a way to make a real page throw, not a second entry. If a
    // driver ever defaulted to it, or a build id named it, this file's whole
    // claim — that the proof was taken on the published instrument — would be
    // false, and a published figure could be measured on the probe.
    const driver = code(src(p.driver));
    assert.ok(
      !driver.includes(p.ns),
      `${path.basename(p.driver)} must not name ${p.ns} in code — it is an operator seam`
    );
    assert.match(src(p.driver), p.seam, `${path.basename(p.driver)} must keep its own INIT_FN seam`);
    const shadow = fs.readFileSync(path.join(IMPL, 'shadow-cljs.edn'), 'utf8');
    assert.ok(!shadow.includes(p.ns), `shadow-cljs.edn must not name ${p.ns}`);
  });
}

// ===========================================================================
// 5. EVERY SENTINEL WAIT IN THE COLLECTOR'S FLEET IS RACED (rf2-qv761)
// ===========================================================================
//
// The other half of `sentinel.cjs`, and the half rf2-sib23 deliberately left
// undone. A page that dies BEFORE its sentinel still satisfies section 2
// above — the failure is recorded and the exit refuses on it — but the exit
// is fifteen, twenty or thirty minutes away, because the bare
// `page.waitForFunction` in front of it has no idea the page is gone. The
// operator gets `Timeout 1200000ms exceeded` about a `ReferenceError` from
// the first second.
//
// The rule is DERIVED and narrow: a driver that installs the shared collector
// must not then wait on a bare `page.waitForFunction`, because it is holding
// the very object that already knows. It deliberately says nothing about the
// drivers that do NOT use `watchPage` — the local-array drivers in the hicasso
// tree wait bare, which is a different (and older) shape, and converting them
// is not this bead. THAT SCOPE LINE STANDS; only its arithmetic did not. This
// note first said "five", and the walk above already yielded SIX at the very
// commit that wrote it (`shapes/census_clock_run.cjs` had landed the day
// before). A stale hand-count in the one file whose thesis is that hand-kept
// rosters drift — so the number is gone rather than corrected. Run the walk.

test('no driver holding the shared collector waits on a BARE page.waitForFunction', () => {
  const offenders = [];
  for (const { file, src: s } of benchDrivers()) {
    if (!/watchPage\(/.test(s)) continue;
    if (/page\.waitForFunction\(/.test(s)) offenders.push(path.relative(IMPL, file));
  }
  assert.deepStrictEqual(
    offenders,
    [],
    'a driver that installed watchPage and then waited bare is asking a page that is already '
      + 'dead to please finish — race the sentinel against it (sentinel.cjs, rf2-qv761)'
  );
});

test('and every one of the nine actually races SOMETHING', () => {
  // The inverse of the rule above: deleting a wait would satisfy it
  // vacuously. Each of the nine must carry at least one `race` call, and its
  // budget must be NAMED — `race` refuses an unnamed ceiling by construction
  // (rf2-p9fa3), so this pins the intent rather than the mechanism.
  for (const w of WIRED) {
    const s = code(src(w.file));
    assert.match(s, /\bwatch\.race\(/, `${w.id} must race its sentinel`);
    assert.match(s, /budget:/, `${w.id}'s race must name the budget it did NOT spend`);
  }
});

let failed = 0;
for (const [name, fn] of tests) {
  try {
    fn();
  } catch (err) {
    failed += 1;
    console.error(`FAIL  ${name}\n      ${err.message}`);
  }
}

if (failed > 0) {
  console.error(`\npageerror_exit_path.test.cjs: ${failed}/${tests.length} failed`);
  process.exit(1);
}
console.log(`pageerror_exit_path.test.cjs: ${tests.length} passed`);
