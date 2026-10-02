# The model

A resource keeps a cached copy of server data. Routes and events ask for it;
views read the result. This separation lets several views share one request
without any view deciding when to fetch.

<a id="three-lanes--registering-causing-projecting"></a>

The application has three jobs: register how to read, cause a load, and render
the cached state. A [mutation](#mutations-invalidate-by-tag) describes a write
and which cached reads it changes.

## Register a resource

<a id="your-first-resource-register-it"></a>

```clojure
;; cf. examples/real-apps/realworld_resources/resources.cljs
(ns app.articles
  (:require [re-frame.core :as rf]
            [re-frame.http.managed]
            [re-frame.resources]
            [re-frame.routing]))

(rf/reg-resource :app/article
  {:params-schema [:map [:slug :string]]
   :scope         :rf.scope/global
   :stale-after-ms 60000
   :tags          (fn [{:keys [slug]} _data] #{[:article slug]})}
  (fn [{:keys [slug]} _ctx]
    {:request {:method :get :url (str "/api/articles/" slug)}
     :decode  :json}))
```

This assumes the endpoint returns `{:article {...}}`, with the same response
for every viewer. `:params-schema` describes the values identifying the read;
`:scope` describes who can share its cached answer. The request function is
the third argument and returns [managed-HTTP args](../async/http.md).
Registration itself sends nothing.

`:stale-after-ms 60000` keeps a successful response fresh for one minute.
Without that key, only explicit invalidation makes it stale. Becoming stale
does not start a request; the next ensure can refresh it.

The optional Resources and HTTP artefacts must both be loaded. Missing
`re-frame.resources` raises `:rf.error/resources-artefact-missing` at
registration; missing `re-frame.http.managed` raises
`:rf.error/http-artefact-missing` when the first request starts.
Params validation also needs the [schemas artefact](../core/how-to/validate-with-schemas.md).
The [resource reference](../api/re-frame.resources.md#the-resource-spec) lists
all registration options.

## Cause a fetch

<a id="cause-it-to-fetch-from-a-route"></a>

For page data, let the route declare what it needs:

```clojure
(rf/reg-route :app/article
  {:params [:map [:slug :string]]
   :resources [{:resource :app/article
                :params (fn [route] {:slug (get-in route [:params :slug])})
                :blocking? true}]}
  "/articles/:slug")
```

Entering this route ensures the article. A fresh entry is a cache hit; an
in-flight load is shared; otherwise an HTTP request starts. `:blocking? true`
keeps `:rf.route/transition` at `:loading` until the first load settles. The
route commits immediately, so its view can render a loading state. A failed
first load makes the transition `:error`; a successful retry restores `:idle`.
The same declaration gives [SSR](../ssr/concepts.md) a wait point.

Events can load data independently of navigation. Here an open preview keeps
the entry alive until it closes:

```clojure
(rf/reg-event :article/preview-opened
  (fn [_ [_ slug]]
    {:fx [[:dispatch [:rf.resource/ensure
                      {:resource :app/article :params {:slug slug}
                       :owner [:article/preview slug]
                       :cause [:event :article/preview-opened]}]]]}))

(rf/reg-event :article/preview-closed
  (fn [_ [_ slug]]
    {:fx [[:dispatch [:rf.resource/release-owner
                      {:owner [:article/preview slug]}]]]}))
```

An **owner** keeps an entry alive. A **cause** explains why the request happened
in traces and Xray. Reading a subscription adds neither.

## Project: five statuses

<a id="read-it-from-a-view"></a>
<a id="what-a-view-sees-five-statuses"></a>
<a id="a-complete-read-loop"></a>

The route above and this view complete the read path. The application shell
renders `article-page` when `:rf.route/id` is `:app/article`:

```clojure
(rf/reg-view article-page []
  (let [slug (:slug @(subscribe [:rf.route/params]))
        query {:resource :app/article :params {:slug slug}}
        state @(subscribe [:rf/resource query])]
    (cond
      (= :idle (:status state)) [:p "Waiting for the article load."]
      (:loading? state)         [:p "Loading article…"]
      (:error state)
      [:div
       [:p "Could not load the article."]
       [:button {:on-click #(dispatch [:rf.resource/refetch query])} "Retry"]]
      :else
      [:article
       [:h1 (get-in state [:data :article :title])]
       [:p (get-in state [:data :article :body])]
       (when (:fetching? state) [:p "Refreshing…"])
       (when (:refresh-error state) [:p "Could not refresh; showing saved data."])
       [:button {:disabled (:fetching? state)
                 :on-click #(dispatch [:rf.resource/refetch query])}
        "Refresh"]])))
```

| Status | Meaning | Useful UI |
|---|---|---|
| `:idle` | No load has started, or a first load was cancelled | Placeholder |
| `:loading` | First load in flight, no data | Loading indicator |
| `:loaded` | Data is available, possibly stale | Content or an empty-result message |
| `:fetching` | Refresh in flight, keeping existing data | Content and a small progress indicator |
| `:error` | First load failed | Error and retry action |

A failed refresh keeps `:loaded` and the data, and sets `:refresh-error`.
`:error` is reserved for a failed first load. Both fields hold a
[managed-HTTP failure](../async/http.md#failures-are-a-closed-set); branch on
its `:kind` when different failures need different messages. Retries are
opt-in in the request args; an error does not retry itself by default.

The query must use the same params and scope as the cause. A valid but
different key has its own entry, which stays `:idle` until ensured. Narrow
subscriptions such as `:rf.resource/data` read one field when a view does not
need the whole state.

## Scope: whose cache?

<a id="the-scoped-key-a-leak-boundary-that-fails-closed"></a>
<a id="logout-is-one-causal-event"></a>

A cache key is `[scope resource-id canonical-params]`. `:rf.scope/global`
asserts that everyone gets the same answer. If authentication, tenant, locale
or permissions affect it, include those distinctions in a named scope resolver:

```clojure
(rf/reg-resource-scope :app/session
  {:inputs {:username [:db [:auth :user :username]]}}
  (fn [{:keys [username]} _ctx]
    (when username [:rf.scope/session {:username username}])))

;; On a viewer-dependent resource registration:
;; :scope {:from-db :app/session}
```

Routes and subscriptions inherit that policy. A resolver returning `nil`
raises `:rf.error/resource-sub-unresolved-scope` on a subscription and
`:rf.error/resource-scope-unresolved-reference` on an ensure. Wait until the
identity is known before reading or loading that resource. Scope selects a
cache entry; your server still authenticates and authorizes the request.

When the resolver's inputs change, a subscription reads the new key without
fetching. If the route stays the same, dispatch
`[:rf.route/replan-resources {:cause :account-changed}]` after committing the
new identity. The [scope tutorial](tutorial/04-scopes-and-guards.md) handles
session restoration, logout and that replan together.

Resolve a departing user's scope from the event's `db` before removing the
identity, then pass the concrete value to `clear-scope`:

```clojure
(rf/reg-event :auth/logout
  (fn [{:keys [db]} _]
    (let [old-scope (rf/resolve-resource-scope db :app/session)]
      {:db (dissoc db :auth)
       :fx (cond-> []
             old-scope
             (conj [:dispatch [:rf.resource/clear-scope
                               {:scope old-scope :cause :logout}]]))})))
```

## Owners, causes, refetch rules

<a id="owners-and-causes-and-the-refetch-rules"></a>
<a id="freshness-and-lifetime-the-policy-keys"></a>
<a id="polling-keep-this-fresh-every-n-ms"></a>

A route releases its owner on leave. A machine using `[:machine actor-id]`
as owner releases it on actor destruction. Your own owner, as in the preview
example, needs a matching `release-owner` event.

Owned entries survive GC and refetch when invalidated. Once unowned and no
longer loading, an entry can be collected at the next `:gc-after-ms` check
(default five minutes). The check is armed when a load settles; it does not
promise five full minutes of retention after the owner leaves.

An ownerless ensure is useful for warming the cache. It loads normally but
keeps nothing alive. A subscription is always passive, even while a view
remains mounted.

`refetch` always starts a new request and supersedes any old one for the key.
Releasing the last owner aborts in-flight work where possible. A cancelled
first load returns to `:idle`; a cancelled refresh keeps its data at `:loaded`.
Cancellation sets neither resource error field. Late replies cannot replace
newer cache data.

Use `:poll-interval-ms` for data that changes regularly: it polls while owned
and visible. Set `:revalidate-on #{:focus :reconnect}` on the frame to refresh
stale owned entries when the user returns or the network reconnects. Both
are optional; [polling and revalidation](../api/re-frame.resources.md#polling)
describe the timing rules.

## Continue after a read

An editor may need a fetched article copied into a draft once. Add `:reply-to`
to the ensure rather than making the subscription dispatch:

```clojure
(rf/reg-event :editor/opened
  (fn [_ [_ slug]]
    {:fx [[:dispatch [:rf.resource/ensure
                      {:resource :app/article :params {:slug slug}
                       :reply-to [:editor/article-loaded]}]]]}))

(rf/reg-event :editor/article-loaded
  (fn [{:keys [db]} [_ {:keys [status value]}]]
    (if (= :ok status)
      {:db (assoc-in db [:editor :draft] (:article value))}
      {})))
```

The reply arrives once for the accepted attempt, including a fresh cache hit.
It contains `:status` and `:value` or `:error`; cancellation uses `:cancelled`.
The view still renders loading and failure from the resource subscription.
For editors that can close or switch articles before the reply arrives,
carry a visit id and check it before changing the draft, as the
[mutation tutorial](tutorial/05-mutations-and-invalidation.md#publish-from-the-editor--and-continue-with-reply-to)
does for saves.

## Mutations invalidate by tag

<a id="writes-invalidate-by-tag--causally"></a>
<a id="optimistic-writes-commit-roll-back-or-reconcile"></a>
<a id="running-a-mutation-and-reading-its-state"></a>

A mutation declares the cache consequences of a server write. Resources tag
their data; mutations name the tags they change. Owned matches refetch now,
while unowned matches become stale for the next ensure.

```clojure
(rf/reg-mutation :app/save-article
  {:params-schema [:map [:slug :string] [:title :string]]
   :scope :rf.scope/global
   :invalidates (fn [{:keys [slug]} _result] #{[:article slug]})}
  (fn [{:keys [slug title]} _ctx]
    {:request {:method :put :url (str "/api/articles/" slug)
               :body {:article {:title title}}
               :request-content-type :json}
     :decode :json}))

;; Dispatch from a handler or a view's captured dispatch:
;; [:rf.mutation/execute {:mutation :app/save-article
;;                        :params {:slug "hello" :title "Hello"}
;;                        :instance [:editor/save "hello"]}]
```

The view watches `[:rf/mutation {:instance [:editor/save "hello"]}]` for
`:pending?`, `:success?` and `:error`. Retrying uses the same execute command;
clearing the instance dismisses its settled state. The
[mutation recipe](how-to/invalidate-after-a-mutation.md) shows tag invalidation
and direct updates; [optimistic updates](how-to/optimistic-updates.md) makes a
small write visible before confirmation.

## Advanced

<a id="routes-with-several-resources"></a>
<a id="routes-can-declare-more-than-one-resource"></a>
<a id="infinite-feeds-accumulate-pages-with-infinite"></a>
<a id="ssr-and-hydration"></a>
<a id="the-full-read-and-command-surface"></a>
<a id="the-cache-you-dont-own"></a>
<a id="cache-home"></a>

A route can declare several resources. `:id` / `:after` order their ensure
dispatches; they do not wait for earlier data. When one read needs another's
result, use a completion event to compute and ensure the dependent read.

[Numbered pagination](how-to/paginate-a-feed.md) caches one entry per page;
[load-more](how-to/load-more.md) grows one entry. Resource entries live in
[runtime-db](../core/glossary.md#runtime-db), alongside mutation instances and
work records; ordinary application handlers never edit those tables directly.
SSR hydrates eligible entries and applies the same scope and freshness rules.

## Troubleshooting

<a id="when-it-fails-loud--the-errors-and-warnings"></a>
<a id="when-resources-are-the-wrong-tool"></a>

| Symptom | Check | Fix |
|---|---|---|
| The view stays `:idle` | Did a route or event ensure this exact key? | Match resource, params and scope in the cause and read |
| Invalidation refreshes nothing | Do tags and scope match, and is the entry owned? | Use the read's scope and attach an owner for active data |
| Cache grows after panels close | Is each app-created owner released? | Dispatch `release-owner` on close |
| A session switch leaves an idle page | Re-keying is passive | Replan the active route after changing identity |
| Route transition is `:error` | Read `:rf.route/error` | Retry a failed blocking read, or fix a failed resource plan |

[Errors and warnings](errors-and-warnings.md) maps each named failure to a
recovery. For a one-off request whose result belongs in app-db, use
[managed HTTP](../async/http.md) directly instead of adding a cache.
