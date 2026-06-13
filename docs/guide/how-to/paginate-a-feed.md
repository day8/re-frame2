# Paginate a feed

You have a list with more rows than you want to fetch at once. This recipe builds two pagination shapes, and they behave differently on purpose. With **numbered pages**, page 2 *replaces* page 1 on screen — think search results or admin tables. With **load more**, page 2 *appends* to what's already there, the way a social feed works. Both assume a resource-backed list — a resource being a declared, cached server query — like the one [Server state: resources](../concepts/server-state.md) sets up.

If you're coming from TanStack Query, you have a head start here. The first shape is `useQuery` with the page in the `queryKey`, plus `keepPreviousData`. The second is `useInfiniteQuery`. The first maps over directly: put the page in the resource's params, and use `:keep-previous?` for the no-flicker behaviour. There are two twists worth flagging up front — the page also lives in the URL, and views never fetch. The second shape diverges more deliberately. There's no `useInfiniteQuery` counterpart yet, because infinite resources are deferred. Instead, the accumulated feed is *your app state*, grown by a reply event — it's not a cache entry at all.

> **A page is part of the resource's identity, not a mutation of it.** Paging doesn't change "the feed". It reads a different, separately cached value.

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
(rf/reg-event-fx :home/go-to-page
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

## Load more: an append-feed is app state, not a cache entry

A load-more feed breaks the cache model on purpose, so it's worth understanding why before you reach for resources here. What's on screen is no longer "the server's page N". It's everything this user has loaded so far this session. That accumulated list is a session fact, and facts live in app-db. So don't contort resources into this shape. Instead, use a [managed HTTP request](../concepts/http.md) — an effect the framework runs and routes the reply back through an event — whose reply event appends:

```clojure
(rf/reg-event-fx :feed/load-more
  (fn [{:keys [db]} _]
    (if (:feed/loading-more? db)
      {}                                       ;; already in flight — ignore the click
      {:db (assoc db :feed/loading-more? true)
       :fx [[:rf.http/managed
             {:request    {:method :get
                           :url    "/api/articles"
                           :params {:limit  page-size
                                    :offset (count (:feed/articles db))}}
              :decode     :json
              :on-success [:feed/page-loaded]
              :on-failure [:feed/load-failed]}]]})))

;; The runtime APPENDS the reply payload as the final event argument —
;; the continuation is data on the event tape, not a callback.
(rf/reg-event-db :feed/page-loaded
  (fn [db [_ {:keys [value]}]]
    (-> db
        (update :feed/articles (fnil into []) (:articles value))
        (assoc  :feed/total (:total value))
        (dissoc :feed/loading-more? :feed/load-error))))

(rf/reg-event-db :feed/load-failed
  (fn [db [_ {:keys [failure]}]]
    (-> db
        (assoc  :feed/load-error failure)
        (dissoc :feed/loading-more?))))
```

That `:on-success` target — an event, the thing your handler reacts to — receives the uniform reply envelope, appended for you. Every managed effect completes through this same no-`await` shape, explained in [No await: continuations are data](../explanation/continuations-are-data.md). And the *next* page needs no counter, because the offset is derived from how many rows are already loaded.

The view is a list plus one button. Dispatch `[:feed/load-more]` from the route's `:on-match` to load the first page on entry:

```clojure
(rf/reg-sub :feed/articles      (fn [db _] (:feed/articles db)))
(rf/reg-sub :feed/loading-more? (fn [db _] (boolean (:feed/loading-more? db))))
(rf/reg-sub :feed/load-error    (fn [db _] (:feed/load-error db)))
(rf/reg-sub :feed/more?
  (fn [db _]
    (or (nil? (:feed/total db))
        (< (count (:feed/articles db)) (:feed/total db)))))

(rf/reg-view feed []
  (let [articles @(subscribe [:feed/articles])
        loading? @(subscribe [:feed/loading-more?])
        more?    @(subscribe [:feed/more?])
        error    @(subscribe [:feed/load-error])]
    [:div
     (into [:div] (for [a articles] [article-row a]))
     (when error [list-error error])           ;; rows already loaded stay visible
     (when more?
       [:button {:disabled loading?
                 :on-click #(dispatch [:feed/load-more])}
        (cond loading? "Loading…" error "Retry" :else "Load more")])]))
```

You've left the resource machinery behind, which means its guarantees are now yours to keep. If the feed is per-user, clear `:feed/articles` at logout yourself. You also decide when the list resets — on route leave, on pull-to-refresh, wherever fits — and `(assoc db :feed/articles [] :feed/total nil)` is the whole reset.

!!! note "Auto-loading sentinel?"

    An `IntersectionObserver` callback fires outside frame context — a frame being one isolated instance of your app's state and event loop. A bare `rf/dispatch` there raises `:rf.error/no-frame-context`. Capture a frame handle where context exists (render or mount) and dispatch through it:

    ```clojure
    ;; Create at mount (Form-3), observe a sentinel div, disconnect on unmount.
    (let [{:keys [dispatch]} (rf/frame-handle)]
      (js/IntersectionObserver.
       (fn [entries _]
         (when (.-isIntersecting (aget entries 0))
           (dispatch [:feed/load-more])))))
    ```

## Scroll position is not a fact

With feeds you'll be tempted to dispatch scroll positions into app-db. Don't — and here's the test to settle it, from [Where should this value live?](../where-state-lives.md): would any handler or sub *decide* anything on this value, and would it mean anything after a time-travel restore or on a server render? A pixel offset fails both. It's host state, and the framework treats it that way. The route's `:scroll` key declares the behaviour (`:top` above; leave it undeclared and the default is `:top` on forward navigation, saved-position restore on Back/Forward). That saved-position cache is kept host-side, deliberately outside app-db. Dispatching on scroll ticks would also flood the event tape with noise no tool can use. What *is* a fact: the page number (URL), the rows loaded so far (app-db), and — if you need a resume point — a real domain fact like the last-read article id. Store those, and let the router own the pixels.

The complete worked version of the numbered-pages half — tag filters, a session-scoped feed, profile tabs — is [`examples/reagent/realworld_resources/`](../../../examples/reagent/realworld_resources/).

---

**You can now:**

- paginate a resource-backed list where each page is its own cache entry, keyed from the URL
- keep the previous page on screen while the next loads — no skeleton flash, and back-navigation is a cache hit
- build a load-more feed whose accumulated rows live in app-db, grown by an appended reply event
- tell paging facts (page number, loaded rows) from host state (scroll pixels), and store only the facts
