# Part 1: pages, state, and the first feed

You left [the setup page](index.md) with an empty Conduit shell. By the end of this part it has two real pages — the home feed and the article page — and the URL decides which one you see. Type `/article/welcome-to-conduit` into the address bar and that article renders; press Back and the feed returns. Along the way you'll write your first [event](../../core/glossary.md#event), your first [subscriptions](../../core/glossary.md#subscription), and your first [views](../../core/glossary.md#view).

That trio — events write, subs read, views render — is the pipeline everything else in re-frame2 builds on. This part is offline: nothing fetches, so you can watch the pipeline run with nothing else moving. Real server data arrives in [Part 2](02-server-data.md).

**The takeaway: the URL is a sub, and a page is a view of it.**

??? info "Coming from React Router?"

    There is no `<Routes>` tree, no router context, no `useParams` hook. A route is a registry entry, navigating is dispatching an event, and the current route is an ordinary [subscription](../../core/glossary.md#subscription) your root view reads like any other state.

You'll touch two files:

```text
src/conduit/articles.cljs   ; the articles slice: seed data, event, subs, views
src/conduit/core.cljs       ; routes, the root view, boot
```

## Step 1 — canned articles into app-db

All of your app's state lives in [**app-db**](../../core/glossary.md#app-db), a single immutable map. A feature claims one top-level key and keeps everything it owns underneath; we call that corner a **slice**. Articles get the `:articles` key.

State changes one way only. An [**event**](../../core/glossary.md#event) is data announcing that something happened — at its simplest a vector like `[:app/initialise]`: a keyword id plus any payload. You [**dispatch**](../../core/glossary.md#dispatch) it, and the registered [**event handler**](../../core/glossary.md#event-handler) computes the next value of app-db. So even seeding canned data is an event:

```clojure
;; src/conduit/articles.cljs
(ns conduit.articles
  (:require [re-frame.core :as rf])
  (:require-macros [re-frame.core :refer [reg-view]]))

(def seed-articles
  [{:slug        "welcome-to-conduit"
    :title       "Welcome to Conduit"
    :description "What you are building, and why."
    :body        "Conduit is a Medium-style publishing app: feeds, auth, favoriting, publishing."
    :tagList     ["intro"]
    :createdAt   "2026-06-01T09:00:00Z"
    :author      {:username "octocat"}}
   {:slug        "events-write-subs-read"
    :title       "Events write, subs read"
    :description "The one-way event pipeline that drives the app."
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

(rf/reg-event :app/initialise
  {:doc "Seed app-db at boot. Part 2 replaces the canned articles
         with a real fetch."}
  (fn [_cofx _event]
    {:db {:articles {:status :loaded
                     :data   seed-articles
                     :error  nil}}}))
```

`reg-event` registers an [event handler](../../core/glossary.md#event-handler): a pure function, two arguments in, one map out. This is the shape you'll write a hundred times.

**The two arguments in** are the [**coeffects**](../../core/glossary.md#coeffect) and the event. Coeffects are the facts the handler is *handed*, so it never reaches out to the world itself. The one you'll use constantly is `:db`, the current app-db. (This handler ignores both arguments; the leading underscore in `_cofx` and `_event` is the Clojure convention for an unused parameter.)

**The one map out** is the [**effect map**](../../core/glossary.md#effect-map): the next state, plus anything else to do. It has a small, **closed** set of keys, and two do almost all the work:

- `:db` — the next app-db.
- `:fx` — a vector of [effects](../../core/glossary.md#effect) to run: dispatches, HTTP, navigation, anything that reaches the outside world.

This handler returns only `:db`, because seeding canned data touches nothing outside the map. You'll meet `:fx` and one more key, `:sensitive`, in [Part 3](03-auth-and-forms.md#keeping-the-jwt-redacted-on-both-surfaces). Because the set is closed, returning a key outside it raises an error the moment the handler runs, so a typo'd effect key can't silently do nothing.

This replaces the placeholder `:app/initialise` that setup dropped into `core.cljs`. Delete that old registration now, so the two don't fight over the same id — Step 4 rewrites the rest of that file anyway.

??? info "From re-frame v1"

    If you've used re-frame v1 you'll reach for `reg-event-db` or `reg-event-fx` out of muscle memory. There's just `reg-event` now: the same shape — coeffects in, an effect map out — under the bare name. A pure state change returns `{:db …}`; one that reaches the outside world adds `:fx`. The old names aren't quiet aliases that'll lull you into thinking nothing changed — calling `reg-event-db` raises a loud `:rf.error/reg-event-db-removed` that names `reg-event` as the replacement. The migration is a rename, not a guessing game.

!!! warning "Gotcha — re-registering an id replaces it"

    [Registries](../../core/glossary.md#registrar) are last-write-wins. If both `core.cljs` and `articles.cljs` register `:app/initialise`, whichever namespace loads last silently wins, and the other body never runs. That's why you *delete* the placeholder rather than leaving it: a duplicate id doesn't error, it just quietly shadows. (The dev build does emit a `:rf.registry/handler-replaced` trace, so a tool like [Xray](../../core/glossary.md#xray) can show you the swap — but your eyes won't catch it in the source.)

Now look at the slice's shape. It isn't a bare vector of articles; it's a map that carries the data *and its lifecycle*:

```clojure
{:status :loaded   ; what state is this data in?
 :data   [...]     ; the articles themselves
 :error  nil}      ; what went wrong, if anything
```

With canned data the slice is born `:loaded` and never moves, so the shape looks like overkill today. Every real page eventually has to answer "what state is my data in?" — loading, loaded, or failed — and Part 2 makes those states real.

??? info "Coming from TanStack Query?"

    A `useQuery` result hands you `isLoading`, `isError`, `data`, and `error`. The `{:status :data :error}` slice is the same idea as plain data in app-db, where any sub can read it and Xray can show it. Part 2 replaces this hand-built slice with a resource, which gives you the same lifecycle without writing it.

??? note "The deeper story of the one-map design"

    The full rationale for keeping all state in a single map lives in [app-db: the one place](../../core/app-db.md).

## Step 2 — subscriptions: named, derived reads

Views never reach into app-db directly; if they did, reshaping a slice would mean hunting down every reader. Instead, views read [**subscriptions**](../../core/glossary.md#subscription): named, registered, pure derivations that read state for you and cache the result. Add three to `articles.cljs`:

```clojure
(rf/reg-sub :articles/slice
  (fn [db _] (:articles db)))

(rf/reg-sub :articles/data {:inputs [[:articles/slice]]}
  (fn [[slice] _] (:data slice)))

(rf/reg-sub :articles/by-slug {:inputs [[:articles/data]]}
  (fn [[articles] [_ slug]]
    (first (filter #(= slug (:slug %)) articles))))
```

The first reads straight from app-db — it declares no `:inputs`, so its first argument *is* `db`. The other two declare an `:inputs` vector, and their bodies receive the resolved values as a vector (`[slice]`, `[articles]`) — always a vector, at one input as at several: `:articles/data` derives from `:articles/slice`, and `:articles/by-slug` derives from `:articles/data`. Subscriptions form a graph — [the derivation graph](../../core/glossary.md#the-derivation-graph). A sub recomputes only when an input produces a new value (compared by `=`), and a view re-renders only when the sub it reads produces a new value.

`:articles/by-slug` takes an argument. A view asks for a subscription with a [**query vector**](../../core/glossary.md#query-vector) — a sub-id followed by any arguments, the very same shape an event has — so to ask for one article it writes `[:articles/by-slug "welcome-to-conduit"]`. The computation function receives that whole vector, destructured here as `[_ slug]`: the first element is the sub-id itself (`:articles/by-slug`), discarded as `_` because the function already knows which sub it is, and everything after it is your argument.

The top sub (`:articles/slice`) reads the raw slice; the layers below shape it into what one view needs. That way the derived subs never re-run because an unrelated corner of app-db changed.

!!! warning "Gotcha — a sub with no registration fails loud"

    Dereference a sub-id you never registered (a typo — `@(subscribe [:articles/dat])`) and you get `:rf.error/no-such-handler` naming the missing id, not a silent `nil`. The same goes for an `:inputs` vector pointing at an unregistered sub.

??? note "The full derivation-graph story"

    For how the graph recomputes and stays cheap, see [Subscriptions: the derivation graph](../../core/subscriptions.md).

## Step 3 — views: the feed, rendered

A [**view**](../../core/glossary.md#view) is a pure function from subscription values to [**hiccup**](../../core/glossary.md#hiccup) — plain Clojure data describing your UI. A vector whose first element is a keyword is one DOM element:

```clojure
[:h1.logo-font "conduit"]
;; → <h1 class="logo-font">conduit</h1>
;;   :h1        the tag
;;   .logo-font a CSS class, glued on with a dot (you can chain .a.b)
;;   "conduit"  a child — strings, and more vectors, follow the tag
```

An optional map right after the tag carries attributes — `[:a {:href "/"} "home"]`. That's the whole notation. Register views with `reg-view`, still in `articles.cljs`:

```clojure
;; Adapted from examples/real-apps/realworld_http/articles.cljs
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

(`rf/route-link` renders a link to another page; Step 4 builds the routes it points at.)

Three things to notice:

- **`reg-view` defines and registers in one move.** It `def`s the symbol, which is why `[article-preview {...}]` works as plain hiccup. It also injects `dispatch` and `subscribe`, as the setup page showed, which is why `home-page` calls `subscribe` without an `rf/` prefix.
- **Dereferencing a sub is the subscription.** `@(subscribe [:articles/data])` gives you the value now and signs the view up to re-render when it changes, so there are no dependency arrays to maintain. (Coming from React, it does the job of `useSelector` with automatic dependency tracking.)
- **`article-page` already reads the route.** `:rf.route/params` is an ordinary subscription yielding the current URL's captured params (here `{:slug "..."}`), and the page chains that into `:articles/by-slug`. The `if` handles a slug that matches the route pattern but names no article — a real URL someone can type.

Two hiccup details the listing leans on:

- **A child view takes its props as one map.** `article-preview`'s parameter list is `[{:keys [article]}]`, so the call site passes `{:article article}`, not positional args.
- **Every element in a `for`-generated list needs a `^{:key …}`.** It's how React tells list items apart across re-renders. Use a stable field from the data (a slug, an id), never the loop index; without one React warns and falls back to index-based reconciliation, which misbehaves when the list reorders.

??? note "Why views stay pure"

    What purity buys you, and where the line is drawn, is covered in [Views: pure functions of data](../../core/views.md).

## Step 4 — the routing skeleton

Routing ships as its own artefact, so apps that don't route don't carry it. Add it to `deps.edn`, then restart `npm run dev` — the watcher reads `deps.edn` only at startup:

```clojure
{:deps    {thheller/shadow-cljs   {:mvn/version "3.4.10"}
           day8/re-frame2         {:local/root "../re-frame2/implementation/core"}
           day8/re-frame2-reagent {:local/root "../re-frame2/implementation/adapters/reagent"}
           day8/re-frame2-routing {:local/root "../re-frame2/implementation/routing"}}
 :aliases {:dev {:extra-deps {day8/re-frame2-xray {:local/root "../re-frame2/tools/xray"}}}}}
```

Now for `core.cljs`. You're replacing the whole file from setup: the placeholder navbar becomes a real header, and `:app/initialise` has already moved to the articles namespace (the session state returns in Part 3, when there's actually someone to sign in). Routes first:

```clojure
;; src/conduit/core.cljs
;; Adapted from examples/capabilities/routing/routing/core.cljs
(ns conduit.core
  (:require [re-frame.core :as rf]
            ;; Loading re-frame.routing once at boot is what makes
            ;; reg-route, route-link, and the :rf.route/* events and
            ;; subs available through re-frame.core.
            [re-frame.routing]
            [re-frame.adapter.reagent :as reagent-adapter]
            [conduit.articles :as articles])
  (:require-macros [re-frame.core :refer [reg-view]]))

(rf/reg-route :conduit/home
  {:doc  "The home page: the article feed."}
  "/")

(rf/reg-route :conduit.article/show
  {:doc    "One article, addressed by its slug."
   :params [:map [:slug :string]]}
  "/article/:slug")

(rf/reg-route :rf.route/not-found
  {:doc  "Fallback page for URLs that match nothing."}
  "/_404")
```

!!! note "If routing isn't loaded"

    Forget that `[re-frame.routing]` require and the first `rf/reg-route` raises `:rf.error/routing-artefact-missing`, naming the namespace to require.

A route is a registry entry, like an event or a sub. `reg-route` has a fixed three-slot shape — `(reg-route id metadata path)` — and **the path is the third argument, not a metadata key**. The middle slot is description: `:doc`, the `:params` schema, and later `:on-match` and a few others. The path (`"/"`, `"/article/:slug"`) is a pattern; `:slug` is a named segment, and whatever it captures arrives in the `:rf.route/params` sub your article page already reads.

!!! warning "Gotcha — `:path` is not a metadata key, and unknown keys throw"

    Tucking the path inside the metadata map — `(reg-route :conduit/home {:path "/"})` — raises `:rf.error/route-bad-metadata`. So does any *bare* (unqualified) key outside the reserved set, such as `:querey` for `:query`; the error names the key and lists the valid ones. Your own namespaced keys (`:myapp/layout`) are always allowed.

The `:params` schema names the capture's shape. Enforcement is opt-in: once the [schemas](../../core/glossary.md#schema) artefact is on the classpath ([Validate with schemas](../../core/how-to/validate-with-schemas.md)), a URL whose params fail validation is treated as unmatched.

`:rf.route/not-found` is the one route id the framework reserves. When a URL matches nothing, the runtime routes to it with the offending URL in `:rf.route/params`. Register it and you own its page. Skip it and the runtime still routes there and emits a `:rf.warning/no-not-found-route` trace, but there is no built-in placeholder page: what renders is whatever your root view does with `:rf.route/not-found`.

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

This `case` is the whole router: **the root view subscribes to `:rf.route/id` and maps route ids to pages.** There are no route components and no `<Outlet>`. The trailing `[not-found-page]` is the `case` default, so a route you register but forget to wire here renders the not-found page rather than a blank screen.

Three route subscriptions cover most needs:

| Sub | Returns |
|---|---|
| `:rf.route/id` | The active route id (the keyword), e.g. `:conduit.article/show`. This is what your root-view `case` switches on. |
| `:rf.route/params` | The path params the URL captured, e.g. `{:slug "welcome-to-conduit"}`. The not-found route puts the unmatched URL here under `:url`. |
| `:rf/route` | The whole route slice — `:route-id`, `:params`, `:query`, `:fragment`, plus `:transition` (`:idle` / `:loading` / `:error`) and an `:error` slot. You'll reach for the transition and error fields once data loading enters the picture in Part 2. |

Finer-grained subs exist too — `:rf.route/query`, `:rf.route/fragment`, `:rf.route/transition`, `:rf.route/error` — but these three are all this part needs. The route lives in [runtime-db](../../core/glossary.md#runtime-db), the framework's half of the frame, but you read it like any other state.

For navigation, `rf/route-link` renders a real `<a href="...">` and turns a plain left-click into a [dispatched](../../core/glossary.md#dispatch) event; Cmd-, middle- and shift-click fall through to the browser, so open-in-new-tab still works. Its props map takes `:to` (the route id, required), `:params`, `:query` and `:fragment`, and passes every other attribute (`:class`, `:id`, `:data-testid`, …) straight through to the `<a>`. (A link with `:target` other than `"_self"`, or with `:download`, is left to the browser even on a plain click.)

To navigate from code — after a successful form submit, say — dispatch an event:

```clojure
(rf/dispatch [:rf.route/navigate {:to :conduit.article/show :params {:slug "welcome-to-conduit"}}])
```

`:rf.route/navigate` takes one **request map**: the route id in `:to`, its path params in `:params`, and options that tune the navigation alongside them:

```clojure
;; Replace the current history entry instead of pushing a new one —
;; the Back button won't return to the URL you're leaving. Handy for
;; redirects and login-flow returns.
(rf/dispatch [:rf.route/navigate {:to :conduit/home :replace? true}])
```

Whether the user clicked a link, pressed Back, or your handler decided to move, every route change arrives as an event.

??? note "URLs both ways — the pure helpers"

    The route table is bidirectional, and that fact is exposed as two **pure** functions you can call anywhere — in a handler, in a test, on the JVM during server rendering. They live in `re-frame.routing`, not on the `rf/` facade:

    - `(re-frame.routing/route-url {:to :conduit.article/show :params {:slug "welcome-to-conduit"}})` → `"/article/welcome-to-conduit"`. Builds the URL string from an address map. It does **not** navigate — it's a string-builder. `:rf.route/navigate` uses it internally.
    - `(re-frame.routing/match-url "/article/welcome-to-conduit")` → `{:route-id :conduit.article/show :params {:slug "..."} :query {} :fragment nil ...}`, or `nil` when nothing matches. The inverse direction.

    You won't need either in this part — `route-link` and `:rf.route/navigate` cover the app's own navigation — but they're the functions tests and SSR call to turn a route into a URL without an app-db in hand.

## Step 5 — boot: one frame that owns the URL

Finish `core.cljs` with the boot function your build invokes:

```clojure
(defonce app-root (reagent-adapter/client-root))

(defn run []
  (rf/init! reagent-adapter/adapter)
  (rf/make-frame {:id         :rf/default
                  :doc        "The Conduit app frame."
                  :url-bound? true})
  (rf/with-frame :rf/default
    (rf/dispatch-sync [:app/initialise]))
  (reagent-adapter/render! app-root
    [rf/frame-provider {:frame :rf/default}
     [root-view]]
    (js/document.getElementById "app")))
```

It's the setup page's boot with one addition: `:url-bound? true`, which declares that *this* frame owns the browser URL. Nothing owns the URL by default, so without the flag the address bar never changes. The flag also syncs the initial URL into route state when the frame is created, so deep links work on first paint, and turns Back/Forward into the same kind of route-change event a link click produces.

Only one frame may claim the URL. A second `:url-bound? true` frame doesn't throw: the first keeps the URL, the second's navigation effects do nothing, and the runtime reports `:rf.error/duplicate-url-binding` naming both frames.

## See it move

With the dev build running, open the app and walk through what you just built:

1. **Click an article.** The page changes and the address bar reads `/article/events-write-subs-read`. You wrote no URL-sync code; the URL follows the route state.
2. **Press Back.** The feed returns: Back arrived as an event, the route slice changed, and your `case` picked the other page.
3. **Type a URL by hand.** Visit `/article/the-url-is-a-sub` directly; the deep link works because boot syncs URL→state before first render. Now try `/article/nope` and then `/definitely-not-a-route` — two different failures, each rendered by a view you wrote.

`/article/nope` **matches** `:conduit.article/show` — `nope` is a valid `:slug` — so the route resolves, `:articles/by-slug` returns `nil`, and your article page's `if` renders "There's no article called nope here." `/definitely-not-a-route` matches **no route**, so the runtime routes to `:rf.route/not-found` with `{:url "/definitely-not-a-route"}` in `:rf.route/params`, and your not-found page renders. (With schema enforcement on, a slug that fails its schema also routes to not-found.)

Open Xray while you click. Each navigation is an event row followed by the route state changing, and link clicks, Back presses and address-bar entries all produce the same kind of row. [Part 2](02-server-data.md) keeps this shape and swaps the canned seed for a real server fetch.
