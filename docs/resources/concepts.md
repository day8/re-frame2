# The model

Server state is data your app **does not own** — a declared, inspectable cache, not a
private fetch inside each view. This page is the **core model**: register a read,
cause a fetch, project five statuses, scope the cache, and declare writes that
invalidate by tag.

To *build* Conduit end to end, use the [tutorial](tutorial/index.md). Task recipes:
[paginate](how-to/paginate-a-feed.md), [invalidate after a mutation](how-to/invalidate-after-a-mutation.md).

??? info "Coming from TanStack Query?"

    Keep the mental model of a keyed cache with staleness and invalidation. Three
    deliberate differences show up below: views never fetch; scope is a required key
    axis; invalidation is declared on the mutation, not an `onSuccess` call you
    remember. Full mapping: [Coming from TanStack Query](coming-from-tanstack-query.md).

!!! note "Optional artefact"

    Require `re-frame.resources` (and usually `re-frame.http.managed`) once at boot —
    Maven coordinate `day8/re-frame2-resources`. Forget the require and the first
    `reg-resource` / `reg-mutation` throws `:rf.error/resources-artefact-missing`.
    An app with one or two uncached reads is often happier with
    [managed HTTP](../async/http.md) alone.

## The cache you don't own

<a id="the-cache-you-dont-own"></a>

A **[resource](glossary.md#resource)** answers five questions that SPAs usually re-decide
per feature: where the copy lives, when it is stale, who may refetch, how concurrent
readers share one request, and how logout stops a cross-user leak.

That cache lives in **[runtime-db](../core/glossary.md#runtime-db)** (path
`:rf.runtime/resources`), not [app-db](../core/app-db.md). Ordinary handlers cannot
wipe it by accident. You change it only through [events](../core/glossary.md#event)
and read it through [subscriptions](../core/glossary.md#subscription).

## Register a resource

<a id="your-first-resource-register-it"></a>

A resource is *a subscription you read and a cause you fire* — two different jobs.

```clojure
;; cf. examples/real-apps/realworld_resources/resources.cljs
(ns app.resources
  (:require [re-frame.core :as rf]
            [re-frame.http.managed]
            [re-frame.resources]))

(rf/reg-resource :realworld/article
  {:params-schema [:map [:slug :string]]
   :scope         :rf.scope/global}       ;; REQUIRED — whose cache?
  (fn [{:keys [slug]} _ctx]
    {:request {:method :get
               :url    (str "/api/articles/" slug)}
     :decode  :json}))
```

Strict **three-slot** grammar: `(reg-resource id metadata request-fn)`. Putting the
request fn in the metadata map is `:rf.error/resource-bad-spec`.

| Metadata key | Role |
|---|---|
| `:params-schema` | **Required.** Malli schema of params — the read's identity |
| `:scope` | **Required.** `:rf.scope/global`, `{:from-db resolver-id}`, or `:rf.scope/from-caller` |
| `:tags` | `(fn [params data] #{…})` — facts this data is about (for invalidation) |
| `:stale-after-ms` | Freshness window; next ensure refetches after this |
| `:gc-after-ms` | Lifetime after last owner leaves (default 5 min; `:never` to pin) |
| `:poll-interval-ms` | Clocked re-read while owned and tab visible |
| `:infinite` | `true` → load-more feed kind ([paginate how-to](how-to/paginate-a-feed.md)) |

The request fn describes the **domain** request only. It must **not** set
`:request-id`, `:on-success`, or `:on-failure` — the runtime owns reply addressing
(stale-reply suppression). Cross-cutting headers live in `reg-http-interceptor`.

`reg-resource` does not fetch. It only teaches the runtime *how* to.

## Cause a fetch

<a id="cause-it-to-fetch-from-a-route"></a>

The cleanest cause is the **page**. Route metadata `:resources` means "this page needs
this server state":

```clojure
;; cf. examples/real-apps/realworld_resources/routing.cljs
(rf/reg-route :realworld/article
  {:params    [:map [:slug :string]]
   :resources [{:resource  :realworld/article
                :params    (fn [route] {:slug (get-in route [:params :slug])})
                :blocking? true}]}
  "/articles/:slug")
```

On entry the runtime **ensures** the resource with the route as **owner**; on leave it
releases. `:blocking? true` holds the transition until the read settles (also an SSR
wait point).

Other causes use the same entry with a different **cause** recorded for the trace:

```clojure
;; Explicit ensure from a handler (app-minted event owner — release on leave)
(rf/dispatch [:rf.resource/ensure
              {:resource :realworld/article
               :params   {:slug "hello"}
               :owner    [:article/opened :article-page]
               :cause    [:event :article/opened]}])

;; Pull-to-refresh — new generation, no owner
(rf/dispatch [:rf.resource/refetch
              {:resource :realworld/article
               :params   {:slug "hello"}
               :cause    [:manual :article/refresh]}])
```

??? info "Coming from TanStack Query?"

    **Views never fetch.** A route or event causes the load; the view only reads. That
    is what lets the same view render on the server, in a test, or on a cache hit.

## Project: five statuses

<a id="read-it-from-a-view"></a>
<a id="what-a-view-sees-five-statuses"></a>
<a id="three-lanes--registering-causing-projecting"></a>

```clojure
(rf/reg-view article-page [slug]
  (let [state @(rf/subscribe [:rf/resource {:resource :realworld/article
                                            :params   {:slug slug}}])]
    (cond
      (= :idle (:status state))                      [article-placeholder]
      (:loading? state)                              [article-skeleton]
      (and (:error state) (not (:has-data? state)))  [article-error (:error state)]
      :else
      [:<>
       (when (:fetching? state)     [refresh-indicator])
       (when (:refresh-error state) [refresh-warning (:refresh-error state)])
       [article-view (:data state)]])))
```

| `:status` | Meaning | Show |
|---|---|---|
| `:idle` | No load attempted | Placeholder |
| `:loading` | First load, no usable data | Skeleton |
| `:fetching` | Refresh while prior data stays | Data + quiet indicator |
| `:loaded` | Usable data (maybe stale) | Data |
| `:error` | First load failed | Error |

**Invariants.** `:error` is first-load only — a failed background refresh keeps
`:loaded` and records `:refresh-error`. Freshness is orthogonal to status. Prefer
the booleans (`:loading?`, `:has-data?`, …) over re-deriving rules from `:status`.

!!! warning "No subscription ever fetches"

    Missing cause ⇒ permanent `:idle` / skeleton. You are missing a route
    `:resources` or an ensure, not a sub.

### Three lanes

| Lane | Spelling | Who |
|---|---|---|
| Register | `reg-resource` / `reg-mutation` | Author at boot |
| Cause | route `:resources`, `ensure` / `execute` events | Routes, handlers, machines |
| Project | `[:rf/resource …]` and friends | Views |

Narrower projections (`[:rf.resource/data …]`, `[:rf.resource/status …]`, …) re-render
only when that slice changes. Commands include
`ensure`, `refetch`, `invalidate-tags`, `release-owner`, `clear-scope`, `remove` —
full list in the [API](../api/re-frame.resources.md).

## Scope: whose cache?

<a id="the-scoped-key-a-leak-boundary-that-fails-closed"></a>

Cache identity is a triple: `[scope resource-id canonical-params]`.

- **`:rf.scope/global`** — same answer for every viewer (explicit claim).
- **`{:from-db resolver-id}`** — viewer-relative; resolver pure over declared
  `:inputs`.
- **`:rf.scope/from-caller`** — every ensure/sub must supply `:scope` or fail loud.

```clojure
(rf/reg-resource-scope :realworld/session
  {:inputs {:username [:db [:auth :user :username]]}}
  (fn [{:keys [username]} _ctx]
    (when username
      [:rf.scope/session {:username username}])))

(rf/reg-resource :realworld/feed
  {:params-schema [:map [:page {:optional true} [:maybe :int]]]
   :scope         {:from-db :realworld/session}
   :tags          (fn [_ _] #{[:feed]})}
  (fn [{:keys [page]} _ctx]
    {:request {:method :get
               :url    "/api/articles/feed"
               :params {:limit 10 :offset (* 10 (dec (or page 1)))}}
     :decode  :json}))
```

Nil resolution **fails closed** — no silent shared read. Logout clears a scope:

```clojure
(rf/reg-event :auth/logout
  (fn [{:keys [db]} _]
    (let [old-scope (rf/resolve-resource-scope db :realworld/session)]
      {:db (dissoc db :auth)
       :fx (cond-> []
             old-scope
             (conj [:dispatch [:rf.resource/clear-scope
                               {:scope old-scope :cause :logout}]]))})))
```

Resolve the old scope **before** stripping auth from `db`.

??? info "Coming from TanStack Query?"

    Scope is a **required structural axis**, not a key segment you assemble by hand
    and sometimes forget.

## Owners, causes, refetch rules

<a id="owners-and-causes-and-the-refetch-rules"></a>

- **Owner** — a liveness hold (route, machine, app-event owner). Controls GC and whether
  invalidation refetches now or only marks stale.
- **Cause** — why this fetch happened (trace / Xray). Does not keep the entry alive.

| Rule | Behaviour |
|---|---|
| Ensure of a fresh entry | Cache hit |
| Ensure while in flight | Join the existing request |
| Explicit refetch | New generation; supersedes in-flight |
| Cancel vs stale reply | Abort if possible; generation check always suppresses stale replies |

Focus revalidation is opt-in: `(rf/install-revalidation-listeners! frame-id)` —
refetches only entries that are **stale and still owned**.

Polling is a registration key — owner-driven, pauses when the tab is hidden:

```clojure
(rf/reg-resource :dashboard/build-status
  {:scope            :rf.scope/global
   :params-schema    [:map [:repo :string]]
   :poll-interval-ms 5000
   :tags             (fn [_ _] #{[:build]})}
  (fn [{:keys [repo]} _ctx]
    {:request {:method :get :url (str "/repos/" repo "/build")}
     :decode  :json}))
```

Three freshness tools, three questions:

| Tool | Question |
|---|---|
| `:poll-interval-ms` | Changes on its own — keep fresh on a clock |
| Focus revalidation | User came back — refresh stale owned data |
| Mutation `:invalidates` | *This* write made *that* read wrong |

## Routes with several resources

<a id="routes-can-declare-more-than-one-resource"></a>

Each `:resources` entry may carry `:params`, `:scope`, `:blocking?`, `:when`,
`:keep-previous?` (show prior page while the next loads), and `:id` / `:after`
(order ensure **dispatch**, not data waterfalls). Full recipe for pages:
[Paginate a feed](how-to/paginate-a-feed.md).

## Mutations invalidate by tag

<a id="writes-invalidate-by-tag--causally"></a>
<a id="optimistic-writes-commit-roll-back-or-reconcile"></a>

A **[mutation](glossary.md#mutation)** is a named write. On success it
[invalidates](glossary.md#invalidate) the tags it broke — declared once, not
remembered in `onSuccess`:

```clojure
;; cf. examples/real-apps/realworld_resources/mutations.cljs
(rf/reg-mutation :realworld/favorite
  {:params-schema [:map [:slug :string]]
   :scope         :rf.scope/global
   :invalidates   (fn [{:keys [slug]} _result]
                    [{:scope :rf.scope/global
                      :tags  #{[:article slug] [:article-list]}}
                     {:scope {:from-db :realworld/session}
                      :tags  #{[:feed]}}])}
  (fn [{:keys [slug]} _ctx]
    {:request {:method :post
               :url    (str "/api/articles/" slug "/favorite")}
     :decode  :json}))
```

Success plan arms (fixed order): `:patches` → `:populates` → `:removes` →
`:invalidates`. Patches run *before* populates, so when the same key is both
patched and populated the **populate wins** — it is applied last, overwriting the
patch. Invalidation runs last of all. Only keys this same mutation **populated**
are spared from its immediate refetch — a populate is an authoritative load, so
the value it just wrote stays fresh. A **patched** key is *not* exempt: the same
pass may still mark it stale and refetch it.

Execute and watch (instance id is app-chosen — reuse it for the sub):

```clojure
(rf/dispatch [:rf.mutation/execute
              {:mutation :realworld/favorite
               :params   {:slug "hello"}
               :instance [:ui :favorite "hello"]
               :cause    [:click :article/favorite]}])

@(rf/subscribe [:rf/mutation {:instance [:ui :favorite "hello"]}])
;; => {:status :pending …} then :success / :error
```

**Scope footgun.** Invalidation matches only entries **in the scopes you name**.
Wrong scope ⇒ silent miss (dev warning). Recipe, populate/patch arms, and optimistic
writes: [Invalidate after a mutation](how-to/invalidate-after-a-mutation.md).

??? info "Coming from TanStack Query?"

    Invalidation is **causal** — a declared consequence of the mutation, visible on
    the event record.

## Troubleshooting

<a id="when-it-fails-loud--the-errors-and-warnings"></a>

Registration and use-time errors fail closed (missing scope policy, bad request
shape, unresolved scope on sub, …). There is no path from "forgot the viewer" to
"served another user's cache." Named ids live in the
[API](../api/re-frame.resources.md) and error catalogue; [testing](testing.md)
turns the same failures into assertions.

| Symptom | Signal | Fix |
|---|---|---|
| Permanent `:idle` / skeleton | No cause fired | Route `:resources` or `[:rf.resource/ensure …]` |
| `:rf.error/resource-missing-scope-policy` | Scope omitted on `reg-resource` | Add `:scope` (`:rf.scope/global` or a resolver) |
| `:rf.error/resource-sub-unresolved-scope` | Scope resolver returned `nil` | Resolve only when logged in, or don't subscribe |
| `:rf.warning/resource-sub-scope-mismatch` | Sub scope ≠ active ensure scope | One named resolver for register, route, and sub |
| Invalidation refreshes nothing | Wrong scope on `:invalidates` | Name the matching scope per descriptor; watch the dev warning |
| `:rf.error/resources-artefact-missing` | Forgot the require | `(:require [re-frame.resources])` at boot |

## A complete read loop

Register → route causes → view projects. Copy-paste skeleton:

```clojure
(ns app.articles
  (:require [re-frame.core :as rf]
            [re-frame.resources]
            [re-frame.http.managed]
            [re-frame.routing]))

(rf/reg-resource :app/article
  {:params-schema [:map [:slug :string]]
   :scope         :rf.scope/global
   :stale-after-ms 60000
   :tags          (fn [{:keys [slug]} _] #{[:article slug] [:article-list]})}
  (fn [{:keys [slug]} _ctx]
    {:request {:method :get :url (str "/api/articles/" slug)}
     :decode  :json}))

(rf/reg-route :app/article
  {:params    [:map [:slug :string]]
   :resources [{:resource  :app/article
                :params    (fn [route] {:slug (get-in route [:params :slug])})
                :blocking? true}]}
  "/articles/:slug")

(rf/reg-view article-page []
  (let [slug  (get @(rf/subscribe [:rf.route/params]) :slug)  ;; or your route projection
        state @(rf/subscribe [:rf/resource {:resource :app/article
                                            :params   {:slug slug}}])]
    (cond
      ;; :idle — nothing has caused a load yet (no route :resources / ensure hit)
      (= :idle (:status state))                     [placeholder]
      ;; :loading — first load, no usable data yet
      (:loading? state)                             [skeleton]
      ;; :error — first load failed, still no data (a failed *refresh* keeps :loaded)
      (and (:error state) (not (:has-data? state))) [error-panel (:error state)]
      ;; :loaded / :fetching — usable data; a background refresh keeps it visible
      :else
      [:<>
       (when (:fetching? state) [refresh-indicator])   ;; refetching with data
       [article-body (:data state)]])))
```

## Advanced (elsewhere)

| Topic | Where |
|---|---|
| Numbered pages & infinite feeds | [Paginate a feed](how-to/paginate-a-feed.md) |
| Optimistic UI, patches, populate | [Invalidate after a mutation](how-to/invalidate-after-a-mutation.md) |
| SSR / hydration of the cache | [SSR concepts](../ssr/concepts.md) + tutorial Part 2 |
| Full RealWorld build | [Tutorial](tutorial/index.md) |
| Prove the cache in tests | [Testing](testing.md) |

<a id="infinite-feeds-accumulate-pages-with-infinite"></a>
<a id="ssr-and-hydration"></a>
<a id="freshness-and-lifetime-the-policy-keys"></a>
<a id="the-full-read-and-command-surface"></a>
<a id="polling-keep-this-fresh-every-n-ms"></a>
<a id="logout-is-one-causal-event"></a>
<a id="running-a-mutation-and-reading-its-state"></a>

## When resources are the wrong tool

<a id="when-resources-are-the-wrong-tool"></a>

| Situation | Prefer |
|---|---|
| One-off uncached call | [Managed HTTP](../async/http.md) |
| Client-only state | app-db |
| Named stage machine | [Machines](../machines/index.md) |
| No server yet | app-db + events |

**Cached server reads that multiply** are the reason to reach for this artefact —
not every network call.
