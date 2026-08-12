# Shared read-set notification groups — the census and its pre-registered criteria

This record owns the [specification §11](specification.md#11-innovation-portfolio) row *Shared read-set notification groups*, whose status is **Census, then spike** and whose deciding rule is *"Proceed only for material identical-set fan-out with no singleton/cleanup regression"*. The detailed proposal is in [the left-field lane](lanes/left-field-ideas.md#shared-read-set-notification-groups). Owned by `rf2-hic-083`.

The order in the row is the design. The census runs first, and if the census says shared read-sets are rare then that is the verdict and no spike is owed — a spike run on a population that does not exist inherits the census's error and then looks authoritative.

**Everything above the *Result* heading was written and committed before the instrument existed.** That ordering is the whole value of a pre-registration, so it is checkable rather than asserted: the criteria landed in their own commit on `worker/readset-hic083`, and the census code landed after it. Measured on a tree based at `8f1234311551e70b9825071e5317441922e56489`.

## What is proposed

> Identical `(frame, ordered-read-set)` boundaries may be able to share cell membership and fan notification out, reducing approximately `B x R` memberships toward `R + B`. Census first. Benchmark singleton, shared and distinct-query populations, then stress retry, set replacement, notification cleanup, HMR, reincarnation and multiple roots. Stop if fewer than roughly 10% of real memberships coalesce or the common case regresses.
>
> — [lanes/left-field-ideas.md](lanes/left-field-ideas.md#shared-read-set-notification-groups)

## The unit being counted

A **membership** is one slot in a cell's `.-readers` array. Since `rf2-dabt3` that one slot is simultaneously the sub-key's reverse edge and the boundary's reference to that key's cell, which is why `re-frame.hicasso.impl.inventory/stats` reports it once under two names (`:cell-refs` and `:edges`) rather than counting two structures. It is the quantity the proposal proposes to reduce, so it is the quantity the census counts, and it is counted in the runtime's own table rather than inferred from source.

Two derived quantities, both per read-set entry:

- **`B`** — the committed boundaries sharing that entry. The runtime already holds it: `entry.refs`, incremented by `make-subscribe`'s registration and decremented by its cleanup.
- **`R`** — the distinct sub-keys one of those boundaries acquires. That is `(count entry.set)` and **not** `(alength entry.keys)`. A body that reads one key twice pushes two sub-keys onto the scratch and acquires one cell, because `make-subscribe` walks the SET. A census that counted the key array would over-report memberships on exactly the shape whose read set is duplicated.

## The arithmetic, fixed before any measurement

For one entry with `B` boundaries and `R` distinct keys:

| | memberships |
|---|---|
| today | `B·R` |
| grouped | `R` cell slots held by the group, `+ B` notify slots held by its members |
| saving | `B·R − (R + B)` = **`(B−1)(R−1) − 1`** |

Four consequences follow from that identity alone, and no measurement can move them:

- **`B = 1` — a singleton — saves `−1`.** A boundary whose read set nobody shares pays one extra slot for the group that holds only it.
- **`R = 1` — a one-read boundary — saves `−1`, for every `B`.** A hundred boundaries reading one identical key coalesce to a net loss of one.
- **`B = R = 2` breaks even at zero.**
- **A group pays at all only when `(B−1)(R−1) ≥ 2`.**

So the population that could justify the scheme is narrow and nameable in advance: **several boundaries reading an identical set of several keys**. The census exists to find out whether real applications contain it.

## The criteria

**The default verdict is STOP**, and these are the conditions for anything else. They are prospective: an amendment applies to a later measurement, never to this one.

**C1 — material coalescence.** `coalesced = (M − M′) / M`, where `M = Σ B·R` and `M′ = Σ (R + B)`, pooled over the witness population. `coalesced ≥ 0.10` is required. Below that is STOP, in the row's own words. Because a denominator choice can decide a marginal verdict on its own, the census also reports the **shareable fraction** — memberships living in entries with `B ≥ 2`, an upper bound on what any sharing scheme could ever touch — and C1 is judged against both. A verdict that flips between the two readings is reported as a non-verdict and escalated rather than resolved by picking one.

**C2 — no singleton regression.** The row requires no singleton regression. The identity above fixes a singleton's saving at `−1`, so C2 is unsatisfiable by a uniform scheme: it can be met only by exempting singletons, which is a second path through `subscribe` and a branch on `B` at every commit. A candidate scheme therefore either FAILS C2 by construction or must state its exemption and price it, and the exemption is itself standing cost on the common case — which is what the row's *no singleton regression* clause exists to forbid.

**C3 — no cleanup regression.** Today an unmount releases `R` memberships and arms up to `R` reapers, per boundary. Grouped, the last member's unmount does that work and every other member's does an `indexOf` and a `splice` on the notify list. A candidate must show that no member's cleanup can leave a live cell unheld, and that a group whose cells were disposed under it (see *frame reincarnation* below) cannot be joined by a later boundary that then receives no notifications.

**C4 — fences.** No third hook (I9 is frozen at two per boundary shell — `useContext` and `useSyncExternalStore`); no new public export; no change to the boundary shell; no optional namespace reachable from production when absent. A scheme that needs any of these is reported and stopped, not negotiated.

## What the census must prove about itself

A census is believed, so it is the instrument most worth attacking. Four proofs, all of them executable, none of them a claim in prose:

1. **NON-EMPTY.** It answers a positive membership count on a real population, asserted explicitly. A reporter that is structurally incapable of returning a row reports "clean" and "nothing ran" identically.
2. **POSITIVE CONTROL.** On a population deliberately built to coalesce — several boundaries, identical multi-key read set — it reports a *positive* saving. A census that cannot detect coalescence when coalescence is present has not measured its absence anywhere else.
3. **OVER-REPORT CONTROL.** On a legal population that must not coalesce — every boundary a distinct read set — it reports exactly zero coalesced memberships and a negative saving. Two of this programme's recent reporter defects were over-reports.
4. **CALIBRATION against a landmark.** `Σ B·R` taken entry-side must equal `re-frame.hicasso.impl.inventory/stats`'s `:cell-refs`, which is walked cell-side. The two walks share no code and no table traversal: one sums `refs × |set|` over the entry cache, the other sums `readers.length` over the cell table. The landmark is reproduced exactly before any new number is believed.

## What it REPORTS rather than skips

A reached entry the census cannot resolve is reported, never dropped. Concretely:

- an entry with `refs = 0` — minted by a render that was abandoned, or awaiting the 4 ms reap horizon — is counted in `:unclaimed` and contributes no memberships, rather than vanishing;
- an entry whose `(count set)` differs from `(alength keys)` — a body that read a key twice — is counted in `:duplicate-read-entries` and priced at `|set|`;
- a disagreement between the entry-side and cell-side membership totals is reported as `:divergence` with both numbers. It has two known causes and both are real: an entry evicted by the reap horizon before React claimed it, and a cell disposed under live readers when its frame was destroyed. A census that quietly took one side would hide exactly those.

## Frame reincarnation

A sub-key is `[frame-kw query-v]` — the frame's public keyword, not its incarnation token — so an entry survives a same-id reincarnation while the cells beneath it do not. Any grouping scheme owes an answer here, and it is a C3 obligation rather than a remark: the group holds the cell references on behalf of members that never acquired them, so a group whose cells were disposed at `invalidate-cell!`'s microtask checkpoint is a group a later boundary can join and receive nothing from. Today that failure is unreachable, because every `subscribe` calls `acquire-cell!` and a disposed cell is rebuilt by the next acquisition.

## Result

Measured 2026-08-12 20:29 AUSEST on a tree based at `8f1234311551e70b9825071e5317441922e56489`, by `re-frame.hicasso.readset-group-census-dom-cljs-test` on the browser lane: seven witness applications, each mounted on a real React root, each censused while mounted. React decides how many boundaries exist; the census only counts them.

`rf2-hic-083` names *the slice + editor/grid apps*. All seven were censused instead, because a verdict three applications support and four contradict is a verdict nobody should take, and the other four cost four mounts.

### Per application

| application | entries | memberships `M` | grouped `M′` | saved | entries with `B ≥ 2` | shareable | max `B` | max `R` | shape `{[B R] n}` |
|---|---|---|---|---|---|---|---|---|---|
| slice | 6 | 19 | 25 | −6 | 0 | 0 | 1 | 7 | `{[1 7] 1, [1 4] 1, [1 2] 4}` |
| editor | 7 | 12 | 19 | −7 | 0 | 0 | 1 | 4 | `{[1 4] 1, [1 2] 3, [1 1] 2, [1 0] 1}` |
| grid | 111 | 121 | 232 | −111 | 1 | 11 | 11 | 1 | `{[1 1] 110, [11 1] 1}` |
| todo | 7 | 10 | 17 | −7 | 0 | 0 | 1 | 3 | `{[1 3] 1, [1 2] 1, [1 1] 5}` |
| forms | 7 | 12 | 20 | −8 | 1 | 0 | 2 | 3 | `{[1 3] 2, [1 2] 2, [1 1] 2, [2 0] 1}` |
| typeahead | 2 | 5 | 7 | −2 | 0 | 0 | 1 | 3 | `{[1 3] 1, [1 2] 1}` |
| navigation | 3 | 3 | 6 | −3 | 0 | 0 | 1 | 1 | `{[1 1] 3}` |

### Pooled

| quantity | value |
|---|---|
| read-set entries | 143, all claimed |
| memberships today `M = Σ B·R` | **182** |
| memberships grouped `M′ = Σ (R + B)` | **326** |
| saved | **−144** |
| `coalesced = (M − M′)/M` | **−79.1%** |
| entries more than one boundary holds | **2 of 143** |
| shareable memberships | **11 of 182 = 6.0%** |
| entries that would SAVE a membership | **0** |
| entries that would even BREAK EVEN | **0** |
| landmark divergence, summed over seven applications | **0** |

### The census's own proofs

- **NON-EMPTY** — every one of the seven answered a positive membership count, asserted per application. The pooled 182 is not a zero that a broken reporter and a clean corpus would produce identically.
- **CALIBRATION** — the entry-side walk (`refs × |set|` over the entry cache) reproduced the cell-side landmark (`readers.length` over the cell table, which is `impl.inventory/stats`'s `:cell-refs`) exactly, in all seven, for a pooled divergence of zero. The two walks discriminate: on a five-boundary shared population the entry side is one entry at `5 × 4` and the cell side four cells at five readers; on a five-boundary distinct-query population it is five entries at `1 × 4` and twenty cells at one reader. Both answer 20 by different routes.
- **POSITIVE control** — on a constructed population of five boundaries reading one identical four-key set, the census reports `saved 11` and `coalesced 55%`. It can see coalescence.
- **OVER-REPORT control** — on a legal distinct-query population of the same twenty memberships it reports zero shareable memberships and `saved −5`, with a sign rather than a zero a reader could mistake for neutrality.
- **The landmark can disagree.** Lifting the cell table out from under three live registrations makes the two walks differ by the whole population, and the census reports `divergence 6` and `calibrated? false` with every row still in the answer. Restored, and the restoration verified by re-reading the report.
- **The `|set|` choice is measured, not preferred.** Pricing the key ARRAY instead of the key SET reds five assertions, and the landmark catches it independently — `entry-side 3, cell-side 2`.

### What it REPORTED rather than skipped

On the real corpus: `unclaimed 0`, `duplicate-read-entries 0`, `read-free-entries 2`, `divergence 0`. Those buckets are non-zero on constructed populations — an uncommitted render, a body reading one key twice, four read-free shells — so a clean corpus reads as clean rather than as a reporter that never fires.

The two read-free entries are `examples.editor/editor` and `examples.forms/screen`, and they matter to the arithmetic: at `R = 0` a group costs `B` notify slots against today's nothing. `examples.forms` holds the only multi-boundary read-free entry in the corpus (`[2 0]` — `screen` and `details-form`, both of which read nothing so that a keystroke stops at its own field's row).

## The verdict

**DO NOT ADOPT. The census decides it, and no spike is owed.**

C1 fails on both denominators and is not close on either. `coalesced` is **−79.1%** — grouping does not fail to save memberships on this corpus, it nearly doubles them, from 182 to 326. The generous reading, `shareable`, is **6.0%**, below the row's own roughly-10% trigger. There is no denominator under which this population supports proceeding.

C2 and C3 are not reached, and would not be reachable: **not one of 143 read-set entries would save a membership, and not one would break even.**

### Why, and why it is structural rather than a property of these seven applications

`(B−1)(R−1) − 1` needs both factors, and **`B` and `R` are anti-correlated by construction**. Two boundaries share an identical `(frame, ordered-read-set)` only when neither read carries a per-instance parameter; a boundary with no per-instance parameter is a shell over a page-wide fact; and a shell reads few keys. The two entries in this corpus that more than one boundary holds say it exactly:

- **`examples.grid`** — the largest `B` the corpus offers, and it is `R = 1`. Eleven boundaries share one entry: the `grid` and its ten `grid-row`s, all reading `[::subs/dimensions]` and nothing else. `11 · 1 = 11` memberships today, `1 + 11 = 12` grouped. The single biggest fan-out in seven applications coalesces to a **net loss of one**.
- **`examples.forms`** — two boundaries share one entry at `R = 0`. Grouping would add two notify slots for nothing.

Meanwhile the boundaries with the largest read sets are all singletons. `examples.slice`'s `chrome` is the corpus maximum at `R = 7` — a locale, a theme and five translated strings — and `examples.editor`'s `readout` is `R = 4`, the four committed fields. Each is one of a kind, because a page has one chrome and one readout.

The remaining 141 entries are the shape a real application is mostly made of: `[1 1]` and `[1 2]` — one boundary, its own parameterised key. `examples.grid`'s hundred cells and ten row totals are 110 of them, each reading `[::subs/cell r c]` or `[::subs/row-total r]`. Those are precisely the boundaries a fan-out scheme would want to be about, and their read sets are distinct **because** they are per-row — the parameter that makes a row independent is the same parameter that makes its read set unshareable.

This is not a finding about seven applications. It is what *fine row reads for sparse independent updates* — [specification §Rung 2](specification.md#rung-2--tune-hicasso-topology)'s first recommended topology — costs and buys. An application that produced material identical-set fan-out would be one whose rows all read the same page-wide keys, which is the coarse topology Rung 2 recommends *against* for independent updates.

### The fences, checked rather than assumed

None was reached, because nothing was built. Recorded because a later reader proposing the scheme needs the answers:

- **No third hook is needed, and that is not what stops this.** The scheme fits the frozen two-hook shell: `subscribe` would push the fiber's `onStoreChange` onto the group's notify list and acquire cells only when the group is empty; the cleanup would remove that slot and release the cells when it empties. `useSyncExternalStore` calls `subscribe` once per fiber and holds the cleanup, so no per-instance storage is added and I9 stands. The scheme is refused on its measured value, not on the budget.
- **No new public export**, no boundary-shell change, no optional namespace, no hot-zone file, no npm dependency. The census reads `impl.collector`'s existing public tables and `impl.inventory`'s existing readers, from the test tree.

### Frame reincarnation

The pre-registered obligation, answered for the record even though the scheme is not adopted, because it is the sharpest hazard the scheme carries and a later proposal must not rediscover it.

A sub-key is `[frame-kw query-v]` — the frame's public keyword, not its incarnation token — so a read-set entry survives a same-id reincarnation while the cells beneath it do not. Today every `subscribe` calls `acquire-cell!`, and a disposed cell is simply rebuilt by the next acquisition, so a boundary mounting after a reincarnation is correct by construction.

Under grouping it would not be. A group holds the cell references on behalf of members that never acquired them, so a boundary joining an existing group *skips acquisition entirely* — and a group whose cells were disposed at `invalidate-cell!`'s microtask checkpoint (the frame did not come back before the rebuild window) is a group a later boundary can join and receive no notification from. The value on screen would be right at mount and frozen thereafter, attributable to nothing: the silent-missing-edge failure the collector's own docstring says it is built to make unreachable.

Closing it needs either a per-join validation of the group's cells — which is the acquisition the scheme exists to avoid, restored on the join path — or keying groups by frame incarnation, which multiplies the group table by the thing the entry cache deliberately does not key on. Both are C3 costs, and both are paid on the common case to buy a saving this census measures at less than zero.

### What did not hold at source

Nothing in the proposal was found to be wrong. Its own arithmetic — `B × R` toward `R + B` — is exactly right, and it is what decides against it: the identity `(B−1)(R−1) − 1` is negative wherever either factor is one, and this corpus has no entry where both exceed one.

One premise of the *brief* did not hold, and is recorded because it changed the shape of the work. The read-set entry — the `subscribe`/`getSnapshot` pair — is **already shared** by every boundary reading an identical ordered read set, and has been since the entry cache existed. What is per-boundary is the registration minted inside `make-subscribe`, one per fiber. So the sharing half of the idea is not unbuilt; only the membership half is, and the membership half is the half the arithmetic refuses.

### Standing

The verdict is recorded, not enforced by prose: `the-pooled-population-decides-c1` pins the pooled figure and both trigger inequalities. A witness application that introduced material identical-set fan-out reds it, and the question is re-opened on the new population rather than on this one.
