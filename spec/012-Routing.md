# Spec 012 — Routing

> Routing is part of Goal #8 (Real SPA concerns are first-class) per [000-Vision.md](000-Vision.md).

## Abstract

Routing is **state plus events**, not a separate subsystem. The URL is a derivable view of `app-db`; navigation is an event. Browser back/forward, deep links, and SSR all flow through this single contract.

The principle: routing does not get its own runtime. It uses the runtime that already exists — frames, events, subs, app-db. A route table is data; routes are registry entries; `:rf.route/navigate` is an event; `(rf/sub :route)` derives the active route from `app-db`. Nothing new at the foundation level.

## Normative surface inventory

The complete routing API surface, for quick audit. Each entry links to its normative definition below.

### Registration

- **`reg-route`** — registers a route. Reserved metadata keys: `:doc`, `:path` (required), `:params`, `:query`, `:query-defaults`, `:query-retain`, `:tags`, `:parent`, `:on-match`, `:on-error`, `:scroll`, `:can-leave`. See [§Reserved route-metadata keys](#reserved-route-metadata-keys) and [§Navigation blocking — pending-nav protocol](#navigation-blocking--pending-nav-protocol) for `:can-leave`.
- **Path-pattern grammar** — five productions (literal, named param, optional segment group, splat, root). See [§Path-pattern grammar](#path-pattern-grammar-canonical).
- **Route ranking** — six-rule cascade for resolving overlapping matches. See [§Route ranking algorithm](#route-ranking-algorithm).

### `app-db` slices

- **`:route` slice** — `{:id :params :query :fragment :transition :error :nav-token}`. Schema `:rf/route-slice`. See [§The `:route` slice](#the-route-slice).
- **`:rf/pending-navigation` slot** — populated when a `:can-leave` guard rejects. Schema `:rf/pending-navigation`. See [§Navigation blocking — pending-nav protocol](#navigation-blocking--pending-nav-protocol).

### Events

| Event | Purpose | Source |
|---|---|---|
| `:rf.route/navigate` | Programmatic navigation. | [§Navigation is an event](#navigation-is-an-event) |
| `:rf.route/handle-url-change` | Default handler for URL change (popstate / initial load / SSR). | [§URL changes are events](#url-changes-are-events) |
| `:rf/url-changed` | Fired by the runtime on every URL transition. | [§Standard runtime events](#standard-runtime-events) |
| `:rf/url-requested` | Fired by `route-link` and equivalent. Decides internal vs external navigation. | [§Standard runtime events](#standard-runtime-events) |
| `:rf.route/navigation-blocked` | Dispatched by the runtime when a `:can-leave` guard rejects. | [§Navigation blocking — pending-nav protocol](#navigation-blocking--pending-nav-protocol) |
| `:rf.route/continue` | User-dispatched: confirm pending navigation. | [§Navigation blocking — pending-nav protocol](#navigation-blocking--pending-nav-protocol) |
| `:rf.route/cancel` | User-dispatched: cancel pending navigation. | [§Navigation blocking — pending-nav protocol](#navigation-blocking--pending-nav-protocol) |

### Effects (`reg-fx`)

| Fx | Purpose | Platform |
|---|---|---|
| `:rf.nav/push-url` | `pushState` for the URL. | `:client` |
| `:rf.nav/replace-url` | `replaceState` for the URL. | `:client` |
| `:rf.nav/scroll` | Apply a scroll strategy. Args carry `{:strategy :from :to :saved-pos :fragment}`. | `:client` |
| `:rf.route/with-nav-token` | Threads `:nav-token` into a downstream dispatch for stale-result suppression. | universal |

### Subscriptions

| Sub | Returns |
|---|---|
| `:route` | The full `:route` map. |
| `:rf.route/id` | The active route id. |
| `:rf.route/params` | Path params. |
| `:rf.route/query` | Query params. |
| `:rf.route/fragment` | The URL `#fragment` or nil. |
| `:rf.route/transition` | `:idle` / `:loading` / `:error`. |
| `:rf.route/error` | Structured error map when `:transition = :error`. |
| `:rf.route/chain` | The `:parent`-chain of the active route. |
| `:rf/pending-navigation` | The pending-nav slot, or nil. |

### Pure helpers

- `(rf/match-url url)` → `{:route-id :params :query :fragment :validation-failed?}` or `nil`. Pure; JVM- and CLJS-runnable.
- `(rf/route-url route-id path-params [query-params [fragment]])` → URL string. Pure; JVM- and CLJS-runnable.

### Frame-level configuration

- `:url-bound?` on `reg-frame` metadata (default true for `:rf/default` only). Only one frame may own the URL. See [§Multi-frame routing](#multi-frame-routing).

### Schemas registered with the framework

- `:rf/route-pattern` — path-pattern grammar (see [Spec-Schemas.md](Spec-Schemas.md#rfroute-pattern)).
- `:rf/route-rank` — structural rank tuple (see [Spec-Schemas.md](Spec-Schemas.md#rfroute-rank)).
- `:rf/route-slice` — the `:route` slice shape (see [Spec-Schemas.md](Spec-Schemas.md#rfroute-slice)).
- `:rf/pending-navigation` — the pending-nav slot shape (see [Spec-Schemas.md §`:rf/pending-navigation`](Spec-Schemas.md#rfpending-navigation)).

### Trace events

Defined per the [009 Error contract](009-Instrumentation.md#error-contract):

- `:route.nav-token/allocated` — fresh nav-token cascade begins.
- `:route.nav-token/stale-suppressed` — async result carrying a now-superseded token.
- `:route.url/fragment-changed` — fragment-only URL update (the URL changed only in its `#fragment`; `:on-match` did not re-fire). Distinct from the runtime event `:rf/url-changed`, which fires on every URL transition.
- `:rf.route/navigation-blocked` — `:can-leave` guard rejected a navigation.
- `:rf.error/duplicate-url-binding` — second frame attempted `:url-bound? true` while another already owns the URL.
- `:rf.warning/route-shadowed-by-equal-score` — registration-time warning when ranking ties on rule 6.
- `:rf.warning/no-not-found-route` — runtime fell back to the built-in placeholder because `:rf.route/not-found` is not registered (per [§Route-not-found](#route-not-found--rfroutenot-found-canonical)).

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

(rf/reg-route :route/files
  {:doc    "A files browser; matches /files and any sub-path."
   :path   "/files/*rest"
   :params [:map [:rest :string]]})
```

#### Path-pattern grammar (canonical)

The `:path` value is a **string in the canonical path-pattern grammar** below. The grammar is **part of the pattern contract**, not implementation-specific. Every conforming implementation parses and emits this grammar; conformance fixtures (`routing-match-url.edn`, `routing-navigate.edn`) assume it.

The grammar is deliberately small. Five productions:

| Production | Syntax | Example | Captures |
|---|---|---|---|
| **Literal segment** | `/text` | `/articles` | nothing |
| **Named param** | `/:name` | `/articles/:id` | `{:id "..."}` (string by default; coerced via `:params` schema) |
| **Optional segment group** | `{/:name}?` or `{/literal}?` | `/articles/:id{/:slug}?` | param present only if matched |
| **Catch-all (splat)** | `/*name` | `/files/*rest` | `{:rest "everything/after"}` (string, includes embedded `/`) |
| **Root** | `/` | `/` | `{}` |

Rules:

1. **Param names** are unqualified keywords on the consumer side; in the pattern string they are bare identifiers (`:id`, not `::feature/id`). A route's `:params` schema (`[:map [:id :uuid]]`) names the same key.
2. **Optional groups** wrap a slash-prefixed sub-pattern in `{...}?`. They may contain literal segments, named params, or both. Nested optional groups are not part of the grammar.
3. **Splats** must be the final segment of the path. The captured value is a single string (slashes preserved). At most one splat per pattern.
4. **Trailing slashes** are normalised away by `match-url` before matching: `/cart` and `/cart/` resolve to the same match. `route-url` emits patterns without a trailing slash (except for the root pattern `/`).
5. **Case** is preserved as written; matching is case-sensitive by default. Implementations may offer a per-route `:case-insensitive? true` opt; the conformance corpus assumes case-sensitive matching.
6. **Reserved characters** (`:`, `*`, `{`, `}`, `?`) inside literal segments must be percent-encoded in the path string; `match-url` URL-decodes captured param values before they reach the handler.
7. The grammar **does not encode** query-string or fragment binding; see "Query strings and fragments" below for those.

The grammar is a small subset of common path-pattern syntaxes — straightforwardly implemented in any host (no parser-combinator library required). It is also a strict subset of RFC 6570 Level 1 plus a splat extension, which keeps it familiar to non-Clojure ecosystems. Other-language ports parse the same strings.

A canonical schema for path patterns is registered as `:rf/route-pattern` (see [Spec-Schemas.md](Spec-Schemas.md#rfroute-pattern)). Tooling can validate patterns at registration time.

> **Data-form path patterns (per host):** the same grammar can be expressed as a vector of segment values — `[:files [:* :rest]]` is the data-form of `/files/*rest`. This is the natural form in hosts without a string-parser library, and lines up with [Principles.md §Data is code](Principles.md#data-is-code). Ports are required to support the string grammar above; hosts may additionally accept a data form whose semantics are equivalent.

#### Route ranking algorithm

When more than one registered route can match the same URL, `match-url` MUST resolve the conflict using the **6-rule ranking cascade** below. The cascade is part of the pattern contract — every conforming implementation produces the same winner for the same registrations and URL. Without this lock, two implementations of `match-url` can both be "reasonable" and still disagree, defeating the cross-host conformance bar.

Ranking rules, evaluated in order. The first rule that distinguishes the candidates wins; later rules are only consulted on ties.

1. **More static segments beat fewer.** Count the literal (non-param, non-splat) segments in each candidate's `:path`. Higher count wins. `/users/me` (2 statics) beats `/users/:id` (1 static) for `/users/me`.
2. **Among equally-static-counted matches, longer paths beat shorter.** Total segment count breaks the tie on equal static-count. `/users/:id/edit` beats `/users/:id` for `/users/abc/edit`.
3. **Named params beat rest params.** A `:name` segment is more specific than a `*name` splat. `/files/:name` beats `/files/*rest` for `/files/x`.
4. **Rest params beat catch-all/not-found.** A `*rest` segment is more specific than a top-level catch-all `/*` (or a registered `:rf.route/not-found`). `/files/*rest` beats `/*` for `/files/x/y`.
5. **Exact routes beat optional-group routes.** A pattern with no `{...}?` group is more specific than a pattern that matches the same URL only by virtue of an optional group. `/about` beats `/{:base}?/about` for `/about`.
6. **Registration order is the final tiebreak only if every structural score is equal.** When two routes are *structurally indistinguishable* (same statics, same length, same params/splats, same optional groups), the route registered first wins. This case is **discouraged** — implementations MUST emit a `:rf.warning/route-shadowed-by-equal-score` warning at registration time when a new route is added and an existing route has an equal structural score on the same URL family. Tooling and AI scaffolds use the warning to flag potential conflicts.

The cascade is **structural** — the score is computable from each pattern's parsed shape; no URL is needed to compute it. Implementations may pre-compute every registered route's score at registration time and rank candidates by score on each `match-url` call.

```text
;; Pseudocode
(defn route-rank [pattern]
  (let [segments       (parse-segments pattern)
        static-count   (count (filter literal? segments))
        total-length   (count segments)
        has-splat?     (some splat? segments)
        has-optional?  (some optional-group? segments)
        is-catch-all?  (= pattern "/*")]
    ;; Higher score = more specific. Tuple compares lexicographically.
    [static-count                 ;; rule 1
     total-length                 ;; rule 2
     (if has-splat? 0 1)          ;; rule 3 — named params beat splats
     (if is-catch-all? 0 1)       ;; rule 4 — rest beats catch-all
     (if has-optional? 0 1)       ;; rule 5 — exact beats optional-group
     ;; rule 6 — registration order — applied externally as a stable sort
     ]))

(defn match-url [url]
  (->> (registered-routes)
       (filter #(pattern-matches? % url))
       (sort-by route-rank #(compare %2 %1))    ;; descending; rule 6 stable-sort
       first
       ;; ... extract params, query, validate ...
       ))
```

**`:rf/route-rank`** is registered as a spec-internal schema (see [Spec-Schemas.md](Spec-Schemas.md#rfroute-rank)) so tooling can read each route's rank vector via `(rf/handler-meta :route route-id)` (under a `:rf.route/rank` slot the registrar attaches at registration time).

**Conformance.** Fixture `route-ranking-precedence.edn` exercises every cascade rule with deliberately-overlapping registrations and asserts the same winner across implementations. Hosts that register routes in a different *internal* order MUST sort by registration *time* (the order user code called `reg-route`) for rule 6, not by hash-map iteration order.

**Why this is correctness, not polish.** Without a defined ranking, a CLJS implementation and a JS implementation of `match-url` can each be self-consistent and still disagree on which route wins for `/users/me` when both `/users/me` and `/users/:id` are registered. The conformance corpus depends on a single deterministic answer; ranking is the lock.

#### Other pattern-level requirements

- The path is **parseable both ways**: a path-pattern matched against an incoming URL produces a params map; a route-id + params map produces a URL.
- The params shape is described by the host's idiom (Malli for CLJS dynamic; types for static hosts; per [000-Vision.md](000-Vision.md) on the schema/type duality).
- Routes are **stably-id'd**, queryable via `(rf/handlers :route)`, source-coordinated.
- Route metadata is an **open map**. The pattern reserves a small set of keys (see "Reserved route-metadata keys" below); hosts and applications may add their own keys (e.g. `:myapp/analytics-id`, `:myapp/layout`) under a chosen namespace. Interceptors, guards, layouts, and analytics tooling read those keys via `(rf/handler-meta :route route-id)`.

#### Reserved route-metadata keys

The pattern reserves these keys on `reg-route`'s metadata map. All are optional except `:path`.

| Key | Type | Purpose |
|---|---|---|
| `:doc` | string | Human-readable description. |
| `:path` | string (path-pattern grammar above) | The URL pattern. Required. |
| `:params` | schema | Schema for **path** params (those captured by `:name` / `*name` segments in `:path`). |
| `:query` | schema | Schema for **search/query** params (key-value pairs after `?`). Distinct from `:params`. See "Query strings and fragments". |
| `:query-defaults` | map | Default values for query-string keys when absent from the URL. Applied during `match-url`. See "Query strings and fragments". |
| `:query-retain` | set of keywords | Query-string keys that should be carried through subsequent navigations even when the caller did not supply them. See "Query strings and fragments". |
| `:tags` | set of keywords | User-defined tags (e.g. `:requires-auth`); read by interceptors. |
| `:parent` | route-id | Parent route id (for the nested-layout convention; see "Nested layouts"). |
| `:on-match` | vector of event vectors | Events the runtime dispatches every time this route becomes the active route (server- and client-side). See "Per-route data loading". |
| `:on-error` | event vector | Event the runtime dispatches if any `:on-match` event errors. See "Per-route error handling". |
| `:scroll` | enum or map | Declarative scroll behaviour on entering this route. See "Scroll restoration". |

### The `:route` slice

The runtime maintains a single slice in `app-db` under the `:route` key:

```clojure
{:route
  {:id           :route/article             ;; current route id
   :params       {:id #uuid "..."}          ;; path params (matches :params schema)
   :query        {:q "clojure" :page 2}     ;; query/search params (matches :query schema)
   :fragment     "section-2"                ;; URL #fragment, or nil; see "Fragments" below
   :transition   :idle                      ;; :idle | :loading | :error
   :error        nil                        ;; populated when :transition = :error
   :nav-token    "nav-42"}}                 ;; per-navigation epoch token; see "Navigation tokens"
```

`:params` and `:query` are **separate maps**. Path params come from segments captured by the `:path` grammar; query params come from the `?key=value` portion of the URL. They are validated by separate schemas (`:params` and `:query` on `reg-route`). Consumers that prefer a single merged map can build one in a derived sub.

`:fragment` carries the URL `#fragment` part (per "Fragments" below). `:nav-token` is the runtime-allocated navigation epoch (per "Navigation tokens — stale-result suppression" below). Both are runtime-managed; user code reads them through subs.

`:transition` is a tiny FSM driven by the runtime: `:idle` when no navigation is in flight; `:loading` while the active route's `:on-match` events are draining; `:error` if any `:on-match` event errors. See "Per-route data loading" and "Per-route error handling" below.

A canonical schema for the slice is registered as `:rf/route-slice` (see [Spec-Schemas.md](Spec-Schemas.md#rfroute-slice)).

### Navigation is an event

```clojure
(rf/reg-event-fx :rf.route/navigate
  {:doc  "Navigate to a registered route."
   :spec [:cat [:= :rf.route/navigate] [:or :keyword [:map [:url :string]]]
                                    [:? :map]      ;; params
                                    [:? :map]]}    ;; opts
  (fn handler-route-navigate [{:keys [db]} [_ target params opts]]
    (let [{:keys [route-id path-params query-params fragment]} (resolve-target target params opts db)
          route-meta (rf/handler-meta :route route-id)
          url        (rf/route-url route-id path-params query-params fragment)
          push-fx-id (if (:replace? opts) :rf.nav/replace-url :rf.nav/push-url)
          nav-token  (rf/gen-nav-token)]
      {:db (-> db
               (assoc :route {:id         route-id
                              :params     path-params
                              :query      query-params
                              :fragment   fragment
                              :transition (if (seq (:on-match route-meta)) :loading :idle)
                              :error      nil
                              :nav-token  nav-token}))
       :fx (into [[push-fx-id url]
                  [:rf/trace [:route.nav-token/allocated {:route-id route-id :nav-token nav-token}]]
                  (when-let [scroll (resolve-scroll route-meta opts fragment)]
                    [:rf.nav/scroll scroll])]
                 ;; per-route :on-match dispatches (see "Per-route data loading")
                 (for [ev (:on-match route-meta)]
                   [:dispatch ev]))})))
```

Three effect categories flow:
1. `app-db`'s `:route` slice is updated (id, params, query, fragment, transition, nav-token).
2. The browser URL is pushed via `:rf.nav/push-url` (a registered fx; `:platforms #{:client}`), or replaced via `:rf.nav/replace-url` when `opts` has `:replace? true`.
3. The route's `:on-match` events (if any) are dispatched, and the route's `:scroll` strategy (if any) is emitted as a `:rf.nav/scroll` effect.

The order is locked: state changes first, URL update second, then `:on-match` dispatches and scroll effect. If the URL update fails (browser denies, user is offline) the state is still consistent.

The trailing `opts` map is open. The pattern recognises:
- `:replace?` (use `replaceState` rather than `pushState` — for redirects, search-as-you-type filters, login-flow returns where the back button should not return to the intermediate URL).
- `:scroll` (per-call override of the route's `:scroll` metadata; same enum/map shape, see "Scroll restoration").
- `:fragment` (target `#fragment` for the new URL; see "Fragments" below). May also be supplied as `:fragment` on the target map.
- Hosts may add their own keys under a chosen namespace.

#### Target form — route-id vs URL-string

`:rf.route/navigate`'s second arg is one of two forms (per the schema's `[:or :keyword [:map [:url :string]]]`):

- **Route-id (canonical):** `(rf/dispatch [:rf.route/navigate :route/cart {:cart-id 42}])`. The runtime resolves the route, builds the URL via `route-url`, and pushes. This is the form Construction-Prompts and well-formed app code use.
- **URL-string (escape hatch):** `(rf/dispatch [:rf.route/navigate {:url "/some/path"}])`. For dynamic or user-supplied URLs the app didn't build itself — deep-link handlers, server-redirect targets, programmatic redirects from a string. The runtime calls `match-url` on the string; if it resolves to a registered route, navigation proceeds normally. URL-strings that don't match any registered route resolve to `:rf.route/not-found` with the URL in `params`.

The route-id form is preferred everywhere it can be used because the route-id is enumerable, refactorable, and queryable through the registrar. URL-strings are stringly-typed escape-hatchy by nature; tooling can flag them as candidates for migration to a registered route-id when the URL pattern is known.

### URL changes are events

When the user clicks a link, presses Back/Forward, or arrives via a deep link, the runtime fires the canonical event `:rf/url-changed` (the pattern's `onUrlChange` analogue per Elm's Browser.application; see "Standard runtime events" below). The default handler is `:rf.route/handle-url-change`:

```clojure
(rf/reg-event-fx :rf.route/handle-url-change
  {:doc       "Triggered by URL change (popstate or initial load). Sets app-db's route slice from the URL."
   :platforms #{:client :server}}                 ;; same handler is used by SSR
  (fn handler-route-handle-url-change [{:keys [db]} [_ url]]
    (let [{:keys [route-id params query fragment validation-failed?]} (rf/match-url url)
          route-meta                                                  (rf/handler-meta :route route-id)
          prev-route                                                  (:route db)
          fragment-only?                                              (and prev-route
                                                                           (= route-id (:id prev-route))
                                                                           (= params   (:params prev-route))
                                                                           (= query    (:query prev-route))
                                                                           (not= fragment (:fragment prev-route)))
          nav-token                                                   (if fragment-only?
                                                                        (:nav-token prev-route)         ;; fragment-only does not advance the epoch
                                                                        (rf/gen-nav-token))]            ;; fresh epoch
      (cond
        ;; No match → 404 route
        (nil? route-id)
        {:db (assoc db :route {:id :rf.route/not-found
                               :params {:url url}
                               :query {} :fragment fragment
                               :transition :idle :error nil
                               :nav-token nav-token})}

        ;; Validation failure → 404 (or, optionally, a configured error route)
        validation-failed?
        {:db (assoc db :route {:id :rf.route/not-found
                               :params {:url url :reason :validation}
                               :query {} :fragment fragment
                               :transition :idle :error nil
                               :nav-token nav-token})}

        ;; Fragment-only change — update the slice; emit :route.url/fragment-changed
        ;; trace; do NOT re-fire :on-match. See "Fragments" below.
        fragment-only?
        {:db (assoc-in db [:route :fragment] fragment)
         :fx [[:rf/trace [:route.url/fragment-changed {:route-id route-id
                                                       :prev-fragment (:fragment prev-route)
                                                       :next-fragment fragment}]]]}

        :else
        {:db (assoc db :route {:id         route-id
                               :params     params
                               :query      query
                               :fragment   fragment
                               :transition (if (seq (:on-match route-meta)) :loading :idle)
                               :error      nil
                               :nav-token  nav-token})
         :fx (into [[:rf/trace [:route.nav-token/allocated {:route-id route-id :nav-token nav-token}]]]
                   (for [ev (:on-match route-meta)]
                     [:dispatch ev]))}))))
```

The same handler runs **on the server during SSR** (no `:platforms` exclusion) — the request URL is fed in, the route slice is set, the view renders against it. The `:on-match` events also fire server-side, populating server-rendered data the same way they would client-side. No SSR-specific routing code.

### Linking from views — plain-anchor semantics

> **Lock: the runtime does not auto-intercept `<a>` clicks. Click interception is the host adapter's job.**

| Form | Behaviour |
|---|---|
| `[rf/route-link {:to :route/cart} "Cart"]` | Renders `<a href="...">` and intercepts plain primary-button clicks itself — its registered view body (per [§Standard runtime events](#standard-runtime-events)) calls `.preventDefault` and dispatches `:rf/url-requested`. Modifier keys (cmd-click, middle-click, shift-click) defer to the browser; the link follows the `href` natively. |
| `[:a {:href "..."} ...]` (plain anchor in user view code) | Browser-native navigation. The runtime does **not** intercept; clicking causes a full page load if the URL is on the same origin and an external navigation otherwise. Apps that want SPA-style interception on plain anchors install it at the **host adapter** layer (a top-level `click` listener on the document that consults `match-url`); the runtime's contract stops at `route-link` plus `:rf/url-requested`. |

**Why the runtime doesn't auto-intercept.** A global `click` listener that calls `match-url` on every link is a host concern (DOM-bound, browser-only, conflicts with non-routed `<a>` tags inside iframes / shadow DOM / third-party widgets). The host adapter has the context to install or skip it; the runtime stays portable.

Users who want plain anchors to be interceptable register their own delegating handler at the host layer, dispatching `:rf/url-requested` on match — this re-uses the same decision-point event the runtime already exposes, so the test surface and policy are unchanged.

### Reading the route is a sub

```clojure
(rf/reg-sub :route
  {:doc "The current route map: {:id :params :query :transition :error}"}
  (fn sub-route [db _] (:route db)))

(rf/reg-sub :rf.route/id
  :<- [:route]
  (fn [route _] (:id route)))

(rf/reg-sub :rf.route/params
  :<- [:route]
  (fn [route _] (:params route)))

(rf/reg-sub :rf.route/query
  :<- [:route]
  (fn [route _] (:query route)))

(rf/reg-sub :rf.route/fragment
  :<- [:route]
  (fn [route _] (:fragment route)))     ;; URL #fragment string, or nil

(rf/reg-sub :rf.route/transition
  :<- [:route]
  (fn [route _] (:transition route)))    ;; :idle | :loading | :error

(rf/reg-sub :rf.route/error
  :<- [:route]
  (fn [route _] (:error route)))

(rf/reg-sub :rf/pending-navigation
  (fn [db _] (:rf/pending-navigation db)))   ;; pending-nav slot when :can-leave guard rejects, else nil
```

Views derive UI from the route the same way they derive UI from any other state — no special routing API in views. A common pattern: a global progress bar reads `:rf.route/transition` and renders when the value is `:loading`; an error banner reads `:rf.route/error`.

### The root view dispatches on `:rf.route/id`

```clojure
(rf/reg-view app-root []
  (case @(subscribe [:rf.route/id])
    :route/home              [home-page]
    :route/cart              [cart-page]
    :route/cart.item-detail  [cart-item-detail]
    :route/article           [article-page]
    :rf.route/not-found      [not-found-page]))
```

Pattern: a single `case` (or equivalent) over the route id at the top of the tree. Per-route views can subscribe to `:rf.route/params` for their own data needs.

### Bidirectional URL ↔ params

Two pure helpers, both registered, both queryable:

- `(rf/match-url url)` → `{:route-id :keyword :params {...} :query {...} :fragment <string-or-nil> :validation-failed? boolean}` or `nil`.
  - Returns `nil` when no path-pattern matches the URL at all.
  - Returns the match map when *some* route's path-pattern matches. The `:params` map carries the captured **path** params (post-coercion against the route's `:params` schema, when one is present). The `:query` map carries the parsed **query-string** params, with `:query-defaults` filled in for absent keys and the route's `:query` schema applied for coercion (e.g. `"2"` → `2` for an `:int` field). The `:fragment` field carries the URL's `#fragment` portion (string or `nil` if absent); see "Fragments" below.
  - If schema validation fails (path params don't conform to `:params`, or query params don't conform to `:query`), the map carries `:validation-failed? true` plus a `:validation-error` field with the schema-explanation (per [Spec 010](010-Schemas.md)). The runtime's `:rf.route/handle-url-change` event treats validation-failure the same as no-match: it routes to `:rf.route/not-found` with the URL in params.
  - Pure; runs on JVM and CLJS.
- `(rf/route-url route-id path-params)` / `(rf/route-url route-id path-params query-params)` / `(rf/route-url route-id path-params query-params fragment)` → URL string. **Pure**; runs on JVM and CLJS.
  - Builds the URL from the `:path` template, substituting path params, then appends `?key=value&...` for any `query-params`, then appends `#fragment` if the 4-arity `fragment` arg is supplied (and non-nil/non-empty).
  - **Does not navigate.** It is a string-builder; there is no side-effect on `app-db`, no `pushState`, no dispatch. To navigate, dispatch `:rf.route/navigate` (which uses `route-url` internally).
  - **Does not read `app-db`.** Inputs are the registered route table (static) and the caller-supplied params/query/fragment. Same inputs always produce the same string output.
  - **`:query-retain` is NOT applied here.** Carrying through retained keys (`:theme`, `:locale`, etc.) is an `app-db`-aware concern — `:rf.route/navigate`'s handler reads the *current* `:rf.route/query` slice and merges retained keys into the outgoing query *before* calling `route-url`. Callers that want the same merge behaviour without going through `:rf.route/navigate` use the helper `(rf/route-url-with-retain route-id path-params query-params fragment {:db db})` (impure: takes a `db` value), or perform the merge themselves. Keeping `route-url` pure is the lock — it is the function the conformance corpus and the SSR pipeline call without an `app-db` in hand.
  - Throws `:rf.error/route-url-validation` if `path-params` doesn't conform to the route's `:params` schema, or `query-params` doesn't conform to the route's `:query` schema (caller bug; not user input).

Both work against the same registered route table, so adding/removing a route updates both directions automatically.

#### Param validation at the call site

The two boundaries where route params enter the runtime — **programmatic navigation** (`route-url` / `:rf.route/navigate`) and **URL-driven navigation** (`match-url`) — validate against the route's `:params` and `:query` schemas, with different failure modes on each side.

| Boundary | Source | Validation failure |
|---|---|---|
| **Programmatic** — `(route-url route-id path-params query-params)` | Caller supplies the params map directly. | **Throws** `:rf.error/route-url-validation` (caller bug; not user input). The schema-explanation is on the exception's data; the trace event is emitted at the same time. |
| **Programmatic** — `[:rf.route/navigate target params opts]` | Caller dispatches an event. | The event-boundary validation interceptor runs the route's `:params` / `:query` schema on the supplied maps **before transitioning**. Failure emits `:rf.error/schema-validation-failure` (per [009 §Error categories](009-Instrumentation.md#error-categories-initial-set), `:where :event`) and the navigation is **rejected** — the `:route` slice does not change. |
| **URL-driven** — `(match-url url)` | Browser URL (popstate, link click, deep link). | `:validation-failed? true` in the result; `:rf.route/handle-url-change` routes to `:rf.route/not-found` with `:reason :validation`. |

The asymmetry is deliberate. Programmatic navigation is *caller code* — schema failures are bugs and should be surfaced loudly (throw / reject). URL-driven navigation is *user input* — schema failures are 404s, not exceptions. Both paths share the same `:params` / `:query` schemas (per [Spec 010](010-Schemas.md)), so a route that compiles cleanly with one validates the same way against the other.

The event-boundary validation for `:rf.route/navigate` is a re-use of the standard schema-validation interceptor (the `:spec` slot on the `reg-event-fx` registration) — no routing-specific machinery.

## Per-route data loading

A route may declare a vector of events the runtime dispatches whenever the route becomes active. This is the pattern's **declarative loader**. The mechanism is purely event-driven; no new effect substrate.

```clojure
(rf/reg-route :route/cart
  {:doc      "The cart page."
   :path     "/cart"
   :on-match [[:cart/load-items]
              [:user/load-prefs]]})
```

Semantics:

1. When `:rf.route/handle-url-change` (URL-driven) or `:rf.route/navigate` (programmatic) makes this route the active route, the runtime dispatches each event in `:on-match`, **in order**, after writing the `:route` slice and before any view renders that depend on the loaded data.
2. The runtime sets `:rf.route/transition` to `:loading` while these dispatches drain, and back to `:idle` when they complete (per the run-to-completion drain semantics, this is observable through trace events; see [009](009-Instrumentation.md)).
3. Same-route-id navigations with **changed `:params` or `:query`** *do* re-fire `:on-match` (the route is becoming active again under new inputs). Same-route-id navigations with identical params do not re-fire — the runtime compares the post-update `:route` slice against the pre-update slice and skips dispatch when nothing relevant changed.
4. `:on-match` events run **server- and client-side**. SSR populates server-rendered data via the same vector. Hydration does *not* re-fire `:on-match` events — the seeded `app-db` already contains the data.
5. Each `:on-match` event is an ordinary event vector. Handlers may emit any `:fx` (typically `:http`, etc.). The events are also enumerable: `(rf/handler-meta :route :route/cart)` returns the metadata, so tooling can render route-loading dependency graphs.

The `:on-match` list is the **enumerable, machine-readable** answer to "what loads when this route is active?" `:on-match` is the canonical surface.

> **Why not parameterise events explicitly with route params?** Each `:on-match` event runs with full access to `app-db` via cofx, including the freshly-written `:route` slice. Handlers read `(:route db)` for params/query as needed. Hard-wiring param substitution into the event vector would re-introduce a string-DSL where data already suffices.

## Route-not-found — `:rf.route/not-found` (canonical)

`:rf.route/not-found` is a **special-cased route id** the runtime dispatches to whenever a URL fails to match any registered route. It is **registered by the user**, exactly like any other route — the runtime does not auto-register it; the framework's only special-casing is the *target id* it routes to on no-match. This keeps not-found rendering, head metadata, and `:on-match` events behaving identically to any other route.

```clojure
(rf/reg-route :rf.route/not-found
  {:doc      "404 page."
   :path     "/404"                     ;; required, but rarely matched directly — the runtime
                                          ;; routes URL-driven misses here regardless of :path
   :on-match [[:analytics/log-404]]
   :scroll   :top})
```

Semantics:

1. **Trigger.** When `match-url` returns `nil` (no path-pattern matches), or when validation failure routes to "not found" (per [§Param validation at the call site](#param-validation-at-the-call-site)), the runtime sets `:route` to `{:id :rf.route/not-found :params {:url <url>} ...}` and proceeds with that route's `:on-match` events.
2. **Same machinery.** `:rf.route/not-found` is an ordinary `reg-route`. It can declare `:on-match`, `:on-error`, `:scroll`, `:head`, `:tags` — all behave normally. The view tree's `case` over `:rf.route/id` renders the not-found view from the leaf.
3. **Required by contract.** Apps **must** register a `:rf.route/not-found` route. If no `:rf.route/not-found` is registered when an unmatched URL arrives, the runtime emits a `:rf.warning/no-not-found-route` trace event and falls back to a built-in placeholder view (a minimal `<h1>Not Found</h1>` page) so the request still produces a response. Test fixtures and the conformance corpus assume the user-registered shape.
4. **Validation failures.** A URL that matches a route's path but fails the route's `:params` / `:query` schema also routes to `:rf.route/not-found`, with `:reason :validation` in the `:params` slice (per [§URL changes are events](#url-changes-are-events)).
5. **Reserved id.** `:rf.route/not-found` is the **single locked id** for this purpose. Implementations and tools depend on it; users do not redefine the meaning of the keyword. Hosts that want a different visual treatment per error kind branch inside the `:rf.route/not-found` view (e.g., on `:reason`).

Tooling enumerates `(rf/handler-meta :route :rf.route/not-found)` to confirm the route is registered; the registrar emits the warning trace event at the first unmatched URL if it isn't.

## Per-route error handling

If any event in `:on-match` errors (a handler throws, a registered fx errors, or a downstream handler errors during the drain — per [009](009-Instrumentation.md)'s structured error contract), the runtime:

1. Sets `:rf.route/transition` to `:error`.
2. Populates `:rf.route/error` with the structured error map (schema: `:rf/error` per [009](009-Instrumentation.md#error-contract)).
3. If the route declares an `:on-error` event, dispatches it. The error map is available to the handler via `(:error (:route db))`.

```clojure
(rf/reg-route :route/cart
  {:path     "/cart"
   :on-match [[:cart/load-items]]
   :on-error [:route/cart-load-failed]})

(rf/reg-event-fx :route/cart-load-failed
  (fn [{:keys [db]} _]
    (let [error (get-in db [:route :error])]
      ;; surface a contextual error UI; toast; redirect; whatever the app needs.
      {:db (assoc-in db [:cart :load-error] (:rf.error/message error))})))
```

`:on-error` is **route-scoped** error handling, layered over [009](009-Instrumentation.md)'s structured error contract — it doesn't replace it. The structured error trace event still fires; `:on-error` is the route's response to it. Routes without an `:on-error` slot leave `:rf.route/transition :error` set; views may inspect `:rf.route/error` and render an error banner.

## Navigation tokens — stale-result suppression

When a route is loading and the user navigates away before the load completes, the older load's result can land *after* the user has moved on, clobbering newer state. This is a real bug class — React Router and TanStack Router both explicitly handle it. Re-frame2's answer is the **navigation-token (nav-token) epoch**: a per-navigation token allocated when a route becomes active, carried by every async result, and validated on receipt. This is the same idiom used by `:after` timers per [005 §Epoch-based stale detection](005-StateMachines.md#epoch-based-stale-detection); see also the cross-cutting [Pattern-StaleDetection.md](Pattern-StaleDetection.md) for why the pattern recurs.

### Mechanism

1. **Allocation.** When `:rf/url-changed` fires (URL-driven) or `:rf.route/navigate` runs (programmatic), the default handler allocates a fresh `:nav-token` (a gensym or monotonic counter) and writes it to the `:route` slice alongside the new id/params/query/fragment.
2. **Carry.** Each `:on-match` dispatch receives the current `:nav-token` in cofx (under the key `:nav-token`):

   ```clojure
   ;; cofx of :on-match handlers
   {:db        ...
    :event     [:cart/load-items]
    :nav-token "nav-42"}                       ;; the token at scheduling time
   ```

3. **Threading.** Async completions either (a) carry the token in their follow-up event payload, or (b) use the framework-supplied `:rf.route/with-nav-token` fx wrapper which threads the token into the dispatched continuation:

   ```clojure
   {:fx [[:rf.route/with-nav-token
          {:do        [:dispatch [:cart/items-loaded items]]
           :nav-token (:nav-token cofx)}]]}
   ```

4. **Validation.** When the receiving handler runs, the framework-provided `:nav-token` cofx checks the carried token against the *current* `:route` slice's `:nav-token`:
   - **Match.** The token is current; the result is committed normally.
   - **Mismatch.** The token has been superseded; the runtime emits `:route.nav-token/stale-suppressed` (with `:tags {:carried-token <t1> :current-token <t2> :event-id <id>}`) and the handler does NOT run — no `:db` write, no `:fx`, no transition.

The validating cofx is shared infrastructure: any handler whose registration declares it (or any handler reached via `:rf.route/with-nav-token`) is automatically protected.

### What the slice looks like over time

```clojure
;; Step 1: User navigates to :route/article id="A". nav-token = "nav-1".
{:route {:id :route/article :params {:id "A"} :transition :loading :nav-token "nav-1"}}

;; Step 2: While the load is in flight, user navigates to :route/article id="B".
;; A fresh nav-token is allocated.
{:route {:id :route/article :params {:id "B"} :transition :loading :nav-token "nav-2"}}

;; Step 3: The "A" load completes; its dispatched [:article/loaded "A" payload] carries
;; nav-token "nav-1". Current is "nav-2". Mismatch → suppressed; trace fires; no commit.

;; Step 4: The "B" load completes; carries "nav-2". Match → commit.
{:route {:id :route/article :params {:id "B"} :transition :idle :nav-token "nav-2"}}
```

### Cancellation as optimisation, not correctness

Suppression alone fixes the user-visible bug — the older load *does* complete and *does* dispatch its event, but its result is silently discarded at the validation cofx. Hosts that support abortable fetches (`AbortController` in JS, etc.) MAY *additionally* abort in-flight work for superseded tokens to save bandwidth — but the conformance contract only requires suppression, not cancellation. This matches the `:after` story per [005 §Epoch-based stale detection](005-StateMachines.md#epoch-based-stale-detection).

### Trace events

Two trace events surround the nav-token lifecycle (added to the trace-op vocabulary per [Spec-Schemas.md](Spec-Schemas.md#rftrace-event)):

- **`:route.nav-token/allocated`** — emitted when a navigation cascade allocates a fresh token. `:tags {:route-id <id> :nav-token <token>}`.
- **`:route.nav-token/stale-suppressed`** — emitted when an async result arrives carrying a now-superseded token. `:tags {:carried-token <t1> :current-token <t2> :event-id <id>}`. The handler does NOT run.

Naming follows the `<feature>/<reason>` convention used by `:rf.machine.timer/stale-after`. See [Pattern-StaleDetection.md](Pattern-StaleDetection.md) for the cross-cutting pattern.

### Conformance

Fixture `route-stale-nav-token-suppression.edn` exercises the canonical race: load route A; navigate to route B before A finishes; A finishes; verify the late result is suppressed and the trace shows `:route.nav-token/stale-suppressed`.

## Standard runtime events

Two named events are part of the routing contract. Implementations register them; user code dispatches them; tests can fire them directly.

| Event | When it fires | Default handler |
|---|---|---|
| `:rf/url-changed` | The browser URL has changed (popstate, initial load, server-side request URL). The runtime dispatches this on every URL transition. | The default handler (the runtime registers it) is `:rf.route/handle-url-change`. Users can override by re-registering. |
| `:rf/url-requested` | The user clicked a link the framework owns (a `route-link` view, or any `<a>` whose `href` resolved to a registered route). The handler decides: navigate internally (dispatch `:rf.route/navigate`) or let the browser follow the link externally (dispatch `:rf.nav/external` or do nothing). | The default handler classifies internal vs external by feeding the URL to `match-url`; matched URLs become `:rf.route/navigate`, unmatched become external. Users can override to enforce per-frame policy (auth-guard, modifier-key handling, etc.). |

These events are the **decision points** for navigation policy. The policy is enumerable and testable: dispatch `[:rf/url-requested {:url "/cart"}]` from a test, observe the resulting `:rf.route/navigate`, no DOM simulation required.

`route-link`'s body becomes:

```clojure
(rf/reg-view route-link [{:keys [to params query]} & children]
  (let [url (rf/route-url to (or params {}) (or query {}))]
    [:a {:href     url
         :on-click (fn [e]
                     (when (plain-left-click? e)        ;; no modifier keys
                       (.preventDefault e)
                       (dispatch [:rf/url-requested {:url url :to to :params params :query query}])))}
     children]))
```

## Scroll restoration

Browser-default behaviour on `popstate` restores scroll position. For SPA-controlled scroll (e.g., scroll to top on forward navigation, restore on back), declare a `:scroll` strategy on the route or pass `:scroll` in the `:rf.route/navigate` opts.

The `:scroll` value is one of:

| Value | Behaviour |
|---|---|
| `:top` | Scroll to top of page (`window.scrollTo(0,0)`). |
| `:restore` | Restore the saved scroll position for this URL (the runtime captures positions on every navigation; SSR-side: no-op). |
| `:preserve` | Do nothing (current scroll position stays as is). |
| `nil` / absent | Same as `:preserve`. |
| map | Hosts may supply additional shapes (e.g. `{:to :element :selector "#article"}`); see "Custom scroll strategies" below. |

Resolution order at navigation time:
1. `:scroll` key in `:rf.route/navigate`'s `opts` map (per-call override). Wins.
2. `:scroll` key on the route's metadata.
3. Implicit default: `:top` for forward navigation, `:restore` for popstate-driven navigation.

When a `:rf.nav/scroll` effect is emitted, its args carry both the strategy and the from/to context: `[:rf.nav/scroll {:strategy :top :from from-route :to to-route :saved-pos saved :fragment <s-or-nil>}]`. The registered fx interprets the strategy. The `:saved-pos` field is captured by the runtime on every navigation (a small in-memory map URL → `[x y]`); on `popstate`, the runtime supplies the saved value. The `:fragment` field is the URL's `#fragment`, when present (per "Fragments" below); the standard strategies use it as described in "Fragments §`:rf.nav/scroll` integration".

```clojure
(rf/reg-fx :rf.nav/scroll
  {:platforms #{:client}}
  (fn fx-nav-scroll [_m {:keys [strategy from to saved-pos fragment]}]
    (case strategy
      :top      (if-let [el (and fragment (.getElementById js/document fragment))]
                  (.scrollIntoView el)
                  (.scrollTo js/window 0 0))
      :restore  (when saved-pos
                  (.scrollTo js/window (first saved-pos) (second saved-pos)))
      :preserve nil)))
```

> **Custom scroll strategies:** the `:scroll` value may be a map, allowing applications to register named scroll-strategies (a small registry on the implementation side). The contract requires the three enum values `:top`, `:restore`, `:preserve`.

## Query strings and fragments

The path syntax is the *primary* binding. Query strings are bound separately via the route's `:query` metadata key, which carries a schema for query-string coercion and validation (per Spec 010).

```clojure
(rf/reg-route :route/search
  {:path            "/search"
   :query           [:map [:q :string] [:page {:optional true} :int]]
   :query-defaults  {:page 1}
   :query-retain    #{:theme :locale}})

;; URL: /search?q=clojure&page=2
;; match-url yields:
;;   {:route-id :route/search :params {} :query {:q "clojure" :page 2}}
;;
;; URL: /search?q=clojure  (page absent; default applied)
;; match-url yields:
;;   {:route-id :route/search :params {} :query {:q "clojure" :page 1}}
```

**Path params (`:params`)** and **query params (`:query`)** are distinct concepts:

| | Path params | Query params |
|---|---|---|
| Source | `:name` / `*name` segments in `:path` | `?key=value&...` after the path |
| Schema slot | `:params` | `:query` |
| In `:route` slice | `(:params (:route db))` | `(:query (:route db))` |
| Required by URL? | Yes (URL doesn't match without them) | No (every key is optional from the URL's perspective) |
| Defaults | n/a (absence = no match) | `:query-defaults` map |

`:query-defaults` populates absent query keys at match time. `:query-retain` is a set of keys that should be **carried through subsequent navigations** even when the caller didn't supply them — useful for global state encoded in the URL (`:theme`, `:locale`, `:debug`). The merge is performed inside `:rf.route/navigate`'s handler (which has access to `app-db` and reads the current `:rf.route/query` slice) before `route-url` is called; `route-url` itself is pure and does not consult `app-db` (per [§Bidirectional URL ↔ params](#bidirectional-url--params)). The result: a `[:rf.route/navigate :route/cart]` from a search page preserves `?theme=dark`.

Coercion is data-shaped (the `:query` schema is the coercion specification — `:int` coerces `"2"` → `2`); per-key middleware functions are not part of the contract — data over functions.

## Fragments

The URL `#fragment` is a first-class part of the routing contract — anchor navigation, scroll-to-section, settings-tab selection, and SSR-safe in-page navigation all depend on it being explicit data flowing through events rather than a `window.location.hash` read in view code.

### Fragment in the slice

The `:route` slice carries `:fragment` (string or `nil`):

```clojure
{:route {:id       :route/docs
         :params   {:page "routing"}
         :query    {}
         :fragment "scroll-restoration"
         ...}}
```

Read it via the `:rf.route/fragment` sub. Fragment is **populated by `match-url` from the URL**, written to the slice by `:rf.route/handle-url-change`, and emitted by `route-url` when the 4-arity form is used (or when `:rf.route/navigate` is called with a `:fragment` opt or target-map key).

### Fragment-only changes do NOT re-fire `:on-match`

When the new URL differs from the current URL **only** in its fragment (same `:route-id`, same `:params`, same `:query`, but different `:fragment`), the runtime:

1. Updates `:fragment` in the `:route` slice.
2. Emits a `:route.url/fragment-changed` trace event with `:tags {:route-id <id> :prev-fragment <s> :next-fragment <s>}`.
3. Does **NOT** allocate a new `:nav-token`.
4. Does **NOT** re-fire `:on-match`.

The reason: `:on-match` exists to re-load route-scoped data when path or query changes. A fragment-only change does not change loaded data — only the in-page anchor target. Re-firing the loaders would re-fetch unchanged data on every `#section` jump, which is exactly the kind of thrash users complain about.

Views that need to react to fragment changes subscribe to `:rf.route/fragment` (or to `:route` for the whole slice). The `:rf/url-changed` event still fires for fragment-only changes — the surface for "the URL is now different" — but `:rf.route/handle-url-change`'s default behaviour distinguishes the cases.

### `:rf.nav/scroll` integration

When a fragment is present and the resolved scroll strategy is one of the standard strategies (`:top`, `:restore`, `:preserve`), the `:rf.nav/scroll` fx receives the fragment in its args:

```clojure
[:rf.nav/scroll {:strategy :top :from from-route :to to-route :saved-pos saved :fragment "section-2"}]
```

The fx's behaviour, when `:fragment` is present:

| Strategy | Behaviour |
|---|---|
| `:top` | Attempt `getElementById(fragment)` and scroll-into-view; on failure, fall back to `window.scrollTo(0,0)`. |
| `:restore` | Restore saved scroll position; the fragment is ignored (the saved position trumps). |
| `:preserve` | Do nothing (fragment ignored). |

Hosts that ship a custom map-form scroll strategy may interpret `:fragment` per their own contract; the three enum strategies' fragment-handling is locked above.

### Programmatic navigation with fragments

`:rf.route/navigate` accepts a `:fragment` key in `opts` or in the target map:

```clojure
;; opts form
[:rf.route/navigate :route/docs {:page "routing"} {:fragment "scroll-restoration"}]

;; target-map form (URL escape hatch)
[:rf.route/navigate {:url "/docs/routing#scroll-restoration"}]
```

Either form ends up in the `:route` slice's `:fragment`.

### SSR

Browsers do **not** send `#fragment` to the server — `window.location.hash` is client-only. For browser-initiated SSR requests, the server-side `:fragment` is therefore typically `nil`, regardless of what the user typed in the address bar. The exceptions are static-site generators, server-side test harnesses, and crawlers that synthesise URLs with explicit fragments (e.g., for anchored documentation pages); when the host's request abstraction exposes a `#fragment`, SSR includes it in the seeded `:route` slice. See [011 §Fragments under SSR](011-SSR.md#fragments-under-ssr) for the full SSR-side contract.

The server does NOT scroll (no DOM); `:rf.nav/scroll` is `:platforms #{:client}` per [011 §Effect handling on the server](011-SSR.md#effect-handling-on-the-server). The first client render after hydration sees the same `:fragment` value the server seeded (typically `nil` for browser requests), so view code that reads `:rf.route/fragment` produces structurally-identical output on both sides. A subsequent `:rf.nav/scroll` (post-hydrate) is the host's choice — the contract leaves it to the host to decide whether to perform the initial scroll-to-fragment after hydration.

### Conformance

Fixture `route-fragment-change.edn` exercises:
1. Navigate to `/docs/routing#scroll-restoration`. Verify the slice's `:fragment` is `"scroll-restoration"`.
2. Navigate to `/docs/routing#caching` (same path/query, different fragment). Verify `:on-match` does NOT re-fire and `:route.url/fragment-changed` trace event fires.
3. Navigate to `/docs/instrumentation#scroll-restoration` (different path, same fragment). Verify `:on-match` DOES re-fire (path changed; fragment-only rule does not apply).

## Nested layouts

For nested layouts (e.g., `/account/settings`, `/account/billing`, `/account/security` all rendering inside an `/account` shell), the pattern is **id namespacing plus an explicit `:parent`**:

```clojure
(rf/reg-route :route/account            {:path "/account"})
(rf/reg-route :route/account.settings   {:path "/account/settings" :parent :route/account})
(rf/reg-route :route/account.billing    {:path "/account/billing"  :parent :route/account})
(rf/reg-route :route/account.security   {:path "/account/security" :parent :route/account})
```

The `:parent` key gives the rendering side an enumerable answer to "what's the layout chain for this route?" The runtime exposes a sub:

```clojure
(rf/reg-sub :rf.route/chain
  :<- [:rf.route/id]
  (fn [id _]
    ;; Returns [parent-most ... current], following :parent links.
    ;; e.g. (:route/account :route/account.settings)
    (chain-from-meta id)))
```

Views render the chain top-down:

```clojure
(defn account-shell [child-view]
  [:div.account-shell
   [account-sidebar]
   [:main child-view]])
```

A more elaborate router with **native nested layouts** — true `<Outlet/>` slot mechanics, parent-loader cascades, partial revalidation — is out of scope. The `:parent` + `:rf.route/chain` convention covers the common case (a parent shell wrapping leaf views), keeps the pattern data-only, and avoids introducing a new render substrate.

Future expansion may revisit:
- A `:layout` slot on `reg-route` (separate from `:parent`) so a route can declare which layout component wraps its leaf view.
- Parent-route `:on-match` events that cascade to children (today, child routes must duplicate the parent's `:on-match` if they need the same data).
- An `<Outlet/>`-equivalent primitive for the child render slot.

The `:parent` + chain-sub convention is sufficient for the common case and doesn't preclude a richer mechanism later.

## Navigation blocking — pending-nav protocol

Real product needs — unsaved forms, interrupted checkouts, destructive multi-step workflows — require navigation to be *blockable*. Angular, Vue Router, and TanStack Router all support this. Re-frame2 makes navigation blocking a **first-class named-event/state protocol** instead of a magic component hook: pending-nav state lives in `app-db`; UI renders confirm dialogs from ordinary subscriptions; user choices are dispatched as standard events. All testable.

### Mechanism

A standard pending-navigation slot in `app-db`, three named events, and an optional `:can-leave` route-metadata key.

**Pending-nav slot** (`:rf/pending-navigation` in `app-db`, schema in [Spec-Schemas.md §`:rf/pending-navigation`](Spec-Schemas.md#rfpending-navigation)):

```clojure
{:rf/pending-navigation
 {:id                  "pn-7"
  :requested-by-event  [:rf/url-requested {:url "/editor/articles/42"}]
  :requested-url       "/editor/articles/42"
  :reason              "Form has unsaved changes"
  :rejecting-route     :editor/article
  :rejecting-guard     :editor/can-leave?}}
```

`nil`/absent when no navigation is pending.

**Three named events:**

| Event | Dispatched by | Behaviour |
|---|---|---|
| `:rf.route/navigation-blocked` | The runtime, when a `:can-leave` guard rejects | Sets `:rf/pending-navigation` (the runtime does this before dispatching the event); user code subscribes and renders the dialog. Event vector: `[:rf.route/navigation-blocked pending-nav]`. |
| `:rf.route/continue` | User code (typically a "Yes, leave" button) | Clears `:rf/pending-navigation` and re-dispatches the original navigation request *without* re-running the leave guard. Event vector: `[:rf.route/continue pending-nav-id]`. |
| `:rf.route/cancel` | User code (typically a "Stay" button) | Clears `:rf/pending-navigation`; the URL stays unchanged. Event vector: `[:rf.route/cancel pending-nav-id]`. |

**Route-metadata extension** — declare the leave-guard sub on a route:

```clojure
(rf/reg-route :editor/article
  {:doc       "Editing an article."
   :path      "/editor/articles/:id"
   :params    [:map [:id :string]]
   :can-leave [:editor/can-leave?]})              ;; sub-id; (subscribe [<sub-id>]) returns boolean

(rf/reg-sub :editor/can-leave?
  :<- [:editor/dirty?]
  (fn [dirty? _] (not dirty?)))                   ;; true means "OK to leave"
```

The sub returns `true` when the route is OK to leave; `false` to block. The convention: the *sub's name* describes the positive case (`:can-leave`), so `false` means "can NOT leave" — block.

### Default flow

1. `:rf/url-requested` fires with the new URL (link click, `:rf.route/navigate` programmatic call, popstate).
2. The runtime evaluates the **current** route's `:can-leave` sub (if any).
3. **No guard or guard returns `true`** → proceed normally. The new URL becomes active; nav-token allocates; `:on-match` runs.
4. **Guard returns `false`** → BLOCK:
   a. Generate a `pending-nav-id` (gensym).
   b. Write `:rf/pending-navigation` with `{:id <id> :requested-by-event <ev> :requested-url <url> :rejecting-route <id> :rejecting-guard <sub-id>}`.
   c. **The URL does not change.** No `pushState`, no `:route` slice update, no `:on-match`.
   d. Dispatch `[:rf.route/navigation-blocked pending-nav]`. Apps may register their own handler (default is a no-op trace; the value is in the slot, which a sub reads).
   e. Emit `:rf.route/navigation-blocked` trace event.
5. UI renders the confirmation dialog by subscribing to `:rf/pending-navigation`.
6. User chooses:
   - **Continue** → dispatch `[:rf.route/continue pending-nav-id]`. Runtime clears the slot and re-issues the original navigation, **bypassing** the leave-guard for this one shot.
   - **Cancel** → dispatch `[:rf.route/cancel pending-nav-id]`. Runtime clears the slot. Nothing else changes.

### Why this shape (not a hook-based router)

The hook-based version (e.g., React Router's `useBlocker`) is convenient but tied to component lifecycle. Re-frame2's strengths are explicit state and dispatched events; this design preserves them. Slightly more verbose at the call site; far more testable.

A test fires `[:rf/url-requested {:url "/cart"}]` against a frame whose `:editor/can-leave?` sub returns `false`, asserts `:rf/pending-navigation` is set, asserts `:rf.nav/push-url` did NOT fire, dispatches `[:rf.route/continue pending-nav-id]`, asserts the navigation completes. No DOM, no event simulation, no hook-mock.

### Interaction with other navigation features

- **Nav-tokens.** Navigation blocking happens *before* the new nav-token would be allocated; tokens are for committed navigations. The `:rf.route/continue` re-issue allocates a fresh nav-token like any other navigation. The original (blocked) attempt never received one.
- **Fragments.** `:can-leave` runs for any URL change, including fragment-only changes. The runtime DOES check `:can-leave` for fragment-only changes — apps that want fragment changes to bypass the guard return `true` from the sub when the only difference is the fragment (the sub reads the current `:rf.route/fragment` and the requested fragment from the pending event).
- **Multiple guards.** A route has at most one `:can-leave` sub (it's a metadata key, single-valued). For frame-level cross-cutting policy (e.g., "always block when `:auth/logging-out?`"), use an interceptor on `:rf/url-requested`. Interceptors run *before* the leave-guard check — they can short-circuit by setting `:rf/pending-navigation` directly.

### Conformance

Fixture `route-navigation-blocked.edn` exercises:
1. Register a route with `:can-leave [:editor/can-leave?]`.
2. Set `:editor/dirty?` to `true` (sub returns `false`).
3. Dispatch `[:rf/url-requested {:url "/cart"}]`.
4. Assert `:rf/pending-navigation` is set; `:rf.nav/push-url` did NOT fire; `:rf.route/navigation-blocked` trace event fired; `:route` slice unchanged.
5. Dispatch `[:rf.route/continue pending-nav-id]`.
6. Assert `:rf/pending-navigation` is `nil`; the URL is `/cart`; `:route/cart` is the active route.

## Redirects and guards

A `:rf.route/navigate` event can be intercepted by an interceptor that decides whether the navigation proceeds, redirects elsewhere, or aborts:

```clojure
(def auth-guard
  {:id     :rf.route/auth-guard
   :before (fn before [ctx]
             (let [event       (get-in ctx [:coeffects :event])
                   target      (second event)
                   route-meta  (rf/handler-meta :route target)
                   needs-auth? (boolean (some #{:requires-auth} (:tags route-meta)))
                   logged-in?  (some? (get-in ctx [:coeffects :db :auth :user]))]
               (if (and needs-auth? (not logged-in?))
                 ;; redirect to login
                 (assoc-in ctx [:coeffects :event] [:rf.route/navigate :route/login {:return-to target}])
                 ctx)))})

(rf/reg-route :route/account
  {:path "/account"
   :tags #{:requires-auth}})
```

Guards are interceptors, not a special routing mechanism. They compose; multiple guards can layer.

## Server-side rendering integration (per [011](011-SSR.md))

The server-side flow:

1. HTTP request arrives.
2. `make-frame` per request. `:on-create` fires `[:rf/server-init request]`, which dispatches `[:rf.route/handle-url-change (:uri request)]`.
3. Route slice is set from the URL; the same handler runs on server and client. Path params, query params, defaults, and `:query-retain` keys are populated.
4. The matched route's `:on-match` events dispatch — the same vector that runs client-side. Server-side data loaders complete before the drain settles.
5. Drain settles; root view renders against the populated state.
6. HTML + serialised state ship to the client.

On the client, hydration runs `[:rf/hydrate state]` which restores the route along with everything else. **`:on-match` does not re-fire on hydration** — the seeded `app-db` already contains the loaded data. The first client render produces the same HTML the server rendered (same `:rf.route/id`, same `:params`, same `:query`).

## Tooling and AI-amenability

- `(rf/handlers :route)` enumerates every registered route. Tools and agents enumerate them; AI scaffolding consults this before generating new routes to avoid collisions.
- `(rf/handler-meta :route :route/cart)` returns the route's metadata: path, params shape, query shape, `:on-match`, `:on-error`, `:scroll`, `:parent`, tags, source coords. The `:on-match` slot is **enumerable** — tools render route-loading dependency graphs without parsing handler bodies.
- The `:route` sub gives the entire route map; `:rf.route/id`, `:rf.route/params`, `:rf.route/query`, `:rf.route/transition`, `:rf.route/error` are conveniences.
- `:rf.route/navigate`, `:rf.route/handle-url-change`, `:rf/url-changed`, `:rf/url-requested` are stable, named events; trace events surface every navigation and every URL request.
- A registered `:rf.route/not-found` is required (per [§Route-not-found](#route-not-found--rfroutenot-found-canonical)); tools surface the `:rf.warning/no-not-found-route` trace event for apps missing the registration.

## Multi-frame routing

Each frame has its own `:route` slice. Only the default frame is URL-bound. Non-default frames have independent routes that don't push to the browser URL.

1. Every frame's `app-db` may have a `:route` slice (it's a regular `app-db` path, not a special concept).
2. The default frame (`:rf/default`) is **URL-bound**: `:rf.route/navigate` events on that frame fire `:rf.nav/push-url`; `popstate` listeners fire `[:rf.route/handle-url-change url] {:frame :rf/default}`. The browser URL reflects the default frame's route.
3. Non-default frames are **not URL-bound** by default. `:rf.route/navigate` updates their `:route` slice (state changes) but does not fire `:rf.nav/push-url`. This is the right default for story-variant frames, devcards, per-test fixtures.
4. Opt-in URL binding for non-default frames via `(rf/reg-frame :my-frame {:url-bound? true})`. The runtime enforces "only one frame can own the URL at a time" — re-registering a second `:url-bound? true` frame is a `:rf.error/duplicate-url-binding` trace event.

The story / devcard / SSR cases all benefit:

- **Stories / devcards**: frame-per-variant; route within the variant is independent of the page URL.
- **Per-test fixtures**: each test frame has its own route; tests don't accidentally hit `pushState`.
- **SSR per-request frames**: the request URL is fed in via `:rf.route/handle-url-change`; no client-side `pushState` (which doesn't exist server-side anyway).

## Open questions

- Native nested layouts (true `<Outlet/>`-style render slots, parent-loader cascades, partial revalidation on child-only navigations) — the current surface is `:parent` + `:rf.route/chain` sub.
- Data-form path patterns (a vector-of-segments alternative to the string grammar), formally specified — the string grammar is the canonical wire form.
- Custom scroll-strategy registry — current contract is the three enums (`:top`, `:restore`, `:preserve`).
- URL-state-as-source-of-truth (URL canonical, `app-db` derives) — currently the inverse: `app-db` canonical, URL derives.
- Declarative redirect rules in route metadata — currently redirects are interceptors.

## Cross-references

- [000-Vision §Working design implications](000-Vision.md#working-design-implications) — "routing is state plus events."
- [011-SSR.md](011-SSR.md) — SSR-side route resolution.
