# Routing: the URL is a sub

**The URL is application state; your back button is a dispatch.** That one line is the whole page. re-frame2 has no router sitting beside your app with its own context, lifecycle, and idea of where truth lives. A route is a registry entry. Navigating is dispatching an event. The active route is a subscription. What you already know about [events](events-and-the-cascade.md) and [subscriptions](subscriptions.md) is everything you need.

> **Coming from React Router or Remix?** Routes-as-data and per-route loaders will feel familiar — the deliberate divergences are that there are no hooks (`useNavigate` is an event dispatch, `useLoaderData` is a subscription, `useBlocker` is a guard sub), no router context to thread, and the same route handler runs on the server with zero SSR-specific code.

## The whole model in three moves

```clojure
;; Adapted from examples/reagent/routing/core.cljs
(ns app.core
  (:require [re-frame.core :as rf]
            [re-frame.routing]))   ;; ships in day8/re-frame2-routing; requiring it
                                   ;; at boot is what makes reg-route available

;; 1. A route is data in the registry.
(rf/reg-route :app/home
  {:path "/"})

(rf/reg-route :app/article
  {:path   "/articles/:id"
   :params [:map [:id :string]]})

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

Everything else on this page is a refinement of those three moves: query strings, data loading, the 404, back/forward, SSR.

## A route is a registry entry

`reg-route` registers a route the way `reg-event-db` registers an event: an id plus a metadata map. The `:path` grammar is small enough to parse in your head. It has literal segments (`/articles`), named params (`:id`), an optional group (`{/:slug}?`), a catch-all splat (`*rest`), and the root (`/`). `:params` and `:query` are schemas. They validate and coerce, so `?page=2` arrives in your app as the integer `2`, not the string `"2"`.

```clojure
(rf/reg-route :app/search
  {:path           "/search"
   :query          [:map [:q :string] [:page {:optional true} :int]]
   :query-defaults {:page 1}})
```

When two patterns could match one URL, a structural ranking decides between them. More static segments win, and named params beat splats. The ranking is computed at registration time from the patterns alone, so there is never runtime ambiguity to debug. Path params and query params stay separate maps throughout: captured separately, validated against separate schemas, never silently merged. The full grammar, the six-rule ranking cascade, and the per-boundary validation failure modes live in [Spec 012 — Routing](../../../spec/012-Routing.md).

Because routes are registry entries, the route table is queryable data. Tag a route `:tags #{:requires-auth}` and an ordinary interceptor on the navigation events can read the tag and redirect. That is the entire auth-guard mechanism ([Add authentication](../how-to/add-auth.md) walks through it).

## Navigation is an event

You navigate with the same verb you use for everything else:

```clojure
(rf/dispatch [:rf.route/navigate :app/article {:id "intro"}])

;; Query params and options ride in the THIRD slot — params 2nd, opts 3rd:
(rf/dispatch [:rf.route/navigate :app/search {} {:query {:q "clojure" :page 2}}])

;; Replace instead of push (login redirects, search-as-you-type):
(rf/dispatch [:rf.route/navigate :app/login {} {:replace? true}])
```

> **Heads-up: params is 2nd, opts is 3rd.** `[:rf.route/navigate :app/cart {:replace? true}]` reads like "navigate with options" but puts `:replace?` in the *params* slot. The runtime rejects the swap with a named error rather than navigating wrongly — pass an empty params map: `[:rf.route/navigate :app/cart {} {:replace? true}]`.

When the event runs, three things happen in a locked order. The route slice in frame state updates first. The browser URL pushes second. The route's loaders dispatch third. State updates before the URL, so if the URL push fails (offline, browser denies it), your application state is still consistent.

For links in views, use `route-link`. It renders a real `<a href>` and intercepts plain primary clicks into a dispatch. It also defers cmd-click, shift-click, and middle-click to the browser, so open-in-new-tab still works — the detail hand-rolled SPA links always forget.

```clojure
[rf/route-link {:to :app/article :params {:id "intro"}} "Read more"]
```

Plain `[:a {:href "..."}]` anchors are deliberately *not* intercepted. They do a native full-page navigation. Site-wide anchor interception is a host-adapter concern, not framework magic.

Navigation can also be *blocked*. A route may declare `:can-leave [:editor/can-leave?]`, a subscription naming the positive case. When it returns `false`, the navigation does not happen: no URL change, no state write. The attempted navigation lands in a pending slot your confirm dialog renders from. The user's choice is a dispatch, either `:rf.route/continue` or `:rf.route/cancel`. The whole unsaved-changes flow is testable with zero DOM. The editor routes in [examples/reagent/realworld_resources](../../../examples/reagent/realworld_resources/) show the working shape.

## The active route is a subscription

The current route lives in the frame's framework-managed partition: your code reads it, never writes it. You read it like any other state:

```clojure
@(rf/subscribe [:rf/route])             ;; the full slice: {:id :params :query :fragment :transition :error :nav-token}
@(rf/subscribe [:rf.route/id])          ;; just the route id
@(rf/subscribe [:rf.route/params])      ;; path params
@(rf/subscribe [:rf.route/query])       ;; query params
@(rf/subscribe [:rf.route/transition])  ;; :idle | :loading | :error
```

`:transition` is a tiny state machine the runtime drives for you: `:loading` while a route's loaders drain, `:error` if one fails, `:idle` otherwise. A global progress bar is a view over `:rf.route/transition`. An error banner is a view over `:rf.route/error`. You never wire loading state per page. It's a property of the slice.

Try it with the inspector open. Dispatch a navigation and watch the trace: the navigate event, the fresh nav-token allocation, then each loader dispatch, in order. Routing has no hidden machinery; everything it does shows up on the same wire as your own events.

## Loaders are route metadata

A route declares what loads when it becomes active. The basic form is `:on-match`, a vector of ordinary event vectors the runtime dispatches, in order, whenever the route activates — including when the same route re-activates with *changed* params. Identical params don't re-fire, so you get no accidental double-loads.

If the page's data is [server state managed as resources](server-state.md), declare it as data instead with the `:resources` key (available when both `re-frame.routing` and `re-frame.resources` are loaded):

```clojure
;; Adapted from examples/reagent/realworld_resources/routing.cljs
(rf/reg-route :realworld.article/show
  {:path   "/article/:slug"
   :params [:map [:slug :string]]
   :scroll :top
   :resources
   [{:resource  :realworld/article
     :params    (fn [route] {:slug (get-in route [:params :slug])})
     :blocking? true}
    {:resource  :realworld/comments
     :params    (fn [route] {:slug (get-in route [:params :slug])})
     :blocking? false
     :keep-previous? true}]})
```

> **Coming from Remix?** This is the loader, as data. `:blocking? true` is the await — it holds the route transition (and, on the server, the render) until the resource settles; non-blocking entries fetch in the background; `:keep-previous?` keeps the old page visible while the next one first-loads.

On route entry, the runtime marks each listed resource active with the route as its owner, keyed by that navigation's *nav-token*. On route leave — or when a newer navigation supersedes this one — ownership is released by token and any stale reply is suppressed rather than written. That is the classic bug where you navigate away, an old fetch lands, and it clobbers the new page — fixed in the substrate instead of in every page.

When a resource is per-user, scope it with a **named scope resolver**. Register the resolver once, then reference it everywhere as `{:from-db ...}`:

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

The runtime resolves `{:from-db :realworld/session}` against app-db at route entry. Resolution fails closed: logged out, the resolver returns `nil` and the feed is simply not planned. It never silently falls back to a shared cache, so one user's feed can never leak into another's session.

## Not found is a route you register

When no pattern matches a URL — or a URL matches but its params fail the schema — the runtime activates the reserved id `:rf.route/not-found`, with the offending URL (and a `:reason` for validation failures) in `:params`. You **must** register it. It's an ordinary route, so it can have its own loaders and scroll behaviour:

```clojure
(rf/reg-route :rf.route/not-found
  {:path     "/404"
   :on-match [[:analytics/log-404]]})
```

Forget it and the runtime warns and falls back to a built-in placeholder, so the request still renders *something*. Every real app registers its own.

## The browser is just another event source

Back/forward, deep links, and the initial page load are all URL changes arriving *into* the app. They come in as one event: `:rf.route/handle-url-change`. Wiring it up is two lines at boot:

```clojure
;; Adapted from examples/reagent/routing/core.cljs (client-only)
(rf/reg-frame :rf/default {:doc "The app frame." :url-bound? true})
(rf/install-history-listener!)
```

`:url-bound? true` declares which [frame](frames.md) owns the browser URL. Ownership is always explicit, never inferred, and only one frame may hold it. Non-owning frames still route internally: a Story variant can sit on `/article/intro` without touching your address bar, and a test frame never calls `pushState`. With no declared owner, URL pushes and the popstate listener simply no-op. `install-history-listener!` installs the `popstate` listener targeted at the owner and does the initial URL-to-state sync. It's idempotent (so hot-reload is safe), and `rf/remove-history-listener!` tears it down.

So a back-button press is, literally, a dispatch: popstate fires, `:rf.route/handle-url-change` runs, the slice updates, the views re-derive. Time-travel falls out for free: rewind the frame and the URL rewinds with it, because the URL was never the source of truth, only a print-out of it. The pure pair `(rf/route-url :app/article {:id "intro"})` and `(rf/match-url "/articles/intro")` translate between the two directions, on both JVM and browser, and you never hand-concatenate a query string.

## The same handler runs on the server

This is the payoff of routing having no runtime of its own. During [server-side rendering](ssr.md), the request URL is fed to the *same* `:rf.route/handle-url-change` handler, against a per-request frame. The slice is written, `:on-match` fires, blocking `:resources` give the server its wait-point before render, and the resulting state ships to the client. There, hydration installs it without re-fetching, because the data is already there. There is no server router, no client router, and no seam between them. One place where URLs become state, one place where state becomes URLs.

What *is* client-only: the URL-push and scroll effects (no-ops on the server, where there's no address bar) and `install-history-listener!` (there's no popstate to listen to). Your route table, handlers, loaders, and guards run unchanged on both sides.

**Honest edges, pre-alpha.** Nested layouts are data: a route may declare a `:parent`, and views read the chain through a sub. But there are no React-Router-style `<Outlet/>` render slots, so you compose layout shells in the root view yourself. If your app has exactly one page and no shareable URLs, skip the artefact entirely. It's separately packaged precisely so a non-routing app ships zero routing bytes.

---

**You can now:**

- register a route table as data, with schema-validated path and query params
- navigate by dispatching `:rf.route/navigate`, and render links with `route-link`
- branch your root view on the `:rf.route/id` subscription and show global loading state from `:rf.route/transition`
- declare a page's data needs on the route — `:on-match` events or `:resources` entries, with `{:from-db ...}` per-user scoping
- handle unmatched URLs through your own `:rf.route/not-found` route
- wire back/forward with `:url-bound?` plus `install-history-listener!` — and explain why none of this needs an SSR-specific twin

**Next:** [Server state: resources](server-state.md) · [Server-side rendering](ssr.md)
