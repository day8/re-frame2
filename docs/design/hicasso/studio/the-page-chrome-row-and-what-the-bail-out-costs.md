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
  +200 B is **≈ +17%**, moving it to ≈ 1,343 B on a figure that was **already over**
  the 1 KB paper line.

The second reading is the one an operator might call "meaningfully farther past
the bar", and it is stated here rather than left for a ladder to find. **The
decisive number is the R=0 shell delta re-taken on the ladder rig itself**
(`reads_ladder_run.cjs`), which is a heavier, separately-sequenced instrument;
this page does not have it, and does not pretend the card-shaped +1.8% substitutes
for it.

## 4. What this page does and does not settle

**Settles.** The cascade is gone — 300 → 0 card bodies on a page-chrome write, on
a production build, with the three other write shapes unchanged. The wrapper's
retained cost is a reproducible ~200 B per boundary.

**Does not settle.** Whether the mount row carries a real few-per-cent regression
(needs `clock_run.cjs`'s M1 row and its adjudicators), and what +200 B does to the
**R=0 shell** figure against the paper line (needs the reads ladder). Both are
filed rather than asserted.
