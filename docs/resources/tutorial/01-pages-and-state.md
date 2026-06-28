# Part 1: pages, state, and the first feed

You left [the setup page](index.md) with an empty Conduit shell. By the end of this part it has two real pages — the home feed and the article page — and the URL decides which one you see. Type `/article/welcome-to-conduit` into the address bar and that article renders; press Back and the feed returns. Along the way you'll write your first [event](../../core/glossary.md#event), your first [subscriptions](../../core/glossary.md#subscription), and your first [views](../../core/glossary.md#view).

That trio — events write, subs read, views render — is the loop everything else in re-frame2 builds on. Get comfortable with it here and the rest of the guide is variations on a theme.

Real server data arrives in [Part 2](02-server-data.md). This part is offline: nothing fetches, nothing ticks, so you can watch the state loop run on its own with nothing else moving around it.

**The takeaway: the URL is a sub, and a page is just a view of it.**

> **Coming from React Router?** There is no `<Routes>` tree, no router context, no `useParams` hook. A route is a registry entry, navigating is dispatching an event, and the current route is an ordinary [subscription](../../core/glossary.md#subscription) your root view reads like any other piece of state. Everything this part teaches about "state in, hiccup out" applies to pages *unchanged* — and that's the whole point.

You'll touch two files:

```text
src/conduit/articles.cljs   ; the articles slice: seed data, event, subs, views
src/conduit/core.cljs       ; routes, the root view, boot
```

## Step 1 — canned articles into app-db

All of your app's state lives in one place: [**app-db**](../../core/glossary.md#app-db), a single immutable map. One map, one source of truth. A feature claims one top-level key and keeps everything it owns underneath. We call that corner a **slice** — one feature's patch of the state map. Articles get the `:articles` key.

State only ever changes one way around here, and it's worth saying out loud because it's the rule the whole framework rests on. An [**event**](../../core/glossary.md#event) is a small piece of data announcing that something happened — at its simplest a vector like `[:app/initialise]`: a keyword id, plus any payload. You [**dispatch**](../../core/glossary.md#dispatch) it, and the registered [**event handler**](../../core/glossary.md#event-handler) computes the next value of app-db. Views dispatch events; handlers compute new state. That's it.

So even seeding canned data is an event — there's no back door for "the first state":

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

(rf/reg-event :app/initialise
  {:doc "Seed app-db at boot. Part 2 replaces the canned articles
         with a real fetch."}
  (fn [_cofx _event]
    {:db {:articles {:status :loaded
                     :data   seed-articles
                     :error  nil}}}))
```

`reg-event` registers an [event handler](../../core/glossary.md#event-handler): a pure function, two arguments in, one map out. Let's take both sides in turn, because they're the load-bearing shape you'll write a hundred times.

**The two arguments in** are the [**coeffects**](../../core/glossary.md#coeffect) and the event. Coeffects are the facts the handler is *handed* so it can stay pure — it never reaches out to the world itself; the world is delivered to it. The one you'll reach for constantly is `:db`, the current app-db. (This particular handler ignores both arguments, written `_cofx` and `_event`. The leading underscore is the Clojure convention for "yes, I know this parameter is here; no, I'm not using it" — it keeps the linter quiet and signals intent to the next reader.)

**The one map out** is the [**effect map**](../../core/glossary.md#effect-map): a description of what should happen next. Read it as *"the next state, plus anything else to do."* Its `:db` key is the new app-db — and since seeding canned data touches nothing else, this handler returns `{:db …}` and hands back the whole initial map. That's all an initialise event really is: a function that ignores the world and returns the starting state.

The effect map is a small, **closed** vocabulary — exactly two keys:

- `:db` — the next app-db.
- `:fx` — a vector of [effects](../../core/glossary.md#effect) to run: dispatches, HTTP, navigation, anything that reaches the outside world.

This handler uses only `:db`, because seeding canned data touches nothing outside the map — no DOM, no network, no clock. You'll meet `:fx` in Part 2, when the feed starts loading from a server and the handler genuinely needs the outside world. Crucially, the vocabulary is *closed*: return a third key and you get a [fail-loud](../../core/glossary.md#fail-loud-not-silent) error, not a silent no-op. A typo in an effect key surfaces the instant the handler runs, instead of vanishing into a feature that mysteriously never happens.

This replaces the placeholder `:app/initialise` that setup dropped into `core.cljs`. Delete that old registration now, so the two don't fight over the same id — Step 4 rewrites the rest of that file anyway.

> **From re-frame v1.** If you've used re-frame v1 you'll reach for `reg-event-db` or `reg-event-fx` out of muscle memory. There's just `reg-event` now: the same shape — coeffects in, an effect map out — under the bare name. A pure state change returns `{:db …}`; one that reaches the outside world adds `:fx`. The old names aren't quiet aliases that'll lull you into thinking nothing changed — calling `reg-event-db` raises a loud `:rf.error/reg-event-db-removed` that names `reg-event` as the replacement. The migration is a rename, not a guessing game.

> **Gotcha — re-registering an id replaces it.** [Registries](../../core/glossary.md#registrar) are last-write-wins. If both `core.cljs` and `articles.cljs` register `:app/initialise`, whichever namespace loads last silently wins, and the other body never runs. That's why you *delete* the placeholder rather than leaving it: a duplicate id doesn't error, it just quietly shadows. (The dev build does emit a `:rf.registry/handler-replaced` trace, so a tool like [Xray](../../core/glossary.md#xray) can show you the swap — but your eyes won't catch it in the source.)

Now look at the slice's shape. It isn't a bare vector of articles; it's a map that carries the data *and its lifecycle*:

```clojure
{:status :loaded   ; what state is this data in?
 :data   [...]     ; the articles themselves
 :error  nil}      ; what went wrong, if anything
```

With canned data the slice is born `:loaded` and never moves, so today this shape can look like wearing a seatbelt to sit on the sofa. Here's why it earns its keep anyway: every real page eventually has to answer "what state is my data in?" — loading, loaded, or failed. Part 2 makes those states real. Start with the honest shape now and you never retrofit it later; the view code you write today already knows how to ask.

> **Coming from TanStack Query?** A `useQuery` result hands you `isLoading`, `isError`, `data`, and `error` so a component can branch on where the fetch sits in its lifecycle. The `{:status :data :error}` slice is the same idea — written as plain data you own and store yourself, rather than a library object handed back to you. The difference: here the lifecycle lives *in* app-db, where any sub can read it, any view can branch on it, and Xray can show it to you. There's no opaque cache off to one side that only the library can see into.

??? note "The deeper story of the one-map design"

    The full rationale for keeping all state in a single map lives in [app-db: the one place](../../core/concepts/app-db.md).

## Step 2 — subscriptions: named, derived reads

Views never reach into app-db directly. (If they did, every view would need to know the exact shape of the map, and reshaping a slice would mean hunting down every reader — the coupling re-frame2 exists to avoid.) Instead, views read [**subscriptions**](../../core/glossary.md#subscription): named, registered, pure derivations that read state *for* you, and cache the result. Add three to `articles.cljs`:

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

The first reads straight from app-db. The other two use `:<-` to declare an *input subscription*: `:articles/data` derives from `:articles/slice`, and `:articles/by-slug` derives from `:articles/data`. Subscriptions form a graph — [the derivation graph](../../core/glossary.md#the-derivation-graph) — and that graph is exactly what makes them cheap. A sub recomputes only when an input it actually reads produces a *new* value (compared by `=`), and a view re-renders only when the sub it reads produces a new value. Nothing recomputes "just in case."

`:articles/by-slug` takes an argument. A view asks for a subscription with a [**query vector**](../../core/glossary.md#query-vector) — a sub-id followed by any arguments, the very same shape an event has — so to ask for one article it writes `[:articles/by-slug "welcome-to-conduit"]`. The computation function receives that whole vector, destructured here as `[_ slug]`: the first element is the sub-id itself (`:articles/by-slug`), discarded as `_` because the function already knows which sub it is, and everything after it is your argument.

The two-layer split is a habit worth forming early. The top sub (`:articles/slice`) reads the raw slice; the layers below (`:articles/data`, `:articles/by-slug`) shape that slice into exactly what one view needs. Keeping the raw read in its own sub means the lifecycle fields (`:status`, `:error`) are one cheap subscription away when Part 2 needs them — and the derived subs never re-run just because some *unrelated* corner of app-db changed.

> **Gotcha — a sub with no registration fails loud.** Dereference a sub-id you never registered (a typo — `@(subscribe [:articles/dat])`) and you get a `:rf.error/no-such-handler`, not a silent `nil` that propagates into a blank screen three components away. The same goes for an input vector pointing at an unregistered sub. The error names the missing id, so a fat-fingered keyword is a one-line fix rather than an afternoon's debugging.

??? note "The full derivation-graph story"

    For how the graph recomputes and stays cheap, see [Subscriptions: the derivation graph](../../core/concepts/subscriptions.md).

## Step 3 — views: the feed, rendered

A [**view**](../../core/glossary.md#view) is a pure function from subscription values to [**hiccup**](../../core/glossary.md#hiccup) — the plain Clojure data that describes your UI. Hiccup is just nested vectors: a vector whose first element is a keyword is one DOM element, and it reads like the tag it builds:

```clojure
[:h1.logo-font "conduit"]
;; → <h1 class="logo-font">conduit</h1>
;;   :h1        the tag
;;   .logo-font a CSS class, glued on with a dot (you can chain .a.b)
;;   "conduit"  a child — strings, and more vectors, follow the tag
```

An optional map right after the tag carries attributes — `[:a {:href "/"} "home"]`. That's the entire notation; there's no template language to learn, because markup here *is* data. Register views with `reg-view`, still in `articles.cljs`:

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

(One unfamiliar form appears in there: `rf/route-link` renders a clickable link to another page. Treat it as a black box for now — Step 4 builds the routes it points at and explains it fully.)

Three things are worth pointing out, because each one trips people up the first time:

- **`reg-view` defines and registers in one move.** It `def`s the symbol, which is why `[article-preview {...}]` works as plain hiccup. It also injects `dispatch` (the verb that fires an event) and `subscribe` as lexical bindings, which is why `home-page` calls `subscribe` without an `rf/` prefix.
- **`@` reads the current value.** `@(subscribe [:articles/data])` gives you the value *right now*, and — this is the magic — it also signs the view up to re-render whenever that value changes. That's the entire data-binding story. No dependency arrays, no manual wiring, no "did I remember to subscribe?"

    > **Coming from React hooks?** `@(subscribe …)` does the job of `useSelector` plus a `useMemo` dependency array, except the dependency tracking is automatic. You never list what a view depends on; the act of *dereferencing the sub* is the subscription. Read a sub and you're subscribed to it; read a different one next render and the wiring re-wires itself. There's no stale-closure footgun and no exhaustive-deps lint rule, because there are no deps to get wrong in the first place.
- **`article-page` already reads the route.** `:rf.route/params` is a subscription like any other — no special-case routing API, just a sub. It yields the current URL's captured params (here `{:slug "..."}`), and the page chains that straight into `:articles/by-slug`. The `if` handles a slug that matches the route pattern but names no actual article — that's a real URL someone can type, so it's a real branch your view owns.

Two more hiccup details the listing above quietly leans on, since both bite newcomers:

- **A view called as `[article-preview {...}]` takes its props as a single map argument.** `article-preview`'s parameter list is `[{:keys [article]}]` — one map, destructured. That's why the call site passes `{:article article}`, not positional args. A child view always receives exactly one argument: the props map after its symbol in the hiccup vector.
- **Every element in a `for`-generated list needs a `^{:key …}` metadata tag.** The `^{:key (:slug article)}` on each preview and `^{:key tag}` on each tag isn't decoration — it's how the renderer tells one list item from another across re-renders. Forget it and React falls back to index-based reconciliation, which breeds subtle bugs the moment the list reorders (plus a console warning to remind you). Use a stable, unique field from the data (a slug, an id) — never the loop index.

Those `rf/route-link`s point at a route id that doesn't exist yet. We add it next.

??? note "Why views stay pure"

    What purity buys you, and where the line is drawn, is covered in [Views: pure functions of data](../../core/concepts/views.md).

## Step 4 — the routing skeleton

Routing ships as its own artefact, so apps that don't route don't have to carry it. The setup page's `deps.edn` doesn't include it yet. Add it beside the core and adapter entries, then restart `npm run dev` — the watcher resolves `deps.edn` only at startup, so a new dependency won't appear until you bounce it:

```clojure
{:deps    {day8/re-frame2         {:local/root "../re-frame2/implementation/core"}
           day8/re-frame2-reagent {:local/root "../re-frame2/implementation/adapters/reagent"}
           day8/re-frame2-routing {:local/root "../re-frame2/implementation/routing"}}
 :aliases {:dev {:extra-deps {day8/re-frame2-xray {:local/root "../re-frame2/tools/xray"}}}}}
```

Now for `core.cljs`. You're replacing the whole file from setup: the placeholder navbar becomes a real header, and `:app/initialise` has already moved to the articles namespace (the session state returns in Part 3, when there's actually someone to sign in). Routes first:

```clojure
;; src/conduit/core.cljs
;; Adapted from examples/capabilities/routing/routing/core.cljs
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

    Forget that `[re-frame.routing]` require and the first `rf/reg-route` raises `:rf.error/routing-artefact-missing`. The error names the artefact *and* the namespace to require, so it's a quick fix — one more named failure mode for setup's collection.

A route is a registry entry, exactly like an event or a sub: data, not components. `reg-route` has a fixed three-slot shape — `(reg-route id metadata path)` — and the one thing newcomers reliably trip on is this: **the path is the third positional argument, a value, not a metadata key**. The middle slot is pure description — `:doc`, the `:params` schema, and (when you need them) `:query`, `:on-match`, and a handful of others you'll meet later. The path (`"/"`, `"/article/:slug"`) is a pattern, `:slug` is a named segment, and whatever it captures arrives in the `:rf.route/params` sub your article page already reads.

> **Gotcha — `:path` is not a metadata key, and unknown keys throw.** Two adjacent mistakes both fail loud at registration, which is exactly when you want to hear about them. The first: tucking the path *inside* the metadata map — `(reg-route :conduit/home {:path "/"})` — raises `:rf.error/invalid-route-metadata`, because the path is the third value, not a key. The second: a typo in a reserved metadata key, like `:querey` for `:query` or `:on-matched` for `:on-match`. `reg-route` carries the largest metadata shape in the framework, so a misspelled key could otherwise sit silently until it failed at navigation time, far from the cause. To stop that, any *bare* (unqualified) key outside the reserved set is rejected right at registration with the same `:rf.error/invalid-route-metadata`, naming the offending key and listing the valid vocabulary. Your own namespaced keys (`:myapp/layout`) are always welcome; it's only bare typos that get caught.

The `:params` schema names the capture's shape. Enforcement is opt-in: once the [schemas](../../core/glossary.md#schema) artefact joins the classpath ([Validate with schemas](../../core/how-to/validate-with-schemas.md)), a URL whose params fail validation is treated as *unmatched* instead of limping through your views half-parsed. Until then the schema is checked-later documentation — and a single `:string` slug has nothing to fail anyway.

`:rf.route/not-found` is the one route id the framework reserves. Whenever a URL matches nothing, the runtime routes to it with the offending URL in `:rf.route/params`. Every app should register it — it's an ordinary route, and you own its page. (Skip it and the app still works: the runtime falls back to a built-in `<h1>Not Found</h1>` placeholder and emits a `:rf.warning/no-not-found-route` trace, nudging you to register your own.)

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

This `case` is the whole router, and it's the heart of this part: **the root view subscribes to `:rf.route/id` and maps route ids to pages.** No route components, no `<Outlet>`, no nesting tree — a page is just the view your `case` picks for the current value of a sub. The trailing `[not-found-page]` is the `case` default, catching any route id you haven't wired in yet — register a route but forget to add its page and it lands here, rather than rendering a blank screen and leaving you guessing.

Three route subscriptions cover most needs:

| Sub | Returns |
|---|---|
| `:rf.route/id` | The active route id (the keyword), e.g. `:conduit.article/show`. This is what your root-view `case` switches on. |
| `:rf.route/params` | The path params the URL captured, e.g. `{:slug "welcome-to-conduit"}`. The not-found route puts the unmatched URL here under `:url`. |
| `:rf/route` | The whole route slice — `:route-id`, `:params`, `:query`, `:fragment`, plus `:transition` (`:idle` / `:loading` / `:error`) and an `:error` slot. You'll reach for the transition and error fields once data loading enters the picture in Part 2. |

There are finer-grained subs too — `:rf.route/query` for the `?key=value` portion, `:rf.route/fragment` for the `#anchor`, and `:rf.route/transition` / `:rf.route/error` for the loading lifecycle — but the three above are all this part needs. Each is an ordinary subscription, so a view reads the route exactly the way it reads any other piece of state. The route lives in [runtime-db](../../core/glossary.md#runtime-db) (the framework's half of the frame), but you'd never know it from the call site — a sub is a sub.

And navigation? `rf/route-link` renders a real `<a href="...">` and turns a plain click into a [dispatched](../../core/glossary.md#dispatch) event. Cmd-click, middle-click, and shift-click fall through to the browser, so open-in-new-tab and open-in-new-window keep working exactly as a user expects — you get a real anchor, not a div pretending to be one. Its props map takes `:to` (the target route id, required), `:params` (the path params, when the route has any), `:query` and `:fragment` (folded into the synthesised href when present), and **any other attribute passes straight through to the underlying `<a>`** — `:class`, `:id`, `:data-testid`, and so on. That's why `[rf/route-link {:to :conduit/home :class "navbar-brand"} "conduit"]` styles the anchor exactly as if you'd written the `:a` by hand.

> **Gotcha — anchors that should behave like anchors aren't intercepted.** Two attributes opt a `route-link` *out* of SPA interception even on a plain left-click, because the DOM already promises something the framework must not override: `:target` set to anything but `"_self"` (e.g. `{:target "_blank"}`) and `:download`. A link carrying either looks like an ordinary anchor to the user — they expect a new tab, or a saved file — so the click falls through to the browser instead of dispatching `:rf/url-requested`. You rarely want `:target "_blank"` on an *internal* route, but it's worth knowing the rule exists the day you reach for it.

To navigate from code — after a successful form submit, say — it's an event like everything else:

```clojure
(rf/dispatch [:rf.route/navigate :conduit.article/show {:slug "welcome-to-conduit"}])
```

`:rf.route/navigate` takes the target route id first and the route's **path params** second. When you need to tune *how* the navigation happens, a third **opts** map comes after the params:

```clojure
;; Replace the current history entry instead of pushing a new one —
;; the Back button won't return to the URL you're leaving. Handy for
;; redirects and login-flow returns.
(rf/dispatch [:rf.route/navigate :conduit/home {} {:replace? true}])
```

> **Gotcha — params is slot two, opts is slot three.** Both are maps, so they're easy to swap, and the swap is the classic mistake: `[:rf.route/navigate :conduit/home {:replace? true}]` *reads* like "navigate, replacing history," but it parses as "navigate with a path-param called `:replace?`" — which `:conduit/home` doesn't have. The runtime catches exactly this case and rejects the navigation with `:rf.error/navigate-arity-misuse`, naming the misplaced key, rather than silently doing the wrong thing. To pass opts, give an explicit (possibly empty) params map first: `[:rf.route/navigate :conduit/home {} {:replace? true}]`.

One verb, `dispatch`, whether the user clicked a link, pressed Back, or your handler decided to move. Every path funnels into the same state change — which is why, when something goes wrong, there's only ever one place to look.

> **From re-frame v1.** There was no routing in re-frame v1 — you reached for an external library (`reitit`, `secretary`, `bidi`) and hand-glued its match events into your event handlers. Routing now ships as a first-class re-frame2 artefact: the route table is a registry like events and subs, the current route is an ordinary subscription, and navigation is a dispatch. Nothing here is a foreign object you bridge into the loop — it *is* the loop.

??? note "URLs both ways — the pure helpers"

    The route table is bidirectional, and that fact is exposed as two **pure** functions you can call anywhere — in a handler, in a test, on the JVM during server rendering. They live in `re-frame.routing`, not on the `rf/` facade:

    - `(re-frame.routing/route-url :conduit.article/show {:slug "welcome-to-conduit"})` → `"/article/welcome-to-conduit"`. Builds the URL string from a route id and params. It does **not** navigate — it's a string-builder. `:rf.route/navigate` uses it internally.
    - `(re-frame.routing/match-url "/article/welcome-to-conduit")` → `{:route-id :conduit.article/show :params {:slug "..."} :query {} :fragment nil ...}`, or `nil` when nothing matches. The inverse direction.

    You won't need either in this part — `route-link` and `:rf.route/navigate` cover the app's own navigation — but they're the functions tests and SSR call to turn a route into a URL without an app-db in hand.

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

1. `rf/init!` installs the Reagent [adapter](../../core/glossary.md#adapter) — the bridge between re-frame2 and your rendering [substrate](../../core/glossary.md#substrate). One line; swap it for `re-frame.adapter.uix` or `helix` and nothing else in the app moves.
2. `rf/reg-frame` creates the [**frame**](../../core/glossary.md#frame) your app runs in: one isolated instance with its own app-db, event queue, and subscription cache ([Frames](../../core/concepts/frames.md)). (Registrations aren't part of that isolation — they live in a process-global [registrar](../../core/glossary.md#registrar) every frame shares; a frame isolates *state*, not behaviour.) What matters today is `:url-bound? true` — the explicit declaration that *this* frame owns the browser URL. Nothing owns the URL by default, so without that flag the address bar would never change. (Only one frame may claim it; register a second `:url-bound? true` frame and the runtime emits a `:rf.error/duplicate-url-binding` error to your error listeners. It doesn't throw — the first claimant keeps the URL and the late-comer's navigation effects quietly no-op — but the error names both frames so the clash is visible rather than a mystery about why the address bar won't move.)
3. `dispatch-sync` runs the seed event *synchronously*, before the first render, so the feed never paints against an empty db. `with-frame` says which frame the dispatch targets. (Plain `dispatch` queues for the next tick — fine everywhere else, but at boot you want the state committed *now*.)
4. `rf/install-history-listener!` does the initial URL→state sync, so deep links work from the very first paint. It also turns the browser's Back/Forward into the same kind of route-change event a link click produces — Back is not a special case, it's just another event.
5. `frame-provider {:frame :rf/default}` scopes the mounted tree to the already-registered frame, so every `subscribe` and `dispatch` inside your views resolves to it. ([Frame identity is carried, not found](../../core/glossary.md#frame-identity-is-carried-not-found) — the scope hands the frame down through React; the runtime never guesses one.)

> **From re-frame v1.** In re-frame v1 there was no frame: app-db was one global atom, and `re-frame.core/dispatch` always hit it. re-frame2 wraps app-db, the event queue, and the subscription cache into a [**frame**](../../core/glossary.md#frame) — a named, isolated instance — so you can run two independent apps on one page, or mount the same app twice without them sharing state. (Registrations stay process-global, shared across frames; it's the *state* that's isolated.) A single-app boot like this one declares one frame, names it `:rf/default`, and scopes the tree to it; for everyday code that mostly means an explicit `with-frame` at boot and a `frame-provider {:frame :rf/default}` around your root view. The everything-is-global model is gone, and with it the whole class of bugs where two parts of a page quietly clobbered each other's state.

> **An equivalent shape: `:initial-events`.** This boot seeds app-db with a `dispatch-sync` *after* `reg-frame`, which keeps the two steps visible side by side. A frame can also carry its setup *declaratively*, as an ordered `:initial-events` vector the runtime dispatches synchronously the moment the frame is created:
>
> ```clojure
> (rf/reg-frame :rf/default {:doc            "The Conduit app frame."
>                            :url-bound?     true
>                            :initial-events [[:app/initialise]]})
> ```
>
> Both forms run the seed before first render; the explicit `with-frame` / `dispatch-sync` form is the one this tutorial uses, because watching the seed dispatch happen is part of learning the loop. Note there's no `:db` config key — a frame *always* starts with `app-db = {}`, and seeding it is itself an event (here `:app/initialise`; the framework also ships `:rf/set-db` for the trivial "just set the whole map" case). Initialisation runs through the same [event cascade](../../core/glossary.md#event-cascade) as every later state change. There's no second mechanism for "the first state" — it's events all the way down.

## See it move

With the dev build running, open the app and walk the loop you just built:

1. **Click an article.** The page changes *and* the address bar now reads `/article/events-write-subs-read`. You didn't write a single line of URL-sync code — the URL is downstream of the route state, so it just follows along.
2. **Press Back.** The feed returns. Back isn't a special browser mystery here: it arrived as an event, the route slice changed, and your `case` picked the other page. Same loop, different trigger.
3. **Type a URL by hand.** Visit `/article/the-url-is-a-sub` directly. The deep link works, because boot syncs URL→state before first render. Now try `/article/nope`: the route matches but the data doesn't, so your missing-article branch renders. Then try `/definitely-not-a-route`: nothing matches, and the not-found page renders. Two different failures, each owned by a view *you* wrote.

> **Two kinds of "not here," and why they read differently.** `/article/nope` and `/definitely-not-a-route` look like the same failure but aren't, and knowing which is which tells you instantly where to look. The first **matches** `:conduit.article/show` — `nope` is a perfectly valid `:slug` — so the route resolves, `:articles/by-slug` returns `nil`, and your *article page's* `if` renders "There's no article called nope here." The second matches **no route at all**, so the runtime routes to `:rf.route/not-found` with `{:url "/definitely-not-a-route"}` in `:rf.route/params`, and your *not-found page* renders. A bad slug is a found page with empty data; a bad path is the not-found page. (Later, once schema enforcement is on, a slug that *fails its schema* — say a route that wants a `:uuid` and gets `nope` — folds into the second case: it routes to not-found rather than limping through your view with a malformed param.)

If you wired Xray during setup, open it while you click. Each navigation shows up as an event row followed by the route state changing — and link clicks, Back presses, and address-bar entries all produce the *same kind* of row. That's this part's claim made visible: there is no second system shoving the pages around behind the curtain. One loop — events write state, subs read it, views render it — and the URL is just one more sub.

This is the loop the entire rest of the guide rests on; [Part 2](02-server-data.md) keeps the exact same shape and swaps the canned seed for a real server fetch.
