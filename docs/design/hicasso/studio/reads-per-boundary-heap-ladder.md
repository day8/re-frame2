# What does a *subscribing* boundary cost in memory, at 1, 3, 7 and 20 reads?

Seat: EVIDENCE SPIKE, EP-0038 Wave 0. Bead `rf2-2rtt6.5`; numbers appended to
the operator-owned standard `rf2-2rtt6.1`.

Measured: 2026-07-30, at commit **`b083b060cb`**, which the rebase onto `main`
carried forward as **`77ec2aa19a`** — the three instrument files are byte-identical
across the two (verified by `git hash-object`), and `77ec2aa19a` is the one to
`git show`. The instrument ships in the same PR as this page. Reagent **2.0.1**, UIx
**1.4.4**, React **19.2.0**, shadow-cljs **3.4.10**, node **24.13.0**, headless
**Chromium 147.0.7727.15** via Playwright 1.59.1. Windows 11, single developer
workstation with other agents running concurrently. Every arm is an
`:advanced` ClojureScript bundle with `goog.DEBUG false`
(`:freehand-release`). **Browser numbers**; nothing here is a JVM or Node
figure.

Reproduce:

```
node implementation/freehand/test/re_frame/freehand/bench/reads_ladder_run.cjs
```

(defaults `LADDER_ROUNDS=6 LADDER_SNAPSHOT=1 LADDER_SUBSTRATES=reagent,uix`;
exits **2** if the arm-order guard refuses, **3** on an unverified mount.)

---

## The answer, first

**The 516-vs-251 tension is not a disagreement. It is two measurements of two
different things, neither of which is what a subscribing boundary costs.**

- **251 B was measuring the INTERCEPT** — the per-boundary shell of a UIx
  boundary that reads *nothing*. This ladder measures that shell directly at
  **212 B** [207–216] and recovers it as the fitted intercept of the reads
  curve at **150 B** [146–160].
- **516 B was measuring one hook slot in isolation** — `useSyncExternalStore`
  alone, decomposed out of a Freehand boundary by `rf2-oob3g`. It is neither a
  boundary nor a read. It is one component of the slope, and a minority one.
- **Neither priced a read.** A UIx boundary's *first* re-frame2 subscription
  read costs **3,550 B** [3,549–3,551] — **6.9× the 516 B hook figure** and
  **16.7× the 212 B boundary figure**. On Reagent the same read costs **942 B**
  [941–943].

Put the two figures on one line and they stop arguing. The fitted lines are
`cost = 150 + 3,550 · R` on UIx and `cost = 406 + 942 · R` on Reagent, with the
directly measured R=0 rungs — 212 B and 431 B — sitting a couple of hundred
bytes above each intercept, which is the width of this instrument's zero against
a 71 KB range. **251 is the first term; 516 is a fraction of the second.** There
was never a contradiction to resolve — only a category error, and a category
error is exactly what a ladder cannot make.

Three consequences, and the second is the one the programme has to act on.

1. **On memory, UIx is not the frontier — it inverts.** UIx wins the
   per-boundary shell (**212 B** against Reagent's **431 B**, 0.49×) and loses
   the read by **3.77×** (3,550 against 942). The crossover is *below one read*:
   any boundary that subscribes at all is cheaper on Reagent. The census's
   seven-read archetype costs **24,762 B/boundary on the raw UIx spine against
   Reagent's 6,580 B**.
2. **The ~0.4–0.5 KB exclusive-retained budget is a SHELL budget, and nothing
   measured meets it for a boundary that reads once.** Both shells clear it
   (UIx 212 B, Reagent 431 B). At one read UIx is at **3,811 B** — 7.6× the
   1 KB paper-fail line — and Reagent at **1,565 B**, 1.6×. The budget row in
   [validation.md](../validation.md) needs a per-read companion, or it is
   unfalsifiable; see [§5](#5-what-this-hands-the-programme).
3. **HD-002's tier-1 exclusion now has a price in bytes.** The scalar per-read
   hook spine was excluded as product on hook-rule grounds (N reads = N hooks
   breaks HD-020's ≤2-hook budget). Memory says the same thing independently and
   louder: the comparator arm allocates **128.5 objects per read** against the
   Reagent path's **36.2**, at an almost identical **~27 bytes per object**. The
   spine is not allocating bigger things; it is allocating **3.55× as many of
   them**.

And a null result worth stating because its absence would have been the worse
finding: **neither arm retains anything after teardown.** Released heap returns
to baseline within ±17 B per boundary on every arm, and under ±5 B on almost
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
that §1's third consequence rests on.

**The in-situ positive control, predicted before anything is measured.** A dense
JS array of 587,500 doubles, which V8 stores as unboxed 8-byte slots:
**4,700,000 bytes, known in advance** — deliberately the same ~4.7 MB the broken
sampler once reported as 0.00 MB. It rides **every round**, on the same readers
as the arms.

| page | reader | predicted | measured | error |
|---|---|---:|---:|---:|
| Reagent | A | 4,700,000 B | 4,700,365 B [4,699,074–4,700,974] | **+0.008%** |
| Reagent | B | 4,700,000 B | 4,700,358 B | +0.008% |
| Reagent | C | 4,700,000 B | **4,700,024 B** | **+0.001%** |
| UIx | A | 4,700,000 B | 4,700,068 B [4,692,128–4,700,910] | **+0.001%** |
| UIx | B | 4,700,000 B | 4,700,068 B | +0.001% |
| UIx | C | 4,700,000 B | 4,698,540 B | −0.031% |

**Verification: 0 unverified of 186 mounts.** Every mount counts the boundary
elements it should have produced and answers the count beside the expectation —
an arm that silently rendered nothing would otherwise read as the cheapest
substrate in the table.

**Order.** Six rounds per page; the schedule rotates *and reflects*, so every arm
is measured after at least two distinct predecessors and both whole-plan orders
run. Even rounds forward, odd reversed, reported **separately, never as a mean**
— the pair exists to show the figure does not move, and by the house rule
overlapping ranges mean indistinguishable. **Position dominates adjacency**, so
an unread warm-up pass over every arm precedes round 1. The arm-order guard
(`order_guard.cjs`, self-tested before the bundle is built) returned **clean on
both pages**; it refuses on a contaminated *or* an unchecked arm and the driver
exits 2 when it does. It did exactly that on a one-round trial run, which is how
the refusal path is known to work here.

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

---

## 1. Curve B — fixed boundaries × growing reads

1,200 boundaries, reads varying. `y` is retained bytes per boundary above the
1,200-boundary floor. **A** is six rounds; **fwd**/**rev** split those six by
plan direction; **C** is the independent snapshot pass.

### Reagent on re-frame2 subs

| reads | A (6 rounds) | A, forward | A, reversed | C |
|---:|---:|---:|---:|---:|
| 0 *(anchor)* | 431 [420–440] | 421 [420–427] | 436 [436–440] | 428 |
| **1** | **1,565** [1,552–1,570] | 1,564 [1,553–1,566] | 1,565 [1,552–1,570] | 1,597 |
| **3** | **3,292** [3,280–3,363] | 3,297 [3,287–3,363] | 3,285 [3,280–3,299] | 3,301 |
| **7** | **6,580** [6,565–6,616] | 6,582 [6,576–6,616] | 6,578 [6,565–6,585] | 6,577 |
| **20** | **19,379** [19,368–19,399] | 19,376 [19,368–19,399] | 19,382 [19,368–19,392] | 19,377 |

> **slope = 942 B per read** [941–943] · forward 942 [941–943] · reversed 942
> [942–942] · **intercept = 406 B** [401–431] · r² 0.99896 [0.99895–0.99905]

### UIx on re-frame2 subs

| reads | A (6 rounds) | A, forward | A, reversed | C |
|---:|---:|---:|---:|---:|
| 0 *(anchor)* | 212 [207–216] | 214 [207–216] | 211 [210–214] | 212 |
| **1** | **3,811** [3,799–3,813] | 3,810 [3,799–3,812] | 3,812 [3,809–3,813] | 3,848 |
| **3** | **10,788** [10,779–10,815] | 10,794 [10,789–10,815] | 10,781 [10,779–10,788] | 10,807 |
| **7** | **24,762** [24,740–24,772] | 24,770 [24,767–24,772] | 24,752 [24,740–24,757] | 24,792 |
| **20** | **71,232** [71,206–71,246] | 71,235 [71,230–71,242] | 71,224 [71,206–71,246] | 71,291 |

> **slope = 3,550 B per read** [3,549–3,551] · forward 3,550 [3,550–3,551] ·
> reversed 3,550 [3,549–3,551] · **intercept = 150 B** [146–160] · r² 0.99998

**Every rung's forward and reversed ranges overlap**, on both substrates, so by
this studio's own rule the plan direction is indistinguishable on every figure
above. The independent reader agrees with A to better than **1.0%** on UIx and
**2.1%** on Reagent (worst case the R=1 rung, 1,597 against 1,565).

**Both curves are lines.** r² ≥ 0.9989 in every round on both substrates. That
matters more than it looks: a per-read cost that grew or shrank with the number
of reads would mean the slope was not a per-read cost at all, and the fit is
what forecloses that reading.

---

## 2. Curve A — fixed reads × growing boundaries

Three reads throughout; boundaries doubling. `y` is *total* excess over the
same-size floor, so the slope is bytes per boundary and the intercept is
whatever the page pays once.

| boundaries | Reagent, fwd | Reagent, rev | UIx, fwd | UIx, rev |
|---:|---:|---:|---:|---:|
| 300 | 3,580 [3,574–3,592] | 3,545 [3,538–3,557] | 10,979 [10,959–11,206] | 10,991 [10,978–10,994] |
| 600 | 3,388 [3,380–3,420] | 3,429 [3,359–3,430] | 10,844 [10,841–10,865] | 10,873 [10,844–10,879] |
| 1,200 | 3,297 [3,287–3,363] | 3,285 [3,280–3,299] | 10,794 [10,789–10,815] | 10,781 [10,779–10,788] |
| 2,400 | 3,231 [3,222–3,233] | 3,224 [3,132–3,228] | 10,742 [10,736–10,742] | 10,750 [10,750–10,754] |

*(bytes per boundary, so the visible decline down each column is the page
constant amortising — which is the thing this curve exists to separate out.)*

| | bytes per boundary @ 3 reads | page constant | r² |
|---|---:|---:|---:|
| **Reagent** | **3,177** [3,061–3,186] | 136,731 B [115,998–199,589] | 0.99997 |
| — forward / reversed | 3,182 [3,165–3,186] / 3,174 [3,061–3,180] | | |
| **UIx** | **10,711** [10,687–10,718] | 89,929 B [76,356–125,879] | 1.00000 |
| — forward / reversed | 10,700 [10,687–10,710] / 10,718 [10,712–10,718] | | |

**The two curves agree, and that is the point of separating them.** Curve B's
fit predicts a three-read boundary at `406 + 3·942 = 3,232 B` on Reagent and
`150 + 3·3,550 = 10,800 B` on UIx. Curve A, built from four different arms and
a different regression, answers **3,177 B** and **10,711 B** — within **1.7%**
and **0.8%**. A per-boundary cost measured by growing the boundaries and a
per-boundary cost inferred from growing the reads land on the same number, so
the decomposition into a shell plus a per-read term is not an artefact of either
fit.

**The page constant is small and does not carry the result**: 90–137 KB against
3.4–25.8 MB of boundary term, i.e. **0.3%–4%**. So Curve B's per-boundary
figures are per-boundary figures, not a page constant divided by 1,200.

---

## 3. What the reads are made of

Reader C counts nodes as well as bytes.

| | objects per boundary, R=0 | per boundary, R=20 | **objects per read** | **bytes per object** |
|---|---:|---:|---:|---:|
| Reagent | 24.9 | 749.6 | **36.2** | 26.0 |
| UIx | 18.0 | 2,587.6 | **128.5** | 27.6 |

The two substrates allocate objects of **essentially the same size**. The entire
3.77× is **object count**: the UIx spine's `use-subscribe` — `useSyncExternalStore`
plus the two `useRef`s, the `useMemo` and the committed-snapshot bookkeeping
around it, per read — costs 3.55× as many objects as one `deref` of the same
subscription under Reagent's capture.

This is where 516 B belongs. `rf2-oob3g` put React's six hooks at 1,171 B for a
whole boundary and `useSyncExternalStore` alone at 516 B of that. Against
**3,550 B for one read**, the store hook is **14.5%** — real, and nowhere near
the whole story. **A read is not a hook.** It is a hook stack *and* a
subscription: a reaction, a cache entry keyed by `(query-id, args)`, a query
vector, and a watch registration, all of which the Reagent arm pays too. Reagent's
942 B is the closest thing this page has to a floor for *the subscription
itself*, which makes it the number a grouped or collected read mechanism has to
beat — see §5.

---

## 4. What happens after teardown

Every arm is released, collected and read again in the same window. A substrate
whose released heap does not return to baseline is retaining something after
unmount, which is a different and worse finding than a large per-boundary figure.

Residue after release, bytes per boundary, median of six rounds:

| | worst arm | typical |
|---|---:|---:|
| Reagent | +17.1 (`a-r3-b300`) | 0.1 – 7.5 |
| UIx | +7.2 (`b-r20-b1200`) | −0.4 – 4.7 |

Floors read −3.5 to +1.3, which is the size of this instrument's zero. **Both
arms return to baseline.** HD-002's clause-(d) survival metric — *zero retained
per-occurrence objects after commit/teardown* — is met by both substrates, and
any candidate that misses it is missing something both donors already have.

---

## 5. What this hands the programme

**The red-zones for this witness family.** Under the delegated ruling on
`rf2-2rtt6.1`, the measured UIx retained-heap figure *is* the red-zone
threshold, and these are it:

| axis | UIx red-zone |
|---|---:|
| per-boundary shell (R=0) | **212 B** [207–216] |
| per read | **3,550 B** [3,549–3,551] |
| boundary @ 1 read | **3,811 B** |
| boundary @ 3 reads | **10,788 B** |
| boundary @ 7 reads | **24,762 B** |
| boundary @ 20 reads | **71,232 B** |

**One thing the operator should look at before those are applied mechanically.**
The ruling's rationale is that *UIx is the frontier comparator*. On the clock
that is the recorded position. **On retained heap, in this family, it is not**:
UIx is 3.77× worse than Reagent per read, and the crossover sits below a single
read. A red-zone derived from UIx alone would therefore set a per-read ceiling
**3.77× looser than the best measured option**, and a candidate could clear it
comfortably while being worse than the Reagent adapter that already ships. The
rule as written is still the rule; this page only records that on this axis it
is not binding, and that **942 B/read is the number a native layer actually has
to beat.** Whether to tighten the memory red-zone to the per-axis best rather
than to UIx is the operator's call, and only the operator's.

**A target for HD-002's grouped tier.** Grouped `use-subs` — one fixed hook
receiving the whole query collection — can only remove the *hook* half of a
read. The subscription half is common to both arms. So the floor a grouped
mechanism can reach on this witness is bounded below by roughly Reagent's
**942 B/read**, and the prize for grouping is the difference: **≈2,600 B per
read**, or **≈18 KB on the census's seven-read archetype**. That is a large
prize and a concrete pre-registered number, which is what HD-002 clause (c) asks
strategy hypotheses to be counted against.

**A budget that needs a second row.** `validation.md` sets *exclusive retained
per boundary* at ~0.4–0.5 KB target, >1 KB paper-fail. Read as a **shell**
budget it is exactly right and both donors pass. Read as a *boundary including
its reads* it is unreachable — the cheapest substrate measured is 1.6× the
paper-fail line at **one** read. The budget table wants a per-read row beside
the per-boundary one, and the per-read row's honest anchor is 942 B.

---

## Anchors, and one that does not fully reproduce

The R=0 rung is here to tie this instrument to the published sub-free rows
before anything it says about reads is believed. **Nothing in §§1–5 is derived
from it**; every reactive figure above is measured at 1, 3, 7 or 20 reads
directly.

| | published (`freehand-vs-reagent-memory.md` §2) | here, R=0 | |
|---|---:|---:|---:|
| Reagent sub-free boundary | 411 / 409 (A), 403 (C) | 431 [420–440] (A), 428 (C) | **+4.9% / +6.2%** |
| UIx sub-free boundary | 251 / 252 (A), 251 (C) | 212 [207–216] (A), 212 (C) | **−15.5%** |

The Reagent anchor reproduces. **The UIx anchor reads 15% below the published
figure and is not reconciled away here.** The witnesses are not identical —
`storm` is 10 roots × 300 `span.leaf` boundaries carrying a text child; this is
1 root × 1,200 `span.cell` boundaries carrying a `data-i` attribute the floor
also carries — and two differently-built instruments landing 39 B apart on a
251 B figure is the size of effect a witness change can produce. It is recorded
rather than explained, and it is small against the thing the page is for: the
shell is O(200 B) on either reading, the read is O(3,550 B), and the ratio
between them is what the ladder was built to measure.

---

## What a JS-heap reading cannot see

DOM nodes live in Blink's C++ heap, not V8's, so none of these figures contain
the elements themselves. Every arm builds the identical DOM — same element
count, same classes, same one-character text — so the omission cancels exactly
in `arm − floor`, which is the only figure quoted.
