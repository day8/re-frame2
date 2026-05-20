# Routing — re-frame2 binding

> Authoring `reg-route`, programmatic navigation, link wiring, and the `:can-leave` pending-nav protocol. Assumes you already know what client-side routing is — this leaf only covers re-frame2's specific declarations.

## When to load this leaf

- Author or edit route registrations (`reg-route`).
- Wire programmatic navigation (`:rf.route/navigate`) or anchor clicks (`:rf/url-requested`).
- Add a leave-guard (`:can-leave`), `:on-match` data loading, or scroll behaviour.
- Read the active route in a view via `:rf.route/id` / `:rf.route/params`.

Do **not** load this leaf to learn what routing is — that is training knowledge. Load it for: the route-metadata key set, the slice shape, the event vocabulary, and the can-leave pending-nav protocol that distinguishes re-frame2 from hook-based routers like React Router's `useBlocker`.

## Canonical signatures

The routing artefact ships separately in `day8/re-frame2-routing`. `re-frame.core` does **not** require it; the consuming app must `:require [re-frame.routing]` at boot, or `reg-route` throws `:rf.error/routing-artefact-missing`. Once required, the surface lives on `re-frame.core` (via the late-bind table).

```clojure
(rf/reg-route id metadata)              ;; metadata keys below
(rf/route-url route-id path-params)     ;; pure; build URL from id + params
(rf/route-url route-id path-params query-params)
(rf/match-url url)                      ;; pure; => {:route-id :params :query :fragment} or nil
```

Reserved `metadata` keys on `reg-route` (all optional except `:path`): `:doc :path :params :query :query-defaults :query-retain :tags :parent :on-match :on-error :scroll :can-leave`. Application keys may sit alongside in non-`:rf/*` namespaces.

Path-pattern grammar:

```
/literal      literal segment
/:name        named param (one segment)
/*name        splat — greedy across /
/{ ... }?     optional group; inner /:name is elided in route-url output when absent
```

## The `:rf/route` slice

The runtime maintains one slice in app-db under `:rf/route`:

```clojure
{:id         :route/article-detail
 :params     {:id "intro"}
 :query      {:tab :comments}
 :fragment   "section-2"        ;; URL #fragment, or nil
 :transition :idle              ;; :idle | :loading | :error
 :error      nil                ;; structured error map when :transition = :error
 :nav-token  "nav-42"}          ;; per-navigation epoch token
```

Framework-shipped subs (registered by `re-frame.routing`): `:rf/route` (whole slice), `:rf.route/id`, `:rf.route/params`, `:rf.route/query`, `:rf.route/transition`, `:rf.route/error`.

## Canonical mini-example

Distilled from `examples/reagent/routing/core.cljs`.

```clojure
(ns app.core
  (:require [re-frame.core :as rf]
            [re-frame.routing])              ;; load-time hook + reg-sub registrations
  (:require-macros [re-frame.core :refer [reg-view]]))

(rf/reg-route :route/home
  {:doc "Landing." :path "/"})

(rf/reg-route :route/articles
  {:doc "Articles list." :path "/articles"})

(rf/reg-route :route/article-detail
  {:doc "One article."
   :path "/articles/:id"
   :params [:map [:id :string]]
   :on-match [[:article/load]]})            ;; runs after every match; sets :transition :loading

(rf/reg-route :rf.route/not-found              ;; canonical fallback id
  {:doc "404." :path "/_404"})

;; Anchor that routes through the framework (not a full page reload).
(reg-view route-link [{:keys [to params]} & children]
  (let [url (rf/route-url to (or params {}))]
    [:a {:href url
         :on-click (fn [e]
                     (when (and (zero? (.-button e))
                                (not (.-metaKey e))
                                (not (.-ctrlKey e))
                                (not (.-shiftKey e)))
                       (.preventDefault e)
                       (rf/dispatch [:rf/url-requested {:url url}])))}
     (into [:span] children)]))

;; Root view dispatches on the route id.
(reg-view root-view []
  (case @(rf/subscribe [:rf.route/id])
    :route/home           [home-page]
    :route/articles       [articles-page]
    :route/article-detail [article-detail-page]
    :rf.route/not-found   [not-found-page]
    [not-found-page]))

;; Wire popstate + initial load.
(defn install-router! []
  (.addEventListener js/window "popstate"
    (fn [_] (rf/dispatch [:rf.route/handle-url-change (current-url)])))
  (rf/dispatch-sync [:rf.route/handle-url-change (current-url)]))
```

## `:can-leave` — the pending-nav protocol

A route may declare a leave-guard sub. The sub returns `true` when leaving is OK, `false` to block. Convention: the *sub name* describes the positive case, so `false` means "can NOT leave".

```clojure
(rf/reg-route :editor/article
  {:path      "/editor/articles/:id"
   :can-leave [:editor/can-leave?]})         ;; sub-id; (subscribe [sub-id]) => boolean

(rf/reg-sub :editor/can-leave?
  :<- [:editor/dirty?]
  (fn [dirty? _] (not dirty?)))              ;; true means "OK to leave"
```

Flow on `:rf/url-requested`:

1. Runtime evaluates the **current** route's `:can-leave` sub.
2. **`true`** → proceed; new URL becomes active; `:on-match` runs; nav-token allocates.
3. **`false`** → block: write `:rf/pending-navigation` `{:id "pn-N" :request {...}}`; emit `:rf.route/navigation-blocked` trace; do NOT push the URL or update the slice.
4. UI subscribes to `:rf/pending-navigation`, renders a confirm dialog.
5. User dispatches `[:rf.route/continue pn-id]` (re-issues the original nav, bypassing the guard for this one shot) or `[:rf.route/cancel pn-id]` (clears the slot).

## Common gotchas — re-frame2-specific

- **Routing is a separate artefact.** `re-frame.core` does not transitively require `re-frame.routing`. The consuming app `:require`s it at boot; otherwise `reg-route` throws `:rf.error/routing-artefact-missing`. The reserved `:rf.route/*` and `:rf.nav/*` keyword strings therefore drop out of bundles that don't use routing.
- **Navigation is an event, not a fn call.** Use `(rf/dispatch [:rf.route/navigate :route/articles])` (programmatic) or `(rf/dispatch [:rf/url-requested {:url ...}])` (anchor clicks). Do NOT call `pushState` directly.
- **`:on-match` runs every time the route becomes active.** Including first match. It is a vector of event vectors (not fns). On entering a route with `:on-match`, `:rf/route :transition` flips to `:loading`; runtime resets it to `:idle` after the events drain (or `:error` on `:on-error`).
- **`:on-match` order is locked.** State-update first (slice + nav-token), URL push second, `:on-match` dispatches and `:rf.nav/scroll` third. If the URL update fails, the slice is still consistent.
- **`:params` and `:query` are separate maps.** Path params come from segments; query params come from `?k=v`. Validated by separate Malli schemas on `reg-route` (`:params` and `:query`). Build a merged map in a derived sub if you want one.
- **Query coercion is per-key, Malli-driven.** When `:query` is a Malli `[:map [:tab :keyword] [:page :int]]`, string values get coerced (`:int`, `:keyword`, `:boolean`). `:query-defaults` populates absent keys. URL key order is preserved for byte-identical round-trip.
- **Fragment-only changes do NOT re-fire `:on-match`.** A change limited to `#fragment` updates the slice's `:fragment` field and emits the `:rf.route/fragment-changed` trace (rf2-cj9fn; pre-rename `:rf.route/fragment-changed`) carrying `:prev-fragment` and `:next-fragment` in `:tags`; `:on-match` is skipped. `:can-leave` DOES run for fragment changes — apps that want to bypass the guard for fragments check the prev/next fragment in the sub.
- **`:rf.route/not-found` is canonical.** Register it explicitly; unmatched URLs resolve to this id. The runtime falls back to a placeholder and emits `:rf.warning/no-not-found-route` if you don't register one.
- **Nav-tokens suppress stale results.** Each navigation allocates a fresh `:nav-token` (`"nav-N"`). Async handlers (typically HTTP `:on-success`) capture the token at request time; the runtime suppresses the continuation when the carried token does not match the current slice's. Tests can simulate via `[:rf.test/simulate-http-resolution {:on-success-event [...] :carried-nav-token "nav-3"}]`.
- **Scroll is declarative.** `:scroll` on the route metadata is one of `:top` (default for forward nav), `:restore` (default for popstate / initial), `:preserve`, or a host-extensible map. Per-call override on `:rf.route/navigate` opts wins. Setting `:scroll false` suppresses the `:rf.nav/scroll` fx entirely.
- **Multi-frame routing.** The slice, nav-token counter, and saved-scroll map all live in the frame's app-db — each frame has its own routing state. No global router.

## Deeper material

- Full path-pattern grammar, ranking algorithm, query coercion, scroll fx contract → `SKILL-REDIRECT.md` → *EP — Routing (012)*.
- Worked three-page example (home / list / detail, popstate, headless tests) → `examples/reagent/routing/`.
- Slice schema (`:rf/route-slice`), pattern schema (`:rf/route-pattern`), rank schema (`:rf/route-rank`) → `SKILL-REDIRECT.md` → *Spec schemas*.
- Interceptor-based guards (`auth-guard`, redirects), tags-driven policies → *EP — Routing (012)* §Redirects and guards.
- SSR routing (server frame handles `:rf.route/handle-url-change` on the request URL) → `SKILL-REDIRECT.md` → *EP — SSR (011)*.

---

*Derived from `implementation/routing/` (artefact source) and `examples/reagent/routing/` @ main `89bd9c3`. Re-verify after route-metadata or `:can-leave` protocol changes.*
