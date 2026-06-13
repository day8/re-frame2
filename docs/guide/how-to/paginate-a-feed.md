# Paginate a feed

You have a list with more rows than you want to fetch at once. This recipe builds two pagination shapes. **Numbered pages**: page 2 *replaces* page 1 on screen (search results, admin tables). **Load more**: page 2 *appends* (social feeds). It assumes a resource-backed list like the one [Server state: resources](../concepts/server-state.md) sets up.

**The anchor.** Think TanStack Query. The first shape is `useQuery` with the page in the `queryKey` plus `keepPreviousData`. The second is `useInfiniteQuery`. The first maps over directly: put the page in the resource's params, use `:keep-previous?` for the no-flicker behaviour. Two twists. The page also lives in the URL. Views never fetch. The second shape diverges on purpose. There is no `useInfiniteQuery` counterpart yet (infinite resources are deferred). The accumulated feed is *your app state*, grown by a reply event. It is not a cache entry.

> **A page is part of the resource's identity, not a mutation of it.** Paging doesn't change "the feed". It reads a different, separately cached value.

## Numbered pages: each page is its own cache entry

### 1. Put the page in the resource's params

Every variable that changes the server's answer belongs in params. The page is one of them. Page 1 and page 2 become distinct entries under one resource:

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

(The server replies `{:articles [...] :total 290}` — adapt the field names. Add `:tags` when writes need to invalidate this list — [Invalidate after a mutation](invalidate-after-a-mutation.md).)

### 2. Let the URL carry the page

The current page tells you *where the user is*. That's the URL's job, not app-db's. The route validates `?page=`, feeds it into the resource's params, and opts into keeping the old page visible while the new one loads:

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

Now route entry loads the right page, owns it while you're there, and releases it when you leave. Unowned pages fall to the normal staleness and GC policy.

> **Both seams must compute the same key.** Params identity is exact. `{:page nil}` and `{:page 1}` are *different* cache entries. A view subscribing under one while the route ensured the other reads `:idle` forever. So normalize the same way everywhere: `(or page 1)` on the route side (above) and the sub side (below).

### 3. Page by navigating

Changing pages is a navigation, not a fetch. Swap only `?page=`. Drop it for page 1 so the first page has one canonical URL:

```clojure
(rf/reg-event-fx :home/go-to-page
  (fn [_ [_ page]]
    {:fx [[:dispatch [:rf.route/navigate :app/home {}
                      {:query (if (> page 1) {:page page} {})}]]]}))

(rf/reg-sub :home/page
  :<- [:rf.route/query]
  (fn [q _] (or (:page q) 1)))
```

A filter (tag, search term) is one more params key and query param. Keep it across page changes via the route's `:query-retain`. Reset to page 1 when the *filter* changes — a new filter is a fresh list.

### 4. Show the old page while the new one loads

With `:keep-previous?`, while page 2 first-loads the state carries `:previous? true` and `:previous-data` (page 1's rows, projected — never inserted into page 2's entry). Render those instead of a skeleton:

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

(`dispatch`/`subscribe` are the frame-bound bindings `reg-view` injects; `list-skeleton`, `list-error`, `article-row` are your own views.)

Now watch it work. Click to page 2 with Xray open. The navigation event row shows the ensure it caused under the `{:page 2}` key, and the entry moves through `:loading` to `:loaded`. Click back to page 1. Same key, still fresh — a cache hit, no network. That's the payoff of pages-as-identity: back-navigation is free.

> **Coming from re-frame v1?** The page-keyed cache map, the `:loading?` flags, the "don't blank the list while fetching" dance — all the framework's job now; your app-db holds none of it.

## Load more: an append-feed is app state, not a cache entry

A load-more feed breaks the cache model on purpose. What's on screen is no longer "the server's page N". It's everything this user has loaded so far this session. That accumulated list is a session fact, and facts live in app-db. Don't contort resources into this shape. Use a [managed HTTP request](../concepts/http.md) whose reply event appends:

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

That `:on-success` target receives the uniform reply envelope, appended for you. Every managed effect completes through this same no-`await` shape ([No await: continuations are data](../explanation/continuations-are-data.md)). The *next* page needs no counter. The offset is derived from how many rows are already loaded.

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

You've left the resource machinery behind, so its guarantees are now yours to keep. If the feed is per-user, clear `:feed/articles` at logout yourself. Decide when the list resets (route leave, pull-to-refresh) — `(assoc db :feed/articles [] :feed/total nil)` is the whole reset.

> **Auto-loading sentinel?** An `IntersectionObserver` callback fires outside frame context — a bare `rf/dispatch` there raises `:rf.error/no-frame-context`. Capture a frame handle where context exists (render/mount) and dispatch through it:
>
> ```clojure
> ;; Create at mount (Form-3), observe a sentinel div, disconnect on unmount.
> (let [{:keys [dispatch]} (rf/frame-handle)]
>   (js/IntersectionObserver.
>    (fn [entries _]
>      (when (.-isIntersecting (aget entries 0))
>        (dispatch [:feed/load-more])))))
> ```

## Scroll position is not a fact

With feeds you'll be tempted to dispatch scroll positions into app-db. Don't. Run the test from [Where should this value live?](../where-state-lives.md): would any handler or sub *decide* anything on this value, and would it mean anything after a time-travel restore or on a server render? A pixel offset fails both. It's host state, and the framework treats it that way. The route's `:scroll` key declares the behaviour (`:top` above; leave it undeclared and the default is `:top` on forward navigation, saved-position restore on Back/Forward). The saved-position cache is kept host-side, deliberately outside app-db. Dispatching on scroll ticks also floods the event tape with noise no tool can use. What *is* a fact: the page number (URL), the rows loaded so far (app-db), and — if you need a resume point — a real domain fact like the last-read article id. Store those. Let the router own the pixels.

The complete worked version of the numbered-pages half — tag filters, a session-scoped feed, profile tabs — is [`examples/reagent/realworld_resources/`](../../../examples/reagent/realworld_resources/).

---

**You can now:**

- paginate a resource-backed list where each page is its own cache entry, keyed from the URL
- keep the previous page on screen while the next loads — no skeleton flash, and back-navigation is a cache hit
- build a load-more feed whose accumulated rows live in app-db, grown by an appended reply event
- tell paging facts (page number, loaded rows) from host state (scroll pixels), and store only the facts

**Next:** writes that touch a paginated list are [Invalidate after a mutation](invalidate-after-a-mutation.md); the full contract, including `:keep-previous?` semantics, is [Spec 016 — Resources](../../../spec/016-Resources.md).
