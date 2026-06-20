# EP-0026: Image API Simplification

Status: proposal
Type: standards-track

> This EP proposes the next cleanup pass over the EP-0023 image API. It keeps
> the current `rf/image` name while the design is under review, but simplifies
> the public shape to selected namespaces plus inline registrations, replaces
> replacement maps with explicit layering, removes unproven capability
> declarations, and gives image-local registrations the same source-stamped
> authoring quality as the `reg-*` macros. If accepted, the normative homes are
> `spec/API.md`, `spec/001-Registration.md`, `spec/002-Frames.md`, and
> `spec/Conventions.md`.

## Abstract

EP-0023 made image-loaded frames the public model, but the shipped image surface
still exposes several ways to say "use this behavior here": namespace selection,
inline registrations, `:replace`, `:replace-standard`, frame overrides, and
dispatch overrides. This EP proposes one registration-shaped overlay vocabulary
and one deterministic layering rule.

The public image value becomes smaller: select namespaces with scoped
`:select-ns` clauses, define local behavior with `:registrations`, and let
layer order decide intentional shadowing. Source coordinates for inline
registrations are captured by an `rf/image` macro, so the inline path is not a
second-class tooling path.

## Motivation

The current public image API accepts these top-level keys:

```clojure
:id
:include-ns
:exclude-ns
:registrations
:rf.image/requires
:replace
:replace-standard
```

That is too many levers for a pre-alpha API. Most real uses of `:replace` are
not about descriptor provenance coordinates. They are about a programmer saying:
"in this test, story, platform variant, or patch image, use this local
implementation for this registration id."

The same pressure appears at frame and dispatch scope. Tests and stories need to
stub effects, coeffects, resources, interceptors, and sometimes views. Today
that is expressed through a mix of `:registrations`, `:fx-overrides`,
`:interceptor-overrides`, and dispatch metadata. The ideas are related, but the
surface asks the user to learn different names and different shapes.

This is an EP-sized decision because it amends final EP-0023 contracts:
namespace selection, image collision behavior, image source stamping, public
replacement APIs, and the relationship between image, frame, and dispatch
overlays.

## Goals / Non-Goals

Goals:

- replace sibling `:include-ns` / `:exclude-ns` keys with scoped `:select-ns`
  clauses;
- make inline `:registrations` the ordinary override and stubbing surface;
- define deterministic layer precedence across images, frame registrations, and
  dispatch registrations;
- remove `:replace` from the ordinary public image API;
- remove `:rf.image/requires` from the public image API unless a concrete
  non-registration capability use case returns through a later EP;
- make `rf/image` a source-stamping authoring macro for literal inline
  registrations;
- use one inline registration tuple grammar for every registration kind;
- preserve inspection of shadowed registrations so layer dominance is visible to
  tools.

Non-goals:

- do not reopen the EP-0023 decision that frames run resolved image generations;
- do not rename `rf/image` in this EP, though a future `rf/program` naming
  decision is recorded below;
- do not remove ordinary namespace-authored `reg-*` forms;
- do not let broad namespace selection silently hide accidental same-id
  duplicates inside one image selection clause;
- do not make dispatch-level coeffect facts (`:rf.cofx`) into registration
  overrides.

## Relationships

- **EP-0023** established image-loaded frames, `rf/image`, `:include-ns`,
  `:exclude-ns`, `:replace`, `:replace-standard`, and
  `:rf.image/requires`. This EP proposes a simplification amendment.
- **EP-0024** established the unified frame lifecycle and frame-provider
  surface. This EP adds frame-local `:registrations` overlays to the frame
  construction contract if accepted.
- **EP-0017** defines `:rf.cofx` as causal coeffect facts carried on the event
  token. This EP keeps that separate from behavior overrides.
- **EP-0018** defines the single `reg-event` surface assumed by the inline
  registration grammar.
- **EP-0022** defines registered interceptors and the standard interceptor
  protection concerns that make public `:replace-standard` suspect.
- **EP-0007** supplies the one-name-per-fact rule this EP applies to override
  APIs.

## Specification

This section uses normative language so acceptance can graduate into specs with
minimal rewriting. At `proposal` status it records the proposed contract, not a
shipped guarantee. The open issues at the end are graduation blockers: this EP
must not move to `final` until each dispatch-overlay question has an operator
ruling or is explicitly deferred to a follow-on EP.

### Image keys

The ordinary public image spec has these top-level keys:

```clojure
:id
:select-ns
:registrations
```

The following keys are retired from the ordinary public `rf/image` surface:

```clojure
:include-ns
:exclude-ns
:replace
:replace-standard
:rf.image/requires
```

If accepted, public image construction must reject these retired keys with
actionable diagnostics rather than silently ignoring them or treating them as
synonyms.

`:replace-standard` may return only through a later, explicit standards-track
decision that names real replaceable framework standards and their conformance
requirements. Framework-standard registrations are not ordinary product
extension points.

### Namespace selection

Namespace selection uses `:select-ns`, a vector of clauses:

```clojure
(rf/image
  {:id :app/main
   :select-ns [{:include ["app.todo.**"]
                :exclude ["app.todo.dev.**"]}

               {:include ["app.admin.**"]
                :exclude ["app.admin.fixtures.**"]}

               {:include ["app.dev.story.**"]}]})
```

Each clause selects:

```text
matches(clause :include) minus matches(clause :exclude)
```

The image-selected namespaces are the union of all clause results. Exclusions
are local to the include clause that contains them; they are not one global
subtraction set.

`:select-ns` defaults to an empty vector. An image with no `:select-ns` clauses
selects no namespace-authored registrations and may still define inline
`:registrations`. Each clause must contain at least one `:include` pattern;
`:exclude` defaults to an empty vector.

Selecting the same source namespace through more than one clause is idempotent:
the descriptor is considered once. A same `[kind id]` collision between two
different selected descriptors remains a collision inside the image and fails as
described below.

The glob grammar remains the EP-0023 grammar:

- namespace strings are dot-separated;
- `*` matches exactly one segment;
- `**` matches zero or more segments;
- matching is case-sensitive;
- selection is by source namespace provenance, not by registration id namespace.

An include pattern that matches no registration source namespace fails image
assembly with an actionable diagnostic. An exclude pattern that matches nothing
is allowed.

### Layered resolution

Image composition is ordered data. A frame created with:

```clojure
(rf/make-frame
  {:images [base-image product-image story-image]})
```

builds a resolved generation using this precedence:

```text
dispatch-local registration overlay
  dominates frame registration overlay
  dominate image inline registrations, later image wins inside the tier
  dominate image namespace-selected registrations, later image wins inside the tier
```

These are resolution layers, not mutations of the global registrar, the image
value, or the frame's recorded image composition. Each layer contributes
descriptor values to the resolved-generation calculation for its scope.

Within one image:

- inline `:registrations` dominate registrations selected by `:select-ns`;
- duplicate inline registrations for the same `[kind id]` fail loud;
- duplicate namespace-selected registrations for the same `[kind id]` fail loud
  unless they are the implementation's ordinary same-source hot-reload
  replacement case.

Across images, later images intentionally shadow earlier images for the same
`[kind id]`. Shadowing is not ambient namespace load order; it is the explicit
order of the `:images` vector.

Assemblers must retain enough provenance to show which descriptors were
shadowed, which layer won, and why. Layer dominance must be inspectable by Xray,
Pair, error reporters, and other tooling rather than disappearing during
resolution. This is a deliberate amendment to EP-0023's exact replacement maps:
the API gains a smaller data shape, and gives up the old coordinate-level winner
declaration. The mitigation is that order is explicit in `:images`, collisions
inside one selection clause still fail loud, and shadowed descriptors remain
visible to diagnostics and tools.

### Inline registration grammar

Every inline registration entry uses one of two tuple shapes:

```clojure
[id body]
[id metadata body]
```

The metadata map is optional. Metadata-only entries are not part of the public
inline grammar; `[id metadata]` is invalid. For handler-style registrations,
`[id map]` is a missing-body error, not a map body, unless a future
registration kind explicitly defines a map body parser in its own spec.

The registration map may contain these keys:

```clojure
:reg-event
:reg-sub
:reg-fx
:reg-cofx
:reg-interceptor
:reg-view
:reg-frame
:reg-route
:reg-head
:reg-error-projector
:reg-flow
:reg-resource
:reg-mutation
:reg-resource-scope
```

Unsupported keys fail loudly. Future registration kinds extend this list through
their own specs or the central registration spec.

Example:

```clojure
(rf/image
  {:id :quickstart/counter
   :registrations
   {:reg-event [[:counter/inc
                 (fn [{:keys [db]} _]
                   {:db (update db :counter/value inc)})]]
    :reg-sub   [[:counter/value
                 (fn [db _]
                   (:counter/value db))]]}})
```

The omitted metadata maps normalize to `{}`.

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

### Registration overlays at image, frame, and dispatch scope

Behavior stubs and overrides use the same `:registrations` shape at each scope.
The scope decides lifetime:

```clojure
;; image-level: part of the composed image value
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
event handler itself may be too magical. Until that ruling lands, image-level
and frame-level overlays are the determinate part of this EP; dispatch-level
overlays are accepted only if the final ruling records their allowed kind set
and inheritance behavior.

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

### Capability declarations

`:rf.image/requires` is removed from the public image API.

No convincing current use case requires image-level capabilities instead of
ordinary registration resolution, frame configuration, or host adapter setup. If
a future host dependency cannot be modeled cleanly by registrations and frame
configuration, it can return through a specific EP with concrete examples.

### Program vocabulary boundary

This EP deliberately keeps `rf/image`. EP-0023 uses `program` for the event
stream executed by a frame, and warns against using `program` for registration
sets. The distinction remains useful here:

```text
image        = selected registration vocabulary loaded by a frame
frame        = live execution context over state and runtime partitions
event stream = the program executed by that frame
```

Any future rename from `rf/image` would need to explicitly revise that EP-0023
vocabulary. This proposal does not do so.

## Rationale

The Clojure shape should be data first. The image says what behavior it selects
and what local behavior it defines. The frame says which images and frame-local
overlays it runs. The dispatch envelope says what one causal run locally
observes or overrides.

The old `:replace` shape is precise, but it forces application authors to speak
in descriptor coordinates. Most of the time, the intent is simpler:

```text
Use this registration here.
```

Inline `:registrations` already say that directly. Making layer order explicit
preserves determinism without teaching a second winner-map API.

Removing `:rf.image/requires` follows the same principle. A public key should
earn its place by naming a real, recurring application fact. So far, capability
declarations look like leftover composition vocabulary rather than a necessary
part of the image/frame model.

## Backwards Compatibility

re-frame2 is pre-alpha. No compatibility shims are required, but this is still a
breaking source migration for any code or documentation already using the
EP-0023 image spellings. The retired keys must fail loudly during assembly so
stale examples do not keep working by accident.

Migration is source-level:

- replace `:include-ns` / `:exclude-ns` with `:select-ns` clauses;
- replace `:replace` with a later image or inline `:registrations`;
- remove `:rf.image/requires`;
- move frame and dispatch behavior overrides to `:registrations`;
- keep dispatch causal facts in `:rf.cofx`.

## Bead Plan / Reference Implementation

Expected implementation slices:

1. Add the `:select-ns` clause parser and diagnostics while keeping the EP-0023
   glob grammar.
2. Implement layered image resolution with shadow provenance reporting.
3. Remove public `:replace` and ordinary public `:replace-standard`; protect
   framework-standard registrations behind an internal or future-EP path.
4. Remove `:rf.image/requires` from image assembly, spec, examples, and guide
   text.
5. Convert `rf/image` into a source-stamping macro and factor the runtime
   constructor as needed.
6. Normalize inline registration tuple parsing to `[id body]` and
   `[id metadata body]` for all registration kinds; reject `[id metadata]`.
7. Add frame-local `:registrations` overlays to frame construction.
8. Decide and implement the dispatch-level allowed kind set, child-dispatch
   inheritance rule, and source-stamping story.
9. Update Xray, Pair, examples, tools, migration skill, and guide chapters that
   teach image assembly or stubbing.
10. Add conformance tests for retired-key rejection, scoped namespace
    selection, duplicate detection, cross-image shadow provenance, source
    stamping, and frame/dispatch overlay precedence after the dispatch ruling.

Guide-impact assessment:

- `docs/guide/quickstart.md` can teach `rf/image` inline registrations without
  descriptor replacement maps.
- frame/story/testing guide material can teach one stubbing vocabulary:
  `:registrations` for behavior, `:rf.cofx` for causal facts.
- image composition docs should show layer dominance and shadow inspection.

## Open Issues

These issues require recorded dispositions before this standards-track EP can
graduate. If any answer is deferred, the final EP should narrow its normative
surface and name the follow-on EP or bead that owns the deferred question.

1. **Should dispatch-level `:registrations` allow every registration kind?**
   The recommended default is conservative: allow the kinds genuinely consulted
   inside a cascade (`:reg-fx`, `:reg-cofx`, `:reg-interceptor`, and any
   resource/mutation kinds used by managed effects), and reject overriding the
   triggering event handler. Operator ruling needed.

2. **Do dispatch-level registration overlays inherit to child dispatches?**
   Existing effect overrides may have inheritance behavior. This EP needs one
   explicit rule.

3. **Does `:interceptor-overrides` have a remaining use as reference rewriting
   or interceptor removal rather than registration replacement?** If yes, it
   should be named narrowly and kept out of the general override vocabulary.

4. **Is `rf/program` the final public name?** This EP records the naming
   preference but does not decide it.

## Recommendation

Accept the simplification direction, then resolve the dispatch-overlay open
issues before marking the EP final. The cleaned-up model is smaller and more
data-oriented: namespace selection imports behavior, `:registrations` defines
local behavior, explicit layer order decides intentional shadowing, and
`:rf.cofx` remains the causal input channel.
