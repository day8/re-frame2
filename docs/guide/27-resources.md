# 27 - Server-state and resources

Server state is the state your app doesn't own. The truth lives on a server, you hold a cache of it, and that cache goes stale the moment you read it. Every SPA grows a private answer to the same questions: where does the cached copy live, how do I know it's stale, who's allowed to refetch it, what happens when two screens want the same data, and how do I stop a logged-out user from seeing the previous user's dashboard. TanStack Query, RTK Query, SWR, and `shipclojure/re-frame-query` are all answers to exactly that. This chapter is re-frame2's answer, and it's re-frame2-shaped: **views read server state passively through subscriptions; route entry, events, and machines *cause* it to fetch; and the cache lives in the framework-owned runtime partition, not in your `app-db`.**

> **Status: optional, post-v1 artefact.** Resources ship in `day8/re-frame2-resources` — an optional capability per the [capability matrix](../../spec/000-Vision.md). An app that doesn't want declarative server-state expresses it with [Pattern-RemoteData](../../spec/Pattern-RemoteData.md) plus managed HTTP ([chapter 10](10-http.md)) directly; that keeps working. The normative contract is [Spec 016 — Resources](../../spec/016-Resources.md); this chapter is the tutorial. **Scope is HTTP-only** — GraphQL is a deferred later phase. The read-resource MVP, **mutations** (`reg-mutation` / `:rf.mutation/execute`, the causal-write counterpart — see [Mutations](#mutations--the-causal-write)), and **focus/reconnect revalidation** (see [Focus and reconnect revalidation](#focus-and-reconnect-revalidation)) are landed — the first public-beta gate is complete. Optimistic rollback is named here but lands with a later slice (see [Deferred](#whats-deferred)).

## The one-line thesis

**A resource is a sub you read and a cause you fire.**

That's the whole chapter compressed. You register a resource once — a named, cached read with a scope, a params schema, and a request. After that, a *view* reads it through an ordinary subscription (`@(rf/subscribe [:rf.resource/state {...}])`) and never fetches anything; a *cause* — a route becoming active, an event firing, a machine entering a state — dispatches `:rf.resource/ensure` to make the fetch happen. The runtime owns everything in between: identity, cache scope, staleness, dedupe, invalidation, garbage collection, in-flight ownership, SSR hydration, and the metadata Xray reads. You stop re-implementing that bookkeeping per feature.

Hold two distinctions, because they run through everything below:

- **Owners keep a resource alive; causes explain why work happened.** A route owns its article while you're on the page; a refresh-button click is a cause, not an owner.
- **Params identify the read; scope is the leak boundary.** Params say *which* article; scope says *whose* cache — the tenant, user, locale, or impersonation boundary that must never leak between users. Scope is **mandatory** and **fails closed**.

## The artefact, and why it's separate

Resources ship as their own artefact, `day8/re-frame2-resources`:

```clojure
{:deps {day8/re-frame2           {...}
        day8/re-frame2-http      {...}   ;; the managed-HTTP transport
        day8/re-frame2-resources {...}}}
```

One `(:require [re-frame.resources])` at app boot wires the late-bind hooks, and from then on `reg-resource`, the `:rf.resource/*` events and subs, and the route `:resources` metadata key are all available. An app that never requires the artefact sees the `reg-resource` wrapper throw a clean `:rf.error/resources-artefact-missing` naming the exact Maven coordinate — there's no silent no-op. The routing and SSR integrations are *late-bound*: an app that loads resources but not routing or SSR carries none of their code.

## The smallest resource loop

Concepts before notation. Here's the entire idea in three moves — register, cause, read:

```clojure
(ns example.articles
  (:require [re-frame.core :as rf]
            [re-frame.resources]))            ;; boots the artefact

;; 1. A resource is data in the registry.
(rf/reg-resource :article/by-slug
  {:params-schema [:map [:slug :string]]
   :scope         :rf.scope/global            ;; REQUIRED — an explicit claim
   :request (fn [{:keys [slug]} _ctx]
              {:request {:method :get :url (str "/api/articles/" slug)}
               :decode  :json})})

;; 2. An event CAUSES the fetch.
(rf/dispatch [:rf.resource/ensure
              {:resource :article/by-slug
               :params   {:slug "welcome"}
               :owner    [:lease :article/opened "welcome"]
               :cause    [:event :article/opened]}])

;; 3. A view READS it passively — no fetching here.
(rf/reg-view article-page []
  (let [state @(rf/subscribe [:rf.resource/state
                              {:resource :article/by-slug
                               :params   {:slug "welcome"}}])]
    (cond
      (:loading? state)                              [article-skeleton]
      (and (:error state) (not (:has-data? state)))  [article-error (:error state)]
      :else                                          [article-view (:data state)])))
```

The view is passive; the event caused the ensure; the runtime owns the state. Every other thing in this chapter is a refinement of those three moves.

A resource creates durable runtime-db state — cache data, params, scopes — that egresses to traces, tools, SSR, and hosted monitoring. Because that data's natural home is the schema that already validates it, **classification rides per-slot `:sensitive?` / `:large?` Malli props on the resource's `:params-schema` / `:data-schema`** (the same mechanism machine `:data` and HTTP bodies use), not a separate declaration: `:params-schema [:map [:slug :string] [:partner-token {:sensitive? true} :string]]` redacts the token everywhere the resource entry egresses. Sensitive scopes/params don't ride raw merely because the entry's `:data` was redacted, and a sensitive resource entry hydrates as a metadata-only redacted entry under SSR. The full model — and why durable `app-db` policy lives on the *frame* instead — is [chapter 23](23-privacy-and-large-things.md).

## Resource identity — the scoped key

A resource *instance* is identified by a triple:

```clojure
[cache-scope resource-id canonical-params]
```

For example:

```clojure
[[:rf.scope/session {:user-id "u-42" :tenant-id "acme"}]
 :article/by-slug
 {:slug "welcome"}]
```

This **scoped resource key** is the cache key, the request-correlation token, and the unit Xray and SSR enumerate. A few rules make it trustworthy:

- **Params are canonicalized.** Maps are normalized so key order doesn't affect identity; two spellings of the same params canonicalize to the same scoped key and resolve to *one* cache entry. The canonical EDN *is* the identity — there's no separate hash to keep in sync (a digest, if a size-constrained screen shows one, is just a recomputable nickname for the params, never the cache key itself; see [chapter 02 — the value *is* the identity](02-app-db.md#the-value-is-the-identity)). Host values — functions, promises, host date objects, DOM nodes, AbortControllers — are not portable data, so they're rejected at the key boundary. (A *date* is fine as identity — but as an **EDN instant**, not a host `Date`/`js/Date`: encode it to an `#inst` before it reaches the params boundary, after which it's an instant fact, not a host object.) Every variable that affects the remote read MUST be in params; `nil`-vs-missing is decided by your `:params-schema`, not by accident.
- **Scope is canonicalized the same way.** `{:tenant-id "acme" :user-id "u-42"}` and `{:user-id "u-42" :tenant-id "acme"}` are the *same* scope, never two leaking caches.
- **The correlation id carries the full scoped key.** The same params in two different user scopes can never supersede each other's results.

There's no `:cache-key` escape hatch in v1 — the **scoped key** *is* the identity, and params are canonicalized *within* that key. Two reads collapse to one cache entry only when their whole scoped key matches: same resource id, same canonical scope, *and* same canonical params. Equal params under different scopes are different entries — scope is the leak boundary (next section). (Projections like "just the title" are ordinary subscriptions layered over `[:rf.resource/data ...]`, not a TanStack-style `:select` key. That's a structural advantage of the subscription graph, not a missing feature.)

## Scope — the leak boundary that fails closed

Scope is the cache's tenant / user / permission / locale / impersonation / SSR boundary, and a resolved scope can carry PII. **A boundary that critical must fail closed: it never silently defaults to "shared."** This is the load-bearing security property of the whole feature, so it's worth slowing down on.

### Every resource declares a scope policy (required)

`:scope` at `reg-resource` is **required**. It declares a *policy*, drawn from a closed set:

- **`:scope :rf.scope/global`** — the resource is *explicitly* global. This is a claim: "the same params produce the same data for every user, tenant, permission-set, locale, and impersonation state." It's an auditable assertion, not a convenience hideaway.
- **`:scope <resolver>`** — derive the scope deterministically. A route-resource resolver is `(fn [route ctx] ...)`; a sub-resolvable resolver is a pure data value or a fn-of-nothing. The resolved scope materializes as visible EDN in the key.
- **`:scope :rf.scope/from-caller`** — the scope is required *from the use site*: every `:rf.resource/ensure` / `:rf.resource/refetch` / `[:rf.resource/state ...]` call must supply `:scope`, or a route resolver must.
- **No declared policy** — a loud **registration error** (`:rf.error/resource-missing-scope-policy`). "I forgot this read is user-scoped" is unrepresentable at registration, rather than something Xray has to guess from `/me`-looking URLs.

There is **no `:rf.scope/global` default.** A user-scoped read can never be silently registered as global. Stating scope intent once, at the registration site, is the loud-failure ethos applied to the cache's leak boundary.

### Resolution precedence (events; no global fallthrough)

For a resource *event*, the runtime resolves the concrete scope in this order — and there is no tier-4 `[:rf.scope/global]` fallthrough:

1. `:scope` supplied on the event payload;
2. the route-resource `:scope` resolver `(route, ctx)`;
3. the resource-spec `:scope` resolver.

If none yields a scope, resolution fails closed: a `:rf.scope/global` resource resolves to global *only because that's its declared policy*; a `:rf.scope/from-caller` resource reached with no scope is a loud `:rf.error/resource-scope-required-from-caller`, not a silent global read.

### Subscription-side resolution (the silent-leak seam)

Subscriptions are **pure** — a sub can't run a `(route, ctx)` resolver, because it has no routing match or event context. This is exactly the seam where a leak used to hide: a route ensures a resource under `[:rf.scope/session {...}]`, but a view's `[:rf.resource/state {...}]` that omits `:scope` would resolve to a *different* scope and read `:idle` forever — a permanent skeleton with no error anywhere. Resources close it the same way the rest of re-frame2 closes silent-wrong-target bugs: loudly.

A subscription resolves scope from, in order:

1. `:scope` on the **subscription payload**;
2. the resource spec's `:scope` *only if* a pure sub can evaluate it — an explicit `:rf.scope/global` claim or a pure-data / fn-of-nothing resolver.

A sub that **cannot** resolve a scope raises a structured `:rf.error/resource-sub-unresolved-scope` carrying the resource id and the unresolvable policy — **never** a silent global read and **never** a silent `:idle`. The fix the error points at is explicit: pass `:scope` on the subscription payload (the same scope the owning route/event ensured under), or re-declare the resource with a sub-resolvable policy.

The practical rule: **if a route or event ensures under a session scope, the view must subscribe under that same session scope.** The cleanest way to do that is a subscription that derives the session scope from `app-db`:

```clojure
(rf/reg-sub :session/resource-scope
  (fn [db _] [:rf.scope/session (select-keys (:auth db) [:user-id :tenant-id])]))

;; In the view:
(let [scope @(rf/subscribe [:session/resource-scope])
      state @(rf/subscribe [:rf.resource/state
                            {:resource :dashboard/summary :scope scope :params {}}])]
  ...)
```

### Named scope resolvers — derive viewer scope once

Session, tenant, organization, locale, account, and impersonation all have the same shape: a *viewer identity* lives in app state, and it should determine cache scope. Threading that derivation by hand through every route resource, event-side ensure, subscription, and invalidation descriptor is exactly the kind of repetition the framework should own. **`reg-resource-scope`** registers a named, pure, *declared* scope resolver, derived once and reused everywhere a derived scope is allowed:

```clojure
(rf/reg-resource-scope :realworld/session
  {:inputs {:username [:db [:auth :user :username]]}
   :resolve
   (fn [{:keys [username]} _ctx]
     (when username
       [:rf.scope/session {:username username}]))})
```

A resolver is **pure** — it derives a scope and does nothing else (no fetch, no dispatch, no ambient host reads). The `:inputs` map is the load-bearing part: names on the left, `[:db <path>]` source descriptors on the right. Declaring inputs lets tools *explain which app facts decide a resource identity*, and lets the runtime re-resolve scope only when a relevant input changes. (A whole-db function sugar — `(fn [db _ctx] …)` — is supported, but it lowers to an explicit whole-db dependency, and tooling marks that cost.)

You reference a named resolver with `{:from-db :realworld/session}` anywhere a derived scope is allowed — resource registration `:scope`, route resource entries, event-side ensure/refetch payloads, subscriptions, invalidation descriptors, map-form populate/patch/remove targets, and clear-scope helpers:

```clojure
(rf/reg-resource :realworld/feed
  {:scope   {:from-db :realworld/session}
   :params  (fn [{:keys [page]}] {:page page})
   :request (fn [{:keys [page]} _ctx]
              {:request {:method :get :url "/articles/feed"
                         :params {:limit 20 :offset (* 20 (dec page))}}
               :decode :json})
   :tags    (fn [_params _value] #{[:feed] [:article-list]})})

(rf/reg-route :realworld/home
  {:path "/"
   :resources [{:resource :realworld/feed :params {:page 1}
                :scope {:from-db :realworld/session} :blocking? true}]})
```

The session feed can now be a declarative route resource — loaded on entry, released on leave, read by subs, and invalidated by descriptors — all keyed by the *same* resolver. Nil from a resolver at a scope-requiring site is **fail-closed**: route planning never substitutes global, and a subscription surfaces a "scope unresolved" diagnostic rather than quietly reading a different entry.

Xray surfaces both halves: a **Named scope resolvers** section lists each resolver's id + declared inputs + the whole-db cost flag (the static declaration, no PII), and a **scope resolution timeline** shows each `:rf.resource/scope-resolved` event — which resolver ran, the resolved scope (summarized), and the fail-closed nil evidence.

### `clear-scope` is causal

Logout, account switch, tenant switch, permission change, locale switch that affects wire data, and impersonation enter/exit must each clear or replace the affected scope. That's a causal operation, the `:rf.resource/clear-scope` event:

```clojure
(rf/dispatch [:rf.resource/clear-scope
              {:scope [:rf.scope/session {:user-id "u-42" :tenant-id "acme"}]
               :cause :logout}])
```

It removes or marks unusable every entry in that scope, releases owners in it, aborts in-flight requests that have no remaining owner outside it, suppresses late replies by scope + generation, and emits trace rows explaining what it removed, aborted, or left alone. (An auth-*token* refresh doesn't necessarily require clearing scope — if the user, tenant, permissions, and impersonation state are unchanged, the cache is still valid.)

Logout has a subtle ordering problem: you want to clear the scope the user *was in*, but the obvious place to do it — after `(dissoc db :auth)` — has already removed the identity the scope is derived from. The canonical idiom resolves the **concrete** old scope from the handler's **coeffect db** (pre-transition by definition — the causal input) with the `resolve-resource-scope` helper, and passes it to `clear-scope` concretely:

```clojure
(rf/reg-event-fx :auth/logout
  (fn [{:keys [db]} _]
    (let [old-scope (rf/resolve-resource-scope db :realworld/session)]
      {:db (dissoc db :auth)
       :fx [[:dispatch [:rf.resource/clear-scope {:scope old-scope :cause :logout}]]]})))
```

`resolve-resource-scope` is a plain function over the resolver registry — not an effect (no app-state or dispatch side effects), no resolution-timing ambiguity. It does emit `:rf.resource/scope-resolved` dev-time trace evidence like every resolution site, so it is a *resolver helper* rather than a pure data helper — safe in the logout coeffect-db idiom either way. (A whole-db snapshot riding an event payload was considered and rejected: it would be an egress-bearing record on traces and epoch history. A `{:from-db …}` reference *may* still appear on a `clear-scope` payload under the single use-time resolution rule; one that resolves nil at a clear-scope site emits a loud diagnostic, never a silent no-op.)

### Xray is defense-in-depth, not the boundary

Because every resource carries an explicit policy, the old `/me`-URL heuristic is downgraded to a hint. Xray's standing security-review surface is *structural*: it enumerates every `:rf.scope/global` resource (the audit list) and warns about *suspicious* explicit-global resources whose requests look session-dependent. A missing scope is now a loud error, not something a tool compensates for.

## Scope — the leak boundary other libraries do not have

The previous section described *how* re-frame2's scope works. This one is about *what it is*, because it's the one place this chapter makes a claim no competitor can: **re-frame2 is the only server-state library in its class where cache scope is a structural, fail-closed isolation axis — not a convention you have to remember.** That's worth being precise about, because "we have multi-tenancy support" is a thing every library says and almost none enforces.

### The mental model you already have: a viewer id inside the query key

If you've used TanStack Query, RTK Query, SWR, or `shipclojure/re-frame-query`, you already know the shape of the answer. You put viewer identity *inside the query key* so two users' caches don't collide:

```js
// TanStack Query — the viewer id is one segment of the key, by convention.
useQuery({ queryKey: ['dashboard', userId, tenantId], queryFn: ... })

// RTK Query — the tag/arg carries the viewer; tenancy lives in the arg.
useGetDashboardQuery({ tenantId, userId })

// SWR — the viewer is interpolated into the key string.
useSWR(`/api/dashboard?tenant=${tenantId}&user=${userId}`, fetcher)
```

This works, and it's the right instinct. The cache key *should* carry the viewer. The problem is **nothing enforces it.** The viewer id is one segment of a key you assemble by hand, in every call site, every time. Forget `tenantId` in one of forty `queryKey`s and that read silently shares one tenant's cache with the next — no error, no warning, just a dashboard showing the previous user's data. The leak is a *missing line*, and a missing line throws no exception. And there's a second seam these libraries don't even have a place to address: **logout.** Clearing "the previous user's cache" is `queryClient.clear()` (a sledgehammer that drops everyone's, including a still-valid global cache) or a hand-maintained list of keys to invalidate (another thing to forget). None of the four benchmarked libraries ships a *scoped* clear, and — verified across their canonical example apps (RealWorld, TodoMVC, Linearlite) — none even *exercises* multi-tenancy. The leak boundary is left as an exercise for the reader.

### The re-frame2 equivalent: the resolved scope *is* a key segment, and it's required

re-frame2 keeps the same mental model — the viewer belongs in the key — and removes the two ways it goes wrong: the *forgotten segment* and the *missing logout clear*. Scope is a **declared policy at registration** (you cannot register a read without saying whose cache it is), it **resolves into the key structurally** (the runtime puts it there, not your call site), it **fails closed** (an unresolvable scope is a loud error, never a silent shared read), and it has a **causal clear** (logout is a first-class scoped operation, not a manual key list). The convention you were *trusting* becomes a guarantee the framework *enforces*. Concretely, the boundary rests on six structural facts, each one already introduced above:

| Guarantee | Mechanism | What it prevents |
|---|---|---|
| **Scope is required, declared once** | `:scope` is mandatory at `reg-resource`; no policy is a `:rf.error/resource-missing-scope-policy` registration error. | "I forgot this read is user-scoped." The viewer dimension is unrepresentable as an afterthought — it's stated at the definition site, not assembled per call. |
| **Scope is *in* the identity** | The [scoped resource key](#resource-identity--the-scoped-key) is `[scope resource-id params]`; the resolved scope is a canonicalized key segment the runtime computes, not a string you concatenate. | The forgotten-segment leak. Two principals' reads of the same params land on two structurally distinct entries — neither is reachable through the other's key. |
| **Sub-side resolution fails closed** | A subscription resolves its own scope or raises `:rf.error/resource-sub-unresolved-scope` — [never a silent global, never a silent `:idle`](#subscription-side-resolution-the-silent-leak-seam). | The read seam: a view that under-specifies scope can't quietly read a *different* (or shared) principal's entry. The error names the fix. |
| **Logout is a causal scoped clear** | [`:rf.resource/clear-scope`](#clear-scope-is-causal) removes a *named* scope's entries, releases its owners, suppresses its late replies, and emits a trace of exactly what it touched — leaving every other scope (and global) intact. | The cross-session leak. The next principal's session structurally cannot read the prior one's cache, and clearing one principal never collaterally drops another's (or a still-valid global). |
| **Invalidation is scoped by default** | [Tag invalidation](#invalidation-by-tag) and [per-target descriptors](#per-target-scoped-invalidation) reach *exactly* the resolved scope; crossing principals is an explicit, audited `:cross-scope? true` escape, visible in Xray. | The cross-tenant blast. A write can't accidentally stale or refetch another tenant's data through a shared tag. |
| **The boundary is auditable, not heuristic** | Xray enumerates every `:rf.scope/global` resource (the [scope audit surface](#xray-is-defense-in-depth-not-the-boundary)), lists each [named resolver's declared inputs](#named-scope-resolvers--derive-viewer-scope-once), shows the scope-resolution timeline, and runs the [scope-mismatch lint](#subscription-side-resolution-the-silent-leak-seam). | The *un-reviewable* cache. "Which reads cross users?" is a query over declared data, not a code review hoping someone remembered every key segment. |

The shift is from *trust* to *structure*. In a query-key library, "no cross-user leak" is a property of your discipline — true only as long as every developer remembers every segment in every key forever. In re-frame2 it's a property of the *type of the cache key*: the viewer is in the identity, resolution fails closed, and the only way to cross the boundary is the loud, audited escape hatch. A forgotten scope is a registration error; a wrong scope is a fail-closed read with a diagnostic; a logout is one causal event. (These guarantees are pinned as executable conformance tests — the cross-user logout-leak, the wrong-scope fail-closed read, and the scoped-invalidation isolation — in `implementation/resources/test/re_frame/resources_scope_leak_boundary_cljs_test.cljc`, alongside the multi-scope lease-lifecycle non-interference suite.)

### Where this matters, and where it doesn't

This isn't free abstraction tax for a single-tenant app. A genuinely global read — public articles, a shared product catalog, anything where the same params produce the same bytes for every viewer — is `:scope :rf.scope/global`, an explicit one-word claim, and you're done; the boundary machinery costs you nothing. The structural payoff lands the moment your app has *any* viewer-relative server state — a per-user dashboard, a tenant-scoped feed, an impersonation/admin mode, a permission-gated list — which is almost every real app eventually. At that point the question stops being "did I remember the viewer in this key?" (a question you re-answer, fallibly, at every call site) and becomes "is this resource's declared scope correct?" (a question you answer once, at registration, and a tool can audit). That's the differentiator: the leak boundary is part of the cache's structure, so it holds without anyone having to keep holding it up.

## Status — facts, not derived booleans

Resource state uses [Pattern-RemoteData](../../spec/Pattern-RemoteData.md) semantics, but it *refines* the broad `:error` state into something views never have to disambiguate. The durable entry stores facts; the `:rf.resource/state` sub projects derived booleans. The five statuses:

| `:status` | Meaning |
|---|---|
| `:idle` | No load attempted (or no entry yet). |
| `:loading` | **First load, no usable data yet.** Show a skeleton. |
| `:fetching` | **Work in flight while prior data stays visible** (refresh / stale-while-revalidate). Show the data plus a quiet refresh indicator. |
| `:loaded` | Usable data present. May still be stale. |
| `:error` | **No usable data because the first load failed.** Show an error. |

The load-bearing invariants:

- **`:error` is reserved for first-load failure** — no usable data. A failed *background* refresh does NOT move to `:error`: the entry returns to `:loaded`, keeps the prior `:data`, and records the failure in `:refresh-error` (cleared by the next success).
- **Freshness is orthogonal to load status.** A `:loaded` entry may be stale; a `:fetching` entry may be refreshing stale data. Don't conflate "is it stale?" with "is it loading?".
- **`:stale?`, `:loading?`, `:fetching?`, `:has-data?` are derived sub values, not stored facts.** Your view reads them; it never infers "error with stale data" from `(:status state)` plus `(:has-data? state)`.

Two worked projections from `:rf.resource/state` make the distinction concrete:

```clojure
;; First-load failure — blank, show the error.
{:status :error :data nil
 :error {:kind :rf.http/http-5xx :status 503}
 :refresh-error nil :has-data? false}

;; Background-refresh failure — prior data kept, refresh warning surfaced.
{:status :loaded :data {:title "Welcome"}
 :error nil
 :refresh-error {:kind :rf.http/http-5xx :status 503}
 :has-data? true :fetching? false}
```

The `:error` / `:refresh-error` envelopes carry the same closed `:rf.http/*` failure taxonomy as managed HTTP ([chapter 10](10-http.md)). And a successful refresh that returns identical EDN preserves the old `:data` value by structural sharing, so downstream subs and views stay quiet when nothing changed.

The full subscription family:

```clojure
[:rf.resource/state         {:resource … :scope … :params …}]  ;; the whole view-model
[:rf.resource/data          {…}]   [:rf.resource/status        {…}]
[:rf.resource/loading?      {…}]   [:rf.resource/fetching?     {…}]
[:rf.resource/stale?        {…}]   [:rf.resource/error         {…}]
[:rf.resource/refresh-error {…}]   [:rf.resource/has-data?     {…}]
[:rf.resource/previous-data {…}]
```

Subscriptions never fetch. A sub is a pure passive read; it resolves scope or raises, never reads global, never returns a silent `:idle`.

## Owners and causes — liveness vs. explanation

TanStack and RTK talk about *observers*. re-frame2 talks about **owners** (liveness leases) and **causes** (trace metadata), and never blurs them.

**Owners** answer: should invalidation refetch now or only mark stale? Should the entry survive GC? What should route-leave release? Every owner names *who is authoritative for releasing it*, so a lease can't silently outlive the thing it represents:

| Owner kind | Form | Released by |
|---|---|---|
| **Route** | `[:route route-id nav-token]` | Routing, on route-leave or nav-token supersession. |
| **Machine** | `[:machine machine-id instance-id]` | Actor destroy — when the owning machine instance stops. |
| **SSR** | `[:ssr request-id nav-token]` | Request teardown — never survives as a live client lease. |
| **App / lease** | `[:lease ...]` and other app-minted kinds | **The app** — an event that mints a lease MUST have a matching `:rf.resource/release-owner` path. |

Route owners **must** include the nav-token (`[:route :route/article]` alone isn't precise — the same route re-enters with different params). Ordinary event ids should usually be *causes*, not owners: a manual refresh or a button click that just wants fresh data omits `:owner` and supplies only `:cause`. An app-minted lease with no observed release path is a slow leak — Xray surfaces an **orphaned-owner lint** for exactly that.

**Causes** are trace/diagnostic metadata. They answer "why did this happen?" without changing liveness, GC, or refetch:

```clojure
[:route-entry :route/article nav-token]   [:manual :article/refresh]
[:invalidate {:tags #{[:article "welcome"]}}]   :focus   :reconnect   :hydration
```

Ensure/refetch accept both `:owner` and `:cause`. And — importantly — **Xray never becomes an owner by observing.** Opening a devtool must not pin a resource, refetch it, or extend its GC. Inspection is free of side effects.

## Cache home and the work ledger

The cache lives **only** at `:rf.runtime/resources` inside the runtime-db partition (`:rf.db/runtime`, [chapter 02](02-app-db.md) / [chapter 21](21-dynamic-model.md)) — never in `app-db`. An ordinary `:db` event handler cannot accidentally wipe it. You read it through the subs and accessors; you never hand-edit the slice.

There's a second, deliberately separate idea here that the docs and examples teach as a pair: **cache *entries* are not the same as *work attempts*.**

- **`:rf.runtime/resources`** stores durable cache *facts* — the entry's status, data, errors, timestamps, tags, owners, and a pointer to its current work id.
- **`:rf.runtime/work-ledger`** stores serializable *attempt* records — one per in-flight fetch: its status (`:queued` / `:running` / `:abort-requested` / terminal), owners, causes, generation, deadline, and outcome.
- **Host handles** — AbortControllers, timeout/poll handles, transport promises — live in side tables keyed by frame and work id, and are **never serialized**. They are not durable state.

Why split them? Because the resource FSM (`:idle → :loading → :loaded → :fetching → ...`) describes *cache-entry status*, while the ledger describes *the attempt currently moving that entry*. Overloading `:status` with host-handle state would conflate "what do I show the user?" with "what's the network doing?" — two questions with different lifetimes. The ledger is also named neutrally on purpose: resources are its first writer, but later slices extend it to timers, streams, and machine async work.

This split powers the **correctness rule** that makes resources safe under races, route changes, logout, and time-travel:

> **Cancellation is opportunistic; stale-reply suppression is mandatory.**

When an owner exits, a scope is cleared, a route is superseded, or a newer generation starts, the runtime *may* abort the host handle if it exists and can be cancelled. If the host *can't* cancel it — the request is already on the wire — the ledger and resource **generation checks still suppress the late reply**. A stale reply can never mutate a newer entry. Abort saves bandwidth; suppression preserves correctness. You get correctness whether or not abort was possible.

(Terminal ledger rows are pruned on the linked entry's next successful transition, with a small bounded tail kept for Xray's recent-races view — so the ledger doesn't grow unbounded across SSR, hydration, and every epoch snapshot.)

## Ensure and refetch

Resource events take **map payloads**, not positional argument vectors:

```clojure
[:rf.resource/ensure
 {:resource :article/by-slug
  :scope    [:rf.scope/session {:user-id "u-42" :tenant-id "acme"}]
  :params   {:slug "welcome"}
  :owner    [:route :route/article nav-token]
  :cause    [:route-entry :route/article nav-token]}]

[:rf.resource/refetch
 {:resource :article/by-slug
  :scope    [:rf.scope/session {:user-id "u-42" :tenant-id "acme"}]
  :params   {:slug "welcome"}
  :cause    [:manual :article/refresh]}]      ;; a manual refresh is a cause, not an owner
```

The race semantics are normative and worth knowing:

- **`ensure` of an entry that is already `:loaded` and still fresh-by-policy is a cache hit** — it serves the cached value, attaches any supplied owner, and starts **no** fetch. It neither dedupes (there's no in-flight work to join) nor refetches; it emits a `:rf.resource/cache-hit` trace and, for a blocking route slot, settles the navigation immediately (a route blocked on a fresh resource never hangs). This is the fresh-skip path that lets [§Route-driven loading](#route-driven-loading) say "if hydrated data is already fresh, it doesn't block at all." A *stale* `:loaded` entry is not a cache hit — its next `ensure` refetches per policy — and a `refetch` is never a cache hit (it always forces a new generation).
- **`ensure` while the same scoped key is already in flight** *joins* the existing work — it attaches any supplied owner to both the entry and the ledger row, records the new cause, and emits a dedupe trace. Two screens asking for the same article fire one request.
- **`refetch` may force a new generation.** If a prior request is still in flight, the old work record is marked superseded, aborted when possible, otherwise suppressed by work-id + generation.
- **Owner release while in flight** aborts only when *no remaining owner* needs the work. A shared request isn't cancelled just because one of its owners went away.
- **Stale/GC timers are advisory.** A timer handler re-reads the current entry, owners, and generation before writing — a newer event may already have refreshed, invalidated, or removed it. Freshness is computed from durable timestamps, not from trusting a timer fired on time.

Freshness and lifetime are policy, declared on the resource. Two optional `reg-resource` keys arm the timers above:

```clojure
(rf/reg-resource :article/by-slug
  {:params-schema  [:map [:slug :string]]
   :scope          :rf.scope/global
   :stale-after-ms 60000        ;; after 60s a :loaded entry is stale — the next ensure refetches
   :gc-after-ms    300000       ;; after 5min with no active owner the entry is GC-eligible
   :request        (fn [{:keys [slug]} _ctx]
                     {:request {:method :get :url (str "/api/articles/" slug)} :decode :json})})
```

`:stale-after-ms` is what makes a fresh ensure a cache hit and a later one a refetch; `:gc-after-ms` reclaims an inactive entry. Omit them and the entry is fresh until explicitly invalidated and never GC'd on a timer — fine for small, long-lived caches; set them for data that ages.

The lowering to transport is the framework's job. Internally an ensure creates or joins a work-ledger record, then lowers to `:rf.http/managed` — supplying the `:request-id`, `:on-success`, and `:on-failure` itself from the scoped key and generation. Your `:request` fn returns a [Spec 014](../../spec/014-HTTPRequests.md) args map (the nested `:request`, `:decode`, `:retry`, sensitivity metadata) but **must not** supply `:request-id` / `:on-success` / `:on-failure` — those are how stale-suppression is wired, and an app that bypasses them is rejected.

## Invalidation by tag

Resources tag their data, and invalidation is by tag:

```clojure
;; A resource produces tags from its data:
(rf/reg-resource :article/by-slug
  {... :tags (fn [{:keys [slug]} _data] #{[:article slug]})})

;; A cause invalidates them — an explicit dispatch, or (more commonly) a mutation's
;; success-time :invalidates (see the Mutations section below):
[:rf.resource/invalidate-tags
 {:scope [:rf.scope/session {:user-id "u-42" :tenant-id "acme"}]
  :tags  #{[:article "welcome"] [:article-list]}
  :cause [:mutation :article/save mutation-id]}]
```

The algorithm: find entries whose tags intersect; mark them stale; refetch entries with active owners; leave inactive entries stale or GC-eligible; emit a decision summary plus per-entry detail (so a broad-tag storm shows in Xray without flooding the trace). On a successful load the entry's tag index is *replaced* with the new data's tags, so stale list/detail relationships don't keep receiving invalidations after the data changed.

**Scoped invalidation is the default** — a cross-scope invalidation must opt in explicitly and is visible in Xray, because it can refetch or stale data across multiple users, tenants, or SSR requests.

## Mutations — the causal write

Reads are half the story. A real app also *writes* — save the article, add the comment, toggle the favourite — and a write almost always means "and now the cached read of that thing is wrong." A **mutation** is the causal-write counterpart of a resource: a named write to remote state that, on success, invalidates (or patches, or populates) the cached resource reads it affected. You stop hand-wiring "POST, then on success dispatch an invalidate" on every form; you declare the write→invalidate→refetch loop once.

If a resource is "a sub you read and a cause you fire," a mutation is "**a cause you fire and an instance you watch**."

### Register the write, declare what it invalidates

```clojure
(rf/reg-mutation :article/save
  {:params-schema :app/article                ;; REQUIRED — validates + canonicalizes params
   :request                                    ;; REQUIRED — a Spec 014 managed-HTTP write
   (fn [{:keys [slug] :as article} _ctx]
     {:request {:method :put :url (str "/api/articles/" slug) :body article}
      :decode  :app/article})
   :scope        :rf.scope/global              ;; the cache scope invalidation/patch defaults to
   :invalidates  (fn [{:keys [slug]} _result]  ;; the tags the write makes stale on success
                   #{[:article slug] [:article-list]})})
```

`:invalidates` is the heart of it: on success the runtime invalidates those tags through the *same* `:rf.resource/invalidate-tags` machinery the previous section described — scoped by default, owner-aware (entries with active owners refetch; inactive ones go stale or GC-eligible). The detail and list views re-render with fresh data with no further wiring. The same `:tags` your resource produced from its data are the join key — that's why tagging is set up before mutations.

The `:request` follows the resource rule exactly: it returns a [Spec 014](../../spec/014-HTTPRequests.md) args map and **must not** supply `:request-id` / `:on-success` / `:on-failure` — the runtime owns reply addressing from the mutation instance + generation, which is how stale-suppression is wired. Note the **reads-retry / writes-don't** discipline carries over: write retries are **opt-in** — a mutation arms `:retry` only when its `:request` declares it ([chapter 10](10-http.md#retry-backoff-and-the-discipline-of-not-retrying)). Re-submitting a `PUT` because a reply was merely slow is exactly the double-charge bug you don't want.

### Fire the write, watch the instance

Run a mutation with `:rf.mutation/execute`, and observe it through passive `:rf.mutation/*` subs keyed by an **instance** id — *not* the mutation id:

```clojure
;; The submit handler (or the view) fires the write:
(rf/dispatch [:rf.mutation/execute
              {:mutation :article/save
               :params   article
               :instance :form/save-1          ;; caller-supplied (or generated) instance id
               :cause    [:form-submit :article/save]}])

;; The form watches that instance passively — no execution in a sub:
(rf/reg-view save-button [article]
  (let [{:keys [pending? error?]} @(rf/subscribe [:rf.mutation/state {:instance :form/save-1}])]
    [:button {:disabled pending?
              :on-click #(rf/dispatch [:rf.mutation/execute
                                       {:mutation :article/save :params article
                                        :instance :form/save-1
                                        :cause [:form-submit :article/save]}])}
     (cond pending? "Saving…" error? "Retry save" :else "Save")]))
```

The full instance view-model and sub family:

```clojure
[:rf.mutation/state    {:instance :form/save-1}]   ;; {:status :result :error :pending? :success? :error? :settled?}
[:rf.mutation/status   {:instance :form/save-1}]
[:rf.mutation/pending? {:instance :form/save-1}]
[:rf.mutation/result   {:instance :form/save-1}]
[:rf.mutation/error    {:instance :form/save-1}]

[:rf.mutation/clear    {:instance :form/save-1}]   ;; the causal instance reset (NOT clear-mutation)
```

The instance-keyed model is load-bearing: **two concurrent submissions of the same mutation never clobber each other.** Fire `:comment/add` twice (instances `:c-1` and `:c-2`) and each keeps its own `:pending` / `:success` / `:error` row. A caller-supplied instance id makes the relationship between a form and its submission explicit; an omitted instance id is generated (and closes over the monotone generation, so concurrent generated submissions still differ). `:rf.mutation/clear` is the **causal** reset of a runtime instance (best-effort aborting in-flight work) — distinct from `clear-mutation`, which is the registration-lifecycle removal of the mutation definition itself, not a form reset.

### Continue the workflow with `:reply-to`

Watching the instance from a view is right for *rendering* — the button reads `:pending?` and disables itself. But a write usually also has to **drive workflow**: save the user, navigate to the new article's slug, clear the editor, show a toast. Those are *causes*, not renders, and they should not live in a component lifecycle reaction watching mutation state. They belong on the event tape.

`:rf.mutation/execute` takes an optional call-site **`:reply-to`** event target. When the runtime accepts the mutation reply as current, it dispatches that target with one **reply map** appended as the final argument:

```clojure
(rf/reg-event-fx :settings/save
  (fn [_ [_ form]]
    {:fx [[:dispatch [:rf.mutation/execute
                      {:mutation :realworld/update-user
                       :params   form
                       :instance [:settings/save]
                       :reply-to [:settings/save-replied]}]]]}))

(rf/reg-event-fx :settings/save-replied
  (fn [{:keys [db]} [_ {:keys [status value error]}]]
    (case status
      :ok        {:db (assoc db :auth/user (:user value))
                  :fx [[:dispatch [:toast/show "Settings saved"]]]}
      :error     {:db (assoc db :settings/error error)}
      :cancelled {:db (assoc db :settings/saving? false)})))   ;; accepted cancellation also lands here
```

No view watcher. The accepted reply becomes the next causal event — folded through the interceptor chain, the trace tape, and replay like any other event. (It is **not** a callback: a callback that returned effects would mint effects outside the event tape, outside replay, outside frame causality. The continuation is a *causal event target*.)

The reply map is one canonical **uniform reply envelope** ([chapter 10](10-http.md#the-uniform-reply-envelope), normative home [`spec/Managed-Effects.md` §The uniform reply envelope](../../spec/Managed-Effects.md#the-uniform-reply-envelope)) — `:reply-to` is the mutation's public spelling of the envelope's reply target, so it carries no private subset. Its `:status` is drawn from the closed EP-0011 taxonomy — `:ok` / `:partial` / `:error` / `:cancelled` / `:stale` — and the handler reads the mutation-specific facts directly: `:status`, `:mutation`, `:params`, `:instance`, `:scope`, `:value` / `:error`, `:affected-keys` (the resource keys the reply populated / patched / invalidated), `:work/id`, `:rf.frame/id`, `:completed-at`, and `:cause`. Static call-site args are preserved — `:reply-to [:toast/after-save {:kind :article}]` dispatches `[:toast/after-save {:kind :article} reply]`.

**The delivery rule:** `:reply-to` fires for any **accepted terminal reply**. Of the closed enum, a mutation currently emits the accepted terminal statuses — `:ok`, `:error`, and accepted terminal `:cancelled` (plus `:partial` if/when a mutation family produces partial-success data) — which is why the `case` above branches on exactly those. `:stale` is different: a stale or superseded reply (a re-execute under the same instance, an `:rf.mutation/clear`) is suppressed before delivery and **never dispatches the app continuation** — that mandatory stale suppression is the envelope's correctness boundary, shared by every managed async family, not a mutation-local rule. The handler branches on `:status`; the runtime guarantees it only ever sees an accepted one.

**Phase order is deterministic.** The continuation runs *after* the cache consequences and instance settlement, so a handler reached by `:reply-to` observes a settled world:

1. resolve canonical params + mutation scope;
2. issue the managed request under runtime-owned reply addressing;
3. accept or stale-suppress the host reply;
4. apply success-time cache consequences (`:patches`, `:populates`, `:invalidates`, removes);
5. settle mutation instance + work-ledger state;
6. dispatch the `:reply-to` continuation, if present.

> **The doctrine, in one line: `:reply-to` is for *workflow*; `:populates` / `:patches` / `:invalidates` are for *cache*.** A continuation updates app-db, navigates, toasts. A cache consequence updates resource entries. Don't reach for `:reply-to` to invalidate a tag (the registration already declared that, scoped and owner-aware), and don't try to express navigation as a cache consequence. Registration-level `:reply-to` is deliberately *not* added — an invariant workflow continuation is spelled by every call site passing the same target, and a workflow target hidden inside the remote-write definition would hide app behaviour.

### The write→invalidate→refetch loop, end to end

Putting the two halves together — this is the write→invalidate→refetch loop, end to end:

```clojure
;; READ: the article detail, tagged by slug.
(rf/reg-resource :article/by-slug
  {:params-schema [:map [:slug :string]]
   :scope         :rf.scope/global
   :request (fn [{:keys [slug]} _ctx]
              {:request {:method :get :url (str "/api/articles/" slug)} :decode :app/article})
   :tags    (fn [{:keys [slug]} _data] #{[:article slug]})})

;; READ: the article list, tagged so a save can stale it too.
(rf/reg-resource :article/list
  {:params-schema [:map]
   :scope         :rf.scope/global
   :request (fn [_ _ctx] {:request {:method :get :url "/api/articles"} :decode :app/article-list})
   :tags    (fn [_ articles] (into #{[:article-list]} (map (fn [a] [:article (:slug a)]) articles)))})

;; WRITE: saving invalidates both the edited article AND the list, by tag.
(rf/reg-mutation :article/save
  {:params-schema :app/article
   :request (fn [{:keys [slug] :as article} _ctx]
              {:request {:method :put :url (str "/api/articles/" slug) :body article} :decode :app/article})
   :invalidates (fn [{:keys [slug]} _result] #{[:article slug] [:article-list]})})

;; The view: read passively, fire the write, watch the instance.
(rf/reg-view article-editor [article]
  (let [save @(rf/subscribe [:rf.mutation/state {:instance :form/article-save}])]
    [:<>
     [editor-fields article]
     [:button {:disabled (:pending? save)
               :on-click #(rf/dispatch [:rf.mutation/execute
                                        {:mutation :article/save :params article
                                         :instance :form/article-save
                                         :cause [:form-submit :article/save]}])}
      (if (:pending? save) "Saving…" "Save")]
     (when (:error? save) [save-error (:error save)])]))
```

When the save succeeds, the runtime invalidates `#{[:article slug] [:article-list]}`. Any mounted detail view (owned by its route) and the list (if a route still owns it) refetch automatically; inactive entries are simply marked stale and refetch the next time something ensures them. The form never touches `app-db`, never dispatches a manual invalidate, and never re-implements "which reads did this write break?" — the mutation declared that once.

### What a mutation refines beyond a plain managed-HTTP write

You *can* write with an ordinary `:rf.http/managed` event whose success handler dispatches `:rf.resource/invalidate-tags` (that was the only path before mutations landed). A mutation adds, on top of that:

- **Instance-keyed lifecycle** — `:pending?` / `:success?` / `:error?` / `:settled?` per submission, concurrency-safe, with no `app-db` slice to maintain. (A write has no last-known-good, so there's no `:refresh-error` analogue — failure simply settles `:error`.)
- **Stale-suppression on writes** — a superseded reply (a re-execute under the same instance, or an `:rf.mutation/clear`) can never overwrite a newer instance, by the same work-id + generation check resources use.
- **Causal workflow continuation** — call-site `:reply-to` (above) dispatches an app event with the accepted reply map after cache consequences settle, so post-write navigation / session updates / toasts ride the event tape instead of a view-side watcher.
- **Patch / populate before invalidate** — `:patches` and `:populates` ([map-form exact targets](#map-form-exact-targets), keyed by scoped key) update resource entries *before* the success-time invalidation, so a saved article can appear in the cache immediately without waiting for the refetch round-trip. A populated key is an [authoritative load](#populate-is-an-authoritative-load) — it is treated as freshly loaded and is not refetched by the same mutation's invalidation pass.
- **Per-target scoped invalidation** — `:invalidates` can target [different scopes precisely](#per-target-scoped-invalidation) (one write that affects a global fact *and* a session-scoped fact), instead of one resolved scope for every tag.
- **Invalidation timing** — `:invalidate-timing` is explicit: `:after-success` (default), `:before-request`, `:after-failure`, or `:after-settle`.
- **Trace-visible instance ids** — the `:rf.mutation/*` trace family (`started` / `succeeded` / `failed` / `replied` / `cleared` / `stale-suppressed`) carries the instance id, so Xray groups submissions under their mutation and shows the write→invalidate→refetch→continue causality. `succeeded` / `failed` carry the per-descriptor invalidation evidence; `replied` is the `:reply-to` continuation dispatch.

(Optimistic rollback — the snapshot/rollback/reconciliation shape — is reserved in the success trace but **deferred**; until it lands, patch/populate are forward-only.)

> **When managed HTTP is still the right tool: user-visible optimistic *rollback*.** `:patches` / `:populates` are **forward-only** seeds — they make a change appear immediately and let the success-time invalidation reconcile, but there is **no automatic revert on failure** (the snapshot/rollback shape is deferred — see [What's deferred](#whats-deferred)). So when you need a write that flips the UI optimistically *and* rolls the change back if the server rejects it — the favourite toggle that un-flips on a 500, a like-count that decrements again, a positional list re-insert that undoes itself — reach for a plain `:rf.http/managed` write where your own `:on-failure` handler restores the prior `app-db` value. A mutation cannot express that today. The favourite / follow / comment-delete shapes in [`examples/reagent/realworld/`](../../examples/reagent/realworld/) (the managed-HTTP sibling of the resources RealWorld variant) are worked examples of exactly this optimistic-then-rollback pattern; the resources variant ([`examples/reagent/realworld_resources/`](../../examples/reagent/realworld_resources/)) uses forward-only `:populates` for the same toggles and accepts the brief refetch round-trip instead.

### Per-target scoped invalidation

The bare tag-set shorthand invalidates those tags **in the mutation's resolved scope**:

```clojure
:invalidates (fn [{:keys [slug]} _result] #{[:article slug] [:article-list]})
```

That's the common case, and it's all most writes need. But one write can affect facts in **more than one scope** — and tags name *facts* while scopes name *viewers*. The minimal failing case is favourite/unfavourite: it changes the global article-detail and article-list facts *and* the current user's session-scoped feed. "Invalidate `[:feed]` in the mutation's global scope" never reaches a feed entry that lives under a session scope; "invalidate `[:feed]` across every scope" is a blunt cross-user fan-out.

The fix is the **descriptor form** — each target carries its own scope:

```clojure
:invalidates
(fn [{:keys [slug]} _result]
  [{:scope :rf.scope/global
    :tags  #{[:article slug] [:article-list]}}
   {:scope {:from-db :realworld/session}      ;; a named scope resolver, below
    :tags  #{[:feed]}}])
```

A descriptor `:scope` may be `:rf.scope/same` (the default — the mutation's resolved scope), `:rf.scope/global`, a concrete canonical scope value (`[:rf.scope/session {:username "jake"}]`), or a **named scope resolver reference** (`{:from-db :realworld/session}`, resolved against the handler's coeffect db at settle time). Bare shorthand and descriptor form lower to the *same* scoped invalidation engine — the descriptor is just the public data telling the engine which `(tags, scope)` pairs to mark stale.

A `{:from-db …}` reference that resolves **nil** is **fail-closed**: that descriptor produces no invalidation — never an implicit global blast. Xray's mutation-invalidation surface shows the per-descriptor resolved scope and lists any fail-closed `:unresolved` references.

Deliberate broad invalidation (admin tooling, cache-poisoning response, a migration — "invalidate this tag in *every* scope currently holding it") stays available as an explicit audited escape — but it answers a *different* question from a descriptor: a descriptor names scopes the call site already knows, while `:cross-scope?` targets scopes it **cannot** enumerate but the cache can. So **prefer descriptors whenever the scopes are enumerable**; reach for `:cross-scope?` only when they genuinely aren't. The escape is `:cross-scope? true`, and because it can stale or refetch data across users, tenants, story frames, and SSR requests, the runtime **requires** `:cause` evidence (a cross-scope invalidation with no `:cause` is rejected) and records it as a privacy-relevant trace event:

```clojure
;; A migration invalidates a tag wherever it lives — scopes unenumerable at the call site.
{:fx [[:dispatch [:rf.resource/invalidate-tags
                  {:tags         #{[:article-list]}
                   :cross-scope? true
                   :cause        [:migration :article-schema-v2]}]]]}   ;; :cause is required
```

It must never be the default reading of a bare tag.

### Map-form exact targets

`:populates`, `:patches`, and removes address an **exact** cache entry. The canonical source shape is the **target map**:

```clojure
{:resource :realworld/article
 :params   {:slug slug}
 :scope    :rf.scope/global}
```

`:scope` may be concrete, `:rf.scope/same`, `:rf.scope/global`, or a named resolver reference. Populate creates-or-replaces exactly one key; patch updates an existing exact key only (patch does not target tags). The internal storage representation is a scoped-key tuple, but the **map form is the only public input form** — spec, guide, traces, and examples use it.

### Populate is an authoritative load

Some replies carry authoritative resource data — an article save returns the full updated article. `:populates` seeds that into the cache. The rule that makes populate + invalidate coherent: **populate is an authoritative load.** A key populated by an accepted mutation reply becomes loaded, its value becomes current, freshness timers arm as if it had loaded normally — and it is **exempt from immediate refetch by that same mutation's invalidation pass**. So a mutation can populate the article detail *and* invalidate a broad `[:article …]` tag without immediately re-fetching the key it just learned from the write.

The populated value MUST be the resource's **stored shape** — the same value a successful load produces (the full decoded envelope), not a sub-projection of the reply — so a populated entry reads identically to a fetched one. If the reply is *partial* relative to the full GET, opt into a same-mutation refetch per descriptor:

```clojure
:invalidates
[{:scope :rf.scope/global
  :tags  #{[:article slug]}
  :refetch-populated? true}]
```

The key may still be invalidated/refetched later by other events, focus/reconnect policy, or explicit refetch — `:refetch-populated?` only governs *this* mutation's own invalidation pass.

### Request decoration lives in managed HTTP, not in every resource

Resources and mutations lower through Spec 014 managed HTTP. A resource's `:request` fn describes the **domain** request — method, URL, params, body, decode. Cross-cutting transport concerns — auth headers, tracing headers, common base URLs, tenant headers, default read-retry policy — are **frame/application managed-HTTP policy**, not something to copy into every resource. The seam is the managed-HTTP interceptor/defaults layer ([chapter 10](10-http.md)):

```clojure
(rf/reg-http-interceptor :realworld/auth
  {:before (fn [ctx]
             (let [token (some-> (rf/app-db-value (:frame ctx)) :auth :token)]
               (cond-> ctx
                 token (assoc-in [:request :headers "Authorization"] (str "Token " token)))))})

(rf/reg-resource :realworld/current-user
  {:params-schema [:map]
   :scope         {:from-db :realworld/session}   ;; /user is session-dependent — never global
   :request       (fn [_params _ctx] {:request {:method :get :url "/user"} :decode :json})})
```

Registered once per frame, the interceptor decorates *every* managed request the frame issues — resource reads, mutations, and plain managed calls alike — so `:realworld/current-user` needs no per-resource opt-in. It reads the token from `(:frame ctx)` (carried-frame-correct, not an ambient db) and returns `ctx` unchanged when there's no token. Two riders: default retry policy should be **read-focused** (retrying writes can duplicate side effects, so mutation retry defaults stay conservative), and traces should report *that* an auth interceptor applied, never the bearer token value.

## Route-driven loading

The cleanest way to load a page's server state is to declare it on the route. `:resources` is route metadata:

```clojure
(rf/reg-route :route/article
  {:path "/articles/:slug"
   :params [:map [:slug :string]]
   :resources
   [{:resource  :article/by-slug
     :params    (fn [route] {:slug (get-in route [:params :slug])})
     :scope     (fn [_route ctx] (:current-session-scope ctx))
     :blocking? true}

    {:resource  :comments/list
     :params    (fn [route] {:slug (get-in route [:params :slug])})
     :when      (fn [route _ctx] (some? (get-in route [:params :slug])))
     :blocking? false
     :keep-previous? true}]})
```

On route entry, the runtime resolves the route and nav-token, evaluates `:when` predicates, computes and validates scopes and params, marks each resource active with owner `[:route route-id nav-token]`, and ensures each with cause `[:route-entry route-id nav-token]`. Blocking resources are tracked under the nav-token; non-blocking ones fetch in the background. On route leave or superseded navigation, route-owned resources are released by token, in-flight work is aborted only when no other owner needs it, and stale replies are suppressed by generation even when abort is unavailable.

`blocking?` keeps the route transition in a loading/pending state and gives SSR a wait point before render. It does **not** have to block URL commit or prevent a client skeleton — and if hydrated data is already fresh, it doesn't block at all. `:on-match` ([chapter 19](19-routing.md)) remains canonical for arbitrary route-entry work; `:resources` is declarative server-state metadata layered beside it, not a second router.

Routes aren't required — an app can use resources entirely from events and machines (with explicit owners and a matching release path). It then gets canonical identity, stale/fresh policy, dedupe, invalidation, GC, passive subscriptions, and Xray visibility — just not route ownership, route-leave release, transition blocking, or SSR route preload.

## Focus and reconnect revalidation

The same staleness instinct every query library ships — refetch active stale data when the user returns to the tab or the network comes back — is built in, and it's expressed the re-frame2 way: as **causal events**, not as a subscription that fetches. A hidden tab can delay stale/GC timers without corrupting correctness; on focus or reconnect, a scan refetches the entries that are both *stale* and *still owned*, so the user sees current data when they come back without a refetch storm of things nothing is watching.

You opt in per frame at app boot:

```clojure
(ns example.app
  (:require [re-frame.core :as rf]
            [re-frame.resources]))

;; After the frame exists (e.g. at boot, after mounting the root app):
(rf/install-revalidation-listeners! frame-id)
```

That installs host `window` focus / `online` listeners for `frame-id`. On window focus / tab return the listener dispatches `[:rf.resource/window-focused]` at the frame; on network reconnect it dispatches `[:rf.resource/network-reconnected]`. **You never dispatch those events yourself** — they're the host-driven signals, and dispatching them by hand is not a supported surface. Each one scans the frame's **active-owner stale** entries and background-refetches them with cause `:focus` / `:reconnect`:

- It refetches only entries that are **both stale and have a live owner** — a mounted route or machine that's actually displaying the data. Fresh entries and owner-free entries are left alone.
- The scan is a **cause, never an owner.** It creates no liveness, pins nothing alive, and extends no GC; generation + stale-suppression protect any late replies exactly as for an ordinary refetch.
- It emits one `:rf.resource/revalidate-scan` summary trace (so a broad tab-return doesn't flood the trace), and the per-entry refetches ride their ordinary refetch traces — Xray surfaces the scan and what it refetched.

`install-revalidation-listeners!` is idempotent (a re-install replaces the frame's listeners, so it's hot-reload safe) and **CLJS-only** — the JVM/SSR arm is a no-op, since there's no DOM to listen on. The listeners are cancelled automatically on frame destroy; `(rf/remove-revalidation-listeners! frame-id)` tears them down explicitly for test isolation or single-page hosts that rotate which frame owns revalidation.

Because revalidation is just a refetch from a cause, nothing else changes: the same stale/fresh policy decides whether a scanned entry is eligible, the same dedupe joins concurrent work, and the same generation rule suppresses replies that arrive after the data moved on.

## SSR and hydration

SSR uses request-local frames — a process-global resource cache would leak data between users. On the server: resolve the route, compute its resources, enqueue blocking ensures, drain until the blocking resources for the current nav-token settle, render with the settled state, then serialize **only the allowed resource projection** (recording which entries were serialized, redacted, omitted, fresh, stale, or marked refetch-on-client). Blocking SSR resources need a timeout policy — a timeout settles the resource as a structured first-load failure for that frame and lets the renderer choose error markup, a skeleton, or a fallback, rather than hanging the request.

On the client, hydration installs that projection into the target frame's `:rf.runtime/resources` slice, preserves hydrated entries, **avoids a duplicate immediate fetch for fresh entries**, and background-refetches stale ones by policy. Hydration never crosses scopes: request-local SSR frames and serialized scopes must agree before the client treats hydrated data as usable. The wire payload carries only `:entries` — the `:tag-index` and `:owner-index` are *recomputed* from the entries on install, so a stale or partial index can never outlive the data it describes. (See [chapter 20](20-server-side.md) for the SSR mental model and [chapter 23](23-privacy-and-large-things.md) for the redaction walker that classifies sensitive/large params, scopes, and data.)

## Restore and time-travel

Resources are runtime-managed read models over an in-flight ledger, so time-travel has to reconcile a freshly-restored *durable snapshot* against the *live transient world* — host handles still attached to the pre-restore timeline, and network replies already on the wire the runtime can't recall. The governing principle is the **anti-recycling rule**: a restored value must never let a stale generation be mistaken for a live one.

The mechanism is the same one routing uses for nav-tokens, generalized:

- **The generation allocator is monotonic and host-side; it never rewinds.** After a restore, the next generation strictly exceeds anything a pre-restore in-flight reply could carry — so a stale reply's generation can never match a live entry. Collision is structurally impossible. (This is deliberately the *opposite* discipline from machine spawn-ids, which never escape the frame and so may be snapshot-local; a generation governs acceptance of a reply that *has* escaped the frame.)
- **In-flight work doesn't survive as live work.** A restored non-terminal ledger row is *dangling*: its work-id can never re-match a live entry, its host slot is cleared, and the linked entry settles to its last stable status (`:loaded` with data, `:error` for a failed first load, `:idle` if never loaded) — never stranded in `:loading` pointing at a vanished request.
- **Freshness is lazy, not an eager refetch storm.** A restored epoch double-fetches nothing: entries render their data immediately and refetch only on the next ensure from a live owner.
- **Owners revive or orphan by kind** — machine owners revive (machine liveness is a pure function of the snapshot), route owners revive only if the restored routing names the same live nav-token, SSR owners orphan.
- **Indexes are recomputed from entries, never trusted from the snapshot** — the same rule SSR hydration uses.

The payoff: time-travel over an app full of server-state is coherent, with no resurrected requests and no refetch storm.

## Xray

Resources are a trace/accessor contract, not just panel UI. Xray exposes a static resource registry (ids, schemas, request summary, stale/GC policy, tag producer, scope resolver, sensitivity, declaring routes); a **named scope resolver registry** (each `reg-resource-scope` resolver's id + declared inputs + whole-db cost flag); a live resource-instance table per frame (key, scope, status, timestamps, generation, owners, tags, errors, data summary, GC eligibility); a live work-ledger table per frame; a route/resource graph; a lifecycle timeline; an invalidation graph; a **scope resolution timeline** (each `:rf.resource/scope-resolved` event — which resolver ran, the resolved scope, and the fail-closed nil evidence); a **mutation continuations + scoped invalidation** view (the per-descriptor resolved scopes + fail-closed unresolved references off the mutation settlement traces, and the `:reply-to` continuation dispatches); a cache-growth view; and the **scope audit surface** — the standing enumeration of every `:rf.scope/global` resource. Two lints ride it: a **scope-mismatch lint** (an entry under scope A while a live sub reads the same key under scope B and gets a permanent `:idle`) and the **orphaned-owner lint**. Tool accessors prefer summaries over raw values — an agent usually needs "this route owns `:article/by-slug`, it's stale, the last refresh failed 503," not the full article body — and params/scopes get the same privacy/size elision as data. (The Xray docs cover the panels in depth.)

## What's deferred

The read-resource MVP, mutations (the [Mutations](#mutations--the-causal-write) section above), and focus/reconnect revalidation (the [Focus and reconnect revalidation](#focus-and-reconnect-revalidation) section above) are landed — that's the **first public-beta gate, now complete.** Some surfaces named in this chapter still land with later slices:

- **Optimistic rollback** — the snapshot / rollback / reconciliation shape is *reserved* in the mutation success trace but not yet implemented. Until it lands, mutation `:patches` / `:populates` are forward-only (no automatic rollback on failure). A write that needs **user-visible optimistic rollback** (flip the UI, revert on server rejection) stays a plain `:rf.http/managed` write with an `:on-failure` handler that restores the prior `app-db` value — see [the boundary note under Mutations](#what-a-mutation-refines-beyond-a-plain-managed-http-write).
- **GraphQL** — a deferred later phase, out of this contract. `:rf.http/managed` is the single built-in transport for both reads and writes; the lifecycle is kept transport-neutral so a GraphQL transport can plug in later without weakening the core semantics.
- **Later still** — a generic transport-extension protocol, polling/interval revalidation, infinite resources, normalized entity caches, automatic graph-derived invalidation, subscription-driven fetching, offline persistence, and cross-tab broadcast.

The read-resource MVP, mutations, and focus/reconnect revalidation together are the first public-beta gate — a complete, locked contract on its own. The later slices above extend it; they don't change its semantics.

## What resources actually buy you

Pulling server-state into the registry isn't a stylistic flourish — it's the difference between a query library that lives *next to* your app and server-state that's *just another piece of state your app already knows how to handle*. Views stay passive; fetches stay causal; the cache lives in a known partition; the leak boundary fails closed instead of leaking silently; races and route changes and logout and time-travel are handled by one generation-monotonicity rule; and every cache decision is data an AI agent or devtool can enumerate without per-app reinvention. That last point is the whole bet: a uniform contract is what lets Xray, SSR projection, restore, and the AI-Audit reason about server state across every re-frame2 app the same way.

## Cross-references

- [Spec 016 — Resources](../../spec/016-Resources.md) — the normative contract.
- [EP-0003 — Resource Queries](../EP/EP-0003-resource-queries.md) — rationale, the TanStack / RTK / SWR / `re-frame-query` benchmark, the slice plan, and the deferred GraphQL phase.
- [10 - HTTP](10-http.md) — the `:rf.http/managed` transport and the `:rf.http/*` failure taxonomy the error envelopes carry.
- [19 - Routing](19-routing.md) — `:resources` route metadata and nav-token ownership.
- [20 - Server side](20-server-side.md) — the SSR mental model resource hydration rides.
- [12 - Machines](12-machines.md) — machine-owned resources and actor-destroy release.
- [02 - app-db](02-app-db.md) / [21 - Runtime model](21-dynamic-model.md) — the runtime-db partition the cache lives in.
- [23 - Privacy and large data](23-privacy-and-large-things.md) — the elision walker that redacts sensitive/large params, scopes, and data.
- [Migration: re-frame-query → resources](../../migration/from-re-frame-v1/re-frame-query-to-resources.md) — moving an app off `shipclojure/re-frame-query` or a hand-rolled Pattern-RemoteData cache.
