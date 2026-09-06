# Routing — re-frame2 binding

> Authoring `reg-route`, programmatic navigation, link wiring, and the asymmetric guard protocol (resumable leave, terminal entry). Assumes you already know what client-side routing is — this leaf only covers re-frame2's specific declarations.

## When to load

- Author or edit route registrations (`reg-route`).
- Wire programmatic navigation (`:rf.route/navigate`) or anchor clicks (`:rf.route/url-requested`).
- Add a leave-guard (`:can-leave`) or entry-guard (`:can-enter`), declare a page's `:resources`, or set scroll behaviour.
- Read the active route in a view via `:rf.route/id` / `:rf.route/params`.
- Carry query state across routes, warm a destination on intent, or style an active link.

Do **not** load this leaf to learn what routing is — that is training knowledge. Load it for: the route-metadata key set, the slice shape, the event vocabulary, and the guard protocol that distinguishes re-frame2 from hook-based routers like React Router's `useBlocker` — a leave rejection parks a resumable pending value, an entry rejection is terminal.

## Canonical signatures

The routing artefact ships separately in `day8/re-frame2-routing`. `re-frame.core` does **not** require it; the consuming app must `:require [re-frame.routing :as routing]` at boot, or `reg-route` throws `:rf.error/routing-artefact-missing`. The `reg-route` **registration macro** stays on the `re-frame.core` façade (`rf/`); the **URL-codec query helpers** `route-url` / `match-url` (and `current-url` / `clear-route`) live on the owning `re-frame.routing` namespace — they are not on the `re-frame.core` façade.

```clojure
(rf/reg-route id metadata path)                             ;; path is the 3rd positional arg; metadata keys below
(routing/route-url {:to route-id :params path-params :query query-params :fragment fragment})  ;; pure; one address map
(routing/match-url url)                 ;; pure; => {:route-id :params :query :fragment} or nil
```

`route-url` takes a single **address map** — `:to` is required (requests spell the route id `:to`; facts spell it `:route-id`), `:params` / `:query` / `:fragment` optional. It is strictly address-only: `:url`, `:query-merge`, policy keys, and unknown keys reject **loud** (`:rf.error/route-url-validation`). A present `:fragment` appends the `#fragment` part; a `nil` (or empty-string) fragment is **omitted** from the URL — `route-url` percent-encodes a present fragment, `match-url` decodes it back and normalises absence to `nil`, so the fragment round-trips lawfully (EP-0012 route-prism law). Build fragment links through `:fragment`; do **not** hand-concatenate `(str url "#" frag)`.

The path is the **third positional arg**, not a `:path` metadata key (`:path` stays in the accepted bare-key set for tolerance, but the positional arg is canonical). The **routing-owned** reserved bare `metadata` keys (all optional): `:doc :params :query :query-defaults :tags :parent :on-match :scroll :can-leave :can-enter`. The runtime also accepts route-owned **data-classification** keys `:sensitive` / `:large` (EP-0025 — projection-relative, lowered into the per-frame elision registry at activation; see [`../cross-cutting/privacy-and-elision.md`](../cross-cutting/privacy-and-elision.md)). Bare keys outside the accepted set throw `:rf.error/route-bad-metadata` at registration — **except** the late-bound cross-feature keys other artefacts publish:

- `:resources` — owned by the Resources artefact (route-owned server-state; see [`../../patterns/resources.md`](../../patterns/resources.md)). Accepted only when the Resources artefact is loaded.
- `:head` — owned by SSR (names which head/meta block the route uses).

These pass the guard because the framework owns them; they are not app typos. Application keys may sit alongside in non-`:rf/*` namespaces.

Path-pattern grammar:

```
/literal      literal segment
/:name        named param (one segment)
/*name        splat — greedy across /
{/:name}?     optional segment group — the slash lives INSIDE the braces
              ({/literal}? too), so the group is a self-contained optional
              segment and composes in any position: {/:base}?/about (leading),
              /articles/:id{/:slug}? (trailing), /docs{/:section}?{/:page}?
              (sequenced). Absent => the param is absent and route-url elides
              the whole segment. The slash-outside spelling is NOT part of the
              grammar: reg-route throws :rf.error/invalid-route-pattern at
              registration, in dev AND prod, before any state mutates.
```

## The `:rf/route` slice

The runtime maintains one slice in the runtime-db partition at `[:rf.runtime/routing :current]` (a reserved `:rf.runtime/*` key — framework state, not app-db); the consumer-facing sub-id `:rf/route` reads it back:

```clojure
{:route-id   :route/article-detail   ;; the slice key is :route-id (NOT :id)
 :params     {:id "intro"}
 :query      {:tab :comments}
 :fragment   "section-2"        ;; URL #fragment, or nil
 :transition :idle              ;; :idle | :loading | :error — a projection over the
                                ;;   blocking :resources, NOT the :on-match drain
 :error      nil                ;; structured error map when :transition = :error
 :nav-token  "nav-42"}          ;; per-navigation epoch token
```

Framework-shipped subs (registered by `re-frame.routing`): `:rf/route` (whole slice), `:rf.route/id`, `:rf.route/params`, `:rf.route/query`, `:rf.route/fragment` (the URL `#fragment` or nil), `:rf.route/transition`, `:rf.route/error`. Read the active fragment via `:rf.route/fragment` — do **not** parse `js/location.hash` or peek the runtime-db slice directly.

## Canonical mini-example

Distilled from `examples/capabilities/routing/routing/core.cljs`.

```clojure
(ns app.core
  (:require [re-frame.core :as rf]
            [re-frame.routing :as routing])  ;; load-time hook + reg-sub registrations; route-url / match-url
  (:require-macros [re-frame.core :refer [reg-view]]))

(rf/reg-route :route/home
  {:doc "Landing."} "/")

(rf/reg-route :route/articles
  {:doc "Articles list."} "/articles")

(rf/reg-route :route/article-detail
  {:doc "One article."
   :params [:map [:id :string]]
   :on-match [[:article/load]]}            ;; fire-and-forget activation work; does NOT drive :transition
  "/articles/:id")

(rf/reg-route :rf.route/not-found              ;; canonical fallback id
  {:doc "404."} "/_404")

;; Anchor that routes through the framework (not a full page reload).
;; `rf/route-link` is the framework-shipped link view — it builds the href via
;; the route prism and dispatches `:rf.route/url-requested` on a plain left-click.
;; Shape: [rf/route-link {:to :route-id :params {} :query {} :fragment "..."} & children]

;; Root view dispatches on the route id.
(reg-view root-view []
  (case @(rf/subscribe [:rf.route/id])
    :route/home           [home-page]
    :route/articles       [articles-page]
    :route/article-detail [article-detail-page]
    :rf.route/not-found   [not-found-page]
    [not-found-page]))

;; Boot: install the adapter, then render an ENSURE-shape provider that
;; creates and seeds the URL-owning frame. `init!` installs the adapter but
;; does NOT create a frame; `:url-bound? true` declares this frame owns the
;; URL — and its creation automatically wires the popstate listener (no
;; separate install call).
(def app-frame :rf/default)

(defn run []
  (rf/init! adapter)
  ;; ENSURE shape: first mount creates the frame, flips on :url-bound?, and
  ;; fires :initial-events once to seed app-db; hot reload reuses it (no
  ;; reseed). Frame creation ALSO installs the framework popstate listener +
  ;; initial URL→slice sync, targeted at the URL owner — it resolves the
  ;; URL-owner frame AT POP TIME and dispatches the URL-change to THAT frame,
  ;; so Back/Forward restores the owner's :rf/route slice. Idempotent
  ;; (re-registration-safe). A hand-rolled frameless
  ;; (rf/dispatch [:rf.route/handle-url-change ...]) would raise
  ;; :rf.error/no-frame-context — the no-ambient-frame contract (EP-0002).
  (render [rf/frame-root {:id app-frame
                          :doc "Routing demo frame."
                          :url-bound? true
                          :initial-events [[:app/initialise]]}
           [root-view]]))
```

## The guards — resumable leave, terminal entry

A route may declare a leave-guard sub. The sub returns `true` when leaving is OK, `false` to block. Convention: the *sub name* describes the positive case, so `false` means "can NOT leave".

```clojure
(rf/reg-route :editor/article
  {:can-leave [:editor/can-leave?]}          ;; sub-id; (subscribe [sub-id]) => boolean
  "/editor/articles/:id")

(rf/reg-sub :editor/can-leave?
  {:inputs [[:editor/dirty?]]}
  (fn [[dirty?] _] (not dirty?)))            ;; true means "OK to leave"
```

`:can-enter` is the entry guard, declared on the **target** route, and it runs in the one planning pipeline — so it covers every door (programmatic navigate, link click, URL bar, Back/Forward, initial load, SSR) with no per-door plumbing. A hand-rolled interceptor attached only to `:rf.route/navigate` fails OPEN through the other two; prefer `:can-enter` for a per-route gate and reach for an interceptor only when one policy genuinely spans many routes.

The pipeline decides leave-then-enter: the current route's `:can-leave` first, then the target's `:can-enter`. Both take the closed boolean contract — any non-boolean refuses AND emits `:rf.error/can-leave-non-boolean` / `:rf.error/can-enter-non-boolean`, so write `(boolean …)` rather than leaning on truthiness. Each guard sub also receives the resolved target appended to its query vector — `(fn [inputs [_ target] …])`, where `target` is `{:route-id :params :query :fragment :url}` — so one shared guard can branch on where the visitor was headed.

The two rejections are deliberately asymmetric, because the two questions are not the same shape. *Leaving* asks the user ("really discard your draft?"), so it parks one resumable pending value resolved by `:rf.route/continue` / `:rf.route/cancel`. *Entering* asks application state ("is this visitor signed in?"), answered the same way every time it is asked, so a rejection is **terminal**: it commits nothing (no slice, URL, scroll, resource, or `:on-match`), parks nothing, and dispatches `[:rf.route/entry-denied denial]` exactly once with `{:destination :target :cause :requested-url :guard}`. A framework no-op default ships, so a denial with no handler is a plain hard deny (and a `403` under SSR).

The auth recipe is a **fresh return**, not a resume: register `:rf.route/entry-denied`, stash the denial's `:destination`, replace-navigate to login, and after sign-in dispatch a fresh `[:rf.route/navigate <destination>]` whose guard re-evaluates naturally. Nothing can loop, because nothing was left pending. `:destination` is already a valid navigate request — do not re-derive it from `:requested-url` — but note it omits an empty `:params` / `:query` and a nil `:fragment`, so a bare stash reads `{:to :account}`. Register the handler bare: the denial payload is framework-constructed, so the framework's `:sensitive` classification of its URL carriers (`:requested-url` / `:destination` / `:target`, which can embed query values and path params) rides across your override and keeps redacting them at trace / off-box egress. Do **not** add a `{:sensitive …}` map to get that — declare `:sensitive` only for paths of your own.

Flow on `:rf.route/url-requested`:

1. Runtime evaluates the **current** route's `:can-leave` sub.
2. **`true`** → proceed to the target's `:can-enter`; on pass the new URL becomes active, `:on-match` runs, nav-token allocates.
3. **`false`** → block: write the pending-nav slot at `[:rf.runtime/routing :pending-navigation]` with `{:id "pn-N" :destination … :target … :cause … :policy … :requested-url … :rejecting-route … :rejecting-guard … :url-restored?}`; emit `:rf.route/navigation-blocked`; do NOT push the URL or update the slice. The slot is **leave-only** — there is no `:reason` / `:direction` discriminator, because there is only one thing it can be.
4. UI subscribes to `:rf/pending-navigation` (the framework-shipped sub over the slot), renders a confirm dialog.
5. User dispatches `[:rf.route/continue pn-id]` — which replays the stored `:destination` plus `:policy` through the normal pipeline with a one-shot `:bypass-leave? true`, so the target's `:can-enter` is still evaluated — or `[:rf.route/cancel pn-id]` (clears the slot). Both ignore a non-matching id.

`:bypass-leave? true` on a `:rf.route/navigate` request is the only bypass: it skips the CURRENT route's `:can-leave` for that one navigation. There is no entry bypass — entry is terminal, so there is nothing to wave through, and an "enter anyway" flag would be a hole through the auth gate.

## Common gotchas — re-frame2-specific

- **Routing is a separate artefact.** `re-frame.core` does not transitively require `re-frame.routing`. The consuming app `:require`s it at boot; otherwise `reg-route` throws `:rf.error/routing-artefact-missing`. The reserved `:rf.route/*` and `:rf.nav/*` keyword strings therefore drop out of bundles that don't use routing.
- **Navigation is an event, not a fn call.** Use `(rf/dispatch [:rf.route/navigate {:to :route/articles}])` (programmatic) or `(rf/dispatch [:rf.route/url-requested {:url ...}])` (anchor clicks). The navigate request is one map — address keys `:to` / `:url` / `:params` / `:query` / `:fragment`, policy keys `:replace?` / `:scroll` / `:bypass-leave?`, edit key `:query-merge`; omit `:to` and `:url` for an in-place patch of the current location. Do NOT call `pushState` directly.
- **`:on-match` is fire-and-forget activation work, NOT a loader.** It runs every time the route becomes active (including the first match, and again when `:params` / `:query` change), and it is a vector of event vectors, not fns. It never touches `:transition` / `:error`: the runtime dispatches its events, does not await the async work they start, and does not rewrite their failures into route state — a throwing handler surfaces on the ordinary event error channel, attributed to the event that threw. Data a page cannot render without belongs in `:resources`. There is **no** route `:on-error` key (retired, no alias — a route declaring it is rejected as an unknown bare key).
- **Route readiness is a projection over the blocking `:resources`.** `:rf.route/transition` is `:loading` while a blocking first load is pending, `:error` on a blocking first-load failure (`:rf.error/resource-route-blocking` on `:rf.route/error`) or a plan that could not be built (`:rf.error/resource-route-plan`), `:idle` otherwise — and always `:idle` when no resources artefact is loaded. A background refresh over data already on screen, a non-blocking read, and an intent prefetch never move it.
- **`:on-match` order is locked.** State-update first (slice + nav-token), URL push second, `:on-match` dispatches and `:rf.nav/scroll` third. If the URL update fails, the slice is still consistent.
- **`:parent` composes `:resources`, and nothing else.** Declaring `:parent` opts the child into its ancestors' `:resources`: activation plans the effective parent-to-leaf branch, identical requirements dedupe to one fetch, and a child restating a parent's requirement gets an advisory rather than a second fetch. `:parent` is itself the opt-in — there is no `:inherit-resources?` marker. `:on-match`, `:scroll`, `:head`, `:tags`, and the guards are NOT inherited. Composing resources does not compose rendering: layout is still `[:rf.route/chain]` folded by hand.
- **Carrying query state across routes is application policy, not a route key.** A destination address is taken literally — `{:to :route/cart}` navigates to exactly `/cart` and never gains query keys from whichever route was current. There is no `:query-retain` (retired, no alias; declaring it is rejected as an unknown bare key), no query middleware, no per-route carry policy. Spell the policy as an ordinary pure function over the address and apply it at the call site (or inside the app's own navigation event): `(defn with-shell-query [current-query address] (update address :query #(merge (select-keys current-query [:locale :tenant]) (or % {}))))`. Tolerate an absent `:query` — a destination replayed out of a pending-leave value omits an empty `:params` / `:query` and a nil `:fragment`, so never compare a stashed destination by `=` against a fully-spelled address map. To change the CURRENT route's query, use the in-place request (`:query-merge` / `:query`), which is the causal primitive for "same page, different query".
- **`:prefetch :intent` warms a destination without navigating.** `[rf/route-link {:to … :prefetch :intent} …]` dispatches `[:rf.route/prefetch {address}]` on hover / focus / touch; you can dispatch that event yourself from any handler. It accepts a NAMED address only (never `:url`), runs the same effective parent-to-leaf plan a navigation would in warm mode — every ensure ownerless, `:blocking?` inert, no slice / URL / scroll / guards / `:on-match` — and a later activation reuses the warmed work through ordinary dedupe. `:intent` is the only accepted value: no render mode, viewport mode, global default, or hover-delay knob, and a passive render dispatches nothing. Prefetch is a performance hint, not an authorization boundary.
- **`route-link` computes no active state.** It renders one `<a href>` and passes every unclaimed prop through, so "am I on this page?" is a comparison you write: `(= (:to props) @(subscribe [:rf.route/id]))` (or a membership test against `[:rf.route/chain]` when a parent tab should light up for its children), then set `:aria-current "page"` and a class on the link. There is no `<NavLink>` equivalent and no `isActive` callback.
- **`:params` and `:query` are separate maps.** Path params come from segments; query params come from `?k=v`. Validated by separate Malli schemas on `reg-route` (`:params` and `:query`). Build a merged map in a derived sub if you want one.
- **Query coercion is per-key, Malli-driven.** When `:query` is a Malli `[:map [:tab :keyword] [:page :int]]`, string values get coerced (`:int`, `:keyword`, `:boolean`). Canonical identity applies *after* coercion (EP-0012).
- **`:query-defaults` belongs to the target, not the URL.** It populates absent keys wherever a target is resolved, so every door agrees: a deep link, a `route-link` click, `[:rf.route/navigate {:to …}]` and `[:rf.route/prefetch …]` all resolve `{:page 1}` for a route declaring `:query-defaults {:page 1}` — which is what lets a hover and the following click land on ONE resource identity. Conversely `route-url` **omits** a key already at its default (`match-url` fills it back), so `/search?q=x` and `/search?q=x&page=1` are the same destination and the shorter one is the canonical href. Don't hand-merge defaults into an address before dispatching; the resolution does it.
- **Routes are prisms.** A route is a lawful round trip — `match-url(route-url(…))` returns canonical route data, with deterministic canonical query order on both legs and `nil`-valued query keys elided rather than emitted. The full emission rules are [`../cross-cutting/path-and-identity.md` §Routes are prisms](../cross-cutting/path-and-identity.md#routes-are-prisms).
- **Fragment-only changes do NOT re-fire `:on-match`.** A change limited to `#fragment` updates the slice's `:fragment` field and emits the `:rf.route/fragment-changed` trace carrying `:prev-fragment` and `:next-fragment` in `:tags`; `:on-match` is skipped. `:can-leave` DOES run for fragment changes — apps that want to bypass the guard for fragments check the prev/next fragment in the sub.
- **`:rf.route/not-found` is canonical.** Register it explicitly; unmatched URLs resolve to this id. The runtime falls back to a placeholder and emits `:rf.warning/no-not-found-route` if you don't register one.
- **Nav-tokens suppress stale results.** Each navigation allocates a fresh `:nav-token` (`"nav-N"`). Async handlers (typically HTTP `:on-success`) capture the token at request time; the runtime suppresses the continuation when the carried token does not match the current slice's. Tests can simulate via `[:rf.test/simulate-http-resolution {:on-success-event [...] :carried-nav-token "nav-3"}]` — this fixture event is test-only, so `(:require [re-frame.routing.test-support])` to register it (it is NOT wired into the production `re-frame.routing` façade).
- **Scroll is declarative.** `:scroll` on the route metadata is one of the closed three-keyword vocabulary `:top` (default for forward nav), `:restore` (default for popstate / initial), or `:preserve` — or `false` to suppress the `:rf.nav/scroll` fx entirely. A per-call `:scroll` in the `:rf.route/navigate` request wins. Any other value, including a map, is rejected: it fails the fx-args schema and the handler emits `:rf.error/unsupported-scroll-strategy` rather than scrolling nothing in silence.
- **Multi-frame routing.** The route slice, nav-token counter, and saved-scroll map all live in the frame's **runtime-db** partition (under the reserved `[:rf.runtime/routing …]` keys) — each frame has its own routing state, read back via the framework `:rf/route` / `:rf.route/*` subs. No global router.

## Deeper material

- Full path-pattern grammar, ranking algorithm, query coercion, scroll fx contract → `SKILL-REDIRECT.md` → *EP — Routing (012)*.
- Worked three-page example (home / list / detail, popstate, headless tests) → `examples/capabilities/routing/routing/`.
- Slice schema (`:rf/route-slice`), pattern schema (`:rf/route-pattern`), rank schema (`:rf/route-rank`) → `SKILL-REDIRECT.md` → *Spec schemas*.
- Interceptor-based guards (`auth-guard`, redirects), tags-driven policies → *EP — Routing (012)* §Redirects and guards.
- Effective parent-to-leaf resource plans, grouped identity dedupe, the redundant-child advisory, attach-before-release owner handoff → *EP — Resources (016)* §Effective parent-chain resource plans.
- Warm-mode prefetch contract (`:rf.route/prefetch`, the `:rf.route/prefetched` trace, bad-address vs planning failure) → *EP — Routing (012)* §Route-plan prefetch.
- SSR routing (server frame handles `:rf.route/handle-url-change` on the request URL) → `SKILL-REDIRECT.md` → *EP — SSR (011)*.

---

*Derived from `implementation/routing/` (artefact source) and `examples/capabilities/routing/routing/` @ main. Re-verify after route-metadata, guard-protocol, readiness-projection, or prefetch changes.*
