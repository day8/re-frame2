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

The applications are the ones
[`rf2-hic-078`](../../../../implementation/hicasso/test/re_frame/hicasso/examples/)
built and `main` carries: `examples/editor` and `examples/grid`. **No number on
this page comes from the frozen bench prototypes** under
`implementation/freehand/test/re_frame/bench/`, which are a different tree
measuring different questions.

---

## 1. Pre-registration

**This section was written and committed before any measurement was taken**, so
that a prediction cannot be trimmed to fit a reading. The commit that carried it
is the one immediately before the first measured commit on `rf2-hic-045`'s
branch, and every figure in §4 onward is reported against it — including the
ones that came back wrong.

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

[kit]: ../../../../implementation/hicasso/test_kit/src/re_frame/hicasso/test/mounted.cljs

Two of these deserve their definition stated rather than assumed.

**The glass-write count excludes the keystroke's own write.** A scripted
keystroke reaches the same code path a real one does only by writing through the
element prototype's own `value` setter and then firing a real `input` event
(`editor.flow-dom-cljs-test/type-into!` established this). That first write is
the *user agent's*, not the runtime's, so it is subtracted. What remains is what
the framework wrote.

**The echo is read before any flush.** Every existing mounted witness calls
`hm/settle!` before asserting the glass, which commits whatever React has
scheduled. That is right for a correctness row and wrong for a latency one: it
measures the value after a flush the browser would not have performed yet. This
page reads `.value` at the instant `dispatchEvent` returns — inside the event
turn, before any paint could occur — and settles afterwards.

### 1.2 The predicted figures

Deterministic counters, so these are exact integers and not bands. A band would
be the wrong instrument: contention cannot move a monotone counter
([budgets §2](budgets.md#2-the-two-disposition-families-which-6-already-separates)).

| # | Subject | Stage | Predicted |
|---|---|---|---:|
| P1 | Editor, steady-state keystroke into `:title` | state writes | 1 |
| P2 | Editor, steady-state keystroke | subscription recomputations | 10 |
| P3 | Editor, steady-state keystroke | boundary runs | 1 |
| P4 | Editor, steady-state keystroke | glass writes by the runtime | 1 |
| P5 | Editor, first keystroke of a session | boundary runs | 2 |
| P6 | Grid 10×10, keystroke into cell `[3 4]` | state writes | 1 |
| P7 | Grid 10×10, keystroke into cell `[3 4]` | subscription recomputations | 111 |
| P8 | Grid 10×10, keystroke into cell `[3 4]` | boundary runs | 2 |
| P9 | Grid 5×5, keystroke into cell `[3 4]` | subscription recomputations | 31 |
| P10 | Grid 10×10, **refused** keystroke | state writes | 0 |
| P11 | Grid 10×10, **refused** keystroke | subscription recomputations | 0 |
| P12 | Either page | echo present before any flush | yes |

**P2, P7 and P9 are the predictions this page exists to test, and the reasoning
behind them is the part worth writing down before the answer is known.** Every
subscription in both applications is a **layer-1** reader — registered as
`(fn [db query-v] …)`, so its input is the whole of app-db rather than another
subscription's value. `re-frame.subs.memo`'s layer-1 wrapper memoises on that
input: the author's body is skipped when the new app-db is `=` to the last one
seen, and re-run when it is not. A keystroke moves app-db. So the prediction is
that **every mounted layer-1 cell that is derefed on the write path re-runs its
body**, and the count is therefore the number of live cells rather than the
number of changed addresses:

- editor: four `::field` cells, four `::committed` cells, `::revision`,
  `::dirty?` = **10**;
- grid 10×10: one hundred `::cell` cells, ten `::row-total` cells, one
  `::dimensions` cell = **111**;
- grid 5×5: twenty-five, five, one = **31**.

If P7 and P9 both hold, subscription recomputation **scales with the mounted
grid** while boundary runs do not, and the two stages of the same keystroke
answer the specification's narrow-update question differently. If they come back
at 2, the substrate is notifying more narrowly than the memo layer alone would
imply, and the page says so instead.

### 1.3 What a refusal would look like

Under [Shape 6](#8-what-this-page-does-not-conclude) a validity failure refuses
the figure rather than publishing it. For a deterministic census the validity
conditions are:

- the sabotage control does not go red when a figure is inverted — the row was
  skipped rather than run, and no figure it would have reported may be published;
- the counting wrapper changes a figure another instrument already pins (D1, D2,
  D7, D8) — the instrument is perturbing its subject and its own readings are
  void;
- the two applications disagree about a stage that is the same mechanism in
  both, with nothing in the topology to explain the difference.

---

<!-- Sections 2 onward are added by the measured commits that follow. -->
