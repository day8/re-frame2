# Paginate a feed

You have a list with more rows than you want to fetch at once. This recipe builds two pagination shapes, and they behave differently on purpose. With **numbered pages**, page 2 *replaces* page 1 on screen — think search results or admin tables. With **load more**, page 2 *appends* to what's already there, the way a social feed works. Both are built on resources — a resource being a declared, cached server query — like the one [Server state: resources](../concepts/server-state.md) sets up.

If you're coming from TanStack Query, you have a head start here. The first shape is `useQuery` with the page in the `queryKey`, plus `keepPreviousData`. The second is `useInfiniteQuery`. Both map over directly. For numbered pages: put the page in the resource's params, and use `:keep-previous?` for the no-flicker behaviour — with two twists, the page also lives in the URL, and views never fetch. For load-more: re-frame2 has a first-class **infinite resource** (the `useInfiniteQuery` counterpart) — one growing entry of accumulated pages, with a next-page cursor *derived from the last page's data* and a `:rf.resource/load-more` event. The one deliberate divergence: the cursor lives in the runtime entry and is advanced by a causal event, not by a `fetchNextPage()` call from a component — because re-frame2 views stay passive.

> **A numbered page is part of the resource's identity. An infinite feed is one identity that grows.** Paging a numbered list reads a different, separately cached value per page. An infinite feed keeps the accumulation together as one entry, so the merged list and the next cursor are one reactive value.

## Numbered pages: each page is its own cache entry

### 1. Put the page in the resource's params

Every variable that changes the server's answer belongs in params — and the page is one of them, because two pages are two different answers. So page 1 and page 2 become distinct entries under one resource:

```clojure
;; Adapted from examples/reagent/realworld_resources/resources.cljs
(def page-size 10)

(rf/reg-resource :app/articles
  {:params-schema  [:map [:page :int]]
   :scope          :rf.scope/global
   :request        (fn [{:keys [page]} _ctx]
                     {:request {:method :get
                                :url    "/api/articles"
                                :params {:limit  page-size
                                         :offset (* page-size (dec page))}}
                      :decode  :json})
   :stale-after-ms 60000
   :gc-after-ms    (* 5 60 1000)})
```

(The server replies `{:articles [...] :total 290}` — adapt the field names to match yours. Add `:tags` when writes need to invalidate this list, covered in [Invalidate after a mutation](invalidate-after-a-mutation.md).)

### 2. Let the URL carry the page

The current page tells you *where the user is*, and that's the URL's job — not app-db's, where app-db is your app's single state map. The route validates the `?page=` query param, feeds it into the resource's params, and opts into keeping the old page visible while the new one loads:

```clojure
;; Adapted from examples/reagent/realworld_resources/routing.cljs
(rf/reg-route :app/home
  {:path      "/"
   :query     [:map [:page {:optional true} :int]]
   :scroll    :top
   :resources [{:resource       :app/articles
                :params         (fn [route] {:page (or (get-in route [:query :page]) 1)})
                :blocking?      true
                :keep-previous? true}]})
```

Now route entry loads the right page, owns it while you're there, and releases it when you leave. Once a page is unowned, it falls back to the normal staleness and garbage-collection policy — so nothing leaks.

!!! warning "Both seams must compute the same key"

    Params identity is exact. `{:page nil}` and `{:page 1}` are *different* cache entries. A view subscribing under one key while the route ensured the other will read `:idle` forever — and that's a confusing bug to chase, because everything looks wired up. So normalize the same way everywhere: `(or page 1)` on the route side (above) and on the sub side (below).

### 3. Page by navigating

Here's the mental shift: changing pages is a navigation, not a fetch. Swap only `?page=`. Drop it for page 1 so the first page has one canonical URL, rather than `/` and `/?page=1` both pointing at the same list:

```clojure
(rf/reg-event :home/go-to-page
  (fn [_ [_ page]]
    {:fx [[:dispatch [:rf.route/navigate :app/home {}
                      {:query (if (> page 1) {:page page} {})}]]]}))

(rf/reg-sub :home/page
  :<- [:rf.route/query]
  (fn [q _] (or (:page q) 1)))
```

A filter — a tag, a search term — is just one more params key and one more query param. Keep it across page changes via the route's `:query-retain`. But reset to page 1 when the *filter* changes, because a new filter is a fresh list and page 2 of the old filter means nothing.

### 4. Show the old page while the new one loads

This is the part that makes pagination feel smooth instead of janky. With `:keep-previous?`, while page 2 first-loads the state carries `:previous? true` and `:previous-data` — page 1's rows, projected, never inserted into page 2's entry. Render those instead of a skeleton:

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

(Here `dispatch` and `subscribe` are the frame-bound bindings `reg-view` injects — dispatch sends an event, subscribe reads a derived value — and `list-skeleton`, `list-error`, and `article-row` are your own views.)

Now watch it work. Click through to page 2 with Xray open. The navigation event row shows the ensure it caused under the `{:page 2}` key, and the entry moves through `:loading` to `:loaded`. Click back to page 1, and you'll see the same key, still fresh — a cache hit, no network. That's the payoff of treating pages as identity: back-navigation is free.

> **Coming from re-frame v1?** The page-keyed cache map, the `:loading?` flags, the "don't blank the list while fetching" dance — all the framework's job now; your app-db holds none of it.

## Load more: an infinite resource is one growing entry

A load-more feed is a different shape on purpose, so it's worth understanding the model before you reach for it. What's on screen is no longer "the server's page N". It's everything this user has accumulated so far: page 1, then page 1+2, then page 1+2+3, rendered as one growing list. re-frame2 models that as a first-class **infinite resource** — *one* cache entry whose value is an ordered, growing sequence of pages, with the *next* page's cursor derived from the *last* page you loaded.

Crucially, this is **not** an app-db slice you maintain by hand. Before infinite resources existed, an app rolled this itself: a list slice in app-db, a `:loading-more?` flag, a cursor slice, an append reducer on the success event, an "end of feed" flag, dedupe of an in-flight click, reset-on-filter-change. The infinite resource owns *all* of that — and, because it's a real resource, it also participates in scope clearing, tag invalidation, SSR, and time-travel restore, which a hand-rolled slice cannot.

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

   ;; :request keeps its (params ctx) shape; for an infinite resource the
   ;; RESERVED ctx (its second arg) carries THIS page's context — the resolved
   ;; cursor (nil for the first page) and the page index. No new arity.
   :request
   (fn [_feed-params {:rf.resource/keys [page-param page-index]}]
     {:request {:method :get
                :url    "/api/timeline"
                :params (cond-> {:limit page-size :page-index page-index}
                          page-param (assoc :cursor page-param))}
      :decode  :json})

   ;; Derive the NEXT page param from the last loaded page. Returning nil is the
   ;; SINGLE terminal signal (no more pages); `:has-next-page?` is then false.
   :next-page-param
   (fn [last-page _all-pages]
     (get-in last-page [:page-info :next-cursor]))   ;; nil ⇒ end of feed

   ;; The pages are ENVELOPED ({:items [...] :page-info {…}}), so the runtime
   ;; needs this accessor to flatten each page into the merged list (below).
   :page->items    :items

   :stale-after-ms 60000
   :gc-after-ms    (* 5 60 1000)})
```

The first page is fetched with `:page-param nil` (TanStack's `initialPageParam`). Each load-more passes the cursor your `:next-page-param` derived from the tail. Two `load-more` calls on the same feed don't make two cache keys — they extend one entry. Only the *identity params* (filter, sort, search) name the feed; change them and you get a different feed instance, not a mutation of this one.

!!! warning "`:page->items` is required for enveloped pages — loud, not magic"

    If a page is **already a vector** (the server returns `[item, item, …]`), it flattens by identity and you need no accessor. But a feed whose page is an **envelope** — `{:items [...] :page-info {…}}`, the common cursor-paginated shape — **must** declare `:page->items` (a keyword key like `:items`, or a `(fn [page] …)`). The runtime does not guess `:items`/`:data`; a non-vector page with no accessor raises `:rf.error/infinite-missing-page-accessor` at the merge, rather than silently flattening the wrong thing.

### 2. Let the route own the feed (it ensures page 0)

A route declares an infinite resource exactly as it declares any resource. Route entry ensures **page 0** — the first load only, not the whole accumulation — under the route's owner; route leave releases it:

```clojure
(rf/reg-route :app/timeline
  {:path  "/timeline"
   :resources
   [{:resource  :feed/timeline
     :params    (fn [_route] {})
     :blocking? true}]})        ;; :blocking? blocks on page 0 only
```

### 3. Read the merged list passively; load-more is a causal event

The view reads the combined `[:rf.resource/infinite-state …]` view-model and dispatches one event. It never fetches and never advances a cursor:

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

Read off the view-model and you have the whole feed UI: `:items` (the merged list), `:has-next-page?` (show the button or the end marker), `:fetching-next?` (a load-more in flight — distinct from `:fetching?`, a whole-feed refresh), and `:page-error` (a load-more failure). The accumulated pages stay visible through a load-more — there's no skeleton flash, because the feed already has data.

> **`load-more` carries a `:cause`, not an `:owner`.** The route already *owns* the feed for its lifetime, keeping it alive. A load-more *extends* that one owned entry — it doesn't intend to keep anything alive on its own, so it omits `:owner` and supplies only `:cause` (owner keeps alive; cause explains why). This is the same owner-vs-cause distinction a manual refresh makes.

### What the runtime does that you no longer write

- **The cursor.** `:next-page-param` derives the next page's param from the loaded tail; the runtime stores it on the entry and passes it to the next `:request`. You never thread a cursor through app-db.
- **The append.** A page success appends to the one entry's page vector with structural sharing — prior pages stay identical. You write no append reducer.
- **The in-flight UI.** `:fetching-next?` is true while a load-more page is fetching; a second load-more while one is in flight dedupes (no double-fetch). You keep no `:loading-more?` flag.
- **The terminal.** `:next-page-param` returning `nil` is the single end-of-feed signal; `:has-next-page?` reads it. A load-more past the end is a no-op (no request).
- **The error.** A load-more failure keeps the feed and surfaces `:page-error` ("couldn't load more — retry"), separate from a first-load failure.

### Refetch and reset

`:rf.resource/refetch` on a feed is **window-preserving by default**: the accumulated pages stay rendered until their replacement succeeds, so a focus/reconnect/invalidation-driven refetch never collapses a loaded feed back to page 0. Two opt-ins ship from day one if you want different behaviour: `:refetch {:refetch-all-pages? true}` re-fetches every accumulated page (TanStack parity), and `:refetch {:refetch-window n}` bounds how much of the accumulation is refreshed.

Resetting on a filter change needs no code: a different filter is a different *identity params* value, so it's a **different feed instance** that first-loads page 0 on its own. The old accumulation is a separate, GC-eligible entry. And because the feed is a real scoped resource, a per-user feed (a scope resolver instead of `:rf.scope/global`) is dropped wholesale on `clear-scope` at logout, and a mutation can invalidate the whole feed by its `:tags` — coherence a hand-rolled app-db slice can't get.

!!! note "Auto-loading sentinel?"

    An `IntersectionObserver` callback fires outside frame context — a frame being one isolated instance of your app's state and event loop. A bare `rf/dispatch` there raises `:rf.error/no-frame-context`. Capture a frame handle where context exists (render or mount) and dispatch through it:

    ```clojure
    ;; Create at mount (Form-3), observe a sentinel div, disconnect on unmount.
    (let [{:keys [dispatch]} (rf/frame-handle)]
      (js/IntersectionObserver.
       (fn [entries _]
         (when (.-isIntersecting (aget entries 0))
           (dispatch [:rf.resource/load-more
                      {:resource :feed/timeline :params {}
                       :cause [:user :feed/scroll-sentinel]}])))))
    ```

!!! note "Bidirectional feeds (prepend)?"

    The `:prev-page-param` derivation mirror is defined (declare it the same way as `:next-page-param`, computed from the *first* page, and `:has-prev-page?` becomes observable), but the prepend event `:rf.resource/load-prev` is deferred until a consumer needs it — v1 ships next-direction `load-more` only.

## Scroll position is not a fact

With feeds you'll be tempted to dispatch scroll positions into app-db. Don't — and here's the test to settle it, from [Where should this value live?](../where-state-lives.md): would any handler or sub *decide* anything on this value, and would it mean anything after a time-travel restore or on a server render? A pixel offset fails both. It's host state, and the framework treats it that way. The route's `:scroll` key declares the behaviour (`:top` in the numbered example above; leave it undeclared and the default is `:top` on forward navigation, saved-position restore on Back/Forward). That saved-position cache is kept host-side, deliberately outside app-db. Dispatching on scroll ticks would also flood the event tape with noise no tool can use. What *is* a fact: the page number (URL), the accumulated pages (the infinite resource entry, runtime-owned), and — if you need a resume point — a real domain fact like the last-read item id. Store those, and let the router own the pixels.

The complete worked version of the infinite half — route-owned page-0 ensure, the passive `infinite-state` view, the causal load-more, the nil terminal, and the `:page-error` channel — is [`examples/reagent/infinite_feed/`](../../../examples/reagent/infinite_feed/). The numbered-pages half — tag filters, a session-scoped feed, profile tabs — is in [`examples/reagent/realworld_resources/`](../../../examples/reagent/realworld_resources/). The normative spec is [Spec 016 §Infinite resources and load-more feeds](../../../spec/016-Resources.md#infinite-resources-and-load-more-feeds).

---

**You can now:**

- paginate a resource-backed list where each page is its own cache entry, keyed from the URL
- keep the previous page on screen while the next loads — no skeleton flash, and back-navigation is a cache hit
- build a load-more feed as a first-class infinite resource: one growing entry, a cursor derived from the loaded tail, accumulation driven by a causal `:rf.resource/load-more` — no app-db slice, no cursor threading, no append reducer
- read the whole feed UI off `:rf.resource/infinite-state` (`:items`, `:has-next-page?`, `:fetching-next?`, `:page-error`) and dispatch one event
- tell paging facts (page number, accumulated pages) from host state (scroll pixels), and store only the facts
