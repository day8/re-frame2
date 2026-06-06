# EP: Resource Management - Resources

Status: proposal

Date: 2026-06-06

Related:

- [Guide 10 - HTTP](../../guide/10-http.md)
- [Guide 19 - Routing](../../guide/19-routing.md)
- [Guide 21 - Runtime model](../../guide/21-dynamic-model.md)
- [Pattern - Remote Data](../../spec/Pattern-RemoteData.md)
- [Spec 014 - HTTP Requests](../../spec/014-HTTPRequests.md)
- [Spec 012 - Routing](../../spec/012-Routing.md)
- [Spec 005 - State Machines](../../spec/005-StateMachines.md)

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
stale/fresh policy, dedupe, GC, route integration, SSR preload/hydration, and
Xray/tool visibility. Mutations, optimistic updates, polling, infinite resources,
generic transports, and normalized caches are important follow-on slices, but
should not make the first artifact too wide.

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
duplicate requests, route waterfalls, and optimistic UI races.

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
  :params   {:slug "welcome"}
  :owner    [:route :route/article nav-token]}]
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
   {[:article/by-slug {:slug "welcome"}]
    {:resource/id   :article/by-slug
     :params        {:slug "welcome"}
     :status        :loaded
     :data          {:title "Welcome"}
     :error         nil
     :loaded-at     1780752000000
     :stale-at      1780752060000
     :generation    3
     :request-id    [:rf.resource :article/by-slug {:slug "welcome"} 3]
     :tags          #{[:article "welcome"]}
     :active-owners #{[:route :route/article nav-token]}}}

   :tag-index
   {[:article "welcome"] #{[:article/by-slug {:slug "welcome"}]}}

   :owner-index
   {[:route :route/article nav-token]
    #{[:article/by-slug {:slug "welcome"}]}}}}}
```

Until the frame-state partition lands, the interim implementation can use:

```clojure
[:rf/runtime :resources]
```

The specification should still be written toward `:rf.db/runtime`, so ordinary
`:db` event handlers cannot accidentally wipe resource state.

### Resource Identity Is Data

A resource instance is identified by:

```clojure
[resource-id canonical-params]
```

For example:

```clojure
[:article/by-slug {:slug "welcome"}]
```

Rules:

- params must conform to `:params-schema`;
- params must be serializable EDN data;
- maps are canonicalized so key order does not affect identity;
- host values such as functions, promises, dates, DOM nodes, AbortControllers,
  and JS objects are rejected;
- nil vs missing must be schema-defined, not accidental;
- every variable that affects remote identity must be represented in params;
- avoid a separate `:cache-key` escape hatch in v1 unless it is validated,
  visible in tools, and tested heavily.

### Active Owners, Not Component Observers

TanStack Query and RTK Query talk about active observers or subscriptions.
re-frame2 should talk about active owners.

Owners answer:

- should invalidation refetch now, or only mark stale?
- should polling continue?
- may the entry be garbage-collected?
- who caused this work?
- what should route leave release?
- what should Xray show in the route/resource graph?

Examples:

```clojure
[:route :route/article nav-token]
[:machine :checkout/flow machine-instance-id]
[:event :dashboard/opened event-trace-id]
[:tool :xray frame-id]
[:ssr request-id nav-token]
```

Route owners must include the navigation token. `[:route :route/article]` is
not precise enough because the same route can be entered multiple times with
different params, pending work, or SSR request frames.

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
  invalidate inactive -> :stale

:fetching
  success -> :loaded
  failure -> :error, preserving last-known-good data
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
- Xray/tool summaries with redaction;
- conformance tests.

V1 should defer:

- `reg-mutation`;
- optimistic rollback;
- generic transport extension;
- polling, focus, and reconnect revalidation;
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
  :params   {:slug "welcome"}
  :owner    [:route :route/article nav-token]}]

[:rf.resource/refetch
 {:resource :article/by-slug
  :params   {:slug "welcome"}
  :owner    [:event :article/refresh trace-id]}]

[:rf.resource/invalidate-tags
 {:tags #{[:article "welcome"]}}]

[:rf.resource/release-owner
 {:owner [:route :route/article nav-token]}]

[:rf.resource/remove
 {:resource :article/by-slug
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
  :params   {:slug "welcome"}}]

[:rf.resource/data
 {:resource :article/by-slug
  :params   {:slug "welcome"}}]

[:rf.resource/status
 {:resource :article/by-slug
  :params   {:slug "welcome"}}]

[:rf.resource/loading?
 {:resource :article/by-slug
  :params   {:slug "welcome"}}]

[:rf.resource/fetching?
 {:resource :article/by-slug
  :params   {:slug "welcome"}}]

[:rf.resource/stale?
 {:resource :article/by-slug
  :params   {:slug "welcome"}}]

[:rf.resource/error
 {:resource :article/by-slug
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
- `:tags`;
- `:sensitive?` / `:large?` / schema-based classification.

Deferred keys:

- `:poll-ms`;
- `:revalidate`;
- `:placeholder`;
- `:select`;
- `:transport` extension protocols;
- `:cache-key`;
- `:infinite`;
- mutation-only keys such as `:invalidates`, `:optimistic`, and `:rollback`.

### Status Semantics

Resource state should use Pattern-RemoteData semantics with explicit derived
flags:

```clojure
{:status     :idle | :loading | :fetching | :loaded | :error | :stale
 :data       <last-known-good-or-nil>
 :error      <last-error-or-nil>
 :loaded-at  <ms-or-nil>
 :stale-at   <ms-or-nil>
 :attempt    <int>
 :generation <int>
 :tags       <set>
 :owners     <set>
 :loading?   <boolean>
 :fetching?  <boolean>
 :has-data?  <boolean>
 :stale?     <boolean>}
```

The important invariant:

- `:loading` means first load with no usable data;
- `:fetching` means work is in flight while prior data stays visible;
- `:error` means the last resource operation failed;
- `:error` may still carry last-known-good `:data`;
- views must use `:has-data?`, `:loading?`, `:fetching?`, and `:error` rather
  than assuming `:status :error` means `:data` is nil.

This makes background refresh failures visible without forcing a blank screen.
If that proves too subtle in practice, a later decision can split refresh
failures into an explicit `:refresh-error`, but v1 should be consistent with
Pattern-RemoteData.

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
     :blocking? true}

    {:resource  :comments/list
     :params    (fn [route]
                  {:slug (get-in route [:params :slug])})
     :blocking? false}]})
```

On route entry:

1. routing resolves the route and nav-token;
2. route resource params are computed and validated;
3. each resource is marked active with owner `[:route route-id nav-token]`;
4. each resource is ensured;
5. blocking resources are tracked under the nav-token;
6. non-blocking resources fetch in the background;
7. failures in blocking resources update route transition/error state;
8. Xray can display the route/resource graph without parsing handlers.

On route leave or superseded navigation:

1. route-owned resources are released by owner token;
2. polling for owners that went away stops in later slices;
3. in-flight work is aborted when possible;
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
             :owner    [:event :dashboard/opened]}]]]}))
```

This still gets canonical identity, stale/fresh policy, dedupe, invalidation,
GC, passive subscriptions, and Xray visibility. What it does not get
automatically is route ownership, route leave release, route transition
blocking, or SSR route preload. Those can be supplied explicitly with owners
and server entry events if the app is not route-driven.

### SSR and Hydration

SSR must use request-local frames. A process-global resource cache would leak
data between users.

Server route handling should:

1. resolve the route;
2. compute route resources;
3. enqueue blocking resource ensures;
4. drain until blocking resources for the current nav-token settle;
5. render with the settled resource state;
6. serialize only the allowed resource runtime projection.

Client hydration should:

1. install frame-state;
2. preserve hydrated resource entries;
3. avoid duplicate immediate fetches for fresh entries;
4. background-refetch stale entries according to policy;
5. maintain frame and nav-token isolation.

Do not serialize all of `:rf.db/runtime` by default. Resource hydration needs an
explicit projection hook that can redact or omit sensitive and large data.

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
                :generation   generation}]
  :on-failure [:rf.resource.internal/failed
               {:resource-key resource-key
                :generation   generation}]}]
```

Success and failure events must verify generation before writing. Cancellation
is an optimization; stale suppression is the correctness boundary.

Generic transport is desirable, and `re-frame-query` demonstrates that demand,
but it should be a later extension protocol after the managed HTTP semantics
are solid.

### Invalidation

V1 should support exact tag invalidation:

```clojure
[:rf.resource/invalidate-tags
 {:tags #{[:article "welcome"] [:article-list]}}]
```

Algorithm:

1. find entries whose provided tags intersect invalidated tags;
2. mark entries stale;
3. refetch entries with active owners;
4. leave inactive entries stale or eligible for GC;
5. emit trace records explaining the decision.

Later, Xray can add lint:

- mutation invalidates no tags;
- mutation invalidates tags no resource provides;
- resource provides overly broad tags;
- route depends on a resource but never owns it;
- active resource is stale but has no refetch policy.

Do not pretend invalidation can always be derived. The server is the source of
truth and the client often lacks enough semantic information.

### Mutations

Mutations should be the second slice, not the read-resource MVP.

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

Optimistic updates should initially use snapshot rollback of affected resource
entries. Epoch-diff rollback can be researched later if the epoch subsystem
becomes production-safe for this purpose.

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

- registered resources and later mutations;
- active resource instances;
- route owners and machine owners;
- status and freshness;
- loaded-at/stale-at;
- in-flight request ids;
- provided tags and invalidation history;
- stale-suppressed replies;
- SSR preload/hydration status;
- redaction and sensitivity markers.

Tool APIs should prefer summaries and metadata over raw values. AIs usually
need to know "this route owns `:article/by-slug`, it is stale, and it last
failed with a 503", not the full article body or user profile payload.

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
     :blocking? true}]})

(rf/reg-view article-page []
  (let [slug  @(rf/subscribe [:rf.route/param :slug])
        state @(rf/subscribe [:rf.resource/state
                              {:resource :article/by-slug
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
       (when (:error state)
         [refresh-error (:error state)])])))
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
             :owner    [:event :dashboard/opened]}]]]}))
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
             :owner    [:event :article/refresh-clicked]}]]]}))
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
               :owner    [:machine :checkout/flow [:data :instance-id]]}]]]}]}}}

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
3. compute `[resource-id canonical-params]`;
4. attach owner if supplied;
5. if entry is fresh, no-op after owner update;
6. if request is in flight, no-op after owner update;
7. transition to `:loading` or `:fetching`;
8. issue managed HTTP effect;
9. record generation, request id, and trace data.

### 5. Managed HTTP Integration

For `:transport :rf.http/managed`, lower a resource request into managed HTTP.

The reply event must carry enough data to verify generation and frame:

```clojure
[:rf.resource.internal/succeeded
 {:resource-key resource-key
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
:rf.resource/loading?
:rf.resource/fetching?
:rf.resource/stale?
```

No v1 subscription should fetch. If a future `:rf.resource/live` is added, it
must be explicitly documented as side-effecting convenience and kept separate
from the recommended route/event pattern.

### 7. Route and SSR Integration

Routing changes:

1. reserve `:resources` in route metadata;
2. compute route resource plans after match;
3. attach owners with `[:route route-id nav-token]`;
4. dispatch ensures;
5. track blocking resources by nav-token;
6. release owners on route leave or superseded nav-token;
7. keep existing `:on-match` behavior.

SSR changes:

1. add a drain/wait point for blocking route resources;
2. project resource runtime state into the hydration payload;
3. redact or omit sensitive values;
4. prevent client double-fetch for fresh hydrated entries.

### 8. Xray and Tool Surfaces

Tools should receive summaries:

```clojure
{:resource-key [:article/by-slug {:slug "welcome"}]
 :status       :error
 :has-data?    true
 :stale?       true
 :owners       #{[:route :route/article nav-token]}
 :tags         #{[:article "welcome"]}
 :last-error   {:category :http/status
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
- first load `:loading`;
- background refresh `:fetching`;
- background refresh failure preserving data;
- success and failure transitions;
- stale/fresh behavior;
- inactive GC;
- duplicate ensure dedupe;
- stale reply suppression;
- active owner release;
- exact tag invalidation;
- active invalidated resource refetch;
- inactive invalidated resource only marked stale;
- route entry ensure;
- route leave owner release;
- route supersession via nav-token;
- SSR preload;
- hydration no-double-fetch;
- frame isolation;
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
- route `:resources`;
- managed HTTP transport;
- canonical params;
- exact tag invalidation;
- stale/fresh policy;
- inactive GC;
- dedupe;
- stale reply suppression;
- SSR preload/hydration;
- Xray/tool metadata.

Defer:

- `reg-mutation`;
- optimistic rollback;
- generic transports;
- polling/focus/reconnect revalidation;
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
- runtime-owned state that ordinary app-db writes cannot clobber;
- managed HTTP as the default transport;
- schema-aware params and decoded data;
- time-travel and SSR through frame-state;
- Xray visibility;
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
   Recommendation: next slice.
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
7. Should errors with prior data use `:status :error` or `:refresh-error`?
   Recommendation: use Pattern-RemoteData `:status :error` with preserved data
   and explicit `:has-data?`, then revisit if confusing.
8. Should generic transports be part of v1?
   Recommendation: no. Managed HTTP first.

## Bead Structure

1. EP/spec bead: turn this proposal into a normative spec.
2. Artifact skeleton bead: create `day8/re-frame2-resources`, facade wrappers,
   feature probes, and `:resource` registrar metadata.
3. Resource runtime bead: entries, canonical params, status transitions,
   passive subscriptions, and frame-local state.
4. Managed HTTP bead: ensure/refetch/success/failure over `:rf.http/managed`,
   dedupe, generation checks, and stale reply suppression.
5. Invalidation/GC bead: tags, active owners, owner indexes, stale marking,
   active refetch, and inactive GC.
6. Route integration bead: `:resources`, nav-token owners, blocking resources,
   release on leave, and preserved `:on-match` behavior.
7. SSR/hydration bead: blocking resource drain, resource projection, redaction,
   and hydration no-double-fetch.
8. Xray/tool/privacy bead: resource registry panel, route/resource graph,
   summaries, egress policy, and redacted accessors.
9. Mutation bead: `reg-mutation`, execution, invalidation, and snapshot
   rollback.
10. Docs/examples bead: guide chapter, API docs, migration notes from
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
