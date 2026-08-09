# HD-008's fourth donor arm — Freehand's codec, entered

HD-008 concluded that *"the residual mount deficit is the hiccup interpreter
rather than the spine"*
([validation.md](../validation.md#p1-gate--the-composed-donor-arm-hd-008)). The
arm it measured was **reagent-slim's** interpreter, and the row that carried the
conclusion — `donor-r1 / reagent-slim` — came back **1.000 – 1.120**, which is
indistinguishable from reagent-slim itself
([hd8-composed-donor-arm.md](hd8-composed-donor-arm.md#mount--the-m-page-300-rows-markup-dominant)).
So what that row established is narrower than it reads: **reagent-slim's**
interpreter is the deficit.

This repository ships a **second runtime hiccup codec** that was never entered
in that comparison — `re-frame.freehand.react/element`, with the pure rules and
the bounded `js/Map`-backed projection cache in
`re-frame.freehand.conversion`. This page enters it as a **fourth donor arm** in
the same instrument, in the same runs, on the same witnesses, under the same
gates.

Bead **`rf2-2rtt6.29`**. Decision **[HD-008](../decisions.md)**. The standard is
the governance set that superseded **`rf2-2rtt6.1`** on 2026-08-10 — K1 price
acceptance `rf2-hic-003`, budgets and shell line `rf2-hic-006`/`rf2-hic-018`,
bulk verdict protocol `rf2-hic-036`, kill rules in
[the decision brief](../product/decision-brief.md).

> **NO BAR VERDICT IS ISSUED HERE, AND NONE MAY BE READ OUT OF THIS PAGE.**
> The mount win condition is suspended as a gate pending an operator amendment:
> post-`rf2-2rtt6.25`, UIx and Reagent are at parity on the converged mount
> witness (1.0150×), which makes *"mount ≤ 1.0× Reagent"* arithmetically require
> a free-or-negative interpreter and puts it in contradiction with the
> *"codec inside 1.10× of direct UIx"* condition beside it
> (1.0150 × 1.10 = 1.117). Every figure below is therefore reported **against
> raw UIx and against the existing donor arms**, and the bar is applied later by
> whoever holds it. This page measures. It issues no ruling and it must not
> learn to.

---

## Provenance

| | |
|---|---|
| **Landed whole-tree anchor** | **`e385cef113fab17158793d06e3c1967bb7cba5af`** on `main` — the commit a reader can check out, and the one that pins the whole bundle these figures came off: `re-frame.core`, the three adapters, `deps.edn`, `package-lock.json` and the React version. The blob table below pins the instrument files and nothing else |
| **Producing commit (authored)** | `62059f140bbac5d0cdeb11e1a72ee922d5dd3883`, on `worker/codecarm-2rtt6-29`, based on `origin/main` `8b7dad68b7a9a16ba156ac3d13455c18a553b82f`. Rewritten by the rebase and now on no branch — kept because it is what the run's own artefacts recorded, and because the mapping is checkable: `git patch-id --stable` reads `a53d3c8e5da7484ae45bc89583d7061b026bb40f` for both it and the landed SHA above |
| **Where the PR landed** | PR #7322 merged 2026-07-31 with `--rebase`, advancing `main` to `c5c64ad4e79938a3a8f07b30466033c01a479e5b`. The two commits between it and the anchor (`f7feccac06`, `c5c64ad4e7`) touch only this page and `studio/README.md`; **they move no figure**, and the eight blobs below are byte-identical at both |
| **Reproduction** | `node implementation/freehand/test/re_frame/bench/hicasso/hd8_run.cjs` — the full three-run sweep, which is the published shape |
| **Build** | `:hicasso-bench` (rf2-2rtt6.2's lane) — `:advanced`, `goog.DEBUG false`. No new build id; `implementation/shadow-cljs.edn` untouched |
| **Runtime** | Chromium `HeadlessChrome/147.0.7727.15` (Windows NT 10.0 x64), React 19.2.0, node v24.13.0 |
| **Rounds** | 6 · mount `{:warmup 4 :samples 12}` · write `{:warmup 3 :samples 10}` |
| **Exit** | `0` on both sweeps — measured, and no arm reads differently for its position in the plan |

Blob hashes, read at the landed anchor and identical at the authored commit —
they survive a rebase, and they pin the files they name and nothing else:

```bash
A=e385cef113fab17158793d06e3c1967bb7cba5af
git rev-parse $A:implementation/freehand/test/re_frame/bench/hicasso/hd8_witnesses.cljs
```

| file | blob |
|---|---|
| `implementation/freehand/test/re_frame/bench/hicasso/hd8_witnesses.cljs` | `8669cc71d1a2406af8aa3395fe5e9dec5b9ddc64` |
| `implementation/freehand/test/re_frame/bench/hicasso/hd8_rows.cljs` | `b34cf52b9d1cdd9127e7ad9fcaa1e0ed37f40bf0` |
| `implementation/freehand/test/re_frame/bench/hicasso/hd8_app.cljs` | `664a3b1f82913033eb0fa4c21c0ff986a247cf4d` |
| `implementation/freehand/test/re_frame/bench/hicasso/lane.cljs` | `0642815dc234c1544d1f97bd9e1e4dd24365c027` |
| `implementation/freehand/src/re_frame/freehand/react.cljs` (**donor, unmodified**) | `1aa795ad942d8cad459cb4452aa72c47a7d14e19` |
| `implementation/freehand/src/re_frame/freehand/conversion.cljc` (**donor, unmodified**) | `12da0033bc2054f8b4bbcfda27ce0b2eaa66a0d1` |
| `implementation/adapters/reagent-slim/src/reagent2/impl/template.cljs` (**donor, unmodified**) | `399348cdfc66080f351c7ac26a306ee86b7fddbb` |
| `implementation/core/src/re_frame/substrate/spine.cljs` | `1384ae128591b83aa90e1c2c9a35a2ec1d37717f` |

**The spine stamp that qualifies the sibling page does not qualify this one.**
Every column here — including the `donor-r1`, `donor-r2`, `uix`, `reagent` and
`reagent-slim` columns — was re-taken in these two sweeps, on today's spine
blob (`1384ae1285`, the `spine.cljs` blob stamped in the table above), on a plan
that now has one more arm in it. **Nothing on this page
is quoted from
[hd8-composed-donor-arm.md](hd8-composed-donor-arm.md)**, and nothing here amends
it: adding a fifth or sixth arm changes `k` in the interleaving schedule, so
these are a *fresh take of the whole instrument*, internally consistent and not
comparable figure-for-figure with rows measured on a four-arm plan.

**One movement in the re-taken columns is large enough that a reader will notice
it, so it is named rather than left to be discovered.** `donor-r1 /
reagent-slim` on `mount-M` reads **0.900 – 0.979** here in sweep 1 (donor-r1 the
*faster* of the two, disjointly) and **0.900 – 1.013** in sweep 2
(indistinguishable), where the sibling page published **1.000 – 1.120**. That
column is not this page's subject, it was taken on a different plan and a
different spine, and **nothing here adjudicates it** — it is flagged because two
tables that disagree deserve a sentence rather than a reader's silent
arithmetic. What it does not do is affect `donor-fh / donor-r1`, which is formed
within each round from two arms measured in the same plan.

---

## The arm

`donor-fh` is `donor-r1` **with one token changed**.

| held fixed | how |
|---|---|
| the boundary | reagent-slim's `:f>` — `as-fn-component`'s wrapper, cached on the fn as `.-cljsFnComponent`, args through `__rfArgv`. The *same* code path, reached by the *same* `slim-element` call |
| the outer skeleton | the same `[:section.panel …]` / `[:div.ugrid …]` hiccup, interpreted by the same `slim-element` call, over the same keyed `for` seq |
| the reactivity | one `re-frame.adapter.uix/use-subscribe` per boundary, at its published two-arity form, with the frame **pinned as a literal** — rung 1's spine exactly |
| the dispatch | `dispatch-for`, primed outside every measured window, read inside each boundary's render |
| the handler | the author's own closure, `(fn [_] (d [:hd8/touch i]))` |
| the page | the same tags, the same attribute maps, the same subscriptions, the same 300 boundaries. Proved by the canonical-DOM parity gate before any clock is read |
| the plan | the same rounds, the same sampling, the same interleaving schedule, the same floor arm, the same round-local normalisation |

| varied | how |
|---|---|
| **the codec** | the markup *inside* each boundary is built by `re-frame.freehand.react/element` instead of by reagent-slim's `as-element` |

That is **900 of the M page's 903 elements** and **300 of the U page's 301**. The
three (M) and one (U) skeleton elements are built by the *same* codec in both
arms and cancel out of `donor-fh / donor-r1`.

**Nothing under `implementation/freehand/src` is touched.** The donor freeze
stands; the codec is consumed at its published door, exactly as the slim donor
is at `reagent2.impl.template/as-element`.

### What was NOT levelled, and which way each residual runs

1. **One extra pass-through per boundary, against Freehand.** `:f>`'s wrapper
   always converts what the body returned through `as-element`. `donor-r1`'s
   body returns hiccup, so that call does the work; `donor-fh`'s body returns a
   finished React element, so that call is a pass-through — five predicate tests
   and a return. `donor-fh` therefore pays one extra function call and a handful
   of predicates per boundary, 300 times a mount, that `donor-r1` does not.
   **Bounded:** the measured `mount-M` gap is 0.60 – 0.80 ms over 300
   boundaries (absolute p50s, `uix` run: `donor-r1` 3.90 – 4.25 ms,
   `donor-fh` 4.50 – 5.05 ms), i.e. **2 – 3 µs per boundary**, and five
   predicates plus a call is tens of nanoseconds — well under 1% of the gap. It
   runs **against** Freehand, so it cannot have flattered it.
2. **The Freehand codec is measured with no candidate above it, in Freehand's
   favour.** `element` is the door for a form with no declared Freehand boundary
   above it, so the walk threads no candidate and an `:on-*` carrying a bare
   function is attached as itself
   (`re-frame.freehand.events/unsited`) — which is precisely what `as-element`
   does with the same handler, and is what makes the two arms comparable. A
   Freehand **application** renders inside a declared boundary and additionally
   records an event **site** per handler. So this arm prices
   Freehand-the-**codec** exactly and Freehand-the-**substrate** from **below**.
3. **The two codecs do not implement the same language, and this is a capability
   difference as much as an implementation one.** Freehand's walk asks, per
   element, questions reagent-slim's does not: the controlled-input door
   (`controlled/controlled-props?`), the `v/spread-safe` caller split
   (`node/split-caller`), the DOM top-layer pair (`top-layer/install!`), the
   declared custom-element property set (`conv/element-properties`), the
   id-slot cardinality check, and the exact-first `:class` / `:style` alias
   composition. Those are features, and they are on the per-element path. **A
   figure below is what Freehand's codec costs *as it is*, not a claim about
   what a codec must cost.**
4. **Adding the arm changed `k`, so no figure here is comparable to the
   four-arm rows on the sibling page.** Stated above and repeated because it is
   the easiest mistake a reader can make with these two tables side by side.

---

## Gates and controls

Every one of these ran on **every** run of **both** sweeps, and the driver exits
non-zero on any of them.

| gate | predicted **before** the run | measured |
|---|---|---|
| **Positive control** — the floor arm mounting `M` at N and N/2, `(3+3N)/(3+3(N/2))` | **1.9934**, ±30%, **every round** inside `[1.395, 2.591]`; and *below* 1.9934 more often than above, because a fixed per-mount `createRoot`/`flushSync` term pulls the ratio toward 1.0 | **PASS** on 6 of 6 run-segments. Sweep 1: uix `[1.9 2.0 1.7]`, reagent `[1.5714 1.9091 1.5]`, slim `[1.6667 1.8333 1.9091]`. Sweep 2: uix `[2.0 1.8 1.7]`, reagent `[1.8333 1.75 1.5]`, slim `[1.9 1.6667 1.75]`. **16 of 18 rounds below prediction** — the predicted direction; the two exceptions read exactly 2.0000 against a prediction of 1.9934 |
| **Canonical-DOM parity**, before any clock | pass, and `can-fail?` true | **PASS**, `can-fail?` **true**, every run of both sweeps. This is what proves Freehand's codec builds the *same page* as the other five arms |
| **`codecs-differ?`** (new, `rf2-2rtt6.29`) — gate 0b | pass: `slim-element` accepts `^{:key 1} [:li.row]`, `fh-element` **refuses** it | **PASS**, every run of both sweeps |
| **DOM read-back** on every measured write | `0 unverified` | **0 unverified of 7,722 writes** per sweep (uix 5 arms × 858, reagent and slim 2 arms × 858) |
| **Arm-order guard** — every arm partitioned by predecessor **and** by position in the run, tolerance 35% | reportable | **reportable** on every arm of every row of every run; driver **exit 0** on both sweeps |
| **Rung-2 lowering check** | lowers, and the DOM follows | `{:before "0" :after "T" :db-after "T" :lowered? true}` in the `uix` run; correctly recorded inapplicable in the two ratom-spine runs |
| **Yield-correction contract** self-test (8 fixtures) + **corrected-table** self-test (3) + **arm-order guard** self-test (12) | pass | **PASS**, before anything was measured |

### Why `codecs-differ?` exists

`donor-fh / donor-r1` is the figure this arm is for, and a reading of **1.0**
there would be unreadable without it: it could mean *the two codecs cost the
same* or *the arm ran one codec twice*, and no clock reading tells those apart.
The parity gate cannot answer it either — making the arms agree is its entire
purpose.

So the difference is **demonstrated**, on a form the two codecs are known to
treat differently and that no arm on this page uses: `^{:key …}` metadata on a
hiccup vector. reagent-slim honours it; Freehand **refuses** it by name
(`re-frame.freehand.node/refuse-metadata-key!` — one spelling for the key, in
the attribute map, so the JVM structural tree cannot drop what React received).
One accepts, one throws, and the run is fatal at boot if that ever stops being
true. Same standing as `parity-can-fail?`.

### A prediction the run could have falsified

Recorded before the published sweeps: **`donor-fh / donor-r1` sits disjointly
above 1.0 on both mount rows and on the bulk write row, and straddles 1.0 on the
narrow write row** — because a narrow write re-renders exactly one boundary, so
almost no markup is rebuilt and there is nearly nothing for a codec to be slow
at. Across the two sweeps, **13 of the 14 markup-rebuilding rows came back
disjoint above 1.0**, **both** narrow rows straddle, and the single
markup-rebuilding row that straddles is `mount-U` in the `uix` run — the witness
with the *least* markup per boundary, on the one segment where the boundary's
own fixed React cost dominates most. That is the shape predicted.

---

## Results — sweep 1

Every figure is a **range over 6 rounds**, formed within each round against that
round's own p50s. **A range including 1.0 means the two arms are
indistinguishable** on that witness, and the mean is not quoted.

### Mount — the `M` page (300 boundaries, 3 elements each, 903 total)

| run | comparison | range | per-round |
|---|---|---|---|
| uix | **`donor-fh / donor-r1` — THE CODEC** | **1.139 – 1.217** | 1.2169 1.1392 1.1667 1.1975 1.1548 1.1882 |
| uix | `donor-fh / donor-r2` | 1.122 – 1.197 | 1.1222 1.1538 1.1974 1.1548 1.1548 1.1348 |
| uix | `donor-fh / uix` | 1.385 – 1.603 | 1.6032 1.3846 1.4219 1.4478 1.4697 1.5075 |
| uix | `donor-r1 / uix` *(for scale)* | 1.209 – 1.317 | 1.3175 1.2154 1.2188 1.2090 1.2727 1.2687 |
| reagent | **`donor-fh / donor-r1` — THE CODEC** | **1.154 – 1.272** | 1.2717 1.1875 1.2267 1.1558 1.1842 1.1538 |
| reagent | `donor-fh / reagent` | 1.391 – 1.625 | 1.6250 1.3971 1.4375 1.3906 1.4062 1.4063 |
| reagent | `donor-r1 / reagent` *(for scale)* | 1.172 – 1.278 | 1.2778 1.1765 1.1719 1.2031 1.1875 1.2188 |
| reagent | `donor-fh / uix` | 1.369 – 1.746 | 1.7463 1.4394 1.4839 1.3692 1.4754 1.4286 |
| slim | **`donor-fh / donor-r1` — THE CODEC** | **1.078 – 1.306** | 1.2593 1.2308 1.3059 1.2283 1.2921 1.0784 |
| slim | `donor-fh / reagent-slim` | 1.018 – 1.237 | 1.1333 1.1294 1.2333 1.2021 1.2366 1.0185 |
| slim | `donor-r1 / reagent-slim` *(for scale)* | 0.900 – 0.979 | 0.9000 0.9176 0.9444 0.9787 0.9570 0.9444 |
| slim | `donor-fh / uix` | 1.358 – 1.734 | 1.5000 1.5484 1.7344 1.4868 1.5541 1.3580 |

Against the floor, for scale — `uix` run: `uix` 3.200 – 3.722, `donor-r1`
3.900 – 4.611, `donor-r2` 3.800 – 5.000, **`donor-fh` 4.500 – 5.611**.

### Mount — the `U` page (300 boundaries, 1 element each, 301 total)

| run | comparison | range | per-round |
|---|---|---|---|
| uix | **`donor-fh / donor-r1` — THE CODEC** | **0.973 – 1.247** · *indistinguishable* | 1.1356 1.1786 1.1500 0.9726 1.2466 1.1233 |
| uix | `donor-fh / uix` | 1.127 – 1.655 | 1.3400 1.3750 1.3939 1.1270 1.6545 1.4386 |
| uix | `donor-r1 / uix` *(for scale)* | 1.159 – 1.327 | 1.1800 1.1667 1.2121 1.1587 1.3273 1.2807 |
| reagent | **`donor-fh / donor-r1` — THE CODEC** | **1.117 – 1.179** | 1.1167 1.1379 1.1791 1.1471 1.1765 1.1714 |
| reagent | `donor-fh / reagent` | 1.322 – 1.425 | 1.4255 1.3750 1.3621 1.3220 1.3559 1.3667 |
| reagent | `donor-r1 / reagent` *(for scale)* | 1.153 – 1.277 | 1.2766 1.2083 1.1552 1.1525 1.1525 1.1667 |
| reagent | `donor-fh / uix` | 1.294 – 1.367 | 1.3400 1.2941 1.3621 1.3000 1.3559 1.3667 |
| slim | **`donor-fh / donor-r1` — THE CODEC** | **1.049 – 1.257** | 1.1233 1.1831 1.0488 1.2568 1.1154 1.1059 |
| slim | `donor-fh / reagent-slim` | 1.036 – 1.135 | 1.1081 1.1351 1.0361 1.0449 1.1013 1.1325 |
| slim | `donor-r1 / reagent-slim` *(for scale)* | 0.832 – 1.024 · *indistinguishable* | 0.9865 0.9595 0.9880 0.8315 0.9873 1.0241 |
| slim | `donor-fh / uix` | 1.211 – 1.469 | 1.3898 1.4000 1.2113 1.3286 1.4262 1.4688 |

Against the floor — `uix` run: `uix` 5.000 – 6.300, `donor-r1` 5.900 – 7.300,
`donor-r2` 6.000 – 7.700, **`donor-fh` 6.700 – 8.273**.

### Write — narrow (ten single-cell writes, ten distinct cells, one clock)

Only one boundary re-renders per write, so almost no markup is rebuilt. Within
the `uix` run:

| comparison | range | per-round |
|---|---|---|
| **`donor-fh / donor-r1` — THE CODEC** | **0.921 – 1.132** · *indistinguishable* | 1.1321 0.9368 1.1010 0.9208 1.0714 0.9910 |
| `donor-fh / uix` | 0.894 – 1.185 · *indistinguishable* | 1.0619 0.9889 1.1848 0.8942 1.1290 1.0377 |
| `donor-r1 / uix` *(for scale)* | 0.938 – 1.076 · *indistinguishable* | 0.9381 1.0556 1.0761 0.9712 1.0538 1.0472 |

**Every comparison on this row straddles 1.0.** The codec is not the narrow
write's cost, on either implementation.

### Write — bulk (all 300 cells in one commit; all 300 boundaries re-render)

| comparison | range | per-round |
|---|---|---|
| **`donor-fh / donor-r1` — THE CODEC** | **1.417 – 1.550** | 1.4286 1.4615 1.5500 1.4500 1.4167 1.4615 |
| `donor-fh / donor-r2` | 1.308 – 1.409 | 1.3793 1.3103 1.4091 1.3182 1.3077 1.4074 |
| `donor-fh / uix` | **1.722 – 2.000** | 2.0000 1.7273 1.7222 1.8125 1.8889 1.9000 |
| `donor-r1 / uix` *(for scale)* | 1.111 – 1.400 | 1.4000 1.1818 1.1111 1.2500 1.3333 1.3000 |

Against the floor — `uix` run: `uix` 4.500 – 8.000, `donor-r1` 5.000 – 10.000,
`donor-r2` 5.500 – 11.000, **`donor-fh` 7.750 – 14.500**. The `reagent` and
`slim` write rows carry no donor arm (the React `use-subscribe` spine does not
propagate over a ratom spine), so this row is `uix`-run only, exactly as the
sibling page's is.

---

## Results — sweep 2, the replication

The identical instrument, run a second time from a cold lane cache. Exit `0`,
every gate green again (parity + `can-fail?`, `codecs-differ?`, the positive
control on all three segments, **0 unverified of 7,722**, and all **12** guard
rows reportable).

**Every codec range in sweep 2 overlaps its sweep-1 counterpart, and not one of
the sixteen ranges sits disjointly BELOW 1.0 — so nothing in the record says
Freehand's codec is ever the faster of the two. But one row's VERDICT is not
stable across the two sweeps, and that is stated rather than averaged away.**

| row | run | `donor-fh / donor-r1` sweep 1 | sweep 2 | ranges overlap? | same verdict? |
|---|---|---|---|---|---|
| mount-M | uix | 1.139 – 1.217 | 1.139 – 1.229 | yes | yes — disjoint above 1.0 |
| mount-M | reagent | 1.154 – 1.272 | 1.159 – 1.205 | yes | yes — disjoint above 1.0 |
| mount-M | slim | 1.078 – 1.306 | 1.114 – 1.292 | yes | yes — disjoint above 1.0 |
| mount-U | uix | 0.973 – 1.247 · *ind.* | 1.065 – 1.164 | yes | **NO** — straddles, then disjoint above 1.0 |
| mount-U | reagent | 1.117 – 1.179 | 1.087 – 1.363 | yes | yes — disjoint above 1.0 |
| mount-U | slim | 1.049 – 1.257 | 1.138 – 1.296 | yes | yes — disjoint above 1.0 |
| write-narrow | uix | 0.921 – 1.132 · *ind.* | 0.898 – 1.143 · *ind.* | yes | yes — straddles |
| write-bulk | uix | 1.417 – 1.550 | 1.273 – 1.454 | yes | yes — disjoint above 1.0 |

**The unstable row is `mount-U` in the `uix` run**, and it is unstable in the
weaker direction only: sweep 1 has one round at 0.9726, which is what makes its
band straddle; the other five rounds read 1.1233 – 1.2466, and sweep 2's six read
1.0645 – 1.1639. **Read that row as *between indistinguishable and ≈1.25× slower*
and do not judge anything on a finer margin.** Every other row on `U` — the same
witness under the other two adapters, in both sweeps — is disjoint above 1.0, so
the direction is not in doubt; the resolution on that one band is.

Sweep 2's `donor-fh / uix`, for completeness: `mount-M` 1.403 – 1.546 (uix run),
1.406 – 1.507 (reagent), 1.419 – 1.576 (slim); `mount-U` 1.214 – 1.420, 1.235 –
1.557, 1.320 – 1.479; `write-bulk` 1.750 – 2.000; `write-narrow` 0.932 – 1.081 ·
*indistinguishable*.

---

## What the fourth arm says

Stated plainly, because a negative answer is worth as much as a positive one and
is easier to bury.

**Freehand's codec is materially SLOWER than reagent-slim's on every row where
markup is rebuilt, and indistinguishable from it where markup is not.** The gap
tracks how much of the window is markup construction:

Over **both** sweeps — six run-segments on the mount rows, two on each write
row:

| row | what is rebuilt | `donor-fh / donor-r1`, all segments | verdict |
|---|---|---|---|
| write-narrow | 1 boundary, 1 element | 0.898 – 1.143 | **indistinguishable**, 2 of 2 |
| mount-U | 300 boundaries, 1 element each, inside `createRoot` + first commit + 300 hook installs | 0.973 – 1.363 | disjoint above 1.0 in **5 of 6** |
| mount-M | 300 boundaries, 3 elements each, same fixed React cost | 1.078 – 1.306 | disjoint above 1.0 in **6 of 6** |
| write-bulk | 300 boundaries re-rendered, no mount cost to dilute | **1.273 – 1.550** | disjoint above 1.0 in **2 of 2** |

**Against raw UIx**, over both sweeps `donor-fh` reads **1.358 – 1.746** on
`mount-M`, **1.127 – 1.655** on `mount-U`, **1.722 – 2.000** on bulk, and is
indistinguishable on narrow. The same arm with reagent-slim's codec
(`donor-r1`) reads **1.179 – 1.373**, **1.051 – 1.328** and **1.111 – 1.429**.

**Two consequences that belong to the record rather than to a ruling.**

1. **HD-008's conclusion survives, and is now properly quantified.** *"The
   residual mount deficit is the hiccup interpreter"* is confirmed — swapping
   only the interpreter moves `mount-M` by up to 31% and bulk re-render by up to
   55%, with everything else in the arm held fixed. Interpretation is the
   dominant term a codec controls, and this arm is the measurement that shows it
   rather than an inference from an indistinguishable row.
2. **"Consume Freehand's codec instead of writing one" is not a free win, and
   this arm is what makes that decidable without six weeks of work.** On these
   witnesses it is the *worse* of the two codecs already in the tree. That does
   not make it the wrong codec to consume — it is also the more capable one, and
   §*What was NOT levelled* names the per-element questions it answers that
   reagent-slim's does not — but a consumption decision now has a price attached
   instead of an assumption.

**What this page does not say.** It issues no bar verdict, and none may be read
out of it (see the note at the top). It makes no claim about retained heap,
which it does not measure. It makes no claim about what a *new* codec would
cost. It does not amend
[hd8-composed-donor-arm.md](hd8-composed-donor-arm.md) — the two pages are on
different plans and their figures are not interchangeable. And it re-derives
nothing that is already settled with instrument evidence: Freehand's measured
position against Reagent, the ruling-out of the compiled tier, and the
architectural character of the broad-update gap are published elsewhere and are
cited, not disputed.
