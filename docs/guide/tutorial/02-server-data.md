# Part 2: real data — resources and the nine states

In [Part 1](01-pages-and-state.md) the feed rendered from `seed-articles`. The data was born `:loaded` and never moved, so the `{:status :data :error}` shape looked like overkill — a lot of ceremony for something that never changed. Now the articles come from a real Conduit API, and that `:status` starts earning its keep. The feed genuinely *loads*, then is *loaded*, sometimes comes back *empty*, sometimes *fails*. By the end of this part the home page fetches the global article list on entry, the article page fetches one article by slug, a second visit is a cache hit with no network, and every render state the feed can be in is a branch *you* chose rather than one that surprised you at 2am in production.

**The takeaway: a server read is a subscription you read and a cause you fire — the view never fetches.**

> **Coming from TanStack Query?** A resource is `useQuery`'s keyed, cached, deduplicated read — same idea, one structural difference you'll feel immediately: the component doesn't fetch on mount. There's no `useQuery(...)` call buried inside the view that quietly kicks off a request the first time React renders it. The *route* causes the fetch; the view only reads what's there. That inversion is the whole point, and the full model — with the *why* — is in [Server state: resources](../concepts/server-state.md).

## Step 1 — add the resources artefact and point at an API

Resources ship as their own optional artefact, the way routing did in Part 1 — you only pay for the machinery you use. Add it, plus the managed-HTTP transport it lowers onto. That transport is the piece that actually talks to the network, which lets resources stay a level above it: a resource describes *what* to read and *how fresh* it must be, and the transport worries about sockets and retries. Add both deps and restart `npm run dev`:

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

The Conduit API answers `GET /articles` with `{:articles [...] :articlesCount N}` and `GET /articles/:slug` with `{:article {...}}`. A resource stores whatever the request decodes — verbatim, no reshaping — so you'll reach into `(:articles data)` and `(:article data)` when you render. The data keeps the shape the server gave it.

## Step 2 — declare the two reads

A **resource** is a server read registered once. You describe the read here — its identity, its freshness, the request to make — and from then on the runtime owns fetching, caching, and revalidation. Create `conduit/resources.cljs` and declare the list and the single article. The two `:tags` lines look like dead weight right now; they're load-bearing in Part 4. They name the *facts* each read contains, so a later write can invalidate exactly the reads it broke and no more. Read past them for now — we'll come back and collect on them.

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
   :stale-after-ms 60000
   :tags           (fn [_params data]
                     (into #{[:article-list]}
                           (map (fn [a] [:article (:slug a)]) (:articles data))))}
  (fn [{:keys [page]} _ctx]
    {:request {:method :get
               :url    (str api/api-base "/articles")
               :params {:limit 10 :offset (* 10 (dec (or page 1)))}}
     :decode  :json}))

(rf/reg-resource :conduit/article
  {:params-schema  [:map [:slug :string]]
   :scope          :rf.scope/global
   :stale-after-ms 60000
   :tags           (fn [{:keys [slug]} _data] #{[:article slug]})}
  (fn [{:keys [slug]} _ctx]
    {:request {:method :get
               :url    (str api/api-base "/articles/" slug)}
     :decode  :json}))
```

Four keys carry the whole model, and each one is a decision the framework wants you to make on purpose rather than by accident:

- **`:params-schema`** is the read's *identity*. Every variable that changes the server's answer belongs in params, because params are exactly what the cache keys on. `:conduit/article` with `{:slug "hello"}` and `{:slug "world"}` are two distinct cache entries; that's not configuration, it's just what "identity" means here. (Coming from TanStack Query, this is your `queryKey` — but typed, and validated against the schema.)
- **`:scope`** is an explicit, auditable claim about *who shares the answer*. `:rf.scope/global` says "this read is the same for everyone" — a public article list. Scopes that aren't global are a *leak boundary* (you wouldn't want one user's private feed served to another from a shared cache), and you'll meet those in Part 3.
- **`:tags`** name the facts the data contains. The list tags `[:article-list]` plus one `[:article <slug>]` per article it carries; the detail tags `[:article <slug>]`. Quiet now, decisive in Part 4.
- **`:stale-after-ms`** is the freshness policy. Fresh for a minute, then the next ensure refetches in the background. This is your `staleTime`.

Now delete Part 1's `seed-articles`, the `{:status …}` seed inside `:app/initialise`, and the three `:articles/*` subs. The resource replaces all of them, so `:app/initialise` shrinks to an empty seed. Here's the part that quietly rearranges people's mental furniture the first time they see it: the article data no longer lives in app-db — app-db being your app's single state map — at all. It lives in the framework-owned runtime cache instead. App-db is for *your* state; server reads live in their own keyed, lifecycle-managed store next door.

```clojure
(rf/reg-event :app/initialise
  {:doc "Boot seed. Resources own server data now; app-db starts empty."}
  (fn [_cofx _event] {:db {}}))
```

> **Why isn't the server data in app-db?** Because a cache entry has a *lifecycle* app-db doesn't model: it's fetching, it's stale, it has an in-flight request, it can be garbage-collected when no page is reading it, it can be refetched without you writing a refetch event. Stuffing all that into app-db means hand-rolling it in every app, forever. Resources move the bookkeeping into the runtime — but, crucially, expose it back to you through the *same* subscription shape you already know. You read it; you don't manage it.

## Step 3 — let the routes cause the fetch

A resource doesn't fetch until something *causes* it, and the cleanest cause is the page that needs it. `:resources` is route metadata; add it to the two routes from Part 1 (in `core.cljs`):

```clojure
(rf/reg-route :conduit/home
  {:doc       "The home page: the global article feed."
   :resources [{:resource       :conduit/articles
                :params         (fn [_route] {})
                :blocking?      false
                :keep-previous? true}]}
  "/")

(rf/reg-route :conduit.article/show
  {:doc       "One article, addressed by its slug."
   :params    [:map [:slug :string]]
   :resources [{:resource  :conduit/article
                :params    (fn [route] {:slug (get-in route [:params :slug])})
                :blocking? true}]}
  "/article/:slug")
```

On entry the runtime *ensures* each listed resource — with the **route as owner** — and on leave (or a superseding navigation) it releases them. "Ensures" is the verb to remember: it means *make sure a fresh-enough load exists*, which is a cache hit when one already does and a fetch when it doesn't. The flags are where the per-page judgement lives:

- `:blocking? true` on the article holds the route transition pending until the read settles, so the article page never flashes empty before its data arrives. (It's also the server-side-rendering wait point, when you get there.)
- `:blocking? false` on the home list lets the feed page render immediately and fill in when the list arrives. The page owns its own loading state — a skeleton — rather than making the whole navigation wait.
- `:keep-previous? true` keeps the prior list on screen while a refetch runs, so a refresh never blinks back to a skeleton. This is the difference between a feed that feels alive and one that strobes.

Notice what you *didn't* write: a fetch call. There is no `http-get`, no `then`, no `dispatch [:articles-loaded ...]`. The route *declares* what the page needs, and the runtime owns everything from there to the pixels. The fetch became data.

## Step 4 — read the read, and handle every state it can be in

Views still never touch the cache directly. They read the `:rf.resource/state` subscription — a subscription being a read-only view into state that recomputes when that state changes — which projects one view-model with five statuses:

| `:status` | Meaning | Show |
|---|---|---|
| `:idle` | No load attempted yet | A placeholder |
| `:loading` | First load, no data yet | A skeleton |
| `:fetching` | Refreshing, prior data still visible | The data + a quiet indicator |
| `:loaded` | Usable data present | The data |
| `:error` | First load failed, no data | An error |

Two of these come in a pair worth staring at: `:loading` and `:fetching`. Both mean "a request is in flight," but `:loading` is the *first* load (nothing to show yet — render a skeleton) and `:fetching` is a *refresh over data you already have* (keep showing it; maybe add a subtle "refreshing…" hint). Conflating them is the classic bug where a background refresh tears the screen down to a spinner. Re-frame2 splits them so you don't have to.

And one invariant about failure that's worth pausing on:

!!! warning "`:error` means first-load failure only"

    A failed *background* refresh does not flip the resource to `:error`. It stays `:loaded` with its prior data and records the problem in `:refresh-error`, so users keep reading last-known-good content through a flaky network. Reserve the `:error` branch for the one case where there is genuinely nothing to show yet — a first load that failed. A refresh that fails is a footnote, not a catastrophe.

You won't reach for the raw `:status` keyword much. The derived booleans — `:loading?`, `:fetching?`, `:has-data?`, and friends — exist so a view never has to re-derive these rules by hand. Read the boolean and trust it; the rules above are already baked in.

Rewrite the home page to read the resource and branch on its state. Counting the loaded articles gives you a second axis — *how many* — and that second axis is where the nine states come in:

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

That `cond` is the data-lifecycle slice of a bigger idea. A real page has more render states than a cache entry does — nine of them, and naming them is half the discipline: *Nothing, Loading, Empty, One, Some, Too Many, Incorrect, Correct, Done.* You just built the first handful: Nothing (`:idle`), Loading (the skeleton), the error branch, Empty (loaded, zero articles), and One/Some (loaded, render the list). The point of the list isn't to memorise it — it's that you decided each state *before* shipping, so none of them shows up as a blank screen a user reports a week later.

??? note "Where the other four states live"

    *Too Many* is a pagination cap you'll add in [Paginate a feed](../how-to/paginate-a-feed.md). *Incorrect* and *Correct* are form states from Part 3. *Done* is a domain state from Part 4. The page's render decision stays one expression over the cache entry plus the page's own state. Name all nine up front and you never discover the fifth one in production.

The article page is simpler, because `:blocking? true` guarantees the read has already settled by the time the page renders. There's no `:loading` branch to write — the route waited so you wouldn't have to:

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

`article-preview`, `feed-skeleton`, `feed-error`, and `article-error` are small presentational views. Keep Part 1's `article-preview` and add the three new ones; none of them fetch — they just render the view-model the resource handed them.

## See it move

With the dev build running and Xray open:

1. **Load the home page.** The feed shows a skeleton, then the article list. The route-entry event row in Xray shows the ensure it caused — an event being a plain map describing something that happened. The Resources panel shows the `:conduit/articles` entry walk `:idle → :loading → :loaded`.
2. **Open an article, then press Back and open it again.** The second open is a **cache hit**. The Resources panel shows it served from cache, and there's no new network row in the timeline. You wrote zero caching code; identity — scope + resource + params — is the entire mechanism that makes the second read free.
3. **Break the network** (offline in dev tools, or point `api-base` at a bad host) **and reload.** The first load fails into the `:error` branch and your error view renders — a real failure, owned by a view *you* wrote, not an uncaught promise rejection scrolling past in the console.

Step back and notice there's still just one loop here: events write state, subs read it, views render it. A resource didn't bolt on a second system or a parallel data path. It moved the fetch/cache/staleness bookkeeping *into* the runtime, behind the same subs-and-events shape you already learned in Part 1. New power, same shape — that's the deal re-frame2 keeps making.

---

**You can now:**

- declare a server read as a resource — identity in `:params-schema`, an explicit `:scope`, `:tags` that name its facts, and a freshness policy
- cause the fetch from a route with `:resources` metadata, choosing `:blocking?` and `:keep-previous?` per page
- read a resource passively with `:rf.resource/state` and render its five statuses, with `:error` reserved for first-load failure and `:loading`/`:fetching` kept distinct
- recognise the nine page states and build the data-lifecycle ones from the cache entry plus a count

The full resources model — scopes as leak boundaries, owners vs. causes, the refetch race rules — is in [Server state: resources](../concepts/server-state.md).
