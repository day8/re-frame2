# Tutorial: build a routed app

Build an articles reader with a home page, a list and a page for each article.
Add links, URL filters and a shared layout to the same app, checking the result
after each change.

Throughout, the URL is application state: you read the active route with a
subscription and change it by dispatching an event.
The finished app also [runs on this page](#run-it-on-this-page), in memory.

## Step 0 — turn routing on

Routing ships as its own package, `day8/re-frame2-routing`, so an app without
shareable URLs doesn't carry it. Add the dependency, then require the namespace once:

```clojure
;; src/app/core.cljc
;; cf. examples/capabilities/routing/routing/core.cljs
(ns app.core
  (:require [re-frame.core :as rf]
            [re-frame.routing]                              ;; turns routing on
            #?(:cljs [re-frame.adapter.reagent :as reagent-adapter])))
```

Requiring `re-frame.routing` registers `reg-route`, the route subscriptions and
`route-link`. Without it, the first `reg-route` throws
`:rf.error/routing-artefact-missing`, and the message names the namespace to require.

Every snippet below goes in this namespace. The file is `.cljc`, so the JVM can load
it too, and [Testing routes](testing.md) tests its routes, events and subscriptions
there without a browser. The Reagent adapter and the code that mounts the app need a
browser, so they sit in `#?(:cljs …)` branches, which the JVM skips.

## Step 1 — your first route, on screen

A route has an id, a metadata map and a path. Register the home page, render a page
based on the active route, and mount the app:

```clojure
(rf/reg-route :app/home {} "/")

;; Inside reg-view, `subscribe` and `dispatch` are bound to the view's frame.
;; Outside a view, name the frame: `(rf/dispatch event {:frame :app})`.
(rf/reg-view root-view []
  (case @(subscribe [:rf.route/id])
    :app/home [:h1 "Home"]
    [:h1 "Nothing here yet"]))   ;; any URL without a route yet

;; Browser only: mounting needs the adapter and the DOM.
#?(:cljs (defonce app-root (reagent-adapter/client-root)))

#?(:cljs
   (defn run []
     (rf/init! reagent-adapter/adapter)
     (reagent-adapter/render! app-root
       [rf/frame-root {:id :app :url-bound? true}
        [root-view]]
       (js/document.getElementById "app"))))
```

`:rf.route/id` is the id of the route that matches the current URL, and the root view
is a `case` over it. There is no router component to configure.

`:url-bound? true` makes the `:app` frame the owner of the browser address bar. At
startup the frame reads the current URL into its route state, and when the route
changes, the address bar follows.

**What you see:** at `/`, the page shows **Home**. Any other URL shows
**Nothing here yet**.

## Step 2 — a second page, and a link between them

Add an articles page and link to it with `route-link`:

```clojure
(rf/reg-route :app/home     {} "/")
(rf/reg-route :app/articles {} "/articles")

(rf/reg-view home-page []
  [:div
   [:h1 "Home"]
   [rf/route-link {:to :app/articles} "See the articles →"]])

(rf/reg-view articles-page []
  [:h1 "Articles"])

(rf/reg-view root-view []
  (case @(subscribe [:rf.route/id])
    :app/home     [home-page]
    :app/articles [articles-page]
    [:h1 "Nothing here yet"]))
```

`route-link` builds the `href` from the route id, so changing a route's path later
updates every link to it. It renders a real `<a>`: a plain left-click navigates
without a page reload, while cmd/ctrl-click, middle-click, hover preview and copy-link
behave as they do for any link.

**What you see:** clicking the link on Home shows **Articles** without a page reload,
and the address bar reads `/articles`.

## Step 3 — a page per item: dynamic segments

The article page needs the article's slug in the URL. A colon segment captures it:

```clojure
(rf/reg-route :app/article
  {:params [:map [:slug :string]]}     ;; coerce (and, with re-frame.schemas, validate) :slug
  "/articles/:slug")
```

The matcher fills `:slug` from the URL. The `:params`
[schema](../core/how-to/validate-with-schemas.md) declares the value's type: here,
`"intro"` is a string. Schemas coerce URL values and, when `re-frame.schemas` is
loaded, validate them too. Without that require, values are not validated.

Read the captured params with a subscription:

```clojure
(rf/reg-view article-page []
  (let [{:keys [slug]} @(subscribe [:rf.route/params])]
    [:h1 (str "Article " slug)]))
```

To link to an article, pass `:params`. A plain map stands in for your server:

```clojure
(def sample-articles
  {"intro" {:title "Intro to re-frame2" :tags #{"basics"}}
   "ssr"   {:title "Server rendering"   :tags #{"ssr"}}})

(rf/reg-view articles-page []
  [:div
   [:h1 "Articles"]
   [:ul
    (for [[slug {:keys [title]}] sample-articles]
      ^{:key slug}
      [:li [rf/route-link {:to :app/article :params {:slug slug}} title]])]])
```

Add `:app/article [article-page]` to the root `case`.

**What you see:** the list shows two links. Clicking **Intro to re-frame2** opens
`/articles/intro`, and the page reads **Article intro**.

## Step 4 — give a page its data

To run work when a page opens, list events under `:on-match`. The runtime dispatches
them each time the route activates:

```clojure
(rf/reg-route :app/article
  {:params   [:map [:slug :string]]
   :on-match [[:app/load-article]]}   ;; on entry, and on every :slug change
  "/articles/:slug")
```

`:app/load-article` is an ordinary event handler. It reads the slug from the route
state, which lives in [runtime-db](../core/glossary.md#runtime-db), the framework's
partition beside app-db. Handlers receive it under `:rf.db/runtime`:

```clojure
(rf/reg-event :app/load-article
  (fn [{:keys [db] rt :rf.db/runtime} _]
    (let [{:keys [slug]} (get-in rt [:rf.runtime/routing :current :params])]
      {:db (assoc db
                  :article/current (get sample-articles slug)
                  :article/last-read slug)})))

(rf/reg-sub :article/current (fn [db _] (:article/current db)))

(rf/reg-view article-page []
  (if-let [article @(subscribe [:article/current])]
    [:h1 (:title article)]
    [:div
     [:h1 "Article not found"]
     [rf/route-link {:to :app/articles} "All articles"]]))
```

The handler writes to [app-db](../core/app-db.md) like any other event, and the page
reads the result with a subscription. `:on-match` fires again when `:slug` or the
query changes, but not for an identical or fragment-only navigation. A path such
as `/articles/missing` matches the route even though the sample map has no such
article; the page handles that missing data separately from an unmatched URL.

The runtime dispatches `:on-match` events and moves on: it does not wait for work they
start, and an exception in the handler is reported like any other event error. The
sample map returns immediately, so there is no loading state to wait for. For
server data, declare [the route's resources](how-to/load-page-data.md) so the
runtime manages loading and late replies.

**What you see:** open `/articles/intro` and the title appears. Open another article
and `:on-match` fires again; open the same one again and it doesn't.
[Xray](../xray/index.md) shows each dispatch.

## Step 5 — when nothing matches: the 404

When no route matches, the runtime activates the reserved route
`:rf.route/not-found`, with the requested URL in its params. Register it like any other
route:

```clojure
(rf/reg-route :rf.route/not-found {} "/_404")

(rf/reg-view not-found-page []
  (let [url (:url @(subscribe [:rf.route/params]))]
    [:div
     [:h1 "Not found"]
     [:p (str "No page at " url)]
     [rf/route-link {:to :app/home} "Home"]]))

(rf/reg-view root-view []
  (case @(subscribe [:rf.route/id])
    :app/home           [home-page]
    :app/articles       [articles-page]
    :app/article        [article-page]
    :rf.route/not-found [not-found-page]
    nil                nil))
```

The not-found arm handles unmatched URLs. The `nil` arm renders no page before
the first navigation commits, including an initial URL refused by an entry guard.

If you skip this registration, an unmatched URL still activates `:rf.route/not-found`,
but the runtime emits `:rf.warning/no-not-found-route`. The not-found params can also
carry a `:reason` that separates a plain miss from a malformed URL or a failed schema;
see [Not found is a route you register](concepts.md#not-found-is-a-route-you-register).

**What you see:** visit `/nonsense` and your 404 page shows `/nonsense`.

## Step 6 — the Back button and deep links

Nothing to add: `:url-bound? true` from Step 1 already provides this. Because the
`:app` frame owns the address bar:

- at startup it reads the current URL, so a deep link or a refresh opens the right page;
- each navigation updates the address bar;
- Back and Forward dispatch an ordinary route-change event to the frame.

A frame without `:url-bound?` routes in memory only, which is what a test frame wants.

`frame-root` creates the frame if it doesn't exist. In a larger app you can create it
with `rf/make-frame` and scope views to it with `rf/frame-provider`; the routing
behaviour is the same.

Your web server must serve the app's HTML for `/articles/intro` and other app paths,
as well as `/`. If it only serves `/`, links work until you refresh. On a static
host without rewrite support, use hash routing; [Configure browser URLs](how-to/configure-browser-urls.md)
shows both setups and deployment under a subpath.

**What you see:** paste `/articles/intro` into the address bar and the app opens on that
article. Go Home → Articles → an article, then press Back twice: each press returns to
the previous page.

## Step 7 — a shared layout

Article pages should sit inside a shared section shell with a "back to all articles"
nav. Give a route a `:parent`, and `:rf.route/chain` returns the active route with its
ancestors, which you use to wrap the page in each ancestor's shell.

`reg-route` replaces a route's whole metadata map, so when you add `:parent`, keep the
keys from Step 4:

```clojure
(rf/reg-route :app/articles {} "/articles")
(rf/reg-route :app/article  {:parent   :app/articles
                             :params   [:map [:slug :string]]
                             :on-match [[:app/load-article]]}
  "/articles/:slug")
```

`:rf.route/chain` lists the root-most route first: on `/articles/intro` it is
`[:app/articles :app/article]`. The last id picks the page, and each id before it may
wrap the page in a shell:

```clojure
(defn page-for [route-id]
  (case route-id
    :app/home           [home-page]
    :app/articles       [articles-page]
    :app/article        [article-page]
    :rf.route/not-found [not-found-page]
    nil                nil))

(defn ancestor-shell [route-id inner]
  (case route-id
    :app/articles [:div.articles-section
                   [:nav [rf/route-link {:to :app/articles} "← All articles"]]
                   inner]
    inner))                                  ;; no shell: return the page unchanged

(rf/reg-view root-view []
  [:div.site
   [:header "My Site " [rf/route-link {:to :app/home} "Home"]]
   (let [chain @(subscribe [:rf.route/chain])]
     (reduce (fn [inner ancestor] (ancestor-shell ancestor inner))
             (page-for (last chain))         ;; the page itself
             (reverse (butlast chain))))])   ;; ancestors, innermost first
```

On `/articles/intro` the `reduce` computes:

```clojure
(ancestor-shell :app/articles (page-for :app/article))
;; ⇒ the articles navigation wrapping [article-page]
```

A deeper chain wraps the page once per ancestor. This does the job of React Router's
`<Outlet/>` with a plain `reduce`; see the
[React Router mapping](coming-from-react-router.md).

**What you see:** `/articles/intro` renders the article inside the
**← All articles** nav, under the site header. `/articles` shows the list without the
nav, and `/` shows Home.

## Step 8 — navigate from an event

The reader can return to the article they last opened. Step 4's load event
stores its slug in `:article/last-read`. Read that state from an event handler and
return the navigation as a `:dispatch` effect:

```clojure
(rf/reg-event :article/resume
  (fn [{:keys [db]} _]
    {:fx [[:dispatch [:rf.route/navigate
                     {:to :app/article
                      :params {:slug (or (:article/last-read db) "intro")}}]]]}))

(rf/reg-view home-page []
  [:div
   [:h1 "Home"]
   [rf/route-link {:to :app/articles} "See the articles →"]
   [:button {:on-click #(dispatch [:article/resume])} "Continue reading"]])
```

`:rf.route/navigate` takes one request map. `:to` and `:params` name the destination
as they do for `route-link`. The `:dispatch` effect sends the navigation to the
same frame as the handler. A view can also dispatch it directly:
`#(dispatch [:rf.route/navigate {:to :app/home}])`.

Use `route-link` when the destination is known while rendering: it gives the
reader a real link they can copy or open in a new tab. Navigate from an event when
an app action determines the destination, as with this resume action or after a
successful save.

**What you see:** open an article, go Home and click **Continue reading**. That
article opens again. Before any article has been opened, the button opens
`/articles/intro`.

## Step 9 — query strings: filter the list

Put the list's tag filter in the URL so it survives a refresh and can be shared.
Query-string values are a separate map from path params. Declare them with `:query`:

```clojure
(rf/reg-route :app/articles
  {:query [:map [:tag {:optional true} :string]]}
  "/articles")
```

Read them with `:rf.route/query`, and link with `:query`:

```clojure
(rf/reg-view articles-page []
  (let [{:keys [tag]} @(subscribe [:rf.route/query])
        shown (if tag
                (filter (fn [[_ a]] (contains? (:tags a) tag)) sample-articles)
                sample-articles)]
    [:div
     [:h1 "Articles"]
     [:p [rf/route-link {:to :app/articles} "All"] " · "
         [rf/route-link {:to :app/articles :query {:tag "basics"}} "#basics"] " · "
         [rf/route-link {:to :app/articles :query {:tag "ssr"}} "#ssr"]]
     [:ul
      (for [[slug {:keys [title]}] shown]
        ^{:key slug}
        [:li [rf/route-link {:to :app/article :params {:slug slug}} title]])]]))
```

Declare every query key you read. A key named in the `:query` schema arrives as a
keyword (`:tag`); an undeclared one stays a string key (`"tag"`), so `(:tag query)`
would return `nil`.

To change the query without leaving the page, navigate in place: omit `:to` and pass
`:query-merge`, which merges into the current query. A `nil` value removes a key:

```clojure
;; inside a view, where `dispatch` is bound to the view's frame
[:button {:on-click #(dispatch [:rf.route/navigate {:query-merge {:tag nil}}])}
 "Clear filter"]
```

**What you see:** click **#ssr** and the address bar reads `/articles?tag=ssr`, with one
article listed. Refresh, and the filter stays.

## The complete app

??? example "All the steps in one namespace"

    ```clojure
    ;; src/app/core.cljc
    ;; cf. examples/capabilities/routing/routing/core.cljs
    (ns app.core
      (:require [re-frame.core :as rf]
                [re-frame.routing]
                #?(:cljs [re-frame.adapter.reagent :as reagent-adapter])))

    (def sample-articles
      {"intro" {:title "Intro to re-frame2" :tags #{"basics"}}
       "ssr"   {:title "Server rendering"   :tags #{"ssr"}}})

    (rf/reg-route :app/home {} "/")
    (rf/reg-route :app/articles
      {:query [:map [:tag {:optional true} :string]]}
      "/articles")
    (rf/reg-route :app/article
      {:parent   :app/articles
       :params   [:map [:slug :string]]
       :on-match [[:app/load-article]]}
      "/articles/:slug")
    (rf/reg-route :rf.route/not-found {} "/_404")

    (rf/reg-event :app/load-article
      (fn [{:keys [db] rt :rf.db/runtime} _]
        (let [{:keys [slug]} (get-in rt [:rf.runtime/routing :current :params])]
          {:db (assoc db
                      :article/current (get sample-articles slug)
                      :article/last-read slug)})))
    (rf/reg-sub :article/current (fn [db _] (:article/current db)))

    (rf/reg-event :article/resume
      (fn [{:keys [db]} _]
        {:fx [[:dispatch [:rf.route/navigate
                         {:to :app/article
                          :params {:slug (or (:article/last-read db) "intro")}}]]]}))

    (rf/reg-view home-page []
      [:div
       [:h1 "Home"]
       [rf/route-link {:to :app/articles} "See the articles →"]
       [:button {:on-click #(dispatch [:article/resume])} "Continue reading"]])

    (rf/reg-view articles-page []
      (let [{:keys [tag]} @(subscribe [:rf.route/query])
            shown (if tag
                    (filter (fn [[_ a]] (contains? (:tags a) tag)) sample-articles)
                    sample-articles)]
        [:div
         [:h1 "Articles"]
         [:p [rf/route-link {:to :app/articles} "All"] " · "
             [rf/route-link {:to :app/articles :query {:tag "basics"}} "#basics"] " · "
             [rf/route-link {:to :app/articles :query {:tag "ssr"}} "#ssr"]]
         [:ul
          (for [[slug {:keys [title]}] shown]
            ^{:key slug}
            [:li [rf/route-link {:to :app/article :params {:slug slug}} title]])]]))

    (rf/reg-view article-page []
      (if-let [article @(subscribe [:article/current])]
        [:h1 (:title article)]
        [:div
         [:h1 "Article not found"]
         [rf/route-link {:to :app/articles} "All articles"]]))

    (rf/reg-view not-found-page []
      (let [url (:url @(subscribe [:rf.route/params]))]
        [:div
         [:h1 "Not found"]
         [:p (str "No page at " url)]
         [rf/route-link {:to :app/home} "Home"]]))

    (defn page-for [route-id]
      (case route-id
        :app/home           [home-page]
        :app/articles       [articles-page]
        :app/article        [article-page]
        :rf.route/not-found [not-found-page]
        nil                nil))

    (defn ancestor-shell [route-id inner]
      (case route-id
        :app/articles [:div.articles-section
                       [:nav [rf/route-link {:to :app/articles} "← All articles"]]
                       inner]
        inner))

    (rf/reg-view root-view []
      [:div.site
       [:header "My Site " [rf/route-link {:to :app/home} "Home"]]
       (let [chain @(subscribe [:rf.route/chain])]
         (reduce (fn [inner ancestor] (ancestor-shell ancestor inner))
                 (page-for (last chain))
                 (reverse (butlast chain))))])

    #?(:cljs (defonce app-root (reagent-adapter/client-root)))

    #?(:cljs
       (defn run []
         (rf/init! reagent-adapter/adapter)
         (reagent-adapter/render! app-root
           [rf/frame-root {:id :app :url-bound? true}
            [root-view]]
           (js/document.getElementById "app"))))
    ```

### Run it on this page

The cell below runs the same routes, events and views in memory. Its frame has no
`:url-bound? true`, so it leaves this page's address bar alone and starts with a
navigation to Home in `:initial-events`. The line above the app stands in for the
address bar: it shows the URL the frame would write, and its buttons send a typed
URL the way the browser does, for Step 4's missing article and Step 5's 404. Back
and Forward need a URL-bound frame, so Step 6 has nothing to try here.

```cljs-rf2
(require '[re-frame.core :as rf]
         '[re-frame.routing :as rf.routing])

(def sample-articles
  {"intro" {:title "Intro to re-frame2" :tags #{"basics"}}
   "ssr"   {:title "Server rendering"   :tags #{"ssr"}}})

(rf/reg-route :app/home {} "/")
(rf/reg-route :app/articles
  {:query [:map [:tag {:optional true} :string]]}
  "/articles")
(rf/reg-route :app/article
  {:parent   :app/articles
   :params   [:map [:slug :string]]
   :on-match [[:app/load-article]]}
  "/articles/:slug")
(rf/reg-route :rf.route/not-found {} "/_404")

(rf/reg-event :app/load-article
  (fn [{:keys [db] rt :rf.db/runtime} _]
    (let [{:keys [slug]} (get-in rt [:rf.runtime/routing :current :params])]
      {:db (assoc db
                  :article/current (get sample-articles slug)
                  :article/last-read slug)})))
(rf/reg-sub :article/current (fn [db _] (:article/current db)))

(rf/reg-event :article/resume
  (fn [{:keys [db]} _]
    {:fx [[:dispatch [:rf.route/navigate
                     {:to :app/article
                      :params {:slug (or (:article/last-read db) "intro")}}]]]}))

(rf/reg-view home-page []
  [:div
   [:h1 "Home"]
   [rf/route-link {:to :app/articles} "See the articles →"]
   [:button {:on-click #(dispatch [:article/resume])} "Continue reading"]])

(rf/reg-view articles-page []
  (let [{:keys [tag]} @(subscribe [:rf.route/query])
        shown (if tag
                (filter (fn [[_ a]] (contains? (:tags a) tag)) sample-articles)
                sample-articles)]
    [:div
     [:h1 "Articles"]
     [:p [rf/route-link {:to :app/articles} "All"] " · "
         [rf/route-link {:to :app/articles :query {:tag "basics"}} "#basics"] " · "
         [rf/route-link {:to :app/articles :query {:tag "ssr"}} "#ssr"]]
     [:ul
      (for [[slug {:keys [title]}] shown]
        ^{:key slug}
        [:li [rf/route-link {:to :app/article :params {:slug slug}} title]])]]))

(rf/reg-view article-page []
  (if-let [article @(subscribe [:article/current])]
    [:h1 (:title article)]
    [:div
     [:h1 "Article not found"]
     [rf/route-link {:to :app/articles} "All articles"]]))

(rf/reg-view not-found-page []
  (let [url (:url @(subscribe [:rf.route/params]))]
    [:div
     [:h1 "Not found"]
     [:p (str "No page at " url)]
     [rf/route-link {:to :app/home} "Home"]]))

(defn page-for [route-id]
  (case route-id
    :app/home           [home-page]
    :app/articles       [articles-page]
    :app/article        [article-page]
    :rf.route/not-found [not-found-page]
    nil                nil))

(defn ancestor-shell [route-id inner]
  (case route-id
    :app/articles [:div.articles-section
                   [:nav [rf/route-link {:to :app/articles} "← All articles"]]
                   inner]
    inner))

(rf/reg-view root-view []
  [:div.site
   [:header "My Site " [rf/route-link {:to :app/home} "Home"]]
   (let [chain @(subscribe [:rf.route/chain])]
     (reduce (fn [inner ancestor] (ancestor-shell ancestor inner))
             (page-for (last chain))
             (reverse (butlast chain))))])

;; Demo only: this frame has no address bar, so show the URL it would
;; write, and send typed URLs the way the browser does.
(rf/reg-view address-bar []
  (let [{:keys [route-id params query]} @(subscribe [:rf/route])]
    [:p
     [:code (if (= route-id :rf.route/not-found)
              (:url params)
              (rf.routing/route-url {:to route-id :params params :query query}))]
     (for [url ["/articles/missing" "/nonsense"]]
       ^{:key url}
       [:button {:on-click #(dispatch [:rf.route/handle-url-change url])}
        (str "Type " url)])]))

[rf/frame-root {:id             :app
                :initial-events [[:rf.route/navigate {:to :app/home}]]}
 [address-bar]
 [root-view]]
```

## Troubleshooting

| Symptom | Cause | Fix |
|---|---|---|
| First `reg-route` throws `:rf.error/routing-artefact-missing` | `re-frame.routing` is not required | Add `[re-frame.routing]` to the `ns` requires |
| Clicking a link reloads the whole page | The link is a hand-written `[:a {:href …}]` | Use `rf/route-link` |
| Clicking works, but refreshing a deep link returns the host's 404 | The server does not serve the app at that path | Configure a history fallback, or use [hash routing](how-to/configure-browser-urls.md#use-hash-routing-on-a-static-host) |
| The address bar never changes, and Back does nothing | The frame has no `:url-bound? true` | Add it to `frame-root` (Step 1) |
| Root view throws `No matching clause` | A registered route has no arm in the `case` | Add the route's arm |
| `:rf.warning/no-not-found-route` on an unmatched URL | `:rf.route/not-found` is not registered | Register it (Step 5) |
| `:on-match` stopped firing after adding `:parent` | `reg-route` replaces the whole metadata map | Re-register with every key you still want (Step 7) |
| `(:tag query)` is `nil` although the URL has `?tag=` | The route does not declare `:tag` in `:query` | Declare it in the `:query` schema (Step 9) |
| Navigation is refused with `:rf.error/navigate-bad-request` | The payload is not one request map, or the map breaks a [request rule](../api/re-frame.routing.md#navigate-request-rules) | Write `[:rf.route/navigate {:to …}]`; the error's `:reason` names the rule |

## Advanced

### Loading real data

`:on-match` starts work when a route activates; the runtime does not wait for it.
For server data, [load data for a route](how-to/load-page-data.md) with `:resources`.
That page shows the registration, loading/error view and parent resources together.
The same declarations also run during [server rendering](../ssr/concepts.md).

<a id="step-10--keep-signed-out-readers-out"></a>

### Requiring sign-in

A route's `:can-enter` subscription decides whether the current app state allows
entry. [Require sign-in on a route](how-to/require-sign-in-on-a-route.md) adds
settings and login pages to this app, including returning to the requested page
after sign-in.

<a id="step-11--warn-before-losing-unsaved-changes"></a>

### Protecting an editor's draft

A route's `:can-leave` subscription can pause navigation while the reader decides
whether to discard edits. [Guard against unsaved changes](how-to/guard-unsaved-changes.md)
adds the editor, its draft events and the confirmation prompt to this app.
