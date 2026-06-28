# 06 — Routing

Routes in re-frame2 are *data*. You register a route with a path and metadata — `:params`, `:query`, `:on-match`, `:on-error`, `:can-leave` — and the runtime turns URL changes into events the same way every other input source does. There's no parallel router state; the current route lives in the frame's **runtime-db** partition under `[:rf.runtime/routing :current]` (read it via the `:rf/route` sub); navigation is just dispatching an event; in-flight navigation is just an event sequence the cascade is mid-way through.

The point isn't novelty — every SPA framework has a router. The point is that **routing-as-state** means the router is debuggable with the same tools that debug everything else. Time-travel works. The trace bus sees navigation. Tests dispatch `:rf.route/navigate` like any other event. There's no special "router debug mode" because the router doesn't have its own mode.

This chapter covers the registration shape, the dispatch / sub / fx surface, and the helpers that map URLs to/from route ids. For nav-token semantics, `:can-leave` flows, query strings, and multi-frame routing, see [Guide ch.19 — Routing reference](concepts.md).

The `reg-route` macro is on the `re-frame.core` facade; the rest of the routing surface lives in `re-frame.routing`:

```clojure
(:require [re-frame.core    :as rf]
          [re-frame.routing :as routing])
```

## Registration

### `reg-route`

- **Kind**: macro
- **Signature**:
  ```clojure
  (reg-route id metadata path)
  ```
- **Description**: Register a route as data. The id is a keyword you'll later dispatch against (`[:rf.route/navigate :route/cart]`); the path is the third positional arg (the URL shape); the metadata map carries the match events and the guards.
- **In the wild**: [routing](https://github.com/day8/re-frame2/tree/main/examples/capabilities/routing/routing) · [realworld](https://github.com/day8/re-frame2/tree/main/examples/real-apps/realworld_http)

### A minimal route

```clojure
(rf/reg-route :route/cart
  {:on-match [[:cart/load-items]]}
  "/cart")
```

The third positional arg is the URL shape — colon-prefixed segments capture into `:params`. `:on-match` is the event vector (or vector of event vectors) the runtime dispatches when the route activates. That's the whole minimal contract; everything else is optional.

### Reserved metadata keys

| Key | Notes |
|---|---|
| `:doc` | Free-form description; pair tools read this. |
| `:params` | Schemas for path segments. |
| `:query` | Schemas for query-string keys. |
| `:query-defaults` | Default values for query keys absent from the URL. |
| `:query-retain` | Keys to preserve across navigations to other routes. |
| `:tags` | Free-form classification — `#{:auth-required :admin-only :public}`. |
| `:parent` | Another route id; builds a chain readable via `:rf.route/chain`. |
| `:on-match` | Event vector(s) to dispatch when the route activates. |
| `:on-error` | Event vector dispatched if any `:on-match` event errors. |
| `:can-leave` | Guard sub-query run before leaving the route. **Closed boolean contract**: `true` allows the navigation, `false` blocks it; any non-boolean value blocks and emits `:rf.error/can-leave-non-boolean`. The sub name reads positively (`:can-leave`), so `false` means "can NOT leave". See [Guide ch.19 — Navigation blocking](concepts.md). |
| `:scroll` | Scroll-restoration behaviour for this route. |

Canonical detail in [The metadata map, in full](concepts.md#the-metadata-map-in-full) in the routing concept guide.

## URL helpers

### `match-url`

- **Kind**: function
- **Signature**:
  ```clojure
  (match-url url) → {:route-id :params :query :validation-failed?} or nil
  ```
- **Description**: "What route does this URL match?" Pure — JVM-runnable; useful for server-side rendering and tests.

### `route-url`

- **Kind**: function
- **Signature**:
  ```clojure
  (route-url route-id path-params) → URL string
  (route-url route-id path-params query-params) → URL string
  ```
- **Description**: "Render this route to a URL." The inverse of `match-url`. Pure; JVM-runnable.

### `route-link`

- **Kind**: registered view (function)
- **Signature**:
  ```clojure
  [rf/route-link {:to :route-id :params {...} :query {...} :fragment "..."} & children]
  ```
- **Description**: A registered view at `:route/link`. Renders an `<a>` with the right `href` and intercepts plain primary-button clicks to dispatch `:rf/url-requested` instead of navigating natively.
- **Example**:
  ```clojure
  [rf/route-link {:to :route/article :params {:slug slug}} (:title article)]
  ```
- **In the wild**: [routing](https://github.com/day8/re-frame2/tree/main/examples/capabilities/routing/routing) · [realworld](https://github.com/day8/re-frame2/tree/main/examples/real-apps/realworld_http)

### `route-link` click semantics

A plain primary-button click (no modifier keys, no `defaultPrevented`) calls `.preventDefault` and dispatches:

```clojure
[:rf/url-requested {:url      <synthesised>
                    :to       <route-id>
                    :params   {...}
                    :query    {...}
                    :fragment "..."}]
```

Modifier-key clicks (cmd / ctrl / shift / alt) and middle-button clicks defer to the browser so the native `href` opens in a new tab. A caller-supplied `:on-click` runs first; if it calls `.preventDefault` (or otherwise leaves `defaultPrevented` true) the framework's interception is skipped. Keys other than `:to` / `:params` / `:query` / `:fragment` / `:on-click` pass through to the underlying `<a>` element.

Anchors carrying **native-handling attributes** are never intercepted — even on a plain left-click — because their DOM semantics must win: a `:target` other than `_self` (`_blank` / `_parent` / `_top` / a named frame) opens the href outside the current document, and `:download` instructs the browser to save the resource. A `route-link` rendered as `{:target "_blank"}` or `{:download "report.pdf"}` therefore behaves as the equivalent plain `<a>` would. To get SPA interception, omit those attributes (or use `:target "_self"`).

Detailed semantics in [Linking from views](concepts.md#linking-from-views) in the routing concept guide.

## Events

These are the standard events the runtime dispatches (or you dispatch) around routing.

| Event | Notes |
|---|---|
| `:rf.route/navigate` | Navigate to a registered route. Args: `{:to :route-id :params {...} :query {...}}`. |
| `:rf.route/handle-url-change` | URL-change handler for popstate / initial load / SSR (default scroll `:restore`). Co-equal sibling of `:rf.route/transitioned` — same slice-rewrite logic, not a delegate. Override for custom URL-change handling. |
| `:rf.route/transitioned` | URL-change handler for forward navigation — a link click or programmatic push (default scroll `:top`). The runtime dispatches this; you read it. |
| `:rf/url-requested` | The user clicked a framework-owned link. `route-link` synthesises this event; you usually let the default handler take it. |
| `:rf.route/navigation-blocked` | A `:can-leave` guard rejected a navigation. The pending nav slot in `app-db` carries the rejected navigation. |
| `:rf.route/continue` | User-dispatched event proceeding a blocked navigation — "yes, leave the page." |
| `:rf.route/cancel` | User-dispatched event abandoning a blocked navigation — "stay here, drop the pending nav." |

## Subscriptions

The full `:rf/route` slice is `{:id :params :query :transition :error}`. The standard subs are projections of that slice plus a couple of conveniences.

| Sub | Returns |
|---|---|
| `:rf/route` | The full `:rf/route` slice `{:id :params :query :transition :error}` |
| `:rf.route/id` | Current route id |
| `:rf.route/params` | Current path params |
| `:rf.route/query` | Current query params |
| `:rf.route/transition` | `:idle` / `:loading` / `:error` |
| `:rf.route/error` | Current error map (when `:transition = :error`) |
| `:rf.route/fragment` | Current URL fragment (string or `nil`) |
| `:rf.route/chain` | Vector of route ids from parent-most to current (per `:parent` links) |
| `:rf/pending-navigation` | The pending-nav slot (per `:rf/pending-navigation` schema) when a navigation is blocked; `nil` otherwise |

## Fx

| Fx | Args | Platforms | Notes |
|---|---|---|---|
| `[:rf.nav/push-url url-string]` | URL string | `:client` | Push a new URL onto the browser history. |
| `[:rf.nav/replace-url url-string]` | URL string | `:client` | Replace the current URL without adding a history entry. |
| `[:rf.nav/scroll scroll-spec]` | scroll-spec map | `:client` | Restore or set scroll position. |
| `[:rf.route/with-nav-token {:rf/reply-to <reply-target> :nav-token <token>}]` | universal | universal | Name an async-completion continuation by its canonical `:rf/reply-to` reply target and guard it with a navigation token. On match the target is completed with the `:status :ok` reply map; if the token has been superseded by a later navigation, the completion is suppressed and `:rf.route.nav-token/stale-suppressed` fires. |

The nav-token wrapper is what makes "user navigates away mid-load" safe: the older load's reply carries the stale token, the runtime suppresses it, and you don't see the older page's data overwrite the newer page's state. Full semantics in [Guide ch.19 — Navigation tokens](concepts.md).

## See also

- [01 — Core](../core/api/01-core.md) — `reg-route` rowed in registration.
- [SSR API](../ssr/api.md) — routes participate in SSR; the active route's `:head` registration is what `render-head` looks up.
- [Guide ch.18 — Routing](concepts.md) and [Guide ch.19 — Routing reference](concepts.md) — narrative coverage including nav-token semantics, `:can-leave` flows, query strings, and multi-frame routing.
- [Routing glossary](glossary.md) — the surface vocabulary (navigate, route, loader, route guard, not-found, url-bound?).
- [Coming from React Router](coming-from-react-router.md) — the mapping, and where re-frame2 routing diverges.
