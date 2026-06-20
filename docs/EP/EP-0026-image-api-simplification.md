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

## Design Review

This is an adversarial review by a skeptical senior re-frame2/Clojure designer.
The job is not to ratify the EP; it is to find where the proposed surface still
costs more than it should, where the semantics are underspecified, and where a
better shape exists. The EP is directionally right — it deletes levers and pulls
overrides toward data — but three of its moves are softer than the prose admits,
and one of them (the cross-scope overlay unification) is the kind of "obvious
simplification" that quietly adds surface area instead of removing it.

Findings are grouped by lens, each with a severity (**blocker** / **major** /
**minor**) and a concrete fix or an honest "unresolved." A closing subsection
proposes alternatives. The proposal is grounded in what actually ships today:
`rf/image` is a plain `defn`, not a macro (`implementation/core/src/re_frame/image.cljc`);
inline `:registrations` already exist and are stamped with `:rf.provenance/image`
+ `:rf.provenance/inline` but carry no source coordinates; frame-local and
dispatch-level `:registrations` do **not** exist yet; collisions already fail
loud in `image_assembly.cljc`; and dispatch-time overrides today are
`:fx-overrides` / `:interceptor-overrides` with a three-tier merge in
`router.cljc`.

### Design smells

**1. The cross-scope overlay unification is the largest hidden cost, not a
simplification. (major)**

The EP's headline win is "one registration-shaped overlay vocabulary" reused at
image, frame, and dispatch scope. That reads as collapse, but it is mostly
*expansion*: image inline `:registrations` already ship; frame `:registrations`
and dispatch `:registrations` are both **new** surfaces. The EP is adding two
override mechanisms while retiring one (`:replace`). The net key count is not
obviously smaller — it is rebalanced.

The deeper smell is that the three scopes are not the same fact. An image
`:registrations` is *definition* — it is a permanent part of a composable value
and participates in collision/replacement reasoning. A dispatch `:registrations`
is *masking* — a throwaway behavior swap that lives for one cascade and is
explicitly forbidden from touching the image or frame. Giving definition and
ephemeral masking the same spelling is exactly the move EP-0023 warned against
when it kept `:replace` loud: "Silent last-writer-wins would reintroduce the
global-registrar failure mode with a more modern API shape." Per-dispatch
`:registrations` is per-cascade last-writer-wins by construction. The EP's own
Open Issue 1 (can dispatch override the triggering event handler?) is the smell
surfacing: when the same word means "define" and "secretly replace the thing
about to run," you immediately need a special rule carving out the dangerous
case.

*Fix:* keep the **shape** uniform (a registrar-keyed tuple map is a good value),
but do not pretend the three scopes are one concept. Name the lifetime in the
key, not only in the surrounding form. Two viable spellings:

```clojure
;; image: definitions (collision-checked, composable, part of the value)
{:registrations {:reg-fx [[:checkout.http/post post-fx]]}}

;; frame / dispatch: overlays (masking, last-writer-wins within scope, never
;; collision-checked against the image — that is the point of a stub)
{:overrides {:reg-fx [[:checkout.http/post recording-fx]]}}
```

`:overrides` says "I am shadowing, on purpose, locally." `:registrations` stays
the word for definitions that earn collision checking. This is *more* honest to
the Clojure ethos than reusing one name, because the two facts behave
differently and the reader should not have to infer the difference from the
enclosing form.

**2. `rf/image` becoming a macro is a real ergonomic regression the EP
understates. (major)**

Today `rf/image` is a plain function: you can `(rf/image some-spec-map)`, build
specs programmatically, thread them, `map` over a vector of feature specs, and
generate them. Making it a macro to get source-stamping makes the *common
runtime path a fallback*: §`rf/image` is an authoring macro says non-literal
specs "may fall back to the runtime constructor, but source-stamping guarantees
then apply only to the literal parts it can inspect." So the most idiomatic
Clojure usage — data-driven image construction — silently loses the very
property the macro exists to add. That is a sharp corner: the macro is best
exactly where humans hand-write literals and worst exactly where the language is
at its best (programmatic data).

This trades a clean value-returning function for compile-time magic to chase a
benefit (source jumps to an inline handler) that EP-0023 explicitly says is the
*minority* path: "Most human-authored code should stay close to ordinary `reg-*`
forms." We are macro-fying the surface that the EP itself recommends people not
lean on.

*Fix (preferred):* keep `rf/image` a function. Solve source-stamping where it
already lives: the inline handler bodies are nearly always literal `fn` forms,
and the existing `reg-*` macros already capture coords via `*pending-coords*`
(`source_coords.cljc`). Provide a tiny `rf/reg` reader/quote helper, or let the
inline tuple optionally carry a coords map the way `reg-interceptor` accepts a
migration `:id` at its boundary. If a macro is genuinely wanted, make it
`rf/image-literal` (or document the fallback as first-class and *equal*, not
degraded) so the plain `rf/image` value path is not quietly demoted. The EP
should not bury "the data path loses stamping" in one sentence; that is a
headline tradeoff.

**3. Dispatch-level overlays are an unproven feature riding in on the
simplification. (major)**

The EP admits dispatch `:registrations` is "the determinate part" only after an
operator ruling (Open Issues 1–2). But it is presented in the Specification body
with worked examples, as though it were part of the simplification. A reader
will copy `(rf/dispatch-sync [...] {:registrations {...}})` from the spec
section. An EP at `proposal` that puts not-yet-ruled surface in normative-voiced
examples invites exactly the stale-example problem the Backwards Compatibility
section worries about.

Crucially, dispatch-level **registration** overlays do not exist today at all —
the shipped surface is `:fx-overrides` / `:interceptor-overrides`. So this is
not "simplify the existing dispatch override" — it is "design a new dispatch
override and fold the old two into it." That is a meatier change than the EP's
framing suggests, and it is the part most likely to break replayability (see
Correctness #2).

*Fix:* move all dispatch-level overlay material into Open Issues / a follow-on
EP. Let EP-0026 ship the determinate wins — `:select-ns`, drop `:replace`, drop
`:rf.image/requires`, frame-local overrides — and explicitly defer dispatch
overlays. The EP even says it should "narrow its normative surface" if an answer
is deferred; it should take its own advice in the body, not only in the Open
Issues.

### Correctness

**1. Trading `:replace` for layer order loses per-coordinate provenance and the
EP does not fully replace what it removes. (major)**

EP-0023's `:replace` is a *declaration*: "this `[kind id]` collides, and this
exact source wins." Assembly proves the collision was real and the winner
resolves to exactly one descriptor — three fail-loud checks
(`image_assembly.cljc` `resolve-replacement-winner`). EP-0026 replaces that with
"later image in the `:images` vector wins." The EP claims the mitigation is that
"order is explicit in `:images`" and "shadowed descriptors remain visible."

That mitigation is weaker than it sounds. Under `:replace`, an *accidental*
cross-image collision still fails loud unless you declared it. Under pure
layer-order shadowing, **every** cross-image collision is silently resolved by
position. The EP says collisions "inside one selection clause still fail loud,"
but cross-image is the case `:replace` was built for, and that is precisely the
case now made silent. A typo that makes `product-image` accidentally re-register
`:auth/login` will be silently shadowed by `story-image` with no error — the
opposite of EP-0023's "a bad image should fail while it is being assembled."

*Fix:* keep layer order as the *default winner rule*, but require an explicit
acknowledgment for cross-image shadowing the way EP-0023 required `:replace` for
cross-namespace collisions. A minimal data shape preserves the loud property
without reintroducing coordinate maps:

```clojure
(rf/make-frame
  {:images [base product story]
   ;; cross-image shadowing must be acknowledged by [kind id];
   ;; an unacknowledged cross-image collision still fails assembly.
   :shadows #{[:fx :checkout.http/post]
              [:event :auth/login]}})
```

This keeps the win (no per-coordinate winner *source* map — order decides the
source) while keeping the safety (intent is declared; accidents fail loud). If
Mike wants the pure-order model anyway, the EP must state plainly that it is
*accepting silent cross-image shadowing* as a deliberate regression from
EP-0023's loud collision rule, and justify it — right now it claims the safety
is preserved when it is not.

**2. Dispatch-level behavior overlays threaten replayability — the EP's own
core principle. (blocker if dispatch overlays ship as specified)**

re-frame2's replay story (EP-0010 / EP-0017) rests on: durable state is a fold
of the event stream plus recorded coeffects, and *behavior is resolved through
the frame's sealed generation*. A dispatch-level `:registrations` overlay breaks
that: the same event token, replayed against the same frame, can now resolve to
a *different effect/handler* depending on an overlay that lived only in the
original dispatch envelope and is **not** part of the frame's recorded
construction (the EP requires it "must not update the frame's recorded
construction data"). The EP carefully keeps `:rf.cofx` as the recordable channel
and says `:registrations` "stubs behavior" — but a recorded event replayed later
has no way to know which behavior was active, because the overlay was ephemeral
and unrecorded.

This is the sharpest correctness issue in the EP and it is not named. The EP
draws the line `:registrations` = behavior / `:rf.cofx` = facts, but the whole
point of recordable coeffects is that *facts are recorded so the fold is
reproducible*. An unrecorded behavior overlay is the one thing that makes a
recorded cascade non-reproducible.

*Fix:* either (a) forbid dispatch-level behavior overlays entirely (frame-level
overlays are recorded in frame construction and so are replayable; that may be
sufficient for every real test/story need), or (b) if dispatch overlays are
kept, require that the active overlay be captured in the cascade's trace/epoch
record so a replay can reconstitute it — i.e. make the overlay a *recorded*
fact, not an ephemeral one. Option (a) is the smaller, safer ship and likely
covers the motivating cases (tests/stories construct a frame anyway). This is a
graduation blocker as currently written.

**3. `:select-ns` clause semantics under cross-clause overlap are
underspecified. (minor)**

The EP says clause results union, and exclusions are clause-local. But it does
not say what happens when clause A includes `app.todo.**` (no exclude) and
clause B includes `app.todo.detail.**` with `:exclude ["app.todo.detail.dev.**"]`.
A descriptor in `app.todo.detail.dev` is selected by A and excluded by B. Union
of clause results means it is **selected** (A had no exclude). Is that intended?
Under EP-0023's single global include/exclude, the dev namespace would be
excluded. The new clause-local model can therefore *re-admit* a namespace a
sibling clause tried to drop. That is a real behavior change and a footgun
(Xray's image uses exactly this pattern — broad `**` include plus `*-cljs-test`
exclude; `tools/xray`).

*Fix:* state the rule explicitly and pick the safe default. The least
surprising rule is "a descriptor is selected iff some clause includes it **and
no clause excludes it**" — i.e. excludes are honored across the union, not
strictly clause-local. That keeps the EP-0023 guard behavior (one `:exclude`
reliably drops a namespace) while still allowing per-clause includes. If the
EP truly wants clause-local excludes, it must show the overlap example and say
"yes, a sibling clause can re-admit." Right now the reader cannot tell.

**4. `:select-ns` zero-match-per-clause vs. the EP-0023 zero-match contract is
ambiguous. (minor)**

EP-0023: each `:include-ns` pattern must match ≥1 descriptor or assembly fails.
EP-0026 says "an include pattern that matches no registration source namespace
fails image assembly." Per *pattern*, per *clause*, or per the union? Consider a
clause `{:include ["app.a.**" "app.b.**"]}` where `app.b` is legitimately not
loaded in this build (platform-conditional). Under per-pattern fail-loud, this
forces the author to split into conditional images — fine, that is EP-0023's
documented stance. But the EP should say so, because a clause with multiple
includes reads like "match any of these." State explicitly: each include
pattern within a clause is individually zero-match-fail-loud (preserving
EP-0023), not "the clause as a whole matched something."

### Clarity

**1. The precedence pseudo-block is grammatically broken and order-ambiguous.
(minor but real)**

```text
dispatch-local registration overlay
  dominates frame registration overlay
  dominate image inline registrations, later image wins inside the tier
  dominate image namespace-selected registrations, later image wins inside the tier
```

"dominates" then "dominate ... dominate" reads as a typo and the indentation
implies frame/inline/selected are siblings under dispatch, when they are a
strict descending chain. A normative precedence rule must be unambiguous. Render
it as an explicit ordered list, highest-wins:

```text
1. dispatch overlay        (highest — masks everything below, this cascade only)
2. frame overlay
3. image inline :registrations   (later image in :images wins within this tier)
4. image :select-ns selections   (later image in :images wins within this tier)
```

And state the within-tier rule once, not twice with a copy-paste verb error.

**2. "later image wins inside the tier" vs. "duplicate inline registrations …
fail loud" need reconciling for the same id across tiers. (minor)**

Within one image, two inline `:reg-fx` for `:x` fail loud. Across two images,
two inline `:reg-fx` for `:x` → later wins. So the *same* duplication is an error
intra-image and a silent win inter-image. That is defensible (intra-image is
almost always a mistake; inter-image is the composition story) but the EP states
the two rules paragraphs apart and never says "yes, this asymmetry is
deliberate." Say it explicitly with a one-line example, or a reader will read it
as inconsistency.

**3. "source-stamped authoring quality" is asserted, not specified. (minor)**

The EP says inline registrations get "the same source-stamped authoring quality
as the `reg-*` macros" and lists `:rf.provenance/image` / `:rf.provenance/inline`.
But the actual `reg-*` coords are `:ns` / `:file` / `:line` / `:column` (dev,
elided in prod per `source_coords.cljc`). The EP should name the exact keys it
promises and confirm the same dev/prod elision applies, or "authoring quality"
is unverifiable by a worker. Today inline descriptors carry the image/inline
provenance but **not** the file/line coords; that gap is the whole reason for
this part of the EP and it should be stated as the concrete deliverable.

### Clojure ethos

**Mostly aligned, with one regression.** Data-over-objects: the value-returning
inline grammar and "image is inert data" are good. Small orthogonal ops:
dropping `:replace` / `:replace-standard` / `:rf.image/requires` is genuine
surface reduction and well-justified — `:rf.image/requires` in particular looks
like leftover composition vocabulary with no live consumer, and removing it is
correct. Clear errors: retired-key rejection with actionable diagnostics is
right.

The regression is the macro turn (Finding #2): macro-fying a value constructor
is *less* Clojure-idiomatic, not more, and it makes the data-driven path
second-class. The whole appeal of "image is a value" is that values compose with
ordinary functions; a macro fights that. The simplest-thing-that-works here is a
function plus a coords-carrying tuple option, not a compile-time walker with a
degraded runtime fallback.

One more ethos point in the EP's favor: keeping `:rf.cofx` strictly separate
from `:registrations` is exactly right and should be louder — it is the cleanest
data/behavior cut in the proposal.

### re-frame2 ethos

**Isolated execution / introspection:** the requirement that shadowed
descriptors stay inspectable by Xray/Pair is correct and must not be softened —
it is the price of dropping the coordinate-precise `:replace` report. **Effects
as data:** folding `:fx-overrides` into `:registrations` is attractive but note
it *removes* a working, shipped, replay-safe mechanism (`:fx-overrides` is
frame-recorded and lexically scoped via `with-fx-overrides`) in favor of an
overlay that, at dispatch scope, is **not** replay-safe (Correctness #2). The EP
should not retire `:fx-overrides` / `with-fx-overrides` until the replacement is
proven to preserve their replay and lexical-scope properties.

**Retired realm vocabulary:** clean. EP-0026 contains no `realm` / `app-value`
references, and the realm/app composition surface was already removed from the
public facade (EP-0023; `spec/API.md`) and the multi-realm substrate collapsed
(rf2-afdlyr). Good — but a graduation note: because the EP amends a `final`,
graduated EP (EP-0023) and the normative homes it lists are live spec files, the
graduating worker must touch `spec/002-Frames.md` / `spec/Conventions.md` and
remove the EP-0023 `:include-ns` / `:exclude-ns` / `:replace` text in the same
pass, or the spec will carry two contradictory image grammars. The EP's Bead
Plan step 9 mentions tooling/guide but not the spec-text supersession; add it.

### Creative alternatives

**A. Split overlay from definition by name, not by scope (preferred).** As in
Smell #1: `:registrations` = definitions (image only, collision-checked);
`:overrides` = masking (frame + dispatch, last-writer-wins, never
collision-checked). This keeps one *tuple grammar* but two *concepts*, which is
the honest cut. It also dissolves Open Issue 1 cleanly: `:overrides` can
naturally forbid `:reg-event` of the triggering id (an override that swaps the
running handler is a different, scarier operation than overriding an fx it
calls).

**B. Keep `:replace` as an optional loud-acknowledgment, default to order.**
(Correctness #1.) Layer order is the default winner; `:shadows #{[kind id]…}`
acknowledges intentional cross-image shadowing; unacknowledged cross-image
collision fails loud. You get the smaller data shape *and* keep EP-0023's
fail-loud-by-default safety. This is strictly safer than pure order and barely
larger.

**C. Frame-only overlays for v1; dispatch overlays deferred.** Ship the
replay-safe, frame-recorded overlay now; defer the dispatch overlay (the
unproven, replay-risky one) to a follow-on EP with its recording story worked
out. This is the EP's own "narrow the surface if deferred" rule applied to the
body.

**D. Function `rf/image` + coords-carrying tuples instead of a macro.** Let an
inline entry optionally carry a coords map (`[id {:rf/coords {…}} body]`),
populated by a thin reader macro or by the `reg-*` machinery, so the value
constructor stays a function and programmatic image construction keeps stamping.
The macro becomes opt-in sugar, never a demotion of the data path.

### Verdict

The direction is sound and three of its moves are clear wins: scoped
`:select-ns`, dropping `:replace`/`:replace-standard` from the ordinary surface,
and removing `:rf.image/requires`. But as written the EP (1) silently changes
cross-image collisions from fail-loud to position-resolved while claiming the
safety is preserved, (2) introduces a dispatch-level behavior overlay that
breaks replayability and is not even named as a risk, (3) over-unifies three
different lifetimes under one word, and (4) macro-fies a value constructor in a
way that demotes the idiomatic data path. None of these is fatal to the
direction; all are fixable with the alternatives above.

**Recommendation: needs-rework before graduation** — specifically: adopt the
loud cross-image acknowledgment (B), name overlays distinctly from definitions
(A), defer or recording-prove dispatch overlays (C / Correctness #2), and keep
`rf/image` a function (D). With those four changes the EP becomes a clean,
data-oriented, replay-safe simplification worth graduating. Without them, it
trades EP-0023's hard-won fail-loud and replay guarantees for a smaller-looking
surface that is actually riskier.
