# Our walk, decomposed against Reagent's own

`rf2-2rtt6.63` is the outcome bead for the mount deficit, and it opens on a
premise: the whole clock deficit follows the interpreter, stock Reagent's own
interpreter proves a runtime walk can be nearly free, so the question is **why
ours is slower than Reagent's and how much of HD-004's budget closes it**.

Nobody had ever put the two side by side. `rf2-y1jkm` profiled our walk against
a *frozen copy of our own older walk*; the census clock rows compare whole
mounts, where two-thirds of the window is shared style, layout and paint. This
page runs both interpreters — and the lineage donor, `reagent-slim` — over one
witness value in one process, attributes the per-element delta to named stages,
and then spends the budget where the attribution points.

**The premise did not survive the measurement.** Our walk was already at stock
Reagent's cost before this bead touched anything (1.0588×), and the arm the
donor rungs actually ride — `reagent2.impl.template` — is **1.90× stock
Reagent**. After two cheapenings the attribution convicted, our walk reads
**0.9636× stock Reagent**. There is no interpreter gap left to close, and that
is the answer the P2 fork ruling needs, in the direction nobody had checked.

> **DIAGNOSTIC, not published.** Every figure here is an in-page
> `performance.now` over eight whole-page walks per sample. It attributes cost
> BETWEEN interpreters and BETWEEN stages of one walk. It is **not** the clock
> of record, no figure here is a gate row, and the mount verdict remains
> `rf2-2rtt6.1`'s to issue against `census_clock_run.cjs`.

## 1. The witness, and why the route-link term cannot reach it

The page is `walk_profile_app`'s twin of the acceptance shape — the census's
1,202-element one-boundary page, guarded by that file's own **fatal**
canonical-DOM parity gate against the real `lt/page`, so the lane carries one
twin and not two. Both runs recorded `twin parity OK — 1202 elements, 58474
canonical bytes`.

`rf2-cno31`'s route-link term (8.21 µs/link, 207 links on this page) is
**structurally absent from this instrument rather than subtracted from it**: the
witness is realized ONCE, outside every timed window (`codec/realize-deep`), so
every `route-link` href is a plain string before any clock starts. No timed
window here contains a `route-url` synthesis. Windows were coordinated with
`worker/linkterm-cno31` on both beads before either run opened.

**THE PLAIN WITNESS.** Hicasso's markup carries intent VECTORS at its event
positions; Reagent has no such surface and would `clj->js` them. An arm doing
different work is not an arm (`rf2-2rtt6.62`), so the compared value is the
realized page with every event position replaced by one shared plain function —
legal, and identical work, for all three interpreters. A fourth arm,
`hicasso-native`, then walks the page's OWN markup, so the authoring surface's
term is named rather than hidden inside the comparison.

### Workload matching is gated, not asserted

Every arm's element tree is rendered into a fresh container and its canonical
DOM read (`lane/canonical`, attribute names sorted). Identical on both runs:

| arm | elements | canonical bytes | vs hicasso |
|---|---|---|---|
| `hicasso` | 1,202 | 58,474 | — |
| `hicasso-native` | 1,202 | 58,474 | **identical** |
| `slim` | 1,202 | 58,529 | differs, 55 bytes |
| `reagent` | 1,202 | 58,529 | differs, 55 bytes |

**The 55 bytes are fully explained and are whitespace only.** The census card
writes `:class (cond-> "" favorited (str " active") pending? (str " optimistic"))`,
so an unfavourited, non-pending card carries a declared class of `""`. Hicasso's
`class-names` drops an empty class (`(when (seq class) class)`); Reagent's treats
`""` as truthy and joins it, producing a trailing space in `className`. 55 of the
69 cards are in that state. Zero elements differ, zero attributes differ, and the
difference costs *Reagent* one extra `str` join — so it is not a workload
advantage to us.

## 2. The arms — ours, the donor's, and stock Reagent's

Design: 4 arms × 6 rounds × (4 warm-up + 10 samples), 8 whole-page walks per
timing window, the lane's reflecting schedule, the arm-order guard adjudicating.
**Every arm runs inside the body door** (`rt/render-body`) including the two that
do not need it, because timing a local arm against a foreign one compares call
conventions as much as phases (`rf2-2rtt6.32`); the clock starts inside the door.

**BEFORE** — codec blob `5a0b04733a`, guard verdict **reportable on every arm,
both by predecessor and by phase, all within 10%**:

| arm | ms/walk p50 [min – max] | ns/element |
|---|---|---|
| `reagent` (stock 2.0.1) | 0.6375 [0.6000 – 0.9000] | 530 |
| **`hicasso`** | **0.6750 [0.6250 – 0.8000]** | **562** |
| `hicasso-native` (intent markup) | 0.7250 [0.6625 – 0.9000] | 603 |
| `slim` (`reagent2.impl.template`) | 1.2125 [1.1125 – 1.4500] | 1,009 |

`hicasso/reagent` **1.0588** · `hicasso/slim` 0.5567 · **`slim/reagent` 1.9020**

**AFTER** — codec blob `0304f489bb`, instrument blob byte-identical to the
before run's, guard verdict **reportable**:

| arm | ms/walk p50 [min – max] | ns/element |
|---|---|---|
| `reagent` (stock 2.0.1) | 0.6875 [0.5000 – 122.6625] | 572 |
| **`hicasso`** | **0.6625 [0.4750 – 1.2875]** | **551** |
| `hicasso-native` (intent markup) | 0.7125 [0.5125 – 16.9000] | 593 |
| `slim` (`reagent2.impl.template`) | 1.1875 [0.9000 – 1.9750] | 988 |

`hicasso/reagent` **0.9636** · `hicasso/slim` 0.5579 · **`slim/reagent` 1.7273**

**Read the ratios, not the absolutes, across the two runs.** This is a shared
fleet box (§6) and the two runs sat in different pockets of it; the arm ratios
are same-run interleaved against donors measured in the same windows, which is
what makes them comparable. The `reagent` and `hicasso-native` maxima in the
after run (122.66 ms, 16.90 ms) are single scheduler stalls; the p50s are over 60
samples and the guard passed on both partitions.

### What the arms say

- **Our walk was already at stock Reagent's cost.** 1.0588× before this bead
  changed anything — a 32 ns/element absolute difference on a 530 ns/element
  walk. The bead's premise, that ours is the slower interpreter, is refuted by
  direct measurement.
- **The donor is the slow one.** `slim/reagent` reads **1.90× / 1.73×** across
  the two runs. The donor rungs and the pre-Hicasso candidate all ride
  `reagent2.impl.template`'s `:f>` walk, and this is where their 1.33–1.5×
  comes from. Hicasso extracted that plumbing and then cheapened it: we read
  **0.56×** the donor we were built from.
- **Hicasso's own authoring surface costs 41 ns/element** (562 → 603 before,
  551 → 593 after) — 71 intent vectors lowered across 1,202 elements, about 7%
  of the walk. That is the price of the intent surface, stated rather than
  buried.
- **After the two cheapenings, we read below stock Reagent** — 0.9636×.

> **Two of these bullets no longer describe `main`, and the rows above are left
> exactly as taken.** `reagent-slim` has since been fixed twice against this very
> instrument, so `slim/reagent` **1.90× / 1.73×** and the **0.56×** that follows
> from it are figures about a donor the programme has since repaired; and the
> codec has itself moved on. Both are re-taken in one process on current `main`
> in [the 2026-08-04 re-take](#2026-08-04-re-take-of-the-arms-on-current-main).

## 3. The stages — absolute ns, because a ratio cannot be read against a frame

The bead requires absolute per-element costs: two-thirds of a mount window is
shared frame work, so a 1.49× TOTAL implies the walk slice is several times
Reagent's in absolute terms, and only absolutes can say. Each row is the same
primitive, ours and each donor's, over the page's own roster (1,202 elements,
1,489 prop occurrences, 1,908 children of which 567 strings).

**A stage table is read WITHIN a run, never across runs.** The micro tables run
after the arms, so they sample a different moment of a shared box; stock
Reagent's own `string-child` reads 8.8 in one run and 20.3 in the other with
identical code. The within-run column differences are the finding.

**`↳` marks a stage measured INSIDE the row above it**, and the rows are ordered
so the nesting is visible.

**BEFORE** (ns/op):

| stage | hicasso | slim | reagent | ours − Reagent's |
|---|---|---|---|---|
| tag lookup (cached) | 21.2 | — | 29.5 | **−8.3** |
| **whole prop pipeline / element** | **209.2** | — | **182.2** | **+27.0** |
| ↳ prop-name lookup (cached) | 21.5 | 49.4 | 24.5 | **−3.0** |
| ↳ prop-value conversion | 6.7 | 8.1 | 7.1 | −0.4 |
| per-element tag hook | 9.2 | — | 6.7 | +2.5 |
| **`as-element` on a string child** | **33.5** | 7.9 | **8.8** | **+24.7** |
| `createElement` (shared floor) | 54.9 | 54.9 | 54.9 | — |

**Two rows are NESTED, and the sum must not count them twice.**
`convert-props-hicasso` and `convert-props-reagent` invoke each interpreter's
**whole** `convert-props` over every element, and that pipeline already performs
the cached prop-name lookup and the value conversion for every prop it emits.
The two are priced separately so the pipeline has named parts — the same
relation `:cedn` and `:lookup` have to `route-url` on the
[route-link page](the-route-link-render-term-priced.md) — and, like those, they
are **shares of the row above rather than stages beside it**. The tag lookup is
not nested: the instrument precomputes the parsed tag outside the pipeline's
window precisely so that row prices one thing.

The non-overlapping sum, weighted by the roster:

| stage | ours − Reagent's | × roster | µs/walk |
|---|---|---|---|
| **whole prop pipeline** | +27.0 ns | 1,202 elements | **+32.5** |
| **`as-element` on a string child** | +24.7 ns | 567 strings | **+14.0** |
| per-element tag hook | +2.5 ns | 1,202 elements | +3.0 |
| tag lookup | −8.3 ns | 1,202 elements | −10.0 |
| *prop-name lookup* | *−3.0 ns* | *1,489 props* | *nested — not added* |
| *prop-value conversion* | *−0.4 ns* | *1,489 props* | *nested — not added* |
| **named stages** | | | **+39.5** |
| **observed whole-walk delta** | | | **+37.5** |

**The named stages sum to 105% of the observed delta, and this page previously
said 92%.** That figure came from adding the two nested rows to the pipeline
that contains them; because both are negative, the double-count pulled the total
*down*, from +39.5 µs to +34.4 µs, and made an overshoot look like a shortfall.
There was never a measured 8% residual to report.

**The overshoot is the instrument's, not a missing stage — and it is why no
closure percentage belongs here at all.** Read against the limits this section
already declared, 5% is nothing: these absolutes are ceilings taken on a box §7
declares was not quiet. Run the same arithmetic over the AFTER table and the
named stages come to **+5.6 µs** against an observed delta of **−25.0 µs** —
they do not even agree in sign. A table with that much room in it can say which
stages are large and roughly how large; it cannot be totalled to the percentage
point in either direction.

**So the table is read as an attribution and not as an accounting.** What it
supports is that the prop pipeline and the string branch are the two terms that
matter, each worth tens of µs per walk where the rest are worth single digits —
and that is what the two convictions below rest on, together with their own
before/after tables in §4a and §4b. Neither conviction, and neither landed
optimization, depends on this sum. The independent evidence for the page's
conclusions is §2's arm ratios, which are same-run interleaved and untouched by
any of this.

**AFTER** (ns/op, same instrument):

| stage | hicasso | slim | reagent | ours − Reagent's |
|---|---|---|---|---|
| tag lookup (cached) | 20.4 | — | 34.9 | −14.5 |
| **whole prop pipeline / element** | **207.6** | — | **186.8** | **+20.8** |
| ↳ prop-name lookup (cached) | 16.8 | 53.4 | 26.2 | −9.4 |
| ↳ prop-value conversion | 8.1 | 23.5 | 10.7 | −2.6 |
| per-element tag hook | 12.1 | — | 9.6 | +2.5 |
| **`as-element` on a string child** | **11.5** | 12.3 | **20.3** | **−8.8** |

## 4. The budget spent, and the budget declined

HD-004 permits codec-work caching only, and its hard fence is verbatim: no
template extraction, no hole plans, no node references, no subscription-addressed
anything, no direct DOM writes. **Nothing here goes near any of them.** There is
still no element cache, no props-object cache and no template; `convert-props`
still mints a fresh object per element per render.

### (a) The caches lose their prototype — HD-004 cache mechanism

Both caches are keyed by the author's literal, so both had to answer two hostile
questions on **every lookup**: could a literal named `__proto__` poison a write,
and could one named `toString` hit an INHERITED property and be served a value
nobody cached. The shipping answer was three `identical?` compares plus
`Object.prototype.hasOwnProperty.call`, on the hit path, once per tag and once
per prop on every element of every mount.

`Object.create(null)` answers the second question **structurally** — no
prototype chain, so a lookup can only ever return an own property — and demotes
the first to the **miss** branch, the only branch that writes, which runs once
per distinct literal for the life of the build.

| lookup | guarded (shipping) | **`Object.create(null)`** | `#js {}` + `instance?` |
|---|---|---|---|
| prop, before run | 18.1 | **11.1** | 13.8 |
| prop, after run | 25.9 | **15.4** | 18.1 |
| tag, before run | 16.6 | **11.2** | 12.1 |
| tag, after run | 24.1 | **12.5** | 16.6 |

**Costed and DECLINED: the type-check variant.** V8 starts `Object.create(null)`
objects in dictionary mode, so the null-prototype route could plausibly have lost
— the alternative keeps `#js {}` in fast mode and validates the hit by TYPE,
since nothing on `Object.prototype` is a `ParsedTag` or a `PropSlot`. It read
consistently second on both caches in both runs, and it is declined on
construction as well as margin: a cache with no prototype **cannot** serve an
inherited value, where a type check is a promise that it will be noticed.

`agreement failures: []` on both runs — every candidate answered what the
shipping shape answers for every literal on the page and for the seven hostile
names the page does not carry.

### (b) The walk asks `string?` before `vector?` — a predicate order, not a cache

Classified plainly: **this is not caching.** It is the same class as `rf2-y1jkm`'s
"small knives" (the in-loop `:key` skip, the `===`-chain reserved check), which
this bead-family's budget already covers, and it touches none of the fenced
mechanisms.

`as-element`'s branches are mutually exclusive — a value satisfies at most one
of `nil?`, `false?`, `string?`, `vector?`, `number?`, `seq?`, `true?` — so their
order **cannot change an answer**, only what each population pays. And `vector?`
was first: it is `IVector` satisfaction, which for anything without the marker
falls through to `native-satisfies?`, so every string, number and lazy seq on the
page proved itself not-a-vector the expensive way before reaching its own branch.
This was the one stage where stock Reagent was decisively ahead of us — its
`as-element` asks one `js-val?` and returns a string on the first branch.

| dispatch | shipping order | **`string?` first** |
|---|---|---|
| whole child roster (1,908), before run | 22.5 | **8.9** |
| whole child roster, after run | 24.6 | **13.4** |
| the 567 strings alone, before run | 31.7 | **5.3** |
| the 567 strings alone, after run | 40.6 | **9.7** |

The vectors pay one extra `typeof` and the whole population still reads 1.8–2.5×
cheaper.

### Declined, with reasons

- **Replicant-style value-keyed skeleton caching.** Already declined by
  `rf2-y1jkm` with census arithmetic; this page strengthens the declination
  rather than revisiting it. A mount is the *first* walk, so a value-keyed
  element cache only pays on subtrees repeated within one page (under 12% of
  this one) — and now there is no gap for it to close: our walk reads below
  stock Reagent's. HD-004's anti-fence on element/skeleton caching stands
  untouched (HD-028 declines the same for boundary-level identity caching too).
- **Forking `reagent2.impl.template`.** Needs a ruling and is not sought. The
  measurement makes it look backwards anyway: the donor's walk is 1.73–1.90×
  stock Reagent's and 1.8× ours.
- **A compiler or analyzer** (UIx's answer). Fenced, unchanged.
- **Closing the remaining `convert-props` term.** See §5 — it is Hicasso's
  semantics, and it is reported as the floor rather than attacked.

## 5. The floor reached, stated plainly

**The walk's remaining cost over stock Reagent's is one stage: the prop
pipeline, +20.8 ns/element after the change (+27.0 before).** That is what
Hicasso's `convert-props` does that Reagent's does not, and every item of it is a
shipped semantic rather than an inefficiency: the `:&` remainder probe and its
owned-literal law (HD-023), the `:ref` reserved-value refusal (HD-022), the
event-position classification that makes intent vectors work at all, the
`callback?` probe at every position, and `controlled/install!`'s per-element tag
switch (HD-020's caret convergence, +2.5 ns/element in its own row). Removing any
of them removes a feature. **It is not attacked, and it is reported as the
floor.**

And the size of the whole prize, which is the number a ship/kill decision needs:

> The walk is **~0.66 ms of a ~10 ms mount**. The two cheapenings landed here
> are worth about **9% of the walk** — and therefore about **0.6% of the
> mount**. Nothing available inside the fence, at the walk, moves the 1.10×
> mount line.

That is the bead's honest negative, and it is a complete answer: the mount
deficit is no longer attributable to the interpreter. Whatever remains above the
line on the census rows is elsewhere — `rf2-cno31`'s route-link term is measured
and being fixed; the read machinery was halved by `rf2-6c237` and its remaining
90% is the substrate's own pure compute. **The interpreter is done. It costs
exactly this, here.**

## 6. Correctness, and the mutation ledger

Full suite at the landed state: **12,381 tests / 61,824 assertions, 0 failures,
0 errors** (`npm run test:cljs`).

A new witness pins what nothing but the cache's construction answers any more —
a tag or prop literal named after an `Object.prototype` member must be parsed and
converted as itself, and must hit its own entry the second time. Each mutation
was **proven able to go red** and green on restore, run against the codec's own
34 tests / 195 assertions:

| mutation | expected failure | observed |
|---|---|---|
| M1 — both caches back to `#js {}` | the inherited-property witness | **RED** — 15 failures; the actual value is literally `#object[toString]` |
| M2 — make the string branch unconditional (`(string? (str x))`) | every hiccup-vector witness | **RED** — 34 failures, 75 errors. This is the ORDERING proof: the string branch demonstrably precedes the vector branch |
| M3 — drop the prop-cache write guard | `:props` cache size | **RED** — `(not (= 3 4))`: `__proto__` reached the cache |
| M4 — drop the tag-cache write guard | `:tags` cache size | **RED** — `(not (= 0 1))`: same, for tags |

M3 and M4 are worth reading together: with a null-prototype cache, writing
`__proto__` is harmless to the *object* — the guard's remaining job is that the
three names never occupy an entry, and the witnesses pin exactly that.

## 7. Window discipline and the box

`worker/linkterm-cno31` announced a diagnostic window at 01:25 AUSEST and owns
the census-clock windows. This bead announced its own on the bead before either
run and takes no census-clock row.

**The box was NOT quiet and the page says so.** It is a shared fleet box: at the
first announce two other worktrees' shadow-cljs JVMs were compiling and the box
averaged 48% over 24 threads; four concurrent builds were seen at 01:53. Waiting
was tried for 25 minutes and the box did not clear.

Consequences, declared:

- The **absolute ns figures are ceilings**, and are not compared across runs.
- The **arm ratios are same-run interleaved** against donors measured in the same
  windows, under the arm-order guard, and are the figures this page's conclusions
  rest on.
- **One run was REFUSED and is not published.** The first after-run took the
  arm-order guard's exit 2: every arm read 1.44–1.55× slower in the LAST THIRD
  than the FIRST, ranges disjoint — a monotonic phase drift as the box ramped,
  affecting all four arms alike. The repair was the run, not the tolerance: the
  after-run was re-taken after a wait-for-clear gate and passed on the first
  attempt. The refused run's figures appear nowhere on this page.
- An earlier **shakedown** of the instrument (01:53–01:57, exit 0) is discarded
  for absolutes and is reported only as the instrument's own validation.

## Provenance

| | |
|---|---|
| **Producing commit** | `90cc9ab338` on `worker/walkdecomp-2rtt6-63` (the after run); the before run is the same tree with `front/codec.cljs` at its pre-change blob |
| **Reproduction** | `HICASSO_INIT_FN=re-frame.bench.hicasso.walk-vs-reagent-app/-main HICASSO_OUT_DIR=out/hicasso-walkcmp HICASSO_PORT=8171 node implementation/freehand/test/re_frame/bench/hicasso/run.cjs` |
| **Build** | `:hicasso-bench` (`--config-merge` entry swap), `:advanced`, `goog.DEBUG false`, lane cache cleared per `rf2-2rtt6.20`; **0 warnings** on every build |
| **Runtime** | chromium `147.0.7727.15` (playwright), node `v24.13.0`, Windows NT 10.0 x64, 24 threads |
| **Design** | 4 arms × 6 rounds × (4 warm-up + 10 samples), 8 whole-page walks per timing window |
| **Clock** | in-page `performance.now`, **diagnostic**; the clock of record is `census_clock_run.cjs` and is untouched here |
| **Windows** | before 2026-08-03 02:01–02:03 AUSEST; refused after-run 02:23–02:31 (published nothing); reportable after-run 02:35–02:38 |
| **Exit codes** | before `0`, guard reportable; first after-run **`2`** (guard refused, nothing published); re-taken after-run `0`, guard reportable |

Measured blobs — **byte-identical across the two runs except the intervention**:

| file | blob |
|---|---|
| `…/front/codec.cljs` | before `5a0b04733a33d1baa815b093f5b297e325aa6675`, after `0304f489bb941f9f12c6c87b05e7fcd4dd9343c6` **(the change under test)** |
| `…/walk_vs_reagent_app.cljs` | `b447e313fcda7ca45c724cb8e8b45acdda54651c` (both runs) |
| `…/walk_profile_app.cljs` (the twin + its parity gate) | unchanged across both runs |
| `reagent/reagent` | `2.0.1`, from the jar, unmodified |
| `reagent2.impl.template` | unmodified — **the codec is not forked** |

## 2026-08-04 re-take of the arms on current main

**Why this run exists.** The P2 fork ruling compares the candidate against a
null — adapter-Prime, the composed `reagent-slim` `:f>` plus UIx
`use-subscribe` arm — and that arm rides `reagent2.impl.template`, the `slim`
row above. Since §2 was taken, **two fixes landed in that shipped interpreter**:
`rf2-lhdp0` (PR #7405) and `rf2-e7zxb` (PR #7427), the second closing on
`slim/reagent` 0.9762 and 0.9649. The HD-013 creative advisory pass
(2026-08-04, recorded on `rf2-2rtt6.1`) drew the obvious inference — that
Hicasso's published 0.9636× and slim's 0.9762×/0.9649× are **parity**, so §2's
`hicasso/slim` **0.56×** is stale — and then said in terms that this was a
**hypothesis until one run put both arms in the same windows**, because the two
figures come from different sessions on a shared box, which is exactly what
this instrument's ratios are not for. This is that run.

**One run. Four arms, one process, one witness value, the arm-order guard
adjudicating.** Nothing was re-run, no run was selected after seeing its
result, and no tolerance was touched. What would be reported was fixed before
the run opened.

### The rows

| arm | ms/walk p50 [min – max] | ns/element |
|---|---|---|
| `reagent` (stock 2.0.1) | 0.4750 [0.3875 – 0.9000] | 395 |
| **`hicasso`** | **0.4000 [0.3250 – 0.5125]** | **333** |
| `slim` (`reagent2.impl.template`) | 0.4625 [0.4125 – 0.7125] | 385 |
| `hicasso-native` (intent markup) | 0.5500 [0.4625 – 0.8875] | 458 |

`hicasso/reagent` **0.8421** · `hicasso/slim` **0.8649** · `slim/reagent` 0.9737

### The quantisation floor, applied before any sign is read

Chrome clamps `performance.now` to 100 µs and this instrument holds **8** walks
in one window, so **one grid step is 0.0125 ms per walk** — the instrument says
so itself at `walk_vs_reagent_app.cljs`, and every p50 this page has ever
published is an exact multiple of it. On a ~0.45 ms walk that is ~2.8%. Each
pair is therefore read in grid steps first, and a pair inside one step is **not
resolved as to sign**:

| pair | Δ ms/walk | grid steps | verdict |
|---|---|---|---|
| `hicasso` vs `reagent` | 0.0750 | 6 | resolved |
| `hicasso` vs `slim` | 0.0625 | 5 | resolved |
| `hicasso` vs `hicasso-native` | 0.1500 | 12 | resolved |
| `slim` vs `reagent` | 0.0125 | **1** | **NOT RESOLVED as to sign** |

So `slim/reagent` 0.9737 reproduces `rf2-e7zxb`'s 0.9762 / 0.9649 in magnitude,
but this run **does not establish that slim is faster than stock Reagent** — one
grid step is the whole of the difference. The defensible statement is that the
donor's walk and stock Reagent's are **at parity within the grain**, which is
itself the finding: it was 1.90×/1.73× on this page's own two runs.

### The rider the pass raised, answered

**The codec advantage over the donor has NOT evaporated — but it has collapsed
from 0.56× to 0.86×.** `hicasso/slim` reads **0.8649** here, five grid steps,
same run, same process, same witness, guard reportable on both arms. The pass's
hypothesis was reasonable and is refuted only by the thing it asked for: put the
two in one process and the codec is still measurably ahead of the interpreter it
was extracted from. What is stale is the **magnitude**. Any statement of the
form "we read 0.56× the donor we were built from" is no longer true of `main`;
0.86× is.

### Whose code moved, and what that costs the comparison

The donor arm is quotable across sessions and the candidate arm is not:

- **`reagent2.impl.template` is byte-identical to `rf2-e7zxb`'s landing** —
  blob `9b30b6b74c` at this run's `HEAD` and at commit `099c1f228e`. So the
  donor ran at exactly the code that produced the published anchor, and it
  reproduced it (0.9737 against 0.9762 / 0.9649). **The donor reproduces its
  anchor.**
- **`front/codec.cljs` does not.** It is **593 insertions / 114 deletions**
  different from the `0304f489bb` blob that produced §2's 0.9636×, across seven
  commits including `rf2-2rtt6.39`, `rf2-2rtt6.65`, `rf2-2rtt6.36` and
  `rf2-digtt`. `hicasso/reagent` 0.8421 therefore **does not reproduce** §2's
  published 0.9636× and is not evidence about it: it is a different codec. The
  same-run `hicasso/slim` ratio is unaffected by this, which is why the rider
  above can be answered and a cross-session candidate comparison cannot.

### One thing nobody asked for, recorded because it is resolved

**The intent surface now costs 125 ns/element, where §2 measured 41.** The
`hicasso-native` arm walks the page's own intent markup; here it reads 458
ns/element against the plain arm's 333, twelve grid steps apart. As a same-run
ratio the authoring surface has gone from ~1.075× the plain walk (603/562 and
593/551) to **1.375×**. This is the price of the surface the P2 fork is about,
it is stated rather than buried, and no cause is offered here — the codec moved
by 593 lines between the two readings and this run does not attribute.

### What this run does not settle

**It does not locate the P2 null.** The `slim` arm is the *interpreter*
adapter-Prime rides, not adapter-Prime — which is the composed `reagent-slim`
`:f>` plus UIx `use-subscribe` arm on the HD-008 instrument — and every figure
here is the **diagnostic in-page walk clock**, not the clock of record. The
advisory pass separates these explicitly: this run answers its rider (b), while
moving the null is its (a), "a donor-row re-take on the census or M1
instrument, which needs a window that can adjudicate". That re-take is
`rf2-2rtt6.31`, and it has been taken — it landed in `1ac48c4a0b` (PR #7378)
and its rows are published in
[the re-take on the current tree](hd8-composed-donor-arm.md#the-re-take-on-the-current-tree-rf2-2rtt631).
The bead is still open, but on a separate held question — the clock-of-record
driver's write-before-refuse dataset ordering, which is an operator ruling —
and not because the re-take is outstanding.

### Controls, all of them, and the box

| control | outcome |
|---|---|
| arm-order guard self-test (12 cases) | all `ok` before any arm ran |
| twin parity gate (fatal) | **OK** — 1,202 elements, 58,474 canonical bytes, identical to `lt/page` |
| workload match | `hicasso` / `hicasso-native` 58,474 B; `slim` / `reagent` 58,529 B — the same **55-byte** whitespace-only difference §1 explains and prices *against* Reagent |
| arm-order guard verdict | **reportable** — every one of the four arms `[ok]` by predecessor **and** by phase, tolerance 10% |
| candidate agreement failures | `[]` |
| `SLIM DECOMPOSED` agreement failures | `[]` |
| exit code | **`0`** |

**The box was quiet, and quiet is measured here as summed per-process CPU-time
deltas over the core count** — not `Win32_Processor.LoadPercentage`, which read
**50** and **14** at the two sampling instants below while the true figure was
**1.44%** and **2.73%**, and is not usable on this host. **1.44%** of 24 logical
cores immediately before the run, **2.73%** immediately after (that window
includes this worker's own `git` calls). **Zero `java` processes** existed at
either sample, so no shadow-cljs JVM was compiling; no other worker and no gate
was running. Note that §7 declares its own runs were taken at ~48% with four
concurrent builds, which is the likeliest reason every absolute here is well
below every absolute above — and is why the absolutes are again **not** compared
across runs.

### Provenance

| | |
|---|---|
| **Producing commit** | `77a92185ea` (`origin/main`), branch `worker/nullwalk` |
| **Reproduction** | `HICASSO_INIT_FN=re-frame.bench.hicasso.walk-vs-reagent-app/-main HICASSO_OUT_DIR=out/hicasso-walkcmp HICASSO_PORT=8171 node implementation/freehand/test/re_frame/bench/hicasso/run.cjs` |
| **Build** | `:hicasso-bench`, `:advanced`, `goog.DEBUG false`, **0 warnings** (200 files, 145 compiled, 19.35 s) |
| **Runtime** | chromium `147.0.7727.15` (playwright), node `v24.13.0`, Windows NT 10.0 x64, 24 threads |
| **Design** | 4 arms × 6 rounds × (4 warm-up + 10 samples), 8 whole-page walks per window — unchanged |
| **Clock** | in-page `performance.now`, **diagnostic**; the clock of record is untouched here |
| **Window** | 2026-08-04 07:53:51 – 07:54:28 AUSEST (37 s wall, build included) |
| **Exit code** | `0`, guard reportable |

| file | blob |
|---|---|
| `…/front/codec.cljs` | `2c0c26ccb57cf3c94a2d0626af4bedbd32dccf26` — **not** §2's `0304f489bb` |
| `…/walk_vs_reagent_app.cljs` | `596d6c1220af1da797dee0797dc3dd1da9e7dbfb` — **not** §2's `b447e313fc`; `rf2-lhdp0`/`rf2-e7zxb` added the `SLIM DECOMPOSED` rows |
| `…/run.cjs` | `1bc82c38ea59ca00f7b58ae479e6eb738eece0ec` |
| `…/walk_profile_app.cljs` (the twin + its parity gate) | `5e12aaf7b2008dee5cb561c63109870fe7b5d291` |
| `reagent/reagent` | `2.0.1`, from the jar, unmodified |
| `reagent2.impl.template` | `9b30b6b74ca5cf348edf4be71ae32d332b93cfff` — byte-identical to `rf2-e7zxb`'s landing; **the codec is still not forked** |

**Advisory only, and no conclusion about the P2 fork is drawn here.** No
adapter, codec or runtime file was touched by this run; `validation.md`,
`decisions.md` and `rf2-2rtt6.1`'s standard-bead notes are unmodified; no bead
was closed or reprioritised. The decider is the operator.
