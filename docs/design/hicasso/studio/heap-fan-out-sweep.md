# What does sharing a subscription do to a boundary's retained heap?

Seat: EVIDENCE SPIKE, EP-0038. Bead `rf2-5prok`, adopted as *Codex rec 4* by the
heap-regime ruling (authoritative text on `rf2-2rtt6.16`; transcription on
`rf2-2rtt6.1`).

**Standing.** This sweep is **non-gating** for that ruling, which stands
regardless of what is below. It is **gating** for freezing Part 3's component
budget rows — the shell / per-edge / per-unique-key numbers that may not enter
[validation.md](../validation.md) until a sweep verifies the additive model and
prices the terms. [§6](#6-what-this-unblocks) says which rows are unblocked and
which one is refused.

**Warrant correction, 2026-07-31 — the held-out claim is withdrawn.** This
page's first revision advertised `R2Q2B` as a rung *held out of every
identification* and presented the model as validated by predicting it. The audit
of PR #7306 found that the adjudicator gives `R2Q2B` a second job: its measured
value is half of the `key-2` estimate, which feeds `key-gap`, which feeds
`key-ok?` — a **mandatory** input to model selection. The rung is an input to
the verdict, not a witness withheld from it, and the residual it returns is
arithmetically the other checks recombined. **The independence claim is
retracted here and everywhere it was carried downstream.** The rows, the
contrasts and the component magnitudes below are unchanged and unedited;
[§4](#4-the-additive-model) states, with the identity written out, exactly what
they now rest on and what they do not. No new measurement was taken: the
operator deferred further heap measurement on 2026-07-31, and
[§7](#7-open) records what an independent adjudication would require if one is
ever run.

---

## The answer, first

**Cache cardinality is worth more than a factor of two, and it is now measured
on one instrument in one run instead of inferred across two.** Holding
boundaries, elements, text and reads-per-boundary all fixed and moving *only*
the number of unique live query keys, a Reagent boundary's exclusive retained
heap runs **1,643 B at fan-out 1 down to 842 B at fan-out 8**, and a UIx
boundary **3,235 B down to 1,813 B**. Nothing else in either page changed.

**The `rf2-2rtt6.4` grid arm reproduces to 0.3%.** Its published Reagent row is
947 B/boundary; run here unchanged, as a rung of this sweep, it reads
**950 B [939–958]** — and this sweep can now say *why* that is not the same
quantity as the ladder's 1,562 B, because it holds both regimes in one table.
The instrument counts **Q off the frame's own sub-cache on every mount** and
refuses a mount whose count is not the one the plan asked for, so the ruling's
central mechanical claim — four roots rendering one frame's query vectors hold
**300** reactions, not 1,200 — stopped being a reading of the source and became
a number 252 mounts had to answer.

**The ruling's prediction holds, once the spine landings that postdate the
ladder are accounted for.** Reagent's fan-out-1 rung lands within **1.8–5.2%**
of the predicted 1,562 B. UIx's misses by **15–16%** — and `rf2-2rtt6.13`, which
removed a retained disposed reaction worth 769 B per unique key and landed
*after* the ladder was published, accounts for it: add that term back and the
two substrates sit **+5.2% and +5.2%** above the ladder, the same common-mode
offset between two instruments. **Part 1 is not reopened.**
[§3](#3-the-prediction-adjudicated) shows the working.

**The additive model *fits* UIx with three terms and Reagent with four — and
"fits" is the strongest word this sweep has earned.** The per-unique-key axis is
a straight line on both substrates in every round of both runs (r²
0.9973–0.9999), so a per-unique-key price exists and is priced twice over from
genuinely disjoint rung sets: the R = 1 family's slope, and the R = 2 pair. The
*per-edge* term is another matter: on UIx the two routes to it land within 3.8%
of each other, and on Reagent they disagree by 160 B — **more than twice the
smaller of the two** — which is a real finding about where Reagent spends, and
the page refuses to publish a per-edge byte count for it. What this page does
**not** claim is an out-of-sample validation. `R2Q2B` is an input to the
model's admissibility gate, not a rung withheld from it, and the residual it
returns is identically the other two checks recombined —
[§4](#4-the-additive-model) writes that identity out.

---

## Provenance

**Instrument.** Whole-tree anchor `cd99cded8227816848499b989a9981f194ac656e` on
`worker/fanout-5prok` — authored, and the branch was rebase-merged, so that SHA
is on no branch and **will not resolve in a fresh clone**. It landed on main as
**`cca5c7ea84`** (same patch — identical `git patch-id --stable`), with every
blob it contributed unchanged; that is the SHA to check out. The landed tree
sits on a later base, so it carries the change rather than the whole measured
tree, and the content checks are beside it:

| file | blob |
|---|---|
| `p0_heap.cljs` | `29a5d1087c2a78d3bc025657d414af2131d358fe` |
| `p0_run.cjs` | `c8c1756dde30a49042c197d6582702efb995d0c9` |
| `p0_reagent.cljs` | `49633402a26d17188987746b2f8f5f0e42213a27` |
| `p0_uix.cljs` | `d19b07697ecf11ce5640bf12f1c5438f6e5eb1da` |
| `p0_fixture.cljc` | `867ad5838ab64ac6aa7afbf8317d8fb305f53619` |

All five live under `implementation/core/test/re_frame/bench/`. If the SHA does
not resolve, a rebase moved it and the blobs are what to trust:

```bash
P=implementation/core/test/re_frame/bench/p0_heap.cljs
git log --oneline --all -- $P
git rev-parse <candidate>:$P   # must print 29a5d1087c2a78d3bc025657d414af2131d358fe
```

**The spine measured.** Both of today's spine landings are ancestors of the
anchor. **One of them is known to move retained heap**, and it is the reason this
page's UIx absolutes are **not** comparable to any P0 heap figure published
before it:

| landing | commit | what it did | retained heap |
|---|---|---|---|
| `rf2-2rtt6.13` (PR #7304) | `9df5094816` | stopped retaining the disposed render-phase reaction — **−769 B / −23.0 objects per unique key** on UIx | **moves it** — measured there, and priced again in [§3](#3-the-prediction-adjudicated) |
| `rf2-2rtt6.25` (PR #7305) | `f784ab0adb` | hook-scoped provisional hand-off | **no established delta** — see below |

**`rf2-2rtt6.25` is named here as a tree stamp, not as a cause.** Under a forced
synchronous commit — which is what this harness does, mounting every arm inside
`react-dom/flushSync` — the hand-off lets a cold read build one reaction rather
than two. The audit of PR #7305 then drove the shipped public path (bare
`createRoot().render`, no `act`, no `flushSync`) and measured **2N** body
constructions at N = 1, 300, 3,000 and 10,000: the reaper's macrotask wins before
React's passive subscribe, so the provisional is disposed and the commit
rebuilds. **No durable retained-heap delta from `.25` is established**, this page
attributes none to it, and every correction below is `.13`'s alone. The public
schedule is open under `rf2-2rtt6.25`.

**Versions.** Reagent **2.0.1**, UIx **1.4.4**, React **19.2.0**, node
**24.13.0**, headless **Chromium 147.0.7727.15** via Playwright 1.59.1. Windows
11, single developer workstation; no other agent or bench process was running
(checked before the published rungs). Every arm is an `:advanced` ClojureScript
bundle with `goog.DEBUG false`, riding the `:hicasso-bench` build id whose cache
entry is cleared before every build (`lane_cache.cjs`, `rf2-2rtt6.20`), so both
runs started cold.

**Reproduce** — foreground, to completion, and the exit code is the verdict:

```bash
P0_ROOTS=4 P0_FAN_ROUNDS=6 node implementation/core/test/re_frame/bench/p0_run.cjs --only fanout
P0_ROOTS=1 P0_FAN_ROUNDS=6 node implementation/core/test/re_frame/bench/p0_run.cjs --only fanout
```

Both **exited 0**. Exit 1 is an unverified mount or a failed positive control;
exit 2 is the arm-order guard refusing, and the repair for that is to the arm,
never to the guard.

---

## The prediction, on record, before the run

Committed as `cd99cded82` — this page's first revision — **before** the
published rungs were measured, so this page is not a record of only the
predictions that came true. `git show cd99cded82` is the receipt.
[§3](#3-the-prediction-adjudicated) marks each one.

- **P1** *(the ruling's own falsifiable claim)* — the fan-out-1 rung lands on
  the ladder's distinct-query family: Reagent ~1,562 B, UIx ~3,807 B at one
  read, ratio 2.25–2.44×. *A material miss reopens Part 1.*
- **P1′** — but the ladder predates `.13`/`.25`. The dead reaction was built
  once per **cache miss**, so it belongs to the per-unique-key term: corrected,
  Reagent ~1,562 B (unchanged, the fix is UIx-side), UIx ~**3,038 B**
  (3,807 − 769), and the `.4` grid's UIx row ~**1,942 B** (2,134 − 769/4).
- **P2** — Reagent shell ~428 B, key ~866 B (`rf2-2rtt6.12`'s ablation), edge
  ~270 B by difference. UIx shell ~208 B, key ~1,684 B, edge ~1,146 B.
- **P3** — the model may not be three terms. The ladder's own Reagent curve fits
  `397 + 943·R` at r² 0.9988 while its R=0 shell reads 428 B and its R=1 rung
  1,562 B: a first read costing **224 B more than the line**, invisible in an r²
  taken against a 19 KB range. Predicted **M4 on Reagent, M3 on UIx**.
- **P4** — the controls: the dense array reads 4,700,000 B; the adjudicator's
  self-test passes 6 of 6 and its synthetic quadratic page clears the r² floor
  at ≈0.997 and is refused only out of sample; Q reads exactly 300 on the
  published `grid/*` arms at every ROOTS setting.

---

## 1. The design, and what is held fixed

One page, one frame, one subscription cache, `K` React roots kept standing. A
boundary's query index is `(n·R + j) mod Q`, where `n` is its index in the
**global** numbering across all roots. That single rule sets the whole witness
stamp from three numbers the driver chooses:

- **B** — boundaries, `K × 300`. Read back off the DOM on every mount.
- **E** — boundary-query edges, `B × R`.
- **Q** — unique live query keys, the modulus. **Counted**, on every mount, off
  the frame's `:sub-cache`; a count that is not the one the plan asked for is an
  unverified mount and the driver exits 1.

Every rung renders **identical DOM** — one `span.cell` per boundary whose text
is `0`, because every seeded cell is `0` and a two-read cell shows their sum —
so a rung differs from its neighbours in cache cardinality and in nothing a
floor subtraction could confuse for it. The floor is the same plain-React
`createElement` walk the published grid rows are differenced against: same
elements, same classes, same text, **no component per boundary and no
reactivity at all**. Every `y` below is `arm − floor`, within one segment of one
round.

| rung | reads/boundary | Q | what it is for |
|---|---:|---:|---|
| `R0` | 0 | 0 | the boundary **shell** |
| `R1Q1` | 1 | B | fan-out 1 — the distinct-query worst case |
| `R1Q2` | 1 | B/2 | fan-out 2 |
| `R1Q4` | 1 | B/4 | fan-out 4 — at ROOTS=4, exactly `rf2-2rtt6.4`'s regime |
| `R1Q8` | 1 | B/8 | fan-out 8 |
| `R2Q2B` | 2 | 2B | **prices no term — and gates the model** ([§4](#4-the-additive-model)) |
| `R2QB2` | 2 | B/2 | identifies the per-edge term against `R1Q2` |
| `anchor` | 1 | 300 | the published `grid/<substrate>` arm, **unchanged** |

**Method** is the published heap row's, unchanged and shared with it through one
measurement engine: retention never allocation (mount and *keep*, force a full
collection, read, release, collect, read again); CDP
`Runtime.getHeapUsage().usedSize` after 3× `HeapProfiler.collectGarbage`, with
in-page `performance.memory` alongside and *not* banked as independent; one
unread warm-up pass over every arm before round 1; two adapter segments per
round with the order alternating; the slot order rotating **and reflecting** so
every arm is measured after at least two distinct predecessors.

**Verification: 0 unverified of 126 mounts on each run**, on both read-backs.

**The in-situ positive control**, predicted before anything was measured: a
dense JS array of 587,500 doubles, which V8 stores as unboxed 8-byte slots —
**4,700,000 B, known in advance**. It rides every round.

| run | predicted | measured | error |
|---|---:|---:|---:|
| ROOTS=4 | 4,700,000 B | 4,700,330 B [4,699,074–4,700,974] | **+0.007%** |
| ROOTS=1 | 4,700,000 B | 4,700,337 B [4,699,074–4,700,974] | **+0.007%** |

**The arm-order guard returned `reportable` on both runs.** Its own self-test —
twelve checks replayed from recorded live fixtures — passed before either run
measured anything.

---

## 2. The rows

`y` is exclusive retained bytes per boundary (arm − floor), six rounds, reader A.

### ROOTS = 4 · B = 1,200

| rung | E/B | Q | E/Q | Reagent | UIx | UIx/Reagent |
|---|---:|---:|---:|---:|---:|---:|
| floor *(absolute)* | 0 | 0 | — | 253 [248–260] | 254 [251–257] | — |
| `R0` shell | 0 | 0 | — | **501** [496–506] | **221** [218–224] | 0.441× |
| `R1Q1` | 1 | 1,200 | **1** | **1,643** [1,634–1,654] | **3,235** [3,221–3,257] | 1.969× |
| `R1Q2` | 1 | 600 | **2** | **1,222** [1,212–1,239] | **2,409** [2,405–2,413] | 1.971× |
| `R1Q4` | 1 | 300 | **4** | **954** [941–964] | **2,009** [2,000–2,015] | 2.106× |
| `R1Q8` | 1 | 150 | **8** | **842** [831–867] | **1,813** [1,804–1,824] | 2.153× |
| `R2Q2B` | 2 | 2,400 | 1 | 2,541 [2,534–2,561] | 6,122 [6,101–6,135] | 2.409× |
| `R2QB2` | 2 | 600 | 4 | 1,305 [1,293–1,315] | 3,752 [3,740–3,773] | 2.875× |
| **`anchor`** (`.4`'s grid arm) | 1 | 300 | **4** | **950** [939–958] | **1,996** [1,991–2,003] | 2.101× |

### ROOTS = 1 · B = 300

| rung | E/B | Q | E/Q | Reagent | UIx | UIx/Reagent |
|---|---:|---:|---:|---:|---:|---:|
| floor *(absolute)* | 0 | 0 | — | 270 [259–278] | 270 [255–285] | — |
| `R0` shell | 0 | 0 | — | **524** [510–545] | **231** [215–255] | 0.441× |
| `R1Q1` | 1 | 300 | **1** | **1,590** [1,549–1,625] | **3,185** [3,158–3,256] | 2.003× |
| `R1Q2` | 1 | 150 | **2** | **1,198** [1,188–1,214] | **2,408** [2,377–2,457] | 2.010× |
| `R1Q4` | 1 | 75 | **4** | **977** [965–991] | **2,022** [1,998–2,050] | 2.070× |
| `R1Q8` | 1 | 38 | **7.89** | **861** [840–886] | **1,819** [1,810–1,836] | 2.113× |
| `R2Q2B` | 2 | 600 | 1 | 2,658 [2,609–2,727] | 6,155 [6,134–6,181] | 2.316× |
| `R2QB2` | 2 | 150 | 4 | 1,278 [1,255–1,331] | 3,753 [3,718–3,808] | 2.937× |
| **`anchor`** (`.4`'s grid arm) | 1 | 300 | **1** | **1,563** [1,542–1,596] | **3,183** [3,165–3,198] | 2.036× |

Three things fall straight out of the pair.

**Fan-out is worth 1.6–1.9×, on one instrument.** From E/Q = 1 to E/Q = 8, with
B, E, elements and text all pinned: Reagent 1,643 → 842 B (**1.95×**), UIx
3,235 → 1,813 B (**1.78×**). From E/Q 1 to 4 — the two published families'
regimes — Reagent **1.72×** and UIx **1.61×**, against the 1.65× / 1.79× the
ruling computed across two instruments. *The incomparability the ruling priced
is real, and this is the direct measurement of it.*

**Per-boundary cost does not depend on B.** Every rung agrees between B = 300
and B = 1,200 to within **4.6% on both substrates**, over a fourfold change in
B. On UIx the *only* rung above 1.6% is the R=0 shell (221 against 231 B, where
ten bytes is 4.5%) — every rung above 1 KB agrees to **1.6% or better**, five of
the six to under 0.7%. On Reagent the spread is 2.0–4.6% throughout. Whatever a
page pays once is not showing up in these denominators.

**The `:p0/fan` substitution is not doing any work.** At identical B/E/Q the fan
rung and the published `grid` arm — different query ids, otherwise the same page
— read within **0.4% and 0.7%** (ROOTS=4, fan-out 4) and **1.7% and 0.06%**
(ROOTS=1, fan-out 1).

### A refinement the ruling will want: the ratio is *approximately* regime-free

Part 1 keeps `.4`'s ratios as "the cross-regime check that survives regimes".
Measured directly here, the UIx/Reagent ratio at one read moves from **1.969×**
at fan-out 1 to **2.153×** at fan-out 8 — a **9.3% drift** across the axis, in
one run, on one instrument, and reproduced at ROOTS=1 (2.003× → 2.113×, 5.5%).
The ratio is far more portable than the absolutes, which was the point; it is
not *constant*, and a cross-regime check quoted to three decimal places is
quoting drift.

---

## 3. The prediction, adjudicated

### P1 — the fan-out-1 rung against the ladder

| | ladder, R=1 | sweep, ROOTS=4 | ROOTS=1 | miss |
|---|---:|---:|---:|---:|
| Reagent | 1,562 B | 1,643 B | 1,590 B | **+5.2% / +1.8%** |
| UIx | 3,807 B | 3,235 B | 3,185 B | **−15.0% / −16.3%** |

Reagent lands. **UIx misses materially, and the miss is fully accounted for by a
landing that postdates the ladder.** `rf2-2rtt6.13` removed a second, disposed,
unreachable reaction worth 769 B — built once per *cache miss*, so once per
unique key, so 769 B per boundary at fan-out 1. Add it back:

| | ladder | sweep + 769 B | miss |
|---|---:|---:|---:|
| Reagent (no correction — the fix is UIx-side) | 1,562 B | 1,643 B | **+5.19%** |
| UIx | 3,807 B | 4,004 B | **+5.17%** |

Two substrates, two independent numbers, the same **+5.2%** offset between this
instrument and the ladder's — which is what a common-mode difference between two
harnesses with different boundary components looks like, and which the ratio
cancels. **P1 is confirmed; Part 1 of the ruling is not reopened.** P1′, the
corrected prediction, was 3,038 B against a measured 3,235 B (+6.5%) — closer
than P1 by a factor of two, and the residual is the same common-mode offset.

The ratio half of P1 goes the same way: predicted 2.25–2.44×, measured
**1.969×** — outside the band, and outside it *because* of `.13`. Adding the
term back recovers **2.437×** (ROOTS=4) and **2.487×** (ROOTS=1), inside the
band at its top. **The measured drop from ~2.44× to ~1.97× is the retained-heap
benefit of `rf2-2rtt6.13`, priced on the fan-out-1 witness.**

### P1′ at fan-out 4 — the `.4` grid arm, re-run unchanged

| | `.4` as published | sweep `anchor` | miss | after adding back 769/4 |
|---|---:|---:|---:|---:|
| Reagent | 947 B | **950 B** | **+0.3%** | — |
| UIx | 2,134 B | **1,996 B** | **−6.5%** | 2,188 B (+2.5%) |

The Reagent arm reproduces to three parts in a thousand across two months, two
trees and a re-run. The UIx arm moves down by what `.13` predicts, to within
2.5%.

**And the published heap ROW — not a rung of this sweep, the `--only heap` row
itself, untouched — says the same thing.** Run on this tree at the same
`P0_ROOTS=4` as a regression check that this PR did not disturb it (exit 0,
guard reportable, 0 unverified of 56 mounts):

| | published `.4` | this tree |
|---|---:|---:|
| `grid` Reagent exclusive | 947 B | 1,198 − 251 = **947 B** |
| `grid` UIx exclusive | 2,134 B | 2,236 − 244 = **1,992 B** |
| `grid` exclusive ratio | 2.254× | **2.104×** [2.056–2.138] |

The Reagent denominator is the published figure **to the byte**. The UIx
numerator has moved, by `.13`, and the ratio with it — a second statement of the
same thing, from a separate run of the very arm that published the original.
Same instrument, different arm, different run: corroboration within one
harness, not a second harness. Those mounts also passed the new **Q**
read-back, so the `.4` grid arm's E/Q = 4 is now checked every time that row is
taken and not only when this sweep runs.

### P2 and P3 — see [§4](#4-the-additive-model) and [§5](#5-the-terms)

P3 called it: **M4 on Reagent, M3 on UIx**, and the mechanism is the one the
ladder's 224 B first-read excess pointed at.

### P4 — the controls

All four held. The dense array read within 0.007% of its predicted 4,700,000 B
on both runs. The adjudicator's self-test passed 6 of 6, and its synthetic
quadratic page returned r² **0.99696** — hand-computed at 0.99694 before it ran
— clearing the 0.98 floor and refused only out of sample. **Q read exactly 300
on the published `grid/*` arms at both ROOTS settings**, which at ROOTS=4 with
B=1,200 is E/Q = 4 exactly: the ruling's mechanical claim, measured.

---

## 4. The additive model

Two models were adjudicated, because assuming the ruling's shape and reporting
its terms would have been assuming the answer:

```
M3    y = shell +              (E/B)·edge + (Q/B)·key      the ruling's shape
M4    y = shell + [E>0]·step + (E/B)·edge + (Q/B)·key
```

**Each term comes from one contrast, not from one fit.**

- `shell` — the `R0` rung, alone.
- `key` — the **slope** of the `R1*` family in Q/B. E/B is pinned at 1 across
  those four rungs; nothing but the number of unique keys moves.
- `edge` — `R2QB2 − R1Q2`: two rungs at the **same Q**, differing by one read
  per boundary. One extra edge each, no extra keys, nothing else.
- `step` — what is left of the `R1*` intercept once `shell` and `edge` are taken
  out. M3 says this is zero.

`R2Q2B` prices none of those four terms. **It is not, however, held out of the
adjudication** — and the first revision of this page said it was. Correcting
that is what the rest of this section does.

**The model criterion, stated before the run and not moved after it.** Written
into `p0-heap/additive-criterion`, applied by `p0-heap/additive-fit`, and
adjudicated per round as well as on the round means:

| check | threshold |
|---|---|
| the `R1*` family is a line in Q/B | r² ≥ **0.98** |
| the key term from the R=2 **pair** agrees with the R=1 slope | within **15%** |
| M3 — edge priced from the intercept equals edge priced from the contrast | `\|step\|` ≤ **10%** of the fan-out-1 boundary |
| the model reproduces the `R2Q2B` rung | within **10%** |

If the first two fail, no component price is quotable at all. Otherwise the model
that clears the fourth row carries the prices, and if neither does, the page
publishes the rows and refuses the prices.

### `R2Q2B` has one role, and it is not the one advertised

The second row of that table is the one that matters. `key-2` is
`(R2Q2B − R2QB2) / Δ(Q/B)`; `key-gap` compares it to the R = 1 slope; `key-ok?`
is that comparison thresholded — and `key-ok?` is a **mandatory** input to model
selection, because `(not (and linear? key-ok?))` returns *no model and no
prices*. `R2Q2B`'s measured value therefore decides whether any price is
quotable at all. That is its one role: **an admissibility gate, not a held-out
witness.**

The fourth row is then not a fifth piece of evidence but the second and third
rows recombined. Write `dx` for the gap in `Q/B` between the two R = 2 rungs
(2.0 − 0.5 = **1.5** here) and `ρ` for the R = 1 regression's residual at
`R1Q2` — its fitted value minus its measured one. The algebra is exact, not
approximate:

```
M4 predicted − R2Q2B measured  =         ρ  +  dx·(key − key₂)
M3 predicted − R2Q2B measured  =  step + ρ  +  dx·(key − key₂)
```

`key − key₂` is precisely what `key-gap` thresholds; `step` is precisely what
the M3 row thresholds. On the ROOTS=4 Reagent arm `step` = 150 B, `ρ` = −28 B,
and `dx·(key − key₂)` = 1.5 × (919 − 824) = +143 B — so 150 − 28 + 143 =
**+265 B**, against a measured M3 miss of 2,806 − 2,541 = **+265 B**; drop
`step` and M4's is **+115 B** against a measured **+115 B**. The "held-out"
residual carries no information the two checks above it do not already carry.

**So the model was not validated out of sample. It was fitted, and checked for
self-consistency.** Everything below that says the model *reproduces* `R2Q2B`
is true and worth reporting — a model whose recombined checks land within 2% is
in better shape than one whose land at 10% — but it is a consistency reading,
and this page no longer offers it as independent evidence. What that costs the
downstream budget rows is stated in [§6](#6-what-this-unblocks); what would buy
the independence back is in [§7](#7-open).

**The adjudicator's own positive control.** Three synthetic pages, built by
arithmetic and never measured, run before the sweep does: an exact M3 page that
must be priced back to its three terms and reproduce `R2Q2B` *to the byte*; an M4
page carrying a 400 B step, whose R=1 family is a **perfect** line
(r² = 1.000000) and which M3 still misses by 12.90%; and a page with a quadratic
key term that both models must refuse. **That middle page is the whole argument
for the R = 2 rungs existing** — a page that is not M3 can fit the R = 1 family
perfectly, and nothing inside that family can tell. On it the identity above
reduces to `miss = step` exactly, which is the point: the R = 2 rungs are what
make `step` observable at all. **`R2QB2` is the rung that supplies it.** `R2Q2B`
supplies the key cross-check from a rung set disjoint from the R = 1 family —
genuinely useful, and not a held-out test.

### The verdicts

| | model | r² | `R2Q2B` reproduced | rounds agreeing |
|---|---|---:|---|---:|
| Reagent, ROOTS=4 | **M4** | 0.99732 | M3 +10.44% *(fail)* · M4 +4.55% | 5 of 6 |
| Reagent, ROOTS=1 | **M4** | 0.99862 | M3 +0.51% · M4 −5.63% | 2 of 6 |
| UIx, ROOTS=4 | **M3** | 0.99989 | M3 +1.95% · M4 +1.34% | **6 of 6** |
| UIx, ROOTS=1 | **M3** | 0.99994 | M3 −0.20% · M4 −1.04% | **6 of 6** |

**UIx is M3, unambiguously.** Both runs, every round, r² ≥ 0.9999; the two routes
to the edge term within 3.8% of each other; `R2Q2B` reproduced within 2%. Those
two routes are **not data-independent** — the intercept is fitted through the
whole R = 1 family and the contrast subtracts `R1Q2`, a member of it, from
`R2QB2` — so 3.8% is internal agreement, not corroboration by a second
experiment. What they jointly support is narrower and still worth having: UIx's
non-per-key cost of reading is 1,327–1,396 B however it is sliced, and its `step`
is 38–52 B, so the model's freedom to move bytes between `edge` and `step` is
bounded at about **50 B** on UIx.

**Reagent reaches M4 in both runs and does not reach it stably.** Look at what
actually happens: at ROOTS=4 the step (150 B) sits *inside* its 165 B band while
M3 misses `R2Q2B` by 10.44% against a 10% threshold; at ROOTS=1 the step (163 B)
sits *outside* its 160 B band while M3 reproduces `R2Q2B` to **0.51%** — better
than M4's −5.63%. Both runs land on M4 by the rule, by different failing checks,
and the per-round verdicts flip (M3, M4, refused, M3, refused, M4).

**That instability is the result, not noise to be averaged away.** Reagent's
non-per-key cost of reading is ~234–244 B at one read, and this sweep can say
that number confidently. What it cannot say is how to split it: the two routes to
`edge` give **84 B** and **234 B** — a disagreement larger than twice the smaller
term. On UIx the same disagreement is 38 B against a 1,344 B term.

**The Reagent per-edge price is refused — and the refusal is this page's
judgement, not the precommitted rule's.** The first revision of this page ran the
two together; they are different things and the difference is worth spelling out.

- **What the precommitted criterion did.** It selected **M4** on both Reagent
  runs, and `additive-fit` reports `:edge` as the *contrast* value under M4. The
  rule as written would therefore have handed out **84 B / 80 B** as Reagent's
  per-edge price. No "twice the smaller term" threshold appears anywhere in
  `additive-criterion`, and no threshold in it was moved after the run.
- **What the criterion did put on the record, before any prose was written.** The
  two runs reach M4 by *different* failing checks; the step sits inside its band
  in one run and outside it in the other; the per-round verdicts flip 5-of-6 and
  2-of-6. That is the precommitted machinery reporting an unstable
  identification, and it is in the table above.
- **What this page then decided.** That an 84 B number carrying a 234 B
  alternative is not a number to freeze into a budget, and that the honest form
  is the ~234–244 B total with the split marked unresolved. That is a
  **conservative post-run editorial call**, made in the safe direction, and it is
  offered as one — not as the output of a rule written down in advance.

---

## 5. The terms

Ranges are across the six rounds of each run; the two runs are reported
separately and never as a mean, because the pair exists to show the figures do
not move.

### Reagent on re-frame2 subs

| term | ROOTS=4 | ROOTS=1 | cross-check |
|---|---:|---:|---|
| **shell** (R=0) | **501** [496–506] | **524** [510–545] | ladder R=0 428 B *(different boundary component — see below)* |
| **per-unique-key** (R=1 slope) | **919** [901–939] | **830** [785–864] | from the R=2 pair: **823** / **919** · `rf2-2rtt6.12` ablation **866** |
| per-edge, from the contrast | 84 [76–98] | 80 [59–117] | — |
| per-edge, from the intercept | 234 | 244 | **the two disagree by 160 B — refused** |
| step (M4's fourth term) | 150 [123–173] | 163 [122–187] | ladder's first-read excess **224 B** |

### UIx on re-frame2 subs

| term | ROOTS=4 | ROOTS=1 | cross-check |
|---|---:|---:|---|
| **shell** (R=0) | **221** [218–224] | **231** [215–255] | ladder R=0 208 B |
| **per-unique-key** (R=1 slope) | **1,628** [1,613–1,659] | **1,559** [1,525–1,658] | from the R=2 pair: **1,580** / **1,601** · `.12` ablation less the removed term **1,684** |
| **per-edge**, from the contrast | **1,344** [1,327–1,365] | **1,345** [1,339–1,352] | from the intercept 1,382 / 1,396 — **3.8% apart** |
| step | 38 [24–54] | 52 [26–72] | inside its band in every round |

**The subscription term is priced four times inside this sweep and agrees with
two readings taken outside it.** Inside: two runs × two disjoint rung sets (the
R = 1 slope, and the R = 2 pair) — same instrument, so four estimates rather than
four instruments. Outside: `rf2-2rtt6.12`'s direct ablation, a different
experiment on a different page, and the ruling's cross-family algebra over the
two published families. Reagent — this sweep's four estimates span **823–939 B**,
`.12`'s ablation reads **866 B**, dead centre, and the algebra solved 820–824 B,
at the bottom of the range. UIx — this sweep reads **1,525–1,659 B** against
`.12`'s 1,684 B once the 769 B `.13` removed is taken out, 1.5% above the top of
the range. The algebra the ruling refused to freeze budgets from turns out to
have been right; it was right for a reason it could not demonstrate, and this run
demonstrates it. **This is the one term on the page with genuinely external
corroboration**, and it is why [§6](#6-what-this-unblocks) treats it differently
from the rest.

**Where the two substrates actually spend.** UIx wins the shell by 2.27×
(221 B against 501 B) and loses everything else: its per-edge term is
**1,344 B against Reagent's 84–234 B**, which is the hook spine — one
`useSyncExternalStore` slot, its store object, its subscribe closure — paid per
*attachment*, and its per-unique-key term is 1.8× Reagent's. That is the same
story `uix-spine-per-read-decomposition.md` tells by ablation, arrived at from
the opposite direction.

**A caveat on the shell, and it is this page's own.** The `R0` rung here reads
501–524 B on Reagent against the ladder's 428 B, because this sweep's boundary
component carries two props (its DOM index and its global index) where the
ladder's carries one, and both are held uniform across every rung so that the
shell rung is not measuring one fewer prop slot than the rungs it anchors. The
**shell is component-shape sensitive at the ~75 B level**, and a shell budget
should therefore name the boundary shape it is stated for. It clears the 1 KB
paper-fail line on both substrates either way; on Reagent it sits at the top of
the 0.4–0.5 KB target band rather than inside it.

---

## 6. What this unblocks

Part 3 of the ruling gated three component budget rows on this sweep. The sweep
answers for two of them on both substrates, and refuses one.

| row | Reagent | UIx | what it rests on |
|---|---|---|---|
| **shell** (R=0 boundary) | **UNBLOCKED** — 501–524 B, *stated for a named boundary shape* | **UNBLOCKED** — 221–231 B | a **measured rung**, `R0`, read directly. No model, no fit |
| **per-unique-subscription-key** | **UNBLOCKED** — **~866 B** [823–939] | **UNBLOCKED** — **~1,590 B** [1,525–1,659] | a **direct slope** at pinned E/B, priced twice from disjoint rungs, and corroborated **outside this sweep** by `.12`'s ablation |
| **per-edge** (consumer attachment) | **REFUSED** — the two routes differ by more than twice the smaller term | **UNBLOCKED, PROVISIONAL** — **~1,345 B** [1,327–1,396] | a **direct two-rung contrast** at fixed Q; the *label* is model-dependent within ~50 B |

**Read that fourth column before quoting the third.** With the held-out claim
withdrawn, the rows no longer rest on a model that survived an out-of-sample
test — so each one is stated for what it individually is:

- **The shell is a measurement.** `R0` is a rung that was mounted and read. The
  additive model plays no part in it, and nothing in this correction touches it
  beyond the component-shape caveat it already carried.
- **The per-unique-key term is the sweep's strongest number.** It is the slope of
  four rungs whose E/B is pinned, it is r² ≥ 0.9973 in every round of both runs,
  it is re-priced from the disjoint R = 2 pair, and `rf2-2rtt6.12`'s ablation —
  which is not this instrument and not this run — lands inside the range on
  Reagent and 1.5% outside it on UIx. It stands.
- **The UIx per-edge term is a direct contrast, and its *name* is provisional.**
  `R2QB2 − R1Q2` measures what one more edge costs at fixed Q, and that
  measurement is 1,344/1,345 B whichever model is selected. What the model
  decides is only whether a further 38–52 B is called `edge` or `step`. Quote
  ~1,345 B as the cost of an attachment and treat the last ~50 B as unallocated,
  rather than reading the number as a validated decomposition.
- **The Reagent per-edge row stays refused**, on the conservative post-run
  judgement set out in [§4](#4-the-additive-model) — which is a judgement, not
  the precommitted rule's output, and is labelled that way there.

And it adds one row Part 3 did not name:

- **a per-subscribing-boundary step on Reagent, ~150–163 B**, which is neither
  shell nor per-edge. Its existence is established (the ladder's 224 B first-read
  excess, this sweep's M4 verdict in both runs); its exact split against the
  per-edge term is not. The honest form for validation.md is a **single
  first-read term of ~234–244 B for Reagent** with the step/edge split marked
  unresolved, rather than two numbers of which one is wrong by 3×.

The edit to `validation.md` is not made from this seat — that file is normative.
It is filed as its own bead, carrying this page's numbers and this section's
refusals.

**What the sweep does *not* touch.** The clock axis, the converged witness set,
the red-zone thresholds, the kill criteria, and the `.4`/`.5` family standings.
Part 1 stands as ruled, now with its prediction confirmed and its mechanism
measured rather than inferred.

---

## 7. Open

- **An independent adjudication of the additive model has not been run, and this
  is what one would take.** The defect is structural, not arithmetic: every rung
  this sweep measured is consumed by identification or by admissibility, so no
  rearrangement of the existing data recovers a held-out test. It needs **one
  more rung, precommitted as untouchable, and a criterion that never reads it**.
  Concretely: (1) add a rung outside both the R = 1 family and the R = 2 pair —
  `R3` at fixed Q is the obvious one, since the Reagent step/edge item below
  wants exactly that rung anyway; (2) drop `key-ok?`'s dependence on it, or price
  the key cross-check from a different pair, so the validation rung feeds *no*
  admissibility check; (3) state the prediction for it before the run, as this
  page did for P1–P4; (4) re-adjudicate the component rows against it. Until that
  runs, [§6](#6-what-this-unblocks)'s fourth column is the warrant. **New
  measurement is deferred by operator direction (Mike, 2026-07-31)** — the
  tournament arms have the box — so this is a recorded requirement, not a queued
  task.
- **The instrument's own comments still say `R2Q2B` is held out.** The blobs in
  [Provenance](#provenance) are pinned to the revision that produced these rows
  and are deliberately not edited, so `p0_heap.cljs`'s "Falsification — one rung
  is held out" block and `p0_run.cjs`'s banner remain as they were when the
  numbers were taken. This page supersedes them. Whichever revision next touches
  the adjudicator should correct the wording there and re-stamp the blobs.
- **The 9.3% ratio drift across fan-out** ([§2](#2-the-rows)) refines Part 1's
  "the ratio survives regimes". It survives approximately. Whether a donor gate
  should carry a band rather than a point is the operator's call, not this
  page's.
- **The Reagent step/edge split** wants a rung this sweep does not have: R=3 at
  fixed Q would over-determine the pair and separate them. It is one more rung
  on the same instrument and would take one more run.
- **The `+5.2%` common-mode offset** between this instrument and the reads
  ladder is measured twice ([§3](#3-the-prediction-adjudicated)) and unexplained.
  The most likely cause is the boundary component's prop count, the same ~75 B
  the shell caveat names; nothing here establishes that.
