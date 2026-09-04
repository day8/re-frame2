# The model

<a id="routing-the-url-is-a-sub"></a>

This page is the **routing model** — three moves, then page data, guards,
not-found, and URL binding. Full leave/enter recipes live in the how-tos;
signatures live in [re-frame.routing](../api/re-frame.routing.md).

To *build* a three-page app step by step, use the [tutorial](tutorial.md).

!!! note "Optional artefact"

    Require `re-frame.routing` once at boot — Maven `day8/re-frame2-routing`. Forget
    it and the first `reg-route` throws `:rf.error/routing-artefact-missing`.

??? info "Coming from React Router?"

    Routes-as-data and loaders will feel familiar. Divergences: no hooks
    (`useNavigate` → dispatch, `useLoaderData` → sub, `useBlocker` → guard sub), no
    router context, same handler on the server. Full map:
    [Coming from React Router](coming-from-react-router.md).

## The whole model in three moves

<a id="the-whole-model-in-three-moves"></a>

```clojure
;; Adapted from examples/capabilities/routing/routing/core.cljs
(ns app.core
  (:require [re-frame.core :as rf]
            [re-frame.routing :as rf.routing]))

;; 1. A route is data in the registry.
(rf/reg-route :app/home {} "/")
(rf/reg-route :app/article
  {:params [:map [:id :string]]}
  "/articles/:id")

;; 2. Navigation is an event.
(rf/dispatch [:rf.route/navigate {:to :app/article :params {:id "intro"}}])

;; 3. The root view reads the active route through an ordinary subscription.
(rf/reg-view article-page []
  (let [{:keys [id]} @(subscribe [:rf.route/params])]
    [:h1 "Article " id]))

(rf/reg-view root-view []
  (case @(subscribe [:rf.route/id])
    :app/home           [:h1 "Home"]
    :app/article        [article-page]
    :rf.route/not-found [:h1 "Not found"]))
```

Inside `reg-view`, `subscribe` / `dispatch` are injected (no `rf/` prefix). Outside
a view, use `rf/subscribe` / `rf/dispatch`.

## Move 1: a route is a registry entry

<a id="move-1-a-route-is-a-registry-entry"></a>

`reg-route` is three slots: **id**, **metadata map**, **path** (third — never
`:path` inside the map; that throws `:rf.error/route-bad-metadata`).

Path grammar: literal segments, named params (`:id`), optional groups (`{/:slug}?`),
splat (`*rest`), root (`/`).

`:params` and `:query` take [schemas](../core/how-to/validate-with-schemas.md) that
**validate and coerce** — `?page=2` arrives as integer `2`.

```clojure
(rf/reg-route :app/search
  {:query          [:map [:q :string] [:page {:optional true} :int]]
   :query-defaults {:page 1}}          ;; fills :page in when the URL omits it
  "/search")
```

Path params and query params stay **separate maps** end to end.

`:query-defaults` is **destination-local** — it describes this route's own query.
No metadata key reaches into another route's query.

Because the declaration belongs to the destination, a filled default belongs to the
**resolved target** rather than to the URL that asked for it. Every way in fills it —
a deep link, a `route-link` click, `[:rf.route/navigate {:to :app/search}]`, a
prefetch — so `:page` reads `1` no matter which. And the URL never spells a value the
route would fill anyway: `route-url` omits a key already at its default, so
`/search?q=x` and `/search?q=x&page=1` are the same destination and the shorter one is
the canonical link.

### Carrying global state through the URL

A destination address is taken **literally**. `[:rf.route/navigate {:to :app/cart}]`
goes to exactly `/cart`; it never picks up query keys from whichever route happened
to be current.

Apps really do carry a theme, a locale, a tenant across routes. That is *your*
policy, so write it as an ordinary function:

```clojure
(defn with-shell-query
  "Carry the shell's global URL state onto a destination address.
   The explicit destination query wins."
  [current-query address]
  (update address :query
          (fn [destination-query]
            (merge (select-keys current-query [:theme :locale])
                   (or destination-query {})))))

(rf/dispatch [:rf.route/navigate
              (with-shell-query @(rf/subscribe [:rf.route/query]) {:to :app/cart})])
```

Read the dispatch and you know the URL — the carried keys are right there in the
address. It is a plain pure function, so `(with-shell-query {:theme "dark"} {:to :app/cart})`
is a one-line unit test with no frame and no router. Opting out is not calling it.

If the policy is genuinely app-wide, apply the helper inside your own navigation
event or an interceptor instead of at every call site. Either way it stays one
function you own.

!!! tip "Two things to get right"

    **Tolerate a missing `:query`.** `{:to :app/cart}` is the normal spelling, and a
    destination replayed out of a pending-leave value omits an empty `:query`
    entirely — hence the `(or destination-query {})`.

    **Keep a carried key's type consistent.** A value pulled from the current query
    slice has already been coerced by *that* route's schema — an `[:enum :light :dark]`
    key is the keyword `:dark`, not `"dark"`. The helper merges; it does not re-parse.
    A mismatch is caught, not silent: the destination route's `:query` schema validates
    at the call site and rejects the navigation.

To edit the **current** route's query instead of building a new address, use the
in-place `:query` / `:query-merge` request — that is the causal primitive for
"same page, different query".

### Routes are queryable data

<a id="routes-are-queryable-data"></a>

Tag a route; anything can query the table:

```clojure
(rf/reg-route :app/admin
  {:tags     #{:requires-auth}
   :on-match [[:admin/load-dashboard]]}
  "/admin")
;; (rf/handler-meta :route :app/admin) → metadata including :tags
```

Recipes: [Require sign-in](how-to/require-sign-in-on-a-route.md) (routing half),
[Add authentication](../core/how-to/add-auth.md) (full flow). Prefer **`:can-enter`**
for a single-route auth gate ([below](#guarding-entry--can-enter)); use an
interceptor when one policy spans many routes (and attach it so all three entry
doors are covered).

### Metadata keys

<a id="the-metadata-map-in-full"></a>
<a id="metadata-map-map-of-the-territory"></a>

| Group | Keys | Controls |
|---|---|---|
| **Shape** | `:params`, `:query`, `:query-defaults` | URL ↔ maps |
| **Lifecycle** | `:on-match`, `:can-leave`, `:can-enter` | Fire-and-forget activation work / guards |
| **Layout** | `:doc`, `:parent`, `:tags`, `:scroll` | Nesting (and `:resources` composition), grouping, scroll |
| **Classification** | `:sensitive`, `:large` | Egress redaction of the route slice |
| **Borrowed** | `:resources` (resources artefact), `:head` ([SSR head](../ssr/head.md)) | Server state / head model |

Bare unknown keys fail loud at registration (`:rf.error/route-bad-metadata`).
Namespaced keys (`:myapp/…`) are open extension. Canonical per-key list and
ranking cascade: [API `reg-route`](../api/re-frame.routing.md#reg-route).
`:path` is the **third** slot of `reg-route`, never a metadata key.

## Move 2: navigation is an event

<a id="move-2-navigation-is-an-event"></a>

```clojure
(rf/dispatch [:rf.route/navigate {:to :app/article :params {:id "intro"}}])

;; One request map — address, policy, and edit keys side by side:
(rf/dispatch [:rf.route/navigate {:to :app/search :query {:q "clojure" :page 2}}])
(rf/dispatch [:rf.route/navigate {:to :app/login :replace? true}])
(rf/dispatch [:rf.route/navigate {:to :app/article :params {:id "intro"} :fragment "section-2"}])
```

| Key | Effect |
|---|---|
| `:to` | Destination route id (`:url` is the raw-URL alternative) |
| `:params` | Path params for `:to` |
| `:replace?` | `replaceState` instead of `pushState` |
| `:query` | Replace query wholesale |
| `:query-merge` | Edit current query — a **map** of deltas (a `nil` *member* value removes that key; a non-map value rejects) |
| `:scroll` | `:top` / `:restore` / `:preserve` override |
| `:fragment` | `#fragment` |
| `:bypass-leave?` | `true` skips this route's `:can-leave` confirmation for one navigation |

<a id="navigate-in-place"></a>
<a id="navigate-in-place-change-the-query-stay-on-the-route"></a>

**Stay on this route, change query** — omit the destination for an *in-place* request:

```clojure
(rf/dispatch [:rf.route/navigate {:query-merge {:page 2}}])
```

No `:to` / `:url`: `:query-merge` folds into the current query, `:query` replaces it
wholesale, `:fragment` moves the anchor. Route and params carry over untouched.

That one request covers most of what a list page needs, and each spelling says
exactly what it means:

```clojure
;; Pagination — change one key, keep the filters.
(rf/dispatch [:rf.route/navigate {:query-merge {:page 2}}])

;; A new filter resets the page — nil removes a key rather than writing a blank.
(rf/dispatch [:rf.route/navigate {:query-merge {:tag "clojure" :page nil}}])

;; Clear every filter — replace the query wholesale.
(rf/dispatch [:rf.route/navigate {:query {}}])

;; A tab the user shouldn't be able to Back through — replace, don't push.
(rf/dispatch [:rf.route/navigate {:query-merge {:tab "comments"} :replace? true}])
```

Reading it back is one sub — `@(subscribe [:rf.route/query])` — and the route's
`:query` schema has already coerced the values, so `:page` is the number `2` rather
than `"2"`. Declare `:query-defaults` and a deep link to the bare `/search` arrives
with `:page 1` filled in, because defaults are applied wherever a target is resolved.

### Linking from views

<a id="linking-from-views"></a>

```clojure
[rf/route-link {:to :app/article :params {:id "intro"}} "Read more"]
[rf/route-link {:to :app/search :query {:q "clojure"} :class "nav-link"} "Search"]
```

Real `<a href>` — hover, copy-link, cmd/middle-click work. Plain left-click becomes
dispatch. `:target "_blank"` / `:download` are **not** SPA-intercepted (browser owns
them). That interception is [`route-link`](#linking-from-views)'s job: its view body
is the only thing that calls `.preventDefault` and dispatches
`:rf.route/url-requested`, the event the router listens on.

A plain `[:a {:href …}]` you hand-write does **not** fire that event — full page
navigation. Two honest ways to keep the click in-app: use
[`route-link`](#linking-from-views), or install one **document-level** click listener
that decides eligibility itself — plain primary-button click, no modifier keys, no
`:target`/`:download`, same-origin in-app `href` — and dispatches
`:rf.route/url-requested` on a match, letting the browser follow every click it
rejects.

Every prop `route-link` doesn't claim is passed through to the `<a>`, so styling,
`:data-*`, and ARIA attributes work as they do on any anchor. Two behaviour props
it does claim: `:prefetch :intent`
([warming a destination](#warming-a-destination-before-the-click)) and the address
keys used to build the `href`.

<a id="highlighting-the-active-link"></a>

#### Highlighting the active link

`route-link` computes **no** active state — it renders one anchor and nothing else.
"Am I on this page?" is a comparison against a route sub, which is the same
question a breadcrumb or a tab strip asks, so it belongs in your view:

```clojure
(rf/reg-view nav-link [props label]
  (let [active? (= (:to props) @(subscribe [:rf.route/id]))]
    [rf/route-link (cond-> props
                     active? (assoc :aria-current "page"
                                    :class (str (:class props) " is-active")))
     label]))
```

Compare `[:rf.route/id]` for "this section is active" and the whole
`[:rf.route/chain]` when a parent tab should light up for any of its children.
For an exact-URL match — one entry in a filter strip, say — compare `:params` or
`:query` too. `:aria-current "page"` is what a screen reader announces; the class
is what you style.

<a id="what-happens-in-order"></a>

### Order of effects

Navigate runs in a **locked order**: update route slice in runtime-db → push URL →
dispatch activation events. State before URL on purpose.

<a id="navigating-to-a-raw-url-string"></a>

Raw URL escape hatch: `(rf/dispatch [:rf.route/navigate {:url "/articles/intro"}])`.

## Move 3: the active route is a subscription

<a id="move-3-the-active-route-is-a-subscription"></a>

The current route lives in **runtime-db** (not app-db). You read; you never write:

```clojure
@(rf/subscribe [:rf/route])              ;; full slice
@(rf/subscribe [:rf.route/id])
@(rf/subscribe [:rf.route/params])
@(rf/subscribe [:rf.route/query])
@(rf/subscribe [:rf.route/fragment])
@(rf/subscribe [:rf.route/transition])   ;; :idle | :loading | :error
@(rf/subscribe [:rf.route/error])
@(rf/subscribe [:rf.route/chain])        ;; :parent ancestry (nested layouts)
@(rf/subscribe [:rf/pending-navigation]) ;; a leave the user hasn't answered, or nil
```

`:transition` drives a global progress bar without per-page loading flags. It is a
projection over the route's blocking `:resources`
([details](#when-a-loader-fails)), so the bar is honest about page data and quiet
about everything else:

```clojure
(rf/reg-view progress-bar []
  (case @(subscribe [:rf.route/transition])
    :loading [:div.progress.active]
    :error   [:div.error (:rf.error/message @(subscribe [:rf.route/error]))]
    nil))
```

### Fragments and scrolling

<a id="fragments-and-scrolling"></a>

Fragment-only changes update the slice and do **not** re-fire `:on-match`. Route
`:scroll` (or navigate opts): `:top` (default forward), `:restore` (default
back/forward), `:preserve`.

## Nested layouts

<a id="nested-layouts"></a>

No `<Outlet/>` — nesting is data. Child names `:parent`; compose shells from
`[:rf.route/chain]` (root-most first):

```clojure
(rf/reg-route :app/articles {} "/articles")
(rf/reg-route :app/article
  {:parent :app/articles
   :params [:map [:id :string]]}
  "/articles/:id")

(rf/reg-view root-view []
  (let [chain @(subscribe [:rf.route/chain])]
    (reduce (fn [inner ancestor] (ancestor-shell ancestor inner))
            (page-for (last chain))
            (reverse (butlast chain)))))
```

`:parent` earns its keep twice over: it gives you the chain to fold, and it
composes the ancestors' `:resources` into the child's plan
([below](#parent-resources-compose-to-the-child)). Nothing else is inherited.

Tutorial builds this: [Step 7](tutorial.md#step-7--a-shared-layout).

## Activation work and page data

<a id="loaders-declaring-a-pages-data"></a>

Two different jobs live next to a route, and keeping them apart is the whole trick.

**`:on-match`** is the *activation hook* — a vector of event vectors the runtime
fires and forgets whenever the route becomes active (including the same route with
changed params; identical params don't re-fire):

```clojure
(rf/reg-route :app/cart
  {:on-match [[:analytics/viewed-cart] [:cart/seed-ui-state]]}
  "/cart")
```

It runs client- and server-side, after the route slice is written and before any
view renders off it. What it is *not* is a readiness mechanism: `:on-match` never
moves `:rf.route/transition`, never waits for the async work its events start, and
never turns a handler's failure into a route error. Work that `:on-match` merely
kicks off keeps its status in the subsystem that owns it. A handler that throws
surfaces on the ordinary [event error channel](../core/errors.md), attributed to
the event that threw.

### Declaring the data a page needs

<a id="declaring-resources-instead"></a>

Managed server reads that must be present before the page is honest are declared
with `:resources`, from the resources artefact:

```clojure
(rf/reg-route :realworld.article/show
  {:params [:map [:slug :string]]
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

Ownership is nav-token keyed: leave or supersede → release; late replies
**suppressed**. Per-user data uses a scope resolver (`{:from-db …}`) — fails closed
when logged out. Full story: [Resources model](../resources/concepts.md).

<a id="when-a-loader-fails"></a>

### Readiness is a projection over the blocking resources

`:rf.route/transition` and `:rf.route/error` report one honest fact: whether the
blocking reads the active route plan declares are present, still on their first
load, or failed.

| Plan state | `:transition` | `:error` |
|---|---|---|
| A blocking first load is still pending | `:loading` | `nil` |
| A blocking first load failed | `:error` | the first failure (`:rf.error/resource-route-blocking`) |
| The plan could not be built at all | `:error` | `:rf.error/resource-route-plan` |
| Every blocking read has usable data, or there are none | `:idle` | `nil` |

A background refresh over data already on screen is not `:loading`, and a refresh
failure stays on the resource's own channel rather than reddening the route. A
non-blocking read, an [intent prefetch](#warming-a-destination-before-the-click),
and `:on-match` never change either value. With no resources artefact loaded the
route is always `:idle` — there is nothing to be honest about.

### Parent resources compose to the child

<a id="parent-resources-compose-to-the-child"></a>

Naming a `:parent` opts the child into its ancestors' `:resources`. Activation
plans the effective parent-to-leaf branch, so a shell read is declared **once** on
the parent instead of restated in every tab:

```clojure
(rf/reg-route :app/profile
  {:params    [:map [:username :string]]
   :resources [{:resource  :app/profile
                :params    (fn [route] {:username (get-in route [:params :username])})
                :blocking? true}]}
  "/profile/:username")

(rf/reg-route :app/profile-favorites
  {:parent    :app/profile                    ;; inherits the profile read above
   :params    [:map [:username :string]]
   :resources [{:resource  :app/favorited-articles
                :params    (fn [route] {:username (get-in route [:params :username])})
                :blocking? false}]}
  "/profile/:username/favorites")
```

`:parent` *is* the opt-in — there is no separate inherit flag. Only `:resources`
fold this way; `:on-match`, `:scroll`, `:head`, `:tags`, and the guards are not
inherited, because unrelated metadata wants incompatible merge rules. Identical
requirements contributed by more than one route in the branch are deduped to one
fetch, and a child that restates a requirement its parent already contributes gets
an advisory rather than a second fetch. Composing resources does not compose
rendering: the layout chain is still yours to walk
([Nested layouts](#nested-layouts)).

<a id="warming-a-destination-before-the-click"></a>

### Warming a destination before the click

A link can warm its destination's data on hover, focus, or touch, so the click
lands on a fetch already in flight:

```clojure
[rf/route-link {:to :app/article :params {:id "intro"} :prefetch :intent}
 "Read more"]
```

`:intent` is the only accepted value — there is no render mode, viewport mode, or
hover delay, and a passive render dispatches nothing. To opt out, **leave
`:prefetch` off**: a key that is present with any other value fails loud at the
render site rather than quietly giving you a passive link, because a link that
should have been warming and isn't looks exactly like one that is. Under the hood
the link dispatches
`[:rf.route/prefetch {:to :app/article :params {:id "intro"}}]`, which you can also
dispatch yourself from any event.

A prefetch runs the *same* effective branch plan a real navigation would, in warm
mode: every ensure is ownerless, `:blocking?` is inert, and no route state moves —
no slice write, no URL, no scroll, no guards, no `:on-match`. Click through
afterwards and the ordinary resource dedupe reuses the warmed work; never click and
it stays garbage-collectable. Prefetch is a performance hint, not an authorization
boundary — warming a destination whose `:can-enter` would deny is permitted and
means nothing, because activation still evaluates the guard.

### Replanning the active route's resources

Sometimes the *identity* behind a route's reads changes while the route itself does
not: a saved session restores after the page was already entered, an admin switches
tenant, a viewer starts impersonating someone. A `{:from-db …}` resource subscription
re-keys to the new identity on its own — but re-keying is passive, so the freshly
selected entry just sits `:idle`, and navigating to the same address is deliberately
a no-op. The causal door is one event:

```clojure
(rf/dispatch [:rf.route/replan-resources {:cause [:session-restore]}])
```

It reruns the active route's effective parent-to-leaf plan against the current
`app-db`, under the **same** nav-token and the same route owner. Identities the plan
still needs are kept (adopted, no request); newly required ones are ensured under the
route owner with your `:cause`; the ones the new plan drops lose the owner and only the
owner. The route's durable plan and blocking facts are replaced and its readiness is
re-projected, so a route that failed to plan while identity was unresolved is repaired
in place. A replan that itself fails to plan is a committed failed replan: nothing is
partially ensured and the owner is released from everything it held, on purpose — a
departed scope's plan must not keep settling data fetched under the new credentials.
`:cause` is required; the recipe for an identity switch is *resolve and clear the old
scope, commit the new identity, then replan*. It is not a reload — unchanged, usable
data is never refetched — and it runs no guards, `:on-match`, URL or scroll work. See
[Server state](../resources/concepts.md).

## Blocking a navigation

<a id="blocking-a-navigation"></a>

`:can-leave` is a **boolean** sub (`true` = leave is fine). On `false`, navigation
parks in `[:rf/pending-navigation]`. Resolve with the pending **id**:

```clojure
(rf/reg-route :app/article-editor
  {:params    [:map [:id :string]]
   :can-leave [:editor/can-leave?]}
  "/articles/:id/edit")

(rf/reg-view leave-dialog []
  (when-let [p @(rf/subscribe [:rf/pending-navigation])]
    [:div.modal
     [:button {:on-click #(dispatch [:rf.route/cancel (:id p)])} "Stay"]
     [:button {:on-click #(dispatch [:rf.route/continue (:id p)])} "Leave"]]))
```

The pending value stores the destination, target, cause and your explicit
`:replace?` / `:scroll` policy, so `:rf.route/continue` replays exactly what you
asked for. Skip the confirmation for one navigation with `{:bypass-leave? true}`.
Full recipe: [Guard against unsaved changes](how-to/guard-unsaved-changes.md).

### Guarding entry — `:can-enter`

<a id="guarding-entry--can-enter"></a>
<a id="guarding-entry---can-enter"></a>

The entry guard — the usual auth gate. Runs on every door (navigate, link, URL
bar, Back/Forward, initial load, SSR).

```clojure
(rf/reg-route :app/account
  {:can-enter [:auth/signed-in?]}
  "/account")
```

Entry rejection is **terminal**, not resumable: nothing commits, *no* pending
value is created, and the runtime dispatches `:rf.route/entry-denied` once. You
do not have to register a handler — the framework ships a no-op default, so a
denial with no handler is simply a hard deny (and a `403` under SSR). Register
one when you want a login bounce:

```clojure
(rf/reg-event :rf.route/entry-denied
  (fn [{:keys [db]} [_ {:keys [destination]}]]
    {:db (assoc-in db [:auth :return-to] destination)
     :fx [[:dispatch [:rf.route/navigate {:to :app/login :replace? true}]]]}))
```

After sign-in you navigate **freshly** to the stashed `destination` — the guard
re-evaluates because that is an ordinary new attempt. Full recipe:
[Require sign-in on a route](how-to/require-sign-in-on-a-route.md).

Non-boolean guard return → fail closed + `:rf.error/can-leave-non-boolean` /
`:rf.error/can-enter-non-boolean` (refuse the move, raise the error).

**Prefer `:can-enter` for per-route auth** (see
[realworld_http](../../examples/real-apps/realworld_http)). Use an interceptor only
when one policy spans many routes:
[Require sign-in](how-to/require-sign-in-on-a-route.md).

## Not found is a route you register

<a id="not-found-is-a-route-you-register"></a>

Register reserved id `:rf.route/not-found`. Offending URL lands in `:params`
(with optional `:reason`):

| `:params` | What happened |
|---|---|
| `{:url "…"}` | No pattern matched |
| `{:url "…" :reason :validation}` | Matched, schema failed |
| `{:url "…" :reason :malformed-url}` | Bad percent-encoding (404, not crash) |

Missing registration → warning + built-in placeholder. Programmatic schema miss is
loud (`route-url` throws; navigate rejects); URL-driven miss is 404.

## The browser is just another event source

<a id="the-browser-is-just-another-event-source"></a>

```clojure
(rf/make-frame {:id :rf/default :url-bound? true})
```

`:url-bound? true` — this frame owns the address bar (one owner;
`:rf.error/duplicate-url-binding` if two claim). Installs listener + initial sync;
no separate install API. Frames without the flag still route **in memory** (Story,
tests).

## The same handler runs on the server

<a id="the-same-handler-runs-on-the-server"></a>

[SSR](../ssr/concepts.md) feeds the request URL to the **same** URL-change path on a
per-request frame. `:on-match` / blocking `:resources` run; state ships in the
payload; client hydrates without re-fetch. URL push and scroll are no-ops on the
server. Detail: [SSR model](../ssr/concepts.md).

## A complete table + root

Copy-paste shape (pages and loaders are stubs — fill in as the tutorial does):

```clojure
(ns app.routes
  (:require [re-frame.core :as rf]
            [re-frame.routing]
            [re-frame.adapter.reagent :as reagent-adapter]))

(rf/reg-route :app/home {} "/")
(rf/reg-route :app/articles {} "/articles")
(rf/reg-route :app/article
  {:params   [:map [:id :string]]
   :on-match [[:article/load]]}
  "/articles/:id")
(rf/reg-route :rf.route/not-found
  {:doc "Unmatched URLs"} "/_404")   ;; path is a registration slot; id is reserved

(rf/reg-view root-view []
  (case @(subscribe [:rf.route/id])
    :app/home           [home-page]
    :app/articles       [articles-page]
    :app/article        [article-page]
    :rf.route/not-found [not-found-page]
    [not-found-page]))

(defonce app-root (reagent-adapter/client-root))

(defn run []
  (rf/init! reagent-adapter/adapter)
  (reagent-adapter/render! app-root
    [rf/frame-root {:id :rf/default :url-bound? true}
     [root-view]]
    (js/document.getElementById "app")))
```

## Troubleshooting

| Symptom | What happened | Error / recovery |
|---|---|---|
| First `reg-route` throws | Forgot `(:require [re-frame.routing])` | `:rf.error/routing-artefact-missing` |
| Registration throws on metadata | `:path` inside the map, or unknown bare key | `:rf.error/route-bad-metadata` — path is the **third** slot |
| Two frames both claim the address bar | Two `:url-bound? true` | `:rf.error/duplicate-url-binding` — one owner |
| Leave/enter always blocks | Guard sub returned non-boolean | `:rf.error/can-leave-non-boolean` / `:rf.error/can-enter-non-boolean` — return strict `true`/`false` |
| `route-url` blows up | Missing path param | `:rf.error/missing-route-param` (nil query keys are elided, not thrown) |
| Navigate rejected | Bad request map | `:rf.error/navigate-bad-request` |
| Unmatched URL is a bare placeholder | Never registered `:rf.route/not-found` | Register it; params carry `:url` and optional `:reason` |
| Plain `[:a {:href …}]` full-reloads | Not going through `route-link` | Use `route-link`, or a document-level click → `:rf.route/url-requested` |

## When *not* to use routing

| Situation | Prefer |
|---|---|
| Single-screen app, no shareable URLs | No routing artefact (zero cost) |
| In-memory UI steps with no URL | app-db flags / a [machine](../machines/index.md) |
| Server-only redirects | Host middleware or [SSR](../ssr/concepts.md) response effects |

## Advanced

### Hand-rolled async loader — capture the nav-token

<a id="a-hand-rolled-async-loader"></a>

`:resources` is the everyday way to load a page. Roll your own async fetch from
`:on-match` and you inherit the race it closes: open article A, navigate to B
before A's reply lands, late A overwrites B. Capture the **navigation token** when
the load starts and gate delivery on it.

Two hooks: `:rf.route/nav-token` **cofx** injects the live token into an
`:on-match` handler; `:rf.route/with-nav-token` **fx** delivers a reply only while
that token still matches the current slice — otherwise suppresses and fires
`:rf.route.nav-token/stale-suppressed`.

```clojure
;; Capture the live token, kick off your fetch, carry the token into the reply.
(rf/reg-event :app/load-article
  {:rf.cofx/requires [:rf.route/nav-token]}
  (fn [{:rf.route/keys [nav-token] rt :rf.db/runtime} _]
    (let [{:keys [id]} (get-in rt [:rf.runtime/routing :current :params])]
      ;; :app/fetch-article is YOUR async effect; on reply it dispatches
      ;; :app/article-arrived with the captured token + payload.
      {:fx [[:app/fetch-article {:id id :on-reply [:app/article-arrived nav-token id]}]]})))

;; Hand the CAPTURED token to :rf.route/with-nav-token. Fresh → :rf/reply-to runs;
;; stale (newer navigation) → reply dropped before it can touch app-db.
(rf/reg-event :app/article-arrived
  (fn [_ [_ captured-token id payload]]
    {:fx [[:rf.route/with-nav-token
           {:rf/reply-to [:app/article-loaded id payload]
            :nav-token   captured-token}]]}))

(rf/reg-event :app/article-loaded
  (fn [{:keys [db]} [_ id payload]]
    {:db (assoc db :article/current payload)}))
```

`:resources` already does this — declare it and the race is closed. Hand-roll only
when the resource layer doesn't cover you.

### Keeping tokens off the wire

<a id="keeping-tokens-off-the-wire"></a>

```clojure
(rf/reg-route :app/oauth-callback
  {:query     [:map [:token :string] [:code :string]]
   :sensitive [[:query :token] [:query :code]]}
  "/oauth/callback")
```

Egress-only redaction while the route is active. Full story:
[Keep secrets out of traces](../core/how-to/keep-secrets-out-of-traces.md).

### URL strategies

<a id="url-strategies"></a>

```clojure
(rf/make-frame {:id           :app
                :url-bound?   true
                :url-strategy rf.routing/hash-url-strategy})  ;; default: history-url-strategy
```

`route-url` / `match-url` stay path-form; strategy encodes `#` at the edges.
`rf.routing/with-base-path` for deploy under a subpath. SSR runs no strategy side
effects (no history, no listener) but does encode `route-link` hrefs through the
rendering frame's strategy, so the server shell carries the same `href` the hydrated
client renders.

<a id="converting-routes--urls-by-hand"></a>

### Codec by hand

```clojure
(rf.routing/route-url {:to :app/article :params {:id "intro"}})
;; => "/articles/intro"
(rf.routing/match-url "/articles/intro")
;; => {:route-id :app/article :params {:id "intro"} …}
```

Pure, JVM + CLJS. `nil` path param → throw; `nil` query param → elided.

| Need | Where |
|---|---|
| Unsaved-changes prompt | [Guard against unsaved changes](how-to/guard-unsaved-changes.md) |
| Multi-route auth interceptor | [Require sign-in](how-to/require-sign-in-on-a-route.md) |
| Cached server reads on a page | [Resources](../resources/concepts.md) + `:resources` above |
| Head metadata / SSR | [SSR model](../ssr/concepts.md) |
| Prove codec + navigation | [Testing](testing.md) |
| Runnable apps | [Examples](examples.md) |

API catalogue: [re-frame.routing](../api/re-frame.routing.md).
