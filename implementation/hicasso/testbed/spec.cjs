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
  return {
    el: el, nativeSet: nativeSet, fire: fire, composition: composition,
    edit: edit, read: read, model: model,
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
// yields: a runtime that converged a frame later would still show the
// draft here.
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
  const observed = await page.evaluate(() => {
    const node = window.__TB__.el('upper');
    node.focus();
    node.setSelectionRange(1, 3, 'backward');
    const before = window.__TB__.read('upper');
    // A programmatic click never moves focus, so the field under
    // observation keeps it and React's restore is in scope.
    window.__TB__.el('correct-upper').click();
    return { before, after: window.__TB__.read('upper') };
  });
  w.eq(observed.before.end - observed.before.start, 2, 'a range was selected to begin with');
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
  const blurred = await page.evaluate(() => {
    const node = window.__TB__.el('digits');
    node.focus();
    window.__TB__.composition(node, 'compositionstart', '');
    window.__TB__.edit('digits', '123あ', 4, {
      isComposing: true, inputType: 'insertCompositionText', data: 'あ',
    });
    const held = window.__TB__.read('digits').value;
    node.blur();
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
  const unmounted = await page.evaluate(() => {
    const node = window.__TB__.el('mountable');
    node.focus();
    window.__TB__.composition(node, 'compositionstart', '');
    window.__TB__.edit('mountable', 'draft', 5, {
      isComposing: true, inputType: 'insertCompositionText', data: 'draft',
    });
    window.__TB__.el('toggle-mounted').click();
    const gone = !!document.querySelector('[data-testid="mountable-gone"]');
    window.__TB__.el('toggle-mounted').click();
    return { gone, back: window.__TB__.read('mountable').value };
  });
  w.eq(unmounted.gone, true, 'the field unmounted mid-composition');
  w.eq(unmounted.back, '', 'the remounted field shows the model, not a stranded draft');
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

// The owned `::h/checked` pair, whose `false` is a presence rather than a
// truth. Read inside the click's own turn.
async function ownedCheckedPair(page, w) {
  const toggled = await page.evaluate(() => {
    const node = window.__TB__.el('flag');
    const before = { dom: node.checked, model: window.__TB__.model().flag };
    node.click();
    const on = { dom: node.checked, model: window.__TB__.model().flag };
    node.click();
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
  const reset = await page.evaluate(() => {
    const a = window.__TB__.el('form-a');
    window.__TB__.edit('form-a', 'FORMx', 5, { data: 'x' });
    const typed = window.__TB__.read('form-a').value;
    const defaultBefore = a.defaultValue;
    window.__TB__.el('form-reset').click();
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

// ---------------------------------------------------------------------------

module.exports = {
  name: 'Hicasso controlled input (I15) — three engines',
  url: '/index.html',
  pageHelpers: PAGE_HELPERS,

  // Every row that must hold in every engine, in order. The runner reports
  // the check count and refuses a run that banked too few, so a section
  // that silently stopped running cannot pass as green.
  run: async (page, ctx) => {
    const w = new Witness(ctx.engine);
    await sameTurnConvergence(page, w);
    await caretAcrossTheEcho(page, w);
    await caretUnderRealTyping(page, w);
    await selectionAcrossAnOutOfBandWrite(page, w);
    await compositionSafety(page, w);
    await compositionReleaseEdges(page, w);
    await revisionResetPreservesIdentity(page, w);
    await ownedCheckedPair(page, w);
    await formResetAndFillProxy(page, w);
    return { checks: w.checks, recorded: w.recorded };
  },
};
