# Tutorial: build a routed app

The fastest way to understand re-frame2 routing is to build something small and watch each piece arrive on its own. We'll make a three-page app — a **home** page, a **list of articles**, and a **detail** page for one article — then add the touches that make it real: page data, a 404, the Back button, and a shared layout.

You'll meet every part of routing you reach for daily, one at a time. This page assumes you've done the [core Quickstart](../core/first-app.md) — you know what an [event](../core/introduction.md), a [subscription](../core/subscriptions.md), and a [view](../core/views.md) are. If you'd rather see the whole model at once, read [Concepts](concepts.md); if you're arriving from React Router, [the mapping](coming-from-react-router.md) may be the faster door.

> **One idea to carry through.** The URL is just application state. You *read* the active route through a subscription and *change* it by dispatching an event. There's no router object to hold and no route context to thread down your tree. Keep that in mind and every step below is a small variation on something you already know.

## Step 0 — turn routing on

Routing ships as its own package, `day8/re-frame2-routing`, so an app with no shareable URLs pays nothing for it. Add the dependency, then require the namespace once at boot:

```clojure
(ns app.core
  (:require [re-frame.core :as rf]
            [re-frame.routing]))   ;; ← requiring it is what turns routing on
```

That bare `[re-frame.routing]` require has a side effect: it wires up `reg-route`, the route subscriptions, and `route-link`. Forget it and your first `reg-route` throws `:rf.error/routing-artefact-missing` — a loud error that names exactly what to require, not a silent no-op.

## Step 1 — your first route, on screen

A route is one row in a table: an **id**, a **metadata map**, and a **path**. Register the home page, give the root view a `case` to pick pages with, and mount the app so you can watch every step from here on:

```clojure
;; 1. Register the route: id, metadata, path.
(rf/reg-route :app/home {} "/")

;; 2. The root view reads the active route id and picks a page.
;;    Inside reg-view you call `subscribe` unprefixed — the macro binds it
;;    to this view's frame for you. (Outside a view, reach through the facade:
;;    `rf/subscribe`, `rf/dispatch`.)
(rf/reg-view root-view []
  (case @(subscribe [:rf.route/id])
    :app/home [:h1 "Home"]
    [:h1 "Nothing here yet"]))   ;; any URL we haven't routed — Step 5 retires this

;; 3. Mount it — the standard mount from the Quickstart, plus one routing
;;    flag that Step 6 explains properly. (Also requires:
;;    [reagent.dom.client :as rdc] [re-frame.adapter.reagent :as reagent-adapter])
(defn run []
  (rf/init! reagent-adapter/adapter)
  (rdc/render (rdc/create-root (js/document.getElementById "app"))
              [rf/frame-provider {:id :rf/default :url-bound? true}  ;; ← this flag
               [root-view]]))
```

`@(subscribe [:rf.route/id])` reads the id of the route that matches the current URL. The root view is a plain `case` over that id — pick a page, render it. That's the entire "router": no `<Routes>`, no `<Switch>`, no nesting.

The routing flag in the mount is `:url-bound? true`, which says this frame is the one that owns the browser address bar. Take it on faith for now — Step 6 comes back to it when we wire the Back button.

**What you see:** at `/`, the page shows **Home**. Any other URL shows the placeholder — for now.

## Step 2 — a second page, and a link between them

Add an articles page, and a link to reach it. Links use `route-link`, which renders a real `<a href>` and turns a plain click into navigation:

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

`route-link` builds the URL from the route id, so you never hand-write `href="/articles"`. Change the route's path later and every link follows — there's one place the URL is synthesised.

**What you see:** Home shows a link; clicking it swaps to **Articles** with no full-page reload, and the address bar reads `/articles`.

> **Why a real `<a>`?** Because hover-preview, copy-link, and — crucially — cmd/ctrl/middle-click-to-open-in-new-tab all keep working. `route-link` intercepts only the plain left-click; modifier clicks defer to the browser. A hand-rolled `[:div {:on-click …}]` quietly breaks all of that.

## Step 3 — a page per item: dynamic segments

A detail page needs the article's id *in the URL*. A colon segment captures it:

```clojure
(rf/reg-route :app/article
  {:params [:map [:id :string]]}     ;; validate & coerce the captured :id
  "/articles/:id")
```

The `:id` in `/articles/:id` is a hole the matcher fills. The `:params` [schema](../core/how-to/validate-with-schemas.md) both validates it and coerces it — declare `[:id :int]` and `/articles/42` arrives as the number `42`, not the string `"42"`. Read the captured params with a subscription:

```clojure
(rf/reg-view article-page []
  (let [{:keys [id]} @(subscribe [:rf.route/params])]
    [:h1 (str "Article " id)]))
```

Link to it by passing `:params`:

```clojure
[rf/route-link {:to :app/article :params {:id "intro"}} "Read intro"]
```

**What you see:** clicking the link lands on `/articles/intro`, and the page reads **Article intro**. (Add `:app/article [article-page]` to the root view's `case` — each new page gets its row.)

> **Path params vs query params.** `:params` are the `/segments/` of the path. Query-string values (`?page=2`) are a *separate* map you declare with `:query` and read with `@(subscribe [:rf.route/query])` — the two never merge into one bag. [Concepts → Move 1](concepts.md#move-1-a-route-is-a-registry-entry) covers `:query`, defaults, and carrying global state like `?theme=dark` across pages.

## Step 4 — give a page its data

Most pages need data when they open. Declare it next to the route with `:on-match` — a list of ordinary events the runtime dispatches whenever the route activates:

```clojure
(rf/reg-route :app/article
  {:params   [:map [:id :string]]
   :on-match [[:app/load-article]]}   ;; dispatched on entry, and on every :id change
  "/articles/:id")
```

Now the loader itself. It's a normal event handler, with one new thing to learn: it needs the article's `:id`, and that lives in the **route slice**, which the framework keeps in [runtime-db](../core/glossary.md#runtime-db) — its own partition beside your app-db. Handlers receive that partition under the `:rf.db/runtime` key, right next to `:db`:

```clojure
(def sample-articles                            ;; stand-in for your server
  {"intro" {:title "Intro to re-frame2"}
   "ssr"   {:title "Server rendering"}})

(rf/reg-event :app/load-article
  (fn [{:keys [db] rt :rf.db/runtime} _]
    (let [{:keys [id]} (get-in rt [:rf.runtime/routing :current :params])]
      ;; A real app starts an HTTP fetch here and lets a later event write the
      ;; reply — see Managed HTTP (../async/http.md). We'll "load" locally.
      {:db (assoc db :article/current (get sample-articles id))})))

(rf/reg-sub :article/current (fn [db _] (:article/current db)))
```

And the detail page from Step 3 now reads the loaded article, like any other state:

```clojure
(rf/reg-view article-page []
  (let [article @(subscribe [:article/current])]
    [:h1 (or (:title article) "Loading…")]))
```

There's no special "loader data" hook, because the loader just wrote to [app-db](../core/app-db.md) like every other event. And `:on-match` re-fires when the `:id` changes but *not* when you navigate to the same route with identical params, so you never get an accidental double-load.

**What you see:** click through to `/articles/intro` and the title appears. Navigate to another article and the loader fires again with the new id; re-navigate to the *same* one and it doesn't. Open [Xray](../xray/index.md) and you'll see exactly those dispatches on the wire.

> **This is the loader — as data.** Because `:on-match` is a vector of event vectors rather than a function, you can read it, test it, and draw a route's data-dependency graph from it without running it: `(rf/handler-meta :route :app/article)` hands you the list. The same events run on the server during [SSR](../ssr/concepts.md) — there's no separate server loader to keep in sync. For server *state* (cached, deduplicated fetches with a built-in click-away race fix), declare `:resources` instead — see [Concepts → Loaders](concepts.md#loaders-declaring-a-pages-data).

## Step 5 — when nothing matches: the 404

When no route matches a URL, the runtime activates a reserved id, `:rf.route/not-found`, with the missed URL dropped into its params. You register it like any other route — you own the design:

```clojure
(rf/reg-route :rf.route/not-found {} "/404")

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

The "Nothing here yet" placeholder arm is gone — and that's the point. Now that `:rf.route/not-found` is registered, every URL lands on a real route id, so the `case` always has a page to pick.

**What you see:** visit `/nonsense` and your own 404 renders, with `/nonsense` shown back to the reader.

> Register it. Skip it and unmatched URLs fall to a bare built-in placeholder (plus a warning) — something renders, but not your design. The not-found params also carry a `:reason` so you can tell a plain miss from a malformed URL from a failed schema; see [Concepts → Not found](concepts.md#not-found-is-a-route-you-register).

## Step 6 — the Back button and deep links

Back in Step 1, the mount had one routing flag you took on faith. Time to cash that in — it's what makes the Back button, refreshes, and shared links work:

```clojure
(defn run []
  (rf/init! reagent-adapter/adapter)
  (rdc/render (rdc/create-root (js/document.getElementById "app"))
              [rf/frame-provider {:id         :rf/default
                                  :url-bound? true}  ;; ← this frame owns the address bar
               [root-view]]))
```

`:url-bound? true` says *this* [frame](../core/frames.md) owns the browser URL. When it navigates, the address bar updates; a frame without the flag routes purely in memory, which is exactly what a test frame wants.

Declaring it also does two jobs automatically, the moment the frame is created — no separate call to make. At startup, it syncs the current URL into state — so a deep link or a refresh lands on the right page instead of always starting at home. From then on, every Back/Forward press is delivered to the owning frame as an ordinary dispatch. It's idempotent, so hot-reload is safe.

**What you see:** paste `/articles/intro` straight into the address bar and the app boots onto that article. Then navigate Home → Articles → an article and press Back twice — each press steps the page back, because a Back press is now, literally, a dispatch.

> **One mount shape, two spellings.** `frame-provider` with `:id` *creates* the frame if it doesn't exist — handy for a small app that boots everything in one spot. Elsewhere you'll see the two-step spelling: `reg-frame` first, then `frame-provider {:frame …}` to scope it. Same frame, same result; the second form just makes the registration explicit.

> **The inversion.** In most routers the URL is the source of truth and your app reacts to it. Here your frame's state is the truth and the URL is a *print-out* of it — which is why [time-travel](../xray/index.md) rewinds the URL for free. [Concepts → The browser is just another event source](concepts.md#the-browser-is-just-another-event-source) goes deeper.

## Step 7 — a shared layout

Our article pages should sit inside a shell — a section header, a "back to all articles" nav — without each page repeating it. In re-frame2, nesting is **data**: a child route names a `:parent`, and a subscription hands you the chain so you compose the shells yourself.

Point the detail page at a parent:

```clojure
(rf/reg-route :app/articles {} "/articles")
(rf/reg-route :app/article  {:parent :app/articles
                             :params [:map [:id :string]]} "/articles/:id")
```

Now `@(subscribe [:rf.route/chain])` returns the active route's ancestry, **root-most first** — on `/articles/intro` it's `[:app/articles :app/article]`. Two jobs fall out of that vector:

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
     ;; Fold from the leaf outward: the page becomes the child of its parent's
     ;; shell, which becomes the child of its parent's shell, and so on.
     (reduce (fn [inner ancestor] (ancestor-shell ancestor inner))
             (page-for (last chain))         ;; start: the leaf page
             (reverse (butlast chain))))])   ;; wrap: ancestors, innermost first
```

If the fold feels abstract, trace it once. On `/articles/intro` the chain is `[:app/articles :app/article]`, so the `reduce` computes:

```clojure
(ancestor-shell :app/articles (page-for :app/article))
;; ⇒ [:div.articles-section [:nav "← All articles"] [article-page]]
```

One wrap, from the inside out. A deeper chain just wraps more times.

**What you see:** on `/articles/intro`, the article renders inside the `← All articles` section nav, under the site header — and every article detail page shares that frame with no copy-paste. Plain `/articles` (no parent above it) shows the bare list; `/` shows the home page. Note the split: truly *global* chrome (the `My Site` header) is just rendered in the root view — you only reach for the chain when a shell wraps a *subtree*.

> **Coming from React Router?** This is the job `<Outlet/>` does there. The trade is deliberate: instead of a routing-specific render slot, you compose plain Clojure with the `case`/`reduce` you'd write for any conditional view. It's the one place routing asks a little more of you — and the only routing-specific piece is the one `:rf.route/chain` read. [Concepts → Nested layouts](concepts.md#nested-layouts) has the reference version.
