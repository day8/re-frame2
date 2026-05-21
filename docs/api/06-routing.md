# 06 — Routing

Routes in re-frame2 are *data*. You register a route with metadata — `:path`, `:params`, `:query`, `:on-match`, `:on-error`, `:can-leave` — and the runtime turns URL changes into events the same way every other input source does. There's no parallel router state; current route lives in `app-db` under `:rf/route`; navigation is just dispatching an event; in-flight navigation is just an event sequence the cascade is mid-way through.

The point isn't novelty — every SPA framework has a router. The point is that **routing-as-state** means the router is debuggable with the same tools that debug everything else. Time-travel works. The trace bus sees navigation. Tests dispatch `:rf.route/navigate` like any other event. There's no special "router debug mode" because the router doesn't have its own mode.

This chapter covers the registration shape, the dispatch / sub / fx surface, and the helpers that map URLs to/from route ids. For nav-token semantics, `:can-leave` flows, query strings, and multi-frame routing, see [Guide ch.19 — Routing reference](../guide/19-routing-ref.md). The normative source is [012-Routing.md](../../spec/012-Routing.md).

## Registration

### `reg-route`

- **Kind**: macro
- **Signature**:
  ```clojure
  (reg-route id metadata)
  ```
- **Status**: v1
- **Description**: Register a route as data. The id is a keyword you'll later dispatch against (`[:rf.route/navigate :route/cart]`); the metadata carries the URL shape, the match events, and the guards.

### A minimal route

```clojure
(rf/reg-route :route/cart
  {:path     "/cart"
   :on-match [[:cart/load-items]]})
```

`:path` is the URL shape — colon-prefixed segments capture into `:params`. `:on-match` is the event vector (or vector of event vectors) the runtime dispatches when the route activates. That's the whole minimal contract; everything else is optional.

### Reserved metadata keys

| Key | Notes |
|---|---|
| `:doc` | Free-form description; pair tools read this. |
| `:path` | The URL shape — `/users/:id/posts/:slug`, etc. |
| `:params` | Schemas for path segments (per [Spec-Schemas](../../spec/Spec-Schemas.md)). |
| `:query` | Schemas for query-string keys. |
| `:query-defaults` | Default values for query keys absent from the URL. |
| `:query-retain` | Keys to preserve across navigations to other routes. |
| `:tags` | Free-form classification — `#{:auth-required :admin-only :public}`. |
| `:parent` | Another route id; builds a chain readable via `:rf.route/chain`. |
| `:on-match` | Event vector(s) to dispatch when the route activates. |
| `:on-error` | Event vector dispatched if any `:on-match` event errors. |
| `:can-leave` | Predicate or guard event; blocks navigation when truthy. See [Guide ch.19 — Navigation blocking](../guide/19-routing-ref.md#navigation-blocking--the-can-leave-protocol). |
| `:scroll` | Scroll-restoration behaviour for this route. |

Canonical detail in [012-Routing.md](../../spec/012-Routing.md); the metadata schema is [Spec-Schemas §`:rf/route-metadata`](../../spec/Spec-Schemas.md#rfroute-metadata).

## URL helpers

### `match-url`

- **Kind**: function
- **Signature**:
  ```clojure
  (match-url url) → {:route-id :params :query :validation-failed?} or nil
  ```
- **Status**: v1
- **Description**: "What route does this URL match?" Pure — JVM-runnable; useful for server-side rendering and tests.

### `route-url`

- **Kind**: function
- **Signature**:
  ```clojure
  (route-url route-id path-params) → URL string
  (route-url route-id path-params query-params) → URL string
  ```
- **Status**: v1
- **Description**: "Render this route to a URL." The inverse of `match-url`. Pure; JVM-runnable.

### `route-link`

- **Kind**: registered view (function)
- **Signature**:
  ```clojure
  [rf/route-link {:to :route-id :params {...} :query {...} :fragment "..."} & children]
  ```
- **Status**: v1
- **Description**: A registered view at `:route/link`. Renders an `<a>` with the right `href` and intercepts plain primary-button clicks to dispatch `:rf/url-requested` instead of navigating natively.

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

Detailed semantics in [012-Routing.md §Linking from views](../../spec/012-Routing.md#linking-from-views--plain-anchor-semantics).

## Events

These are the standard events the runtime dispatches (or you dispatch) around routing.

| Event | Notes | Spec |
|---|---|---|
| `:rf.route/navigate` | Navigate to a registered route. Args: `{:to :route-id :params {...} :query {...}}`. | 012 |
| `:rf.route/handle-url-change` | Default handler for `:rf.route/transitioned`. You'd override this if you want custom transition handling. | 012 |
| `:rf.route/transitioned` | The browser URL changed (popstate, pushState, etc.). The runtime dispatches this; you read it. | 012 |
| `:rf/url-requested` | The user clicked a framework-owned link. `route-link` synthesises this event; you usually let the default handler take it. | 012 |
| `:rf.route/navigation-blocked` | A `:can-leave` guard rejected a navigation. The pending nav slot in `app-db` carries the rejected navigation. | 012 |
| `:rf.route/continue` | User-dispatched event proceeding a blocked navigation — "yes, leave the page." | 012 |
| `:rf.route/cancel` | User-dispatched event abandoning a blocked navigation — "stay here, drop the pending nav." | 012 |

## Subscriptions

The full `:rf/route` slice is `{:id :params :query :transition :error}`. The standard subs are projections of that slice plus a couple of conveniences.

| Sub | Returns | Spec |
|---|---|---|
| `:rf/route` | The full `:rf/route` slice `{:id :params :query :transition :error}` | 012 |
| `:rf.route/id` | Current route id | 012 |
| `:rf.route/params` | Current path params | 012 |
| `:rf.route/query` | Current query params | 012 |
| `:rf.route/transition` | `:idle` / `:loading` / `:error` | 012 |
| `:rf.route/error` | Current error map (when `:transition = :error`) | 012 |
| `:rf.route/fragment` | Current URL fragment (string or `nil`) | 012 |
| `:rf.route/chain` | Vector of route ids from parent-most to current (per `:parent` links) | 012 |
| `:rf/pending-navigation` | The pending-nav slot (per `:rf/pending-navigation` schema) when a navigation is blocked; `nil` otherwise | 012 |

## Fx

| Fx | Args | Platforms | Notes |
|---|---|---|---|
| `[:rf.nav/push-url url-string]` | URL string | `:client` | Push a new URL onto the browser history. |
| `[:rf.nav/replace-url url-string]` | URL string | `:client` | Replace the current URL without adding a history entry. |
| `[:rf.nav/scroll scroll-spec]` | scroll-spec map | `:client` | Restore or set scroll position. |
| `[:rf.route/with-nav-token {:do <fx-entry> :nav-token <token>}]` | universal | universal | Wrap an fx with a navigation token. If the token has been superseded by a later navigation, the wrapped fx is suppressed and `:rf.route.nav-token/stale-suppressed` fires. |

The nav-token wrapper is what makes "user navigates away mid-load" safe: the older load's reply carries the stale token, the runtime suppresses it, and you don't see the older page's data overwrite the newer page's state. Full semantics in [Guide ch.19 — Navigation tokens](../guide/19-routing-ref.md#navigation-tokens--stale-result-suppression).

## See also

- [01 — Core](01-core.md) — `reg-route` rowed in registration.
- [09 — SSR](09-ssr.md) — routes participate in SSR; the active route's `:head` registration is what `render-head` looks up.
- [Guide ch.18 — Routing](../guide/18-routing.md) and [Guide ch.19 — Routing reference](../guide/19-routing-ref.md) — narrative coverage including nav-token semantics, `:can-leave` flows, query strings, and multi-frame routing.
- [Spec 012 — Routing](../../spec/012-Routing.md) — the normative source.
