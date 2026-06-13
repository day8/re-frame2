# Part 1: pages, state, and the first feed

You left [the setup page](index.md) with an empty Conduit shell. By the end of this part it has two real pages — the home feed and the article page — rendering from canned data, and the URL decides which one you see. Type `/article/welcome-to-conduit` into the address bar and that article renders; press Back and the feed returns. Along the way you'll write your first event, subscriptions, and views — the loop everything else builds on.

Real server data arrives in [Part 2](02-server-data.md). This part is deliberately offline, so you can watch the state loop with nothing else moving.

**The takeaway: the URL is a sub; a page is just a view of it.**

> **Coming from React Router?** There is no `<Routes>` tree, no router context, no `useParams` hook. A route is a registry entry, navigating is dispatching an event, and the current route is an ordinary subscription your root view reads like any other piece of state. Everything this part teaches you about "state in, hiccup out" applies to pages unchanged — that's the point.

You'll touch two files:

```text
src/conduit/articles.cljs   ; the articles slice: seed data, event, subs, views
src/conduit/core.cljs       ; routes, the root view, boot
```

## Step 1 — canned articles into app-db

All of your app's state lives in **app-db**: one map. A feature claims one top-level key in that map and keeps everything it owns under it — we call that a **slice**. Articles get the `:articles` key.

State only changes when an **event** is dispatched and its registered handler computes the next value of app-db. So even seeding canned data is an event:

```clojure
;; src/conduit/articles.cljs
(ns conduit.articles
  (:require [re-frame.core :as rf])
  (:require-macros [re-frame.core :refer [reg-view]]))

(def seed-articles
  [{:slug        "welcome-to-conduit"
    :title       "Welcome to Conduit"
    :description "What you are building, and why."
    :body        "Conduit is a Medium-style publishing app. Five parts: feeds, auth, favoriting, publishing."
    :tagList     ["intro"]
    :createdAt   "2026-06-01T09:00:00Z"
    :author      {:username "octocat"}}
   {:slug        "events-write-subs-read"
    :title       "Events write, subs read"
    :description "The one-way loop at the heart of the app."
    :body        "Views dispatch events. Handlers compute new state. Subs deliver it back."
    :tagList     ["re-frame2"]
    :createdAt   "2026-06-02T09:00:00Z"
    :author      {:username "octocat"}}
   {:slug        "the-url-is-a-sub"
    :title       "The URL is a sub"
    :description "Routing without a router universe."
    :body        "The current route is state. Pages are views of it. Back is an event."
    :tagList     ["routing"]
    :createdAt   "2026-06-03T09:00:00Z"
    :author      {:username "octocat"}}])

(rf/reg-event-db :app/initialise
  {:doc "Seed app-db at boot. Part 2 replaces the canned articles
         with a real fetch."}
  (fn [_db _event]
    {:articles {:status :loaded
                :data   seed-articles
                :error  nil}}))
```

`reg-event-db` registers the simplest kind of event handler: a pure function from `(current-db, event)` to the next db. This one ignores both arguments and returns the whole initial map — that's what an initialise event is. Nothing here touches the DOM, the network, or a clock; a handler that needs the outside world graduates to a different registration form, which you'll meet in Part 2.

(This replaces the placeholder `:app/initialise` that setup put in `core.cljs` — delete that old registration now, so the two don't fight over the id. Step 4 rewrites the rest of that file anyway.)

Look at the slice's shape. It isn't a bare vector of articles — it's a map that carries the data *and* its lifecycle:

```clojure
{:status :loaded   ; what state is this data in?
 :data   [...]     ; the articles themselves
 :error  nil}      ; what went wrong, if anything
```

With canned data the slice is born `:loaded` and never moves, so the shape looks like overkill today. It's here because "what state is my data in?" is the question every real page must answer — loading, loaded, failed — and Part 2 makes those states real. Start with the honest shape and you never retrofit it. (The deeper story of the one-map design: [app-db: the one place](../concepts/app-db.md).)

## Step 2 — subscriptions: named questions

Views never reach into app-db directly. They ask **subscriptions** — named, registered queries. Add three to `articles.cljs`:

```clojure
(rf/reg-sub :articles/slice
  (fn [db _] (:articles db)))

(rf/reg-sub :articles/data
  :<- [:articles/slice]
  (fn [slice _] (:data slice)))

(rf/reg-sub :articles/by-slug
  :<- [:articles/data]
  (fn [articles [_ slug]]
    (first (filter #(= slug (:slug %)) articles))))
```

The first reads straight from app-db. The other two use `:<-` to declare an *input subscription*: `:articles/data` derives from `:articles/slice`, and `:articles/by-slug` derives from `:articles/data`. Subscriptions form a graph, and the graph is why this is cheap: a sub recomputes only when its inputs actually change, and a view re-renders only when the sub it reads produces a new value.

`:articles/by-slug` takes an argument. The argument rides in the query vector — a view asks for `[:articles/by-slug "welcome-to-conduit"]` and the computation function receives the whole vector, destructured here as `[_ slug]`.

(The full derivation-graph story is [Subscriptions: the derivation graph](../concepts/subscriptions.md).)

## Step 3 — views: the feed, rendered

A view is a pure function from subscription values to hiccup. Register them with `reg-view`, still in `articles.cljs`:

```clojure
;; Adapted from examples/reagent/realworld/articles.cljs
(reg-view article-preview [{:keys [article]}]
  (let [{:keys [slug title description tagList author createdAt]} article]
    [:div.article-preview
     [:div.article-meta
      [:div.info
       [:span.author (:username author)]
       [:span.date createdAt]]]
     [rf/route-link {:to     :conduit.article/show
                     :params {:slug slug}
                     :class  "preview-link"}
      [:h1 title]
      [:p description]
      [:span "Read more..."]
      [:ul.tag-list
       (for [tag tagList]
         ^{:key tag}
         [:li.tag-default.tag-pill.tag-outline tag])]]]))

(reg-view home-page []
  [:div.home-page
   [:div.banner
    [:div.container
     [:h1.logo-font "conduit"]
     [:p "A place to share your knowledge."]]]
   [:div.container.page
    (for [article @(subscribe [:articles/data])]
      ^{:key (:slug article)}
      [article-preview {:article article}])]])

(reg-view article-page []
  (let [{:keys [slug]} @(subscribe [:rf.route/params])
        article        @(subscribe [:articles/by-slug slug])]
    (if article
      [:div.article-page
       [:div.banner
        [:div.container [:h1 (:title article)]]]
       [:div.container.page
        [:div.row.article-content [:p (:body article)]]]]
      [:div.container.page
       [:p "There's no article called " [:code slug] " here."]])))
```

Three things to notice:

- **`reg-view` defines and registers in one move.** It defs the symbol (so `[article-preview {...}]` works as plain hiccup) and injects `dispatch` and `subscribe` as lexical bindings — that's why `home-page` calls `subscribe` without an `rf/` prefix.
- **`@` reads the current value.** `@(subscribe [:articles/data])` gives the value now, and signs the view up to re-render when it changes. That's the entire data-binding story.
- **`article-page` already reads the route.** `:rf.route/params` is a subscription like any other; it yields the current URL's captured params (here `{:slug "..."}`), and the page chains it into `:articles/by-slug`. The `if` handles a slug that matches the route pattern but names no article — a real URL someone can type, so a real branch your view owns.

Those `rf/route-link`s point at a route id that doesn't exist yet. Next step.

(Why views stay pure, and what that buys you: [Views: pure functions of data](../concepts/views.md).)

## Step 4 — the routing skeleton

Routing ships as its own artefact so apps that don't route don't carry it. The setup page's `deps.edn` doesn't have it yet — add it beside the core and adapter entries, then restart `npm run dev` (the watcher resolves `deps.edn` only at startup):

```clojure
{:deps    {day8/re-frame2         {:local/root "../re-frame2/implementation/core"}
           day8/re-frame2-reagent {:local/root "../re-frame2/implementation/adapters/reagent"}
           day8/re-frame2-routing {:local/root "../re-frame2/implementation/routing"}}
 :aliases {:dev {:extra-deps {day8/re-frame2-xray {:local/root "../re-frame2/tools/xray"}}}}}
```

Now `core.cljs`. You're replacing the whole file from setup: the placeholder navbar becomes a real header, and `:app/initialise` has already moved to the articles namespace (the session state returns in Part 3, when there's someone to sign in). Routes first:

```clojure
;; src/conduit/core.cljs
;; Adapted from examples/reagent/routing/core.cljs
(ns conduit.core
  (:require [reagent.dom.client :as rdc]
            [re-frame.core :as rf]
            ;; Loading re-frame.routing once at boot is what makes
            ;; reg-route, route-link, and the :rf.route/* events and
            ;; subs available through re-frame.core.
            [re-frame.routing]
            [re-frame.adapter.reagent :as reagent-adapter]
            [conduit.articles :as articles])
  (:require-macros [re-frame.core :refer [reg-view]]))

(rf/reg-route :conduit/home
  {:doc  "The home page: the article feed."
   :path "/"})

(rf/reg-route :conduit.article/show
  {:doc    "One article, addressed by its slug."
   :path   "/article/:slug"
   :params [:map [:slug :string]]})

(rf/reg-route :rf.route/not-found
  {:doc  "Fallback page for URLs that match nothing."
   :path "/_404"})
```

(One more named failure mode for setup's collection: forget that `[re-frame.routing]` require and the first `rf/reg-route` raises `:rf.error/routing-artefact-missing` — the error names the artefact and the namespace to require.)

A route is a registry entry, exactly like an event or a sub — data, not components. `:path` is a pattern; `:slug` is a named segment, and whatever it captures arrives in the `:rf.route/params` sub your article page already reads. The `:params` schema names the capture's shape; enforcement is opt-in — once the schemas artefact joins the classpath ([Validate with schemas](../how-to/validate-with-schemas.md)), a URL whose params fail validation is treated as unmatched rather than limping through your views half-parsed. Until then the schema is checked-later documentation, and a single `:string` slug has nothing to fail anyway.

`:rf.route/not-found` is the one route id the framework reserves: whenever a URL matches nothing, the runtime routes to it with the offending URL in `:rf.route/params`. Every app must register it — it's an ordinary route, you own its page.

Then the chrome and the root view:

```clojure
(reg-view header []
  [:nav.navbar
   [:div.container
    [rf/route-link {:to :conduit/home :class "navbar-brand"} "conduit"]]])

(reg-view not-found-page []
  (let [url (:url @(subscribe [:rf.route/params]))]
    [:div.container.page
     [:h1 "Page not found"]
     (when url [:p "No route matches " [:code url] "."])
     [rf/route-link {:to :conduit/home} "Take me home"]]))

(reg-view root-view []
  [:div.app
   [header]
   (case @(subscribe [:rf.route/id])
     :conduit/home          [articles/home-page]
     :conduit.article/show  [articles/article-page]
     :rf.route/not-found    [not-found-page]
     [not-found-page])])
```

This `case` is the whole router, and it's the heart of this part: **the root view subscribes to `:rf.route/id` and maps route ids to pages.** No route components, no outlet — a page is just the view your `case` picks for the current value of a sub.

Three route subscriptions cover most needs: `:rf.route/id` (which route), `:rf.route/params` (what it captured), and `:rf/route` (the whole route slice — id, params, query, plus the transition status and error slot you'll care about when data loading enters the picture).

And navigation? `rf/route-link` renders a real `<a href="...">` and turns a plain click into a dispatched event; Cmd-click and middle-click fall through to the browser, so open-in-new-tab still works. To navigate from code — after a successful form submit, say — it's an event like everything else:

```clojure
(rf/dispatch [:rf.route/navigate :conduit.article/show {:slug "welcome-to-conduit"}])
```

One verb, `dispatch`, whether the user clicked a link, pressed Back, or your handler decided to move — every path funnels into the same state change.

## Step 5 — boot: one frame that owns the URL

Finish `core.cljs` with the boot function your build invokes:

```clojure
(defonce root
  (rdc/create-root (js/document.getElementById "app")))

(defn run []
  (rf/init! reagent-adapter/adapter)
  (rf/reg-frame :rf/default {:doc        "The Conduit app frame."
                             :url-bound? true})
  (rf/with-frame :rf/default
    (rf/dispatch-sync [:app/initialise]))
  ;; Sync the current URL into state and wire the browser's
  ;; Back/Forward buttons. Idempotent, so hot reload is safe.
  (rf/install-history-listener!)
  (rdc/render root
              [rf/frame-provider {:frame :rf/default}
               [root-view]]))
```

Reading it top to bottom:

1. `rf/init!` installs the Reagent adapter — the bridge between re-frame2 and your rendering substrate.
2. `rf/reg-frame` creates the **frame** your app runs in: one isolated world of app-db, registrations, and subscriptions ([Frames](../concepts/frames.md)). What matters today is `:url-bound? true` — the explicit declaration that *this* frame owns the browser URL. Nothing owns the URL by default; without the declaration the address bar would never change.
3. `dispatch-sync` runs the seed event before the first render, so the feed never renders against an empty db. `with-frame` says which frame the dispatch targets.
4. `rf/install-history-listener!` does the initial URL→state sync — deep links work from the first paint — and turns the browser's Back/Forward into the same kind of route-change event a link click produces.
5. `frame-provider` scopes the mounted tree to the frame, so every `subscribe` and `dispatch` inside your views resolves to it.

## See it move

With the dev build running, open the app and walk the loop you just built:

1. **Click an article.** The page changes *and* the address bar now reads `/article/events-write-subs-read`. You didn't write URL-sync code — the URL is downstream of the route state.
2. **Press Back.** The feed returns. Back isn't a special browser mystery; it arrived as an event, the route slice changed, and your `case` picked the other page.
3. **Type a URL by hand.** Visit `/article/the-url-is-a-sub` directly — the deep link works, because boot syncs URL→state before first render. Now try `/article/nope`: the route matches, the data doesn't, and your missing-article branch renders. Then `/definitely-not-a-route`: nothing matches, and the not-found page renders. Two different failures, each owned by a view you wrote.

If you wired Xray during setup, open it while you click: each navigation shows up as an event row followed by the route state changing — link clicks, Back presses, and address-bar entries all produce the same kind of row. That's this part's claim made visible: there is no second system moving the pages around. One loop — events write state, subs read it, views render it — and the URL is just one more sub.

---

**You can now:**

- seed app-db through an initialise event, with each feature's state under one slice key
- shape a slice as `{:status :data :error}` so a view can always ask what state its data is in
- register layered subscriptions with `:<-`, including parameterised ones like `:articles/by-slug`
- write `reg-view` views that read subs with `@(subscribe ...)` and link with `rf/route-link`
- register a route table (including the required `:rf.route/not-found`) and render pages from a `case` over `:rf.route/id`, with the URL driving the whole thing

**Next:** [Part 2: real data — resources and the nine states](02-server-data.md), where the canned articles give way to a real server and the slice's `:status` stops being decorative. For the rest of the routing picture — query params, data loading on navigation, blocked navigation — see [Routing: the URL is a sub](../concepts/routing.md).
