# EP-0028: Registration Overlays and Inline Image Authoring

Status: deferred
Type: standards-track

> **Deferred 2026-06-24** (operator ruling): parked pending completion of the document. The override/overlay vocabulary and the `rf/image` source-stamping macro this EP carries are its contested, replay-sensitive surfaces; they wait here, kept per EP-0009, until the design is settled.

> This EP carries the override/overlay vocabulary and the `rf/image`
> source-stamping authoring macro that were split out of EP-0026 on 2026-06-22.
> EP-0026 retains the determinate image-surface simplification (`:select-ns`,
> dropping `:replace` / `:replace-standard`, removing `:rf.image/requires`); this
> EP owns the contested, replay-sensitive surfaces: behaviour overrides at frame
> and dispatch scope, and making `rf/image` a source-stamping macro for literal
> inline registrations. Both EPs remain `proposal`. If accepted, the normative
> homes are `spec/002-Frames.md` (frame/dispatch override precedence and child
> inheritance), `spec/API.md`, and `spec/Conventions.md`.

## Abstract

EP-0023 ships image-scope inline `:registrations` and dispatch-time
`:fx-overrides` / `:interceptor-overrides`. This EP proposes (a) one
registration-shaped overlay vocabulary reused at frame and dispatch scope for
behaviour stubs, and (b) turning `rf/image` into an authoring macro so literal
inline registrations carry the same source coordinates as namespace-authored
`reg-*` forms.

Both are separable from EP-0026's image-surface simplification and carry real,
unresolved design risk — most sharply, dispatch-scoped behaviour overrides
interact with re-frame2's replay model. They are gathered here so EP-0026 can
graduate without waiting on them.

## Motivation

Tests and stories routinely need to stub behaviour: a fake HTTP effect, a
recording effect, a swapped interceptor. Today this is expressed at image scope
(inline `:registrations`) and at dispatch/frame scope (`:fx-overrides`,
`:interceptor-overrides`, `with-fx-overrides`). The shapes differ across scopes.
This EP asks whether one `:registrations`-shaped overlay vocabulary should span
image, frame, and dispatch scope, and under what lifetime and precedence rules.

Separately, literal inline registrations authored inside an `rf/image` value
today carry image/inline provenance but no source coordinates, so they are a
second-class tooling path compared with `reg-*` forms. This EP asks whether
`rf/image` should become a source-stamping macro to close that gap.

Both questions amend final EP-0023 contracts (the image source-stamping
guarantees, and the relationship between image, frame, and dispatch overlays) and
carry unresolved alternatives, so they warrant an EP rather than a bead.

## Goals / Non-Goals

Goals:

- decide whether behaviour overrides at frame and dispatch scope use one
  `:registrations`-shaped vocabulary, and under what lifetime/precedence rules;
- decide the dispatch-overlay allowed kind set, child-dispatch inheritance, and
  replay-recording story;
- decide whether `rf/image` becomes a source-stamping authoring macro, and the
  exact coordinate sinks and production-elision rules if so;
- keep behaviour overrides strictly separate from `:rf.cofx` causal facts.

Non-goals:

- do not respecify image-scope selection, collision, or capability behaviour —
  that is EP-0026;
- do not make dispatch-level coeffect facts (`:rf.cofx`) into registration
  overrides;
- do not retire the shipped `:fx-overrides` / `:interceptor-overrides` /
  `with-fx-overrides` surfaces until a replacement is proven to preserve their
  replay and lexical-scope properties.

## Relationships

- **EP-0026** — split-from sibling; provides the image-surface simplification
  this builds on. The two are siblings carved from one draft; neither supersedes
  the other.
- **EP-0023** — established `rf/image` (a plain function returning inert data),
  image-scope inline `:registrations`, and the resolved image generation.
- **EP-0022** — defines registered interceptors; `:interceptor-overrides` are
  exact-reference substitution/removal, not registration replacement.
- **EP-0017** — defines `:rf.cofx` causal facts and the recordable-coeffect
  replay model that dispatch-scoped behaviour overrides must not break.
- **EP-0018** — defines the single `reg-event` surface assumed by the inline
  registration grammar.
- **EP-0007** — one-name-per-fact; the masking-vs-definition naming question
  below.

## Specification

> At `proposal` status this records the proposed contract, not a shipped
> guarantee. The open issues at the end are graduation blockers.

### Layered resolution — overlay tiers

EP-0026 defines image-tier resolution (inline `:registrations` over `:select-ns`
selections, later image wins). This EP adds two overlay tiers above the image
tiers:

```text
1. dispatch overlay              (highest — masks everything below, this cascade only)
2. frame overlay
   —— image tiers (EP-0026) ——
3. image inline :registrations   (later image in :images wins within this tier)
4. image :select-ns selections   (later image in :images wins within this tier)
```

These are resolution layers, not mutations of the global registrar, the image
value, or the frame's recorded image composition. Each layer contributes
descriptor values to the resolved-generation calculation for its scope.

### `rf/image` is an authoring macro

The public `rf/image` form is a macro for literal image specs. It must do for
inline image entries what `reg-event`, `reg-sub`, `reg-view`, and other
`reg-*` macros do for namespace-authored registrations:

- capture source namespace, file, line, and column where available;
- capture handler/source text where the corresponding `reg-*` macro captures it;
- preserve production elision behavior for source-heavy metadata;
- preserve image provenance with `:rf.provenance/image` and
  `:rf.provenance/inline`;
- return an inert image value rather than registering behavior globally.

The runtime constructor may be factored as `rf/image*` or an internal function.
The public contract is that literal inline registrations authored with
`rf/image` are first-class in errors, Xray, Pair, source jumping, and handler
introspection.

If the macro receives a non-literal spec that cannot be walked at compile time,
it may fall back to the runtime constructor, but source-stamping guarantees then
apply only to the literal parts it can inspect.

### Registration overlays at frame and dispatch scope

Image-scope inline `:registrations` are defined in EP-0026. This EP adds
behaviour overrides at frame and dispatch scope using the same `:registrations`
shape. The scope decides lifetime:

```clojure
;; image-level: part of the composed image value (EP-0026)
(rf/image
  {:id :checkout/story-overrides
   :registrations
   {:reg-fx [[:checkout.http/post story-http-post]]}})

;; frame-level: local to this frame for its lifetime
(rf/make-frame
  {:id :checkout/story
   :images [checkout-image]
   :registrations
   {:reg-fx [[:checkout.http/post story-http-post]]}})

;; dispatch-level: local to this dispatch/cascade
(rf/dispatch-sync
  [:checkout/submit]
  {:frame :checkout/story
   :registrations
   {:reg-fx [[:checkout.http/post recording-http-post]]}})
```

Conceptually:

```text
image :registrations
  = inline local definitions in the image

frame :registrations
  = a frame-owned overlay in the frame's resolved generation

dispatch :registrations
  = a cascade-local overlay owned by the dispatch envelope
```

Frame-level `:registrations` use the same tuple grammar as image-level
registrations. Dispatch-level `:registrations` use the same syntactic shape, but
the exact allowed kind set is an open issue because overriding the triggering
event handler itself may be too magical. Until that ruling lands, frame-level
overlays are the more determinate part of this EP; dispatch-level overlays are
accepted only if the final ruling records their allowed kind set, inheritance
behaviour, and replay-recording story (Open Issues 1, 2, 6).

Dispatch-level overlays are part of one event cascade's resolution context. They
must not update the frame's recorded construction data, must not rewrite the
image value, and must not affect unrelated cascades. If child dispatches inherit
the overlay, that inheritance must be specified explicitly rather than falling
out of queue implementation details.

### `:rf.cofx` is not a registration overlay

`:rf.cofx` remains a causal input fact channel on the event token:

```clojure
(rf/dispatch-sync
  [:todo/add "milk"]
  {:frame :todo/test
   :rf.cofx {:rf/time-ms 1781078400123}})
```

It can be used as a test or story stub, but it stubs a different layer:

```text
:registrations  stubs behavior
:rf.cofx        stubs causal input facts
```

The first changes what the frame can resolve. The second records what fact this
event observed.

## Rationale

Frame and dispatch overrides are masking, not definition. Image `:registrations`
are part of an inert composable value and participate in collision reasoning; a
dispatch overlay is a throwaway behaviour swap that lives for one cascade. Giving
both the same spelling is convenient but may conflate two facts (Open Issue 4).
Keeping `:rf.cofx` strictly separate from `:registrations` is the cleanest
data/behaviour cut: `:rf.cofx` records what fact an event observed; overlays
change what the frame can resolve.

The `rf/image` macro question trades a clean value-returning function for
compile-time source capture on the inline path. That benefit is real for tooling
but lands on the minority authoring path while making programmatic image
construction (`map`, threading, generated specs) second-class (Open Issue 5).

## Backwards Compatibility

re-frame2 is pre-alpha. The shipped `:fx-overrides` / `:interceptor-overrides`
and `with-fx-overrides` surfaces remain until any replacement is proven to
preserve their replay-safety and lexical scoping. `rf/image` remains a plain
function unless the macro decision (Open Issue 5) is ruled in.

## Bead Plan / Reference Implementation

Expected implementation slices, each gated on the relevant open-issue ruling:

1. Add frame-local `:registrations` overlays to frame construction, with a
   defined generation-identity / cache / reload / introspection story.
2. Decide and implement the dispatch-level allowed kind set, child-dispatch
   inheritance rule, and replay-recording — or forbid dispatch behaviour overlays.
3. Convert `rf/image` into a source-stamping macro and factor the runtime
   constructor as needed — or adopt opt-in literal sugar instead.
4. Define the public shadow/layer introspection shape consumed by Xray / Pair.
5. Update guide/testing chapters that teach stubbing.

## Open Issues

These require recorded dispositions before this standards-track EP can graduate.

1. **Should dispatch-level `:registrations` allow every registration kind?** The
   conservative default: allow the kinds genuinely consulted inside a cascade
   (`:reg-fx`, `:reg-cofx`, `:reg-interceptor`, and any resource/mutation kinds
   used by managed effects), and reject overriding the triggering event handler.
   Operator ruling needed.

2. **Do dispatch-level registration overlays inherit to child dispatches?** The
   shipped `:fx-overrides` / `:interceptor-overrides` have inheritance behaviour;
   this EP needs one explicit rule.

3. **Does `:interceptor-overrides` have a remaining use as exact-reference
   rewriting / interceptor removal rather than registration replacement?** If yes,
   keep it as its own narrow surface, out of the general overlay vocabulary.

4. **Definition vs masking naming.** Image `:registrations` are collision-checked
   definitions; frame/dispatch overlays are masking. Should masking use a distinct
   key (e.g. `:overrides`) so one word does not name two facts (EP-0007 rule 3)?

5. **`rf/image` function vs macro.** Source-stamping needs compile-time capture,
   but macro-fying the whole constructor demotes programmatic construction. Whole
   constructor macro, opt-in literal sugar (`rf/image-literal`), or a
   coords-carrying inline tuple?

6. **Dispatch-overlay replay safety.** A recorded event replayed against the same
   frame generation can resolve to *different* behaviour if the original cascade
   used an unrecorded dispatch overlay — sharpest for `:reg-cofx`, where exact
   observed facts belong on `:rf.cofx`. Either forbid dispatch behaviour overlays,
   or require the active overlay be recorded in the cascade/epoch record so replay
   can reconstitute it. Graduation blocker.

## Recommendation

Resolve the dispatch-overlay open issues (especially replay safety, Open Issue 6)
and the function-vs-macro question before any of this graduates. The likely-safe
ship is frame-scoped overlays (recorded in frame construction, hence replayable)
plus the shipped curated dispatch fx/interceptor surface, with the broader
dispatch registration overlay and the `rf/image` macro deferred or proven first.
</content>
