# 18 — Routing

Routing in re-frame2 is **state plus events**, not a separate subsystem.

The URL is a derivable view of `app-db`. Navigation is an event. Browser back/forward is an event. Deep links and SSR feed the same `:rf.route/handle-url-change` handler that client-side popstate does. The current route lives at the `:rf/route` slice; views read it through subs; the root view dispatches on `:rf.route/id`.

There is no separate routing runtime. There are no route-aware components. There is no "router context." It's just data that happens to be reflected in the address bar.

This chapter walks through the routing surface: registering routes, navigating, reading the route in views, the navigation token that suppresses stale loads, the `:can-leave` protocol for unsaved-changes prompts, and how multi-frame apps handle routes that don't push to the URL.

The chapter has two halves. The **basics** (everything up to the "Reference and advanced topics" divider) get you to a working URL ↔ state ↔ view loop: registering routes, navigating, reading the route, loading data, the nav-token in one paragraph, the not-found route. Read that part top-to-bottom. The **reference half** that follows is per-topic: `:on-error`, `:can-leave`, the full nav-token walkthrough, query-string knobs, multi-frame routing, the pure helpers, the RealWorld worked example, tooling hooks. Read those sections when the topic comes up, not as the next-link in the linear sequence.

You'll know how to:

- Register a route with path params, query params, defaults, and retain keys.
- Trigger navigation programmatically and from links.
- Read the active route in views.
- Block navigation with a `:can-leave` guard and resume it from a confirmation dialog.
- Avoid stale-result clobber with `:rf/url-changed`'s nav-token epoch.
- Run the same route resolution server- and client-side.
- Give a non-default frame its own route without touching the browser URL.

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

The grammar is small — five productions. **Literal segment** (`/articles`), **named param** (`:id`), **optional segment group** (`{/:slug}?`), **catch-all/splat** (`*rest`), and **root** (`/`). The full grammar with rules is in [Spec 012 §Path-pattern grammar](../../spec/012-Routing.md#path-pattern-grammar-canonical) — it's a strict subset of RFC 6570 Level 1 plus a splat extension, parseable by hand in any host.

When two routes can match the same URL, [a six-rule cascade](../../spec/012-Routing.md#route-ranking-algorithm) decides which wins. More static segments beat fewer; longer paths beat shorter; named params beat splats; exact patterns beat optional-group patterns. The cascade is **structural** — the score is computable from each pattern's parsed shape, no URL needed. Implementations pre-compute the score at registration time.

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

That's the whole loop: a route is data in the registry, navigation is an event, and the view reads `:rf.route/id` through an ordinary subscription. Everything else in this chapter — params, query, `:on-match` loaders, the `:can-leave` guard, nav-tokens — builds on those three moves.

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

`:fragment` carries the URL `#fragment` part. `:nav-token` is the runtime-allocated navigation epoch (see [§Navigation tokens](#navigation-tokens-stale-result-suppression)). Both are runtime-managed; user code reads them through subs.

`:transition` is a tiny FSM driven by the runtime: `:idle` when no navigation is in flight; `:loading` while the active route's `:on-match` events are draining; `:error` if any errors. A global progress bar reads `:rf.route/transition` and renders when it's `:loading`; an error banner reads `:rf.route/error`.

Schema for the slice: `:rf/route-slice` (per [Spec-Schemas](../../spec/Spec-Schemas.md#rfroute-slice)).

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
- **`:rf.route/url-changed`** — a **trace event** (not a runtime event) fired when the URL changes only in its `#fragment`. Distinct from the runtime event above; the runtime emits the trace and updates `:fragment` in the slice but does NOT re-fire `:on-match`. The reason: `:on-match` exists to re-load route-scoped data when path or query changes; a fragment-only change does not change loaded data, only the in-page anchor target.

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

If an `:on-match` event kicks off an async load (HTTP, query, timer) and the user navigates away before it completes, the older load's reply can land *after* the user has moved on and clobber the newer state. Re-frame2's answer is the **navigation token (nav-token)**: each navigation gets a fresh token written into the `:rf/route` slice; an async result is committed only if its carried token still matches the current one. You don't allocate or thread the token by hand — `:on-match` handlers receive it as a cofx, and the runtime drops mismatched results with a `:route.nav-token/stale-suppressed` trace. The mechanism is mostly invisible; you'll meet it when you write an `:on-match` continuation that fetches data. Full walkthrough with a worked example in [§Navigation tokens — stale-result suppression](#navigation-tokens-stale-result-suppression) in the reference half.

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

---

## Reference and advanced topics

The sections that follow are per-topic reference material. They're independent of one another — reach for them when the topic comes up. `:on-error` is the route's response to a load failure. `:can-leave` blocks navigation when you have unsaved work. The nav-token section expands the basics-half callout into the full mechanism — cofx shape, the `:rf.route/with-nav-token` wrapper, a step-by-step worked example. Query strings, multi-frame routing, the pure `match-url` / `route-url` helpers, and the RealWorld worked example round out the surface. Tooling and AI-amenability close the chapter.

## Per-route error handling: `:on-error`

If any event in `:on-match` errors, the runtime:

1. Sets `:rf.route/transition` to `:error`.
2. Populates `:rf.route/error` with the structured error map.
3. If the route declares an `:on-error` event, dispatches it. The error map is available via `(:error (:rf/route db))`.

```clojure
(rf/reg-route :route/cart
  {:path     "/cart"
   :on-match [[:cart/load-items]]
   :on-error [:route/cart-load-failed]})

(rf/reg-event-fx :route/cart-load-failed
  (fn [{:keys [db]} _]
    (let [error (get-in db [:rf/route :error])]
      {:db (assoc-in db [:cart :load-error] (:rf.error/message error))})))
```

`:on-error` is **route-scoped** error handling, layered over [Spec 009](../../spec/009-Instrumentation.md)'s structured error contract — it doesn't replace it. The structured error trace event still fires; `:on-error` is the route's response to it.

## Navigation tokens — stale-result suppression

When a route is loading and the user navigates away before the load completes, the older load's result can land **after** the user has moved on, clobbering newer state. Re-frame2's answer is the **navigation-token (nav-token) epoch**: a per-navigation token allocated when a route becomes active, carried by every async result, and validated on receipt.

```clojure
;; cofx of :on-match handlers carries the current :nav-token
{:db        ...
 :event     [:cart/load-items]
 :nav-token "nav-42"}                       ;; the token at scheduling time
```

Async completions either carry the token in their follow-up event payload, or use the framework-supplied `:rf.route/with-nav-token` fx wrapper which threads the token into the dispatched continuation:

```clojure
{:fx [[:rf.route/with-nav-token
       {:do        [:dispatch [:cart/items-loaded items]]
        :nav-token (:nav-token cofx)}]]}
```

When the receiving handler runs, the framework-provided `:nav-token` cofx checks the carried token against the *current* `:rf/route` slice's `:nav-token`:

- **Match** — the token is current; the result is committed normally.
- **Mismatch** — the token has been superseded; the runtime emits `:route.nav-token/stale-suppressed` and the handler does NOT run. No `:db` write, no `:fx`, no transition.

```clojure
;; Step 1: User navigates to article id="A". nav-token = "nav-1".
{:rf/route {:id :route/article :params {:id "A"} :transition :loading :nav-token "nav-1"}}

;; Step 2: While the load is in flight, user navigates to article id="B".
;; A fresh nav-token is allocated.
{:rf/route {:id :route/article :params {:id "B"} :transition :loading :nav-token "nav-2"}}

;; Step 3: The "A" load completes; its dispatched [:article/loaded "A" payload] carries
;; nav-token "nav-1". Current is "nav-2". Mismatch → suppressed; trace fires; no commit.

;; Step 4: The "B" load completes; carries "nav-2". Match → commit.
{:rf/route {:id :route/article :params {:id "B"} :transition :idle :nav-token "nav-2"}}
```

Suppression alone fixes the user-visible bug. Hosts that support abortable fetches (`AbortController` in JS, etc.) MAY *additionally* abort in-flight work for superseded tokens to save bandwidth — but the conformance contract only requires suppression, not cancellation. Same shape as the `:after` timer story for state-machines (see [Pattern-StaleDetection](../../spec/Pattern-StaleDetection.md)).

Two trace events surround the nav-token lifecycle:

- **`:route.nav-token/allocated`** — emitted when a navigation cascade allocates a fresh token.
- **`:route.nav-token/stale-suppressed`** — emitted when an async result arrives carrying a now-superseded token.

## Navigation blocking — the `:can-leave` protocol

Real product needs — unsaved forms, interrupted checkouts, destructive multi-step workflows — require navigation to be **blockable**. Re-frame2 makes this a first-class named-event/state protocol instead of a hook-based router thing. Pending-nav state lives in `app-db`; UI renders confirm dialogs from ordinary subs; user choices are dispatched as standard events. All testable.

### Declare the guard on the route

```clojure
(rf/reg-route :editor/article
  {:doc       "Editing an article."
   :path      "/editor/articles/:id"
   :params    [:map [:id :string]]
   :can-leave [:editor/can-leave?]})

(rf/reg-sub :editor/can-leave?
  :<- [:editor/dirty?]
  (fn [dirty? _] (not dirty?)))             ;; true means "OK to leave"
```

The sub returns `true` when the route is OK to leave; `false` to block. Convention: the sub's name describes the **positive** case (`:can-leave?`), so `false` means "can NOT leave."

### What happens on a blocked navigation

1. `:rf/url-requested` fires (link click, programmatic call, popstate).
2. The runtime evaluates the **current** route's `:can-leave` sub.
3. **Guard returns `true`** (or no guard) → proceed normally. The new URL becomes active; nav-token allocates; `:on-match` runs.
4. **Guard returns `false`** → BLOCK:
   - Generate a `pending-nav-id`.
   - Write `:rf/pending-navigation` with `{:id <id> :requested-by-event <ev> :requested-url <url> :rejecting-route <id> :rejecting-guard <sub-id>}`.
   - **The URL does not change.** No `pushState`, no `:rf/route` update, no `:on-match`.
   - Dispatch `[:rf.route/navigation-blocked pending-nav]` and emit the trace.

The pending-nav slot's shape (per [Spec-Schemas](../../spec/Spec-Schemas.md#rfpending-navigation)):

```clojure
{:rf/pending-navigation
 {:id                  "pn-7"
  :requested-by-event  [:rf/url-requested {:url "/editor/articles/42"}]
  :requested-url       "/editor/articles/42"
  :reason              "Form has unsaved changes"
  :rejecting-route     :editor/article
  :rejecting-guard     :editor/can-leave?}}
```

### The dialog

UI renders the confirm dialog by subscribing to `:rf/pending-navigation`:

```clojure
(rf/reg-view leave-confirmation []
  (when-let [pn @(subscribe [:rf/pending-navigation])]
    [:dialog.confirm-leave
     [:p (:reason pn)]
     [:p "Leaving will discard your changes."]
     [:button {:on-click #(dispatch [:rf.route/cancel   (:id pn)])} "Stay"]
     [:button {:on-click #(dispatch [:rf.route/continue (:id pn)])} "Leave"]]))
```

User choice:

- **Continue** → `[:rf.route/continue pending-nav-id]` clears the slot and re-dispatches the original navigation **bypassing** the leave-guard for this one shot.
- **Cancel** → `[:rf.route/cancel pending-nav-id]` clears the slot. Nothing else changes.

### Why this shape (and not a hook-based router)

The hook version (`useBlocker`) is convenient but tied to component lifecycle. Re-frame2's strengths are explicit state and dispatched events; the named-event/slot shape preserves them. Slightly more verbose at the call site; far more testable.

A test fires `[:rf/url-requested {:url "/cart"}]` against a frame whose `:editor/can-leave?` sub returns `false`, asserts `:rf/pending-navigation` is set, asserts `:rf.nav/push-url` did NOT fire, dispatches `[:rf.route/continue pending-nav-id]`, asserts the navigation completes. No DOM, no hook-mock, no event simulation.

## Query strings and fragments

Path syntax is the *primary* binding. Query strings are bound separately via the route's `:query` metadata key:

```clojure
(rf/reg-route :route/search
  {:path            "/search"
   :query           [:map [:q :string] [:page {:optional true} :int]]
   :query-defaults  {:page 1}
   :query-retain    #{:theme :locale}})

;; URL: /search?q=clojure&page=2
;; match-url yields {:route-id :route/search :params {} :query {:q "clojure" :page 2}}

;; URL: /search?q=clojure  (page absent; default applied)
;; match-url yields {:route-id :route/search :params {} :query {:q "clojure" :page 1}}
```

`:query-defaults` populates absent query keys at match time. `:query-retain` carries keys through subsequent navigations even when the caller didn't supply them — useful for global state encoded in the URL (`:theme`, `:locale`, `:debug`). The merge happens inside `:rf.route/navigate`'s handler (which has access to `app-db` and the current query slice) before the URL is built. The result: a `[:rf.route/navigate :route/cart]` from a search page preserves `?theme=dark`.

Coercion is data-shaped (the `:query` schema is the coercion specification — `:int` coerces `"2"` → `2`). No per-key middleware functions — data over functions.

The URL `#fragment` is a first-class part of the routing contract — anchor navigation, scroll-to-section, settings-tab selection. Read it via `:rf.route/fragment`. Fragment-only changes update the slice and emit `:rf.route/url-changed` trace but do NOT re-fire `:on-match` (the data didn't change; only the in-page target did).

## Multi-frame routing

Each frame may have its own `:rf/route` slice — it's a regular `app-db` path, not a special concept. **Only one frame is URL-bound.**

| Frame | URL-bound? | Behaviour |
|---|---|---|
| `:rf/default` | yes (default) | `:rf.route/navigate` events fire `:rf.nav/push-url`; popstate dispatches into this frame. The browser URL reflects this frame's route. |
| Non-default | no (default) | `:rf.route/navigate` updates the frame's `:rf/route` slice but does **not** fire `:rf.nav/push-url`. The browser URL is unchanged. |
| Non-default with `(rf/reg-frame :my-frame {:url-bound? true})` | yes (opt-in) | The runtime enforces "only one frame can own the URL at a time" — re-registering a second URL-bound frame is a `:rf.error/duplicate-url-binding` trace event. |

The story tools / devcards / SSR cases all benefit:

- **Stories / devcards.** Each story-variant frame has its own `:rf/route` independent of the page URL. A "show me the cart in the loaded state at `/cart/items/abc`" variant doesn't push to the browser bar.
- **Per-test fixtures.** Each test frame has its own route; tests don't accidentally hit `pushState`.
- **SSR per-request frames.** The request URL is fed in via `:rf.route/handle-url-change`; no client-side `pushState` (which doesn't exist server-side anyway).

## Pure helpers

Two pure functions are part of the public surface:

```clojure
(rf/match-url url)
;; → {:route-id :route/search :params {} :query {:q "clojure"} :fragment nil
;;    :validation-failed? false}
;; or nil if no route matches at all.

(rf/route-url :route/cart)
(rf/route-url :route/cart.item-detail {:id "abc"})
(rf/route-url :route/search {} {:q "clojure"})
(rf/route-url :route/docs {:page "routing"} {} "scroll-restoration")
;; → URL string, e.g. "/docs/routing#scroll-restoration"
```

Both are pure; both run on JVM and CLJS; both work against the same registered route table so adding/removing a route updates both directions automatically. `route-url` is a string-builder — it does NOT navigate, does NOT read `app-db`, does NOT push or dispatch.

To navigate, dispatch `:rf.route/navigate` (which uses `route-url` internally). To resolve a URL incoming from outside, call `match-url` (which `:rf.route/handle-url-change` uses internally).

## Worked example — the realworld scaffold

The [`examples/reagent/realworld/`](https://github.com/day8/re-frame2/tree/main/examples/reagent/realworld) example is a re-frame2 implementation of the [RealWorld example app spec](https://github.com/gothinkster/realworld). Its routing setup is the canonical end-to-end shape:

```clojure
(ns realworld.routes
  (:require [re-frame.core :as rf]))

(rf/reg-route :route/home
  {:path     "/"
   :on-match [[:articles/load-feed]]})

(rf/reg-route :route/login
  {:path "/login"})

(rf/reg-route :route/register
  {:path "/register"})

(rf/reg-route :route/article
  {:path     "/article/:slug"
   :params   [:map [:slug :string]]
   :on-match [[:article/load]
              [:article/load-comments]]})

(rf/reg-route :route/profile
  {:path     "/profile/:username"
   :params   [:map [:username :string]]
   :on-match [[:profile/load]]})

(rf/reg-route :route/editor
  {:path      "/editor"
   :tags      #{:requires-auth}
   :can-leave [:editor/can-leave?]})

(rf/reg-route :route/editor-edit
  {:path      "/editor/:slug"
   :params    [:map [:slug :string]]
   :tags      #{:requires-auth}
   :on-match  [[:editor/load-for-edit]]
   :can-leave [:editor/can-leave?]})

(rf/reg-route :route/settings
  {:path "/settings"
   :tags #{:requires-auth}})

(rf/reg-route :rf.route/not-found
  {:path "/404"})
```

The root view dispatches on `:rf.route/id`:

```clojure
(rf/reg-view app-root []
  (let [route-id @(subscribe [:rf.route/id])]
    [:div.app
     [navbar]
     [leave-confirmation]              ;; renders pending-nav dialog
     (case route-id
       :route/home          [home-page]
       :route/login         [login-page]
       :route/register      [register-page]
       :route/article       [article-page]
       :route/profile       [profile-page]
       :route/editor        [editor-page]
       :route/editor-edit   [editor-page]
       :route/settings      [settings-page]
       :rf.route/not-found  [not-found-page])
     [footer]]))
```

An auth guard (regular interceptor on `:rf.route/navigate`) consults `:tags` and redirects to `/login` when the user isn't authed:

```clojure
(def auth-guard
  (rf/->interceptor
    :id     :rf.route/auth-guard
    :before (fn [ctx]
              (let [event       (get-in ctx [:coeffects :event])
                    target      (second event)
                    route-meta  (rf/handler-meta :route target)
                    needs-auth? (boolean (some #{:requires-auth} (:tags route-meta)))
                    logged-in?  (some? (get-in ctx [:coeffects :db :auth :user]))]
                (if (and needs-auth? (not logged-in?))
                  (assoc-in ctx [:coeffects :event]
                            [:rf.route/navigate :route/login {} {} nil
                             {:return-to target}])
                  ctx)))))
```

Guards are interceptors, not a special routing mechanism. They compose; multiple guards can layer. The interceptor primitive (`->interceptor`), the sandwich shape, and how `:before` modifies the context are covered in [chapter 07 — Interceptors](07-interceptors.md).

The editor's `:can-leave` guard plus a confirmation dialog are wired exactly as described above — the dirty-flag drives the sub; the dialog renders from `:rf/pending-navigation`; user clicks dispatch `:rf.route/continue` or `:rf.route/cancel`.

The same routing setup runs **server-side under SSR** without modification. The request URL is fed to `:rf.route/handle-url-change`; `:on-match` events fire and populate the route's data; the view renders against the populated state; HTML + serialised state ship to the client; hydration restores the route along with everything else.

## Tooling and AI-amenability

- `(rf/handlers :route)` enumerates every registered route. AI scaffolds consult this before generating new routes to avoid collisions.
- `(rf/handler-meta :route :route/cart)` returns the route's metadata: path, params shape, query shape, `:on-match`, `:on-error`, `:scroll`, `:parent`, tags, source coords. The `:on-match` slot is enumerable — tools render route-loading dependency graphs without parsing handler bodies.
- `:rf.route/navigate`, `:rf.route/handle-url-change`, `:rf/url-changed`, `:rf/url-requested` are stable, named events; trace events surface every navigation and every URL request.
- A registered `:rf.route/not-found` is required by contract; tools surface the `:rf.warning/no-not-found-route` trace event for apps missing the registration.

## Next

- [19 — Where to go next](19-where-next.md) — the chapter wrap-up, with pointers to the worked examples, pattern docs, the API ref, and the spec.
- [Spec 012 — Routing](../../spec/012-Routing.md) — the full normative surface, including the path-pattern grammar's productions, the route ranking cascade, scroll restoration, and the SSR integration story in detail.
- [chapter 11](11-server-side.md) — how routing folds into SSR.
- [Pattern-StaleDetection](../../spec/Pattern-StaleDetection.md) — why nav-tokens are the same shape as `:after`-timer epochs, and the cross-cutting pattern.
