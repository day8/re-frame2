# The model

Server state is data your app **does not own**, held in a declared, inspectable
cache rather than fetched privately inside each view. This page explains the model:
register a read, cause a fetch, project its status into a view, scope the cache, and
declare writes that invalidate by tag.

<a id="three-lanes--registering-causing-projecting"></a>

Every call in the API sits in one of three **lanes**:

| Lane | What you write | Who |
|---|---|---|
| **Register** | `reg-resource` / `reg-mutation` | Your code, once, at load |
| **Cause** | route `:resources`, `[:rf.resource/ensure …]`, `[:rf.mutation/execute …]` | Routes, handlers, machines |
| **Project** | `@(subscribe [:rf/resource …])` and its narrower siblings | Views — a subscription never fetches |

??? info "Coming from TanStack Query?"

    Keep the mental model of a keyed cache with staleness and invalidation. Three
    deliberate differences show up below: views never fetch; scope is a required key
    axis; invalidation is declared on the mutation, not an `onSuccess` call you
    remember. Full mapping: [Coming from TanStack Query](coming-from-tanstack-query.md).

!!! note "Optional artefact"

    Require `re-frame.resources` (and usually `re-frame.http.managed`) once at boot —
    Maven coordinate `day8/re-frame2-resources`. Forget the require and the first
    `reg-resource` / `reg-mutation` throws `:rf.error/resources-artefact-missing`.

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
   :scope         :rf.scope/global}       ;; required — whose cache?
  (fn [{:keys [slug]} _ctx]
    {:request {:method :get
               :url    (str "/api/articles/" slug)}
     :decode  :json}))
```

`reg-resource` takes three slots: `(reg-resource id metadata request-fn)`. Putting the
request fn in the metadata map raises `:rf.error/resource-bad-spec`.

| Metadata key | Role |
|---|---|
| `:params-schema` | **Required.** Malli schema of params — the read's identity |
| `:scope` | **Required.** Either `:rf.scope/global` or `{:from-db resolver-id}` |
| `:tags` | `(fn [params data] #{…})` — facts this data is about (for invalidation) |
| `:stale-after-ms` | Freshness window; next ensure refetches after this |
| `:gc-after-ms` | GC check interval for an owner-free entry, armed when the entry settles (default 5 min; `:never` to pin) |
| `:poll-interval-ms` | Clocked re-read while owned and tab visible |
| `:infinite` | `true` → load-more feed kind ([paginate how-to](how-to/paginate-a-feed.md)) |

The request fn describes the **domain** request only. It must **not** set
`:request-id`, `:on-success`, or `:on-failure`: the runtime decides where the reply
goes, which is how it suppresses stale replies. Cross-cutting headers live in
`reg-http-interceptor`.

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
releases. `:blocking? true` keeps `:rf.route/transition` at `:loading` until the first
load settles (also an SSR wait point); the route itself commits at once.

Other causes use the same entry with a different **cause** recorded for the trace:

```clojure
;; Ensure from a handler, with an owner your app releases later
(rf/dispatch [:rf.resource/ensure
              {:resource :realworld/article
               :params   {:slug "hello"}
               :owner    [:article/opened :article-page]
               :cause    [:event :article/opened]}])

;; Pull-to-refresh: always a new request, no owner
(rf/dispatch [:rf.resource/refetch
              {:resource :realworld/article
               :params   {:slug "hello"}
               :cause    [:manual :article/refresh]}])
```

A handler or machine that causes a read and must *continue* once it settles — fill
an editor once the article is loaded, say — adds a `:reply-to` event vector to the
`ensure` or `refetch`. The reply map is appended and dispatched once: immediately on a
cache hit (`:cache-hit? true`), otherwise when the fetch settles, and never for a
stale reply:

```clojure
(rf/dispatch [:rf.resource/ensure
              {:resource :realworld/article
               :params   {:slug "hello"}
               :cause    [:event :editor/opened]
               :reply-to [:editor/article-loaded]}])

(rf/reg-event :editor/article-loaded
  (fn [{:keys [db]} [_ {:keys [status value]}]]
    {:db (cond-> db
           (= :ok status) (assoc-in [:editor :draft] (:article value)))}))
```

Views still read the cache through the subscription; `:reply-to` is for workflow
steps. The reply's fields: [`ensure` in the API](../api/re-frame.resources.md#rfresourceensure-).

??? info "Coming from TanStack Query?"

    **Views never fetch.** A route or event causes the load; the view only reads. That
    is what lets the same view render on the server, in a test, or on a cache hit.

## Project: five statuses

<a id="read-it-from-a-view"></a>
<a id="what-a-view-sees-five-statuses"></a>

```clojure
(rf/reg-view article-page [{:keys [slug]}]
  (let [state @(subscribe [:rf/resource {:resource :realworld/article
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

    With no cause, a read stays `:idle` and the view shows its placeholder for
    good. What's missing is a route `:resources` entry or an ensure, not a
    subscription.

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

Those are the only two forms. A use-site `:scope` — on an ensure payload or a sub
query — is an **override**, never a required repetition.

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

A resolver that returns `nil` **fails closed**: the read raises rather than falling
back to a shared entry. Logout clears the departing user's scope:

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

A `{:from-db …}` subscription **re-keys** when the resolver's inputs change — after
that logout, or a login, the same subscription points at the new viewer's entry. The
re-key is passive, though: it never fetches, so the new key sits `:idle` until a cause
ensures it. Navigation is the usual cause. When identity changes and the route does
not — a session restored after the page was entered, an account or tenant switch — the
cause to dispatch is `[:rf.route/replan-resources {:cause …}]`, which reruns the active
route's resource plan under the new identity without navigating: clear the old scope,
commit the new identity, then replan. See
[Replanning the active route's resources](../routing/concepts.md#replanning-the-active-routes-resources).

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

Focus revalidation is opt-in, and it is declared on the frame rather than
called: `:revalidate-on #{:focus :reconnect}` in the frame's config map. The
frame lifecycle installs the host listeners, reconciles them on
re-registration and removes them on destroy. It refetches only entries that
are **stale and still owned**.

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

On success the arms run in a fixed order: `:patches` → `:populates` → `:removes` →
`:invalidates`. Patches run *before* populates, so when the same key is both
patched and populated the **populate wins** — it is applied last, overwriting the
patch. Invalidation runs last of all. Only keys this same mutation **populated**
are spared from its immediate refetch — a populate is an authoritative load, so
the value it just wrote stays fresh. A **patched** key is *not* exempt: the same
pass may still mark it stale and refetch it.

Run it with an execute, and watch it through the instance id you chose:

```clojure
(rf/dispatch [:rf.mutation/execute
              {:mutation :realworld/favorite
               :params   {:slug "hello"}
               :instance [:ui :favorite "hello"]
               :cause    [:click :article/favorite]}])

@(rf/subscribe [:rf/mutation {:instance [:ui :favorite "hello"]}])
;; => {:status :pending …} then :success / :error
```

**Scope matters.** Invalidation matches only entries **in the scopes you name**; the
wrong scope misses silently (dev builds warn). The recipe, the populate and patch
arms, and optimistic writes are in [Invalidate after a mutation](how-to/invalidate-after-a-mutation.md).

For optimistic writes, [`linearlite`](../../examples/capabilities/resources/linearlite)
is a focused example — create,
retitle and change-status as three `:optimistic` mutations against one board
entry, with a "fail the next write" toggle that puts the rollback on screen.

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
| `:rf.error/resource-missing-scope-policy` | `:scope` omitted, or not one of the two shapes | Declare `:scope` as `:rf.scope/global` or `{:from-db <id>}` |
| `:rf.error/resource-sub-unresolved-scope` | Scope resolver returned `nil` | Resolve only when logged in, or don't subscribe |
| Invalidation refreshes nothing | Wrong scope on `:invalidates` | Name the matching scope per descriptor; watch the dev warning |
| `:rf.error/resources-artefact-missing` | Forgot the require | `(:require [re-frame.resources])` at boot |

## A complete read loop

Register, cause the fetch from the route, project in the view — a skeleton to copy:

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
  (let [slug  (get @(subscribe [:rf.route/params]) :slug)  ;; or your route projection
        state @(subscribe [:rf/resource {:resource :app/article
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
| SSR / hydration of the cache | [SSR: hydrate, then verify](../ssr/concepts.md#the-client-side-hydrate-then-verify) and the [`resources_ssr`](../../examples/capabilities/ssr/resources_ssr) example |
| Reading resources from Fresco views | [Fresco: async resources](../core/fresco/08-async-resources.md) |
| Full RealWorld build | [Tutorial](tutorial/index.md) |
| Prove the cache in tests | [Testing](testing.md) |
| Every key, event and subscription | [API reference](../api/re-frame.resources.md) |
| Migrating from `re-frame-query` | [re-frame-query → resources](../../migration/from-re-frame-v1/re-frame-query-to-resources.md) |
| When resources are the wrong tool | [When not to use resources](index.md#when-not-to-use-resources) |

<a id="infinite-feeds-accumulate-pages-with-infinite"></a>
<a id="ssr-and-hydration"></a>
<a id="freshness-and-lifetime-the-policy-keys"></a>
<a id="the-full-read-and-command-surface"></a>
<a id="polling-keep-this-fresh-every-n-ms"></a>
<a id="logout-is-one-causal-event"></a>
<a id="running-a-mutation-and-reading-its-state"></a>
<a id="when-resources-are-the-wrong-tool"></a>
