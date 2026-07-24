# The model

<a id="routing-the-url-is-a-sub"></a>

This page is the **routing model** — three moves, then loaders, guards, not-found,
and URL binding. Full leave/enter recipes live in the how-tos; signatures live in
[re-frame.routing](../api/re-frame.routing.md).

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

<a id="carrying-global-state-through-the-url"></a>

```clojure
(rf/reg-route :app/search
  {:query          [:map [:q :string] [:page {:optional true} :int]]
   :query-defaults {:page 1}
   :query-retain   #{:theme :locale}}   ;; ride along on later navigations
  "/search")
```

Path params and query params stay **separate maps** end to end.

!!! warning "Retained query keys aren't re-coerced"

    `:query-retain` merges keys **verbatim** into the next URL. Keep types consistent
    across routes that retain them.

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
| **Shape** | `:params`, `:query`, `:query-defaults`, `:query-retain` | URL ↔ maps |
| **Lifecycle** | `:on-match`, `:on-error`, `:can-leave`, `:can-enter` | Activate / error / guards |
| **Layout** | `:doc`, `:parent`, `:tags`, `:scroll` | Nesting, grouping, scroll |
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
| `:query-merge` | Edit current query (`nil` removes a key) |
| `:scroll` | `:top` / `:restore` / `:preserve` override |
| `:fragment` | `#fragment` |
| `:bypass-guards?` | Set `#{:leave :enter}` to skip named guards |

<a id="navigate-in-place"></a>
<a id="navigate-in-place-change-the-query-stay-on-the-route"></a>

**Stay on this route, change query** — omit the destination for an *in-place* request:

```clojure
(rf/dispatch [:rf.route/navigate {:query-merge {:page 2}}])
```

No `:to` / `:url`: `:query-merge` folds into the current query, `:query` replaces it
wholesale, `:fragment` moves the anchor. Route and params carry over untouched.

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

<a id="what-happens-in-order"></a>

### Order of effects

Navigate runs in a **locked order**: update route slice in runtime-db → push URL →
dispatch loaders. State before URL on purpose.

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
@(rf/subscribe [:rf/pending-navigation]) ;; blocked leave/enter, or nil
```

`:transition` drives a global progress bar without per-page loading flags:

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

Tutorial builds this: [Step 7](tutorial.md#step-7--a-shared-layout).

## Loaders: declaring a page's data

<a id="loaders-declaring-a-pages-data"></a>

**`:on-match`** — vector of event vectors on activate (including same route with
changed params; identical params don't re-fire):

```clojure
(rf/reg-route :app/cart
  {:on-match [[:cart/load-items] [:user/load-prefs]]
   :on-error [:app/cart-load-failed]}
  "/cart")
```

<a id="when-a-loader-fails"></a>

Runs client- and server-side. On loader failure, `:transition` → `:error`; optional
`:on-error` event fires once (first error wins; later loaders still run).

### Declaring resources instead

<a id="declaring-resources-instead"></a>

With the resources artefact, declare cached server reads on the route:

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

Bypass one navigation: `{:bypass-guards? #{:leave}}` (or `#{:enter}` / both).
Full recipe: [Guard against unsaved changes](how-to/guard-unsaved-changes.md).

### Guarding entry — `:can-enter`

<a id="guarding-entry--can-enter"></a>
<a id="guarding-entry---can-enter"></a>

Mirror for **enter** (the usual auth gate). Runs on every door (navigate, link, URL
bar, Back/Forward). On block → `:rf.route/entry-blocked` (redirect to login there).
`[:rf.route/continue <id>]` re-runs `:can-enter` on resume.

```clojure
(rf/reg-route :app/account
  {:can-enter [:auth/signed-in?]}
  "/account")
```

Non-boolean guard return → block + `:rf.error/can-leave-non-boolean` /
`:rf.error/can-enter-non-boolean` (deny the move, raise the error).

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
            [reagent.dom.client :as rdc]
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

(defn run []
  (rf/init! reagent-adapter/adapter)
  (rdc/render (rdc/create-root (js/document.getElementById "app"))
              [rf/frame-root {:id :rf/default :url-bound? true}
               [root-view]]))
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

`:on-match` and `:resources` are the everyday loaders. Roll your own async fetch and
you inherit the race they close: open article A, navigate to B before A's reply
lands, late A overwrites B. Capture the **navigation token** when the load starts
and gate delivery on it.

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
`rf.routing/with-base-path` for deploy under a subpath. SSR ignores strategies (path
form on the wire; client re-encodes on hydrate).

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
