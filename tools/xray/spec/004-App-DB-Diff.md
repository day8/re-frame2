# 004-App-DB-Diff

> **See also**: [`021-Dynamic-Panel-Designs.md` §4](021-Dynamic-Panel-Designs.md#4-the-app-db-panel-state-bridge) for the canonical content design. (Its §4.4 downstream-subs overlay is retired — the popover was removed by rf2-kbxgj · rf2-ilubp and its resolver by rf2-6r9j.17.)

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

A **sectioned state inspector with inline diff** — the focused epoch's
post-state, with its pre-state carried into the shared inspector for
added, modified and removed annotations. APP STATE is separate from
the reserved framework areas; the operator can expand any subtree
without switching to another panel. Path navigation is the shared
inspector's **zoom-into-node** gesture — double-click or `Enter`
re-roots the tree onto a container and the breadcrumb zooms back
(§Path interaction).

This is the **single most-used Xray surface** after the Epoch panel.

## Affordance

App-db tab — the sectioned state view specified by
[021 §4](021-Dynamic-Panel-Designs.md#4-the-app-db-panel-state-bridge).
The lazy inspector carries the diff in place; there is no separate
changed-slices-only default surface.

---

The state value is complete, but its DOM is lazy and collapsible.
Changed nodes remain visible in context instead of being duplicated
in a second diff list. The former pinned-slices strip is retired
(§What this replaces); it is not a requirement on the current panel.

## Default view

APP STATE comes first, followed by the reserved-area sections from
`app-db-diff-helpers/current-state-sections`. Each section uses the
shared `edn-inspector` widget; machine and spawned-instance areas
fan out by identity, while singleton areas remain single sections.
See [021 §4.2–§4.3](021-Dynamic-Panel-Designs.md#42-layout-figma-design--rf2-ad7zx)
for the layout and missing-vs-present pre-image contract.

With a focused epoch, the value is that record's `:db-after` and the
diff base is its `:db-before`, not today's live db. With no focused
epoch, the panel can show live state without inventing a pre-image.

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
- **The shared edn-inspector consumes the projection directly** — it
  takes `value` + `before` and resolves each node's op out of the
  projection, so there is no per-consumer row tuple. The former
  `[path before after op]` shape went with the App-DB `:diff` lens
  (rf2-vv3m6) and the Machine Inspector snapshot drill-in
  (rf2-g2axio); nothing converts rows today.

The framework's `:rf/epoch-record` does **not** pre-compute changed
paths (the runtime stays cheap); Xray derives the diff at render time.
The panel's read-model holds no diff cache — it re-derives from the
live subs on every render, and the only memo in the chain is the
inspector's per-mount projection memo (§Performance).

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

> **rf2-nfgps — frame-scoped cache key (the caches themselves are
> RETIRED, rf2-p53m2).** The per-epoch diff caches keyed on the
> COMPOUND `[frame-id epoch-id]`, never `:epoch-id` alone, because the
> framework's epoch contract guarantees `:epoch-id` is unique only
> WITHIN a frame's history (the global-counter scheme that makes ids
> incidentally process-unique today is an implementation detail, not a
> spec promise). In a multi-frame app two frames can carry the same
> `:epoch-id`; an id-only key would have let one frame read another
> frame's cached diff, and a frame-switch prune would have evicted a
> sibling frame's live entries. Those three caches went with the dead
> `:rf.xray/app-db-diff` sub family (rf2-p53m2) and **no diff cache
> ships today**, so there is no key left to compound — which is also
> what closed the rf2-zgrhw prune-growth risk. The rule is kept here
> because it binds any future per-epoch cache, not because one exists.

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
the diff and the per-member chrome shows with the key intact.

**Maps take the same union (rf2-3x7nj.26.3).** A map is keyed by a
shared key, but one whose every old key was removed and every new key
added puts its before-leaves and after-leaves at disjoint paths exactly
as a set swap does (`{:form {:errors {:email "bad"}}} → {:form {:errors
{:name "required"}}}` ⇒ `[[:form :errors :email] :-] [[:form :errors
:name] :+ "required"]`), and a one-sided walk promoted the surviving
`:form` to a wholly-removed root. The uniformity walk therefore collects
the union of both sides' keys, so a map is a wholly-changed root only
when its opposite side is empty or absent. Vectors pair each element
with its counterpart through the replay slots (§Vectors and lists).

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

**A vector `:r`'s `:before`-value is resolved through the SAME replay
slots (rf2-96csq4), not a raw `value-at`.** Although `:r` itself never
runs through the replay, its edit-index still addresses a position in
the FINAL after-vector — a position a prior `:+`/`:-` at the same
parent may already have shifted. Resolving `:before` as a naive
`(value-at before [parent-path after-idx])` reads the WRONG slot (or an
out-of-range one) whenever the vector saw a mixed insert/delete-then-
replace script. The correct before-value is `slots[after-idx]` — the
survivor index the unified `:+`/`:-` replay already computed for that
exact after-position (the replay's `:slots` output is 1:1 with the
after-vector regardless of whether the caller asks about a survivor, an
insert, *or* a replace target). REPRO: `[:x :y] → [:new :x :z]` ⇒
`[[0] :+ :new] [[2] :r :z]`. The naive read, `(value-at before [2])`,
is out-of-range on the 2-element before-vector — the missing-sentinel
misclassifies the slot as `:added` and `:y`'s removal never surfaces
anywhere (not `:modified`, not `:removed` — silently lost). The replay-
resolved read, `slots[2]`, is `1` (`:y`'s original index), correctly
classifying `[2]` as `:modified :y → :z`.

**The same holds for every vector index a path descends THROUGH, not
only its last segment (rf2-3x7nj.26.2).** `{:todos [a b]} → {:todos
[new a b']}` ⇒ `[[:todos 0] :+ new] [[:todos 2 :done?] :r true]` edits
the todo that sat at before-index 1, so a raw read of `[:todos 2
:done?]` falls out of range and the toggle reads as `:added`. Every
before-side read — an `:r`'s `:before`, a nested `:-`'s removed value, a
replaced collection's expansion, a nested vector's own replay, and the
wholly-changed walk's pairing of an element with its counterpart — maps
each vector segment of the path through that vector's replay slots, from
the root down. A map key or set member is never index-shifted and passes
through unchanged.

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

Every change-bearing row carries the shared edn-inspector's three
marks — a gutter glyph, a 2px left-edge stripe, and a row-background
wash. No key is tagged with a literal `(added)` / `(removed)` word.

| Op | Glyph | Visual |
|---|---|---|
| `:added` | `+` | Green stripe; green wash across the whole added subtree. |
| `:modified` | `~` | Yellow stripe; yellow wash. A modified leaf scalar carries an inline `← was <prior>` annotation rather than a side-by-side pair. |
| `:removed` | `-` | Red stripe; red wash; the value renders struck-through. |
| `:children` | `◴` | No stripe and no wash — the change is below and the descendants carry the signal; a collapsed changed container carries the R3 `[N∆]` chip. |

`:same` and `:same-shifted` paint no glyph, no stripe and no wash — a
`:same-shifted` survivor carries its `(was N)` suffix alone. The glyph
paints the reserved `:diff-gutter` cyan-teal for every active op; the
colour above lives in the stripe and the wash.

**There is no diff flash.** A 400ms yellow → transparent tween on
newly-touched slices was specified here and its `rf-xray-diff-flash`
keyframes shipped in `theme/global_styles.cljs`, but no element ever
carried the animation. The keyframes and the `:flash-duration-ms`
motion token were deleted under rf2-y8doi.29 (2026-09-17);
`theme/global_styles_cljs_test` and `theme/tokens_cljs_test` pin their
absence, with the applied `rf-xray-fade-in` tab cross-fade as the
control.

## Path interaction: zoom into a node (rf2-h71e0 · rf2-zl4rs)

The panel mounts the shared edn-inspector with `:zoomable? true`, and
zoom is the **only** path gesture: double-click a container — or press
`Enter` while it is keyboard-focused — and the inspector re-roots onto
that node. A breadcrumb above the body shows the path from the original
root, each crumb zooms back to that level, and `Esc` zooms up. A single
click on a key segment does nothing. The expand triangle owns its own
double-click (rf2-6nw3g), so toggling a node never zooms it.

Zoom applies inside the single full+diff rendering: with a pre-image
present the widget re-roots BOTH `value` and `before` along the zoom
path, so the diff annotations keep painting relative to the focused
subtree. The zoom path and the per-node expand overrides are both keyed
by the panel's stable `:site-id` — `[:rf.xray/app-db <render-id>]`,
qualified by the mount's instance name where the caller supplies one —
so both survive a tab-switch round-trip (rf2-pvsxs · rf2-t3fz).

The gesture contract is
[021 §10.0.11](021-Dynamic-Panel-Designs.md#10011-zoomable-opt--zoom-into-node--breadcrumb-rf2-h71e0-gesture-reworked-rf2-zl4rs);
the panel-wide interaction model is
[021 §10.5](021-Dynamic-Panel-Designs.md#105-interaction-model).

> **The click-to-inspect popup is RETIRED (rf2-y8doi.29, 2026-09-17).**
> The rf2-e9tb0 design made every segment of a diff path a click target
> that opened a segment-inspector popup at that prefix, and rf2-jmucu
> resolved the popup against the focused epoch's `:db-after` rather than
> the live `target-frame-db` so it could not disagree with the body
> off-head. Nothing ever opened it — no view, sub or event in the tree
> dispatched the open — so the popup view, its two exclusive suites, the
> `:rf.xray/focus-slice-path` / `:rf.xray/clear-slice-focus` events and
> the shell mount were all deleted. The reasoning survives in
> §Diff semantics, which is where the focused-epoch rule now lives for
> the body itself. This panel also deliberately declines the
> edn-inspector's `:popup-affordance?` opt (rf2-7sdja, Mike's 2026-05-26
> live-testing call): the side panel has the horizontal room to render
> the whole tree in place.

## What this replaces (rf2-e9tb0)

The pinned-watches strip was DROPPED when clickable path segments
landed (Mike 2026-05-19 Q13). The diff already identifies changes
surgically; the pin-this-up-front flow was redundant when any prefix
of any diff path could be inspected with one click on its breadcrumb
segment. The `:rf.xray/pin-slice` / `:rf.xray/unpin-slice` /
`:rf.xray/reorder-pinned-slices` events and the corresponding
`:pinned-slices-store` slot are no longer registered, and neither are
the `pin-path` / `unpin-path` / `reorder-paths` helpers.

The clicking gesture that replaced them was itself retired unreached
under rf2-y8doi.29 (§Path interaction), so neither affordance ships:
the panel renders the whole value in place and zoom is how the
operator narrows it.

## Reserved-keys group

EP-0001 (rf2-vzld77 / rf2-tj6w9l): the framework's durable subsystem
state — machine snapshots, the route slice, the spawn registry, the
elision registry — moved OUT of app-db's `:rf/runtime` container into a
SEPARATE **runtime-db partition** keyed by the reserved `:rf.runtime/*`
namespace, catalogued in
[Conventions §Reserved runtime-db keys](../../../spec/Conventions.md#reserved-runtime-db-keys)
and [002-Frames §The two-partition frame contract](../../../spec/002-Frames.md).
Xray's `[runtime]` group surfaces these **five** as operator-facing
section labels — the whole of `app_db_diff_helpers/runtime-areas` — and
the underlying paths live under the runtime-db partition's
`:rf.runtime/*` roots (NOT app-db):

| Section label | Underlying path (runtime-db partition) | Owner | One-line role |
|---|---|---|---|
| `:rf/machines` | `[:rf.runtime/machines :snapshots]` | machine runtime | Per-frame map of `<machine-id> → :rf/machine-snapshot` — every active machine's snapshot. |
| `:rf/spawned` | `[:rf.runtime/machines :spawned]` | machine runtime | Declarative-`:spawn` / `:spawn-all` spawn registry — `<parent-id> → {<invoke-id> <slot>}` for the destroy-cascade walker. |
| `:rf/route` | `[:rf.runtime/routing :current]` | routing runtime | The current route slice `{:route-id :params :query :fragment :transition :error :nav-token}`, schema `:rf/route-slice` (Spec 012 §The `:rf/route` slice). |
| `:rf/pending-navigation` | `[:rf.runtime/routing :pending-navigation]` | routing runtime | Pending-navigation slot populated when a `:can-leave` guard rejects; cleared by `:rf.route/continue` / `:rf.route/cancel`. |
| `:rf/elision` | `[:rf.runtime/elision]` | elision runtime | Wire-elision declaration registry — `{:declarations {<path> {:large? :hint :source}} :sensitive-declarations {<path> {:sensitive? :hint :source}}}`. Written by the EP-0025 commit-plane `:sensitive` / `:large` classification effects (a `reg-event` returns them alongside `:db`, installed by `re-frame.elision/apply-classification-effects` under `:source :effect`, Spec 015 §Data classification); also fed by `reg-flow` outputs (`:source :flow`) and subsystem projection-relative declarations (routing / machines). Consulted by `rf/project-egress` at every wire-boundary emit. Durable app-db classification rides the commit-plane effects, NOT a schema-slot route — per [Spec 015 §Schemas describe shape](../../../spec/015-Data-Classification.md), a `reg-app-schema` `{:sensitive? true}` slot prop is no longer a nomination path into this registry (machine `[:schemas :data]` props and schema-validation-failure redaction are separate, schema-owned surfaces). |

Conventions is the canonical home; this table is the panel-facing
projection. The `runtime-areas` lookup in `app_db_diff_helpers.cljc`
maps each operator label to its sub-path under the **runtime-db
partition value** — sourced from `:rf.xray/target-frame-runtime-db` (the
live partition) + each focused epoch's runtime-db pre/post-image (the
`:rf.db/runtime` projection of `:frame-state-before` / `-after`), the
same way the Machines inspector + Routing tab read runtime-db. The TOP
user-domain section reads the app-db partition (minus any reserved `:rf*`
key). Because the runtime subsystems no longer live in app-db, an app-db
diff triple is never runtime-owned; `reserved-namespace-key?` /
`user-domain-db` now key on the reserved `:rf*` NAMESPACE family (a
framework-internal slot a host might stash at the app-db root). If a new
subsystem lands in Conventions, the `runtime-areas` table and this
section are updated in lockstep.

```
┌─ [runtime] ───────────────────────────────────────┐
│  :rf/machines            (3 active)               │
│  :rf/route               :app/cart                │
│  :rf/spawned             (1 live)                 │
└────────────────────────────────────────────────────┘
```

These are informational: the panel labels each section with its
reserved `:rf/*` key so the programmer recognises the state as
runtime-owned and knows to open the equivalent dedicated tab —
Machines, Routing — for that subsystem's own view.

**No cross-panel navigation ships from this panel, and no soft cue.**
Clicking a reserved-area row jumps nowhere: `select-tab` appears
nowhere in the panel's namespaces. A reserved-area section behaves
exactly like the TOP section — expand, collapse, zoom.

## Full-tree escape hatch

None is needed, because the panel never hid the tree. The TOP section
IS the whole user-domain `app-db`, auto-expanded to the panel's
`:default-expanded-depth` of 3 and expandable the rest of the way by
hand; the reserved areas render as sibling sections beside it.

The explicit `Show full app-db tree ▸` row was dropped under rf2-e9tb0
in favour of the segment-inspector popup, and that popup was in turn
retired unreached under rf2-y8doi.29. Nothing replaced either, and
nothing needs to: zoom is what NARROWS the view, and zooming out (a
breadcrumb crumb, or `Esc`) is what restores the whole tree.

## Redacted-paths-modified hint chip

Per [Spec 015 §Data Classification](../../../spec/015-Data-Classification.md)
and [Security §Epoch privacy posture](../../../spec/Security.md#epoch-privacy-posture--raw-in-process-records-vs-projected-egress),
the panel renders the projected view of an epoch, and any value that the
observed frame declared `:sensitive` is substituted by the `:rf/redacted`
sentinel through **egress projection** (`project-egress` keyed on the
observed frame) — never by mutating the stored record. Per Spec 015 §6
(Epoch projection — no storage-side mutation), raw epoch records remain
in-process and the legacy `(rf/configure! {:epoch-history
{:redact-fn …}})` hook is gone (retired outright 2026-09-08, rf2-kuky.7); projection at the export / on-box-render
boundary is the normal answer. When the underlying value at a redacted
path actually changed across a cascade, the structural diff correctly sees
`:rf/redacted` = `:rf/redacted` and emits no row — the elision
contract is preserved (per `diff/engine.cljc` §Sentinel-aware
modified handling). The developer is left with an empty diff and no
signal that anything happened in the redacted slot.

Xray surfaces a **separate-from-diff** signal: a muted-grey chip in
the TOP section's header, beside the `app-db` title, when count > 0.

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

1. `P` is classified sensitive (`[:rf.runtime/elision :sensitive-declarations]`
   in the runtime-db partition, EP-0001 rf2-vzld77,
   written by the EP-0025 commit-plane `:sensitive` classification effect — a
   `reg-event` returns `:sensitive` alongside `:db` — under `:source :effect` per
   [Spec 015 §Data classification](../../../spec/015-Data-Classification.md)).
2. `(not= (get-in db-before P) (get-in db-after P))` — value-equality
   on the raw (unprojected) in-process dbs.

This is the **exact** count of declared-sensitive paths that mutated
this cascade, and it is the ONLY count. Xray reads the slot straight
off the focused record — the same record `:value` and `:before` come
from, so the chip can never describe a different epoch — and does no
walk of its own.

**There is no fallback, and that is deliberate.** A Xray-side
heuristic was designed under rf2-bz1cl for records lacking the slot:
count paths reading `:rf/redacted` on BOTH sides whose parent subtree
is not `identical?` across the pair, as a tight upper bound. It was
never implemented, and the chapter previously described it as though
it shipped.
[Spec-Schemas §`:rf/epoch-record`](../../../spec/Spec-Schemas.md#rfepoch-record)
marks the count OPTIONAL and tells consumers to read an absent slot as
0, which is exactly what the panel does: the chip renders only on a
positive integer, so a legacy snapshot, a hand-rolled fixture or a
host that classified nothing draws **no chip at all** rather than a
`0` chip or a guessed one. On a privacy surface an approximate count
is worth less than an honest silence, and re-proposing the heuristic
should start from that.

## Read-only

The app-db panel is **read-only forever** (lock #3 in
[`DESIGN-RATIONALE.md`](./DESIGN-RATIONALE.md)). No in-place edit
boxes, no "set value" affordances. The runtime is the source of
truth; pokes from the debugger are out of scope.

The user can:

- Expand / collapse any container — click the `▸` / `▾` triangle, or
  press `Space` on the focused row
- Zoom into a container and back out — double-click or `Enter`, then a
  breadcrumb crumb or `Esc` (§Path interaction)

That is the whole list. The click-to-inspect popup that this list once
named went unreached under rf2-y8doi.29; the section-header affordance buttons this
bullet list once also named were dropped when the panel became a
current-state inspector (rf2-okvit, recorded in the
`panels/app_db_diff.cljs` namespace docstring); the universal
value-copy gesture on the renderer itself was retired separately under
rf2-6r9j.24, honouring the
[`021-Dynamic-Panel-Designs.md`](./021-Dynamic-Panel-Designs.md) §10.5
B.9 lock rather than reopening it.

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
  floor (`:rf.egress/include-large? false`) is overlaid with an explicit
  `:rf.egress/include-large? true` (the override wins —
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
- **Fail-closed.** The observed frame-id is **stamped verbatim** as the
  `:frame` opt, whatever it is. `project-egress` reads that opt by **key
  presence**, so an unreachable observed frame (nil / destroyed /
  never-registered) takes the walker's **unresolvable-frame** redact-whole
  branch, redacting the entire value rather than shipping it raw under no
  policy. The key is stamped, **not** omitted: an ABSENT `:frame` opt would
  let the walker fall through to the **ambient** dynamically-bound frame
  (`frame/resolve-current-frame`) and ship value-bearing fields RAW under
  that borrowed frame's (possibly empty) policy — the exact ambient-borrow
  leak this seam abolishes (rf2-cra0nq, mirroring the off-box
  derivation-graph fix rf2-udkj69). Under the `:rf.egress/local-raw` opt-in
  (explicit `:rf.egress/include-sensitive? true`) the walker ships the value
  raw even under an unresolvable frame — the operator has deliberately
  waived redaction.

  **This seam once minted a DEAD-FRAME SENTINEL, and the reason it no longer
  needs one is worth keeping (rf2-ws60, retired by rf2-kuky.5).** The walker
  used to resolve its frame with `(or (:frame opts) …)`, so a nil `:frame`
  read as ABSENCE and borrowed the ambient frame. To force the fail-closed
  arm, the seam substituted a fake id that could never resolve — and the
  naive reading of "pick an id no app would register" was wrong three times
  over, each wrong reading shipping in turn: a `::`-namespaced keyword is an
  ordinary public keyword an app CAN register; a private host object handed
  back to a caller is an id that caller can register; and a per-call host
  object still travels inside an opts map the caller can register against and
  then replay. `make-frame` validates no `:id` type and the registry is keyed
  by whatever id it is handed, so ANY value a caller can obtain is a value a
  caller can register a live frame under — which makes the stamp RESOLVE and
  ships the value raw under that frame's empty declaration registry.

  There is exactly one id with no such weakness, and it is `nil`: the walker
  guards its live-frame arm on the id being non-nil, so a nil `:frame` cannot
  resolve however the registry is populated. Making the walker believe an
  explicit nil therefore removed the substitute, the liveness probe and the
  whole escape-hatch surface at once. Pinned by `local_render_cljs_test` §7.

Revealing sensitive values is **not** a process-global toggle. Per
[Spec 015 §Cross-tool visibility grain](../../../spec/015-Data-Classification.md#cross-tool-visibility-grain)
on-box visibility is **per (tool, frame)**: an explicit trusted-local
operator act flips the grain to `:rf.egress/local-raw` (include sensitive
AND large), carried by `local-render-value`'s `raw?` arg. This is the
on-box analogue of the off-box redaction call site
`derivation-graph-helpers/redact-graph-for-egress` (rf2-yjarv6) — the same
frame-owned, fail-closed `project-egress` boundary, here the on-box render
default rather than the off-box wire.

> **Status (rf2-g7zayk): the operator reveal act is not yet wired.** The
> `raw?` **mechanism** exists end-to-end — `local-render-value`'s `raw?`
> arg resolves to `:rf.egress/local-raw` and is unit-tested
> (`local_render_cljs_test` §local-raw-opt-in-reveals-sensitive) — but no
> operator surface (no App-DB panel affordance) flips it today. Every
> on-box App-DB render therefore currently uses the
> `:rf.egress/local-redacted` default (`raw?` false), so the panel **fails
> closed**. Surfacing sensitive on-box values is a **deferred**
> per-(tool,frame) affordance, not a shipped reveal path; when it lands it
> is the caller passing `raw? true` at the `local-render-value` call site,
> not a change to this contract.

## "Show me when this changed" — RETIRED UNBUILT (rf2-okvit · rf2-y8doi.29)

The design was a right-click affordance on any path: walk
`epoch-history`, diff each epoch's `:db-before` / `:db-after`, list the
epochs that touched the path, and rebase the event detail on click.

It was dropped from this panel when the panel became a current-state
inspector (rf2-okvit, recorded in the `panels/app_db_diff.cljs`
namespace docstring), which left the data half of it standing with no
renderer: a sub, an event, and the `epochs-touching-path` /
`path-touched?` / `op-at-path` / `event-of-epoch` walker in
`app_db_diff_helpers.cljc`. Nothing subscribed the sub, so all of it
was deleted under rf2-y8doi.29 (2026-09-17).

Per-path history is not a shipped Xray affordance. A single cascade's
change is read in the Epoch panel's `:db` section; re-proposing this
one means building the renderer, not reviving the walker.

## Performance

- **No diff cache.** The read-model is purely reactive:
  `:rf.xray/app-db-current+diff` resolves the focused record's
  `:db-after` / `:db-before` and `:rf.xray/app-db-state` sections them
  on each render, with no memo atom anywhere in the chain. rf2-p53m2
  removed the three `[frame-id epoch-id]` caches along with the dead
  sub family they served, which is also what closed the rf2-zgrhw
  prune-growth risk — there is nothing left to age out.
- **The projection memo is per-mount** (rf2-4p1vl). The edn-inspector
  memoises its `engine/project` call in the per-mount store, keyed on
  `identical?` of both inputs, so an expand toggle or a parent
  re-render does not re-walk the pair; the memo is released with the
  mount.
- **Lazy DOM, not virtualisation.** A collapsed container renders none
  of its children, auto-expansion is ceilinged at the panel's
  `:default-expanded-depth` of 3, and recursion stops at the widget's
  `:max-depth`. There is **no** slice virtualisation — no head/tail
  windowing and no `… N entries …` row. What IS bounded is realisation:
  a not-`counted?` sequential is realised to `count-bound` (1001) and a
  slot past a capped side paints `::unrealised` — an explicit unknown,
  never `:added` / `:removed` (`views/edn_inspector.cljs`).
- **Sticky expand + zoom.** Both key off the panel's stable `:site-id`
  (rf2-pvsxs · rf2-t3fz), so a subtree the operator drilled into stays
  drilled across a tab-switch round-trip.

## Empty state

The empty state is a property of the VALUE, not of the dispatch count:
the TOP section renders its empty body whenever the user-domain app-db
is an empty map — the boot value, or a db carrying nothing but
reserved `:rf*` keys.

```
   app-db has no user-domain keys yet.
```

The TOP section ALWAYS renders, even empty — it is the panel's anchor,
and an empty user-domain app-db is itself operator information. Empty
or absent reserved areas are filtered at projection time and draw no
card at all (rf2-jcdvo).

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

### Path-origin tags (rf2-s8r6c)

**Bug class:** "Both the event handler's `:db` return and a downstream
flow's `:output` touch this path in the same cascade — which one wrote
the value I am looking at?"

Each changed slice would carry a **path-origin tag** answering *"who
wrote this?"*:

| Tag | Source | Visual |
|---|---|---|
| `[fx :db]`        | The event handler's `:db` effect return.                                          | Green chip on the slice header. |
| `[flow :flow-id]` | A flow's `:output` wrote this path during the cascade (see [`spec/013-Flows.md`](../../../spec/013-Flows.md)). | Violet chip on the slice header. |
| `[mixed]`         | Multiple sources touched this path in this cascade (handler + flow, or multiple flows). | Yellow chip; hovering expands to the per-source breakdown. |

**Neither half of this exists.** The chapter described it as shipped,
reading `:writer` markers off each trace-bus entry under a Spec 009
§Writer attribution section — but Spec 009 defines no such section and
no writer attribution, the runtime stamps no `:writer` marker, and
nothing in Xray renders a chip. Building it starts at the FRAMEWORK:
the runtime must tag the writer at emission, because Xray cannot
re-derive writer identity from the diff (a structural diff sees the
resulting value, never who assoc'd it). Until that lands this is a
wish, not a design under construction.

### Pin two epochs side-by-side

**Bug class:** "I want to diff arbitrary epoch A vs epoch B, not just
before/after of a single event."

Pin two epochs via `*`; press `=` → opens a split view in the App-db
tab showing slice-by-slice diff between the two pinned epochs. Closes
a long-standing gap in both 10x and Xray (workflow-gap-4 from the
findings). Needs Editscript A* for compact diffs over arbitrary epoch
pairs.
