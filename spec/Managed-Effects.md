# Managed External Effects

> **Type:** Reference
> The unifying conceptual frame for every framework-owned, lifecycle-aware effect surface in re-frame2 — HTTP requests, state-machine actors, SSR per-request fxs, and managed flows (the internal-effect cousin). Names the nine properties every conformant managed-effect surface inherits, so new surfaces (managed timers, managed background jobs, managed IndexedDB transactions) can be evaluated against a single checklist instead of re-deriving the shape each time. The same checklist also grades **app- and library-built** surfaces — re-frame2 does not ship a managed WebSocket, but [Pattern-WebSocket](Pattern-WebSocket.md) shows how an app or library builds one that satisfies all of them.

## Why this doc exists

A pattern is visible in re-frame2 across four shipped capability surfaces: [`:rf.http/managed`](014-HTTPRequests.md), state-machine [`:spawn` / `:spawn-all`](005-StateMachines.md), [`:rf.server/*`](011-SSR.md), and [`:rf.flow/*`](013-Flows.md). Each Spec describes its own surface in detail; this doc names the **shared shape** so the architectural concept stops living implicitly in four places and starts being citeable as a single concept with a single anchor. The same shape also describes app- and library-built surfaces — most notably the [WebSocket connection pattern](Pattern-WebSocket.md), which re-frame2 deliberately does **not** ship but whose recommended shape satisfies the properties.

**A managed external effect is an effect whose entire interaction lifecycle — issuance, observability, failure classification, retry, abort, teardown, and reply addressing — is owned by the framework, not by the calling event handler.** The handler returns *data* describing what it wants; the framework owns *how* the interaction unfolds across time.

This is the architectural contract that lets pair tools, `:fx-overrides`, error projectors, and the trace bus compose uniformly across surfaces. New surfaces inherit the contract by adopting the nine properties below; the AI-Audit grades surfaces against this checklist.

## The nine properties

Every managed-effect surface MUST satisfy these properties. A surface that satisfies fewer is an "ad-hoc fx" — useful, but outside the contract pair tools and the conformance corpus assume. Properties 1–8 govern issuance, observability, failure, retry/abort/teardown, the in-flight registry, and per-frame scoping; **property 9** governs how a *managed async* effect reports its completion back — the uniform reply envelope ([EP-0011](../docs/EP/EP-0011-uniform-async-reply-envelope.md) is the rationale record). Property 9 applies only to surfaces with asynchronous completion (HTTP, resources, mutations, machine async work, route loaders, timers); a one-shot synchronous effect that finishes inside the `:fx` walk has no later reply and is exempt.

### 1. Effect-as-data, not callbacks

The handler returns a plain-data description of the interaction in its effect-map, never a function. The interaction's shape is a serialisable map under a registered fx-id; the framework walks the map and dispatches the work.

```clojure
;; Effect-as-data — what every managed-effect call site looks like.
{:fx [[:rf.http/managed   {:request {...} :on-success ... :retry {...}}]
      [:rf.machine/spawn  {:id ... :machine ... :data {...}}]
      [:rf.server/set-cookie {:name "session" :value ...}]]}
```

This property is the architectural foundation. Without it none of the remaining seven mean anything — callbacks are opaque to overrides, to time-travel, to elision, and to pair-tools. See [Pattern-AsyncEffect §Why this shape](Pattern-AsyncEffect.md#why-this-shape) for the underlying rationale; managed effects specialise that pattern with a fixed contract.

### 2. Framework-owned execution lifecycle

The framework — not the calling event handler — owns issuance, intermediate state, and teardown. The handler does not call `.then`, does not register completion listeners, does not unregister, does not free resources. The runtime tracks the interaction from dispatch to terminal state (success / failure / abort / teardown) and emits trace events at each transition.

**Atomicity boundary.** A managed effect's *issuance* runs inside the per-event `:fx` walk (per [002 §`:fx` ordering and atomicity guarantees](002-Frames.md#fx-ordering-and-atomicity-guarantees)), which is the **post-install** stage — the `:db` install has already committed by the time any `:fx` entry fires. A throw inside the issuance code of one managed effect (or any other fx) does **not** wind `app-db` back, and side effects already fired by earlier `:fx` entries (an HTTP request that flew, a `:dispatch` that queued, a navigation that landed) are not undone. This asymmetry is deliberate; see [013 §Why this asymmetry?](013-Flows.md#why-this-asymmetry--db-is-atomic-fx-is-best-effort) for the rationale (three load-bearing constraints: most fx are irreversible, gating commit on "all fx succeeded" forces every event async, and the principled answer is application-level sagas) and the compensating-event escape-valve pattern, with a worked example at [`examples/reagent/realworld/favorites.cljs`](../examples/reagent/realworld/favorites.cljs).

### 3. Structured failure taxonomy under `:rf.<surface>/*`

Failures are classified into a closed, enumerable taxonomy under a single reserved namespace per surface (per [Conventions §Reserved namespaces](Conventions.md#reserved-namespaces-framework-owned)). Shipped surfaces in re-frame2 today:

| Surface | Failure namespace | Spec |
|---|---|---|
| HTTP requests | `:rf.http/*` (eight categories: `:rf.http/transport`, `:rf.http/http-4xx`, `:rf.http/http-5xx`, `:rf.http/decode`, ...) | [014 §Failure taxonomy](014-HTTPRequests.md) |
| State-machine actors | `:rf.machine/*` (`:rf.machine/invoke-failed`, `:rf.machine/snapshot-version-mismatch`, ...) | [005 §Error contract](005-StateMachines.md) |
| SSR per-request | `:rf.ssr/*` (`:rf.ssr/hydration-mismatch`, `:rf.ssr/render-failed`, ...) | [011 §Error contract](011-SSR.md) |
| Managed flows | `:rf.flow/*` per-flow trace operations (`:rf.flow/failed` on `:output` throw, `:rf.flow/computed` / `:rf.flow/skip` on success); cascade-level `:rf.error/flow-eval-exception` at the router's outer catch; registration-time `:rf.error/flow-cycle` ex-info | [013 §Failure semantics](013-Flows.md#failure-semantics) |

An app- or library-built surface follows the same convention. The [WebSocket pattern](Pattern-WebSocket.md), for instance, recommends classifying connection failures under an app-chosen `:rf.ws/*`-style namespace (`:rf.ws/transport`, `:rf.ws/auth`, `:rf.ws/stale-socket`, ...) — re-frame2 does not ship or reserve this namespace; it is the app/library's own.

Every failure is a structured trace event with `:operation`, `:tags`, and `:recovery` per [009 §Error contract](009-Instrumentation.md#error-contract). No surface invents its own error shape.

### 4. Observable via the trace bus

Every issuance, intermediate transition, retry attempt, and terminal outcome emits a trace event on the single global trace bus (per [009 §The trace event model](009-Instrumentation.md#the-trace-event-model)). Pair tools, 10x, and off-box monitors consume the same stream — no surface-specific observation API.

### 5. `:sensitive?` + `:large?` composition

Every wire-bearing slot in a managed-effect trace passes through [`rf/elide-wire-value`](API.md#elide-wire-value-the-wire-boundary-walker), the single shared walker that composes privacy elision (`:sensitive?` per [009 §Privacy](009-Instrumentation.md#privacy--sensitive-data-in-traces)) and size elision (`:large?` per [009 §Size elision](009-Instrumentation.md#size-elision-in-traces)). Surfaces MUST NOT roll their own wire-boundary elision; the walker is the single point of truth. A slot carrying both `:sensitive? true` and `:large? true` redacts on sensitivity — the `:rf.size/large-elided` marker itself would leak `:path` / `:bytes` / `:digest` and is suppressed.

### 6. Built-in retry / abort / teardown semantics

Each surface ships first-class **retry-with-backoff**, **abort**, and **teardown** semantics as data on the args map, not as caller code. The vocabularies differ (HTTP has `:retry {:on ... :max-attempts ... :backoff {...}}`; machines have `:after` + `:always` guards; WebSocket reconnect lives on the `:reconnecting` state), but the contract is identical: the handler declares the policy; the framework executes it.

### 7. In-flight registry

Each surface maintains a framework-private registry of currently-in-flight interactions, keyed by an addressable id (HTTP request-id, machine instance-id, socket-id, flow-id, SSR request-id). The registry is queryable via the public registrar API (per [001 §The query API](001-Registration.md#the-query-api)); pair tools list active interactions, and the runtime emits a structured warning if a frame is destroyed while interactions are still in-flight.

### 8. Per-frame interceptors / scoping

Managed effects MUST honour the dispatching frame's `:fx-overrides`, `:interceptor-overrides`, and `:platforms` filters (per [002 §Per-frame and per-call overrides](002-Frames.md#per-frame-and-per-call-overrides) and [011 §`:platforms`](011-SSR.md)). Tests stub `:rf.http/managed` to a canned reply via `:fx-overrides`; SSR builds short-circuit `:rf.machine/spawn` for actors that aren't `:platforms #{:client}`; stories install per-story `:interceptor-overrides`. The override seam is id-based — overrides cite the registered fx-id, not a function value, so they round-trip through the wire.

### 9. Uniform reply envelope (async completions)

A managed effect whose completion crosses an event boundary MUST report that completion through **one** uniform reply envelope: one standard **reply map** delivered to one standard **reply target**. The effect either accepts `:rf/reply-to` directly or defines public sugar (HTTP `:on-success` / `:on-failure`, the resource/mutation internal replies, machine `:on-done`, nav-token threading) that **lowers** to `:rf/reply-to` internally. Completion MUST produce a reply map with a single `:status`, value and/or error data, work correlation, causal completion metadata, and any cancellation/staleness facts. Ledger-backed effects MUST correlate by `:work/id`.

This is the property that makes "an event reply is a causal continuation" a framework-wide law rather than a per-family convention. It is faithful to [EP-0011 §Specification](../docs/EP/EP-0011-uniform-async-reply-envelope.md#specification); where this section and that EP differ, this section governs (the EP is the rationale record). The contract is defined in full immediately below ([§The uniform reply envelope](#the-uniform-reply-envelope)); each async surface's spec adds the family-specific work-id tuple and suppression gates and a back-reference to here when it lowers.

## The uniform reply envelope

Property 9 above is the checklist line; this section is its canonical normative home. Every managed *async* surface — HTTP ([014](014-HTTPRequests.md)), resources and mutations ([016](016-Resources.md)), machine async work ([005](005-StateMachines.md)), route loaders ([012](012-Routing.md)), and any future managed timer or background-job surface — completes through the shape defined here. The per-family specs own their work-id tuple shape and their suppression gates; this section owns the reply map, the reply target, the closed status taxonomy, the work-id correlation rule, mandatory stale suppression, and the reply-mapping functor law.

Nothing in this contract introduces promises, monads, callbacks, async/await, or channels into the app-facing model, and it does **not** add result-binding between sibling `:fx` entries: one effect's reply never feeds another effect in the same effect map. The envelope unifies only the *continuation slot* — where completion is dispatched and what it carries. The only composition mechanism remains the next causal event.

### The reply target

The canonical public target key is `:rf/reply-to`. The short form is an event-vector prefix:

```clojure
{:rf/reply-to [:article/load-replied {:id 42}]}
```

On **live** completion the runtime dispatches the target event with the reply map appended as the final argument:

```clojure
[:article/load-replied
 {:id 42}
 {:status       :ok
  :value        {:title "Welcome"}
  :work/id      [:rf.work/http :article/by-id 42 1]
  :completed-at 1781078400456}]
```

A descriptor form is available for framework internals and future public surfaces that need explicit delivery options:

```clojure
{:rf/reply-to {:event    [:article/load-replied {:id 42}]
               :delivery :append
               :suppress {:route/nav-token "nav-7"
                          :generation      1}}}
```

| Descriptor field | Required | Meaning |
|---|---:|---|
| `:event` | yes | Event-vector prefix to complete. |
| `:delivery` | no | `:append` by default — the reply map is appended as the final argument. Compatibility adapters may use other delivery internally to preserve an older public event shape. `:append` is the only public delivery mode; a new mode requires a recorded ruling justified by public migration need, not effect-family preference. |
| `:suppress` | no | Data-only gates that MUST still match before the reply is delivered ([§Stale suppression](#stale-suppression)). |
| `:dispatch-stale?` | no | `false` by default. Framework tests/tools MAY opt into receiving stale envelopes; app targets MUST NOT. Restricted to framework test and tool targets. |

A family MAY expose only the short vector form publicly while using the descriptor form internally.

### The reply map

The reply map is **data only**. It MUST NOT contain functions, promises, `AbortController`s, timer handles, DOM nodes, or any other host resource (those live in a host-transient side-table keyed by `[frame-id work-id]`, never in durable reply data).

```clojure
{:status       :ok | :partial | :error | :cancelled | :stale
 :value        value-or-nil
 :error        error-map-or-nil
 :work/id      work-id-or-nil
 :work/kind    :http | :resource | :mutation | :timer | :route | :machine | ...
 :work/status  :completed | :failed | :timed-out | :suppressed | :cancelled
 :attempt      positive-int-or-nil
 :rf.frame/id  frame-id
 :started-at   started-at-or-nil
 :completed-at completed-at-or-nil
 :deadline-at  deadline-at-or-nil
 :correlation  data-only-map
 :stale?       boolean
 :stale/reason keyword-or-nil
 :cancelled?   boolean
 :cancel/reason keyword-or-nil
 :trace        data-only-trace-summary
 :meta         effect-family-data}
```

Required / conditional fields:

- `:status` is **always** required.
- `:value` carries the decoded successful result for `:status :ok` and `:status :partial`. **One-name-per-fact note ([EP-0007](../docs/EP/EP-0007-one-name-per-fact.md)):** the decoded result on the *reply map* is `:value` everywhere, across every family (HTTP, resource, mutation, machine, timer, route) — there is no per-family synonym for it. A *mutation-instance state* sub may store that same decoded result under a different key (`:result`) as a deliberately distinct fact: the instance sub is a durable, queryable status record (`{:pending? :success? :error? :settled? :result :error}`), not the transient causal reply, so the two spellings name two facts living in two layers. Spec 016 reconciles that pairing on its own surface. The same layer-distinction explains the [Pattern-RemoteData](Pattern-RemoteData.md) slice's `:data` key: a reply handler reads the transient `:value` off the reply and *folds* it into its durable app-db slice under that slice's own spelling (`[:feature.resource :data]`) — `:value` is the reply fact, `:data` is the durable slice fact, and the handler is the explicit hop between them. In every case the reply-map spelling is `:value`, full stop.
- `:work/id` is required for ledger-backed work and SHOULD be present for any managed async effect that can be correlated.
- `:rf.frame/id` is required when the effect is frame-scoped. This is the canonical carried frame stamp ([EP-0002](../docs/EP/EP-0002-frame-target-resolution.md)); there is no second frame spelling. The frame id is the **whole** routing coordinate on the reply map — there is no second realm/app stamp carried beside it (the EP-0013 `:rf.realm/id` reservation was retired with the multi-realm substrate, [EP-0023](../docs/EP/EP-0023-image-loaded-frames.md) / [EP-0024](../docs/EP/EP-0024-unified-frame-identity-and-lifecycle.md)).
- `:error` is present for `:status :error`; present with structured partial diagnostics for `:status :partial`; and MAY carry compatibility failure data for `:status :cancelled`.
- `:started-at`, `:completed-at`, and `:deadline-at` are causal completion metadata ([EP-0010](../docs/EP/EP-0010-causal-world-inputs.md) — suffixless durable timestamps; the values are causal epoch-millisecond readings supplied by the triggering or reply token, **not** fresh ambient clock reads). Carry them when the fact affects durable state; omit a field a family does not durably use. The `:completed-at` payload is the **same durable wall-clock value** the **live reply dispatch** carries as the flat `:rf.cofx` `:rf/time-ms` fact ([EP-0017](../docs/EP/EP-0017-recordable-coeffects.md) — `:rf/time-ms` is the framework's one built-in recordable coeffect, stamped on every dispatch and reply envelope; the dispatch-opts key is the flat `:rf.cofx`, never the retired `:rf.world/inputs`). The reply-map `:completed-at` and the reply-dispatch envelope's `:rf.cofx` `:rf/time-ms` therefore stay in sync; every managed-async family threads the same fact uniformly, and a family supplied no completion time omits `:completed-at` (no nil sentinel) rather than letting a reducer derive a durable timestamp from a stale/missing fact. The cross-family `reply-conformance` tier locks this propagation (pure over reply maps); the router and per-family lowering suites lock the live-dispatch `:rf.cofx` half.

Optional fields SHOULD be omitted when absent rather than filled with placeholder sentinels, except where a per-spec schema requires nilable keys.

### Status taxonomy

The reply `:status` vocabulary is **closed**:

| Reply status | Meaning | Value / error convention |
|---|---|---|
| `:ok` | Work completed successfully and the reply is current. | `:value` present; `:error` absent. |
| `:partial` | Work completed with usable value data **and** structured family-specific problems; the reply is current and the family decides how partial data installs. | `:value` present; `:error` present as a family error **map** carrying a `:kind` (e.g. `:rf.graphql/partial-success`) — a loose scalar error is rejected. |
| `:error` | Work completed with a failure and the reply is current. | `:error` present as a family error **map** carrying a `:kind` — a loose scalar error is rejected. |
| `:cancelled` | Work was intentionally cancelled while still correlated with the target. | `:cancelled? true` (the intentional-cancellation marker) **and** `:cancel/reason` present; `:error` MAY carry compatibility failure data. |
| `:stale` | Work completed or was observed after its correlation became obsolete. | `:stale? true`; `:stale/reason` present; **no app-state mutation**. |

`:partial` keeps the envelope transport-neutral: a protocol that can return both data and errors in one completion (GraphQL is the motivating case) must not be forced to pretend it is plain `:ok` or plain `:error`. Plain managed HTTP does not emit `:partial` — an HTTP response is decoded as `:ok` or projected as `:error`/`:cancelled`/`:stale`.

**Timeout is not a top-level status.** It is an error kind plus a work status:

```clojure
{:status :error :work/status :timed-out
 :error  {:kind :rf.http/timeout :limit-ms 30000 :elapsed-ms 30012}}
```

**Stale wins over the natural completion status for delivery purposes.** A late successful, partial, or failed completion for a superseded attempt is not `:ok`/`:partial`/`:error`; it is `:stale`/`:suppressed` in the ledger and trace, and the app target is not dispatched unless `:dispatch-stale?` is explicitly set for a test/tool target.

### Work-id correlation

Ledger-backed async work MUST use `:work/id` as the single attempt identity. A work id is `=`-comparable, EDN-serializable when it appears in runtime-db or trace data, frame-scoped enough to avoid intra-frame collision, the key used by the work ledger, and the key used by stale suppression for that attempt. **One attempt has one work id.** Any other public identity (an HTTP `:request-id`, a route `:nav-token`) is correlation metadata under `:correlation`, never a second stale-suppression key ([EP-0007](../docs/EP/EP-0007-one-name-per-fact.md): no `:stale-key`-style synonym).

The tuple head is owned by each family, but the heads in use are:

| Family | Work-id tuple | Owning spec |
|---|---|---|
| HTTP | `[:rf.work/http logical-id issuance attempt]` — `issuance` is the monotonic per-`request-id` re-issuance counter (a fresh request under the same `:request-id` bumps it, so a superseded attempt and its superseder carry distinct work ids); `attempt` discriminates transport retries within one issuance | [014](014-HTTPRequests.md) |
| Resource | `[:rf.work/resource scoped-resource-key generation]` | [016](016-Resources.md) |
| Mutation | `[:rf.work/resource [:rf.mutation instance-id] generation]` — mutation work reuses the resource head with a mutation-instance key; the ledger row distinguishes the writer with `:work/kind :mutation` | [016](016-Resources.md) |
| Route loader | `[:rf.work/route route-id nav-token loader-id]` | [012](012-Routing.md) |
| Timer | `[:rf.work/timer logical-timer-id generation]` — the machine `:after` instance uses `[:rf.work/timer [machine-id decl-path…] scheduled-epoch]` ([005 §`:after` timers](005-StateMachines.md#after-timers--the-existing-specialized-stale-gated-instance)) | machine `:after` ([005](005-StateMachines.md)); future public timer surface |
| Machine | `[:rf.work/machine actor-id work-bearing-path generation]` | [005](005-StateMachines.md) |

Because the frame-local work id carries no frame id, it is **not** a safe process-global transport correlation token; the landed Spec 016 lowers a frame-qualified transport request-id `[:rf.req frame-id work-id]` as the one sanctioned second identity for transport-level in-flight correlation (registry keying, supersede-on-lower, opportunistic abort). Intra-frame stale suppression still keys on `:work/id` + generation. See [016 §Ledger row retention and identity](016-Resources.md#ledger-row-retention-and-identity).

### Work-ledger integration

When a work-ledger row exists ([016](016-Resources.md) is the durable substrate — a ledger row *is* the reified continuation, and its `:reply-to` field is this section's reply target made durable), issuance writes or joins a non-terminal row carrying `:work/id`, `:work/kind`, `:work/frame`, `:status :running`, `:owners`, `:causes`, `:cancellable?`, `:started-at`, `:deadline-at`, and `:reply-to`. Completion updates that row before, or atomically with, delivery. The ledger MUST NOT store host handles. Terminal ledger statuses MAY be more operational than reply statuses:

| Ledger status | Typical reply status |
|---|---|
| `:completed` | `:ok` or `:partial` |
| `:failed` | `:error` |
| `:timed-out` | `:error` with timeout kind |
| `:cancelled` | `:cancelled` |
| `:suppressed` | `:stale` |

This EP defines the reply surface ledger-backed work uses; it does **not** define the full `:rf.runtime/work-ledger` schema. The multi-writer authority question for the ledger (the first writer outside the Resources artefact) is the [Runtime Subsystem Contract](Runtime-Subsystems.md)'s to settle ([EP-0006](../docs/EP/EP-0006-runtime-subsystem-contract.md)).

### Stale suppression

Stale suppression is the **correctness boundary**; cancellation is only an optimization (a cancelled fetch may still produce a late host completion). Every family that can be superseded MUST define **data-only** suppression gates and validate them before any durable write. Examples: resource/mutation `:work/id` + generation still matches the current entry/instance `:current-work`; route `:nav-token` still matches `[:rf.runtime/routing :current :nav-token]`; machine `:after` epoch and declaring path still match the active snapshot; the actor id still names a live actor; the frame id still names a live frame.

If validation fails:

1. the app reply target MUST NOT run;
2. the reply outcome becomes `:status :stale`;
3. the ledger row, if present, reaches `:suppressed`;
4. the trace stream emits a stale-suppression row carrying the **carried** and **current** correlation facts;
5. the stale reply MUST NOT produce any user-visible app-db or runtime-db mutation, except framework-owned ledger/trace bookkeeping.

### Cancellation

Cancellation is represented as **data**, not as the absence of a reply. Explicit user cancellation that is still live MAY dispatch a `:status :cancelled` reply (with `:cancelled? true` and `:cancel/reason`); supersession cancellation should usually suppress the old app reply as `:status :stale`/`:suppressed`. Actor-destroy cancellation is delivered by the owning surface and completes through this same envelope: `:status :cancelled` when the actor-bound target is still meaningful, `:status :stale`/`:suppressed` when teardown made the target obsolete before delivery. The *obsolete-target* check itself is surface-specific by design: HTTP needs an explicit target-identity gate (`actor-destroy-target-obsolete?`) because it alone re-dispatches a reply at a caller-supplied event target; resources/mutations gate obsolescence on work-id/generation entry-liveness, and machines split late-obsolete arrivals into separate semantic `:stale` paths — so none of the sibling surfaces needs HTTP's predicate.

### Causal completion metadata

Managed async replies are causal tokens ([EP-0010](../docs/EP/EP-0010-causal-world-inputs.md)). Any host fact from the completion that can affect durable app-db, runtime-db, resource entries, machine snapshots, route state, or ledger rows MUST ride the reply map (or be supplied as an explicit replayable coeffect derived from it). A reducer reached by a reply derives a durable timestamp from `(:completed-at reply)`, never from a fresh ambient clock read. The enqueue time of the dispatching event envelope is distinct from `:completed-at` when the host completion happened before the runtime enqueued the reply; a spec may choose which a durable field uses, but the value must be causal data.

### Tracing

Managed async families MUST emit trace rows from the reply-envelope facts, not from private callback facts: issuance/start (with `:work/id`, frame, owner/cause, target summary), retries/intermediate transitions, cancellation-requested (reason + whether a host handle existed), completion classified as one of the five statuses, stale suppression (carried/current correlation), and delivery-or-explicit-non-delivery. Every wire-bearing value (`:value`, `:error`, params, scopes, request bodies, route params) routes through the shared `rf/elide-wire-value` walker (property 5), never a family-private elider.

A reply summarized for a trace row **self-summarizes**: the egress frame whose classification governs the wire slots resolves from the reply's own carried `:rf.frame/id` stamp when the summary call supplies no explicit frame. A caller summarizing a carried reply outside frame scope therefore need not thread the identity back through opts — the carried stamp *is* the egress policy frame by default. An explicit frame still **wins** (a tool may reclassify under a different frame), and resolution still **fails closed**: if neither an explicit frame nor a carried `:rf.frame/id` names a live frame, the wire slots redact rather than ship under no policy (the same no-default-frame rule [Spec 015 §Direct reads and fail-closed frame resolution](015-Data-Classification.md#direct-reads-and-fail-closed-frame-resolution) pins — a frame stamp is policy-bearing only when it resolves to a live frame). This mirrors the record-level seed [Spec 015 §`project-egress`](015-Data-Classification.md#project-egress--the-record-level-boundary-primitive) applies, where a frame-bearing record seeds its egress frame from its own `:frame` slot.

### Reply mapping and the functor law

Because the reply target is plain data, relocating or wrapping a continuation is a pure data transform — the role `Cmd.map` plays in Elm's command algebra. The runtime SHOULD expose pure helpers for transforming reply targets (public or implementation-internal), but the law is normative: **mapping a target changes only the completed event — not issuance, work id, status classification, cancellation, stale checks, or tracing.**

```text
complete(map-completed-event(f, target), reply) == f(complete(target, reply))

map-completed-event(identity, target)      == target
map-completed-event(comp(f, g), target)    == map-completed-event(f, map-completed-event(g, target))
```

Implementations need not store arbitrary functions in effect maps to satisfy this: a helper may rewrite source forms to named adapter events, or the law may be exercised in tests over the internal completion function. The required property is that reply-target wrappers compose predictably and create no hidden callback semantics.

### SSR, preload, hydration, and restore

SSR and preload integrations observe **work-ledger rows and reply statuses**, not effect-family callback slots: the server enqueues blocking resource work; SSR waits for ledger rows on the current route/nav-token to become terminal; successful replies update entries with causal `:completed-at`; stale/superseded replies are suppressed by work id/generation/nav-token; hydration serializes the allowed projection and non-terminal work *summaries*, never host handles. Hydration and epoch restore MUST NOT revive host work — a pre-restore host completion that arrives later cannot mutate the restored frame because its work id/generation/token cannot match the post-restore live correlation. See [016 §SSR and hydration](016-Resources.md#ssr-and-hydration) for the landed projection.

A restore installs the captured durable frame-state WHOLESALE, but the async host work the unwound epochs spawned — machine `:after` host-clock timers and non-resource managed-HTTP `AbortController`s / in-flight handles — is **not** frame-state, so the install leaves it attached to the pre-restore timeline. Restore therefore **quiesces** that orphaned host work for the restored frame on a successful install: it cancels/clears the host handles so a late pre-restore completion is stale-suppressed and never delivers to its original `:rf/reply-to` target. For ledger-backed work (resources/mutations) the dangling work-id + generation check is the structural suppression boundary ([016 §Restore and replay](016-Resources.md#restore-and-replay)); for a plain `:rf.http/managed` request (no ledger gate) the restore aborts it with the reply-suppressing `:epoch-restored` reason and emits the `:status :stale` / `:work/status :suppressed` envelope facts; a machine `:after` timer's liveness reverts with the snapshot (a fired stale-epoch timer drops via `:rf.machine.timer/stale-after`, [005 §Hierarchy interaction](005-StateMachines.md#hierarchy-interaction)) while its host-clock handle is released eagerly (`:rf.machine.timer/cancelled :reason :on-restore`). In the reference implementation the epoch restore boundary fires a late-bound host-transient quiesce hook chain (`:machines/on-frame-restored!`, `:http/abort-in-flight-for-frame!`) AFTER the atomic install — the restore counterpart of the `destroy-frame!` teardown chain (rf2-u5kmf8).

## Instances today

The four shipped surfaces in the v1 corpus that satisfy the contract. Each Spec owns its own contract surface in detail; this section is informational — the spec text below points back to each canonical home. (Property 9 applies to the async surfaces among them — HTTP, machine async work — and is the lowering target of the slices that wire each onto the [uniform reply envelope](#the-uniform-reply-envelope). For an app/library-built surface graded against the same checklist, see the [WebSocket note](#websocket--an-applibrary-built-surface-not-shipped) below.)

### `:rf.http/managed` — HTTP requests ([Spec 014](014-HTTPRequests.md))

Single-request / single-reply HTTP. Args map shape: `:request`, `:decode`, `:accept`, `:on-success`, `:on-failure`, `:retry`. Eight-category failure taxonomy under `:rf.http/*`. Frame-aware reply addressing via co-located request-and-reply handlers (the `(:rf/reply msg)` branch). Specialises [Pattern-AsyncEffect](Pattern-AsyncEffect.md); pins [Pattern-RemoteData](Pattern-RemoteData.md)'s lifecycle slice.

### `:spawn` / `:spawn-all` — state-machine actors ([Spec 005](005-StateMachines.md))

Declarative actor spawn anchored on a state node. The framework owns the actor's lifetime: spawned on entry, destroyed on exit (or when an ancestor's `:spawn` boundary closes). `:spawn-all` parallel-fans children with a join condition. Reply addressing uses the carry-the-id-back-to-the-parent idiom; stale replies (from an actor whose owning state has already exited) are dropped.

### `:rf.server/*` — SSR per-request fxs ([Spec 011](011-SSR.md))

Seven server-side response-shape fxs (`set-status`, `set-header`, `append-header`, `set-cookie`, `delete-cookie`, `redirect`, `safe-redirect`) plus the `reg-error-projector` registry kind and the per-request HTTP response accumulator (a framework-private side-channel atom keyed by frame-id, read via `get-response`). The "interaction" here is the HTTP response itself; the framework owns building it across the per-request frame's lifetime, then emitting it as the response.

### `:rf.flow/*` — managed derived computation ([Spec 013](013-Flows.md))

The internal-effect cousin. The "external system" is the framework's own scheduler — flows are registered rules that compute on input change and materialise their output into `app-db`. They satisfy the nine properties applied to a derived-state surface — property 9 (the uniform async-reply envelope) is exempt because a flow completes synchronously inside the drain and never crosses an event boundary, so the eight synchronous properties are the ones that bite: effect-as-data (the registration map), framework-owned execution (the scheduler runs them as the outermost `:after` of every event's interceptor chain in topological order, transforming the pending `:db` effect before the single deferred install — see [013 §Drain integration](013-Flows.md#drain-integration) and [002 §Run-to-completion dispatch](002-Frames.md#run-to-completion-dispatch-drain-semantics)), failure taxonomy (per-flow `:rf.flow/failed` on `:output` throw under the atomicity contract at [013 §Failure semantics](013-Flows.md#failure-semantics) — a flow throw is a pre-install throw, so the pending `:db` effect is **discarded** (no partial commit; prior-flow writes do NOT land; `app-db` is unchanged; no `:rf.event/db-changed`; `:fx` is skipped), `last-inputs` is rolled back so every flow re-attempts on the next drain, the cascade halts, and the router emits `:rf.error/flow-eval-exception` onto the always-on production error-emit substrate ([009 §Error contract](009-Instrumentation.md#error-contract)); registration-time cycles throw `:rf.error/flow-cycle`), trace-bus observability (`:rf.flow/computed` and `:rf.flow/skip` per evaluation, plus `:rf.flow/registered` / `:rf.flow/cleared` lifecycle events), elision composition (`:sensitive?` on flow outputs), retry/abort/teardown (runtime-toggleable via `:rf.fx/reg-flow` / `:rf.fx/clear-flow`), in-flight registry (queryable via the registrar), per-frame scoping (flows are frame-local — see [013 §Frame-scoping](013-Flows.md#frame-scoping)).

### WebSocket — an app/library-built surface (not shipped)

> **re-frame2 does NOT ship a managed WebSocket** (Mike-ruled). The four surfaces above are framework-shipped and framework-enforced; the WebSocket "surface" is **not** — there is no `:rf.ws/*` fx, no reserved `:rf.ws/*` namespace, and no framework contract that anything implements. Apps and library authors supply their own connection surface (or use a community library) appropriate to their needs.

[Pattern-WebSocket](Pattern-WebSocket.md) is listed here because it is the canonical worked example of the nine properties applied **by an app/library** to a long-lived connection: a state machine that owns the socket actor, with connection states `:disconnected` / `:active{:connecting, :authenticating, :connected}` / `:reconnecting` / `:failed`, subscription state and queued sends surviving reconnects via the machine's `:data`, and a connection epoch (the socket-actor's gensym'd id) gating stale replies. A WebSocket connection is a long-lived synchronous surface rather than a one-shot async request, so property 9 (the uniform async-reply envelope) is exempt — the eight synchronous properties are the ones the pattern is graded on. They describe what a *good* app/library implementation looks like — they are a quality yardstick the pattern meets, not a framework-shipped contract the runtime guarantees. Names like `:rf.ws/transport` / `:rf.ws/stale-socket` in that pattern are an app-chosen convention, not a reserved framework namespace.

## How new managed-effect surfaces inherit the contract

A future surface — managed timers, managed IndexedDB transactions, managed background jobs, managed WebAuthn flows — becomes a "managed effect" by adopting all of the properties above. Concretely, a new surface SHOULD:

1. Register a single fx-id under a new reserved sub-namespace `:rf.<surface>/*` ([Conventions §Reserved namespaces](Conventions.md#reserved-namespaces-framework-owned) MUST be amended to add the namespace).
2. Define a closed args-map shape with a registered schema in [Spec-Schemas](Spec-Schemas.md).
3. Enumerate the failure taxonomy under `:rf.<surface>/*` in [009 §Error event catalogue](009-Instrumentation.md#error-event-catalogue).
4. Emit trace events at issuance, intermediate transitions (if any), retries, and terminal outcomes via the trace bus.
5. Route every wire-bearing slot through `rf/elide-wire-value` at the trace-emit site.
6. Ship retry / abort / teardown as data on the args map (not as caller code).
7. Maintain a framework-private in-flight registry keyed by an addressable id; expose via the registrar query API.
8. Honour the dispatching frame's `:fx-overrides`, `:interceptor-overrides`, and `:platforms` filters.
9. **If the surface has asynchronous completion**, report it through the [uniform reply envelope](#the-uniform-reply-envelope): accept `:rf/reply-to` (or define sugar that lowers to it), complete with a reply map carrying one closed `:status`, correlate ledger-backed work by `:work/id`, and make stale suppression the correctness boundary. A purely synchronous surface (no completion after the `:fx` walk) is exempt from property 9 only.

A surface that satisfies fewer than these remains useful but does **not** carry the "managed external effect" label; pair tools, the conformance corpus, and the AI-Audit treat it as out-of-contract.

This inheritance claim — that a *brand-new* surface, one that did not co-evolve with the contract, can in fact adopt all nine properties by consuming the shared infrastructure rather than re-spelling it — is **conformance-proven** by a test-only managed-timer probe (`re-frame.timer-probe`). The probe is a minimal managed-timer instance and the first fresh consumer of the shared reply substrate: it inherits the closed status taxonomy, the `[:rf.work/timer logical-timer-id generation]` work-id correlation, the `:rf/reply-to` `:suppress {:generation N}` stale-suppression boundary (stale completion → `:status :stale`, suppression trace joined to `:work/id`, no app dispatch), the reply-mapping functor law, a framework-private frame-scoped in-flight registry (host handles never leave it for a reply map), abort/teardown-as-data, and causal `:completed-at` ([EP-0010](../docs/EP/EP-0010-causal-world-inputs.md) — the host completion's reading, never a fresh ambient clock read) — all without a family-private substrate. The probe ships **no public surface** (it follows the EP-0003 precedent of a mandated test-only instance for an otherwise-unproven plug-in claim); a public `:rf.timer/after` surface, and the `:rf.timer/*` namespace reservation, are deferred to a future managed-timer surface.

## What this concept replaces

Before naming this concept, each downstream Spec independently described its own slice of the shape. The risk was drift — two Specs answering "how do we elide a sensitive request body?" with subtly different mechanisms; a new Spec inventing a new failure-vocabulary scheme; each async family spelling its completion continuation differently. Naming the concept makes the contract a **single point of accretion**: future surfaces are graded against the same checklist, and the shared infrastructure (the trace bus, `rf/elide-wire-value`, the registrar's in-flight queries, the `:fx-overrides` seam, and the [uniform reply envelope](#the-uniform-reply-envelope)) is the single point of implementation for all of them.

## Cross-references

- [Pattern-AsyncEffect](Pattern-AsyncEffect.md) — the underlying generic shape; managed effects specialise it with a fixed contract.
- [009 §Error contract](009-Instrumentation.md#error-contract) — the structured-error shape every managed-effect failure conforms to.
- [009 §Privacy](009-Instrumentation.md#privacy--sensitive-data-in-traces) and [§Size elision](009-Instrumentation.md#size-elision-in-traces) — the single shared `rf/elide-wire-value` walker.
- [API §`rf/elide-wire-value`](API.md#elide-wire-value-the-wire-boundary-walker) — the wire-boundary walker public surface.
- [Conventions §Reserved namespaces](Conventions.md#reserved-namespaces-framework-owned) — the `:rf.<surface>/*` namespace policy new surfaces extend.
- [Ownership](Ownership.md) — the contract-surface → owning-Spec map; consult before naming a new managed-effect surface.
- [EP-0011](../docs/EP/EP-0011-uniform-async-reply-envelope.md) — the rationale record for property 9 / [the uniform reply envelope](#the-uniform-reply-envelope): why one envelope beats N effect-family callback vocabularies, the alternatives considered, and the cross-family motivation.
- [EP-0010](../docs/EP/EP-0010-causal-world-inputs.md) — causal world inputs; reply maps are causal tokens and carry the `:started-at` / `:completed-at` / `:deadline-at` durable timestamps.
- [EP-0007](../docs/EP/EP-0007-one-name-per-fact.md) — the one-name-per-fact rule behind one-attempt-one-`:work/id` and the `:value`-everywhere reply-result spelling.
- [016 §Frame work ledger](016-Resources.md#frame-work-ledger) — the durable substrate for ledger-backed reply envelopes (resources + mutations are the landed writers).
