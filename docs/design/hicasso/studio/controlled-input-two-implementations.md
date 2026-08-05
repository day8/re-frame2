# A controlled input has two implementations, and the bundle was picking

> **Amended 2026-08-02 (`rf2-fki5d`).** Option B below is **TAKEN** and
> shipped in Arm 1's element path, so there is now a third behaviour on this
> page and the matrix has a column for it. Nothing measured here moved: the
> two shipped implementations behave exactly as the rows below record, and
> the arm's converge is a third thing sitting on top of React's. The one
> figure that changed is the price — see
> [the record turned out to be React's own](#the-record-turned-out-to-be-reacts-own).

**Bead** `rf2-n3dxw` · **epic** `rf2-2rtt6` (EP-0038)
**Witness content hash** `d747b10d82daa24ce39a4a7a6cff825ce7716483`
(`implementation/freehand/test/re_frame/bench/hicasso/controlled_restore_dom_cljs_test.cljs`;
authored on `worker/reject-n3dxw`, amended on `worker/heqwo-default` when
`rf2-heqwo` pinned the selector — the measured rows are unchanged, the
restore-to-default helper is not; the previous blob was
`966e6d8390ecc9945193417bb221b6c574c9681f`). A SHA does not survive a rebase
and this branch was rebased once already — the content hash is the
identifier, and `git log --oneline --all -- <path>` plus
`git rev-parse <candidate>:<path>` finds a commit carrying the blob.
**Measured** 2026-08-01 AUSEST

**The arm's own witnesses** (`rf2-fki5d`, measured 2026-08-02 AUSEST, same
runtime): `22e7e6f456fd1ac9a1628fd985588ff87e68532d`
(`…/hicasso/arm1/controlled_grid_dom_cljs_test.cljs`) and
`176442a54e1be2ea346e7cfee460f4583bf10233`
(`…/hicasso/front/controlled_dom_cljs_test.cljs`). The mechanism is
`e625d031cb251614899015894bccc2703052ddfd`
(`…/hicasso/front/controlled.cljs`).

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
`*use-reagent-input-enabled?*` unset, which was the shipped default when this
page was measured — answers

> is `reagent.impl.util/*non-reactive*` present, and false?

So the answer was a fact about **what else is on the classpath**, not a
decision the application makes. Every `:browser-test` bundle in this repo
carries Reagent, because the Reagent adapter is first-class and ships with
tests. A UIx-only consumer app got the other implementation.

**This is the part that has since been fixed** — see [Option C](#c--pin-the-selector)
below. `re-frame.adapter.uix` now pins the var at load, so a re-frame2 UIx app
gets React's implementation whatever else is in the bundle. Every row below
still measures what it says it measures: each one names its implementation and
pins it explicitly, which is why the ruling did not move a single figure.

The probe that found it read the props React had committed to the DOM node.
Where the view had written `:value`, the node carried `defaultValue "12"` and
a `ref` whose source was `function (el){ return (this$.inputEl = el); }` —
`uix/compiler/input.cljs`'s `input-render-setup`.

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
installed (`uix/compiler/input.cljs`'s `input-render-setup`). React's restore now has
nothing to restore — `updateInput` skips the write entirely when the `value`
prop is absent. UIx drives the value itself, and schedules that work on
`reagent.impl.batching/do-after-render`, a queue drained from
`requestAnimationFrame` (`reagent/impl/batching.cljs`'s `next-tick`, drained by
`run-funs`).
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
one frame late.** Neither *shipped implementation* gives both.

The regrouping row is green on React only because the edit was at the *end*
of the field, which is where React's write leaves the caret anyway. It is not
evidence that React preserves a caret across a length change.

### The third column — Arm 1's element path

**Taken 2026-08-02 (`rf2-fki5d`).** The same five rows, same model, same
keystrokes, on Arm 1's own grid
(`arm1_controlled_grid_dom_cljs_test/the-family-converges-in-one-turn-with-the-caret-where-the-edit-left-it`),
every reading taken on the line after `dispatchEvent` returns:

| row | `:react` in-turn | Arm 1 in-turn |
|---|---|---|
| refusal at the end — `"12"` + `a` at `[2 2]` | `"12"` `[2 2]` ✅ | `"12"` `[2 2]` ✅ |
| refusal mid-string — `"12345"` + `z` at `[2 2]` | `"12345"` **`[5 5]`** ⚠️ | `"12345"` **`[2 2]`** ✅ |
| uppercasing mid-string — `"ABCD"` + `x` at `[2 2]` | `"ABXCD"` **`[5 5]`** ⚠️ | `"ABXCD"` **`[3 3]`** ✅ |
| regrouping at the end — `"1,234"` + `5` at `[5 5]` | `"12,345"` `[6 6]` ✅ | `"12,345"` `[6 6]` ✅ |
| accepted keystroke mid-string — `"abcd"` + `X` at `[2 2]` | `"abXcd"` `[3 3]` ✅ | `"abXcd"` `[3 3]` ✅ |

There is no "settled" column because there is nothing to settle: every row
above is final when the discrete event returns, and no later frame moves it.

**Mutation-proved, twice.** Removing the one call in
`front.codec/native-element` that installs the converge reds 14 assertions,
and every caret reading falls back to `[5 5]` with the refused character
still on the screen — React's own behaviour, exactly as the matrix records
it. Removing only the `flushSync` reds 4, all of them caret readings at
`[5 5]`, including the **ordinary accepted keystroke**: without it the
record is one render stale, the converge writes the wrong value, and React's
restore repairs the value and throws the caret away. Both were restored and
the suite is green at 59 tests / 303 assertions.

### The range row

Arm 2 restored both ends of a selection by distance from the end of the
string and required `[2 5]`. Neither shipped implementation does, and neither
is close: React resets the cursor to the end of the value it wrote, and the
port writes one offset into both `selectionStart` and `selectionEnd`
(`uix/compiler/input.cljs`'s `input-node-set-value`), so a range collapses **by
construction**.
`[2 2]`, the value the bead recorded, is the port's — correct as a cursor,
never a range. This is not a defect to be fixed in passing; restoring a range
means restoring two offsets, which is a different algorithm from the one both
implementations run.

### IME composition

**Established** (`rf2-o27h3`), by a harness that drives the browser's own
composition machinery rather than dispatching Events shaped like it:
`ime_run.cjs` (beside the bench drivers, in
`implementation/freehand/test/re_frame/bench/hicasso/`) uses CDP
`Input.imeSetComposition` / `Input.insertText` / `Input.dispatchKeyEvent`,
which mint **trusted** `compositionstart`/`compositionupdate` events, trusted
`input` events carrying `insertCompositionText` and `isComposing true`, real
mid-composition keydowns, and a real composition range — against **three**
pages, one implementation each: plain React, the port, and Arm 1's element
path with the converge installed. What it holds and what it measured:

- **The commit fence holds, on each signal independently, on all three.** A
  mid-composition Enter (native `isComposing true`) commits nothing; a bare
  keyCode-229 Enter commits nothing; an ordinary Enter commits. The gate
  reads the **native** event — React's synthetic keyboard event drops
  `isComposing` (measured at the handler, again), and the harness's
  mutation run (the guard re-pointed at the synthetic event) reds exactly
  the modern-signal row on all three pages while the 229 row stays green.
- **A model-agreeing exchange survives to `compositionend`** on all three —
  one `compositionstart`, the composition string forming through the
  updates, commit data delivered. The converge's `flushSync` and its
  unconditional `setSelectionRange` did not disturb the composition range.
- **The model observes every intermediate composition state** on all three:
  the change handler fires per composing `input`, so `s`/`sh`/`し` each
  reach app-db. The fence is the commit door, not the value path.
- **A refused or normalised value was written back mid-composition on all
  three, and the write silently destroys the exchange** — no
  `compositionend`, fresh `compositionstart` on the IME's next update.
  React in-turn, the converge in-turn, the port one frame late. That was
  the finding (`rf2-digtt`), and the converge was nowhere worse than the
  React baseline; but the PR #7371 audit's pin — that it neither writes
  nor moves selection mid-composition — was false for every
  implementation the moment the model disagreed. **The operator ruled the
  carve-out IN on 2026-08-03, and the section below is what the same
  harness measures now.**

### The composition carve-out, and where it diverges from React

**Taken 2026-08-03 (`rf2-digtt`, HD-019's dated addendum).** Controlled-text
convergence is suppressed while a composition is live; the field converges
once, at `compositionend`, against the then-current model. The re-run of
`ime_run.cjs` is two conducts rather than one, and the difference is the
claim — measured in a single run, on one model, in one browser:

| after one composing input on a model-**refusing** field (`digits`, model `"12"`, composing `し`) | Arm 1 | plain React | UIx port |
|---|---|---|---|
| field, in-turn | `"12し"` | `"12"` | `"12し"` |
| field, settled | `"12し"` | `"12"` | `"12"` |
| `compositionstart` across the exchange | **1** | 2 | 2 |
| `compositionend` delivered | **1** | 0 | 0 |
| field once the exchange closes | `"12"` | `"12"` | `"12"` |
| model, throughout | `"12"` | `"12"` | `"12"` |

Read it as one sentence: **the refusal is identical; what differs is that the
user's composition survives to reach it.** Arm 1 writes nothing while the
composition runs — neither its own converge, suppressed by one reading of the
native event's `isComposing`, nor React's end-of-event restore, which finds
the controlled value already agreeing with the live draft and assigns
nothing. The refusal then lands whole and visibly at the commit.

On the **normalising** field (`upper`) the divergence is larger than
"survives versus does not", and the harness pins it:

| `upper`, composing `s` then `sh`, then committing | Arm 1 | plain React |
|---|---|---|
| field while composing | `"s"`, `"sh"` (the draft) | `"S"`, `"S"` |
| model while composing | `"S"`, `"SH"` | `"S"`, `"SH"` |
| model once the exchange closes | **`"SH"`** | **`"SSHSH"`** |

The baseline does not merely lose the composition: each aborted draft is
written back into the field, and the IME's next composition composes *on top
of it* — `s` → `S`, `sh` on top of `S` → `SSH`, the commit on top of that →
`SSHSH`. Arm 1, having written nothing until the end, commits the `SH` that
was typed. That row is a `comparative:` check in `ime_run.cjs`, not prose
here.

- A cancelled exchange (`compositionend` with empty data) leaves field and
  model exactly as before, on all three. The refusing field is where the two
  conducts part again: on Arm 1 it cancels like any other field, because
  there is a live composition left to cancel; on React and the port there is
  no `compositionend` at all, because the abort already happened.
- **What is in-page, and what needs the harness.** A composition is browser
  machinery and nothing dispatched from page script creates one, so the
  *exchange* is `ime_run.cjs`'s claim alone. What the PR-gated suite witnesses
  is the **write** — the cause — in
  `arm1_controlled_grid_dom_cljs_test` §7: on a composing `input` event the
  arm's field is untouched in-turn, while plain React's cell on the same
  model in the same turn already shows the refused-to value. The
  safety-rider paths (blur, a non-composing keystroke, unmount) are witnessed
  there too.

> **Witness scope: Chromium only.** `Input.imeSetComposition` is a CDP method
> and CDP is Chromium's protocol, so every composition measurement on this page
> — before and after the carve-out — is Chromium's and nowhere else's. WebKit
> has had composition/key-ordering defects of its own; driving it would need a
> different instrument, which is not built and is not being mandated here.
> Misconduct observed on WebKit is a new bug rather than a known limitation of
> this record.

## The options, and what they cost

### A — do nothing

The refused character already comes off the screen in the same turn on the
React path, for free. **Gap**: the caret is thrown to the end of the field on
every mid-string refusal, and on every keystroke a normalising model rewrites.
That is React's own long-standing controlled-input caret jump, not something
re-frame2 introduced.

### B — converge at the end of the change handler

**TAKEN** (`rf2-fki5d`, 2026-08-02). Prototyped as
`a-same-turn-converge-can-have-both-halves-at-a-stated-price` and now
shipped in Arm 1's element path:
`front.codec/native-element` calls `front.controlled/install!`, which wraps
the change handler the author already wrote. At the end of that handler,
still inside the discrete event and still ahead of React's own restore:

1. `flushSync` so the synchronous door's commit lands now rather than in the
   `finally` of `batchedUpdates$1`;
2. if the field still disagrees with what the element renders, write the
   rendered value — which also makes React's later `updateInput` a no-op,
   because it only assigns when the two differ;
3. put the caret back by offset from the end of the string.

Every row of the family, in one turn, including the mid-string refusal that
neither shipped path gets right.

**Price, stated rather than hidden.** One `flushSync` per keystroke on a
controlled element, and the synchronous door only: `dispatch` drains on a
macrotask, so at the end of the handler the model has not moved and there is
nothing to converge to. A queued field converges one macrotask later, as it
always did.

**What it does not cost.** No user-visible ceremony — the view still writes
an ordinary `:value`/`:on-change` pair, with no ref, no effect and no escape
hatch. No hook in the boundary shell, so the ≤2-hook budget (HD-020) is
untouched, and `arm1_hook_ledger_dom_cljs_test` reads the same ledger it did
before. UIx's own answer to the same problem costs a wrapper *component* per
input with three hooks in it (`uix/compiler/input.cljs`'s `reagent-input`); this one
costs none, because it lives in the element path rather than in a component.
`grid.cljs` — the 100-cell witness's view — did not change by a character.

#### The record turned out to be React's own

The third item on the quoted price was **a per-instance record of the value
that element last rendered**, and it is not charged. The handler's own
closure cannot serve: it carries the value from the render that *minted* it,
which is one behind the moment step 1 commits a new one, and writing it back
**deletes a keystroke the model took verbatim** — reproduced in
`front/controlled_dom_cljs_test/the-closure-value-wipes-a-keystroke-the-model-took-verbatim`,
which calls the same function twice with one argument different. Comparing
the field against what the handler saw cannot serve either: `(= (.-value
node) dom-value)` reads true on a refusal *and* on a keystroke taken
verbatim — the same reading, two opposite obligations.

What does serve is `node.defaultValue`, and **React already maintains it**.
On any genuinely controlled input or textarea, every commit mirrors the
committed `value` prop into the element's default value —
`updateInput:1671-1672` → `setDefaultValue:1737-1741`, `initInput:1721` on
mount, `updateTextarea:1842-1851` for the other tag. It is the element's own
bookkeeping, per instance, on the node: no `ref`, no `WeakMap`, no extra
prop, nothing to keep in step. Typing does not disturb it, because the
`value` IDL setter sets the value and the dirty flag and never the content
attribute `defaultValue` reflects.

That leaves a **dependency** rather than a cost, and it is named in one
place. `front.controlled/last-rendered` is the only reader, every guard in
`install!` is a condition under which React's mirror really is the rendered
value — `input`/`textarea` only, a non-nil `value`, no author `defaultValue`
(a `<textarea>` honours that one over the mirror), and a type with a text
cursor (`setDefaultValue` deliberately skips a focused `number` field, and
`setSelectionRange` does not apply to it either — the same exclusion twice)
— and
`arm1_controlled_grid_dom_cljs_test/the-record-is-reacts-own-mirror-and-is-not-the-handlers-closure`
asserts the invariant against a live React tree, including that it *moves*
when the rendered value moves. If React ever stops mirroring, that row goes
red by name instead of five caret rows going quietly wrong.

#### What went to the decisions record

HD-019 used to say `flushSync` was "the evidence-gated last resort, never the
default", and this makes it the default for every controlled text element. The
evidence gate is met — the mutation probe above shows the ordinary accepted
keystroke regressing without it, not merely the refusal — so the tension raised
here was resolved by amending the record rather than the code (`rf2-ncn5p`).
HD-019 now reads that `flushSync` is never the *general* default and that this
converge is its single evidence-gated exception, scoped to the one audited call
site in the element path and to controlled text entry. Anywhere else the old
blanket binds unchanged: a second call site needs fresh evidence and its own
ruling.

### C — pin the selector

**TAKEN** (`rf2-heqwo`; Mike, 2026-08-01: *"make the React path the
default"*). Independent of A and B, and cheap: decide which implementation a
re-frame2 UIx app gets, instead of inheriting whatever the bundle implies.
One `set!` of `uix.compiler.input/*use-reagent-input-enabled?*` to `false` at
`re-frame.adapter.uix` load. The cost was never the implementation, it was
the ruling — the two implementations have materially different behaviour, and
a consumer's choice of a *second* adapter silently changed the first one's
inputs.

The ruling takes in-turn convergence and a React-native, predictable path
over late caret preservation, and more importantly takes determinism over a
silent classpath dependency: an app that behaves differently because of what
else is in its bundle is the worse defect. The var stays public and dynamic,
so the port remains reachable by an explicit `set!` — what is gone is getting
either one by accident. The consumer-facing statement of the trade-off,
including the caret, lives in `docs/api/re-frame.adapter.uix.md`.

What this does **not** settle: the mid-string refusal row above is still red
on the caret **for a UIx consumer**, because React is now the path every UIx
consumer is on. Pinning the selector made the default honest; it did not give
either implementation the half it lacks. Option B closed it for Arm 1, in the
element path — a UIx consumer is not on that path and does not get it.

## What this changes in the record

- `architecture.md`'s "React owns ... the controlled-input end-of-event
  restore" is **confirmed**, with the qualification that it holds only while
  the element is actually controlled — which UIx did not guarantee, and which
  `re-frame.adapter.uix` now does (`rf2-heqwo`, Option C above).
- HD-019's "rejected/unchanged-model paths lean on React's own end-of-event
  restore" is **confirmed for the value and refuted for the caret** — and the
  caret is now the *arm's*, taken in the element path rather than leaned on
  (`rf2-fki5d`, Option B above). HD-019's `flushSync` clause **has** the
  amendment noted under B (`rf2-ncn5p`), and HD-019's own text now carries the
  caret half of this correction rather than leaving it to this page.
- `rf2-n3dxw`'s headline residue is **answered for Arm 1**: a refused
  keystroke converges in the same turn with the caret at the position before
  it. One thing it recorded is not answered and is not rounded up — the
  **range** row (a second algorithm, and off the change path entirely). The
  **IME** fence is now established by the real-composition harness
  (`rf2-o27h3`, the *IME composition* section above), and its one open
  residue — the mid-composition rewrite every implementation performed when
  the model disagreed — was closed by `rf2-digtt`'s carve-out on
  2026-08-03, in Arm 1 only. And nothing here reaches a UIx consumer's
  `:input`: the carve-out is the element path's, so a UIx consumer's
  composition is still React's to abort.
