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

This EP replaces that surface with one selection vocabulary and one explicit
shadowing vocabulary:

```clojure
:id
:select-ns
:registrations
:shadows
```

Images select namespace-authored registrations with `:select-ns`, define local
image registrations with `:registrations`, and acknowledge intentional
cross-image overrides with `:shadows`. Unacknowledged distinct duplicate `[kind
id]` descriptors fail. Framework standard registrations are protected and cannot
be shadowed through ordinary app image order. Image-level capabilities are
removed end-to-end.

## Motivation

The old `:replace` and `:replace-standard` maps are precise, but they force
authors to speak in descriptor coordinates for the common case:

```text
In this image, use this registration for this id.
```

Inline `:registrations` already say that directly. The API should let the
programmer define the winning registration where it belongs, and require only a
small acknowledgement when one image intentionally shadows another image's
registration of the same id.

The pre-alpha bar is not compatibility with a noisy surface. The bar is a small,
powerful model that fails loudly when composition is accidental and explains
itself when composition is deliberate — with the minimum ceremony that still
buys those two properties.

## Goals

- Replace sibling `:include-ns` / `:exclude-ns` with a single `:select-ns` map.
- Keep inline `:registrations` as the ordinary image-local definition surface.
- Replace public `:replace` with deterministic image layering plus an explicit
  `:shadows` acknowledgement for cross-image overrides.
- Remove public `:replace-standard`; standards are protected, not ordinary app
  extension points.
- Remove image/frame capability declarations end-to-end.
- Add machine-readable shadow provenance (`:rf.gen/shadows`) for Xray, Pair,
  diagnostics, and test tooling.
- Preserve fail-loud duplicate handling for accidental same-id composition.

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
:shadows
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
They MUST NOT be accepted as aliases and MUST NOT be ignored.

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
corner cases. (A single flat map, rather than a vector of include/exclude
clauses, is deliberate: with global exclusion, clause grouping would carry no
semantics — it would only imply a per-clause pairing the rule denies.)

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
- duplicate distinct `[kind id]` descriptors in the default image fail loudly;
- default image hot-reload behavior remains the ordinary same-source replacement
  case, not general last-writer-wins.

`:images []` is an error: pass at least one image, or omit `:images` for the
default. To create a frame with no app registrations, pass a real empty image:

```clojure
(rf/make-frame
  {:images [(rf/image {:id :test/empty})]})
```

(There is one way to ask for the default — omission — and `[]` does not quietly
become a second one.)

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

Image order is the primary composition axis. A later image's selected descriptor
can beat an earlier image's inline descriptor — but only when the later image
explicitly acknowledges that shadow (see `:shadows`).

Within one image:

- duplicate inline entries for the same `[kind id]` fail;
- duplicate selected descriptors for the same `[kind id]` fail unless they are
  the ordinary same-source hot-reload replacement case;
- an inline descriptor automatically wins over a selected descriptor with the
  same `[kind id]` — this is local authoring intent and needs no acknowledgement.
  The win is still recorded in `:rf.gen/shadows` so it stays inspectable.

Across images:

- a later descriptor may beat an earlier descriptor with the same `[kind id]`
  only when the winning image includes a matching `:shadows` acknowledgement;
- without that acknowledgement, image assembly fails;
- shadowing is never ambient namespace load order.

Resolution MUST NOT mutate the global registrar, the image value, or the frame's
recorded image composition.

### Shadow Acknowledgement

`:shadows` is the public acknowledgement surface for deliberate **cross-image**
descriptor shadowing. (Within-image inline-over-selected wins automatically and
needs no entry — see Layered Resolution.)

```clojure
;; base image defines the real :counter/inc by namespace selection
(def base-image
  (rf/image
    {:id :app/base
     :select-ns {:include ["app.counter.**"]}}))

;; a later image intentionally overrides it with an inline stub
(def story-image
  (rf/image
    {:id :story/counter
     :registrations {:reg-event [[:counter/inc {} story-inc]]}
     :shadows #{[:event :counter/inc]}}))

(rf/make-frame {:images [base-image story-image]})  ;; story wins, acknowledged
```

Each entry is:

```clojure
[kind id]
```

where `kind` is the normalized descriptor kind, such as `:event`, `:sub`, `:fx`,
or `:cofx`.

Rules:

- The acknowledgement lives on the winning (later) image.
- Acknowledgement authorizes only the winning descriptor for that `[kind id]`.
- One acknowledgement may cover multiple shadowed losers for the same `[kind id]`.
- Acknowledgement cannot authorize duplicate inline entries in the same image.
- Acknowledgement cannot authorize app shadowing of framework standards.
- A stale acknowledgement that matches no actual cross-image shadow MUST fail;
  stale `:shadows` entries are composition lies.

This replaces public `:replace` without reintroducing coordinate-heavy
replacement maps. The programmer says "this id is intentionally shadowed", the
ack's location names the winner, and tooling records the precise loser/winner
coordinates.

### Framework Standard Registrations

Framework standard registrations are protected. They are not part of ordinary app
image layer order.

If an app descriptor has the same `[kind id]` as a framework standard descriptor,
assembly MUST fail with a standard-collision diagnostic. `:shadows` MUST NOT
authorize this collision.

A future standards-track EP may define a specific standard-extension or
standard-replacement hook. This EP does not.

### Shadow Provenance

Resolved frame generations MUST retain machine-readable provenance for the
collisions they resolved. The generation value MUST expose:

```clojure
:rf.gen/resolver   ;; the sealed [kind id] -> descriptor map a frame runs
:rf.gen/images     ;; the composed image inputs
:rf.gen/kinds      ;; the kinds present
:rf.gen/shadows    ;; the resolved collisions (winner + losers + ack)
```

`:rf.gen/requires` is retired with the capability feature.

Each `:rf.gen/shadows` entry MUST identify at least:

```clojure
{:kind :event
 :id   :counter/inc
 :winner   {:image-id :story/counter :image-index 1 :tier :registrations}
 :shadowed [{:image-id :app/base :image-index 0 :tier :select-ns
             :source-ns "app.counter.events"}]
 :ack {:image-id :story/counter :entry [:event :counter/inc]}}
```

Per-descriptor layer facts (source namespace, owning image, tier) already live on
each resolved descriptor's `:rf.provenance/*` metadata; a frame's layer view is a
recomputable projection of the resolver plus that metadata and is **not** a
separate normative generation key (EP-0007 rule 4 — mirrors are projections, not
co-equal sources). `:rf.gen/shadows` is mandated because the loser coordinates it
preserves are otherwise discarded at resolution. Xray, Pair, error reporters, and
conformance tests consume `:rf.gen/shadows` for the collision story and the
resolver + descriptor provenance for everything else.

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
metadata/body disambiguation, a lowering hook, a provenance shape, and
conformance tests. This explicitly does not standardize inline forms for frames,
routes, heads, flows, resources, mutations, resource scopes, views, error
projectors, or interceptors in EP-0026.

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
- duplicate inline `[kind id]`;
- duplicate selected `[kind id]`;
- unacknowledged cross-image shadow;
- stale `:shadows` entry;
- attempted standard shadow;
- unsupported inline kind;
- invalid inline tuple arity (including a metadata-only tuple);
- `:images []`.

Diagnostics SHOULD include:

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

The exact error ids are assigned in the implementation/spec update, but the
errors themselves are normative.

## Rationale

The winning image should be visible in data, not hidden in registration load
order. The winning override should be visible in the image that owns it, not in a
separate coordinate map only experts remember.

`:shadows` is intentionally smaller than `:replace`. It does not ask authors to
name loser coordinates, and it does not ask anything at all for the common case —
overriding a selected handler with an inline one in the same image. It asks for a
single `[kind id]` acknowledgement only when one image deliberately shadows
another, which is exactly where an accidental collision would otherwise hide. The
implementation keeps the exact loser/winner coordinates for tools: authors write
compact intent; the system preserves precise provenance.

Deleting capabilities end-to-end follows the same rule. A public key should name a
recurring application fact. The current capability surface is not connected to a
strong enough use case and should not survive as design residue.

## Backwards Compatibility

re-frame2 is pre-alpha. This EP makes clean breaking changes and does not require
compatibility shims.

Migration is source-level:

- replace `:include-ns` / `:exclude-ns` with one `:select-ns {:include … :exclude …}`;
- replace `:replace` with a later image plus `:shadows` (only for cross-image
  overrides; a same-image inline override needs no acknowledgement);
- remove `:replace-standard`; ordinary app images cannot shadow standards;
- remove `:rf.image/requires`, `make-frame :capabilities`, and consumers of
  `:rf.gen/requires`;
- rewrite metadata-only inline entries (permitted by EP-0023) as explicit
  metadata-plus-body, or move them back to namespace-authored registrations;
- replace `:images []` with omission (for the default) or a real empty image.

Retired keys MUST fail loudly so stale examples do not keep working by accident.

## Reference Implementation Plan

1. Add `:select-ns` map parsing with global exclusion semantics and strict
   include diagnostics.
2. Add `:shadows` parsing and cross-image validation (incl. stale-ack rejection).
3. Replace replacement-map resolution with image-index-first layer resolution;
   within an image, inline wins over selected automatically.
4. Preserve fail-loud duplicate handling for unacknowledged cross-image duplicates.
5. Protect framework standards from ordinary app shadowing.
6. Add `:rf.gen/shadows`; remove `:rf.gen/requires`. Do not add a separate
   `:rf.gen/layers` key — expose layer facts via descriptor provenance.
7. Delete `:rf.image/requires`, `make-frame :capabilities`, and capability checks
   from implementation, specs, tools, guides, and tests.
8. Narrow inline grammar to event/sub/fx/cofx and reject unsupported inline kinds.
9. Make `:images []` an error.
10. Add conformance coverage for selection, default image behavior, cross-image
    shadows, stale acknowledgements, standard collisions, retired keys, `:images []`,
    and inline tuple errors.
11. Add a static residue gate for live retired spellings outside historical prose,
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

1. No unacknowledged distinct cross-image duplicate `[kind id]` can silently
   resolve.
2. `:shadows` has parser, cross-image validation, `:rf.gen/shadows` provenance,
   tests, Xray, and Pair support.
3. Image order and within-image tier order are tested across the cross-tier cases.
4. Framework standards cannot be shadowed through ordinary image order.
5. Capability deletion is complete across code, specs, docs, tools, and tests.
6. Default image behavior — including `:images []` as an error — is specified and
   covered.
7. Unsupported inline kinds fail loudly.
8. Retired spellings are blocked by a static residue gate.

## Open Questions

The core model is settled: fail loud by default, acknowledge cross-image shadows
explicitly, protect standards, delete capabilities, and keep the authoring surface
small. The remaining questions are implementation detail:

1. What exact error ids should the new diagnostics use?
2. Should `:rf.gen/shadows` carry full loser descriptors or coordinate-plus-summary
   only?
3. Should future inline grammars reuse the same tuple shell, or may each kind
   define a richer body form in its owning spec?
</content>
