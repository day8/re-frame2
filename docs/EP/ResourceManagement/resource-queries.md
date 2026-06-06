# EP: Resource Management - Resources

Status: proposal

Date: 2026-06-06

Related:

- [Guide 10 - HTTP](../../guide/10-http.md)
- [Guide 19 - Routing](../../guide/19-routing.md)
- [Guide 21 - Runtime model](../../guide/21-dynamic-model.md)
- [Pattern - Remote Data](https://github.com/day8/re-frame2/blob/main/spec/Pattern-RemoteData.md)
- [Spec 014 - HTTP Requests](https://github.com/day8/re-frame2/blob/main/spec/014-HTTPRequests.md)
- [Spec 012 - Routing](https://github.com/day8/re-frame2/blob/main/spec/012-Routing.md)
- [Spec 005 - State Machines](https://github.com/day8/re-frame2/blob/main/spec/005-StateMachines.md)

## Summary

This enhancement proposes an optional `day8/re-frame2-resources` artifact for
server-state and external-resource management.

The public vocabulary should be:

```clojure
(rf/reg-resource ...)
(rf/reg-mutation ...)
```

A resource is a named, cached read of remote or external state. A mutation is a
causal write that may invalidate, patch, or refetch resources. The proposal uses
"resource" as the public term because it fits route data, HTTP reads, GraphQL
reads, local persistence, and future non-HTTP sources. "Query" remains a useful
prior-art term, but it should not be the re-frame2 API name.

The core rule is:

> Resources are remote server-state as runtime-managed read models.

That keeps the design inside the re-frame2 ethos:

- views are declarative reads;
- subscriptions and flows are materialized views over state;
- events are causal;
- routes are application state and should declare the remote data they need;
- state machines model lifecycles and workflows;
- tools and AI agents need enumerable, redacted, frame-aware metadata.

The initial implementation should be a read-resource MVP: registration,
explicit ensure/refetch/invalidate events, passive subscriptions, active owners,
cache scopes, stale/fresh policy, dedupe, GC, route integration, SSR
preload/hydration, and Xray/tool visibility. It should also define timer policy
for stale/GC behavior and resolve background-refresh error semantics up front.

Two distinctions are important enough to be part of the first specification:

- owners keep resources alive; causes explain why work happened;
- params identify the remote read inside a cache scope; scopes prevent
  `/api/me`, tenant, locale, impersonation, and SSR cache leaks.

Mutations and focus/reconnect revalidation should be the first public-beta gate,
not distant future work. A read-resource MVP is useful for route and SSR data,
but the artifact should not be presented as complete resource management until
minimal mutation invalidation and active-stale revalidation are in place.
Optimistic updates, polling, infinite resources, generic transports, and
normalized caches are important later slices, but should not make the first
artifact too wide.

## Problem

Every substantial SPA repeats the same server-state machinery:

- identify remote data by endpoint and params;
- fetch it;
- show first-load state;
- keep old data visible during background refresh;
- deduplicate identical in-flight requests;
- suppress or abort stale replies;
- cache successful responses;
- decide when cached data is stale;
- refetch on navigation, invalidation, focus, reconnect, or manual demand;
- garbage-collect inactive cache entries;
- mutate remote data and invalidate affected reads;
- prefetch for routes and SSR;
- hydrate without double-fetching;
- keep auth-, tenant-, locale-, and user-scoped caches from bleeding into each
  other;
- keep paginated and filtered tables from blanking on every param change;
- show tools which page, event, machine, or route is waiting on which data.

re-frame2 already has strong primitives for pieces of this:

- `:rf.http/managed` owns transport mechanics such as retry, abort, timeout,
  schema decode, frame-aware replies, test stubs, and structured failure data.
- Pattern-RemoteData gives the canonical `:loading` vs `:fetching` distinction.
- Routing owns route metadata, `:on-match`, nav-tokens, route transition state,
  and SSR route entry.
- Frames isolate app instances, story frames, tests, and SSR requests.
- State machines model long-running workflows and semantic retry.
- Xray, traces, schemas, and pair tools depend on declarative surfaces.

The gap is policy and bookkeeping: resource identity, active ownership, stale
policy, dedupe, invalidation, route graphs, hydration, GC, and tool-readable
state. Apps can build those by hand, but that produces hidden conventions and
bugs in the exact places users notice: flickering loaders, stale screens,
duplicate requests, route waterfalls, cross-user cache leaks, invalidation
storms, and optimistic UI races.

## Developer and AI Use Cases

The feature should help programmers and AI maintainers answer concrete
questions:

- What remote data does this route require?
- Which resource instances are active right now, and who owns them?
- Is this screen blank because it is first-loading, background-refreshing, or
  blocked on an error?
- If this mutation succeeds, which cached reads should become stale?
- Did the stale response win a race, or was it suppressed?
- Why did this request happen: route entry, manual event, invalidation, focus,
  reconnect, polling, or SSR preload?
- Is this cache entry scoped to the current user, tenant, locale, impersonation,
  or SSR request?
- Which remote values are safe for tools and AIs to inspect?
- Can this app be server-rendered without leaking another user's cache?

Features that do not answer questions like these should be treated as later
research rather than MVP requirements.

## Prior Art

### TanStack Query

TanStack Query is the main external gold standard for server-state cache
semantics. The parts re-frame2 should learn from are:

- structured, serializable identity keys;
- deterministic key hashing where map/object key order does not change
  identity;
- stale/fresh state and explicit stale windows;
- inactive-entry garbage collection;
- in-flight request dedupe;
- retries on failure;
- invalidation that marks matching entries stale and refetches active entries;
- optimistic update snapshots and rollback;
- SSR prefetch/dehydrate/hydrate;
- router-level prefetching to avoid component-tree request waterfalls.

The part re-frame2 should not copy is the React hook as the causal boundary.
In re-frame2, views read and events cause.

### RTK Query

RTK Query shows a Redux-oriented version of the same design:

- endpoint definitions identify reads and writes;
- endpoint params form cache keys;
- active subscribers share cached data;
- unused entries are retained and then removed;
- query endpoints provide tags;
- mutation endpoints invalidate tags.

The tag model is the useful lesson. Tags are explicit, simple, and proven. They
are also fallible, so re-frame2 should later add Xray or contract-graph lint
for broad, missing, or ineffective tags.

### SWR

SWR's useful lesson is stale-while-revalidate UX: keep displaying previous data
while refreshing in the background. In re-frame2 terms, that is exactly the
Pattern-RemoteData `:fetching` state.

The hook-oriented fetch lifecycle is less useful for re-frame2. Subscription
lifecycle should not be the default cause of network work.

### Apollo and Relay

Apollo and Relay show the normalized graph-cache path. That is powerful for
GraphQL and entity-heavy applications, but it is too much for the first
resource artifact.

re-frame2 should start with a document/resource cache keyed by resource identity
and canonical params. Normalized entity storage or relational materialized views
can become a later scale-gated enhancement.

### shipclojure/re-frame-query

`shipclojure/re-frame-query` is important prior art inside the re-frame
ecosystem. It proves demand for this feature class and already includes many of
the right ideas:

- declarative queries and mutations;
- automatic success/failure callback wiring;
- tag invalidation with active-query refetch;
- per-query cache-time GC;
- polling;
- conditional fetching;
- prefetching;
- loading vs background-refetch status;
- transport-agnostic effects;
- infinite queries;
- mutation lifecycle hooks;
- passive route-driven use via `ensure-query`, `mark-active`,
  `mark-inactive`, and `query-state`;
- an ergonomic subscription-driven mode for simpler apps.

The route-driven option is the important convergence point: fetch from a router
or event, render through a passive subscription, and keep re-frame subscriptions
pure.

The re-frame2 artifact should learn from `re-frame-query`, but not clone it.
re-frame2 needs tighter integration with frames, route metadata, SSR,
runtime/app-db partitioning, managed HTTP, Xray, privacy egress, and AI-readable
metadata. It should also avoid making subscription-driven fetching the default
because that weakens event causality and makes route behavior harder to inspect.

## Design Principles

### Passive Read, Explicit Causal Fetch

Views should read resource state:

```clojure
(rf/subscribe [:rf.resource/state
               {:resource :article/by-slug
                :params   {:slug "welcome"}}])
```

Views should not be the main cause of fetching. Route entry, events, machines,
or explicit resource events should cause resource work:

```clojure
[:rf.resource/ensure
 {:resource :article/by-slug
  :scope    [:rf.scope/session {:user-id "u-42" :tenant-id "acme"}]
  :params   {:slug "welcome"}
  :owner    [:route :route/article nav-token]
  :cause    [:route-entry :route/article nav-token]}]
```

A future ergonomic `:rf.resource/live` subscription can be reconsidered after
the causal route/event model has shipped. It should not be in the v1 MVP.

### Runtime-Owned, Not User-Owned

Resource state is runtime-managed process state. App code can read it through
public subscriptions and accessors, and influence it through events, but should
not hand-edit the resource runtime slice.

The target frame-state shape is:

```clojure
{:rf.db/app
 <user-app-db>

 :rf.db/runtime
 {:rf.runtime/resources
  {:entries
   {[[:rf.scope/session {:user-id "u-42" :tenant-id "acme"}]
     :article/by-slug
     {:slug "welcome"}]
    {:resource/id    :article/by-slug
     :scope          [:rf.scope/session {:user-id "u-42" :tenant-id "acme"}]
     :params         {:slug "welcome"}
     :status         :loaded
     :data           {:title "Welcome"}
     :error          nil
     :refresh-error  nil
     :loaded-at      1780752000000
     :stale-at       1780752060000
     :gc-at          nil
     :generation     3
     :request-id     [:rf.resource :article/by-slug {:slug "welcome"} 3]
     :tags           #{[:article "welcome"]}
     :active-owners  #{[:route :route/article nav-token]}}}

   :tag-index
   {[:article "welcome"]
    #{[[:rf.scope/session {:user-id "u-42" :tenant-id "acme"}]
       :article/by-slug
       {:slug "welcome"}]}}

   :owner-index
   {[:route :route/article nav-token]
    #{[[:rf.scope/session {:user-id "u-42" :tenant-id "acme"}]
       :article/by-slug
       {:slug "welcome"}]}}}}}
```

Until the frame-state partition lands, the interim implementation can use:

```clojure
[:rf/runtime :resources]
```

The specification should still be written toward `:rf.db/runtime`, so ordinary
`:db` event handlers cannot accidentally wipe resource state.

### Resource Identity Is Data

A resource instance is identified by a cache scope, a resource id, and canonical
params:

```clojure
[cache-scope resource-id canonical-params]
```

For example:

```clojure
[[:rf.scope/session {:user-id "u-42" :tenant-id "acme"}]
 :article/by-slug
 {:slug "welcome"}]
```

Rules:

- cache scope must be serializable EDN data;
- the default scope is `[:rf.scope/global]` for data that is genuinely
  process-independent;
- user-, tenant-, locale-, permission-, impersonation-, and session-dependent
  reads must use an explicit scope or put those values in params;
- logout, account switch, tenant switch, and impersonation changes must have a
  causal way to clear or replace the affected scope;
- params must conform to `:params-schema`;
- params must be serializable EDN data;
- maps are canonicalized so key order does not affect identity;
- host values such as functions, promises, dates, DOM nodes, AbortControllers,
  and JS objects are rejected;
- nil vs missing must be schema-defined, not accidental;
- every variable that affects remote identity must be represented in params;
- avoid a separate `:cache-key` escape hatch in v1 unless it is validated,
  visible in tools, and tested heavily.

### Scope Resolution

Scope resolution must be explicit and deterministic. The precedence should be:

1. `:scope` supplied on the resource event or subscription payload;
2. route-resource `:scope` resolver;
3. resource spec `:scope` resolver;
4. `[:rf.scope/global]`.

The global default should be treated as a deliberate choice, not a convenient
place to hide user-specific data. Xray should warn when a resource with no
scope resolver calls a request that looks session-dependent, such as `/me`,
`/current-user`, tenant-local URLs, or requests with auth-derived params.

`clear-scope` is a causal operation. It should:

- remove or mark unusable every entry in that scope;
- release owners in that scope;
- abort in-flight requests that have no remaining owner outside that scope;
- suppress late replies by scope + generation checks;
- emit trace rows explaining which entries were removed, aborted, or left alone.

Auth token refresh does not necessarily require clearing scope if the user,
tenant, permissions, and impersonation state are unchanged. Login, logout,
account switch, tenant switch, permission-set change, locale switch when it
affects wire data, and impersonation enter/exit do require either a new scope or
an explicit clear/replace operation.

Invalidation is scoped by default. A cross-scope invalidation must opt in
explicitly and be visible in Xray because it can refetch or stale data for
multiple users, tenants, story frames, or SSR requests.

### Active Owners, Not Component Observers

TanStack Query and RTK Query talk about active observers or subscriptions.
re-frame2 should talk about active owners.

Owners are liveness leases. They answer:

- should invalidation refetch now, or only mark stale?
- should polling continue?
- may the entry be garbage-collected?
- what should route leave release?
- which workflows are intentionally keeping this resource active?

Examples:

```clojure
[:route :route/article nav-token]
[:machine :checkout/flow machine-instance-id]
[:ssr request-id nav-token]
```

Route owners must include the navigation token. `[:route :route/article]` is
not precise enough because the same route can be entered multiple times with
different params, pending work, or SSR request frames.

Do not use ordinary event ids as durable owners unless the event creates a
releaseable lease. A manual refresh, a button click, or a one-shot dashboard
open should usually be a cause, not an owner.

### Causes Explain Why Work Happened

Causes are trace and diagnostic metadata. They answer "why did this happen?"
without changing liveness, GC, polling, or refetch decisions.

Examples:

```clojure
[:route-entry :route/article nav-token]
[:manual :article/refresh]
[:invalidate {:tags #{[:article "welcome"]}}]
:focus
:reconnect
:ssr-preload
:hydration
```

Ensure/refetch events should accept both `:owner` and `:cause`. `:owner`
changes the active-owner set. `:cause` is recorded in trace/resource history.
Trace dispatch ids, event trace ids, and Xray focus state belong in cause/trace
metadata, not in durable owners.

Xray must not become an owner by observing. Opening a devtool must not pin a
resource, refetch it, extend GC, or alter polling. A future "pin this resource"
debug action would be an explicit tool mutation with its own trace, not normal
inspection.

### Sub-Resources Are Ordinary Resources

Do not introduce a separate `sub-resource` primitive in v1.

A sub-resource is usually a naming, ownership, and invalidation relationship,
not a different lifecycle. It still needs the same identity, stale/fresh state,
owners, dedupe, SSR behavior, and GC as any other resource.

Model it as an ordinary resource whose params include the parent identity:

```clojure
(rf/reg-resource
  :article/comments
  {:params-schema
   [:map [:slug :string]]

   :request
   (fn [{:keys [slug]} _]
     {:method :get
      :url    (str "/api/articles/" slug "/comments")
      :decode [:vector :app/comment]})

   :tags
   (fn [{:keys [slug]} _comments]
     #{[:article slug]
       [:comments slug]})})
```

Route metadata can then own both the parent resource and the child collection:

```clojure
:resources
[{:resource  :article/by-slug
  :params    (fn [route] {:slug (get-in route [:params :slug])})
  :blocking? true}

 {:resource  :article/comments
  :params    (fn [route] {:slug (get-in route [:params :slug])})
  :blocking? false}]
```

If Xray later needs to draw this relationship explicitly, add optional metadata
such as `:parent-resource` or `:resource/parent` for tooling. That metadata
should not change cache identity or lifecycle semantics.

### Lifecycle Is an FSM

Every resource instance has a lifecycle:

```text
:idle
  ensure/refetch without data -> :loading

:loading
  success -> :loaded
  failure -> :error

:loaded
  stale/refetch -> :fetching
  invalidate inactive -> :loaded with stale timestamps/invalidated-at

:fetching
  success -> :loaded
  failure -> :loaded with :refresh-error, preserving last-known-good data
  superseded reply -> previous stable state

:error
  refetch without data -> :loading
  refetch with data -> :fetching
```

The default implementation should be a compact transition function, not a
spawned machine per resource entry. Semantic retry, multi-step negotiation,
streaming, and workflow-coupled reads can graduate to explicit machines.

Transport retry belongs to HTTP. Semantic retry belongs to machines.

## Proposed Solution

Ship an optional artifact:

```clojure
day8/re-frame2-resources
```

Requiring `re-frame.resources` wires the artifact into the core facade, feature
registry, routing integration, SSR support, and tool metadata.

### MVP Scope

V1 should include:

- `reg-resource`;
- passive resource subscriptions;
- explicit ensure/refetch/invalidate/remove events;
- active owners;
- non-liveness causes/reasons for traceability;
- explicit cache scopes and scope clearing;
- canonical params;
- stale/fresh policy;
- in-flight dedupe;
- stale reply suppression;
- inactive-entry GC;
- route `:resources`;
- blocking and non-blocking route resources;
- SSR preload and hydration for route resources;
- managed HTTP as the only built-in transport;
- exact tag invalidation;
- conditional route resources and clear params-failure behavior;
- timer policy for stale/fresh and inactive GC;
- explicit `:refresh-error` semantics for failed background refresh;
- Xray/tool summaries with redaction;
- conformance tests.

First public-beta gate:

- `reg-mutation`;
- focus and reconnect revalidation for active stale resources;
- mutation invalidation integration.

Later slices:

- optimistic rollback;
- generic transport extension;
- polling and interval revalidation;
- infinite resources;
- normalized entity caches;
- automatic graph-derived invalidation;
- subscription-driven fetching;
- offline persistence and cross-tab broadcast.

The follow-on mutation slice is still part of the resource-management direction,
but keeping it out of the read-resource MVP reduces design risk.

### Public API

Registration:

```clojure
(rf/reg-resource resource-id resource-spec)
(rf/clear-resource resource-id)

;; Later slice:
(rf/reg-mutation mutation-id mutation-spec)
(rf/clear-mutation mutation-id)
```

Events use map payloads, not positional argument vectors:

```clojure
[:rf.resource/ensure
 {:resource :article/by-slug
  :scope    [:rf.scope/session {:user-id "u-42" :tenant-id "acme"}]
  :params   {:slug "welcome"}
  :owner    [:route :route/article nav-token]
  :cause    [:route-entry :route/article nav-token]}]

[:rf.resource/refetch
 {:resource :article/by-slug
  :scope    [:rf.scope/session {:user-id "u-42" :tenant-id "acme"}]
  :params   {:slug "welcome"}
  :cause    [:manual :article/refresh]}]

[:rf.resource/invalidate-tags
 {:scope [:rf.scope/session {:user-id "u-42" :tenant-id "acme"}]
  :tags  #{[:article "welcome"]}
  :cause [:mutation :article/save mutation-id]}]

[:rf.resource/release-owner
 {:owner [:route :route/article nav-token]}]

[:rf.resource/clear-scope
 {:scope [:rf.scope/session {:user-id "u-42" :tenant-id "acme"}]
  :cause :logout}]

[:rf.resource/remove
 {:resource :article/by-slug
  :scope    [:rf.scope/session {:user-id "u-42" :tenant-id "acme"}]
  :params   {:slug "welcome"}}]

;; Later slice:
[:rf.mutation/execute
 {:mutation :article/save
  :params   article}]
```

Subscriptions are passive:

```clojure
[:rf.resource/state
 {:resource :article/by-slug
  :scope    [:rf.scope/session {:user-id "u-42" :tenant-id "acme"}]
  :params   {:slug "welcome"}}]

[:rf.resource/data
 {:resource :article/by-slug
  :scope    [:rf.scope/session {:user-id "u-42" :tenant-id "acme"}]
  :params   {:slug "welcome"}}]

[:rf.resource/status
 {:resource :article/by-slug
  :scope    [:rf.scope/session {:user-id "u-42" :tenant-id "acme"}]
  :params   {:slug "welcome"}}]

[:rf.resource/loading?
 {:resource :article/by-slug
  :scope    [:rf.scope/session {:user-id "u-42" :tenant-id "acme"}]
  :params   {:slug "welcome"}}]

[:rf.resource/fetching?
 {:resource :article/by-slug
  :scope    [:rf.scope/session {:user-id "u-42" :tenant-id "acme"}]
  :params   {:slug "welcome"}}]

[:rf.resource/stale?
 {:resource :article/by-slug
  :scope    [:rf.scope/session {:user-id "u-42" :tenant-id "acme"}]
  :params   {:slug "welcome"}}]

[:rf.resource/error
 {:resource :article/by-slug
  :scope    [:rf.scope/session {:user-id "u-42" :tenant-id "acme"}]
  :params   {:slug "welcome"}}]

[:rf.resource/refresh-error
 {:resource :article/by-slug
  :scope    [:rf.scope/session {:user-id "u-42" :tenant-id "acme"}]
  :params   {:slug "welcome"}}]
```

Introspection:

```clojure
(rf/resource-meta :article/by-slug)
(rf/resource-state :article/by-slug {:slug "welcome"} {:frame :rf/default})
(rf/resources {:frame :rf/default})
```

### Resource Registration

Example:

```clojure
(rf/reg-resource
  :article/by-slug
  {:doc "Article detail by slug."

   :params-schema
   [:map [:slug :string]]

   :data-schema
   :app/article

   :request
   (fn [{:keys [slug]} _ctx]
     {:method :get
      :url    (str "/api/articles/" slug)
      :decode :app/article})

   :transport
   :rf.http/managed

   :stale-after-ms
   60000

   :gc-after-ms
   300000

   :tags
   (fn [{:keys [slug]} _data]
     #{[:article slug]})

   :sensitive?
   false})
```

Required keys:

- `:params-schema` validates and canonicalizes params;
- `:request` returns managed HTTP request data;
- `:data-schema` validates successful data when transport decode supports it.

Optional v1 keys:

- `:doc`;
- `:transport`, fixed initially to `:rf.http/managed`;
- `:stale-after-ms`;
- `:gc-after-ms`;
- `:scope`, a function or declarative resolver for the cache scope when the
  caller does not supply one explicitly;
- `:tags`;
- `:sensitive?` / `:large?` / schema-based classification.

Deferred keys:

- `:poll-ms`;
- `:revalidate`;
- arbitrary `:placeholder`;
- `:transport` extension protocols;
- `:cache-key`;
- `:infinite`;
- mutation-only keys such as `:invalidates`, `:optimistic`, and `:rollback`.

Do not add a TanStack-style `:select` key in v1. In re-frame2, projections are
ordinary subscriptions layered over `[:rf.resource/data ...]`. That is not a
missing feature; it is a structural advantage of the subscription graph.

### Status Semantics

Resource state should use Pattern-RemoteData semantics, but durable entries
should store facts rather than derived booleans:

```clojure
{:status     :idle | :loading | :fetching | :loaded | :error
 :data       <last-known-good-or-nil>
 :error      <first-load-error-or-nil>
 :refresh-error <background-refresh-error-or-nil>
 :loaded-at  <ms-or-nil>
 :stale-at   <ms-or-nil>
 :invalidated-at <ms-or-nil>
 :attempt    <int>
 :generation <int>
 :request-id <request-id-or-nil>
 :tags       <set>
 :owners     <set>}
```

The important invariant:

- `:loading` means first load with no usable data;
- `:fetching` means work is in flight while prior data stays visible;
- `:error` means the resource has no usable data because the first load failed;
- freshness is orthogonal to load status. A `:loaded` entry may be stale, and a
  `:fetching` entry may be refreshing stale data;
- a failed background refresh keeps the prior `:data`, returns to `:loaded`, and
  records the failure in `:refresh-error`;
- `:refresh-error` is cleared by the next successful load or refresh;
- `:stale?`, `:loading?`, `:fetching?`, and `:has-data?` are public derived
  subscription values, not durable stored facts;
- views should not have to infer "error with stale data" from
  `(:status state)` plus `(:has-data? state)`.

First-load failure:

```clojure
{:status :error
 :data nil
 :error {:category :http/status :status 503}
 :refresh-error nil
 :has-data? false}
```

Background-refresh failure:

```clojure
{:status :loaded
 :data {:title "Welcome"}
 :error nil
 :refresh-error {:category :http/status :status 503}
 :has-data? true
 :fetching? false}
```

This keeps the `:loading` / `:fetching` promise intact: views do not guess
whether they are looking at a blank first-load failure or stale data with a
refresh warning.

### Structural Sharing

Successful resource loads should preserve the old `:data` value when the newly
decoded data is `=` to the previous data. This keeps downstream subscriptions
and views quiet when a background refresh returns identical EDN.

Large or non-EDN values may need a later explicit merge/structural-sharing hook,
but the v1 default should be the re-frame2 value model: compare values, preserve
the old value when nothing changed, and make equality decisions observable in
trace rows when they affect a resource transition.

### Stale And GC Scheduling

Because `:stale-after-ms` and `:gc-after-ms` are v1 features, their scheduling
rules are part of v1 too. They have the same hidden-tab and event-drain concerns
as later polling.

Rules:

- freshness is computed from durable timestamps such as `:loaded-at` and
  `:stale-at`, not from trusting that a timer fired exactly on time;
- a stale timer may enqueue a resource event, but the handler must re-check the
  current entry before writing;
- inactive GC may use host timers, but GC must re-check owner sets and entry
  generation after wake;
- timers and host handles live in side tables, not in frame-state;
- frame destroy cancels all resource timers for that frame;
- a hidden tab can delay timers without corrupting correctness; on focus or
  reconnect, the first public-beta revalidation slice should scan active stale
  entries and refetch by event.

### Route Integration

Add `:resources` as route metadata:

```clojure
(rf/reg-route
  :route/article
  {:path "/articles/:slug"
   :params [:map [:slug :string]]

   :resources
   [{:resource  :article/by-slug
     :params    (fn [route]
                  {:slug (get-in route [:params :slug])})
      :scope     (fn [_route ctx]
                   (:current-session-scope ctx))
      :blocking? true}

     {:resource  :comments/list
      :params    (fn [route]
                   {:slug (get-in route [:params :slug])})
      :when      (fn [route _ctx]
                   (some? (get-in route [:params :slug])))
      :blocking? false
      :keep-previous? true}]})
```

On route entry:

1. routing resolves the route and nav-token;
2. route resource `:when` predicates are evaluated;
3. route resource scopes and params are computed and validated;
4. each resource is marked active with owner `[:route route-id nav-token]`;
5. each resource is ensured with cause `[:route-entry route-id nav-token]`;
6. blocking resources are tracked under the nav-token;
7. non-blocking resources fetch in the background;
8. failures in blocking resources update route transition/error state;
9. Xray can display the route/resource graph without parsing handlers.

On route leave or superseded navigation:

1. route-owned resources are released by owner token;
2. polling for owners that went away stops in later slices;
3. in-flight work is aborted only when no remaining owner still needs it;
4. stale replies are suppressed by generation/nav-token even if abort is not
   available;
5. inactive resources become eligible for `:gc-after-ms` cleanup.

`blocking?` should be defined precisely:

- it keeps the route transition in a loading/pending state;
- it gives SSR a wait point before render;
- it does not have to block URL commit or prevent a client skeleton from
  rendering;
- if hydrated data is already fresh, it should not block.

Existing `:on-match` remains canonical for arbitrary route-entry work.
`:resources` is declarative server-state metadata layered beside it, not a
second router.

Route resources should define params-failure behavior explicitly. A failed
params schema should be a route/resource planning error visible in route state
and Xray, not a silent cache miss. Conditional resources should use `:when`
rather than sentinel nil params.

Dependent route resources should be modeled as a route plan, not a hidden view
effect. The simple v1 rule can be conservative: compute independent resources
in parallel; let a resource declare `:after` another route-resource key when its
params depend on the first resource's data; show the dependency and any
waterfall in Xray.

Routes are not required. An app can use resources entirely from events and
machines:

```clojure
(rf/reg-event-fx
  :dashboard/opened
  (fn [_ [_ user-id]]
    {:fx [[:dispatch
           [:rf.resource/ensure
            {:resource :dashboard/summary
             :params   {:user-id user-id}
             :owner    [:lease :dashboard/opened user-id]
             :cause    [:event :dashboard/opened]}]]]}))
```

This still gets canonical identity, stale/fresh policy, dedupe, invalidation,
GC, passive subscriptions, and Xray visibility. What it does not get
automatically is route ownership, route leave release, route transition
blocking, or SSR route preload. Those can be supplied explicitly with owners
and server entry events if the app is not route-driven.

If an event only wants to refresh data and does not intend to keep it active, it
should omit `:owner` and supply only `:cause`. Event-created owners must have a
matching release path.

### Paginated and Previous Data

Paginated tables, filtered lists, search results, and cursor feeds are ordinary
resources in v1. They should not wait for the later "infinite resources" slice.

The v1 pattern is:

- include every filter, sort, page, cursor, and server-visible option in params;
- tag both the list identity and any returned item identities;
- keep old data visible while a new page/filter resource is first-loading when
  the route/resource declaration opts into `:keep-previous?`;
- mark previous data as previous/placeholder in the public resource state, not
  as cached data for the new key;
- let Xray show the previous key and the new key so near-duplicate params,
  nil-vs-missing mistakes, and accidental request duplication are obvious.

The public `:rf.resource/state` projection for a `:keep-previous?` load should
make the distinction explicit:

```clojure
{:status :loading
 :data nil
 :previous? true
 :previous-key [scope :articles/list {:page 1 :filter "recent"}]
 :previous-data [{:id 1 :title "Old page"}]
 :placeholder? false}
```

`previous-data` is a projection from the prior key; it is not inserted into the
new cache entry and must not provide tags for the new key. The new entry becomes
ordinary `:loaded` data only after its own request succeeds.

Cache growth for table/list params is controlled by the same owner and GC rules
as other resources. `:keep-previous?` must not pin old pages beyond their
owners; it only allows the current view to project the previous entry while the
new key is loading.

This keeps the common "page 2 of an admin table" case small while still
reserving infinite scrolling, max-page retention, and cursor-window policies for
a later dedicated slice.

### SSR and Hydration

SSR must use request-local frames. A process-global resource cache would leak
data between users.

Server route handling should:

1. resolve the route;
2. compute route resources;
3. enqueue blocking resource ensures;
4. drain until blocking resources for the current nav-token settle;
5. render with the settled resource state;
6. serialize only the allowed resource runtime projection;
7. record projection metadata: serialized, redacted, omitted, fresh, stale, and
   refetch-on-client decisions.

Blocking SSR resources need a timeout policy. A timeout should settle the
resource as a structured first-load failure for that SSR frame, record the route
blocking failure, and let the renderer choose between error markup, a skeleton,
or an application-specific fallback. It must not hang the request indefinitely.

Client hydration should:

1. install frame-state;
2. preserve hydrated resource entries;
3. avoid duplicate immediate fetches for fresh entries;
4. background-refetch stale entries according to policy;
5. maintain frame and nav-token isolation.

Do not serialize all of `:rf.db/runtime` by default. Resource hydration needs an
explicit projection hook that can redact or omit sensitive and large data.
Hydration should never cross scopes: request-local SSR frames and serialized
resource scopes must agree before a client treats hydrated data as usable.

Hydration rules:

- `loaded-at`, `stale-at`, and `invalidated-at` are absolute timestamps; server
  clock skew should be surfaced in trace/hydration diagnostics when it makes
  freshness ambiguous;
- omitted or redacted entries hydrate as metadata only and refetch on the client
  if the route still needs them;
- stale hydrated entries may render their data immediately, then refetch by
  resource event according to policy;
- `refresh-error` should serialize only when the error envelope is allowed by
  the same privacy/size projection as data.

### Transport

V1 should be HTTP-first:

```clojure
:transport :rf.http/managed
```

The resource runtime lowers an ensure/refetch into managed HTTP:

```clojure
[:rf.http/managed
 {:request-id request-id
  :request    request-map
  :on-success [:rf.resource.internal/succeeded
                {:resource-key resource-key
                 :scope        scope
                 :generation   generation}]
  :on-failure [:rf.resource.internal/failed
                {:resource-key resource-key
                 :scope        scope
                 :generation   generation}]}]
```

Success and failure events must verify generation before writing. Cancellation
is an optimization; stale suppression is the correctness boundary.

Generic transport is desirable, and `re-frame-query` demonstrates that demand,
but it should be a later extension protocol after the managed HTTP semantics
are solid.

### Race and In-Flight Semantics

These cases should be specified before implementation:

- `ensure` while the same scoped resource key is already in flight joins the
  existing request, attaches any supplied owner, records the new cause, and emits
  a dedupe trace;
- `refetch` may force a new generation. If a prior request is still in flight,
  abort it when possible; otherwise suppress its late reply by generation;
- invalidation while a request is in flight marks the entry stale and records
  the invalidation. If the in-flight request is for the current generation, its
  success may satisfy the invalidation only when policy says the request covered
  the invalidated identity; otherwise schedule a follow-up refetch;
- owner release while a request is in flight aborts only when no remaining owner
  needs that request. Shared requests must not be cancelled just because one
  route, machine, or lease went away;
- route supersession uses both nav-token owner release and generation checks.
  The old nav-token may not write into the new route's resource state;
- stale/GC timers are advisory. A timer handler must re-read the current entry,
  scope, owners, and generation before writing because a newer event may already
  have refreshed, invalidated, removed, or re-owned the entry.

### Invalidation

V1 should support exact tag invalidation:

```clojure
[:rf.resource/invalidate-tags
 {:scope [:rf.scope/session {:user-id "u-42" :tenant-id "acme"}]
  :tags  #{[:article "welcome"] [:article-list]}
  :cause [:mutation :article/save mutation-id]}]
```

Algorithm:

1. find entries whose provided tags intersect invalidated tags;
2. mark entries stale;
3. refetch entries with active owners;
4. leave inactive entries stale or eligible for GC;
5. emit trace records explaining matched keys and decisions.

On successful resource load, the tag index for that scoped resource key is
replaced with the tags produced by the new data. Old tags must be removed so
that stale list/detail relationships do not keep receiving invalidations after
the data has changed.

Invalidation can be batched. A single event may carry many tags, but it should
emit one decision summary plus per-entry details so Xray can show broad-tag
storms without flooding the trace. Broad invalidations are allowed, but they
should be visible and lintable.

Scoped invalidation is the default. If an invalidation has no matches, Xray
should distinguish "no match in this scope" from "no resource provides this tag
in any scope." That distinction catches tenant/user scope mistakes.

Invalidation traces should distinguish:

- no matching resource;
- matching inactive resource marked stale;
- matching active resource refetched;
- matching resource already stale;
- matching resource skipped by policy;
- broad tag matched many entries.

Later, Xray can add lint:

- mutation invalidates no tags;
- mutation invalidates tags no resource provides;
- resource provides overly broad tags;
- route depends on a resource but never owns it;
- active resource is stale but has no refetch policy.

Do not pretend invalidation can always be derived. The server is the source of
truth and the client often lacks enough semantic information.

### Focus And Reconnect Revalidation

Focus and reconnect revalidation should be part of the first public-beta gate.

TanStack Query's most visible magic is that stale active data refreshes when
the user returns to the tab or the network reconnects. re-frame2 should provide
the same user-facing behavior, but through events rather than subscription
lifecycle.

The implementation should reuse v1 primitives:

- active owners decide which entries are worth refetching;
- stale/fresh timestamps decide whether refetch is needed;
- generation checks suppress stale replies;
- managed HTTP owns transport retry and abort;
- Xray and traces show why the refresh happened.

Likely public/internal events:

```clojure
:rf.resource/window-focused
:rf.resource/network-reconnected
```

Algorithm:

1. receive focus or reconnect signal;
2. scan active resource entries;
3. refetch entries that are stale or policy-marked for revalidation;
4. leave fresh entries alone;
5. emit trace records explaining the decision.

This is deliberately not subscription-driven fetching. The browser event causes
resource events; views remain passive reads.

### Mutations

Mutations should be the second slice, not the read-resource MVP. They should
follow quickly, alongside focus/reconnect revalidation, because a read cache
without a write/invalidation story feels incomplete next to TanStack Query,
RTK Query, SWR, and `re-frame-query`.

Until the mutation slice lands, apps can still dispatch
`:rf.resource/invalidate-tags` manually after their own write events. That is
coherent, but it is not the full value proposition.

The minimal mutation slice should include:

- `reg-mutation` and `:rf.mutation/execute`;
- mutation pending/error/result state;
- a generated or caller-supplied mutation instance id;
- scoped execution, using the same cache-scope rules as resources;
- concurrency semantics for multiple submissions of the same mutation id;
- tag invalidation from success and, when useful, failure;
- explicit invalidation timing: before request, after success, after failure, or
  after settle;
- controlled resource patch/populate APIs for mutation responses;
- abort and retry policy inherited from managed HTTP, with write retries opt-in;
- failure-state lifetime and a causal clear/reset event;
- trace-visible mutation instance ids;
- instrumentation hooks for later optimistic snapshots and rollback.

Mutation runtime state should be keyed by mutation instance id, not only by
mutation id, so two concurrent `:comment/add` submissions do not overwrite each
other's pending/error/result state. Xray should group those instances under the
registered mutation id while still showing each request, invalidation, patch,
and result separately.

Example shape:

```clojure
(rf/reg-mutation
  :article/save
  {:params-schema :app/article

   :request
   (fn [{:keys [slug] :as article} _ctx]
     {:method :put
      :url    (str "/api/articles/" slug)
      :body   article
      :decode :app/article})

   :transport
   :rf.http/managed

   :invalidates
   (fn [{:keys [slug]} _result]
     #{[:article slug]
       [:article-list]})})
```

Dispatch:

```clojure
[:rf.mutation/execute
 {:mutation :article/save
  :params   article}]
```

Optimistic updates are deferred beyond the first public-beta gate, but the
mutation trace shape should reserve room for them now: affected resource keys,
patch summaries, snapshot ids, rollback result, and reconciliation refetches.
When optimistic updates land, they should initially use snapshot rollback of
affected resource entries. Epoch-diff rollback can be researched later if the
epoch subsystem becomes production-safe for this purpose.

### Machines

Resources and machines should compose, not compete.

Use resources for shared cached reads:

```clojure
[:rf.resource/ensure
 {:resource :checkout/quote
  :params   {:cart-id cart-id}
  :owner    [:machine :checkout/flow machine-instance-id]}]
```

Use machines for semantic workflows:

- a checkout quote with auth renewal and alternate payment paths;
- a websocket subscription with reconnect states;
- a multi-step upload;
- a wizard that loads resources, validates state, and performs writes.

Do not spawn a full machine per ordinary resource entry. That would make common
read caching heavier without improving correctness.

### Xray and AI Tooling

Xray should expose:

- a static resource registry: resource id, source coordinates, params/data
  schemas, request summary, stale/GC policy, tag producer, scope resolver,
  sensitivity/large classification, and declaring routes;
- a live resource instance table per frame: resource key, scope, status,
  loaded-at, stale-at, gc-at, generation, request id, attempt, owners, tags,
  errors, data summary, and GC eligibility;
- a route/resource graph: current route/nav-token, blocking vs non-blocking
  resources, SSR wait points, hydrated/fresh/stale state;
- a lifecycle timeline: ensure, owner attach, cache hit, dedupe, request
  issued, success/failure, refresh failure, invalidation, refetch decision,
  owner release, GC schedule/fire/skip, stale suppression, hydration;
- an invalidation/mutation graph: invalidated tags, matched entries, active
  refetches, inactive stale marks, no-match invalidations, broad-tag warnings,
  mutation source coordinates;
- a cache growth view: counts by resource/status/scope/owner/tag, inactive
  entries, entries past GC time, largest elided data summaries, orphaned owners,
  and retained side-table handles.

Tool APIs should prefer summaries and metadata over raw values. AIs usually
need to know "this route owns `:article/by-slug`, it is stale, and the latest
background refresh failed with a 503", not the full article body or user
profile payload.

This needs a trace/accessor contract, not only panel UI. Add a `:rf.resource/*`
trace family with operations such as:

```clojure
:rf.resource/registered
:rf.resource/ensure
:rf.resource/owner-attached
:rf.resource/cache-hit
:rf.resource/deduped
:rf.resource/fetch-started
:rf.resource/succeeded
:rf.resource/failed
:rf.resource/refresh-failed
:rf.resource/invalidated
:rf.resource/refetch-decision
:rf.resource/owner-released
:rf.resource/gc-scheduled
:rf.resource/gc-fired
:rf.resource/gc-skipped
:rf.resource/removed
:rf.resource/stale-suppressed
:rf.resource/hydrated
:rf.resource/hydrate-refetch
```

Every resource trace should carry, where applicable, frame, scope, resource key,
resource id, params summary, generation, request id, owner, cause, status
before/after, resource tags, invalidated tags, freshness timestamps, and
redaction/size markers.

Trace and history retention are part of the tool contract. Resource history must
be bounded, and params/scopes need the same privacy and size elision treatment
as data because scopes can contain user ids, tenant ids, locale, or
impersonation markers. Xray should display elided summaries for sensitive or
large params/scopes and keep enough retained history to explain recent races,
invalidations, and GC decisions without becoming its own unbounded cache.

Candidate tool accessors:

```clojure
(list-resources opts)
(list-resource-instances opts)
(get-resource-state opts)
(get-resource-history opts)
(list-resource-invalidations opts)
```

They should filter by frame, scope, resource id, tag, owner, status, stale?,
request id, and nav-token. Raw data access continues to go through existing
egress and elision rules.

## Examples

### Route-Driven Page Load

```clojure
(rf/reg-resource
  :article/by-slug
  {:params-schema [:map [:slug :string]]
   :data-schema   :app/article
   :request
   (fn [{:keys [slug]} _]
     {:method :get
      :url    (str "/api/articles/" slug)
      :decode :app/article})
   :stale-after-ms 60000
   :gc-after-ms    (* 5 60 1000)
   :tags
   (fn [{:keys [slug]} _]
     #{[:article slug]})})

(rf/reg-route
  :route/article
  {:path "/articles/:slug"
   :params [:map [:slug :string]]
   :resources
   [{:resource  :article/by-slug
     :params    (fn [route]
                  {:slug (get-in route [:params :slug])})
      :scope     (fn [_route ctx]
                   (:current-session-scope ctx))
     :blocking? true}]})

(rf/reg-view article-page []
  (let [slug  @(rf/subscribe [:rf.route/param :slug])
        scope @(rf/subscribe [:session/resource-scope])
        state @(rf/subscribe [:rf.resource/state
                              {:resource :article/by-slug
                               :scope    scope
                               :params   {:slug slug}}])]
    (cond
      (:loading? state)
      [article-skeleton]

      (and (:error state) (not (:has-data? state)))
      [article-error (:error state)]

      :else
      [:<>
       [:article-view {:article (:data state)}]
       (when (:fetching? state)
         [refresh-indicator])
       (when (:refresh-error state)
         [refresh-error (:refresh-error state)])])))
```

The view is passive. The route caused the resource ensure. The runtime owns
the resource state.

### Event-Driven Ensure

```clojure
(rf/reg-event-fx
  :dashboard/opened
  (fn [_ [_ user-id]]
    {:fx [[:dispatch
           [:rf.resource/ensure
            {:resource :dashboard/summary
             :params   {:user-id user-id}
             :owner    [:lease :dashboard/opened user-id]
             :cause    [:event :dashboard/opened]}]]]}))
```

### Manual Refresh

```clojure
(rf/reg-event-fx
  :article/refresh-clicked
  (fn [_ [_ slug]]
    {:fx [[:dispatch
           [:rf.resource/refetch
            {:resource :article/by-slug
             :params   {:slug slug}
             :cause    [:event :article/refresh-clicked]}]]]}))
```

### Mutation With Invalidation

```clojure
(rf/reg-mutation
  :comment/add
  {:params-schema
   [:map
    [:slug :string]
    [:body :string]]

   :request
   (fn [{:keys [slug body]} _]
     {:method :post
      :url    (str "/api/articles/" slug "/comments")
      :body   {:body body}
      :decode :app/comment})

   :invalidates
   (fn [{:keys [slug]} _]
     #{[:comments slug]})})

(rf/reg-event-fx
  :comment-form/submitted
  (fn [_ [_ params]]
    {:fx [[:dispatch
           [:rf.mutation/execute
            {:mutation :comment/add
             :params   params}]]]}))
```

### Machine-Owned Resource

```clojure
{:states
 {:idle
  {:on
   {:quote.requested
    {:target :loading
     :actions
     [{:fx [[:dispatch
              [:rf.resource/ensure
               {:resource :checkout/quote
                :params   {:cart-id [:data :cart-id]}
                :owner    [:machine :checkout/flow [:data :instance-id]]
                :cause    [:machine-action :checkout/quote.requested]}]]]}]}}}

  :loading
  {:on
   {:quote.loaded {:target :ready}
    :quote.failed {:target :failed}}}}}
```

The machine remains the semantic workflow. The resource runtime handles cached
read mechanics.

## Implementation Plan

### 1. Artifact and Namespaces

Add an optional artifact:

```text
implementation/resources/
```

Likely namespaces:

```text
re_frame.resources
re_frame.resources.registry
re_frame.resources.state
re_frame.resources.events
re_frame.resources.transport
re_frame.resources.subs
re_frame.resources.route
re_frame.resources.ssr
re_frame.resources.test-support
```

Facade integration:

- add `implementation/core/src/re_frame/core_resources.cljc`;
- expose `reg-resource`, `clear-resource`, `resource-meta`,
  `resource-state`, and `resources`;
- add feature probe `:resources/reg-resource`;
- add optional `reg-mutation` only when the mutation slice lands.

### 2. Registrar Kinds

Add registrar kind:

```clojure
:resource
```

Add later:

```clojure
:mutation
```

Do not add `:query` as a public kind. It creates unnecessary vocabulary
collision with route query params and with prior-art implementation names.

### 3. Runtime State

Use target path:

```clojure
[:rf.db/runtime :rf.runtime/resources]
```

Use interim path until the frame-state partition exists:

```clojure
[:rf/runtime :resources]
```

Store serializable state in frame-state:

- entries;
- status;
- data;
- errors after elision policy;
- timestamps;
- generations;
- owners;
- scopes;
- causes/history summaries;
- tags;
- indexes.

Store host handles in side tables keyed by frame, resource key, and generation:

- AbortControllers;
- timeout handles;
- polling timers;
- transport-specific subscriptions;
- promise handles.

Frame destroy must clean side tables.

### 4. Core Events

Implement public events:

```clojure
:rf.resource/ensure
:rf.resource/refetch
:rf.resource/invalidate-tags
:rf.resource/release-owner
:rf.resource/clear-scope
:rf.resource/remove
```

Implement internal events:

```clojure
:rf.resource.internal/succeeded
:rf.resource.internal/failed
:rf.resource.internal/aborted
:rf.resource.internal/gc-fired
:rf.resource.internal/stale-suppressed
```

`ensure` algorithm:

1. resolve resource metadata;
2. validate and canonicalize params;
3. resolve and validate cache scope;
4. compute `[cache-scope resource-id canonical-params]`;
5. attach owner if supplied;
6. record cause if supplied;
7. if entry is fresh, no-op after owner update and emit cache-hit trace;
8. if request is in flight, join/dedupe after owner update and emit dedupe
   trace;
9. transition to `:loading` or `:fetching`;
10. issue managed HTTP effect;
11. record generation, request id, and trace data.

### 5. Managed HTTP Integration

For `:transport :rf.http/managed`, lower a resource request into managed HTTP.

The reply event must carry enough data to verify generation and frame:

```clojure
[:rf.resource.internal/succeeded
 {:resource-key resource-key
  :scope        scope
  :generation   generation
  :frame        frame-id}]
```

If generation does not match, suppress the reply and emit trace metadata. A
stale reply must never overwrite newer data.

### 6. Subscriptions

Register passive subscriptions:

```clojure
:rf.resource/state
:rf.resource/data
:rf.resource/status
:rf.resource/error
:rf.resource/refresh-error
:rf.resource/loading?
:rf.resource/fetching?
:rf.resource/stale?
:rf.resource/has-data?
:rf.resource/previous-data
```

No v1 subscription should fetch. If a future `:rf.resource/live` is added, it
must be explicitly documented as side-effecting convenience and kept separate
from the recommended route/event pattern.

### 7. Route and SSR Integration

Routing changes:

1. reserve `:resources` in route metadata;
2. compute route resource plans after match;
3. evaluate `:when` and dependency ordering;
4. resolve scopes and params;
5. attach owners with `[:route route-id nav-token]`;
6. dispatch ensures with route-entry causes;
7. track blocking resources by nav-token;
8. release owners on route leave or superseded nav-token;
9. keep existing `:on-match` behavior.

SSR changes:

1. add a drain/wait point for blocking route resources;
2. project resource runtime state into the hydration payload;
3. redact or omit sensitive values;
4. prevent client double-fetch for fresh hydrated entries.

### 8. Xray and Tool Surfaces

Tools should receive summaries:

```clojure
{:resource-key [[:rf.scope/session {:user-id "u-42" :tenant-id "acme"}]
                :article/by-slug
                {:slug "welcome"}]
 :scope        [:rf.scope/session {:user-id "u-42" :tenant-id "acme"}]
 :status       :loaded
 :has-data?    true
 :stale?       true
 :owners       #{[:route :route/article nav-token]}
 :causes       [[:route-entry :route/article nav-token]]
 :tags         #{[:article "welcome"]}
 :refresh-error {:category :http/status
                 :status   503}
 :data-summary {:schema :app/article
                :redacted? true
                :size 14231}}
```

Raw data access should go through existing egress and elision rules.

### 9. Tests

Initial conformance fixtures should cover:

- registration and metadata;
- params schema validation;
- params canonicalization;
- cache scope validation and clearing;
- scope switch/clear while requests are in flight;
- first load `:loading`;
- background refresh `:fetching`;
- background refresh failure records `:refresh-error` and preserves data;
- success and failure transitions;
- stale/fresh behavior;
- derived status flags do not drift from durable facts;
- structural sharing preserves unchanged data values;
- inactive GC;
- duplicate ensure dedupe;
- dedupe traces joined owners and request id;
- forced refetch supersedes or suppresses older in-flight generations;
- stale reply suppression;
- active owner release;
- cause does not create liveness;
- Xray/tool inspection does not create an owner;
- exact tag invalidation;
- invalidation trace records matched keys and decisions;
- invalidation while a matching request is in flight;
- tag index replacement after successful reload;
- active invalidated resource refetch;
- inactive invalidated resource only marked stale;
- route entry ensure;
- route `:when` skips without sentinel params;
- dependent route resource ordering;
- route `:keep-previous?` reports previous data without polluting new-key cache;
- route leave owner release;
- route supersession via nav-token;
- blocking route resource failure and timeout behavior;
- SSR preload;
- hydration no-double-fetch;
- hydration omitted/redacted-data refetch behavior;
- hydration scope isolation;
- frame isolation;
- mutation patch/populate then invalidation;
- cache growth and GC limits for list resources;
- trace redaction and pruning for params/scopes;
- redacted tool summaries.

## Options Considered

### A. Pattern Only

Keep Pattern-RemoteData plus managed HTTP and write a stronger cookbook.

This is low risk, but it does not solve cache identity, invalidation, active
ownership, GC, route graphs, SSR hydration, or Xray visibility. It is not enough
for a best-in-class SPA library.

### B. Adopt or Recommend shipclojure/re-frame-query

This is useful prior art and could be recommended for current re-frame apps.
It should not be the re-frame2 answer because it cannot own re-frame2 frames,
runtime partitions, route metadata, SSR, Xray, privacy, and event-causal traces
without becoming a re-frame2 artifact in practice.

### C. Optional First-Class Resource Artifact

This proposal.

It adds real API surface, but it removes repeated app-level machinery and gives
tools a stable contract. Pre-alpha posture favors building the right primitive
when the problem is real and recurring.

### D. Normalized Cache First

Start with Apollo/Relay-style entity normalization.

Too heavy for v1. Keep it as a later scale-gated enhancement.

### E. Projection First

Before implementing the primitive, derive a route/resource/work graph from
existing route metadata, managed HTTP metadata, traces, and RemoteData
conventions.

This is useful as an Xray bead and can reveal app patterns, but it does not
replace the runtime primitive.

## Recommendation

Build Option C in slices, starting with a read-resource MVP.

The v1 artifact should include:

- `reg-resource`;
- passive resource state subscriptions;
- map-payload ensure/refetch/invalidate/remove events;
- active owners;
- non-liveness causes;
- explicit cache scopes and scope clearing;
- route `:resources`;
- managed HTTP transport;
- canonical params;
- exact tag invalidation;
- stale/fresh policy;
- inactive GC;
- stale/GC timer policy;
- dedupe;
- stale reply suppression;
- `:refresh-error` for background-refresh failures;
- conditional route resources;
- previous-data support for ordinary paginated/filter resources;
- structural sharing for equal decoded data;
- SSR preload/hydration;
- Xray/tool metadata and resource trace operations.

First public-beta gate:

- `reg-mutation`;
- focus/reconnect revalidation;
- mutation invalidation integration.

Defer beyond those first slices:

- optimistic rollback;
- generic transports;
- polling/interval revalidation;
- infinite resources;
- normalized caches;
- automatic graph-derived invalidation;
- subscription-driven fetching;
- offline persistence;
- cross-tab broadcast.

## Why This Can Be Better Than TanStack Query

It should not be sold as "TanStack Query but more mature." It will not be more
mature on day one.

The honest claim is narrower:

> TanStack Query is the gold standard for general server-state cache behavior.
> re-frame2 can match the core semantics that apply, and be better for
> re-frame2 apps because resources live inside the same event, frame, route,
> SSR, schema, trace, Xray, and privacy model as the rest of the application.

Structural advantages inside re-frame2:

- frame-local caches for app frames, story frames, test frames, and SSR request
  frames;
- route-declared data dependencies;
- event-causal traces;
- explicit owner/cause separation, so liveness and causality do not blur;
- cache scopes for auth, tenant, locale, impersonation, and SSR correctness;
- runtime-owned state that ordinary app-db writes cannot clobber;
- managed HTTP as the default transport;
- schema-aware params and decoded data;
- derived projections through ordinary subscriptions instead of a query-local
  `:select` hook;
- time-travel and SSR through frame-state;
- Xray visibility over decisions, not just final state;
- AI-readable metadata and redacted values;
- FSM escalation for lifecycles that deserve it.

Where TanStack and alternatives remain ahead:

- maturity;
- ecosystem defaults;
- infinite query breadth;
- offline and persistence plugins;
- cross-tab broadcast;
- normalized GraphQL cache ecosystems;
- years of production edge cases.

The goal is not imitation. The goal is to make server state a first-class
re-frame2 runtime process.

## Open Decisions

1. Should the file and guide title say "resources", "resource queries", or
   "server state"?
   Recommendation: API says resources; docs can use "resource queries" when
   comparing to prior art.
2. Should `reg-mutation` land with v1 or as the next slice?
   Recommendation: read-resource MVP first, but do not call the artifact
   complete until minimal mutation invalidation lands in the first public-beta
   gate.
3. What exact route `blocking?` behavior should client navigation expose?
   Recommendation: route transition pending and SSR wait; do not block URL
   commit.
4. How should resource hydration project data from `:rf.db/runtime`?
   Recommendation: explicit resource projection hook using schema and
   sensitivity metadata; never serialize all runtime state by default.
5. Should `:cache-key` exist?
   Recommendation: no in v1. Canonical params are the identity.
6. Should subscription-driven fetching exist?
   Recommendation: not in v1. Reconsider later as explicit convenience.
7. What exact shape should `:refresh-error` carry?
   Recommendation: use the same error envelope shape as `:error`, plus
   timestamp/attempt metadata if useful. `:status :error` is reserved for
   first-load failure with no usable data.
8. Should generic transports be part of v1?
   Recommendation: no. Managed HTTP first.
9. What is the cache scope shape?
   Recommendation: make scope explicit EDN and part of the resource key, with
   `[:rf.scope/global]` as the default and `clear-scope` for logout/account
   changes.
10. Should owners also represent causes?
    Recommendation: no. Owners are liveness leases; causes are trace metadata.
11. Should Xray ever become an owner?
    Recommendation: no for inspection. A future explicit debug pin may be a
    tool mutation with trace evidence.
12. How much previous-data support belongs in v1?
    Recommendation: support `:keep-previous?` for ordinary route/list churn;
    keep arbitrary placeholder data deferred.

## Bead Structure

1. EP/spec bead: turn this proposal into a normative spec.
2. Artifact skeleton bead: create `day8/re-frame2-resources`, facade wrappers,
   feature probes, and `:resource` registrar metadata.
3. Resource runtime bead: entries, cache scopes, canonical params, status
   transitions, structural sharing, passive subscriptions, and frame-local
   state.
4. Managed HTTP bead: ensure/refetch/success/failure over `:rf.http/managed`,
   dedupe, generation checks, and stale reply suppression.
5. Invalidation/GC bead: tags, active owners, owner indexes, stale marking,
   active refetch, causes, stale/GC timer policy, scope clearing, and inactive
   GC.
6. Route integration bead: `:resources`, nav-token owners, blocking resources,
   `:when`, dependent route resources, `:keep-previous?`, release on leave, and
   preserved `:on-match` behavior.
7. SSR/hydration bead: blocking resource drain, resource projection, redaction,
   scope isolation, projection metadata, and hydration no-double-fetch.
8. Xray/tool/privacy bead: resource registry panel, route/resource graph,
   lifecycle timeline, invalidation graph, cache growth view, summaries, trace
   operations, egress policy, and redacted accessors.
9. Focus/reconnect bead: active-stale scan on browser focus and network
   reconnect, expressed as resource events with trace records.
10. Mutation bead: `reg-mutation`, mutation instance state, execution,
    patch/populate APIs, invalidation, and trace hooks for later optimistic
    rollback.
11. Docs/examples bead: guide chapter, API docs, migration notes from
    `shipclojure/re-frame-query`, route-driven example, SSR example, and
    machine-owned resource example.

## Sources Consulted

- [TanStack Query: Important Defaults](https://tanstack.com/query/v5/docs/framework/react/guides/important-defaults)
- [TanStack Query: Query Keys](https://tanstack.com/query/v5/docs/framework/react/guides/query-keys)
- [TanStack Query: Query Invalidation](https://tanstack.com/query/v5/docs/framework/react/guides/query-invalidation)
- [TanStack Query: Optimistic Updates](https://tanstack.com/query/v5/docs/framework/react/guides/optimistic-updates)
- [TanStack Query: Prefetching and Router Integration](https://tanstack.com/query/v5/docs/framework/react/guides/prefetching)
- [TanStack Query: Server Rendering and Hydration](https://tanstack.com/query/v5/docs/framework/react/guides/ssr)
- [RTK Query: Cache Behavior](https://redux-toolkit.js.org/rtk-query/usage/cache-behavior)
- [RTK Query: Automated Re-fetching](https://redux-toolkit.js.org/rtk-query/usage/automated-refetching)
- [RTK Query: Manual Cache Updates](https://redux-toolkit.js.org/rtk-query/usage/manual-cache-updates)
- [SWR: Data Fetching](https://swr.vercel.app/docs/data-fetching)
- [SWR: Automatic Revalidation](https://swr.vercel.app/docs/revalidation)
- [SWR: Mutation and Revalidation](https://swr.vercel.app/docs/mutation)
- [Apollo Client: Caching](https://www.apollographql.com/docs/react/caching/overview)
- [Relay: Staleness of Data](https://relay.dev/docs/guided-tour/reusing-cached-data/staleness-of-data/)
- [shipclojure/re-frame-query](https://github.com/shipclojure/re-frame-query)
