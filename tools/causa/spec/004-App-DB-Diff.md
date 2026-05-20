# 004-App-DB-Diff

> **See also**: [`021-Dynamic-Panel-Designs.md` §4](021-Dynamic-Panel-Designs.md#4-the-app-db-panel-state-bridge) for the canonical content design including downstream-subs overlay.

## Bug class

**"What part of app-db actually changed when I dispatched this event?"**

Real app-dbs are big (1–50MB); the change from one event is small (a
handful of paths). The author needs to see the slices that changed in
THIS cascade — added, modified, removed — without scrolling through
the whole tree.

## Example bug

You dispatched `:cart/add-item {:id 22 :qty 1}`. The UI didn't
update. You don't know whether the cart slice changed at all, whether
it changed at an unexpected path, or whether something else changed
that you weren't expecting (a reset, a clobber).

## Insight Causa provides

A **slice-centric view** — the slices that changed in this epoch,
each shown with `before` and `after` values, colour-coded by op
(`:added` green, `:modified` yellow, `:removed` red). Plus
**clickable path segments** — every segment of every diff path opens
a popup inspector at that path-prefix, so the user can inspect
arbitrary sub-trees of `app-db` on demand.

This is the **single most-used Causa surface** after the Event tab.
The 400ms yellow → transparent diff-flash on touched slices is the
attention-cue that keeps the user oriented across cascades.

## Affordance

App-db tab — slice-centric mini-panels. Each section's breadcrumb
path renders as individually clickable segments; clicking any
segment opens an inspector popup at the path-prefix up to and
including that segment.

---

The app-db panel is **slice-centric**, not tree-centric. Real app-dbs
run 1–50MB. Rendering the whole tree on every dispatch competes for
canvas real estate, virtualisation only partly helps, and it isn't
what programmers want. Programmers want to see **the slices that
changed in this epoch**, plus a few slices they've pinned for
watching.

## Default view

A stack of focused slice mini-panels:

```
┌─ [:cart :items]   (modified) ─────────────────────┐
│   ↑     ↑                                         │
│   each segment is clickable; click opens the      │
│   segment-inspector popup at that path-prefix     │
│                                                   │
│  before:  [{:id 7 :qty 1}]                        │
│  after:   [{:id 7 :qty 1} {:id 22 :qty 1}]        │
└────────────────────────────────────────────────────┘

┌─ [:cart :totals :gross]   (added) ────────────────┐
│  added:   $48.00                                  │
└────────────────────────────────────────────────────┘
```

The panel never renders the whole tree by default. Slice mini-panels
are bounded by the size of the touched path. A 50MB `app-db` with
two touched slices renders the same as a 100KB one. Clicking the
root segment of any breadcrumb opens the popup at `[]` — that's the
escape hatch for 'show me app-db in its entirety'.

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

## Path-origin tags (rf2-s8r6c)

Each diff slice header carries a **path-origin tag** identifying
which step of the focused cascade wrote that path. The tag answers
*"who wrote this?"* — critical when both the event handler's `:db`
return and a downstream flow's `:output` touch overlapping paths in
the same cascade.

| Tag | Source | Visual |
|---|---|---|
| `[fx :db]`        | The event handler's `:db` effect return.                                          | Green chip on the slice header. |
| `[flow :flow-id]` | A flow's `:output` wrote this path during the cascade (see [`spec/013-Flows.md`](../../../spec/013-Flows.md)). | Violet chip on the slice header. |
| `[mixed]`         | Multiple sources touched this path in this cascade (handler + flow, or multiple flows). | Yellow chip; hovering expands to the per-source breakdown. |

Tags are derived from the trace bus by partitioning the changed-path
set per writer and union-tagging overlaps. A slice header reads like:

```
┌─ [:cart :totals :gross]   (modified)   [flow :cart/totals-flow] ─┐
```

When the focused cascade touches a path from a single source the tag
is the green or violet chip; when both the handler and a flow touch
the same path the chip turns yellow `[mixed]` and the hover surfaces
both writers in cascade order.

The implementation reads `:writer` markers carried on each trace-bus
entry (Spec 009 §Writer attribution) — Causa does NOT re-derive
writer identity from the diff; the runtime tags every writer at
emission and Causa renders the tag.

## Clickable path segments (rf2-e9tb0)

For each diff path like `[:cart :items 0 :price]`, each segment is
independently clickable:

- Click `:cart` → popup shows app-db at `[:cart]`
- Click `:items` → popup shows app-db at `[:cart :items]`
- Click `0` → popup shows app-db at `[:cart :items 0]`
- Click `:price` → popup shows app-db at `[:cart :items 0 :price]`
  (the leaf)

The popup renders the value at the inspected path via Causa's
existing data-inspector primitive — the same cljs-devtools-shaped
expandable tree every L4 detail panel uses.

Discoverability: segments carry a dotted underline + pointer cursor
on hover, and a `Inspect app-db at <prefix>` tooltip on hover. The
underlying path colour stays accent-violet so the inline path still
scans as a single phrase when the user isn't pointing at it.

Three close affordances:

  1. `Esc` while the popup is focused.
  2. Click outside (backdrop) — the backdrop swallows clicks and
     dispatches close; the dialog stops propagation so click-throughs
     on its body don't close.
  3. `✕` button in the header.

The popup is a transient overlay (modal-light, not a full-window
modal). The escape-hatch use case ('let me see app-db in its
entirety') is served by clicking the leftmost segment of any
breadcrumb — that path-prefix is `[<first-seg>]`, so to inspect the
whole root the user clicks the root segment of the synthesised
`(root)` breadcrumb on a `:children` section rooted at `[]`. The
popup body then renders the entire `app-db` as an expandable tree.

## What this replaces (rf2-e9tb0)

The pinned-watches strip was DROPPED when clickable path segments
landed (Mike 2026-05-19 Q13). The diff already identifies changes
surgically; the pin-this-up-front flow was redundant when any prefix
of any diff path can be inspected with one click on its breadcrumb
segment. The `:rf.causa/pin-slice` / `:rf.causa/unpin-slice` /
`:rf.causa/reorder-pinned-slices` events and the corresponding
`:pinned-slices-store` slot are no longer registered.

## Reserved-keys group

The runtime owns a fixed set of top-level `app-db` keys, catalogued
in [Conventions §Reserved app-db keys](../../../spec/Conventions.md#reserved-app-db-keys).
Causa's `[runtime]` group segregates these six:

| Reserved key | Owner | One-line role |
|---|---|---|
| `:rf/machines` | machine runtime | Per-frame map of `<machine-id> → :rf/machine-snapshot` — every active machine's snapshot. |
| `:rf/system-ids` | machine runtime | Reverse index `<system-id> → <gensym'd-machine-id>` for `:system-id` named-machine addressing. |
| `:rf/spawned` | machine runtime | Declarative-`:spawn` / `:spawn-all` spawn registry — `<parent-id> → {<invoke-id> <slot>}` for the destroy-cascade walker. |
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
the programmer recognises them as runtime-owned and routes to the
equivalent dedicated tab — e.g., clicking the `:rf/machines` row jumps
to the Machines tab; clicking the `:rf/route` row jumps to the Routing
tab. Segment-inspector clicks on a reserved-key path-prefix still
work (the popup renders the value), but the panel surfaces a soft
cue suggesting the equivalent tab.

## Full-tree escape hatch

Per rf2-e9tb0 the explicit `Show full app-db tree ▸` row was dropped
in favour of segment-inspector reuse — clicking the root segment of
any breadcrumb opens the popup at the inspected path; chaining up
from any diff section to the root is a one-click affordance.

The full tree is rarely needed; the slice-centric view answers most
questions.

## Redacted-paths-modified hint chip

Per [Spec 015 §Data Classification](../../../spec/015-Data-Classification.md)
and [Security §Epoch privacy posture](../../../spec/Security.md#epoch-privacy-posture--raw-in-process-records-vs-projected-egress),
an app-supplied epoch `:redact-fn` may substitute the `:rf/redacted`
sentinel into `:db-before` / `:db-after` to keep sensitive material out
of recorded records. When the underlying value at a redacted path
actually changed across a cascade, the structural diff correctly sees
`:rf/redacted` = `:rf/redacted` and emits no row — the elision
contract is preserved (per [`diff/annotated_tree.cljc` §Sentinel
handling](#)). The developer is left with an empty diff and no signal
that anything happened in the redacted slot.

Causa surfaces a **separate-from-diff** signal: a muted-grey chip
at the top of the diff body when count > 0.

```
[· 3 redacted paths modified]
```

The chip uses the muted-`·` marker from the rf2-87lkf Views polish
family (`·` = muted/informational; `✱` = amber/attention-cue). Hover
to read the contract explanation; the chip is absent (no DOM) when
count is 0.

**Count semantics — preferred path (rf2-dl3gx).** The framework
threads an exact `:rf.epoch/redacted-modified-paths-count` integer on
the epoch record (per
[Spec-Schemas §`:rf/epoch-record`](../../../spec/Spec-Schemas.md#rfepoch-record)).
Computed inside `re-frame.epoch.assembly/build-record` from raw
db-before / db-after values BEFORE the `:redact-fn` runs — parallel to
the `:rf.epoch/sensitive?` rollup. A path `P` counts in the framework's
figure when:

1. `P` is schema-declared sensitive (`[:rf/elision :sensitive-declarations]`,
   populated from `{:sensitive? true}` per-slot schema props per
   [Spec 015](../../../spec/015-Data-Classification.md)).
2. `(not= (get-in db-before P) (get-in db-after P))` — value-equality
   on the raw (pre-redact-fn) dbs.

This is the **exact** count of declared-sensitive paths that mutated
this cascade. Causa reads it directly from the record; no walk, no
heuristic.

**Heuristic fallback (rf2-bz1cl).** Records that lack the egress slot
(legacy snapshots, hand-rolled test fixtures, hosts with no schema
layer that produces a sensitive-declarations registry) fall back to a
Causa-side heuristic — paths `P` where:

1. `(= :rf/redacted (get-in db-before P))`, AND
2. `(= :rf/redacted (get-in db-after  P))`, AND
3. `P`'s parent subtree is NOT `identical?` across `db-before` /
   `db-after` (something in the enclosing subtree changed).

Distinct paths are counted independently. The reserved `:rf/elision`
subtree at the root is skipped (the elision registry's own values may
include `:rf/redacted` as documentation/sentinel form). Condition (3)
is the structural-sharing tightener — without it every redacted slot
in `app-db` would count for every cascade. The fallback is a tight
upper bound; it may over-state if a sibling slot changed and the
redacted slot was incidentally untouched. The exact framework count
above is preferred whenever it is present.

## Read-only

The app-db panel is **read-only forever** (lock #3 in
[`DESIGN-RATIONALE.md`](./DESIGN-RATIONALE.md)). No in-place edit
boxes, no "set value" affordances. The runtime is the source of
truth; pokes from the debugger are out of scope.

The user can:

- Click a breadcrumb segment → opens the segment-inspector popup
  (rf2-e9tb0)
- Click the Copy value / Copy path / Show me when this changed
  affordance buttons in the section header

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

This is the affordance that turns "I notice this is wrong" into "show
me when it became wrong" in two clicks. From a deep cascade, it's
faster than re-reading the source.

## Performance

- **Diff caching** per `:epoch-id`. A second render of the same epoch
  is O(1).
- **Slice virtualisation** for slices whose `after` value is large
  (e.g., a 10k-entry vector). The slice mini-panel renders the head
  and tail, with a `… 9970 entries …` ellipsis; click expands.
- **Segment-inspector popup** resolves the inspected value via
  `get-in` (O(path-depth)) against the live target-frame db; no
  separate watch graph. Per-node expand-state is shared with the
  data-inspector slot in `:rf/causa` app-db so a value the user
  drilled into in the inspector stays drilled when reopened.

## Empty state

Before any dispatches:

```
   app-db is at the boot value.
   No diffs yet — every dispatch will land here with the slices
   it touched.
```

## Vision

### Branch-aware diff (Story integration)

**Bug class:** "I'm running a Story variant that sim-clones app-db;
which slices changed because of my dispatch and which were already
different on the branch?"

When Causa is embedded inside Story
([`008-Embedding-Contract.md`](008-Embedding-Contract.md)) and the
variant is a sim-clone (Story branches `app-db` so each variant runs in
isolation without polluting the host), the diff has TWO axes:

- **Branch baseline diff** — what's different between the variant's
  app-db and the host's app-db, irrespective of any dispatch.
- **Cascade diff** — what changed because of THIS dispatch.

Causa renders both in separate sections; cascade-diff is the headline,
branch-baseline-diff is a collapsed-by-default "What's different on this
branch" group.

### Cross-frame diff

**Bug class:** "Multiple frames share substate via shared sub keys;
where does an event in frame A change values that frame B reads?"

When multi-frame apps share substate (e.g. an auth slice mirrored across
two frames), Causa renders the diff per-frame and shows where a write
in one frame propagates to another.

### Pin two epochs side-by-side

**Bug class:** "I want to diff arbitrary epoch A vs epoch B, not just
before/after of a single event."

Pin two epochs via `*`; press `=` → opens a split view in the App-db
tab showing slice-by-slice diff between the two pinned epochs. Closes
a long-standing gap in both 10x and Causa (workflow-gap-4 from the
findings). Needs Editscript A* for compact diffs over arbitrary epoch
pairs.
