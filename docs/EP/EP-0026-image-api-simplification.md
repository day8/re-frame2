# EP-0026: Image API Simplification

Status: proposal
Type: standards-track

> This EP simplifies the EP-0023 image surface. It keeps the narrowed scope:
> frame/dispatch overlays and the source-stamping `rf/image` macro remain EP-0028.
> This EP owns only the public image value, image selection, inline image-local
> registrations, collision/shadow semantics, and capability cleanup. If accepted,
> the normative home is primarily `spec/Conventions.md` (the `:rf.image/*`
> reserved-key grammar), with `spec/002-Frames.md` and `spec/API.md` updated to
> match.

## Abstract

EP-0023 made image-loaded frames the public model, but the current image surface
still has too many levers:

```clojure
:include-ns
:exclude-ns
:replace
:replace-standard
:rf.image/requires
```

This EP replaces that surface with one selection vocabulary and a returned shadow
report. The public image value is just:

```clojure
:id
:select-ns
:registrations
```

Images select namespace-authored registrations with `:select-ns` and define local
registrations with `:registrations`. **Composition** (at `make-frame`) resolves
same-`[kind id]` collisions deterministically — later image wins; within an image,
inline wins over selected — and returns a **shadow report** the programmer can
inspect or assert on. A resolvable app shadow never fails assembly. Collisions
with no deterministic winner, malformed images, and framework-standard collisions
still fail loud. Image-level capabilities are removed end-to-end.

## Motivation

The old `:replace` and `:replace-standard` maps are precise, but they force
authors to speak in descriptor coordinates for the common case:

```text
In this image, use this registration for this id.
```

Inline `:registrations` already say that directly. The API should let the
programmer define the winning registration where it belongs, resolve composition
by explicit image order, and **report** what got shadowed — so the programmer
applies whatever policy they want (assert none, assert a known set, log, ignore)
rather than writing an acknowledgement for every deliberate override.

The pre-alpha bar is a small, powerful model that hands back data where
composition is a deliberate, resolvable choice, and fails loudly where composition
is genuinely ambiguous, malformed, or unsafe — with the minimum ceremony that
still buys those properties.

## Goals

- Replace sibling `:include-ns` / `:exclude-ns` with a single `:select-ns` map.
- Keep inline `:registrations` as the ordinary image-local definition surface.
- Replace public `:replace` with deterministic image layering (later image wins)
  plus a returned shadow report — no upfront acknowledgement key.
- Remove public `:replace-standard`; standards are protected, not ordinary app
  extension points.
- Remove image/frame capability declarations end-to-end.
- Expose the shadow report (`:rf.gen/shadows` + a frame accessor) for Xray, Pair,
  diagnostics, and test assertions.
- Keep fail-loud for ambiguous collisions, malformed images, and
  framework-standard collisions.

## Non-Goals

- Do not reopen the EP-0023 decision that frames run resolved image generations.
- Do not rename `rf/image`.
- Do not remove ordinary namespace-authored `reg-*` forms.
- Do not specify frame- or dispatch-scoped overlays; EP-0028 owns those.
- Do not standardize an inline grammar for every registration kind in this EP.
  Kinds without a concrete parser remain namespace-authored until their owning
  spec defines inline lowering.

## Relationships

- EP-0023 established image-loaded frames and the current image API. This EP is a
  simplification amendment.
- EP-0028 carries frame/dispatch overlays and the `rf/image` authoring macro
  split out of the earlier EP-0026 draft.
- EP-0022 / standard interceptors motivate the protected-standard rule.
- EP-0017 remains the source for causal coeffects; image-level capabilities are
  not a replacement for causal facts.
- EP-0007 (one name per fact) governs the "mirrors are recomputable projections"
  rule applied to generation provenance below.

## Specification

This section is written in final-spec style. While this EP is a `proposal`, the
language below is the proposed normative contract.

### Image Keys

The ordinary public image value accepts these top-level keys:

```clojure
:id
:select-ns
:registrations
```

`:id` is required for named images and SHOULD be stable enough for diagnostics
and tooling. Anonymous test helpers MAY synthesize an id, but generated ids must
still appear in provenance records.

The following keys are retired from the public `rf/image` surface:

```clojure
:include-ns
:exclude-ns
:replace
:replace-standard
:rf.image/requires
```

Public image construction MUST reject retired keys with actionable diagnostics.
They MUST NOT be accepted as aliases and MUST NOT be ignored. There is no image
acknowledgement key (`:shadows`, `:replace`, …): shadowing is a composition
outcome, reported at `make-frame`, not a property an image declares about a
composition it cannot see.

### Namespace Selection

Namespace selection uses `:select-ns`, a single map of `:include` and `:exclude`
pattern vectors:

```clojure
(rf/image
  {:id :app/main
   :select-ns {:include ["app.todo.**" "app.admin.**"]
               :exclude ["app.todo.dev.**" "app.admin.fixtures.**"]}})
```

`:include` is required and MUST be a non-empty vector. `:exclude` is optional and
defaults to an empty vector.

The selected namespace set is:

```text
union(:include matches) minus union(:exclude matches)
```

Exclusion is global to the image selection: a namespace matched by any `:exclude`
pattern is never selected, regardless of which `:include` pattern caught it. This
keeps the option teachable as "select these, never those" with no re-admission
corner cases.

The glob grammar remains the EP-0023 grammar:

- namespace strings are dot-separated;
- `*` matches exactly one segment;
- `**` matches zero or more segments;
- matching is case-sensitive;
- selection is by registration source namespace provenance, not by registration
  id namespace.

An include pattern that matches no registration source namespace MUST fail image
assembly. An exclude pattern that matches nothing is allowed.

Selecting the same source namespace through more than one include pattern is
idempotent. The descriptor is considered once.

An explicit image with no `:select-ns` selects no namespace-authored
registrations. It may still define inline `:registrations`.

### Default Image

The default image is a frame-construction behavior, not an ordinary
`rf/image` value.

For the reference implementation:

- omitting `:images` from `make-frame` uses the default frame image behavior;
- the default image selects all ordinary namespace-authored registrations in the
  default registrar source set and includes framework standards;
- duplicate distinct `[kind id]` descriptors in the default image fail loudly
  (there is no image order to resolve them — see Layered Resolution);
- default image hot-reload behavior remains the ordinary same-source replacement
  case, not general last-writer-wins.

`:images []` is an error: pass at least one image, or omit `:images` for the
default. To create a frame with no app registrations, pass a real empty image:

```clojure
(rf/make-frame
  {:images [(rf/image {:id :test/empty})]})
```

### Layered Resolution

Image composition is ordered data. A frame created with:

```clojure
(rf/make-frame
  {:images [base-image product-image story-image]})
```

resolves descriptors with this total precedence key:

```text
1. image index in :images, later image wins
2. tier within the winning image, inline :registrations win over :select-ns
```

Image order is the primary composition axis. Every same-`[kind id]` collision that
has a deterministic winner — across images (later wins) or within an image (inline
over selected) — resolves and is recorded in the frame's **shadow report** (see
below). Ordinary app shadowing does **not** fail assembly; the programmer reads the
report and applies whatever policy they want.

Three collisions still fail loud, because they are not a policy choice:

- a **duplicate inline** entry for the same `[kind id]` within one image — a
  malformed image, caught at `rf/image` construction;
- two **selected** descriptors for the same `[kind id]` within one image with no
  deterministic winner (different source namespaces, same tier) — ambiguous;
  assembly fails. (The ordinary same-source hot-reload replacement of one
  namespace's own descriptor is not a collision.)
- an app descriptor colliding with a framework **standard** — see Framework
  Standard Registrations.

Resolution MUST NOT mutate the global registrar, the image value, or the frame's
recorded image composition.

### Shadow Report

Composition computes a shadow report: every same-`[kind id]` collision it resolved,
with the winner and the shadowed losers. It is exposed on the frame's generation
as `:rf.gen/shadows` and via a convenience accessor:

```clojure
(let [frame (rf/make-frame {:images [base-image story-image]})]
  (rf/frame-shadows frame))
;; =>
[{:kind :event :id :counter/inc
  :scope :cross-image
  :winner   {:image-id :story/counter :image-index 1 :tier :registrations}
  :shadowed [{:image-id :app/base :image-index 0 :tier :select-ns
              :source-ns "app.counter.events"}]}]
```

The programmer applies whatever policy they want — there is no upfront
acknowledgement:

```clojure
(is (empty? (rf/frame-shadows frame)))                        ;; assert no override happened
(is (= #{[:event :counter/inc]}                               ;; assert exactly the expected ones
       (set (map (juxt :kind :id) (rf/frame-shadows frame)))))
```

Each entry is tagged `:scope :within-image` or `:scope :cross-image` so a policy
can treat them differently — for example, accept within-image inline overrides
while rejecting cross-image ones. A composition with deliberate overrides simply
orders its images; if it wants to check, it reads the report. Production wiring
that wants a hard guarantee asserts the report (e.g. that no cross-image shadow
occurred) in its own boot/test code.

This replaces public `:replace` and the upfront acknowledgement model without any
coordinate maps: the programmer defines the winner where it belongs, order decides
resolution, and the report preserves the precise loser/winner coordinates.

### Framework Standard Registrations

Framework standard registrations are protected. They are not part of ordinary app
image layer order, and the report does **not** silently resolve them.

If an app descriptor has the same `[kind id]` as a framework standard descriptor,
assembly MUST fail with a standard-collision diagnostic. A standard encodes an
execution invariant (e.g. `:rf.interceptor/path` and the app-db commit no-op
rule), so shadowing it is a correctness violation, not an app policy choice.

A future standards-track EP may define a specific standard-extension or
standard-replacement hook. This EP does not.

### Generation Provenance

The resolved generation value MUST expose:

```clojure
:rf.gen/resolver   ;; the sealed [kind id] -> descriptor map a frame runs
:rf.gen/images     ;; the composed image inputs
:rf.gen/kinds      ;; the kinds present
:rf.gen/shadows    ;; the shadow report (above)
```

`:rf.gen/requires` is retired with the capability feature.

Per-descriptor layer facts (source namespace, owning image, tier) already live on
each resolved descriptor's `:rf.provenance/*` metadata; a frame's full layer view
is a recomputable projection of the resolver plus that metadata and is **not** a
separate normative generation key (EP-0007 rule 4 — mirrors are projections, not
co-equal sources). `:rf.gen/shadows` is mandated because the loser coordinates it
preserves are otherwise discarded at resolution.

### Inline Registration Grammar

Inline registrations use this outer tuple shape:

```clojure
[id body]
[id metadata body]
```

The metadata map is optional and normalizes to `{}`. Metadata-only `[id metadata]`
entries are invalid in this EP. (EP-0023's image-fragment text permitted a
metadata-only tuple; this EP deliberately reverses that — see Backwards
Compatibility.)

EP-0026 standardizes only the inline kinds with a concrete parser:

| map key | kind | tuple body |
| --- | --- | --- |
| `:reg-event` | `:event` | event handler body accepted by the event registrar |
| `:reg-sub` | `:sub` | simple db-reader subscription body |
| `:reg-fx` | `:fx` | effect handler function |
| `:reg-cofx` | `:cofx` | coeffect handler function |

All other inline registration keys MUST fail with an unsupported-inline-kind
diagnostic until their owning spec defines: legal tuple forms, a body parser,
metadata/body disambiguation, a lowering hook, a provenance shape, and conformance
tests. This explicitly does not standardize inline forms for frames, routes,
heads, flows, resources, mutations, resource scopes, views, error projectors, or
interceptors in EP-0026.

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
                   (:counter/value db))]]

    :reg-fx    [[:metrics/send
                 (fn [payload]
                   (send-metric! payload))]]}})
```

### Capability Removal

The image/frame capability feature is removed from the public API in this EP. The
following public surfaces are retired:

```clojure
:rf.image/requires
make-frame :capabilities
:rf.gen/requires
```

Assembly-time capability checking tied to those keys MUST be deleted, not left as
an unreachable half-feature.

If a future host dependency cannot be modeled by ordinary registration selection,
frame configuration, adapter setup, or registration metadata, it must return
through a new EP with concrete examples and an end-to-end tooling shape.

### Diagnostics

Implementations MUST fail loudly for at least these cases:

- retired image key;
- invalid `:select-ns` (missing/empty `:include`, or non-vector `:include` /
  `:exclude`);
- include pattern with no matches;
- duplicate inline `[kind id]` in one image (at `rf/image` construction);
- ambiguous within-image collision (two selected descriptors, same `[kind id]`,
  no deterministic winner);
- app shadowing a framework standard;
- unsupported inline kind;
- invalid inline tuple arity (including a metadata-only tuple);
- `:images []`.

Note what is *not* here: a resolvable cross-image or inline-over-selected shadow is
reported, not failed. Diagnostics SHOULD include:

```clojure
{:rf.error/id ...
 :rf.error/phase :image/assembly
 :kind ...
 :id ...
 :image-id ...
 :image-index ...
 :candidates [...]
 :recovery ...}
```

The exact error ids are assigned in the implementation/spec update, but the errors
themselves are normative.

## Rationale

The winning image should be visible in data, not hidden in registration load
order. So composition resolves by explicit image order and then **reports** what it
shadowed, rather than demanding an acknowledgement for every deliberate override.
The report is data: assert none, assert a known set, log, or ignore. This keeps the
common deliberate-override case ceremony-free while keeping every shadow
inspectable — and it concentrates fail-loud exactly where composition is genuinely
ambiguous (no winner), malformed, or unsafe (framework standards), which are
correctness facts, not policy.

Deleting capabilities end-to-end follows the same rule. A public key should name a
recurring application fact. The current capability surface is not connected to a
strong enough use case and should not survive as design residue.

## Backwards Compatibility

re-frame2 is pre-alpha. This EP makes clean breaking changes and does not require
compatibility shims.

Migration is source-level:

- replace `:include-ns` / `:exclude-ns` with one `:select-ns {:include … :exclude …}`;
- replace `:replace`: order the images so the intended winner is later, and read
  the returned shadow report if you want to assert on the overrides (there is no
  acknowledgement key);
- remove `:replace-standard`; ordinary app images cannot shadow standards;
- remove `:rf.image/requires`, `make-frame :capabilities`, and consumers of
  `:rf.gen/requires`;
- rewrite metadata-only inline entries (permitted by EP-0023) as explicit
  metadata-plus-body, or move them back to namespace-authored registrations;
- replace `:images []` with omission (for the default) or a real empty image.

Retired keys MUST fail loudly so stale examples do not keep working by accident.

## Reference Implementation Plan

1. Add `:select-ns` map parsing with global exclusion semantics and strict include
   diagnostics.
2. Replace replacement-map resolution with image-index-first layer resolution
   (within an image, inline wins over selected); build the shadow report and expose
   it as `:rf.gen/shadows` plus an `rf/frame-shadows` accessor.
3. Fail loud only for: ambiguous within-image selected collision, duplicate inline,
   framework-standard collision, retired keys, `:images []`, bad inline kind. A
   resolvable shadow is reported, never failed. No acknowledgement key, no
   stale-ack check.
4. Protect framework standards from app shadowing.
5. Remove `:rf.gen/requires`; do not add a `:rf.gen/layers` key — expose layer
   facts via descriptor provenance.
6. Delete `:rf.image/requires`, `make-frame :capabilities`, and capability checks
   from implementation, specs, tools, guides, and tests.
7. Narrow inline grammar to event/sub/fx/cofx and reject unsupported inline kinds.
8. Make `:images []` an error.
9. Add conformance coverage for selection, default image behavior, the shadow
   report contents and `:scope` tags, ambiguous-collision failure, standard-
   collision failure, retired keys, `:images []`, and inline tuple errors.
10. Add a static residue gate for live retired spellings outside historical prose,
    migration prose, and negative tests.

## Affected Surfaces

At minimum, the implementation sweep should cover:

- `implementation/core/src/re_frame/image.cljc`
- `implementation/core/src/re_frame/image_assembly.cljc`
- `implementation/core/src/re_frame/core.cljc`
- `implementation/core/src/re_frame/live_frame.cljc`
- `spec/API.md`
- `spec/Conventions.md`
- `spec/001-Registration.md`
- `spec/002-Frames.md`
- `docs/guide/concepts/images.md`
- Pair `describe-image`
- Xray image panels and reads
- Story schemas/specs that model image values
- examples and migration docs
- conformance and residue gates

## Acceptance Bar

This EP should not graduate until:

1. The shadow report is returned and complete: every resolved same-`[kind id]`
   collision, correctly tagged `:within-image` / `:cross-image`, with winner and
   losers.
2. A resolvable app shadow (cross-image order, or inline-over-selected) does not
   fail assembly.
3. Ambiguous within-image selected collisions, duplicate inline entries, and
   framework-standard collisions fail loud.
4. Image order and within-image tier order are tested across the cross-tier cases.
5. Capability deletion is complete across code, specs, docs, tools, and tests.
6. Default image behavior — including `:images []` as an error — is specified and
   covered.
7. Unsupported inline kinds fail loudly.
8. Retired spellings are blocked by a static residue gate.

## Open Questions

The core model is settled: resolve by explicit image order, **report** shadows
rather than acknowledging them, fail loud only on ambiguous/malformed/unsafe
collisions, protect standards, delete capabilities, and keep the authoring surface
small. The remaining questions are implementation detail:

1. What exact error ids should the new diagnostics use?
2. Should `:rf.gen/shadows` carry full loser descriptors or coordinate-plus-summary
   only?
3. Is `rf/frame-shadows` the right accessor name, or should consumers read
   `:rf.gen/shadows` off the generation read directly?
</content>
