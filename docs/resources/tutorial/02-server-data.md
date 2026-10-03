# Part 2: real data — resources and their states

In [Part 1](01-pages-and-state.md) the feed rendered from canned data that was born `:loaded` and never moved. Now the articles come from a real Conduit API: the feed *loads*, then is *loaded*, sometimes comes back *empty*, sometimes *fails*.

By the end of this part the home page fetches the article list on entry, the article page fetches one article by slug, a second visit is a cache hit with no network, and every state the feed can be in has a branch you chose.

## Step 1 — add the resources artefact and point at an API

Resources ship as their own artefact, like routing. They sit on top of a second artefact, `day8/re-frame2-http` — the managed-HTTP **transport**, which does the network work (sending, retries, decoding). A resource describes *what* to read and how fresh it must be, hands the transport a request, and gets a decoded value back. Add both, then restart `npm run dev`:

```clojure
{:deps {thheller/shadow-cljs        {:mvn/version "3.4.10"}
        day8/re-frame2              {:local/root "../re-frame2/implementation/core"}
        day8/re-frame2-reagent      {:local/root "../re-frame2/implementation/adapters/reagent"}
        day8/re-frame2-routing      {:local/root "../re-frame2/implementation/routing"}
        day8/re-frame2-http         {:local/root "../re-frame2/implementation/http"}
        day8/re-frame2-resources    {:local/root "../re-frame2/implementation/resources"}}
 :aliases {:dev {:extra-deps {day8/re-frame2-xray {:local/root "../re-frame2/tools/xray"}}}}}
```

Leave out `day8/re-frame2-http` and the `re-frame.http.managed` require below can't be found: the resources artefact reaches the transport late-bound, so it doesn't put it on your classpath for you.

Now a tiny namespace that says where the API is. Make it `.cljc` rather than `.cljs` — it holds no browser code, and [Part 6](06-test-and-ship.md) loads it on the JVM:

```clojure
;; src/conduit/api.cljc
(ns conduit.api)

;; The current official hosted RealWorld API.
(def api-base "https://api.realworld.show/api")

;; Running the upstream reference backend locally instead? Use this line:
;; (def api-base "http://localhost:3000/api")
```

The Conduit API answers `GET /articles` with `{:articles [...] :articlesCount N}` and `GET /articles/:slug` with `{:article {...}}`. A resource stores whatever the request decodes, unchanged, so you'll reach into `(:articles data)` and `(:article data)` when you render.

!!! note "Hosted or local?"

    `https://api.realworld.show/api` is the API the RealWorld project hosts today, and it accepts browser requests from `localhost`, so your dev server can call it directly. To keep everything on your own machine instead, run the project's reference backend, [nitro-prisma-zod-realworld-example-app](https://github.com/realworld-apps/nitro-prisma-zod-realworld-example-app): install [Bun](https://bun.sh), clone it with `--recurse-submodules`, then run `make setup` and `JWT_SECRET=<any-string> bun run dev` in the checkout. It keeps its data in a local SQLite file and serves `http://localhost:3000/api` — swap the commented line in `conduit.api`. Either way, create two accounts before [Part 3](03-auth-and-forms.md) (a `POST /users` to the API does it), so that in [Part 4](04-scopes-and-guards.md) you can watch one reader's data stay out of the other's cache.

    The finished example in this repo runs with no network at all, but it gets there with an in-page backend that has a single demo user — every sign-in is the same person — so it can't show Part 4's viewer switch, and it isn't something your project can point at.

!!! warning "Gotcha — forgot to require `re-frame.resources`?"

    `rf/reg-resource` only works once the `re-frame.resources` namespace is loaded. Call it before and you get `:rf.error/resources-artefact-missing` at registration — the same pattern as routing in Part 1.

## Step 2 — declare the two reads

A **[resource](../glossary.md#resource)** is a server read registered once. You describe its identity, its freshness and the request to make; the runtime owns fetching, caching and revalidation. Create `src/conduit/resources.cljc` (`.cljc` again — registrations carry no browser code) with the two reads the app needs:

```clojure
;; src/conduit/resources.cljc
;; Adapted from examples/real-apps/realworld_resources/resources.cljs
(ns conduit.resources
  (:require [re-frame.core :as rf]
            [re-frame.http.managed]   ; the managed-HTTP transport resources use
            [re-frame.resources]      ; boots the optional artefact
            [conduit.api :as api]))

(rf/reg-resource :conduit/articles
  {:params-schema  [:map]
   :scope          :rf.scope/global          ; nobody can sign in yet — Part 4 changes this
   :stale-after-ms 60000}
  (fn [_params _ctx]
    {:request {:method :get
               :url    (str api/api-base "/articles")
               :params {:limit 10}}
     :decode  :json}))

(rf/reg-resource :conduit/article
  {:params-schema  [:map [:slug :string]]
   :scope          :rf.scope/global
   :stale-after-ms 60000}
  (fn [{:keys [slug]} _ctx]
    {:request {:method :get
               :url    (str api/api-base "/articles/" slug)}
     :decode  :json}))
```

### The shape of a `reg-resource` call

The three arguments are the resource id, metadata, and request function.
The two registrations above use the normal shape. A `:request` key in metadata
raises `:rf.error/resource-bad-spec`; the function belongs in the third argument.

### The three keys that carry the model

Most of the config map is optional. Three keys carry the idea:

- **`:params-schema`** is the read's *identity*. Everything that changes the server's answer belongs in params, because params are what the cache keys on: `:conduit/article` with `{:slug "hello"}` and with `{:slug "world"}` are two cache entries. The list takes no params, so its schema is an empty `[:map]`.
- **`:scope`** says *who shares the answer*. `:rf.scope/global` means "the same for everyone", which is true while nobody can sign in. Once requests carry a token, Conduit's articles embed `favorited` and `following` flags relative to the reader, so [Part 4](04-scopes-and-guards.md#whose-cache-is-it-scope-reads-by-viewer) moves both reads to a per-viewer [scope](../glossary.md#scope).
- **`:stale-after-ms`** is the freshness window: fresh for a minute, then the next ensure refetches in the background. Leave it out and the read is **never stale by the clock** — it stays fresh until a write invalidates it or you refetch it by hand. (TanStack Query's `staleTime` defaults to `0`, the opposite.)

!!! note "Why is `:scope` required, with no default?"

    A user-scoped read silently registered as global would serve one user's private data to another from a shared cache. So you state the intent once, at registration: a `reg-resource` with no `:scope` raises `:rf.error/resource-missing-scope-policy`. Part 4 introduces the other form, a `{:from-db <id>}` resolver that derives the scope from app-db.

The request function returns [managed-HTTP args](../../async/http.md), so transport
options such as `:retry` and `:timeout-ms` go there. Resources addresses the reply;
do not add `:request-id`, `:on-success` or `:on-failure`. The
[registration reference](../../api/re-frame.resources.md#the-resource-spec)
records the remaining metadata options.

Now delete Part 1's `seed-articles`, the `{:status …}` seed inside `:app/initialise`, and the three `:articles/*` subs. The resource replaces all of them, so `:app/initialise` shrinks to an empty seed:

```clojure
(rf/reg-event :app/initialise
  {:doc "Boot seed. Resources own server data now; app-db starts empty."}
  (fn [_cofx _event] {:db {}}))
```

The article data no longer lives in [app-db](../../core/glossary.md#app-db) at all. It lives in **[runtime-db](../../core/glossary.md#runtime-db)**, the framework-owned partition beside app-db ([the two partitions](../../core/glossary.md#the-two-partitions)). A cache entry has a lifecycle app-db doesn't model — in flight, stale, garbage-collectable when no owner keeps it alive — so the runtime keeps that bookkeeping and exposes the result through a subscription.

## Step 3 — let the routes cause the fetch

Nothing fetches yet. A resource doesn't fetch until something *causes* it, and the cleanest cause is the page that needs it.

`:resources` is route metadata; add it to the two routes from Part 1, in `core.cljs`. A route can only plan a resource that is already registered, so `core` requires `conduit.resources` — a registration namespace nobody requires never runs.

```clojure
(ns conduit.core
  (:require [re-frame.core :as rf]
            [re-frame.routing]
            [re-frame.adapter.reagent :as reagent-adapter]
            [conduit.resources]              ;; Part 2: registers the reads at load
            [conduit.articles :as articles])
  (:require-macros [re-frame.core :refer [reg-view]]))
```

Then the routes:

```clojure
(rf/reg-route :conduit/home
  {:doc       "The home page: the global article feed."
   :resources [{:resource       :conduit/articles
                :params         (fn [_route] {})
                :blocking?      false}]}
  "/")

(rf/reg-route :conduit.article/show
  {:doc       "One article, addressed by its slug."
   :params    [:map [:slug :string]]
   :resources [{:resource  :conduit/article
                :params    (fn [route] {:slug (get-in route [:params :slug])})
                :blocking? true}]}
  "/article/:slug")
```

On entry the runtime *ensures* each listed resource, with the **route as [owner](../glossary.md#owner--cause)**; on leave it releases them. [*Ensure*](../glossary.md#ensure) means "make sure a fresh-enough load exists": a cache hit when one does, a fetch when it doesn't, and — if a request for the same `{:resource :params}` is already in flight — joining that request rather than sending a second. `:rf.resource/refetch` (Step 5) is the opposite: it always sends a new request.

The flags are per-page choices:

- `:blocking? true` on the article keeps the route's `:rf.route/transition` at `:loading` until the first load settles, so a global progress bar can reflect page data. The route itself still commits at once, so the page renders its own placeholder meanwhile. (It's also the wait point for server-side rendering.)
- `:blocking? false` on the home list leaves `:rf.route/transition` alone; the feed page shows its own skeleton.

The route declares the page's data dependencies and ensures them on entry.
Numbered pagination adds `:keep-previous?` when the resource params change;
[Paginate a feed](../how-to/paginate-a-feed.md) shows that case.

!!! note "Routes aren't the only cause"

    An [event](../../core/glossary.md#event) or a [state machine](../../machines/glossary.md#machine) can ensure a resource too — `[:rf.resource/ensure {:resource … :params … :owner … :cause …}]`. A route owner is released for you on route leave; an owner your event supplies needs a matching `[:rf.resource/release-owner {:owner …}]`, or the entry stays pinned. Route entries also accept `:scope`, `:when`, and `:id` / `:after` — see [Routes with several resources](../concepts.md#routes-can-declare-more-than-one-resource).

## Step 4 — read the resource, and handle every state it can be in

The view reads the data through the `:rf/resource` subscription. It takes a `{:resource … :params …}` query and returns one ready-to-render map: the data, plus whether it is loading, refreshing, failed or stale.

Here's the rewritten home page, with the two small views it uses defined above it:

```clojure
;; src/conduit/articles.cljs  (views; the subs and seed are gone)
(reg-view feed-skeleton []
  [:div.article-preview "Loading articles…"])

(reg-view feed-error [{:keys [kind status]}]
  [:div.article-preview.error-messages
   "Couldn't load articles (" (name kind) (when status (str " " status)) ")."])

(reg-view home-page []
  (let [state    @(subscribe [:rf/resource {:resource :conduit/articles :params {}}])
        articles (:articles (:data state))]
    [:div.home-page
     [:div.banner [:div.container [:h1.logo-font "conduit"]]]
     [:div.container.page
      (cond
        (or (= :idle (:status state)) (:loading? state)) [feed-skeleton]
        (and (:error state) (not (:has-data? state)))
        [:div [feed-error (:error state)]
         [:button {:on-click #(dispatch [:rf.resource/refetch
                                          {:resource :conduit/articles :params {}
                                           :cause [:user :feed/retry]}])}
          "Retry"]]
        (empty? articles)                              [:div.article-preview "No articles are here… yet."]
        :else
        (for [article articles]
          ^{:key (:slug article)}
          [article-preview {:article article}]))]]))
```

Each branch of that `cond` handles one state the feed can be in.

### The view-model: one fixed map

`:rf/resource` returns the decoded response as `:data`. The view above uses
`:loading?` for its first load and `:has-data?` when deciding whether to show
a full error. A refresh uses `:fetching?` and keeps the cached data.
The [subscription reference](../../api/re-frame.resources.md#resource-subscriptions-passive)
records the complete map.

The five `:status` values are the model:

| `:status` | Meaning | Show |
|---|---|---|
| `:idle` | No load attempted yet | A placeholder |
| `:loading` | First load, no data yet | A skeleton |
| `:fetching` | Refreshing, prior data still visible | The data + a quiet indicator |
| `:loaded` | Usable data present | The data |
| `:error` | First load failed, no data | An error |

`:loading` and `:fetching` both mean "a request is in flight", but `:loading` is the *first* load (render a skeleton) and `:fetching` is a refresh over data you already have (keep showing it). Keeping them apart is what stops a background refresh from tearing the screen down to a spinner.

Branch on the derived booleans — `:loading?`, `:fetching?`, `:has-data?` — rather than on the raw `:status`, so the rules live in one place. That's why the `cond` above reads `(:loading? state)`, not `(= :loading (:status state))`.

Use `:rf.resource/data` when a view needs only the decoded value. It accepts the
same query as `:rf/resource` and re-renders only when the data changes.
[Focused subscriptions](../../api/re-frame.resources.md#resource-subscriptions-passive)
cover the other individual fields.

### Failure: `:error` is for first-load only

A failed *background* refresh does not flip the resource to `:error`. It stays `:loaded` with its prior data and records the problem in `:refresh-error`, so users keep reading the last good content through a flaky network. The `:error` branch is for a first load that failed, when there is nothing to show.

Both error fields carry the same closed failure shape from [managed HTTP](../../async/http.md#failures-are-a-closed-set), so one view can render either. A first-load failure looks like:

```clojure
{:status :error
 :data nil
 :error {:kind :rf.http/http-5xx :status 503}
 :has-data? false}
```

and a background-refresh failure keeps the data and tucks the problem into `:refresh-error`:

```clojure
{:status :loaded
 :data {:title "Welcome"}
 :error nil
 :refresh-error {:kind :rf.http/http-5xx :status 503}
 :has-data? true}
```

`:kind` is one of a closed `:rf.http/*` set (`:rf.http/http-4xx`, `:rf.http/http-5xx`, `:rf.http/transport`, `:rf.http/timeout`, `:rf.http/decode-failure`, …), so an error view branches on the kind of failure rather than parsing a string.

A failed first load stays `:error` until something causes the read again; a request retries on its own only when it declares `:retry`. To offer a Retry button, dispatch the same `:rf.resource/refetch` that [Step 5](#step-5--refresh-on-demand) wires to its Refresh button — an ensure works too, because an entry in `:error` is never fresh. The entry goes back to `:loading`, and when the new load lands it is `:loaded` with `:error` cleared.

On a `:blocking? true` route the failure reaches the route as well: `:rf.route/transition` turns `:error`, and `:rf.route/error` holds a `:rf.error/resource-route-blocking` map carrying the resource's failure under `:error`. A successful retry returns the route to `:idle` ([route readiness](../../routing/concepts.md#when-a-loader-fails)).

### The article page

The article page is simpler. `:blocking? true` doesn't hold the page back — the route commits at once and the page renders while the first load is in flight — so it needs two branches, the error and the article, with the skeleton as the fallback. It gets its own error view:

```clojure
(reg-view article-error [{:keys [kind status]}]
  [:div.error-messages
   "Couldn't load this article (" (name kind) (when status (str " " status)) ")."])

(reg-view article-page []
  (let [{:keys [slug]} @(subscribe [:rf.route/params])
        state          @(subscribe [:rf/resource {:resource :conduit/article :params {:slug slug}}])
        article        (:article (:data state))]
    (cond
      (and (:error state) (not (:has-data? state)))
      [:div.container.page [article-error (:error state)]
       [:button {:on-click #(dispatch [:rf.resource/refetch
                                        {:resource :conduit/article :params {:slug slug}
                                         :cause [:user :article/retry]}])}
        "Retry"]]

      article
      [:div.article-page
       [:div.banner [:div.container [:h1 (:title article)]]]
       [:div.container.page
        (when (:fetching? state) [:p "Refreshing…"])
        (when (:refresh-error state)
          [:p "Could not refresh. Showing the saved article. "
           [:button {:disabled (:fetching? state)
                     :on-click #(dispatch [:rf.resource/refetch
                                            {:resource :conduit/article :params {:slug slug}
                                             :cause [:user :article/retry]}])}
            "Retry"]])
        [:div.row.article-content [:p (:body article)]]]]

      :else [feed-skeleton])))
```

`article-preview` is Part 1's, unchanged. A child view receives one props map, so `[article-error (:error state)]` hands it the failure map itself, and the view destructures `:kind` and `:status` straight out of it.

!!! warning "Gotcha — the params must match exactly"

    A subscription is keyed by `{:resource … :params …}`. If the route ensures under `{:slug slug}` and your view subscribes with a different value or a missing key, the sub resolves a *different* entry — one nobody ensured — and reads `:idle` forever: a permanent skeleton with no error. Subscribe with exactly the params the route ensured. (Scope can't drift this way: it's declared once at registration and inherited by route and sub alike.)

## Step 5 — refresh on demand

The route causes the first fetch, and once `:stale-after-ms` has passed, the next ensure (a route entry, say) refreshes in the background; going stale fetches nothing by itself. For a user-triggered refresh, dispatch `:rf.resource/refetch` with the same identity and a `:cause`. Remove the home page's `(empty? articles)` branch, then replace its `:else` branch with the following. An empty loaded list needs Refresh too:

```clojure
        :else
        [:<>
         [:button.btn.btn-sm.btn-outline-secondary
          {:disabled (:fetching? state)
           :on-click #(dispatch [:rf.resource/refetch {:resource :conduit/articles
                                                       :params   {}
                                                       :cause    [:manual :feed/refresh]}])}
          "↻ Refresh"]
         (when (:fetching? state)      [:div.feed-refreshing "Refreshing…"])
         (when (:refresh-error state)  [:div.feed-refresh-error "Couldn't refresh — showing the last list."])
         (when (empty? articles) [:div.article-preview "No articles are here… yet."])
         (for [article articles]
           ^{:key (:slug article)}
           [article-preview {:article article}])]
```

Because the prior data is still there, the entry goes to `:fetching` (not `:loading`), so the feed stays on screen and the "Refreshing…" line appears. If the refresh fails, the list stays too and `:refresh-error` shows the second line. The `:cause` is trace metadata — it answers *why* in [Xray](../../core/glossary.md#xray) — and, unlike an `:owner`, it doesn't keep the entry alive.

## See it move

The cell below runs this part's two reads and routes against canned Conduit
replies, with a trimmed home page and article page, and lists each request
that reaches the API. Open an article, click **Home**, then open the same
article again: only the first visit sends a request. **↻ Refresh** always
sends one.

```cljs-rf2
(require '[re-frame.core :as rf]
         '[re-frame.http.managed]
         '[re-frame.resources]
         '[re-frame.routing]
         '[re-frame.http.test-support :as http-test-support]
         '[reagent2.core :as r])

(def api-base "https://api.realworld.show/api")

;; Canned Conduit replies, so the cell needs no network.
(def welcome {:slug "welcome" :title "Welcome to Conduit" :description "Start here."
              :body "Conduit is a Medium clone." :tagList ["intro"]
              :author {:username "jake"} :createdAt "2026-01-01"})
(def resources {:slug "resources" :title "Resources in practice" :description "Server data."
                :body "A resource caches one server read." :tagList ["data"]
                :author {:username "jane"} :createdAt "2026-01-02"})
(http-test-support/install-managed-request-stubs!
  {[:get (str api-base "/articles")]           {:reply {:ok {:articles [welcome resources] :articlesCount 2}}}
   [:get (str api-base "/articles/welcome")]   {:reply {:ok {:article welcome}}}
   [:get (str api-base "/articles/resources")] {:reply {:ok {:article resources}}}})

;; Log each request that reaches the stubbed API.
(def requests (r/atom []))
(rf/reg-http-interceptor :conduit/request-log
  {:frame  :conduit
   :before (fn [ctx] (swap! requests conj (get-in ctx [:request :url])) ctx)})

(rf/reg-resource :conduit/articles
  {:params-schema  [:map]
   :scope          :rf.scope/global
   :stale-after-ms 60000}
  (fn [_params _ctx]
    {:request {:method :get
               :url    (str api-base "/articles")
               :params {:limit 10}}
     :decode  :json}))

(rf/reg-resource :conduit/article
  {:params-schema  [:map [:slug :string]]
   :scope          :rf.scope/global
   :stale-after-ms 60000}
  (fn [{:keys [slug]} _ctx]
    {:request {:method :get
               :url    (str api-base "/articles/" slug)}
     :decode  :json}))

(rf/reg-route :conduit/home
  {:resources [{:resource  :conduit/articles
                :params    (fn [_route] {})
                :blocking? false}]}
  "/")

(rf/reg-route :conduit.article/show
  {:params    [:map [:slug :string]]
   :resources [{:resource  :conduit/article
                :params    (fn [route] {:slug (get-in route [:params :slug])})
                :blocking? true}]}
  "/article/:slug")

(rf/reg-view article-preview [{:keys [article]}]
  [:div.article-preview
   [rf/route-link {:to :conduit.article/show :params {:slug (:slug article)}}
    [:h3 (:title article)]]
   [:p (:description article)]])

(rf/reg-view home-page []
  (let [state    @(subscribe [:rf/resource {:resource :conduit/articles :params {}}])
        articles (:articles (:data state))]
    (cond
      (or (= :idle (:status state)) (:loading? state)) [:p "Loading articles…"]
      (and (:error state) (not (:has-data? state)))    [:p "Couldn't load articles."]
      :else
      [:div
       [:button {:disabled (:fetching? state)
                 :on-click #(dispatch [:rf.resource/refetch {:resource :conduit/articles
                                                             :params   {}
                                                             :cause    [:manual :feed/refresh]}])}
        "↻ Refresh"]
       (for [article articles]
         ^{:key (:slug article)}
         [article-preview {:article article}])])))

(rf/reg-view article-page []
  (let [{:keys [slug]} @(subscribe [:rf.route/params])
        state          @(subscribe [:rf/resource {:resource :conduit/article :params {:slug slug}}])
        article        (:article (:data state))]
    (if article
      [:div [:h2 (:title article)] [:p (:body article)]]
      [:p "Loading article…"])))

(rf/reg-view root-view []
  [:div
   [rf/route-link {:to :conduit/home} "Home"]
   (case @(subscribe [:rf.route/id])
     :conduit/home         [home-page]
     :conduit.article/show [article-page]
     [:p "Not found"])
   [:p "Requests sent: " (count @requests)]
   (into [:ol] (for [url @requests] [:li url]))])

[rf/frame-root {:id :conduit
                :initial-events [[:rf.route/navigate {:to :conduit/home}]]
                :fx-overrides {:rf.http/managed :rf.http/managed-test-stub}}
 [root-view]]
```

With the dev build running and Xray open:

1. **Load the home page.** The feed shows a skeleton, then the list. In Xray, the route-entry event row shows the ensure it caused, and the Resources panel shows `:conduit/articles` go `:idle → :loading → :loaded`.
2. **Open an article, press Back, and open it again.** The second open is a **cache hit**: the Resources panel shows it served from cache, and there's no new network row. Identity — scope + resource + params — is what makes the second read free.
3. **Wait a minute, then revisit the home page.** The list is past its `:stale-after-ms` window, so the route entry ensures it into `:fetching`: the old list stays on screen while a background refetch runs.
4. **Refresh with the network off.** Switch dev tools to offline and click **↻ Refresh**. The refetch fails and the list stays put: the entry is still `:loaded`, `:refresh-error` holds the failure, and your "Couldn't refresh" line appears. Go back online.
5. **Break a first load** (point `api-base` at a host that doesn't answer) **and reload.** The first load fails into the `:error` branch and your error view renders.

The shape hasn't changed: events write state, subs read it, views render it. The resource moved the fetch, cache and staleness bookkeeping into the runtime, behind a subscription like the ones you wrote in Part 1. The full model — scopes, owners and causes, polling, refetch rules — is in [the model](../concepts.md).
