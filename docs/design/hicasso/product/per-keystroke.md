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
frozen bench prototypes** that then stood under
`implementation/freehand/test/re_frame/bench/` — a different tree measuring
different questions, and one deleted whole on 2026-08-15 by `c951808b47`. The
disclaimer is kept rather than dropped because it is what a reader needs in
order to know where these numbers did *not* come from; what has changed is that
the tree it excludes is no longer somewhere a reader could go and look.

[apps]: ../../../../implementation/hicasso/test/re_frame/hicasso/examples/
[contrast]: ../../../../implementation/hicasso/test/re_frame/hicasso/examples/grid/row_total_layer2_dom_cljs_test.cljs
[kit]: ../../../../implementation/hicasso/test_kit/src/re_frame/hicasso/test/mounted.cljs

## Currency, and the census suite this page was written against

**Re-read 2026-09-04 against `main@bd61169475` (`rf2-lexh`). No figure below was
re-measured and none moved; what this note records is the state of the artefacts
the figures were taken from.**

Every figure in §2–§5 and §7 was taken by the **per-keystroke census suite**,
which stood at
`implementation/hicasso/test/re_frame/hicasso/examples/per_keystroke_dom_cljs_test.cljs`
and **is no longer in the tree**: it was retired on 2026-08-30 by `f5f40d1116`,
*"retire the per-keystroke census suite; its counter moves into the grid layer-2
contrast"* (`rf2-6c12m.8`). The retirement was deliberate and its reasoning is on
the commit — the suite had been kept off an earlier deletion list only because
`check_budget_ledger.py` reddened on a missing L6 witness, and that gate retired
in the same PR.

What survives, and what does not:

- **The counter survives.** `with-counted-subs` and `total` — the census's own
  subscription-recomputation instrument — moved into
  [the layer-2 contrast][contrast] as private defns, which is where §4.1's
  figures already came from. That file's own docstring names the census as their
  origin and treats this page's published figures (111 at 10×10, 31 at 5×5) as a
  cross-check on its layer-1 arm.
- **The rows do not.** The other five instruments of §1.1, the pre-registered
  predictions of §1.2, and the run table of §7 have no artefact in the tree that
  re-takes them. **These figures are therefore no longer re-runnable from
  `main`**, and nothing has replaced them for the editor at all.
- **Nothing here re-scores anything.** The figures stand exactly as measured, on
  the base §7 names. What would settle the gap is a bead that either re-takes the
  census against the shipped applications or rules the per-keystroke path
  witnessed by §4.1's contrast alone; neither has been ruled and this page does
  not rule it.

Citations of *the census* below therefore name a retired suite, and the three
verbatim `cljs.test` traces in §7 quote its file and line numbers as they stood
when those runs were taken. Both are left as written: a dated measurement record
names the artefact that produced it, and rewriting the trace would make the run
unreproducible on any tip.

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
| Commit — DOM mutations | a `MutationObserver` over the container (`childList`, `attributes`, `attributeOldValue`, `characterData`) | one mutation record |
| Visible echo | the typed control's `.value`, read at the instant `dispatchEvent` returns | — |

Two of these deserve their definition stated rather than assumed.

**The glass-write count excludes the keystroke's own write.** A scripted
keystroke reaches the same code path a real one does only by writing through the
element prototype's own `value` setter and then firing a real `input` event
(`editor.flow-dom-cljs-test/type-into!` established this). That first write is
the *user agent's*, not the runtime's, so the census performed it
through a setter captured **on first browser use, before the spy** — the spy
forces the capture at its own top, ahead of replacing any descriptor. Both
halves of that ordering are load-bearing. Capturing at namespace load instead
takes the node lane down with it — §7 records that this census had exactly that
defect and fixed it — and capturing lazily on the first *keystroke* would capture
the spy's own setter, because every keystroke here is typed inside one, turning
the accepted keystroke's measured zero into a one. So the user agent's write is
outside the count by construction rather than by subtracting one and hoping the
subtrahend never changes.

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
moved. **That contrast has since been measured**, and §4.1 reports it. It is a
measurement and not a recommendation: the applications are unchanged, and
adopting the spelling in `examples/grid` would be a separate decision.

### 4.1 The layer-2 contrast, measured

[`rf2-18u0`][contrast] restates the grid's `::row-total` as a layer-2
subscription over its own row's cells and re-runs the census with that one
substitution made. The arm lives in the suite and **the witness applications are
untouched** — `examples/grid/subs.cljs` and `examples/grid/views.cljs` are
unchanged by this measurement, and its layer-1 arm re-reads §3's figures on the
same tree to say so — for the reason
`grid.scaling-dom-cljs-test`'s coarse shapes live there too: an application is
evidence about the public door, and a spelling nobody has adopted does not belong
in one. Both arms are measured in the same file on the census's own counter,
which at the time of the measurement was made public in the census suite rather
than copied — so the contrast below is a subtraction between two readings taken
together rather than between two instruments run in two places. *(As at
2026-09-04, the census suite is retired and that counter is a pair of private
defns in the contrast file itself; the property the sentence is claiming — one
counter, both arms, one file — survives the move unchanged, and no figure in this
table was re-taken.)*

| Subscription recomputations, one keystroke into `[3 4]` | 5×5 | 10×10 |
|---|---:|---:|
| `::row-total` as a **layer-1** reader — the application, and §3's row | **31** | **111** |
| `::row-total` restated as a **layer-2** read over its row's cells | **27** | **102** |
| difference | −4 | −9 |

And the attribution, which is where the whole reading is:

| Arm | `::cell` | the row total | `::dimensions` |
|---|---:|---:|---:|
| 5×5, layer-1 | 25 | 5 | 1 |
| 5×5, layer-2 | 25 | **1** | 1 |
| 10×10, layer-1 | 100 | 10 | 1 |
| 10×10, layer-2 | 100 | **1** | 1 |

**The row-total term goes from `rows` to one, and stops following the mount.**
That is the thing §4 predicted and the thing it declined to claim: a layer-1
reader is memoised on the whole of app-db, which a keystroke always moves, so
every mounted row's fold re-runs; a layer-2 read is memoised on the `cols` cell
values it names, and nine of the ten rows at 10×10 have not moved one of them.

**And the linear term survives, which is the half of this result a reader is
likeliest to misread.** `::cell` is a layer-1 reader in *both* arms and there are
a hundred of them mounted, so a hundred bodies run either way. The saving is
`rows − 1` recomputations — **9 of 111** at 10×10 and **4 of 31** at 5×5, which
is roughly 8% and 13% — and the count that remains still follows the mounted cell
count rather than the change: 102 for a hundred cells and 27 for twenty-five. So
this does not overturn §4's headline sentence. What it does is remove exactly the
term §4 named as *the one that would bite first on a page with an expensive
derived read per row*, and leave standing the term §4 called cheap. **On a page
whose derived read is expensive, the shape of the saving matters more than this
page's fraction of it does**, and the shape is what the two rows of ones above
report.

**Boundary runs did not move: two, both arms, both sizes.** The contrast is about
which *subscription* bodies re-run, and that row is what says the layer-2
spelling bought it without changing what the page re-renders — a restatement that
had broadened notification would read higher and a dead one would read lower.
The arm's liveness has its own positive control beside it: row 3's rendered total
goes `345` → `652` across the keystroke (the seeded row sums 30…39 = 345, and
cell `[3 4]` becomes `341`), so the single body run counted is a real recompute
of a real new value.

**What the spelling costs the author, stated because it is not nothing.** A
`:parametric` sub's `input-fn` is pure in the *query vector* and cannot read
app-db, so the row's width has to arrive in the query itself —
`[::row-total-l2 row cols]` where the layer-1 spelling was `[::row-total row]`.
Here the width is to hand, because the row's own body already read the dimensions
to lay its cells out. **A derived read whose input set is not knowable from its
query vector cannot take this spelling at all**, and that, rather than any
per-keystroke figure, is the first question a reader should ask of their own
page. In exchange, `input-fn` runs once per cache entry at materialisation, so
the entry's topology is fixed for its lifetime and a resize mints a new entry
rather than leaving one folding a stale width.

**What §4.1 does not conclude.** No clock: the figures are body-run counters, and
nine fewer executions of a ten-cell `parseInt` fold is not a latency claim — this
page owns no instrument that could turn it into one, for [§6](#6-the-visible-echo-and-the-budget-that-is-refused)'s
reason. No general claim about layer-2 subscriptions: one derived read, on one
page, at two sizes. And no adoption — see the head of this subsection.

**Provenance.** `P-DEV-1`, browser lane, `npm run test:browser`, on a branch
based on `c68844184c9912479b0220778b2ec6161e33f5b7` (on `main`). The captured
exit below is the runner's; the lane's `shadow-cljs compile browser-test` step
ran ahead of runs 1–4 and returned `0` each time, and runs 5 and 6 re-ran the
runner against run 4's compiled bundle because the tree had not moved.

| Run | What | Captured exit | Result |
|---|---|---:|---|
| 1 | the contrast arm, before its boundary-count row | `0` | 1,554 tests, 9,867 assertions, 0 failures |
| 2 | **sabotage** — the layer-2 arm's 10×10 total and 5×5 attribution inverted | `1` | captured red naming both lines, below |
| 3 | restored, unchanged from run 1 | `0` | 1,554 / 9,867, 0 failures — **no figure moved** |
| 4 | the boundary-count row added | `1` | two reds, both in the async-nav back-button suites; this file clean |
| 5 | re-run, no tree change | `1` | one red, the browser-heap `requestGC` handshake; this file clean |
| 6 | re-run, no tree change | `0` | 1,554 / **9,868**, 0 failures |

**Runs 4 and 5 are flakes, and run 6 is what says so.** Runs 4, 5 and 6 were
taken on the identical tree *and the identical compiled bundle*, so the green one
settles it: nothing in that bundle can be responsible for a red the same bundle
also produces green. The reds
were different tests each time — two `poll-until` timeouts on the real Back
button in run 4, an absent `requestGC` handshake in run 5, all three in browser
suites this change does not touch — and the contrast arm's own namespace printed
no failure in either run.

**Run 1 to run 6 is `+0` tests and `+1` assertion**, which is exactly the
boundary-count row: it lives inside a `deftest` that already existed, so the test
count cannot move and the assertion count moves by the one row added. That
arithmetic is a consistency check and not the proof the rows ran — run 2 is.

**Run 2 is the sabotage control, and it is what makes runs 1, 3 and 6 mean
something.** Inverting the layer-2 arm's 10×10 total and its 5×5 attribution
produced a captured failure naming both lines and printing the readings from the
failure path, so the numbers
above are witnessed by something other than the passing assertion that reports
them:

```
FAIL in (the-layer-2-row-total-recomputes-once-where-the-layer-1-one-recomputes-per-row)
  (re_frame/hicasso/examples/grid/row_total_layer2_dom_cljs_test.cljs:277:13)
expected: (= 999 (:sub-runs l2-100))
  actual: (not (= 999 102))

FAIL in (the-layer-2-row-total-recomputes-once-where-the-layer-1-one-recomputes-per-row)
  (re_frame/hicasso/examples/grid/row_total_layer2_dom_cljs_test.cljs:278:13)
expected: (= {…grid.subs/cell 999, …/row-total-l2 999, …grid.subs/dimensions 999}
             (:by-sub l2-25))
  actual: (not (= {…999, …999, …999}
                  {…grid.subs/cell 25, …grid.subs/dimensions 1, …/row-total-l2 1}))
```

A red naming the file proves the rows **execute** — a skipped row cannot fail —
and the printed right-hand sides are the 10×10 total (`102`) and the 5×5
attribution (`{25, 1, 1}`, summing to 27) read out of the failure path rather
than out of the passing assertion that reports them. The namespace prefixes are
elided for width; nothing else in the two reports is. The line numbers pin the
**sabotage tree**, in which the boundary-count row above them had not yet been
written; the same two assertions are at 286 and 287 in the file as it ships. The
file was restored and run 3 re-took every figure unchanged.

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

**What a mutation record can and cannot say, because the table below is read
through it.** A `MutationRecord` for an attribute carries that attribute's value
*before* the record and nothing else. There is no new-value field, and reading
one back off the element answers what the attribute holds *now* rather than what
that record wrote — which is why the census's raw trace showed its
`name` records as `nil -> nil`, both sides being the attribute as it finally
stands. **No arrow the instrument prints is an observed transition, and this page
does not present one as such.** What the observer does witness, exactly, is the
*order* of the attribute names and each record's old value; so the table below is
read forwards through those old values, and what a record did is named by the
*next* record's.

| Records | Whose | The attributes in order, and what each held before its record |
|---:|---|---|
| 4 | the commit | `name` (absent), `type`, `value` — i.e. `defaultValue` — (the pre-keystroke text), `name` (`""`) |
| 3 | React's post-event controlled-state restore | `name` (absent), `type`, `name` (`""`) — the same churn without the `value` write, `defaultValue` already being right |
| 1 | the commit, grid only | `characterData` on the row total's text node |

**Read forwards, those old values are what say `name` churns.** It is absent, a
record writes it, and the record that removes it reports `""` as what it
removed — so React added an empty `name` and took it away again, once per pass,
and `name` is absent again by the end. The transient `""` is therefore
*witnessed*, one record later than the record that wrote it, rather than shown
as a transition the observer never saw.

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

**The reading that carries that claim is the one where the model DISAGREES with
the field, and it has to be.** A scripted keystroke reaches the same code path a
real one does only by writing the accepted text onto the control and then firing
the event (§1.1), so on a keystroke the model takes *verbatim* the model's value
and the census's own pre-event write are the same string — and a reading taken
at dispatch return cannot tell an echo from its own setup. Those readings are
still taken and still reported, because *the character survived the converge* is
a real fact whose opposite is a real regression, but they are not what proves
this section. Each page carries a second reading typed into a field whose model
answers something else:

| Page | Field, and its policy | Typed onto the control | The model's value | Read at dispatch return |
|---|---|---|---|---|
| editor | `:slug`, which **normalises** | `intents-are-data, World` | `intents-are-data-world` | `intents-are-data-world` |
| grid | cell `[3 4]`, which **refuses** | `34x` | `34` | `34` |

**Neither echoed string could have come from the census's own pre-event write,
and neither needed a flush to arrive.** That is the whole of the claim. The
slug's normalisation is length-changing on purpose — a normalisation that
preserved length could be satisfied by a field that echoed nothing at all
(`editor.events/slugify`) — and the grid's refusal is the case where the
converge has actual work to do, which is why it is also the one glass write §5
counts.

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
`npm run test:browser`, from the census suite (retired 2026-08-30 by
`f5f40d1116`; see [Currency](#currency-and-the-census-suite-this-page-was-written-against))
— **except §4.1's, which
come from a different suite on the same lane and the same profile and carry
[their own run table](#41-the-layer-2-contrast-measured)**. The runs numbered
below are this section's; §4.1's are numbered separately and independently.
Runs 1–7 were taken on
a branch based on `52275d7b19b3de2bdc48708e46e4102104c3199d`; the branch was then
rebased onto `b44c5e854cae93a2a6ec520f1667f1da56c19e8b` and **run 8 re-took every
figure on that base without one of them moving**. Both are on `main`.

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
| 8 | **re-taken after rebasing onto `b44c5e854c`** | `0` | 1,502 / 9,508, 0 failures — no figure moved |
| 9 | control on this page's current base, before P12's rows | `0` | 1,509 / 9,559, 0 failures |
| 10 | **sabotage** — P12's two new rows asserting the PRE-EVENT text | `1` | captured red naming both lines, below |
| 11 | P12's discriminating rows, corrected | `0` | 1,509 / 9,561, 0 failures — no census figure moved |
| 12 | re-taken after rebasing onto `cd58aec50a` | `0` | 1,509 / 9,561, 0 failures — identical to run 11 |

The node lane (`npm run test:cljs`) was run on the same tree and returns `0` at
13,875 tests / 70,108 assertions. It is not where these figures come from — every
row here is `browser?`-guarded and skips there — but it is where a namespace that
touched `HTMLInputElement` at load time would have taken the whole lane down with
it, which is a defect this census had and fixed.

**The assertion arithmetic is what proves the rows ran rather than skipped.** The
census's rows degrade to stated skips off the browser lane, so a green aggregate
alone would not distinguish *ran and passed* from *skipped*. Run 1 to run 5 is
`+2` tests and `+23` assertions, exactly the assertions the two new `deftest`s
then contained; run 7 added three per-subscription attributions for `+26`. Run 9
to run 11 is `+0` tests and `+2` assertions — P12's two discriminating rows,
which live inside the two `deftest`s that already existed, so the test count
does not move and the assertion count moves by exactly the rows added.

**Run 8's arithmetic is checkable against a figure this page did not produce.**
`rf2-hic-036`'s tournament landed on `main` between run 7 and the rebase, and
[its own §2.1](topology-tournament.md) published the lane at **1,500 tests /
9,482 assertions** on that base. Run 8 reads 1,502 / 9,508 — the same `+2` and
`+26`, arrived at from a control another worker measured. **That page has since
re-taken its own lane** on a later base and now publishes a larger total, so the
cross-check reproduces against the reading quoted here rather than against its
current head. The `+2` and `+26` are the claim; the absolute totals are only the
two endpoints the difference was measured between.

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
its content hash (`git hash-object`, not a byte digest — this checkout
translates line endings) matched its pre-sabotage value exactly, at
`7197902f6ed8054daf627122a993eb22ae24ed33`. That hash pins the file **as it
stood between runs 5 and 6**; it has had three commits since — the `delay` in
the node-lane fix above, the attribute-trace correction §5 is drawn through, and
P12's discriminating rows — and **run 12** is the reading that covers the file as
it ships.

**Run 10 is the second sabotage, and it is the one that retired P12's
fail-open.** Both new rows were first asserted against the text the census
writes onto the control *before* the event — which is precisely what the earlier
reading could not tell an echo apart from — and both came back red, printing the
model's value beside it:

```
FAIL in (the-editors-per-keystroke-census)
  (re_frame/hicasso/examples/per_keystroke_dom_cljs_test.cljs:450:23)
expected: (= "intents-are-data, World" echo)
  actual: (not (= "intents-are-data, World" "intents-are-data-world"))

FAIL in (the-grids-per-keystroke-census-at-two-sizes)
  (re_frame/hicasso/examples/per_keystroke_dom_cljs_test.cljs:580:23)
expected: (= "34x" (clojure.core/deref echo))
  actual: (not (= "34x" "34"))
```

A red naming both lines proves both rows **execute**, and the printed right-hand
sides witness the model's values from the failure path rather than from the
passing assertion — run 6's technique, doing double duty here, because the
sabotage value *is* the reading the pre-repair row would have accepted. (The
line numbers pin the sabotage tree, in which the corrected expectations had not
yet been written.)

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
  [budgets §9](budgets.md#9-the-budget-line-reconciliation-ledger). When this was
  written, minting them meant editing `check_budget_ledger.py`'s pinned
  constants, which was `rf2-hic-089`'s surface and not this bead's. **That route
  no longer exists**: `check_budget_ledger.py` was deleted on 2026-08-30 by
  `bb3a92cd73` (`rf2-6c12m.8`), together with `check_facade_inventory.py` and
  `check_naming_census.py`, as part of shortening the invariants chain and the
  spine — so there are no pinned constants left to edit and no gate that would
  redden on a missing row. The rows are therefore not merely unfiled but
  **unmintable in the form this bullet described**, and what would settle it is a
  ruling on whether budget rows are still pinned by a gate at all. `U1`'s entry
  in §9.2 was updated to point here.
- **No remedy for §4's amplification, and no claim that it needs one.** The
  layer-2 contrast named at the end of §4 has now been measured — §4.1 — and
  measuring it is still not adopting it. The applications are unchanged, the
  saving it reports is `rows − 1` recomputations against a count that stays
  linear in the mounted grid, and whether `examples/grid` should be rewritten
  that way is a decision this page does not take.
- **No attribution of §5's `name` churn beyond React's own documented reason.**
  The trace shows what happens and the refusal experiment shows which pass owns
  it; why React 19 spells the atomicity guarantee that way is React's business
  and was not investigated.
- **Nothing about composition, IME or the caret.** A keystroke here is a scripted
  `input` event on a settled field. Composition exchanges are `rf2-hic-040`'s
  cross-browser matrix and are not this census's subject.
- **Nothing that can be read across to the topology tournament**, which landed
  while this census was being taken. [`topology-tournament.md`](topology-tournament.md)
  also has an `edit` operation and also counts work per keystroke, and the two
  sets of figures are **not** comparable: its arms vary *boundary placement* on
  the bench tree's arm-1 runtime, which its own §2.1 states, while every figure
  here is `implementation/hicasso` with the topology held fixed. Neither page
  re-derives the other and neither may be quoted for the other's population.
