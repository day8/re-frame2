# ER-01 — interpreted, compiled Hiccup, and `v/$`

Seat: EVIDENCE SPIKE. Bead `rf2-drpa3.182.2`, under
[`product-completion-setpoint.md`](../product-completion-setpoint.md) ER-01.

Measured: 2026-07-26, against `ec8b94557a` (branch `worker/er01-vdollar`).
Host: Windows 11, Temurin OpenJDK 21.0.10, single developer workstation under
concurrent load. Nothing here is a release-worker number.

Authority: the accepted setpoint's ER-01, plus D002, D008, D010 and D021. This
document reports evidence and a recommendation. It changes no architecture, and
the scaffolding that produced it was deleted with the same PR that added this
page — the measurement is the deliverable, not a standing harness.

## The recommendation, first

**Keep `v/$` a non-goal.** The evidence does not justify reopening the fork, and
the reason is not that `v/$` is slow. It is that `v/$` is *free* — and being free
buys nothing, because the work it would remove is not where the cost is.

Two findings carry that, and either one alone would be enough:

1. **At equal semantics, `v/$` and compiled Hiccup allocate the same.** The
   measured ratio is **1.011** — `v/$` allocating 1.1% *more*, not less — and it
   was 1.011 in all seven independent JVM runs that observed it, to three
   decimal places, with 0.00% within-run spread. There is no render-work
   argument for the fork in either direction.
2. **The two front ends are disjoint, and the setpoint's own preservation
   clause decides between them.** ER-01 asks for a comparison that preserves
   "descriptor, callback, ViewCell, structure, and evidence semantics." The
   `v/$` arm preserves the first four and fails the fifth, by construction: the
   compiled analyzer refuses a `$` body outright, so a declaration is written in
   one front end or the other and never both.

The honest limit on this result is stated in [What would change the
answer](#what-would-change-the-answer). It is not a small limit, and it is
narrower than the question.

## What was measured, and why that metric

The fixture is one fixed-size virtual table — the ER-05 witness shape — at two
sizes: a 40-row window of 8 columns (700 nodes) and a 200-row window of 8
columns (3,420 nodes). One fixture, one set of workload parameters, four arms.

The headline metric is **bytes allocated per render**, read from the JVM's
`ThreadMXBean/getThreadAllocatedBytes`, which is a monotonic per-thread counter
rather than a heap-occupancy sample. It is the right metric here for one
reason: on this machine it is *exact*. Across 40–60 samples per arm the minimum,
p50 and maximum allocation reading were the same number — 0.00% spread — in
every arm of every run but one, where a single arm moved by 0.01%. A wall-clock
comparison on the same box moved by more than the effect it was trying to
measure, so it is reported below as a direction and never as a percentage.

Allocation also answers the question ER-01 actually asks. All three front ends
build the same structural tree through the same canonicaliser, so the tree's own
allocation is common to all of them and the difference between arms *is* the
front end's overhead — which is the thing being compared.

Determinism came first. Every arm's structural output is compared for equality
before any number is read, at four fixture sizes, on both the JVM and
ClojureScript, and the arms are rendered interleaved (A, B, C, D, A, B, C, D…)
with `System/gc` immediately before each timed window.

## The four arms

| Arm | Front end | Declaration |
|---|---|---|
| **I** | interpreted | ordinary `v/defview`, Hiccup body |
| **C** | compiled Hiccup | arm I plus `{:compiled true}`, nothing else changed |
| **$** | `v/$` prototype | ordinary `v/defview` whose body calls the `$` constructor |
| **$k** | `v/$` + key proof | arm $ with the compiled emitter's `node/keyed-run` restored |

Arm $k exists for attribution and nothing else. Without it the raw `$` number is
uninterpretable, for the reason the next section gives.

The `$` prototype is deliberately the **strongest** version of the case: a macro
that resolves the literal tag's `.class#id` sugar and folds literal attribute
entries at macroexpansion, exactly as `emit-jvm/emit-element` does. A function
`$` would parse the tag at render and could only lose. Its whole expansion is

```clojure
($ :div.vtrow {:key (:id r) :data-index (:index r) :role "row"} kid)
;; =>
(re-frame.freehand.node/element
  {:tag      :div
   :sugar    ["vtrow"]
   :attrs    {:role "row"}                       ; folded at macroexpansion
   :dyn      {:data-index (:index r)}
   :key?     true
   :key-val  (:id r)
   :children (fn [] (re-frame.freehand.node/children kid))})
```

That expansion is the first result worth recording, before any measurement:
**`v/$` needs no new runtime.** Every symbol in it already ships and is already
reachable from every compiled view. `node/collect` already takes a `node?`
branch on an already-built child, so a `defview` body that returns nodes works
today with no substrate change at all. The prototype is 50 lines, and 50 lines
is not an underestimate of the runtime cost of `v/$` — it is the whole of it.

## Allocation

Bytes per render, p50, at 200×8 (3,420 nodes). One representative run; the
ratios from all three canonical runs follow.

| Arm | Bytes | vs interpreted | vs compiled |
|---|---:|---:|---:|
| I — interpreted | 3,709,352 | 1.000 | 1.244 |
| C — compiled Hiccup | 2,980,768 | 0.804 | 1.000 |
| $ — `v/$` | 2,393,000 | 0.645 | 0.803 |
| $k — `v/$` + key proof | 3,012,664 | 0.812 | **1.011** |

Read the last column before the third row tempts you. Arm $ appears to beat
compiled Hiccup by 20%, and that difference is **entirely** the key-uniqueness
proof arm $ was skipping. `node/keyed-run` realises each list site into a vector
and scans it for duplicate keys after React's string coercion — real allocation
buying a real correctness property that the interpreted walk does not check at
all. Restore it and the gap closes to 1.1%, in the compiler's favour.

Across three fresh JVMs the ratios were stable to the third decimal:

| Ratio | Run 1 | Run 2 | Run 3 |
|---|---:|---:|---:|
| compiled ÷ interpreted | 0.785 | 0.804 | 0.804 |
| `v/$` ÷ interpreted | 0.624 | 0.645 | 0.645 |
| `v/$`+keys ÷ interpreted | 0.793 | 0.812 | 0.812 |
| **`v/$`+keys ÷ compiled** | **1.011** | **1.011** | **1.011** |

Four earlier development runs, in their own fresh JVMs, gave 1.011 as well —
seven observations in total. The compiled-versus-interpreted ratio does drift by
about two points between JVMs; the arm-to-arm ratio that decides ER-01 does not
drift at all.

Two things follow.

**Compiling a Hiccup template saves about a fifth of its render allocation, and
no front end can save much more.** Roughly 80% of what a render allocates is the
output tree, which every arm must build identically. That is the ceiling any
front-end change is competing under, and compiled Hiccup already reaches most of
it.

**`v/$` reaches the same ceiling and stops there.** At matched semantics it is
1.1% worse. A programmer switching to `v/$` for speed would be paying an
architecture change for a number that is not there.

### Wall clock, as a direction only

At 200×8 the p50 ratio of arm $k to arm C was 0.951, 0.893 and 0.962 across the
three fresh JVMs — a 7-point spread on a 5-point effect. **No percentage is
defensible from that**, and none is offered. The honest statement is directional:
arm $k was not slower than compiled Hiccup in any run, which is what a 1.011
allocation ratio would predict. Anyone wanting a real timing claim needs a
pinned release worker, per D021.

## The evidence plane

This is where the arms stop being interchangeable.

```text
arm            manifest    view-cell
interpreted    ABSENT      nil
compiled       present     :elided
$              ABSENT      nil
```

The compiled declaration publishes a manifest — grammar, subscriptions, events,
slots, crossings, capabilities, html-sites, diagnostics — and its ViewCell is
`:elided`, the B2 capability that lets a sub-free boundary skip its reactive
cell entirely. Neither the interpreted arm nor the `v/$` arm has any of it,
because neither has an analysis for it to come from.

That is not a prototype gap I could close with more work. It is the fork:

> `re-frame.freehand compile error: macro …/$ is outside the compiler's audited
> expression set and could inject, duplicate, or defer a reactive call after
> lexical site analysis. Rewrite it with ordinary functions/control forms, or
> hoist the computation around the view template`

The analyzer refuses a `$` body. `{:compiled true}` and `$` cannot appear on the
same declaration, so `v/$` is not a syntax dial inside the compiled tier — it is
an alternative to it.

And the refusal is *correct*, which is the part that closes the question. The
reason `v/$` is attractive is that a `$` call is a value, so helpers compose and
D010's cliff disappears. The reason it cannot be analysed is that a `$` call is a
value, so the analyzer cannot know what any call site produced. Those are one
sentence. Make `$` analysable and it needs the same lexically-visible closed
grammar Hiccup needs, the same 10,276-line compiler, and it dissolves nothing.
Leave it composable and the manifest, cell elision, build-time refusal, `v/check`
and the SSR proof all go. There is no third setting.

Against ER-01's own words — *"preserving descriptor, callback, ViewCell,
structure, and evidence semantics"* — the composable `v/$` fails on ViewCell and
on evidence, and the analysable `v/$` is compiled Hiccup with different brackets.

## Authoring friction — the one axis `v/$` wins

D010 calls markup-as-value "Tier 1's deepest idiom and compilation's deepest
impossibility," and prices the crossing at the `v/markup` boundary. The spike
extracted the table's cell into an ordinary `defn` helper and put the same
source through each front end.

| Arm | Result |
|---|---|
| interpreted | renders |
| `v/$` | renders — output identical to interpreted, proven by equality |
| compiled Hiccup | **build error** |

So the ergonomic claim for `v/$` is real, not rhetorical: it makes
helper-returning-markup legal at compiled-tier cost, which is exactly the cliff
D010 says nothing dissolves. Recorded plainly, because it is the one thing the
fork genuinely offers.

It does not outweigh the evidence plane, for the reason above — the same
property that buys the ergonomics is the one that forfeits the analysis. But it
does leave one thing worth acting on, and it is cheap.

### A diagnostic-quality defect, found on the way

The compiled refusal over the helper cell is:

```clojure
{:rf.ui.compile/error :rf.ui.compile/unkeyed-list-item
 :message  "for body must be a keyed element/view/fragment — got expr.
            Keyed lists compile to direct JS arrays; missing key = build failure"
 :recovery [:key-each-row :extract-declared-child :keep-interpreted]}
```

The cell helper returns `[:div.vtcell {:key c} …]`. **The key is there.** The
analyzer cannot see it, reports it as missing, and its first recovery rung tells
the author to add a key the source already has. The real defect is an opaque
child result, and D010's own ladder for that is
`[:make-template-visible :extract-declared-child]`.

DC-05 requires that opaque child results "report one stable id, source,
expression summary, and executable recoveries." This one reports a stable id and
a source, and then names the wrong defect with a non-executable first recovery.
That is a DC-05 gap in shipped diagnostics rather than a contradiction between
the setpoint and the code — DC-05 is accepted work not yet done, and
`rf2-drpa3.182.6` is its plausible owner. Filed here so it is not lost with the
scaffolding.

## What it would cost to change, if the answer were different

Priced for completeness, since a recommendation to keep the status quo should
show the bill it is declining to pay.

- **Implementation complexity.** `implementation/freehand/compiler/` is 10,276
  lines across 14 namespaces. The `$` prototype is 50. That ratio is the whole
  seduction of the fork, and it is honest as far as it goes — but most of those
  10,276 lines implement analysis products (manifest, elision, `v/check`, a11y,
  build-time refusal, React/JVM parity) that `v/$` does not replace and cannot
  provide. The comparable subset — recovering element structure from a Hiccup
  literal — is a minority of it.
- **Bundle.** `v/$` adds zero runtime bytes; the expansion above names only
  already-shipped functions. Any bundle *saving* would have to come from
  deleting the compiler, which is a consequence of the architecture decision
  rather than evidence for it, so no bundle measurement was taken. Measuring one
  before the decision would have priced a deletion nobody has authorised.
- **Migration.** Every Hiccup body in the corpus, every guide fence, every
  conformance fixture, and both emitters' coverage suites. Not attempted.
- **AI editability.** Hiccup is data an agent can read, walk, splice and diff
  without expanding a macro. `$` forms are calls whose meaning depends on
  macroexpansion. On Principles' AI-first posture this is a point against the
  fork, though a soft one.

## What would change the answer

Stated plainly, because the result above is narrower than ER-01's full question.

1. **The React host is not measured here.** Both arms were compared on the JVM
   structural emitter and proven structurally equal on ClojureScript, but no
   `$` counterpart to the React emitter was built and no browser timing was
   taken. Production render cost lives in `emit-react`, and a large asymmetry
   there would reopen the performance half of the question. I do not expect one
   — both front ends bottom out in the same `createElement` calls — but I did
   not measure it and will not claim it.
2. **The evidence-plane argument is architectural, not empirical.** It would be
   overturned by an analyser that can read a composable `$` — which, as argued
   above, appears to be self-contradictory, but "appears" is the right word.
3. **A pinned release-worker timing run** could turn the wall-clock direction
   into a number. It would have to move a lot to matter against 1.011.

None of these is a reason to hold the recommendation. ER-01's deferred choice is
*"whether the `v/$` evidence justifies replacing compiled Hiccup,"* and on the
evidence gathered it does not: the performance case is empty at matched
semantics, and the ergonomic case is paid for in the evidence plane the setpoint
requires preserved.

## Provenance

- Fixture: fixed-size virtual table, 40×8 (700 nodes) and 200×8 (3,420 nodes).
- Sampling: 200 warm-up / 60 samples at 40×8; 100 warm-up / 40 samples at 200×8.
  Arms interleaved; `System/gc` immediately before each timed window.
- Determinism gates: structural equality across all four arms at 1×1, 3×4, 40×8
  and 200×8 on the JVM, and at 1×1, 3×4 and 40×8 on ClojureScript. Green before
  any timing was read.
- Suites at the measured revision: freehand JVM 1,104 tests / 7,433 assertions,
  0 failures; freehand CLJS 1,169 tests / 6,449 assertions, 0 failures.
- Instrumentation: `re-frame.freehand.bench.measure/allocated-bytes` and
  `now-ms`, the D021 harness's own readings.
- Scaffolding: commits `c5ef899f7c` and `ec8b94557a` on `worker/er01-vdollar`,
  reverted before merge. Recoverable from git if ER-01 is ever reopened.
