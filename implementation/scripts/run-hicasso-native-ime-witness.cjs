#!/usr/bin/env node
'use strict';

/*
 * THE SCRIPTED NATIVE-IME WITNESS — a real Windows IME, driven from a
 * script, in Playwright's pinned Firefox and WebKit with Chromium as the
 * control (rf2-hic-016).
 *
 *   node implementation/scripts/run-hicasso-native-ime-witness.cjs --dry-run
 *   node implementation/scripts/run-hicasso-native-ime-witness.cjs --inject
 *   npm run witness:hicasso-native-ime            (from implementation/)
 *
 * ============================ WHAT THIS IS NOT ============================
 *
 * NOT A CI GATE, and it can never become one. It needs Windows, an INSTALLED
 * Japanese IME, a visible desktop session, and the physical keyboard focus of
 * the machine it runs on. There is no hosted runner with those things. The
 * recurring three-engine regression net is and remains the SYNTHETIC witness
 * — `implementation/hicasso/testbed/spec.cjs`, driven by
 * `serve-and-run-hicasso-controlled-testbed.cjs` in the required
 * `cljs-hicasso-controlled` job. This script replaces the HUMAN in the
 * bounded native-IME session; it does not replace that gate, and a green run
 * here is not continuous coverage of anything.
 *
 * NOT SAFE TO RUN UNATTENDED. `--inject` sends OS-level keystrokes with
 * `SendInput`, which delivers to whatever window has the foreground. It
 * brings the browser to the front and types into it. Anything else with
 * focus will receive keystrokes if the browser loses it — which is why the
 * driver re-checks the foreground window before EVERY key and aborts the
 * batch the moment it changes, and why `--dry-run` is the default.
 *
 * ============================== THE TWO MODES =============================
 *
 * `--dry-run` (default) is a full REHEARSAL. It runs the verdict logic's
 * mutation teeth, preflights the machine, compiles and serves the testbed,
 * launches each engine HEADED, installs the observer, reads the trace table
 * back through the DOM, and executes every page-side step of every check —
 * focus, caret placement, the armed bump and the armed unmount, the blur,
 * the reloads, every selector and every read. The one thing it does not do
 * is type: its driver process is started WITHOUT `-Armed`, so each `KEYS`
 * call is answered `REFUSED` by the driver itself rather than skipped by a
 * branch here. Every check therefore returns INCONCLUSIVE against the named
 * premise "no composition ever started", which is the truth about a
 * rehearsal, and the run reports itself as a rehearsal rather than a result.
 *
 * `--inject` is the witness proper. It additionally brings each browser
 * window to the foreground, requests the Japanese input locale for it,
 * VERIFIES that the request took, and then types.
 *
 * ========================= WHY THE IME IS VERIFIED ========================
 *
 * `IMEON` posts `WM_INPUTLANGCHANGEREQUEST` and the IMM32 conversion-mode
 * messages. The modern Microsoft IME is a TSF text service reached through a
 * compatibility layer, and a given browser build need not honour any of it.
 * So the request is never trusted: `IMESTATE` reads the input locale of the
 * BROWSER WINDOW'S OWN THREAD afterwards, and if it is not `0x0411` the run
 * stops and asks the operator to switch with Win+Space rather than typing
 * seven ASCII letters into a text box and calling it a composition. That
 * failure mode is not hypothetical — it is the most likely explanation of
 * the operator observation this witness exists to resolve (see check 5).
 */

const fs = require('fs');
const path = require('path');
const readline = require('readline');
const { spawn, spawnSync } = require('child_process');
const playwright = require('playwright');
const {
  createHarnessCleanup,
  resolveServePort,
  startLocalHttpServer,
} = require('./lib/local-browser-harness.cjs');
const { enforcePolicy, DEFAULT_OUT_ROOT } = require('./_path-policy.cjs');

const W = require(path.join(__dirname, '..', 'hicasso', 'testbed', 'native-ime-witness.cjs'));

const IMPL_ROOT = path.resolve(__dirname, '..');
const BUILD_ID = 'hicasso/testbed';
const ROOT = enforcePolicy(
  'HICASSO_TESTBED_ROOT',
  path.join(IMPL_ROOT, 'out', 'hicasso-testbed'),
  { allowedRoots: [DEFAULT_OUT_ROOT] },
);
const HTML_SRC = path.join(IMPL_ROOT, 'hicasso', 'testbed', 'index.html');
const DRIVER_PS1 = path.join(__dirname, 'lib', 'windows-ime-driver.ps1');

const DEFAULT_PORT = 8066;
const READY_TIMEOUT_MS = 30000;
const NAV_TIMEOUT_MS = 60000;
const MOUNT_TIMEOUT_MS = 60000;
// The testbed's armed edges fire at 5s (`hicasso_testbed.core/arm-delay-ms`).
// The ceiling here is that plus slack, and it is a CEILING rather than a
// sleep: the wait polls the on-screen `armed` readout and returns the moment
// it goes idle.
const ARM_FIRE_TIMEOUT_MS = 9000;
const ALL_ENGINES = ['chromium', 'firefox', 'webkit'];

// ---------------------------------------------------------------------------
// Arguments
// ---------------------------------------------------------------------------

function parseArgs(argv) {
  const args = {
    mode: 'dry-run', engines: ALL_ENGINES.slice(), port: DEFAULT_PORT,
    keyDelayMs: 80, keepOpen: false,
  };
  for (const a of argv) {
    if (a === '--dry-run') args.mode = 'dry-run';
    else if (a === '--inject') args.mode = 'inject';
    else if (a === '--self-test') args.mode = 'self-test';
    else if (a === '--keep-open') args.keepOpen = true;
    else if (a.startsWith('--engines=')) {
      const wanted = a.slice('--engines='.length).split(',').map((s) => s.trim());
      args.engines = ALL_ENGINES.filter((e) => wanted.includes(e));
      args.unknownEngines = wanted.filter((e) => !ALL_ENGINES.includes(e));
    } else if (a.startsWith('--port=')) args.port = Number(a.slice('--port='.length));
    else if (a.startsWith('--key-delay=')) args.keyDelayMs = Number(a.slice('--key-delay='.length));
    else args.bad = (args.bad || []).concat(a);
  }
  return args;
}

// ---------------------------------------------------------------------------
// The OS driver, as a client
// ---------------------------------------------------------------------------

class ImeDriver {
  constructor(child) {
    this.child = child;
    this.pending = new Map();
    this.seq = 0;
    this.ready = new Promise((resolve) => { this.resolveReady = resolve; });
    const rl = readline.createInterface({ input: child.stdout });
    rl.on('line', (line) => this.onLine(line));
    child.stderr.on('data', (d) => process.stderr.write(`[ime-driver] ${d}`));
  }

  onLine(line) {
    const m = /^(\S+) (OK|ERR|REFUSED|READY)\s?(.*)$/.exec(line.trim());
    if (!m) return;
    const [, id, status, body] = m;
    let payload = null;
    if (body) { try { payload = JSON.parse(body); } catch { payload = body; } }
    if (status === 'READY') { this.resolveReady(payload); return; }
    const p = this.pending.get(id);
    if (!p) return;
    this.pending.delete(id);
    p({ status, payload });
  }

  send(verb, ...args) {
    const id = `r${++this.seq}`;
    return new Promise((resolve) => {
      this.pending.set(id, resolve);
      this.child.stdin.write(`${id} ${verb} ${args.join(' ')}\n`);
    });
  }

  /** As `send`, but a non-OK reply throws — for the verbs a run cannot proceed without. */
  async require(verb, ...args) {
    const r = await this.send(verb, ...args);
    if (r.status !== 'OK') {
      throw new Error(`${verb} ${args.join(' ')} -> ${r.status} ${JSON.stringify(r.payload)}`);
    }
    return r.payload;
  }

  async quit() {
    try { await this.send('QUIT'); } catch { /* the pipe may already be gone */ }
    this.child.stdin.end();
  }
}

const b64 = (s) => Buffer.from(s, 'utf16le').toString('base64');

function startDriver({ armed }) {
  const pwsh = process.env.RF2_PWSH || 'pwsh';
  const argv = ['-NoProfile', '-NonInteractive', '-ExecutionPolicy', 'Bypass',
    '-File', DRIVER_PS1];
  if (armed) argv.push('-Armed');
  const child = spawn(pwsh, argv, { stdio: ['pipe', 'pipe', 'pipe'] });
  return new ImeDriver(child);
}

// ---------------------------------------------------------------------------
// Mutation teeth — the verdict logic, proven able to fail before a browser or
// a keyboard is anywhere near this run.
// ---------------------------------------------------------------------------

const REAL = {
  compositionstart: 1, compositionupdate: 4, compositionend: 1,
  compositionendData: '日本語', composingInputs: 4, settledInputs: 0,
  processKeydowns: 8, escapeKeydowns: 0,
};
const NOT_REAL = {
  compositionstart: 0, compositionupdate: 0, compositionend: 0,
  compositionendData: null, composingInputs: 0, settledInputs: 7,
  processKeydowns: 0, escapeKeydowns: 1,
};

function runMutationTeeth() {
  const teeth = [];
  const bite = (name, fn) => {
    let ok = false;
    try { ok = fn(); } catch (err) { throw new Error(`TOOTH THREW: ${name}: ${err.message}`); }
    if (!ok) throw new Error(`MUTATION TOOTH DID NOT BITE: ${name}`);
    teeth.push(name);
  };
  const v = (r) => r.verdict;

  // --- check 5, the priority case, and its three outcomes ---
  bite('an aborted real exchange that returns to the committed value ticks', () =>
    v(W.abortVerdict({
      before: { value: 'abcにほんご', committed: '"abc"', edits: 5 },
      after: { value: 'abc', committed: '"abc"', edits: 5 },
      evidence: REAL, seed: 'abc',
    })) === W.TICK);

  bite('a real exchange whose ESC discarded nothing CROSSES, and names the operator observation', () => {
    const r = W.abortVerdict({
      before: { value: 'abcにほんご', committed: '"abcにほんご"', edits: 5 },
      after: { value: 'abcにほんご', committed: '"abcにほんご"', edits: 5 },
      evidence: REAL, seed: 'abc',
    });
    return r.verdict === W.CROSS && r.why.includes('2026-08-11');
  });

  // The whole reason this file is not a list of assertions: the SAME
  // observation, with no composition behind it, must not read as a defect.
  bite('the same end-state with NO composition is INCONCLUSIVE, not a cross', () => {
    const r = W.abortVerdict({
      before: { value: 'abcnihongo', committed: '"abcnihongo"', edits: 7 },
      after: { value: 'abcnihongo', committed: '"abcnihongo"', edits: 7 },
      evidence: NOT_REAL, seed: 'abc',
    });
    return r.verdict === W.INCONCLUSIVE && r.why.includes('IME was not engaged');
  });

  bite('arrivals going backwards across an abort crosses', () =>
    v(W.abortVerdict({
      before: { value: 'abcにほんご', committed: '"abc"', edits: 5 },
      after: { value: 'abc', committed: '"abc"', edits: 3 },
      evidence: REAL, seed: 'abc',
    })) === W.CROSS);

  // --- check 1 ---
  bite('a draft standing over a refusing model ticks', () =>
    v(W.draftVisibleVerdict({
      during: { value: '123にほんご', committed: '"123"', edits: 5 },
      evidence: REAL, seed: '123',
    })) === W.TICK);

  bite('a field snapped back to the committed value mid-composition crosses', () =>
    v(W.draftVisibleVerdict({
      during: { value: '123', committed: '"123"', edits: 5 },
      evidence: REAL, seed: '123',
    })) === W.CROSS);

  // --- check 2 ---
  bite('a draft at the caret with the caret inside it ticks', () =>
    v(W.caretVerdict({
      during: { value: 'AにほんごBC', selectionStart: 5, selectionEnd: 5 },
      evidence: REAL, seed: 'ABC', caretAt: 1,
    })) === W.TICK);

  bite('the end-of-string jump crosses', () =>
    v(W.caretVerdict({
      during: { value: 'ABCにほんご', selectionStart: 8, selectionEnd: 8 },
      evidence: REAL, seed: 'ABC', caretAt: 1,
    })) === W.CROSS);

  bite('a draft at the caret with the caret OUTSIDE it crosses', () =>
    v(W.caretVerdict({
      during: { value: 'AにほんごBC', selectionStart: 7, selectionEnd: 7 },
      evidence: REAL, seed: 'ABC', caretAt: 1,
    })) === W.CROSS);

  // --- check 3 ---
  bite('climbing arrivals under an unmoved model tick', () =>
    v(W.survivesModelVerdict({
      early: { value: '123にほ', committed: '"123"', edits: 2 },
      late: { value: '123にほんご', committed: '"123"', edits: 5 },
      evidence: REAL, seed: '123',
    })) === W.TICK);

  bite('a snap-back mid-exchange crosses', () =>
    v(W.survivesModelVerdict({
      early: { value: '123にほ', committed: '"123"', edits: 2 },
      late: { value: '123', committed: '"123"', edits: 5 },
      evidence: REAL, seed: '123',
    })) === W.CROSS);

  // A stalled exchange is not a passing one. Without this the section could
  // read green on a run in which nothing was typed after the first reading.
  bite('arrivals that never moved are INCONCLUSIVE, not a tick', () =>
    v(W.survivesModelVerdict({
      early: { value: '123にほ', committed: '"123"', edits: 2 },
      late: { value: '123にほ', committed: '"123"', edits: 2 },
      evidence: REAL, seed: '123',
    })) === W.INCONCLUSIVE);

  // --- check 4 ---
  bite('a refusing field snapping to its committed value at the commit ticks', () =>
    v(W.commitEchoVerdict({
      after: { value: '123', committed: '"123"', edits: 9 },
      evidence: REAL, expected: '123', policy: 'refusing',
    })) === W.TICK);

  bite('a refusing field that KEPT the composition crosses', () =>
    v(W.commitEchoVerdict({
      after: { value: '123日本語', committed: '"123"', edits: 9 },
      evidence: REAL, expected: '123', policy: 'refusing',
    })) === W.CROSS);

  bite('an accepting field whose screen and store disagree crosses', () =>
    v(W.commitEchoVerdict({
      after: { value: 'abc日本語', committed: '"abc"', edits: 9 },
      evidence: REAL, expected: 'abc日本語', policy: 'accepting',
    })) === W.CROSS);

  bite('a commit that never closed is INCONCLUSIVE', () =>
    v(W.commitEchoVerdict({
      after: { value: '123', committed: '"123"', edits: 9 },
      evidence: { ...REAL, compositionend: 0 }, expected: '123', policy: 'refusing',
    })) === W.INCONCLUSIVE);

  // --- check 6 ---
  bite('a close that adds no arrival ticks', () =>
    v(W.commitAddsNoIntentVerdict({
      before: { value: 'abcにほんご', committed: '"abcにほんご"', edits: 5 },
      after: { value: 'abc日本語', committed: '"abc日本語"', edits: 5 },
      evidence: REAL,
    })) === W.TICK);

  bite('a close that adds an arrival crosses and reports the delta', () => {
    const r = W.commitAddsNoIntentVerdict({
      before: { value: 'abcにほんご', committed: '"abcにほんご"', edits: 5 },
      after: { value: 'abc日本語', committed: '"abc日本語"', edits: 6 },
      evidence: REAL,
    });
    return r.verdict === W.CROSS && r.why.includes('added 1 intent');
  });

  bite('a doubled commit crosses', () =>
    v(W.commitAddsNoIntentVerdict({
      before: { value: 'にほんご', committed: '"にほんご"', edits: 5 },
      after: { value: '日本語日本語', committed: '"日本語日本語"', edits: 5 },
      evidence: REAL,
    })) === W.CROSS);

  // --- check 7 ---
  bite('a draft standing across a mid-exchange revision bump ticks', () =>
    v(W.revisionDefersVerdict({
      duringDraft: { value: '42にほんご' }, afterFire: { value: '42にほんご' },
      evidence: REAL, seed: '42', armedFired: true,
    })) === W.TICK);

  bite('a reset that landed immediately crosses', () =>
    v(W.revisionDefersVerdict({
      duringDraft: { value: '42にほんご' }, afterFire: { value: '42' },
      evidence: REAL, seed: '42', armedFired: true,
    })) === W.CROSS);

  bite('an arm that never fired is INCONCLUSIVE, not a tick', () =>
    v(W.revisionDefersVerdict({
      duringDraft: { value: '42にほんご' }, afterFire: { value: '42にほんご' },
      evidence: REAL, seed: '42', armedFired: false,
    })) === W.INCONCLUSIVE);

  // --- check 8 ---
  bite('a clean blur and a fired unmount tick', () =>
    v(W.teardownVerdict({
      afterBlur: { value: '9' }, afterUnmount: { gone: true },
      evidence: REAL, seed: '9', pageErrors: [],
    })) === W.TICK);

  bite('a stranded draft after blur crosses', () =>
    v(W.teardownVerdict({
      afterBlur: { value: '9にほんご' }, afterUnmount: { gone: true },
      evidence: REAL, seed: '9', pageErrors: [],
    })) === W.CROSS);

  bite('a page error during teardown crosses even with a clean field', () =>
    v(W.teardownVerdict({
      afterBlur: { value: '9' }, afterUnmount: { gone: true },
      evidence: REAL, seed: '9', pageErrors: ['boom'],
    })) === W.CROSS);

  // --- the roster and the summary: fail-closed in every direction ---
  bite('the roster is the eight checks, uniquely identified', () => {
    const ids = new Set(W.CHECKS.map((c) => c.id));
    const ns = new Set(W.CHECKS.map((c) => c.n));
    return W.CHECKS.length === 8 && ids.size === 8 && ns.size === 8
      && [1, 2, 3, 4, 5, 6, 7, 8].every((n) => ns.has(n));
  });

  bite('check 5 runs FIRST and is the marked priority case', () =>
    W.CHECKS[0].n === 5 && W.CHECKS[0].priority === true);

  bite('every check carries a non-empty key plan', () =>
    W.CHECKS.every((c) => Array.isArray(c.plan) && c.plan.length > 0));

  const full = W.CHECKS.map((c) => ({ id: c.id, verdict: W.TICK }));
  bite('a full clean run is complete', () => W.summarise(full).complete === true);

  bite('a MISSING check is not a passing one', () => {
    const s = W.summarise(full.slice(1));
    return s.complete === false && s.missing.length === 1;
  });

  bite('an INCONCLUSIVE check makes the run incomplete', () => {
    const s = W.summarise(full.map((r, i) => (i === 3 ? { ...r, verdict: W.INCONCLUSIVE } : r)));
    return s.complete === false && s.inconclusive.length === 1;
  });

  bite('a check nobody rostered reds', () =>
    W.summarise(full.concat([{ id: 'invented', verdict: W.TICK }])).complete === false);

  // A crossed run IS complete — a cross is a finding, not a failure to
  // measure — but it must never be recorded as verified.
  bite('a crossed run is complete but not verified', () => {
    const s = W.summarise(full.map((r, i) => (i === 2 ? { ...r, verdict: W.CROSS } : r)));
    const cell = W.dispositionCell(s, { engine: 'firefox', build: 'firefox-1511', date: '2026-08-11' });
    return s.complete === true && !cell.includes('Witness-verified') && cell.includes('Divergence');
  });

  bite('an INCOMPLETE run cannot be recorded as verified', () => {
    const s = W.summarise(full.slice(1));
    const cell = W.dispositionCell(s, { engine: 'webkit', build: 'webkit-2272', date: '2026-08-11' });
    return !cell.includes('Witness-verified') && cell.includes('Not established');
  });

  bite('only an all-tick run is recorded as verified', () => {
    const cell = W.dispositionCell(W.summarise(full),
      { engine: 'firefox', build: 'firefox-1511', date: '2026-08-11' });
    return cell.includes('Witness-verified') && cell.includes('firefox-1511');
  });

  return teeth;
}

// ---------------------------------------------------------------------------
// Build and serve — the same three steps the synthetic gate performs, and the
// same three the manual session did by hand.
// ---------------------------------------------------------------------------

function compile() {
  const runner = require.resolve('shadow-cljs/cli/runner.js', { paths: [IMPL_ROOT] });
  console.log(`> shadow-cljs compile ${BUILD_ID}`);
  const result = spawnSync(process.execPath, [runner, 'compile', BUILD_ID], {
    cwd: IMPL_ROOT, env: process.env, shell: false, stdio: 'inherit',
  });
  if (result.error) throw result.error;
  if (result.status !== 0) {
    throw new Error(`shadow-cljs compile ${BUILD_ID} exited ${result.status}`);
  }
}

function stageHtml() {
  fs.mkdirSync(ROOT, { recursive: true });
  fs.copyFileSync(HTML_SRC, path.join(ROOT, 'index.html'));
}

// ---------------------------------------------------------------------------
// Page helpers
// ---------------------------------------------------------------------------

const sleep = (ms) => new Promise((r) => setTimeout(r, ms));

async function armedReadout(page) {
  return page.evaluate(() => {
    const el = document.querySelector('[data-testid="armed"]');
    return el ? el.textContent : null;
  });
}

/**
 * Wait for an armed edge to FIRE, by polling the readout it publishes rather
 * than sleeping for the delay. Returns false on the ceiling — an arm that
 * never fired makes its check INCONCLUSIVE, never a tick.
 */
async function waitForArmToFire(page, timeoutMs = ARM_FIRE_TIMEOUT_MS) {
  const deadline = Date.now() + timeoutMs;
  while (Date.now() < deadline) {
    if ((await armedReadout(page)) === 'idle') return true;
    await sleep(150);
  }
  return false;
}

async function cell(page, field) {
  const [f, trace] = await Promise.all([W.readField(page, field), W.readTrace(page)]);
  return {
    value: f ? f.value : null,
    selectionStart: f ? f.selectionStart : null,
    committed: trace[field] ? trace[field].committed : null,
    edits: trace[field] ? trace[field].edits : null,
  };
}

// The READBACK line, printed under every check. It is not decoration: a
// verdict that short-circuits on an unmet premise says nothing about whether
// the DOM reads actually returned anything, and a rehearsal in which every
// selector silently resolved to `null` would print the same eight
// INCONCLUSIVEs as one in which they all worked. This is the line that
// separates them.
const fmtCell = (c) => `field=${JSON.stringify(c.value)} trace=${c.committed} ` +
  `edits=${c.edits}${c.selectionStart === null ? '' : ` caret=${c.selectionStart}`}`;
const fmtEv = (e) => `cs=${e.compositionstart} cu=${e.compositionupdate} ` +
  `ce=${e.compositionend} composingIn=${e.composingInputs} settledIn=${e.settledInputs} ` +
  `process=${e.processKeydowns} esc=${e.escapeKeydowns}`;

// ---------------------------------------------------------------------------
// The checks, as they are actually driven
// ---------------------------------------------------------------------------

const RUNNERS = {
  async 'escape-abort-mid-composition'(ctx) {
    const { page, keys } = ctx;
    await page.focus('[data-testid="plain"]');
    await W.resetEvents(page);
    await keys(W.KEY_PLANS.compose);
    const before = await cell(page, 'plain');
    await keys(W.KEY_PLANS.abort);
    const after = await cell(page, 'plain');
    const evidence = W.compositionEvidence(await W.readEvents(page), 'plain');
    return {
      ...W.abortVerdict({ before, after, evidence, seed: W.SEEDS.plain }),
      evidence: { before, after, ...evidence },
      readback: `before-ESC ${fmtCell(before)} | after-ESC ${fmtCell(after)} | ${fmtEv(evidence)}`,
    };
  },

  async 'draft-visible-during-composition'(ctx) {
    const { page, keys } = ctx;
    await page.focus('[data-testid="digits"]');
    await W.resetEvents(page);
    await keys(W.KEY_PLANS.compose);
    const during = await cell(page, 'digits');
    const evidence = W.compositionEvidence(await W.readEvents(page), 'digits');
    await keys(W.KEY_PLANS.abort);
    return {
      ...W.draftVisibleVerdict({ during, evidence, seed: W.SEEDS.digits }),
      evidence: { during, ...evidence },
      readback: `during ${fmtCell(during)} | ${fmtEv(evidence)}`,
    };
  },

  async 'caret-correct'(ctx) {
    const { page, keys } = ctx;
    await page.focus('[data-testid="upper"]');
    // Between A and B. Placed programmatically because a click that placed
    // it would be a pointer-down, and the OS keystrokes that follow must
    // arrive at a field whose caret is where this check says it is.
    await page.evaluate(() => {
      const el = document.querySelector('[data-testid="upper"]');
      el.setSelectionRange(1, 1);
    });
    await W.resetEvents(page);
    await keys(W.KEY_PLANS.compose);
    const during = await cell(page, 'upper');
    const evidence = W.compositionEvidence(await W.readEvents(page), 'upper');
    await keys(W.KEY_PLANS.abort);
    return {
      ...W.caretVerdict({ during, evidence, seed: W.SEEDS.upper, caretAt: 1 }),
      evidence: { during, ...evidence },
      readback: `during ${fmtCell(during)} | ${fmtEv(evidence)}`,
    };
  },

  async 'draft-survives-the-model'(ctx) {
    const { page, keys } = ctx;
    await page.focus('[data-testid="digits"]');
    await W.resetEvents(page);
    await keys(W.KEY_PLANS.composeShort);
    const early = await cell(page, 'digits');
    await keys(W.KEY_PLANS.composeRest);
    const late = await cell(page, 'digits');
    const evidence = W.compositionEvidence(await W.readEvents(page), 'digits');
    await keys(W.KEY_PLANS.abort);
    return {
      ...W.survivesModelVerdict({ early, late, evidence, seed: W.SEEDS.digits }),
      evidence: { early, late, ...evidence },
      readback: `early ${fmtCell(early)} | late ${fmtCell(late)} | ${fmtEv(evidence)}`,
    };
  },

  // Two policies, one law — so this check drives both and takes the worse
  // verdict. A refusing field that echoed the composition and an accepting
  // one that lost it are the same defect seen from either side.
  async 'commit-echo'(ctx) {
    const { page, keys, reload } = ctx;
    await page.focus('[data-testid="digits"]');
    await W.resetEvents(page);
    await keys([...W.KEY_PLANS.compose, ...W.KEY_PLANS.selectCandidate, ...W.KEY_PLANS.commit]);
    const afterDigits = await cell(page, 'digits');
    const evDigits = W.compositionEvidence(await W.readEvents(page), 'digits');
    const refusing = W.commitEchoVerdict({
      after: afterDigits, evidence: evDigits, expected: W.SEEDS.digits, policy: 'refusing',
    });

    await reload();
    await page.focus('[data-testid="plain"]');
    await W.resetEvents(page);
    await keys([...W.KEY_PLANS.compose, ...W.KEY_PLANS.selectCandidate, ...W.KEY_PLANS.commit]);
    const afterPlain = await cell(page, 'plain');
    const evPlain = W.compositionEvidence(await W.readEvents(page), 'plain');
    const accepting = W.commitEchoVerdict({
      after: afterPlain, evidence: evPlain, expected: afterPlain.value, policy: 'accepting',
    });

    return {
      ...worst([refusing, accepting]),
      evidence: { digits: { ...afterDigits, ...evDigits }, plain: { ...afterPlain, ...evPlain } },
      readback: `digits ${fmtCell(afterDigits)} ${fmtEv(evDigits)} | `
        + `plain ${fmtCell(afterPlain)} ${fmtEv(evPlain)}`,
    };
  },

  async 'compositionend-adds-no-intent'(ctx) {
    const { page, keys } = ctx;
    await page.focus('[data-testid="plain"]');
    await W.resetEvents(page);
    // Compose AND select the candidate first: the Space that opens the
    // candidate window is itself a composing update, so the reading either
    // side of the close has to be taken around the ENTER alone.
    await keys([...W.KEY_PLANS.compose, ...W.KEY_PLANS.selectCandidate]);
    const before = await cell(page, 'plain');
    await keys(W.KEY_PLANS.commit);
    const after = await cell(page, 'plain');
    const evidence = W.compositionEvidence(await W.readEvents(page), 'plain');
    return {
      ...W.commitAddsNoIntentVerdict({ before, after, evidence }),
      evidence: { before, after, ...evidence },
      readback: `before-ENTER ${fmtCell(before)} | after-ENTER ${fmtCell(after)} | ${fmtEv(evidence)}`,
    };
  },

  async 'revision-reset-mid-composition'(ctx) {
    const { page, keys } = ctx;
    // Armed, not clicked-during: a real pointer-down closes the composition
    // before the click can land inside it. A PROGRAMMATIC click here would
    // not, but the arm is what the operator's own instrument is, and driving
    // the same path is what makes this run comparable to the manual one.
    await page.click('[data-testid="arm-bump"]');
    const readout = await armedReadout(page);
    await page.focus('[data-testid="revision-strict"]');
    await W.resetEvents(page);
    await keys(W.KEY_PLANS.compose);
    const duringDraft = await cell(page, 'revision-strict');
    const armedFired = await waitForArmToFire(page);
    const afterFire = await cell(page, 'revision-strict');
    const evidence = W.compositionEvidence(await W.readEvents(page), 'revision-strict');
    await keys(W.KEY_PLANS.abort);
    return {
      ...W.revisionDefersVerdict({
        duringDraft, afterFire, evidence, seed: W.SEEDS['revision-strict'], armedFired,
      }),
      evidence: { readout, duringDraft, afterFire, armedFired, ...evidence },
      readback: `armed=${JSON.stringify(readout)} fired=${armedFired} | `
        + `during ${fmtCell(duringDraft)} | after-fire ${fmtCell(afterFire)} | ${fmtEv(evidence)}`,
    };
  },

  async 'blur-and-unmount-mid-composition'(ctx) {
    const { page, keys, reload, pageErrors } = ctx;
    await page.focus('[data-testid="mountable"]');
    await W.resetEvents(page);
    await keys(W.KEY_PLANS.compose);
    await page.evaluate(() => {
      const el = document.activeElement;
      if (el && el.blur) el.blur();
    });
    const afterBlur = await cell(page, 'mountable');
    const evidence = W.compositionEvidence(await W.readEvents(page), 'mountable');

    await reload();
    await page.click('[data-testid="arm-unmount"]');
    const readout = await armedReadout(page);
    await page.focus('[data-testid="mountable"]');
    await W.resetEvents(page);
    await keys(W.KEY_PLANS.compose);
    const armedFired = await waitForArmToFire(page);
    const gone = await page.evaluate(() =>
      document.querySelector('[data-testid="mountable-gone"]') !== null);
    const evUnmount = W.compositionEvidence(await W.readEvents(page), 'mountable');
    return {
      ...W.teardownVerdict({
        afterBlur, afterUnmount: { gone }, evidence, seed: W.SEEDS.mountable,
        pageErrors: pageErrors.slice(),
      }),
      evidence: { afterBlur, readout, armedFired, gone, blur: evidence, unmount: evUnmount },
      readback: `after-blur ${fmtCell(afterBlur)} ${fmtEv(evidence)} | `
        + `armed=${JSON.stringify(readout)} fired=${armedFired} unmounted=${gone} `
        + `${fmtEv(evUnmount)}`,
    };
  },
};

/** INCONCLUSIVE beats CROSS beats TICK — the fail-closed fold. */
function worst(verdicts) {
  const inc = verdicts.find((r) => r.verdict === W.INCONCLUSIVE);
  if (inc) return inc;
  const cross = verdicts.find((r) => r.verdict === W.CROSS);
  if (cross) return cross;
  return { verdict: W.TICK, why: verdicts.map((r) => r.why).join(' AND ') };
}

// ---------------------------------------------------------------------------
// One engine
// ---------------------------------------------------------------------------

async function driveEngine(engine, baseUrl, driver, args) {
  const inject = args.mode === 'inject';
  console.log(`\n=================== ${engine.toUpperCase()} ===================`);
  const browser = await playwright[engine].launch({ headless: false });
  const build = browser.version();
  const pageErrors = [];
  const results = [];
  let hwnd = null;
  let imeState = null;
  try {
    const context = await browser.newContext();
    const page = await context.newPage();
    page.on('pageerror', (err) => pageErrors.push(err.message));

    await page.addInitScript(W.pageObserver);
    await page.goto(baseUrl, { waitUntil: 'commit', timeout: NAV_TIMEOUT_MS });
    await page.waitForSelector('[data-testid="hicasso-controlled-testbed"]',
      { timeout: MOUNT_TIMEOUT_MS });

    // A nonce in the document title is how the OS side finds THIS window.
    // Matching on "Hicasso" would match this terminal, an editor with the
    // file open, or a second engine still on screen.
    const nonce = `RF2-IME-${engine}-${Date.now().toString(36)}`;
    await page.evaluate((t) => { document.title = t; }, nonce);
    await sleep(400);
    const found = await driver.send('FIND', b64(nonce));
    if (found.status !== 'OK') {
      throw new Error(
        `could not find the ${engine} window by title nonce "${nonce}": ` +
        `${JSON.stringify(found.payload)}. Nothing was typed.`);
    }
    hwnd = found.payload.hwnd;
    console.log(`> window: hwnd=${hwnd} matches=${found.payload.matches} ` +
      `title=${JSON.stringify(found.payload.title)}`);
    if (found.payload.matches > 1) {
      throw new Error(
        `${found.payload.matches} windows answer to the nonce; refusing to ` +
        'type into a window this run did not uniquely identify.');
    }

    if (inject) {
      await driver.require('FOREGROUND', hwnd);
      await driver.require('IMEON', hwnd);
      imeState = await driver.require('IMESTATE', hwnd);
      console.log(`> IME: ${JSON.stringify(imeState)}`);
      if (!imeState.japanese) {
        throw new Error(
          `the ${engine} window's input locale is ${imeState.langid}, not 0x0411. ` +
          'The Japanese IME did not engage, so every keystroke below would be ' +
          'plain ASCII and every check would be measuring nothing. Switch the ' +
          'input method for that window by hand (Win+Space) and re-run, or ' +
          'install the Japanese IME first — Settings > Time & Language > ' +
          'Language & region > Add a language > 日本語.');
      }
    } else {
      imeState = await driver.require('IMESTATE', hwnd);
      console.log(`> IME (read-only, rehearsal): ${JSON.stringify(imeState)}`);
    }

    const reload = async () => {
      await page.reload({ waitUntil: 'commit', timeout: NAV_TIMEOUT_MS });
      await page.waitForSelector('[data-testid="hicasso-controlled-testbed"]',
        { timeout: MOUNT_TIMEOUT_MS });
    };
    const keys = async (tokens) => {
      const r = await driver.send('KEYS', hwnd, tokens.join(','), args.keyDelayMs);
      if (r.status === 'ERR') {
        throw new Error(`KEYS aborted: ${JSON.stringify(r.payload)}`);
      }
      return r;
    };

    for (const check of W.CHECKS) {
      await reload();
      const label = `check ${check.n} — ${check.title}`;
      let out;
      try {
        out = await RUNNERS[check.id]({ page, keys, reload, pageErrors, engine });
      } catch (err) {
        out = { verdict: W.INCONCLUSIVE, why: `the check could not be driven: ${err.message}` };
      }
      results.push({ id: check.id, n: check.n, title: check.title, ...out });
      const mark = { TICK: '[x]', CROSS: '[!]', INCONCLUSIVE: '[ ]' }[out.verdict];
      console.log(`${mark} ${label}\n      ${out.why}`);
      if (out.readback) console.log(`      READBACK ${out.readback}`);
      if (check.priority) {
        console.log('      ^ PRIORITY CASE (the ruling\'s check 5, and the ' +
          'unresolved operator observation of 2026-08-11)');
      }
    }
  } finally {
    if (!args.keepOpen) await browser.close();
  }
  if (pageErrors.length > 0) {
    console.log(`> the page emitted ${pageErrors.length} uncaught error(s); ` +
      `first: ${pageErrors[0]}`);
  }
  return { engine, build, hwnd, imeState, results, pageErrors };
}

// ---------------------------------------------------------------------------

async function preflight(driver, args) {
  const problems = [];
  const notes = [];
  if (process.platform !== 'win32') {
    problems.push(`this witness drives the WINDOWS input stack; platform is ${process.platform}.`);
    return { problems, notes };
  }
  const ping = await driver.require('PING');
  notes.push(`PowerShell ${ping.psVersion} (pid ${ping.pid}), armed=${ping.armed}`);
  const layouts = await driver.require('LAYOUTS');
  notes.push(`installed input locales: ${layouts.langids.join(', ')}`);
  if (!layouts.japanese) {
    const msg =
      'NO JAPANESE INPUT LOCALE (0x0411) IS INSTALLED ON THIS MACHINE. ' +
      'Install it once — Settings > Time & Language > Language & region > ' +
      'Add a language > 日本語 — then set Microsoft IME to Hiragana. Without ' +
      'it the romaji below is seven ASCII letters and no composition ever ' +
      'starts.';
    if (args.mode === 'inject') problems.push(msg);
    else notes.push(`REHEARSAL ONLY: ${msg}`);
  }
  // Every key plan spelled against the DRIVER'S OWN vocabulary, so a typo'd
  // token is a preflight failure rather than a keystroke silently not sent
  // in the middle of a composition.
  for (const check of W.CHECKS) {
    const r = await driver.send('RESOLVE', check.plan.join(','));
    if (r.status !== 'OK') {
      problems.push(`check ${check.n} (${check.id}) has an unsendable key plan: ` +
        `${JSON.stringify(r.payload)}`);
    }
  }
  notes.push(`all ${W.CHECKS.length} key plans resolve against the driver's vocabulary`);
  return { problems, notes };
}

function reportEngine(run, date) {
  const summary = W.summarise(run.results);
  console.log(`\n--- ${run.engine} (${run.build}) ---`);
  console.log(`  ticks: ${summary.ticks}  crosses: ${summary.crosses.length}  ` +
    `inconclusive: ${summary.inconclusive.length}  complete: ${summary.complete}`);
  console.log(`  dispositions.md §2.3 cell: ${W.dispositionCell(summary,
    { engine: run.engine, build: run.build, date })}`);
  return summary;
}

async function main() {
  const args = parseArgs(process.argv.slice(2));
  if (args.bad) {
    console.error(`unknown argument(s): ${args.bad.join(' ')}`);
    return 2;
  }
  if (args.unknownEngines && args.unknownEngines.length > 0) {
    console.error(`unknown engine(s): ${args.unknownEngines.join(', ')}; ` +
      `known: ${ALL_ENGINES.join(', ')}`);
    return 2;
  }
  if (args.engines.length === 0) {
    console.error('--engines selected no engine');
    return 2;
  }

  const teeth = runMutationTeeth();
  console.log(`> verdict logic teeth bit: ${teeth.length}`);
  if (args.mode === 'self-test') {
    for (const t of teeth) console.log(`    - ${t}`);
    console.log('\nSELF-TEST PASS — verdict logic only. No browser, no server, ' +
      'no keystroke, and NOTHING about any engine has been observed.');
    return 0;
  }

  if (args.mode === 'inject') {
    console.log(
      '\n!!! ARMED RUN — this will bring each browser window to the FOREGROUND\n' +
      '!!! and send OS-level keystrokes to it. Do not touch the keyboard or\n' +
      '!!! switch windows until it finishes. Alt-tabbing aborts the batch.\n');
  } else {
    console.log('\n> REHEARSAL (--dry-run): the OS driver is started UNARMED, so every\n' +
      '> keystroke is REFUSED by the driver itself. Browser windows will open.\n');
  }

  const driver = startDriver({ armed: args.mode === 'inject' });
  const cleanup = createHarnessCleanup();
  cleanup.installSignalHandlers();
  let tearingDown = false;
  try {
    const boot = await driver.ready;
    console.log(`> ime driver ready: ${JSON.stringify(boot)}`);
    const { problems, notes } = await preflight(driver, args);
    for (const n of notes) console.log(`> ${n}`);
    if (problems.length > 0) {
      console.error('\nPREFLIGHT REFUSED THIS RUN:');
      for (const p of problems) console.error(`  - ${p}`);
      return 1;
    }

    compile();
    stageHtml();
    const port = await resolveServePort(args.port, {
      onFallback: (p, f) => console.log(`> port ${p} busy, serving on ${f}`),
    });
    const httpServerBin = require.resolve('http-server/bin/http-server', { paths: [IMPL_ROOT] });
    const server = await startLocalHttpServer({
      cleanup,
      httpServerBin,
      root: ROOT,
      port,
      cwd: IMPL_ROOT,
      readyTimeoutMs: READY_TIMEOUT_MS,
      suppressExitDiagnostic: () => tearingDown,
    });
    if (!server.ready) {
      console.error('the testbed server did not prove owned readiness');
      return 1;
    }
    const baseUrl = `http://127.0.0.1:${port}`;
    console.log(`> serving ${ROOT} at ${baseUrl}`);

    const date = new Date().toISOString().slice(0, 10);
    const runs = [];
    for (const engine of args.engines) {
      runs.push(await driveEngine(engine, baseUrl, driver, args));
    }

    console.log('\n=================== SUMMARY ===================');
    let incomplete = 0;
    for (const run of runs) {
      const s = reportEngine(run, date);
      if (!s.complete) incomplete += 1;
    }

    if (args.mode !== 'inject') {
      console.log(
        '\nREHEARSAL COMPLETE. Every page-side step of every check ran and every\n' +
        'read came back; no keystroke was sent, so no verdict above says\n' +
        'anything about any engine. Re-run with --inject to take the witness.');
      return 0;
    }

    console.log(
      '\nRecord the cells above in docs/design/hicasso/product/dispositions.md §2.3\n' +
      '(native-IME block), and file ONE BEAD PER CROSS with the engine name on it.\n' +
      'A cross is a finding, not a reason to re-run until it passes.');
    if (incomplete > 0) {
      console.error(`\nWITNESS INCOMPLETE — ${incomplete} engine(s) left a check ` +
        'undecided. The disposition cells cannot be filled from this run.');
      return 1;
    }
    console.log('\nWITNESS COMPLETE.');
    return 0;
  } finally {
    tearingDown = true;
    await driver.quit();
    await cleanup.cleanup();
  }
}

module.exports = { runMutationTeeth, parseArgs, worst, RUNNERS };

if (require.main === module) {
  main().then((code) => process.exit(code)).catch((error) => {
    console.error(error.stack || String(error));
    process.exit(1);
  });
}
