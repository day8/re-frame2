# Tutorial: build a routed app

Build a three-page app — **home**, **articles list**, **article detail** — then add
per-page activation work, a 404, the Back button, and a shared layout. One idea the whole way: the
URL is application state (read via a sub, change via dispatch).

Vocabulary after this walk-through: [The model](concepts.md). From React Router:
[the mapping](coming-from-react-router.md).

## Step 0 — turn routing on

Routing ships as its own package, `day8/re-frame2-routing`, so an app with no
shareable URLs pays nothing for it. Add the dependency, then require the namespace
once at boot:

```clojure
(ns app.core
  (:require [re-frame.core :as rf]
            [re-frame.routing]))   ;; ← requiring it is what turns routing on
```

That bare `[re-frame.routing]` require has a side effect: it wires up `reg-route`,
the route subscriptions, and `route-link`. Forget it and the first `reg-route` throws
`:rf.error/routing-artefact-missing` — a loud error that names exactly what to
require, not a silent no-op.

## Step 1 — your first route, on screen

A route is one row in a table: an **id**, a **metadata map**, and a **path**.
Register the home page, give the root view a `case` over the active route, and mount
so you can watch every step from here on:

```clojure
;; 1. Register the route: id, metadata, path.
(rf/reg-route :app/home {} "/")

;; 2. The root view reads the active route id and picks a page.
;;    Inside reg-view you call `subscribe` unprefixed — the macro binds it
;;    to this view's frame. (Outside a view: `rf/subscribe`, `rf/dispatch`.)
(rf/reg-view root-view []
  (case @(subscribe [:rf.route/id])
    :app/home [:h1 "Home"]
    [:h1 "Nothing here yet"]))   ;; any URL we haven't routed — Step 5 retires this

;; 3. Mount — standard Quickstart mount, plus one routing flag Step 6 explains.
;;    (Also requires: [reagent.dom.client :as rdc]
;;     [re-frame.adapter.reagent :as reagent-adapter])
(defn run []
  (rf/init! reagent-adapter/adapter)
  (rdc/render (rdc/create-root (js/document.getElementById "app"))
              [rf/frame-root {:id :rf/default :url-bound? true}  ;; ← this flag
               [root-view]]))
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

A detail page needs the article's id *in the URL*. A colon segment captures it:

```clojure
(rf/reg-route :app/article
  {:params [:map [:id :string]]}     ;; validate & coerce the captured :id
  "/articles/:id")
```

The `:id` in `/articles/:id` is a hole the matcher fills. The `:params`
[schema](../core/how-to/validate-with-schemas.md) validates and coerces — declare
`[:id :int]` and `/articles/42` arrives as the number `42`, not `"42"`. Read captured
params with a subscription:

```clojure
(rf/reg-view article-page []
  (let [{:keys [id]} @(subscribe [:rf.route/params])]
    [:h1 (str "Article " id)]))
```

Link by passing `:params`:

```clojure
[rf/route-link {:to :app/article :params {:id "intro"}} "Read intro"]
```

**What you see:** click lands on `/articles/intro`, page reads **Article intro**.
(Add `:app/article [article-page]` to the root `case`.)

> **Path params vs query params.** `:params` are the `/segments/` of the path.
> Query-string values (`?page=2`) are a *separate* map — declare with `:query`, read
> with `@(subscribe [:rf.route/query])`. The two never merge. [The model → Move 1](concepts.md#move-1-a-route-is-a-registry-entry)
> covers `:query`, defaults, and carrying global state like `?theme=dark` across pages.

## Step 4 — give a page its data

Most pages need something to happen when they open. Declare it next to the route with
`:on-match` — events the runtime **fires and forgets** whenever the route activates:

```clojure
(rf/reg-route :app/article
  {:params   [:map [:id :string]]
   :on-match [[:app/load-article]]}   ;; on entry, and on every :id change
  "/articles/:id")
```

The activation event is a normal event handler. It needs the article `:id`, which
lives in the **route slice** in [runtime-db](../core/glossary.md#runtime-db) — the
framework partition beside app-db. Handlers receive that partition under
`:rf.db/runtime`, next to `:db`:

```clojure
(def sample-articles                            ;; stand-in for your server
  {"intro" {:title "Intro to re-frame2"}
   "ssr"   {:title "Server rendering"}})

(rf/reg-event :app/load-article
  (fn [{:keys [db] rt :rf.db/runtime} _]
    (let [{:keys [id]} (get-in rt [:rf.runtime/routing :current :params])]
      ;; A real app starts an HTTP fetch here and lets a later event write the
      ;; reply — see Managed HTTP (../async/http.md). If you hand-roll the fetch,
      ;; capture the nav-token so a slow reply can't overwrite a newer page
      ;; (concepts.md#a-hand-rolled-async-loader). We'll "load" locally.
      {:db (assoc db :article/current (get sample-articles id))})))

(rf/reg-sub :article/current (fn [db _] (:article/current db)))
```

Detail page from Step 3 now reads the loaded article like any other state:

```clojure
(rf/reg-view article-page []
  (let [article @(subscribe [:article/current])]
    [:h1 (or (:title article) "Loading…")]))
```

No special "loader data" hook — the handler wrote to [app-db](../core/app-db.md) like
every other event. `:on-match` re-fires when `:id` changes, not when you re-navigate
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
> running it: `(rf/handler-meta :route :app/article)`. The same events run on the
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

> Register it. Skip it and unmatched URLs fall to a bare built-in placeholder (plus a
> warning). Not-found params also carry a `:reason` so you can tell a plain miss from
> a malformed URL from a failed schema; see [The model → Not found](concepts.md#not-found-is-a-route-you-register).

## Step 6 — the Back button and deep links

Step 1's mount flag is what makes Back, refreshes, and shared links work:

```clojure
(defn run []
  (rf/init! reagent-adapter/adapter)
  (rdc/render (rdc/create-root (js/document.getElementById "app"))
              [rf/frame-root {:id         :rf/default
                              :url-bound? true}  ;; ← this frame owns the address bar
               [root-view]]))
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
                             :params   [:map [:id :string]]
                             :on-match [[:app/load-article]]}   ;; kept from Step 4
  "/articles/:id")
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

## The complete shape

| Piece | Surface | You supply |
|---|---|---|
| Artefact | `(:require [re-frame.routing])` | Once at boot |
| Table | `reg-route` id / metadata / **path** | Including `:rf.route/not-found` |
| Change | `[:rf.route/navigate {…}]` or `route-link` | One request map (`:to` / `:params` / …) |
| Read | `[:rf.route/id]` / `params` / `query` / … | Ordinary subs |
| Browser | `:url-bound? true` on the frame | One owner of the address bar |
| Activation | `:on-match` | Fire-and-forget event vectors |
| Page data | `:resources` | Awaited reads, reported on `:rf.route/transition` |

Full copy-paste table + mount: [The model → A complete table + root](concepts.md#a-complete-table--root).
Growth: [unsaved changes](how-to/guard-unsaved-changes.md),
[sign-in](how-to/require-sign-in-on-a-route.md), [testing](testing.md).
