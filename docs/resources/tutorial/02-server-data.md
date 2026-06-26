# Part 2: real data — resources and the nine states

In [Part 1](01-pages-and-state.md) the feed rendered from `seed-articles`. The data was born `:loaded` and never moved, so the `{:status :data :error}` shape looked like overkill — a lot of ceremony for something that never changed. Now the articles come from a real Conduit API, and that `:status` starts earning its keep. The feed genuinely *loads*, then is *loaded*, sometimes comes back *empty*, sometimes *fails*.

By the end of this part the home page fetches the global article list on entry, the article page fetches one article by slug, a second visit is a cache hit with no network, and every render state the feed can be in is a branch *you* chose rather than one that surprised you at 2am in production.

Here is the one sentence to carry through the whole part:

**A server read is a [subscription](../../guide/glossary.md#subscription) you read and a [cause](../glossary.md#owner--cause) you fire — the view never fetches.**

That is the whole trick. The route *causes* the fetch, a subscription *reads* the result, and nothing in your view ever calls the network. We'll build up to it one piece at a time. Let's go.

> **Coming from TanStack Query?** A re-frame2 *[resource](../glossary.md#resource)* is `useQuery`'s keyed, cached, deduplicated read — same idea, with one structural difference you'll feel immediately: the component doesn't fetch on mount. There's no `useQuery(...)` call buried inside the view that quietly kicks off a request the first time React renders it. The *route* causes the fetch; the view only reads what's there. That inversion is the whole point of this part.

> **From re-frame v1.** In v1 a server read was a hand-rolled chain of events: a `:get-articles` event firing an `:http-xhrio` effect, an `:articles-loaded` event to stash the response in app-db, an `:articles-failed` event for the error, and a flag in app-db you flipped to drive a spinner. Every read re-implemented loading, caching, and staleness by hand. re-frame2 makes a server read a *declared* thing — a **resource** — and the runtime owns the fetch/cache/staleness bookkeeping behind the same subscription shape you already know. The four-event boilerplate is gone.

## Step 1 — add the resources artefact and point at an API

Resources ship as their own optional artefact, the way routing did in Part 1 — you only pay for the machinery you use. Add it, plus the managed-HTTP transport it sits on top of (the piece that actually talks to the network). Add both deps and restart `npm run dev`:

```clojure
{:deps {day8/re-frame2              {:local/root "../re-frame2/implementation/core"}
        day8/re-frame2-reagent      {:local/root "../re-frame2/implementation/adapters/reagent"}
        day8/re-frame2-routing      {:local/root "../re-frame2/implementation/routing"}
        day8/re-frame2-resources    {:local/root "../re-frame2/implementation/resources"}}}
```

Now a tiny namespace that says where the API is:

```clojure
;; src/conduit/api.cljs
(ns conduit.api)

(def api-base "https://api.realworld.io/api")
```

The Conduit API answers `GET /articles` with `{:articles [...] :articlesCount N}` and `GET /articles/:slug` with `{:article {...}}`. A resource stores whatever the request decodes — verbatim, no reshaping — so you'll reach into `(:articles data)` and `(:article data)` when you render. The data keeps the shape the server gave it.

One word from Step 1 is worth pinning down before we go further: *transport*. The two deps you added are two layers, on purpose. The transport handles the network — sockets, retries, the raw request — and the resource sits a level above it, describing *what* to read and *how fresh* it must be. The resource never touches a socket; it hands the transport a request and gets a decoded value back.

> **Want to run offline?** Install the in-repo demo stub instead of pointing at the hosted Conduit — see `examples/reagent/realworld_resources/http.cljs` for the canned-response override, which serves the same routes without a network.

> **Gotcha — forgot to require `re-frame.resources`?** `rf/reg-resource` is *late-bound* by the optional artefact — the facade only learns the verb once you `:require` the namespace. Call it before the artefact is loaded and you get a loud `:rf.error/resources-artefact-missing` at registration, not a mystery `nil`. Same shape as routing in Part 1: require the artefact, get the verb.

## Step 2 — declare the two reads

A **[resource](../glossary.md#resource)** is a server read registered once. You describe the read — its identity, its freshness, the request to make — and from then on the runtime owns fetching, caching, and revalidation.

Here are the two reads our app needs. Create `conduit/resources.cljs`:

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
   :gc-after-ms    300000
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
   :gc-after-ms    300000
   :tags           (fn [{:keys [slug]} _data] #{[:article slug]})}
  (fn [{:keys [slug]} _ctx]
    {:request {:method :get
               :url    (str api/api-base "/articles/" slug)}
     :decode  :json}))
```

Two reads, declared. Now let's unpack what you just wrote.

### The shape of a `reg-resource` call

`reg-resource` follows the same **three-slot** shape every `reg-*` registration form in re-frame2 uses — an id, a config map, and a function — `(rf/reg-resource id metadata request-fn)`:

```clojure
(rf/reg-resource <resource-id>     ; 1. the name
  { … metadata … }                 ; 2. the config map (identity, scope, freshness, tags)
  (fn [params ctx] …))             ; 3. the request fn — the THIRD slot, not a metadata key
```

The request fn lives in the **third slot**, not inside the metadata map. This trips people coming from libraries where everything is one options object, so the framework fails loud: put `:request` *inside* the metadata map and you get `:rf.error/invalid-resource-spec` at registration, with a message telling you to move it to the third slot.

### The four keys that carry the model

Most of the config map is optional knobs you reach for later. Four keys carry the core idea, and each is a decision the framework wants you to make on purpose rather than by accident:

- **`:params-schema`** is the read's *identity*. Every variable that changes the server's answer belongs in params, because params are exactly what the cache keys on. `:conduit/article` with `{:slug "hello"}` and `{:slug "world"}` are two distinct cache entries; that's not configuration, it's just what "identity" means here.
- **`:scope`** is an explicit, auditable claim about *who shares the answer*. `:rf.scope/global` says "this read is the same for everyone" — a public article list. A [scope](../glossary.md#scope) that isn't global is a *leak boundary*, and you'll meet those in Part 3.
- **`:stale-after-ms`** is the freshness policy. Fresh for a minute, then the next ensure refetches in the background.
- **`:tags`** name the *facts* the data contains. The two `:tags` lines look like dead weight right now; they're load-bearing in Part 4, where a later write invalidates exactly the reads it broke. Read past them for now — we'll come back and collect on them.

> **Coming from TanStack Query?** Mapping these onto query keys and times: `:params-schema` is your `queryKey` — but typed, and validated against the schema. `:stale-after-ms` is your `staleTime`. `:gc-after-ms` is your `gcTime` / `cacheTime`. And `:poll-interval-ms` (below) is `refetchInterval`, but owner-driven — no component observer kicks it off.

> **Why is `:scope` required, with no default?** Because the cache's leak boundary is too important to infer. A user-scoped read silently registered as global would serve one user's private data to another from a shared cache — a security bug the framework can't lint its way out of after the fact. So you state the intent *once*, at the registration site. A `reg-resource` with no `:scope` is a loud `:rf.error/resource-missing-scope-policy` at registration — "I forgot this read is user-scoped" is unrepresentable rather than a 2am incident. You'll meet the non-global scopes — a `:rf.scope/session` value, the `{:from-db <id>}` named resolver — in Part 3; here, `:rf.scope/global` is the honest answer for a public list.

??? note "The full metadata-key reference"

    **Required** — the framework refuses to register a resource without them:

    | Key | What it is |
    |---|---|
    | **`:params-schema`** | the read's *identity* — a Malli schema for the params the cache keys on |
    | **`:scope`** | the cache's *leak boundary* — `:rf.scope/global`, a `{:from-db <id>}` resolver reference, or `:rf.scope/from-caller` |
    | **`:request`** (3rd slot) | the request fn — `(fn [params ctx] → managed-HTTP args)` |

    **Optional** — each a knob you reach for when you need it:

    | Key | What it does |
    |---|---|
    | **`:stale-after-ms`** | freshness window — fresh for this long, then the next *ensure* refetches in the background. Your `staleTime`. |
    | **`:gc-after-ms`** | how long an entry lingers in cache after its last reader/owner goes away, before garbage collection. Your `gcTime`/`cacheTime`. |
    | **`:poll-interval-ms`** | re-read every N ms while the entry has an active owner and the tab is visible — `refetchInterval`, but owner-driven. Covered in [Server state: resources](../concepts.md). |
    | **`:tags`** | `(fn [params data] → #{tag …})` — names the facts the data contains, so a write can invalidate exactly the reads it broke (Part 4). |
    | **`:data-schema`** | optional Malli schema for the *decoded response*. When present, the data is shape-validated; when absent, it isn't. Validation only — it does **not** drive caching or privacy. |
    | **`:transport`** | which transport the read runs on. The only built-in is `:rf.http/managed`, which is also the default — you'll rarely write this. |
    | **`:doc`** | a docstring the registry and Xray surface. |
    | **`:sensitive` / `:large`** | privacy/size classification — vectors of paths into the entry (e.g. `[[:data :ssn]]`) marking fields to redact or summarise at every egress boundary (trace, Xray, SSR). The whole-entry shorthands are `:sensitive?` / `:large?`. |

    The request fn returns the managed-HTTP args map — `{:request {…} :decode …}` — which is itself the network layer's contract, [Spec 014](../../../spec/014-HTTPRequests.md). You get its other knobs for free here: `:retry`, `:timeout-ms`, `:accept`, request headers. One restriction — the runtime owns reply addressing, so the args map MUST NOT supply `:request-id`, `:on-success`, or `:on-failure`; the resource lowering derives those from the scoped key and current generation. Put one in and it's a loud reject, not a silent override.

Now delete Part 1's `seed-articles`, the `{:status …}` seed inside `:app/initialise`, and the three `:articles/*` subs. The resource replaces all of them, so `:app/initialise` shrinks to an empty seed:

```clojure
(rf/reg-event :app/initialise
  {:doc "Boot seed. Resources own server data now; app-db starts empty."}
  (fn [_cofx _event] {:db {}}))
```

Here's the part that quietly rearranges people's mental furniture the first time they see it: the article data no longer lives in [app-db](../../guide/glossary.md#app-db) — your app's single state map — at all. It lives in **[runtime-db](../../guide/glossary.md#runtime-db)** instead, the framework-owned partition beside app-db (its resource cache is the `:rf.runtime/resources` subsystem there — see [app-db](../../guide/concepts/app-db.md) for [the two-partition model](../../guide/glossary.md#the-two-partitions)). App-db is for *your* state; server reads are a keyed, lifecycle-managed slice the runtime owns next door.

> **Why isn't the server data in app-db?** Because a cache entry has a *lifecycle* app-db doesn't model: it's fetching, it's stale, it has an in-flight request, it can be garbage-collected when no page is reading it, it can be refetched without you writing a refetch event. Stuffing all that into app-db means hand-rolling it in every app, forever. Resources move the bookkeeping into runtime-db — but, crucially, expose it back to you through the *same* subscription shape you already know. You read it; you don't manage it.

## Step 3 — let the routes cause the fetch

We've declared the reads but nothing fetches yet. A resource doesn't fetch until something *causes* it, and the cleanest cause is the page that needs it.

`:resources` is route metadata; add it to the two routes from Part 1 (in `core.cljs`):

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

On entry the runtime *ensures* each listed resource — with the **route as [owner](../glossary.md#owner--cause)** — and on leave (or a superseding navigation) it releases them. "Ensures" is the verb to remember: it means *make sure a fresh-enough load exists*, which is a cache hit when one already does and a fetch when it doesn't.

"Ensure" also covers the third case, the one the intro promised: a *deduplicated* read. If two things ensure the same `{:resource :params}` while a request is already in flight — two route entries racing, a view remounting mid-fetch — the second ensure doesn't fire a second request. It *joins* the one already running and waits for the same reply. (That's `useQuery`'s request deduplication, owner-driven: the identity is the dedupe key.) The flip side is `:rf.resource/refetch` (Step 5), which deliberately *does* force a new request even over an in-flight one — a manual refresh means "I want the latest," not "join whatever's running."

The flags are where the per-page judgement lives:

- `:blocking? true` on the article holds the route transition pending until the read settles, so the article page never flashes empty before its data arrives. (It's also the server-side-rendering wait point, when you get there.)
- `:blocking? false` on the home list lets the feed page render immediately and fill in when the list arrives. The page owns its own loading state — a skeleton — rather than making the whole navigation wait.
- `:keep-previous? true` keeps the prior list on screen while a refetch runs, so a refresh never blinks back to a skeleton. This is the difference between a feed that feels alive and one that strobes.

Notice what you *didn't* write: a fetch call. There is no `http-get`, no `then`, no `dispatch [:articles-loaded ...]`. The route *declares* what the page needs, and the runtime owns everything from there to the pixels. The fetch became data.

> **Gotcha — a blocking read can't hang the server forever.** When `:blocking? true` is the SSR wait point, the render can't sit there indefinitely waiting on a slow upstream. A blocking SSR read that blows its deadline settles as an ordinary first-load failure for that render — its `:error` is `{:kind :rf.http/timeout :reason :ssr-blocking-timeout}`. The `:reason` lets your error view tell an SSR-deadline miss apart from a genuine upstream timeout; the `:kind` stays inside the same `:rf.http/*` taxonomy every resource error uses, so the *same* error branch you write below renders it. Client-side, a blocking read just keeps the transition pending until it settles — no deadline.

> **Coming from TanStack Query?** This is the inversion from the intro made concrete. In a React + TanStack app the `useQuery` call lives *inside* the component, so the fetch is a side-effect of rendering. Here the page-to-data binding lives in the route table, *outside* any view — so you can read the whole app's data dependencies in one place, and a view that renders is guaranteed its data was already asked for.

??? note "The full `:resources` entry reference"

    Each entry in the `:resources` vector takes more keys than the two we used; the full set covers the next pages you'll build:

    | Key | What it does |
    |---|---|
    | **`:resource`** | the registered resource id to ensure. |
    | **`:params`** | `(fn [route] → params)` — pull the read's params out of the matched route (its path params, query, etc.). |
    | **`:scope`** | the scope to ensure under, for a non-global read — usually a `{:from-db <id>}` named-resolver reference (Part 3). Omit it for a `:rf.scope/global` resource. |
    | **`:blocking?`** | hold the transition until this read settles (and the SSR wait point). |
    | **`:keep-previous?`** | keep the prior entry's data visible while the new params first-load (no flicker between pages/filters). |
    | **`:when`** | `(fn [route ctx] → boolean)` — only ensure this read when the predicate holds (skip a read whose params aren't available yet). The clean alternative to ensuring with sentinel `nil` params. |
    | **`:id` / `:after`** | order the ensure-*dispatch* of dependent reads — give an entry a local `:id`, and another `:after #{that-id}` to dispatch it later. (Dispatch order only, not a data waterfall — the params still come from the route, not from the earlier read's data.) |

> **Routes aren't the only cause.** The route is the *cleanest* cause, but an [event](../../guide/glossary.md#event) or a [state machine](../../machines/glossary.md#machine) can ensure a resource too — `[:rf.resource/ensure {:resource … :params … :owner … :cause …}]`. The difference is the **[owner](../glossary.md#owner--cause)**: a route owner is released for you on route leave, while an event-minted owner needs a matching `[:rf.resource/release-owner {:owner …}]` so the entry doesn't get pinned alive forever. For "fetch this when the page is showing," the route is exactly right and frees you from the bookkeeping.

## Step 4 — read the read, and handle every state it can be in

The data is fetching. Now the view reads it — passively, the same way it would read anything else. Views still never touch the cache directly. They read the `:rf.resource/state` subscription — a [subscription](../../guide/glossary.md#subscription) being a read-only view into state that recomputes when that state changes — and what it hands back is a single ready-to-render map: the data, plus everything the view needs to know about *how* it's doing (loading, fetching, errored, stale).

Here's the rewritten home page. Read the resource, branch on its state:

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

That `cond` is the heart of this step. Each branch handles one state the feed can genuinely be in. Let's look at what the subscription handed you.

### The view-model: one fixed map

`:rf.resource/state` returns a fixed map; here's every key it carries:

```clojure
{:status        :idle | :loading | :fetching | :loaded | :error
 :data          <last-known-good-or-nil>     ;; the decoded response
 :error         <first-load-error-or-nil>    ;; only set on a failed FIRST load
 :refresh-error <background-refresh-error-or-nil>
 :loading?      <bool>   ;; first load, nothing to show yet
 :fetching?     <bool>   ;; refreshing over data you already have
 :stale?        <bool>   ;; past its :stale-after-ms window (orthogonal to status)
 :has-data?     <bool>   ;; usable :data is present
 :previous?     <bool>}  ;; :keep-previous? is showing the prior key's data
```

The five `:status` values are the heart of it:

| `:status` | Meaning | Show |
|---|---|---|
| `:idle` | No load attempted yet | A placeholder |
| `:loading` | First load, no data yet | A skeleton |
| `:fetching` | Refreshing, prior data still visible | The data + a quiet indicator |
| `:loaded` | Usable data present | The data |
| `:error` | First load failed, no data | An error |

Two of these come in a pair worth staring at: `:loading` and `:fetching`. Both mean "a request is in flight," but `:loading` is the *first* load (nothing to show yet — render a skeleton) and `:fetching` is a *refresh over data you already have* (keep showing it; maybe add a subtle "refreshing…" hint). Conflating them is the classic bug where a background refresh tears the screen down to a spinner. re-frame2 splits them so you don't have to.

You won't reach for the raw `:status` keyword much. The derived booleans — `:loading?`, `:fetching?`, `:has-data?`, and friends — exist so a view never has to re-derive these rules by hand. Read the boolean and trust it; the rules are already baked in. That's why the `cond` above branches on `(:loading? state)` and `(:fetching? state)`, not on `(= :loading (:status state))`.

> **Don't want the whole map?** `:rf.resource/state` is the workhorse, but there's a narrower sub for each field, taking the same `{:resource … :params …}` payload: `:rf.resource/data`, `:rf.resource/status`, `:rf.resource/loading?`, `:rf.resource/fetching?`, `:rf.resource/stale?`, `:rf.resource/error`, `:rf.resource/refresh-error`, `:rf.resource/has-data?`, and `:rf.resource/previous-data`. A view that only needs the data reads `[:rf.resource/data {:resource … :params …}]` and re-renders only when *that* changes. They're projections of the same entry — pick the narrowest read the view actually uses.

### Failure: `:error` is for first-load only

One invariant about failure is worth pausing on:

!!! warning "`:error` means first-load failure only"

    A failed *background* refresh does not flip the resource to `:error`. It stays `:loaded` with its prior data and records the problem in `:refresh-error`, so users keep reading last-known-good content through a flaky network. Reserve the `:error` branch for the one case where there is genuinely nothing to show yet — a first load that failed. A refresh that fails is a footnote, not a catastrophe.

The two error channels carry the **same envelope** — the closed [Spec 014](../../../spec/014-HTTPRequests.md) HTTP-failure shape — so you can render either with the same view. A first-load failure looks like:

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

That `:kind` is one of a closed `:rf.http/*` taxonomy (`:rf.http/http-4xx`, `:rf.http/http-5xx`, `:rf.http/transport`, `:rf.http/timeout`, `:rf.http/decode-failure`, …), so an error view can branch on the *kind* of failure rather than parsing a string.

### The nine states

That `cond` in the home page is the data-lifecycle slice of a bigger idea. A real page has more render states than a cache entry does — nine of them, and naming them is half the discipline: *Nothing, Loading, Empty, One, Some, Too Many, Incorrect, Correct, Done.*

You just built the first handful: Nothing (`:idle`), Loading (the skeleton), the error branch, Empty (loaded, zero articles), and One/Some (loaded, render the list). The point of the list isn't to memorise it — it's that you decided each state *before* shipping, so none of them shows up as a blank screen a user reports a week later.

??? note "Where the other four states live"

    *Too Many* is a pagination cap you'll add in [Paginate a feed](../how-to/paginate-a-feed.md). *Incorrect* and *Correct* are form states from Part 3. *Done* is a domain state from Part 4. The page's render decision stays one expression over the cache entry plus the page's own state. Name all nine up front and you never discover the fifth one in production.

### The article page

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

> **Gotcha — the params must match exactly (this one bites everyone once).** A subscription is keyed by `{:resource … :params …}`, and a resource with `{:slug "hello"}` is a *different cache entry* from one with `{:slug "world"}` — that's identity working as designed. But it means if the route ensures under `{:slug slug}` and your view subscribes with `{:slug (str slug)}` or forgets a params key, the sub resolves a *different* entry — one nobody ever ensured — and reads `:idle` **forever**: a permanent skeleton with no error in the console. Subscribe with the exact same params the route ensured. (On a dev build the framework helps: for a non-global, caller-supplied scope it emits a `:rf.warning/resource-sub-scope-mismatch` trace when a sub lands on an un-owned key while a sibling key *is* active — your tip that the two scopes drifted apart.)

## Step 5 — refresh on demand

The route causes the *first* fetch, and `:stale-after-ms` causes background refreshes on its own. When you want a user-triggered refresh — a "↻" button on the feed — dispatch `:rf.resource/refetch` with the same identity and a `:cause` for the trace:

```clojure
;; in an event handler, or straight from a button's on-click
(rf/dispatch [:rf.resource/refetch
              {:resource :conduit/articles
               :params   {}
               :cause    [:manual :feed/refresh]}])
```

Because the prior data is still there, the entry goes to `:fetching` (not `:loading`), so the feed keeps showing while the new list arrives — your `:fetching?` branch lights up the "Refreshing…" hint and nothing blinks. Note the `:cause`: it's pure trace/diagnostic metadata (it answers *why* in [Xray](../../guide/glossary.md#xray)) and, unlike an `:owner`, it doesn't keep the entry alive — a one-shot refresh shouldn't pin a cache entry.

## See it move

With the dev build running and Xray open:

1. **Load the home page.** The feed shows a skeleton, then the article list. The route-entry event row in Xray shows the ensure it caused — an [event](../../guide/glossary.md#event) being an inert data vector recording that something happened. The Resources panel shows the `:conduit/articles` entry walk `:idle → :loading → :loaded`.
2. **Open an article, then press Back and open it again.** The second open is a **cache hit**. The Resources panel shows it served from cache, and there's no new network row in the timeline. You wrote zero caching code; identity — scope + resource + params — is the entire mechanism that makes the second read free.
3. **Wait a minute, then revisit the home page.** The list is now past its `:stale-after-ms` window, so the route entry ensures it into `:fetching` — the old list stays on screen (you set `:keep-previous? true`) while a quiet background refetch runs. Stale-while-revalidate, declared in one number.
4. **Break the network** (offline in dev tools, or point `api-base` at a bad host) **and reload.** The first load fails into the `:error` branch and your error view renders — a real failure, owned by a view *you* wrote, not an uncaught promise rejection scrolling past in the console.

Step back and notice there's still just one loop here: events write state, subs read it, views render it. A resource didn't bolt on a second system or a parallel data path. It moved the fetch/cache/staleness bookkeeping *into* the runtime, behind the same subs-and-events shape you already learned in Part 1. New power, same shape — that's the deal re-frame2 keeps making.

> **Going deeper.** Why does this stay "one loop" rather than becoming a second data path bolted on the side? Because a resource entry is a *value* — an immutable view-model projected from the runtime cache — and your view is a pure function of that value. The cache's mutation (fetch, settle, expire, GC) happens entirely inside the runtime; what crosses the boundary into your code is always a fresh immutable snapshot. So the substitution model you rely on for app-db subscriptions holds unchanged: same input value, same rendered output, every time. The lifecycle complexity is real, but it's *encapsulated* — it never leaks into the referential transparency your views depend on. That's the algebraic reason "new power, same shape" isn't just a slogan: the resource is a new *source* feeding the same pure reduction, not a new kind of computation.

The full resources model — scopes as leak boundaries, owners vs. causes, polling, the refetch race rules — is in [Server state: resources](../concepts.md). The normative contract is [Spec 016 — Resources](../../../spec/016-Resources.md).
