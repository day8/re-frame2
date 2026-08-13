# Counterfactual topology advice — the blinded prediction, frozen before the tournament exists

This record owns **phase 1** of [specification §11](specification.md#11-innovation-portfolio)'s row *Counterfactual topology advice*, whose status is **Spike** and whose deciding rule is *"Retain only if blinded calibration predicts useful coarse/fine/chunked choices"*. The detailed proposal is in [the left-field lane](lanes/left-field-ideas.md#counterfactual-topology-advice). Owned by `rf2-hic-080`.

**Everything on this page was written and committed before the tournament it predicts had run.** That is the whole value of the artefact, and it is checkable rather than asserted: this file landed in its own commit on `worker/topospike-hic080`, pushed before any other work on the branch, on a tree based at `e337a1dd295f3c3d94a348ac66dd7dccdec6fb32`.

## The blinding here is physical, not procedural

The spike's deliverable is *"blinded calibration: the advice compared against the hic-036 tournament's measured outcomes without seeing them first."* `rf2-hic-036` has not run. Its outcomes **do not exist** — [the hot-path lane](lanes/hot-path-architecture.md#decisive-experiments) describes the topology tournament as a plan, and `rf2-hic-036` is fenced as a measurement window awaiting a quiet machine.

So the usual weakness of a blinded prediction — that the predictor could have peeked, and the record can only show that they say they did not — is absent. There was nothing to peek at. That protection is available exactly once, while `rf2-hic-036` is unrun, and it is why phase 1 was split off and frozen now rather than held until one worker could hold both halves.

**Phase 2 is not on this page and was not attempted.** No verdict on whether the advice is useful appears here, because the deciding rule's evidence does not exist. A verdict written now would be a guess wearing a rule's clothes.

## Basis vocabulary

Borrowed from [the causal slice](../../../../tools/xray/src/day8/re_frame2_xray/panels/hicasso_causal.cljc)'s discipline, because a worksheet that mixed the two would be read as measurement throughout:

| basis | meaning here |
|---|---|
| `:observation` | a number a running instrument reported |
| `:derivation` | a number computed from committed source, exact for this application, and wrong wherever the model of the runtime is wrong |

**Every quantity below is a `:derivation`, with one `:observation` used as its landmark.** The read-edge table is computed from the slice's committed views, subs, events and seed database; it is then checked against the one committed browser measurement of the same quantity, and it reproduces it exactly. A derivation that reproduces an independent observation of the same population is still a derivation — it is a derivation with one anchor.

## The input: the slice's committed read edges

Feed route, page 1, seeded database. A **membership** is one boundary→cell edge — the unit [the read-set census](readset-group-census.md#the-unit-being-counted) fixed and counted.

| # | boundary | instances `B` | read set | `R` | entries | memberships `B·R` |
|---|---|---|---|---|---|---|
| 1 | `app` | 1 | `[:rf.route/id]`, `[::token :surface]`, `[::token :ink]`, `[::t :app/pane-error]` | 4 | 1 | 4 |
| 2 | `chrome` | 1 | `[::locale]`, `[::theme]`, and 5 `[::t …]` | 7 | 1 | 7 |
| 3 | `feed-page` | 1 | `[::feed]`, `[::t :feed/heading]` | 2 | 1 | 2 |
| 4 | `digest` | 1 | `[::digest-blocks]`, `[::digest-loading?]`, and 3 `[::t …]` | 5 | 1 | 5 |
| 5 | `digest-body` | 1 | `[::digest-blocks]` | 1 | 1 | 1 |
| 6 | `pager` | 1 | `[::current-page]`, `[::page-count]`, and 3 `[::t …]` | 5 | 1 | 5 |
| 7 | `prose-block` + `list-block` | 2 | — (read-free) | 0 | 1 | 0 |
| 8 | `callout-block` (accent) | 1 | `[::token :accent]` | 1 | 1 | 1 |
| 9 | `callout-block` (warning) | 1 | `[::token :danger]` | 1 | 1 | 1 |
| 10 | `unsupported-block` | 1 | `[::t :digest/unsupported]` | 1 | 1 | 1 |
| 11 | `article-row` × 3 | 1 each | `[::tags-open? <slug>]`, `[::t :feed/tags]` | 2 | 3 | 6 |

**14 boundaries, 13 read-set entries, 30 distinct cells, 33 memberships.** Two cells carry more than one reader: `[::t :feed/tags]` (3, one per row) and `[::digest-blocks]` (2, `digest` and `digest-body`).

### The landmark, reproduced exactly

The census measured this same population on a real React root on 2026-08-13 and published it as its `slice` row. The derivation above reproduces every figure it reports:

| quantity | census (`:observation`) | this derivation | agree |
|---|---|---|---|
| read-set entries | 13 | 13 | yes |
| memberships `M` | 33 | 33 | yes |
| grouped `M′ = Σ (R + B)` | 47 | 47 | yes |
| saved | −14 | −14 | yes |
| shape `{[B R] n}` | `{[1 7] 1, [1 5] 2, [1 4] 1, [1 2] 4, [1 1] 4, [2 0] 1}` | identical | yes |
| max `B` | 2 | 2 | yes |
| max `R` | 7 | 7 | yes |

The derivation also reproduces the census's own internal landmark by the census's own two routes: summing `B·R` entry-side gives 33, and summing readers cell-side over the 30 distinct cells gives 33. The two walks share no traversal here either.

**This is what makes the rest of the page worth reading.** A source-level derivation of read edges is exactly the kind of thing that is plausibly wrong — a conditional read modelled as unconditional, an eagerly-built fallback missed, a parameterised key collapsed — and each of those errors would have moved a figure in the table above. None did.

## The input: the event windows

The committed event corpus, restricted to what is reachable on the feed route. Cells that move are value changes; `::digest-blocks` recomputes under `::reload-digest` to a value that is `=`, so its cell does not move.

| event window | app-db write | cells moved | boundaries invalidated (of 14) |
|---|---|---|---|
| `::set-locale` | `:locale` | 16 | 9 — `app` 1/4, `chrome` 6/7, `feed-page` 1/2, `digest` 3/5, `pager` 3/5, `unsupported-block` 1/1, `article-row` 1/2 × 3 |
| `::set-theme` | `:theme` | 5 | 4 — `app` 2/4, `chrome` 1/7, `callout-block` 1/1 × 2 |
| `::tags-open? <slug>` | `[:ui ::tags-open? <slug>]` | 1 | 1 — `article-row` 1/2 |
| `::reload-digest` | `[:digest :status]` | 1 | 1 — `digest` 1/5 |
| `::digest-arrived` | `:digest` | 2 | 2 — `digest` 2/5, `digest-body` 1/1 |
| navigate `?page=2` | `:rf.route/query` | 2 | 2 — `feed-page` 1/2, `pager` 1/5 (and 3 rows replaced) |

**The population is unweighted, and that is a limitation rather than a choice.** Six event kinds, each counted once. The committed evidence carries no interaction frequencies, and [§3.3](#33-the-finding-that-conditions-everything-below) shows that the weighting decides one of the three quantities outright.

## 1. Membership savings

The counterfactual, for a family of `B` sibling row boundaries each holding `R` reads:

| topology | memberships attributable to the family | bodies run on a **single-row** change | bodies run on an **all-row** change |
|---|---|---|---|
| fine (today) | `B·R` | 1 | `B` |
| coarse | 0 — folded into the parent's existing slot, re-pointed at a view-model | `B` rows of markup, in one body | `B` rows of markup, in one body |
| chunked at width `k` | `⌈B/k⌉` | `k` | `B` |

Coarse contributes no new membership because the parent already holds one: `feed-page` reads `[::feed]`, and the coarse arm re-points that slot at a view-model that has already merged the rows' per-instance state. The open flags move into the **subscription graph**, whose internal edges are not memberships.

So the **membership saving of coarse over fine is exactly `B·R`**, and of chunked over fine `B·R − ⌈B/k⌉`. For the slice's row family, `R = 2`:

| rows `B` | fine `B·R` | coarse | chunked `k=25` | saving, coarse | saving, chunked |
|---|---|---|---|---|---|
| 3 (the slice as committed) | 6 | 0 | 1 | 6 | 5 |
| 100 | 200 | 0 | 4 | 200 | 196 |
| 300 | 600 | 0 | 12 | 600 | 588 |
| 1000 | 2000 | 0 | 40 | 2000 | 1960 |

At the slice's own size the whole family is 6 of the page's 33 memberships — **18%**, and 6 slots is not a quantity anything measures. The saving only becomes a number worth a decision at the row counts the tournament uses, which is why the rows above are extrapolation and are labelled as such: they hold `R = 2` and the read-edge structure fixed while moving `B`.

## 2. Set stability

Does a boundary's read set change between commits? [The hot-path lane](lanes/hot-path-architecture.md#read-topology-guidance) makes this a first-class hazard — *"Large oscillating read sets are suspect because whole-set reconciliation can become proportional to the current read count"* — so it is a **precondition on the coarse arm**, not a tiebreak.

| boundary | declared read sets | reachable from the committed events | max symmetric difference | stable |
|---|---|---|---|---|
| `app` | 1 | 1 | 0 | yes |
| `chrome` | 1 | 1 | 0 | yes |
| `feed-page` | 2 | 1 | 1 | yes (reachably) |
| `digest` | 2 | **2** | 2 | **no** |
| `digest-body` | 1 | 1 | 0 | yes |
| `pager` | 2 | 1 | 3 | yes (reachably) |
| `prose-block`, `list-block` | 1 | 1 | 0 | yes |
| `callout-block` | 1 per instance | 1 | 0 | yes |
| `unsupported-block` | 1 | 1 | 0 | yes |
| `article-row` | 1 | 1 | 0 | **yes** |
| `editor` (article route) | 6 | **6** | 5 | **no** |
| `article-page` (article route) | 2 | **2** | 1 | **no** |

**Ten of thirteen boundary kinds hold exactly one read set, and all three that do not are status-bearing** — `digest` swaps `[::t :digest/retry]` for `[::t :digest/loading]` while in flight, `editor` grows three reads on a failed save and swaps its button string while saving, `article-page` grows one on a slug nobody published.

The two entries with unreachable variants are worth naming because they are the ones a purely static reading would get wrong in the pessimistic direction. `pager`'s read set collapses from 5 to 2 on a single-page feed — its own docstring calls that *"the read set shrinking with the branch"* — and `feed-page` grows `[::t :feed/empty]` on an empty feed. Neither is reachable from the committed events on the seeded roster, so neither oscillates in practice, and a worksheet that counted declared variants alone would have called two stable boundaries unstable.

**The family the tournament is about is the most stable thing on the page.** `article-row` reads through `h/use-subs` with both reads unconditional, so its set has one form and cannot oscillate. Every precondition the coarse arm has on set stability is satisfied by this family.

## 3. Co-change frequency

Co-change is two different questions depending on which direction the counterfactual moves, and collapsing them is how this quantity stops doing work.

### 3.1 Within a boundary — should it SPLIT finer?

Given the boundary invalidates at all, how many of its reads actually changed? Reads that always move together are reads a split would not separate.

| boundary | changed / held, summed over invalidating windows | ratio |
|---|---|---|
| `chrome` | 7 / 14 | 0.50 |
| `feed-page` | 2 / 4 | 0.50 |
| `article-row` | 4 / 8 | 0.50 |
| `digest` | 6 / 15 | 0.40 |
| `pager` | 4 / 10 | 0.40 |
| `app` | 3 / 8 | 0.375 |
| **pooled** (`R ≥ 2` boundaries) | **26 / 59** | **0.441** |

Under half. On the face of it that argues for splitting — but the reads that fail to co-change are, in every row above, **a string or a token against a datum**: `chrome`'s locale against its theme, `pager`'s page number against its three labels, `app`'s route against its two colours. Splitting a boundary to separate its i18n reads from its data reads would mint a boundary per label, which is the shape [the census](readset-group-census.md#why-and-why-it-is-structural-rather-than-a-property-of-these-seven-applications) already priced and refused from the other side.

**So the within-boundary figure is real and points nowhere useful on this application.** It is reported because a worksheet that dropped a computed quantity for being inconvenient would be worthless as a pre-registration.

### 3.2 Across a family — should it MERGE coarser?

Given an event touches the row family at all, how many of its `B` instances invalidate? Near `1/B` is genuine sparsity and fine is buying something; near `1` is bulk and the fine arm's memberships bought nothing.

| event window | instances invalidated, of 3 | fraction |
|---|---|---|
| `::set-locale` | 3 | 1.00 |
| `::tags-open? <slug>` | 1 | 0.33 |
| navigate `?page=2` | 3 (replaced) | 1.00 |
| **pooled over touching windows** | **7 / 9** | **0.78** |

### 3.3 The finding that conditions everything below

**Exactly one committed event discriminates fine from coarse on the row family, and it is the only one whose key carries a per-instance parameter.**

`::tags-open?` writes `[:ui ::tags-open? <slug>]`, so it moves one cell and invalidates one row. Every other event that reaches a row reaches all of them: `::set-locale` moves the shared `[::t :feed/tags]` cell — a *chrome* fact arriving in the row family through the one key the rows share — and a page flip replaces the whole list. Under the coarse arm both of those invalidate the merged boundary anyway, so neither can distinguish the arms.

Unweighted, the pooled across-family figure is **0.78**, which reads as *mostly correlated, merge it*. But a realistic interaction mix inverts it: a user toggles disclosures and types constantly and switches locale approximately never, and under any weighting that reflects that, the figure falls toward 0.33 and reads as *independent, keep it fine*.

**The committed evidence cannot choose between those two readings.** The event windows are a statement about what the application *can* do; the weighting is a statement about what its users *do*, and nothing in the corpus carries it. This is the single largest source of uncertainty on the page, and it sits on the quantity the §11 row's deciding rule is most about.

## The rule the three quantities imply

Stated as a rule so phase 2 scores a method rather than an intuition:

1. **Set stability is a gate, not a term.** A family whose read set oscillates is refused the coarse arm outright, whatever the other two say.
2. **Across-family co-change orders the arms.** Near 1 → coarse; near `1/B` → fine; in between → chunked.
3. **Membership savings sets the stakes, not the direction.** `B·R` decides whether the choice is worth making, never which way. At `B = 3` it is 6 slots and the answer is *do not bother*.

## The predictions

Pre-registered, falsifiable, and frozen. `B ∈ {100, 300, 1000}`, `R = 2`, the tournament's four operations.

| # | prediction | confidence | scored |
|---|---|---|---|
| P1 | **Sparse** (single-row update): fine beats coarse at every row count, and the margin grows monotonically with `B` | high | control |
| P2 | **Bulk** (all-row update): coarse beats fine at every row count, and the margin grows with `B` | medium | **yes** |
| P3 | **Reorder**: coarse ≥ fine, and at 100 rows all arms land within noise of each other | low | **yes** |
| P4 | **Controlled edit** (one field, one row): fine beats coarse, by a larger margin than P1 | high | control |
| P5 | Chunked/windowed is **not the best arm on any of the four named operations at any row count** | medium | **yes** |
| P6 | No arm changes rank between 100, 300 and 1000 rows on any single operation | medium-low | **yes** |

**P1 and P4 are controls, not achievements.** Both follow from an identity — one body against `B` bodies — rather than from any quantity computed above, so getting them right proves nothing about the method. Their job is the opposite: **if either comes out wrong, the model of the runtime on this page is wrong and nothing else on it should be believed**, including the parts that came out right.

The deciding rule therefore bites on **P2, P3, P5 and P6** and on nothing else.

### Scoring rule, frozen with the predictions

- A prediction counts as right only if the tournament's measured **ordering** of the arms matches the predicted ordering, for that operation at that row count. Predicted magnitudes are not scored; none was stated.
- "Within noise" in P3 is the tournament's own noise band, whatever it turns out to be. It is not a band this page gets to choose afterwards.
- A prediction the tournament's design cannot address is scored **unaddressed**, never right.
- P5 is the prediction most worth being wrong about, because it says the tournament's four operations cannot show the chunked arm at its best — a chunked read is the useful middle for a *mixed* workload, and the four named operations are each pure.

### What is refused rather than predicted

**N1 — the chunk width.** The naive optimum minimises `⌈B/k⌉ + k`, giving `k* = √B`: 10, 17, 32 at the three row counts. That arithmetic **adds memberships to body runs as if one slot cost one body**, which is certainly false. Introduce the true ratio `c` = cost of one row of markup ÷ cost of one membership slot, and the optimum becomes `k* = √(B/c)`, which at a plausible `c = 10` gives 3, 5 and 10 — different advice at every row count. **The committed evidence carries no value for `c`**, so the chunk width is a free parameter and this page declines to pin it. An advisor that emitted `k` from this worksheet would be emitting a guess about `c`.

**N2 — the native-virtualized arm.** It is not a read-topology choice. It changes how many rows exist in the DOM, which is a different lever from how many read edges exist, and no quantity on this page is about it. Predicting it would be a bluff.

## What the derivation cannot see

Recorded because a later reader must be able to attack this page where it is weakest.

- **Equality gating.** `::reload-digest` is modelled as moving one cell, on the premise that `::digest-blocks` recomputes to an `=` value and does not propagate. If propagation is not equality-gated there, `digest-body` invalidates too and one row of the window table is wrong.
- **Commit-time versus ask-time.** The census reads reader arrays at ask time; this derivation reads them at no time at all. A boundary that mounts and unmounts inside one window is invisible to both.
- **The unweighted corpus.** Named in §3.3 and repeated here because it is the one limitation that changes an answer rather than a digit.
- **Extrapolation in `B`.** Every row count above 3 holds the slice's read-edge structure fixed while moving `B`. A 1000-row table is not a 3-row feed with more rows in it — it will have virtualization, a scroll container, and probably a different key scheme — and if the tournament's table reads more than 2 keys per row, every `B·R` figure on this page is low.

## Phase 2, and why it is not here

Phase 2 is the blinded calibration itself: score the six predictions against `rf2-hic-036`'s measured outcomes under the rule frozen above, apply the §11 deciding rule, and either graduate the method or keep this worksheet and stop.

It needs exactly one thing that does not exist: **`rf2-hic-036` must have run.** It is fenced as a measurement window needing a quiet machine, and until it records outcomes there is nothing to calibrate against. A phase 2 attempted before then would have to invent the comparison, which is the failure this split exists to make impossible.

## Standing

**Nothing is retained.** No advisor was built, nothing was wired into Xray, no runtime tap was added, no public surface moved and no production namespace was touched. This page and the branch that carries it are the whole of phase 1, and the §11 row stays at **Spike** until phase 2 returns a verdict.

---

## Phase 2 — the scoring, applied

Added 2026-08-14 by `rf2-hic-080` phase 2, a different worker in a different context, after [the topology tournament](topology-tournament.md) ran and published. **Everything above this rule is phase 1 and is unchanged**: not one prediction, not one confidence, not one clause of the scoring rule, and not one figure in the three quantities. The scoring below is applied to the text as frozen, including where that text turns out not to decide the question — those places are recorded as ambiguities rather than resolved, because resolving them now, with the outcomes visible, is exactly the move the split exists to prevent.

### The freeze held on both sides

Worth stating because it is the artefact's only real asset, and because it was not guaranteed.

| obligation | who carried it | held |
|---|---|---|
| predictions written before outcomes existed | phase 1, commit `de4b1bef3ea202ca97f5989424b14bc998f15794` | yes — `rf2-hic-036` had not run |
| tournament does not grade its own predictor | [tournament §1.7](topology-tournament.md#17-what-this-page-will-not-do), restated at [§2.8](topology-tournament.md#28-what-was-not-concluded) | yes — it hands over outcomes and declines to score |
| phase 2 does not amend predictions or rule | this section | yes — appended only |
| phase 2 held by a different worker | dispatch | yes |

The tournament also pre-committed to *this page's* unaddressed clause by name, before its own results existed — [§1.4](topology-tournament.md#14-the-estimand-and-the-one-substitution-that-is-refused) states that a cell whose clock control refuses is handed over as **unaddressed** rather than as *"a work-census ordering wearing a clock's clothes."* The two frozen vocabularies interlocked without either side adjusting to the other. That is the one unambiguous success of the exercise, and it is a success of the protocol rather than of the method.

### What the tournament delivered, and what it did not

| half | state | bearing on scoring |
|---|---|---|
| deterministic work census | **complete** — 48 of 48 cells, both counters agreeing, witnessed by 1,507 tests / 9,533 assertions, 0 failures | measured orderings exist for sparse, edit and reorder |
| clock table | **does not exist** — no cell, for any arm, operation or row count | no clock ordering exists to score against |

Two separate causes are recorded for the clock, and neither is a judgement about the machine: the windowed arm's control [refused on the band](topology-tournament.md#26-the-clock-half--the-first-control-refused-the-replacement-certifies-three-arms-of-four) at the committed window size, and — the larger one — **the 4 × 4 clock table was never instrumented at all.** Only the control was built.

### The one place the frozen rule does not decide: which estimand

**Recorded, not resolved.** The rule says a prediction is right *"only if the tournament's measured **ordering** of the arms matches the predicted ordering, for that operation at that row count."* It does not name the estimand, and the tournament turned out to have two:

- The tournament's **primary** estimand is [the clock](topology-tournament.md#14-the-estimand-and-the-one-substitution-that-is-refused), and "beats" in these predictions means what it means to a user. Under this reading nothing is scoreable at all.
- The tournament's **only measured** ordering is the deterministic work census. Under this reading the three resolvable operations are scoreable.

The frozen text supports both and chooses neither. Rather than pick the one that flatters the method, **both are scored below.** They agree on the verdict, which is the most that can honestly be claimed for the ambiguity: it is real, it is the rule's fault, and it did not change the outcome.

What the rule *does* decide, and which resolves the compound-prediction problem without amendment, is its own scope clause — **"for that operation at that row count"**. Scoring is therefore **per cell**, not per prediction as a monolith, and a prediction spanning four operations is right on the cells it addresses and unaddressed on the cells it does not. No new score category was invented; `right`, `wrong` and `unaddressed` are the whole vocabulary and remain so.

### Reading A — the clock, the tournament's primary estimand

| # | scored | outcome | evidence |
|---|---|---|---|
| P1 | control | **UNADDRESSED** | no clock cell exists |
| P2 | yes | **UNADDRESSED** | no clock cell exists |
| P3 | yes | **UNADDRESSED** | no clock cell exists; no noise band was ever published |
| P4 | control | **UNADDRESSED** | no clock cell exists |
| P5 | yes | **UNADDRESSED** | no clock cell exists |
| P6 | yes | **UNADDRESSED** | no clock cell exists |

Six of six unaddressed; four of four on the deciding set. A method that cannot be scored cannot be *usefully right*, so this reading returns **stop** immediately. The controls do not fire either, so nothing above is discredited by it — the page is neither confirmed nor falsified on the clock.

### Reading B — the work census, the tournament's one measured ordering

The measured integers, from [§2.2](topology-tournament.md#22-the-rung-2-teaching-table--rows-of-markup-built) (rows of markup, the counter that separates the arms) and [§2.3](topology-tournament.md#23-the-same-table-boundary-bodies-run) (boundary bodies). `bulk` is excluded from scoring throughout, because the tournament itself declares it **UNRESOLVED** at [§2.7](topology-tournament.md#27-the-kill-rule-and-how-many-iterations-were-spent) and routes the question to `rf2-hic-018` rather than answering it — *"the line is in milliseconds and no millisecond here has a control."* Its integers exist; its ordering is withheld. Excluding it is the rule's unaddressed clause applied, not a choice made here.

| # | prediction, in brief | outcome | the measurement that decides it |
|---|---|---|---|
| P1 | sparse: fine beats coarse at every `B` | **RIGHT** (control) | markup 1 against 100 / 300 / 1000 |
| P2 | bulk: coarse beats fine at every `B` | **UNADDRESSED** | bulk is unresolved by the tournament's own disposition |
| P3 | reorder: coarse ≥ fine | **WRONG** | fine builds **0** rows, coarse builds `B`, at all three row counts |
| P4 | edit: fine beats coarse | **RIGHT** (control) | markup 1 against 100 / 300 / 1000 |
| P5 | chunked is not the best arm on any operation | **RIGHT** | best arm is fine or virtual on every resolvable cell; chunked is worst on reorder |
| P6 | no arm changes rank between 100, 300 and 1000 | **RIGHT** | ranks constant on sparse, edit and reorder, on both counters |

**Deciding set: two right, one wrong, one unaddressed.** Controls: both right, so the runtime model behind the three quantities is not falsified and the rest of the page stays readable.

#### The four scored predictions, at source

**P2 — unaddressed, and the near miss is recorded rather than banked.** On boundary bodies the direction the prediction named is there (coarse 1 against fine's `B`, and the gap grows 100 → 300 → 1000). But the tournament's [finding 2](topology-tournament.md#24-the-four-findings) is explicit that this is *"the finding most likely to be misread"*: bulk does not separate fine, coarse and chunked on markup at all — all three build exactly `B` — and *"whatever separates those three arms on a bulk commit is not the number of rows built."* The tournament withholds the bulk ordering deliberately. Taking the bodies counter as the ordering anyway would be substituting a census for a clock, which both pages refused in advance. **Unaddressed, not right.**

**P3 — wrong, and it is the only directional claim the tournament could resolve.** The prediction was that a permutation, being a broad operation, would not favour the fine arm. Measured, the fine arm builds **zero rows of markup for a table that visibly reorders** — the memo bails on unchanged props and React moves DOM nodes — while coarse rebuilds all `B`. The arms invert here, and [finding 3](topology-tournament.md#24-the-four-findings) names the inversion. P3's second clause, *"at 100 rows all arms land within noise of each other"*, is separately **unaddressed**: the rule pinned it to *"the tournament's own noise band, whatever it turns out to be"*, and no noise band exists, because no clock published. On exact integers there is no noise to be within — 0, 1, 100, 100 is not a near-tie by any reading.

**P5 — right, and it is the least informative thing on the page to be right about.** Chunked is never the best arm on any resolvable cell: fine and virtual build 1 row on sparse and edit against chunked's 25, and on reorder chunked is [strictly worst on both counters](topology-tournament.md#24-the-four-findings), drawing the tournament's only **STOP** disposition. `chunked/windowed` here is the chunked arm and not the native-virtualized one — the page's own gloss on P5 says *"the chunked arm"*, and [N2](#what-is-refused-rather-than-predicted) refuses to predict the virtualized arm at all. Under the other reading of that slash, P5 would be **wrong**, because virtual is the best arm on bulk; the page's own text settles it, but the slash was sloppy and is recorded as such. The rightness is a null result either way: the page said in advance that P5 *"is the prediction most worth being wrong about"*, because the four named operations are each pure and cannot show a chunked read at its best. Being right about a designed-in blind spot is a fact about the tournament's operation set, not evidence that the method chooses well.

**P6 — right on the three resolvable operations.** Ranks hold across 100, 300 and 1000 on sparse, edit and reorder, on both counters. **One counterexample exists and is recorded rather than suppressed**: on `bulk` boundary bodies, chunked (4, 12, 40) and virtual (20, 20, 20) swap rank between `B = 300` and `B = 1000`. Bulk is unaddressed for every other prediction here for a reason the tournament states, so admitting it against P6 alone would be scoring inconsistently in the direction that hurts — but a reader who holds that measured integers are measured integers reaches **wrong** for P6, and the frozen rule does not adjudicate between them. P6 emits no advice in any case; it is a stability claim, not a choice.

#### The unscored magnitude clauses, recorded because two are false

The rule excludes magnitudes — *"Predicted magnitudes are not scored; none was stated"* — which is itself slightly wrong, since three predictions do state one. They are not scored. They are reported, because a worksheet that hid a falsified clause behind an exclusion would be worthless.

| clause | predicted | measured on the census | note |
|---|---|---|---|
| P1 margin grows monotonically with `B` | yes | holds — 99, 299, 999 | not scored |
| P2 margin grows with `B` | yes | holds on bodies — 100, 300, 1000 | rides on an unaddressed ordering |
| P4 margin larger than P1's | yes | **false** — the edit and sparse cells are *identical* integers | not scored, but it is a model error |

P4's miss is the interesting one. The derivation expected a controlled-input keystroke to cost more than a plain single-field change; on the deterministic counter it costs exactly the same, in all four arms, at all three row counts. That difference, if it exists, lives entirely in the clock — which does not exist.

### The verdict

The §11 deciding rule is *"retain only if blinded calibration predicts useful coarse/fine/chunked choices."* Applied:

**STOP. The method is not retained.**

Four reasons, in descending weight:

1. **The method's actual advice is contradicted where the tournament could speak.** The [rule the three quantities imply](#the-rule-the-three-quantities-imply) orders the arms by across-family co-change: 0.78 → *merge it, go coarse.* The tournament's dispositions narrow `coarse` out of sparse and edit **at every row count**, and on reorder coarse builds `B` rows against fine's 0. The one operation where 0.78 might have been vindicated — bulk — is the one the tournament could not resolve.
2. **[§3.3](#33-the-finding-that-conditions-everything-below) called this in advance, and was right about the wrong thing being right.** It recorded that the committed evidence cannot choose between 0.78 (merge) and 0.33 (keep fine), and that the ambiguity sits on the quantity the deciding rule is most about. Every resolvable operation favours the 0.33 reading. The method's central quantity did not decide, and the direction it leaned unweighted is the direction the measurements went against.
3. **Two right, one wrong, one unaddressed is not "usefully right"** on a four-prediction deciding set — and neither of the two right ones emits advice. P5 is a negative claim about the tournament's operation set; P6 is a stability claim. The single deciding prediction that named a direction between coarse and fine on a resolvable operation is P3, and it is wrong.
4. **Under the primary estimand there is nothing to score.** Reading A returns four of four unaddressed. A method whose calibration is unscoreable on the estimand that defines "beats" has not earned retention on the other one.

**Retention is not close, and it is not made close by re-reading the rule.** Both estimand readings, both counters, and both readings of P5's slash reach stop. The only scoring choice that moves any headline number is P6's, and P6 emits no advice either way.

#### What follows, and what deliberately does not

- **Nothing is retained.** No advisor, no instrument, no runtime tap, no public surface, no production namespace — the same standing phase 1 recorded, now final rather than pending.
- **The §11 row stays at Spike**, which needs no edit: a stop verdict is what leaves it there. It is now *closed at Spike* rather than *awaiting phase 2*.
- **No follow-up programme is proposed.** [The decision brief](decision-brief.md#part-iii--the-plan) closes the decisive experiments with *"no open-ended benchmark programme after it"*, and a spike that failed its deciding rule is the last place to open one. The clock table's absence is `rf2-hic-036`'s record to carry, not a debt this page books.
- **The three quantities stay on the page and stay believable.** Both controls came out right, so the runtime model is not falsified; the [landmark](#the-landmark-reproduced-exactly) against the census still reproduces exactly. What failed is the inference from those quantities to an arm, not the quantities.

### Ambiguities in the frozen rule, recorded for whoever writes the next pre-registration

The rule survived contact, but not intact. Four gaps, each found by needing it and not having it — and each of them was a live decision at scoring time rather than a tidy-up:

1. **The estimand is not named.** "The tournament's measured ordering" was written when the tournament had one estimand in prospect and turned out to have two, one of which never materialised. A pre-registration must name the quantity, not just the comparison.
2. **The counter is not named either.** The census publishes rows-of-markup *and* boundary-bodies, and they disagree in direction on bulk and in rank-stability on P6. The rule has no tiebreak.
3. **Roll-up to the verdict is unstated.** The rule scores cells; the §11 deciding rule wants retain-or-stop; nothing connects them. "Usefully right" stayed a post-outcome judgement, which is precisely what a freeze is supposed to remove. Here it did not matter, because every path reaches stop — that is luck, not design.
4. **`chunked/windowed` in P5 names two arms with a slash.** The page's own gloss settles it, but only because the gloss happens to exist.

**This was seen before the outcomes were visible.** The merged-PR audit of #8150 recorded gaps 1, 3 and the compound-claim problem, and proposed a bounded repair *"before `rf2-hic-036` outcomes are visible."* That window closed unused. The repair is deliberately **not** made now: amending a scoring rule after seeing the results is indistinguishable from tuning it, and a stop verdict reached under an imperfect frozen rule is worth more than a retain verdict reached under a repaired one. Gap 3 is the one that would have mattered had the result been marginal.
