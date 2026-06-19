# Routing — re-frame2 binding

> Authoring `reg-route`, programmatic navigation, link wiring, and the `:can-leave` pending-nav protocol. Assumes you already know what client-side routing is — this leaf only covers re-frame2's specific declarations.

## When to load

- Author or edit route registrations (`reg-route`).
- Wire programmatic navigation (`:rf.route/navigate`) or anchor clicks (`:rf/url-requested`).
- Add a leave-guard (`:can-leave`), `:on-match` data loading, or scroll behaviour.
- Read the active route in a view via `:rf.route/id` / `:rf.route/params`.

Do **not** load this leaf to learn what routing is — that is training knowledge. Load it for: the route-metadata key set, the slice shape, the event vocabulary, and the can-leave pending-nav protocol that distinguishes re-frame2 from hook-based routers like React Router's `useBlocker`.

## Canonical signatures

The routing artefact ships separately in `day8/re-frame2-routing`. `re-frame.core` does **not** require it; the consuming app must `:require [re-frame.routing :as routing]` at boot, or `reg-route` throws `:rf.error/routing-artefact-missing`. The `reg-route` **registration macro** stays on the `re-frame.core` façade (`rf/`); the **URL-codec query helpers** `route-url` / `match-url` (and `current-url` / `clear-route`) live on the owning `re-frame.routing` namespace — they are no longer re-exported from `re-frame.core` (front-porch shrink).

```clojure
(rf/reg-route id metadata)                                  ;; metadata keys below
(routing/route-url route-id path-params)                    ;; pure; build URL from id + params
(routing/route-url route-id path-params query-params)
(routing/route-url route-id path-params query-params fragment)  ;; 4-arity adds #fragment
(routing/match-url url)                 ;; pure; => {:route-id :params :query :fragment} or nil
```

The 4-arity `route-url` appends the `#fragment` part. A `nil` (or empty-string) fragment is **omitted** from the URL — `route-url` percent-encodes a present fragment, `match-url` decodes it back and normalises absence to `nil`, so the fragment round-trips lawfully (EP-0012 route-prism law). Build fragment links through this arity; do **not** hand-concatenate `(str url "#" frag)`.

Reserved **routing-owned** `metadata` keys on `reg-route` (twelve total, all optional except `:path`): `:doc :path :params :query :query-defaults :query-retain :tags :parent :on-match :on-error :scroll :can-leave`. Bare keys outside this set throw `:rf.error/invalid-route-metadata` at registration — **except** the late-bound cross-feature keys other framework artefacts publish:

- `:resources` — owned by the Resources artefact (route-owned server-state; see [`../../patterns/resources.md`](../../patterns/resources.md)). Accepted only when the Resources artefact is loaded.
- `:head` — owned by SSR (names which head/meta block the route uses).

These pass the guard because the framework owns them; they are not app typos. Application keys may sit alongside in non-`:rf/*` namespaces.

Path-pattern grammar:

```
/literal      literal segment
/:name        named param (one segment)
/*name        splat — greedy across /
/{ ... }?     optional group; inner /:name is elided in route-url output when absent
```

## The `:rf/route` slice

The runtime maintains one slice in the runtime-db partition at `[:rf.runtime/routing :current]` (a reserved `:rf.runtime/*` key — framework state, not app-db); the consumer-facing sub-id `:rf/route` reads it back:

```clojure
{:id         :route/article-detail
 :params     {:id "intro"}
 :query      {:tab :comments}
 :fragment   "section-2"        ;; URL #fragment, or nil
 :transition :idle              ;; :idle | :loading | :error
 :error      nil                ;; structured error map when :transition = :error
 :nav-token  "nav-42"}          ;; per-navigation epoch token
```

Framework-shipped subs (registered by `re-frame.routing`): `:rf/route` (whole slice), `:rf.route/id`, `:rf.route/params`, `:rf.route/query`, `:rf.route/fragment` (the URL `#fragment` or nil), `:rf.route/transition`, `:rf.route/error`. Read the active fragment via `:rf.route/fragment` — do **not** parse `js/location.hash` or peek the runtime-db slice directly.

## Canonical mini-example

Distilled from `examples/reagent/routing/core.cljs`.

```clojure
(ns app.core
  (:require [re-frame.core :as rf]
            [re-frame.routing :as routing])  ;; load-time hook + reg-sub registrations; route-url / match-url
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
;; `rf/route-link` is the framework-shipped link view — it builds the href via
;; the route prism and dispatches `:rf/url-requested` on a plain left-click.
;; Shape: [rf/route-link {:to :route-id :params {} :query {} :fragment "..."} & children]

;; Root view dispatches on the route id.
(reg-view root-view []
  (case @(rf/subscribe [:rf.route/id])
    :route/home           [home-page]
    :route/articles       [articles-page]
    :route/article-detail [article-detail-page]
    :rf.route/not-found   [not-found-page]
    [not-found-page]))

;; Boot: register a URL-owning frame, then install the framework popstate
;; listener. `:url-bound? true` declares this frame owns the URL.
(def app-frame :rf/default)

(defn run []
  (rf/init! adapter)
  (rf/reg-frame app-frame {:doc "Routing demo frame." :url-bound? true})
  (rf/with-frame app-frame
    (rf/dispatch-sync [:app/initialise]))
  ;; Framework popstate listener + initial URL→slice sync, targeted at the URL
  ;; owner. It resolves the URL-owner frame AT POP TIME and dispatches the
  ;; URL-change to THAT frame, so Back/Forward restores the owner's :rf/route
  ;; slice. Idempotent (hot-reload safe). A hand-rolled frameless
  ;; (rf/dispatch [:rf.route/handle-url-change ...]) would raise
  ;; :rf.error/no-frame-context — the no-ambient-frame contract (EP-0002).
  (rf/install-history-listener!)
  (render [rf/frame-provider-existing {:frame app-frame} [root-view]]))
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
3. **`false`** → block: write the pending-nav slot at `[:rf.runtime/routing :pending-navigation]` with `{:id "pn-N" :request {...}}`; emit `:rf.route/navigation-blocked` trace; do NOT push the URL or update the slice.
4. UI subscribes to `:rf/pending-navigation` (the framework-shipped sub over the slot), renders a confirm dialog.
5. User dispatches `[:rf.route/continue pn-id]` (re-issues the original nav, bypassing the guard for this one shot) or `[:rf.route/cancel pn-id]` (clears the slot).

## Common gotchas — re-frame2-specific

- **Routing is a separate artefact.** `re-frame.core` does not transitively require `re-frame.routing`. The consuming app `:require`s it at boot; otherwise `reg-route` throws `:rf.error/routing-artefact-missing`. The reserved `:rf.route/*` and `:rf.nav/*` keyword strings therefore drop out of bundles that don't use routing.
- **Navigation is an event, not a fn call.** Use `(rf/dispatch [:rf.route/navigate :route/articles])` (programmatic) or `(rf/dispatch [:rf/url-requested {:url ...}])` (anchor clicks). Do NOT call `pushState` directly.
- **`:on-match` runs every time the route becomes active.** Including first match. It is a vector of event vectors (not fns). On entering a route with `:on-match`, the slice's `:transition` field (at `[:rf.runtime/routing :current :transition]`) flips to `:loading`; runtime resets it to `:idle` after the events drain (or `:error` on `:on-error`).
- **`:on-match` order is locked.** State-update first (slice + nav-token), URL push second, `:on-match` dispatches and `:rf.nav/scroll` third. If the URL update fails, the slice is still consistent.
- **`:params` and `:query` are separate maps.** Path params come from segments; query params come from `?k=v`. Validated by separate Malli schemas on `reg-route` (`:params` and `:query`). Build a merged map in a derived sub if you want one.
- **Query coercion is per-key, Malli-driven.** When `:query` is a Malli `[:map [:tab :keyword] [:page :int]]`, string values get coerced (`:int`, `:keyword`, `:boolean`). `:query-defaults` populates absent keys. Canonical identity applies *after* coercion (EP-0012).
- **Routes are prisms — canonical query order in BOTH directions.** A route is a lawful round trip: `match-url(route-url(…))` returns canonical route data. `route-url` emits query keys in **deterministic canonical order** (a host map's iteration order never leaks into the URL string — the same query map written two ways yields byte-identical URLs), and `match-url` returns the `:query` map in that **same canonical key order** for an *arbitrary inbound URL* — so a deep link `?b=2&a=1` and `?a=1&b=2` resolve to one identical `:query` value (a stable `:rf.route/query` sub identity / no-op-detection key / SSR-hydration parity, independent of the link author's key spelling). A `nil`-valued query param is **elided by policy** before printing (absent after `match-url`), while `false` / `0` / `""` are present values that round-trip; and an **out-of-domain param fails closed** (a required path param that is `nil` / absent / empty, or a coerced value that cannot be represented as canonical EDN, fails the match/print rather than inventing an identity via `str` or object identity). See [`../cross-cutting/path-and-identity.md`](../cross-cutting/path-and-identity.md).
- **Fragment-only changes do NOT re-fire `:on-match`.** A change limited to `#fragment` updates the slice's `:fragment` field and emits the `:rf.route/fragment-changed` trace carrying `:prev-fragment` and `:next-fragment` in `:tags`; `:on-match` is skipped. `:can-leave` DOES run for fragment changes — apps that want to bypass the guard for fragments check the prev/next fragment in the sub.
- **`:rf.route/not-found` is canonical.** Register it explicitly; unmatched URLs resolve to this id. The runtime falls back to a placeholder and emits `:rf.warning/no-not-found-route` if you don't register one.
- **Nav-tokens suppress stale results.** Each navigation allocates a fresh `:nav-token` (`"nav-N"`). Async handlers (typically HTTP `:on-success`) capture the token at request time; the runtime suppresses the continuation when the carried token does not match the current slice's. Tests can simulate via `[:rf.test/simulate-http-resolution {:on-success-event [...] :carried-nav-token "nav-3"}]` — this fixture event is test-only, so `(:require [re-frame.routing.test-support])` to register it (it is NOT wired into the production `re-frame.routing` façade).
- **Scroll is declarative.** `:scroll` on the route metadata is one of `:top` (default for forward nav), `:restore` (default for popstate / initial), `:preserve`, or a host-extensible map. Per-call override on `:rf.route/navigate` opts wins. Setting `:scroll false` suppresses the `:rf.nav/scroll` fx entirely.
- **Multi-frame routing.** The route slice, nav-token counter, and saved-scroll map all live in the frame's **runtime-db** partition (under the reserved `[:rf.runtime/routing …]` keys) — each frame has its own routing state, read back via the framework `:rf/route` / `:rf.route/*` subs. No global router.

## Deeper material

- Full path-pattern grammar, ranking algorithm, query coercion, scroll fx contract → `SKILL-REDIRECT.md` → *EP — Routing (012)*.
- Worked three-page example (home / list / detail, popstate, headless tests) → `examples/reagent/routing/`.
- Slice schema (`:rf/route-slice`), pattern schema (`:rf/route-pattern`), rank schema (`:rf/route-rank`) → `SKILL-REDIRECT.md` → *Spec schemas*.
- Interceptor-based guards (`auth-guard`, redirects), tags-driven policies → *EP — Routing (012)* §Redirects and guards.
- SSR routing (server frame handles `:rf.route/handle-url-change` on the request URL) → `SKILL-REDIRECT.md` → *EP — SSR (011)*.

---

*Derived from `implementation/routing/` (artefact source) and `examples/reagent/routing/` @ main `89bd9c3`. Re-verify after route-metadata or `:can-leave` protocol changes.*
