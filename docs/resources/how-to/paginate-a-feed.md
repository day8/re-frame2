# Paginate a feed

This recipe pages a list without a skeleton flash on every page turn and without a
hand-rolled cursor in app-db. Resources support two shapes:

| Shape | On screen | Identity |
|---|---|---|
| **Numbered pages** | Page 2 *replaces* page 1 (search, admin tables) | Page is part of the resource key |
| **Load more** | Page 2 *appends* (social feed) | One growing entry (`:infinite true`) |

Either way, the page cursor and the cached pages live in
[runtime-db](../../core/glossary.md#runtime-db); pagination adds nothing to the
[app-db](../../core/glossary.md#app-db) you own. Numbered pages come first, then
load-more.

!!! note "The one idea to hold onto"

    A numbered page is part of the resource's *identity* — page 7 is its own
    separately-cached value. An infinite feed is *one* identity that *grows* —
    page 1, then 1+2, then 1+2+3. Almost everything below follows from that
    distinction.

??? info "Coming from TanStack Query?"

    Numbered pages are `useQuery` with the page in the `queryKey`, plus `keepPreviousData`. Load-more is `useInfiniteQuery`. The one difference that will feel unfamiliar: the next page is loaded by a dispatched [event](../../core/glossary.md#event), not by a `fetchNextPage()` call from inside a component. Views read; events cause work.

## Numbered pages

The page number is part of the resource's identity, and it lives in the URL. Three steps get you there. (The worked version — tag filters, a session-scoped feed, profile tabs — is [`examples/real-apps/realworld_resources/`](../../../examples/real-apps/realworld_resources).)

### 1. Put the page in the resource's params

Every variable that changes the server's answer belongs in `:params`, and the page is one of them. Page 1 and page 2 are two different answers, so they become two cache entries under one resource:

```clojure
;; Adapted from examples/real-apps/realworld_resources/resources.cljs
(def page-size 10)

(rf/reg-resource :realworld/articles
  {:params-schema [:map [:page :int]]
   :scope         :rf.scope/global}
  (fn [{:keys [page]} _ctx]
    {:request {:method :get
               :url    "/api/articles"
               :params {:limit  page-size
                        :offset (* page-size (dec page))}}
     :decode  :json}))
```

The server replies `{:articles [...] :total 290}` — adapt the field names to yours. Add `:stale-after-ms`, `:gc-after-ms` and `:tags` as you would for any resource ([The model](../concepts.md#register-a-resource)); `:tags` is what a write elsewhere uses to [invalidate](../glossary.md#invalidate) the list ([Invalidate after a mutation](invalidate-after-a-mutation.md)). To [shape-validate](../../core/glossary.md#schema) each page at runtime, put a Malli schema on the request's `:decode`.

Pagination doesn't change the [`:scope`](../glossary.md#scope) rule. This list is the same for every viewer, so it declares `:rf.scope/global`. A per-user list — "my drafts", a tenant's table — declares a scope resolver instead, so page 2 for tenant A and page 2 for tenant B are different entries.

### 2. Let the URL carry the page

The current page number says *where the user is*, and that is the URL's job. In the URL, the page gets shareable links, working Back/Forward, and a reload that lands on the same page.

The [route](../../routing/glossary.md#route) validates the `?page=` query param, feeds it into the resource's params, and keeps the old page visible while the new one loads:

```clojure
;; Adapted from examples/real-apps/realworld_resources/routing.cljs
(rf/reg-route :realworld/home
  {:query     [:map [:page {:optional true} :int]]
   :scroll    :top
   :resources [{:resource       :realworld/articles
                :params         (fn [route] {:page (or (get-in route [:query :page]) 1)})
                :blocking?      true
                :keep-previous? true}]}
  "/")
```

On entry the route becomes the entry's [owner](../glossary.md#owner--cause), so the page you're looking at can't be garbage-collected; leaving the route releases it. There is no cleanup code to write.

The four `:resources` keys above:

- `:resource` names the registered resource;
- `:params` is a pure `(fn [route] …)` computing its params from the route match;
- `:blocking?` keeps `:rf.route/transition` at `:loading` (and gives [SSR](../../ssr/glossary.md#ssr) a wait point) until this resource's first load lands — the route itself commits at once;
- `:keep-previous?` keeps the previous page on screen while the next one loads (step 4).

Two more keys earn their place on real tables:

- **`:when`** — a `(fn [route _ctx] …)` predicate; the resource is only ensured when it returns truthy. Use it for a list that should load only under a condition (a search that waits for a non-empty `?q=`) rather than ensuring with `nil` params.
- **`:scope`** — an override of the resource's registered scope, for a route that reads as a *different* principal (an admin reading tenant X). A scoped list normally declares `:scope {:from-db :realworld/session}` on its registration and omits it here, so the route and the view's subscription inherit the same scope.

!!! warning "Gotcha — route and sub must compute the same key"

    Params identity is exact: `{:page nil}` and `{:page 1}` are different cache entries. If the view subscribes under one while the route ensured the other, the view reads `:idle` forever — hard to chase, because everything *looks* wired up. Normalise the same way on both sides: `(or page 1)` in the route (above) and in the sub (next step).

!!! warning "Gotcha — a scoped list needs the same scope on both sides"

    The same-key rule has a second half once a list is scoped. A sub that can't resolve a scope at all (logged out, so the resolver returns `nil`) raises `:rf.error/resource-sub-unresolved-scope` rather than reading a shared entry. A sub that passes an explicit `:scope` override naming a *different* scope reads its own empty entry — `:idle` forever — so pass one only when you mean to read as another principal. Declaring the scope once, on the registration, and letting route and sub inherit it avoids both. ([Troubleshooting](../concepts.md#when-it-fails-loud--the-errors-and-warnings) lists the signals.)

### 3. Page by navigating, not by fetching

**Changing pages is a [navigation](../../routing/glossary.md#navigate), not a fetch.** You change `?page=` in the URL and the route ensures the right entry. Drop the param for page 1, so the first page has one canonical URL rather than both `/` and `/?page=1`:

```clojure
(rf/reg-event :home/go-to-page
  (fn [_ [_ page]]
    {:fx [[:dispatch [:rf.route/navigate {:to :realworld/home
                                          :query (if (> page 1) {:page page} {})}]]]}))

(rf/reg-sub :home/page {:inputs [[:rf.route/query]]}
  (fn [[q] _] (or (:page q) 1)))
```

The event has no fetch in it. It navigates, and the route declaration from step 2 turns the navigation into the right ensure.

A filter — a tag, a search term — is one more params key and one more query param. To carry it across page changes, put it in the address you dispatch: either an in-place `:query-merge` (same route, edit one key) or a small pure helper that adds the current filter to the destination ([Carrying global state through the URL](../../routing/concepts.md#carrying-global-state-through-the-url)). When the *filter* changes, go back to page 1 — a new filter is a new list, and "page 2 of the old filter" means nothing. That reset is a navigation that drops `:page` while setting the filter:

```clojure
(rf/reg-event :home/set-tag
  (fn [_ [_ tag]]
    ;; New filter, new list: set :tag and drop :page.
    {:fx [[:dispatch [:rf.route/navigate {:to :realworld/home
                                          :query (cond-> {} tag (assoc :tag tag))}]]]}))
```

The filter then joins the resource's `:params-schema` and the route's `:query` schema, and the route's `:params` fn passes it through alongside the page.

### 4. Show the old page while the new one loads

With `:keep-previous?`, while page 2 is first-loading its state carries `:previous? true` and `:previous-data` — page 1's rows, shown through page 2's loading state. They are borrowed for display only and never copied into page 2's entry. Render them instead of a blank skeleton:

```clojure
;; Adapted from examples/real-apps/realworld_resources/views.cljs
(rf/reg-view article-list []
  (let [page  @(subscribe [:home/page])
        state @(subscribe [:rf/resource {:resource :realworld/articles :params {:page page}}])]
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

`list-skeleton`, `list-error` and `article-row` are your own views.

Click through to page 2 with [Xray](../../core/glossary.md#xray) open: the navigation event row shows the ensure it caused under the `{:page 2}` key, and the entry goes `:loading` → `:loaded`. Click back to page 1 and the `{:page 1}` entry is still fresh — a cache hit, no network request. Pages are identity, so going back is free.

The full `:rf/resource` view-model — including `:previous-key`, the cache key the borrowed rows came from — and the single-key subs such as `:rf.resource/previous-data` are in [Part 2 of the tutorial](../tutorial/02-server-data.md#the-view-model-one-fixed-map) and the [API reference](../../api/re-frame.resources.md).

!!! warning "Gotcha — two error channels"

    `:error` is **first-load only**. Revisit a page after it has gone stale and it refreshes in the background: the status moves to `:fetching` (not `:loading`) and the old rows stay on screen. If that refresh fails, the entry goes back to `:loaded`, keeps its rows, and records the failure in `:refresh-error` — the list does *not* collapse to an error screen. So the full error branch gates on `(:error state)` *and* `(not (:has-data? state))`, while a "couldn't refresh — showing cached" banner gates on `(:refresh-error state)`.

## Load more: an infinite resource is one growing entry

A load-more feed shows *everything loaded so far*: page 1, then 1+2, then 1+2+3, as one growing list. re-frame2 models that as an **infinite resource** — *one* cache entry whose value is an ordered sequence of pages, with the next page's cursor derived from the last page loaded. (The worked version is [`examples/capabilities/resources/infinite_feed/`](../../../examples/capabilities/resources/infinite_feed).)

### 1. Register the feed with `:infinite true`

An infinite resource is an ordinary resource — identity, scope, request — plus `:infinite true` and a pure `:next-page-param` function that returns the *next* page's cursor from the last page you loaded.

The cursor is **not** a params key. If it were, every page would be its own cache entry, as in the numbered case. The runtime tracks it for you and passes it to your request fn in its second argument, `ctx`:

```clojure
;; Adapted from examples/capabilities/resources/infinite_feed/core.cljs
(def page-size 8)

(rf/reg-resource :feed/timeline
  {:infinite       true

   ;; The feed's identity params (filter / sort / search) — what makes two feeds
   ;; separate cache entries. The per-page cursor is not here. This demo is one
   ;; public timeline, so the identity is empty.
   :params-schema  [:map]
   :scope          :rf.scope/global

   ;; The next page's param, from the last loaded page. nil means "no more
   ;; pages", and :has-next-page? is then false.
   :next-page-param
   (fn [last-page _all-pages]
     (get-in last-page [:page-info :next-cursor]))

   ;; Each page is an envelope ({:items [...] :page-info {…}}), so the runtime
   ;; needs this accessor to flatten pages into the merged list.
   :page->items    :items

   :stale-after-ms 60000
   :gc-after-ms    (* 5 60 1000)}

  ;; For an infinite resource, ctx carries this page's cursor (nil for the
  ;; first page) and its index.
  (fn [_feed-params {:rf.resource/keys [page-param page-index]}]
    {:request {:method :get
               :url    "/api/timeline"
               :params (cond-> {:limit page-size :page-index page-index}
                         page-param (assoc :cursor page-param))}
     :decode  :json}))
```

- The **first page** is fetched with `:page-param nil` (override it with `:initial-page-param` if your API's first page wants a real cursor) and `:page-index 0`. Each load-more passes the cursor `:next-page-param` derived from the last page.
- **Two load-mores don't make two cache keys** — they extend *one* entry. Only the identity params (filter, sort, search) name the feed. Change those and you get a different feed; the cursor never touches the cache key.

Beyond `:next-page-param` (required) and `:page->items`, an infinite resource accepts:

- `:prev-page-param` — the backward mirror, which feeds `:has-prev-page?` (there is no prepend event; see the note at the end of this section).
- `:initial-page-param` — the first page's cursor (default `nil`).
- `:refetch` — the refetch-window policy (see [Refetch and reset](#refetch-and-reset)).

??? info "Coming from TanStack Query?"

    `:next-page-param` is `getNextPageParam`, `:initial-page-param` is `initialPageParam`, and `:page->items` is the accessor you'd write inline when flattening `data.pages`. The first page's `nil` param is TanStack's defaulted `initialPageParam`. re-frame2 adds a derived `:has-next-page?`, so a view never works out the end of the feed itself.

!!! warning "Gotcha — two ways to register a feed wrong"

    `:infinite true` with **no `:next-page-param`** raises `:rf.error/infinite-missing-next-page-param` — the runtime can't guess where the next page is. And a feed whose pages are *envelopes* (`{:items [...] :page-info {…}}`) **must** declare `:page->items`, or the runtime raises `:rf.error/infinite-missing-page-accessor` at the merge rather than flattening the wrong key. (If a page is *already a vector*, it flattens as-is and needs no accessor.)

### 2. Let the route own the feed (it ensures page 0)

A route declares an infinite resource like any other. Route entry ensures **page 0** — the first load only — under the route's owner; route leave releases it:

```clojure
(rf/reg-route :app/timeline
  {:resources
   [{:resource  :feed/timeline
     :params    (fn [_route] {})
     :blocking? true}]}        ;; :blocking? waits for page 0 only
  "/timeline")
```

The same `:resources` entry keys apply — `:when`, `:scope`, `:params` — but for an infinite feed, `:blocking?` and route ownership concern **page 0 only**. Load-more is a user event during the route's lifetime, not part of the route's load plan.

### 3. Read the merged list; load more with an event

The view reads the `[:rf.resource/infinite-state …]` subscription and dispatches one event. It never fetches and never advances a cursor:

```clojure
(rf/reg-view timeline-feed []
  (let [feed @(subscribe [:rf.resource/infinite-state
                          {:resource :feed/timeline :params {}}])]
    (cond
      ;; First load (page 0), no data yet.
      (:loading? feed) [feed-skeleton]

      ;; First load failed with no data. The full error screen reads :error;
      ;; :page-error is the separate load-more channel used below.
      (and (:error feed) (not (:has-data? feed)))
      [feed-error (:error feed)]

      :else
      [:<>
       ;; :items is the merged flat list. The runtime concatenates pages
       ;; (via :page->items) and memoises the merge.
       (into [:div] (for [item (:items feed)]
                      ^{:key (:id item)} [feed-row item]))

       ;; A load-more failure keeps every page visible — show an inline retry.
       (when (and (:page-error feed) (:has-data? feed))
         [load-more-error (:page-error feed)])

       (cond
         (:fetching-next? feed) [spinner]              ;; a load-more in flight

         (:has-next-page? feed)
         [:button {:on-click #(dispatch [:rf.resource/load-more
                                         {:resource :feed/timeline :params {}
                                          :cause    [:user :feed/load-more]}])}
          "Load more"]

         :else [end-of-feed])])))      ;; nil next-page-param: no more pages
```

Four keys carry the whole feed UI:

- **`:items`** — the merged flat list.
- **`:has-next-page?`** — show the button or the end marker.
- **`:fetching-next?`** — a load-more in flight (distinct from `:fetching?`, which is a *whole-feed* refresh).
- **`:page-error`** — a load-more failure.

The loaded pages stay visible through a load-more, with no skeleton flash, because the feed already has data.

The full view-model has a few more keys for the cases that need them:

```clojure
@(subscribe [:rf.resource/infinite-state {:resource :feed/timeline :params {}}])
;; =>
{:status         :loaded
 :items          [<item> <item> …]     ;; merged flat list
 :pages          [<page-0> <page-1> …] ;; raw page boundaries (for per-page headers / dividers)
 :page-count     2
 :has-next-page? true
 :has-prev-page? false                 ;; backward feeds only (no prepend event — see below)
 :loading?       false                 ;; first load (page 0), no data yet
 :fetching-next? false                 ;; a load-more in flight (pages stay visible)
 :fetching?      false                 ;; a whole-feed refresh in flight
 :stale?         false
 :has-data?      true
 :error          nil                   ;; page-0 first-load failure
 :refresh-error  nil                   ;; whole-feed refresh failure
 :page-error     nil}                  ;; last load-more (page N>0) failure
```

Reach for `:pages` when you need page boundaries — a divider, per-page headers — rather than the flat `:items`. Note the *three* error channels, each a different situation with its own UI: `:error` is a page-0 failure with nothing on screen yet; `:page-error` is a load-more failure that keeps every loaded page visible; `:refresh-error` is a failed whole-feed background refresh that kept the data. They never overlap.

The single-key subs from the numbered case apply to a feed too, but `:rf.resource/data` returns the *raw page vector*, so the infinite family adds `:rf.resource/items`, `:rf.resource/pages`, `:rf.resource/has-next-page?`, `:rf.resource/has-prev-page?`, `:rf.resource/fetching-next?`, `:rf.resource/page-count` and `:rf.resource/page-error`.

!!! warning "Gotcha — `load-more` carries a `:cause`, not an `:owner`"

    The route already owns the feed, which is what keeps it alive. A load-more only extends that entry, so it supplies a `:cause` and no `:owner`. Pass an `:owner` anyway and the runtime warns (`:rf.warning/resource-load-more-owner-ignored`) and ignores it — the page still appends — rather than creating a second owner that would keep the feed alive after the route leaves.

### What the runtime does for you

You can wire the button straight to `dispatch`, with no "has next?" check and no in-flight flag around it:

- **The cursor.** `:next-page-param` derives the next cursor from the last page; the runtime stores it on the entry and passes it to the next request. You never thread a cursor through app-db.
- **The append.** A page success appends to the entry's page vector with structural sharing — earlier pages stay identical (`=` and `identical?`). A load-more moves the feed to `:fetching` with the loaded pages still visible.
- **The in-flight guard.** `:fetching-next?` is true while a load-more page is fetching, and a second load-more while one is in flight is deduplicated (Xray shows `:rf.resource/load-more-skipped` with `:reason :in-flight`).
- **The end.** `:next-page-param` returning `nil` is the one end-of-feed signal, and `:has-next-page?` reads it. A load-more past the end sends no request (`:reason :no-next-page`).
- **The error.** A failed page keeps every loaded page and records `:page-error`, a separate channel from a first-load failure.

### Refetch and reset

`:rf.resource/refetch` on a feed **keeps the window by default**: the loaded pages stay on screen until their replacement succeeds, so a focus-, reconnect- or invalidation-driven refetch never collapses the feed back to page 0. Two options on the resource's `:refetch` policy change that:

- `:refetch {:refetch-all-pages? true}` — refetch every loaded page (TanStack parity).
- `:refetch {:refetch-window n}` — bound how much of the feed is refreshed.

Tag invalidation reaches a feed like any resource: `:rf.resource/invalidate-tags`, or a [`reg-mutation`](../glossary.md#mutation) with `:invalidates`, marks the feed stale by its **feed [tag](../glossary.md#cache-tag)**. An owned feed (the route's, say) refetches at once and an unowned one on its next ensure, both under the window rule above. So give a feed a `:tags` fn — `(fn [_params _data] #{[:feed :timeline]})` — and a "new post" mutation can invalidate the whole timeline. A mutation that touches *one item inside* the feed invalidates the **whole feed**; patching one item in place inside a feed's pages is not supported.

Resetting on a filter change needs **no code**. A different filter is a different identity, so it's a different feed that loads page 0 on its own. The old one becomes an unowned entry that GC collects; you don't clear it. And because the feed is a scoped resource, a per-user feed (a scope resolver instead of `:rf.scope/global`) is dropped along with its scope by `clear-scope` at logout.

??? note "Infinite scroll instead of a button"

    To load as the user nears the bottom, wire an `IntersectionObserver` to a sentinel `div`. The observer callback runs *outside frame context* — the browser calls it directly, not during your app's render or an event — so a bare `rf/dispatch` there has no frame to target and raises `:rf.error/no-frame-context` ([frame identity is carried, not found](../../core/glossary.md#frame-identity-is-carried-not-found)). Take a [capture-frame](../../core/glossary.md#capture-frame) while you *are* in frame context (during render or mount) and dispatch through it:

    ```clojure
    ;; Create at mount, observe a sentinel div, disconnect on unmount.
    (let [{:keys [dispatch]} (rf/capture-frame)]
      (js/IntersectionObserver.
       (fn [entries _]
         (when (.-isIntersecting (aget entries 0))
           (dispatch [:rf.resource/load-more
                      {:resource :feed/timeline :params {}
                       :cause [:user :feed/scroll-sentinel]}])))))
    ```

    The captured `dispatch` is bound to the frame, so it lands correctly even though the callback runs detached. And because `load-more` deduplicates an in-flight fetch and does nothing past the end, a sentinel that fires repeatedly is safe without debouncing.

??? note "Backward feeds (prepend)"

    `:prev-page-param` is supported — declare it like `:next-page-param`, computed from the *first* page, and `:has-prev-page?` becomes observable — but there is no prepend event: `load-more` only appends.

## Scroll position: let the route handle it

A scroll offset doesn't belong in app-db. The test from [Where should this value live?](../../core/where-state-lives.md): would any handler or sub *decide* anything on this value, and would it mean anything after a [time-travel](../../core/glossary.md#time-travel) restore or on a server render? A pixel offset fails both. It's host state.

The route's `:scroll` key declares the behaviour, as one of three values:

- **`:top`** — scroll to the top on entry (or scroll a `#fragment` element into view, if the URL has one). The numbered example above uses this.
- **`:restore`** — restore the saved scroll position for this URL. The runtime saves positions on every navigation, so this is the natural Back/Forward behaviour.
- **`:preserve`** — leave the scroll position where it is.

`false` switches the scroll effect off.

With no `:scroll` declared, the default is `:top` on forward navigation and `:restore` on Back/Forward — what a feed wants, so an infinite feed usually declares nothing. The saved positions live on the host, outside app-db, and no event is dispatched per scroll.

What *does* belong in state: the page number (in the URL), the loaded pages (the infinite resource entry), and — if you need a resume point — a real domain fact like the last-read item id.

## Advanced

### Keep a list fresh on an interval

A leaderboard, a notifications badge, an admin queue — sometimes a list should re-read itself every few seconds. Declare `:poll-interval-ms` on the resource and the runtime reloads it on that interval; there's no timer or `:dispatch-later` loop to write:

```clojure
(rf/reg-resource :realworld/articles
  {:params-schema    [:map [:page :int]]
   :scope            :rf.scope/global
   :poll-interval-ms 5000               ;; re-read every 5s — while owned + visible
   :stale-after-ms   60000}
  (fn [{:keys [page]} _ctx] …))
```

Polling follows the same owner model as the rest of this page, not a mounted-component one (TanStack's `refetchInterval`, SWR's `refreshInterval` and RTK's `pollingInterval` are driven by a mounted hook):

- **Polling needs a live owner.** A tick fires only while the entry has at least one [owner](../glossary.md#owner--cause) — the route, a machine, an app-event owner. The poll is a [cause](../glossary.md#owner--cause), never an owner, so when the last owner releases (route leave), polling stops. A view that polls with no route creates its own app-event owner (e.g. `[:dashboard/opened …]`) with a matching release.
- **Hidden tabs pause.** A tick is skipped while the document is hidden (`document.visibilityState != "visible"`) and resumes on tab return — the `refetchIntervalInBackground: false` default of the other libraries. There is no option to keep polling while hidden.
- **It can't stampede.** A tick that finds a refetch already in flight skips and re-arms. A tab return that triggers both focus revalidation and a poll tick fetches once — whichever starts first wins. A failed tick is an ordinary background-refresh failure (the data stays, `:refresh-error` records it), and the *next* tick still fires.

`:poll-interval-ms` is independent of `:stale-after-ms`: staleness governs "refetch on focus or route entry *if* older than X", polling governs "re-read every X regardless". A zero, negative or absent value means no polling. Because of [structural sharing](../../core/glossary.md#the-derivation-graph), a poll that returns identical rows keeps the old `:data` value, so the list stays quiet when nothing changed.

### Validate and classify each page

A feed's `:data` is the *sequence* of pages, so per-page rules go on the same surfaces every resource uses, applied one page at a time:

- **Validate a page** with the request's `:decode`. Put a Malli page schema there (in place of `:json`) and it validates each page's decoded value before it settles — page 0, every load-more, and every refetch. (`:data-schema` is a static shape declaration for tooling; it validates nothing at runtime.)
- **Redact sensitive page fields** with the resource's `:sensitive` / `:large` declarations, written relative to the data (e.g. `:sensitive [[:data :author-email]]` — see [data classification](../../core/glossary.md#data-classification)). The path matches every page — `[:data 0 :author-email]`, `[:data 1 :author-email]`, … — so the field is redacted on every page wherever data leaves the app (SSR, tools, traces).

### Feeds and pages under SSR

Both shapes work under [SSR](../../ssr/glossary.md#ssr) with no extra code, because a resource entry is the same runtime-owned value on the server as in the browser. A `:blocking?` route resource is the wait point: the server waits for it before rendering, serializes the settled entry (a numbered page, or the feed's loaded pages — usually just page 0), and the client [hydrates](../../ssr/glossary.md#hydration) it instead of refetching. For an infinite feed, **load-more resumes from the hydrated page**: the client reads `:next-page-param` off the page 0 the server rendered, and the first "Load more" continues from there without refetching what shipped in the HTML.

!!! warning "Gotcha — a blocking read can't hang the server"

    A blocking resource that misses the render deadline settles as a first-load failure for that server frame — `{:kind :rf.http/timeout :reason :ssr-blocking-timeout}`, in the same `:rf.http/*` set every resource `:error` uses. Your error branch (which already gates on `:error` and `(not (:has-data? state))`) renders an error or skeleton instead of the page never arriving. `:reason` tells an SSR deadline apart from a genuine upstream timeout. A non-blocking route resource never delays the render: if it hasn't settled by serialize time, it ships no data and the client fetches it after hydration.
