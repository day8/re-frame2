# 17 — Routing

**The URL is a sub.**

In re-frame2 the URL is a derivable view of `app-db`. Navigation is an event. Browser back/forward is an event. Deep links and SSR feed the same `:rf.route/handle-url-change` handler that client-side popstate does. The current route lives at the `:rf/route` slice; views read it through subs; the root view dispatches on `:rf.route/id`. Same registry, same dispatch, same time-travel, same tests.

There's no separate routing runtime, no route-aware components, no "router context." Routing is just data reflected in the address bar — feature parity with React Router, TanStack Router, and reitit's frontend module, while staying inside re-frame2's one mental model.

This chapter walks the basics top-to-bottom: registering routes, navigating, reading the route in views, the not-found route, and a one-paragraph callout for the navigation token. The per-topic reference half — `:on-error`, the full nav-token walkthrough, the `:can-leave` protocol, query strings, multi-frame routing, the pure helpers, and a RealWorld worked example — lives in [17a — Routing: reference and advanced topics](17a-routing-reference.md). Read this chapter linearly; reach for 17a when the topic comes up.

You'll know how to:

- Register a route with path params, query params, defaults, and retain keys.
- Trigger navigation programmatically and from links.
- Read the active route in views.
- Wire `:on-match` events to load the data a route needs.
- Handle URLs that don't match any registered route.

## The artefact

Routing ships as a separate per-feature artefact (`day8/re-frame2-routing`):

```clojure
{:deps {day8/re-frame2          {...}
        day8/re-frame2-routing  {...}}}
```

Per Strategy B, an app that doesn't use routing doesn't bundle routing code. The bundle-isolation gate asserts that core's release build carries zero routing namespace markers — re-importing `re-frame.routing` from core is a CI failure.

Once the artefact is on the classpath, `(:require [re-frame.routing])` once at app boot wires the late-bind hooks; `reg-route`, `:rf.route/navigate`, the `:rf/route` sub, and the `:rf.route/...` family of fxs are then available through `re-frame.core`.

## Registering a route

Routes are registry entries — same shape as events, fxs, subs:

```clojure
(rf/reg-route :route/home
  {:doc  "The landing page."
   :path "/"})

(rf/reg-route :route/cart
  {:doc      "The cart page."
   :path     "/cart"
   :on-match [[:cart/load-items]]})

(rf/reg-route :route/cart.item-detail
  {:doc    "Detail page for a single cart item."
   :path   "/cart/items/:id"
   :params [:map [:id :uuid]]})

(rf/reg-route :route/article
  {:doc    "An article. Optional slug suffix."
   :path   "/articles/:id{/:slug}?"
   :params [:map [:id :uuid] [:slug {:optional true} :string]]})

(rf/reg-route :route/files
  {:doc   "A files browser; matches /files and any sub-path."
   :path  "/files/*rest"
   :params [:map [:rest :string]]})
```

The grammar is small — five productions. **Literal segment** (`/articles`), **named param** (`:id`), **optional segment group** (`{/:slug}?`), **catch-all/splat** (`*rest`), and **root** (`/`). The rules are an RFC-6570-Level-1 subset plus the splat extension, parseable by hand in any host.

When two routes can match the same URL, a six-rule cascade decides which wins — the four most-load-bearing rules are: more static segments beat fewer; longer paths beat shorter; named params beat splats; exact patterns beat optional-group patterns. The cascade is **structural** — the score is computable from each pattern's parsed shape, no URL needed. Implementations pre-compute the score at registration time.

## A first routing loop

Before the full slice, here's the smallest end-to-end example — one route, one navigation, one sub — so the rest of the chapter has something concrete to refer back to:

```clojure
(ns example.routing
  (:require [re-frame.core :as rf]))

;; 1. Register a route
(rf/reg-route :route/cart
  {:path "/cart"})

;; 2. Navigate to it
(rf/dispatch [:rf.route/navigate :route/cart])

;; 3. A view reads the active route id and renders accordingly
(rf/reg-view app-root []
  (case @(rf/subscribe [:rf.route/id])
    :route/cart  [:h1 "Cart"]
    [:h1 "Home"]))
```

That's the whole loop: a route is data in the registry, navigation is an event, and the view reads `:rf.route/id` through an ordinary subscription. Everything else — params, query, `:on-match` loaders, the `:can-leave` guard, nav-tokens — builds on those three moves.

## The `:rf/route` slice

The navigation in the example above leaves a slice in `app-db`. The runtime maintains a single such slice, and it's the source of truth every route-aware sub reads from:

```clojure
{:rf/route
  {:id           :route/article             ;; current route id
   :params       {:id #uuid "..."}          ;; path params (matches :params schema)
   :query        {:q "clojure" :page 2}     ;; query/search params (matches :query schema)
   :fragment     "section-2"                ;; URL #fragment, or nil
   :transition   :idle                      ;; :idle | :loading | :error
   :error        nil                        ;; populated when :transition = :error
   :nav-token    "nav-42"}}                 ;; per-navigation epoch token
```

The `:rf/route` key is reserved. Path params (`:params`) and query params (`:query`) are distinct maps — captured separately, validated against separate schemas, kept separate in the slice. Consumers that prefer a merged map build one in a derived sub.

`:fragment` carries the URL `#fragment` part. `:nav-token` is the runtime-allocated navigation epoch (see [17a §Navigation tokens](17a-routing-reference.md#navigation-tokens--stale-result-suppression)). Both are runtime-managed; user code reads them through subs.

`:transition` is a tiny FSM driven by the runtime: `:idle` when no navigation is in flight; `:loading` while the active route's `:on-match` events are draining; `:error` if any errors. A global progress bar reads `:rf.route/transition` and renders when it's `:loading`; an error banner reads `:rf.route/error`.

Schema for the slice: `:rf/route-slice`, a map with `:route-id` (keyword), `:params` (path-params map), `:query` (query-params map), `:fragment` (string or nil), `:transition` (one of `#{:idle :loading :error}`), `:error` (error info or nil), and `:nav-token` (runtime epoch).

## Navigation is an event

```clojure
;; Programmatic navigation by route-id
(rf/dispatch [:rf.route/navigate :route/cart])

;; With path params
(rf/dispatch [:rf.route/navigate :route/cart.item-detail {:id "abc-123"}])

;; With query params
(rf/dispatch [:rf.route/navigate :route/search {} {:q "clojure" :page 2}])

;; With a fragment
(rf/dispatch [:rf.route/navigate :route/docs {:page "routing"} {} "scroll-restoration"])

;; Replace rather than push (login redirects, redirects-on-load)
(rf/dispatch [:rf.route/navigate :route/login {} {} nil {:replace? true}])

;; URL-string escape hatch (for dynamic / user-supplied URLs)
(rf/dispatch [:rf.route/navigate {:url "/some/path"}])
```

The route-id form is preferred — the route-id is enumerable, refactorable, and queryable through the registrar. URL-strings are stringly-typed escape-hatchy by nature; tools can flag them as candidates for migration.

When the navigation event fires, three things happen, in order:

1. The `:rf/route` slice is updated (id, params, query, fragment, transition, nav-token).
2. The browser URL is pushed via `:rf.nav/push-url` (registered fx; `:platforms #{:client}`).
3. The route's `:on-match` events dispatch, in order, and the route's `:scroll` strategy is emitted as a `:rf.nav/scroll` effect.

The order is locked: state changes first, URL update second, then `:on-match` and scroll. If the URL update fails (browser denies, user is offline) the state is still consistent.

## URL changes are events

When the user clicks a link, presses Back/Forward, or arrives via a deep link, the runtime fires `:rf/url-changed` — the canonical event for "the URL is now different." The default handler is `:rf.route/handle-url-change`, which:

1. Calls `match-url` to resolve the URL against the registered route table.
2. Sets the `:rf/route` slice with the matched id, params, query, fragment, transition, and a fresh `:nav-token`.
3. Dispatches the matched route's `:on-match` events.

```clojure
;; Run for popstate, initial load, and SSR alike — same handler everywhere
(rf/reg-event-fx :rf.route/handle-url-change ...)
```

The same handler runs **on the server during SSR** — the request URL is fed in, the route slice is set, the view renders against it. The `:on-match` events also fire server-side, populating server-rendered data the same way they do client-side. There is **no SSR-specific routing code**. (See [chapter 11](11-server-side.md) for the SSR story.)

### Two URL-change events you'll see

Two named events surface in the trace stream:

- **`:rf/url-changed`** — the runtime event fired on every URL transition. Default handler is `:rf.route/handle-url-change`. Users can override by re-registering — to log analytics, run an auth-check, or apply per-app routing policy.
- **`:rf.route/fragment-changed`** — a **trace event** (not a runtime event) fired when the URL changes only in its `#fragment`. Distinct from the runtime event above; the runtime emits the trace and updates `:fragment` in the slice but does NOT re-fire `:on-match`. The reason: `:on-match` exists to re-load route-scoped data when path or query changes; a fragment-only change does not change loaded data, only the in-page anchor target.

If your view subscribes to `:rf.route/fragment` and re-renders when the fragment changes, you'll see the update. If your view subscribes only to `:rf.route/id` or `:rf.route/params`, fragment changes don't ripple through.

## Reading the route is a sub

```clojure
@(rf/subscribe [:rf/route])               ;; the full slice map
@(rf/subscribe [:rf.route/id])            ;; just the route id
@(rf/subscribe [:rf.route/params])        ;; path params
@(rf/subscribe [:rf.route/query])         ;; query params
@(rf/subscribe [:rf.route/fragment])      ;; #fragment string or nil
@(rf/subscribe [:rf.route/transition])    ;; :idle | :loading | :error
@(rf/subscribe [:rf.route/error])         ;; structured error map when :error
@(rf/subscribe [:rf.route/chain])         ;; :parent chain for nested layouts
@(rf/subscribe [:rf/pending-navigation])  ;; pending-nav slot when blocked
```

Views derive UI from the route the same way they derive UI from any other state — no special routing API in views.

The root view dispatches on `:rf.route/id`:

```clojure
(rf/reg-view app-root []
  (case @(subscribe [:rf.route/id])
    :route/home              [home-page]
    :route/cart              [cart-page]
    :route/cart.item-detail  [cart-item-detail]
    :route/article           [article-page]
    :rf.route/not-found      [not-found-page]))
```

Per-route views can subscribe to `:rf.route/params` for their own data needs:

```clojure
(rf/reg-view article-page []
  (let [{:keys [id slug]} @(subscribe [:rf.route/params])]
    [:article
     [:h1 "Article " id]
     (when slug [:p.slug slug])]))
```

## Linking from views

```clojure
[rf/route-link {:to :route/cart} "Cart"]
[rf/route-link {:to :route/article :params {:id "abc"}} "Read more"]
[rf/route-link {:to :route/search :query {:q "clojure"}} "Search"]
```

`route-link` renders an `<a href="...">` and intercepts plain primary-button clicks itself: it calls `.preventDefault` and dispatches `:rf/url-requested`. **Modifier keys** (cmd-click, middle-click, shift-click) defer to the browser — the link follows the `href` natively, opening in a new tab the way users expect.

Plain anchors (`[:a {:href "..."}]`) in user view code are **not** intercepted by the framework. They cause native browser navigation. Apps that want SPA-style interception on plain anchors install it at the host adapter layer (a top-level `click` listener on the document that consults `match-url`); the runtime's contract stops at `route-link` plus `:rf/url-requested`. Why: a global `click` listener is host-bound and conflicts with non-routed `<a>` tags inside iframes, shadow DOM, or third-party widgets — the host adapter has the context to install it appropriately.

## Per-route data loading: `:on-match`

A route declares a vector of events the runtime dispatches whenever the route becomes active:

```clojure
(rf/reg-route :route/cart
  {:doc      "The cart page."
   :path     "/cart"
   :on-match [[:cart/load-items]
              [:user/load-prefs]]})
```

Semantics:

1. When the route becomes active (URL-driven via `:rf.route/handle-url-change`, or programmatic via `:rf.route/navigate`), the runtime dispatches each `:on-match` event in order, after writing the `:rf/route` slice and before any view renders that depend on the loaded data.
2. The runtime sets `:rf.route/transition` to `:loading` while the dispatches drain, and back to `:idle` when they complete.
3. Same-route-id navigations with **changed `:params` or `:query`** *do* re-fire `:on-match` (the route is becoming active again under new inputs). Same-route-id navigations with identical params do **not** re-fire — the runtime compares the post-update `:rf/route` slice against the pre-update slice and skips dispatch when nothing relevant changed.
4. `:on-match` events run server- and client-side. SSR populates server-rendered data via the same vector. Hydration does **not** re-fire `:on-match` events — the seeded `app-db` already contains the data.
5. Each `:on-match` event is an ordinary event vector. Handlers may emit any `:fx` (typically `:rf.http/managed`). The events are also enumerable: `(rf/handler-meta :route :route/cart)` returns the metadata so tooling can render route-loading dependency graphs.

The `:on-match` list is the **enumerable, machine-readable** answer to "what loads when this route is active?"

### Async `:on-match` and stale results — the nav-token in one paragraph

If an `:on-match` event kicks off an async load (HTTP, query, timer) and the user navigates away before it completes, the older load's reply can land *after* the user has moved on and clobber the newer state. Re-frame2's answer is the **navigation token (nav-token)**: each navigation gets a fresh token written into the `:rf/route` slice; an async result is committed only if its carried token still matches the current one. You don't allocate or thread the token by hand — `:on-match` handlers receive it as a cofx, and the runtime drops mismatched results with a `:rf.route.nav-token/stale-suppressed` trace. The mechanism is mostly invisible; you'll meet it when you write an `:on-match` continuation that fetches data. Full walkthrough with a worked example in [17a §Navigation tokens](17a-routing-reference.md#navigation-tokens--stale-result-suppression).

## The `:rf.route/not-found` route

Apps **must** register a `:rf.route/not-found` route. The id is special-cased in one direction: when a URL fails to match any registered route, the runtime sets `:rf/route` to `{:id :rf.route/not-found :params {:url <url>} ...}` and proceeds with that route's `:on-match` events.

```clojure
(rf/reg-route :rf.route/not-found
  {:doc      "404 page."
   :path     "/404"
   :on-match [[:analytics/log-404]]
   :scroll   :top})
```

It's an ordinary `reg-route` — `:on-match`, `:on-error`, `:scroll`, `:tags` all behave normally. The view tree's `case` over `:rf.route/id` renders the not-found view from the leaf:

```clojure
(rf/reg-view app-root []
  (case @(subscribe [:rf.route/id])
    ;; ... matched routes ...
    :rf.route/not-found  [not-found-page]))
```

If no `:rf.route/not-found` is registered, the runtime emits a `:rf.warning/no-not-found-route` trace and falls back to a built-in placeholder view (a minimal `<h1>Not Found</h1>` page) so the request still produces a response. Test fixtures and the conformance corpus assume the user-registered shape.

URL-validation failures (a route's path matches but its `:params` schema rejects the captured values) also route here, with `:reason :validation` in the `:params` slice.

## Next

You now have the URL ↔ state loop: routes are registry entries, navigation is an event, the route is a sub, `:on-match` loads data, and unmatched URLs fall through to `:rf.route/not-found`. The per-topic reference half picks up the rest:

- [17a — Routing: reference and advanced topics](17a-routing-reference.md) — `:on-error`, the full nav-token walkthrough, the `:can-leave` protocol for unsaved-changes prompts, query-string defaults and retain keys, multi-frame routing, the pure `match-url` / `route-url` helpers, and a RealWorld worked example.
- [20 — Where to go next](20-where-next.md) — the chapter wrap-up, with pointers to the worked examples, pattern docs, the API ref, and the spec.
- [chapter 11 — The server side](11-server-side.md) — how routing folds into SSR.
