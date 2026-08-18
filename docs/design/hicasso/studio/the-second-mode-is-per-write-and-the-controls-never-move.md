# The second mode is per-write, and the controls never move

Seat: RE-ANALYSIS RECORD, EP-0038. Bead `rf2-77gz8`, which asks what mechanism
puts the floor arm's `rise/W` into either of two levels 3,792 B apart at one
revision. Written 2026-08-18 on `worker/bimodal-77gz8`, written off `858452ce35`
and rebased onto `7902167197`. The three instrument blobs below are byte-identical
at both, so the derivations here are against one instrument.

**No allocation window was taken for this page, and no instrument was built.**
Nothing here is a new measurement. Every figure below is re-derived from the
eight datasets `rf2-9jrhi` committed under
`implementation/hicasso/test/re_frame/bench/hicasso/data/alloc-9jrhi/`, or read
out of the instrument's source at the blob the runs were taken on. The
instrument was not run, not configured, and not edited; no rig file was touched.

Instrument revision, beside every re-derivation. The page-side half is
byte-identical to the blobs `rf2-9jrhi` published, so the source read here is
the source those runs executed:

| File | Blob at this page's base | Same object as the run window? |
|---|---|---|
| `core/test/re_frame/bench/p0_heap.cljs` | `2d922d31f86bcafb251c7c8d5b9cab458e31df28` | yes |
| `core/test/re_frame/bench/p0_arms.cljs` | `beced24315f740eede28cf5f32f855ff91bbd854` | yes |
| `core/test/re_frame/bench/p0_run.cjs` | `9c993e96b36f4878d03912743154e938ffff896e` | **no** — the runs used `e88d2be45efd59d023a9d23da9e4ff1f9800b5c0` |

The driver has moved since the window, so **every structural claim below about
the driver was verified against driver blob
`e88d2be45efd59d023a9d23da9e4ff1f9800b5c0`**, the object the runs actually
executed, and not against the working tree.

## The answer, first

**The second mode is a PER-WRITE cost, it is confirmed by a second and
independent counter, and it is not the instrument miscounting. What it is NOT
is now settled on four of the candidates; what it IS remains a two-way split
that the committed corpus cannot decide.**

- **It is per write, not per window and not per leg.** The prime work unit is a
  single write, and it carries the same step as each of the six measured writes:
  +3,792 B on the measured legs and +3,792 / +3,808 B on the prime, on **both**
  segments. A per-window or per-leg term would not scale with the prime.
- **A second, independent counter confirms it.** `cdpBracket` is
  `Runtime.getHeapUsage()` read over CDP from **outside** the page, and on the
  same certified subset it reads **24,380 – 26,566 B** per window higher in the
  high-mode run. A window is seven writes, and 7 × 3,792 = **26,544**. Two
  counters that share no code path agree on the same quantity.
- **The controls never move — in any run, in any round.** Across all eight
  datasets, 4 revisions and 8 browser launches, the three control windows take
  exactly **one distinct value each**: `idle` = 16 B, `ctl1` = 8,064 B,
  `ctl2` = 3,264 B. Not a range — one value. The controls are interleaved with
  the arms on the same page in the same round.
- **The two runs did identical work in identical order.** `alloc-tick` is
  monotone for the life of the page, and the high and low runs' `tick0` agree at
  **every one of the eighteen rounds** — 21, 105, 133, 217, 245, … , 1001.
- **The step decomposes exactly, and the round-4 transition is UNIVERSAL.**
  Every one of the seven eighteen-round runs changes level at the round 3 → 4
  boundary. Across the other six the step runs **−43 to +158 B**; the high-mode
  run changes by **3,948 / 3,912 B**. And the arithmetic is exact: 19,100 + 3,792 = **22,892**
  and 19,540 + 3,792 = **23,332**. The high mode is the universal round-4 step
  **plus** a discrete 3,792 B rider, at the same boundary.
- **The extra bytes are transient garbage, not retained structure.** Over rounds
  4 – 17 the high run allocates 14 × 2 × 7 × 3,792 = **743,232 B** more than the
  low one, yet the two heaps close within **4,812 B** of each other.

## What the datasets establish, and the evidence for each

### It is per write

`primeLegs[0]` is the allocation of the window's single prime write; `legMedian`
is the median of its six measured writes. Both step, by the same amount, on both
segments. Estimator for the steady-state columns: **the median, over certified
windows at round index ≥ 6, of that window's `legMedian`** — the estimand
`rf2-9jrhi` declared, re-derived here and reproducing its published figures to
the byte.

| Quantity | high run (`bisect-1-a`) | low run (`bisect-6-a-…replicate2`) | step |
|---|---|---|---|
| `reagent-subs` `legMedian` | 22,892 | 19,100 | **+3,792** |
| `reagent-subs` `primeLegs[0]` | 29,708 | 25,900 | **+3,808** |
| `uix-subs` `legMedian` | 23,332 | 19,540 | **+3,792** |
| `uix-subs` `primeLegs[0]` | 30,212 | 26,404 | **+3,808** |

The 16 B between 3,792 and 3,808 is one sampler read's footprint — the constant
every `gaps` entry in every window in every dataset reads.

`primeExcess` — the first-write excess `rf2-oiy1` is about — is **unchanged**
across the mode: 6,816 against 6,800 on `reagent-subs`, 6,880 against 6,864 on
`uix-subs`. The mode moves the whole per-write baseline and leaves the
first-write premium alone.

### The controls never move

Distinct values of each control's `legMedian` over every round of every dataset:

| Dataset | rounds | `idle` | `ctl1` (d = 1000) | `ctl2` (d = 400) |
|---|---|---|---|---|
| `pilot-rounds6-head-88411ed803` | 6 | {16} | {8064} | {3264} |
| `bisect-1-a-4a1537cb71` (**high**) | 18 | {16} | {8064} | {3264} |
| `bisect-2-m-a158c40288` | 18 | {16} | {8064} | {3264} |
| `bisect-3-b-48c715f97c` | 18 | {16} | {8064} | {3264} |
| `bisect-4-p-9d20be1d00` | 18 | {16} | {8064} | {3264} |
| `bisect-5-a-4a1537cb71-replicate` | 18 | {16} | {8064} | {3264} |
| `bisect-6-a-4a1537cb71-replicate2` | 18 | {16} | {8064} | {3264} |
| `bisect-7-head-88411ed803` | 18 | {16} | {8064} | {3264} |

Fifty-four control windows in the high-mode run, and every one of them reads
what the low-mode run's read. A term that added 3,792 B to a leg on this page
would have to spare all fifty-four while hitting all thirty-six arm windows.

### The universal step at round 4

`pre` is the median `legMedian` over certified rounds 1 – 3; `post` over
certified rounds 6 – 17. The pilot ran six rounds and has no `post` subset, so
it is excluded.

| Run | `core/src` at | `reagent-subs` step | `uix-subs` step |
|---|---|---|---|
| `bisect-1-a` (**high**) | `4a1537cb71` | **+3,948** | **+3,912** |
| `bisect-2-m` | `a158c40288` | +130 | +128 |
| `bisect-3-b` | `48c715f97c` | −43 | +140 |
| `bisect-4-p` | `9d20be1d00` | +156 | +120 |
| `bisect-5-a-replicate` | `4a1537cb71` | +120 | −1,017 |
| `bisect-6-a-replicate2` | `4a1537cb71` | +129 | +120 |
| `bisect-7-head` | `88411ed803` | +158 | +148 |

The `bisect-5-a-replicate` `uix-subs` cell is the run whose positive control
FAILED, and its `pre` subset is contaminated by a 110,642 B round-0 reading; it
is printed for completeness and carries nothing.

**The driver has no round-4 behaviour.** In the alloc loop of driver blob
`e88d2be45efd59d023a9d23da9e4ff1f9800b5c0` the only round-index-dependent
statement is `round % 2 === 0`, which flips the segment order. Round 4 shares
its parity with rounds 0 and 2, and neither of those steps. So round 4 is not a
scheduled event in the rig; it is where a deterministic amount of accumulated
work has been reached, which is why it falls at the same round in every run.

### Where the transition sits

The driver interposes three warm-up windows of seven writes — `warmups` 3,
`windowWrites` 7 — between measured windows, so consecutive windows' ticks run
…→ 224, then 245 → 252. Reading the high run in tick order across the boundary:

| Tick span | Window | Reading |
|---|---|---|
| 189 – 196 | round 3, `uix-subs` | prime 26,268, six legs all 19,420 — **low** |
| 217 – 224 | round 3, `reagent-subs` | prime 25,764, legs 18,896 – 18,980 — **low** |
| — | round 4's three controls | 16 / 8,064 / 3,264 — **unchanged** |
| 245 – 252 | round 4, `reagent-subs` | steady legs 22,964 — **high** |
| 273 – 280 | round 4, `uix-subs` | prime 30,268, steady legs 23,316 — **high** |

So the transition is bracketed to **ticks 224 – 252**, it completed before the
first work unit that follows it, and it did not disturb the control path that
ran inside the bracket.

## The candidates, and which the evidence excludes

| Candidate | Verdict | What excludes it |
|---|---|---|
| An `:advanced` build artefact, or any static property of the compiled code | **EXCLUDED** | The high run read the **low** level at its own rounds 1 – 3 before stepping. No static property of a build changes within the life of one page. The three runs at `4a1537cb71` also shared one build. |
| A page-global allocation the counter attributes to the leg | **EXCLUDED** | The fifty-four control windows in the high run read byte-identically to the low run's; and a second counter outside the page (`cdpBracket`) confirms the same 3,792 B/write, so it is not the in-page counter mis-attributing. |
| A collection, or a GC generation promotion | **EXCLUDED** | Zero falling steps in the quoted certified windows; the two heaps close within 4,812 B; and a collection moves a level down, not up, and not byte-stably for fourteen rounds. |
| A growing array's doubling, or a one-time lazily-installed cache | **EXCLUDED as the carrier** | A doubling or a one-time install pays **once**. This pays 3,792 B on each of ~196 subsequent writes and is **not retained** — 743,232 B allocated against a 4,812 B difference in the closing heap. It is per-write transient garbage, not a structure. |
| The prime term | **EXCLUDED** | `primeExcess` moves 16 B across the mode while the baseline moves 3,792 B. |
| **Different work per write** — a re-entrant registration, a duplicated reaction, an extra pass | **NOT EXCLUDED** | `alloc-tick` counts **writes**, not handler invocations, so the identical schedule does not speak to it. Nothing in the corpus counts work inside a write. |
| **A V8 tier or deoptimisation transition in the compiled write path** — most specifically a loss of escape analysis, which turns elided allocations into real ones at a fixed cost per invocation | **NOT EXCLUDED** | Consistent with every observation above, and the only candidate that also explains why *all seven* runs transition at the same round under a deterministic schedule. But no dataset here records V8 tier state, so it is not established either. |

Both survivors predict a byte-exact, per-write, page-global constant that
appears at a work-count boundary. **They are not separated by anything in the
committed corpus**, and this page does not prefer one.

## The one observation that would separate them

The split is "the write path runs **more work**" against "the write path
allocates **more per unit of work**". One measurement decides it:

> Add a monotone **work counter** to the measured window — event-handler
> invocations, subscription recomputations and render calls, counted per window
> beside the existing byte counters — and read it in a high-mode and a low-mode
> window. If the counts are **identical** while the bytes differ by 3,792 per
> write, "different work" is excluded and the residue is per-invocation
> allocation, i.e. a runtime codegen effect. If the counts **differ**, "different
> work" is established and the runtime candidate is excluded.

That instrument is preferable to V8 tracing as the first move: it is page-side,
it is a **census of monotone counters** and so reads the same on a loaded box,
it needs no `--trace-deopt` and no quiet machine, and it decides the whole
remaining question rather than confirming one branch of it. V8 tracing
(`--trace-deopt`, or `%GetOptimizationStatus` under `--allow-natives-syntax`)
is the confirming second move if the counts come back identical.

**This page did not build that instrument.** Building it means editing the rig
under `implementation/core/test/re_frame/bench/`, which is outside this
window's fence, and the rig's blobs are the constancy guarantee the whole
`alloc-9jrhi` series and the `rf2-nkeba` figures are published against.

## The hazard: both modes certify, and a within-run witness would see it

The certificate asks whether a window's legs are **alike**, and in both modes
they are; it has nothing to say about which **level** the window sits at. That
is unchanged by this page. What this page adds is that the level question does
**not** need a cross-run comparison to be answerable — the mode announces
itself **within a single run**, at the round 3 → 4 boundary, in a quantity every
run already records:

| Population | Observed round-4 step | n |
|---|---|---|
| Normal | −43 to +158 B | 11 segment-runs |
| The mode | 3,912 – 3,948 B | 2 segment-runs (one run) |

The largest normal step is 158 B and the smallest mode step is 3,912 B — the
two populations do not overlap, and the gap between them is a factor of 24. A within-run level witness — compare
the pre-round-4 certified level against the post-round-4 certified level in the
same run, and refuse the run when the step exceeds a declared bound — would
have refused the high-mode run from its own data, with no new instrument and no
second run.

**Stated as a proposal and not as a gate**, because the high population is
**n = 1 run**. One run cannot set a threshold; it can only show that the two
populations are far apart in the data that exists. Arming this needs the mode
reproduced at least once more, which the instrument above would also deliver.

## What is NOT concluded

- **The mechanism is NOT identified.** Two candidates survive and this page
  prefers neither. Nothing here licenses attributing the mode to a deopt.
- **The revision-dependence question is NOT closed.** The count is unchanged
  from `rf2-9jrhi`: one high of three at `4a1537cb71`, zero of four elsewhere.
  What this page adds is that the round-4 transition itself is present at
  **every** revision measured — the runs differ in the size of that step, not in
  its presence or its timing. That reframes the open question from "can this
  revision reach the mode" to "what selects the size of the step every run
  takes", but it does not answer it.
- **No bound is claimed on how often the mode occurs.** One observation in eight
  runs is a rate estimate with no useful precision, and no null arm here bounds
  it.
- **Nothing is concluded about wall-clock.** No timing quantity was computed.
  The datasets record none, and a clock estimand would need a quiet box this
  re-analysis deliberately did not ask for.
- **No published figure is revised.** The `rf2-9jrhi` estimand was re-derived
  and reproduces to the byte; nothing in the bisect conclusion moves.

## Reproduction

Every figure on this page is re-derived from committed JSON, with no browser and
no build. From the **repository root**:

```bash
python - <<'PY'
import json, os, statistics
D = "implementation/hicasso/test/re_frame/bench/hicasso/data/alloc-9jrhi"
for f in sorted(os.listdir(D)):
    a = json.load(open(os.path.join(D, f)))["alloc"]
    for seg in ("reagent-subs|grid/floor", "uix-subs|grid/floor"):
        cert = [a["perRound"][i]["arms"][seg]
                for i in range(6, len(a["perRound"]))
                if a["perRound"][i]["arms"][seg]["certified"]]
        if not cert:
            continue
        print(f, seg,
              "legMedian", statistics.median(w["legMedian"] for w in cert),
              "prime", statistics.median(w["primeLegs"][0] for w in cert),
              "cdpBracket", statistics.median(w["cdpBracket"] for w in cert))
    ctl = {k: sorted({a["perRound"][i]["controls"][k]["legMedian"]
                      for i in range(len(a["perRound"]))})
           for k in ("idle", "ctl1", "ctl2")}
    print(f, "controls", ctl)
PY
```

The driver's round loop, which the round-4 claim rests on, is read at the blob
the runs executed rather than at the working tree:

```bash
git cat-file -p e88d2be45efd59d023a9d23da9e4ff1f9800b5c0 | grep -n 'round %'
```

## Related

- [The bisect is flat, and the floor has a second mode](the-bisect-is-flat-and-the-floor-has-a-second-mode.md)
  — `rf2-9jrhi`, the window that measured the mode and committed the eight
  datasets this page re-analyses.
- [The eight signs are one block](the-eight-signs-are-one-block.md)
  — the other re-analysis of this corpus, and the same posture: refute what the
  data refutes, and name the cause as unresolved when it is.
- [The arms' spread does not collapse (τ refused)](the-arms-spread-does-not-collapse.md)
  — `rf2-rs8q6`, the SPREAD within a window. This page is about its LEVEL, which
  is a different quantity and survives that gate.
- [The control's target is not the quantity it is read against](the-controls-target-is-not-the-quantity-it-is-read-against.md)
  — `rf2-nkeba`, whose across-time effect this mode is large enough to explain.
