# Load more

Use an infinite resource when each new page should append to an article feed.
The cache keeps the loaded pages and next cursor; the view reads one growing
list and dispatches `:rf.resource/load-more`.

For numbered links that replace the current page, use
[Paginate a feed](paginate-a-feed.md).

## Register the feed

This endpoint returns `{:items [...] :page-info {:next-cursor ...}}`.
Each item has a stable `:slug` and a `:title`. A `nil` cursor means the feed is complete. The resource is public and has the same
response for every viewer; viewer-dependent feeds need a
[scope resolver](../concepts.md#scope-whose-cache).

```clojure
;; cf. examples/capabilities/resources/infinite_feed/core.cljs
(ns app.feed
  (:require [re-frame.core :as rf]
            [re-frame.resources]
            [re-frame.http.managed]
            [re-frame.routing]))

(rf/reg-resource :feed/articles
  {:params-schema [:map]
   :scope :rf.scope/global
   :infinite true
   :page->items :items
   :next-page-param (fn [last-page _all-pages]
                      (get-in last-page [:page-info :next-cursor]))
   :tags (fn [_params _pages] #{[:article-feed]})}
  (fn [_params {:rf.resource/keys [page-param]}]
    {:request {:method :get
               :url "/api/article-feed"
               :params (cond-> {:limit 10}
                         page-param (assoc :cursor page-param))}
     :decode :json}))
```

`:page->items` extracts each page's article rows. `:next-page-param` computes the
next cursor from the last reply. The runtime passes it to the next request as
`:rf.resource/page-param`, initially `nil`.

The cursor is separate from the resource's params. Params identify a feed, such
as a tag filter; a different filter creates another entry that starts at its
first page. Loading another page extends the current entry.

## Let the route load the first page

```clojure
(rf/reg-route :article/feed
  {:resources [{:resource :feed/articles
                :params (fn [_route] {})
                :blocking? true}]}
  "/feed")
```

The route owns the feed until it leaves. Entry ensures only the first page;
`:blocking?` waits for that page and never waits for future Load more clicks.

## Render the list and the button

The combined `:rf.resource/infinite-state` subscription returns the merged
`:items` and the loading state:

```clojure
(rf/reg-view article-feed []
  (let [query {:resource :feed/articles :params {}}
        feed @(subscribe [:rf.resource/infinite-state query])]
    (cond
      (or (= :idle (:status feed)) (:loading? feed))
      [:p "Loading articles…"]

      (:error feed)
      [:div
       [:p "Could not load the feed."]
       [:button {:on-click #(dispatch [:rf.resource/refetch query])} "Retry"]]

      :else
      [:div
       (when (empty? (:items feed)) [:p "No articles."])
       (into [:ul] (for [article (:items feed)]
                     ^{:key (:slug article)} [:li (:title article)]))
       [:button {:disabled (or (:fetching? feed) (:fetching-next? feed))
                 :on-click #(dispatch [:rf.resource/refetch query])} "Refresh"]
       (when (:fetching? feed) [:p "Refreshing…"])
       (when (:refresh-error feed)
         [:p "Could not refresh; showing the loaded articles. "
          [:button {:on-click #(dispatch [:rf.resource/refetch query])} "Retry refresh"]])
       (when (:page-error feed) [:p "Could not load another page."])
       (when (:has-next-page? feed)
         [:button {:disabled (:fetching-next? feed)
                   :on-click #(dispatch [:rf.resource/load-more
                                         (assoc query :cause [:user :articles/load-more])])}
          (cond
            (:fetching-next? feed) "Loading…"
            (:page-error feed) "Retry load more"
            :else "Load more")])
       (when-not (:has-next-page? feed) [:p "All articles loaded."])])))
```

Render `article-feed` for `:article/feed` in the application's root view.
A load-more keeps existing rows visible. The runtime ignores another load-more
while one is in flight and sends no request once the cursor is `nil`. The command
needs no owner: the route already keeps this entry alive.

The cell below runs the feed, route and view against three canned pages. Each
**Load more** appends a page to the same entry, and the button gives way to
"All articles loaded." once a page returns a `nil` cursor.

```cljs-rf2
(require '[re-frame.core :as rf]
         '[re-frame.resources]
         '[re-frame.http.managed]
         '[re-frame.routing]
         '[re-frame.http.test-support :as http-test-support])

;; Three canned pages. A stub matches the URL without :params, so this cell
;; puts the cursor in the URL itself.
(defn rows [& ns] (mapv (fn [n] {:slug (str "a" n) :title (str "Article " n)}) ns))
(http-test-support/install-managed-request-stubs!
  {[:get "/api/article-feed"]          {:reply {:ok {:items (rows 1 2 3) :page-info {:next-cursor "c2"}}}}
   [:get "/api/article-feed?cursor=c2"] {:reply {:ok {:items (rows 4 5 6) :page-info {:next-cursor "c3"}}}}
   [:get "/api/article-feed?cursor=c3"] {:reply {:ok {:items (rows 7 8)   :page-info {:next-cursor nil}}}}})

(rf/reg-resource :feed/articles
  {:params-schema [:map]
   :scope :rf.scope/global
   :infinite true
   :page->items :items
   :next-page-param (fn [last-page _all-pages]
                      (get-in last-page [:page-info :next-cursor]))
   :tags (fn [_params _pages] #{[:article-feed]})}
  (fn [_params {:rf.resource/keys [page-param]}]
    {:request {:method :get
               :url (cond-> "/api/article-feed"
                      page-param (str "?cursor=" page-param))}
     :decode :json}))

(rf/reg-route :article/feed
  {:resources [{:resource :feed/articles
                :params (fn [_route] {})
                :blocking? true}]}
  "/feed")

(rf/reg-view article-feed []
  (let [query {:resource :feed/articles :params {}}
        feed @(subscribe [:rf.resource/infinite-state query])]
    (cond
      (or (= :idle (:status feed)) (:loading? feed))
      [:p "Loading articles…"]

      (:error feed)
      [:div
       [:p "Could not load the feed."]
       [:button {:on-click #(dispatch [:rf.resource/refetch query])} "Retry"]]

      :else
      [:div
       (when (empty? (:items feed)) [:p "No articles."])
       (into [:ul] (for [article (:items feed)]
                     ^{:key (:slug article)} [:li (:title article)]))
       [:button {:disabled (or (:fetching? feed) (:fetching-next? feed))
                 :on-click #(dispatch [:rf.resource/refetch query])} "Refresh"]
       (when (:fetching? feed) [:p "Refreshing…"])
       (when (:refresh-error feed)
         [:p "Could not refresh; showing the loaded articles. "
          [:button {:on-click #(dispatch [:rf.resource/refetch query])} "Retry refresh"]])
       (when (:page-error feed) [:p "Could not load another page."])
       (when (:has-next-page? feed)
         [:button {:disabled (:fetching-next? feed)
                   :on-click #(dispatch [:rf.resource/load-more
                                         (assoc query :cause [:user :articles/load-more])])}
          (cond
            (:fetching-next? feed) "Loading…"
            (:page-error feed) "Retry load more"
            :else "Load more")])
       (when-not (:has-next-page? feed) [:p "All articles loaded."])])))

[rf/frame-root {:id :feed
                :initial-events [[:rf.route/navigate {:to :article/feed}]]
                :fx-overrides {:rf.http/managed :rf.http/managed-test-stub}}
 [article-feed]]
```

## Recover from a failed page

| Failure | Field | UI |
|---|---|---|
| First page fails before any data arrives | `:error` | Full error with `refetch` |
| Refresh of the first page fails | `:refresh-error` | Keep rows and offer refresh retry |
| A later load-more or refetch page fails | `:page-error` | Keep rows and offer the appropriate retry |

A failed load-more retains its cursor, so the same button retries that page.
A failed later-page refetch needs `refetch` instead. The two background error
fields can coexist; render both. A successful page append or replacement clears
them.

With a real endpoint, disable the network before clicking Load more. Verify
that every loaded row stays visible, then reconnect and retry the missing page.
[The runnable example](../../../examples/capabilities/resources/infinite_feed)
uses canned responses to demonstrate page appends without a backend.

## Refresh the loaded feed

By default `:rf.resource/refetch` replaces only the first page and retains the
tail. If a write can change all loaded pages, declare
`:refetch {:refetch-all-pages? true}`. Each page is reread with its saved cursor;
the server's cursor contract must support that.

Tag invalidation uses the same policy. A mutation with
`:invalidates (fn [_params _result] #{[:article-feed]})` in the feed's scope
refreshes the owned feed. For changes affecting an item in the feed, invalidate
the feed instead of patching its internal page vector.

The [infinite-resource reference](../../api/re-frame.resources.md#infinite-resources)
records windowed refetch, the complete subscription fields and supported options.

## Troubleshooting

| Symptom | Cause | Fix |
|---|---|---|
| `:rf.error/infinite-missing-next-page-param` | No next-cursor function | Register `:next-page-param` and return `nil` at the end |
| `:rf.error/infinite-missing-page-accessor` | Pages are envelopes without an item accessor | Set `:page->items :items` for this response shape |
| `:rf.warning/resource-load-more-owner-ignored` | Load-more supplied another owner | Keep the owner on the initial ensure or route |
| A filter change appends unrelated rows | The filter is absent from params | Put it in the resource's identity and route plan |

## Advanced

An `IntersectionObserver` can dispatch the same command instead of a button.
Call `rf/capture-frame` during render. Keep that capture for the mount callback,
which creates the observer, observes the sentinel and disconnects on unmount:

```clojure
;; Call at mount with the frame capture made during render.
(defn load-more-observer [{:keys [dispatch]}]
  (js/IntersectionObserver.
   (fn [entries _observer]
     (when (.-isIntersecting (aget entries 0))
       (dispatch [:rf.resource/load-more
                  {:resource :feed/articles :params {}
                   :cause [:user :articles/scroll]}])))))
```

The browser calls the observer outside frame context. Its captured `dispatch`
targets the correct frame; a bare `rf/dispatch` there raises
`:rf.error/no-frame-context`.

Validate each page by putting a Malli schema in the request's `:decode`.
Classification paths such as `:sensitive [[:data :items :author-email]]` apply
to every page's rows. [The reference](../../api/re-frame.resources.md#infinite-resources)
records both forms. Under [SSR](../../ssr/concepts.md), an eligible hydrated
feed continues loading from its saved cursor.
