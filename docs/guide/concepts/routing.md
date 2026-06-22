# Routing: the URL is a sub

Here's the whole idea in one line: **the URL is application state, and your back button is a dispatch.**

Most frameworks bolt a router onto the side of your app — its own context, its own lifecycle, its own opinion about where truth lives. re-frame2 doesn't. A route is a registry entry (one row in re-frame2's table of routes). Navigating is dispatching an event (a named action sent through the app). The active route is a subscription (a read of state your views watch). That's it. By the end of this page you'll register a route table, navigate and link between pages, branch your views on the active route, load each page's data declaratively, handle the 404, wire up back/forward, and — for free — run the exact same routing on the server.

Which means: everything you already know about [events](events-and-the-cascade.md) and [subscriptions](subscriptions.md) is genuinely everything you need here. There is no fourth concept hiding behind the curtain.

> **Coming from React Router or Remix?** Routes-as-data and per-route loaders will feel familiar. The deliberate divergences: there are no hooks (`useNavigate` is an event dispatch, `useLoaderData` is a subscription, `useBlocker` is a guard sub), there's no router context to thread through your tree, and the same route handler runs on the server with zero SSR-specific code.

## The whole model in three moves

```clojure
;; Adapted from examples/reagent/routing/core.cljs
(ns app.core
  (:require [re-frame.core :as rf]
            [re-frame.routing]))   ;; ships in day8/re-frame2-routing; requiring it
                                   ;; at boot is what makes reg-route available

;; 1. A route is data in the registry.
(rf/reg-route :app/home
  {}
  "/")

(rf/reg-route :app/article
  {:params [:map [:id :string]]}
  "/articles/:id")

;; 2. Navigation is an event.
(rf/dispatch [:rf.route/navigate :app/article {:id "intro"}])

;; 3. The root view reads the active route through an ordinary subscription.
;;    (reg-view injects lexical `dispatch`/`subscribe` bound to the frame.)
(rf/reg-view article-page []
  (let [{:keys [id]} @(subscribe [:rf.route/params])]
    [:h1 "Article " id]))

(rf/reg-view root-view []
  (case @(subscribe [:rf.route/id])
    :app/home           [:h1 "Home"]
    :app/article        [article-page]
    :rf.route/not-found [:h1 "Not found"]))
```

Read those three moves and you've read the whole framework's idea of routing. Everything below is a refinement of one of them: query strings, data loading, the 404, back/forward, SSR. Once the three click, the rest is detail.

## A route is a registry entry

`reg-route` registers a route the same way `reg-event` registers an event: an id, a metadata map, and — in the third slot — the path. The path grammar is deliberately small enough to parse in your head: literal segments (`/articles`), named params (`:id`), an optional group (`{/:slug}?`), a catch-all splat (`*rest`), and the root (`/`). Five productions, no more.

The `:params` and `:query` keys take [schemas](../how-to/validate-with-schemas.md), which validate *and coerce* for you, so `?page=2` arrives as the integer `2`, not the string `"2"`. That coercion is the part everyone forgets to do by hand — so let the schema own it:

```clojure
(rf/reg-route :app/search
  {:query          [:map [:q :string] [:page {:optional true} :int]]
   :query-defaults {:page 1}}
  "/search")
```

When two patterns could both match one URL, a structural ranking breaks the tie: more static segments win, and named params beat splats. The ranking is computed at *registration* time from the patterns alone — so the winner is decided before a single URL ever arrives, and there's no runtime ambiguity to debug. Path params and query params also stay separate maps the whole way through: captured separately, validated against separate schemas, never silently merged into one bag.

??? note "The full grammar and ranking rules"

    The full path grammar, the six-rule ranking cascade, and the per-boundary validation failure modes live in [Spec 012 — Routing](../../../spec/012-Routing.md). You won't need them for everyday routes.

Because routes are registry entries, the route table is *queryable data* — and that turns out to pay off. Tag a route `:tags #{:requires-auth}` and an ordinary [interceptor](interceptors.md) (a step that wraps your navigation events) can read the tag and redirect. That's the entire auth-guard mechanism — no special router middleware, just data and a step that reads it. [Add authentication](../how-to/add-auth.md) walks through it end to end.

## Navigation is an event

You navigate with the same verb you use for everything else, which is the whole reason the surface stays small:

```clojure
(rf/dispatch [:rf.route/navigate :app/article {:id "intro"}])

;; Query params and options ride in the THIRD slot — params 2nd, opts 3rd:
(rf/dispatch [:rf.route/navigate :app/search {} {:query {:q "clojure" :page 2}}])

;; Replace instead of push (login redirects, search-as-you-type):
(rf/dispatch [:rf.route/navigate :app/login {} {:replace? true}])
```

!!! warning "Params is 2nd, opts is 3rd"

    `[:rf.route/navigate :app/cart {:replace? true}]` *reads* like "navigate with options" but actually drops `:replace?` into the **params** slot. The runtime catches the mistake and rejects the navigation with a named error (`:rf.error/navigate-arity-misuse`) rather than navigating wrongly — so you get a loud signal, not a silent bug. Just pass an empty params map: `[:rf.route/navigate :app/cart {} {:replace? true}]`.

When the navigate event runs, three things happen in a **locked order**: first the route slice in frame state updates, then the browser URL pushes, then the route's loaders dispatch. State-before-URL is on purpose. If the URL push fails — offline, or the browser denies it — your application state is still consistent, so you never end up showing one page while the address bar swears you're on another.

For links *in views*, use `route-link`. It renders a real `<a href>` (good for accessibility, hover-preview, and copy-link) and intercepts plain primary clicks into a dispatch. Crucially it also *defers* cmd-click, shift-click, and middle-click to the browser, so open-in-new-tab still works — the detail hand-rolled SPA links almost always forget:

```clojure
[rf/route-link {:to :app/article :params {:id "intro"}} "Read more"]
```

Plain `[:a {:href "..."}]` anchors are deliberately **not** intercepted; they do a native full-page navigation. This surprises people at first, but it's intentional: site-wide anchor interception is a host-adapter concern, not framework magic, so you opt in per link (or install your own document-level click handler that dispatches `:rf/url-requested` — the same decision-point event `route-link` uses).

### Blocking a navigation (the unsaved-changes guard)

Navigation can also be *blocked*, which is how you build an "are you sure? you have unsaved changes" guard. A route declares `:can-leave [:editor/can-leave?]`, a subscription naming the **positive** case (`true` means "yes, leaving is fine"). When it returns `false`, the navigation simply doesn't happen — no URL change, no state write. The attempted navigation parks in a pending slot that your confirm dialog renders from, and the user's choice is itself a dispatch: either `:rf.route/continue` or `:rf.route/cancel`.

> **Why this matters.** The entire flow is testable with zero DOM. Dispatch the navigate, assert the pending slot filled, dispatch `:rf.route/continue`, assert it went through — no browser, no `beforeunload`, no flaky modal automation. The editor routes in [examples/reagent/realworld_resources](../../../examples/reagent/realworld_resources/) show the shape.

## The active route is a subscription

The current route lives in the frame's framework-managed partition — the slice of state the runtime owns. Your code *reads* it and never *writes* it, and you read it like any other state:

```clojure
@(rf/subscribe [:rf/route])             ;; the full slice: {:route-id :params :query :fragment :transition :error :nav-token}
@(rf/subscribe [:rf.route/id])          ;; just the route id
@(rf/subscribe [:rf.route/params])      ;; path params
@(rf/subscribe [:rf.route/query])       ;; query params
@(rf/subscribe [:rf.route/transition])  ;; :idle | :loading | :error
```

`:transition` is a tiny state machine the runtime drives for you: `:loading` while a route's loaders drain, `:error` if one fails, `:idle` otherwise. So a global progress bar is just a view over `:rf.route/transition`, and an error banner is a view over `:rf.route/error`. You never wire per-page loading state again — it's a property of the slice, the same on every page.

> **Coming from TanStack Query?** That global `:transition` plays the role `isFetching` plays at the page level — except you didn't have to thread it through any component, because it's a subscription anyone can read from anywhere in the tree.

Try it with the [Xray inspector](../how-to/debug-with-xray.md) open. Dispatch a navigation and watch the trace: the navigate event, the fresh nav-token allocation, then each loader dispatch, in order. Routing has no hidden machinery — everything it does shows up on the same wire as your own events, which is exactly what makes it easy to trust.

## Loaders are route metadata

A route declares what loads when it becomes active. The simplest form is `:on-match`: a vector of ordinary event vectors the runtime dispatches, in order, whenever the route activates — *including* when the same route re-activates with **changed** params. Identical params don't re-fire, so you never get accidental double-loads from a no-op navigation.

If the page's data is [server state managed as resources](server-state.md) — a resource being a declared, cached unit of server data — declare it as data with the `:resources` key instead (available when both `re-frame.routing` and `re-frame.resources` are loaded):

```clojure
;; Adapted from examples/reagent/realworld_resources/routing.cljs
(rf/reg-route :realworld.article/show
  {:params [:map [:slug :string]]
   :scroll :top
   :resources
   [{:resource  :realworld/article
     :params    (fn [route] {:slug (get-in route [:params :slug])})
     :blocking? true}
    {:resource  :realworld/comments
     :params    (fn [route] {:slug (get-in route [:params :slug])})
     :blocking? false
     :keep-previous? true}]}
  "/article/:slug")
```

> **Coming from Remix?** This is the loader — as data. `:blocking? true` is the `await`: it holds the route transition (and, on the server, the render) until the resource settles. Non-blocking entries fetch in the background. `:keep-previous? true` keeps the old page on screen while the next one first-loads, so a slug change doesn't flash an empty article.

Here's the bug this design quietly kills. On route entry, the runtime marks each listed resource active, owned by the route, keyed by *this navigation's* **nav-token**. On route leave — or the moment a newer navigation supersedes this one — ownership is released by token, and any stale reply that lands late is *suppressed* rather than written. That's the classic race where you click away, an old fetch resolves a beat too late, and clobbers the page you're now looking at. Fixed once, in the substrate, instead of in every page you'll ever write.

When a resource is per-user, scope it with a **named scope resolver**. Register the resolver once, then reference it everywhere as `{:from-db ...}` — so the "who is this for?" logic lives in exactly one place:

```clojure
;; Adapted from examples/reagent/realworld_resources/scope.cljs + routing.cljs
(rf/reg-resource-scope :realworld/session
  {:inputs  {:username [:db [:auth :user :username]]}
   :resolve (fn [{:keys [username]} _ctx]
              (when username
                [:rf.scope/session {:username username}]))})

;; The personalised feed, as a route resource:
{:resource :realworld/feed
 :scope    {:from-db :realworld/session}
 :params   (fn [route] {:page (get-in route [:query :page])})
 :blocking? false}
```

The runtime resolves `{:from-db :realworld/session}` against app-db (your app's single state map) at route entry. Resolution **fails closed**: logged out, the resolver returns `nil` and the feed is simply *not planned*. It never silently falls back to a shared cache, so one user's feed can never leak into another's session. Security by construction, not by remembering to check.

## Not found is a route you register

When no pattern matches a URL — or a URL matches but its params fail their schema — the runtime activates the reserved id `:rf.route/not-found`, dropping the offending URL (and a `:reason` for validation failures) into `:params`. You **must** register it. It's an ordinary route, so it can carry its own loaders and scroll behaviour:

```clojure
(rf/reg-route :rf.route/not-found
  {:on-match [[:analytics/log-404]]}
  "/404")
```

!!! warning "Register your own not-found route"

    Forget it and the runtime warns (`:rf.warning/no-not-found-route`) and falls back to a built-in placeholder — so the request still renders *something*, just not your design. Every real app registers its own.

## The browser is just another event source

Back/forward, deep links, and the initial page load are all, underneath, URL changes arriving *into* the app. They come in as one event, `:rf.route/handle-url-change`, and wiring it up is two lines at boot:

```clojure
;; Adapted from examples/reagent/routing/core.cljs (client-only)
(rf/reg-frame :rf/default {:doc "The app frame." :url-bound? true})
(rf/install-history-listener!)
```

`:url-bound? true` declares which [frame](frames.md) — an isolated instance of your app's state and registry — owns the browser URL. Ownership is always *explicit*, never inferred, and only one frame may hold it. Non-owning frames still route internally: a [Story](observability.md) variant can sit on `/article/intro` without touching your address bar, and a test frame never calls `pushState`. With no declared owner, URL pushes and the popstate listener simply no-op. `install-history-listener!` installs the `popstate` listener (targeted at the owner) and does the initial URL-to-state sync; it's idempotent, so hot-reload is safe, and `rf/remove-history-listener!` tears it down.

So a back-button press is, literally, a dispatch: popstate fires, `:rf.route/handle-url-change` runs, the slice updates, the views re-derive. And [time-travel](../how-to/debug-with-xray.md) falls out for free — rewind the frame and the URL rewinds with it, because the URL was never the source of truth, only a *print-out* of it.

When you need to convert between a route and a URL by hand, the pure pair `(rf/route-url :app/article {:id "intro"})` and `(rf/match-url "/articles/intro")` translate between the two directions on both JVM and browser — so you never hand-concatenate a query string again.

## The same handler runs on the server

This is the payoff of routing having no runtime of its own. During [server-side rendering](ssr.md), the request URL is fed to the *same* `:rf.route/handle-url-change` handler, against a per-request frame. The slice is written, `:on-match` fires, blocking `:resources` give the server its wait-point before render, and the resulting state ships to the client, where hydration installs it **without re-fetching** because the data is already there. There's no server router, no client router, and no seam between them — one place where URLs become state, one place where state becomes URLs.

!!! note "What is client-only"

    The URL-push and scroll effects are no-ops on the server, where there's no address bar, and `install-history-listener!` does nothing server-side because there's no popstate to listen to. Everything else — your route table, handlers, loaders, and guards — runs unchanged on both sides.

!!! note "Honest edges, pre-alpha"

    Nested layouts are data: a route may declare a `:parent`, and views read the chain through a sub (`:rf.route/chain`). But there are no React-Router-style `<Outlet/>` render slots — you compose layout shells in the root view yourself. And if your app has exactly one page with no shareable URLs, skip the artefact entirely: it's separately packaged precisely so a non-routing app ships zero routing bytes.

---

**You can now:**

- register a route table as data, with schema-validated *and coerced* path and query params
- navigate by dispatching `:rf.route/navigate`, and render links with `route-link` (cmd-click and all)
- branch your root view on the `:rf.route/id` subscription and show global loading state from `:rf.route/transition`
- declare a page's data needs on the route — `:on-match` events or `:resources` entries, with `{:from-db ...}` per-user scoping
- block a navigation with a `:can-leave` guard and resolve it with `:rf.route/continue` / `:rf.route/cancel`
- handle unmatched URLs through your own `:rf.route/not-found` route
- wire back/forward with `:url-bound?` plus `install-history-listener!` — and explain why none of this needs an SSR-specific twin
