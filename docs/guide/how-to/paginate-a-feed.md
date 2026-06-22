# Paginate a feed

You have a list with more rows than you want to fetch at once. This recipe builds the two pagination shapes you actually ship, and they behave differently on purpose. With **numbered pages**, page 2 *replaces* page 1 on screen — think search results or an admin table. With **load more**, page 2 *appends* to what's already there, the way a social feed grows. Both ride on resources — a resource being a declared, cached server query — like the one [Server state: resources](../concepts/server-state.md) sets up. By the end you'll have both shapes wired with no pagination state in app-db at all.

If you're coming from TanStack Query, you already know the two halves of this page; you just don't know their re-frame2 names yet. The numbered shape is `useQuery` with the page in the `queryKey`, plus `keepPreviousData`. The load-more shape is `useInfiniteQuery`. Both map over almost one-to-one. For numbered pages: put the page in the resource's params, and use `:keep-previous?` for the no-flicker behaviour — with two twists, the page also lives in the URL, and views never fetch. For load-more: re-frame2 has a first-class **infinite resource** (the `useInfiniteQuery` counterpart) — one growing entry of accumulated pages, with a next-page cursor *derived from the last page's data* and a `:rf.resource/load-more` event.

The one deliberate divergence from TanStack is worth stating up front, because it's the thing that will feel unfamiliar: the cursor lives in the runtime entry and is advanced by a *causal event*, not by a `fetchNextPage()` call from inside a component. That's not pedantry — it's the same passive-views rule the whole framework runs on. Views read; events change the world.

> **A numbered page is part of the resource's identity. An infinite feed is one identity that grows.** Paging a numbered list reads a *different*, separately cached value per page — page 7 is its own thing. An infinite feed keeps the accumulation together as one entry, so the merged list and the next cursor are a single reactive value. Hold that distinction and the rest of the page is mostly mechanics.

## Numbered pages: each page is its own cache entry

### 1. Put the page in the resource's params

Here's the one rule that makes resources work: every variable that changes the server's answer belongs in params. The page is one of those variables, because page 1 and page 2 are two different answers to two different questions. So they become two distinct entries under one resource:

```clojure
;; Adapted from examples/reagent/realworld_resources/resources.cljs
(def page-size 10)

(rf/reg-resource :app/articles
  {:params-schema  [:map [:page :int]]
   :scope          :rf.scope/global
   :stale-after-ms 60000
   :gc-after-ms    (* 5 60 1000)}
  (fn [{:keys [page]} _ctx]
    {:request {:method :get
               :url    "/api/articles"
               :params {:limit  page-size
                        :offset (* page-size (dec page))}}
     :decode  :json}))
```

The server here replies `{:articles [...] :total 290}` — adapt the field names to yours. (When writes elsewhere need to invalidate this list, add `:tags`; that's covered in [Invalidate after a mutation](invalidate-after-a-mutation.md).)

### 2. Let the URL carry the page

Quick question: where should the *current page number* live? It's tempting to drop it into app-db — your app's single state map — but the page number is really telling you *where the user is*, and "where the user is" is the URL's job. Put it in the URL and you get shareable links, working Back/Forward, and a reload that lands on the same page, all for free.

The route validates the `?page=` query param, feeds it into the resource's params, and opts into keeping the old page visible while the new one loads:

```clojure
;; Adapted from examples/reagent/realworld_resources/routing.cljs
(rf/reg-route :app/home
  {:query     [:map [:page {:optional true} :int]]
   :scroll    :top
   :resources [{:resource       :app/articles
                :params         (fn [route] {:page (or (get-in route [:query :page]) 1)})
                :blocking?      true
                :keep-previous? true}]}
  "/")
```

Now route entry loads the right page, *owns* it while you're there, and releases it when you leave. Once a page is unowned it falls back to the normal staleness and garbage-collection policy — so nothing leaks, and you wrote no cleanup code to make that true.

> **Gotcha — both seams must compute the same key.** Params identity is *exact*. `{:page nil}` and `{:page 1}` are different cache entries. If a view subscribes under one key while the route ensured the other, the view reads `:idle` forever — and that's a miserable bug to chase, because everything *looks* wired up. So normalise the same way everywhere: `(or page 1)` on the route side (above) and on the sub side (below). Same fallback, both seams.

### 3. Page by navigating

Here's the mental shift, and it's the whole trick: changing pages is a *navigation*, not a fetch. You swap `?page=` in the URL and the route does the rest. Drop the param entirely for page 1, so the first page has one canonical URL rather than `/` and `/?page=1` both pointing at the same list:

```clojure
(rf/reg-event :home/go-to-page
  (fn [_ [_ page]]
    {:fx [[:dispatch [:rf.route/navigate :app/home {}
                      {:query (if (> page 1) {:page page} {})}]]]}))

(rf/reg-sub :home/page
  :<- [:rf.route/query]
  (fn [q _] (or (:page q) 1)))
```

Notice the event has no fetch in it — no HTTP, no resource call. It just navigates. The route declaration from step 2 turns that navigation into the right `ensure`. That's the seam doing its job.

A filter — a tag, a search term — is just one more params key and one more query param. Carry it across page changes with the route's `:query-retain`. But reset to page 1 when the *filter* changes, because a new filter is a fresh list, and "page 2 of the old filter" means nothing.

### 4. Show the old page while the new one loads

This is the part that makes pagination feel smooth instead of janky. With `:keep-previous?`, while page 2 is first-loading the state carries `:previous? true` and `:previous-data` — page 1's rows, *projected* across, never inserted into page 2's entry. Render those instead of a blank skeleton:

```clojure
;; Adapted from examples/reagent/realworld_resources/views.cljs
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

(Here `dispatch` and `subscribe` are the frame-bound bindings `reg-view` injects — `dispatch` sends an event, `subscribe` reads a derived value — and `list-skeleton`, `list-error`, and `article-row` are your own views.)

Now watch it work. Click through to page 2 with Xray open. The navigation event row shows the `ensure` it caused under the `{:page 2}` key, and the entry walks `:loading` → `:loaded`. Click *back* to page 1 and you'll see the same `{:page 1}` key, still fresh — a cache hit, no network request. That's the payoff of treating pages as identity: back-navigation is free, because you never threw page 1 away. You just stopped looking at it.

> **Coming from re-frame v1?** The page-keyed cache map, the `:loading?` flags, the "don't blank the list while fetching" dance — all the framework's job now. Your app-db holds none of it. The whole reducer-and-flags apparatus you used to write by hand has become four declarations and a `cond`.

## Load more: an infinite resource is one growing entry

A load-more feed is a deliberately different shape, so it's worth a minute on the model before you reach for it. What's on screen is no longer "the server's page N". It's *everything this user has accumulated so far*: page 1, then page 1+2, then page 1+2+3, rendered as one growing list. re-frame2 models that as a first-class **infinite resource** — *one* cache entry whose value is an ordered, growing sequence of pages, with the *next* page's cursor derived from the *last* page you loaded.

Crucially, this is **not** an app-db slice you maintain by hand. Before infinite resources existed, an app rolled this itself, and the parts list was long: a list slice in app-db, a `:loading-more?` flag, a cursor slice, an append reducer on the success event, an "end of feed" flag, dedupe of a double-clicked button, reset-on-filter-change. The infinite resource owns *all* of that — and, because it's a real resource, it also rides scope clearing, tag invalidation, SSR, and time-travel restore, which a hand-rolled slice never gets for free.

### 1. Register the feed with `:infinite true`

An infinite resource is an ordinary resource — identity, scope, request — plus two additions: `:infinite true` (which makes `:next-page-param` required), and a pure `:next-page-param` derivation. The page cursor is **not** a params key; it's internal sequencing state the runtime threads for you, riding the request's reserved second argument:

```clojure
;; Adapted from examples/reagent/infinite_feed/core.cljs
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

A few things just happened, so let's name them. The first page is fetched with `:page-param nil` — that's TanStack's `initialPageParam`, defaulted for you. Each load-more passes the cursor your `:next-page-param` derived from the tail. And — this is the key one — two `load-more` calls on the same feed don't make two cache keys; they extend *one* entry. Only the *identity params* (filter, sort, search) name the feed. Change those and you get a different feed instance; the per-page cursor never touches the cache key.

> **Gotcha — `:page->items` is required for enveloped pages, by design.** If a page is *already a vector* (the server returns `[item, item, …]`), it flattens by identity and you need no accessor. But a feed whose page is an *envelope* — `{:items [...] :page-info {…}}`, the common cursor-paginated shape — **must** declare `:page->items` (a keyword key like `:items`, or a `(fn [page] …)`). The runtime refuses to guess `:items` vs `:data`: a non-vector page with no accessor raises `:rf.error/infinite-missing-page-accessor` at the merge, loudly, rather than silently flattening the wrong thing. Loud-over-magic is a recurring re-frame2 stance, and this is one of its sharper edges.

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

### 3. Read the merged list passively; load-more is a causal event

The view reads the combined `[:rf.resource/infinite-state …]` view-model and dispatches one event. It never fetches and never advances a cursor — it can't, and that's the point:

```clojure
(rf/reg-view timeline-feed []
  (let [feed @(subscribe [:rf.resource/infinite-state
                          {:resource :feed/timeline :params {}}])]
    (cond
      ;; First load (page 0), no usable data yet.
      (:loading? feed) [feed-skeleton]

      ;; First load failed with no data. A feed has one error axis: a page-0
      ;; failure lands on :page-error (the feed stays :loaded), so split the
      ;; full error screen from the inline retry by :has-data?.
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

Read that view-model and you have the entire feed UI in four keys: `:items` (the merged list — the headline read), `:has-next-page?` (show the button or the end marker), `:fetching-next?` (a load-more in flight — distinct from `:fetching?`, which is a whole-feed *refresh*), and `:page-error` (a load-more failure). The accumulated pages stay visible right through a load-more — no skeleton flash — because the feed already has data; the spinner is just the next page arriving.

> **`load-more` carries a `:cause`, not an `:owner`.** This trips people up, so here's the why. The route already *owns* the feed for its whole lifetime, which is what keeps it alive. A load-more *extends* that one owned entry — it isn't trying to keep anything alive on its own, so it omits `:owner` and supplies only `:cause` (owner keeps alive; cause explains why). It's the same owner-vs-cause distinction a manual refresh makes. Pass an `:owner` anyway and the runtime warns and ignores it rather than minting a stray lease that would pin the feed open past its real owner.

### What the runtime does that you no longer write

This is the load-bearing payoff, so it's worth itemising what just disappeared from your codebase:

- **The cursor.** `:next-page-param` derives the next page's param from the loaded tail; the runtime stores it on the entry and passes it to the next `:request`. You never thread a cursor through app-db.
- **The append.** A page success appends to the one entry's page vector with structural sharing — prior pages stay *identical* (`=` and `identical?`). You write no append reducer.
- **The in-flight UI.** `:fetching-next?` is true while a load-more page is fetching; a second load-more while one is in flight dedupes (no double-fetch). You keep no `:loading-more?` flag.
- **The terminal.** `:next-page-param` returning `nil` is the single end-of-feed signal; `:has-next-page?` reads it. A load-more past the end is a no-op — no request fires.
- **The error.** A load-more failure keeps the feed and surfaces `:page-error` ("couldn't load more — retry"), a separate channel from a first-load failure.

### Refetch and reset

`:rf.resource/refetch` on a feed is **window-preserving by default**: the accumulated pages stay rendered until their replacement succeeds, so a focus/reconnect/invalidation-driven refetch never collapses a loaded feed back to page 0. Two opt-ins ship from day one if you want different behaviour: `:refetch {:refetch-all-pages? true}` re-fetches every accumulated page (TanStack parity), and `:refetch {:refetch-window n}` bounds how much of the accumulation is refreshed.

Resetting on a filter change needs no code at all — and this falls straight out of the identity model. A different filter is a different *identity params* value, so it's a **different feed instance** that first-loads page 0 on its own. The old accumulation is a separate, GC-eligible entry; you don't clear it, you just stop owning it. And because the feed is a real scoped resource, a per-user feed (a scope resolver instead of `:rf.scope/global`) is dropped wholesale on `clear-scope` at logout, and a mutation can invalidate the whole feed by its `:tags` — coherence a hand-rolled app-db slice simply can't buy.

> **Auto-loading sentinel?** Want infinite *scroll* instead of a button? Wire an `IntersectionObserver` to a sentinel `div`. One catch: the observer callback fires *outside frame context* — a frame being one isolated instance of your app's state and event loop — so a bare `rf/dispatch` there raises `:rf.error/no-frame-context`. Capture a frame handle where context still exists (render or mount) and dispatch through it:
>
> ```clojure
> ;; Create at mount (Form-3), observe a sentinel div, disconnect on unmount.
> (let [{:keys [dispatch]} (rf/frame-handle)]
>   (js/IntersectionObserver.
>    (fn [entries _]
>      (when (.-isIntersecting (aget entries 0))
>        (dispatch [:rf.resource/load-more
>                   {:resource :feed/timeline :params {}
>                    :cause [:user :feed/scroll-sentinel]}])))))
> ```

> **Bidirectional feeds (prepend)?** The `:prev-page-param` derivation mirror is defined (declare it just like `:next-page-param`, computed from the *first* page, and `:has-prev-page?` becomes observable), but the prepend event `:rf.resource/load-prev` is deferred until a consumer needs it — v1 ships next-direction `load-more` only.

## Scroll position is not a fact

With feeds you'll be tempted to dispatch scroll positions into app-db. Don't — and here's the test that settles it, lifted from [Where should this value live?](../where-state-lives.md): would any handler or sub *decide* anything on this value, and would it mean anything after a time-travel restore or on a server render? A pixel offset fails both tests cold. It's host state, and the framework treats it as such.

The route's `:scroll` key declares the behaviour (`:top` in the numbered example above; leave it undeclared and the default is `:top` on forward navigation, saved-position restore on Back/Forward). That saved-position cache is kept host-side, deliberately outside app-db. Dispatching on every scroll tick would also flood the event tape with noise no tool can use — you'd be paying the cost of an event for a value no event reads.

So what *is* a fact? The page number (in the URL), the accumulated pages (the infinite resource entry, runtime-owned), and — if you need a resume point — a real domain fact like the last-read item id. Store those, and let the router own the pixels.

The complete worked version of the infinite half — route-owned page-0 ensure, the passive `infinite-state` view, the causal load-more, the `nil` terminal, and the `:page-error` channel — is in [`examples/reagent/infinite_feed/`](../../../examples/reagent/infinite_feed/). The numbered-pages half — tag filters, a session-scoped feed, profile tabs — is in [`examples/reagent/realworld_resources/`](../../../examples/reagent/realworld_resources/). The normative spec is [Spec 016 §Infinite resources and load-more feeds](../../../spec/016-Resources.md#infinite-resources-and-load-more-feeds).

---

**You can now:**

- paginate a resource-backed list where each page is its own cache entry, keyed from the URL
- keep the previous page on screen while the next loads — no skeleton flash, and back-navigation is a cache hit
- build a load-more feed as a first-class infinite resource: one growing entry, a cursor derived from the loaded tail, accumulation driven by a causal `:rf.resource/load-more` — no app-db slice, no cursor threading, no append reducer
- read the whole feed UI off `:rf.resource/infinite-state` (`:items`, `:has-next-page?`, `:fetching-next?`, `:page-error`) and dispatch one event
- tell paging facts (page number, accumulated pages) from host state (scroll pixels), and store only the facts
