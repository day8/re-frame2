# Tutorial: build a routed app

Build a small articles app — **home**, **articles list**, **article detail** — then add
per-page activation work, a 404, the Back button, a shared layout, navigation from an
event, a query-string filter, a signed-in-only settings page, and an editor that warns
about unsaved changes. One idea the whole way: the URL is application state (read via a
sub, change via dispatch).

Vocabulary after this walk-through: [The model](concepts.md). From React Router:
[the mapping](coming-from-react-router.md).

## Step 0 — turn routing on

Routing ships as its own package, `day8/re-frame2-routing`, so an app with no
shareable URLs pays nothing for it. Add the dependency, then require the namespace
once at boot:

```clojure
(ns app.core
  (:require [re-frame.core :as rf]
            [re-frame.routing]                              ;; ← turns routing on
            [re-frame.adapter.reagent :as reagent-adapter]))
```

That bare `[re-frame.routing]` require has a side effect: it wires up `reg-route`,
the route subscriptions, and `route-link`. Forget it and the first `reg-route` throws
`:rf.error/routing-artefact-missing` — a loud error that names exactly what to
require, not a silent no-op.

Every snippet below goes in this one namespace.

## Step 1 — your first route, on screen

A route is one row in a table: an **id**, a **metadata map**, and a **path**.
Register the home page, give the root view a `case` over the active route, and mount
so you can watch every step from here on:

```clojure
;; 1. Register the route: id, metadata, path.
(rf/reg-route :app/home {} "/")

;; 2. The root view reads the active route id and picks a page.
;;    Inside reg-view you call `subscribe` unprefixed — the macro binds it
;;    to this view's frame. (Outside a view, name the frame:
;;    `(rf/dispatch event {:frame :app})`.)
(rf/reg-view root-view []
  (case @(subscribe [:rf.route/id])
    :app/home [:h1 "Home"]
    [:h1 "Nothing here yet"]))   ;; any URL we haven't routed — Step 5 retires this

;; 3. Mount — standard Quickstart mount, plus one routing flag Step 6 explains.
(defonce app-root (reagent-adapter/client-root))

(defn run []
  (rf/init! reagent-adapter/adapter)
  (reagent-adapter/render! app-root
    [rf/frame-root {:id :app :url-bound? true}  ;; ← this flag
     [root-view]]
    (js/document.getElementById "app")))
```

`@(subscribe [:rf.route/id])` is the id of the route that matches the current URL.
The root view is a plain `case` over that id — pick a page, render it. That's the
entire "router": no `<Routes>`, no `<Switch>`, no nesting.

`:url-bound? true` says this frame owns the browser address bar. Take it on faith
for now — Step 6 comes back when we wire the Back button.

**What you see:** at `/`, the page shows **Home**. Any other URL shows the
placeholder — for now.

## Step 2 — a second page, and a link between them

Add an articles page and a link. `route-link` renders a real `<a href>` and turns a
plain click into navigation:

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

`route-link` builds the URL from the route id — you never hand-write
`href="/articles"`. Change the path later and every link follows.

**What you see:** Home shows a link; clicking it swaps to **Articles** with no
full-page reload, and the address bar reads `/articles`.

> **Why a real `<a>`?** Hover-preview, copy-link, and cmd/ctrl/middle-click-to-open
> in a new tab keep working. `route-link` intercepts only the plain left-click;
> modifier clicks defer to the browser. A hand-rolled `[:div {:on-click …}]` quietly
> breaks all of that.

## Step 3 — a page per item: dynamic segments

A detail page needs the article's slug *in the URL*. A colon segment captures it:

```clojure
(rf/reg-route :app/article
  {:params [:map [:slug :string]]}     ;; coerce (and, with re-frame.schemas, validate) :slug
  "/articles/:slug")
```

The `:slug` in `/articles/:slug` is a hole the matcher fills. The `:params`
[schema](../core/how-to/validate-with-schemas.md) always coerces — declare
`[:id :int]` on an `/items/:id` route and `/items/42` arrives as the number `42`, not
`"42"`. It also validates, but only in an app that requires `re-frame.schemas`;
without that require, a value the schema rejects passes through unchecked. Read
captured params with a subscription:

```clojure
(rf/reg-view article-page []
  (let [{:keys [slug]} @(subscribe [:rf.route/params])]
    [:h1 (str "Article " slug)]))
```

Link by passing `:params`. Give the list page some articles to link to — a plain map
stands in for your server:

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

**What you see:** the list shows two links; clicking **Intro to re-frame2** lands on
`/articles/intro`, and the page reads **Article intro**.

## Step 4 — give a page its data

Most pages need something to happen when they open. Declare it next to the route with
`:on-match` — events the runtime **fires and forgets** whenever the route activates:

```clojure
(rf/reg-route :app/article
  {:params   [:map [:slug :string]]
   :on-match [[:app/load-article]]}   ;; on entry, and on every :slug change
  "/articles/:slug")
```

The activation event is a normal event handler. It needs the article `:slug`, which
lives in the **route slice** in [runtime-db](../core/glossary.md#runtime-db) — the
framework partition beside app-db. Handlers receive that partition under
`:rf.db/runtime`, next to `:db`:

```clojure
(rf/reg-event :app/load-article
  (fn [{:keys [db] rt :rf.db/runtime} _]
    (let [{:keys [slug]} (get-in rt [:rf.runtime/routing :current :params])]
      ;; A real app starts an HTTP fetch here and lets a later event write the
      ;; reply — see Managed HTTP (../async/http.md). If you hand-roll the fetch,
      ;; capture the nav-token so a slow reply can't overwrite a newer page
      ;; (concepts.md#a-hand-rolled-async-loader). We'll "load" locally.
      {:db (assoc db :article/current (get sample-articles slug))})))

(rf/reg-sub :article/current (fn [db _] (:article/current db)))
```

Detail page from Step 3 now reads the loaded article like any other state:

```clojure
(rf/reg-view article-page []
  (let [article @(subscribe [:article/current])]
    [:h1 (or (:title article) "Loading…")]))
```

No special "loader data" hook — the handler wrote to [app-db](../core/app-db.md) like
every other event. `:on-match` re-fires when `:slug` changes, not when you re-navigate
to the same route with identical params — no accidental double-load.

> **`:on-match` is activation work, not the loader.** The runtime dispatches these
> events and moves on. It never waits for async work they start, never moves
> `:rf.route/transition` or `:rf.route/error`, and never turns a handler's failure
> into a route error — a throw surfaces on the ordinary event error channel. Data the
> page cannot honestly render without is a *different* declaration: `:resources`,
> which the runtime does await and does project onto the transition subs.
> [The model → Activation work and page data](concepts.md#loaders-declaring-a-pages-data).

**What you see:** click through to `/articles/intro` and the title appears. Navigate
to another article and `:on-match` fires again; re-navigate to the *same* one and it
doesn't. Open [Xray](../xray/index.md) and you'll see those dispatches on the wire.

> **Activation work as data.** `:on-match` is a vector of event vectors, not a
> function — you can read it, test it, and draw a data-dependency graph without
> running it: `(rf/handler-meta {:source :store :kind :route :id :app/article})`. The same events run on the
> server during [SSR](../ssr/concepts.md). For cached server state — a real page
> load, awaited and reported — declare `:resources` instead:
> [The model → Declaring the data a page needs](concepts.md#declaring-resources-instead).
> Hand-rolling your own async fetch inside `:on-match` means owning the click-away
> race yourself: capture the navigation token and gate delivery, or a slow reply
> overwrites the page you navigated to next —
> [hand-rolled async loader](concepts.md#a-hand-rolled-async-loader).

## Step 5 — when nothing matches: the 404

When no route matches, the runtime activates reserved id `:rf.route/not-found`, with
the missed URL in its params. Register it like any other route:

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

The "Nothing here yet" arm is gone. Every URL lands on a real route id, so the `case`
always has a page.

**What you see:** visit `/nonsense` and your own 404 renders, with `/nonsense` shown
back.

> Register it. Skip it and an unmatched URL still activates `:rf.route/not-found`,
> but the runtime emits `:rf.warning/no-not-found-route`. Not-found params also carry a `:reason` so you
> can tell a plain miss from a malformed URL from a failed schema; see
> [The model → Not found](concepts.md#not-found-is-a-route-you-register).

## Step 6 — the Back button and deep links

Step 1's mount flag is what makes Back, refreshes, and shared links work:

```clojure
(defn run []
  (rf/init! reagent-adapter/adapter)
  (reagent-adapter/render! app-root
    [rf/frame-root {:id         :app
                    :url-bound? true}  ;; ← this frame owns the address bar
     [root-view]]
    (js/document.getElementById "app")))
```

`:url-bound? true` says *this* [frame](../core/frames.md) owns the browser URL. When
it navigates, the address bar updates; a frame without the flag routes purely in
memory — what a test frame wants.

At startup it syncs the current URL into state — deep link or refresh lands on the
right page. From then on every Back/Forward press is an ordinary dispatch. Idempotent,
so hot-reload is safe.

**What you see:** paste `/articles/intro` into the address bar and the app boots onto
that article. Navigate Home → Articles → an article and press Back twice — each press
steps the page back, because Back is a dispatch.

> **One mount shape, two spellings.** `frame-root {:id …}` *creates* the frame if it
> doesn't exist — handy for a small app. Elsewhere: `make-frame` first, then
> `frame-provider {:frame …}` to scope it. Same frame, same result.

> **The inversion.** Most routers treat the URL as truth and the app as a reaction.
> Here the frame's state is truth and the URL is a *print-out* — which is why
> [time-travel](../xray/index.md) rewinds the URL for free. [The model → The browser is just another event source](concepts.md#the-browser-is-just-another-event-source).

## Step 7 — a shared layout

Article pages should sit inside a shell — section header, "back to all articles" —
without each page repeating it. Nesting is **data**: a child route names a
`:parent`, and a subscription hands you the chain so you compose the shells yourself.

Point the detail page at a parent — and **carry the whole metadata map forward**.
`reg-route` is full replacement, not a merge: re-registering `:app/article` with only
`:parent` and `:params` would drop Step 4's `:on-match` entry. Keep every key
you still want:

```clojure
(rf/reg-route :app/articles {} "/articles")
(rf/reg-route :app/article  {:parent   :app/articles
                             :params   [:map [:slug :string]]
                             :on-match [[:app/load-article]]}   ;; kept from Step 4
  "/articles/:slug")
```

`@(subscribe [:rf.route/chain])` returns the active route's ancestry, **root-most
first** — on `/articles/intro` it's `[:app/articles :app/article]`. Two jobs fall out
of that vector:

```clojure
;; The LEAF of the chain is the page you're on.
(defn page-for [route-id]
  (case route-id
    :app/home           [home-page]
    :app/articles       [articles-page]
    :app/article        [article-page]
    :rf.route/not-found [not-found-page]))

;; Each ANCESTOR contributes a shell that wraps whatever is inside it.
(defn ancestor-shell [route-id inner]
  (case route-id
    :app/articles [:div.articles-section [:nav "← All articles"] inner]
    inner))                                  ;; ancestor with no chrome: pass through

(rf/reg-view root-view []
  [:div.site
   [:header "My Site"]                       ;; site-wide chrome needs no routing
   (let [chain @(subscribe [:rf.route/chain])]
     ;; Fold from the leaf outward: page becomes child of parent's shell, etc.
     (reduce (fn [inner ancestor] (ancestor-shell ancestor inner))
             (page-for (last chain))         ;; start: the leaf page
             (reverse (butlast chain))))])   ;; wrap: ancestors, innermost first
```

If the fold feels abstract, trace it once. On `/articles/intro` the chain is
`[:app/articles :app/article]`, so the `reduce` computes:

```clojure
(ancestor-shell :app/articles (page-for :app/article))
;; ⇒ [:div.articles-section [:nav "← All articles"] [article-page]]
```

One wrap, from the inside out. A deeper chain wraps more times.

**What you see:** on `/articles/intro`, the article renders inside the
`← All articles` section nav, under the site header — every article detail page
shares that frame with no copy-paste. Plain `/articles` shows the bare list; `/`
shows home. Global chrome (the `My Site` header) is just rendered in the root view —
reach for the chain only when a shell wraps a *subtree*.

> **Coming from React Router?** This is the job `<Outlet/>` does there. The trade is
> deliberate: instead of a routing-specific render slot, you compose plain Clojure
> with the `case`/`reduce` you'd write for any conditional view. The only
> routing-specific piece is the one `:rf.route/chain` read. [The model → Nested layouts](concepts.md#nested-layouts).

> **`:parent` does one more thing.** Once you start declaring a page's data with
> `:resources`, a child inherits its ancestors' declarations automatically — so a
> shell read is written once on the parent instead of restated in every child.
> Nothing else is inherited; `:on-match`, `:scroll`, and the guards stay per-route.
> [The model → Parent resources compose to the child](concepts.md#parent-resources-compose-to-the-child).

## Step 8 — navigate from an event

Links cover clicks. When navigation is the *result* of something — signing in,
saving, deleting — dispatch `:rf.route/navigate` from the event handler. Add a login
page whose button signs the reader in and sends them to the articles:

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

Add `:app/login [login-page]` to `page-for`, and a
`[rf/route-link {:to :app/login} "Sign in"]` to the header.

`:rf.route/navigate` takes **one request map**: `:to` and `:params` name the
destination exactly as `route-link` does, `:query` sets the query string (Step 9), and
`:replace? true` replaces the current history entry instead of pushing one — here it
keeps `/login` out of the back stack, so Back from the articles list doesn't return
to a sign-in form. Any other shape, such as `[:rf.route/navigate :app/articles]`,
is rejected with `:rf.error/navigate-bad-request`.

Navigation stays inside the event pipeline: the handler returns the navigation as an
`:fx` entry, and the runtime dispatches it to the same frame. A view can dispatch it
directly too — `#(dispatch [:rf.route/navigate {:to :app/home}])`.

**What you see:** click **Sign in as Ada** and the app lands on `/articles`. Press
Back and you go to the page before the login form, not to the form.

## Step 9 — query strings: filter the list

A filter belongs in the URL so it survives refresh and can be shared. Query-string
values (`?tag=ssr`) are a separate map from path params. Declare them on the route
with `:query`:

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

Declaring the key matters. A query key the route's `:query` schema names arrives as a
keyword (`:tag`); an undeclared one stays a string key (`"tag"`), so `(:tag query)`
would read `nil`.

To change the query without leaving the page, navigate **in place** — no `:to`, just
the edit. `:query-merge` folds changes into the current query, and a `nil` value
removes a key:

```clojure
;; inside a view, where `dispatch` is bound to the view's frame
[:button {:on-click #(dispatch [:rf.route/navigate {:query-merge {:tag nil}}])}
 "Clear filter"]
```

**What you see:** click **#ssr** and the address bar reads `/articles?tag=ssr` with
one article listed. Refresh, and the filter is still applied.

## Step 10 — keep signed-out readers out

Settings is only for signed-in readers. Put a `:can-enter` guard on the route — a
subscription that returns `true` to allow entry and `false` to refuse it:

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

The runtime checks the guard on every way into the route — a link, a navigate, a
typed URL, a refresh, Back/Forward. A refused entry commits nothing: the current page
stays, the URL stays, and `:on-match` does not run. The guard must return `true` or
`false`; anything else refuses and raises `:rf.error/can-enter-non-boolean`, which is
why the sub wraps the user in `some?`.

A refusal dispatches `:rf.route/entry-denied`. Its built-in handler does nothing, so
the click is simply ignored. Register your own to send the reader to sign in:

```clojure
(rf/reg-event :rf.route/entry-denied
  (fn [_ _]
    {:fx [[:dispatch [:rf.route/navigate {:to :app/login :replace? true}]]]}))
```

**What you see:** signed out, clicking **Settings** takes you to `/login`. Sign in,
then click **Settings** again and the page opens. To send the reader back to the page
they asked for after signing in, use the recipe in
[Require sign-in on a route](how-to/require-sign-in-on-a-route.md).

## Step 11 — warn before losing unsaved changes

Add an editor for an article. Leaving it with unsaved edits should ask first. That is
a `:can-leave` guard — the mirror of `:can-enter`, checked on the route you are
leaving:

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

When `:editor/can-leave?` returns `false`, the navigation does not happen. Instead the
runtime parks it in `:rf/pending-navigation`, a subscription that is `nil` until
something is waiting. Render a prompt from it, and answer with `:rf.route/continue` or
`:rf.route/cancel`, passing the pending navigation's `:id`:

```clojure
(rf/reg-view leave-prompt []
  (when-let [pending @(subscribe [:rf/pending-navigation])]
    [:div.modal
     [:p "You have unsaved changes. Leave anyway?"]
     [:button {:on-click #(dispatch [:rf.route/cancel (:id pending)])} "Stay"]
     [:button {:on-click #(dispatch [:rf.route/continue (:id pending)])} "Leave"]]))
```

Wire it up: add `:app/article-editor [editor-page]` to `page-for`, render
`[leave-prompt]` once in the root view, and give the article page an **Edit** link —
`[rf/route-link {:to :app/article-editor :params {:slug slug}} "Edit"]`, reading
`slug` from `:rf.route/params` as in Step 3.

`:rf.route/continue` finishes the navigation the reader started; `:rf.route/cancel`
drops it and leaves them in the editor. Like `:can-enter`, the guard must return a
boolean — anything else blocks and raises `:rf.error/can-leave-non-boolean`.

**What you see:** open **Edit**, change the title, and click **Home** — the prompt
appears and the URL stays put. **Stay** keeps your draft; **Leave** goes home. Save
first and **Home** goes straight through.

The guard covers navigation inside the app. Closing the tab or reloading is the
browser's business; [Guard against unsaved changes](how-to/guard-unsaved-changes.md)
covers that, plus a "save and leave" button.

## The complete app

Every step assembled into one namespace:

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

## The complete shape

| Piece | Surface | You supply |
|---|---|---|
| Artefact | `(:require [re-frame.routing])` | Once at boot |
| Table | `reg-route` id / metadata / **path** | Including `:rf.route/not-found` |
| Change | `[:rf.route/navigate {…}]` or `route-link` | One request map (`:to` / `:params` / `:query` / …) |
| Read | `[:rf.route/id]` / `params` / `query` / `chain` | Ordinary subs |
| Browser | `:url-bound? true` on the frame | One owner of the address bar |
| Activation | `:on-match` | Fire-and-forget event vectors |
| Guards | `:can-enter` / `:can-leave` | Boolean subs |
| Page data | `:resources` | Awaited reads, reported on `:rf.route/transition` |

## Troubleshooting

| Symptom | Cause | Fix |
|---|---|---|
| First `reg-route` throws `:rf.error/routing-artefact-missing` | `re-frame.routing` is not required | Add `[re-frame.routing]` to the `ns` requires |
| Clicking a link reloads the whole page | The link is a hand-written `[:a {:href …}]` | Use `rf/route-link` |
| The address bar never changes, and Back does nothing | The frame has no `:url-bound? true` | Add it to `frame-root` (Step 6) |
| Root view throws `No matching clause` | A registered route has no arm in the `case` | Add the route's arm |
| `:rf.warning/no-not-found-route` on an unmatched URL | `:rf.route/not-found` is not registered | Register it (Step 5) |
| `:on-match` stopped firing after adding `:parent` | `reg-route` replaces the whole metadata map | Re-register with every key you still want (Step 7) |
| `(:tag query)` is `nil` although the URL has `?tag=` | The route does not declare `:tag` in `:query` | Declare it in the `:query` schema (Step 9) |
| Navigation is refused with `:rf.error/navigate-bad-request` | The payload is not one request map | Write `[:rf.route/navigate {:to …}]` |
| A guarded route can never be entered or left, with `:rf.error/can-enter-non-boolean` or `:rf.error/can-leave-non-boolean` | The guard sub returned something other than `true` / `false` | Wrap the value in `boolean`, `some?` or `not` |
| Nothing happens when a signed-out reader clicks a guarded link | The built-in `:rf.route/entry-denied` handler does nothing | Register your own (Step 10) |

Growth: [unsaved changes](how-to/guard-unsaved-changes.md),
[sign-in](how-to/require-sign-in-on-a-route.md), [testing](testing.md).
