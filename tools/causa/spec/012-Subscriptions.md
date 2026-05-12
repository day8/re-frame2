# 012-Subscriptions

The Subscription panel is the answer to canonical questions #3 and #4
([`Principles.md`](./Principles.md) §The five canonical questions):

3. **Why is this subscription returning the wrong value?**
4. **Why is this view re-rendering?**

This doc defines two affordances the panel ships with that did not
exist in re-frame-10x v1:

- A **sub-status badge taxonomy** — a small, canonical, shape-paired
  set of badges Causa applies to every row in the sub list and every
  node in the sub graph, so the cache state of every sub is legible at
  a glance (peer landscape: TanStack Query DevTools' fresh / stale /
  fetching / paused / inactive vocabulary).
- An **invalidation-chain affordance** — for any sub that re-ran this
  epoch, a one-click "why did this re-run?" walk **up** the input
  chain to the originating `app-db` slice change (peer landscape:
  Svelte 5's `$inspect` rune, which logs which reactive state caused
  an effect to fire).

Both surfaces are **read-only** projections of data the framework
already emits ([`Principles.md`](./Principles.md) §Observation only —
no new runtime surfaces). The runtime stays cheap; Causa folds the
projections on panel mount and caches per `:epoch-id`.

## Data sources

Causa derives both affordances from three surfaces the framework
already exposes (per [API.md](./API.md) §Trace / epoch surfaces):

| Surface | Spec | What this panel uses it for |
|---|---|---|
| `(rf/sub-cache frame-id)` (CLJS only) | Tool-Pair | The live cache map: per-`[query-v]` cached value, ref-count, input subs, layer (1 / 2 / 3+). |
| `:rf/epoch-record :sub-runs` | [Spec-Schemas §`:rf/epoch-record`](../../../spec/Spec-Schemas.md#rfepoch-record) | The per-cascade `:sub-id` / `:query-v` / `:recomputed?` projection — every sub that re-ran in the cascade just settled. |
| `:rf/epoch-record :db-before` / `:db-after` | Same | The changed-paths derivation ([`004-App-DB-Diff.md`](./004-App-DB-Diff.md) §Changed-paths derivation) used to attribute layer-1 invalidations to specific `app-db` slices. |

No new instrumentation. No new registrar surface. No new trace
events. The panel observes; the framework records.

## Sub-status badge taxonomy

Every sub the panel renders — whether as a row in the list view or
as a node in the graph view — carries **exactly one** sub-status
badge. The badge tells the programmer "what state is this sub's cache
in right now, relative to the epoch under inspection?" without
needing to open the detail pane.

### The five statuses

| Status | When | Visual | Meaning |
|---|---|---|---|
| `:fresh` | The sub was computed this epoch and its value is the current cached value. | Green `●` filled circle. | "This is current. The cache reflects the most recent app-db." |
| `:re-running` | The sub is currently mid-recompute (a layer-2+ recompute is in flight inside the drain). | Cyan `◐` half-filled circle (animated 250ms spin under non-reduced-motion; static under `prefers-reduced-motion`). | "Causa caught this sub mid-recompute. Hold." |
| `:invalidated` | The sub's cache entry was invalidated this epoch (an input changed by `=`) but the sub has no watcher and was therefore not recomputed; a future `subscribe` would recompute. | Yellow `◌` hollow circle. | "Inputs changed; no watcher to drive recompute; next deref will rebuild." |
| `:cached-no-watcher` | The sub has a cached value but zero watchers. Ref-count is zero; the value is whatever was last computed. The cache may be stale (an invalidation could have arrived after the last computation; see §"Stale vs invalidated"). | Tertiary-grey `○` hollow circle. | "Cached, but nothing is watching. The value is from the last time someone asked." |
| `:error` | The most recent attempt to compute this sub threw. The cached value (if any) is from before the throw. | Red `▲` filled triangle + `!` icon. | "This sub's last compute threw. See the error in the detail pane." |

These are the **only** five statuses. The taxonomy is deliberately
small: every additional category is one more thing the programmer
has to learn to read the panel fluently.

### "Stale vs invalidated"

A note on what is **not** in the taxonomy: there is no `:stale`
status. TanStack Query separates `fresh` from `stale` because
network-backed caches age out by wall-clock. re-frame's sub-cache is
value-equality-driven ([Spec 006 §Invalidation algorithm](../../../spec/006-ReactiveSubstrate.md#invalidation-algorithm))
— a sub is either **fresh** (cached value matches what a recompute
would produce, by the input-equality invariant) or **invalidated**
(an input changed, so the cached value is suspect). There is no
"stale-but-cached" middle ground in the framework; introducing one
in the panel would create a category that has no runtime referent.

The closest analogue — "cached, but the cache may be lying because
no watcher refreshed it after an invalidation" — is `:cached-no-watcher`,
and Causa flags it as a separate badge precisely because the value
on display **is** the last-computed one, and the programmer must
know that distinction. The tooltip on `:cached-no-watcher` reads:
"Cached, no watcher. Value is from the last computation; an
invalidation may have landed since."

### Colour is never alone

Per [`007-UX-IA.md`](./007-UX-IA.md) §"Colour is never alone" and the
[`Principles.md`](./Principles.md) §"Colour is never alone" principle,
each status pairs **colour + shape + tooltip-label**. The
colour-blind path is reachable without ever relying on hue:

- `:fresh` — green **filled circle** `●` + tooltip "Fresh".
- `:re-running` — cyan **half-filled circle** `◐` + tooltip
  "Re-running".
- `:invalidated` — yellow **hollow circle** `◌` + tooltip
  "Invalidated".
- `:cached-no-watcher` — tertiary-grey **hollow circle** `○` +
  tooltip "Cached, no watcher".
- `:error` — red **filled triangle** `▲` + `!` icon + tooltip
  "Error".

Shape carries the taxonomy:

```
●  ◐  ◌  ○  ▲!
fresh  rerun  invalid  cached  error
```

The badge sits at the **left margin** of the sub row (or as the
node-glyph in the graph view), 14px on cosy density, before the
`sub-id`. Hover surfaces the tooltip after 250ms; right-click opens
the action menu (which always carries "Show invalidation chain" — see
§Invalidation-chain affordance below).

### Where badges appear

The badge is the **canonical sub-status surface**. It renders in
every surface that lists or graphs subs:

- **Sub list view** (the panel's default). Left-margin badge on every
  row.
- **Sub graph view** (force-directed graph, opt-in). The node glyph
  is the badge.
- **Event-detail panel's "Subs recomputed" section** ([`000-Vision.md`](./000-Vision.md)
  §The 16-panel inventory, Event detail row). Each entry carries its
  status as of the cascade that just settled.
- **Causality graph's per-dispatch sub-attribution chip** ([`001-Causality-Graph.md`](./001-Causality-Graph.md)).
  When a dispatch node is expanded, the subs that ran inside it list
  with their post-cascade status.
- **Command palette sub matches** ([`007-UX-IA.md`](./007-UX-IA.md)
  §Command palette). Each sub row in the palette carries its current
  status, so the user can `Ctrl+K` → type a sub-id → see the cache
  state in one motion.

The taxonomy is **single-source-of-truth**: the same five badges
mean the same five things everywhere Causa renders a sub.

### Filtering and grouping

The sub list's header row carries five toggleable filter chips
(one per status). Clicking `:invalidated` filters the list to subs
in that state; `Shift+`-clicking adds to the filter; the empty
filter shows all. Group-by-status is the default sort (errors first,
then re-running, then invalidated, then fresh, then
cached-no-watcher).

Status counts surface in the sidebar activity badge per
[`007-UX-IA.md`](./007-UX-IA.md) §Activity badges: an `●N` next to
`Subscriptions` lists `:error` + `:invalidated` count (the two
states a programmer typically cares about) so the panel
self-announces when something interesting has accumulated.

## Invalidation-chain affordance

The five-question framing's #4 — **"Why is this view re-rendering?"** —
is answered in re-frame at the **subscription** layer. A view
re-renders because a subscription it reads changed value. That
subscription changed because **one of its inputs** changed value.
That input — if itself a sub — changed because of *its* input. The
chain bottoms out at a layer-1 sub reading a slice of `app-db` that
the cascade just modified.

Walking that chain by hand is tractable on a small app and
exhausting on a large one. The Subscription panel ships a one-click
walk.

### What the affordance renders

For **any** sub that re-ran this epoch (i.e. any sub present in the
epoch record's `:sub-runs` projection), the panel surfaces an
**Invalidation chain** view:

```
┌─ Invalidation chain — [:cart/total]  (epoch 14) ──────────────┐
│                                                                │
│  [:cart/total]            layer 3  ● recomputed                │
│      ↑ input changed                                           │
│  [:cart/items]            layer 2  ● recomputed                │
│      ↑ input changed                                           │
│  [:cart/items-raw]        layer 1  ● recomputed                │
│      ↑ slice changed                                           │
│  [:cart :items]           app-db slice  (modified)             │
│      origin: epoch 14 · :cart/add-item · events.cljs:213       │
│                                                                │
│  [Open in App-DB Diff ▸]  [Open in Causality graph ▸]          │
└────────────────────────────────────────────────────────────────┘
```

The chain reads **up** (newest → oldest, derived → root): the sub
under inspection is at the top, the originating `app-db` slice
change is at the bottom. Each link carries:

- The sub's `query-v` and its layer (1 / 2 / 3+).
- The status badge for the just-settled cascade (always `:fresh` on
  the recompute path; `:error` if the recompute threw partway down).
- The link-reason: `input changed` between sub layers; `slice
  changed` from layer-1 sub down to the `app-db` path.

At the bottom of the chain, the **originating slice** is named —
the `[:cart :items]` path that changed in this cascade, with the
dispatch that touched it (`:cart/add-item`), the cascade's
event-detail link, and the source-coord chip (per
[`API.md`](./API.md) §Open in editor).

### How the chain is computed

Pure projection over data the framework already records. No new
instrumentation.

1. **Start with the focused sub.** Either: the row the user
   right-clicked → "Show invalidation chain"; or the sub the
   event-detail panel highlighted as the trigger of a recently-fired
   render (per `:rf/epoch-record :renders :triggered-by`); or
   `Ctrl+K` → type sub-id → "Show invalidation chain".

2. **Walk the sub-cache's per-entry input list down to layer-1.**
   Each cache entry carries its input subs ([Spec 006 §Subscription
   cache — contract and operational
   semantics](../../../spec/006-ReactiveSubstrate.md#subscription-cache--contract-and-operational-semantics)).
   At each level, restrict to the inputs whose value **changed in
   this cascade** — they're the subs present in `:sub-runs` with the
   right layer. Drop inputs that did not re-run; they did not
   contribute to the invalidation.

3. **At layer-1, attribute to changed `app-db` slices.** A layer-1
   sub reads one or more paths from `app-db`. Cross-reference the
   layer-1 sub's input-paths against the cascade's changed-paths set
   (the same set [`004-App-DB-Diff.md`](./004-App-DB-Diff.md) §Changed-paths
   derivation already computes). The intersection is the originating
   slice or slices.

4. **Render up.** The chain renders top-down on screen (sub under
   inspection first), bottom-down in causality (originating slice
   last).

The walk is **O(depth)** in the sub graph, bounded by the
panel-level cap on display depth (default 8 — see §"Diamond
dependencies" below). Sub graphs deeper than the cap render with a
`▸ (N more layers above)` row at the top; click to expand.

### Diamond dependencies

A re-running sub may have **two** input chains that both contributed
to the invalidation (e.g. a sub joins
`[:cart/items]` and `[:user/auth]` and both moved this cascade).
The chain affordance handles this by rendering **branches**:

```
┌─ Invalidation chain — [:cart/checkout-button-state] ──────────┐
│                                                                │
│  [:cart/checkout-button-state]   layer 3  ● recomputed         │
│      ↑ inputs changed                                          │
│      ├─ [:cart/items]       layer 2  ● recomputed              │
│      │     ↑ slice changed                                     │
│      │  [:cart :items]      app-db slice  (modified)           │
│      │                                                         │
│      └─ [:user/auth]        layer 2  ● recomputed              │
│            ↑ slice changed                                     │
│         [:user :auth-token] app-db slice  (modified)           │
└────────────────────────────────────────────────────────────────┘
```

Branches collapse by default to the *deepest* branch (the one with
the most layers); click `▸` on a branch head to expand the others.
The "most-deep first" heuristic is empirical — when a sub has
multiple contributing branches, the deeper branch is usually the
"surprising" one.

### Trigger points

The affordance is reachable from every surface that names a sub
(per §"Where badges appear" above), via a uniform right-click
action **"Show invalidation chain"**. From the keyboard:

| Context | Key | Action |
|---|---|---|
| Sub focused in panel (list or graph view) | `i` | Open invalidation chain. |
| Sub row focused in event-detail's "Subs recomputed" | `i` | Same. |
| App-DB slice focused in App-DB Diff | `i` | "Subs that re-ran because of this slice" — the **reverse** view (see §"Reverse: from a slice to its dependents" below). |

The mnemonic is `i` for **i**nvalidation. (Distinct from the global
`i` jumps-to-Issues panel — the per-panel `i` only fires when a sub
or slice is focused; otherwise `i` propagates to the global handler.)

### Reverse: from a slice to its dependents

The complement of "walk up from a sub" is "walk down from a slice."
When the user focuses an `app-db` slice in [`004-App-DB-Diff.md`](./004-App-DB-Diff.md)
and presses `i`, the canvas pivots to:

```
┌─ Subs invalidated by [:cart :items] this epoch ───────────────┐
│  ● [:cart/items]               layer 1  recomputed             │
│  ● [:cart/items-summary]       layer 2  recomputed             │
│  ● [:cart/total]               layer 3  recomputed             │
│  ● [:cart/checkout-button…]    layer 3  recomputed             │
│  ◌ [:cart/coupon-eligible]     layer 2  invalidated (no watcher)│
└────────────────────────────────────────────────────────────────┘
```

The list is sorted layer-ascending (the layer-1 sub that read the
slice directly, then its dependents up the graph). Clicking any
entry opens its forward invalidation-chain view (above).

The reverse view answers "I changed this slice; what re-ran?" — the
flip-side of "this sub re-ran; what slice caused it?" Together
they're a navigable two-way bridge between the App-DB Diff panel
and the Subscription panel.

### Cross-references to other panels

The invalidation-chain view is **not** a peer panel; it's a Subscription
panel surface that links into other panels:

- **App-DB Diff** ([`004-App-DB-Diff.md`](./004-App-DB-Diff.md)) — the
  originating slice's "Open in App-DB Diff" button rebases the App-DB
  panel to this epoch with the slice focused. Combined with App-DB
  Diff's "Show me when this changed" affordance, the user walks
  forward through the slice's history of changes.
- **Causality graph** ([`001-Causality-Graph.md`](./001-Causality-Graph.md))
  — the originating dispatch's "Open in Causality graph" button filters
  the graph to the cascade rooted at that dispatch, then highlights
  the node.
- **Time-travel scrubber** ([`002-Time-Travel.md`](./002-Time-Travel.md))
  — the chain renders the epoch number alongside each step; clicking
  the epoch chip rebases the scrubber view (passive, per §"The
  passive-scrubbing rule"). The view rebases; the runtime does not.

This is the same shape as [`004-App-DB-Diff.md`](./004-App-DB-Diff.md)
§"Show me when this changed" — a high-leverage right-click affordance
that pivots Causa from "I notice this is wrong" to "show me why" in
two clicks.

## JVM behaviour

The Subscription panel is **CLJS-only** in the same way
`(rf/sub-cache frame-id)` is CLJS-only (per [API.md](./API.md) §Trace
/ epoch surfaces). JVM-hosted Causa surfaces — pair-tool dashboards
that render epoch records server-side — render the badges from
`:rf/epoch-record :sub-runs` (every entry has `:recomputed? true`,
i.e. `:fresh` post-cascade), but the live cache-state distinction
(`:invalidated` vs `:cached-no-watcher`) requires the sub-cache and
therefore degrades to "show the cascade-projected status only" on
the JVM. The invalidation-chain affordance is similarly best-effort:
the chain renders from `:sub-runs` correlations, but live ref-counts
are unavailable.

This is consistent with the framework split — JVM keeps the data
plane; the reactive cache is a CLJS-substrate concern.

## Production elision

Per [`Principles.md`](./Principles.md) §Production elision is
non-negotiable, the Subscription panel — like every Causa panel —
elides entirely in production builds. The badge taxonomy and the
chain affordance ship zero bytes when `goog.DEBUG=false`.

CI's `npm run test:elision` ([`007-UX-IA.md`](./007-UX-IA.md)
§Production posture) verifies the contract.

## Performance

- **Badge derivation is O(visible rows).** Each row reads its own
  cache entry; the panel virtualises long lists (per
  [`007-UX-IA.md`](./007-UX-IA.md) §Performance budget; never
  DOM-mounts more than ~200 rows at once).
- **Chain walk is O(depth)** in the sub graph, capped at 8 layers
  by default. Diamonds expand only on click.
- **Chain caching** per `(:epoch-id, focused-sub-id)`. A second
  render of the same chain for the same epoch is O(1).
- The `:re-running` animated badge is a CSS transform spin; one rAF
  per spin tick; respects `prefers-reduced-motion` (falls to a
  static half-filled circle).

If the panel's render budget approaches the INP target ([`007-UX-IA.md`](./007-UX-IA.md)
§Performance budget), the panel cuts non-visible animation first;
badges and chains are correctness-critical and never elided at runtime.

## Embedding

The Subscription panel ships as `day8.re-frame2-causa.panels.subscriptions/Panel`
([`008-Embedding-Contract.md`](./008-Embedding-Contract.md) §The
Panel component contract; [`API.md`](./API.md) §`day8.re-frame2-causa.panels`).
Embeddability is **v1.1** (per
[`008-Embedding-Contract.md`](./008-Embedding-Contract.md) — graph
rendering is expensive for many small embeds); v1.0 ships the panel
as a standalone Causa surface only.

## Cross-references

- [`Principles.md`](./Principles.md) §The five canonical questions
  (questions #3 and #4 are what this panel answers).
- [`007-UX-IA.md`](./007-UX-IA.md) §"Colour is never alone" (the
  shape-paired colour discipline the badge taxonomy honours).
- [`004-App-DB-Diff.md`](./004-App-DB-Diff.md) §Changed-paths
  derivation (the layer-1 attribution input).
- [`002-Time-Travel.md`](./002-Time-Travel.md) §The passive-scrubbing
  rule (the affordance never auto-rewinds).
- [`001-Causality-Graph.md`](./001-Causality-Graph.md) §Edges (the
  chain's link to the causality view).
- [Spec 006 §Subscription cache — contract and operational semantics](../../../spec/006-ReactiveSubstrate.md#subscription-cache--contract-and-operational-semantics)
  (the runtime contract this panel observes).
- [Spec-Schemas §`:rf/epoch-record`](../../../spec/Spec-Schemas.md#rfepoch-record)
  (the `:sub-runs` projection the panel folds).
