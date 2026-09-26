# Tutorial: build a routed app

This tutorial builds a small articles app: a home page, an articles list and an
article page, then a 404, the Back button, a shared layout, navigation from an event,
a query-string filter, a settings page for signed-in readers, and an editor that warns
about unsaved changes. Each step adds one idea and ends with code you can run.

Throughout, the URL is application state: you read the active route with a
subscription and change it by dispatching an event.

## Step 0 — turn routing on

Routing ships as its own package, `day8/re-frame2-routing`, so an app without
shareable URLs doesn't carry it. Add the dependency, then require the namespace once:

```clojure
(ns app.core
  (:require [re-frame.core :as rf]
            [re-frame.routing]                              ;; turns routing on
            [re-frame.adapter.reagent :as reagent-adapter]))
```

Requiring `re-frame.routing` registers `reg-route`, the route subscriptions and
`route-link`. Without it, the first `reg-route` throws
`:rf.error/routing-artefact-missing`, and the message names the namespace to require.

Every snippet below goes in this namespace.

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

(defonce app-root (reagent-adapter/client-root))

(defn run []
  (rf/init! reagent-adapter/adapter)
  (reagent-adapter/render! app-root
    [rf/frame-root {:id :app :url-bound? true}
     [root-view]]
    (js/document.getElementById "app")))
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
[schema](../core/how-to/validate-with-schemas.md) always coerces — declare
`[:id :int]` on an `/items/:id` route and `/items/42` arrives as the number `42`, not
`"42"`. It also validates, but only in an app that requires `re-frame.schemas`;
without that require, a value the schema rejects passes through unchecked.

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
      {:db (assoc db :article/current (get sample-articles slug))})))

(rf/reg-sub :article/current (fn [db _] (:article/current db)))

(rf/reg-view article-page []
  (let [article @(subscribe [:article/current])]
    [:h1 (or (:title article) "Loading…")]))
```

The handler writes to [app-db](../core/app-db.md) like any other event, and the page
reads the result with a subscription. `:on-match` fires again when `:slug` changes, but
not when you navigate to the same route with the same params.

The runtime dispatches `:on-match` events and moves on: it does not wait for work they
start, and an exception in the handler is reported like any other event error. A real
app would start an [HTTP request](../async/http.md) here. For data the page cannot
render without, see [Loading real data](#loading-real-data).

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
    :rf.route/not-found [not-found-page]))
```

Every URL now lands on a registered route, so the `case` no longer needs a default.

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
    :rf.route/not-found [not-found-page]))

(defn ancestor-shell [route-id inner]
  (case route-id
    :app/articles [:div.articles-section [:nav "← All articles"] inner]
    inner))                                  ;; no shell: return the page unchanged

(rf/reg-view root-view []
  [:div.site
   [:header "My Site"]                       ;; site-wide chrome needs no routing
   (let [chain @(subscribe [:rf.route/chain])]
     (reduce (fn [inner ancestor] (ancestor-shell ancestor inner))
             (page-for (last chain))         ;; the page itself
             (reverse (butlast chain))))])   ;; ancestors, innermost first
```

On `/articles/intro` the `reduce` computes:

```clojure
(ancestor-shell :app/articles (page-for :app/article))
;; ⇒ [:div.articles-section [:nav "← All articles"] [article-page]]
```

A deeper chain wraps the page once per ancestor. This does the job of React Router's
`<Outlet/>` with a plain `reduce`; see the
[React Router mapping](coming-from-react-router.md).

**What you see:** `/articles/intro` renders the article inside the
**← All articles** nav, under the site header. `/articles` shows the list without the
nav, and `/` shows Home.

## Step 8 — navigate from an event

When navigation follows from something the user did — signing in, saving, deleting —
dispatch `:rf.route/navigate` from the event handler. Add a login page that signs the
reader in and opens the articles:

```clojure
(rf/reg-route :app/login {} "/login")

(rf/reg-sub :auth/user (fn [db _] (:auth/user db)))

(rf/reg-event :auth/sign-in
  (fn [{:keys [db]} [_ user]]
    {:db (assoc db :auth/user user)
     :fx [[:dispatch [:rf.route/navigate {:to       :app/articles
                                          :replace? true}]]]}))

(rf/reg-view login-page []
  [:div
   [:h1 "Sign in"]
   [:button {:on-click #(dispatch [:auth/sign-in {:name "Ada"}])}
    "Sign in as Ada"]])
```

Add `:app/login [login-page]` to `page-for`, and
`[rf/route-link {:to :app/login} "Sign in"]` to the header.

`:rf.route/navigate` takes one request map. `:to` and `:params` name the destination as
they do for `route-link`, `:query` sets the query string, and `:replace? true` replaces
the current history entry instead of adding one, so Back from the articles list skips
the login form. Any other payload shape, such as `[:rf.route/navigate :app/articles]`,
is rejected with `:rf.error/navigate-bad-request`.

The `:dispatch` effect sends the navigation to the same frame as the handler. A view can
also dispatch it directly: `#(dispatch [:rf.route/navigate {:to :app/home}])`.

**What you see:** click **Sign in as Ada** and the app opens `/articles`. Back returns
to the page before the login form.

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

## Step 10 — keep signed-out readers out

Settings is for signed-in readers only. Give the route a `:can-enter` guard: a
subscription that returns `true` to allow entry and `false` to refuse it.

```clojure
(rf/reg-sub :auth/signed-in? {:inputs [[:auth/user]]}
  (fn [[user] _] (some? user)))

(rf/reg-route :app/settings
  {:can-enter [:auth/signed-in?]}
  "/settings")

(rf/reg-view settings-page []
  [:h1 (str "Settings for " (:name @(subscribe [:auth/user])))])
```

Add `:app/settings [settings-page]` to `page-for`, and a link to it in the header.

The runtime checks the guard on every way into the route: a link, a navigate, a typed
URL, a refresh, Back or Forward. A refused entry changes nothing — the current page and
URL stay, and `:on-match` does not run. The guard must return `true` or `false`;
anything else refuses and raises `:rf.error/can-enter-non-boolean`, which is why the
sub wraps the user in `some?`.

A refusal dispatches `:rf.route/entry-denied`. The built-in handler does nothing, so the
click is ignored. Register your own handler to send the reader to the login page:

```clojure
(rf/reg-event :rf.route/entry-denied
  (fn [_ _]
    {:fx [[:dispatch [:rf.route/navigate {:to :app/login :replace? true}]]]}))
```

**What you see:** signed out, clicking **Settings** opens `/login`. After signing in,
**Settings** opens the settings page. To return the reader to the page they asked for
after signing in, see [Require sign-in on a route](how-to/require-sign-in-on-a-route.md).

## Step 11 — warn before losing unsaved changes

Add an article editor that asks before the reader leaves with unsaved edits. Give its
route a `:can-leave` guard, which the runtime checks on the route being left:

```clojure
(rf/reg-route :app/article-editor
  {:params    [:map [:slug :string]]
   :on-match  [[:editor/open]]
   :can-leave [:editor/can-leave?]}
  "/articles/:slug/edit")

(rf/reg-event :editor/open
  (fn [{:keys [db] rt :rf.db/runtime} _]
    (let [{:keys [slug]} (get-in rt [:rf.runtime/routing :current :params])
          title          (get-in sample-articles [slug :title])]
      {:db (assoc db :editor {:slug slug :draft title :saved title})})))

(rf/reg-event :editor/edit
  (fn [{:keys [db]} [_ text]]
    {:db (assoc-in db [:editor :draft] text)}))

(rf/reg-event :editor/save          ;; a real app would also send it to the server
  (fn [{:keys [db]} _]
    {:db (assoc-in db [:editor :saved] (get-in db [:editor :draft]))}))

(rf/reg-sub :editor/draft (fn [db _] (get-in db [:editor :draft])))

(rf/reg-sub :editor/can-leave?     ;; true when there is nothing to lose
  (fn [db _]
    (= (get-in db [:editor :draft]) (get-in db [:editor :saved]))))

(rf/reg-view editor-page []
  [:div
   [:h1 "Edit title"]
   [:input {:value     (or @(subscribe [:editor/draft]) "")
            :on-change #(dispatch [:editor/edit (.. % -target -value)])}]
   [:button {:on-click #(dispatch [:editor/save])} "Save"]])
```

When `:editor/can-leave?` returns `false`, the navigation waits in
`:rf/pending-navigation`, a subscription that is `nil` when nothing is waiting. Render a
prompt from it, and answer with `:rf.route/continue` or `:rf.route/cancel`, passing the
pending navigation's `:id`:

```clojure
(rf/reg-view leave-prompt []
  (when-let [pending @(subscribe [:rf/pending-navigation])]
    [:div.modal
     [:p "You have unsaved changes. Leave anyway?"]
     [:button {:on-click #(dispatch [:rf.route/cancel (:id pending)])} "Stay"]
     [:button {:on-click #(dispatch [:rf.route/continue (:id pending)])} "Leave"]]))
```

Add `:app/article-editor [editor-page]` to `page-for`, render `[leave-prompt]` once in
the root view, and give the article page an **Edit** link:
`[rf/route-link {:to :app/article-editor :params {:slug slug}} "Edit"]`, with `slug`
read from `:rf.route/params` as in Step 3.

`:rf.route/continue` completes the waiting navigation; `:rf.route/cancel` drops it. As
with `:can-enter`, the guard must return a boolean — anything else blocks and raises
`:rf.error/can-leave-non-boolean`.

**What you see:** open **Edit**, change the title and click **Home**: the prompt
appears and the URL doesn't change. **Stay** keeps your draft; **Leave** goes home.
After **Save**, **Home** navigates straight away.

The guard covers navigation inside the app, not closing the tab or reloading. For
those, and for a "save and leave" button, see
[Guard against unsaved changes](how-to/guard-unsaved-changes.md).

## The complete app

All the steps in one namespace:

```clojure
(ns app.core
  (:require [re-frame.core :as rf]
            [re-frame.routing]
            [re-frame.adapter.reagent :as reagent-adapter]))

;; ---- data ------------------------------------------------------------------

(def sample-articles
  {"intro" {:title "Intro to re-frame2" :tags #{"basics"}}
   "ssr"   {:title "Server rendering"   :tags #{"ssr"}}})

;; ---- routes ----------------------------------------------------------------

(rf/reg-route :app/home {} "/")

(rf/reg-route :app/articles
  {:query [:map [:tag {:optional true} :string]]}
  "/articles")

(rf/reg-route :app/article
  {:parent   :app/articles
   :params   [:map [:slug :string]]
   :on-match [[:app/load-article]]}
  "/articles/:slug")

(rf/reg-route :app/article-editor
  {:params    [:map [:slug :string]]
   :on-match  [[:editor/open]]
   :can-leave [:editor/can-leave?]}
  "/articles/:slug/edit")

(rf/reg-route :app/settings
  {:can-enter [:auth/signed-in?]}
  "/settings")

(rf/reg-route :app/login {} "/login")

(rf/reg-route :rf.route/not-found {} "/_404")

;; ---- events and subs -------------------------------------------------------

(rf/reg-event :app/load-article
  (fn [{:keys [db] rt :rf.db/runtime} _]
    (let [{:keys [slug]} (get-in rt [:rf.runtime/routing :current :params])]
      {:db (assoc db :article/current (get sample-articles slug))})))

(rf/reg-sub :article/current (fn [db _] (:article/current db)))

(rf/reg-sub :auth/user (fn [db _] (:auth/user db)))

(rf/reg-sub :auth/signed-in? {:inputs [[:auth/user]]}
  (fn [[user] _] (some? user)))

(rf/reg-event :auth/sign-in
  (fn [{:keys [db]} [_ user]]
    {:db (assoc db :auth/user user)
     :fx [[:dispatch [:rf.route/navigate {:to :app/articles :replace? true}]]]}))

(rf/reg-event :rf.route/entry-denied
  (fn [_ _]
    {:fx [[:dispatch [:rf.route/navigate {:to :app/login :replace? true}]]]}))

(rf/reg-event :editor/open
  (fn [{:keys [db] rt :rf.db/runtime} _]
    (let [{:keys [slug]} (get-in rt [:rf.runtime/routing :current :params])
          title          (get-in sample-articles [slug :title])]
      {:db (assoc db :editor {:slug slug :draft title :saved title})})))

(rf/reg-event :editor/edit
  (fn [{:keys [db]} [_ text]]
    {:db (assoc-in db [:editor :draft] text)}))

(rf/reg-event :editor/save
  (fn [{:keys [db]} _]
    {:db (assoc-in db [:editor :saved] (get-in db [:editor :draft]))}))

(rf/reg-sub :editor/draft (fn [db _] (get-in db [:editor :draft])))

(rf/reg-sub :editor/can-leave?
  (fn [db _]
    (= (get-in db [:editor :draft]) (get-in db [:editor :saved]))))

;; ---- pages -----------------------------------------------------------------

(rf/reg-view home-page []
  [:div
   [:h1 "Home"]
   [rf/route-link {:to :app/articles} "See the articles →"]])

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
  (let [{:keys [slug]} @(subscribe [:rf.route/params])
        article        @(subscribe [:article/current])]
    [:div
     [:h1 (or (:title article) "Loading…")]
     [rf/route-link {:to :app/article-editor :params {:slug slug}} "Edit"]]))

(rf/reg-view editor-page []
  [:div
   [:h1 "Edit title"]
   [:input {:value     (or @(subscribe [:editor/draft]) "")
            :on-change #(dispatch [:editor/edit (.. % -target -value)])}]
   [:button {:on-click #(dispatch [:editor/save])} "Save"]])

(rf/reg-view settings-page []
  [:h1 (str "Settings for " (:name @(subscribe [:auth/user])))])

(rf/reg-view login-page []
  [:div
   [:h1 "Sign in"]
   [:button {:on-click #(dispatch [:auth/sign-in {:name "Ada"}])}
    "Sign in as Ada"]])

(rf/reg-view not-found-page []
  (let [url (:url @(subscribe [:rf.route/params]))]
    [:div
     [:h1 "Not found"]
     [:p (str "No page at " url)]
     [rf/route-link {:to :app/home} "Home"]]))

(rf/reg-view leave-prompt []
  (when-let [pending @(subscribe [:rf/pending-navigation])]
    [:div.modal
     [:p "You have unsaved changes. Leave anyway?"]
     [:button {:on-click #(dispatch [:rf.route/cancel (:id pending)])} "Stay"]
     [:button {:on-click #(dispatch [:rf.route/continue (:id pending)])} "Leave"]]))

;; ---- layout ----------------------------------------------------------------

(defn page-for [route-id]
  (case route-id
    :app/home           [home-page]
    :app/articles       [articles-page]
    :app/article        [article-page]
    :app/article-editor [editor-page]
    :app/settings       [settings-page]
    :app/login          [login-page]
    :rf.route/not-found [not-found-page]))

(defn ancestor-shell [route-id inner]
  (case route-id
    :app/articles [:div.articles-section [:nav "← All articles"] inner]
    inner))

(rf/reg-view root-view []
  [:div.site
   [:header "My Site "
    [rf/route-link {:to :app/settings} "Settings"] " "
    [rf/route-link {:to :app/login} "Sign in"]]
   [leave-prompt]
   (let [chain @(subscribe [:rf.route/chain])]
     (reduce (fn [inner ancestor] (ancestor-shell ancestor inner))
             (page-for (last chain))
             (reverse (butlast chain))))])

;; ---- mount -----------------------------------------------------------------

(defonce app-root (reagent-adapter/client-root))

(defn run []
  (rf/init! reagent-adapter/adapter)
  (reagent-adapter/render! app-root
    [rf/frame-root {:id :app :url-bound? true}
     [root-view]]
    (js/document.getElementById "app")))
```

## Troubleshooting

| Symptom | Cause | Fix |
|---|---|---|
| First `reg-route` throws `:rf.error/routing-artefact-missing` | `re-frame.routing` is not required | Add `[re-frame.routing]` to the `ns` requires |
| Clicking a link reloads the whole page | The link is a hand-written `[:a {:href …}]` | Use `rf/route-link` |
| The address bar never changes, and Back does nothing | The frame has no `:url-bound? true` | Add it to `frame-root` (Step 1) |
| Root view throws `No matching clause` | A registered route has no arm in the `case` | Add the route's arm |
| `:rf.warning/no-not-found-route` on an unmatched URL | `:rf.route/not-found` is not registered | Register it (Step 5) |
| `:on-match` stopped firing after adding `:parent` | `reg-route` replaces the whole metadata map | Re-register with every key you still want (Step 7) |
| `(:tag query)` is `nil` although the URL has `?tag=` | The route does not declare `:tag` in `:query` | Declare it in the `:query` schema (Step 9) |
| Navigation is refused with `:rf.error/navigate-bad-request` | The payload is not one request map | Write `[:rf.route/navigate {:to …}]` |
| A guarded route can never be entered or left, with `:rf.error/can-enter-non-boolean` or `:rf.error/can-leave-non-boolean` | The guard sub returned something other than `true` / `false` | Wrap the value in `boolean`, `some?` or `not` |
| Nothing happens when a signed-out reader clicks a guarded link | The built-in `:rf.route/entry-denied` handler does nothing | Register your own (Step 10) |

## Advanced

### Loading real data

`:on-match` suits work the page can render without, because the runtime doesn't wait
for it. For data the page needs before it can render, declare `:resources` on the
route instead: the runtime waits for those reads and reports progress on
`:rf.route/transition`. A child route also receives the `:resources` declared on its
`:parent` routes, so a read the section shell needs is declared once. See
[Declaring the data a page needs](concepts.md#declaring-resources-instead) and
[Parent resources compose to the child](concepts.md#parent-resources-compose-to-the-child).

If you start your own async request from an `:on-match` handler, a slow reply can
arrive after the reader has moved to another page and overwrite it. Capture the
navigation token when the request starts and check it when the reply arrives; see
[A hand-rolled async loader](concepts.md#a-hand-rolled-async-loader).

`:on-match` events also run during [server rendering](../ssr/concepts.md).
