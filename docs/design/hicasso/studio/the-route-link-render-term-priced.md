# The route-link render term, profiled and priced

`rf2-2rtt6.54` migrated the census pages' anchors to `route-link`, and
`rf2-6c237`'s clock re-take then measured what that cost: **8.21 µs per link at
mount** — 207 links on the acceptance page, 900 on the feed — and both rows
moved back above the line (acceptance 1.2409× → 1.4759×, feed 1.0875× →
1.3311×). The bead asks one question first, and the answer decides everything
after it: **is 8.21 µs necessary?**

It is not. Roughly half of it was work the synthesis performed and discarded,
and **77% of it was not Hicasso's at all** — it was routing's, on a path every
re-frame2 link surface runs.

Bead **`rf2-cno31`**. The standard is **`rf2-2rtt6.1`** (mount ≤ 1.10× direct
UIx-on-subs, floor-normalised, same run, raw `TaskDuration`); the before rows
are `rf2-6c237`'s ([the cold-read page](the-cold-read-mount-term.md) §6).

> **THE CANONICAL MOUNT WITNESS IS M1 AND STAYS M1.** These rows corroborate
> the amendment's line on census-real screens exactly as the before rows did;
> nothing here re-baselines the canonical witness, and the ruling on any verdict
> below is the operator's (`rf2-2rtt6.1`), never this page's.

## 1. The profile — where 8.21 µs actually goes

The walk page's lesson was that the suspected cost was 2.7% of the walk while
the real one was 39%, so this bead's first act is a decomposition, not a
remedy.

**Instrument.** Two diagnostic probe apps on the lane's generic `run.cjs`
driver, both riding `:hicasso-bench` (`:advanced`, `goog.DEBUG false`): they
build the acceptance page's own 207 link addresses from the model's own seed
(69 cards × two profile links + one article link), run them through the
runtime's `render-body` door exactly as `route-link` performs them, and
interleave the stages as arms under the lane's reflecting schedule with the
arm-order guard adjudicating — 5 rounds × (4 warmup + 8 samples), 4 whole
207-call passes per timing window. **Diagnostic clock** (in-page
`performance.now`), stated as such: it attributes cost *between stages of one
synthesis* and publishes no gated figure.

> **The instrument's floor.** Chrome clamps `performance.now` to 100 µs and a
> sample times four passes, so the quantum is **0.12 µs/call**. Any arm reading
> 0.12 is at ONE tick and is indistinguishable from zero; it means "≤ 0.12",
> never "0.12". Three arms below sit there and are marked.

### 1a. The whole seam (`link_decomp_probe_app`)

Guard **reportable**; positive control predicted 2.925×, measured 2.900×
[2.600 – 4.675] — **PASS**.

| stage | µs/call | share of the seam |
|---|---|---|
| `:floor` — the loop and the `aget` | 0.00 | — |
| `:cedn` — `canonical-bytes` on ONE param value | 0.36 | 5.1% |
| `:lookup` — `registrar/lookup :route` | 0.12 (one tick) | ≤1.7% |
| **`:route-url` — the whole path synthesis** | **4.71** | **66.6%** |
| `:strategy` — `url-strategy-for-frame-id` | 0.72 | 10.2% |
| **`:link-model` — the whole seam** | **7.07** | 100% |

`:cedn` and `:lookup` are not additional stages — they are stages *inside*
`route-url`, priced separately so the decomposition has named parts rather than
one lump. **The seam's two big terms are routing's**: `route-url` (66.6%) and
the strategy consult (10.2%) are 77% of the call, and the remaining 1.64 µs is
the seam's own glue (address extraction, the dispatch payload, the native-anchor
verdict).

**So the cost is the seam's, not ours.** That is the first finding and it
decides where the remedy goes: fixing it in Hicasso's `route-link` would be
working around routing on behalf of one consumer, while every other link
surface — `rf/route-link`, `ui/route-link`, Freehand's `v/route-link` — kept
paying.

### 1b. Inside `route-url` (`link_inner_probe_app`)

`route-url`'s stages are `defn-` private, so this probe replicates each one
VERBATIM — the same expression on the same data — and prices them against an
`:ideal` floor: what building `/profile/jane` costs when only the string is
built. The anchor arm (`:route-url`) keeps both probes on one scale.

Guard **reportable**; positive control predicted 1.800×, measured 1.762×
[1.650 – 13.150] — **PASS** (the wide upper edge is one outlier round; the
median is the quoted figure).

| stage | µs/call | share of `route-url` |
|---|---|---|
| `:ideal` — `(str "/profile/" (encodeURIComponent v))` | 0.12 (one tick) | ≤2.8% |
| **`:emit-loop`** — the per-character pattern walk | **1.69** | **38.9%** |
| `:addr-keys` — the address-key reject scan + the empty-query sort | 0.60 | 13.8% |
| `:set-build` — `uncaptured-param-keys`' per-call keyword set | 0.48 | 11.0% |
| `:cedn` (from 1a) — the fail-closed URL-scalar guard | 0.36 | 8.3% |
| `:lookup` (from 1a) — the route-meta read | 0.12 | ≤2.8% |
| **`:route-url`** | **4.35** | 100% |

**75% attributed, and there is no single culprit.** The largest term is 38.9%
and the next three are 8–14% each. That matters for the remedy: this is not one
mistake, it is five ordinary ones, and the honest fix removes five ordinary
amounts of work rather than hunting for a smoking gun that is not there.

Two suspects the bead named were **acquitted by the measurement**. The
fail-closed CEDN guard — which builds a value's whole canonical byte string and
throws it away — looked like the pathology and is 8.3%. The route-meta lookup
looked like a per-render registry hit and is at the instrument's floor.

### 1c. And the memo the bead proposed is declined, on the evidence

A per-frame memo on the seam call is the obvious candidate and it is the wrong
one here, for two reasons the profile makes plain:

- **The regression is a MOUNT regression, and a memo is empty at mount.** The
  8.21 µs is paid 207 times on a page's first render, when nothing is cached.
  A memo pays back on re-render, which is not where the rows moved.
- **On this bench a memo would flatter us, and the reason is the bench.**
  `model.cljs` draws its bylines from **four** authors, so 138 of the
  acceptance page's 207 links carry one of four addresses. A memo would hit
  ~65% of them at mount — and that number is a property of the fixture, not of
  Conduit, whose feed has as many authors as it has articles. Publishing a
  speed-up that large from a four-element pool would be the measurement
  artefact this programme exists to refuse.

Making the synthesis itself cheaper has neither problem: it pays on the first
render, it pays for a unique address, and it pays for every link surface in
re-frame2 rather than for the census.

## 2. The remedy — five specialisations, no new state

Every change below is **a specialisation, never a second implementation**: the
same answers by a cheaper route, with no cache, no invalidation surface, and no
new stored state to go stale. (There is therefore nothing here of the frozen-row
family — the question "what invalidates it?" has the answer "nothing, because
nothing is remembered".)

| # | where | what it stops doing |
|---|---|---|
| 1 | `registry/route-url` emission | A pattern with **no optional group** takes a walk that reads each literal RUN in one `subs` instead of one character at a time — the old loop spent a one-character string and a vector `conj` per literal character and an `apply str` over the result. `/profile/:username` cost nine of each before reaching its single param. The general loop is **untouched** and still runs for every pattern that carries a group. |
| 2 | `registry/route-url` address gate | The reject scan walks the address's entries (`reduce-kv`) instead of allocating a `keys` sequence and a lazy `remove` over it. The canonical-order sort survives, on the failure leg, where a throw is already the outcome. |
| 3 | `registry/route-url` query gate | An address with **no query** short-circuits the canonical-order sort it has nothing to order. Every query-bearing address takes the identical sorted path. |
| 4 | `registry/uncaptured-param-keys` | Membership is tested against the capture NAMES directly instead of interning a keyword per name and allocating a hash-set per link. Namespaced and non-keyword keys stay uncaptured, exactly as the set had them. |
| 5 | `registry/assert-url-value!` | The guard asks a DOMAIN question and answered it by building a CEDN-1 token string and discarding it. For string / keyword / symbol / boolean the answer is decidable by TYPE — `identity/encode` can never reject any of them — so those four answer immediately. Everything else (integers, whose safe-range check is the whole point; UUIDs; instants; host objects) takes the identical encode-and-catch path. |
| 6 | `strategy/url-strategy-for-frame-id` | Reads `frame/frame-config` rather than merging a whole `frame-meta` map to reach one key. `rf2-ecb4sx` removed this consult's per-call VALIDATION and left its per-call ALLOCATION. |

`re-frame.frame/frame-config` is the one new surface: the narrow sibling of
`frame-meta`, for consult points that need one config key on the render path.
`frame-meta` remains the canonical `:rf/frame-meta` reflection shape and the
only shape tools may depend on; the lifecycle fields it merges are disjoint
from the config keys, so for any config key the two answer identically.

**The measured result** (`link_decomp_probe_app` re-run at the changed blobs,
same box, same session shape; guard **reportable**, control predicted 1.400×
measured 1.400× [1.300 – 1.975] **PASS**):

| stage | before | after | |
|---|---|---|---|
| `:cedn` (unchanged — the scale control) | 0.36 | 0.36 | — |
| `:lookup` (unchanged — the scale control) | 0.12 | 0.12 | — |
| `:route-url` | 4.71 | **2.29** | **0.49×** |
| `:strategy` | 0.72 | **0.12** (one tick) | **≤0.17×** |
| **`:link-model`** | **7.07** | **3.38** | **0.48×** |

The two arms nothing touched read **identically** before and after, which is
what says the two runs are on one scale rather than two.

**The seam call halved.** Against the bead's published 8.21 µs — taken in
`rf2-6c237`'s session, not this one, so the honest comparison is the 7.07 → 3.38
within-session pair — the term is now ~3.4 µs: **1.70 ms → ~0.70 ms** on the
acceptance page's 207 links, **7.4 ms → ~3.0 ms** on the feed's 900.

## 3. The mutations

`implementation/routing/test/re_frame/routing_url_emission_equivalence_test.clj`
is what makes the "specialisation, not reimplementation" claim falsifiable: the
emitted path for both walks, the `route-url ∘ match-url` prism round trip, the
boundary scanner, every fail-closed class, the query orders, and
`frame-config` ≡ `frame-meta` on `:url-strategy`. 86 assertions.

Three mutations were run against the shipped code and reverted:

| mutation | result |
|---|---|
| `\{` deleted from `literal-run-end`'s boundary set | **RED** — 1 failure |
| `\:` deleted from the same set (every pattern emits its own text where the value belongs) | **RED** — 27 failures across the emission table and the prism |
| the fast walk's optional-group bail turned into a skip | **RED** — 10 failures: `/docs{/:section}?` emits `/docs/api?`, an elided group raises `:rf.error/missing-route-param`, and the chain pattern round-trips to the wrong route |

**The third mutation is why the walk decides for itself.** It was first written
as an `(empty? groups)` gate computed outside the loop, and under that shape the
same mutation does not fail — **it hangs**: `literal-run-end` stops at `{` and
returns the cursor unmoved, so the loop spins and the suite never completes. It
had to be killed by hand at 722 s of CPU. The shipped walk instead handles `{`
/ `}` in a branch of its own `cond`, returning nil and handing the whole
emission to the general loop, so **every branch either advances the cursor or
returns** and no pattern can spin it. It also no longer depends on a `:groups`
map that a route-meta installed outside `reg-route` could disagree with — a
hazard the neighbouring code already defends against by name.

## 4. The twin fairness gap, and how it is closed

`rf2-2rtt6.54`'s own worker flagged this when it landed and the size is now
measured: the hand-written UIx and Reagent twins spelled
`(str "#/profile/" username)` — a literal href, hand-built — while the
candidate resolved the same destination through routing's route table. **Part
of the 1.33× was the candidate doing real work no arm it was measured against
was doing.** A row whose arms do different work is not a row.

**The twins now do it too.** They take the SAME two published seams the
candidate's `route-link` takes — `:routing/link-model` for the href and the
dispatch payload, `:routing/activate-link!` for the click — because those seams
exist for exactly this consumer class: a view that must reach route-link
semantics without statically requiring routing. It is what a competent UIx or
Reagent author writes on re-frame2 when the destination is a ROUTE rather than a
string, and it is why the census's real UIx counterpart would pay this too.

**Three syntheses per card, including the two identical author addresses.** The
candidate's card calls `route-link` three times and synthesises three hrefs; a
twin that computed the author's href once and spent it twice would be doing two
thirds of the arm's routing work and calling the result a comparison.

**Two asymmetries are kept, and both run AGAINST the candidate** — the same
posture the merged class strings already take in that file:

- the twins resolve the two late-bound hooks **once**, at namespace load; the
  candidate's `route-link` resolves `:routing/link-model` per anchor per render.
  Hoisting is what an author writes; per-call resolution is a property of the
  candidate's own spelling and stays in the candidate's row.
- the twins call the seam and inline the `<a>` themselves rather than mounting
  `rf/route-link`, which is a registered Reagent view and would add a component
  per anchor, three per card. Inlining is the cheapest faithful spelling of the
  same routing work, and a control should have the cheapest one.

**The floor is untouched, deliberately.** It is the calibrator, not a rival —
no frame, no subscription, no handler indirection — and a floor that resolved
routes would be a fourth arm rather than a floor.

The DOM did not move: the parity gate reports the three non-control arms'
canonical DOM **IDENTICAL** at stress AND small size, and is still able to
answer false.

## 5. The re-taken rows

The gated pair is **hicasso / direct UIx-on-subs, same run, raw `TaskDuration`,
floor-normalised, plumb-tared**. Before rows are `rf2-6c237`'s.

`uix` run — **the gate**:

| row | before | **after** | band | verdict |
|---|---|---|---|---|
| large-template (acceptance) | 1.4759× [1.3281 – 1.6302], band 6.1% | **1.1884× [1.1223 – 1.2660]** | 7.0% | **FAILS THE LINE** — whole range above 1.10, margin 8.0% clears the band |
| feed | 1.3311× [1.2357 – 1.4195], band 3.9% | **1.0737× [0.9669 – 1.1859]** | 6.7% | **INSTRUMENT-LIMITED** — the range straddles 1.10, which is NOT a pass |
| ordinary | 1.2097× [1.0751 – 1.3274], band 10.0% | 1.1698× [0.9671 – 1.5014] | 15.8% | INSTRUMENT-LIMITED (straddles 1.10) **and the control FAILED** |

`reagent` run — co-instrumented, **never a second gate**:

| row | hicasso / uix | hicasso / reagent | uix / reagent |
|---|---|---|---|
| large-template | 1.1566× [0.9258 – 1.3686] | 1.0436× [STRADDLES 1.0] | 0.9064× [STRADDLES 1.0] |
| feed | 1.1814× [1.1121 – 1.3159] | 1.1438× [STRADDLES 1.0] | 0.9685× [STRADDLES 1.0] |
| ordinary | 1.1334× [STRADDLES 1.0] | — | — |

**Absolutes, `uix` run** (p50 raw `TaskDuration`, tared; `= taskNet + in-page`):

| row | floor | hicasso | uix | ctl-2× |
|---|---|---|---|---|
| large-template (1,202 el) | 12.010 ms (tared 10.825) | 16.015 = 8.254 + 6.100 | 13.742 = 8.246 + 3.900 | 21.022 |
| feed (5,129 el) | 43.671 ms (tared 40.452) | 64.110 = 34.029 + 26.150 | 60.068 = 34.054 + 21.300 | 84.289 |

**B / E / Q, per row:** large-template **B=1** (hicasso 141 per-instance reads,
uix 5 coarse — the read-shape asymmetry the roster states on every stamp),
E=1,202, Q — grain 0.905 ms; feed **B=301**, 603 per-instance reads on BOTH
arms (the cleanest gated pair in the file), E=5,129, grain 1.836 ms; ordinary
B=7, E=51, and it sits near this door's own floor, which is why its control
could not hold.

**Positive controls:** large-template **PASS** 1.8392× [1.6188 – 2.0111]
against the arithmetic prediction 1.9759× (strict — every block inside the
band); feed **PASS** 1.9924× [1.8057 – 2.1729] against 1.9943×; ordinary
**FAIL** 1.2056× [0.9188 – 1.6452] against 1.7255× — the 51-element page is
below what this door can resolve, and the row is published carrying that
failure rather than quoting a magnitude the instrument did not earn.

**Read-backs:** 0 unverified of 1,260 per `uix`-run row, 0 of 1,512 per
`reagent`-run row.

**Reading it honestly.**

- **Both terms the bead named came off the rows, and the acceptance row moved
  further than the whole `route-link` term is worth.** 1.4759 → 1.1884 is a
  0.288 move against a term the migration had cost 0.235; the remaining
  difference is the routing remedy also making the *candidate's* own links
  cheaper on top of the arms being levelled. Feed 1.3311 → **1.0737** is a
  0.257 move, and its mean is now **below the 1.10 line and below its own
  pre-migration 1.0875×**.
- **Neither is a pass, and the page says so.** The acceptance row FAILS the
  line — its whole range is above 1.10 and its 8.0% margin clears its 7.0%
  band, so the instrument resolved the boundary and the answer is no. The feed
  row's range straddles 1.10: the mean is below the line but the run cannot
  resolve the boundary, and a straddling range is INSTRUMENT-LIMITED, never a
  pass. The ordinary row additionally carries a failed control.
- **`taskNet` says the same thing it said before.** 1.0140× on acceptance,
  0.9752× on feed — the frame half of the work is indistinguishable between the
  arms, and the whole difference remains in-page script. That was the finding
  the cold-read page made and this run does not disturb it.
- **The rows are now a comparison of substrates again.** Every arm resolves the
  same destinations through the same route table (§4), so what is left in the
  acceptance row's 1.1884× is the interpreter and the collector, which is what
  the bar is for.

## 6. The hook budget, and the fences walked

- **`route-link` is still a plain function.** No boundary, no hook, no
  subscription read — the ≤2-hook shell and every page's boundary arithmetic
  are untouched by links, exactly as `front/route_link.cljs` promises. Nothing
  in this bead added state to it; the whole remedy is behind the seam it calls.
- **The `[::h/prevent …]` composition survives, and is witnessed.**
  `front/route_link_cljs_test.cljs` and `shapes/route_link_dom_cljs_test.cljs`
  own the roster and the click witnesses; both are green in the suite run.
- Surface B untouched; no compiler, no analyzer, no candidate ledger; the codec
  is still reagent-slim's.

## 7. Provenance

| | |
|---|---|
| **Producing commit** | `08344cb500` on `worker/linkterm-cno31`. The working tree carried no uncommitted change to any measured file — the only untracked paths were a pinned clj-kondo binary, a PR draft and this run's own dataset, none on the measured path |
| **Reproduction** | `C56CLOCK_DATA_DIR=…/data/censusclock-cno31 node implementation/freehand/test/re_frame/bench/hicasso/shapes/census_clock_run.cjs` — both adapter runs, all three rows, nothing overridden |
| **Build** | `:hicasso-bench` (`--config-merge` entry swap; `implementation/shadow-cljs.edn` untouched) — `:advanced`, `goog.DEBUG false`, lane cache cleared per `rf2-2rtt6.20`. **0 warnings**, 201 files |
| **Runtime** | `HeadlessChrome/147.0.7727.15` (Windows NT 10.0 x64), node `v24.13.0`, hardware-concurrency 24, device-memory 32 |
| **Design** | 6 rounds × 3 blocks × (4 warmup + 10 samples) per arm per row — 18 blocks, the published shape |
| **Clock / door / tare** | PUBLISHED: `Performance.getMetrics` raw `TaskDuration`, frame-settled. DIAGNOSTIC: `taskNet` + the in-page window on the same samples. One door for every arm including the plumb tare (`page.evaluate → C56CLOCK.sample`), tare subtracted from every figure |
| **Guard / band** | arm-order guard tolerance 0.35 — **reportable on all six row-runs**; band ceiling 35%, and every row's band was well inside it (6.7–15.8%) |
| **Read-backs** | 0 unverified of 1,260 (`uix` rows) and 1,512 (`reagent` rows) |
| **Windows** | announced on the bead before opening and closed after. `uix` `2026-08-02T18:14:02Z – 18:17:26Z`; `reagent` `18:17:26Z – 18:21:10Z`. Quiet gate (8 × 1 s < 30% CPU) **QUIET on attempt 1 for all six rows** |
| **Exit codes** | published run `0`. **The first attempt was REFUSED and published nothing**: the box would not go quiet before `uix/feed` across five attempts (CPU runs in the 22–46% range against the 30% gate) while sibling workers held the machine, and the runner discards a partial run rather than reporting it. The `uix/large-template` row of that attempt did take, and it is not quoted here |

Blob hashes, read at the producing commit — the three marked are this bead's
changes, and the census pages themselves are byte-identical to `rf2-6c237`'s:

| file | blob |
|---|---|
| `…/shapes/census_clock_arms.cljs` | `de6bacfad4` **(the twin fairness fix — §4)** |
| `implementation/routing/src/re_frame/routing/registry.cljc` | `96de354972` **(the emission + guard specialisations — §2)** |
| `implementation/routing/src/re_frame/routing/strategy.cljc` | `e16b469cd5` **(the strategy consult — §2)** |
| `implementation/core/src/re_frame/frame.cljc` | `6c950ea566` (`frame-config`) |
| `…/shapes/census_clock_{app,run}` | `b077ad6a11` / `1f2a7e1c8b` |
| `…/shapes/model.cljs` | `7f4043dc09` |
| `…/shapes/{card,large_template,feed,ordinary}.cljs` | `07458921f7` / `f575b78429` / `589291891f` / `a1d7005d74` |
| `…/front/route_link.cljs` · `…/front/codec.cljs` | `e093d72932` · `5a0b04733a` |
| `…/arm1/runtime.cljs` · `…/lane.cljs` | `d6067d5a41` · `0642815dc2` |

Compact datasets:
`implementation/freehand/test/re_frame/bench/hicasso/data/censusclock-cno31/`.
