# Coming from TanStack Query

Keep the idea of a keyed server cache with freshness, deduplication and
invalidation. In re-frame2, registration describes the request, an event or
route starts it, and a subscription reads the cached result. This page maps
that workflow and the defaults that matter during migration.

## The mapping

| TanStack Query | re-frame2 resources |
|---|---|
| `queryKey` | `[scope resource-id canonical-params]`; declare scope on the resource |
| `queryFn` | The third argument of `reg-resource`, returning managed-HTTP request data |
| `useQuery` result | `[:rf/resource {:resource … :params …}]`, read with `subscribe` |
| Start or ensure a query | Route `:resources` or `:rf.resource/ensure` |
| `refetch()` | `:rf.resource/refetch` |
| `enabled: false` | Do not ensure; use route `:when` for conditional loads |
| `select` | An ordinary subscription deriving from `:rf.resource/data` |
| `staleTime` | Resource `:stale-after-ms` |
| `gcTime` | Resource `:gc-after-ms`, with different timer semantics below |
| `refetchInterval` | Resource `:poll-interval-ms`, while owned and visible |
| `refetchOnWindowFocus` / `refetchOnReconnect` | Frame `:revalidate-on #{:focus :reconnect}` |
| `placeholderData: keepPreviousData` | `:keep-previous?` on an ensure or route resource |
| `useMutation` | `reg-mutation`, `:rf.mutation/execute`, then `:rf/mutation` by instance id |
| `invalidateQueries` after a write | Mutation `:invalidates`, matching tags within a scope |
| `setQueryData` after a write | Mutation `:populates` or `:patches` |
| `onSuccess` / `onError` workflow | An execute's `:reply-to` event |
| `useInfiniteQuery` / `fetchNextPage` | `:infinite true` / `:rf.resource/load-more` |
| `data.pages` | `:rf.resource/pages`; `:rf.resource/items` merges the item lists |

The [API reference](../api/re-frame.resources.md) gives the exact payloads,
return values and errors. `isFetching` needs care: resource `:loading?` means
a first load, while `:fetching?` means a refresh over existing data. Use
`(or (:loading? state) (:fetching? state))` for either kind of in-flight read.
Infinite state separates `:fetching-next?` from whole-feed `:fetching?`.

## Defaults to choose explicitly

TanStack's [documented defaults](https://tanstack.com/query/latest/docs/framework/react/guides/important-defaults)
include immediately stale cached queries, background revalidation on focus
and reconnect, and automatic query retries. Resources chooses these separately:

| Policy | Resources default | Migration choice |
|---|---|---|
| Time-based staleness | Never stale by time alone | Set `:stale-after-ms`, including `0` for immediately stale |
| Focus/reconnect | Off | Declare the frame's `:revalidate-on` set |
| Retries | Off for reads and writes | Add `:retry` to the managed-HTTP args returned by the request function |
| Unowned cache retention | GC checks every 300000 ms | Set `:gc-after-ms`, or `:never` to keep unowned entries |

A GC check is armed when a load settles and repeats while the entry is owned
or loading. Releasing the final owner does not start a new five-minute
retention window; collection can happen at the next pending check.

```clojure
(rf/reg-resource :article/by-slug
  {:params-schema [:map [:slug :string]]
   :scope :rf.scope/global
   :stale-after-ms 60000}
  (fn [{:keys [slug]} _ctx]
    {:request {:method :get :url (str "/api/articles/" slug)}
     :decode :json}))

(rf/make-frame {:id :app :revalidate-on #{:focus :reconnect}})
```

Load `re-frame.resources` and `re-frame.http.managed` before using these forms,
as the [model](concepts.md#register-a-resource) shows.

<a id="where-it-diverges"></a>
<a id="1-a-read-never-fetches-one-hook-becomes-three-jobs"></a>

## Give each read a cause and an owner

Subscribing never starts work. If a page remains `:idle`, check its route's
`:resources` declaration or the event that should ensure it. The
[read example](concepts.md#cause-a-fetch) connects registration, route and view.
For preloading, an ownerless ensure warms one entry;
[route prefetch](../routing/concepts.md#warming-a-destination-before-the-click)
warms the destination's resource plan.

An owner has the lifetime role that an active query observer usually has.
A route owns its entries until you leave; a panel can attach and release an
application owner. Mounted subscriptions themselves do not keep an entry
alive. Invalidation immediately refetches owned matches and only marks
unowned matches stale.

<a id="2-scope-is-a-required-key-axis-not-a-key-segment-you-remember"></a>

## Put viewer identity in the scope

Every resource declares either `:rf.scope/global` or a named `{:from-db …}`
resolver. Omitting that declaration is an error. A resolver returning `nil`
also raises when a read needs a scope; it never silently chooses global data.

This does not infer authentication for you. If a response changes with the
viewer, tenant or permissions, the scope must include that distinction.
A public article with a viewer-relative `favorited` flag needs a viewer scope
too. [The scope tutorial](tutorial/04-scopes-and-guards.md) demonstrates that
case, including session restore and logout cleanup.

<a id="3-invalidation-is-a-declared-consequence-not-a-remembered-call"></a>

## Declare cache consequences on the write

`:invalidates` runs for every call of its mutation, so each button needs only
to execute the write. Tags connect the mutation to all affected reads.
Return scoped descriptors when a write affects several scopes; an unmatched
scope invalidates nothing. Direct `:rf.resource/invalidate-tags` remains
useful for changes arriving from a websocket or another server signal.

Use `:populates` when the response already contains a complete cached value,
and `:reply-to` when completion should navigate or show a message. The
[mutation recipe](how-to/invalidate-after-a-mutation.md) develops those cases.

<a id="4-optimistic-rollback-restores-the-runtimes-inverse-not-your-context"></a>

## Choose how optimistic changes settle

TanStack documents [optimistic UI and cache-update patterns](https://tanstack.com/query/latest/docs/framework/react/guides/optimistic-updates).
Resources' `:optimistic` and `:optimistic-tags` update the cache before the
request and record a snapshot for rollback. If another write changes an
entry before rollback, the default `:on-conflict :invalidate` marks it stale
instead of restoring an obsolete snapshot. `:force` restores anyway.

Keep controls disabled while pending when requests must arrive at the server
in order. Suppressing stale replies protects client state; it cannot undo
a write already applied by the server.

<a id="5-the-cache-lives-in-a-frame-not-a-process-global-client"></a>

## Scope the cache lifetime to a frame

Each frame has its own cache. SSR uses a frame per request and hydrates eligible
entries into the client frame. A fresh hydrated entry can serve the next ensure
without another request. Scope must agree on both sides; classified data may
be withheld and loaded on the client instead.

<a id="where-do-auth-headers-and-retries-go"></a>

Cross-cutting auth headers belong in a [managed-HTTP interceptor](../async/http-going-further.md#interceptors-stamp-every-request-once).
Read its frame's current token when decorating the request, so resources,
mutations and direct managed requests use the same policy.

<a id="the-full-parity-scorecard"></a>

## Check the boundaries of the migration

[Infinite resources](how-to/load-more.md) accumulate pages in one entry.
Their default refetch replaces page zero and retains the tail. Choose
`:refetch {:refetch-all-pages? true}` to refresh every loaded page, or
`:refetch {:refetch-window n}` for a leading window. There is no prepend event
or automatic page eviction. A failed later-page refetch uses `:page-error`.

Resources provides neither an Apollo/Relay-style normalized entity cache nor
offline persistence or cross-tab broadcast. If those are requirements, plan
their integration explicitly.

## When to reach for resources at all

Use resources when cached reads, freshness and invalidation simplify the app.
For a one-off login request or a result an event stores in app-db,
[managed HTTP](../async/http.md) is enough. RTK Query and SWR users can use the
same resource model, but should compare their own retry and retention policies
rather than assuming TanStack's defaults apply.
