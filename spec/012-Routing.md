# Spec 012 — Routing

> Routing is part of Goal #8 (Real SPA concerns are first-class) per [000-Vision.md](000-Vision.md).

## Abstract

Routing is **state plus events**, not a separate subsystem. The active route is a slice of frame-state — the durable **runtime-db** slice at `[:rf.runtime/routing :current]` — and the URL is a derivable view of it; navigation is an event. Browser back/forward, deep links, and SSR all flow through this single contract.

The principle: routing does not get its own runtime. It uses the runtime that already exists — frames, events, subs, the frame-state container (app-db + runtime-db). A route table is data; routes are registry entries; `:rf.route/navigate` is an event; `(rf/sub :rf/route)` derives the active route from the runtime-db route slice. Locating the slice in runtime-db (not app-db) keeps it out of the application's own contract while still riding frame revertibility, SSR hydration, and epoch restore for free (per [§`runtime-db` slices](#runtime-db-slices)). Nothing new at the foundation level.

## Normative surface inventory

The complete routing API surface, for quick audit. Each entry links to its normative definition below.

### Registration

- **`reg-route`** — registers a route. Canonical 3-slot grammar `(reg-route id metadata path)` (per [001 §Registration grammar](001-Registration.md#registration-grammar)): the URL **`:path` pattern is the third VALUE slot** (a route has no handler fn — its defining value is the pattern, the legitimate "handler-or-value" reading), and the middle slot is the pure reflection-metadata map. Reserved metadata keys: `:doc`, `:params`, `:query`, `:query-defaults`, `:query-retain`, `:tags`, `:parent`, `:on-match`, `:on-error`, `:scroll`, `:can-leave`, `:can-enter`, `:sensitive`, `:large` (`:path` is the VALUE slot, not a metadata key — declaring it inside the metadata map is a loud `:rf.error/invalid-route-metadata`). `:sensitive` / `:large` are projection-relative data classification — see [§Route data classification](#route-data-classification). See [§Reserved route-metadata keys](#reserved-route-metadata-keys) and [§Navigation blocking — pending-nav protocol](#navigation-blocking--pending-nav-protocol) for `:can-leave` / `:can-enter`. Returns its `id` argument per the family-wide [`reg-*` return-value convention](Conventions.md#reg--return-value-convention).
- **Path-pattern grammar** — five productions (literal, named param, optional segment group, splat, root). See [§Path-pattern grammar](#path-pattern-grammar-canonical).
- **Route ranking** — six-rule cascade for resolving overlapping matches. See [§Route ranking algorithm](#route-ranking-algorithm).

### `runtime-db` slices

Routing state splits into two tiers — **durable** runtime-db state under `[:rf.runtime/routing]` (per [Conventions §Reserved runtime-db keys](Conventions.md#reserved-runtime-db-keys)) and **host-side transient** caches (module-private per-frame atoms, **not** `runtime-db`):

- **Route slice** at `[:rf.runtime/routing :current]` — `{:route-id :params :query :fragment :transition :error :nav-token}`. Schema `:rf/route-slice`. Consumer-facing sub-id `:rf/route`. Durable runtime-db; rides the SSR hydration payload + restores coherently on epoch restore (the active `:nav-token` rides *with* the slice). See [§The `:rf/route` slice](#the-rfroute-slice).
- **Pending-nav slot** at `[:rf.runtime/routing :pending-navigation]` — populated when a `:can-leave` guard rejects. Schema `:rf/pending-navigation`. Sub-id `:rf/pending-navigation`. Runtime-db (subscribable + kept in local replay) but **SSR-stripped** (fail-closed allowlist ships only `:current`). See [§Navigation blocking — pending-nav protocol](#navigation-blocking--pending-nav-protocol).
- **Scroll-position LRU** — a host-side **transient** cache (module-private per-frame atom, **not** `runtime-db`); see [§Scroll restoration](#scroll-restoration).
- **Routing counters** (`:nav-token-counter` + `:pending-nav-counter`) — the internal monotonic allocators, held in a host-side **transient** cache (module-private per-frame atom, **not** `runtime-db`); not part of the consumer-facing sub surface. They are host-side specifically so an epoch restore — which replaces the `runtime-db` partition wholesale — cannot rewind them and recycle a token still carried by an in-flight async continuation; see [§Navigation tokens — stale-result suppression](#navigation-tokens--stale-result-suppression).

### Events

Audience column: **user** = an event apps dispatch or handle directly; **runtime** = an internal plumbing event the runtime fires at itself, sub-namespaced under `:rf.route.internal/*` so the user-facing `:rf.route/*` surface stays tidy. Apps and tools should never dispatch `:rf.route.internal/*` events. The same audience-split principle scopes `:rf.route.nav-token/*` and `:rf.machine.internal/*`.

| Event | Audience | Purpose | Source |
|---|---|---|---|
| `:rf.route/navigate` | user | Programmatic navigation. | [§Navigation is an event](#navigation-is-an-event) |
| `:rf.route/handle-url-change` | user | URL-change handler for popstate / initial load / SSR (default scroll `:restore`). Co-equal with `:rf.route/transitioned`. | [§URL changes are events](#url-changes-are-events) |
| `:rf.route/transitioned` | user | URL-change handler for forward navigation (link click / programmatic push; default scroll `:top`). Co-equal with `:rf.route/handle-url-change`. | [§Standard runtime events](#standard-runtime-events) |
| `:rf/url-requested` | user | Fired by `route-link` and equivalent. Decides internal vs external navigation. | [§Standard runtime events](#standard-runtime-events) |
| `:rf.route/navigation-blocked` | user | Dispatched by the runtime when a `:can-leave` (leave) guard rejects. | [§Navigation blocking — pending-nav protocol](#navigation-blocking--pending-nav-protocol) |
| `:rf.route/entry-blocked` | user | Dispatched by the runtime when a `:can-enter` (enter) guard rejects — the mirror of `:rf.route/navigation-blocked`. | [§Navigation blocking — pending-nav protocol](#navigation-blocking--pending-nav-protocol) |
| `:rf.route/continue` | user | User-dispatched: confirm pending navigation (re-runs `:can-enter`). | [§Navigation blocking — pending-nav protocol](#navigation-blocking--pending-nav-protocol) |
| `:rf.route/cancel` | user | User-dispatched: cancel pending navigation. | [§Navigation blocking — pending-nav protocol](#navigation-blocking--pending-nav-protocol) |
| `:rf.route.internal/settle-transition` | runtime | Runtime-fired after the `:on-match` drain to land `:transition` at `:idle`. Nav-token-guarded. | [§Per-route data loading](#per-route-data-loading) |
| `:rf.route.internal/on-match-error` | runtime | Runtime-fired by the corpus-wide error-emit listener when an `:on-match` event throws; flips `:transition :error`, populates `:rf.route/error`, chains `:on-error`. Nav-token-guarded. | [§Per-route error handling](#per-route-error-handling) |

### Effects (`reg-fx`)

| Fx | Purpose | Platform |
|---|---|---|
| `:rf.nav/push-url` | `pushState` for the URL. | `:client` |
| `:rf.nav/replace-url` | `replaceState` for the URL. | `:client` |
| `:rf.nav/scroll` | Apply a scroll strategy. Args carry `{:strategy :from :to :saved-pos :fragment}`. | `:client` |
| `:rf.nav/capture-scroll` | Capture the current scroll position into the host-side scroll-position cache before leaving a route (keyed by the leaving route's URL). See [§Scroll restoration](#scroll-restoration). | `:client` |
| `:rf.route/with-nav-token` | Threads `:nav-token` into a downstream dispatch for stale-result suppression — the nav-token lowered to the [uniform reply envelope](Managed-Effects.md#the-uniform-reply-envelope)'s `{:route/nav-token …}` `:suppress` gate (see [§Lowering onto the uniform reply envelope](#lowering-onto-the-uniform-reply-envelope)). | universal |

### Subscriptions

| Sub | Returns |
|---|---|
| `:rf/route` | The current route slice (`:route-id` `:params` `:query` `:fragment` `:transition` `:error` `:nav-token`). |
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

- `:url-bound?` on `reg-frame` metadata. URL ownership is an explicit declaration — a frame owns the URL only with `:url-bound? true`; there is no `:rf/default`-owns-by-default floor. Only one frame may own the URL. See [§Multi-frame routing](#multi-frame-routing).
- `:url-strategy` on `reg-frame` metadata. A map `{:encode :decode :push! :replace! :install-listener!}` that skins the router's path-form model onto the browser's chosen address-bar form. Default `history-url-strategy` (path-form); the framework also ships `hash-url-strategy` (`#`-prefixed). Consulted at exactly four egress/ingress points; `route-url` / `match-url` stay pure and path-form; SSR ignores strategies. See [§URL strategies](#url-strategies).

### Schemas registered with the framework

- `:rf/route-pattern` — path-pattern grammar (see [Spec-Schemas.md](Spec-Schemas.md#rfroute-pattern)).
- `:rf/route-rank` — structural rank tuple (see [Spec-Schemas.md](Spec-Schemas.md#rfroute-rank)).
- `:rf/route-slice` — the `:rf/route` slice shape (see [Spec-Schemas.md](Spec-Schemas.md#rfroute-slice)).
- `:rf/pending-navigation` — the pending-nav slot shape (see [Spec-Schemas.md §`:rf/pending-navigation`](Spec-Schemas.md#rfpending-navigation)).

### Trace events

Defined per the [009 Error contract](009-Instrumentation.md#error-contract):

- `:rf.route/registered` — first-time `reg-route`. Re-registration rides the cross-kind `:rf.registry/handler-replaced` trace; not re-emitted here. Mirrors the `:rf.flow/registered` symmetry.
- `:rf.route/cleared` — explicit `clear-route`. Mirrors the `:rf.flow/cleared` symmetry.
- `:rf.route/activated` / `:rf.route/deactivated` — fire on every cross-route navigation commit, in `deactivated → activated` order. Same-id navigation (path/query change with no route-id shift) emits neither. First-ever navigation emits `:rf.route/activated` only (no prior route). Both carry `:tags {:route-id <id> :frame <navigating-frame>}`.
- `:rf.route.nav-token/allocated` — fresh nav-token cascade begins. Carries `:tags {:route-id <id> :nav-token <token> :frame <navigating-frame>}`.
- `:rf.route.nav-token/stale-suppressed` — async result carrying a now-superseded token.
- `:rf.route/fragment-changed` — fragment-only URL update (the URL changed only in its `#fragment`; `:on-match` did not re-fire). Distinct from the runtime URL-change events `:rf.route/transitioned` / `:rf.route/handle-url-change`, which carry a full route transition. The op-name says what fires it (only a `#fragment` differed) and disambiguates from those runtime events.
- `:rf.route/navigation-blocked` — `:can-leave` (leave) guard rejected a navigation. Carries `:tags :phase :can-leave :direction :leave`.
- `:rf.route/entry-blocked` — `:can-enter` (enter) guard rejected a navigation; the mirror of `:rf.route/navigation-blocked` (rf2-p69yaz). Carries `:tags :phase :can-enter :direction :enter`.
- `:rf.error/can-leave-non-boolean` — `:can-leave` sub returned a non-boolean value; the runtime BLOCKED the navigation. Closed contract; see [§Navigation blocking — pending-nav protocol](#navigation-blocking--pending-nav-protocol).
- `:rf.error/can-enter-non-boolean` — `:can-enter` sub returned a non-boolean value; the runtime BLOCKED the entry. Closed contract mirror of `:rf.error/can-leave-non-boolean`.
- `:rf.error/route-guard-loop` — a `:can-enter` guard re-blocked the same target across repeated `:rf.route/continue` resumes past the loop ceiling; the runtime STOPPED re-issuing (fail-closed). See [§The loop guard](#the-loop-guard).
- `:rf.error/duplicate-url-binding` — second frame attempted `:url-bound? true` while another already owns the URL.
- `:rf.error/invalid-route-metadata` — `reg-route` was passed a bare metadata key outside the reserved set (a likely typo), or non-map metadata. Thrown at registration (caller bug; dev *and* prod). Names the offending `:keys` and the `:reserved` vocabulary. See [§Authoring-boundary key validation](#authoring-boundary-key-validation).
- `:rf.error/invalid-route-pattern` — `reg-route`'s `:path` value violated the [path-pattern grammar](#path-pattern-grammar-canonical): a missing leading `/`, an empty segment, an invalid param/splat name, a reserved char not percent-encoded, a malformed optional group (unclosed, empty, nested, containing a splat, or spelled slash-**outside** `{:name}?` instead of the canonical slash-inside `{/:name}?`), or more than one splat / a non-final splat. Thrown at registration on the first violation (caller bug; dev *and* prod), before any state mutates. Names the `:route-id`, the `:pattern`, and the offending `:index`. `:where 'rf/reg-route`, `:recovery :no-recovery`. (The full error contract lives in [009 §Error event catalogue](009-Instrumentation.md#error-event-catalogue).)
- `:rf.error/invalid-route-classification` — `reg-route`'s `:sensitive` / `:large` data-classification declaration is structurally malformed (a non-vector axis, a non-sequential path entry, or a non-EDN-identity path segment). Thrown at registration (caller bug; dev *and* prod), before any state mutates and before the route can activate. Names the offending `:axis` and `:bad-path`; a bad segment surfaces the inner `:rf.error/bad-path` under `:rf.error/cause`. `:where 'rf/reg-route`, `:recovery :fix-route-classification`. See [§Route data classification](#route-data-classification).
- `:rf.error/navigate-arity-misuse` — `[:rf.route/navigate target params opts]` was dispatched with an opts-only key (`:replace?` / `:scroll` / `:fragment` / `:bypass-guards?`) in the **params** slot that the target route does not declare as a path-param (the classic params/opts swap). Navigation rejected; `:where :event`. See [§Arities — params is 2nd, opts is 3rd](#arities--params-is-2nd-opts-is-3rd).
- `:rf.warning/route-shadowed-by-equal-score` — registration-time warning when ranking ties on rule 6.
- `:rf.warning/no-not-found-route` — runtime fell back to the built-in placeholder because `:rf.route/not-found` is not registered (per [§Route-not-found](#route-not-found--rfroutenot-found-canonical)).

**Frame attribution.** Every routing trace emitted from inside a known navigation cascade carries `:tags :frame` — the in-flight cascade's frame, validated at the handler boundary by `frame/require-frame-stamp!` and threaded to the emit site. This is load-bearing, not cosmetic: `re-frame.epoch.capture` admits ONLY frame-tagged traces into a cascade's `:trace-events` (an untagged trace silently drops from epoch history / Xray), and the frame-level trace-disable gate (a `:rf.trace/frame-no-emit?` tool frame) keys suppression off `:tags :frame` (an untagged trace leaks into the very ring the inspector reads). The frame-known emit sites are the lifecycle / nav-token traces (`:rf.route/activated`, `:rf.route/deactivated`, `:rf.route.nav-token/allocated`), the leave-guard / blockage diagnostics (`:rf.error/can-leave-non-boolean`, `:rf.warning/can-leave-subs-artefact-missing`, `:rf.route/navigation-blocked`), and the external-URL diagnostic (`:rf.route/external-url-requested`, on both the `:rf/url-requested` and programmatic `:rf.route/navigate {:url …}` paths). The route-miss / malformed-URL diagnostics already carried `:frame`.

## Pattern-level contract

### The route table is data

A route is a `(kind :route, id keyword)` registry entry whose metadata describes its URL pattern, params, and any constraints. Routes register exactly like any other kind:

The canonical 3-slot grammar puts the URL `:path` pattern in the third VALUE
slot; the middle slot is the pure reflection-metadata map:

```clojure
(rf/reg-route :route/home
  {:doc "The landing page."}
  "/")

(rf/reg-route :route/cart
  {:doc "The cart page."}
  "/cart")

(rf/reg-route :route/cart.item-detail
  {:doc    "Detail page for a single cart item."
   :params [:map [:id :uuid]]}
  "/cart/items/:id")

(rf/reg-route :route/article
  {:doc    "An article. Optional slug suffix."
   :params [:map [:id :uuid] [:slug {:optional true} :string]]}
  "/articles/:id{/:slug}?")

(rf/reg-route :route/files
  {:doc    "A files browser; matches /files and any sub-path."
   :params [:map [:rest :string]]}
  "/files/*rest")
```

#### Path-pattern grammar (canonical)

The `:path` value is a **string in the canonical path-pattern grammar** below. The grammar is **part of the pattern contract**, not implementation-specific. Every conforming implementation parses and emits this grammar; conformance fixtures (`routing-match-url.edn`, `routing-navigate.edn`) assume it.

The grammar is small. Five productions:

| Production | Syntax | Example | Captures |
|---|---|---|---|
| **Literal segment** | `/text` | `/articles` | nothing |
| **Named param** | `/:name` | `/articles/:id` | `{:id "..."}` (string by default; coerced via `:params` schema) |
| **Optional segment group** | `{/:name}?` or `{/literal}?` | `/articles/:id{/:slug}?`, `{/:base}?/about` | param present only if matched |
| **Catch-all (splat)** | `/*name` (or bare `/*`) | `/files/*rest` | `{:rest "everything/after"}` (string, includes embedded `/`; matches one-or-more segments) |
| **Root** | `/` | `/` | `{}` |

Rules:

1. **Param names** are unqualified keywords on the consumer side; in the pattern string they are bare identifiers (`:id`, not `::feature/id`). A route's `:params` schema (`[:map [:id :uuid]]`) names the same key.
2. **Optional groups** wrap a slash-prefixed sub-pattern in `{...}?` — the slash lives **INSIDE** the braces (`{/:slug}?`, `{/guide}?`, `{/:base}?`). This is the **one canonical spelling** (rf2-av1): the group is a self-contained optional segment, so it composes in any position — it may **lead** (`{/:base}?/about`), **trail** (`/articles/:id{/:slug}?`), or **sequence** (`/docs{/:section}?{/:page}?`). The slash-**outside** spelling (`/{:base}?/about`, where the `/` is a literal outside the braces) is **not** part of the grammar and is rejected at registration — it is the shape that orphans a separator on elision (`/about` → `//about`, a protocol-relative URL). A group may contain literal segments, named params, or both; nested optional groups are not part of the grammar; a group may not contain a splat.
   - **Rank contribution.** Segments *inside* an optional group count toward **neither** rule-1 static-count **nor** rule-3 total-length (see [§Route ranking algorithm](#route-ranking-algorithm)). A maybe-present segment must never outrank an always-present one, so an optional group contributes only the rule-5 "has-optional-group" bit — its inner segments are structurally invisible to the length-based rules.
3. **Splats** must be the final segment of the path, and there is at most one per pattern. A **named** splat (`/files/*rest`) matches **one or more** trailing segments — `/files/*rest` matches `/files/a` and `/files/a/b/c` but **not** the bare `/files` (zero segments). The captured value is a single string with embedded slashes **preserved** (`{:rest "a/b/c.txt"}`). The **bare** unnamed splat `/*` is the one grammar-special fallback: it matches every URL (including the root `/`) and is the universal least-specific route (see rule 2 of the ranking cascade); it is unnamed, so it exposes no useful path param, and a route whose entire path is `/*` is a match-only sink, not a `route-url` target. `match-url` segments the **raw** URL string first and percent-decodes each captured segment independently, so an encoded slash (`%2F`) inside a segment can never change the route's structure (the encoded-slash path-confusion class is closed by construction).
4. **Trailing slashes** are normalised away by `match-url` before matching: `/cart` and `/cart/` resolve to the same match. `route-url` emits patterns without a trailing slash (except for the root pattern `/`).
5. **Case** is preserved as written; matching is case-sensitive by default. Implementations may offer a per-route `:case-insensitive? true` opt; the conformance corpus assumes case-sensitive matching. (The CLJS reference implementation does not implement the optional `:case-insensitive?` opt — match regexes are always built case-sensitively; the `may` keeps the door open for hosts that need it.)
6. **Reserved characters** (`:`, `*`, `{`, `}`, `?`) inside literal segments must be percent-encoded in the path string; `match-url` URL-decodes captured param values before they reach the handler.
7. The grammar **does not encode** query-string or fragment binding; see "Query strings and fragments" below for those.

The grammar is a small subset of common path-pattern syntaxes — straightforwardly implemented in any host (no parser-combinator library required). It is also a strict subset of RFC 6570 Level 1 plus a splat extension, which keeps it familiar to non-Clojure ecosystems. Other-language ports parse the same strings.

A canonical schema for path patterns is registered as `:rf/route-pattern` (see [Spec-Schemas.md](Spec-Schemas.md#rfroute-pattern)). Tooling can validate patterns at registration time.

> **Data-form path patterns (per host):** the same grammar can be expressed as a vector of segment values — `[:files [:* :rest]]` is the data-form of `/files/*rest`. This is the natural form in hosts without a string-parser library, and lines up with [Principles.md §Data is code](Principles.md#data-is-code). Ports are required to support the string grammar above; hosts may additionally accept a data form whose semantics are equivalent.

#### Route ranking algorithm

When more than one registered route can match the same URL, `match-url` MUST resolve the conflict using the **6-rule ranking cascade** below. The cascade is part of the pattern contract — every conforming implementation produces the same winner for the same registrations and URL. Without this lock, two implementations of `match-url` can both be "reasonable" and still disagree, defeating the cross-host conformance bar.

Ranking rules, evaluated in order. The first rule that distinguishes the candidates wins; later rules are only consulted on ties.

1. **More static segments beat fewer.** Count the literal (non-param, non-splat) segments in each candidate's `:path`. Higher count wins. `/users/me` (2 statics) beats `/users/:id` (1 static) for `/users/me`.
2. **The bare catch-all `/*` is demoted below every other matching route.** The bare top-level catch-all (`/*`, an unnamed splat with no other segments) is the universal least-specific fallback — above only `:rf.route/not-found`. Any other route that matches the same URL wins. This demotion is consulted **before** total-length (rule 3), because the catch-all also matches the root URL `/` (the splat captures the literal `/`), and a registered home route `{:path "/"}` has segment-length 0 while `/*` has length 1 — without the early demotion the catch-all would out-length the root and shadow it. The discriminator is therefore lifted ahead of total-length so `/` (and every other concrete route) wins over `/*`. `/` beats `/*` for `/`; `/files/*rest` (a *named* rest param, not the bare catch-all) beats `/*` for `/files/x/y`. A registered `:rf.route/not-found` is the only route below the bare catch-all.
3. **Among equally-static-counted, non-catch-all matches, longer paths beat shorter.** Total segment count breaks the tie on equal static-count. `/users/:id/edit` beats `/users/:id` for `/users/abc/edit`.
4. **Named params beat rest params.** A `:name` segment is more specific than a `*name` splat. `/files/:name` beats `/files/*rest` for `/files/x`.
5. **Exact routes beat optional-group routes.** A pattern with no `{...}?` group is more specific than a pattern that matches the same URL only by virtue of an optional group. `/about` beats `{/:base}?/about` for `/about`. (Because a group's inner segments count toward neither rule 1 nor rule 3 — see [rule 2 of the path grammar](#path-pattern-grammar-canonical) — a route that reaches a URL through an optional group also loses the length rules to a concrete route of the same static shape: `/docs/:page` beats `/docs{/guide}?` for `/docs/guide`, because the group's `guide` is length-invisible so `/docs/:page` out-lengths it at rule 3.)
6. **Registration order is the final tiebreak only if every structural score is equal.** When two routes are *structurally indistinguishable* (same statics, same length, same params/splats, same optional groups), the route registered first wins. This case is **discouraged** — implementations MUST emit a `:rf.warning/route-shadowed-by-equal-score` warning at registration time when a new route is added and an existing route has an equal structural score on the same URL family. Tooling and AI scaffolds use the warning to flag potential conflicts.

The cascade is **structural** — the score is computable from each pattern's parsed shape; no URL is needed to compute it. Implementations may pre-compute every registered route's score at registration time and rank candidates by score on each `match-url` call.

```text
;; Pseudocode
(defn route-rank [pattern]
  (let [segments       (parse-segments pattern)
        ;; Segments INSIDE an optional group are excluded from BOTH
        ;; static-count (rule 1) and total-length (rule 3): a maybe-present
        ;; segment must never outrank an always-present one (rf2-5u1r6a). Only
        ;; top-level (non-grouped) segments count toward length; the group
        ;; contributes solely the rule-5 has-optional? bit.
        top-level      (remove in-optional-group? segments)
        static-count   (count (filter literal? top-level))
        total-length   (count top-level)
        has-splat?     (some splat? top-level)
        has-optional?  (some optional-group? segments)
        is-catch-all?  (= pattern "/*")]
    ;; Higher score = more specific. Tuple compares lexicographically.
    ;; The catch-all demotion (rule 2) is lifted ahead of total-length
    ;; (rule 3): the bare `/*` also matches the root URL `/`, and a home
    ;; route `{:path "/"}` has total-length 0 while `/*` has length 1, so
    ;; if total-length came first the catch-all would out-length the root
    ;; and shadow it. Putting the catch-all discriminator before length
    ;; makes `/` (and every concrete route) win over `/*`.
    [static-count                 ;; rule 1
     (if is-catch-all? 0 1)       ;; rule 2 — bare catch-all demoted below all
     total-length                 ;; rule 3 — among non-catch-all, longer wins
     (if has-splat? 0 1)          ;; rule 4 — named params beat splats
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

**Conformance.** Fixture `route-ranking-precedence.edn` exercises every cascade rule with overlapping registrations and asserts the same winner across implementations. Hosts that register routes in a different *internal* order MUST sort by registration *time* (the order user code called `reg-route`) for rule 6, not by hash-map iteration order.

**Why this is correctness, not polish.** Without a defined ranking, a CLJS implementation and a JS implementation of `match-url` can each be self-consistent and still disagree on which route wins for `/users/me` when both `/users/me` and `/users/:id` are registered. The conformance corpus depends on a single deterministic answer; ranking is the lock.

#### Other pattern-level requirements

- The path is **parseable both ways**: a path-pattern matched against an incoming URL produces a params map; a route-id + params map produces a URL.
- The params shape is described by the host's idiom (Malli for CLJS dynamic; types for static hosts; per [000-Vision.md](000-Vision.md) on the schema/type duality).
- Routes are **stably-id'd**, queryable via `(rf/registrations :route)`, source-coordinated.
- Route metadata is an **open map**. The pattern reserves a small set of keys (see "Reserved route-metadata keys" below); hosts and applications may add their own keys (e.g. `:myapp/analytics-id`, `:myapp/layout`) under a chosen namespace. Interceptors, guards, layouts, and analytics tooling read those keys via `(rf/handler-meta :route route-id)`.

#### Reserved route-metadata keys

The pattern reserves thirteen keys on `reg-route`'s metadata map, plus the URL `:path` pattern in the third VALUE slot (`:path` is not a metadata key; it is the canonical 3-slot value). All metadata keys are optional. This is the largest registration shape in the v2 surface — for context, `reg-flow` carries six keys total ([013 §The registration shape](013-Flows.md#the-registration-shape)) and `reg-event` reserves only the cross-kind registration metadata. The scale reflects the cross-cutting concerns routing absorbs (URL ↔ params, query/path separation, lifecycle hooks at navigation boundaries, layout chains, scroll behaviour, data classification); the keys do not cluster naturally as one flat list. The four axes below name the clusters (the **Shape** axis additionally carries the value-slot `:path`) so generators reading "what does `reg-route` accept?" can branch on intent rather than scan the docstrings.

##### The four axes

The keys cluster into four axes by what each controls (the value-slot `:path` belongs to the **Shape** axis):

| Axis | Keys | What it controls |
|---|---|---|
| **Shape** — URL ↔ params binding | `:path` (the VALUE slot), `:params`, `:query`, `:query-defaults`, `:query-retain` | What URLs match this route and how their parts coerce into a params/query map. The contract surface that `match-url` and `route-url` agree on. `:path` is the third positional VALUE; the rest are metadata keys. |
| **Lifecycle hooks** — events the runtime dispatches at navigation boundaries | `:on-match`, `:on-error`, `:can-leave`, `:can-enter` | Events the runtime fires on route activation (`:on-match`), on `:on-match` errors (`:on-error`), a sub-id consulted before navigation **away** from this route (`:can-leave`), and a sub-id consulted before navigation **into** this route (`:can-enter` — the first-class mirror). These are the route's reactive surface — handlers run from app code, the runtime owns the dispatch points. |
| **Layout** — how the route fits with neighbours | `:doc`, `:parent`, `:tags`, `:scroll` | How the route is described (`:doc`), composed with others (`:parent` chains layout shells; see [§Nested layouts](#nested-layouts)), grouped for interceptors (`:tags`), and visually transitioned (`:scroll`; see [§Scroll restoration](#scroll-restoration)). |
| **Classification** — data hygiene | `:sensitive`, `:large` | Projection-relative paths (rooted at the route's `{:query … :params …}` projection) whose values are redacted (`:sensitive`) or kept off the wire (`:large`) at egress while the route is active. Lowered into the per-frame elision registry at activation, dropped on route change. See [§Route data classification](#route-data-classification). |

The axes are documentation, not data structure — the keys remain flat on the metadata map. An earlier sketch considered nesting lifecycle hooks under `:hooks {...}`; v1 keeps the flat shape because (a) the registration metadata is read by `(rf/handler-meta :route id)` and tools enumerate top-level keys; nesting would require every consumer to know the nesting; (b) the v1 surface is settled, a nested shape is a v2.x candidate at most. The cluster headings are the carry — a generator scaffolding a route picks the axis first, then the keys.

##### Authoring-boundary key validation

Because `reg-route` carries the largest shape in the surface, a typo'd key (`:on-matched` for `:on-match`, `:querey` for `:query`) would otherwise be silently accepted and fail later at nav-time, or never — a silent-swallow that costs a debugging session. `reg-route` therefore **validates the metadata at the authoring boundary**: a **bare** (unqualified) key outside the reserved metadata keys is rejected at registration with a thrown `:rf.error/invalid-route-metadata` (canonical thrown-error shape per [009 §The thrown-error shape](009-Instrumentation.md#the-thrown-error-shape--the-rferrorid-ex-data-contract); `:where 'rf/reg-route`), whose `:keys` slot names every offending key and `:reserved` slot carries the valid vocabulary. A **`:path` left INSIDE the metadata map** is rejected the same way — `:path` is the third VALUE slot, not a metadata key. This is a **caller bug**, so it throws in dev *and* prod (it is not user input). Non-map metadata is rejected the same way, naming the route.

**Namespaced keys are exempt.** Route metadata is an open map for host/app extension keys, but only under a namespace (`:myapp/analytics-id`, `:myapp/layout`) — see [§Other pattern-level requirements](#other-pattern-level-requirements). Bare keys are the framework's reserved vocabulary; namespaced keys are the extension surface. The split makes the typo case (a bare key) distinguishable from the extension case (a namespaced key) without a registry of permitted host keys.

**Cross-feature reserved keys.** A small number of bare keys are reserved by *other* framework features that extend route metadata — `:head`, owned by SSR ([011 §Head/meta contract](011-SSR.md#headmeta-contract): "routes name which head to use via `:head` route metadata"), and `:resources`, owned by the [Resources artefact](016-Resources.md#route-integration) (Spec 016 §Route integration: declarative server-state metadata layered beside `:on-match`). These pass the guard because the framework owns them, even though they are not among the routing-owned metadata keys above. The accepted-key set is therefore the routing-owned metadata keys plus the enumerated cross-feature keys; a new framework feature that adds a bare route-metadata key adds it to that set. The extension is **late-bound**: routing's accepted-key set is the routing-owned keys UNIONed with whatever a cross-feature artefact publishes under the `:routing/extra-route-keys` hook (the Resources artefact publishes `#{:resources}`), so a routing-only app sees only the routing-owned keys and a route declaring `:resources` in an app that omits the Resources artefact is correctly rejected. The same late-bound seam carries the resources route-entry plan (`:routing/on-route-entry`) and the blocking-transition predicate (`:routing/route-blocking?`); routing never statically requires the Resources artefact.

##### Per-key table

| Key | Axis | Type | Purpose |
|---|---|---|---|
| `:doc` | layout | string | Human-readable description. |
| `:path` | shape | string (path-pattern grammar above) | The URL pattern. The **third VALUE slot**, NOT a metadata key — `(reg-route id {…metadata…} "/path")`. Required (positionally). |
| `:params` | shape | schema | Schema for **path** params (those captured by `:name` / `*name` segments in `:path`). |
| `:query` | shape | schema | Schema for **search/query** params (key-value pairs after `?`). Distinct from `:params`. See "Query strings and fragments". |
| `:query-defaults` | shape | map | Default values for query-string keys when absent from the URL. Applied during `match-url`. See "Query strings and fragments". |
| `:query-retain` | shape | set of keywords | Query-string keys that should be carried through subsequent navigations even when the caller did not supply them. See "Query strings and fragments". |
| `:tags` | layout | set of keywords | User-defined tags (e.g. `:requires-auth`); read by interceptors. |
| `:parent` | layout | route-id | Parent route id (for the nested-layout convention; see "Nested layouts"). |
| `:on-match` | lifecycle | vector of event vectors | Events the runtime dispatches every time this route becomes the active route (server- and client-side). See "Per-route data loading". |
| `:on-error` | lifecycle | event vector | Event the runtime dispatches if any `:on-match` event errors. See "Per-route error handling". |
| `:can-leave` | lifecycle | sub-id | A subscription whose value (boolean) gates navigation **away from** this route. The sub receives the pending target as an argument (`(fn [inputs [_ target] …])`). **Strict contract**: `true` allows, `false` blocks, any other value blocks AND emits `:rf.error/can-leave-non-boolean`. See [§Navigation blocking — pending-nav protocol](#navigation-blocking--pending-nav-protocol). |
| `:can-enter` | lifecycle | sub-id | A subscription whose value (boolean) gates navigation **into** this route — the first-class mirror of `:can-leave` (rf2-p69yaz). The sub receives the pending target as an argument. **Strict contract**: `true` allows, `false` blocks, any other value blocks AND emits `:rf.error/can-enter-non-boolean`. On a block the runtime dispatches `:rf.route/entry-blocked` (the mirror of `:rf.route/navigation-blocked`). See [§Navigation blocking — pending-nav protocol](#navigation-blocking--pending-nav-protocol). |
| `:scroll` | layout | enum or map | Declarative scroll behaviour on entering this route. See "Scroll restoration". |
| `:sensitive` | classification | vector of projection-relative paths | Data classification. Paths (each rooted at the route's `{:query … :params …}` projection) whose values are **redacted** at egress while the route is active. See [§Route data classification](#route-data-classification). |
| `:large` | classification | vector of projection-relative paths | Data classification. Paths whose values are kept **off the wire** (size marker) at egress while the route is active. Sensitive wins over large at the same path. See [§Route data classification](#route-data-classification). |

### The `:rf/route` slice

The runtime maintains the route slice in the frame's **runtime-db** partition at `[:rf.runtime/routing :current]` (per [Conventions §Reserved runtime-db keys](Conventions.md#reserved-runtime-db-keys) — the route slice is framework-owned durable state, not app data):

```clojure
;; in the frame's runtime-db:
{:rf.runtime/routing
  {:current
    {:route-id     :route/article             ;; current route id (self-describing slice key; the sub-id stays `:rf.route/id`)
     :params       {:id #uuid "..."}          ;; path params (matches :params schema)
     :query        {:q "clojure" :page 2}     ;; query/search params (matches :query schema)
     :fragment     "section-2"                ;; URL #fragment, or nil; see "Fragments" below
     :transition   :idle                      ;; :idle | :loading | :error
     :error        nil                        ;; populated when :transition = :error
     :nav-token    "nav-42"}}}                 ;; per-navigation epoch token; see "Navigation tokens"
```

The framework reg-sub `[:rf/route]` reads against `[:rf.runtime/routing :current]` and is the supported consumer surface (apps subscribe via `(rf/sub :rf/route)`; the sub-id remains `:rf/route` for API stability).

`:params` and `:query` are **separate maps**. Path params come from segments captured by the `:path` grammar; query params come from the `?key=value` portion of the URL. They are validated by separate schemas (`:params` and `:query` on `reg-route`). Consumers that prefer a single merged map can build one in a derived sub.

`:fragment` carries the URL `#fragment` part (per "Fragments" below). `:nav-token` is the runtime-allocated navigation epoch (per "Navigation tokens — stale-result suppression" below). Both are runtime-managed; user code reads them through subs.

`:transition` is a tiny FSM driven by the runtime: `:idle` when no navigation is in flight; `:loading` while the active route's `:on-match` events are draining; `:error` if any `:on-match` event errors. See "Per-route data loading" and "Per-route error handling" below.

A canonical schema for the slice is registered as `:rf/route-slice` (see [Spec-Schemas.md](Spec-Schemas.md#rfroute-slice)).

### Route data classification

Per [EP-0025 (Data Classification)](../docs/EP/EP-0025-data-classification.md), every runtime subsystem classifies its own instance data **projection-relative** and lowers it into the per-frame elision registry — applied at instance creation, dropped at teardown. A route is the *subsystem-matrix* `reg-route` row: it is effectively a **singleton current-route** (only one route is active per frame, at `[:rf.runtime/routing :current]`), so the projection root is the current route's `{:query … :params …}`, the classification is applied at **route activation**, and dropped at **route change / deactivation**.

#### Declaring it

A route declares `:sensitive` / `:large` as vectors of paths **relative to the route's `{:query … :params …}` projection** — the author never names the absolute `[:rf.runtime/routing :current …]` storage position:

```clojure
(rf/reg-route :route/oauth-callback
  {:sensitive [[:query :token] [:query :code]]   ;; redact these query values at egress
   :large     [[:params :payload]]               ;; keep this path param off the wire
   :query     [:map [:token :string] [:code :string]]}
  "/oauth/callback")
```

The empty path `[]` is legal and marks the whole route projection. (Query keys are promoted to keyword keys only when the route declares them in its `:query` schema / `:query-defaults` / `:query-retain` — see [§Keyword-interning cap](#keyword-interning-cap-on-query-keys--values); a `:sensitive [[:query :token]]` declaration therefore pairs with a `:query` schema that names `:token` so the slice carries the keyword key the path targets.)

> **Query-key promotion advisory.** Because the slice keys an *undeclared* query key as a **string** while a `[:query :token]` classification path names the **keyword** `:token`, a classification path for a query key the route does not promote *silently fails open* — the keyword path never matches the runtime string key, and the value ships raw with no signal (the hygiene bargain, not a security boundary). To close this authoring footgun, `reg-route` emits a `:rf.warning/route-classification-query-key-unpromoted` **advisory trace** (a warning, never a throw — fail-open is intended) when a `:sensitive` / `:large` `[:query k]` path names a query key the route's `:query` / `:query-defaults` / `:query-retain` vocabulary does not promote. The advisory names the offending key(s) and the fix (add the key to the `:query` schema). A **string-segment** classification path (`:sensitive [[:query "token"]]`) is accepted as concrete EDN at registration but can *never* match a keyword-promoted slot, so it is reported too. Path **params** are immune (path captures are always keyword-keyed), so the advisory is query-axis-only.

#### Lowering and re-rooting

At route activation the projection-relative paths are **re-rooted** under `[:rf.runtime/routing :current …]` — a declared `[:query :token]` classifies the runtime path `[:rf.runtime/routing :current :query :token]` — and installed into the frame's durable elision registry (`[:rf.runtime/elision …]`, per [Conventions §Reserved runtime-db keys](Conventions.md#reserved-runtime-db-keys)) tagged `:source :route`. The install lands **atomically with the slice publish** (the same `:rf.db/runtime` commit), so a route's classification protects its slice from the moment it activates. Classification is **value-independent** and read **only at egress** — the handler, subs, and views always see the real values while the route is active; only the trace bus / Xray / MCP / off-box / SSR egress copies are redacted (per [015 §Data classification](015-Data-Classification.md)).

#### Egress of the route read surfaces (the re-rooting and the bare slice)

The route classification is stored **runtime-db-absolute** (`[:rf.runtime/routing :current :query :token]`), but the framework route subs return a **bare projection** of that slice — `:rf/route` returns the whole `{:route-id :params :query …}` map, `:rf.route/query` the bare `:query` map, `:rf.route/params` the bare `:params` map. A naive egress walk of a bare sub value is rooted at the **whole value** (path `[]`), where the registry's absolute declaration can never match — so the classified `:query` / `:params` would ship **raw** on every read surface that copies the slice off the live value (the `:rf.sub/run` dev-trace, Xray, and the Tool-Pair MCP `read-sub` / `list-subscriptions :include-values` / `snapshot :sub-cache` reads).

To honour the re-rooting, those framework route read subs are treated as **alternate projections of the route-owned durable fact**: their egress copy is walked **re-seeded at the slice's runtime-db storage position** (`:rf/route` at `[:rf.runtime/routing :current]`, `:rf.route/query` at `[… :current :query]`, `:rf.route/params` at `[… :current :params]`), so the candidate declaration-coordinate set starts where the re-rooted `:source :route` decls live and the bare value matches exactly. This is the direct-read sibling of the **SSR hydration** projection of the routing slice (which seeds at the offset `[:rf.runtime/routing]`). The re-seeding is keyed off a small framework **seed table** — only the route-owned read surfaces above; the derived `:rf.route/id` / `:transition` / `:error` / `:fragment` / `:chain` subs carry no classifiable secret and ride verbatim.

This is **not** generic sub-output propagation (there is none): an *app* sub that reads route values into a re-keyed shape is **fail-open** (ships raw — classify its own app-db path to cover it), exactly as [015 §What is removed](015-Data-Classification.md) disclaims for re-keyed copies. Only the framework's own route read surfaces — which are *the route slice under another name* — get this treatment, and only at egress: in-process `@(rf/subscribe [:rf/route])` always returns the real values (the handler, views, and app subs need them).

#### Singleton drop (no leak across a route change)

Because a route is a singleton, activation **replaces** the prior route's `:source :route` entries: a route change drops the leaving route's classification, and a navigation to a route that declares none (including `:rf.route/not-found`) clears the route-sourced entries entirely. Other sources in the registry (`:source :effect` from the [commit-plane classification effects](015-Data-Classification.md#durable-app-db--the-four-commit-plane-effects), `:source :flow`, `:source :machine`) survive untouched and union at egress-lookup time. Frame teardown drops the whole runtime-db elision slot with the frame, so no separate teardown is needed.

#### Sensitive wins over large

A path declared **both** `:sensitive` and `:large` lowers as sensitive **only** — its large entry is dropped at lowering time, so no `:rf.size/large-elided` marker (which would leak path / byte size / digest) is ever emitted for it. This is the install-time complement of the elision walker's sensitive-before-large ordering, identical to the [`reg-frame` classification rule](002-Frames.md).

#### Fail-loud at registration

A **malformed** declaration (a non-vector axis, a non-sequential path entry, a non-EDN-identity path segment) **fails loudly at `reg-route` time** — before any state mutates and before the route can ever activate — with the canonical thrown-error shape (per [009 §The thrown-error shape](009-Instrumentation.md#the-thrown-error-shape--the-rferrorid-ex-data-contract)) carrying `:rf.error/id :rf.error/invalid-route-classification` (`:where 'rf/reg-route`, `:recovery :fix-route-classification`), naming the offending `:axis` and `:bad-path`. A non-EDN segment surfaces the inner `:rf.error/bad-path` cause under `:rf.error/cause`. A **forgotten** classification is fail-open (the value ships raw — the hygiene bargain, not a security boundary).

### Navigation is an event

```clojure
;; The route slice is framework-owned RUNTIME-DB state, so this framework route
;; handler reads it from the `:rf.db/runtime` coeffect and writes it back through
;; the reserved `:rf.db/runtime` effect — NOT the app `:db` effect — exactly like
;; the co-equal `:rf.route/handle-url-change` handler below. The route registrar
;; mints a framework-authority handler, so emitting `:rf.db/runtime` is in-bounds
;; (per [002 §Write authority is by convention]).
(rf/reg-event :rf.route/navigate
  {:doc    "Navigate to a registered route."
   :schema [:cat [:= :rf.route/navigate] [:or :keyword [:map [:url :string]]]
                                    [:? :map]      ;; params
                                    [:? :map]]}    ;; opts
  (fn handler-route-navigate [{rt :rf.db/runtime} [_ target params opts]]
    ;; `resolve-target` is the pure target-resolution step — route-id /
    ;; `:rf.route/self` / `{:url ...}` form dispatch + the query-shaping
    ;; layers (`:query-retain`, `:query-merge`). Specified in
    ;; §`resolve-target` below.
    (let [{:keys [route-id path-params query-params fragment]} (resolve-target target params opts rt)
          route-meta (rf/handler-meta :route route-id)
          url        (rf/route-url route-id path-params query-params fragment)
          push-fx-id (if (:replace? opts) :rf.nav/replace-url :rf.nav/push-url)
          nav-token  (rf/gen-nav-token)]
      {:rf.db/runtime (-> rt
               (assoc-in [:rf.runtime/routing :current]
                         {:route-id   route-id
                          :params     path-params
                          :query      query-params
                          :fragment   fragment
                          :transition (if (seq (:on-match route-meta)) :loading :idle)
                          :error      nil
                          :nav-token  nav-token}))
       :fx (into [[push-fx-id url]
                  [:rf/trace [:rf.route.nav-token/allocated {:route-id route-id :nav-token nav-token}]]
                  (when-let [scroll (resolve-scroll route-meta opts fragment)]
                    [:rf.nav/scroll scroll])]
                 ;; per-route :on-match dispatches (see "Per-route data loading")
                 (for [ev (:on-match route-meta)]
                   [:dispatch ev]))})))
```

Three effect categories flow:
1. The runtime-db `:rf/route` slice (at `[:rf.runtime/routing :current]`) is updated (id, params, query, fragment, transition, nav-token).
2. The browser URL is pushed via `:rf.nav/push-url` (a registered fx; `:platforms #{:client}`), or replaced via `:rf.nav/replace-url` when `opts` has `:replace? true`.
3. The route's `:on-match` events (if any) are dispatched, and the route's `:scroll` strategy (if any) is emitted as a `:rf.nav/scroll` effect.

The order is locked: state changes first, URL update second, then `:on-match` dispatches and scroll effect. If the URL update fails (browser denies, user is offline) the state is still consistent.

<a id="the-navigate-opts--canonical-list"></a>
##### The `navigate` opts — canonical list

The trailing `opts` map (the **third** positional slot; see [§Arities](#arities--params-is-2nd-opts-is-3rd)) is open. This table is the **single canonical enumeration** of the keys the runtime recognises — the docs guide references it rather than re-listing, so the two never drift:

| Opt | Effect |
|---|---|
| `:replace?` | Use `replaceState` rather than `pushState` — for redirects, search-as-you-type filters, and login-flow returns where the back button should not land on the intermediate URL. |
| `:query` | The **query-string params** for the new URL — coerced against the target route's `:query` schema and emitted as `?key=value`. This is the query slot for the `[:rf.route/navigate target params opts]` form (path params ride the 2nd slot). |
| `:query-merge` | Fold the given deltas into the **current** route's `:query` slice, rather than replacing it — the "stay here, change these query params" primitive (search, pagination, tabs). A `nil` value **removes** a key (matching `route-url`'s query nil-elision; see [§Bidirectional URL ↔ params](#bidirectional-url--params)). Its natural pairing is the [`:rf.route/self`](#rfrouteself--navigate-in-place) target. See [§The `:query-merge` opt](#the-query-merge-opt--navigate-in-place). |
| `:scroll` | Per-call override of the route's `:scroll` metadata; same enum/map shape (see [§Scroll restoration](#scroll-restoration)). |
| `:fragment` | Target `#fragment` for the new URL (see [§Fragments](#fragments)). May also be supplied as `:fragment` on the target map. |
| `:bypass-guards?` | A **set** of `#{:leave :enter}` naming which navigation guards to skip for this navigation — `#{:leave}` skips the active route's `:can-leave` gate, `#{:enter}` skips the target route's `:can-enter` gate, `#{:leave :enter}` skips both (see [§Navigation blocking](#navigation-blocking--pending-nav-protocol)). |

Hosts and apps may add their own keys under a chosen namespace. Only `:replace?`, `:scroll`, `:fragment`, and `:bypass-guards?` are treated as **opts-only** for the params/opts swap check (see [§Arities](#arities--params-is-2nd-opts-is-3rd)); `:query` and `:query-merge` are not, because a route may legitimately capture a path segment of that name.

##### Arities — params is 2nd, opts is 3rd

The event vector has two trailing **maps** in fixed positions — `params` second, `opts` third — which are positionally ambiguous to a reader. The three arities are:

```clojure
[:rf.route/navigate target]              ;; no path-params, no opts
[:rf.route/navigate target params]       ;; PATH-PARAMS in the 2nd slot; opts absent
[:rf.route/navigate target params opts]  ;; path-params 2nd, OPTS THIRD
```

At a call site `[:rf.route/navigate :route/x {...}]` the `{...}` is **path-params**, not opts. The classic mistake is dropping an opts-shaped map into the params slot — `[:rf.route/navigate :route/cart {:replace? true}]` *reads* like "navigate with these opts" but is parsed as "navigate with path-param `:replace?`". The runtime **rejects this swap**: when an opts-only key (`:replace?`, `:scroll`, `:fragment`, `:bypass-guards?`) appears in the params slot and is **not** a declared path-param of the target route, navigation is rejected (slice unchanged, no URL push) and `:rf.error/navigate-arity-misuse` (`:where :event`) is emitted naming the misplaced key. A route that *legitimately* captures a path segment named `:fragment` (`"/anchor/:fragment"`) is not false-flagged — the key is a declared path-param, not a misplaced opt. To pass opts, use the explicit three-arity form with an empty (or real) params map: `[:rf.route/navigate :route/cart {} {:replace? true}]`.

The [`:rf.route/self`](#rfrouteself--navigate-in-place) reserved target uses the same three-arity form — `[:rf.route/navigate :rf.route/self {} {:query-merge {…}}]` — where the 2nd slot is an ignored `{}` placeholder (self-nav never re-threads path params) and the opts carry the query change.

#### Target form — route-id, URL-string, or reserved

`:rf.route/navigate`'s second arg (`target`) is one of three forms (per the schema's `[:or :keyword [:map [:url :string]]]` — the reserved-target form is a distinguished keyword, so it fits the `:keyword` branch):

- **Route-id (canonical):** `(rf/dispatch [:rf.route/navigate :route/cart {:cart-id 42}])`. The runtime resolves the route, builds the URL via `route-url`, and pushes. This is the form Construction-Prompts and well-formed app code use.
- **URL-string (escape hatch):** `(rf/dispatch [:rf.route/navigate {:url "/some/path"}])`. For dynamic or user-supplied URLs the app didn't build itself — deep-link handlers, server-redirect targets, programmatic redirects from a string. The runtime calls `match-url` on the string; if it resolves to a registered route, navigation proceeds normally. URL-strings that don't match any registered route resolve to `:rf.route/not-found` with the URL in `params`, and the runtime pushes the **requested URL** to the address bar (not the not-found route's own `:path`) — the user keeps the URL they aimed at while the not-found view renders, exactly as the URL-driven not-found path leaves the requested URL in place. A miss with no `:rf.route/not-found` route registered still commits the not-found slice and emits the `:rf.warning/no-not-found-route` trace (per [§Route-not-found](#route-not-found--rfroutenot-found-canonical)); it is not rejected.
- **Reserved `:rf.route/self` (navigate-in-place):** `(rf/dispatch [:rf.route/navigate :rf.route/self {} {:query-merge {:page 2}}])`. Stay on the current route, changing only the query (or fragment). See [§`:rf.route/self` — navigate-in-place](#rfrouteself--navigate-in-place) below.

The route-id form is preferred everywhere it can be used because the route-id is enumerable, refactorable, and queryable through the registrar. URL-strings are stringly-typed escape-hatchy by nature; tooling can flag them as candidates for migration to a registered route-id when the URL pattern is known.

<a id="rfrouteself--navigate-in-place"></a>
##### `:rf.route/self` — navigate-in-place

`:rf.route/self` is a **reserved navigate target** meaning *stay on the current route; change only these query params (or fragment)*. It is the single most common URL operation — search, pagination, tab switches, filter toggles — where the route (path) is unchanged and only the query string moves. Without it, an app hand-rolls a runtime-db-reading helper: read the current route-id and path-params out of the slice, branch on the id to re-thread the path params, and rebuild the query — all to change `?page=N`. React Router does this in one `setSearchParams` call; `:rf.route/self` is the re-frame2 equivalent, paired with the [`:query-merge` opt](#the-query-merge-opt--navigate-in-place).

It is a **documented, semantically-honest exception** to the "targets are enumerable route-ids" rule: `:rf.route/self` is not a registered route, it is the reserved identity-on-route sentinel. Resolution:

- **`:route-id` and path-`:params`** are read from the **current** route slice (`[:rf.runtime/routing :current]`). The path is held fixed — `:rf.route/self` never re-threads path params, so the **2nd `params` slot is ignored** (pass `{}`, the canonical placeholder). To change path params, navigate to the route-id form instead.
- **Query** starts from `(:query opts)` (empty by default) exactly like a route-id target; the [`:query-merge` opt](#the-query-merge-opt--navigate-in-place) then folds the caller's deltas into the **current** query.
- **Fragment** resolves as for any target (`:fragment` opt, else the current fragment is *not* carried — a self-nav that omits `:fragment` clears it, matching a route-id nav; supply `:fragment` to set one).
- The resolved route-id's `:query` schema still validates the merged query at the call site (per [§Param validation at the call site](#param-validation-at-the-call-site)), and `:query-retain` on the resolved route still applies. Scroll, `:can-leave`, the rule-3 no-op short-circuit, and nav-token allocation all behave exactly as for the equivalent route-id nav.
- **Before the first navigation** there is no current route: `:route-id` resolves to `nil` and the `route-url` build fails closed to a rejected navigation (`:rf.error/schema-validation-failure` wrapping `:rf.error/no-such-route`; slice unchanged, no push) — the same fail-closed reject as any unresolvable target.

<a id="the-query-merge-opt--navigate-in-place"></a>
##### The `:query-merge` opt — navigate-in-place

`:query-merge` is a navigate **opt** (third slot) that folds the given deltas into the **current** route's `:query` slice, rather than replacing it. It is the "change these query params, keep the rest" primitive:

```clojure
;; On /search?q=clojure&page=1&sort=recent  →  /search?q=clojure&page=2&sort=recent
(rf/dispatch [:rf.route/navigate :rf.route/self {} {:query-merge {:page 2}}])

;; Remove a key: nil value drops it  →  /search?q=clojure&page=2
(rf/dispatch [:rf.route/navigate :rf.route/self {} {:query-merge {:sort nil}}])
```

Semantics:

- **Base is the current query.** The merge reads the current `:query` slice from the runtime-db (`[:rf.runtime/routing :current :query]`) — the **same read-and-merge machinery `:query-retain` uses** — and folds the deltas on top. Precedence, low→high: current query → any already-resolved query (`:query` opt + `:query-retain` keys) → the `:query-merge` deltas. An explicit delta wins.
- **A `nil` value REMOVES a key** from the result — from both the pushed URL and the written slice — matching `route-url`'s query nil-elision (see [§Bidirectional URL ↔ params](#bidirectional-url--params)). Eliding here (rather than writing `{:sort nil}` into the slice and relying on `route-url` to drop it at emission) keeps the slice consistent with the URL: a self-nav to the same query is a genuine rule-3 no-op. A present-but-**falsy** value (`false`, `0`, `""`) is a legitimate value and survives, same as `route-url`.
- **Target-agnostic.** `:query-merge` works with any target — its natural pairing is `:rf.route/self` (where the current route *is* the target), but `[:rf.route/navigate :route/other {} {:query-merge {…}}]` folds the current query into the *other* route's outgoing query too (subject to that route's `:query` schema). When `:query-merge` is absent the query resolves exactly as before — no current-query base is folded in.

`:query-merge` is **not** treated as an opts-only key for the params/opts swap check (a route may legitimately capture a path segment named `:query-merge`), identical to `:query`.

<a id="resolve-target"></a>
##### `resolve-target` — the target-resolution contract

The navigate handler resolves its `target`/`params`/`opts` triple into a `{:route-id :path-params :query-params :fragment}` shape through **`resolve-target`** (referenced in the handler sketch above). It is a **pure** function of the target triple plus the current route slice — no side effects, no dispatch. It dispatches on the `target` form:

| `target` form | `:route-id` | `:path-params` | `:query-params` |
|---|---|---|---|
| **route-id** `:route/x` | the route-id | the 2nd `params` slot | `(:query opts)` |
| **reserved** `:rf.route/self` | the **current** slice's `:route-id` | the **current** slice's `:params` (2nd slot ignored) | `(:query opts)` |
| **URL-string** `{:url s}` | `match-url`'s route-id (or `:rf.route/not-found` on a miss / open-redirect / throw) | `match-url`'s params (or `{:url s}` on a miss) | `match-url`'s query |

After the form dispatch, `resolve-target` applies the query-shaping layers uniformly: `:query-retain` on the resolved route (verbatim carry from the current slice — see [§`:query-retain` cross-route coercion class](#query-retain-cross-route-coercion-class)), then the [`:query-merge`](#the-query-merge-opt--navigate-in-place) fold (current-query base, nil removes a key). Fragment resolves from `:fragment` opt → target-map `:fragment` → URL-embedded fragment (`match-url`), normalised so an empty-string fragment collapses to nil. `resolve-target` performs **no** validation and **no** URL build — those happen downstream (`route-url` at the call site raises the structured caller-bug error, which the handler catches and rejects). This keeps `resolve-target` a pure planning step: given the same triple and slice it always yields the same resolved shape, on JVM and CLJS alike.

### URL changes are events

When the URL changes from the *link/browser* layer, the runtime fires one of **two co-equal events** (the pattern's `onUrlChange` analogue per Elm's Browser.application; see "Standard runtime events" below). Both write the `:rf/route` slice from the URL and run identical match/validation/fragment/`:on-match` logic; they differ only in *who fires them* and the *default scroll strategy*. (Programmatic `:rf.route/navigate` is the *third* commit path: it writes the slice **inline** in its own handler — see [§Navigation is an event](#navigation-is-an-event) — and does **not** dispatch either of these two events.)

- **`:rf.route/transitioned`** — forward navigation from a `route-link` click (dispatched by `:rf/url-requested` after the `:can-leave` gate passes and the URL is pushed). Default scroll strategy `:top`.
- **`:rf.route/handle-url-change`** — popstate (Back/Forward), initial page load, and the server-side request URL during SSR. Default scroll strategy `:restore` (the saved position for the URL being returned to). On SSR the runtime threads the `:frame` id through so per-frame error projections can attribute a `:no-such-handler` trace.

Neither delegates to the other — they are sibling handlers over one shared slice-rewrite. The handler below is `:rf.route/handle-url-change`; `:rf.route/transitioned`'s handler is identical except for the `:top` default scroll and the SSR `:frame` threading:

```clojure
;; The route slice is framework-owned RUNTIME-DB state, so this framework
;; route handler reads it from the `:rf.db/runtime` coeffect and writes it back
;; through the reserved `:rf.db/runtime` effect — NOT the app `:db` effect. The
;; route registrar mints a framework-authority handler, so emitting
;; `:rf.db/runtime` is in-bounds (per [002 §Write authority is by convention]).
(rf/reg-event :rf.route/handle-url-change
  {:doc       "Triggered by URL change (popstate or initial load). Sets the route slice in runtime-db from the URL."
   :platforms #{:client :server}}                 ;; same handler is used by SSR
  (fn handler-route-handle-url-change [{rt :rf.db/runtime} [_ url]]
    (let [{:keys [route-id params query fragment validation-failed?]} (rf/match-url url)
          route-meta                                                  (rf/handler-meta :route route-id)
          prev-route                                                  (get-in rt [:rf.runtime/routing :current])
          fragment-only?                                              (and prev-route
                                                                           (= route-id (:route-id prev-route))
                                                                           (= params   (:params prev-route))
                                                                           (= query    (:query prev-route))
                                                                           (not= fragment (:fragment prev-route)))
          nav-token                                                   (if fragment-only?
                                                                        (:nav-token prev-route)         ;; fragment-only does not advance the epoch
                                                                        (rf/gen-nav-token))]            ;; fresh epoch
      (cond
        ;; No match → 404 route
        (nil? route-id)
        {:rf.db/runtime (assoc-in rt [:rf.runtime/routing :current]
                       {:route-id :rf.route/not-found
                        :params {:url url}
                        :query {} :fragment fragment
                        :transition :idle :error nil
                        :nav-token nav-token})}

        ;; Validation failure → 404 (or, optionally, a configured error route)
        validation-failed?
        {:rf.db/runtime (assoc-in rt [:rf.runtime/routing :current]
                       {:route-id :rf.route/not-found
                        :params {:url url :reason :validation}
                        :query {} :fragment fragment
                        :transition :idle :error nil
                        :nav-token nav-token})}

        ;; Fragment-only change — update the slice; emit
        ;; :rf.route/fragment-changed trace; do NOT re-fire
        ;; :on-match. See "Fragments" below.
        fragment-only?
        {:rf.db/runtime (assoc-in rt [:rf.runtime/routing :current :fragment] fragment)
         :fx [[:rf/trace [:rf.route/fragment-changed {:route-id route-id
                                                      :prev-fragment (:fragment prev-route)
                                                      :next-fragment fragment}]]]}

        :else
        {:rf.db/runtime (assoc-in rt [:rf.runtime/routing :current]
                       {:route-id   route-id
                        :params     params
                        :query      query
                        :fragment   fragment
                        :transition (if (seq (:on-match route-meta)) :loading :idle)
                        :error      nil
                        :nav-token  nav-token})
         :fx (into [[:rf/trace [:rf.route.nav-token/allocated {:route-id route-id :nav-token nav-token}]]]
                   (for [ev (:on-match route-meta)]
                     [:dispatch ev]))}))))
```

The same handler runs **on the server during SSR** (no `:platforms` exclusion) — the request URL is fed in, the route slice is set, the view renders against it. The `:on-match` events also fire server-side, populating server-rendered data the same way they would client-side. No SSR-specific routing code.

### Linking from views — plain-anchor semantics

> **Lock: the runtime does not auto-intercept `<a>` clicks. Click interception is the host adapter's job.**

| Form | Behaviour |
|---|---|
| `[rf/route-link {:to :route/cart} "Cart"]` | Renders `<a href="...">` and intercepts plain primary-button clicks itself — its registered view body (per [§Standard runtime events](#standard-runtime-events)) calls `.preventDefault` and dispatches `:rf/url-requested`. The dispatch **carries the frame address captured at render time** (per [EP-0002 carried-invariant](002-Frames.md) — the render-time `with-frame` / frame-provider scope has unwound by the time the click fires, so the click closure pins the rendering frame just as a `capture-frame` does for view bodies; resolving the frame ambiently at click time would raise `:rf.error/no-frame-context` or route to the wrong frame). Modifier keys (cmd-click, middle-click, shift-click) defer to the browser; the link follows the `href` natively. |
| `[:a {:href "..."} ...]` (plain anchor in user view code) | Browser-native navigation. The runtime does **not** intercept; clicking causes a full page load if the URL is on the same origin and an external navigation otherwise. Apps that want SPA-style interception on plain anchors install it at the **host adapter** layer (a top-level `click` listener on the document that consults `match-url`); the runtime's contract stops at `route-link` plus `:rf/url-requested`. |

**Why the runtime doesn't auto-intercept.** A global `click` listener that calls `match-url` on every link is a host concern (DOM-bound, browser-only, conflicts with non-routed `<a>` tags inside iframes / shadow DOM / third-party widgets). The host adapter has the context to install or skip it; the runtime stays portable.

Users who want plain anchors to be interceptable register their own delegating handler at the host layer, dispatching `:rf/url-requested` on match — this re-uses the same decision-point event the runtime already exposes, so the test surface and policy are unchanged.

### Reading the route is a sub

The `:rf/route` sub projects the published slice keys via `select-keys` over the route slice at `[:rf.runtime/routing :current]`. The internal `:pending-navigation` slot lives alongside `:current` under `[:rf.runtime/routing]` in `runtime-db` (it has its own `:rf/pending-navigation` sub) but does not surface through the `:rf/route` sub — consumers that `deref` the route sub see only the slice. The nav-token / pending-nav **counters** are **not** `runtime-db` siblings at all — like the scroll-position cache they live in host-side transient caches (per [§Navigation tokens — stale-result suppression](#navigation-tokens--stale-result-suppression) and [§Scroll restoration](#scroll-restoration)) — so no internal counter tick ever touches `runtime-db` or risks a route-sub re-render.

These are **framework subscriptions** — their layer-1 reader runs against the frame's **runtime-db** projection (where the route slice lives), not the app-db projection (per [002 §Subscriptions read the partition they belong to](002-Frames.md#subscriptions-read-the-partition-they-belong-to) and [006 §Frame-state container and partition projections](006-ReactiveSubstrate.md#frame-state-container-and-partition-projections)). The `rt` arg below is the runtime-db projection. A runtime-only route commit propagates to these subs (and to nothing in app-sub-land); app authors consume them through the public sub-ids, never by reaching into runtime-db.

```clojure
(def route-slice-keys
  [:route-id :params :query :fragment :transition :error :nav-token])

(rf/reg-sub :rf/route
  {:doc "The current route slice: {:route-id :params :query :fragment :transition :error :nav-token}."}
  (fn sub-route [rt _] (select-keys (get-in rt [:rf.runtime/routing :current]) route-slice-keys)))   ;; rt = runtime-db projection

(rf/reg-sub :rf.route/id   ;; sub-id stays :rf.route/id; reads the slice's :route-id key
  :<- [:rf.runtime/routing :current]
  (fn [route _] (:route-id route)))

(rf/reg-sub :rf.route/params
  :<- [:rf.runtime/routing :current]
  (fn [route _] (:params route)))

(rf/reg-sub :rf.route/query
  :<- [:rf.runtime/routing :current]
  (fn [route _] (:query route)))

(rf/reg-sub :rf.route/fragment
  :<- [:rf.runtime/routing :current]
  (fn [route _] (:fragment route)))     ;; URL #fragment string, or nil

(rf/reg-sub :rf.route/transition
  :<- [:rf.runtime/routing :current]
  (fn [route _] (:transition route)))    ;; :idle | :loading | :error

(rf/reg-sub :rf.route/error
  :<- [:rf.runtime/routing :current]
  (fn [route _] (:error route)))

(rf/reg-sub :rf/pending-navigation
  (fn [rt _] (get-in rt [:rf.runtime/routing :pending-navigation])))   ;; rt = runtime-db projection; pending-nav slot when :can-leave guard rejects, else nil
```

Views derive UI from the route the same way they derive UI from any other state — no special routing API in views. A common pattern: a global progress bar reads `:rf.route/transition` and renders when the value is `:loading`; an error banner reads `:rf.route/error`.

For the common top-level read, the framework ships **`sub-route`** as named read sugar over the canonical `[:rf/route]` vector — `@(rf/sub-route)` is `@(rf/subscribe [:rf/route])`, returning a reaction over the published slice. The route is a per-frame singleton, so the primary form is **zero-arity**; a 1-arity `(sub-route opts)` carries `{:frame <target>}` (a frame-id keyword or a live frame object, lowered to `subscribe`'s frame-first arity) to read a non-default URL-bound frame's slice from outside an established scope. `sub-route` is exported on the **`re-frame.routing` façade — not `re-frame.core`** — so a non-routing app's production-elision bundle carries no route keyword strings (the routing bundle-isolation invariant); the `[:rf/route]` vector stays the canonical registered sub and the sugar coexists with it. This mirrors `sub-machine` for machine snapshots — both are runtime-db reads, and the convention is that runtime-db state is eligible for named read sugar while app-db content (including flow output) is read with the ordinary `subscribe` (per [Conventions §Reserved sub-ids](Conventions.md#reserved-sub-ids)).

The pending-nav slot is the route slice's runtime-db sibling, and it gets the matching sugar: **`sub-pending-navigation`** over the canonical `[:rf/pending-navigation]` vector — `@(rf/sub-pending-navigation)` is `@(rf/subscribe [:rf/pending-navigation])`, returning a reaction over the pending-nav map `{:id :requested-url :requested-by-event :reason :rejecting-route :rejecting-guard}` (or `nil` in the steady state — the slot is non-nil only while a `:can-leave` guard holds a blocked navigation awaiting `:rf.route/continue` / `:rf.route/cancel`, per [§Navigation blocking — pending-nav protocol](#navigation-blocking--pending-nav-protocol)). Like `sub-route` the slot is a per-frame singleton, so the primary form is **zero-arity** and a 1-arity `(sub-pending-navigation opts)` carries the same `{:frame <target>}` capability; it is exported on the **`re-frame.routing` façade — not `re-frame.core`** — and the `[:rf/pending-navigation]` vector stays canonical with the sugar coexisting.

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
  - Builds the URL from the `:path` template, substituting path params, then appends `?key=value&...` for any `query-params`, then appends `#fragment` if the 4-arity `fragment` arg is supplied (and non-nil/non-empty). Ordinary path params, query keys/values, **and the fragment** are percent-encoded **per component** on emission (encodeURIComponent semantics) — symmetric with `match-url`, which decodes each portion the same way. A fragment carrying a literal `%` (`"50% done"`) emits as `#50%25%20done` and `match-url` reads it back as `"50% done"`; emitting the raw value would produce a `#fragment` that `match-url` rejects as malformed (the bare `%` fails to decode) → a route-miss, breaking the bidirectional contract. **Splat values are the one exception: they are encoded PER CHUNK.** A splat capture is a multi-segment string whose embedded `/` are structural separators, not data — so `route-url` splits the value on `/`, `encodeURIComponent`-encodes each chunk, and rejoins with raw `/` (a whole-value `encodeURIComponent` would emit `%2F` for every separator and break the round-trip). `route-url(:route/files, {:rest "a/b/c.txt"})` therefore emits `/files/a/b/c.txt`, and `route-url(:route/files, {:rest "my file/50%.txt"})` emits `/files/my%20file/50%25.txt` — each chunk encoded, the separators preserved. `match-url` is the exact inverse: it segments the raw URL first, then decodes each chunk, so an encoded slash inside a chunk can never change the route's structure.
  - **Keyword-enum values emit their declared token name** (path or query). A slot declared `[:enum :asc :desc]` carrying the keyword `:asc` emits the token `asc` — the exact inverse of `match-url`'s enum decode (which interns the declared name back to `:asc`), so `match-url(route-url(...))` recovers the canonical enum keyword. (Host `(str :asc)` would emit `%3Aasc`, which `match-url`'s enum decoder — keyed on the declared names `asc` / `desc` — reads as the string `":asc"`, not `:asc`, breaking the prism.) This also covers a `:query-retain` value carried as a keyword from a prior match into an outgoing `route-url`. An out-of-enum keyword fails `:rf.error/route-url-validation` rather than being stringified into a URL.
  - **Does not navigate.** It is a string-builder; there is no side-effect on `app-db`, no `pushState`, no dispatch. To navigate, dispatch `:rf.route/navigate` (which uses `route-url` internally).
  - **Does not read `app-db`.** Inputs are the registered route table (static) and the caller-supplied params/query/fragment. Same inputs always produce the same string output.
  - **`:query-retain` is NOT applied here.** Carrying through retained keys (`:theme`, `:locale`, etc.) is an `app-db`-aware concern — `:rf.route/navigate`'s handler reads the *current* `:rf.route/query` slice and merges retained keys into the outgoing query *before* calling `route-url`. Callers that want the same merge behaviour without going through `:rf.route/navigate` perform the merge themselves (read `:query-retain` off the route, `select-keys` from the current `:rf.route/query`, merge under their own values, then call `route-url`). Keeping `route-url` pure is the lock — it is the function the conformance corpus and the SSR pipeline call without an `app-db` in hand.
  - Throws `:rf.error/route-url-validation` if `path-params` doesn't conform to the route's `:params` schema, or `query-params` doesn't conform to the route's `:query` schema (caller bug; not user input).

Both work against the same registered route table, so adding/removing a route updates both directions automatically.

##### `route-url` nil-policy: path params vs query params

`route-url` applies **two different nil-policies** to the path side and the query side — same function, opposite rules. The split is deliberate, but it is surprising enough to document explicitly (it otherwise costs a debugging session when `{:page nil}` mysteriously vanishes):

| Slot | `nil` (or absent) value | present-but-falsy value (`false`, `0`) | empty string `""` |
|---|---|---|---|
| **Path param** (a `:name` / `*name` segment) | **Hard error** — throws `:rf.error/missing-route-param`. The URL cannot be built without the segment. | **Round-trips.** A falsy-but-present value is a legitimate segment (`/items/0`, `/page/false`). | **Hard error** — throws `:rf.error/missing-route-param`. A zero-length segment cannot round-trip. |
| **Query param** (a key in `query-params`) | **Silently elided** — `{:page nil}` omits the key entirely (no bare `?page=`, no throw). | **Round-trips.** A falsy-but-present value emits (`?archived=false`). | **Round-trips** — emits `?key=` (a present empty value, distinct from absent). |

The query-side elision is the useful default for **absent optional query keys**: a search form that conditionally adds `?sort=` only when a sort is chosen passes `{:sort nil}` and gets a clean URL with no `sort` key. The path side cannot elide — a missing path segment has no URL to produce — so it throws. The non-empty falsy values (`false`, `0`) stringify to legitimate segments and round-trip on both sides; the **empty string** is the exception on the path side: a `""` segment would emit a zero-length path component (`/articles/` for `{:slug ""}`) that `match-url`'s trailing-slash normalisation erases (`/articles/` → `/articles`) before matching, so it cannot round-trip back to the same route/params. Rather than emit a URL that silently fails to parse, the path side rejects an empty-string segment on emission (`:rf.error/missing-route-param`), the same as `nil`/absent. The query side has no such constraint — `?key=` is a representable, round-trippable present-empty value. Authors who need a query key to always be present must supply a non-nil value (use a sentinel string, not `nil`).

#### Param validation at the call site

The two boundaries where route params enter the runtime — **programmatic navigation** (`route-url` / `:rf.route/navigate`) and **URL-driven navigation** (`match-url`) — validate against the route's `:params` and `:query` schemas, with different failure modes on each side.

| Boundary | Source | Validation failure |
|---|---|---|
| **Programmatic** — `(route-url route-id path-params query-params)` | Caller supplies the params map directly. | **Throws** `:rf.error/route-url-validation` (caller bug; not user input). The schema-explanation is on the exception's data; the trace event is emitted at the same time. |
| **Programmatic** — `[:rf.route/navigate target params opts]` | Caller dispatches an event. | The event-boundary validation interceptor runs the route's `:params` / `:query` schema on the supplied maps **before transitioning**. Failure emits `:rf.error/schema-validation-failure` (per [009 §Error event catalogue](009-Instrumentation.md#error-event-catalogue), `:where :event`) and the navigation is **rejected** — the `:rf/route` slice does not change. |
| **URL-driven** — `(match-url url)` | Browser URL (popstate, link click, deep link). | `:validation-failed? true` in the result; `:rf.route/handle-url-change` routes to `:rf.route/not-found` with `:reason :validation`. |

The asymmetry is deliberate. Programmatic navigation is *caller code* — schema failures are bugs and should be surfaced loudly (throw / reject). URL-driven navigation is *user input* — schema failures are 404s, not exceptions. Both paths share the same `:params` / `:query` schemas (per [Spec 010](010-Schemas.md)), so a route that compiles cleanly with one validates the same way against the other.

The event-boundary validation for `:rf.route/navigate` is a re-use of the standard schema-validation interceptor (the `:schema` slot on the `reg-event` registration) — no routing-specific machinery.

##### Validation-error surfacing across the three paths

The three validation paths surface failures through **three different error/no-error shapes**. The table below names what an observer sees on each path so tools and handlers branch on the right surface.

| Path | Error id | Trace `:operation` | Cascade-level error fired? | Slice discriminator |
|---|---|---|---|---|
| Programmatic — `(route-url ...)` | `:rf.error/route-url-validation` | none (synchronous throw) | thrown directly via `ex-info`; not on the trace bus | n/a (the call throws; no slice write) |
| Programmatic — `[:rf.route/navigate ...]` | `:rf.error/schema-validation-failure` (with `:where :event`) | `:rf.error/schema-validation-failure` per [009 §Error event catalogue](009-Instrumentation.md#error-event-catalogue) | yes — rides the always-on error-emit substrate ([009 §Production builds](009-Instrumentation.md#production-builds-zero-overhead-zero-code)) | n/a (navigation rejected; slice unchanged) |
| URL-driven — `(match-url ...)` → `:rf.route/handle-url-change` | no `:rf.error/*` — the failure becomes a not-found | none (`:rf.warning/malformed-url` may fire for a separate sibling case) | no | `:rf/route` slice writes `{:route-id :rf.route/not-found :params {:url url :reason :validation}}` |

The split is principled (per [§Param validation at the call site](#param-validation-at-the-call-site) above): caller-bug paths throw, event-boundary paths reject with a structured error, URL-driven paths route to the canonical not-found id. A consumer reading "the user tried to reach a route they can't parse" therefore branches differently per source: a caller-bug surfaces as an exception in dev (and as a substrate error in production); an event-boundary failure surfaces via the standard error substrate; a URL-driven failure surfaces via the not-found view's `:reason :validation` branch.

**Asymmetry with flows.** Flows' validation surface is **flat by comparison** — `reg-flow` rejects a malformed flow map with one of **six** explicit error ids that all fire at registration time, all under `:rf.error/flow-*`: `:rf.error/flow-missing-id`, `:rf.error/flow-bad-id`, `:rf.error/flow-bad-inputs`, `:rf.error/flow-bad-output`, `:rf.error/flow-bad-path`, and `:rf.error/flow-bad-marks` (the registration-validation family, owned by [Spec-Schemas §FlowMeta](Spec-Schemas.md) and catalogued in [009 §Error event catalogue](009-Instrumentation.md#error-event-catalogue); reference implementation `implementation/flows/src/re_frame/flows/registry.cljc`). These are distinct from flows' **runtime** error ids (`:rf.error/flow-cycle`, `:rf.error/flow-path-overlap`, `:rf.error/flow-eval-exception`, per [013 §Failure semantics](013-Flows.md#failure-semantics)). Flows have a single validation time for the registration family (registration) and a single surface (registration-throw); routing has three validation times (caller-fn invocation / event-boundary interceptor / URL-driven match) and three surfaces (synchronous throw / structured error / not-found route). The asymmetry is **not a bug** — it is principled per the table above — but it does mean that an AI scanning routing for "validation error ids" does not see one closed family, and a tool building an aggregate "show me all validation failures" surface needs to subscribe to two distinct error ids plus a slice-write predicate. The split is the cost of routing's caller-bug-vs-user-input distinction; flows have no such distinction (registration is always caller code).

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

1. When `:rf.route/handle-url-change` (URL-driven) or `:rf.route/navigate` (programmatic) makes this route the active route, the runtime dispatches each event in `:on-match`, **in order**, after writing the `:rf/route` slice and before any view renders that depend on the loaded data.
2. The runtime sets `:rf.route/transition` to `:loading` while these dispatches drain, and back to `:idle` when they complete (per the run-to-completion drain semantics, this is observable through trace events; see [009](009-Instrumentation.md)).
3. Same-route-id navigations with **changed `:params` or `:query`** *do* re-fire `:on-match` (the route is becoming active again under new inputs). Same-route-id navigations with identical params do not re-fire — the runtime compares the post-update `:rf/route` slice against the pre-update slice and skips dispatch when nothing relevant changed.
4. `:on-match` events run **server- and client-side**. SSR populates server-rendered data via the same vector. Hydration does *not* re-fire `:on-match` events — the seeded `app-db` already contains the data.
5. Each `:on-match` event is an ordinary event vector. Handlers may emit any `:fx` (typically `:http`, etc.). The events are also enumerable: `(rf/handler-meta :route :route/cart)` returns the metadata, so tooling can render route-loading dependency graphs.

The `:on-match` list is the **enumerable, machine-readable** answer to "what loads when this route is active?" `:on-match` is the canonical surface.

### Route `:resources` and named scope resolvers (EP-0016 integration)

The `:resources` route-metadata key (owned by the [Resources artefact](016-Resources.md#route-integration), late-bound through the `:routing/extra-route-keys` / `:routing/on-route-entry` / `:routing/route-blocking?` seam above) carries a vector of route-resource entries; each entry names a `:resource`, its `:params`, an optional `:scope`, and `:blocking?` / `:when` / `:keep-previous?` flags. [EP-0016](../docs/EP/EP-0016-resource-mutation-completion.md) Decision 3 extends the entry `:scope` slot — the only routing-visible change in this action wave:

- a route-resource entry `:scope` MAY be a **named scope resolver reference** `{:from-db <resolver-id>}` (a `reg-resource-scope` resolver, [016 §Resolver references](016-Resources.md#resolver-references--from-db-id)), in addition to the existing concrete value or `(fn [route ctx] …)` route resolver;
- a `{:from-db …}` reference is **resolved at route-entry planning time against the frame db** — the resources route-entry plan (`:routing/on-route-entry`) runs the named resolver before planning the resource's ensure, so route ownership, the blocking slot under the nav-token, and route-leave release all key on the resolved concrete scope;
- resolution is **fail-closed**: a resolver that returns `nil` at a route-resource site is a route/resource **planning error** (`:rf.error/resource-route-plan-failed`, surfaced on the route slice's transition/error state and Xray), **never** a silent substitution of `:rf.scope/global` and never a silent skip. This is the routing-side application of the resources fail-closed scope boundary;
- the route-resource `:params` / `:scope` / `:when` functions remain the one site with a **populated** planning context — `(fn [route ctx] …)` — because route-entry planning has a real route match and planning context to thread (contrast the reserved-nil `ctx` on resource/mutation fns, [016 §The `ctx` argument is reserved](016-Resources.md#the-ctx-argument-is-reserved-across-resourcemutation-fn-surfaces)).

The mechanism is the existing late-bound seam; the design change is purely the resolver-reference `:scope` form and its planning-time, fail-closed resolution. Spec 016 owns the full route-resource plan contract ([016 §Route integration](016-Resources.md#route-integration)); 012 names only this accepted-`:scope`-form extension.

> **`:on-match` events read route params from cofx, not from the event vector.** Each `:on-match` event runs with full access to the frame's state via cofx, including the freshly-written route slice. The route slice is framework-owned **runtime-db** state (per [§The `:rf/route` slice](#the-rfroute-slice)), so a handler that needs params/query reads them from the `:rf.db/runtime` cofx — `(get-in (:rf.db/runtime cofx) [:rf.runtime/routing :current])` — or, more idiomatically, derives them through the public route subs (`:rf.route/params`, `:rf.route/query`). It does NOT read the slice from the app `:db` cofx (the slice does not live in app-db). The event vector carries no param substitution.

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

1. **Trigger.** When `match-url` returns `nil` (no path-pattern matches), or when validation failure routes to "not found" (per [§Param validation at the call site](#param-validation-at-the-call-site)), the runtime sets `:rf/route` to `{:route-id :rf.route/not-found :params {:url <url>} ...}` and proceeds with that route's `:on-match` events.
2. **Same machinery.** `:rf.route/not-found` is an ordinary `reg-route`. It can declare `:on-match`, `:on-error`, `:scroll`, `:head`, `:tags` — all behave normally. The view tree's `case` over `:rf.route/id` renders the not-found view from the leaf.
3. **Required by contract.** Apps **must** register a `:rf.route/not-found` route. If no `:rf.route/not-found` is registered when an unmatched URL arrives, the runtime emits a `:rf.warning/no-not-found-route` trace event and falls back to a built-in placeholder view (a minimal `<h1>Not Found</h1>` page) so the request still produces a response. Test fixtures and the conformance corpus assume the user-registered shape.
4. **Validation failures.** A URL that matches a route's path but fails the route's `:params` / `:query` schema also routes to `:rf.route/not-found`, with `:reason :validation` in the `:params` slice (per [§URL changes are events](#url-changes-are-events)).
5. **Malformed percent-encoding.** A URL carrying malformed `%`-sequences (`%`, `%a`, `%XX`, …) produces a route-miss, **never an exception**. Malformed encoding anywhere in the URL — captured path segments, query keys, query values, or the `#fragment` portion — fails the whole match closed: `match-url` returns nil and `:rf.route/transitioned` writes `:rf.route/not-found` with `{:url url :reason :malformed-url}` in the slice's `:params`. A `:rf.warning/malformed-url` trace fires alongside the standard `:rf.error/no-such-handler`. The `:reason` discriminator distinguishes the malformed-URL case from a bare miss (`{:url url}`) and from a validation failure (`{:url url :reason :validation}`) Hostile URLs, partner integrations with broken escaping, and back-button to a malformed link must never crash a request handler on SSR.
6. **Reserved id.** `:rf.route/not-found` is the **single locked id** for this purpose. Implementations and tools depend on it; users do not redefine the meaning of the keyword. Hosts that want a different visual treatment per error kind branch inside the `:rf.route/not-found` view (e.g., on `:reason`).

Tooling enumerates `(rf/handler-meta :route :rf.route/not-found)` to confirm the route is registered; the registrar emits the warning trace event at the first unmatched URL if it isn't.

## Per-route error handling

If any event in `:on-match` errors (a handler throws, a registered fx errors, or a downstream handler errors during the drain — per [009](009-Instrumentation.md)'s structured error contract), the runtime:

1. Sets `:rf.route/transition` to `:error`.
2. Populates `:rf.route/error` with the structured error map (schema: `:rf/error` per [009](009-Instrumentation.md#error-contract)).
3. If the route declares an `:on-error` event, dispatches it. The error map lives in the route slice (**runtime-db**), so the handler reads it from the `:rf.db/runtime` cofx — `(get-in (:rf.db/runtime cofx) [:rf.runtime/routing :current :error])` — or via the `:rf.route/error` sub. The handler writes its own app data through the ordinary `:db` effect.

```clojure
(rf/reg-route :route/cart
  {:path     "/cart"
   :on-match [[:cart/load-items]]
   :on-error [:route/cart-load-failed]})

(rf/reg-event :route/cart-load-failed
  (fn [{:keys [db] rt :rf.db/runtime} _]
    (let [error (get-in rt [:rf.runtime/routing :current :error])]   ;; route slice is runtime-db
      ;; surface a contextual error UI; toast; redirect; whatever the app needs.
      {:db (assoc-in db [:cart :load-error] (:rf.error/message error))})))   ;; app data → app-db
```

`:on-error` is **route-scoped** error handling, layered over [009](009-Instrumentation.md)'s structured error contract — it doesn't replace it. The structured error trace event still fires; `:on-error` is the route's response to it. Routes without an `:on-error` slot leave `:rf.route/transition :error` set; views may inspect `:rf.route/error` and render an error banner.

### Failure semantics — later `:on-match` events and first-error-wins

A route's `:on-match` is a vector of events dispatched **in order** inside one navigation cascade. The cascade runs inside re-frame2's locked FIFO run-to-completion drain (per [002 §Run-to-completion](002-Frames.md#run-to-completion-dispatch-drain-semantics)), which does **not** cancel events already queued. Two failure-semantics rules follow, and are part of the contract:

1. **Later `:on-match` events still run after an earlier one fails (continuation).** For `:on-match [[:load/a] [:load/b]]` where `:load/a`'s handler throws, `:load/b` **still runs** — the runtime does not abort the remaining loaders. The error is observed by the always-on `:on-match` trap (see [§`:on-match` exception attribution](#on-match-exception-attribution)), which dispatches its `:transition :error` flip to the **back** of the drain queue; the already-queued `:load/b` runs ahead of it. This continuation is the deliberate consequence of the locked FIFO drain: cancelling already-queued events would require a generic front-of-queue / cancellation primitive that re-frame2 does not expose, and a route loader is an ordinary event whose own side effects (its `:db` write, its `:fx`) are governed by run-to-completion like any other. Loaders that must short-circuit on a sibling's failure read `:rf.route/transition` (or the `:rf.route/error` sub) and self-guard — the same idiom the framework's own `settle-transition` uses.

2. **First-error-wins when multiple `:on-match` events throw.** If both `:load/a` and `:load/b` throw in the same transition, the route's `:rf.route/error` and its routing-domain attribution slots ([§`:on-match` exception attribution](#on-match-exception-attribution)) record the **first** attributed failure, and a declared `:on-error` dispatches **exactly once**. The trap drops every later same-`:nav-token` failure once the slice is already `:transition :error`. This matches XState v5's errored-transition semantics (the first error is the recorded one) and keeps `:on-error` from firing per-throw. A genuinely *new* navigation (a fresh `:nav-token`) is not suppressed — its own commit resets the slice off `:error` before its loaders run, so a failure after a recovery still records.

**Final state is always `:error`.** Regardless of queue interleaving (the settle event may run between a failing loader and the trap's error flip), the slice lands on `:transition :error`: `settle-transition` only settles `:loading → :idle` and never clobbers `:error`, and the error flip is the last write for the transition's `:nav-token`. Subscribers see the settled post-drain state (`:error`); the transient `:idle` that settle may write before the error flip is internal to the synchronous drain and is never an observable resting state.

### `:on-match` exception attribution

The error map written to `:rf.route/error` carries two routing-domain attribution slots in addition to the standard [009](009-Instrumentation.md#error-contract) shape:

| Slot | Value | Purpose |
|---|---|---|
| `:rf.route/on-match-id` | the failing event-id | Identifies the `:on-match` event vector whose handler threw. |
| `:rf.route/on-match-frame` | the dispatching frame-id | Identifies the frame whose `:on-match` cascade was mid-drain. |

These slots ride on the structured error so downstream consumers — Xray's event lens, an off-box Sentry/Honeybadger shipper, the SSR error projection per [011](011-SSR.md), an `:on-error` handler — can identify the throw as `:on-match`-attributed **without** re-running the corpus-wide error-emit (the `:errors` stream of `register-listener!`) discrimination logic that the runtime uses to wire the trap. Mirrors the flow-attribution slots `:rf.flow/failed-id` / `:rf.flow/failed-frame` (per [013 §Failure semantics](013-Flows.md#failure-semantics) and). Tools that read `:rf.error/handler-exception` outside the routing listener's discrimination context get the same attribution the trap itself uses.

**How the listener attributes a throw.** The corpus-wide error listener sees every `:rf.error/handler-exception` record and must decide whether the throw belongs to the active (`:loading`) route's `:on-match` drain. The discriminator is **full `:on-match` event-vector identity**: the failing record's `:event` vector must be byte-for-byte one of the route's declared `:on-match` vectors. This is tighter than bare event-**id** membership — a button handler's `[:app/load-x 'from-button]` does not collide with the route's `[:app/load-x 'from-route]`, and a bare `[:app/load-x]` does not collide with the route's `[:app/load-x 42]` — so a non-routing throw whose id merely coincides with an `:on-match` id during the loading window is **not** mis-attributed. The full vector rides the always-on error record (per [009 §Record shape](009-Instrumentation.md)), so the discriminator survives `:advanced` + `goog.DEBUG=false`.

Two edges are part of the contract:

- **Wire-elision fallback.** When [009 §size-elision](009-Instrumentation.md) replaces a large `:event` with the sentinel `:rf.size/large-elided`, the full vector is unavailable. The discriminator then falls back to coarse event-**id** membership — a wider window that can mis-attribute a coincidental same-id throw, which preserves the `:on-error` chain for a genuine large-payload loader throw.
- **Residual identical-vector coincidence (accepted limitation).** A *different* cascade dispatching the byte-for-byte **identical** `:on-match` vector during the loading window remains indistinguishable from the genuine `:on-match` throw on the always-on substrate: the error record carries no cause/cascade correlation, and the epoch cause-event-id surface that would disambiguate it is dev-only and elides in production. This is an accepted limitation, not a bug — the on-match-attribution code's docstring points here for the normative statement.

## Navigation tokens — stale-result suppression

When a route is loading and the user navigates away before the load completes, the older load's result can land *after* the user has moved on, clobbering newer state. This is a real bug class — React Router and TanStack Router both explicitly handle it. Re-frame2's answer is the **navigation-token (nav-token) epoch**: a per-navigation token allocated when a route becomes active, carried by every async result, and validated on receipt. This is the same idiom used by `:after` timers per [005 §Epoch-based stale detection](005-StateMachines.md#epoch-based-stale-detection); see also the cross-cutting [Pattern-StaleDetection.md](Pattern-StaleDetection.md) for why the pattern recurs.

### Mechanism

1. **Allocation.** When a navigation cascade commits the slice — the programmatic `:rf.route/navigate` handler (which writes the slice inline; see [§Navigation is an event](#navigation-is-an-event)), or a URL-change handler `:rf.route/transitioned` (forward nav driven by a `route-link` click via `:rf/url-requested`) or `:rf.route/handle-url-change` (popstate / initial / SSR) — it allocates a fresh `:nav-token` (a gensym or monotonic counter) and writes it to the `:rf/route` slice alongside the new id/params/query/fragment.

   > **The allocation counter is monotone and unbounded.** A nav-token need only be *unique within the lifetime of any in-flight async continuation*; equality against the current slice token is the only operation performed on it (step 4). A per-frame monotonic counter satisfies uniqueness without ever needing to wrap or reset. It does NOT carry the bounded-structure treatment applied to other *retained* collections (the host-side scroll-position LRU cache, the route-registry decoded-key cap): those bound collections that would otherwise accumulate entries, whereas the counter is a single scalar that retains nothing — it is GC'd whole when the frame is destroyed. Practical overflow is a non-concern: on CLJS the counter is an IEEE-754 double (exact integers to 2^53, far beyond any real navigation count); on the JVM it is a `long` that would overflow only after 2^63 navigations. No id collision is possible because each token is a fresh string. Implementations MUST NOT wrap or recycle the counter — doing so would risk colliding a freshly-allocated token with one still carried by a slow in-flight continuation, silently re-validating a stale result.

   > **Storage: the counter is host-side transient, NOT `runtime-db`.** The *active* `:nav-token` is a durable fact on the route slice (`[:rf.runtime/routing :current :nav-token]`) and restores coherently with the rest of the slice. The *allocator* (the `:nav-token-counter` / `:pending-nav-counter` monotonic high-water marks) is held in a host-side per-frame transient cache (a module-private atom, mirroring the scroll-position cache). The two are separated: an epoch restore replaces the `runtime-db` partition **wholesale**, which would rewind a runtime-db-resident counter to its value at the restored epoch — and a pre-restore in-flight async continuation (a request already on the wire, uncancellable) returning afterward could then carry a token the post-restore timeline has *re-allocated*, the exact recycle the rule above forbids. Held host-side, the counter is untouched by restore, so every post-restore allocation strictly exceeds any pre-restore in-flight token and a collision is structurally impossible. Conflating the allocator (whose property is *never rewind*) with the active token (which *should* restore) in one restorable partition is a category error.

   > **The minted id is a RECORDABLE allocation coeffect — replay re-presents it verbatim.** The handlers stay pure, but the id they publish must be **recorded on the causal token** so record→replay reproduces the *same* id. (If the id were re-minted from the ambient counter on replay, recorded events referencing the original would mismatch: a recorded `[:rf.route/continue "pn-1"]` would no-op against a re-minted `"pn-2"` and the navigation would stay blocked; an async continuation carrying a recorded `:nav-token` would flip the stale-suppression gate against a re-minted current token.) So the id rides one of two **recordable, generator-backed** allocation coeffects (one per allocator — they are *two distinct facts* because they are two distinct allocators):
   >
   > | coeffect | shape | minted by |
   > |---|---|---|
   > | `:rf.route/nav-allocation` | `{:token "nav-N" :counter N}` | the nav commit handlers (`:rf.route/navigate`, `:rf.route/transitioned`, `:rf.route/handle-url-change`) |
   > | `:rf.route/pending-nav-allocation` | `{:id "pn-N" :counter N}` | the leave-guard block (`:rf/url-requested` + the same commit handlers) |
   >
   > Each generator mints the next id from the host high-water snapshot at processing-start (router `:live` policy); the cofx machinery **records the produced allocation onto the causal token** (per [001 §`reg-cofx`](001-Registration.md) recordable grade / EP-0017 §5) so the epoch captures it. Strict replay re-presents the recorded id verbatim — the generator does not run — and **FAILS LOUDLY (`:rf.error/missing-required-cofx`) if the recorded allocation is absent** (an incomplete record must not silently re-read the host). The handler writes only the `:token` / `:id` into `runtime-db` and rides the allocation's `:counter` on the `:rf.route/commit-nav-counter` fx, which advances the host high-water with **`max`** — so a restore/replay re-establishes the allocator from the recorded `:counter` and can never rewind it. This is the same write-via-fx seam the scroll cache uses; the read half is the recordable allocation cofx rather than an ambient snapshot read.
2. **Capture.** An `:on-match`-reached handler declares the framework-supplied `:rf.route/nav-token` cofx via `{:rf.cofx/requires [:rf.route/nav-token]}`; the value-returning supplier delivers the current token (read from `[:rf.runtime/routing :current :nav-token]`) flat under `:rf.route/nav-token` in the handler's coeffects, so the handler captures the epoch live at scheduling time. A loader SHOULD also declare the companion `:rf.route/route-id` cofx (the live route id, read from `[:rf.runtime/routing :current :route-id]`) so it captures **both** facts the route-loader [work id](Managed-Effects.md#work-id-correlation) `[:rf.work/route route-id nav-token loader-id]` needs together — the documented path then cannot thread a nil route id into the work-id tuple (the route id is a *carried* fact of the attempt, captured at scheduling time, never read from the live slice at stale-arrival where a cross-route completion's slice id would be the superseding route's):

   ```clojure
   (rf/reg-event :cart/load-items
     {:rf.cofx/requires [:rf.route/nav-token :rf.route/route-id]}    ;; <-- declare BOTH cofx
     (fn [{:keys [db] :rf.route/keys [nav-token route-id]} _]        ;; <-- "nav-42" + :route/cart, live at scheduling time
       ...))
   ```

3. **Threading.** Async completions either (a) carry the captured facts in their follow-up event payload, or (b) use the framework-supplied `:rf.route/with-nav-token` fx wrapper, which names the continuation by the canonical `:rf/reply-to` reply target (the [uniform reply envelope](Managed-Effects.md#the-uniform-reply-envelope) lowering — on match the route loader's `:status :ok` reply map is appended to the target via the shared `re-frame.reply/complete`; on a framework/tool-authorised `:dispatch-stale?` target the stale reply is delivered the same way) and threads the captured token + route id for the gate and the work-id:

   ```clojure
   {:fx [[:rf.route/with-nav-token
          {:rf/reply-to [:cart/items-loaded]    ;; the reply map is appended as the final arg
           :value       items                   ;; the loader's decoded result (rides :status :ok)
           :nav-token   nav-token               ;; the token captured in step 2
           :route-id    route-id}]]}            ;; the route id captured in step 2 (completes the work-id)
   ```

   `:rf/reply-to` is the single, required continuation surface on `:rf.route/with-nav-token` — every match lowers through the uniform reply envelope.

4. **Validation.** On receipt, the carried token is checked against the *current* `:rf/route` slice's `:nav-token`. Path (b) — `:rf.route/with-nav-token` — performs this check for you; path (a) hands the captured token to the receiving handler, which compares it against its own freshly-declared `:rf.route/nav-token` coeffect and short-circuits on mismatch:
   - **Match.** The token is current; the result is committed normally (the `:rf/reply-to` target is completed with the `:status :ok` reply map).
   - **Mismatch.** The token has been superseded; the runtime emits `:rf.route.nav-token/stale-suppressed` (with `:tags {:carried-token <t1> :current-token <t2> :rf.trace/event-id <id>}`) and the `:rf/reply-to` target (path b) or the receiving handler's commit (path a) does NOT run — no `:db` write, no `:fx`, no transition.

The two halves are shared infrastructure: the `:rf.route/nav-token` cofx (and its companion `:rf.route/route-id` cofx) supplies the capture-side facts to any handler that declares `{:rf.cofx/requires [:rf.route/nav-token :rf.route/route-id]}`, and `:rf.route/with-nav-token` performs the receipt-side check for any continuation routed through it. A handler can use both (declare to capture, wrap to validate) or compare the declared `:rf.route/nav-token` coeffect directly.

### What the slice looks like over time

```clojure
;; All slice snapshots below are at [:rf.runtime/routing :current] in runtime-db.
;;
;; Step 1: User navigates to :route/article id="A". nav-token = "nav-1".
{:route-id :route/article :params {:id "A"} :transition :loading :nav-token "nav-1"}

;; Step 2: While the load is in flight, user navigates to :route/article id="B".
;; A fresh nav-token is allocated.
{:route-id :route/article :params {:id "B"} :transition :loading :nav-token "nav-2"}

;; Step 3: The "A" load completes; its dispatched [:article/loaded "A" payload] carries
;; nav-token "nav-1". Current is "nav-2". Mismatch → suppressed; trace fires; no commit.

;; Step 4: The "B" load completes; carries "nav-2". Match → commit.
{:route-id :route/article :params {:id "B"} :transition :idle :nav-token "nav-2"}
```

### Cancellation as optimisation, not correctness

Suppression alone fixes the user-visible bug — the older load *does* complete and *does* dispatch its event, but its result is silently discarded at the validation cofx. Hosts that support abortable fetches (`AbortController` in JS, etc.) MAY *additionally* abort in-flight work for superseded tokens to save bandwidth — but the conformance contract only requires suppression, not cancellation. This matches the `:after` story per [005 §Epoch-based stale detection](005-StateMachines.md#epoch-based-stale-detection).

### Trace events

Two trace events surround the nav-token lifecycle (added to the trace-op vocabulary per [Spec-Schemas.md](Spec-Schemas.md#rftrace-event)):

- **`:rf.route.nav-token/allocated`** — emitted when a navigation cascade allocates a fresh token. `:tags {:route-id <id> :nav-token <token> :frame <navigating-frame>}` (the `:frame` — see the frame-attribution note under [§Trace events](#trace-events)).
- **`:rf.route.nav-token/stale-suppressed`** — emitted when an async result arrives carrying a now-superseded token. `:tags {:carried-token <t1> :current-token <t2> :rf.trace/event-id <id> :work/id <route-work-id>}`. The handler does NOT run. The `:work/id` tag is the route-loader work-id `[:rf.work/route route-id nav-token loader-id]`, joining the suppression to the superseded attempt's identity (see [§Lowering onto the uniform reply envelope](#lowering-onto-the-uniform-reply-envelope) below).

Naming follows the `<feature>/<reason>` convention used by `:rf.machine.timer/stale-after`. See [Pattern-StaleDetection.md](Pattern-StaleDetection.md) for the cross-cutting pattern.

### Conformance

Fixture `route-stale-nav-token-suppression.edn` exercises the canonical race: load route A; navigate to route B before A finishes; A finishes; verify the late result is suppressed and the trace shows `:rf.route.nav-token/stale-suppressed`.

### Lowering onto the uniform reply envelope

Route-loader async work is one of the managed *async* surfaces that complete through the framework-wide [uniform reply envelope](Managed-Effects.md#the-uniform-reply-envelope) (property 9 of the [managed-effect contract](Managed-Effects.md#the-nine-properties); [EP-0011](../docs/EP/EP-0011-uniform-async-reply-envelope.md) is the rationale record). The nav-token mechanism above is **not** a bespoke per-family stale-detection scheme: it is exactly one instance of the envelope's mandatory [stale suppression](Managed-Effects.md#stale-suppression) — the same correctness boundary HTTP ([014](014-HTTPRequests.md)), resources and mutations ([016](016-Resources.md)), and machine async work ([005](005-StateMachines.md)) lower onto. The public routing surface — the `:rf.route/nav-token` cofx, the `:rf.route/with-nav-token` fx, and `:on-match` loaders — is **preserved verbatim**; the lowering is internal.

The two correlations the envelope requires:

- **Work-id correlation.** A route loader's [work id](Managed-Effects.md#work-id-correlation) is `[:rf.work/route route-id nav-token loader-id]`. One attempt has one work id ([EP-0007](../docs/EP/EP-0007-one-name-per-fact.md)): a fresh navigation epoch (a new `:nav-token`) is a distinct attempt, so the nav-token rides *in* the work-id tuple. It is the **same fact named once** — the component that discriminates a superseded attempt *and* the value of the sole suppression gate (next bullet); it is never a second stale-suppression key alongside the work id. The `route-id` and `nav-token` are both **carried facts of the attempt**, captured at scheduling time via the framework `:rf.route/route-id` + `:rf.route/nav-token` cofx (step 2 above) and threaded into the continuation — *not* read from the live route slice at completion, where a cross-route stale arrival's slice id would be the superseding route's. Declaring both cofx is what keeps the documented path from emitting a nil-route route work-id.
- **The nav-token is the suppression gate.** The [stale-suppression](Managed-Effects.md#stale-suppression) gate is the data-only map `{:route/nav-token <captured-token>}`, validated against the live `{:route/nav-token <current>}` read from `[:rf.runtime/routing :current :nav-token]`. The validation step 4 above (the `:rf.route/with-nav-token` check, or a handler comparing the declared `:rf.route/nav-token` coeffect) is exactly this gate comparison — `carried` vs `current` by value equality. On match the **live** reply (`:ok` / `:error`, and `:cancelled` for an explicit user cancel) flows through to the app handler unchanged; on mismatch the completion is suppressed *before* the handler runs — its outcome is `:status :stale` with no app-db / runtime-db mutation, and the `:rf.route.nav-token/stale-suppressed` trace fires joined to the route `:work/id` (the carried + current gates ride in the trace facts). [§Cancellation as optimisation, not correctness](#cancellation-as-optimisation-not-correctness) above is the envelope's [cancellation-vs-suppression](Managed-Effects.md#cancellation) line: suppression is the correctness boundary; aborting the in-flight fetch is the optional optimisation.

A route loader that fetches through managed HTTP (the common case — an `:on-match` handler emitting `:rf.http/managed`) therefore carries **two** correlated identities: the HTTP attempt's `[:rf.work/http …]` work id (014's gate is request-id + generation) and the route's `[:rf.work/route …]` nav-token gate. Both gates are checked independently before the app reply commits — the HTTP reply is delivered only if its own request is still current *and* the wrapping `:rf.route/with-nav-token` finds the nav-token current. This composition is the worked example in [EP-0011 §Route Loader Completion](../docs/EP/EP-0011-uniform-async-reply-envelope.md#route-loader-completion).

## Standard runtime events

Three named events are part of the routing contract. Implementations register a handler for each; user code dispatches them; tests can fire them directly. Users can override any of them by re-registering.

| Event | When it fires | Default behaviour |
|---|---|---|
| `:rf.route/transitioned` | Forward navigation from a `route-link` click: dispatched by `:rf/url-requested` after the `:can-leave` gate passes and the in-app URL is pushed onto history. (Programmatic `:rf.route/navigate` does **not** route through this event — it commits the slice inline; see [§Navigation is an event](#navigation-is-an-event).) | Rewrites the `:rf/route` slice from the URL (match → validate → fragment-only short-circuit → full rewrite + `:on-match` drain). Default scroll strategy `:top`. |
| `:rf.route/handle-url-change` | Popstate (Back/Forward), initial page load, and the server-side request URL during SSR. | The **same** slice-rewrite logic as `:rf.route/transitioned` — they are co-equal sibling handlers, not a delegate pair. Differs only in the default scroll strategy `:restore` (the saved position for the URL being returned to) and in threading the `:frame` id so SSR error projections can attribute a `:no-such-handler` trace per-frame. |
| `:rf/url-requested` | The user clicked a link the framework owns (a `route-link` view, or any `<a>` whose `href` resolved to a registered route). The handler decides whether the request is in-app or external, runs the active route's `:can-leave` guard for an in-app request, and on a clear request pushes the URL and synthesises the transition. | Classifies in-app vs external by **origin comparison** — on the client the URL is resolved against the browser `Location` and its origin compared; on the JVM / SSR / no-`window` path it falls back to a fail-**closed** lexical check (only a provably same-origin rooted path / pure query / pure fragment is in-app — see [§Open-redirect fail-closed classification](#open-redirect-fail-closed-classification)). An **external** request emits a `:rf.route/external-url-requested` trace and does nothing else (no push — the browser follows the link). An **in-app** request runs the current route's `:can-leave` guard (blocking via the pending-nav protocol if rejected), then pushes the in-app URL and dispatches `:rf.route/transitioned`. Users can override to enforce per-frame policy (auth-guard, modifier-key handling, etc.). |

`:rf/url-requested` is the **decision point** for navigation policy. The policy is enumerable and testable: dispatch `[:rf/url-requested {:url "/cart"}]` from a test and observe the result — an in-app request pushes the URL and dispatches `:rf.route/transitioned`; an external request emits a `:rf.route/external-url-requested` trace and pushes nothing — no DOM simulation required.

#### Open-redirect fail-closed classification

The in-app-vs-external decision is the open-redirect defense: a `:rf/url-requested` request whose URL is **not** provably same-origin must not be pushed into history as if it were an in-app route. The same classifier gates the `:rf.route/navigate` `{:url ...}` programmatic escape hatch, so both URL-string nav sinks fail closed through **one** decision rather than each entry point deciding independently.

Classification is **origin-based**, not match-based — a same-host URL that happens not to match any registered route is still in-app (it routes to `:rf.route/not-found`), and an absolute / protocol-relative / scheme-bearing URL is external regardless of whether it would match:

- **Client (browser `Location` available).** The URL is resolved against the document origin (`new URL(url, location.href)`) and classed external when its protocol is not `http(s):` or its origin differs from the document's.
- **JVM / SSR / no-`window` (and any client resolution failure).** There is no browser `Location` to origin-compare against, so the runtime cannot *prove* same-origin — it falls back to a **fail-closed** lexical check. A URL is in-app only when, after rejecting any embedded whitespace or ASCII control character (which browsers strip mid-URL before parsing), it is a single-leading-`/` rooted path **not** followed by `/` or `\` (a protocol-relative authority a browser reads as off-origin), or a pure `?query` / `#fragment` reference. A leading `//`, a `/\`, a scheme (`name:`), a bare relative segment, and the empty string all class **external**. This is a deliberate client/server asymmetry: the client proves same-origin against the live origin; the server defaults to deny.

An external classification emits `:rf.route/external-url-requested` (carrying `:tags {:url <url> :frame <navigating-frame>}` — the `:frame` enters the navigating frame's epoch trace-events and obeys the frame trace-disable gate, symmetric across the link-click `:rf/url-requested` and programmatic `:rf.route/navigate {:url …}` paths) and performs no push; an in-app classification normalises the URL to its origin-relative form and continues into the `:can-leave` / push / `:rf.route/transitioned` path above.

`route-link` ships in the routing artefact as a registered view at id `:route/link`. The body:

```clojure
(rf/reg-view ^{:rf/id :route/link} route-link
  [{:keys [to params query fragment on-click] :as props} & children]
  (let [base-url (rf/route-url to (or params {}) (or query {}))
        url      (if (and fragment (not= "" fragment))
                   (str base-url "#" fragment)
                   base-url)
        attrs    (-> props
                     (dissoc :to :params :query :fragment :on-click)
                     (assoc :href url
                            :on-click
                            (fn [e]
                              (when on-click (on-click e))
                              (when (and (not (.-defaultPrevented e))
                                         (plain-left-click? e))   ;; no modifier keys; primary button
                                (.preventDefault e)
                                (dispatch [:rf/url-requested
                                           (cond-> {:url url :to to}
                                             (seq params) (assoc :params params)
                                             (seq query)  (assoc :query query)
                                             fragment     (assoc :fragment fragment))])))))]
    (into [:a attrs] children)))
```

The view exposes three behavioural seams: passthrough attributes (`:class`, `:title`, `:id`, `:aria-label`, …) flow through to the `<a>`; a caller-supplied `:on-click` runs before the framework's interception and can pre-empt it by calling `.preventDefault`; and modifier-key clicks defer to the browser so middle-click / cmd-click / shift-click keep their native open-in-new-tab affordances. `route-url` is the single point where the URL is synthesised, so route-rename and route-shape changes flow into every `route-link` site without per-link edits.

**Native-anchor attributes are never intercepted.** A passthrough attribute whose semantics require the browser to handle the click — a `target` other than `_self` (`_blank` / `_parent` / `_top` / a named frame), or `download` — defers to the browser even on a plain left-click, the same as a modifier-key click. SPA interception would convert a `{:target "_blank"}` link into same-document navigation and a `{:download …}` link into a no-op navigation, silently breaking the native anchor contract the DOM attributes advertise. The framework treats these attributes as a fourth defer-to-browser seam alongside modifier/middle clicks and caller `.preventDefault`: the link still renders as a real `<a href=…>` with the attributes, and the click is left for the browser. Authors who want SPA interception omit those attributes (or set `:target "_self"`).

## Scroll restoration

Browser-default behaviour on `popstate` restores scroll position. For SPA-controlled scroll (e.g., scroll to top on forward navigation, restore on back), declare a `:scroll` strategy on the route or pass `:scroll` in the `:rf.route/navigate` opts.

The `:scroll` value is one of:

| Value | Behaviour |
|---|---|
| `:top` | Scroll to top of page (`window.scrollTo(0,0)`) — **unless a `#fragment` is present**, in which case `:top` scrolls the fragment's element into view (falling back to top if the element is absent); see [§`:rf.nav/scroll` integration](#rfnavscroll-integration). |
| `:restore` | Restore the saved scroll position for this URL (the runtime captures positions on every navigation; SSR-side: no-op). |
| `:preserve` | Do nothing (current scroll position stays as is). |
| `nil` / absent | Same as `:preserve`. |
| map | Hosts may supply additional shapes (e.g. `{:to :element :selector "#article"}`); see "Custom scroll strategies" below. |

Resolution order at navigation time:
1. `:scroll` key in `:rf.route/navigate`'s `opts` map (per-call override). Wins.
2. `:scroll` key on the route's metadata.
3. Implicit default: `:top` for forward navigation, `:restore` for popstate-driven navigation.

When a `:rf.nav/scroll` effect is emitted, its args carry both the strategy and the from/to context: `[:rf.nav/scroll {:strategy :top :from from-route :to to-route :saved-pos saved :fragment <s-or-nil>}]`. The registered fx interprets the strategy. The `:saved-pos` field is captured by the runtime on every navigation (a small in-memory map URL → `[x y]`); on `popstate`, the runtime supplies the saved value. The `:fragment` field is the URL's `#fragment`, when present (per "Fragments" below); the standard strategies use it as described in "Fragments §`:rf.nav/scroll` integration".

> **Storage — host-side transient cache (not `runtime-db`).** The saved-position map (URL → `[x y]`, LRU-capped per frame) is a **host-side transient cache**, held in a module-private per-frame atom owned by the routing artefact — it is **not** `runtime-db` state and does not sit under `[:rf.runtime/routing …]`. Scroll positions are host-derived (read from `window.scrollX/Y`), bounded LRU caches, meaningless on the server, and not needed to reconstitute a coherent frame-state on restore / SSR-hydration / time-travel — i.e. **transient**, per [002 §Durable vs transient](002-Frames.md). Holding them off `runtime-db` keeps them out of epoch/snapshot capture and off the trace / AI-MCP egress wire by storage location, not by an egress filter. The `:rf.nav/capture-scroll` effect writes this cache (keyed by the leaving route's URL); the nav planner reads the active frame's cache to fill `:saved-pos` on a `:restore`. The cache is released per frame on `destroy-frame!` via the `:routing/on-frame-destroyed!` hook (per [§Frame-destroy teardown](#frame-destroy-teardown)).

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
| In route slice (runtime-db; read via `:rf.db/runtime` cofx or route subs) | `(get-in rt [:rf.runtime/routing :current :params])` | `(get-in rt [:rf.runtime/routing :current :query])` |
| Required by URL? | Yes (URL doesn't match without them) | No (every key is optional from the URL's perspective) |
| Defaults | n/a (absence = no match) | `:query-defaults` map |

`:query-defaults` populates absent query keys at match time. `:query-retain` is a set of keys that should be **carried through subsequent navigations** even when the caller didn't supply them — useful for global state encoded in the URL (`:theme`, `:locale`, `:debug`). The merge is performed inside `:rf.route/navigate`'s handler (which reads the current `:rf.route/query` slice from the runtime-db route slice via the `:rf.db/runtime` cofx) before `route-url` is called; `route-url` itself is pure and does not consult frame-state (per [§Bidirectional URL ↔ params](#bidirectional-url--params)). The result: a `[:rf.route/navigate :route/cart]` from a search page preserves `?theme=dark`.

#### `:query-retain` cross-route coercion class

Retained query values are pulled from the **current** route's `:query` slice and merged into the **target** route's outgoing query **verbatim** — they are *not* re-coerced against the target route's `:query` schema. This matters because the current route may have coerced a retained value into a specific class: a `[:enum :light :dark]`-typed key arrives in the slice as the **keyword** `:dark` (per [§Keyword-interning cap](#keyword-interning-cap-on-query-keys--values)), an `:int`-typed key as a **number**. Carrying verbatim means that keyword/number flows into the target unchanged.

**The contract: keep a retain key's type consistent across every route that retains it.** Because the merge is a pure `select-keys` (no re-coercion), an author who types `:theme` as `[:enum :light :dark]` on route A and as `:string` on route B will carry a *keyword* `:dark` into B's `:string` slot. This is **caught, not silent**: the target route's `:query` validator runs at the call site (in `:rf.route/navigate`'s handler and in `route-url`), so a class mismatch surfaces as a validation failure (rejecting the navigation per [§Param validation at the call site](#param-validation-at-the-call-site)) rather than desyncing the slice.

Re-coercing retained values against the target schema was considered and rejected: retained values are already-coerced **runtime values**, not URL strings, so re-running the string→class coercion pipeline on them is ill-typed (the coercer's input contract is a raw URL string). Verbatim carry keeps the retain-merge a pure `select-keys` and keeps `route-url` pure (it never sees the merge). Consistency across routes is an author-named-intent invariant, the same trust class as the `:query` schema itself.

Coercion is data-shaped (the `:query` schema is the coercion specification — `:int` coerces `"2"` → `2`); per-key middleware functions are not part of the contract — data over functions.

`:int` coercion is **strict and host-identical**. A value is coerced to a number only when the *whole* string is an integer literal — `#"^-?\d+$"` (an optional sign followed by ASCII digits); partial-numeric, radix-prefixed, or whitespace-padded input (`"12abc"`, `"0x10"`, `" 12"`) is **left as a string on every host**. The route's `:query` schema then catches the type mismatch — a string in an `:int`-typed slot fails validation and surfaces `:validation-failed? true` (per [Spec 010](010-Schemas.md)), identically server- and client-side. This rule exists because `match-url` runs on both the JVM (SSR) and CLJS (browser); a host-divergent integer parser (`Long/parseLong` is strict, `js/parseInt` is lenient) would yield a different `:query` slice for the same URL on each host — exactly the [Spec 011](011-SSR.md) hydration-mismatch class, and a violation of the "same handler runs server- and client-side" contract and [Goal 2's cross-host conformance bar](000-Vision.md#ai-implementable-from-the-spec-alone). The strict regex makes the parse decision a pure function of the input string, host-independent. The conformance corpus pins `?page=12abc` and `?page=12` across both harnesses in [`routing-query-string-coercion.edn`](conformance/fixtures/routing-query-string-coercion.edn).

### `+` is a literal

`+` decodes to a **literal `+`** — not a space — on **both** the JVM (SSR) and CLJS (browser), in path captures *and* query values. `%20` (and a real space) decodes to a space on both hosts. This is the same cross-host-symmetry rule as `:int` coercion above, applied to percent-decoding: `match-url` runs on both hosts, so a host-divergent decoder would yield a different `:params` / `:query` slice for the same URL on each host — the [Spec 011](011-SSR.md) hydration-mismatch class again.

The rationale is threefold:

1. **`decodeURIComponent` is the de-facto reference.** The CLJS path decodes via `js/decodeURIComponent`, which leaves `+` untouched. The browser is the canonical host, so the JVM matches it (the JVM's `java.net.URLDecoder/decode` is the `application/x-www-form-urlencoded` decoder, which turns a bare `+` into a space — the wrong decoder here; the implementation pre-escapes `+` → `%2B` before handing the string to `URLDecoder` so the JVM reproduces `decodeURIComponent` exactly).
2. **RFC 3986 path semantics.** In a URL *path* segment, `+` is a literal — the `+`-means-space convention is specific to `application/x-www-form-urlencoded` form bodies and query strings of HTML form `GET` submissions, not to path captures. re-frame2 applies one rule to both path and query for a single, predictable decode contract.
3. **re-frame2 never emits a bare `+`.** `route-url` encodes a literal `+` (and a space) as `%2B` (and `%20`) via `url-encode`, so the round-trip (`route-url` → `match-url`) is exact and never depends on the `+`-as-space reading. A bare `+` only ever appears in a URL that re-frame2 did not author (a hand-typed or partner-supplied link), where the literal reading is the conservative, host-stable choice.

A `match-url` query string also **skips empty pairs**: a trailing `?` (`/x?`), a leading `&` (`/x?&a=1`), or a doubled `&&` (`/x?a=1&&b=2`) does **not** inject a spurious `{"" ""}` key into the `:query` slice. An explicit empty *value* (`?foo=`) is distinct — it keeps the key with an empty-string value (`{"foo" ""}`).

Three further parse edges are pinned so two hosts cannot diverge on a real-world URL shape (the same cross-host determinism the `:int` and `+` rules enforce):

1. **Duplicate keys — last occurrence wins.** `?a=1&a=2` parses to `{"a" "2"}`; the later pair overwrites the earlier. (First-wins would serve equally — the point is *one* pinned answer; last-wins is what a left-to-right `assoc` fold produces and is host-stable. Collecting duplicates into a vector is **rejected**: the slice value's class would then depend on the URL shape, breaking `:query`-schema coercion.)
2. **A bare key is present-empty.** `?flag` (a key with no `=`) parses to `{"flag" ""}` — the same present-but-empty shape as `?flag=`. This composes with the empty-*value* rule above: "present, no value" is always `""`, never a `nil`/absent key or a `true`/boolean.
3. **Split on the first `=` only.** `?next=/a=b` parses to `{"next" "/a=b"}`; the first `=` separates key from value, and any later `=` are literal value characters (no `?next` → `{"next" "/a"}` truncation). This matters for URLs that carry an encoded return-path or token as a value.

The conformance corpus pins `+`-literal (path capture + query value), the `%20`-is-space case, and the empty-pair filter across both harnesses in [`routing-plus-decode.edn`](conformance/fixtures/routing-plus-decode.edn); the three edges above are pinned in [`routing-query-parse-edges.edn`](conformance/fixtures/routing-query-parse-edges.edn).

### Keyword-interning cap on query keys + values

URL query strings are an attacker-influenceable input — caller-controlled, often deep-linked from third parties (search results, partner sites, share links). JVM keywords intern into a process-global, never-GC'd table; a routing layer that turns every URL query key into a keyword permanently extends that table on every unique hostile key, eventually exhausting the host. Long-running SSR JVMs are the worst case. This is the routing-side analogue of the HTTP-side keyword-interning DoS (per [Spec 014 §Keyword-interning cap](014-HTTPRequests.md#keyword-interning-cap)) — but **the routing side closes it at the source by selective keywording, so no URL-level key cap is needed**. The route's declared `:query` / `:query-defaults` / `:query-retain` vocabulary **is** the keyword universe; a hostile URL of N-unique *undeclared* keys interns **zero** keywords (each undeclared key passes through as a string). The HTTP side genuinely keywordizes partner/webhook JSON object keys against a schema, so it carries a real cap; routing's string-passthrough already provides what such a cap would offer, so the two are *not* symmetric and routing has no `route-too-many-keys` error.

Two layered defenses, both on by default:

1. **Selective keywording against the route's declared vocabulary.** When the route declares a `:query` schema (or `:query-defaults` / `:query-retain`), only keys named by those slots are promoted to keyword keys. Unknown URL query keys retain their **string** form in the parsed `:query` map. The route's declared vocabulary is the keyword universe; the framework refuses to permanently extend the JVM keyword table on behalf of URL keys the route did not name. **This is the keyword-interning DoS closure** — a hostile URL composed of N-unique undeclared keys interns nothing, so the raw query map (a transient, GC'd value) needs no size cap.

2. **`:keyword`-typed value gate.** A bare `:keyword` query-slot type-form is treated as an **unbounded** intern site (any URL value would intern as a keyword) and the value is preserved as a **string**. Authors who want keyword-typed values declare an `[:enum :asc :desc ...]` allowlist — the bounded keyword universe. Values matching one of the declared enum choices are interned; values outside the allowlist stay as strings.

Routes that declare **no** `:query` vocabulary at all (no `:query` schema, `:query-defaults`, or `:query-retain`) keep **every** URL query key as a **string** — the keyword-all fallback was cut, so a bare `(reg-route :route/x {} "/x")` interns nothing on behalf of the URL, regardless of how many unique keys the URL carries. The selective-keywording rule (defense #1) and the `:keyword`-value gate (defense #2) promote **only** declared keys/values to keywords. (Defense #1 is the key-side mirror of defense #2: author-named intent is the trust boundary for promoting an attacker-influenceable URL key into the process-global keyword table.) A general input-*size* policy (byte/pair/request-boundary limits) is intentionally out of scope here; if one is ever wanted it would be designed separately, not as a keyword-interning cap.

```clojure
;; safe enum allowlist for a keyword-typed query value.
(rf/reg-route :route/sorted
  {:path  "/items"
   :query [:map
           [:sort [:enum :asc :desc]]]})

;; URL: /items?sort=desc       → :query {:sort :desc}     ;; declared enum value → interned
;; URL: /items?sort=hostile    → :query {:sort "hostile"} ;; outside enum → stays as string
```

Cross-references: [Security.md §DoS by input](Security.md#dos-by-input) for the framework-wide stance (slot catalogue cross-ref TBD post-Security.md anchor stabilisation), and [014 §Keyword-interning cap](014-HTTPRequests.md#keyword-interning-cap) for the symmetric HTTP-side cap.

## Fragments

The URL `#fragment` is a first-class part of the routing contract — anchor navigation, scroll-to-section, settings-tab selection, and SSR-safe in-page navigation all depend on it being explicit data flowing through events rather than a `window.location.hash` read in view code.

### Fragment in the slice

The route slice (`[:rf.runtime/routing :current]`) carries `:fragment` (string or `nil`):

```clojure
;; at [:rf.runtime/routing :current]:
{:route-id :route/docs
 :params   {:page "routing"}
 :query    {}
 :fragment "scroll-restoration"
 ...}
```

Read it via the `:rf.route/fragment` sub. Fragment is **populated by `match-url` from the URL**, written to the slice by the URL-change handlers (`:rf.route/transitioned` / `:rf.route/handle-url-change`), and emitted by `route-url` when the 4-arity form is used (or when `:rf.route/navigate` is called with a `:fragment` opt or target-map key).

### Fragment-only changes do NOT re-fire `:on-match`

When the new URL differs from the current URL **only** in its fragment (same `:route-id`, same `:params`, same `:query`, but different `:fragment`), the runtime:

1. Updates `:fragment` in the `:rf/route` slice.
2. Emits a `:rf.route/fragment-changed` trace event with `:tags {:route-id <id> :prev-fragment <s> :next-fragment <s>}`.
3. Does **NOT** allocate a new `:nav-token`.
4. Does **NOT** re-fire `:on-match`.

The reason: `:on-match` exists to re-load route-scoped data when path or query changes. A fragment-only change does not change loaded data — only the in-page anchor target. Re-firing the loaders would re-fetch unchanged data on every `#section` jump, which is exactly the kind of thrash users complain about.

Views that need to react to fragment changes subscribe to `:rf.route/fragment` (or to `:rf/route` for the whole slice). Whichever URL-change event fires — `:rf.route/transitioned` on forward nav, `:rf.route/handle-url-change` on popstate — the shared slice-rewrite logic distinguishes a fragment-only change from a full transition, so both honour the no-re-fire rule above.

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

Either form ends up in the `:rf/route` slice's `:fragment`.

### SSR

Browsers do **not** send `#fragment` to the server — `window.location.hash` is client-only. For browser-initiated SSR requests, the server-side `:fragment` is therefore typically `nil`, regardless of what the user typed in the address bar. The exceptions are static-site generators, server-side test harnesses, and crawlers that synthesise URLs with explicit fragments (e.g., for anchored documentation pages); when the host's request abstraction exposes a `#fragment`, SSR includes it in the seeded `:rf/route` slice. See [011 §Fragments under SSR](011-SSR.md#fragments-under-ssr) for the full SSR-side contract.

The server does NOT scroll (no DOM); `:rf.nav/scroll` is `:platforms #{:client}` per [011 §Effect handling on the server](011-SSR.md#effect-handling-on-the-server). The first client render after hydration sees the same `:fragment` value the server seeded (typically `nil` for browser requests), so view code that reads `:rf.route/fragment` produces structurally-identical output on both sides. A subsequent `:rf.nav/scroll` (post-hydrate) is the host's choice — the contract leaves it to the host to decide whether to perform the initial scroll-to-fragment after hydration.

### Conformance

Fixture `route-fragment-change.edn` exercises:
1. Navigate to `/docs/routing#scroll-restoration`. Verify the slice's `:fragment` is `"scroll-restoration"`.
2. Navigate to `/docs/routing#caching` (same path/query, different fragment). Verify `:on-match` does NOT re-fire and `:rf.route/fragment-changed` trace event fires.
3. Navigate to `/docs/instrumentation#scroll-restoration` (different path, same fragment). Verify `:on-match` DOES re-fire (path changed; fragment-only rule does not apply).

## Nested layouts

For nested layouts (e.g., `/account/settings`, `/account/billing`, `/account/security` all rendering inside an `/account` shell), the pattern is **id namespacing plus an explicit `:parent`**:

```clojure
(rf/reg-route :route/account            {}                            "/account")
(rf/reg-route :route/account.settings   {:parent :route/account}      "/account/settings")
(rf/reg-route :route/account.billing    {:parent :route/account}      "/account/billing")
(rf/reg-route :route/account.security   {:parent :route/account}      "/account/security")
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
- Parent-route `:resources`/`:on-match` that cascade to children (today, child routes must duplicate the parent's data plan if they need the same reads — see [§Open questions → Native nested layouts](#native-nested-layouts-post-v1) for the flagship duplication the deferral now records).
- An `<Outlet/>`-equivalent primitive for the child render slot.

The `:parent` + chain-sub convention is sufficient for the common case and doesn't preclude a richer mechanism later.

## Navigation blocking — pending-nav protocol

Real product needs — unsaved forms, interrupted checkouts, destructive multi-step workflows, **auth gates** — require navigation to be *blockable*, at both ends of a transition: the route you are **leaving** (`:can-leave` — "you have unsaved changes") and the route you are **entering** (`:can-enter` — "you must be signed in"). Angular, Vue Router, and TanStack Router all support both. Re-frame2 makes navigation blocking a **first-class named-event/state protocol** instead of a magic component hook: pending-nav state lives in `runtime-db`; UI renders confirm dialogs (or redirects) from ordinary subscriptions (the framework `:rf/pending-navigation` sub reads the runtime-db projection); user choices are dispatched as standard events. All testable.

`:can-enter` is the **first-class mirror of `:can-leave`** — same closed boolean contract, same pending-nav slot, same continue/cancel protocol — consulted on the **one navigation gate** every entry door shares. This closes the asymmetry that made the most common routing policy (the auth gate) a hand-rolled interceptor: interceptors remain the tool for genuinely cross-cutting policy (§Redirects and guards), but the auth-on-a-route case is now a declarative `:can-enter` route-metadata key, exactly like the unsaved-form case is a declarative `:can-leave`.

### Mechanism

A standard pending-navigation slot in `runtime-db`, four named events, and two optional route-metadata guard keys — `:can-leave` (on the route being left) and `:can-enter` (on the route being entered).

**Pending-nav slot** at `[:rf.runtime/routing :pending-navigation]` (schema in [Spec-Schemas.md §`:rf/pending-navigation`](Spec-Schemas.md#rfpending-navigation)):

```clojure
;; at [:rf.runtime/routing :pending-navigation]:
{:id                  "pn-7"
 :requested-by-event  [:rf/url-requested {:url "/editor/articles/42"}]
 :requested-url       "/editor/articles/42"
 :reason              :can-leave       ;; or :can-enter — which GUARD KIND rejected
 :direction           :leave           ;; or :enter — which END of the transition
 :rejecting-route     :editor/article
 :rejecting-guard     :editor/can-leave?}
```

`nil`/absent when no navigation is pending. `:direction` is `:leave` when the **current** route's `:can-leave` rejected and `:enter` when the **target** route's `:can-enter` rejected; `:reason` names the same distinction as the guard kind (`:can-leave` / `:can-enter`), so UI code branches on either. On an enter block the slot also carries `:enter-attempts` (the loop-guard counter — see [§The loop guard](#the-loop-guard)); on a blocked popstate it carries `:url-restored? true`.

**Four named events:**

| Event | Dispatched by | Behaviour |
|---|---|---|
| `:rf.route/navigation-blocked` | The runtime, when a `:can-leave` guard rejects | Sets `:rf/pending-navigation` with `:direction :leave` (the runtime does this before dispatching the event); user code subscribes and renders the dialog. Event vector: `[:rf.route/navigation-blocked pending-nav]`. |
| `:rf.route/entry-blocked` | The runtime, when a `:can-enter` guard rejects | The enter-block **mirror** of `:rf.route/navigation-blocked`. Sets `:rf/pending-navigation` with `:direction :enter`; user code subscribes and renders the dialog (or, in the canonical auth case, its handler redirects to login). Event vector: `[:rf.route/entry-blocked pending-nav]`. |
| `:rf.route/continue` | User code (typically a "Yes, leave" button, or a login-completed action) | Clears `:rf/pending-navigation` and re-dispatches the original navigation request, **bypassing the `:can-leave` guard for this one shot but RE-RUNNING `:can-enter`** (see [§Continue re-runs `:can-enter`](#continue-re-runs-can-enter)). Event vector: `[:rf.route/continue pending-nav-id]`. |
| `:rf.route/cancel` | User code (typically a "Stay" button) | Clears `:rf/pending-navigation`; the URL stays unchanged. Event vector: `[:rf.route/cancel pending-nav-id]`. |

**Route-metadata extensions** — declare the leave-guard and/or enter-guard sub on a route:

```clojure
(rf/reg-route :editor/article
  {:doc       "Editing an article."
   :path      "/editor/articles/:id"
   :params    [:map [:id :string]]
   :can-leave [:editor/can-leave?]}             ;; sub-id; (subscribe [<sub-id> <target>]) → boolean
  )

(rf/reg-route :account/settings
  {:doc       "Account settings — signed-in only."
   :path      "/account/settings"
   :can-enter [:auth/signed-in?]})              ;; sub-id; (subscribe [<sub-id> <target>]) → boolean

(rf/reg-sub :editor/can-leave?
  :<- [:editor/dirty?]
  (fn [dirty? _] (not dirty?)))                 ;; true means "OK to leave"

(rf/reg-sub :auth/signed-in?
  :<- [:auth/user]
  (fn [user _] (some? user)))                   ;; true means "OK to enter"
```

Each sub returns `true` when the route is OK to leave / enter; `false` to block. The convention: the *sub's name* describes the positive case (`:can-leave` / `:can-enter`), so `false` means "can NOT" — block. `:can-enter` is evaluated on the **target** route (the one being entered); `:can-leave` on the **current** route (the one being left).

<a id="the-guard-sub-receives-the-pending-target-as-an-argument"></a>
**The guard sub receives the pending target as an argument.** The runtime subscribes the guard sub with the pending **target** appended to its query vector — `(subscribe [<guard-id> <target>])` — so the guard receives it as the second destructure position, `(fn [inputs [_ target] ...])`. `target` is `{:route-id :params :query :fragment :url}` (the resolved target of the navigation). This is what makes the [fragment-check contract](#fragments) *implementable* for `:can-leave` (compare the current `:rf.route/fragment` against `(:fragment target)` — allow when only the fragment differs) and what gives `:can-enter` the destination to branch on (read `(:route-id target)`, `(:params target)`, or the target route's `:tags` via `handler-meta`). A guard that ignores the extra arg (the common `:<- [:editor/dirty?]` shape above) is unaffected — the target rides in the query vector's tail, unread.

**Closed contract.** The runtime accepts only the literals `true` and `false` from a guard sub. Any other value (`42`, a non-empty string, `nil`, a map) BLOCKS the navigation and emits a structured trace — `:rf.error/can-leave-non-boolean` for a leave guard, `:rf.error/can-enter-non-boolean` for an enter guard — with `:tags {:route-id :query :value :reason :recovery :blocked-navigation :frame <navigating-frame>}` (the `:frame` tag). The closed contract forces the route author to write `(boolean ...)` / `(not ...)` rather than warn-and-let-through. Pre-alpha posture: no shim, no soft transition; the warning slot is removed entirely.

### Default flow

The gate consults `:can-leave` (on the **current** route) then `:can-enter` (on the **target** route). Both run on the SAME `maybe-block-navigation` gate, so coverage does not depend on which event drove the navigation.

1. A navigation is requested. The entry event differs by source: a link click fires `:rf/url-requested`; a programmatic call fires `:rf.route/navigate`; a popstate (Back/Forward) or the initial-sync fires `:rf.route/handle-url-change`. The runtime runs the gate on **every** one of these entry points — they share a single `maybe-block-navigation` gate, so guard coverage does not depend on which event drove the navigation. **This is what makes `:can-enter` fail *closed* through every door:** a hand-rolled interceptor that attaches only to `:rf.route/navigate` (the historical shape — see [§Redirects and guards](#redirects-and-guards)) fails *open* through link clicks (`:rf/url-requested`) and deep-links / Back-Forward (`:rf.route/handle-url-change`), which never dispatch `:rf.route/navigate`. The one gate covers all three.
2. The runtime resolves the pending **target** (`{:route-id :params :query :fragment :url}`) from the requested URL, then evaluates:
   a. the **current** route's `:can-leave` sub (if any), passing the target as an argument;
   b. if leave passed, the **target** route's `:can-enter` sub (if any), passing the same target.
3. **No guard, or both guards return `true`** → proceed normally. The new URL becomes active; nav-token allocates; `:on-match` runs.
4. **Either guard returns `false`** → BLOCK. `:can-leave` blocks with `:direction :leave` and dispatches `:rf.route/navigation-blocked`; `:can-enter` blocks with `:direction :enter` and dispatches `:rf.route/entry-blocked`. In both cases:
   a. Generate a `pending-nav-id`.
   b. Write `:rf/pending-navigation` with `{:id <id> :requested-by-event <ev> :requested-url <url> :reason <:can-leave|:can-enter> :direction <:leave|:enter> :rejecting-route <id> :rejecting-guard <sub-id>}` (the rejecting route is the **current** route for a leave block, the **target** route for an enter block).
   c. **The URL does not change.** No `pushState`, no `:rf/route` slice update, no `:on-match`. For a **forward** nav (`:rf/url-requested` / `:rf.route/navigate` / `:rf.route/transitioned`) the browser URL has not moved yet, so declining to push is sufficient. A **popstate** block (the triggering event is `:rf.route/handle-url-change` — Back / Forward) is different: the browser has *already* moved the address bar to the rejected URL. To keep "the URL does not change" true, the runtime emits a `:rf.nav/replace-url` that **restores the address bar to the current route slice's URL** (rebuilt via `route-url` from the slice's `:route-id`/`:params`/`:query`/`:fragment`) — a history *replace*, not a push, so no entry is added. This applies to a leave *and* an enter block reached through popstate (the browser moved to the target either way). URL and slice agree again; `:rf.route/cancel` then leaves nothing else to do.
   d. Dispatch `[:rf.route/navigation-blocked pending-nav]` (leave) or `[:rf.route/entry-blocked pending-nav]` (enter). Apps may register their own handler (default is a no-op; the value is in the slot, which a sub reads). The canonical enter handler is an **auth redirect**: read the slot's `:requested-url`, dispatch `:rf.route/navigate` to login, stash the target for a post-login bounce-back.
   e. Emit the block trace event (`:rf.route/navigation-blocked` / `:rf.route/entry-blocked`) with `:tags {:requested-url <url> :rejecting-route <id> :rejecting-guard <sub-id> :direction <:leave|:enter> :frame <navigating-frame>}` (the `:frame` lets a multi-frame app filter the block to the frame that caused it; `:requested-url` is redacted per [009 §EP-0015 egress](009-Instrumentation.md) before the trace crosses the egress boundary).
5. UI renders the confirmation dialog (or the redirect handler acts) by subscribing to `:rf/pending-navigation`.
6. User (or the login flow) chooses:
   - **Continue** → dispatch `[:rf.route/continue pending-nav-id]`. Runtime clears the slot and re-issues the original navigation, **bypassing the `:can-leave` guard** for this one shot but **re-running `:can-enter`** (see [§Continue re-runs `:can-enter`](#continue-re-runs-can-enter)).
   - **Cancel** → dispatch `[:rf.route/cancel pending-nav-id]`. Runtime clears the slot. Nothing else changes.

<a id="continue-re-runs-can-enter"></a>
### Continue re-runs `:can-enter`

`:rf.route/continue` bypasses the **leave** guard (the user confirmed leaving) but **re-runs** the **enter** guard. This is not an optimisation detail — it is the enter-gate's whole correctness story:

- An **enter-pending survives a committed navigation.** A `:can-enter` block on `/account` does not commit anything — the user is still on `/home`. If they then navigate elsewhere and come back, or the login flow completes and dispatches `:rf.route/continue`, the enter gate must re-evaluate: has the condition that blocked entry actually changed? Bypassing it on resume would let a still-signed-out user into `/account` merely because they clicked "continue".
- The canonical shape: a login flow blocks entry, redirects to login, and on successful login dispatches `[:rf.route/continue pn-id]`. The resume re-runs `:can-enter` — now the user IS signed in, so it passes and the navigation completes. Had the user *not* actually signed in, the resume re-blocks (a fresh `:rf.route/entry-blocked` pending slot), and the [loop guard](#the-loop-guard) below stops an infinite `continue → block → continue`.

The bypass is expressed as `:bypass-guards? #{:leave}` on the re-issued event (see [§The `:bypass-guards?` opt](#the-bypass-guards-opt)); the enter guard is deliberately *not* in the set.

<a id="the-loop-guard"></a>
### The loop guard

A `:can-enter` that re-blocks the SAME target across successive `:rf.route/continue` resumes is a `continue → block → continue` spin — a runaway loop. The runtime tracks an `:enter-attempts` counter on the pending-nav slot: each enter block through the same target URL increments it, and once it crosses the loop ceiling the gate **fails closed** — it emits `:rf.error/route-guard-loop` (with `:requested-url`, `:rejecting-route`, `:rejecting-guard`, `:attempts`, `:frame`), clears the pending slot rather than stranding a resumable one, and dispatches **no** further block user-event (which would only invite the same spin). A leave block resets the counter (a leave block starts a fresh chain). This bounds a misbehaving `:can-enter` sub (or resume policy) to a finite number of retries instead of an infinite loop.

<a id="the-bypass-guards-opt"></a>
### The `:bypass-guards?` opt

`:bypass-guards?` is a navigate **opt** (third slot) carrying a **set** of `#{:leave :enter}` — the guards to skip for this one navigation:

- `#{:leave}` skips the active route's `:can-leave` gate (the `:rf.route/continue` resume shape).
- `#{:enter}` skips the target route's `:can-enter` gate.
- `#{:leave :enter}` skips both (the runtime's own synthesised `:rf.route/transitioned` re-dispatch out of `:rf/url-requested`, where the gate already ran and passed).

It replaces the former single-boolean `:bypass-leave-guard?` opt (pre-alpha rename — no back-compat alias). A missing or `nil`/`false` value bypasses nothing.

### Why this shape (not a hook-based router)

The hook-based version (e.g., React Router's `useBlocker`) is convenient but tied to component lifecycle. Re-frame2's strengths are explicit state and dispatched events; this design preserves them. Slightly more verbose at the call site; far more testable.

A test fires `[:rf/url-requested {:url "/cart"}]` against a frame whose `:editor/can-leave?` sub returns `false`, asserts `:rf/pending-navigation` is set, asserts `:rf.nav/push-url` did NOT fire, dispatches `[:rf.route/continue pending-nav-id]`, asserts the navigation completes. The enter mirror: fires `[:rf/url-requested {:url "/account"}]` against a frame whose `:auth/signed-in?` sub returns `false`, asserts `:rf/pending-navigation` is set with `:direction :enter`, asserts `:rf.nav/push-url` did NOT fire, asserts `:rf.route/entry-blocked` fired. No DOM, no event simulation, no hook-mock.

### Interaction with other navigation features

- **Nav-tokens.** Navigation blocking happens *before* the new nav-token would be allocated; tokens are for committed navigations. The `:rf.route/continue` re-issue allocates a fresh nav-token like any other navigation. The original (blocked) attempt never received one.
- **Fragments.** Both guards run for any URL change, including fragment-only changes. The guard sub receives the pending target as an argument, so a `:can-leave` that wants fragment changes to bypass the guard returns `true` when the only difference is the fragment — it compares the current `:rf.route/fragment` against `(:fragment target)` (the argument), which is what makes the fragment-check contract *implementable* (before the target argument, the guard sub had no way to read where the navigation was heading — see [§The guard sub receives the pending target as an argument](#the-guard-sub-receives-the-pending-target-as-an-argument)).
- **Multiple guards.** A route has at most one `:can-leave` and one `:can-enter` sub (each is a single-valued metadata key). For frame-level cross-cutting policy (e.g., "always block when `:auth/logging-out?`"), register the policy with `reg-interceptor` and reference it from the frame's `:interceptors` chain (the [EP-0022](../docs/EP/EP-0022-registered-interceptors.md) "global within this frame" mechanism) so it runs on every navigation entry event (`:rf/url-requested`, `:rf.route/navigate`, `:rf.route/handle-url-change`). Such an interceptor runs *before* the guard check — it can short-circuit by setting `:rf/pending-navigation` directly. **`:can-enter` vs interceptor:** reach for `:can-enter` for a policy that belongs to *one route* (this route needs auth); reach for a frame-`:interceptors` policy for a rule that spans *many* routes uniformly (§Redirects and guards).

### Conformance

Fixture `route-navigation-blocked.edn` exercises:
1. Register a route with `:can-leave [:editor/can-leave?]`.
2. Set `:editor/dirty?` to `true` (sub returns `false`).
3. Dispatch `[:rf/url-requested {:url "/cart"}]`.
4. Assert `:rf/pending-navigation` is set with `:direction :leave`; `:rf.nav/push-url` did NOT fire; `:rf.route/navigation-blocked` trace event fired; `:rf/route` slice unchanged.
5. Dispatch `[:rf.route/continue pending-nav-id]`.
6. Assert `:rf/pending-navigation` is `nil`; the URL is `/cart`; `:route/cart` is the active route.

Fixture `route-entry-blocked.edn` exercises the enter mirror, through EACH of the three doors:
1. Register a target route with `:can-enter [:auth/signed-in?]`; the sub returns `false`.
2. Dispatch `[:rf/url-requested {:url "/account"}]` (link click) — assert `:rf/pending-navigation` set with `:direction :enter`, `:reason :can-enter`; `:rf.nav/push-url` did NOT fire; `:rf.route/entry-blocked` trace fired; slice unchanged.
3. Repeat with `[:rf.route/navigate :account/settings]` (programmatic) and `[:rf.route/handle-url-change "/account"]` (deep-link / popstate) — assert the SAME block outcome (fail-closed through every door).
4. Flip `:auth/signed-in?` to `true`, dispatch `[:rf.route/continue pending-nav-id]`, assert `:can-enter` re-ran and now passes: `:rf/pending-navigation` is `nil` and `:account/settings` is active.
5. Negative: a `:can-enter` sub returning a non-boolean BLOCKS and emits `:rf.error/can-enter-non-boolean`; a `:can-enter` that keeps blocking across repeated `:rf.route/continue` resumes eventually emits `:rf.error/route-guard-loop` and stops re-issuing.
6. Bypass: `[:rf.route/navigate :account/settings {} {:bypass-guards? #{:enter}}]` skips the enter gate (enters while signed out); `#{:leave}` skips only the leave gate.

## Redirects and guards

**Auth-on-a-route is `:can-enter`, not an interceptor.** The most common routing policy — "you must be signed in to reach this route" — is a declarative [`:can-enter`](#navigation-blocking--pending-nav-protocol) route-metadata key. It runs on the ONE navigation gate, so it fails *closed* through all three entry doors (link click, programmatic nav, deep-link / Back-Forward) with no per-door plumbing:

```clojure
(rf/reg-route :route/account
  {:path      "/account"
   :can-enter [:auth/signed-in?]})

(rf/reg-sub :auth/signed-in?
  :<- [:auth/user]
  (fn [user _] (some? user)))     ;; true → OK to enter

;; The app's :rf.route/entry-blocked handler turns the block into a login
;; redirect (the enter-block mirror of a confirm dialog). The pending-nav
;; slot carries the target the user aimed at, so the login flow can bounce
;; them back with :rf.route/continue after they sign in (which RE-RUNS
;; :can-enter — now signed in, it passes).
(rf/reg-event :rf.route/entry-blocked
  (fn [{:keys [db]} [_ {:keys [requested-url]}]]
    {:db (assoc-in db [:auth :return-to] requested-url)
     :fx [[:dispatch [:rf.route/navigate :route/login]]]}))
```

**Interceptors remain the tool for cross-cutting policy** — a rule that spans *many* routes uniformly (a feature flag gating a whole section, a maintenance-mode lockout, an analytics-driven redirect). Per [EP-0022](../docs/EP/EP-0022-registered-interceptors.md) an interceptor is a **registered program member**: author its behaviour once with `reg-interceptor` (an app-owned id), then attach it to the frame's `:interceptors` chain (so it runs on *every* navigation entry event) by **reference**. Inline interceptor maps/Vars in a chain are rejected at registration with `:rf.error/inline-interceptor-removed`.

A cross-cutting guard interceptor **must cover all three entry doors**, or it fails *open*: a policy that inspects only `:rf.route/navigate` lets a logged-out user in through a link click (`:rf/url-requested`) or a deep-link / Back-Forward (`:rf.route/handle-url-change`), neither of which dispatches `:rf.route/navigate`. So the interceptor resolves the target from *whichever* nav event drove it, then decides:

```clojure
;; Resolve the target route + params from ANY of the three navigation
;; events (fail-closed coverage — do not guard only :rf.route/navigate).
(defn- nav-target [event]
  (let [[ev-id a b] event]
    (case ev-id
      :rf.route/navigate          {:id a :params (or b {})}     ;; a = route-id
      :rf/url-requested           (let [{:keys [to params url]} a]
                                    (cond
                                      to  {:id to :params (or params {})}
                                      url (when-let [{:keys [route-id params]} (rf/match-url url)]
                                            {:id route-id :params (or params {})})))
      :rf.route/handle-url-change (when-let [{:keys [route-id params]} (rf/match-url a)]
                                    {:id route-id :params (or params {})})
      nil)))

(rf/reg-interceptor :app/section-lockout
  {:doc "Redirect away from a whole gated section when the feature flag is off — a
         cross-cutting rule spanning many routes, so an interceptor not :can-enter."}
  {:before
   (fn before [ctx]
     (if-let [{:keys [id]} (nav-target (get-in ctx [:coeffects :event]))]
       (let [route-meta (rf/handler-meta :route id)
             gated?     (boolean (some #{:beta-section} (:tags route-meta)))
             enabled?   (get-in ctx [:coeffects :db :flags :beta])]
         (if (and gated? (not enabled?))
           ;; skip the original handler (so the gated slice never commits) and
           ;; redirect. Skipping — not rewriting :coeffects :event — because the
           ;; handler is picked from the ORIGINAL event id before interceptors run.
           (-> ctx
               (assoc :rf/skip-handler? true)
               (assoc-in [:effects :fx] [[:dispatch [:rf.route/navigate :route/home]]]))
           ctx))
       ctx))})

;; Attach to the FRAME's :interceptors so it runs on every navigation entry
;; event — the "global within this frame" mechanism (EP-0022). Attaching only
;; to :rf.route/navigate is the classic fail-open bug.
(rf/reg-frame :app
  {:interceptors [:app/section-lockout]})
```

Guards are interceptors, not a special routing mechanism. They are registered once and referenced by id; they compose — list multiple refs in the `:interceptors` chain and they layer in order. Prefer `:can-enter` when the policy belongs to one route; reach for a frame-`:interceptors` guard when it spans many routes uniformly, and cover all three doors when you do.

## Server-side rendering integration (per [011](011-SSR.md))

The server-side flow:

1. HTTP request arrives.
2. `make-frame` per request. `:initial-events` fire `[:rf/server-init request]`, which dispatches `[:rf.route/handle-url-change (:uri request)]`.
3. Route slice is set from the URL; the same handler runs on server and client. Path params, query params, defaults, and `:query-retain` keys are populated.
4. The matched route's `:on-match` events dispatch — the same vector that runs client-side. Server-side data loaders complete before the drain settles.
5. Drain settles; root view renders against the populated state.
6. HTML + serialised state ship to the client.

On the client, hydration runs `[:rf/hydrate state]` which restores the route along with everything else. **`:on-match` does not re-fire on hydration** — the seeded `app-db` already contains the loaded data. The first client render produces the same HTML the server rendered (same `:rf.route/id`, same `:params`, same `:query`).

## Tooling and AI-amenability

- `(rf/registrations :route)` enumerates every registered route. Tools and agents enumerate them; AI scaffolding consults this before generating new routes to avoid collisions.
- `(rf/handler-meta :route :route/cart)` returns the route's metadata: path, params shape, query shape, `:on-match`, `:on-error`, `:scroll`, `:parent`, tags, source coords. The `:on-match` slot is **enumerable** — tools render route-loading dependency graphs without parsing handler bodies.
- The `:rf/route` sub gives the entire route map; `:rf.route/id`, `:rf.route/params`, `:rf.route/query`, `:rf.route/transition`, `:rf.route/error` are conveniences.
- `:rf.route/navigate`, `:rf.route/handle-url-change`, `:rf.route/transitioned`, `:rf/url-requested` are stable, named events; trace events surface every navigation and every URL request.
- A registered `:rf.route/not-found` is required (per [§Route-not-found](#route-not-found--rfroutenot-found-canonical)); tools surface the `:rf.warning/no-not-found-route` trace event for apps missing the registration.

## Frame-destroy teardown

Routing's **durable** per-frame state — the route slice (with the active `:nav-token`) and the pending-nav slot — **lives in the frame's `runtime-db`** under `[:rf.runtime/routing]` (per [§The `:rf/route` slice](#the-rfroute-slice)) and is released **naturally** when the frame value goes away. The **host-side transient caches** are the exception: the saved **scroll-position cache** *and* the **nav-token / pending-nav allocator counters** (the `:nav-token-counter` / `:pending-nav-counter` high-water marks) are held in module-private per-frame atoms outside the frame value (per [§Scroll restoration](#scroll-restoration) and [§Navigation tokens — stale-result suppression](#navigation-tokens--stale-result-suppression)) — **not** `runtime-db` — so routing **publishes `:routing/on-frame-destroyed!`** to release their entries on `destroy-frame!`. This matches the per-feature artefacts that hold frame-scoped state outside the frame value:

- [Flows](013-Flows.md#frame-destroy-teardown) — publishes `:flows/teardown-on-frame-destroy!` because the per-frame flow registry and `last-inputs` dirty-check cache live in module-private atoms, not in the frame value.
- [Machines](005-StateMachines.md) — the machine snapshots live at `[:rf.runtime/machines :snapshots <id>]` inside `runtime-db` so they die naturally. The artefact publishes **two** distinct destroy hooks for state held outside the frame value: `:machines/on-frame-destroyed!` cancels the per-frame `:after` timer registry and epoch counters (the destroyed-frame cleanup callback, step 5 of [002 §Destroy](002-Frames.md#destroy)), and `:machines/teardown-on-frame-destroy!` runs the owned-actor teardown cascade — the reverse-order `:exit` walk plus per-actor handler unregistration (step 2). See [§Two destroy-hook verbs](002-Frames.md#two-destroy-hook-verbs) for why the two coexist.
- [Schemas](010-Schemas.md) — publishes `:schemas/on-frame-destroyed!` for the per-frame validator caches held in module-private atoms.
- [Routing](#scroll-restoration) — publishes `:routing/on-frame-destroyed!` for the host-side scroll-position cache. Scroll positions are host-derived (`window.scrollX/Y`), ephemeral, and meaningless on the server / after a restore, so they are **transient** (not runtime-db) per [002 §Durable vs transient](002-Frames.md) — and therefore need explicit per-frame teardown like the caches above.

Every other piece of routing's per-frame state remains in the frame value and dies naturally; only the scroll cache requires the hook.

### Process-global slots are intentionally not per-frame

Routing holds three process-global resources that survive `destroy-frame!` and are **intentionally cross-frame**:

| Resource | Where | Why global |
|---|---|---|
| The `:route` registrar map | `rf/registrar` (per [001-Registration](001-Registration.md)) | Routes are a **corpus-wide resource** — every frame sees the same registered routes. A user's `(rf/reg-route :route/cart ...)` registers a route that frame `:left` and frame `:right` both match-URL against the same way. Per-frame route tables would multiply registrations and have no consumer use case. |
| `reg-counter` (rule-6 tiebreak counter, monotonic) | process-global `defonce` atom inside the routing artefact | Rule 6 of the [§Route ranking algorithm](#route-ranking-algorithm) breaks structural ties on registration order. The counter monotonically increases over the process lifetime so a re-registered route lands "after" its siblings (per [§Hot-reload semantics for routing](001-Registration.md#hot-reload-semantics)). Per-frame counters would re-shuffle ranks on frame destroy in surprising ways; cross-frame correctness requires the counter to be global. `reset-counters!` is a test-only helper. |
| `route-table-cache` (compiled-route lookup memo) | process-global `defonce` atom inside the routing artefact | A pre-sorted compiled-route table keyed on the registrar map's identity. Self-managing: rebuilds whenever `(identical? @route-registrar last-key)` is false. Per-frame caches would compute the same value redundantly for every frame; cross-frame caching is correct because the registrar is itself cross-frame (above). |
| `url-claim-order` (URL-ownership claim order) | process-global `defonce` atom inside the routing artefact | A vector recording, in claim order, which frames carry `:url-bound? true` (maintained by the duplicate-binding registration-hook). The browser URL is **one** process resource with **one** owner — `url-owner-frame-id` resolves the first-claimed still-live binding across all frames (the incumbent), so the "existing owner is unchanged" rule (§Multi-frame routing) holds against a later duplicate regardless of id ordering. Per-frame claim tracking would be meaningless — there is a single browser URL, not one per frame. A destroyed frame's claim is dropped on `destroy-frame!` (the `:routing/on-frame-destroyed!` teardown), so ownership self-heals to the next claimant. |

None of these clear on `destroy-frame!` and none should. A new feature artefact author scanning routing for the teardown shape should note the two-part pattern: durable per-frame state in `runtime-db` releases naturally with the frame value, while frame-scoped state held **outside** the frame value (module-private atoms — here the host-side scroll cache) is released by an explicit `:…/on-frame-destroyed!` hook, mirroring [Flows §Frame-destroy teardown](013-Flows.md#frame-destroy-teardown), [Machines §Teardown](005-StateMachines.md), and [Schemas](010-Schemas.md).

### What the teardown looks like

`destroy-frame!` calls `(swap! frame-registry dissoc frame-id)` which drops the whole frame's frame-state (both partitions) along with everything else (per [002 §Destroy](002-Frames.md#destroy)). The route slice (`[:rf.runtime/routing :current]`) and the pending-nav slot (`[:rf.runtime/routing :pending-navigation]`) live in the reserved **runtime-db** child `:rf.runtime/routing` (per [Conventions §Reserved runtime-db keys](Conventions.md#reserved-runtime-db-keys)) and release in lockstep when the frame value goes away. The host-side transient caches — the saved **scroll-position cache** and the **nav-token / pending-nav counter** high-water marks — are held outside the frame value in module-private per-frame atoms (per [§Scroll restoration](#scroll-restoration) and [§Navigation tokens — stale-result suppression](#navigation-tokens--stale-result-suppression)), so `destroy-frame!` fires the single `:routing/on-frame-destroyed!` late-bind hook to drop that frame's entry in *both* caches. Beyond those there is no orphaned listener and no leaked timer to clear.

The corpus-wide `:rf.error/handler-exception` listener (`on-match-error-listener`, registered at routing-artefact load time per [§Per-route error handling](#per-route-error-handling)) is process-global and survives every `destroy-frame!`. It is `defonce`-protected and re-discriminates each handler-exception against the failing frame's current route slice (`[:rf.runtime/routing :current]`) — destroying frame `:left` simply means future exceptions thrown in `:left`'s drain no longer trigger the listener (the frame is gone) and the listener continues to discriminate `:right`'s exceptions normally.

## Multi-frame routing

Each frame has its own `[:rf.runtime/routing :current]` slice. URL ownership is an **explicit** declaration — a frame owns the browser URL only by carrying `:url-bound? true` on its `reg-frame` metadata. Frames that do not declare it have independent routes that don't push to the browser URL.

1. Every frame's `runtime-db` may have a `:rf/route` slice at `[:rf.runtime/routing :current]` (a framework-owned runtime-db path, read through the public `:rf/route` sub — not an app-db path).
2. A frame is **URL-bound** only when it carries an explicit `(reg-frame :id {:url-bound? true})`. The URL owner's `:rf.route/navigate` events fire `:rf.nav/push-url`, and `popstate` (Back/Forward) drives it. The browser URL reflects the URL-owning frame's route. There is **no `:rf/default`-owns-by-default floor** — the runtime never infers URL ownership from absence. `:rf/default` may BE the owner, but only when it carries `:url-bound? true` like any other frame.
3. Frames without `:url-bound? true` are **not URL-bound**. `:rf.route/navigate` updates their `:rf/route` slice (state changes) but does not fire `:rf.nav/push-url`. This is the right default for story-variant frames, devcards, per-test fixtures.
4. The runtime enforces "only one frame can own the URL at a time" — registering a second `:url-bound? true` frame while one already owns the URL is a `:rf.error/duplicate-url-binding` trace event. The duplicate-binding diagnostic fires from a registrar registration-hook that runs *after* the registry slot is written, so the conflict is observable but **not rejected**: both frames' `:url-bound? true` metadata is stored in the registry. Ownership is then a deterministic *resolution* over the stored metadata (`url-owner-frame-id`, [§popstate drives the URL-owner frame](#popstate-drives-the-url-owner-frame-both-directions)) — **the first frame to claim `:url-bound? true` (the incumbent) is the owner; the existing owner is unchanged** and the losing binding's history-mutation fxs (`:rf.nav/push-url` / `:rf.nav/replace-url`) no-op. The resolution is **first-claim-wins, NOT by id ordering**: the registrar's `(kind, id) → metadata` table is unordered, so the artefact tracks claim order in a process-global vector (maintained by the duplicate-binding registration-hook), and `url-owner-frame-id` returns the first-claimed binding that is still live. A later duplicate — *even one whose id sorts before the incumbent* — therefore cannot steal the browser URL; resolving by id ordering would have let it. The resolution is self-healing: if the incumbent later relinquishes its binding (re-registers `:url-bound? false`) or is destroyed, ownership falls to the next-claimed live binding. The error does not mutate any frame's metadata; resolving the conflict (removing one binding) is the app's concern.

   **Load order — frames registered before the routing artefact loads.** The registration-hook that records claim order is an *append-only future observer* (it does not replay prior registrations), so a frame that declared `:url-bound? true` **before** the routing artefact loaded is invisible to claim-order recording. To keep first-claim-wins deterministic *regardless of frame-vs-routing load order*, the artefact **reconciles the registry at hook-install time**: a single pre-existing `:url-bound? true` frame is seeded as the incumbent (its claim order is trivially known — it is the sole claimant), so a later duplicate — even an earlier-sorting one — cannot steal it. When **two or more** `:url-bound? true` frames pre-exist the load, their relative claim order is **unrecoverable** (the registrar table is unordered and no claim was recorded), so the runtime **fails closed**: `url-owner-frame-id` returns `nil` (no owner — outbound pushes no-op, popstate skips) and emits one `:rf.error/duplicate-url-binding` per extra pre-existing binding, rather than silently picking a winner by id sort. Re-registering or removing one of the bindings through the now-live hook re-establishes a deterministic claim order. This closes the URL-owner-steal hazard for the pre-load case in the same way the claim-order vector closes it for the post-load case. When no frame declares `:url-bound? true`, there is **no URL owner**: outbound `:rf.nav/push-url` / `:rf.nav/replace-url` no-op, and the inbound `popstate` listener skips (installing a history listener with no declared owner is a routing-config no-op, not a default-frame write).

### popstate drives the URL-owner frame, both directions

URL ownership is symmetric across the app↔browser boundary, and resolves to the **same single owner** in both directions:

- **Outbound (app → browser).** `:rf.nav/push-url` / `:rf.nav/replace-url` consult `url-owner-frame-id` (public) and only the owner mutates browser history. A non-owner's navigation updates its own `:rf/route` slice but no-ops the history push.
- **Inbound (browser → app).** A `popstate` listener fires `[:rf.route/handle-url-change url] {:frame (url-owner-frame-id)}` — targeted at the **current** owner resolved at pop time. `url-owner-frame-id` returns the explicitly-declared owner, or `nil` when none is declared; the listener skips the dispatch (no frameless write) when there is no owner. So Back/Forward restores the owner frame's `:rf/route` slice (and the body rendered off it) whenever a frame has declared `:url-bound? true`.

The routing artefact ships `install-history-listener!` (CLJS) — re-exported as `rf/install-history-listener!` — which wires this `popstate` listener and does the initial URL → owner-slice sync. Apps boot it once after registering frames. It is idempotent (re-install replaces the listener, hot-reload safe) and is the inbound counterpart of the `:rf.nav/push-url` gate; `rf/remove-history-listener!` tears it down. Targeting `url-owner-frame-id` at pop time means the listener tracks whichever frame declared `:url-bound? true` — a popstate dispatched at a stale owner after ownership transferred would update a frozen slice and leave Back/Forward broken. The listener also skips entirely when no owner is declared, rather than synthesising `:rf/default`.

The story / devcard / SSR cases all benefit:

- **Stories / devcards**: frame-per-variant; route within the variant is independent of the page URL.
- **Per-test fixtures**: each test frame has its own route; tests don't accidentally hit `pushState`.
- **SSR per-request frames**: the request URL is fed in via `:rf.route/handle-url-change`; no client-side `pushState` (which doesn't exist server-side anyway).

## URL strategies

The router is **path-form** on the write side: `route-url` builds `/active`, `match-url` reads `/active`, and the whole navigation cascade threads path-form URLs. But the browser's address bar can carry a **different shape** — `#/active` for a hash-URL app (no-server-rewrite static hosting; the shape most secretary-era re-frame v1 apps actually are). A **`:url-strategy`** is the thin skin between the two: a frame-level config map consulted at exactly **four** egress/ingress points, so the router's path-form model is unchanged and only the browser-facing shape differs.

A frame declares its strategy alongside `:url-bound?`:

```clojure
(rf/reg-frame :app {:url-bound?   true
                    :url-strategy rf/hash-url-strategy})   ;; default: rf/history-url-strategy
```

### The strategy contract

A strategy is a map with five keys:

```clojure
{:encode            (fn [path] href)     ;; PURE: path-form URL → browser href
 :decode            (fn [] path)         ;; read the current browser URL → path-form
 :push!             (fn [href])          ;; drive window.history (add an entry)
 :replace!          (fn [href])          ;; drive window.history (overwrite the current entry)
 :install-listener! (fn [on-change] teardown)}  ;; wire the browser URL-change event
```

- **`:encode`** maps a path-form app URL (`/active?q=milk`) to the browser href form (`#/active?q=milk` for hash; unchanged for history). **Pure**, host-agnostic.
- **`:decode`** reads the *current* browser URL and returns its path-form (`#/active` → `/active`; `pathname+search+hash` → itself for history). Side-reading (`window.location`), CLJS-only.
- **`:push!` / `:replace!`** drive `window.history` with the **encoded** href — `:push!` adds an entry, `:replace!` overwrites the current one. Side-effecting, CLJS-only.
- **`:install-listener!`** installs the browser URL-change listener (`popstate` for history, `hashchange` for hash) and returns a 0-arg **teardown** thunk. `on-change` is a 1-arg fn the listener calls with the **decoded** path-form URL on every browser-driven change.

**Round-trip law.** `:encode` / `:decode` are inverses over the app-relative URL: for every path `p`, `(decode)` at a URL the browser reached via `(push! (encode p))` yields `p` back. This is the property the conformance fixtures pin for both shipped strategies (encode/decode round-trip per strategy, plus a malformed-URL negative that fails closed to a route-miss).

### The four consult points

The strategy is consulted **only** here; everything else — `route-url`, `match-url`, the navigation cascade, the route slice — is path-form throughout:

1. **`:rf.nav/push-url`** — encodes the path-form URL and drives `window.history` via the owner's `:push!`.
2. **`:rf.nav/replace-url`** — same, via the owner's `:replace!`.
3. **`route-link` href render** — the rendered `:href` is `(encode path-url)` (so copy-link / open-in-new-tab land on the right address); the click still dispatches the path-form `:rf/url-requested`.
4. **`install-url-listener!`** — installs the owner strategy's browser listener and decodes each change to path-form before dispatching `:rf.route/handle-url-change`.

The strategy is resolved from the **URL-owning frame's** `:url-strategy` (default `history-url-strategy`), so a non-owner frame never consults it. `install-url-listener!` resolves the strategy once at install (which browser event to wire); it has a 1-arity that takes an explicit strategy map for apps whose URL-owning frame is created asynchronously (e.g. a `frame-provider` React effect), so the listener kind does not depend on registration timing. Its established alias `install-history-listener!` is retained (history is the default).

### Two strategies ship — the line holds at two

- **`history-url-strategy`** (the **default**) — HTML5 History, path-form. `:encode` / `:decode` are identity over the app-relative URL.
- **`hash-url-strategy`** — `#`-prefixed (`#/active`), for no-server-rewrite static hosting and secretary-era v1 migrations.

**Memory URLs need no third strategy.** A frame that does not declare `:url-bound? true` is already URL-free ([§Multi-frame routing](#multi-frame-routing)) — the non-url-bound frame *is* the "memory" case, spec-free. Stories, devcards, and per-test fixtures already ride that path; adding a memory strategy would duplicate it.

### SSR ignores strategies

On the server there is no `window` and no address bar: the request URL is fed in **path-form** via `:rf.route/handle-url-change`, the view renders against the slice, and `route-link` emits its **path-form** `<a href>` shell (`:encode` = `identity` server-side). A hash never reaches the server. So every side-effecting strategy key (`:push!` / `:replace!` / `:install-listener!`) is CLJS-only — the JVM half of each map carries only `:encode` / `:decode` — and the four consult points fall back to path-form when no `window` is present. On hydration the CLJS `route-link` render re-encodes the href through the frame's strategy, so a hash app's server shell carries `/active` and the hydrated anchor carries `#/active`, both pointing at the same route (no hydration mismatch — the route is the same, only the address-bar shape changes, and the shape is a client-only concern).

## Open questions

> **SA-4 classification.** Per [SPEC-AUTHORING §SA-4](SPEC-AUTHORING.md): all eight items classify as **`:post-v1 tracked`** — additive design candidates that do not block v1.

### Declarative `:on-leave` route hook (post-v1)

`:can-enter` shipped as the first-class mirror of `:can-leave` (rf2-p69yaz Option A). A route-owned **`:on-leave`** hook — a declarative "run these events when this route is left" slot, the imperative sibling of `:on-match` (which runs on *entry*) — was considered as the leave-side companion (rf2-uu19xv) and **deliberately deferred, not shipped**. The shipped-surface `:on-leave` remedy was **rejected** in the same 012 design pass: the leave-side teardown story is already covered by two existing, sufficient surfaces —

- **Machine `:exit` actions and frame teardown.** A route that owns machine-shaped resources tears them down through the machine's `:exit` cascade, which fires on every exit path including the frame's destruction (per [005 §Cooperative cancellation](005-StateMachines.md) and [Cross-Spec-Interactions §Routing × Machines](Cross-Spec-Interactions.md#routing--machines)). 10+ machine examples already teach this shape; a route `:on-leave` would duplicate it for the machine case.
- **Route-owned resources release declaratively.** A route's `:resources` plan releases its owner leases on route change (per [016 §Route integration](016-Resources.md)) — the leaving route's `[:route prev-id prev-nav-token]` owner is dropped automatically, no `:on-leave` needed.

What `:on-leave` would add over these is a *route-declared, non-machine, non-resource* leave hook (e.g. "clear this app-db slice when leaving this route", "fire an analytics `left-page` event"). Today that rides an ordinary event dispatched from the `:can-leave` path or from a machine `:exit`; the declarative slot is sugar over it. Deferred until real apps surface a leave-side teardown that neither the machine `:exit` nor the resource-lease release covers cleanly — the two surfaces above carry the common cases, and adding a third leave-side mechanism before the gap is demonstrated would be redundant surface.

### Native nested layouts (post-v1)

Per [§Nested layouts](#nested-layouts) the v1 surface is `:parent` + the `:rf.route/chain` sub — the rendering side reads the layout chain as data and composes shells top-down. A richer mechanism — true `<Outlet/>`-style render slots, **parent-loader cascades** (a parent route's `:resources`/`:on-match` inherited by children so a child doesn't restate the parent's data plan), and partial revalidation on child-only navigations (parent doesn't re-run when only the leaf changes) — is a substrate-shaped addition rather than a data convention. Deferred until apps surface a real cost the chain-sub pattern can't carry; the `:parent` convention does not preclude a richer slot mechanism later.

**The duplication tax is already on the record — the motivating case the deferral lacked.** The data half of nesting (a parent-route data plan that cascades to children) is missing today: child routes must **duplicate the parent's `:resources`/`:on-match`** if they need the same reads (per the [§Nested layouts](#nested-layouts) future-expansion note). The flagship RealWorld-on-resources example already pays this, three ways over:

- **Byte-identical sibling entries.** `:realworld.profile/show` and `:realworld.profile/favorites` carry the *same* profile-banner read verbatim — `{:resource :realworld/profile :params (fn [route] {:username …}) :blocking? true}` — restated in each route's `:resources` (`examples/real-apps/realworld_resources/routing.cljs` lines 169-171 and 190-192).
- **A helper *function* to stay DRY.** The `:realworld/home` / `:realworld/home-tag` sibling pair shares its three-read plan only by factoring it through a `home-resources` helper *function* (`routing.cljs` lines 54-84) — which degrades the [§The route table is data](#the-route-table-is-data) property at exactly the nesting boundary: the shared plan stops being greppable data and becomes a call.
- **Zero `:parent` uptake corpus-wide.** No example registers a route `:parent` (grep the corpus: the only `:parent-id` hits are unrelated machine `:data`). The render-chain convention exists but the corpus never reaches for it, because it carries layout but not data — so the shared-data cases route around it into duplication instead.

The concrete drift risk this invites: change the banner entry's `:blocking?` on one profile tab and not the other, and the two tabs get silently divergent loading/SSR-wait UX — no lint, no error. A cascade design (effective plan = ancestors' entries then the leaf's, deduped by resolved `[scope resource-id canonical-params]` identity; the same-route re-fire rule refined per contributing ancestor so a sibling-leaf nav under an unchanged parent skips the parent's re-dispatches) would let the shell own the banner once — but it trades away a route's data needs being readable off its own registration, which the corpus's explicitness bias resists. That tension is why this stays a post-v1 open question rather than a shipped surface; the receipts above give the revisit its motivating cost.

### Data-form path patterns (post-v1)

Per [§The route table is data](#the-route-table-is-data) the v1 canonical wire form for `:path` is the string grammar (`"/account/:id/orders/*rest"`). A formally-specified vector-of-segments alternative (e.g. `[:account [:id :int] "orders" [:rest :catchall]]`) would carry per-segment schema inline and survive copy-paste better than embedded sigils. Deferred — the string grammar is the v1 wire form and tools, conformance fixtures, and `match-url` all key off it; the data form would be an additive parser front-end.

### Custom scroll-strategy registry (post-v1)

Per [§Scroll restoration](#scroll-restoration) the v1 contract is the closed three-enum set (`:top`, `:restore`, `:preserve`) plus the map-form opt-in for host-specific shapes. A first-class registry (apps `register-scroll-strategy!` named entries; routes / nav opts name them by keyword) is an additive composition surface that keeps strategy registration enumerable for tools. Deferred — the three enums cover the documented cases and locking them keeps tools' enumeration of scroll behaviour decidable.

### URL-state-as-source-of-truth (post-v1)

Per [§State-first, URL-second update order is locked](#state-first-url-second-update-order-is-locked) the v1 model is **state-canonical** (the runtime-db route slice), URL-derived: navigation mutates state first, then syncs the URL. The inverse — URL canonical, state derived (the browser URL is the single source of truth; subscriptions parse it on demand) — is a substantial design change with downstream impact on SSR, multi-frame, stale-suppression, and the navigation cascade ordering. Deferred; v1's direction is locked because the URL update can fail (browser denies, offline) and state must remain consistent.

### Declarative redirect rules in route metadata (post-v1)

Per [§Redirects and guards](#redirects-and-guards) v1 redirects compose as interceptors — guards are ordinary middleware over `:rf.route/navigate`, with full access to `app-db` and the event vector. A declarative metadata key (e.g. `:redirect-to :route/login`, optionally a fn of the route map) would let the simple "always redirect this route" cases skip interceptor boilerplate. Deferred — the interceptor form is the universal carry; the declarative key is sugar over it once the common shapes have settled in real apps.

### Route-plan prefetch — warm-mode `:on`-hover preload (post-v1)

Per [§Per-route data loading](#per-route-data-loading) a route's `:resources` plan runs on **activation** — the click pays the load. There is no *pre-navigation* prefetch surface: hovering a link cannot warm the destination route's data before the user commits, so the click then pays a blocking round-trip. React Router (`<Link prefetch>`) and TanStack Router (`preload` / intent) both ship this. The engine is ~95% built — [016 §Active owners and causes](016-Resources.md#active-owners-and-causes) already supports ownerless, cause-only `ensure` entries (the focus/reconnect scans use exactly this shape), and unowned entries are GC-eligible — so the missing piece is a **route-plan-level** prefetch verb, not new cache machinery. App-space hover-`ensure` already works *per resource*, but route-plan prefetch in app space forces every link site to re-derive the destination's params / `:when` / scope precedence — the exact duplication the plan machinery exists to prevent.

**Design to flip post-v1 (attached verbatim so the flip is cheap).** A public `[:rf.route/prefetch target]` event that runs the route's `:resources` plan in **WARM mode**: entries `ensure` **ownerless** (cause-only, so the warmed data is GC-eligible if the navigation never happens), `:blocking?` is **ignored** (a prefetch never blocks anything), and `:on-match` is **deliberately NOT run** (prefetch warms *data*, it does not activate the route — no side effects, no navigation). Paired surfaces: a `route-link` `:prefetch :intent` opt (warm on hover/focus intent), one trace op (`:rf.route/prefetched`, warm-mode marker), and a scorecard row. Deferred — not shipped in v1; hover-prefetch stays unavailable until post-v1, and the competitive gap vs TanStack/React Router stays open but is now on the record. The warm-mode design above makes the post-v1 flip a small, additive surface (one event + one link opt + one trace op) that does not disturb the activation-time `:resources` contract.

### Route-driven code loading — the `:load` seam (post-v1, seam only)

The **code-loading axis** — per-route code splitting, so a route's view code is not in the first-paint bundle until that route is reached — is named here so it stops being a blind spot. v1 ships no story for it, shipped *or* deferred *or* rejected: the [§The root view dispatches on `:rf.route/id`](#the-root-view-dispatches-on-rfrouteid) pattern resolves each route's leaf view at render time, which requires every route's view code loaded up front, and nothing in this spec speaks to lazily-loaded route code. React Router v7's route modules (`lazy:`) and TanStack Router's prefetchable chunks make "don't ship the admin panel to every visitor" a config line; re-frame2 apps that outgrow single-bundle today must hand-roll a host module loader (a CLJS app would reach for `shadow.lazy` plus a "module pending" sentinel) that races `:on-match` with no defined interaction.

CLJS whole-program optimisation plus gzip pushes the single-bundle wall much further out than in JS-ecosystem apps, and no example or user has hit it — so this is deliberately **seam-only, committing to nothing**. The shape a post-v1 revisit would consider (recorded so the axis is on the map, not blessed): a host-discretion route-metadata key `:load` naming a host-specific loadable (a `shadow.lazy` loadable, in the CLJS reference) that the runtime resolves *before* dispatching `:on-match`, folding into the existing transition protocol — `:rf.route/transition` stays `:loading` until the load and `:on-match` both settle, a load failure routes through the route's existing `:on-error` under a new `:rf.error/route-load-failed` category, a superseded in-flight load is discarded like any other stale async (per [§Navigation tokens](#navigation-tokens--stale-result-suppression)), and SSR ignores `:load` on the server side because server bundles are eager — the runtime already knows which side it is on (the `:server`/`:client` platform split, [011](011-SSR.md)). The contract, were it ever shipped, would be only the ordering and the error category — never a particular module system. Deferred until an app demonstrates the single-bundle cost; naming the seam now costs one item and commits the spec to nothing.

## Resolved decisions

### `:rf.route.nav-token/*` trace-operation namespace

The two nav-token trace operations — `:rf.route.nav-token/allocated` and `:rf.route.nav-token/stale-suppressed` (per [§Navigation tokens — stale-result suppression](#navigation-tokens--stale-result-suppression)) — live under `:rf.route.nav-token/*`. An earlier carve-out grandfathered the bare `:route.nav-token/*` prefix as the sole framework trace-operation namespace outside `:rf.*` (per the now-removed paragraph in [Conventions](Conventions.md)); closed that single-bit-of-difference exception, mechanically renaming all 91 occurrences across spec, conformance fixtures, implementation, docs, skills, and tools. The Conventions single-root rule (every framework-owned keyword sits under `:rf.*`) now holds without exception.

### URL ownership is an explicit declaration (no default-frame floor)

Per [§Multi-frame routing](#multi-frame-routing) a frame owns the browser URL only by carrying an explicit `(rf/reg-frame :id {:url-bound? true})`; the runtime enforces "only one frame can own the URL at a time" (registering a second `:url-bound? true` frame emits `:rf.error/duplicate-url-binding`). This was chosen over a "first frame to dispatch `:rf.route/navigate` wins" rule because explicit declaration is auditable at registration time and matches the story / devcard / per-test-fixture / SSR-per-request use cases without surprise.

EP-0002 removed the prior `:rf/default`-owns-the-URL-by-default floor: URL ownership was an *absence repair* (the default frame owned the URL unless it opted out), which the carried invariant forbids. Ownership is now a positive host/bootstrap policy. `:rf/default` may BE the URL owner, but only when it declares `:url-bound? true` like any other frame; an app with no declared owner simply has no URL owner (outbound history mutations no-op, the popstate listener skips). A migration that wants the old behaviour declares `(rf/reg-frame :rf/default {:url-bound? true})` at bootstrap.

### State-first, URL-second update order is locked

Per [§Pattern-level contract](#pattern-level-contract), navigation runs state changes first, then the URL update, then `:on-match` dispatches and the scroll effect. The order is locked: if the URL update fails (browser denies, user is offline) the state is still consistent. An earlier alternative — URL-first — was rejected because the runtime-db route slice becomes the source of truth for what URL the runtime *intends*; the `:rf.nav/push-url` fx is a downstream sync.

### Three-enum scroll strategy (`:top`, `:restore`, `:preserve`)

Per [§Scroll restoration](#scroll-restoration) the canonical scroll-strategy contract is the closed three-enum set. A custom scroll-strategy registry was considered but deferred to [§Open questions](#open-questions) — the three enums cover the documented cases (default-to-top on new navigation, restore on back/forward, preserve on intra-page transitions) and host-specific strategies layer additively via the map-form opt-in. Locking the enum keeps tools' enumeration of scroll behaviour decidable.

### `:rf.route/not-found` is the single canonical reserved id

Per [§Route-not-found — `:rf.route/not-found` (canonical)](#route-not-found--rfroutenot-found-canonical) the framework names exactly one reserved route id for unmatched URLs. Earlier sketches considered per-host customisation of the reserved id; the single-id rule was chosen because tools, conformance fixtures, and the `:rf.warning/no-not-found-route` trace event all depend on it. Apps that want per-error-kind visual treatment branch inside the `:rf.route/not-found` view on `:reason`.

### Run-to-completion enforced for navigation cascades

Per [§Pattern-level contract](#pattern-level-contract) the `:rf.route/navigate` cascade — state update, URL push, `:on-match` dispatches, scroll effect — settles inside a single drain. This matches [Spec 002 §Run-to-completion dispatch](002-Frames.md#run-to-completion-dispatch-drain-semantics) and was chosen over a multi-drain cascade so that subscribers see a consistent post-navigation state in one render pass rather than visible intermediate states.

## Cross-references

- [000-Vision §Working design implications](000-Vision.md#working-design-implications) — "routing is state plus events."
- [011-SSR.md](011-SSR.md) — SSR-side route resolution.
