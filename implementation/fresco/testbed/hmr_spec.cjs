'use strict';

/*
 * THE HMR CONTRACT, WITNESSED IN A BROWSER (rf2-vsgq — the browser half
 * the #7755 audit on rf2-hic-015 requires).
 *
 * `hmr_registry_cljs_test` and `hmr_remount_cljs_test` measured this
 * contract in Node, at the commit seam, with `load-namespace!` standing in
 * for a reload. The audit accepted those suites and named what they cannot
 * reach: they call `collector/mint-view!` directly and drive the
 * commit/release seams by hand, so they can neither catch drift between
 * the `defview` macro and a real shadow reload, nor catch a renderer that
 * fails to run old-generation cleanup on a type replacement.
 *
 * Every save below is therefore a REAL ONE. `serve-and-run-hicasso-hmr-
 * testbed.cjs` runs `shadow-cljs watch`, rewrites one marked line in
 * `hicasso/testbed/hicasso_hmr_testbed/views.cljs`, and shadow recompiles
 * that namespace, pushes the module to the live page, re-evaluates it and
 * runs the app's `^:dev/after-load` hook. `runContext.save()` returns only once
 * the page has reported the new literal on screen — so nothing here can
 * pass on a reload that fired without carrying code.
 *
 * ## Why a value or a text assertion cannot see most of this
 *
 * The field under test is CONTROLLED, so its value after a save is
 * repainted from app-db — and app-db survives a save. The characters on
 * screen are therefore byte-identical whether React updated the fiber in
 * place or destroyed the subtree and built a new one. rf2-hic-016
 * established the same point by measurement: three injected regressions
 * were caught by its caret rows and by nothing else, because React's own
 * end-of-event restore repairs value errors inside the same discrete
 * event.
 *
 * So the observables here are the ones a repaint cannot forge:
 *
 *   - the DOM node's IDENTITY, carried as an expando the runtime knows
 *     nothing about, which cannot survive a node being replaced;
 *   - `document.activeElement` and the caret, which live on the node;
 *   - a foreign component's per-fiber instance id, minted once per mount
 *     from a counter that outlives the reload;
 *   - an imperative write to `textContent` that React never learned
 *     about, so no re-render can restore it;
 *   - and, for the hand-over, the registration objects themselves,
 *     compared by `identical?` in ClojureScript.
 *
 * Each section states, in its own comment, what a value assertion would
 * have said instead — and several rows ASSERT the value is unchanged
 * precisely so the blindness is on the record rather than argued.
 *
 * ## Every row is shown red first
 *
 * The rows that assert LOSS (focus, hook state, an instance) are paired
 * with a no-save control taken immediately before, on the same
 * instruments: if a control ever reports loss, the row is measuring a
 * stray remount rather than the reload, and that is the finding. The rows
 * that assert PRESERVATION (frame routing) carry the transition that
 * genuinely breaks them — a frame destroyed and rebuilt. The row that
 * asserts CLEANLINESS carries the sabotage.
 *
 * ## No row is a bare "it threw"
 *
 * rf2-hic-011 ran its own named sabotage and the reads still threw, from a
 * different layer and with a different error id, so `(is (thrown? …))`
 * would have stayed green. Nothing in this suite asserts that something
 * failed: every row asserts an exact value, an exact count or an exact
 * identity. Uncaught page errors are a separate, independent verdict the
 * runner keeps and fails on.
 */

const PAGE_HELPERS = `
window.__HMR__ = (function () {
  function door() {
    var d = window.__RF2_HMR__;
    if (!d) throw new Error('the testbed door is not installed');
    return d;
  }
  function el(root, id) {
    var n = document.querySelector('#' + root + ' [data-testid="' + id + '"]');
    if (!n) throw new Error('no [data-testid=' + id + '] under #' + root);
    return n;
  }
  // The browser's own value setter — the one React's value tracker is
  // watching. Assigning \`el.value\` would go through the tracker's patched
  // setter, React would conclude nothing changed, and the controlled path
  // this row is about would never run.
  function nativeSet(node, v) {
    Object.getOwnPropertyDescriptor(HTMLInputElement.prototype, 'value').set.call(node, v);
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
  function edit(root, id, next, caret, opts) {
    var node = el(root, id);
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
    return read(root, id);
  }
  // Everything about a field that a repaint cannot forge, in one read.
  // \`mark\` is an expando this driver put on the node: it survives any
  // number of re-renders and cannot survive the node being replaced.
  function read(root, id) {
    var n = el(root, id);
    return {
      value: n.value,
      start: n.selectionStart,
      end: n.selectionEnd,
      active: document.activeElement === n,
      mark: n.__hmrMark === undefined ? null : n.__hmrMark,
    };
  }
  function markNode(root, id, mark) { el(root, id).__hmrMark = mark; return mark; }
  function text(root, id) { return el(root, id).textContent; }
  function model(root) { return JSON.parse(door().model(root)); }
  function settle() { return new Promise(function (r) { setTimeout(r, 0); }); }
  // The runtime's own settling point. A residue baseline read before this
  // counts entries the runtime is about to drop.
  function quiesce() { return door().quiesce(); }
  return {
    door: door, el: el, nativeSet: nativeSet, fire: fire, composition: composition,
    edit: edit, read: read, markNode: markNode, text: text, model: model,
    settle: settle, quiesce: quiesce,
  };
})();
`;

// ---------------------------------------------------------------------------
// Assertion vocabulary
// ---------------------------------------------------------------------------

class Witness {
  constructor(engine) {
    this.engine = engine;
    this.checks = 0;
    this.recorded = {};
    this.sections = {};
  }

  eq(actual, expected, what) {
    this.checks += 1;
    if (!Object.is(actual, expected)) {
      throw new Error(
        `[${this.engine}] ${what}: expected ${JSON.stringify(expected)}, ` +
        `got ${JSON.stringify(actual)}`);
    }
  }

  // Deep equality for the residue maps, which are small flat objects.
  same(actual, expected, what) {
    this.checks += 1;
    const a = JSON.stringify(actual);
    const b = JSON.stringify(expected);
    if (a !== b) {
      throw new Error(`[${this.engine}] ${what}: expected ${b}, got ${a}`);
    }
  }

  // Conduct MEASURED rather than required. The runner compares these
  // across engines and reds on a divergence no narrowing names.
  record(key, value) { this.recorded[key] = value; }
}

const ROOTS = ['alpha', 'beta'];

// Every watched (frame, query) pair, spelled the way the door spells it.
const KEYS = [];
for (const root of ROOTS) {
  for (const q of ['[:hmr/text]', '[:hmr/digits]', '[:hmr/label]']) {
    KEYS.push(`${root} ${q}`);
  }
}

const identity = (page) => page.evaluate(() => window.__HMR__.door().identity());
const residue = (page) => page.evaluate(() => window.__HMR__.door().residue());
const capture = (page) => page.evaluate(() => window.__HMR__.door().capture());
const reloads = (page) => page.evaluate(() => window.__HMR__.door().reloads());
const quiesce = (page) => page.evaluate(() => window.__HMR__.quiesce());
const islandLoads = (page) => page.evaluate(() => window.__HMR__.door().islandLoads());
const pinEscapeHead = (page, on) =>
  page.evaluate((v) => window.__HMR__.door().pinEscapeHead(v), on);

// The split region's two crossings, in both roots.
const ISLAND_SLOTS = ['island-host', 'island-escape'];

// The island's arrival, waited for on a REAL SIGNAL rather than slept
// through. A save re-mints the heads, so the region suspends and shows its
// fallback until the chunk lands; every island row below is taken after
// this resolves, and it fails loudly rather than reading a fallback and
// calling it a remount.
const waitForIslands = (page) => page.waitForFunction(
  (slots) => slots.every((slot) => ['alpha', 'beta'].every((root) =>
    !!document.querySelector(`#${root} [data-testid="${slot}"]`))),
  ISLAND_SLOTS, { timeout: 30000, polling: 50 });

/** Every island's instance id and rendered generation label, per root. */
const islandState = (page) => page.evaluate((slots) => {
  const out = {};
  for (const root of ['alpha', 'beta']) {
    for (const slot of slots) {
      out[`${root} ${slot}`] = {
        instance: window.__HMR__.text(root, `${slot}-instance`),
        gen: window.__HMR__.text(root, `${slot}-gen`),
      };
    }
  }
  return out;
}, ISLAND_SLOTS);

// ---------------------------------------------------------------------------
// 1. The reload is real, and it really replaced what the macro made
// ---------------------------------------------------------------------------

// The audit's first clause: "a browser suite drives an actual shadow
// reload through `defview`". Three independent facts have to hold together
// for that sentence to be true — the hook ran, the NEW CODE is on screen,
// and the object the real `defview` macro produced was really replaced.
// A reload counter alone proves only the first; a counter plus a rendered
// literal proves shadow carried code; the head identity is what ties it to
// `defview` rather than to any other top-level form in the file.
async function reloadIsReal(page, witness, runContext) {
  // NEGATIVE CONTROL, taken FIRST so the identity instrument is proven
  // able to report SAMENESS before it is asked to report a change. Without
  // this every `head-same?: false` below would be vacuous — an instrument
  // that always answered "changed" would satisfy them all.
  await capture(page);
  await page.evaluate(() => window.__HMR__.settle());
  const quiet = await identity(page);
  witness.eq(quiet['head-same?'], true,
    'with no save, the head is the same object — the instrument can see sameness');

  const before = await reloads(page);
  const labelBefore = await page.evaluate(() => window.__HMR__.text('alpha', 'gen-label'));

  await capture(page);
  const label = await runContext.save();

  witness.eq(await reloads(page) > before, true, 'the ^:dev/after-load hook ran');
  witness.eq(label === labelBefore, false, 'the save really changed the source literal');
  witness.eq(await page.evaluate(() => window.__HMR__.text('alpha', 'gen-label')), label,
    'the NEW literal is on screen — shadow carried code, it did not merely fire');
  witness.eq(await page.evaluate(() => window.__HMR__.text('beta', 'gen-label')), label,
    'and in the second root too');

  const after = await identity(page);
  witness.eq(after['head-same?'], false,
    'the head the real defview macro produced was replaced by the real reload — ' +
    'which is the drift the node lane could not have caught');
}

// ---------------------------------------------------------------------------
// 2. The focused controlled input
// ---------------------------------------------------------------------------

// The matrix row, and the one where a value assertion is at its most
// misleading: the field is controlled off app-db, app-db survives a save,
// so the characters on screen are IDENTICAL either side of the reload.
// This section asserts that identity deliberately — it is the blindness,
// on the record — and then reads the three things that move.
async function focusedControlledInput(page, witness, runContext) {
  // Type into the middle of the string and leave the caret there.
  const typed = await page.evaluate(() =>
    window.__HMR__.edit('alpha', 'field', 'abZcdef', 3, { data: 'Z' }));
  witness.eq(typed.value, 'abZcdef', 'the keystroke was accepted');
  witness.eq(typed.start, 3, 'and the caret is mid-string, where a remount can be seen to lose it');
  await page.evaluate(() => window.__HMR__.markNode('alpha', 'field', 'N1'));

  // CONTROL: a turn passes with NO save. If focus, caret or the node's
  // identity moved here, the row below would be measuring a stray remount
  // rather than the reload.
  await page.evaluate(() => window.__HMR__.settle());
  const held = await page.evaluate(() => window.__HMR__.read('alpha', 'field'));
  witness.eq(held.mark, 'N1', 'CONTROL — with no save the node is the same node');
  witness.eq(held.active, true, 'CONTROL — and it still holds focus');
  witness.eq(held.start, 3, 'CONTROL — and the caret has not moved');

  await capture(page);
  await runContext.save();

  const after = await page.evaluate(() => window.__HMR__.read('alpha', 'field'));
  witness.eq(after.value, 'abZcdef',
    'the VALUE is byte-identical across the save — a value assertion sees nothing here');
  witness.eq(after.mark, null,
    'but the node that held the caret is gone: the expando cannot survive a replacement');
  witness.eq(after.active, false, 'so focus is lost, which is what the contract promises');
  // The caret a freshly-mounted, unfocused control reports is the
  // browser's business, not this runtime's, so it is measured rather than
  // required — and compared across engines by the runner.
  witness.record('caret-after-a-save', { start: after.start, end: after.end });
}

// ---------------------------------------------------------------------------
// 3. An in-flight composition
// ---------------------------------------------------------------------------

// Driven on the REFUSING field, and that is what makes it a witness. With
// an accepting model the composing draft would be in app-db, so the
// post-save repaint would put the same characters back and "the draft did
// not survive" would be satisfied by a runtime that preserved everything.
// Here the model never took the draft, so the characters on screen after
// the save can only be the committed value.
async function compositionInFlight(page, witness, runContext) {
  const live = await page.evaluate(() => {
    const node = window.__HMR__.el('alpha', 'digits');
    node.focus();
    window.__HMR__.composition(node, 'compositionstart', '');
    const draft = window.__HMR__.edit('alpha', 'digits', '123あ', 4, {
      isComposing: true, inputType: 'insertCompositionText', data: 'あ',
    });
    window.__HMR__.markNode('alpha', 'digits', 'D1');
    return { draft, model: window.__HMR__.model('alpha').digits };
  });
  witness.eq(live.draft.value, '123あ', 'the field is holding a composing draft');
  witness.eq(live.model, '123', 'and the model refused it, so the draft is nowhere but the DOM');

  // CONTROL: the draft survives a turn in which nothing reloaded. Without
  // this, "the draft is gone after the save" could be an ordinary converge
  // having wiped it a moment later.
  await page.evaluate(() => window.__HMR__.settle());
  const held = await page.evaluate(() => window.__HMR__.read('alpha', 'digits'));
  witness.eq(held.value, '123あ', 'CONTROL — with no save the composition is still in flight');
  witness.eq(held.mark, 'D1', 'CONTROL — on the same node');

  await capture(page);
  await runContext.save();

  const after = await page.evaluate(() => window.__HMR__.read('alpha', 'digits'));
  witness.eq(after.mark, null, 'the composing node was destroyed by the save');
  witness.eq(after.value, '123',
    'and the field shows the committed value — the in-flight composition is lost, ' +
    'not stranded on screen as a draft the model never agreed to');
  witness.eq(after.active, false, 'the composing field is no longer focused');
}

// ---------------------------------------------------------------------------
// 4. Child hook state inside a host
// ---------------------------------------------------------------------------

// The child is plain React with no Hicasso in it, which is what makes it a
// fair witness: the runtime is not being asked to preserve something it
// could have preserved. Two observables rather than one — the counter and
// the per-fiber instance id — because a counter alone cannot separate
// "the state was reset" from "the component was rebuilt", and only the
// second is the claim.
async function childHookStateInAHost(page, witness, runContext) {
  await page.evaluate(async () => {
    for (let i = 0; i < 3; i += 1) {
      window.__HMR__.el('alpha', 'hook-bump').click();
      await window.__HMR__.settle();
    }
  });
  const before = await page.evaluate(() => ({
    count: window.__HMR__.text('alpha', 'hook-count'),
    instance: window.__HMR__.text('alpha', 'hook-instance'),
  }));
  witness.eq(before.count, '3', 'the child is holding three clicks of its own hook state');

  // CONTROL: no save, so the state must still be there. A row asserting
  // "0 after a save" is worthless if the counter resets on its own.
  await page.evaluate(() => window.__HMR__.settle());
  const held = await page.evaluate(() => ({
    count: window.__HMR__.text('alpha', 'hook-count'),
    instance: window.__HMR__.text('alpha', 'hook-instance'),
  }));
  witness.eq(held.count, '3', 'CONTROL — with no save the hook state is still three');
  witness.eq(held.instance, before.instance, 'CONTROL — and it is the same fiber');

  await capture(page);
  await runContext.save();

  const after = await page.evaluate(() => ({
    count: window.__HMR__.text('alpha', 'hook-count'),
    instance: window.__HMR__.text('alpha', 'hook-instance'),
  }));
  witness.eq(after.count, '0', 'the save cleared the child hook state');
  witness.eq(after.instance === before.instance, false,
    'and it is a different fiber — the state was not reset, the component was rebuilt');
  witness.eq(Number(after.instance) > Number(before.instance), true,
    'from a counter that outlived the reload, so the two ids are comparable');
}

// ---------------------------------------------------------------------------
// 5. The active imperative host
// ---------------------------------------------------------------------------

// "Active" is the load-bearing word, so the row makes the host actually
// do something first: `note!` writes `textContent` on a node the foreign
// component owns, directly, with React never learning about it. No
// re-render can restore that text and no subscription repaints it — so if
// it is gone after the save, the node it was written on is gone.
async function activeImperativeHost(page, witness, runContext) {
  const before = await page.evaluate(() => {
    const id = window.__HMR__.door().note('alpha', 'LIVE');
    return { id, text: window.__HMR__.text('alpha', 'note'),
             instanceId: window.__HMR__.door().instanceId('alpha') };
  });
  witness.eq(before.text, 'LIVE', 'the imperative handle really drove the host');
  witness.eq(before.id, before.instanceId, 'and the call was served by the attached instance');

  // ONE baseline, taken here and used by BOTH readings below — which is
  // what makes the pair a proof rather than two separate claims: the
  // control says this baseline matches the live instance, and the row
  // then says the same baseline does not, so the only thing that changed
  // between them is the save.
  //
  // Taking it here also fixes a defect the control caught: a baseline
  // captured before the PREVIOUS section's save is stale by a remount, so
  // `instance-same?` was already false before this section did anything
  // and the control failed. It was right to.
  await capture(page);

  // CONTROL: no save. The instance must still be the same one and its
  // imperative write must still be on screen.
  await page.evaluate(() => window.__HMR__.settle());
  const quiet = await identity(page);
  witness.eq(quiet.frames.alpha['instance-same?'], true,
    'CONTROL — with no save the host instance is the same object');
  witness.eq(await page.evaluate(() => window.__HMR__.text('alpha', 'note')), 'LIVE',
    'CONTROL — and its imperative write is still there');

  await runContext.save();

  const after = await page.evaluate(() => ({
    text: window.__HMR__.text('alpha', 'note'),
    instanceId: window.__HMR__.door().instanceId('alpha'),
    attach: window.__HMR__.door().instance('alpha'),
  }));
  const ids = await identity(page);
  witness.eq(ids.frames.alpha['instance-same?'], false,
    'the ref now holds a DIFFERENT instance object — the host was torn down and rebuilt');
  witness.eq(after.instanceId === before.instanceId, false, 'with a new instance id to match');
  witness.eq(after.text, '',
    'and the imperative write is gone, which only a destroyed node can explain');
  // A ref callback firing is not by itself evidence of a remount — React
  // detaches and re-attaches on plenty of occasions — so the counts are
  // measured rather than required.
  witness.record('ref-traffic-across-a-save', after.attach);
}

// ---------------------------------------------------------------------------
// 6. Frame routing
// ---------------------------------------------------------------------------

// The audit's clause the bead's own text did not name. Two frames render
// the SAME view into two roots, so "each root still reads its own frame"
// is a real question with a wrong answer available. The row checks the
// static half (each root paints its own label) and the LIVE half (a write
// to one frame after the reload does not move the other), because a
// routing table that survived as stale text would pass the first alone.
async function frameRoutingAcrossASave(page, witness, runContext) {
  await page.evaluate(() => window.__HMR__.door().setLabel('alpha', 'ALPHA-2'));
  await page.evaluate(() => window.__HMR__.settle());
  witness.eq(await page.evaluate(() => window.__HMR__.text('alpha', 'frame-label')), 'ALPHA-2',
    'the write reached its own frame');
  witness.eq(await page.evaluate(() => window.__HMR__.text('beta', 'frame-label')), 'BETA',
    'and did not reach the other one');

  await capture(page);
  await runContext.save();

  witness.eq(await page.evaluate(() => window.__HMR__.text('alpha', 'frame-label')), 'ALPHA-2',
    'after the save each root still paints its own frame');
  witness.eq(await page.evaluate(() => window.__HMR__.text('beta', 'frame-label')), 'BETA');

  const ids = await identity(page);
  witness.eq(ids.frames.alpha['token-same?'], true,
    'the reload hook re-ran make-frame and did NOT reincarnate — same token object');
  witness.eq(ids.frames.beta['token-same?'], true);
  witness.eq(ids.frames.alpha['row-same?'], true,
    "and the arm's frame-op row is the same object, so a bundle captured before " +
    'the save still addresses the same incarnation');
  witness.eq(ids.frames.beta['row-same?'], true);

  // The LIVE half: routing is still wired, not merely still painted.
  await page.evaluate(() => window.__HMR__.door().setLabel('alpha', 'ALPHA-3'));
  await page.evaluate(() => window.__HMR__.settle());
  witness.eq(await page.evaluate(() => window.__HMR__.text('alpha', 'frame-label')), 'ALPHA-3',
    'a write after the reload still reaches the remounted root');
  witness.eq(await page.evaluate(() => window.__HMR__.text('beta', 'frame-label')), 'BETA',
    'and frame isolation survived the save');

  // NEGATIVE CONTROL. The token reader is worth nothing unless it can
  // report a change, and this is the transition that produces one: the
  // OTHER reload shape, a hook that destroys the frame before rebuilding
  // it. Scoped to beta, so the same reading proves alpha's `true` above
  // was not the instrument being stuck.
  await capture(page);
  await page.evaluate(() => window.__HMR__.door().reincarnate('beta', 'BETA-R'));
  await page.evaluate(() => window.__HMR__.settle());
  const control = await identity(page);
  witness.eq(control.frames.beta['token-same?'], false,
    'CONTROL — destroying and rebuilding a frame DOES reincarnate it');
  witness.eq(control.frames.alpha['token-same?'], true,
    'CONTROL — and only the frame it was done to; alpha is untouched');
  witness.eq(await page.evaluate(() => window.__HMR__.text('beta', 'frame-label')), 'BETA-R',
    'CONTROL — the rebuilt frame paints its re-seeded state');
}

// ---------------------------------------------------------------------------
// 7. The island and the React.lazy bridge, through a real recompile
// ---------------------------------------------------------------------------

// rf2-y5x6j and rf2-iq0a in one section, because they are one fixture.
//
// §7 of the Hicasso specification names `HMR` in the code-splitting row's
// required proof, beside load, fallback, error and retry — the four that
// landed in `lazy_boundary_dom_cljs_test`, which then stated the hot-reload
// fact in PROSE: "the retry a rejected chunk needs is a NEW HEAD, which is
// the same allocation a hot reload performs". Nothing had performed one.
// Separately, the island is a raw React function component behind a
// `react/memo` record and two `react/lazy` heads — top-level `def`s in the
// reloaded namespace, every one — and React's own remount rule says a save
// REPLACES each of them: the def re-evaluates, the element type at that
// position is a new object, and React rebuilds the subtree. Allocation,
// never a lookup by name. Nothing had measured that under a real recompile
// either.
//
// Both claims are about the same event, so they are measured on the same
// save. The instrument is the LOADER COUNT and OBJECT IDENTITY, never the
// paint: a re-fetched chunk and a preserved one paint the same island, in
// the same place, with the same text — which is exactly how the prose
// survived unmeasured.
async function nativeLazyIslandAcrossASave(page, witness, runContext) {
  await waitForIslands(page);
  await quiesce(page);
  await capture(page);
  const loadsBefore = await islandLoads(page);
  const before = await islandState(page);
  // Taken past the runtime's own reap horizon, which is the only honest
  // place for a residue baseline — the same discipline as section 9's.
  const censusBefore = await residue(page);

  // CONTROL, taken FIRST. Every row below reports a CHANGE, so the
  // instruments have to be shown able to report sameness — otherwise an
  // identity reader that always answered "different" and a counter that
  // ticked on its own would satisfy the whole section.
  await page.evaluate(() => window.__HMR__.settle());
  const quiet = await identity(page);
  witness.eq(quiet.heads.hostHead['same?'], true,
    'CONTROL — with no save the defhost crossing\'s lazy head is the same object');
  witness.eq(quiet.heads.escapeHead['same?'], true,
    'CONTROL — and so is the [:>] crossing\'s');
  witness.eq(quiet.heads.islandMemo['same?'], true,
    'CONTROL — and the memo record around the island');
  witness.eq(quiet.heads.islandBody['same?'], true,
    'CONTROL — and the island itself');
  witness.eq(await islandLoads(page), loadsBefore,
    'CONTROL — and nothing re-fetched the chunk, so the count below is the save\'s');

  await runContext.save();
  await waitForIslands(page);

  // ------------------------------------------------------------------
  // rf2-y5x6j: WHICH of the two is it?
  // ------------------------------------------------------------------
  //
  // The bead requires this row to state one and assert it, rather than
  // assert whichever happened. It is THE BOUNDARY RE-LOADS. A head is a
  // top-level `def` in the reloaded namespace, so the save re-evaluates it
  // and `react/lazy` allocates a fresh record; a fresh payload is
  // Uninitialized, so React calls the loader again and the chunk is
  // fetched a second time. The loaded module does NOT survive the save.
  //
  // Two heads, two payloads, so exactly two fetches — one per crossing.
  // The `+ 2` is the whole assertion: `> loadsBefore` would be green for a
  // bridge that re-fetched once and shared, and `>= loadsBefore` would be
  // green for one that never re-fetched at all.
  witness.eq(await islandLoads(page), loadsBefore + 2,
    'THE ANSWER: the boundary RE-LOADS. Each re-minted head gets a fresh, '
    + 'Uninitialized payload, so the chunk is fetched again — once per head, '
    + 'and the loaded module did not survive');

  const after = await identity(page);
  witness.eq(after.heads.hostHead['same?'], false,
    'the defhost crossing\'s lazy head was re-minted by the save');
  witness.eq(after.heads.escapeHead['same?'], false, 'and the [:>] crossing\'s');
  witness.eq(after.heads.islandMemo['same?'], false,
    'and the `react/memo` record — a memo record is the element type React '
    + 'reconciles on, so it must NOT survive a hot reload, and nothing had run '
    + 'one to check');
  witness.eq(after.heads.islandBody['same?'], false,
    'and the island function itself — allocation, never a lookup by name, '
    + 'which is React\'s own remount rule');

  // The subtree really was rebuilt, read three independent ways: React's
  // own fiber state, the DOM node the ref is attached to, and the ref
  // traffic itself.
  const state = await islandState(page);
  for (const key of Object.keys(before)) {
    witness.eq(state[key].instance === before[key].instance, false,
      `the island at ${key} is a different fiber — the subtree was rebuilt`);
    witness.eq(Number(state[key].instance) > Number(before[key].instance), true,
      `from the counter that outlived the reload, so the two ids are comparable (${key})`);
    witness.eq(after.islands[key]['node-same?'], false,
      `and its ref re-attached to a NEW node at ${key}`);
    witness.eq(after.islands[key]['attached?'], true,
      `and is attached, not merely detached and dropped (${key})`);
    // Not an identity claim: `generation-label` is a live namespace read,
    // so even a stale closure would answer the new literal. It says the
    // island is painting THIS generation's label, which is what makes the
    // region live rather than a fallback the row mistook for an island.
    witness.eq(state[key].gen === before[key].gen, false,
      `and it is painting the new generation's label at ${key}`);
  }

  // Ownership across the save. The island holds no subscription by
  // construction — see `views.cljs`, where the reason is that a
  // subscribing island in this shared tree would put every other section's
  // residue comparison in a race with React's scheduler — so this is a
  // claim about the WHOLE census, which the rest of the app populates: a
  // lazy re-load and a full remount of the split region leak nothing into
  // it.
  await quiesce(page);
  witness.same(await residue(page), censusBefore,
    'and the retained census is unchanged across the save: a re-loaded split '
    + 'region and a fully rebuilt island subtree leak nothing into it');
}

// ---------------------------------------------------------------------------
// 8. The pinned-head sabotage
// ---------------------------------------------------------------------------

// rf2-y5x6j's third acceptance clause: break the bridge's reload path and
// the row above fails BY NAME.
//
// The fault modelled is a bridge that caches a head BY NAME. React's
// remount rule is that a `def` the save re-evaluates is a new object and
// the subtree at that position is rebuilt; a runtime that went on
// rendering the head it had before the save would preserve identity
// across a reload and quietly contradict that. So the sabotage is exactly
// that runtime, holding the head it had before the save and rendering it
// afterwards. Everything else is untouched: the module still
// re-evaluates, `react/lazy` still mints a fresh head, and the `defhost`
// crossing beside it still takes the new one. Only what the `[:>]`
// crossing RENDERS is pinned.
//
// Unlike the lost-cleanup sabotage this one is RECOVERABLE, so all four
// phases assert: arm, confirm red, disarm, confirm green.
async function pinnedLazyHeadSabotage(page, witness, runContext) {
  await waitForIslands(page);
  await capture(page);
  const loadsBefore = await islandLoads(page);

  witness.eq(await pinEscapeHead(page, true), true, 'the sabotage is armed');
  await runContext.save();
  await waitForIslands(page);

  const red = await identity(page);
  // THE PREMISE, and the row is worthless without it: the save really did
  // re-mint the head. The fault is not that the module failed to reload —
  // it is that the app went on rendering the head it had.
  witness.eq(red.heads.escapeHead['same?'], false,
    'the premise — the module DID re-evaluate and mint a fresh head');
  witness.eq(red.heads.hostHead['same?'], false,
    'and the unsabotaged crossing beside it took its own fresh head');

  witness.eq(red.heads.rendered['same?'], true,
    'RED — but the head the app RENDERS survived the save: a bridge that '
    + 'cached by name, which is the conduct React\'s remount rule forbids');
  witness.eq(await islandLoads(page), loadsBefore + 1,
    'RED — and the chunk was fetched ONCE, not twice: a resolved payload is '
    + 'never re-read, so the pinned crossing silently skipped its re-load. '
    + 'This is the exact number the row above asserts, and it is wrong here');

  // RESTORE.
  witness.eq(await pinEscapeHead(page, false), false, 'the sabotage is disarmed');
  await capture(page);
  const loadsRestored = await islandLoads(page);
  await runContext.save();
  await waitForIslands(page);

  const green = await identity(page);
  witness.eq(green.heads.rendered['same?'], false,
    'GREEN — the app renders the head the save minted again');
  witness.eq(await islandLoads(page), loadsRestored + 2,
    'GREEN — and both crossings re-loaded, so the red above was the pin and '
    + 'not a broken instrument');
}

// ---------------------------------------------------------------------------
// 9. Zero stale-generation registrations
// ---------------------------------------------------------------------------

// The audit's last clause, and the one the whole door exists for. Read
// past the runtime's own reap horizon, because a residue baseline taken
// one macrotask after a render still counts entries the runtime is about
// to drop.
//
// `count` alone cannot settle this: a runtime that leaked the predecessor
// AND failed to acquire for the successor also reports one reader. `stale`
// is the identity half — how many of the registrations reading the key
// right now are the SAME OBJECTS that were reading it before the save —
// and it is computed with `identical?` inside ClojureScript, so no cyclic
// object is ever serialised to reach this assertion.
async function zeroStaleRegistrations(page, witness, runContext) {
  await quiesce(page);
  await page.evaluate(() => window.__HMR__.markNode('alpha', 'field', 'R1'));
  await capture(page);
  const baseline = await residue(page);

  await runContext.save();
  await quiesce(page);

  const ids = await identity(page);
  // THE ROW'S OWN PREMISE, asserted rather than assumed. "No predecessor
  // is still reading the key" is trivially true of a save that replaced
  // nothing, so without these two the whole section is fail-open — it
  // would report a perfect hand-over for a reload that never reached the
  // boundary. Both are read the way the sections above read them: the
  // head by identity, the subtree by an expando the runtime cannot see.
  witness.eq(ids['head-same?'], false,
    'the save this row measures really did re-mint the head');
  witness.eq(await page.evaluate(() => window.__HMR__.read('alpha', 'field').mark), null,
    'and really did replace the subtree — so a clean hand-over is a claim ' +
    'about a hand-over that happened');
  for (const key of KEYS) {
    const row = ids.keys[key];
    const seen = `now ${JSON.stringify(row.now)} was ${JSON.stringify(row.was)}`;
    witness.eq(row.count, 1, `exactly one boundary reads ${key} after the save — ${seen}`);
    witness.eq(row.stale, 0,
      `and it is the SUCCESSOR's registration — no predecessor is still ` +
      `reading ${key} — ${seen}`);
  }
  witness.same(await residue(page), baseline,
    'and the whole retained census is unchanged: the save cost nothing');

  // Saves do not accumulate. A per-generation leak of any size shows as
  // growth here, and section 8 is this row's proof that the instrument
  // does grow when something is retained.
  await capture(page);
  await runContext.save();
  await runContext.save();
  await quiesce(page);
  const after = await identity(page);
  for (const key of KEYS) {
    witness.eq(after.keys[key].count, 1, `three saves later, still one reader on ${key}`);
    witness.eq(after.keys[key].stale, 0, `and still no predecessor on ${key}`);
  }
  witness.same(await residue(page), baseline, 'three saves later, the same census');
  witness.record('retained-census', baseline);
}

// ---------------------------------------------------------------------------
// 10. The lost-cleanup sabotage
// ---------------------------------------------------------------------------

// The bead's named perturbation, run for real. The fault is a RENDERER
// fault — "a renderer failing to run old-generation cleanup on type
// replacement" — so it is injected at the renderer's own seam: the
// function React calls to unsubscribe is swallowed, and the runtime, which
// is not modified at all, never learns that the retired boundary went
// away.
//
// RUN LAST, and the reason is the finding rather than a scheduling
// detail: a swallowed cleanup is UNRECOVERABLE. The stranded registration
// holds the key for the life of the page, so "restore and confirm green"
// cannot mean the count returning to one. What it means, and what is
// asserted, is that the count HOLDS at two across two further clean saves
// — exactly one casualty, from exactly one sabotaged save, and the
// hand-over working correctly again either side of it.
async function lostCleanupSabotage(page, witness, runContext) {
  await quiesce(page);
  await capture(page);
  const clean = await residue(page);

  witness.eq(await page.evaluate(() => window.__HMR__.door().sabotage(true)), true,
    'the sabotage is armed');
  await runContext.save();
  await quiesce(page);

  const red = await identity(page);
  for (const key of KEYS) {
    witness.eq(red.keys[key].count, 2, `RED — two registrations are now reading ${key}`);
    witness.eq(red.keys[key].stale, 1,
      `RED — and one of them is the predecessor, by identity, on ${key}`);
  }
  const grown = await residue(page);
  witness.eq(grown['cell-refs'] > clean['cell-refs'], true,
    'RED — the retained census grew, which the clean saves never did');
  witness.eq(grown.cells, clean.cells,
    'RED — one cell still, because the leak is a reader that never let go ' +
    'rather than a second key');

  // The leak is LIVE, not a dead slot: a write reaches the retired
  // generation too. Without this the row could not tell a stranded
  // reference from a still-wired subscription.
  const notifiedBefore = await page.evaluate(() => window.__HMR__.door().staleNotified());
  await page.evaluate(() => window.__HMR__.door().setLabel('alpha', 'ALPHA-4'));
  await page.evaluate(() => window.__HMR__.settle());
  witness.eq(await page.evaluate(() => window.__HMR__.door().staleNotified()) > notifiedBefore, true,
    'RED — the unmounted generation was told the store moved');

  // RESTORE.
  witness.eq(await page.evaluate(() => window.__HMR__.door().sabotage(false)), false,
    'the sabotage is disarmed');
  await capture(page);
  await runContext.save();
  await runContext.save();
  await quiesce(page);

  const green = await identity(page);
  for (const key of KEYS) {
    witness.eq(green.keys[key].count, 2,
      `GREEN — two further saves added NOTHING to ${key}: the count holds at the ` +
      'one registration the sabotage stranded');
    witness.eq(green.keys[key].stale, 1,
      `GREEN — and exactly one of the two is old, so the predecessor of each ` +
      `clean save did let go on ${key}`);
  }
  witness.same(await residue(page), grown,
    'GREEN — the census is unchanged across the restored saves, so the red above ' +
    'was the swallowed cleanup and not a broken instrument');
}

// ---------------------------------------------------------------------------

// The order matters — later sections read state earlier ones left, and the
// sabotage is unrecoverable so it runs last — and the NAMES matter,
// because the runner's coverage floor requires this exact set by name. A
// section deleted from this list fails the gate rather than shrinking a
// total.
const SECTIONS = [
  ['reload-is-real', reloadIsReal],
  ['focused-controlled-input', focusedControlledInput],
  ['composition-in-flight', compositionInFlight],
  ['child-hook-state-in-a-host', childHookStateInAHost],
  ['active-imperative-host', activeImperativeHost],
  ['frame-routing-across-a-save', frameRoutingAcrossASave],
  // The split region's two, before the census rows. Their own sabotage is
  // recoverable and is disarmed inside its section, so nothing downstream
  // inherits it — unlike `lost-cleanup-sabotage`, which strands a
  // registration for the life of the page and must therefore stay last.
  ['native-lazy-island-across-a-save', nativeLazyIslandAcrossASave],
  ['pinned-lazy-head-sabotage', pinnedLazyHeadSabotage],
  ['zero-stale-registrations', zeroStaleRegistrations],
  ['lost-cleanup-sabotage', lostCleanupSabotage],
];

module.exports = {
  name: 'Hicasso HMR contract through a real shadow reload',
  url: '/',
  pageHelpers: PAGE_HELPERS,
  SECTIONS,

  run: async (page, runContext) => {
    const witness = new Witness(runContext.engine);
    for (const [name, section] of SECTIONS) {
      const before = witness.checks;
      await section(page, witness, runContext);
      witness.sections[name] = witness.checks - before;
    }
    return { checks: witness.checks, recorded: witness.recorded, sections: witness.sections };
  },
};
