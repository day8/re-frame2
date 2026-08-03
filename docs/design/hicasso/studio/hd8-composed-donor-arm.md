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
>
> **The advisory has since been issued — 2026-07-31, recorded on `rf2-2rtt6.1`
> and `rf2-2rtt6.7`, and summarised in
> [validation.md](../validation.md#p1-gate--the-composed-donor-arm-hd-008).**
> It found the stop rule **not met as written**, so continuation is an operator
> override of a pre-registered gate rather than a passing grade. That verdict
> still is not this page's, and this page still holds no verdict; the line above
> is what it is, and this note only stops a reader concluding the gate is
> outstanding. **Read the verdict together with the spine stamp below** — it was
> issued against these pre-landing donor columns.
>
> **The donor rows have since been re-taken on the current tree
> (`rf2-2rtt6.31`), and the mount rows are adjudicated on the clock of record**
> — raw `TaskDuration`, per the mount-gate amendment of 2026-08-02 on
> `rf2-2rtt6.1` — in [the re-take on the current
> tree](#the-re-take-on-the-current-tree-rf2-2rtt631). The published deficit
> against stock Reagent does not reproduce there, and the gated donor-vs-UIx
> pairs sit at the amended 1.10× line, instrument-limited. **Five of the
> re-take's six clock rows failed their positive control**, so every comparative
> claim in that section names the rows it stands on, and only the
> control-passing `reagent-U` row carries one on its own. The rows above stand
> as measured, under their stamp; the re-taken rows are what a post-landing
> candidate is judged against.

---

## Provenance

Three producing commits, each re-taking only the rows whose window it changed.
**The SHA in the left column is the one that LANDED on `main`** — the commit a
reader can check out. The authored SHA beside it is what the run actually
executed, kept because it is what the run's own artefacts recorded, and because
a mapping a reader can verify is worth more than a rewritten SHA presented as if
it had always been the one.

| rows | landed on `main` | authored (rewritten by rebase) | instrument at the landed commit |
|---|---|---|---|
| every row except the `reagent-slim` and narrow write rows | **`172244521eb780a3ba38ccd057cad3430017bf1f`** | `d46ede4fb05a8f4c5af9900f0a010772f0b0883a` | `hd8_rows.cljs` `edec4ba86f543f0f0f4b9566b322c3c1021b360e` · `hd8_witnesses.cljs` `87b8624adefc502f127ddef27ef3e768ecae618c` · `lane.cljs` `d32312d9c562f0b6aa7d7f84538eb81ffc18e61c` |
| the `reagent-slim` write rows (`rf2-b69lw`) | **`c7e4c70ac067d1ace58720a63804d268dad3df3a`** | `b943c7ed20d63d66fade4775059dad9fcf0012a7` | `hd8_rows.cljs` `ab4e7dab4e072123120e0f825f5e2befd8a62452` · `hd8_witnesses.cljs` `87b8624adefc502f127ddef27ef3e768ecae618c` · `lane.cljs` `d32312d9c562f0b6aa7d7f84538eb81ffc18e61c` |
| the **narrow** write rows, on a batched window (`rf2-9zysg`) | **`0cba8181a7293f4aca4d9bd397cdbfc94b2c850a`** | `d3f1c2fff6305235216dc1e4cd9ac1cdf4519d9d` | `hd8_rows.cljs` `e6bca24420b7fc4c9de2c6137f5b2f7144ad243d` · `hd8_witnesses.cljs` `87b8624adefc502f127ddef27ef3e768ecae618c` · `lane.cljs` `671756751ecdb25c4c3d81e164c3204b022e93ae` |

`5cd819c5bffc665fce4fae06abb144dbca1a92db` (authored `7aa55a2433`) is where this
page's final `rf2-b69lw` record landed; it moved no figure.

| | |
|---|---|
| **Reproduction** | `node implementation/freehand/test/re_frame/bench/hicasso/hd8_run.cjs` — runs at each **landed** commit above, and **from a cold or a warm cache in any order** since `rf2-2rtt6.20` ([why](#the-lanes-shared-build-cache-made-this-page-look-broken-rf2-2rtt620)) |
| **Build** | `:hicasso-bench` (rf2-2rtt6.2's lane) — `:advanced`, `goog.DEBUG false` |
| **Runtime** | Chromium `HeadlessChrome/147.0.7727.15` (Windows NT 10.0 x64), React 19.2.0, node v24.13.0 |
| **Rounds** | 6 · mount `{:warmup 4 :samples 12}` · write `{:warmup 3 :samples 10}` |

**The instrument at `main`'s tip is no longer any of the three.** `ba0c215a73`
batched the yield-cost control (`rf2-2rtt6.19`), and `rf2-b69lw` has since turned
that control into an enforced gate — see [The correction contract is enforced,
not stated](#the-correction-contract-is-enforced-not-stated). Neither touched an
arm's window, so no figure on this page moves; but a run at the tip is a run of
a *later* instrument over the same plan, and only a run at the landed commit in
the row's own line reproduces that row's instrument exactly.

### Spine stamp — three of the six arms read a spine that no longer ships

**Every producing commit in the table above carries `spine.cljs` blob
`befd8469d932d6ee381e80e724cfbc98c0861814`**, which is the spine as it stood
*before* both of this week's production landings. Verified by blob rather than by
date, at all four of `172244521e`, `c7e4c70ac0`, `0cba8181a7` and `5cd819c5bf`:

```bash
S=implementation/core/src/re_frame/substrate/spine.cljs
git rev-parse 172244521e:$S   # befd8469d932d6ee381e80e724cfbc98c0861814
git rev-parse 9df5094816:$S   # 56f7e5480c99330515d525a2bdacf5f86a0db7bd  (.13)
git rev-parse f784ab0adb:$S   # 086d08e94089002c19e6b30cc901d03324b0f4cc  (.25)
```

The two landings are `rf2-2rtt6.13` (PR #7304, **`9df5094816`**) — stopped
retaining the disposed render-phase reaction — and `rf2-2rtt6.25` (PR #7305,
**`f784ab0adb`**) — the hook-scoped provisional hand-off, a **mount-path** change
that makes a cold read build one reaction instead of two **under a forced
synchronous commit** (`act`/`flushSync`). **As this page's rows were measured it
did not hold on the public mount schedule**: `rf2-2rtt6.25`'s witness mounts
through the adapter's `:render` slot with no `act` and no `flushSync`, and there
the shipped hook built **twice** — `bodyRuns` 2.00N at N = 1 and at N = 300 —
the `setTimeout 0` reaper releasing the escrowed reference before React's passive
subscribe. `rf2-2rtt6.71` has since ruled the reap horizon out to `setTimeout 4`
(2026-08-03) on the strength of a swap-the-primitive probe — a measured margin,
not a React guarantee — but **no row on this page was re-taken**, so every figure
below still reads the two-build spine. See the retraction banner and its
2026-08-03 update on
[coldmount-double-build-priced.md](coldmount-double-build-priced.md#the-hand-off-landed--the-same-instrument-re-run-against-shipped-code).
This page attributes no retained-heap benefit to `.25` either way. Both are
ancestors of `main`.

**Which columns are pre-landing, and which are not.** `hd8_witnesses.cljs` routes
exactly three arms through `re-frame.adapter.uix/use-subscribe`:

| arm | spine | status |
|---|---|---|
| `uix` | React `use-subscribe` | **PRE-LANDING — reads a spine that no longer exists** |
| `donor-r1` | React `use-subscribe` | **PRE-LANDING** |
| `donor-r2` | React `use-subscribe` | **PRE-LANDING** |
| `reagent` | ratom | unaffected — neither landing goes near the ratom path |
| `reagent-slim` | ratom | unaffected |
| `floor` | none | unaffected |

**So every donor-vs-Reagent ratio on this page has a numerator measured on a
spine term that has since been removed, and a denominator that has not moved.**
`.25` cuts the cold read's double build, which lands on the **mount** rows; the
independent coldmount re-derivation of the layer-1 mount witness post-`.25`
closed that red zone outright. The direction is therefore known — the donor and
`uix` mount columns are **pessimistic** as published — while the magnitude is
not, because this page has not been re-run since. **No figure here is amended by
this stamp and none is deleted:** the rows stand as measured, under the stamp,
and a post-landing candidate is judged against a re-taken donor row rather than
against these. The clock counterpart of this restatement, on the converged
witness, is
[the converged witness set](p0-converged-witness-set.md#red-zone--clock-on-rf2-2rtt62s-witnesses).

**The re-take this stamp obliges has been taken** — `rf2-2rtt6.31`, on the
current tree, and on the clock the mount gate now adjudicates on; see
[the re-take on the current tree](#the-re-take-on-the-current-tree-rf2-2rtt631).

Every figure below is a **browser** figure, which is what HD-012 requires of
anything quotable against the bar.

**Why both columns, and what each one is actually good for.** A producing SHA is
rewritten by rebase *and* by merge. Every authored SHA in the middle column above
is now unreachable — `d46ede4f`, `b943c7ed` and `d3f1c2ff` are on no branch, so a
reader handed one of them can check out nothing and run nothing. That is what the
landed column is for, and it is the **whole-tree anchor**: the bundle these
figures came off depends on `re-frame.core`, on the three adapters, on
`deps.edn`, `package-lock.json` and the React version, and only a commit pins all
of them.

**The blob hashes pin the files they name and nothing else, which is a real but
narrower guarantee.** `git rev-parse HEAD:implementation/freehand/test/re_frame/bench/hicasso/hd8_rows.cljs`
tells a reader whether the instrument file in front of them is the one that took
the row — useful precisely because it survives a rebase. It does **not** tell
them the rest of the tree matches, and this page used to claim it did. The
sibling P0 page has a concrete witness that the claim is false: its narrow row's
authored and landed commits carry the *same* three pinned instrument blobs while
the imported `lane.cljs` changed underneath them. So the two are recorded side by
side and neither substitutes for the other — the commit says *which tree*, the
blob says *which instrument*.

**Each later producing commit re-took only the rows whose window it changed.**
The narrow write rows are the batched re-take's and are marked as such in
[their own section](#write--narrow-one-cell-in-a-300-cell-grid), where
the superseded unbatched ranges are kept rather than deleted; `rf2-9zysg`
batched ten writes under one clock and left every other window alone, so no
other row moved. Before that:

At `d46ede4f` the `reagent-slim` write rows read *78 of 78*
unverified and their figures were withheld. `rf2-z3vlz` diagnosed why and
`rf2-b69lw` repaired it; `b943c7ed` is that repair, and the `reagent-slim`
write rows below are its. **No other row was replaced.** The repair changes the
write window for arms on a **microtask-scheduled** substrate and leaves every
other arm's window byte-for-byte as it was, so the rows taken at `d46ede4f`
stand as published — see [the re-take](#the-re-take-rf2-b69lw) for what was
changed and the reproduction check that says the change was inert for them.

## The re-take on the current tree (rf2-2rtt6.31)

The spine stamp above ends in an obligation — *a post-landing candidate is
judged against a re-taken donor row rather than against these* — and the gate
verdict issued against this page's pre-landing columns was, on its own record,
scored against stale numerators. This section is the ordered re-take. Between
the ordering and the taking, the ground under the instrument moved: the
**mount-gate amendment** (recorded on `rf2-2rtt6.1`, 2026-08-02, delegated by
Mike, operator-overturnable) ratified **raw `TaskDuration` — the arm's script
AND the frame it caused, frame-settled — as the bar's adjudicating clock**, and
restated the mount gate as one line: **≤ 1.10× direct UIx-on-subs,
floor-normalised, same run, on the clock of record.** The in-page
`performance.now()` window every figure above was taken on is now a
**diagnostic**, never a verdict-bearer — that is the settlement of the three
instrument defects (`rf2-8nqsl`, `rf2-yd52q`, `rf2-emvod`), and the amendment
names this bead's re-take as one of the things it un-suspends.

So the re-take is two measurements, deliberately separate:

1. **the six published arms behind the clock-of-record door** — a new
   instrument pair (`hd8_clock_app.cljs` / `hd8_clock_run.cjs`) that takes THIS
   page's own mount arms, mount doors, per-arm frames and witnesses through
   `clock_run.cjs`'s frame-settlement protocol clock. These are the rows the
   amended gate adjudicates.
2. **a full three-run sweep of this page's own instrument at the same commit**
   — the like-for-like re-run, so the movement the spine landings caused is
   readable against the tables above without an instrument change confounding
   it.

### Provenance of the re-take

| | |
|---|---|
| **Producing commit** | `16ddf3e30772ceff170f28ffe466247b0a84341b` on `worker/hd8retake-2rtt6-31` — both measurements, one tree. The landed SHA is the merge's to mint |
| **Spine** | blob `ad7b19d9d8957e7a1872e58f9b18ace8acdc4841` — post-`.13`/`.25`, and **further moved since this page's stamp** (latest spine-touching commit `0c7c5bfb0d`, the `.25` reap-horizon race statement). The pre-landing blob this page's original rows read is `befd8469d932…`, above |
| **Clock of record** | CDP `Performance.getMetrics` raw `TaskDuration`, settled to the next frame (`requestAnimationFrame` → `setTimeout 0`), tared by a `plumb` arm measured in the same block. `taskNet` and the in-page `flushSync` window are recorded on the **same samples** as diagnostics |
| **The door** | every arm, tare included: `page.evaluate → HD8CLOCK.sample` — one door, so its cost is common-mode and subtracted. Through this door `taskNet` is FRAME-ONLY (`DevToolsCommandDuration` carries the arm's own script, `rf2-emvod`) and is never a verdict here |
| **Design** | 6 rounds × 3 blocks × (4 warmup + 10 samples) per arm — 18 blocks per row, `clock_run.cjs`'s own per-block depth. A first cut at (2 + 4) could not adjudicate — single low-side block outliers failed the control and every gated range straddled the boundary by construction — and is recorded in the driver header, publishing nothing |
| **Runtime** | HeadlessChrome/147.0.7727.15 (Windows NT 10.0 x64, 24 cores), React 19.2.0, node v24.13.0, `:hicasso-bench` — `:advanced`, `goog.DEBUG false` |
| **Sweep schedule** | the page's own: 6 rounds, mount `{:warmup 4 :samples 12}`, write `{:warmup 3 :samples 10}`, all three adapter runs, 7-arm mount plans (`donor-fh` rides per `rf2-2rtt6.29`) |
| **Witness stamp** | B/E/Q = 300/300/300 — 300 boundaries, one subscription edge each, 300 distinct query vectors; `M` 903 elements, `U` 301; `ctl-2x` doubles them |
| **Quiet box** | 8 consecutive sub-30 % CPU samples verified immediately before **every** clock-of-record row; loud attempts refused and recorded (three were). Measurement windows (2026-08-02): uix 04:59:59–05:02:46 Z · reagent 05:02:46–05:05:02 Z · slim 05:05:02–05:07:41 Z; the sweep followed at ~05:08–05:13 Z on the box the last gate had just verified. The sibling implementation worker (`worker/burst-2rtt6-55`) held its browser gates out of these windows |
| **Datasets** | `implementation/freehand/test/re_frame/bench/hicasso/data/hd8clock-2rtt6-31/{uix,reagent,slim}.json` — the reduced quantities every clock-of-record figure below recomputes from |
| **Exit codes** | clock-of-record run ~~`0`~~; sweep `0` — as taken: measured, guard clean, no refusal. **The clock-of-record `0` is superseded — see the addendum below** |
| **Reproduce** | `node implementation/freehand/test/re_frame/bench/hicasso/hd8_clock_run.cjs` · `node implementation/freehand/test/re_frame/bench/hicasso/hd8_run.cjs` — **at the producing commit above**, whose drivers are the blobs in the table below. The first command's exit rule at `main`'s tip is not the one that produced the code beside it |

Instrument blobs at the producing commit (they survive a rebase; they pin the
files they name and nothing else):

| file | blob |
|---|---|
| `hd8_clock_app.cljs` | `e2469a0e6fe353200392fda3c471fb5c9731282f` |
| `hd8_clock_run.cjs` | `ba79e38eb25ab05d57932cc2f634816c19b6e64b` |
| `hd8_rows.cljs` | `b34cf52b9d1cdd9127e7ad9fcaa1e0ed37f40bf0` |
| `hd8_witnesses.cljs` | `8669cc71d1a2406af8aa3395fe5e9dec5b9ddc64` |
| `lane.cljs` | `0642815dc234c1544d1f97bd9e1e4dd24365c027` |
| `spine.cljs` | `ad7b19d9d8957e7a1872e58f9b18ace8acdc4841` |

> **Addendum, 2026-08-04 — the clock-of-record run's `0` no longer reproduces
> (`rf2-2rtt6.31`).** The exit code above was true of the run that took these
> rows, and it is kept rather than rewritten because it is what that instrument
> reported. The instrument has since tightened. `rf2-rr6do` (PR #7450,
> `2926049d5e`) moved this driver's exit decision into one pure exported
> `verdict(summary)` and made three refusals it had only ever *printed* fail
> closed — `3` unverified operations, `4` a breached band ceiling, **`5` a
> positive control that missed its own prediction**. The old exit came off the
> arm-order guard alone, which is why a run whose controls read the way these did
> could exit `0`.
>
> Replaying that `verdict()` over the committed datasets — a re-derivation from
> the published data, not a second draw, and no figure on this page moves —
> returns **`5`**, naming `uix/M 1.6503`, `uix/U 1.5566`, `reagent/M 1.6868`,
> `slim/M 1.5518` and `slim/U 1.7100`. Only `reagent/U` passes strict.
> `unverified` is `0` and the band ceiling clean on all six rows, so the control
> is the whole of the refusal. **A reader running the first reproduce command
> today, on rows that behave as these did, gets `5` and not `0`** — and with it
> the driver's own rule: *no magnitude from a failed-control row is reportable*.
> Every comparative claim below therefore names the rows it stands on.

### The gated pairs on the clock of record

Every figure is a mean and **range over 18 blocks**, formed within the block —
the floor cancels, the plumb tare is subtracted, and both arms of every pair
were measured in the same run of the same process. `0 unverified of 10,080`
read-backs across the six row-runs; canonical DOM identical on every row (M
27,491 bytes, U 11,915), held at the stress and the small size, and the
comparison provably able to answer false; the arm-order guard **reportable on
all six row-runs** at tolerance 0.35 on the published clock.

| run | row | `donor-r1 / uix` | `donor-r2 / uix` | band | `ctl-2x` (2.00× predicted) |
|---|---|---|---|---|---|
| uix | M | 1.1024 [0.9593 – 1.2831] | 1.1004 [0.9736 – 1.2497] | 13.2 % | 1.6503 — fails strict |
| uix | U | 1.0855 [0.8672 – 1.2691] | 1.1390 [0.8488 – 1.4122] | 27.2 % | 1.5566 — fails strict |
| reagent | M | 1.1191 [1.0557 – 1.2442] | 1.1322 [1.0267 – 1.2199] | 9.2 % | 1.6868 — fails strict |
| reagent | U | 1.1301 [1.0387 – 1.2275] | 1.1316 [1.0158 – 1.2121] | **5.4 %** | **1.6837 [1.5858 – 1.8570] — PASSES strict** |
| slim | M | 1.1002 [0.9329 – 1.2427] | 1.0932 [0.9478 – 1.1889] | 13.4 % | 1.5518 — fails strict |
| slim | U | 1.1034 [0.9674 – 1.2461] | 1.1358 [1.0362 – 1.3397] | 15.6 % | 1.7100 — fails strict |

**Verdict against the amended line, all twelve cells: INSTRUMENT-LIMITED.**
Every range straddles 1.10, and every margin to the line (0.03 – 3.5 %) sits
inside its row's band (5.4 – 27.2 %). Under the amendment's own clause a result
that cannot resolve the 1.10 boundary is instrument-limited, **not a pass** —
and not a fail either: the donor mount cost over direct UIx sits **at** the
line, means 1.086 – 1.139 across six independent row-runs. What *is* resolved:
on the reagent run both gated pairs sit **wholly above parity** with margins
that clear the band, so the donor cost over UIx is a real ~10 – 13 %, not noise;
on the uix and slim runs the same pairs straddle 1.0.

**That reads off two rows of unequal standing, and the difference matters.**
`reagent-U` is the row whose control passed strict, and its band is the tightest
on the page at 5.4 %; its pairs read 1.1301 [1.0387 – 1.2275] and
1.1316 [1.0158 – 1.2121], both ranges clear of 1.0. **It carries the finding on
its own** — one row, one passing control, one band that resolves it, and it is
the strongest thing on this page rather than one of six. `reagent-M` agrees in
direction and in shape, but its control failed (1.6868× against a predicted
2.00×), so under the driver's rule it corroborates and contributes no magnitude
of its own. The four uix and slim rows, whose pairs straddle 1.0, failed the
control too — a straddle is what they report, and it is not a magnitude.

**The control, stated per its own record.** Mount rows carry `ctl-2x` and the
plumb tare, and no changed-set control can reach a mount (`rf2-jcm3p`). The
control certifies page-proportional signal and prices the additive residual —
inverting the doubling gives c = 0.48 – 2.96 ms across the six row-runs — and
cannot certify exactness: the recorded mount undershoot (1.8173× over
`rf2-emvod`'s seven runs) is exactly where these means sit (1.55 – 1.79). Five
rows failed the *strict* rule on single blocks below 1.50; the reagent-U row
passed it whole, and it is also the row with the tightest band. **Under the
instrument of record those five failures are a refusal rather than a footnote**
— `verdict()` exits `5` on them and states the rule this section reads its rows
by: *no magnitude from a failed-control row is reportable*.

### Co-instrumented beside the gate — and the published deficit does not reproduce

Reagent stays co-instrumented and is **not a second mount gate** (the
amendment's Reagent clause). Same blocks, same arithmetic:

| run | row | `donor-r1 / path` | `donor-r2 / path` | `uix / path` |
|---|---|---|---|---|
| reagent | M | 1.0006 [0.8621 – 1.1318] | 1.0114 [0.9097 – 1.1938] | 0.8943 [0.7875 – 1.0113] |
| reagent | U | 0.9880 [0.8915 – 1.0737] | 0.9888 [0.8891 – 1.0349] | 0.8750 [0.7914 – 0.9830] |
| slim | M | 0.9913 [0.8524 – 1.1580] | 0.9858 [0.8686 – 1.1194] | 0.9027 [0.7835 – 1.0003] |
| slim | U | 0.9544 [0.8774 – 1.0872] | 0.9850 [0.8394 – 1.1958] | 0.8669 [0.7730 – 0.9653] |

Every donor-vs-Reagent-path range **straddles 1.0**. The 1.333 – 1.473× (M) and
1.448 – 1.542× (U) deficits against stock Reagent published above **do not
reproduce on the current tree on the clock of record** — the donor rungs read
indistinguishable from both Reagent paths.

**What that claim stands on: four rows, one of which passed its control.**
`reagent-U` is that one, and on it `donor-r1 / reagent` reads 0.9880
[0.8915 – 1.0737] and `donor-r2 / reagent` 0.9888 [0.8891 – 1.0349] — the
non-reproduction stated on a row entitled to state it, since a straddle is a
failure to resolve and not a magnitude. `reagent-M`, `slim-M` and `slim-U`
straddle the same way and their controls failed, so they are agreement rather
than evidence. And on the control-passing reagent-U row, `uix / reagent` sits
wholly below 1.0 with a 12.5 % margin against a 5.4 % band: on this witness
family, on this clock, **direct UIx is now the faster donor**. The shell
(`donor-r2 / donor-r1`) straddles 1.0 in all six row-runs (0.9957 – 1.0524) —
rung 2 stays free, which re-confirms this page's finding; five of those six
carry a failed control, so what the six share is the straddle and reagent-U is
the one that reaches it unaided.

**Absolutes beside the ratios** (p50 raw `TaskDuration` ms per mount, M row,
by run): floor 6.49 / 7.06 / 7.39 · uix 9.12 / 9.60 / 9.48 · donor-r1 9.96 /
10.78 / 10.26 · donor-r2 9.89 / 10.86 / 10.23 · reagent 10.64 · reagent-slim
10.32; plumb tare 0.69 – 0.74; counter grain measured 0.42 – 0.55 ms. The U
row's floor sits at 1.6 – 2.0 ms tared — three to four grains — which is why
its bands run wide and the well-resolved M row leads this table; full per-arm
absolutes for every row are in the datasets.

**The diagnostics on the same samples say where the gap lives.** `taskNet`
(frame-only through this door) reads the gated pairs at 0.98 – 1.08 — the
frame halves of donor and UIx mounts are equal, so **the whole donor-vs-UIx gap
is script**, the interpreter walk, restated from inside the clock. The in-page
window reads the same pairs at 1.13 – 1.24 and `uix / reagent` at 0.80 – 0.83
against the clock of record's 0.87 – 0.89 — the in-page window overstates the
donor deficit against UIx by ~8 – 12 % and overstates UIx's advantage over
Reagent, on this page's own arms. That is the recorded defect family, measured
here rather than assumed.

**What the clock-of-record re-take refuses.** The write rows: `rf2-d2tzk`
fences the bulk row (its floor sits on the in-page clock clamp), and
`rf2-7iqb5` puts this box's bulk-class noise (28 – 48 % within-block IQR
against the ~3.5 % a difference-statistic magnitude needs) an order of
magnitude past adjudicable. No bulk or narrow magnitude is published on the
clock of record, with this sentence as the reason; the sweep below carries the
write rows under the yield-correction contract instead.

### The same instrument, re-run at the same commit

The full three-run sweep, the page's own schedule and gates: positive control
predicted 1.9934 before any clock, measured 1.9167 – 2.0 / 1.8 – 1.9091 /
1.5833 – 1.9167 — every round inside ±30 % in all three runs; lowering check
`:before "0" → :after "T"` with `:db-after "T"`; arm-order guard reportable on
all twelve rows; exit `0`. The plan is the current 7-arm plan (`donor-fh` rides
per `rf2-2rtt6.29`), so ranges are compared with the published 4/5/5-arm rows
as *the same comparison on a fresh take*, not figure-for-figure.

Mount, published above → re-taken at `16ddf3e307`, in-page instrument:

| run | comparison | published *(pre-landing spine)* | **re-taken (current tree)** |
|---|---|---|---|
| uix | `donor-r1 / uix` | 1.149 – 1.230 | 1.211 – 1.300 |
| uix | `donor-r2 / uix` | 1.162 – 1.286 | 1.200 – 1.300 |
| reagent | `donor-r1 / reagent` | **1.333 – 1.473** | **1.143 – 1.227** |
| reagent | `donor-r2 / reagent` | **1.353 – 1.460** | **1.137 – 1.242** |
| reagent | `donor-r1 / uix` | 1.184 – 1.267 | 1.212 – 1.278 |
| reagent | `donor-r2 / uix` | 1.209 – 1.250 | 1.203 – 1.281 |
| slim | `donor-r1 / reagent-slim` | 1.000 – 1.120 · *indist.* | **0.914 – 0.954 — donor faster, disjoint** |
| slim | `donor-r2 / reagent-slim` | 1.034 – 1.133 | **0.919 – 0.989 — donor faster** |
| slim | `donor-r1 / uix` | 1.086 – 1.184 | 1.181 – 1.266 |
| slim | `donor-r2 / uix` | 1.123 – 1.200 | 1.212 – 1.292 |

Mount-U moves the same way: `donor-r1 / reagent` 1.448 – 1.542 → 1.130 – 1.200,
`donor-r1 / reagent-slim` 0.948 – 1.106 → 0.918 – 0.967, `donor / uix`
1.132 – 1.264 across runs. Against the floor on M: uix 3.889 – 4.111 →
3.429 – 3.700, donor-r1 4.500 – 4.790 → 4.300 – 4.550, donor-r2 4.667 – 5.000
→ 4.200 – 4.550, while `reagent` (3.500 – 3.895 → 3.667 – 4.056) and
`reagent-slim` (4.200 – 4.500 → 4.200 – 4.429) hold — **the numerators moved
and the denominators did not, which is precisely what the spine stamp
predicted.** The `donor-fh` columns re-measure consistent with
[the codec-arm page](hd8-freehand-codec-donor-arm.md) (`donor-fh / donor-r1` M
1.139 – 1.282 across runs against its published 1.139 – 1.306), and that page
remains their page of record.

The write rows re-took cleanly, and the correction contract **fired live and
discharged** on this sweep: the slim run's ten-turn aggregate resolved at
0.1 ms on the narrow row, the verdict was `:corrected`, and both bands
published — `reagent-slim / floor` 4.500 – 5.389 `[UNADJUSTED]` /
5.143 – 6.063 `[CORRECTED]` — the exact case
[the contract](#the-correction-contract-is-enforced-not-stated) was built for.
The bulk row's one-turn aggregate read 0.0 in every window
(`:below-resolution`), so `rf2-d2tzk`'s refusal did not arm on this draw.
Within-run write pairs reproduce the published shape: narrow `donor / uix` all
straddle 1.0; bulk `donor-r1 / uix` 1.250 – 1.500 and `donor-r2 / uix`
1.313 – 1.600 against the published 1.185 – 1.313 / 1.278 – 1.469.

### What the re-take does to the gate verdict

**The stop rule as written is still not met — and the failure it was scored on
has narrowed to the boundary.** The rule asked the composed arm to *clearly
beat both Reagent paths and stay acceptably close to direct UIx*. On the
current tree: it clearly beats neither Reagent path on the clock of record —
it is indistinguishable from both, and parity is not a clear win — though on
this page's own instrument it now beats `reagent-slim` outright and trails
stock Reagent by 1.14 – 1.24× where 1.33 – 1.54× was published. Against direct
UIx it sits **at** the amendment's 1.10× line — means 1.086 – 1.139, every
range straddling the boundary, every margin inside its band —
instrument-limited under the amendment's own clause, not a pass and not a
clearance. The verdict's **direction** (not met) survives the re-take; the
published **magnitude** it was scored on does not — it was a property of the
pre-landing spine and of a clock the programme has since demoted. The ruling
itself remains HD-013's to issue and the operator's to overturn; this page
measures, and these are the rows a post-landing candidate is judged against.

---

## The arms

Every arm reads **re-frame2 subscriptions** — the bar's like-for-like
condition. No arm reads a bare ratom.

| arm | hooks | markup | handler | how it resolves `dispatch` |
|---|---|---|---|---|
| `floor` | 0 | hand-written `createElement` | inert | **nothing** — one hoisted `(fn [_] nil)`, shared by every element |
| `reagent` | 0 | Reagent hiccup (`reg-view`) | author closure | the **lexical** `dispatch` `reg-view` injects, bound to the surrounding frame |
| `reagent-slim` | 0 | slim hiccup (`reg-view`) | author closure | the same lexical `dispatch` — one source, the other engine |
| `uix` | 2 | `$` macro — resolved at compile time | author closure | `dispatch-for` — a **deref + lookup** in the shared `frame-dispatch` atom, in render |
| `donor-r1` | 1 | slim hiccup + `:f>` | author closure | `dispatch-for`, as above |
| `donor-r2` | 2 | slim hiccup + `:f>` | **codec-lowered** | `dispatch-for`, as above, then lowered by `lower-events` |

**The arms do NOT all resolve dispatch the same way, and this page used to say
they did.** The sentence here read *"every arm resolves its dispatch fn the same
way — one map lookup, primed outside render"*. Three mechanisms are in the
instrument, not one, and the column above is what the source does:

* `prime-frame!` runs outside every measured window and puts each frame's
  `dispatch` in a shared atom. That much of the old sentence is true, and it is
  what stops any arm paying to *construct* a dispatch fn during a render.
* But only the three React-spine arms go through it, and the **lookup** —
  `(dispatch-for frame)`, a deref of the atom plus a `get` — happens **inside**
  each boundary's render, 300 times a mount. "Primed outside render" described
  the value; it did not describe the read.
* The two Reagent paths never touch that atom at all. `reg-view` injects a
  lexical `dispatch` already bound to the frame from context, which is what a
  Reagent application actually contains.
* The floor resolves nothing. Its handler is a single hoisted no-op shared by all
  300 elements, which is correct — it is the calibrator, not a rival — but it is
  a third thing again.

**What the sentence was defending is still true, and worth keeping.** No arm
mints a fresh **ops map** per render; every mechanism above is one indirection or
fewer; and the frontier comparator is not strawmanned. **The direction of what
remains is stated rather than waved away:** the deref-plus-`get` is paid by
`uix`, `donor-r1` and `donor-r2` and *not* by the two Reagent paths, so the
residual asymmetry runs **against** the arms HD-008 is arguing for. It cannot
have flattered the donor rungs in the ship comparison.

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

> **These figures were re-taken on a BATCHED window (`rf2-9zysg`)** — see
> [Provenance](#provenance) for the producing commit and the blob hashes that
> survive a rebase. Ten single-cell writes, to ten distinct cells, now share one
> clock; the per-write figure is the sample divided by ten. The ranges directly
> below **supersede** the ones taken on the unbatched window, which are kept at
> the end of this section so a reader can see the measurement was revisited
> rather than quietly replaced. Every other row on this page is unchanged and
> still carries its original producing commit — the batch touches the narrow
> window and nothing else.

Within-run, `uix` run:

| comparison | range |
|---|---|
| `donor-r1 / uix` | 0.947 – 1.056 · *indistinguishable* |
| `donor-r2 / uix` | 0.926 – 1.148 · *indistinguishable* |
| `donor-r2 / donor-r1` — the shell | 0.939 – 1.088 · *indistinguishable* |

Cross-run, floor-normalised — **the weaker warrant**: `donor-r1` 5.182 – 6.790
and `donor-r2` 5.400 – 6.579 against `reagent` 4.130 – 6.947 and
**`reagent-slim` 4.222 – 5.944**.

Absolute p50s, **per write** (the sample ÷ 10): floor 0.090 – 0.110 ms, `uix`
0.510 – 0.675, `donor-r1` 0.505 – 0.665, `donor-r2` 0.490 – 0.665; `reagent`
0.475 – 0.760 against its own floor 0.095 – 0.125; `reagent-slim` 0.380 – 0.535
against its own floor 0.090 – 0.095. **0 unverified of 780** on every arm of
every run.

**What the batch bought, and what it did not.** The denominator is no longer on
the clamp: the floor's *sample* p50 is 0.90 – 1.25 ms, nine to twelve quanta,
where the unbatched floor was 0.10 – 0.15 ms — one to one-and-a-half. That was
named as this instrument's weakest figure and it is now the same order of
resolution as everything else on the page. The ranges tightened where
quantisation was what made them wide (`reagent-slim` 2.667 – 6.000 → 4.222 –
5.944, a 2.25× spread down to 1.41×; `donor-r1` 5.000 – 8.000 → 5.182 – 6.790,
1.60× down to 1.31×). They did **not** tighten for `reagent` (1.67× → 1.68×),
where round-to-round drift, not the quantum, is what the range is made of.
Batching cannot help with drift and is not claimed to.

**The per-write absolutes reproduce the unbatched run**, which is the check that
says the batch measures the same operation: `reagent-slim` reads 0.380 – 0.535
ms per write here against 0.40 – 0.60 ms published, and its floor 0.090 – 0.095
against 0.10 – 0.15. The batched ratios sit *higher* than the unbatched ones for
the same reason — a denominator pinned at one quantum was rounded **up**, and a
floor rounded up understates every ratio taken against it.

<details><summary><b>Superseded — the same row on the unbatched window</b>
(`d46ede4f`, and `b943c7ed` for <code>reagent-slim</code>)</summary>

Within-run, `uix` run: `donor-r1 / uix` 0.909 – 1.200, `donor-r2 / uix`
0.960 – 1.200, `donor-r2 / donor-r1` 1.000 – 1.167 — all *indistinguishable*.

Cross-run, floor-normalised: `donor-r1` 5.000 – 8.000 and `donor-r2`
5.000 – 8.000 against `reagent` 3.000 – 5.000 and `reagent-slim` 2.667 – 6.000.
An independent slim-only replication at `f5bd4b49` read 3.000 – 5.500. Absolute
p50s: `reagent-slim` 0.40 – 0.60 ms, floor 0.10 – 0.15 ms. **0 unverified of
78.**

These were taken one write per clock, so both numerator and denominator carried
a 100 µs quantum. They are kept because a superseded measurement is evidence
about the instrument, and deleting it would hide that this row was ever
revisited.

</details>

### Write — bulk (all 300 cells in one commit)

Within-run, `uix` run:

| comparison | range |
|---|---|
| `donor-r1 / uix` | 1.185 – 1.313 |
| `donor-r2 / uix` | 1.278 – 1.469 |
| `donor-r2 / donor-r1` — the shell | 1.046 – 1.125 |

Cross-run, floor-normalised — **the weaker warrant**: `donor-r1` 7.750 – 11.000 and
`donor-r2` 8.250 – 11.750 against `reagent` 8.750 – 17.000 and **`reagent-slim`
11.000 – 13.667**. The independent slim-only replication at `f5bd4b49` read
9.500 – 13.000. Absolute p50s: `reagent-slim` 2.05 – 2.70 ms, floor
0.15 – 0.20 ms — four to eighteen quanta, so this row is far better resolved
than the narrow one beside it. **0 unverified of 78.**

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
`1.667–1.833` (slim run) — every round inside ±30%. The re-take predicted the
same `1.9934` before its own clock and measured `1.750–1.833`, `1.750–2.083`
and `1.714–1.846` — again every round inside the band, on the run that produced
the `reagent-slim` figures as much as on the two that did not.

**Event-vector lowering** — one click fired through rung 2's codec-lowered
handler, outside every measured window, read back out of the DOM:
`:before "0"` → `:after "T"`, with `:db-after "T"` beside it. Without this the
rung-2 clock could be pricing a lowering that produces a closure nobody can
call — the fastest possible implementation of the wrong thing.

**Arm-order guard** — every sample carries its predecessor **and its position in
the run**; the guard partitions on both and refuses any arm whose figure moves
with the plan. All **twelve** rows across the three runs came back
*reportable*, on both factors, with none refused — and the same twelve did on
the re-take. The two-arm write rows are the ones the `k = 2` degeneracy below
would have silenced, and they did not run in one order: each arm's 60 samples
split **30 / 30** across its two possible predecessors, which is the local
`slot-order` override working.

**DOM read-back — 0 unverified of 1,248**, which is every write the driver
executed across the three runs at `b943c7ed` (`0 of 960` counting only the timed
post-warmup samples). **On the batched re-take the narrow rows carry ten writes per
sample, so the same three runs execute 0 unverified of 6,864** — `0 of 780` on
each of the eight narrow arm-columns, plus the bulk rows' `0 of 78` each. The
denominator grew with the batch; the numerator did not.

At `d46ede4f` the same counts read **156 of 1,248** and **120 of 960**,
every one of them the `reagent-slim` arm, and its write figures were suppressed
rather than published. *An earlier version of this page reported that as "156
unverified of 936", which is not a like-for-like denominator and is corrected
here.*

### Five faults the instrument caught before they became numbers

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
5. **The write window waited one fixed microtask for every arm**, and one
   fixed wait is not neutral across scheduler families. The `reagent-slim`
   write rows read **78 of 78** unverified on the first publication and were
   suppressed — which is the read-back doing its job, because unsuppressed
   they said `0.16–0.50×` the floor while the page never changed. The cause
   is below.

### Two findings that are not about the clock

**The React `use-subscribe` spine does not propagate over a ratom spine.** The
lowering check reported `:db-after "T"` with the DOM still at `"0"`: the click
dispatched, the event ran, `app-db` was written, and no view followed. No drain
fixes it — `ratom/flush!` settles the subscription graph and
`reagent.core/flush` renders the dirty components, and a
`useSyncExternalStore` subscriber watching a Reagent Reaction is notified by
neither. This bounds what *"composed from parts already in the repo"* can mean:
the composition cannot share a process with the thing it must beat.

**A benchmark harness cannot hold one wait for every substrate.** The
`reagent-slim` arm looked non-reactive and was in fact merely **late** — every
write landed, one macrotask after the window closed — because
`reagent2.impl.batching` schedules its render queue on the **microtask** queue
and the harness yielded a microtask between the write and the drain. Stock
Reagent survived the identical harness only because its queue is
`requestAnimationFrame`-scheduled and was therefore still full when the drain
arrived. `app-db` followed **every** write in every bundle, so the failure was
always on the view leg; and the positive control that reproduces it is a plain
`reagent2` component reading a plain `reagent2.core/atom`, with no frame, no
`subscribe` and no adapter hook, which puts **re-frame nowhere on the causal
path**. Diagnosed under **`rf2-z3vlz`**
([slim-non-reactive-arm-diagnosis.md](slim-non-reactive-arm-diagnosis.md)), and
neither the adapter nor the mixed bundle is implicated: four bundle
compositions from 96 to 114 compiled sources return byte-identical verdicts.
**No shipped code needs changing and no consumer is affected** — an application
does not write and then yield before flushing; the shipped reagent-slim adapter
smoke was green throughout, and it was right.

## The re-take (`rf2-b69lw`)

**What changed.** The write window's wait now belongs to the **arm** rather than
to the harness. `hd8-rows/arm-scheduler` names the queue each arm's own render
work is scheduled on — `:none` for the floor and for the React spine,
`:animation-frame` for stock Reagent, `:microtask` for reagent-slim — and
`window-of` gives a `:microtask` arm a window with **nothing between the write
and the drain**. Every other arm's window is the same three operations in the
same order as before, and the DOM read-back still runs in the same turn as the
drain, so a commit that arrives a turn late still reads as unverified rather
than as a pass.

**The suppressed figures were not rescued; they were re-taken.** They priced a
commit that had not happened, and the size of the correction says so: the narrow
write moved from `0.16–0.50×` the floor to `2.667–6.000×`, and the bulk write
to `11.000–13.667×`. A number roughly an order of magnitude below the truth is
what an unpaid commit looks like from inside a clock.

**Two windows do not bill the same wait, so the difference was measured.** The
`reagent-slim` window omits one harness microtask that the floor's contains.
`hd8-rows/yield-cost!` prices exactly that turn against the same clock, outside
every arm's window. **On the run that produced the rows above** it read
**p50 0.0 ms, min 0.0, max 0.0 over 10 samples in all three runs** — below
Chrome's 100 µs quantum, so on that run there was nothing to subtract before the
ratios above are read.

**That is a reading, not a property of the instrument, and this page used to
blur the two.** It said the asymmetry *"is beneath this instrument's
resolution"*, full stop. A later run of the same source on the same host read
`{:p50 0 :min 0 :max 0.1 :n 10}` on the `slim` run — nonzero, and therefore
exactly the case the next sentence named. So the reading has to be taken and
adjudicated **every run**, which is what it now is; see
[The correction contract is enforced, not stated](#the-correction-contract-is-enforced-not-stated).

**And since the narrow window now holds ten of those turns rather than one, ten
of them are priced under one clock as well** (`rf2-2rtt6.19`). That reading is
also **0.0 ms**, which bounds the whole ten-turn asymmetry at `< 100 µs` instead
of at ten times a sub-quantum reading — see [The ten-turn asymmetry is measured,
not multiplied](#the-ten-turn-asymmetry-is-measured-not-multiplied).

### The ten-turn asymmetry is measured, not multiplied

**The arithmetic that was wrong.** After the batched re-take, this page bounded
its own microtask asymmetry as *"ten times a quantity below the instrument's own
resolution"*. That does not follow, and it is the same argument the batch was
adopted to make: `hd8-rows/yield-cost!` read `p50 0.0, min 0.0, max 0.0` over
`n = 10`, which against Chrome's 100 µs clamp bounds **one** turn at `< 100 µs`.
It bounds **ten** turns at `< 1.0 ms` — up to **~26%** of a 3.8–7.6 ms batched
narrow sample. And the term is present in three columns and absent from the
fourth:

| arm | batched narrow, × floor | harness microtasks in the window |
|---|---|---|
| `reagent-slim` | 4.222 – 5.944 | **zero** |
| `donor-r1` | 5.182 – 6.790 | ten |
| `donor-r2` | 5.400 – 6.579 | ten |
| `reagent` | 4.130 – 6.947 | ten |

An unpriced term present in three columns and absent from the one they are read
against is exactly the shape of thing that decides whether `reagent-slim` reads
faster than the donor rungs — and HD-008 is the donor gate, so that comparison
is the point of the row.

**The repair is this page's own technique, applied to the control.**
`yield-cost!` now prices **ten harness microtasks inside one clock window**, the
way the narrow row prices ten writes, and reports the per-turn figure as the
sample divided by ten. The recursion mirrors `window-of`'s non-microtask branch
exactly — `(js/Promise.resolve nil)` with the continuation inside the `.then`,
so the next turn begins in the turn the previous one finished in — because
threading the turns through a promise-returning `.then` would add two resolution
ticks per step and price a window no arm runs. The two readings are taken
**sequentially, never through `Promise.all`**: two microtask chains in flight on
one queue would each be timing the other's turns.

**The measurement, all three runs:**

| reading | window | per turn | n |
|---|---|---|---|
| one turn (unchanged, as published) | **0.0 ms** | 0.0 ms | 10 |
| **ten turns under one clock** | **0.0 ms** | **0.0 ms** | 10 |

**Ten turns together still measure 0.0 ms against a 100 µs quantum.** So the
asymmetry is bounded at `< 100 µs` for the whole batch — not at `< 1.0 ms` — and
per turn at `< 10 µs`. Against the narrow row's 3.8–7.6 ms samples that is **at
most 2.6% of the smallest sample and 1.3% of the largest**, where the sentence
this replaces left a reader facing up to 26%.

**Nothing is subtracted on this run, and that is a finding rather than an
assumption.** The bound is ten times tighter than the one it replaces, and it is
the bound the batched window actually needs. Had the ten-turn window read
anything above the clamp, this page would have owed the reader the subtraction
the old sentence quietly assumed away.

**No published row moves.** The control is measured outside every arm's window,
the one-turn reading is unchanged and still reported, and no arm's window was
touched — so the ratios above are the ratios above.

### The correction contract is enforced, not stated

**The sentence above — *"had it read anything else, this page would owe the
reader a subtraction"* — was a promise nothing kept.** `yield-cost!` was
recorded beside the write rows and read by nobody. The audit of `#7269` ran the
exact landed source and measured `{:p50 0 :min 0 :max 0.1 :n 10}` on the `slim`
run: nonzero, the case the promise names, and the driver exited `0` and emitted
unadjusted ratios anyway. A contract nothing evaluates is a sentence.

`hd8-rows/yield-correction` is that contract as code, run against **both write
rows of every run**, and `hd8-app` fails the run on a refusal — the same
fail-closed path a positive control that missed its prediction takes. Four
verdicts, and they are the whole of it:

| verdict | when | what happens to the row |
|---|---|---|
| `:not-owed` | every arm in the row shares one window shape | published unchanged — the harness turns are in numerator and denominator alike |
| `:below-resolution` | the row mixes shapes and the **aggregate** yield window's max is `0.0` across every sample | published unadjusted, with the bound on the record rather than assumed |
| `:corrected` | mixed shapes, aggregate nonzero | the subtraction is **applied**; both bands publish, labelled `[UNADJUSTED]` and `[CORRECTED]` in the cross-run table itself |
| `:refused` | the correction cannot be discharged | **nothing in the row may be published**, and the run exits non-zero |

**Which rows owe anything: the `slim` run's, and no others.** A row owes only
when it mixes the two window shapes, and only `reagent-slim` is
microtask-scheduled. In the `reagent` and `uix` runs every arm carries the
harness turns, so there is nothing asymmetric to correct.

**Two ways to be refused, and neither has a tolerance of its own.**

* **The correction changes the verdict.** If subtracting the bound moves any
  published range across `1.0` — indistinguishable becoming a finding, the
  reverse, or a band crossing the line *whole* — then what the row reports is
  the asymmetry rather than the arms. The test is **three-state**
  (`hd8-rows/side-of-1`: below, straddles, above — any change of state is a
  crossing), and it earned its third state from the PR #7282 audit: the first
  cut compared the `:straddles-1?` boolean at both ends, and a band wholly
  below `1.0` that lands wholly above it never straddles at either end —
  `false → false`, a complete direction reversal accepted as `:corrected`. The
  audit's live-rule counterexample is two slim-row rounds at floor `1.0` /
  `reagent-slim` `0.95` under a `0.1 ms` bound: `0.95×` unadjusted, `1.0556×`
  corrected, the bearer survives, and neither band straddles. The
  classification is still the instrument's **own house rule** extended to
  which side of the line a decided band sits on, deliberately: a contract
  that decides whether a row may be published is not entitled to invent a
  threshold for doing so.
* **The correction exceeds the window** — a yield-bearing arm for which what
  *remains* after the subtraction does not exceed what was *removed*. That
  window was measuring the harness's turns rather than the arm.

  **This test began as `pos?`, and the gate run caught it publishing a precise
  wrong number**, which is worth recording because it is the same shape of fault
  the whole instrument is built against. On a `slim` run where the one-turn
  aggregate resolved at `0.0999999 ms`, the **bulk** row's floor — whose p50 is
  about *one clock quantum* — survived the subtraction at `~1e-8 ms`, still
  positive, and the corrected `reagent-slim / floor` came out at
  **`15,518,934×`**. A denominator that survives by a rounding error is not a
  denominator. The rule is now the comparison between two measured quantities
  with no constant between them, and that row refuses.

**Which number is subtracted, and why the max.** The aggregate measured for
*this row's* `k` — the ten-turn window for narrow, the one-turn reading for bulk
— never a per-turn figure multiplied up, which is the argument that batched the
writes in the first place. A row whose `k` has no measured aggregate is refused
as **unevaluable** rather than corrected with the nearest available number. The
bound subtracted is the aggregate's **max**: the p50 would be the central
estimate of a correction, but a gate has to be evaluated at the end that could
change the answer.

**The direction is fixed and it is why a correction is publishable rather than
alarming.** The turns sit in the bearers' windows only, so removing them makes
the bearers *smaller*. On the `slim` run the floor is the bearer, so a corrected
`reagent-slim / floor` is **larger** than the published one — the unadjusted
figure is a **lower bound** on the slim arm's cost. Both ends are published; a
reader gets the interval instead of a point sitting quietly at one end of it.

**A gate nobody has watched refuse is not a gate, and this one refuses
rarely.** The harness asymmetry reads `0.0` on most runs of this host and
resolves only intermittently, so a live run cannot be relied on to exercise the
branch that matters — which is the branch that stops a figure being published.
So the contract has a **self-test**, replayed from recorded fixtures through the
live rule, run inside the bundle **before any clock**, and fatal when it
disagrees: `hd8-app` throws, `hd8_run.cjs` prints every check and exits `1`. It
is `order_guard.cjs`'s technique and `parity-can-fail?`'s argument, applied to
the newest gate rather than only the oldest ones. Seven fixtures: one per
verdict, one per refusal reason, and one per repaired hole in the rule itself.
**Fixture 4 is the `15,518,934×` row** and **fixture 7 is the whole-band
crossing** — the `0.95× → 1.0556×` reversal the boolean test accepted — each
kept so its repair cannot silently come undone.

**No figure on this page moves.** The contract reads the rows; it does not take
them. A full three-run sweep on the branch that added it returns `:not-owed` on
every `uix` and `reagent` write row and `:below-resolution` on both `slim` write
rows — the aggregate read `0.0` in all ten of its windows, both at `k = 10` and
at `k = 1` — so the ratios above stand exactly as published, now with the bound
checked rather than asserted. A full sweep at the three-state repair (authored
`79de836f35`; the landed SHA is the merge's to mint) answers the same, exit
`0`: `:not-owed` on all four
`uix`/`reagent` write rows, `:below-resolution` on both `slim` rows with every
aggregate window at `0.0`, every read-back `0` unverified, and every slim write
range overlapping the published one — the repair moves gate logic and table
labelling, not a number.

**And the contract has been watched doing the other thing, which is the only
reason to trust it.** A `slim`-only run on the same branch and the same host
drew the nonzero reading: the ten-turn aggregate read **`0.0999999 ms` at its
worst window**, the narrow row came back **`:corrected`**, the bound was
subtracted from the floor — the sole yield-bearing arm in that row — and the
ratios were re-formed within each round. The corrected band came out **larger**
than the unadjusted one, which is the direction the fixed sign predicts, and no
range crossed `1.0`, so the correction was discharged rather than refused. Its
figures are not quoted here: that run was `HD8_ONLY=slim` with no `HD8_ROWS`, so
every row it produced is marked non-publishing, and the point being made is
about the gate rather than about the numbers. This is the case the old page
promised to act on and the old driver exited `0` through.

**The `:corrected` verdict carried a second hole, found by the same audit: its
bands were published in the EDN record and nowhere else.** `yield-correction`
computed `:summary-corrected` and `:head-to-head-corrected`, but the export
carried only verdict, reason, bound and why, and the cross-run table — the
thing a reader copies a figure out of — printed the unadjusted endpoints alone.
So a `:corrected` run announced that both bands publish while exposing exactly
one of them. The export now carries both bands on a `:corrected` verdict, and
the table prints each governed figure twice, labelled `[UNADJUSTED]` and
`[CORRECTED]`, so a figure cannot leave the table without the name of its
band.

**Second-order, worth stating in the same pass.** The same reasoning applies
wherever a sub-quantum per-item cost is asserted negligible across a batch.
`lane/verified-write!`'s `:gap-ms` is priced per window; if a future arm batches
*k* operations under one clock on the shared lane, the same multiplication
reappears there and wants the same treatment.

**Reproduction check — NOT a republication.** The re-take ran all three
adapters, so the rows published at `d46ede4f` were measured again by a driver
carrying the change. Every one of them reproduced: on the mount rows, which are
the well-resolved ones, `donor-r1 / uix` in the `uix` run read `1.150 – 1.233`
against the published `1.149 – 1.230`. On the write rows five of the six
published cross-run ranges are **contained in** the re-take's and the sixth
overlaps it (narrow `donor-r1` `4.000 – 8.000` ⊃ `5.000 – 8.000`; narrow
`reagent` `2.500 – 7.000` ⊃ `3.000 – 5.000`; bulk `donor-r1` `6.000 – 12.000`
⊃ `7.750 – 11.000`; bulk `reagent` `9.750 – 19.000` overlapping
`8.750 – 17.000`). The re-take's write ranges are **wider** — four sibling
workers were live on the host — which is why they are cited as a check and not
as a replacement. **No figure moved systematically, and nothing published was
invalidated.**

**`HD8_ONLY` did not do what this page said it did.** The sentence here read
*"`HD8_ONLY` exists so that a future re-take of one run cannot mint a competing
set of figures for rows published at another commit"*. It selects **adapters**.
Each selected run still executed the bundle's whole row set, so `HD8_ONLY=slim`
emitted `mount-M`, `mount-U`, `write-narrow` and `write-bulk` — four rows, where
a re-take needed one or two — and nothing marked the other three as anything but
figures. The competing set it was supposed to prevent was one copy-and-paste
away (`rf2-b69lw`, from the `#7269` audit).

**The declaration is explicit now, and the default is the safe one.**
`HD8_ROWS` names what a partial run publishes; every other row it emits is
stamped `NON-PUBLISHING` on its heading and on every figure under it, in the
cross-run table a reader actually copies from, and the run states which rows it
may publish in its provenance block.

| invocation | publishes |
|---|---|
| `HD8_ONLY` unset | every row — the full three-run sweep, which is the published shape |
| `HD8_ONLY=slim` | **nothing.** The driver cannot know which re-take was intended, so every row is marked |
| `HD8_ONLY=slim HD8_ROWS=write-narrow,write-bulk` | those two; the mount rows are marked |

Marked rather than suppressed: parity, the positive control, the lowering check,
the read-back and the arm-order guard all still run over the whole set, and a row
withheld from the log is a row nobody can diagnose. What a partial run must never
do is emit a figure that *looks* like the published one.

**Where the general fix belongs, and where it now lives.** The same fixed yield
lived in the shared `lane/verified-write!`, which every Hicasso arm uses, and
the general repair — an arm-declared scheduler, additive, today's path unchanged
when it is absent — is **`rf2-pq7d8`**. It was not made in the same pass as this
page's, because `lane.cljs` is a shared instrument with sibling arms measuring
on it and a shared instrument must not change under a measurement in flight;
HD-008's own `timed-write!` is a separate copy, which is why this repair could
land first without touching it. **`rf2-pq7d8` has since landed.** The lane now
takes the same `:scheduler` declaration this page's `window-of` takes and gives
it the same two shapes — lifted from here rather than invented a second time —
and an arm that declares no `:scheduler` gets the lane's unchanged window. No
arm outside HD-008 declares one, so **no published row moved.**

## The lane's shared build cache made this page look broken (`rf2-2rtt6.20`)

**No figure on this page moves, and none of them ever depended on this.** It is
recorded because the symptom pointed squarely at HD-008 and the cause was not
here at all.

Found by `rf2-2rtt6.19` while re-reading the yield-cost control: after the
natural sequence — run the P0 lane, then run HD-008 — `hd8_run.cjs` **exited 1
before taking a single sample**, with

```
pageerror: Cannot read properties of undefined (reading 'd')
```

It reproduced at *unmodified* HEAD, which is how it was localised, and it
cleared completely with `rm -rf implementation/.shadow-cljs/builds/hicasso-bench`.

**The cause is the lane's one build id, not this arm.** HD-017 gives the whole
programme a single `:hicasso-bench` so that no arm costs a hot-zone edit of
`implementation/shadow-cljs.edn`; shadow-cljs derives the build cache directory
from the build id alone, and fixes it *before* any `--config-merge` data is
applied. The arm is therefore invisible to the cache key, and several different
programs shared one cache entry.

The carrier was isolated rather than assumed, each trial from a cold cache
replaying the same poisoning history — the trap self-heals once the cache has
accumulated every arm's modules, so a warm trial proves nothing:

| trial | result |
|---|---|
| control — poison, remove nothing | **DEAD**, `reading 'd'` |
| remove `shadow-js/` only | **ALIVE** |
| remove `closure.property.map` + `closure.variable.map` only | **DEAD** — the first suspect, and not the carrier |

`shadow-js/` is the npm-conversion cache. Its index is invalidated on shadow's
own cache key and never on the set of npm modules the build actually needs, so
the Reagent arms' fourteen modules — which do not include `react/jsx-runtime` —
left an index that this page's UIx arm was then linked against. That produces a
bundle which compiles cleanly and is wrong in a way only execution can show.

**The driver's fail-closed logic behaved correctly throughout.** It refused to
publish anything from a page that had thrown (`rf2-f5roa`), which is exactly
right; what it could not do was say *why*, and the likeliest conclusion for a
reader was that HD-008 was broken on main. It was not.

Every driver in the lane now clears the shared entry before it builds, so
**this page's reproduction command runs from a cold or a warm cache, in any
order.** The clear is inside the run-to-run noise;
`freehand/test/re_frame/freehand/bench/lane_cache.cjs` carries the measurement
and the alternatives that were rejected.

## Known limitations of this instrument

- ~~**The narrow-write row sits near Chrome's `performance.now()` clamp.**~~
  **Repaired by the batched re-take (`rf2-9zysg`).** Ten writes now share one clock, so
  the row's samples are 3.8–7.6 ms and its floor 0.90–1.25 ms, against a 100 µs
  quantum. What remains is that an early write in a batch is read back up to
  nine microtask turns after its own drain — the pre-batch window already
  granted one such turn by construction, and a commit React parks at the default
  lane needs a *macrotask*, which never occurs inside the window, so the
  read-back's actual target is unaffected. The batched window also contains ten
  harness microtasks for every arm except the microtask-scheduled one, which
  contains none. ~~Each such turn measures 0.0 ms against this clock, so the
  asymmetry is ten times a quantity below the instrument's own resolution.~~
  **That sentence was wrong, and `rf2-2rtt6.19` replaced the multiplication with
  a measurement — see [The ten-turn asymmetry is measured, not
  multiplied](#the-ten-turn-asymmetry-is-measured-not-multiplied).** Ten times a
  quantity below resolution is *not* below resolution: it bounds ten turns at
  `< 1.0 ms`, which is up to ~26% of a 3.8–7.6 ms sample.
- **The bulk write verifies one probe cell**, where the shared lane verifies a
  seq including the far end of the grid. Same bead.
- **The bulk row's FLOOR sits on the clock clamp**, at a p50 of about one 100 µs
  quantum, and that is a harder limit than it looks. The published row was taken
  on a run whose harness-microtask aggregate read `0.0`, so nothing was owed and
  the figure stands. But on a run where that aggregate *resolves* — and it does,
  intermittently, at `0.1 ms` on this host — the correction is the whole of the
  bulk floor, and [the contract](#the-correction-contract-is-enforced-not-stated)
  refuses the row rather than quoting a ratio against a denominator that is
  mostly harness. Lifting it needs the same repair the narrow row got: **batch
  the bulk window** so the floor clears the clamp. Not done here — that changes a
  measured window and therefore obliges a re-take of the row, which is a
  re-publication and not this bead's to make.
- **The write rows' donor-vs-Reagent comparison is cross-run**, floor-normalised.
  The mount rows' is not. **The `reagent-slim` write column is normalised through
  the floor of a run taken at a different commit** from the donor and `reagent`
  columns beside it, which is weaker again; the reproduction check above is what
  that reading rests on.
- **Two window shapes now exist**, and only one of them contains the harness
  microtask. Measured at 0.0 ms against a 100 µs quantum **one turn at a time and
  ten turns at a time** (`rf2-2rtt6.19`) **on the run that produced these rows**,
  so that run's whole batched asymmetry is beneath this instrument's resolution
  rather than ten times something beneath it. **A later run of the same source on
  the same host read `max 0.1 ms`, so this is a per-run reading and not a settled
  property**, which is why `hd8-rows/yield-correction` now adjudicates it every
  run and refuses a row it cannot discharge ([the contract](#the-correction-contract-is-enforced-not-stated)).
  A future instrument with a finer clock inherits a real asymmetry, and
  `rf2-pq7d8` has since carried both shapes into the shared lane, which inherits
  that asymmetry rather than settling it.
- **The arms resolve `dispatch` three ways, not one** — see
  [The arms](#the-arms). `:uix` and both donor rungs read the shared
  `frame-dispatch` atom inside each boundary's render; the two Reagent paths take
  `reg-view`'s lexical `dispatch`; the floor's handler is inert. The residual runs
  against the donor rungs, so it cannot have flattered them, but the arms are not
  levelled on this axis and this page claimed they were.
- **No retained-heap leg.** The red-zone rule governs clock *and* retained heap;
  this arm measures the clock. The heap ladder is `rf2-2rtt6.5`'s
  ([reads-per-boundary-heap-ladder.md](reads-per-boundary-heap-ladder.md)).
