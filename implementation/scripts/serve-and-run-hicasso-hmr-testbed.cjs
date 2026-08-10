#!/usr/bin/env node
'use strict';

/*
 * THE HICASSO HMR GATE — the contract witnessed through a REAL shadow
 * reload, in three engines (rf2-vsgq, closing the browser half of
 * rf2-hic-015).
 *
 *   node implementation/scripts/serve-and-run-hicasso-hmr-testbed.cjs
 *   npm run test:hicasso-hmr                 (from implementation/)
 *
 * ## Why this one runs `watch` when every other browser gate runs `compile`
 *
 * Because the reload IS the thing under test. The #7755 audit on
 * rf2-hic-015 accepted that bead's Node suites and named exactly what they
 * cannot reach: they call `collector/mint-view!` directly and drive the
 * commit and release seams by hand, so they can neither catch drift
 * between the `defview` macro and a real shadow reload, nor catch a
 * renderer that fails to run old-generation cleanup on a type replacement.
 * A compiled bundle cannot close either gap — only shadow's own watcher,
 * websocket and module re-evaluation can — so this gate starts
 * `shadow-cljs watch :hicasso-hmr-testbed`, rewrites one marked line in a
 * real source file, and lets the real pipeline deliver the consequences.
 *
 * The page is served by shadow's own `:dev-http` (port 8061) rather than
 * by http-server, so the document and the devtools websocket share an
 * origin. That is the one respect in which this gate cannot follow the
 * resolved-port convention the other browser gates keep.
 *
 * ## The edit, and why it cannot leave the tree dirty
 *
 * `views.cljs` carries a single line marked `rf2-vsgq:HOT-LINE`, holding a
 * string literal that the app RENDERS. This gate rewrites that literal and
 * nothing else.
 *
 *   - It refuses to start unless the line is in its canonical state, so a
 *     crashed previous run is a loud red rather than a fixture silently
 *     baked into the tree and committed by the next person.
 *   - It restores the line in a `finally` and again from the harness
 *     cleanup, which runs on signals as well as on the normal path.
 *   - It rewrites by regex on the marker, so the file's line endings and
 *     every other byte survive untouched.
 *
 * Because the literal is rendered, `save()` can wait for the NEW TEXT to
 * appear on screen. That is what separates "shadow delivered code" from "a
 * hook fired": a counter alone would be satisfied by an after-load hook
 * running against the module it already had.
 *
 * ## Two verdicts, kept apart — as in the controlled-input gate
 *
 * REQUIRED rows are the spec's assertions; a throw in any engine fails.
 * RECORDED rows are conduct measured without demanding, and they are not a
 * way of not asserting: this runner COMPARES them across engines and fails
 * on a divergence no `NARROWINGS` entry names. Both rest on the suite
 * having run, so the coverage floor is structural — `REQUIRED_SECTIONS`
 * pins the witnesses by name and `REQUIRED_RECORDS` pins the keys each
 * measured row carries.
 *
 * The verdict logic and the hot-line rewriter both carry mutation teeth
 * that run before any browser launches. The rewriter needs them as much as
 * the comparator does: a gate whose "save" silently stopped editing the
 * file would report a perfectly green run in which nothing ever reloaded.
 *
 * ## Coverage — what is witnessed here, and what is not
 *
 * The audit's list, and where each clause is proven:
 *
 * | clause | section | what makes it visible |
 * |---|---|---|
 * | an actual shadow reload through `defview` | `reload-is-real` | the new literal is on screen AND the head the macro produced was replaced |
 * | controlled-input focus / selection | `focused-controlled-input` | node identity, `activeElement`, caret — the value is asserted UNCHANGED |
 * | controlled-input composition | `composition-in-flight` | a draft the model refused, so the post-save text can only be the committed value |
 * | child hook state in a host | `child-hook-state-in-a-host` | the counter AND a per-fiber instance id |
 * | an imperative host ref/instance | `active-imperative-host` | a `textContent` write React never learned about, plus instance identity |
 * | frame routing | `frame-routing-across-a-save` | two frames, two roots, static and live halves, with a reincarnation control |
 * | zero stale-generation registrations | `zero-stale-registrations` | reader counts AND `identical?` against the pre-save registrations |
 * | the lost-cleanup sabotage turns red | `lost-cleanup-sabotage` | React's unsubscribe swallowed at the renderer seam |
 *
 * ### Out of reach here, and named rather than implied
 *
 * - **A real IME.** `Input.imeSetComposition` is a CDP method, so a real
 *   composition RANGE is Chromium-only and stays with
 *   `freehand/test/re_frame/bench/hicasso/ime_run.cjs`. The composition
 *   row drives the event SEQUENCE, which reaches the carve-out and the
 *   shadow but not the browser's own composition range.
 * - **Selection as a RANGE across a save.** A remount destroys the node,
 *   so there is no selection to preserve and nothing to measure beyond
 *   the caret's loss; rf2-hic-016 owns range conduct across an
 *   out-of-band write, where the node survives.
 * - **A reload that changes a `reg-sub`'s BODY.** The two-phase repair
 *   that produces — the held reaction dropped synchronously and rebuilt a
 *   macrotask later — is `hmr_registry_cljs_test`'s, measured at the
 *   snapshot number and the notification count, neither of which is a
 *   browser fact. Nothing here duplicates it.
 */

const fs = require('fs');
const http = require('http');
const path = require('path');
const playwright = require('playwright');
const {
  createHarnessCleanup,
  spawnHarnessProcess,
} = require('./lib/local-browser-harness.cjs');

const IMPL_ROOT = path.resolve(__dirname, '..');
const BUILD_ID = ':hicasso-hmr-testbed';
const PORT = Number(process.env.HICASSO_HMR_PORT) || 8061;
const HOT_FILE = path.join(
  IMPL_ROOT, 'hicasso', 'testbed', 'hicasso_hmr_testbed', 'views.cljs');
const SPEC = require(path.join(IMPL_ROOT, 'hicasso', 'testbed', 'hmr_spec.cjs'));

// The label the file is committed with and must be restored to.
const CANONICAL_LABEL = 'GEN-A';
// The marker is matched rather than a whole line, so CRLF checkouts and any
// future re-indentation of the form survive the rewrite untouched.
const HOT_LINE_RE = /"([A-Za-z0-9-]+)"\)(\s*);; rf2-vsgq:HOT-LINE/;

const BUILD_TIMEOUT_MS = parseInt(process.env.HICASSO_HMR_BUILD_TIMEOUT_MS || '300000', 10);
const SAVE_TIMEOUT_MS = parseInt(process.env.HICASSO_HMR_SAVE_TIMEOUT_MS || '90000', 10);
const SPEC_TIMEOUT_MS = parseInt(process.env.HICASSO_HMR_SPEC_TIMEOUT_MS || '600000', 10);
const NAV_TIMEOUT_MS = 60000;
// The app's own mount, once shadow is already serving a compiled bundle.
// Deliberately NOT the build ceiling: by this point the slow part is done,
// so a long wait here only delays a red.
const MOUNT_TIMEOUT_MS = 60000;

const ALL_ENGINES = ['chromium', 'firefox', 'webkit'];
const ONLY = (process.env.HICASSO_HMR_ENGINES || '').trim();
const ENGINES = ONLY
  ? ALL_ENGINES.filter((e) => ONLY.split(',').map((s) => s.trim()).includes(e))
  : ALL_ENGINES;

// ---------------------------------------------------------------------------
// The coverage floor — names, not a total
// ---------------------------------------------------------------------------

const REQUIRED_SECTIONS = {
  'reload-is-real': 6,
  'focused-controlled-input': 8,
  'composition-in-flight': 7,
  'child-hook-state-in-a-host': 6,
  'active-imperative-host': 7,
  'frame-routing-across-a-save': 13,
  'zero-stale-registrations': 28,
  'lost-cleanup-sabotage': 30,
};

const REQUIRED_RECORDS = {
  'caret-after-a-save': ['start', 'end'],
  'ref-traffic-across-a-save': ['attachments', 'detachments'],
  'retained-census': ['cells', 'cell-refs', 'boundaries', 'edges', 'entries'],
};

const NARROWINGS = [
  // Populated only by a divergence this gate actually measured.
];

const has = (o, k) => Object.prototype.hasOwnProperty.call(o, k);

// ---------------------------------------------------------------------------
// The hot line
// ---------------------------------------------------------------------------

/** The label the source currently carries, or null when the marker is gone. */
function readHotLabel(source) {
  const m = HOT_LINE_RE.exec(source);
  return m ? m[1] : null;
}

/**
 * `source` with the hot label set to `label`. Throws when the marker is
 * absent — a gate that silently stopped editing the file would report a
 * green run in which nothing ever reloaded, which is the worst failure
 * available to this script.
 */
function rewriteHotLabel(source, label) {
  if (readHotLabel(source) === null) {
    throw new Error(
      `the rf2-vsgq:HOT-LINE marker is not in ${HOT_FILE}. Every save this ` +
      `gate performs is a rewrite of that line; without it nothing reloads ` +
      `and every witness below would be measuring a page that never changed.`);
  }
  return source.replace(HOT_LINE_RE, `"${label}")$2;; rf2-vsgq:HOT-LINE`);
}

function readHotFile() { return fs.readFileSync(HOT_FILE, 'utf8'); }
function writeHotFile(text) { fs.writeFileSync(HOT_FILE, text); }

function restoreHotFile() {
  const source = readHotFile();
  if (readHotLabel(source) !== CANONICAL_LABEL) {
    writeHotFile(rewriteHotLabel(source, CANONICAL_LABEL));
  }
}

// ---------------------------------------------------------------------------
// Verdict logic — identical in shape to the controlled-input gate's
// ---------------------------------------------------------------------------

function divergenceReport(perEngine, narrowings = NARROWINGS) {
  const problems = [];
  const engines = Object.keys(perEngine);
  if (engines.length < 2) return problems;
  const rows = new Set();
  for (const e of engines) for (const r of Object.keys(perEngine[e])) rows.add(r);

  for (const row of rows) {
    const byValue = new Map();
    for (const e of engines) {
      const key = JSON.stringify(perEngine[e][row]);
      if (!byValue.has(key)) byValue.set(key, []);
      byValue.get(key).push(e);
    }
    if (byValue.size === 1) continue;
    const narrowing = narrowings.find((n) => n.row === row);
    const groups = [...byValue.entries()]
      .map(([value, es]) => `${es.join('+')}: ${value}`)
      .join('  |  ');
    if (!narrowing) {
      problems.push(
        `RECORDED row "${row}" diverges across engines and no NARROWINGS ` +
        `entry names it — ${groups}. Either the runtime should agree here ` +
        `(fix it) or the divergence is real (add a narrowing with its ` +
        `engines and reason).`);
      continue;
    }
    const seen = engines.filter((e) =>
      JSON.stringify(perEngine[e][row]) !== JSON.stringify(perEngine[engines[0]][row]));
    const unexpected = seen.filter((e) => !narrowing.engines.includes(e));
    if (unexpected.length > 0) {
      problems.push(
        `RECORDED row "${row}" has a narrowing for ${narrowing.engines.join('+')}, ` +
        `but ${unexpected.join('+')} also diverged — ${groups}.`);
    }
  }
  return problems;
}

function coverageReport(result, required = REQUIRED_SECTIONS) {
  const problems = [];
  const sections = (result && result.sections) || {};
  for (const [name, min] of Object.entries(required)) {
    if (!has(sections, name)) {
      problems.push(
        `required section "${name}" did not run — the suite reported ` +
        `[${Object.keys(sections).join(', ') || 'nothing'}].`);
    } else if (sections[name] < min) {
      problems.push(
        `section "${name}" banked ${sections[name]} checks, was ${min} — ` +
        `rows were dropped from a section that still runs.`);
    }
  }
  for (const name of Object.keys(sections)) {
    if (!has(required, name)) {
      problems.push(
        `section "${name}" ran but is not pinned in REQUIRED_SECTIONS — add ` +
        `it with the rows it banks.`);
    }
  }
  return problems;
}

function recordSchemaReport(recorded, required = REQUIRED_RECORDS) {
  const problems = [];
  const rows = recorded || {};
  for (const [row, keys] of Object.entries(required)) {
    if (!has(rows, row) || rows[row] === undefined) {
      problems.push(
        `RECORDED row "${row}" is missing — the comparator cannot find a ` +
        `divergence in a measurement nobody took.`);
      continue;
    }
    if (keys.length === 0) continue;
    const value = rows[row];
    if (value === null || typeof value !== 'object' || Array.isArray(value)) {
      problems.push(
        `RECORDED row "${row}" should be an object carrying ` +
        `[${keys.join(', ')}], got ${JSON.stringify(value)}.`);
      continue;
    }
    const missing = keys.filter((k) => !has(value, k));
    if (missing.length > 0) {
      problems.push(
        `RECORDED row "${row}" is missing key(s) [${missing.join(', ')}] — ` +
        `got [${Object.keys(value).join(', ')}].`);
    }
  }
  for (const row of Object.keys(rows)) {
    if (!has(required, row)) {
      problems.push(
        `RECORDED row "${row}" is not pinned in REQUIRED_RECORDS — add it ` +
        `with the keys it carries.`);
    }
  }
  return problems;
}

// ---------------------------------------------------------------------------
// Mutation teeth
// ---------------------------------------------------------------------------

function runMutationTeeth() {
  const teeth = [];
  const bite = (name, fn) => {
    if (!fn()) throw new Error(`MUTATION TOOTH DID NOT BITE: ${name}`);
    teeth.push(name);
  };

  // --- the rewriter, which is this gate's own load-bearing novelty -------
  const canonical = '  "GEN-A") ;; rf2-vsgq:HOT-LINE\n';

  bite('the label is read back off the canonical line', () =>
    readHotLabel(canonical) === 'GEN-A');

  bite('a rewrite changes the label and nothing else', () => {
    const next = rewriteHotLabel(canonical, 'GEN-7');
    return next === '  "GEN-7") ;; rf2-vsgq:HOT-LINE\n'
      && readHotLabel(next) === 'GEN-7';
  });

  bite('a rewrite is reversible byte for byte', () =>
    rewriteHotLabel(rewriteHotLabel(canonical, 'GEN-7'), 'GEN-A') === canonical);

  bite('a CRLF checkout rewrites just as cleanly', () => {
    const crlf = '  "GEN-A") ;; rf2-vsgq:HOT-LINE\r\n';
    return rewriteHotLabel(crlf, 'GEN-2') === '  "GEN-2") ;; rf2-vsgq:HOT-LINE\r\n';
  });

  bite('a file that lost the marker is REFUSED rather than silently not saved', () => {
    try {
      rewriteHotLabel('(def generation-label "GEN-A")\n', 'GEN-2');
      return false;
    } catch (err) {
      return /HOT-LINE marker is not in/.test(err.message);
    }
  });

  bite('the real source file is in its canonical state and carries the marker', () =>
    readHotLabel(readHotFile()) !== null);

  // --- the comparator ---------------------------------------------------
  bite('agreement across engines raises nothing', () =>
    divergenceReport({
      chromium: { row: { a: 1 } }, firefox: { row: { a: 1 } }, webkit: { row: { a: 1 } },
    }, []).length === 0);

  bite('an unnarrowed divergence is a problem', () =>
    divergenceReport({
      chromium: { row: { a: 1 } }, firefox: { row: { a: 2 } }, webkit: { row: { a: 1 } },
    }, []).length === 1);

  bite('a narrowing that names the engine excuses it', () =>
    divergenceReport({
      chromium: { row: { a: 1 } }, firefox: { row: { a: 2 } },
    }, [{ row: 'row', engines: ['firefox'], why: 'tooth' }]).length === 0);

  bite('a narrowing that names the WRONG engine does not', () =>
    divergenceReport({
      chromium: { row: { a: 1 } }, firefox: { row: { a: 2 } },
    }, [{ row: 'row', engines: ['webkit'], why: 'tooth' }]).length === 1);

  // --- the floors -------------------------------------------------------
  const fullSections = () => ({ ...REQUIRED_SECTIONS });
  const fullRecords = () => Object.fromEntries(
    Object.entries(REQUIRED_RECORDS).map(([row, keys]) => [
      row,
      keys.length === 0 ? true : Object.fromEntries(keys.map((k) => [k, 'x'])),
    ]));

  bite('the floor refuses a run that asserted almost nothing', () =>
    coverageReport({ checks: 3, sections: {} }).length
      === Object.keys(REQUIRED_SECTIONS).length
    && coverageReport({ checks: 1, sections: fullSections() }).length === 0);

  bite('deleting a whole section reds, and the message names it', () => {
    const sections = fullSections();
    delete sections['lost-cleanup-sabotage'];
    const problems = coverageReport({ sections });
    return problems.length === 1 && problems[0].includes('lost-cleanup-sabotage');
  });

  bite('a section that stopped banking rows reds', () => {
    const sections = fullSections();
    sections['frame-routing-across-a-save'] -= 1;
    return coverageReport({ sections }).length === 1;
  });

  bite('a section nobody pinned reds', () =>
    coverageReport({ sections: { ...fullSections(), invented: 1 } }).length === 1);

  bite('an engine that recorded nothing reds', () =>
    recordSchemaReport({}).length === Object.keys(REQUIRED_RECORDS).length
    && divergenceReport({ chromium: {}, firefox: {}, webkit: {} }, []).length === 0);

  bite('the record schema accepts the rows the spec records', () =>
    recordSchemaReport(fullRecords()).length === 0);

  bite('a recorded row missing a key reds', () => {
    const records = fullRecords();
    delete records['caret-after-a-save'].end;
    const problems = recordSchemaReport(records);
    return problems.length === 1 && problems[0].includes('end');
  });

  bite('a recorded row nobody pinned reds', () =>
    recordSchemaReport({ ...fullRecords(), invented: 1 }).length === 1);

  // --- the section roster is the spec's own, not a copy of it -----------
  bite('every section the spec runs is pinned, and vice versa', () => {
    const spec = SPEC.SECTIONS.map(([name]) => name).sort();
    const pinned = Object.keys(REQUIRED_SECTIONS).sort();
    return JSON.stringify(spec) === JSON.stringify(pinned);
  });

  return teeth;
}

// ---------------------------------------------------------------------------
// The watch server
// ---------------------------------------------------------------------------

function get(url) {
  return new Promise((resolve) => {
    const req = http.get(url, (res) => {
      res.resume();
      resolve(res.statusCode);
    });
    req.on('error', () => resolve(null));
    req.setTimeout(2000, () => { req.destroy(); resolve(null); });
  });
}

const sleep = (ms) => new Promise((r) => setTimeout(r, ms));

/**
 * Wait until THIS watch has completed a compile and shadow is serving it.
 *
 * Both halves are load-bearing, and the second one was learned the hard
 * way. `out/hicasso-hmr-testbed/` survives between runs, so an HTTP probe
 * for `/main.js` answers 200 from the PREVIOUS run's bundle within
 * milliseconds of the server binding — the first version of this function
 * gated on that alone, and a run whose source had been fixed loaded the
 * broken bundle from the run before it and reported the bug as still
 * present. A gate that can serve stale code is a gate that can report a
 * green on code nobody compiled.
 *
 * So the pin is the watch's OWN completion line for this build id, which
 * no earlier run can have written into this process's stream.
 */
async function waitForBuild(baseUrl, watchLog, deadline) {
  while (Date.now() < deadline) {
    if (watchLog.failed()) {
      throw new Error(
        `the ${BUILD_ID} build failed to compile.\n${watchLog.tail()}`);
    }
    if (watchLog.exited) {
      throw new Error(
        `shadow-cljs watch exited before the build was servable.\n${watchLog.tail()}`);
    }
    if (watchLog.completed()) {
      const [page, bundle] = await Promise.all([
        get(`${baseUrl}/`), get(`${baseUrl}/main.js`),
      ]);
      if (page === 200 && bundle === 200) return true;
    }
    await sleep(500);
  }
  throw new Error(
    `the ${BUILD_ID} watch did not compile and serve within ` +
    `${BUILD_TIMEOUT_MS}ms.\n${watchLog.tail()}`);
}

function startWatch(cleanup) {
  const runner = require.resolve('shadow-cljs/cli/runner.js', { paths: [IMPL_ROOT] });
  const lines = [];
  // shadow prints one line per build outcome, prefixed with the build id.
  // Matching on the id keeps a sibling build in the same JVM from
  // answering for this one.
  // Exactly as shadow prints it, colon included: `[:hicasso-hmr-testbed]`.
  const marker = `[${BUILD_ID}]`;
  // Scans are FROM A MARK rather than over the whole stream. A watch
  // compiles many times in one run — once at startup and once per save —
  // so a sticky "did a build ever fail" would poison every wait after the
  // first failure, and a sticky "did a build ever complete" would let a
  // later wait be satisfied by an earlier compile.
  const saw = (needle, from) => lines.slice(from)
    .some((l) => l.includes(marker) && l.includes(needle));
  const log = {
    exited: false,
    mark: () => lines.length,
    tail: () => lines.slice(-40).join('\n'),
    completed: (from = 0) => saw('Build completed', from),
    failed: (from = 0) => saw('Build failure', from),
  };
  console.log(`> shadow-cljs watch ${BUILD_ID}`);
  const child = spawnHarnessProcess(process.execPath, [runner, 'watch', BUILD_ID], {
    cwd: IMPL_ROOT, env: process.env, shell: false,
    stdio: ['ignore', 'pipe', 'pipe'],
  });
  cleanup.trackProcess(child);
  const collect = (buf) => {
    for (const line of String(buf).split(/\r?\n/)) if (line.trim()) lines.push(line);
  };
  if (child.stdout) child.stdout.on('data', collect);
  if (child.stderr) child.stderr.on('data', collect);
  child.on('exit', () => { log.exited = true; });
  return log;
}

// ---------------------------------------------------------------------------
// One engine
// ---------------------------------------------------------------------------

let labelCounter = 0;

/**
 * Perform a real save and return once the page is running the new code.
 *
 * The wait is on the RENDERED literal as well as the reload counter,
 * because those two facts fail independently: a hook can fire against the
 * module it already had, and a module can be replaced without anything
 * re-rendering. Requiring both is what makes every `ctx.save()` in the
 * spec a proof that shadow carried code into this page.
 */
function makeSave(page, watchLog) {
  return async () => {
    labelCounter += 1;
    const label = `GEN-${labelCounter}`;
    const before = await page.evaluate(() => window.__RF2_HMR__.reloads());
    const from = watchLog.mark();
    writeHotFile(rewriteHotLabel(readHotFile(), label));
    try {
      await Promise.race([
        page.waitForFunction(
          (arg) => {
            const door = window.__RF2_HMR__;
            if (!door || door.reloads() <= arg.before) return false;
            const node = document.querySelector('#alpha [data-testid="gen-label"]');
            return !!node && node.textContent === arg.label;
          },
          { before, label },
          { timeout: SAVE_TIMEOUT_MS, polling: 100 }),
        // A save that does not compile never reaches the page at all, and
        // waiting out the ceiling for it turns a one-line CLJS error into
        // a ninety-second silence.
        pollUntil(() => watchLog.failed(from), SAVE_TIMEOUT_MS,
          () => new Error(`the save to ${label} failed to compile.`)),
      ]);
    } catch (err) {
      throw new Error(
        `the save to ${label} never reached the page. A compile error in the ` +
        `watch is the usual cause — its last output follows.\n` +
        `${watchLog.tail()}\nUnderlying: ${err.message}`);
    }
    return label;
  };
}

async function driveEngine(engine, baseUrl, watchLog) {
  const browserType = playwright[engine];
  if (!browserType) throw new Error(`unknown engine ${engine}`);
  const browser = await browserType.launch({ headless: true });
  const pageErrors = [];
  const consoleErrors = [];
  const lines = [];
  const log = (s) => lines.push(s);
  let passed = false;
  let result = null;
  try {
    const context = await browser.newContext();
    const page = await context.newPage();
    page.on('console', (msg) => {
      if (msg.type() === 'error') consoleErrors.push(msg.text());
      log(`[${engine}:${msg.type()}] ${msg.text()}`);
    });
    page.on('pageerror', (err) => {
      pageErrors.push(err);
      log(`[${engine}:pageerror] ${err.message}`);
      if (err.stack) log(err.stack);
    });
    await page.addInitScript(SPEC.pageHelpers);

    log(`\n=== ${SPEC.name} — ${engine} ===`);
    await page.goto(baseUrl + SPEC.url, { waitUntil: 'commit', timeout: NAV_TIMEOUT_MS });
    // Both roots mounted AND the door installed: the spec's very first
    // action reads the door, and a testbed whose init threw would
    // otherwise fail with a confusing selector error.
    //
    // Raced against the page's own error channel. A mount that threw is
    // known within milliseconds, and waiting out the full ceiling for it
    // buys nothing but a slow red — measured, the first run of this gate
    // spent five minutes discovering a `defhost` typo it had already
    // printed.
    //
    // The fast-fail watches the CONSOLE rather than only `pageerror`, and
    // that distinction is the one this gate got wrong first: shadow-cljs
    // catches a module-eval throw and reports it through `console.error`,
    // so a testbed that failed to load emits no uncaught error at all and
    // a `pageerror`-only race waits out the whole ceiling. Uncaught errors
    // remain the hard verdict below; a console error during MOUNT is
    // treated as fatal only because a page that logged one has not come
    // up.
    await Promise.race([
      page.waitForFunction(
        () => !!window.__RF2_HMR__
          && !!document.querySelector('#alpha [data-testid="hmr-app"]')
          && !!document.querySelector('#beta [data-testid="hmr-app"]'),
        undefined, { timeout: MOUNT_TIMEOUT_MS }),
      pollUntil(
        () => pageErrors.length > 0 || consoleErrors.length > 0,
        MOUNT_TIMEOUT_MS,
        () => new Error(
          'the page reported an error before it finished mounting: ' +
          (pageErrors[0] ? pageErrors[0].message : consoleErrors[0]))),
    ]);

    result = await withTimeout(
      SPEC.run(page, { engine, save: makeSave(page, watchLog) }),
      SPEC_TIMEOUT_MS, `${SPEC.name} (${engine})`);

    if (pageErrors.length > 0) {
      throw new Error(
        `[${engine}] page emitted ${pageErrors.length} uncaught error(s); ` +
        `first: ${pageErrors[0].message}`);
    }
    const gaps = [
      ...coverageReport(result),
      ...recordSchemaReport(result.recorded),
    ];
    if (gaps.length > 0) {
      throw new Error(
        `[${engine}] banked ${result.checks} checks but the coverage floor ` +
        `is not met:\n  - ${gaps.join('\n  - ')}`);
    }
    passed = true;
  } catch (err) {
    log(`FAIL ${SPEC.name} (${engine}): ${err && err.message ? err.message : err}`);
    if (err && err.stack) log(err.stack);
  } finally {
    await browser.close();
  }
  if (!passed) for (const ln of lines) console.log(ln);
  console.log(passed
    ? `PASS  ${engine} — ${result.checks} checks across ` +
      `${Object.keys(result.sections).length} sections`
    : `FAIL  ${engine}`);
  return { passed, result };
}

/**
 * A promise that rejects as soon as `predicate` is true, and otherwise
 * never settles — for racing against a long wait so a known failure is
 * reported the moment it is known rather than at the end of a ceiling.
 */
function pollUntil(predicate, ms, makeError) {
  return new Promise((_, reject) => {
    const started = Date.now();
    const tick = setInterval(() => {
      if (predicate()) {
        clearInterval(tick);
        reject(makeError());
      } else if (Date.now() - started > ms) {
        clearInterval(tick);
      }
    }, 100);
    if (tick.unref) tick.unref();
  });
}

function withTimeout(promise, ms, label) {
  let timer;
  return Promise.race([
    promise.finally(() => clearTimeout(timer)),
    new Promise((_, reject) => {
      timer = setTimeout(() => reject(new Error(`${label} exceeded ${ms}ms`)), ms);
    }),
  ]);
}

// ---------------------------------------------------------------------------

async function main() {
  const cleanup = createHarnessCleanup();
  cleanup.installSignalHandlers();
  cleanup.addCleanup(() => { restoreHotFile(); });

  if (ENGINES.length === 0) {
    console.error(`HICASSO_HMR_ENGINES=${ONLY} selects no engine; ` +
      `known: ${ALL_ENGINES.join(', ')}`);
    return 1;
  }

  // FAIL CLOSED on a dirty tree. The gate edits a tracked file, so a
  // previous run killed between its edit and its restore would otherwise
  // hand this run a source whose "canonical" state is already a fixture —
  // and the next commit would carry it.
  const startLabel = readHotLabel(readHotFile());
  if (startLabel !== CANONICAL_LABEL) {
    console.error(
      `${HOT_FILE} is not in its canonical state: the hot line reads ` +
      `"${startLabel}" and should read "${CANONICAL_LABEL}". A previous run ` +
      `was probably killed between its edit and its restore. Restore the ` +
      `file (git checkout) and run again.`);
    return 1;
  }

  try {
    const teeth = runMutationTeeth();
    console.log(`> verdict logic teeth bit: ${teeth.length}`);

    const watchLog = startWatch(cleanup);
    const baseUrl = `http://127.0.0.1:${PORT}`;
    await waitForBuild(baseUrl, watchLog, Date.now() + BUILD_TIMEOUT_MS);
    console.log(`> ${BUILD_ID} is live on ${baseUrl}`);

    const recorded = {};
    let failures = 0;
    for (const engine of ENGINES) {
      const { passed, result } = await driveEngine(engine, baseUrl, watchLog);
      if (!passed) failures += 1;
      else recorded[engine] = result.recorded;
    }
    if (failures > 0) {
      console.error(`\n${failures} of ${ENGINES.length} engine(s) failed.`);
      return 1;
    }

    console.log('\n--- RECORDED conduct (measured, not required) ---');
    for (const engine of ENGINES) {
      console.log(`  [${engine}] ${JSON.stringify(recorded[engine])}`);
    }
    const problems = divergenceReport(recorded);
    if (problems.length > 0) {
      console.error('\nCROSS-ENGINE DIVERGENCE:');
      for (const p of problems) console.error(`  - ${p}`);
      return 1;
    }
    for (const n of NARROWINGS) {
      console.log(`  narrowing: ${n.row} on ${n.engines.join('+')} — ${n.why}`);
    }
    console.log(`\nHICASSO HMR PASS (${ENGINES.join(' + ')}) — ` +
      `${labelCounter} real shadow reloads`);
    return 0;
  } finally {
    restoreHotFile();
    await cleanup.cleanup();
  }
}

module.exports = {
  divergenceReport,
  runMutationTeeth,
  coverageReport,
  recordSchemaReport,
  readHotLabel,
  rewriteHotLabel,
  NARROWINGS,
  REQUIRED_SECTIONS,
  REQUIRED_RECORDS,
};

if (require.main === module) {
  main().then((code) => process.exit(code)).catch((error) => {
    console.error(error.stack || String(error));
    process.exit(1);
  });
}
