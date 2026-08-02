# The interpreter walk, profiled and cheapened

Three measurements agreed the candidate's mount deficit is the runtime hiccup
walk — 100% script, scaling with elements-per-boundary, worst on the
one-boundary 1,202-element census page (`rf2-0qj9w`, `rf2-emvod`/`rf2-yd52q`,
`rf2-2rtt6.56`) — and none of them said where inside the walk the time goes.
This page does three things, in the order the bead requires: **profiles** the
walk element-by-element on the acceptance shape, **cheapens** the phases the
profile convicts with behaviour held fixed, and **re-takes** the census clock
rows at the changed blobs on the clock of record.

Bead **`rf2-y1jkm`**. The standard is **`rf2-2rtt6.1`** (mount ≤ 1.10× direct
UIx-on-subs, floor-normalised, same run, raw `TaskDuration`); the before rows
are `rf2-2rtt6.56`'s (`census-real-clock-rows.md`).

> **THE CANONICAL MOUNT WITNESS IS M1 AND STAYS M1.** These rows corroborate
> the amendment's line on census-real screens exactly as the before rows did;
> nothing here re-baselines the canonical witness, and the ruling on any
> verdict below is the operator's (`rf2-2rtt6.1`), never this page's.

## 1. The profile — where a 1,202-element walk actually pays

**Instrument.** `walk_profile_app.cljs` + the lane's generic `run.cjs` driver:
the acceptance page's own hiccup, walked by the shipping codec and by a family
of single-phase ablations written in the measuring namespace (the
`rf2-2rtt6.32` discipline — a local arm timed against a foreign one compares
call conventions as much as phases), interleaved under the lane's reflecting
schedule with the arm-order guard adjudicating, 6 rounds × (4 warmup + 10
samples), 8 whole-page walks per timing window. The profiled page is a twin
whose canonical DOM is compared against the real `lt/page` at boot and was
**byte-identical** (1,202 elements, 58,474 canonical bytes); the run is fatal
on disagreement. **Diagnostic clock** (in-page `performance.now`), stated as
such: it attributes cost *between phases of one walk* and publishes no gated
figure. Timed walks run inside the runtime's public `render-body` door, so
the 141 reads and the intent lowering run exactly as a mount runs them.

**The census** (what the acceptance page is made of — the denominator for
every per-element claim):

| population | count |
|---|---|
| native elements | 1,202 (0 fragments, 0 boundaries — the page IS one boundary) |
| elements with **no attribute map** | 567 |
| elements with a `.class` **shorthand** | 924 |
| elements with a **declared** `:class` | 71 |
| props total | 1,075 — `data-testid` 290, `href` 219, `:key` 217, `class` 71, `on-click` 71, `src` 69, `alt` 69, `type` 69 |
| prop values | 1,003 strings, 71 intent vectors, 1 nil |
| children | 567 strings, 71 seqs, 69 numbers |

**The attribution** (pre-optimisation build; ms per whole-page walk, p50 over
60 samples; phase deltas are `local − ablation`, i.e. floors on each phase,
quoted against the local copy — the copy read 0.93× the shipping walk in the
same process, so shares are conservative):

| phase | delta ms/walk | ns/element | share of walk |
|---|---|---|---|
| **whole prop pipeline** (`convert-props`) | 1.075 | 894 | **67.5%** |
| — of which the **shorthand merge** alone | 0.625 | 520 | **39.2%** |
| — of which value conversion (`convert-prop-value`) | 0.238 | 198 | 14.9% |
| — of which intent lowering (incl. the per-prop regex) | 0.044 | 36 | 2.7% |
| `React.createElement` itself | 0.156 | 130 | 9.8% |
| the lazy body tail (card calls + sub reads realized inside the walk) | 0.294 | 244 | 18.4% |
| what the tag cache already saves (parse-fresh minus cached) | 0.619 | — | — |

Micro table, over the page's own literal roster (ns/op, same build):
`cached-prop-name` 49.3; `event-prop?` regex 34.7; reserved-set lookup 36.9 vs
9.6 as three `===` compares; `convert-prop-value` on the page's values 169.9
(the string chain); `createElement` minimal 77.6; `cached-parse` hit 29.1 vs
335.6 fresh.

**The headline is not the one the bead's candidate list guessed.** The
per-prop regex everyone suspected is 2.7%. The monster is the **shorthand
merge**: 924 of 1,202 elements carry their class as a `.class` tag shorthand,
and each one paid `merge-shorthand`'s map surgery — a `dissoc`/`assoc` copy
of the props map plus a re-conversion of the merged class through the full
per-prop path — to arrive at a `className` that is, for 853 of them, the
shorthand string verbatim. Second is value conversion, where a plain string
(1,003 of 1,075 values) proved itself *not* a fn, map, keyword, symbol or
collection — two of those the dear `native-satisfies?` checks — on its way to
an identity return.

## 2. The cheapening — pay where the page is, change nothing observable

All in `front/codec.cljs`; Surface B untouched elsewhere; no compiler, no
analyzer, no candidate ledger, no ViewCell graph; the shell's 2 hooks
untouched (the change is entirely inside the element walk).

1. **The prop cache's entries become `PropSlot`s** — the React name it always
   held plus the three classifications that are pure functions of the same
   literal: reserved slot, event position, ref slot. Same keys, same
   lifetime, same `__proto__` guard, still one entry per literal; one lookup
   replaces a name lookup, two reserved set-hashes and a per-prop regex. The
   event flag is minted from the NAME and gated on `keyword?` at the call
   site, so no spelling can poison another (witnessed both directions from a
   cold cache — see §3). A value nothing claims (not a ref, not a lowerable
   value at an event position, not a marked callback) no longer enters
   `lower-prop` at all; the values that do enter it meet the same branches
   they always met, because `lower-prop` re-derives its own classification.
2. **`convert-props` grows two lanes under the unchanged general path.** The
   propless element (567 of 1,202) emits its shorthand `id`/`className`
   directly — the general path's answer for `nil` props, minus the map
   machinery. The shorthand-class-only element (no literal
   `:class`/`:className`, no `:&`, no `#id`; ~357 more) runs the per-prop
   loop and writes `className` after it — the same slots in the same order
   the donor's re-`assoc`'d map produced, including the overwrite order for
   an exotic spelling (`:x/class`) that canonicalises onto the same slot
   (witnessed). Declared classes, `:&` remainders and `#id` shorthands take
   the donor's path byte-for-byte.
3. **Small knives the micro table licensed:** the literal `:key` is skipped
   in-loop (one keyword-identity test) instead of via a map-copying `dissoc`;
   `convert-prop-value` asks `string?` first; the reserved-name check is
   three `===` compares instead of a set hash, per tag and per prop.

**HD-004 posture.** This is codec-work caching in the ruling's own sense —
derived, per-literal, same elements, same behaviour, safe invalidation
(nothing varies: every cached field is a pure function of the literal). The
bead names "prop-map conversion, keyword→string, intent classification" as
the sanctioned targets. There is still no element cache, no props-object
cache, no template extraction, no hole plan and no DOM write; the fresh
props object per element per render stands.

**Prior art, taken and declined.**

- **Reagent** (`reagent2.impl.template`, the measured donor): caches tag
  parse, prop names and stable component heads; re-interprets hiccup fully
  every render. Taken — this change extends the *values* of the donor's own
  prop-name cache; the walk's shape is still the donor's.
- **UIx**: compiles markup to `createElement` at macro time. Declined by
  fence — the charter's hard line is a compiler/analyzer, and `defview`
  deliberately reads no body form.
- **re-frame.ui / Freehand's compiled tier**: static extraction with
  runtime-value holes. Same fence, same declination; Freehand's own
  runtime caches are stable-boundary/component caches — head caching, which
  Hicasso already has by construction (HD-016).
- **Replicant** (the bead's "static-skeleton caching" pointer): a runtime
  interpreter whose optimisation is update-time diffing over pure-data
  hiccup, plus opt-in value-keyed memoisation of unchanged subtrees.
  **Declined for the mount problem**, with the census as the reason: a
  mount is the *first* walk, so a value-keyed element cache only pays on
  subtrees repeated *within* one page. On the acceptance page the repeated
  handler-free subtrees are `[:i.ion-heart]` and `[:span "Read more..."]`
  (69 each) — under 12% of elements — while every distinct card would pay a
  deep hash and a failed lookup on top of its walk, event-position intents
  make equal hiccup frame-coupled (two frames rendering equal pages must
  not share handlers), and the cache would grow with distinct data for the
  life of the build. The profile prices the whole prop pipeline at 67.5%
  reachable *without* any of that; the skeleton cache buys the remainder's
  minority at an unbounded-memory cost. HD-006's no-element-memoisation
  line therefore stands untouched.
- **Amortisation across commits** (the bead's direction 3): the memo
  wrapper (HD-028, PR #7375) stops child re-walks on re-render; what
  mount prices is the first walk, which no cross-commit amortisation can
  reach. Within-mount amortisation IS the literal caches, extended here.

**In-process A/B** (same run, same interleaving; the `local` arm carries its
own frozen copy of the pre-change walk, prop-name cache included):

| arm | ms/walk p50 [min–max] | ns/element |
|---|---|---|
| old walk (`local`, frozen copy) | 0.7250 [0.6375 – 1.1125] | 603 |
| **new walk (`ship`)** | **0.4437 [0.3500 – 0.5875]** | **369** |
| new walk, mount-billed (lazy tail included) | 0.5625 [0.4500 – 0.7625] | 468 |

**0.61× the old walk — a 38.8% reduction** (43% adjusting for the frozen
copy's measured 0.93× fidelity offset in the pre-change run). Diagnostic
clock; the published question is §4's.

## 3. Correctness — witnesses and the mutation ledger

The full witness suite: **12,333 tests / 61,728 assertions, 0 failures, 0
errors** (`npm run test:cljs`), the codec's own 33 witnesses included —
shorthand folding, owned-literal merges, structural-slot spellings, `:ref`
reservation, event lowering through the walk, controlled-field markers, the
fresh-props-per-element pin and the `{:tags 1 :props 3}` cache-growth pin all
green over the new lanes.

New witnesses pin what the change added, and each was **proven able to go
red** by mutating the code it guards:

| mutation | expected failure | observed |
|---|---|---|
| M1 — mint the event flag from the raw key instead of the name (a symbol minted first would poison the keyword's lowering) | `the-cached-classification-is-spelling-aware…` | **RED** — 1 failure 1 error (`onClick` no longer a handler); green on restore |
| M2 — drop the `keyword?` gate on the cached event flag | (see below) | **GREEN by design** — `lower-prop` re-derives `event-prop?` internally, so the gate is a cost optimisation whose removal cannot change behaviour; the witness pins the observable (a symbol-keyed vector still converts as data) under both |
| M3 — write the fast lane's `className` before the loop instead of after | `the-shorthand-fast-lane-emits-what-the-donor-map-produced` | **RED** — `"a"` ≠ `"b"`: the overwrite order is load-bearing and watched; green on restore |

M2 is reported although it did not go red, because a mutation table that
lists only its reds is an advertisement: the gate's removal is behaviourally
masked by `lower-prop`'s own re-check, which is exactly the belt-and-braces
the lanes were designed to keep.

## 4. The clock of record — before and after

**Before** (`rf2-2rtt6.56`, commit `7885a7c148`, 2026-08-02T06:03Z, this
box): every measured blob byte-identical to this branch **except
`front/codec.cljs`** — the intervention — so the recorded rows are the same
pages on the same instrument on the same box.

| row | hicasso/uix before | verdict before |
|---|---|---|
| large-template (the acceptance shape) | **1.3053× [1.1044 – 1.4660]**, band 6.7% | **FAILS THE LINE** (margin 18.7%) |
| feed (the guard shape) | 1.1646× [1.0951 – 1.2445], band 6.7% | INSTRUMENT-LIMITED (straddles 1.10) |
| ordinary | 1.1248× [0.8939 – 1.3504], band 20.8%, ctl FAIL | INSTRUMENT-LIMITED |

**After** (this run, commit `8ccd9f4b41`, 2026-08-02T08:31Z, exit `0`, both
runs to completion, arm-order guard clean on every row):

`uix` run — the gated run:

| row | floor abs p50 | hicasso abs | uix abs | **hicasso / uix** | ctl-2x (pred) | band | **verdict vs 1.10×** |
|---|---|---|---|---|---|---|---|
| large-template | 8.858 ms | 10.976 ms | 9.096 ms | **1.2409× [1.0371 – 1.5371]** | 1.8470 [1.5270–2.1961] vs 1.9759 **PASS** | 13.7% | **INSTRUMENT-LIMITED** — the range straddles 1.10; not a pass |
| feed | 45.709 ms | 60.231 ms | 56.328 ms | **1.0875× [1.0100 – 1.1921]** | 1.9715 [1.8163–2.1879] vs 1.9943 **PASS** | 5.7% | **INSTRUMENT-LIMITED** — straddles 1.10 |
| ordinary | 2.238 ms | 2.816 ms | 2.639 ms | 1.1090× [0.8592 – 1.4058] | 1.2192 vs 1.7255 **FAIL** | 14.2% | INSTRUMENT-LIMITED, and the magnitude carries the control's failure |

`reagent` run — co-instrumented, never a second gate:

| row | hicasso / uix | hicasso / reagent | uix / reagent | band |
|---|---|---|---|---|
| large-template | 1.1646× [1.0019 – 1.4016] | **1.0303× [0.9103 – 1.1419] — STRADDLES 1.0** | 0.8872× | 12.6%, ctl PASS |
| feed | 1.1467× [1.0187 – 1.2784] | 1.1165× [0.9686 – 1.3168] | 0.9758× | 13.4%, **ctl FAIL** |
| ordinary | 1.1412× [0.8714 – 1.2622] | 1.0265× [0.7563 – 1.2201] | 0.9055× | 13.6%, **ctl FAIL** |

**Reading it honestly.**

- **The acceptance shape no longer fails the line.** Before, the whole range
  sat above 1.10 with an 18.7% margin clearing a 6.7% band — an unambiguous
  FAIL. After, the mean is 1.2409 (uix run) / 1.1646 (reagent run) and both
  ranges reach down through 1.10. The verdict is INSTRUMENT-LIMITED, **which
  is not a pass** — this run's bands (13.7% / 12.6% on that row, against
  6.7% before) cannot resolve the boundary, and the row straddles it.
- **The guard shape did not regress — it is the clearest single result.**
  The feed row is the cleanest instrument of the day (band 5.7%, control
  PASS, the same 6.7%-class as the before-run) and it moved from 1.1646×
  [1.0951–1.2445] to **1.0875× [1.0100–1.1921]**: the mean crossed below
  the 1.10 line for the first time on any census row.
- **On the pure interpreter shape, Hicasso now reads at stock-Reagent cost**:
  hicasso/reagent 1.0303× straddles 1.0 on large-template — the shape built
  to price the interpreter with the shell held at one boundary.
- **What remains above the line is no longer mostly the walk.** The
  large-template decomposition: hicasso in-page 3.300 ms vs uix 1.900 ms,
  taskNet 1.0613× — the surviving gap concentrates in the in-page window,
  which on this shape is element construction PLUS the 141-per-instance
  collector reads no one-boundary hook surface can spell (the row's stamp);
  the uix twin reads 5 coarse subscriptions. The walk term this bead
  attacked shrank by the A/B's 39%; the read-machinery term (cold
  `subscribe-once` per read at mount) is untouched by this bead and is the
  next candidate the decomposition points at.
- This run's box carried more scatter than the before-run's (three control
  failures in the reagent run, 13–14% bands on four rows; the before-run's
  large-template band was 6.7%). A quieter re-take could plausibly resolve
  the acceptance row against the line in either direction; nothing in this
  page claims it would land inside.

**Window discipline.** Windows announced on the bead before opening and
closed on the bead after; the first window (07:58Z) was **aborted before any
row published** — the build's log carried a shadow type-inference warning on
the new slot access — and the published run was re-taken at the warning-free
commit. Quiet-box gate per row: QUIET on attempt 1 or 2 everywhere (the one
attempt-1 refusal was the build's own heat decaying).

## Provenance (the published run)

| | |
|---|---|
| **Producing commit** | `8ccd9f4b41` on `worker/walkopt-y1jkm`, working tree clean — the stamped blobs are the commit's |
| **Reproduction** | `node implementation/freehand/test/re_frame/bench/hicasso/shapes/census_clock_run.cjs` (datasets redirected via `C56CLOCK_DATA_DIR` to `data/censusclock-y1jkm/` so the `rf2-2rtt6.56` before-datasets stay intact) |
| **Build** | `:hicasso-bench` (`--config-merge` entry swap), `:advanced`, `goog.DEBUG false`, cache cleared per `rf2-2rtt6.20`; 0 warnings |
| **Runtime** | `HeadlessChrome/147.0.7727.15` (Windows NT 10.0 x64), node `v24.13.0`, hardware-concurrency 24, device-memory 32 |
| **Design** | 6 rounds × 3 blocks × (4 warmup + 10 samples) per arm per row — the published shape, nothing overridden |
| **Clock / door / tare** | as the before-run: raw `TaskDuration` frame-settled, one door (`page.evaluate → C56CLOCK.sample`), plumb-tared per block |
| **Read-backs** | 0 unverified of 1,260 per row (7,560 total) |
| **Windows** | `uix` 2026-08-02T07:58:54Z – 08:27:21Z; `reagent` – 08:31:46Z |
| **Exit codes** | aborted first window: killed before any row (nothing published from it); published run exit `0`; arm-order guard: reportable on all six row-runs |

Blob hashes, read at the producing commit — **identical to the before-run's
on every measured file except the intervention**:

| file | blob |
|---|---|
| `…/front/codec.cljs` | `5a0b04733a33d1baa815b093f5b297e325aa6675` **(the change under test; before: `92942efb0bc9…`)** |
| every other measured blob | byte-identical to the before-run's table in `census-real-clock-rows.md` (`census_clock_arms/app/run`, `model`, `card`, `large_template`, `feed`, `ordinary`, `arm1/runtime`, `arm1/lang`, `lane`, `substrate/spine`) |

Compact datasets: `implementation/freehand/test/re_frame/bench/hicasso/data/censusclock-y1jkm/`.

## 5. How this composes with the pending memo wrapper (#7375)

The two sit at different layers and compose by construction. The memo
wrapper (`memoize-boundary!`) wraps the **boundary head**: it decides whether
a boundary's body runs again on a parent's re-render. This change cheapens
the **walk inside a body run** — every native element the body interprets,
whichever mount or re-render caused the run. On a mount the wrapper does
nothing (there is no previous props map) and this change is the whole
effect; on a bailed-out re-render the wrapper stops the walk before it
starts and this change is moot; on a non-bailed re-render both apply. No
shared state: the wrapper lives on the head and `boundary-element`'s
`element-type` lookup; this change lives in `convert-props`/`convert-entry`
and the caches. The one textual meeting point is `front/codec.cljs` itself —
disjoint regions (boundary-heads section vs the prop pipeline), so the held
PR rebases over this cleanly (or vice versa).

## 6. The hook budget, and the fences walked

- ≤2-hook shell: untouched — no change outside the codec's element walk;
  `hook_budget_dom_cljs_test` green in the suite run.
- `subscribe` closes over the read set alone: untouched.
- No compiler, no analyzer: `defview` still reads no body form; every new
  cache key is a runtime literal.
- reagent-slim's codec: **not forked** — `reagent2.impl.template` is
  untouched; the change is to Hicasso's own extraction, which is the surface
  the bead's candidate list names, and it is a cache *at* the codec of the
  kind the bead pre-classifies as in scope.
- Examples/testbeds: untouched. `.beads` untouched.
