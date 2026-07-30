# HD-008 — the composed donor arm, both rungs

The EP-0038 stop-gate, measured. HD-008 asks whether the programme's central
hypothesis survives when it is assembled **out of parts already in this
repository, before any API is designed**: reagent-slim's `:f>`
function-component path and its runtime hiccup interpreter for markup, the
existing UIx `use-subscribe` spine for reactivity.

Two rungs, because the two halves of the claim have to be priced apart.

| | what it adds | hooks per boundary |
|---|---|---|
| **Rung 1** | `:f>` boundaries, runtime hiccup, `use-subscribe` with the frame pinned as a literal | 1 |
| **Rung 2** | plus the **product shell** — one frame-context hook, and **native event-vector lowering** (`:on-click [:hd8/touch i]` lowered by the codec, not by the author) | 2 |

Bead **`rf2-2rtt6.7`**. Decision **[HD-008](../decisions.md)**. The standard is
**`rf2-2rtt6.1`**.

> **THE STOP/CONTINUE RULING IS NOT ISSUED HERE, AND NOT BY THIS PAGE.**
> Per HD-013 and HD-014 it is a *delegated advisory* ruling — one adversarial
> and one creative pass — issued **only against the published P0 baseline
> table**, recorded on `rf2-2rtt6.1`, and operator-overturnable. The red-zone
> thresholds it is judged against (*the measured UIx ratios per witness family,
> on clock and on retained heap*) are set when P0 publishes. This page is
> measurement. There is no verdict in it and there must not be.

---

## Provenance

| | |
|---|---|
| **Producing commit** | `d46ede4fb05a8f4c5af9900f0a010772f0b0883a` |
| **Reproduction** | `node implementation/freehand/test/re_frame/bench/hicasso/hd8_run.cjs` |
| **Build** | `:hicasso-bench` (rf2-2rtt6.2's lane) — `:advanced`, `goog.DEBUG false` |
| **Runtime** | Chromium `HeadlessChrome/147.0.7727.15` (Windows NT 10.0 x64), React 19.2.0, node v24.13.0 |
| **Rounds** | 6 · mount `{:warmup 4 :samples 12}` · write `{:warmup 3 :samples 10}` |

Every figure below is a **browser** figure, which is what HD-012 requires of
anything quotable against the bar.

## The arms

Every arm reads **re-frame2 subscriptions** — the bar's like-for-like
condition. No arm reads a bare ratom. Every arm resolves its dispatch fn the
same way (one map lookup, primed outside render), so an arm that minted a fresh
ops map per render would not be carrying an allocation its rivals escape; the
frontier comparator in particular is never strawmanned.

| arm | hooks | markup | handler |
|---|---|---|---|
| `floor` | 0 | hand-written `createElement` | inert |
| `reagent` | 0 | Reagent hiccup (`reg-view`) | author closure |
| `reagent-slim` | 0 | slim hiccup (`reg-view`) | author closure |
| `uix` | 2 | `$` macro — resolved at compile time | author closure |
| `donor-r1` | 1 | slim hiccup + `:f>` | author closure |
| `donor-r2` | 2 | slim hiccup + `:f>` | **codec-lowered** |

So `donor-r2 / donor-r1` is the product shell's price and nothing else's;
`donor-r2 / uix` holds hooks and dispatch fixed and varies **the codec**;
`donor-r* / reagent*` is HD-008's ship comparison.

**The witnesses.** `M` — 300 rows, each a boundary with its own subscription
and its own handler (`3 + 3N` elements), markup-dominant. `U` — a 300-cell
grid of the same shape (`1 + N` elements), and the page the write rows drive.

## Three runs, and why

Spec 006 allows exactly **one installed adapter per process**. The two Reagent
paths need the ratom spine; the donor rungs need the React one. So the app runs
three times over one bundle.

A **mount is a one-shot read** — `use-subscribe` takes its first snapshot
correctly under either spine, and the canonical-DOM parity gate proves it — so
the mount rows carry every arm in every run and their donor-vs-Reagent
comparison lands **within one process**. Updates are where the spines part
company, so the write rows keep only the arms native to the installed adapter
and their donor comparison is made **through the floor**, which is a weaker
warrant and is labelled as one wherever it appears.

---

## Results

### Mount — the `M` page (300 rows, markup-dominant)

Every figure is a **range over 6 rounds**; a range including 1.0 means the two
arms are **indistinguishable** on this witness, and the mean is not quoted.
Within-run in every row.

| run | comparison | range |
|---|---|---|
| uix | `donor-r1 / uix` | 1.149 – 1.230 |
| uix | `donor-r2 / uix` | 1.162 – 1.286 |
| uix | **`donor-r2 / donor-r1` — the shell** | 1.012 – 1.049 |
| reagent | **`donor-r1 / reagent`** | 1.333 – 1.473 |
| reagent | **`donor-r2 / reagent`** | 1.353 – 1.460 |
| reagent | `donor-r1 / uix` | 1.184 – 1.267 |
| reagent | `donor-r2 / uix` | 1.209 – 1.250 |
| reagent | **`donor-r2 / donor-r1` — the shell** | 0.954 – 1.023 · *indistinguishable* |
| slim | **`donor-r1 / reagent-slim`** | 1.000 – 1.120 · *indistinguishable* |
| slim | **`donor-r2 / reagent-slim`** | 1.034 – 1.133 |
| slim | `donor-r1 / uix` | 1.086 – 1.184 |
| slim | `donor-r2 / uix` | 1.123 – 1.200 |
| slim | **`donor-r2 / donor-r1` — the shell** | 0.961 – 1.063 · *indistinguishable* |

Against the floor, for scale: `reagent` 3.500 – 3.895, `reagent-slim` 4.200 – 4.500, `uix` 3.889 – 4.111, `donor-r1` 4.500 – 4.790, `donor-r2` 4.667 – 5.000.

### Mount — the `U` page (300 cells)

| run | comparison | range |
|---|---|---|
| uix | `donor-r1 / uix` | 0.984 – 1.250 · *indistinguishable* |
| uix | `donor-r2 / uix` | 1.125 – 1.222 |
| uix | **`donor-r2 / donor-r1` — the shell** | 0.943 – 1.143 · *indistinguishable* |
| reagent | **`donor-r1 / reagent`** | 1.448 – 1.542 |
| reagent | **`donor-r2 / reagent`** | 1.250 – 1.542 |
| reagent | `donor-r1 / uix` | 1.125 – 1.333 |
| reagent | `donor-r2 / uix` | 1.078 – 1.299 |
| reagent | **`donor-r2 / donor-r1` — the shell** | 0.813 – 1.031 · *indistinguishable* |
| slim | **`donor-r1 / reagent-slim`** | 0.948 – 1.106 · *indistinguishable* |
| slim | **`donor-r2 / reagent-slim`** | 1.043 – 1.121 |
| slim | `donor-r1 / uix` | 1.028 – 1.364 |
| slim | `donor-r2 / uix` | 1.121 – 1.439 |
| slim | **`donor-r2 / donor-r1` — the shell** | 0.990 – 1.110 · *indistinguishable* |

Against the floor: `reagent` 4.800 – 6.700, `reagent-slim` 5.500 – 7.750, `uix` 5.400 – 6.400, `donor-r1` 6.300 – 7.300, `donor-r2` 6.600 – 7.200.

### Write — narrow (one cell in a 300-cell grid)

Within-run, `uix` run:

| comparison | range |
|---|---|
| `donor-r1 / uix` | 0.909 – 1.200 · *indistinguishable* |
| `donor-r2 / uix` | 0.960 – 1.200 · *indistinguishable* |
| `donor-r2 / donor-r1` — the shell | 1.000 – 1.167 · *indistinguishable* |

Cross-run, floor-normalised — **the weaker warrant**: `donor-r1` 5.000 – 8.000 and
`donor-r2` 5.000 – 8.000 against `reagent` 3.000 – 5.000. This row's precision is the
instrument's weakest (see limitations). `reagent-slim`: **UNPUBLISHED** — 78/78 unverified.

### Write — bulk (all 300 cells in one commit)

Within-run, `uix` run:

| comparison | range |
|---|---|
| `donor-r1 / uix` | 1.185 – 1.313 |
| `donor-r2 / uix` | 1.278 – 1.469 |
| `donor-r2 / donor-r1` — the shell | 1.046 – 1.125 |

Cross-run, floor-normalised — **the weaker warrant**: `donor-r1` 7.750 – 11.000 and
`donor-r2` 8.250 – 11.750 against `reagent` 8.750 – 17.000. `reagent-slim`: **UNPUBLISHED** — 78/78 unverified.

### What the rungs cost

**Markup and reactivity (rung 1) is where essentially the whole cost sits.**

**The product shell (rung 2) is at or below this instrument's resolution.** One
frame-context hook per boundary plus codec-side event-vector lowering read
**indistinguishable from rung 1 on 6 of the 8 rows** above. Where it is
distinguishable at all it is small: mount-M (uix run) 1.012 – 1.049; write-bulk (uix run) 1.046 – 1.125. An earlier run of the same instrument
had it indistinguishable on every row, which is itself the finding — the shell
sits close enough to zero that whether it resolves depends on the round, and no
row shows it as a material cost.

---

## The gates, and what they caught

**Canonical-DOM parity** — every arm built the same page in all three runs, at
the stress size and at a small realistic size, compared with attribute names
sorted. The same comparison at two different sizes answers *false*, so the gate
is not passing vacuously.

**Positive control** — the floor arm building the `M` page at N and at N/2, the
two sizes interleaved as arms in one round. **Predicted 1.9934** from the
witness's own arithmetic `(3 + 3N) / (3 + 3(N/2))` at N = 300, before any clock
was read. Measured `1.800–2.000` (uix run), `1.750–2.000` (reagent run) and
`1.667–1.833` (slim run) — every round inside ±30%.

**Event-vector lowering** — one click fired through rung 2's codec-lowered
handler, outside every measured window, read back out of the DOM:
`:before "0"` → `:after "T"`, with `:db-after "T"` beside it. Without this the
rung-2 clock could be pricing a lowering that produces a closure nobody can
call — the fastest possible implementation of the wrong thing.

**Arm-order guard** — every sample carries its predecessor **and its position in
the run**; the guard partitions on both and refuses any arm whose figure moves
with the plan. All **twelve** rows across the three runs came back
*reportable*, on both factors, with none refused.

**156 unverified of 936** measured writes — every one of them the
`reagent-slim` arm, whose figures are suppressed rather than published.

### Four faults the instrument caught before they became numbers

1. **The floor ignored the witness's `n`** and built 300 rows for a 6-row
   witness. Caught by parity at the small size.
2. **The lowering check read the DOM synchronously after `.click`**, before
   re-frame's event queue had drained, and reported a working lowering as
   broken. It now yields, and reports `:db-after` beside `:after` so that a
   dispatch failure and a drain failure stop looking identical.
3. **The positive control measured its two sizes as consecutive blocks** and
   promptly read 0.42 in one round of three — the full page *faster* than the
   half page. A control measured as two blocks is subject to the very drift it
   exists to detect. The sizes are now interleaved arms in one round, and every
   round must sit inside the band rather than the range merely overlapping it.
4. **The arm-order guard refused four rows** — *"only 1 stratum, the question
   was never asked."* `slot-order` rotates by the sample index then reflects on
   odd indices, and **at k = 2 those two operations cancel** (a pair rotated by
   one *is* the pair reversed), so a two-arm plan runs in one order for ever.
   Three copies of that arithmetic carry the defect; filed as **`rf2-ouwh8`**
   and repaired locally, because sibling P0 arms were measuring on the shared
   copies at the time.

### Two findings that are not about the clock

**The React `use-subscribe` spine does not propagate over a ratom spine.** The
lowering check reported `:db-after "T"` with the DOM still at `"0"`: the click
dispatched, the event ran, `app-db` was written, and no view followed. No drain
fixes it — `ratom/flush!` settles the subscription graph and
`reagent.core/flush` renders the dirty components, and a
`useSyncExternalStore` subscriber watching a Reagent Reaction is notified by
neither. This bounds what *"composed from parts already in the repo"* can mean:
the composition cannot share a process with the thing it must beat.

**The `reagent-slim` arm is not reactive in this bundle.** Its mount is
correct — parity passes at both sizes, values right — but 78 of 78 writes
failed their DOM read-back, under both write mechanisms and every drain tried.
Its write figures are therefore **suppressed, not published**: unsuppressed
they read 0.16–0.50× the floor while the page never changed, which is a precise,
plausible, entirely wrong number and exactly the fault the read-back exists to
catch. Filed as **`rf2-z3vlz`**; the leading hypothesis is a mixed-bundle
artefact of stock `reagent` and `reagent2` coexisting, which would make it
bench-only.

## Known limitations of this instrument

- **The narrow-write row sits near Chrome's `performance.now()` clamp.** Its
  p50s are ~0.4–1.2 ms against a 100 µs quantum, and its ranges are wide in
  proportion. rf2-2rtt6.2's `lane/mount-batch!` — timing *k* operations as one
  window — is the repair; this arm predates it. Filed as **`rf2-f5roa`**.
- **The bulk write verifies one probe cell**, where the shared lane verifies a
  seq including the far end of the grid. Same bead.
- **The write rows' donor-vs-Reagent comparison is cross-run**, floor-normalised.
  The mount rows' is not.
- **No retained-heap leg.** The red-zone rule governs clock *and* retained heap;
  this arm measures the clock. The heap ladder is `rf2-2rtt6.5`'s
  ([reads-per-boundary-heap-ladder.md](reads-per-boundary-heap-ladder.md)).
