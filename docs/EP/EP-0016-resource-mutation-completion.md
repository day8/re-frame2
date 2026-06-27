# EP-0016: Resource Mutation Completion, Scoped Invalidation Targets, And Named Scope Resolution

Status: final
Type: standards-track

> **Ruling recorded 2026-06-12 (Mike, in-session).** Graduated `accepted →
> final`. The implementation is complete and merged (mutation completion with
> `:reply-to`, scoped invalidation descriptors, named scope resolvers,
> populate, and request decoration), the full wave-end review battery passed
> clean (correctness ×2, testing-coverage STRONG, code-comments, and a
> final-corrective pass), and it was dogfooded through the realworld example.
> The normative amendment lives in its home —
> [`spec/016-Resources.md`](../../spec/016-Resources.md). This EP is now the
> **rationale record**; where it and the spec differ, the spec governs.
>
> **BUILD status (updated 2026-06-12):** complete. The deferred
> derived-sensitivity propagation arm (`rf2-fi6tda.1`) shipped — the
> named-scope-resolver sensitivity inheritance landed via PR #3993 (merged
> 2026-06-12) — so the gate the original ruling held open is now resolved. The
> action epic (`rf2-fi6tda`) is ready to close: all implementation slices, both
> wave-end review passes (`rf2-tho0f9` correctness / `rf2-8xq7kp` clean repeat),
> and all four propagation beads (`/skills` `rf2-hdwp7h`, `/examples`
> `rf2-3gl52p`, `/tools` `rf2-f8s9g6`, `/docs/guide` `rf2-40o3nm`) are closed and
> merged. The items this EP lists as deferred — optimistic rollback,
> tag-addressed patching, and the GraphQL transport — remain intentional
> future-EP non-goals, not outstanding action-epic work (see [§Non-Goals](#non-goals)).

> This EP defines a narrow post-EP-0003 amendment to Spec 016:
> mutation completion continuations, per-target scoped invalidation, named
> resource-scope resolvers, and the small semantic riders needed to make those
> features coherent.
>
> **Ruling recorded 2026-06-11 (Mike, in-session; bead `rf2-6o6a62`).**
> Accepted. All nine open issues are dispositioned in
> [§Open Issues](#open-issues) — six as recommended with riders, and three
> sharpened in the ruling: issue 4 drops the migration window entirely
> (map-form targets are the **only** public input form from day one; the tuple
> is the internal/storage representation), issue 6 keys continuation delivery
> on **acceptance** rather than a status enumeration (any accepted terminal
> reply fires; stale/suppressed never do), and issue 7 replaces the
> `:snapshot-db` escape hatch with the resolve-in-handler-from-coeffect idiom
> plus a pure resolver helper (a whole-db snapshot on an event payload is an
> egress-bearing record — rejected under EP-0015). Implementation is tracked
> by the EP-0016 action epic, with the standard wave tails (correctness
> review, repeat pass, and the four propagation beads).
>
> Normative home after acceptance: `spec/016-Resources.md`, with a small
> routing-integration touch in `spec/012-Routing.md`, managed-HTTP guide
> material for request decoration, and Xray/tooling notes for the new trace
> evidence.

## Abstract

Spec 016's resource model has now survived a full RealWorld dogfood. The result
is strong evidence that the core is right: cached server reads belong in the
resource runtime, mutations belong beside those reads, and route-owned resources
are the right page-data model. The same dogfood also exposed three ordinary
application seams that Spec 016 does not yet name:

1. A verified mutation reply cannot causally continue into application workflow.
   RealWorld settings and editor writes therefore use adapter-specific watcher
   reactions over mutation state.
2. A single mutation cannot precisely invalidate both global and session-scoped
   facts. RealWorld favorite/unfavorite invalidates `[:feed]` in the mutation's
   global scope, so the session feed is not reached.
3. The session feed cannot be a declarative route resource because the scope
   identity lives in app state and the current route resource context does not
   provide a reusable, inspectable way to derive it.

This EP closes exactly those seams. It adds:

- call-site `:reply-to` on `:rf.mutation/execute`, dispatched with a standard
  reply map after stale suppression, cache consequences, and mutation instance
  settlement;
- per-target scoped invalidation descriptors, so one mutation can invalidate
  global facts and viewer-relative facts without blunt cross-scope fan-out;
- named resource-scope resolvers, so session, tenant, account, locale, and
  similar viewer identities can be derived once and reused by route resources,
  event-side ensures, subscriptions, invalidation descriptors, populate/patch
  targets, and clear-scope helpers;
- the semantic riders that make the above deterministic: populate is an
  authoritative load, cache consequence targets have one canonical map form,
  and resource/mutation request decoration belongs in the Spec 014 managed-HTTP
  interceptor/defaults layer.

This EP deliberately does not add optimistic rollback, tag-addressed patching,
normalized graph caching, subscription-driven fetching, pagination primitives,
or a broad cache-query language. Those remain separate design questions. The
point here is to complete the high-demand Spec 016 surfaces exposed by
RealWorld without turning the resources spec into an uncontrolled cache
framework.

## Motivation

The RealWorld comparison is unusually useful because it is the same application
implemented twice:

- `examples/real-apps/realworld_http/` demonstrates the lower-level managed-HTTP,
  app-db, event, subscription, machine, and hand-written cache-coherence style.
- `examples/real-apps/realworld_resources/` demonstrates the Spec 016 resource
  style over the same problem.

Across the covered read surface, the resources version removes whole classes of
application code: per-read status/data/error slices, load/success/failure event
trios, "loading vs fetching" decisions, manual stale-state handling, ad hoc
request dedupe, and most view-specific selectors. The app states the remote
facts, and the runtime owns resource identity, loading, refresh, cache hits,
stale-while-revalidate, work-ledger rows, owner leases, GC, route preload, and
focus/reconnect revalidation.

That validates EP-0003 and Spec 016's center. It also makes the remaining gaps
clearer because they show up as local workarounds in otherwise declarative code.

### Evidence boundary: RealWorld is a witness, not the oracle

RealWorld is a good test case for this EP because it is ordinary. It has
authenticated reads and writes, route-owned page data, a current-user/session
boundary, list/detail consistency, form submissions, post-write navigation,
favorites/follows/comments/settings/editor workflows, and enough app surface to
expose ergonomics that do not show up in toy examples.

It is not a complete design space for resources. It does not seriously stress
offline operation, collaborative/realtime updates, high-cardinality data grids,
concurrent optimistic writes, normalized GraphQL-style entity graphs, streaming
jobs, complex tenant/role hierarchies, or large SSR/hydration edge cases.

Therefore RealWorld is used here as a dogfood witness and forcing function, not
as the final authority on API shape. The design discipline is:

1. RealWorld identifies concrete friction in normal application code.
2. Prior art checks whether the friction is mainstream rather than app-local.
3. First principles decide the re-frame2-shaped primitive.
4. Conformance tests prove the general law with small synthetic cases.
5. RealWorld dogfood then proves the primitive is usable in a real app.

That boundary is why this EP accepts mutation continuations, scoped
invalidation descriptors, named scope resolvers, and populate semantics, but
continues to reject or defer RealWorld-adjacent requests such as pagination
primitives, official RealWorld E2E conformance, optimistic rollback, and
tag-addressed patching.

### RealWorld finding 1: mutation replies need causal continuations

Settings save, article create, article update, and article delete are writes
whose accepted replies must drive app workflow:

- update the auth/session slice from the saved user;
- navigate to the server-returned article slug after create;
- navigate away after delete;
- clear editor state or show a notification.

Current Spec 016 mutations own reply addressing, stale suppression, population,
patching, invalidation, and instance state. They do not provide a first-class
way to say "when this accepted mutation reply settles, dispatch this app event."
The example therefore watches `[:rf.mutation/state ...]` from a Reagent Form-3
lifecycle reaction and dispatches once when the watched mutation becomes
successful. That is better than dispatching from render, but it is still an
adapter-specific workaround around a runtime-level missing primitive.

The correct continuation mechanism in re-frame2 is not a callback. It is a
causal event target.

### RealWorld finding 2: one write can affect facts in multiple scopes

Favorite/unfavorite is the minimum failing case. A favorite changes:

- the global article detail fact;
- global article-list containment facts;
- the current user's session-scoped feed;
- profile/favorited article projections once those routes are present.

Current invalidation is scoped by the mutation's resolved scope unless the
author asks for broad cross-scope behavior. In the resources example the
favorite mutation is global and declares `[:feed]` among its invalidation tags.
Because the feed resource lives under a session scope, that invalidation cannot
hit the feed entry.

The problem is structural. Tags name remote facts; scopes name viewers. Stale is
a property of a `(fact, viewer)` pair. A mutation author needs to be able to say
that one affected fact is global and another affected fact is viewer-relative.
Neither "always current mutation scope" nor "invalidate this tag across every
scope" is precise enough.

### RealWorld finding 3: scope identity should be derived once

The session feed wants to be declared in route metadata, loaded on route entry,
released on route leave, and read by subscriptions. Its scope is a pure function
of current viewer identity, which in RealWorld lives in app state. Today that
same scope must be threaded manually through event-side ensure and subscription
queries, and the route resource path cannot easily express it.

This is not just route context. Session, tenant, organization, locale,
impersonation, backend mode, and account all have the same shape: viewer
identity should determine cache scope, and the derivation should be pure,
declared, inspectable, and shared across the places that use scope.

### RealWorld finding 4: request decoration needs one visible home

The resources port dropped authenticated headers and read retry policy at one
point because each resource request function focused on the domain request, and
Spec 016 did not clearly name the managed-HTTP interceptor/defaults layer as the
home for cross-cutting transport decoration.

That is not a reason to copy auth header code into every resource. It is a
reason for Spec 016 to state the doctrine plainly: resources and mutations lower
through Spec 014 managed HTTP; auth headers, tracing headers, common base URLs,
and default read retry policy belong in the managed-HTTP decoration seam.

### RealWorld finding 5: populate plus invalidate needs a net-effect rule

Some mutation replies include authoritative resource data. For example, an
article save can return the full updated article. Spec 016 already has
`:populates` and `:invalidates`, but if a mutation populates an article detail
entry and then invalidates a broad article tag matching that entry, the runtime
can immediately refetch a key it just learned from the write reply.

The narrow rule is simple: populate is an authoritative load. A key populated by
an accepted mutation reply is fresh for that mutation result and is not
immediately refetched by that same mutation's invalidation pass unless the app
asks for that behavior.

## Prior Art

EP-0003 deliberately benchmarked resources against TanStack Query, RTK Query,
SWR, and `shipclojure/re-frame-query`. Reviewing the proposed amendments
against those alternatives changes the design in one important way: it confirms
the demand, but argues against copying the callback shape.

### TanStack Query

TanStack Query centers server-state identity on query keys. Mutations expose
completion hooks (`onSuccess`, `onError`, `onSettled`), direct cache writes from
mutation responses (`setQueryData`), matching writes across many queries
(`setQueriesData`), invalidation (`invalidateQueries`), and optimistic update
hooks (`onMutate` with rollback context).

The useful lessons are:

- mutation completion is table stakes;
- invalidation should be the default correctness tool;
- direct cache writes are an escape hatch when the mutation reply is
  authoritative enough;
- optimism is useful but large enough to deserve its own design.

The part not to copy is callback execution as the app workflow continuation.
TanStack needs callbacks because React query hooks do not have re-frame2's
causal event substrate.

### RTK Query

RTK Query centers coherence on endpoint+arg cache keys plus
`providesTags`/`invalidatesTags`. It offers `onQueryStarted`,
`updateQueryData`, and `upsertQueryData`, but its strongest lesson is that a
declarative invalidation plan removes much of the need for registration-level
success callbacks.

That maps directly onto Spec 016: `:patches`, `:populates`, and `:invalidates`
are already a registration-level success-phase data plan. A new
registration-level mutation callback would mostly duplicate a surface re-frame2
already has in declarative form.

### SWR

SWR exposes `mutate` and `useSWRMutation`, including optimistic data,
population from a mutation result, rollback on error, and revalidation
controls. SWR reinforces the same split:

- use the mutation result to populate when the response is authoritative;
- revalidate when the local write cannot prove the full server result;
- treat optimistic rollback as a separate, carefully bounded feature.

### shipclojure/re-frame-query

`shipclojure/re-frame-query` is the closest prior art because it is
re-frame-native. It supports route-driven queries, tag invalidation, mutation
lifecycle hooks such as `:on-start`, `:on-success`, and `:on-failure`, and
optimistic updates.

The relevant lesson is demand, not API shape. A re-frame2 mutation completion
should not be an effect-returning callback. It should be an event target
completed by the runtime after stale suppression and cache consequences.

### Synthesis from alternatives

The alternatives agree on the capability set mature SPA authors expect:

- cached reads keyed by explicit identity;
- declarative invalidation;
- mutation completion;
- direct cache population from authoritative mutation replies;
- some optimistic update path for latency-sensitive interactions.

The re-frame2-specific synthesis is narrower:

- RealWorld decides priority, not API shape;
- cache consequences stay declarative on `reg-mutation`;
- workflow continuation is a call-site event target on mutation execution;
- optimistic rollback and tag-addressed patching are deferred to a dedicated
  follow-on EP;
- per-target scoped invalidation is needed because re-frame2 splits scope out
  of tags as a fail-closed leak boundary, while most alternatives put viewer
  identity directly into the query key/tag.

## First Principles

This EP rests on the following laws.

### Cache identity must be explicit

A resource key is a server-state identity: resource id, canonical params, and
scope. Every server-visible read parameter belongs in that identity. Transport
credentials generally do not; they are request decoration unless they change
which facts are visible, in which case the resource needs an explicit scope.

### Scope is both identity and leak boundary

A scoped read must never silently fall back to global. Scope may be derived, but
the derivation must be declared and inspectable so tools can explain which
identity was used. Nil from a resolver at a scope-requiring site remains a
fail-closed condition, not permission to use global.

### Views stay passive

Resource subscriptions read cache state. They do not start fetches, retry
requests, or perform mutation effects. Route entry, explicit events, prefetch
events, SSR planning, and mutation consequences are the causal places that
start work.

### A verified mutation reply is a causal token

When the runtime accepts a mutation reply as current, that reply is ordinary
causal input. It can drive durable app state only by dispatching an event. A
callback that returns effects would create a second effect-minting site outside
the event tape, outside the interceptor chain, and outside replay.

### Cache consequences stay declarative

The mutation registration describes server-state consequences: populate this
key, patch that exact key, invalidate these scoped tags. Application workflow
belongs in event handlers reached by `:reply-to`, not in mutation callbacks.

### Invalidation is the default correctness tool

Invalidation says "this cached fact may be stale." It does not require the
client to emulate the server. Direct populate/patch is for cases where the
response is trustworthy enough to update exact entries. Optimistic rollback and
tag-addressed patching are useful, but they are separate because they must
handle concurrency, ordering, and server-rule drift.

### One scope-resolution currency beats local seams

The same named resolver should work wherever current viewer identity determines
resource identity: resource registration, route resources, event-side ensure,
subscriptions, invalidation descriptors, populate/patch targets, and
clear-scope helpers. Several local mechanisms would be harder to explain, test,
and inspect.

## Goals

- Preserve Spec 016's resource center: cached server reads over explicit
  identity and a frame work ledger.
- Add mutation workflow continuation without introducing callbacks, promises,
  channels, or async/await into the app-facing model.
- Make cross-scope invalidation precise and explicit.
- Make db-derived viewer scope reusable and inspectable.
- Preserve fail-closed resource scope behavior.
- Define deterministic mutation phase ordering.
- Define the net effect when one mutation both populates and invalidates the
  same key.
- Clarify the managed-HTTP request-decoration seam for resources and mutations.
- Keep the public surface small enough that existing Spec 016 mental models
  still hold.

## Non-Goals

- No optimistic snapshot/rollback surface.
- No `:on-start`, `onMutate`, rollback tokens, or transaction contexts.
- No tag-addressed patching in this EP.
- No normalized entity or graph cache.
- No GraphQL transport amendment; that remains deferred by EP-0003.
- No subscription-driven fetching or `:rf.resource/live`.
- No pagination primitives; RealWorld pagination is plain route/resource params
  plus existing previous-data behavior.
- No predicate/key-pattern cache query language.
- No global mutation callback registry.
- No new transport beside managed HTTP.
- No RealWorld E2E conformance requirement; that belongs to example beads and
  project policy.

## Relationships

- **[EP-0003](EP-0003-resource-queries.md) / [Spec 016](../../spec/016-Resources.md)
  (final).** This EP is a post-final amendment to the resources contract. It
  does not reopen the read-resource foundation, work ledger, route ownership,
  SSR, cache-hit, lifecycle, or HTTP-only graduation decisions.
- **[EP-0019](EP-0019-optimistic-mutation-rollback.md) (final) — successor.**
  The optimistic rollback / tag-addressed patching this EP defers (see
  [§Non-Goals](#non-goals), [§Synthesis from alternatives](#synthesis-from-alternatives),
  and issue 9 in [§Open Issues](#open-issues)) is the subject of EP-0019. It
  reuses this EP's scoped-invalidation descriptors, map-form exact targets, and
  `:reply-to` continuation unchanged.
- **[EP-0020](EP-0020-active-owner-polling.md) (final) — successor.**
  Interval revalidation (polling) is the cache-freshness sibling of this EP's
  mutation/invalidation surface; it reuses the same named scope resolvers and
  refetch substrate.
- **[EP-0021](EP-0021-infinite-resources.md) (final) — successor.** The
  pagination / infinite-feed primitives this EP lists as a non-goal
  ([§Non-Goals](#non-goals): "No pagination primitives") are designed in
  EP-0021, which builds on the read-resource and invalidation surfaces here.
- **[EP-0011](EP-0011-uniform-async-reply-envelope.md) (final).** Mutation
  `:reply-to` uses the same core idea: async completion is a causal reply map
  delivered to an event target. EP-0016 is the concrete resources/mutations
  slice; EP-0011 remains the cross-family envelope proposal.
- **[EP-0010](EP-0010-causal-world-inputs.md) (final).** Mutation replies
  that affect durable state must carry causal completion facts rather than
  asking handlers to read ambient time.
- **[EP-0002](EP-0002-frame-target-resolution.md) (final).** All continuations,
  resource keys, and scope resolutions are frame-scoped. This EP adds no
  ambient default-frame fallback.
- **[EP-0007](EP-0007-one-name-per-fact.md) (active).** The EP follows the
  one-name-per-fact rule: `:status` is the reply status, `:work/id` is the work
  identity, scope resolver ids are the named scope-derivation facts, and
  request decoration remains HTTP policy instead of becoming resource-local
  auth policy.
- **[EP-0012](EP-0012-path-optics-and-canonical-forms.md) (final).** Named
  scope resolver inputs and map-form resource targets should use canonical
  params/scope identity rules defined there where applicable.
- **[EP-0014](EP-0014-derivation-and-process-algebra.md) (final).** Named
  scope resolvers are shaped consistently with EP-0014's derivation vocabulary
  (declared inputs, output, lifecycle), so if EP-0014 is accepted they slot
  into its algebra unchanged — but this EP specifies them independently and
  carries no dependency on that proposal.
- **[EP-0015](EP-0015-frame-owned-egress-policy.md) (accepted).** Resource
  scope values, params, reply values, mutation payloads, and trace evidence
  produced by this EP must pass through the eventual egress projection policy.

## Specification

The proposal has three decisions and three semantic riders.

### Decision 1: mutation completion continuations

`[:rf.mutation/execute ...]` accepts an optional call-site `:reply-to` event
target.

```clojure
[:rf.mutation/execute
 {:mutation :realworld/save-article
  :params   {:slug slug
             :draft draft}
  :instance [:editor/save slug]
  :reply-to [:editor/save-replied]}]
```

If the mutation reply is accepted as current, the runtime dispatches the target
with one reply map appended:

```clojure
[:editor/save-replied
 {:status        :ok
  :mutation      :realworld/save-article
  :params        {:slug "first-post"}
  :instance      [:editor/save "first-post"]
  :scope         :rf.scope/global
  :value         {:article {:slug "first-post"
                            :title "First post"}}
  :error         nil
  :affected-keys #{{:resource :realworld/article
                    :params   {:slug "first-post"}
                    :scope    :rf.scope/global}}
  :work/id       [:rf.work/resource [:rf.mutation [:editor/save "first-post"]] 8]
  :rf.frame/id   :app/main
  :completed-at  1781078400456
  :cause         [:mutation :realworld/save-article
                  [:editor/save "first-post"]]}]
```

The exact reply map is aligned with EP-0011 where that proposal governs common
async reply fields. EP-0016 requires these mutation-specific facts:

| Field | Required | Meaning |
|---|---|---|
| `:status` | yes | `:ok`, `:error`, or accepted `:cancelled`; stale/suppressed replies are traced but do not dispatch call-site continuations. |
| `:mutation` | yes | Mutation id. |
| `:params` | yes | Canonical mutation params used for the accepted attempt. |
| `:instance` | yes | Mutation instance id. |
| `:scope` | yes | Resolved mutation scope. |
| `:value` | for `:ok` | Decoded accepted value. |
| `:error` | for `:error` | Structured error. |
| `:affected-keys` | yes | Resource keys populated, patched, removed, or marked stale by the accepted reply. |
| `:work/id` | yes | Work-ledger identity for the accepted attempt. |
| `:rf.frame/id` | yes | Carried frame stamp. |
| `:completed-at` | yes when completion time can affect durable state | EP-0010 causal completion time. |
| `:cause` | yes | Data explaining the mutation/instance that caused the continuation. |

The runtime appends the reply map to the event target. If the target already
carries static arguments, the reply is appended after them:

```clojure
:reply-to [:toast/after-save {:kind :article}]

;; Runtime dispatch:
[:toast/after-save {:kind :article} reply]
```

#### Delivery rule

The continuation fires only for a reply the mutation runtime accepts as current
for the frame, mutation instance, work id, and generation. A stale or superseded
reply does not fire the continuation. Cancellation may produce an accepted
terminal reply if the runtime owns a terminal cancellation result; host-level
best-effort abort alone does not guarantee a reply.

#### Phase order

A mutation attempt has this deterministic runtime-owned order:

1. Resolve canonical params and mutation scope.
2. Issue the managed request under runtime-owned work/reply addressing.
3. Receive a host reply and stale-suppress or accept it.
4. Apply success-time cache consequences: `:patches`, `:populates`,
   `:invalidates`, and removes.
5. Settle mutation instance state and work-ledger state.
6. Dispatch the workflow continuation in `:reply-to`, if present.

That order is normative because it determines what a continuation observes. A
handler reached by `:reply-to` sees cache consequences and mutation instance
state already settled for the accepted reply.

#### Registration-level continuations are deferred

This EP does not add `:reply-to` to `reg-mutation`. The standing
registration-level success plan already exists as declarative data:
`:patches`, `:populates`, and `:invalidates`. An invariant workflow
continuation can be spelled by every call site passing the same event target.

If a later consumer proves that invariant non-cache workflow is common and
cannot be cleanly expressed at call sites, a future EP may add a
registration-level event target with the same reply map shape. It must not be
an effect-returning callback.

### Decision 2: per-target scoped invalidation

The existing shorthand remains valid:

```clojure
:invalidates #{[:article slug]
               [:article-list]}
```

It means: invalidate those tags in the mutation's resolved scope.

This EP adds descriptor form:

```clojure
:invalidates
(fn [{:keys [slug]} _result]
  [{:scope :rf.scope/global
    :tags  #{[:article slug]
             [:article-list]}}
   {:scope {:from-db :realworld/session}
    :tags  #{[:feed]}}])
```

Each descriptor has its own scope. Descriptor `:scope` may be:

- `:rf.scope/same` for the mutation's resolved scope;
- `:rf.scope/global`;
- a concrete canonical scope value such as `[:rf.scope/session
  {:username "jake"}]`;
- a named scope resolver reference such as `{:from-db :realworld/session}`;
- a future route/frame resolver reference if accepted by a later EP.

Missing descriptor scope defaults to `:rf.scope/same`.

"The mutation's resolved scope" here is the scope defined by Spec 016
§Mutation scope is two distinct scopes (hybrid): the fail-open *execution*
scope (execute-payload `:scope` → spec `:scope` → `:rf.scope/global`), which
the mutation supplies as the fail-closed *invalidation* scope. This EP's
descriptors refine that composition — each target names its own invalidation
scope explicitly instead of inheriting the execution scope for every tag.

Bare shorthand and descriptor form both lower to the same scoped invalidation
engine. There is one invalidation implementation. The descriptor is only the
public data that tells the engine which `(tags, scope)` pairs to mark stale.

The `:invalidates` and `:populates` callbacks both receive `(params result)` —
the one canonical mutation-consequence signature. Db-derived scope is expressed
through named resolver references (`{:from-db …}`), never by threading `db` /
`ctx` into the callback (which would reintroduce the anonymous-db-function path
[Alternatives](#anonymous-db-functions-everywhere) rejects).

#### Cross-scope fan-out

Existing deliberate broad invalidation remains available only as explicit broad
behavior. It must not be the default interpretation of a bare tag. Tooling
should flag broad invalidation as an expensive or privacy-sensitive operation
when a descriptor would be more precise.

#### Trace evidence

The runtime records trace evidence for:

- invalidation descriptor source;
- resolved scope per descriptor;
- tags requested;
- entries hit in the same scope;
- entries hit in other scopes;
- descriptors that resolved nil and therefore produced no invalidation;
- entries populated by the same mutation and exempted from same-mutation
  refetch by Rider 1.

### Decision 3: named resource-scope resolvers

Add a registry for named resource-scope resolvers:

```clojure
(rf/reg-resource-scope :realworld/session
  {:inputs {:username [:db [:auth :user :username]]}
   :resolve
   (fn [{:keys [username]} _ctx]
     (when username
       [:rf.scope/session {:username username}]))})
```

A resolver is pure. It derives a resource scope. It does not fetch, dispatch,
mutate state, read ambient host state, or perform transport work.

The primary form declares inputs. The input map is intentionally shaped like
other derivation input declarations: names on the left, source descriptors on
the right. This lets tools explain which app facts decide a resource identity,
and it lets the runtime re-resolve scope only when relevant inputs change.

An implementation may support a simple function sugar:

```clojure
(rf/reg-resource-scope :realworld/session
  (fn [db _ctx]
    (when-let [username (get-in db [:auth :user :username])]
      [:rf.scope/session {:username username}])))
```

The sugar lowers to an explicit whole-db dependency. Tooling should show that
cost.

#### Resolver references

The named resolver reference form is:

```clojure
{:from-db :realworld/session}
```

That reference may appear wherever this EP allows derived resource scope:

- resource registration `:scope`;
- route resource entries;
- event-side ensure/refetch payloads;
- resource subscriptions;
- invalidation descriptors;
- map-form populate/patch/remove targets;
- clear-scope helpers for logout, account switch, tenant switch, and similar
  boundaries.

Example resource:

```clojure
(rf/reg-resource :realworld/feed
  {:scope {:from-db :realworld/session}
   :params (fn [{:keys [page]}] {:page page})
   :request
   (fn [{:keys [page]} _ctx]
     {:request {:method :get
                :url    "/articles/feed"
                :params {:limit  20
                         :offset (* 20 (dec page))}}
      :decode :json})
   :tags
   (fn [_params _value]
     #{[:feed] [:article-list]})})
```

Example route entry:

```clojure
(rf/reg-route :realworld/home
  {:path "/"
   :resources
   [{:resource  :realworld/feed
     :params    {:page 1}
     :scope     {:from-db :realworld/session}
     :blocking? true}]})
```

Example subscription:

```clojure
(rf/subscribe
 [:rf.resource/state
  {:resource :realworld/feed
   :params   {:page page}
   :scope    {:from-db :realworld/session}}])
```

Nil from a resolver at a scope-requiring site is fail-closed. Route planning
must not silently substitute global. Subscription state should be explainable as
"scope unresolved" or an equivalent diagnostic state rather than quietly
reading a different cache entry.

#### Route-derived scope is reserved

Some applications derive viewer identity from the route, such as tenant in a
path segment. This EP's primary mechanism is db-derived scope because it closes
the RealWorld session/feed gap and composes with event and subscription sites.
Two future-compatible extensions are reserved:

- the route match is mirrored into frame state, so a db-derived resolver can
  read it as ordinary app/runtime state;
- a later EP adds a `{:from-route ...}` or `{:from-frame ...}` resolver input
  source with the same named resolver discipline.

This EP should not add anonymous route-context functions as a second public
scope-resolution currency.

### Rider 1: populate is an authoritative load

For a key it targets, `:populates` is semantically equivalent to a successful
resource load produced by the accepted mutation reply.

Therefore:

- the populated key becomes loaded;
- the populated value becomes the current value;
- freshness/staleness timers are armed as if the key had loaded normally;
- a populated key is exempt from immediate refetch by the same mutation's
  invalidation pass;
- the key may still be invalidated/refetched by later events, later mutations,
  focus/reconnect policy, or explicit refetch.

The populated value MUST be the resource's stored shape — the same value a
successful load of that key produces (e.g. the full decoded envelope), not a
sub-projection of the reply — so a populated entry reads identically to a
fetched one.

If a mutation reply is partial relative to the full resource GET, the author can
ask for a same-mutation refetch:

```clojure
:invalidates
[{:scope :rf.scope/global
  :tags  #{[:article slug]}
  :refetch-populated? true}]
```

The default is no same-mutation refetch for keys populated by that mutation.

### Rider 2: map-form exact resource targets

Exact resource targets should have one canonical source shape:

```clojure
{:resource :realworld/article
 :params   {:slug slug}
 :scope    :rf.scope/global}
```

The target map is accepted for `:populates`, `:patches`, removes, and any other
exact-key cache consequence. `:scope` may be concrete, `:rf.scope/same`,
`:rf.scope/global`, or a named resolver reference.

Rules:

- populate creates or replaces exactly one key;
- patch updates an existing exact key only;
- patch does not target tags in this EP;
- every exact target is scoped after resolution;
- hand-built scoped-key tuple syntax may remain as compatibility input during
  migration, but the spec, guide, traces, and examples use the map form.

This rider is intentionally smaller than a general cache operation language.
Internal normalization is allowed, but this EP does not introduce public
`{:op ...}` cache-operation maps.

### Rider 3: request-decoration doctrine

Resources and mutations lower through Spec 014 managed HTTP for the initial
HTTP-only scope. Cross-cutting request concerns belong in the managed-HTTP
decoration seam, not in every resource declaration.

Spec 016 and the guide should state:

- resource `:request` functions describe domain requests;
- auth headers, tracing headers, API base URLs, tenant headers, and retry
  defaults are frame/application managed-HTTP policy;
- default retry policy should be read-focused; mutation retry defaults must be
  conservative because retrying writes can duplicate side effects;
- resource/mutation traces should make applied decoration visible without
  leaking sensitive header values.

Example:

```clojure
(rf/reg-http-interceptor :realworld/auth
  {:before (fn [ctx]
             (let [token (some-> (rf/app-db-value (:frame ctx)) :auth :token)]
               (cond-> ctx
                 token (assoc-in [:request :headers "Authorization"]
                                 (str "Token " token)))))})

(rf/reg-resource :realworld/current-user
  {:request
   (fn [_params _ctx]
     {:request {:method :get :url "/user"}
      :decode  :json})})
```

The interceptor is registered once per frame and decorates *every*
`:rf.http/managed` request the frame issues — resource reads, mutations, and
plain managed calls alike — so `:realworld/current-user` needs no per-resource
opt-in. It reads the token from `(:frame ctx)` (EP-0002 carried-frame-correct),
not an ambient `db`, and returns `ctx` unchanged when no token is present.

The exact interceptor registration names are illustrative. The normative rule
is ownership: transport decoration belongs to managed HTTP policy and is reused
by resources/mutations.

## Examples

### Settings save

Before this EP, settings save must watch mutation state from a component
lifecycle hook and dispatch when the mutation settles. With this EP:

```clojure
(rf/reg-event-fx :settings/save
  (fn [{:keys [db]} [_ form]]
    {:fx [[:dispatch
           [:rf.mutation/execute
            {:mutation :realworld/update-user
             :params   form
             :instance [:settings/save]
             :reply-to [:settings/save-replied]}]]]}))

(rf/reg-event-fx :settings/save-replied
  (fn [{:keys [db]} [_ {:keys [status value error]}]]
    (case status
      :ok
      {:db (assoc db :auth/user (:user value))
       :fx [[:dispatch [:toast/show "Settings saved"]]]}

      :error
      {:db (assoc db :settings/error error)})))
```

The mutation registration still owns cache consequences. The app event owns
auth/session app-db and user-facing workflow.

### Article create/update

```clojure
(rf/reg-event-fx :editor/submit
  (fn [_ [_ draft]]
    {:fx [[:dispatch
           [:rf.mutation/execute
            {:mutation :realworld/save-article
             :params   {:draft draft}
             :instance [:editor/save (:slug draft)]
             :reply-to [:editor/save-replied]}]]]}))

(rf/reg-event-fx :editor/save-replied
  (fn [_ [_ {:keys [status value error]}]]
    (case status
      :ok
      {:fx [[:dispatch [:router/navigate
                        :article/show
                        {:slug (get-in value [:article :slug])}]]]}

      :error
      {:fx [[:dispatch [:editor/show-errors error]]]})))
```

No view watcher is needed. The accepted mutation reply becomes the next causal
event.

### Favorite/unfavorite across global and session scopes

```clojure
(rf/reg-mutation :realworld/favorite-article
  {:scope :rf.scope/global
   :request
   (fn [{:keys [slug]} _ctx]
     {:request {:method :post
                :url    (str "/articles/" slug "/favorite")}
      :decode  :json})

   :populates
   (fn [{:keys [slug]} result]
     [{:target {:resource :realworld/article
                :params   {:slug slug}
                :scope    :rf.scope/global}
       :value result}])

   :invalidates
   (fn [{:keys [slug]} _result]
     [{:scope :rf.scope/global
       :tags  #{[:article-list]
                [:article slug]}}
      {:scope {:from-db :realworld/session}
       :tags  #{[:feed]}}])})
```

The global article/detail/list facts and the session feed fact are addressed
separately. The just-populated article detail key is not immediately refetched
unless the descriptor opts into `:refetch-populated? true`.

### Session feed as a route resource

```clojure
(rf/reg-resource-scope :realworld/session
  {:inputs {:username [:db [:auth :user :username]]}
   :resolve
   (fn [{:keys [username]} _ctx]
     (when username
       [:rf.scope/session {:username username}]))})

(rf/reg-resource :realworld/feed
  {:scope {:from-db :realworld/session}
   :request
   (fn [{:keys [page]} _ctx]
     {:request {:method :get
                :url    "/articles/feed"
                :params {:limit  20
                         :offset (* 20 (dec page))}}
      :decode :json})
   :tags (fn [_params _value] #{[:feed] [:article-list]})})

(rf/reg-route :realworld/home
  {:path "/"
   :resources
   [{:resource  :realworld/feed
     :params    {:page 1}
     :scope     {:from-db :realworld/session}
     :blocking? true}]})
```

Route ownership, route leave release, subscriptions, invalidation descriptors,
and logout clear-scope can now all use the same named resolver.

### Logout clear-scope

Logout needs to clear the scope the user was *in* after current db has already
removed the user. The canonical idiom resolves the **concrete** old scope from
the handler's **coeffect db** — pre-transition by definition, the EP-0010-coherent
causal input — using the `resolve-resource-scope` resolver helper, and passes it to
`clear-scope` concretely:

```clojure
(rf/reg-event-fx :auth/logout
  (fn [{:keys [db]} _]
    (let [old-scope (rf/resolve-resource-scope db :realworld/session)]
      {:db (dissoc db :auth)
       :fx [[:dispatch
             [:rf.resource/clear-scope
              {:scope old-scope
               :cause :logout}]]]})))
```

`resolve-resource-scope` is a plain function over the resolver registry (a
resolver helper, not a new effect-API surface) — it resolves a named scope
against a given db value, with no resolution-timing ambiguity and no app-state /
dispatch side effects. It is **not a pure data helper**, though: like every
resolution site it emits `:rf.resource/scope-resolved` dev-time trace evidence.
There is **no `:snapshot-db` payload key**: a whole-db snapshot riding an event vector is an egress-bearing
record on traces and epoch history, rejected under EP-0015 (issue 7). A
`{:from-db …}` reference *may* still appear on a `clear-scope` payload (the
single use-time resolution rule applies); a reference that resolves nil at a
clear-scope site emits a loud diagnostic, never a silent no-op.

## Reference Implementation Plan

This is a proposed implementation sequence, not a requirement that all work
land in one PR.

### Slice 1: Spec 016 and guide amendments

- Add mutation phase order.
- Add call-site `:reply-to`.
- Add per-target invalidation descriptors.
- Add named resource-scope resolver grammar.
- Add populate-as-authoritative-load semantics.
- Add map-form exact target grammar.
- Add request-decoration doctrine.
- Add examples grounded in RealWorld.

### Slice 2: resolver registry

- Add `reg-resource-scope`.
- Store resolver definitions in the appropriate frame/runtime registry.
- Validate resolver ids, declared inputs, purity expectations, and result
  canonicalization.
- Implement input evaluation for `[:db path]`.
- Represent whole-db sugar explicitly if supported.
- Expose resolver id, declared inputs, current input values, and current
  resolved scope to Xray/tooling, with egress projection applied.

### Slice 3: route/event/subscription integration

- Allow resource `:scope` and route resource `:scope` to reference named
  resolvers.
- Ensure route entry can resolve db-derived scope before planning resource
  work.
- Ensure route leave releases the owner acquired under the resolved scope.
- Ensure event-side ensure/refetch and subscription reads use the same target
  resolution path.
- Preserve fail-closed behavior for nil scope.
- Add a diagnostic for unresolved scope that does not silently read global.

### Slice 4: mutation completion continuation

- Extend `:rf.mutation/execute` payload validation with `:reply-to`.
- Carry the target through the mutation instance/work row without exposing host
  handles in durable state.
- Build the EP-0011-compatible mutation reply map.
- Dispatch the reply target after cache consequences and instance settlement.
- Suppress continuation dispatch for stale/superseded replies.
- Trace continuation dispatch cause, target, work id, mutation id, instance,
  and status.

### Slice 5: scoped invalidation descriptors

- Parse bare tag-set shorthand into `{:scope :rf.scope/same :tags ...}`.
- Parse descriptor vectors/maps/functions.
- Resolve descriptor scope against mutation scope, concrete scope, global, or a
  named resolver.
- Lower all forms into one scoped invalidation engine.
- Record descriptor-level trace evidence.
- Preserve explicit broad invalidation as deliberate broad behavior, with dev
  warnings where appropriate.

### Slice 6: populate and exact-target normalization

- Accept map-form exact targets for `:populates`, `:patches`, and removes.
- Canonicalize params and scope before key construction.
- Mark populated keys loaded/fresh using the same bookkeeping as resource
  success.
- Exempt same-mutation populated keys from invalidation refetch by default.
- Honor `:refetch-populated? true`.
- Keep exact patch exact-only.

### Slice 7: request decoration and dogfood

- Document the managed-HTTP decoration seam.
- Add a RealWorld bearer-auth example.
- Update `realworld_resources` to use managed-HTTP decoration for auth/retry.
- Replace settings/editor watcher reactions with `:reply-to`.
- Make the session feed a route resource using a named scope resolver.
- Fix favorite/unfavorite invalidation with descriptors.

### Slice 8: tooling and docs

- Xray/resource tools show resolver ids and resolved scopes.
- Invalidation traces show descriptor resolution and hit counts.
- Mutation traces show phase order and continuation dispatch.
- Guide material explains the doctrine:
  "reply-to is for workflow; populate/patch/invalidate are for cache."

## Validation Plan

Implementation must add conformance or regression coverage for the general
rules below. These tests should be small synthetic cases first, with RealWorld
kept as dogfood acceptance rather than the only proof of correctness.

1. Mutation phase order: resolve scope -> send -> accept/suppress -> cache
   consequences -> instance settlement -> continuation.
2. Call-site `:reply-to` fires exactly once for an accepted reply.
3. The continuation fires after cache consequences and mutation instance
   settlement.
4. A stale or superseded mutation reply fires no continuation.
5. The continuation reply carries mutation id, params, instance, scope, status,
   value/error, affected keys, work id, frame id, completion time, and cause.
6. A mutation invalidates global and session-scoped targets in one execution.
7. A descriptor referencing a named scope resolver resolves against db at
   settle time.
8. A named resolver's declared inputs are visible to tooling and used to avoid
   unnecessary whole-db re-resolution.
9. Route entry, event ensure, and subscription reads resolve the same scoped key
   through the same named resolver.
10. Nil resolver output at a scoped site fails closed and never falls back to
    global.
11. A populated key is treated as loaded/fresh and is not refetched by the same
    mutation's invalidation unless `:refetch-populated? true`.
12. Existing exact-key `:patches`, `:populates`, `:invalidates`, and remove
    behavior continue to work after map-form target support.
13. Resource and mutation managed-HTTP requests receive configured request
    decoration.
14. Tooling/trace output explains resolver id, resolved scope, invalidation
    descriptors, hit counts, and continuation dispatch.

Dogfood acceptance for `examples/real-apps/realworld_resources/`:

- settings save uses call-site `:reply-to`;
- editor save/delete uses call-site `:reply-to` after the editor port lands on
  main;
- favorite/unfavorite invalidates global article/list entries and the session
  feed precisely;
- session feed is a declarative route resource again;
- auth headers and read retry are demonstrated through managed-HTTP decoration;
- no resource subscription starts fetches.

## Backwards Compatibility

This EP is additive for application authors:

- existing `:invalidates` tag-set shorthand remains valid;
- existing exact-key populate/patch forms may remain valid during migration;
- existing mutations without `:reply-to` behave as they do today;
- broad invalidation remains available only when explicitly requested.

The main compatibility pressure is documentation and examples. The guide should
move users toward map-form exact targets, named scope resolvers, and call-site
`:reply-to` rather than watcher reactions.

Because re-frame2 is still pre-1.0 and Spec 016 is new, the implementation may
choose to warn on older target tuple forms earlier than it would in a stable
release. If it does, the warning must point to the canonical map-form target.

## Security, Privacy, And Observability

Scope is a leak boundary. Derived scope must remain explicit and inspectable,
and nil scope must fail closed. This EP must not introduce any behavior that
uses global scope when a scoped resolver fails.

Per-target invalidation can cross scopes deliberately. That is a powerful
operation. Trace and tooling should make it visible when a mutation invalidates
entries outside its own resolved scope. Broad invalidation should be diagnosable
and reviewable.

Continuation reply maps can contain mutation params, response values, errors,
resource keys, and scopes. Those records must pass through the egress policy
once EP-0015 lands, and should follow current projection/elision conventions in
the meantime.

Request decoration must not leak sensitive header values into trace rows.
Traces should report that an auth interceptor applied, not the bearer token
itself.

## Alternatives Considered

### Continue using watcher reactions

Rejected. Watcher reactions are adapter-specific, per-site boilerplate, and
lifecycle-sensitive. They also invert ownership: the runtime knows when it has
accepted and settled a mutation reply, so the runtime should produce the causal
continuation.

### Add effect-returning mutation callbacks

Rejected. A callback that returns effects would create a second effect-producing
site outside event handlers. That weakens replay, tracing, interceptors, schema
checking, and frame causality. Prior-art libraries use callbacks because they do
not have re-frame2's event substrate.

### Add registration-level `:on-success`

Deferred/rejected for this EP. Registration-level cache consequences already
exist as `:patches`, `:populates`, and `:invalidates`. A registration-level
workflow target may be reconsidered later, but should be event-target-shaped and
must not be a callback.

### Use `:cross-scope? true` for mixed-scope invalidation

Rejected as the primary answer. It is too broad for ordinary writes and hides
which viewer-relative facts the mutation author intended to affect.

### Put viewer identity into tags

Rejected. It would undo Spec 016's deliberate split between fact tags and scope
identity. That split is what gives scoped resources their fail-closed leak
boundary.

### Anonymous db functions everywhere

Rejected as the primary API. Anonymous functions hide the dependency that
determines resource identity, are harder for tools to name, and do not give
invalidation descriptors a stable reference. A function sugar may exist, but it
must lower to an explicit whole-db resolver cost.

### Predicate cache queries

Rejected. TanStack-style query filters and SWR-style key matcher functions are
useful, but they introduce a broad cache-query language. This EP only needs
scoped invalidation descriptors and exact-key map targets.

### Include optimistic rollback now

Rejected. RealWorld shows optimism matters for latency-sensitive interactions,
but the design is not small: concurrent optimistic writes, server-side sorting
and filtering, inverse patches, rollback conflicts, and stale snapshot restore
need their own EP. The follow-on should require per-entry revision tracking and
rollback-by-invalidation (`:on-conflict :invalidate`) when a touched entry
changed after the optimistic patch.

## Implementation Notes

The internal runtime may normalize shipped cache consequences into private
operation records, for example populate, patch, invalidate, and remove. This EP
does not standardize those operation records as public API. If an optimistic
follow-on needs public transaction operations, it should do so explicitly and
reuse any internal experience from this implementation.

Named resolver evaluation should be deterministic and side-effect free. If
declared input values are unavailable in SSR, restore, or route planning, the
resolver should produce nil or an explainable unresolved state rather than
falling back.

`clear-scope` needs careful treatment around logout/account switch. The common
case wants to clear the old scope after current db has removed the user. Issue 7
settles this: the logout handler resolves the **concrete** old scope from its
**coeffect db** (pre-transition by definition) via the `resolve-resource-scope`
resolver helper and passes it to `clear-scope` concretely (see the corrected
[§Logout clear-scope](#logout-clear-scope) example). The rejected `:snapshot-db`
event-payload option is **not** an implementation choice — a whole-db snapshot on
an event vector is an egress-bearing record under EP-0015. A `{:from-db …}`
reference on a `clear-scope` payload remains valid under the single use-time
resolution rule and emits a loud diagnostic (never a silent no-op) when it
resolves nil.

## Guide Impact

Guide material should be updated in the resources chapter and routing chapter:

- show mutation `:reply-to` for workflow continuation;
- explain the split between workflow continuation and cache consequences;
- show per-target scoped invalidation for global plus session facts;
- show `reg-resource-scope` with declared inputs;
- show session feed as a route resource;
- show map-form exact targets for populate/patch;
- explain populate-as-authoritative-load and `:refetch-populated?`;
- show managed-HTTP request decoration for auth headers and read retry;
- restate that resource subscriptions are passive reads.

The examples should use RealWorld-shaped snippets because this EP is motivated
by RealWorld dogfooding.

## Open Issues

**All nine issues were ruled 2026-06-11 (Mike, in-session; bead `rf2-6o6a62`),
merging three convergent analyses.** Original recommendations are kept verbatim
as the record of what was ruled; dispositions and riders are inline.

1. Should registration-level mutation `:reply-to` be added now?
   **Recommendation:** no. Defer until a real invariant non-cache workflow
   appears that cannot be cleanly expressed at call sites.
   **Disposition: as recommended.** Riders: registration-level workflow would
   hide app behavior inside the remote-write definition (the locality
   argument); EP-0015's observability work is explicitly NOT the deferral
   trigger — cross-cutting post-write concerns (toasts, logging) were already
   considered-and-rejected to the instrumentation/interceptor axis (the
   MutationCache rejection). If ever added, it is event-target-shaped, never a
   callback.

2. What exact input descriptor grammar should `reg-resource-scope` support?
   **Recommendation:** ship `[:db path]` first, reserve route/frame sources,
   and treat anonymous whole-db functions as explicit-cost sugar.
   **Disposition: as recommended, now contractually grounded:** the
   `{:inputs … :resolve …}` grammar is pinned forward-compatible by EP-0012's
   disposition 3 (the reserved named-declaration shape), and the `path` in
   `[:db path]` is an EP-0012 concrete `:rf/path`. Rider from EP-0015's
   disposition 8: declared inputs make resolvers members of the
   derived-sensitivity graph — whole-db sugar degrades both narrow
   re-resolution and sensitivity-inheritance precision, so tooling marks the
   whole-db cost on both axes.

3. Should `:cross-scope?` remain after descriptors exist?
   **Recommendation:** keep it as an explicit broad operation with dev warnings
   and trace evidence. Do not let it become the ergonomic path.
   **Disposition: as recommended, with the justification stated:** descriptors
   can only name scopes the call site knows; "invalidate this tag in every
   scope currently holding it" (admin tooling, cache-poisoning response,
   migration) targets scopes unenumerable at the call site but known to the
   cache — a genuinely different operation. Riders: cross-scope invalidations
   MUST carry `:cause` evidence; the full lattice is — bare invalidate-tags
   with no scope = loud error (fail-closed, `rf2-pvdae1`), descriptors = the
   precise path, `:cross-scope? true` = the explicit audited escape; cross-scope
   invalidations are privacy-relevant trace events per EP-0015.

4. Should map-form exact targets replace tuple-style scoped keys immediately?
   **Recommendation:** make map form canonical in spec/docs/traces, accept old
   tuple forms during a short migration if the implementation already exposes
   them.
   **Disposition: STRONGER than the recommendation — no migration window.**
   The map form is the **only** accepted public input form from day one;
   in-repo tuple writers (`realworld_resources/mutations.cljs`) are swept in
   the same change (pre-alpha; no external consumers). The tuple remains the
   documented **internal/storage** representation — this is an input-form vs
   storage-form distinction recorded per EP-0007 rule 3, not two spellings of
   one fact. Grounds: the `rf2-cg7llv` and EP-0010 disposition-5 lessons —
   coexistence windows become permanent and the de-facto form wins. This also
   eliminates the `rf2-vv87xz` spelling-ambiguity class at the API boundary.

5. How should route-derived tenant scope be represented?
   **Recommendation:** do not add a second mechanism in this EP. Either mirror
   route match into frame state or reserve a named resolver input source for a
   later routing EP.
   **Disposition: as recommended, with the reservation named precisely:** the
   route match is already mirrored into the fold — `[:rf.runtime/routing
   :current]` is durable runtime-db — so the reserved input source is
   **`[:runtime path]`** (already in EP-0014's input vocabulary); no new
   mirroring, no anonymous route functions. Deferred until a consumer; the
   named un-defer consumer is the tenant-switcher testbed (`rf2-s6rviz`).
   Selection rule: viewer identity that is app state → `[:db …]`; a pure
   route fact → the reserved `[:runtime …]`.

6. Should `:reply-to` fire for accepted error replies?
   **Recommendation:** yes. A workflow may need to fold validation errors,
   notifications, or form state. The reply `:status` tells the handler what
   happened. Stale/suppressed replies still do not fire.
   **Disposition: yes — keyed on acceptance, not on a status enumeration.**
   The rule: `:reply-to` fires for **any accepted terminal reply** (`:ok`,
   `:error`, and an accepted terminal `:cancelled`); it never fires for
   stale/suppressed replies. One rule, no per-status table to drift, and it
   settles the cancellation edge the delivery rule left implicit.

7. How should logout clear the old named scope after auth state is removed?
   **Recommendation:** require either concrete old scope or resolver evaluation
   against a supplied pre-change snapshot. Do not let `clear-scope` resolve
   against already-cleared db and silently do nothing.
   **Disposition: REVISED — the snapshot API is dropped.** Canonical: the
   logout handler resolves the **concrete** scope from its **coeffect db**
   (pre-transition by definition — the existing idiom, and the EP-0010-coherent
   answer: the cofx db is the causal input) and passes it to `clear-scope`
   concretely. A resolver helper — resolve a named resource scope against a given
   db value — ships for ergonomics (a plain function over the registry; no new
   effect-API surface, no resolution-timing ambiguity, no app-state / dispatch
   side effects, though it emits `:rf.resource/scope-resolved` dev trace like
   every resolution site, so not a pure data helper). `:snapshot-db` on the
   event payload is rejected: a whole-db snapshot in an event vector is an
   egress-bearing record riding traces and epoch history — unacceptable under
   EP-0015. The §Logout clear-scope example is corrected in the action wave.
   Tripwire: a `{:from-db …}` reference that resolves nil at a clear-scope
   site emits a loud diagnostic, never a silent no-op. The single use-time
   resolution rule for `{:from-db …}` references is preserved.

8. Is `:refetch-populated?` the right spelling?
   **Recommendation:** yes unless the implementation already has a closer
   established option name. It says exactly what it changes.
   **Disposition: as recommended** (no established competitor exists in
   Spec 016). Wording precision for the spec edit: the flag changes exactly
   whether a key populated by this mutation may be immediately refetched by
   this same mutation's invalidation pass.

9. Should optimistic rollback be the next resources EP?
   **Recommendation:** only when a consumer requires sub-RTT write feedback.
   The likely follow-on scope is optimistic `:on-start`, tag-addressed patching,
   per-entry revision tracking, rollback-by-invalidation on conflict, and
   explicit transaction traces.
   **Disposition: as recommended — the deferral stands with its consumer
   trigger.** Riders: the follow-on scope **keeps tag-addressed patching**
   (the `patch-article-everywhere` cross-view-consistency demand is the
   canonical motivating case; exact-key-only would ship the machinery while
   missing the demand that justified it) and excludes the normalized graph
   cache; the identity substrate for per-entry revision tracking is settled by
   EP-0012's disposition 5; the recommended driver when the trigger fires is a
   Linearlite-class port (the local-first ecosystem's canonical
   optimistic-workload benchmark), not an invented stressor.
   **Delivered by [EP-0019](EP-0019-optimistic-mutation-rollback.md) (final):**
   the optimistic apply / commit / rollback / reconcile surface graduated there,
   carrying exactly this follow-on scope (tag-addressed patching via
   `:optimistic-tags`, per-entry revision tracking, rollback-by-invalidation on
   conflict), and reusing this EP's scoped-invalidation descriptors and
   `:reply-to` continuation unchanged.

## Recommendation

Accept this EP as a focused Spec 016 amendment.

The RealWorld dogfood validates the resources architecture but shows that three
seams are missing for ordinary applications: mutation replies need causal
workflow continuations, invalidation must target multiple scopes precisely, and
scope identity needs one reusable resolver mechanism. Prior art confirms these
capabilities are mainstream, while first principles show re-frame2 should
express them as data and event targets, not callbacks.

The recommended cut is intentionally narrow:

- ship call-site mutation `:reply-to`;
- ship per-target scoped invalidation;
- ship named db-derived scope resolvers with declared inputs;
- define populate-as-authoritative-load;
- make map-form exact targets canonical;
- document managed-HTTP request decoration;
- defer optimistic rollback and tag-addressed patching to their own EP.

That completes the high-value gaps exposed by RealWorld without expanding Spec
016 into a general cache framework.
