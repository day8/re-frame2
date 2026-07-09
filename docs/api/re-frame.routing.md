# re-frame.routing

Routes are data. You register a route with a path and a metadata map (`:params`, `:query`, `:on-match`, `:on-error`, `:can-leave`, …). URL changes become events. The current route lives in the frame's runtime-db at `[:rf.runtime/routing :current]` and is read via the `:rf/route` sub. Navigation is dispatching an event.

This namespace is the public boot point and façade for the routing artefact. Requiring it wires every routing event, fx, cofx, and sub. The `reg-route` macro is published on the `re-frame.core` facade. The rest of the surface lives here:

- URL helpers
- registry introspection
- scroll restoration
- URL strategies
- the browser URL-listener boot seam
- the multi-frame URL-ownership resolver

For motivation and narrative, see the [Routing guide](../routing/index.md).

```clojure
(:require [re-frame.routing :as routing])
```

Throughout, `rf` is the `re-frame.core` facade alias (`[re-frame.core :as rf]`). The `reg-route` macro and the `route-link` view live on that facade.

## Route registration

### `reg-route`

- **Kind**: function (`re-frame.core` publishes the source-coord-capturing `reg-route` macro; this namespace's `reg-route` is the equivalent plain fn)
- **Signature**:
  ```clojure
  (reg-route id metadata path) → id
  ```
- **Description**: Register a route as data.

  - `id` — keyword dispatched against later (`[:rf.route/navigate :route/cart]`).
  - `metadata` — map of match events and guards (keys below).
  - `path` — the URL shape; colon-prefixed segments capture into `:params`.

  Throws `:rf.error/route-bad-metadata` when `metadata`:

  - is not a map,
  - carries `:path` (the path pattern belongs in the third slot), or
  - carries a bare (unqualified) key outside the reserved set below. Namespaced keys (`:myapp/analytics-id`) always pass.

  Emits `:rf.warning/route-shadowed-by-equal-score` when an already-registered route has an equal structural rank. Emits `:rf.route/registered` on first-time registration.

#### A minimal route

```clojure
(rf/reg-route :route/cart
  {:on-match [[:cart/load-items]]}
  "/cart")
```

Colon-prefixed path segments capture into `:params`. `:on-match` is the event vector (or vector of event vectors) dispatched when the route activates. Everything else is optional.

#### Reserved metadata keys

| Key | Notes |
|---|---|
| `:doc` | Free-form description; pair tools read this. |
| `:params` | Schemas for path segments. |
| `:query` | Schemas for query-string keys. |
| `:query-defaults` | Default values for query keys absent from the URL. |
| `:query-retain` | Keys to preserve across navigations to other routes. |
| `:tags` | Free-form classification, e.g. `#{:auth-required :admin-only :public}`. |
| `:parent` | Another route id; builds a chain readable via `:rf.route/chain`. |
| `:on-match` | Event vector(s) to dispatch when the route activates. |
| `:on-error` | Event vector dispatched if any `:on-match` event errors. |
| `:can-leave` | Guard sub-query run before leaving the route. Closed boolean contract: `true` allows, `false` blocks. Any non-boolean also blocks and emits `:rf.error/can-leave-non-boolean`. The name reads positively, so `false` means "can NOT leave". The sub receives the pending target as an argument. See [Routing → Blocking a navigation](../routing/concepts.md#blocking-a-navigation). |
| `:can-enter` | Guard sub-query run before entering the route (the auth-gate mirror of `:can-leave`). Closed boolean contract: `true` allows entry, `false` blocks. Any non-boolean also blocks and emits `:rf.error/can-enter-non-boolean`. On a block the runtime dispatches `:rf.route/entry-blocked`. See [Routing → Guarding entry](../routing/concepts.md#guarding-entry--can-enter). |
| `:scroll` | Scroll-restoration behaviour for this route. |
| `:sensitive` | Slice paths (projection-relative, e.g. `[:query :token]`) redacted at egress while the route is active. See [Routing → Keeping tokens off the wire](../routing/concepts.md#keeping-tokens-off-the-wire). |
| `:large` | Slice paths kept off the wire at egress (a size marker ships instead of the value). |

Two cross-feature bare keys are also accepted:

- `:head` — SSR's head-metadata contract. It is always in the accepted set.
- `:resources` — the Resources artefact's route integration, late-bound via the `:routing/extra-route-keys` hook. In an app without the Resources artefact, `:resources` is rejected like any other unknown bare key.

Canonical detail in [The metadata map, in full](../routing/concepts.md#the-metadata-map-in-full).

### `clear-route`

- **Kind**: function
- **Signature**:
  ```clojure
  (clear-route id) → nil
  ```
- **Description**: Remove a registered route. Emits `:rf.route/cleared` (symmetric with `:rf.flow/cleared`) so tools subscribing to route lifecycle observe the removal. No-op when `id` was not registered.

### `reset-counters!`

- **Kind**: function
- **Signature**:
  ```clojure
  (reset-counters!)
  ```
- **Description**: Test-time helper. Resets the route-registration index counter to zero so the registration-order tie-breaker in route ranking is deterministic across fixture runs.

## URL and route matching

The URL ↔ route mapping is a prism. `match-url` reads a URL into route data. `route-url` renders route data back into a URL. `match-url(route-url(...))` round-trips the canonical route data. Both functions are pure and JVM-runnable.

### `match-url`

- **Kind**: function
- **Signature**:
  ```clojure
  (match-url url) → {:route-id :params :query :fragment :validation-failed?} or nil
  ```
- **Description**: Match a URL to route data. Pure; JVM-runnable.

  - Returns `nil` when no route matches. Also fails closed to `nil` on malformed percent-encoding anywhere in the URL.
  - When the route declares `:params` / `:query` schemas and the parsed values fail them, `:validation-failed?` is `true` and the explanation rides under `:validation-error`.
  - Query keys the route declares (via `:query` / `:query-defaults` / `:query-retain`) come back as keyword keys, in a deterministic canonical order. Undeclared keys stay strings.
- **Example**:
  ```clojure
  ;; with (rf/reg-route :user/show {} "/users/:id") registered:
  (routing/match-url "/users/42")
  ;; => {:route-id :user/show, :params {:id "42"}, :query {},
  ;;     :fragment nil, :validation-failed? false}

  ;; nil when no route matches:
  (routing/match-url "/no/such/path")  ;; => nil
  ```

### `route-url`

- **Kind**: function
- **Signature**:
  ```clojure
  (route-url route-id path-params) → URL string
  (route-url route-id path-params query-params) → URL string
  (route-url route-id path-params query-params fragment) → URL string
  ```
- **Description**: Render a route to a URL — the inverse of `match-url`. Pure; JVM-runnable.

  - The 4-arity appends `#fragment` when `fragment` is a non-empty string (`nil` / `""` append nothing).
  - Nil-valued query keys are silently elided. A nil required path param is an error.
  - Query keys are emitted percent-encoded, in a deterministic canonical order.

  Throws:
  - `:rf.error/no-such-route` — `route-id` not registered.
  - `:rf.error/missing-route-param` — a required path segment's param is nil or absent.
  - `:rf.error/route-url-validation` — `path-params` / `query-params` fail the route's `:params` / `:query` schemas.
  - `:rf.error/route-url-non-edn-value` — non-EDN param/query values or a non-string fragment.
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
- **Description**: `true` when any percent-encoded portion of `url` is malformed — a non-empty path segment, a query key or value, or the `#fragment`. The check is purely lexical; no route table is consulted.

  The `:rf.route/transitioned` / `:rf.route/handle-url-change` handlers use it to tell two cases apart: a plain route miss (`{:url url}`) and a malformed URL that failed closed (`{:url url :reason :malformed-url}`). Both cases end at `:rf.route/not-found`. The structured `:reason` lets per-route error UIs and SSR projections branch on the cause.

### `current-url`

- **Kind**: function
- **Signature**:
  ```clojure
  (current-url) → app-relative URL string
  ```
- **Description**: Read the current browser URL as an app-relative string (`pathname + search + hash`). Returns `"/"` when no `window.location` is available (JVM / SSR / Node). This is the history strategy's `:decode`. A hash app's listener decodes through its own strategy and never calls this directly. It is public so apps that wire their own history listener can recover the same projection the framework's listener uses.

## Introspection and slice access

This is the read-side surface over the route registry and the live route slice. The static accessors answer "which routes are registered, and what is route X's spec?". The live readers expose the per-frame slice. The `*-algebra-view` helpers lower routes into the shared derivation/process-algebra node shape, so a tool can show subscriptions, flows, resources, route facts, and machine selectors as one family.

### `route-ids`

- **Kind**: function
- **Signature**:
  ```clojure
  (route-ids) → vector of route ids
  ```
- **Description**: Return a vector of every registered route id. This is the static-registry "enumerate" half of routing introspection; the live per-frame slice is read through the `:rf.route/*` subs instead. Mirrors the sibling accessors: `resource-ids` for resources, `machines` for machines.
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
- **Description**: Return the metadata map registered for `route-id`, or `nil` if no route is registered under that id. The map carries the `:path` pattern, `:on-match`, `:params`, `:query`, `:scroll`, and `:can-leave`. It also carries the computed `:rf.route/rank` / `:rf.route/compiled` / coercion tables and the source coords. Mirrors the sibling accessors: `resource-meta` for resources, `machine-meta` for machines.

### `route-algebra-view`

- **Kind**: function (JVM convenience alias; CLJS callers use `re-frame.routing.tooling/route-algebra-view`)
- **Signature**:
  ```clojure
  (route-algebra-view) → {route-id route-fact-node}
  (route-algebra-view route-id) → route-fact-node or nil
  ```
- **Description**: The STATIC derivation/process-algebra view of every registered route. It is pure data over the `:route` registrar kind — no app-db, no runtime-db, no live slice. The zero-arity form returns the map for every route (`{}` when none). The one-arity form returns one node, or `nil`. JVM-runnable; consumed by Xray and the conformance fixtures. There is no `re-frame.core` facade export.

  Each route lowers to a normalized node carrying:
  - `:id` `:rf/route` — the route FACT identity (every route materializes the one route slice; the per-route registration id is under `:source-form`).
  - `:kind` `:process`, `:refinement` `:route-fact`.
  - `:source-form` `{:kind :reg-route :id <route-id>}`.
  - `:inputs` — the route-transition inputs (`:rf.route/navigate` / `:rf.route/transitioned` / `:rf.route/handle-url-change`).
  - `:output` `[:runtime [:rf.runtime/routing :current]]`, `:storage` `:runtime-db`.
  - `:evaluation` `:on-route`, `:lifecycle` `:frame`, `:materialized?` `true`.
  - `:resource-edges` — only when the route declares `:resources`.

### `route-slice-algebra-view`

- **Kind**: function (JVM convenience alias; CLJS callers use `re-frame.routing.tooling/route-slice-algebra-view`)
- **Signature**:
  ```clojure
  (route-slice-algebra-view frame-id) → route-fact-node or nil
  ```
- **Description**: The LIVE counterpart to `route-algebra-view`. It reads the route fact materialized in a frame's runtime-db at `[:rf.runtime/routing :current]` — the concrete matched route, with its params, query, transition state, and nav-token. Returns a single `:route-fact` node. Returns `nil` when the frame is missing or destroyed, or when no navigation has committed yet (so no route has been materialized). The node carries the same fixed classifications as the static node, plus `:route-id`, `:params`, `:query`, `:transition`, `:nav-token`, and `:owner` `[:route <route-id> <nav-token>]`. Runs on both CLJS and the JVM (a single runtime-db container deref).

### `route-sub-fn`

- **Kind**: function
- **Signature**:
  ```clojure
  (route-sub-fn db query-v) → route slice
  ```
- **Description**: The layer-1 sub fn behind `:rf/route`. It reads the route slice from `[:rf.runtime/routing :current]`. Exposed publicly so external callers (smoke tests, tooling) can read the slice without re-deriving the path.

### Reading the route and the pending-nav slot

You read the route slice and the pending-navigation slot with ordinary `subscribe` calls naming their framework sub vectors. There is no named-read-sugar fn — a runtime-db framework read is a subscription vector, one grammar. Both are per-frame singletons, so no id argument is needed. To read an explicit frame (say, a non-default url-bound one), use `subscribe`'s `{:frame <target>}` opts form.

```clojure
(:route-id @(rf/subscribe [:rf/route]))   ;; the active route id, or nil pre-navigation

;; show an "unsaved changes?" prompt only while a navigation is blocked
(when-let [pending @(rf/subscribe [:rf/pending-navigation])]
  [confirm-leave-dialog pending])
```

The `:rf.route/*` granular subs (`:rf.route/id`, `:rf.route/params`, …) chain off `[:rf/route]`.

## Scroll restoration

Saved scroll positions live in a host-side, per-frame transient LRU cache keyed by frame-id. They are NOT runtime-db state. They are host-derived (read from `window.scrollX/Y`), meaningless server-side, and not needed to reconstitute a coherent frame on restore, SSR-hydration, or time-travel. So they never ride the trace / epoch / SSR egress wire, and they cannot rewind on an epoch restore. The pure helpers operate on a plain per-frame cache map `{:positions {url [x y]} :order [url ...]}`. The `!`-suffixed wrappers read and write the host cache.

### `scroll-positions-cap`

- **Kind**: value
- **Signature**:
  ```clojure
  scroll-positions-cap  ;; => 50
  ```
- **Description**: Soft upper bound on tracked URLs in the per-frame scroll-positions cache. It is large enough that real Back-button restoration hits saved positions, and small enough that the per-frame host cache stays bounded over long sessions.

### `frame-scroll-cache`

- **Kind**: function
- **Signature**:
  ```clojure
  (frame-scroll-cache frame-id) → {:positions :order} or nil
  ```
- **Description**: Read the per-frame cache map (`{:positions :order}`) for `frame-id` from the host scroll-position cache, or `nil` when none. This is the value threaded into the pure nav-planning seam.

### `lookup-scroll-position`

- **Kind**: function
- **Signature**:
  ```clojure
  (lookup-scroll-position cache url) → [x y] or nil
  ```
- **Description**: Pure. Return the saved `[x y]` for `url` in `cache`, or `nil` if none is saved. `cache` is a per-frame cache map `{:positions {url [x y]} :order [...]}`; it may itself be `nil`.

### `save-scroll-position`

- **Kind**: function
- **Signature**:
  ```clojure
  (save-scroll-position cache url xy) → cache'
  ```
- **Description**: Pure. Return `cache` with the scroll position for `url` recorded under `:positions`. The cache is LRU-capped at `scroll-positions-cap`. Re-saving an existing url promotes it to most-recent. A new save past the cap evicts the least-recently-used entry. The `:order` vector is the recency anchor.

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

The nav-token and pending-nav allocators are host-side, per-frame, monotonic high-water marks — not runtime-db state. An epoch restore replaces the runtime-db partition wholesale, so it cannot rewind these counters and recycle a token still carried by a slow in-flight continuation. `counter-snapshot` reads them. `routing-state-classification` is the canonical durable/transient map that SSR, docs, and schemas key off.

### `counter-snapshot`

- **Kind**: function
- **Signature**:
  ```clojure
  (counter-snapshot frame-id) → {:nav-token-counter N :pending-nav-counter M} or {}
  ```
- **Description**: Read the per-frame counter snapshot for `frame-id` from the host nav-counters cache, or `{}` when none. This is the value the allocation-cofx generators mint the next nav-token / pending-nav id from.

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
- **Description**: The canonical classification of every piece of per-frame routing state, by tier. Consumed by SSR, docs, and Spec-Schemas so the durable/transient split has one home.

  - `:durable-runtime-db` — serializable facts needed to reconstitute a coherent frame on restore / SSR-hydration; the route slice at `:current`.
  - `:local-subscribable-runtime-db` — runtime-db state that stays subscribable and restores in local replay but is SSR-stripped fail-closed; the `:pending-navigation` slot.
  - `:host-transient` — host-derived caches never in runtime-db; saved scroll positions and the two allocator high-water marks.

### `reset-nav-counters!`

- **Kind**: function
- **Signature**:
  ```clojure
  (reset-nav-counters!) → nil
  ```
- **Description**: Test-time helper. Drop the whole host nav-counters cache so a counter value does not leak across tests. Wired into the shared reset-runtime fixture via the `:routing/reset-nav-counters!` late-bind key.

## Multi-frame URL ownership

At most one frame owns the browser URL at a time. A frame claims ownership by registering with `{:url-bound? true}`. The resolver below names the current owner. The outbound `:rf.nav/push-url` fx and the inbound popstate listener both route through it — one owner, both directions.

### `url-owner-frame-id`

- **Kind**: function
- **Signature**:
  ```clojure
  (url-owner-frame-id) → frame-id or nil
  ```
- **Description**: Return the single frame that has explicitly declared browser-history ownership via `(rf/reg-frame :id {:url-bound? true})`, or `nil` when none has.

  - URL ownership is an explicit host/bootstrap policy, not an absence repair. The runtime never infers `:rf/default` as the owner. `:rf/default` owns the URL only when it carries an explicit `{:url-bound? true}`, like any other frame.
  - Ownership resolves to the first-claimed still-live `:url-bound? true` frame (the incumbent), so a later duplicate cannot steal the URL.
  - `nil` means no owner is declared. In that case outbound history fxs no-op and the inbound popstate listener skips.
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

## URL strategies

A `:url-strategy` is a frame-level config map declared on the URL-owning frame — `(rf/reg-frame :app {:url-bound? true :url-strategy routing/hash-url-strategy})`. The strategy is consulted at exactly four egress/ingress points: the two history fxs, the `route-link` href render, and the URL-listener install. `route-url`, `match-url`, and the navigation cascade stay pure and path-form. A strategy map carries `{:encode :decode :push! :replace! :install-listener!}`. The side-effecting keys (`:push!` / `:replace!` / `:install-listener!`) are present on CLJS only. SSR ignores strategies: the server emits path-form hrefs and takes the request URL via `:rf.route/handle-url-change`.

### `history-url-strategy`

- **Kind**: value
- **Signature**:
  ```clojure
  history-url-strategy  ;; {:encode :decode :push! :replace! :install-listener!}
  ```
- **Description**: The default URL strategy: HTML5 History, path-form. A frame that declares no `:url-strategy` uses this.

  - `:encode` / `:decode` are identity over the app-relative URL (`:decode` reads `pathname + search + hash`).
  - `:push!` / `:replace!` drive `pushState` / `replaceState`.
  - `:install-listener!` wires `popstate`.

### `hash-url-strategy`

- **Kind**: value
- **Signature**:
  ```clojure
  hash-url-strategy  ;; {:encode :decode :push! :replace! :install-listener!}
  ```
- **Description**: The hash URL strategy: `#`-prefixed URLs (`#/active`) for no-server-rewrite static hosting and secretary-era v1 migrations. `route-url` still builds path-form `/active`.

  - `:encode` maps it to `#/active` at the `route-link` href and the history fxs.
  - `:decode` strips the leading `#` from `window.location.hash` (an empty hash decodes to `/`).
  - `:install-listener!` wires `hashchange`.
- **Example**:
  ```clojure
  (rf/reg-frame :app {:url-bound?   true
                      :url-strategy routing/hash-url-strategy})
  ```

### `with-base-path`

- **Kind**: function
- **Signature**:
  ```clojure
  (with-base-path strategy base) → strategy-map
  ```
- **Description**: A STRATEGY COMBINATOR, not a third shipped strategy. Use it when an app is deployed under a sub-path — say a host mounting several demos side by side, so an app that would otherwise own `/` instead lives at `/realworld/`. It wraps `strategy` (either shipped strategy, or a custom one) so that `:encode` / `:decode` / `:push!` / `:replace!` / `:install-listener!` all account for `base`. The base is stripped off every inbound URL and re-added to every outbound one, underneath whichever address-bar form `strategy` already provides. `route-url` / `match-url` and the rest of the cascade stay path-form and base-agnostic. A blank or nil `base` returns `strategy` unchanged.
- **Example**:
  ```clojure
  (rf/reg-frame :app {:url-bound?   true
                      :url-strategy (routing/with-base-path
                                      routing/history-url-strategy
                                      "/realworld")})
  ```

## Browser URL listener

There is no imperative boot seam to call. The browser `popstate` / `hashchange` listener is wired automatically by the **`:url-bound?` frame LIFECYCLE** (rf2-g8pbwg). When a `:url-bound? true` frame is created (or re-registered) and resolves as the URL owner, that step installs the listener AND syncs the current URL into the owner's route slice. Destroying the frame removes the listener. The retired `install-url-listener!` / `remove-url-listener!` / `install-history-listener!` / `remove-history-listener!` exports are GONE (pre-alpha, no back-compat shim). There is nothing to call.

- Each browser-driven change is decoded to a path-form URL by the strategy's `:decode`, then dispatched synchronously as `:rf.route/handle-url-change` to `(url-owner-frame-id)`, resolved at fire time. When no frame declares `:url-bound? true`, the dispatch is skipped.
- The listener kind (`popstate` vs `hashchange`) is resolved from the URL-owning frame's `:url-strategy` at install time. The owner (the dispatch target) is re-resolved at every fire.
- Installation is idempotent. A re-registration that resolves as the owner tears down the prior listener before reinstalling. A losing duplicate `:url-bound? true` registration never installs.
- CLJS-only. On the JVM there is nothing to install; SSR feeds the request URL via `:rf.route/handle-url-change`.

## Route links

The `:route/link` registered view renders an `<a href=...>` from a route id. It intercepts plain left-clicks and turns them into `:rf.route/url-requested` dispatches. The authoring surface is also published as `rf/route-link` on the `re-frame.core` facade.

### `route-link`

- **Kind**: component (CLJS-only; the registered `:route/link` view)
- **Signature**:
  ```clojure
  [route-link {:to :route-id :params {...} :query {...} :fragment "..."
               :on-click f & html-attrs}
   & children]
  ```
- **Description**: The registered `:route/link` view.

  - `:to` is the only required key. `:params`, `:query`, and `:fragment` are forwarded to `route-url` for href synthesis. Every other props key passes through to the `<a>` element.
  - A plain primary-button click (no modifier keys, `defaultPrevented` false) is intercepted. The view calls `preventDefault`, then dispatches `[:rf.route/url-requested {:url ... :to ...}]` targeted at the frame that rendered the link.
  - Modifier-key / middle-button clicks, and anchors carrying native-handling attributes (`:target` other than `_self`, or `:download`), defer to the browser.
  - A caller-supplied `:on-click` runs first. If it calls `preventDefault`, the framework's interception is skipped.
  - The rendered href is encoded through the rendering frame's `:url-strategy`.
  - On the JVM the `:route/link` registration renders via [`route-link-render-ssr`](#route-link-render-ssr).
- **Example**:
  ```clojure
  [rf/route-link {:to :user/show :params {:id 42} :class "nav-item"}
   "Profile"]
  ```

### `route-link-render`

- **Kind**: function (CLJS-only)
- **Signature**:
  ```clojure
  (route-link-render props & children) → hiccup
  ```
- **Description**: The CLJS render fn behind `route-link`, exposed without the registry wrap so tests can call it directly. Same props contract and click rules as [`route-link`](#route-link). It must run inside a frame scope, and raises `:rf.error/no-frame-context` when rendered outside one. The render-time frame is captured so the click dispatch still targets it after the render scope has unwound.

## Server-side rendering

### `route-link-render-ssr`

- **Kind**: function
- **Signature**:
  ```clojure
  (route-link-render-ssr props & children) → hiccup
  ```
- **Description**: The JVM render fn for the `:route/link` view. It renders the `<a href=...>` shell without the click-interception logic. SSR has no DOM events to intercept, so the anchor is emitted as-is; clicks on the hydrated page run the CLJS render fn's on-click path. The href is emitted path-form regardless of `:url-strategy` (SSR ignores strategies), and the hydrated CLJS render re-encodes it. The authoring surface is [`route-link`](#route-link), also published on the `re-frame.core` facade.

## Keyword surfaces

The routing artefact registers a family of events, subscriptions, effects, and coeffects addressed by keyword. Loading `re-frame.routing` wires them all. It also registers internal machinery that apps and tools never dispatch or declare: the `:rf.route.internal/*` runtime events, the recordable allocation cofx (`:rf.route/nav-allocation`, `:rf.route/pending-nav-allocation`), and the `:rf.route/commit-nav-counter` fx. Those internals are omitted from the tables below.

### Events

Standard events the runtime dispatches (or you dispatch) around routing.

| Event | Notes |
|---|---|
| `:rf.route/navigate` | Navigate to a registered route. Arities: `[:rf.route/navigate target]` / `[:rf.route/navigate target params]` / `[:rf.route/navigate target params opts]`. `target` is a route id, the reserved `:rf.route/self` (stay on the current route, reshape only the query), or a `{:url "..."}` map. `params` (2nd slot) are path params. `opts` (3rd slot) recognises `:query`, `:query-merge`, `:replace?`, `:scroll`, `:fragment`, `:bypass-guards?`. |
| `:rf.route/handle-url-change` | URL-change handler for popstate / initial load / SSR (default scroll `:restore`). A co-equal sibling of `:rf.route/transitioned`: same slice-rewrite logic, not a delegate. Override it for custom URL-change handling. |
| `:rf.route/transitioned` | URL-change handler for forward navigation — a link click or programmatic push (default scroll `:top`). The runtime dispatches this; you read it. |
| `:rf.route/url-requested` | The user clicked a framework-owned link. `route-link` synthesises this event; you usually let the default handler take it. |
| `:rf.route/navigation-blocked` | A `:can-leave` (leave) guard rejected a navigation. The pending nav slot carries the rejected navigation (`:direction :leave`). |
| `:rf.route/entry-blocked` | A `:can-enter` (enter) guard rejected a navigation — the mirror of `:rf.route/navigation-blocked`. The pending nav slot carries the rejected entry (`:direction :enter`). The natural place to redirect (e.g. to login). |
| `:rf.route/continue` | User-dispatched event proceeding a blocked navigation — "yes, leave the page." Re-runs `:can-enter` on resume. |
| `:rf.route/cancel` | User-dispatched event abandoning a blocked navigation — "stay here, drop the pending nav." |

### Subscriptions

The full `:rf/route` slice is `{:route-id :params :query :fragment :transition :error :nav-token}`. The standard subs are projections of that slice plus a couple of conveniences.

| Sub | Returns |
|---|---|
| `:rf/route` | The full `:rf/route` slice `{:route-id :params :query :fragment :transition :error :nav-token}`. Read with `@(rf/subscribe [:rf/route])`. |
| `:rf.route/id` | Current route id (the slice's `:route-id`) |
| `:rf.route/params` | Current path params |
| `:rf.route/query` | Current query params |
| `:rf.route/transition` | `:idle` / `:loading` / `:error` |
| `:rf.route/error` | Current error map (when `:transition = :error`) |
| `:rf.route/fragment` | Current URL fragment (string or `nil`) |
| `:rf.route/chain` | Vector of route ids from parent-most to current (per `:parent` links) |
| `:rf/pending-navigation` | The pending-nav slot (per `:rf/pending-navigation` schema) when a navigation is blocked; `nil` otherwise. Read with `@(rf/subscribe [:rf/pending-navigation])`. |

### Effects (`fx`)

| Fx | Args | Platforms | Notes |
|---|---|---|---|
| `[:rf.nav/push-url url-string]` | URL string | `:client` | Push a new URL onto the browser history. |
| `[:rf.nav/replace-url url-string]` | URL string | `:client` | Replace the current URL without adding a history entry. |
| `[:rf.nav/scroll scroll-spec]` | scroll-spec map | `:client` | Restore or set scroll position. |
| `[:rf.nav/capture-scroll {:url url-string}]` | `{:url ...}` map | `:client` | Capture the current scroll position into the host-side per-frame scroll-position cache (keyed by `url`) before leaving a route. |
| `[:rf.route/with-nav-token {:rf/reply-to <reply-target> :nav-token <token>}]` | see notes | universal | Name an async-completion continuation by its canonical `:rf/reply-to` reply target and guard it with a navigation token. On a token match, the target is completed with the `:status :ok` reply map. If the token has been superseded by a later navigation, the completion is suppressed and `:rf.route.nav-token/stale-suppressed` fires. Optional args keys: `:route-id` (the captured route id, for the work-id), `:value` (rides the `:status :ok` reply map), `:completed-at`. |

The nav-token wrapper guards against "user navigates away mid-load". The older load's reply carries the stale token. The runtime suppresses it, so the older page's data does not overwrite the newer page's state. Full semantics in [Routing → Loaders](../routing/concepts.md#loaders-declaring-a-pages-data).

### Coeffects (`cofx`)

Declare these on a handler via `:rf.cofx/requires`. Each value is delivered flat under the cofx key in the coeffects map. Both are universal (client and server).

| Cofx | Delivers |
|---|---|
| `:rf.route/nav-token` | The current navigation epoch token, read from `[:rf.runtime/routing :current :nav-token]`. Declare `{:rf.cofx/requires [:rf.route/nav-token]}` on an `:on-match`-reached handler to capture the epoch live at scheduling time and thread it into an async continuation. `:rf.route/with-nav-token` validates it on receipt. |
| `:rf.route/route-id` | The current route id, read from `[:rf.runtime/routing :current :route-id]`. The capture-side companion of `:rf.route/nav-token`. Declare both (`{:rf.cofx/requires [:rf.route/nav-token :rf.route/route-id]}`) so the route-loader work-id `[:rf.work/route route-id nav-token loader-id]` carries its complete attempt identity. |

## See also

- [re-frame.core.md](re-frame.core.md) — the `re-frame.core` facade: the `reg-route` macro's brief row and the `route-link` view. The browser URL-change listener is NOT on the facade — it is wired automatically by the `:url-bound?` frame lifecycle (see Browser URL listener above).
- [re-frame.ssr.md](re-frame.ssr.md) — routes participate in SSR; the active route's `:head` registration is what `render-head` looks up.
- [Routing guide](../routing/index.md) — the narrative side: a [tutorial](../routing/tutorial.md), [concepts](../routing/concepts.md) (nav-token semantics, `:can-leave` flows, query strings, multi-frame routing), and how-to recipes.
- [Routing glossary](../routing/glossary.md) — the surface vocabulary (navigate, route, loader, route guard, not-found, url-bound?).
- [Coming from React Router](../routing/coming-from-react-router.md) — the mapping, and where re-frame2 routing diverges.
