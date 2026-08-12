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

*Written after the criteria above were committed. See [the verdict](#the-verdict).*
