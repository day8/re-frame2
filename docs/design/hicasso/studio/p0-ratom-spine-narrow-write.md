# P0 — the ratom-spine narrow write (write + flush, summed)

**Bead** `rf2-2rtt6.3` · **Producing commit**
`828d4f1ac0cff850b6fcb44a7fa2d0729fc175d8` — the commit carrying the
instrument that took these readings; this page is its child.

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
0.0006–0.0010 ms per write for an operation that costs 0.39–0.52 — an
understatement by a factor of between 400 and 850. That is why the summed
figure is the published one.

---

## The headline

**A narrow write on the ratom spine costs 0.39–0.52 ms, and 99.8% of it is
the flush.**

Three independent runs at the one commit; 300 cells, 300 layer-1
subscriptions, one per cell. Per-write milliseconds — a sample is 20
writes and the per-write figure is the sample divided by twenty.

| arm | write+flush (mean, r1 / r2 / r3) | write | flush | write share |
|---|---|---|---|---|
| bare `r/atom` + cursor, no re-frame | 0.0621 / 0.0494 / 0.0310 | 0.0007–0.0008 | 0.0300–0.0613 | 1.1–2.2% |
| **re-frame ratom spine, `replace-app-db!`** | **0.5244 / 0.5146 / 0.3903** | 0.0006–0.0010 | 0.3892–0.5237 | **0.1–0.2%** |
| re-frame ratom spine, `dispatch-sync` | 0.5910 / 0.6076 / 0.4653 | 0.0213–0.0360 | 0.4437–0.5750 | 4.6–6.1% |

Within-run **ranges** for the headline arm — median [min–max] over 36
samples, which is 720 verified writes per run:

| run | write+flush |
|---|---|
| 1 | 0.5475 [0.1850–1.1650] |
| 2 | 0.5075 [0.1950–0.8800] |
| 3 | 0.4200 [0.1350–0.7300] |

All three ranges overlap, so these are one measurement taken three times.
The spread is wide — a factor of six between the best and worst sample of
run 1 — because the box is carrying six other agents, and run 3 caught it
quieter than the other two. **The absolute figure is therefore quoted as a
range and not as a number**, and the two quantities that matter for the
comparison are much steadier than it: the write share holds at 0.1–0.2%
across every run, and the ladder exponent at 1.45–1.53.

### Both orders

Arms are interleaved at the sample level with the slot order rotating
**and reflecting** on the sample index, so every arm is measured under two
different adjacencies. A bare rotation would not vary that at all: arm `a`
sits at slot `(a − s) mod k`, so its predecessor is `(a − 1) mod k` at
every index, and only the round seam ever differs.

Run 1, per-write medians [min–max]:

| arm | forward | reflected | verdict |
|---|---|---|---|
| bare-ratom | 0.0500 [0.0150–0.1450] | 0.0450 [0.0200–0.1300] | overlapping — indistinguishable |
| spine-replace | 0.4925 [0.1950–1.1650] | 0.5700 [0.1850–0.9400] | overlapping — indistinguishable |
| spine-dispatch | 0.5300 [0.2350–1.1100] | 0.5875 [0.2500–1.7900] | overlapping — indistinguishable |
| spine-30 | 0.0150 [0.0000–0.0400] | 0.0125 [0.0000–0.0500] | overlapping — indistinguishable |
| spine-100 | 0.0850 [0.0200–0.2350] | 0.0750 [0.0250–0.2050] | overlapping — indistinguishable |

The arm-order guard returned **reportable on all three runs**: no arm's
figure is separated by what ran before it, or by where in the run it was
measured, on either factor, at a 10% tolerance.

---

## Where the money goes, and why "flush" needed splitting further

"The flush" is one word doing two jobs — Reagent re-running every dirtied
reaction, and React committing the one cell that changed — and telling
them apart is exactly what the predecessor did not do. Three cell counts
separate them by construction: the React commit is the same one-cell
commit at every rung, and only the subscription count moves.

| subscriptions | flush ms/write (r1 / r2 / r3) | per subscription | local slope vs the rung below |
|---|---|---|---|
| 30 | 0.0154 / 0.0182 / 0.0114 | 0.38–0.61 µs | — |
| 100 | 0.0932 / 0.0946 / 0.0557 | 0.56–0.95 µs | 0.63–1.11 µs/sub |
| 300 | 0.5237 / 0.5140 / 0.3892 | 1.30–1.75 µs | 1.67–2.15 µs/sub |

**No per-subscription cost is quoted, and that is a finding rather than a
caution.** A line fitted through the top and bottom rungs has an intercept
of **−0.031 to −0.041 ms**, and the work that does *not* scale with the
subscription count — React's one-cell commit, Reagent's drain overhead —
cannot cost less than nothing. The two-rung fit was tried first and
produced a clean-looking "2.27 µs per subscription"; the negative
intercept is the data refusing that model, and the third rung exists
because of it. Ten times the subscriptions costs **34.0× / 28.2× / 34.1×**
the flush — an exponent of **1.53 / 1.45 / 1.53**.

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

| | mean ms/write (r1 / r2 / r3) | ratio to bare ratom |
|---|---|---|
| bare `r/atom` + cursor (no re-frame) | 0.0621 / 0.0494 / 0.0310 | 1.0× |
| re-frame ratom spine, `replace-app-db!` | 0.5244 / 0.5146 / 0.3903 | **8.4× / 10.4× / 12.6×** |
| re-frame ratom spine, `dispatch-sync` | 0.5910 / 0.6076 / 0.4653 | 9.5× / 12.3× / 15.0× |

**Any narrow-update ratio quoted against the bare-ratom column is
measuring this gap and calling it something else.** A ratio of, say, 15×
against a bare ratom is not 15× against a re-frame application: on these
numbers between eight and thirteen of it is already paid by re-frame
reading its own subscriptions, whatever renders them.

The ratio's own run-to-run spread — 8.4× to 12.6× — is real and is stated
rather than averaged away. Both arms move with the box, and they do not
move together, so the ratio is not the stable quantity here; **the
write-versus-flush split is**, and that is what this bead was asked for.

The event drain — the difference between the two write doors — is
**0.067 / 0.093 / 0.075 ms per write**, 11–16% of the total. It is the
only part of the cost that shows up in the *write* leg at all:
`dispatch-sync` is the one arm whose write leg clears the clock grid, and
it clears it because the drain runs there.

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
| rung 1 | 0.3000 ms/write | 0.3300 / 0.3300 / 0.3250 |
| rung 2 | 0.9000 ms/write | 0.9600 / 0.9650 / 0.9600 |
| **slope** | 0.6000 ms | 0.6300 / 0.6350 / 0.6350 — **5.0–5.8% high** |

The direct readings run 0.025–0.065 ms above prediction and the slope runs
5–6% high; both are the spin loop's own clock-read overhead, which the
slope reduces but does not remove, since the loop reads the clock at both
rungs. The control is a *floor* on the instrument's resolution, not a
correction applied to anything.

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
- **`bare-ratom` and `spine-30` totals** carry only a few multiples of the
  quantum per sample. They are reported, and they are not absolute
  figures — which is one more reason the like-for-like *ratio* is quoted
  as a range and the *split* is quoted as the finding.

### Warm-up

Each arm is warmed at full window size until the first third of its
trajectory stops separating from the last third by median, floor 8 windows
and ceiling 20. **On these three runs several arms reached the ceiling
still trending** — `bare-ratom` and `spine-dispatch` on all three,
`spine-replace` on one — and that is stated rather than smoothed, because
it is the honest account of a box carrying six other agents.

What says the figures are nevertheless usable is not the warm-up rule but
the guard: its **phase** factor partitions each arm's 36 measured samples
into thirds by position in the run and compares the first third against
the last, and it came back clean on every arm of every run. A site that
was still climbing through the measured window would separate there. The
whole trajectory is printed on every run, so this is checkable rather than
asserted.

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
