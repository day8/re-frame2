# 004-App-DB-Diff

The app-db panel is **slice-centric**, not tree-centric. Real app-dbs
run 1–50MB. Rendering the whole tree on every dispatch competes for
canvas real estate, virtualisation only partly helps, and it isn't
what programmers want. Programmers want to see **the slices that
changed in this epoch**, plus a few slices they've pinned for
watching.

## Default view

A stack of focused slice mini-panels:

```
┌─ Slice 1: [:cart :items]  (modified) ─────────────┐
│  before:  [{:id 7 :qty 1}]                        │
│  after:   [{:id 7 :qty 1} {:id 22 :qty 1}]        │
└────────────────────────────────────────────────────┘

┌─ Slice 2: [:cart :totals :gross]  (added) ────────┐
│  added:   $48.00                                  │
└────────────────────────────────────────────────────┘

┌─ Pinned slices ───────────────────────────────────┐
│  [:user :auth :status]           :authenticated   │
│  [:nav :route]                   :app/cart        │
└────────────────────────────────────────────────────┘

[Show full app-db tree ▸]
```

The panel never renders the whole tree by default. Slice mini-panels
are bounded by the size of the touched path. A 50MB `app-db` with
two touched slices renders the same as a 100KB one.

## Changed-paths derivation

Causa reads `:rf/epoch-record`'s `:db-before` and `:db-after` and
derives a changed-paths set via structural-sharing diff:

- **PersistentHashMap pointer-equality** at each level — if the
  pointer is identical, the subtree is unchanged; skip.
- **Recurse only where pointers differ.** This is O(changed paths),
  not O(db size).
- **Emit a sorted vector of `[op path before-value after-value]`
  triples** where `op` is one of `:added`, `:modified`, `:removed`.

The framework's `:rf/epoch-record` does **not** pre-compute changed
paths (the runtime stays cheap); Causa runs the diff on the panel's
first mount per epoch, caches per `:epoch-id`, and discards on epoch
age-out.

## Colour coding

| Op | Visual |
|---|---|
| `:added` | Green left-border; key tagged `(added)`. |
| `:modified` | Yellow left-border; `before` / `after` side-by-side. |
| `:removed` | Red left-border; key tagged `(removed)`; value rendered struck-through. |

The diff flash on epoch land is a 400ms tween (yellow → transparent)
on each newly-touched slice. Respects `prefers-reduced-motion` — the
tween becomes a static yellow border for 600ms.

## Pinned slices

Right-click any value in the runtime → **Pin this slice**. The path
persists to localStorage and renders in the Pinned-slices list across
sessions.

- **Each pin shows the path and current value** (live-updating on
  every epoch).
- **Right-click a pin** → Unpin · Edit path · Open source (jumps to the
  most-recent `reg-event-*` registration that touched this path).
- **Pin order** is user-controlled (drag to reorder); persisted.
- **Pins are per-frame.** Switching frames switches the pin set.

## Reserved-keys group

The runtime owns a fixed set of top-level `app-db` keys, catalogued
in [Conventions §Reserved app-db keys](../../../spec/Conventions.md#reserved-app-db-keys).
Causa's `[runtime]` group segregates these six:

| Reserved key | Owner | One-line role |
|---|---|---|
| `:rf/machines` | machine runtime | Per-frame map of `<machine-id> → :rf/machine-snapshot` — every active machine's snapshot. |
| `:rf/system-ids` | machine runtime | Reverse index `<system-id> → <gensym'd-machine-id>` for `:system-id` named-machine addressing. |
| `:rf/spawned` | machine runtime | Declarative-`:invoke` / `:invoke-all` spawn registry — `<parent-id> → {<invoke-id> <slot>}` for the destroy-cascade walker. |
| `:rf/route` | routing runtime | The current route slice `{:id :params :query :transition :error}`. |
| `:rf/pending-navigation` | routing runtime | Pending-navigation slot populated when a `:can-leave` guard rejects; cleared by `:rf.route/continue` / `:rf.route/cancel`. |
| `:rf/elision` | elision runtime | Wire-elision declaration registry — `{:declarations {<path> {:large? :hint :source}} :sensitive-declarations {<path> {:sensitive? :hint :source}}}`. Populated at boot from `:large? true` / `:sensitive? true` schema slots; consulted by `rf/elide-wire-value` at every wire-boundary emit. Schemas are the only nomination path. |

Conventions is the canonical home; this table is the panel-facing
projection. The six above are the keys Causa's `partition-reserved`
treats as runtime-owned in the diff panel — diff triples rooted in
one of them render here rather than as slice mini-panels. If a new
reserved key lands in Conventions, the `[runtime]` group's coverage
and this table are updated in lockstep; the drift-detector test in
`app_db_diff_helpers_cljs_test.cljc` enforces this on every run.

```
┌─ [runtime] ───────────────────────────────────────┐
│  :rf/machines            (3 active)               │
│  :rf/route               :app/cart                │
│  :rf/system-ids          (1 bound)                │
└────────────────────────────────────────────────────┘
```

These are informational; the panel surfaces them clearly marked so
the programmer recognises them as runtime-owned and doesn't reach for
"pin this." (A pin attempt on a reserved key shows a warning toast
suggesting the equivalent panel — e.g., "Use the Machine inspector
for `:rf/machines`.")

## Full-tree escape hatch

The `Show full app-db tree ▸` row at the bottom expands to fill the
canvas:

- Virtualised lazy-expand collapsible tree.
- Only first 100 keys at each level eager-render; deeper levels load
  on click.
- Search input at the top filters by path or value substring.
- Persistent breadcrumb at the top of the tree shows the currently
  focused path.
- Path-click → copies the path to clipboard (for pasting into a sub
  or for a pin).

The full tree is rarely needed; the slice-centric view answers most
questions.

## Read-only

The app-db panel is **read-only forever** (lock #3 in
[`DESIGN-RATIONALE.md`](./DESIGN-RATIONALE.md)). No in-place edit
boxes, no "set value" affordances. The runtime is the source of
truth; pokes from the debugger are out of scope.

The user can:

- Right-click → Copy value
- Right-click → Copy path
- Right-click → Pin this slice
- Right-click → Show me when this changed *(pivots the canvas to a
  list of epochs that touched the path; see below)*

Not present:

- "Edit value"
- "Set to..."
- "Inject"
- Any text-input that mutates the runtime

If the user wants to mutate `app-db`, they do so via `(rf/dispatch
...)` from the REPL, or via the Re-dispatch affordance from the event
log. Causa's writes are funnelled through dispatch (per
[`002-Time-Travel.md`](./002-Time-Travel.md) §The read-only constraint).

## "Show me when this changed"

The high-leverage right-click affordance. When invoked on any path:

1. Causa walks `epoch-history`, diffs each epoch's `:db-before` and
   `:db-after`, finds epochs where the path was touched.
2. The canvas pivots to a list:

   ```
   Epochs that touched [:cart :items]:
   ▸ epoch 14   :cart/add-item       added {:id 22 :qty 1}
   ▸ epoch 11   :cart/clear          removed [{:id 7 :qty 1}]
   ▸ epoch 8    :cart/add-item       added {:id 7 :qty 1}
   ▸ epoch 3    :app/boot            set to []
   ```

3. Clicking an entry → event-detail rebases to that epoch.
4. Pressing `c` on a focused entry → causality graph filters to that
   cascade.

This is the affordance that turns "I notice this is wrong" into "show
me when it became wrong" in two clicks. From a deep cascade, it's
faster than re-reading the source.

## Performance

- **Diff caching** per `:epoch-id`. A second render of the same epoch
  is O(1).
- **Slice virtualisation** for slices whose `after` value is large
  (e.g., a 10k-entry vector). The slice mini-panel renders the head
  and tail, with a `… 9970 entries …` ellipsis; click expands.
- **Pinned-slice deref** is bounded to one path per pin per epoch;
  pinned slices add O(pins) per epoch, not O(db-size).
- **Full-tree** virtualises rows past the viewport; never DOM-mounts
  more than ~80 rows.

## Empty state

Before any dispatches:

```
   app-db is at the boot value.
   No diffs yet — every dispatch will land here with the slices
   it touched.
```

The pinned-slices area is empty until the user pins something.
