# 17a — Routing: reference and advanced topics

The previous chapter walked through the basics of routes-as-state: registering routes, navigating, reading the route in views, the `:on-match` loader, the not-found route, and a one-paragraph mention of the navigation token. That was the tutorial track.

This chapter is the per-topic reference. The sections are independent of one another — reach for them when the topic comes up, not as the next link in a linear sequence. `:on-error` is the route's response to a load failure. `:can-leave` blocks navigation when there's unsaved work. The nav-token section expands the basics-half callout into the full mechanism — cofx shape, the `:rf.route/with-nav-token` wrapper, a step-by-step worked example. Query strings, multi-frame routing, and the pure `match-url` / `route-url` helpers round out the surface. A RealWorld worked example shows the pieces wired together, and a closing section says what the AI-track gets from routing-as-state.

## Per-route error handling: `:on-error`

If any event in `:on-match` errors, the runtime:

1. Sets `:rf.route/transition` to `:error`.
2. Populates `:rf.route/error` with the structured error map.
3. If the route declares an `:on-error` event, dispatches it. The error map is available via `(:error (:rf/route db))`.

```clojure
(rf/reg-route :route/cart
  {:path     "/cart"
   :on-match [[:cart/load-items]]
   :on-error [:route/cart-load-failed]})

(rf/reg-event-fx :route/cart-load-failed
  (fn [{:keys [db]} _]
    (let [error (get-in db [:rf/route :error])]
      {:db (assoc-in db [:cart :load-error] (:rf.error/message error))})))
```

`:on-error` is **route-scoped** error handling, layered over [Spec 009](../../spec/009-Instrumentation.md)'s structured error contract — it doesn't replace it. The structured error trace event still fires; `:on-error` is the route's response to it.

## Navigation tokens — stale-result suppression

When a route is loading and the user navigates away before the load completes, the older load's result can land **after** the user has moved on, clobbering newer state. Re-frame2's answer is the **navigation-token (nav-token) epoch**: a per-navigation token allocated when a route becomes active, carried by every async result, and validated on receipt.

```clojure
;; cofx of :on-match handlers carries the current :nav-token
{:db        ...
 :event     [:cart/load-items]
 :nav-token "nav-42"}                       ;; the token at scheduling time
```

Async completions either carry the token in their follow-up event payload, or use the framework-supplied `:rf.route/with-nav-token` fx wrapper which threads the token into the dispatched continuation:

```clojure
{:fx [[:rf.route/with-nav-token
       {:do        [:dispatch [:cart/items-loaded items]]
        :nav-token (:nav-token cofx)}]]}
```

When the receiving handler runs, the framework-provided `:nav-token` cofx checks the carried token against the *current* `:rf/route` slice's `:nav-token`:

- **Match** — the token is current; the result is committed normally.
- **Mismatch** — the token has been superseded; the runtime emits `:route.nav-token/stale-suppressed` and the handler does NOT run. No `:db` write, no `:fx`, no transition.

```clojure
;; Step 1: User navigates to article id="A". nav-token = "nav-1".
{:rf/route {:id :route/article :params {:id "A"} :transition :loading :nav-token "nav-1"}}

;; Step 2: While the load is in flight, user navigates to article id="B".
;; A fresh nav-token is allocated.
{:rf/route {:id :route/article :params {:id "B"} :transition :loading :nav-token "nav-2"}}

;; Step 3: The "A" load completes; its dispatched [:article/loaded "A" payload] carries
;; nav-token "nav-1". Current is "nav-2". Mismatch → suppressed; trace fires; no commit.

;; Step 4: The "B" load completes; carries "nav-2". Match → commit.
{:rf/route {:id :route/article :params {:id "B"} :transition :idle :nav-token "nav-2"}}
```

Suppression alone fixes the user-visible bug. Hosts that support abortable fetches (`AbortController` in JS, etc.) MAY *additionally* abort in-flight work for superseded tokens to save bandwidth — but the conformance contract only requires suppression, not cancellation. Same shape as the `:after` timer story for state-machines (see [Pattern-StaleDetection](../../spec/Pattern-StaleDetection.md)).

Two trace events surround the nav-token lifecycle:

- **`:route.nav-token/allocated`** — emitted when a navigation cascade allocates a fresh token.
- **`:route.nav-token/stale-suppressed`** — emitted when an async result arrives carrying a now-superseded token.

## Navigation blocking — the `:can-leave` protocol

Real product needs — unsaved forms, interrupted checkouts, destructive multi-step workflows — require navigation to be **blockable**. Re-frame2 makes this a first-class named-event/state protocol instead of a hook-based router thing. Pending-nav state lives in `app-db`; UI renders confirm dialogs from ordinary subs; user choices are dispatched as standard events. All testable.

### Declare the guard on the route

```clojure
(rf/reg-route :editor/article
  {:doc       "Editing an article."
   :path      "/editor/articles/:id"
   :params    [:map [:id :string]]
   :can-leave [:editor/can-leave?]})

(rf/reg-sub :editor/can-leave?
  :<- [:editor/dirty?]
  (fn [dirty? _] (not dirty?)))             ;; true means "OK to leave"
```

The sub returns `true` when the route is OK to leave; `false` to block. Convention: the sub's name describes the **positive** case (`:can-leave?`), so `false` means "can NOT leave."

### What happens on a blocked navigation

1. `:rf/url-requested` fires (link click, programmatic call, popstate).
2. The runtime evaluates the **current** route's `:can-leave` sub.
3. **Guard returns `true`** (or no guard) → proceed normally. The new URL becomes active; nav-token allocates; `:on-match` runs.
4. **Guard returns `false`** → BLOCK:
   - Generate a `pending-nav-id`.
   - Write `:rf/pending-navigation` with `{:id <id> :requested-by-event <ev> :requested-url <url> :rejecting-route <id> :rejecting-guard <sub-id>}`.
   - **The URL does not change.** No `pushState`, no `:rf/route` update, no `:on-match`.
   - Dispatch `[:rf.route/navigation-blocked pending-nav]` and emit the trace.

The pending-nav slot's shape (per [Spec-Schemas](../../spec/Spec-Schemas.md#rfpending-navigation)):

```clojure
{:rf/pending-navigation
 {:id                  "pn-7"
  :requested-by-event  [:rf/url-requested {:url "/editor/articles/42"}]
  :requested-url       "/editor/articles/42"
  :reason              "Form has unsaved changes"
  :rejecting-route     :editor/article
  :rejecting-guard     :editor/can-leave?}}
```

### The dialog

UI renders the confirm dialog by subscribing to `:rf/pending-navigation`:

```clojure
(rf/reg-view leave-confirmation []
  (when-let [pn @(subscribe [:rf/pending-navigation])]
    [:dialog.confirm-leave
     [:p (:reason pn)]
     [:p "Leaving will discard your changes."]
     [:button {:on-click #(dispatch [:rf.route/cancel   (:id pn)])} "Stay"]
     [:button {:on-click #(dispatch [:rf.route/continue (:id pn)])} "Leave"]]))
```

User choice:

- **Continue** → `[:rf.route/continue pending-nav-id]` clears the slot and re-dispatches the original navigation **bypassing** the leave-guard for this one shot.
- **Cancel** → `[:rf.route/cancel pending-nav-id]` clears the slot. Nothing else changes.

### Why this shape (and not a hook-based router)

The hook version (`useBlocker`) is convenient but tied to component lifecycle. Re-frame2's strengths are explicit state and dispatched events; the named-event/slot shape preserves them. Slightly more verbose at the call site; far more testable.

A test fires `[:rf/url-requested {:url "/cart"}]` against a frame whose `:editor/can-leave?` sub returns `false`, asserts `:rf/pending-navigation` is set, asserts `:rf.nav/push-url` did NOT fire, dispatches `[:rf.route/continue pending-nav-id]`, asserts the navigation completes. No DOM, no hook-mock, no event simulation.

## Query strings and fragments

Path syntax is the *primary* binding. Query strings are bound separately via the route's `:query` metadata key:

```clojure
(rf/reg-route :route/search
  {:path            "/search"
   :query           [:map [:q :string] [:page {:optional true} :int]]
   :query-defaults  {:page 1}
   :query-retain    #{:theme :locale}})

;; URL: /search?q=clojure&page=2
;; match-url yields {:route-id :route/search :params {} :query {:q "clojure" :page 2}}

;; URL: /search?q=clojure  (page absent; default applied)
;; match-url yields {:route-id :route/search :params {} :query {:q "clojure" :page 1}}
```

`:query-defaults` populates absent query keys at match time. `:query-retain` carries keys through subsequent navigations even when the caller didn't supply them — useful for global state encoded in the URL (`:theme`, `:locale`, `:debug`). The merge happens inside `:rf.route/navigate`'s handler (which has access to `app-db` and the current query slice) before the URL is built. The result: a `[:rf.route/navigate :route/cart]` from a search page preserves `?theme=dark`.

Coercion is data-shaped (the `:query` schema is the coercion specification — `:int` coerces `"2"` → `2`). No per-key middleware functions — data over functions.

The URL `#fragment` is a first-class part of the routing contract — anchor navigation, scroll-to-section, settings-tab selection. Read it via `:rf.route/fragment`. Fragment-only changes update the slice and emit `:rf.route/url-changed` trace but do NOT re-fire `:on-match` (the data didn't change; only the in-page target did).

## Multi-frame routing

Each frame may have its own `:rf/route` slice — it's a regular `app-db` path, not a special concept. **Only one frame is URL-bound.**

| Frame | URL-bound? | Behaviour |
|---|---|---|
| `:rf/default` | yes (default) | `:rf.route/navigate` events fire `:rf.nav/push-url`; popstate dispatches into this frame. The browser URL reflects this frame's route. |
| Non-default | no (default) | `:rf.route/navigate` updates the frame's `:rf/route` slice but does **not** fire `:rf.nav/push-url`. The browser URL is unchanged. |
| Non-default with `(rf/reg-frame :my-frame {:url-bound? true})` | yes (opt-in) | The runtime enforces "only one frame can own the URL at a time" — re-registering a second URL-bound frame is a `:rf.error/duplicate-url-binding` trace event. |

The story tools / devcards / SSR cases all benefit:

- **Stories / devcards.** Each story-variant frame has its own `:rf/route` independent of the page URL. A "show me the cart in the loaded state at `/cart/items/abc`" variant doesn't push to the browser bar.
- **Per-test fixtures.** Each test frame has its own route; tests don't accidentally hit `pushState`.
- **SSR per-request frames.** The request URL is fed in via `:rf.route/handle-url-change`; no client-side `pushState` (which doesn't exist server-side anyway).

## Pure helpers

Two pure functions are part of the public surface:

```clojure
(rf/match-url url)
;; → {:route-id :route/search :params {} :query {:q "clojure"} :fragment nil
;;    :validation-failed? false}
;; or nil if no route matches at all.

(rf/route-url :route/cart)
(rf/route-url :route/cart.item-detail {:id "abc"})
(rf/route-url :route/search {} {:q "clojure"})
(rf/route-url :route/docs {:page "routing"} {} "scroll-restoration")
;; → URL string, e.g. "/docs/routing#scroll-restoration"
```

Both are pure; both run on JVM and CLJS; both work against the same registered route table so adding/removing a route updates both directions automatically. `route-url` is a string-builder — it does NOT navigate, does NOT read `app-db`, does NOT push or dispatch.

To navigate, dispatch `:rf.route/navigate` (which uses `route-url` internally). To resolve a URL incoming from outside, call `match-url` (which `:rf.route/handle-url-change` uses internally).

## Worked example — the realworld scaffold

The [`examples/reagent/realworld/`](https://github.com/day8/re-frame2/tree/main/examples/reagent/realworld) example is a re-frame2 implementation of the [RealWorld example app spec](https://github.com/gothinkster/realworld). Its routing setup is the canonical end-to-end shape:

```clojure
(ns realworld.routes
  (:require [re-frame.core :as rf]))

(rf/reg-route :route/home
  {:path     "/"
   :on-match [[:articles/load-feed]]})

(rf/reg-route :route/login
  {:path "/login"})

(rf/reg-route :route/register
  {:path "/register"})

(rf/reg-route :route/article
  {:path     "/article/:slug"
   :params   [:map [:slug :string]]
   :on-match [[:article/load]
              [:article/load-comments]]})

(rf/reg-route :route/profile
  {:path     "/profile/:username"
   :params   [:map [:username :string]]
   :on-match [[:profile/load]]})

(rf/reg-route :route/editor
  {:path      "/editor"
   :tags      #{:requires-auth}
   :can-leave [:editor/can-leave?]})

(rf/reg-route :route/editor-edit
  {:path      "/editor/:slug"
   :params    [:map [:slug :string]]
   :tags      #{:requires-auth}
   :on-match  [[:editor/load-for-edit]]
   :can-leave [:editor/can-leave?]})

(rf/reg-route :route/settings
  {:path "/settings"
   :tags #{:requires-auth}})

(rf/reg-route :rf.route/not-found
  {:path "/404"})
```

The root view dispatches on `:rf.route/id`:

```clojure
(rf/reg-view app-root []
  (let [route-id @(subscribe [:rf.route/id])]
    [:div.app
     [navbar]
     [leave-confirmation]              ;; renders pending-nav dialog
     (case route-id
       :route/home          [home-page]
       :route/login         [login-page]
       :route/register      [register-page]
       :route/article       [article-page]
       :route/profile       [profile-page]
       :route/editor        [editor-page]
       :route/editor-edit   [editor-page]
       :route/settings      [settings-page]
       :rf.route/not-found  [not-found-page])
     [footer]]))
```

An auth guard (regular interceptor on `:rf.route/navigate`) consults `:tags` and redirects to `/login` when the user isn't authed:

```clojure
(def auth-guard
  (rf/->interceptor
    :id     :rf.route/auth-guard
    :before (fn [ctx]
              (let [event       (get-in ctx [:coeffects :event])
                    target      (second event)
                    route-meta  (rf/handler-meta :route target)
                    needs-auth? (boolean (some #{:requires-auth} (:tags route-meta)))
                    logged-in?  (some? (get-in ctx [:coeffects :db :auth :user]))]
                (if (and needs-auth? (not logged-in?))
                  (assoc-in ctx [:coeffects :event]
                            [:rf.route/navigate :route/login {} {} nil
                             {:return-to target}])
                  ctx)))))
```

Guards are interceptors, not a special routing mechanism. They compose; multiple guards can layer. The interceptor primitive (`->interceptor`), the sandwich shape, and how `:before` modifies the context are covered in [chapter 07 — Interceptors](07-interceptors.md).

The editor's `:can-leave` guard plus a confirmation dialog are wired exactly as described in [§Navigation blocking](#navigation-blocking--the-can-leave-protocol) above — the dirty-flag drives the sub; the dialog renders from `:rf/pending-navigation`; user clicks dispatch `:rf.route/continue` or `:rf.route/cancel`.

The same routing setup runs **server-side under SSR** without modification. The request URL is fed to `:rf.route/handle-url-change`; `:on-match` events fire and populate the route's data; the view renders against the populated state; HTML + serialised state ship to the client; hydration restores the route along with everything else.

## Tooling and AI-amenability

- `(rf/handlers :route)` enumerates every registered route. AI scaffolds consult this before generating new routes to avoid collisions.
- `(rf/handler-meta :route :route/cart)` returns the route's metadata: path, params shape, query shape, `:on-match`, `:on-error`, `:scroll`, `:parent`, tags, source coords. The `:on-match` slot is enumerable — tools render route-loading dependency graphs without parsing handler bodies.
- `:rf.route/navigate`, `:rf.route/handle-url-change`, `:rf/url-changed`, `:rf/url-requested` are stable, named events; trace events surface every navigation and every URL request.
- A registered `:rf.route/not-found` is required by contract; tools surface the `:rf.warning/no-not-found-route` trace event for apps missing the registration.

## What routing-as-state buys you

Pulling routing inside the registry isn't a stylistic choice. It's the difference between a routing library that lives next to your app and a route that's just another piece of state your app already knows how to handle.

Tests are the most obvious payoff. A blocked navigation, a `:can-leave` guard, a stale `:on-match` reply landing after the user has moved on — each one is a sequence of named events against a frame. Dispatch the events, assert the slice, assert what `:rf.nav/push-url` did or didn't fire. No DOM, no router mock, no event simulation, no hook test library. Routing tests run on the JVM in milliseconds alongside the rest of the unit suite.

Time-travel works on routes the same way it works on the counter. Replay a session and the URL replays with it, because the URL is a function of the slice. Pair-tools enumerate routes (`rf/handlers :route`), render `:on-match` dependency graphs, and surface every navigation as a named trace event. The deterministic ranking cascade means an AI scaffold (or a teammate) can answer "which route matches `/articles/foo`?" by reading data — no need to step through library code.

SSR is the load-bearing case. The same `:rf.route/handle-url-change`, the same `:on-match` events, the same `:rf/route` slice — on a per-request frame, with no client-side `pushState` and no SSR-specific routing code. The seam vanishes because there was never a seam: only one place where URLs become state, only one place where state becomes URLs. The hook-based router needs a different code path for the server. re-frame2 needs none.

Nav-tokens, `:can-leave`, multi-frame routing, query-string defaults, scroll restoration — all of them fall out of the same primitive. They're not features the router has; they're consequences of routes being data and navigation being events. Your routing code is the route table, the `:on-match` events, and a `case` in the root view. Anything else is your app's policy, written the same way you write the rest of the app.

That's the bet the previous chapter was defending: the URL is a sub.

## Next

- [18 — From re-frame v1](18-from-re-frame-v1.md) — appendix-shaped migration notes if you're carrying a re-frame v1 app forward.
- [20 — Where to go next](20-where-next.md) — the chapter wrap-up, with pointers to the worked examples, pattern docs, the API ref, and the spec.
- [Spec 012 — Routing](../../spec/012-Routing.md) — the full normative surface, including the path-pattern grammar's productions, the route ranking cascade, scroll restoration, and the SSR integration story in detail.
- [chapter 11](11-server-side.md) — how routing folds into SSR.
- [Pattern-StaleDetection](../../spec/Pattern-StaleDetection.md) — why nav-tokens are the same shape as `:after`-timer epochs, and the cross-cutting pattern.
