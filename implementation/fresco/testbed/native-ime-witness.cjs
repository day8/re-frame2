'use strict';

/*
 * THE SCRIPTED NATIVE-IME WITNESS — the eight checks, and the observations
 * each one is decided on (rf2-hic-016).
 *
 * This module is the checklist of
 * `docs/design/hicasso/native-ime-scripted-witness.md` expressed as code. It
 * is driven by `implementation/scripts/run-hicasso-native-ime-witness.cjs`,
 * which owns the browser, the server and the OS-level keyboard; everything
 * here is about WHAT to type and HOW to read the answer.
 *
 * ## What replaced the human, and what did not
 *
 * The operator ruling of 2026-08-11 retired the manual session as the
 * acceptance path and sanctioned driving a REAL IME from a script. What that
 * buys is repeatability and a written record. What it does NOT buy is a CI
 * gate: this needs Windows, an installed Japanese IME, a visible desktop and
 * the keyboard, so it runs locally, on purpose, by a person who knows it is
 * running. The recurring regression net stays what it was — the synthetic
 * three-engine gate at `spec.cjs`. Read `WHAT THIS IS NOT` in the runner
 * before quoting this file as coverage.
 *
 * ## Three verdicts, and why a cross is not a failure
 *
 *   TICK          the check's claim held, on this engine, with a real IME.
 *   CROSS         it did not. That is a FINDING — it becomes a bead with an
 *                 engine name on it, exactly as the manual session's cross
 *                 would have. It is not a reason to re-run until green.
 *   INCONCLUSIVE  the check could not be decided, because its PREMISE was
 *                 not met — most often that no composition ever started, so
 *                 there was no exchange to observe. An inconclusive check
 *                 leaves the disposition cell unfillable, and the run says
 *                 so and exits non-zero.
 *
 * The third verdict is the whole reason this file is not a list of
 * assertions. During the operator's partial manual session (2026-08-11),
 * typing in `plain` and pressing ESC discarded NOTHING — the draft stayed.
 * Two readings fit that observation and they are opposite:
 *
 *   - the IME never engaged, so `nihongo` went in as seven ASCII letters and
 *     ESC had no composition to abort. Nothing about Hicasso was measured.
 *   - the IME did engage and the abort left the draft standing, which is a
 *     real defect in an engine.
 *
 * A checklist read off the screen cannot separate them, and that is why the
 * observation went unresolved. A script can, because the discriminator is in
 * the event stream: a real exchange fires `compositionstart`, carries
 * `isComposing` on its `input` events, and delivers ESC to the page as
 * `keydown` with `key === 'Process'` (keyCode 229) rather than as `Escape` —
 * the IME ate it. So check 5 runs FIRST, records that evidence, and returns
 * INCONCLUSIVE-with-a-named-premise rather than a cross when the exchange
 * was never real.
 *
 * ## The observer, and why the page is not modified
 *
 * The testbed app is unchanged: the same bundle a human would open. The
 * event evidence comes from a capture-phase LISTENER installed by
 * `addInitScript` before the app mounts — the same door `spec.cjs` uses for
 * its page helpers. It reads; it never dispatches, never mutates, never
 * touches a value. A composition driven through the OS input stack is real
 * whether or not something is listening.
 *
 * The primary observable stays the page's own trace table (committed value
 * and intents-arrived count per field), read back through the DOM, because
 * that is what the ruling names and what a browser shell with no devtools
 * can show.
 *
 * ## Why the decisive edges are read TWICE
 *
 * The observer accumulates, so one reading taken at the end of a check is an
 * aggregate over everything the check did — and an aggregate cannot say WHICH
 * keystroke produced it. Audit #7896 found that gap load-bearing under three
 * verdicts: an exchange the candidate-window Space had already closed ticked a
 * no-op Enter, and a check whose whole subject is "mid-composition" could tick
 * with no composition anywhere near the edge. So every decisive edge here is
 * bracketed — evidence read immediately before the keystroke and immediately
 * after it — and the verdict names the transition between the two rather than
 * the total. `exchangeIsOpen` and `closedAcross` are that discipline in two
 * lines each.
 */

// The seeds the testbed starts every field at (`hicasso_testbed.core/seed`).
// Duplicated here deliberately: a check asserts "the field went back to its
// committed value", and reading that expectation out of the page under test
// would make the assertion agree with any value the page happened to hold.
const SEEDS = {
  plain: 'abc',
  digits: '123',
  empty: '',
  grouped: '1,234',
  upper: 'ABC',
  notes: 'one two',
  revision: 'keep',
  'revision-strict': '42',
  'form-a': 'FORM',
  'form-b': 'form',
  mountable: '9',
};

const TRACED_FIELDS = Object.keys(SEEDS);

// `nihongo` -> にほんご -> 日本語. Seven letters, one Space to open the
// candidate window, one Enter to commit. The plans are DATA so the dry run
// can print every keystroke it would have sent without sending one.
const ROMAJI = ['n', 'i', 'h', 'o', 'n', 'g', 'o'];
const KEY_PLANS = {
  compose: ROMAJI,
  composeShort: ROMAJI.slice(0, 3),
  composeRest: ROMAJI.slice(3),
  selectCandidate: ['space'],
  commit: ['enter'],
  abort: ['escape'],
};

/** Clojure's `pr-str` of a string, which is what the trace cell renders. */
function prStr(s) {
  return `"${String(s).replace(/\\/g, '\\\\').replace(/"/g, '\\"')}"`;
}

// ---------------------------------------------------------------------------
// The page-side observer. Serialised into the page by `addInitScript`, so it
// must be self-contained.
// ---------------------------------------------------------------------------

function pageObserver() {
  const state = { events: [], cap: 4000 };
  const testid = (el) => (el && el.getAttribute && el.getAttribute('data-testid')) || null;
  const record = (e) => {
    if (state.events.length >= state.cap) return;
    state.events.push({
      type: e.type,
      target: testid(e.target),
      data: typeof e.data === 'string' ? e.data : null,
      isComposing: e.isComposing === true,
      key: typeof e.key === 'string' ? e.key : null,
      keyCode: typeof e.keyCode === 'number' ? e.keyCode : null,
      value: e.target && typeof e.target.value === 'string' ? e.target.value : null,
      t: Date.now(),
    });
  };
  for (const type of [
    'compositionstart', 'compositionupdate', 'compositionend',
    'beforeinput', 'input', 'keydown', 'keyup',
  ]) {
    document.addEventListener(type, record, true);
  }
  window.__RF2_IME_WITNESS__ = {
    events: () => state.events.slice(),
    reset: () => { state.events.length = 0; return true; },
  };
}

// ---------------------------------------------------------------------------
// Reading the page
// ---------------------------------------------------------------------------

async function readTrace(page) {
  return page.evaluate((fields) => {
    const out = {};
    for (const f of fields) {
      const v = document.querySelector(`[data-testid="trace-${f}-value"]`);
      const e = document.querySelector(`[data-testid="trace-${f}-edits"]`);
      out[f] = {
        committed: v ? v.textContent : null,
        edits: e ? Number(e.textContent) : null,
      };
    }
    return out;
  }, TRACED_FIELDS);
}

async function readField(page, field) {
  return page.evaluate((f) => {
    const el = document.querySelector(`[data-testid="${f}"]`);
    if (!el) return null;
    return {
      value: el.value,
      selectionStart: el.selectionStart,
      selectionEnd: el.selectionEnd,
      focused: document.activeElement === el,
    };
  }, field);
}

async function readEvents(page) {
  return page.evaluate(() => (window.__RF2_IME_WITNESS__
    ? window.__RF2_IME_WITNESS__.events() : []));
}

async function resetEvents(page) {
  return page.evaluate(() => (window.__RF2_IME_WITNESS__
    ? window.__RF2_IME_WITNESS__.reset() : false));
}

/**
 * The composition evidence for one field — the discriminator the manual
 * checklist could not read. `processKeydowns` is the sharp one: a keystroke
 * an IME has consumed reaches the page as `key === 'Process'` / keyCode 229,
 * so it says the IME was in the loop at all, independently of what any value
 * ended up being.
 */
function compositionEvidence(events, field) {
  const mine = events.filter((e) => e.target === field);
  const of = (type) => mine.filter((e) => e.type === type);
  const ends = of('compositionend');
  return {
    compositionstart: of('compositionstart').length,
    compositionupdate: of('compositionupdate').length,
    compositionend: ends.length,
    compositionendData: ends.length ? ends[ends.length - 1].data : null,
    composingInputs: of('input').filter((e) => e.isComposing).length,
    settledInputs: of('input').filter((e) => !e.isComposing).length,
    processKeydowns: of('keydown').filter((e) => e.key === 'Process' || e.keyCode === 229).length,
    escapeKeydowns: of('keydown').filter((e) => e.key === 'Escape').length,
  };
}

/** Did a real IME exchange happen at all? The premise every check rests on. */
function compositionWasReal(ev) {
  return ev.compositionstart > 0 && (ev.compositionupdate > 0 || ev.composingInputs > 0);
}

/**
 * Was an exchange OPEN at the instant this reading was taken? `compositionstart`
 * outnumbering `compositionend` is the discriminator, and it is the one an
 * aggregate reading cannot supply: `compositionWasReal` says a composition
 * happened SOMEWHERE in the check, and "somewhere" is not "immediately before
 * the keystroke under test". Audit #7896 found three checks resting their whole
 * verdict on that difference, which is why the decisive edges below are read
 * from a PAIR of evidence snapshots rather than from one taken at the end.
 */
function exchangeIsOpen(ev) {
  return compositionWasReal(ev) && ev.compositionstart > ev.compositionend;
}

/**
 * Did an exchange CLOSE across one edge? `before` and `after` are two
 * `compositionEvidence` readings taken either side of a SINGLE keystroke, so
 * one more `compositionend` after than before is that keystroke's own doing and
 * nothing else's. This is what pins a verdict to its transition instead of to
 * the sequence that contained it.
 */
function closedAcross(before, after) {
  return after.compositionend > before.compositionend;
}

const NO_COMPOSITION =
  'no composition ever started on this field, so nothing about the ' +
  'composition carve-out was exercised. The IME was not engaged for this ' +
  'window: the romaji went in as plain ASCII. This is a rig result, not a ' +
  'runtime result — do not read it as a pass or a defect.';

/**
 * The premise an aggregate reading could not tell apart from a met one: a
 * composition DID happen, but it was already closed when the edge arrived, so
 * the edge acted on nothing and the field's end state is not its doing.
 */
const closedBeforeEdge = (edge, ev) =>
  `a composition did happen on this field, but none was OPEN immediately ` +
  `before the ${edge}: ${ev.compositionstart} start(s) against ` +
  `${ev.compositionend} close(s) at that instant. The ${edge} therefore had ` +
  `no exchange to act on, and what the field shows afterwards is not ` +
  `evidence about it. This is a rig result, not a runtime result — do not ` +
  `read it as a pass or a defect.`;

/** The other half: an exchange was open at the edge and the edge did not close it. */
const edgeDidNotClose = (edge, before, after) =>
  `an exchange was open at the ${edge} and was STILL open after it ` +
  `(${before.compositionend} -> ${after.compositionend} close(s)): the ` +
  `${edge} never reached the composition — the IME most likely ate the key — ` +
  `so the reading after it is a mid-exchange reading rather than a ${edge} ` +
  `result. This is a rig result, not a runtime result — do not read it as a ` +
  `pass or a defect.`;

// ---------------------------------------------------------------------------
// The verdict functions. PURE, and unit-tested by the runner's teeth, because
// a witness whose verdict logic cannot be shown to fail is decoration.
// ---------------------------------------------------------------------------

const TICK = 'TICK';
const CROSS = 'CROSS';
const INCONCLUSIVE = 'INCONCLUSIVE';

/**
 * Check 5 — ESC abort mid-composition, and the resolution of the operator's
 * 2026-08-11 observation. `before` / `after` are `{ value, committed, edits }`
 * read either side of the Escape keystroke; `evBefore` / `evAfter` are the
 * event evidence read at those SAME two instants.
 *
 * Both pairs are needed and neither is sufficient. The cells say what the field
 * did; only the two event readings say that the ESC is what did it. A single
 * aggregate reading taken after the sequence proves a composition happened —
 * not that one was open when ESC arrived, and not that ESC closed it (#7896).
 * So this verdict is gated on three premises before it decides anything: a real
 * exchange, OPEN at the ESC, CLOSED across it.
 */
function abortVerdict({ before, after, evBefore, evAfter, seed }) {
  if (!compositionWasReal(evAfter)) {
    return {
      verdict: INCONCLUSIVE,
      why: `${NO_COMPOSITION} It is also the FIRST of the two readings of the ` +
        'operator observation of 2026-08-11 ("typed in plain, pressed ESC, ' +
        'nothing was discarded"): with no exchange open there was nothing to ' +
        'discard, and the draft staying is correct conduct.',
    };
  }
  if (!exchangeIsOpen(evBefore)) {
    return { verdict: INCONCLUSIVE, why: closedBeforeEdge('ESC', evBefore) };
  }
  if (!closedAcross(evBefore, evAfter)) {
    return { verdict: INCONCLUSIVE, why: edgeDidNotClose('ESC', evBefore, evAfter) };
  }
  if (after.edits < before.edits) {
    return {
      verdict: CROSS,
      why: `the intents-arrived count went DOWN across the abort ` +
        `(${before.edits} -> ${after.edits}). An abort discards a draft; it ` +
        'cannot un-deliver intents the model already took.',
    };
  }
  if (after.value === seed && after.committed === prStr(seed)) {
    return {
      verdict: TICK,
      why: `an exchange that was OPEN at the ESC (${evBefore.compositionstart} ` +
        `start(s), ${evBefore.composingInputs} composing input(s), ` +
        `${evBefore.compositionend} close(s)) closed ACROSS it ` +
        `(${evAfter.compositionend} close(s)), and the field returned to its ` +
        `committed value ${prStr(seed)} with no fragment left behind.`,
    };
  }
  if (after.value === before.value) {
    return {
      verdict: CROSS,
      why: 'REPRODUCES THE OPERATOR OBSERVATION OF 2026-08-11, and refutes the ' +
        'benign reading of it: a real composition was OPEN at the ESC ' +
        `(${evBefore.compositionstart} start(s), ${evBefore.composingInputs} ` +
        `composing input(s)) and the ESC CLOSED it ` +
        `(${evBefore.compositionend} -> ${evAfter.compositionend} close(s)) — ` +
        `yet it discarded nothing: the field still shows ` +
        `${JSON.stringify(after.value)}. File this as a bead with the engine ` +
        'name on it.',
    };
  }
  return {
    verdict: CROSS,
    why: `after the abort the field shows ${JSON.stringify(after.value)} and the ` +
      `trace reads ${after.committed}; the committed value is ${prStr(seed)}. ` +
      'A fragment of the abandoned composition survived.',
  };
}

/** Check 1 — the draft is on screen while the model refuses every character. */
function draftVisibleVerdict({ during, evidence, seed }) {
  if (!compositionWasReal(evidence)) return { verdict: INCONCLUSIVE, why: NO_COMPOSITION };
  if (during.value === seed) {
    return {
      verdict: CROSS,
      why: `the field shows its committed value ${prStr(seed)} while a ` +
        'composition is open — the refusing model was written over the live ' +
        'draft, which is the carve-out failing.',
    };
  }
  if (!during.value.startsWith(seed)) {
    return {
      verdict: CROSS,
      why: `the field shows ${JSON.stringify(during.value)}, which does not ` +
        `start with the committed ${prStr(seed)}; the draft did not compose ` +
        'onto the existing value.',
    };
  }
  return {
    verdict: TICK,
    why: `the draft is on screen (${JSON.stringify(during.value)}) while the ` +
      `trace still reads ${during.committed} — ${during.edits} intent(s) arrived ` +
      'and the model kept none of them.',
  };
}

/** Check 2 — the draft grows at the caret, not at the end of the string. */
function caretVerdict({ during, evidence, seed, caretAt }) {
  if (!compositionWasReal(evidence)) return { verdict: INCONCLUSIVE, why: NO_COMPOSITION };
  const head = seed.slice(0, caretAt);
  const tail = seed.slice(caretAt);
  if (!during.value.startsWith(head) || !during.value.endsWith(tail)) {
    return {
      verdict: CROSS,
      why: `composing at offset ${caretAt} of ${prStr(seed)} produced ` +
        `${JSON.stringify(during.value)}, which is not ${JSON.stringify(head)} + ` +
        `draft + ${JSON.stringify(tail)}. The end-of-string jump is exactly ` +
        'what this check exists to catch.',
    };
  }
  const draftEnd = during.value.length - tail.length;
  if (during.selectionStart < caretAt || during.selectionStart > draftEnd) {
    return {
      verdict: CROSS,
      why: `the draft landed at the caret but the caret left it: selectionStart ` +
        `is ${during.selectionStart}, outside [${caretAt}, ${draftEnd}].`,
    };
  }
  return {
    verdict: TICK,
    why: `the draft grew at offset ${caretAt} (${JSON.stringify(during.value)}) ` +
      `and the caret stayed inside it at ${during.selectionStart}.`,
  };
}

/**
 * Check 3 — the draft survives the model mid-composition. Two readings taken
 * during one exchange: the arrivals must CLIMB (every composing update
 * reached the store) while the committed value does not move and the draft
 * is not snapped back.
 */
function survivesModelVerdict({ early, late, evidence, seed }) {
  if (!compositionWasReal(evidence)) return { verdict: INCONCLUSIVE, why: NO_COMPOSITION };
  if (late.edits <= early.edits) {
    return {
      verdict: INCONCLUSIVE,
      why: `the intents-arrived count did not move between the two readings ` +
        `(${early.edits} -> ${late.edits}), so there was nothing for the model ` +
        'to survive. The exchange stalled rather than the law failing.',
    };
  }
  if (late.committed !== prStr(seed)) {
    return {
      verdict: CROSS,
      why: `the refusing model moved: the trace reads ${late.committed}, was ` +
        `${prStr(seed)}.`,
    };
  }
  if (late.value === seed) {
    return {
      verdict: CROSS,
      why: 'the field snapped back to the committed value while the exchange ' +
        `was still open (${early.edits} -> ${late.edits} arrivals). This is the ` +
        'write-over the carve-out exists to prevent.',
    };
  }
  return {
    verdict: TICK,
    why: `arrivals climbed ${early.edits} -> ${late.edits} while the trace held ` +
      `${late.committed} and the draft stayed on screen ` +
      `(${JSON.stringify(late.value)}).`,
  };
}

/** Check 4 — the commit echoes COMMITTED state, in the same turn. */
function commitEchoVerdict({ after, evidence, expected, policy }) {
  if (!compositionWasReal(evidence)) return { verdict: INCONCLUSIVE, why: NO_COMPOSITION };
  if (evidence.compositionend === 0) {
    return {
      verdict: INCONCLUSIVE,
      why: 'the composition never closed — no `compositionend` was seen, so the ' +
        'commit under test did not happen.',
    };
  }
  if (policy === 'refusing') {
    return after.value === expected && after.committed === prStr(expected)
      ? {
        verdict: TICK,
        why: `the refusing field snapped to its committed ${prStr(expected)} in ` +
            'the same turn the composition closed.',
      }
      : {
        verdict: CROSS,
        why: `after the commit the field shows ${JSON.stringify(after.value)} ` +
            `and the trace reads ${after.committed}; the model refused the ` +
            `composition, so both should read ${prStr(expected)}.`,
      };
  }
  if (after.committed !== prStr(after.value)) {
    return {
      verdict: CROSS,
      why: `the accepting field shows ${JSON.stringify(after.value)} but the ` +
        `trace reads ${after.committed} — the screen and the store disagree ` +
        'after the commit.',
    };
  }
  return {
    verdict: TICK,
    why: `the accepting field kept the committed composition ` +
      `(${JSON.stringify(after.value)}), and the trace agrees.`,
  };
}

/**
 * Check 6 — `compositionend` adds no intent of its own. The claim the
 * checklist makes is that arrivals rise once per composing keystroke and the
 * CLOSE adds none; the failure it names is a double-application. Both are
 * decided here, and they are decided separately, because an engine that
 * delivers one settling `input` at the close is a divergence to record with
 * its name on it rather than an implementation defect.
 *
 * The close under test is the ENTER'S, and saying so takes two evidence
 * readings. The Space that opens the candidate window can itself close an
 * exchange, and an aggregate `compositionend > 0` cannot tell that close from
 * the one this check is about — so a no-op Enter that ended nothing ticked on
 * the Space's transition (#7896). The premises are therefore the same three as
 * check 5, around the Enter: a real exchange, OPEN at the Enter, CLOSED across
 * it. Only then is the arrivals delta attributable to the close.
 */
function commitAddsNoIntentVerdict({ before, after, evBefore, evAfter }) {
  if (!compositionWasReal(evAfter)) return { verdict: INCONCLUSIVE, why: NO_COMPOSITION };
  if (!exchangeIsOpen(evBefore)) {
    return { verdict: INCONCLUSIVE, why: closedBeforeEdge('Enter', evBefore) };
  }
  if (!closedAcross(evBefore, evAfter)) {
    return { verdict: INCONCLUSIVE, why: edgeDidNotClose('Enter', evBefore, evAfter) };
  }
  const delta = after.edits - before.edits;
  const doubled = after.value.length > 0
    && after.value === after.value.slice(0, after.value.length / 2).repeat(2);
  if (doubled) {
    return {
      verdict: CROSS,
      why: `the committed text landed twice: the field reads ` +
        `${JSON.stringify(after.value)}. A double-application at the close is ` +
        'the failure this check names.',
    };
  }
  if (delta === 0) {
    return {
      verdict: TICK,
      why: `the Enter closed an exchange that was open at it ` +
        `(${evBefore.compositionend} -> ${evAfter.compositionend} close(s)) and ` +
        `arrivals were unchanged across that close (${after.edits}); the commit ` +
        'converged the field without dispatching an intent of its own.',
    };
  }
  return {
    verdict: CROSS,
    why: `the close added ${delta} intent(s) (${before.edits} -> ${after.edits}). ` +
      'Record the engine: a settling `input` at `compositionend` is an engine ' +
      'divergence, and it is what this check exists to surface.',
  };
}

/**
 * Check 7 — a revision arriving MID-composition defers to the exchange's
 * close. Read on `revision-strict`, whose model REFUSES the kana, so the
 * reset has `42` to write while the field shows `42…` — the only arrangement
 * in which "the draft stood" distinguishes a deferral from a reset that had
 * nothing different to write (#7815/#7817 audits).
 */
function revisionDefersVerdict({ duringDraft, afterFire, evidence, seed, armedFired }) {
  if (!compositionWasReal(evidence)) return { verdict: INCONCLUSIVE, why: NO_COMPOSITION };
  if (!armedFired) {
    return {
      verdict: INCONCLUSIVE,
      why: 'the armed revision bump never fired inside the composition window, ' +
        'so the mid-exchange edge was not reached.',
    };
  }
  if (afterFire.value === seed) {
    return {
      verdict: CROSS,
      why: `the reset landed IMMEDIATELY: the field went back to ${prStr(seed)} ` +
        `under a live composition (it was showing ${JSON.stringify(duringDraft.value)}). ` +
        'The deferral to the close is what HD-019 requires.',
    };
  }
  if (afterFire.value !== duringDraft.value) {
    return {
      verdict: CROSS,
      why: `the draft changed under the reset: ${JSON.stringify(duringDraft.value)} ` +
        `-> ${JSON.stringify(afterFire.value)}.`,
    };
  }
  return {
    verdict: TICK,
    why: `the revision bump fired mid-exchange and the draft stood ` +
      `(${JSON.stringify(afterFire.value)}) while the model still held ` +
      `${prStr(seed)} — a reset that wrote immediately would have been visible.`,
  };
}

/**
 * Check 8 — blur and unmount mid-composition strand nothing and throw nothing.
 *
 * TWO PHASES, TWO PIECES OF EVIDENCE. The check blurs a live composition, then
 * RELOADS and composes again before the armed unmount fires, so the second
 * teardown is a different exchange on a different page instance. Passing only
 * the blur phase's evidence made the unmount half unfalsifiable: real evidence
 * from the blur, a fired unmount and NO composition at all in the second phase
 * returned a tick (#7896). So `evBlur` and `evUnmount` are separate parameters,
 * and the unmount half must show its own exchange, live when the node went.
 */
function teardownVerdict({ afterBlur, afterUnmount, evBlur, evUnmount, seed, pageErrors }) {
  if (!compositionWasReal(evBlur)) {
    return { verdict: INCONCLUSIVE, why: `BLUR PHASE: ${NO_COMPOSITION}` };
  }
  if (pageErrors.length > 0) {
    return {
      verdict: CROSS,
      why: `the page threw during teardown: ${pageErrors[0]}`,
    };
  }
  if (afterBlur.value !== seed) {
    return {
      verdict: CROSS,
      why: `after blurring a live composition the field shows ` +
        `${JSON.stringify(afterBlur.value)}; its refusing model holds ` +
        `${prStr(seed)}, so a draft was stranded.`,
    };
  }
  if (!exchangeIsOpen(evUnmount)) {
    return {
      verdict: INCONCLUSIVE,
      why: 'UNMOUNT PHASE: no exchange was open on the node when the armed ' +
        `unmount fired (${evUnmount.compositionstart} start(s), ` +
        `${evUnmount.compositionend} close(s), ${evUnmount.composingInputs} ` +
        'composing input(s) in that phase). The blur phase happened on a page ' +
        'this one reloaded away, so its evidence says nothing here: a node ' +
        'removed with no live composition under it is not the mid-composition ' +
        'teardown this check claims. This is a rig result, not a runtime ' +
        'result — do not read it as a pass or a defect.',
    };
  }
  if (!afterUnmount.gone) {
    return {
      verdict: INCONCLUSIVE,
      why: 'the armed unmount never fired inside the composition window, so the ' +
        'mid-exchange teardown was not reached.',
    };
  }
  return {
    verdict: TICK,
    why: 'blur returned the field to its committed value, and a SECOND exchange ' +
      `open on the node (${evUnmount.compositionstart} start(s), ` +
      `${evUnmount.composingInputs} composing input(s), ` +
      `${evUnmount.compositionend} close(s)) was still live when the armed ` +
      'unmount removed it — without stranding a draft or throwing.',
  };
}

// ---------------------------------------------------------------------------
// The roster. Check 5 is FIRST: it is the priority case of the ruling, the one
// signature no page script can produce, and the one carrying an unresolved
// operator observation.
// ---------------------------------------------------------------------------

const CHECKS = [
  {
    n: 5,
    id: 'escape-abort-mid-composition',
    title: 'Escape / abort mid-composition',
    field: 'plain',
    priority: true,
    plan: [...KEY_PLANS.compose, ...KEY_PLANS.abort],
    resolves: 'the operator observation of 2026-08-11',
  },
  {
    n: 1,
    id: 'draft-visible-during-composition',
    title: 'Draft text visible during composition',
    field: 'digits',
    plan: [...KEY_PLANS.compose, ...KEY_PLANS.abort],
  },
  {
    n: 2,
    id: 'caret-correct',
    title: 'Caret correct',
    field: 'upper',
    plan: [...KEY_PLANS.compose, ...KEY_PLANS.abort],
  },
  {
    n: 3,
    id: 'draft-survives-the-model',
    title: 'The draft survives the model, mid-composition',
    field: 'digits',
    plan: [...KEY_PLANS.composeShort, ...KEY_PLANS.composeRest, ...KEY_PLANS.abort],
  },
  {
    n: 4,
    id: 'commit-echo',
    title: 'Commit echo',
    field: 'digits + plain',
    plan: [...KEY_PLANS.compose, ...KEY_PLANS.selectCandidate, ...KEY_PLANS.commit],
  },
  {
    n: 6,
    id: 'compositionend-adds-no-intent',
    title: '`compositionend` adds no intent of its own',
    field: 'plain',
    plan: [...KEY_PLANS.compose, ...KEY_PLANS.selectCandidate, ...KEY_PLANS.commit],
  },
  {
    n: 7,
    id: 'revision-reset-mid-composition',
    title: 'Revision reset during composition (`revision-strict`)',
    field: 'revision-strict',
    plan: [...KEY_PLANS.compose, ...KEY_PLANS.abort],
  },
  {
    n: 8,
    id: 'blur-and-unmount-mid-composition',
    title: 'Blur / unmount mid-composition',
    field: 'mountable',
    plan: [...KEY_PLANS.compose, ...KEY_PLANS.compose],
  },
];

// ---------------------------------------------------------------------------
// The summary, and the one thing it must never say
// ---------------------------------------------------------------------------

/**
 * Fold a run's per-check verdicts into a report. FAIL-CLOSED in three ways,
 * each of which was a way an earlier gate on this bead read green having
 * measured nothing: a check that did not run is not a check that passed; an
 * INCONCLUSIVE run is not a complete one; and `complete` is false unless the
 * roster is covered exactly.
 */
function summarise(results, roster = CHECKS) {
  const byId = new Map(results.map((r) => [r.id, r]));
  const missing = roster.filter((c) => !byId.has(c.id)).map((c) => c.id);
  const extra = results.filter((r) => !roster.some((c) => c.id === r.id)).map((r) => r.id);
  const ticks = results.filter((r) => r.verdict === TICK).length;
  const crosses = results.filter((r) => r.verdict === CROSS);
  const inconclusive = results.filter((r) => r.verdict === INCONCLUSIVE);
  return {
    ticks,
    crosses: crosses.map((r) => r.id),
    inconclusive: inconclusive.map((r) => r.id),
    missing,
    extra,
    complete: missing.length === 0 && extra.length === 0 && inconclusive.length === 0
      && results.length === roster.length,
  };
}

/**
 * The cell text for `dispositions.md` §2.3. It may say WITNESS-VERIFIED only
 * when every check on the roster reached a definite verdict AND none of them
 * crossed — the recording must not launder an incomplete or a crossed run
 * into a green cell, which is the whole failure mode the audits on this bead
 * kept finding.
 */
function dispositionCell(summary, { engine, build, date }) {
  const where = `${engine} \`${build}\`, ${date}`;
  if (!summary.complete) {
    const reason = summary.missing.length
      ? `checks did not run: ${summary.missing.join(', ')}`
      : `inconclusive: ${summary.inconclusive.join(', ')}`;
    return `**Not established** — scripted witness run ${where} did not decide ` +
      `every check (${reason}). The cell stays unfilled.`;
  }
  if (summary.crosses.length > 0) {
    return `**Divergence** — scripted witness run ${where}: ${summary.ticks} of ` +
      `${summary.ticks + summary.crosses.length} checks held; ` +
      `${summary.crosses.join(', ')} crossed (bead filed).`;
  }
  return `**Witness-verified** — scripted native-IME witness, all ` +
    `${summary.ticks} checks, ${where}.`;
}

module.exports = {
  CHECKS,
  KEY_PLANS,
  SEEDS,
  TRACED_FIELDS,
  TICK,
  CROSS,
  INCONCLUSIVE,
  abortVerdict,
  caretVerdict,
  commitAddsNoIntentVerdict,
  commitEchoVerdict,
  closedAcross,
  compositionEvidence,
  compositionWasReal,
  dispositionCell,
  draftVisibleVerdict,
  exchangeIsOpen,
  pageObserver,
  prStr,
  readEvents,
  readField,
  readTrace,
  resetEvents,
  revisionDefersVerdict,
  summarise,
  survivesModelVerdict,
  teardownVerdict,
};
