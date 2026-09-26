# re-frame.routing

Routing maps URLs to named routes and back. You register each route as data, with an id, a metadata map and a path. Navigating is dispatching an event, and the active route is a subscription: it lives in the frame's `runtime-db` at `[:rf.runtime/routing :current]` and is read with `[:rf/route]`.

Routing ships in the optional `day8/re-frame2-routing` artefact. Require `re-frame.routing` once at boot; loading it registers every routing event, effect, coeffect and subscription, and the `:route/link` view. Without it, `rf/reg-route`, `rf/route-link` and `(rf/clear :route id)` throw `:rf.error/routing-artefact-missing`.

```clojure
(:require [re-frame.core    :as rf]
          [re-frame.routing :as rf.routing])
```

```clojure
(rf/reg-route :app/home    {} "/")
(rf/reg-route :app/article {} "/articles/:id")

(rf/reg-view root-view []
  (case @(subscribe [:rf.route/id])
    :app/home    [rf/route-link {:to :app/article :params {:id "intro"}} "Read intro"]
    :app/article [:h1 "Article " (:id @(subscribe [:rf.route/params]))]
    [:h1 "Not found"]))

;; Navigate from an event handler.
(rf/reg-event :article/open
  (fn [_ [_ id]]
    {:fx [[:dispatch [:rf.route/navigate {:to :app/article :params {:id id}}]]]}))

;; At the render root: this frame owns the browser's address bar.
[rf/frame-root {:id :app/main :url-bound? true} [root-view]]
```

`reg-route` and `route-link` are called on the `re-frame.core` facade. The URL helpers, URL strategies and test hooks are called on this namespace as `rf.routing/…`, and events, effects and subscriptions are addressed by keyword. Inside `reg-view`, `subscribe` and `dispatch` are bound without the `rf/` prefix.

The frame created with `:url-bound? true` owns the browser's address bar. Its navigations push browser history entries, Back and Forward navigate it, and on creation it reads the current URL, so a deep link or a reload lands on the right route. Leave the flag off for a frame that routes in memory only, such as a story, a test fixture or an embedded widget: its route slice still changes, but the address bar does not. See [Multi-frame URL ownership](#multi-frame-url-ownership).

The [Routing guide](../routing/index.md) teaches the model.

## Route registration

### `reg-route`

- **Kind**: macro (`rf/reg-route`); also a function, `rf.routing/reg-route`, which does not capture source coordinates
- **Signature**:
  ```clojure
  (reg-route id metadata path) → id
  ```
- **Description**: Registers a route. `id` is the keyword you navigate to (`[:rf.route/navigate {:to :route/cart}]`), `metadata` declares its match events, guards and schemas (keys below), and `path` is its URL pattern.
    - A pattern is built from literal segments (`/articles`), named params (`/:id`, captured into `:params` as a string unless the `:params` schema coerces it), optional groups with the slash inside the braces (`/articles/:id{/:slug}?`, or leading, as in `{/:lang}?/about`), and at most one splat, which must come last (`/files/*rest` matches one or more segments and captures them as one string, such as `"a/b.txt"`). The bare `/*` matches every URL. Trailing slashes are ignored when matching, and matching is case-sensitive.
    - When several routes match a URL, the most specific wins: more literal segments first, then any route over the bare `/*`, then more segments, then a named param over a splat, then a route without an optional group. A remaining tie goes to the earlier registration.
    - Emits `:rf.warning/route-shadowed-by-equal-score` when an existing route has an equal structural rank and the two patterns can match a common URL. `/a/:x` and `/a/:y` warn; `/x/:id` and `/y/:slug` tie in rank but never match the same URL, so they do not. The earlier registration wins at match time, so the new route is the shadowed one: the warning's tags name it under `:route-id`, the existing winner under `:shadowed-by`, and the tied structural tuple under `:rank`.
    - Emits the `:rf.route/registered` trace the first time an id is registered.
- **Options** (the `metadata` map; every key is optional):

    | Key | Notes |
    |---|---|
    | `:doc` | Free-form description, read by tools. |
    | `:params` | A Malli `[:map …]` schema for the path params. Captured strings are coerced to the declared type for `:int`, `:uuid`, `:boolean` and keyword `[:enum …]` slots; other types stay strings. When the schemas artefact is loaded the values are also validated, in every build: a URL that fails lands on [`:rf.route/not-found`](#not-found-route) with `:reason :validation`, and a `{:to …}` navigation that fails is rejected. `:double` and bare `:keyword` slots are refused at registration, because they cannot round-trip through a URL. |
    | `:query` | A Malli `[:map …]` schema for query-string keys, coerced and validated as for `:params`. Only keys declared here or in `:query-defaults` come back as keywords with typed values; any other key stays a string key with a string value. |
    | `:query-defaults` | Default values for absent query keys, filled in wherever a target is resolved, so a URL, a link, `{:to …}` and a prefetch all resolve the same `:query`. A key already at its default is left out of the URL and `match-url` fills it back, so each destination has one canonical URL. |
    | `:tags` | Free-form classification, e.g. `#{:auth-required :admin-only :public}`. |
    | `:parent` | Another route id. Builds the chain read by `:rf.route/chain`, and adds the ancestors' `:resources` to this route's plan, parent to leaf, with identical requirements deduplicated. Nothing else is inherited: not `:on-match`, `:scroll`, `:head`, `:tags` or the guards. |
    | `:on-match` | A vector of event vectors, dispatched in order each time a navigation commits this route with a new route id, params or query; an identical or fragment-only navigation does not re-fire it. A handler reads the new route from its `:rf.db/runtime` coeffect at `[:rf.runtime/routing :current]`. It only dispatches: it never moves `:rf.route/transition` or `:rf.route/error`, never waits for the work its events start, and never turns their failures into route state. A throwing handler reports through the ordinary event error channel. Use it for work such as analytics or seeding UI state, and declare a page's data in `:resources`. A single event is written `[[:app/load]]`; `[:app/load]` is refused at registration. |
    | `:can-leave` | A subscription id or query vector, subscribed before leaving the route with the pending target `{:route-id :params :query :fragment :url}` appended as its last element. `true` allows the navigation and `false` blocks it. Any other value also blocks, and emits `:rf.error/can-leave-non-boolean`. See [Routing → Blocking a navigation](../routing/concepts.md#blocking-a-navigation). |
    | `:can-enter` | The same shape, subscribed before entering the route, such as an auth check. `true` allows entry and `false` blocks it. Any other value also blocks, and emits `:rf.error/can-enter-non-boolean`. A rejection is final: nothing commits, no pending navigation is created, and the runtime dispatches `:rf.route/entry-denied` once. See [Routing → Guarding entry](../routing/concepts.md#guarding-entry--can-enter). |
    | `:scroll` | Where the page scrolls on entering the route: `:top` (to the element named by the `#fragment`, or the top of the page), `:restore` (the position saved for this URL), `:preserve` (no movement), or `false` (no scroll effect). Without it, a link click or `:rf.route/navigate` uses `:top`, and Back, Forward and the initial load use `:restore`. A `:rf.route/navigate` request's own `:scroll` overrides the route's. Any other value is rejected by the `:rf.nav/scroll` effect with `:rf.error/unsupported-scroll-strategy`. |
    | `:resources` | The page's server data, from the Resources artefact; without that artefact the key is rejected like any unknown key. A vector of requirement maps: `:resource` (the resource id), `:params` `(fn [route] params)` (omit it for a resource that takes none), `:blocking? true` to keep `:rf.route/transition` at `:loading` until the first load settles (also the SSR wait point), `:keep-previous?` to keep the previous params' data readable while the next loads, `:when` `(fn [route ctx] bool)` to include the entry conditionally, `:scope` to override the resource's registered scope, and a local `:id` plus `:after #{id}` to order the ensures. Entering the route ensures each resource with the route as owner, and leaving releases it. See [Routing → Declaring the data a page needs](../routing/concepts.md#declaring-the-data-a-page-needs). |
    | `:sensitive` | Slice paths, relative to the route projection (e.g. `[:query :token]`), redacted at egress while the route is active. A `[:query k]` path matches only when `k` is declared in `:query` or `:query-defaults`; otherwise registration warns with `:rf.warning/route-classification-query-key-unpromoted`. See [Routing → Keeping tokens off the wire](../routing/concepts.md#keeping-tokens-off-the-wire). |
    | `:large` | Slice paths replaced by a size marker at egress, so the value itself is not sent. |

    `:head`, SSR's head metadata, is also accepted unqualified, whether or not the SSR artefact is loaded. The registration-metadata key `:ns` is accepted too: it is not a routing key but names the registration's namespace for image selection, so a programmatic `reg-route` can set it and be selectable with `:select-ns`.

    The guide groups these keys by purpose in [Metadata keys](../routing/concepts.md#the-metadata-map-in-full).
- **Errors**:
    - `:rf.error/route-bad-metadata`: `metadata` is not a map, carries `:path` (the pattern goes in the third argument), carries an unqualified key outside the set above, or has an `:on-match` that is not a vector of event vectors (the error names the key under `:keys` and carries the value under `:value`). Namespaced keys such as `:myapp/analytics-id` are always accepted.
    - `:rf.error/invalid-route-pattern`: `path` breaks the pattern grammar, for example a missing leading `/`, an empty segment, a splat that is not last, or an optional group whose slash is outside the braces (`/{:lang}?/about`).
    - `:rf.error/route-decimal-unsupported`: a `:params` or `:query` slot is `:double`. Encode the value as a string, or use `:int`.
    - `:rf.error/route-keyword-unbounded-unsupported`: a `:params` or `:query` slot is a bare `:keyword`. Use a keyword `[:enum …]`, or `:string`.
    - `:rf.error/invalid-route-classification`: a `:sensitive` or `:large` path is malformed.
- **Example**:
  ```clojure
  ;; "/articles/42" matches with :params {:id 42}.
  (rf/reg-route :app/article
    {:params [:map [:id :int]]}
    "/articles/:id")

  ;; A typed query with a default, and an entry guard.
  (rf/reg-sub :auth/signed-in?
    (fn [db _] (some? (:user db))))

  (rf/reg-route :app/search
    {:query          [:map [:q {:optional true} :string]
                           [:page {:optional true} :int]]
     :query-defaults {:page 1}
     :can-enter      [:auth/signed-in?]}
    "/search")

  ;; Page data through the Resources artefact (:cart/items is a registered resource).
  (rf/reg-route :app/cart
    {:resources [{:resource :cart/items :blocking? true}]}
    "/cart")
  ```

### Not-found route

Register a route under the reserved id `:rf.route/not-found` to render a page for URLs that do not resolve. Its path is only a placeholder. A link click, Back or Forward, the initial load or SSR commits it for any URL that does not resolve to a route, and so does `[:rf.route/navigate {:url …}]` for a URL no route matches. The requested URL is in `:params`:

| `:params` | Cause |
|---|---|
| `{:url url}` | No route matched. |
| `{:url url :reason :validation}` | A pattern matched, but the route's `:params` or `:query` schema rejected the values. |
| `{:url url :reason :malformed-url}` | The URL has malformed percent-encoding. |
| `{:url url :reason :match-error}` | Matching the URL threw. |

A URL-driven miss also reports `:rf.error/no-such-handler` (`:kind :route`) on the always-on `:errors` stream, which SSR answers with a 404. A malformed URL, or one whose matching threw, also emits `:rf.warning/malformed-url` on the development trace stream, from a URL-driven change and from `[:rf.route/navigate {:url …}]` alike. Its tags carry the URL under `:url`, with query and fragment values redacted, and `:reason :match-error` when matching threw. When no `:rf.route/not-found` route is registered, the slice still takes that id and the runtime emits `:rf.warning/no-not-found-route`. A `{:to …}` navigation never lands here: an unregistered route or a failing param rejects it instead (see [`:rf.route/navigate`](#rfroutenavigate-request)).

```clojure
(rf/reg-route :rf.route/not-found {} "/404")

;; Rendered from the root view's case as  :rf.route/not-found [not-found-page]
(rf/reg-view not-found-page []
  [:h1 "No page at " (:url @(subscribe [:rf.route/params]))])
```

### Clearing a route

- **Signature**:
  ```clojure
  (rf/clear :route id) → id
  ```
- **Description**: Removes a registered route and emits the `:rf.route/cleared` trace, so tools that follow route registrations see the removal. Does nothing when `id` is not registered. There is no `clear-route` function on either namespace; `:route` is one of the kinds [`clear`](re-frame.core.md#clear) removes.

## Route links

### `route-link`

- **Kind**: component (`rf/route-link`, the registered `:route/link` view; there is no `re-frame.routing/route-link` var)
- **Signature**:
  ```clojure
  [route-link {:to :route-id :params {...} :query {...} :fragment "..."
               :replace? ... :scroll ... :bypass-leave? ...
               :prefetch :intent :on-click f & html-attrs}
   & children]
  ```
- **Description**: Renders an `<a href=...>` for a route and turns a plain left-click into navigation.
    - `:to` is the only required key. `:params`, `:query` and `:fragment` are passed to `route-url` to build the href. `:replace?`, `:scroll` and `:bypass-leave?` apply to the navigation the click makes, as they do on [`:rf.route/navigate`](#rfroutenavigate-request). Every other key except `:prefetch` passes through to the `<a>`, including `:class` and `:aria-current`. `route-link` computes no active state: to style the active link, compare `:to` with `:rf.route/id` (or `:rf.route/chain`) in your own view. See [Routing → Highlighting the active link](../routing/concepts.md#highlighting-the-active-link).
    - A plain primary-button click (no modifier keys, `defaultPrevented` false) is intercepted: the view calls `preventDefault`, then dispatches `[:rf.route/url-requested {:url ...}]` to the frame that rendered the link, with any of the link's `:replace?`, `:scroll` and `:bypass-leave?` beside `:url`. The URL is the whole address, and the handler derives the route from it.
    - Modifier-key and middle-button clicks, and anchors with `:target` other than `_self` or with `:download`, are left to the browser.
    - A caller-supplied `:on-click` runs first. If it calls `preventDefault`, the link does not intercept the click.
    - `:prefetch :intent` dispatches [`:rf.route/prefetch`](#rfrouteprefetch-address) with the link's own address on hover, focus or touch. The address leaves out `:fragment`, which is never a resource input. Caller-supplied `:on-mouse-enter`, `:on-focus` and `:on-touch-start` handlers still run alongside.
    - `:intent` is the only accepted `:prefetch` value, and leaving the key out is the only way to opt out. The intent handlers exist only in ClojureScript (SSR renders the anchor without them), but the value is validated on both hosts, so the server never accepts a value the client rejects.
    - `re-frame.fresco/route-link` accepts the same `:prefetch` key, but throws `:rf.error/fresco-route-link-claimed-intent-position` if the link also supplies one of those three handlers. See [Fresco → Prefetch on user intent](../core/fresco/07-routing-and-navigation.md#prefetch-on-user-intent).
    - The href is encoded through the rendering frame's `:url-strategy` on both hosts, so the server-rendered link and the hydrated client agree. On the JVM the view renders with [`route-link-render-ssr`](#route-link-render-ssr).
- **Errors**: each is thrown at the render site, in this order.
    - `:rf.error/no-frame-context`: in ClojureScript, the link renders outside any frame. It captures its frame at render so the click dispatches there, so render it under a `frame-root` or `frame-provider`. On the JVM the link reads the frame without requiring one, and outside any frame it renders the history-strategy href.
    - `:rf.error/route-link-bad-prefetch`: `:prefetch` is present with any value other than `:intent`, including `true`, `false` and `nil`. It is checked before the route lookup, so a mistyped `:to` does not hide it.
    - `route-url`'s errors, from building the href: `:rf.error/no-such-route`, `:rf.error/missing-route-param`, `:rf.error/route-url-validation` and `:rf.error/route-url-non-edn-value`. See [`route-url`](#route-url).
- **Example**:
  ```clojure
  [rf/route-link {:to :user/show :params {:id 42} :class "nav-item"}
   "Profile"]
  ```

## Keyword surfaces

Loading `re-frame.routing` registers these events, subscriptions, effects and coeffects. It also registers some internal ones that apps and tools never use directly, which this section leaves out: the `:rf.route/nav-allocation` and `:rf.route/pending-nav-allocation` coeffects and the `:rf.route/commit-nav-counter` effect. The `:rf.route.internal/*` event namespace is reserved for the runtime and has no members.

### Events

Applications dispatch `:rf.route/navigate`, `:rf.route/continue`, `:rf.route/cancel`, `:rf.route/prefetch` and `:rf.route/replan-resources`. `route-link` dispatches `:rf.route/url-requested`, and the runtime dispatches the other three; an SSR app also dispatches `:rf.route/handle-url-change` with the request URL.

`:rf.route/navigation-blocked` and `:rf.route/entry-denied` are the two events an application registers its own handler for. Registering a handler under any other routing event id makes frame creation throw `:rf.error/image-duplicate-id`.

#### `[:rf.route/navigate {request}]`

- **Kind**: event
- **Payload**: one request map. Address keys: `:to` (a route id), `:url` (a URL inside the app), `:params`, `:query`, `:fragment`. Policy keys: `:replace?`, `:scroll`, `:bypass-leave?`. Edit key: `:query-merge`.
- **Description**: Navigates the frame the event is dispatched to.
    - With `:to`, the target is built from `:params`, `:query` and `:fragment` alone, and the route's `:query-defaults` fill absent query keys. Nothing carries over from the current route.
    - With `:url`, the URL is matched as a link click's would be, which suits a URL from a notification or a server redirect. `:fragment` beside it replaces the URL's own. A URL no route matches commits the [not-found route](#not-found-route). An external URL is never followed: the request does nothing and emits the `:rf.route/external-url-requested` trace.
    - With neither, the request edits the current location in place. `:query` replaces the whole query, `:query-merge` merges into it (a `nil` value removes that key), and `:fragment` replaces the fragment. Path params cannot change in place, because new params are a new destination.
    - `:replace? true` replaces the current history entry instead of pushing one. `:scroll` overrides the target route's [`:scroll`](#reg-route) for this navigation. `:bypass-leave? true` skips the current route's `:can-leave` guard once; the target's `:can-enter` still runs.
    - A request identical to the current location does nothing and runs no guards. Any other request runs the current route's `:can-leave` guard, then the target's `:can-enter`, and a `false` from either ends it (see [`:rf.route/navigation-blocked`](#rfroutenavigation-blocked-pending) and [`:rf.route/entry-denied`](#rfrouteentry-denied-denial)).
    - A request that changes only the fragment then updates `:fragment`, pushes the URL and scrolls. It keeps the navigation token and does not re-run `:on-match` or the resource plan.
    - Any other allowed request commits: the route slice is written, the URL pushed or replaced, `:on-match` dispatched, the resource plan run and the scroll applied.
- **Errors**: both leave the route slice unchanged and push nothing.
    - `:rf.error/schema-validation-failure` (`:where :event`): `route-url` cannot build the target, because `:to` is not registered, a path param is missing, or the route's schemas reject the params or query. `route-url`'s error is under `:error`, elided when the route's schema marks a slot `:sensitive?`.
    - `:rf.error/navigate-bad-request`: the request breaks one of the rules below, checked before any guard runs. `:reason` names the rule, and `:keys` the offending keys.

    <a id="navigate-request-rules"></a>

    | `:reason` | Rule |
    |---|---|
    | `:bad-event-arity` | The event is exactly `[:rf.route/navigate {request}]`, with no third element such as a separate opts map. |
    | `:request-not-a-map` | The request is a map. |
    | `:unknown-keys` | Every key is one of the address, policy and edit keys above. A namespaced key is refused too. |
    | `:to-url-exclusive` | `:to` or `:url`, not both. |
    | `:url-excludes-address` | `:url` takes no `:params`, `:query` or `:query-merge`. `:fragment` is allowed and replaces the URL's own. |
    | `:params-requires-destination` | `:params` needs `:to`: changing path params is a new destination, never an in-place edit. |
    | `:query-exclusive` | `:query` or `:query-merge`, not both. |
    | `:query-merge-in-place-only` | `:query-merge` needs a request with no `:to` or `:url`. |
    | `:query-merge-not-map` | `:query-merge` is a map. `{}` is a no-op and a `nil` inside it removes a key, but a `nil` or other non-map value for `:query-merge` itself is refused. |
    | `:no-destination-or-change` | The request names a destination or an in-place change. `{}` and a policy-only map such as `{:replace? true}` are refused; `{:query {}}` and `{:fragment nil}` are valid. |
    | `:no-current-route` | An in-place request needs a current route to edit. |
- **Example**:
  ```clojure
  [:rf.route/navigate {:to :app/article :params {:id "intro"}}]   ;; a named route
  [:rf.route/navigate {:to :app/home :replace? true}]             ;; no new history entry
  [:rf.route/navigate {:url "/articles/intro?tab=comments"}]      ;; a URL, e.g. from a notification
  [:rf.route/navigate {:query-merge {:page 2 :filter nil}}]       ;; same route, edit the query
  ```

#### `[:rf.route/url-requested {:url url}]`

- **Kind**: event
- **Payload**: `{:url url}`, plus any of the policy keys `:replace?`, `:scroll` and `:bypass-leave?`, with the meanings they have on `:rf.route/navigate`.
- **Description**: A click on a framework link. [`route-link`](#route-link) dispatches it with the link's URL, and the default handler is the one to keep.
    - An external URL does nothing here and emits the `:rf.route/external-url-requested` trace.
    - A URL that resolves to the current location does nothing and runs no guards.
    - Otherwise the guards run before the address bar moves, so a blocked or denied click adds no history entry. When both allow, the handler pushes the URL (or replaces it, for `:replace? true`) and dispatches `:rf.route/handle-url-change` with cause `:link`, which commits the route.

#### `[:rf.route/handle-url-change url opts?]`

- **Kind**: event
- **Payload**: `url`, an app URL such as `"/articles/intro?tab=comments"`, and an optional opts map.
- **Description**: Commits the route for a URL the address bar already shows. The runtime dispatches it after a link click, on Back and Forward, and for the current URL when a `:url-bound? true` frame takes ownership. On the server, dispatch it with the request URL, as in [SSR → Reading the request](../ssr/concepts.md#reading-the-request).
    - The opts map's `:rf.route/cause` says why the URL changed: `:link`, `:popstate`, `:initial` or `:ssr`. The runtime sets it on its own dispatches. Without it the cause is `:ssr` on a `:platform :server` frame and `:initial` otherwise. Scroll defaults to `:top` for `:link` and `:restore` for every other cause.
    - `:bypass-leave? true` on the opts map skips the current route's `:can-leave` guard once.
    - This event never rejects a URL. One that no route matches, whose values fail the route's schemas, or with malformed percent-encoding commits the [not-found route](#not-found-route).
    - A URL identical to the current location does nothing. Otherwise the guards run as for `:rf.route/navigate`. The address bar has already moved, so a blocked or denied change puts the current route's URL back by replacing it.
    - A change to the fragment alone updates `:fragment` without a new navigation token or a re-run of `:on-match`.
- **Example**:
  ```clojure
  ;; The server's per-request setup event hands the request URL to routing.
  [:rf.route/handle-url-change "/articles/intro"]

  ;; A test standing in for the Back button.
  (rf/dispatch-sync [:rf.route/handle-url-change "/articles/intro" {:rf.route/cause :popstate}])
  ```

#### `[:rf.route/navigation-blocked pending]`

- **Kind**: event, dispatched by the runtime
- **Payload**: `pending`, the value the runtime stores in the pending-navigation slot:

    | Key | Value |
    |---|---|
    | `:id` | The pending-navigation id that `:rf.route/continue` and `:rf.route/cancel` take. |
    | `:destination` | Where the user was going, as a request `:rf.route/navigate` accepts: `{:to :params :query :fragment}` for a registered route, `{:url …}` for a URL no route matches. |
    | `:target` | The resolved target, `{:route-id :params :query :fragment :url}`. |
    | `:cause` | `:link`, `:navigate`, `:popstate`, `:initial` or `:ssr`. |
    | `:policy` | The request's `:replace?` and `:scroll`, or `{}`. |
    | `:requested-url` | The URL that was requested. |
    | `:rejecting-route` | The current route's id. |
    | `:rejecting-guard` | The id of the `:can-leave` subscription that returned `false`. |
    | `:url-restored?` | Present and `true` when the runtime put the address bar back, after a URL-driven change. |

- **Description**: Dispatched once when the current route's `:can-leave` guard returns `false`. The route stays where it is, and the pending value is readable from [`:rf/pending-navigation`](#subscriptions) until `:rf.route/continue` or `:rf.route/cancel` clears it. The slot holds one value, and a later block replaces it. The default handler does nothing; register your own to react, for example by opening a confirm dialog. The payload's `:requested-url`, `:destination` and `:target` are redacted in traces, with or without your handler.
- **Example**:
  ```clojure
  (rf/reg-event :rf.route/navigation-blocked
    (fn [{:keys [db]} [_ pending]]
      {:db (assoc db :ui/confirm-leave (:id pending))}))
  ```

#### `[:rf.route/entry-denied denial]`

- **Kind**: event, dispatched by the runtime
- **Payload**: `denial`, `{:destination :target :cause :requested-url :guard}`. The first four are as in [`:rf.route/navigation-blocked`](#rfroutenavigation-blocked-pending); `:guard` is the id of the target's `:can-enter` subscription.
- **Description**: Dispatched once when the target route's `:can-enter` guard returns `false`. The denial is final: nothing commits, nothing is stored and there is nothing to continue.
    - After a URL-driven change the runtime puts the current route's URL back.
    - On a server frame the runtime sets the response status to 403 before dispatching, and your handler can replace it with `:rf.server/redirect` or `:rf.server/set-status`.
    - The default handler does nothing. Register your own to redirect, for example to a sign-in page. After sign-in, dispatch a fresh `[:rf.route/navigate destination]`, and the guard runs again. See [Routing → Guarding entry](../routing/concepts.md#guarding-entry--can-enter).
- **Example**:
  ```clojure
  (rf/reg-event :rf.route/entry-denied
    (fn [{:keys [db]} [_ {:keys [destination]}]]
      {:db (assoc db :auth/return-to destination)
       :fx [[:dispatch [:rf.route/navigate {:to :app/sign-in :replace? true}]]]}))
  ```

#### `[:rf.route/continue pending-nav-id]`

- **Kind**: event
- **Payload**: `pending-nav-id`, the pending value's `:id`.
- **Description**: Goes ahead with a blocked navigation ("yes, leave the page"). Clears the pending slot and replays its `:destination` and `:policy` through `:rf.route/navigate` with a one-shot `:bypass-leave? true`, so the target's `:can-enter` still runs. When the address bar was put back (`:url-restored?`), the replay replaces the history entry instead of pushing one. An id that does not match the pending value does nothing.

#### `[:rf.route/cancel pending-nav-id]`

- **Kind**: event
- **Payload**: `pending-nav-id`, the pending value's `:id`.
- **Description**: Abandons a blocked navigation ("stay here") and clears the pending slot. The route and the URL stay as they are. An id that does not match the pending value does nothing.

#### `[:rf.route/prefetch {address}]`

- **Kind**: event
- **Payload**: a named address, `{:to :params :query}`. `:fragment` is accepted and plays no part, because it is never a resource input; `:url` is refused.
- **Description**: Warms a destination's resource plan without navigating. It runs the parent-to-leaf plan a navigation would, in warm mode: every ensure is ownerless and `:blocking?` has no effect. No route state, guards or `:on-match` run, and the warm-up stays in the frame that dispatched it. Without the resources artefact, or with an empty plan, it only emits its `:rf.route/prefetched` summary trace. `route-link`'s `:prefetch :intent` dispatches it.
- **Errors**: `:rf.error/prefetch-bad-address`, before planning, when the address is malformed or does not resolve (an unregistered `:to`, a missing path param, or values the route's schemas reject). `:reason` names the failure, such as `:no-such-route` or `:missing-route-param`.
- **Example**:
  ```clojure
  [:rf.route/prefetch {:to :app/article :params {:id "intro"}}]
  ```

#### `[:rf.route/replan-resources {:cause cause}]`

- **Kind**: event
- **Payload**: `{:cause cause}`. `:cause` is required and must not be `nil`.
- **Description**: Reruns the active route's resource plan against the current `app-db` without navigating. Use it when an identity input (principal, tenant, locale) changed with no route change: a `{:from-db …}` subscription re-keys on its own but stays `:idle` until something ensures the new key.
    - It keeps the same navigation token, owner and planner. Kept identities are adopted with no fetch, added ones are ensured under the route owner with your `:cause`, and dropped ones lose the owner. The plan, the blocking facts and readiness are replaced, so a successful replan clears an earlier `:rf.error/resource-route-plan`.
    - A planning failure commits as a failed replan: nothing is partly ensured, and the owner is released from every earlier identity.
    - It is not a reload: unchanged data is never refetched, and no guards, `:on-match`, URL, history or scroll work runs. Without the resources artefact it does nothing.
- **Errors**: `:rf.error/replan-bad-request`, before planning, for a malformed payload or a dispatch with no active route.
- **Example**:
  ```clojure
  [:rf.route/replan-resources {:cause [:session-restore]}]
  ```

### Subscriptions

Read the route and the pending-navigation slot with ordinary `subscribe` calls. Each frame has its own route, and a subscription reads the frame it runs in, so the query vectors carry no frame argument. To read another frame, pass `subscribe`'s `{:frame <target>}` opts.

```clojure
(:route-id @(rf/subscribe [:rf/route]))   ;; the active route id, or nil before the first navigation

;; Show an "unsaved changes?" prompt only while a navigation is blocked.
(when-let [pending @(rf/subscribe [:rf/pending-navigation])]
  [confirm-leave-dialog pending])
```

| Sub | Returns |
|---|---|
| `:rf/route` | The route slice `{:route-id :params :query :fragment :transition :error :nav-token}`. The `:rf.route/*` subscriptions below are projections of it. |
| `:rf.route/id` | The current route id (the slice's `:route-id`). |
| `:rf.route/params` | The current path params. |
| `:rf.route/query` | The current query params. |
| `:rf.route/transition` | `:idle`, `:loading` or `:error`, derived from the blocking `:resources` in the route's plan. `:loading` while a blocking first load is pending; `:error` on a blocking first-load failure or a plan that could not be built; `:idle` otherwise, and always when the resources artefact is not loaded. A background refresh, a non-blocking read, an intent prefetch and `:on-match` never move it. |
| `:rf.route/error` | When `:transition` is `:error`, the structured failure: `:rf.error/resource-route-blocking` for a blocking first-load failure, `:rf.error/resource-route-plan` for a plan that could not be built. Otherwise `nil`. |
| `:rf.route/fragment` | The current URL fragment, a string or `nil`. |
| `:rf.route/chain` | A vector of route ids from the outermost parent to the current route, following `:parent`. |
| `:rf/pending-navigation` | The pending-navigation slot (shaped by the `:rf/pending-navigation` schema) while a `:can-leave` guard holds a navigation, otherwise `nil`. A denied entry is final and never appears here. |

### Effects (`fx`)

| Fx | Args | Platforms | Notes |
|---|---|---|---|
| `[:rf.nav/push-url url-string]` | URL string | `:client` | Pushes a new URL onto the browser history. |
| `[:rf.nav/replace-url url-string]` | URL string | `:client` | Replaces the current URL without adding a history entry. |
| `[:rf.nav/scroll scroll-spec]` | `{:strategy :from :to :saved-pos :fragment}` | `:client` | Restores or sets the scroll position after the new route renders. `:strategy` is `:top`, `:restore` or `:preserve`; any other value emits `:rf.error/unsupported-scroll-strategy`. Navigation emits this effect for you from the route's `:scroll`. |
| `[:rf.nav/capture-scroll {:url url-string}]` | `{:url ...}` map | `:client` | Saves the current scroll position in the frame's scroll cache under `url`, before leaving a route. |
| `[:rf.route/with-nav-token {:rf/reply-to <reply-target> :nav-token <token>}]` | see notes | universal | Completes an async continuation, named by its `:rf/reply-to` reply target, only if its navigation token is still current. On a match, the target is completed with the `:status :ok` reply map. If a later navigation has superseded the token, the completion is suppressed and `:rf.route.nav-token/stale-suppressed` fires. Optional keys: `:route-id` (the captured route id, for the work-id), `:value` (carried in the `:status :ok` reply map), `:completed-at`. |

`:rf.route/with-nav-token` handles a user navigating away mid-load: the older load's reply carries a stale token, so the runtime suppresses it and the older page's data does not overwrite the newer page's state. See [Routing → Activation work and page data](../routing/concepts.md#loaders-declaring-a-pages-data).

### Coeffects (`cofx`)

Declare these on a handler with `:rf.cofx/requires`. Each value is delivered under its cofx key in the coeffects map. Both work on client and server.

| Cofx | Delivers |
|---|---|
| `:rf.route/nav-token` | The current navigation token, from `[:rf.runtime/routing :current :nav-token]`. Declare `{:rf.cofx/requires [:rf.route/nav-token]}` on a handler reached from `:on-match` to capture the token when the work is scheduled and pass it to an async continuation; `:rf.route/with-nav-token` checks it on receipt. |
| `:rf.route/route-id` | The current route id, from `[:rf.runtime/routing :current :route-id]`. Declare it with `:rf.route/nav-token` (`{:rf.cofx/requires [:rf.route/nav-token :rf.route/route-id]}`) so the route-loader work-id `[:rf.work/route route-id nav-token loader-id]` identifies the attempt completely. |

## URL and route matching

`match-url` reads a URL into route data, and `route-url` renders route data back into a URL; `match-url` of a `route-url` result gives back the canonical route data. Both are pure and run on the JVM.

### `match-url`

- **Kind**: function
- **Signature**:
  ```clojure
  (match-url url) → {:route-id :params :query :fragment :validation-failed?} or nil
  ```
- **Description**: Matches a URL against the registered routes and returns the route data.
    - Returns `nil` when no route matches, and when any part of the URL has malformed percent-encoding.
    - Path params and declared query keys come back coerced by the route's schemas. When the coerced values fail those schemas, `:validation-failed?` is `true` and the explanation is under `:validation-error`; this check runs only when the schemas artefact is loaded.
    - Query keys the route declares (in `:query` or `:query-defaults`) come back as keywords, in a deterministic canonical order. Undeclared keys stay strings.
- **Example**:
  ```clojure
  ;; with (rf/reg-route :user/show {} "/users/:id") registered:
  (rf.routing/match-url "/users/42")
  ;; => {:route-id :user/show, :params {:id "42"}, :query {},
  ;;     :fragment nil, :validation-failed? false}

  ;; nil when no route matches:
  (rf.routing/match-url "/no/such/path")  ;; => nil
  ```

### `route-url`

- **Kind**: function
- **Signature**:
  ```clojure
  (route-url {:to route-id :params path-params :query query-params :fragment fragment}) → URL string
  ```
- **Description**: Builds the URL for a route, the inverse of `match-url`, from one address map.
    - `:to` is the only required key. Requests name the route with `:to`; results such as `match-url` and the route slice name it `:route-id`.
    - `:fragment` appends `#fragment` when it is a non-empty string; `nil` and `""` append nothing.
    - Query keys with `nil` values are left out. A `nil` required path param is an error.
    - A query key already at the route's `:query-defaults` value is left out, because `match-url` fills it back and spelling it would give one destination two URLs. Validation still runs against the full query you passed.
    - Query keys are percent-encoded, in a deterministic canonical order.
    - It takes an address only, and there is no in-place form, because a pure function cannot read the current route.
- **Errors**:
    - `:rf.error/no-such-route`: the `:to` route is not registered.
    - `:rf.error/missing-route-param`: a required path segment's param is `nil`, absent or `""`. An empty segment would be dropped when the URL is matched, so it cannot round-trip.
    - `:rf.error/route-url-validation`, for any of these:
        - the address is not a map (`:reason :not-a-map`), or has no `:to` (`:reason :missing-to`);
        - the map carries a non-address key such as `:url`, `:query-merge`, `:replace?`, `:scroll`, `:bypass-leave?` or an unknown key (`:reason :bad-address-keys`);
        - `:params` carries a key the route's pattern does not capture (`:reason :uncaptured-params`);
        - `:params` or `:query` fail the route's schemas;
        - `:params` fills a later optional group while an earlier one is left out. Sequential optional groups are filled in order, or `match-url` would read the value into the earlier group.
    - `:rf.error/route-url-non-edn-value`: a param or query value has no portable EDN form (a function, atom or other host object, a fractional number, or an integer too large for both hosts to hold exactly), or is an instant or `Date`; or the fragment is neither a string nor `nil`.
- **Example**:
  ```clojure
  ;; with (rf/reg-route :user/show {} "/users/:id") registered:
  (rf.routing/route-url {:to :user/show :params {:id 42}})        ;; => "/users/42"

  ;; with (rf/reg-route :search {} "/search") registered,
  ;; query params are appended and percent-encoded:
  (rf.routing/route-url {:to :search :query {:q "hello world"}})  ;; => "/search?q=hello%20world"
  ```

### `malformed-url?`

- **Kind**: function
- **Signature**:
  ```clojure
  (malformed-url? url) → boolean
  ```
- **Description**: Returns `true` when any percent-encoded part of `url` is malformed: a non-empty path segment, a query key or value, or the `#fragment`. The check is lexical and consults no routes.
    - `:rf.route/handle-url-change` uses it to tell a plain route miss (`{:url url}`) from a malformed URL that failed closed (`{:url url :reason :malformed-url}`). Both end at `:rf.route/not-found`; the `:reason` lets error pages and SSR branch on the cause.

## Querying registered routes

There is no `route-ids` or `route-meta` function. Use the generic registrar queries, [`registrations`](re-frame.core.md#registrations) and [`handler-meta`](re-frame.core.md#handler-meta):

```clojure
(keys (rf/registrations {:source :store :kind :route}))
;; => (:route/cart :user/show)

(rf/handler-meta {:source :store :kind :route :id :route/cart})
;; => the registered metadata map, or nil
```

The returned map holds the `:path` pattern and everything the registration declared (see [`reg-route`](#reg-route)'s options), plus the computed `:rf.route/rank`, `:rf.route/compiled` and coercion tables and the source coordinates. Unlike a resource, mutation or resource-scope registration, it keeps this metadata at the top level rather than under an inner key.

## URL strategies

A URL strategy decides how the app's path-form URLs (`/active`) appear in the browser's address bar. Declare one on the URL-owning frame with the `:url-strategy` config key, as in the examples below. A frame without one uses `history-url-strategy`.

Choosing one:

- Keep the default `history-url-strategy` when your server answers every app path with the app's page, so reloading `/articles/intro` works. The URLs are the plain paths.
- Use `hash-url-strategy` when it cannot, as on a static host without rewrite rules: the route lives after the `#`, so the server only ever serves the page itself.
- Wrap either with `with-base-path` when the app is not served from the site root, for example under `/realworld/`.

A strategy is a map of five functions, `{:encode :decode :push! :replace! :install-listener!}`; [A custom strategy](#a-custom-strategy) gives each one's contract. It is consulted at four points: the two history effects, the `route-link` href, and decoding an incoming URL (the URL listener, and a `{:url …}` or `:rf.route/url-requested` URL that carries an origin). `route-url`, `match-url` and navigation itself always work in path form.

`:push!`, `:replace!` and `:install-listener!` exist only in ClojureScript. SSR runs none of them, because the server reads the request URL through `:rf.route/handle-url-change` and has no history. It does apply `:encode`, so a server-rendered `route-link` has the same href as the hydrated client: `/demos/active` for a `with-base-path` frame, `#/active` for a hash frame.

### `history-url-strategy`

- **Kind**: var
- **Signature**:
  ```clojure
  history-url-strategy  ;; {:encode :decode :push! :replace! :install-listener!}
  ```
- **Description**: The default strategy: HTML5 History with path-form URLs.
    - `:encode` and `:decode` are identity over the app-relative URL. `:decode` is `(fn [href] path)`, pure, and receives the origin-relative browser address (`pathname + search + hash`).
    - `:push!` and `:replace!` call `pushState` and `replaceState`.
    - `:install-listener!` listens for `popstate`.

### `hash-url-strategy`

- **Kind**: var
- **Signature**:
  ```clojure
  hash-url-strategy  ;; {:encode :decode :push! :replace! :install-listener!}
  ```
- **Description**: Puts the route in the URL fragment (`#/active`), for static hosting without server rewrites and for apps coming from hash-based routing such as secretary. `route-url` still builds the path form `/active`.
    - `:encode` turns it into `#/active` for the `route-link` href and the history effects.
    - `:decode` takes the origin-relative browser address and returns what follows its first `#`; a missing or empty fragment decodes to `/`. It is pure: the listener reads `window.location` and passes the address in.
    - `:install-listener!` listens for `hashchange`.
- **Example**:
  ```clojure
  (rf/make-frame {:id :app
                  :url-bound?   true
                  :url-strategy rf.routing/hash-url-strategy})
  ```

### `with-base-path`

- **Kind**: function
- **Signature**:
  ```clojure
  (with-base-path strategy base) → strategy-map
  ```
- **Description**: Wraps `strategy` so the app can be deployed under a sub-path, such as `/realworld/` on a host that mounts several demos side by side. It works with either shipped strategy or your own; it is not a third strategy.
    - `:encode` adds `base` to every outbound href, outside whatever form `strategy` produces: `/realworld/active` for history, `/realworld#/active` for hash.
    - `:decode` and `:install-listener!` strip `base` from every inbound URL. A fragment-form strategy's `:decode` never sees the base, so its result passes through unchanged.
    - `:push!` and `:replace!` are not wrapped, because the href they receive is already encoded, base included.
    - `route-url`, `match-url` and navigation stay path-form and know nothing of the base.
    - A blank or `nil` `base` returns `strategy` unchanged.
- **Example**:
  ```clojure
  (rf/make-frame {:id :app
                  :url-bound?   true
                  :url-strategy (rf.routing/with-base-path
                                  rf.routing/history-url-strategy
                                  "/realworld")})
  ```

### A custom strategy

Any map carrying these five functions is a strategy, and extra keys are kept.

| Key | Signature | Contract |
|---|---|---|
| `:encode` | `(fn [path] href)` | Turns an app URL (`/active?q=milk`) into the href the address bar and `route-link` show. Pure; runs on both hosts. |
| `:decode` | `(fn [href] path)` | The inverse: takes the origin-relative browser address (`pathname + search + hash`) and returns the app URL. Pure, and reads no `window`. For every app URL `p`, `(decode (encode p))` is `p`. |
| `:push!` | `(fn [href])` | Adds a history entry for `href`, which `:encode` has already produced. It must not encode again. ClojureScript only. |
| `:replace!` | `(fn [href])` | Replaces the current history entry, taking `href` as `:push!` does. ClojureScript only. |
| `:install-listener!` | `(fn [on-change] teardown)` | Installs the browser's URL-change listener and returns a zero-argument teardown function. Calls `on-change` with the decoded app URL on each browser-driven change. The runtime syncs the current URL itself when it installs the listener, so this function does not. ClojureScript only. |

`make-frame` checks a declared `:url-strategy`. In ClojureScript every one of the five keys must hold a function; on the JVM, `:encode` and `:decode`. Anything else, an explicit `nil` included, throws `:rf.error/invalid-url-strategy`, naming the keys that are missing or not functions. A new frame is then not created, and a re-registered one keeps its previous config.

`with-base-path` treats a strategy as fragment-form when `(encode "/")` returns a string starting with `#`, so a custom hash-style strategy takes a base path the way `hash-url-strategy` does.

## Multi-frame URL ownership

At most one frame owns the browser URL at a time. A frame claims it by being created with `{:url-bound? true}`. Both the outbound `:rf.nav/push-url` effect and the inbound browser listener use `url-owner-frame-id` to find the owner.

### `url-owner-frame-id`

- **Kind**: function
- **Signature**:
  ```clojure
  (url-owner-frame-id) → frame-id or nil
  ```
- **Description**: Returns the frame that declared browser-history ownership with `(rf/make-frame {:id … :url-bound? true})`, or `nil` when none has.
    - Ownership is always declared: like any other frame, `:rf/default` owns the URL only when it is created with `{:url-bound? true}`.
    - The owner is the first still-live frame that claimed `:url-bound? true`, so a later duplicate cannot take the URL. Creating the duplicate emits `:rf.error/duplicate-url-binding`, naming both frames; if the owner is destroyed, the next claimant takes over.
    - Frames that claimed `:url-bound? true` before `re-frame.routing` loaded have no recorded order. One such frame becomes the owner; with two or more, the runtime emits `:rf.error/duplicate-url-binding` for each extra and no frame owns the URL until one of those frames is registered again or destroyed.
    - With no owner, the outbound history effects do nothing and the inbound listener skips its dispatch.
- **Example**:
  ```clojure
  ;; one frame opts into URL ownership at boot:
  (rf/make-frame {:id :app/main :url-bound? true})
  (rf.routing/url-owner-frame-id)  ;; => :app/main
  ```

## Browser URL listener

The browser `popstate` or `hashchange` listener is installed for you. When a `:url-bound? true` frame is created or re-registered and becomes the URL owner, the runtime installs the listener and syncs the current URL into that frame's route slice. Destroying the frame removes the listener. There is no `install-url-listener!`, `remove-url-listener!`, `install-history-listener!` or `remove-history-listener!` to call.

- Each browser-driven change is decoded to a path-form URL by the strategy's `:decode`, then dispatched synchronously as `:rf.route/handle-url-change` to `(url-owner-frame-id)`, resolved when the change fires. With no `:url-bound? true` frame, nothing is dispatched.
- The listener kind (`popstate` or `hashchange`) comes from the owning frame's `:url-strategy` when the listener is installed.
- Installing is idempotent. A re-registration with the same owner and `:url-strategy` leaves the listener alone; a changed owner or strategy removes the old listener before installing the new one. A duplicate `:url-bound? true` frame that loses to the current owner never installs one.
- ClojureScript only. On the JVM there is nothing to install; SSR passes the request URL to `:rf.route/handle-url-change`.

## Framework integration

Not for application code — used by adapters, tools and the test harness.

The static route view and the live route-slice view that Xray draws have no public accessor: tools require `re-frame.routing.tooling` directly.

### Server-side rendering

#### `route-link-render-ssr`

- **Kind**: function
- **Signature**:
  ```clojure
  (route-link-render-ssr props & children) → hiccup
  ```
- **Description**: The JVM render function for the `:route/link` view. It renders the `<a href=...>` without click handling, since the server has no DOM events; on the hydrated page, clicks go through the ClojureScript view.
    - The href is encoded through the rendering frame's `:url-strategy`, as on the client. SSR sets the request frame for its render, so a `with-base-path` server frame renders `/demos/active` and a hash frame `#/active`, matching the hydrated render. Called outside any frame it renders the path form, as the history strategy would.
    - Application code writes [`route-link`](#route-link).

### Scroll restoration

Saved scroll positions are kept in a per-frame LRU cache on the host, keyed by frame id, not in `runtime-db`. They are read from `window.scrollX/Y`, mean nothing on the server, and are not needed to rebuild a frame on restore, SSR hydration or time-travel. So they never appear in traces, epochs or SSR payloads, and an epoch restore does not rewind them. The pure functions work on one frame's cache map, `{:positions {url [x y]} :order [url ...]}`; the `!` functions read and write the host cache.

#### `scroll-positions-cap`

- **Kind**: var
- **Signature**:
  ```clojure
  scroll-positions-cap  ;; => 50
  ```
- **Description**: The soft limit on URLs tracked in one frame's scroll cache. It is large enough for real Back-button restoration to find saved positions and small enough to keep the cache bounded over long sessions.

#### `frame-scroll-cache`

- **Kind**: function
- **Signature**:
  ```clojure
  (frame-scroll-cache frame-id) → {:positions :order} or nil
  ```
- **Description**: Returns the cache map `{:positions :order}` for `frame-id` from the host cache, or `nil` when there is none. Navigation planning reads this value.

#### `lookup-scroll-position`

- **Kind**: function
- **Signature**:
  ```clojure
  (lookup-scroll-position cache url) → [x y] or nil
  ```
- **Description**: Returns the saved `[x y]` for `url` in `cache`, or `nil`. Pure. `cache` is one frame's cache map `{:positions {url [x y]} :order [...]}`, and may itself be `nil`.

#### `save-scroll-position`

- **Kind**: function
- **Signature**:
  ```clojure
  (save-scroll-position cache url xy) → cache'
  ```
- **Description**: Returns `cache` with the position for `url` recorded under `:positions`. Pure.
    - The cache holds at most `scroll-positions-cap` URLs. Saving a URL again makes it the most recent, and a new save past the cap evicts the least recently used one. The `:order` vector records recency.

#### `save-scroll-position!`

- **Kind**: function
- **Signature**:
  ```clojure
  (save-scroll-position! frame-id url xy) → nil
  ```
- **Description**: Records `xy` for `url` in `frame-id`'s host cache, applying the cap through `save-scroll-position`.

### Navigation counters and state classification

The counters that allocate navigation tokens and pending-navigation ids are per-frame high-water marks kept on the host, not in `runtime-db`. An epoch restore replaces `runtime-db` wholesale; keeping the counters outside it means a restore cannot rewind them and reissue a token that a slow in-flight continuation still holds.

#### `counter-snapshot`

- **Kind**: function
- **Signature**:
  ```clojure
  (counter-snapshot frame-id) → {:nav-token-counter N :pending-nav-counter M} or {}
  ```
- **Description**: Returns the counters for `frame-id` from the host cache, or `{}` when there are none. The allocation coeffects take the next navigation token and pending-navigation id from this value.

#### `routing-state-classification`

- **Kind**: var
- **Signature**:
  ```clojure
  routing-state-classification
  ;; => {:durable-runtime-db             {:keys [:current] :doc "..."}
  ;;     :local-subscribable-runtime-db  {:keys [:pending-navigation] :doc "..."}
  ;;     :host-transient                 {:keys [:scroll-positions
  ;;                                             :nav-token-counter
  ;;                                             :pending-nav-counter] :doc "..."}}
  ```
- **Description**: Classifies every piece of per-frame routing state by tier, so the durable/transient split is defined in one place. SSR's payload policy takes its hydration keys from the `:durable-runtime-db` tier.
    - `:durable-runtime-db`: serializable facts needed to rebuild a coherent frame on restore or SSR hydration. This is the route slice at `:current`.
    - `:local-subscribable-runtime-db`: `runtime-db` state that stays subscribable and restores in local replay, but is stripped from SSR payloads. This is the `:pending-navigation` slot.
    - `:host-transient`: caches on the host, never in `runtime-db`. These are the saved scroll positions and the two counters.

### Test helpers

Each of these resets process-wide routing state so it does not leak from one test into the next. The fixture built by [`make-reset-runtime-fixture`](re-frame.test-support.md#make-reset-runtime-fixture) calls `reset-counters!`, `reset-nav-counters!` and `reset-url-claims!`.

#### `reset-counters!`

- **Kind**: function
- **Signature**:
  ```clojure
  (reset-counters!)
  ```
- **Description**: Resets the route-registration counter to zero, so the registration-order tiebreak in route ranking is the same on every fixture run.

#### `reset-scroll-cache!`

- **Kind**: function
- **Signature**:
  ```clojure
  (reset-scroll-cache!) → nil
  ```
- **Description**: Clears the whole host scroll cache.

#### `reset-nav-counters!`

- **Kind**: function
- **Signature**:
  ```clojure
  (reset-nav-counters!) → nil
  ```
- **Description**: Clears the whole host navigation-counter cache.

#### `reset-url-claims!`

- **Kind**: function
- **Signature**:
  ```clojure
  (reset-url-claims!) → nil
  ```
- **Description**: Clears the URL-ownership claim order, so the next test starts with no `:url-bound?` claim.

## See also

- [re-frame.core](re-frame.core.md): the facade entries for `reg-route` and `route-link`.
- [re-frame.ssr](re-frame.ssr.md): routes take part in SSR, and `head-model` looks up the active route's `:head`.
- [Routing glossary](../routing/glossary.md): navigate, route, loader, route guard, not-found, `url-bound?`.
- [Coming from React Router](../routing/coming-from-react-router.md): how the concepts map, and where re-frame2 routing differs.
