# Frame Object / Record Unification

Status: draft finding — **new in the 2026-06-18 fresh consolidation pass**.
Highest-leverage structural finding, and the most contentious (it argues the
graduated EP-0023 implementation realized the collapse *additively*). Needs
validation against the EP-0023 implementers' intent before action.

This file is the structural sibling of
[frame-targeting-and-lifecycle.md](frame-targeting-and-lifecycle.md). That file
treats the crowding as an **addressing-spelling** problem ("do I pass the id or
the object?"). This file makes the deeper claim underneath it: there are
**two frame constructors backed by two registries**, and most of the addressing
crowding, the normalization seams, and several guard mechanisms are glue holding
those two halves together. Fix the backing structure and much of the
spelling-level crowding dissolves with it.

## Crowding Signal

EP-0023 promised to collapse the public model to `image -> frame -> event
stream`, with `make-frame` returning the one thing that "owns app-db,
runtime-db, queue, sub-cache, lifecycle, AND a reference to the resolved image
generation" (EP-0023:300-315). The implementation instead grew a **second**
frame type, a **second** registry, and a **second** creation path layered over
the retained EP-0013 record — then invented normalization seams, dual-address
guards, and a "create twice" idiom to keep the two coherent. At the code level
the object does **not** own app-db; the record does. The object owns a
*pointer* (`:rf.frame/runnable-id`) to the record.

## Implementation evidence (verified)

- Two `make-frame`:
  - `implementation/core/src/re_frame/live_frame.cljc:469-587` — the object
    constructor. Its body calls `frame/reg-frame runnable-id {}` (≈line 580) to
    mint the backing EP-0013 record, then `register-live-frame!` (≈line 570)
    into a **separate** `live-frames` atom (≈line 172).
  - `implementation/core/src/re_frame/frame.cljc:1499-1505` — the other
    `make-frame`: "Anonymous-instance creation. Generates a gensym'd id …
    Returns the gensym'd id."
- Two registries: `live-frame/live-frames` vs `frame/frames`. The object
  docstring concedes the split (`live_frame.cljc:86-95`): "DELIBERATELY SEPARATE
  from `re-frame.frame`'s EP-0013 `(realm, frame)`-addressed `frames` registry."
- The object is a pointer, not the owner: every per-frame subsystem "keys
  per-frame state through a frame-id ADDRESS keyed into `frames`"
  (`frame.cljc:99-101`). `frame-target->id` (`frame.cljc:125-136`) strips the
  object back to that id at every public entry point
  (`core.cljc:2009/2024/2040`, dispatch via `build-envelope` `core.cljc:995`,
  `destroy-frame!` `frame.cljc:2081`), and `object-marker` is **duplicated** in
  `frame.cljc:108` and `live_frame.cljc:246` to dodge a require cycle.
- The create-twice idiom: to build an image-frame that *also* carries config
  (`:preset`/`:fx-overrides`/classification) a caller must invoke **both**
  constructors on the same id in a load-bearing order —
  `tools/story/src/re_frame/story/frames.cljc:629-642` does exactly this, with a
  comment warning that reversing the order silently clobbers the Story config
  back to empty. The facade hardcodes the split with `make-frame-opt-keys` +
  `assert-make-frame-opts!` → `:rf.error/make-frame-record-only-key`
  (`core.cljc:814-848`), a guard whose whole job is to reject keys the *other*
  `make-frame` would accept.
- Two opposite duplicate-id policies for one concept: `reg-frame` silently
  surgical-updates an existing id (`frame.cljc:1481`, and `make-frame` *relies*
  on this not clobbering, `live_frame.cljc:574-580`), while the object
  `make-frame` fails loud on a duplicate id (`live_frame.cljc:386-419`,
  `:rf.error/live-frame-id-conflict`).
- Teardown is split across both registries: `destroy-frame!`
  (`frame.cljc:2136-2230`) fires ~10 late-bound subsystem cleanups PLUS a realm
  host-transient inventory walk PLUS a separate `:live-frame/forget!` hook
  (`frame.cljc:2227`) purely to drop the EP-0023 registry entry the EP-0013
  record doesn't know about.
- The reprojection re-entrancy guard exists because `make-frame` secretly fires
  `reg-frame`: `reproject-live-frames!` / `mark-dirty-and-schedule!`
  (`live_frame.cljc:803-1016`) carries a `reprojecting?` guard
  (`:891-902`) and a no-live-frame skip (`:964-974`) added specifically because
  every object-frame creation now trips the `reg-*` reprojection hook. ~100
  lines of machinery defending the framework against tripping its own hot-reload
  hook.

## Observed use cases

1. EP-0023 image-loaded frames (tests, Story variants, SSR) want the object.
2. `reg-frame`'s `:on-create`/`:preset`/`:fx-overrides` config surface and
   gensym anonymous frames want the record.
3. Story variant frames need **both** at once → the create-twice idiom.
4. Hot reload needs `reg-frame` idempotency (preserve app-db on re-eval).
5. Empirical adoption note: examples converge on `{:frame f}` ids + `make-frame`
   for tests; no example holds a record vs object distinction consciously — the
   fork is framework-internal, paid by the framework, not chosen by users.

## Proposed smaller API

Fold the image generation onto the EP-0013 record and let there be **one
constructor over one registry**:

- add one `:generation` slot to `new-frame-record` (`frame.cljc:1277`);
- make `make-frame` *be* `reg-frame` with an `:images`-resolution front-end,
  accepting `:images` **and** the record-config keys in one call;
- the "frame object" becomes the record itself (or a frozen handle over it), so
  `frame-object?` / `runnable-id` / `live-frames` / `frame-target->id` all
  disappear;
- one duplicate-id policy (pick `reg-frame`'s idempotent surgical-update for hot
  reload, documented in Spec 002 §reg-frame is atomic), removing the
  `make-frame` vs `reg-frame` contradiction.

What that one move dissolves or shrinks:

- the create-twice idiom + its ORDER footgun comment (Story collapses to one
  `make-frame` call);
- `assert-make-frame-opts!` + `make-frame-opt-keys` +
  `:rf.error/make-frame-record-only-key` (no rival constructor to guard
  against);
- `frame-target->id` + the duplicated `object-marker` (no object/id round-trip;
  dispatch/subscribe deref the record directly);
- `:live-frame/forget!` (destroy dissocs one record from one registry);
- the `reprojecting?` re-entrancy guard + the CI-hang no-live-frame skip
  rationale (creation no longer trips the reprojection hook);
- one home for "the set of live frames," so `live-frame`/`live-frame-ids`/
  `forget-live-frame!` become derived reads over `frames`.

This finishes, at the code level, what EP-0023 set out to do at the model level.
It also lets the spelling-level cleanups in the sibling files land cleanly:
`frame-provider` target unification (frame-targeting finding), `:realm` removal
from public reads (registrar-addressing finding), and one resolve-once seam for
the value reads (frame-state finding).

## Vocabulary coherence (fold-in from the model-coherence pass)

EP-0023's own new vocabulary already shows an EP-0007 one-name-per-fact wobble
that this work should settle while the surface is young:

- "image generation" / "resolved image generation" / "sealed image generation"
  are used interchangeably for one fact (EP-0023:44 vs :93; the facade accessor
  `frame-generation` returns the sealed set with `:rf.gen/*` keys). Pick **one**
  canonical noun — "sealed image generation" matches the keys and the accessor.
- "frame object" / "live frame" name one thing (EP-0023:105, :114). Use
  **"frame"** for the live context; qualify only where contrasting with the
  serializable "frame-state value." If the unification above lands, "frame
  object" stops being a distinct concept anyway.

## Classification

**EP-level (or an EP-0023 erratum/amendment).** This questions a graduated
implementation's internal structure, not just docs or facade tiering, so it
needs explicit owner adjudication: is the two-constructor/two-registry split a
deliberate transitional state with a planned convergence, or unrealized collapse
debt? If deliberate-and-temporary, record the convergence as a tracked
post-graduation slice. If not, this is the single highest-leverage frame
simplification on the board.

Caution for whoever picks this up: the claims above are read off the source as
of 2026-06-18 and are strong; verify against the EP-0023 implementers' intent
(some seams — e.g. the require-cycle `object-marker` mirror — may be load-bearing
for reasons not visible in the code) before scheduling the refactor.

## Why this is better

EP-0023's mental model is one frame object that owns everything including its
generation. The code's model is a generation-carrying pointer to a record that
owns the rest, plus a second registry and the glue to keep them in step. Two
registries for "the set of live frames" is the definition of sprawl; the spec's
own model wants one. Realizing the spec in code removes more public-surface and
internal complexity per line changed than any other frame finding in this
review.

## Implementation

- **Vehicle: EP - specifically an EP-0023 amendment/erratum.** It changes the
  graduated EP-0023 implementation's backing structure (one constructor, one
  registry, generation-on-record). **Needs explicit owner adjudication first:** is
  the two-layer split deliberate-transitional (with a planned convergence) or
  unrealized-collapse debt? Record the answer before scheduling.
- **Co-located with [frame-targeting-and-lifecycle.md](frame-targeting-and-lifecycle.md)**
  in one EP: the unification is the structural core; the targeting / provider /
  spelling cleanup is its surface. It also gates the smaller frame beads that
  assume the unified model (provider object acceptance, one resolve-once
  value-read seam).
- **Pre-alpha makes "collapse the layers" the mandated path** - keeping the
  EP-0013 record alongside the EP-0023 object is exactly the accretion the
  posture rejects (see facade-accretion-and-removal.md). The only caveat is the
  owner-intent check above (some seams may be load-bearing for non-obvious
  reasons).
- Sequence: adjudicate intent -> EP -> implementation slice. Hot-zone:
  `core.cljc`, `frame.cljc`, `live_frame.cljc`, `tools/story/.../frames.cljc`,
  `spec/002-Frames.md`.
