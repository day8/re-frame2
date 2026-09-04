# Checkpoint 3 — the native-tier adoption review

> **Currency, 2026-09-04 (`rf2-l67a`, `rf2-nf8w`). This page reviews a tier that no longer ships, and
> is a dated record rather than a statement about the tree today.** It ran on 2026-08-14 at
> `main`@`f455316fbf`, its §2 re-run was taken at `main`@`77bee1b9b7`, and its newest amendment is
> 2026-08-21 (`rf2-r3dgc`). It was **reconciled to `main`@`4f54988b07` by this pass**, which re-scored
> nothing.
>
> **The event its rows do not reflect.** On 2026-08-29, `aa01f0e8a6` (`rf2-6c12m.31`, wave 2 of
> ruling `rf2-6c12m.3` Option A) shrank `re-frame.hicasso.native` from 1,116 lines to 82 and deleted
> the native grammar: `$`, `props`, `defcomponent`, `memo`, `lazy`, `component`, `marker`,
> `tier-sentinel`, `prop-slots`, `el`, `props*`, `declared-server`, `server-policies` and
> `check-child!`, with their seven `:rf.error/hicasso-native-*` emitters, and the **eight `native_*`
> suites — 91 deftests, 5,234 lines**. `use-sub` and `use-frame` are the whole public surface that
> survives. So the `n/$`, `n/props`, `n/defcomponent` and `prop-slots` this page names throughout
> **do not exist at tip**, and neither do the suites its evidence columns cite. The verdict, the
> twelve misses and the freeze rule stand as the record of 2026-08-14; none of them can now be moved
> by measurement, because the subject is gone.
>
> **What was re-run.** `rf2-nf8w` re-took this record's §2 Correctness section against
> `main`@`4f54988b07` on 2026-09-04. Two of its four conjuncts pass, one is unreproducible for the
> reason just given, and the result is written into the `rf2-s52w` row of
> [`correction-ledger.md`](correction-ledger.md) and into [Row 5](#row-5--server-and-hydration)
> below. **Row 5's score does not move.**

**Verdict: the Phase 3 exit is NOT MET, and the host, outward-bridge and hot-path facade are
therefore NOT frozen.** The canonical native-tier checklist is not green — no row of the eight is
green on every clause it states — and the exit's own second conjunct, *a measured hot boundary … meet
the island budget*, is **unaddressed**: the island parity budget `C7` has never been read, because
no package-resident clock instrument exists to read it.

Checkpoint 3 (`rf2-hic-038`) ran on 2026-08-14 in a worktree of its own, at `main`@`f455316fbf`, by a
reviewer who wrote none of the artefacts it reviews. **It took no measurement and opened no
measurement window**, and it still has not: what §2 asks for is re-runs and sabotages, not a clock.
Those were outstanding when this record was first written and were **taken on 2026-08-14** by a
re-dispatch of the same bead, against `main`@`77bee1b9b7` — three clean-checkout re-runs and six
independent sabotages, all six reddening. See
[§4](#4-what-the-re-run-covered-and-what-it-did-not-change), which also says why neither that earlier
gap nor its closing could have produced this verdict.

**The refusal is the deliverable.** Twelve misses are filed ([§5](#5-the-misses)), three of them
`correctness` — and the re-run added no thirteenth, which is the only outcome a sabotage family that
bites can produce. But the headline is narrower than the miss list and is worth separating from it: this
checkpoint declines to certify that a native island performs like the component it replaces, because
nobody has measured whether it does, and the artefacts say so in their own words. Everything else
here is secondary to that.

## Where each fact lives

| Fact | Owner |
|---|---|
| What Phase 3 must deliver, and the words its exit is written in | [`specification.md` §12](specification.md#12-action-programme) |
| The eight rows the native tier is judged on | [`lanes/hot-path-architecture.md`](lanes/hot-path-architecture.md#canonical-native-tier-acceptance-checklist) |
| Every numeric threshold, and which are pinned | [`specification.md` §6](specification.md#6-performance-contract), [`budgets.md`](budgets.md) |
| Which SSR/hydration row each surface sits on, and its witness | [`dispositions.md` §2.1](dispositions.md), **not** the matrix — see [`rf2-1qws`](#5-the-misses) |
| The review protocol this record discharges — §1 Completeness, §2 Correctness, §3 Quality | `rf2-hic-038` |
| What each miss is, its severity, and what it takes to close it | [`correction-ledger.md`](correction-ledger.md), and the bead each row names |
| Every spelling this checkpoint met | [`naming-findings-cp3.md`](naming-findings-cp3.md) |
| Whether the Phase 3 exit is met | this file |

Bare `§1`, `§2` and `§3` mean the review protocol's three sections; specification sections are always
written as `specification.md §N`. Findings are **not** restated here: their text lives in the ledger
row and their full detail in the bead.

## 1. Why this checkpoint ran now, and what it inherited

`rf2-hic-038`'s fence required a decision rather than a default: run against the census plus the
documented clock refusal, or wait for `rf2-m6i0` to unblock `rf2-hic-036`'s clock half. **The tracker
settled it, not the reviewer.** `rf2-m6i0` closed on 2026-08-14 **without** unblocking that half —
its own close note records that `rf2-hic-036`'s clock table *"is still withheld entire because topo/
HAS NO CLOCK DRIVER"* — and the clock half was split to `rf2-w01c`, which is open, deferred, and
carries the operator's direction in its description: *"get on with fully implementing hicasso, run
measurement later."* The waiting branch therefore had nothing left to wait for.

What was inherited is the **estimand rule**, applied here for the fourth time: score what the evidence
addresses, mark the rest **unaddressed**, and never substitute one estimand for another. This
checkpoint had one live opportunity to break that rule and declined it — see
[§3, row 8](#row-8--performance).

`rf2-hic-036`'s deterministic work census is read as landed and is not re-adjudicated here. Its clock
table is read as what it is: **a documented refusal with a named blocker**, which is a legitimate
state, and which this checklist does not consult in any row.

## 2. The exit, conjunct by conjunct

> *"Exit when the canonical native-tier checklist is green and a measured hot boundary can move to
> native React, meet the island budget, and remain diagnosable from the same Xray surface. Freeze the
> host, outward-bridge, and hot-path facade from those witnesses."*
> — [`specification.md` §12](specification.md#12-action-programme)

Four conjuncts. Unlike Phase 2's, they do not all hold.

| # | Conjunct | State |
|---|---|---|
| 1 | the canonical native-tier checklist is green | **NOT MET** — [§3](#3-the-checklist-row-by-row) scores no row green on every clause it states |
| 2 | a measured hot boundary can move to native React | **MET, structurally** — a hot boundary does move, under one root and one frame, with no second state owner; `three_way_parity_dom_cljs_test.cljs` paints three routes byte-identically in one commit, and `native_abi_dom_cljs_test.cljs` proves the bridge resolves no frame of its own |
| 3 | … meet the island budget | **UNADDRESSED** — `C7` is `UNPINNED` with evidence `— (none)` |
| 4 | … and remain diagnosable from the same Xray surface | **MET for interpreted boundaries; UNADDRESSED for a native subtree** — the causal slice carries no native subject at all ([`rf2-t2d3`](#5-the-misses)) |

Conjunct 3 is the one that decides this record, and it needs no judgement call, because two
independently authored pages say the same thing in their own words:

> *"they are **the deterministic half of `C7` only**: they say there is no interposed work, never how
> long a render takes, so `C7` stays `UNPINNED`"* — [`budgets.md`](budgets.md)

> *"**No package-resident clock instrument exists.**"* — [`per-keystroke.md`](per-keystroke.md), which
> refuses to pin `U1` for the same reason

A third fact compounds it, and it is separate from the measurement gap: the checklist asks for the
**ratified** island parity budget, and `C7` carries no ratification. `budgets.md` disclaims ratifying
anything and `specification.md` §6 makes every budget a proposal until the product operator ratifies
it against the registered physical profiles. Only `C2` has been through that. **So the phrase has no
ratified referent yet** — the budget is a registered proposal with no reading.

**None of this is a failure.** No island has been measured and found slow. The estimand rule's whole
point is that "unmeasured" and "missed" are different verdicts, and the honest one here is the first.
What it is not is *green*.

## 3. The checklist, row by row

Each row is scored on the clauses **it** states, in three verdicts: **met** (evidence establishes it),
**unmet** (evidence exists and a required clause is contradicted or absent from a witness that ought
to carry it), **unaddressed** (no evidence either way). A row is green only when every clause is met.
The checklist's own rule is that *"a red row blocks publication … no row is waived by success on
another row"*, so the count below is not a score out of eight.

The evidence is substantial and mostly excellent; the table records what it does **not** reach.

### Row 1 — Native-form grammar
**Not green.** 59 deftests across five files cover the shape clauses thoroughly: omitted/literal/`nil`/
`#js` props, `n/props` on maps and objects, dynamic element children, trailing and nested children,
keyword/string/component/JS heads, `:key`, SVG, custom elements, canonical-slot collisions with
non-member controls, and refusals for hiccup children, intents in props and maps as children — each
carrying a stable `:rf.error/id`, `:where`, `:reason` and `:recovery`.

- **Unmet — the refusals are not source-located.** The required result says *source-located refusal of
  Hiccup/intent semantics*. No test asserts `:source` on any grammar refusal, and it is structurally
  unreachable as landed: `native.cljc`'s `component` does not wrap with `error/traced-boundary`, so a
  grammar refusal raised inside an `n/defcomponent` body carries neither `:view` nor `:source`. The
  only `:source` assertions in the tier are about the **declaration map**. → [`rf2-dva6`](#5-the-misses)
- **Unaddressed — no macro-expansion fixtures exist**, in any form; the artefact has zero JVM tests by
  design, so the compile-time refusal path — a real path, since `$` calls `check-child!` on child
  *forms* at expansion — is witnessed by nothing this repository runs. `native.cljc` says so itself.
  → [`rf2-h63i`](#5-the-misses)
- **Met, and better than named** — the *client DOM and React server witnesses against handwritten
  `createElement`* both exist and are strong: a nine-row matched corpus written three ways, byte
  equality against a literal `react/createElement` arm under `renderToString`, with the instrument
  validated first by a row that proves it can report a difference.

### Row 2 — Component ABI and identity
**Not green.** One raw-JavaScript props/children ABI is measured — not asserted about a data structure
but read off a server render (`map?=false`) and off a live mount — and it survives `n/memo` (marker
kept where a raw `react/memo` loses it, with that loss as the control), `n/lazy`, ref forwarding,
CLJS and JavaScript heads, and display/source metadata. The zero-wrapper identity claim is exact:
the element type is `identical?` to the author's function.

- **Unmet — the HMR clause has no native witness.** The required result ends *"display/source metadata
  and HMR"*, and the deciding evidence names *one HMR replacement cycle*. The real reload gate —
  `testbed/hmr_spec.cjs`, eight sections driven by a live `shadow-cljs watch` rewriting a source
  line — exercises **no native component**: `hicasso_hmr_testbed/views.cljs` requires only `react`,
  `re-frame.core` and `re-frame.hicasso`, and contains zero native references. What is landed for the
  tier is an **in-process simulated re-mint** that never re-evaluates a module. → [`rf2-iq0a`](#5-the-misses)
- **Unaddressed — "one matched component exercised through every wrapper"** does not exist: coverage is
  spread across four distinct components, and the file's own roster asserts ten *mechanisms* ran, not
  one component through ten wrappers. Same finding.
- **Unaddressed — "there is no parallel fast ABI"** is argued in a docstring and gated by nothing; no
  surface census would redden if a second props door were added.

### Row 3 — Frame and store lifecycle
**Not green.** The shared-substrate claim is structural and real: both hooks resolve through
`collector/resolve-frame!` and hand `collector/hook-entry` to `useSyncExternalStore` — the same doors
the boundary shell uses, with no parallel implementation. Five of eight named sub-states are covered
with instruments that read what React cannot forge (cell-table keys, registration identity, body-run
counts, residue censuses): no-provider failure (both hooks, each naming itself), two frames,
frame replacement as same-id reincarnation, StrictMode double mount, and unmount with exact cleanup.

- **Unmet — Activity hide/reveal, aggravated by the documentation.**
  [`react-compatibility-notes.md`](lanes/react-compatibility-notes.md) closes its Activity section with
  *"Activity should be used through native React construction—Hicasso-native, UIx, or a `defhost`
  declaration."* That sentence **assigns** Activity to this tier rather than deferring it, and no
  landed row drives `n/use-sub` or `n/use-frame` under an `<Activity>`. The Activity suites are
  substantial and green — and contain zero native references. A published route with no witness.
  → [`rf2-9ywe`](#5-the-misses)
- **Unaddressed — Suspense and retry/abandonment** have no native-hook row. Both results exist at the
  boundary-shell tier and are inherited by argument rather than by measurement. → [`rf2-sr19`](#5-the-misses)
- **Unaddressed — the dependency check proving no UIx import does not exist** as an executable
  assertion. → [`rf2-b3gy`](#5-the-misses)
- **Partial — the matched UIx consumer exists but is never driven through the lifecycle states.** One
  row matches a read across all three routes and agrees; the DOM-lane trio reads no subscription at
  all, and the node-lane UIx arm resolves its frame explicitly rather than ambiently, so the claim is
  agreement on the value rather than parity of resolution.

### Row 4 — Same-root interop
**Not green.** The inward direction is complete across all three native routes, in one body, one
frame, one commit, painting byte-identical DOM. The no-second-owner claim is asserted rather than
described. Retained callbacks are the strongest evidence in the row: a memoising vendor that knows
nothing about Hicasso, a nine-carrier grid with four controls and a liveness row, and abandonment and
reincarnation each with a negative control beside it. Provider and compound components run through a
real vendor family with context, slots and a render prop. Teardown is exact everywhere.

- **Unmet — no UIx parent renders Hicasso.** The required result names *Hicasso-native, UIx and raw
  React parents*. `h/as-component` is reached by a native parent on a fiber and by a raw React parent
  in the node lane only; no UIx parent renders a bridged view anywhere in the tree. → [`rf2-ap7w`](#5-the-misses)
- **Unaddressed — "both embedding directions across two frames"** holds for the outward bridge; the
  inward three-route tree runs under a single frame. Same finding.
- **Thin** — no error crossing involves a UIx subtree, and none runs in the outward direction.

### Row 5 — Server and hydration
**Not green**, though the native tier's own rows are the best-evidenced part of it. `HS-24`–`HS-30` —
intrinsic and component-headed `n/$`, `n/props`, `n/defcomponent`, both hooks, and the memo/lazy/ref
helpers — are witnessed through real `react-dom/server` bytes on both policy arms, and the witness
records that the defect it was written against (a Client-only island appearing in the server bytes
because the recorded `:server` policy was consulted by nothing) was found and fixed. **No instance of
a semantic-tree claim standing in for server bytes was found anywhere**, which was this section's
sharpest question.

**[Amended 2026-09-04, `rf2-l67a` — the surfaces this paragraph scores no longer exist.]** Of the set
it names, only *both hooks* survives: `re-frame.hicasso.native` is 82 lines at tip whose entire
public surface is `use-sub` and `use-frame`, and `n/$`, `n/props`, `n/defcomponent` and the
memo/lazy/ref helpers were deleted with the rest of the grammar by `aa01f0e8a6` on 2026-08-29
(`rf2-6c12m.31`). The witnesses too: the eight `native_*` suites that carried the `react-dom/server`
byte readings on both policy arms went in the same commit. **The reading is kept exactly as taken** —
it was true of `main`@`f455316fbf` on 2026-08-14 and this note is not a re-score — but a reader must
not carry the sentence forward as a claim about the package. The one clause of it that a 2026-09-04
re-run could still take is the last: `rf2-nf8w` re-read the surviving parity witnesses and found each
still naming the equality it proves, with server bytes and element shape asserted by separate rows.

- **Unmet — mismatch attribution is unreachable for the outward bridge.** The row requires *mismatch
  attribution*; an outward-bridged root is built by the consumer's own `createElement`, so
  `impl.mount/hydrate-root!` cannot adopt it and a hand-rolled `hydrateRoot` installs no Spec 011
  reporter. Not a missing test — unsatisfiable without a door. → [`rf2-s52w`](#5-the-misses)

  **[Amended 2026-08-21, `rf2-r3dgc`.]** Everything above still holds as a mechanism — a root the
  consumer opened themselves carries no framework reporter, because `onRecoverableError` is an option
  of an individual root. What has gone is the premise in the first clause: **the row no longer
  requires mismatch attribution without qualification.** PR #8646 (`137bd927db`, `rf2-0brem`) rewrote
  the *Server and hydration* required result in
  [`lanes/hot-path-architecture.md`](lanes/hot-path-architecture.md#canonical-native-tier-acceptance-checklist),
  and the SSR/hydration matrix's blanket sentence with it, to owe the state only on roots a re-frame2
  door opens — citing [Spec 011's hydration-mismatch
  detection](../../../../spec/011-SSR.md#hydration-mismatch-detection), which had said so normatively
  all along. `rf2-s52w` closed the same way on 2026-08-15, a **scope rather than a gap**, confirmed
  and measured rather than repaired, and [`dispositions.md`](dispositions.md)'s HS-21 row records
  that half. So this clause was never unmet, only unstated, and it is stated now. **This amendment
  re-scores nothing**: it takes no measurement, runs no section of the protocol, and leaves Row 5's
  *Not green* and this page's verdict where §2 put them. Whether the row's score moves is a §2 re-run's
  to say.

  **[Amended 2026-09-04, `rf2-nf8w` — the §2 re-run has now said, and the answer is no.]** §2
  Correctness was re-taken against `main`@`4f54988b07` on 2026-09-04, in a worktree of its own, by a
  reviewer who wrote none of the fixes. **Row 5 stays *Not green*, and this page's verdict is
  unmoved.** The reason is not that the clause failed — it is that the row's subject was retired
  under it. `aa01f0e8a6` (2026-08-29, `rf2-6c12m.31`) deleted the native grammar and its eight
  suites, so `HS-24`–`HS-30`'s `n/$`, `n/props`, `n/defcomponent` and the memo/lazy/ref helpers are
  gone, the outward bridge this bullet is about is gone with them, and
  `native_abi_dom_cljs_test.cljs` — the 1,028-line file carrying the *a-consumer-built-root-hydrates-
  a-bridged-subtree-with-no-framework-reporter* witness that `rf2-s52w` landed, and the plant that
  falsified it — was deleted in the same commit. **A score cannot move on a re-measurement that has
  nothing left to measure**, and inventing a substitute subject would be re-scoring, which this
  amendment is forbidden to do exactly as the one above it was. What the re-run *could* take is
  recorded in the `rf2-s52w` row of [`correction-ledger.md`](correction-ledger.md): the three-way
  matrix and the bundle gate both green with captured exit **0**, the parity-witness conjunct read
  and passing, and this record's own sabotage 3 replanted and reddening its surviving witness with
  captured exit **1**. **The mechanism the bullet names is untouched and still true**:
  `impl/roots.cljs:23` still states that `onRecoverableError` is an option of an *individual*
  `hydrateRoot`, so a root the consumer opens carries no framework reporter. It is the *bridge*, not
  the mechanism, that has gone.
- **Quality — the row points at the wrong document.** Its deciding evidence is the SSR/hydration
  matrix, which is a table of **policy** carrying no witness column and no test citation. The witness
  ledger is [`dispositions.md` §2.1](dispositions.md). A reviewer following the release checklist
  finds obligations and no evidence. → [`rf2-1qws`](#5-the-misses)
- **Recorded, and owned elsewhere** — `HS-11`/`HS-14` remain Client-only under a measured, unrepaired
  obstruction: the package's only server path emits no counterpart to the adoption closer, so a tree
  minting a `useId` hydrates into an id mismatch. `HS-33` is measured to satisfy **neither** policy.
  Both are outside the native tier and are already carried by open beads; this checkpoint files
  nothing against them and names them so the final audit does not read row 5's native half as the
  whole matrix.

  **[Amended 2026-08-21, `rf2-r3dgc`.]** The phrase *the package's only server path* was true when
  this record was written and stopped being true the same day. `re-frame.hicasso.server/render`
  landed on 2026-08-14 as `30317bfe0e` (PR #8236, `rf2-b6jkj`), some hours after this page was first
  filed, and `impl/roots.cljs` now names it one of **two** minters of the adoption window rather than
  the one. The second half of the sentence went with it: that module does not emit the bare app
  subtree but calls `impl.mount/tree` with the request's own window handle — the same function the
  hydrating door calls — so it emits the Fragment-and-closer shape, and its namespace docstring
  names HS-11's obstruction 2 as the thing it was built to answer. **The dispositions are not re-scored here, and
  this bullet's point is unchanged**: HS-11, HS-14 and HS-33 are outside the native tier, this
  checkpoint still files nothing against them, and whether the obstruction has actually lifted is
  [`dispositions.md`](dispositions.md)'s to measure and say — it still records the obstruction as
  standing. Filed as `rf2-lau0u` so the reading is taken rather than assumed.

### Row 6 — Dependency and rent
**Not green**, and the met half is exemplary. The interpreted-only bundle is proved to contain no
native-tier runtime by a gate with four **reachable positive controls** — strings that must be
present in the same real `:advanced` bundle — a fail-closed empty-input check, a premise check that
reddens if a sentinel's source string is renamed, a self-test that plants each sentinel and drops each
control individually, and a too-eager battery of shape-adjacent legal strings that must stay green. A
source-side gate parses `ns` forms to prove nothing outside the tier requires its door.

- **Unaddressed — the "nor UIx" clause has no gate on either side.** The law reads *"an interpreted-only
  production dependency graph and bundle contain neither native-tier runtime **nor UIx code**"*. The
  bundle gate declines that half explicitly and honestly — the measured bundle *does* contain UIx,
  because the exemplar picks an adapter, and `rf-uix-sub-` is carried as a **present control** — and
  refers it to the source-side gate as *"the source-side gate's kind of question"*. The source-side
  gate never asks it: its `native` row guards the door edge only. Each artefact is individually
  honest; the clause falls through the seam between them. The property holds today — `hicasso/src`
  names UIx only in docstrings — but nothing would redden if it stopped. → [`rf2-b3gy`](#5-the-misses)
- **Unaddressed — the second clause has no artefact at all.** *"Native bundles contain no UIx unless
  the application imports it"* has no native-tier release build to measure. Same finding.

### Row 7 — Diagnostics
**Not green**, with a genuinely strong core. The causal slice's seven links are prefix-evidenced, its
envelope is unconditionally `:complete? false` with a stated loss reason, basis and join are rendered
separately, and **four links carry real mutation rows, each with an in-row positive control asserted
first on the same runtime** — including one that asserts the mounted census was available and *not
substituted*, which is the trap of answering a reverse edge with a forward one. Production erasure is
proved by five sentinels with controls, chained into the release build behind its own self-test, so a
bundle cannot be produced unscanned. `rf2-hic-037` added no evidence machinery and touched no code
under `implementation/` — the row's *"adds no production evidence machinery"* clause, met by
construction.

- **Unmet — there is no opaque foreign subtree.** The deciding evidence names *one causal trace with an
  opaque foreign subtree*. The slice runs entirely on interpreted Hicasso boundaries; `tools/xray/`
  contains no native-tier subject anywhere. Links 5–7 are `:host-opaque` because React owns commit and
  paint for **any** boundary — not because a foreign subtree was crossed. The witness does not reach
  its named scenario. → [`rf2-t2d3`](#5-the-misses)
- **Recorded, not filed** — *"Xray names and **times** the boundary"* is met as subscription time;
  boundary self time was killed as a decision (`rf2-hic-081`) on a grain argument. A ruled decision,
  not a miss.

**The advisor is met, and it is the best-judged artefact this checkpoint read.** §3 required
spot-checking three recommendations against the measured pressure class; all three hold, and the
third is the interesting one. (i) A hot `:computation` owner is routed **below** the substrate — narrow
or memoize the subscription — because every native route would move the markup and keep the cost.
(ii) A boundary with an oscillating read set gets rung 2 regardless of how hot it is, so the hottest
and coldest boundary with the same owner get the same rung: the route is looked up on the **owner**,
never on the rank. (iii) Pressure that lands in lowering, React or layout arrives as `:unattributed`
and gets **measure first** — never rungs 3, 4 or 5 — because Xray cannot see those classes, and the
refusal names a non-Xray authority for each. The consequence is asserted as a property over the
classifier's whole output: **from this evidence the advisor never recommends a native route**, with a
non-vacuity control that hands the ladder the three unmeasurable owners directly and gets rungs 3/4/5
back. An advisor that recommended an island it could not price would have been the easy thing to
build. This one refuses, at source, and proves the refusal is not vacuous.

### Row 8 — Performance
**Not green, and the gap is unaddressed rather than failed.** What landed is the deterministic half,
in full and to a high standard: the three routes share server bytes, painted DOM, element shape, props
key-sets, ref behaviour, teardown, re-render identity and hook tape — the island's
`["useContext" "useSyncExternalStore"]` pair is indistinguishable from a boundary shell's — and
`D14`–`D16` read `0` wrappers, `1` author-owned slot and `0` unwrapping hops, against a UIx arm that
reads one generated component and one `argv` hop.

- **Unaddressed — the clock half, entire.** `C7` is `UNPINNED`, evidence `— (none)`. No figure has ever
  been compared to `5%` or to `1 ms`. `C8`, the escape-benefit threshold, is `UNPINNED` with no
  population; its nearest reading prices a two-arm hiccup-versus-`n/$` comparison, not a retained
  escape site, and is itself `UNRESOLVED` because its range crosses the 20% line.
- **Unaddressed — the runs are not interleaved.** The deciding evidence names *interleaved three-way
  client and server runs*; what landed is a deterministic `cljs.test` suite with no round loop, no
  arm-order guard and no sampling. Interleaving rigs exist in the bench tree — none carries a
  Hicasso-native or handwritten-React arm.
- **Unaddressed — the budget is not ratified**, as [§2](#2-the-exit-conjunct-by-conjunct) sets out.

**Nothing is filed against this row.** Every part of it is already owned: `rf2-hic-071` holds `C7`'s
clock half, the ladder re-pin and the package-resident clock instrument that half needs; `rf2-w01c`
holds the deferred measurement lane. Filing a thirteenth bead here would duplicate a live owner, and
the ledger's job is to make the gap visible to `rf2-hic-064`, which [§6](#6-the-freeze-rule-applied)
does by other means.

**The estimand rule was live here and is recorded as taken.** `budgets.md` argues — correctly, and in
its own interest — that the structural reading is *"a stronger reading than a timing, not a weaker
one"*, since two arms handing React the same element type with the same props object leave no
interposed work for a stopwatch to find. That argument is sound and this checkpoint does not dispute
it. **It is still not a reading of `C7`.** `C7` asks how long a render takes relative to the same
component mounted directly; "there is nothing between React and the body" does not answer it, and
accepting it as an answer would be exactly the substitution the rule forbids. The page itself refuses
the substitution in the same paragraph, which is why this row is scored unaddressed rather than met —
**scoring it as the artefacts already score themselves, rather than more generously.**

## 4. What the re-run covered, and what it did not change

**This section used to record an omission. It now records the work.** When this report was first
written, §2's clean-checkout re-runs and its sabotage family had not been taken — three workers were
live, the bead's fence gated the section on a free box, and the bundle gate is reachable only through
a full `:advanced` release build. That was filed as a miss against this checkpoint itself
([`rf2-5nijq`](#5-the-misses)), and MERGED-PR AUDIT #8176 then reopened `rf2-hic-038` — the exact owner —
rather than leaving the row to a separate bead. The re-dispatch took the section on 2026-08-14,
against `main`@`77bee1b9b7`, in a worktree of its own, and wrote none of the artefacts it exercises.

**The paragraph this section was built around is kept, because it is still the reason the omission
never threatened the verdict:**

> A sabotage that fails to redden, or a re-run that fails, **adds** a finding. Neither can turn an
> unaddressed clause into a met one. The verdict here is *not met*, reached on documentary facts that
> no execution could have reversed — `C7` carries no reading, and no re-run creates one.

That argument decided in advance what the re-run was allowed to do, and the outcome is the benign
one: **it added nothing.** The verdict, the twelve misses and the freeze rule below are untouched.

### 4.1 The clean-checkout re-runs

| What | Result | Captured exit |
|---|---|---|
| Node lane (`test:cljs`) — the three-way matrix's structural half, the fence, both hooks, the HMR suites, the Client-only server arms | 13,905 tests / 70,217 assertions / 0 failures / 0 errors | **0** |
| Browser lane (`test:browser`) — the three-way matrix's DOM half, the two-frame isolation row, the ABI/bridge suites, the causal slice over a native subject | 1,542 tests / 9,783 assertions / 0 failures / 0 errors | **0** |
| Bundle-rent gate — a real `:advanced` + `goog.DEBUG=false` release build, then `check_bundle_isolation.cjs` self-test and scan | 4 sentinels absent, 4 positive controls present; the erasure gate green beside it | **0** |

### 4.2 The sabotages

Six, planted **one at a time** in source — never in a test — each run alone, and each restored and
verified with `git hash-object` against the committed object rather than by reading a diff, because a
patch that never applied and a clean restore have the same diff. Every plant was anchored to a single
line. **All six reddened, each naming its own row.**

| # | Target row | The plant | What reddened | Exit |
|---|---|---|---|---|
| 1 | Dependency and rent | the native tier made reachable from the release entry, exactly as a leak would arrive | `check_bundle_isolation.cjs` named the surface, the sentinel `rf2:hicasso-native-tier` and the remedy — while its own self-test and the erasure gate stayed green | **1** |
| 2 | Native-language leakage | the intent-in-prop refusal in `prop-slots` made unreachable, so hiccup semantics pass the fence | 10 assertions across four files, including the matched pair at `native_fence_cljs_test.cljs:180-181` and the fence row inside the three-way matrix itself | **1** |
| 3 | Frame and store lifecycle | the frame dropped from `use-sub`'s cell key, so every island's read shares one cell | `native_hooks_cljs_test.cljs:144` and `three_way_parity_cljs_test.cljs:585` | **1** |
| 4 | Frame and store lifecycle, on its strongest row | the same plant, taken to the browser lane | `two-frames-are-two-cells-and-an-island-cannot-see-across`, its inward-door twin, and 23 more — and both suites' `declared-population` rosters fired, reporting the states that stopped being reached | **1** |
| 5 | HMR residue | the cell reaper made unreachable, so a retired generation keeps its key | all four residue rows in `hmr_remount_cljs_test.cljs`, including the in-suite control `a-leaked-stale-registration-turns-the-cleanup-witness-red` | **1** |
| 6 | Server and hydration | the `:client-only` branch bypassed, so a gated island renders into the server bytes | `a-client-only-island-is-absent-from-the-server-bytes` and three sibling policy rows, plus the suite's own false-render control | **1** |

Three properties of the runs are worth recording, because each is a way this family could have
reported a pass it had not earned. **The counts held**: every node-lane sabotage ran the same 13,905
tests and 70,217 assertions as the control, so no plant crashed a namespace and stopped the ones
after it. **The browser sabotage's assertion count fell** — 9,672 against the control's 9,783, on an
identical 1,542 tests — which is the expected shape when async rows reject early rather than a sign
of skipping, and the roster guards named the unreached states outright. And **the harness's own
report of a run's exit code disagreed with the captured one on every red above**, reporting success
each time; the numbers in these tables are the ones the runner wrote to its own exit file.

**Two limits, stated rather than left to be found.** The node lane cannot reach frame isolation: the
two-frame row skips itself there with `":node-test has no React DOM"`, which is why sabotage 4 exists
as a separate row rather than being folded into 3. And the plants are *deliberate* faults, so they
prove these witnesses are **non-vacuous**, not that they are complete — row 8's clock half is
unaddressed for reasons no sabotage touches, and [§3](#3-the-checklist-row-by-row) is unchanged by
all six.

### 4.3 What this adds to the misses

**Nothing, and that is the finding.** A sabotage that had stayed green would have been a
`correctness` row — the ledger's own definition names *a sabotage control that fails to redden* — and
none did. So the twelve below stand as twelve; `rf2-5nijq` closes as withdrawn-and-discharged rather
than as a thirteenth; and the one thing this section can now say that it could not before is that the
native tier's witnesses **bite**. That was previously argued from their existence. It is now measured.

## 5. The misses

Twelve, filed as real `bd` issues with rows in [`correction-ledger.md`](correction-ledger.md). Three
are `correctness`, eight `coverage`, one `quality`. Their text lives in the ledger and their detail in
the bead; the table below is an index.

| bd id | Row | Finding, in one line | Severity |
|---|---|---|---|
| `rf2-dva6` | 1 | Grammar refusals are not source-located, and are structurally unreachable inside an island body | correctness |
| `rf2-t2d3` | 7 | The causal slice has no opaque foreign subtree — no native subject exists in `tools/xray/` | correctness |
| `rf2-9ywe` | 3 | A lane doc assigns Activity to this tier; no native-hook row runs under `<Activity>` | correctness |
| `rf2-h63i` | 1 | No macro-expansion fixtures; the compile-time refusal path is witnessed by nothing | coverage |
| `rf2-iq0a` | 2 | The native tier is absent from the real HMR gate; no one component crosses every wrapper | coverage |
| `rf2-b3gy` | 3, 6 | The "nor UIx" clause has no executable gate on either side | coverage |
| `rf2-sr19` | 3 | Suspense and retry/abandonment have no native-hook witness | coverage |
| `rf2-ap7w` | 4 | No UIx parent renders Hicasso; inward interop runs under one frame | coverage |
| `rf2-s52w` | 5 | Outward-bridge mismatch attribution is unreachable by construction | coverage |
| `rf2-1qws` | 5 | The row's deciding evidence points at a policy table with no witnesses | quality |
| `rf2-e0d2` | — | The native namespace's public var surface is unclassified before a freeze | coverage |
| `rf2-5nijq` | §2 | This checkpoint's own sabotage re-runs were not taken — **withdrawn as a duplicate and the obligation discharged**, [§4](#4-what-the-re-run-covered-and-what-it-did-not-change) | coverage |

## 6. The freeze rule, applied

Contract freezing is a deterministic application of the spec's Phase-3 rule, kept separate from the
findings above. The rule conditions the freeze on the exit: *"Freeze the host, outward-bridge, and
hot-path facade **from those witnesses**"* — the witnesses the same sentence's exit clause names.
[`specification.md`](specification.md) states it again from the other side: *"Phase 3 freezes the
grammar and ABI only when every row of the canonical native-tier checklist passes."*

**The exit is not met, so the freeze does not fire.** No name freezes and no law freezes here.
`n/$`'s grammar, the component ABI, the host contracts and the outward bridge all remain
provisional, and every prototype spelling stays in use — which is also the standing rule while
`rf2-hic-065` is unpublished.

This is deliberately unlike Checkpoint 2, which met its exit and froze the ordinary authoring facade,
and deliberately like Checkpoint 1, whose exit is a conjunction with an open conjunct. The difference
between the two open conjuncts is worth naming for the final audit: **Checkpoint 1's is a measured
red awaiting a decision; Checkpoint 3's is an unmeasured blank awaiting an instrument.** They do not
close the same way, and neither closes by re-reading the other's evidence.

## 7. What would change this verdict

Stated so the release audit can check it rather than re-derive it. Conjunct 3 is the whole of it:

1. A **package-resident clock instrument** exists (`rf2-hic-071`), and
2. `C7` is **read** on it against the same-instrument anchor the ladder re-pin supplies, and
3. the reading is compared to `5%` or `1 ms` and the budget is **ratified** by the product operator on
   the fields `specification.md` §6 requires.

Then conjunct 3 is adjudicable — met or missed, either being a verdict where today there is none. The
checklist's other rows need their twelve misses discharged under
[the closure rule](correction-ledger.md#the-closure-rule), which is ordinary work and none of it is
blocked on an instrument.

**Nothing here asks for the budget to be widened, and nothing here asks for it to be met.** An island
that misses `C7` is simplified or removed; `specification.md` §6 says thresholds do not widen to keep
one. This checkpoint takes no position on which way the reading will go, having declined to take it.
