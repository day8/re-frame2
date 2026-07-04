# EP-0028: Registration Overlays and Inline Image Authoring

Status: deferred
Type: standards-track

> **Deferred 2026-06-24** (operator ruling): parked. The override/overlay
> vocabulary and the inline source-stamping question this EP carries are its
> contested, replay-sensitive surfaces; they wait here, kept per EP-0009, until
> the open issues are ruled.

> **Modernised 2026-07-04** (five-lens review; findings in
> `ai/findings/EP-0028/`, local-only): rebased against final EP-0026 (image
> order is the only image precedence; `rf/image` stays a plain function),
> corrected the override-surface attribution (Spec 002 + EP-0022 + EP-0024, not
> EP-0023), added the shipped lexical tier and inheritance rules to the base
> model, re-aimed Open Issue 6's replay analysis, and adopted the pipeline-run
> vocabulary. The shipped half of Open Issue 6 — envelope overrides are not
> recorded in the epoch record today — is extracted as bug **rf2-yigokd**, to be
> ruled jointly with **rf2-70h9wn** (payload serialisability). Status is
> unchanged: the open issues remain open.

> This EP carries the override/overlay vocabulary and the inline-authoring
> source-coordinates question that were split out of EP-0026 on 2026-06-22.
> EP-0026 retains the determinate image-surface simplification (`:select-ns`,
> dropping `:replace` / `:replace-standard`, removing `:rf.image/requires`) and
> has since graduated to `final`. This EP owns the contested surfaces: behaviour
> overrides at frame and dispatch scope, and first-class source coordinates for
> literal inline registrations. If accepted, the normative homes are
> `spec/002-Frames.md` (frame/dispatch override precedence and child
> inheritance), `spec/API.md`, and `spec/Conventions.md`.

## Abstract

EP-0023 ships image-scope inline `:registrations`. Separately, Spec 002 carries
the envelope override surface — per-call and per-frame `:fx-overrides` /
`:interceptor-overrides` (interceptor references per EP-0022, constructor-uniform
config per EP-0024) — plus the lexical `with-fx-overrides` seam. This EP asks
(a) whether one registration-shaped overlay vocabulary should span image, frame,
and dispatch scope for behaviour stubs, and (b) whether literal inline
registrations should gain source coordinates — noting that final EP-0026
resolved that `rf/image` itself stays a plain function, so any compile-time
capture must ride a companion form or caller-supplied metadata.

Both questions carry real, unresolved design risk — most sharply, dispatch-scoped
behaviour overlays interact with re-frame2's replay model. They were gathered
here so EP-0026 could graduate without waiting on them. Question (a) amends
Spec 002's override contract; question (b) amends EP-0023's source-stamping
guarantees.

## Motivation

Tests and stories routinely need to stub behaviour: a fake HTTP effect, a
recording effect, a swapped interceptor. Today every such journey is served by
shipped seams, but the seams have different shapes at different scopes:

- **image scope** — inline `:registrations` *define* entries into the sealed
  generation (collision-checked, shadow-reported); a frame's doubles are a
  trailing image in its `:images` vector (EP-0026 use case 3);
- **frame scope** — config `:fx-overrides` / `:interceptor-overrides` *mask*
  registered behaviour for the frame's lifetime;
- **dispatch scope** — the same two keys as per-call opts mask for one pipeline
  run (inheriting to child dispatches; see the base model below);
- **lexical scope** — `with-fx-overrides` masks fx for a dynamic extent
  (`with-managed-request-stubs` rides this seam).

The capability is covered; the *spelling* is not uniform. This EP asks whether
one `:registrations`-shaped overlay vocabulary should span the scopes, and under
what lifetime and precedence rules — an ergonomics-and-uniformity question, not
a capability gap.

Separately, literal inline registrations authored inside an `rf/image` value
carry image/inline provenance (`:rf.provenance/image`, `:rf.provenance/inline`)
but no source coordinates, so they are a lesser tooling path than namespace-
authored `reg-*` forms (no click-to-source). Spec 001 makes coordinate capture
an optional capability whose absence does not violate conformance, so this too
is an ergonomics question. This EP asks whether and how to close the gap,
within EP-0026's ruling that `rf/image` remains a function.

Both questions amend graduated contracts and carry unresolved alternatives, so
they warrant an EP rather than a bead.

## Goals / Non-Goals

Goals:

- decide whether behaviour overrides at frame and dispatch scope use one
  `:registrations`-shaped vocabulary, and under what lifetime/precedence rules;
- decide the dispatch-overlay allowed kind set, child-dispatch inheritance, and
  replay-recording story — meeting whatever recording bar the rf2-yigokd /
  rf2-70h9wn ruling sets for the shipped surface;
- decide how (or whether) literal inline registrations gain source coordinates,
  and the exact coordinate sinks and production-elision rules if they do;
- keep behaviour overrides strictly separate from `:rf.cofx` causal facts.

Non-goals:

- do not respecify image-scope selection, collision, or capability behaviour —
  that is final EP-0026;
- do not revisit EP-0026's resolved decision that `rf/image` is a plain
  function — any coordinate story here is a companion form or metadata path,
  not a constructor macro;
- do not make dispatch-level coeffect facts (`:rf.cofx`) into registration
  overrides;
- do not retire the shipped `:fx-overrides` / `:interceptor-overrides` /
  `with-fx-overrides` surfaces until a replacement is proven to preserve their
  replay and lexical-scope properties.

## Relationships

- **EP-0026** — split-from sibling, now `final`. Rules the image resolution
  model this EP must build on (image order is the **only** image precedence;
  within one image, inline and selected entries are collision-checked, with
  disjointness normative in Conventions) and resolves `rf/image` as "stays a
  plain function, never a macro". Delegates the frame/dispatch-overlay question
  here (EP-0026 §Non-Goals).
- **EP-0023** — established `rf/image` (a plain function returning inert data),
  image-scope inline `:registrations`, provenance naming, and the resolved image
  generation. It does **not** define the dispatch-time override surface.
- **Spec 002 / EP-0022 / EP-0024** — the envelope override heritage:
  Spec 002 owns per-call and per-frame `:fx-overrides` /
  `:interceptor-overrides` and their precedence; EP-0022 defines registered
  interceptors (overrides are exact-reference substitution/removal, not
  registration replacement); EP-0024 made the config keys constructor-uniform.
- **EP-0017** — defines `:rf.cofx` causal facts and the recordable-coeffect
  replay model. Recorded facts are re-presented verbatim under strict replay,
  which is why cofx-supplier overlays are *not* the sharp replay hazard (see
  Open Issue 6).
- **EP-0018** — defines the single `reg-event` surface assumed by the inline
  registration grammar.
- **EP-0007** — one-name-per-fact; the masking-vs-definition naming question
  below.
- **rf2-yigokd / rf2-70h9wn** — the shipped epoch-record gap for envelope
  overrides, and the payload-serialisability clause; ruled jointly, they set
  the recording bar any new overlay tier must meet.

## Specification

> At `deferred` status this records the proposed contract and its open
> questions, not a shipped guarantee. The open issues at the end are graduation
> blockers.

### The shipped base model (normative today, restated for orientation)

Resolution for a dispatch, from the outside in:

```text
per-call envelope overrides        :fx-overrides / :interceptor-overrides on
                                   the dispatch opts — highest precedence
lexical scope (fx only)            with-fx-overrides dynamic extent
per-frame config overrides         :fx-overrides / :interceptor-overrides in
                                   frame config
—— the frame's sealed generation (EP-0026, final) ——
images, in :images order           later image wins; within ONE image, inline
                                   :registrations and :select-ns selections are
                                   collision-checked — there is no within-image
                                   tiering
```

Shipped semantics any overlay proposal must reconcile with:

- precedence is per-call > lexical > per-frame (router envelope build);
- per-call overrides inherit down `:dispatch` / `:dispatch-later` children
  (crossing the timer hop), filtered against the reserved reject tier
  (state-installing fxs cannot be overridden); they do **not** survive async
  re-entries — a reply is a fresh dispatch;
- fx overrides are id-valued or fn-valued (fn-valued is CLJS sugar and is not
  serialisable); interceptor overrides are exact-reference keyed;
- image `:registrations` **define** (into the sealed, collision-checked
  generation); envelope overrides **mask** (resolution-time, nothing
  re-registered).

### Proposed: layered `:registrations` overlays

This EP proposes two overlay tiers *above* the generation, sharing the image
`:registrations` tuple grammar:

```clojure
;; image-level: part of the composed image value (EP-0026, shipped)
(rf/image
  {:id :checkout/story-overrides
   :registrations
   {:reg-fx [[:checkout.http/post story-http-post]]}})

;; frame-level (PROPOSED): local to this frame for its lifetime
;; shipped alternatives: a trailing doubles image in :images, or frame-config
;; :fx-overrides / :interceptor-overrides
(rf/make-frame
  {:id :checkout/story
   :images [checkout-image]
   :registrations
   {:reg-fx [[:checkout.http/post story-http-post]]}})

;; dispatch-level (PROPOSED): local to this pipeline run
;; shipped alternative: per-call :fx-overrides / :interceptor-overrides
(rf/dispatch-sync
  [:checkout/submit]
  {:frame :checkout/story
   :registrations
   {:reg-fx [[:checkout.http/post recording-http-post]]}})
```

Conceptually:

```text
image :registrations      = inline local definitions in the image (shipped)
frame :registrations      = a frame-owned overlay over the resolved generation
dispatch :registrations   = a run-local overlay owned by the dispatch envelope
```

Frame-level `:registrations` would use the same tuple grammar as image-level
registrations. Two design paths exist and the choice is open: define the frame
overlay **as sugar lowering to a trailing anonymous image** (inheriting
EP-0026's generation identity, cache, shadow-report, and reload semantics for
free), or as an independent post-generation layer (which must then specify its
generation-identity, cache-key, shadow-report, reload/surgical-update, allowed-
kind, and mask-vs-define semantics from scratch).

Dispatch-level `:registrations` use the same syntactic shape, but the allowed
kind set is an open issue — overriding the triggering event handler itself may
be too magical, and today only `:reg-event` / `:reg-sub` / `:reg-fx` /
`:reg-cofx` have inline lowering machinery at all (other kinds fail loud at
image assembly), so any wider set is new machinery, not reuse. Dispatch-level
overlays are part of one pipeline run's resolution context. They must not
update the frame's recorded construction data, must not rewrite the image
value, and must not affect unrelated runs. If child dispatches inherit the
overlay, that inheritance must be specified explicitly — and should be stated
relative to the shipped envelope-inheritance rule rather than falling out of
queue implementation details.

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

### Source coordinates for inline registrations (open)

Shipped today: inline entries are provenance-named (`:rf.provenance/image` +
`:rf.provenance/inline`) and coordinate-less. Spec 001's two-sink contract makes
file/line capture an optional capability — its absence does not violate
conformance — so tools render the provenance name where namespace-authored
registrations render a source chip.

Final EP-0026 resolved that `rf/image` **stays a plain function** (Resolved
Decisions; normative in Conventions). Graduating a whole-constructor macro here
would mean explicitly superseding a resolved decision of a final EP, and
nothing so far motivates that. The live options are therefore:

- **caller-supplied metadata** — standard Spec 001 coordinate keys (`:ns` /
  `:file` / `:line`) in a 3-tuple's metadata map, lowered through to the
  descriptor. The lowering preserves `:metadata`; end-to-end survival into the
  coordinate sinks needs one verification test before this can be called the
  documented escape hatch;
- **an opt-in companion literal form** (`rf/image-literal` / `defimage`-style)
  that walks a literal spec at compile time and stamps coordinates, leaving
  `rf/image` untouched for programmatic construction (`map`, threading,
  generated specs);
- **accept provenance-only** as the documented posture for the inline path
  (tests, teaching, generated code), revisiting when a named tooling consumer
  demonstrates need.

Whatever form ships must: capture namespace/file/line where available; preserve
production elision for source-heavy metadata; preserve image provenance; and
return an inert image value rather than registering behaviour globally —
first-class in errors, Xray, Pair, source jumping, and handler introspection
exactly where `reg-*` forms are.

## Rationale

Frame and dispatch overrides are masking, not definition. Image `:registrations`
are part of an inert composable value and participate in collision reasoning; a
dispatch overlay is a throwaway behaviour swap that lives for one run. Giving
both the same spelling is convenient but may conflate two facts (Open Issue 4) —
and the shipped surface already keeps the two verbs distinct: images define,
envelope overrides mask, `:rf.cofx` supplies facts. Keeping `:rf.cofx` strictly
separate from `:registrations` is the cleanest data/behaviour cut: `:rf.cofx`
records what fact an event observed; overlays change what the frame can
resolve.

The inline-coordinates question trades authoring ceremony against tooling
fidelity on what is today a minority authoring path with no non-framework
consumers. The compile-time options land only on literal specs; programmatic
image construction stays coordinate-less under every option, which bounds how
much any of them can promise (Open Issue 5).

## Backwards Compatibility

re-frame2 is pre-alpha. The shipped `:fx-overrides` / `:interceptor-overrides`
and `with-fx-overrides` surfaces remain until any replacement is proven to
preserve their replay-safety and lexical scoping. `rf/image` remains a plain
function per final EP-0026's resolved decision; Open Issue 5 concerns companion
forms and metadata paths only.

## Bead Plan / Reference Implementation

Expected implementation slices, each gated on the relevant open-issue ruling:

1. Frame-local `:registrations` overlays — **if ruled in**, preferably as sugar
   lowering to a trailing anonymous image (inheriting generation identity,
   cache, shadow-report, and reload semantics); if instead an independent
   layer, it owes each of those semantics an explicit definition first.
2. Decide and implement the dispatch-level allowed kind set, child-dispatch
   inheritance rule, and replay-recording — or forbid dispatch behaviour
   overlays. Gated on the rf2-yigokd / rf2-70h9wn recording ruling.
3. The inline-coordinates path: verify the caller-metadata escape hatch
   end-to-end; add the opt-in literal companion only if ruled in.
4. Define the public shadow/layer introspection shape consumed by Xray / Pair.
5. Update guide/testing chapters that teach stubbing.

## Open Issues

These require recorded dispositions before this standards-track EP can graduate.

1. **Should dispatch-level `:registrations` allow every registration kind?** The
   conservative default: allow the kinds genuinely consulted inside a run
   (`:reg-fx`, `:reg-cofx`, `:reg-interceptor`, and any resource/mutation kinds
   used by managed effects), and reject overriding the triggering event handler.
   Note the machinery dependency: inline lowering exists today only for
   `:reg-event` / `:reg-sub` / `:reg-fx` / `:reg-cofx`; every other kind is new
   machinery. Operator ruling needed.

2. **Do dispatch-level registration overlays inherit to child dispatches?** The
   shipped envelope keys inherit down `:dispatch` / `:dispatch-later` children,
   filtered against the reserved reject tier, and do not survive async
   re-entries. This EP needs one explicit rule, stated relative to that shipped
   behaviour.

3. **Does `:interceptor-overrides` have a remaining use as exact-reference
   rewriting / interceptor removal rather than registration replacement?** If yes,
   keep it as its own narrow surface, out of the general overlay vocabulary.
   (Registration replacement cannot address one instance of a duplicated
   factory reference; exact-reference keys can.)

4. **Definition vs masking naming.** Image `:registrations` are collision-checked
   definitions; frame/dispatch overlays are masking. Should masking use a distinct
   key (e.g. `:overrides`) so one word does not name two facts (EP-0007 rule 3)?

5. **Inline source coordinates: which path?** Caller-supplied metadata (needs
   the end-to-end verification test), an opt-in literal companion form, or
   accept provenance-only. The whole-constructor macro is foreclosed by final
   EP-0026 unless that resolved decision is explicitly superseded.

6. **Overlay replay safety.** A recorded event replayed against the same frame
   generation can resolve to *different* behaviour if the original run used an
   unrecorded overlay. The sharp cases are **interceptor overlays** (they edit
   the pre-commit chain, changing `db-after`) and **fx overlays that swallow or
   redirect child dispatches** (they change which events enter the stream).
   Cofx-supplier overlays are *not* the sharp case: EP-0017 records every
   minted recordable fact post-generation and strict replay re-presents them
   verbatim, supplier overlays notwithstanding — and exact observed facts
   belong on `:rf.cofx` regardless. Note this hazard already exists for the
   **shipped** envelope overrides, which the epoch record does not capture;
   that half is extracted as bug **rf2-yigokd** (ruled jointly with
   **rf2-70h9wn**). Any new overlay tier must meet the recording bar that
   ruling sets: recorded-and-reconstituted, or fail-loud under strict replay.
   Graduation blocker for the dispatch tier.

## Recommendation

Rule the rf2-yigokd / rf2-70h9wn recording question first — it sets the replay
bar for everything here, and it must be fixed for the shipped surface
regardless of this EP's fate. Then resolve the overlay open issues against the
shipped baseline: every stubbing journey is served today, so adoption of any
new tier is an ergonomics decision to be demand-gated, not a capability gap to
be closed. If frame-scoped `:registrations` are ruled in, prefer the
trailing-image lowering, which inherits EP-0026's semantics wholesale. The
inline-coordinates question resolves independently via Open Issue 5's three
paths, the first of which costs one verification test.
