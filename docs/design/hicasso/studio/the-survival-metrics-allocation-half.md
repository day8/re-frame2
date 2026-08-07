# What does a warm re-render *allocate* per read — and can this rig see it?

Seat: EVIDENCE SPIKE, EP-0038. Bead `rf2-2rtt6.76`, re-homed from
`rf2-2rtt6.41` item 4. Measured 2026-08-07 on the **P0 bench instrument**.

**No slope is published on this page, and that is the finding.** The
instrument was built, its arithmetic was validated against a control it had
to hit, and it then refused the series on a criterion written before the
series was taken. The refusal is reported here in full rather than
withdrawn, because the programme has retracted a published row before and
the cheaper mistake is the one that never ships.

## The answer, first

- **The allocation half of HD-002's survival metric is still unwitnessed**,
  and after this run it is unwitnessed *with a reason and a route* instead
  of merely unattempted.
- **The P0 rig could not answer it before today.** `p0_heap.cljs`'s own
  header says "Nothing here counts allocations", and it is right to. The
  reads ladder prices what a boundary *keeps* per read; the survival metric
  asks what a warm re-render *throws away* per read. Those are two
  quantities and they need not have the same sign.
- **An allocation row now exists on the rig** (`p0_run.cjs --only alloc`)
  and its arithmetic is sound: the positive control reads **8.13 B/double
  measured directly and 7.99 B/double as a differential**, against a
  predicted 8, with the idle window at 32 B. Transient garbage is visible
  to this counter, which is the one claim the row had to establish before
  quoting anything.
- **The fit rule refused every arm it was given.** Over the mandated
  1/3/7/20 rungs the r² came back **0.75, 0.94 and 0.31** against a 0.98
  floor, and no arm was a line in more than one round of four. The rule is
  the reads ladder's own `ladder-fit`, with its self-test proving it can
  fail, so the refusal is a criterion's and not an editorial call.
- **The candidate's Reagent-segment rows are struck, and "refused" was the
  wrong word for them.** That arm never re-rendered: its DOM read-back was
  stale by exactly the number of writes each window drove, in every round,
  at every rung, and its allocation column sat flat on ~100 B/boundary,
  which is the *floor's* own figure. A refusal says the fit did not hold;
  the truth is that nothing was measured, so the rows and their fit line are
  **removed rather than re-captioned**. The cause is a propagation gap in
  the arm's own runtime, diagnosed on `rf2-2rtt6.137` and repaired in one
  line by `rf2-2kshh`.
- **The UIx-adapter segment is untouched by that mechanism and its rows
  stand.** The React-hook spine is push-based from birth, those read-backs
  all passed, and the candidate's refusal there is the fit rule's — r²
  0.311, slope −37 B/read, 0 of 4 rounds linear — which is a genuine
  refusal and is not withdrawn.
- **The instrument has a hard ceiling and the published witness is outside
  it.** At B = 1,200 boundaries, 32 collections fell inside 44 measured
  windows and the row refused on that alone.

## What the metric is, and why the reads ladder does not answer it

[validation.md](../validation.md) prices the tier-3 per-read budget as
"steady-state **allocation** slope across warm 1/3/7/20 reads, zero
retained per-occurrence objects after commit/teardown". Two halves. The
second is witnessed — in bytes by the ladder's residue column and in
objects by its structural stamp, `rf2-2rtt6.9`. The first is what
[the dogfood judgement](arm1-lean-react-dogfood-judgement.md) records as
"needs the bench and is not taken here".

[hd-002-adjudication.md](../hd-002-adjudication.md) states the cost law
being tested, and states it so it can fail:

> Allocation is proportional to the change, not to the read count. The
> unchanged case allocates nothing.

and H1's pre-registered prediction is that "the allocation slope across
warm 1/3/7/20 reads is **flat at zero**", **falsified by** a non-flat
slope. The mechanism is `entry-matches?` in `arm1/runtime.cljs` — an
ordered pairwise compare of the cached entry's key array against the
scratch, which allocates nothing — so a warm re-render whose read set did
not change re-uses `subscribe`'s identity and the commit does no work.

That is a claim about **edge maintenance**, not about the whole render. A
warm re-render at R reads also allocates R query vectors in the arm's own
body, R subscription recomputations and a React element tree, and every
substrate pays all three. So the quantity that answers HD-002 is the
candidate's slope **less the same-run donor's**, in the same segment —
which is why this row carries the donors beside the candidate exactly as
the ladder does. A candidate slope quoted alone would be mostly other
people's allocation.

## The instrument, and the two it is not

If no collection runs between two readings of V8's used-heap counter, their
difference is the bytes allocated in between — *including* the bytes
already unreachable, because nothing has reclaimed them yet. Garbage is
visible precisely because it has not been collected. So a window collects,
then samples the counter at every leg boundary of every warm bulk write,
and accumulates the **rising** steps separately from the **falling** ones.
A rising-step sum is an allocation total whether or not a collection
intervened, because a collection is excluded from it rather than netted
against it.

This is `b8-alloc`'s method, and the two obvious instruments stay ruled out
for the reasons that namespace established rather than re-argued here:
V8's CDP **sampling** heap profiler drops the samples of collected objects,
so it reports retention wearing an allocation instrument's name; and a heap
**snapshot** collects before it walks, destroying the very bytes in
question.

The write is one `dispatch-sync` of `:p0/write-all` — the same event the
bulk clock arms drive — through the event pipeline and the signal graph,
followed by the substrate's own drain. Every boundary re-renders and every
boundary's read set is unchanged, which is the steady state the cost law is
stated over. The arm stays **mounted** across the window: this is what a
standing page allocates when it is written to, not what a mount costs.

### The controls, which pass

Three, taken in situ before every round's arms:

| control | predicted | measured | reading |
|---|---:|---:|---:|
| idle window (the sampler's own footprint) | — | 32 B/iteration [32–32] | — |
| D = 1,000 doubles, dropped | 8,000 B | 8,126 B [8,080–8,228] | **8.13 B/double** |
| D = 400 doubles, dropped | 3,200 B | 3,330 B [3,280–3,480] | **8.32 B/double** |
| differential, D=1,000 less D=400 | 8 B/double | — | **7.99 B/double** |

The differential is the one a residual per-window overhead cannot flatter:
it cancels every constant, including the sampler's own 32 B. **A retention
instrument reads both control figures as zero.** That this one does not is
the entire licence the row needed, and it is measured rather than asserted.

### The ceiling, which is where it stops

The method's premise is that no collection falls inside the window. Two
standalone probes measured what happens when one does, on both kinds of
garbage, twenty iterations each:

| garbage per iteration | shape | measured | falls |
|---|---|---:|---:|
| 8 KB (D = 1,000) | one large array | 8.40 B/double | 0 |
| 32 KB (D = 4,000) | one large array | 8.08 B/double | 1 |
| 120 KB (D = 15,000) | one large array | 7.32 B/double | 3 |
| 800 KB (D = 100,000) | one large array | 4.00 B/double | 10 |
| ~240 KB (K = 5,000) | many small objects | 23.92 B/object | 3 |
| ~1.9 MB (K = 40,000) | many small objects | 6.94 B/object | 10 |

The reading degrades **monotonically with the number of collections**, and
the same object reads a third of its own value once ten of them land. The
first fall arrives at roughly **600 KB of cumulative garbage per window**.

**The obvious repair does not work.** Enlarging the young generation so a
window's garbage fits — `--max-semi-space-size` — was tried at 128 MB and
at 512 MB and returned the same table, falls unchanged to the unit. The
flag is therefore not shipped; a flag that changes nothing while looking
like a repair is worse than none, and the row gates on the falling-step
count instead.

**The direction of the bias is why none of this could be waved through.**
The error is always *downward*: an instrument over its ceiling under-reads
allocation. HD-002 predicts the candidate's excess slope is flat at zero.
An instrument that systematically under-reads allocation is an instrument
that manufactures exactly that answer, so a run taken over the ceiling
would have produced a number that read as evidence and was not.

## The rows

Taken **under** the ceiling: B = 300 boundaries (one root), one warm write
per window, two full-size warm-up windows before each measured one, four
rounds, segment order alternating with round parity. `y` is bytes
allocated per boundary per warm write. **0 falling steps across 88
windows** — the instrument is inside its valid regime everywhere below.

### Reagent-adapter segment

| arm | reads | B/boundary/write [min–max] | B/write |
|---|---:|---:|---:|
| floor *(no subscription — the write's own cost)* | — | 104 [97–118] | 31,114 |
| Reagent | 0 | 96 [88–100] | 28,804 |
| Reagent | 1 | 1,329 [1,188–1,695] | 398,557 |
| Reagent | 3 | 4,637 [3,162–5,874] | 1,391,177 |
| Reagent | 7 | 3,051 [2,916–3,157] | 915,306 |
| Reagent | 20 | 7,387 [4,090–9,435] | 2,216,118 |

**The candidate's five rows are struck from this table.** They read
99/100/102/98/103 B/boundary at R = 0/1/3/7/20 — the floor's own figure at
every rung, on an arm that has a subscription at four of them — because the
arm never re-rendered at all. There is no reading here to refuse and none
to re-caption: the write never reached the boundary, so the counter
measured a page that did not move.
[The cause is below](#the-second-blocker-the-candidate-does-not-re-render-under-reagent).

### UIx-adapter segment

| arm | reads | B/boundary/write [min–max] | B/write |
|---|---:|---:|---:|
| floor *(no subscription — the write's own cost)* | — | 101 [98–102] | 30,218 |
| UIx | 0 | 100 [98–102] | 30,031 |
| UIx | 1 | 2,164 [2,082–2,204] | 649,123 |
| UIx | 3 | 2,497 [2,172–2,995] | 749,217 |
| UIx | 7 | 7,821 [5,599–8,863] | 2,346,252 |
| UIx | 20 | 13,155 [12,346–14,238] | 3,946,403 |
| Hicasso | 0 | 102 [101–105] | 30,725 |
| Hicasso | 1 | 3,007 [2,890–3,085] | 902,065 |
| Hicasso | 3 | 3,178 [2,831–3,352] | 953,289 |
| Hicasso | 7 | 3,840 [3,092–4,137] | 1,152,061 |
| Hicasso | 20 | 2,461 [2,165–3,150] | 738,225 |

The R = 0 rung is the anchor on both tables and is regressed nowhere. It is
also, on this row, a boundary that *cannot* re-render — it reads nothing,
so no write reaches it — and it lands on the floor's figure on every arm,
which is the cheapest available check that the floor subtraction is
measuring what it claims.

## The fits, which are refused

Over 1/3/7/20 and over nothing else, through `p0-heap/ladder-fit` — the
same rule and the same 0.98 r² floor the reads ladder publishes under,
whose self-test feeds it a quadratic page and requires a refusal.

| arm | slope | r² | verdict | rounds linear |
|---|---:|---:|---|---:|
| Reagent | 262 B/read [54–411] | 0.75279 | **NOT A LINE** | 0 of 4 |
| UIx | 589 B/read [549–645] | 0.93851 | **NOT A LINE** | 1 of 4 |
| Hicasso, UIx substrate | −37 B/read [−56–−6] | 0.31140 | **NOT A LINE** | 0 of 4 |

**The "Hicasso, Reagent substrate" fit is struck rather than refused.** It
read 0 B/read at r² 0.283, and a fit over four rungs of an arm that never
re-rendered is a fit over the floor. A refusal on this table invites a
re-run on a quieter box, and no box will ever help this one — the repair
is a line of the arm's runtime and it is `rf2-2kshh`.

**No slope on that table may be quoted, including the donors'.** The
per-round ranges say the same thing the r² does: Reagent's 20-read rung
spans 4,090 to 9,435 B/boundary across four rounds, a factor of 2.3 on an
arm nothing about which changed between them. A one-write window has no
averaging inside it, and the noise that buys is larger than the quantity
being fitted.

It is worth being explicit about the shape of the failure, because it is
not the shape a noisy line has. The rungs are not scattered around a line;
they are **non-monotone**. Reagent reads 4,637 B at three reads and 3,051 B
at seven. UIx reads 2,497 B at three and 7,821 B at seven. Something other
than the read count is moving these windows, and until it is identified the
fit has nothing to fit.

## The second blocker: the candidate does not re-render under Reagent

The row's warm-write read-back is what caught this, and it is the reason
that gate exists. At R reads of a page whose cells were all written to `v`,
a ladder boundary's text is `R·v`. On the Reagent-adapter segment the
Hicasso arm's text is stale by **exactly the number of writes the window
drove** — three, every time, at every rung, in every round:

| arm (Reagent-adapter segment) | read back | expected |
|---|---:|---:|
| `lad/hicasso#R1` | 21 | 24 |
| `lad/hicasso#R3` | 72 | 81 |
| `lad/hicasso#R7` | 189 | 210 |
| `lad/hicasso#R20` | 600 | 660 |

Sixteen such failures across four rounds, all of them on that one arm. Its
allocation column sits flat on ~100 B/boundary — the same figure the
*floor* reads, and the floor has no subscription and cannot re-render. The
UIx-adapter segment's Hicasso arm re-renders normally and reads 2,461 to
3,840 B/boundary.

**That is why those rows are struck above rather than refused.** The
candidate needs neither adapter's hooks, but every read goes through
`re-frame.subs` and the reaction `subscribe` builds is the installed
adapter's — the same fact the reads ladder's two candidate columns are
built on. The read-back ruled the bench's drain out and a read-only pass on
`rf2-2rtt6.137` named the cause: a **real propagation gap in the
candidate's own runtime**, not in the architecture and not in the
instrument. `arm1/runtime.cljs`'s `wire-cell!` — the arm's entire
attachment to the substrate — subscribes, derefs once for a baseline,
`add-watch`es and never calls `interop/activate-derived-value!`. Under the
ratom family a subscription *is* a bare `Reaction` built deliberately
without `:auto-run`, and a `Reaction` learns its sources only through
deref-capture, so a plain deref outside `*ratom-context*` runs the body raw
and leaves its `watching` set nil. The reaction is therefore never in
`app-db`'s watcher set, the watch never fires, and `mark-dirty!` — whose
only caller is that watch — never fires either. The arm paints once at
mount and is deaf thereafter.

This is the same defect as `rf2-8cnxg`, which the shipping observation port
repaired by ordering the acts *activate, then watch, then observe*; arm 1's
runtime is a second consumer of that substrate which never received the
fix. `rf2-2kshh` carries the one-line insertion. **It had not landed when
this page was corrected, so a re-take of the Reagent segment is blocked on
it** — and a re-take needs the quiet box this row's provenance describes,
not merely the fix.

Nothing else on the page moves with it. The clock bench gives the candidate
a segment that installs the UIx adapter on purpose, and every `p0-heap` row
is mount–hold–release with no write at all, so **this row is the first time
in the P0 programme that a write was driven at `lad/hicasso` with the
Reagent adapter installed**. No previously published Hicasso figure is
invalidated.

Note what it would have done unnoticed. A candidate arm that never
re-renders allocates nothing per read, and "nothing per read" is precisely
HD-002's predicted answer. Without the read-back the row would have
published a flat-at-zero candidate slope on the Reagent segment as a
confirmation of the design.

## The published witness is outside the instrument's range

The reads ladder states its rows at **B = 1,200** boundaries. Re-taken at
that witness with six writes per window, the row refuses before any fit:

- **32 falling steps across 44 measured windows.** Every arm figure in that
  run is an under-estimate by an amount the run cannot bound.
- The controls still pass in the same run (8.09 B/double direct, 8.01
  differential) — **what refuses is the arms' scale, not the method.**

A warm re-render of 1,200 boundaries allocates 1.2–1.7 MB, two to three
times the ~600 KB fall threshold **in a single write**. There is no window
size that fixes this, because one write is the atom.

## What this hands the programme

1. **The rig has an allocation instrument it did not have**, gated on its
   own controls and on the falling-step count, which refuses rather than
   publishes when it is out of range. It is reusable for any future
   allocation question on this bench.
2. **The ceiling is now a measured number** (~600 KB of garbage per window)
   rather than an unexamined assumption, and the repair that does not work
   is recorded so the next reader does not spend the afternoon on it.
3. **Two blockers stand between here and the metric**, and neither is about
   effort:
   - the candidate does not re-render under the Reagent adapter on this
     bench, so half the witness is not being exercised at all. This one is
     now **diagnosed and has a one-line repair in flight** (`rf2-2kshh`);
     until it lands, a Reagent-segment re-take is blocked, and the rows it
     produced are struck from this page rather than standing as a refusal;
   - at any witness the instrument can see, a single-write window is too
     noisy to fit, and at any window large enough to average, the collector
     is inside it.
4. **The route through is to shrink the measured unit, not the window.**
   The quantity the metric wants is *per boundary*, so an arm of a few
   dozen boundaries would put a many-write window under the fall threshold
   and restore the averaging a fit needs. That is a new arm on this row
   rather than a new instrument, and it is the shape the next attempt
   should take.
5. **Nothing in [validation.md](../validation.md) moves.** The survival
   metric's allocation half is exactly as unwitnessed as it was, and no
   gate line, budget or verdict anywhere in the corpus is restated on the
   strength of this page.

## Provenance

**The instrument is identified by content hash, not by commit SHA** — a SHA
does not survive a rebase, and this corpus has been bitten by that before.

| file | blob |
|---|---|
| `p0_run.cjs` | `44d106b3c248108ea1fdfd0c54229fb3e499b22b` |
| `p0_heap.cljs` | `5c83cc2a653812cfbddc7c7c76ae022ebe6f870c` |
| `p0_arms.cljs` | `5be2024f326c4a3debd17f9f5c791c171468eeb4` |

All three live under `implementation/core/test/re_frame/bench/`. Measured
at authored head `d31fdb5a069b5b5ff5541ff2878f60278dd61e7a` on branch
`worker/heapslope-2rtt6-76`, which branched from the landed commit
`6dbf37998dcccf21f6ec7316887410beaa09dbc4` on main — that one resolves in
any fresh clone and is the tree everything above was measured *against*.
The authored head will not survive this page's own rebase-merge, which is
why it is accompanied rather than left alone. **If it does not resolve, the
blobs above are what to trust:**

```bash
P=implementation/core/test/re_frame/bench/p0_run.cjs
git log --oneline --all -- $P
git rev-parse <candidate>:$P    # must print 44d106b3c248108ea1fdfd0c54229fb3e499b22b
```

Reagent **2.0.1**, UIx **1.4.4**, React **19.2.0**, node **24.13.0**,
Playwright **1.59.1**, headless **Chromium 147.0.7727.15**. Windows 11,
single developer workstation. Every arm is an `:advanced` ClojureScript
bundle with `goog.DEBUG false`. **Browser numbers**; nothing here is a JVM
or Node figure.

**Box quietness, which this bead made a precondition.** The series was
taken on a deliberately quiet machine and the state was checked before and
after: **zero `java.exe`**, no `shadow-cljs` server, no orphaned gate
process trees, and three idle Node processes that are MCP servers and an
agent host. Background CPU sat at 16–25% of 24 logical cores throughout,
all of it idle Chrome tabs and Defender. Nothing was killed because nothing
needed to be, and no other bench work ran on this instrument during the
series.

Reproduce:

```bash
# the in-range witness — controls pass, fits refused
P0_ROOTS=1 P0_ALLOC_ROUNDS=4 P0_ALLOC_WRITES=1 P0_ALLOC_WARMUPS=2 \
  node implementation/core/test/re_frame/bench/p0_run.cjs --only alloc

# the published witness — refused on the falling-step gate
P0_ALLOC_ROUNDS=2 P0_ALLOC_WRITES=6 P0_ALLOC_WARMUPS=2 \
  node implementation/core/test/re_frame/bench/p0_run.cjs --only alloc
```

Both exit **1**, and the exit is the point: this row's gates are what
stopped a slope being published, so a run of it that exited 0 would be the
surprising outcome.
