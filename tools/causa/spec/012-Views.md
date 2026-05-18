# 012-Views

## Bug class

**"Component re-rendered 50 times when I dispatched once — why?"** And:
**"Why is this subscription returning the wrong value?"**

The render-cascade is invisible from the component. A dispatch
invalidates N subs; each invalidated sub re-runs (or hits cache); each
re-run sub triggers re-render of every view subscribed to it. The
author sees the symptom (excess renders, stale value) but not the
chain.

## Example bug

You dispatched `:cart/add-item`. You expected 1 view to re-render
(`cart-summary`). The Views tab shows 50 view renders for THIS
cascade. You don't know which sub invalidated which view, or whether
some views re-rendered identically (wasted work) vs because their
inputs actually changed.

## Insight Causa provides

A **three-group view list** (mounted / re-rendered / unmounted)
attributed to the focused cascade. **Each view row carries its subs
nested beneath** — the subs the view actually consumed, with their
return values inline. Per-sub click → invalidation-chain drilldown
("why did this sub re-run"). Cluster-large-grids (≥50
same-identity-key) collapse into a single row with count. Heatmap
mode tints rows by render cost.

## Affordance

Views tab — three-group layout, nested subs, per-component
drilldown headline (props-diff). Replaces the pre-rewrite
Subscriptions panel — subs are nested under their views, not a
separate tab.

---

The Views tab (tab 3 of 6 in the 4-layer chrome — see
[`018-Event-Spine.md`](018-Event-Spine.md) §5) is the answer to two of
the five canonical questions:

3. **Why is this subscription returning the wrong value?**
4. **Why is this view re-rendering?**

This spec replaces the pre-rewrite Subscriptions panel
(`012-Subscriptions.md`). The structural change: **subscriptions are
no longer a top-level tab**; they nest under the views that consumed
them. The view (not the sub) is the natural unit developers reason
about — they wrote the components; subs are upstream cause. Subs hang
beneath each view, with their return values visible inline.

## Purpose

Answer: "which views rendered in this cascade, and why?" Plus, for
each rendered view, expose the subscriptions it consumed and their
return values inline — so the same tab also answers "why did this view
recompute?" without a separate panel.

The Views tab is **isolation-scoped to the selected frame ONLY**. Per
[`018-Event-Spine.md`](018-Event-Spine.md) §8 I3, Causa-internal
renders (`:rf/causa`-namespaced) MUST NOT appear in the host
frame's Views panel.

## Three-group layout

Each cascade's rendering activity splits into three temporal groups,
in fixed top-to-bottom order:

```
┌─ Views tab content (frame: :app/main · cascade #347) ──────────────────────────────────┐
│                                                                                          │
│ ── Mounted this cascade (2) ─────────────────────────────────────────────────────────── │
│                                                                                          │
│   ◆ <OrderRow id=92>                                src/cart/views.cljs:118  ↗          │
│     subs used (2)                                                                        │
│       :cart/order [92]        → {:id 92 :total 47.50 …}                                 │
│       :ui/theme               → :dark                                                    │
│     ⏱ 1.2ms · mount-reason: parent <OrderList> conj'd this child                        │
│                                                                                          │
│   ◆ <RetryButton order-id=92>                       src/cart/views.cljs:204  ↗          │
│     subs used (1)                                                                        │
│       :cart/can-retry? [92]   → true                                                     │
│     ⏱ 0.4ms                                                                              │
│                                                                                          │
│ ── Re-rendered this cascade (4) ─────────────────────────────────────────────────────── │
│                                                                                          │
│   ┌─ <OrderList> ─────────────────────┬─ Rerendered because ─────────────────────────────┐ │
│   │ src/cart/views.cljs:84  ↗         │ ✱ :cart/orders            → [<92 92> <91 91>]│ │
│   │ ⏱ 0.7ms                            │   (was: [<92> <91 91>] — element 0 changed)  │ │
│   │ ▾ expand for diff                  │ · :ui/theme               → :dark             │ │
│   └────────────────────────────────────┴───────────────────────────────────────────────┘ │
│                                                                                          │
│   ┌─ <CartTotal> ─────────────────────┬─ Rerendered because ─────────────────────────────┐ │
│   │ src/cart/views.cljs:62  ↗         │ ✱ :cart/subtotal          → 47.50            │ │
│   │ ⏱ 0.2ms                            │   (was: 42.00)                                │ │
│   │ ▾ expand for diff                  │ · :cart/items             → [{…} {…}]         │ │
│   └────────────────────────────────────┴───────────────────────────────────────────────┘ │
│                                                                                          │
│   ┌─ <MyCell> × 1000 (clustered) ─────┬─ Rerendered because ─────────────────────────────┐ │
│   │ src/grid/views.cljs:42  ↗         │ ✱ :grid/cell-data [args vary per instance]   │ │
│   │ ⏱ 12.4ms total · 12µs avg          │   (each instance invalidated by own data)    │ │
│   │ [Expand cluster ▾]                 │                                               │ │
│   └────────────────────────────────────┴───────────────────────────────────────────────┘ │
│                                                                                          │
│ ── Unmounted this cascade (1) ───────────────────────────────────────────────────────── │
│                                                                                          │
│   ◇ <EmptyCartBanner>                               src/cart/views.cljs:240  ↗          │
│     subs used at unmount (1)                                                             │
│       :cart/empty?            → false  (was true)                                        │
│     unmount-reason: parent <CartShell> conditional flipped                               │
│                                                                                          │
│ ─────────────────────────────────────────────────────────────────────────────────────── │
│ Heatmap mode ○        Show framework-internal ○        Group by ◉ component  ○ sub      │
└──────────────────────────────────────────────────────────────────────────────────────────┘
```

### Group gutters

| Glyph | Group |
|---|---|
| `◆` filled diamond | Mounted |
| `◐` half-circle (rendered as two-column Re-rendered card; see below) | Re-rendered |
| `◇` open diamond + row text struck-through | Unmounted |

### Per-row content (Mounted + Unmounted groups)

Single-column row:

- Component name (`<ComponentName>`).
- Source coords (`↗ open in editor`).
- Nested **subs used (N)** list: `:sub-id [args] → return-value` (uses
  `inspect-inline` per the §Renderer below).
- Render duration in ms.
- Reason (mount-reason for Mounted; unmount-reason for Unmounted).

Unmounted rows show the subs the component had subscribed to with
last-known values shown but greyed — so the user can see what data the
component was reading immediately before it disappeared.

### Per-row content (Re-rendered group — two-column "Rerendered because")

The Re-rendered group uses a structured two-column row layout that
surfaces "which sub change caused this re-render" as first-class
content. **Developer-framed framing**: the column is titled "Rerendered
because" (not "Invalidated by") — it answers the developer's question
("why did this view re-render?") rather than the substrate's view of
the world ("which subs invalidated"). The underlying data shape
(`:invalidated-by` slot, `:rf.causa/views-*` sub keywords) is internal-
data and remains unchanged.

- **Left column (~40% width):** component identity, source coords,
  render duration, expand-caret for inline drilldown (§Per-component
  drilldown).
- **Right column (~60% width):** "Rerendered because" list.
  - Each consumed sub gets a row.
  - Trigger sub(s) marked with `✱` (amber, bold) at the front;
    non-trigger subs marked with `·` (muted-grey). Each marker
    ships a hover tooltip explaining its meaning:
    - `✱` → "Value changed since last cascade — likely cause of
      the re-render."
    - `·` → "Sub recomputed, value unchanged — React skipped
      re-render of any view reading only this sub."
  - For each trigger sub, the previous-vs-new value shown in `(was:
    …)` on the next line; if previous value was large, `inspect-diff`
    summary used.
  - Multiple triggers → multiple `✱` rows; the most-likely-cause
    (deepest in dependency graph) is rendered first.
  - Parent-forced re-render (no changed sub) → "Rerendered because" reads
    `✱ <parent component> re-rendered → forced child re-render` (no
    sub-id; parent is the trigger).
  - Clustered renders (per §Grid-explosion clustering) → single `✱`
    row pointing to the cluster's `dispatched-from` sub-id; per-instance
    triggers vary; aggregate "args vary per instance" annotation.

The two-column layout is **exclusive to Re-rendered**. Mounted +
Unmounted groups use the single-column layout above.

### Re-render reason heuristics

The render-tracker tags each re-render with the changed-sub-id (the
sub whose return value diverged from cache). Multiple changed subs →
each appears as a `✱` row. No changed sub → "parent re-rendered"
(forced child re-render).

## Grid-explosion clustering

A 20×50 grid of `<MyCell>` produces 1000 simultaneous renders —
listing each is a non-starter. Clustering:

| Rule | Behaviour |
|---|---|
| Identity key | `(component-name, dispatched-from-sub-id)` — "MyCell instances all reading the same sub chain" cluster together |
| Threshold | ≥ 50 renders sharing the same identity key → auto-cluster into ONE row showing aggregate stats |
| Aggregate row format | `<MyCell> × 1000 · ⏱ 12.4ms total · 12µs avg · 47µs p95 · [Expand cluster ▾]` |
| Expand affordance | Click `[Expand cluster ▾]` → inline virtualised list of individual renders; per-row props differ; sub return value identical (by definition of clustering on sub-id) |
| Below threshold | < 50 renders → list individually (standard per-row layout) |

```
Cluster collapsed (default at ≥ 50 renders):

   ◐ <MyCell> × 1000  (clustered)                 src/grid/views.cljs:42   ↗
     dispatched-from: :grid/cell-data
     ⏱ 12.4ms total · 12µs avg · 47µs p95
     [Expand cluster ▾]

Cluster expanded (after click):

   ◐ <MyCell> × 1000  (clustered)                 src/grid/views.cljs:42   ↗
     dispatched-from: :grid/cell-data
     ⏱ 12.4ms total · 12µs avg · 47µs p95
     [Collapse cluster ▴]
     ┌─ Instances (virtualised; 1000 rows) ───────────────────────────────┐
     │ <MyCell {:row 0 :col 0}>     :grid/cell-data [0 0]  → 42     11µs │
     │ <MyCell {:row 0 :col 1}>     :grid/cell-data [0 1]  → 17     12µs │
     │ <MyCell {:row 0 :col 2}>     :grid/cell-data [0 2]  → 3      10µs │
     │ …                                                                  │
     └────────────────────────────────────────────────────────────────────┘
```

Threshold configurable via Settings → Buffer → `:views/cluster-threshold`
(default 50).

## Heatmap mode

For cascades where per-row enumeration is overwhelming even after
clustering (hundreds of distinct clusters), toggle the **Heatmap
mode** chip in the bottom controls. Replaces the three-group row
layout with a single panel-wide horizontal bar showing where the
cascade's render time was spent:

```
┌─ Views tab content · Heatmap mode (frame: :app/main · cascade #347) ────────────────────┐
│                                                                                          │
│ Cascade render budget: 23.8ms (tier ●)                                                   │
│                                                                                          │
│ ┌──────────────────────────────────────────────────────────────────────────────────────┐│
│ │ <MyCell>×1000    │ <OrderList>│<OrderRow>│<RetryB>│<CartTotal>│<CartIcon>│ <rest> │ ││
│ │      52%         │    18%     │   12%    │   8%   │    4%     │   1%     │   5%   │ ││
│ │      12.4ms      │   4.3ms    │  2.9ms   │ 1.9ms  │   0.9ms   │   0.2ms  │  1.2ms │ ││
│ └──────────────────────────────────────────────────────────────────────────────────────┘│
│                                                                                          │
│ Click a segment to filter the three-group layout to that component.                      │
│ Hover a segment for per-component breakdown (mount/re-render/unmount counts).            │
│                                                                                          │
│ Heatmap mode ●        Show framework-internal ○        Group by ◉ component  ○ sub      │
└──────────────────────────────────────────────────────────────────────────────────────────┘
```

- **Segment width** = % of cascade's total render time consumed by
  that component (sums to 100%).
- **Segment colour** = tier shading (cool → warm by ms cost).
- **`<rest>` segment** aggregates components whose share < 1% to keep
  the bar legible.

### Segment interaction

| Action | Result |
|---|---|
| **Click segment** | (1) Flips back out of Heatmap mode to the three-group layout; (2) applies a per-component filter so only that component's mount/re-render/unmount rows are visible. Single action = "I saw the heatmap; the next thing I want is detail on the hot one." |
| **Hover segment** | Tooltip with per-component breakdown (mount/re-render/unmount counts + per-instance avg/p95) |
| **Right-click segment** | Context menu: "Filter to this component" / "Hide this component" (Views-local OUT filter) / "Copy component name" / "Pin in palette" |
| **Shift-click segment** | Adds the segment's component to a multi-component filter set; visible as filter chips above the three-group layout. Rare; documented |
| **ESC while in Heatmap** | Return to three-group layout (no filter) |

Heatmap mode is OFF by default; toggle persists per-cascade-size
threshold (auto-suggests heatmap when cluster-count > 20 after
clustering).

## Per-component drilldown

Clicking a component row in any group (mounted / re-rendered /
unmounted) **expands inline** (NOT to a right-pane — Causa's L4 layout
is single-column scroll). Re-rendered rows already use the two-column
shape; the expand-caret reveals additional content BELOW the row
inline. Mounted/unmounted rows expand to a single-column content
block.

### Expansion content (top-to-bottom)

```
┌─ <CartTotal> · expanded ─────────────────────────────────────────────────────────────────┐
│ src/cart/views.cljs:62  ↗                                                                │
│                                                                                          │
│ ── Props diff (headline) ──────────────────────────────────────────────────────────────  │
│   :items             [{…} {…}]                                                           │
│     0 ▸ {:id 92 :qty 2}      → {:id 92 :qty 3}     (qty changed)                        │
│     1 ▸ {:id 91 :qty 1}      (unchanged)                                                 │
│   :subtotal          42.00   → 47.50                                                     │
│   :currency          "USD"   (unchanged)                                                 │
│                                                                                          │
│ ── Subs consumed ──────────────────────────────────────────────────────────────────────  │
│   ✱ :cart/subtotal              → 47.50      (was: 42.00)                                │
│   · :cart/items                 → [{…} {…}] (unchanged from cache)                       │
│                                                                                          │
│ ── Reason ─────────────────────────────────────────────────────────────────────────────  │
│   re-render-reason: :cart/subtotal return changed                                        │
│                                                                                          │
│ ── Render timing (when React profiler available) ──────────────────────────────────────  │
│   render phase   0.18ms                                                                  │
│   commit phase   0.02ms                                                                  │
│   effects        n/a (no useEffect)                                                      │
│                                                                                          │
│ ── React Fiber metadata (collapsed) ──────────────────────────────────────────────────   │
│   ▸ Fiber type · key · ref · ...                                                         │
└──────────────────────────────────────────────────────────────────────────────────────────┘
```

- **Headline content = props diff** — the diff is what the developer
  is most-often after: "what changed about this component's input that
  the framework saw?" Rendered via `inspect-diff` per the §Renderer.
- **Subs consumed** repeats the Re-rendered group's "Rerendered because"
  content (trigger marked with `✱`, sub return value, was-value). For
  mounted: "Subs consumed at mount" (no diff — first read). For
  unmounted: "Subs consumed at unmount" (last-known values from cache,
  greyed).

  Expanding a Re-rendered row whose `Rerendered because` list contains
  a `·` (recomputed-but-equal) sub shows an explicit drilldown
  treatment per sub instead of an empty diff space:

  ```
  · :user/profile   ✓ No change.  Sub recomputed; value = previous.
                    (React skipped re-render.)
                    [Why?] → reaction-lifecycle explainer (inline expander)
  ```

  Trigger subs (`✱`) drill into the structural-diff renderer; non-
  trigger but recomputed subs (`·`) drill into the "No change" chip.
  Both treatments are anchored on the same marker the row column
  uses, so the marker → drilldown affordance is consistent.
- **Reason** echoes the row-level reason for in-context disclosure.
- **Render timing** appears WHEN React profiler data is available
  (host app has `Profiler` mounted, or `React.unstable_*` API
  available). Otherwise omitted. No fake data.
- **React Fiber metadata** is collapsed by default; clicking expands.
  Includes Fiber type/key/ref/childIndex/sibling — useful for
  debugging key collisions, list rendering issues. Only present when
  React internals are accessible.

### Why inline expansion (not right-pane)

1. **Layout consistency** — Causa's L4 layout is single-column scroll.
   Adding a right-pane to ONE tab (Views) breaks the pattern.
2. **Scroll position preserved** — clicking another row collapses the
   previous; the user sees the new row in context (still in the
   three-group structure they were navigating).
3. **Multiple expansions** — allowed; user can keep two-three expanded
   at once for comparison.
4. **Mobile/narrow popout safety** — 560px-wide popout has no room for
   a right-pane; inline expansion just makes the row taller.

## Group-by toggle

Default group-by is `component` (the three temporal groups, then
per-component rows). Alternate group-by is `sub` — flips the
hierarchy: each sub that contributed to the cascade gets a parent row,
with the views that consumed it as children. Useful when the user is
reasoning "which sub triggered all this rendering?" rather than "which
views ran?" The two views are mathematically symmetric; the toggle
lets the user pick their entry point.

## Sub-status legibility

Subs nested under each view row carry sub-cache-state information
inline. The classic 10x sub-status (fresh / re-running / invalidated
/ cached-no-watcher / error) is rendered as a coloured glyph prefix
on the sub-id when the cache state is interesting:

| Status | Glyph + colour | Meaning |
|---|---|---|
| `:fresh` | (no glyph; default) | Cache reflects current app-db |
| `:re-running` | cyan `◐` | Mid-recompute inside the drain |
| `:invalidated` | yellow `◌` | Inputs changed; no watcher to drive recompute |
| `:cached-no-watcher` | tertiary `○` | Cached but zero watchers; value may be stale |
| `:error` | red `▲ !` | Most recent compute threw |

Compared to the legacy Subs panel, this is a leaner surface — the sub
no longer gets its own row; it's a decorator on the view's "subs used"
list. The detailed cache-state taxonomy is preserved inline.

## Data sources

Causa derives the Views tab from data the framework already records.
No new instrumentation, no new registrar surface, no new trace events.
The panel observes; the framework records.

| Surface | Spec | Use |
|---|---|---|
| `(rf/sub-cache frame-id)` (CLJS only) | Tool-Pair | The live cache map: per-`[query-v]` cached value, ref-count, input subs, layer (1 / 2 / 3+). Drives sub-status decoration. |
| `:rf/epoch-record :sub-runs` | [Spec-Schemas §`:rf/epoch-record`](../../../spec/Spec-Schemas.md#rfepoch-record) | Per-cascade `:sub-id` / `:query-v` / `:recomputed?` — every sub that re-ran. |
| `:rf/epoch-record :renders` | Same | Per-cascade list of component-render entries; tagged with `:owning-frame` (per Spec 009 / render-tracker). |
| `:rf/epoch-record :db-before` / `:db-after` | Same | Changed-paths derivation used to attribute layer-1 invalidations to specific `app-db` slices. |
| Render-tracker `:owning-frame` tag | Spec 009 / [`018-Event-Spine.md`](018-Event-Spine.md) §8 I3 | The isolation filter: `(filter #(= (:owning-frame %) (sub :rf.causa/focus.frame)) renders)`. |

## Isolation invariant (I3)

The Views panel's render projection is filtered to the selected frame
ONLY. Causa-internal renders (the Views panel itself, the event list,
the ribbon, etc. — all under `:rf/causa`) MUST NOT appear, even when
both `:rf/causa` and the inspected host frame mount under the same
React root.

**Implementation:** render-tracker tags each entry with
`:owning-frame` at capture time; Views panel applies the filter shown
in the table above.

**Test gate:** browser feature test asserts no Causa-namespaced
component appears in `:rf/default`'s Views panel after triggering
Causa-internal hover-renders. Per
[`018-Event-Spine.md`](018-Event-Spine.md) §8 I4 — **failure blocks
merge**.

## Renderer

Sub return values use `inspect-inline` (one-line, tail-elided); click
expands to `inspect` (expandable hero). Props diffs use `inspect-diff`
(side-by-side or unified). All three live in
`tools/causa/src/day8/re_frame2_causa/theme/data_inspector.cljc` — see
[`007-UX-IA.md`](007-UX-IA.md) §Detail panel renderer.

The renderer respects [spec/015 data classification](../../../spec/015-Data-Classification.md):
`:rf/redacted` → `[● REDACTED N]` magenta opaque; `:rf/large` → `[●
ELIDED · N bytes]` yellow drillable. See
[`018-Event-Spine.md`](018-Event-Spine.md) §12.

## JVM behaviour

The Views tab is **CLJS-only** in the same way `(rf/sub-cache
frame-id)` is CLJS-only. JVM-hosted Causa surfaces — pair-tool
dashboards that render epoch records server-side — render the
re-render reason from `:sub-runs` correlations (every entry has
`:recomputed? true` post-cascade, i.e. `:fresh`), but the live
cache-state distinction (`:invalidated` vs `:cached-no-watcher`)
requires the sub-cache and therefore degrades to "show the
cascade-projected status only" on the JVM. The per-component
drilldown's render-timing block is similarly best-effort.

This is consistent with the framework split — JVM keeps the data
plane; the reactive cache is a CLJS-substrate concern.

## Production elision

Per [`Principles.md`](Principles.md) §Production elision is
non-negotiable, the Views tab — like every Causa surface — elides
entirely in production builds. The renderer, the cluster algorithm,
the heatmap, the per-component drilldown all ship zero bytes when
`goog.DEBUG=false`.

CI's `npm run test:elision` ([`007-UX-IA.md`](007-UX-IA.md)
§Production posture) verifies the contract.

## Performance

- **Three-group projection is O(renders in cascade).** Each cascade's
  `:renders` slot is bounded by drain depth × per-cascade component
  count.
- **Clustering is O(N log N)** (group-by-identity-key, then count).
- **Heatmap is O(distinct components in cascade).**
- **Per-row sub-status is O(visible rows).** Each row reads its own
  cache entry; the panel virtualises long lists; nothing renders >200
  rows at once.

If the panel's render budget approaches the INP target
([`007-UX-IA.md`](007-UX-IA.md) §Performance budget), the panel cuts
non-visible animation first; structural correctness (clustering,
isolation filter) is never elided at runtime.

## Empty states

- **No views rendered this cascade** (handler was effects-only):
  "No views rendered this cascade. (The handler made no `app-db`
  changes that any subscribed view depends on.)"
- **No subs registered** (in a sub-less app — rare): per-row "subs
  used (0)".
- **First cascade after page load with full mount tree:** "First
  cascade — initial mount. N components mounted." (mount group only)

## Embedding

The Views tab ships as
`day8.re-frame2-causa.panels.views/Panel`. Embeddability is **v1.1**;
v1.0 ships the panel as part of Causa's tab bar only.

## Cross-references

- [`018-Event-Spine.md`](018-Event-Spine.md) — Views tab placement
  (tab 3 of 6); spine binding; isolation invariants.
- [`007-UX-IA.md`](007-UX-IA.md) — detail panel renderer; long-keyword
  treatment.
- [`004-App-DB-Diff.md`](004-App-DB-Diff.md) — changed-paths
  derivation (the layer-1 attribution input for sub trigger
  detection).
- [Spec 006 §Subscription cache — contract and operational semantics](../../../spec/006-ReactiveSubstrate.md#subscription-cache--contract-and-operational-semantics) — the runtime contract this panel observes.
- [Spec-Schemas §`:rf/epoch-record`](../../../spec/Spec-Schemas.md#rfepoch-record) — the `:sub-runs` + `:renders` projections folded.
- [spec/015-Data-Classification](../../../spec/015-Data-Classification.md) — classification sentinels rendered in sub return values + props diff.
- [`014-Registry-Catalogue.md`](014-Registry-Catalogue.md) — the
  `:rf.causa/*` registry ids for the Views tab.
- [`017-Test-Coverage-Matrix.md`](017-Test-Coverage-Matrix.md) — Views
  three-group + clustering + heatmap test rows.
