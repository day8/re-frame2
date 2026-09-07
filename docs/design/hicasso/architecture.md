# Hicasso — architecture

The runtime architecture space, the one live arm, the front half it inherited
from the two-arm period, and the mechanism ladders. Decisions cited as HD-nnn are
normative in [decisions.md](decisions.md); the proof obligations live in
[validation.md](validation.md).

> **Status, 2026-07-31: Hicasso is a React adapter, and there is one arm.** Mike
> ruled that Hicasso is "an adaptor for React that is optimised for re-frame2,
> user ergonomics and performance", and dropped Arm 2 (PATCH). The tournament is
> over, decided on **product direction, not on measurement**: Arm 2 *met* its
> hard gate in real Chromium and its code was retired afterwards (`rf2-m6if4`),
> not because it lost. HD-007's two-equal-arms ruling is superseded accordingly;
> the Arm 2 material below is kept as the design record of a road not taken.

> **Amendment, 2026-08-03 (rf2-dabt3): deliverable 2 of the shared front half is
> retired.** The subscription→boundary index is no longer a structure of its
> own. Its readers moved onto Arm 1's key cells, `front.sub-index` was deleted,
> and the six laws are now discharged against the fused table's doors. **This is
> a consequence of the ruling above and could not have been taken before it**:
> deliverable 2 was shared, so while two arms were live the index had to be a
> general, separately-testable algebra with its own namespace and its own
> six-law suite. With Arm 2 withdrawn there is one consumer, and it already had
> a table keyed by the same `(frame, query)` space — so the second structure was
> paying two persistent map entries, and a singleton reader set per key at
> fan-out 1, to say what one reader list on the existing cell says. §2 and the
> Arm 1 section below carry the corrected wording; nothing about the laws
> themselves changed.

## The space

| | **Views re-run when dirty** | **Views run once; reactive holes** |
|---|---|---|
| **React owns the DOM** | **Hicasso lean-React** — the product line | dead — two-owner input clobber |
| **Own renderer, React at islands** | ~~**Hicasso/PATCH**~~ — withdrawn 2026-07-31 (HD-007, superseded) | dead — the shipped read surface requires a re-running body (HD-002) |

`sub`-as-a-value in open Clojure forces the re-run column: without a compiler to
thunk expressions, run-once economics require hole-based authoring, which the
product rejects. The surviving arm owns the front half outright; while both arms
were live they shared it and diverged only at **who applies the patch** (React
elements vs own DOM).

## The shared front half (built once for both arms; now the adapter's alone)

1. **The Hiccup codec** — runtime interpretation of arbitrary hiccup to the arm's
   element representation, built by extracting reagent-slim's measured tag/prop/
   child plumbing (never its component protocol, ratoms, argv equality, or
   scheduler). Codec-work caching (parsed tags, prop-name conversion, cached
   stable component heads) is in scope (HD-004; it was in scope for both arms
   when both ran).
2. ~~**The subscription→boundary index**~~ — **retired 2026-08-03 (rf2-dabt3);
   the dependency edges live on Arm 1's key cells.** The six laws are unchanged
   and still normative — sub-key `(query-id, args)` → boundary set; dirty set
   computed at commit; edge add/remove on mount/unmount/re-run; conditional
   reads as an edge-set replacement — and they are restated here so the tracked
   record is self-sufficient:
   (1) after mount+read, a commit of that sub dirties that boundary only;
   (2) two boundaries sharing a sub both dirty; (3) unmount removes edges;
   (4) a re-run with fewer reads drops edges (conditional read); (5) the broad
   dirty set is the union of all readers of any dirty sub; (6) an unknown dirty
   sub yields the empty set — no phantom boundaries. What changed is **where
   they are answered**, and the tournament's end is what made the change
   available: while both arms were live the index had to be a general,
   separately-testable algebra serving two consumers, so it was a namespace of
   its own (`front.sub-index`) holding two process-global maps. Arm 2 was
   withdrawn on 2026-07-31 and the sole surviving consumer now owns the table.
   Edges therefore live **on the key cell that already existed** — one reader
   list per `(frame, query)`, still shared structure rather than per-boundary
   object fan-out, still not reactions, and still applied only at the commit.
   The reverse edge and the subscription reference are now **one membership**,
   and the forward edge needs no storage at all because the registration
   already holds its read set by reference. The pure `spike-01` model remains
   the proof of the algebra; the laws are discharged against the fused doors in
   `arm1/cell_table_laws_cljs_test`. **The edges serve the one product read
   tier** — the ambient collector, which records them — **and its comparator**,
   grouped, which declares them; the scalar comparator arm does not use them.
   The ~3-line nil-checked evidence sink seam (HD-005) moved with them, event
   shapes unchanged, so tooling attaches without redesign; no evidence
   subsystem ships in v0. **[Amended 2026-08-29, `rf2-6c12m.17`.]** That seam
   is gone: PR #8745 deleted `impl/evidence.cljs` and the collector's two
   taps, since nothing in src attached and the Xray projection reads the
   collector's tables directly. The instruments that did attach now reach the
   runtime through the test kit's own door, `re-frame.hicasso.test.runtime`.
3. **Ergonomics-as-data** — event vectors in attributes, the value placeholder,
   auto-prevent on submit, the composition-gated key-map, `route-link`
   ([authoring.md](authoring.md)).
4. **Sub-layer push-cheap/pull-lazy** — equality cutoff on subscription values at
   the existing sub layer; unchanged hot reads perform no new attach/release.

## Arm 1 — Hicasso lean-React (the product line since 2026-07-31)

- A boundary is a **real React function component** minted by `defview`
  (invocation semantics: HD-016). React owns identity, reconciliation, context,
  refs, errors, concurrency, and the controlled-input end-of-event restore —
  the last of these **only while the element is genuinely controlled**, which
  UIx does not guarantee
  ([the two implementations](studio/controlled-input-two-implementations.md)).
- **Hook budget ≤ 2 per boundary** (paper rule), fully consumed by the
  subscription/epoch hook and the frame-context hook (HD-020); refs are callback
  refs, never `useRef` in the shell. A ViewCell-class per-boundary object graph
  appearing means the arm has failed.
- **Re-render path (grouped/collector topology)**: commit → dirty key cells →
  the union of their reader lists → per-boundary epoch bump → React re-renders
  exactly those boundaries → bodies re-run against the committed snapshot →
  hiccup → codec → React reconciles. No lookup was lost when the index fused in
  (rf2-dabt3): the commit already held the dirty cells and was mapping their
  sub-keys out purely so a second structure could map them straight back. A
  generation fence keeps all reads within one render pass on one commit
  (invariant-5 preservation; the staged-stale CI witness guards it). The scalar
  *comparator* arm has a different topology — React notifies per uSES hook and
  the edges are not in its notify path; it is priced by the 1/3/7/20 heap
  ladder, never by the shell-hook budget.
- **Boundary-exclusive retention is priced, not "absent"**: a boundary
  necessarily retains an identity token, one reader-list membership per key it
  reads, its subscription/epoch cell, and (collector tier) a committed read
  set. **That membership is one slot, not three** — before rf2-dabt3 the
  inventory carried `:index/b->subs` (a forward-edge map entry), `:index/sub->bs`
  (a reverse-edge set membership) and, at fan-out 1, the singleton
  `PersistentHashSet` the reverse map retained per key; the retained inventory
  now carries a single `:cell/reader-membership` in their place. Before an arm
  is admitted, every boundary-exclusive token, callback, hook cell, epoch, map
  entry, and edge membership is inventoried in the 1/3/7/20 heap ladder against
  the 0.4–0.5 KB target — honest accounting, not a claimed absence.
- **Inside-React feasibility constraints** (internalize before writing shells):
  React re-renders only via parent, local state, context, uSES, or root render; a
  root store cannot pay the consistency guarantee "once" (no root observer of
  leaf commits; registering watches during render violates the abandoned-render
  rules); per-boundary uSES is the only tear detector React offers. The honest
  ceiling on bulk may be parity-with-UIx rather than dominance — an open
  measurement, not doom.
- **Interpreter accelerant**: codec caching only. Template extraction with hole
  plans is out of scope for this arm (HD-004) — if an optimization starts wanting
  node references, subscription-addressed holes, or direct DOM writes, it is the
  PATCH strategy and must say so.

## Arm 2 — Hicasso/PATCH (equal-class spike arm; HD-007)

> **WITHDRAWN 2026-07-31, and the code is gone.** Mike ruled that Hicasso is an
> adapter for React (`rf2-2rtt6.10`), so this arm was dropped on **product
> direction, not on measurement** — it *met* its hard gate, in real Chromium.
> Its tree under `implementation/freehand/test/re_frame/bench/hicasso/arm2/` was
> retired by `rf2-m6if4`. The section stays as the design record. What survived
> the retirement is the controlled-restore witness, moved to
> `bench/hicasso/controlled_restore_dom_cljs_test.cljs` and re-taken on React —
> where the `:unchanged-model-rejection` row does **not** hold, which is open on
> `rf2-n3dxw`.

- **Own renderer**: dirty boundaries re-run *inside the commit* against the
  committed snapshot; an own keyed hiccup differ applies the patch; React exists
  only at `defhost` islands. Invariant 5 holds by construction (reads and patch
  happen in the commit's own extent).
- **Differ tiers, one grammar**: template extraction with data-encoded hole plans
  as the static-shape fast path (CSP-safe, no `new Function`; production-proven
  pattern), value-equality cutoff (bail when the new hiccup sexp `=` the old) as
  the fallback, full keyed diff as the floor.
- **Hard-gated obligations**: the **controlled-restore obligation** — off React's
  discrete-event path there is no free end-of-event value restore; the renderer
  must converge controlled value/checked against the live node after every
  controlled dispatch, caret preserved, composition fenced. Plus the islands
  bridge (context/error/event semantics at the seam) and an SSR/hydration story
  of its own. Any PATCH spike that cannot demonstrate the controlled-restore on
  the 100-cell grid witness has failed regardless of its clock numbers.
- **Why it stayed in the tournament while one ran**: it is the only family that
  can *beat* the UIx frontier on bulk and memory rather than approach it. That
  argument was never refuted — it was set aside by the product ruling.

## The sub-read mechanism (HD-002, superseded 2026-07-31 — `sub` is the one product surface)

The operator ruled on ergonomics, not on the measurement this section
originally set up: **the ambient collector — `(sub q)` as a plain tracked read
anywhere in the body — is the only read surface acceptable**, and grouped
`use-subs` sits below the usability bar. **Tier 1 — scalar per-read hooks**
(the raw UIx spine) stays a comparator arm only, the measured floor, exempt
from the product hook budget because it is the control (N reads = N hooks can
never satisfy the product budget, and hook rules forbid conditional reads).
**Grouped `use-subs`** — one fixed hook receiving the complete query
collection before the body — is ergonomically rejected as a product surface
and is kept, and kept working, only as the **comparator** the collector is
measured against; it is not a fallback, and a collector loss does not promote
it. **The ambient collector** — one fixed runtime hook, edge diff after the
body — is the one product tier, and the correctness and cost gates this
ruling did **not** waive still bind in full, adjudicated in
[hd-002-adjudication.md](hd-002-adjudication.md): the render/commit ownership
state machine, the allowed edge-diff operation vs the forbidden ledger, two
pre-registered strategy hypotheses counted only by benchmarked commits, and
the warm 1/3/7/20 allocation-slope survival metric — and the standing
tripwire **overrides the clock**: the first need for a candidate ledger or
generic post-render dependency reconciliation kills the collector outright,
however good its numbers look. It is the same mechanism class as the
predecessor's per-read ledger — the single largest measured killer — which is
why it must win its place rather than defend it. **If the collector trips the
tripwire or fails the survival metric, the outcome is null — never a fallback
to grouped** — or a mechanism not currently on the table must earn its way in
(decisions.md HD-002).

**Frame plumbing and the hook ledger (HD-020)**: each boundary reads the frame
once via the substrate's single internal context, then binds it ambiently for
the render's dynamic extent — inlined helpers and generated callbacks resolve it
without hooks. The ≤2 hook budget is thereby fully consumed (subscription/epoch
hook + frame-context hook); shells use callback refs, never `useRef`.

**The synchronous controlled-input door (HD-019)**: controlled-element intent
lowering dispatches synchronously inside the discrete browser event; the store
notification runs synchronously so React commits the echo in the same turn;
`flushSync` is never the general default, and its single evidence-gated
exception is the controlled-text converge in the element path (amended by
`rf2-ncn5p`). Rejected/unchanged-model paths lean on React's own end-of-event
restore for the value but not the caret; resets are by explicit caller
revision, never value equality. The 100-cell grid witnesses prove the door.

**Measured 2026-08-01 (rf2-n3dxw), and the lean holds for the value only.**
React's restore does fire on a rejection, inside the discrete event and with
nothing re-rendered, so the refused character comes off the screen for free.
It does not put the caret back: assigning `value` moves the cursor to the end
of the control, and React restores a selection only around a commit in which
focus moved. So a mid-string refusal — and every keystroke a normalising model
rewrites — converges with the caret thrown to the end of the field.

**The caret half is the arm's, taken 2026-08-02 (rf2-fki5d), in the element
path.** `front.codec/native-element` calls `front.controlled/install!`, which
wraps the change handler the author wrote: at the end of it, inside the same
discrete event and ahead of React's restore, `flushSync`, write the value the
element renders if the field disagrees — which makes React's own later write a
no-op — and put the caret back by offset from the end of the string. The
authoring surface does not change (an ordinary `:value` / `:on-input` pair),
**no hook is spent**, and the value the element last rendered is read off the
node as `defaultValue`, which React maintains there itself. The matrix, the
two implementations UIx chooses between, and the price are on
[the studio page](studio/controlled-input-two-implementations.md).

## The root (HD-021(b)) — mount, hydration and teardown

The mechanism record for `impl/mount.cljs`, written 2026-08-30 when that
file's docstrings were trimmed to the contract (rf2-76hbj, executing the
rf2-6c12m.4 rule). The ruling is
[HD-021](decisions.md#hd-021--the-v0-execution-contract-root-hmr-headless);
the door names and the `(node config view)` shape are naming-ledger rows 13
and 20 (`docs/design/hicasso/product/naming-ledger.md`); what teardown may
and may not touch is [globals.md](product/globals.md).

**Every door commits before it returns, except the hydrating one.** React 19
renders a root concurrently and a `useSyncExternalStore` notification
schedules at the sync lane rather than committing inline, so `render!` renders
inside `flushSync` and `settle!` is the empty `flushSync` that lets an
already-scheduled notification land — which is what lets a witness read the
DOM on the line after a dispatch. Neither is `act`: `act` diverts work to a
queue that is not the browser's, the right tool for an effect-ordering test
and the wrong one for a witness that reads the page. `hydrate-root!` calls
`hydrateRoot` plain. Adoption is React's own concurrent business and nothing
in the tree forces it synchronously, so a `flushSync` there would manufacture
a schedule no shipped caller has and every row taken through it would be a
row about the manufactured one; the door returns before the tree is adopted,
the DOM on the next line is still the server's, and the completion signal is
the adoption window closing
([the mutation finding](studio/ssr-spike-witness.md#a-finding-the-mutation-proof-produced)).

**The hydrated root's shape, and why it rides on every render.** `tree` is the
one place the root tree's shape is decided. An ordinary root is the bare frame
provider around the app subtree — no extra fiber, passive effect or context
provider, so the tree the bench lane measures carries none of them. A
hydrated root is a Fragment whose first child is `adoption-window-closer` and
whose second is the app under the window's provider, and the handle's
`:adoption` key selects that branch: the window's presence is the one fact
that says a root is hydrated, and there is no second flag to disagree with
it. The wrapper must be reproduced on every subsequent `render!`, because
React reconciles a root by its top element — a bare provider handed to a root
that adopted under the Fragment is not a cheaper render but a different tree,
and React unmounts the adopted subtree and mounts a fresh one, discarding
every node, cell and subscription the adoption established. That was observed
when the shape landed as the first `render!` after a hydration re-running all
four boundary bodies and replacing all four DOM nodes; the standing witness is
`re-frame.hicasso.roots-frames-hydration-dom-cljs-test`, whose
`tearing-down-one-hydrated-root-leaves-the-other-adopted-and-live` row asserts
that a props-equal `render!` after adoption bails at the memo with zero body
runs and every node still the server's. `tree` is public because
`re-frame.hicasso.server/render` builds its element from the same function:
React derives a `useId` from tree position as well as from the prefix, so the
server's bytes and the adopted tree agree by construction rather than by two
implementations agreeing (`docs/design/hicasso/product/dispositions.md`,
HS-11 obstruction 2).

**The adoption window is per-root, closed by a component, and minted in
production.** `hydrate-root!` mints the window and carries it on the handle as
`:adoption`; nothing else can reach it, and the closer, the reporter and the
presence reads all take that one object, so *this root is adopting* is one
fact with one owner rather than a page-wide slot several callers race for.
It is closed by `adoption-window-closer` — a nil-rendering component running
a passive `useEffect` with empty deps, placed outside the frame provider
because it reads no subscription — rather than by the function, because a
passive effect is the earliest thing unambiguously after the hydration
commit; closing before `hydrateRoot` returned would close it before a single
body had run inside it. The window reaches the closer as a prop, which is
what confines a closer to its own root. Per-root rather than page-wide
because the two failure directions are both real: React holds
`onRecoverableError` for the root's whole lifetime and fires it for
post-hydration recoveries too, so a page-wide window (or a reference count)
would label a completed root's later recovery a hydration mismatch whenever
any sibling was still adopting, while a page-wide boolean lets one root's
closer silence another root's genuine mismatch — the
`a-completed-roots-later-recovery-is-not-a-mismatch-while-a-sibling-adopts`
and `two-overlapping-hydrating-roots-recover-and-complain-independently` rows
in the same namespace. The window is minted unconditionally, where the spine
(`re-frame.substrate.spine`) mints only when it is going to install a
reporter, because this one has a production reader: presence starts an
adopted child `:present` rather than `:mounting`
(`a-page-global-adoption-window-steals-an-ordinary-roots-enter-transition`),
so the window and its provider ride in a release build and only the reporter
is debug-gated.

**The reporter, and why the two root options are gated differently.** With no
root options React's default handler is the only channel, so a divergence
React recovers from — a text mismatch, a missing or extra or wrong-type
element — would be an uncaught window error and nothing else, and Spec 011's
`:rf.ssr/hydration-mismatch` would never fire. `hydration-reporter` closes
over one root's window, emits the diagnostic while that window is open, and
always delegates to React's default afterwards, because installing any
`onRecoverableError` takes the default off and a reporter that only emitted
would swallow the error the fail-open rule requires to stay uncaught
(`rf2-2rtt6.97`, on [the SSR spike page](studio/ssr-spike-witness.md)).
Attribute-only divergences are outside React's own contract and stay outside
this channel ([production-server-arm.md](production-server-arm.md)). The
reporter is a diagnostic: its emit compiles away behind
`interop/debug-enabled?`, and what would remain is a replica of the default
React runs anyway, so a release build installs none. `:identifier-prefix` is
behaviour — it decides what `useId` answers, and a release build that dropped
it would hydrate every server `useId` into a mismatch — so it is never gated.
Both doors hand React the bare arity when there is nothing to say, which is
why `root-options` answers nil rather than an empty object.

**`ensure-frame!` seeds before the first render and guards on the
incarnation.** `root!` ensures its frame before `createRoot`: created through
`rf/make-frame` with `:initial-events` when absent, joined untouched when
live. The guard asks `frame/frame-incarnation-token` rather than trusting
`make-frame`, because re-`make-frame`-ing a live id is idempotent
replacement — config and generation refresh, durable state preserved — so an
unguarded call would not fail a joining root, it would silently refresh the
config of the frame the first root created, and the guide promises the
opposite (`docs/core/hicasso/00-installation.md`). `make-frame` drains the
seed to a fixed point before it returns, so the seeded app-db is on the frame
before React renders anything and the first paint is the seeded one — an
ordering the substrate's `frame-root` component cannot have, since its ensure
runs in a `useLayoutEffect` after the first commit, and a root door, being a
function call, simply does first. `hydrate-root!` does not ensure: an
adopting root takes its state from the payload through `re-frame.ssr/hydrate!`,
and a seed there would overwrite it.

**Teardown is root-scoped.** `unmount!` shuts the root's own window first — a
root torn down before its passive effects ran never gets its closer, and an
open window that outlives its root is one nothing can shut — then unmounts
inside `flushSync`. It empties none of the runtime's tables: they are
one-per-page and keyed by frame, so a reset would tear down every sibling
root's state, and what survives an unmount is exactly what a residue gate
reads — a teardown that emptied the tables first would answer zero whether it
released anything or not. It does not remove the container, which is the
caller's node. `release!` does both and empties the runtime; it is the fixture
door, and it is off the facade for that reason (naming-ledger row 13).

## The collector — cells, entries, the commit basis and its repairs

The mechanism record for `impl/collector.cljs`, written 2026-08-30 when that
file's docstrings were trimmed to the contract (rf2-76hbj, executing the
rf2-6c12m.4 rule). The read surface and the correctness clauses are
[HD-002](decisions.md#hd-002--the-sub-read-mechanism-grouped-default-collector-challenger-scalar-comparator--superseded-2026-07-31)
and the shell's hook budget is
[HD-020](decisions.md#hd-020--v0-host-mechanics-frame-plumbing-hook-ledger-error-boundary-ssr-posture);
the ownership state machine, the allowed edge-diff operation, the laziness
property and the two places the record did not survive the substrate are
discharged on [the dogfood judgement](studio/arm1-lean-react-dogfood-judgement.md)
§1–§3 against [the adjudication](hd-002-adjudication.md), and every
module-level owner named below has its row on [globals.md](product/globals.md).
What this section carries is the reasoning none of those pages holds: why the
commit basis has the shape it has, what the two repairs close, and why the
entry cache, the reapers, the render bracket and the alias entry sit where they
do.

**One namespace, because the pieces are one dependency cycle.** `flush!` has
to know whether a body is running — it must not call React's `onStoreChange`
from inside somebody's render — which is a read of `rstate`; `with-commit` is
`flush!`'s window; `frame-dispatch` is `with-commit` applied to one captured
incarnation's `:dispatch-sync`, memoised per incarnation in `impl.frames`'s
row; and `run-once`, which owns `rstate`, binds that dispatch for the body's
dynamic extent, which is how a lowered callback comes to hold one incarnation's
dispatch for the rest of its life. Cut the chain anywhere and the two halves
require each other, so the chain is one namespace and what left it —
`impl.generation`, `impl.frames`, `impl.roots` — is the set of parts with an
edge in one direction only. The closure closes over the captured bundle and
never over the frame keyword, so a callback lowered under incarnation A calls
A's own door and core's `capture-frame` fence refuses it once A is destroyed,
rather than resolving the address again and writing whoever occupies it now
([the incarnation rule](product/invariants.md#the-callback-and-frame-incarnation-rule-rf2-hic-013)).

**The cell is the whole record.** One cell per unique `[frame-kw query-v]`,
holding the reaction, the stamp (`epoch`) and the reader list; a slot in the
reader list is simultaneously the boundary's edge on the key and its reference
to the cell, so there is no `refs` counter to drift from it, and the forward
edge needs no home because the registration already holds the entry's key set
by reference (the 2026-08-03 amendment under *The shared front half* above).
Cells are plain JS objects and the reader list is a JS array: this is the object
[the heap ladder](studio/reads-per-boundary-heap-ladder.md) prices per unique
key, a `deftype` would add a constructor and a prototype to a structure with no
behaviour, and at the fan-out the table actually sees a `.push` and an
`.indexOf` beat a persistent set and retain one object rather than a container
per membership. Every cell's value-change watch uses one constant keyword: a
watch key need only be unique within the watched reference, and it is, since
there is at most one cell per key and no two cells share a reaction — a minted
keyword per cell bought nothing but a `Keyword`, its name and its qualified
string retained per unique key
([the cold-read page](studio/the-cold-read-mount-term.md), the mint's retirement).

**The commit basis, and the two windows of invariant 5.** `generation/commit-basis`
is the flush generation plus the frame's own physical-install epoch
(`frame/frame-commit-epoch`) plus the registry epoch, three terms because each
sees a movement the other two cannot. The generation moves only through a
committed cell's watch — `flush!` runs from `mark-dirty!`, whose only caller is
the watch `acquire-cell!` installs at commit — so a key nothing holds yet can
move without moving it by one. The install epoch is a counter bumped at both of
the substrate's write chokepoints, not a watch, which is exactly why it answers
*did durable state move?* for a key with no cell. Neither is a registry write,
so the third term is what carries a `reg-sub` landing between render and
commit. Install-counting rather than `=`-counting: a value-equal install still
advances it, one redundant re-render at worst and never a missed one. Invariant
5 has two windows and they are not the same window. *Inside a body*, a commit
landing between two of one render's reads: `render-body` captures the basis
before the body and compares after, re-running the body against the newer
commit, which is invariant-5 preservation as one comparison per boundary
rather than one deref per read — the commit-side re-read HD-002 forbids. It
compares the basis rather than the generation alone because a body that read a
staged key, dispatched, and read again could straddle two commits with the
generation sitting still. *The render→commit gap*, a commit landing after the
body returned and before React runs the passive effect that acquires its
edges: a key no cell holds contributes the live basis to `getSnapshot` and a
cell records the basis it was created at, so a staged key's number is
`basis@render` while the boundary renders and `basis@commit` once the commit
acquires it — equal when nothing moved, different when something did. React
re-reads `getSnapshot` immediately after calling `subscribe`
(`updateStoreInstance` runs as the next passive effect) and compares against
the snapshot that fiber captured at render, so the tear check is per boundary,
costs one number and holds no record of what any read returned. A staged key
that contributed 0 instead would answer the same number before and after the
commit however far its value had moved, and React would see no tear. It is
conservative in the safe direction only — a boundary mounting exactly as an
unrelated install lands re-renders once — and steady state pays nothing, since
a mounted boundary holds a reference to every key it reads and its snapshot
has no staged term. Witnessed in `kernel_commit_owns_cljs_test`
(`a-commit-landing-in-the-render-to-commit-gap-moves-the-snapshot`,
`a-quiet-render-to-commit-gap-moves-the-snapshot-by-nothing`) and its DOM
sibling (`a-write-landing-in-the-render-to-commit-gap-heals-the-boundary`).

**A cell's stamp is a basis reading, floored.** `flush!` re-stamps each dirty
cell rather than incrementing it, so the stamp stays comparable with a staged
key's live reading; it floors the re-stamp at one above the stamp the cell
carried, because across a same-id frame reincarnation the frame term restarts
and the basis alone can land on the number the cell already holds — measured in
Chromium at 3 → 3, the notification delivered and ignored, the predecessor's
value left on screen. The floor can only raise a stamp, so the sum stays
monotone. The ruling and the paint-order witness are on
[invariants.md](product/invariants.md#the-callback-and-frame-incarnation-rule-rf2-hic-013).
The generation term is load-bearing across that same reincarnation read from
the staged side (rf2-6c12m.19): the frame term ties, the staged key moved no
watch, and what bumps the generation is the microtask rewire of any other cell
the frame holds — `staged_reincarnation_basis_cljs_test` shows the boundary
corrected with the term and frozen without it. A frame holding no other cell
ties either way; that axis is Spec 006 invariant 5's `:node-key` axis, not the
basis's.

**The other two axes, and which half of each the basis carries.** A `:sub`
registration and a same-id reincarnation split by whether the boundary already
holds a cell for the key, and the halves want opposite answers. For a *held*
cell, adding a term closes nothing: each transition leaves the cell holding a
reaction that can no longer answer for its key, so a moved number would buy one
extra render that read back through the same dead reference. A re-registration
evicts the sub-cache entry and disposes the reaction, and frame destruction
disposes the frame's cached reactions — in both the container's `-dispose` has
already run `(reset! watchers {})`, so the watch this runtime installed is gone
and `mark-dirty!` can never fire for that key again; measured before the hook
existed, the boundary read the retired computation forever. A *first*
registration disposes nothing: the substrate deliberately does not cache its
`:rf.error/no-such-sub` nil-recovery so that the next `subscribe` observes the
handler, and this runtime's one property that breaks that assumption — a cell
holds its reaction for the life of every reader and never subscribes again —
cached the recovery anyway, in a cell, where nothing evicted it. So the runtime
takes the substrate's own events rather than a term: `wire-cell!` arms
`invalidate-cell!` per unique key against the reaction's disposal, which covers
the two transitions that dispose, and `first-registration!` hangs the same
repair off `registrar/add-registration-hook!` — the public sibling of the
replacement hook the eviction rides, which fires only when a previous handler
existed — narrowed to first-time `:sub` registrations and to the cells holding
the id being registered. It costs no React hook and no per-boundary object;
first-time registrations are namespace-load and lazy-module-load events, and at
namespace load there are no cells to scan. For a key *inside the gap* there is
no cell and no dead reference: the commit acquires against whatever is live
then, and one extra render is exactly the repair, which is what the basis's
registry term buys. **The rejected placement is the other one**: a registry
term in every key's live contribution to `getSnapshot`, which moves every
mounted boundary in the application on every `reg-sub`. In the basis, which
`make-snapshot` reads live for staged keys only, an unrelated registration
moves no mounted boundary's snapshot — `hmr_registry_cljs_test`'s
`an-unrelated-namespaces-save-disturbs-no-mounted-boundary` and
`one-save-is-invisible-to-a-mounted-boundary-and-visible-to-an-in-flight-one`
are the rows that distinguish the two placements, and `first_registration_cljs_test`
carries the held-cell half. The epoch is bumped *before* the scan, because the
scan's synchronous phase drops reaction references and a render racing it must
not see an epoch from before the registration it is about to read against.

**The repair has two phases, and the deferral is a microtask by ruling.**
Synchronously `invalidate-cell!` drops the reaction reference, which is all a
correct read needs: `read-key!` treats a cell with no reaction as it treats a
key with no cell and takes the cold probe, so every render from that instant
computes against the live registration and the live frame. At the microtask
checkpoint the attachment is rebuilt and the cell re-stamped and notified —
deferred because the callback runs inside the registrar's replacement hook, its
registration hook and frame teardown, none of which is a place to subscribe;
rewiring in-stack re-enters all three and was measured red. A frame that did not
come back has nothing to rebuild against and the cell is disposed instead. The
deferral is a microtask and not `setTimeout 0` because
[design law React 3](product/lanes/design-laws.md#react-and-ownership) requires
a render/commit tear corrected before visible paint and the re-stamp is that
correction: a later task lets the event loop update the rendering in between,
and on a tenant switch the predecessor's value on screen is another tenant's
data. The microtask checkpoint drains before the same task's rendering update,
including microtasks queued while draining — which is how React's own
sync-lane flush for the notification gets in. The cost of that narrowing is
that a successor seated in a *later* task finds the cell disposed, and recovers
through the probe on its next render exactly as a key that never had a cell
does (`reincarnation_cells_cljs_test`, and the ruling rf2-2l17 on
[invariants.md](product/invariants.md#the-callback-and-frame-incarnation-rule-rf2-hic-013)).
`acquire-cell!` is the second writer of the same attachment: between the
synchronous drop and the deferred rewire the table holds a cell with no reaction
and no watch, and a reader attached to it then would be unreachable by
`mark-dirty!` — measured, a boundary mounted in that window painted once and
did not move on a same-turn write — so the acquire wires a reaction-less cell it
reuses. The `(nil? (.-reaction cell))` guard on both writers is what makes them
idempotent with respect to each other: wiring a cell that already holds a
reaction would add a second `add-on-dispose!` hook, and the next disposal would
invalidate twice, wire twice and compound.

**Wiring is activate, then watch, then observe, with one baseline deref.**
Under the ratom family a subscription is a bare `Reaction` built without
`:auto-run`, which learns its sources only through `deref-capture`; a plain
deref outside `*ratom-context*` leaves it watching nothing, so the watch never
fires and the runtime paints once and goes deaf. `interop/activate-derived-value!`
is the substrate's own op for that and a routed no-op on the React-hook spine;
it runs before the watch so the activating run cannot fan a priming
notification, and before the baseline deref so that deref reads a settled node
([substrate-decision.md](product/substrate-decision.md), the ratom-only line).
The baseline deref exists because *acquire without deref* is not implementable
against this substrate: a derived value starts at an `unset` baseline that is
never `rf=` a real value, and the render's own read went through the probe,
which built no reaction, so a freshly acquired reaction would report movement on
the first later commit whatever it did — every newly mounted boundary
re-rendering once for nothing. One compute per new unique key, on a path that
has to compute anyway, never again for the life of the cell, and not the
forbidden per-read commit re-read ([the dogfood judgement](studio/arm1-lean-react-dogfood-judgement.md)
§3.1). The movement test is made here rather than trusted to the layer below
because this runtime uses the notification itself as the dirty signal; the
shipping spine can tolerate a notification that did not move, since
`useSyncExternalStore` re-compares snapshots after it, and this runtime cannot.

**The read-set entry cache, and why its bucket hashes the whole sequence.** An
entry is the cached `subscribe`/`getSnapshot` pair for one read sequence: a
hit allocates nothing and keeps `subscribe`'s identity, so React does not
re-subscribe and the commit does no work at all; a miss materialises the key
array, the key set and the two closures once, for every boundary that will ever
read that set. The bucket a sequence belongs to is an order-sensitive hash of
the whole sequence, and it *selects* — `entry-matches?` still compares every
key pairwise before an entry is reused. That division is the safety argument:
a hash instead of the compare could hand back an entry for a different read
set, a silently missing edge, while a hash in front of the compare can only send
two sequences to one bucket, where the compare rejects one and the caller mints
a second entry. False negatives only, in both directions, and the ordered
compare is itself a false-negative device (same set, different order, a second
entry and a replacement of a set with itself). Hashing costs nothing
measurable — every sub-key on the scratch was hashed this render when
`read-key!` looked it up in `!cells`. Bucketing on the first sub-key instead
makes the scan's cost a function of how an author orders their `let`
bindings: a row body reading its per-row key first puts one entry per bucket,
and the same body reading a page-wide key first — one line moved — puts every
live row's entry in one bucket, where every probe passes the length test and
the index-0 test and fails only at the last key, so mounting N rows costs
`sum(i)` probes; at the N = 300 rung that is the same page, the same edges and
about 150× the entry-lookup work (rf2-2rtt6.46). The same entry cache serves
`re-frame.hicasso.native/use-sub`: a hook is a real React component and not a
boundary — no shell ran, `rstate` names no frame, the scratch holds somebody
else's reads or none — so it takes `hook-entry` and `hook-read`, doors onto
this module's tables and never a second copy of them. A hook and a boundary
reading one key share one entry, one registration shape and one cell, which is
why `re-frame.hicasso.tool`'s rosters see a hook's reads without knowing hooks
exist; the entry's identity is what spares a hook a `useMemo` or `useRef`, and
`hook-read` scopes the cold-probe box to its own call so a later hook read
cannot inherit a snapshot of a world that has moved.

**The reapers: one timer per horizon, and the entry horizon is 4 ms.** A cell
whose last reader leaves gets one macrotask of grace (a keyed reorder that
unmounts and remounts a row within one turn reuses the reaction) and an unclaimed
entry gets `entry-reap-horizon-ms`; neither arms a timer of its own. Each
horizon has one pending queue, an arm pushes onto it and starts a timer only when
none is running, so a cold mount of 300 distinct-read boundaries arms one timer
rather than three hundred (`reaper_coalescing_cljs_test`). What a per-item timer
gave for free the one timer keeps: an item's task is enqueued no earlier than
its own horizon, so a task posted before that horizon — React's passive flush,
which is what claims an entry — runs first; a timer reaps only what it was armed
for and leaves what rode in after it to a timer armed for that, and a timer a
reset or a re-arm has superseded does nothing. The entry horizon is 4 ms rather
than 0 because an entry is minted during the render and claimed during the
commit, and on a root React renders concurrently (`hydrateRoot` is one) a
`setTimeout 0` armed inside the render beats React back to its own passive
flush: the entry is evicted before it is claimed, the next render of the same
sequence misses and hands `useSyncExternalStore` a different `subscribe`, and
React tears the subscription down and rebuilds it, releasing and re-acquiring
every cell. The spine met the same class, and 4 ms was the shortest probed delay
that read 1.00N at N = 1 and N = 300
([the coldmount page](studio/coldmount-double-build-priced.md#the-hand-off-landed--the-same-instrument-re-run-against-shipped-code),
rf2-2rtt6.71); the hydration schedule was measured against it on
[the SSR spike page](studio/ssr-spike-witness.md#x3--reactivity-adopted). A
margin, not a contract: React documents no maximum render-to-subscribe interval,
no caller may rely on it, and a lost race costs a cache miss and a rebuilt
subscription, never a wrong value, because the entry object survives in the
closure that was handed out.

**The cold probe.** A render-phase read of a key with a committed cell is a pure
deref. A key nothing holds is probed: a live sub-cache reaction is reused by
deref alone (single-threaded CLJS is what makes the unguarded deref safe), else
the value is computed pure through `subs/compute-sub-with-memo` against one
frame-state snapshot per body run, memoised in a per-run value map so a repeated
key is a `find`, and a missing or destroyed frame falls back to
`subscribe-once`. No cache entry, no reference, no watch and no disposal
obligation, so an abandoned render leaves the world as it found it; the
shipping spine attacks the same double build with a render-phase escrow, which
is a render-phase ref-count mutation the state machine forbids, and this
runtime moves the read the other way. The memo is per read rather than shared
across the run because the shared one cost more than it saved on the acceptance
shape (2.75 vs 1.42 µs/read,
[the cold-read page](studio/the-cold-read-mount-term.md)). The compute runs
inside `live-frame/call-with-frame-resolution` so an image-loaded frame's lookups
resolve through its own image and a `reg-sub` issued earlier in the same tick is
visible to this very read (`cold_probe_cljs_test`).

**The shell, the fence and the dev-only view attribution.** The shell is
`useContext` and `useSyncExternalStore` with the body between them, legal because
what React fixes is hook order and count and not the position of ordinary code
around them (`hook_budget_cljs_test` counts at React's own dispatcher). The frame
is resolved from the substrate's single context and deliberately not from
`frame/require-current-frame!`'s dynamic-var chain, because a body's dynamic
extent has unwound by the time React renders the component it returned, so the
var tier can only answer for a different render than the one asking — the
finding recorded as §3.2 of [the dogfood judgement](studio/arm1-lean-react-dogfood-judgement.md).
`render-body` is the fence loop with a ceiling of three re-runs: a fourth commit
inside three consecutive body runs is a write loop and fails loudly. In a dev
build the entry's `subscribe` is wrapped per declared view name so the name is
counted where React commits the reference and uncounted where its cleanup
releases it: the roster `re-frame.hicasso.tool` exports claims the *mounted*
views, and only the commit knows that — a render React discards and a view that
has unmounted name nothing, exactly as they hold nothing
([the adjudication](hd-002-adjudication.md#3-the-ownership-state-machine)).
The wrapper is cached per (entry, name) on the entry, so its identity moves
exactly when the entry's does and React re-subscribes on no render it did not
already; `commit-boundary!` hands a harness the same closure, so a harness commit
names the view exactly as React's does.

**`mint-view!`: the memo, and where the `:render` bracket sits.** The
value-equality bail-out and why it is safe when bodies read subscriptions are
[HD-028](decisions.md#hd-028--value-equality-is-the-boundary-default) and the
Memoization section below. Spec 009's `:render` bucket
(`spec/009-Instrumentation.md`, *What gets bracketed*) is the view substrate's,
and the bracket is the component fn rather than `render-body` for a cost that
would survive elision: `render-body` does not know the view's name, and threading
one in would add a parameter passed on every render of every boundary in the
OFF bundle as well as the ON one, where `view-name` is already closed over at the
component fn, so under `:advanced` with `re-frame.performance/enabled?` false
the macro folds to `(shell body-fn js-props)` — byte-for-byte the call that was
there before. Four behaviours fall out of that placement: a memo bail-out emits
nothing, because React consults the comparator above the fn; StrictMode's
double-invoke emits twice, which is what happened; the generation fence emits
once, because the bracket spans the whole retry loop, so the count is one per
render pass and the duration is the wall-clock React paid; and a throwing body
still emits through the macro's `try/finally`. Helpers inlined into a body are
not bracketed — their cost lands inside the enclosing boundary's measure, where
their reads land. The entry is `rf:render:<view-name>` with `view-name` the
string stamped as `displayName`, so a measure name and React DevTools show one
identifier (`bench/hicasso/src/re_frame/bench/hicasso/arm1/render_measure_cljs_test.cljs`
is the OFF half, its `_emit_nightly_` sibling the ON half). The body is kept on
the head under one dev-only own property because a minted head is a React
component whose body is reachable only through the shell, and the test kit's L2
walk mounts nothing; the property folds away with `codec/retain-body!` under
`goog.DEBUG=false` (`view_body_retention_elision_prod_test`).

**The authoring-time alias.** `publish-view-alias!` writes one `:view` registrar
entry per `defview`, dev only, so a keyword an author wrote resolves forward to
the boundary they meant. The id is `(keyword "<ns>" "<sym>")`, byte-identical to
what `rf/reg-view` derives from its own symbol, so one convention answers for both
substrates; the coordinate is stored at the top level of the registration
metadata, where `(rf/handler-meta :view id)` already reads it, and the author's
`:doc` rides along so the registrar's `:rf.warning/missing-doc` does not fire on
a documented view. The head is stored at `:handler-fn`, the one executable slot
every substrate's `:view` entry uses, so `(rf/view id)` answers a boundary the
way it answers a Reagent or a UIx head (rf2-kuky.60). What comes back is
`identical?` to what the `def` bound: `re-frame.views/view-head` returns a
`:view` slot it did not itself build exactly as stored, so nothing composes,
wraps or componentises a boundary that already is a React component. The entry
carried a private `:hicasso/component` and no `:handler-fn` until rf2-kuky.60,
on the reasoning that `rf/view` answers *the registered render fn*; the answer
was that `view-head`'s pass-through makes the ordinary key free, and one lookup
on every substrate is worth more than a contract sentence that was itself stale.
It publishes resolvability, not identity — mounted boundary identity is still
keyed by the read set and unnamed, which is what `re-frame.hicasso.tool` answers
the backward question with. It goes through `registrar/register!` rather than
`rf/reg-view*` for two reasons, either decisive: `reg-view*` always builds a
frame-aware render wrapper under `:handler-fn`, so it cannot mint a metadata-only
entry, and its first step consults the `:adapter/wrap-view` late-bind hook at
registration time, while a `defview` is declared at namespace load, which
routinely precedes `rf/init!`. A plain registration is adapter-neutral by
construction. Re-evaluating a `defview` re-registers the same id and the
registrar replaces the slot, and it reports that replacement correctly with
nothing said out loud: `register!` derives `:rf.registry/handler-replaced`'s
`:different-fn?` from `:handler-fn` by default, which is where the head now
lives. The entry used to need an `:executable-key :hicasso/component` to point
that derivation at its private slot, because nil against nil reports a genuine
swap of one component for another as an idempotent reload — the second defect
rf2-kuky.60 retired, and the reason `registrar/executable-identity` now has no
caller in the tree naming a key (`view_alias_registry_cljs_test`;
the whole door leaves the bundle under `goog.DEBUG=false`,
`error_source_coord_elision_prod_test`).

**Teardown.** `reset-runtime!` is the page-wide fixture door and not root
teardown; every table it empties is one-per-page and keyed by frame, it calls
each sibling's own door for what it does not hold, and it does not touch the
hydration adoption window, which belongs to the root that minted it
([globals.md](product/globals.md), [The root](#the-root-hd-021b--mount-hydration-and-teardown)
above). `bodyRuns` is deliberately not reset by it: witnesses take a delta
across the thing they measure, and a teardown that zeroed the counter would let
a reading taken on the wrong side of a reset look like a reading.

## Memoization (HD-028, amending HD-006)

A value-equality bail-out is the boundary **default**: every minted head carries
one stable internal `React.memo` wrapper (`codec/memoize-boundary!`) comparing
the whole `rfProps` value with CLJS `=`, fail-open on a throw. It stops the
cascade HD-006 assumed boundary placement alone would prevent — a page-chrome
write re-ran all 300 of 300 card boundaries beneath it with every card's props
value-equal; it now re-runs 0. `useContext` and `useSyncExternalStore`
invalidation still outrank it — React tests `checkScheduledUpdateOrContext`
*before* it consults the comparator, so a boundary whose own reads moved
re-renders regardless of what its props compare equal to. There is no public
`:memo false` opt-out in v1; a boundary that wants parent-driven re-runs takes an
explicit changing revision prop instead. Full ruling, prior-art audit and priced
cost: [HD-028](decisions.md#hd-028--value-equality-is-the-boundary-default).

## Code residence (HD-017)

Instrument arms live on branches against the existing tracked bench trees, with
results published to their beads and the studio table. Runtime skeletons stay
disposable (spike branch or the local `ai/` tree) until the surviving arm
graduates into a tracked `implementation/hicasso/` artefact. (HD-017 wrote that
condition as "the P2 ruling graduates exactly one arm"; the 2026-07-31 product
ruling settled *which* arm ahead of P2, and the residence rule is otherwise
unchanged.) The spike-01 index model was library input to the front half, not a
product namespace; since rf2-dabt3 its algebra is discharged directly against
Arm 1's cell table and `front.sub-index` no longer exists.
