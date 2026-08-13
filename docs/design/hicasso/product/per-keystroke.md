# The per-keystroke mechanics: the four-field editor and the 100-cell grid

What one keystroke costs, stage by stage, on the two public-package witness
applications — measured rather than reasoned about.

[specification §6](specification.md#6-performance-contract) asks for the
mechanical per-keystroke path of these two pages: *state writes, subscription
recomputations, boundary runs, write amplification, commit, and visible echo*,
as **explanatory product documentation as well as a performance witness**. This
page is both halves. A reader who finishes it should be able to predict what a
keystroke costs in their own application from its read topology alone, and a
reader auditing the numbers should be able to find the witness that took each
one.

The applications are the ones [`rf2-hic-078`][apps] built and `main` carries:
`examples/editor` and `examples/grid`. **No number on this page comes from the
frozen bench prototypes** under `implementation/freehand/test/re_frame/bench/`,
which are a different tree measuring different questions.

[apps]: ../../../../implementation/hicasso/test/re_frame/hicasso/examples/
[census]: ../../../../implementation/hicasso/test/re_frame/hicasso/examples/per_keystroke_dom_cljs_test.cljs
[kit]: ../../../../implementation/hicasso/test_kit/src/re_frame/hicasso/test/mounted.cljs

**Two findings up front, because they are the ones a reader should leave with.**
A keystroke's *boundary* cost is flat in the size of the page, exactly as
specification §6 claims — and its *subscription recomputation* cost is not: it is
one recomputation per mounted cell, at every size. And the visible echo of an
accepted keystroke costs **zero** writes onto the glass, because the character is
already there.

---

## 1. Pre-registration

**This section was written and committed before any measurement was taken**, so
that a prediction could not be trimmed to fit a reading. Two of its twelve
predictions missed, and both misses are reported below in the place the
prediction was made rather than quietly corrected.

### 1.1 The six instruments, fixed in advance

Each stage of the path gets exactly one instrument, and no stage gets two.

| Stage | Instrument | What one unit is |
|---|---|---|
| State writes | app-db value diffed either side of the DOM event | one leaf address whose value moved |
| Subscription recomputations | a counting wrapper installed on the registrar's `:handler-fn` before mount, removed after | one invocation of the author's own computation fn |
| Boundary runs | [`hm/bodies-run`][kit] | one `defview` body React actually ran |
| Commit — glass writes | a spy on the `value` property setter of `HTMLInputElement.prototype` / `HTMLTextAreaElement.prototype`, restored in a `finally` | one write of a value onto a live control |
| Commit — DOM mutations | a `MutationObserver` over the container (`childList`, `attributes`, `characterData`) | one mutation record |
| Visible echo | the typed control's `.value`, read at the instant `dispatchEvent` returns | — |

Two of these deserve their definition stated rather than assumed.

**The glass-write count excludes the keystroke's own write.** A scripted
keystroke reaches the same code path a real one does only by writing through the
element prototype's own `value` setter and then firing a real `input` event
(`editor.flow-dom-cljs-test/type-into!` established this). That first write is
the *user agent's*, not the runtime's, so [the census][census] performs it
through a setter captured at namespace load, outside the spy's reach by
construction rather than by subtracting one and hoping the subtrahend never
changes.

**The echo is read before any flush.** Every existing mounted witness in the tree
calls `hm/settle!` and then asserts the glass. That is right for a correctness
row and wrong for a latency one — it reads the page after a flush the browser had
not yet performed. This page reads `.value` at the instant `dispatchEvent`
returns, inside the discrete event, and settles afterwards.

### 1.2 The predicted figures, and what they did

Deterministic counters, so these were exact integers and not bands. A band would
have been the wrong instrument: contention cannot move a monotone counter
([budgets §2](budgets.md#2-the-two-disposition-families-which-6-already-separates)).

| # | Subject | Stage | Predicted | Measured | |
|---|---|---|---:|---:|---|
| P1 | Editor, steady-state keystroke into `:title` | state writes | 1 | **1** | held |
| P2 | Editor, steady-state keystroke | subscription recomputations | 10 | **10** | held |
| P3 | Editor, steady-state keystroke | boundary runs | 1 | **1** | held |
| P4 | Editor, steady-state keystroke | glass writes by the runtime | 1 | **0** | **missed** |
| P5 | Editor, first keystroke of a session | boundary runs | 2 | **2** | held |
| P6 | Grid 10×10, keystroke into cell `[3 4]` | state writes | 1 | **1** | held |
| P7 | Grid 10×10, keystroke into cell `[3 4]` | subscription recomputations | 111 | **111** | held |
| P8 | Grid 10×10, keystroke into cell `[3 4]` | boundary runs | 2 | **2** | held |
| P9 | Grid 5×5, keystroke into cell `[3 4]` | subscription recomputations | 31 | **31** | held |
| P10 | Grid 10×10, **refused** keystroke | state writes | 0 | **0** | held |
| P11 | Grid 10×10, **refused** keystroke | subscription recomputations | 0 | **0** | held |
| P12 | Either page | echo present before any flush | yes | **yes** | held |
| — | Either page | DOM mutation records | 0 | **7** / **8** | **missed** |

**P2, P7 and P9 were the predictions this page existed to test**, and the
reasoning behind them is worth keeping now that they have held, because it is
the reasoning a reader needs in order to predict their own page. Every
subscription in both applications is a **layer-1** reader — registered as
`(fn [db query-v] …)`, so its input is the whole of app-db rather than another
subscription's value. `re-frame.subs.memo`'s layer-1 wrapper memoises on that
input: the author's body is skipped when the new app-db is `=` to the last one
seen, and re-run when it is not. **A keystroke moves app-db.** So every mounted
layer-1 cell that is derefed on the write path re-runs its body, and the count is
the number of live cells rather than the number of changed addresses.

The two misses are §5's and §6's subjects and are treated there.

---

## 2. The four-field editor

Four controls, a button row and a committed readout. Ten subscription cells are
live once it is mounted: `[::field …]` ×4, `[::committed …]` ×4, `[::revision]`
and `[::dirty?]`.

One keystroke into the title field, in the steady state — the second and every
later keystroke of an editing session:

| Stage | Count | Attribution |
|---|---:|---|
| State writes | **1** | `[:draft :title]`, and nothing else in app-db |
| Subscription recomputations | **10** | `::field` 4, `::committed` 4, `::revision` 1, `::dirty?` 1 |
| — of which computed a *new* value | **1** | `[::field :title]` |
| Boundary runs | **1** | the title field's body |
| Glass writes (`value` property) | **0** | the character is already on the glass |
| DOM mutation records | **7** | `name` ×4, `type` ×2, `value` ×1 — none of them the echo |
| Visible echo | present | at the instant `dispatchEvent` returned |

**The session's first keystroke runs two bodies, not one**, and that is named
rather than tuned away: `[::dirty?]` goes false to true exactly once per session,
which runs the button row alongside the field. It is the ordinary way an
application spends its second body run. From the second keystroke on the count is
one, and it stays one however long the burst of typing is.

**Nothing else on the page is notified.** The three other controls read addresses
this keystroke did not move; the readout reads the *committed* article, which a
keystroke never touches, so it holds still while you type and moves when you
save; and the form body itself reads nothing at all, so it is neither notified
nor made to props-compare its six children. That last absence is the load-bearing
one — a parent that read anything a keystroke touches would put itself, and a
compare over every child, on the typing path.

---

## 3. The 100-cell grid

The same control written a hundred times, with a per-row total that genuinely
depends on its row. One keystroke into cell `[3 4]`, at two sizes:

| Stage | 5×5 | 10×10 | Scales with the mount? |
|---|---:|---:|---|
| State writes | **1** | **1** | no |
| Subscription recomputations | **31** | **111** | **yes** |
| — `::cell` | 25 | 100 | yes |
| — `::row-total` | 5 | 10 | yes |
| — `::dimensions` | 1 | 1 | no |
| — of which computed a *new* value | 2 | 2 | no |
| Boundary runs | **2** | **2** | **no** |
| Glass writes (`value` property) | 0 | **0** | no |
| DOM mutation records | 8 | **8** | no |

**Two boundary bodies, at both sizes.** The cell that was typed into, and its
row's total, which genuinely changed. Quadrupling the mounted cells changes
nothing, which is specification §6's *narrow-update body work scales with changed
rows rather than all mounted rows*, measured. The layout bodies — `grid` and the
ten `grid-row`s — read only the dimensions, a value a keystroke cannot touch, so
they are not on the path at all.

**And one hundred and eleven subscription recomputations.** Every mounted cell
re-runs its `get-in`; every row total re-runs its ten-cell fold and its
`parseInt`s; the dimensions cell re-runs and answers what it answered before.
One hundred and nine of the hundred and eleven compute the value they computed
last time and notify nobody. The count is `rows × cols + rows + 1` at both sizes
measured, and it follows the mount rather than the change.

---

## 4. Write amplification, stated four ways

**Write amplification is not one number, and reporting it as one is how a page
like this misleads.** The guide defines it as *the number of view bodies that run
per state write*, and on that definition both applications are flat in their own
size. On three other definitions, each of them a real cost somebody pays, the
same keystroke reads differently.

| Amplification of one state write into… | Editor | Grid 5×5 | Grid 10×10 | Grows with the page? |
|---|---:|---:|---:|---|
| **view bodies** — the guide's definition | 1 | 2 | 2 | **no** |
| **subscription recomputations** | 10 | 31 | 111 | **yes, linearly** |
| **writes onto the glass** | 0 | 0 | 0 | no |
| **DOM mutation records** | 7 | 8 | 8 | no |

Three of those four rows are flat and the honest one is not. Stated plainly:
**one keystroke in a hundred-cell grid runs 111 subscription bodies to run 2 view
bodies.** The read topology buys narrow *notification*, which is what stops the
page re-rendering; it does not buy narrow *recomputation*, and nothing in the
applications' own docstrings said so before this census was taken. Two of those
docstrings have been corrected in the same change as this page.

**Why it is nevertheless the right shape, and where it would stop being right.**
A layer-1 body here is one `get-in` into one address; a hundred of them is a
hundred map lookups, which is cheap in a way the two view bodies are not — a view
body allocates elements and reconciles them, and the whole reason the count of
those stays at 2 is the topology. The row totals are the term to watch: each is a
ten-cell fold with a `parseInt` per cell, so the grid's real recomputation work
is nearer `rows × (cols + cols)` than its cell count suggests, and it is
`::row-total` and not `::cell` that would bite first on a page with an expensive
derived read per row. **A reader predicting their own page should count their
mounted layer-1 cells and ask what the most expensive one of them costs**, not
count the addresses their event writes.

The remedy, where one is wanted, is the ordinary one and is not a new mechanism:
a derived read stated as a layer-2 subscription over its own inputs is memoised
on *those inputs* rather than on app-db, so it does not re-run when they have not
moved. Nothing in this corpus has measured that contrast yet, and this page does
not claim it — it is named as the next question rather than as an answer.

---

## 5. The commit, attributed

The prediction was zero DOM mutations, on the reasoning that a controlled input's
value is a *property* and never reaches the markup. The first half of that is
right and the conclusion was wrong: the value is indeed a property and never
appears in the markup, and the commit still produces **seven** mutation records
in the editor and **eight** in the grid.

They are attributed by an experiment the census can run because the grid refuses
non-digits: **a refused keystroke runs zero bodies**, so React commits nothing —
and three of the seven records appear anyway.

| Records | Whose | What they are |
|---:|---|---|
| 4 | the commit | `name: nil → ""`, `type`, `value` (i.e. `defaultValue`), `name: "" → removed` |
| 3 | React's post-event controlled-state restore | the same churn without the `value` write, `defaultValue` already being right |
| 1 | the commit, grid only | `characterData` on the row total's text node |

**Four of the editor's seven records are churn on an attribute the application
never wrote.** React 19 removes and restores `name` around every controlled-input
update, so that a radio group's changes apply atomically; these inputs have no
`name` at all, and they pay for the guarantee anyway. It is React's cost and not
the substrate's, it is recorded rather than complained about, and no remedy is
proposed here — the note exists so that a reader counting mutation records on
their own page is not surprised by a number four times larger than their markup
can explain.

**The one record a user could point at is the grid's `characterData`.** On both
pages the echo the typist actually sees — the character in the field — produces
no mutation record at all, because it is a property. The only visible change that
reaches the DOM tree is the row total's text, and it belongs to the derived read
rather than to the field that was typed into.

### The glass write, and P4's miss

**An accepted keystroke writes the `value` property zero times.** The prediction
was one, and the reason the truth is zero is the whole of the controlled law read
from the other end: the character is already on the glass — the user agent put it
there before the event fired — and the model took it unchanged, so the converge
has nothing to write and React's own value diff finds the node already showing
what it was about to commit. **The echo of an accepted keystroke is free.**

**A refused keystroke writes it exactly once.** That single write is the
committed value going back over the character the model would not take, and it is
what a user sees as the field declining to accept a letter. It is also this
instrument's own positive control, and it came for free: a spy that read zero
everywhere would be a broken spy, and this one reads 1 on the same page, in the
same file, through the same descriptor.

So the rule an author can carry away is exact: **the controlled law costs a write
onto the glass only when the model disagrees with the field.**

---

## 6. The visible echo, and the budget that is REFUSED

### What was measured

On both pages, the typed field shows the model's value **at the instant
`dispatchEvent` returns** — inside the discrete event, before the turn yields,
with no flush performed and no opportunity for the browser to paint. The census
reads it there and settles afterwards, which is the opposite order from every
other mounted witness in the tree.

That is a stronger fact than the one [`U1`](budgets.md#9-the-budget-line-reconciliation-ledger)
is written about. `U1` asks that controlled updates be *echoed within one 60 Hz
frame at p95*; what the measurement shows is that **no frame boundary is crossed
at all**. There is no frame to be within.

### Why `U1` is nevertheless NOT pinned, and this page refuses to pin it

`U1`'s registered estimand is *latency to visible echo*, at `p95`. It is a
**distributional** row ([budgets §4](budgets.md#4-distributional-rows--s1s5-re-pinned-on-the-package-s6s7-carried)),
and this page has no clock. The refusal is at source and is not a scheduling
problem:

- **No package-resident clock instrument exists.**
  [budgets §9.2](budgets.md#92-what-each-not-green-row-is-waiting-on) says so in
  terms, and [§9.3](budgets.md#93-where-this-ledger-stops-and-rf2-hic-071-begins)
  assigns building one to `rf2-hic-071` — naming this very budget, the *one-frame
  keystroke echo*, as the row that needs it.
- **Building one here was out of bounds**, and §9.3 gives the reason rather than
  the schedule: *building a second instrument to say more would be building a
  second thing to drift*. A measurement window may not improve its own rig.
- **A structural fact cannot be substituted for a distributional one.** *The echo
  is present before the turn yields* and *the echo reaches the glass within
  16.7 ms at the 95th percentile* are different claims, and the first does not
  imply the second on a machine where the event turn itself can be slow. Reading
  one as the other is precisely the substitution [budgets §3](budgets.md#3-deterministic-rows-pinned-on-the-moved-package)
  refuses when it keeps `D9`'s residue counters apart from `S5`'s retained bytes.

**So `U1` stays `UNPINNED`, and its deterministic half is now published rather
than merely asserted.** What this page adds to the row is not a figure but a
narrowing: whatever the clock eventually reports, it will be timing work that
happens inside one discrete event, on a path whose every other stage is counted
above.

---

## 7. Provenance

Every figure above was taken on `P-DEV-1`
([budgets §1](budgets.md#1-the-named-reference-profiles)) by the browser lane,
`npm run test:browser`, from [the census suite][census], on a branch based on
`52275d7b19b3de2bdc48708e46e4102104c3199d` — which is on `main`.

**The quiet box was verified and was not load-bearing.** `Get-Counter
'\System\Processor Queue Length'` read `0` on every sample before, during and
after the runs, and `'\Processor(_Total)\% Processor Time'` ran 6–17%. It is
recorded because the lane requires it, and the honest note beside it is that
**this census did not need it**: every figure on this page is a monotone counter,
and [budgets §2](budgets.md#2-the-two-disposition-families-which-6-already-separates)
records that a counter reads the same on a loaded box. The rows that would have
needed a quiet box are the ones §6 refuses.

| Run | What | Captured exit | Result |
|---|---|---:|---|
| 1 | control, before the census existed | `0` | 1,489 tests, 9,311 assertions, 0 failures |
| 2 | the census, asserting the pre-registered predictions | `1` | 1,491 / 9,332 — four reds, being P4 and the mutation prediction on both pages |
| 3–4 | attribution probes | `1` | the mutation breakdown and attribute trace, read out of the failure path |
| 5 | the measured figures | `0` | 1,491 / 9,334, 0 failures |
| 6 | **sabotage** — P7's `111` inverted to `112` | `1` | captured red naming the line, below |
| 7 | restored, plus the per-subscription attributions | `0` | 1,491 / 9,337, 0 failures |

**The assertion arithmetic is what proves the rows ran rather than skipped.** The
census's rows are `browser?`-guarded and degrade to stated skips off the browser
lane, so a green aggregate alone would not distinguish *ran and passed* from
*skipped*. Run 1 to run 5 is `+2` tests and `+23` assertions, which is exactly
the twenty-three assertions the two new `deftest`s contain.

**Run 6 is the sabotage control, and it is why run 5 and run 7 mean anything.**
Inverting one figure produced a captured failure naming it:

```
FAIL in (the-grids-per-keystroke-census-at-two-sizes)
  (re_frame/hicasso/examples/per_keystroke_dom_cljs_test.cljs:437:11)
P7 — subscription recomputations at 10x10. Measured:
  {…grid.subs/cell 100, …grid.subs/row-total 10, …grid.subs/dimensions 1}
expected: (= 112 (:sub-runs at-100))
  actual: (not (= 112 111))
```

That report does two jobs, and the second is the more useful. It proves the row
**executes** — a skipped row cannot fail — and because `cljs.test` prints the
bound values, `(not (= 112 111))` and the breakdown beside it **independently
witness the figure and its attribution** from the failure path, without the
passing assertion being the thing that reports them. The file was restored and
its content hash (`git hash-object`) matched its pre-sabotage value exactly:
`7197902f6ed8054daf627122a993eb22ae24ed33`.

### What would falsify this page

Any of: a subscription in either application changing layer, which would move the
recomputation counts without touching any other row; React changing its
controlled-input update, which would move §5's seven and three; a boundary count
moving without a topology change to explain it (the standing clause
[budgets §8](budgets.md#8-this-pages-own-run-and-what-would-falsify-it) already
states over `D1`–`D16`); or the arrival of a package-resident clock, which would
make §6's refusal answerable rather than merely correct.

---

## 8. What this page does not conclude

Stated as its own section because a window that publishes nothing still reports
everything, and this one published a good deal.

- **No latency, of any kind.** No millisecond, no `p50`, no `p95`. `U1`–`U4` stay
  `UNPINNED` and §6 says why the refusal is at source.
- **No claim about any machine but `P-DEV-1`** — though the counters here are the
  family that carries no hardware profile at all, so
  [§1's single-profile limitation](budgets.md#the-single-profile-limitation-accepted-explicitly)
  binds this page less tightly than it binds a distributional one.
- **No new ledger rows.** The census's figures deserve rows in
  [budgets §9](budgets.md#9-the-budget-line-reconciliation-ledger), and minting
  them means editing `check_budget_ledger.py`'s pinned constants, which is
  `rf2-hic-089`'s surface and not this bead's. `U1`'s entry in §9.2 has been
  updated to point here; the rows are filed as follow-up work.
- **No remedy for §4's amplification, and no claim that it needs one.** The
  layer-2 contrast named at the end of §4 is a measurement nobody has taken.
  Naming it is not proposing it.
- **No attribution of §5's `name` churn beyond React's own documented reason.**
  The trace shows what happens and the refusal experiment shows which pass owns
  it; why React 19 spells the atomicity guarantee that way is React's business
  and was not investigated.
- **Nothing about composition, IME or the caret.** A keystroke here is a scripted
  `input` event on a settled field. Composition exchanges are
  [`rf2-hic-040`](https://github.com/day8/re-frame2/issues)'s cross-browser
  matrix and are not this census's subject.
