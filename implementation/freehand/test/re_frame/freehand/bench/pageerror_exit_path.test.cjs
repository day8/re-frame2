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
    collects: /PAGE_WATCHES\.push\(watchPage\(page, 'b7'\)\)/,
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
    collects: /PAGE_WATCHES\.push\(watchPage\(page, 'ladder'\)\)/,
    carries: /const pageErrors = pageFailures\(\);/,
    decides: /if \(pageErrors\.length\) \{[\s\S]{0,900}?process\.exit\(1\);/,
    green: /console\.error\('\[ladder\] done'\)/,
  },
  {
    id: 'spine_ablation_run.cjs',
    file: path.join(BENCH, 'spine_ablation_run.cjs'),
    collects: /PAGE_WATCHES\.push\(watchPage\(page, 'abl'\)\)/,
    carries: /const pageErrors = pageFailures\(\);/,
    decides: /if \(pageErrors\.length\) \{[\s\S]{0,900}?process\.exit\(1\);/,
    green: /console\.error\('\[abl\] done'\)/,
  },
  {
    id: 'p0_run.cjs',
    file: path.join(CORE_BENCH, 'p0_run.cjs'),
    collects: /PAGE_WATCHES\.push\(watchPage\(page, 'p0'\)\)/,
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
// `watchPage`: seven drivers in the hicasso tree collect into a local array
// and refuse, which is sound, and forcing them onto the shared collector is a
// fleet-wide refactor nobody asked for. What is forbidden is a handler that
// only PRINTS.

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

test('NO bench driver installs a pageerror handler that only prints (rf2-sib23)', () => {
  const offenders = [];
  for (const { file, src: s } of benchDrivers()) {
    let i = 0;
    for (;;) {
      const at = s.indexOf("page.on('pageerror'", i);
      if (at === -1) break;
      i = at + 1;
      // The handler body, generously bounded. A recording handler names a
      // sink: `push(` into an array, or `record(` — the shared collector's
      // own verb.
      const body = s.slice(at, at + 220);
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
