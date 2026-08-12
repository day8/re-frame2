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
 * window to the foreground, asks its IME to come up in Japanese, PROVES the
 * IME is composing by making it compose, and then types.
 *
 * ========================= WHY THE IME IS VERIFIED ========================
 *
 * `IMEON` posts `WM_INPUTLANGCHANGEREQUEST` and the IMM32 conversion-mode
 * messages. The modern Microsoft IME is a TSF text service reached through a
 * compatibility layer, and a given browser build need not honour any of it.
 * So the request is never trusted: `IMESTATE` reads the input locale of the
 * BROWSER WINDOW'S OWN THREAD afterwards, and if it is not `0x0411` the run
 * stops and asks the operator to switch with Win+Space rather than typing
 * seven ASCII letters into a text box and calling it a composition.
 *
 * ================== AND WHY IT IS VERIFIED BY CONDUCT =====================
 *
 * Two armed runs on 2026-08-12, and the second is why the gate below reads
 * the PAGE rather than the input stack.
 *
 * The first seized the foreground on all three engines and delivered both
 * the romaji and the ESC — and decided nothing: `langid 0x0411`,
 * `japanese: true`, `open: 0` everywhere. The IME had ignored
 * `IMC_SETOPENSTATUS`. The repair was to open it by its own toggle key
 * (`kanji`, 半角/全角) through `KEYS` — the door the keystrokes were
 * demonstrably arriving by — and to REQUIRE `open: 1` back from `IMESTATE`
 * before driving a plan.
 *
 * The second run returned `open: 1`, `conversion: 9`, `native: true` on
 * Chromium — engaged, by that gate — with `compositionstart` at ZERO on
 * every check and the romaji in the box as literal ASCII. And `conversion: 9`
 * is `IME_CMODE_HIRAGANA`, the exact constant this rig had just written.
 * THE IMM32 SHIM'S STATE IS WRITE-THROUGH: the gate read back the bit it had
 * itself set, so it proved a value had been written and nothing else. It
 * could not have failed — a fail-open sitting inside the guard against one.
 *
 * So that reading is now a LOG LINE, and engagement is decided by CONDUCT.
 * `probeComposition` types ONE romaji letter into the plain field and
 * requires `compositionstart > 0` off the page's own observer. It is the
 * gate precisely because that event cannot be produced by the thing under
 * test: the browser emits it in response to real composed input, and no
 * value this rig writes into an input context makes one appear. An engine
 * that will not compose is recorded NOT ENGAGED and nothing is typed to it.
 *
 * ================= A REFUSAL IS PER-ENGINE, NOT PER-BATCH =================
 *
 * On that same run Firefox read `open: 0` after `IMEON` and two toggles and
 * aborted with nothing typed. That was correct conduct — the abort is the
 * deliverable — but it was a THROW out of the engine loop, so WebKit never
 * launched at all: one engine's rig failure cost the run an engine it exists
 * to measure. Engagement failure is now caught at the engine boundary and
 * recorded as NOT ENGAGED for that engine — no check driven, no cell
 * fillable, exit non-zero — and the batch continues to the next engine.
 *
 * Between the ladder and that refusal sits a bounded OPERATOR-ASSIST WAIT:
 * up to ninety seconds in which the run prints what to press and polls by
 * re-running the conduct probe. NO KEYSTROKES ARE IN FLIGHT while it waits,
 * and the foreground is re-verified before every poll, so the operator
 * touching the browser window during that pause is what the rig asked for
 * rather than the interference the per-key interlock exists to catch.
 *
 * ====================== AND WHY DELIVERY IS VERIFIED ======================
 *
 * The same run turned up a SECOND rig failure wearing the first one's
 * clothes: WebKit received zero keystrokes for five of the eight checks
 * (`settledIn=0`, the ESC uncounted) and then came alive. "The keys never
 * arrived" and "the IME was closed" both end in a field full of nothing,
 * and no line of that output told them apart. So `probeDelivery` sends one
 * key and reads it back off the page before each plan is driven, and a
 * check whose probe never lands says KEYS ARE NOT ARRIVING in those words
 * instead of borrowing the IME's excuse.
 */

const fs = require('fs');
const path = require('path');
const readline = require('readline');
const { spawn, spawnSync } = require('child_process');
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

// 半角/全角 — the IME's own open/close toggle, sent through `KEYS` and so
// through `SendInput`. See "WHY THE IME IS VERIFIED" above: this is the path
// that demonstrably delivered keystrokes to the very IME that ignored
// `IMC_SETOPENSTATUS`.
const IME_TOGGLE_KEY = 'kanji';
// A toggle is handled by the IME's UI thread. Read too soon and any reading
// taken after it — including the page's — is of the state before the key.
const IME_SETTLE_MS = 500;
// THE CONDUCT PROBE. One romaji letter into the `plain` field, and a
// `compositionstart` off the page's own observer or the IME is not engaged.
// `n` is the first letter of `nihongo`, so a composing IME opens its exchange
// on it; an alphanumeric one puts an `n` in the box and fires nothing.
const CONDUCT_KEY = 'n';
const CONDUCT_FIELD = 'plain';
// A composition is opened by the IME's own UI thread and reaches the page as
// an event. Longer than the IMM32 settle above, because this one waits for a
// round trip through the browser rather than for a message queue.
const CONDUCT_SETTLE_MS = 600;
// The bounded operator-assist wait, and its poll interval. Ninety seconds is
// long enough to click into a window and press one key, and short enough that
// an unattended run cannot sit on the keyboard indefinitely.
const ASSIST_TIMEOUT_MS = 90000;
const ASSIST_POLL_MS = 6000;
// The delivery probe's key. Escape, for two reasons: the 2026-08-12 run
// PROVED it reaches the page, and with no field focused it mutates nothing —
// no text, no caret, no focus. Its keydown is read off the observer and then
// erased by the check's own `resetEvents`, so no verdict ever sees it.
const PROBE_KEY = 'escape';
const PROBE_SETTLE_MS = 150;

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
// The evidence PAIR a decisive edge is read from: OPEN is the reading taken
// immediately before the keystroke (a real exchange, not yet closed), CLOSED
// the one taken immediately after it. Feeding a verdict OPEN twice says the
// edge closed nothing; feeding it CLOSED twice says the exchange was already
// over when the edge arrived. Both are what audit #7896 found ticking.
const OPEN = { ...REAL, compositionend: 0, compositionendData: null };
const CLOSED = { ...REAL, compositionend: 1 };

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
      evBefore: OPEN, evAfter: CLOSED, seed: 'abc',
    })) === W.TICK);

  bite('a real exchange whose ESC discarded nothing CROSSES, and names the operator observation', () => {
    const r = W.abortVerdict({
      before: { value: 'abcにほんご', committed: '"abcにほんご"', edits: 5 },
      after: { value: 'abcにほんご', committed: '"abcにほんご"', edits: 5 },
      evBefore: OPEN, evAfter: CLOSED, seed: 'abc',
    });
    return r.verdict === W.CROSS && r.why.includes('2026-08-11');
  });

  // The whole reason this file is not a list of assertions: the SAME
  // observation, with no composition behind it, must not read as a defect.
  bite('the same end-state with NO composition is INCONCLUSIVE, not a cross', () => {
    const r = W.abortVerdict({
      before: { value: 'abcnihongo', committed: '"abcnihongo"', edits: 7 },
      after: { value: 'abcnihongo', committed: '"abcnihongo"', edits: 7 },
      evBefore: NOT_REAL, evAfter: NOT_REAL, seed: 'abc',
    });
    return r.verdict === W.INCONCLUSIVE && r.why.includes('IME was not engaged');
  });

  bite('arrivals going backwards across an abort crosses', () =>
    v(W.abortVerdict({
      before: { value: 'abcにほんご', committed: '"abc"', edits: 5 },
      after: { value: 'abc', committed: '"abc"', edits: 3 },
      evBefore: OPEN, evAfter: CLOSED, seed: 'abc',
    })) === W.CROSS);

  // #7896, THE FIRST OF THE TWO NEW REFUSALS. An aggregate reading could not
  // tell an exchange that was open when ESC arrived from one the IME had
  // already closed — so a field sitting at its committed value ticked either
  // way, and the tick said "the abort worked" about an abort that aborted
  // nothing. This exact input ticked before the repair.
  bite('a composition already CLOSED before the ESC cannot tick', () => {
    const r = W.abortVerdict({
      before: { value: 'abc', committed: '"abc"', edits: 5 },
      after: { value: 'abc', committed: '"abc"', edits: 5 },
      evBefore: CLOSED, evAfter: CLOSED, seed: 'abc',
    });
    return r.verdict === W.INCONCLUSIVE && r.why.includes('none was OPEN');
  });

  // The other half of the same premise: open at the ESC, and still open after
  // it. Whatever the field shows was not produced by an abort that never
  // reached the exchange.
  bite('an ESC that closed nothing cannot tick', () => {
    const r = W.abortVerdict({
      before: { value: 'abcにほんご', committed: '"abc"', edits: 5 },
      after: { value: 'abc', committed: '"abc"', edits: 5 },
      evBefore: OPEN, evAfter: OPEN, seed: 'abc',
    });
    return r.verdict === W.INCONCLUSIVE && r.why.includes('STILL open');
  });

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
      evBefore: OPEN, evAfter: CLOSED,
    })) === W.TICK);

  bite('a close that adds an arrival crosses and reports the delta', () => {
    const r = W.commitAddsNoIntentVerdict({
      before: { value: 'abcにほんご', committed: '"abcにほんご"', edits: 5 },
      after: { value: 'abc日本語', committed: '"abc日本語"', edits: 6 },
      evBefore: OPEN, evAfter: CLOSED,
    });
    return r.verdict === W.CROSS && r.why.includes('added 1 intent');
  });

  bite('a doubled commit crosses', () =>
    v(W.commitAddsNoIntentVerdict({
      before: { value: 'にほんご', committed: '"にほんご"', edits: 5 },
      after: { value: '日本語日本語', committed: '"日本語日本語"', edits: 5 },
      evBefore: OPEN, evAfter: CLOSED,
    })) === W.CROSS);

  // #7896 again, one edge along: the candidate-window Space can close the
  // exchange itself, and an aggregate `compositionend > 0` cannot tell that
  // close from the Enter's. A no-op Enter then ticked on the Space's
  // transition — "the close added no intent" said of a close that had already
  // happened. This exact input ticked before the repair.
  bite('an exchange the SPACE already closed cannot tick on the Enter', () => {
    const r = W.commitAddsNoIntentVerdict({
      before: { value: 'abc日本語', committed: '"abc日本語"', edits: 5 },
      after: { value: 'abc日本語', committed: '"abc日本語"', edits: 5 },
      evBefore: CLOSED, evAfter: CLOSED,
    });
    return r.verdict === W.INCONCLUSIVE && r.why.includes('none was OPEN');
  });

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
      evBlur: CLOSED, evUnmount: OPEN, seed: '9', pageErrors: [],
    })) === W.TICK);

  bite('a stranded draft after blur crosses', () =>
    v(W.teardownVerdict({
      afterBlur: { value: '9にほんご' }, afterUnmount: { gone: true },
      evBlur: CLOSED, evUnmount: OPEN, seed: '9', pageErrors: [],
    })) === W.CROSS);

  bite('a page error during teardown crosses even with a clean field', () =>
    v(W.teardownVerdict({
      afterBlur: { value: '9' }, afterUnmount: { gone: true },
      evBlur: CLOSED, evUnmount: OPEN, seed: '9', pageErrors: ['boom'],
    })) === W.CROSS);

  // #7896, THE SECOND OF THE TWO NEW REFUSALS, and the sharpest of them. The
  // check reloads between its two phases, so the blur phase's evidence is
  // about a page that no longer exists. Passing only that evidence made the
  // unmount half unfalsifiable: real blur evidence + a fired unmount + NO
  // composition whatever in the second phase returned a TICK for a check whose
  // entire subject is the word "mid-composition".
  bite('an unmount phase with no composition of its own cannot tick', () => {
    const r = W.teardownVerdict({
      afterBlur: { value: '9' }, afterUnmount: { gone: true },
      evBlur: CLOSED, evUnmount: NOT_REAL, seed: '9', pageErrors: [],
    });
    return r.verdict === W.INCONCLUSIVE && r.why.includes('UNMOUNT PHASE');
  });

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
    // Bracketed around the ESC and nothing else: the cells say what the field
    // did, the two evidence readings say the ESC is what did it.
    const before = await cell(page, 'plain');
    const evBefore = W.compositionEvidence(await W.readEvents(page), 'plain');
    await keys(W.KEY_PLANS.abort);
    const after = await cell(page, 'plain');
    const evAfter = W.compositionEvidence(await W.readEvents(page), 'plain');
    return {
      ...W.abortVerdict({ before, after, evBefore, evAfter, seed: W.SEEDS.plain }),
      evidence: { before, after, evBefore, evAfter },
      readback: `before-ESC ${fmtCell(before)} ${fmtEv(evBefore)} | `
        + `after-ESC ${fmtCell(after)} ${fmtEv(evAfter)}`,
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
    // side of the close has to be taken around the ENTER alone. The evidence
    // is bracketed the same way, which is what proves the close the verdict
    // reads belongs to the Enter and not to that Space.
    await keys([...W.KEY_PLANS.compose, ...W.KEY_PLANS.selectCandidate]);
    const before = await cell(page, 'plain');
    const evBefore = W.compositionEvidence(await W.readEvents(page), 'plain');
    await keys(W.KEY_PLANS.commit);
    const after = await cell(page, 'plain');
    const evAfter = W.compositionEvidence(await W.readEvents(page), 'plain');
    return {
      ...W.commitAddsNoIntentVerdict({ before, after, evBefore, evAfter }),
      evidence: { before, after, evBefore, evAfter },
      readback: `before-ENTER ${fmtCell(before)} ${fmtEv(evBefore)} | `
        + `after-ENTER ${fmtCell(after)} ${fmtEv(evAfter)}`,
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
    const evBlur = W.compositionEvidence(await W.readEvents(page), 'mountable');

    // PHASE TWO, on a page this reloads into: a fresh exchange, and the
    // unmount fired under it. The reload plus `resetEvents` is what makes
    // `evUnmount` this phase's own evidence rather than a running total, and
    // it is read BEFORE the arm fires — the claim is that the node went while
    // an exchange was live, so the reading has to be taken while it still is.
    await reload();
    await page.click('[data-testid="arm-unmount"]');
    const readout = await armedReadout(page);
    await page.focus('[data-testid="mountable"]');
    await W.resetEvents(page);
    await keys(W.KEY_PLANS.compose);
    const evUnmount = W.compositionEvidence(await W.readEvents(page), 'mountable');
    const armedFired = await waitForArmToFire(page);
    const gone = await page.evaluate(() =>
      document.querySelector('[data-testid="mountable-gone"]') !== null);
    return {
      ...W.teardownVerdict({
        afterBlur, afterUnmount: { gone }, evBlur, evUnmount, seed: W.SEEDS.mountable,
        pageErrors: pageErrors.slice(),
      }),
      evidence: { afterBlur, readout, armedFired, gone, blur: evBlur, unmount: evUnmount },
      readback: `after-blur ${fmtCell(afterBlur)} ${fmtEv(evBlur)} | `
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
// Getting the rig into a state worth measuring — and refusing when it will
// not go there. Both of these run BEFORE anything a verdict reads, and
// neither one may end in a shrug: an engine that cannot be engaged aborts,
// and a check whose keys are not arriving is not driven.
// ---------------------------------------------------------------------------

/** The IMM32 reading, for the log. Never a premise — see the header. */
const fmtIme = (s) => `open=${s.open} conversion=${s.conversion} ` +
  `native=${s.native} langid=${s.langid} isForeground=${s.isForeground}`;

/**
 * THE CONDUCT PROBE. Is this window's IME COMPOSING? Decided by making it
 * compose: one romaji letter into the `plain` field, and `compositionstart`
 * read back off the page's own observer.
 *
 * It is the gate because it is the one thing in this rig the thing under test
 * cannot fake. `IMC_GETOPENSTATUS` answers from an input context this process
 * writes to, and on 2026-08-12 it handed back the very bit that had just been
 * written while nothing composed. `compositionstart` is emitted by the BROWSER
 * in response to input a real IME actually consumed; no message this driver
 * can send produces one.
 *
 * Leaves the page as it found it: the letter is aborted with ESC and the
 * reload restores every field to its seed, so a probe run four times over is
 * four times the same probe rather than a field filling up with `nnnn`.
 */
async function probeComposition({ page, driver, hwnd, keyDelayMs, reload }) {
  const observing = await W.resetEvents(page);
  if (!observing) {
    // The same distinction `probeDelivery` insists on: nothing to read the
    // answer WITH is not the same as an answer of no.
    throw new Error('the page observer is not installed, so composition ' +
      'cannot be read; no engagement claim is possible for this engine');
  }
  await page.focus(`[data-testid="${CONDUCT_FIELD}"]`);
  const sent = await driver.send('KEYS', hwnd, CONDUCT_KEY, keyDelayMs);
  if (sent.status !== 'OK') {
    return {
      composed: false,
      detail: `the driver would not send the probe key: ${sent.status} ` +
        `${JSON.stringify(sent.payload)}`,
    };
  }
  await sleep(CONDUCT_SETTLE_MS);
  const ev = W.compositionEvidence(await W.readEvents(page), CONDUCT_FIELD);
  const field = await W.readField(page, CONDUCT_FIELD);
  await driver.send('KEYS', hwnd, W.KEY_PLANS.abort.join(','), keyDelayMs);
  await sleep(PROBE_SETTLE_MS);
  await reload();
  return {
    composed: ev.compositionstart > 0,
    detail: `one ${CONDUCT_KEY} into ${CONDUCT_FIELD} (scans ${sent.payload.scans}): ` +
      `cs=${ev.compositionstart} cu=${ev.compositionupdate} ` +
      `composingIn=${ev.composingInputs} settledIn=${ev.settledInputs} ` +
      `process=${ev.processKeydowns} field=${JSON.stringify(field ? field.value : null)}`,
  };
}

/**
 * The bounded operator-assist wait: the rig has run out of things it can do
 * about a closed IME, so it asks, and waits, and keeps asking the same
 * question it would have asked itself.
 *
 * THE OPERATOR TOUCHING THE BROWSER WINDOW HERE IS EXPECTED, NOT
 * CONTAMINATION. Nothing is in flight during the pause — the run sends no
 * keystroke except the probe's own letter, and it re-reads the foreground
 * immediately before each of those, so a poll that arrives while the operator
 * is somewhere else sends nothing at all rather than typing into wherever
 * they went. That is the opposite case to the per-key interlock in the
 * driver, which exists for focus stolen from an UNATTENDED batch.
 *
 * Returns the engaged reading, or `null` on the deadline.
 */
async function awaitOperatorEngagement({ driver, hwnd, engine, probe, timeoutMs }) {
  console.log(
    `\n!!! ${engine.toUpperCase()}: THE IME IS NOT COMPOSING, and this rig has run out\n` +
    '!!! of things it can do about it.\n' +
    '!!!\n' +
    '!!! PRESS 半角/全角 — or PICK ひらがな — IN THE BROWSER WINDOW NOW.\n' +
    '!!! Click into the page first if it needs the focus.\n' +
    '!!!\n' +
    `!!! This is a designed pause of up to ${Math.round(timeoutMs / 1000)}s and NOTHING IS IN FLIGHT\n` +
    '!!! during it: touching that window now is what the run is asking for.\n' +
    '!!! It polls by typing one letter and reading the page for a\n' +
    '!!! compositionstart, and it re-checks that the browser still holds the\n' +
    '!!! foreground before each poll.\n');
  const deadline = Date.now() + timeoutMs;
  while (Date.now() < deadline) {
    const left = Math.max(0, Math.round((deadline - Date.now()) / 1000));
    const ime = await driver.require('IMESTATE', hwnd);
    if (!ime.isForeground) {
      console.log(`> [${left}s left] the ${engine} window does not hold the ` +
        'foreground, so nothing was sent. Click into it.');
    } else {
      const conduct = await probe();
      console.log(`> [${left}s left] conduct probe: ${conduct.detail}`);
      if (conduct.composed) {
        const after = await driver.require('IMESTATE', hwnd);
        console.log(`> ${engine} IME ENGAGED BY THE OPERATOR — it COMPOSED. ` +
          `(IMM32 alongside, for the record only: ${fmtIme(after)})`);
        return { ime: after, conduct };
      }
    }
    await sleep(ASSIST_POLL_MS);
  }
  return null;
}

/**
 * Get this window's IME COMPOSING, or refuse the engine.
 *
 * A ladder of attempts, each one followed by the same conduct probe, because
 * none of them can be trusted to have worked and the IMM32 reading that used
 * to answer that question turned out to be reading this process's own
 * writes (see the header). `state` is the reading `IMEON` already produced,
 * and it is PRINTED rather than tested.
 *
 * The toggle is sent twice at most and the second is deliberate: it TOGGLES,
 * so if the first one closed an IME that was already open, the second puts it
 * back — and the probe between them is what tells those two apart.
 *
 * Throws with what it MEASURED in the message when the IME will not compose.
 * That abort is the deliverable; the caller records the engine as not engaged
 * and moves on. The alternative is typing romaji into a closed IME and
 * reporting eight INCONCLUSIVEs that look like an engine result.
 */
async function engageIme({
  driver, hwnd, engine, keyDelayMs, state, page, reload,
  assistMs = ASSIST_TIMEOUT_MS,
}) {
  const probe = () => probeComposition({ page, driver, hwnd, keyDelayMs, reload });
  console.log(`> IMM32 reports ${fmtIme(state)} — A LOG LINE, NOT A GATE: on ` +
    '2026-08-12 it read back the values this rig had written while nothing ' +
    'composed.');

  const ladder = [
    { what: 'as IMEON left it', act: async () => {} },
    {
      what: 'after IMECONV (ひらがな re-asserted)',
      act: async () => { await driver.require('IMECONV', hwnd); },
    },
    {
      what: `after ${IME_TOGGLE_KEY} (半角/全角) through SendInput`,
      act: async () => {
        await driver.require('KEYS', hwnd, IME_TOGGLE_KEY, keyDelayMs);
        await sleep(IME_SETTLE_MS);
      },
    },
    {
      what: `after a second ${IME_TOGGLE_KEY} (undoing the first, if it closed one)`,
      act: async () => {
        await driver.require('KEYS', hwnd, IME_TOGGLE_KEY, keyDelayMs);
        await sleep(IME_SETTLE_MS);
      },
    },
  ];

  let conduct = null;
  for (const rung of ladder) {
    await rung.act();
    conduct = await probe();
    console.log(`> conduct probe ${rung.what}: ${conduct.detail}`);
    if (conduct.composed) {
      const ime = await driver.require('IMESTATE', hwnd);
      console.log(`> ${engine} IME ENGAGED — it COMPOSED. (IMM32 alongside, ` +
        `for the record only: ${fmtIme(ime)})`);
      return { ime, conduct };
    }
  }

  const assisted = await awaitOperatorEngagement({
    driver, hwnd, engine, probe, timeoutMs: assistMs,
  });
  if (assisted) return assisted;

  const ime = await driver.require('IMESTATE', hwnd);
  throw new Error(
    `the ${engine} window's IME IS NOT COMPOSING. Its input locale is ` +
    `${ime.langid} (japanese=${ime.japanese}) and it holds the foreground ` +
    `(isForeground=${ime.isForeground}), but a single ${CONDUCT_KEY} typed ` +
    `into the ${CONDUCT_FIELD} field produced NO compositionstart after ` +
    `IMEON, an IMECONV, two ${IME_TOGGLE_KEY} (半角/全角) keystrokes and a ` +
    `${Math.round(assistMs / 1000)}s wait for you to open it by hand. Last ` +
    `reading: ${conduct.detail}. IMM32 says ${fmtIme(ime)}, and that is worth ` +
    'nothing here: on 2026-08-12 it said open=1 conversion=9 native=true on ' +
    'Chromium — the exact values this rig had written — while compositionstart ' +
    'stayed 0 and the romaji landed as ASCII. Nothing was typed for this ' +
    'engine and no check was driven.');
}

/**
 * Prove keystrokes are ARRIVING at this page before a plan is driven.
 *
 * Three outcomes, and the middle one is the reason this exists:
 *
 *   `true`  — the driver sent it and the page saw it.
 *   `false` — the driver sent it and the page saw NOTHING. Keys are not
 *             arriving; the check is not driven and says so in its own
 *             words, rather than reporting the IME's excuse for a fault
 *             that has nothing to do with the IME.
 *   `null`  — the driver REFUSED to send it, unarmed. That is the rehearsal,
 *             and it is a constructional fact reported by the driver rather
 *             than an assertion this function talked itself into; an armed
 *             run cannot reach it, and treats it as an error if it does.
 *
 * The observer is reset before the probe and the check resets it again, so
 * the probe's own keydown is never in evidence any verdict reads.
 */
async function probeDelivery({ page, driver, hwnd, keyDelayMs, inject }) {
  const observing = await W.resetEvents(page);
  if (!observing) {
    // Neither a delivery fault nor an IME one: there is nothing on the page
    // to read arrival WITH. Conflating it with "no keys arrived" is the exact
    // mistake this probe was added to stop making.
    throw new Error('the page observer is not installed, so key arrival ' +
      'cannot be read; no delivery claim is possible for this check');
  }
  const r = await driver.send('KEYS', hwnd, PROBE_KEY, keyDelayMs);
  if (r.status === 'REFUSED') {
    if (inject) {
      throw new Error('the delivery probe was REFUSED by a driver this run ' +
        `started ARMED: ${JSON.stringify(r.payload)}`);
    }
    return { delivered: null, detail: 'REFUSED by the unarmed driver (rehearsal)' };
  }
  if (r.status !== 'OK') {
    return { delivered: false, detail: `the driver could not send it: ${JSON.stringify(r.payload)}` };
  }
  await sleep(PROBE_SETTLE_MS);
  const keydowns = (await W.readEvents(page)).filter((e) => e.type === 'keydown').length;
  return {
    delivered: keydowns > 0,
    detail: `driver sent ${r.payload.sent}, page saw ${keydowns} keydown(s)`,
  };
}

// ---------------------------------------------------------------------------
// One engine
// ---------------------------------------------------------------------------

async function driveEngine(engine, baseUrl, driver, args) {
  const inject = args.mode === 'inject';
  console.log(`\n=================== ${engine.toUpperCase()} ===================`);
  // Required HERE rather than at module load, so `--self-test` needs no
  // browser package at all and its claim to touch nothing is literal.
  const playwright = require('playwright');
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

    // THE PREPARE PHASE — find the window, engage its IME, or refuse THIS
    // ENGINE. Every failure below is a statement about this window and this
    // IME and nothing else, so it is caught HERE rather than thrown at the
    // batch: on 2026-08-12 Firefox's honest abort took WebKit down with it,
    // and the engine that never launched was one of the two this bead exists
    // to record.
    let refusal = null;
    try {
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

      // What the toggle would actually carry, resolved under THIS window's
      // layout and read out before anything is sent. A `0x00` here is the
      // defect of 2026-08-12 — `MapVirtualKey` answering for the driver's own
      // English layout, where 半角/全角 is not a key — and it is the one part
      // of the engage ladder the REHEARSAL can measure without typing.
      const toggle = await driver.send('RESOLVE', IME_TOGGLE_KEY, hwnd);
      if (toggle.status === 'OK') {
        console.log(`> ${IME_TOGGLE_KEY} (半角/全角) resolves to vk ` +
          `${toggle.payload.vks} scan ${toggle.payload.scans} under this ` +
          "window's layout");
      }

      if (inject) {
        await driver.require('FOREGROUND', hwnd);
        await driver.require('IMEON', hwnd);
        imeState = await driver.require('IMESTATE', hwnd);
        if (!imeState.japanese) {
          throw new Error(
            `the ${engine} window's input locale is ${imeState.langid}, not 0x0411. ` +
            'The Japanese IME did not engage, so every keystroke below would be ' +
            'plain ASCII and every check would be measuring nothing. Switch the ' +
            'input method for that window by hand (Win+Space) and re-run, or ' +
            'install the Japanese IME first — Settings > Time & Language > ' +
            'Language & region > Add a language > 日本語.');
        }
        // The locale is the one thing IMM32 answers honestly here — it is read
        // off the window's own thread rather than out of an input context this
        // process writes to. Everything past it is decided by conduct.
        const engaged = await engageIme({
          driver, hwnd, engine, keyDelayMs: args.keyDelayMs, state: imeState,
          page, reload,
        });
        imeState = engaged.ime;
      } else {
        imeState = await driver.require('IMESTATE', hwnd);
        console.log(`> IME (read-only, rehearsal): ${JSON.stringify(imeState)}`);
      }
    } catch (err) {
      refusal = err.message;
    }
    if (refusal) {
      console.log(`\n[ ] ${engine.toUpperCase()} NOT ENGAGED — no check was ` +
        `driven and nothing was typed:\n      ${refusal}\n` +
        '      Continuing to the next engine. This says nothing about any ' +
        'other engine,\n      and nothing about this one either: it is a rig ' +
        'result, not a runtime result.');
      return { engine, build, hwnd, imeState, results: [], refusal, pageErrors };
    }

    for (const check of W.CHECKS) {
      await reload();
      const label = `check ${check.n} — ${check.title}`;
      let out;
      try {
        const probe = await probeDelivery({
          page, driver, hwnd, keyDelayMs: args.keyDelayMs, inject,
        });
        console.log(`> probe ${PROBE_KEY}: ${probe.detail}`);
        if (probe.delivered === false) {
          out = {
            verdict: W.INCONCLUSIVE,
            why: `KEYS ARE NOT ARRIVING at the ${engine} window: a single ` +
              `${PROBE_KEY} sent immediately before this check reached the ` +
              `page as no keydown at all (${probe.detail}). The plan was NOT ` +
              'driven. This says nothing about the IME and nothing about the ' +
              'engine — it is a transport failure between SendInput and the ' +
              'page, and it is a rig result, not a runtime result.',
            readback: `probe=${PROBE_KEY} ${probe.detail}`,
          };
        } else {
          out = await RUNNERS[check.id]({ page, keys, reload, pageErrors, engine });
        }
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
  return { engine, build, hwnd, imeState, results, refusal: null, pageErrors };
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
  if (run.refusal) {
    // Said here as well as where it happened, because the summary is the part
    // that gets pasted into a bead, and "0 ticks" alone reads like an engine
    // result rather than the rig refusing to produce one.
    console.log(`  NOT ENGAGED, so no check ran: ${run.refusal}`);
  }
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

// `engageIme`, `probeComposition` and `probeDelivery` are exported for the
// same reason `worst` and `RUNNERS` are: they take their driver and their page
// as arguments, so their REFUSALS can be forced and read back without an
// interactive desktop — which is the only place the armed run they guard can
// ever happen. `probeComposition` is the one that matters most: it is the gate
// the whole engagement now rests on, and a gate whose refusal nobody has ever
// seen fire is the gate that read `open: 1` on 2026-08-12.
module.exports = {
  runMutationTeeth, parseArgs, worst, RUNNERS, engageIme, probeComposition,
  probeDelivery,
};

if (require.main === module) {
  main().then((code) => process.exit(code)).catch((error) => {
    console.error(error.stack || String(error));
    process.exit(1);
  });
}
