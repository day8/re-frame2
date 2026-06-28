# Paginate a feed

You have a list with more rows than you want to fetch at once. There are two pagination shapes you actually ship, and they behave differently *on purpose*:

- **Numbered pages** — page 2 *replaces* page 1 on screen. Think search results, an admin table, a "1 2 3 … 29" pager.
- **Load more** — page 2 *appends* to what's already there, the way a social feed grows.

Both ride on [**resources**](../glossary.md#resource). A resource is a declared, cached server-state read: you register it once with `reg-resource`, and from then on the framework owns the fetching, caching, and freshness — [views](../../core/glossary.md#view) just subscribe to its current state. [Server state: resources](../concepts.md) is the full introduction; this page assumes you've met them. We'll build the numbered shape first, then load-more. By the end you'll have both wired with *no pagination state in [app-db](../../core/glossary.md#app-db) at all* — no page-number slice, no `:loading-more?` flag, no cursor, no append handler. That's the headline: the page cursor and the page cache are all framework-owned [runtime-db](../../core/glossary.md#runtime-db), so pagination adds *nothing* to the state you own. (Remember [the two partitions](../../core/glossary.md#the-two-partitions): app-db is yours, runtime-db is the framework's.)

> **The one idea to hold onto.** A numbered page is part of the resource's *identity* — page 7 is its own separately-cached value. An infinite feed is *one* identity that *grows* — page 1, then 1+2, then 1+2+3, kept together as a single reactive value. Almost everything below falls out of that one distinction.

> **Coming from TanStack Query?** You already know both halves; you just don't know their re-frame2 names yet. Numbered pages are `useQuery` with the page in the `queryKey`, plus `keepPreviousData`. Load-more is `useInfiniteQuery`. They map over nearly one-to-one. The one deliberate divergence — stated up front because it's the bit that feels unfamiliar — is that the next-page cursor is advanced by a *causal [event](../../core/glossary.md#event)*, not by a `fetchNextPage()` call from inside a component. That's the same passive-views rule the whole framework runs on: views read; events change the world.

## Numbered pages

The whole trick for numbered pages: **the page number is part of the resource's identity, and lives in the URL.** Three small steps get you there.

### 1. Put the page in the resource's params

Here's the one rule that makes resources work: every variable that changes the server's answer belongs in `:params`. The page is one of those variables — page 1 and page 2 are two different answers to two different questions — so they become two distinct cache entries under one resource:

```clojure
;; Adapted from examples/real-apps/realworld_resources/resources.cljs
(def page-size 10)

(rf/reg-resource :app/articles
  {:params-schema [:map [:page :int]]
   :scope         :rf.scope/global}
  (fn [{:keys [page]} _ctx]
    {:request {:method :get
               :url    "/api/articles"
               :params {:limit  page-size
                        :offset (* page-size (dec page))}}
     :decode  :json}))
```

Every `reg-resource` requires exactly three things, and they're all here: `:params-schema` (the resource's *identity* — which inputs distinguish one cached answer from another; the page is one of them), [`:scope`](../glossary.md#scope) (the cache's visibility boundary — more on that in the gotcha below), and the request function (the last argument, returning the HTTP call). Leave any one out and registration [fails loud](../../core/glossary.md#fail-loud-not-silent). The server replies `{:articles [...] :total 290}` — adapt the field names to yours.

That's the minimum. A real list usually tunes a few more optional keys:

```clojure
(rf/reg-resource :app/articles
  {:params-schema  [:map [:page :int]]
   :scope          :rf.scope/global
   :stale-after-ms 60000               ;; revalidate after a minute
   :gc-after-ms    (* 5 60 1000)       ;; drop unowned pages after 5 min
   :tags           (fn [_params _data] #{[:article-list]})}  ;; for invalidation
  (fn [{:keys [page]} _ctx]
    {:request {:method :get
               :url    "/api/articles"
               :params {:limit page-size :offset (* page-size (dec page))}}
     :decode  :json}))
```

`:stale-after-ms` / `:gc-after-ms` tune the staleness and garbage-collection clocks. `:tags` is the handle a write elsewhere uses to [invalidate](../glossary.md#invalidate) this list — see [Invalidate after a mutation](invalidate-after-a-mutation.md). (You could also add a `:data-schema` to [shape-validate](../../core/glossary.md#schema) each page, but a paginated list rarely needs one.)

> **Gotcha — `:scope` is required, even for a public list.** There's no implicit global default — a `reg-resource` with no `:scope` is a loud registration error (`:rf.error/resource-missing-scope-policy`), not a silent shared read. "I forgot this read is user-scoped" is made unrepresentable at the door. The articles list is the same for every viewer, so it states `:scope :rf.scope/global` outright. A *per-user* list — "my drafts", a tenant-scoped table — would carry a scope resolver instead, so page 2 of tenant A and page 2 of tenant B never collide in the cache. Pagination doesn't move that boundary.

> **From re-frame v1.** The page-keyed cache map and the staleness clocks are framework state, not handler code. Your app-db holds none of it.

### 2. Let the URL carry the page

Quick question: where should the *current page number* live? It's tempting to drop it into app-db — but the page number is really telling you *where the user is*, and "where the user is" is the URL's job. Put it in the URL and you get shareable links, working Back/Forward, and a reload that lands on the same page, all for free. (This is the [routing](../../routing/concepts.md) ethos in one move: the URL is an input, not a thing you sync.)

The [route](../../routing/glossary.md#route) validates the `?page=` query param, feeds it into the resource's params, and opts into keeping the old page visible while the new one loads:

```clojure
;; Adapted from examples/real-apps/realworld_resources/routing.cljs
(rf/reg-route :app/home
  {:query     [:map [:page {:optional true} :int]]
   :scroll    :top
   :resources [{:resource       :app/articles
                :params         (fn [route] {:page (or (get-in route [:query :page]) 1)})
                :blocking?      true
                :keep-previous? true}]}
  "/")
```

A word on *owning*, since it's the mechanism doing the cleanup. A cached entry lives as long as something [owns](../concepts.md) it — holds a lease — and falls back to normal staleness/GC once unowned. Here route entry takes the lease, so the page you're looking at can't be garbage-collected out from under you; route leave drops it. Nothing leaks, and you wrote no cleanup code to make that true. (You'll see *owner* again later, paired with *cause*, when we get to load-more: owner = lifetime, cause = explanation.)

Each `:resources` entry is a small declaration. The four keys above:

- `:resource` names the registered resource;
- `:params` is a pure `(fn [route] …)` computing its params from the route match;
- `:blocking?` keeps the route transition pending (and gives [SSR](../../ssr/glossary.md#ssr) a wait point) until this resource's first load lands;
- `:keep-previous?` is the no-flicker key from step 4.

Two more keys earn their place on real tables:

- **`:when`** — a `(fn [route _ctx] …)` predicate; the resource is only ensured when it returns truthy. Use it for a list that should only load under a condition (a search that waits for a non-empty `?q=`) rather than ensuring with sentinel-`nil` params.
- **`:scope`** — for a *scoped* list, a named-resolver reference like `{:from-db :app/session}` so the route ensures under the same principal the view subscribes under. (A global list omits it; the resource's own `:scope :rf.scope/global` resolves sub-side too.)

> **Gotcha — both seams must compute the same key.** Params identity is *exact*. `{:page nil}` and `{:page 1}` are different cache entries. If a view subscribes under one *params* key while the route ensured the other, the view reads `:idle` forever — a miserable bug to chase, because everything *looks* wired up. So normalise the same way everywhere: `(or page 1)` on the route side (above) and on the sub side (next step). Same fallback, both seams.

> **Gotcha — a scoped list's *scope* must match too, and it fails differently.** The same-key rule has a second half that only bites once you go scoped (step 2's `:scope {:from-db :app/session}` list, or the session-scoped feed below). A subscription resolves its own scope — and the failure modes are *not* the silent `:idle` of a params slip. A sub that **can't resolve a scope at all** (a `{:from-db …}` resolver that returns `nil` because the user is logged out, say) is a loud, structured `:rf.error/resource-sub-unresolved-scope` carrying the resource id — never a silent shared-cache read and never a silent skeleton. A sub that resolves a *valid but wrong* scope (it ensured under one principal, the view subscribed under another) does land on `:idle` forever — but in dev the framework spots it for you and emits `:rf.warning/resource-sub-scope-mismatch`, naming the resolved sub-scope, the active scope the read likely meant, and the fix (pass the same `:scope` the owning route/event ensured under). So the cure is the same — make both seams resolve the same scope, by pointing registration, route, and sub at one named resolver (`{:from-db :app/session}`) — but you're not chasing it blind. (A *global* list sidesteps all of this: `:rf.scope/global` resolves identically sub-side and ensure-side, so there's no scope to mismatch.)

### 3. Page by navigating, not by fetching

Here's the mental shift, and it's the whole numbered-pages trick: **changing pages is a [navigation](../../routing/glossary.md#navigate), not a fetch.** You swap `?page=` in the URL and the route does the rest. Drop the param entirely for page 1, so the first page has one canonical URL rather than `/` and `/?page=1` both pointing at the same list:

```clojure
(rf/reg-event :home/go-to-page
  (fn [_ [_ page]]
    {:fx [[:dispatch [:rf.route/navigate :app/home {}
                      {:query (if (> page 1) {:page page} {})}]]]}))

(rf/reg-sub :home/page
  :<- [:rf.route/query]
  (fn [q _] (or (:page q) 1)))
```

Notice the event has *no fetch in it* — no HTTP, no resource call. It just navigates. The route declaration from step 2 turns that navigation into the right `ensure`. That's the seam doing its job, and it's why the [event handler](../../core/glossary.md#event-handler) stays a pure function returning a tiny [effect map](../../core/glossary.md#effect-map).

> **Gotcha — navigate takes three args.** It's `(navigate target path-params opts)`: target, then path-params `{}`, then the opts map carrying `:query`. The opts always go in the *third* slot — dropping a `{:query …}` map into the second (params) slot is the classic mistake the router rejects with `:rf.error/navigate-arity-misuse`.

A filter — a tag, a search term — is just one more params key and one more query param. Carry it across page changes with the route's `:query-retain` (a set of query keys the router threads through subsequent navigations even when the caller didn't supply them). But reset to page 1 when the *filter* changes, because a new filter is a fresh list, and "page 2 of the old filter" means nothing. That reset is just a navigation that drops `:page` while setting the new filter:

```clojure
(rf/reg-event :home/set-tag
  (fn [_ [_ tag]]
    ;; New filter ⇒ fresh list ⇒ back to page 1: set :tag, DROP :page.
    {:fx [[:dispatch [:rf.route/navigate :app/home {}
                      {:query (cond-> {} tag (assoc :tag tag))}]]]}))
```

The filter then joins the resource's `:params-schema` and the route's `:query` schema, and the route's `:params` fn threads it through alongside the page — one more key on each of the two seams, no new machinery.

### 4. Show the old page while the new one loads

This is the part that makes pagination feel smooth instead of janky. With `:keep-previous?`, while page 2 is first-loading, its state carries `:previous? true` and `:previous-data`. That `:previous-data` is page 1's rows shown *through* page 2's loading state — borrowed for display only, never copied into page 2's own cache entry, so when page 2's real data lands it isn't polluted. Render those borrowed rows instead of a blank skeleton:

```clojure
;; Adapted from examples/real-apps/realworld_resources/views.cljs
(rf/reg-view article-list []
  (let [page  @(subscribe [:home/page])
        state @(subscribe [:rf.resource/state
                           {:resource :app/articles :params {:page page}}])]
    (cond
      (and (:loading? state) (not (:previous? state)))
      [list-skeleton]

      (and (:error state) (not (:has-data? state)) (not (:previous? state)))
      [list-error (:error state)]

      :else
      (let [{:keys [articles total]} (or (:data state) (:previous-data state))
            pages (js/Math.ceil (/ (or total 0) page-size))]
        [:div
         (when (:previous? state) [:p "Loading page " page "…"])
         (into [:div] (for [a articles] [article-row a]))
         (when (> pages 1)
           (into [:nav]
                 (for [p (range 1 (inc pages))]
                   [:a {:href "#" :class (when (= p page) "active")
                        :on-click #(do (.preventDefault %)
                                       (dispatch [:home/go-to-page p]))}
                    p])))]))))
```

(Here `dispatch` and `subscribe` are bindings that `reg-view` injects into the view's body, already wired to the [frame](../../core/glossary.md#frame) this view is rendering in — the same `dispatch`/`subscribe` you'd reach for via `rf/`, just pre-targeted. `list-skeleton`, `list-error`, and `article-row` are your own views.)

Now watch it work. Click through to page 2 with [Xray](../../core/glossary.md#xray) open: the navigation event row shows the `ensure` it caused under the `{:page 2}` key, and the entry walks `:loading` → `:loaded`. Click *back* to page 1 and you'll see the same `{:page 1}` key, still fresh — a cache hit, no network request. That's the payoff of treating pages as identity: back-navigation is free, because you never threw page 1 away. You just stopped looking at it.

The `:rf.resource/state` [subscription](../../core/glossary.md#subscription) you read here is the same one every resource exposes — pagination just leans on a few of its keys. The full shape is worth knowing once:

```clojure
@(subscribe [:rf.resource/state {:resource :app/articles :params {:page 2}}])
;; =>
{:status        :loading       ;; :idle | :loading | :fetching | :loaded | :error
 :data          nil            ;; this page's data once its own request lands
 :loading?      true           ;; first load, no usable data yet
 :fetching?     false          ;; a background refresh while data stays visible
 :stale?        false
 :has-data?     false
 :error         nil            ;; first-load failure (no usable data)
 :refresh-error nil            ;; a background-refresh failure, prior data kept
 :previous?     true           ;; :keep-previous? — page 1 is showing through
 :previous-key  [scope :app/articles {:page 1}]
 :previous-data {:articles [...] :total 290}}
```

If you'd rather subscribe to one fact than the whole map, the family splits into single-key subs — `:rf.resource/data`, `:rf.resource/status`, `:rf.resource/loading?`, `:rf.resource/fetching?`, `:rf.resource/stale?`, `:rf.resource/error`, `:rf.resource/refresh-error`, `:rf.resource/has-data?`, and `:rf.resource/previous-data` — each taking the same `{:resource … :params …}` map. Read one or read the bundle; same underlying entry.

> **Gotcha — two error channels, not one.** This trips people the first time. `:error` is **first-load only**. Click back to a page you visited a while ago and its entry has gone stale: the framework revalidates in the background, the page stays `:loaded` with its old rows on screen, and the status moves to `:fetching` (not `:loading`). If *that* refresh fails, the failure lands on `:refresh-error` and the data **stays visible** — the list does *not* collapse to an error screen. So your full error branch (above) gates on `(:error state)` *and* `(not (:has-data? state))`, while a "couldn't refresh — showing cached" banner gates on `(:refresh-error state)`. Two channels, two affordances; conflate them and you either hide refresh failures or blank a perfectly good list.

> **From re-frame v1.** The page-keyed cache map, the `:loading?` flags, the "don't blank the list while fetching" dance — all the framework's job. The whole handler-and-flags apparatus is four declarations and a `cond`.

> **Going deeper.** Why is back-navigation free? Because a numbered page is *referentially identified* by its params: `{:page 1}` names exactly one value, and that value is content-addressed in the cache. Re-visiting the same params is a pure lookup — no request, no recomputation. It's the same property that makes a memoised pure function cheap to re-call: identity in, cached value out. The cache *is* a memoisation table keyed by the resource's identity, and the page number is just one coordinate of that key.

## Load more: an infinite resource is one growing entry

A load-more feed is a *different* shape, so it's worth a minute on the model before you reach for it. What's on screen is no longer "the server's page N". It's *everything this user has accumulated so far*: page 1, then 1+2, then 1+2+3, rendered as one growing list. re-frame2 models that as a first-class **infinite resource** — *one* cache entry whose value is an ordered, growing sequence of pages, with the *next* page's cursor derived from the *last* page you loaded.

> **Going deeper — one entry, not N.** An infinite feed is *one* identity (the filter/sort), and its value is the *accumulation* — not a scatter of per-page keyed entries you concatenate in a sub. Keeping the pages together as a single entry means the merged list and the next cursor are one reactive value with one owner, one GC clock, one SSR-restore unit. The accumulation is a fold — `load-more` is the step that conj's the next page onto the sequence — and the cursor is derived from the fold's current tail. Modelling it as one growing value lets the runtime own structural sharing, invalidation, and restore for the whole feed at once.

### 1. Register the feed with `:infinite true`

An infinite resource is an *ordinary* resource — identity, scope, request — plus two additions: `:infinite true` (which makes `:next-page-param` required), and a pure `:next-page-param` function that, given the last page you loaded, returns the cursor for the *next* one.

The thing to internalise: the page cursor is **not** a params key. If it were, every page would be its own cache entry (the numbered case) — but a feed is *one* growing entry, so the cursor can't be part of its identity. Instead it's internal sequencing state the runtime tracks for you and hands to your request function as a *second argument* (the request function below takes `(params ctx)` — `ctx` is where the current page's cursor arrives):

```clojure
;; Adapted from examples/capabilities/resources/infinite_feed/core.cljs
(def page-size 8)

(rf/reg-resource :feed/timeline
  {:infinite       true

   ;; The feed-IDENTITY params (filter / sort / search) — what makes two feeds
   ;; distinct cache instances. The per-page cursor is NOT here. (This demo
   ;; feed is a single public timeline, so the identity is empty.)
   :params-schema  [:map]
   :scope          :rf.scope/global

   ;; Derive the NEXT page param from the last loaded page. Returning nil is the
   ;; SINGLE terminal signal (no more pages); :has-next-page? is then false.
   :next-page-param
   (fn [last-page _all-pages]
     (get-in last-page [:page-info :next-cursor]))   ;; nil ⇒ end of feed

   ;; The pages are ENVELOPED ({:items [...] :page-info {…}}), so the runtime
   ;; needs this accessor to flatten each page into the merged list (below).
   :page->items    :items

   :stale-after-ms 60000
   :gc-after-ms    (* 5 60 1000)}

  ;; The request fn keeps its (params ctx) shape; for an infinite resource the
  ;; RESERVED ctx (its second arg) carries THIS page's context — the resolved
  ;; cursor (nil for the first page) and the page index.
  (fn [_feed-params {:rf.resource/keys [page-param page-index]}]
    {:request {:method :get
               :url    "/api/timeline"
               :params (cond-> {:limit page-size :page-index page-index}
                         page-param (assoc :cursor page-param))}
     :decode  :json}))
```

A few things just happened, so let's name them:

- The **first page** is fetched with `:page-param nil` (override it with `:initial-page-param` if your API's first page wants a real cursor) and `:page-index 0`. Each load-more passes the cursor your `:next-page-param` derived from the tail.
- **Two `load-more` calls don't make two cache keys** — they extend *one* entry. Only the identity params (filter, sort, search) name the feed. Change those and you get a different feed instance; the per-page cursor never touches the cache key.

Beyond `:next-page-param` (required) and `:page->items`, an infinite resource also accepts a handful of optional keys you'll reach for as feeds get real:

- `:prev-page-param` — the bidirectional mirror, for prepending older pages (see the prepend callout at the end).
- `:initial-page-param` — the first page's cursor (default `nil`).
- `:refetch` — the refetch-window policy (see [Refetch and reset](#refetch-and-reset)).
- `:page-data-schema` — a schema that validates **one page** (the thing your request decodes). This is the grain that matters: the resource's accumulated `:data` is the *sequence* of pages, so a whole-feed `:data-schema` would be wrong — you want to validate (and, where a page carries sensitive fields, [classify on egress](../../core/glossary.md#data-classification)) one page at a time. That's why it's `:page-data-schema`, not `:data-schema`.

> **Coming from TanStack Query?** Map it straight across: `:next-page-param` is `getNextPageParam`, `:initial-page-param` is `initialPageParam`, `:page->items` is the accessor you'd write inline when flattening `data.pages`. The first page's `nil` param is TanStack's defaulted `initialPageParam`. The one re-frame2 addition: a derived `:has-next-page?` so a view never re-derives the terminal itself.

> **Gotcha — two ways to register a feed wrong, both loud at registration.** `:infinite true` with **no `:next-page-param`** is a hard error (`:rf.error/infinite-missing-next-page-param`) — there's no way to guess the next cursor, so the framework refuses a feed that can't say where its next page is. The second: a feed whose page is an *envelope* (`{:items [...] :page-info {…}}`) **must** declare `:page->items`, or the runtime raises `:rf.error/infinite-missing-page-accessor` at the merge rather than silently flattening the wrong key. (If a page is *already a vector*, it flattens by identity and you need no accessor.) Loud-over-magic is a recurring re-frame2 stance, and this is one of its sharper edges.

### 2. Let the route own the feed (it ensures page 0)

A route declares an infinite resource exactly as it declares any other. Route entry ensures **page 0** — the first load only, not the whole future accumulation — under the route's owner; route leave releases it:

```clojure
(rf/reg-route :app/timeline
  {:resources
   [{:resource  :feed/timeline
     :params    (fn [_route] {})
     :blocking? true}]}        ;; :blocking? blocks on page 0 only
  "/timeline")
```

The same `:resources` entry keys apply — `:when`, `:scope`, `:params` — with one wrinkle to internalise: for an infinite feed, `:blocking?` and route ownership concern **page 0 only**. Load-more is a user-caused event *during* the route's lifetime, not a step in the route's load plan, so the route blocks on the first page and then gets out of the way.

### 3. Read the merged list passively; load-more is a causal event

The view reads the combined `[:rf.resource/infinite-state …]` subscription and dispatches one event. It never fetches and never advances a cursor — it *can't*, and that's the point:

```clojure
(rf/reg-view timeline-feed []
  (let [feed @(subscribe [:rf.resource/infinite-state
                          {:resource :feed/timeline :params {}}])]
    (cond
      ;; First load (page 0), no usable data yet.
      (:loading? feed) [feed-skeleton]

      ;; First load failed with no data. Split the full error screen from the
      ;; inline retry by :has-data?.
      (and (:page-error feed) (not (:has-data? feed)))
      [feed-error (:page-error feed)]

      :else
      [:<>
       ;; :items is the merged flat list — the headline read. The runtime
       ;; concatenates pages (via :page->items) and memoises the merge.
       (into [:div] (for [item (:items feed)]
                      ^{:key (:id item)} [feed-row item]))

       ;; A load-more failure keeps every page visible — the inline retry.
       (when (and (:page-error feed) (:has-data? feed))
         [load-more-error (:page-error feed)])

       (cond
         (:fetching-next? feed) [spinner]              ;; a load-more in flight

         (:has-next-page? feed)
         [:button {:on-click #(dispatch [:rf.resource/load-more
                                         {:resource :feed/timeline :params {}
                                          :cause    [:user :feed/load-more]}])}
          "Load more"]

         :else [end-of-feed])])))      ;; nil next-param ⇒ no more pages
```

Read that view-model and you have the entire feed UI in four keys:

- **`:items`** — the merged flat list, the headline read.
- **`:has-next-page?`** — show the button or the end marker.
- **`:fetching-next?`** — a load-more in flight (distinct from `:fetching?`, which is a *whole-feed* refresh).
- **`:page-error`** — a load-more failure.

The accumulated pages stay visible right through a load-more — no skeleton flash — because the feed already has data; the spinner is just the next page arriving.

That's the four you'll use every time, but `:rf.resource/infinite-state` carries a few more for the cases where they earn their place:

```clojure
@(subscribe [:rf.resource/infinite-state {:resource :feed/timeline :params {}}])
;; =>
{:status         :loaded
 :items          [<item> <item> …]   ;; merged flat list — the headline read
 :pages          [<page-0> <page-1> …] ;; raw page boundaries (for per-page headers / dividers)
 :page-count     2
 :has-next-page? true
 :has-prev-page? false               ;; bidirectional only (load-prev deferred — see below)
 :loading?       false               ;; first load (page 0), no data yet
 :fetching-next? false               ;; a load-more in flight (pages stay visible)
 :fetching?      false               ;; a WHOLE-feed refresh in flight
 :stale?         false
 :has-data?      true
 :error          nil                 ;; page-0 first-load failure
 :refresh-error  nil                 ;; whole-feed refresh failure
 :page-error     nil}                ;; last load-more (page N>0) failure
```

A couple of these are worth a sentence. `:pages` is the *raw* page sequence — reach for it when you need page boundaries, like a "—— more from earlier ——" divider or per-page section headers, rather than the flattened `:items`. `:page-count` is just `(count pages)`. And note the *three* error channels a feed can show, each a different situation: `:error` is a page-0 first-load failure with nothing on screen yet (the full error branch above); `:page-error` is a load-more failure that keeps every accumulated page visible (the inline retry); `:refresh-error` is a whole-feed background refresh that failed while the data stayed put. They never overlap; each maps to its own affordance.

Every single-key sub from the numbered family applies to a feed too — but `:rf.resource/data` returns the *raw page vector*, which is rarely what you want, so the infinite family adds `:rf.resource/items`, `:rf.resource/pages`, `:rf.resource/has-next-page?`, `:rf.resource/has-prev-page?`, `:rf.resource/fetching-next?`, `:rf.resource/page-count`, and `:rf.resource/page-error` for the reads a feed actually makes.

> **Gotcha — `load-more` carries a `:cause`, not an `:owner`.** The route already *owns* the feed for its whole lifetime, which is what keeps it alive. A load-more *extends* that one owned entry — it isn't trying to keep anything alive on its own, so it omits `:owner` and supplies only `:cause` (owner keeps alive; cause explains why). Pass an `:owner` anyway and the runtime warns (`:rf.warning/resource-load-more-owner-ignored`) and ignores it — the page still appends — rather than minting a stray lease that would pin the feed open past its real owner.

> **Going deeper — the runtime is the guard.** Four edge behaviours mean you can wire the button straight to `dispatch` with no `if-has-next` and no in-flight flag around it. A `load-more` when the feed *has* a next page transitions it to `:fetching` (refresh-class, since it already has data), keeps the accumulated pages visible, and appends on success with structural sharing. A `load-more` when `:next-page-param` is already `nil` is a **no-op** — no request fires (Xray shows `:rf.resource/load-more-skipped`, `:reason :no-next-page`). A *second* `load-more` while one is in flight **dedupes** against the in-flight work (`:reason :in-flight`). And a page fetch that *fails* keeps every accumulated page and records `:page-error` rather than collapsing the feed. The runtime is the guard.

### What the runtime does that you no longer write

This is the load-bearing payoff. Itemised, here's what just disappeared from your codebase:

- **The cursor.** `:next-page-param` derives the next page's param from the loaded tail; the runtime stores it on the entry and passes it to the next `:request`. You never thread a cursor through app-db.
- **The append.** A page success appends to the one entry's page vector with structural sharing — prior pages stay *identical* (`=` and `identical?`). You write no append handler.
- **The in-flight UI.** `:fetching-next?` is true while a load-more page is fetching; a second load-more while one is in flight dedupes (no double-fetch). You keep no `:loading-more?` flag.
- **The terminal.** `:next-page-param` returning `nil` is the single end-of-feed signal; `:has-next-page?` reads it. A load-more past the end is a no-op — no request fires.
- **The error.** A load-more failure keeps the feed and surfaces `:page-error` ("couldn't load more — retry"), a separate channel from a first-load failure.

> **From re-frame v1.** Before infinite resources, you rolled all of the above by hand, and the parts list was long: a list slice in app-db, a `:loading-more?` flag, a cursor slice, an append handler on the success event, an "end of feed" flag, dedupe of a double-clicked button, reset-on-filter-change. The infinite resource owns all of it — and, because it's a real resource, it also rides scope clearing, tag invalidation, SSR, and time-travel restore, which a hand-rolled slice never gets for free.

### Refetch and reset

`:rf.resource/refetch` on a feed is **window-preserving by default**: the accumulated pages stay rendered until their replacement succeeds, so a focus/reconnect/invalidation-driven refetch never collapses a loaded feed back to page 0. Two opt-ins ship from day one if you want different behaviour, declared on the resource's `:refetch` policy:

- `:refetch {:refetch-all-pages? true}` — re-fetch every accumulated page (TanStack parity).
- `:refetch {:refetch-window n}` — bound how much of the accumulation is refreshed.

Tag invalidation reaches a feed the same way it reaches any resource: a write that dispatches `:rf.resource/invalidate-tags` (or a [`reg-mutation`](../glossary.md#mutation) with `:invalidates`) marks the feed stale by its **feed [tag](../glossary.md#cache-tag)**, and the next ensure refetches it under the window-preserving rule above. So give a feed a `:tags` fn — `(fn [_params _data] #{[:feed :timeline]})` — and a "new post" mutation can invalidate the whole timeline by that tag. One coarse note: a mutation that touches *one item inside* the feed invalidates the **whole feed** (correct, if blunt) rather than patching that one element in place — in-place page-vector patching is a later, optimistic slice.

Resetting on a filter change needs **no code at all** — and this falls straight out of the identity model. A different filter is a different *identity params* value, so it's a different feed instance that first-loads page 0 on its own. The old accumulation is a separate, GC-eligible entry; you don't clear it, you just stop owning it. And because the feed is a real scoped resource, a per-user feed (a scope resolver instead of `:rf.scope/global`) is dropped wholesale on `clear-scope` at logout — coherence a hand-rolled app-db slice simply can't buy.

> **Going deeper — infinite scroll instead of a button.** Want the feed to load as the user nears the bottom? Wire an `IntersectionObserver` to a sentinel `div`. One catch: the observer callback fires *outside frame context* — the browser calls it directly, not as part of your app's render or event loop — so a bare `rf/dispatch` there has no frame to target and raises `:rf.error/no-frame-context` ([frame identity is carried, not found](../../core/glossary.md#frame-identity-is-carried-not-found)). The fix is to capture a [capture-frame](../../core/glossary.md#capture-frame) while you *are* still in frame context (during render or mount) and dispatch through that:
>
> ```clojure
> ;; Create at mount (Form-3), observe a sentinel div, disconnect on unmount.
> (let [{:keys [dispatch]} (rf/capture-frame)]
>   (js/IntersectionObserver.
>    (fn [entries _]
>      (when (.-isIntersecting (aget entries 0))
>        (dispatch [:rf.resource/load-more
>                   {:resource :feed/timeline :params {}
>                    :cause [:user :feed/scroll-sentinel]}])))))
> ```
>
> The `dispatch` you captured is frame-bound, so it lands on the right frame even though the observer callback runs detached. And because `load-more` dedupes an in-flight fetch and no-ops past the terminal, a sentinel that fires repeatedly as the user scrolls is safe — you don't need to debounce it to avoid double-loading.

> **Going deeper — bidirectional feeds (prepend).** The `:prev-page-param` derivation mirror is defined (declare it just like `:next-page-param`, computed from the *first* page, and `:has-prev-page?` becomes observable), but the prepend event `:rf.resource/load-prev` is deferred until a consumer needs it — v1 ships next-direction `load-more` only. So you can register `:prev-page-param` and read `:has-prev-page?` today; there just isn't a built-in event to advance backward yet.

## Scroll position is not a fact

With feeds you'll be tempted to dispatch scroll positions into app-db. Don't — and here's the test that settles it, lifted from [Where should this value live?](../../core/where-state-lives.md): would any handler or sub *decide* anything on this value, and would it mean anything after a [time-travel](../../core/glossary.md#time-travel) restore or on a server render? A pixel offset fails both tests cold. It's host state, and the framework treats it as such.

The route's `:scroll` key declares the behaviour. The contract is a closed three-value enum (plus a map form for host-specific shapes):

- **`:top`** — scroll to the top on entry (or scroll a `#fragment` element into view, if the URL carries one). This is the numbered example above.
- **`:restore`** — restore the saved scroll position for this URL (the runtime captures positions on every navigation; this is the natural Back/Forward behaviour).
- **`:preserve`** — leave the scroll position where it is. (Also the meaning of `nil` / an absent `:scroll`.)

Leave `:scroll` undeclared and the resolved default is `:top` on forward navigation and `:restore` on Back/Forward — exactly what a feed wants, so an infinite feed usually declares nothing here. That saved-position cache is kept host-side, outside app-db; scroll position is not dispatched per [event](../../core/glossary.md#event) tick.

So what *is* a fact? The page number (in the URL), the accumulated pages (the infinite resource entry, runtime-owned), and — if you need a resume point — a real domain fact like the last-read item id. Store those, and let the router own the pixels.

## Advanced

Two things you won't reach for on day one, but that the paginated/feed shapes support the moment you need them.

### Keep a list fresh on an interval

A leaderboard, a notifications badge, an admin queue — sometimes a list should re-read itself every few seconds without anyone clicking. Declare `:poll-interval-ms` on the resource and the runtime re-loads it on that cadence; you write no timer, no `setInterval`, no `:dispatch-later` loop:

```clojure
(rf/reg-resource :app/articles
  {:params-schema    [:map [:page :int]]
   :scope            :rf.scope/global
   :poll-interval-ms 5000               ;; re-read every 5s — while owned + visible
   :stale-after-ms   60000}
  (fn [{:keys [page]} _ctx] …))
```

The contract is worth knowing, because it's the same *owner-driven* model the rest of this page runs on, not a component-observer one (TanStack's `refetchInterval`, SWR's `refreshInterval`, RTK's `pollingInterval` — but driven by the lease, not by a mounted hook):

- **Polling needs a live owner.** A `:poll` tick fires only while the entry has at least one active [owner](../glossary.md#owner--cause) (the route lease, a machine, an explicit `[:lease …]`). The poll itself is pure [cause](../glossary.md#owner--cause), never an owner — it creates no liveness and extends no GC, so the instant the last owner releases (route leave), polling stops. A "just polling, no route" view mints its own `[:lease …]` owner with a matching release.
- **Hidden tabs pause.** A tick is suppressed while the document is hidden (`document.visibilityState != "visible"`) and resumes on tab return — matching the `refetchIntervalInBackground: false` default of every prior-art tool. (A background opt-in is reserved for the first consumer that needs it.)
- **It can't stampede.** A tick that finds a refetch already in flight skips and re-arms (no overlapping requests); a tab return that fires both the focus revalidation and a poll tick double-fetches nothing — whichever starts work first wins, the other no-ops. A failed tick is an ordinary background-refresh failure (data stays, `:refresh-error` records it) and the *next* tick still fires — a transient blip never silently stops the monitor.

`:poll-interval-ms` is orthogonal to `:stale-after-ms`: staleness governs "refetch on focus/route-entry *if* older than X", polling governs "re-read every X regardless." A non-positive or absent value means no polling. Because of [structural sharing](../../core/glossary.md#the-derivation-graph), a poll that returns identical rows preserves the old `:data` value, so the list stays quiet on screen when nothing actually changed.

### Feeds and pages under SSR

Both shapes ride [SSR](../../ssr/glossary.md#ssr) with no extra work, because a resource entry is the same runtime-owned value on the server as in the browser. A `:blocking?` route resource is the wait point: the server drains it before render, serializes the settled entry through the egress projection (a numbered page, or the feed's accumulated pages — typically just page 0 from the server), and the client [hydrates](../../ssr/glossary.md#hydration) it instead of refetching. For an infinite feed the nice consequence is that **load-more resumes from the hydrated tail** — the client reads `:next-page-param` off the page-0 the server already painted and the first "Load more" continues the sequence, no double-fetch of what shipped in the HTML.

> **Gotcha — a blocking SSR resource needs a deadline, and a slow upstream surfaces as a typed failure.** Blocking governs the server's *wait-before-render* point, so a hung backend can't be allowed to hang the request forever. A blocking resource that exceeds the render deadline settles as a structured first-load failure for that server frame — `{:kind :rf.http/timeout :reason :ssr-blocking-timeout}`, inside the same closed `:rf.http/*` taxonomy every resource `:error` carries — so your error branch (which already gates on `:error` + `(not (:has-data? state))`) renders an error or skeleton rather than the page never arriving. The `:reason` lets you tell an SSR-deadline miss apart from a genuine upstream timeout; the `:kind` keeps it in one taxonomy. A non-blocking route resource never blocks render at all — if it hasn't settled by serialize time it simply ships no data and the client fetches it on hydration.

## Where to go from here

The complete worked version of the infinite half — route-owned page-0 ensure, the passive `infinite-state` view, the causal load-more, the `nil` terminal, and the `:page-error` channel — is in [`examples/capabilities/resources/infinite_feed/`](../../../examples/capabilities/resources/infinite_feed). The numbered-pages half — tag filters, a session-scoped feed, profile tabs — is in [`examples/real-apps/realworld_resources/`](../../../examples/real-apps/realworld_resources). The normative spec is [Spec 016 §Infinite resources and load-more feeds](../../../spec/016-Resources.md#infinite-resources-and-load-more-feeds).
