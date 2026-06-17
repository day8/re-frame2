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

## Insight Xray provides

A **slice-centric view** — the slices that changed in this epoch,
each shown with `before` and `after` values, colour-coded by op
(`:added` green, `:modified` yellow, `:removed` red). Plus
**clickable path segments** — every segment of every diff path opens
a popup inspector at that path-prefix, so the user can inspect
arbitrary sub-trees of `app-db` on demand.

This is the **single most-used Xray surface** after the Epoch panel.
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

Xray reads `:rf/epoch-record`'s `:db-before` and `:db-after` and
derives a changed-paths set via the canonical Editscript-A* engine
(`day8.re-frame2-xray.diff.engine/project`):

- **Editscript A* edit-script** produces the minimal compact set of
  `:+` / `:-` / `:r` ops across the two values
  (`juji/editscript` 0.6.5).
- **Per-path projection** classifies each leaf as `:added` /
  `:modified` / `:removed` / `:same-shifted` and exposes the result
  as a `:flat-rows` channel of maps `{:path :op :before :after}`.
- **Universal 4-tuple shape** at the call site — every `:diff`-lens
  consumer (App-DB panel, Machine Inspector snapshot, Epoch HANDLER
  `:db`) converts each row to `[path before after op]`, the shape the
  view layer's row renderer destructures.

The framework's `:rf/epoch-record` does **not** pre-compute changed
paths (the runtime stays cheap); Xray runs the diff on the panel's
first mount per epoch, caches per `[frame-id epoch-id]`, and discards
on epoch age-out.

### Diff semantics: per-epoch delta (rf2-02j4r)

The diff is always the **selected epoch N's own delta** —
`db-before(N) → db-after(N)`, the change introduced by THIS event
compared to the immediately previous event. Both sides come from the
**same focused epoch record**: `:db-before` is the pre-image and
`:db-after` is the post-image (the App-DB panel surfaces `:db-after` as
its `:value`). The delta is therefore **independent of any later
event** — scrubbing back to an earlier epoch shows only what that epoch
changed, never the cumulative change since.

> **rf2-02j4r (2026-06-04)** reversed the earlier rf2-yng0y App-DB-panel
> design, which diffed the **live** target-frame db against the focused
> epoch's `:db-before`. That equals the per-epoch delta only when the
> focused epoch is head; at any non-head epoch it became a cumulative
> live-vs-`db-before(N)` diff, so later events' changes bled onto earlier
> selections. The panel now pulls `:value` from the focused record's
> `:db-after` (see [`021` §4.5](021-Dynamic-Panel-Designs.md#45-per-epoch-delta-not-cumulative-rf2-02j4r)).
> The Editscript engine call here is unchanged — it always diffed the
> record's `:db-before`/`:db-after` pair; the reversal was in which value
> the *panel* presents as the current-state side.

> **rf2-nfgps — frame-scoped cache key.** The per-epoch caches key on
> the COMPOUND `[frame-id epoch-id]`, never `:epoch-id` alone. The
> framework's epoch contract guarantees `:epoch-id` is unique only
> WITHIN a frame's history (the global-counter scheme that makes ids
> incidentally process-unique today is an implementation detail, not a
> spec promise). In a multi-frame app two frames can carry the same
> `:epoch-id`; an id-only key would let one frame read another frame's
> cached diff, and a frame-switch prune would evict a sibling frame's
> live entries. Compounding `frame-id` keeps each frame's cache
> isolated (no read-bleed, no cross-frame eviction), and the prune ages
> out only the observed frame's stale keys — upholding the
> frame-isolation invariant (frames are isolated contexts).

> **rf2-xuyac migration** (2026-05-27): the App-DB panel + Epoch
> HANDLER `:db` `:diff` lenses previously routed through the
> home-grown `app-db-diff-helpers/diff-paths` walker (a
> structural-sharing key-walker, not Editscript). Engines disagreed
> on R6 vector-shift, R7 type-change, and R8 redaction across the
> `:diff` and `:full+diff` modes of the same data. Path A (Mike's
> 2026-05-27 decision): migrate the two lenses onto the canonical
> Editscript engine, mirroring Machine Inspector's already-canonical
> pattern (`snapshot-flat-diff-rows`). The home-grown walker is
> retained ONLY for the trace panel's `db-changed-diff-triples`
> surface (out of scope; tracked separately).

### Empty-collection leaves are honest leaves (rf2-bufw2)

An **empty collection** (`[]`, `{}`, `#{}`, `'()`) is a *terminal
leaf*, not a container to recurse into — it has no descendant slots.
The projection classifies an empty-collection slot exactly as it
classifies a scalar:

- Inside a wholly-**added** subtree (absent → present), an empty-
  collection leaf classifies `:added` — the same green chrome as every
  other leaf under the new subtree. The absent↔empty-collection
  transition is a real change the operator must see.
- Symmetrically, inside a wholly-**removed** subtree, an empty-
  collection leaf classifies `:removed` (red).
- An empty-collection leaf that is **identical on both sides** stays
  `:same`. Its presence never falsely promotes an otherwise-mixed
  container to a wholly-changed root.

The rule is **kind-agnostic** — `(container? v)` + `(empty? v)` covers
all four collection kinds, not just the vec + map first witnessed in a
testbed epoch-2 `:rf/runtime` allocation (`:messages []` +
`:rf/spawn-counter {}`).

> **Why this is a contract, not an edge case.** Editscript's A* treats
> empty-collection-vs-absence as a no-diff (it emits no edit-script
> entry), so the projection's leaf-expansion would drop the slot and
> `op-at` would fall through to `:same` — the *only* path inside an
> otherwise wholly-green added subtree that would lie to the operator.
> Both the leaf-expansion walker and the wholly-changed uniformity
> walker treat the empty container as a leaf so the two agree on the
> classification (rf2-bufw2; this is the third operator-honesty fix in
> the FULL+DIFF family alongside rf2-9d4j8 root-container and
> rf2-fyd8u scalar-sub-cache cases).

### A changed set diffs member-by-member, key intact (rf2-l0us2)

A **set** whose membership changes is a *member-level* diff — the key
stays intact and each member carries its own `:added` / `:removed`
chrome (`#{:door/locked}` → `#{:door/closed}` reads as `-:door/locked
+:door/closed`, not a struck-through whole `:tags` entry). Members match
**by value** — sets are unordered, so there is no positional or key
identity, only membership: a member present only in `before` is
`:removed`, present only in `after` is `:added`, present in both is
`:same`.

Two engine rules make this hold:

- **The wholly-changed uniformity walk takes the UNION of both sides'
  set members.** Editscript keys set members by *value*, so a swap puts
  each side's members at **disjoint** paths (`#{:a} → #{:b}` ⇒ `[[:a] :-]
  [[:b] :+]`). A one-sided uniformity walk would then see the before-set
  as "all members removed" and the after-set as "all members added" and
  *both* falsely promote the set — and every ancestor map/vector of it —
  to a wholly-changed root, which the renderer paints as a whole-key
  removal (the "sea of red"). Collecting the union of members means a
  swapped set contributes both a `:removed` and an `:added` leaf, so the
  uniformity test correctly fails **at the set and at every ancestor**.
- **A whole-set `:r` replace expands into the membership delta — for ANY
  member count (rf2-4vp8c).** Editscript emits per-member `:+` / `:-`
  edits for a *single*-member swap, but crosses its A* cost threshold and
  falls back to a whole-value `:r` once *multiple* members change at once
  (`#{:a :b :c} → #{:a :d :e}` ⇒ `[[] :r #{:a :d :e}]`) — and for the
  empty↔populated edge (`#{} → #{:a}`, the same pathology as the empty
  map, rf2-9d4j8). The projection catches every set `:r` and pre-expands
  it into the membership delta (members only-in-before ⇒ `:-`,
  only-in-after ⇒ `:+`, in-both ⇒ unchanged) so a multi-member swap reads
  `-:b -:c +:d +:e` with `:a` intact rather than as a single opaque
  `:modified` ("sea of red"). The empty↔populated case is the degenerate
  one where the in-both intersection is empty. Without this, l0us2's
  single-member fix left the *common* real-world transition — a machine
  `:tags` set dropping and adding several tags at once — still
  misrendering as a whole-set replacement.

A set is therefore only a **wholly-changed root** when the opposite side
is empty or absent (a genuine cold-boot `#{} → #{…}` or clear `#{…} →
#{}`, where there is no surviving member to anchor a member-level diff).
While the opposite side still holds members, the membership delta *is*
the diff and the per-member chrome shows with the key intact. Maps and
vectors are unaffected — their slots are keyed by a shared key/index, so
the one-sided uniformity walk was always correct for them; the union is
taken only for sets.

> **Why this is a contract, not an edge case.** The misrender hit ANY
> set whose membership changed — `:tags` on a machine snapshot, a
> set-valued app-db key, a sub result — reading as "the key vanished"
> rather than "one member swapped." Surfaced live on the machine-epochs
> deck (`[:rf/runtime :machines :snapshots :door/main :tags]`, rf2-l0us2,
> related rf2-iwy0c). The deck's canonical HARD machine (`:hvac/controller`,
> rf2-k08ay) drives the richer case: a `:hvac/mode-toggle` swaps
> `:climate/heating` for `:climate/cooling` in the `:tags` set, and the
> rendering-fidelity test (`panels.epoch.hard-machine-fidelity-cljs-test`)
> pins that exactly one member joined + one left at member level (no
> wholly-replaced blob). This is the FULL+DIFF family's set-keyed counterpart
> to the rf2-bufw2 empty-collection and rf2-9d4j8 root-container honesty
> fixes: all three close gaps where the wholly-changed uniformity walk
> disagreed with the per-leaf op classification.

### Vectors and lists diff member-level at the empty edge (rf2-yucxn)

A **vector or list** that goes empty with its **key intact** (`{:a [1]}
→ {:a []}`, `{:a '(1)} → {:a '()}`) is an **element removal**, not a
wholesale value mutation — and going **populated from empty** (`{:a []}
→ {:a [1]}`) is an **element addition**. Editscript emits a whole-value
`:r` for the sequential empty edge (`[1] → []` ⇒ `[[] :r []]`), exactly
as it does for the empty map (rf2-9d4j8) and empty set (rf2-l0us2). Left
alone that `:r` classifies as a single `:modified` at the sequential's
path — a whole-key `~` modify — which reads **inconsistently** with the
set/map empty edges (which expand member-level with the key intact).

The projection **pre-expands the sequential empty-edge `:r`** into
per-index `:-` (going empty) / `:+` (filling from empty), bringing
vectors and lists to member-level parity with sets and maps. The
expansion is scoped to the empty edge **and to same-family
sequentials** — both sides must be sequentials (neither set nor map), so
a vector↔map (or vector↔set) flip at the empty edge stays an R7
`:modified` type-change rather than a spurious member delta. A
populated↔populated vector swap never collapses to a whole-value `:r`
(Editscript emits per-index edits), so there is no `:r` to intercept
there.

A vector/list `:-` removal flows through the off-path
`:vector-removals` channel (a removed before-index has no stable
after-side path — the survivors shift up). The removals channel **and**
the `:same-shifted` shift channel both derive from **one unified replay
of the `:+` and `:-` edits in edit-script order** against a single
evolving slot vector (`replay-vector-edits`, rf2-3eplfk), because
Editscript applies `:+`/`:-` edits *sequentially* against an evolving
sequence: each `:-` at edit-index `i` removes the element *currently* at
index `i` *after* every prior `:+` insert **and** `:-` delete at this
parent has shifted the sequence. A `:-`'s edit-index is therefore a
position relative to the sequence as it stands at that edit — **not** a
pristine before-index.

The slot vector starts as `(range before-len)`. Each `:+` splices an
insert-marker at its (current) index; each `:-` records and removes
whatever slot currently sits at its index. The final slots align 1:1
with the after-vector (a survivor's original before-index or an
insert-marker); the recorded removals carry the true before-index for
every dropped element. `:r` (replace) edits stay *out* of the replay —
a replace is length- and order-preserving, so it never shifts a
subsequent index or adds/removes a slot — and their after-indices are
skipped from the shift output (they classify as `:modified`).

> **Why the unified `:+`/`:-` replay (rf2-3eplfk).** An earlier
> implementation replayed **only** the `:-` edits against pristine
> `(range before-len)`, ignoring interleaved `:+` inserts. That is
> correct for delete-only (no inserts shift the indices) and insert-only
> (no deletes) scripts — which is why those cases passed — but **wrong**
> for a *mixed* insert+delete script: the `:-` index had already been
> shifted by a prior `:+`, so deleting against pristine indices read the
> wrong slot. Symptoms: mis-attributed removal (`[:a :b :c] → [:X :a :c]`
> ⇒ `[[0] :+ :X] [[2] :-]` reported `:c` removed when `:b` was, and
> marked the surviving `:c` `:same-shifted`); a surviving element struck
> (`[:a :b :c :d] → [:X :a :d]` struck the surviving `:d`); a **dropped**
> removal (an out-of-range `:-` whose true index sat past `before-len`
> because a prior `:+` grew the sequence was silently skipped —
> `[:a :b :c :d] → [:a :X :b :c]` ⇒ `[[1] :+ :X] [[4] :-]` lost `:d`'s
> removal and emitted a phantom shift). Same wrong-before/after class as
> the scar history rf2-1njv97 / rf2-yucxn / rf2-vu42n, with the
> mixed-edit case uncovered. The unified walk removes the actual slot at
> each `:-`'s post-shift index and derives the `(was N)` shift suffix
> from the *same* slots, so the two channels can never disagree.

The `:same-shifted` `(was N)` before-index of a surviving element is the
original index left in that slot once the unified replay finishes — *not*
a deletes-then-splice-inserts reconstruction and *not* an arithmetic
`after-index − inserts + deletes` count. (Treating the post-shift `:-`
edit-indices as before-indices over-counted deletes for scattered /
multi-element removals — `[:a :b :c :d] → [:a :c]` reported the surviving
`:c` as `(was 3)` instead of `(was 2)` — rf2-1njv97.)

> **Renderer note (rf2-vu42n, fixed).** The inline vector / list / seq
> body renderer **consumes** this `:vector-removals` channel (plus the
> `:same-shifted` shift projection) via `sequential-diff-children`,
> rather than index-aligning the raw before/after vectors. The walk
> reconstructs the body in before-order: each surviving element renders
> at its *after* index (so the projection resolves its `:same` /
> `:same-shifted` / `:modified` op) carrying its prior value on the
> `before` slot; each genuinely-removed element is spliced back in at its
> true before-index, struck-through, with an `::missing` after-value;
> purely-added elements append after the before-ordered run. Maps / sets
> / records keep the `children-of-pair` union walk — their slots are
> key/member-addressed, so there is no positional shift to recover. The
> pre-fix index-alignment struck a surviving-*shifted* element (the one
> that slid up into a vacated slot) and dropped the actually-removed one
> for scattered / mid-vector removals; contiguous *tail* removals lined
> up under index alignment, so only mid / scattered removals mis-rendered.

### Removed slots render in place; the absence marker never escapes (rf2-8pfkk)

The diff renders the **union of `before ∪ after`** — a slot present in
`before` but absent from `after` (a `dissoc`, a `disj`, a popped vector
tail) must still be visible, rendered **in place** struck-through with
the `:removed` chrome (the universal diff idiom). The union walker
threads an internal **`::missing` sentinel** for the slot that does not
exist on one side; the renderer routes that sentinel through the
`:added` / `:removed` paths.

Two honesty rules make this robust regardless of how the engine
anchored the edit:

- **The structural sentinel is authoritative.** A slot whose `value`
  side is `::missing` is a removal, full stop; a slot whose `before`
  side is `::missing` is an addition. This overrides the projection's
  per-path op. The override matters because removing the *only* key of
  a nested map (`(update db :shapes dissoc :added)`, leaving `:shapes
  {}`) is anchored by Editscript on the **surviving parent** — `op-at
  [:shapes]` reports `:removed`/`:children` while the removed child slot
  `[:shapes :added]` carries the ghost subtree in `:container-ops` and
  reports `:children`. Trusting that child op leaked the internal
  `::missing` keyword (`:day8…edn-inspector/missing`) literally into the
  row (`:added ::missing`). The internal sentinel **must never appear in
  rendered output**.

- **A removed *container* renders as a collapsed struck-through ghost.**
  A deleted subtree shows as a single struck-through node (`:shapes {…}
  (N keys)`, red), expandable on demand to walk the ghost — bounding
  verbosity and reusing the ordinary collapse / elision machinery rather
  than `pr-str`-ing the whole deleted tree. Every descendant inside the
  ghost **inherits `:removed`** via a nearest-removed-ancestor walk-down
  (the symmetric of rf2-bufw2's `:added` inheritance) — never an
  `:added` (green) or `:same` row. Maps slot removed keys by sort order;
  vectors / lists / sets surface a removed index/member via the union
  walk (a removed index shifts the survivors, so the dropped element is
  marked by value/index, not by the now-occupied slot).

> **Why the renderer cannot defer to the projection here.** The engine
> anchors structurally-equivalent deletions in different channels — a
> dissoc-to-`{}` lands on the surviving parent, a vector-tail deletion
> lands in the off-path `:vector-removals` channel (no stable after-side
> path), so `op-at` reports `:same` for the parent. The renderer
> promotes any container whose `before` and `after` sides genuinely
> differ but whose projection op reads `:same` to `:children` so the
> union walk surfaces the struck-through removed slots. (rf2-8pfkk; the
> fourth operator-honesty fix in the FULL+DIFF family.)

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
entry (Spec 009 §Writer attribution) — Xray does NOT re-derive
writer identity from the diff; the runtime tags every writer at
emission and Xray renders the tag.

## Clickable path segments (rf2-e9tb0)

For each diff path like `[:cart :items 0 :price]`, each segment is
independently clickable:

- Click `:cart` → popup shows the value at `[:cart]`
- Click `:items` → popup shows the value at `[:cart :items]`
- Click `0` → popup shows the value at `[:cart :items 0]`
- Click `:price` → popup shows the value at `[:cart :items 0 :price]`
  (the leaf)

The popup renders the value at the inspected path via Xray's
existing data-inspector primitive — the same cljs-devtools-shaped
expandable tree every L4 detail panel uses.

### Popup reads the FOCUSED epoch, consistent with the panel body (rf2-jmucu)

The value the popup renders is resolved against the **focused epoch's
`:db-after`** — the *same image the panel body shows* (the
`:rf.xray/app-db-current+diff` `:value`, per §Diff semantics above),
**not** the live `target-frame-db`. The segment-inspector pops out of
the panel body, so the two must agree at every scrub position:

- **On-head** the focused epoch's `:db-after` *is* the live db, so the
  popup shows current state — unchanged from the user's expectation.
- **Off-head** (scrubbed back to an earlier epoch N) the popup shows
  the value at the inspected path **in epoch N's own post-state**, the
  same value the body is showing — with no later-event bleed.
- **Cold boot / no focus** (no cascades yet) the `:value` falls back to
  the live db, so the popup renders plain current-state — same as the
  body's empty-state.

Reading the popup against the *live* db instead (the pre-rf2-jmucu
wiring) made the popup disagree with the body off-head: it surfaced
everything later events did, the exact later-event-bleed class
[rf2-02j4r](#diff-semantics-per-epoch-delta-rf2-02j4r) killed in the
body. Routing the popup through `:rf.xray/app-db-current+diff`'s
`:value` (rather than `:rf.xray/target-frame-db`) is the single seam
that keeps the two surfaces identical.

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
segment. The `:rf.xray/pin-slice` / `:rf.xray/unpin-slice` /
`:rf.xray/reorder-pinned-slices` events and the corresponding
`:pinned-slices-store` slot are no longer registered.

## Reserved-keys group

EP-0001 (rf2-vzld77 / rf2-tj6w9l): the framework's durable subsystem
state — machine snapshots, the route slice, the spawn registry, the
elision registry — moved OUT of app-db's `:rf/runtime` container into a
SEPARATE **runtime-db partition** keyed by the reserved `:rf.runtime/*`
namespace, catalogued in
[Conventions §Reserved runtime-db keys](../../../spec/Conventions.md#reserved-runtime-db-keys)
and [002-Frames §The two-partition frame contract](../../../spec/002-Frames.md).
Xray's `[runtime]` group surfaces these six as operator-facing section
labels; the underlying paths now live under the runtime-db partition's
`:rf.runtime/*` roots (NOT app-db):

| Section label | Underlying path (runtime-db partition) | Owner | One-line role |
|---|---|---|---|
| `:rf/machines` | `[:rf.runtime/machines :snapshots]` | machine runtime | Per-frame map of `<machine-id> → :rf/machine-snapshot` — every active machine's snapshot. |
| `:rf/system-ids` | `[:rf.runtime/machines :system-ids]` | machine runtime | Reverse index `<system-id> → <gensym'd-machine-id>` for `:system-id` named-machine addressing. |
| `:rf/spawned` | `[:rf.runtime/machines :spawned]` | machine runtime | Declarative-`:spawn` / `:spawn-all` spawn registry — `<parent-id> → {<invoke-id> <slot>}` for the destroy-cascade walker. |
| `:rf/route` | `[:rf.runtime/routing :current]` | routing runtime | The current route slice `{:route-id :params :query :transition :error}`. |
| `:rf/pending-navigation` | `[:rf.runtime/routing :pending-navigation]` | routing runtime | Pending-navigation slot populated when a `:can-leave` guard rejects; cleared by `:rf.route/continue` / `:rf.route/cancel`. |
| `:rf/elision` | `[:rf.runtime/elision]` | elision runtime | Wire-elision declaration registry — `{:declarations {<path> {:large? :hint :source}} :sensitive-declarations {<path> {:sensitive? :hint :source}}}`. Hydrated at `reg-frame` time from the **frame-owned** `:sensitive {:app-db …}` / `:large {:app-db …}` path-map declarations (installed by `re-frame.frame-classification` under `:source :frame`, Spec 015 §Frame-owned durable classification); consulted by `rf/elide-wire-value` at every wire-boundary emit. Durable app-db classification is frame-owned, NOT a schema-slot route — per [Spec 015 §Schemas describe shape](../../../spec/015-Data-Classification.md), a `reg-app-schema` `{:sensitive? true}` slot prop is no longer a nomination path into this registry (machine `:data-schema` props and schema-validation-failure redaction are separate, schema-owned surfaces). |

Conventions is the canonical home; this table is the panel-facing
projection. The `runtime-areas` lookup in `app_db_diff_helpers.cljc`
maps each operator label to its sub-path under the **runtime-db
partition value** — sourced from `:rf.xray/target-frame-runtime-db` (the
live partition) + each focused epoch's runtime-db pre/post-image (the
`:rf.db/runtime` projection of `:frame-state-before` / `-after`), the
same way the Machines inspector + Routing tab read runtime-db. The TOP
user-domain section reads the app-db partition (minus any reserved `:rf*`
key). Because the runtime subsystems no longer live in app-db, an app-db
diff triple is never runtime-owned; `partition-reserved` /
`reserved-path?` now key on the reserved `:rf*` NAMESPACE family (a
framework-internal slot a host might stash at the app-db root). If a new
subsystem lands in Conventions, the `runtime-areas` table and this
section are updated in lockstep.

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
the panel renders the projected view of an epoch, and any value that the
observed frame declared `:sensitive` is substituted by the `:rf/redacted`
sentinel through **egress projection** (`project-egress` keyed on the
observed frame) — never by mutating the stored record. Per Spec 015 §6
(Epoch projection — no storage-side mutation), raw epoch records remain
in-process and the legacy storage-side `(rf/configure! :epoch-history
{:redact-fn …})` hook is gone; projection at the export / on-box-render
boundary is the normal answer. When the underlying value at a redacted
path actually changed across a cascade, the structural diff correctly sees
`:rf/redacted` = `:rf/redacted` and emits no row — the elision
contract is preserved (per `diff/engine.cljc` §Sentinel-aware
modified handling). The developer is left with an empty diff and no
signal that anything happened in the redacted slot.

Xray surfaces a **separate-from-diff** signal: a muted-grey chip
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
Computed inside `re-frame.epoch.assembly/build-record` from the raw,
in-process db-before / db-after values (no storage-side mutation runs;
projection happens only at egress / render) — parallel to
the `:rf.epoch/sensitive?` rollup. A path `P` counts in the framework's
figure when:

1. `P` is frame-declared sensitive (`[:rf.runtime/elision :sensitive-declarations]`
   in the runtime-db partition, EP-0001 rf2-vzld77,
   hydrated from the frame's `:sensitive {:app-db …}` path-map declarations
   under `:source :frame` per
   [Spec 015 §Frame-owned durable classification](../../../spec/015-Data-Classification.md)).
2. `(not= (get-in db-before P) (get-in db-after P))` — value-equality
   on the raw (unprojected) in-process dbs.

This is the **exact** count of declared-sensitive paths that mutated
this cascade. Xray reads it directly from the record; no walk, no
heuristic.

**Heuristic fallback (rf2-bz1cl).** Records that lack the egress slot
(legacy snapshots, hand-rolled test fixtures, hosts whose frames declared
no `:sensitive {:app-db …}` classification, so no sensitive-declarations
registry exists) fall back to a Xray-side heuristic — paths `P` where:

1. `(= :rf/redacted (get-in db-before P))`, AND
2. `(= :rf/redacted (get-in db-after  P))`, AND
3. `P`'s parent subtree is NOT `identical?` across `db-before` /
   `db-after` (something in the enclosing subtree changed).

Distinct paths are counted independently. The elision registry —
post-EP-0001 (rf2-vzld77) at `[:rf.runtime/elision]` in the runtime-db
partition, no longer in the app-db `:db-before` / `:db-after` the
heuristic walks — is therefore naturally excluded; a host that still
carries a stray reserved subtree in app-db has it skipped (the elision
registry's own values may include `:rf/redacted` as
documentation/sentinel form). Condition (3)
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
log. Xray's writes are funnelled through dispatch — inspection is the
default, rewind is opt-in (per
[Tool-Pair §Time-travel: epoch snapshots and undo](../../../spec/Tool-Pair.md#time-travel-epoch-snapshots-and-undo)).

## On-box local-render egress policy (EP-0015, rf2-t55hxg.12)

Even though the panel renders on-box, the value the section model hands
to the shared edn-inspector is **projected** through the frame-owned
egress policy first. The App-DB panel is the **graduating on-box-dev-tool
consumer** of the `:rf.egress/local-redacted` profile (the on-box dev-UI
default — [Spec 015 §Projection profiles](../../../spec/015-Data-Classification.md#projection-profiles--the-rfegress-enum-provisional)
+ [§The graduation gate](../../../spec/015-Data-Classification.md#the-graduation-gate)).

The section-model sub `:rf.xray/app-db-state` projects every value-bearing
partition (the app-db `value` / `before` + the runtime-db `runtime-value`
/ `runtime-before`) through `re-frame.core/project-egress` under
`:rf.egress/local-redacted`, keyed on the **observed frame** (the frame
the picker / focus selects). The contract — the shared seam
`day8.re-frame2-xray.panels.local-render/local-render-value`:

- **Suppress sensitive display by default.** A slot the observed frame
  declared `:sensitive` (`re-frame.frame-classification`) is replaced by
  the `:rf/redacted` sentinel — which the edn-inspector already renders as
  a first-class muted chip (the R8 redaction type). A shoulder-surfer,
  screen-share, or recorded debugging session never sees the secret.
- **The local operator MAY see large values.** `:rf.egress/local-redacted`'s
  floor (`:rf.size/include-large? false`) is overlaid with an explicit
  `:rf.size/include-large? true` (the override wins —
  [`re-frame.projection` composition](../../../spec/015-Data-Classification.md#projection-profiles--the-rfegress-enum-provisional)).
  On-box size bounding is a *display ergonomics* concern owned by the
  edn-inspector, **not** an egress-redaction concern — the operator is
  entitled to big values, only secrets are withheld.
- **Per-frame.** The policy is applied from the OBSERVED frame's own
  classification, passed as the explicit `:frame` opt, never a borrowed or
  ambient one. Both the value and its diff pre-image are projected under
  the same policy, so a sensitive slot reads `:rf/redacted` on both sides
  and the inline `← was X` annotation never reconstructs the redacted
  content.
- **Fail-closed.** An unreachable observed frame (nil / destroyed /
  never-registered) projects WITHOUT a `:frame` opt so the underlying
  `elide-wire-value` walker takes its frameless redact-whole branch —
  redacting the entire value rather than shipping it raw under no policy.

Revealing sensitive values is **not** a process-global toggle. Per
[Spec 015 §Cross-tool visibility grain](../../../spec/015-Data-Classification.md#cross-tool-visibility-grain)
on-box visibility is **per (tool, frame)**: an explicit trusted-local
operator act flips the grain to `:rf.egress/local-raw` (include sensitive
AND large), carried by `local-render-value`'s `raw?` arg. This is the
on-box analogue of the off-box redaction call site
`derivation-graph-helpers/redact-graph-for-egress` (rf2-yjarv6) — the same
frame-owned, fail-closed `project-egress` boundary, here the on-box render
default rather than the off-box wire.

## "Show me when this changed"

The high-leverage right-click affordance. When invoked on any path:

1. Xray walks `epoch-history`, diffs each epoch's `:db-before` and
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

- **Diff caching** per `[frame-id epoch-id]` (rf2-nfgps — frame-scoped
  so two frames with overlapping epoch-ids never collide; see §Diff
  engine). A second render of the same epoch is O(1).
- **Slice virtualisation** for slices whose `after` value is large
  (e.g., a 10k-entry vector). The slice mini-panel renders the head
  and tail, with a `… 9970 entries …` ellipsis; click expands.
- **Segment-inspector popup** resolves the inspected value via
  `get-in` (O(path-depth)) against the live target-frame db; no
  separate watch graph. Per-node expand-state is shared with the
  data-inspector slot in `:rf/xray` app-db so a value the user
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

When Xray is embedded inside Story
([`008-Embedding-Contract.md`](008-Embedding-Contract.md)) and the
variant is a sim-clone (Story branches `app-db` so each variant runs in
isolation without polluting the host), the diff has TWO axes:

- **Branch baseline diff** — what's different between the variant's
  app-db and the host's app-db, irrespective of any dispatch.
- **Cascade diff** — what changed because of THIS dispatch.

Xray renders both in separate sections; cascade-diff is the headline,
branch-baseline-diff is a collapsed-by-default "What's different on this
branch" group.

### Cross-frame diff

**Bug class:** "Multiple frames share substate via shared sub keys;
where does an event in frame A change values that frame B reads?"

When multi-frame apps share substate (e.g. an auth slice mirrored across
two frames), Xray renders the diff per-frame and shows where a write
in one frame propagates to another.

### Pin two epochs side-by-side

**Bug class:** "I want to diff arbitrary epoch A vs epoch B, not just
before/after of a single event."

Pin two epochs via `*`; press `=` → opens a split view in the App-db
tab showing slice-by-slice diff between the two pinned epochs. Closes
a long-standing gap in both 10x and Xray (workflow-gap-4 from the
findings). Needs Editscript A* for compact diffs over arbitrary epoch
pairs.
