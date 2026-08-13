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
