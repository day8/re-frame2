# The ordinary authoring facade — the freeze record

**The ordinary surface is frozen at nine names, two prop markers and one framework-minted
event id, on the evidence of two independent applications.** Every law below is transcribed
from [`specification.md` §4](specification.md#4-target-programming-model) and
[`lanes/ergonomics-api.md`](lanes/ergonomics-api.md), which pre-resolved them; this page applies
them and adds none. **One law could not be applied** — [§4](#4-the-one-law-that-could-not-be-applied) —
and that is the whole of what this freeze leaves open.

**No name is frozen or changed here.** [`specification.md` §4](specification.md#4-target-programming-model)
opens by saying so — *"These names are a provisional facade"* — and the naming ledger's own header
rule is that nobody renames mid-flow. Every naming question this checkpoint met is in
[`naming-findings-cp2.md`](naming-findings-cp2.md) for `rf2-hic-065` to consolidate. Prototype
spellings remain in use everywhere, including in this page.

The evidence this freeze reads is reported, not restated, in
[`checkpoint-2-slice.md`](checkpoint-2-slice.md). Read it before quoting anything here.

> **Amended 2026-08-13.** Three sentences on this page said, in the present tense, that the four
> event-value shapes were missing from the public door — in
> [§1](#1-the-membership-and-how-it-was-decided), in [§2](#2-the-frozen-laws) row 7, and in
> [§6](#6-what-this-record-is-not). Each was true when this page was written and had stopped being true
> before it merged: `rf2-lu0s` was fixed and closed by PR #8088, which put a *FOUR shapes* section in
> `defview`'s docstring carrying the Enter/Escape key-map example and naming the central composition
> gate. The three now read in the past tense and keep the finding, because a record that AGED is not a
> record that was WRONG. Nothing else moves —
> [§4](#4-the-one-law-that-could-not-be-applied) already reads the landed fix and what it reports that
> fix published is unchanged, and [`correction-ledger.md`](correction-ledger.md) carries the closure
> evidence clause by clause.

## Where each fact lives

| Fact | Owner |
|---|---|
| Which laws the ordinary surface has, in their normative words | [`specification.md` §4](specification.md#4-target-programming-model), [`lanes/ergonomics-api.md`](lanes/ergonomics-api.md) |
| Which of them the Phase 2 witnesses reached, and what that was like | [`authoring-report-slice.md`](authoring-report-slice.md), [`authoring-report-todo.md`](authoring-report-todo.md) |
| Whether the Phase 2 exit is met, and what was re-run to say so | [`checkpoint-2-slice.md`](checkpoint-2-slice.md) |
| Every spelling still open | [`naming-ledger.md`](naming-ledger.md), [`naming-findings-cp2.md`](naming-findings-cp2.md) |
| What the ordinary surface **is**, and which laws are frozen | this file |

## 1. The membership, and how it was decided

Two applications, chosen to be as unlike each other as the ordinary gets — a RealWorld-class
vertical slice (`rf2-hic-025`) and the canonical Todo class (`rf2-hic-086`) — were each written on
the public door and nothing else, and each has its import discipline asserted off the ClojureScript
analyzer rather than reviewed. **The Todo class reached a strict subset of the slice's names.**

| Frozen member | Reached by | The law it is frozen under |
|---|---|---|
| `defview` | both — 13 boundaries and 5 | §4 row 1; authoring law 1 |
| `sub` | both — every read in every body but one | §4 row 2; authoring law 3 |
| `use-subs` | slice only — one body, two reads | §4's read topology; below |
| `error-boundary` | slice only — two, one nested | §4 *error region* |
| `route-link` | both — 2 link sites and 3 filter tabs | §4.3 / routing integration |
| `reg-state` | both — one per-row concern each | HD-009; **see the caveat below** |
| `root!` | both — the entry point | §4 *root lifecycle* |
| `render!` | both — the `^:dev/after-load` hook | §4 *root lifecycle* |
| `unmount!` | slice only — the teardown half | §4 *root lifecycle* |

Two prop markers — `::h/value` and `::h/checked` — and one framework-minted event id, `::h/clear`,
which the Todo class reached and the slice did not.

**The headline datum's keyword count is corrected here, twice over.** `rf2-hic-026`'s own note
records the union as *"nine names and three keywords"*. The names are nine and the Todo class added
none, both of which hold. The keywords do not.

`::h/clear` was missing from the count. It appears in the Todo application twice — from the view on
Escape and from the commit handler's `:fx` — and it is listed separately above because it is not a
prop marker at all: it is an event id the framework mints on the author's behalf, reached by
`dispatch` rather than written at a position, and it rides on `reg-state` rather than standing on its
own.

`::h/revision` was in the count and should not have been. The slice reached for it, wrote it into two
fields and two handlers, and believed it — and `rf2-36bd` measured it doing nothing, so it came out.
**Neither witness application reaches it**, and where it does belong is [§5](#5-what-is-not-in-the-ordinary-surface).
The reset LAW is untouched by that and is frozen as [§2](#2-the-frozen-laws) row 9; what moved is only
the claim that an ordinary application meets it.

Beyond the nine and the three, the Todo class added exactly one thing to the union and **it is not a
name**: the key-map shape at an `:on-*` prop, `{"Enter" […] "Escape" […]}`. It is why that
application's `views.cljs` contains no callback at all. It is frozen as part of the event grammar in
[§2](#2-the-frozen-laws), and it was missing from the door when this page was written, which is
`rf2-lu0s` — since fixed and closed.

**One member is frozen conditionally.** `reg-state` is on this list because two independent ordinary
applications reached it, which is the evidence this freeze weighs. But
[`naming-ledger.md`](naming-ledger.md) row 3 carries a live recommendation to *remove `h/reg-state`
from the adaptor core and reconsider it in forms*, and `re-frame.hicasso.forms` has since shipped.
That is a membership question, not a spelling, and it is `rf2-hic-065`'s sitting to settle. **This
freeze records the demand and does not pre-empt the removal**: if the sitting removes it, `::h/clear`
and the `[:ui ::concern ikey]` tier go with it, and the two witnesses acquire a draft key each.

## 2. The frozen laws

Transcribed. Each row's normative sentence lives at its cited owner; where a row and its owner
disagree the owner governs and this row is the defect.

| # | Law | Source | Witnessed by |
|---|---|---|---|
| 1 | A `defview` is always a boundary; an ordinary `defn` is always inline composition. A direct Clojure call refuses. | ergonomics-api authoring law 1; §4 row 1 | both apps; `:rf.error/hicasso-view-called-directly`, lint `direct-view-call` (error) |
| 2 | A body is pure and may run, retry or be abandoned. Render owns nothing. | authoring law 2; §3.2 | Phase 1 kernel rows 3 and 4 |
| 3 | `sub` is ambient only during the active synchronous body. A helper may donate reads; a callback, promise, timer or lazy escape may not. | authoring law 3; §3.3 | Phase 1 kernel row 4's nine scenarios; lint `deferred-read`, `parked-read` (warnings) |
| 4 | Reads in branches and loops are legal, and a branch not taken contributes no edge. `use-subs` is the control: it declares its edge set, so an untaken branch still costs its edge. | §3.3; the facade's own two docstrings | slice `editor` (ambient, branching) vs `article-row` (grouped) |
| 5 | React owns keys, refs, hooks, effects, errors, concurrency, hydration and component identity. | authoring law 4; §3.2 | Todo N4 (`:ref` identity); §4 hooks-not-in-a-body |
| 6 | Event vectors are data. One explicit callback form. Ordinary `fn` values keep ordinary JavaScript callback semantics, and **position selects the contract**. | authoring law 5; §3.5; §4.1 | `impl.intent`'s position table; neither app needed the callback form once |
| 7 | A value at an `on-*` prop takes one of four shapes: an intent vector, a key map, the one callback form, or a plain function passed through. | §4.1; ergonomics-api §Surface boundaries | Todo N1 — **and the door did not say so when this page was written (`rf2-lu0s`, since fixed and closed)** |
| 8 | Key maps are composition-gated centrally, so an IME's Enter commits nothing. | §4.1 *must make IME composition behavior explicit* | `impl.intent` `composing?`; `test:hicasso-controlled`, three engines |
| 9 | Controlled fields are a framework law, not an application pattern: same-turn convergence, committed echo, rejection/normalization, caret and selection preservation, composition safety, and identity-preserving reset through an explicit revision. | §4.2; authoring law 6 | `test:hicasso-controlled` — 97 checks × 13 sections × 3 engines. **The revision half is witnessed there and by NEITHER ordinary application (`rf2-36bd`, closed); [§5](#5-what-is-not-in-the-ordinary-surface) states its real population.** |
| 10 | Owned control attributes beat forwarded attributes by presence, not truthiness. | authoring law 7 | testbed `empty` arm (`""` is falsy and still wins) |
| 11 | Buffered drafts, touched/submit-attempt validation and mutation status are **not** in the boundary shell. | §4.2 final paragraph | `re-frame.hicasso.forms`, separately reachable and proven unreachable from the door |
| 12 | Every refusal carries a stable id, source coordinate, view, position, offending value, expected shape and a recovery. | §3.6; ergonomics-api §Editor and diagnostic ergonomics | 74 live complaint rows, each emitted and rowed in Spec 009 |
| 13 | No public option selects an execution mode. | authoring law 9 | Phase 0 exit clause, already discharged |
| 14 | Optional capabilities live in named namespaces with bundle-reachability proofs, and the door names none of them. | §4 final paragraph; ergonomics-api §Recommendation | `check_optional_module_reachability.py` — motion, overlay, native, forms all unreachable from the door |

Rows 1, 3, 6, 7, 8, 9, 12 and 14 were re-measured by this checkpoint; the rest are transcribed
against their standing witnesses. What each measurement was is
[`checkpoint-2-slice.md` §3](checkpoint-2-slice.md#3-what-was-re-run-and-what-it-measures).

## 3. The reserved-data vocabulary

[`specification.md` §4](specification.md#4-target-programming-model) fixes it at four — *event value,
checked value, explicit prevention, and controlled-value revision* — and
[`dispositions.md`](dispositions.md) HS-07 carries the same four with a server-side refusal arm.
The runtime's marker list (`re-frame.hicasso`, ns docstring) names eight. The difference is
accounted for and no part of it is a finding:

| Marker | Status |
|---|---|
| `::h/value`, `::h/checked` | the two an ordinary application writes. Both witnesses, at every controlled element. **Ordinary.** |
| `::h/prevent` | frozen, and reached by neither witness — [§4](#4-the-one-law-that-could-not-be-applied) is why. |
| `::h/revision` | frozen as a law and reached by neither witness. Its population is a reset that leaves every other read the body makes `=` — see [§5](#5-what-is-not-in-the-ordinary-surface). |
| `::h/navigate` | framework-minted by `route-link`, never author-written. Already rowed as a reserved-vocabulary addition the brief's list omits — [`naming-ledger.md`](naming-ledger.md) row 35. |
| `::h/mounting`, `::h/unmounting` | the **motion module's** vocabulary. They stay on the door's marker list because moving a namespace is not renumbering its keywords ([`naming-ledger.md`](naming-ledger.md) row 31). Not ordinary — neither application reached them. |
| `::h/clear` | `reg-state`'s clear event id. Frozen conditionally with `reg-state` — see [§1](#1-the-membership-and-how-it-was-decided). |

So the four the specification reserves are all frozen, and **two of them are what an ordinary
application actually writes**. Of the other four, three are optional-module or framework-minted and
the fourth rides on `reg-state`. That two of the reserved four go unreached by two deliberately broad
applications is a fact about their populations rather than a case for shrinking the vocabulary:
`::h/prevent` is reached the moment an anchor acts as a button, and `::h/revision` the moment a field
rejects or normalises what is typed.

## 4. The one law that could not be applied

**Explicit prevention.** Both pre-resolved sources say the same thing in the same words:

- [`specification.md` §4.1](specification.md#41-events): *"Explicit prevention is uniform rather than
  special-cased for submit."*
- [`lanes/ergonomics-api.md`](lanes/ergonomics-api.md) §Surface boundaries and exclusions:
  *"Prevention is explicit everywhere; there is no submit-only auto-prevention."*

The shipped surface has a submit-only auto-prevention
(`implementation/hicasso/src/re_frame/hicasso/impl/intent.cljs:841-843`), states it as a
census-weighted policy default at `:175-178`, and the Todo application depends on it — *Enter adds a
to-do* is `{:on-submit [::events/add]}` with no key test, no `preventDefault` and no callback
(`authoring-report-todo.md` N5, recorded as a positive).

A freeze that is *deterministic application of pre-resolved dispositions* cannot apply a disposition
the code contradicts, and cannot amend a published normative sentence to make it fit. So this row is
**open**, filed as `rf2-j6fn` and rowed in [`correction-ledger.md`](correction-ledger.md). It has since
got wider rather than narrower: `rf2-lu0s`'s fix put the exemption on the **door** too — `defview`'s
docstring now tells authors that `:on-submit` "is the only position that prevents by default" — so
three published surfaces now say two different things. The
checkpoint's recommendation is recorded there and is evidence, not a ruling: keep the auto-prevent
and amend both sentences, because the asymmetry is principled — a modifier-click on a real link must
still open a tab, so a click needs the explicit opt-in and a submit has no such counterpart.

**Nothing else in [§2](#2-the-frozen-laws) is held on this.** It is one row, and the other thirteen
are applied.

## 5. What is NOT in the ordinary surface

Deliberately chosen breadth, twice, and neither application reached any of these:

| Not ordinary | Where it belongs |
|---|---|
| `hfn` — the one callback form | real callbacks, render props and imperative APIs (§4.1). An application with two text fields, a checkbox, a select and five buttons never needed one, because an intent vector said everything. |
| `hframe` | [`naming-ledger.md`](naming-ledger.md) row 18 holds a **retire** recommendation — §4 already says core's frame doors are the frame doors. |
| `defhost`, `as-element`, `as-component`, `portal` | §4.3 host interop — Phase 3 freezes them from host witnesses, not from these. |
| `::h/revision` | a reset that leaves **every other read the body makes `=`** — measured, not assumed (`rf2-36bd`). `impl.codec`'s `revision-key` is the mechanism: the revision is a value the body reads and its change *re-runs the body*, and the re-run's commit re-asserts the model over the DOM — so any re-render already does it. An ordinary discard moves three of the slice editor's reads and needs nothing. The populations that do need it are a **rejecting or normalising** field, and a DOM drifted by a route React never saw (autofill, an extension, a live composition); the controlled testbed's `revision` / `revision-strict` arms are the corpus's witnesses for both. |
| `motion/presence` | the optional motion module. |
| the whole `n/*` native tier | §5's rungs 3–5; Phase 3 freezes its grammar and ABI. |
| `h/handler` (§4's spelling of the callback form) | the same row as `hfn`; the spelling is [`naming-ledger.md`](naming-ledger.md) row 1's. |
| grouped-`use-subs` ergonomics beyond one call site | one grouped read between two applications. Consistent with the operator's standing ruling that it sits below the ergonomics bar. |

**Being absent from this list is not a demotion.** Each has a real use case named in the
specification and its own phase to be frozen in. What two ordinary applications establish is that
none of them is *ordinary*, which is the only question this freeze asked of them.

## 6. What this record is not

It is not a naming decision: [§1](#1-the-membership-and-how-it-was-decided)'s spellings are the
prototype's and every question about them is in
[`naming-findings-cp2.md`](naming-findings-cp2.md). It is not a claim that the ordinary surface is
finished — Phase 4 extends the application coverage matrix and may find a tenth name, which this
page would then have to be amended to admit, with its witness. It is not evidence about the host or
hot-path facades, which Phase 3 freezes from their own witnesses. And it is not a statement that
every frozen law is witnessed by the Phase 2 applications: row 9's revision half is not — `rf2-36bd`
measured that, the slice's inert bookkeeping came out, and the law keeps its own three-engine witness —
and row 7 is witnessed in the applications and was absent from the door when this page was written,
which is `rf2-lu0s`, since fixed and closed.
