# EP-0011: Uniform Async Reply Envelope

Status: final
Type: standards-track

> **`final` means the decisions are settled (2026-06-11).** Mike green-lit
> actioning this EP on 2026-06-11; the full lowering chain (slices .1–.8) then
> landed and the final correctness review (`rf2-zqefg3.9`) and completeness
> review (`rf2-zqefg3.10`) both passed clean. The canonical contract — the
> uniform reply map shape, the reply target, the closed status taxonomy,
> work-id correlation, mandatory stale suppression, and the reply-mapping
> functor law — lives in its primary normative home,
> [`spec/Managed-Effects.md` §Property 9](../../spec/Managed-Effects.md#9-uniform-reply-envelope-async-completions),
> as the ninth managed-effect property. Where this EP and the spec differ, the
> spec governs. This EP remains the durable **rationale record**: why one
> envelope beats N effect-family callback vocabularies, the alternatives
> considered, and the cross-family motivation.
>
> **Every managed async family now lowers onto the envelope.** All four shipped
> async surfaces — HTTP ([014](../../spec/014-HTTPRequests.md)), resources +
> mutations ([016](../../spec/016-Resources.md)), machine async work
> ([005](../../spec/005-StateMachines.md)), and route loaders
> ([012](../../spec/012-Routing.md)) — consume the one shared
> `re-frame.reply` substrate, carry property 9 with their family-specific
> work-id tuple, and back-reference
> [`Managed-Effects.md` §The uniform reply envelope](../../spec/Managed-Effects.md#the-uniform-reply-envelope)
> from their own spec. The "any future surface inherits all nine properties"
> claim is conformance-proven by the test-only managed-timer probe
> (`re-frame.timer-probe`, slice .6). A **public** managed-timer surface
> (`:rf.timer/after`) and its `:rf.timer/*` Conventions reservation are
> deliberately deferred to `rf2-5wuikc` (EP-0003 test-only-instance precedent),
> so the timer carries no shipped Conventions reservation by design, not by
> omission.
>
> **`final` settles the decisions; it does not on its own assert the build is
> gap-free (EP-0005 pattern).** Both non-blocking follow-ups have since been
> resolved — the P3 observability follow-up and the P4 cosmetic
> one-name-per-fact tidy. Both are tracked in the
> [Implementation errata](#implementation-errata) ledger below — neither reopened
> a ruling.
>
> Plain-language summary: when framework-managed async work completes, it
> reports back as one standard reply map delivered to one standard reply target.
> HTTP callbacks, resource replies, timers, route loaders, and machine
> completions may keep their public conveniences, but those conveniences lower
> to the same causal continuation shape.

## Implementation errata

The EP decisions are **final** and the lowering chain has shipped: all four
managed async families (HTTP, resources + mutations, machines, route loaders)
complete through the one shared `re-frame.reply` substrate, each carrying
property 9 and its work-id tuple, and the inheritance claim is
conformance-proven by the test-only managed-timer probe. The final correctness
review (`rf2-zqefg3.9`) and this completeness review (`rf2-zqefg3.10`) both
passed clean with no state-safety or correctness defect.

The items below carry out the settled design; neither reopens a ruling or
changes a contract. They are recorded here per the EP-0005 final-with-errata
pattern: finalizing the *decisions* does not, on its own, assert the
*implementation* is gap-free.

- **`rf2-lohbfg`** *(RESOLVED — P3, observability, behaviourally safe)* — wire
  the machine `stale-spawn-reply` into the one reachable machine-supersession
  path: a spawned child reaching a `:final?` leaf *after* its parent was
  already destroyed. This path was previously handled in `finalize-machine` by
  silently reducing `:on-done` to identity when the parent snapshot is `nil` —
  **behaviourally safe** (no parent to mutate, no app-state corruption) and so
  already meeting the §Stale suppression clauses (1) app target not run and (5)
  no app mutation, but it did not yet emit the `:status :stale` reply (clause 2)
  nor the stale-suppression trace joined to `:work/id` (clause 4) that the
  machine `:after`-timer stale path emits. **Now resolved:** `finalize-machine`
  gates on parent liveness (`parent-live?`) and, for a stale late completion,
  builds `stale-spawn-reply` and emits the `:rf.machine/done` trace carrying the
  reply-envelope `:rf.reply/status :stale` / `:rf.reply/work-status :suppressed`
  facts plus the carried/current generation correlation joined to `:work/id` —
  the spawn analogue of the `:after` path's `:rf.machine.timer/stale-after`
  trace, so all five §Stale suppression clauses now hold for the spawn path.
  The machine completion model is synchronous, so a genuinely-late completion
  arriving after the child itself was destroyed is not reachable;
  parent-destroyed-before-child-finishes was the only live case.
- **`rf2-mdjzs0`** *(RESOLVED — P4, cosmetic, one-name-per-fact tidy)* — converge
  the unqualified `:work-id` spelling that survived in several resource /
  mutation **trace-tag** and internal in-flight-bookkeeping maps onto the
  qualified `:work/id`. The **durable** identity was already uniformly
  `:work/id` everywhere it matters (the verification payload, the entry's
  `:current-work`, the ledger row key, and the canonical reply map), so there
  was no second stale-suppression key and no second identity on durable state —
  this was trace-stream readability only ([EP-0007](EP-0007-one-name-per-fact.md)).
  **Now resolved:** the resource / mutation reply-envelope identity is the
  qualified `:work/id` at every emit and consume site, and the Xray
  reply-envelope reader's tolerance for the bare `:work-id` was dropped — it now
  reads only the canonical `:work/id` (the unqualified spelling is no longer
  accepted). The remaining bare `:work-id` keys are the internal in-flight
  bookkeeping maps, which are not the identity and are deliberately retained.

The two **decision-level** Open Issues that did not resolve to a contract here —
the `:rf.runtime/work-ledger` multi-writer authority path and the streaming /
multi-reply sibling EP — are recorded honestly as future-EP questions in
[§Open Issues](#open-issues), not as build errata; each carries a recommendation
and is dispositioned to a named future trigger.

## Abstract

Every framework-managed asynchronous effect completes through one envelope.
re-frame2 already has the right foundation for large SPAs: event handlers return
data, effects are interpreted at the boundary, and asynchronous work comes back
as events rather than hidden callbacks mutating state. The remaining problem is
that each asynchronous effect family spells the same continuation idea
differently. Managed HTTP has `:on-success` and `:on-failure`; resources have
internal success/failure/abort reply events; mutations have internal
success/failure reply events; both are tied to a work ledger. Machine `:after`
timers carry epochs; route loaders carry nav-tokens; spawned actors notify
parents through machine-specific completion hooks.

This EP defines a uniform async reply envelope. A managed async effect declares
one reply target and one optional work id. The runtime completes that target
with a standard reply map containing outcome status, value and/or error data,
work-ledger correlation, causal completion metadata,
cancellation/staleness information, and tracing metadata. Existing public APIs
remain as compatibility sugar that lower to the envelope.

The proposal makes "event replies are causal continuations" a framework-wide
law instead of an effect-family convention. The payoff is one substrate for
work id correlation, stale suppression, cancellation, tracing, SSR preload
waiting, resource hydration, mutation settlement, and deterministic replay
metadata.

## Problem Statement

Managed asynchronous effects all need the same facts:

- what work was started;
- which frame and continuation own the completion;
- how the work correlates with a ledger row or in-flight handle;
- whether a late completion is still current;
- whether the work succeeded, failed, was cancelled, or became stale;
- which causal world inputs, such as completion time, may affect durable state;
- which trace rows describe issuance, retry, cancellation, suppression, and
  completion.

Today those facts are distributed across effect-family-specific vocabularies.
HTTP success/failure callbacks, resource internal replies, route nav-token
wrappers, machine timer epochs, and actor completion hooks all express "when
this finishes, dispatch a causal reply", but they do not share a reply map or a
target descriptor.

Concretely, the same fact — "when this work finishes, dispatch a causal reply,
and drop it if it is no longer current" — is spelled four ways today:

| Family | Continuation spelling today | Staleness identity | Source |
|---|---|---|---|
| Managed HTTP | `:on-success` / `:on-failure` event vectors, or the co-located `(:rf/reply msg)` merge | `:request-id` (abort correlation only) | [Spec 014](../../spec/014-HTTPRequests.md) |
| Resources / mutations | `:rf.resource.internal/succeeded` / `failed` / `aborted` and `:rf.mutation.internal/succeeded` / `failed` reply events carrying `:work-id` + `:generation` | `:work/id` embedding the generation | [Spec 016](../../spec/016-Resources.md), EP-0003 |
| Machine `:after` timers | synthetic timer event validated against the per-path `:rf/after-epoch` counter | declaring path + epoch | [Spec 005](../../spec/005-StateMachines.md) |
| Route-owned work | downstream dispatch threaded through `:rf.route/with-nav-token`; nav-token-guarded internal settles | `:nav-token` | [Spec 012](../../spec/012-Routing.md) |

Under this proposal all four complete through one shape — a `:rf/reply-to`
target plus a `:work/id`, completed with one reply map, suppressed by one
data-only gate check. The per-family spellings survive as sugar; the substrate
is shared.

That duplication makes the most important runtime concerns harder than they
should be:

- Stale suppression must be reimplemented for resources, routes, timers, and
  actor-bound requests.
- Cancellation is easy to treat as correctness, even though cancellation is only
  an optimization and stale suppression is the correctness boundary.
- Work-ledger correlation risks drifting into several near-identical identities
  such as request id, work id, stale key, generation, actor id, and nav-token.
- SSR preload and hydration need to know which async work blocks rendering, but
  each subsystem exposes a different completion surface.
- Tracing and error promotion need to classify completion status without
  decoding every effect family's private callback shape.
- EP-0010 causal world inputs need one place to carry completion facts such as
  `:started-at`, `:completed-at`, and `:deadline-at`.

Large SPA architecture benefits when the remaining ambient concepts are values
with laws. The continuation of managed async work is one of those concepts.

## Motivation

The core runtime can be read as a fold:

```text
next-frame-state = transition(previous-frame-state, causal-event)
```

Effects are data emitted by that fold and interpreted by the host. Async
completion is not a side channel; it is another causal event. That is the
important re-frame choice to preserve.

The missing abstraction is the shape of that causal event. If every effect
family invents its own callback slots, the framework gets N versions of the
same substrate. A uniform reply envelope turns the continuation into ordinary
data:

```clojure
{:rf/reply-to [:article/load-replied {:id 42}]
 :work/id     [:rf.work/http :article/by-id 42 7]}
```

and turns completion into an ordinary reply map:

```clojure
{:status          :ok
 :value           {:title "Welcome"}
 :work/id         [:rf.work/http :article/by-id 42 7]
 :attempt         1
 :rf.frame/id     :app/main
 :started-at      1781078400123
 :completed-at    1781078400456
 :correlation     {:request-id [:article/by-id 42]
                   :generation 7}
 :stale?          false}
```

The work ledger proposed by Resources is the natural durable substrate: a work
row is the reified continuation of a managed async effect. This EP generalizes
that idea beyond resources without forcing every public API to look the same on
day one.

## Goals

- Define one reply target shape for managed async effects.
- Define one reply map shape for async completions.
- Preserve events as the continuation mechanism.
- Make `:work/id` the canonical correlation identity for ledger-backed work.
- Make stale suppression mandatory and cancellation opportunistic.
- Carry EP-0010 causal completion metadata on async replies.
- Let HTTP, resources, mutations, timers, routing, and machines share tracing
  and status classification.
- Preserve existing public HTTP callback APIs as compatibility sugar where
  useful.
- Make reply-target mapping law-checkable, so wrappers compose predictably.
- Give SSR preload, hydration, restore, and Xray one work/reply vocabulary.

## Non-Goals

- This EP does not introduce promises, monads, async/await, callbacks, or
  channels into the app-facing event model.
- This EP does not add result binding between effects. The `:fx` vector stays a
  sequence without data flow between entries; one effect's reply never feeds a
  sibling effect in the same effect map. The envelope unifies the *continuation
  slot* — where completion is dispatched and what it carries — and nothing
  more. The only composition mechanism remains the next causal event.
- This EP does not remove `:on-success` or `:on-failure` from public HTTP APIs.
- This EP does not define the full `:rf.runtime/work-ledger` schema; it defines
  the reply surface that ledger-backed work uses.
- This EP does not make every synchronous effect produce a reply.
- This EP does not cover streaming or multi-reply protocols. A stream may use
  the same work id and status taxonomy, but per-chunk delivery is a separate
  shape.
- This EP does not make cancellation reliable. Hosts may fail to cancel work
  already in flight; stale suppression remains the correctness rule.
- This EP does not define a new time representation. `:started-at`,
  `:completed-at`, and `:deadline-at` carry EP-0010 causal time values; the
  current recommended representation is epoch milliseconds supplied by the
  triggering or reply token, not fresh ambient clock reads.

## Relationships

- **[EP-0003](EP-0003-resource-queries.md) / [Spec 016](../../spec/016-Resources.md)
  (final).** The frame work ledger is this EP's durable
  substrate: a ledger row *is* the reified continuation of a managed async
  effect, and the row's `:reply-to` field is this EP's reply target made
  durable. This EP rides that design — same `:work/id`, same
  stale-suppression rule, same host-handle exclusion — and adds the causal
  reply shape on top. That includes the current mutation slice in Spec 016:
  mutation instance rows point at `:current-work`, their in-flight writes use
  `:work/kind :mutation`, and their internal replies verify frame, work id, and
  generation before settling. This EP MUST land with or after the EP-0003
  work-ledger slice and MUST NOT introduce a parallel correlation store.
- **[EP-0016](EP-0016-resource-mutation-completion.md) (final).** EP-0016's
  call-site mutation `:reply-to` is the concrete resources/mutations slice of
  this envelope: its reply map is designed to *be* (or trivially lower to) this
  EP's reply shape, delivered to an event target after stale suppression. The
  two proposals must converge on one reply vocabulary (one-name-per-fact) —
  whichever graduates first, the other cites its shape rather than forking.
  This EP remains the cross-family envelope proposal (HTTP, timers, routing,
  machines); EP-0016 covers only the mutation instance.
- **[EP-0010](EP-0010-causal-world-inputs.md) (final).** Replies are causal
  tokens under EP-0010; completion facts that affect durable state ride the
  reply map. This EP uses EP-0010's suffixless durable timestamp vocabulary:
  `:started-at`, `:completed-at`, and `:deadline-at`.
- **[Managed-Effects](../../spec/Managed-Effects.md).** This EP adds the ninth
  property to the existing eight-property managed-effect checklist; conforming
  surfaces inherit it the same way they inherit the other eight.
- **[Spec 014](../../spec/014-HTTPRequests.md), [Spec 005](../../spec/005-StateMachines.md),
  [Spec 012](../../spec/012-Routing.md).** Their existing continuation
  vocabularies (`:on-success` / `:on-failure` and the co-located `:rf/reply`
  merge; `:after` epochs and spawn completion; nav-token threading and
  settles) become public sugar or internal instances lowering onto the
  envelope. No user-facing break is required.
- **[EP-0002](EP-0002-frame-target-resolution.md) (final).** The reply map's
  `:rf.frame/id` field is the canonical carried frame stamp; this EP adds no
  second frame spelling.
- **[EP-0006](EP-0006-runtime-subsystem-contract.md) (final).** The
  multi-writer authority question for `:rf.runtime/work-ledger` (see Open
  Issues) is the runtime-subsystem contract's to settle.
- **[EP-0007](EP-0007-one-name-per-fact.md) (active).** The one-attempt-one-
  `:work/id` rule, and the rejection of `:stale-key`-style synonyms, descend
  directly from the one-name-per-fact rulebook.
- **[EP-0014](EP-0014-derivation-and-process-algebra.md) (final).** Any
  process-shaped surface that performs managed async work completes through
  this envelope; the two proposals share the work-ledger generalization
  rather than each defining its own completion shape.

## Definitions

**Managed async effect**

A managed external effect whose lifecycle crosses event boundaries and whose
completion is owned by the framework. Examples include managed HTTP requests,
resource fetches, mutation writes, managed timers, route loader work, spawned
actor work, and machine delayed transitions.

**Reply target**

Data that tells the runtime where to dispatch a completion. The canonical public
sugar is an event vector prefix under `:rf/reply-to`.

**Reply envelope / reply map**

The data map delivered when managed async work completes. It contains one
`:status` plus the value, error, work correlation, causal metadata, and
suppression/cancellation facts needed by reducers and tools.

**Work id**

A stable, `=`-comparable identity for one attempt of work, carried as
`:work/id`. When a work-ledger row exists, `:work/id` is the row key and the
stale-suppression identity. There must not be a second synonym such as
`:stale-key` for the same attempt.

**Generation**

A monotonic discriminator embedded in or associated with a work id when the same
logical resource, route, actor, or timer can launch several attempts over time.
Generation-like values must not be recycled while an out-of-frame continuation
could still carry the old value.

**Causal completion metadata**

World inputs from EP-0010 captured on the completion token, such as start time,
completion time, deadline, generated ids, or host status facts that may affect
durable frame-state.

**Stale reply**

A completion whose correlation no longer matches the live frame-state: for
example a resource generation was superseded, a route nav-token changed, a
machine `:after` epoch no longer matches, or a frame was restored past the work.
A stale reply must not mutate the user-visible target state.

**Cancellation**

A request to stop host work. Cancellation may abort a fetch, clear a timer, or
destroy an actor, but the runtime must still handle the case where the host
completion arrives later. Cancellation is an optimization; stale suppression is
the correctness boundary.

**Public compatibility sugar**

An app-facing source form that remains convenient or migration-friendly but
lowers to `:rf/reply-to` plus the reply map internally. HTTP `:on-success` and
`:on-failure` are the main examples.

## Proposed Solution

Add a ninth property to the Managed-Effects contract: every managed async
effect has a uniform reply envelope. A conforming effect family either exposes
`:rf/reply-to` directly or lowers its public callback vocabulary to
`:rf/reply-to` internally.

At issuance time, the runtime records a work id, frame id, target, owner/cause
metadata, and any suppression gates. At completion time, it builds a reply map,
checks suppression gates, updates the work ledger if present, emits trace rows,
and dispatches the reply target only if delivery is still current.

The public source form can stay familiar:

```clojure
{:fx [[:rf.http/managed
       {:request    {:method :get :url "/api/articles/42"}
        :decode     :app/article
        :on-success [:article/loaded 42]
        :on-failure [:article/load-failed 42]}]]}
```

but the internal shape is one continuation:

```clojure
{:fx [[:rf.http/managed
       {:request     {:method :get :url "/api/articles/42"}
        :decode      :app/article
        :work/id     [:rf.work/http :article/by-id 42 1]
        :rf/reply-to [:rf.http/compat-reply
                      {:on-success [:article/loaded 42]
                       :on-failure [:article/load-failed 42]}]}]]}
```

The compatibility reply handler then preserves the public HTTP contract while
the runtime, ledger, trace stream, cancellation paths, and stale-suppression
paths all see the same reply map.

## Specification

> **Graduated (2026-06-11).** This section's normative content now lives in
> [`spec/Managed-Effects.md` §Property 9](../../spec/Managed-Effects.md#9-uniform-reply-envelope-async-completions)
> and the [§The uniform reply envelope](../../spec/Managed-Effects.md#the-uniform-reply-envelope)
> section beneath it — the reply map, reply target, closed status taxonomy,
> work-id correlation, mandatory stale suppression, work-ledger integration,
> causal completion metadata, tracing, the reply-mapping functor law, and the
> SSR/restore rule. Where this EP and the spec differ, the spec governs. The
> text below is retained as the **rationale record** that motivated each clause.

### Managed-Effects Property 9: Uniform Reply Envelope

`spec/Managed-Effects.md` adds this property (graduated; the spec wording governs):

> A managed async effect MUST complete through the uniform reply envelope. The
> effect either accepts `:rf/reply-to` directly or defines public sugar that
> lowers to `:rf/reply-to`. Completion MUST produce a reply map with a single
> `:status`, value and/or error data, work correlation, causal completion metadata,
> and any cancellation/staleness facts. Ledger-backed effects MUST correlate
> by `:work/id`.

This property applies only to effects with asynchronous completion. A one-shot
synchronous effect that performs work during the `:fx` walk and has no later
reply does not need `:rf/reply-to`.

### Reply Target

The canonical public target key is `:rf/reply-to`.

The short form is an event vector prefix:

```clojure
{:rf/reply-to [:article/load-replied {:id 42}]}
```

On live completion, the runtime dispatches the target event with the reply map
appended as the final event argument:

```clojure
[:article/load-replied
 {:id 42}
 {:status          :ok
  :value           {:title "Welcome"}
  :work/id         [:rf.work/http :article/by-id 42 1]
  :completed-at 1781078400456}]
```

The descriptor form is available for framework internals and future public
surfaces that need explicit delivery options:

```clojure
{:rf/reply-to {:event    [:article/load-replied {:id 42}]
               :delivery :append
               :suppress {:route/nav-token "nav-7"
                          :generation      1}}}
```

Descriptor fields:

| Field | Required | Meaning |
|---|---:|---|
| `:event` | yes | Event vector prefix to complete. |
| `:delivery` | no | `:append` by default. Compatibility adapters may use other delivery internally to preserve old public event shapes. |
| `:suppress` | no | Data-only gates that must still match before the reply is delivered. |
| `:dispatch-stale?` | no | `false` by default. Framework tests/tools may opt into receiving stale envelopes; app targets should not. |

Effect families MAY expose only the short vector form publicly while using the
descriptor form internally.

### Reply Map Fields

The canonical reply map is data only. It must not contain functions, promises,
AbortControllers, timer handles, DOM nodes, or other host resources.

```clojure
{:status          :ok | :partial | :error | :cancelled | :stale
 :value           value-or-nil
 :error           error-map-or-nil
 :work/id         work-id-or-nil
 :work/kind       :http | :resource | :mutation | :timer | :route | :machine | ...
 :work/status     :completed | :failed | :timed-out | :suppressed | :cancelled
 :attempt         positive-int-or-nil
 :rf.frame/id     frame-id
 :started-at      started-at-or-nil
 :completed-at    completed-at-or-nil
 :deadline-at     deadline-at-or-nil
 :correlation     data-only-map
 :stale?          boolean
 :stale/reason    keyword-or-nil
 :cancelled?      boolean
 :cancel/reason   keyword-or-nil
 :trace           data-only-trace-summary
 :meta            effect-family-data}
```

Required fields:

- `:status` is always required.
- `:work/id` is required for ledger-backed work and SHOULD be present for every
  managed async effect that can be correlated.
- `:rf.frame/id` is required when the effect is frame-scoped.
- `:value` is present for `:status :ok` and `:status :partial`.
- `:error` is present for `:status :error`, present with structured partial
  diagnostics for `:status :partial`, and MAY also carry compatibility failure
  data for `:status :cancelled`.
- `:started-at`, `:completed-at`, and `:deadline-at` are causal
  metadata when those facts affect durable state. If the effect family does not
  use a field durably, it may omit it.

Optional fields should be omitted when absent rather than filled with placeholder
sentinels, except where a schema for a specific spec requires nilable keys.

### Status Taxonomy

The reply `:status` vocabulary is closed:

| Reply status | Meaning | Value/error convention |
|---|---|---|
| `:ok` | Work completed successfully and the reply is current. | `:value` present; `:error` absent. |
| `:partial` | Work completed with usable value data and structured family-specific problems. The reply is current, but the effect family must decide how partial data is installed. | `:value` present; `:error` present with a family-specific `:kind` such as `:rf.graphql/partial-success`. |
| `:error` | Work completed with a failure and the reply is current. | `:error` present with a family-specific `:kind`. |
| `:cancelled` | Work was intentionally cancelled while still correlated with the target. | `:cancel/reason` present; `:error` MAY carry compatibility failure data. |
| `:stale` | Work completed or was observed after its correlation became obsolete. | `:stale? true`; `:stale/reason` present; no app-state mutation. |

`:partial` exists to keep this envelope transport-neutral. GraphQL responses can
legitimately contain both `data` and `errors`; a future GraphQL resource
transport must not be forced to pretend that shape is plain `:ok` or plain
`:error`. A resource policy may lower a partial reply into loaded data,
`:refresh-error`, or first-load `:error` according to EP-0003's deferred
GraphQL rules, but the reply envelope itself can represent value-and-errors.

Timeout is not a top-level reply status. It is an error kind or work status:

```clojure
{:status      :error
 :error       {:kind :rf.http/timeout
               :limit-ms 30000
               :elapsed-ms 30012}
 :work/status :timed-out}
```

HTTP abort compatibility may still surface the HTTP category inside `:error`:

```clojure
{:status        :cancelled
 :cancelled?    true
 :cancel/reason :actor-destroyed
 :error         {:kind :rf.http/aborted
                 :reason :actor-destroyed}}
```

Stale wins over the natural completion status for delivery purposes. A late
successful, partial, or failed completion for a superseded resource generation
is not `:ok`, `:partial`, or `:error`; it is `:stale`/`:suppressed` in the
ledger and trace stream, and the app target is not dispatched unless explicitly
opted in for testing or tooling.

### Work Id Correlation

Ledger-backed async work MUST use `:work/id` as the single attempt identity.
The work id is:

- stable under `=`;
- serializable as EDN when it appears in runtime-db or trace data;
- scoped enough to avoid collision inside a frame;
- the key used by the work ledger;
- the key used by stale suppression for that attempt.

Resource work should embed the scoped resource key and generation:

```clojure
[:rf.work/resource scoped-resource-key generation]
```

Mutation work in the current Resources artefact reuses the resource work-id head
with a mutation-instance key. The ledger row distinguishes the writer with
`:work/kind :mutation`:

```clojure
[:rf.work/resource [:rf.mutation instance-id] generation]
```

Route loader work should embed the route id and nav-token:

```clojure
[:rf.work/route route-id nav-token loader-id]
```

Timer work should embed the logical timer identity and generation or epoch:

```clojure
[:rf.work/timer :search/debounce query-id generation]
```

Machine work should embed the actor id and the work-bearing state or invoke id:

```clojure
[:rf.work/machine actor-id [:authenticating :fetch-profile] generation]
```

The exact tuple shape is owned by each effect family, but the rule is common:
one attempt has one work id. If another public identity exists, such as an HTTP
`:request-id`, it is correlation metadata, not a second stale-suppression key.

### Work Ledger Integration

When a work-ledger row exists, issuance writes or joins a non-terminal row:

```clojure
{:work/id       [:rf.work/resource scoped-resource-key 4]
 :work/kind     :resource
 :work/frame    :app/main
 :status        :running
 :owners        #{[:route :route/article "nav-12"]}
 :causes        [[:route-entry :route/article "nav-12"]]
 :cancellable?  true
 :started-at    1781078400123
 :deadline-at   1781078405123
 :reply-to      {:event [:rf.resource.internal/replied
                         {:resource/key scoped-resource-key
                          :generation   4
                          :rf.frame/id  :app/main}]}}
```

The `:reply-to` field is where the ledger row and the reply envelope visibly
become one fact: the durable row carries the continuation that this EP's
completion path consumes. Timestamp field spellings intentionally match
EP-0010 and the graduated Spec 016 ledger row; the values remain causal epoch
millisecond readings unless a later accepted EP changes the time representation
for the whole durable timestamp family.

The `:rf.resource.internal/replied` event id in this illustrative ledger row is
the candidate consolidated resource reply target. Shipped Spec 016 may keep
separate success/failure/abort internal event ids until that consolidation is
ruled.

Completion updates that row before, or atomically with, delivery:

```clojure
{:work/id          [:rf.work/resource scoped-resource-key 4]
 :status           :completed
 :completed-at     1781078400456
 :outcome          {:status :ok}}
```

Terminal ledger statuses MAY be more operational than reply statuses:

| Ledger status | Typical reply status |
|---|---|
| `:completed` | `:ok` or `:partial` |
| `:failed` | `:error` |
| `:timed-out` | `:error` with timeout kind |
| `:cancelled` | `:cancelled` |
| `:suppressed` | `:stale` |

A `:partial` reply is still operationally completed: the host work returned a
current response and the effect family received usable value data. The partial
condition lives in the reply `:status` and `:error` payload; the work-ledger
`:status` remains `:completed` unless the effect family also needs a narrower
ledger outcome under `:outcome`.

The ledger must not store host handles. AbortControllers, timer handles,
transport promises, and subscriptions remain host-transient side-table entries
keyed by `[frame-id work-id]`.

### Causal Completion Metadata

Managed async replies are causal tokens under EP-0010. Any host fact from the
completion that can affect durable app-db, runtime-db, resource entries,
machine snapshots, route state, or ledger rows MUST be placed on the reply map
or supplied as an explicit replayable coeffect derived from it.

Correct:

```clojure
(rf/reg-event-fx :article/replied
  (fn [{:keys [db]} [_ {:keys [id]} reply]]
    (case (:status reply)
      :ok
      {:db (assoc-in db [:articles id]
                     {:data      (:value reply)
                      :loaded-at (:completed-at reply)})}

      :error
      {:db (assoc-in db [:articles id :error] (:error reply))}

      {:db db})))
```

Incorrect:

```clojure
;; Do not compute durable freshness from a fresh ambient read here.
{:db (assoc-in db [:articles id :loaded-at] (interop/now-ms))}
```

The event envelope that dispatches the reply may also carry an enqueue time.
That time is distinct from `:completed-at` when the host completion happened
before the runtime enqueued the reply. Specs may decide which timestamp a
durable field uses, but the value must be causal data.

### Stale Suppression

Every effect family that can be superseded MUST define data-only suppression
gates and validate them before durable writes. Examples:

- resource `:work/id` plus generation still matches the current resource entry;
- mutation `:work/id` plus generation still matches the current mutation
  instance row's `:current-work`;
- route `:nav-token` still matches `[:rf.runtime/routing :current :nav-token]`;
- machine `:after` epoch and declaring path still match the active snapshot;
- actor id still names a live actor when actor-bound work replies;
- frame id still names a live frame.

If validation fails:

1. the app reply target MUST NOT run;
2. the reply outcome becomes `:status :stale`;
3. the ledger row, if present, reaches `:suppressed`;
4. the trace stream emits a stale-suppression row with the carried and current
   correlation facts;
5. no user-visible app-db or runtime-db mutation may be produced by the stale
   reply, except the framework-owned ledger/trace bookkeeping.

Cancellation does not weaken this rule. A cancelled fetch may still produce a
late host completion; it is accepted only if its correlation is still live and
the effect family intentionally delivers cancellation to the target.

### Cancellation

Cancellation is represented as data, not as the absence of a reply.

Explicit user cancellation that is still live may dispatch:

```clojure
[:search/replied
 {:query "abc"}
 {:status        :cancelled
  :cancelled?    true
  :cancel/reason :user
  :work/id       [:rf.work/http :search "abc" 8]
  :completed-at 1781078400999}]
```

Supersession cancellation should usually suppress the old app reply:

```clojure
{:status        :stale
 :stale?        true
 :stale/reason  :request-id-superseded
 :work/id       [:rf.work/http :search "abc" 7]
 :work/status   :suppressed}
```

Actor-destroy cancellation is delivered according to the owning surface. For
HTTP inside a spawned actor, Spec 005 and Spec 014 already define the abort
cascade. Under this EP, the abort cascade completes through the same envelope,
with `:status :cancelled` if the actor-bound target is still meaningful, or
`:status :stale`/`:suppressed` when the actor teardown makes the target
obsolete before delivery.

### Tracing

Managed async effect families MUST emit trace rows using the reply envelope
facts rather than private callback facts. At minimum:

- issuance/start with `:work/id`, frame, owner/cause, and target summary;
- retry or intermediate transition where applicable;
- cancellation requested, including reason and whether a host handle existed;
- completion classified as `:ok`, `:partial`, `:error`, `:cancelled`, or
  `:stale`;
- stale suppression with carried/current correlation facts;
- delivery to a target, or explicit non-delivery.

Trace rows must use the shared elision walker for wire-bearing values. Large or
sensitive `:value`, `:error`, params, scopes, request bodies, and route params
must be summarized or redacted by the same privacy/size rules used elsewhere.

### Public Compatibility Sugar

Public APIs may keep ergonomic or migration-friendly forms, provided they lower
to the uniform envelope.

HTTP `:on-success` and `:on-failure` remain valid:

```clojure
{:fx [[:rf.http/managed
       {:request    {:method :post :url "/api/login" :body credentials}
        :decode     :app/session
        :on-success [:auth/login-succeeded]
        :on-failure [:auth/login-failed]}]]}
```

The owning HTTP implementation lowers them to a single reply target:

```clojure
{:fx [[:rf.http/managed
       {:request     {:method :post :url "/api/login" :body credentials}
        :decode      :app/session
        :work/id     [:rf.work/http :auth/login login-attempt]
        :rf/reply-to [:rf.http/compat-reply
                      {:on-success [:auth/login-succeeded]
                       :on-failure [:auth/login-failed]}]}]]}
```

The compatibility handler receives the canonical reply:

```clojure
[:rf.http/compat-reply
 {:on-success [:auth/login-succeeded]
  :on-failure [:auth/login-failed]}
 {:status          :ok
  :value           session
  :work/id         [:rf.work/http :auth/login login-attempt]
  :completed-at 1781078400456}]
```

and preserves the public HTTP event shape promised by Spec 014:

```clojure
[:auth/login-succeeded {:kind :success :value session}]
```

For migration from re-frame v1-style HTTP libraries, an additional compatibility
adapter may unwrap further to old positional payload shapes, but that adapter is
public sugar. It must not become the internal managed-effect contract.

### SSR, Preload, Hydration, And Restore

SSR and preload integrations should observe work ledger rows and reply statuses,
not effect-family callback slots.

For route resources:

- server route handling enqueues blocking resource work;
- SSR waits for ledger rows associated with the current route/nav-token to
  become terminal;
- successful replies update resource entries with causal `:completed-at`;
- failures settle as structured resource or route errors;
- stale or superseded replies are suppressed by work id/generation/nav-token;
- hydration serializes the allowed resource projection and non-terminal work
  summaries, not host handles.

Hydration and epoch restore must not revive host work. Non-terminal restored
rows are reconciled as dangling/superseded unless a spec explicitly defines a
safe live handoff. A pre-restore host completion that later arrives cannot
mutate the restored frame because its work id/generation/token cannot match the
post-restore live correlation.

Timers follow the owning spec's SSR rule. Machine `:after` timers are not
scheduled on the server. A future general timer surface must either no-op under
SSR or define an explicit server-safe completion policy; it still uses the
reply map if it completes.

### Reply Mapping And Functor Law

Because the reply target is plain data, wrapping or relocating a continuation
is a pure data transform — the role `Cmd.map` plays in Elm's command algebra.
A feature module can take an effect description whose reply lands on a child
event and re-target it onto a parent event without touching issuance or
correlation.

The runtime SHOULD expose pure helpers for transforming reply targets. The
helper may be public API or implementation-internal, but the law is normative:
mapping a target changes only the completed event, not issuance, work id,
status classification, cancellation, stale checks, or tracing.

In abstract form:

```text
complete(map-completed-event(f, target), reply)
  == f(complete(target, reply))
```

Identity and composition must hold:

```text
map-completed-event(identity, target) == target
map-completed-event(comp(f, g), target)
  == map-completed-event(f, map-completed-event(g, target))
```

Concrete example:

```clojure
(def target
  {:event [:article/replied {:id 42}]
   :delivery :append})

(def reply
  {:status :ok
   :value {:article {:id 42 :title "Welcome"}}
   :work/id [:rf.work/http :article/by-id 42 1]})

(defn select-article-event [event]
  (let [reply (peek event)]
    (conj (pop event) (update reply :value :article))))

(= (rf.reply/complete
     (rf.reply/map-completed-event select-article-event target)
     reply)
   (select-article-event
     (rf.reply/complete target reply)))
```

Implementations do not have to store arbitrary functions in effect maps to
satisfy this law. A helper can rewrite source forms to named adapter events, or
the law can be exercised in tests over the internal completion function. The
required property is that reply-target wrappers compose predictably and do not
create hidden callback semantics.

## Examples

### Managed HTTP Lowering

Public source:

```clojure
(rf/reg-event-fx :article/load
  (fn [{:keys [db]} [_ id]]
    {:db (assoc-in db [:articles id :status] :loading)
     :fx [[:rf.http/managed
           {:request    {:method :get
                         :url    (str "/api/articles/" id)}
            :decode     :app/article
            :retry      {:on #{:rf.http/transport :rf.http/http-5xx}
                         :max-attempts 3}
            :on-success [:article/load-succeeded id]
            :on-failure [:article/load-failed id]}]]}))
```

Internal lowering:

```clojure
[:rf.http/managed
 {:request     {:method :get :url "/api/articles/42"}
  :decode      :app/article
  :retry       {:on #{:rf.http/transport :rf.http/http-5xx}
                :max-attempts 3}
  :request-id  [:article/by-id 42]
  :work/id     [:rf.work/http :article/by-id 42 1]
  :rf/reply-to [:rf.http/compat-reply
                {:on-success [:article/load-succeeded 42]
                 :on-failure [:article/load-failed 42]}]}]
```

Canonical completion:

```clojure
[:rf.http/compat-reply
 {:on-success [:article/load-succeeded 42]
  :on-failure [:article/load-failed 42]}
 {:status          :ok
  :value           {:id 42 :title "Welcome"}
  :work/id         [:rf.work/http :article/by-id 42 1]
  :work/kind       :http
  :work/status     :completed
  :attempt         1
  :rf.frame/id     :app/main
  :started-at   1781078400123
  :completed-at 1781078400456
  :correlation     {:request-id [:article/by-id 42]}}]
```

Compatibility dispatch:

```clojure
[:article/load-succeeded
 42
 {:kind :success
  :value {:id 42 :title "Welcome"}}]
```

### Resource Reply And Work Ledger

A route-owned resource ensure creates an entry and a work row:

```clojure
[:rf.resource/ensure
 {:resource :article/by-slug
  :scope    [:rf.scope/session {:tenant-id "acme"}]
  :params   {:slug "welcome"}
  :owner    [:route :route/article "nav-12"]
  :cause    [:route-entry :route/article "nav-12"]}]
```

The resource runtime allocates:

```clojure
(def scoped-key
  [:article/by-slug
   [:rf.scope/session {:tenant-id "acme"}]
   {:slug "welcome"}])

(def work-id
  [:rf.work/resource scoped-key 4])
```

and lowers to managed HTTP:

```clojure
[:rf.http/managed
 {:request     {:method :get :url "/api/articles/welcome"}
  :decode      :app/article
  :request-id  work-id
  :work/id     work-id
  :rf/reply-to [:rf.resource.internal/replied
                {:resource/key scoped-key
                 :generation   4
                 :rf.frame/id  :app/main}]}]
```

The examples in this section use the candidate consolidated
`:rf.resource.internal/replied` event. Shipped Spec 016 may keep separate
success/failure/abort internal event ids until that consolidation is ruled; the
normative point here is the reply map and `:work/id`, not the exact internal
event id.

The internal resource reply handler receives one reply map for successful,
partially successful, failed, cancelled, or stale-suppressed completions:

```clojure
[:rf.resource.internal/replied
 {:resource/key scoped-key
  :generation   4
  :rf.frame/id  :app/main}
 {:status          :ok
  :value           {:id 42 :title "Welcome"}
  :work/id         work-id
  :work/kind       :resource
  :work/status     :completed
  :completed-at 1781078400456
  :correlation     {:scope      [:rf.scope/session {:tenant-id "acme"}]
                    :generation 4
                    :owner      [:route :route/article "nav-12"]}}]
```

Before writing, the handler verifies:

- frame id matches the target frame;
- `:work/id` still equals the entry's `:current-work`;
- generation still equals the entry's generation;
- route owner token, if present, is still live or intentionally revived.

If verification passes, it updates the resource entry and ledger. If it fails,
the app resource state is unchanged and the ledger records suppression:

```clojure
{:status        :stale
 :stale?        true
 :stale/reason  :resource/generation-mismatch
 :work/id       work-id
 :work/status   :suppressed
 :correlation   {:carried-generation 4
                 :current-generation 5}}
```

### Mutation Reply

Spec 016's mutation slice is the write counterpart of resource reads. It also
lowers through managed HTTP, creates a work-ledger row, and suppresses stale
replies by work id plus generation.

```clojure
[:rf.mutation/execute
 {:mutation :article/save
  :params   {:slug "welcome" :title "Welcome"}
  :instance :form/save-1}]
```

The mutation runtime allocates a mutation instance row and a work id that reuses
the resource work-id head with a mutation-instance key:

```clojure
(def work-id
  [:rf.work/resource [:rf.mutation :form/save-1] 2])
```

Spec 016 currently lowers the write to managed HTTP with runtime-owned reply
addressing:

```clojure
[:rf.http/managed
 {:request    {:method :post :url "/api/articles/welcome"}
  :decode     :app/article
  :request-id work-id
  :on-success [:rf.mutation.internal/succeeded
               {:instance-id :form/save-1
                :mutation-id :article/save
                :work-id     work-id
                :generation  2
                :rf.frame/id :app/main}]
  :on-failure [:rf.mutation.internal/failed
               {:instance-id :form/save-1
                :mutation-id :article/save
                :work-id     work-id
                :generation  2
                :rf.frame/id :app/main}]}]
```

Those slots are the current compatibility surface; under this EP they lower to
`:rf/reply-to`, and the internal replies receive the same canonical reply facts:

```clojure
{:status       :ok
 :value        {:slug "welcome" :title "Welcome"}
 :work/id      work-id
 :work/kind    :mutation
 :work/status  :completed
 :completed-at 1781078400456
 :correlation  {:mutation/id :article/save
                :instance/id :form/save-1
                :generation  2}}
```

Before settling the mutation instance or applying patch/populate/invalidation
effects, the handler verifies that the frame matches, `:work/id` still equals
the instance row's `:current-work`, and generation still matches the instance
generation. If a re-execute or `:rf.mutation/clear` superseded the write, the
reply becomes `:status :stale`, the ledger row is marked `:suppressed`, and no
patch/populate/invalidation writes are produced.

### Timer Reply

A future managed timer surface can use the same continuation shape:

```clojure
(rf/reg-event-fx :search/query-changed
  (fn [{:keys [db]} [_ query]]
    (let [generation (inc (get-in db [:search :generation] 0))
          work-id    [:rf.work/timer :search/debounce generation]]
      {:db (-> db
               (assoc-in [:search :query] query)
               (assoc-in [:search :generation] generation))
       :fx [[:rf.timer/after
             {:ms          250
              :work/id     work-id
              :rf/reply-to {:event    [:search/debounce-replied
                                       {:generation generation}]
                            :suppress {:generation generation}}}]]})))
```

Live completion:

```clojure
[:search/debounce-replied
 {:generation 8}
 {:status          :ok
  :value           nil
  :work/id         [:rf.work/timer :search/debounce 8]
  :work/kind       :timer
  :completed-at 1781078400300
  :correlation     {:generation 8}}]
```

If generation 9 is current by the time the timer fires, the runtime emits a
stale trace and does not dispatch `:search/debounce-replied` to app code:

```clojure
{:status        :stale
 :stale?        true
 :stale/reason  :generation-mismatch
 :work/id       [:rf.work/timer :search/debounce 8]
 :work/status   :suppressed
 :correlation   {:carried-generation 8
                 :current-generation 9}}
```

Machine `:after` timers are the existing specialized instance of this pattern:
the machine carries a declaring path and `:rf/after-epoch`, validates them on
receipt, and drops stale timer events without app mutation.

### Route Loader Completion

Routing already uses nav-tokens for stale suppression. The reply envelope makes
that an ordinary suppression gate:

```clojure
(rf/reg-route
  :route/article
  {:path "/articles/:slug"
   :on-match [[:article/route-entered]]})

(rf/reg-event-fx :article/route-entered
  (fn [{runtime :rf.db/runtime} _]
    (let [{:keys [params nav-token]} (get-in runtime
                                             [:rf.runtime/routing :current])
          slug    (:slug params)
          work-id [:rf.work/route :route/article nav-token :article]]
      {:fx [[:rf.http/managed
             {:request     {:method :get :url (str "/api/articles/" slug)}
              :decode      :app/article
              :work/id     work-id
              :rf/reply-to {:event    [:article/route-replied
                                       {:slug slug :nav-token nav-token}]
                            :suppress {:route/nav-token nav-token}}}]]})))
```

The handler only sees live replies:

```clojure
(rf/reg-event-fx :article/route-replied
  (fn [{:keys [db]} [_ {:keys [slug]} reply]]
    (case (:status reply)
      :ok
      {:db (assoc-in db [:article/by-slug slug] (:value reply))}

      :error
      {:db (assoc-in db [:article/error slug] (:error reply))}

      ;; :cancelled may be visible for explicit user cancellation.
      :cancelled
      {:db (update db :article dissoc :loading)}

      ;; App handlers should not normally receive :stale; kept for explicit
      ;; testing/tooling opt-in.
      :stale
      {:db db})))
```

If the user navigates from article A to article B before A completes, the
nav-token check suppresses A's reply before this handler runs. The trace row
records `:rf.route.nav-token/stale-suppressed`-equivalent data joined to
`:work/id`.

### Machine Completion

Machine finality and spawned actor completion can be represented as reply
completion internally while preserving Spec 005's statechart surface.

```clojure
(rf/reg-machine
  :auth/main
  {:initial :authenticating
   :states
   {:authenticating
    {:spawn {:machine-id :auth/flow
             ;; Spec 005: :on-done is the success hook — a :data callback
             ;; receiving the child's :output-key result. :on-error is its
             ;; failure counterpart — a declarative transition.
             :on-done  (fn [{data :data result :result}]
                         (assoc data :session result))
             :on-error {:target :failed}}
     :after {30000 :timed-out}}

    :failed {}
    :timed-out {}}})
```

The spawned child has a work id (the third element is the spawn-bearing
node's declaring path, matching Spec 005's `:rf/invoke-id`):

```clojure
[:rf.work/machine :auth/flow#1 [:authenticating] 1]
```

When the child reaches a successful top-level final state, the runtime can form:

```clojure
{:status          :ok
 :value           {:user-id "u-42"}
 :work/id         [:rf.work/machine :auth/flow#1 [:authenticating] 1]
 :work/kind       :machine
 :work/status     :completed
 :completed-at 1781078400888
 :correlation     {:actor-id  :auth/flow#1
                   :parent-id :auth/main
                   :spawn-id  [:authenticating]}}
```

and then run the parent's `:spawn :on-done` `:data` callback with the child's
`:output-key` result as `(:value reply)`. If the parent's `:after` fires
first, the standard exit cascade destroys the child. Any late child completion
is stale because the actor id or spawn correlation is no longer live. If the
child fails through an `:error?` final state or throws, the same envelope uses
`:status :error` and drives the `:spawn :on-error` transition.

### Stale, Cancelled, And Error Handling

A single reply handler can branch on the common status taxonomy:

```clojure
(rf/reg-event-fx :upload/replied
  (fn [{:keys [db]} [_ {:keys [upload-id]} reply]]
    (case (:status reply)
      :ok
      {:db (assoc-in db [:uploads upload-id]
                     {:status :done
                      :result (:value reply)
                      :done-at (:completed-at reply)})}

      :error
      {:db (assoc-in db [:uploads upload-id]
                     {:status :failed
                      :error  (:error reply)})}

      :partial
      {:db (assoc-in db [:uploads upload-id]
                     {:status :partial
                      :result (:value reply)
                      :error  (:error reply)
                      :done-at (:completed-at reply)})}

      :cancelled
      {:db (assoc-in db [:uploads upload-id]
                     {:status :cancelled
                      :reason (:cancel/reason reply)})}

      :stale
      {:db db})))
```

An HTTP timeout:

```clojure
{:status      :error
 :work/status :timed-out
 :error       {:kind :rf.http/timeout
               :limit-ms 30000
               :elapsed-ms 30004}}
```

A user cancel:

```clojure
{:status        :cancelled
 :work/status   :cancelled
 :cancelled?    true
 :cancel/reason :user}
```

A stale route result:

```clojure
{:status       :stale
 :work/status  :suppressed
 :stale?       true
 :stale/reason :route/nav-token-mismatch
 :correlation  {:carried-token "nav-1"
                :current-token "nav-2"}}
```

## Rationale

The proposal keeps the strongest part of re-frame: asynchronous work reports
back as causal events. It does not smuggle a second callback language into the
runtime. The improvement is that "where does completion go?" becomes one value
instead of a family of near-synonyms.

The reply envelope also draws the right line between cancellation and
correctness. Hosts are allowed to fail at cancellation. A browser fetch may
complete after abort, a network response may already be queued, and a timer
callback may arrive after an exit cascade. The runtime remains correct because
the reply carries data-only correlation and suppression checks run before
delivery.

Putting EP-0010 completion metadata on the reply makes deterministic replay
more literal. Durable fields such as `:loaded-at`, `:stale-at`,
`:completed-at`, and ledger outcomes are derived from the causal reply, not
from a fresh ambient clock read in a reducer.

The work ledger becomes more than a resource implementation detail. It is the
runtime's table of live continuations. Resources were the first writer and
mutations are the second landed writer inside the Resources artefact, but the
same shape naturally accommodates route loaders, timers, spawned actors,
machine async work, and future managed background jobs.

## Alternatives Considered

### Keep effect-family callback vocabularies

This preserves local familiarity but keeps duplicating cross-cutting behavior.
Every family must re-answer stale suppression, cancellation, tracing, SSR wait
points, and causal metadata. It is easy to ship two almost-identical concepts
with different names.

### Replace replies with promises or async/await

Promises are host control flow, not re-frame causal events. They hide ordering
from the event log and do not naturally carry frame targeting, work ids,
runtime-db write authority, trace classification, or replay metadata.

### Make every async source a resource

Resources are cached read-model facts. Timers, route settles, machine child
completion, upload jobs, and actor-bound work may share the work ledger without
being resources. Collapsing them into resources would overload the resource FSM
and make non-cache workflows awkward.

### Use cancellation as the primary correctness mechanism

Cancellation cannot be trusted across hosts or races. The correct invariant is
that stale replies cannot write. Cancellation remains valuable for bandwidth,
quota, battery, and cleanup, but it is not the safety boundary.

### Keep separate success and failure target slots

Separate public handlers can be ergonomic, especially for HTTP. They should
remain as sugar. Internally, two target slots make status taxonomy, tracing,
mapping, and work-ledger completion less uniform. One reply target plus one
status field is the smaller substrate.

Plain managed HTTP does not emit `:partial`: an HTTP response is decoded as
`:ok` or projected as `:error`/`:cancelled`/`:stale` under Spec 014's current
two-slot compatibility surface. `:partial` is reserved for effect families whose
protocol can return usable data and structured problems in one completion, such
as the deferred GraphQL transport. A future public transport that can emit
`:partial` must expose either the uniform `:rf/reply-to` target or an explicit
partial-delivery rule; it must not silently choose one of HTTP's
`:on-success`/`:on-failure` slots.

### Always dispatch stale replies to app code

Dispatching stale replies by default asks app handlers to remember to ignore
them. That repeats the bug class the runtime is supposed to prevent. Stale
envelopes are useful for ledgers, traces, tests, and tools, but app reply
targets should receive only live replies unless explicitly opted in.

## Backwards Compatibility and Migration

This proposal is intentionally compatible at public API boundaries.

Existing HTTP `:on-success` and `:on-failure` source forms remain valid. The
implementation lowers them to `:rf/reply-to` and then reshapes the canonical
reply back into the event payload promised by Spec 014. Mechanical upgrades from
re-frame v1 codebases can keep familiar two-handler HTTP shapes while the
runtime gains uniform internals.

Co-located HTTP handlers that branch on `(:rf/reply msg)` remain valid as
compatibility sugar. Internally, the default target is just the originating
event plus a compatibility delivery adapter.

Resource internals may consolidate
`:rf.resource.internal/succeeded`, `:rf.resource.internal/failed`, and
`:rf.resource.internal/aborted` into one `:rf.resource.internal/replied`
handler, or may keep separate event ids that all receive the canonical reply
map. The normative rule is the reply map and work id, not the exact internal
event id split. The internal verification-payload key has since converged on the
qualified `:work/id` already used by the ledger row and this EP — one attempt
identity, one spelling, per EP-0007 (the convergence and the Xray
tolerance-drop landed under `rf2-mdjzs0`; see the
[Implementation errata](#implementation-errata) ledger).

Mutation internals follow the same compatibility posture. The current
`:rf.mutation.internal/succeeded` / `:rf.mutation.internal/failed` event ids
may remain public-internal plumbing, but they should receive the canonical
reply map and settle the mutation instance and work-ledger row by `:work/id`
plus generation.

Machine and routing public APIs do not need immediate surface changes. Their
existing nav-token, `:after` epoch, `:on-done`, and actor-destroy semantics can
lower onto reply envelope facts internally and expose the same behavior to
applications.

The compatibility posture is not "v1 callback names are the design". The design
is one causal reply envelope. Callback names are migration and ergonomics
layers over it.

## Reference Implementation / Bead Plan

Sequencing: the reply substrate lands with the EP-0003 work-ledger slice —
the ledger row and the reply envelope are the same fact, and they should be
designed in one moment rather than reconciled later.

1. Add the uniform reply envelope property to `spec/Managed-Effects.md`.
2. Define a small internal reply namespace with helpers for target
   normalization, completion, mapping, schema validation, and trace summaries.
3. Lower `:rf.http/managed` `:on-success` / `:on-failure` / co-located reply
   behavior to the uniform envelope while preserving the public Spec 014 event
   shapes.
4. Wire Resources and mutations to receive a canonical reply map and update
   `:rf.runtime/work-ledger` rows by `:work/id`.
5. Add stale-suppression helpers that take carried/current correlation data and
   produce `:status :stale` plus trace facts without dispatching app targets.
6. Adapt route nav-token wrappers and machine `:after` timer handling to share
   reply-envelope trace/status vocabulary where doing so does not perturb the
   public API.
7. Add SSR/preload wait logic that observes ledger rows and terminal reply
   statuses, not HTTP-specific callback names.
8. Document public compatibility sugar and migration examples for HTTP,
   resource-backed route loading, and mutation writes.

## Validation / Conformance

Conformance should include fixtures or tests for:

- reply map schema: exactly one valid `:status`, value/error conventions
  including `:partial` with both usable value and structured error data, and no
  host handles in durable reply data;
- HTTP lowering: `:on-success` and `:on-failure` preserve public event shapes
  while producing canonical internal replies;
- co-located HTTP default: originating-event reply still branches on
  `:rf/reply` through compatibility delivery;
- work id correlation: ledger-backed completions, including mutation writes,
  update exactly one row by `:work/id`;
- stale suppression: a superseded resource generation, mutation generation,
  route nav-token, or machine `:after` epoch does not dispatch the app target
  and records a suppressed ledger/trace outcome;
- cancellation precedence: explicit abort produces `:status :cancelled`, while
  superseded or destroyed obsolete work suppresses stale app delivery;
- EP-0010 metadata: durable timestamps derive from reply metadata in replay
  tests, not fresh ambient time reads;
- SSR preload: blocking route resources settle through ledger terminal statuses
  and hydration serializes only allowed resource/work projections;
- reply mapping law: identity and composition hold for target mapping, and
  mapping does not change work id, status, cancellation, stale checks, or trace
  correlation.

## Open Issues

- `:rf.runtime/work-ledger` is a multi-writer subsystem. Resources and
  mutations can mint authority today through the Resources artefact; the first
  writer outside that artefact must settle the general authority path for
  timers, route loaders, machine work, and future async surfaces.
  **Recommendation:** settle it through the EP-0006 runtime-subsystem contract
  when that first outside writer lands; until then this EP adds no new
  authority surface.
- The descriptor form for `:rf/reply-to` may need additional delivery modes for
  compatibility adapters. **Recommendation:** ship `:append` as the only
  public delivery mode; any new mode requires a recorded ruling justified by
  public migration needs, not effect-family preference.
- Streaming and multi-reply effects need a sibling proposal. They may share
  `:work/id` and status vocabulary, but their per-message continuation shape is
  different from single completion. **Recommendation:** keep them out of scope
  here and open the sibling EP only when a concrete streaming surface is
  proposed.
- The default for stale app delivery is non-delivery. Tooling and tests need an
  explicit opt-in shape; this EP provisionally spells it `:dispatch-stale?
  true` on the descriptor form. **Recommendation:** keep that spelling unless
  graduation review finds a conflict, and restrict it to framework test and
  tool targets.

## Guide Impact

On graduation, the implementation bead must update the guide's async/no-await
material (`concepts/http.md` and `explanation/continuations-are-data.md`) so
managed effects are taught as causal reply events. The guide update should show `:rf/reply-to`, the uniform reply
map, the full status set including `:partial`, stale suppression, cancellation,
and EP-0010 completion timestamps. HTTP and mutation compatibility examples may
keep `:on-success` / `:on-failure`, but they must be presented as lowering
sugar over the uniform envelope rather than the general async model.

## Recommendation

Adopt this EP. It is a small abstraction with high leverage: one causal reply
shape removes duplicated async callback vocabularies, gives the work ledger a
framework-wide meaning, makes EP-0010 completion metadata practical, and lets
HTTP, resources, mutations, timers, routing, and machines share stale
suppression, cancellation, tracing, SSR preload, and migration-compatible public
sugar.
