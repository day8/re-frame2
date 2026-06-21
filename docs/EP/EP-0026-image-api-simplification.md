# EP-0026: Image API Simplification

Status: proposal
Type: standards-track

> This EP proposes the next cleanup pass over the EP-0023 image API. It keeps the
> current `rf/image` name and shape while the design is under review, and
> simplifies the public image surface to selected namespaces plus inline
> registrations, replaces replacement maps with explicit layer order, and removes
> unproven capability declarations. The override/overlay vocabulary (frame- and
> dispatch-scoped behaviour stubs) and the `rf/image` source-stamping authoring
> macro were **split out to EP-0028** on 2026-06-22 so this EP can carry the
> determinate image-surface simplification on its own; both remain `proposal`. If
> accepted, the normative home is primarily `spec/Conventions.md` (the
> `:rf.image/*` reserved-key grammar), with `spec/002-Frames.md` and
> `spec/API.md` updated to match.

## Abstract

EP-0023 made image-loaded frames the public model, but the shipped image surface
still exposes several ways to select and override behaviour: namespace selection,
inline registrations, `:replace`, `:replace-standard`, and capability
declarations. This EP proposes one registration-shaped selection vocabulary and
one deterministic layering rule.

The public image value becomes smaller: select namespaces with scoped
`:select-ns` clauses, define local behaviour with inline `:registrations`, and
let layer order decide intentional shadowing between images.

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

The same pressure appears at frame and dispatch scope — tests and stories need to
stub effects, coeffects, interceptors, and sometimes views beyond what an image
value alone expresses. That override/overlay vocabulary is a separable decision
surface and is carried by **EP-0028**; this EP is limited to the image value
itself.

This is an EP-sized decision because it amends final EP-0023 contracts: namespace
selection, image collision behavior, public replacement APIs, and image
capability declarations.

## Goals / Non-Goals

Goals:

- replace sibling `:include-ns` / `:exclude-ns` keys with scoped `:select-ns`
  clauses;
- keep inline `:registrations` as the ordinary local-definition surface;
- define deterministic layer precedence between images (later image wins);
- remove `:replace` from the ordinary public image API in favour of explicit
  layer order;
- remove `:rf.image/requires` from the public image API unless a concrete
  non-registration capability use case returns through a later EP;
- preserve inspection of shadowed registrations so layer dominance is visible to
  tools.

Non-goals:

- do not reopen the EP-0023 decision that frames run resolved image generations;
- do not rename `rf/image` in this EP, though a future `rf/program` naming
  decision is recorded below;
- do not remove ordinary namespace-authored `reg-*` forms;
- do not let broad namespace selection silently hide accidental same-id
  duplicates inside one image selection clause;
- do not specify frame- or dispatch-scoped behaviour overrides, or the `rf/image`
  source-stamping macro — those are **EP-0028**.

## Relationships

- **EP-0023** established image-loaded frames, `rf/image`, `:include-ns`,
  `:exclude-ns`, `:replace`, `:replace-standard`, and
  `:rf.image/requires`. This EP proposes a simplification amendment.
- **EP-0028** carries the override/overlay vocabulary and the `rf/image`
  authoring-macro decision split out of this EP. The two are siblings carved from
  one draft; neither supersedes the other.
- **EP-0024** established the unified frame lifecycle and frame-provider surface.
- **EP-0022** defines registered interceptors and the standard interceptor
  protection concerns that make public `:replace-standard` suspect.
- **EP-0007** supplies the one-name-per-fact rule this EP applies to the image
  key surface.

## Specification

This section uses normative language so acceptance can graduate into specs with
minimal rewriting. At `proposal` status it records the proposed contract, not a
shipped guarantee. The open issues at the end are graduation blockers: this EP
must not move to `final` until each has an operator ruling or is explicitly
deferred to a follow-on EP.

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

The image-selected namespaces are the union of all clause results. (Whether
exclusions are clause-local or honoured across the whole selection is Open Issue
2.)

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

builds a resolved generation by layering images in order:

```text
image inline :registrations      (later image in :images wins within this tier)
image :select-ns selections      (later image in :images wins within this tier)
```

with image inline `:registrations` dominating `:select-ns` selections. (Frame-
and dispatch-scoped overlays sit above these image tiers in precedence; they are
specified in EP-0028.)

These are resolution layers, not mutations of the global registrar, the image
value, or the frame's recorded image composition.

Within one image:

- inline `:registrations` dominate registrations selected by `:select-ns`;
- duplicate inline registrations for the same `[kind id]` fail loud;
- duplicate namespace-selected registrations for the same `[kind id]` fail loud
  unless they are the implementation's ordinary same-source hot-reload
  replacement case.

Across images, later images intentionally shadow earlier images for the same
`[kind id]`. Shadowing is not ambient namespace load order; it is the explicit
order of the `:images` vector.

Assemblers must retain enough provenance to show which descriptors were shadowed,
which layer won, and why. Layer dominance must be inspectable by Xray, Pair,
error reporters, and other tooling rather than disappearing during resolution.
This is a deliberate amendment to EP-0023's exact replacement maps: the API gains
a smaller data shape, and gives up the old coordinate-level winner declaration.
The mitigation is that order is explicit in `:images`, collisions inside one
selection clause still fail loud, and shadowed descriptors remain visible to
diagnostics and tools. (Whether silent cross-image shadowing should instead
require an explicit acknowledgement is Open Issue 1.)

### Inline registration grammar

Every inline registration entry uses one of two tuple shapes:

```clojure
[id body]
[id metadata body]
```

The metadata map is optional. Metadata-only entries are not part of the public
inline grammar; `[id metadata]` is invalid. For handler-style registrations,
`[id map]` is a missing-body error, not a map body, unless a future registration
kind explicitly defines a map body parser in its own spec. (This rejects the
metadata-only tuple EP-0023 permitted — see Open Issue 4.)

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

### Capability declarations

`:rf.image/requires` is removed from the public image API.

No convincing current use case requires image-level capabilities instead of
ordinary registration resolution, frame configuration, or host adapter setup. If
a future host dependency cannot be modeled cleanly by registrations and frame
configuration, it can return through a specific EP with concrete examples. (The
fate of the wider capability contract — `make-frame :capabilities` and the
assembly-time check — is Open Issue 3.)

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
and what local behavior it defines. Layer order across `:images` says which
definitions win.

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
- frame/dispatch behaviour overrides and `:rf.cofx` causal facts are covered by
  EP-0028 / EP-0017.

## Bead Plan / Reference Implementation

Expected implementation slices:

1. Add the `:select-ns` clause parser and diagnostics while keeping the EP-0023
   glob grammar.
2. Implement layered image resolution with shadow provenance reporting.
3. Remove public `:replace` and ordinary public `:replace-standard`; protect
   framework-standard registrations behind an internal or future-EP path.
4. Remove `:rf.image/requires` from image assembly, spec, examples, and guide
   text, deciding the wider capability contract (Open Issue 3).
5. Normalize inline registration tuple parsing to `[id body]` and
   `[id metadata body]` for all registration kinds; reject `[id metadata]`, and
   reconcile with EP-0023's stated inline grammar (Open Issue 4).
6. Add conformance tests for retired-key rejection, scoped namespace selection,
   duplicate detection, and cross-image shadow provenance.
7. Update Xray, Pair, examples, tools, migration skill, and the guide chapters
   that teach image assembly.

Guide-impact assessment:

- `docs/guide/quickstart.md` can teach `rf/image` inline registrations without
  descriptor replacement maps.
- image composition docs should show layer dominance and shadow inspection.

## Open Issues

These issues require recorded dispositions before this standards-track EP can
graduate. If any answer is deferred, the final EP should narrow its normative
surface and name the follow-on EP or bead that owns the deferred question.

1. **Should silent cross-image shadowing be allowed, or require an explicit
   acknowledgement?** Dropping `:replace` makes later-image-wins the default, but
   EP-0023 made cross-image collisions fail loud. A compact `:shadows #{[kind id]
   …}` acknowledgement on the frame/image would keep the smaller surface while
   preserving fail-loud-on-accident; unacknowledged cross-image collisions would
   still fail. If pure order is chosen instead, this EP must state plainly that it
   is accepting silent cross-image shadowing as a deliberate regression from
   EP-0023's fail-loud collision rule. Operator ruling needed.

2. **`:select-ns` clause-local vs global exclusion semantics.** Are exclusions
   local to their include clause (so a sibling clause can re-admit an excluded
   namespace), or honoured across the whole selection (one `:exclude` reliably
   drops a namespace, per EP-0023)? State the rule with an overlap example and
   pick the safe default. Operator ruling needed.

3. **Decide the capability contract end-to-end.** Removing `:rf.image/requires`
   leaves `make-frame :capabilities` and the assembly-time capability check
   without a declaration surface. Either delete the capability feature
   end-to-end (frame opts, generation shape, error catalogue, tools, tests) or
   move capability requirements onto selected registration metadata.

4. **Reconcile the inline tuple grammar with graduated EP-0023.** EP-0023 permits
   metadata-only `[id metadata]` entries; this EP rejects them. The reversal must
   be declared and any existing inline entries migrated.

5. **Is `rf/program` the final public name?** This EP records the naming
   preference but does not decide it.

## Recommendation

Accept the image-surface simplification: scoped `:select-ns`, inline
`:registrations` as the local-definition surface, explicit layer order in place
of `:replace`, and removal of `:rf.image/requires`. Resolve the cross-image
shadow and `:select-ns` exclusion open issues before marking the EP final. The
override/overlay vocabulary and the `rf/image` authoring-macro decision are
carried separately by EP-0028.
</content>
