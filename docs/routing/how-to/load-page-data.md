# Load data for a route

Load an article when its URL opens, show loading and failure states, and discard
late replies after the reader leaves. Declare the read on the route with
`:resources`; the view reads the cache and never starts a request.

Extend the tutorial's `app.core` namespace with the Resources and managed HTTP
packages, `day8/re-frame2-resources` and `day8/re-frame2-http`:

```clojure
(ns app.core
  (:require [re-frame.core :as rf]
            [re-frame.routing]
            [re-frame.resources]
            [re-frame.http.managed]
            #?(:cljs [re-frame.adapter.reagent :as reagent-adapter])))

(rf/reg-event :article/remember
  (fn [{:keys [db] rt :rf.db/runtime} _]
    {:db (assoc db :article/last-read
                (get-in rt [:rf.runtime/routing :current :params :slug]))}))

;; cf. examples/capabilities/resources/resources/core.cljs
(rf/reg-resource :article/detail
  {:params-schema [:map [:slug :string]]
   :scope :rf.scope/global}
  (fn [{:keys [slug]} _ctx]
    {:request {:method :get :url (str "/api/articles/" slug)}
     :decode :json}))

(rf/reg-route :app/article
  {:parent :app/articles
   :params [:map [:slug :string]]
   :on-match [[:article/remember]]
   :resources [{:resource :article/detail
                :params (fn [route] {:slug (get-in route [:params :slug])})
                :blocking? true}]}
  "/articles/:slug")
```

This replaces the tutorial's `:app/article` registration and its local
`:on-match` loader. The new activation event remembers the slug for the
**Continue reading** button, while resources load the article. Register the
resource, route and events before creating the frame:
a URL-bound frame loads its first route during creation. Here the API returns
an article JSON object with a `title` field. `:rf.scope/global` means these
articles are public and the same for every reader; for per-user data, use a
[scope resolver](../../resources/concepts.md#scope-whose-cache).

## Render the result

Replace `article-page` with a view that reads the same resource and params:

```clojure
(rf/reg-view article-page []
  (let [{:keys [slug]} @(subscribe [:rf.route/params])
        article @(subscribe [:rf/resource {:resource :article/detail
                                           :params {:slug slug}}])]
    (cond
      (:has-data? article) [:h1 (:title (:data article))]
      (:error article)     [:p.error "Could not load the article."]
      :else                [:p "Loading article…"])))
```

Opening `/articles/intro` starts the request. The view first shows a loading
message, then the article title or an error. Navigating to another article
changes the subscribed cache entry. Leaving releases the route's ownership;
a superseded request cannot overwrite the new page. The
[resource cache](../../resources/concepts.md) manages reuse and freshness.

`reg-route` replaces the whole metadata map. Keep `:parent`, the param schema
and any guards when changing the loader. Use `:on-match` alongside resources
for work such as recording a page visit; its events do not control readiness.

## Show progress across pages

`:blocking? true` includes this read in the route's readiness. It keeps
`:rf.route/transition` at `:loading` during a first load without data and gives
[server rendering](../../ssr/concepts.md) a read to wait for. On the client,
the URL and route commit immediately, so the loading view can render.

Render this view once in the root to report progress across routes:

```clojure
(rf/reg-view route-status []
  (case @(subscribe [:rf.route/transition])
    :loading [:p {:role "status"} "Loading page…"]
    :error [:p.error "Could not load this page."]
    nil))
```

The structured failure is available through `[:rf.route/error]`. A failed
blocking first load reports `:rf.error/resource-route-blocking`; a failed plan
reports `:rf.error/resource-route-plan`. Once all blocking reads have data,
the transition is `:idle`. The model explains the
[readiness rules](../concepts.md#readiness-comes-from-the-blocking-resources).

A cached article stays visible during a refresh, and a failed refresh stays
on the resource rather than the route. Non-blocking reads, prefetches and
`:on-match` never change the route transition. A resource declared with
`:blocking? false` starts on entry too; give its view its own loading state.

## Share a parent's data

Put data needed by a section's shell on its parent route. For example, register
the public tag list and add it to the articles route:

```clojure
(rf/reg-resource :article/tags
  {:params-schema [:map]
   :scope :rf.scope/global}
  (fn [_params _ctx]
    {:request {:method :get :url "/api/tags"}
     :decode :json}))

(rf/reg-route :app/articles
  {:query [:map [:tag {:optional true} :string]]
   :resources [{:resource :article/tags :blocking? true}]}
  "/articles")
```

The article route already names `:app/articles` as its `:parent`, so entry
loads the tag list and article detail together. An identical requirement
contributed by several routes is fetched once. Only `:resources` compose this
way; guards and `:on-match` remain specific to each route. The root view still
[renders the layout](../tutorial.md#step-7--a-shared-layout).

## Troubleshooting

| Symptom | Cause | Fix |
|---|---|---|
| `reg-route` rejects `:resources` | The Resources package is not loaded | Require `re-frame.resources` before registering routes |
| First page reports `:rf.error/resource-route-plan` | A resource or parent route is not registered, or its params or scope cannot resolve | Read `:rf.route/error`; register dependencies before creating the frame |
| First request throws `:rf.error/http-artefact-missing` | The managed HTTP transport is not loaded | Require `re-frame.http.managed` |
| Data loads but the article view stays empty | Route and view use different resource params | Build the view's params from the same route values |
| An `:on-match` failure does not show in the route's error view | Activation events report through the ordinary event error channel | Use resources for page data; handle other event failures where they occur |

## Advanced

### Warm the destination before a click

```clojure
[rf/route-link {:to :app/article :params {:slug "intro"} :prefetch :intent}
 "Read intro"]
```

Hover, focus or touch starts the destination's resources, including its
parents'. The click reuses the data or in-flight request. Prefetch changes
no route state and runs no guards or `:on-match`; it cannot grant entry to a
protected route. Omit `:prefetch` to turn it off; `:intent` is its only mode.

### Reload the plan after an identity change

After restoring a session or switching tenant, a scoped resource may select a
new cache entry while the route stays the same. Navigating to the identical URL
does nothing. Clear the old scope, commit the new identity, then dispatch:

```clojure
;; From the identity-change event's :fx, or a view's injected dispatch.
[:rf.route/replan-resources {:cause [:session-restore]}]
```

The active route's plan runs again against the current app-db. Reusable data
and in-flight reads are kept, missing reads are loaded, and dropped reads are
released. No guards, `:on-match`, URL or scroll work runs. A failed replan releases
the old plan and reports `:rf.error/resource-route-plan` on the route. The exact
request forms are in the [routing reference](../../api/re-frame.routing.md#events).
