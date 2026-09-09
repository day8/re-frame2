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
 * (`hicasso/test/re_frame/bench/hicasso/ime_run.cjs`) states its scope as
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
  //
  // SELECT was added by rf2-hic-040 and is the same mechanism for the same
  // reason: React installs its value tracker on select as well as on input
  // and textarea, so a select driven through the patched setter would look
  // unchanged to React and the controlled restore this bead measures would
  // never run.
  function nativeSet(node, v) {
    var proto = node.tagName === 'TEXTAREA'
      ? HTMLTextAreaElement.prototype
      : node.tagName === 'SELECT'
        ? HTMLSelectElement.prototype
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

  // --- rf2-hic-040's vocabulary --------------------------------------------
  // \`read\` touches \`selectionStart\`, which is not applicable to a
  // checkbox, a radio, a select, a file input or a number/date/range field.
  // Reading it there is engine conduct rather than a property of this
  // runtime — some engines answer null, some have historically thrown — so
  // the roster's non-text controls get a reader that touches nothing they
  // do not have.
  function plain(id) {
    var n = el(id);
    return { value: n.value, checked: n.checked, active: document.activeElement === n };
  }
  // Whether the selection API is applicable at all, ASKED rather than
  // assumed, and answered without failing the run either way.
  function selectionProbe(id) {
    try { return { ok: true, start: el(id).selectionStart }; }
    catch (e) { return { ok: false, error: e && e.name }; }
  }
  function selected(id) {
    return Array.prototype.map.call(el(id).selectedOptions, function (o) { return o.value; });
  }
  // Move a select's selection the way a user does — through the OPTIONS,
  // not through \`select.value\`, which can only ever express one of them —
  // and then let the browser's own change event out.
  //
  // \`chosen\` and \`dotValue\` are captured BEFORE the event goes
  // out, and that ordering is the whole reason this helper exists rather
  // than two lines at each call site. The first cut read the selection
  // after \`dispatchEvent\` and every engine reported one option where two
  // had been chosen — because React's controlled restore had ALREADY run
  // inside the dispatch and converged the element. The row that was meant
  // to establish "the user chose two" was reading the aftermath of the
  // conduct it was the premise for.
  function choose(id, values) {
    var n = el(id);
    Array.prototype.forEach.call(n.options, function (o) {
      o.selected = values.indexOf(o.value) >= 0;
    });
    var chosen = selected(id);
    // \`HTMLSelectElement.value\` — the PLATFORM's scalar answer, which the
    // standard defines as the first selected option in tree order and does
    // not redefine for a \`multiple\` select. Captured so the rows below can
    // state what the marker is NOT: \`impl.intent/target-value\` reads
    // \`selectedOptions\` on this control (rf2-42vlw), and the gap between
    // this scalar and that list is the whole of what the fix recovered.
    var dotValue = n.value;
    n.dispatchEvent(new Event('change', { bubbles: true }));
    return {
      chosen: chosen,
      dotValue: dotValue,
      value: n.value,
      selected: selected(id),
    };
  }
  // Every attribute actually on the node, by the name the DOM gave it.
  // The point of reading names rather than asking \`getAttribute\` for the
  // one expected is that a slot rule which emitted a DIFFERENT name is
  // then visible instead of merely absent.
  function attrs(id) {
    var n = el(id);
    var out = {};
    for (var i = 0; i < n.attributes.length; i += 1) {
      out[n.attributes[i].name] = n.attributes[i].value;
    }
    return out;
  }
  function form() { return el('form'); }
  function formData() {
    var out = {};
    var fd = new FormData(form());
    fd.forEach(function (v, k) {
      if (Object.prototype.hasOwnProperty.call(out, k)) { out[k] = [].concat(out[k], v); }
      else { out[k] = v; }
    });
    return out;
  }
  return {
    el: el, nativeSet: nativeSet, fire: fire, composition: composition,
    edit: edit, read: read, model: model, settle: settle,
    plain: plain, selectionProbe: selectionProbe, selected: selected,
    choose: choose, attrs: attrs, form: form, formData: formData,
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
async function sameTurnConvergence(page, witness) {
  const grouped = await page.evaluate(() =>
    // "1,234" with the caret at the end; the user types "5".
    window.__TB__.edit('grouped', '1,2345', 6, { data: '5' }));
  witness.eq(grouped.value, '12,345', 'grouped normalises within the edit turn');

  const plain = await page.evaluate(() =>
    window.__TB__.edit('plain', 'abcd', 4, { data: 'd' }));
  // The converge's own trap: hand `converge-to!` the handler's stale
  // closure value instead of the element's record and this row shows
  // "abc" — the accepted keystroke wiped off the screen.
  witness.eq(plain.value, 'abcd', 'an accepted keystroke survives the converge');

  const digits = await page.evaluate(() =>
    window.__TB__.edit('digits', '123x', 4, { data: 'x' }));
  witness.eq(digits.value, '123', 'a refused edit echoes the committed value in-turn');

  // Owned `:value` wins by PRESENCE, not truthiness. This field's model is
  // "" forever; a runtime that read `value` for truth would call the
  // element uncontrolled and leave the typed character on screen.
  const empty = await page.evaluate(() =>
    window.__TB__.edit('empty', 'x', 1, { data: 'x' }));
  witness.eq(empty.value, '', 'an empty owned value is present, not falsy');

  const model = await page.evaluate(() => window.__TB__.model());
  witness.eq(model.fields.grouped, '12,345', 'the store holds the normalised value');
  witness.eq(model.fields.plain, 'abcd', 'the store took the accepted keystroke');
  witness.eq(model.fields.digits, '123', 'the store did not move on a refusal');
  witness.eq(model.fields.empty, '', 'the store did not move on the empty field');
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
async function beforeinputDoesNotDriveTheConverge(page, witness) {
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
  witness.eq(alone.midModel, alone.base, 'a beforeinput alone does not move the model');
  witness.eq(alone.midEdits, alone.editsBefore, 'and dispatches no intent at all');
  witness.eq(alone.held, alone.draft, 'nor does it converge the field out from under the draft');
  witness.eq(alone.afterModel, alone.draft, 'the `input` that follows is what moves the model');
  witness.eq(alone.afterEdits, alone.editsBefore + 1, 'exactly one intent, from the `input`');

  // The other direction: no `beforeinput` at all. "12,345" with the caret
  // after "12,3"; the user types "9" -> field "12,3945" caret 5 -> model
  // "123,945", and the caret is still after the 9 at 5.
  const grouped = await page.evaluate(() =>
    window.__TB__.edit('grouped', '12,3945', 5, { data: '9', beforeinput: false }));
  witness.eq(grouped.value, '123,945', 'an input with no beforeinput converges all the same');
  witness.eq(grouped.start, 5, 'with the caret where the edit left it');
}

// I15 — "preserve caret across that echo". The caret is the property that
// separates this runtime from plain React, which converges in the same
// discrete event and throws the caret to the end of the control. Every row
// here therefore edits in the MIDDLE of the string; a row that typed at the
// end would pass under either conduct.
async function caretAcrossTheEcho(page, witness) {
  // Length-CHANGING normalisation — the only case that distinguishes an
  // offset from the end of the string from an absolute position.
  // "1,2|34" + "9" -> field "1,2934" caret 4 -> model "12,934", caret 4.
  const grouped = await page.evaluate(() =>
    window.__TB__.edit('grouped', '1,2934', 4, { data: '9' }));
  witness.eq(grouped.value, '12,934', 'grouped regrouped around the inserted digit');
  witness.eq(grouped.start, 4, 'the caret sits after the digit just typed, not at the end');

  // Length-PRESERVING normalisation, so a caret failure cannot hide behind
  // a change of length. "A|BC" + "x" -> "AxBC" caret 2 -> "AXBC", caret 2.
  const upper = await page.evaluate(() =>
    window.__TB__.edit('upper', 'AxBC', 2, { data: 'x' }));
  witness.eq(upper.value, 'AXBC', 'upper normalised the inserted character');
  witness.eq(upper.start, 2, 'the caret survived a same-length normalisation');

  // On a REFUSAL. "1|23" + "x" -> field "1x23" caret 2 -> model "123";
  // the offset from the end is 2, so the caret lands at 1 — where the
  // refused character would have gone.
  const digits = await page.evaluate(() =>
    window.__TB__.edit('digits', '1x23', 2, { data: 'x' }));
  witness.eq(digits.value, '123', 'the refusal echoed');
  witness.eq(digits.start, 1, 'the caret survived the refusal');

  // The other convergeable tag. "one| two" + " " -> "one  two" caret 4 ->
  // collapsed to "one two", offset from the end 4, caret 3.
  const notes = await page.evaluate(() =>
    window.__TB__.edit('notes', 'one  two', 4, { data: ' ' }));
  witness.eq(notes.value, 'one two', 'the textarea normalised');
  witness.eq(notes.start, 3, 'the caret survived on a textarea');

  // Every converge leaves a CARET rather than a range — `converge-to!`
  // restores one offset, which is the whole of rf2-n3dxw's honest limit.
  witness.eq(grouped.start === grouped.end, true, 'the converge leaves a collapsed caret');
}

// The same claim again, this time with the browser's own trusted key
// events, so no caret row rests on a dispatched event alone. Read at
// quiescence — a keystroke cannot be read inside its own turn from here —
// which is exactly why the rows above exist beside these.
async function caretUnderRealTyping(page, witness) {
  const field = page.locator('[data-testid="upper"]');
  await field.click();
  // Put the caret between "A" and "X" of the "AXBC" the rows above left.
  await page.evaluate(() => window.__TB__.el('upper').setSelectionRange(1, 1));
  await page.keyboard.type('q');
  const after = await page.evaluate(() => window.__TB__.read('upper'));
  witness.eq(after.value, 'AQXBC', 'a real keystroke normalised');
  witness.eq(after.start, 2, 'a real keystroke left the caret after the character typed');

  const groupedField = page.locator('[data-testid="grouped"]');
  await groupedField.click();
  await page.evaluate(() => window.__TB__.el('grouped').setSelectionRange(4, 4));
  await page.keyboard.type('7');
  const grouped = await page.evaluate(() => window.__TB__.read('grouped'));
  // "12,9|34" + "7" -> digits "129734" -> "129,734"; the offset from the
  // end was 2, so the caret is at 5, immediately after the 7.
  witness.eq(grouped.value, '129,734', 'a real keystroke regrouped');
  witness.eq(grouped.start, 5, 'a real keystroke kept the caret off the end');
}

// I15 — "preserve selection". A keystroke collapses a selection by itself,
// so the only place the question is live is an OUT-OF-BAND write: a model
// correction no keystroke caused, which `converge!` is deliberately not on
// (rf2-n3dxw). What survives there is React's own selection restore, and
// what it does is measured per engine rather than assumed.
async function selectionAcrossAnOutOfBandWrite(page, witness) {
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
  witness.record('out-of-band-write-lands-in-the-same-task',
    observed.immediate.value === 'ZZZZZZ');
  witness.eq(observed.before.end - observed.before.start, 2, 'a range was selected to begin with');
  // The PREMISE of the recorded direction below, which went unread until
  // rf2-hic-016's second pass. An engine that had never honoured
  // `'backward'` would have recorded the same post-write direction as the
  // others and agreed with them perfectly — three engines agreeing about a
  // selection that was never directional. Recording an outcome whose
  // premise is unread is the cross-engine comparator's one blind spot.
  witness.eq(observed.before.direction, 'backward',
    'the selection really was directional before the write');
  witness.eq(observed.after.value, 'ZZZZZZ', 'the out-of-band correction reached the field');
  witness.eq(observed.after.active, true, 'the field still holds focus across the correction');
  witness.record('selection-across-out-of-band-write', {
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
async function compositionSafety(page, witness) {
  const run = await page.evaluate(() => {
    const node = window.__TB__.el('digits');
    node.focus();
    const before = window.__TB__.model();
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

    const mid = window.__TB__.model();

    window.__TB__.composition(node, 'compositionend', 'あい');
    const ended = window.__TB__.read('digits');

    return {
      started, draft1, draft2, ended, seen,
      editsBefore: before.edits.digits || 0,
      midEdits: mid.edits.digits,
      midModel: mid.fields.digits,
      startCount: seen.filter((t) => t === 'start').length,
      endCount: seen.filter((t) => t === 'end').length,
    };
  });

  witness.eq(run.draft1.value, '123あ', 'the first composing update survives in the field');
  witness.eq(run.draft2.value, '123あい', 'the second composing update survives too');
  // The PREMISE of the row below, without which it is not a witness at all:
  // a model nothing spoke to has also "never moved". Both composing updates
  // reached the store, so the stillness the next row reads is a REFUSAL
  // rather than an absence.
  witness.eq(run.midEdits, run.editsBefore + 2, 'both composing updates reached the store');
  witness.eq(run.midModel, '123', 'the refusing model never moved during the exchange');
  // The exchange was not destroyed: one start, one end. A value write
  // landing mid-composition is what mints a second `compositionstart` in
  // the CDP harness, and a synthetic exchange can at least witness that
  // this runtime wrote nothing.
  witness.eq(run.startCount, 1, 'exactly one compositionstart');
  witness.eq(run.endCount, 1, 'exactly one compositionend');
  // And the refusal lands whole, at the close, visibly.
  witness.eq(run.ended.value, '123', 'the refusal lands at compositionend');

  const model = await page.evaluate(() => window.__TB__.model());
  witness.eq(model.fields.digits, '123', 'the store still holds the committed value');
}

// The carve-out's safety rider: every path out of a composition releases
// the shadow, so a field cannot be stranded showing a draft the model never
// agreed to. `compositionend` is witnessed above; these are the other two
// that do not go through it.
async function compositionReleaseEdges(page, witness) {
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
  witness.eq(blurred.held, '123あ', 'the draft was held before the blur');
  witness.eq(blurred.after, '123', 'blur releases the shadow and the model re-asserts');

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
  witness.eq(recovered.held, '123あ', 'the draft was held');
  witness.eq(recovered.plain.value, '1234', 'a non-composing change releases and converges');

  // Unmount — the shadow is one `useState` on a fiber and cannot outlive
  // it. There is no registry and no node property to strand.
  const unmounted = await page.evaluate(async () => {
    const node = window.__TB__.el('mountable');
    node.focus();
    const before = window.__TB__.model();
    window.__TB__.composition(node, 'compositionstart', '');
    // A REFUSING field, so the draft in the element is one the model never
    // took — without that the row below would pass on a model that had
    // simply accepted the composition.
    const draft = window.__TB__.edit('mountable', '9あ', 2, {
      isComposing: true, inputType: 'insertCompositionText', data: 'あ',
    });
    const mid = window.__TB__.model();
    window.__TB__.el('toggle-mounted').click();
    await window.__TB__.settle();
    const gone = !!document.querySelector('[data-testid="mountable-gone"]');
    window.__TB__.el('toggle-mounted').click();
    await window.__TB__.settle();
    return {
      draft: draft.value, gone,
      editsBefore: before.edits.mountable || 0,
      midEdits: mid.edits.mountable,
      midModel: mid.fields.mountable,
      back: window.__TB__.read('mountable').value,
    };
  });
  witness.eq(unmounted.draft, '9あ', 'the field held a draft the model had refused');
  // Same premise as `composition-safety`'s: "refused it" is a claim about
  // something the store was asked to do, and only the arrival counter can
  // tell that apart from an exchange that dispatched nothing.
  witness.eq(unmounted.midEdits, unmounted.editsBefore + 1, 'the composing update reached the store');
  witness.eq(unmounted.midModel, '9', 'and the model really had refused it');
  witness.eq(unmounted.gone, true, 'the field unmounted mid-composition');
  witness.eq(unmounted.back, '9', 'the remounted field shows the model, not a stranded draft');
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
async function anAcceptingModelDuringAComposition(page, witness) {
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

  witness.eq(run.d1, run.base + 'あ', 'the first composing update stands in the field');
  witness.eq(run.m1.v, run.base + 'あ', 'AND the accepting model took it — mid-composition');
  witness.eq(run.m1.e, run.edits0 + 1, 'exactly one intent arrived for it');
  witness.eq(run.d2, run.base + 'あい', 'the second composing update stands too');
  witness.eq(run.m2.v, run.base + 'あい', 'and the model took that one as well');
  witness.eq(run.m2.e, run.edits0 + 2, 'one intent per composing input, no more and no fewer');
  // So the committed text does not "arrive once, at the close": it arrived
  // progressively, and `compositionend` adds nothing to it. What the close
  // does is converge the field to whatever the model then holds.
  witness.eq(run.m3.edits.plain, run.edits0 + 2, 'compositionend dispatches no intent of its own');
  witness.eq(run.ended, run.m3.fields.plain, 'and the field converges to the model at the close');
}

// I15 — "reset is an explicit revision that preserves element identity".
// The divergence is created the way a password manager creates one: a value
// write with no event, which no handler sees and no commit is scheduled for.
async function revisionResetPreservesIdentity(page, witness) {
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
  witness.eq(observed.drifted, 'drifted', 'the eventless write reached the field');
  witness.eq(observed.survived, 'drifted', 'and survived a turn — nothing else reset it');
  witness.eq(observed.modelBefore.fields.revision, 'keep', 'the store never saw the draft');
  witness.eq(observed.after.value, 'keep', 'the revision bump re-baselined the field to the model');
  witness.eq(observed.revision, 1, 'exactly one revision advance');
  witness.eq(observed.mark, 'M1', 'the DOM node survived the reset — no remount');
  witness.eq(observed.identical, true, 'and it is the same node the document still holds');
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
async function aRevisionArrivingMidComposition(page, witness) {
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

  witness.eq(strict.draft, '42あ', 'the field held the composing draft');
  // The PREMISE that makes this field different from the accepting one:
  // the update reached the store and the store turned it down, so the
  // reset's target and the draft are two different strings.
  witness.eq(strict.midEdits, strict.editsBefore + 1, 'the composing update reached the store');
  witness.eq(strict.midModel, '42', 'and the refusing model kept none of it');
  witness.eq(strict.revision, strict.revisionBefore + 1, 'the revision really did advance mid-exchange');
  // THE DEFERRAL, on the only field that can red on it. A reset that
  // landed immediately would have written `42` over a live composition —
  // the abort the carve-out exists to prevent, and the conduct the
  // accepting field below cannot distinguish.
  witness.eq(strict.during.value, '42あ',
    'the reset deferred: the draft is untouched while the exchange is open');
  // …and the exchange it did not disturb is intact: a mid-composition
  // value write is what mints a second `compositionstart` in the CDP
  // harness.
  witness.eq(strict.startCount, 1, 'still exactly one compositionstart');
  witness.eq(strict.endCount, 1, 'and exactly one compositionend');
  witness.eq(strict.value, strict.model, 'and the field converges to the model at the close');
  witness.eq(strict.model, '42', 'which is the value the refusal left standing');

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

  witness.eq(observed.draft, 'keepあ', 'the field held the composing draft');
  witness.eq(observed.revision, observed.revisionBefore + 1, 'a second revision advanced mid-exchange');
  witness.eq(observed.during.value, 'keepあ', 'the draft is untouched here too');
  witness.eq(observed.after.value, observed.model, 'and the field converges at the close');
  // The honest limit, as `controlled.cljs` states it: on an ACCEPTING field
  // the composing updates the model kept taking supersede the reset by
  // ordinary event order. "The deferral cannot strand the field" is what is
  // true; "the reset cannot be lost" is not.
  witness.eq(observed.model, 'keepあ',
    'an accepting model kept the composing update, so the reset did not win');
  witness.eq(observed.mark, 'M1', 'and the DOM node survived the whole exchange');
}

// The owned `::h/checked` pair, whose `false` is a presence rather than a
// truth. Read inside the click's own turn.
async function ownedCheckedPair(page, witness) {
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
  witness.eq(toggled.before.dom, false, 'the checkbox starts unchecked');
  witness.eq(toggled.before.model, false, 'and so does its model');
  witness.eq(toggled.on.dom, true, 'the DOM followed the click');
  witness.eq(toggled.on.model, true, 'and the store did too, in the same turn');
  witness.eq(toggled.off.dom, false, 'and back — an owned false is a presence');
  witness.eq(toggled.off.model, false, 'with the store agreeing');
}

// RECORDED, not gated (the conformance matrix is hic-040). A form reset
// returns a control to its `defaultValue`, and `defaultValue` is exactly
// the per-instance record `controlled/last-rendered` reads — so a reset is
// the one ordinary browser action that touches the converge's own
// bookkeeping. Autofill has no cross-engine drive (Chromium's is a CDP
// method and needs a profile), so the eventless write below is recorded as
// its PROXY and named as one.
async function formResetAndFillProxy(page, witness) {
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
  witness.eq(reset.typed, 'FORMX', 'the form field normalised like any other');
  witness.record('form-reset', {
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
  witness.eq(fill.withEvent, 'filled', 'a fill carrying an input event converges like a keystroke');
  witness.eq(fill.withEventModel, 'filled', 'and reaches the store');
  witness.record('fill-proxy', {
    'eventless-fill-leaves-a-draft': fill.silent === 'silent',
    'form-reset-clears-the-eventless-draft': fill.afterReset === fill.withEventModel,
    'value-after-reset': fill.afterReset,
  });
}

// ===========================================================================
// rf2-hic-040 — THE CONTROL / DOM CONFORMANCE MATRIX
//
// Everything above is I15, which is a law about TEXT: one control shape in
// seven model policies. The rows below are a different question — does
// every control type specification 4.2 names have a support-or-refusal
// policy that holds in three engines, with none of them silently
// unsupported. `docs/design/hicasso/product/dispositions.md` section 2.3 is
// the roster and this block fills it.
//
// Three of these rows are FINDINGS rather than confirmations, and they are
// asserted rather than recorded because each is deterministic: the reserved
// `::h/value` marker under-reads a multiple select, a file input cannot be
// value-controlled at all, and a kebab KEYWORD does not reach a custom
// element as a dashed attribute. Each is asserted in the direction the
// runtime actually behaves, so the row reds if the behaviour changes in
// either direction — a finding that is only written down is a finding that
// rots.
// ===========================================================================

// RADIO. Three elements, one model slot, and the group refuses `"c"`.
//
// A radio group is the one control where the clicked element is NOT the one
// that carries the model, so "echoes only committed state" has a shape it
// has nowhere else: clicking the refused button leaves the DOM momentarily
// showing it checked, and what must put it back is React's controlled
// restore across the WHOLE group — the other buttons sharing the name have
// to be restored too, and only React knows they exist.
async function radioGroupEchoesCommitted(page, witness) {
  const run = await page.evaluate(async () => {
    const snap = () => ({
      a: window.__TB__.plain('radio-a').checked,
      b: window.__TB__.plain('radio-b').checked,
      c: window.__TB__.plain('radio-c').checked,
      model: window.__TB__.model().radio,
      edits: window.__TB__.model().edits.radio || 0,
    });
    const before = snap();
    window.__TB__.el('radio-a').click();
    await window.__TB__.settle();
    const accepted = snap();
    // The REFUSED option. The click lands, the intent arrives, the model
    // declines — and the group must show the model rather than the click.
    window.__TB__.el('radio-c').click();
    await window.__TB__.settle();
    const refused = snap();
    return { before, accepted, refused };
  });

  witness.eq(run.before.b, true, 'the group starts on its committed choice');
  witness.eq(run.before.model, 'b', 'and the store agrees');
  witness.eq(run.accepted.a, true, 'the clicked radio is checked');
  witness.eq(run.accepted.b, false, 'and the previous one is not — the group is exclusive');
  witness.eq(run.accepted.model, 'a', 'the store took the accepted choice');
  // The PREMISE of the refusal row: the click really did reach the store.
  // Without it, a radio wired to nothing would read exactly the same.
  witness.eq(run.refused.edits, run.before.edits + 2, 'both clicks reached the store');
  witness.eq(run.refused.model, 'a', 'the refusing group did not move');
  witness.eq(run.refused.c, false, 'and the refused radio echoes UNCHECKED — committed state');
  witness.eq(run.refused.a, true, 'while the committed choice is still the one shown');
}

// SELECT, single. Nothing in `impl.controlled` applies: `convergeable-tag?`
// answers false for a select and the namespace docstring gives the reason —
// no text cursor, no `defaultValue` mirror. So the echo here is React's own
// controlled restore, measured rather than assumed to be equivalent.
async function selectSingleEchoesCommitted(page, witness) {
  const run = await page.evaluate(async () => {
    const before = { dom: window.__TB__.selected('pick'), model: window.__TB__.model().fields.pick };
    // Accepted.
    const took = window.__TB__.choose('pick', ['two']);
    const inTurn = window.__TB__.selected('pick');
    await window.__TB__.settle();
    const accepted = { dom: window.__TB__.selected('pick'), model: window.__TB__.model().fields.pick };
    // Refused — and read on the NEXT LINE, because React's restore for a
    // select runs inside the discrete event exactly as it does for an input.
    window.__TB__.choose('pick', ['banned']);
    const refusedInTurn = window.__TB__.selected('pick');
    await window.__TB__.settle();
    const refused = { dom: window.__TB__.selected('pick'), model: window.__TB__.model().fields.pick };
    return { before, took, inTurn, accepted, refusedInTurn, refused };
  });

  witness.eq(run.before.dom.join(','), 'one', 'the select starts on its committed option');
  witness.eq(run.before.model, 'one', 'and the store agrees');
  witness.eq(run.took.value, 'two', 'the choice reached the element');
  witness.eq(run.inTurn.join(','), 'two', 'and stands within the turn that made it');
  witness.eq(run.accepted.model, 'two', 'the store took it');
  witness.eq(run.refusedInTurn.join(','), 'two',
    'the refused option echoes the committed one INSIDE the turn');
  witness.eq(run.refused.dom.join(','), 'two', 'and still does at rest');
  witness.eq(run.refused.model, 'two', 'with the store never having moved');
}

// SELECT, multiple — the SUPPORTED spelling. `h/event` reads
// `selectedOptions`, which is the only expression of a multi-selection the
// DOM has.
async function selectMultipleSupported(page, witness) {
  const run = await page.evaluate(async () => {
    const drove = window.__TB__.choose('picks', ['a', 'c']);
    const inTurn = window.__TB__.selected('picks');
    await window.__TB__.settle();
    const took = { dom: window.__TB__.selected('picks'), model: window.__TB__.model().picks };
    // …and a selection containing the refused option.
    window.__TB__.choose('picks', ['a', 'banned', 'c']);
    await window.__TB__.settle();
    const refused = { dom: window.__TB__.selected('picks'), model: window.__TB__.model().picks };
    return { drove, inTurn, took, refused };
  });

  // The same premise the marker row states, and it has to be stated on
  // BOTH: the supported path's claim is that the echo KEEPS two options,
  // which is only a claim if two were chosen before the event went out.
  witness.eq(run.drove.chosen.join(','), 'a,c', 'two options were chosen');
  witness.eq(run.drove.dotValue, 'a',
    'while the platform\'s own `select.value` still answers one of them — the scalar this path never used');
  witness.eq(run.inTurn.join(','), 'a,c', 'both options survive the echo, inside the turn');
  witness.eq(run.took.model.join(','), 'a,c', 'and BOTH reach the store — the whole selection');
  witness.eq(run.took.dom.join(','), 'a,c', 'the echo keeps both');
  witness.eq(run.refused.model.join(','), 'a,c', 'the refused option is dropped by the policy');
  witness.eq(run.refused.dom.join(','), 'a,c',
    'and the element echoes the committed selection, not the chosen one');
}

// SELECT, multiple — the NAIVE spelling, and the FINDING it used to be.
//
// This row was written against the bug and asserted it: `::h/value` lowered
// to `(.-value target)`, `HTMLSelectElement.value` is the first selected
// option in tree order, and so the marker delivered ONE option however many
// the user chose — a handler could not tell a single-option selection from
// a truncated one, and nothing refused it. rf2-42vlw fixed that:
// `impl.intent/target-value` reads `selectedOptions` on a `<select
// multiple>`, so the marker now delivers the SELECTION, a list, `[]` when
// nothing is picked, exactly as `spec/004B-UI-Tree-and-Conversion.md` rules
// it for the same DOM control on the sibling substrate.
//
// The row is kept and turned around rather than deleted, because what it
// witnesses is still a real difference the platform makes: `select.value`
// remains the scalar first option in all three engines, and the assertion
// on `dotValue` below is what stops the marker's answer from being
// confused with it. Turning a row around is the gate working — it reds when
// this conduct changes in EITHER direction.
async function reservedMarkerReadsTheWholeMultipleSelection(page, witness) {
  const run = await page.evaluate(async () => {
    const before = window.__TB__.model().edits['picks-marker'] || 0;
    const drove = window.__TB__.choose('picks-marker', ['a', 'c']);
    // Read on the NEXT LINE: React's controlled restore for a select runs
    // inside the discrete event, so whatever the echo made of the
    // selection is already settled here — this is where the discard used
    // to be visible, and where its absence is now the claim.
    const inTurn = window.__TB__.selected('picks-marker');
    await window.__TB__.settle();
    const m = window.__TB__.model();
    return {
      before,
      drove,
      inTurn,
      raw: m['picks-marker-raw'],
      model: m['picks-marker'],
      dom: window.__TB__.selected('picks-marker'),
      edits: m.edits['picks-marker'],
    };
  });

  witness.eq(run.drove.chosen.join(','), 'a,c', 'the user really did choose two options');
  witness.eq(run.edits, run.before + 1, 'and exactly one intent arrived for it');
  witness.eq(run.drove.dotValue, 'a',
    'the platform\'s `select.value` STILL answers one option — the marker is not reading it');
  // `raw` and `model` are compared by SHAPE, not by a joined string: this
  // row is about a list arriving where a scalar used to, and `'a,c'` is
  // reachable from `['a,c']` and from `[['a','c']]` as well as from the
  // truth. The DOM reads below stay joined — `selectedOptions` is a flat
  // list of option values by construction.
  witness.eq(JSON.stringify(run.raw), '["a","c"]',
    'so the handler received the whole SELECTION, as a list');
  witness.eq(JSON.stringify(run.model), '["a","c"]', 'and that is what the store holds');
  witness.eq(run.inTurn.join(','), 'a,c',
    'the echo KEEPS both choices inside the turn — nothing is discarded');
  witness.eq(run.dom.join(','), 'a,c', 'and both are still there at rest');
}

// FILE INPUT — a refusal, and the refusal is the PLATFORM's.
//
// `HTMLInputElement.value` refuses every assignment but `""` on a file
// input (`InvalidStateError`), and React's controlled write is exactly that
// assignment: `react-dom@19.2.0` `updateInput` reaches
// `element.value = "" + getToStringValue(value)` for every type but
// `number`, and `initInput` reaches `element.value = value`. So a `:value`
// off a subscription on a file input cannot be honoured by anything, and
// what an author gets is an engine exception naming no view and no
// framework — React 19.2 carries no controlled-file-input warning at all.
//
// This row therefore witnesses the constraint rather than the crash: the
// supported path works, and the assignment the controlled path would make
// is shown to throw in each engine. The absence of a source-located Hicasso
// refusal is a FINDING recorded against the matrix, not repaired here — the
// repair mints an error id, and an error id owes a `spec/009` row, which is
// hot zone this bead may not touch.
async function fileInputIsUncontrollable(page, witness) {
  await page.setInputFiles('[data-testid="file"]', [
    { name: 'one.txt', mimeType: 'text/plain', buffer: Buffer.from('1') },
    { name: 'two.txt', mimeType: 'text/plain', buffer: Buffer.from('2') },
  ]);
  const run = await page.evaluate(async () => {
    await window.__TB__.settle();
    const node = window.__TB__.el('file');
    const m = window.__TB__.model();
    // What the controlled path would assign, run as the platform sees it.
    let threw = null;
    try { node.value = 'anything'; } catch (e) { threw = e && e.name; }
    // …and the one assignment the platform DOES allow, so the row cannot
    // pass by the property being read-only in general.
    let clearOk = true;
    try { node.value = ''; } catch (e) { clearOk = false; }
    return {
      files: m.files,
      edits: m.edits.files,
      // What `::h/value` would have delivered off this control.
      markerWouldRead: window.__TB__.plain('file').value,
      threw,
      clearOk,
      afterClear: window.__TB__.plain('file').value,
    };
  });

  witness.eq(run.files.join(','), 'one.txt,two.txt', 'the SUPPORTED path carries both chosen files');
  witness.eq(run.edits, 1, 'through exactly one intent');
  witness.eq(run.threw, 'InvalidStateError',
    'the assignment React makes for a controlled value THROWS on a file input');
  witness.eq(run.clearOk, true, 'while the empty string — the one legal write — is accepted');
  witness.eq(run.afterClear, '', 'and clears the control');
  // RECORDED because the exact string is the engine's (`C:\\fakepath\\…`),
  // and because the point is what it CANNOT express rather than what it is.
  witness.record('file-input-marker-reading', {
    'names-the-first-file-only': run.markerWouldRead.indexOf('two.txt') === -1,
    'is-not-the-file-list': run.markerWouldRead !== 'one.txt,two.txt',
  });
}

// NUMBER, DATE and RANGE — the roster's honest middle.
//
// All three are controlled and all three are OUTSIDE the converge:
// `impl.controlled/caret-type?` admits only `text`, `search`, `url`, `tel`,
// `password` and a bare `<input>`, so `convergeable?` answers no and no
// wrapper is installed. What echoes them is React's own end-of-event
// restore — which is enough for the VALUE and says nothing about a caret,
// because on these types there is no caret to lose. That is the support
// policy, and the narrowing is part of it rather than a defect in it.
async function typesWithoutACaretEchoCommitted(page, witness) {
  const run = await page.evaluate(async () => {
    const drive = (id, next) => {
      const node = window.__TB__.el(id);
      node.focus();
      window.__TB__.nativeSet(node, next);
      window.__TB__.fire(node, 'input', { inputType: 'insertText' });
      // Read on the very next line — React's restore for a controlled
      // input runs inside the discrete event whether a converge was
      // installed or not.
      return window.__TB__.plain(id).value;
    };
    const before = window.__TB__.model().fields;
    const count = drive('count', '42');          // clamps to "10"
    const day = drive('day', '2025-05-05');      // refused; stays 2026-01-15
    const dayOk = drive('day', '2026-03-03');    // accepted
    const level = drive('level', '37');          // snaps to "40"
    await window.__TB__.settle();
    return {
      before, count, day, dayOk, level,
      model: window.__TB__.model().fields,
      selection: {
        count: window.__TB__.selectionProbe('count'),
        day: window.__TB__.selectionProbe('day'),
        level: window.__TB__.selectionProbe('level'),
      },
    };
  });

  witness.eq(run.count, '10', 'a number field echoes the CLAMPED value, in-turn');
  witness.eq(run.model.count, '10', 'and the store holds it');
  witness.eq(run.day, run.before.day, 'a refused date echoes the committed one, in-turn');
  witness.eq(run.dayOk, '2026-03-03', 'and an accepted one stands');
  witness.eq(run.model.day, '2026-03-03', 'with the store agreeing');
  witness.eq(run.level, '40', 'a range field echoes the SNAPPED value, in-turn');
  witness.eq(run.model.level, '40', 'and the store holds that too');
  // The narrowing, measured. `selectionStart` is not applicable to these
  // types; whether an engine answers null or refuses is the ENGINE's and is
  // recorded rather than required — but it is recorded for all three, so a
  // divergence is still a red gate.
  witness.record('selection-api-on-types-without-a-caret', run.selection);
}

// CONTENTEDITABLE — not a controlled field, and this is the whole policy.
//
// There is no owned slot for a contenteditable region, no `::h/value` that
// reads one, and nothing in `impl.controlled` that could apply: the guards
// are `input` and `textarea`, by tag. What an author gets is an ordinary
// element whose children are the model's and whose edits come back through
// `h/event`. The row exists so that "not supported as a controlled field" is
// a measured property rather than an omission nobody checked.
async function contenteditableIsNotAControlledField(page, witness) {
  const run = await page.evaluate(async () => {
    const node = window.__TB__.el('prose');
    const before = { text: node.textContent, model: window.__TB__.model().prose };
    // The element carries no controlled bookkeeping of any kind — the
    // per-instance record `impl.controlled/last-rendered` reads does not
    // exist on a div, which is why the converge cannot reach it.
    const record = node.defaultValue;
    const editable = node.getAttribute('contenteditable');
    // An edit, the way a contenteditable edit arrives: the browser has
    // already changed the content, and an `input` event follows.
    //
    // The existing TEXT NODE is mutated rather than replaced, which is both
    // what a simple contenteditable keystroke does and the only version of
    // this that is a witness. Assigning `textContent` detaches the node
    // React is holding, so the next model change would write to a node no
    // longer in the document and the row would red on the harness rather
    // than on the runtime.
    node.firstChild.data = 'edited by hand';
    node.dispatchEvent(new InputEvent('input', { bubbles: true, inputType: 'insertText' }));
    await window.__TB__.settle();
    const after = { text: node.textContent, model: window.__TB__.model().prose };
    // …and an OUT-OF-BAND model change, which a controlled field would
    // converge and this one re-renders as ordinary children.
    window.__TB__.el('prose-correct').click();
    await window.__TB__.settle();
    return {
      before, record, editable, after,
      corrected: window.__TB__.el('prose').textContent,
      model: window.__TB__.model().prose,
      identical: window.__TB__.el('prose') === node,
    };
  });

  witness.eq(run.before.text, 'hand-written', 'the region renders the model');
  witness.eq(run.editable, 'plaintext-only', 'and is really contenteditable');
  witness.eq(run.record, undefined,
    'it carries NO defaultValue record — the converge has nothing to read');
  witness.eq(run.after.model, 'edited by hand', "the author's handler carried the edit to the store");
  witness.eq(run.after.text, 'edited by hand', 'and the region was not converged out from under it');
  witness.eq(run.corrected, 'CORRECTED', 'an out-of-band model change re-renders the content');
  witness.eq(run.model, 'CORRECTED', 'from the store');
  witness.eq(run.identical, true, 'on the same node — a re-render, not a remount');
}

// BLUR AFTER UNMOUNT. The row is about an ABSENCE, and the absence is
// React's: it synthesises no `blur` for a node it removes. An application
// that hangs commit-on-blur off that handler therefore loses the edit, and
// the framework's obligation is the narrower one — nothing stranded, no
// error, and the field's model intact when it comes back.
async function blurAfterUnmount(page, witness) {
  const run = await page.evaluate(async () => {
    const node = window.__TB__.el('blur-probe');
    node.focus();
    await window.__TB__.settle();
    const focused = {
      active: document.activeElement === node,
      log: window.__TB__.model()['focus-log'].slice(),
    };
    // Leave a DRAFT in the field the model has not taken, so "nothing
    // stranded" is a claim about something there was to strand. An
    // eventless write is the shape that produces one.
    window.__TB__.nativeSet(node, 'about-to-vanish');
    const draft = window.__TB__.plain('blur-probe').value;
    const committed = window.__TB__.model().fields.async;

    window.__TB__.el('toggle-probe').click();
    await window.__TB__.settle();
    const gone = {
      present: !!document.querySelector('[data-testid="blur-probe-gone"]'),
      stillThere: !!document.querySelector('[data-testid="blur-probe"]'),
      log: window.__TB__.model()['focus-log'].slice(),
      activeIsBody: document.activeElement === document.body,
      activeTag: document.activeElement && document.activeElement.tagName,
      detachedStillFocused: document.activeElement === node,
    };

    window.__TB__.el('toggle-probe').click();
    await window.__TB__.settle();
    return {
      focused, draft, committed, gone,
      back: window.__TB__.plain('blur-probe').value,
      backModel: window.__TB__.model().fields.async,
      sameNode: window.__TB__.el('blur-probe') === node,
    };
  });

  witness.eq(run.focused.active, true, 'the field had focus before it went away');
  witness.eq(run.focused.log.join(','), 'focus', 'and the focus edge reached the store');
  witness.eq(run.draft, 'about-to-vanish', 'a draft the model never took was on screen');
  witness.eq(run.gone.present, true, 'the field unmounted');
  witness.eq(run.gone.stillThere, false, 'and left nothing behind in the tree');
  witness.eq(run.gone.detachedStillFocused, false,
    'the detached node does not keep the document focus');
  witness.eq(run.sameNode, false, 'the remount is a NEW node — the old fiber is gone');
  witness.eq(run.back, run.committed,
    'and the field comes back showing the model, not the stranded draft');
  witness.eq(run.backModel, run.committed, 'with the store never having moved');
  // The ABSENCE this row is named for. React synthesises no `blur` for a
  // node it removes, so a commit-on-blur handler never runs — recorded per
  // engine rather than required, because it is React's conduct rather than
  // this runtime's, and recorded in all three so a divergence still reds.
  witness.record('blur-edges-across-an-unmount', {
    'edges-before-the-unmount': run.focused.log.join(','),
    'edges-after-the-unmount': run.gone.log.join(','),
    'a-blur-was-reported': run.gone.log.indexOf('blur') !== -1,
    'focus-fell-to-the-body': run.gone.activeIsBody,
  });
}

// ASYNC NORMALIZATION — the correction that arrives a turn later.
//
// This is deliberately the path the keystroke converge is NOT on: no change
// event fires, so `install!`'s wrapper never runs and React's own restore
// is what writes the field (`impl.controlled`, "An out-of-band correction …
// fires no change event"). The roster row is whether the field lands on the
// committed value at all, in three engines, without a keystroke to carry it.
// The armed correction fires at 30ms; this is the ceiling the wait runs
// under, named rather than written inline so a CI log cannot read it as a
// budget for anything else. It is not a navigation, so it carries no
// relation to the runner's navigation ceiling.
const ASYNC_NORMALISE_CEILING_MS = 5000;

async function asyncNormalization(page, witness) {
  const armed = await page.evaluate(async () => {
    // A keystroke first, so the correction has something to normalise and
    // the row cannot pass on a field nobody touched.
    window.__TB__.edit('blur-probe', 'abc', 3, { data: 'c' });
    const typed = window.__TB__.plain('blur-probe').value;
    const before = window.__TB__.model();
    window.__TB__.el('normalise-async').click();
    // Read IMMEDIATELY: the correction is armed, not applied, and a row
    // that could not tell those apart would pass on a synchronous write.
    return {
      typed,
      normalisationsBefore: before.normalisations,
      immediate: window.__TB__.plain('blur-probe').value,
    };
  });
  witness.eq(armed.typed, 'abc', 'the keystroke was accepted');
  witness.eq(armed.immediate, 'abc', 'and the armed correction has not landed yet');

  await page.waitForFunction(
    (n) => window.__TB__.model().normalisations > n,
    armed.normalisationsBefore, { timeout: ASYNC_NORMALISE_CEILING_MS });

  const landed = await page.evaluate(() => ({
    value: window.__TB__.plain('blur-probe').value,
    model: window.__TB__.model().fields.async,
    normalisations: window.__TB__.model().normalisations,
  }));
  witness.eq(landed.model, 'ABC', 'the async normalisation reached the store');
  witness.eq(landed.value, 'ABC', 'and the field converged to it with no keystroke to carry it');
  witness.eq(landed.normalisations, armed.normalisationsBefore + 1, 'exactly once');
}

// FORM RESET, AUTOFILL and FORMDATA — the three ordinary browser actions
// that read or write a control behind the runtime's back.
//
// rf2-hic-016 RECORDED the reset and the fill proxy and said the
// conformance matrix was this bead's. This is that: the same conduct as
// REQUIRED rows, plus the extraction the matrix names and the two control
// classes a text field cannot speak for.
//
// NATIVE autofill is not here and is not claimed: Chromium's drive is a CDP
// method needing an address profile, and neither Firefox nor WebKit exposes
// one at all. What is gated is the eventless write that a password manager
// performs, which is the shape that can actually reach a field in all three.
async function formResetAutofillAndFormData(page, witness) {
  const run = await page.evaluate(async () => {
    const model = window.__TB__.model();
    const extracted = window.__TB__.formData();
    // A checkbox contributes to FormData only while CHECKED, which is the
    // property that separates a form extraction from a model read.
    window.__TB__.el('form-flag').click();
    await window.__TB__.settle();
    const checkedOn = { data: window.__TB__.formData(), model: window.__TB__.model().flag };
    window.__TB__.el('form-flag').click();
    await window.__TB__.settle();
    const checkedOff = { data: window.__TB__.formData(), model: window.__TB__.model().flag };
    return { model, extracted, checkedOn, checkedOff };
  });

  witness.eq(run.extracted['form-a'], run.model.fields['form-a'],
    'FormData extracts the COMMITTED value of a controlled text field');
  witness.eq(run.extracted['form-b'], run.model.fields['form-b'], 'for every such field');
  witness.eq(run.extracted['form-pick'], run.model.fields['form-pick'],
    'and the committed option of a controlled select');
  witness.eq(run.checkedOn.model, true, 'the checkbox took the click');
  witness.eq(run.checkedOn.data['form-flag'], 'yes',
    "a CHECKED box contributes its `value`, not its checked state");
  witness.eq(run.checkedOff.model, false, 'and back');
  witness.eq(run.checkedOff.data['form-flag'], undefined,
    'an unchecked box contributes nothing at all');

  // AUTOFILL, as the proxy that can be driven everywhere — now REQUIRED
  // rather than recorded. Two shapes: one carrying an input event, which is
  // indistinguishable from a keystroke, and one carrying nothing, which is
  // the pathological case a password manager can still produce.
  const fill = await page.evaluate(async () => {
    const withEvent = window.__TB__.edit('form-a', 'refilled', 8,
      { inputType: 'insertReplacementText' });
    const node = window.__TB__.el('form-a');
    window.__TB__.nativeSet(node, 'silently');
    await new Promise((r) => setTimeout(r, 50));
    return {
      withEvent: withEvent.value,
      withEventModel: window.__TB__.model().fields['form-a'],
      silent: window.__TB__.plain('form-a').value,
      silentModel: window.__TB__.model().fields['form-a'],
    };
  });
  witness.eq(fill.withEvent, 'REFILLED', 'a fill carrying an input event converges like a keystroke');
  witness.eq(fill.withEventModel, 'REFILLED', 'and reaches the store');
  witness.eq(fill.silent, 'silently', 'an EVENTLESS fill leaves a draft the runtime never sees');
  witness.eq(fill.silentModel, 'REFILLED',
    'and the store is untouched by it — nothing here polls the DOM');

  // FORM RESET, across the three control classes. The text row is the one
  // rf2-hic-016 recorded (`defaultValue` is already the model, so the reset
  // is visually inert); the checkbox and the select are this bead's, and
  // their conduct is RECORDED because `defaultChecked` and `defaultSelected`
  // are maintained by React on different rules from `defaultValue`.
  const reset = await page.evaluate(async () => {
    window.__TB__.el('form-reset').click();
    await window.__TB__.settle();
    const m = window.__TB__.model();
    return {
      text: window.__TB__.plain('form-a').value,
      textModel: m.fields['form-a'],
      flag: window.__TB__.plain('form-flag').checked,
      flagModel: m.flag,
      pick: window.__TB__.selected('form-pick'),
      pickModel: m.fields['form-pick'],
    };
  });
  witness.eq(reset.text, reset.textModel,
    'a form reset leaves a converged text field showing the model');
  witness.eq(reset.text, 'REFILLED',
    'which is the committed value, not the eventless draft — the reset cleared it');
  witness.record('form-reset-across-control-classes', {
    'text-agrees-with-the-model': reset.text === reset.textModel,
    'checkbox-agrees-with-the-model': reset.flag === reset.flagModel,
    'select-agrees-with-the-model': reset.pick.join(',') === reset.pickModel,
    'checkbox-after-reset': reset.flag,
    'select-after-reset': reset.pick.join(','),
  });
}

// SVG ATTRIBUTES, read off the live DOM.
//
// An SVG element's attributes are case-sensitive and namespaced, which is
// why this is a browser row rather than a string comparison: `viewBox`
// survives as written and `viewbox` would be a different attribute that
// does nothing. The slot rule (`impl.slot/prop-name`) camelCases a kebab
// keyword, so `:view-box` lands as React's `viewBox` — and React knows to
// emit the kebab form for a PRESENTATION attribute like `stroke-width`,
// which is the half the slot rule cannot do on its own.
async function svgAttributes(page, witness) {
  const run = await page.evaluate(() => ({
    ns: window.__TB__.el('svg').namespaceURI,
    svg: window.__TB__.attrs('svg'),
    circle: window.__TB__.attrs('svg-circle'),
    text: window.__TB__.attrs('svg-text'),
    // Case matters here and nowhere else in HTML, so it is asked directly.
    viewBoxExact: window.__TB__.el('svg').getAttribute('viewBox'),
    viewBoxLower: window.__TB__.el('svg').getAttribute('viewbox'),
    camelStroke: window.__TB__.el('svg-circle').getAttribute('strokeWidth'),
  }));

  witness.eq(run.ns, 'http://www.w3.org/2000/svg', 'the subtree is really in the SVG namespace');
  witness.eq(run.viewBoxExact, '0 0 20 20', ':view-box lands as the camelCase viewBox SVG requires');
  witness.eq(run.viewBoxLower, null, 'and NOT as a lower-cased attribute that would do nothing');
  witness.eq(run.circle['stroke-width'], '2',
    'a kebab presentation attribute lands kebab, which is what SVG reads');
  witness.eq(run.circle['stroke-linecap'], 'round', 'for every such attribute');
  witness.eq(run.camelStroke, null, 'and never as the camelCase spelling the slot rule produced');
  witness.eq(run.text['font-size'], '4', 'including on a second element type');
  witness.eq(run.circle.r, '6', 'while ordinary numeric attributes coerce to strings');
}

// CUSTOM ELEMENTS — and the third finding.
//
// React 19 passes an unknown prop on a custom element through as an
// ATTRIBUTE under the name it was given. The name it was given is the slot
// rule's output, and the slot rule camelCases a kebab KEYWORD — so
// `:my-other-attr` reaches the DOM as `myotherattr` (HTML lower-cases
// attribute names) rather than as the dashed attribute the author wrote.
// The two spellings that DO survive are a string key, which
// `impl.slot/prop-name` takes verbatim, and `data-*`, which the rule
// exempts from camelCasing outright.
//
// Nothing refuses the keyword spelling and nothing warns about it. It is
// asserted here in the direction the runtime behaves, so the row reds
// whichever way this changes.
async function customElementAttributes(page, witness) {
  const run = await page.evaluate(() => ({
    tag: window.__TB__.el('custom').tagName,
    attrs: window.__TB__.attrs('custom'),
    names: Array.prototype.slice.call(window.__TB__.el('custom').getAttributeNames()),
    text: window.__TB__.el('custom').textContent,
  }));

  witness.eq(run.tag, 'X-WIDGET', 'the custom element is in the tree under its own tag');
  witness.eq(run.text, 'widget', 'carrying its children');
  witness.eq(run.attrs['my-attr'], 'from-string',
    'a STRING prop key reaches a custom element as the dashed attribute written');
  witness.eq(run.attrs['data-kebab-attr'], 'from-data',
    'and so does data-*, which the slot rule exempts from camelCasing');
  witness.eq(run.attrs['my-other-attr'], undefined,
    'FINDING: a kebab KEYWORD does NOT — the slot rule camelCased it first');
  witness.eq(run.attrs.myotherattr, 'from-keyword',
    'it lands under the camelCased name, lower-cased by HTML, with no refusal anywhere');
  witness.eq(run.names.indexOf('myOtherAttr'), -1,
    'and not under the camelCase spelling either, which HTML cannot hold');
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

const TESTBED_ROOT = '[data-testid="hicasso-controlled-testbed"]';

/**
 * Wait for the app to mount after a re-navigation, under the navigation's own
 * ceiling, and fail with the one thing the bare wait could not say: WHICH of
 * the two candidate mechanisms it was (rf2-vinj).
 *
 * A single webkit run of this section timed out here at Playwright's anonymous
 * 30s, having taken about six seconds over the twelve sections before it. The
 * bead that filed it left slow-versus-hung open, because it could not
 * reproduce. Forty consecutive local webkit re-navigations, with the same
 * multi-second idle gap the real section has, put this mount at p50 80ms and
 * max 108ms with no stalls — so 30,000ms is not a slow mount, it is a stopped
 * one. What it is NOT is a mechanism, and a ceiling cannot supply one.
 *
 * So the failure carries the page's own state at the moment the ceiling fires,
 * chosen to separate the candidates rather than to describe the page:
 *
 *   no `bundle`, !`bundleRan`  main.js never arrived — the fetch stalled, and
 *                              no ceiling on this line can repair that
 *   `bundle` but !`bundleRan`  it arrived and never began executing
 *   `bundleRan`, `appEmpty`    it executed and mounted nothing
 *   !`appEmpty`, !`rootPresent`  something mounted, but not this testbed
 *   `rootPresent`, zero box    the mount is fine; `waitForSelector` defaults
 *                              to state 'visible', so THAT is what was waited
 *                              on and the wait, not the page, is the bug
 *   all present                the only reading on which it was merely slow
 *
 * `bundleRan` witnesses `shadow$provide`, which main.js declares on its FIRST
 * line. It deliberately does not witness `window.__TB__`: the runner installs
 * that via `addInitScript`, so it is present before the page's own script on
 * every navigation and would report a bundle that never loaded as one that
 * ran. That is not hypothetical — it is what the first cut of this asserted,
 * and a 1ms-ceiling run caught it saying `appRan: true` about a document still
 * in `readyState: 'loading'` with no bundle fetched at all.
 *
 * Whichever it is, the next occurrence closes the question instead of filing
 * the bead again.
 */
async function waitForRemount(page, runContext) {
  try {
    await page.waitForSelector(TESTBED_ROOT, { timeout: runContext.navTimeoutMs });
  } catch {
    const at = await page.evaluate((sel) => {
      const root = document.querySelector(sel);
      const box = root && root.getBoundingClientRect();
      const app = document.getElementById('app');
      return {
        readyState: document.readyState,
        scriptTag: !!document.querySelector('script[src="main.js"]'),
        bundle: performance.getEntriesByType('resource')
          .filter((e) => e.name.endsWith('main.js'))
          .map((e) => ({ ms: Math.round(e.duration), bytes: e.transferSize })),
        bundleRan: typeof window.shadow$provide !== 'undefined',
        appEmpty: !app || app.childNodes.length === 0,
        rootPresent: !!root,
        rootBox: box ? `${Math.round(box.width)}x${Math.round(box.height)}` : null,
      };
    }, TESTBED_ROOT).catch((err) => ({ evaluateFailed: String(err && err.message) }));
    throw new Error(
      `the testbed root did not mount within ${runContext.navTimeoutMs}ms of the ` +
      're-navigation. This is the navigation\'s OWN ceiling and not the lane ' +
      'budget that wraps the section, so the re-navigation is what failed. ' +
      'The same mount measures p50 80ms locally, which is why this reads as ' +
      'a stopped mount rather than a slow one — the state below says which ' +
      `(rf2-vinj, and the docstring above reads it): ${JSON.stringify(at)}`);
  }
}

async function armedEdgesAreWired(page, witness, runContext) {
  const before = await page.evaluate(() => ({
    label: window.__TB__.el('armed').textContent,
    revision: window.__TB__.model().revision,
    mounted: !!document.querySelector('[data-testid="mountable"]'),
  }));
  witness.eq(before.label, 'idle', 'nothing is armed to begin with');

  await page.locator('[data-testid="arm-bump"]').click();
  const bump = await page.evaluate(() => ({
    label: window.__TB__.el('armed').textContent,
    revision: window.__TB__.model().revision,
  }));
  witness.eq(bump.label, 'armed: bump -> [:tb/bump-revision] fires in 5s',
    'the page names the event the bump arm will fire');
  witness.eq(bump.revision, before.revision, 'and nothing has happened yet — that is the whole point');

  await page.locator('[data-testid="arm-unmount"]').click();
  const unmount = await page.evaluate(() => ({
    label: window.__TB__.el('armed').textContent,
    mounted: !!document.querySelector('[data-testid="mountable"]'),
  }));
  witness.eq(unmount.label, 'armed: unmount -> [:tb/toggle-mounted] fires in 5s',
    'and the event the unmount arm will fire, which nothing pinned before');
  witness.eq(unmount.mounted, before.mounted, 'the field is still mounted — this one is deferred too');

  // AND THE FIRE. Everything above is equally true of an arm that never
  // armed: the label is rendered from the dispatch that set it, and
  // "nothing has happened yet" is exactly what a dead timer looks like.
  // Both arms shipped DEAD for that reason — `:tb/arm` returned a v1
  // top-level `:dispatch-later` beside `:db`, which re-frame2's closed
  // effect-map (seven top-level keys: `#{:db :rf.db/runtime :fx}` plus the
  // four EP-0025 commit-plane classification effects, migration M-8) does
  // not carry — and this section was green in three engines throughout,
  // while the operator instrument it claims to pin could not fire. That is
  // the PRE-rf2-04tx contract: the foreign key was dropped while the `:db`
  // beside it committed, so the label rendered with no timer behind it.
  // Today that spelling refuses the event pre-commit, label and all.
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
  //
  // AND SO DOES THE MOUNT-WAIT THAT FOLLOWS IT (rf2-vinj). It was bare until
  // that bead, which made the paragraph above true of the `goto` and false of
  // the line under it: `waitUntil` is `'commit'`, so the navigation is only
  // half done when `goto` returns and the mount-wait is the other half of the
  // SAME operation — yet it took Playwright's anonymous 30s while the goto
  // took 60,000. One operation, two budgets, and the second one invisible in
  // the source. That is the shape this comment already refuses; it just wore
  // a name the sweep did not police, which is now fixed there too.
  const base = page.url().split('?')[0];
  if (typeof runContext.navTimeoutMs !== 'number') {
    throw new Error(
      'the runner must pass `navTimeoutMs` — a navigation with no ceiling ' +
      'inherits Playwright\'s 30s default, which is invisible in the source ' +
      'and unreachable from the runner\'s own knob');
  }
  await page.goto(`${base}?arm-ms=${ARM_MS}`,
    { waitUntil: runContext.navWaitUntil, timeout: runContext.navTimeoutMs });
  await waitForRemount(page, runContext);

  const seed = await page.evaluate(() => window.__TB__.model().revision);
  await page.locator('[data-testid="arm-bump"]').click();
  witness.eq(await page.evaluate(() => window.__TB__.el('armed').textContent),
    `armed: bump -> [:tb/bump-revision] fires in ${ARM_MS}ms`,
    'the readout is rendered from the delay actually armed, not a constant');

  await waitForArm(page, (r) => window.__TB__.model().revision > r, seed,
    'the armed bump never fired');
  const fired = await page.evaluate(() => ({
    revision: window.__TB__.model().revision,
    label: window.__TB__.el('armed').textContent,
  }));
  witness.eq(fired.revision, seed + 1, 'the armed bump FIRES its event');
  witness.eq(fired.label, 'idle', 'and the readout returns to idle when it does');

  await page.locator('[data-testid="arm-unmount"]').click();
  await waitForArm(page,
    () => document.querySelector('[data-testid="mountable-gone"]') !== null,
    null, 'the armed unmount never fired');
  witness.eq(await page.evaluate(() =>
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
  // rf2-hic-040's conformance matrix. It runs after rf2-hic-016's witnesses
  // and BEFORE `armed-edges-are-wired`, deliberately: that section
  // re-navigates, which reseeds the whole store, so anything placed after it
  // would be reading a different page from the one every row above touched.
  ['radio-group-echoes-committed', radioGroupEchoesCommitted],
  ['select-single-echoes-committed', selectSingleEchoesCommitted],
  ['select-multiple-supported', selectMultipleSupported],
  ['reserved-marker-reads-the-whole-multiple-selection', reservedMarkerReadsTheWholeMultipleSelection],
  ['file-input-is-uncontrollable', fileInputIsUncontrollable],
  ['types-without-a-caret-echo-committed', typesWithoutACaretEchoCommitted],
  ['contenteditable-is-not-a-controlled-field', contenteditableIsNotAControlledField],
  ['blur-after-unmount', blurAfterUnmount],
  ['async-normalization', asyncNormalization],
  ['form-reset-autofill-and-formdata', formResetAutofillAndFormData],
  ['svg-attributes', svgAttributes],
  ['custom-element-attributes', customElementAttributes],
  ['armed-edges-are-wired', armedEdgesAreWired],
];

module.exports = {
  name: 'Hicasso controlled input (I15) — three engines',
  url: '/index.html',
  pageHelpers: PAGE_HELPERS,

  run: async (page, runContext) => {
    const witness = new Witness(runContext.engine);
    for (const [name, section] of SECTIONS) {
      const before = witness.checks;
      // `runContext` reaches every section so one that navigates is bounded by the
      // runner's own ceiling rather than a literal of its own.
      await section(page, witness, runContext);
      witness.sections[name] = witness.checks - before;
    }
    return { checks: witness.checks, recorded: witness.recorded, sections: witness.sections };
  },
};
