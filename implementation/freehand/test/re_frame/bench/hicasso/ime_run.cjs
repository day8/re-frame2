#!/usr/bin/env node
// THE IME COMPOSITION HARNESS — driver (rf2-o27h3).
//
//   node implementation/freehand/test/re_frame/bench/hicasso/ime_run.cjs
//
// Drives REAL composition exchanges — CDP `Input.imeSetComposition` /
// `Input.insertText` / `Input.dispatchKeyEvent` — against the three
// controlled-input implementations `ime_app.cljs` mounts one per page:
// Arm 1's element path (converge installed), plain React, and UIx's port
// of Reagent's workaround. Nothing here dispatches a synthetic Event from
// page script: the exchange is the browser's own composition machinery,
// which is the entire point (the row was left unasserted precisely
// because synthetic composition Events exercise none of it).
//
// ## What CDP gives, measured on this Chromium before this file was built
//
//   - `imeSetComposition` mints a TRUSTED `compositionstart`, trusted
//     `compositionupdate`s carrying `data`, and trusted `input` events
//     with `inputType "insertCompositionText"` and `isComposing true`,
//     mutating the field through a real composition range.
//   - a trusted keydown sent DURING a composition carries
//     `isComposing true`; `windowsVirtualKeyCode: 229` lands as
//     `keyCode 229` — the two signals `front.intent/composing?` reads,
//     each drivable on its own.
//   - `insertText(text)` commits the composition (`compositionend` with
//     the committed data); `insertText("")` cancels it (field restored,
//     `compositionend` data "").
//   - a JS `value` write mid-composition SILENTLY ABORTS the composition:
//     no `compositionend` fires, and the next IME update mints a fresh
//     `compositionstart`. That signature — more than one
//     `compositionstart` in one exchange's log — is how this driver
//     detects a converge (or React's own restore) destroying an exchange.
//
// ## Verdict discipline
//
// LAW rows assert what must hold (the commit fence on both signals, the
// carriage of composition state on the native event, a cancelled
// exchange leaving field and model exactly as before, a model-agreeing
// exchange surviving to `compositionend`). RECORDED rows pin the
// MEASURED per-implementation behaviour, so drift reds the run without
// the row claiming the behaviour is desired.
//
// Since **rf2-digtt** the mid-composition rows are two conducts rather
// than one, and the difference is the point. `hicasso` carries the
// COMPOSITION CARVE-OUT — nothing writes a controlled text field while a
// composition is live, and the refusal or normalisation lands whole at
// `compositionend` — so its rows are `(CARVE-OUT)`. `react` and
// `uix-port` keep the old `(pinned, DIVERGENT)` rows because the abort is
// still what they do. The comparative section, which used to assert
// PARITY (the converge is nowhere worse than React's own restore — the
// PR #7371 audit's question, and true when it was asked), now asserts
// the DIVERGENCE and its scope: one uninterrupted exchange against two,
// and the same model and the same field once the exchange closes.
//
// **Witness scope: Chromium only.** `Input.imeSetComposition` is a CDP
// method and CDP is Chromium's protocol, so every claim in this file is
// witnessed on Chromium and nowhere else. WebKit's composition/key
// ordering has had defects of its own; this harness cannot drive it, and
// misconduct there would be a new bug rather than a known one.
//
// ## Why the bundle is a DEV build (`shadow-cljs compile`), not `release`
//
// The uix-port page reaches Reagent's after-render queue through the raw
// global `js/reagent.impl.batching.do-after-render`
// (uix/compiler/input.cljs) — a path that only exists while namespaces
// live on the global object, i.e. under `:none`. `:advanced` renames it
// away and the port throws. The `:browser-test` lane is a dev build for
// the same reason it works there. This is a correctness instrument, not
// a clock; nothing here quotes a figure, so the release/dev distinction
// the bench drivers guard does not apply.
//
// Exit codes:
//   0  every law held, every recorded row matched its pin, floor met
//   1  a law or pin failed, a page threw, or the check floor was unmet
'use strict';

const fs = require('node:fs');
const http = require('node:http');
const path = require('node:path');

const { navigate, NAV_TIMEOUT_MS } = require('../../freehand/bench/navigate.cjs');
const { watchPage } = require('../../freehand/bench/sentinel.cjs');
const { resetLaneBuildCache } = require('../../freehand/bench/lane_cache.cjs');
// shadow-cljs exits 0 on WARNINGS, so a status check is not a gate. The
// lane's one build door refuses a warned build (rf2-2rtt6.73).
const { shadowBuild } = require('./lane_build.cjs');

const IMPL_ROOT = path.resolve(__dirname, '../../../../..');

const BUILD_ID = 'hicasso-bench';
const OUT_DIR = process.env.IME_OUT_DIR || 'out/hicasso-ime';
const INIT_FN = 're-frame.bench.hicasso.ime-app/-main';
const OUT = path.join(IMPL_ROOT, OUT_DIR);
const PORT = Number(process.env.IME_PORT || 8146);
const READY_TIMEOUT_MS = 60 * 1000;

// The check floor (the rf2-qqzmf lesson, carried here): a run that
// asserted almost nothing must not exit 0. A full three-page run banks
// ~120 checks (122 as of rf2-digtt's carve-out rows); 100 refuses a
// silently-skipped page while leaving room for per-impl variation.
const MIN_CHECKS = 100;

const ALL_IMPLS = ['hicasso', 'react', 'uix-port'];
// `IME_ONLY=react` runs one page over the same bundle and gates —
// development convenience; the floor is prorated so a partial run can
// still be green while a partial run masquerading as full cannot.
const ONLY = (process.env.IME_ONLY || '').trim();
const IMPLS = ONLY ? ALL_IMPLS.filter((i) => ONLY.split(',').includes(i)) : ALL_IMPLS;
if (IMPLS.length === 0) {
  console.error(`[ime] IME_ONLY=${ONLY} selects no page; known: ${ALL_IMPLS.join(', ')}`);
  process.exit(1);
}
const CHECK_FLOOR = Math.floor(MIN_CHECKS * (IMPLS.length / ALL_IMPLS.length));

// ONE LINE, deliberately: shadow-cljs's CLI re-splits `--config-merge` on
// whitespace when the EDN contains a newline.
const CONFIG_MERGE =
  `{:output-dir "${OUT_DIR}" :asset-path "." ` +
  `:modules {:main {:init-fn ${INIT_FN}}}}`;

function build() {
  if (resetLaneBuildCache(IMPL_ROOT, BUILD_ID)) {
    console.error(`[ime] cleared .shadow-cljs/builds/${BUILD_ID} — one build id, N programs (rf2-2rtt6.20)`);
  }
  console.error(`[ime] building DEV bundle — ${INIT_FN} -> ${OUT_DIR} (see header for why not :advanced)`);
  shadowBuild({
    impl: IMPL_ROOT,
    mode: 'compile',
    buildId: BUILD_ID,
    configMerge: CONFIG_MERGE,
    tag: 'ime',
  });
}

const MIME = { '.js': 'text/javascript', '.html': 'text/html', '.map': 'application/json', '.json': 'application/json' };

function serve() {
  fs.writeFileSync(
    path.join(OUT, 'index.html'),
    '<!doctype html><html><head><meta charset="utf-8"><title>Hicasso IME harness</title></head>' +
      '<body><div id="app"></div><script src="main.js"></script></body></html>'
  );
  return http
    .createServer((req, res) => {
      const rel = decodeURIComponent(req.url.split('?')[0]);
      const file = path.join(OUT, rel === '/' ? 'index.html' : rel);
      if (!file.startsWith(OUT) || !fs.existsSync(file)) {
        res.writeHead(404).end('not found');
        return;
      }
      res.writeHead(200, { 'content-type': MIME[path.extname(file)] || 'application/octet-stream' });
      fs.createReadStream(file).pipe(res);
    })
    .listen(PORT);
}

// ---------------------------------------------------------------------------
// The reporter — laws, pins, records, one tally
// ---------------------------------------------------------------------------

function reporter(impl) {
  const checks = [];
  const say = (line) => console.log(`;; [${impl}] ${line}`);
  return {
    impl,
    checks,
    check(label, ok, detail) {
      checks.push({ label, ok: !!ok });
      if (!ok) say(`FAIL  ${label}${detail === undefined ? '' : `  — got ${JSON.stringify(detail)}`}`);
      return !!ok;
    },
    record(label, value) {
      say(`record  ${label} = ${JSON.stringify(value)}`);
    },
    failed() { return checks.filter((c) => !c.ok); },
  };
}

// ---------------------------------------------------------------------------
// Page helpers
// ---------------------------------------------------------------------------

const state = async (page) => JSON.parse(await page.evaluate('window.IME_STATE()'));
const clearLog = (page) => page.evaluate('window.IME_CLEAR_LOG()');
// Two animation frames and a macrotask: the uix-port's refusal path
// converges on Reagent's after-render queue (drained from rAF), and the
// cell/entry reapers in the hicasso runtime ride a macrotask.
const settle = (page) =>
  page.evaluate(() => new Promise((r) => requestAnimationFrame(() => requestAnimationFrame(() => setTimeout(r, 0)))));
const reset = async (page) => { await page.evaluate('window.IME_RESET()'); await settle(page); };
const fieldRead = (page, f) =>
  page.evaluate((id) => {
    const n = document.getElementById(id);
    return { value: n.value, s: n.selectionStart, e: n.selectionEnd };
  }, f);
const focusEnd = (page, f) =>
  page.evaluate((id) => {
    const n = document.getElementById(id);
    n.focus();
    n.setSelectionRange(n.value.length, n.value.length);
  }, f);

// ---------------------------------------------------------------------------
// CDP input — the real exchange
// ---------------------------------------------------------------------------

const compose = (cdp, text) =>
  cdp.send('Input.imeSetComposition', { text, selectionStart: text.length, selectionEnd: text.length });
const commit = (cdp, text) => cdp.send('Input.insertText', { text });
const cancel = (cdp) => cdp.send('Input.insertText', { text: '' });
async function pressKey(cdp, { key = 'Enter', code = 'Enter', vk = 13 }) {
  await cdp.send('Input.dispatchKeyEvent', {
    type: 'keyDown', key, code, windowsVirtualKeyCode: vk, nativeVirtualKeyCode: vk,
  });
  await cdp.send('Input.dispatchKeyEvent', {
    type: 'keyUp', key, code, windowsVirtualKeyCode: vk, nativeVirtualKeyCode: vk,
  });
}

// Log slices.
const nativeOf = (st, field) => st.log.filter((e) => e.layer === 'native' && e.field === field);
const handlerOf = (st, field) => st.log.filter((e) => e.layer === 'handler' && e.field === field);
const ofType = (log, t) => log.filter((e) => e.type === t);

// ---------------------------------------------------------------------------
// Scenario 1 — carriage: one full exchange on the model-agreeing field
// ---------------------------------------------------------------------------
//
// This is also the SURVIVAL law: on a field whose model takes what the
// IME composes, the exchange must reach `compositionend` uninterrupted —
// exactly one `compositionstart` in the log. A converge (or restore)
// that wrote or collapsed mid-composition mints a second one, which is
// what the mutation run demonstrates.

async function s1Carriage(page, cdp, R) {
  await reset(page);
  await focusEnd(page, 'plain');
  await clearLog(page);

  const trajectory = [];
  for (const t of ['s', 'sh', 'し']) {
    await compose(cdp, t);
    await settle(page);
    const st = await state(page);
    trajectory.push({ composed: t, model: st.cells.plain, field: (await fieldRead(page, 'plain')).value });
  }
  await commit(cdp, 'し');
  await settle(page);

  const st = await state(page);
  const nat = nativeOf(st, 'plain');
  const starts = ofType(nat, 'compositionstart');
  const updates = ofType(nat, 'compositionupdate');
  const inputs = ofType(nat, 'input');
  const ends = ofType(nat, 'compositionend');

  R.check('s1: exactly one compositionstart — the exchange was never aborted', starts.length === 1, starts.length);
  R.check('s1: compositionupdate data trail is the composition forming',
    JSON.stringify(updates.map((e) => e.data)) === JSON.stringify(['s', 'sh', 'し', 'し']),
    updates.map((e) => e.data));
  R.check('s1: every native input event carries isComposing true',
    inputs.length > 0 && inputs.every((e) => e.isComposing === true), inputs.map((e) => e.isComposing));
  R.check('s1: every native input event carries inputType insertCompositionText',
    inputs.every((e) => e.inputType === 'insertCompositionText'), inputs.map((e) => e.inputType));
  R.check('s1: the composition events are trusted — this is the browser, not a dispatched Event',
    starts.every((e) => e.isTrusted) && updates.every((e) => e.isTrusted) && inputs.every((e) => e.isTrusted),
    { starts: starts.map((e) => e.isTrusted), inputs: inputs.map((e) => e.isTrusted) });
  R.check('s1: compositionend carries the committed data',
    ends.length === 1 && ends[0].data === 'し', ends.map((e) => e.data));

  const f = await fieldRead(page, 'plain');
  R.check('s1: the field holds the committed text with the caret after it',
    f.value === 'し' && f.s === 1 && f.e === 1, f);
  R.check('s1: the model holds the committed text', st.cells.plain === 'し', st.cells.plain);
  R.check('s1: nothing committed through the commit door', st.committed.plain === undefined, st.committed);

  // The model's view of the exchange, per intermediate update — RECORDED
  // and pinned: on every implementation the change handler fires per
  // composition input, so the model tracks the intermediate composition
  // text. This is measured behaviour, not the fence; the fence is the
  // commit door (s2/s3).
  R.record('s1: model trajectory across the updates', trajectory.map((t) => t.model));
  R.check('s1 (pinned): the model observes each intermediate composition state',
    JSON.stringify(trajectory.map((t) => t.model)) === JSON.stringify(['s', 'sh', 'し']),
    trajectory.map((t) => t.model));

  // The handler layer — what the application's own handlers saw.
  const handler = handlerOf(st, 'plain');
  if (R.impl === 'hicasso') {
    const hStarts = ofType(handler, 'compositionstart');
    const hEnds = ofType(handler, 'compositionend');
    R.check('s1: React delivered onCompositionStart/End to the hfn probes',
      hStarts.length === 1 && hEnds.length === 1, { starts: hStarts.length, ends: hEnds.length });
    R.record('s1: synthetic composition data at the handler',
      ofType(handler, 'compositionupdate').map((e) => e.syntheticData));
  } else {
    const changes = ofType(handler, 'change');
    R.check('s1: every change handler invocation saw isComposing true ON THE NATIVE EVENT',
      changes.length > 0 && changes.every((e) => e.nativeIsComposing === true),
      changes.map((e) => e.nativeIsComposing));
    R.check('s1: and insertCompositionText on the native event',
      changes.every((e) => e.nativeInputType === 'insertCompositionText'),
      changes.map((e) => e.nativeInputType));
    // The dead-half lesson, recorded where it lives: the synthetic event.
    R.record('s1: syntheticInputType at the change handler', changes.map((e) => e.syntheticInputType));
  }
}

// ---------------------------------------------------------------------------
// Scenario 2 — the commit fence, modern signal: a composing Enter
// ---------------------------------------------------------------------------

async function s2ComposingEnter(page, cdp, R) {
  await reset(page);
  await focusEnd(page, 'plain');
  await clearLog(page);

  await compose(cdp, 'し');
  await settle(page);
  await pressKey(cdp, { vk: 13 });   // Enter, mid-composition
  await settle(page);

  let st = await state(page);
  const kd = ofType(nativeOf(st, 'plain'), 'keydown');
  R.check('s2: the mid-composition keydown carried isComposing true, natively and trusted',
    kd.length === 1 && kd[0].isComposing === true && kd[0].isTrusted === true, kd);
  R.check('s2: LAW — a composing Enter commits nothing', st.committed.plain === undefined, st.committed);

  if (R.impl !== 'hicasso') {
    const hkd = ofType(handlerOf(st, 'plain'), 'keydown');
    R.check('s2: the handler saw isComposing true on the NATIVE event',
      hkd.length === 1 && hkd[0].nativeIsComposing === true, hkd);
    R.check("s2: and React's synthetic keyboard event DROPPED it — the reason the gate reads the native event",
      hkd[0].syntheticIsComposing === undefined || hkd[0].syntheticIsComposing === null,
      hkd[0].syntheticIsComposing);
  }

  // Control: the gate is a gate, not an off switch.
  await commit(cdp, 'し');
  await settle(page);
  await pressKey(cdp, { vk: 13 });   // Enter, composition over
  await settle(page);
  st = await state(page);
  R.check('s2: control — an ordinary Enter after compositionend DOES commit',
    st.committed.plain === 'し', st.committed);
}

// ---------------------------------------------------------------------------
// Scenario 3 — the commit fence, legacy signal: keyCode 229 alone
// ---------------------------------------------------------------------------

async function s3KeyCode229(page, cdp, R) {
  await reset(page);
  await focusEnd(page, 'plain');
  await clearLog(page);

  await commit(cdp, 'shi');          // plain text, no composition
  await settle(page);
  await pressKey(cdp, { vk: 229 });  // Enter whose keyCode is 229 — all some IMEs send
  await settle(page);

  let st = await state(page);
  const kd = ofType(nativeOf(st, 'plain'), 'keydown');
  R.check('s3: the keydown carried keyCode 229 with isComposing FALSE — the legacy signal alone',
    kd.length === 1 && kd[0].keyCode === 229 && kd[0].isComposing === false, kd);
  R.check('s3: LAW — keyCode 229 alone holds the fence', st.committed.plain === undefined, st.committed);

  await pressKey(cdp, { vk: 13 });
  await settle(page);
  st = await state(page);
  R.check('s3: control — keyCode 13 commits', st.committed.plain === 'shi', st.committed);
}

// ---------------------------------------------------------------------------
// Scenario 4 — a cancelled composition leaves field and model as before
// ---------------------------------------------------------------------------

async function s4Cancel(page, cdp, R) {
  for (const field of ['plain', 'digits']) {
    await reset(page);
    await focusEnd(page, field);
    await clearLog(page);
    const before = { field: (await fieldRead(page, field)).value, model: (await state(page)).cells[field] };

    await compose(cdp, 'か');
    await settle(page);
    await cancel(cdp);
    await settle(page);

    const st = await state(page);
    const f = await fieldRead(page, field);
    const ends = ofType(nativeOf(st, field), 'compositionend');
    R.check(`s4 [${field}]: LAW — the cancelled exchange left the field exactly as before`,
      f.value === before.field, { before, after: f.value });
    R.check(`s4 [${field}]: and the model exactly as before`,
      st.cells[field] === before.model, { before, after: st.cells[field] });
    if (field === 'plain') {
      R.check('s4 [plain]: a compositionend closed the exchange, with empty data',
        ends.length >= 1 && ends[ends.length - 1].data === '', ends.map((e) => e.data));
    } else if (R.impl === 'hicasso') {
      // THE CARVE-OUT, on the cancel path (rf2-digtt). Nothing wrote the
      // field while the composition was live, so there IS a composition
      // for the cancel to cancel — and cancelling it is the ordinary
      // exchange the `plain` branch above asserts, on a field whose model
      // refused every intermediate state.
      R.check('s4 [digits]: the refusing field cancels like any other — a compositionend closed the exchange, with empty data',
        ends.length >= 1 && ends[ends.length - 1].data === '', ends.map((e) => e.data));
    } else {
      // MEASURED, first run of this harness (2026-08-02), and now the
      // DIVERGENCE rather than the parity: on plain React and on the UIx
      // port there is NO compositionend to observe. The model refused
      // `か`, the implementation wrote the refused-to value back
      // MID-composition, and that write silently aborted the exchange
      // (the measured JS-write semantics — no compositionend fires on an
      // abort). The later cancel found no composition left to cancel.
      R.check('s4 [digits] (pinned, DIVERGENT): the exchange was already silently aborted — no compositionend ever fired',
        ends.length === 0, ends.map((e) => e.data));
    }
    R.check(`s4 [${field}]: nothing committed`, st.committed[field] === undefined, st.committed);
  }
}

// ---------------------------------------------------------------------------
// Scenario 5 — the mid-composition conduct matrix (the PR #7371 question,
// and since rf2-digtt the carve-out's own witness)
// ---------------------------------------------------------------------------
//
// `digits` refuses the composition text and `upper` normalises it, so on
// both fields the model DISAGREES with what the IME is composing. That
// disagreement is what every implementation used to resolve by writing
// the field mid-composition — Arm 1's converge inside the input event,
// React's restore at the end of the same discrete event, the uix-port on
// the after-render queue — and every one of those writes silently aborts
// the live exchange (the measured JS-write semantics in the header).
//
// **These rows were the tripwire the carve-out ruling was told to trip,
// and they are flipped here on purpose (rf2-digtt).** Two conducts now,
// asserted as a DIVERGENCE rather than as parity:
//
//   - `hicasso` re-pins ONE uninterrupted compositionstart → compositionend
//     exchange. Nothing writes the field while the composition is live;
//     the model refuses or normalises every intermediate state exactly as
//     before; and the refusal or normalisation lands whole, once, at the
//     commit.
//   - `react` and `uix-port` keep pinning the OLD abort conduct, because
//     it is still what they do and the pins are still true of them. They
//     are the baseline the divergence is measured against, in the same
//     run, on the same model.

async function s5MidComposition(page, cdp, R, out) {
  // The carve-out is Arm 1's; the other two pages are the baseline.
  const carved = R.impl === 'hicasso';

  // -- digits: the refusing model -------------------------------------------
  await reset(page);
  await focusEnd(page, 'digits');
  await clearLog(page);

  await compose(cdp, 'し');
  const immediate = (await fieldRead(page, 'digits')).value;  // before any settle
  await settle(page);
  const settled = await fieldRead(page, 'digits');
  const modelAfter = (await state(page)).cells.digits;

  // Continue the exchange the way an IME would — the next update tells us
  // whether the composition survived the implementation's convergence.
  await compose(cdp, 'しん');
  await settle(page);
  const st2 = await state(page);
  const starts = ofType(nativeOf(st2, 'digits'), 'compositionstart').length;
  const mid = (await fieldRead(page, 'digits')).value;

  // COMMIT, rather than cancel: the carve-out defers the refusal to
  // exactly here, so the row that reads it has to let the exchange close.
  await commit(cdp, 'しん');
  await settle(page);
  const st3 = await state(page);
  const ends = ofType(nativeOf(st3, 'digits'), 'compositionend').length;
  const closed = await fieldRead(page, 'digits');

  R.check('s5 [digits]: LAW — the refusing model never moved', modelAfter === '12', modelAfter);
  R.check('s5 [digits]: LAW — and it had not moved when the exchange closed either',
    st3.cells.digits === '12', st3.cells.digits);
  R.record('s5 [digits]: field immediately after the composing input (no settle)', immediate);
  R.record('s5 [digits]: field after settle', settled.value);
  R.record('s5 [digits]: field after the second composing update', mid);
  R.record('s5 [digits]: compositionstart count across one exchange', starts);
  R.record('s5 [digits]: compositionend count across one exchange', ends);
  R.record('s5 [digits]: field once the exchange closed', closed.value);

  if (carved) {
    R.check('s5 [digits] (CARVE-OUT): the composing input wrote NOTHING — the draft is on the screen in-turn',
      immediate === '12し', immediate);
    R.check('s5 [digits] (CARVE-OUT): and nothing wrote it after the settle either — not the converge, not React\'s restore',
      settled.value === '12し', settled.value);
    R.check('s5 [digits] (CARVE-OUT): the exchange was never aborted — ONE compositionstart across the whole of it',
      starts === 1, starts);
    R.check('s5 [digits] (CARVE-OUT): the second update grew the SAME draft',
      mid === '12しん', mid);
    R.check('s5 [digits] (CARVE-OUT): the exchange reached compositionend — exactly one, where the baseline has none',
      ends === 1, ends);
    R.check('s5 [digits] (CARVE-OUT): and the refusal landed WHOLE at the commit — the field is the model\'s again',
      closed.value === '12', closed.value);
  } else {
    const immediateExpected = { react: '12', 'uix-port': '12し' }[R.impl];
    R.check(`s5 [digits] (pinned, DIVERGENT): the refused composition text is rewritten ${R.impl === 'uix-port' ? 'ONE FRAME LATE' : 'IN-TURN'}`,
      immediate === immediateExpected, { immediate, expected: immediateExpected });
    R.check('s5 [digits] (pinned, DIVERGENT): after settling, the write has landed and the field shows the refused-to value',
      settled.value === '12', settled.value);
    R.check('s5 [digits] (pinned, DIVERGENT): the write ABORTED the live composition — the next IME update minted a NEW compositionstart',
      starts === 2, starts);
    R.check('s5 [digits] (pinned, DIVERGENT): and no compositionend was ever delivered for it',
      ends === 0, ends);
  }

  out.digits = {
    immediate, settled: settled.value, caret: [settled.s, settled.e],
    starts, ends, closed: closed.value, model: st3.cells.digits,
  };

  // -- upper: the normalising model -----------------------------------------
  await reset(page);
  await focusEnd(page, 'upper');
  await clearLog(page);

  await compose(cdp, 's');
  const upImmediate = (await fieldRead(page, 'upper')).value;
  await settle(page);
  const upSettled = await fieldRead(page, 'upper');
  const upModel = (await state(page)).cells.upper;

  await compose(cdp, 'sh');
  await settle(page);
  const st4 = await state(page);
  const upStarts = ofType(nativeOf(st4, 'upper'), 'compositionstart').length;

  await commit(cdp, 'sh');
  await settle(page);
  const st5 = await state(page);
  const upEnds = ofType(nativeOf(st5, 'upper'), 'compositionend').length;
  const upClosed = await fieldRead(page, 'upper');

  R.check('s5 [upper]: the model normalised the intermediate composition text', upModel === 'S', upModel);
  R.record('s5 [upper]: field immediately after the composing input', upImmediate);
  R.record('s5 [upper]: field after settle', upSettled.value);
  R.record('s5 [upper]: compositionstart count across one exchange', upStarts);
  R.record('s5 [upper]: compositionend count across one exchange', upEnds);
  R.record('s5 [upper]: field once the exchange closed', upClosed.value);
  R.record('s5 [upper]: model once the exchange closed', st5.cells.upper);

  if (carved) {
    R.check('s5 [upper] (CARVE-OUT): the composing input wrote nothing — the field shows the DRAFT while the model holds the normalisation',
      upSettled.value === 's' && upModel === 'S', { field: upSettled.value, model: upModel });
    R.check('s5 [upper] (CARVE-OUT): the exchange was never aborted', upStarts === 1, upStarts);
    R.check('s5 [upper] (CARVE-OUT): it reached compositionend', upEnds === 1, upEnds);
    R.check('s5 [upper] (CARVE-OUT): and the normalisation landed whole at the commit',
      upClosed.value === 'SH' && st5.cells.upper === 'SH',
      { field: upClosed.value, model: st5.cells.upper });
  } else {
    R.check('s5 [upper] (pinned, DIVERGENT): the normalised value is written back over the live composition',
      upSettled.value === 'S', upSettled.value);
    R.check('s5 [upper] (pinned, DIVERGENT): and that write aborted the composition',
      upStarts === 2, upStarts);
  }

  out.upper = {
    immediate: upImmediate, settled: upSettled.value, starts: upStarts,
    ends: upEnds, closed: upClosed.value, model: st5.cells.upper,
  };
}

// ---------------------------------------------------------------------------
// One page
// ---------------------------------------------------------------------------

async function runImpl(browser, impl) {
  const page = await browser.newPage();
  const watch = watchPage(page, `ime:${impl}`);
  const R = reporter(impl);
  const out = { impl, R };
  try {
    const cdp = await page.context().newCDPSession(page);
    await navigate(page, `http://127.0.0.1:${PORT}/?impl=${impl}`, {
      waitUntil: 'commit',
      timeoutMs: NAV_TIMEOUT_MS,
      budget: `the ${READY_TIMEOUT_MS / 1000}s wait for window.IME_READY`,
    });
    await watch.race('window.IME_READY === true || window.IME_ERROR', {
      timeoutMs: READY_TIMEOUT_MS,
      budget: `the ${READY_TIMEOUT_MS / 1000}s wait for window.IME_READY`,
    });
    const err = await page.evaluate('window.IME_ERROR || null');
    if (err) throw new Error(`the page failed to mount: ${err}`);

    const st = await state(page);
    R.check('page: the pin matches the implementation this page claims to measure',
      st.pin === (impl === 'uix-port' ? 'true' : 'false'), st.pin);

    console.log(`;; ==== IME ${impl} ====`);
    await s1Carriage(page, cdp, R);
    await s2ComposingEnter(page, cdp, R);
    await s3KeyCode229(page, cdp, R);
    await s4Cancel(page, cdp, R);
    await s5MidComposition(page, cdp, R, out);

    out.pageErrors = watch.failures.map((f) => `${f.kind}: ${f.detail}`);
    return out;
  } finally {
    watch.dispose();
    await page.close();
  }
}

// ---------------------------------------------------------------------------
// Main
// ---------------------------------------------------------------------------

(async () => {
  build();
  const server = serve();
  const { chromium } = require(require.resolve('playwright', { paths: [IMPL_ROOT] }));
  const browser = await chromium.launch({ headless: true });
  const outcomes = [];
  let died = null;
  try {
    for (const impl of IMPLS) {
      console.error(`[ime] page ${impl}`);
      try {
        outcomes.push(await runImpl(browser, impl));
      } catch (e) {
        died = `${impl}: ${e.message}`;
        break;
      }
    }
  } finally {
    await browser.close();
    server.close();
  }
  if (died) {
    console.error(`[ime] FAILED: ${died}`);
    process.exit(1);
  }

  // Comparative verdict: Arm 1's carve-out against the plain-React
  // baseline measured in the SAME run, on the same model, in the same
  // browser. Until rf2-digtt this section asserted PARITY — the converge
  // is nowhere worse than React's own restore — and it was true. The
  // ruling replaced the question: the arm is now deliberately BETTER
  // here, and what has to be asserted is that the divergence is real and
  // that it is scoped to the live composition and nothing else.
  const by = Object.fromEntries(outcomes.map((o) => [o.impl, o]));
  if (by.hicasso && by.react) {
    const R = by.hicasso.R;
    console.log(';; ==== IME comparative (hicasso vs the plain-React baseline) ====');
    R.check('comparative: THE DIVERGENCE — one uninterrupted exchange on the arm where React aborts and the IME restarts',
      by.hicasso.digits.starts === 1 && by.react.digits.starts === 2,
      { hicasso: by.hicasso.digits.starts, react: by.react.digits.starts });
    R.check('comparative: and the arm reaches a compositionend where React delivers none at all',
      by.hicasso.digits.ends === 1 && by.react.digits.ends === 0,
      { hicasso: by.hicasso.digits.ends, react: by.react.digits.ends });
    R.check('comparative: mid-composition the arm shows the DRAFT where React has already written the refused-to value',
      by.hicasso.digits.settled === '12し' && by.react.digits.settled === '12',
      { hicasso: by.hicasso.digits.settled, react: by.react.digits.settled });
    R.check('comparative: THE SCOPE — the refusal itself is identical. Same model throughout, same field once the exchange closes',
      by.hicasso.digits.model === by.react.digits.model &&
        by.hicasso.digits.closed === by.react.digits.closed,
      { hicasso: by.hicasso.digits, react: by.react.digits });
    R.check('comparative: on the normalising field the same divergence — one uninterrupted exchange against two, a compositionend against none',
      by.hicasso.upper.starts === 1 && by.react.upper.starts === 2 &&
        by.hicasso.upper.ends === 1 && by.react.upper.ends === 0,
      { hicasso: by.hicasso.upper, react: by.react.upper });
    // MEASURED 2026-08-03, and the row the digits scope-claim above does
    // NOT extend to: on a normalising model the baseline's abort does not
    // merely lose the composition, it CORRUPTS what the exchange commits.
    // Each aborted draft is written back into the field, and the IME's
    // next composition composes on top of it — `s` → `S`, `sh` on top of
    // `S` → `SSH`, the commit on top of that → `SSHSH`. The arm, having
    // written nothing until the end, commits the `SH` the user typed.
    R.check('comparative: THE SCOPE, on the normalising field — the arm commits what was composed; the baseline commits what its own restarts left behind',
      by.hicasso.upper.model === 'SH' && by.react.upper.model === 'SSHSH',
      { hicasso: by.hicasso.upper.model, react: by.react.upper.model });
  }
  if (by.hicasso && by['uix-port']) {
    const R = by.hicasso.R;
    R.check('comparative: the UIx port aborts the exchange too — one frame later, and the arm diverges from it identically',
      by.hicasso.digits.starts === 1 && by['uix-port'].digits.starts === 2,
      { hicasso: by.hicasso.digits.starts, 'uix-port': by['uix-port'].digits.starts });
  }

  console.log(';; ==== IME RUNTIME ====');
  console.log(`;; chromium (playwright), DEV bundle (:none — see header), pages: ${IMPLS.join(', ')}`);
  console.log(`;; reproduce  ${ONLY ? `IME_ONLY=${ONLY} ` : ''}node implementation/freehand/test/re_frame/bench/hicasso/ime_run.cjs`);

  const errored = outcomes.filter((o) => o.pageErrors.length > 0);
  if (errored.length > 0) {
    console.error(
      `[ime] FAILED: uncaught page error(s):\n  ` +
        errored.map((o) => `${o.impl}: ${o.pageErrors.join(' | ')}`).join('\n  ')
    );
    process.exit(1);
  }

  const totalChecks = outcomes.reduce((n, o) => n + o.R.checks.length, 0);
  const failures = outcomes.flatMap((o) => o.R.failed().map((c) => `${o.impl}: ${c.label}`));
  console.log(`;; checks     ${totalChecks} (floor ${CHECK_FLOOR}), failures ${failures.length}`);
  if (failures.length > 0) {
    console.error(`[ime] FAILED:\n  ${failures.join('\n  ')}`);
    process.exit(1);
  }
  if (totalChecks < CHECK_FLOOR) {
    console.error(
      `[ime] FAILED: only ${totalChecks} checks ran — below the floor of ${CHECK_FLOOR}. ` +
        `A harness that asserted almost nothing must not exit 0.`
    );
    process.exit(1);
  }
  console.error('[ime] ok');
})().catch((e) => {
  console.error(e);
  process.exit(1);
});
