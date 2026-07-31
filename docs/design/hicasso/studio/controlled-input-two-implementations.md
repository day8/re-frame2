# A controlled input has two implementations, and the bundle was picking

**Bead** `rf2-n3dxw` · **epic** `rf2-2rtt6` (EP-0038)
**Witness content hash** `966e6d8390ecc9945193417bb221b6c574c9681f`
(`implementation/freehand/test/re_frame/bench/hicasso/controlled_restore_dom_cljs_test.cljs`;
authored on `worker/reject-n3dxw`). A SHA does not survive a rebase and this
branch was rebased once already — the content hash is the identifier, and
`git log --oneline --all -- <path>` plus `git rev-parse <candidate>:<path>`
finds a commit carrying the blob.
**Measured** 2026-08-01 AUSEST

**Runtime for every row on this page**: headless Chromium via Playwright
1.59.1, shadow-cljs `:browser-test` (development optimisations, `goog.DEBUG`
true), react/react-dom **19.2.0**, uix.core **1.4.4**. These are correctness
rows, not bar rows — no figure here is quotable against the performance bar.

Reproduce:

```bash
cd implementation
npx shadow-cljs compile browser-test \
  --config-merge '{:ns-regexp "controlled-restore-dom-cljs-test$"}'
node scripts/serve-and-run-browser-tests.cjs
```

## The headline

`rf2-n3dxw` recorded a P1 against the React path: a keystroke the model
refuses stays on the screen, so the field and the model disagree
permanently. **React does not lose the refused character.** It takes it off
inside the discrete event, with nothing re-rendered. What the bead measured
was a second implementation that the test bundle had silently selected.

## The selector

UIx decides, per element and at element-creation time, between plain React
and a port of Reagent's controlled-input workaround.
`uix.compiler.aot/create-uix-input` branches on
`uix.compiler.input/should-use-reagent-input?`, and that predicate — with
`*use-reagent-input-enabled?*` unset, which is the shipped default — answers

> is `reagent.impl.util/*non-reactive*` present, and false?

So the answer is a fact about **what else is on the classpath**, not a
decision the application makes. Every `:browser-test` bundle in this repo
carries Reagent, because the Reagent adapter is first-class and ships with
tests. A UIx-only consumer app gets the other implementation.

The probe that found it read the props React had committed to the DOM node.
Where the view had written `:value`, the node carried `defaultValue "12"` and
a `ref` whose source was `function (el){ return (this$.inputEl = el); }` —
`uix/compiler/input.cljs:110-127`.

## What each implementation does

### `:react` — the element stays controlled

React's own end-of-discrete-event state restore fires, and it fires even when
nothing re-rendered. In `react-dom@19.2.0`,
`cjs/react-dom-client.development.js`:

| line | what happens |
|---|---|
| `3512-3523` | `createAndAccumulateChangeEvent` records the event target for restoration |
| `3251-3272` | the `finally` of `batchedUpdates$1` flushes sync work, then calls `restoreStateOfTarget` |
| `3178-3196` | `restoreStateOfTarget` hands the props React last committed to `updateInput` |
| `1661-1667` | `updateInput` assigns `element.value` whenever it differs from the prop |

All of that runs before `dispatchEvent` returns. The witness asserts zero
boundary body runs on the same keystroke, so the write cannot have come from
a render.

What React does **not** do is put the caret back. Assigning `value` moves the
text entry cursor to the end of the control (HTML standard, the `value` IDL
attribute setter), and React restores a selection only around a commit in
which focus *moved*. Every write React makes therefore throws the caret to
the end of the field.

### `:uix-reagent-input` — the element is made uncontrolled

`value` is deleted from the props, `defaultValue` is set and a `ref` is
installed (`uix/compiler/input.cljs:124-127`). React's restore now has
nothing to restore — `updateInput` skips the write entirely when the `value`
prop is absent. UIx drives the value itself, and schedules that work on
`reagent.impl.batching/do-after-render`, a queue drained from
`requestAnimationFrame` (`reagent/impl/batching.cljs:16-25`, `:57-59`).
Value and caret both come back correct, by offset from the end of the
string — Arm 2's algorithm, and Reagent's before it — but **one animation
frame later, never inside the discrete event.**

## The matrix

Field value and caret, read on the line after `dispatchEvent` returns
("in-turn") and again after one animation frame ("settled"). The model is
Arm 2's 100-cell grid, unchanged: cell 11 refuses non-digits, cell 13
uppercases, cell 17 regroups, cell 7 takes what it is given.

| row | `:react` in-turn | `:react` settled | `:uix-reagent-input` in-turn | `:uix-reagent-input` settled |
|---|---|---|---|---|
| refusal at the end — `"12"` + `a` at `[2 2]` | `"12"` `[2 2]` ✅ | unchanged | `"12a"` `[3 3]` ❌ | `"12"` `[2 2]` ✅ |
| refusal mid-string — `"12345"` + `z` at `[2 2]` | `"12345"` **`[5 5]`** ⚠️ | unchanged | `"12z345"` `[3 3]` ❌ | `"12345"` `[2 2]` ✅ |
| uppercasing mid-string — `"ABCD"` + `x` at `[2 2]` | `"ABXCD"` **`[5 5]`** ⚠️ | unchanged | `"ABXCD"` `[3 3]` ✅ | `[3 3]` ✅ |
| regrouping at the end — `"1,234"` + `5` at `[5 5]` | `"12,345"` `[6 6]` ✅ | unchanged | `[6 6]` ✅ | `[6 6]` ✅ |
| accepted keystroke mid-string — `"abcd"` + `X` at `[2 2]` | `"abXcd"` `[3 3]` ✅ | unchanged | `[3 3]` ✅ | `[3 3]` ✅ |
| a range `[1 4]` across a converge that WRITES | `"Xabcdef"` `[7 7]` | unchanged | `[2 2]` | unchanged |

Read the table as one sentence: **React converges in the same turn and puts
the caret in the wrong place; UIx's port puts the caret in the right place
one frame late.** Neither gives both.

The regrouping row is green on React only because the edit was at the *end*
of the field, which is where React's write leaves the caret anyway. It is not
evidence that React preserves a caret across a length change.

### The range row

Arm 2 restored both ends of a selection by distance from the end of the
string and required `[2 5]`. Neither shipped implementation does, and neither
is close: React resets the cursor to the end of the value it wrote, and the
port writes one offset into both `selectionStart` and `selectionEnd`
(`uix/compiler/input.cljs:68-69`), so a range collapses **by construction**.
`[2 2]`, the value the bead recorded, is the port's — correct as a cursor,
never a range. This is not a defect to be fixed in passing; restoring a range
means restoring two offsets, which is a different algorithm from the one both
implementations run.

### IME composition

Still not established, and still not asserted. Synthetic `Event`s named
`compositionstart`/`compositionend` do not exercise React's composition path,
and there are now **two** implementations to establish a fence against rather
than one — React's `SyntheticCompositionEvent` plumbing on the controlled
path, and the port's write-owning path, which never consults composition
state at all. A fence asserted without being demonstrated is worse than no
fence. It needs a real `CompositionEvent` harness against both.

## The options, and what they cost

### A — do nothing

The refused character already comes off the screen in the same turn on the
React path, for free. **Gap**: the caret is thrown to the end of the field on
every mid-string refusal, and on every keystroke a normalising model rewrites.
That is React's own long-standing controlled-input caret jump, not something
re-frame2 introduced.

### B — converge at the end of the change handler

Measured, green, and committed as
`a-same-turn-converge-can-have-both-halves-at-a-stated-price`. At the end of
the change handler, still inside the discrete event and still ahead of
React's own restore:

1. `flushSync` so the synchronous door's commit lands now rather than in the
   `finally` of `batchedUpdates$1`;
2. if the field still disagrees with what the element renders, write the
   rendered value — which also makes React's later `updateInput` a no-op,
   because it only assigns when the two differ;
3. put the caret back by offset from the end of the string.

Every row of the family, in one turn, including the mid-string refusal that
neither shipped path gets right.

**Price, stated rather than hidden.** One `flushSync` per keystroke on a
controlled element. A per-instance record of the value that element last
rendered — the handler's own closure carries the value from the render that
*minted* it, which is stale after a re-render, and `(= (.-value node)
dom-value)` alone cannot tell a refusal from a keystroke the model took
verbatim. The synchronous door only: `dispatch` drains on a macrotask, so at
the end of the handler the model has not moved and there is nothing to
converge to.

**What it does not cost.** No user-visible ceremony — the view still writes
an ordinary `:value`/`:on-change` pair, with no ref, no effect and no escape
hatch. No hook in the boundary shell, so the ≤2-hook budget (HD-020) is
untouched. UIx's own answer to the same problem costs a wrapper *component*
per input with three hooks in it (`uix/compiler/input.cljs:132-143`); this
one costs none, because it lives in the element path rather than in a
component.

Where it would live is the open question: neither Hicasso nor the shipped
UIx adapter mints its own `:input` element today — UIx does — so the
prototype sits in the witness's view, labelled as a measurement rather than
an authoring pattern.

### C — pin the selector

Independent of A and B, and cheap: decide which implementation a re-frame2
UIx app gets, instead of inheriting whatever the bundle implies. One `set!`
of `uix.compiler.input/*use-reagent-input-enabled?*` at adapter load. The
cost is not implementation, it is the ruling — the two implementations have
materially different behaviour, and today a consumer's choice of a *second*
adapter silently changes the first one's inputs.

## What this changes in the record

- `architecture.md`'s "React owns ... the controlled-input end-of-event
  restore" is **confirmed**, with the qualification that it holds only while
  the element is actually controlled, which UIx does not guarantee.
- HD-019's "rejected/unchanged-model paths lean on React's own end-of-event
  restore" is **confirmed for the value and refuted for the caret**.
- `rf2-n3dxw` stays open on the residue: no shipped path gives both halves.
