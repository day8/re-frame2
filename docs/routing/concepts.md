# Routing: the URL is a sub

Here's the whole idea in one line: **the URL is application state, and your back button is a dispatch.**

Most frameworks bolt a router onto the side of your app — its own context, its own lifecycle, its own opinion about where truth lives. re-frame2 doesn't. A route is a [registration](../core/glossary.md#registration): one row in re-frame2's table of routes. Navigating is dispatching an [event](../core/glossary.md#event): a data vector sent through the app. The active route is a [subscription](../core/glossary.md#subscription): a read of state your [views](../core/glossary.md#view) watch. That's it — three things you already know, pointed at the URL.

Which means everything you already know about [events](../core/introduction.md) and [subscriptions](../core/concepts/subscriptions.md) is genuinely everything you need here. There is no fourth concept hiding behind the curtain. By the end of this page you'll register a route table, navigate and link between pages, branch your views on the active route, load each page's data declaratively, handle the 404, block a navigation, wire up back/forward, and — for free — run the exact same routing on the server.

> **From re-frame v1.** There was no routing in v1 — you reached for secretary, bidi, or reitit, wired a third-party router to `dispatch` by hand, and kept the active route somewhere in [app-db](../core/glossary.md#app-db) yourself. re-frame2 folds all of that into the framework: routes are registrations, navigation is a built-in event, and the active route is a built-in subscription. The bring-your-own-router era is over. (It's still a *separate* artefact — `day8/re-frame2-routing` — so an app with no shareable URLs ships zero routing bytes.)

> **Coming from React Router or Remix?** Routes-as-data and per-route loaders will feel familiar. The deliberate divergences: there are no hooks (`useNavigate` is an event dispatch, `useLoaderData` is a subscription, `useBlocker` is a guard sub), there's no router context to thread through your tree, and the same route handler runs on the server with zero SSR-specific code.

## The whole model in three moves

Read these three moves and you've read the whole framework's idea of routing. Everything after them is a refinement of one of the three.

```clojure
;; Adapted from examples/capabilities/routing/routing/core.cljs
(ns app.core
  (:require [re-frame.core :as rf]
            ;; Ships in day8/re-frame2-routing. Requiring it at boot is what makes
            ;; reg-route available; the alias serves the routing-namespace helpers
            ;; you'll meet below (sub-route, route-url, match-url).
            [re-frame.routing :as routing]))

;; 1. A route is data in the registry.
(rf/reg-route :app/home
  {}
  "/")

(rf/reg-route :app/article
  {:params [:map [:id :string]]}
  "/articles/:id")

;; 2. Navigation is an event.
(rf/dispatch [:rf.route/navigate :app/article {:id "intro"}])

;; 3. The root view reads the active route through an ordinary subscription.
;;    Inside reg-view, you call `subscribe` and `dispatch` unprefixed: the macro
;;    binds them for you to this view's frame. (Outside a view — like moves 1 and
;;    2 above — you reach through the `rf/` facade: `rf/subscribe`, `rf/dispatch`.)
;;    The leading `@` deref-es the subscription to its current value and registers
;;    this view to re-render when that value changes — see the Subscriptions page.
(rf/reg-view article-page []
  (let [{:keys [id]} @(subscribe [:rf.route/params])]
    [:h1 "Article " id]))

(rf/reg-view root-view []
  (case @(subscribe [:rf.route/id])
    :app/home           [:h1 "Home"]
    :app/article        [article-page]
    :rf.route/not-found [:h1 "Not found"]))
```

Move 1 is a couple of registry rows. Move 2 is a dispatch — the same verb you use everywhere. Move 3 is a `case` over a subscription. No router object, no provider, no context. The rest of this page builds gently on these three: query strings, data loading, the 404, blocking a navigation, back/forward, SSR. Once the three click, the rest is detail.

## Move 1: a route is a registry entry

`reg-route` registers a route the same way `reg-event` registers an event: an id, a metadata map, and — in the third slot — the path. The path grammar is small enough to parse in your head. A path is made of exactly five kinds of thing, and no more: literal segments (`/articles`), named params (`:id`), an optional group (`{/:slug}?`), a catch-all splat (`*rest`), and the root (`/`).

> **Gotcha — `reg-route` needs the artefact loaded.** `reg-route` (and `route-link`, `match-url`, `route-url`, the route subs) all live in the separately-packaged `day8/re-frame2-routing` artefact, not in core. Requiring `re-frame.routing` *at boot* is what wires them up — that's the `(:require … [re-frame.routing])` in the three-moves snippet above. Forget it and the first `reg-route` call **throws** `:rf.error/routing-artefact-missing`, naming the namespace to require and the Maven coordinate to add — a loud, actionable error, not a silent no-op.

The `:params` and `:query` keys take [schemas](../core/how-to/validate-with-schemas.md), which validate *and coerce* for you, so `?page=2` arrives as the integer `2`, not the string `"2"`. That coercion is the part everyone forgets to do by hand — so let the schema own it:

```clojure
(rf/reg-route :app/search
  {:query          [:map [:q :string] [:page {:optional true} :int]]
   :query-defaults {:page 1}}
  "/search")
```

`:query-defaults` fills absent query keys at match time, so a search page with no `?page=` still gets `{:page 1}`. Path params and query params stay separate maps the whole way through: captured separately, validated against separate schemas, never silently merged into one bag.

### Carrying global state through the URL

One more `:query` key earns its keep early. `:query-retain` is a set of keys carried through *subsequent* navigations even when the caller didn't supply them — exactly what you want for URL-encoded global state like `?theme=dark` or `?locale=fr`. Navigate from a search page to the cart and the theme rides along:

```clojure
(rf/reg-route :app/search
  {:query          [:map [:q :string] [:page {:optional true} :int]]
   :query-defaults {:page 1}
   :query-retain   #{:theme :locale}}
  "/search")

;; From a /search?q=clojure&theme=dark page:
(rf/dispatch [:rf.route/navigate :app/cart])
;; → /cart?theme=dark   — :theme rode along, you never named it
```

> **Gotcha — retained keys aren't re-coerced.** A retained key is merged **verbatim** into the next URL, *not* re-validated against the target route's `:query` schema. So keep a retain key's *type* consistent across every route that retains it: type `:theme` as `[:enum :light :dark]` on one route and `:string` on another and you'll carry a keyword `:dark` into a `:string` slot. That's caught (the target's `:query` validator runs and rejects the navigation), not silent — but it's friction you avoid by being consistent.

### Routes are queryable data

Because routes are registry entries, the route table is *queryable data* — and that pays off. Tag a route `:tags #{:requires-auth}` and an ordinary [interceptor](../core/concepts/interceptors.md) (a `:before`/`:after` wrapper around your navigation events) can read the tag and redirect. That's the entire auth-guard mechanism — no special router machinery, just data and a step that reads it:

```clojure
(rf/reg-route :app/admin
  {:tags     #{:requires-auth}
   :on-match [[:admin/load-dashboard]]}
  "/admin")

;; An interceptor reads the tag off any navigation target and redirects when logged out.
;; (rf/handler-meta :route route-id) returns the metadata, :tags and all.
```

[Require sign-in on a route](how-to/require-sign-in-on-a-route.md) is the routing-focused recipe; [Add authentication](../core/how-to/add-auth.md) walks through the whole auth flow end to end.

> **For JavaScript developers.** This is the inverse of React Router's `<Route>` JSX tree, where each route is a *component* and you read it by being rendered inside it. Here a route is a *data row* you can `get`, `filter`, and `map` over from anywhere — auth guards, breadcrumb generators, sitemap builders, and analytics all just query the same table.

### The metadata map, in full

You've now met the keys you'll reach for daily. The metadata map has fourteen reserved keys in total — the largest registration shape in re-frame2 — but you never learn them as a flat list. They cluster into four groups by *what they control*; pick the group first, then the key. The rest of this page introduces the remaining keys in context, so treat this table as a map of where you're going — and the [API reference](../api/re-frame.routing.md#reg-route) for the canonical, per-key spec.

| Group | Keys | What it controls |
|---|---|---|
| **Shape** — URL ↔ params | `:params`, `:query`, `:query-defaults`, `:query-retain` | Which URLs match, and how their parts coerce into maps. The contract `match-url` and `route-url` agree on. |
| **Lifecycle** — events at navigation boundaries | `:on-match`, `:on-error`, `:can-leave`, `:can-enter` | What the runtime dispatches when the route activates (`:on-match`), when a loader errors (`:on-error`), a guard sub consulted before you leave (`:can-leave`), and a guard sub consulted before you enter (`:can-enter`). |
| **Layout** — how the route fits with neighbours | `:doc`, `:parent`, `:tags`, `:scroll` | How the route is described, nested (`:parent`), grouped for interceptors (`:tags`), and scrolled on entry (`:scroll`). |
| **Classification** — data hygiene | `:sensitive`, `:large` | Which slice values are redacted or kept off the wire at egress while the route is active (see [Data classification](#keeping-tokens-off-the-wire) below). |

Two more bare keys are on loan from other parts of the framework, accepted when their owning artefact is loaded: `:resources` (declared server state — [below](#declaring-resources-instead), owned by the resources artefact) and `:head` (the server-rendered page's [head metadata](../ssr/concepts.md#head-metadata----opengraph-json-ld), owned by SSR). In an app that doesn't load the owning artefact, they're rejected like any other unknown bare key — so a `:resources` declaration can't silently do nothing.

> **Gotcha — typos fail loud, at registration.** Because the shape is large, a typo like `:on-matched` or `:querey` would otherwise be silently accepted and then quietly do nothing forever. So `reg-route` validates its metadata *at the authoring boundary*: a **bare** (unqualified) key outside the reserved set throws `:rf.error/route-bad-metadata` the instant you register, naming the offending key and the valid vocabulary. Your own extension keys are fine — just namespace them (`:myapp/layout`, `:analytics/id`); namespaced keys are the open-extension surface, bare keys are the framework's reserved vocabulary. And note `:path` belongs in the *third slot*, not the map — leaving it inside the metadata is the same loud error.

When two patterns could both match one URL, a structural ranking breaks the tie: more static segments win, and named params beat splats. The ranking is computed at *registration* time from the patterns alone — so the winner is decided before a single URL ever arrives, and there's no runtime ambiguity to debug.

??? note "The full ranking cascade"

    The five path elements above are the whole grammar; the ranking cascade, when two patterns compete, runs six rules deep: (1) more static segments win; (2) the *bare* catch-all `/*` is demoted below everything; (3) longer paths win; (4) named params beat splats; (5) exact routes beat optional-group routes; (6) registration order is the final, discouraged tiebreak — and the runtime warns (`:rf.warning/route-shadowed-by-equal-score`) at registration if two routes tie all the way down. You won't need past rule (1) for everyday routes.

## Move 2: navigation is an event

You navigate with the same verb you use for everything else, which is the whole reason the surface stays small:

```clojure
(rf/dispatch [:rf.route/navigate :app/article {:id "intro"}])

;; Query params and options ride in the THIRD slot — params 2nd, opts 3rd:
(rf/dispatch [:rf.route/navigate :app/search {} {:query {:q "clojure" :page 2}}])

;; Replace instead of push (login redirects, search-as-you-type):
(rf/dispatch [:rf.route/navigate :app/login {} {:replace? true}])

;; Jump to a fragment:
(rf/dispatch [:rf.route/navigate :app/article {:id "intro"} {:fragment "section-2"}])
```

The opts map (third slot) is open; the runtime recognises these keys (the canonical enumeration lives in [Spec 012 §The `navigate` opts](../../spec/012-Routing.md), which this table mirrors):

| Opt | Effect |
|---|---|
| `:replace?` | Use `replaceState` instead of `pushState` — for redirects, login-flow returns, and search-as-you-type, where the back button should *not* land on the intermediate URL. |
| `:query` | The query-string params for the new URL — coerced and emitted as `?key=value`. Replaces the query wholesale. |
| `:query-merge` | Fold these deltas into the **current** query instead of replacing it — the "stay here, change these params" move for search, pagination, and tabs. A `nil` value **removes** a key. Pairs with the `:rf.route/self` target (see [Navigate-in-place](#navigate-in-place) below). |
| `:scroll` | Per-call override of the route's `:scroll` strategy (`:top` / `:restore` / `:preserve`; see [Fragments and scrolling](#fragments-and-scrolling)). |
| `:fragment` | The target `#fragment` for the new URL. |
| `:bypass-guards?` | A **set** of `#{:leave :enter}` naming which navigation guards to skip for this one navigation — `#{:leave}` skips the current route's `:can-leave`, `#{:enter}` skips the target route's `:can-enter`, `#{:leave :enter}` skips both (see [Blocking a navigation](#blocking-a-navigation) below). |

Hosts and apps may add their own opts under a namespace.

<a id="navigate-in-place"></a>
#### Navigate-in-place: change the query, stay on the route

Changing `?page=`, a search term, or a tab means *stay on this route, change these query params* — the most common URL operation there is. Reach for the reserved `:rf.route/self` target and `:query-merge`:

```clojure
;; On /search?q=clojure&page=1  →  /search?q=clojure&page=2
(rf/dispatch [:rf.route/navigate :rf.route/self {} {:query-merge {:page 2}}])

;; Clear a filter: a nil value removes the key  →  /search?q=clojure
(rf/dispatch [:rf.route/navigate :rf.route/self {} {:query-merge {:page nil}}])
```

`:rf.route/self` resolves to the route you're already on (id + path params, read from the current slice), so you never hand-roll a "read the route, branch on the id, rebuild the query" helper. `:query-merge` folds your deltas onto the current query — caller values win, a `nil` drops a key. It works with any target, but `:rf.route/self` is its natural partner. (`:query` *replaces* the whole query; `:query-merge` *edits* it — pick by whether you're building a fresh query or nudging the current one.)

> **From re-frame v1.** `useNavigate`-style imperative calls and `secretary`'s `(set! js/window.location ...)` both become an ordinary `dispatch`. That means navigation is now *interceptable* and *replayable* like any other event — a navigate shows up in [Xray](../core/glossary.md#xray) on the same wire as your business events, and time-travel rewinds it for free, because it was never a side-channel. And this holds for **hash-URL apps too** — the shape most secretary-era apps have. Declare `:url-strategy rf/hash-url-strategy` on your URL-owning frame and the router speaks `#/…` at the edges (link hrefs, history, the URL-change listener) while `route-url` / `match-url` stay path-form; you use `:rf.route/navigate` and `route-link` exactly as a path-URL app does. There is no `(set! js/window.location.hash …)` side-channel left to write. See [URL strategies](#url-strategies) below.

!!! warning "Params is 2nd, opts is 3rd"

    `[:rf.route/navigate :app/cart {:replace? true}]` *reads* like "navigate with options" but actually drops `:replace?` into the **params** slot. The runtime catches the mistake and rejects the navigation with a named error (`:rf.error/navigate-arity-misuse`) rather than navigating wrongly — so you get a loud signal, not a silent bug. Just pass an empty params map: `[:rf.route/navigate :app/cart {} {:replace? true}]`. (A route that *legitimately* captures a path segment named `:fragment` isn't false-flagged — the runtime only complains when an opts-only key lands in the params slot of a route that doesn't declare it as a path-param.)

### Linking from views

For links *in views*, use `route-link`. It renders a real `<a href>` (good for accessibility, hover-preview, and copy-link) and intercepts plain primary clicks into a dispatch. Crucially it also *defers* cmd-click, shift-click, and middle-click to the browser, so open-in-new-tab still works — the detail hand-rolled SPA links almost always forget:

```clojure
[rf/route-link {:to :app/article :params {:id "intro"}} "Read more"]

;; The full prop surface — query, fragment, and any HTML attrs pass through:
[rf/route-link {:to       :app/search
                :query    {:q "clojure" :page 2}
                :fragment "results"
                :class    "nav-link"
                :title    "Search articles"}
 "Search"]
```

`route-link` takes `:to` (route id), `:params`, `:query`, and `:fragment` to build the URL, plus any HTML attributes (`:class`, `:title`, `:id`, `:aria-label`, …) which pass straight through to the `<a>`. A caller-supplied `:on-click` runs *before* the framework's interception and can pre-empt it with `.preventDefault`. Because `route-url` is the single point where the URL is synthesised, renaming or reshaping a route flows into every `route-link` site with no per-link edits.

> **Gotcha — native-anchor attributes win.** A `route-link` carrying `:target "_blank"` (or `_parent`/`_top`/a named frame) or `:download` is **not** intercepted, even on a plain left-click — the browser handles it, exactly as the DOM attributes advertise. SPA-intercepting a `{:target "_blank"}` link would silently break open-in-new-tab; intercepting a `{:download ...}` link would turn it into a no-op. The link still renders as a real `<a>`; the click is just left to the browser. Want SPA interception? Omit those attributes (or set `:target "_self"`).

Plain `[:a {:href "..."}]` anchors are **not** intercepted; they do a native full-page navigation. Site-wide anchor interception is a host-adapter concern, not framework magic, so you opt in per link (or install your own document-level click handler that dispatches `:rf/url-requested` — the same decision-point event `route-link` uses).

> **`:rf/url-requested` is the policy seam.** Every `route-link` click dispatches `:rf/url-requested`, and *that* event is the single decision point for "in-app or external?", "is the leave-guard satisfied?", and any policy of your own (auth, modifier keys). Because it's an ordinary event, you can drive it from a test with zero DOM: dispatch `[:rf/url-requested {:url "/cart"}]` and assert what happened. An in-app request pushes the URL and runs the route transition. Anything not provably same-origin is treated as external — it emits a `:rf.route/external-url-requested` trace and pushes nothing. Re-registering `:rf/url-requested` is how you install app-wide navigation policy.

### Navigating to a raw URL string

The canonical target is a route id, because ids are enumerable, refactorable, and queryable. But sometimes you have a *string* you didn't build yourself — a deep-link handler, a server redirect target, a URL from an API. For those, navigate to a `{:url ...}` map:

```clojure
;; Escape hatch: navigate to a URL string the app didn't construct.
(rf/dispatch [:rf.route/navigate {:url "/articles/intro"}])
```

The runtime runs `match-url` on the string. If it resolves to a registered route, navigation proceeds normally; if it matches nothing, it routes to `:rf.route/not-found` with the URL in `:params` — and pushes the *requested* URL to the address bar (not the not-found route's own path), so the user keeps the link they aimed at while your 404 view renders. The string is also passed through the same fail-closed open-redirect check as `route-link`: a URL that isn't provably same-origin is treated as external and *not* pushed into history.

> **Tooling can nudge you back.** Because the route-id form is the well-formed one, tools can flag `{:url ...}` call sites as candidates to migrate to a registered route-id when the pattern is known. Reach for the string form only when you genuinely have a string.

### What happens, in order

When the navigate event runs, three things happen in a **locked order**: first the route slice in [runtime-db](../core/glossary.md#runtime-db) updates, then the browser URL pushes, then the route's loaders dispatch. State-before-URL is on purpose. If the URL push fails — offline, or the browser denies it — your application state is still consistent, so you never end up showing one page while the address bar swears you're on another.

## Move 3: the active route is a subscription

The current route lives in **runtime-db** — the framework-owned partition beside your app-db (see [the two partitions](../core/concepts/app-db.md#yours-and-the-frameworks-next-door)), addressed at `[:rf.db/runtime :rf.runtime/routing :current]`. Your code *reads* it and never *writes* it, and you read it like any other state:

```clojure
@(rf/subscribe [:rf/route])             ;; the full slice: {:route-id :params :query :fragment :transition :error :nav-token}
@(rf/subscribe [:rf.route/id])          ;; just the route id
@(rf/subscribe [:rf.route/params])      ;; path params
@(rf/subscribe [:rf.route/query])       ;; query params
@(rf/subscribe [:rf.route/fragment])    ;; the URL #fragment string, or nil
@(rf/subscribe [:rf.route/transition])  ;; :idle | :loading | :error
@(rf/subscribe [:rf.route/error])       ;; structured error map when :transition = :error
@(rf/subscribe [:rf.route/chain])       ;; the :parent chain of the active route (for nested layouts)
@(rf/subscribe [:rf/pending-navigation]);; the blocked navigation parked by a :can-leave guard, or nil
```

Nine subscriptions, every one a plain read — no special routing API in views. `:rf/route` is the whole slice; the rest are projections of it you'll reach for far more often. The [API reference](../api/re-frame.routing.md#subscriptions) lists each with its return shape.

Two of those you'll read constantly — the whole slice, and the pending navigation — so `re-frame.routing` ships small named helpers for them: `(routing/sub-route)` over `[:rf/route]` and `(routing/sub-pending-navigation)` over `[:rf/pending-navigation]`. Same reaction, fewer brackets. The vector forms stay canonical; the sugar just tidies the two most common reads.

`:transition` is a tiny state machine the runtime drives for you: `:loading` while a route's loaders drain, `:error` if one fails, `:idle` otherwise. So a global progress bar is just a view over `:rf.route/transition`, and an error banner is a view over `:rf.route/error`. You never wire per-page loading state again — it's a property of the slice, the same on every page:

```clojure
(rf/reg-view global-progress-bar []
  (case @(subscribe [:rf.route/transition])
    :loading [:div.progress-bar.active]
    :error   [:div.error-banner (:rf.error/message @(subscribe [:rf.route/error]))]
    nil))   ;; :idle ⇒ nothing
```

> **Coming from TanStack Query?** That global `:transition` plays the role `isFetching` plays at the page level — except you didn't have to thread it through any component, because it's a subscription anyone can read from anywhere in the tree.

Try it with the [Xray inspector](../xray/index.md) open. Dispatch a navigation and watch the trace: the navigate event, the fresh nav-token allocation, then each loader dispatch, in order. Routing has no hidden machinery — everything it does shows up on the same wire as your own events, which is exactly what makes it easy to trust.

### Fragments and scrolling

The `#fragment` part of a URL is first-class: it rides in the slice, it has its own sub (`:rf.route/fragment`), and `route-url`'s 4-arity emits one. A fragment-only change — same route, same params, just a new `#anchor` — updates the slice and emits a `:rf.route/fragment-changed` trace but does **not** re-fire the route's loaders (`:on-match`). You jumped to an anchor; you didn't reload the page.

Scrolling on navigation is declarative too — a route's `:scroll` key (or a per-call `:scroll` in the navigate opts) names one of three strategies:

| `:scroll` | Behaviour |
|---|---|
| `:top` | Scroll to the top of the page — *unless* a `#fragment` is present, in which case it scrolls that element into view (falling back to top if the element is absent). |
| `:restore` | Restore the saved scroll position for this URL (the runtime captures positions on every navigation). |
| `:preserve` | Leave the scroll position alone. `nil`/absent means the same. |

The default is `:top` for forward navigation and `:restore` for back/forward (popstate) — the behaviour browsers give you natively, made explicit and overridable. A per-call `:scroll` opt wins over the route's metadata, which wins over the default.

```clojure
(rf/reg-route :app/article
  {:params [:map [:id :string]]
   :scroll :top}                       ;; forward nav scrolls to top
  "/articles/:id")

;; Override per call — keep position on this one navigation:
(rf/dispatch [:rf.route/navigate :app/article {:id "next"} {:scroll :preserve}])
```

## Nested layouts

A real app wraps its pages in shared shells — a site header, a section sidebar — without each page repeating them. re-frame2 has no `<Outlet/>` render slot; instead, **nesting is data**. A child route names a `:parent`, and the `:rf.route/chain` sub hands you the active route's ancestry, so you compose the shells yourself.

```clojure
(rf/reg-route :app/articles {} "/articles")
(rf/reg-route :app/article  {:parent :app/articles
                             :params [:map [:id :string]]} "/articles/:id")
```

`@(subscribe [:rf.route/chain])` returns the chain **root-most first** — on `/articles/intro`, `[:app/articles :app/article]`; on a route with no `:parent`, a single-element vector. The leaf is the page you're on; each ancestor contributes a wrapping shell. Fold from the leaf outward:

```clojure
;; page-for: a route id → its page view.  ancestor-shell: an ancestor id → the
;; shell it wraps around `inner` (returning `inner` unchanged when it adds no chrome).
(rf/reg-view root-view []
  (let [chain @(subscribe [:rf.route/chain])]
    (reduce (fn [inner ancestor] (ancestor-shell ancestor inner))
            (page-for (last chain))          ;; the leaf page
            (reverse (butlast chain)))))     ;; its ancestors, innermost first
```

Truly global chrome — a header on every page — needs no chain at all; render it once in the root view and fold the chain inside it. You only reach for the chain when a shell wraps a *subtree*.

> **Coming from React Router.** This is `<Outlet/>`'s job, done as plain view composition. The cost is one `reduce`; the gain is no routing-specific rendering primitive — the chain is an ordinary subscription, and the layout is the same `case`/`reduce` you'd write for any conditional view. The [tutorial](tutorial.md#step-7--a-shared-layout) builds this up step by step.

## Loaders: declaring a page's data

A route can declare what data to load when it becomes active, so a page's data-needs live next to its URL instead of scattered through a dozen `componentDidMount`s. The simplest form is `:on-match`: a vector of ordinary event vectors the runtime dispatches, in order, whenever the route activates — *including* when the same route re-activates with **changed** params. Identical params don't re-fire, so you never get accidental double-loads from a no-op navigation.

```clojure
(rf/reg-route :app/cart
  {:on-match [[:cart/load-items]
              [:user/load-prefs]]}   ;; dispatched in order, on every activation
  "/cart")
```

`:on-match` events run server- *and* client-side (SSR populates the same data through the same vector), and they're enumerable — `(rf/handler-meta :route :app/cart)` returns the list, so tooling can draw a route's data-dependency graph. Each event reads the freshly-written route slice through a [coeffect](../core/concepts/coeffects.md) (a fact the framework injects into a handler) or the route subs (`:rf.route/params`, `:rf.route/query`), so you don't hand-wire params into the event vector.

> **Coming from React Router or Remix?** `:on-match` *is* the route loader — but as a list of event vectors, not a function. Because it's data, you can read it, test it, and draw a dependency graph from it without running it. And it runs on the server through the exact same vector, so there's no separate "server loader" to keep in sync.

### When a loader fails

If any `:on-match` event errors, the runtime flips `:rf.route/transition` to `:error`, populates `:rf.route/error` with the structured error, and — if the route declares `:on-error` — dispatches that event so you can respond (toast, redirect, contextual error UI):

```clojure
(rf/reg-route :app/cart
  {:on-match [[:cart/load-items]]
   :on-error [:app/cart-load-failed]}   ;; dispatched if a loader throws
  "/cart")

(rf/reg-event :app/cart-load-failed
  (fn [{:keys [db] rt :rf.db/runtime} _]
    (let [error (get-in rt [:rf.runtime/routing :current :error])]   ;; route slice is runtime-db
      {:db (assoc-in db [:cart :load-error] (:rf.error/message error))})))
```

Routes with no `:on-error` simply leave `:transition :error` set; a view over `:rf.route/error` can render a banner. Either way, the structured-error trace still fires on the wire — `:on-error` is the route's *response*, layered over the framework's [error contract](../core/concepts/errors.md), not a replacement for it.

> **Two subtleties worth knowing.** (1) *Later loaders still run.* re-frame2's [run-to-completion](../core/glossary.md#drain--run-to-completion) drain doesn't cancel events already queued, so `:on-match [[:a] [:b]]` runs `:b` even if `:a` threw. A loader that must short-circuit on a sibling's failure reads `:rf.route/transition` and self-guards. (2) *First error wins.* If both throw, `:rf.route/error` records the first, and `:on-error` fires exactly once. The slice always lands on `:error` regardless of how the events interleave.

### Declaring resources instead

If the page's data is [server state managed as resources](../resources/concepts.md) — a [resource](../resources/glossary.md#resource) being a declared, cached unit of server data — declare it as data with the `:resources` key instead (available when both `re-frame.routing` and `re-frame.resources` are loaded):

```clojure
;; Adapted from examples/real-apps/realworld_resources/routing.cljs
(rf/reg-route :realworld.article/show
  {:params [:map [:slug :string]]
   :scroll :top
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

> **Coming from Remix?** This is the loader — as data. `:blocking? true` is the `await`: it holds the route transition (and, on the server, the render) until the resource settles. Non-blocking entries fetch in the background. `:keep-previous? true` keeps the old page on screen while the next one first-loads, so a slug change doesn't flash an empty article. A fourth flag, `:when` — a `(fn [route ctx] …)` predicate — makes an entry *conditional*: the resource is planned only when the predicate is truthy, which beats threading a sentinel `nil` param to mean "don't load yet".

Here's the bug this design quietly kills. On route entry, the runtime marks each listed resource active, owned by the route, keyed by *this navigation's* **nav-token**. On route leave — or the moment a newer navigation supersedes this one — ownership is released by token, and any stale reply that lands late is *suppressed* rather than written. That's the classic race where you click away, an old fetch resolves a beat too late, and clobbers the page you're now looking at. Fixed once, in the substrate, instead of in every page you'll ever write.

> **Going deeper.** The nav-token is just a counter. Every navigation gets the next number, and that number answers "which navigation am I?". Suppression uses it as a receipt check: a result is written only if its token still matches the live navigation's; otherwise it's quietly dropped. And it isn't a `:resources`-only trick. A hand-rolled async loader can capture the live token (declare the `:rf.route/nav-token` cofx) and either compare it when the reply lands, or route the reply through the `:rf.route/with-nav-token` effect wrapper, which does the check for you. Resources do all of this automatically; hand-rolled loaders opt in. If the host can also abort the in-flight fetch, great — that saves bandwidth — but abort is an optimisation *on top of* suppression, never a substitute, because an abort isn't guaranteed to win the race.

When a resource is per-user, scope it with a **named scope resolver**. Register the resolver once, then reference it everywhere as `{:from-db ...}` — so the "who is this for?" logic lives in exactly one place:

```clojure
;; Adapted from examples/real-apps/realworld_resources/scope.cljs + routing.cljs
(rf/reg-resource-scope :realworld/session
  {:inputs {:username [:db [:auth :user :username]]}}
  (fn [{:keys [username]} _ctx]
    (when username
      [:rf.scope/session {:username username}])))

;; The personalised feed, as a route resource:
{:resource :realworld/feed
 :scope    {:from-db :realworld/session}
 :params   (fn [route] {:page (get-in route [:query :page])})
 :blocking? false}
```

The runtime resolves `{:from-db :realworld/session}` against [app-db](../core/concepts/app-db.md) at route entry, and the resolution **fails closed**. Logged out, the resolver returns `nil` and the feed simply isn't loaded — that surfaces as a visible route error, never a quiet fall-back to a shared cache. So one user's feed can never leak into another's session. Security by construction, not by remembering to check.

## Blocking a navigation

Navigation can be *blocked*, which is how you build an "are you sure? you have unsaved changes" guard. A route declares `:can-leave [:editor/can-leave?]`, a subscription naming the **positive** case (`true` means "yes, leaving is fine"). When it returns `false`, the navigation simply doesn't happen — no URL change, no state write. The attempted navigation parks in a pending slot that your confirm dialog renders from, and the user's choice is itself a dispatch: either `:rf.route/continue` or `:rf.route/cancel`.

```clojure
(rf/reg-route :app/article-editor
  {:params    [:map [:id :string]]
   :can-leave [:editor/can-leave?]}   ;; a sub: true ⇒ leaving is fine
  "/articles/:id/edit")

;; The pending navigation surfaces through its own sub, read here with the
;; routing/sub-pending-navigation sugar — your dialog renders from it:
(rf/reg-view leave-guard-dialog []
  (when-let [pending @(routing/sub-pending-navigation)]
    [:div.modal
     [:p "You have unsaved changes. Leave anyway?"]
     [:button {:on-click #(dispatch [:rf.route/cancel])}   "Stay"]
     [:button {:on-click #(dispatch [:rf.route/continue])} "Discard & leave"]]))
```

When the guard blocks, the runtime also dispatches `:rf.route/navigation-blocked` and emits a matching trace, so you can react (toast, analytics) beyond just rendering the dialog. To leave *without* asking — a "save then navigate" button, say — pass `{:bypass-guards? #{:leave}}` in the navigate opts.

### Guarding *entry* — `:can-enter`

`:can-enter` is the first-class mirror of `:can-leave`: it guards navigation **into** a route, which is exactly the shape of an auth gate ("you must be signed in to reach this route"). It runs on the same one navigation gate, so it refuses a forbidden entry through *every* door — a programmatic nav, a link click, a URL-bar deep-link, or a Back/Forward — with no per-door wiring.

```clojure
(rf/reg-route :app/account
  {:can-enter [:auth/signed-in?]}          ;; a sub: true ⇒ entering is fine
  "/account")

(rf/reg-sub :auth/signed-in?
  :<- [:auth/user]
  (fn [user _] (some? user)))

;; On a block the runtime dispatches :rf.route/entry-blocked (the mirror of
;; :rf.route/navigation-blocked) — the natural place to redirect to login and
;; remember where the user was headed for a post-login bounce-back:
(rf/reg-event :rf.route/entry-blocked
  (fn [{:keys [db]} [_ {:keys [requested-url]}]]
    {:db (assoc-in db [:auth :return-to] requested-url)
     :fx [[:dispatch [:rf.route/navigate :app/login]]]}))
```

`:rf.route/continue` re-runs `:can-enter` on resume — so after the login flow completes and dispatches `[:rf.route/continue pn-id]`, the gate re-evaluates (now signed in, it passes) rather than sailing a still-signed-out user through. The [realworld_http example](../../examples/real-apps/realworld_http) shows the full shape: `:requires-auth` routes declare `:can-enter`, and one `:rf.route/entry-blocked` handler does the redirect.

**Auth-on-a-route is `:can-enter`, not an interceptor.** Reach for an interceptor only for a policy that spans *many* routes uniformly (a whole gated section, a maintenance lockout) — and when you do, attach it to the frame's `:interceptors` so it covers all three doors (guarding only `:rf.route/navigate` is the classic fail-open bug).

> **For JavaScript developers.** `:can-leave` replaces React Router's `useBlocker` / `usePrompt` and the old `beforeunload` hack; `:can-enter` replaces the `loader`-throws-`redirect` / `<ProtectedRoute>` wrapper pattern. Both are *subscriptions* (declarative, derive from state), and the pending navigation is *state you render from*, so your confirm dialog (or redirect) is ordinary re-frame2, not an imperative hook. No modal-automation flakiness, no native-dialog ugliness.

> **Gotcha — the guard subs are strict.** `:can-leave` / `:can-enter` are a **boolean** contract: `true` allows, `false` blocks. Return anything else (a nil, a map, a truthy non-boolean) and the runtime **blocks the navigation** *and* emits `:rf.error/can-leave-non-boolean` / `:rf.error/can-enter-non-boolean` — it fails closed (deny) and fails loud (raise), never open. Keep the guard sub returning a real `true`/`false`.

The payoff is that the entire flow is testable with zero DOM. Dispatch the navigate, assert the pending slot filled (`@(routing/sub-pending-navigation)`), dispatch `:rf.route/continue`, assert it went through — no browser, no `beforeunload`, no flaky modal automation. The editor routes in [examples/real-apps/realworld_resources](../../examples/real-apps/realworld_resources) show the shape, and [Guard against unsaved changes](how-to/guard-unsaved-changes.md) is the step-by-step recipe.

## Not found is a route you register

When no pattern matches a URL — or a URL matches but its params fail their schema — the runtime activates the reserved id `:rf.route/not-found`, dropping the offending URL into `:params`. You **must** register it. It's an ordinary route, so it can carry its own loaders and scroll behaviour:

```clojure
(rf/reg-route :rf.route/not-found
  {:on-match [[:analytics/log-404]]
   :scroll   :top}
  "/404")
```

The not-found `:params` slot carries a `:reason` discriminator you can branch on in the view, so you can tell a plain miss from a malformed URL from a schema failure:

| `:params` shape | What happened |
|---|---|
| `{:url "/no/such/page"}` | No registered route matched the URL. |
| `{:url "/articles/abc" :reason :validation}` | A route matched, but the URL's params failed the route's `:params` / `:query` schema. |
| `{:url "/bad%ZZ" :reason :malformed-url}` | The URL carried malformed percent-encoding — it fails *closed* to a 404, never crashes the handler (important for hostile or partner-supplied links, especially on SSR). |

!!! warning "Register your own not-found route"

    Forget it and the runtime warns (`:rf.warning/no-not-found-route`) and falls back to a built-in placeholder — so the request still renders *something*, just not your design. Every real app registers its own.

> **Gotcha — schema failures behave differently by entry point.** A *URL-driven* schema miss (a deep link, a back-button) is user input, so it's a 404 — never an exception. A *programmatic* miss is caller code, so it's loud: `route-url` **throws** `:rf.error/route-url-validation`, and `[:rf.route/navigate ...]` **rejects** the navigation with `:rf.error/schema-validation-failure` (the slice doesn't change). Same schemas, opposite failure modes — bugs in *your* code throw; bad input from *the world* 404s.

## Keeping tokens off the wire

A route slice can hold secrets — an OAuth `?token=`, a `?code=` callback param. While the route is active, those values are live and your handlers and views see them normally. But you almost certainly *don't* want them copied onto the trace bus, into Xray, through the MCP pair tools, or into the SSR payload. The `:sensitive` and `:large` keys classify slice paths so the runtime redacts (or size-markers) them at *egress* — declared once, relative to the route's own `{:query ... :params ...}` projection, never naming the absolute storage position:

```clojure
(rf/reg-route :app/oauth-callback
  {:query     [:map [:token :string] [:code :string]]
   :sensitive [[:query :token] [:query :code]]   ;; redacted at egress while active
   :large     [[:params :payload]]}              ;; kept off the wire (size marker only)
  "/oauth/callback")
```

`:sensitive` redacts a value's content; `:large` keeps a bulky value off the wire (emitting only a size marker). A path declared both lowers as `:sensitive` only. Classification is applied atomically when the route activates and dropped when it changes — so a route declaring none (including `:rf.route/not-found`) clears the previous route's classification cleanly. Crucially, it's *egress-only*: `@(routing/sub-route)` in your running app always returns the real values; only the copies that leave the box are redacted.

> **Gotcha — classify only query keys the route declares.** A classification path like `[:query :token]` names the keyword `:token`, and the slice only holds a query value under a keyword when the route declares that key — in `:query`, `:query-defaults`, or `:query-retain`. Classify a key the route never declares and there'd be nothing at that path to redact, so `reg-route` warns (`:rf.warning/route-classification-query-key-unpromoted`) and points at the fix: add the key to the `:query` schema. Path params can't have this problem — captures are always keyword-keyed. And a structurally malformed declaration (a non-vector axis, a bad path segment) fails loud at registration with `:rf.error/invalid-route-classification`.

Routing's classification is the framework-wide [data-classification](../core/glossary.md#data-classification) model applied to the singleton current-route — [Keep secrets out of traces](../core/how-to/keep-secrets-out-of-traces.md) is the full story; routes just declare the paths.

## The browser is just another event source

Back/forward, deep links, and the initial page load are all, underneath, URL changes arriving *into* the app. Wiring them up is two lines at boot:

```clojure
;; Adapted from examples/capabilities/routing/routing/core.cljs (client-only)
(rf/reg-frame :rf/default {:doc "The app frame." :url-bound? true})
(rf/install-url-listener!)
```

`:url-bound? true` declares which [frame](../core/concepts/frames.md) owns the browser URL. Ownership is always explicit, never inferred. Only one frame may hold it — a second claimant is a loud `:rf.error/duplicate-url-binding` — and having *no* owner is legal too: URL pushes and the URL-change listener simply no-op.

Frames without the flag still route internally, in memory. That's a feature, not a gap: a [Story](../core/concepts/observability.md) variant can sit on `/article/intro` without touching your address bar, and a test frame never calls `pushState`.

`install-url-listener!` installs the URL-change listener (aimed at whichever frame owns the URL) and syncs the URL into state once at startup, so deep links and refreshes land on the right page. It's idempotent — hot-reload can call it again — and `rf/remove-url-listener!` tears it down. (`install-history-listener!` is retained as an alias — history is the default strategy, so the two behave identically for a path-URL app.)

> **From re-frame v1.** This is where the inversion lands hardest. In v1 the browser URL was the source of truth and your router *reacted* to it. Here the frame's state is the source of truth and the URL is a *print-out* of it. So a back-button press is, literally, a dispatch: popstate fires, the URL-change handler runs, the slice updates, the views re-derive. And [time-travel](../xray/index.md) falls out for free — rewind the frame and the URL rewinds with it, because the URL was never truth, only a projection.

### URL strategies

By default the router speaks **path-form** URLs (`/active`) — it uses the HTML5 History API and a `popstate` listener. But plenty of apps live at a **hash** URL (`#/active`): no-server-rewrite static hosting needs it, and it's the shape most secretary-era re-frame v1 apps have. A **`:url-strategy`** on the URL-owning frame switches the address-bar shape without changing anything else:

```clojure
(rf/reg-frame :app {:url-bound?   true
                    :url-strategy rf/hash-url-strategy})   ;; default: rf/history-url-strategy
```

That one line is all it takes. `route-url` and `match-url` stay path-form — they still build and read `/active` — and the strategy encodes the `#` at exactly four edges: the `route-link` href, `pushState`, `replaceState`, and the URL-change listener (a hash app listens for `hashchange`, a history app for `popstate`). So your links are ordinary `route-link`s, navigation is ordinary `:rf.route/navigate`, and Back/Forward flows through the same event path — the `#` is a rendering detail the strategy owns. Two strategies ship, and the line holds at two:

- `rf/history-url-strategy` — the default, path-form.
- `rf/hash-url-strategy` — `#`-prefixed, for static hosting and v1 migrations.

Memory URLs need no third strategy: a frame that isn't `:url-bound?` is already URL-free, so a Story variant or a test frame is the "memory" case for free.

When your URL-owning frame is created *asynchronously* — e.g. by a `frame-provider` whose React effect registers it just after mount — pass the strategy explicitly so the listener kind doesn't depend on registration timing: `(rf/install-url-listener! rf/hash-url-strategy)`. The [TodoMVC example](https://github.com/day8/re-frame2/tree/main/examples/core/todomvc) is a hash app wired exactly this way.

> **SSR ignores strategies.** On the server there's no address bar and a hash never arrives — the request URL is fed in path-form and `route-link` renders a path-form `<a href>` shell. On hydration the client re-encodes the href through the frame's strategy, so the server ships `/active` and the hydrated anchor becomes `#/active`, both pointing at the same route. The shape is a client-only concern.

> **Frames seed with events, not config.** Note the frame carries `:doc` and `:url-bound?` and nothing else — there's no `:db` or `:initial-db` config key. A frame's startup state is built by dispatching ordinary events via `:initial-events`: `(rf/reg-frame :app {:initial-events [[:rf/set-db {…}] [:app/initialise]]})`. "Events are the unit of state change" holds even for the very first state. See [Frames](../core/concepts/frames.md) for the full lifecycle.

### Converting routes ↔ URLs by hand

When you need to convert between a route and a URL by hand, two pure helpers do it on both JVM and browser, so you never hand-concatenate a query string again:

```clojure
;; Both live in re-frame.routing — the URL codec is in the routing artefact,
;; not on the rf/ facade. Pure, JVM- and CLJS-runnable, and exact inverses.

(routing/route-url :app/article {:id "intro"})
;; => "/articles/intro"

(routing/route-url :app/search {} {:q "clojure" :page 2} "results")
;; => "/search?q=clojure&page=2#results"   (4-arity: params, query, fragment)

(routing/match-url "/articles/intro")
;; => {:route-id :app/article :params {:id "intro"} :query {} :fragment nil :validation-failed? false}

(routing/match-url "/no/such/page")
;; => nil
```

`route-url` has 2-, 3-, and 4-arities (params; + query; + fragment), and it's *pure*: no `pushState`, no dispatch, no app-db read — same inputs, same string out. It throws `:rf.error/route-url-validation` if your params don't conform to the route's schema (a caller bug). `match-url` returns the match map, or `nil` for no match, or `:validation-failed? true` when a route matched but its params failed schema.

> **Gotcha — `route-url`'s nil-policy differs for path vs query.** A `nil` (or empty-string) **path** param is a hard error (`:rf.error/missing-route-param`) — there's no URL to build without the segment. A `nil` **query** param is *silently elided* — `{:page nil}` just omits the key, which is exactly what you want for "only add `?sort=` when a sort is chosen". Falsy-but-present values (`false`, `0`) round-trip on both sides. The asymmetry surprises people, so it's worth pinning in your memory: missing path segment = throw; missing query key = drop.

## The same handler runs on the server

This is the payoff of routing having no runtime of its own. During [server-side rendering](../ssr/concepts.md), the request URL is fed to the *same* URL-change handler, against a per-request frame. The slice is written, `:on-match` fires, blocking `:resources` give the server its wait-point before render, and the resulting state ships to the client, where hydration installs it **without re-fetching** because the data is already there. There's no server router, no client router, and no seam between them — one place where URLs become state, one place where state becomes URLs.

> **Going deeper.** This is what "the URL is a sub" buys you structurally. Because the route is a *derivable view* of state — a projection, not an independent variable — the same pure function (`match-url`) turns a request URL into a slice on the JVM and in the browser, and the same inverse (`route-url`) turns a slice back into a string on both. SSR isn't a parallel implementation that has to be kept in sync; it's the *same* implementation pointed at a different event source. The seam between server and client routing doesn't exist because there was never a second router to seam against.

!!! note "What is client-only"

    The URL-push and scroll effects are no-ops on the server, where there's no address bar, and `install-history-listener!` does nothing server-side because there's no popstate to listen to. Everything else — your route table, handlers, loaders, and guards — runs unchanged on both sides. `route-url` and `match-url` are pure and run identically on the JVM, which is exactly why SSR can build links and parse the request URL with the same code.
