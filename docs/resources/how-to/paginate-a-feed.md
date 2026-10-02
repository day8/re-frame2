# Paginate a feed

Put the page number in the URL and in the resource's params. Each page then has
its own cached value, and Back returns to an already loaded page.

This recipe replaces the visible article list on each page turn. For a list that
keeps every loaded page on screen, use [Load more](load-more.md).

## Numbered pages

### 1. Put the page in the resource's params

The examples use the Conduit response shape, `{:articles [...] :articlesCount N}`.
These reads are anonymous and send no authorization header. If the response
depends on the signed-in viewer, declare a [scope resolver](../concepts.md#scope-whose-cache)
instead of global scope.

```clojure
;; cf. examples/real-apps/realworld_resources/resources.cljs
(ns app.articles
  (:require [re-frame.core :as rf]
            [re-frame.resources]
            [re-frame.http.managed]
            [re-frame.schemas]
            [re-frame.routing]))

(def page-size 10)

(rf/reg-resource :article/list
  {:params-schema [:map [:page :int]]
   :scope :rf.scope/global}
  (fn [{:keys [page]} _ctx]
    {:request {:method :get
               :url "/api/articles"
               :params {:limit page-size
                        :offset (* page-size (dec page))}}
     :decode :json}))
```

Page 1 and page 2 have different params, so they are different entries. Freshness
and [cache tags](../glossary.md#cache-tag) work as on any other resource.

### 2. Let the URL carry the page

A route computes the resource params from `?page=`. Omitting the query param
means page 1:

```clojure
;; cf. examples/real-apps/realworld_resources/routing.cljs
(rf/reg-route :article/home
  {:query [:map [:page {:optional true} :int]]
   :scroll :top
   :resources [{:resource :article/list
                :params (fn [route]
                          {:page (or (get-in route [:query :page]) 1)})
                :blocking? true
                :keep-previous? true}]}
  "/")

(rf/reg-sub :article/page {:inputs [[:rf.route/query]]}
  (fn [[query] _] (or (:page query) 1)))
```

The route owns the active page's entry and releases it when the identity changes
or the route leaves. `:blocking? true` reports first-load progress through
`:rf.route/transition` and gives SSR a wait point; the route commits immediately.

`:keep-previous? true` supplies the old page's data while the new key loads.
The [schemas artefact](../../core/how-to/validate-with-schemas.md) required above
validates the declared query and resource params.

### 3. Page by navigating, not by fetching

Use `route-link` for page links. It builds shareable URLs and preserves browser
behaviour such as opening a link in a new tab:

```clojure
(rf/reg-view page-links [{:keys [page-count]}]
  (let [current @(subscribe [:article/page])]
    (into [:nav {:aria-label "Article pages"}]
          (for [page (range 1 (inc page-count))]
            ^{:key page}
            [rf/route-link {:to :article/home
                            :query (if (= page 1) {} {:page page})
                            :aria-current (when (= page current) "page")}
             (str page)]))))
```

Changing `?page=` makes the route ensure the corresponding entry. There is no
fetch in the link or view.

A filter belongs in both the route query and the resource params. Preserve it
in page-link destinations; when it changes, drop `:page` to return to page 1.
[URL query updates](../../routing/concepts.md#carrying-global-state-through-the-url)
cover adding a filter without losing other query values.

### 4. Show the old page while the new one loads

The view must compute the same params as the route. Both use page 1 when the URL
omits `?page=`. During a page turn, `:previous-data` is available for display
without being copied into the new entry.

```clojure
;; cf. examples/real-apps/realworld_resources/views.cljs
(rf/reg-view article-row [{:keys [article]}]
  [:li (:title article)])

(rf/reg-view article-list []
  (let [page @(subscribe [:article/page])
        query {:resource :article/list :params {:page page}}
        state @(subscribe [:rf/resource query])
        data (or (:data state) (:previous-data state))]
    (cond
      (or (= :idle (:status state))
          (and (:loading? state) (not (:previous? state))))
      [:p "Loading articles…"]

      (and (:error state) (not (:has-data? state)) (not (:previous? state)))
      [:div
       [:p "Could not load this page."]
       [:button {:on-click #(dispatch [:rf.resource/refetch query])} "Retry"]]

      :else
      [:div
       (when (:previous? state) [:p "Showing the previous page."])
       (when (or (:loading? state) (:fetching? state)) [:p "Loading page " page "…"])
       (when (or (:error state) (:refresh-error state))
         [:p "Could not refresh these rows. "
          [:button {:on-click #(dispatch [:rf.resource/refetch query])} "Retry"]])
       (when (empty? (:articles data)) [:p "No articles."])
       (into [:ul] (for [article (:articles data)]
                     ^{:key (:slug article)} [article-row {:article article}]))
       [page-links {:page-count
                    (js/Math.ceil (/ (or (:articlesCount data) 0) page-size))}]])))
```

Render this list for `:article/home` in the application's root view. Click page 2,
then Back: a fresh page 1 is served from cache. Repeat with a slow network and
verify that page 1 remains visible while page 2 loads.

## Troubleshooting

| Symptom | Cause | Fix |
|---|---|---|
| A page stays `:idle` | The route and view use different params or scopes | Compute the same page default and inherit the registration's scope |
| Every page turn shows a blank skeleton | Previous data is not rendered | Set `:keep-previous?` and read `:previous-data` |
| A refresh hides the existing rows | First-load and refresh failures share one branch | Render `:refresh-error` beside the loaded rows |
| A viewer's flags appear for another user | A viewer-dependent response uses global scope | Use a scope resolver consistently on route and read |

## Load more: an infinite resource is one growing entry

<a id="1-register-the-feed-with-infinite-true"></a>
<a id="2-let-the-route-own-the-feed-it-ensures-page-0"></a>
<a id="3-read-the-merged-list-load-more-with-an-event"></a>
<a id="what-the-runtime-does-for-you"></a>
<a id="refetch-and-reset"></a>

When each page should append to the list, an infinite resource keeps the cursor
and pages in one entry. [Load more](load-more.md) shows that registration, the
button, and recovery after a later page fails.

## Scroll position: let the route handle it

`:scroll :top` on this recipe's route resets scroll when changing pages. The
default for Back/Forward restores the saved position. For a growing feed,
[route scroll policy](../../routing/concepts.md#fragments-and-scrolling)
keeps this browser state outside app-db.

## Advanced

<a id="keep-a-list-fresh-on-an-interval"></a>
<a id="validate-and-classify-each-page"></a>
<a id="feeds-and-pages-under-ssr"></a>

Polling is independent of pagination: `:poll-interval-ms` refreshes a resource
while owned and visible. [The model](../concepts.md#owners-causes-refetch-rules)
explains owners; the [polling reference](../../api/re-frame.resources.md#polling)
records timing and revalidation options.

For an infinite feed, [per-page validation](load-more.md#advanced) applies to
each reply. [SSR](../../ssr/concepts.md#the-client-side-hydrate-then-verify)
hydrates eligible cached entries; `:blocking?` determines whether a route's
first load delays server rendering.
