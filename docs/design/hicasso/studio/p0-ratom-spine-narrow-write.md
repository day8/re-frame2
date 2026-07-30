# P0 — the ratom-spine narrow write (write + flush, summed)

**Bead** `rf2-2rtt6.3` · **Producing commit** `99eeeafcde7aa11db6c0925a8aa0e1113a57c2a0`

```bash
cd implementation && npm ci
node adapters/reagent/test/re_frame/bench/hicasso_narrow_run.cjs
```

**Runtime, and it is the same for every figure on this page:** Chromium
147.0.7727.15, `:advanced`, `goog.DEBUG=false`, Windows 11 (10.0.26200), 24
logical CPUs, six sibling agents live on the box. Exit `0` = reportable,
`1` = a gate failed, `2` = the arm-order guard refused.

---

## What this page settles

A narrow write — one cell of three hundred — on **re-frame2 running the
Reagent adapter**, which is `re-frame.substrate.spine/make-ratom-spine`:
Reagent's own `r/atom`, Reagent's own `make-reaction`, Reagent's own
batching. The figure is **write + flush, summed**, and the split is
published beside it.

It exists because the withdrawn predecessor programme's narrow-update row
was not a valid target as measured, in two separate ways, and both are
repaired here.

**The comparison arm was not the same amount of framework.** It read a
bare `reagent.core/atom` through a `reagent.core/cursor` — Reagent's own
idiom, and a fair arm for *what does Reagent cost*, but not for *what does
re-frame on Reagent cost*. A re-frame-shaped application pays a frame
write and a subscription graph the bare-ratom arm never touches. Both arms
are measured here, in the same interleave on the same page, so the size of
that framing error is a row rather than a claim.

**And the write leg alone means nothing on this substrate.** On the ratom
spine a write is a bare install into the frame's one physical container;
the reactions do not recompute there, they recompute on Reagent's flush.
A harness that timed the write leg and stopped would have published
0.0006–0.0017 ms per write for an operation that costs 0.44. That is a
factor of about 400, and it is why the summed figure is the published one.

---

## The headline

**A narrow write on the ratom spine costs 0.44–0.45 ms, and 99.6% of it is
the flush.**

Three independent runs at the same commit, 300 cells, 300 layer-1
subscriptions, one per cell. Per-write milliseconds; a sample is 20 writes
and the per-write figure is the sample divided by twenty.

| arm | write+flush (mean, 3 runs) | write | flush | write share |
|---|---|---|---|---|
| bare `r/atom` + cursor, no re-frame | 0.0437 / 0.0558 / 0.0485 | 0.0006–0.0015 | 0.0432–0.0543 | 1.3–2.7% |
| **re-frame ratom spine, `replace-app-db!`** | **0.4506 / 0.4383 / 0.4500** | 0.0006–0.0017 | 0.4367–0.4500 | **0.1–0.4%** |
| re-frame ratom spine, `dispatch-sync` | 0.4807 / 0.5264 / 0.4979 | 0.0239–0.0315 | 0.4501–0.4946 | 4.8–6.3% |

Within-run **ranges** for the headline arm, median [min–max] across 36
samples per run:

| run | write+flush |
|---|---|
| 1 | 0.4750 [0.1700–0.7250] |
| 2 | 0.4100 [0.1500–0.9600] |
| 3 | 0.4250 [0.1600–0.7200] |

The three runs' ranges overlap throughout, so they are one measurement
taken three times, not three measurements. The within-run spread is wide —
a factor of four between best and worst sample — because the box is
carrying six other agents; the median is stable to 6% across runs and the
**mean is stable to 3%**, which is what the arms are compared on.

### Both orders

Arms are interleaved at the sample level with the slot order rotating
**and reflecting** on the sample index, so every arm is measured under two
different adjacencies. A bare rotation would not vary that at all: arm `a`
sits at slot `(a − s) mod k`, so its predecessor is `(a − 1) mod k` at
every index.

| arm | forward | reflected | verdict |
|---|---|---|---|
| bare-ratom | 0.0450 [0.0100–0.0800] | 0.0450 [0.0150–0.0800] | overlapping — indistinguishable |
| spine-replace | 0.4725 [0.2450–0.7050] | 0.4750 [0.1700–0.7250] | overlapping — indistinguishable |
| spine-dispatch | 0.4750 [0.2600–0.6800] | 0.4950 [0.1550–0.7600] | overlapping — indistinguishable |

The arm-order guard returned **reportable** on all three runs: no arm's
figure is separated by what ran before it or by where in the run it was
measured, on either factor, at a 10% tolerance.

---

## Where the money goes, and why "flush" needed splitting further

"The flush" is one word doing two jobs — Reagent re-running every dirtied
reaction, and React committing the one cell that changed — and the
distinction is exactly the one the predecessor got wrong. Three cell
counts separate them: the React commit is the same one-cell commit at
every rung and only the subscription count moves.

| subscriptions | flush ms/write | per subscription | local slope vs the rung below |
|---|---|---|---|
| 30 | 0.0129–0.0150 | 0.43–0.50 µs | — |
| 100 | 0.0681–0.0815 | 0.68–0.82 µs | 0.79–0.89 µs/sub |
| 300 | 0.4367–0.4500 | 1.46–1.50 µs | 1.83–1.88 µs/sub |

**No per-subscription cost is quoted, and that is a finding rather than a
caution.** A line fitted through the top and bottom rungs has an intercept
of **−0.032 to −0.036 ms**, and the work that does *not* scale with the
subscription count — React's one-cell commit, Reagent's drain overhead —
cannot cost less than nothing. The two-rung fit was tried first and
produced a clean-looking "2.27 µs per subscription"; the negative
intercept is the data refusing that model. Ten times the subscriptions
costs **34.8× / 29.1× / 33.8×** the flush across the three runs — an
exponent of **1.46–1.54**.

So a narrow write's cost on this substrate is **super-linear in the number
of layer-1 subscriptions in the frame**, and essentially all of it lands
in Reagent's reaction drain rather than in React.

What is *not* in doubt is the shape, which is substrate-independent: a
one-cell write marks **every** layer-1 subscription in the frame dirty and
re-evaluates all of them, because a layer-1 body is an opaque function of
the whole app-db and the graph holds no path. This page prices that shape
on the ratom spine; it does not discover it.

---

## The like-for-like correction

| | mean ms/write | ratio to bare ratom |
|---|---|---|
| bare `r/atom` + cursor (no re-frame) | 0.0437 / 0.0558 / 0.0485 | 1.0× |
| re-frame ratom spine, `replace-app-db!` | 0.4506 / 0.4383 / 0.4500 | **10.3× / 7.9× / 9.3×** |
| re-frame ratom spine, `dispatch-sync` | 0.4807 / 0.5264 / 0.4979 | 11.0× / 9.4× / 10.3× |

**Any narrow-update ratio quoted against the bare-ratom column is
measuring this gap and calling it something else.** A ratio of, say, 15×
against a bare ratom is not 15× against a re-frame application: on these
numbers roughly eight to ten of it is already paid by re-frame reading its
own subscriptions, whatever renders them.

The event drain — the difference between the two write doors — is
**0.030 / 0.088 / 0.048 ms per write** — 6%, 17% and 10% of the total, the
one figure on this page whose three runs do *not* sit tightly together.
It is also the only part of the cost that shows up in the *write* leg at
all: `dispatch-sync` is the only arm whose write leg clears the clock
grid, and it clears it because the event drain runs there.

---

## The instrument's own gates

Every one of these ran before any figure was taken, on every run.

| gate | result |
|---|---|
| **Arm-order guard self-test**, replayed from recorded fixtures, run inside the `:advanced` bundle | 8/8 passed |
| **Key-renaming integrity probe** — the bundle writes and reads its own accumulator keys under the renaming it was compiled with | passed; keys `control,write,gap,force,total,span,bad,writes` |
| **Clock quantum**, measured in-page | 0.100 ms — Chrome's documented clamp, confirmed rather than assumed |
| **DOM read-back**, every write read back out of the page inside its own window | **0 unverified of 4320**, all three runs |
| **Empty-`flushSync` negative control** — the same arm with an empty drain, which must go red | **20 unverified of 20**, all three runs |
| **Positive control**, predicted vs measured | see below |
| **Leg identity** — write + gap + force = total, exactly | holds on every arm |
| **Position completeness** — every sample reached the guard with a finite position | 36 of 36 per arm |

### The positive control

A predicted burn inside every measured window, as its own leg, read three
ways.

| | predicted | measured (p50, run 1 / 2 / 3) |
|---|---|---|
| rung 1 | 0.3000 ms/write | 0.3300 / 0.3300 / 0.3300 |
| rung 2 | 0.9000 ms/write | 0.9700 / 0.9600 / 0.9550 |
| **slope** | 0.6000 ms | 0.6400 / 0.6300 / 0.6250 — **4.2–6.7% high** |

The direct readings run ~0.03 ms above prediction and the slope runs 4–7%
high; both are the spin loop's own clock-read overhead, which the slope
reduces but does not remove, since the loop reads the clock at both rungs.

The third reading is the one that matters, because it is falsifiable: **an
arm's write+flush must not move when the control's size changes.** It
does not — every arm's rung-1 and rung-2 ranges overlap on every run. Had
the control's time leaked into the arms' legs, the leg accounting would be
wrong and nothing on this page would be quotable.

### What is *not* quotable

The clamp check names these, and they are listed rather than quietly
rounded:

- **`write` on every arm but `spine-dispatch`** sits under the 100 µs
  grid. Its median is 0 by construction, so no median-derived write share
  appears anywhere on this page; the split above is taken from the
  **quantisation-unbiased mean**, which recovers a sub-quantum leg because
  `floor(t₁/q)·q − floor(t₀/q)·q` has expectation exactly `t₁ − t₀`.
- **`bare-ratom` and `spine-30` totals** carry only 2–9× the quantum per
  sample. They are reported, and they are not absolute figures.

### Warm-up

Each arm is warmed at full window size until the first third of its
trajectory stops separating from the last third by median, floor 8 windows
and ceiling 20. `spine-replace` reached the ceiling still trending on one
run of the three; the guard's phase factor passed on all three regardless,
which is the direct evidence that warm-up was adequate for the figures
published.

The settle test is the median one **only**. Accepting "medians within
tolerance *or* ranges overlap" let every arm settle at the floor while
still visibly trending — on a loaded box the ranges always overlap, so
that test passes by being uninformative. Overlapping ranges are the right
rule for adjudicating two measured strata; they are the wrong rule for
deciding a site has stopped moving.

---

## Three faults this harness found in itself

Recorded because each produced a plausible, precise, wrong number first.

1. **A shadowed binding silenced three quarters of the guard.** The
   destructured `{:keys [samples …]}` in the round driver shadowed the
   numeric `samples` parameter, so the running position counter came back
   `NaN` and every sample from round two onward reached the arm-order
   guard with a non-finite position. The guard filters those — so it went
   on reporting "24 samples" per arm while adjudicating the phase question
   on the **six** that survived. Nothing threw; the verdict read `[ok]`.
   The driver now prints the finite-position count per arm beside the
   strata and **fails the run** if any is missing.

2. **A median write-share read a flat 0%.** With the write leg under the
   clock grid its median is exactly zero, so a share computed from it was
   0% on every arm — a precise wrong number that looked like a finding.
   The split moved to the mean and the median table lost its share column.

3. **A two-rung ladder fitted a negative intercept.** Reported above; the
   third rung exists because of it.

---

## Lane note

This bead adds **no build id**. `implementation/shadow-cljs.edn` is
hot-zone and `rf2-2rtt6.2` owns the lane's id, so the driver overrides an
existing `:advanced` `:browser` build's entry point and output directory
with `--config-merge` — the technique the donor's own `b8_run.cjs` uses.
It prefers `:hicasso-bench` when the checkout has it and falls back to
`:freehand-release` otherwise, so the lane's id is picked up with no edit
once it lands. `HN_BASE_BUILD` overrides both.
