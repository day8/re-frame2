# re-frame.routing

Routes in re-frame2 are *data*. You register a route with a path and metadata — `:params`, `:query`, `:on-match`, `:on-error`, `:can-leave` — and the runtime turns URL changes into events the same way every other input source does. There's no parallel router state: the current route lives in the frame's runtime-db partition under `[:rf.runtime/routing :current]` (read it via the `:rf/route` sub), navigation is just dispatching an event, and in-flight navigation is just an event sequence the cascade is mid-way through. Because routing is state, the router is debuggable with the same tools that debug everything else — time-travel works, the trace bus sees navigation, and tests dispatch `:rf.route/navigate` like any other event. This namespace is the public boot point and façade for the routing artefact: apps load it with `(:require [re-frame.routing])`, which wires every routing event, fx, and sub. The `reg-route` macro is published on the `re-frame.core` facade; the rest of the surface — URL helpers, registry introspection, scroll restoration, and the multi-frame URL-ownership resolver — lives here.

```clojure
(:require [re-frame.routing :as routing])
```

Throughout, `rf` denotes the `re-frame.core` facade alias (`[re-frame.core :as rf]`), where the `reg-route` macro and the `route-link` view live.

## Route registration

### `reg-route`

- **Kind**: macro
- **Signature**:
  ```clojure
  (reg-route id metadata path)
  ```
- **Description**: Register a route as data. The id is a keyword you'll later dispatch against (`[:rf.route/navigate :route/cart]`); the path is the third positional arg (the URL shape); the metadata map carries the match events and the guards.

#### A minimal route

```clojure
(rf/reg-route :route/cart
  {:on-match [[:cart/load-items]]}
  "/cart")
```

The third positional arg is the URL shape — colon-prefixed segments capture into `:params`. `:on-match` is the event vector (or vector of event vectors) the runtime dispatches when the route activates. That's the whole minimal contract; everything else is optional.

#### Reserved metadata keys

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
| `:can-leave` | Guard sub-query run before leaving the route. **Closed boolean contract**: `true` allows the navigation, `false` blocks it; any non-boolean value blocks and emits `:rf.error/can-leave-non-boolean`. The sub name reads positively (`:can-leave`), so `false` means "can NOT leave". See [Routing → Blocking a navigation](../routing/concepts.md#blocking-a-navigation). |
| `:scroll` | Scroll-restoration behaviour for this route. |

Canonical detail in [The metadata map, in full](../routing/concepts.md#the-metadata-map-in-full) in the routing concept guide.

### `clear-route`

- **Kind**: function
- **Signature**:
  ```clojure
  (clear-route id) → nil
  ```
- **Description**: Remove a registered route. Emits `:rf.route/cleared` so tools subscribing to route lifecycle observe the removal (symmetric with `:rf.flow/cleared`). No-op when the route id was not registered.

### `reset-counters!`

- **Kind**: function
- **Signature**:
  ```clojure
  (reset-counters!)
  ```
- **Description**: Test-time helper. Resets the route-registration index counter to zero so the registration-order tie-breaker in route ranking is deterministic across fixture runs.

## URL and route matching

The URL ↔ route mapping is a **prism**: `match-url` reads a URL to route data, `route-url` renders route data back to a URL, and `match-url(route-url(...))` round-trips the canonical route data. Both are pure and JVM-runnable.

### `match-url`

- **Kind**: function
- **Signature**:
  ```clojure
  (match-url url) → {:route-id :params :query :validation-failed?} or nil
  ```
- **Description**: "What route does this URL match?" Pure — JVM-runnable; useful for server-side rendering and tests.
- **Example**:
  ```clojure
  ;; with (rf/reg-route :user/show {} "/users/:id") registered:
  (routing/match-url "/users/42")
  ;; => {:route-id :user/show, :params {:id "42"}, :query {}, :fragment nil}

  ;; nil when no route matches:
  (routing/match-url "/no/such/path")  ;; => nil
  ```

### `route-url`

- **Kind**: function
- **Signature**:
  ```clojure
  (route-url route-id path-params) → URL string
  (route-url route-id path-params query-params) → URL string
  ```
- **Description**: "Render this route to a URL." The inverse of `match-url`. Pure; JVM-runnable.
- **Example**:
  ```clojure
  ;; with (rf/reg-route :user/show {} "/users/:id") registered:
  (routing/route-url :user/show {:id 42})            ;; => "/users/42"

  ;; query params are appended and percent-encoded:
  (routing/route-url :search {} {:q "hello world"})  ;; => "/search?q=hello%20world"
  ```

### `malformed-url?`

- **Kind**: function
- **Signature**:
  ```clojure
  (malformed-url? url) → boolean
  ```
- **Description**: Predicate: `true` when any percent-encoded portion of `url` (a non-empty path segment, a query key or value, or the `#fragment`) is malformed. The scan is purely lexical — no route table is consulted. The `:rf.route/transitioned` / `:rf.route/handle-url-change` handlers use it to discriminate the bare route-miss case (`{:url url}`) from the malformed-URL fail-closed case (`{:url url :reason :malformed-url}`); both end at `:rf.route/not-found`, but the structured `:reason` lets per-route error UIs and SSR projections branch on the cause.

### `current-url`

- **Kind**: function
- **Signature**:
  ```clojure
  (current-url) → app-relative URL string
  ```
- **Description**: Read the current browser URL as an app-relative string (`pathname + search + hash`). CLJS-only — returns `"/"` when no `window.location` is available (SSR / Node). Public so apps that wire their own history listener can recover the same projection the framework's popstate listener uses.

## Introspection and slice access

The read-side surface over the route registry and the live route slice — the static "which routes are registered, and what is route X's spec?" accessors plus the live per-frame slice readers. The `*-algebra-view` helpers lower routes into the shared derivation/process-algebra node shape so a tool can show subscriptions, flows, resources, route facts, and machine selectors as one family.

### `route-ids`

- **Kind**: function
- **Signature**:
  ```clojure
  (route-ids) → vector of route ids
  ```
- **Description**: Return a vector of every registered route id — the static-registry "enumerate" half of routing introspection (the live per-frame route slice is read through the `:rf.route/*` subs). Mirrors the sibling `resource-ids` / machines `machines` accessors.
- **Example**:
  ```clojure
  (routing/route-ids)  ;; => [:route/cart :user/show]
  ```

### `route-meta`

- **Kind**: function
- **Signature**:
  ```clojure
  (route-meta route-id) → metadata map or nil
  ```
- **Description**: Return the registered route's metadata map (`:path` pattern, `:on-match`, `:params`, `:query`, `:scroll`, `:can-leave`, the computed `:rf.route/rank` / `:rf.route/compiled` / coercion tables, source coords) for `route-id`, or `nil` if no route is registered under that id. Mirrors the sibling `resource-meta` / machines `machine-meta` accessors.

### `route-algebra-view`

- **Kind**: function (JVM convenience alias; CLJS callers use `re-frame.routing.tooling/route-algebra-view`)
- **Signature**:
  ```clojure
  (route-algebra-view) → {route-id route-fact-node}
  (route-algebra-view route-id) → route-fact-node or nil
  ```
- **Description**: The STATIC derivation/process-algebra view of every registered route — pure data over the `:route` registrar kind (no app-db, no runtime-db, no live slice). Each route lowers to a normalized node carrying `:id` `:rf/route` (the route FACT identity — every route materializes the one route slice; the per-route registration id is under `:source-form`), `:kind` `:process`, `:refinement` `:route-fact`, `:source-form` `{:kind :reg-route :id <route-id>}`, the route-transition `:inputs` (`:rf.route/navigate` / `:rf.route/transitioned` / `:rf.route/handle-url-change`), `:output` `[:runtime [:rf.runtime/routing :current]]`, `:storage` `:runtime-db`, `:evaluation` `:on-route`, `:lifecycle` `:frame`, `:materialized?` `true`, and (only when the route declares `:resources`) `:resource-edges`. Zero-arity returns the map for every route (`{}` when none); one-arity returns one node or `nil`. JVM-runnable; consumed by Xray and the conformance fixtures. There is no `re-frame.core` facade export.

### `route-slice-algebra-view`

- **Kind**: function
- **Signature**:
  ```clojure
  (route-slice-algebra-view frame-id) → route-fact-node or nil
  ```
- **Description**: The LIVE counterpart to `route-algebra-view`: the route fact materialized in a frame's runtime-db at `[:rf.runtime/routing :current]` — the concrete matched route, its params, query, transition state, and nav-token (the live route owner). Returns a single `:route-fact` node, or `nil` for a missing / destroyed frame or when no route has been materialized yet (no navigation has committed). Adds `:route-id`, `:params`, `:query`, `:transition`, `:nav-token`, and `:owner` `[:route <route-id> <nav-token>]` to the same fixed classifications the static node carries. CLJS- and JVM-runnable (a single runtime-db container deref).

### `route-sub-fn`

- **Kind**: function
- **Signature**:
  ```clojure
  (route-sub-fn db query-v) → route slice
  ```
- **Description**: The layer-1 sub fn behind `:rf/route` — reads the route slice from `[:rf.runtime/routing :current]`. Exposed publicly so external callers (smoke tests, tooling) read the slice without re-deriving the path.

### `sub-pending-navigation`

- **Kind**: function
- **Signature**:
  ```clojure
  (re-frame.routing/sub-pending-navigation)        ;; → reaction over the pending-nav map, or nil
  (re-frame.routing/sub-pending-navigation opts)   ;; opts {:frame <id-or-frame>}
  ```
- **Description**: Named read sugar over `[:rf/pending-navigation]`. Returns a reaction over the pending-navigation map `{:requested-url :requested-by-event :rejecting-route :rejecting-guard …}`, or `nil` in the steady state — the slot is non-nil only while a `:can-leave` guard holds a blocked navigation awaiting `:rf.route/continue` / `:rf.route/cancel`. Zero-arity is the primary form (the slot is a per-frame singleton); the 1-arity `opts` carries `{:frame …}` to target an explicit (e.g. non-default url-bound) frame. The vector form remains the canonical registered sub.

```clojure
(require '[re-frame.routing :as routing])

;; show an "unsaved changes?" prompt only while a navigation is blocked
(when-let [pending @(routing/sub-pending-navigation)]
  [confirm-leave-dialog pending])
```

## Scroll restoration

Saved scroll positions are a **host-side, per-frame transient LRU cache** keyed by frame-id — NOT runtime-db state. They are host-derived (read from `window.scrollX/Y`), meaningless server-side, and not needed to reconstitute a coherent frame on restore / SSR-hydration / time-travel, so they never ride the trace / epoch / SSR egress wire and cannot rewind on an epoch restore. The pure helpers operate on a plain per-frame cache map `{:positions {url [x y]} :order [url ...]}`; the `!`-suffixed wrappers read/write the host cache.

### `scroll-positions-cap`

- **Kind**: value
- **Signature**:
  ```clojure
  scroll-positions-cap  ;; => 50
  ```
- **Description**: Soft upper bound on tracked URLs in the per-frame scroll-positions cache. Sized for typical SPA navigation depth — large enough that real Back-button restoration hits saved positions, small enough that the per-frame host cache stays bounded over long sessions.

### `frame-scroll-cache`

- **Kind**: function
- **Signature**:
  ```clojure
  (frame-scroll-cache frame-id) → {:positions :order} or nil
  ```
- **Description**: Read the per-frame cache map (`{:positions :order}`) for `frame-id` from the host scroll-position cache, or `nil` when none. The value threaded into the pure nav-planning seam.

### `lookup-scroll-position`

- **Kind**: function
- **Signature**:
  ```clojure
  (lookup-scroll-position cache url) → [x y] or nil
  ```
- **Description**: Pure. Return the saved `[x y]` for `url` in `cache` (a per-frame cache map `{:positions {url [x y]} :order [...]}`, or `nil`), or `nil` if none.

### `save-scroll-position`

- **Kind**: function
- **Signature**:
  ```clojure
  (save-scroll-position cache url xy) → cache'
  ```
- **Description**: Pure. Return `cache` with the scroll position for `url` recorded under `:positions`. LRU-capped at `scroll-positions-cap`: re-saving an existing url promotes it to most-recent; new saves past the cap evict the least-recently-used entry. The `:order` vector is the recency anchor.

### `save-scroll-position!`

- **Kind**: function
- **Signature**:
  ```clojure
  (save-scroll-position! frame-id url xy) → nil
  ```
- **Description**: Record `xy` for `url` under `frame-id` in the host scroll-position cache, applying the LRU cap via the pure `save-scroll-position`.

### `reset-scroll-cache!`

- **Kind**: function
- **Signature**:
  ```clojure
  (reset-scroll-cache!) → nil
  ```
- **Description**: Test-time helper. Drop the whole host scroll-position cache so a saved position does not leak across tests.

## Navigation counters and state classification

The nav-token and pending-nav allocators are **host-side, per-frame, monotonic high-water marks** (not runtime-db), so an epoch restore — which replaces the runtime-db partition wholesale — cannot rewind them and recycle a token still carried by a slow in-flight continuation. `counter-snapshot` reads them; `routing-state-classification` is the canonical durable/transient map that SSR, docs, and schemas key off.

### `counter-snapshot`

- **Kind**: function
- **Signature**:
  ```clojure
  (counter-snapshot frame-id) → {:nav-token-counter N :pending-nav-counter M} or {}
  ```
- **Description**: Read the per-frame counter snapshot for `frame-id` from the host nav-counters cache, or `{}` when none. The value the allocation-cofx generators mint the next nav-token / pending-nav id from.

### `routing-state-classification`

- **Kind**: value
- **Signature**:
  ```clojure
  routing-state-classification
  ;; => {:durable-runtime-db             {:keys [:current] :doc "..."}
  ;;     :local-subscribable-runtime-db  {:keys [:pending-navigation] :doc "..."}
  ;;     :host-transient                 {:keys [:scroll-positions
  ;;                                             :nav-token-counter
  ;;                                             :pending-nav-counter] :doc "..."}}
  ```
- **Description**: The canonical classification of every piece of per-frame routing state, by tier — `:durable-runtime-db` (serializable facts needed to reconstitute a coherent frame on restore / SSR-hydration; the route slice at `:current`), `:local-subscribable-runtime-db` (runtime-db state that stays subscribable and restores in local replay but is SSR-stripped fail-closed; the `:pending-navigation` slot), and `:host-transient` (host-derived caches never in runtime-db; saved scroll positions and the two allocator high-water marks). SSR, docs, and Spec-Schemas consume this so the durable/transient split has one home.

### `reset-nav-counters!`

- **Kind**: function
- **Signature**:
  ```clojure
  (reset-nav-counters!) → nil
  ```
- **Description**: Test-time helper. Drop the whole host nav-counters cache so a counter value does not leak across tests. Wired into the shared reset-runtime fixture via the `:routing/reset-nav-counters!` late-bind key.

## Multi-frame URL ownership

At most one frame owns the browser URL at a time. A frame claims ownership by registering with `{:url-bound? true}`; the resolver below names the current owner, and the outbound `:rf.nav/push-url` fx and the inbound popstate listener both route through it — one owner, both directions.

### `url-owner-frame-id`

- **Kind**: function
- **Signature**:
  ```clojure
  (url-owner-frame-id) → frame-id or nil
  ```
- **Description**: Return the single frame that has EXPLICITLY declared browser-history ownership via `(rf/reg-frame :id {:url-bound? true})`, or `nil` when no frame has. URL ownership is an explicit host/bootstrap policy, not an absence repair — the runtime never infers `:rf/default` as the owner; `:rf/default` owns the URL only when it carries an explicit `{:url-bound? true}` like any other frame. Ownership resolves to the FIRST-CLAIMED still-live `:url-bound? true` frame (the incumbent), so a later duplicate cannot steal the URL. `nil` means no owner is declared — outbound history fxs no-op and the inbound popstate listener skips.
- **Example**:
  ```clojure
  ;; one frame opts into URL ownership at boot:
  (rf/reg-frame :app/main {:url-bound? true})
  (routing/url-owner-frame-id)  ;; => :app/main
  ```

### `reset-url-claims!`

- **Kind**: function
- **Signature**:
  ```clojure
  (reset-url-claims!) → nil
  ```
- **Description**: Test-time helper. Drop the whole URL-ownership claim-order vector so a prior test's URL claim does not leak into the next. Wired into the shared reset-runtime fixture via the `:routing/reset-url-claims!` late-bind key.

## Server-side rendering

### `route-link-render-ssr`

- **Kind**: function
- **Signature**:
  ```clojure
  (route-link-render-ssr props & children) → hiccup
  ```
- **Description**: The JVM render fn for the `:route/link` view. Renders the `<a href=...>` shell without the click-interception logic — server-side rendering has no DOM events to intercept, so the anchor is emitted as-is and clicks on the hydrated page run the CLJS render fn's on-click path. The authoring surface — the `route-link` view itself — is on the `re-frame.core` facade; see [re-frame.core.md](re-frame.core.md).

## Keyword surfaces

The routing artefact registers a family of events, subscriptions, and effects addressed by keyword. Loading `re-frame.routing` wires them all.

### Events

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

### Subscriptions

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
| `:rf/pending-navigation` | The pending-nav slot (per `:rf/pending-navigation` schema) when a navigation is blocked; `nil` otherwise. Named read sugar: [`sub-pending-navigation`](#sub-pending-navigation). |

### Effects (`fx`)

| Fx | Args | Platforms | Notes |
|---|---|---|---|
| `[:rf.nav/push-url url-string]` | URL string | `:client` | Push a new URL onto the browser history. |
| `[:rf.nav/replace-url url-string]` | URL string | `:client` | Replace the current URL without adding a history entry. |
| `[:rf.nav/scroll scroll-spec]` | scroll-spec map | `:client` | Restore or set scroll position. |
| `[:rf.route/with-nav-token {:rf/reply-to <reply-target> :nav-token <token>}]` | universal | universal | Name an async-completion continuation by its canonical `:rf/reply-to` reply target and guard it with a navigation token. On match the target is completed with the `:status :ok` reply map; if the token has been superseded by a later navigation, the completion is suppressed and `:rf.route.nav-token/stale-suppressed` fires. |

The nav-token wrapper is what makes "user navigates away mid-load" safe: the older load's reply carries the stale token, the runtime suppresses it, and you don't see the older page's data overwrite the newer page's state. Full semantics in [Routing → Loaders](../routing/concepts.md#loaders-declaring-a-pages-data) (the nav-token "going deeper").

## See also

- [re-frame.core.md](re-frame.core.md) — the `re-frame.core` facade: the `reg-route` macro's brief row and the `route-link` view.
- [re-frame.ssr.md](re-frame.ssr.md) — routes participate in SSR; the active route's `:head` registration is what `render-head` looks up.
- [Routing guide](../routing/index.md) — the narrative side: a [tutorial](../routing/tutorial.md), [concepts](../routing/concepts.md) (nav-token semantics, `:can-leave` flows, query strings, multi-frame routing), and how-to recipes.
- [Routing glossary](../routing/glossary.md) — the surface vocabulary (navigate, route, loader, route guard, not-found, url-bound?).
- [Coming from React Router](../routing/coming-from-react-router.md) — the mapping, and where re-frame2 routing diverges.
