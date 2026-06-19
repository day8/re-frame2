# Part 2: real data — resources and the nine states

In [Part 1](01-pages-and-state.md) the feed rendered from `seed-articles`. The data was born `:loaded` and never moved, so the `{:status :data :error}` shape looked like overkill — a lot of ceremony for something that never changed. Now the articles come from a real Conduit API, and that `:status` starts earning its keep. The feed genuinely *loads*, then is *loaded*, sometimes comes back *empty*, sometimes *fails*. By the end of this part the home page fetches the global article list on entry, the article page fetches one article by slug, a second visit is a cache hit with no network, and every render state the feed can be in is a branch you chose rather than one that surprised you.

**The takeaway: a server read is a subscription you read and a cause you fire — the view never fetches.**

> **Coming from TanStack Query?** A resource is `useQuery`'s keyed, cached, deduplicated read — with one structural difference you'll feel immediately: the component doesn't fetch on mount. The *route* causes the fetch; the view only reads. (The full model, and why, is [Server state: resources](../concepts/server-state.md).)

## Step 1 — add the resources artefact and point at an API

Resources ship as their own optional artefact, the way routing did in Part 1. Add it, plus the managed-HTTP transport it lowers onto — that transport is the piece that actually talks to the network, so resources can stay a level above it. Then restart `npm run dev`:

```clojure
{:deps {day8/re-frame2              {:local/root "../re-frame2/implementation/core"}
        day8/re-frame2-reagent      {:local/root "../re-frame2/implementation/adapters/reagent"}
        day8/re-frame2-routing      {:local/root "../re-frame2/implementation/routing"}
        day8/re-frame2-resources    {:local/root "../re-frame2/implementation/resources"}}}
```

Now a tiny namespace that says where the API is. To run against the hosted Conduit demo, point at it. To run offline, install the in-repo demo stub instead — see `examples/reagent/realworld_resources/http.cljs` for the canned-response override, which serves the same routes without a network:

```clojure
;; src/conduit/api.cljs
(ns conduit.api)

(def api-base "https://api.realworld.io/api")
```

The Conduit API answers `GET /articles` with `{:articles [...] :articlesCount N}` and `GET /articles/:slug` with `{:article {...}}`. A resource stores whatever the request decodes, so you'll reach into `(:articles data)` and `(:article data)` when you render — the data keeps the shape the server gave it.

## Step 2 — declare the two reads

A **resource** is a server read registered once — you describe the read here, and the runtime owns fetching, caching, and freshness from then on. Create `conduit/resources.cljs` and declare the list and the single article. The two `:tags` lines are quiet now, but they're load-bearing in Part 4: they name the facts each read contains, so a later write can invalidate exactly the reads it broke and no more.

```clojure
;; src/conduit/resources.cljs
;; Adapted from examples/reagent/realworld_resources/resources.cljs
(ns conduit.resources
  (:require [re-frame.core :as rf]
            [re-frame.http.managed]   ; the managed-HTTP transport resources use
            [re-frame.resources]      ; boots the optional artefact
            [conduit.api :as api]))

(rf/reg-resource :conduit/articles
  {:params-schema  [:map [:page {:optional true} [:maybe :int]]]
   :scope          :rf.scope/global          ; a public list — every viewer gets the same answer
   :request        (fn [{:keys [page]} _ctx]
                     {:request {:method :get
                                :url    (str api/api-base "/articles")
                                :params {:limit 10 :offset (* 10 (dec (or page 1)))}}
                      :decode  :json})
   :stale-after-ms 60000
   :tags           (fn [_params data]
                     (into #{[:article-list]}
                           (map (fn [a] [:article (:slug a)]) (:articles data))))})

(rf/reg-resource :conduit/article
  {:params-schema  [:map [:slug :string]]
   :scope          :rf.scope/global
   :request        (fn [{:keys [slug]} _ctx]
                     {:request {:method :get
                                :url    (str api/api-base "/articles/" slug)}
                      :decode  :json})
   :stale-after-ms 60000
   :tags           (fn [{:keys [slug]} _data] #{[:article slug]})})
```

Four keys carry the model. `:params-schema` is the read's *identity* — every variable that changes the server's answer belongs in params, because that's what the cache keys on. `:scope :rf.scope/global` is an explicit, auditable claim that this read is the same for everyone; scopes that aren't global are a leak boundary you'll meet in Part 3. `:tags` name the facts in the data — the list carries `[:article-list]` plus one `[:article <slug>]` per article it contains, and the detail carries `[:article <slug>]`. And `:stale-after-ms` is the freshness policy: fresh for a minute, then the next ensure refetches.

Now delete Part 1's `seed-articles`, the `{:status …}` seed in `:app/initialise`, and the three `:articles/*` subs. The resource replaces all of them, so `:app/initialise` shrinks to an empty seed. This is the part that surprises people the first time: the article data no longer lives in app-db — app-db being your app's single state map — at all. It lives in the framework-owned runtime cache instead.

```clojure
(rf/reg-event :app/initialise
  {:doc "Boot seed. Resources own server data now; app-db starts empty."}
  (fn [_cofx _event] {:db {}}))
```

## Step 3 — let the routes cause the fetch

A resource doesn't fetch until something *causes* it, and the cleanest cause is the page that needs it. `:resources` is route metadata; add it to the two routes from Part 1 (in `core.cljs`):

```clojure
(rf/reg-route :conduit/home
  {:doc       "The home page: the global article feed."
   :path      "/"
   :resources [{:resource       :conduit/articles
                :params         (fn [_route] {})
                :blocking?      false
                :keep-previous? true}]})

(rf/reg-route :conduit.article/show
  {:doc       "One article, addressed by its slug."
   :path      "/article/:slug"
   :params    [:map [:slug :string]]
   :resources [{:resource  :conduit/article
                :params    (fn [route] {:slug (get-in route [:params :slug])})
                :blocking? true}]})
```

On entry the runtime ensures each listed resource with the **route as owner**, and on leave (or a superseding navigation) it releases them. The flags are the interesting part:

- `:blocking? true` on the article holds the route transition pending until the read settles, so the article page never flashes empty before its data. (It's also the server-side-rendering wait point, when you get there.)
- `:blocking? false` on the home list lets the feed page render immediately and fill in when the list arrives. The page owns its own loading state.
- `:keep-previous? true` keeps the prior list visible while a refetch runs, so a refresh never blinks back to a skeleton.

Notice that you wrote no fetch call. The route *declares* what the page needs, and the runtime owns everything from there to the pixels.

## Step 4 — read the read, and handle every state it can be in

Views still never touch the cache directly. They read the `:rf.resource/state` subscription — a subscription being a read-only view into state that recomputes when that state changes — which projects one view-model with five statuses:

| `:status` | Meaning | Show |
|---|---|---|
| `:idle` | No load attempted yet | A placeholder |
| `:loading` | First load, no data yet | A skeleton |
| `:fetching` | Refreshing, prior data still visible | The data + a quiet indicator |
| `:loaded` | Usable data present | The data |
| `:error` | First load failed, no data | An error |

Two invariants keep views honest, and they're worth pausing on.

!!! warning "`:error` means first-load failure only"

    A failed *background* refresh does not flip the resource to `:error`. It stays `:loaded` with its prior data and records the problem in `:refresh-error`, so users keep reading last-known-good data through a flaky network. Reserve the `:error` branch for the case where there's nothing to show yet.

The derived booleans (`:loading?`, `:has-data?`, …) exist so a view never has to re-derive these rules by hand — read the boolean and trust it.

Rewrite the home page to read the resource and branch on its state. Counting the loaded articles gives you the next axis — *how many* — and that's where the nine states come in:

```clojure
;; src/conduit/articles.cljs  (views; the subs and seed are gone)
(reg-view home-page []
  (let [state    @(subscribe [:rf.resource/state {:resource :conduit/articles :params {}}])
        articles (:articles (:data state))]
    [:div.home-page
     [:div.banner [:div.container [:h1.logo-font "conduit"]]]
     [:div.container.page
      (cond
        (:loading? state)                              [feed-skeleton]
        (and (:error state) (not (:has-data? state)))  [feed-error (:error state)]
        (empty? articles)                              [:div.article-preview "No articles are here… yet."]
        :else
        [:<>
         (when (:fetching? state) [:div.feed-refreshing "Refreshing…"])
         (for [article articles]
           ^{:key (:slug article)}
           [article-preview {:article article}])])]]))
```

That `cond` is the data-lifecycle slice of a bigger idea. A real page has more render states than a cache entry does — nine of them: *Nothing, Loading, Empty, One, Some, Too Many, Incorrect, Correct, Done.* You just built the first handful: Nothing (`:idle`), Loading (the skeleton), the error branch, Empty (loaded, zero articles), and One/Some (loaded, render the list).

??? note "Where the other four states live"

    *Too Many* is a pagination cap you'll add in [Paginate a feed](../how-to/paginate-a-feed.md). *Incorrect* and *Correct* are form states from Part 3. *Done* is a domain state from Part 4. The page's render decision stays one expression over the cache entry plus the page's own state. Name all nine up front and you never discover the fifth one in production.

The article page is simpler, because `:blocking? true` guarantees the read has already settled by the time the page renders:

```clojure
(reg-view article-page []
  (let [{:keys [slug]} @(subscribe [:rf.route/params])
        state          @(subscribe [:rf.resource/state {:resource :conduit/article :params {:slug slug}}])
        article        (:article (:data state))]
    (cond
      (and (:error state) (not (:has-data? state)))
      [:div.container.page [article-error (:error state)]]

      article
      [:div.article-page
       [:div.banner [:div.container [:h1 (:title article)]]]
       [:div.container.page
        [:div.row.article-content [:p (:body article)]]]]

      :else [feed-skeleton])))
```

`article-preview`, `feed-skeleton`, `feed-error`, and `article-error` are small presentational views. Keep Part 1's `article-preview` and add the three new ones; none of them fetch.

## See it move

With the dev build running and Xray open:

1. **Load the home page.** The feed shows a skeleton, then the article list. The route-entry event row in Xray shows the ensure it caused — an event being a plain map describing something that happened. The Resources panel shows the `:conduit/articles` entry move `:idle → :loading → :loaded`.
2. **Open an article, then press Back and open it again.** The second open is a **cache hit**. The Resources panel shows it served from cache, and there's no new network row. You wrote no caching code; identity (scope + resource + params) is what makes the second read free.
3. **Break the network** (offline in dev tools, or point `api-base` at a bad host) **and reload.** First load fails into the `:error` branch and your error view renders — a real failure, owned by a view you wrote, not an uncaught promise rejection in the console.

There's still just one loop here: events write state, subs read it, views render it. A resource didn't add a second system. It moved the fetch/cache/staleness bookkeeping *into* the runtime, behind the same subs-and-events shape you already knew.

---

**You can now:**

- declare a server read as a resource — identity in `:params-schema`, an explicit `:scope`, `:tags` that name its facts, and a freshness policy
- cause the fetch from a route with `:resources` metadata, choosing `:blocking?` and `:keep-previous?` per page
- read a resource passively with `:rf.resource/state` and render its five statuses, with `:error` reserved for first-load failure
- recognise the nine page states and build the data-lifecycle ones from the cache entry plus a count

The full resources model — scopes as leak boundaries, owners vs. causes, the refetch race rules — is in [Server state: resources](../concepts/server-state.md).
