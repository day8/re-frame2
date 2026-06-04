# 19 - Routing

The URL is application state. Your back button is a dispatch. A route change is just an event. If you've ever fought a router that wanted to own a parallel universe of state next to your real state — its own context, its own lifecycle hooks, its own idea of where the truth lives — this chapter is routing that doesn't do that. In re-frame2 the URL is a derivable view of `app-db`, navigation is an event, and the active route is a subscription. Same registry, same dispatch, same time-travel, same tests as everything else you've built.

## The one-line thesis

**The URL is a sub.**

Hold that, because it's the whole chapter compressed. The current route lives at `[:rf/runtime :routing :current]` in `app-db`. Views read it through an ordinary subscription (`(rf/subscribe [:rf/route])`). The root view dispatches on `:rf.route/id` to pick which page to show. Navigation — programmatic, link-click, or the browser's own back/forward — all funnels through the same handler. There is no separate routing runtime, no route-aware components, no "router context" you have to thread. Routing is just data reflected in the address bar.

And to be clear about the bar this clears: that's feature parity with React Router, TanStack Router, and reitit's frontend module — deep links, nested layouts, blocked navigation, scroll restoration, the lot — while never once leaving re-frame2's single mental model. You already know how to handle state that's a value, derived through subs, mutated through events. The URL is that, and nothing more.

## The artefact, and why it's separate

Routing ships as its own per-feature artefact, `day8/re-frame2-routing`:

```clojure
{:deps {day8/re-frame2          {...}
        day8/re-frame2-routing  {...}}}
```

This isn't bureaucracy — it's the modularity strategy paying off. An app that doesn't route doesn't bundle a byte of routing code, and the bundle-isolation CI gate *enforces* that: core's release build is asserted to carry zero routing-namespace markers, and re-importing `re-frame.routing` from core fails the build. Once the artefact is on the classpath, one `(:require [re-frame.routing])` at app boot wires the late-bind hooks, and from then on `reg-route`, `:rf.route/navigate`, the `:rf/route` sub family, and the `:rf.route/...` effects are all available through plain `re-frame.core`. (The state itself lands at `[:rf/runtime :routing]` in `app-db`; `:rf/route` is the sub-id that projects out the consumer-facing slice.)

## The smallest routing loop that does anything

Concepts before notation, so before the full slice and the whole grammar, here's the entire idea in three moves — one route, one navigation, one sub:

```clojure
(ns example.routing
  (:require [re-frame.core :as rf]))

;; 1. A route is data in the registry.
(rf/reg-route :route/cart
  {:path "/cart"})

;; 2. Navigation is an event.
(rf/dispatch [:rf.route/navigate :route/cart])

;; 3. The view reads the active route through an ordinary subscription.
(rf/reg-view app-root []
  (case @(rf/subscribe [:rf.route/id])
    :route/cart  [:h1 "Cart"]
    [:h1 "Home"]))
```

That's it. A route is a registry entry, navigating is dispatching an event, and the root view `case`s over `:rf.route/id` to decide what to render. Every other thing in this chapter — path params, query strings, data-loading, the unsaved-changes guard, stale-result suppression — is a refinement of those three moves. If you understand this loop, you understand routing; the rest is detail you reach for when a feature needs it.

## Registering a route

Routes are registry entries with the same registration shape as events, subs, and effects:

```clojure
(rf/reg-route :route/home
  {:doc  "The landing page."
   :path "/"})

(rf/reg-route :route/cart
  {:doc      "The cart page."
   :path     "/cart"
   :on-match [[:cart/load-items]]})

(rf/reg-route :route/cart.item-detail
  {:doc    "Detail page for a single cart item."
   :path   "/cart/items/:id"
   :params [:map [:id :uuid]]})

(rf/reg-route :route/article
  {:doc    "An article, with an optional slug suffix."
   :path   "/articles/:id{/:slug}?"
   :params [:map [:id :uuid] [:slug {:optional true} :string]]})

(rf/reg-route :route/files
  {:doc    "A files browser; matches /files and any sub-path."
   :path   "/files/*rest"
   :params [:map [:rest :string]]})
```

The path grammar is small — five productions, and you can parse them in your head: a **literal segment** (`/articles`), a **named param** (`:id`), an **optional segment group** (`{/:slug}?`), a **catch-all splat** (`*rest`), and the **root** (`/`). It's an RFC-6570-Level-1 subset plus the splat extension, deliberately hand-parseable in any host language.

When two routes could both match the same URL, a structural ranking cascade picks the winner — the four load-bearing rules being: more static segments beat fewer, longer paths beat shorter, named params beat splats, exact patterns beat optional-group patterns. The key word is *structural*: the score is computed from each pattern's parsed shape with no URL in hand, so implementations pre-compute it at registration time. There's no runtime ambiguity to debug — the precedence is a function of the patterns, knowable before any request arrives.

## The route slice — where the URL lives as state

That navigation back in the smallest loop wrote something into `app-db`. The runtime maintains exactly one such slice — at `[:rf/runtime :routing :current]` — and it is the source of truth every route-aware sub reads from:

```clojure
{:rf/runtime
 {:routing
  {:current
   {:id           :route/article             ;; current route id
    :params       {:id #uuid "..."}          ;; path params (matches :params schema)
    :query        {:q "clojure" :page 2}     ;; query/search params (matches :query schema)
    :fragment     "section-2"                ;; URL #fragment, or nil
    :transition   :idle                      ;; :idle | :loading | :error
    :error        nil                        ;; populated when :transition = :error
    :nav-token    "nav-42"}}}}               ;; per-navigation epoch token
```

A few things worth internalising about this slice:

The whole `[:rf/runtime :routing]` subtree is **reserved** — the routing runtime owns it, and your code reads it but never writes it directly. (That "runtime-managed slice you read but don't write" property is a recurring shape; [chapter 21](21-dynamic-model.md) is the whole pattern.) The consumer-facing sub-id is `:rf/route`, which projects the slice at `[:rf/runtime :routing :current]`.

Path params (`:params`) and query params (`:query`) are kept as **distinct maps** — captured separately, validated against separate schemas, never silently merged. If a consumer wants them merged, it builds the merged map in a derived sub; the slice keeps them honest.

`:transition` is a tiny FSM the runtime drives for you: `:idle` when nothing's in flight, `:loading` while the active route's `:on-match` events drain, `:error` if any of them fail. This is the hook for the boring-but-essential global UX: a progress bar reads `:rf.route/transition` and shows itself when it's `:loading`; an error banner reads `:rf.route/error`. You don't wire up loading state per page — it's a property of the slice.

## Navigation is an event

You navigate by dispatching, the same verb you use for everything else:

```clojure
;; By route-id
(rf/dispatch [:rf.route/navigate :route/cart])

;; With path params
(rf/dispatch [:rf.route/navigate :route/cart.item-detail {:id "abc-123"}])

;; With query params
(rf/dispatch [:rf.route/navigate :route/search {} {:q "clojure" :page 2}])

;; With a fragment
(rf/dispatch [:rf.route/navigate :route/docs {:page "routing"} {} "scroll-restoration"])

;; Replace rather than push (login redirects, redirect-on-load)
(rf/dispatch [:rf.route/navigate :route/login {} {} nil {:replace? true}])

;; URL-string escape hatch, for dynamic / user-supplied URLs
(rf/dispatch [:rf.route/navigate {:url "/some/path"}])
```

Prefer the route-id form. A route-id is enumerable, refactorable, and queryable through the registrar — rename a route's path and every navigation to it updates for free. URL-strings are stringly-typed by nature and tools can (and do) flag them as migration candidates; reach for the escape hatch only when the URL is genuinely dynamic.

When a navigation event fires, three things happen in a **locked order**:

1. The route slice at `[:rf/runtime :routing :current]` updates — id, params, query, fragment, transition, fresh nav-token.
2. The browser URL is pushed, via the `:rf.nav/push-url` effect (which is `:platforms #{:client}` — see [the server side](20-server-side.md) for why that matters).
3. The route's `:on-match` events dispatch in order, and the route's scroll strategy emits as a `:rf.nav/scroll` effect.

State first, URL second, data-loading third. The ordering is the contract: if the URL push fails — browser denies it, the user's offline — your state is still consistent, because state changed *before* the address bar did, not after.

## URL changes are also events

The flip side of "navigation is an event" is that *every* way the URL can change comes back in as an event too — and there are two of them, one per direction the URL moves. A link click or a programmatic push fires `:rf.route/transitioned`; a back/forward press, an initial page load, or the SSR request URL fires `:rf.route/handle-url-change`. They're **co-equal siblings**, not a delegate pair: each runs the identical slice-rewrite, and they differ only in their default scroll strategy — `:top` for forward nav, `:restore` for popstate (so Back returns you to where you were). Both:

1. Call `match-url` to resolve the URL against the registered route table.
2. Set the route slice at `[:rf/runtime :routing :current]` with the matched id, params, query, fragment, transition, and a fresh `:nav-token`.
3. Dispatch the matched route's `:on-match` events.

```clojure
;; The handle-url-change handler runs for popstate, initial load, and SSR alike.
(rf/reg-event-fx :rf.route/handle-url-change ...)
```

And here's a payoff worth pausing on: that *same handler runs on the server during SSR*. The request URL is fed in, the route slice is set, the `:on-match` events fire and populate server-rendered data — exactly as they do in the browser. There is **no SSR-specific routing code**, because there's no SSR-specific anything; it's the same handler fed a different URL. ([Chapter 20](20-server-side.md) leans on this hard.)

You'll also occasionally see `:rf.route/fragment-changed` in the trace stream. It's a *trace* event, not a runtime event, fired when the URL changes only in its `#fragment`. The runtime updates `:fragment` in the slice but deliberately does **not** re-fire `:on-match` — `:on-match` exists to reload route-scoped *data*, and a fragment-only change moves the in-page anchor, not the data. If your view subscribes to `:rf.route/fragment` it'll re-render; if it only watches `:rf.route/id`, fragment changes don't ripple through. That's the correct economy.

## Reading the route is a sub — and linking from views

Views derive UI from the route exactly the way they derive UI from any other state. There is no special routing API inside views:

```clojure
@(rf/subscribe [:rf/route])               ;; the full slice map
@(rf/subscribe [:rf.route/id])            ;; just the route id
@(rf/subscribe [:rf.route/params])        ;; path params
@(rf/subscribe [:rf.route/query])         ;; query params
@(rf/subscribe [:rf.route/fragment])      ;; #fragment string or nil
@(rf/subscribe [:rf.route/transition])    ;; :idle | :loading | :error
@(rf/subscribe [:rf.route/error])         ;; structured error map when :error
```

The root view is a `case` over `:rf.route/id` — this is *the* routing idiom, and it's just a function from route-id to page view:

```clojure
(rf/reg-view app-root []
  (case @(subscribe [:rf.route/id])
    :route/home              [home-page]
    :route/cart              [cart-page]
    :route/cart.item-detail  [cart-item-detail]
    :route/article           [article-page]
    :rf.route/not-found      [not-found-page]))
```

A per-route view reads `:rf.route/params` for its own data needs:

```clojure
(rf/reg-view article-page []
  (let [{:keys [id slug]} @(subscribe [:rf.route/params])]
    [:article
     [:h1 "Article " id]
     (when slug [:p.slug slug])]))
```

For links, use `route-link`:

```clojure
[rf/route-link {:to :route/cart} "Cart"]
[rf/route-link {:to :route/article :params {:id "abc"}} "Read more"]
[rf/route-link {:to :route/search :query {:q "clojure"}} "Search"]
```

`route-link` renders a real `<a href="...">` and intercepts plain primary-button clicks — `.preventDefault` then dispatch `:rf/url-requested`. Crucially, it gets the boring stuff right: **modifier-key clicks defer to the browser.** Cmd-click, middle-click, shift-click follow the `href` natively and open in a new tab, the way users expect and the way most hand-rolled SPA link components forget to.

One sharp edge worth knowing: plain anchors (`[:a {:href "..."}]`) in your view code are **not** intercepted — they cause native full-page navigation. If you want SPA interception on plain anchors, that's installed at the host adapter layer (a top-level document `click` listener consulting `match-url`), not by the framework. The reason is principled: a global click listener is host-bound and would conflict with non-routed anchors inside iframes, shadow DOM, or third-party widgets — only the host adapter has the context to install it safely. The framework's contract stops at `route-link` plus `:rf/url-requested`.

## Per-route data loading: `:on-match`

A route declares the events the runtime should dispatch whenever that route becomes active. This is how a page loads its data:

```clojure
(rf/reg-route :route/cart
  {:doc      "The cart page."
   :path     "/cart"
   :on-match [[:cart/load-items]
              [:user/load-prefs]]})
```

The semantics, and they're carefully chosen:

1. When the route becomes active — URL-driven or programmatic — the runtime dispatches each `:on-match` event in order, *after* writing the route slice at `[:rf/runtime :routing :current]` and *before* any data-dependent view renders.
2. `:rf.route/transition` is `:loading` while the dispatches drain, back to `:idle` when they finish. That's your loading-state, for free.
3. Re-navigating to the **same** route-id with **changed** params or query *does* re-fire `:on-match` — the route is becoming active again under new inputs. Re-navigating with identical params does **not** re-fire; the runtime compares the post-update slice against the pre-update slice and skips when nothing relevant changed. No accidental double-loads.
4. `:on-match` runs server- and client-side, so SSR populates the same data via the same vector. Hydration does **not** re-fire it — the seeded `app-db` already carries the data, and re-fetching it would defeat the entire point of SSR.
5. Each `:on-match` entry is an ordinary event vector whose handler can emit any `:fx` (typically a managed HTTP request). And the list is *enumerable* — `(rf/handler-meta :route :route/cart)` hands tooling the metadata, so an AI or a teammate can render the route's load-dependency graph without parsing handler bodies.

That last property is the quiet win: `:on-match` is the **machine-readable answer to "what loads when this route is active?"** — not buried in imperative `useEffect` calls scattered across components, but declared as data on the route.

### The stale-result problem, and the nav-token

There's a classic async bug lurking here, and re-frame2 handles it so you mostly don't have to think about it — but you should know it's being handled. Suppose an `:on-match` event kicks off an HTTP load, the user gets bored and navigates away before it lands, and then the *old* load completes and clobbers the new page's state with stale data. Every SPA has shipped this bug at least once.

The fix is the **navigation token**. Each navigation gets a fresh token written into the route slice; an async result is committed only if the token it carries still matches the current one.

```clojure
;; Showing only the relevant sub-tree at [:rf/runtime :routing :current] for clarity.

;; Step 1: user navigates to article "A". nav-token = "nav-1".
{:id :route/article :params {:id "A"} :transition :loading :nav-token "nav-1"}

;; Step 2: while that's in flight, user navigates to article "B".
;; A fresh token is allocated.
{:id :route/article :params {:id "B"} :transition :loading :nav-token "nav-2"}

;; Step 3: the "A" load completes carrying "nav-1". Current is "nav-2".
;;         Mismatch → suppressed; a :rf.route.nav-token/stale-suppressed trace fires; no commit.

;; Step 4: the "B" load completes carrying "nav-2". Match → commit.
{:id :route/article :params {:id "B"} :transition :idle :nav-token "nav-2"}
```

You don't allocate or thread the token by hand. An `:on-match` handler receives the current token as a cofx, and the cleanest way to carry it through an async continuation is the framework-supplied wrapper:

```clojure
{:fx [[:rf.route/with-nav-token
       {:do        [:dispatch [:cart/items-loaded items]]
        :nav-token (:nav-token cofx)}]]}
```

On receipt, the framework checks the carried token against the slice's current one: **match** commits normally, **mismatch** suppresses the whole handler — no `:db` write, no `:fx`, no transition — and emits the `:rf.route.nav-token/stale-suppressed` trace. Suppression alone fixes the user-visible bug; hosts with abortable fetches *may* additionally cancel the superseded work to save bandwidth, but the contract only requires suppression. (This is the same "carry an epoch, drop stale replies" idiom a state machine uses for late `:after` timers — see [chapter 12](12-machines.md). One pattern, two surfaces.)

The mechanism is mostly invisible; you'll meet it the day you write an `:on-match` continuation that fetches.

## The not-found route — register it, it's required

Every app **must** register a `:rf.route/not-found` route. When a URL fails to match anything, the runtime sets the route slice at `[:rf/runtime :routing :current]` to `{:id :rf.route/not-found :params {:url <url>} ...}` and proceeds with that route's `:on-match` as normal:

```clojure
(rf/reg-route :rf.route/not-found
  {:doc      "404 page."
   :path     "/404"
   :on-match [[:analytics/log-404]]
   :scroll   :top})
```

It's an ordinary `reg-route` — `:on-match`, `:on-error`, `:scroll`, `:tags` all behave normally — and the root view's `case` renders the not-found page from the leaf like any other route. If you forget to register it, the runtime emits a `:rf.warning/no-not-found-route` trace and falls back to a built-in `<h1>Not Found</h1>` placeholder so the request still produces *a* response, but the conformance corpus and test fixtures assume the user-registered shape. URL-validation failures route here too (a path that matched but whose captured params failed the `:params` schema), tagged `:reason :validation` in the params slice.

## When you need more: the reference surface

The five moves above — register, navigate, read, load, fall through to not-found — are the spine, and most apps live almost entirely within them. The remaining routing surface is *reference-shaped*: independent features you reach for when a specific need shows up, not a linear sequence to read front-to-back. The next several sections are exactly that — skim them now, return when the need is real.

### Per-route error handling: `:on-error`

If any `:on-match` event errors, the runtime sets `:rf.route/transition` to `:error`, populates `:rf.route/error` with the structured error map, and — if the route declares one — dispatches an `:on-error` event:

```clojure
(rf/reg-route :route/cart
  {:path     "/cart"
   :on-match [[:cart/load-items]]
   :on-error [:route/cart-load-failed]})

(rf/reg-event-fx :route/cart-load-failed
  (fn [{:keys [db]} _]
    (let [error (get-in db [:rf/runtime :routing :current :error])]
      {:db (assoc-in db [:cart :load-error] (:rf.error/message error))})))
```

`:on-error` is *route-scoped* handling layered over the framework's structured-error contract — it doesn't replace it. The structured error trace still fires; `:on-error` is the route's specific response to it.

### Blocking navigation: the `:can-leave` protocol

Real products need to *stop* navigation sometimes — an unsaved form, an interrupted checkout, a half-finished destructive workflow. React's answer is a lifecycle-coupled hook (`useBlocker`). re-frame2's answer is what you'd now expect: a named-event-and-slot protocol where pending-nav state lives in `app-db`, the confirm dialog renders from an ordinary sub, and the user's choice is a dispatched event. Slightly more verbose at the call site; vastly more testable.

Declare the guard as a sub on the route — naming the *positive* case, so `false` means "do not leave":

```clojure
(rf/reg-route :editor/article
  {:path      "/editor/articles/:id"
   :params    [:map [:id :string]]
   :can-leave [:editor/can-leave?]})

(rf/reg-sub :editor/can-leave?
  :<- [:editor/dirty?]
  (fn [dirty? _] (not dirty?)))             ;; true ⇒ OK to leave
```

When a navigation is requested, the runtime evaluates the *current* route's `:can-leave` sub. `true` (or no guard) proceeds normally. `false` **blocks**: the URL does not change, no `pushState`, no update to the route slice at `[:rf/runtime :routing :current]`, no `:on-match` — instead the runtime writes to the pending-nav slot at `[:rf/runtime :routing :pending-navigation]` describing what was attempted:

```clojure
;; At [:rf/runtime :routing :pending-navigation] in app-db:
{:id                  "pn-7"
 :requested-by-event  [:rf/url-requested {:url "/editor/articles/42"}]
 :requested-url       "/editor/articles/42"
 :reason              "Form has unsaved changes"
 :rejecting-route     :editor/article
 :rejecting-guard     :editor/can-leave?}
```

The dialog renders from that slot like any other piece of state, and the buttons dispatch the user's decision:

```clojure
(rf/reg-view leave-confirmation []
  (when-let [pn @(subscribe [:rf/pending-navigation])]
    [:dialog.confirm-leave
     [:p (:reason pn)]
     [:p "Leaving will discard your changes."]
     [:button {:on-click #(dispatch [:rf.route/cancel   (:id pn)])} "Stay"]
     [:button {:on-click #(dispatch [:rf.route/continue (:id pn)])} "Leave"]]))
```

`:rf.route/continue` clears the slot and re-dispatches the original navigation, *bypassing the guard for that one shot*; `:rf.route/cancel` just clears the slot and nothing else changes. The whole protocol is testable with zero DOM: fire `[:rf/url-requested {:url "/cart"}]` against a frame whose guard returns `false`, assert `:rf/pending-navigation` is set and `:rf.nav/push-url` did *not* fire, dispatch `:rf.route/continue`, assert the navigation completes. No hook mock, no event simulation, no browser.

### Query strings, defaults, and retained keys

Path syntax is the primary binding; query strings bind separately via the route's `:query` metadata, with optional defaults and retain-keys:

```clojure
(rf/reg-route :route/search
  {:path           "/search"
   :query          [:map [:q :string] [:page {:optional true} :int]]
   :query-defaults {:page 1}
   :query-retain   #{:theme :locale}})

;; /search?q=clojure&page=2  → {:query {:q "clojure" :page 2}}
;; /search?q=clojure         → {:query {:q "clojure" :page 1}}  (default applied)
```

`:query-defaults` fills absent keys at match time. `:query-retain` carries keys through *subsequent* navigations even when the caller didn't supply them — exactly what you want for URL-encoded global state like `:theme`, `:locale`, or `:debug`, so a `[:rf.route/navigate :route/cart]` from a search page preserves the `?theme=dark`. Coercion is data-shaped: the `:query` schema *is* the coercion spec (`:int` turns `"2"` into `2`). No per-key middleware functions — data over functions, like everywhere else.

### Multi-frame routing

Each frame ([chapter 18](18-frames.md)) may carry its own route slice at `[:rf/runtime :routing :current]` — it's a regular `app-db` path, not a special concept — but **only one frame is URL-bound**:

| Frame | URL-bound? | Behaviour |
|---|---|---|
| `:rf/default` | yes (default) | `:rf.route/navigate` fires `:rf.nav/push-url`; popstate dispatches here; the browser bar reflects this frame's route. |
| Non-default | no (default) | `:rf.route/navigate` updates the frame's slice but does **not** push the URL. The address bar is untouched. |
| Non-default with `{:url-bound? true}` | yes (opt-in) | The runtime enforces single ownership — a second URL-bound frame is a `:rf.error/duplicate-url-binding` trace. |

This is what lets a Story variant render *"the cart at `/cart/items/abc` in the loaded state"* without hijacking the page's address bar, lets per-test frames route without ever touching `pushState`, and lets per-request SSR frames take the request URL with no client-side history to worry about.

### The pure helpers

Two pure, host-agnostic functions are part of the public surface:

```clojure
(rf/match-url url)
;; → {:route-id :route/search :params {} :query {:q "clojure"} :fragment nil
;;    :validation-failed? false}, or nil if nothing matches.

(rf/route-url :route/cart.item-detail {:id "abc"})
;; → "/cart/items/abc"   — a string. Does NOT navigate, read app-db, push, or dispatch.
```

Both run on JVM and CLJS, both resolve against the same registered route table, so adding or removing a route updates both directions automatically. `:rf.route/navigate` uses `route-url` internally to build the URL; `:rf.route/handle-url-change` uses `match-url` internally to resolve one. They're the same functions, exposed for when you need URL↔route translation without a navigation.

## A worked example, end to end

The [`examples/reagent/realworld/`](https://github.com/day8/re-frame2/tree/main/examples/reagent/realworld) app — a re-frame2 implementation of the [RealWorld spec](https://github.com/gothinkster/realworld) — has the canonical full-size routing setup. The route table is just data:

```clojure
(ns realworld.routes
  (:require [re-frame.core :as rf]))

(rf/reg-route :route/home
  {:path "/" :on-match [[:articles/load-feed]]})

(rf/reg-route :route/article
  {:path     "/article/:slug"
   :params   [:map [:slug :string]]
   :on-match [[:article/load] [:article/load-comments]]})

(rf/reg-route :route/editor
  {:path "/editor" :tags #{:requires-auth} :can-leave [:editor/can-leave?]})

(rf/reg-route :route/settings
  {:path "/settings" :tags #{:requires-auth}})

(rf/reg-route :rf.route/not-found
  {:path "/404"})
```

The root view is a `case` over `:rf.route/id` with the persistent chrome (navbar, footer, leave-confirmation dialog) around it:

```clojure
(rf/reg-view app-root []
  (let [route-id @(subscribe [:rf.route/id])]
    [:div.app
     [navbar]
     [leave-confirmation]              ;; renders the pending-nav dialog
     (case route-id
       :route/home          [home-page]
       :route/article       [article-page]
       :route/editor        [editor-page]
       :route/settings      [settings-page]
       :rf.route/not-found  [not-found-page])
     [footer]]))
```

And the auth guard — *"redirect to login if this route needs auth and you're not logged in"* — is not a special routing mechanism at all. It's an ordinary interceptor on `:rf.route/navigate` that reads the route's `:tags`:

```clojure
(def auth-guard
  (rf/->interceptor
    :id     :rf.route/auth-guard
    :before (fn [ctx]
              (let [target      (second (get-in ctx [:coeffects :event]))
                    needs-auth? (some #{:requires-auth} (:tags (rf/handler-meta :route target)))
                    logged-in?  (some? (get-in ctx [:coeffects :db :auth :user]))]
                (if (and needs-auth? (not logged-in?))
                  (assoc-in ctx [:coeffects :event]
                            [:rf.route/navigate :route/login {} {} nil {:return-to target}])
                  ctx)))))
```

Guards are interceptors. They compose; you can layer several. (The interceptor primitive and the sandwich shape are [chapter 09](09-interceptors.md).) And every line of this routing setup runs **unchanged on the server under SSR** — the request URL feeds `:rf.route/handle-url-change`, `:on-match` populates the data, the view renders against it, HTML plus serialised state ship to the client, hydration restores the route along with everything else.

## What routing-as-state actually buys you

Pulling routing inside the registry isn't a stylistic flourish — it's the difference between a routing library that lives *next to* your app and a route that's *just another piece of state your app already knows how to handle*. Three payoffs make the case.

**Tests.** A blocked navigation, a `:can-leave` guard, a stale `:on-match` reply landing after the user moved on — each is a sequence of named events against a frame. Dispatch the events, assert the slice, assert whether `:rf.nav/push-url` fired. No DOM, no router mock, no hook-test library. Routing tests run on the JVM in milliseconds, in the same suite as everything else.

**Time-travel.** Replay a session and the URL replays with it, because the URL is a function of the slice. There's no separate history to reconstruct; the address bar is downstream of `app-db`, so rewinding `app-db` rewinds the URL.

**SSR.** This is the load-bearing case. The same handler, the same `:on-match`, the same slice — on a per-request frame, with no client-side `pushState` and *no SSR-specific routing code*. The seam vanishes because there was never a seam: exactly one place where URLs become state, exactly one place where state becomes URLs. A hook-based router needs a parallel code path for the server. re-frame2 needs none. ([Chapter 20](20-server-side.md).)

Nav-tokens, `:can-leave`, multi-frame routing, query defaults, scroll restoration — none of these are features the router *has*. They're consequences of routes being data and navigation being events. Your routing code is the route table, the `:on-match` events, and a `case` in the root view. Everything else is your app's policy, written the same way you write the rest of the app. That's the bet the thesis was making: the URL is a sub.
