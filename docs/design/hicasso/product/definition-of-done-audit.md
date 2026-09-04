# The §13 definition-of-done audit — `rf2-hic-064`

**Two verdicts, and they are different claims.**

> **Implementation audit complete: NOT CERTIFIED.**
> Six of the seventeen §13 bullets are red on the artefacts, two more are not
> scoreable as written, and the correction ledger carries one unresolved
> `coverage` row. The green definition fails on the ledger alone, before any
> bullet is read.
>
> **Product definition of done: RED.**
> Bullet 16 — *"Two pilot applications ship substantial screens without bespoke
> support"* — has no evidence of any kind. `rf2-hic-063` is open, no counted log
> exists, and the ratified route to one runs through an operator act
> (`rf2-kmqx3`) that has not happened.

Neither verdict is close. Nothing below widens a threshold to reach a green, and
where a check the protocol orders could not be run, the row says so and says what
would settle it rather than substituting a reconstruction.

## Provenance

| Fact | Value |
|---|---|
| Base | `main`@`aa0ee76ae4`, rebased onto `main`@`09d4d84a80` before publication |
| Date | 2026-09-04 |
| Re-verified after the rebase | the only non-tracker change between the two commits is one bench test file, `p0_ladder_structural.test.cjs`; no finding below reads it, and both doc gates were re-run on the final base |
| Reviewer | a worker who authored none of the artefacts under review |
| Checkout | a linked worker worktree; `scripts/assert-worker-worktree.sh` captured exit **0** |
| §13 text | taken verbatim from [`specification.md` §13](specification.md#13-definition-of-done), never from a summary |
| Findings | seven corrective beads filed, [§6](#6-the-corrective-beads) |

The bullets are numbered 1–17 here in the order §13 states them. The numbering is
this page's, for citation; §13 itself is an unnumbered list.

**This audit ran against a standing fence, and that is recorded rather than
glossed.** `rf2-hic-064`'s newest note, dated 2026-09-03, reads *"FENCED — DO NOT
DISPATCH UNTIL: rf2-hic-063 closes"*, and `rf2-hic-063` is open at this base — a
fact re-checked at source here, not inherited. Its tracker dependency manifest, by
contrast, is clear: both edges (`rf2-hic-090`, `rf2-hic-088`) read closed, and all
thirteen ids in its prose *Depends* line read closed. So the fence is a scheduling
note rather than a tracker edge, and the delegated ruling of 2026-09-02 —
*implementation audit complete is certifiable without pilots* — is what makes
running it now coherent. **The consequence is stated plainly: the product verdict
is red by construction, on the very bead the fence names, and no reading below
should be taken as evidence that the pilots question has been examined on
evidence. It has not, because there is none.**

## 1. The green definition, tested first

`rf2-hic-064`'s green definition is a conjunction: **every §13 bullet green AND
zero unresolved correctness/coverage rows in the correction ledger.** The second
conjunct is cheaper to test, so it is tested first.

[`correction-ledger.md`](correction-ledger.md) carries **34** rows. **33 read
`closed`. One does not** — the `rf2-hic-038` §2 Correctness row carrying
`rf2-s52w`, severity `coverage`, status `resolved`.

The ledger's own rule decides what that means, and it is not ambiguous:

> For the rf2-hic-064 gate, *unresolved* means **any status other than `closed`**.
> A `resolved` row fails the gate exactly as an `open` one does.

**So the audit is non-green on the ledger conjunct, independently of the pilots
and independently of every bullet below.** The row states what it still waits on
in its own words — *"`closed` therefore waits on the §2 Correctness re-run
alone"* — and both halves it was previously fenced to have since landed:
`dispositions.md`'s HS-21 row as `6cc52f60fb`, and the *Server and hydration*
required result in [`lanes/hot-path-architecture.md`](lanes/hot-path-architecture.md#canonical-native-tier-acceptance-checklist)
as `137bd927db`. Neither was a re-run, and the row says so.

The re-run has never been dispatched. The ledger's closure rule sends a
non-trivial re-run to a **closure bead** worked by someone who did not write the
fix; the 2026-08-20 keeper pass recorded that it filed none, because this bead's
notes forbid successor recorders, and asked for a ruling on which reading wins.
That ruling was never recorded, so the obligation has sat unowned for fifteen
days. **`rf2-nf8w` is that closure bead, filed by this audit.**

## 2. Completeness — the seventeen bullets

Scored on the evidence that exists, one bullet at a time. **Green** means the
artefacts establish the bullet. **Red** means an artefact contradicts it or the
obligation has no witness. **Not scoreable** means the bullet names a subject
that no longer exists.

| # | §13 bullet (abbreviated; the spec is authoritative) | Verdict | What decides it |
|---|---|---|---|
| 1 | Package independent of the benchmark tree; documented compatibility/release policy | **green, qualified** | [§2.1](#21-bullet-1--package-independence-and-release-policy) |
| 2 | Language small, internally coherent, source-located, no execution-mode switch | **green, qualified** | [§2.2](#22-bullet-2--the-language) |
| 3 | Collector substrate and two-hook shell explicit, measured choices | **green** | [§2.3](#23-bullet-3--substrate-and-shell) |
| 4 | Full correctness matrix passes with sabotage controls and exact cleanup | **red** | [§2.4](#24-bullet-4--the-correctness-matrix) |
| 5 | Representative app, controlled grid, compound host, virtualizer, imperative SDK use only public surfaces | **amber** | [§2.5](#25-bullet-5--public-surface-only-witnesses) |
| 6 | React-library interop is a core contract, incl. same-root native islands | **not scoreable** | [§2.6](#26-bullets-6-7-and-11--the-two-canonical-tables) |
| 7 | Every inventoried public surface passes its SSR/hydration policy row | **red** | [§2.6](#26-bullets-6-7-and-11--the-two-canonical-tables) |
| 8 | Ordinary performance meets the ratified budgets | **red** | [§2.7](#27-bullets-8-9-and-10--the-budgets) |
| 9 | Read-free boundary shell meets the byte-exact line or a ratified disposition | **green on the second disjunct** | [§2.7](#27-bullets-8-9-and-10--the-budgets) |
| 10 | Qualified bulk evidence; warm allocation carries no product claim | **green on the non-claim; red on the bulk half** | [§2.7](#27-bullets-8-9-and-10--the-budgets) |
| 11 | Every row of the canonical native-tier acceptance checklist passes | **not scoreable** | [§2.6](#26-bullets-6-7-and-11--the-two-canonical-tables) |
| 12 | Testing ladder supported, honest opacity, browser coverage for browser laws | **amber** | [§2.8](#28-bullet-12--the-testing-ladder) |
| 13 | Xray explains reads and causal work, accounts for loss, guides hot extraction, respects privacy, erases from production | **green, with one verb qualified** | [§2.9](#29-bullet-13--xray) |
| 14 | Every motivating use case assigned to one of five layers | **amber** | [§2.10](#210-bullets-14-and-15--coverage-mine-per-keystroke-resource-demand) |
| 15 | Requirements mine and per-keystroke current; resource spike has an adopt/stop verdict | **red on both currency clauses; green on the verdict** | [§2.10](#210-bullets-14-and-15--coverage-mine-per-keystroke-resource-demand) |
| 16 | Two pilot applications ship substantial screens without bespoke support | **red** | [§2.11](#211-bullet-16--the-pilots) |
| 17 | No primary doc, production path or live tool consumer depends on re-frame.ui or Freehand | **green** | [§2.12](#212-bullet-17--donor-independence) |
| — | *Closing paragraph*: the bounded Node service is a v0 deliverable, built and witnessed | **green** | [§2.13](#213-the-closing-paragraph--the-bounded-node-service) |

Six red, two not scoreable, three amber, six green. The arithmetic is not the
verdict — one red is enough — but the distribution is worth having, because five
of the six reds are the same defect wearing different clothes: **a record that
was true when it was written and has not been re-read since the tree moved under
it.**

### 2.1 Bullet 1 — package independence and release policy

**Independence: established, mechanically.** `implementation/hicasso/deps.edn`
carries two production dependencies, both in-repo (`day8/re-frame2` and
`day8/re-frame2-ssr`), and no benchmark coordinate. Nothing under `src/` or
`test_kit/src/` `:require`s a `re-frame.bench` namespace — nine textual mentions
exist across both trees and every one is inside a docstring or a `;;` comment.
`check_optional_module_reachability.py` enforces it with a two-row
`FORBIDDEN_IMPORTS` roster (UIx and the bench tree) and a `--self-test` that pins
both directions including a near-miss spelling. **Re-run for this audit and
captured exit 0** — see [§3](#3-correctness--what-was-run).

**Release policy: documented, with three named deficits.**
[`release-policy.md`](release-policy.md) states a compatibility surface, a
fourteen-row compatibility matrix, a versioning scheme and an upgrade policy. It
also states, in its own words, that three of the gates it cites were deleted on
2026-08-30, that the coordinate it documents is **not the one that will ship**
(*"the first tag therefore cuts on `io.github.day8/re-frame2-hicasso`"*, a string
that appears nowhere in the tree), and that **no version has been cut of
anything** — `VERSION` reads `0.0.1.alpha` and the repository carries no release
tag. Its own §1.1 acceptance is recorded as *blocked*, not merely unexecuted.

The bullet asks for a documented policy, not a published artefact, so it is
green. But the deficits are what bullet 16 is waiting on, and
`release-policy.md:152-157` still prints a reproduce block invoking two deleted
scripts, which `rf2-87iu` carries.

### 2.2 Bullet 2 — the language

**Small and coherent: established.** The ordinary authoring facade is frozen at
nine names, two prop markers and one framework-minted event id, on the evidence
of two independent applications, with **fourteen of fourteen** laws frozen
([`facade-freeze.md`](facade-freeze.md)). One law was amended before it could be
applied, and the amendment is recorded rather than absorbed.

**No execution-mode switch: established.** No compiler, analyzer or dual mode
exists, and `rf2-6c12m.3`'s K1–K3 reopen trigger — *reopened only if one is ever
proposed as necessary* — has not fired.

**The qualification is the instrument, not the claim.** This bead's own
dependency line orders a re-run of the naming-ledger completeness census — *no
provisional name ships*. **That census cannot be run.** `check_naming_census.py`
was deleted on 2026-08-30 with `check_budget_ledger.py` and
`check_facade_inventory.py`, their CI jobs and the fast-PR block, on the stated
and sound reasoning that *closed programme records were being gated as if live*.
The last reading stands as a dated snapshot — 106 public names across 11 shipped
namespaces, 0 unrostered, self-test exit 0 over 12 checks including a seeded
positive control — and there is no instrument at tip that can reproduce it.

**An ad-hoc substitute was attempted and is reported as a failure rather than a
result.** A hand-rolled enumeration of shipped public definitions at tip misses
the `(def ^{:doc …})` forms in `motion.cljs` and `overlay.cljs` entirely and
picks up `defview` samples written inside docstrings — precisely the two failure
modes the seeded control existed to catch. It is recorded here so a later reader
does not mistake it for a census. `rf2-87iu` carries the disposition.

One substantive thing did move and is worth stating, because it discharges what
held this bead for three weeks: **naming-ledger row 18 is executed, not
stopped.** `rf2-t32wg` was ruled by the operator on 2026-08-30 and `hframe` is
gone — zero occurrences under `implementation/hicasso/src/`.

### 2.3 Bullet 3 — substrate and shell

**Green.** [`substrate-decision.md`](substrate-decision.md) discharges all three
things `rf2-hic-018` asks for: an explicit choice of the subscription substrate
under the collector, the two-hook boundary ceiling frozen with its measurement,
and a disposition of the read-free shell against the byte-exact line. It is a
delegated, operator-overturnable verdict and says so at the head, using the
adjudication markers that let a reader separate what the record decided from what
the page concluded. Optional capabilities add no universal hook: the reachability
gate confirms *motion, overlay, native, forms, server, substrate unreachable from
the public door*, captured exit 0.

The shell's byte disposition is scored separately at bullet 9.

### 2.4 Bullet 4 — the correctness matrix

**Red, and it is the programme's own reading rather than this audit's.** Three of
the four phase exits the correctness matrix is composed of are recorded NOT MET,
by the checkpoints that took them:

| Record | Verdict, verbatim |
|---|---|
| [`checkpoint-1-kernel.md`](checkpoint-1-kernel.md) | *"the correctness half of the Phase 1 exit is CONFIRMED; the exit as a whole is NOT MET"* |
| [`checkpoint-2-slice.md`](checkpoint-2-slice.md) | *"the Phase 2 exit is MET, and the ordinary authoring facade is frozen"* |
| [`checkpoint-3-native.md`](checkpoint-3-native.md) | *"the Phase 3 exit is NOT MET, and the host, outward-bridge and hot-path facade are therefore NOT frozen"* |
| [`checkpoint-4-coverage.md`](checkpoint-4-coverage.md) | *"the Phase 4 exit is NOT MET."* |

**The sabotage controls are the strong half and they are genuinely strong.**
Checkpoint 3 ran three clean-checkout re-runs and six independent sabotages, all
six reddening. Checkpoint 1's row-2 and row-8 controls each run armed and
disarmed halves that redden in opposite directions, so the armed half is itself
the proof the mutation still bites. The `rf2-s52w` witness reads a zero with the
package's own door as its control and is falsified by plant. This audit re-ran one
sabotage control of its own and it bit — [§3](#3-correctness--what-was-run).

**What makes the bullet red is not the controls but the matrix.** A conjunction
with a failed conjunct is failed however good the rest of it is, and three of four
conjunctions are failed. The individual failures are scored under bullets 7, 8 and
11.

**And the records are stale, which is a second and independent problem.**
`checkpoint-4-coverage.md`'s newest amendment is dated 2026-08-15; the amendment
that rescoped mismatch attribution landed as `137bd927db` on 2026-08-21, and a
search of that page for the amendment's commit, PR number or bead ids returns
nothing at all. The same page still records `rf2-s52w` as *open* and its cause as
*"a missing door … the door is the operator's call"* — a root cause the ledger row
itself records as overtaken on both clauses, since `re-frame.hicasso.server/render`
landed as `30317bfe0e` on 2026-08-14. `rf2-l67a` carries the currency repair.

### 2.5 Bullet 5 — public-surface-only witnesses

**Amber: three of the five hold and are fenced; two are outside every mechanism
that could say so; and the positive witness was retired.**

The representative app (`examples/slice/`), the controlled grid
(`examples/grid/`) and the virtualizer (`examples/ledger/`) use only public
surfaces. Every `ns` form in all nine example packages was read: the complete set
of non-sibling namespaces they name is `re-frame.hicasso`, `re-frame.core`,
`re-frame.routing`, `re-frame.resources`, `re-frame.adapter.uix`,
`clojure.string` and `["react"]`. The `rf2-hic-078` editor and grid applications
are in the tree.

The compound host and the imperative SDK each `:require`
`re-frame.hicasso.impl.codec`, `.impl.collector` and `.impl.mount`, and the SDK
additionally requires the test kit. **Their subjects are built on public
surfaces** — the internals are the harness's, for mounting and observation — so
this is not read as a breach. What it is: the bullet's two hardest witnesses sit
outside `re-frame.hicasso.examples.fence-cljs-test`'s population twice over, being
outside `examples/` and being suites.

**The positive claim lost its instrument.** The `*surface-cljs-test*` suites that
pinned a roster of permitted doors no longer exist, and four pages still cite them
in the present tense as the enforcing witness. The surviving fence is a
four-family blocklist and cannot make the positive claim; it also names a second
root that does not exist at tip, guarded so its absence is silent. `rf2-60jv`.

**No document scores this bullet.** Its words appear in exactly one place in the
tree: `specification.md` §13 itself.

### 2.6 Bullets 6, 7 and 11 — the two canonical tables

**This is the audit's most consequential completeness finding, and it is a fact
about the artefact rather than a judgement.**

`implementation/hicasso/src/re_frame/hicasso/native.cljc` is **82 lines** and
defines **two** public names, `use-frame` and `use-sub`. Its own docstring states
the end state: *"The two React hooks that join a React island to the Hicasso frame
it is mounted in … and nothing else. An island is a UIx `defui` or a raw React
function component, mounted through `h/defhost` or `[:>]`."* That is `rf2-6c12m.3`
executed. **`n/$`, `n/props`, `n/defcomponent`, `n/memo` and `n/lazy` do not
exist.**

Both tables §13 links to are still written over them:

* Bullet 11 cites the **canonical native-tier acceptance checklist** and adds
  *"no partial parity result substitutes for that checklist"*. Eight of its lines
  carry the retired names, including its first row — *"the provisional `n/$`
  grammar handles omitted and literal props, explicit `n/props` dynamic
  maps/objects …"* — and its second, on an ABI surviving `n/defcomponent`, memo
  and lazy.
* Bullet 7 cites the **public-surface SSR/hydration matrix**. Four of its lines
  carry them, including two whole matrix rows: *Intrinsic `n/$` form* and
  *`n/defcomponent`, component-headed `n/$`, memo/lazy/ref helpers*.
* `specification.md` itself carries eight, including the §4 facade-table rows that
  publish `n/$`, `n/props` and `n/defcomponent` as shipped names.

**Bullets 6 and 11 are therefore not scoreable as written.** A reader of the
definition of done is sent to a checklist whose leading rows describe a grammar
the package does not have, and told nothing substitutes for it.

**Bullet 7 is scoreable and is red**, because its failures are not the retired
rows. Checkpoint 4's conjunct B is NOT MET on `HS-33`, `HS-17`, `HS-18` and
`HS-34`, each re-read at source for this audit in `dispositions.md`:

| Id | State at tip, from its own cell |
|---|---|
| `HS-33` | *"NEITHER POLICY HOLDS TODAY — measured, and this is the one row in the table that is out of the matrix rather than merely unproved"* |
| `HS-17` | Client-only; a `:slots`-declared named position is *"witnessed neither way"* |
| `HS-18` | Client-only; *"`h/as-element` has no server-render row anywhere in the tree"* |
| `HS-34` | *"THERE IS NO MODULE"* — no `re-frame.hicasso.routing` namespace exists |

`HS-21` sits behind the `137bd927db` rescoping and is the subject of `rf2-nf8w`;
whether it still fails conjunct B is a §2 re-run's to say, and nobody has said.

The teaching corpus is already clean — `docs/core/hicasso/` carries **zero**
occurrences of the retired names, and so does `dispositions.md`. The drift is
confined to the specification and the two lane tables, which is the small end of
the repair and the load-bearing end of the harm. `rf2-aunp`.

### 2.7 Bullets 8, 9 and 10 — the budgets

The reconciliation ledger at [`budgets.md` §9](budgets.md#9-the-budget-line-reconciliation-ledger)
was re-parsed cell by cell for this audit. **49 rows: 32 `MET`, 5 `BREACH`, 3
`UNRESOLVED`, 9 `UNPINNED`.** Seventeen of forty-nine are not green.

**Bullet 8 is red, and the user-visible half is the sharpest part of it.** The
bullet names *user-visible, regression, mount, update, and heap* budgets by name:

| Row | Line | Status |
|---|---|---|
| `U1` | echo within one 60 Hz frame at p95 | `UNPINNED` |
| `U3` | ≤ 100 ms p95 for broad operations | `UNPINNED` |
| `U4` | dragging and animation inside the frame budget | `UNPINNED` |
| `C1` | ≤ 5% regression on the same witness and instrument | `UNPINNED` |
| `C3` | ≤ 1.25x the best relevant adapter on broad updates | `UNPINNED` |
| `C4` | no sustained 1.5x as ordinary Hicasso | `UNPINNED` |
| `S3` / `C6` | ≤ 10% per-read regression | `UNRESOLVED` — 1,417 vs Reagent 948 per read |
| `S6` / `C2` | 1.10x cold mount | `BREACH` — 1.1718x [1.1263–1.2190] |

§9.4 states why, and the reason is an instrument rather than an edit: the two
landed clock drivers mount a synthetic bench page inside one `flushSync` window,
which is *a mount and not a paint*, while `U1`–`U4` are the slice application's
own interactions. The `p95` half of the gap has since been closed in source; **the
population half is untouched and it is the one that governs.** `UNPINNED` is the
honest label and this audit does not move it.

**Bullet 9 is green on its second disjunct, and the row stays red.** The shell
breaches — `S1` 1,100 B and `S2` 1,095 B against a line frozen at 1,024 B — and
the bullet's alternative is *"a separately ratified, prospective operator
disposition"*. That exists: a scoped acceptance ruled on `rf2-0xx2`, recorded on
five fields in [`budgets.md` §5](budgets.md#5-the-read-free-boundary-shell-the-byte-exact-line-now-frozen-at-1024-b),
with the line **unmoved**, the rows kept `BREACH` and the acceptance deleted rather
than kept as a floor the moment a qualifying arm lands under the line. It prices
the breach; it does not pass it, which is what the bullet's second disjunct is for.
**One qualification a reader should carry**: the ruling is *delegated and
operator-reversible*, so "ratified" here means ratified under standing delegation,
not confirmed in person. That is a distinction for the operator, not a
re-scoring — flagged, not scored.

**Bullet 10 splits.** The warm-allocation non-claim is **green** and exemplary:
`S7` reads *"no publishable claim"* and the instrument's failure to qualify is
recorded as the reason. The qualified-bulk half is **red**: the tournament
published its deterministic work census and recorded its clock table NOT
INSTRUMENTED, concluding *"no verdict"*, and Checkpoint 4's conjunct C is
UNADDRESSED on that half — *neither red nor green*, which this audit reports as
stated rather than resolving in either direction.

### 2.8 Bullet 12 — the testing ladder

**Amber.** The ladder ships as a machine-readable contract with an honest
self-declared coverage flag — `:here?` reads `false` for L0, L3 and L4 — mirrored
in the published chapter and the API reference. Opacity is executable rather than
prose: four distinct `hicasso-test-*-is-opaque` refusal ids plus an opaque marker,
each pointing at the tier that can witness the claim. That half is green and is
better than the bullet asks for.

**"Browser coverage for browser laws" is the qualified half.** Every
`*_dom_cljs_test.cljs` suite under `implementation/hicasso/test/` runs in headless
Chromium, and the nightly is Chromium-only. Firefox and WebKit are reached by
exactly two gates — the controlled-input and HMR jobs — and both are conditional
on the changed-surface classifier, so neither runs on a pull request the
classifier does not arm. Real IME has no continuous coverage and cannot get any;
the witness runs `--dry-run` and its own script says a green run *"is not
continuous coverage of anything"*. Whether a synthetic composition witness
discharges *browser laws* is a judgement nobody in the tree has recorded, and this
audit does not make it on the operator's behalf.

The lane's Acceptance list is a twelve-item conjunction and **nothing in the tree
walks it item by item**. The nearest verdict, Checkpoint 4's §7 Testing row, is
scored on differently-worded criteria and names only L0–L3.

### 2.9 Bullet 13 — Xray

**Green on four of five verbs, with the fifth qualified rather than failed.**

*Erases from production* is the strongest artefact in the corpus. Five load-bearing
sentinels that must be absent, three positive controls that must be present, a
fail-closed rule so an empty or wrong bundle is refused, a substring-collision
check, a source-side premise check so a renamed string reds rather than reporting a
green absence, and a `--self-test` chained ahead of the real run. **Re-run for this
audit and captured exit 0** — [§3](#3-correctness--what-was-run).

*Respects privacy* is implemented at the door, fails closed on a frameless or
destroyed frame, and is witnessed by a suite that first proves the secret really is
in the runtime state before asserting it is absent from the envelopes — without
that first row, the second would pass against a runtime that had never seen the
value.

*Accounts for loss* is schema-enforced: an unknown is never spelled as an empty
collection, and `:complete? true` beside a stated loss is a refusal.

*Guides hot extraction* is the qualified verb, and the qualification is a credit
rather than a defect. The advisor is proved never to recommend a native route from
the available evidence — asserted as a property over the classifier's whole output,
with a non-vacuity control that hands it the three unmeasurable owners directly and
gets the native rungs back. Three of its five pressure classes have no instrument
at all, and the advisor's answer to them is *measure first*. That is the honest
artefact; it is not the thing the sentence claims, and no page reconciles the two
readings.

### 2.10 Bullets 14 and 15 — coverage, mine, per-keystroke, resource demand

**Bullet 14 is amber.** All twenty §7 rows carry a primary-home assignment, but
the assignment is not in §7 — it is in `dispositions.md` §1.1, which since
2026-08-30 declares itself design history and *"not maintained"*. One row
(Migration) is assigned to `Developer product`, a label outside the five §13
enumerates. No row is an explicit non-goal, which that page names as a finding
rather than an omission. And the programme's own scoring says conjunct A is **NOT
MET on one row**, the SSR row — nineteen and one.

**Bullet 15 splits three ways, and two of the three are red.**

*The resource-demand verdict is green and is a model of its kind.* **STOP**,
explicit, pre-registered against criteria frozen at a named commit, decided by a
rule fixed in advance — *any NOT MET or any AMBIGUOUS gives STOP* — with C1 and C3
AMBIGUOUS and four criteria MET, and a witness page that records without grading
itself. A recorded dissent on C1 changes nothing because C3's ambiguity gives STOP
on its own. Every downstream page quotes it consistently.

*The requirements mine is not current.* Of 100 fully-spelled repository paths cited
on the page, 22 do not resolve; six are deliberate and the page names them;
**eleven are genuinely stale and eight sit on live ledger rows.** The cause is the
2026-08-29 landings — the bench tree moving under `8a10915ed8` and the native
suites deleted by `aa01f0e8a6` — which fall after the last consolidation pass of
2026-08-20/21. One row also calls a closed bead open. **The page predicted this
failure in terms**: *"Twice now this ledger has gone stale within days, and neither
time was a hic bead closing … A month is long enough for that to happen again."* It
happened in fifteen days. `rf2-j77q`.

*Per-keystroke is not current either, and its own witness was deleted under it.*
The `[census]` reference the page leans on throughout resolves to
`per_keystroke_dom_cljs_test.cljs`, deleted by `f5f40d1116` on 2026-08-30 — by the
same bead that edited the page that day and did not touch the dead reference. Two
further citations name a tree deleted 2026-08-15 and a script deleted 2026-08-30,
both in the present tense. The page states no date and makes no currency claim at
all. `rf2-lexh`.

**Note for a later reader: no gate can catch this class.**
`scripts/check_doc_slugs.py` is green on this tree with the dead `[census]` target
in place — captured exit 0 at the base above. It resolves markdown link targets
and heading anchors; a reference definition pointing at a source file is outside
what it reads.

### 2.11 Bullet 16 — the pilots

**Red, and it is the whole of the product verdict.**

The apparatus is complete and good: two ratified briefs, a workspace procedure, a
blank friction log built around the seven outcomes rather than a generic template,
and a stated revisit trigger that treats a near-empty log as suspect rather than as
success. **No pilot has run.** The friction log in the tree is the blank template.

`rf2-hic-063` is open. The delegated ruling of 2026-09-02 settles what would count:
*implementation audit complete* is certifiable without pilots, while *product
definition of done* stays red until **both** ratified pilots ship on the
**published** artefact — `:local/root` was ruled **not** to satisfy §13's adoption
proof, because install, test and upgrade are not an adopter's acts on a checkout,
and the test kit is on `:src-dirs` but not `:paths`, so a checkout consumer
receives a different testing package from a jar consumer.

The same ruling authorises a **pre-pilot rehearsal** on `:local/root`, logs headed
*PRE-PILOT — NOT §13 EVIDENCE*, for defect harvesting only. **A rehearsal log,
however full, is not evidence for this bullet**, and a later audit that cites one
as such should treat the citation as a finding rather than a pass. The counted logs
are the ones whose header carries the published coordinate and pin.

Nothing here is dispatchable work. The chain runs through `rf2-kmqx3` — cutting the
`v0.0.1.alpha` tag — and `rf2-lb566`, and both need the operator.

### 2.12 Bullet 17 — donor independence

**Green.** `git ls-files implementation/ui implementation/freehand` returns
nothing. Across every tracked file excluding the tracker export, **six**
`:require` forms name a donor namespace and all six are inside design-history
prose under `docs/design/freehand/`; **zero** appear in any `.clj`, `.cljs` or
`.cljc` source. No `deps.edn` carries a donor coordinate outside a comment
recording its removal — `tools/story/deps.edn` is the strongest disconfirmation,
its `:test` alias annotated *"NO SUBSTRATE-DONOR COORDINATE — deliberately"* over
an `:extra-deps` map that carries none. **`docs/core/**` has zero occurrences**, so
primary documentation is clean.

The large surviving token counts are retirement bookkeeping and design history,
plus a deliberate reserved-kind roster — `:rf.adapter/ui` and
`:rf.adapter/freehand` stay listed precisely so tooling refuses them, and
`substrate/adapter.cljc` says *"nothing here can produce one"*.

Two things a reader should carry rather than trip over. The clause *"any retained
compatibility fixture is named and isolated"* is **vacuously satisfied**: the
census records *"Zero remain FIXTURE-ONLY"*, which is stronger than the bullet
asks but means a reader looking for the named fixture finds nothing. And one
reachable `console.warn` in the shipped Xray tool still says `re-frame.ui` in its
message text — the census calls it STILL-LIVE and files the wording as
`rf2-0ucyg`, while the same file asserts the coordinate is off that artefact's
classpath and must stay off it. On the bullet's own word, *depends*, it does not
falsify the claim.

### 2.13 The closing paragraph — the bounded Node service

**Green.** The 2026-08-12 operator amendment removed the *"when a named caller
exists"* condition and made the bounded Node service a v0 deliverable in its own
right. `re-frame.hicasso.server` exists in the shipped package with four public
names — `render`, `render-body`, `document`, `payload-script` — landed as
`30317bfe0e`, and `impl/roots.cljs` names it one of two minters of the adoption
window. The paragraph's own carve-out holds: building it is the obligation,
deploying it is not, and the SSR row still requires every inventoried surface to
pass its policy with the service absent.

## 3. Correctness — what was run

Each command below was redirected to a log and its runner's own exit code echoed
on the same command line; the number quoted is the captured one.

| Gate | Captured exit | Result |
|---|---|---|
| `scripts/assert-worker-worktree.sh` | **0** | guard passed |
| `npm run test:hicasso-invariants` | **0** | reachability self-test OK, then OK; guide-sample self-test *"the rule fires"*, then 74 distinct verbs at 401 sites across 29 pages resolve |
| `check_production_erasure.cjs --self-test` | **0** | *"self-test OK (5 sentinels, 3 positive controls)"* — the erasure positive control this bead's protocol names |
| `scripts/check_doc_slugs.py` | **0** | baseline, then again over this page and the ledger rows |
| `scripts/check_provenance_pins.py --changed-since origin/main` | **0** | 2 pages, 76 cited pins — 75 landed, 1 stranded and accompanied in scope, 0 findings |
| `npm run test:cljs` (node lane) | **0** | 11,172 tests / 55,747 assertions, 0 failures, 0 errors |

### 3.1 The sabotage control, and the proof it read this tree

`test:hicasso-invariants` prints no root, so its provenance was established the
other way: **by a planted fault that exists only in this checkout.** One `h/sub`
call in a fenced block of `docs/core/hicasso/03-events-as-data.md` was replaced
with a verb the package does not define; the anchor was confirmed to match exactly
once before the plant, so the edit could not silently no-op.

The gate went **red at captured exit 1**, naming the plant:

```
FAIL: the Hicasso guide names a verb the package does not define
  re-frame.hicasso/dodaudit-sabotage-verb: named at 03-events-as-data.md block 2, defined nowhere in re-frame.hicasso
```

The file was then restored and verified by **git blob hash**, identical to the
pre-plant object, with the working tree clean. The red is the discrimination: the
fault existed only here, so a run that had wandered into another checkout would
have come back green.

The node lane needs no such proof — it prints its config path, and the path it
printed is this worktree's.

**The doc-surface gate was proved the same way, on this page.** One anchor link in
the bullet table was repointed at a heading that does not exist; the anchor was
confirmed to match exactly once beforehand. `check_doc_slugs.py` went **red at
captured exit 1**, naming *this file* and *line 73* — a line that exists in no
other checkout — then green again at captured exit 0 once the file was restored
and verified by git blob hash against its pre-plant object.

Nothing validates this page's tables, rendering or nav, so its column counts were
checked by hand: nine table blocks, each internally uniform, no cell carrying a
literal unescaped pipe. The correction ledger's table was re-parsed after the
append — 41 data rows, every one at seven columns, matching its header.

### 3.2 The node lane

`implementation/node_modules` did not exist in this checkout, and the first attempt
failed immediately on it (captured exit 1, *"could not resolve shadow-cljs"*).
**No junction into the coordinating checkout's dependency tree was made** — that is
the documented route by which an installer rewrites a shared target — so
`npm ci` installed this worktree's own tree from the lockfile, captured exit **0**,
110 packages. The `:node-test` build then compiled 1,892 files with 0 warnings, and
the lane ran **11,172 tests containing 55,747 assertions, 0 failures, 0 errors,
captured exit 0**.

**Read that as evidence about the suite and not about the matrix.** A green node
lane says the shipped contract tests pass on this tree; it says nothing about a
phase exit whose conjunct is failed by an obligation that has no witness at all,
which is what bullets 7, 8 and 10 are.

### 3.3 What was not re-run, and why

The protocol asks for a clean-checkout full-suite run and one sabotage per
checkpoint domain. **One sabotage was re-run, not four**, and the browser lane was
not attempted. Three peer workers were live on this machine for the duration, and
the programme's own record is that two heavyweight runs here **wedge rather than
fail** — no progress, no exit file, no error. A wedged run produces no verdict and
costs the peers theirs. This is stated as a limit on the audit rather than folded
into a pass.

## 4. Quality

**The fresh-reader result.** `rf2-hic-069`'s protocol is cited by the pilots
apparatus — an empty defect list is treated as suspect on the same logic that
makes a near-empty friction log suspect — but no published fresh-reader result
exists in the tree to review. Recorded as **not available**, not as green.

**Three complaint ids traced, chosen at random from the 41 live rows.** All three
resolve at source: each is emitted by the package, and each carries a Spec 009 row
stating its meaning, its payload and an explicit `:recovery` value.

| Id | Emitted in | Spec 009 row | Guide chapter |
|---|---|---|---|
| `:rf.error/hicasso-test-missing-read-fixture` | the test kit | yes, with `:no-recovery` and four payload keys | `—` |
| `:rf.error/hicasso-generation-fence-exhausted` | `impl/collector.cljs` | yes, with the fence's full reasoning | `—` |
| `:rf.error/hicasso-test-react-is-opaque` | the test kit | yes | `—` |

**The docs-anchor leg is where the trace stops, and it is a documented
convention rather than a defect.** The catalogue says `—` means *no page names it
yet*. **26 of the 41 live ids carry it**; sixteen of those twenty-six are test-kit
ids that the testing chapter arguably covers generically. Recorded as a quality
observation with no bead: the convention is honest, the ratio is the thing worth
knowing, and inventing chapter pointers to close a ratio is the shape this
programme exists to refuse.

**The anti-cathedral check — what grew beyond its witness.** The honest answer is
that this question was already asked and already acted on, at scale, by
`rf2-6c12m`: the package was measured at roughly 22% library and 78% apparatus by
line, and the campaign retired the native grammar, twelve gate scripts' worth of
process protection down to five that protect something a consumer feels, four
gated ledgers demoted to design history, and a bench tree of donor copies moved
off the classpath. **What this audit adds is the cost side of that trade, which is
the whole of §2.6, §2.10 and `rf2-87iu`:** the apparatus came down faster than the
records describing it, so the definition of done, two canonical tables, two
checkpoint records, the requirements mine and the per-keystroke page all now
describe a tree that has moved. That is not a reason to regret the campaign. It is
the reason five of this audit's seven beads exist.

## 5. The two states, stated plainly

**Implementation audit complete — NOT CERTIFIED.** Achievable without pilots, and
not achieved. It fails on the correction-ledger conjunct alone (`rf2-nf8w`), and
independently on bullets 4, 7, 8, 10 and 15, with bullets 6 and 11 not scoreable
until `rf2-aunp` lands.

**Product definition of done — RED.** Bullet 16 has no evidence and its route to
evidence runs through an operator act. This was the expected outcome and it is a
complete result, not a deferral.

**What would move each.** For the first: the seven beads in [§6](#6-the-corrective-beads),
of which `rf2-nf8w` and `rf2-aunp` are load-bearing and the rest are records
catching up with the tree. For the second: `rf2-kmqx3`, then `rf2-lb566`, then two
counted pilot runs recorded on `rf2-hic-063`. No amount of worker time shortens
the second chain.

## 6. The corrective beads

Every finding above is filed. Each also takes an `open` row in
[`correction-ledger.md`](correction-ledger.md), written by this report's own PR
per that file's transition table; no corrective PR edits it.

| Bead | Severity | Finding | Bullets it blocks |
|---|---|---|---|
| `rf2-nf8w` | `coverage` | The closure bead the ledger's own rule requires and nobody filed: re-run `rf2-hic-038` §2 Correctness so the `rf2-s52w` row can reach `closed` | the green definition itself; 4, 7 |
| `rf2-aunp` | `correctness` | §13's two canonical tables, and `specification.md` §4, are written over a native tier that no longer ships | 6, 7, 11 |
| `rf2-l67a` | `coverage` | The checkpoint records score surfaces since retired and cite corrections since closed; five named cells | 4 |
| `rf2-j77q` | `coverage` | The requirements mine is stale — eleven cited paths on live rows do not resolve | 15 |
| `rf2-lexh` | `coverage` | `per-keystroke.md`'s own census witness was deleted under it | 15 |
| `rf2-87iu` | `quality` | The naming-census positive control is gone; a reproduce block still invokes deleted scripts; row 18 reads stopped where it is executed | 2 |
| `rf2-60jv` | `coverage` | The public-surface-only positive witness was retired; four pages still cite it | 5 |

## 7. What this audit could not run

Stated rather than reconstructed, because an honest not-runnable row is itself a
finding.

| Ordered check | State | What would settle it |
|---|---|---|
| The naming-census positive control | **not runnable** — `check_naming_census.py` was deleted on 2026-08-30 with its CI job and fast-PR block | `rf2-87iu`: either an instrument, or a dated sentence accepting the 2026-08-20 reading as the standing evidence |
| The budgets reconciliation against a fresh pinned run on the physical reference profile | **not runnable as ordered** | The machine is not the obstacle — it was verified to be `P-DEV-1` on every registered field and reading a quiet processor queue of 0/0/0. **The instrument is**: `check_budget_ledger.py` was deleted on 2026-08-30, so there is no reconciliation gate to run, and §9.4 records that the two landed clock drivers measure a synthetic mount rather than the application paint `U1`–`U4` are stated over. A reading taken anyway would be a figure against the wrong population, which is what `UNPINNED` exists to say instead |
| One sabotage per checkpoint domain (four) | **one run, three not** | A quiet box. Three peer workers were live and the recorded failure mode here is a wedge rather than a failure |
| Clean-checkout full browser lane | **not attempted** | Same |
| The `rf2-hic-069` fresh-reader result | **no artefact in the tree to review** | Whoever owns that protocol publishing its result |

**And one thing that ran and should be read as a limit rather than a pass**: the
node lane compiled and ran in this worktree, but a green node lane is evidence
about the suite, not about the matrix. Three of four phase exits are recorded NOT
MET by the checkpoints that took them, and no suite run flips a conjunction whose
conjunct is failed.
