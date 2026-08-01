# Cross-checked against an instrument nobody here wrote

**The mount row survives and the bulk-broad win does not.** Running
[krausest/js-framework-benchmark](https://github.com/krausest/js-framework-benchmark)'s
own driver over three re-frame2 arms — Reagent-on-subs, UIx-on-subs and Hicasso
Arm 1, sharing one model and building byte-identical DOM — an instrument this
programme did not write reads the candidate's mount at **1.1756× Reagent**
against our published **1.2107×**, and reads the contested `UIx / Reagent`
bulk-broad comparison at **0.9740× — parity**, against a published **0.6291×**
that claimed a 37% win.

That second number is the one that matters, because it is now the **third**
frame-inclusive reading of that row and the third to refuse the win:
[the in-page audit](https://github.com/day8/re-frame2/pull/7357) read `1.0509×`,
this benchmark reads `0.9740×`, and our own clock method on this benchmark's app
reads `1.1401×`. Three instruments, two of them ours and one of them nobody's
here, none reproducing a 37% win.

Owner: `rf2-rguy1`. The bar and the kill criteria live on `rf2-2rtt6.1`.

---

## 1. What was actually built, and what it is not

Three framework entries, in a **clone** of the benchmark kept outside this
repository, all `keyed`:

| entry | substrate | reads |
|---|---|---|
| `rf2-reagent` | Reagent + `reagent.dom.client` | `@(rf/subscribe [q])` in `reg-view` boundaries |
| `rf2-uix` | UIx + `uix.dom` | `uixa/use-subscribe` — the published spine |
| `rf2-hicasso` | Hicasso Arm 1 over the UIx adapter | `(sub [q])` — the ambient collector |

**One model serves all three.** `jsfb_model.cljs` holds the app-db shape, the
three subscriptions and the eight event handlers; no arm holds state of its own.
So an arm differs from another arm in the view substrate and in nothing else,
which is the condition that makes a ratio a substrate ratio.

**This is not an upstream entry and is not becoming one.** The bead is explicit
that upstreaming is a maintenance commitment and pre-alpha is the wrong time for
it; that decision is separate and is now better informed. Nothing from the
benchmark's repository is committed here.

### 1.1 What the benchmark requires, verified from its repository

Read out of the clone rather than from the bead's summary, and two of the bead's
recalled facts were wrong:

| requirement | what the repository says |
|---|---|
| layout | `frameworks/[keyed\|non-keyed]/<name>/` |
| metadata | `package.json` with a `js-framework-benchmark` property carrying `frameworkVersion` (fixed, no ranges) or `frameworkVersionFromPackage` |
| **discovery** | the server's `isFrameworkDir` requires **both `package.json` and `package-lock.json`**; a directory missing the lockfile is absent from `/ls` and cannot be benchmarked — **silently, with no error anywhere** |
| the DOM contract | `#run` `#runlots` `#add` `#update` `#clear` `#swaprows`; `tbody>tr>td:nth-of-type(1)` the id, `td:nth-of-type(2)>a` the label, `td:nth-of-type(3)>a>span` the remove control, `danger` on the selected `<tr>` |
| the driver | **puppeteer by default**, not chromedriver — `webdriver-cdp`, `playwright` and `webdriver-afterframe` are the alternatives. All read Chrome's tracing timeline |
| warm-up | 5 iterations on most rows, **3** on partial update |
| default iterations | 15 CPU iterations; a **fresh page per iteration** |
| throttling | benchmarks 03, 04, 05 and 09 get a **4× CPU slowdown** by default; 06 gets 2× |

**Two corrections to the bead.** It states *"there is NO ClojureScript entry
among the 186, so there is no Reagent baseline in that harness; one would have to
be written. That is the real cost."* There are **three** — `keyed/reagent`
(Reagent 0.10), `keyed/re-frame` (re-frame **1.4.3**) and `keyed/helix` — so the
cost was lower than budgeted. And the driver's default runner is puppeteer rather
than chromedriver.

The three existing entries were **not** used as the denominator, and the reason
is the one that governs this whole page: they are a 2020-era Reagent on React 16
built by Leiningen. A ratio across that gap would measure five years of React as
much as it measured a substrate. All three arms here are compiled from this
repository, at one React, by one shadow-cljs, at `:advanced` with `goog.DEBUG
false`.

### 1.2 Keyed, adjudicated by the benchmark rather than by this page

```
rf2-reagent-v0.0.1.alpha-keyed is keyed for 'run benchmark' and keyed for
  'remove row benchmark' and keyed for 'swap rows benchmark'
rf2-uix-v0.0.1.alpha-keyed     … same
rf2-hicasso-v0.0.1.alpha-keyed … same
```

All three in one category, so the comparison is not void. Ids are stable across a
swap and a remove because `:order` holds ids rather than positions.

### 1.3 The one deliberate deviation, and why it strengthens the comparison

Every upstream implementation builds labels with `Math.random()`. Upstream can
afford that because it never compares two implementations on one page — it
compares medians from separate runs, where the distributions coincide.

This page compares arms directly, so it removes the variance instead of averaging
it: the generator is a fixed-seed LCG, reset before the first render. All three
arms draw the identical id sequence and the identical labels, and therefore build
**canonically identical DOM: 216,066 bytes on all three**, attribute names
sorted.

That last gate is worth a sentence because it first came back red. Reagent
serialises `type, id, class` and UIx serialises `class, type, id` — same
elements, same attributes, same values, same length, different write order.
Attribute order is not part of the DOM, so the gate now sorts attribute names, as
this lane's own DOM gate does, and reports the raw lengths beside the canonical
ones.

---

## 2. The design, and the confound it exists to avoid

The naive cross-check is confounded and badly. Our published ratio is `hicasso /
reagent-subs` on the **M1 witness** — 901 elements, 300 boundaries, a cold mount
into an empty container. The benchmark's is **create 1,000 rows** — ~8,000
elements, 1,000 boundaries, a click on a mounted app. Two instruments *and* two
workloads move at once, so a disagreement could not be attributed to either.

That is the shape of fault this programme has already found twice. So the two
factors are crossed:

| | our instrument | the benchmark's |
|---|---|---|
| **the benchmark's app** | [§3](#3-the-two-instruments-on-one-app) | [§3](#3-the-two-instruments-on-one-app) |
| **the M1 witness** | [published](the-candidates-clock.md) | not run |

With the top row filled, *ours vs theirs* is a pure **instrument** comparison at
one workload, and *our create-1,000 vs our published M1 mount* is a pure
**workload** comparison on one instrument. Neither is available from a single
run.

### 2.1 The two instruments measure different quantities, and that is stated first

| | the benchmark's | ours |
|---|---|---|
| statistic | **wall clock**, click `EventDispatch` → end of the first paint `Commit` | **summed main-thread task time**, `TaskDuration` less `DevToolsCommandDuration` |
| idle | included (partly corrected by its `raf_long_delay` adjustment) | excluded |
| window closes at | the paint commit | `requestAnimationFrame` → `setTimeout(0)` |
| page | fresh per iteration | warm, reused across samples |
| statistic over samples | median of 10 | median within a round, mean of 5 rounds |

**So the milliseconds are not comparable and are not compared.** The ratio is,
because a ratio divides out whatever both arms share. Two honest instruments can
differ on a magnitude and agree on a ratio, and the ratio is the cross-check.

The agreement band below is **15%**, declared in `jsfb_compare.cjs` before the
numbers were looked at rather than chosen after.

### 2.2 One arm per page, so the clock lane's worst confound cannot arise

The published clock harness had to hide arms with `display: none` because four
arms shared one document and a dirty frame pays for the whole document. Here each
arm is a separate URL and a separate page, so exactly one arm is ever mounted.
The repair is structural rather than applied.

The cost is that arms are no longer sample-interleaved. Arms alternate within
each round and the round order flips, so a monotone drift cancels to first order,
and the per-round range is published on every row.

### 2.3 The one-op-per-frame caution, answered

The audit worker could not re-take the published harnesses frame-inclusive in
place, because its producer app runs a whole row in one macrotask by design and
adding a settle yields a different row. **That constraint does not bind here**:
every measured operation on this page is one click → one `dispatch-sync` → one
React commit → one frame, which is what the benchmark's own driver requires of
every implementation it measures. There was no row to reshape.

It does leave one real asymmetry, and it is the leading explanation for the
largest disagreement below. Our window **includes** the rAF + `setTimeout(0)`
settle, so work that lands in a post-paint macrotask — disposals, reaper passes —
is inside our number and outside theirs, whose window ends at the paint commit.
[§4](#4-where-they-disagree) is where that shows.

---

## 3. The two instruments on one app

Ratios against `rf2-reagent`, the denominator HD-012 names. Every figure below
comes from one run of each instrument on a quiet box, at the blobs in
[§6](#6-provenance).

**Gates on our run: canonical DOM identical (216,066 B, three arms). 0
unverified of 1,000 writes. 0 page errors. Positive control FAILED — see
[§5](#5-the-control-failed-and-here-is-what-that-does-and-does-not-touch).**

### 3.1 The candidate — `hicasso / reagent`

| operation | benchmark id | theirs | ours | ours, range over rounds | \|diff\| | agree? |
|---|---|---:|---:|---|---:|---|
| create 1,000 rows | `01_run1k` | **1.1756** | **1.2789** | [0.9616 – 1.5482] | 8.8% | **YES** |
| replace all rows | `02_replace1k` | **1.6216** | **1.4260** | [1.3865 – 1.4680] | 13.7% | **YES** |
| partial update (every 10th) | `03_update10th1k` | **0.7203** | **0.7583** | [0.5864 – 1.0118] | 5.3% | **YES** |
| swap rows | `05_swap1k` | 1.3883 | 1.1964 | [0.9422 – 1.4560] | 16.0% | no |
| clear rows | `09_clear1k` | 1.2877 | 1.8638 | [1.4780 – 2.1342] | 44.7% | no |
| create 10,000 rows | `07_create10k` | not run | 1.2683 | [1.1560 – 1.3548] | — | — |

### 3.2 The donor — `uix / reagent`, which is what the contested row is about

| operation | benchmark id | theirs | ours | ours, range over rounds | \|diff\| | agree? |
|---|---|---:|---:|---|---:|---|
| create 1,000 rows | `01_run1k` | 0.8961 | 1.1004 | [0.7907 – 1.6697] | 22.8% | no |
| **replace all rows** | `02_replace1k` | **0.9740** | **1.1401** | [1.0386 – 1.4344] | 17.1% | no |
| partial update (every 10th) | `03_update10th1k` | **0.6907** | **0.6303** | [0.5649 – 0.6963] | 9.6% | **YES** |
| swap rows | `05_swap1k` | **1.1419** | **1.1065** | [0.9284 – 1.2998] | 3.2% | **YES** |
| clear rows | `09_clear1k` | **0.9416** | **1.0440** | [0.9338 – 1.1272] | 10.9% | **YES** |
| create 10,000 rows | `07_create10k` | not run | 1.0535 | [0.9101 – 1.2215] | — | — |

**6 of 10 comparable rows agree within 15%**, and the four that do not are
discussed rather than averaged away.

### 3.3 The mount row, three ways

The bead's question was whether the two instruments agree on the ratio. On the
row the programme has actually published, they do:

| reading | instrument | workload | `hicasso / reagent` |
|---|---|---|---:|
| published (`rf2-0qj9w`) | ours, CDP frame-inclusive | M1 — 901 elements, 300 boundaries | **1.2107** [0.9756 – 1.7208] |
| this page | ours, CDP frame-inclusive | benchmark — ~8,000 elements, 1,000 boundaries | **1.2789** [0.9616 – 1.5482] |
| this page | **the benchmark's own** | the same benchmark page | **1.1756** |

Three readings spanning two instruments and two workloads land in **1.18 – 1.28**.
Changing the *workload* moves the ratio by 5.6%; changing the *instrument* moves
it by 8.8%. **The candidate's mount deficit is real, it is about 20%, and it is
not an artefact of our harness.**

### 3.4 Bulk broad — the sharpened target, and the row that changes

The programme published `UIx / Reagent` bulk-broad at **0.6291×**, a 37% win. The
in-page audit reported that this does not survive a frame-inclusive instrument.
The benchmark's nearest equivalent operations now give two more independent
readings:

| reading | instrument | `UIx / Reagent` | verdict |
|---|---|---:|---|
| published, converged page | in-page `performance.now()` | **0.6291** | a 37% win |
| the audit (`rf2-8nqsl`) | ours, frame-inclusive, same samples | **1.0509** | parity |
| this page, replace all rows | **the benchmark's own** | **0.9740** | parity |
| this page, replace all rows | ours, frame-inclusive | **1.1401** | parity or slightly worse |
| this page, swap rows | **the benchmark's own** | **1.1419** | slightly worse |
| this page, swap rows | ours, frame-inclusive | **1.1065** | slightly worse |

**Every frame-inclusive reading is at parity or worse, and none is near 0.63.**
The two instruments disagree with each other by 17% on `replace all` and agree to
3% on `swap rows`; both disagree with the published figure by 35–80%. That is not
a close call, and the conclusion does not depend on which of the two
frame-inclusive instruments a reader prefers.

`rf2-yd52q` is the filed re-take. This page does not close it — that row is on the
programme's own witnesses and this is a different page — but it removes the
possibility that the audit's finding was an artefact of the audit's instrument.

### 3.5 The rows our clock refused, filled in

[The candidate's clock](the-candidates-clock.md) refused `bulk300`, `bulk100` and
`narrow` because its doubling control failed and the rows moved more between runs
than the effect they reported. Two independent instruments here agree on both:

- **Bulk is the candidate's worst row, and worse than its mount.** `replace all`
  reads 1.4260 (ours) and 1.6216 (theirs) — both well above the 1.18–1.28 mount
  band, both far outside any range straddling 1.0.
- **Narrow is the candidate's best row, and it is a win.** `partial update` reads
  0.7583 (ours) and 0.7203 (theirs): the candidate is 24–28% **faster** than
  Reagent-on-subs when 100 of 1,000 rows are dirty. UIx wins it too (0.6303 /
  0.6907). The clock page's qualitative claim — *"the candidate localises a
  one-cell write, and it is neither better nor worse at it than the donors are"* —
  survives with a correction: on this page all three localise, and both React
  arms localise **better than Reagent does**, with UIx slightly ahead of the
  candidate.

---

## 4. Where they disagree

Four of ten rows fall outside the 15% band. None is dismissed.

**`clear rows`, `hicasso`: ours 1.8638, theirs 1.2877 (44.7%).** The largest gap
on the page, and [§2.3](#23-the-one-op-per-frame-caution-answered) predicts its
direction. Clearing 1,000 rows unmounts 1,000 boundaries; the candidate's
disposals and reaper passes run in a macrotask **after** the paint. Our window
runs to `setTimeout(0)` and contains them; theirs ends at the paint commit and
does not. The decomposition supports it: on this row the candidate's `script`
term is 31.5 ms against Reagent's 14.5 ms while `layout` is 0.08 ms on both —
the divergence is all script, none of it paint, which is what post-paint
teardown looks like. **This is our instrument charging the candidate for work
the benchmark's does not see, and both are defensible; they are answering
different questions.**

**`swap rows`, `hicasso`: ours 1.1964, theirs 1.3883 (16.0%).** Just outside the
band, and both instruments put the candidate clearly slower. The per-round range
[0.9422 – 1.4560] contains the benchmark's figure. Not a disagreement worth a
mechanism.

**`create 1,000` and `replace all`, `uix`: 22.8% and 17.1%.** Both instruments
place UIx within ±15% of parity; ours reads it slightly above 1.0 and theirs
slightly below, so the *direction* flips while both say "about the same". A sign
flip either side of parity is not a finding, and the page does not report it as
one. What it does mean is that neither instrument can resolve `UIx / Reagent` on
these two rows to better than about 20% — which is worth knowing, and is exactly
why the bulk-broad conclusion in [§3.4](#34-bulk-broad--the-sharpened-target-and-the-row-that-changes)
rests on the distance from 0.63 rather than on the third decimal.

**The disagreements are not systematic.** Our-over-theirs ratio-of-ratios runs
1.088, 0.879, 1.053, 0.862, 1.447 on the candidate and 1.228, 1.171, 0.913,
0.969, 1.109 on the donor — above and below 1.0 on both arms, with no consistent
sign. There is no scale error between the instruments to correct for; there are
row-specific window effects, the largest of which is named above.

---

## 5. The control failed, and here is what that does and does not touch

`create 10,000 rows` builds exactly ten times `create 1,000 rows`' rows.
**Predicted before the run: 8× – 13×.** Measured:

| arm | measured | verdict |
|---|---:|---|
| `rf2-reagent` | **13.696×** | **FAIL** |
| `rf2-hicasso` | **13.583×** | **FAIL** |
| `rf2-uix` | **13.112×** | **FAIL** |

All three above the band, by 1–5%. The registered prediction named this direction
in advance: *"above it, that something superlinear (most likely GC) dominates at
10k and the 1k rows are not a clean tenth of it."* The decomposition agrees — at
10k rows the `script` term grows faster than `layout` and `style` on every arm.

**What it does not touch.** The failure is a statement about the *workload*
scaling superlinearly, not about the instrument mis-reading one arm: all three
arms read 13.1–13.7×, a spread of 4%, so whatever is superlinear is being paid
almost equally by every substrate and divides out of an arm-to-arm ratio.

**What it does touch.** Under this lane's strict rule a failed control is a
caveat on the rows it guards, and that is not being re-described here. What this
control certifies is *the instrument tracks work over a 10× change*, and it
certifies that with a 31–37% overshoot rather than cleanly. The right control for
these rows would hold page size fixed and double the **changed set** — the same
repair [the clock page](the-candidates-clock.md#63-what-a-future-run-would-have-to-change)
already filed for its own bulk rows, and this run inherits the gap rather than
closing it.

The band is not widened after the fact. It is recorded as failed.

---

## 6. Provenance

| | |
|---|---|
| Landed whole-tree anchor | *(filled on merge — a rebase-merge mints a new landed SHA, which is why the blob table below is the real pin)* |
| Authoring anchor | `19a3710bc9604684ddbc7b2b72ec901dcc0f0ea7` on `worker/xbench-rguy1`, the tree both runs were taken at. A later rebase moved it to `3680992949a6eec842ba033ae50f3a9a34af7884`; **every blob below is unchanged across that rebase**, which is the check that matters |
| Runtime, ours | Chromium `147.0.7727.15` headless, Playwright 1.59.1, node v24.13.0, `hardware-concurrency` 24, `device-memory` 32 |
| Runtime, theirs | Chrome **150.0.7871.186** (system), puppeteer-core 25.3.0 via `webdriver-ts`, headless, chromedriver 150.0.1 present and version-matched |
| Benchmark revision | `krausest/js-framework-benchmark` `master`, shallow clone taken 2026-08-01, kept at `%LOCALAPPDATA%\Temp\jsfb-rguy1\repo` — **outside this repository, never committed** |
| Build | `:hicasso-bench`, `:advanced`, `goog.DEBUG false`, via `--config-merge` only — no build id added, `implementation/shadow-cljs.edn` untouched |
| Bundles | `rf2-reagent` `300a273bd20d44fcc9a6ef6718a2366faf73d691b2a2122c43003d4fc0ce8ac6` · `rf2-hicasso` `1cc9fef3bca6a6a85602361f807ff95d8338ffefba0dcad9ed04dc8ff343814f` · `rf2-uix` `4594e3f11222a16e7ef47f3fb7c6827854ff3cd9e3fa4ea07682482adea1abc1` |
| Their run | started 2026-08-01 23:55:03 AUSEST, ended 00:00:55, **exit 0**, driver's own PlausibilityCheck `successful run` |
| Our run | started 2026-08-02 00:01:09 AUSEST, ended 00:06:59, **exit 1** — scoped to the positive control ([§5](#5-the-control-failed-and-here-is-what-that-does-and-does-not-touch)); DOM parity, unverified writes and page errors all cleared |
| Box | verified quiet before each run — 8 consecutive `LoadPercentage` samples below 30% |

The instrument, by blob rather than by SHA, because a SHA does not survive a
rebase:

| file | blob |
|---|---|
| `implementation/freehand/test/re_frame/bench/hicasso/jsfb_model.cljs` | `3c186ba5636859a01cb98586dad05a3d4138557d` |
| `implementation/freehand/test/re_frame/bench/hicasso/jsfb_reagent_app.cljs` | `32126d9e8c5e095a3a1c803297a8358e824adea1` |
| `implementation/freehand/test/re_frame/bench/hicasso/jsfb_uix_app.cljs` | `3b8ef3230c91ca9ba726949624c936050734f94b` |
| `implementation/freehand/test/re_frame/bench/hicasso/jsfb_hicasso_app.cljs` | `6ad2e6ae097357f6336762a3edb22d7972b83ac6` |
| `implementation/freehand/test/re_frame/bench/hicasso/jsfb_build.cjs` | `b0aee6935b019a6303bc1ccf8088709c0ccb4cdb` |
| `implementation/freehand/test/re_frame/bench/hicasso/jsfb_ours_run.cjs` | `8f4c8c7657ffe34d63de0b6fccc944021e088259` |
| `implementation/freehand/test/re_frame/bench/hicasso/jsfb_compare.cjs` | `6af3eb23b86b512f0c25dd8020099f8364cd63fa` |

Reproduction — the clone is not vendored, so step one fetches it:

```bash
git clone --depth 1 https://github.com/krausest/js-framework-benchmark.git /tmp/jsfb
cd /tmp/jsfb && npm --prefix server install && npm --prefix webdriver-ts ci \
  && npm --prefix webdriver-ts run compile
(cd server && npm start &)                      # serves :8080

cd <re-frame2>/implementation && npm ci
node freehand/test/re_frame/bench/hicasso/jsfb_build.cjs --dest /tmp/jsfb

cd /tmp/jsfb/webdriver-ts                        # THEIRS
node dist/benchmarkRunner.js \
  --framework keyed/rf2-reagent keyed/rf2-hicasso keyed/rf2-uix \
  --benchmark 01_ 02_ 03_ 05_ 09_ --count 10 --headless true --nothrottling true

cd <re-frame2>/implementation                    # OURS
JSFB_OURS_JSON=/tmp/ours.json \
  node freehand/test/re_frame/bench/hicasso/jsfb_ours_run.cjs

node freehand/test/re_frame/bench/hicasso/jsfb_compare.cjs \
  --theirs /tmp/jsfb/webdriver-ts/results --ours /tmp/ours.json
```

`--nothrottling` is passed deliberately: the driver applies a **4× CPU slowdown**
to benchmarks 03, 05 and 09 by default, and our instrument applies none. Upstream's
published figures are the throttled ones, so **no number on this page is
comparable to the public leaderboard** — which is not what it is for.

---

## 7. The limits, stated so nobody over-reads this

- **The app has essentially trivial state.** A table of rows with an id, a label
  and one selection flag. It exercises rendering and DOM reconciliation. It does
  **not** exercise subscription fan-out beyond the trivial (fan-out 1, two reads
  per row), frame isolation, boundary-scoped reactivity, event pipelines, flows,
  machines, or anything else re-frame2's design is actually about. It can say
  whether the candidate's rendering is competitive. **It cannot say whether the
  reactivity design earns its keep** — the census-derived witnesses still own
  that half.
- **No comparison is made to React, Vue, Svelte or Solid**, though the benchmark
  makes one available. Doing so would have meant reporting a leaderboard position,
  and the Goodhart risk of optimising for a public leaderboard rather than for the
  shapes the census found is real and named on the bead.
- **The three arms are not upstream entries** and their numbers are not comparable
  to upstream's, for the throttling reason above and because the whole point of
  compiling them here was to hold React and the compiler fixed.
- **One run each.** The clock page's five-run instrument-development record has no
  counterpart here. What substitutes for it is that two independent instruments
  were run rather than one, which is a different kind of evidence and not a
  larger amount of the same kind.
- **The positive control failed** ([§5](#5-the-control-failed-and-here-is-what-that-does-and-does-not-touch)).

---

## 8. What this hands the programme

- **The mount row is corroborated by an outside instrument.** 1.1756 against our
  published 1.2107, on a different app, on a different Chrome, by a driver nobody
  here wrote. The candidate's ~20% mount deficit is not a harness artefact.
- **The bulk-broad win is refused by a third instrument.** `UIx / Reagent` reads
  0.9740 on the benchmark's `replace all rows` and 1.1419 on `swap rows`, against
  a published 0.6291. Combined with the audit's 1.0509, no frame-inclusive
  instrument reproduces the win. `rf2-yd52q` should proceed expecting parity.
- **The candidate's refused bulk row has an answer, and it is bad.** `replace all
  rows` at 1.43 (ours) and 1.62 (theirs) — materially worse than its mount row,
  and agreed by both instruments.
- **The candidate's narrow row has an answer, and it is good.** `partial update`
  at 0.76 / 0.72 — a 24–28% win over Reagent-on-subs. UIx wins it slightly harder.
- **Our clock harness and an outside instrument agree on 6 of 10 rows within 15%,
  and the largest disagreement has a named mechanism** — our window includes the
  post-paint macrotask, theirs stops at the paint commit. That is a real
  difference between two defensible instruments, and it is now documented rather
  than latent.
- **Two facts on the bead were wrong and are corrected**: the benchmark has three
  ClojureScript entries, not none, and its default driver is puppeteer, not
  chromedriver.
