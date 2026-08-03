# Spec 012 — Routing

> Routing is part of Goal #8 (Real SPA concerns are first-class) per [000-Vision.md](000-Vision.md).

## Abstract

Routing is **state plus events**, not a separate subsystem. The active route is a slice of frame-state — the durable **runtime-db** slice at `[:rf.runtime/routing :current]` — and the URL is a derivable view of it; navigation is an event. Browser back/forward, deep links, and SSR all flow through this single contract.

The principle: routing does not get its own runtime. It uses the runtime that already exists — frames, events, subs, the frame-state container (app-db + runtime-db). A route table is data; routes are registry entries; `:rf.route/navigate` is an event; `(rf/sub :rf/route)` derives the active route from the runtime-db route slice. Locating the slice in runtime-db (not app-db) keeps it out of the application's own contract while still riding frame revertibility, SSR hydration, and epoch restore for free (per [§`runtime-db` slices](#runtime-db-slices)). Nothing new at the foundation level.

## Normative surface inventory

The complete routing API surface, for quick audit. Each entry links to its normative definition below.

### Registration

- **`reg-route`** — registers a route. Canonical 3-slot grammar `(reg-route id metadata path)` (per [001 §Registration grammar](001-Registration.md#registration-grammar)): the URL **`:path` pattern is the third VALUE slot** (a route has no handler fn — its defining value is the pattern, the legitimate "handler-or-value" reading), and the middle slot is the pure reflection-metadata map. Reserved metadata keys: `:doc`, `:params`, `:query`, `:query-defaults`, `:tags`, `:parent`, `:on-match`, `:scroll`, `:can-leave`, `:can-enter`, `:sensitive`, `:large` (`:path` is the VALUE slot, not a metadata key — declaring it inside the metadata map is a loud `:rf.error/route-bad-metadata`). `:sensitive` / `:large` are projection-relative data classification — see [§Route data classification](#route-data-classification). See [§Reserved route-metadata keys](#reserved-route-metadata-keys) and [§Navigation blocking — pending-nav protocol](#navigation-blocking--pending-nav-protocol) for `:can-leave` / `:can-enter`. Returns its `id` argument per the family-wide [`reg-*` return-value convention](Conventions.md#reg--return-value-convention).
- **Path-pattern grammar** — five productions (literal, named param, optional segment group, splat, root). See [§Path-pattern grammar](#path-pattern-grammar-canonical).
- **Route ranking** — six-rule cascade for resolving overlapping matches. See [§Route ranking algorithm](#route-ranking-algorithm).

### `runtime-db` slices

Routing state splits into two tiers — **durable** runtime-db state under `[:rf.runtime/routing]` (per [Conventions §Reserved runtime-db keys](Conventions.md#reserved-runtime-db-keys)) and **host-side transient** caches (module-private per-frame atoms, **not** `runtime-db`):

- **Route slice** at `[:rf.runtime/routing :current]` — `{:route-id :params :query :fragment :transition :error :nav-token}`. Schema `:rf/route-slice`. Consumer-facing sub-id `:rf/route`. Durable runtime-db; rides the SSR hydration payload + restores coherently on epoch restore (the active `:nav-token` rides *with* the slice). See [§The `:rf/route` slice](#the-rfroute-slice).
- **Pending-nav slot** at `[:rf.runtime/routing :pending-navigation]` — populated when a `:can-leave` guard rejects. Schema `:rf/pending-navigation`. Sub-id `:rf/pending-navigation`. Runtime-db (subscribable + kept in local replay) but **SSR-stripped** (fail-closed allowlist ships only `:current`). See [§Navigation blocking — pending-nav protocol](#navigation-blocking--pending-nav-protocol).
- **Scroll-position LRU** — a host-side **transient** cache (module-private per-frame atom, **not** `runtime-db`); see [§Scroll restoration](#scroll-restoration).
- **Routing counters** (`:nav-token-counter` + `:pending-nav-counter`) — the internal monotonic allocators, held in a host-side **transient** cache (module-private per-frame atom, **not** `runtime-db`); not part of the consumer-facing sub surface. They are host-side specifically so an epoch restore — which replaces the `runtime-db` partition wholesale — cannot rewind them and recycle a token still carried by an in-flight async continuation; see [§Navigation tokens — stale-result suppression](#navigation-tokens--stale-result-suppression).

### Events

Audience column: **user** = an event apps dispatch or handle directly. Every v1 routing event is user-facing. The `:rf.route.internal/*` sub-namespace stays reserved for runtime-fired plumbing events — it has no members in v1 (EP-0037 R1 retired the readiness-settle and on-match error-trap internals), but the convention holds so a future internal event does not have to widen the tidy user-facing `:rf.route/*` surface. Apps and tools never dispatch `:rf.route.internal/*` events. The same audience-split principle scopes `:rf.route.nav-token/*` and `:rf.machine.internal/*`.

| Event | Audience | Purpose | Source |
|---|---|---|---|
| `:rf.route/navigate` | user | Programmatic navigation. | [§Navigation is an event](#navigation-is-an-event) |
| `:rf.route/handle-url-change` | user | URL-change handler for popstate / initial load / SSR (default scroll `:restore`). Co-equal with `:rf.route/transitioned`. | [§URL changes are events](#url-changes-are-events) |
| `:rf.route/transitioned` | user | URL-change handler for forward navigation (link click / programmatic push; default scroll `:top`). Co-equal with `:rf.route/handle-url-change`. | [§Standard runtime events](#standard-runtime-events) |
| `:rf.route/url-requested` | user | Fired by `route-link` and equivalent. Decides internal vs external navigation. | [§Standard runtime events](#standard-runtime-events) |
| `:rf.route/navigation-blocked` | user | Dispatched by the runtime when a `:can-leave` guard rejects — the resumable half. A framework no-op default handler ships, and it is a [replaceable framework default](#replaceable-framework-defaults) — an application registers its own under the same id. | [§Navigation blocking](#navigation-blocking--the-leave-only-pending-protocol) |
| `:rf.route/entry-denied` | user | Dispatched by the runtime when a `:can-enter` guard rejects — the TERMINAL half. Exactly once per navigation attempt; a framework no-op default handler ships, so denial is safe with no application handler, and it is a [replaceable framework default](#replaceable-framework-defaults) — an application registers its own under the same id through the ordinary `rf/reg-event`. | [§Entry is terminal](#entry-is-terminal) |
| `:rf.route/continue` | user | User-dispatched: confirm the pending LEAVE. Replays the stored destination + policy with a one-shot leave bypass. | [§Navigation blocking](#navigation-blocking--the-leave-only-pending-protocol) |
| `:rf.route/cancel` | user | User-dispatched: abandon the pending LEAVE. | [§Navigation blocking](#navigation-blocking--the-leave-only-pending-protocol) |
| `:rf.route/prefetch` | user | Warm-mode resource-only intent preload — runs a named destination's effective resource plan ownerlessly WITHOUT navigating (no route state, guards, `:on-match`, or readiness change). | [§Route-plan prefetch — warm-mode intent preload](#route-plan-prefetch--warm-mode-intent-preload) |

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
| `:rf.route/transition` | `:idle` / `:loading` / `:error` — the resource-derived readiness projection over the active plan (see [§Route readiness is a resource projection](#route-readiness-is-a-resource-projection)). |
| `:rf.route/error` | Structured planning / blocking-first-load error when `:transition = :error`, else nil. |
| `:rf.route/chain` | The `:parent`-chain of the active route. |
| `:rf/pending-navigation` | The pending-nav slot, or nil. |

### Pure helpers

Both live in `re-frame.routing` — they are **not** on the `rf/` (`re-frame.core`) facade; callers require `[re-frame.routing :as rf.routing]`.

- `(rf.routing/match-url url)` → `{:route-id :params :query :fragment :validation-failed?}` or `nil`. Pure; JVM- and CLJS-runnable.
- `(rf.routing/route-url {:to route-id :params path-params :query query-params :fragment fragment})` → URL string. Strictly address-only (`:url` / `:query-merge` / policy / unknown keys reject loud); no in-place form. Pure; JVM- and CLJS-runnable.

### Frame-level configuration

- `:url-bound?` on the frame config. URL ownership is an explicit declaration — a frame owns the URL only with `:url-bound? true`; there is no `:rf/default`-owns-by-default floor. Only one frame may own the URL. See [§Multi-frame routing](#multi-frame-routing).
- `:url-strategy` on the frame config. A map `{:encode :decode :push! :replace! :install-listener!}` that skins the router's path-form model onto the browser's chosen address-bar form. Default `history-url-strategy` (path-form); the framework also ships `hash-url-strategy` (`#`-prefixed). Consulted at exactly four egress/ingress points; `route-url` / `match-url` stay pure and path-form; SSR ignores strategies. See [§URL strategies](#url-strategies).

### Schemas registered with the framework

- `:rf/route-pattern` — path-pattern grammar (see [Spec-Schemas.md](Spec-Schemas.md#rfroute-pattern)).
- `:rf/route-rank` — structural rank tuple (see [Spec-Schemas.md](Spec-Schemas.md#rfroute-rank)).
- `:rf/route-slice` — the `:rf/route` slice shape (see [Spec-Schemas.md](Spec-Schemas.md#rfroute-slice)).
- `:rf/pending-navigation` — the pending-nav slot shape (see [Spec-Schemas.md §`:rf/pending-navigation`](Spec-Schemas.md#rfpending-navigation)).
- `:rf/route-address` — the closed caller-authored address `{:to :params :query :fragment}` every door resolves through (EP-0037 R0; see [Spec-Schemas.md §`:rf/route-address`](Spec-Schemas.md#rfroute-address) and [§The RouteAddress value](#the-routeaddress-value)).
- `:rf/route-destination` — the address / raw-URL replay union used by pending-leave and entry-denial payloads (EP-0037 R0; see [Spec-Schemas.md §`:rf/route-destination`](Spec-Schemas.md#rfroute-destination)).

### Trace events

Defined per the [009 Error contract](009-Instrumentation.md#error-contract):

- `:rf.route/registered` — first-time `reg-route`. Re-registration rides the cross-kind `:rf.registry/handler-replaced` trace; not re-emitted here. Mirrors the `:rf.flow/registered` symmetry.
- `:rf.route/cleared` — explicit `clear-route`. Mirrors the `:rf.flow/cleared` symmetry.
- `:rf.route/planned` — one per door **commit branch**: the R0 plan diagnostic projection, emitted just before the commit so the stream reads `planned → nav-token allocated → deactivated → activated`. This is what makes the projection reachable from an executed navigation rather than only from a tool holding a plan value (see [§Resolved target and the plan diagnostic projection](#resolved-target-and-the-plan-diagnostic-projection)). Carries `:tags {:cause <door> :route-id <id> :url <redacted-url> :param-keys [...] :query-keys [...] :branch [parent-most … leaf] :leaf-plan-ids [...] :frame <navigating-frame>}`. The `:cause` is the one thing that distinguishes the four sub-doors the URL-driven commit branch stands for (`:link` / `:popstate` / `:initial` / `:ssr`) from the programmatic `:navigate`. The `:url` rides the same URL-carrier redaction as the route-miss diagnostics; `:param-keys` / `:query-keys` are the **key sets** of the resolved params / query, never their values, and the plan's source address is not carried at all — the carrier discipline and the rejected alternatives are stated in full in [§Resolved target and the plan diagnostic projection](#resolved-target-and-the-plan-diagnostic-projection). `:leaf-plan-ids` are the leaf resource plan's event **ids**. One tag is conditional: `:branch-error {:kind … :route-id* …}` rides **only** on a failed `:parent` resolution, naming the failure kind and the offending route id, and is absent on every healthy navigation. An exact no-op and a fragment-only change commit no plan and emit none.
- `:rf.route/activated` / `:rf.route/deactivated` — fire on every cross-route navigation commit, in `deactivated → activated` order. Same-id navigation (path/query change with no route-id shift) emits neither. First-ever navigation emits `:rf.route/activated` only (no prior route). Both carry `:tags {:route-id <id> :frame <navigating-frame>}`.
- `:rf.route.nav-token/allocated` — a fresh navigation drain begins with a new nav-token. Carries `:tags {:route-id <id> :nav-token <token> :frame <navigating-frame>}`.
- `:rf.route.nav-token/stale-suppressed` — async result carrying a now-superseded token.
- `:rf.route/fragment-changed` — fragment-only URL update (the URL changed only in its `#fragment`; `:on-match` did not re-fire). Distinct from the runtime URL-change events `:rf.route/transitioned` / `:rf.route/handle-url-change`, which carry a full route transition. The op-name says what fires it (only a `#fragment` differed) and disambiguates from those runtime events.
- `:rf.route/prefetched` — the single summary trace a `:rf.route/prefetch` emits once per warm-mode preload (see [§Route-plan prefetch — warm-mode intent preload](#route-plan-prefetch--warm-mode-intent-preload)). Carries `:tags {:route-id <destination-id> :warmed <n> :frame <dispatching-frame>}` — `:warmed` is the count of unique effective-plan requirements run through Resources in warm mode (`0` when Resources is absent or the plan is empty). A planning failure additionally sets `:plan-error true`; the underlying resource plan / ensure traces carry `:plan-cause :prefetch` and no nav-token (per [016 §Route integration](016-Resources.md#route-integration)). It is **not** an activation trace: no `:rf.route/activated` / `:rf.route.nav-token/allocated` pair fires for a prefetch.
- `:rf.route/navigation-blocked` — `:can-leave` guard rejected a navigation; a resumable leave-only pending value was written. Carries `:tags {:requested-url :rejecting-route :rejecting-guard :cause :phase :can-leave :frame}`.
- `:rf.route/entry-denied` — `:can-enter` guard rejected a navigation. TERMINAL: nothing committed, no pending value written. Carries `:tags {:requested-url :rejecting-route :rejecting-guard :cause :phase :can-enter :frame}`.
- `:rf.error/can-leave-non-boolean` — `:can-leave` sub returned a non-boolean value; the runtime BLOCKED the navigation. Closed contract; see [§Navigation blocking — pending-nav protocol](#navigation-blocking--pending-nav-protocol).
- `:rf.error/can-enter-non-boolean` — `:can-enter` sub returned a non-boolean value; the runtime DENIED the entry. Closed contract mirror of `:rf.error/can-leave-non-boolean`.
- `:rf.error/duplicate-url-binding` — second frame attempted `:url-bound? true` while another already owns the URL.
- `:rf.error/route-bad-metadata` — `reg-route` was passed a bare metadata key outside the reserved set (a likely typo), or non-map metadata. Thrown at registration (caller bug; dev *and* prod). Names the offending `:keys` and the `:reserved` vocabulary. See [§Authoring-boundary key validation](#authoring-boundary-key-validation).
- `:rf.error/invalid-route-pattern` — `reg-route`'s `:path` value violated the [path-pattern grammar](#path-pattern-grammar-canonical): a missing leading `/`, an empty segment, an invalid param/splat name, a reserved char not percent-encoded, a malformed optional group (unclosed, empty, nested, containing a splat, or spelled slash-**outside** `{:name}?` instead of the canonical slash-inside `{/:name}?`), or more than one splat / a non-final splat. Thrown at registration on the first violation (caller bug; dev *and* prod), before any state mutates. Names the `:route-id`, the `:pattern`, and the offending `:index`. `:where 'rf/reg-route`, `:recovery :no-recovery`. (The full error contract lives in [009 §Error event catalogue](009-Instrumentation.md#error-event-catalogue).)
- `:rf.error/invalid-route-classification` — `reg-route`'s `:sensitive` / `:large` data-classification declaration is structurally malformed (a non-vector axis, a non-sequential path entry, or a non-EDN-identity path segment). Thrown at registration (caller bug; dev *and* prod), before any state mutates and before the route can activate. Names the offending `:axis` and `:bad-path`; a bad segment surfaces the inner `:rf.error/bad-path` under `:rf.error/cause`. `:where 'rf/reg-route`, `:recovery :fix-route-classification`. See [§Route data classification](#route-data-classification).
- `:rf.error/unsupported-scroll-strategy` — the `:rf.nav/scroll` fx was handed a `:strategy` outside the closed `:top` / `:restore` / `:preserve` vocabulary (classically a map, the form earlier drafts advertised as host-extensible). No scroll is performed and the navigation is otherwise unaffected. The dev trace names the offending `:strategy`; the always-on record — which ships off-box and bypasses the elision seam — carries only the `:supported` set and a closed-vocabulary `:strategy-type` shape tag, never the value itself (rf2-s3n6h). See [§Scroll restoration](#scroll-restoration) and [§Custom scroll strategies](#custom-scroll-strategies).
- `:rf.error/navigate-bad-request` — `[:rf.route/navigate {request}]` carried a structurally-invalid request map. The always-on structural gate rejected it BEFORE any guard ran (slice unchanged, no push); `:where :event`, `:reason` names the first violation. See [§Validity rules](#validity-rules--the-always-on-structural-gate).
- `:rf.error/prefetch-bad-address` — `[:rf.route/prefetch {address}]` carried an address prefetch will not plan against: either it is not a closed `:rf/route-address` (a missing / non-keyword `:to`, a raw `:url` escape, a policy / edit key, or any unknown key), or it is well-formed but its named destination does not **resolve** (an unregistered `:to`, a missing required path param, or `:params` / `:query` that fail the route's declared schemas). Both gates run BEFORE any planning — no ensures dispatched, no summary trace, current route readiness untouched; `:where :event`, `:reason` names the first violation. Distinct from a resource *planning* failure, which is a well-formed address whose plan could not be built and surfaces as the ordinary `:rf.error/resource-route-plan` diagnostic with `:plan-cause :prefetch`. See [§Route-plan prefetch — warm-mode intent preload](#route-plan-prefetch--warm-mode-intent-preload).
- `:rf.warning/route-shadowed-by-equal-score` — registration-time warning when ranking ties on rule 6 **between co-matchable patterns** (some URL matches both — equal structural rank alone never warns; see rule 6 in [§Route ranking algorithm](#route-ranking-algorithm)).
- `:rf.warning/no-not-found-route` — runtime fell back to the built-in placeholder because `:rf.route/not-found` is not registered (per [§Route-not-found](#route-not-found--rfroutenot-found-canonical)).

**Frame attribution.** Every routing trace emitted from inside a known navigation drain carries `:tags :frame` — the in-flight drain's frame, validated at the handler boundary by `frame/require-frame-stamp!` and threaded to the emit site. This is load-bearing, not cosmetic: `re-frame.epoch.capture` admits ONLY frame-tagged traces into a run's `:trace-events` (an untagged trace silently drops from epoch history / Xray), and the frame-level trace-disable gate (a `:rf.trace/frame-no-emit?` tool frame) keys suppression off `:tags :frame` (an untagged trace leaks into the very ring the inspector reads). The frame-known emit sites are the plan trace (`:rf.route/planned`), the lifecycle / nav-token traces (`:rf.route/activated`, `:rf.route/deactivated`, `:rf.route.nav-token/allocated`), the leave-guard / blockage diagnostics (`:rf.error/can-leave-non-boolean`, `:rf.warning/can-leave-subs-artefact-missing`, `:rf.route/navigation-blocked`), and the external-URL diagnostic (`:rf.route/external-url-requested`, on both the `:rf.route/url-requested` and programmatic `:rf.route/navigate {:url …}` paths). The route-miss / malformed-URL diagnostics already carried `:frame`.

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
2. **Optional groups** wrap a slash-prefixed sub-pattern in `{...}?` — the slash lives **INSIDE** the braces (`{/:slug}?`, `{/guide}?`, `{/:base}?`). This is the **one canonical spelling**: the group is a self-contained optional segment, so it composes in any position — it may **lead** (`{/:base}?/about`), **trail** (`/articles/:id{/:slug}?`), or **sequence** (`/docs{/:section}?{/:page}?`). The slash-**outside** spelling (`/{:base}?/about`, where the `/` is a literal outside the braces) is **not** part of the grammar and is rejected at registration — it is the shape that orphans a separator on elision (`/about` → `//about`, a protocol-relative URL). A group may contain literal segments, named params, or both; nested optional groups are not part of the grammar; a group may not contain a splat.
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
6. **Registration order is the final tiebreak only if every structural score is equal.** When two routes are *structurally indistinguishable* (same statics, same length, same params/splats, same optional groups), the route registered first wins. This case is **discouraged** — implementations MUST emit a `:rf.warning/route-shadowed-by-equal-score` warning at registration time when a new route is added and an existing route has an equal structural score **on the same URL family — i.e. some URL matches both patterns** (co-matchability, decided exactly by language intersection over the two patterns' segment atoms: a literal matches its canonical text, a `:name` param matches any one segment, a final splat matches one-or-more remaining segments, an optional group contributes its inner segments or nothing). Equal structural score alone MUST NOT warn: rank tuples ignore literal segment text, so every same-shape pair ties (`/x/:id` vs `/y/:slug`, `/home` vs `/about`) while presenting no ambiguity — this tiebreak can never fire between routes that cannot co-match, and warning on the bare tie would flood every ordinary multi-page app with a spurious warning per static route. Note co-matchability is **not** "corresponding literal segments are equal" — optional groups shift positions (`/a{/x}?/b` and `/a/x{/b}?` share no literal column yet both match `/a/x/b`), and params cross positions (`/x/:id` and `/:kind/y` intersect at `/x/y`). Because the earlier registration wins, the NEW route is the shadowed one: the warning's `:tags` name it under `:route-id`, the existing winner under `:shadowed-by`, and the tied structural tuple under `:rank` (see [009 §Error event catalogue](009-Instrumentation.md#error-event-catalogue)). Tooling and AI scaffolds use the warning to flag genuine conflicts.

The cascade is **structural** — the score is computable from each pattern's parsed shape; no URL is needed to compute it. Implementations may pre-compute every registered route's score at registration time and rank candidates by score on each `match-url` call.

```text
;; Pseudocode
(defn route-rank [pattern]
  (let [segments       (parse-segments pattern)
        ;; Segments INSIDE an optional group are excluded from BOTH
        ;; static-count (rule 1) and total-length (rule 3): a maybe-present
        ;; segment must never outrank an always-present one. Only
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

The pattern reserves twelve keys on `reg-route`'s metadata map, plus the URL `:path` pattern in the third VALUE slot (`:path` is not a metadata key; it is the canonical 3-slot value). All metadata keys are optional. This is the largest registration shape in the v2 surface — for context, `reg-flow` carries six keys total ([013 §The registration shape](013-Flows.md#the-registration-shape)) and `reg-event` reserves only the cross-kind registration metadata. The scale reflects the cross-cutting concerns routing absorbs (URL ↔ params, query/path separation, lifecycle hooks at navigation boundaries, layout chains, scroll behaviour, data classification); the keys do not cluster naturally as one flat list. The four axes below name the clusters (the **Shape** axis additionally carries the value-slot `:path`) so generators reading "what does `reg-route` accept?" can branch on intent rather than scan the docstrings.

##### The four axes

The keys cluster into four axes by what each controls (the value-slot `:path` belongs to the **Shape** axis):

| Axis | Keys | What it controls |
|---|---|---|
| **Shape** — URL ↔ params binding | `:path` (the VALUE slot), `:params`, `:query`, `:query-defaults` | What URLs match this route and how their parts coerce into a params/query map. The contract surface that `match-url` and `route-url` agree on. `:path` is the third positional VALUE; the rest are metadata keys. |
| **Lifecycle hooks** — events the runtime dispatches at navigation boundaries | `:on-match`, `:can-leave`, `:can-enter` | Events the runtime fires-and-forgets on a *successful* route activation (`:on-match`; see [§Per-route data loading](#per-route-data-loading)), a sub-id consulted before navigation **away** from this route (`:can-leave`), and a sub-id consulted before navigation **into** this route (`:can-enter` — the first-class mirror). These are the route's reactive surface — handlers run from app code, the runtime owns the dispatch points. `:on-match` does not drive route readiness or carry an error hook — managed page-read readiness is declared with `:resources` (see [§Route readiness is a resource projection](#route-readiness-is-a-resource-projection)). |
| **Layout** — how the route fits with neighbours | `:doc`, `:parent`, `:tags`, `:scroll` | How the route is described (`:doc`), composed with others (`:parent` chains layout shells; see [§Nested layouts](#nested-layouts)), grouped for interceptors (`:tags`), and visually transitioned (`:scroll`; see [§Scroll restoration](#scroll-restoration)). |
| **Classification** — data hygiene | `:sensitive`, `:large` | Projection-relative paths (rooted at the route's `{:query … :params …}` projection) whose values are redacted (`:sensitive`) or kept off the wire (`:large`) at egress while the route is active. Lowered into the per-frame elision registry at activation, dropped on route change. See [§Route data classification](#route-data-classification). |

The axes are documentation, not data structure — the keys remain flat on the metadata map. An earlier sketch considered nesting lifecycle hooks under `:hooks {...}`; v1 keeps the flat shape because (a) the registration metadata is read by `(rf/handler-meta :route id)` and tools enumerate top-level keys; nesting would require every consumer to know the nesting; (b) the v1 surface is settled, a nested shape is a v2.x candidate at most. The cluster headings are the carry — a generator scaffolding a route picks the axis first, then the keys.

##### Authoring-boundary key validation

Because `reg-route` carries the largest shape in the surface, a typo'd key (`:on-matched` for `:on-match`, `:querey` for `:query`) would otherwise be silently accepted and fail later at nav-time, or never — a silent-swallow that costs a debugging session. `reg-route` therefore **validates the metadata at the authoring boundary**: a **bare** (unqualified) key outside the reserved metadata keys is rejected at registration with a thrown `:rf.error/route-bad-metadata` (canonical thrown-error shape per [009 §The thrown-error shape](009-Instrumentation.md#the-thrown-error-shape--the-rferrorid-ex-data-contract); `:where 'rf/reg-route`), whose `:keys` slot names every offending key and `:reserved` slot carries the valid vocabulary. A **`:path` left INSIDE the metadata map** is rejected the same way — `:path` is the third VALUE slot, not a metadata key. This is a **caller bug**, so it throws in dev *and* prod (it is not user input). Non-map metadata is rejected the same way, naming the route.

**Namespaced keys are exempt.** Route metadata is an open map for host/app extension keys, but only under a namespace (`:myapp/analytics-id`, `:myapp/layout`) — see [§Other pattern-level requirements](#other-pattern-level-requirements). Bare keys are the framework's reserved vocabulary; namespaced keys are the extension surface. The split makes the typo case (a bare key) distinguishable from the extension case (a namespaced key) without a registry of permitted host keys.

**Cross-feature reserved keys.** A small number of bare keys are reserved by *other* framework features that extend route metadata — `:head`, owned by SSR ([011 §Head/meta contract](011-SSR.md#headmeta-contract): "routes name which head to use via `:head` route metadata"), and `:resources`, owned by the [Resources artefact](016-Resources.md#route-integration) (Spec 016 §Route integration: declarative server-state metadata layered beside `:on-match`). These pass the guard because the framework owns them, even though they are not among the routing-owned metadata keys above. The accepted-key set is therefore the routing-owned metadata keys plus the enumerated cross-feature keys; a new framework feature that adds a bare route-metadata key adds it to that set. The two keys ride two mechanisms: `:head` is statically enumerated in routing's reserved-key set (SSR is consulted at render, not at registration), while `:resources` is **late-bound** — routing's accepted-key set is the routing-owned + static cross-feature keys UNIONed with whatever an artefact publishes under the `:routing/extra-route-keys` hook (the Resources artefact publishes `#{:resources}`), so a route declaring `:resources` in an app that omits the Resources artefact is correctly rejected. The same late-bound seam carries the resources route-entry plan (`:routing/on-route-entry`) and the warm-mode prefetch plan (`:routing/on-route-prefetch`); routing never statically requires the Resources artefact. There is no blocking-transition *predicate* on the seam — under [§Route readiness is a resource projection](#route-readiness-is-a-resource-projection) routing runs no settle step, so it never asks the Resources artefact whether the route is still blocked.

##### Per-key table

Thirteen rows for twelve metadata keys: the `:path` row is the third positional VALUE slot, listed here so the whole `reg-route` shape reads in one table.

| Key | Axis | Type | Purpose |
|---|---|---|---|
| `:doc` | layout | string | Human-readable description. |
| `:path` | shape | string (path-pattern grammar above) | The URL pattern. The **third VALUE slot**, NOT a metadata key — `(reg-route id {…metadata…} "/path")`. Required (positionally). |
| `:params` | shape | schema | Schema for **path** params (those captured by `:name` / `*name` segments in `:path`). |
| `:query` | shape | schema | Schema for **search/query** params (key-value pairs after `?`). Distinct from `:params`. See "Query strings and fragments". |
| `:query-defaults` | shape | map | Default values for query-string keys when absent. Applied when the **target is resolved** — by `match-url` for the URL-bearing doors and by the one resolved-target seam for the named-address doors, so every door resolves the same `:query`. A key already at its default is **not emitted** into the URL. See "Query strings and fragments". |
| `:tags` | layout | set of keywords | User-defined tags (e.g. `:requires-auth`); read by interceptors. |
| `:parent` | layout | route-id | Parent route id (for the nested-layout convention; see "Nested layouts"). |
| `:on-match` | lifecycle | vector of event vectors | Events the runtime **fires-and-forgets**, in order, after a *successful* full activation (server- and client-side); a committed planning-failure target dispatches none of them. It never drives route readiness or awaits async tails. See "Per-route data loading". |
| `:can-leave` | lifecycle | sub-id | A subscription whose value (boolean) gates navigation **away from** this route. The sub receives the pending target as an argument (`(fn [inputs [_ target] …])`). **Strict contract**: `true` allows, `false` blocks, any other value blocks AND emits `:rf.error/can-leave-non-boolean`. See [§Navigation blocking — pending-nav protocol](#navigation-blocking--pending-nav-protocol). |
| `:can-enter` | lifecycle | sub-id | A subscription whose value (boolean) gates navigation **into** this route. The sub receives the resolved target as an argument. **Strict contract**: `true` allows, `false` denies, any other value denies AND emits `:rf.error/can-enter-non-boolean`. Denial is **terminal** — nothing commits, no pending value is created, and the runtime dispatches `:rf.route/entry-denied` once. See [§Entry is terminal](#entry-is-terminal). |
| `:scroll` | layout | enum | Declarative scroll behaviour on entering this route — `:top` / `:restore` / `:preserve`, or `false` to suppress the effect. Closed vocabulary. See "Scroll restoration". |
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

`:transition` (`:idle` / `:loading` / `:error`) and `:error` are **not** a runtime-driven FSM over the `:on-match` drain. They are the **resource-derived readiness projection** over the active route plan: `:loading` while a blocking first load is pending, `:error` when the plan could not be formed or a blocking first load failed, `:idle` otherwise (including when the route declares no resources). `:on-match` never moves them. They ride in the stored slice for ergonomic whole-route reads, but a runtime that caches them there keeps that cache reconstructible from the plan plus managed-resource state and reconciles it through one pure projector. See [§Route readiness is a resource projection](#route-readiness-is-a-resource-projection) below.

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

The empty path `[]` is legal and marks the whole route projection. (Query keys are promoted to keyword keys only when the route declares them in its `:query` schema / `:query-defaults` — see [§Keyword-interning cap](#keyword-interning-cap-on-query-keys--values); a `:sensitive [[:query :token]]` declaration therefore pairs with a `:query` schema that names `:token` so the slice carries the keyword key the path targets.)

> **Query-key promotion advisory.** Because the slice keys an *undeclared* query key as a **string** while a `[:query :token]` classification path names the **keyword** `:token`, a classification path for a query key the route does not promote *silently fails open* — the keyword path never matches the runtime string key, and the value ships raw with no signal (the hygiene bargain, not a security boundary). To close this authoring footgun, `reg-route` emits a `:rf.warning/route-classification-query-key-unpromoted` **advisory trace** (a warning, never a throw — fail-open is intended) when a `:sensitive` / `:large` `[:query k]` path names a query key the route's `:query` / `:query-defaults` vocabulary does not promote. The advisory names the offending key(s) and the fix (add the key to the `:query` schema). A **string-segment** classification path (`:sensitive [[:query "token"]]`) is accepted as concrete EDN at registration but can *never* match a keyword-promoted slot, so it is reported too. Path **params** are immune (path captures are always keyword-keyed), so the advisory is query-axis-only.

#### Lowering and re-rooting

At route activation the projection-relative paths are **re-rooted** under `[:rf.runtime/routing :current …]` — a declared `[:query :token]` classifies the runtime path `[:rf.runtime/routing :current :query :token]` — and installed into the frame's durable elision registry (`[:rf.runtime/elision …]`, per [Conventions §Reserved runtime-db keys](Conventions.md#reserved-runtime-db-keys)) tagged `:source :route`. The install lands **atomically with the slice publish** (the same `:rf.db/runtime` commit), so a route's classification protects its slice from the moment it activates. Classification is **value-independent** and read **only at egress** — the handler, subs, and views always see the real values while the route is active; what the registry governs is every egress copy of the **classified runtime-db paths** (per [015 §Data classification](015-Data-Classification.md)): the `:rf/route` / `:rf.route/query` / `:rf.route/params` read surfaces in the seed table below, the trace / Xray / MCP reads over them, and the SSR hydration projection of the routing slice.

**It does not cover arbitrary trace tags.** A route's declaration names *paths in its slice*, so it reaches a trace only where the trace carries a copy of a classified path — never an unrelated tag map on the trace bus. Ambient traces from the same navigation stay wide open by design: the event vector on `:rf.event/dispatched` (`[:rf.route/navigate {:query {:invite "…"}}]`), the fx args on `:rf.fx/handled` (`:rf.nav/push-url`'s URL string, `:rf.nav/scroll`'s target address), and a route plan's resource identities all carry the query value verbatim, because each of those slots is governed by **its own registration's** `:sensitive` declaration ([015 §Registration-owned transient classification](015-Data-Classification.md#registration-owned-transient-classification)) rather than by the route's. That is the same fail-open direction as a forgotten declaration — the hygiene bargain, not a security boundary. The one routing trace that declines to carry those carriers ([§Resolved target and the plan diagnostic projection](#resolved-target-and-the-plan-diagnostic-projection)) does so as local hygiene at its own emit site, not because the classification reached it.

#### Egress of the route read surfaces (the re-rooting and the bare slice)

The route classification is stored **runtime-db-absolute** (`[:rf.runtime/routing :current :query :token]`), but the framework route subs return a **bare projection** of that slice — `:rf/route` returns the whole `{:route-id :params :query …}` map, `:rf.route/query` the bare `:query` map, `:rf.route/params` the bare `:params` map. A naive egress walk of a bare sub value is rooted at the **whole value** (path `[]`), where the registry's absolute declaration can never match — so the classified `:query` / `:params` would ship **raw** on every read surface that copies the slice off the live value (the `:rf.sub/run` dev-trace, Xray, and the Tool-Pair MCP `read-sub` / `list-subscriptions :include-values` / `snapshot :sub-cache` reads).

To honour the re-rooting, those framework route read subs are treated as **alternate projections of the route-owned durable fact**: their egress copy is walked **re-seeded at the slice's runtime-db storage position** (`:rf/route` at `[:rf.runtime/routing :current]`, `:rf.route/query` at `[… :current :query]`, `:rf.route/params` at `[… :current :params]`), so the candidate declaration-coordinate set starts where the re-rooted `:source :route` decls live and the bare value matches exactly. This is the direct-read sibling of the **SSR hydration** projection of the routing slice (which seeds at the offset `[:rf.runtime/routing]`). The re-seeding is keyed off a small framework **seed table** — only the route-owned read surfaces above; the derived `:rf.route/id` / `:transition` / `:error` / `:fragment` / `:chain` subs carry no classifiable secret and ride verbatim.

This is **not** generic sub-output propagation (there is none): an *app* sub that reads route values into a re-keyed shape is **fail-open** (ships raw — classify its own app-db path to cover it), exactly as [015 §What is removed](015-Data-Classification.md) disclaims for re-keyed copies. Only the framework's own route read surfaces — which are *the route slice under another name* — get this treatment, and only at egress: in-process `@(rf/subscribe [:rf/route])` always returns the real values (the handler, views, and app subs need them).

#### Singleton drop (no leak across a route change)

Because a route is a singleton, activation **replaces** the prior route's `:source :route` entries: a route change drops the leaving route's classification, and a navigation to a route that declares none (including `:rf.route/not-found`) clears the route-sourced entries entirely. Other sources in the registry (`:source :effect` from the [commit-plane classification effects](015-Data-Classification.md#durable-app-db--the-four-commit-plane-effects), `:source :flow`, `:source :machine`) survive untouched and union at egress-lookup time. Frame teardown drops the whole runtime-db elision slot with the frame, so no separate teardown is needed.

#### Sensitive wins over large

A path declared **both** `:sensitive` and `:large` lowers as sensitive **only** — its large entry is dropped at lowering time, so no `:rf.size/large-elided` marker (which would leak path / byte size / digest) is ever emitted for it. This is the install-time complement of the elision walker's sensitive-before-large ordering, identical to the [frame classification rule](002-Frames.md).

#### Fail-loud at registration

A **malformed** declaration (a non-vector axis, a non-sequential path entry, a non-EDN-identity path segment) **fails loudly at `reg-route` time** — before any state mutates and before the route can ever activate — with the canonical thrown-error shape (per [009 §The thrown-error shape](009-Instrumentation.md#the-thrown-error-shape--the-rferrorid-ex-data-contract)) carrying `:rf.error/id :rf.error/invalid-route-classification` (`:where 'rf/reg-route`, `:recovery :fix-route-classification`), naming the offending `:axis` and `:bad-path`. A non-EDN segment surfaces the inner `:rf.error/bad-path` cause under `:rf.error/cause`. A **forgotten** classification is fail-open (the value ships raw — the hygiene bargain, not a security boundary).

### Navigation is an event

Programmatic navigation is one event carrying ONE flat **request map**:

```clojure
;; The route slice is framework-owned RUNTIME-DB state, so this framework route
;; handler reads it from the `:rf.db/runtime` coeffect and writes it back through
;; the reserved `:rf.db/runtime` effect — NOT the app `:db` effect — exactly like
;; the co-equal `:rf.route/handle-url-change` handler below. The route registrar
;; mints a framework-authority handler, so emitting `:rf.db/runtime` is in-bounds
;; (per [002 §Write authority is by convention]).
(rf/reg-event :rf.route/navigate
  {:doc "Navigate to a registered route."}
  (fn handler-route-navigate [{rt :rf.db/runtime} [_ request]]
    ;; 1. The ALWAYS-ON structural gate (`validate-request`) rejects a
    ;;    malformed request BEFORE any guard runs — closed key roster, the
    ;;    exclusions, and the destination-or-change rule (see §Validity rules).
    ;;    A violation emits `:rf.error/navigate-bad-request` and returns `{}`.
    ;; 2. `resolve-target` (pure) turns the request into a
    ;;    `{:route-id :path-params :query-params :fragment}` shape — a FRESH
    ;;    address for a destination request, a PATCH of the current location for
    ;;    an in-place request (see §In-place navigation).
    ;; 3. `route-url` builds the URL; a caller-bug throw rejects with
    ;;    `:rf.error/schema-validation-failure` (`:where :event`).
    (let [current (get-in rt [:rf.runtime/routing :current])]
      (if-let [bad (validate-request request current)]
        (do (rf/emit-error! :rf.error/navigate-bad-request
                            {:where :event :reason (:reason bad) :keys (:keys bad)})
            {})
        (let [{:keys [route-id path-params query-params fragment]} (resolve-target request current)
              route-meta (rf/handler-meta :route route-id)
              url        (rf.routing/route-url {:to route-id :params path-params
                                                :query query-params :fragment fragment})
              push-fx-id (if (:replace? request) :rf.nav/replace-url :rf.nav/push-url)
              nav-token  (rf/gen-nav-token)]
          {:rf.db/runtime (-> rt
                   (assoc-in [:rf.runtime/routing :current]
                             {:route-id   route-id
                              :params     path-params
                              :query      query-params
                              :fragment   fragment
                              :transition :idle    ;; resource-derived readiness projection derives :loading/:error from the plan
                              :error      nil
                              :nav-token  nav-token}))
           :fx (into [[push-fx-id url]
                      [:rf/trace [:rf.route.nav-token/allocated {:route-id route-id :nav-token nav-token}]]
                      (when-let [scroll (resolve-scroll route-meta request fragment)]
                        [:rf.nav/scroll scroll])]
                     ;; per-route :on-match fire-and-forget dispatches (see "Per-route data loading")
                     (for [ev (:on-match route-meta)]
                       [:dispatch ev]))})))))
```

Three effect categories flow:
1. The runtime-db `:rf/route` slice (at `[:rf.runtime/routing :current]`) is updated (id, params, query, fragment, transition, nav-token).
2. The browser URL is pushed via `:rf.nav/push-url` (a registered fx; `:platforms #{:client}`), or replaced via `:rf.nav/replace-url` when the request carries `:replace? true`.
3. The route's `:on-match` events (if any) are dispatched, and the route's `:scroll` strategy (if any) is emitted as a `:rf.nav/scroll` effect.

The order is locked: state changes first, URL update second, then `:on-match` dispatches and scroll effect. If the URL update fails (browser denies, user is offline) the state is still consistent.

<a id="the-navigate-opts--canonical-list"></a>
<a id="the-request-grammar"></a>
#### The request grammar — address, policy, and edit keys

There is **one positional-free grammar**: a single map. This table is the **single canonical enumeration** of the keys the runtime recognises — the docs guide references it rather than re-listing, so the two never drift. Any key outside this roster (**namespaced included** — `:my-app/replace?` fails as loudly as `:replcae?`) is rejected by the structural gate.

| Key | Kind | Effect |
|---|---|---|
| `:to` | address | The **route id** to navigate to (a registered route keyword). Mutually exclusive with `:url`. |
| `:url` | address | A raw **URL string** (the escape hatch — deep-link handlers, server-redirect targets, programmatic redirects from a string). A raw URL IS the address, so `:url` excludes `:params`, `:query`, and `:query-merge`; `:url` + `:fragment` is legal (the request's `:fragment` overrides a URL-embedded one). |
| `:params` | address | The **path params** for the target route (`{:id 42}` → `/articles/42`). Coerced/validated against the route's `:params` schema. |
| `:query` | address | The **query-string params** — coerced against the route's `:query` schema, emitted as `?key=value`. On an in-place request `:query` **replaces** the current query wholesale (`{}` clears it). Mutually exclusive with `:query-merge`. |
| `:fragment` | address | Target `#fragment` for the new URL (see [§Fragments](#fragments)). `:fragment nil` clears it; on an in-place request an omitted `:fragment` carries the current one. |
| `:replace?` | policy | Use `replaceState` rather than `pushState` — for redirects, search-as-you-type filters, and login-flow returns where the back button should not land on the intermediate URL. |
| `:scroll` | policy | Per-call override of the route's `:scroll` metadata; same closed enum (see [§Scroll restoration](#scroll-restoration)). |
| `:bypass-leave?` | policy | A plain **boolean**. `true` skips the *current* route's `:can-leave` confirmation for this one navigation — the explicit trusted-programmer escape for workflow code that must move without asking (the ordinary confirmation path is [`:rf.route/continue`](#navigation-blocking--the-leave-only-pending-protocol)). There is **no** entry bypass: entry is [terminal](#entry-is-terminal), so there is nothing to skip past. Only the literal `true` bypasses. |
| `:query-merge` | edit | Fold the given deltas into the **current** route's `:query` slice, rather than replacing it — the "stay here, change these query params" primitive (search, pagination, tabs). A `nil` value **removes** a key (matching `route-url`'s query nil-elision; see [§Bidirectional URL ↔ params](#bidirectional-url--params)). Requires an **in-place** request (no `:to` / `:url`). See [§In-place navigation](#in-place-navigation). |

Hosts and apps may **not** add their own keys — the roster is closed, with **no exemption**. (EP-0037 R4 retired the enter-resume protocol and with it the runtime's `:rf.route/enter-attempts` rider; a request carrying it now rejects as an ordinary unknown key.) The runtime's remaining internal riders live on the URL-driven doors' trailing opts map, not on this roster, and both exist so a door does not have to guess something it already knows:

- [`:rf.route/url-requested`](#navigation-blocking--the-leave-only-pending-protocol) stamps `:rf.route/decided? true` on the `:rf.route/transitioned` event it synthesises after deciding, so the same target is not decided twice.
- The `:url-bound?` frame's history listener stamps `:rf.route/cause` on the `:rf.route/handle-url-change` event it dispatches — `:popstate` from the browser-driven callback, `:initial` from the same listener's initial URL sync. One event stands for three doors ([§URL changes are events](#url-changes-are-events)), so without the rider the `:initial` and `:ssr` causes would be unreachable and cause-specific diagnostics would misreport two of the five doors. Only a member of the closed cause set is honoured. The SSR feed carries no rider — it is the application's own `:initial-events` dispatch ([§Server-side rendering integration](#server-side-rendering-integration)) — and resolves to `:ssr` from the frame's `:platform :server`, the same read that gates the SSR 403 floor; a rider-free dispatch on a client frame is the initial / direct-URL feed.

<a id="validity-rules--the-always-on-structural-gate"></a>
#### Validity rules — the always-on structural gate

Event `:schema` validation is **dev-only and schemas-optional** (it is gated on `debug-enabled?` and late-bound on the schemas artefact), so a Malli schema alone would protect FEWER builds than the check it replaces. The handler therefore carries a small **always-on structural gate**: closed key-roster membership, the exclusions below, and the destination-or-change rule — plain set logic, total over the roster. The Malli `reg-event` `:schema` **may** ride alongside as a dev-time rich explainer; the gate is the production-surviving enforcement. The gate rejects **before any guard evaluation** (as the URL-driven path's checks do), so a malformed request never consumes a `:can-leave` run.

A malformed request emits **`:rf.error/navigate-bad-request`** (`:where :event`, a `:reason` discriminator, and `:keys` naming the offending keys) and returns `{}` — the slice is unchanged, no URL is pushed. This is deliberately **not** `:rf.error/schema-validation-failure`: that category is the schemas-artefact-gated validation channel and reports on the dev-only trace bus, while this gate is plain set logic, total over the roster and armed on every build. The rules (the `:reason` value in parentheses):

1. **`:to` xor `:url`** — supplying both rejects (`:to-url-exclusive`).
2. **`:url` excludes `:params`, `:query`, `:query-merge`** — a raw URL IS the address; letting address keys ride beside it and be silently ignored is the exact failure class this grammar exists to kill (`:url-excludes-address`). `:url` + `:fragment` is legal.
3. **`:query` xor `:query-merge`** — one replaces wholesale, one edits (`:query-exclusive`).
4. **`:query-merge` requires an in-place request** (no `:to` / `:url`) (`:query-merge-in-place-only`). Cross-route query carry is DELETED — carrying state into another route's query is the application's own [explicit fold over the destination address](#carrying-query-state-across-routes), not the caller's imperative choice and not route metadata.
5. **A destination (`:to` / `:url`) OR an in-place change is required** — discriminated by key PRESENCE, not truthiness: `:query {}` (clear the query) and `:fragment nil` (clear the fragment) are valid lone in-place requests. Empty maps (`{}`) and pure-policy maps (`{:replace? true}`) reject (`:no-destination-or-change`).
6. **An in-place request before any current route exists rejects** — there is nothing to patch (`:no-current-route`).
7. **Unknown keys reject, NAMESPACED INCLUDED** — full closure (`:unknown-keys`).
8. **`:params` requires a destination** — `:params` names path params for a FRESH address, so it is only valid beside `:to` (or embedded in a `:url`); supplying it on an in-place request rejects (`:params-requires-destination`). Changing params is a **destination**, never an in-place edit (see [§In-place navigation](#in-place-navigation)).

The gate also checks the event **vector** before the request map: `:rf.route/navigate` is exactly `[:rf.route/navigate {request}]` — a **non-map payload** (`:request-not-a-map`) and a **third event element** (`:bad-event-arity`, e.g. a positional opts map left over from the deleted `[target opts]` split) reject through the same channel rather than throwing a raw host exception or being silently dropped. Unknown-key (and bad-address-key) reporting is **total** over heterogeneous EDN keys: the offending keys are ordered by their shared CEDN-1 identity (`re-frame.identity/canonical-bytes`), never a `compare`-based `sort` that would throw on a mixed-kind key set — the report is identical on JVM and CLJS.

<a id="in-place-navigation"></a>
<a id="rfrouteself--navigate-in-place"></a>
<a id="the-query-merge-opt--navigate-in-place"></a>
#### In-place navigation

A **destination** request (`:to` / `:url` present) builds a FRESH address — omitted `:query` / `:fragment` are empty. An **in-place** request (neither `:to` nor `:url`) is a PATCH of the current location — *stay on the current route, change only the query (or fragment)*. It is the single most common URL operation — search, pagination, tab switches, filter toggles — where the route (path) is unchanged and only the query string moves. React Router does this in one `setSearchParams` call; the re-frame2 equivalent is a `:rf.route/navigate` request carrying only the change:

```clojure
;; On /search?q=clojure&page=1&sort=recent  →  /search?q=clojure&page=2&sort=recent
(rf/dispatch [:rf.route/navigate {:query-merge {:page 2}}])

;; Remove a key: nil value drops it  →  /search?q=clojure&page=2
(rf/dispatch [:rf.route/navigate {:query-merge {:sort nil}}])

;; Replace the whole query wholesale  →  /search?tab=history
(rf/dispatch [:rf.route/navigate {:query {:tab "history"}}])
```

Resolution is discriminated by key PRESENCE:

| Key | Present | Omitted |
|---|---|---|
| route + `:params` | — (never accepted in-place; changing params is a **destination**) | carried from the current `:rf/route` slice |
| `:query` | replaces the current query wholesale (`{}` clears it) | carried unchanged |
| `:query-merge` | folds into the current query (a `nil` value removes a key) | — |
| `:fragment` | set (`nil` clears) | carried unchanged |

The merge reads the current `:query` slice from the runtime-db (`[:rf.runtime/routing :current :query]`) and folds the `:query-merge` deltas on top. It is the **only** place the router reads ambient query state into an outgoing navigation, and it is in-place only — a destination address is taken literally ([§Carrying query state across routes](#carrying-query-state-across-routes)). A present-but-**falsy** value (`false`, `0`, `""`) is a legitimate value and survives; only `nil` removes a key. A `nil`-valued key is elided from BOTH the pushed URL and the written slice, so an in-place nav to the same query is a genuine [rule-3 no-op](#per-route-data-loading). The route-id's `:query` schema still validates the resulting query at the call site (per [§Param validation at the call site](#param-validation-at-the-call-site)); `:can-leave`/`:can-enter`, the rule-3 no-op short-circuit, and nav-token allocation all behave exactly as for the equivalent destination nav.

This deliberately **fixes today's self-nav surprise**: under a positional grammar, "stay here, change only `#fragment`" silently cleared the query and re-fired `:on-match`. An in-place `{:fragment "x"}` **carries** the current query, satisfies the same-route/params/query condition, and lands in the existing [fragment-only short-circuit](#programmatic-navigation-with-fragments) — an anchor change, not a reactivation. Requests assembled with conditional key-dropping (a `cond->`-style optional destination) should assert their own `:to`; the framework adds no marker key or mode flag (trust-the-programmer).

#### Target form — route-id or URL-string

`:rf.route/navigate`'s request carries one of two destination forms (or neither, for an in-place request):

- **Route-id (canonical):** `(rf/dispatch [:rf.route/navigate {:to :route/cart :params {:cart-id 42}}])`. The runtime resolves the route, builds the URL via `route-url`, and pushes. This is the form Construction-Prompts and well-formed app code use — the route-id is enumerable, refactorable, and queryable through the registrar.
- **URL-string (escape hatch):** `(rf/dispatch [:rf.route/navigate {:url "/some/path"}])`. For dynamic or user-supplied URLs the app didn't build itself. The runtime calls `match-url` on the string; if it resolves to a registered route, navigation proceeds normally. URL-strings that don't match any registered route resolve to `:rf.route/not-found` with the URL in `:params`, and the runtime pushes the **requested URL** to the address bar (not the not-found route's own `:path`) — the user keeps the URL they aimed at while the not-found view renders. A miss with no `:rf.route/not-found` route registered still commits the not-found slice and emits the `:rf.warning/no-not-found-route` trace (per [§Route-not-found](#route-not-found--rfroutenot-found-canonical)); it is not rejected.

URL-strings are stringly-typed escape-hatchy by nature; tooling can flag them as candidates for migration to a registered route-id when the URL pattern is known.

<a id="resolve-target"></a>
##### `resolve-target` — the target-resolution contract

The navigate handler resolves its **request** into a `{:route-id :path-params :query-params :fragment}` shape through **`resolve-target`** (referenced in the handler sketch above). It is a **pure** function of the request plus the current route slice — no side effects, no dispatch. It dispatches on the request form:

| request form | `:route-id` | `:path-params` | `:query-params` |
|---|---|---|---|
| **route-id** `{:to :route/x …}` | the `:to` route-id | `(:params request)` | `(:query request)` verbatim — **no** ambient current-slice fold |
| **URL-string** `{:url s}` | `match-url`'s route-id (or `:rf.route/not-found` on a miss / open-redirect / throw) | `match-url`'s params (or `{:url s}` on a miss) | `match-url`'s query |
| **in-place** (no `:to` / `:url`) | the **current** slice's `:route-id` | the **current** slice's `:params` | the current query, patched by `:query` (wholesale) or `:query-merge` (fold) |

Fragment resolves from an explicit `:fragment` (present, even `nil`) → else a destination's URL-embedded fragment (nil for a route-id) → else the current slice's fragment (in-place carry), normalised so an empty-string fragment collapses to nil. `resolve-target` performs **no** validation and **no** URL build — those happen downstream (`route-url` raises the structured caller-bug error, which the handler catches and rejects). Given the same request and slice it always yields the same resolved shape, on JVM and CLJS alike. Facts spell `:route-id`; requests spell `:to` — deliberate intent/fact vocabulary, not drift.

### URL changes are events

When the URL changes from the *link/browser* layer, the runtime fires one of **two co-equal events** (the pattern's `onUrlChange` analogue per Elm's Browser.application; see "Standard runtime events" below). Both write the `:rf/route` slice from the URL and run identical match/validation/fragment/`:on-match` logic; they differ only in *who fires them* and the *default scroll strategy*. (Programmatic `:rf.route/navigate` is the *third* commit path: it writes the slice **inline** in its own handler — see [§Navigation is an event](#navigation-is-an-event) — and does **not** dispatch either of these two events.)

- **`:rf.route/transitioned`** — forward navigation from a `route-link` click (dispatched by `:rf.route/url-requested` after the `:can-leave` gate passes and the URL is pushed). Default scroll strategy `:top`.
- **`:rf.route/handle-url-change`** — popstate (Back/Forward), initial page load, and the server-side request URL during SSR. Default scroll strategy `:restore` (the saved position for the URL being returned to). On SSR the runtime threads the `:frame` id through so per-frame error projections can attribute a `:no-such-handler` trace. Because one event stands for **three** doors, *which* one it is rides on the trailing `:rf.route/cause` rider the `:url-bound?` lifecycle's listener stamps (`:popstate` from the browser callback, `:initial` from the same listener's initial sync, and `:ssr` resolved from a server frame's `:platform` with no rider at all) — see [§The request grammar](#the-request-grammar--address-policy-and-edit-keys).

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
    (let [{:keys [route-id params query fragment validation-failed?]} (rf.routing/match-url url)
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
                        :transition :idle    ;; resource-derived readiness projection derives :loading/:error from the plan
                        :error      nil
                        :nav-token  nav-token})
         :fx (into [[:rf/trace [:rf.route.nav-token/allocated {:route-id route-id :nav-token nav-token}]]]
                   (for [ev (:on-match route-meta)]
                     [:dispatch ev]))}))))
```

The sketch above shows the **slice rewrite** only. It destructures `[_ url]` and so elides the trailing opts map carrying the `:rf.route/cause` rider, and it predates the shared planning pipeline every door now lowers to — the guards, the resolved-target seam, the activation plan, and the `:rf.route/planned` emit all live in [§The one planning pipeline](#the-one-planning-pipeline). Read it as the slice contract, not as the handler's full body.

The same handler runs **on the server during SSR** (no `:platforms` exclusion) — the request URL is fed in, the route slice is set, the view renders against it. The `:on-match` events also fire-and-forget server-side, so their synchronous effects stay symmetric with the client; they are not awaited, and required page data that must settle before render is a blocking `:resources` requirement, not an `:on-match` event (see [§Server-side rendering integration](#server-side-rendering-integration)). No SSR-specific routing code.

### Linking from views — plain-anchor semantics

> **Lock: the runtime does not auto-intercept `<a>` clicks. Click interception is the host adapter's job.**

| Form | Behaviour |
|---|---|
| `[rf/route-link {:to :route/cart} "Cart"]` | Renders `<a href="...">` and intercepts plain primary-button clicks itself — its registered view body (per [§Standard runtime events](#standard-runtime-events)) calls `.preventDefault` and dispatches `:rf.route/url-requested`. The dispatch **carries the frame address captured at render time** (per [EP-0002 carried-invariant](002-Frames.md) — the render-time scope — a `with-frame`, or a `frame-provider` (SCOPE) / `frame-root` (ENSURE) boundary — has unwound by the time the click fires, so the click closure pins the rendering frame just as a `capture-frame` does for view bodies; resolving the frame ambiently at click time would raise `:rf.error/no-frame-context` or route to the wrong frame). Modifier keys (cmd-click, middle-click, shift-click) defer to the browser; the link follows the `href` natively. |
| `[ui/route-link {:to :route/cart} "Cart"]` | The **compiled-view counterpart** (Spec 004, artefact `day8/re-frame2-ui`) — an ORDINARY compiled `defview`, same public shape and behaviour. It renders the same real `<a href="...">`, applies the SAME click law, and carries the SAME render-time-captured frame address on the `:rf.route/url-requested` dispatch. The routing calculation and the click law are NOT reimplemented in the ui artefact: routing publishes them behind a substrate-neutral late-bound seam — `:routing/link-model` (pure, both hosts: strategy-encoded href + dispatch payload + native-anchor detection) and `:routing/activate-link!` (the CLJS click op) — and `re-frame.ui` consumes them through core's late-bind registry, so neither optional artefact statically requires the other (`ui -> core late-bind <- routing`). Absent the routing artefact, rendering it fails loud with `:rf.error/routing-artefact-missing`. |
| `[v/route-link {:to :route/cart} "Cart"]` | The **Freehand descriptor** (Spec 004, artefact `day8/re-frame2-freehand`) — an ORDINARY `v/defview`, same public shape and behaviour, and not a compiler form. It renders the same real `<a href="...">`, applies the SAME click law, and carries the SAME render-time-captured frame address on the `:rf.route/url-requested` dispatch. It reimplements none of it: routing publishes the calculation and the click decision behind the same substrate-neutral late-bound seam described in the row above, and Freehand consumes them through core's late-bind registry (`freehand -> core late-bind <- routing`), so neither optional artefact statically requires the other. Absent the routing artefact, rendering it fails loud with `:rf.error/routing-artefact-missing` at RENDER — before any anchor exists — rather than emitting a dead link. See [§The Freehand route-link descriptor](#the-freehand-route-link-descriptor). |
| `[:a {:href "..."} ...]` (plain anchor in user view code) | Browser-native navigation. The runtime does **not** intercept; clicking causes a full page load if the URL is on the same origin and an external navigation otherwise. Apps that want SPA-style interception on plain anchors install it at the **host adapter** layer (a top-level `click` listener on the document that consults `match-url`); the runtime's contract stops at `route-link` plus `:rf.route/url-requested`. |

`rf/route-link` (the stock-Reagent registered view at `:route/link`, below),
`ui/route-link` (the compiled `defview`) and `v/route-link` (the Freehand
descriptor) are behaviourally identical and share this law. `rf/route-link` is
frozen into the compatibility/interop tier; `v/route-link` is the view-substrate
surface. Migrating a routed Reagent app is a mechanical head-rename, because the
href and the click law are the same law in every spelling — only the substrate
differs.

**`:prefetch :intent` — the opt-in warm-mode trigger.** A link accepts one
behaviour value, `:prefetch :intent`, which warms the destination's resources on
credible **user intent** — pointer hover, focus, or touch-start — by dispatching
`[:rf.route/prefetch {address}]` for the link's own address to the
render-time-captured frame (see [§Route-plan prefetch](#route-plan-prefetch--warm-mode-intent-preload)).

```clojure
[v/route-link {:to :route/article :params {:slug slug} :prefetch :intent} title]
```

`:intent` is the **only** accepted value; there is no render mode, viewport mode,
global default, or hover-delay knob (Governing Law 1 — a passive render dispatches
nothing, so prefetch never fires merely because a view rendered or scrolled into
view). `:prefetch` is a routing control key on every link surface: it is validated
and **stripped before DOM emission** alongside `:to` / `:params` / `:query` /
`:fragment`, so it never reaches the `<a>` as an unknown attribute.

**Validation is fail-loud, and an absent key is the only way to be passive.** A
`:prefetch` that is PRESENT with any other value — an unsupported mode borrowed
from another router (`:render`, `:viewport`), a boolean, an explicit `nil` or
`false`, a typo — is a caller bug and throws
[`:rf.error/route-link-bad-prefetch`](009-Instrumentation.md#error-event-catalogue)
at the render site. Stripping it instead would render a link indistinguishable from a
working one: nothing on screen, nothing in the log, and the warm-up the author
asked for silently absent until someone measured. The check lives in routing's one
shared link calculation and runs on **both hosts**, so `rf/route-link`,
`ui/route-link`, and `v/route-link` reject the same values the same way — the SSR
shell included, which must not accept a mode the hydrated client rejects. The installed
intent handlers **compose with**, rather than replace, a caller-supplied
`:on-mouse-enter` / `:on-focus` / `:on-touch-start`, and dispatch to the same
render-time-captured frame the click handler targets — so a prefetch warms the
frame that rendered the link, never a sibling. A caller who wants prefetch on a
non-link intent (a search result becoming the keyboard selection) dispatches
`:rf.route/prefetch` directly; the link opt is sugar over that event.

**Why the runtime doesn't auto-intercept.** A global `click` listener that calls `match-url` on every link is a host concern (DOM-bound, browser-only, conflicts with non-routed `<a>` tags inside iframes / shadow DOM / third-party widgets). The host adapter has the context to install or skip it; the runtime stays portable.

Users who want plain anchors to be interceptable register their own delegating handler at the host layer, dispatching `:rf.route/url-requested` on match — this re-uses the same decision-point event the runtime already exposes, so the test surface and policy are unchanged.

### Reading the route is a sub

The `:rf/route` sub projects the published slice keys via `select-keys` over the route slice at `[:rf.runtime/routing :current]`. The internal `:pending-navigation` slot lives alongside `:current` under `[:rf.runtime/routing]` in `runtime-db` (it has its own `:rf/pending-navigation` sub) but does not surface through the `:rf/route` sub — consumers that `deref` the route sub see only the slice. The nav-token / pending-nav **counters** are **not** `runtime-db` siblings at all — like the scroll-position cache they live in host-side transient caches (per [§Navigation tokens — stale-result suppression](#navigation-tokens--stale-result-suppression) and [§Scroll restoration](#scroll-restoration)) — so no internal counter tick ever touches `runtime-db` or risks a route-sub re-render.

These are **framework subscriptions** — their layer-1 reader runs against the frame's **runtime-db** projection (where the route slice lives), not the app-db projection (per [002 §Subscriptions read the partition they belong to](002-Frames.md#subscriptions-read-the-partition-they-belong-to) and [006 §Frame-state container and partition projections](006-ReactiveSubstrate.md#frame-state-container-and-partition-projections)). The `rt` arg below is the runtime-db projection. A runtime-only route commit propagates to these subs (and to nothing in app-sub-land); app authors consume them through the public sub-ids, never by reaching into runtime-db.

```clojure
(def route-slice-keys
  [:route-id :params :query :fragment :transition :error :nav-token])

(rf/reg-sub :rf/route
  {:doc "The current route slice: {:route-id :params :query :fragment :transition :error :nav-token}."}
  (fn route-slice [rt _] (select-keys (get-in rt [:rf.runtime/routing :current]) route-slice-keys)))   ;; rt = runtime-db projection

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

For the common top-level read, subscribe to the canonical `[:rf/route]` vector directly — `@(rf/subscribe [:rf/route])` returns a reaction over the published slice. The route is a per-frame singleton, so no id argument is needed; to read a non-default URL-bound frame's slice from outside an established scope, name the frame with `subscribe`'s `{:frame <target>}` opts form — `@(rf/subscribe [:rf/route] {:frame <target>})` (`<target>` is a frame-id keyword or a live frame value). There is no named-read-sugar fn: a runtime-db framework read is a subscription vector, one read grammar (per [Conventions §Reserved sub-ids](Conventions.md#reserved-sub-ids)) — the `[:rf/route]` vector is what a `:<-` chain names and the `:rf.route/*` granular subs derive from.

The pending-nav slot is the route slice's runtime-db sibling and is read the same way — `@(rf/subscribe [:rf/pending-navigation])` returns a reaction over the pending-nav map `{:id :destination :target :cause :policy :requested-url :rejecting-route :rejecting-guard}` (or `nil` in the steady state — the slot is non-nil only while a `:can-leave` guard holds a blocked navigation awaiting `:rf.route/continue` / `:rf.route/cancel`, per [§Navigation blocking — pending-nav protocol](#navigation-blocking--pending-nav-protocol)). Like the route it is a per-frame singleton, and `subscribe`'s `{:frame <target>}` opts form reads an explicit frame.

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

Two pure helpers, both registered, both queryable. Both live in `re-frame.routing` (require `[re-frame.routing :as rf.routing]`) — **not** on the `rf/` (`re-frame.core`) facade:

- `(rf.routing/match-url url)` → `{:route-id :keyword :params {...} :query {...} :fragment <string-or-nil> :validation-failed? boolean}` or `nil`.
  - Returns `nil` when no path-pattern matches the URL at all.
  - Returns the match map when *some* route's path-pattern matches. The `:params` map carries the captured **path** params (post-coercion against the route's `:params` schema, when one is present). The `:query` map carries the parsed **query-string** params, with `:query-defaults` filled in for absent keys and the route's `:query` schema applied for coercion (e.g. `"2"` → `2` for an `:int` field). The `:fragment` field carries the URL's `#fragment` portion (string or `nil` if absent); see "Fragments" below.
  - If schema validation fails (path params don't conform to `:params`, or query params don't conform to `:query`), the map carries `:validation-failed? true` plus a `:validation-error` field with the schema-explanation (per [Spec 010](010-Schemas.md)). The runtime's `:rf.route/handle-url-change` event treats validation-failure the same as no-match: it routes to `:rf.route/not-found` with the URL in params.
  - Pure; runs on JVM and CLJS.
- `(rf.routing/route-url {:to route-id :params path-params :query query-params :fragment fragment})` → URL string. Takes a single **address map** — `:to` (the route id) is required; `:params`, `:query`, and `:fragment` are optional. Strictly address-only: `:url`, `:query-merge`, policy keys, and any unknown key reject loud (`:rf.error/route-url-validation`); there is no in-place form. The boundary is **total**: a **non-map** address (`:reason :not-a-map`) and a **missing `:to`** (`:reason :missing-to`) reject through the same `:rf.error/route-url-validation` error rather than a raw host exception or a misleading `:rf.error/no-such-route`, and heterogeneous bad address keys are reported in total canonical order (never a `compare`-based `sort`). **Pure**; runs on JVM and CLJS.
  - Builds the URL from the `:path` template, substituting path params, then appends `?key=value&...` for any `:query`, then appends `#fragment` if a non-nil/non-empty `:fragment` is supplied. Ordinary path params, query keys/values, **and the fragment** are percent-encoded **per component** on emission (encodeURIComponent semantics) — symmetric with `match-url`, which decodes each portion the same way. A fragment carrying a literal `%` (`"50% done"`) emits as `#50%25%20done` and `match-url` reads it back as `"50% done"`; emitting the raw value would produce a `#fragment` that `match-url` rejects as malformed (the bare `%` fails to decode) → a route-miss, breaking the bidirectional contract. **Splat values are the one exception: they are encoded PER CHUNK.** A splat capture is a multi-segment string whose embedded `/` are structural separators, not data — so `route-url` splits the value on `/`, `encodeURIComponent`-encodes each chunk, and rejoins with raw `/` (a whole-value `encodeURIComponent` would emit `%2F` for every separator and break the round-trip). `route-url({:to :route/files :params {:rest "a/b/c.txt"}})` therefore emits `/files/a/b/c.txt`, and `route-url({:to :route/files :params {:rest "my file/50%.txt"}})` emits `/files/my%20file/50%25.txt` — each chunk encoded, the separators preserved. `match-url` is the exact inverse: it segments the raw URL first, then decodes each chunk, so an encoded slash inside a chunk can never change the route's structure.
  - **Keyword-enum values emit their declared token name** (path or query). A slot declared `[:enum :asc :desc]` carrying the keyword `:asc` emits the token `asc` — the exact inverse of `match-url`'s enum decode (which interns the declared name back to `:asc`), so `match-url(route-url(...))` recovers the canonical enum keyword. (Host `(str :asc)` would emit `%3Aasc`, which `match-url`'s enum decoder — keyed on the declared names `asc` / `desc` — reads as the string `":asc"`, not `:asc`, breaking the prism.) This also covers a value carried as a keyword from a prior match into an outgoing `route-url` by an application's own carry helper. An out-of-enum keyword fails `:rf.error/route-url-validation` rather than being stringified into a URL.
  - **A query key already at the route's declared default is not emitted.** `match-url` fills it back, so spelling it would give one destination two URLs (see [§A declared default lives in the target, never in the URL](#a-declared-default-lives-in-the-target-never-in-the-url)). The omission is an *emission* rule applied after `:query`-schema validation — a schema that requires a defaulted key still validates against the caller's full query.
  - **Does not navigate.** It is a string-builder; there is no side-effect on `app-db`, no `pushState`, no dispatch. To navigate, dispatch `:rf.route/navigate` (which uses `route-url` internally).
  - **Does not read `app-db`.** Inputs are the registered route table (static) and the caller-supplied params/query/fragment. Same inputs always produce the same string output.
  - **No ambient query is folded in.** The address it is handed IS the address it emits. Carrying global URL state (`:theme`, `:locale`, …) across routes is an `app-db`-aware application concern, so it happens *before* the call — the application's pure carry helper reads the current `:rf.route/query` slice and folds the keys it wants into the address ([§Carrying query state across routes](#carrying-query-state-across-routes)). Keeping `route-url` pure is the lock — it is the function the conformance corpus and the SSR pipeline call without an `app-db` in hand.
  - Throws `:rf.error/route-url-validation` if `path-params` doesn't conform to the route's `:params` schema, or `query-params` doesn't conform to the route's `:query` schema (caller bug; not user input). The same error also rejects a `:params` key the route's pattern has no segment for, under `:reason :uncaptured-params` — see [§`route-url` rejects a path param the pattern does not capture](#route-url-rejects-a-path-param-the-pattern-does-not-capture).

Both work against the same registered route table, so adding/removing a route updates both directions automatically.

##### `route-url` nil-policy: path params vs query params

`route-url` applies **two different nil-policies** to the path side and the query side — same function, opposite rules. The split is deliberate, but it is surprising enough to document explicitly (it otherwise costs a debugging session when `{:page nil}` mysteriously vanishes):

| Slot | `nil` (or absent) value | present-but-falsy value (`false`, `0`) | empty string `""` |
|---|---|---|---|
| **Path param** (a `:name` / `*name` segment) | **Hard error** — throws `:rf.error/missing-route-param`. The URL cannot be built without the segment. | **Round-trips.** A falsy-but-present value is a legitimate segment (`/items/0`, `/page/false`). | **Hard error** — throws `:rf.error/missing-route-param`. A zero-length segment cannot round-trip. |
| **Query param** (a key in `query-params`) | **Silently elided** — `{:page nil}` omits the key entirely (no bare `?page=`, no throw). | **Round-trips.** A falsy-but-present value emits (`?archived=false`). | **Round-trips** — emits `?key=` (a present empty value, distinct from absent). |

The query-side elision is the useful default for **absent optional query keys**: a search form that conditionally adds `?sort=` only when a sort is chosen passes `{:sort nil}` and gets a clean URL with no `sort` key. The path side cannot elide — a missing path segment has no URL to produce — so it throws. The non-empty falsy values (`false`, `0`) stringify to legitimate segments and round-trip on both sides; the **empty string** is the exception on the path side: a `""` segment would emit a zero-length path component (`/articles/` for `{:slug ""}`) that `match-url`'s trailing-slash normalisation erases (`/articles/` → `/articles`) before matching, so it cannot round-trip back to the same route/params. Rather than emit a URL that silently fails to parse, the path side rejects an empty-string segment on emission (`:rf.error/missing-route-param`), the same as `nil`/absent. The query side has no such constraint — `?key=` is a representable, round-trippable present-empty value. Authors who need a query key to always be present must supply a non-nil value (use a sentinel string, not `nil`).

##### `route-url` rejects a path param the pattern does not capture

The same round-trip reasoning closes the `:params` map itself. A key the route's pattern has no `:name` / `*name` segment for cannot reach the URL, and `match-url` has no reading that recovers it, so `route-url` **rejects** it — `:rf.error/route-url-validation` with `:reason :uncaptured-params`, naming the offending `:keys` — rather than building a URL and dropping the key on the floor. `{:to :route/probe :params {:id "7" :extra "x"}}` against `/probe/:id` is a caller bug, not a request for `/probe/7`. The rule sits at `route-url` because that is the emission boundary all three named-address doors already share: the programmatic URL build, `route-link`'s href synthesis, and prefetch's destination gate. The URL-driven doors need no rule at all — a URL physically cannot carry an uncaptured param.

Silence here cost more than it saved, because it set the doors against each other. The programmatic door committed `:params {:id "7" :extra "x"}`, a slice its own address bar could not spell and a reload could not reproduce, while a `route-link` **click** resolved through the URL and committed `{:id "7"}` — so hovering a link warmed one identity and clicking that same link activated another. Truncating the uncaptured key instead would settle the doors, but it swallows a typo class nothing else catches: on `/docs{/:section}?`, `{:sction "x"}` elides the optional group and builds `/docs` cleanly, so `:rf.error/missing-route-param` can never fire and the author gets a wrong-but-plausible URL. Rejecting is also what this function already does for every other break of the `route-url` / `match-url` prism — the empty-string segment above, the sequential-optional-group prefix chain — and [§Validity rules](#validity-rules--the-always-on-structural-gate) rule 2 already names address keys that "ride beside it and be silently ignored" as the exact failure class this grammar exists to kill. This applies that closure one level down, inside `:params`.

**One route is exempt, and only one: `:rf.route/not-found`.** Its slice `:params` are the framework's record of the miss (`{:url … :reason …}`, per [§Route-not-found](#route-not-found--rfroutenot-found-canonical)), not path captures — the fallback route's pattern is not their source and has no say over their vocabulary. Without the exemption, rebuilding a registered not-found route's URL (the address-bar restore after a URL-driven rejection) would quietly stop working.

#### Param validation at the call site

The two boundaries where route params enter the runtime — **programmatic navigation** (`route-url` / `:rf.route/navigate`) and **URL-driven navigation** (`match-url`) — validate against the route's `:params` and `:query` schemas, with different failure modes on each side.

| Boundary | Source | Validation failure |
|---|---|---|
| **Programmatic** — `(route-url {:to … :params … :query …})` | Caller supplies the address map directly. | **Throws** `:rf.error/route-url-validation` (caller bug; not user input). The schema-explanation is on the exception's data; the trace event is emitted at the same time. |
| **Programmatic** — `[:rf.route/navigate {request}]` **shape** | Caller dispatches an event with a malformed request map. | The **always-on structural gate** (roster / exclusions / destination-or-change) rejects it BEFORE any guard runs, emitting `:rf.error/navigate-bad-request` (`:where :event`, a `:reason` discriminator). The `:rf/route` slice does not change. See [§Validity rules](#validity-rules--the-always-on-structural-gate). |
| **Programmatic** — `[:rf.route/navigate {request}]` **schema** | Caller dispatches a well-formed request whose `:params` / `:query` fail the route schema. | The route's `:params` / `:query` schema runs (via `route-url`) **before transitioning**. Failure emits `:rf.error/schema-validation-failure` (per [009 §Error event catalogue](009-Instrumentation.md#error-event-catalogue), `:where :event`) and the navigation is **rejected** — the `:rf/route` slice does not change. |
| **URL-driven** — `(match-url url)` | Browser URL (popstate, link click, deep link). | `:validation-failed? true` in the result; `:rf.route/handle-url-change` routes to `:rf.route/not-found` with `:reason :validation`. |

The asymmetry is deliberate. Programmatic navigation is *caller code* — schema failures are bugs and should be surfaced loudly (throw / reject). URL-driven navigation is *user input* — schema failures are 404s, not exceptions. Both paths share the same `:params` / `:query` schemas (per [Spec 010](010-Schemas.md)), so a route that compiles cleanly with one validates the same way against the other.

The request-**shape** check for `:rf.route/navigate` is the always-on structural gate ([§Validity rules](#validity-rules--the-always-on-structural-gate)) — plain set logic over the closed roster, needing neither the schemas artefact nor a declared schema — while the `:params` / `:query`-**schema** check re-uses the standard schema-validation path (`route-url`'s throw, surfaced as `:rf.error/schema-validation-failure` with `:where :event`). The two are distinct channels (structural-shape vs value-schema), deliberately so.

**Both rejections survive a release build; neither report does.** `route-url`'s validation is gated on the optional schemas artefact and a declared schema, not on `debug-enabled?`, so a production navigate whose `:params` fail the route's schema is still refused and the `:rf/route` slice is still left unchanged — the same outcome as in dev. What does not survive is the signal. Both errors are emitted through `trace/emit-error!`, which *is* `debug-enabled?`-gated. `:rf.error/navigate-bad-request` is catalogued `diagnostic` outright, so nothing of it survives the gate. `:rf.error/schema-validation-failure` needs the finer statement: its `Channel` cell reads `always-on`, but that promotion is **arm-level** — what rides the always-on axis is the `:rf.schema/at-boundary` arm, keyed on the `:rf/boundary-rejected?` marker that interceptor stamps on the event context, and a route-shape rejection is not that arm. Routing's failure emits from `route-url`'s caller through the dev trace and stamps no marker, so this arm elides with the rest of the diagnostic channel. That is the same partial promotion the catalogue records for `:rf.error/no-such-handler`, whose row also reads `always-on` while only its `:kind :route` arm was promoted (per [009 §Error event catalogue](009-Instrumentation.md#error-event-catalogue) and [010 §Production builds](010-Schemas.md#production-builds)). Under `:advanced` + `goog.DEBUG=false` a rejected navigation therefore reports nothing on either axis. Production-reachable and production-observable are different claims: the refusal is real and silent, so do not wire `register-listener!` on the `:errors` stream to these ids and read a quiet count as "no bad navigations". An app that needs them counted must do so from its own handler code.

##### Validation-error surfacing across the three paths

The three validation paths surface failures through **three different error/no-error shapes**. The table below names what an observer sees on each path so tools and handlers branch on the right surface.

| Path | Error id | Trace `:operation` | Drain-level error fired? | Slice discriminator |
|---|---|---|---|---|
| Programmatic — `(route-url ...)` | `:rf.error/route-url-validation` | none (synchronous throw) | thrown directly via `ex-info`; not on the trace bus | n/a (the call throws; no slice write) |
| Programmatic — `[:rf.route/navigate {:to ...}]` | `:rf.error/schema-validation-failure` (with `:where :event`) | `:rf.error/schema-validation-failure` per [009 §Error event catalogue](009-Instrumentation.md#error-event-catalogue) | **dev only** — the emit is `trace/emit-error!`, gated on `debug-enabled?`. The category's `Channel` cell reads `always-on`, but the promoted arm is `:rf.schema/at-boundary` and a route-shape rejection is not it ([009 §Error event catalogue](009-Instrumentation.md#error-event-catalogue)). A release build fires nothing; the rejection itself still happens | n/a (navigation rejected on every build; slice unchanged) |
| URL-driven — `(match-url ...)` → `:rf.route/handle-url-change` | no `:rf.error/*` — the failure becomes a not-found | none (`:rf.warning/malformed-url` may fire for a separate sibling case) | no | `:rf/route` slice writes `{:route-id :rf.route/not-found :params {:url url :reason :validation}}` |

The split is principled (per [§Param validation at the call site](#param-validation-at-the-call-site) above): caller-bug paths throw, event-boundary paths reject with a structured error, URL-driven paths route to the canonical not-found id. A consumer reading "the user tried to reach a route they can't parse" therefore branches differently per source, and the three do not degrade alike under `goog.DEBUG=false`. A caller bug calling `route-url` directly surfaces as a thrown `ex-info` on **every** build — the check is gated on the schemas artefact, not on `debug-enabled?` — and never reaches the trace bus at all, so the exception is the whole surface. An event-boundary failure is refused identically on every build, but *reports* only in dev, on the trace bus. A URL-driven failure surfaces via the not-found view's `:reason :validation` branch, which is ordinary slice state rather than an error channel, and so is the only one of the three an observer can still see in a release build.

**Asymmetry with flows.** Flows' validation surface is **flat by comparison** — `reg-flow` rejects a malformed flow map with one of **six** explicit error ids that all fire at registration time, all under `:rf.error/flow-*`: `:rf.error/flow-missing-id`, `:rf.error/flow-bad-id`, `:rf.error/flow-bad-inputs`, `:rf.error/flow-bad-output`, `:rf.error/flow-bad-path`, and `:rf.error/flow-bad-marks` (the registration-validation family, owned by [Spec-Schemas §FlowMeta](Spec-Schemas.md) and catalogued in [009 §Error event catalogue](009-Instrumentation.md#error-event-catalogue); reference implementation `implementation/flows/src/re_frame/flows/registry.cljc`). These are distinct from flows' **runtime** error ids (`:rf.error/flow-cycle`, `:rf.error/flow-path-overlap`, `:rf.error/flow-eval-exception`, per [013 §Failure semantics](013-Flows.md#failure-semantics)). Flows have a single validation time for the registration family (registration) and a single surface (registration-throw); routing has three validation times (caller-fn invocation / event-boundary interceptor / URL-driven match) and three surfaces (synchronous throw / structured error / not-found route). The asymmetry is **not a bug** — it is principled per the table above — but it does mean that an AI scanning routing for "validation error ids" does not see one closed family, and a tool building an aggregate "show me all validation failures" surface needs to subscribe to two distinct error ids plus a slice-write predicate. The split is the cost of routing's caller-bug-vs-user-input distinction; flows have no such distinction (registration is always caller code).

<a id="the-routeaddress-and-the-shared-planning-pipeline"></a>
## The `RouteAddress` and the shared planning pipeline (EP-0037 R0)

[EP-0037](../docs/EP/EP-0037-route-planning-and-activation-ownership.md) makes routing one inspectable planning boundary: a caller-authored **address** is resolved once into a target, an active branch, and navigation policy, and every navigation door executes that one plan. This section is the R0 contract — the address value, the law that extracts it from the flat public maps each door accepts, the ordered planning pipeline every door lowers to, and the resolved-target / plan diagnostic projection. The stages that graduate later (honest readiness, the parent-to-leaf branch plan, terminal entry) are named to their EP-0037 sections and land in EP-0037's later slices; existing runtime behaviour is otherwise preserved.

<a id="the-routeaddress-value"></a>
### The `RouteAddress` value

A registered destination is one closed, ordinary EDN map — caller *intent* for one named route (EP-0037 §Terms, §Canonical `RouteAddress`):

```clojure
{:to       :route/article
 :params   {:slug "routing-as-data"}
 :query    {:tab :comments}
 :fragment "reply-42"}
```

Its schema id is `:rf/route-address` ([Spec-Schemas §`:rf/route-address`](Spec-Schemas.md#rfroute-address)): a closed `[:map {:closed true} [:to :keyword] [:params {:optional true} :map] [:query {:optional true} :map] [:fragment {:optional true} [:maybe :string]]]`. `:to` is required; omitted `:params` / `:query` normalise to `{}`, an omitted or `nil` `:fragment` to no fragment. There is no record, constructor, builder, relative-address language, or redirect object — an application names address constants and pure functions with ordinary Clojure values.

The same four fields are consumed wherever an address is stored, printed, linked, navigated to, denied, returned to, or prefetched: `rf.routing/route-url`, `rf/route-link` / `v/route-link` (as control fields inside the larger props map), the destination branch of `:rf.route/navigate`, the named destination carried by entry-denied and pending-leave data, application redirect / return-to state, and `:rf.route/prefetch`. Two companion values complete the vocabulary (EP-0037 §Terms):

- **`RouteDestination`** ([`:rf/route-destination`](Spec-Schemas.md#rfroute-destination)) — the closed replay union of a `RouteAddress` and the raw-URL escape, used where runtime state must replay a destination that may have no named spelling. It never absorbs navigation policy.
- **`ResolvedTarget`** — planner output (`:route-id`, `:params`, `:query`, `:fragment`, `:url`) after matching, defaults, and validation. It is a *fact*, not another accepted input spelling; facts say `:route-id`, intent says `:to`.

Policy and edits are deliberately **not** part of the address. Navigation policy (`:replace?`, `:scroll`, and the leave-only `:bypass-leave?`) and the in-place edit `:query-merge` travel in the same flat map but keep their own shapes, so no future feature can make a policy or edit key serialisable as a destination (EP-0037 §Navigation policy and in-place edits). Public call sites stay flat — a `:rf.route/navigate` request is an address plus navigation policy; a `route-link` props map is an address plus link behaviour and ordinary DOM attributes — and no nested `{:address … :policy …}` envelope is introduced to encode the separation.

<a id="the-extraction-law"></a>
### The extraction law

Every door extracts and validates the address through **one** shared law over closed key classes (EP-0037 §Normative extraction from flat public maps):

```clojure
address keys       #{:to :params :query :fragment}
policy keys        #{:replace? :scroll :bypass-leave?}
edit keys          #{:query :query-merge :fragment}
link behavior keys #{:on-click :prefetch}
```

`:on-click` is a **behaviour** key, not a passthrough DOM attribute: it is the imperative pre-navigation seam, replaced by the framework's own click closure on the interactive host and **dropped** on the SSR path — a serialized document has no click to intercept and must not carry a host closure ([§The Freehand route-link descriptor](#the-freehand-route-link-descriptor)). Leaving it on the props map would let a caller's closure reach the server-rendered `<a>`.

Key **presence** selects the request branch *before* any validation:

1. **`:to` present** — the extractor selects exactly the address keys and validates that extracted map against `:rf/route-address`. `:query` and `:fragment` are destination facts in this branch.
2. **`:url` present** — the extractor selects the raw destination and validates it against the raw arm of `:rf/route-destination`. `:to`, `:params`, `:query`, and `:query-merge` are forbidden beside it; an explicit `:fragment` overrides the URL-embedded one under the existing fragment rule.
3. **neither `:to` nor `:url`** — `:query`, `:query-merge`, and `:fragment` are **in-place edits**. They are never validated or stored as a `RouteAddress`. A `:params` key in this branch retains the named `:rf.error/navigate-bad-request` reason `:params-requires-destination`: changing path params always requires a destination (see [§In-place navigation](#in-place-navigation)).

Policy keys are extracted and validated **separately** from the address. Every closed control-map boundary — navigation, URL generation, prefetch, stored-destination replay — validates its **whole accepted-key roster before extraction**, so extraction can never silently discard a misspelled address, policy, or edit key; the navigate structural gate's `:unknown-keys` rejection (namespaced keys included) is exactly this whole-roster check (see [§Validity rules](#validity-rules--the-always-on-structural-gate)). Because only the extracted address reaches the closed schema, a flat wrapper carrying `{:to … :params … :replace? true}` navigates to the same target and URL whether or not the policy key is present — the policy changes the *effect* (`replaceState` vs `pushState`), not the destination.

`route-link` is the one deliberate exception, because its props map is *also* an open DOM-attribute map: it validates the recognised routing controls (including `:prefetch`), strips the exact address and behaviour keys before DOM emission, and passes the remaining attributes and children to the view substrate. It does not ask routing to classify every misspelled control against a caller-authored DOM attribute; host / view DOM diagnostics own unknown attributes. The schema is therefore closed over the *extracted address*, not over the convenient flat props map a link accepts (EP-0037 §Normative extraction from flat public maps).

<a id="raw-url-escape"></a>
#### The raw-URL escape

`{:url "/partner/supplied/path"}` remains a stringly escape accepted by navigation doors that must handle an already-authored URL (deep-link handlers, server-redirect targets, a programmatic redirect from a string). It is **not** a `RouteAddress`, cannot combine with `:params` / `:query`, and is not accepted by `route-url` or prefetch. If it matches a registered route the planner produces the same resolved target and canonical named address it would from `:to`; if it does not, the existing same-origin / not-found rules apply (see [§Target form](#target-form--route-id-or-url-string)). Where runtime state must replay either form, `:rf/route-destination` is the closed union of a `RouteAddress` and this raw map; a matching raw URL normalises to the named branch, and only a destination that cannot be reified without changing the requested URL stays raw (EP-0037 §Raw URL escape).

<a id="the-one-planning-pipeline"></a>
### The one planning pipeline

Every navigation door — a `route-link` click, `:rf.route/navigate`, Back / Forward, initial load, and SSR — lowers to the **same** ordered stages. Doors differ in cause and history / scroll policy, not in target, entry, resource, or readiness semantics (EP-0037 §One planning pipeline for every door):

| Stage | Result | Failure / short-circuit |
|---|---|---|
| 1. Validate intent | accepted address / raw URL / in-place edit plus policy | a malformed request rejects before any guard or history effect |
| 2. Resolve target | canonical target, URL, named address when possible, parent branch | schema / URL failures follow the existing not-found or caller-error contract |
| 3. Classify transition | full, fragment-only, or no-op | a no-op terminates before guards, pending state, history, scroll, or activation |
| 4. Decide leave | allow, or a leave-only pending navigation | full and fragment-only transitions consult the current route; pending leave preserves it, and popstate restores its URL |
| 5. Decide entry | allow, or a terminal entry denial | full and fragment-only transitions consult the target route; denial commits no target and creates no pending entry |
| 6. Build activation plan | for a full transition, the effective resource plan and old/new identity diff | fragment-only skips activation planning; a planning failure produces a failed plan and no partial resource plan executes |
| 7. Commit and activate | route facts / plan ownership, history / scroll effects, resource ensures, activation events | stale completions stay fenced by token / generation |

Classification precedes the guards deliberately: an exact **no-op** evaluates neither guard — nothing is being left or entered, and a redundant request must not create pending or denied state. A **full** transition evaluates `:can-leave` then `:can-enter`, including a same-route activation whose canonical `:params` or `:query` changed; query is data-bearing, so the in-place request form is not an implicit guard bypass. A **fragment-only** transition preserves 012's existing both-guard [fragment contract](#fragments) and skips only resource planning and activation. Initial load has no current leave guard but evaluates target entry normally. Transition kind is derived from resolved **facts**, not request spelling (EP-0037 §Resolved target and route plan): *full* when there is no current target or `:route-id` / `:params` / `:query` differ, *fragment-only* when those three are equal and only `:fragment` differs, *no-op* when all four are equal. A changed in-place `:query` / `:query-merge` is therefore a full, data-bearing activation, not a weaker path.

The programmatic, link, URL-change, initial-load, and SSR handlers may remain separate public events for cause-specific tests and host integration, but they call the **same** resolver, decisions, planner, and commit assembler — no door reimplements guard coverage or resource planning. The existing [state-first ordering](#state-first-url-second-update-order-is-locked) holds: the frame's committed route facts and next plan ownership are established before any client history effect and before activation events; a URL-driven door performs no push (the browser has already moved), and a blocked or denied popstate restores the current route's URL by replace.

<a id="failed-activation"></a>
#### Failed activation

A resource-planning failure is a committed **failed activation**, not a return to the prior page (EP-0037 §One planning pipeline for every door, §Honest activation and readiness): the target and URL **commit**, route readiness projects `:error`, and **no** resource ensure from the invalid plan runs. The target's `:on-match` events do **not** run — committing target facts makes the failure addressable and gives client and SSR error views stable route context, but a failed activation is not a successful activation boundary for analytics, host notification, or state seeding. The structured planning error and its trace are the causal facts an application observes; a retry is a fresh navigation and dispatches `:on-match` only after its plan forms successfully.

Plan execution is atomic at this boundary: a failed plan contributes an empty next-ownership set, so after the failed target / error facts are installed every owner held only by the previous route plan is released, no partially planned next owner is attached, and no partial ensure is dispatched. A committed full activation allocates a fresh nav-token; a fragment-only transition and a no-op allocate none, and prefetch (not an activation) allocates none. The readiness projection — `:rf.route/transition` / `:rf.route/error` as a resource-derived value over the (leaf-only, until R2) plan — lands in EP-0037 R1 (see [§Route readiness is a resource projection](#route-readiness-is-a-resource-projection)); R2 replaces its plan input with the parent-to-leaf branch plan without changing the projector; the terminal-entry protocol that retires the resumable enter machinery graduates in R4. R0 fixed the stage order and the failed-activation rule above; R1 makes `:on-match` fire-and-forget and derives readiness from managed resources.

<a id="resolved-target-and-the-plan-diagnostic-projection"></a>
### Resolved target and the plan diagnostic projection

Planning turns the caller's address into resolved **facts** — a `ResolvedTarget`:

```clojure
{:route-id :route/article
 :params   {:slug "routing-as-data"}
 :query    {:tab :comments}
 :fragment "reply-42"
 :url      "/articles/routing-as-data?tab=comments#reply-42"}
```

It is passed to guard subscriptions, recorded in diagnostics, and used to derive the parent chain. Its `:query` is the field the seam *resolves* rather than reflects: the route's `:query-defaults` are filled in here, once, for every door (see [§A declared default lives in the target, never in the URL](#a-declared-default-lives-in-the-target-never-in-the-url)). The internal **route plan** is plain data carrying at least the source address or raw-URL request, the resolved target, the cause (`:link`, `:navigate`, `:popstate`, `:initial`, or `:ssr`), the parent-to-leaf branch, the effective resource requirements and their contributors, the transition kind, history / scroll policy, and the old/new resource-identity diff for ownership handoff. It is an implementation and tooling seam — this contract adds no public `RoutePlan` constructor or promise-returning router object; its observable laws are public and its diagnostic projection is visible in trace / Xray (EP-0037 §Resolved target and route plan).

The plan must be legible without executing view code, and its diagnostic projection graduates with the planner (EP-0037 §Tooling and observability): **R0 exposes only the source address and cause, the resolved target, the parent-to-leaf branch, and the behaviour-preserving leaf resource plan** — the minimum needed to prove the shared spine. R2 adds occurrence / dependency groups, contributor dedupe advisories, the grouped order, and the identity diff; later slices prove the integrated view. No slice is licence to build a second Xray graph or a general-purpose public plan debugger.

**The projection is reachable from an executed navigation.** Every door **commit branch** emits one [`:rf.route/planned`](#trace-events) trace, so the projection is observable on the trace / Xray stream and not only from a tool that happens to hold a plan value. The non-commit branches — an exact no-op and a fragment-only anchor change — commit no plan and emit none.

A trace tag is an **egress** surface, and the route's `:sensitive` classification cannot reach it: that classification is lowered against runtime-db slice **paths** ([§Route data classification](#route-data-classification)), which governs what a tool reads out of the `:rf/route` slice and says nothing about a tag map on the trace bus. The projection's resolved target nonetheless carries `:url` / `:params` / `:query` — carrier **values**. So this trace declines to carry them, and the reason is worth stating precisely, because it is **not** that doing so would breach a boundary the classification holds: it does not hold one here. The URL carriers of an executed navigation are reachable elsewhere on the very same drain — `:rf.nav/push-url`'s fx args carry the identical string a row later, as does the `:rf.route/navigate` event vector on `:rf.event/dispatched`. The plan trace therefore declines to add a **redundant** copy of carriers that are already ambient, and keeps the half that is diagnostically load-bearing: **which** keys were bound, not what they were bound to. That is local emit-site hygiene, not a boundary — the same register as [§Route data classification](#route-data-classification)'s fail-open note. It is lossy in exactly two ways, and only those two:

- the **URL** rides the same URL-carrier redaction the route-miss and blocked-navigation diagnostics already use — the structured path survives (it is what a consumer branches on) and the query-string and `#fragment` carrier values are redacted. There is no second redaction route for the same datum;
- `:params` / `:query` **values are not carried**; their **key sets** are. That `:id` and `:invite` were bound is diagnostically useful; that `invite=SECRET100` is the leak. A key set is not a carrier. The plan's source address is a carrier by the same argument and is likewise not carried — the cause already names the door that supplied it and the resolved `:route-id` names what it resolved to.

Extending the `:sensitive` classification to reach trace tags is **rejected**: it would be new machinery for one call site and a second classification surface beside the path lowering. Emitting the values and relying on tracing being disabled in production is also rejected — it inverts the default: where the framework *does* hold a URL-carrier classification it retains it rather than asking the app to remember (the retention in [§Replaceable framework defaults](#replaceable-framework-defaults)), and an emit site the framework owns outright is the cheapest place to keep faith with that posture.

## Per-route data loading

A route may declare a vector of events the runtime **fires-and-forgets** whenever the route becomes active. This is the pattern's **activation hook** — useful for synchronous seeding, analytics / host notification, and application-owned work that naturally begins at activation. The mechanism is purely event-driven; no new effect substrate. `:on-match` is **not** a readiness mechanism: managed page-read data that must be present before a view renders is declared with `:resources`, which drives the resource-derived readiness projection ([§Route readiness is a resource projection](#route-readiness-is-a-resource-projection)).

```clojure
(rf/reg-route :route/cart
  {:doc      "The cart page."
   :on-match [[:analytics/viewed-cart]
              [:cart/seed-ui-state]]}
  "/cart")
```

Its contract is fire-and-forget (EP-0037 §`:on-match` is activation work):

1. `:on-match` runs **only after a valid plan forms** — after a *successful* full activation. A committed **planning-failure** target dispatches **none** of its events (a failed activation is addressable route context, not an activation boundary; see [§Failed activation](#failed-activation)). When it does run, the runtime dispatches each event **in order** with normal run-to-completion semantics, after writing the `:rf/route` slice and before any view renders that depend on the seeded state.
2. It **does not drive route readiness.** `:on-match` never sets `:rf.route/transition` to `:loading` or `:error`, never queues a settle, does not await the asynchronous effects its events start, does not infer when their transitive work finished, and does not correlate later global error records back to the route. Route readiness is the resource projection, independent of `:on-match`.
3. Same-route-id navigations with **changed `:params` or `:query`** *do* re-fire `:on-match` (the route is becoming active again under new inputs). Same-route-id navigations with identical id/params/query do not re-fire, and a fragment-only change does not re-fire — this is the exact re-fire key set (request spelling and policy keys are irrelevant; the runtime compares the resolved facts).
4. `:on-match` events run **server- and client-side**. SSR dispatches them through the request-local frame so synchronous event effects stay symmetric, but SSR does **not** wait for an arbitrary asynchronous tail (the only route-owned server wait is a blocking `:resources` requirement; see [§Server-side rendering integration](#server-side-rendering-integration)). Hydration does *not* re-fire `:on-match` — the seeded `app-db` already contains the data.
5. Each `:on-match` event is an ordinary event vector. Handlers may emit any `:fx` (typically `:http`, etc.). A synchronous handler **throw** stays visible through the ordinary [009](009-Instrumentation.md) event error channel, attributed to the event that threw — it is **not** rewritten into route-loader state. Applications that start asynchronous work from `:on-match` own its status and error state in the same event/effect subsystem that owns the work. The events are also enumerable: `(rf/handler-meta :route :route/cart)` returns the metadata, so tooling can render activation dependency graphs.

The `:on-match` list is the **enumerable, machine-readable** answer to "what runs when this route activates?" `:on-match` is the canonical surface.

### Route `:resources` and named scope resolvers (EP-0016 integration)

The `:resources` route-metadata key (owned by the [Resources artefact](016-Resources.md#route-integration), late-bound through the `:routing/extra-route-keys` / `:routing/on-route-entry` / `:routing/on-route-prefetch` seam above) carries a vector of route-resource entries; each entry names a `:resource`, its `:params`, an optional `:scope`, and `:blocking?` / `:when` / `:keep-previous?` flags. [EP-0016](../docs/EP/EP-0016-resource-mutation-completion.md) Decision 3 extends the entry `:scope` slot — the only routing-visible change in this action wave:

- a route-resource entry `:scope` MAY be a **named scope resolver reference** `{:from-db <resolver-id>}` (a `reg-resource-scope` resolver, [016 §Resolver references](016-Resources.md#resolver-references--from-db-id)), in addition to the existing concrete value or `(fn [route ctx] …)` route resolver;
- a `{:from-db …}` reference is **resolved at route-entry planning time against the frame db** — the resources route-entry plan (`:routing/on-route-entry`) runs the named resolver before planning the resource's ensure, so route ownership, the blocking slot under the nav-token, and route-leave release all key on the resolved concrete scope;
- resolution is **fail-closed**: a resolver that returns `nil` at a route-resource site is a route/resource **planning error** (the canonical `:rf.error/resource-route-plan`, surfaced on the route slice's transition/error state and Xray), **never** a silent substitution of `:rf.scope/global` and never a silent skip. This is the routing-side application of the resources fail-closed scope boundary;
- the route-resource `:params` / `:scope` / `:when` functions remain the one site with a **populated** planning context — `(fn [route ctx] …)` — because route-entry planning has a real route match and planning context to thread (contrast the reserved-nil `ctx` on resource/mutation fns, [016 §The `ctx` argument is reserved](016-Resources.md#the-ctx-argument-is-reserved-across-resourcemutation-fn-surfaces)).

The mechanism is the existing late-bound seam; the design change is purely the resolver-reference `:scope` form and its planning-time, fail-closed resolution. Spec 016 owns the full route-resource plan contract ([016 §Route integration](016-Resources.md#route-integration)); 012 names only this accepted-`:scope`-form extension.

> **`:on-match` events read route params from cofx, not from the event vector.** Each `:on-match` event runs with full access to the frame's state via cofx, including the freshly-written route slice. The route slice is framework-owned **runtime-db** state (per [§The `:rf/route` slice](#the-rfroute-slice)), so a handler that needs params/query reads them from the `:rf.db/runtime` cofx — `(get-in (:rf.db/runtime cofx) [:rf.runtime/routing :current])` — or, more idiomatically, derives them through the public route subs (`:rf.route/params`, `:rf.route/query`). It does NOT read the slice from the app `:db` cofx (the slice does not live in app-db). The event vector carries no param substitution.

### Route readiness is a resource projection

The public `:rf.route/transition` and `:rf.route/error` reads name the route's **honest readiness** — whether the managed page-read data the route requires is present, still loading, or failed. Their meaning is a **pure projection over the active route plan's blocking resource requirements** (EP-0037 §Route readiness is a resource projection), *not* a state driven by the `:on-match` drain:

| Effective-plan state | `:rf.route/transition` | `:rf.route/error` |
|---|---|---|
| plan could not be formed | `:error` | the structured planning error (`:rf.error/resource-route-plan`) |
| any blocking first load is pending and none has failed | `:loading` | `nil` |
| a blocking first load failed | `:error` | the deterministic first failure — the outstanding blocking requirements are keyed by canonical CEDN-1 resource-key identity and the pick is ordered by that key, not by hash or plan order, so it is stable across settles that prune siblings (`:rf.error/resource-route-blocking`) |
| all blocking requirements have usable data, or there are none | `:idle` | `nil` |
| Resources artefact absent | `:idle` | `nil` |

The table uses [Spec 016](016-Resources.md) resource terms, not router-local guesses:

- a blocking requirement has **usable data** when its active resource identity projects `:rf.resource/has-data? true`;
- a blocking **first load is pending** when that identity has no usable data and work capable of settling it exists — it projects `:rf.resource/loading? true`, or it is absent / `:idle` with its first attempt still to come. An identity that has *already spent* an attempt and settled with no usable data and no error (a first load that was **aborted**) is neither pending nor failed: nothing will ever settle it, so it stops blocking rather than holding the route `:loading` forever;
- a blocking **first load failed** when the identity has no usable data and its resource status is `:error`; and
- `:fetching` **with** usable data is a background **refresh** (including a stale revalidation). It never makes the route `:loading`, and a refresh failure stays on the resource's `:refresh-error` channel — it does not make the route `:error`.

**Previous-data projection** for a newly-keyed requirement (`:keep-previous?`) may keep useful pixels on screen, but it does **not** make the new identity's first load complete: the route stays `:loading` until the new identity has its own usable data. A **non-blocking** first load, an intent **prefetch**, and arbitrary **`:on-match`** work likewise never change route transition — their honest status lives on their owning resource / application read model.

This projection has **one pure implementation** used by the `:rf.route/transition` / `:rf.route/error` subscriptions, SSR wait / render decisions, Xray, and any cached route-slice fields. A runtime **may** cache the projected `:transition` / `:error` in the stored slice (runtime-db) for efficient whole-route reads and incremental updates, but the cache is **not** independent authority: it must be reconstructible from the active plan plus managed-resource / planning state, and it is reconciled through the same projector on **hydration**, **epoch restore**, and **every resource settlement**. Hydration and epoch restore must not preserve a route `:loading` (or `:error`) value that the restored resource state contradicts (per OI-2, 2026-07-24).

R1 applies this projector to R0's behaviour-preserving **leaf-only** resource plan. R2 replaces that input with the effective **parent-to-leaf** branch plan; it does not change the readiness table or fork the projector. This staging lets activation honesty land before branch composition without an interim readiness contract.

<a id="route-plan-prefetch--warm-mode-intent-preload"></a>
### Route-plan prefetch — warm-mode intent preload

Routing adds one public event that warms a destination's data **before** the user commits to navigating — hovering a link warms the article it points at, so the click lands on data already in flight or cached (EP-0037 §Resource-only intent prefetch, OI-3):

```clojure
[:rf.route/prefetch {:to :route/article :params {:slug slug}}]
```

It accepts a named `:rf/route-address` (never a raw `:url`), resolves the destination, builds the **same** effective parent-to-leaf resource plan a full activation would (the same `:parent` walk, the same per-entry `:when` / `:params` / `:scope` resolution, the same identity dedupe), and runs each unique requirement through the [Resources artefact](016-Resources.md#route-integration) in **warm mode**. It is frame-scoped: the event runs in — and warms only — the frame it was dispatched to.

Warm mode has a deliberately narrow contract (EP-0037 §Resource-only intent prefetch; [016 §Route integration](016-Resources.md#route-integration)):

- every ensure is **ownerless** and carries cause `[:route-prefetch <destination-route-id>]`, so a warmed entry no navigation ever claims stays GC-eligible under ordinary resource freshness / dedupe / GC — a prefetch that is never followed leaves no durable owner;
- `:blocking?` is **inert** — a prefetch never blocks anything and records no blocking slot;
- **no** route state changes: no `:rf/route` slice write, no nav-token, no URL, no history, no scroll, no focus, no pending-navigation, and no `:rf.route/transition` / `:rf.route/error` change on the live route;
- **no** `:can-leave`, `:can-enter`, or `:on-match` runs — warmup is not activation;
- a resource **failure stays a resource failure** on its own trace / status channel; it never becomes a route error or touches route readiness; and
- a later activation of the same destination **reuses** the fresh or in-flight warmed work and attaches its real `[:route route-id nav-token]` owner before normal activation proceeds — the ordinary `ensure` dedupe / freshness path, so a prefetch followed by a click issues no duplicate fetch.

A prefetch never warms or attaches work in a **sibling frame** merely because that frame resolves the same address or scoped resource identity — the carried-frame invariant applies to planning, trace attribution, cache entries, and teardown, exactly as it does for navigation.

**Bad address vs planning failure are distinct.** An **invalid address** rejects *before* planning with `:rf.error/prefetch-bad-address`, dispatches no ensures, emits no summary trace, and leaves current readiness untouched. Invalid has two halves, gated in this order: anything that is not a closed `:rf/route-address`, and then any well-formed address whose named destination does not **resolve** — an unregistered `:to`, a missing required path param, or `:params` / `:query` the route's declared schemas reject. The second half is adjudicated by the same named-destination resolution boundary `route-url` and the programmatic door use, so **prefetch can only ever warm the registered, validated destination a full activation would**: warming a nonexistent route, or the wrong resource identity because a required param was omitted, is not a prefetch. Neither half reports a carrier value — the diagnostic names the offending **key** (or slot) and the route id. A well-formed address, for a destination that *does* resolve, whose **resource plan cannot be built** (a fail-closed `:params` / `:scope` / `:when` throw, a missing / cyclic `:after`, or an unresolved / cyclic `:parent`) emits the ordinary `:rf.error/resource-route-plan` diagnostic with `:plan-cause :prefetch`, dispatches **no partial ensures**, and — because prefetch owns no route state — does not alter current route readiness. When the Resources artefact is absent (or the effective plan is empty), the warm plan is empty and the event performs **no work** beyond its summary trace; prefetch does not make Resources a mandatory routing dependency.

**Prefetch is a performance hint, not an authorization boundary.** Resource requests already enforce scope and server authorization; running entry guards during warmup would make prefetch a partial navigation and still would not be a security boundary. Prefetching a destination whose `:can-enter` would later **deny** is therefore permitted — it may warm an already-authorized resource cache and means nothing more. Activation still evaluates and may deny entry.

**One summary trace.** A prefetch emits exactly one `:rf.route/prefetched` trace (`:route-id` / `:warmed` / `:frame`, `:plan-error true` on a planning failure); its underlying resource-plan and ensure traces carry `:plan-cause :prefetch` and no nav-token, distinguishing warm work from a navigation commit on the trace / Xray stream.

**Non-goals (this slice).** There is no global default, render mode, viewport mode, hover-delay option, separate preload cache, or prefetch-stale clock — resource freshness and dedupe already answer whether work is useful. Raw URLs, external links, guards, `:on-match`, and route-driven code chunks are not prefetched. A future trigger or code-loading consumer can be proposed without changing warm-mode semantics.

## Route-not-found — `:rf.route/not-found` (canonical)

`:rf.route/not-found` is a **special-cased route id** the runtime dispatches to whenever a URL fails to match any registered route. It is **registered by the user**, exactly like any other route — the runtime does not auto-register it; the framework's only special-casing is the *target id* it routes to on no-match. This keeps not-found rendering, head metadata, and `:on-match` events behaving identically to any other route.

```clojure
(rf/reg-route :rf.route/not-found
  {:doc      "404 page."
   :on-match [[:analytics/log-404]]
   :scroll   :top}
  "/404")                                ;; required, but rarely matched directly — the runtime
                                         ;; routes URL-driven misses here regardless of the pattern
```

Semantics:

1. **Trigger.** When `match-url` returns `nil` (no path-pattern matches), or when validation failure routes to "not found" (per [§Param validation at the call site](#param-validation-at-the-call-site)), the runtime sets `:rf/route` to `{:route-id :rf.route/not-found :params {:url <url>} ...}` and proceeds with that route's `:on-match` events.
2. **Same machinery.** `:rf.route/not-found` is an ordinary `reg-route`. It can declare `:on-match`, `:scroll`, `:head`, `:tags` — all behave normally. The view tree's `case` over `:rf.route/id` renders the not-found view from the leaf.
3. **Required by contract.** Apps **must** register a `:rf.route/not-found` route. If no `:rf.route/not-found` is registered when an unmatched URL arrives, the runtime emits a `:rf.warning/no-not-found-route` trace event and falls back to a built-in placeholder view (a minimal `<h1>Not Found</h1>` page) so the request still produces a response. Test fixtures and the conformance corpus assume the user-registered shape.
4. **Validation failures.** A URL that matches a route's path but fails the route's `:params` / `:query` schema also routes to `:rf.route/not-found`, with `:reason :validation` in the `:params` slice (per [§URL changes are events](#url-changes-are-events)).
5. **Malformed percent-encoding.** A URL carrying malformed `%`-sequences (`%`, `%a`, `%XX`, …) produces a route-miss, **never an exception**. Malformed encoding anywhere in the URL — captured path segments, query keys, query values, or the `#fragment` portion — fails the whole match closed: `match-url` returns nil and the URL-change handlers (`:rf.route/transitioned` / `:rf.route/handle-url-change`) write `:rf.route/not-found` with `{:url url :reason :malformed-url}` in the slice's `:params`. A `:rf.warning/malformed-url` trace fires alongside the standard `:rf.error/no-such-handler`. The `:reason` discriminator distinguishes the malformed-URL case from a bare miss (`{:url url}`) and from a validation failure (`{:url url :reason :validation}`) Hostile URLs, partner integrations with broken escaping, and back-button to a malformed link must never crash a request handler on SSR.
6. **Reserved id.** `:rf.route/not-found` is the **single locked id** for this purpose. Implementations and tools depend on it; users do not redefine the meaning of the keyword. Hosts that want a different visual treatment per error kind branch inside the `:rf.route/not-found` view (e.g., on `:reason`).

Tooling enumerates `(rf/handler-meta :route :rf.route/not-found)` to confirm the route is registered; the registrar emits the warning trace event at the first unmatched URL if it isn't.

## Per-route error handling

There is **no** route-owned `:on-match` error handling, and no corpus-wide error correlation back to the route. `:on-match` is fire-and-forget ([§Per-route data loading](#per-route-data-loading)): the runtime dispatches its events and does not observe, correlate, or rewrite their outcomes into route state. Two honest channels carry failures instead:

1. **A synchronous `:on-match` handler throw** is visible through the ordinary [009](009-Instrumentation.md#error-contract) event error channel (`:rf.error/handler-exception`), **attributed to the event that threw** — not to the route. It carries no routing-domain attribution slots and does not flip `:rf.route/transition`. Tools, off-box shippers, and application error listeners observe it exactly as they observe any other event throw.
2. **Managed page-read failures** surface through the resource-derived readiness projection ([§Route readiness is a resource projection](#route-readiness-is-a-resource-projection)): a blocking first-load failure projects `:rf.route/transition :error` with the deterministic first failure (`:rf.error/resource-route-blocking`) on `:rf.route/error`; a planning failure projects `:error` with `:rf.error/resource-route-plan`. Views inspect those reads and render an error surface. A background-refresh failure stays on the resource's `:refresh-error` channel and does **not** make the route `:error`.

> **Migration — `:on-error` as "page failed" is retired.** Route metadata `:on-error`, the internal `:rf.route.internal/on-match-error` trap and its `:rf.route/on-match-error-trap` listener, and the `:rf.route/on-match-id` / `:rf.route/on-match-frame` attribution slots are removed with **no alias** (EP-0037 §Backwards Compatibility). A guide or app that used `:on-error` to mean *"a required page read failed"* declares that read as a **blocking `:resources`** requirement and renders off `:rf.route/transition :error` + `:rf.route/error`. Work that `:on-match` merely *starts* (analytics, host notification, application-owned async) keeps its status and error state in the same event/effect subsystem that owns it — the router no longer manufactures a route error from it, and there is no longer a first-error-wins ordering or an identical-vector attribution caveat to reason about.

## Navigation tokens — stale-result suppression

When a route is loading and the user navigates away before the load completes, the older load's result can land *after* the user has moved on, clobbering newer state. This is a real bug class — React Router and TanStack Router both explicitly handle it. Re-frame2's answer is the **navigation-token (nav-token) epoch**: a per-navigation token allocated when a route becomes active, carried by every async result, and validated on receipt. This is the same idiom used by `:after` timers per [005 §Epoch-based stale detection](005-StateMachines.md#epoch-based-stale-detection); see also the cross-cutting [Pattern-StaleDetection.md](Pattern-StaleDetection.md) for why the pattern recurs.

### Mechanism

1. **Allocation.** When a navigation drain commits the slice — the programmatic `:rf.route/navigate` handler (which writes the slice inline; see [§Navigation is an event](#navigation-is-an-event)), or a URL-change handler `:rf.route/transitioned` (forward nav driven by a `route-link` click via `:rf.route/url-requested`) or `:rf.route/handle-url-change` (popstate / initial / SSR) — it allocates a fresh `:nav-token` (a gensym or monotonic counter) and writes it to the `:rf/route` slice alongside the new id/params/query/fragment.

   > **Two commits do NOT allocate: the identical and fragment-only short-circuits.** "Commits the slice" above means a *route (re)activation* — a change to `:route-id`, `:params`, or `:query`. Two resolved targets skip allocation entirely and keep the standing token: an **identical** re-navigation (nothing changed — [§Per-route data loading](#per-route-data-loading) rule 3) and a **fragment-only** change (same route-id/params/query, different `#fragment` — [§Fragments](#fragment-only-changes-do-not-re-fire-on-match)). Both hold the current `:nav-token` so a still-in-flight loader for the unchanged route stays eligible; neither is a new epoch. This holds whichever door the navigation entered.

   > **The allocation counter is monotone and unbounded.** A nav-token need only be *unique within the lifetime of any in-flight async continuation*; equality against the current slice token is the only operation performed on it (step 4). A per-frame monotonic counter satisfies uniqueness without ever needing to wrap or reset. It does NOT carry the bounded-structure treatment applied to other *retained* collections (the host-side scroll-position LRU cache, the route-registry decoded-key cap): those bound collections that would otherwise accumulate entries, whereas the counter is a single scalar that retains nothing — it is GC'd whole when the frame is destroyed. Practical overflow is a non-concern: on CLJS the counter is an IEEE-754 double (exact integers to 2^53, far beyond any real navigation count); on the JVM it is a `long` that would overflow only after 2^63 navigations. No id collision is possible because each token is a fresh string. Implementations MUST NOT wrap or recycle the counter — doing so would risk colliding a freshly-allocated token with one still carried by a slow in-flight continuation, silently re-validating a stale result.

   > **Storage: the counter is host-side transient, NOT `runtime-db`.** The *active* `:nav-token` is a durable fact on the route slice (`[:rf.runtime/routing :current :nav-token]`) and restores coherently with the rest of the slice. The *allocator* (the `:nav-token-counter` / `:pending-nav-counter` monotonic high-water marks) is held in a host-side per-frame transient cache (a module-private atom, mirroring the scroll-position cache). The two are separated: an epoch restore replaces the `runtime-db` partition **wholesale**, which would rewind a runtime-db-resident counter to its value at the restored epoch — and a pre-restore in-flight async continuation (a request already on the wire, uncancellable) returning afterward could then carry a token the post-restore timeline has *re-allocated*, the exact recycle the rule above forbids. Held host-side, the counter is untouched by restore, so every post-restore allocation strictly exceeds any pre-restore in-flight token and a collision is structurally impossible. Conflating the allocator (whose property is *never rewind*) with the active token (which *should* restore) in one restorable partition is a category error.

   > **The minted id is a RECORDABLE allocation coeffect — replay re-presents it verbatim.** The handlers stay pure, but the id they publish must be **recorded on the causal token** so record→replay reproduces the *same* id. (If the id were re-minted from the ambient counter on replay, recorded events referencing the original would mismatch: a recorded `[:rf.route/continue "pn-1"]` would no-op against a re-minted `"pn-2"` and the navigation would stay blocked; an async continuation carrying a recorded `:nav-token` would flip the stale-suppression gate against a re-minted current token.) So the id rides one of two **recordable, generator-backed** allocation coeffects (one per allocator — they are *two distinct facts* because they are two distinct allocators):
   >
   > | coeffect | shape | minted by |
   > |---|---|---|
   > | `:rf.route/nav-allocation` | `{:token "nav-N" :counter N}` | the nav commit handlers (`:rf.route/navigate`, `:rf.route/transitioned`, `:rf.route/handle-url-change`) |
   > | `:rf.route/pending-nav-allocation` | `{:id "pn-N" :counter N}` | the leave-guard block (`:rf.route/url-requested` + the same commit handlers) |
   >
   > Each generator mints the next id from the host high-water snapshot at processing-start (router `:live` policy); the cofx machinery **records the produced allocation onto the causal token** (per [001 §`reg-cofx`](001-Registration.md) recordable grade / EP-0017 §5) so the epoch captures it. Strict replay re-presents the recorded id verbatim — the generator does not run — and **FAILS LOUDLY (`:rf.error/missing-required-cofx`) if the recorded allocation is absent** (an incomplete record must not silently re-read the host). The handler writes only the `:token` / `:id` into `runtime-db` and rides the allocation's `:counter` on the `:rf.route/commit-nav-counter` fx, which advances the host high-water with **`max`** — so a restore/replay re-establishes the allocator from the recorded `:counter` and can never rewind it. This is the same write-via-fx seam the scroll cache uses; the read half is the recordable allocation cofx rather than an ambient snapshot read.
2. **Capture.** An `:on-match`-reached handler declares the framework-supplied `:rf.route/nav-token` cofx via `{:rf.cofx/requires [:rf.route/nav-token]}`; the value-returning supplier delivers the current token (read from `[:rf.runtime/routing :current :nav-token]`) flat under `:rf.route/nav-token` in the handler's coeffects, so the handler captures the epoch live at scheduling time. A loader SHOULD also declare the companion `:rf.route/route-id` cofx (the live route id, read from `[:rf.runtime/routing :current :route-id]`) so it captures **both** facts the route-loader [work id](Managed-Effects.md#work-id-correlation) `[:rf.work/route route-id nav-token loader-id]` needs together — the documented path then cannot thread a nil route id into the work-id tuple (the route id is a *carried* fact of the attempt, captured at scheduling time, never read from the live slice at stale-arrival where a cross-route completion's slice id would be the superseding route's):

   ```clojure
   (rf/reg-event :cart/load-items
     {:rf.cofx/requires [:rf.route/nav-token :rf.route/route-id]}    ;; <-- declare BOTH cofx
     (fn [{:keys [db] :rf.route/keys [nav-token route-id]} _]        ;; <-- "nav-42" + :route/cart, live at scheduling time
       ...))
   ```

3. **Threading.** Async completions either (a) carry the captured facts in their follow-up event payload, or (b) use the framework-supplied `:rf.route/with-nav-token` fx wrapper, which names the continuation by the canonical `:rf/reply-to` reply target (the [uniform reply envelope](Managed-Effects.md#the-uniform-reply-envelope) lowering — on match the route loader's `:status :ok` reply map is appended to the target via the shared `re-frame.reply/complete`; on mismatch the completion is suppressed and the app target is never dispatched — a stale completion never app-delivers) and threads the captured token + route id for the gate and the work-id:

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

- **`:rf.route.nav-token/allocated`** — emitted when a navigation drain allocates a fresh token. `:tags {:route-id <id> :nav-token <token> :frame <navigating-frame>}` (the `:frame` — see the frame-attribution note under [§Trace events](#trace-events)).
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
| `:rf.route/transitioned` | Forward navigation from a `route-link` click: dispatched by `:rf.route/url-requested` after the `:can-leave` gate passes and the in-app URL is pushed onto history. (Programmatic `:rf.route/navigate` does **not** route through this event — it commits the slice inline; see [§Navigation is an event](#navigation-is-an-event).) | Rewrites the `:rf/route` slice from the URL (match → validate → fragment-only short-circuit → full rewrite + `:on-match` drain). Default scroll strategy `:top`. |
| `:rf.route/handle-url-change` | Popstate (Back/Forward), initial page load, and the server-side request URL during SSR. | The **same** slice-rewrite logic as `:rf.route/transitioned` — they are co-equal sibling handlers, not a delegate pair. Differs only in the default scroll strategy `:restore` (the saved position for the URL being returned to) and in threading the `:frame` id so SSR error projections can attribute a `:no-such-handler` trace per-frame. |
| `:rf.route/url-requested` | The user clicked a link the framework owns (a `route-link` view, or any `<a>` whose `href` resolved to a registered route). The handler decides whether the request is in-app or external, runs the active route's `:can-leave` guard for an in-app request, and on a clear request pushes the URL and synthesises the transition. | Classifies in-app vs external by **origin comparison** — on the client the URL is resolved against the browser `Location` and its origin compared; on the JVM / SSR / no-`window` path it falls back to a fail-**closed** lexical check (only a provably same-origin rooted path / pure query / pure fragment is in-app — see [§Open-redirect fail-closed classification](#open-redirect-fail-closed-classification)). An **external** request emits a `:rf.route/external-url-requested` trace and does nothing else (no push — the browser follows the link). An **in-app** request runs the current route's `:can-leave` guard (blocking via the pending-nav protocol if rejected), then pushes the in-app URL and dispatches `:rf.route/transitioned`. Users can override to enforce per-frame policy (auth-guard, modifier-key handling, etc.). |

`:rf.route/url-requested` is the **decision point** for navigation policy. The policy is enumerable and testable: dispatch `[:rf.route/url-requested {:url "/cart"}]` from a test and observe the result — an in-app request pushes the URL and dispatches `:rf.route/transitioned`; an external request emits a `:rf.route/external-url-requested` trace and pushes nothing — no DOM simulation required.

#### Open-redirect fail-closed classification

The in-app-vs-external decision is the open-redirect defense: a `:rf.route/url-requested` request whose URL is **not** provably same-origin must not be pushed into history as if it were an in-app route. The same classifier gates the `:rf.route/navigate` `{:url ...}` programmatic escape hatch, so both URL-string nav sinks fail closed through **one** decision rather than each entry point deciding independently.

Classification is **origin-based**, not match-based — a same-host URL that happens not to match any registered route is still in-app (it routes to `:rf.route/not-found`), and an absolute / protocol-relative / scheme-bearing URL is external regardless of whether it would match:

- **Client (browser `Location` available).** The URL is resolved against the document origin (`new URL(url, location.href)`) and classed external when its protocol is not `http(s):` or its origin differs from the document's.
- **JVM / SSR / no-`window` (and any client resolution failure).** There is no browser `Location` to origin-compare against, so the runtime cannot *prove* same-origin — it falls back to a **fail-closed** lexical check. A URL is in-app only when, after rejecting any embedded whitespace or ASCII control character (which browsers strip mid-URL before parsing), it is a single-leading-`/` rooted path **not** followed by `/` or `\` (a protocol-relative authority a browser reads as off-origin), or a pure `?query` / `#fragment` reference. A leading `//`, a `/\`, a scheme (`name:`), a bare relative segment, and the empty string all class **external**. This is a deliberate client/server asymmetry: the client proves same-origin against the live origin; the server defaults to deny.

An external classification emits `:rf.route/external-url-requested` (carrying `:tags {:url <url> :frame <navigating-frame>}` — the `:frame` enters the navigating frame's epoch trace-events and obeys the frame trace-disable gate, symmetric across the link-click `:rf.route/url-requested` and programmatic `:rf.route/navigate {:url …}` paths) and performs no push; an in-app classification normalises the URL to its origin-relative form and continues into the `:can-leave` / push / `:rf.route/transitioned` path above.

`route-link` ships in the routing artefact as a registered view at id `:route/link`. The body:

```clojure
(rf/reg-view ^{:rf/id :route/link} route-link
  [{:keys [to params query fragment on-click] :as props} & children]
  (let [url   (rf.routing/route-url {:to to :params (or params {}) :query (or query {}) :fragment fragment})
        attrs (-> props
                  (dissoc :to :params :query :fragment :on-click)
                  (assoc :href url
                         :on-click
                         (fn [e]
                           (when on-click (on-click e))
                           (when (and (not (.-defaultPrevented e))
                                      (plain-left-click? e))   ;; no modifier keys; primary button
                             (.preventDefault e)
                             (dispatch [:rf.route/url-requested
                                        (cond-> {:url url :to to}
                                          (seq params) (assoc :params params)
                                          (seq query)  (assoc :query query)
                                          fragment     (assoc :fragment fragment))])))))]
    (into [:a attrs] children)))
```

The view exposes three behavioural seams: passthrough attributes (`:class`, `:title`, `:id`, `:aria-label`, …) flow through to the `<a>`; a caller-supplied `:on-click` runs before the framework's interception and can pre-empt it by calling `.preventDefault`; and modifier-key clicks defer to the browser so middle-click / cmd-click / shift-click keep their native open-in-new-tab affordances. `route-url` is the single point where the URL is synthesised, so route-rename and route-shape changes flow into every `route-link` site without per-link edits.

**Native-anchor attributes are never intercepted.** A passthrough attribute whose semantics require the browser to handle the click — a `target` other than `_self` (`_blank` / `_parent` / `_top` / a named frame), or `download` — defers to the browser even on a plain left-click, the same as a modifier-key click. SPA interception would convert a `{:target "_blank"}` link into same-document navigation and a `{:download …}` link into a no-op navigation, silently breaking the native anchor contract the DOM attributes advertise. The framework treats these attributes as a fourth defer-to-browser seam alongside modifier/middle clicks and caller `.preventDefault`: the link still renders as a real `<a href=…>` with the attributes, and the click is left for the browser. Authors who want SPA interception omit those attributes (or set `:target "_self"`).

#### The Freehand route-link descriptor

`v/route-link` is the view-substrate spelling of the law above. It is **ordinary
framework view code** — a `v/defview` holding the same descriptor an application
view holds (uncallable in the same way, per
[004 §A declared view cannot be called](004-Views.md#a-declared-view-cannot-be-called)),
taking the same one props map, lowered by the same emitters. There is no route-link intrinsic, and there is no second routing
contract: this section is the whole of what the view adds, and everything it
does with a URL or a click is the routing law already stated above.

```clojure
[v/route-link {:to :article :params {:slug slug} :class "title"}
 title]
```

The view's control keys are `:to` / `:params` / `:query` / `:fragment`,
`:on-click` and `:prefetch`. **`:to` is required**; `:params` / `:query` /
`:fragment` feed both the href and the dispatch payload. Every other key is an HTML attribute and
reaches the `<a>` untouched, so `:class`, `:title`, `:aria-label`, `:target` and
`:download` work without the view enumerating the attribute space. The
framework-owned `:href` wins over a caller-supplied one; no route key ever leaks
onto the element.

**The anchor is real.** The rendered element is an `<a>` carrying the route's
strategy-encoded href, because everything a link is expected to do outside a
plain left click is the browser's, not the framework's: copy-link,
open-in-new-tab, keyboard activation, the status-bar preview, a reader with
JavaScript disabled, and a crawler. A link that intercepts a click but has no
`href` is not a link.

**The frame is captured at render, not at click.** A click fires long after the
render-time frame scope has unwound, so resolving the frame ambiently at click
time would raise `:rf.error/no-frame-context` or route to the wrong frame. The
capture happens at render — and so does its failure: a link rendered outside any
frame scope fails at the render site, with the render stack, rather than at a
detached click.

**Native behaviour is not overridden.** A modifier click, an auxiliary-button
click, a `:download` anchor and a `:target` other than `_self` are left to the
browser exactly as they are for `rf/route-link`. This is the seam that decides
whether a link feels native, and it is not one an application should have to
re-derive per link — which is the reason the framework supplies this view at all.

**A caller `:on-click` runs first and may veto.** It is invoked before the
navigation decision, exactly once, sees an event nothing has prevented yet, and
if it prevents the default the framework stands down and the caller owns the
outcome.

**`:on-click` is the imperative pre-navigation seam, and its accepted grammar is
closed.** A plain function, a `v/handler`, or nothing — and anything else is
rejected AT RENDER with `:rf.error/view-bad-event` and the recovery
`:use-a-plain-fn-or-v-handler`. The narrowing is the contract, not an omission.
Freehand teaches `:on-click [:app/event …]` as ordinary intent data everywhere
else, so the reason it cannot mean that here has to be stated rather than
discovered: a route click already produces the ONE routing intent
(`:rf.route/url-requested`) and everything that follows from it, so a second
intent site on the same click would be one user action yielding two semantic
events. An application reaction belongs behind the routing event or its
transition. What is genuinely needed here is the other thing — imperative work
that runs before the decision and can stop it (`.preventDefault`, a confirm
dialog, an analytics ping) — which is exactly `v/handler`'s declared role, and a
bare function is its unceremonious spelling. A `v/handler` is unwrapped to the
function it carries before routing sees it: a roster callback is deliberately
not `IFn`, so fencing without translating would have left the declared
imperative form as the one thing that did not work here. The check runs on both
hosts, so a form the browser would misuse is refused by the server render too.

**`:prefetch :intent` makes the three intent positions framework-owned, and the
same closed grammar governs them.** An opted-in link installs its own
`:on-mouse-enter` / `:on-focus` / `:on-touch-start` handlers and runs the
caller's first — and composing that way means the framework has to be able to
CALL what it found, so those three positions accept exactly what `:on-click`
accepts (a plain function, a `v/handler`, or nothing), anything else rejected at
render on both hosts with the same `:rf.error/view-bad-event` and
`:use-a-plain-fn-or-v-handler`. The narrowing is **conditional on the opt-in**:
every other link leaves all three as ordinary open Freehand event positions, so
hover intent spelled as event data (`:on-mouse-enter [:app/hovered]`) keeps
working everywhere else. An author who wants an application event on hover *and*
a warm-up keeps the position as it is and dispatches `:rf.route/prefetch`
themselves — the link opt is sugar over that event.

**The server shell is the same anchor without the handler.** The JVM render
emits the path-form href and no click handler — a serialized document has no
click to intercept and must not carry a host closure — and the hydrated client
re-encodes through the frame's URL strategy, per
[§SSR ignores strategies](#ssr-ignores-strategies).

**Without the routing artefact it fails loud, at render.** Routing is optional,
so `v/route-link` resolves the seam before it builds anything; an unpublished
hook raises `:rf.error/routing-artefact-missing`, naming the view and the link's
`:to`. It never renders a half-formed anchor, and never a silently dead one. A
plain `[:a]` remains available for intentional browser-native navigation.

These statements are proven row by row under the `FH-ROUTELINK` area of the
[Freehand conformance index](conformance/freehand/conformance-index.md).

## Scroll restoration

Browser-default behaviour on `popstate` restores scroll position. For SPA-controlled scroll (e.g., scroll to top on forward navigation, restore on back), declare a `:scroll` strategy on the route or pass `:scroll` in the `:rf.route/navigate` request map.

The `:scroll` value is one of:

| Value | Behaviour |
|---|---|
| `:top` | Scroll to top of page (`window.scrollTo(0,0)`) — **unless a `#fragment` is present**, in which case `:top` scrolls the fragment's element into view (falling back to top if the element is absent); see [§`:rf.nav/scroll` integration](#rfnavscroll-integration). |
| `:restore` | Restore the saved scroll position for this URL (the runtime captures positions on every navigation; SSR-side: no-op). |
| `:preserve` | Do nothing (current scroll position stays as is). |
| `nil` / absent | **Not** `:preserve` — the implicit default applies (resolution order below): `:top` on forward navigation, `:restore` on popstate / initial. To genuinely suppress scrolling, declare `:preserve` (keeps the `:rf.nav/scroll` fx, which does nothing) or `false` (skips the fx entirely). |

The vocabulary is **closed** to those three keywords. Any other value — including a map — is rejected: the `:rf.nav/scroll` args schema (`:rf.fx.nav/scroll-args`) enumerates exactly `:top`, `:restore`, `:preserve`, so an unsupported strategy fails at the `:fx-args` boundary and the effect is skipped ([010 §Validation order step 5](010-Schemas.md#validation-order-on-event-processing)); and the registered fx handler — the always-on leg, since the schemas artefact is optional — emits `:rf.error/unsupported-scroll-strategy` rather than doing nothing. That handler leg fans through the two-channel error seam (`re-frame.error-emit/emit-error-both!`), so the rejection is genuinely unconditional: it survives `:advanced` + `goog.DEBUG=false` on a host that never loaded the schemas artefact, where the dev trace is elided. See [§Custom scroll strategies](#custom-scroll-strategies) and [009 §Error event catalogue](009-Instrumentation.md#error-event-catalogue).

Resolution order at navigation time:
1. `:scroll` key in `:rf.route/navigate`'s request map (per-call override). Wins.
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
      :preserve nil
      ;; Closed vocabulary — an unrecognised strategy is a caller bug.
      ;; Fanned on BOTH error channels, not the dev trace alone: this branch
      ;; is the only rejection a schemas-less host gets, so it must survive
      ;; `:advanced` + `goog.DEBUG=false` (009 §Error event catalogue).
      ;;
      ;; The two channels carry DIFFERENT payloads. `:strategy` may be any
      ;; runtime value, and the always-on record is merged past the elision
      ;; seam and ships off-box — so the raw value rides the DEV TRACE only,
      ;; and the record carries a closed-vocabulary SHAPE tag instead.
      (emit-error-both! :rf.error/unsupported-scroll-strategy
                        ;; axis 2 — dev trace, DCE'd in production
                        {:strategy  strategy
                         :supported [:top :restore :preserve]
                         :recovery  :no-scroll}
                        ;; axis 1 — always-on record: structural and bounded
                        {:supported     [:top :restore :preserve]
                         :strategy-type (:type (diag-value-summary strategy))
                         :recovery      :no-scroll}))))
```

### Custom scroll strategies

There are none. The `:scroll` vocabulary is the closed enum `:top` / `:restore` / `:preserve` (plus `false` to suppress the effect); the runtime has no registry, callback, or late-bound hook that would interpret any other value.

Earlier drafts of this section admitted a **map** form — "hosts may supply additional shapes, e.g. `{:to :element :selector "#article"}`" — against a named-strategy registry that was deferred until a real need appeared. The registry never arrived, so the map form was a **false promise**: `:rf.nav/scroll`'s args schema accepted any map, the planner carried it through verbatim, and the fx handler's default branch returned `nil`. An author who wrote a plausible-looking map strategy got no scroll and no diagnostic — the code read correctly, ran clean, and did nothing. A documented value must have executable semantics; an accepted-and-ignored option is strictly worse than a rejected one, so the map form is **removed** rather than left as an unbacked extension point (rf2-px26m).

If a concrete host-specific strategy is ever required, the resolution is one explicit seam with a test that proves it executes — not a speculative registry, and not a re-opened `:any` slot.

## Query strings and fragments

The path syntax is the *primary* binding. Query strings are bound separately via the route's `:query` metadata key, which carries a schema for query-string coercion and validation (per Spec 010).

```clojure
(rf/reg-route :route/search
  {:query           [:map [:q :string] [:page {:optional true} :int]]
   :query-defaults  {:page 1}}
  "/search")

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

`:query-defaults` populates absent query keys. It is **destination-local declaration**: the route says what its own query means when a key is absent. There is no metadata key that reaches into *another* route's query — see [§Carrying query state across routes](#carrying-query-state-across-routes).

##### A declared default lives in the target, never in the URL

Because the declaration is destination-local, a filled default is a property of the **resolved target** — not of the URL that happened to request it. Two rules follow, and together they are what make `:params` / `:query` / `:query-defaults` genuinely "the contract surface that `match-url` and `route-url` agree on":

1. **Every door fills them.** Defaults are applied where the target is resolved: `match-url` fills them for the three URL-bearing doors, and the [one resolved-target seam](#resolved-target-and-the-plan-diagnostic-projection) fills them for the named-address doors — `[:rf.route/navigate {:to …}]`, `route-url`, `route-link`'s href projection, and `[:rf.route/prefetch …]`. A door that skipped the fill would resolve a different `:query`, derive a different URL, and select a different resource identity for one destination, which [§The one planning pipeline](#the-one-planning-pipeline) forbids ("Doors differ in cause and history / scroll policy, not in target, entry, resource, or readiness semantics"). It also breaks the prefetch contract in the quietest possible way: a link that warms `{:tab nil}` while the click activates `{:tab :overview}` produces *two* cache entries — the warm one ownerless and never reused — with no error and no warning to say so.
2. **`route-url` omits a key already at its default.** The emitted URL never spells a value the route would fill anyway, because `match-url` fills it back. So a target has exactly **one** canonical URL: a link whose address omits `:page` and a resolved target that carries `:page 1` derive the same href, rather than two history entries for one place. `/search?q=x` and `/search?q=x&page=1` are therefore the same destination — both resolve `{:q "x" :page 1}` — and the first is the canonical spelling. `route-url ∘ match-url` is consequently the identity on every URL re-frame2 emits.

The fill is **membership-only** — an explicitly supplied value always wins over the declared default, and a key the route declares no default for is untouched. It is not a normalisation pass, a per-door hook, or a public defaults-resolution API: it is one rule applied at the one place a target is shaped.

Coercion is data-shaped (the `:query` schema is the coercion specification — `:int` coerces `"2"` → `2`); per-key middleware functions are not part of the contract — data over functions.

`:int` coercion is **strict and host-identical**. A value is coerced to a number only when the *whole* string is an integer literal — `#"^-?\d+$"` (an optional sign followed by ASCII digits); partial-numeric, radix-prefixed, or whitespace-padded input (`"12abc"`, `"0x10"`, `" 12"`) is **left as a string on every host**. The route's `:query` schema then catches the type mismatch — a string in an `:int`-typed slot fails validation and surfaces `:validation-failed? true` (per [Spec 010](010-Schemas.md)), identically server- and client-side. This rule exists because `match-url` runs on both the JVM (SSR) and CLJS (browser); a host-divergent integer parser (`Long/parseLong` is strict, `js/parseInt` is lenient) would yield a different `:query` slice for the same URL on each host — exactly the [Spec 011](011-SSR.md) hydration-mismatch class, and a violation of the "same handler runs server- and client-side" contract and [Goal 2's cross-host conformance bar](000-Vision.md#ai-implementable-from-the-spec-alone). The strict regex makes the parse decision a pure function of the input string, host-independent. The conformance corpus pins `?page=12abc` and `?page=12` across both harnesses in [`routing-query-string-coercion.edn`](conformance/fixtures/routing-query-string-coercion.edn).

`:double` / decimal-typed route keys are **rejected fail-loud at `reg-route`**. A floating-point value has no canonical-EDN identity ([Conventions §Canonical EDN identity](Conventions.md#canonical-edn-identity) — floats, ratios, arbitrary-precision decimals, `NaN`, and infinities are all outside the CEDN-1 numeric domain), so a `:double`-typed `:params` or `:query` key breaks the route prism ([§Bidirectional URL ↔ params](#bidirectional-url--params)): `match-url` would coerce a URL segment to a float, but `route-url` **refuses to emit that same float** (it raises `:rf.error/route-url-non-edn-value` rather than host-stringify a non-CEDN value), so `route-url(match-url(url))` throws instead of recovering `url` — a URL-driven vs programmatic-navigation split. A float also diverges across hosts (a JVM `Double` vs a CLJS number; an integer-valued `2.0` is even admitted by CEDN on CLJS but rejected on the JVM), the same [Spec 011](011-SSR.md) hydration-mismatch class the `:int` rule guards. So `reg-route` throws `:rf.error/route-decimal-unsupported` naming the offending slot and key. Encode a decimal as a string (parse it in a handler) or use an `:int`-typed key. (Finite-decimal URL routing would first require a decimal URL-identity policy defined in canonical identity and the `route-url` guards — it is deliberately not smuggled in via lossy `:double` coercion.)

A **bare (unbounded) `:keyword`-typed** route key is **rejected fail-loud at `reg-route`** for the same reason — it has no round-trippable URL form. `route-url` host-stringifies a keyword value (`:asc` → `%3Aasc`), but `match-url` keeps the URL segment a **string** (an unbounded `:keyword` slot must not intern arbitrary URL input — the keyword-interning DoS guard), which then fails the route's own `:keyword` schema, so `match-url(route-url(…))` cannot recover the value. `reg-route` throws `:rf.error/route-keyword-unbounded-unsupported` naming the offending slot and key. Use a **bounded `[:enum :a :b …]`** keyword slot (its declared choices intern and round-trip via the enum allowlist — see [§Bidirectional URL ↔ params](#bidirectional-url--params)) or a `:string` slot for a free-form value.

#### Carrying query state across routes

A destination address is taken **literally**. `[:rf.route/navigate {:to :route/cart}]` navigates to exactly `/cart` — it never gains query keys from whichever route happened to be current. There is no route-metadata key, no per-route carry policy, and no query middleware that makes a final address depend on ambient state. If a key is not in the address, it is not in the URL.

Applications *do* legitimately carry global URL state — a theme, a locale, a tenant. That is an application policy, so it is spelled as an ordinary named pure function over the address:

```clojure
(defn with-shell-query
  "Carry the shell's global URL state onto a destination address.
   The explicit destination query WINS."
  [current-query address]
  (update address :query
          (fn [destination-query]
            (merge (select-keys current-query [:locale :tenant])
                   (or destination-query {})))))

;; at the call site — the policy is visible in the dispatch
(rf/dispatch [:rf.route/navigate (with-shell-query @(rf/subscribe [:rf.route/query])
                                                   {:to :route/cart})])
```

Three properties follow, and they are the reason this is a recipe rather than a framework feature:

- **The carried keys are in the authored address.** Read the dispatch and you know the URL. Nothing is added afterwards.
- **It is a pure function, so it is unit-testable** without a frame, a router, or a URL — `(with-shell-query {:locale "en"} {:to :route/cart})` is an ordinary value assertion.
- **Opting out is not writing it.** There is no per-route configuration to reason about and no interaction between a caller's intent and a route's declaration.

An application whose policy really is application-wide applies the helper in its own navigation event or interceptor rather than at each call site; that is still one named function the application owns. The framework adds **no** generic query middleware, functional query updater, or second `update-query` event — the [in-place `:query` / `:query-merge` edit](#in-place-navigation) is already the causal primitive for changing the *current* route's query.

**The helper must tolerate an absent `:query` key.** `{:to :route/cart}` is the ordinary spelling, and a destination replayed out of a [leave-pending value](#navigation-blocking--pending-nav-protocol) omits an empty `:query` entirely — hence the `(or destination-query {})` above. Do not compare a stashed destination by `=` against a fully-spelled address map.

**Carried values are runtime values, not URL strings.** A key pulled out of the current `:query` slice has already been coerced by *that* route's schema: a `[:enum :light :dark]`-typed key is the **keyword** `:dark`, an `:int`-typed key is a **number**. Folding it into another route's address carries that class through unchanged — the helper is a `merge`, not a re-parse, and re-running a string→class coercion on an already-coerced value would be ill-typed (the coercer's input contract is a raw URL string). So **keep a carried key's type consistent across the routes that carry it.** A mismatch is **caught, not silent**: the destination route's `:query` validator runs at the call site (in `:rf.route/navigate`'s handler and in `route-url`), so a `:dark` keyword arriving in a `:string` slot surfaces as a validation failure that rejects the navigation (per [§Param validation at the call site](#param-validation-at-the-call-site)) rather than desyncing the slice.

**Keyword promotion is not inherited from the carry.** A query key is keyword-promoted only when the route declares it in `:query` or `:query-defaults` ([§Keyword-interning cap](#keyword-interning-cap-on-query-keys--values)). A key that an application carries but no route declares stays a **string** on both ends — which is consistent, and is the DoS-safe default.

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

URL query strings are an attacker-influenceable input — caller-controlled, often deep-linked from third parties (search results, partner sites, share links). JVM keywords intern into a process-global, never-GC'd table; a routing layer that turns every URL query key into a keyword permanently extends that table on every unique hostile key, eventually exhausting the host. Long-running SSR JVMs are the worst case. This is the routing-side analogue of the HTTP-side keyword-interning DoS (per [Spec 014 §Keyword-interning cap](014-HTTPRequests.md#keyword-interning-cap)) — but **the routing side closes it at the source by selective keywording, so no URL-level key cap is needed**. The route's declared `:query` / `:query-defaults` vocabulary **is** the keyword universe; a hostile URL of N-unique *undeclared* keys interns **zero** keywords (each undeclared key passes through as a string). The HTTP side genuinely keywordizes partner/webhook JSON object keys against a schema, so it carries a real cap; routing's string-passthrough already provides what such a cap would offer, so the two are *not* symmetric and routing has no `route-too-many-keys` error.

Two layered defenses, both on by default:

1. **Selective keywording against the route's declared vocabulary.** When the route declares a `:query` schema (or `:query-defaults`), only keys named by those slots are promoted to keyword keys. Unknown URL query keys retain their **string** form in the parsed `:query` map. The route's declared vocabulary is the keyword universe; the framework refuses to permanently extend the JVM keyword table on behalf of URL keys the route did not name. **This is the keyword-interning DoS closure** — a hostile URL composed of N-unique undeclared keys interns nothing, so the raw query map (a transient, GC'd value) needs no size cap.

2. **`:keyword`-typed value gate.** A bare `:keyword` query-slot type-form is treated as an **unbounded** intern site (any URL value would intern as a keyword) and the value is preserved as a **string**. Authors who want keyword-typed values declare an `[:enum :asc :desc ...]` allowlist — the bounded keyword universe. Values matching one of the declared enum choices are interned; values outside the allowlist stay as strings.

Routes that declare **no** `:query` vocabulary at all (neither a `:query` schema nor `:query-defaults`) keep **every** URL query key as a **string** — the keyword-all fallback was cut, so a bare `(reg-route :route/x {} "/x")` interns nothing on behalf of the URL, regardless of how many unique keys the URL carries. The selective-keywording rule (defense #1) and the `:keyword`-value gate (defense #2) promote **only** declared keys/values to keywords. (Defense #1 is the key-side mirror of defense #2: author-named intent is the trust boundary for promoting an attacker-influenceable URL key into the process-global keyword table.) A general input-*size* policy (byte/pair/request-boundary limits) is intentionally out of scope here; if one is ever wanted it would be designed separately, not as a keyword-interning cap.

```clojure
;; safe enum allowlist for a keyword-typed query value.
(rf/reg-route :route/sorted
  {:query [:map
           [:sort [:enum :asc :desc]]]}
  "/items")

;; URL: /items?sort=desc       → :query {:sort :desc}     ;; declared enum value → interned
;; URL: /items?sort=hostile    → :query {:sort "hostile"} ;; outside enum → stays as string
```

Cross-references: [Security.md §DoS by input](Security.md#dos-by-input) for the framework-wide stance, and [014 §Keyword-interning cap](014-HTTPRequests.md#keyword-interning-cap) for the symmetric HTTP-side cap.

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

Read it via the `:rf.route/fragment` sub. Fragment is **populated by `match-url` from the URL**, written to the slice by the URL-change handlers (`:rf.route/transitioned` / `:rf.route/handle-url-change`), and emitted by `route-url` when its address map carries a non-nil `:fragment` — as it does when `:rf.route/navigate`'s flat request map carries an explicit `:fragment` (which overrides any fragment embedded in a `:url`, and `nil` clears it).

### Fragment-only changes do NOT re-fire `:on-match`

When a navigation's resolved target differs from the current slice **only** in its fragment (same `:route-id`, same `:params`, same `:query`, but different `:fragment`), the runtime treats it as an in-page anchor change — one logical operation with **the same behaviour whichever of the three commit doors it enters** (programmatic `:rf.route/navigate`, forward-nav `:rf.route/transitioned`, or popstate `:rf.route/handle-url-change`). The runtime:

1. Updates **only** `:fragment` in the `:rf/route` slice. `:route-id`, `:params`, `:query`, `:transition`, `:error`, and `:nav-token` are preserved **byte-for-byte**, as are the routing siblings (pending-navigation, resource-blocking bookkeeping).
2. Emits **exactly one** `:rf.route/fragment-changed` trace event with `:tags {:route-id <id> :prev-fragment <s> :next-fragment <s> :frame <navigating-frame>}`. It emits **no** `:rf.route.nav-token/allocated`, **no** route `:rf.route/activated`/`:rf.route/deactivated` lifecycle pair, and **no** route-resource plan trace.
3. Does **NOT** allocate or bump the `:nav-token` counter. The standing token stays current, so an already-running `:on-match` loader (or route `:resources` fetch) for the unchanged route remains eligible to complete — a fragment jump must not stale-suppress in-flight work for the route you are already on.
4. Does **NOT** re-fire `:on-match`, run the route's `:resources` re-plan, or release/re-`ensure` any resource owner (the route-entry hook is not invoked — the fragment-only branch never calls the shared navigation-commit assembler).

**Guard ordering.** Classification happens **after** target resolution / fragment-normalisation / query-shaping / URL build / validation **and** after the shared leave-then-enter gate (`:can-leave` on the current route, then `:can-enter` on the target). A blocked guard wins over the fragment-only short-circuit and produces the normal pending-nav protocol (no fragment update, no history mutation, no `:rf.route/fragment-changed`); a `:rf.route/continue` re-runs the gate and takes the short-circuit only after it passes. A malformed / route-miss / rejected / external target keeps its existing path and is **not** newly classified as fragment-only.

**History (programmatic door).** `:rf.route/navigate` drives the browser URL: a fragment-only nav pushes the new fragment URL via `:rf.nav/push-url` by default, or replaces the active entry via `:rf.nav/replace-url` when `{:replace? true}` is supplied. Clearing a fragment (navigating with no `:fragment`) pushes/replaces the fragment-less URL and writes `:fragment nil`. The URL is driven through those registered effects (never a direct `window.location.hash` write), so URL-owner and URL-strategy enforcement stay intact. The URL-driven doors emit no push — the browser URL already changed (popstate / link-click).

**Scroll (all doors).** Every fragment-only door emits the resolved `:rf.nav/scroll` effect, ordered after the leaving-scroll capture (and, on the programmatic door, after the history push/replace). This holds for the URL-driven doors too — even though they emit no push, `pushState`/popstate do **not** scroll to a fragment natively, so without the effect a `#section` link-click (`:rf.route/transitioned`) or a Back/Forward to a fragment (`:rf.route/handle-url-change`) would compute a scroll plan and then never scroll. The resolved strategy is the entry point's default unless the route's `:scroll` meta (or, programmatic only, a per-call `:scroll` opt) overrides it: `:top` for forward nav (`:rf.route/navigate` / `:rf.route/transitioned`) scrolls to the new fragment element (or the top when cleared/absent); `:restore` for popstate / SSR (`:rf.route/handle-url-change`) restores the saved position for the history entry. `:scroll false` suppresses the effect on every door. As with the full-commit path, no focus-movement or `:target` pseudo-class parity is claimed — `:rf.nav/scroll` supplies visual scrolling only.

The reason for rules 3-4: `:on-match` (and route `:resources`) exist to re-load route-scoped data when path or query changes. A fragment-only change does not change loaded data — only the in-page anchor target. Re-firing the loaders would re-fetch unchanged data on every `#section` jump, which is exactly the kind of thrash users complain about — and, worse, the token bump would stale-suppress a still-in-flight loader for the route you never left. (An explicit force-reload is a *separate*, named contract, not an overload of `#fragment`; route data must therefore never depend on the fragment — data-bearing tabs/filters belong in path/query, fragments are presentation / in-page-location state.)

Views that need to react to fragment changes subscribe to `:rf.route/fragment` (or to `:rf/route` for the whole slice). The fragment-only classification lives in the shared navigation-planning seam (`plan/fragment-only?`) that all three doors consult, so they cannot drift.

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

The three enum strategies are the whole vocabulary, and their fragment-handling is locked above — there is no fourth, host-supplied strategy that could interpret `:fragment` differently (see [§Custom scroll strategies](#custom-scroll-strategies)).

### Programmatic navigation with fragments

`:rf.route/navigate`'s flat request map carries the fragment two ways — an explicit `:fragment` key, or a fragment embedded in a `:url`:

```clojure
;; explicit :fragment key (route-id or in-place request)
[:rf.route/navigate {:to :route/docs :params {:page "routing"} :fragment "scroll-restoration"}]

;; embedded in a :url (URL-string escape hatch)
[:rf.route/navigate {:url "/docs/routing#scroll-restoration"}]
```

Either way the fragment ends up in the `:rf/route` slice's `:fragment`. When BOTH are present — an explicit `:fragment` beside a `:url` — the explicit key wins (it overrides the embedded fragment; `:fragment nil` clears it), for a matched OR an unmatched raw URL, so the address bar, the slice, and the guard/pending target always agree.

**Fragment-only programmatic navs take the short-circuit too.** A `:rf.route/navigate` whose resolved target differs from the current slice **only** in its fragment is a fragment-only change and honours [§Fragment-only changes do NOT re-fire `:on-match`](#fragment-only-changes-do-not-re-fire-on-match) in full: `:fragment` updates, one `:rf.route/fragment-changed` fires, and the `:nav-token` / `:on-match` / route-`:resources` are left untouched — identical to the same anchor jump arriving via a `route-link` click or Back/Forward. This is the regularity contract: the same logical operation cannot behave differently just because it entered through the programmatic door. (Before this was wired, a programmatic fragment nav took the full commit path — re-firing every loader and bumping the token, stale-suppressing in-flight work for the route you were already on.)

**Scroll.** A fragment-only programmatic nav still emits the resolved `:rf.nav/scroll` effect (default strategy `:top` — scroll to the new fragment element, or to the top when the fragment is cleared/absent), ordered after the leaving-scroll capture and the history push/replace. `pushState`/`replaceState` do **not** scroll to a fragment natively (they are neither navigation nor traversal per the WHATWG URL-and-history-update steps), so the `:rf.nav/scroll` effect is what performs the visual scroll; `{:scroll false}` suppresses it, and `:restore`/`:preserve` retain their usual meanings. The runtime makes **no** focus-movement or `:target` pseudo-class parity claim for the fragment-only path — `:rf.nav/scroll` supplies visual scrolling only; focus/`:target` parity is a separate accessibility decision.

### SSR

Browsers do **not** send `#fragment` to the server — `window.location.hash` is client-only. For browser-initiated SSR requests, the server-side `:fragment` is therefore typically `nil`, regardless of what the user typed in the address bar. The exceptions are static-site generators, server-side test harnesses, and crawlers that synthesise URLs with explicit fragments (e.g., for anchored documentation pages); when the host's request abstraction exposes a `#fragment`, SSR includes it in the seeded `:rf/route` slice. See [011 §Fragments under SSR](011-SSR.md#fragments-under-ssr) for the full SSR-side contract.

The server does NOT scroll (no DOM); `:rf.nav/scroll` is `:platforms #{:client}` per [011 §Effect handling on the server](011-SSR.md#effect-handling-on-the-server). The first client render after hydration sees the same `:fragment` value the server seeded (typically `nil` for browser requests), so view code that reads `:rf.route/fragment` produces structurally-identical output on both sides. A subsequent `:rf.nav/scroll` (post-hydrate) is the host's choice — the contract leaves it to the host to decide whether to perform the initial scroll-to-fragment after hydration.

### Conformance

Fixture `route-fragment-change.edn` exercises the **URL-driven** door (`:rf.route/transitioned`):
1. Navigate to `/docs/routing#scroll-restoration`. Verify the slice's `:fragment` is `"scroll-restoration"`.
2. Navigate to `/docs/routing#caching` (same path/query, different fragment). Verify `:on-match` does NOT re-fire and `:rf.route/fragment-changed` trace event fires.
3. Navigate to `/docs/instrumentation#scroll-restoration` (different path, same fragment). Verify `:on-match` DOES re-fire (path changed; fragment-only rule does not apply).

Fixture `routing-fragment-navigate.edn` exercises the **programmatic** door (`:rf.route/navigate`):
1. Navigate `[:rf.route/navigate {:to :route/docs :params {:page "routing"} :fragment "a"}]` — a full commit: `:on-match` fires once, a fresh `:nav-token` is allocated, `:rf.nav/push-url` pushes `/docs/routing#a`.
2. Navigate `[:rf.route/navigate {:to :route/docs :params {:page "routing"} :fragment "b"}]` (same route-id/params/query, different fragment). Verify `:on-match` does NOT re-fire (loader count unchanged), the `:nav-token` is **unchanged** (no new allocation), `:rf.route/fragment-changed` fires, and `:rf.nav/push-url` pushes `/docs/routing#b`.
3. Navigate `[:rf.route/navigate {:to :route/docs :params {:page "routing"} :fragment "c" :replace? true}]`. Verify the same fragment-only short-circuit routes through `:rf.nav/replace-url` (not push) and still does not re-fire `:on-match` or allocate a token.

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

A more elaborate router with **native nested layouts** — true `<Outlet/>` slot mechanics, generic parent-metadata cascades — remains out of scope. The `:parent` + `:rf.route/chain` convention covers the common case (a parent shell wrapping leaf views), keeps the pattern data-only, and avoids introducing a new render substrate.

**Parent `:resources` DO compose to children (EP-0037 R2).** As of EP-0037 R2, declaring `:parent` opts the child into its ancestors' `:resources` automatically: a full activation plans the effective parent-to-leaf branch, so shared shell reads are declared **once** on the parent rather than duplicated in every child. `:parent` itself is the opt-in (EP-0037 OI-4) — there is no `:inherit-resources?` marker. Only `:resources` fold this way; `:on-match`, `:scroll`, `:head`, `:tags`, and guards are **not** inherited, because unrelated metadata needs incompatible merge rules. Parent-chain resource planning does not imply parent-chain render ownership — there is still no `<Outlet/>`, provider tree, or loader-data hook. Composition, grouped identity dedupe, the redundant-child advisory, the plan diff, attach-before-release owner handoff, and the partial-revalidation law are owned by [016 §Effective parent-chain resource plans](016-Resources.md#effective-parent-chain-resource-plans); readiness over the branch plan is [§Route readiness is a resource projection](#route-readiness-is-a-resource-projection).

Future expansion may still revisit:
- A `:layout` slot on `reg-route` (separate from `:parent`) so a route can declare which layout component wraps its leaf view.
- An `<Outlet/>`-equivalent primitive for the child render slot.

The `:parent` + chain-sub convention is sufficient for the common case and doesn't preclude a richer mechanism later.

<a id="navigation-blocking--pending-nav-protocol"></a>
## Navigation blocking — the leave-only pending protocol

Real product needs — unsaved forms, interrupted checkouts, destructive multi-step workflows, **auth gates** — require navigation to be *blockable* at both ends of a transition: the route you are **leaving** (`:can-leave` — "you have unsaved changes") and the route you are **entering** (`:can-enter` — "you must be signed in"). Re-frame2 makes both a **first-class named-event/state protocol** rather than a magic component hook: state lives in `runtime-db`, UI renders confirm dialogs from ordinary subscriptions, and user choices are dispatched as standard events. All testable.

The two decisions are deliberately **asymmetric**, because the two questions are not the same shape:

- **Leaving is a question to the user.** "You have unsaved changes — really leave?" has an answer that arrives later, so the block is **resumable**: it parks one pending value and waits for `:rf.route/continue` or `:rf.route/cancel`.
- **Entering is a question to the application's state.** "Is this user signed in?" is answered by the same subscription every time it is asked, so the rejection is **terminal**: it commits nothing, parks nothing, and says so once. A post-denial return is an ordinary *fresh* navigation whose guard re-evaluates naturally — there is no paused transition to resume out of its original causal context.

Both decisions run in [the one planning pipeline](#the-one-planning-pipeline) (stages 4 and 5) for **every** door, so coverage never depends on which event drove the navigation. This is what makes `:can-enter` fail *closed*: a hand-rolled interceptor attached only to `:rf.route/navigate` fails *open* through link clicks (`:rf.route/url-requested`) and deep-links / Back-Forward (`:rf.route/handle-url-change`), neither of which dispatches `:rf.route/navigate`.

### Declaring the guards

Two optional route-metadata keys — `:can-leave` on the route being left, `:can-enter` on the route being entered:

```clojure
(rf/reg-route :editor/article
  {:doc       "Editing an article."
   :params    [:map [:id :string]]
   :can-leave [:editor/can-leave?]}             ;; sub-id; (subscribe [<sub-id> <target>]) → boolean
  "/editor/articles/:id")

(rf/reg-route :account/settings
  {:doc       "Account settings — signed-in only."
   :can-enter [:auth/signed-in?]}               ;; sub-id; (subscribe [<sub-id> <target>]) → boolean
  "/account/settings")

(rf/reg-sub :editor/can-leave?
  :<- [:editor/dirty?]
  (fn [dirty? _] (not dirty?)))                 ;; true means "OK to leave"

(rf/reg-sub :auth/signed-in?
  :<- [:auth/user]
  (fn [user _] (some? user)))                   ;; true means "OK to enter"
```

Each sub returns `true` when the route is OK to leave / enter; `false` to reject. The convention: the *sub's name* describes the positive case, so `false` means "can NOT".

<a id="the-guard-sub-receives-the-pending-target-as-an-argument"></a>
**The guard sub receives the resolved target as an argument.** The runtime subscribes the guard with the resolved **target** appended to its query vector — `(subscribe [<guard-id> <target>])` — so the guard receives it as the second destructure position, `(fn [inputs [_ target] …])`. `target` is `{:route-id :params :query :fragment :url}`. This is what makes the [fragment-check contract](#fragments) *implementable* for `:can-leave` (compare the current `:rf.route/fragment` against `(:fragment target)` — allow when only the fragment differs) and what gives `:can-enter` the destination to branch on (read `(:route-id target)`, `(:params target)`, or the target route's `:tags` via `handler-meta`). A guard that ignores the extra arg (the common `:<- [:editor/dirty?]` shape) is unaffected — the target rides in the query vector's tail, unread.

**Closed contract.** The runtime accepts only the literals `true` and `false`. Any other value (`42`, a non-empty string, `nil`, a map) **fails closed** and emits a structured trace — `:rf.error/can-leave-non-boolean` for a leave guard, `:rf.error/can-enter-non-boolean` for an entry guard — with `:tags {:route-id :query :value :reason :recovery :blocked-navigation :frame}`. The closed contract forces the route author to write `(boolean …)` / `(not …)` rather than rely on truthiness (the classic polarity bug: a sub returning the dirty-flag *value* silently let the user navigate away and lose form state). Pre-alpha posture: no shim, no soft transition.

**Guard coverage by transition kind** is the pipeline's, not the guards': an exact **no-op** evaluates neither guard; a **full** transition (including a changed in-place `:query` / `:query-merge`) and a **fragment-only** transition evaluate `:can-leave` then `:can-enter`, in that order. See [§The one planning pipeline](#the-one-planning-pipeline).

A route has at most one `:can-leave` and one `:can-enter` (each is a single-valued metadata key). For a policy that spans *many* routes uniformly, either attach the same `:can-enter` sub-id to each registration with an ordinary map helper, or use a frame-`:interceptors` policy — see [§Redirects and guards](#redirects-and-guards).

### The leave decision — one resumable pending value

A `:can-leave` returning `false` writes **one** pending value at `[:rf.runtime/routing :pending-navigation]` (schema: [Spec-Schemas §`:rf/pending-navigation`](Spec-Schemas.md#rfpending-navigation)), dispatches `:rf.route/navigation-blocked`, and leaves the current route untouched. The value stores structured **intent**, so consumers never reparse an original event vector:

```clojure
;; at [:rf.runtime/routing :pending-navigation]:
{:id              "pn-7"
 :destination     {:to :route/cart}                   ;; a :rf/route-destination — replayable
 :target          {:route-id :route/cart :params {} :query {} :fragment nil :url "/cart"}
 :cause           :link                               ;; which door: :link :navigate :popstate :initial :ssr
 :policy          {:replace? true :scroll :preserve}  ;; the caller's explicit policy; {} when none
 :requested-url   "/cart"
 :rejecting-route :editor/article
 :rejecting-guard :editor/can-leave?
 :url-restored?   false}
```

`nil`/absent when no navigation is pending. The slot is **leave-only** — there is no `:reason` / `:direction` discriminator, because there is only one thing it can be.

`:destination` is a [`:rf/route-destination`](Spec-Schemas.md#rfroute-destination): a target that resolved to a registered named route normalises to `{:to … :params … :query … :fragment …}`, so a *matching* raw-URL request replays as the canonical address it resolved to; an unmatched in-app URL stays `{:url …}`, because rewriting it as the not-found route's address would change the URL on replay. `:policy` is exactly the caller-authored `:replace?` / `:scroll` overrides — `{}` when neither was supplied. It never stores `:bypass-leave?`: a request carrying a true bypass could not have entered the slot, and continuation owns its own one-shot bypass.

**Two named events resolve it:**

| Event | Dispatched by | Behaviour |
|---|---|---|
| `:rf.route/continue` | User code (a "Yes, leave" button) | Clears the slot and executes the stored `:destination` plus the stored `:policy` through the normal pipeline with a one-shot `:bypass-leave? true`. **Entry is evaluated normally** — continuation replays *intent*, it does not carry a decision. Event vector: `[:rf.route/continue pending-nav-id]`. |
| `:rf.route/cancel` | User code (a "Stay" button) | Clears the slot. The URL and the current route stay as they are. Event vector: `[:rf.route/cancel pending-nav-id]`. |

Both ignore a non-matching id, and both are safe no-ops when the slot is empty.

**The URL does not change on a block.** For a **forward** door (`:rf.route/navigate`, `:rf.route/url-requested`) the browser URL has not moved yet, so declining to push is sufficient. For a **URL-driven** door (`:rf.route/transitioned`, `:rf.route/handle-url-change` — popstate / deep-link / a host that pushed before dispatching) the address bar has *already* moved, so the runtime emits a `:rf.nav/replace-url` restoring it to the current slice's URL (rebuilt via `route-url`) — a history *replace*, so no entry is added — and records `:url-restored? true`. `:rf.route/continue` then forces `:replace? true` on the replay, moving the address bar back to `:requested-url` without adding a second entry.

**Hard reload / cross-origin `beforeunload` integration remains a separate host concern.** An SPA pending value cannot stop the browser from unloading, and re-frame2 does not add a second confirmation API pretending otherwise.

<a id="the-bypass-leave-opt"></a>
### `:bypass-leave?` — the explicit escape

`:bypass-leave?` is a plain public **boolean** navigation policy key. `true` skips the current route's `:can-leave` for that one navigation; anything else skips nothing. It exists so trusted workflow code can name the exceptional policy honestly ("this sign-out really does discard the draft") instead of contorting the guard's source state. The ordinary confirmation path is `:rf.route/continue`.

There is **no entry bypass**. Entry is terminal, so there is no paused transition to wave through, and a public "enter anyway" flag would be a hole straight through the auth gate.

<a id="entry-is-terminal"></a>
### Entry is terminal

A `:can-enter` returning `false` (or any non-boolean) **denies**. Denial:

- commits **no** target route, resource owner, URL push, scroll, or activation event;
- creates **no** pending-navigation value — the slot is untouched, including any unrelated pending leave;
- dispatches `[:rf.route/entry-denied denial]` **exactly once** for the navigation attempt;
- emits the `:rf.route/entry-denied` trace (`:phase :can-enter`); and
- restores the current route's URL by **replace** when the door was URL-driven (the browser had already moved).

Exactly-once is structural, not a de-duplication step: the decision seam short-circuits its caller, so the first rejection in a navigation terminates the attempt and no later door in the same chain can reach a second one. (The link door decides once and stamps `:rf.route/decided?` on the `:rf.route/transitioned` event it synthesises, so an *allowed* link click does not re-decide either.)

The denial event receives:

```clojure
{:destination   {:to :route/account}                     ;; :rf/route-destination — replayable
 :target        {:route-id :route/account :params {} :query {} :fragment nil :url "/account"}
 :cause         :link
 :requested-url "/account"
 :guard         :auth/signed-in?}
```

There is no `:id`: there is nothing to continue or cancel.

**The framework registers a no-op default handler for `:rf.route/entry-denied`.** An application is never *required* to register one merely to make denial safe: with the default, client denial is a hard deny — the current route and URL stay in place (or are restored), no protected activation work runs, and the dispatch resolves without `:rf.error/no-such-handler`. Applications replace it with their own policy.

<a id="replaceable-framework-defaults"></a>
##### Replaceable framework defaults

`:rf.route/entry-denied` and its leave-half sibling `:rf.route/navigation-blocked` are **replaceable framework defaults**: the framework seeds a handler so the feature is safe with no application handler, and an application registering its own handler under the same id through the ordinary public `rf/reg-event` is the **documented, intended override**. No image composition, no opt-in key, no special spelling — the app just registers the id.

This is the exact opposite of a **framework standard** (`:rf/set-db`, `:rf.interceptor/path`), which encodes an execution invariant and is *protected*: an app registration colliding with a standard fails loud (`:rf.error/image-standard-replacement-forbidden`, per [Conventions §`:rf.standard/*`](Conventions.md#the-single-root-reserved-set)), and `:rf/set-db` is refused at the registration site outright (`:rf.error/reserved-event-id`).

The two registrations carry the reserved registration-meta marker **`:rf/framework-default? true`** ([Conventions §Reserved registration metadata](Conventions.md#reserved-registration-metadata-framework-owned)). The framework seeds them through its internal registration path, which captures no source provenance, so image assembly reads the marker together with the absent `:rf.provenance/ns` to recognise the framework's *own* copy — and stops projecting it into the application layer exactly when an application registration for the same id is present. Two consequences follow, and both matter:

- **Order still decides nothing.** Two *application* namespaces registering the same framework-default id remain an ambiguous collision (`:rf.error/image-duplicate-id`), just like any other duplicate id. The seam removes the framework's own seeding from the app layer; it does not introduce a winner rule.
- **What is replaced is the BEHAVIOUR — not the framework's description of its own payload.** The denial / block payload is *framework-constructed*, so the `:sensitive` declaration on its URL carriers (`:requested-url` / `:destination` / `:target`, which embed query values and path params — see [§Navigation blocking](#navigation-blocking--the-leave-only-pending-protocol)) is a fact about the framework's payload shape, not something the application is asked to restate. It therefore **rides across the override**: an ordinary `(rf/reg-event :rf.route/entry-denied (fn …))` with no metadata map at all still redacts those carriers at every trace / off-box projection. An override that declares `:sensitive` of its own gets the **union** — the framework's carriers plus its own paths. Nothing else carries over: not the marker, not framework authority, not `:doc`, not the handler.

    **The frame-targeted read is the effective one.** A tool asking what classification actually applies asks a *frame*: `(rf/handler-meta {:frame f :kind :event :id :rf.route/entry-denied})`. That is the arity that resolves through the frame's own sealed image generation — the single seam every dispatch, subscription and trace projection already resolves registrations through — so it is the read that answers the question the classification exists to answer. It reports the union, and it identifies the application descriptor (its provenance, not the framework marker).

    The positional `(rf/handler-meta :event :rf.route/entry-denied)` is a different question with its own established answer: it reads the **process-global resolver map**, whose semantics are **last-write-wins**. It is not frame-resolved and it is not the effective classification.

    **The redaction is independent of namespace load order; the positional read is not.** `re-frame.core` does not pull the routing artefact in, so an application namespace that registers the id and never requires `re-frame.routing` itself may be loaded *before* the façade seeds its defaults. In either order the frame-targeted read converges on the same union and every trace / off-box projection redacts the carriers — require order is not something an author has to reason about to keep the redaction. The positional read does **not** converge: under app-first / framework-second the framework's own seeding is the last writer, so the positional form reports the framework's carriers and its `:rf/framework-default? true` marker without the app's own paths. That is last-write-wins behaving as specified, and it is **not a carrier leak** — the framework's carriers are present at that surface, and nothing resolves dispatch or egress through it.

    Converging the positional form is deliberately **not** done. It would mean unioning application metadata into the framework's own descriptor — a second precedence rule beside image assembly, which is exactly the general metadata-inheritance machinery this seam is scoped to avoid.

    This retention is scoped precisely to a replaceable framework default's carrier classification. There is no general metadata inheritance — replace any other registration and its metadata is entirely yours.

<a id="the-fresh-return-recipe"></a>
#### The fresh-return recipe (auth)

An authentication flow stashes the denied `:destination`, navigates freshly to login (normally with `:replace? true`), and after a successful sign-in dispatches a **fresh** `:rf.route/navigate` with the stored destination. The guard re-evaluates because that is an ordinary new attempt:

```clojure
(rf/reg-event :rf.route/entry-denied
  (fn [{:keys [db]} [_ {:keys [destination]}]]
    {:db (assoc-in db [:auth :return-to] destination)
     :fx [[:dispatch [:rf.route/navigate {:to :route/login :replace? true}]]]}))

;; after a successful sign-in:
[:rf.route/navigate (get-in db [:auth :return-to])]
```

The stored value is a plain `:rf/route-destination` map, so it survives in `app-db`, in a URL, or across a page load, and replays through the ordinary navigate door. Nothing about the return is special-cased: it is a new navigation, with a new nav-token, whose guard runs like any other.

#### Initial load and SSR

Initial load has no current route to leave, but evaluates target entry policy normally — a guarded deep-link is denied before anything commits. On a **server frame** denial additionally stamps the default `403` response *before* the denial event drains, so an application handler may supersede it with `:rf.server/redirect` or an explicit status. That arm is owned by [011 §Route entry denial — the default 403](011-SSR.md#route-entry-denial--the-default-403).

### Why this shape (not a hook-based router)

The hook-based version (e.g. React Router's `useBlocker`) is convenient but tied to component lifecycle. Re-frame2's strengths are explicit state and dispatched events; this design preserves them. Slightly more verbose at the call site; far more testable.

A leave test fires `[:rf.route/url-requested {:url "/cart"}]` against a frame whose `:editor/can-leave?` sub returns `false`, asserts `:rf/pending-navigation` is set, asserts `:rf.nav/push-url` did NOT fire, dispatches `[:rf.route/continue pending-nav-id]`, asserts the navigation completes. An entry test fires the same event at `/account` against a frame whose `:auth/signed-in?` sub returns `false`, asserts `:rf.route/entry-denied` fired **once**, asserts `:rf/pending-navigation` is still `nil`, and asserts the slice never moved. No DOM, no event simulation, no hook-mock.

### Interaction with other navigation features

- **Nav-tokens.** Decisions run *before* a nav-token would be allocated; tokens are for committed navigations. A `:rf.route/continue` replay allocates a fresh token like any other navigation. A blocked or denied attempt never receives one.
- **Fragments.** Both guards run on a fragment-only change. The guard sub receives the resolved target, so a `:can-leave` that wants fragment changes to pass returns `true` when only the fragment differs.
- **Resources and readiness.** A denial commits nothing, so it touches neither the route plan nor `[:rf.runtime/routing :resource-blocking]`; route readiness stays exactly what the [resource projection](#route-readiness-is-a-resource-projection) says it is for the route that is still current.
- **Cross-cutting policy.** For a rule spanning many routes uniformly, register it with `reg-interceptor` and reference it from the frame's `:interceptors` chain ([EP-0022](../docs/EP/EP-0022-registered-interceptors.md)) so it runs on every navigation entry event. Reach for `:can-enter` when the policy belongs to *one* route; reach for a frame interceptor when it spans *many* — see [§Redirects and guards](#redirects-and-guards).

### Conformance

Fixture [`route-navigation-blocked.edn`](conformance/fixtures/route-navigation-blocked.edn) exercises the resumable leave:
1. Register a route with `:can-leave [:editor/can-leave?]`; make the sub return `false`.
2. Dispatch `[:rf.route/url-requested {:url "/cart"}]`.
3. Assert `:rf/pending-navigation` is set with the `:destination` / `:target` / `:cause` / `:policy` shape; `:rf.nav/push-url` did NOT fire; the `:rf.route/navigation-blocked` trace fired; the `:rf/route` slice is unchanged.
4. Dispatch `[:rf.route/continue pending-nav-id]`; assert the slot is `nil`, the URL is `/cart`, and `:route/cart` is active.

Fixture [`route-entry-denied.edn`](conformance/fixtures/route-entry-denied.edn) exercises terminal entry through EACH door:
1. Register a target with `:can-enter [:auth/signed-in?]`; the sub returns `false`.
2. Dispatch `[:rf.route/url-requested {:url "/account"}]` (link click) — assert `:rf.route/entry-denied` fired **exactly once**, `:rf/pending-navigation` is still `nil`, `:rf.nav/push-url` did NOT fire, and the slice is unchanged.
3. Repeat with `[:rf.route/navigate {:to :account/settings}]` (programmatic) and `[:rf.route/handle-url-change "/account"]` — a rider-free client dispatch, which is the **deep-link / initial-load** door — for the same outcome through every door; the URL-driven arm additionally restores the address bar by `:rf.nav/replace-url`. Spell the **Back/Forward** arm with the rider the framework's own listener stamps, `[:rf.route/handle-url-change "/account" {:rf.route/cause :popstate}]`, and the **SSR** arm as the rider-free dispatch on a `:platform :server` frame. One event stands for three doors and a rider-free dispatch on a client frame resolves as `:initial` ([§popstate drives the URL-owner frame](#popstate-drives-the-url-owner-frame-both-directions)), so a bare dispatch labelled "popstate" asserts the right outcome under the wrong cause — and every cause-bearing diagnostic it produces, the `:rf.route/planned` projection included, names a door the user never used.
4. Register NO application `:rf.route/entry-denied` handler for one arm: the framework default keeps the denial safe, and no `:rf.error/no-such-handler` fires.
5. Flip `:auth/signed-in?` to `true` and dispatch a **fresh** `[:rf.route/navigate {:to :account/settings}]`; assert the guard re-ran and entry completed.
6. Negative: a `:can-enter` returning a non-boolean denies and emits `:rf.error/can-enter-non-boolean`; `:bypass-leave? true` does **not** let a caller past the entry guard; `[:rf.route/navigate {:to … :bypass-guards? #{:enter}}]` rejects with `:rf.error/navigate-bad-request` (`:reason :unknown-keys`).
7. Exact no-op: re-requesting the already-active URL through any door evaluates **neither** guard and creates no state.


## Redirects and guards

**Auth-on-a-route is `:can-enter`, not an interceptor.** The most common routing policy — "you must be signed in to reach this route" — is a declarative [`:can-enter`](#entry-is-terminal) route-metadata key. It runs in the one planning pipeline, so it fails *closed* through every entry door (link click, programmatic nav, deep-link / Back-Forward, initial load, SSR) with no per-door plumbing:

```clojure
(rf/reg-route :route/account
  {:can-enter [:auth/signed-in?]}
  "/account")

(rf/reg-sub :auth/signed-in?
  :<- [:auth/user]
  (fn [user _] (some? user)))     ;; true → OK to enter

;; The app's :rf.route/entry-denied handler turns the denial into a login
;; redirect. Entry is TERMINAL, so there is no paused transition to resume:
;; stash the denied :destination, replace-navigate to login, and after a
;; successful sign-in dispatch a FRESH navigate with the stored destination
;; (the guard re-evaluates because that is an ordinary new attempt).
(rf/reg-event :rf.route/entry-denied
  (fn [{:keys [db]} [_ {:keys [destination]}]]
    {:db (assoc-in db [:auth :return-to] destination)
     :fx [[:dispatch [:rf.route/navigate {:to :route/login :replace? true}]]]}))
```

Registering a handler is optional: the framework ships a no-op default, so a denial with no application handler is a safe hard deny (and, on a server frame, a [`403`](011-SSR.md#route-entry-denial--the-default-403)). Registering one is an ordinary `rf/reg-event` under the same id — see [§Replaceable framework defaults](#replaceable-framework-defaults).

**Interceptors remain the tool for cross-cutting policy** — a rule that spans *many* routes uniformly (a feature flag gating a whole section, a maintenance-mode lockout, an analytics-driven redirect). Per [EP-0022](../docs/EP/EP-0022-registered-interceptors.md) an interceptor is a **registered program member**: author its behaviour once with `reg-interceptor` (an app-owned id), then attach it to the frame's `:interceptors` chain (so it runs on *every* navigation entry event) by **reference**. Inline interceptor maps/Vars in a chain are rejected at registration with `:rf.error/inline-interceptor-removed`.

A cross-cutting guard interceptor **must cover all three entry doors**, or it fails *open*: a policy that inspects only `:rf.route/navigate` lets a logged-out user in through a link click (`:rf.route/url-requested`) or a deep-link / Back-Forward (`:rf.route/handle-url-change`), neither of which dispatches `:rf.route/navigate`. So the interceptor resolves the target from *whichever* nav event drove it, then decides:

```clojure
;; Requires: [re-frame.core :as rf] [re-frame.routing :as rf.routing]
;; `match-url` lives in re-frame.routing — NOT on the rf/ facade.

;; Resolve the target route + params from ANY of the three navigation
;; events (fail-closed coverage — do not guard only :rf.route/navigate).
;; `current` is the current route slice ([:rf.runtime/routing :current]) — an
;; in-place request (no :to / :url) resolves against it, exactly as the runtime
;; resolves it (§In-place navigation).
(defn- nav-target [event current]
  (let [[ev-id a _b] event]
    (case ev-id
      :rf.route/navigate          (let [{:keys [to url params]} a]   ;; a is the request map
                                    (cond
                                      to  {:id to :params (or params {})}
                                      url (when-let [{:keys [route-id params]} (rf.routing/match-url url)]
                                            {:id route-id :params (or params {})})
                                      :else                          ;; in-place — stay on the current route
                                      {:id (:route-id current) :params (or (:params current) {})}))
      :rf.route/url-requested           (let [{:keys [to params url]} a]
                                    (cond
                                      to  {:id to :params (or params {})}
                                      url (when-let [{:keys [route-id params]} (rf.routing/match-url url)]
                                            {:id route-id :params (or params {})})))
      :rf.route/handle-url-change (when-let [{:keys [route-id params]} (rf.routing/match-url a)]
                                    {:id route-id :params (or params {})})
      nil)))

(rf/reg-interceptor :app/section-lockout
  {:doc "Redirect away from a whole gated section when the feature flag is off — a
         cross-cutting rule spanning many routes, so an interceptor not :can-enter."}
  {:before
   (fn before [ctx]
     ;; The route slice is framework runtime-db state — read it from the
     ;; :rf.db/runtime coeffect so an in-place request resolves to the current route.
     (if-let [{:keys [id]} (nav-target (get-in ctx [:coeffects :event])
                                       (get-in ctx [:coeffects :rf.db/runtime
                                                    :rf.runtime/routing :current]))]
       (let [route-meta (rf/handler-meta :route id)
             gated?     (boolean (some #{:beta-section} (:tags route-meta)))
             enabled?   (get-in ctx [:coeffects :db :flags :beta])]
         (if (and gated? (not enabled?))
           ;; skip the original handler (so the gated slice never commits) and
           ;; redirect. Skipping — not rewriting :coeffects :event — because the
           ;; handler is picked from the ORIGINAL event id before interceptors run.
           (-> ctx
               (assoc :rf/skip-handler? true)
               (assoc-in [:effects :fx] [[:dispatch [:rf.route/navigate {:to :route/home}]]]))
           ctx))
       ctx))})

;; Attach to the FRAME's :interceptors so it runs on every navigation entry
;; event — the "global within this frame" mechanism (EP-0022). Attaching only
;; to :rf.route/navigate is the classic fail-open bug.
(rf/make-frame {:id :app
                :interceptors [:app/section-lockout]})
```

The `:rf.route/navigate` door itself carries **three request forms** (per [§Target form](#target-form--route-id-or-url-string) and [§In-place navigation](#in-place-navigation)): a route-id destination (`:to`), the `{:url …}` escape hatch (deep links, redirects), and an in-place request (no `:to` / `:url`). A raw URL is not a route id, so the normaliser routes the `:url` form through `match-url` exactly as the runtime does before the guard reads `handler-meta` — otherwise `[:rf.route/navigate {:url "/admin"}]` resolves `handler-meta` on a URL string, finds no route, and slips past the gate. An in-place request names no target at all: it means *stay on the current route, change only the query*, and the runtime resolves its id from the current route slice ([§In-place navigation](#in-place-navigation)). The guard must resolve it the same way — from `[:rf.runtime/routing :current]` in the `:rf.db/runtime` coeffect — or it fails **open** exactly where it is most dangerous: a session that expires *while already on a `:requires-auth` route* navigates in place (a query change, a tab switch) and the guard, seeing no named target, waves it through. This is the request-union mirror of the three-door rule: cover all three forms of the door too.

Cross-cutting guards are interceptors, not a special routing mechanism. They are registered once and referenced by id; they compose — list multiple refs in the `:interceptors` chain and they layer in order. Prefer `:can-enter` when the policy belongs to one route (for a *group* of protected routes, associate the same `:can-enter` sub-id with each registration through an ordinary map helper — the router does not add a middleware chain merely to avoid that); reach for a frame-`:interceptors` guard when the rule spans many routes uniformly, and cover all three doors when you do.

<a id="server-side-rendering-integration"></a>
## Server-side rendering integration (per [011](011-SSR.md))

The server-side flow:

1. HTTP request arrives.
2. `make-frame` per request. `:initial-events` fire `[:rf/server-init request]`, which dispatches `[:rf.route/handle-url-change (:uri request)]`.
3. Route slice is set from the URL; the same handler runs on server and client. Path params, query params, and `:query-defaults` are populated.
4. The matched route's `:on-match` events dispatch through the request-local frame — the same **fire-and-forget** vector that runs client-side, so synchronous event effects stay symmetric. They are **not** awaited: the server does not claim to wait for an arbitrary asynchronous tail started by `:on-match` (EP-0037 §`:on-match` is activation work).
5. The **only** route-owned server wait is a **blocking `:resources`** requirement. The server builds the route's resource plan, ensures it, and waits only for the blocking first loads of the current plan / nav-token; route readiness is the resource projection ([§Route readiness is a resource projection](#route-readiness-is-a-resource-projection)) — a render that must reflect required page data waits on those resources, not on `:on-match`. Work that must settle before server render is therefore a blocking route resource, not an `:on-match` event.
6. Drain settles and blocking resources resolve; root view renders against the populated + resource-projected state.
7. HTML + serialised state ship to the client.

**Entry denial on the server.** Step 3's `:rf.route/handle-url-change` runs the same [entry decision](#entry-is-terminal) it runs on the client. A denial commits no route, so steps 4-6 have no target to activate: no `:on-match` fires, no route resource plan is built or awaited, and no hydration data for the denied target is produced. The runtime stamps the default `403` before the denial event drains; the application handler may supersede it with `:rf.server/redirect` (which truncates HTML under 011's redirect precedence) or another explicit status. The full HTTP contract is [011 §Route entry denial — the default 403](011-SSR.md#route-entry-denial--the-default-403).

On the client, hydration runs `[:rf/hydrate state]` which restores the route along with everything else. **`:on-match` does not re-fire on hydration** — the seeded `app-db` already contains the loaded data. The first client render produces the same HTML the server rendered (same `:rf.route/id`, same `:params`, same `:query`).

## Tooling and AI-amenability

- `(rf/registrations :route)` enumerates every registered route. Tools and agents enumerate them; AI scaffolding consults this before generating new routes to avoid collisions.
- `(rf/handler-meta :route :route/cart)` returns the route's metadata: path, params shape, query shape, `:on-match`, `:scroll`, `:parent`, tags, source coords. The `:on-match` slot is **enumerable** — tools render activation dependency graphs without parsing handler bodies.
- The `:rf/route` sub gives the entire route map; `:rf.route/id`, `:rf.route/params`, `:rf.route/query`, `:rf.route/transition`, `:rf.route/error` are conveniences.
- `:rf.route/navigate`, `:rf.route/handle-url-change`, `:rf.route/transitioned`, `:rf.route/url-requested` are stable, named events; trace events surface every navigation and every URL request.
- A registered `:rf.route/not-found` is required (per [§Route-not-found](#route-not-found--rfroutenot-found-canonical)); tools surface the `:rf.warning/no-not-found-route` trace event for apps missing the registration.

## Frame-destroy teardown

Routing's **durable** per-frame state — the route slice (with the active `:nav-token`) and the pending-nav slot — **lives in the frame's `runtime-db`** under `[:rf.runtime/routing]` (per [§The `:rf/route` slice](#the-rfroute-slice)) and is released **naturally** when the frame value goes away. The **host-side transient caches** are the exception: the saved **scroll-position cache** *and* the **nav-token / pending-nav allocator counters** (the `:nav-token-counter` / `:pending-nav-counter` high-water marks) are held in module-private per-frame atoms outside the frame value (per [§Scroll restoration](#scroll-restoration) and [§Navigation tokens — stale-result suppression](#navigation-tokens--stale-result-suppression)) — **not** `runtime-db` — so routing **publishes `:routing/on-frame-destroyed!`** to release their entries on `destroy-frame!`. This matches the per-feature artefacts that hold frame-scoped state outside the frame value:

- [Flows](013-Flows.md#frame-destroy-teardown) — publishes `:flows/teardown-on-frame-destroy!` because the per-frame flow registry and `last-inputs` dirty-check cache live in module-private atoms, not in the frame value.
- [Machines](005-StateMachines.md) — the machine snapshots live at `[:rf.runtime/machines :snapshots <id>]` inside `runtime-db` so they die naturally. The artefact publishes **two** distinct destroy hooks for state held outside the frame value: `:machines/on-frame-destroyed!` cancels the per-frame `:after` timer registry and epoch counters (the destroyed-frame cleanup callback, step 7 of [002 §Destroy](002-Frames.md#destroy)), and `:machines/teardown-on-frame-destroy!` runs the owned-actor teardown cascade — the reverse-order `:exit` walk plus per-actor handler unregistration (step 3). See [§Two destroy-hook verbs](002-Frames.md#two-destroy-hook-verbs) for why the two coexist.
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

`destroy-frame!` calls `(swap! frame-registry dissoc frame-id)` which drops the whole frame's frame-state (both partitions) along with everything else (per [002 §Destroy](002-Frames.md#destroy)). The route slice (`[:rf.runtime/routing :current]`) and the pending-nav slot (`[:rf.runtime/routing :pending-navigation]`) live in the reserved **runtime-db** child `:rf.runtime/routing` (per [Conventions §Reserved runtime-db keys](Conventions.md#reserved-runtime-db-keys)) and release in lockstep when the frame value goes away. The host-side transient caches — the saved **scroll-position cache** and the **nav-token / pending-nav counter** high-water marks — are held outside the frame value in module-private per-frame atoms (per [§Scroll restoration](#scroll-restoration) and [§Navigation tokens — stale-result suppression](#navigation-tokens--stale-result-suppression)), so `destroy-frame!` fires the single `:routing/on-frame-destroyed!` late-bind hook to drop that frame's entry in *both* caches. Beyond those there is no orphaned listener and no leaked timer to clear — EP-0037 R1 retired the corpus-wide `:on-match` error-emit listener, so routing installs no process-global error listener that would survive `destroy-frame!`.

## Multi-frame routing

Each frame has its own `[:rf.runtime/routing :current]` slice. URL ownership is an **explicit** declaration — a frame owns the browser URL only by carrying `:url-bound? true` on its frame config. Frames that do not declare it have independent routes that don't push to the browser URL.

1. Every frame's `runtime-db` may have a `:rf/route` slice at `[:rf.runtime/routing :current]` (a framework-owned runtime-db path, read through the public `:rf/route` sub — not an app-db path).
2. A frame is **URL-bound** only when it carries an explicit `(make-frame {:id … :url-bound? true})`. The URL owner's `:rf.route/navigate` events fire `:rf.nav/push-url`, and `popstate` (Back/Forward) drives it. The browser URL reflects the URL-owning frame's route. There is **no `:rf/default`-owns-by-default floor** — the runtime never infers URL ownership from absence. `:rf/default` may BE the owner, but only when it carries `:url-bound? true` like any other frame.
3. Frames without `:url-bound? true` are **not URL-bound**. `:rf.route/navigate` updates their `:rf/route` slice (state changes) but does not fire `:rf.nav/push-url`. This is the right default for story-variant frames, devcards, per-test fixtures.
4. The runtime holds "only one frame owns the URL at a time" by detection + deterministic resolution — registering a second `:url-bound? true` frame while one already owns the URL emits `:rf.error/duplicate-url-binding` and ownership resolves to the first claimant (the conflict is observable, not rejected). The duplicate-binding diagnostic fires from a registrar registration-hook that runs *after* the registry slot is written, so the conflict is observable but **not rejected**: both frames' `:url-bound? true` metadata is stored in the registry. Ownership is then a deterministic *resolution* over the stored metadata (`url-owner-frame-id`, [§popstate drives the URL-owner frame](#popstate-drives-the-url-owner-frame-both-directions)) — **the first frame to claim `:url-bound? true` (the incumbent) is the owner; the existing owner is unchanged** and the losing binding's history-mutation fxs (`:rf.nav/push-url` / `:rf.nav/replace-url`) no-op. The resolution is **first-claim-wins, NOT by id ordering**: the registrar's `(kind, id) → metadata` table is unordered, so the artefact tracks claim order in a process-global vector (maintained by the duplicate-binding registration-hook), and `url-owner-frame-id` returns the first-claimed binding that is still live. A later duplicate — *even one whose id sorts before the incumbent* — therefore cannot steal the browser URL; resolving by id ordering would have let it. The resolution is self-healing: if the incumbent later relinquishes its binding (re-registers `:url-bound? false`) or is destroyed, ownership falls to the next-claimed live binding. The error does not mutate any frame's metadata; resolving the conflict (removing one binding) is the app's concern.

   **Load order — frames registered before the routing artefact loads.** The registration-hook that records claim order is an *append-only future observer* (it does not replay prior registrations), so a frame that declared `:url-bound? true` **before** the routing artefact loaded is invisible to claim-order recording. To keep first-claim-wins deterministic *regardless of frame-vs-routing load order*, the artefact **reconciles the registry at hook-install time**: a single pre-existing `:url-bound? true` frame is seeded as the incumbent (its claim order is trivially known — it is the sole claimant), so a later duplicate — even an earlier-sorting one — cannot steal it. When **two or more** `:url-bound? true` frames pre-exist the load, their relative claim order is **unrecoverable** (the registrar table is unordered and no claim was recorded), so the runtime **fails closed**: `url-owner-frame-id` returns `nil` (no owner — outbound pushes no-op, popstate skips) and emits one `:rf.error/duplicate-url-binding` per extra pre-existing binding, rather than silently picking a winner by id sort. Re-registering or removing one of the bindings through the now-live hook re-establishes a deterministic claim order. This closes the URL-owner-steal hazard for the pre-load case in the same way the claim-order vector closes it for the post-load case. When no frame declares `:url-bound? true`, there is **no URL owner**: outbound `:rf.nav/push-url` / `:rf.nav/replace-url` no-op, and the inbound `popstate` listener skips (installing a history listener with no declared owner is a routing-config no-op, not a default-frame write).

### popstate drives the URL-owner frame, both directions

URL ownership is symmetric across the app↔browser boundary, and resolves to the **same single owner** in both directions:

- **Outbound (app → browser).** `:rf.nav/push-url` / `:rf.nav/replace-url` consult `url-owner-frame-id` (public) and only the owner mutates browser history. A non-owner's navigation updates its own `:rf/route` slice but no-ops the history push.
- **Inbound (browser → app).** A `popstate` listener dispatches `[:rf.route/handle-url-change url {:rf.route/cause :popstate}]` at `{:frame (url-owner-frame-id)}` — targeted at the **current** owner resolved at pop time. The trailing `:rf.route/cause` rider is not optional decoration: one event stands for three doors, so a *rider-free* dispatch on a client frame resolves as `:initial` ([§The request grammar](#the-request-grammar--address-policy-and-edit-keys)), and every cause-bearing diagnostic — the `:rf.route/planned` projection, an entry denial, a blocked navigation — would then report the wrong door for a Back/Forward. `url-owner-frame-id` returns the explicitly-declared owner, or `nil` when none is declared; the listener skips the dispatch (no frameless write) when there is no owner. So Back/Forward restores the owner frame's `:rf/route` slice (and the body rendered off it) whenever a frame has declared `:url-bound? true`.

This `popstate` listener is wired by the **`:url-bound?` frame LIFECYCLE**, automatically (rf2-g8pbwg): a `:url-bound? true` frame's creation (or re-registration, when it resolves as the URL owner) installs it — and does the initial URL → owner-slice sync in the same step, through the *same* dispatch closure, riding `:rf.route/cause :initial` where the browser-driven callback rides `:popstate`; the frame's destroy removes it. There is no imperative install/remove pair for an app to call — the earlier `install-url-listener!` / `install-history-listener!` / `remove-url-listener!` / `remove-history-listener!` exports are retired (pre-alpha, no back-compat shim). The install is idempotent (a re-registration replaces the prior listener, hot-reload safe) and is the inbound counterpart of the `:rf.nav/push-url` gate. Targeting `url-owner-frame-id` at pop time means the listener tracks whichever frame declared `:url-bound? true` — a popstate dispatched at a stale owner after ownership transferred would update a frozen slice and leave Back/Forward broken. The listener also skips entirely when no owner is declared, rather than synthesising `:rf/default`. A losing duplicate `:url-bound? true` registration (per [§Multi-frame routing](#multi-frame-routing) point 4) never installs its own listener — only the resolved owner's registration reaches the install step, so the existing owner's listener is never torn down and replaced by a loser's.

The install runs from a **POST-CREATE** lifecycle hook — fired *after* the frame container exists (and, on first registration, after `:initial-events` has run) — deliberately **not** the same `:frame` registration hook the duplicate-binding check above consults. That hook fires from *inside* the construction engine's frame-registered write, before the frame container exists; an install from there would dispatch the initial URL → owner-slice sync at a not-yet-live frame.

The story / devcard / SSR cases all benefit:

- **Stories / devcards**: frame-per-variant; route within the variant is independent of the page URL.
- **Per-test fixtures**: each test frame has its own route; tests don't accidentally hit `pushState`.
- **SSR per-request frames**: the request URL is fed in via `:rf.route/handle-url-change`; no client-side `pushState` (which doesn't exist server-side anyway).

## URL strategies

The router is **path-form** internally: `route-url` builds `/active`, `match-url` reads `/active`, and the whole navigation drain threads path-form URLs. But the browser's address bar can carry a **different shape** — `#/active` for a hash-URL app (no-server-rewrite static hosting; the shape most secretary-era re-frame v1 apps actually are). A **`:url-strategy`** is the thin skin between the two: a frame-level config map consulted at exactly **four** egress/ingress points, so the router's path-form model is unchanged and only the browser-facing shape differs.

A frame declares its strategy alongside `:url-bound?`:

```clojure
(rf/make-frame {:id :app
                :url-bound?   true
                :url-strategy rf.routing/hash-url-strategy})   ;; default: rf.routing/history-url-strategy
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

**Validated at frame construction — before the config commits.** An explicitly-declared `:url-strategy` is host-validated (a map carrying a callable fn for every host-required leg — both hosts require `:encode` / `:decode`; CLJS additionally requires the three browser legs, which SSR never executes) as a registration-time **preflight** at frame construction AND re-construction, over the final expanded config, **before** the frame config commits — before any registrar write, trace-policy write, frame-container creation, `:initial-events` dispatch, or trace emit (per [002 §make-frame — atomic create-and-register](002-Frames.md#make-frame--atomic-create-and-register-and-the-canonical-config-grammar)'s construction failure-atomicity: a bad declaration leaves no half-registered frame). A malformed declaration fails loud with `:rf.error/invalid-url-strategy`, the ex-data naming the `:frame`. **Omission alone selects the default**: a config with no `:url-strategy` key resolves to `history-url-strategy` and pays no validation; a *present* key — **including an explicit `nil`** — is an explicit declaration and is validated (presence semantics, not truthiness). A failed **re**-registration preserves the previous frame entirely — every previously committed config value, the installed URL listener instance, and the URL-claim order are unchanged, and no `:rf.frame/re-registered` fires. Declaring `:url-strategy` requires the routing artefact loaded **before** the frame is constructed: the preflight reaches core through the `:routing/preflight-frame-config!` late-bind hook, and a config declaring `:url-strategy` while the hook is unpublished fails loud with `:rf.error/routing-artefact-missing` rather than storing a strategy nobody can validate or execute (a `:url-bound?`-only config without the key remains registrable before routing loads). Validation is static shape/callability only — the preflight never *executes* a strategy leg (probing `:push!` or `:install-listener!` would itself cause browser effects); a shape-valid strategy whose leg throws at runtime fails at that lifecycle point, where the listener install keeps its failure-atomic install-new-before-teardown handoff. The construction preflight is the **sole** validation seam: because it runs at the one frame-config commit chokepoint — the only writer into the frame store the consult points read — a seated `:url-strategy` is always already valid, so the four consult points (the `route-link` href render is the hot one, re-run per render) are **trusted reads** that resolve the strategy verbatim and pay no per-consult re-validation (rf2-ecb4sx). A dev-only assertion, dead-code-eliminated from production builds, re-checks at the consult so a future write-path bypass still fails loud in development.

### The four consult points

The strategy is consulted **only** here; everything else — `route-url`, `match-url`, the navigation drain, the route slice — is path-form throughout:

1. **`:rf.nav/push-url`** — encodes the path-form URL and drives `window.history` via the owner's `:push!`.
2. **`:rf.nav/replace-url`** — same, via the owner's `:replace!`.
3. **`route-link` href render** — the rendered `:href` is `(encode path-url)` (so copy-link / open-in-new-tab land on the right address); the click still dispatches the path-form `:rf.route/url-requested`.
4. **The automatically-installed browser listener** — installs the owner strategy's browser listener and decodes each change to path-form before dispatching `:rf.route/handle-url-change`.

The strategy is resolved from the **URL-owning frame's** `:url-strategy` (default `history-url-strategy`), so a non-owner frame never consults it. The `:url-bound?` frame lifecycle (previous section) resolves the strategy once at install (which browser event to wire) — the OWNER (dispatch target) is re-resolved at every fire, so Back/Forward keeps tracking ownership even if it changes after install.

### Two strategies ship — the line holds at two

- **`history-url-strategy`** (the **default**) — HTML5 History, path-form. `:encode` / `:decode` are identity over the app-relative URL.
- **`hash-url-strategy`** — `#`-prefixed (`#/active`), for no-server-rewrite static hosting and secretary-era v1 migrations.

**Memory URLs need no third strategy.** A frame that does not declare `:url-bound? true` is already URL-free ([§Multi-frame routing](#multi-frame-routing)) — the non-url-bound frame *is* the "memory" case, spec-free. Stories, devcards, and per-test fixtures already ride that path; adding a memory strategy would duplicate it.

### `with-base-path` — the base-path combinator

A third orthogonal concern — a deployment **sub-path** (a host mounting several demos side by side, so an app that would otherwise own `/` instead lives at `/realworld/`) — is NOT a third strategy either. `with-base-path` (rf2-g8pbwg) is a **combinator** over an existing strategy: `(with-base-path strategy base)` re-adds `base` to `:encode` and strips it off `:decode` / `:install-listener!`, so `base` is stripped off every inbound URL and re-added to every outbound one, underneath whichever address-bar form the wrapped strategy already provides. Because `:encode` is the **single outbound encoding authority** (the nav fxs encode once, then drive the raw `:push!` / `:replace!` legs — [The strategy contract](#the-strategy-contract)), the base composition lives in `:encode` **only**; `:push!` / `:replace!` are NOT re-wrapped (rf2-irygd6). The mount-point prefix therefore sits **outside** the address-bar form — a `/demos`-deployed hash app's `route-link` href AND its address bar both read `/demos#/active` (base outside the fragment, the only shape a static host can route), never `#/demos/active` (base swallowed into the fragment):

```clojure
(rf/make-frame {:id :app
                :url-bound?   true
                :url-strategy (rf.routing/with-base-path
                                rf.routing/history-url-strategy
                                "/realworld")})
```

`route-url` / `match-url` and the rest of the navigation cascade stay path-form and base-agnostic — only the four consult points ever see the base path. A blank/nil `base` returns `strategy` unchanged, so the common no-sub-path app pays no wrapping cost. This closes the gap a base-path-deployed app previously had to fill with a hand-rolled popstate listener (the framework's install assumed the app owned the root path).

### SSR ignores strategies

On the server there is no `window` and no address bar: the request URL is fed in **path-form** via `:rf.route/handle-url-change`, the view renders against the slice, and `route-link` emits its **path-form** `<a href>` shell (`:encode` = `identity` server-side). A hash never reaches the server. So every side-effecting strategy key (`:push!` / `:replace!` / `:install-listener!`) is CLJS-only — the JVM half of each map carries only `:encode` / `:decode` — and the four consult points fall back to path-form when no `window` is present. On hydration the CLJS `route-link` render re-encodes the href through the frame's strategy, so a hash app's server shell carries `/active` and the hydrated anchor carries `#/active`, both pointing at the same route (no hydration mismatch — the route is the same, only the address-bar shape changes, and the shape is a client-only concern).

## Open questions

> **SA-4 classification.** Per [SPEC-AUTHORING §SA-4](SPEC-AUTHORING.md): all seven items are additive design candidates that do not block v1. Only **one** carries a tracking bead and so qualifies as **`:post-v1 tracked`** — the declarative `:on-leave` hook (`rf2-uu19xv`, deliberately deferred). The other **six** are **post-v1, untracked notes** — no tracking bead is filed yet (so none qualifies as `:post-v1 tracked`, which requires a `rf2-<id>`); each records the concrete "deferred until …" condition that files its bead when it fires. (The earlier "all eight are `:post-v1 tracked`" framing was an SA-4 violation — the untracked items had no id.) An eighth item, route-plan prefetch, **shipped** in EP-0037 R3 and so moved to [§Resolved decisions](#resolved-decisions).

### Declarative `:on-leave` route hook (post-v1)

`:can-enter` shipped as the first-class mirror of `:can-leave` (rf2-p69yaz Option A). A route-owned **`:on-leave`** hook — a declarative "run these events when this route is left" slot, the imperative sibling of `:on-match` (which runs on *entry*) — was considered as the leave-side companion (rf2-uu19xv) and **deliberately deferred, not shipped**. The shipped-surface `:on-leave` remedy was **rejected** in the same 012 design pass: the leave-side teardown story is already covered by two existing, sufficient surfaces —

- **Machine `:exit` actions and frame teardown.** A route that owns machine-shaped resources tears them down through the machine's `:exit` cascade, which fires on every exit path including the frame's destruction (per [005 §Cooperative cancellation](005-StateMachines.md) and [Cross-Spec-Interactions §Routing × Machines](Cross-Spec-Interactions.md#routing--machines)). 10+ machine examples already teach this shape; a route `:on-leave` would duplicate it for the machine case.
- **Route-owned resources release declaratively.** A route's `:resources` plan releases its owners on route change (per [016 §Route integration](016-Resources.md)) — the leaving route's `[:route prev-id prev-nav-token]` owner is dropped automatically, no `:on-leave` needed.

What `:on-leave` would add over these is a *route-declared, non-machine, non-resource* leave hook (e.g. "clear this app-db slice when leaving this route", "fire an analytics `left-page` event"). Today that rides an ordinary event dispatched from the `:can-leave` path or from a machine `:exit`; the declarative slot is sugar over it. Deferred until real apps surface a leave-side teardown that neither the machine `:exit` nor the resource-owner release covers cleanly — the two surfaces above carry the common cases, and adding a third leave-side mechanism before the gap is demonstrated would be redundant surface.

### Native nested layouts (post-v1)

Per [§Nested layouts](#nested-layouts) the v1 surface is `:parent` + the `:rf.route/chain` sub — the rendering side reads the layout chain as data and composes shells top-down.

**The data half shipped in EP-0037 R2.** The **parent-loader cascade for `:resources`** — a parent route's reads inherited by children so a child doesn't restate the parent's data plan, deduped by resolved `[scope resource-id canonical-params]` identity, with a partial-revalidation law so a sibling-leaf navigation under an unchanged parent does not re-run the parent's reads — is now the effective parent-to-leaf branch plan ([016 §Effective parent-chain resource plans](016-Resources.md#effective-parent-chain-resource-plans)). `:parent` is the opt-in; the shell owns shared reads once. The former duplication receipts (the `:realworld.profile/show` / `:realworld.profile/favorites` byte-identical profile-banner entries) are paid off in the same cut, and the drift risk they invited — divergent `:blocking?` across sibling tabs — is closed because the banner is declared once on the shared parent.

What remains deferred is only the **render** half — a true `<Outlet/>`-style render slot and a generic parent-metadata cascade for the render-affecting keys (`:on-match`, `:scroll`, `:head`, `:tags`, guards). Those are **deliberately not** folded by R2 (unrelated keys need incompatible merge/ordering rules), and a render-slot substrate stays out of scope until apps surface a real cost the chain-sub pattern can't carry; the `:parent` convention does not preclude a richer slot mechanism later. Untracked note — no bead filed yet.

### Data-form path patterns (post-v1)

Per [§The route table is data](#the-route-table-is-data) the v1 canonical wire form for `:path` is the string grammar (`"/account/:id/orders/*rest"`). A formally-specified vector-of-segments alternative (e.g. `[:account [:id :int] "orders" [:rest :catchall]]`) would carry per-segment schema inline and survive copy-paste better than embedded sigils. The host-optional sketch already exists as a *note* (per [§Path-pattern grammar (canonical)](#path-pattern-grammar-canonical) `[:files [:* :rest]]` ≡ `/files/*rest`), but it is not a formally-specified wire form. Deferred — the string grammar is the v1 wire form and tools, conformance fixtures, and `match-url` all key off it; the data form would be an additive parser front-end. Untracked note — no bead filed yet.

- **Reconsideration trigger (falsifiable).** A host without a string-parser library, or a concrete app that hits copy-paste corruption / missing inline per-segment schema on string `:path` values, demonstrates a real cost the string grammar can't carry — at which point the data form is formally specified.
- **Hard constraint on the eventual design (cross-cited, so the trigger can't graduate a second grammar).** Per [EP-0012 §Non-Goals](../docs/EP/EP-0012-path-optics-and-canonical-forms.md#non-goals) route data-form patterns are **not mandatory** and are added later only as an *alternate front end to the same prism laws* — this deferral is **demand-driven**, not a standing to-do. When the data front end does come it **MUST normalize into the same canonical template shape** the string grammar produces (per the EP-0012 rider: "a route pattern is a path template over segments, and a second template grammar would be the per-subsystem redefinition this EP exists to prevent"). Any data-form proposal that introduces a *distinct* template model, rather than a parser front-end that normalizes to the existing canonical form, is out of scope — that would be a second route grammar, which this deferral exists to prevent.

### Custom scroll-strategy registry (post-v1)

Per [§Scroll restoration](#scroll-restoration) the v1 contract is the closed three-enum set (`:top`, `:restore`, `:preserve`), and there is no host extension point at all — the map form that earlier drafts advertised was removed as a false promise (rf2-px26m; see [§Custom scroll strategies](#custom-scroll-strategies)). A first-class registry (apps `register-scroll-strategy!` named entries; routes / nav opts name them by keyword) is an additive composition surface that keeps strategy registration enumerable for tools. Deferred — the three enums cover the documented cases and locking them keeps tools' enumeration of scroll behaviour decidable. Untracked note — no bead filed yet.

- **Reconsideration trigger (falsifiable).** The **first** host-specific scroll strategy a real app demonstrably needs that none of the three enums can express. Until such a strategy appears, the named registry adds a surface tools must enumerate for no demonstrated gain. Note the bar this must clear: whatever ships must *execute* — the removed map form is precisely what an unbacked extension point costs, so a registry graduates only with a handler seam and a test proving a registered strategy runs.

### URL-state-as-source-of-truth (post-v1)

Per [§State-first, URL-second update order is locked](#state-first-url-second-update-order-is-locked) the v1 model is **state-canonical** (the runtime-db route slice), URL-derived: navigation mutates state first, then syncs the URL. The inverse — URL canonical, state derived (the browser URL is the single source of truth; subscriptions parse it on demand) — is a substantial design change with downstream impact on SSR, multi-frame, stale-suppression, and the navigation drain ordering. Deferred; v1's direction is locked because the URL update can fail (browser denies, offline) and state must remain consistent. Untracked note — no bead filed yet; **deferred until** a concrete app demonstrates a case where the state-canonical model is a genuine obstacle that the URL-canonical inversion would fix, weighed against the SSR / multi-frame / stale-suppression downstream cost — the bead files when that case is on the record, not on speculation.

### Declarative redirect rules in route metadata (post-v1)

Per [§Redirects and guards](#redirects-and-guards) v1 redirects compose as interceptors — guards are ordinary middleware over `:rf.route/navigate`, with full access to `app-db` and the event vector. A declarative metadata key (e.g. `:redirect-to :route/login`, optionally a fn of the route map) would let the simple "always redirect this route" cases skip interceptor boilerplate. Deferred — the interceptor form is the universal carry; the declarative key is sugar over it once the common shapes have settled in real apps. Untracked note — no bead filed yet; **deferred until** the common "always redirect this route" shapes have settled across enough real apps to lock a declarative grammar over the interceptor form.

### Route-driven code loading — the `:load` seam (post-v1, seam only)

The **code-loading axis** — per-route code splitting, so a route's view code is not in the first-paint bundle until that route is reached — is named here so it stops being a blind spot. v1 ships no story for it, shipped *or* deferred *or* rejected: the [§The root view dispatches on `:rf.route/id`](#the-root-view-dispatches-on-rfrouteid) pattern resolves each route's leaf view at render time, which requires every route's view code loaded up front, and nothing in this spec speaks to lazily-loaded route code. React Router v7's route modules (`lazy:`) and TanStack Router's prefetchable chunks make "don't ship the admin panel to every visitor" a config line; re-frame2 apps that outgrow single-bundle today must hand-roll a host module loader (a CLJS app would reach for `shadow.lazy` plus a "module pending" sentinel) that races `:on-match` with no defined interaction.

CLJS whole-program optimisation plus gzip pushes the single-bundle wall much further out than in JS-ecosystem apps, and no example or user has hit it — so this is deliberately **seam-only, committing to nothing**. The shape a post-v1 revisit would consider (recorded so the axis is on the map, not blessed): a host-discretion route-metadata key `:load` naming a host-specific loadable (a `shadow.lazy` loadable, in the CLJS reference) that the runtime resolves as part of activation, folding into the **resource-derived readiness projection** ([§Route readiness is a resource projection](#route-readiness-is-a-resource-projection)) — a pending code-load is treated as a blocking first-load requirement, so `:rf.route/transition` stays `:loading` until it settles; a load failure projects `:rf.route/transition :error` with a new `:rf.error/route-load-failed` category on `:rf.route/error`, exactly as a blocking resource first-load failure does; a superseded in-flight load is discarded like any other stale async (per [§Navigation tokens](#navigation-tokens--stale-result-suppression)); and SSR ignores `:load` on the server side because server bundles are eager — the runtime already knows which side it is on (the `:server`/`:client` platform split, [011](011-SSR.md)). The contract, were it ever shipped, would be only the ordering and the error category — never a particular module system. Deferred until an app demonstrates the single-bundle cost; naming the seam now costs one item and commits the spec to nothing. Untracked note — no bead filed yet (seam-only, committing to nothing until the trigger fires).

## Resolved decisions

### Route-plan prefetch ships as a warm-mode verb, not an app-space idiom

Per [§Per-route data loading](#per-route-data-loading) a route's `:resources` plan runs on **activation**, so without a pre-navigation surface the click pays the load — hovering a link could not warm the destination, and the competitive gap against React Router (`<Link prefetch>`) and TanStack Router (`preload` / intent) stayed open. The decision was **where** the verb belongs, because the cache machinery was already there: [016 §Active owners and causes](016-Resources.md#active-owners-and-causes) supports ownerless, cause-only `ensure` entries (the focus/reconnect scans use exactly that shape) and unowned entries are GC-eligible. App-space hover-`ensure` already worked *per resource*, but it forced every link site to re-derive the destination's params / `:when` / scope precedence — the exact duplication the plan machinery exists to prevent. So the verb ships at **route-plan level**.

**Shipped in EP-0037 R3.** The normative contract is [§Route-plan prefetch — warm-mode intent preload](#route-plan-prefetch--warm-mode-intent-preload): a public `[:rf.route/prefetch {address}]` event that runs the destination's effective resource plan in warm mode — every ensure **ownerless** under cause `[:route-prefetch <route-id>]`, `:blocking?` **inert**, `:on-match` and guards **not run**, one `:rf.route/prefetched` summary trace, and a later activation reusing the warmed work through ordinary dedupe. The paired link opt is `:prefetch :intent` ([§Linking from views](#linking-from-views--plain-anchor-semantics), and [§The Freehand route-link descriptor](#the-freehand-route-link-descriptor) for the intent-position grammar), which binds the three intent positions and is stripped before DOM emission; conformance row **FH-ROUTELINK-008** carries that law. The non-goals fence recorded in the normative section still holds: no global default, render or viewport mode, hover-delay option, separate preload cache, or prefetch-stale clock, and raw URLs, external links, and route-driven code chunks are not prefetched.

### `:rf.route.nav-token/*` trace-operation namespace

The two nav-token trace operations — `:rf.route.nav-token/allocated` and `:rf.route.nav-token/stale-suppressed` (per [§Navigation tokens — stale-result suppression](#navigation-tokens--stale-result-suppression)) — live under `:rf.route.nav-token/*`. An earlier carve-out grandfathered the bare `:route.nav-token/*` prefix as the sole framework trace-operation namespace outside `:rf.*` (per the now-removed paragraph in [Conventions](Conventions.md)); closed that single-bit-of-difference exception, mechanically renaming all 91 occurrences across spec, conformance fixtures, implementation, docs, skills, and tools. The Conventions single-root rule (every framework-owned keyword sits under `:rf.*`) now holds without exception.

### URL ownership is an explicit declaration (no default-frame floor)

Per [§Multi-frame routing](#multi-frame-routing) a frame owns the browser URL only by carrying an explicit `(rf/make-frame {:id … :url-bound? true})`; the runtime enforces "only one frame can own the URL at a time" (registering a second `:url-bound? true` frame emits `:rf.error/duplicate-url-binding`). This was chosen over a "first frame to dispatch `:rf.route/navigate` wins" rule because explicit declaration is auditable at registration time and matches the story / devcard / per-test-fixture / SSR-per-request use cases without surprise.

EP-0002 removed the prior `:rf/default`-owns-the-URL-by-default floor: URL ownership was an *absence repair* (the default frame owned the URL unless it opted out), which the carried invariant forbids. Ownership is now a positive host/bootstrap policy. `:rf/default` may BE the URL owner, but only when it declares `:url-bound? true` like any other frame; an app with no declared owner simply has no URL owner (outbound history mutations no-op, the popstate listener skips). A migration that wants the old behaviour declares `(rf/make-frame {:id :rf/default :url-bound? true})` at bootstrap.

### State-first, URL-second update order is locked

Per [§Pattern-level contract](#pattern-level-contract), navigation runs state changes first, then the URL update, then `:on-match` dispatches and the scroll effect. The order is locked: if the URL update fails (browser denies, user is offline) the state is still consistent. An earlier alternative — URL-first — was rejected because the runtime-db route slice becomes the source of truth for what URL the runtime *intends*; the `:rf.nav/push-url` fx is a downstream sync.

### Three-enum scroll strategy (`:top`, `:restore`, `:preserve`)

Per [§Scroll restoration](#scroll-restoration) the canonical scroll-strategy contract is the closed three-enum set. A custom scroll-strategy registry was considered but deferred to [§Open questions](#open-questions) — the three enums cover the documented cases (default-to-top on new navigation, restore on back/forward, preserve on intra-page transitions). Earlier drafts also admitted a map form for host-specific shapes; nothing ever interpreted it, so it was removed rather than left standing as an accepted-and-ignored option (rf2-px26m). Locking the enum keeps tools' enumeration of scroll behaviour decidable.

### `:rf.route/not-found` is the single canonical reserved id

Per [§Route-not-found — `:rf.route/not-found` (canonical)](#route-not-found--rfroutenot-found-canonical) the framework names exactly one reserved route id for unmatched URLs. Earlier sketches considered per-host customisation of the reserved id; the single-id rule was chosen because tools, conformance fixtures, and the `:rf.warning/no-not-found-route` trace event all depend on it. Apps that want per-error-kind visual treatment branch inside the `:rf.route/not-found` view on `:reason`.

### Run-to-completion enforced for navigation drains

Per [§Pattern-level contract](#pattern-level-contract) the `:rf.route/navigate` drain — state update, URL push, `:on-match` dispatches, scroll effect — settles inside a single drain. This matches [Spec 002 §Run-to-completion dispatch](002-Frames.md#run-to-completion-dispatch-drain-semantics) and was chosen over a multi-drain approach so that subscribers see a consistent post-navigation state in one render pass rather than visible intermediate states.

## Cross-references

- [000-Vision §Working design implications](000-Vision.md#working-design-implications) — "routing is state plus events."
- [011-SSR.md](011-SSR.md) — SSR-side route resolution.
