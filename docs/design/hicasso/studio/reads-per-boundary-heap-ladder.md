# What does a *subscribing* boundary cost in memory, at 1, 3, 7 and 20 reads?

Seat: EVIDENCE SPIKE, EP-0038 Wave 0. Bead `rf2-2rtt6.5`; numbers appended to
the operator-owned standard `rf2-2rtt6.1`.

**Re-measured 2026-07-31 after the audit of PR #7260**, which found that the
regression included the R=0 anchor the page promised it excluded. Every figure
below is a fresh reading through the corrected fit; the superseded publication
is recorded in [§7](#7-superseded) rather than deleted.

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
these rows; its ratios remain the cross-regime check.

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
Authored as `09ec4e6b3c` on `worker/bench-audit-cluster`. **If that SHA does not
resolve, a rebase moved it and the blobs above are what to trust** — this finds
a commit carrying them, and confirms it:

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
  boundary costs **3,552 B** [3,551–3,553] — **6.9× the 516 B hook figure** and
  **17.1× the 208 B boundary figure**. On Reagent the same read costs **943 B**
  [935–944].

Put the two figures on one line and they stop arguing. The fitted lines are
`cost = 113 + 3,552 · R` on UIx and `cost = 397 + 943 · R` on Reagent, with the
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
| Reagent | **943 B** [935–944] | **1,137 B** [1,125–1,141] |
| UIx | **3,552 B** [3,551–3,553] | **3,600 B** [3,581–3,601] |

Both are measured, both are quoted, and neither is derived from the other. On
UIx the two are within 1.4% and the distinction barely matters; on Reagent the
first read costs **21% more** than the marginal one, which is a real feature of
that substrate and was invisible while one number stood for both.

Three consequences, and the second is the one the programme has to act on.

1. **On memory, UIx is not the frontier — it inverts.** UIx wins the
   per-boundary shell (**208 B** against Reagent's **428 B**, 0.49×) and loses
   the read by **3.77×** (3,552 against 943). The crossover is *below one read*:
   any boundary that subscribes at all is cheaper on Reagent. The census's
   seven-read archetype costs **24,758 B/boundary on the raw UIx spine against
   Reagent's 6,587 B**.
2. **The ~0.4–0.5 KB exclusive-retained budget is a SHELL budget, and nothing
   measured meets it for a boundary that reads once.** Both shells clear it
   (UIx 208 B, Reagent 428 B). At one read UIx is at **3,807 B** — 7.6× the
   1 KB paper-fail line — and Reagent at **1,562 B**, 1.6×. The budget row in
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
**3,552 B for one read**, the store hook is **14.5%** — real, and nowhere near
the whole story. **A read is not a hook.** It is a hook stack *and* a
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
operative upper-envelope family.** Under the delegated ruling on `rf2-2rtt6.1`
as regime-qualified by the heap-regime ruling (rf2-2rtt6.16), the measured UIx
retained-heap figure on this witness *is* the red-zone threshold, and these are
it — witness stamp B = 1,200 · E = 1,200·R · Q = E, **re-derived from the
corrected fit, superseding the values published on 2026-07-30**:

| axis | UIx red-zone | superseded value |
|---|---:|---:|
| per-boundary shell (R=0, measured) | **208 B** [201–213] | 212 B |
| per read (marginal) | **3,552 B** [3,551–3,553] | 3,550 B |
| first read (increment) | **3,600 B** [3,581–3,601] | *not published* |
| boundary @ 1 read | **3,807 B** | 3,811 B |
| boundary @ 3 reads | **10,785 B** | 10,788 B |
| boundary @ 7 reads | **24,758 B** | 24,762 B |
| boundary @ 20 reads | **71,229 B** | 71,232 B |

Every threshold moved by less than 0.2%, so no candidate's verdict can turn on
the correction. They are restated anyway, because a red-zone whose provenance
is a fit that used a forbidden rung is not a red-zone anyone should have to
defend.

**The inversion this page surfaced has been ruled on** (rf2-2rtt6.16, delegated
by Mike, 2026-07-31; transcription on rf2-2rtt6.1). On retained heap UIx is
3.77× worse than Reagent per read and the crossover sits below a single read,
so a UIx-only ceiling is 3.77× looser than the best measured option. The ruling
keeps **both lines, regime-matched**, rather than re-sourcing the red-zone: the
red-zone stays UIx-sourced at **3,552 B/read** [3,551–3,553] — worse than that
is RED and needs an explicit operator waiver naming the dogfood benefit — and
Reagent's **943 B/read** [935–944] governs through K3 — worse than it with no
named paper path down is K3 territory. Between the two lines a candidate is
**"UIx-rule cleared, K3 open until a path down is named"**, never plain green.
**943 B/read remains the number a native layer actually has to beat.**

**A target for HD-002's grouped tier.** Grouped `use-subs` — one fixed hook
receiving the whole query collection — can only remove the *hook* half of a
read. The subscription half is common to both arms. So the floor a grouped
mechanism can reach on this witness is bounded below by roughly Reagent's
**943 B/read**, and the prize for grouping is the difference: **≈2,600 B per
read**, or **≈18 KB on the census's seven-read archetype**. That is a large
prize and a concrete pre-registered number, which is what HD-002 clause (c) asks
strategy hypotheses to be counted against.

**The budget row is now split, shell from read.**
[validation.md](../validation.md) sets *exclusive retained per boundary* at
~0.4–0.5 KB target, >1 KB paper-fail, and per the heap-regime ruling
(rf2-2rtt6.16, Part 3) that line is explicitly the **R = 0 boundary shell** —
both donors comply (Reagent ~418–428 B, UIx ~208 B). The per-read axis is
judged separately under the regime-matched gates above, so the shipped Reagent
adapter is not retroactively K3-failed: its ~1,562 B at one read is shell
(~428 B) plus first-read increment (~1,137 B), each judged on its own axis —
and a candidate cannot pass the paper line by amortising subscriptions across
boundaries. Component budget rows (shell / per-edge / per-unique-key) enter
validation.md only after rf2-5prok's fan-out sweep verifies the additive heap
model and prices the terms; in this witness (Q = E) the 943 B slope is the
*sum* of the per-edge and per-unique-key terms — a valid total marginal cost in
this regime only, not a pure view-layer per-read price.

---

## 6. Anchors, and one that does not fully reproduce

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

## 7. Superseded

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
