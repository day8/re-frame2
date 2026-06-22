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

An image does two things: it **selects** existing namespace-authored registrations
with `:select-ns`, and it **defines** new registrations inline with
`:registrations` — the image-level analogue of a namespace's `(:require …)` and
`(def …)`. **Composition** (at `make-frame`) layers images by order — the later
image wins — and returns a **shadow report** the programmer can inspect or assert
on. Within a single image a `[kind id]` collision is an error, so every override is
between images; a cross-image shadow never fails assembly, while malformed images
and framework-standard collisions do. `rf/image` stays a plain function, and
image-level capabilities are removed end-to-end.

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

## Use Cases

Three uses cover the surface, and they map cleanly onto the two keys —
`:select-ns` **selects** existing registrations, `:registrations` **defines** new
ones.

### 1. Typical — select your app's namespaces

The everyday production path: an image selects the registrations already authored
across your namespaces. No inline definitions.

```clojure
(rf/image
  {:id :app/main
   :select-ns {:include ["app.todo.**" "app.admin.**"]
               :exclude ["app.todo.dev.**" "app.admin.fixtures.**"]}})
```

### 2. Teaching and explanation — define a whole example inline

A self-contained image with no `:select-ns` — every registration defined inline,
so a doc page, tutorial, or tiny test is one complete, copy-pasteable form.

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

### 3. Unit tests and Story — compose the app with an overrides image

Use case 1 (the real app image) **composed with** a separate, small image that
defines overrides — stub effects, coeffects, and the like. The override image is
composed *after* the app image, so its registrations win, and `frame-shadows`
reports exactly what it overrode.

```clojure
(def app-image
  (rf/image {:id :app/main
             :select-ns {:include ["app.checkout.**"]}}))

(def test-doubles
  (rf/image {:id :test/doubles
             :registrations
             {:reg-fx   [[:checkout.http/post recording-post]]          ;; stub the effect
              :reg-cofx [[:checkout/clock   (fn [] fixed-instant)]]}}))  ;; stub the coeffect

(let [frame (rf/make-frame {:images [app-image test-doubles]})]         ;; test-doubles wins (later)
  (rf/frame-shadows frame))
;; =>
[{:registration [:fx :checkout.http/post] :image :app/main :shadowed-by :test/doubles}
 {:registration [:cofx :checkout/clock]   :image :app/main :shadowed-by :test/doubles}]
```

An override is a *separate image*, never a second key: it is just a later image
whose registration shadows an earlier one. Within one image, two definitions of
the same `[kind id]` are an error, not an override (see Layered Resolution).

## Goals

- Replace sibling `:include-ns` / `:exclude-ns` with a single `:select-ns` map.
- Keep inline `:registrations` as the ordinary image-local definition surface.
- Replace public `:replace` with deterministic image layering (later image wins)
  plus a returned shadow report — no upfront acknowledgement key.
- Remove public `:replace-standard`; standards are protected, not ordinary app
  extension points.
- Remove image-declared host-capability declarations end-to-end.
- Expose the shadow report (`:rf.gen/shadows` + a frame accessor) for Xray, Pair,
  diagnostics, and test assertions.
- Keep fail-loud for within-image collisions, malformed images, and
  framework-standard collisions.

## Non-Goals

- Do not reopen the EP-0023 decision that frames run resolved image generations.
- Do not rename `rf/image`.
- Do not make `rf/image` a macro; it stays a plain function returning an inert
  image value. (Source-stamping authoring is discussed in EP-0028; `rf/image`
  stays a function regardless.)
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

Image ids MUST be unique within a single `:images` composition. The shadow report
identifies images by id, so two images sharing an id in one composition is an
error (see Diagnostics). A synthesized id for an anonymous image is unique within
its composition.

`rf/image` accepts this source map and returns an inert image value — a plain
function, not a macro. `:id` / `:select-ns` / `:registrations` are the public
**source** keys; the `:rf.image/*` reserved namespace (Conventions) names the
**normalized internal** form the value carries, not the authoring surface.

An image may carry both `:select-ns` and `:registrations`, but the two must be
**disjoint**: a `[kind id]` may not be both selected and defined inline in the
same image (see Layered Resolution). To override a selected registration, define
the override in a *later* image and compose them.

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

Selection does not load code. The namespaces must already be loaded through normal
`ns` / build-tool mechanisms; `:select-ns` only filters registrations the runtime
already knows about, by source-namespace provenance. (This is why the key is
`:select-ns`, not `:require`: it selects, it does not load — and it must not defeat
dead-code elimination.)

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

This is a behavior change to call out plainly: under this EP, omitting `:images`
(e.g. `make-frame {}`) resolves the **default image generation**, where a
configured frame might previously have run without resolving a generation at all.
The magnitude is real, the change may well be correct, and acceptance MUST cover
the omit-`:images` default path explicitly (see Acceptance Bar / Open Questions).

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

resolves by one rule: **the later image in `:images` wins.** Image order is the
only precedence — there is no within-image winner rule, because an image must
resolve cleanly to one descriptor per `[kind id]`.

Within a single image, any `[kind id]` that resolves two ways is an **error**:

- two **selected** descriptors for the same `[kind id]` (different source
  namespaces) — ambiguous; assembly fails. (The ordinary same-source hot-reload
  replacement of one namespace's own descriptor is not a collision.)
- two **inline** entries for the same `[kind id]` — malformed; caught at
  `rf/image` construction.
- an **inline** entry colliding with a **selected** one — error: to override, put
  the registration in a later image.

So an image may carry both `:select-ns` and `:registrations`, but they must be
disjoint. Overriding is never a within-image act; it is always a later image
**shadowing** an earlier one. When more than two images define the same
`[kind id]`, the last image still wins and every earlier definition is a loser
(the Shadow Report names the final winner for each — see below). A cross-image
shadow resolves (later wins) and is recorded in the **shadow report**; it does
**not** fail assembly, and the programmer reads the report and applies whatever
policy they want. The one cross-image collision that still fails is an app
descriptor colliding with a framework **standard** (see Framework Standard
Registrations).

Resolution MUST NOT mutate the global registrar, the image value, or the frame's
recorded image composition.

### Shadow Report

When a later image shadows an earlier image's registration, composition records
it. Each entry says exactly one thing: the registration `[kind id]`, the image it
was defined in, and the image that shadowed it. The report is exposed on the
frame's generation as `:rf.gen/shadows` and via the `rf/frame-shadows` accessor:

```clojure
(let [frame (rf/make-frame {:images [app-image test-doubles]})]
  (rf/frame-shadows frame))
;; =>
[{:registration [:fx :checkout.http/post]
  :image        :app/main
  :shadowed-by  :test/doubles}]
```

That is the whole entry — three keys, nothing else: no winner descriptor, image
index, tier, source namespace, or scope tag. Images are named by their
composition-unique id (see Image Keys). If two earlier images are both shadowed by
the same later one, that is two entries.

When images form a **chain** for one `[kind id]` — say
`[base override-a override-b]` — the report names the **final** winner for every
loser, not the immediate predecessor: `base` is `:shadowed-by override-b` and
`override-a` is `:shadowed-by override-b`. The report always names the registration
that is actually live, so an assertion never has to walk a chain.

Every shadow is **cross-image** — it names two distinct images — because within
one image a `[kind id]` collision is an error rather than a resolved winner (see
Layered Resolution). There is no within-image case and no scope tag.

The programmer applies whatever policy they want — there is no upfront
acknowledgement:

```clojure
(is (empty? (rf/frame-shadows frame)))                  ;; assert nothing was shadowed
(is (= #{[:fx :checkout.http/post]}                     ;; assert exactly the intended overrides
       (set (map :registration (rf/frame-shadows frame)))))
```

This replaces public `:replace` and the upfront acknowledgement model: the
programmer defines the winner in a later image, image order decides, and the
report names which registration each later image shadowed. A tool that wants
fuller detail (source namespace, etc.) reads the live registration's
`:rf.provenance/*` in the resolver.

### Framework Standard Registrations

Framework standard registrations are protected. They are not part of ordinary app
image layer order, and the report does **not** silently resolve them.

If an app descriptor has the same `[kind id]` as a framework standard descriptor,
assembly MUST fail with a standard-collision diagnostic. A standard encodes an
execution invariant (e.g. `:rf.interceptor/path` and the app-db commit no-op
rule), so shadowing it is a correctness violation, not an app policy choice.

A **framework standard** is a registration the framework itself ships and marks
standard because it encodes such an invariant — not an ordinary product
registration. The no-shadowing rule binds **public app images**; the framework, as
the standard's owner, keeps an internal path to define and revise its own
standards. A public app-facing standard-extension or standard-replacement hook, if
ever wanted, is a separate standards-track decision. This EP does not add one.

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
co-equal sources). `:rf.gen/shadows` carries only the three facts above per shadow
— the registration and the two image ids — because the "X was shadowed by Y" fact
is otherwise discarded at resolution; anything richer stays on `:rf.provenance/*`.

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

The inline form of a kind lowers through that kind's own registrar parser, so the
inline contract is exactly the registrar's contract — neither a looser superset
nor a stricter subset, and the EP invents no parallel grammar. Two constraints are
pinned now:

- inline `:reg-sub` accepts **only the layer-1 db-reader form** `(fn [db query] …)`;
  static `:<-` and parametric input-fn subscriptions stay namespace-authored;
- inline `:reg-cofx` carries the coeffect's grade (recordable / provided) exactly
  as `reg-cofx` does, including a generator-less provided/recordable entry where
  the registrar allows it.

The exact per-kind metadata legal inline — for example `:reg-event` `:interceptors`
/ `:rf.cofx/requires` / event-arg schema / classification — is pinned in the
implementation slice against the live lowering, and is an Open Question until then.

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

This EP removes one narrow feature: **image-declared host-capability
requirements** — the key an image used to declare it needs a host service, plus
the frame-side check. Exactly these three public surfaces are retired:

```clojure
:rf.image/requires        ;; an image declaring a required host capability
make-frame :capabilities  ;; the frame-supplied capability map
:rf.gen/requires          ;; the required-capability set on a resolved generation
```

Assembly-time capability checking tied to those keys MUST be deleted, not left as
an unreachable half-feature.

This removal is **surgical**. The word "capability" is reused across the repo —
`:rf.capability/*` host-service vocabulary, conformance capability ids, tool
capability flags, runtime profiles — and **none of those are touched**. The
residue gate MUST target exactly the three keys above, never the bare string
"capability".

If a future host dependency cannot be modeled by ordinary registration selection,
frame configuration, adapter setup, or registration metadata, it must return
through a new EP with concrete examples and an end-to-end tooling shape.

### Diagnostics

Implementations MUST fail loudly for at least these cases:

- retired image key;
- invalid `:select-ns` (missing/empty `:include`, or non-vector `:include` /
  `:exclude`);
- include pattern with no matches;
- two images sharing an `:id` within one `:images` composition;
- two definitions of the same `[kind id]` in one image — two selected
  (ambiguous), two inline (malformed, at `rf/image` construction), or an inline
  entry colliding with a selected one (an override must be a later image);
- app shadowing a framework standard;
- unsupported inline kind;
- invalid inline tuple arity (including a metadata-only tuple);
- `:images []`.

Note what is *not* here: a cross-image shadow — a later image overriding an
earlier one — is reported, not failed. Diagnostics SHOULD include:

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
order. So composition resolves by explicit image order and then **reports** what
it shadowed, rather than demanding an acknowledgement for every deliberate
override. The report is data: assert none, assert a known set, log, or ignore.
This keeps the common deliberate-override case ceremony-free while keeping every
shadow inspectable.

Overriding is a *composition* act, not a within-image one. An image must resolve
cleanly to one descriptor per `[kind id]`, so a within-image collision is an error;
to override, you compose a later image whose registration wins. That single rule
removes any within-image winner precedence and makes every shadow cross-image —
which is why the report needs no scope tag and the entry is just registration +
two image ids. Image ids are required unique per composition so those two ids name
exactly one image each. Fail-loud concentrates exactly where composition is
genuinely ambiguous, malformed, or unsafe (framework standards), which are
correctness facts, not policy.

Deleting image-declared capabilities end-to-end follows the same rule. A public
key should name a recurring application fact. That capability surface is not
connected to a strong enough use case and should not survive as design residue —
but the deletion is scoped narrowly, because "capability" names several unrelated
facts elsewhere in the repo.

## Backwards Compatibility

re-frame2 is pre-alpha. This EP makes clean breaking changes and does not require
compatibility shims.

Migration is source-level:

- replace `:include-ns` / `:exclude-ns` with one `:select-ns {:include … :exclude …}`;
- replace `:replace`: move the intended winner into a *later* image, compose, and
  read the returned shadow report if you want to assert on the overrides (there is
  no acknowledgement key, and an override defined in the same image as the thing it
  overrides is now an error);
- remove `:replace-standard`; ordinary app images cannot shadow standards;
- remove `:rf.image/requires`, `make-frame :capabilities`, and consumers of
  `:rf.gen/requires` (image-capability surfaces only — leave other "capability"
  vocabulary alone);
- rewrite metadata-only inline entries (permitted by EP-0023) as explicit
  metadata-plus-body, or move them back to namespace-authored registrations;
- replace `:images []` with omission (for the default) or a real empty image.

Retired keys MUST fail loudly so stale examples do not keep working by accident.

## Reference Implementation Plan

1. Add `:select-ns` map parsing with global exclusion semantics and strict include
   diagnostics.
2. Replace replacement-map resolution with image-order resolution (the later image
   wins; within one image a `[kind id]` collision is an error; image ids are
   unique per composition; a chain reports the final winner for every loser). Build
   the shadow report and expose it as `:rf.gen/shadows`, the `rf/frame-shadows`
   accessor, and the `reload-images!` report; reflect it in the `frame-generation`
   read (rf2-wkw8na). The resolved-generation cache key MUST include the
   `:select-ns` selection and the image order.
3. Fail loud only for: any within-image `[kind id]` collision (two selected, two
   inline, or inline-vs-selected), a duplicate image `:id` in one composition, a
   framework-standard collision, retired keys, `:images []`, and a bad inline kind.
   A cross-image shadow is reported, never failed. No acknowledgement key, no
   stale-ack check.
4. Protect framework standards from app shadowing; keep the standard-owner's
   internal definition/revision path non-public.
5. Remove `:rf.gen/requires`; do not add a `:rf.gen/layers` key — expose layer
   facts via descriptor provenance. `rf/image` stays a plain function.
6. Delete the three image-capability keys (`:rf.image/requires`,
   `make-frame :capabilities`, `:rf.gen/requires`) and their checks from
   implementation, specs, tools, guides, and tests — **surgically**, leaving
   `:rf.capability/*` / conformance / tool capability vocabulary untouched.
7. Narrow inline grammar to event/sub/fx/cofx via the late-bind inline-lowering
   publishers (events / subs / fx / cofx) and reject unsupported inline kinds; pin
   each kind's legal inline metadata and body forms against the live lowering.
8. Make `:images []` an error and specify the omit-`:images` default path.
9. Add conformance coverage for selection, default image behavior (including the
   omit-`:images` path), the shadow report contents (registration + image +
   shadowed-by, final-winner for chains), duplicate-image-id failure,
   within-image-collision failure, standard-collision failure, retired keys,
   `:images []`, and inline tuple errors.
10. Add a static residue gate for live retired spellings — scoped to the exact
    retired keys, not the bare string "capability" — outside historical prose,
    migration prose, and negative tests.

## Affected Surfaces

At minimum, the implementation sweep should cover:

- `implementation/core/src/re_frame/image.cljc`
- `implementation/core/src/re_frame/image_assembly.cljc`
- `implementation/core/src/re_frame/core.cljc`
- `implementation/core/src/re_frame/live_frame.cljc`
- `implementation/core/src/re_frame/late_bind/directory.cljc` and the per-kind
  inline-lowering publishers (events / subs / fx / cofx)
- the `reload-images!` report path and the `frame-generation` read (rf2-wkw8na)
- the resolved-generation cache key
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

1. The shadow report is returned and complete: for every cross-image shadow, one
   flat entry naming the registration `[kind id]`, the image it was defined in
   (`:image`), and the image that shadowed it (`:shadowed-by`) — by their
   composition-unique ids. No scope tag, no winner/loser descriptors.
2. A cross-image shadow (a later image overriding an earlier one) does not fail
   assembly.
3. Every within-image `[kind id]` collision (two selected, two inline, or
   inline-vs-selected) and every framework-standard collision fails loud.
4. Image order is tested across the override cases, including a multi-image chain:
   the later image wins, and every loser's report names the **final** winner.
5. Image-capability deletion is complete and **surgical**: the three image keys are
   gone, and `:rf.capability/*` / conformance / tool capability vocabulary is
   untouched.
6. Default image behavior — the omit-`:images` default path and `:images []` as an
   error — is specified and covered.
7. Unsupported inline kinds fail loudly, and each supported inline kind's legal
   metadata/body is pinned against the live lowering.
8. Two images sharing an `:id` within one composition fail loud.
9. Retired spellings are blocked by a static residue gate scoped to the exact keys.

## Open Questions

Several design points are settled (see Resolved Decisions). These remain genuinely
open:

1. What exact error ids should the new diagnostics use?
2. The exact per-kind inline metadata grammar pinned against the live lowering —
   e.g. which `:reg-event` metadata is legal inline (`:interceptors`,
   `:rf.cofx/requires`, event-arg schema, classification), and the precise
   `:reg-cofx` grade encoding.
3. Confirm the omit-`:images` default-frame path against the current
   implementation: today a generation resolves only when `:images` is present, so
   `make-frame {}` resolving the default image generation is a behavior change to
   bless (and acceptance-cover) or restrict.

## Resolved Decisions

Settled during design:

- **Shadow report shape** — a flat `[{:registration [kind id] :image <defined-in>
  :shadowed-by <winner>}]` list; no scope tag, no winner/loser descriptor.
- **Image-id uniqueness** — image ids MUST be unique within an `:images`
  composition; the report identifies images by id.
- **Shadow chains** — every loser reports the **final** winning image, not the
  immediate predecessor.
- **Accessor** — `rf/frame-shadows`.
- **Capability scope** — only the three image-capability keys are removed; other
  "capability" vocabulary is untouched.
- **`rf/image`** — stays a plain function, never a macro.
