#!/usr/bin/env node
//
// OUR CLOCK INSTRUMENT, ON THE BENCHMARK'S APP (rf2-rguy1).
//
//   node freehand/test/re_frame/bench/hicasso/jsfb_ours_run.cjs
//
// ## Why this file exists — the cross-check needs a 2x2, not a 1x1
//
// The bead asks whether our harness and krausest/js-framework-benchmark
// AGREE ON THE RATIO. Run naively that comparison is confounded, and badly:
//
//   * our published ratio is `hicasso / reagent-subs` on the M1 witness —
//     901 elements, 300 boundaries, a COLD MOUNT into an empty container;
//   * the benchmark's is `create 1,000 rows` — ~8,000 elements, 1,000
//     boundaries, a CLICK on an already-mounted app.
//
// Two instruments AND two workloads differ at once, so a disagreement
// could not be attributed to either. That is the same shape of fault this
// session already found twice — a confounded design that could not have
// produced its null, and a held-out rung that was an algebraic identity of
// the checks it was meant to validate.
//
// So the design crosses the two factors:
//
//                       | our instrument | the benchmark's
//   --------------------|----------------|-----------------
//   the benchmark's app |    THIS FILE   |  npm run bench
//   the M1 witness      |  already published (the candidate's clock)  | -
//
// The cell this file fills is the one that makes the other two readable.
// With it, `THIS FILE vs npm run bench` is a pure INSTRUMENT comparison at
// one workload, and `THIS FILE vs the published M1 row` is a pure WORKLOAD
// comparison on one instrument. Neither is available from a single run.
//
// ## The instrument is the published one, unchanged
//
// `Performance.getMetrics` over CDP, `TaskDuration` less
// `DevToolsCommandDuration`, delta across one operation, the operation
// resolved only after `requestAnimationFrame` -> `setTimeout(0)` so the
// rendering lifecycle it caused has run. Main-thread task time including
// style, layout and paint recording; no raster, no compositing.
//
// It is deliberately NOT the same statistic as the benchmark's, and the
// report says so rather than discovering it afterwards: the benchmark
// measures WALL CLOCK from the click's `EventDispatch` to the end of the
// first `Commit` after it, which includes idle time waiting for a frame;
// this measures SUMMED MAIN-THREAD TASK TIME, which does not. Two honest
// instruments can therefore differ on a magnitude while agreeing on a
// ratio, and the ratio is what is being cross-checked.
//
// ## One arm per page, so the clock lane's worst confound cannot arise
//
// The clock harness had to hide arms with `display: none` because four
// arms shared one document and a dirty frame pays for the whole document.
// Here each arm is a separate URL and a separate page, so at every instant
// exactly one arm is mounted. The repair is structural rather than
// applied.
//
// The cost is that the two arms are no longer sample-interleaved. Arms
// alternate WITHIN each round and the round order flips, so a monotone
// drift across the run cancels to first order; and a per-round seam figure
// is published so a reader can see what did not cancel.

const { spawnSync } = require('node:child_process');
const fs = require('node:fs');
const path = require('node:path');

const { chromium } = require('playwright');

const HOST = process.env.JSFB_HOST || 'localhost';
const PORT = Number(process.env.JSFB_PORT || 8080);

const ROUNDS = Number(process.env.JSFB_ROUNDS || 6);
const WARMUP = Number(process.env.JSFB_WARMUP || 5);
const SAMPLES = Number(process.env.JSFB_SAMPLES || 10);

// Every navigation states its own ceiling. Playwright's silent 30s default
// is the trap `_navigation-ceiling-policy` exists to catch: its failure line
// reads like a lane budget, so the fix reached for is a bigger budget, which
// moves nothing. This bounds a page load of a ~636 KB :advanced bundle from
// localhost, so it is generous rather than tight.
const NAV_TIMEOUT_MS = Number(process.env.JSFB_NAV_TIMEOUT_MS || 60000);

// ARMS[0] is the DENOMINATOR every ratio is taken against — Reagent-on-subs,
// which is what HD-012 names the bar. The third arm was added after the first
// run: the contested bulk-broad row is `UIx / Reagent`, so a Reagent-and-
// Hicasso pair cannot speak to it. See `jsfb_uix_app`'s docstring.
const ARMS = ['rf2-reagent', 'rf2-hicasso', 'rf2-uix'];
const BASE = ARMS[0];
const OTHERS = ARMS.slice(1);

const url = (arm) => `http://${HOST}:${PORT}/frameworks/keyed/${arm}/`;

// ---------------------------------------------------------------------------
// The rows
// ---------------------------------------------------------------------------
//
// `setup` runs before the window opens and `verify` after it closes, so
// neither is measured. Every row states which benchmark id it mirrors, so
// the two instruments can be put in one table without a translation step.

// `JSFB_ONLY=run1k,replace1k` narrows the run to named rows. It exists so a
// question about ONE row can be asked without paying for `create10k`, which
// is the most expensive row here and the positive control for all of them —
// so a narrowed run is a PROBE and cannot certify a magnitude. Any run that
// drops the control says so on its own output rather than leaving a reader
// to notice the missing block (rf2-emvod).
const JSFB_ONLY = (process.env.JSFB_ONLY || '').trim();

const ALL_ROWS = [
  {
    id: 'run1k',
    jsfb: '01_run1k',
    maps: 'our M1 mount row — build N elements from nothing',
    setup: async (p) => { await click(p, '#clear'); await expectRows(p, 0); },
    op: async (p) => { await click(p, '#run'); },
    verify: async (p) => expectRows(p, 1000),
  },
  {
    id: 'replace1k',
    jsfb: '02_replace1k',
    maps: 'our bulk rows — replace every row of a mounted table',
    setup: async (p) => { await ensure1k(p); },
    op: async (p) => { await click(p, '#run'); },
    verify: async (p) => expectRows(p, 1000),
  },
  {
    id: 'update10th',
    jsfb: '03_update10th1k',
    maps: 'our narrow row — a localised write, 100 of 1,000 rows dirty',
    // `#run` every sample rather than `ensure1k`, and the difference is
    // not cosmetic: `#update` APPENDS " !!!" to a label, so a page reused
    // across 15 samples measures the 15th update against labels 60
    // characters longer than the 1st, and the row would carry a monotone
    // text-width drift inside itself. Rebuilding the table restores
    // pristine labels, so every sample measures the same operation.
    //
    // The benchmark avoids this a different way — a fresh page per
    // iteration — and its measured update therefore follows 3 warm-up
    // updates rather than 0. Stated because it is a real protocol
    // difference between the two instruments on this row, and it applies
    // identically to both arms.
    setup: async (p) => { await click(p, '#run'); await expectRows(p, 1000); },
    op: async (p) => { await click(p, '#update'); },
    verify: async (p) => {
      const t = await text(p, 'tbody>tr:nth-of-type(991)>td:nth-of-type(2)>a');
      if (!t.endsWith(' !!!')) throw new Error(`update10th: row 991 is ${JSON.stringify(t)}`);
    },
  },
  {
    id: 'swaprows',
    jsfb: '05_swap1k',
    maps: 'our bulk rows — two rows move, the rest are untouched',
    setup: async (p) => { await ensure1k(p); },
    op: async (p) => { await click(p, '#swaprows'); },
    verify: async (p) => {
      const a = await text(p, 'tbody>tr:nth-of-type(2)>td:nth-of-type(1)');
      const b = await text(p, 'tbody>tr:nth-of-type(999)>td:nth-of-type(1)');
      if (a === b) throw new Error(`swaprows: rows 2 and 999 both read ${a}`);
    },
  },
  {
    id: 'clear1k',
    jsfb: '09_clear1k',
    maps: 'teardown of 1,000 boundaries — no clock row of ours covers it',
    setup: async (p) => { await ensure1k(p); },
    op: async (p) => { await click(p, '#clear'); },
    verify: async (p) => expectRows(p, 0),
  },
  {
    id: 'create10k',
    jsfb: '07_create10k',
    maps: 'THE POSITIVE CONTROL — exactly 10x run1k’s rows',
    // A tenth of the samples, because each one costs about ten times as
    // much and the row is a CONTROL rather than a published magnitude:
    // what it has to resolve is "did the instrument see ~10x", and a
    // 10x effect does not need 10 samples to clear a 5-wide band.
    warmup: 2,
    samples: 4,
    setup: async (p) => { await click(p, '#clear'); await expectRows(p, 0); },
    op: async (p) => { await click(p, '#runlots'); },
    verify: async (p) => expectRows(p, 10000),
  },
];

const ROWS = JSFB_ONLY ? ALL_ROWS.filter((r) => JSFB_ONLY.split(',').includes(r.id)) : ALL_ROWS;
if (ROWS.length === 0) {
  console.error(`[ours] JSFB_ONLY=${JSFB_ONLY} selects no row; known ids: ${ALL_ROWS.map((r) => r.id).join(', ')}`);
  process.exit(1);
}

// THE PREDICTION, REGISTERED BEFORE THE RUN.
//
// `create10k` builds exactly ten times `run1k`'s rows, so a working
// instrument must read it as roughly ten times the work. "Roughly" is
// doing real work in that sentence: row creation is close to linear in
// row count but the surrounding constants are not, so the band is wide
// on purpose and stated before the numbers rather than after.
//
// A reading below the band means the instrument saturates; above it,
// that something superlinear (most likely GC) dominates at 10k and the
// 1k rows are not a clean tenth of it. Either way the row is
// informative, and neither outcome is quietly re-described afterwards.
const CONTROL = { row: 'create10k', against: 'run1k', lo: 8.0, hi: 13.0 };

// ---------------------------------------------------------------------------
// Page helpers
// ---------------------------------------------------------------------------

async function click(page, sel) {
  await page.click(sel, { timeout: 30000 });
  await settle(page);
}

// rAF then a macrotask: the operation resolves only after the rendering
// lifecycle it caused has run. Identical to the published clock harness.
async function settle(page) {
  await page.evaluate(
    () =>
      new Promise((res) => requestAnimationFrame(() => setTimeout(res, 0)))
  );
}

async function rowCount(page) {
  return page.$$eval('tbody>tr', (ts) => ts.length);
}

async function expectRows(page, n) {
  const got = await rowCount(page);
  if (got !== n) throw new Error(`expected ${n} rows, saw ${got}`);
}

async function text(page, sel) {
  return page.$eval(sel, (e) => e.textContent);
}

async function ensure1k(page) {
  if ((await rowCount(page)) !== 1000) {
    await click(page, '#run');
    await expectRows(page, 1000);
  }
}

// ---------------------------------------------------------------------------
// The instrument
// ---------------------------------------------------------------------------

const METRICS = [
  'TaskDuration',
  'ScriptDuration',
  'LayoutDuration',
  'RecalcStyleDuration',
  'DevToolsCommandDuration',
];

async function readMetrics(cdp) {
  const { metrics } = await cdp.send('Performance.getMetrics');
  const out = {};
  for (const m of metrics) if (METRICS.includes(m.name)) out[m.name] = m.value;
  return out;
}

function deltaOf(a, b) {
  const d = {};
  for (const k of METRICS) d[k] = (b[k] || 0) - (a[k] || 0);
  const task = d.TaskDuration * 1000;
  const devtools = d.DevToolsCommandDuration * 1000;
  return {
    taskNet: task - devtools,
    // RAW TaskDuration, and on THIS harness it is very nearly the same
    // number (rf2-emvod). `rf2-yd52q` found that `DevToolsCommandDuration`
    // absorbs page script — but only script that runs INSIDE a protocol
    // command. `clock_run.cjs` drives every operation through
    // `page.evaluate`, so its `taskNet` lost the arm's whole write. This
    // harness drives every operation through `page.click`, an INPUT-domain
    // command, and the page's handler runs in an input task the command
    // does not own. Both are reported so the claim is measured here rather
    // than inherited from the other harness's finding.
    task,
    devtools,
    script: d.ScriptDuration * 1000,
    layout: d.LayoutDuration * 1000,
    style: d.RecalcStyleDuration * 1000,
  };
}

// ---------------------------------------------------------------------------
// Statistics — the lane's, so a figure here is read the way its figures are
// ---------------------------------------------------------------------------

const mean = (xs) => xs.reduce((a, b) => a + b, 0) / xs.length;

function median(xs) {
  const s = [...xs].sort((a, b) => a - b);
  const m = s.length >> 1;
  return s.length % 2 ? s[m] : (s[m - 1] + s[m]) / 2;
}

const fmt = (x, n = 4) => (Number.isFinite(x) ? x.toFixed(n) : 'n/a');

// ---------------------------------------------------------------------------
// The run
// ---------------------------------------------------------------------------

async function measureArm(browser, arm, row, roundIdx) {
  const page = await browser.newPage();
  const errors = [];
  page.on('pageerror', (e) => errors.push(String(e)));
  page.on('console', (m) => { if (m.type() === 'error') errors.push('console: ' + m.text()); });

  await page.goto(url(arm), { waitUntil: 'load', timeout: NAV_TIMEOUT_MS });
  await page.waitForSelector('#run', { timeout: NAV_TIMEOUT_MS });
  await settle(page);

  const cdp = await page.context().newCDPSession(page);
  await cdp.send('Performance.enable');

  const out = [];
  let unverified = 0;

  const warmup = row.warmup ?? WARMUP;
  const samples = row.samples ?? SAMPLES;

  for (let i = 0; i < warmup + samples; i++) {
    await row.setup(page);
    await settle(page);

    const before = await readMetrics(cdp);
    await row.op(page);
    const after = await readMetrics(cdp);

    const d = deltaOf(before, after);

    try {
      await row.verify(page);
    } catch (e) {
      unverified++;
      if (unverified <= 3) {
        console.error(`[ours] ${arm} ${row.id} r${roundIdx} sample ${i}: ${e.message}`);
      }
    }

    if (i >= warmup) out.push(d);
  }

  await page.close();
  return { samples: out, unverified, errors };
}

async function main() {
  // Provenance the report can pin, gathered before anything is measured.
  const provenance = {
    node: process.version,
    playwright: require('playwright/package.json').version,
    rounds: ROUNDS,
    warmup: WARMUP,
    samples: SAMPLES,
    bundles: {},
  };

  const browser = await chromium.launch({ headless: true });
  provenance.browser = browser.version();

  // Is the box quiet? Recorded rather than asserted: a run taken under
  // load is discarded by this lane, and run 4 of the clock harness is the
  // precedent. `hardware-concurrency` is reported so a reader knows how
  // much headroom the figures had.
  {
    const p = await browser.newPage();
    await p.goto(url(ARMS[0]), { waitUntil: 'load', timeout: NAV_TIMEOUT_MS });
    provenance.hardwareConcurrency = await p.evaluate(() => navigator.hardwareConcurrency);
    provenance.deviceMemory = await p.evaluate(() => navigator.deviceMemory);
    await p.close();
  }

  // CANONICAL DOM — attribute NAMES SORTED, which is the comparison this
  // lane's own parity gate makes and not a weakening of it.
  //
  // The first three-arm run failed a raw `innerHTML` comparison at byte
  // 187 with all three strings the same length: Reagent serialises
  // `type, id, class` and UIx serialises `class, type, id`. Same
  // elements, same attributes, same values, same text — a different
  // WRITE ORDER for one attribute set. Attribute order is not part of the
  // DOM (it is not observable to CSS, to layout, or to the benchmark's
  // selectors), so a gate that fails on it is testing the serialiser
  // rather than the page, and would refuse a run whose arms do render
  // identical pages.
  //
  // Raw innerHTML lengths are reported alongside, so a reader can see
  // that the canonicalisation moved a serialisation and not a page.
  const canonicalise = (root) => {
    const walk = (el) => {
      const attrs = [...el.attributes]
        .map((a) => `${a.name}="${a.value}"`)
        .sort()
        .join(' ');
      const kids = [...el.childNodes]
        .map((n) => (n.nodeType === 1 ? walk(n) : n.nodeType === 3 ? n.textContent : ''))
        .join('');
      return `<${el.tagName.toLowerCase()}${attrs ? ' ' + attrs : ''}>${kids}</${el.tagName.toLowerCase()}>`;
    };
    return [...root.children].map(walk).join('');
  };

  // THE DOM PARITY GATE. Two arms that build different DOM are not one
  // experiment, and no ratio below means anything if this fails. Taken on
  // a 1,000-row table, which is the state every row but `create10k`
  // measures.
  let parity = { identical: false, lens: {}, firstDiff: null };
  {
    const html = {};
    const rawLens = {};
    for (const arm of ARMS) {
      const p = await browser.newPage();
      await p.goto(url(arm), { waitUntil: 'load', timeout: NAV_TIMEOUT_MS });
      await p.waitForSelector('#run');
      await click(p, '#run');
      await expectRows(p, 1000);
      html[arm] = await p.$eval('#main', canonicalise);
      rawLens[arm] = await p.$eval('#main', (e) => e.innerHTML.length);
      await p.close();
    }
    const base = html[BASE];
    parity.identical = ARMS.every((x) => html[x] === base);
    parity.lens = Object.fromEntries(ARMS.map((x) => [x, html[x].length]));
    parity.rawLens = rawLens;
    if (!parity.identical) {
      const bad = OTHERS.find((x) => html[x] !== base);
      const b = html[bad];
      let i = 0;
      while (i < Math.min(base.length, b.length) && base[i] === b[i]) i++;
      parity.firstDiff = { arm: bad, at: i, base: base.slice(Math.max(0, i - 80), i + 120), other: b.slice(Math.max(0, i - 80), i + 120) };
    }
  }

  // per row -> per arm -> array of round means
  const acc = {};
  for (const row of ROWS) {
    acc[row.id] = {};
    for (const arm of ARMS) acc[row.id][arm] = { rounds: [], unverified: 0, errors: [] };
  }

  for (let r = 0; r < ROUNDS; r++) {
    // Arms alternate within the round and the order flips with it, so a
    // monotone drift across the run cancels to first order.
    const order = r % 2 === 0 ? ARMS : [...ARMS].reverse();
    for (const row of ROWS) {
      for (const arm of order) {
        const { samples, unverified, errors } = await measureArm(browser, arm, row, r);
        const a = acc[row.id][arm];
        a.rounds.push(median(samples.map((s) => s.taskNet)));
        (a.roundsTask ||= []).push(median(samples.map((s) => s.task)));
        a.unverified += unverified;
        a.errors.push(...errors);
        if (!a.decomp) a.decomp = [];
        a.decomp.push({
          script: median(samples.map((s) => s.script)),
          layout: median(samples.map((s) => s.layout)),
          style: median(samples.map((s) => s.style)),
          devtools: median(samples.map((s) => s.devtools)),
          task: median(samples.map((s) => s.task)),
          taskNet: median(samples.map((s) => s.taskNet)),
        });
      }
    }
    console.error(`[ours] round ${r + 1}/${ROUNDS} done`);
  }

  await browser.close();

  // -------------------------------------------------------------------------
  // Report
  // -------------------------------------------------------------------------

  console.log('');
  console.log(';; OUR CLOCK INSTRUMENT ON THE BENCHMARK APP (rf2-rguy1)');
  console.log(`;; clock    Performance.getMetrics TaskDuration less DevToolsCommandDuration,`);
  console.log(`;;          frame-settled (rAF + setTimeout) — main thread only, no raster`);
  console.log(`;; schedule ${ROUNDS} rounds x (${WARMUP} warm-up + ${SAMPLES} samples), arms alternating, order flipping`);
  console.log(`;; runtime  ${provenance.browser}, playwright ${provenance.playwright}, node ${provenance.node}`);
  console.log(`;; box      hardware-concurrency ${provenance.hardwareConcurrency}, device-memory ${provenance.deviceMemory}`);
  console.log('');
  console.log(`;; DOM PARITY (canonical, attribute names sorted): ${parity.identical ? 'IDENTICAL' : 'DIFFERENT'}`);
  console.log(`;;   canonical lengths ${JSON.stringify(parity.lens)}`);
  console.log(`;;   raw innerHTML     ${JSON.stringify(parity.rawLens)}`);
  if (parity.firstDiff) {
    console.log(`;;   ${parity.firstDiff.arm} diverges at ${parity.firstDiff.at}`);
    console.log(`;;   ${BASE}: ${JSON.stringify(parity.firstDiff.base)}`);
    console.log(`;;   ${parity.firstDiff.arm}: ${JSON.stringify(parity.firstDiff.other)}`);
  }
  console.log('');

  const summary = {};
  console.log(`;; ratios are against ${BASE}, the denominator HD-012 names`);
  console.log('');
  console.log(';; row          jsfb id               arm            ms      ratio   range over rounds');
  for (const row of ROWS) {
    const R = acc[row.id][BASE].rounds;
    const unverified = ARMS.reduce((a, x) => a + acc[row.id][x].unverified, 0);
    summary[row.id] = { base: mean(R), unverified, arms: {} };
    console.log(`;; ${row.id.padEnd(12)} ${row.jsfb.padEnd(21)} ${BASE.padEnd(13)} ${fmt(mean(R), 3).padStart(8)}     1.0000   —`);
    for (const arm of OTHERS) {
      const H = acc[row.id][arm].rounds;
      const perRound = R.map((v, i) => H[i] / v);
      const ratio = mean(H) / mean(R);
      summary[row.id].arms[arm] = {
        ms: mean(H), ratio, perRound,
        lo: Math.min(...perRound), hi: Math.max(...perRound),
      };
      console.log(
        `;; ${''.padEnd(12)} ${''.padEnd(21)} ${arm.padEnd(13)} ${fmt(mean(H), 3).padStart(8)}  ${fmt(ratio).padStart(9)}   [${fmt(perRound.length ? Math.min(...perRound) : NaN)} – ${fmt(perRound.length ? Math.max(...perRound) : NaN)}]`
      );
    }
  }

  // THE SAME SAMPLES ON RAW `TaskDuration` (rf2-emvod).
  //
  // The question this answers is whether the figures above needed
  // `rf2-yd52q`'s correction at all. On `clock_run.cjs` they did, badly —
  // `taskNet` there is the operation's frame with the operation's script
  // removed. The claim here is that this harness was never exposed, because
  // it clicks rather than evaluates. A claim is not a measurement, so the
  // two clocks are printed side by side and the reader can see the gap.
  console.log('');
  console.log(';; THE SAME SAMPLES ON RAW TaskDuration — script AND frame (rf2-emvod)');
  console.log(';; row          arm             taskNet     task     ratio-on-taskNet  ratio-on-task   gap');
  const summaryTask = {};
  for (const row of ROWS) {
    const Rn = acc[row.id][BASE].rounds;
    const Rt = acc[row.id][BASE].roundsTask;
    summaryTask[row.id] = { baseNet: mean(Rn), baseTask: mean(Rt), arms: {} };
    console.log(
      `;; ${row.id.padEnd(12)} ${BASE.padEnd(14)} ${fmt(mean(Rn), 3).padStart(8)} ${fmt(mean(Rt), 3).padStart(8)}` +
        `${'1.0000'.padStart(18)} ${'1.0000'.padStart(14)}       —`
    );
    for (const arm of OTHERS) {
      const Hn = acc[row.id][arm].rounds;
      const Ht = acc[row.id][arm].roundsTask;
      const rNet = mean(Hn) / mean(Rn);
      const rTask = mean(Ht) / mean(Rt);
      const perRoundTask = Rt.map((v, i) => Ht[i] / v);
      summaryTask[row.id].arms[arm] = {
        msNet: mean(Hn), msTask: mean(Ht), ratioNet: rNet, ratioTask: rTask,
        perRoundTask, lo: Math.min(...perRoundTask), hi: Math.max(...perRoundTask),
      };
      console.log(
        `;; ${''.padEnd(12)} ${arm.padEnd(14)} ${fmt(mean(Hn), 3).padStart(8)} ${fmt(mean(Ht), 3).padStart(8)}` +
          `${fmt(rNet).padStart(18)} ${fmt(rTask).padStart(14)}   ${fmt(((rTask - rNet) / rNet) * 100, 1).padStart(6)}%` +
          `   [${fmt(Math.min(...perRoundTask))} – ${fmt(Math.max(...perRoundTask))}]`
      );
    }
  }

  console.log('');
  console.log(';; UNVERIFIED SAMPLES (must be 0 on every row)');
  for (const row of ROWS) console.log(`;;   ${row.id.padEnd(12)} ${summary[row.id].unverified}`);

  // The positive control, adjudicated against the band registered above.
  console.log('');
  console.log(';; POSITIVE CONTROL — create10k against run1k, predicted ' + `[${CONTROL.lo} – ${CONTROL.hi}]x before the run`);
  let controlPass = true;
  const haveControl = acc[CONTROL.row] && acc[CONTROL.against];
  if (!haveControl) {
    controlPass = false;
    console.log(
      `;;   NOT RUN — JSFB_ONLY=${JSFB_ONLY} dropped ${!acc[CONTROL.row] ? CONTROL.row : CONTROL.against}. ` +
        `THIS RUN IS A PROBE AND CERTIFIES NO MAGNITUDE: with no control there is nothing saying the ` +
        `instrument saw the work at all. Use it to compare two clocks on the SAME samples, which is a ` +
        `question the control does not guard, and never to publish a ratio.`
    );
  } else {
    for (const arm of ARMS) {
      const num = mean(acc[CONTROL.row][arm].rounds);
      const den = mean(acc[CONTROL.against][arm].rounds);
      const x = num / den;
      const ok = x >= CONTROL.lo && x <= CONTROL.hi;
      if (!ok) controlPass = false;
      console.log(`;;   ${arm.padEnd(14)} ${fmt(x, 3)}x  ${ok ? 'PASS' : 'FAIL'}`);
    }
  }

  console.log('');
  console.log(';; DECOMPOSITION — median ms per sample, round 1');
  console.log(';; row          arm             script    layout     style  devtools      task   taskNet');
  for (const row of ROWS) {
    for (const arm of ARMS) {
      const d = acc[row.id][arm].decomp[0];
      console.log(
        `;; ${row.id.padEnd(12)} ${arm.padEnd(14)} ${fmt(d.script, 3).padStart(8)} ${fmt(d.layout, 3).padStart(9)} ${fmt(d.style, 3).padStart(9)}` +
          ` ${fmt(d.devtools, 3).padStart(9)} ${fmt(d.task, 3).padStart(9)} ${fmt(d.taskNet, 3).padStart(9)}`
      );
    }
  }
  // THE TELL, in one line per row. If `devtools` is roughly CONSTANT across
  // arms that differ by milliseconds of script, the counter is not carrying
  // their script and `taskNet` is a script-inclusive reading here. If it
  // TRACKS the arm — as it does on `clock_run.cjs`, where the operation runs
  // inside the command — it is carrying it, and this harness's published
  // ratios would need the same correction `clock_run.cjs` needed.
  console.log('');
  console.log(';; IS devtools TRACKING THE ARM? (spread across arms, per row)');
  for (const row of ROWS) {
    const dv = ARMS.map((arm) => acc[row.id][arm].decomp[0].devtools);
    const sc = ARMS.map((arm) => acc[row.id][arm].decomp[0].script);
    const spread = (xs) => (Math.max(...xs) - Math.min(...xs));
    console.log(
      `;;   ${row.id.padEnd(12)} devtools ${ARMS.map((a, i) => fmt(dv[i], 3)).join(' / ')}` +
        ` spread ${fmt(spread(dv), 3)} ms   against a SCRIPT spread of ${fmt(spread(sc), 3)} ms`
    );
  }

  const pageErrors = ROWS.flatMap((row) => ARMS.flatMap((arm) => acc[row.id][arm].errors));
  console.log('');
  console.log(`;; PAGE ERRORS: ${pageErrors.length}`);
  pageErrors.slice(0, 5).forEach((e) => console.log(`;;   ${e.slice(0, 160)}`));

  const out = {
    provenance, parity, summary, summaryTask,
    decomposition: Object.fromEntries(
      ROWS.map((row) => [row.id, Object.fromEntries(ARMS.map((arm) => [arm, acc[row.id][arm].decomp]))])
    ),
    control: { ...CONTROL, pass: controlPass },
    pageErrors: pageErrors.length,
  };
  const dest = process.env.JSFB_OURS_JSON;
  if (dest) {
    fs.writeFileSync(dest, JSON.stringify(out, null, 2));
    console.error(`[ours] wrote ${dest}`);
  }

  const totalUnverified = Object.values(summary).reduce((a, s) => a + s.unverified, 0);
  if (!parity.identical || totalUnverified > 0 || pageErrors.length > 0 || !controlPass) {
    console.error('[ours] a gate did not clear — see the report above');
    process.exit(1);
  }
  process.exit(0);
}

main().catch((e) => {
  console.error('[ours] FAILED', e);
  process.exit(1);
});
