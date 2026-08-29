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
and 20 (`implementation/hicasso/spec/naming-ledger.md`); what teardown may
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
implementations agreeing (`implementation/hicasso/spec/dispositions.md`,
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
