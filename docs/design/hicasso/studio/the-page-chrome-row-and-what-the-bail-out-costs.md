# The page-chrome row, and what the bail-out costs

**Bead:** `rf2-2rtt6.52` · **Ruling:** [HD-028](../decisions.md#hd-028--value-equality-is-the-boundary-default),
amending [HD-006](../decisions.md#hd-006--memoization-defaults--amended-2026-08-02) ·
**Taken:** 2026-08-02 12:00 AUSEST

HD-028 makes a value-equality bail-out the boundary default, and the ruling made
that default **conditional on this measurement**: it lands if it removes the
300-row cascade *without a material mount/bulk regression and without pushing
retained heap meaningfully farther past the bar*. The cost being priced is that a
`React.memo` carrying a custom comparator takes the full `MemoComponent` path and
adds an **outer Fiber per boundary**. If the Fiber fails the gate, HD-006 stands
and the same comparator ships as an explicit opt-in.

## Reproduction

| | |
|---|---|
| Producing commit | `2158869e2b226fb01af866ed4656c3dba6c58d86` |
| Command | `node implementation/freehand/test/re_frame/bench/hicasso/chrome_run.cjs` |
| Runtime | HeadlessChrome **147.0.7727.15**, `:advanced`, **`goog.DEBUG=false`**, 24 cores, 32 GB |
| Page | the shape roster's feed — 300 card boundaries under one page boundary, the roster's own card markup |
| Rounds | 10 (3 warm-up, 7 measured) for the clock; 5 (plus a discarded warm-up per arm) for the heap; arms alternate order every round |
| Window | **frame-inclusive** — every span closes after a `requestAnimationFrame` + `setTimeout`, so it spans the frame that followed the mutation |
| Runs | two, the second on the same bundle (`--no-build`). Both exited **0**; both are quoted below |

**This is a self-comparison, and it is not a bar row.** The two arms are one
substrate with one line between them — `:memo` is `mint-view!` with the codec's
stable wrapper, `:plain` is `mint-view!` exactly as it stood before the repair,
reproduced in the bench app rather than described. Nothing here is stated as a
Reagent ratio and no `HD-` figure is minted from it; the comparative bulk ladder
is `clock_run.cjs`'s, under its own adjudicators.

## 1. What each write actually re-ran

Identical in both runs. This is the table the ruling's witness list asks for, and
the only row that may differ between the arms is the first one.

| op | write | plain | memo | |
|---|---|---|---|---|
| **chrome** | `:conduit/show-your-feed` | page 1, **cards 300** | page 1, **cards 0** | **the repair** |
| **bulk** | `:conduit/refresh-feed` | page 0, cards 300 | page 0, cards 300 | external-store invalidation still fires |
| **narrow** | `:conduit/favorite` | page 0, cards 1 | page 0, cards 1 | the other 299 stay asleep |
| **props** | `:conduit/go-to-page` | page 1, cards 300 | page 1, cards 300 | props still propagate |

The three non-chrome rows agreeing exactly is what says the bail-out did not buy
the chrome row by breaking something else — and the driver **refuses** (exit 4)
if they ever disagree.

## 2. The clock, frame-inclusive (ms, median [min–max])

| row | plain | memo | memo ÷ plain | |
|---|---:|---:|---:|---|
| mount, 301 boundaries | 56.0 [49.5–63.2] | 56.5 [52.4–60.4] | **1.0089×** | run 1 |
| | 58.2 [48.1–64.7] | 62.6 [57.8–73.6] | **1.0756×** | run 2 |
| chrome | 19.7 [10.7–28.1] | 11.8 [4.1–14.0] | **0.599×** | run 1 |
| | 22.0 [18.0–30.0] | 7.3 [2.2–14.3] | **0.3318×** | run 2 |
| bulk | 22.3 [18.5–23.4] | 21.6 [18.9–23.1] | 0.9686× | run 1 |
| | 20.7 [17.9–25.0] | 23.1 [20.6–31.5] | 1.1159× | run 2 |
| narrow | 9.9 [4.1–11.5] | 10.9 [5.6–21.9] | 1.101× | run 1 |
| | 11.1 [4.2–13.8] | 10.0 [4.7–11.6] | 0.9009× | run 2 |
| props | 13.2 [12.5–15.1] | 13.7 [12.0–14.0] | 1.0379× | run 1 |
| | 13.1 [12.1–13.5] | 12.7 [12.1–14.2] | 0.9695× | run 2 |

**Read the ranges, not the medians.** On `bulk`, `narrow` and `props` the two runs
disagree on the *sign* of the difference and every range overlaps: these rows are
**indistinguishable** at this round count on this box, and the honest statement is
a bound rather than a figure — the wrapper costs **no more than ~10%** on them,
and this instrument cannot resolve smaller.

Two rows say more than that:

- **`chrome` is the win, and it is not subtle.** 0.599× and 0.3318×, with the
  card-body count going 300 → 0. The clock agrees with the counter, which is the
  only reason to trust either.
- **`mount` is the row to watch.** 1.0089× then 1.0756×; the ranges overlap in
  both runs, so it is not separable here either — but both runs put `:memo` on the
  slow side of parity, which is the direction the extra Fiber predicts. **A mount
  regression of a few per cent is inside this instrument's noise and would need
  `clock_run.cjs`'s M1 row, with its adjudicators, to call.** Filed, not claimed.

## 3. Per-boundary retained heap — the Fiber, priced

Driver-owned readings: a page cannot force a collection, so every figure is
taken by the driver over CDP — collect ×3 with a beat, read; mount and **hold**;
collect, read; release, collect, read.

| run | arm | boundaries | exclusive retained | **per boundary** | residual after release |
|---|---|---:|---:|---:|---:|
| 1 | plain | 301 | 3,316,432 B | **11,018 B** [10,882–11,409] | 19,084 B (0.6%) |
| 1 | memo | 301 | 3,375,204 B | **11,213 B** [11,128–11,904] | 39,332 B (1.2%) |
| 2 | plain | 301 | 3,312,212 B | **11,004 B** [10,904–11,437] | 17,536 B (0.5%) |
| 2 | memo | 301 | 3,378,380 B | **11,224 B** [11,088–11,918] | 36,872 B (1.1%) |

> **per-boundary delta — +195 B (1.0177×) and +220 B (1.0200×)**

Unlike the clock rows this **reproduces**: two independent runs put the wrapper at
**~195–220 B per boundary**, which is the size of a React Fiber and is exactly
what the ruling predicted would be there.

**The residual gate is why these are quotable.** The first version of this
instrument read the two arms on a page that never returned to its baseline —
`reset-runtime!` leaves the frames standing and a frame holds its subscription
cache, so the arms' baselines came out **1.9 MB apart** and the wrapper appeared
*cheaper* than no wrapper (−4,756 B). That is a differenced-against-a-drifting-floor
artefact, not a result. The driver now destroys both frames, warms each arm with a
discarded mount, alternates arm order, and **refuses the heap table** (exit 4) if
the post-release residual exceeds 25% of what was held. It reads 0.5–1.2% above.

### What +200 B means depends on the boundary shape, and both readings are true

The 11 KB figure above is **not** the R=0 shell: these boundaries each read two
subscriptions and render a 17-element card, so 11 KB is a whole card and must not
be compared to the 1 KB paper-fail line.

- Against **this** boundary — a real card — the wrapper is **+1.8%**.
- Against the **R=0 shell anchor** the
  [reads ladder](reads-per-boundary-heap-ladder.md) measured at **1,143 B**,
  the same +200 B would be ≈ +17%, on a figure that was **already over** the
  1 KB paper line.

The second reading is the one an operator might call "meaningfully farther past
the bar", and it is stated here rather than left for a ladder to find. **This
page does not have it** and does not pretend the card-shaped +1.8% substitutes
for it.

> **Re-taken 2026-08-02 (`rf2-2rtt6.58`) — and the ≈ +17% projection above was
> an over-estimate by about a factor of two.** On the R=0 rung the wrapper costs
> **≈ +100 B, not ≈ +200 B**: **+9%**, moving the shell from 1,141 B to
> **1,247 B** rather than to ≈ 1,343 B. The A/B ranges are disjoint and the
> per-read slope is unmoved to the byte. The rows are on
> [the ladder's own page](reads-per-boundary-heap-ladder.md#the-memo-wrapper-priced-on-this-rung-rf2-2rtt658).
>
> **Two corrections to this section, recorded rather than silently edited.**
> First, the ≈ +17% figure: it assumed the card-shaped +200 B transferred
> unchanged to a boundary that reads nothing, and it does not. ~~This instrument
> mounts and writes, the ladder mounts and holds, and an updated component
> retains an `alternate` fiber that a held one does not.~~ **That reason is
> withdrawn (`rf2-2rtt6.61`, 2026-08-03): this instrument does NOT write before
> it reads.** `chrome_run.cjs` runs `heapForArms` to completion first, on a quiet
> page (line 243, *"the heap half, first, on a quiet page"*), and the four write
> ops do not begin until the clock half at line 264; `heapOnce` is `resetRuntime`
> → baseline → `mountArm` → held, with nothing between the mount and the read.
> The ordering is the same at `cb179b6b3c`, which published the +195/+220 rows.
> **Both instruments mount and hold**, so the `alternate` fiber cannot be the
> difference; the ~2× is real and unexplained, and is `rf2-2rtt6.79`. The +100 B
> re-take stands — only the account of *why* the two disagree was wrong. Second,
> this paragraph
> originally named **`reads_ladder_run.cjs`** as the rig to re-take it on. That
> is the wrong instrument: the freehand ladder carries only `reagent,uix`, has
> no Hicasso arm, and could not have produced the 1,143 B figure. That number
> comes from the **P0 bench** — `p0_run.cjs --only ladder` — which is where the
> re-take was taken, on instrument blobs byte-identical to the published run.
> The bead `rf2-2rtt6.58` was filed from this sentence and inherited its error.

## 4. What this page does and does not settle

**Settles.** The cascade is gone — 300 → 0 card bodies on a page-chrome write, on
a production build, with the three other write shapes unchanged. The wrapper's
retained cost is a reproducible ~200 B per boundary.

**Does not settle.** Whether the mount row carries a real few-per-cent regression
(needs `clock_run.cjs`'s M1 row and its adjudicators). ~~And what +200 B does to
the **R=0 shell** figure against the paper line.~~ **That second one is now
settled** — `rf2-2rtt6.58` re-took the shell on the P0 bench and reads
**+100 B / +9%**, moving it from 1,141 B to 1,247 B; see the note in
[§3](#what-200-b-means-depends-on-the-boundary-shape-and-both-readings-are-true).
Whether that is acceptable for a **default** on a line the shell was already over
remains the operator's call.
