# Pattern — Resources

Declarative server-state: a **named, cached read** of remote state that views read passively and route-entry / events / machines *cause* to fetch. `reg-resource` registers it; the framework owns identity, cache scope, staleness, dedupe, invalidation, GC, in-flight ownership, and SSR hydration — so the app stops re-implementing that bookkeeping per feature. Write counterpart — `reg-mutation` and the whole mutation surface — lives in [`resources-mutations.md`](resources-mutations.md).

> **Mental-model anchor:** this is re-frame2's answer to **TanStack Query / RTK Query / SWR** — re-expressed in the re-frame2 model. Map that intuition onto the surface below, then note the **divergences** flagged inline:
>
> | TanStack Query | re-frame2 resources | The divergence |
> |---|---|---|
> | `useQuery({ queryKey, queryFn })` in a component | `reg-resource` registers; a route/event *ensures*; a view *subscribes* | Views are **passive reads** — they never trigger a fetch. Fetching is **causal** (route entry, event, machine), never a render side effect. |
> | the `queryKey` array | `[scope resource-id params]` triple | **Scope is mandatory and fail-closed** — the tenant / user / locale / impersonation leak boundary is declared, not inferred. There is no silent shared cache. |
> | active observers (a mounted component keeps data alive) | **active owners** (route / machine / lease) vs **causes** (trace metadata) | Liveness is **explicit** (an owner with a release path), decoupled from rendering. A view mounting does not pin the cache. |
> | `select` option | an ordinary `reg-sub` over `[:rf.resource/data …]` | No `:select` key — projections are subscriptions (EP-0004 parametric inputs). |
> | `queryClient.invalidateQueries` | `[:rf.resource/invalidate-tags …]` (scoped) | Invalidation is **data** an event dispatches, **scoped by default** — and a mutation can invalidate facts in *different* scopes precisely with per-target **descriptors** (`{:scope … :tags …}`); visible in trace/Xray. |
> | `useMutation` | `reg-mutation` + `[:rf.mutation/execute …]` (see [`resources-mutations.md`](resources-mutations.md)) | Keyed by **instance id** so concurrent submissions never clobber; success patches/populates entries then invalidates tags. |
> | `useMutation({ onSuccess })` callback | call-site **`:reply-to`** on `[:rf.mutation/execute …]` | The continuation is a **causal event target**, not a callback — the runtime dispatches your event with a reply map after cache consequences settle, so it lands on the event tape (replayable, traced, interceptor-visible). Cache effects stay declarative on `reg-mutation`; **`:reply-to` is for app workflow**. |

**Optional capability — `day8/re-frame2-resources` (Spec 016).** Resources is a post-v1 optional artefact. An app that does not require it expresses server state with [Pattern-RemoteData](remote-data.md) + managed HTTP directly — see §When to use vs plain managed HTTP. Everything below assumes the artefact is on the classpath; `(rf/feature-loaded? :resources)` answers whether it is.

## When to load

The prompt mentions: a **server-state cache** with freshness / staleness / TTL, "TanStack Query / React-Query / SWR / RTK Query in re-frame2", a fetch that several views read, cache **invalidation** after a write, "refetch on focus / reconnect", "don't refetch if fresh", a reusable **session / tenant / account scope** (`reg-resource-scope`), route-driven data loading, auth headers / retry for resource requests, or "stop hand-rolling the loading/error/refetch bookkeeping". Also load when choosing between a resource and a plain Pattern-RemoteData slice (§When to use vs plain managed HTTP). For the **write** side — a mutation that updates cached reads, post-write workflow (`:reply-to`), mixed-scope invalidation descriptors, or optimistic updates — load [`resources-mutations.md`](resources-mutations.md).

## The shape

A **resource instance** is identified by a **scoped resource key** — the triple `[canonical-scope resource-id canonical-params]` (scope = leak boundary; params = the read within a scope). Both run through re-frame2's one canonical EDN identity rule (`CEDN-1`): the **canonical EDN value is the authoritative identity** — equal facts produce the same key across CLJ/CLJS hosts, map key order never affects it, a present `nil` param differs from an absent one. **Fail-closed**: a param outside the portable EDN domain (a function, host `Date`, DOM node, float) is rejected with `:rf.error/non-edn-identity`, not hashed by object identity — coerce host values to portable EDN (e.g. a `#inst`) at the boundary first. Any digest is an **optional derived projection** for size-constrained surfaces, never the authoritative identity. Shared contract: [`../references/cross-cutting/path-and-identity.md`](../references/cross-cutting/path-and-identity.md).

Three roles never blur:

- **Views are passive.** A `[:rf/resource …]` subscription reads cached state; it never fetches.
- **Causes explain why work happened.** `[:route-entry …]`, `[:manual …]`, `:focus`, `:reconnect` — trace/diagnostic metadata that does not change liveness.
- **Owners keep a resource alive** (a liveness lease) and have a matching **release path**. Route owners carry the nav-token; machine owners are released on actor-destroy; app-minted leases need an explicit `:rf.resource/release-owner`.

## Canonical declaration

```clojure
(rf/reg-resource
  :article/by-slug
  {:doc            "Article detail by slug."
   :params-schema  [:map [:slug :string]]          ;; REQUIRED — validates + canonicalizes params
   :data-schema    :app/article                    ;; validates decoded data
   :scope          :rf.scope/global                 ;; REQUIRED — see §Scope is mandatory
   :stale-after-ms 60000                            ;; fresh window; after it, an ensure refetches
   :gc-after-ms    300000                           ;; inactive (no owner) entries GC'd after this
   :tags           (fn [{:keys [slug]} _data] #{[:article slug]})}  ;; invalidation tags

  ;; REQUIRED request fn (third positional arg) — returns a Spec 014 managed-HTTP args map
  (fn [{:keys [slug]} _ctx]
    {:request {:method :get :url (str "/api/articles/" slug)}
     :decode  :app/article}))
```

The request fn returns a managed-HTTP args map but MUST NOT supply `:request-id` / `:on-success` / `:on-failure` — the runtime owns reply addressing and stale suppression (those are rejected at registration / dispatch).

### Read it (passive subscriptions)

```clojure
;; In a reg-view — `subscribe` is reg-view's injected frame-aware local; the
;; scope + params name the same instance the owner ensured under.
(let [state @(subscribe [:rf/resource
                         {:resource :article/by-slug
                          :scope    :rf.scope/global
                          :params   {:slug slug}}])]
  ;; state => {:status :loaded :data {…} :has-data? true :fetching? false :stale? false …}
  ...)
```

Sibling subs project single facts: `:rf.resource/data`, `:rf.resource/status`, `:rf.resource/loading?`, `:rf.resource/fetching?`, `:rf.resource/stale?`, `:rf.resource/error`, `:rf.resource/refresh-error`, `:rf.resource/has-data?`, `:rf.resource/previous-data`. The status semantics are Pattern-RemoteData's, refined: `:loading` = first load, no usable data; `:fetching` = refreshing while prior data stays visible; `:error` = first load failed (no data); `:refresh-error` = a background refresh failed but prior `:data` is kept. **A subscription never fetches** and never silently returns `:idle` for a scope mismatch — an unresolvable scope is a loud `:rf.error/resource-sub-unresolved-scope` (pass `:scope` on the sub payload).

### Cause it to fetch (events)

```clojure
[:rf.resource/ensure                               ;; fetch if absent/stale; cache-hit + skip if fresh
 {:resource :article/by-slug
  :scope    :rf.scope/global
  :params   {:slug "welcome"}
  :owner    [:route :route/article nav-token]      ;; liveness lease (needs a release path)
  :cause    [:route-entry :route/article nav-token]}]

[:rf.resource/refetch  {:resource :article/by-slug :scope :rf.scope/global
                        :params {:slug "welcome"} :cause [:manual :article/refresh]}]

[:rf.resource/release-owner {:owner [:route :route/article nav-token]}]
```

`ensure` is the workhorse: fresh entry ⇒ cache-hit, no fetch (fresh-skip); absent/stale ⇒ fetch. A manual refresh button is usually a **cause**, not an owner (it does not intend to keep the entry alive).

### Scope is mandatory (fail-closed)

`:scope` is REQUIRED at `reg-resource`. It is the cache's tenant / user / permission / locale / impersonation / SSR leak boundary, so it **fails closed** — there is no silent shared default:

> **The leak boundary other libraries do not have.** TanStack Query / RTK Query / SWR put the viewer id *inside the query key* (`['dashboard', userId, tenantId]`), but that segment is a **convention** — hand-assembled at every call site, so a forgotten `tenantId` in one of forty keys is a *silent* shared-cache leak (a missing line throws nothing), with logout left to `queryClient.clear()` (drops everyone). re-frame2 makes the **resolved scope part of the key's *type*, not a concatenated segment**: the runtime computes it into `[scope resource-id params]` structurally, it is **required** at registration, **fails closed** (unresolvable scope is a loud error, never a silent shared read), and logout is a **causal scoped clear** (`:rf.resource/clear-scope`) that touches one principal's entries and leaves global intact. The shift is *trust* → *structure*. Depth (the six structural guarantees + conformance tests): guide ch27 §"Scope — the leak boundary other libraries do not have".

- `:rf.scope/global` — an explicit, auditable **claim**: "the same params produce the same data for every user, tenant, locale, impersonation state." Xray enumerates every global resource as the security-review list.
- a **resolver** (`(fn [route ctx] …)` at a route `:resources` entry — the one site with a populated planning context, §Route-driven loading; or a pure-data / fn-of-nothing for a sub-resolvable scope) — derives a concrete scope that materializes as visible EDN in the key, e.g. `[:rf.scope/session {:user-id "u-42" :tenant-id "acme"}]`.
- a **named resolver reference** `{:from-db <resolver-id>}` — the reusable form (below) for db-derived viewer identity.
- `:rf.scope/from-caller` — every ensure / refetch / sub must supply `:scope` on the payload.
- **no `:scope`** — a loud `:rf.error/resource-missing-scope-policy` at registration. "I forgot this read is user-scoped" is unrepresentable.

#### Named scope resolvers — `reg-resource-scope`

When the same viewer identity (session / tenant / account / locale / impersonation) determines scope across *many* sites — resource registration, route resources, event-side ensure, subscriptions, invalidation descriptors, populate/patch targets, clear-scope — derive it **once** and reference it by name. `reg-resource-scope` registers a **pure** resolver (it derives a scope; it does not fetch, dispatch, mutate state, or read host state):

```clojure
(rf/reg-resource-scope :session
  {:inputs  {:username [:db [:auth :user :username]]}    ;; declared inputs — names ↦ source descriptors
   :resolve (fn [{:keys [username]} _ctx]
              (when username [:rf.scope/session {:username username}]))})

;; reference it anywhere derived scope is allowed:
(rf/reg-resource :feed
  {:scope   {:from-db :session}                          ;; ← reference
   :tags    (fn [_params _value] #{[:feed] [:article-list]})}
  (fn [{:keys [page]} _ctx]
    {:request {:method :get :url "/api/feed" :params {:page page}}
     :decode  :app/feed}))
```

The `{:inputs … :resolve …}` form **declares** which app facts decide identity — so tooling can explain it and the runtime re-resolves only when a declared input changes. A whole-db function sugar exists (`(rf/reg-resource-scope :session (fn [db _ctx] …))`) but lowers to an explicit whole-db dependency tooling flags as a cost — prefer declared inputs. A `{:from-db …}` reference resolves **at use time** against the causal db of its site, **fail-closed**: nil at a scope-requiring site is the unresolved condition (route planning never substitutes global; a sub is the loud `:rf.error/resource-sub-unresolved-scope`), never a silent fall-through. A **live subscription re-keys reactively** when the resolver's inputs change mid-session (account switch / login / logout): it points at the *new* scoped key and shows that key's state (`:idle` / `:loading`), never the old principal's data — the leak boundary holds across the re-key.

> **Declarative route-derived scope references (`{:from-route …}` / `{:from-frame …}`) are reserved, not shipped.** A `reg-resource-scope` resolver's declared `:inputs` are **db-derived** only in this slice (a future EP adds a route/runtime input source) — so don't write a *named* resolver reaching for route/frame facts, and don't invent a `{:from-route …}` / `{:from-frame …}` form. **This does not retire route-resource scope functions:** a route `:resources` entry's `:scope` MAY still be an anonymous `(fn [route ctx] …)` route resolver (§Route-driven loading), because that one site carries a *populated* planning context — the exception to the reserved-`ctx` rule. The narrow ban: don't synthesise an anonymous scope fn at registration / spec-side surfaces (where `ctx` is reserved-nil), and don't reach for the unshipped route/frame references — name db-derived identity with `reg-resource-scope` instead.

Login, logout, account switch, tenant switch, permission change, and impersonation enter/exit MUST clear or replace the affected scope causally. Clear the scope the user was **in** — and resolve that scope from the handler's **coeffect db** (pre-transition by definition) *before* removing the user, using the `resolve-resource-scope` helper:

```clojure
(rf/reg-event :auth/logout
  (fn [{:keys [db]} _]
    (let [old-scope (rf/resolve-resource-scope db :session)]   ;; concrete scope from cofx db
      {:db (dissoc db :auth)
       :fx [[:dispatch [:rf.resource/clear-scope
                        {:scope old-scope :cause :logout}]]]})))
```

`resolve-resource-scope` is a **pure** data helper over the resolver registry — resolves a named scope against a *given* db value, no timing ambiguity, no side effects, and emits **no** `:rf.resource/scope-resolved` trace (that fires only at causal resolution boundaries — resource events, route entry, mutation settlement, clear-scope diagnostics; `resolve-resource-scope` and subscription-key resolution are trace-free passive reads). **Never** put a whole-db snapshot on the event payload (there is no `:snapshot-db` key) — a db snapshot riding an event vector is an egress-bearing record on traces/epoch history, rejected under the egress policy. A `{:from-db …}` reference *may* appear on a `clear-scope` payload (use-time resolution applies); one resolving nil there emits a **loud diagnostic** (`:rf.warning/resource-clear-scope-unresolved`), never a silent no-op.

### Invalidate after a write (scoped, tag-based)

```clojure
[:rf.resource/invalidate-tags
 {:scope [:rf.scope/session {:user-id "u-42"}]
  :tags  #{[:article "welcome"] [:article-list]}
  :cause [:mutation :article/save mutation-id]}]
```

Matching entries are marked stale; entries with active owners refetch; inactive ones stay stale / eligible for GC. **Scoped by default** — a cross-scope invalidation opts in explicitly (it can refetch data for multiple users/tenants) and is visible in Xray. The per-target **descriptor** form (invalidating facts in *different* scopes from one write) is a write concern — see [`resources-mutations.md` §Per-target scoped invalidation](resources-mutations.md#per-target-scoped-invalidation-descriptors).

### Mutations — the write counterpart

Writes — `reg-mutation`, `[:rf.mutation/execute …]`, call-site `:reply-to`, scoped invalidation descriptors, exact-target populate/patch/removes, and optimistic plans — live in [`resources-mutations.md`](resources-mutations.md). A mutation is a named write that, on success, patches / populates / invalidates the cached reads this leaf owns, keyed by instance id so concurrent submissions never clobber. The `[:rf.resource/invalidate-tags …]` event above is the read-side half any writer dispatches; the declarative cache plan and the workflow-vs-cache split are on the mutations leaf.

### Request decoration — auth headers, retry (the managed-HTTP seam)

A resource/mutation request fn describes **the domain request** — method, url, params, body. Cross-cutting transport concerns — auth/bearer headers, tracing headers, a common base URL, tenant headers, default retry — do **not** belong in every declaration. They live **once** in the managed-HTTP decoration seam, because resources and mutations lower through Spec 014 managed HTTP. Register a frame-level HTTP interceptor and it decorates *every* managed request the frame issues — resource reads, mutations, plain managed calls alike:

```clojure
(rf/reg-http-interceptor :auth
  {:before (fn [ctx]
             (let [token (some-> (rf/app-db-value (:frame ctx)) :auth :token)]
               (cond-> ctx
                 token (assoc-in [:request :headers "Authorization"]
                                 (str "Token " token)))))})

(rf/reg-resource :current-user                  ;; no per-resource auth opt-in needed
  {:scope   :rf.scope/from-caller}
  (fn [_params _ctx]
    {:request {:method :get :url "/api/user"} :decode :app/user}))
```

The interceptor reads the token from `(:frame ctx)` (EP-0002 carried-frame-correct), not an ambient db, and returns `ctx` unchanged when no token is present. **Default retry should be read-focused** — retrying writes can duplicate side effects, so mutation retry defaults stay conservative (a mutation arms `:retry` only when its third-slot request fn returns a managed-HTTP args map carrying a top-level `:retry`). Traces report *that* an auth interceptor applied, never the bearer value itself.

### Route-driven loading (route `:resources`)

Routes are not required, but when a load is route-scoped, declare it on the route — the framework ensures on entry (owner `[:route route-id nav-token]`), releases on leave, and gives SSR a blocking wait point:

```clojure
(rf/reg-route
  :route/article
  {:params [:map [:slug :string]]
   :resources
   [{:resource :article/by-slug
     :params   (fn [route] {:slug (get-in route [:params :slug])})
     :scope    {:from-db :session}                  ;; ← named resolver, reused from registration
     :blocking? true}
    {:resource :comments/list
     :params   (fn [route] {:slug (get-in route [:params :slug])})
     :when     (fn [route _ctx] (some? (get-in route [:params :slug])))
     :blocking? false
     :keep-previous? true}]}
  "/articles/:slug")
```

`:blocking?` keeps the route transition pending and gives SSR a wait point; `:when` gates conditional resources (use it, not sentinel `nil` params); `:keep-previous?` keeps the prior page visible while a new page/filter first-loads. `:after #{local-id}` orders **ensure-dispatch only** — it is **not** a data waterfall (a later entry's params come from the *route*, not an earlier entry's loaded data). `:on-match` remains canonical for arbitrary route-entry work — `:resources` is declarative server-state beside it, not a second router.

## When to use vs plain managed HTTP

**Stay on [Pattern-RemoteData](remote-data.md) + managed HTTP** for a one-off fetch a single feature owns — no sharing, no cross-read invalidation, no TTL, unambiguously global. Reach for **Resources** the moment a fetch is shared across views, needs freshness / fresh-skip / focus-reconnect revalidation, must be invalidated after a write (list ⇄ detail), or needs a fail-closed tenant/user/locale scope. The two compose: a resource's transport **is** managed HTTP (Spec 014); Resources adds identity, scope, ownership, staleness, and invalidation on top. Full chooser: [`../decision-trees/pick-a-pattern.md` §Resources vs RemoteData](../decision-trees/pick-a-pattern.md).

## Anti-patterns

- **Fetching from a view / subscription.** Views are passive reads. A render must never trigger a fetch — `ensure` from the route or an event. (A subscription that "fetches" is a category error; v1 has no subscription-side fetch.)
- **Hand-editing the resource cache.** `:rf.runtime/resources` is framework-owned runtime-db state; never `assoc-in` into it from a `:db` handler. Use the `:rf.resource/*` events.
- **An implicit / forgotten scope.** A user-scoped read registered global (or with no scope) is the leak the fail-closed policy kills. Declare scope intent once, at registration.
- **An owner with no release path.** An app-minted lease (`[:lease …]`) that is never released pins an entry alive and keeps it refetching on focus/reconnect — a slow leak. Xray lints orphaned owners; mint a lease only with a matching `:rf.resource/release-owner`.
- **A manual refresh as an owner.** A button click that just wants fresh data is a **cause**, not an owner — it should not keep the entry alive.
- **Re-implementing a `:select` hook.** Project with an ordinary `reg-sub` over `[:rf.resource/data …]`; the subscription graph is the projection layer.
- **Keying a cache/lookup by host `=` over normalized EDN.** The scoped key's identity is the **`CEDN-1` byte comparison**, not host `=`. Host equality over a normalized EDN value is **not** sufficient unless it preserves EDN *kind*: in CLJS `(= [:a 1] '(:a 1))` is `true`, but a vector and a list are distinct `CEDN-1` identities (different type tags), so a hand-rolled `assoc`-into-a-host-map keyed on raw params can collide two distinct reads or miss a hit. Sorting map keys is necessary but not sufficient — kind-collapse still bites. Let the framework compute the key (`:scope` + `:params` flow through the canonical rule); never derive your own resource key from `(= params-a params-b)` or a host hash over raw params. See [`../references/cross-cutting/path-and-identity.md`](../references/cross-cutting/path-and-identity.md) §Canonical EDN identity (`CEDN-1`) — the "CEDN-1 byte trap" callout.
- **`:snapshot-db` on a logout/clear-scope payload.** There is no such key. Resolve the concrete old scope from the handler's coeffect db via `resolve-resource-scope`, before removing the user. A whole-db snapshot on an event is an egress-bearing record.

Write-side anti-patterns (app-workflow-on-`reg-mutation`, `:cross-scope?` overuse, hand-rolled optimistic rollback, threading `db`/`ctx` into a cache callback) live in [`resources-mutations.md` §Anti-patterns](resources-mutations.md#anti-patterns).

## Worked examples

- **`examples/capabilities/resources/resources/`** — focused **read-side** demo: route-driven page load (`:resources` route metadata), event-driven `ensure` under an app `[:lease …]` owner with a release path, manual refresh as a *cause* (not owner), a machine-owned resource. Views read through passive `[:rf.resource/*]` subs; scope is the fail-closed leak boundary (explicit `:rf.scope/global` claim).
- **Write side** — the mutation counterpart (call-site `:reply-to`, scoped invalidation descriptors, populate-as-authoritative-load, a named `reg-resource-scope` resolver) is worked end-to-end in `examples/real-apps/realworld_resources/`; see [`resources-mutations.md`](resources-mutations.md).

For the hand-rolled-slice shape Resources *supersedes*, `examples/real-apps/realworld_http/articles.cljs` is the closest reference (slice-form Pattern-RemoteData over `[:articles]`).

## Pointers

- Spec: `SKILL-REDIRECT.md` → *EP — Resources (016)* (full identity rules, scope resolution, the lifecycle FSM, the work ledger, race/in-flight semantics, route integration, SSR/hydration, the `:rf.resource/*` and `:rf.mutation/*` surfaces).
- Write side: [`patterns/resources-mutations.md`](resources-mutations.md) (`reg-mutation`, `:reply-to`, descriptors, populate/patch/removes, optimistic plans).
- Transport: `SKILL-REDIRECT.md` → *EP — HTTP requests (014)* (the `:rf.http/managed` surface resources lower onto).
- Compose: [`patterns/remote-data.md`](remote-data.md) (the hand-rolled slice form, and the slice-vs-resource decision); [`patterns/stale-detection.md`](stale-detection.md) (the epoch idiom — resources own generation-based stale suppression internally, so you rarely hand-roll it for a resource read).
- Frames: every resource carries its explicit frame (EP-0002 — no ambient `:rf/default`); a frameless resource op fails closed with `:rf.error/no-frame-context`.

---

*Derived from `spec/016-Resources.md` (optional capability `day8/re-frame2-resources`) @ main. Re-verify after later resource slices land.*
