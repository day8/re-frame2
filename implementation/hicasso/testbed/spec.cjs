'use strict';

/*
 * THE CONTROLLED-INPUT WITNESSES — invariant I15, in three real engines
 * (rf2-hic-016).
 *
 * `serve-and-run-hicasso-controlled-testbed.cjs` compiles the testbed,
 * serves it, and runs everything below once per engine in Chromium,
 * Firefox and WebKit. The spec asserts what must hold in every engine and
 * RECORDS what differs; the runner compares the recorded rows across
 * engines and reds on an unexplained divergence.
 *
 * ## Why the witnesses are driven from page script rather than the keyboard
 *
 * Both, in fact — and the split is the point.
 *
 * `page.keyboard` produces the browser's own trusted key and input events,
 * which is the only way to be sure the field is really being typed into.
 * But a Playwright keystroke resolves a round-trip later, so the value read
 * after it is the value at QUIESCENCE, and I15's first clause is about the
 * TURN: a runtime that converged one animation frame late (UIx's port of
 * Reagent's workaround does exactly that) passes every quiescent
 * assertion. The only place a same-turn claim can be witnessed is inside
 * the dispatching task, so the convergence rows dispatch the event
 * themselves and read the field on the next line, before the task yields.
 *
 * Those dispatched events are not fakes of the DOM: `nativeSet` writes
 * through `HTMLInputElement.prototype`'s own `value` setter, which is what
 * a keystroke does and what React's value tracker is watching, so React's
 * ChangeEventPlugin banks a restore target exactly as it does for a real
 * key. Assigning `el.value` instead would go through the tracker's patched
 * setter, React would conclude nothing changed, and the whole
 * end-of-event restore this invariant is about would never run — a test
 * that passed by never reaching the mechanism.
 *
 * The caret rows then re-witness the same fields with `page.keyboard`, so
 * no caret claim rests on a dispatched event alone.
 *
 * ## Composition
 *
 * `Input.imeSetComposition` is a CDP method and CDP is Chromium's protocol,
 * so the repo's existing real-IME harness
 * (`freehand/test/re_frame/bench/hicasso/ime_run.cjs`) states its scope as
 * Chromium only. This gate needs the carve-out witnessed on WebKit and
 * Firefox, where no such protocol exists, so composition here is the event
 * SEQUENCE a composition produces — `compositionstart`, `beforeinput` and
 * `input` carrying `isComposing`, `compositionend` — dispatched at the real
 * node, through real React, into the real shadow component.
 *
 * What that reaches and what it does not is stated rather than glossed:
 * it reaches `composing-input?`'s reading of the native event, the shadow
 * that holds the draft, and React's own end-of-event restore finding
 * nothing to write — every mechanism the carve-out is made of. It does not
 * reach the browser's composition RANGE, so the abort signature the CDP
 * harness detects (a value write killing an exchange without a
 * `compositionend`) stays Chromium-only and stays that harness's. See the
 * `## Coverage` block in the runner.
 */

// Everything the page needs, installed before the app's own script runs.
// Kept as one string so the runner can hand it to `page.addInitScript`
// unchanged in every engine.
const PAGE_HELPERS = `
window.__TB__ = (function () {
  function el(id) {
    var n = document.querySelector('[data-testid="' + id + '"]');
    if (!n) throw new Error('no element with data-testid=' + id);
    return n;
  }
  // The browser's own value setter, which is the one React's value tracker
  // is watching. See the spec header.
  function nativeSet(node, v) {
    var proto = node.tagName === 'TEXTAREA'
      ? HTMLTextAreaElement.prototype
      : HTMLInputElement.prototype;
    Object.getOwnPropertyDescriptor(proto, 'value').set.call(node, v);
  }
  function fire(node, type, init) {
    node.dispatchEvent(new InputEvent(type, Object.assign(
      { bubbles: true, cancelable: type === 'beforeinput', composed: true }, init)));
  }
  function composition(node, type, data) {
    node.dispatchEvent(new CompositionEvent(type, {
      bubbles: true, composed: true, data: data == null ? '' : data,
    }));
  }
  // One edit, delivered as a keystroke delivers it: the field already
  // shows what the user left behind, the caret is where they left it, and
  // the event goes out. Returns what the field shows on the very next
  // line — inside the same task, which is what "same turn" means.
  function edit(id, next, caret, opts) {
    var node = el(id);
    var o = opts || {};
    node.focus();
    nativeSet(node, next);
    node.setSelectionRange(caret, caret);
    if (o.beforeinput !== false) {
      fire(node, 'beforeinput', { inputType: o.inputType || 'insertText', data: o.data || null });
    }
    fire(node, 'input', {
      inputType: o.inputType || 'insertText',
      data: o.data || null,
      isComposing: !!o.isComposing,
    });
    return read(id);
  }
  function read(id) {
    var node = el(id);
    return {
      value: node.value,
      start: node.selectionStart,
      end: node.selectionEnd,
      direction: node.selectionDirection,
      active: document.activeElement === node,
    };
  }
  function model() { return JSON.parse(window.__RF2_HIC_TB__.model()); }
  // Let a concurrent root's sync lane land. A keystroke does not need this
  // — \`converge!\` spends a \`flushSync\` so the echo is inside the turn —
  // but an ordinary click spends none, so anything driven by a button is
  // read on the next task rather than the next line.
  function settle() { return new Promise(function (r) { setTimeout(r, 0); }); }
  return {
    el: el, nativeSet: nativeSet, fire: fire, composition: composition,
    edit: edit, read: read, model: model, settle: settle,
  };
})();
`;

// ---------------------------------------------------------------------------
// Assertion vocabulary — every failure names the engine, the field and the
// property, because a row that reds in one engine only is the finding.
// ---------------------------------------------------------------------------

class Witness {
  constructor(engine) {
    this.engine = engine;
    this.checks = 0;
    this.recorded = {};
    // What each named section banked, so the runner can require the
    // sections BY NAME rather than by a total a deleted section survives.
    this.sections = {};
  }

  eq(actual, expected, what) {
    this.checks += 1;
    if (actual !== expected) {
      throw new Error(
        `[${this.engine}] ${what}: expected ${JSON.stringify(expected)}, ` +
        `got ${JSON.stringify(actual)}`);
    }
  }

  // A row whose conduct is MEASURED rather than required. The runner
  // compares these across engines and reds on a divergence no narrowing
  // names, so recording is not a way of not asserting.
  record(key, value) {
    this.recorded[key] = value;
  }
}

// ---------------------------------------------------------------------------
// The witnesses
// ---------------------------------------------------------------------------

// I15 — "converge within the turn that edited them" and "echo only
// committed state". Read on the line after the dispatch, before the task
// yields.
//
// These rows are the invariant as stated, and each one's expectation was
// flipped to confirm it is wired to a real reading. They do NOT attribute
// the conduct to this runtime, and the runner's `## Coverage` block says
// why it was measured rather than argued: React's own end-of-event restore
// corrects any value the converge got wrong, in the same discrete event.
// The rows that isolate this runtime are the caret rows below.
async function sameTurnConvergence(page, w) {
  const grouped = await page.evaluate(() =>
    // "1,234" with the caret at the end; the user types "5".
    window.__TB__.edit('grouped', '1,2345', 6, { data: '5' }));
  w.eq(grouped.value, '12,345', 'grouped normalises within the edit turn');

  const plain = await page.evaluate(() =>
    window.__TB__.edit('plain', 'abcd', 4, { data: 'd' }));
  // The converge's own trap: hand `converge-to!` the handler's stale
  // closure value instead of the element's record and this row shows
  // "abc" — the accepted keystroke wiped off the screen.
  w.eq(plain.value, 'abcd', 'an accepted keystroke survives the converge');

  const digits = await page.evaluate(() =>
    window.__TB__.edit('digits', '123x', 4, { data: 'x' }));
  w.eq(digits.value, '123', 'a refused edit echoes the committed value in-turn');

  // Owned `:value` wins by PRESENCE, not truthiness. This field's model is
  // "" forever; a runtime that read `value` for truth would call the
  // element uncontrolled and leave the typed character on screen.
  const empty = await page.evaluate(() =>
    window.__TB__.edit('empty', 'x', 1, { data: 'x' }));
  w.eq(empty.value, '', 'an empty owned value is present, not falsy');

  const model = await page.evaluate(() => window.__TB__.model());
  w.eq(model.fields.grouped, '12,345', 'the store holds the normalised value');
  w.eq(model.fields.plain, 'abcd', 'the store took the accepted keystroke');
  w.eq(model.fields.digits, '123', 'the store did not move on a refusal');
  w.eq(model.fields.empty, '', 'the store did not move on the empty field');
}

// `beforeinput` is CARRIED by every edit this spec dispatches, and until
// rf2-hic-016's second pass nothing distinguished a run with it from a run
// without — the `{ beforeinput: false }` knob on `edit` had no caller.
// Carrying an event is not witnessing it, so this section makes the knob
// load-bearing in both directions: `beforeinput` alone moves nothing, and
// an `input` with no `beforeinput` in front of it converges exactly as one
// with. That is what makes the composition SEQUENCE below faithful rather
// than decorative — the sequence is the browser's, and the converge hangs
// off the `input` in it.
async function beforeinputDoesNotDriveTheConverge(page, w) {
  const alone = await page.evaluate(() => {
    const node = window.__TB__.el('plain');
    node.focus();
    const before = window.__TB__.model();
    const draft = before.fields.plain + 'Z';
    // The keystroke, up to but NOT including the `input` event.
    window.__TB__.nativeSet(node, draft);
    node.setSelectionRange(draft.length, draft.length);
    window.__TB__.fire(node, 'beforeinput', { inputType: 'insertText', data: 'Z' });
    const held = window.__TB__.read('plain').value;
    const mid = window.__TB__.model();
    // …and now the rest of it, so the field is not left diverged and the
    // contrast is the same event pair completed.
    window.__TB__.fire(node, 'input', { inputType: 'insertText', data: 'Z' });
    return {
      base: before.fields.plain, editsBefore: before.edits.plain, draft, held,
      midModel: mid.fields.plain, midEdits: mid.edits.plain,
      after: window.__TB__.read('plain').value,
      afterModel: window.__TB__.model().fields.plain,
      afterEdits: window.__TB__.model().edits.plain,
    };
  });
  w.eq(alone.midModel, alone.base, 'a beforeinput alone does not move the model');
  w.eq(alone.midEdits, alone.editsBefore, 'and dispatches no intent at all');
  w.eq(alone.held, alone.draft, 'nor does it converge the field out from under the draft');
  w.eq(alone.afterModel, alone.draft, 'the `input` that follows is what moves the model');
  w.eq(alone.afterEdits, alone.editsBefore + 1, 'exactly one intent, from the `input`');

  // The other direction: no `beforeinput` at all. "12,345" with the caret
  // after "12,3"; the user types "9" -> field "12,3945" caret 5 -> model
  // "123,945", and the caret is still after the 9 at 5.
  const grouped = await page.evaluate(() =>
    window.__TB__.edit('grouped', '12,3945', 5, { data: '9', beforeinput: false }));
  w.eq(grouped.value, '123,945', 'an input with no beforeinput converges all the same');
  w.eq(grouped.start, 5, 'with the caret where the edit left it');
}

// I15 — "preserve caret across that echo". The caret is the property that
// separates this runtime from plain React, which converges in the same
// discrete event and throws the caret to the end of the control. Every row
// here therefore edits in the MIDDLE of the string; a row that typed at the
// end would pass under either conduct.
async function caretAcrossTheEcho(page, w) {
  // Length-CHANGING normalisation — the only case that distinguishes an
  // offset from the end of the string from an absolute position.
  // "1,2|34" + "9" -> field "1,2934" caret 4 -> model "12,934", caret 4.
  const grouped = await page.evaluate(() =>
    window.__TB__.edit('grouped', '1,2934', 4, { data: '9' }));
  w.eq(grouped.value, '12,934', 'grouped regrouped around the inserted digit');
  w.eq(grouped.start, 4, 'the caret sits after the digit just typed, not at the end');

  // Length-PRESERVING normalisation, so a caret failure cannot hide behind
  // a change of length. "A|BC" + "x" -> "AxBC" caret 2 -> "AXBC", caret 2.
  const upper = await page.evaluate(() =>
    window.__TB__.edit('upper', 'AxBC', 2, { data: 'x' }));
  w.eq(upper.value, 'AXBC', 'upper normalised the inserted character');
  w.eq(upper.start, 2, 'the caret survived a same-length normalisation');

  // On a REFUSAL. "1|23" + "x" -> field "1x23" caret 2 -> model "123";
  // the offset from the end is 2, so the caret lands at 1 — where the
  // refused character would have gone.
  const digits = await page.evaluate(() =>
    window.__TB__.edit('digits', '1x23', 2, { data: 'x' }));
  w.eq(digits.value, '123', 'the refusal echoed');
  w.eq(digits.start, 1, 'the caret survived the refusal');

  // The other convergeable tag. "one| two" + " " -> "one  two" caret 4 ->
  // collapsed to "one two", offset from the end 4, caret 3.
  const notes = await page.evaluate(() =>
    window.__TB__.edit('notes', 'one  two', 4, { data: ' ' }));
  w.eq(notes.value, 'one two', 'the textarea normalised');
  w.eq(notes.start, 3, 'the caret survived on a textarea');

  // Every converge leaves a CARET rather than a range — `converge-to!`
  // restores one offset, which is the whole of rf2-n3dxw's honest limit.
  w.eq(grouped.start === grouped.end, true, 'the converge leaves a collapsed caret');
}

// The same claim again, this time with the browser's own trusted key
// events, so no caret row rests on a dispatched event alone. Read at
// quiescence — a keystroke cannot be read inside its own turn from here —
// which is exactly why the rows above exist beside these.
async function caretUnderRealTyping(page, w) {
  const field = page.locator('[data-testid="upper"]');
  await field.click();
  // Put the caret between "A" and "X" of the "AXBC" the rows above left.
  await page.evaluate(() => window.__TB__.el('upper').setSelectionRange(1, 1));
  await page.keyboard.type('q');
  const after = await page.evaluate(() => window.__TB__.read('upper'));
  w.eq(after.value, 'AQXBC', 'a real keystroke normalised');
  w.eq(after.start, 2, 'a real keystroke left the caret after the character typed');

  const groupedField = page.locator('[data-testid="grouped"]');
  await groupedField.click();
  await page.evaluate(() => window.__TB__.el('grouped').setSelectionRange(4, 4));
  await page.keyboard.type('7');
  const grouped = await page.evaluate(() => window.__TB__.read('grouped'));
  // "12,9|34" + "7" -> digits "129734" -> "129,734"; the offset from the
  // end was 2, so the caret is at 5, immediately after the 7.
  w.eq(grouped.value, '129,734', 'a real keystroke regrouped');
  w.eq(grouped.start, 5, 'a real keystroke kept the caret off the end');
}

// I15 — "preserve selection". A keystroke collapses a selection by itself,
// so the only place the question is live is an OUT-OF-BAND write: a model
// correction no keystroke caused, which `converge!` is deliberately not on
// (rf2-n3dxw). What survives there is React's own selection restore, and
// what it does is measured per engine rather than assumed.
async function selectionAcrossAnOutOfBandWrite(page, w) {
  const observed = await page.evaluate(async () => {
    const node = window.__TB__.el('upper');
    node.focus();
    node.setSelectionRange(1, 3, 'backward');
    const before = window.__TB__.read('upper');
    // A programmatic click never moves focus, so the field under
    // observation keeps it and React's restore is in scope.
    window.__TB__.el('correct-upper').click();
    // Read on the next task, NOT the next line, and the difference is the
    // whole of why an out-of-band write is a different path. A concurrent
    // root flushes a discrete update's sync lane in a microtask; the
    // keystroke rows above are inside the turn only because `converge!`
    // spends a `flushSync` to put them there, and no `flushSync` is
    // spent here because there is no caret to save.
    const immediate = window.__TB__.read('upper');
    await new Promise((r) => setTimeout(r, 0));
    return { before, immediate, after: window.__TB__.read('upper') };
  });
  w.record('out-of-band-write-lands-in-the-same-task',
    observed.immediate.value === 'ZZZZZZ');
  w.eq(observed.before.end - observed.before.start, 2, 'a range was selected to begin with');
  // The PREMISE of the recorded direction below, which went unread until
  // rf2-hic-016's second pass. An engine that had never honoured
  // `'backward'` would have recorded the same post-write direction as the
  // others and agreed with them perfectly — three engines agreeing about a
  // selection that was never directional. Recording an outcome whose
  // premise is unread is the cross-engine comparator's one blind spot.
  w.eq(observed.before.direction, 'backward',
    'the selection really was directional before the write');
  w.eq(observed.after.value, 'ZZZZZZ', 'the out-of-band correction reached the field');
  w.eq(observed.after.active, true, 'the field still holds focus across the correction');
  w.record('selection-across-out-of-band-write', {
    start: observed.after.start,
    end: observed.after.end,
    direction: observed.after.direction,
    collapsed: observed.after.start === observed.after.end,
  });
}

// I15 — "preserve in-flight composition". The carve-out (rf2-digtt): while
// a composition is live nothing writes the field, and the model's refusal
// lands whole and visible at `compositionend`. Driven on the REFUSING
// field, which is the case the CDP harness measured plain React destroying.
async function compositionSafety(page, w) {
  const run = await page.evaluate(() => {
    const node = window.__TB__.el('digits');
    node.focus();
    const seen = [];
    const log = (t) => seen.push(t);
    node.addEventListener('compositionstart', () => log('start'));
    node.addEventListener('compositionend', () => log('end'));

    window.__TB__.composition(node, 'compositionstart', '');
    const started = window.__TB__.read('digits').value;

    // First composing update. Read on the next line: if the converge ran
    // here the field would already show the model's refusal.
    const draft1 = window.__TB__.edit('digits', '123あ', 4, {
      isComposing: true, inputType: 'insertCompositionText', data: 'あ',
    });

    const draft2 = window.__TB__.edit('digits', '123あい', 5, {
      isComposing: true, inputType: 'insertCompositionText', data: 'あい',
    });

    const midModel = window.__TB__.model().fields.digits;

    window.__TB__.composition(node, 'compositionend', 'あい');
    const ended = window.__TB__.read('digits');

    return {
      started, draft1, draft2, midModel, ended, seen,
      startCount: seen.filter((t) => t === 'start').length,
      endCount: seen.filter((t) => t === 'end').length,
    };
  });

  w.eq(run.draft1.value, '123あ', 'the first composing update survives in the field');
  w.eq(run.draft2.value, '123あい', 'the second composing update survives too');
  w.eq(run.midModel, '123', 'the refusing model never moved during the exchange');
  // The exchange was not destroyed: one start, one end. A value write
  // landing mid-composition is what mints a second `compositionstart` in
  // the CDP harness, and a synthetic exchange can at least witness that
  // this runtime wrote nothing.
  w.eq(run.startCount, 1, 'exactly one compositionstart');
  w.eq(run.endCount, 1, 'exactly one compositionend');
  // And the refusal lands whole, at the close, visibly.
  w.eq(run.ended.value, '123', 'the refusal lands at compositionend');

  const model = await page.evaluate(() => window.__TB__.model());
  w.eq(model.fields.digits, '123', 'the store still holds the committed value');
}

// The carve-out's safety rider: every path out of a composition releases
// the shadow, so a field cannot be stranded showing a draft the model never
// agreed to. `compositionend` is witnessed above; these are the other two
// that do not go through it.
async function compositionReleaseEdges(page, w) {
  // Blur — the composition the browser abandoned with the focus.
  const blurred = await page.evaluate(async () => {
    const node = window.__TB__.el('digits');
    node.focus();
    window.__TB__.composition(node, 'compositionstart', '');
    window.__TB__.edit('digits', '123あ', 4, {
      isComposing: true, inputType: 'insertCompositionText', data: 'あ',
    });
    const held = window.__TB__.read('digits').value;
    // A blurred field has no caret worth restoring, so the release spends
    // no `flushSync` and the model re-asserts on the next task rather than
    // this line — the deliberate asymmetry `shadowed-props` states.
    node.blur();
    await window.__TB__.settle();
    return { held, after: window.__TB__.read('digits').value };
  });
  w.eq(blurred.held, '123あ', 'the draft was held before the blur');
  w.eq(blurred.after, '123', 'blur releases the shadow and the model re-asserts');

  // A NON-COMPOSING change — the path that recovers an exchange some other
  // write aborted silently, since the abort itself fires nothing.
  const recovered = await page.evaluate(() => {
    const node = window.__TB__.el('digits');
    node.focus();
    window.__TB__.composition(node, 'compositionstart', '');
    window.__TB__.edit('digits', '123あ', 4, {
      isComposing: true, inputType: 'insertCompositionText', data: 'あ',
    });
    const held = window.__TB__.read('digits').value;
    // No compositionend: just an ordinary keystroke.
    const plain = window.__TB__.edit('digits', '1234', 4, { data: '4' });
    return { held, plain };
  });
  w.eq(recovered.held, '123あ', 'the draft was held');
  w.eq(recovered.plain.value, '1234', 'a non-composing change releases and converges');

  // Unmount — the shadow is one `useState` on a fiber and cannot outlive
  // it. There is no registry and no node property to strand.
  const unmounted = await page.evaluate(async () => {
    const node = window.__TB__.el('mountable');
    node.focus();
    window.__TB__.composition(node, 'compositionstart', '');
    // A REFUSING field, so the draft in the element is one the model never
    // took — without that the row below would pass on a model that had
    // simply accepted the composition.
    const draft = window.__TB__.edit('mountable', '9あ', 2, {
      isComposing: true, inputType: 'insertCompositionText', data: 'あ',
    });
    const midModel = window.__TB__.model().fields.mountable;
    window.__TB__.el('toggle-mounted').click();
    await window.__TB__.settle();
    const gone = !!document.querySelector('[data-testid="mountable-gone"]');
    window.__TB__.el('toggle-mounted').click();
    await window.__TB__.settle();
    return { draft: draft.value, midModel, gone, back: window.__TB__.read('mountable').value };
  });
  w.eq(unmounted.draft, '9あ', 'the field held a draft the model had refused');
  w.eq(unmounted.midModel, '9', 'and the model really had refused it');
  w.eq(unmounted.gone, true, 'the field unmounted mid-composition');
  w.eq(unmounted.back, '9', 'the remounted field shows the model, not a stranded draft');
}

// What the carve-out does NOT claim, measured rather than left to the
// prose. The shadow holds the DRAFT, but the author's handler still runs on
// every composing `input` — `controlled.cljs`'s `shadowed-props` calls
// `inner` before it branches on `composing-input?` — so a model that
// ACCEPTS moves right through the exchange. That is stated in
// `controlled.cljs` as the deferral's honest limit and was witnessed in
// Chromium alone (`front/revision_dom_cljs_test`), which is the wrong
// number of engines for a claim about how a discrete event is flushed.
//
// It is also the fact the manual native-IME checklist's "app-db clean
// until commit" and "arrives exactly once" contradict, and the arrival
// counter is what makes the difference visible: on a REFUSING field the
// snap-back looks the same whether the composing updates were dispatched
// or not, and on an ACCEPTING one a progressive write is visually
// idempotent. Only the count separates them.
async function anAcceptingModelDuringAComposition(page, w) {
  const run = await page.evaluate(() => {
    const node = window.__TB__.el('plain');
    node.focus();
    const before = window.__TB__.model();
    const base = before.fields.plain;
    window.__TB__.composition(node, 'compositionstart', '');

    const d1 = window.__TB__.edit('plain', base + 'あ', base.length + 1, {
      isComposing: true, inputType: 'insertCompositionText', data: 'あ',
    });
    const m1 = window.__TB__.model();
    const d2 = window.__TB__.edit('plain', base + 'あい', base.length + 2, {
      isComposing: true, inputType: 'insertCompositionText', data: 'あい',
    });
    const m2 = window.__TB__.model();

    window.__TB__.composition(node, 'compositionend', 'あい');
    return {
      base,
      edits0: before.edits.plain,
      d1: d1.value, m1: { v: m1.fields.plain, e: m1.edits.plain },
      d2: d2.value, m2: { v: m2.fields.plain, e: m2.edits.plain },
      ended: window.__TB__.read('plain').value,
      m3: window.__TB__.model(),
    };
  });

  w.eq(run.d1, run.base + 'あ', 'the first composing update stands in the field');
  w.eq(run.m1.v, run.base + 'あ', 'AND the accepting model took it — mid-composition');
  w.eq(run.m1.e, run.edits0 + 1, 'exactly one intent arrived for it');
  w.eq(run.d2, run.base + 'あい', 'the second composing update stands too');
  w.eq(run.m2.v, run.base + 'あい', 'and the model took that one as well');
  w.eq(run.m2.e, run.edits0 + 2, 'one intent per composing input, no more and no fewer');
  // So the committed text does not "arrive once, at the close": it arrived
  // progressively, and `compositionend` adds nothing to it. What the close
  // does is converge the field to whatever the model then holds.
  w.eq(run.m3.edits.plain, run.edits0 + 2, 'compositionend dispatches no intent of its own');
  w.eq(run.ended, run.m3.fields.plain, 'and the field converges to the model at the close');
}

// I15 — "reset is an explicit revision that preserves element identity".
// The divergence is created the way a password manager creates one: a value
// write with no event, which no handler sees and no commit is scheduled for.
async function revisionResetPreservesIdentity(page, w) {
  const observed = await page.evaluate(async () => {
    const node = window.__TB__.el('revision');
    node.__tbMark = 'M1';
    window.__TB__.nativeSet(node, 'drifted');
    const drifted = window.__TB__.read('revision').value;
    // A turn passes with no revision change: the draft must survive it,
    // or the row below would be measuring an incidental commit.
    await new Promise((r) => setTimeout(r, 50));
    const survived = window.__TB__.read('revision').value;
    const modelBefore = window.__TB__.model();

    window.__TB__.el('bump-revision').click();
    await window.__TB__.settle();
    const after = window.__TB__.read('revision');
    const same = window.__TB__.el('revision');
    return {
      drifted, survived, modelBefore, after,
      mark: same.__tbMark,
      identical: same === node,
      revision: window.__TB__.model().revision,
    };
  });
  w.eq(observed.drifted, 'drifted', 'the eventless write reached the field');
  w.eq(observed.survived, 'drifted', 'and survived a turn — nothing else reset it');
  w.eq(observed.modelBefore.fields.revision, 'keep', 'the store never saw the draft');
  w.eq(observed.after.value, 'keep', 'the revision bump re-baselined the field to the model');
  w.eq(observed.revision, 1, 'exactly one revision advance');
  w.eq(observed.mark, 'M1', 'the DOM node survived the reset — no remount');
  w.eq(observed.identical, true, 'and it is the same node the document still holds');
}

// The reset's other half: a revision that arrives while a composition is
// LIVE. `controlled.cljs` documents the deferral at length — there is no
// cancel primitive, and the only immediate write available (`element.value`)
// aborts the exchange — and `front/revision_dom_cljs_test` asserted it on
// the Chromium-only `:browser-test` lane. A deferral is a claim about the
// order an engine flushes a discrete event's work, so it belongs here.
//
// ## Why it takes two fields, and what one of them alone could not say
//
// The section shipped on the ACCEPTING `revision` field and the #7815
// audit found it non-discriminating there, correctly: by the time the bump
// fires, the composing `:tb/edit` has already moved that field's model to
// `keepあ`, so an immediate reassertion and a deferred one have the SAME
// string to write and the row reads `keepあ` under either. A witness whose
// expected value does not move when the law is broken is decoration.
//
// `revision-strict` is the repair, and the whole of it is a model policy:
// it REFUSES the kana, so while the draft is on screen the reset's target
// is `42` and the draft is `42あ`. Now the two conducts are two different
// strings, and the mutation the row names — implementing the reset as the
// immediate `element.value` write `controlled.cljs` rejects — turns it red
// on the line that reads the field mid-exchange.
//
// What is NOT claimed, because a refusing field cannot show it: that the
// reset "landed" at the close. Every release path converges the field to
// the then-current model whether a revision moved or not, so on a refusing
// field the reset's own contribution at the close is not separable from
// the ordinary release converge. The deferral's observable content is that
// the exchange survived it, and that is what these rows read.
//
// The accepting field keeps its rows, because they carry the other half:
// `controlled.cljs`'s honest limit, that the deferral cannot promise the
// reset survives.
//
// The bump is a PROGRAMMATIC click, which never moves focus; the operator's
// equivalent is the armed button below, because a real pointer-down would
// close the composition before the reset could arrive mid-exchange.
async function aRevisionArrivingMidComposition(page, w) {
  // (1) The REFUSING field — where the reset has something different to
  // write, so the deferral is observable at all.
  const strict = await page.evaluate(async () => {
    const node = window.__TB__.el('revision-strict');
    node.focus();
    const seen = [];
    node.addEventListener('compositionstart', () => seen.push('start'));
    node.addEventListener('compositionend', () => seen.push('end'));
    const before = window.__TB__.model();

    window.__TB__.composition(node, 'compositionstart', '');
    const draft = window.__TB__.edit('revision-strict', '42あ', 3, {
      isComposing: true, inputType: 'insertCompositionText', data: 'あ',
    });
    const mid = window.__TB__.model();

    window.__TB__.el('bump-revision').click();
    await window.__TB__.settle();
    const during = window.__TB__.read('revision-strict');

    window.__TB__.composition(node, 'compositionend', 'あ');
    const after = window.__TB__.model();
    return {
      revisionBefore: before.revision,
      editsBefore: before.edits['revision-strict'] || 0,
      draft: draft.value,
      midModel: mid.fields['revision-strict'],
      midEdits: mid.edits['revision-strict'],
      during,
      revision: after.revision,
      model: after.fields['revision-strict'],
      value: window.__TB__.read('revision-strict').value,
      startCount: seen.filter((t) => t === 'start').length,
      endCount: seen.filter((t) => t === 'end').length,
    };
  });

  w.eq(strict.draft, '42あ', 'the field held the composing draft');
  // The PREMISE that makes this field different from the accepting one:
  // the update reached the store and the store turned it down, so the
  // reset's target and the draft are two different strings.
  w.eq(strict.midEdits, strict.editsBefore + 1, 'the composing update reached the store');
  w.eq(strict.midModel, '42', 'and the refusing model kept none of it');
  w.eq(strict.revision, strict.revisionBefore + 1, 'the revision really did advance mid-exchange');
  // THE DEFERRAL, on the only field that can red on it. A reset that
  // landed immediately would have written `42` over a live composition —
  // the abort the carve-out exists to prevent, and the conduct the
  // accepting field below cannot distinguish.
  w.eq(strict.during.value, '42あ',
    'the reset deferred: the draft is untouched while the exchange is open');
  // …and the exchange it did not disturb is intact: a mid-composition
  // value write is what mints a second `compositionstart` in the CDP
  // harness.
  w.eq(strict.startCount, 1, 'still exactly one compositionstart');
  w.eq(strict.endCount, 1, 'and exactly one compositionend');
  w.eq(strict.value, strict.model, 'and the field converges to the model at the close');
  w.eq(strict.model, '42', 'which is the value the refusal left standing');

  // (2) The ACCEPTING field — `controlled.cljs`'s honest limit. Nothing
  // here can red on the deferral (that is (1)'s job); what it pins is the
  // sentence the namespace refuses to overclaim.
  const observed = await page.evaluate(async () => {
    const node = window.__TB__.el('revision');
    node.focus();
    const revisionBefore = window.__TB__.model().revision;
    window.__TB__.composition(node, 'compositionstart', '');
    const draft = window.__TB__.edit('revision', 'keepあ', 5, {
      isComposing: true, inputType: 'insertCompositionText', data: 'あ',
    });

    window.__TB__.el('bump-revision').click();
    await window.__TB__.settle();
    const during = window.__TB__.read('revision');

    window.__TB__.composition(node, 'compositionend', 'あ');
    const model = window.__TB__.model();
    return {
      revisionBefore, draft: draft.value, during,
      after: window.__TB__.read('revision'),
      model: model.fields.revision,
      revision: model.revision,
      mark: window.__TB__.el('revision').__tbMark,
    };
  });

  w.eq(observed.draft, 'keepあ', 'the field held the composing draft');
  w.eq(observed.revision, observed.revisionBefore + 1, 'a second revision advanced mid-exchange');
  w.eq(observed.during.value, 'keepあ', 'the draft is untouched here too');
  w.eq(observed.after.value, observed.model, 'and the field converges at the close');
  // The honest limit, as `controlled.cljs` states it: on an ACCEPTING field
  // the composing updates the model kept taking supersede the reset by
  // ordinary event order. "The deferral cannot strand the field" is what is
  // true; "the reset cannot be lost" is not.
  w.eq(observed.model, 'keepあ',
    'an accepting model kept the composing update, so the reset did not win');
  w.eq(observed.mark, 'M1', 'and the DOM node survived the whole exchange');
}

// The owned `::h/checked` pair, whose `false` is a presence rather than a
// truth. Read inside the click's own turn.
async function ownedCheckedPair(page, w) {
  const toggled = await page.evaluate(async () => {
    const node = window.__TB__.el('flag');
    const before = { dom: node.checked, model: window.__TB__.model().flag };
    node.click();
    await window.__TB__.settle();
    const on = { dom: node.checked, model: window.__TB__.model().flag };
    node.click();
    await window.__TB__.settle();
    const off = { dom: node.checked, model: window.__TB__.model().flag };
    return { before, on, off };
  });
  w.eq(toggled.before.dom, false, 'the checkbox starts unchecked');
  w.eq(toggled.before.model, false, 'and so does its model');
  w.eq(toggled.on.dom, true, 'the DOM followed the click');
  w.eq(toggled.on.model, true, 'and the store did too, in the same turn');
  w.eq(toggled.off.dom, false, 'and back — an owned false is a presence');
  w.eq(toggled.off.model, false, 'with the store agreeing');
}

// RECORDED, not gated (the conformance matrix is hic-040). A form reset
// returns a control to its `defaultValue`, and `defaultValue` is exactly
// the per-instance record `controlled/last-rendered` reads — so a reset is
// the one ordinary browser action that touches the converge's own
// bookkeeping. Autofill has no cross-engine drive (Chromium's is a CDP
// method and needs a profile), so the eventless write below is recorded as
// its PROXY and named as one.
async function formResetAndFillProxy(page, w) {
  const reset = await page.evaluate(async () => {
    const a = window.__TB__.el('form-a');
    window.__TB__.edit('form-a', 'FORMx', 5, { data: 'x' });
    const typed = window.__TB__.read('form-a').value;
    const defaultBefore = a.defaultValue;
    window.__TB__.el('form-reset').click();
    await window.__TB__.settle();
    return {
      typed,
      defaultBefore,
      afterValue: window.__TB__.read('form-a').value,
      afterDefault: a.defaultValue,
      model: window.__TB__.model().fields['form-a'],
    };
  });
  w.eq(reset.typed, 'FORMX', 'the form field normalised like any other');
  w.record('form-reset', {
    'default-value-mirrors-the-model': reset.defaultBefore === reset.typed,
    'value-after-reset': reset.afterValue,
    'model-after-reset': reset.model,
    'reset-is-visually-inert': reset.afterValue === reset.model,
  });

  const fill = await page.evaluate(async () => {
    const b = window.__TB__.el('form-b');
    // (1) a fill that dispatches an input event, which is what most
    // password managers do — indistinguishable from a keystroke.
    const withEvent = window.__TB__.edit('form-b', 'filled', 6, { inputType: 'insertReplacementText' });
    // (2) a fill that dispatches nothing, which is the pathological one.
    window.__TB__.nativeSet(b, 'silent');
    await new Promise((r) => setTimeout(r, 50));
    const silent = window.__TB__.read('form-b').value;
    window.__TB__.el('form-reset').click();
    await window.__TB__.settle();
    return {
      withEvent: withEvent.value,
      withEventModel: window.__TB__.model().fields['form-b'],
      silent,
      afterReset: window.__TB__.read('form-b').value,
    };
  });
  w.eq(fill.withEvent, 'filled', 'a fill carrying an input event converges like a keystroke');
  w.eq(fill.withEventModel, 'filled', 'and reaches the store');
  w.record('fill-proxy', {
    'eventless-fill-leaves-a-draft': fill.silent === 'silent',
    'form-reset-clears-the-eventless-draft': fill.afterReset === fill.withEventModel,
    'value-after-reset': fill.afterReset,
  });
}

// The operator's two instruments, and the only thing a driver can say
// about them: they are wired, and the action really is deferred. Their
// POINT — that a real pointer-down does not close the composition the
// action is meant to arrive inside — is not witnessable from here, because
// the click below is the only kind of click this harness has and the
// section above already uses the programmatic one. What would rot silently
// is the wiring, so the wiring is what this pins.
//
// BOTH arms, and that is the #7815 audit's finding: this pinned `arm-bump`
// alone while the section and the PR claimed both instruments were held,
// so a dead `arm-unmount` button — or an arm firing an event nobody
// registered — stayed green. Spending five seconds per arm per engine to
// find out is not the answer either. The answer is that the arm RESOLVES
// its event when it is armed and puts it in the readout, so the queued
// event is a thing on screen rather than a branch that will be taken
// later, and both arms are witnessed in the turn they are clicked.
//
// It still runs LAST deliberately: the armed dispatches land five seconds
// later, which is a wait no gate should spend and a bump no later section
// should receive. The page closes long before either fires.
// The armed delay this section drives at, and the ceiling it waits under.
// `ARM_MS` is passed to the page as `?arm-ms` AND asserted in the readout,
// so the two cannot drift.
const ARM_MS = 300;
const ARM_FIRE_CEILING_MS = 5000;

/** Wait for an armed edge to land, failing with the section's own words. */
async function waitForArm(page, predicate, arg, whatFailed) {
  try {
    await page.waitForFunction(predicate, arg, { timeout: ARM_FIRE_CEILING_MS });
  } catch {
    throw new Error(
      `${whatFailed} within ${ARM_FIRE_CEILING_MS}ms of arming at ${ARM_MS}ms. ` +
      'The readout said it was armed, so the arm dispatched; what did not ' +
      'happen is the deferred effect. Check that `:tb/arm` returns its ' +
      '`:dispatch-later` INSIDE `:fx` — re-frame2\'s effect-map is closed ' +
      'and a v1 top-level key is silently carried by nothing.');
  }
}

async function armedEdgesAreWired(page, w, ctx) {
  const before = await page.evaluate(() => ({
    label: window.__TB__.el('armed').textContent,
    revision: window.__TB__.model().revision,
    mounted: !!document.querySelector('[data-testid="mountable"]'),
  }));
  w.eq(before.label, 'idle', 'nothing is armed to begin with');

  await page.locator('[data-testid="arm-bump"]').click();
  const bump = await page.evaluate(() => ({
    label: window.__TB__.el('armed').textContent,
    revision: window.__TB__.model().revision,
  }));
  w.eq(bump.label, 'armed: bump -> [:tb/bump-revision] fires in 5s',
    'the page names the event the bump arm will fire');
  w.eq(bump.revision, before.revision, 'and nothing has happened yet — that is the whole point');

  await page.locator('[data-testid="arm-unmount"]').click();
  const unmount = await page.evaluate(() => ({
    label: window.__TB__.el('armed').textContent,
    mounted: !!document.querySelector('[data-testid="mountable"]'),
  }));
  w.eq(unmount.label, 'armed: unmount -> [:tb/toggle-mounted] fires in 5s',
    'and the event the unmount arm will fire, which nothing pinned before');
  w.eq(unmount.mounted, before.mounted, 'the field is still mounted — this one is deferred too');

  // AND THE FIRE. Everything above is equally true of an arm that never
  // armed: the label is rendered from the dispatch that set it, and
  // "nothing has happened yet" is exactly what a dead timer looks like.
  // Both arms shipped DEAD for that reason — `:tb/arm` returned a v1
  // top-level `:dispatch-later` beside `:db`, which re-frame2's closed
  // effect-map (`#{:db :rf.db/runtime :fx}`, migration M-8) does not
  // carry — and this section was green in three engines throughout,
  // while the operator instrument it claims to pin could not fire.
  // Measured 2026-08-11: 15s after the click the readout still read
  // `armed`, while a plain 5000ms `setTimeout` in the same page returned
  // in 5006ms.
  //
  // `?arm-ms` shortens the OPERATOR's five seconds for this section only.
  // It costs under a second per engine rather than the thirty the #7815
  // audit rightly refused, and the readout is rendered from the same
  // number, so a stuck default reads wrong here before anything is
  // waited on.
  //
  // The re-navigation carries the RUNNER's navigation ceiling rather than a
  // literal of its own: it is the same navigation, to the same server, as the
  // one the runner already made, and a second number here would be a second
  // budget for one operation, free to drift from it. Passing no number is the
  // defect `_navigation-ceiling-policy.test.cjs` exists to remove — Playwright
  // would apply an anonymous 30s default that no lane budget can reach — so a
  // missing ceiling REFUSES here rather than defaulting, which is the same
  // choice `examples/scripts/spec-helpers.cjs` makes for spec-side callers.
  const base = page.url().split('?')[0];
  if (typeof ctx.navTimeoutMs !== 'number') {
    throw new Error(
      'the runner must pass `navTimeoutMs` — a navigation with no ceiling ' +
      'inherits Playwright\'s 30s default, which is invisible in the source ' +
      'and unreachable from the runner\'s own knob');
  }
  await page.goto(`${base}?arm-ms=${ARM_MS}`,
    { waitUntil: ctx.navWaitUntil, timeout: ctx.navTimeoutMs });
  await page.waitForSelector('[data-testid="hicasso-controlled-testbed"]');

  const seed = await page.evaluate(() => window.__TB__.model().revision);
  await page.locator('[data-testid="arm-bump"]').click();
  w.eq(await page.evaluate(() => window.__TB__.el('armed').textContent),
    `armed: bump -> [:tb/bump-revision] fires in ${ARM_MS}ms`,
    'the readout is rendered from the delay actually armed, not a constant');

  await waitForArm(page, (r) => window.__TB__.model().revision > r, seed,
    'the armed bump never fired');
  const fired = await page.evaluate(() => ({
    revision: window.__TB__.model().revision,
    label: window.__TB__.el('armed').textContent,
  }));
  w.eq(fired.revision, seed + 1, 'the armed bump FIRES its event');
  w.eq(fired.label, 'idle', 'and the readout returns to idle when it does');

  await page.locator('[data-testid="arm-unmount"]').click();
  await waitForArm(page,
    () => document.querySelector('[data-testid="mountable-gone"]') !== null,
    null, 'the armed unmount never fired');
  w.eq(await page.evaluate(() =>
    document.querySelector('[data-testid="mountable"]') === null),
  true, 'and the armed unmount fires its own, different event');
}

// ---------------------------------------------------------------------------

// Every witness that must run, in order, under the name the runner pins it
// by. The order matters — several sections read fields the previous ones
// left — and the NAMES matter, because the runner's coverage floor requires
// this exact set and reds naming whatever is absent. A section deleted from
// this list therefore fails the gate rather than shrinking a total.
const SECTIONS = [
  ['same-turn-convergence', sameTurnConvergence],
  ['beforeinput-does-not-drive-the-converge', beforeinputDoesNotDriveTheConverge],
  ['caret-across-the-echo', caretAcrossTheEcho],
  ['caret-under-real-typing', caretUnderRealTyping],
  ['selection-across-an-out-of-band-write', selectionAcrossAnOutOfBandWrite],
  ['composition-safety', compositionSafety],
  ['composition-release-edges', compositionReleaseEdges],
  ['an-accepting-model-during-a-composition', anAcceptingModelDuringAComposition],
  ['revision-reset-preserves-identity', revisionResetPreservesIdentity],
  ['a-revision-arriving-mid-composition', aRevisionArrivingMidComposition],
  ['owned-checked-pair', ownedCheckedPair],
  ['form-reset-and-fill-proxy', formResetAndFillProxy],
  ['armed-edges-are-wired', armedEdgesAreWired],
];

module.exports = {
  name: 'Hicasso controlled input (I15) — three engines',
  url: '/index.html',
  pageHelpers: PAGE_HELPERS,

  run: async (page, ctx) => {
    const w = new Witness(ctx.engine);
    for (const [name, section] of SECTIONS) {
      const before = w.checks;
      // `ctx` reaches every section so one that navigates is bounded by the
      // runner's own ceiling rather than a literal of its own.
      await section(page, w, ctx);
      w.sections[name] = w.checks - before;
    }
    return { checks: w.checks, recorded: w.recorded, sections: w.sections };
  },
};
