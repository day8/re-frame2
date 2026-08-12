# What does a *subscribing* boundary cost in memory, at 1, 3, 7 and 20 reads?

Seat: EVIDENCE SPIKE, EP-0038 Wave 0. Bead `rf2-2rtt6.5`; numbers appended to
the P0 baseline record that superseded the operator-owned standard
`rf2-2rtt6.1` on 2026-08-10 —
[the evidence baseline](../product/lanes/evidence-baseline.md).

**Two measurements live on this page, on two instruments, and they are kept
apart.** §§1–5 are the donor ladder of `rf2-2rtt6.5`, taken on the freehand
harness. [§6](#6-the-hicasso-candidate-rung--one-hook-plus-a-shared-index) is
the **Hicasso candidate rung** of `rf2-2rtt6.34`, measured 2026-08-01 on the
**P0 bench instrument** with both donors re-taken beside it in the same runs —
because a candidate is judged against the donor row taken on its own
instrument, and the two harnesses differ by a measured ~5% common-mode offset.
No figure is scaled from one onto the other.

**Re-measured 2026-07-31 after the audit of PR #7260**, which found that the
regression included the R=0 anchor the page promised it excluded. Every figure
below is a fresh reading through the corrected fit; the superseded publication
is recorded in [§8](#8-superseded) rather than deleted.

**Spine stamp — every UIx rung below was measured on a spine that no longer
ships.** Two production landings postdate this run. **One of them cuts UIx
retained heap**: `rf2-2rtt6.13` (PR #7304, `9df5094816`) stopped retaining the
disposed render-phase reaction — **−769 B / −23.0 objects per unique query
key**, which on this ladder's distinct-query regime is 769 B *per read*. The
other, `rf2-2rtt6.25` (PR #7305, `f784ab0adb`), landed the hook-scoped
provisional hand-off; it is named here as a **tree stamp, not as a cause**,
because no retained-heap delta from it has been established — the audit of
PR #7305 drove the shipped bare `createRoot().render` path and measured 2N body
constructions at N = 1, 300, 3,000 and 10,000, the reaper winning before
React's passive subscribe. That schedule question is open under
`rf2-2rtt6.25`; every correction on this page is `.13`'s. The **measured rows
are left exactly as measured**, because a measurement record edited to match a
later tree stops being a record. What is restated, in place, is everything this
page *hands the programme* as a live number:
[§5](#5-what-this-hands-the-programme) carries the gate lines on the
post-landing tree, with the arithmetic written out. Reagent's rows are
untouched by both landings — neither goes near the ratom path.

**Regime stamp — distinct-query (Q = E), mandatory worst-case witness,
operative upper-envelope family.** The heap red-zone regime ruling (delegated
by Mike, 2026-07-31; authoritative text on rf2-2rtt6.16, transcription on
rf2-2rtt6.1) makes cache cardinality part of the witness: every heap red-zone
row stamps **B** (boundaries), **E** (boundary-query edges) and **Q** (unique
live query keys). On this ladder every read is a distinct query, so **Q = E**
and the fan-out E/Q is 1 on every rung — Curve B holds B = 1,200 with
E = 1,200·R, Curve A holds R = 3 with E = 3·B. These rows are the **operative
upper-envelope red-zone family**: where governance needs one conservative
number, the distinct-query row is used and labelled an *upper-envelope capacity
budget*, never "retained bytes per boundary" unqualified. The frontier arm's
shared-query grid (E/Q = 4) is fan-out witness evidence, not comparable to
these rows; its ratios remain the cross-regime check — **approximately**. The
[fan-out sweep](heap-fan-out-sweep.md) measured that ratio drifting **9.3%**
across E/Q 1 → 8 (5.5% reproduced at ROOTS=1), so a ratio is far more portable
than an absolute (which moves 1.6–1.9× over the same range) but is not
regime-free, and a cross-regime check quoted to three decimals is quoting
drift.

**The instrument is identified by content hash, not by commit SHA.** A SHA does
not survive a rebase — the previous publication was invalidated by exactly that,
and this one was rebased onto `main` between its run and its merge, moving every
SHA again while the three blobs below did not move at all:

| file | blob |
|---|---|
| `reads_ladder_run.cjs` | `eabd226bcb6fe3877d056145cae496eccd5ab62c` |
| `reads_ladder.cljs` | `535ee5fd67fb0579854720ff26776c51c7007fd7` |
| `reads_ladder_app.cljs` | `5b1770a49d4cac50248946b9e80bed726ca4f53d` |

All three live under `implementation/freehand/test/re_frame/freehand/bench/`.
Authored as `09ec4e6b3c` on `worker/bench-audit-cluster`; that head is stranded
by the rebase merge and is in no fresh clone, so the resolvable anchor is the
commit it landed as, `ea225b8e11`. The landed commit anchors the patch, and the
authored head names the tree — they are not the same object and the rest of this
page keeps them apart. For *this* instrument the distinction costs nothing: all
three blobs above are byte-identical at both, which is the claim that matters
here and the only one being made. **If a SHA does not resolve, the blobs above
are what to trust** — this finds a commit carrying them, and confirms it:

```bash
P=implementation/freehand/test/re_frame/freehand/bench/reads_ladder_run.cjs
git log --oneline --all -- $P
git rev-parse <candidate>:$P    # must print eabd226bcb6fe3877d056145cae496eccd5ab62c
```

The reproduction below runs unchanged at any commit whose tree answers those
three hashes.

Reagent **2.0.1**, UIx **1.4.4**, React **19.2.0**, node **24.13.0**, headless
**Chromium 147.0.7727.15** via Playwright. Windows 11, single developer
workstation with other agents running concurrently. Every arm is an
`:advanced` ClojureScript bundle with `goog.DEBUG false`
(`:freehand-release`). **Browser numbers**; nothing here is a JVM or Node
figure.

Reproduce:

```
node implementation/freehand/test/re_frame/freehand/bench/reads_ladder_run.cjs
```

(defaults `LADDER_ROUNDS=6 LADDER_SNAPSHOT=1 LADDER_SUBSTRATES=reagent,uix`;
exits **2** if the arm-order guard refuses, **3** on an unverified mount,
**4** if reader C was requested and did not arrive complete.)

The two substrate pages are independent by construction, so this run took them
one at a time (`LADDER_SUBSTRATES=reagent`, then `=uix`) at the one commit.

**That command reproduces §§1–5 only.** [§6](#6-the-hicasso-candidate-rung--one-hook-plus-a-shared-index)
is a different instrument and carries its own reproduction line, its own
blobs and its own conditions.

---

## The answer, first

**The 516-vs-251 tension is not a disagreement. It is two measurements of two
different things, neither of which is what a subscribing boundary costs.**

- **251 B was measuring the INTERCEPT** — the per-boundary shell of a UIx
  boundary that reads *nothing*. This ladder measures that shell directly at
  **208 B** [201–213] and recovers it as the fitted intercept of the reads
  curve at **113 B** [107–124].
- **516 B was measuring one hook slot in isolation** — `useSyncExternalStore`
  alone, decomposed out of a Freehand boundary by `rf2-oob3g`. It is neither a
  boundary nor a read. It is one component of the slope, and a minority one.
- **Neither priced a read.** One more re-frame2 subscription read on a UIx
  boundary cost **3,552 B** [3,551–3,553] on the spine this ladder measured —
  **6.9× the 516 B hook figure** and **17.1× the 208 B boundary figure**.
  `rf2-2rtt6.13` has since taken 769 B out of that read: on the spine that
  ships today it is **2,783 B**, still **5.4×** the hook and **13.4×** the
  boundary. On Reagent the same read costs **943 B** [935–944], and neither
  landing moved it.

Put the two figures on one line and they stop arguing. The fitted lines are
`cost = 113 + 3,552 · R` on UIx as measured — `113 + 2,783 · R` on the shipping
spine — and `cost = 397 + 943 · R` on Reagent, with the
directly measured R=0 rungs — 208 B and 428 B — sitting ninety-five and thirty
bytes above their intercepts, which is the width of this instrument's zero
against a 71 KB range. **251 is the first term; 516 is a fraction of the
second.** There was never a contradiction to resolve — only a category error,
and a category error is exactly what a ladder cannot make.

### The marginal read and the first read are not the same number

The slope above is a **marginal** cost: what the *next* read costs once a
boundary already reads. The **first** read costs more, because it also buys
whatever the shell has to grow to hold a subscription at all, and this page
previously called the slope "the first read".

| | fitted marginal slope | first-read increment `y(R=1) − y(R=0)` |
|---|---:|---:|
| Reagent *(both landings miss the ratom path)* | **943 B** [935–944] | **1,137 B** [1,125–1,141] |
| UIx, the spine this ladder measured | 3,552 B [3,551–3,553] | 3,600 B [3,581–3,601] |
| **UIx, the shipping spine** *(less `.13`'s 769 B per read)* | **2,783 B** | **2,831 B** |

The first two rows are measured, both are quoted, and neither is derived from
the other; the third is the first arithmetically corrected onto the current
tree and cross-checked three ways in [§5](#5-what-this-hands-the-programme). On
UIx the marginal and first read sit within 1.4–1.7% of each other either way
and the distinction barely matters; on Reagent the first read costs **21%
more** than the marginal one, which is a real feature of that substrate and was
invisible while one number stood for both.

Three consequences, and the second is the one the programme has to act on.

1. **On memory, UIx is not the frontier — it inverts.** UIx wins the
   per-boundary shell (**208 B** against Reagent's **428 B**, 0.49×) and loses
   the read by **2.95×** on the shipping spine (2,783 against 943; it was
   3.77× on the spine this ladder measured, and `rf2-2rtt6.13` closed that
   fifth of the gap). The crossover is still *below one read*: any boundary
   that subscribes at all is cheaper on Reagent. The census's seven-read
   archetype costs **≈19,400 B/boundary on the raw UIx spine against Reagent's
   6,587 B** — 24,758 B before `.13`.
2. **The ~0.4–0.5 KB exclusive-retained budget is a SHELL budget, and nothing
   measured meets it for a boundary that reads once.** Both shells clear it
   (UIx 208 B, Reagent 428 B). At one read UIx is at **≈3,038 B** on the
   shipping spine — 3.0× the 1 KB paper-fail line, and 3,807 B before `.13` —
   and Reagent at **1,562 B**, 1.6×. The budget row in
   [validation.md](../validation.md) now says so explicitly: the target/fail
   line is the **R = 0 boundary shell**, and the per-read axis is judged
   separately under the ruling's regime-matched gates; see
   [§5](#5-what-this-hands-the-programme).
3. **HD-002's tier-1 exclusion now has a price in bytes.** The scalar per-read
   hook spine was excluded as product on hook-rule grounds (N reads = N hooks
   breaks HD-020's ≤2-hook budget). Memory says the same thing independently and
   louder: the comparator arm allocates **128.4 objects per read** against the
   Reagent path's **35.8**, at an almost identical **~26–28 bytes per object**.
   The spine is not allocating bigger things; it is allocating **3.58× as many
   of them**.

And a null result worth stating because its absence would have been the worse
finding: **neither arm retains anything after teardown.** Released heap returns
to baseline within ±11 B per boundary on every arm, and under ±6 B on almost
all of them — HD-002's clause-(d) survival metric, met by both substrates
([§4](#4-what-happens-after-teardown)).

---

## Method, and the fault it is built around

**Retention, never allocation.** V8's CDP *sampling* heap profiler drops the
samples of collected objects: pointed at a mount/unmount loop it reports the
residue of a page that has already been discarded, not the cost of the page —
the same 80,000 objects read **4.77 MB when a global held them and 0.00 MB when
nothing did**. That fault has already produced one wrong table on this surface.
So a reading here is: **mount an arm and keep it**, force a full collection,
read the heap; release, collect, read again.

**Readers.**

| | reader |
|---|---|
| **A** | CDP `Runtime.getHeapUsage().usedSize`, after 3× `HeapProfiler.collectGarbage` |
| **B** | in-page `performance.memory.usedJSHeapSize`, same moment, `--enable-precise-memory-info` |
| **C** | a full heap **snapshot**, every node's `self_size` summed by a streaming scan, as its own pass |

**A and B are not independent** — two doors onto one V8 counter — and this page
does not bank them as if they were. **C** walks the object graph and is the
reader that makes the table falsifiable. It also carries the object *counts*
that §1's third consequence rests on. **If C is requested and does not arrive
complete, the driver now exits 4** rather than printing a table on the two
correlated readers; that refusal is the audit's second repair.

**The in-situ positive control, predicted before anything is measured.** A dense
JS array of 587,500 doubles, which V8 stores as unboxed 8-byte slots:
**4,700,000 bytes, known in advance** — deliberately the same ~4.7 MB the broken
sampler once reported as 0.00 MB. It rides **every round**, on the same readers
as the arms.

| page | reader | predicted | measured | error |
|---|---|---:|---:|---:|
| Reagent | A | 4,700,000 B | 4,700,464 B [4,698,960–4,701,088] | **+0.010%** |
| Reagent | B | 4,700,000 B | 4,700,463 B | +0.010% |
| Reagent | C | 4,700,000 B | **4,700,024 B** | **+0.0005%** |
| UIx | A | 4,700,000 B | 4,700,084 B [4,694,914–4,701,024] | **+0.002%** |
| UIx | B | 4,700,000 B | 4,700,084 B | +0.002% |
| UIx | C | 4,700,000 B | 4,693,988 B | **−0.128%** |

Five of the six readings sit inside ±0.01%. **The sixth does not, and it is
stated rather than smoothed**: reader C on the UIx page read 6,012 B light on a
4.7 MB prediction. That page's snapshot pass walks a heap that reaches ~26 MB
above baseline at the 20-read rung, against ~8 MB on the Reagent page, and a
full-graph scan of a heap that size drifts more between its two reads. It is a
bound on reader C's resolution on the *larger* page, and it is two orders of
magnitude below the 3.77× this page reports.

**Verification: 0 unverified of 186 mounts** (93 per page). Every mount counts
the boundary elements it should have produced and answers the count beside the
expectation — an arm that silently rendered nothing would otherwise read as the
cheapest substrate in the table.

**Order.** Six rounds per page; the schedule rotates *and reflects*, so every arm
is measured after at least two distinct predecessors and both whole-plan orders
run. Even rounds forward, odd reversed, reported **separately, never as a mean**
— the pair exists to show the figure does not move, and by the house rule
overlapping ranges mean indistinguishable. **Position dominates adjacency**, so
an unread warm-up pass over every arm precedes round 1. The arm-order guard
(`order_guard.cjs`, self-tested before the bundle is built — **11 checks, all
passed**, including the two repairs PR #7267 landed: the k=2 ordering degeneracy
and the refusal-on-lost-samples) returned **clean on both pages**; it refuses on
a contaminated *or* an unchecked arm and the driver exits 2 when it does.

**Like-for-like, and why two pages.** Both arms read **re-frame2 subscriptions**
— the same registrar, the same `[:lad/cell i]` query vectors, one shared frame
behind every boundary. The difference measured is Reagent's `deref`-capture
against UIx's `useSyncExternalStore` spine, not one substrate's store against
another's. `rf/init!` installs at most one adapter per JS context, so each
substrate gets its own page load with **its own floor arms**; every figure below
is `arm − floor` **within a page and within a round**, which is what cancels the
box's drift.

**The floor** is plain React: the same element count, the same classes, the same
one-character text, and **no component per boundary and no reactivity at all**.
That is `b6-floor/w2`'s choice, and matching it is what allows the R=0 rung here
to be compared with the published sub-free rows.

**Every read is a distinct query.** Boundary *i* at *R* reads takes cells
`i·R … i·R+R−1`, so no two boundaries share a reaction and the per-read figure
carries the subscription as well as the hook. That is the editing-grid shape
(use-case A4, and A3's bulk row), and it is the honest worst case: a page where
many boundaries read *one* shared sub pays the reaction once, not once each.
The heap-regime ruling (rf2-2rtt6.16) makes that property normative —
**distinct-query (Q = E), mandatory worst-case witness, operative
upper-envelope family** — and a candidate row is judged only against donor rows
measured under the same regime on the same witness.

**The fit is over 1, 3, 7 and 20 — and over nothing else.** `validation.md`
requires the ladder to be measured directly and never inferred from a sub-free
rung, and R=0 is a sub-free rung. It rides along as an **anchor**: it is the
published `storm/uix` 251 B / `storm/reagent` 411 B shape rebuilt in this
harness, so a reader can see whether this instrument lands where the
predecessor's did before believing anything it says about reads. It is reported
everywhere and regressed nowhere.

---

## 1. Curve B — fixed boundaries × growing reads

1,200 boundaries, reads varying. `y` is retained bytes per boundary above the
1,200-boundary floor. Witness stamp: **B = 1,200 · E = 1,200·R · Q = E**
(fan-out 1). **A** is six rounds; **fwd**/**rev** split those six by
plan direction; **C** is the independent snapshot pass.

### Reagent on re-frame2 subs

| reads | A (6 rounds) | A, forward | A, reversed | C |
|---:|---:|---:|---:|---:|
| 0 *(anchor — excluded from the fit)* | 428 [421–434] | 422 [421–429] | 432 [427–434] | 429 |
| **1** | **1,562** [1,552–1,573] | 1,560 [1,555–1,568] | 1,563 [1,552–1,573] | 1,605 |
| **3** | **3,286** [3,275–3,342] | 3,290 [3,285–3,342] | 3,285 [3,275–3,287] | 3,300 |
| **7** | **6,587** [6,573–6,606] | 6,596 [6,575–6,606] | 6,579 [6,573–6,595] | 6,578 |
| **20** | **19,380** [19,215–19,399] | 19,381 [19,367–19,399] | 19,380 [19,215–19,393] | 19,373 |

> **marginal slope = 943 B per read** [935–944] · forward 942 [942–944] ·
> reversed 943 [935–944] · **fitted intercept = 397 B** [393–420] ·
> **first-read increment 1,137 B** [1,125–1,141] · r² 0.99878 [0.99875–0.99894]

### UIx on re-frame2 subs

| reads | A (6 rounds) | A, forward | A, reversed | C |
|---:|---:|---:|---:|---:|
| 0 *(anchor — excluded from the fit)* | 208 [201–213] | 207 [201–213] | 209 [206–212] | 211 |
| **1** | **3,807** [3,794–3,810] | 3,801 [3,794–3,808] | 3,809 [3,806–3,810] | 3,854 |
| **3** | **10,785** [10,770–10,801] | 10,797 [10,783–10,801] | 10,781 [10,770–10,787] | 10,811 |
| **7** | **24,758** [24,744–24,770] | 24,767 [24,763–24,770] | 24,748 [24,744–24,754] | 24,799 |
| **20** | **71,229** [71,216–71,241] | 71,231 [71,228–71,235] | 71,217 [71,216–71,241] | 71,300 |

> **marginal slope = 3,552 B per read** [3,551–3,553] · forward 3,553
> [3,552–3,553] · reversed 3,552 [3,551–3,553] · **fitted intercept = 113 B**
> [107–124] · **first-read increment 3,600 B** [3,581–3,601] · r² 0.99997

**Every rung's forward and reversed ranges overlap**, on both substrates, so by
this studio's own rule the plan direction is indistinguishable on every figure
above. The independent reader agrees with A to better than **1.0%** on UIx and
**2.8%** on Reagent (worst case the R=1 rung, 1,605 against 1,562).

**Both curves are lines.** r² ≥ 0.9987 in every round on both substrates. That
matters more than it looks: a per-read cost that grew or shrank with the number
of reads would mean the slope was not a per-read cost at all, and the fit is
what forecloses that reading. The r² is computed over the four reactive rungs
only, so it is a statement about 1–20 reads and not about a line drawn through
a point that reads nothing.

---

## 2. Curve A — fixed reads × growing boundaries

Three reads throughout; boundaries doubling. `y` is *total* excess over the
same-size floor, so the slope is bytes per boundary and the intercept is
whatever the page pays once. Witness stamp: **B = 300–2,400 · E = 3·B ·
Q = E** (fan-out 1). This curve never had an R=0 rung and is unaffected
by the fit correction; it is re-measured here because everything else was.

| boundaries | Reagent, fwd | Reagent, rev | UIx, fwd | UIx, rev |
|---:|---:|---:|---:|---:|
| 300 | 3,568 [3,568–3,574] | 3,524 [3,517–3,552] | 10,979 [10,965–11,177] | 10,971 [10,966–11,018] |
| 600 | 3,393 [3,384–3,420] | 3,412 [3,337–3,418] | 10,860 [10,840–10,874] | 10,872 [10,844–10,877] |
| 1,200 | 3,290 [3,285–3,342] | 3,285 [3,275–3,287] | 10,797 [10,783–10,801] | 10,781 [10,770–10,787] |
| 2,400 | 3,232 [3,227–3,236] | 3,224 [3,216–3,226] | 10,744 [10,738–10,747] | 10,750 [10,750–10,757] |

*(bytes per boundary, so the visible decline down each column is the page
constant amortising — which is the thing this curve exists to separate out.)*

| | bytes per boundary @ 3 reads | page constant | r² |
|---|---:|---:|---:|
| **Reagent** | **3,178** [3,165–3,188] | 128,760 B [103,249–133,669] | 0.99998 |
| — forward / reversed | 3,182 [3,173–3,188] / 3,173 [3,165–3,183] | | |
| **UIx** | **10,710** [10,694–10,724] | 88,122 B [75,507–120,064] | 1.00000 |
| — forward / reversed | 10,701 [10,694–10,711] / 10,718 [10,710–10,724] | | |

**The two curves agree, and that is the point of separating them.** Curve B's
corrected fit predicts a three-read boundary at `397 + 3·943 = 3,226 B` on
Reagent and `113 + 3·3,552 = 10,769 B` on UIx. Curve A, built from four
different arms and a different regression, answers **3,178 B** and **10,710 B**
— within **1.5%** and **0.6%**. A per-boundary cost measured by growing the
boundaries and a per-boundary cost inferred from growing the reads land on the
same number, so the decomposition into a shell plus a per-read term is not an
artefact of either fit. *(The agreement is slightly tighter than the superseded
publication's 1.7% / 0.8%, which is a small point in favour of the corrected
fit and not offered as evidence for it.)*

**The page constant is small and does not carry the result.** On the
1,200-boundary arm that Curve B is built from, it is **0.7% (UIx) to 3.4%
(Reagent)** of the boundary term. Across all four Curve A arms it ranges from
0.3% (the 2,400-boundary UIx arm) to 12% (the 300-boundary Reagent arm, the
smallest and thinnest on the page). So Curve B's per-boundary figures are
per-boundary figures, not a page constant divided by 1,200.

---

## 3. What the reads are made of

Reader C counts nodes as well as bytes. Both columns are **floor-subtracted**,
which the superseded table's R=0 and R=20 columns were not — the per-read
figure was unaffected, since the floor cancels in a difference, but the two
end-point columns read about 13 objects high.

| | objects/boundary, R=0 | per boundary, R=20 | **objects per read** | **bytes per object** |
|---|---:|---:|---:|---:|
| Reagent | 12.1 | 736.8 | **35.8** | 26.3 |
| UIx | 5.2 | 2,574.8 | **128.4** | 27.7 |

The two substrates allocate objects of **essentially the same size**. The entire
3.77× is **object count**: the UIx spine's `use-subscribe` — `useSyncExternalStore`
plus the two `useRef`s, the `useMemo` and the committed-snapshot bookkeeping
around it, per read — costs 3.58× as many objects as one `deref` of the same
subscription under Reagent's capture.

This is where 516 B belongs. `rf2-oob3g` put React's six hooks at 1,171 B for a
whole boundary and `useSyncExternalStore` alone at 516 B of that. Against
**2,783 B for one read** on the shipping spine the store hook is **18.5%** —
14.5% against the 3,552 B measured here, and it rose only because `.13` removed
a term the hook was never part of. Real either way, and nowhere near the whole
story. **A read is not a hook.** It is a hook stack *and* a
subscription: a reaction, a cache entry keyed by `(query-id, args)`, a query
vector, and a watch registration, all of which the Reagent arm pays too. Reagent's
943 B is the closest thing this page has to a floor for *the subscription
itself*, which makes it the number a grouped or collected read mechanism has to
beat — see §5.

`rf2-2rtt6.12` takes this decomposition further, ablating the spine's own hooks
to find where the 128 objects go.

---

## 4. What happens after teardown

Every arm is released, collected and read again in the same window. A substrate
whose released heap does not return to baseline is retaining something after
unmount, which is a different and worse finding than a large per-boundary figure.

Residue after release, bytes per boundary, median of six rounds:

| | worst arm | all reactive arms |
|---|---:|---:|
| Reagent | +10.5 (`a-r3-b300`) | +0.4 – +10.5 |
| UIx | +6.0 (`b-r20-b1200`) | −4.0 – +6.0 |

Floors read −3.6 to +0.8, which is the size of this instrument's zero. **Both
arms return to baseline.** HD-002's clause-(d) survival metric — *zero retained
per-occurrence objects after commit/teardown* — is met by both substrates, and
any candidate that misses it is missing something both donors already have.

---

## 5. What this hands the programme

**The red-zones for this witness family — distinct-query (Q = E), the
operative upper-envelope family.** Under the delegated ruling recorded on
`rf2-2rtt6.1` — superseded and closed on 2026-08-10, with red-zones now running
through budget gates `rf2-hic-089` (early) and `rf2-hic-071` (late) — as
regime-qualified by the heap-regime ruling (rf2-2rtt6.16), the UIx
retained-heap figure on this witness *is* the red-zone threshold. Witness stamp
B = 1,200 · E = 1,200·R · Q = E. **The rows are stated for the tree that ships
today** (Mike's ruling of 2026-07-31, option (a), on `rf2-e3flf`): a gate
measured on a spine two landings ago is looser than the tree warrants, and
loose is the unsafe direction for a gate. `.13` removed 769 B per unique query
key, which at Q = E is 769 B per read, so the correction is `−769·R`:

| axis | UIx red-zone, shipping spine | as this ladder measured it (pre-`.13`/`.25`) | superseded 2026-07-30 |
|---|---:|---:|---:|
| per-boundary shell (R=0, measured) | **208 B** [201–213] | 208 B | 212 B |
| per read (marginal) | **2,783 B** | 3,552 B [3,551–3,553] | 3,550 B |
| first read (increment) | **2,831 B** | 3,600 B [3,581–3,601] | *not published* |
| boundary @ 1 read | **3,038 B** | 3,807 B | 3,811 B |
| boundary @ 3 reads | **8,478 B** | 10,785 B | 10,788 B |
| boundary @ 7 reads | **19,375 B** | 24,758 B | 24,762 B |
| boundary @ 20 reads | **55,849 B** | 71,229 B | 71,232 B |

The shell is unmoved: `.13` was a per-read term and an R=0 boundary has no
read. The 2026-07-30 column is kept because it was once quoted; every figure in
it moved by less than 0.2% for a reason that had nothing to do with the spine
(a fit that used a forbidden rung), and the middle column supersedes it.

### The line a candidate is judged against, and how it was derived

**The operative gate line is UIx `2,935 B/read` [2,852–3,055] on the P0 bench
instrument, superseding `3,552 B/read`.** It is a **marginal slope**, not a
boundary total — the at-one-read total is the separate row above — and it is
stamped with the instrument it was taken on, because the two instruments in
play differ by a measured common-mode offset that no gate should silently
absorb.

**It stands on a direct two-point contrast, not on an independent
triangulation** — this section said the latter when it was first written, and
the audit of PR #7306 was right to refuse it. The number is unchanged, and so
is the band; what changes is the account of why either is trustworthy, which is
below.

The derivation is desk work over landed, gated data: no new campaign was run
for it. The [fan-out sweep](heap-fan-out-sweep.md) is the first heap page
measured *after* both landings, and its UIx arm is **M3 in both runs and every
round** (r² ≥ 0.9999).

**The primary source is a direct contrast, and it needs no model at all.** Two
of the sweep's rungs sit at Q = E — this witness family's own regime — and
differ by exactly one read and one unique key per boundary. Subtracting them
measures the gate's quantity outright:

```
R2Q2B − R1Q1 = 6,122 − 3,235 = 2,887 B/read   at B = 1,200
             = 6,155 − 3,185 = 2,970 B/read   at B = 300
                                mean  2,929 B/read
```

**The model decomposition says the same thing and explains it.** Under M3 with
Q = E the per-read slope is the sum of two terms, and the sum lands 0.2% from
the direct reading above:

```
M3          y = shell + (E/B)·edge + (Q/B)·key
Q = E   ⟹   E/B = Q/B = R
            y(R) = shell + R·(edge + key)

marginal per read = edge + key
                  = 1,345 B  [1,327–1,396]    per edge, UIx
                  + 1,590 B  [1,525–1,659]    per unique subscription key, UIx
                  = 2,935 B  [2,852–3,055]
```

**What these two are, and what they are not.** They are *not* two independent
readings. They come from one run on one instrument; they share the `R1Q1`
observation — it is a member of the R = 1 family the key slope is fitted
through — and the sweep's model verdict itself depends on `R2Q2B` through the
mandatory `key-ok?` admissibility check, so nothing here was held out of
anything. The corrected warrant is on
[the sweep's §4](heap-fan-out-sweep.md#4-the-additive-model). What they are is
one measured quantity stated two ways, agreeing to 0.2%, with the second way
telling you where the bytes go.

**Two readings that do come from elsewhere.** Both are on the *other*
instrument, so they corroborate the magnitude and not the bench number:

1. **This ladder's own slope, corrected.** `3,552 − 769 = 2,783 B/read`. The
   sweep's figure sits 5.5% above it, and +5.2% is exactly the common-mode
   offset the sweep measured twice between the two harnesses (Reagent +5.19%,
   UIx +5.17%) — a difference in boundary-component shape, not in the spine.
2. **A third instrument, measured after the fix.**
   [The spine decomposition](uix-spine-per-read-decomposition.md) reads the
   shipped UIx spine at **2,734 B/read** [2,731–2,735] post-`.13`. It sits 1.4%
   below this ladder pre-fix (3,501 against 3,552); scaled onto this ladder's
   instrument that is 2,774 B/read — **0.4%** from the reading above it.

So: **one quantity measured directly on the bench instrument and decomposed
there, plus two same-direction readings on two other instruments** — ~2,780
B/read on this page's instrument, ~2,935 B/read on the bench's. That is the
whole warrant, and it is a weaker claim than the "four readings, three
instruments" this section carried when it was first written, which counted the
sweep's overlapping arithmetics as separate evidence.

**What survives the correction, and what does not.** The line's *number* and
its *band* both survive, and the reason is that neither ever needed the
withdrawn claim: the two direct readings are 2,887 and 2,970 B/read, 2.9% apart
and both inside `[2,852–3,055]`, so the band is a fair statement of the spread
whether or not the additive model was validated out of sample. What does not
survive is the decomposition's standing as *independent* support. Treat
`1,345 + 1,590` as an explanation of the line rather than a second proof of it,
and treat the split itself as provisional to within the ~50 B the sweep's model
is free to move between `edge` and `step` — [its §6](heap-fan-out-sweep.md#6-what-this-unblocks)
says so row by row. **Nothing here is re-measured**: the operator deferred new
heap measurement on 2026-07-31, and this restatement is desk work over landed
data exactly as the ruling directed.

**Provenance of the restatement.** Whole-tree anchor **`61dd44950a`** — the
landed sweep commit, with `9df5094816` (`.13`) and `f784ab0adb` (`.25`) both
ancestors of it. A SHA does not survive a rebase, so the bench instrument's
blobs are beside it, all five under
`implementation/core/test/re_frame/bench/`:

| file | blob |
|---|---|
| `p0_heap.cljs` | `29a5d1087c2a78d3bc025657d414af2131d358fe` |
| `p0_run.cjs` | `c8c1756dde30a49042c197d6582702efb995d0c9` |
| `p0_reagent.cljs` | `49633402a26d17188987746b2f8f5f0e42213a27` |
| `p0_uix.cljs` | `d19b07697ecf11ce5640bf12f1c5438f6e5eb1da` |
| `p0_fixture.cljc` | `867ad5838ab64ac6aa7afbf8317d8fb305f53619` |

No run was taken for this restatement. Every figure in it is read off the
landed sweep, this ladder, and the landed spine decomposition.

**Which number governs a given candidate.** The one taken on the instrument the
candidate was measured on. Candidate arms live in the P0 bench
(`p0_run.cjs` / `p0_heap.cljs`), which is the sweep's instrument, so **2,935 B**
is the working line; a candidate measured on this ladder's instrument is judged
at **2,783 B**. The ~5% between them is unexplained and is not resolved here,
so **a margin under 5% is instrument-limited and is not a pass** — if a
candidate needs that margin, it needs a same-instrument donor row, not a
finer quotation of this one. Quoting either line to the byte is quoting offset.

**Reagent's line does not move.** `rf2-2rtt6.13` is a UIx-spine change and
`rf2-2rtt6.25` is a UIx hook change; neither goes near the ratom path. The
sweep's own post-landing two-point reading of the Reagent slope straddles the
published figure — `2,541 − 1,643 = 898 B` at B = 1,200 and
`2,658 − 1,590 = 1,068 B` at B = 300 — so nothing measured after the landings
moves **943 B/read** [935–944], and it stands as published.

**The inversion this page surfaced has been ruled on** (rf2-2rtt6.16, delegated
by Mike, 2026-07-31; transcription on rf2-2rtt6.1). On retained heap UIx is
2.95× worse than Reagent per read on the shipping spine — 3.77× when this page
measured it — and the crossover still sits below a single read, so a UIx-only
ceiling is ~3× looser than the best measured option. The ruling keeps **both
lines, regime-matched**, rather than re-sourcing the red-zone: the red-zone
stays UIx-sourced — worse than the line above is RED and needs an explicit
operator waiver naming the dogfood benefit — and Reagent's **943 B/read**
[935–944] governs through K3, worse than it with no named paper path down being
K3 territory. Between the two lines a candidate is **"UIx-rule cleared, K3 open
until a path down is named"**, never plain green. **943 B/read remains the
number a native layer actually has to beat**, and `.13` moved the ceiling
toward it without moving the floor.

**A target for HD-002's grouped tier.** Grouped `use-subs` — one fixed hook
receiving the whole query collection — can only remove the *hook* half of a
read. The subscription half is common to both arms. So the floor a grouped
mechanism can reach on this witness is bounded below by roughly Reagent's
**943 B/read**, and the prize for grouping is the difference: **≈1,900–2,000 B
per read**, or **≈13–14 KB on the census's seven-read archetype**. Both figures
are the sweep's own same-instrument subtraction (UIx 2,887 / 2,970 B against
Reagent 898 / 1,068 B), so no cross-instrument offset rides in them. `.13` took
roughly 600 B of that prize before any candidate could claim it — it was
≈2,600 B per read and ≈18 KB on the archetype — which is the point of restating
the line rather than stamping it historical: a candidate must now win the
remainder, not the whole.

**The budget row is now split, shell from read.**
[validation.md](../validation.md) sets *exclusive retained per boundary* at
~0.4–0.5 KB target, >1 KB paper-fail, and per the heap-regime ruling
(rf2-2rtt6.16, Part 3) that line is explicitly the **R = 0 boundary shell** —
both donors comply (Reagent ~418–428 B, UIx ~208 B on this page's one-prop
boundary). The per-read axis is judged separately under the regime-matched
gates above, so the shipped Reagent adapter is not retroactively K3-failed: its
~1,562 B at one read is shell (~428 B) plus first-read increment (~1,137 B),
each judged on its own axis — and a candidate cannot pass the paper line by
amortising subscriptions across boundaries. The component budget rows (shell /
per-edge / per-unique-key) were gated on the fan-out sweep, which has landed
and priced two of the three on both substrates and refused Reagent's per-edge
term. In this witness (Q = E) the 943 B slope is the *sum* of the per-edge and
per-unique-key terms — a valid total marginal cost in this regime only, not a
pure view-layer per-read price.

---

## 6. The Hicasso candidate rung — one hook plus a shared index

**Measured 2026-08-01 for `rf2-2rtt6.34`** (and discharging the per-read
half of `rf2-2rtt6.41`'s item 5), on the **P0 bench instrument** —
`p0_run.cjs --only ladder` — and not on the instrument §§1–4 were taken
on. That is deliberate and it is what [validation.md](../validation.md)
requires: a candidate is judged against the donor row taken on **its own
instrument**, the two instruments in play differ by a measured ~5%
common-mode offset, and a margin under 5% is instrument-limited rather
than cleared. So this section carries **three arms measured in one run,
in the same six rounds, under the same collector, the same positive
control and the same arm-order guard** — the two donors re-taken, and the
candidate beside them. Nothing here is scaled onto anything.

**Slope stamp — every candidate slope in this section went stale before it
merged, and no tree on `main` has ever answered it.** Run 3 below was taken
against `arm1/runtime.cljs` blob `69bfc6fc`; thirty-five minutes before this
section's own PR merged, `8e973eeace` (rf2-2rtt6.44's disposal hook) replaced
that blob on `main`, and the rebase-merge landed this section on top of it.
On every `main` tree since, the candidate's marginal slope reads **1,447
B/read** on the Reagent segment and **2,289 B/read** on the UIx segment —
+84 B (+6.2%) and +17 B (+0.7%) — while every donor figure and every shell
stands. The measured rows below are left exactly as measured, because a
record edited to match a later tree stops being a record; the attribution,
the mechanism and the restated live numbers — **the bill is +499 B/read, not
+415** — are in
[the slope subsection](#the-slope-went-stale-before-this-section-merged-and-the-landing-that-moved-it-rf2-2rtt660)
at the end of this section. **That bill has since been attacked and it is
now +390 B/read**, the candidate reading 1,338 / 2,175 — one of the three
things the section names as buying the gap turned out to be a per-key
identity the runtime did not need, one turned out not to exist at all, and
the third is filed:
[the attack](#attacking-the-gap-one-of-the-three-suspects-was-real-rf2-aqgr2),
also at the end of this section.

### The answer, first, including the part that does not flatter the design

**The structural claim is true and the axis is won against UIx. The
number the programme said had to be beaten is not beaten, and the shell
fails its own paper line.**

- **One hook per boundary instead of N is worth 708 B per read**, measured
  as a straight same-substrate contrast: on the UIx-adapter segment the
  UIx spine costs **2,981 B/read** and the Hicasso arm on the identical
  subscription substrate costs **2,272 B/read**. That is **0.7624×**, a
  23.8% margin — nearly five times the 5% instrument-limited floor, so it
  is a result and not an offset.
- **It lands below 2,000 B/read on one substrate and not on the other.**
  With Reagent's reactions underneath it the candidate reads **1,363
  B/read**; with the React spine's, **2,272 B/read**. The grouped tier's
  conceded ~2,000 B/read is therefore cleared only on the cheaper of the
  two, and which one a shipped Hicasso would sit on is not settled by
  this page.
- **943 B/read is not beaten.** On the *same* substrate as the K3 donor,
  the candidate costs **1,363 B/read against Reagent's 948** — **1.4379×**,
  and 415 B/read *worse*. The index, the key cell and the read-set entry
  are not free, and Reagent's `deref`-capture is cheaper than any of the
  three arms measured here.
- **The boundary shell fails the >1 KB paper line.** The candidate's R=0
  rung is **1,143 B** [1,132–1,156] against Reagent's 508 B and UIx's
  220 B. The one subscription hook is per *boundary*, so a boundary that
  reads nothing still pays it — which is the same structural choice that
  wins the slope, seen from the other end. **HD-028's memo wrapper has
  since been priced on this same rung and adds ≈ 100 B to it** — half the
  projected figure, and it is the shell row rather than the slope that
  moves: [below](#the-memo-wrapper-priced-on-this-rung-rf2-2rtt658).
- **Nothing is retained after teardown**, in bytes *or* in objects, on
  every arm of every round. [Below](#the-survival-metric-in-objects-and-not-only-in-bytes).

### The instrument reproduces both published gates before it prices anything

This ladder family is built from different arms, on a different plan, at
rungs the P0 bench had never carried, and fitted by a different rule from
the fan sweep the gate lines were derived on. Pointed at the two donors it
answers:

| | published gate | this ladder, same instrument | |
|---|---:|---:|---:|
| Reagent, per read | 943 B [935–944] | **948 B** [947–948] | +0.5% |
| UIx, per read | 2,935 B [2,852–3,055] | **2,981 B** [2,979–2,986] | +1.6%, inside the band |

**What that is, and what it is not.** It is a genuine reproduction of both
quantities by an independent arm family — nothing in the ladder's
arithmetic makes it come out at the fan sweep's numbers, and it could have
disagreed. It is **not** a check on the instrument's own ~5% offset
against the freehand harness, because it is the same instrument; and it is
not a second sample of the fan sweep, because it never uses the fan
sweep's rungs. Read it as *the donors are where the record says they are,
on the box the candidate was measured on*, which is the one thing a
same-instrument comparison has to establish first.

### What the rung is, and how the shape is verified rather than asserted

The arm **is** Arm 1 — `re-frame.bench.hicasso.arm1.runtime`, reached
through that arm's own `defview` and its ambient collector `sub`, mounted
through `arm1.mount/root!`. It is not a model of the design; a hand-rolled
imitation would have priced the imitation.

`stats` counts the runtime's own index and cell tables on every mount, and
the driver **exits** on the counts rather than printing them. Predicted
before the run and answered identically in all six rounds:

| | R = 0 | R = 1 | R = 3 | R = 7 | R = 20 |
|---|---:|---:|---:|---:|---:|
| boundaries (registrations) | 1,200 | 1,200 | 1,200 | 1,200 | 1,200 |
| edges (`b→subs`) | 0 | 1,200 | 3,600 | 8,400 | 24,000 |
| cells (unique `(frame, query)`) | 0 | 1,200 | 3,600 | 8,400 | 24,000 |
| read-set entries | 1 | 1,200 | 1,200 | 1,200 | 1,200 |

and **every one of those four fields reads 0 on every donor arm**, which
is the check that the candidate's runtime is not quietly standing behind
the rows it is being compared to. `boundaries` is flat in R — one
registration and one hook stack per boundary, whatever it reads — and
that flat row is the design claim, counted.

**The last row is the one to read carefully, and it is the honest half.**
`architecture.md` says index edges "live in global maps — shared
structure, not per-boundary object fan-out", and the read-set entry is
shared by every boundary whose read *set* is identical. On the mandatory
distinct-query witness **no two boundaries read the same set**, so
`entries` is B and the sharing buys exactly nothing here. The global maps
are shared *containers* holding B·R entries. This rung is the design's
worst case for that structure, exactly as it is the donors' worst case for
their subscription caches, and the page states it rather than quoting the
architecture's sentence over a measurement that contradicts it.

Hook count is **not** re-measured here: `arm1_hook_ledger_dom_cljs_test`
counts the shell's calls at React's own dispatcher and pins the budget at
two. This section takes that as given and prices what the budget costs.

### The rows

1,200 boundaries throughout; `y` is exclusive retained bytes per boundary
above the same-round, same-segment floor. Witness stamp: **B = 1,200 ·
E = 1,200·R · Q = E** (fan-out 1) on every rung of every arm. Six rounds;
ranges are min–max across them. **0 unverified of 154 mounts** — every
mount answers both read-backs, the boundary elements it produced *and* the
unique query keys the frame's sub-cache is holding.

#### Reagent-adapter segment

| reads | Reagent | Hicasso | Hicasso ÷ Reagent |
|---:|---:|---:|---:|
| 0 *(anchor — regressed nowhere)* | 508 [500–513] | 1,143 [1,132–1,156] | 2.248× |
| **1** | **1,675** [1,659–1,689] | **2,954** [2,935–2,967] | 1.764× |
| **3** | **3,390** [3,378–3,411] | **5,406** [5,380–5,420] | 1.595× |
| **7** | **6,704** [6,676–6,714] | **10,298** [10,292–10,306] | 1.536× |
| **20** | **19,569** [19,560–19,579] | **28,685** [28,619–28,706] | 1.466× |

#### UIx-adapter segment

| reads | UIx | Hicasso | Hicasso ÷ UIx |
|---:|---:|---:|---:|
| 0 *(anchor — regressed nowhere)* | 220 [213–226] | 1,134 [1,123–1,149] | 5.146× |
| **1** | **3,287** [3,270–3,318] | **3,855** [3,842–3,871] | 1.173× |
| **3** | **9,117** [9,107–9,125] | **8,049** [7,957–8,075] | 0.883× |
| **7** | **20,799** [20,789–20,808] | **16,457** [16,386–16,498] | 0.791× |
| **20** | **59,848** [59,816–59,923] | **46,833** [46,765–46,870] | 0.783× |

### The fitted lines

Over **1/3/7/20 and over nothing else**. R = 0 rides along as the anchor
and is regressed nowhere — the exclusion is in the code, and the fit
rule's own self-test proves it by feeding the fitter an absurd R = 0 rung
and requiring the line not to move by one bit. That is a regression guard
on the exact defect the audit of PR #7260 found in this page's
predecessor, and it is a check of arithmetic, not corroboration of a
measurement.

| arm | marginal slope | fitted intercept | shell (R=0, **measured**) | first read | r² |
|---|---:|---:|---:|---:|---:|
| Reagent | **948** [947–948] | 492 [478–503] | 508 [500–513] | 1,166 [1,155–1,179] | 0.99872 |
| Hicasso, Reagent substrate | **1,363** [1,360–1,364] | 1,271 [1,259–1,280] | 1,143 [1,132–1,156] | 1,812 [1,793–1,824] | 0.99903 |
| UIx | **2,981** [2,979–2,986] | 161 [143–182] | 220 [213–226] | 3,067 [3,044–3,099] | 0.99996 |
| Hicasso, UIx substrate | **2,272** [2,268–2,275] | 1,175 [1,158–1,200] | 1,134 [1,123–1,149] | 2,721 [2,703–2,748] | 0.99947 |

**All four are lines, in all six rounds.** r² ≥ 0.9987 everywhere, so the
slopes are per-read costs rather than something that grows with the number
of reads. The r² is computed over the four reactive rungs only. A page
whose per-read term were quadratic reads r² 0.9646 over these same
rungs — the fit rule's self-test measures that in advance rather than
assuming it — so the 0.98 floor is a criterion that can fail.

### The two contrasts that are level, and the one that is not

The candidate was mounted in **both** adapter segments. That is a
measurement, not a duplicate, and the first run of the row is what
established why: the Hicasso shell read **1,139 and 1,132 B** in the two
segments — the same number, because the shell touches no adapter — while
its per-read slope read **1,363 and 2,273 B**. The arm needs neither
adapter's *hooks*, but every read goes through `re-frame.subs`, and the
reaction `subscribe` builds is the installed adapter's. **The two
candidate columns are one view layer over two subscription substrates.**

So there are two level contrasts, each taken inside one segment, in the
same rounds, against the same floor:

```
within the UIx-adapter segment      UIx 2,981  −  Hicasso 2,272  =  −708 B/read
  what replacing N hook stacks with one hook plus N index edges is worth

within the Reagent-adapter segment  Hicasso 1,363  −  Reagent 948  =  +415 B/read
  what the key cell, the index edge and the read-set entry cost over a
  bare deref-capture of the same reaction
```

Neither is derived from the other; they come from different arm pairs on
different substrates. **The −708 is the design's win and the +415 is its
bill**, and both are real.

**The cross-segment ratio is not level and is marked as such.** Hicasso's
1,363 against UIx's 2,981 is **0.4572×**, and it is quoted nowhere in this
section's verdict, because it folds a substrate change into a view-layer
claim: it is the answer to "what if Hicasso ran on Reagent's reactions and
UIx ran on its own", which is not a question anyone asked. The two
within-segment figures above are what the row hands the programme.

**A shipped Hicasso would sit on neither.** It is an adapter for React
(`rf2-2rtt6.10`) and would install its own reactive substrate. The two
columns **bracket** that choice — a view layer cannot cost less than the
reactions it holds — and the bracket is wide: 1,363 to 2,272 B/read.

### The crossover, and where the archetype falls

Against the UIx spine the candidate pays a large fixed cost at the boundary
and saves **708 B on every read**, so it starts behind and ends ahead.
*Where* it crosses depends on which fixed term the arithmetic uses, and
this page states **one model: the two fitted lines**, whose own intercepts
are the only fixed terms that belong to them.

```
fitted lines   UIx      y =   161 + 2,981·R
               Hicasso  y = 1,175 + 2,272·R

crossover   =  (1,175 − 161) / (2,981 − 2,272)  =  R = 1.43 reads
               MODEL-INFERRED — no R = 2 rung was measured
```

**Direct observation brackets the crossing; it does not locate it.** The
candidate is behind at R = 1 — **1.173×** UIx — and ahead at R = 3 —
**0.883×** — in all six rounds, both directly measured. The crossing
therefore lies somewhere in (1, 3). That bracket is the evidence. The 1.43
is what the model says inside it, and **anything this page or anything
downstream reads as the candidate being ahead at R = 2 is model-inferred**,
because no R = 2 rung exists on this ladder.

**Why the model's answer sits low in its own bracket, stated rather than
smoothed.** Both arms cost more on their first read than on their marginal
one — UIx 3,067 against 2,981, the candidate 2,721 against 2,272 — so a
line fitted over the reactive rungs under-predicts R = 1 on both, by 145 B
and 408 B. Anchoring instead on the *measured* R = 1 pair and applying the
−708 B/read after the first read would put the crossing near R = 1.8. That
is a second model and this page does not quote it as a result; it is named
here so that 1.43 is read as one model's output inside a measured bracket,
and not as a quantity known to two significant figures.

**The archetype is past the crossing under either model, and by direct
measurement.** The census's seven-read archetype reads **16,457 B against
20,799 B, a 4,341 B/boundary saving** — 5.2 MB across 1,200 boundaries —
with no model standing between that row and its reading. Against Reagent
there is no crossover at all: the candidate is worse at every rung, and the
ratio only *improves* with reads (1.764× → 1.466×) because the shell
amortises.

**What this row published before, and why it was withdrawn
(`rf2-2rtt6.34`).** It read `(1,134 − 220) / (2,981 − 2,272) = R = 1.29
reads`, dividing the **measured** R = 0 shell difference by the **fitted**
marginal-slope difference. The fit excludes R = 0 by rule — that exclusion
is the whole point of [the fitted lines](#the-fitted-lines) — so those two
quantities belong to no single line, and 1.29 was neither the fitted
crossing nor an observation of one. Nothing measured moved; the arithmetic
did. The withdrawn value is left visible here rather than erased, because
it was quoted downstream.

### The survival metric, in objects and not only in bytes

HD-002 clause (d) is *zero retained per-occurrence objects after
commit/teardown*, and this row answers it twice.

**In bytes.** Released heap returns to baseline. Across all twenty-two
arms the per-boundary residue means span **−35 B to +21 B**, most of them
inside ±16 B, against floors reading **+1 to +4 B** — which is the width
of this instrument's zero. Single rounds swing wider (−116 to +58
B/boundary, worst on the 7- and 20-read arms), and that is worth stating
rather than smoothing: it is a bound on this reader's resolution on an
arm that is itself 16–60 KB per boundary, and it is two orders of
magnitude below the quantities the section reports. It is also looser
than the freehand ladder's ±11 B/boundary, on arms several times larger.

**In objects, which is the stronger reading.** The candidate's own index
and cell tables are counted after the collector has run: `cells`,
`cell-refs`, `boundaries`, `edges` and `entries` all read **exactly 0**
after every release of every arm of every round — 0 of 0 tolerance, not a
range. The teardown is React's own `useSyncExternalStore` cleanup plus the
runtime's reapers; **`arm1.mount/release!`'s `reset-runtime!` is
deliberately not called**, because an arm torn down by force would answer
zero residue whatever it had leaked. HD-002 clause (d) is met, and met
without the sledgehammer.

### What could not be levelled, and which way each biases

1. **The two subscription substrates.** Handled above: within-segment
   contrasts are level and are what the verdict rests on; the
   cross-segment ratio is marked and unused.
2. **The candidate's boundary is built by the runtime codec**, and the
   donors' by UIx's compile-time `$` and Reagent's own path. That cost is
   constant in R, so it lands in the **shell and not in the slope** — the
   1,139 B figure is a Hicasso-plus-codec shell and is not decomposed
   here. **Biases against the candidate on the shell axis** and not at all
   on the per-read axis this section is for.
3. **The hook budget is cited, not re-measured.** Two hooks per boundary
   is `arm1_hook_ledger_dom_cljs_test`'s dispatcher-level count. If it were
   three, the shell figure would be explained and the slope would not
   move.
4. **Three runs, one box.** Every range above is across the six rounds of
   run 3, the run taken against the Arm 1 this branch merges onto. Two
   earlier complete runs — one of them against four superseded arm blobs —
   agree to **0.03%** on every slope; see
   [the three runs](#the-three-runs-and-the-arm-the-last-one-was-taken-against).
   So the figures are not one session's and not one arm revision's. They
   are still one machine's, and no claim here is made about a second box.

### The three runs, and the arm the last one was taken against

**The rows above are run 3**, and run 3 is the only one taken against the
Arm 1 that this branch actually merges onto. That distinction is the
whole reason this subsection is long.

Runs 1 and 2 were taken at this branch's original base. While they were
being taken, four of the five blobs the candidate arm is *made of* moved
on `main` — `arm1/runtime.cljs` (+164 lines), `front/codec.cljs` (+188),
`arm1/mount.cljs` (+32), `front/sub_index.cljs` (+15). A row whose
provenance table names blobs that are not in the merged tree is not
reproducible, whatever its numbers say, so the branch was rebased and the
row **re-taken on the landed arm**.

| | run 1 | run 2 | **run 3 (published)** |
|---|---:|---:|---:|
| Arm 1 blobs | pre-rebase | pre-rebase | **landed** |
| Reagent, per read | 948 [947–949] | 947 [947–948] | **948** [947–948] |
| Hicasso, Reagent substrate | 1,363 [1,360–1,364] | 1,363 [1,360–1,364] | **1,363** [1,360–1,364] |
| UIx, per read | 2,980 [2,978–2,985] | 2,981 [2,979–2,985] | **2,981** [2,979–2,986] |
| Hicasso, UIx substrate | 2,273 [2,268–2,275] | 2,273 [2,268–2,275] | **2,272** [2,268–2,275] |
| Hicasso shell (Reagent seg.) | 1,139 [1,131–1,150] | 1,138 [1,129–1,147] | **1,143** [1,132–1,156] |
| Hicasso shell (UIx seg.) | 1,132 [1,120–1,145] | 1,135 [1,119–1,148] | **1,134** [1,123–1,149] |
| positive control | 4,698,439 B | 4,697,764 B | **4,698,615 B** |

All three: **0 unverified of 154 mounts**, **0 structural read-back
failures**, arm-order verdict **reportable**, exit **0**. Every slope
agrees across all three to **0.03%** and every shell to **0.5%**, and
every per-round range overlaps its counterparts — indistinguishable on
every figure by this studio's own house rule.

**That agreement is itself a result, and it is the one to read.** Those
399 changed lines of runtime, codec, mount and index are **per-read
retained-heap-neutral**: they moved the candidate's slope by 1 B in 2,272
and its shell by at most 5 B in 1,134. Whatever those landings did — and
they were not aimed at this axis — they did not spend or recover memory
on it. A row that had simply been re-quoted after the rebase would have
been *approximately* right and would have proved nothing; re-taking it is
what turns "probably still true" into a measurement.

**Two things this does not claim.** It is still one machine — no figure
here is evidence about a second box. And runs 1 and 2 are kept in the
table as the pre-rebase pair rather than deleted, because a record that
quietly drops the readings taken against superseded code is a record that
cannot be audited for exactly the fault that made the re-take necessary.

**On the outage.** An API outage took down this worker and all five
siblings partway through this bead. Run 1 had finished cleanly two and a
half hours before it — 6 of 6 rounds, 154 of 154 mounts, the driver's own
`done` line — so no published row was ever taken across it. Runs 2 and 3
were both taken afterwards on a verified-quiet box, run 3 after waiting
out a resumed sibling's browser suite.

### Provenance

Whole-tree anchor for **run 3, the published run**: `worker/heapaxis-2rtt6-34`
rebased onto `main`. A SHA does not survive a rebase — and this branch was
rebased mid-bead, which is precisely how the re-take came to be necessary —
so **the blobs are what to trust**. The instrument:

| file | blob |
|---|---|
| `p0_run.cjs` | `4718aaead7035ae9a6cf74a89ef13141803742cc` |
| `p0_heap.cljs` | `34c9210dfe39d3c7ee153c724fa63cf8e65dd1e1` |
| `p0_hicasso.cljs` | `f2440e307423665048dfe227b14baaf4ffc8ac89` |
| `p0_reagent.cljs` | `b1f5ec9223536557403f6ae9415ab42ac26843b0` |
| `p0_uix.cljs` | `deec8976010c17e4d2c6e8dc3499678997acd2c0` |
| `p0_fixture.cljc` | `867ad5838ab64ac6aa7afbf8317d8fb305f53619` |

all under `implementation/core/test/re_frame/bench/`. **The candidate arm
is not in that list**, because the candidate arm is Arm 1 itself — these
are its blobs, under `implementation/freehand/test/re_frame/bench/hicasso/`:

| file | blob |
|---|---|
| `arm1/runtime.cljs` | `69bfc6fc23af3035af88a2f69c4f4623a869fd83` |
| `arm1/mount.cljs` | `4653e168d08dcd91386df4e78b3cd5b0b5cf9267` |
| `arm1/lang.clj` | `0151ddafb4aefe6a6a2403a349187ae5b28cc537` |
| `front/sub_index.cljs` | `394927d6f6493ea651daac84b9f140cd54f8f6c1` |
| `front/codec.cljs` | `5eb17dbd199eec481b60d66adbc502c6abaa9b57` |

Four of those five moved between run 1 and run 3, and only `lang.clj` — the
two-macro file — did not. Runs 1 and 2 were taken against
`be66197c` / `ca15137e` / `8c63e213` / `5d3e5c32`, recorded here so the
pre-rebase readings in [the three runs](#the-three-runs-and-the-arm-the-last-one-was-taken-against)
remain checkable rather than merely asserted.

Reproduce:

```
node implementation/core/test/re_frame/bench/p0_run.cjs --only ladder
```

(defaults `P0_LADDER_ROUNDS=6 P0_LADDER_RUNGS=0,1,3,7,20 P0_ROOTS=4`;
exits **1** on an unverified mount, a failed positive control or a failed
structural read-back, and **2** if the arm-order guard refuses.)

**Conditions.** Every run is six rounds and was taken on a box checked for
live bench, browser and shadow-cljs processes first, because a contended row
on this instrument is invalid. Run 1: 221 s, no sibling suite running,
CPU < 15%. Run 2: 247 s, every sibling worker down. **Run 3, the published
run: 236 s, after waiting out a resumed sibling's browser suite to CPU 0%**
([above](#the-three-runs-and-the-arm-the-last-one-was-taken-against)). An
earlier one-round trial was taken *during* a sibling's JVM compile and is
not published; its slopes were 949 / 2,979 / 1,359 / 2,273 B/read. Two of
those four sit inside run 3's six-round ranges and **two sit exactly one
byte outside** — Reagent 949 against [947–948], and Hicasso on the Reagent
substrate 1,359 against [1,360–1,364], each about 0.07% out. It is recorded
exactly, because a contended reading that lands within a byte is still a
contended reading: it is not evidence for the rows above, and treating the
near-agreement as licence to skip the quiet is the reasoning the requirement
exists to stop. What it does show is the size of the effect being guarded
against here — small, and on the same side of the line as the quantities
being compared, which is why it is guarded against rather than corrected
for. The
positive control — the
same 587,500-double array, **4,700,000 B predicted before the run** — read
**4,698,615 B** [4,690,838–4,700,872] on run 3, **0.03% low**, and inside
0.05% on all three runs. React **19.2.0**,
Reagent **2.0.1**, UIx **1.4.4**, `:advanced` with `goog.DEBUG false`,
headless Chromium via Playwright, Windows 11.

**What this section deliberately does not do.** It writes no candidate-bar
row into [validation.md](../validation.md). `rf2-2rtt6.41`'s item 5 keeps
those rows unpublished under the restated-bar posture (`rf2-b0tz5`), and
the measurement being available is not the same act as the bar being
restated. The numbers are here, on the studio page, for the operator to
rule on.

### The memo wrapper, priced on this rung (rf2-2rtt6.58)

> **HISTORICAL, and not comparable to the tree that ships (2026-08-04).** These
> rows were taken on `worker/cascade-2rtt6-52` at tree `4a33c61e1c`, an authored
> head the rebase merge stranded; the same change landed as `cb179b6b3c`, which
> resolves. The landed commit anchors the **patch** and not this **tree** — the
> rebase rewrote both blobs under test — so the two are not interchangeable, and
> the head above is the only thing that names what was measured. The
> provenance paragraph below says the recorded blobs "will prove the measured
> tree and the merged tree agree once it is rebased" — **they do not agree**:
> the measured runtime and codec blobs were `a6d6c55a58` and `12284ef8f4`, and
> after PR #7390 and the landings that followed, the shipped blobs are
> `9f3d42be7b` and `cf9ef32dc8`. The audit on `rf2-2rtt6.58` (PR #7392) reopened
> the bead for exactly that.
>
> **The wrapper delta below survived the re-take almost exactly** — +105.5 and
> +105.0 B against the +106 and +98 B here — but the **base did not**, and one
> conclusion drawn from the base is now false. On the tree that ships the
> no-wrapper shell reads **994 / 992 B**, not 1,141 / 1,138 B, so the claim
> repeated throughout this subsection that the shell *was already over the 1 KB
> line before any wrapper existed* **no longer holds**.
> [The re-take](#the-memo-wrapper-re-taken-on-the-tree-that-ships-rf2-2rtt658-re-take)
> is the current price. Nothing below is edited; it is kept as the record of
> what was measured on that branch.
>
> One arithmetic correction the audit also named, recorded here rather than
> silently fixed in the table: the UIx row's `1,138 → 1,236` is **+98 B,
> +8.6%** — not the +100 B / +8.8% printed below.

**Measured 2026-08-02** for `rf2-2rtt6.58`, to answer the one question
[HD-028](../decisions.md#hd-028--value-equality-is-the-boundary-default) left
open when it made a value-equality bail-out the boundary default: what its
extra Fiber does to **this** rung — the R=0 shell, which is the figure
[validation.md](../validation.md)'s paper line is stated against, and which
was already over that line at 1,143 B before any wrapper existed.

**The answer is ≈ +100 B, not the ≈ +200 B the card-shaped reading implied,
and ≈ +9%, not ≈ +17%.** The projection was carried in three places — this
bead, HD-028's cost paragraph, and
[the page-chrome row](the-page-chrome-row-and-what-the-bail-out-costs.md#3-per-boundary-retained-heap--the-fiber-priced)
— and all three are corrected against the measurement rather than left to
stand beside it.

#### Which instrument this had to be taken on, and the mis-citation it corrects

`rf2-2rtt6.58` and the page-chrome row both name
`implementation/freehand/test/re_frame/freehand/bench/reads_ladder_run.cjs` as
"the instrument that produced 1,143 B". **It is not, and it could not have
been.** 1,143 B is published in [§6](#6-the-hicasso-candidate-rung--one-hook-plus-a-shared-index)
above, whose own opening sentence names the **P0 bench** — `p0_run.cjs --only
ladder` — "and not on the instrument §§1–4 were taken on". The freehand ladder
carries `reagent,uix` and nothing else: it has no Hicasso arm, never reaches
`re-frame.bench.hicasso.*`, and so cannot mount the wrapper at all. A run there
would have produced no candidate row to compare. This re-take is therefore on
`p0_run.cjs --only ladder`, and the six P0 instrument blobs below are
**byte-identical to §6's published run 3** — same instrument, not merely the
same kind of instrument.

The mis-citation is traceable and is fixed at its source: the page-chrome row's
§3 said "re-taken on the ladder rig itself (`reads_ladder_run.cjs`)", and the
bead was filed from that sentence.

#### Design: both arms in one session, and a third run to catch drift

The two arms differ by **one line** in
`arm1/runtime.cljs`'s `mint-view!` — it returns
`(codec/memoize-boundary! (codec/mark-boundary! component))` against
`(codec/mark-boundary! component)`. That is the wrapper's only call site in the
repository, so removing it removes the wrapper and nothing else; the rest of the
`rf2-2rtt6.52` branch, `codec.cljs` included, is identical across both arms.

Runs are **A → B → A**, all three in one session on a box verified quiet before
each. A1 and A2 bracket B in time, so if the box moved under the pair the two
wrapper readings separate and say so. **They do not: A1 and A2 agree to 0 B on
the Reagent segment and 5 B on the UIx segment.**

**A first A/B pair was taken and is discarded, not reported.** An API outage
killed the worker while its B arm was running; under `rf2-6t03c` a run that dies
mid-way is discarded, and because the arms are paired *within* a session the
surviving A arm is discarded with it rather than matched against a later B. Both
arms below were re-taken afterwards.

#### The rows

1,200 boundaries; `y` is exclusive retained bytes per boundary above the
same-round, same-segment floor. Witness stamp: **B = 1,200 · E = 0 · Q = 0**
(R = 0 reads nothing, so the distinct-query regime is vacuous on this rung and
the fan-out E/Q is undefined rather than 1). Six rounds per run; ranges are
min–max across them. **0 unverified of 154 mounts** in each of the three runs.

| R=0 shell | A1 *(wrapper)* | B *(no wrapper)* | A2 *(wrapper)* | delta |
|---|---:|---:|---:|---:|
| Hicasso, Reagent segment | **1,247** [1,240–1,257] | **1,141** [1,125–1,154] | **1,247** [1,239–1,267] | **+106 B, +9.3%** |
| Hicasso, UIx segment | **1,236** [1,227–1,250] | **1,138** [1,119–1,159] | **1,241** [1,227–1,261] | **+100 B, +8.8%** |

**The A and B ranges are disjoint on both segments** — an 85 B gap on the
Reagent segment and 68 B on the UIx segment — so by this studio's own house rule
the two arms are distinguishable, which is the thing a 100 B delta on a 1,140 B
figure has to establish before it may be quoted.

**B reproduces the published anchor.** 1,141 and 1,138 B against §6's 1,143 and
1,134 B, every range overlapping. That is a second result riding along: the
`rf2-2rtt6.52` branch's other changes — 112 lines of `codec.cljs` — are
**shell-neutral**, and the whole of the shell movement is the wrapper.

#### The wrapper is exactly R-independent, which is what the ruling predicted

HD-028 priced the wrapper as a per-boundary constant, so it must land in the
shell and leave the per-read slope alone. It does, to the byte:

| marginal slope | A1 *(wrapper)* | B *(no wrapper)* | A2 *(wrapper)* |
|---|---:|---:|---:|
| Hicasso, Reagent segment | 1,447 [1,444–1,448] | 1,447 [1,444–1,448] | 1,447 [1,443–1,448] |
| Hicasso, UIx segment | 2,289 [2,284–2,291] | 2,289 [2,284–2,291] | 2,289 [2,283–2,291] |

**Identical medians across all three runs on both segments.** A wrapper that had
leaked into the per-read term would have shown here, and it does not.

#### The controls

**Negative controls — the donors, which cannot see the toggle.** Neither Reagent
nor UIx goes through `mint-view!`, so they must not move between arms. They do
not, which is what licenses reading the Hicasso difference as the wrapper rather
than as the box:

| donor | A1 | B | A2 |
|---|---:|---:|---:|
| Reagent shell (R=0) | 506 [499–517] | 506 [501–513] | 507 [498–516] |
| UIx shell (R=0) | 221 [217–226] | 223 [213–230] | 225 [217–232] |
| Reagent per read | 947 [947–948] | 948 [947–948] | 948 [947–948] |
| UIx per read | 2,981 [2,979–2,986] | 2,980 [2,978–2,985] | 2,981 [2,979–2,985] |

**Positive control, predicted before any run.** The same dense array of 587,500
unboxed doubles — **4,700,000 B, known in advance**. Read **4,697,434 B**
(−0.055%), **4,697,603 B** (−0.051%) and **4,697,603 B** (−0.051%). The
pre-registered prediction was ±0.05% and all three land marginally **outside**
it, at 0.05–0.06% low; that is recorded rather than rounded into compliance. It
is common-mode across the arms — the same offset on the wrapper run and the
no-wrapper run — so it cannot manufacture a difference between them, and it sits
three orders of magnitude inside the driver's own ±25% verdict slack.

Arm-order guard **reportable**, structural read-back **every field answered**,
and exit **0** on all three runs.

#### Why this instrument reads half of what the card-shaped one did

The page-chrome row measured **+195 and +220 B** per boundary for the same
wrapper; this rung reads **+100 to +106 B**. Both reproduce on their own
instrument, so the disagreement is real and belongs to the shapes, not to a bad
run. **What produces it is not yet known, and the first candidate has been
excluded.**

> **Correction, 2026-08-03 (`rf2-2rtt6.61`).** This paragraph offered React's
> double buffering as the leading explanation: the ladder mounts and *holds*, so
> the wrapper costs one extra fiber, while the page-chrome instrument was said to
> perform its four write ops before reading heap, so an updated component would
> retain an `alternate` fiber beside its current one and a wrapper position there
> could be holding two. Roughly 2× is what that predicts, and roughly 2× is what
> the instruments differ by. It was published as a hypothesis and not as a
> result, because neither run counted fibers.
>
> **Its premise is false, and the instruments' own source says so.**
> `chrome_run.cjs` takes the whole heap half FIRST, on a quiet page —
> `heapForArms` at line 243, under the comment *"the heap half, first, on a quiet
> page"*, and the clock half's `runOp` calls do not begin until line 264.
> `heapOnce` (lines 164–182) is `resetRuntime` → baseline read → `mountArm` →
> held read, with no op between the mount and the read. The same ordering is
> present at `cb179b6b3c`, the commit that published the +195/+220 figures, so
> those numbers were taken on a held tree exactly as this rung's were — as is the
> ladder's own window (`p0_run.cjs` lines 588–593: gc, pre-read, `mountOne`, gc,
> held read).
>
> **Both instruments mount and hold.** Double buffering cannot be what separates
> them, and the general rule the bead proposed — *a memo wrapper costs one fiber
> on a held tree and two on a tree that has been updated* — is **not** adopted:
> no measurement on this programme has read an updated tree's wrapper at all. The
> exclusion is settled from committed source; no fiber count was needed to reach
> it, and none was taken.
>
> **What separates them is still open**, and is `rf2-2rtt6.79`. It asks for the
> fiber count on a different question: whether the wrapper is ONE fiber on both
> shapes, in which case the difference is in what a fiber *holds* rather than in
> how many there are — a 17-element card reading two subscriptions against an
> R=0 cell reading nothing is an order of magnitude apart in retained bytes per
> boundary, against a 2× in what the wrapper adds to it.

The practical reading is the one the ruling asked for and it is unchanged by any
of this: **a Fiber is not a fixed tax, and the shape it sits on decides what it
costs.** Quoting either instrument's figure onto the other's shape is the error
this rung exists to prevent.

#### What this settles, and what it hands the operator

**Settles.** The wrapper costs **≈ +100 B** on the R=0 shell — **+9%**, moving it
from **1,141 B to 1,247 B**. The ≈ +17% / ≈ 1,343 B projection carried by the
bead and by HD-028 was an over-estimate by about a factor of two. The delta is
above the **~75 B** shape sensitivity [validation.md](../validation.md) names for
this line, so it is not shape noise — though not by a wide margin, and that is
worth saying plainly.

**Does not settle, deliberately.** Whether ≈ +9% on a figure **already 1.14×
over** the 1 KB paper line is "pushing retained heap meaningfully farther past
the bar" in HD-028's sense. **That clause is not quantified anywhere** — not in
HD-028, not in validation.md, not in the heap-regime ruling — and HD-028's
reopen clause is worded as *failing* the bar, which this shell already did at
1,141 B without any wrapper. The wrapper cannot cause a failure that pre-dates
it; what it does is widen one. Whether that widening is acceptable for a
**default** is the operator's call under the restated-bar posture (`rf2-b0tz5`),
exactly as §6's own shell row was left to be — and this section, like §6, writes
**no candidate-bar row into validation.md**.

#### Provenance

Whole-tree anchor **`4a33c61e1c`** on `worker/cascade-2rtt6-52`. That branch was
**conflicting with `main` and deliberately not rebased while these rows were
taken**, so the blobs are what to trust — and they are what will prove the
measured tree and the merged tree agree once it is rebased. The rebase came, and
stranded that head: the change is on `main` as `cb179b6b3c`, an anchor for the
**patch** only. It is not the tree these rows were taken on — the rebase moved
`arm1/runtime.cljs` and `front/codec.cljs`, which are precisely the two blobs
under test — so it is quoted here to be resolvable, never as a substitute. (The
callout opening this section records what the disagreement cost.)

The one line under test, and the only thing that differs between the arms:

| arm | `arm1/runtime.cljs` blob |
|---|---|
| **A1, A2** — wrapper present | `a6d6c55a5885689cb62dcf444da2e58562d53d23` |
| **B** — wrapper absent | `5b4f4e065f689f955a8750c9533c75650a3077a3` |

`front/codec.cljs` is `12284ef8f47d99e909e4ca40326310262d6f7c6a` in **all three**
runs — the wrapper's definition is present throughout and merely goes uncalled
in B. The remaining Arm 1 blobs are unmoved from §6: `arm1/mount.cljs`
`4653e168d08dcd91386df4e78b3cd5b0b5cf9267`, `arm1/lang.clj`
`0151ddafb4aefe6a6a2403a349187ae5b28cc537`, `front/sub_index.cljs`
`394927d6f6493ea651daac84b9f140cd54f8f6c1`.

The instrument, **byte-identical to §6's run 3**, all under
`implementation/core/test/re_frame/bench/`:

| file | blob |
|---|---|
| `p0_run.cjs` | `4718aaead7035ae9a6cf74a89ef13141803742cc` |
| `p0_heap.cljs` | `34c9210dfe39d3c7ee153c724fa63cf8e65dd1e1` |
| `p0_hicasso.cljs` | `f2440e307423665048dfe227b14baaf4ffc8ac89` |
| `p0_reagent.cljs` | `b1f5ec9223536557403f6ae9415ab42ac26843b0` |
| `p0_uix.cljs` | `deec8976010c17e4d2c6e8dc3499678997acd2c0` |
| `p0_fixture.cljc` | `867ad5838ab64ac6aa7afbf8317d8fb305f53619` |

Reproduce — the wrapper arm as it stands, the no-wrapper arm by reverting the one
line above:

```
node implementation/core/test/re_frame/bench/p0_run.cjs --only ladder
```

**Conditions.** Three runs, ~3.5 minutes each, 2026-08-02 13:23–13:35 AUSEST, on
a box checked before **each** run for live bench, browser and shadow-cljs
processes — no process consumed more than 0.5 CPU-seconds over a 5-second sample
apart from the checking shell itself and an idle browser tab. React **19.2.0**,
Reagent **2.0.1**, UIx **1.4.4**, `:advanced` with `goog.DEBUG false`, headless
Chromium via Playwright, Windows 11. One machine; no claim here is evidence about
a second box.

### The slope went stale before this section merged, and the landing that moved it (rf2-2rtt6.60)

**Measured 2026-08-02 for `rf2-2rtt6.60`.** The rf2-2rtt6.58 session (the
memo-wrapper pricing, taken on `worker/cascade-2rtt6-52`) observed the
candidate's marginal slope at **1,447 B/read** on the Reagent segment and
**2,289 B/read** on the UIx segment — +84 B (+6.2%) and +17 B (+0.7%) over
the rows above — while both donors, all four shells and all six instrument
blobs reproduced this section's run 3 exactly. Not the wrapper (toggled off
to no effect in that same session), not the box, not the instrument:
something in the trees had moved the candidate, and nothing had attributed
it. This subsection is the attribution by bisection, the mechanism, and the
restatement of every live number this section hands the programme. At 1,447
the bill below becomes **+499 B/read — a 20% increase in a figure the
programme quotes** — which is why the attribution could not wait for the
branch that surfaced it to merge.

#### The bisection: four runs, two of which decide everything

All four on this instrument — `p0_run.cjs --only ladder`, six rounds, same
collector, same guard, same discipline as run 3 above — on 2026-08-02, box
verified quiet before each (no live bench, browser or shadow-cljs process).
The donors ride every run as the negative control and answer the published
948 / 2,981 in all four; the positive control was predicted at **4,700,000 B**
before any run and read within 0.06% low in all four; **0 unverified of 154
mounts, structural witness fully answered, arm-order guard reportable, exit
0 — every run.**

| run | tree | Hicasso, Reagent seg. | Hicasso, UIx seg. | donors (Rg / UIx) |
|---|---|---:|---:|---:|
| *§6 run 3 (2026-08-01, above)* | *runtime blob `69bfc6fc`* | *1,363 [1,360–1,364]* | *2,272 [2,268–2,275]* | *948 / 2,981* |
| 1 | the `main` ↔ `worker/cascade-2rtt6-52` merge-base, `8d0e06f6d3` | **1,446** [1,443–1,447] | **2,289** [2,284–2,291] | 948 / 2,980 |
| 2 | PR #7337's own merge commit, `4d3331fad5` | **1,447** [1,444–1,448] | **2,289** [2,284–2,291] | 947 / 2,981 |
| 3 | `4d3331fad5` minus the one `interop/add-on-dispose!` line | **1,343** [1,340–1,344] | **2,253** [2,248–2,255] | 947 / 2,981 |
| 4 | `main` tip `316d34ef81`, the tree that ships today | **1,447** [1,444–1,448] | **2,289** [2,284–2,291] | 947 / 2,981 |

**Run 1 answers the filed question first: the merge-base already reads the
shifted slope, so a `main` landing owns the move and
`worker/cascade-2rtt6-52` is exonerated.** Its 112 codec lines and its memo
wrapper move neither segment's slope — the .58 session had already shown the
wrapper exactly R-independent, and run 1 shows the branch's base was
carrying the whole shift before the branch added a line.

**Run 2 pins it to one landing.** Between run 3's tree and `4d3331fad5` the
only file in the measured bundle that moves at all is `arm1/runtime.cljs` —
`69bfc6fc` → `e1fd44ca`, which is **`8e973eeace`** (rf2-2rtt6.44, "close the
registry-epoch and node-key axes off disposal") exactly, merged 2026-08-01
09:34 +1000 — thirty-five minutes before this section's own PR. The full
shift is present there: the published slope was stale at the moment it
landed.

**Run 3 names the line inside the commit, and splits its price.** The
commit does two separable things to the read path. Removing
`(interop/add-on-dispose! reaction (fn [] (invalidate-cell! cell)))` from
`wire-cell!` — the disposal hook's only arming site — returns the slope not
to 1,363 but to **1,343 / 2,253**, twenty B *below* the published rows on
both segments. So, per read at Q = E:

```
published run 3               1,363    2,272     (Reagent seg. / UIx seg.)
the wiring restructure          −20      −19     wire-cell!'s re-plumbing, hook removed (run 3)
the disposal hook              +104      +36     the one interop/add-on-dispose! line (run 2 − run 3)
every main tree since         1,447    2,289     (runs 1, 2, 4, and the .58 readings)
```

Runs 2 → 3 → 4 are an A–B–A bracket in time: the two hook-armed readings
agree to the byte around the hook-less one, so the 104 B is the line and
not the box.

#### The mechanism: a second on-dispose callback per unique key, priced by each substrate's storage

`8e973eeace` closes a real deafness: a `:sub` re-registration or a same-id
frame reincarnation disposes the reaction a cell holds, and before the
landing the boundary answered the retired computation forever after. The
repair arms the substrate's own disposal event — **once per unique
`(frame, query)` cell**, at `wire-cell!` time. On this ladder's mandatory
distinct-query witness, cells are B·R, so the whole price lands in the
marginal slope and none of it in the shell — which is why work pointed at
the shell never saw it, and a slope re-take did.

What is retained per cell, for the life of the arm: the invalidation
closure, and the substrate's storage for a **second** on-dispose callback —
second, because the sub-cache already wires its ref-release closure onto
every cached reaction (`re-frame.subs/compute-and-cache!`), and that first
callback is priced into every row above. The two substrates store the
second one very differently, and that is the Reagent-side asymmetry:

- **Reagent's `Reaction` declares no on-dispose field.** Its
  `add-on-dispose!` keeps callbacks on an `on-dispose-arr` JS array, and
  the second push grows a capacity-1 array under V8's growth policy — a
  fresh, larger elements store per reaction. Measured: **+104 B/read**
  (runs 2 − 3).
- **The spine's derived-value container** (the UIx segment's reactions)
  pre-allocates `on-dispose-fns (atom [])` at construction — already in
  the baseline price — and the second callback is a persistent-vector
  `conj` that replaces a one-element vector with a two-element one.
  Measured: **+36 B/read**.

That account is offered at the allocation level; the byte-exact split is
V8 representation detail (growth slack, pointer compression, context
sharing) and was not decomposed object-by-object. The **−20 / −19 B** the
same commit's restructure recovered (subscribe/watch wiring moved from
`acquire-cell!` into `wire-cell!`) is likewise measured, consistent across
both segments, and not decomposed further.

**This is a price worth recording, not a defect to revert.** The hook is
the correctness repair for a measured wrong-answer class, and rf2-2rtt6.44
records the costing that rejected the alternative (an epoch term read by
every key on every snapshot). What was missing was its price on this axis,
and it is now on the record.

#### What this section hands the programme, restated on the tree that ships

Donor gates, all four shells and the structural witness stand exactly as
published — nothing here touches them. The candidate's live numbers move:

| live number | as published above | on `main` today (run 4) |
|---|---:|---:|
| the design's win (UIx seg., donor − candidate) | −708 B/read (0.7624×) | **−692 B/read** (0.7679×, a 23.2% margin) |
| the design's bill (Reagent seg., candidate − donor) | +415 B/read (1.4379×) | **+499 B/read** (1.5264×) |
| the bracket a shipped Hicasso sits in | 1,363 – 2,272 B/read | **1,447 – 2,289 B/read** |
| crossover against the UIx spine *(model-inferred)* | R = 1.43 reads, fitted lines | **not restated** — the bisection publishes slopes, not intercepts |
| the grouped tier's ~2,000 B line | cleared on the Reagent substrate only | unchanged in kind: 1,447 < 2,000 < 2,289 |
| seven-read archetype vs UIx, B = 1,200 | 4,341 B/boundary saved | **4,230 B/boundary** saved (16,572 vs 20,802, measured R = 7; ≈5.1 MB across 1,200) |

**The crossover row was corrected, not re-measured (`rf2-2rtt6.34`).** Both
of its cells previously carried the hybrid arithmetic — 1.29 as published,
restated as 1.32 on run 4's slopes — which divides a *measured* R = 0 shell
difference by a *fitted* slope difference and so belongs to neither line.
[§6's crossover](#the-crossover-and-where-the-archetype-falls) now states
the fitted-line model alone, and run 4's counterpart cannot be restated in
that model, because the bisection above publishes marginal slopes and no
fitted intercepts. What the row's reading actually rests on is the direct
bracket, and no arithmetic here touches it: the candidate is behind at the
measured R = 1 and ahead at the measured R = 3, and **R = 2 was never
measured on any tree on this page.**

The verdict's shape survives — the axis is still won against UIx by nearly
five times the instrument-limited floor, and 943/948 B is still not beaten
— but the margin to the donor it loses to widened by a fifth, and the
number the programme quotes as the bill is **499, not 415**.

#### Provenance

Whole-tree anchors, in run order: `8d0e06f6d3` (run 1), `4d3331fad5`
(runs 2 and 3 — run 3's working tree carries exactly one removed line, its
modified `arm1/runtime.cljs` hashing `165e7fdea6`), `316d34ef81` (run 4).
The six instrument blobs are byte-identical to run 3's provenance table
above at every measured tree, so this is the same instrument, not merely
the same kind. The convicted landing is `8e973eeace`
(`arm1/runtime.cljs` `69bfc6fc` → `e1fd44ca`); the later per-read-path
landings in the range — the rest of rf2-2rtt6.44, rf2-2rtt6.32's codec
key-walk, rf2-fki5d's converge, rf2-2rtt6.57's prevent head — have a joint
slope effect that runs 1 = 2 = 4 measure at zero. Conditions: 2026-08-02
13:54–14:20 +1000, React 19.2.0, Reagent 2.0.1, UIx 1.4.4, `:advanced`
with `goog.DEBUG false`, headless Chromium via Playwright, Windows 11;
runs ~3.5 minutes each, all exit 0.

### Attacking the gap: one of the three suspects was real (rf2-aqgr2)

**Measured 2026-08-03.** The subsection above leaves the programme with a
bill of **+499 B/read** against Reagent and three named things buying it —
the index edge, the key cell and the read-set entry. `rf2-aqgr2` asked
whether any of the three could be made cheaper or eliminated. One could,
by 108 B/read; one turned out not to exist; the third is filed rather than
taken.

**Read these as DELTAS, not as replacement gate rows.** Every run below
was taken on a box carrying five other workers, which is not the quiet box
`validation.md` requires of a published absolute. What makes them
quotable is that both donors ride every run as the negative control and
answer 947–948 / 2,978–2,980 in all nine, the positive control is `ok`
in all nine, the arm-order guard is reportable in all nine, the
candidate reproduces byte-identically across every run that shares a
tree, and the unchanged `main` arm reproduces the published 1,447. The
*differences* are therefore instrument-clean even though the box was not
certified for a new absolute.

#### The key cell was minting an identity it did not need

Every cell carried its own watch key — `(keyword "rf-hicasso-arm1" (str
"w" (vswap! counter inc)))` — so that `add-watch`/`remove-watch` could
name it. That bought a uniqueness the runtime already had: there is at
most one cell per `(frame, query)`, `subs/subscribe` hands back that
pair's own cached reaction, and no two cells ever hold the same reaction.
The mint therefore retained a `Keyword`, its name string and its
fully-qualified string **per unique key** — which is per *read* on this
rung — plus a field on the cell to hold them. One namespaced constant does
the same job, and the global counter goes with it.

#### The nine runs

`p0_run.cjs --only ladder`, six rounds, 0/1/3/7/20, Q = E, B = 1,200 —
the same instrument, plan and guard as the runs above. **A″ and B″ are
the pair that decides it**: back to back on `1ef3fdb73e`, the `main` this
landing rebases onto, with `arm1/runtime.cljs` the only file differing
between them. `main` moved twice under this session — `rf2-2rtt6.32`'s
codec rewrite, then `rf2-2rtt6.50`'s registry-epoch term — so the pair
was re-taken each time rather than carried forward. The earlier runs are
kept because they corroborate, and because three unchanged arms agreeing
to within 1 B is itself the evidence that neither landing touches this
axis.

| run | tree | Rg donor | UIx donor | Hicasso, Rg seg. | Hicasso, UIx seg. |
|---|---|---:|---:|---:|---:|
| **A″** | **`main` `1ef3fdb73e`, unchanged** | 948 | 2,979 | **1,446** [1,444–1,447] | **2,283** [2,277–2,287] |
| **B″** | **that tree + this landing** | 947 | 2,980 | **1,338** [1,336–1,340] | **2,175** [2,170–2,179] |
| A′ | `cda407be49`, unchanged | 948 | 2,980 | 1,447 [1,446–1,448] | 2,283 [2,278–2,287] |
| B′ | that tree + this landing | 948 | 2,980 | 1,338 [1,337–1,340] | 2,175 [2,170–2,179] |
| A | `18e92b671a`, unchanged | 948 | 2,980 | 1,446 [1,444–1,448] | 2,283 [2,277–2,287] |
| B1 | that tree + this landing | 948 | 2,980 | 1,339 [1,338–1,339] | 2,175 [2,170–2,179] |
| B2 | B1's tree, re-taken | 947 | 2,978 | 1,339 [1,338–1,339] | 2,175 [2,169–2,179] |
| C | ablation: the watch key only | 947 | 2,979 | 1,339 [1,338–1,339] | 2,175 [2,169–2,179] |
| D | probe: one forced set copy | 947 | 2,979 | 1,385 [1,384–1,385] | 2,222 [2,216–2,225] |

**The unchanged arm reproduces the published `main` figure** — 1,446–1,447
B/read against the 1,447 the slope subsection above restates — which is
the strongest evidence available that this box, contended or not, is
measuring the same quantity that session did. Its UIx segment reads 2,283
against that session's 2,289, 0.26% apart and inside the between-session
offset; the delta below is taken against A″ rather than against the
published figure for exactly that reason.

**Two `main` landings in this window move this axis by nothing**, which
is worth recording because the subsection above exists precisely because
one once did: `rf2-2rtt6.32`'s codec rewrite (A → A′) and
`rf2-2rtt6.50`'s third `commit-basis` term (A′ → A″) leave the unchanged
candidate at 1,446 / 1,447 / 1,446 and 2,283 / 2,283 / 2,283. A term
added to a sum of numbers retains no object per read, and that is a
prediction checked rather than assumed.

**The shell does not move.** Across A″ → B″ the measured R = 0 rung reads
`1,244 → 1,246` on the Reagent segment and `1,237 → 1,236` on the UIx
segment, each inside its own round-to-round band, while the FIRST read
falls with the slope — `1,893 → 1,779` and `2,740 → 2,624`. That is
exactly what a per-*key* saving predicts and a per-boundary one does not,
so the shape of the saving is checked rather than assumed.

#### The index edge's duplicate read set does not exist, and run D prices the sharing that prevents it

The second suspect looked certain on the page: `front.sub-index/record-reads`
calls `(set read-sub-keys)` on a value the caller is already holding as a
set, and stores the result as the forward edge for the life of the mount.
**Run C is the honest negative.** With that call replaced by an explicit
share-if-already-a-set the slope did not move by one byte, because
`cljs.core/set` returns a meta-less set unchanged — the sharing was
already there, and the code change was a no-op. The guard was reverted.

**Run D prices what that sharing is worth**, by forcing the copy the
suspect assumed (`(into #{} …)`): **+46 B/read** on the Reagent segment and
**+47** on the UIx segment, donors unmoved. So one retained hash-set
membership costs ~46 B/read on this rung — a term price the page did not
have — and the property is now asserted with `identical?` in the index's
law suite so a tidy-up cannot cost it silently.

#### The read-set entry, and the term that is left

The entry's key array and key set are not redundant with each other: the
array is what `entry-matches?` compares and what `getSnapshot` sums over,
the set is what the index and `acquire-cell!` consume, and eliminating
either only moves its work. It is not attacked here.

What run D's 46 B does make sayable is where the remaining bill most
likely sits. At Q = E and fan-out 1 the reverse edge `:sub->bs` holds one
persistent-map entry **and a singleton set** per key, keyed by exactly the
sub-keys `!cells` is already keyed by — two global maps over one key
space. That is filed as `rf2-dabt3`: it is an elimination rather than an
encoding, and it is a redesign of where the dependency index lives rather
than a byte shave, so it wants a ruling and not a perf worker.

#### What this section hands the programme, restated again

Both columns are A″ and B″, taken back to back on the same box, and both
are stated against a donor of 948 / 2,980 so a 1 B round-to-round donor
wobble does not read as a change in the bill.

| live number | `main` before this landing (A″) | on this landing (B″) |
|---|---:|---:|
| the design's bill (Reagent seg., candidate − donor) | +498 B/read (1.5253×) | **+390 B/read** (1.4114×) |
| the design's win (UIx seg., donor − candidate) | −697 B/read (0.7661×) | **−805 B/read** (0.7299×, a 27.0% margin) |
| the bracket a shipped Hicasso sits in | 1,446 – 2,283 B/read | **1,338 – 2,175 B/read** |
| the grouped tier's ~2,000 B line | cleared on the Reagent substrate only | unchanged in kind: 1,338 < 2,000 < 2,175 |

**943/948 B/read is still not beaten**, and the verdict's shape is
unchanged: the axis is won against UIx — now by more than five times the
instrument-limited floor — and lost to Reagent's `deref`-capture. What
moved is the size of the loss, by 22%. The crossover against the UIx
spine is still **not** restated here, and now for a settled reason rather
than a pending one: `rf2-2rtt6.34` has since fixed the page's model to
[the fitted lines](#the-crossover-and-where-the-archetype-falls), this
section publishes marginal slopes and no fitted intercepts, and a fifth
number computed a sixth way was never what that bead asked for.

#### Provenance

Whole-tree anchors: A″ on `1ef3fdb73e`; B″ on that tree plus this
landing, which moves `arm1/runtime.cljs` alone. A′ and B′ were taken the
same session on `cda407be49`, and A, B1, B2, C and D on `18e92b671a` —
before `rf2-2rtt6.32`'s codec rewrite and `rf2-2rtt6.50`'s registry term
landed on `main`. The unchanged arm measures both landings' effect on
this axis at one byte, which is why the earlier seven still
corroborate. C reverts
`front/sub_index.cljs`'s share guard to `(set read-sub-keys)`; D forces
that call to `(into #{} read-sub-keys)`; both are working-tree probes and
ship nowhere. The six instrument blobs are unmoved from the provenance
table above. Conditions: 2026-08-03 11:06–12:54 +1000, React 19.2.0,
Reagent 2.0.1, UIx 1.4.4, `:advanced` with `goog.DEBUG false`, headless
Chromium via Playwright, Windows 11, **box NOT verified quiet** (five
concurrent workers); runs ~3.9 minutes each, all exit 0, `0 unverified of
154 mounts` and the structural witness fully answered on every one.

---

### The sub-index fusion, priced on the ladder (rf2-zei9w)

**2026-08-04.** `rf2-dabt3` landed the fusion — `front.sub-index` retired, the
reverse edge moved onto each key cell's own reader list — and deliberately
published **no magnitude**, because the box was not quiet when it landed and a
figure taken under five concurrent workers would have read as evidence without
being any. This section is that magnitude, taken on a verified-quiet box, with
the prediction quoted before the rows rather than summarised after them.

#### The prediction, quoted, and how it fared

From `rf2-dabt3`, written before any of this was measured:

> The elimination is REAL — two persistent maps over one B·R key space, plus a
> singleton PersistentHashSet per key at fan-out 1. PR #7418's run D prices ONE
> retained set membership at +46/+47 B/read, **so the reverse edge's total is
> plausibly several times that.**

and, on the same bead:

> It is plausibly **the single largest remaining term** in the +391 B/read bill
> (down from +499).

against which the same bead's own dispatch note entered a caution that turns
out to have been the important sentence:

> a naive cell-local reader set still retains a container per cell at fan-out 1.

| the claim | verdict | what was measured |
|---|---|---|
| the elimination is real | **MET** | −60 B/read, in **both** segments, donors unmoved |
| the reverse edge's total is several times one 46 B membership | **MET**, but only on the derived total | ~151 / ~152 B/read, ≈ 3.3× one membership |
| it is the single largest remaining term in the bill | **not settled here** | it was ≈ 39% of the +390 B/read bill — consistent, but the other terms are unpriced |
| (implied) the surviving array is the cheap residue | **MISSED** | the fused array costs **91–92 B/read**, ≈ 2× one membership, and is 60% of what the whole old reverse edge cost |

The fusion **won, and won in both segments by the same 60 bytes** — but it
banked only about **40%** of the term it removed, because a JS array per cell
is not free. The caution was right and the flattering reading was wrong.

#### The rows

Six rounds, `p0_run.cjs --only ladder`, both donors riding as same-run
controls, exit 0 with every gate answered. The donors reproduce
`rf2-aqgr2`'s B″ anchor of 948 / 2,980 to within the 1 B round-to-round
wobble that section already names, which is what makes the cross-session
comparison below quotable at all.

| segment | donor | candidate | candidate − donor | ratio |
|---|---:|---:|---:|---:|
| Reagent (`reagent-subs`) | 947 B/read | 1,278 B/read | +331 | 1.3492× |
| UIx (`uix-subs`) | 2,980 B/read | 2,115 B/read | −864 | 0.7099× (29.0% margin) |

Both candidate fits are lines in R on all six rounds (r² 0.99931 and 0.99957),
and the R=0 shell is 1,103 B and 1,097 B — measured, not fitted.

Against B″, restating the table `rf2-aqgr2` left:

| live number | on B″ (`rf2-aqgr2`) | on the fusion (this section) |
|---|---:|---:|
| the design's bill (Reagent seg.) | +390 B/read (1.4114×) | **+331 B/read** (1.3492×) |
| the design's win (UIx seg.) | −805 B/read (0.7299×, 27.0%) | **−864 B/read** (0.7099×, 29.0%) |
| the bracket a shipped Hicasso sits in | 1,338 – 2,175 B/read | **1,278 – 2,115 B/read** |
| the grouped tier's ~2,000 B line | 1,338 < 2,000 < 2,175 | unchanged in kind: 1,278 < 2,000 < 2,115 |

**943/948 B/read is still not beaten** and the verdict's shape is unchanged:
won against UIx, lost to Reagent's `deref`-capture.

#### The ablation attributes the surviving array rather than inferring it

The whole-arm delta above cannot say how much of the fused design is the
reader array itself. A second run does, by removing it: every cell's
`"readers"` slot points at **one process-global `#js []`** and the membership
push is dropped. (The literal instruction — drop the array — cannot be run:
`.-readers` becomes `undefined` and `release-cell!`'s `.indexOf`,
`arm-cell-reaper!`'s and `stats`'s `alength` all throw. The shared-array shape
answers `-1` and `0` instead, so the reaper still fires and teardown stays
clean.) The probe is a working-tree edit and **ships nowhere**.

| segment | main | ablated | attributed to the reader array |
|---|---:|---:|---:|
| Reagent (`reagent-subs`) | 1,278 B/read | 1,187 B/read | **91 B/read** |
| UIx (`uix-subs`) | 2,115 B/read | 2,023 B/read | **92 B/read** |

The per-round bands are **disjoint** — main [1,275–1,280] against ablated
[1,186–1,188], and main [2,110–2,117] against ablated [2,018–2,025] — so the
attribution separates in every one of the six rounds rather than on the mean.
Donors moved by 0.05 and 0.19 B/read, which is the same-run negative control.

**The R=0 shell does not move**: 1,103 → 1,098 B and 1,097 → 1,097 B, each
inside the other's band. That is the shape a per-key array term predicts and a
per-boundary one does not — at R=0 there are no cells, so there are no arrays
to remove.

> **The shell row this section owed, added 2026-08-06 (`rf2-2rtt6.82`).** The
> sentence above is true of the *ablation*, and was read for a while as though
> it were true of the *fusion*. It is not. The ablation removes the reader
> array, which is per key and therefore invisible at R=0 — but the fusion made a
> second elimination, the `:b->subs` entry `mount` installed per boundary
> **whether or not it read**, and that one is nothing but a shell term.
> Bisected on the landing itself, the fusion is worth **−77 B on both segments**
> at R=0, with disjoint per-round bands. The ablation could not have seen it: by
> the time it ran, that entry was already gone from the main arm and the ablated
> arm alike. The rows above stand; what they do not price is
> [priced below](#the-shells-lost-154-and-147-b-bisected-and-attributed-rf2-2rtt682).

The ablation run **exits 1, by construction and only by construction**: with
no slot ever pushed, `stats` derives `:boundaries` and `:edges` from an
always-empty array and reads 0. That is 96 structural failures — exactly
2 fields × 4 rungs × 2 segments × 6 rounds — and **nothing else fails**: no
donor arm, no residue check, no `cells` or `entries` field, arm-order verdict
reportable, positive control 0.9997, `0 unverified of 154 mounts`. The two
fields that break are precisely the two the ablation removes, which is what
makes this an attribution rather than a subtraction.

#### The commit-time term is below this instrument's resolution

`read_profile_app`'s phase B prices the same fused edge on the clock, through
`c-local − c-noreaders`. **These rows are diagnostic and are never a gate
row.**

| arm | p50 ms/pass | µs/read |
|---|---:|---:|
| `commit` | 0.4500 | — |
| `c-local` | 0.4250 | 3.01 |
| `c-noreaders` | 0.4000 | 2.84 |
| `c-nowatch` | 0.3875 | 2.75 |
| `c-nomap` | 0.3875 | 2.75 |
| `b-build` | 0.3375 | 2.39 |
| `c-nosub` | 0.1750 | 1.24 |

`c-local − c-noreaders` = **+0.0250 ms/pass** (0.18 µs/read), copy fidelity
`c-local/commit` 0.9444, positive control predicted 0.925× and measured
0.925×, exit 0.

**The term is not resolved, and this run is what establishes that rather than
weakening it.** Every phase-B p50 lands exactly on a 0.0125 ms/pass grid, so
the delta is two grid steps. The same contrast taken on the same instrument
hours earlier read **−0.0250 ms/pass** — the same magnitude with the opposite
sign. A quantity whose sign inverts between sessions at two grid steps is
below the clock's floor, so the honest reading is **NOT RESOLVED** — not
"free". The heap ladder above prices this edge; this instrument cannot.

#### What this section hands the programme

The fusion is banked at −60 B/read in both segments and the bill is restated
at +331 / −864. What it also hands over is a **named, measured, surviving
term**: 91–92 B/read of JS array, per unique key, retained purely to hold
reader pointers at a fan-out where the overwhelmingly common case is one
reader. That is about twice what one PersistentHashSet membership costs, and
it is the largest single term this page has ever attributed to a container
rather than to a value. Whether it can be collapsed — a scalar slot that
promotes to an array on the second reader is the obvious shape — is not
attacked here and wants its own ruling, not a byte shave.

#### Provenance

Both ladder runs on `23d60a1fe9`, which is `origin/main`; the ablation is that
tree plus a three-hunk working-tree edit to `arm1/runtime.cljs`, reverted
byte-identically afterwards (sha256
`db5f27408900f549f8ef22f68fc8578d3d2564c9a18c6b2d069acc60b9d9f5d1` before and
after, `git diff HEAD` and `git status --porcelain` both empty). B″'s figures
are quoted from the section above and were taken on `1ef3fdb73e` plus that
landing. Conditions: 2026-08-04 02:02:43–02:11:15 +1000, React 19.2.0, Reagent
2.0.1, UIx 1.4.4, `:advanced` with `goog.DEBUG false`, headless Chromium via
Playwright, Windows 11. **Box verified quiet at both ends** — node 15, chrome
55, java 0, 500 processes, ~31.5 GB free at open and close, with real CPU
occupancy 1.05% at open and 1.64% at close measured as summed per-process
CPU-time deltas over 24 logical cores (the coarse `LoadPercentage` poll reads
an order of magnitude higher and is noise); all 15 node processes idle MCP
servers, no JVM, zero open PRs, no other worker. The box did not change across
the window, so the runs are same-load. Ladder runs ~2.9 minutes each,
ladder-fit self-test 3 of 3 on both.

This section was blocked on `rf2-xzg3b`: `--only ladder`'s `:boundaries`
expectation still encoded the pre-fusion, R-independent reading and refused
the fused arm at R=0. The first attempt at this measurement declined to widen
that gate to admit its own run, and published nothing; the fix landed as
PR #7451 and the expectation is now `R === 0 ? 0 : B`, which is strictly
stronger — the old form could not have detected a boundary retained at R=0.

### The memo wrapper, re-taken on the tree that ships (rf2-2rtt6.58, re-take)

**Measured 2026-08-04** on `81321da3fe`, `origin/main`. This replaces the
2026-08-02 rows in [the subsection above](#the-memo-wrapper-priced-on-this-rung-rf2-2rtt658)
as the wrapper's current price. Those rows were taken on
`worker/cascade-2rtt6-52` at tree `4a33c61e1c`, and their own provenance
paragraph promised that the recorded blobs *"will prove the measured tree and
the merged tree agree once it is rebased"*. **They do not agree.** The runtime
blob measured was `a6d6c55a58` and the codec blob `12284ef8f4`; after PR #7390
and the four landings that followed it the shipped blobs are `9f3d42be7b` and
`cf9ef32dc8`. The audit on `rf2-2rtt6.58` (PR #7392) called that out and asked
for the pair to be re-taken on the landed implementation before the number is
used as the current price. This is that re-take.

**The delta survives almost exactly. The base does not, and the conclusion
drawn from the base is now false.**

#### The prior, quoted, and how it fared

From the bead's own verdict line, written 2026-08-02:

> the wrapper costs ~+100 B on the R=0 shell, NOT the ~+200 B projected —
> +9%, not +17%.
>
> | R=0 shell | no wrapper | wrapper | delta |
> |---|---:|---:|---:|
> | Reagent segment | 1,141 B | 1,247 B | +106 B, +9.3% |
> | UIx segment | 1,138 B | 1,236 B | +100 B, +8.8% |

| the claim | verdict | what was measured |
|---|---|---|
| the wrapper costs ≈ +100 B, not ≈ +200 B | **MET**, and closely | +105.5 B and +105.0 B |
| the cost is constant in R and lands wholly in the shell | **MET** | slope identical to the byte across all three runs, both segments |
| the percentage is ≈ +9% | **MISSED**, low | **+10.6%** on both segments — the delta held, the base fell |
| the shell was *already over* the 1 KB line without the wrapper | **NO LONGER TRUE** | without the wrapper the shell reads **994 / 992 B** |
| ⇒ "the wrapper widens a pre-existing failure rather than causing one" | **WITHDRAWN** | on the tree that ships, the wrapper is what carries the shell across the line |

The last row is the reason this re-take was worth its box time. HD-028's
disposition paragraph, this page's own 2026-08-02 subsection and
[the page-chrome row](the-page-chrome-row-and-what-the-bail-out-costs.md) all
rest their argument on the shell having failed the paper line *before any
wrapper existed*. That was true at 1,141 B. It is not true at 994 B.

#### The rows

Three runs, `A1 → B → A2`, one session, box probed before each. B = 1,200
boundaries, six rounds, rungs 0/1/3/7/20, Q = E. `shell` is the driver's own
**directly measured R=0 rung**, never the fitted intercept; bands are min–max
across the six rounds.

| R=0 shell | A1 *(wrapper)* | B *(no wrapper)* | A2 *(wrapper)* | delta |
|---|---:|---:|---:|---:|
| Hicasso, Reagent segment | **1,101** [1,088–1,112] | **994** [985–1,003] | **1,098** [1,090–1,103] | **+105.5 B, +10.6%** |
| Hicasso, UIx segment | **1,097** [1,095–1,098] | **992** [985–998] | **1,097** [1,092–1,105] | **+105.0 B, +10.6%** |

**The estimator is named rather than left to be inferred.** The delta is the
paired one — `mean(A1, A2) − B` — and the percentage is that delta over **B**,
the no-wrapper arm, because the question is what the wrapper *adds* to a shell
without it. The unpaired deltas are `A1 − B` = +107 / +105 B and `A2 − B` =
+104 / +105 B, so the pairing changes the Reagent figure by 1.5 B and the UIx
figure by nothing.

**The A/B bands are disjoint on both segments** — 85 B and 87 B of clear air on
the Reagent segment, 97 B and 94 B on the UIx segment — so the arms are
distinguishable by this studio's house rule before the delta is quoted.

**A1 and A2 bracket B in time and do not separate**: 1,101 against 1,098 (bands
overlap over [1,090–1,103]) and 1,097 against 1,097. The box did not move under
the pair.

#### Against the 1 KB line, which is what changed

| | Reagent segment | UIx segment | against the 1 KB paper-fail line |
|---|---:|---:|---|
| no wrapper (B) | 994 B | 992 B | **0.99× — at the line** |
| wrapper (A, paired) | 1,099.5 B | 1,097 B | **1.10× — over it** |

**Read the no-wrapper row as "at the line", not as "passing".** 994 B and 992 B
are 6 B and 8 B under 1,000, the Reagent arm's own per-round band
[985–1,003] straddles it, and [validation.md](../validation.md) puts this line's
component-shape sensitivity at **~75 B**. The honest statement is that the
no-wrapper shell is **not distinguishable from the line in either direction**.

The wrapper arm is a different matter. Its per-round bands are
[1,088–1,112] and [1,092–1,105]: **every one of the twelve readings is above
1,075 B**, so it clears the line by more than the shape sensitivity in every
round rather than on the mean. The flip is also robust to which "1 KB" is
meant — at 1,024 B the no-wrapper arm reads 0.97× and the wrapper arm 1.07×.

So the shape of the finding is: **the design's own shell now arrives at the
paper line, and the wrapper is what puts it past.** That is a materially
different question for the operator than the one the 2026-08-02 rows posed.

#### The wrapper is exactly R-independent, again

The 2026-08-02 session's strongest result reproduces on a tree 145 / 139 B
lighter in the shell and 169 / 174 B lighter per read:

| marginal slope | A1 *(wrapper)* | B *(no wrapper)* | A2 *(wrapper)* |
|---|---:|---:|---:|
| Hicasso, Reagent segment | 1,278 [1,275–1,280] | 1,278 [1,276–1,279] | 1,279 [1,276–1,280] |
| Hicasso, UIx segment | 2,115 [2,110–2,118] | 2,115 [2,110–2,118] | 2,115 [2,110–2,118] |

A per-boundary constant is what HD-028 priced the wrapper as, and three runs on
two segments put the slope inside a 1 B spread. Nothing leaked into the
per-read term.

#### The controls

**Negative controls — the donors, taken in the same runs.** Neither donor goes
through `mint-view!`, so neither can see the toggle:

| donor | A1 | B | A2 |
|---|---:|---:|---:|
| Reagent shell (R=0) | 510 [499–518] | 510 [503–516] | 506 [499–516] |
| UIx shell (R=0) | 223 [220–230] | 222 [217–226] | 224 [221–231] |
| Reagent per read | 947 [947–948] | 947 [947–948] | 947 [947–948] |
| UIx per read | 2,979 [2,978–2,980] | 2,979 [2,978–2,981] | 2,979 [2,978–2,980] |

**The donors also reproduce their published anchor**, which is what licenses the
cross-session sentences above: 947 and 2,979 B/read against the 947–948 and
2,978–2,981 the two most recent sections state. And the **A arm reproduces this
page's own most recent published `main` rows** — slope 1,278 / 2,115 exactly, and
shell 1,101 / 1,097 against the 1,103 / 1,097 the fusion section published two
hours earlier. That reproduction, rather than a table of blob hashes, is what
says the measured tree and the shipped tree are the same tree; it is the check
the 2026-08-02 provenance promised and could not deliver.

**Positive control.** The same dense array of 587,500 unboxed doubles —
**4,700,000 B, fixed before any run.** Measured 4,698,736 B, 4,697,603 B and
4,698,736 B: ratios 0.9997, 0.9995 and 0.9997, **`ok` under
`lane/control-verdict`** at the driver's ±25% slack in all three.

> **The control protocol, stated unambiguously (the audit's third item).** The
> binding gate is `lane/control-verdict` at ±25%, which the driver *exits on*.
> It is the only band this session registered. The deviations from the
> prediction — −0.027%, −0.051%, −0.027% — are reported as a **non-gating
> observation** with no acceptance band attached. The 2026-08-02 session
> pre-registered a separate ±0.05% expectation, missed it on all three runs and
> accepted the miss afterwards; registering no second band is the way not to
> repeat that, and is a deliberate choice made before the first run rather than
> a band dropped after seeing one.

**0 unverified of 154 mounts**, structural read-back **every field answered**,
arm-order guard **reportable**, **exit 0** — all three runs, no gate waived and
none widened.

#### The 145 / 139 B the shell lost since 2026-08-02 is not attributed here

The wrapper arm read 1,247 / 1,236 B on 2026-08-02 and reads 1,099.5 / 1,097 B
now; `rf2-aqgr2`'s A″/B″ pair puts the shell at 1,244–1,246 / 1,236–1,237 as
late as that section, so the drop is later than it and no section on this page
attributes it. The obvious suspect is `rf2-dabt3`'s sub-index fusion, but the
fusion section's own ablation reports the shell **unmoved** by the part of the
fusion it ablates, which is about the reader array and not about the whole
landing. **It is left unattributed rather than guessed at**, and filed as
`rf2-2rtt6.82`. Nothing in this subsection depends on the answer: A and B were
taken hours apart from nothing, in one session, on one tree.

**Since attributed**, by the bisect in
[the section below](#the-shells-lost-154-and-147-b-bisected-and-attributed-rf2-2rtt682):
two commits on the sub-index line, `rf2-ixb92` (−75 / −68 B) and `rf2-dabt3`'s
fusion (−77 / −77 B), both removing per-boundary index structure and neither
ever priced on this axis. The suspicion above was half right — the fusion is one
of the two — and the reason the fusion section could not convict itself is that
its ablation removed the one part of the fusion that cannot appear at R = 0.

#### What this settles, and what it hands the operator

**Settles.** On the tree that ships, the wrapper costs **+105 B — +10.6%** — on
the R=0 shell, moving it from **994 / 992 B to 1,099.5 / 1,097 B**. The delta is
within 1 B of the 2026-08-02 re-take on both segments, so that session's
measurement of the *wrapper* stands and only its base was overtaken. The delta
is above validation.md's ~75 B shape sensitivity, though not by a wide margin.

**Changes.** The argument every carrier of the old figure rests on —
*the wrapper widens a failure that pre-dates it* — **no longer holds.** Without
the wrapper the shell is at the line; with it the shell is 1.10× over. HD-028's
*Reopens* clause is worded as **failing** the bar, and on this tree the wrapper
is the thing that reaches it.

**Does not settle, deliberately.** Whether that is "pushing retained heap
meaningfully farther past the bar" in HD-028's sense. The clause remains
unquantified in HD-028, in validation.md and in the heap-regime ruling, and it
is not this page's to quantify. **No candidate-bar row is written into
validation.md**, matching §6's posture under `rf2-b0tz5`.

**The pre-registered fallback, named and not executed.** HD-028 already records
what happens if the Fiber fails its gate: *retain HD-006 and ship the same
comparator as an explicit boundary-level opt-in.* That is the operator's call to
make, not this section's, and it is written here so the disposition does not
have to be invented when it is taken.

#### Provenance

Whole-tree anchor **`81321da3fe`**, which is `origin/main`; the working tree was
clean at the start of A1, carried exactly the one-line B edit for B, and was
restored **byte-identically** for A2 (`git status --porcelain` empty, runtime
blob back to `9f3d42be7b`).

The one line under test, `mint-view!` in `arm1/runtime.cljs`:

| arm | line | `arm1/runtime.cljs` blob |
|---|---|---|
| **A1, A2** | `(codec/memoize-boundary! (codec/mark-boundary! component))` | `9f3d42be7b3aa529e8fc6ef8531262ceb4d65f1a` |
| **B** | `(codec/mark-boundary! component)` | `a832fafb1ef164e7a8aaddb329a43875dc38b5c1` |

`front/codec.cljs` is `cf9ef32dc8f751e344016cfa01b1db722ba2440b` in all three —
the wrapper's definition is present throughout and merely goes uncalled in B.
`mint-frame-prop-view!` acquired a second `memoize-boundary!` call site since
2026-08-02 and is **deliberately untouched**: `defview` expands to `mint-view!`
only, so the frame-prop twin is unreachable from this rig and editing it would
have made the arms differ by two lines instead of one.

The instrument, all under `implementation/core/test/re_frame/bench/`:

| file | blob | vs §6 run 3 |
|---|---|---|
| `p0_run.cjs` | `586474b5cfad0f09df5e3e968ca0282e2c1cd95c` | moved — PRs #7448/#7450/#7451 |
| `p0_heap.cljs` | `0a568a63cd24b66865e433c49a62eadff8993e8a` | moved |
| `p0_hicasso.cljs` | `f2440e307423665048dfe227b14baaf4ffc8ac89` | identical |
| `p0_reagent.cljs` | `b1f5ec9223536557403f6ae9415ab42ac26843b0` | identical |
| `p0_uix.cljs` | `deec8976010c17e4d2c6e8dc3499678997acd2c0` | identical |
| `p0_fixture.cljc` | `867ad5838ab64ac6aa7afbf8317d8fb305f53619` | identical |

The two that moved moved **towards refusing more**, not less: nine conditions
that previously printed a fault and exited 0 now exit non-zero. The four arm
files are byte-identical to §6's published run 3.

Reproduce — the wrapper arm as `main` stands, the no-wrapper arm by deleting the
one call above:

```
node implementation/core/test/re_frame/bench/p0_run.cjs --only ladder
```

**Conditions.** 2026-08-04 02:45–02:57 +1000, three runs of ~3.5 minutes,
React 19.2.0, Reagent 2.0.1, UIx 1.4.4, `:advanced` with `goog.DEBUG false`,
headless Chromium via Playwright, Windows 11, 24 logical cores, 32 GB.
**Box verified quiet before each run and at close** — real CPU occupancy
**1.38% / 1.60% / 1.89% / 2.07%**, measured as summed per-process CPU-time
deltas over a 5-second window divided by the core count. `LoadPercentage` is
deliberately not used: it reads 15–20% on this box when the true occupancy is
under 2%. Throughout: 15 node processes (idle MCP servers), 56 chrome, **zero
java**, ~500 processes, ~30.7 GB free, no other worker and no open PR. The box
did not change across the window, so the three runs are same-load.

---

### The shell's lost 154 and 147 B, bisected and attributed (rf2-2rtt6.82)

**Measured 2026-08-06.** The subsection above left a hole and named it: the R=0
shell fell by ~145 / ~139 B between `rf2-aqgr2`'s session and the fusion's, and
no section on this page said why. On a quantity the paper line is stated
against, and whose named shape sensitivity is ~75 B, that is an unexplained
move of about twice the resolution of the line it supports. This section closes
it by bisection rather than by argument.

**The answer, first: two commits, both on the sub-index line, and neither was
ever priced on this axis.** `27f846cf5a` (`rf2-ixb92`) is worth −75 / −68 B and
`383ba2d645` (`rf2-dabt3`, the fusion) is worth −77 / −77 B. Everything else in
the window — six further arm landings — is worth −2 / −1 B, which is the
round-to-round floor. **The drop is a saving, not a boundary that stopped doing
something**, and the last part of this section is the evidence for that clause
rather than an assurance about it.

**Read these as DELTAS, not as replacement gate rows.** Every run below was
taken on a box carrying other workers and 78 chrome processes, which is not the
quiet box `validation.md` requires of a published absolute. What makes the
differences quotable is that both donors ride every run as the negative control
and do not move while the candidate moves 154 B, the positive control is `ok` in
all five, the arm-order guard is reportable in all five, the structural witness
is fully answered in all five, and — the load-bearing one — **the two ends of
the bisect reproduce the two published sessions they are being used to explain**.

#### The bisect

Five runs, `p0_run.cjs --only ladder`, `P0_LADDER_RUNGS=0,1`,
`P0_LADDER_ROUNDS=4`, B = 1,200, Q = E. `shell` is the driver's own **directly
measured R=0 rung**, never the fitted intercept; bands are min–max across the
four rounds. Only the R=0 rung is at issue, so the ladder is run short — the R=1
rung is carried because a fit needs two points, and the slope it yields is
correctly reported `UNIDENTIFIED` and is not used here.

| point | tree | position in the window | Hicasso, Rg seg. | Hicasso, UIx seg. |
|---|---|---|---:|---:|
| **T1** | `abfc2e5ce4` | `27f846cf5a^` — after `rf2-aqgr2`'s landing | **1,237** [1,224–1,255] | **1,232** [1,218–1,249] |
| **T2** | `27f846cf5a` | that tree + `rf2-ixb92` | **1,162** [1,146–1,168] | **1,164** [1,154–1,175] |
| **T3** | `9d89cb21c6` | `383ba2d645^` — six arm landings later | **1,160** [1,146–1,169] | **1,163** [1,155–1,182] |
| **T4** | `23d60a1fe9` | the fusion, on the tree `rf2-zei9w` measured | **1,082** [1,077–1,088] | **1,085** [1,076–1,098] |
| **T4′** | `23d60a1fe9` | T4 re-taken last, to close the bracket | **1,084** [1,079–1,095] | **1,086** [1,077–1,102] |

**Both ends reproduce their published session.** T1 reads 1,237 / 1,232 against
the 1,244–1,246 / 1,236–1,237 `rf2-aqgr2` published for A″/B″ — 7–9 B and 4–5 B
apart. T4 and T4′ read 1,083 / 1,085.5 paired against the 1,103 / 1,097
`rf2-zei9w` published **on that same tree** — 20 B and 11.5 B apart, this box
reading low at both ends by about the same offset. So the bisect is measuring
the quantity those two sessions measured, and the offset is a box constant that
differencing removes.

#### What each step is worth

| step | what lands in it | Reagent seg. | UIx seg. | bands |
|---|---|---:|---:|---|
| T1 → T2 | `27f846cf5a` — `rf2-ixb92`, **the only arm commit in the span** | **−75 B** | **−68 B** | **disjoint** — 56 B and 43 B of clear air |
| T2 → T3 | six arm landings: `rf2-digtt` ×4, `rf2-e3i6y`, `rf2-2rtt6.54` | −2 B | −1 B | fully overlapping — indistinguishable |
| T3 → T4 | `383ba2d645` — `rf2-dabt3`, the fusion (and `rf2-2rtt6.74`, which touches `front/intent.cljs` only) | **−77 B** | **−77 B** | **disjoint** — 58 B and 57 B of clear air |
| **total** | T1 → T4 paired | **−154 B** | **−146.5 B** | — |

The two convicted steps separate with clear air between the per-round bands;
the six-landing step does not separate at either end — its Reagent bands are
[1,146–1,168] against [1,146–1,169], identical to the byte at both ends. That
is the shape of a real attribution rather than a residual: the movement is
concentrated in two named landings, and the span that contains the most commits
contributes nothing.

**T1 → T2 is a single-commit span**, so `rf2-ixb92`'s −75 / −68 B is attributed
rather than bracketed. T3 → T4 contains two arm landings, but only the fusion
touches the runtime's per-boundary retention; `rf2-2rtt6.74` is an
invocation-scoped render-position enforcement in `front/intent.cljs`.

#### Why it moved, in objects rather than in bytes

Before the fusion the sub-index held **two structures per MOUNTED boundary**,
independently of whether that boundary ever read anything:

- `:live`, a `PersistentHashSet` membership — deleted by `rf2-ixb92`, which
  observed that it was exactly `(set (keys (:b->subs idx)))` at every reachable
  index value;
- `:b->subs`, a `PersistentHashMap` entry that `mount` installed as `b → #{}`
  **whether or not the boundary read** — retired with the whole index by the
  fusion.

At R = 0 a boundary reads nothing, so those two memberships were the *entire*
per-boundary index cost, and the shell rung is precisely the rung that prices
them. The two eliminations are therefore not merely correlated with the drop;
they are the only per-boundary terms the window removes.

**The ladder counts this rather than inferring it.** Its structural witness
asserts `boundaries` at every rung and exits on it. At T3 the expectation is
R-independent `boundaries = B` and the run passes with **1,200** at R = 0; at T4
it is `R === 0 ? 0 : B` and the run passes with **0**. Twelve hundred
per-boundary registrations became none, counted on both sides, in the same
instrument. That is also why `rf2-xzg3b` had to land at `23d60a1fe9` before
`rf2-zei9w` could publish: the old expectation encoded the pre-fusion reading
and refused the fused arm at R = 0.

#### Why the record had a hole where this belongs

Neither elimination was hidden; both were simply never priced on this axis, for
two different and individually reasonable causes.

`rf2-ixb92` **said so itself**, in its own commit message:

> This is a per-BOUNDARY term on the shell axis, not a per-read one, so no byte
> figure is claimed for it and none was measured.

That was the honest thing to write, and nothing ever came back to measure it.
This section is what comes back.

`rf2-zei9w` did look at the fusion on the shell axis, and reported the shell
**unmoved** — correctly. Its ablation removes the fused **reader array**, which
is per *key*: at R = 0 there are no cells, so there are no arrays, so the shell
cannot move. But the fusion made **two** eliminations, and the ablation could
not see the other one, because by the time the ablation ran the per-boundary
registration was already gone from the main arm *and* from the ablated arm
alike. **The section ablated the only part of the fusion that cannot appear on
the rung it was reading.** Its conclusion is sound about the array and was never
about the registration; the gap is one of scope, not of arithmetic.

#### Saving, or work that stopped happening?

**A saving.** The question is worth asking — 140 B leaving a boundary shell is
equally consistent with a boundary that quietly stopped doing something — so it
is answered from three directions rather than asserted.

**The information survives both eliminations.** `:live` was a second encoding of
a set the index already held, and `rf2-ixb92` derives `live?` from
`(contains? (:b->subs idx) b)` and lands a law that walks the reachable index
values — mount, read, second mount, narrowing re-run, a run that read nothing,
StrictMode's remount, unmount, an abandoned render's reads — asserting at each
step that the forward edges' key set *is* the mounted set. The fusion then moves
the reverse edge onto the key cell that already existed, and ports the six laws
to the fused doors.

**The one thing genuinely no longer retained is consumed by nothing.** That is
the record that an *edgeless* boundary is mounted. A boundary that reads nothing
holds no cell, so there is nothing that can dirty it and nothing to notify; when
it later reads, it acquires a cell and is recorded at that moment. The fusion
pinned exactly this as a positive claim rather than leaving it as an absence —
`rf2-dabt3` landed a witness that an edgeless boundary retains no membership,
and the ladder's R = 0 zero is now an assertion whose *violation* is read as a
retention bug.

**Nothing leaked in the other direction.** The complementary failure — heap
saved on the shell by dropping a reference something still needs — would show as
residue after teardown. Every structural field reads zero after teardown on
every arm of every round of all five runs, and the residue column stays at its
usual few-byte floor.

**The one narrowing worth naming** is diagnostic, not behavioural: `stats`'
`:boundaries` no longer means "boundaries mounted", it means "boundaries
retained by at least one cell". The instrument absorbed that at `23d60a1fe9`,
and the replacement expectation is **strictly stronger** than the one it
replaced — the old R-independent form could not have detected a boundary
retained at R = 0, and the new one exits on it.

#### What this hands the programme

The shell's current position is now accounted for rather than drifted into. The
~1,100 B the wrapper arm reads is 1,237 B minus two deliberate, separately
attributed eliminations of per-boundary index structure — so the number
[HD-028's disposition turns on](#the-memo-wrapper-re-taken-on-the-tree-that-ships-rf2-2rtt658-re-take)
has a provenance, and the wrapper's +105 B is being weighed against a base that
moved for a reason the page can now name.

It also explains the shape the re-take found and could not account for: the drop
is present in the wrapper arm and the no-wrapper arm alike (1,247 → 1,099.5 and
1,141 → 994, −147.5 and −147), which is exactly what a per-boundary *index* term
predicts and what a wrapper-coupled term does not. The wrapper measurement was
never in question; only its base was, and the base moved here.

**No gate row is restated and no absolute is republished.** These are same-box
deltas on a box that was not certified quiet. The published absolutes remain
`rf2-zei9w`'s and the wrapper re-take's.

#### Provenance

Whole-tree anchors, all five **ancestors of `origin/main`**, verified with
`git merge-base --is-ancestor` before use: `abfc2e5ce4`, `27f846cf5a`,
`9d89cb21c6`, `23d60a1fe9` (twice). No working-tree edit was made in any run —
each point is a clean `git checkout --detach` of a landed commit, so there is no
probe to revert and no blob to reconcile. `implementation/package-lock.json`,
`implementation/shadow-cljs.edn` and `implementation/deps.edn` are **unmoved
across the whole window**, so one `npm ci` serves every point and the toolchain
is not a variable.

One pin quoted by an earlier section on this page does **not** resolve:
`4a33c61e1c`, the 2026-08-02 wrapper tree, is not an ancestor of `origin/main` —
it was authored on `worker/cascade-2rtt6-52` and stranded by the rebase merge.
That is already recorded in the re-take's opening paragraph as the reason the
re-take exists, and it is noted rather than re-pinned: re-pinning would restore
the patch and not the tree. The patch is on `main`, landed as `cb179b6b3c`, and
that is the whole of what it recovers — the rebase rewrote both blobs under test
there, so the resolvable commit and the measured tree are two different things
and the page says so wherever it quotes either.

**Run order was deliberately not monotone in tree age** — T4, T3, T1, T2, T4′ —
so that box drift could not masquerade as the bisect. The oldest and
highest-reading tree ran third while the newest and lowest-reading tree ran
first *and* last, which means any monotone drift over the session would have
**shrunk** the measured drop rather than manufactured it. T4′ closes the
bracket at 2 B and 1 B from T4: the box did not move.

**Controls, all five runs.** The donors are the same-run negative controls and
neither goes anywhere near the sub-index:

| control | T4 | T3 | T1 | T2 | T4′ |
|---|---:|---:|---:|---:|---:|
| Reagent donor shell | 513 | 520 | 519 | 518 | 516 |
| UIx donor shell | 224 | 222 | 224 | 224 | 224 |
| Reagent floor (calibrator) | 251 | 248 | 249 | 249 | 249 |
| UIx floor (calibrator) | 250 | 251 | 250 | 251 | 251 |

Seven bytes of total spread on the donors across a session in which the
candidate moved 154 B. **Positive control**: the same dense array of unboxed
doubles, predicted 4,700,000 B, measured 4,700,412 B [4,699,074–4,700,974],
ratio **1.0001**, `ok` under `lane/control-verdict` at ±25% — identical in all
five runs. **`0 unverified of 50 mounts`**, structural read-back **every field
answered**, arm-order guard **reportable**, ladder-fit self-test **3 of 3**, and
**exit 0** on all five, no gate waived and none widened.

Reproduce, at any of the four trees:

```
P0_LADDER_RUNGS=0,1 P0_LADDER_ROUNDS=4 \
  node implementation/core/test/re_frame/bench/p0_run.cjs --only ladder
```

**Conditions.** 2026-08-06 23:06–23:24 +1000, five runs of ~1.5 minutes plus a
~30 s `:advanced` compile each, React 19.2.0, Reagent 2.0.1, UIx 1.4.4,
`goog.DEBUG false`, headless Chromium via Playwright, Windows 11, 24 logical
cores, 32 GB. **Box NOT verified quiet** — 8–10 node, 78 chrome, 1–2 java,
572–581 processes, 25.3 → 24.7 GB free, with other workers compiling
throughout. The box is stable across the window rather than quiet, which is what
licenses the differences and not any absolute.

---

### The frame-as-a-prop variant, priced on this rung (rf2-2rtt6.72)

**Measured 2026-08-04** on `2303ef6781`, one commit off `origin/main`
`1ce64c06a2`. It is the same instrument, the same B = 1,200 page and the same
`A1 → B → A2` design as [the wrapper re-take above](#the-memo-wrapper-re-taken-on-the-tree-that-ships-rf2-2rtt658-re-take),
deliberately, so the two rows compose.

`rf2-2rtt6.39` landed a second boundary shell beside the incumbent: the frame
arrives as an ordinary element prop (`rfFrame`) rather than through
`useContext`, so the shell spends **one** React hook where the incumbent
spends two. That bead measured the hook count at React's own dispatcher and
proved multi-frame isolation through both variants; it did not price them.
This is the price.

**The variant is cheaper, and by less than the wrapper costs.**

#### The prediction, quoted, and how it fared

Registered before the first build, in the run's own pre-registration:

> The variant deletes React's `useContext` hook cell and the fiber's context
> dependency record, and adds one property slot (`rfFrame`) on every boundary
> element's props object. A keyword is interned, so the added retention is a
> slot and not an object. `useContext` is a much lighter hook than
> `useSyncExternalStore` — no store cell, no subscribe closure, no snapshot
> pair — so the ~516 B the P0 record prices an isolated
> `useSyncExternalStore` rung at is an **upper bound the saving will not
> approach**. Registered prediction: **`|B − A| < 100 B`** on both segments,
> direction not predicted. Slope: predicted **unchanged**.

| the claim | verdict | what was measured |
|---|---|---|
| the saving is real, i.e. the arms are distinguishable | **MET** | bands disjoint on both segments |
| the delta is under 100 B in magnitude | **MET** | **45.0 B** and **46.5 B** |
| the ~516 B hook rung is an upper bound this will not approach | **MET** | 45 B is **8.7%** of it |
| the cost is constant in R — nothing leaks into the per-read term | **MET** | slope identical to the byte, all three runs, both segments |

The direction was deliberately not predicted, and it is **down**: removing the
hook wins more than carrying the prop costs. The two effects are not separable
by this instrument — 45 B is the *net* of a hook cell removed and a props slot
added, and nothing here decomposes it.

#### The rows

Three runs, `A1 → B → A2`, one session. B = 1,200 boundaries, six rounds,
rungs 0/1/3/7/20, Q = E. `shell` is the driver's own **directly measured R=0
rung**, never the fitted intercept; bands are min–max across the six rounds.

| R=0 shell | A1 *(context, 2 hooks)* | B *(frame-prop, 1 hook)* | A2 *(context)* | delta |
|---|---:|---:|---:|---:|
| Hicasso, Reagent segment | **1,098** [1,087–1,111] | **1,054** [1,049–1,066] | **1,100** [1,091–1,105] | **−45.0 B, −4.1%** |
| Hicasso, UIx segment | **1,096** [1,092–1,101] | **1,051** [1,047–1,056] | **1,099** [1,095–1,102] | **−46.5 B, −4.2%** |

**The estimator is the paired one** — `B − mean(A1, A2)`, with the percentage
over the **A** arm, because the question is what the variant *takes off* the
shell that has the hook. The unpaired deltas are `B − A1` = −44 / −45 B and
`B − A2` = −46 / −48 B, so the pairing moves the Reagent figure by 1 B and the
UIx figure by 1.5 B.

**The A/B bands are disjoint on both segments** — 21 B and 25 B of clear air on
the Reagent segment, 36 B and 39 B on the UIx segment — so the arms are
distinguishable by this studio's house rule before the delta is quoted.

**A1 and A2 bracket B in time and do not separate**: 1,098 against 1,100 and
1,096 against 1,099, both pairs' bands overlapping heavily. The box did not
move under the pair.

#### The saving is exactly R-independent, which is the same shape the wrapper showed

| marginal slope | A1 *(context)* | B *(frame-prop)* | A2 *(context)* |
|---|---:|---:|---:|
| Hicasso, Reagent segment | 1,278 [1,276–1,280] | 1,278 [1,275–1,280] | 1,279 [1,278–1,280] |
| Hicasso, UIx segment | 2,115 [2,110–2,118] | 2,115 [2,110–2,118] | 2,115 [2,110–2,118] |

A hook is per boundary, not per read, so a saving that appeared in the slope
would have meant the arms differed in something other than the hook. Three runs
on two segments put the slope inside a 1 B spread and the UIx segment does not
move at all.

#### Against the 1 KB line, which is the question this row was taken for

The wrapper re-take above withdrew the claim that the shell failed the paper
line before the wrapper existed. That makes the standing question *what, if
anything, brings it back*, and this variant is the one lever already built.

| R=0 shell | Reagent segment | UIx segment | against the 1 KB paper-fail line |
|---|---:|---:|---|
| context shell, no wrapper (rf2-2rtt6.58) | 994 B | 992 B | **0.99× — at the line** |
| context shell, wrapper — what ships | 1,099.5 B | 1,097 B | **1.10× — over it** |
| **frame-prop shell, wrapper** *(this row)* | **1,054 B** | **1,051 B** | **1.05× — still over it** |

**It does not clear the line.** The variant gives back **45 of the wrapper's
105 B — 43%** — and the shell stays above 1 KB by more than the per-round
scatter: every one of the twelve B readings is at or above 1,047 B, so it
clears 1,000 B in every round, and clears 1,024 B in every round too
(1.029× and 1.026× against that reading).

Two things this row therefore does **not** license. It does not make the
frame-prop variant an alternative to HD-028's pre-registered fallback: the
fallback is about the wrapper, and 45 B does not reach 105 B. And it does not
put the shell inside the ~75 B component-shape sensitivity
[validation.md](../validation.md) states for this line — **the saving is
smaller than that sensitivity**, so while the A-vs-B comparison is sound (one
page, one shape, paired, disjoint bands), the *absolute* position of either arm
against 1 KB is not resolved to 45 B by this instrument.

#### The controls

**Negative controls — the donors, taken in the same runs.** Neither donor is
minted by `defview`, so neither can see the toggle:

| donor | A1 | B | A2 |
|---|---:|---:|---:|
| Reagent shell (R=0) | 504 [488–516] | 509 [502–520] | 509 [500–521] |
| UIx shell (R=0) | 223 [220–225] | 225 [220–233] | 226 [223–233] |
| Reagent per read | 947 [946–948] | 947 [946–948] | 947 [947–949] |
| UIx per read | 2,979 [2,978–2,981] | 2,980 [2,979–2,981] | 2,979 [2,978–2,980] |

**The donors reproduce their published anchor** — 947 and 2,979–2,980 B/read
against the standing 947–948 and 2,978–2,981 — which is what licenses quoting
this row's B against the previous section's A. (A2's Reagent band reaches 949,
one byte above the anchor's top; the mean, which is the anchored quantity,
is 947 in all three runs.) The **floor** arm is flat across all three:
258 / 257 / 257 B on the Reagent segment and 254 / 253 / 252 B on the UIx one.

**And the A arm reproduces the previous section's published `main` rows to half
a byte.** Paired A here is **1,099.0 / 1,097.5 B**; the wrapper re-take
published **1,099.5 / 1,097 B**, and the slope agrees exactly at 1,278 / 2,115.
That reproduction across two sessions is what says the two sections' figures
sit on one tree and may be put in one table.

**Positive control.** The same dense array of 587,500 unboxed doubles —
**4,700,000 B, fixed before any run.** Measured 4,698,415 B, 4,697,569 B and
4,697,603 B: ratios 0.9997, 0.9995 and 0.9995, **`ok` under
`lane/control-verdict`** at the driver's ±25% slack in all three. Following the
previous section's protocol, `lane/control-verdict` at ±25% is **the only band
this session registered**; the deviations from the prediction are reported as a
non-gating observation with no acceptance band attached.

**0 unverified of 154 mounts**, structural read-back **every field answered**
(boundaries = B, edges = B·R, cells = Q, entries = B, all zero after teardown),
arm-order guard **reportable**, **exit 0** — all three runs, no gate waived and
none widened.

#### The ablation cannot fail silently, which is why one line is enough

The toggle is **one symbol** in `arm1/lang.clj`'s `defview` macro —
`mint-view!` against `mint-frame-prop-view!`. Neither shell is edited: both
already ship on `main` from one body, so the arms differ by which of the two
already-shipped mints the macro names.

A one-line ablation invites the question of whether it took effect at all, and
here it cannot have failed to. `frame-prop-shell` resolves its frame through
`resolve-frame-prop!`, which **raises `:rf.error/no-frame-prop` on a nil
frame** rather than falling back to context. A B run in which the codec had not
marked the heads, or in which the root had not named the frame, would have
thrown on the first boundary of the first mount instead of quietly measuring
the incumbent twice. It instead mounted 1,200 boundaries in every round of six
and answered every structural field — and moved the shell by 45 B on one
segment and 46 B on the other, consistently, in the one run that carried the
edit.

#### The clock half of rf2-2rtt6.72 is BLOCKED, and no clock figure is published

The bead asks for the mount and bulk clock beside this ladder. **It could not
be taken, and nothing is quoted in its place.** `clock_run.cjs` refuses on
`main`, on both rows tried, before it takes a single sample:

    [clock] FAILED: M1: page.evaluate: vj          exit 1
    [clock] FAILED: bulk300: page.evaluate: vj     exit 1

That is the **incumbent** arm on a pristine tree, so it is not this variant's.
It reproduces with `arm1/runtime.cljs` at the shipped blob
`9f3d42be7b`, and it reproduces with `arm1/mount.cljs` reverted to the
`as-element` `render!` that predates `rf2-2rtt6.39`, so it is not PR #7416's
either. `vj` is `cljs.core/ExceptionInfo` in the `:advanced` bundle: the page
threw an `ex-info` and the driver surfaced its minified type name, discarding
the `:rf.error/*` id, the `:where` and the ex-data that would identify it. The
driver's own offline adjudicators are all healthy (`--selftest`, exit 0, 49
checks). Filed as **`rf2-029ed`**; `rf2-2rtt6.72` stays **open** for the clock
rows.

#### Provenance

Whole-tree anchor **`2303ef6781`** on `worker/frameprop-2rtt6-72`, whose only
difference from `origin/main` `1ce64c06a2` is a `:what` string in
`runtime/retained-inventory` recording that the frame-fed variant holds no
`:react/use-context`. It was committed **before** the first run rather than
after, so the measured tree and the published tree are the same tree — the
invariant the audit on PR #7392 established. That it moved nothing is not
asserted: the A arm reproduces the previous section's rows to half a byte.

The one line under test, `defview` in `arm1/lang.clj`:

| arm | line | `arm1/lang.clj` blob |
|---|---|---|
| **A1, A2** | `(re-frame.bench.hicasso.arm1.runtime/mint-view!` | `74cfbfab7e77db64c3098b63a5e58b5ab4c0e1d3` |
| **B** | `(re-frame.bench.hicasso.arm1.runtime/mint-frame-prop-view!` | `eca40a01c9feacc8fe97eede3db59bf21e09f2d9` |

`arm1/runtime.cljs` is `8b37dd2cbf67f19cc3f07933285c04593d8b7e3f` and
`front/codec.cljs` is `cf9ef32dc8f751e344016cfa01b1db722ba2440b` in all three
— **neither shell is touched by the toggle**, and both variants are present
throughout with one of them merely unreached. The working tree was restored
byte-identically for A2 (`git status --porcelain` empty, `lang.clj` back to
`74cfbfab7e`).

The instrument, all under `implementation/core/test/re_frame/bench/` — **all
six byte-identical to the wrapper re-take above**, so the two sections share an
instrument as well as a tree:

| file | blob |
|---|---|
| `p0_run.cjs` | `586474b5cfad0f09df5e3e968ca0282e2c1cd95c` |
| `p0_heap.cljs` | `0a568a63cd24b66865e433c49a62eadff8993e8a` |
| `p0_hicasso.cljs` | `f2440e307423665048dfe227b14baaf4ffc8ac89` |
| `p0_reagent.cljs` | `b1f5ec9223536557403f6ae9415ab42ac26843b0` |
| `p0_uix.cljs` | `deec8976010c17e4d2c6e8dc3499678997acd2c0` |
| `p0_fixture.cljc` | `867ad5838ab64ac6aa7afbf8317d8fb305f53619` |

Reproduce — the context arm as the tree stands, the frame-prop arm by changing
the one symbol above:

```
node implementation/core/test/re_frame/bench/p0_run.cjs --only ladder
```

**Conditions.** 2026-08-04 03:43–04:02 +1000, three runs of ~3.5 minutes,
React 19.2.0, Reagent 2.0.1, UIx 1.4.4, `:advanced` with `goog.DEBUG false`,
headless Chromium via Playwright, Windows 11, 24 logical cores, 32 GB.
**Box verified quiet at open, after the ladder trio and at close** — real CPU
occupancy **2.27% / 3.04% / 1.80%**, measured as summed per-process CPU-time
deltas over a 5-second window divided by the core count. `LoadPercentage` is
deliberately not used: it reads 15–20% on this box when the true occupancy is
under 2%. Throughout: 15–18 node processes (idle MCP servers), 56 chrome,
**zero java**, ~505 processes, ~30.1 GB free, no other worker and no open PR.
**The probes are at open and at close, not before each of the three runs** —
the previous section's protocol — and what carries the same weight here is the
A1/A2 bracket: 2 B and 3 B apart across the pair, which is the box saying it
did not move.

---

### The package itself, priced on this rung at last (rf2-fe0l)

**2026-08-12.** Every candidate figure above this line was taken on
`re-frame.bench.hicasso.arm1.*` — the prototype in the benchmark tree.
`implementation/hicasso/src` is a deliberately frozen *copy* of that runtime,
and until 2026-08-11 **no heap instrument pointed at the package at all**,
which is why [budgets.md](../product/budgets.md) had to refuse its "re-measured
on `implementation/hicasso`" deliverable and report the refusal instead.
PR #7939 repointed this rig's four candidate seams at the package — the mount
door, the runtime reset, and both residue reads — and left the donors, the
floor, the harness, the fixtures, the fit rules and the order guard alone.
**This section is the first reading taken through it**, and it is the anchor
`rf2-hic-018` needs before it mutates the collector: a package baseline taken
on the same instrument as the thing it will later be compared with.

The two dispatches are deliberately separate. The rig merged, and its blobs
were fixed by that merge, **before a single sample existed** — so the window
below could not repair an arm it did not write, and pre-registration is
mechanical here rather than disciplinary.

#### The rig resolves the package, and the compile is not what says so

`:hicasso-bench` compiles both trees. An arm pointed back at the prototype
therefore builds **green with zero warnings** and reads plausibly; it simply
prices different software. Dispatch 1 demonstrated exactly that by reverting one
seam. So the evidence is the rig's own **module graph**, read from a cleared
cache entry so the analysis cache holds precisely what the entry point reached.
It was read **twice**: once before the window opened, so a wrong rig would not
cost a run — and once afterwards **from the build the driver itself made**,
which is the read that matters, because it describes the very bundle the figures
below came out of rather than a rehearsal of it. The two agree exactly:

```
PRESENT  re_frame.hicasso.impl.mount.js      ABSENT  re_frame.bench.hicasso.arm1.runtime.js
PRESENT  re_frame.hicasso.impl.collector.js  ABSENT  re_frame.bench.hicasso.arm1.mount.js
PRESENT  re_frame.hicasso.impl.inventory.js  ABSENT  re_frame.bench.hicasso.arm1.lang.js
PRESENT  re_frame.hicasso.js                 ABSENT  re_frame.bench.hicasso.front.codec.js
130 namespaces in the rig's graph; build=hicasso-bench initFn=re-frame.bench.p0-app/-main
arm1 namespaces in graph: 0
bench.hicasso.front namespaces in graph: 0
hicasso.impl namespaces in graph: 16
```

**That read is worthless without a control, because an enumeration that finds
nothing and an enumeration that looks nowhere print the same thing.** The
control is a sibling program on the *same build id* that genuinely does require
the prototype — `re-frame.bench.hicasso.clock-app/-main`, swapped in through the
driver's own `P0_INIT_FN` override, with **no file in the rig touched**. It
reports `arm1 namespaces in graph: 2`, `hicasso.impl namespaces in graph: 0`,
and exits **1**. The reader can see the prototype when the prototype is there.

Sixteen of the package's eighteen `impl.*` modules are in the graph;
`impl.presence` and `impl.presence-react` are not reached from this arm, which
is a property of what the arm exercises and not a defect.

#### The rows

`B` = 1,200 boundaries held fixed across every rung, 4 roots × 300 cells; six
rounds; rungs 0/1/3/7/20 with Q = E on every one. `y` is exclusive retained
bytes per boundary above the same-round, same-segment floor; bands are min–max
across the six rounds. Residue is per boundary after teardown.

| `reagent-subs` | reads | E | Q | exclusive B/boundary | residue B/bdy |
|---|---:|---:|---:|---:|---:|
| floor | 0 | 0 | 0 | 256 [248–265] | 4 [0–8] |
| reagent | 0 | 0 | 0 | 511 [500–519] | 4 [−6–23] |
| reagent | 1 | 1,200 | 1,200 | 1,678 [1,659–1,692] | 14 [−1–45] |
| reagent | 3 | 3,600 | 3,600 | 3,391 [3,382–3,405] | 2 [−7–18] |
| reagent | 7 | 8,400 | 8,400 | 6,704 [6,682–6,717] | 11 [1–20] |
| reagent | 20 | 24,000 | 24,000 | 19,570 [19,556–19,585] | 6 [−9–46] |
| **hicasso** | 0 | 0 | 0 | **1,100** [1,091–1,107] | 1 [−6–7] |
| **hicasso** | 1 | 1,200 | 1,200 | **2,949** [2,934–2,965] | 3 [−10–19] |
| **hicasso** | 3 | 3,600 | 3,600 | **5,400** [5,384–5,409] | 5 [−2–10] |
| **hicasso** | 7 | 8,400 | 8,400 | **10,310** [10,293–10,331] | 7 [−5–18] |
| **hicasso** | 20 | 24,000 | 24,000 | **29,650** [29,629–29,670] | −6 [−55–12] |

| `uix-subs` | reads | E | Q | exclusive B/boundary | residue B/bdy |
|---|---:|---:|---:|---:|---:|
| floor | 0 | 0 | 0 | 255 [252–261] | 1 [0–6] |
| uix | 0 | 0 | 0 | 220 [214–226] | 0 [0–1] |
| uix | 1 | 1,200 | 1,200 | 3,286 [3,263–3,316] | 18 [0–39] |
| uix | 3 | 3,600 | 3,600 | 9,114 [9,108–9,122] | 6 [−4–20] |
| uix | 7 | 8,400 | 8,400 | 20,800 [20,786–20,816] | 13 [4–24] |
| uix | 20 | 24,000 | 24,000 | 59,825 [59,812–59,832] | −31 [−106–3] |
| **hicasso** | 0 | 0 | 0 | **1,095** [1,087–1,101] | −1 [−6–2] |
| **hicasso** | 1 | 1,200 | 1,200 | **3,660** [3,653–3,665] | 1 [−8–7] |
| **hicasso** | 3 | 3,600 | 3,600 | **7,602** [7,520–7,637] | −17 [−101–14] |
| **hicasso** | 7 | 8,400 | 8,400 | **15,491** [15,428–15,540] | −14 [−69–27] |
| **hicasso** | 20 | 24,000 | 24,000 | **43,681** [43,587–43,738] | −11 [−109–46] |

#### The fitted lines

`y = intercept + slope·R`, fitted over 1/3/7/20 only. `shell` is the directly
measured R=0 rung and never the fitted intercept.

| arm | slope B/read | intercept | shell (R=0, measured) | first read | r² |
|---|---:|---:|---:|---:|---:|
| `reagent-subs` \| reagent | 948 [947–948] | 493 [481–503] | 511 [500–519] | 1,167 [1,153–1,182] | 0.99871 |
| `reagent-subs` \| **hicasso** | **1,417** [1,416–1,417] | 1,097 [1,085–1,110] | **1,100** [1,091–1,107] | 1,849 [1,836–1,859] | 0.99833 |
| `uix-subs` \| uix | 2,980 [2,979–2,981] | 165 [149–177] | 220 [214–226] | 3,066 [3,049–3,090] | 0.99996 |
| `uix-subs` \| **hicasso** | **2,115** [2,109–2,118] | 1,217 [1,187–1,251] | **1,095** [1,087–1,101] | 2,565 [2,557–2,575] | 0.99957 |

All four are lines in R, and **6 of 6 rounds are linear on every arm** — the
per-round verdict, not only the pooled one.

#### Exactly one quantity moved, and the instrument says which

The prototype's most recently published rows are directly above this section.
Set the package beside them:

| quantity | prototype (bench tree) | **package** (this run) | moved? |
|---|---:|---:|---|
| R=0 shell, Reagent segment | 1,099.5 / 1,098 / 1,100 B | **1,100** B | no |
| R=0 shell, UIx segment | 1,097 / 1,096 / 1,099 B | **1,095** B | no |
| slope, UIx segment | 2,115 [2,110–2,118] | **2,115** [2,109–2,118] | no |
| **slope, Reagent segment** | 1,278 [1,275–1,280] | **1,417** [1,416–1,417] | **+139 B/read, +10.9%** |
| Reagent donor shell | 504 / 509 / 509 B | 511 B | no |
| UIx donor shell | 223 / 225 / 226 B | 220 B | no |
| Reagent donor slope | 947 | 948 [947–948] | no |
| UIx donor slope | 2,979 / 2,980 | 2,980 [2,979–2,981] | no |
| floor, Reagent segment | 258 / 257 / 257 B | 256 B | no |
| floor, UIx segment | 254 / 253 / 252 B | 255 B | no |

**Nine of ten quantities reproduce; one moved, and the two bands are disjoint
by 136 B** ([1,275–1,280] against [1,416–1,417]). Because every donor, both
floors, both shells and the *other* candidate slope came back where the
prototype left them, the move is the candidate's and not the instrument's —
which is this studio's own standing rule for putting two sessions' figures in
one table, applied here rather than invented for the occasion.

**The mechanism is not attributed here, and the asymmetry is why.** A package
carrying more per-read state than the frozen prototype would be expected to show
it in *both* segments; it shows in one, to the byte, while the UIx segment
reproduces `2,115` exactly and its ratio moves from `0.7099×` to `0.7098×`.
Naming a cause would take ablations this window is not permitted to run — its
terms are one invocation, and an instrument iterated until it explains itself is
no longer the instrument the figures were taken on. **`rf2-hic-018` owns the
attribution, and it now has the same-instrument package baseline that makes an
attribution possible at all**, which is precisely what this bead existed to
supply.

What it does to the candidate's standing, stated plainly:

| segment | donor | candidate | ratio, prototype | ratio, **package** |
|---|---:|---:|---:|---:|
| `reagent-subs` | 948 B/read | 1,417 B/read | 1.3492× | **1.4953×** (margin −49.5%) |
| `uix-subs` | 2,980 B/read | 2,115 B/read | 0.7099× | **0.7098×** (margin 29.0%) |

The verdict's *shape* is unchanged — won against UIx, lost to Reagent's
`deref`-capture — but the loss against Reagent is materially deeper on the
software that ships, and the bracket a shipped Hicasso sits in widens from
`1,278 – 2,115` to **`1,417 – 2,115` B/read**.

#### Against the 1,024 B line, which is now frozen

The operator froze the shell's paper-fail line at the literal **1,024 B** on
2026-08-12 (the ruling is recorded on `rf2-fe0l`), and adopted with it the rule
that **a confidence band crossing that line is UNRESOLVED rather than a pass**.
Neither clause is load-bearing for this row, and it is worth saying why:

| R=0 shell, package | figure | worst round | vs 1,024 B |
|---|---:|---:|---|
| Reagent segment | 1,100 B | 1,091 B | **1.074× — over, in every round** |
| UIx segment | 1,095 B | 1,087 B | **1.069× — over, in every round** |

Every one of the twelve readings sits at or above 1,087 B, so the row is red
under the frozen reading, red under the retired 1,000 B reading, and **not a
band-crossing case at all**. The freeze changes no verdict here; it will matter
at remediation, where the no-wrapper arm's 994 / 992 B sits exactly in the
window the two readings disagree about.

**The breach survives the move to the package**, which is the substantive news
for `rf2-hic-018`: it is a property of the design and not an artefact of the
prototype it was first measured on.

#### Teardown, in bytes, on the package

**All ten candidate rungs' residue bands straddle zero** — the point estimates
run from −17 to +7 B per boundary and every band contains 0. The structural
witness reports the same thing in objects, and exits on it: boundaries, edges,
cells and entries all read **exactly zero after teardown on every arm of every
round**. Teardown is therefore re-pinned on the package as *indistinguishable
from zero*, which is the honest form of the claim — it is what a distributional
reading can support, where the object counts are exact.

One observation recorded rather than smoothed: two *donor* rungs' residue bands
do not straddle zero (Reagent at R=7, 11 [1–20]; UIx at R=7, 13 [4–24]). Those
are donor rows and not this page's to disposition, but a reader comparing
columns should not have to notice it alone.

#### The controls

Every control below is **from this run**. Nothing is scaled in from another
session and nothing is re-derived.

**The positive control — the instrument has signal, and it is calibrated in
bytes.** The same dense array of 587,500 unboxed doubles, **4,700,000 B fixed
before the run**: measured **4,700,284 B** [4,699,042–4,700,872], ratio
**1.0001** — 0.006% high, the closest reading this control has produced on this
page. `ok` under `lane/control-verdict` at the driver's ±25% slack, which is the
only band this session registers; the deviation is reported as a non-gating
observation.

**The ladder is its own control for the slope.** Reads walk 0 → 20, a twentyfold
range, and every arm answers with a line: r² from 0.99833 to 0.99996, six of six
rounds linear on all four. A per-read cost that could not be made to move with R
would be a coincidence; this one moves with R exactly, in the right direction,
on every arm.

**The segment swap is the control for the shell.** The candidate is one view
layer measured over two subscription substrates. Its R=0 shell reads 1,100 and
1,095 B — **5 B apart**, so the shell does not depend on the substrate beneath
it, which is what a *shell* figure must show. That the instrument can see a
substrate difference at R=0 is not assumed either: the donors' own shells differ
by **291 B** across the same two segments (511 against 220). The instrument is
demonstrably capable of the reading it declines to make for the candidate.

**The floor is the calibrator that licenses the cross-segment comparison.** It
holds no re-frame state, so it is the same work either side of the seam, and it
reads 256 and 255 B — 0.4% apart.

**The fit refuses what it should refuse**, checked in the page before anything
was measured: an exact line is recovered to the byte with R=0 excluded from the
fit; a quadratic per-read term is rejected by the r² floor (0.96464 under 0.98);
and an absurd R=0 rung of 99,999 B **does not move the fit by one bit** — which
is what makes the shell and the slope independent quantities that may be pinned
separately.

**The arm-order guard** ran its twelve self-tests before installation, including
a recorded 2.01× it is required to *refuse*, and returned **`reportable`** over
the samples. **0 unverified of 154 mounts.** The driver exits non-zero on any of
these; it exited **0**.

#### Provenance

Whole-tree anchor **`ce31a30b77`**, which is `origin/main` — the measured tree
and the published tree are the same tree, and the working tree was clean
(`git status --porcelain` empty) before the run and unchanged by it.

The instrument, all under `implementation/core/test/re_frame/bench/`:

| file | blob | vs the frame-prop run above |
|---|---|---|
| `p0_run.cjs` | `ce4f01a9e548dad37513929ea7e03ed0fe909f8f` | moved — one comment line, PR #7939 |
| `p0_heap.cljs` | `ef9b5adcf0ef81487ddbab43affc2e46f229ffac` | moved — the four seams, PR #7939 |
| `p0_hicasso.cljs` | `7a91564f59a216ae4c0d13535fdac65b0ef81481` | moved — the two doors, PR #7939 |
| `p0_reagent.cljs` | `419e166a93526bfb32794fb6236c840068fbd417` | moved — docstring only |
| `p0_uix.cljs` | `f1aaf9cb1e58a62c8c1429ea66bca8bdd8c76a56` | moved — docstring only |
| `p0_fixture.cljc` | `de27135ce820229e782b86628c42f7fcca2b899f` | moved — see below |
| `p0_arms.cljs` | `beced24315f740eede28cf5f32f855ff91bbd854` | — |
| `p0_harness.cljs` | `e18c2f50d4f5985d7bc81ff99dfd173ae296f82b` | — |
| `p0_floor.cljs` | `6b61e125f4bd4c479be9438b55d04c1d8d20e601` | — |

**The donor and fixture blobs are NOT byte-identical to the runs this section
compares against, and that is stated rather than glossed.** Three of them moved
after 2026-08-04. `p0_reagent.cljs` and `p0_uix.cljs` moved by docstring alone
(`606bad8445`, re-pointing a superseded owner citation). `p0_fixture.cljc` also
carries an executable change (`eb17886c5b`, `rf2-2rtt6.140`): `seed-db` gained a
grid-width arity that **defaults to the published `cells-n`**, and `:p0/fan`'s
modulus became `(count (:cells db))` where it was the same constant — so every
caller that passes nothing, the retention ladder included, gets the published
page to the byte. That is an argument from construction; the *measurement* is
that both donors and both floors came back on their published anchors, which is
what actually settles it.

The candidate arm is no longer in a table of its own, because **the candidate
arm is now the package**. Its doors, under
`implementation/hicasso/src/re_frame/hicasso/`:

| file | blob |
|---|---|
| `impl/mount.cljs` | `ddf06f21e0ae2112031de6f835da389ed6760ec3` |
| `impl/collector.cljs` | `3876ae023224f670e2cdaa086cf364f5fdbf4844` |
| `impl/inventory.cljs` | `e1ac96953e7739aa30c8b7bfd7e752bc159fabda` |
| `../hicasso.cljc` (the facade) | `405646b7bceab6d98d1fdb879932d7913b7f149e` |

Sixteen `impl.*` modules are in the graph, so these four are the doors and not
the whole arm; the tree anchor above is what pins the rest.

Reproduce:

```
node implementation/core/test/re_frame/bench/p0_run.cjs --only ladder
```

(defaults `P0_LADDER_ROUNDS=6 P0_LADDER_RUNGS=0,1,3,7,20 P0_ROOTS=4`; no build
id was added — the driver rides `:hicasso-bench` through `P0_BUILD` and
`P0_INIT_FN`, merging `:output-dir` and `:init-fn` onto it, and clears that id's
cache entry first.)

**Conditions.** 2026-08-12 06:47–06:51 +1000, **one run** of ~3.5 minutes —
the window's terms are a single invocation, and no second run was taken.
Captured exit **0**. React 19.2.0, Reagent 2.0.1, UIx 1.4.4, node 24.13.0,
`:advanced` with `goog.DEBUG false`, headless Chromium via Playwright,
Windows 11, 24 logical cores. **Box verified quiet before and after**, by two
readings that cover each other's blind spot: real CPU occupancy
**4.17% / 3.02%** before and **2.15% / 2.35%** after — summed per-process
CPU-time deltas over a 5-second window divided by the core count, never
`LoadPercentage`, which reads 15–20% on this box at a true occupancy under 2% —
and `\System\Processor Queue Length` **0 on every sample**, which is the
decisive number because it says whether anything was *waiting* for a core.
Throughout: 32 node processes (idle MCP servers), 129 chrome, **zero java**,
~585 processes, ~17.2 GB free, no other worker running and no local gate.

The occupancy is higher than the 1.4–3.0% the 2026-08-04 sections recorded, and
the reason is visible in the counts: 129 chrome processes against 56, the
operator's own idle browser. It is recorded rather than corrected for. This is a
**retained-heap** row and not a clock row, and this page has already priced what
contention does to it — a deliberately contended trial landed within about
0.07% of a quiet six-round range, and was still refused as evidence. A box at
2–4% with an empty run queue is far inside that.

---

### The `+139 B/read` attributed, and the premise it had to correct first (rf2-l50z)

**Pre-registered 2026-08-13 06:13 +1000, before any arm of this bisection ran.**
The section above left the mechanism of its one moved quantity `[OPEN]` and said
so plainly; [`substrate-decision.md` §6](../product/substrate-decision.md#6-what-this-page-does-not-decide)
named the ablation that would settle it and declined to guess. This subsection
runs that ablation.

#### The premise did not survive being checked, and the asymmetry is why

The section above reads its own result as a *package versus prototype* contrast,
and records the thing that does not fit: "a package carrying more per-read state
than the frozen prototype would be expected to show it in **both** segments; it
shows in one, to the byte."

It shows in one because the contrast is not the one the table's columns imply.
Those two columns are **two runs separated in time**, not two source trees
measured together — the prototype's last published slope was taken
**2026-08-04** (the frame-prop section above, whole-tree anchor `2303ef6781` off
`origin/main` `1ce64c06a2`, which landed 2026-08-04 03:03 +1000), and the
package run was taken **2026-08-12**. Everything that landed in that window is
inside the delta, and the `wire-cell!` bodies of the two trees are *identical
today* — the package's `impl/collector.cljs` and the prototype's
`arm1/runtime.cljs` carry the same subscribe / activate / baseline / watch /
on-dispose sequence, line for line.

One landing in that window adds exactly one per-cell retained item and adds it
**to the ratom family alone**: `9d01cd171e`, `fix(hicasso): arm1 must activate
its Reaction before watching it` (`rf2-2kshh`), merged **2026-08-07 09:32
+1000** — three days after the 1,278 reading and five before the package run.
Its whole code change is one line inside `wire-cell!`:

```clojure
(interop/activate-derived-value! reaction)
```

and its own commit message states the property that makes the observed shape
predictable rather than puzzling: *"The op is a routed no-op on the React-hook
spine, so UIx is untouched."*

**The mechanism, in objects.** A `re-frame.subs` subscription under the ratom
family IS a bare `reagent.ratom/Reaction` built without `:auto-run`, and a
Reaction learns its sources only through `deref-capture`. The baseline deref
`wire-cell!` takes is outside `*ratom-context*`, so before this landing the
reaction ran its body raw and left `watching` **nil** — watchable, watched, and
notifying nobody (the deafness `rf2-2kshh` repaired). Activation is
`ratom/run`, a real capture: afterwards the reaction holds a populated
`watching` array **and** is enrolled in each captured source's watcher map. Both
are retained for the life of the cell, and cells are B·R on this rung's
mandatory distinct-query witness — so the whole price lands in the marginal
slope and none of it in the shell, which is the same reason
[the disposal hook](#the-slope-went-stale-before-this-section-merged-and-the-landing-that-moved-it-rf2-2rtt660)
was invisible to work pointed at the shell. On the React-hook spine
`:adapter/activate-derived-value!` is published by nobody
(`make-derived-value-fn` wires one watch per source at construction), the
late-bound lookup finds no hook, and the call returns `nil` having allocated
nothing.

**This is a hypothesis with a mechanism, and it is not yet an attribution.** The
ablation below is what makes it one.

#### The registered prediction

Written before the first arm was measured, in the terms the A–B–A bisection
above established:

| quantity | A (main as it stands) | B (main minus the one line) | A2 (restored) |
|---|---:|---:|---:|
| slope, Reagent segment | 1,417 B/read | **≈ 1,278 B/read** | 1,417 B/read |
| slope, UIx segment | 2,115 B/read | **2,115 B/read — unchanged** | 2,115 B/read |
| both donors, both floors, both shells | published | **unchanged** | published |

The **UIx segment is the negative control and it is a control by measurement**,
having reproduced `2,115` to the byte across the tree move the whole delta is
supposed to live in. If the ablation moves it, activation is not the mechanism
and the reading is refused rather than published.

---

## 7. Anchors, and one that does not fully reproduce

The R=0 rung is here to tie this instrument to the published sub-free rows
before anything it says about reads is believed. **Nothing in §§1–5 is derived
from it** — and unlike the superseded publication, that is now true of the fit
as well as of the prose.

| | published (`freehand-vs-reagent-memory.md` §2) | here, R=0 | |
|---|---:|---:|---:|
| Reagent sub-free boundary | 411 / 409 (A), 403 (C) | 428 [421–434] (A), 429 (C) | **+4.1% / +6.5%** |
| UIx sub-free boundary | 251 / 252 (A), 251 (C) | 208 [201–213] (A), 211 (C) | **−17.1%** |

The Reagent anchor reproduces. **The UIx anchor reads 17% below the published
figure and is not reconciled away here.** The witnesses are not identical —
`storm` is 10 roots × 300 `span.leaf` boundaries carrying a text child; this is
1 root × 1,200 `span.cell` boundaries carrying a `data-i` attribute the floor
also carries — and two differently-built instruments landing 43 B apart on a
251 B figure is the size of effect a witness change can produce. It is recorded
rather than explained, and it is small against the thing the page is for: the
shell is O(200 B) on either reading, the read is O(3,550 B), and the ratio
between them is what the ladder was built to measure.

---

## 8. Superseded

**The publication of 2026-07-30, withdrawn 2026-07-31.** Its rows are on
`rf2-2rtt6.1`; nothing there has been amended, and this section says why the
earlier rows should not be quoted.

- **Provenance as published.** The page named `77ec2aa19a` as "the one to
  `git show`". That is the **authored PR commit and is not on `main`**. The
  durable landed instrument commit is **`9b0759be46`**, and the full
  authored-to-landed mapping is `6f98cd7cc0=6c6d299664`, `77ec2aa19a=9b0759be46`,
  `50aa0dfee3=42f4692104`. Recorded because a page whose SHA cannot be checked
  out cannot be reproduced.
- **The fits are withdrawn and replaced.** `curves()` regressed
  `[0, 1, 3, 7, 20]` — including the sub-free anchor the page said in three
  separate places was excluded — and published **942 B/read + 406 B** on
  Reagent and **3,550 B/read + 150 B** on UIx. Refitted over 1/3/7/20 alone the
  same data gives about 943 + 397 and 3,552 + 118, which is how the audit
  proved the anchor had been used. This re-run measures **943 + 397** and
  **3,552 + 113** from fresh arms.
- **The intercept moved most, and it is the figure the anchor most distorted.**
  On UIx it drops from 150 B to 113 B — a 25% change in a term the page uses to
  argue that 251 B was "the intercept". The argument survives; the number in it
  did not.
- **"The first read costs 3,550 B" is withdrawn as a phrasing.** That is the
  marginal slope. The first read costs **3,600 B** on UIx and **1,137 B** on
  Reagent, and on Reagent the difference from the slope is 21%.
- **The direct rung data stands.** Every A-reader rung on both substrates
  re-measures within 0.5% of its superseded value, the r² values are unchanged
  in kind, both curves still agree to better than 2%, and the 3.77× ratio is
  unchanged to three significant figures. **The finding this page exists for —
  that 516 and 251 were the slope-fraction and the intercept of one line — is
  not affected by the correction, and neither is anything the programme has
  been asked to act on.**
- **Reader C could fail silently.** A snapshot failure was caught and the run
  still exited 0, so "reader C agrees" was a claim the instrument could not have
  refused. It exits 4 now.
- **§3's R=0 and R=20 object columns were not floor-subtracted** while the rest
  of the table was. Corrected above; the per-read figures were never affected.

---

## What a JS-heap reading cannot see

DOM nodes live in Blink's C++ heap, not V8's, so none of these figures contain
the elements themselves. Every arm builds the identical DOM — same element
count, same classes, same one-character text — so the omission cancels exactly
in `arm − floor`, which is the only figure quoted.
