# EP 012 — Routing

> Status: Drafting. **v1-required.** Routing is part of Goal #7 (Real SPA concerns are first-class) per [reorient.md](reorient.md). The pattern speaks to it; this EP locks the contract.

## Abstract

Routing is **state plus events**, not a separate subsystem. The URL is a derivable view of `app-db`; navigation is an event. Browser back/forward, deep links, and SSR all flow through this single contract.

The reorient principle: routing does not get its own runtime. It uses the runtime that already exists — frames, events, subs, app-db. A route table is data; routes are registry entries; `:route/navigate` is an event; `(rf/sub :route)` derives the active route from `app-db`. Nothing new at the foundation level.

## Pattern-level contract

### The route table is data

A route is a `(kind :route, id keyword)` registry entry whose metadata describes its URL pattern, params, and any constraints. Routes register exactly like any other kind:

```clojure
(rf/reg-route :route/home
  {:doc  "The landing page."
   :path "/"})

(rf/reg-route :route/cart
  {:doc  "The cart page."
   :path "/cart"})

(rf/reg-route :route/cart.item-detail
  {:doc    "Detail page for a single cart item."
   :path   "/cart/items/:id"
   :params [:map [:id :uuid]]})

(rf/reg-route :route/article
  {:doc    "An article. Optional slug suffix."
   :path   "/articles/:id{/:slug}?"
   :params [:map [:id :uuid] [:slug {:optional true} :string]]})
```

The `:path` syntax is implementation-specific (the CLJS reference uses bidi-flavoured syntax with `:param` segments and `{...}?` optional groups). Pattern-level requirements:

- The path is **a single string**, parseable both ways: a path-pattern matched against an incoming URL produces a params map; a route-id + params map produces a URL.
- The params shape is described by the host's idiom (Malli for CLJS dynamic; types for static hosts; per [reorient.md](reorient.md) on the schema/type duality).
- Routes are **stably-id'd**, queryable via `(rf/handlers :route)`, source-coordinated.

### Navigation is an event

```clojure
(rf/reg-event-fx :route/navigate
  {:doc  "Navigate to a registered route."
   :spec [:cat [:= :route/navigate] :keyword [:? :map]]}
  (fn handler-route-navigate [{:keys [db]} [_ route-id params]]
    (let [route-meta (rf/handler-meta :route route-id)
          url        (rf/route-url route-id (or params {}))]
      {:db (assoc db :route {:id route-id :params (or params {})})
       :fx [[:nav/push-url url]]})))
```

Two effects flow:
1. `app-db`'s `:route` slice is updated.
2. The browser URL is pushed via `:nav/push-url` (a registered fx; `:platforms #{:client}`).

The order is locked: state changes first, URL update second. If the URL update fails (browser denies, user is offline) the state is still consistent.

### URL changes are events

When the user clicks a link, presses Back/Forward, or arrives via a deep link, a `popstate`-equivalent event fires on the client. The pattern handler:

```clojure
(rf/reg-event-fx :route/handle-url-change
  {:doc       "Triggered by URL change (popstate or initial load). Sets app-db's route slice from the URL."
   :platforms #{:client :server}}                 ;; same handler is used by SSR
  (fn handler-route-handle-url-change [{:keys [db]} [_ url]]
    (let [{:keys [route-id params]} (rf/match-url url)
          route-meta                (rf/handler-meta :route route-id)]
      (cond
        ;; No match → 404 route
        (nil? route-id)
        {:db (assoc db :route {:id :route/not-found :params {:url url}})}

        ;; Validation failure → 404 (or, optionally, a configured error route)
        (and route-meta (:params route-meta) (not (rf/validate (:params route-meta) params)))
        {:db (assoc db :route {:id :route/not-found :params {:url url :reason :validation}})}

        :else
        {:db (assoc db :route {:id route-id :params params})}))))
```

The same handler runs **on the server during SSR** (no `:platforms` exclusion) — the request URL is fed in, the route slice is set, the view renders against it. No SSR-specific routing code.

### Reading the route is a sub

```clojure
(rf/reg-sub :route
  {:doc "The current route map: {:id ... :params ...}"}
  (fn sub-route [db _] (:route db)))

(rf/reg-sub :route/id
  :<- [:route]
  (fn [route _] (:id route)))

(rf/reg-sub :route/params
  :<- [:route]
  (fn [route _] (:params route)))
```

Views derive UI from the route the same way they derive UI from any other state — no special routing API in views.

### The root view dispatches on `:route/id`

```clojure
(def app-root
  (rf/reg-view :app/root
    (fn render-app-root []
      (case @(subscribe [:route/id])
        :route/home              [home-page]
        :route/cart              [cart-page]
        :route/cart.item-detail  [cart-item-detail]
        :route/article           [article-page]
        :route/not-found         [not-found-page]))))
```

Pattern: a single `case` (or equivalent) over the route id at the top of the tree. Per-route views can subscribe to `:route/params` for their own data needs.

### Bidirectional URL ↔ params

Two pure helpers, both registered, both queryable:

- `(rf/match-url url)` → `{:route-id :keyword :params {...}}` or `nil`. Pure; runs on JVM and CLJS.
- `(rf/route-url route-id params)` → URL string. Pure; runs on JVM and CLJS.

Both work against the same registered route table, so adding/removing a route updates both directions automatically.

## Optional patterns

### Nested routes

For nested layouts (e.g., `/account/settings`, `/account/billing`, `/account/security` all rendering inside an `/account` shell), the simplest pattern is **id namespacing**:

```clojure
(rf/reg-route :route/account            {:path "/account"})
(rf/reg-route :route/account.settings   {:path "/account/settings"})
(rf/reg-route :route/account.billing    {:path "/account/billing"})
(rf/reg-route :route/account.security   {:path "/account/security"})
```

Views check the prefix:

```clojure
(rf/reg-sub :route/section
  :<- [:route/id]
  (fn [id _]
    (cond
      (= id :route/account)               :account-overview
      (str/starts-with? (str id) ":route/account.") :account-section
      :else                                :other)))
```

A more elaborate router with native nested routes is possible but **not required by the pattern**. The id-prefix convention covers the common case.

### Redirects and guards

A `:route/navigate` event can be intercepted by an interceptor that decides whether the navigation proceeds, redirects elsewhere, or aborts:

```clojure
(def auth-guard
  {:id     :route/auth-guard
   :before (fn before [ctx]
             (let [event       (get-in ctx [:coeffects :event])
                   target      (second event)
                   route-meta  (rf/handler-meta :route target)
                   needs-auth? (boolean (some #{:requires-auth} (:tags route-meta)))
                   logged-in?  (some? (get-in ctx [:coeffects :db :auth :user]))]
               (if (and needs-auth? (not logged-in?))
                 ;; redirect to login
                 (assoc-in ctx [:coeffects :event] [:route/navigate :route/login {:return-to target}])
                 ctx)))})

(rf/reg-route :route/account
  {:path "/account"
   :tags #{:requires-auth}})
```

Guards are interceptors, not a special routing mechanism. They compose; multiple guards can layer.

### Scroll restoration

Browser-default behaviour on `popstate` restores scroll position. For SPA-controlled scroll (e.g., scroll to top on forward navigation, restore on back), a registered fx handles it:

```clojure
(rf/reg-fx :nav/scroll
  {:platforms #{:client}}
  (fn fx-nav-scroll [_m {:keys [behavior position]}]
    (case position
      :top      (.scrollTo js/window 0 0)
      :restore  ...)))                              ;; impl-specific
```

`:route/navigate` decides per-route whether to emit `:nav/scroll` based on the route's `:scroll-on-navigate` metadata.

### Query strings and fragments

The path syntax is the *primary* binding; query strings and fragments are *additional*:

```clojure
(rf/reg-route :route/search
  {:path  "/search"
   :query [:map [:q :string] [:page {:optional true} :int]]})

;; URL: /search?q=clojure&page=2
;; Match yields: {:route-id :route/search :params {:q "clojure" :page 2}}
```

The pattern doesn't distinguish path-params from query-params at the consumer level — both flow into `:params`. The implementation decides how the URL serialises.

## Server-side rendering integration (per [011](011-SSR.md))

The server-side flow:

1. HTTP request arrives.
2. `make-frame` per request. `:on-create` fires `[:rf/server-init request]`, which dispatches `[:route/handle-url-change (:uri request)]`.
3. Route slice is set from the URL; the same handler runs on server and client.
4. Per-route data fetches happen in subsequent setup events (e.g., `[:cart/load-items]` if `:route/id` is `:route/cart`).
5. Drain settles; root view renders against the populated state.
6. HTML + serialised state ship to the client.

On the client, hydration runs `[:rf/hydrate state]` which restores the route along with everything else. The first client render produces the same HTML the server rendered (same `:route/id`, same `:route/params`).

## Tooling and AI-amenability

- `(rf/handlers :route)` enumerates every registered route. Tools and agents enumerate them; AI scaffolding consults this before generating new routes to avoid collisions.
- `(rf/handler-meta :route :route/cart)` returns the route's metadata: path, params shape, tags, source coords.
- The `:route` sub gives the entire route map; `:route/id` and `:route/params` are conveniences.
- `:route/navigate` and `:route/handle-url-change` are stable, named events; trace events surface every navigation.
- A registered `:route/not-found` is required (handles 404s); the runtime emits a warning trace event if no `:route/not-found` is registered when an unknown URL arrives.

## Open questions

### R-1. Route-id vs. URL-string in `:route/navigate`

Two possible call shapes:

```clojure
(rf/dispatch [:route/navigate :route/cart])                       ;; route-id (preferred)
(rf/dispatch [:route/navigate {:url "/cart"}])                    ;; URL-string (escape hatch)
```

Lean: route-id is the canonical form. URL-string accepted as a fallback (for dynamic links, e.g. user-supplied content) and resolves via `match-url` internally.

### R-2. Linking from views

Should `<a href="..."` clicks be intercepted automatically by the runtime (so any `<a>` becomes a navigation event), or should views explicitly use a `[:route-link {:to :route/cart} "Cart"]` component?

Lean: explicit `[:route-link ...]` registered view. Automatic interception of all `<a>` clicks is too invasive and breaks externally-targeted links.

### R-3. Route reloading

When the URL changes but resolves to the same route id with different params (e.g., `/articles/abc` → `/articles/xyz`), should it count as a new mount or an update? Affects per-page setup events (`:on-route-enter`-style hooks).

Lean: the *same view* renders with new params; views are responsible for reading `:route/params` reactively. A `:on-route-enter` interceptor pattern is available but opt-in — there's no built-in lifecycle hook.

### R-4. Multi-frame routing

If a page hosts multiple frames (devcards, story variants), do they share a route or have independent routes?

Lean: each frame has its own `:route` slice in its own `app-db`. The default frame's route is bound to the browser URL; non-default frames' routes are independent and don't push to the URL. Story-tooling explicitly opts a frame in or out of URL-binding via `reg-frame :url-bound? true`.

## Disposition

**v1.** Routing is in the v1 contract. The CLJS reference ships:

- `reg-route`, `match-url`, `route-url`.
- `:route/navigate`, `:route/handle-url-change` standard events.
- `:nav/push-url`, `:nav/replace-url`, `:nav/scroll` registered fx.
- `:route` / `:route/id` / `:route/params` standard subs.
- `[:route-link ...]` registered view.
- A path-syntax parser (bidi-flavoured) and validator.
- 404 / not-found handling with a default `:route/not-found` if user doesn't register one.

**Post-v1:** native nested routes (beyond id-prefix convention), URL-state-as-source-of-truth (the URL is the canonical state, `app-db` derives), declarative redirect rules in route metadata.

## Cross-references

- [reorient.md §working design implications](reorient.md) — "routing is state plus events."
- [011-SSR.md](011-SSR.md) — SSR-side route resolution.
- [Construction-Prompts §CP-7](Construction-Prompts.md) — route scaffolding template.
