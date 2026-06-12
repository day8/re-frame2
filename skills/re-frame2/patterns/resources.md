# Pattern — Resources

Declarative server-state: a **named, cached read** of remote state that views read passively and route-entry / events / machines *cause* to fetch. `reg-resource` registers it; the framework owns identity, cache scope, staleness, dedupe, invalidation, garbage collection, in-flight ownership, and SSR hydration — so the app stops re-implementing that bookkeeping per feature. The write counterpart is `reg-mutation`.

> **Mental-model anchor:** this is re-frame2's answer to **TanStack Query / RTK Query / SWR** — re-expressed in the re-frame2 model. Map that intuition onto the surface below, then note the **deliberate divergences** flagged inline:
>
> | TanStack Query | re-frame2 resources | The divergence |
> |---|---|---|
> | `useQuery({ queryKey, queryFn })` in a component | `reg-resource` registers; a route/event *ensures*; a view *subscribes* | Views are **passive reads** — they never trigger a fetch. Fetching is **causal** (route entry, event, machine), never a render side effect. |
> | the `queryKey` array | `[scope resource-id params]` triple | **Scope is mandatory and fail-closed** — the tenant / user / locale / impersonation leak boundary is declared, not inferred. There is no silent shared cache. |
> | active observers (a mounted component keeps data alive) | **active owners** (route / machine / lease) vs **causes** (trace metadata) | Liveness is **explicit** (an owner with a release path), decoupled from rendering. A view mounting does not pin the cache. |
> | `select` option | an ordinary `reg-sub` over `[:rf.resource/data …]` | No `:select` key — projections are subscriptions (EP-0004 parametric inputs). |
> | `queryClient.invalidateQueries` | `[:rf.resource/invalidate-tags …]` (scoped) | Invalidation is **data** an event dispatches, scoped by default; visible in trace/Xray. |
> | `useMutation` | `reg-mutation` + `[:rf.mutation/execute …]` | Keyed by **instance id** so concurrent submissions never clobber; success patches/populates entries then invalidates tags. |

**Optional capability — `day8/re-frame2-resources` (Spec 016).** Resources is a post-v1 optional artefact. An app that does not require it expresses server state with [Pattern-RemoteData](remote-data.md) + managed HTTP directly — see §When to use vs plain managed HTTP. Everything below assumes the artefact is on the classpath; `(rf/feature-loaded? :resources)` answers whether it is.

## When to load

The prompt mentions: a **server-state cache** with freshness / staleness / TTL, "TanStack Query / React-Query / SWR / RTK Query in re-frame2", a fetch that several views read, cache **invalidation** after a write, "refetch on focus / reconnect", "don't refetch if fresh", a **mutation** that updates cached reads, route-driven data loading, or "stop hand-rolling the loading/error/refetch bookkeeping". Also load when choosing between a resource and a plain Pattern-RemoteData slice (§When to use vs plain managed HTTP).

## The shape

A **resource instance** is identified by a **scoped resource key** — the triple `[canonical-scope resource-id canonical-params]`. The scope is the leak boundary; params identify the read within a scope. Scope and params both run through re-frame2's one canonical EDN identity rule (`CEDN-1`): the **canonical EDN value is the authoritative identity** — equal facts produce the same key across CLJ/CLJS hosts, map key order never affects it, and a present `nil` param differs from an absent one. The identity domain is **fail-closed**: a param value outside the portable EDN domain (a function, a host `Date`, a DOM node, a floating-point number) is rejected with `:rf.error/non-edn-identity` rather than hashed by object identity — coerce host values to portable EDN (e.g. a `#inst` instant) at the boundary first. Any digest is an **optional, derived projection** of that canonical key for size-constrained surfaces, never the authoritative identity. See [`../references/cross-cutting/path-and-identity.md`](../references/cross-cutting/path-and-identity.md) for the shared contract.

Three roles never blur:

- **Views are passive.** A `[:rf.resource/state …]` subscription reads cached state; it never fetches.
- **Causes explain why work happened.** `[:route-entry …]`, `[:manual …]`, `:focus`, `:reconnect` — trace/diagnostic metadata that does not change liveness.
- **Owners keep a resource alive** (a liveness lease) and have a matching **release path**. Route owners carry the nav-token; machine owners are released on actor-destroy; app-minted leases need an explicit `:rf.resource/release-owner`.

## Canonical declaration

```clojure
(rf/reg-resource
  :article/by-slug
  {:doc            "Article detail by slug."
   :params-schema  [:map [:slug :string]]          ;; REQUIRED — validates + canonicalizes params
   :data-schema    :app/article                    ;; validates decoded data
   :request                                         ;; REQUIRED — returns a Spec 014 managed-HTTP args map
   (fn [{:keys [slug]} _ctx]
     {:request {:method :get :url (str "/api/articles/" slug)}
      :decode  :app/article})
   :scope          :rf.scope/global                 ;; REQUIRED — see §Scope is mandatory
   :stale-after-ms 60000                            ;; fresh window; after it, an ensure refetches
   :gc-after-ms    300000                           ;; inactive (no owner) entries GC'd after this
   :tags           (fn [{:keys [slug]} _data] #{[:article slug]})})  ;; invalidation tags
```

The `:request` fn returns a managed-HTTP args map but MUST NOT supply `:request-id` / `:on-success` / `:on-failure` — the runtime owns reply addressing and stale suppression (those are rejected at registration / dispatch).

### Read it (passive subscriptions)

```clojure
;; In a reg-view — the scope + params name the same instance the owner ensured under.
(let [state @(rf/subscribe [:rf.resource/state
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

- `:rf.scope/global` — an explicit, auditable **claim**: "the same params produce the same data for every user, tenant, locale, impersonation state." Xray enumerates every global resource as the security-review list.
- a **resolver** (`(fn [route ctx] …)` for a route resource, or a pure-data / fn-of-nothing for a sub-resolvable scope) — derives a concrete scope that materializes as visible EDN in the key, e.g. `[:rf.scope/session {:user-id "u-42" :tenant-id "acme"}]`.
- `:rf.scope/from-caller` — every ensure / refetch / sub must supply `:scope` on the payload.
- **no `:scope`** — a loud `:rf.error/resource-missing-scope-policy` at registration. "I forgot this read is user-scoped" is unrepresentable.

Login, logout, account switch, tenant switch, permission change, and impersonation enter/exit MUST clear or replace the affected scope causally:

```clojure
[:rf.resource/clear-scope {:scope [:rf.scope/session {:user-id "u-42"}] :cause :logout}]
```

### Invalidate after a write (scoped, tag-based)

```clojure
[:rf.resource/invalidate-tags
 {:scope [:rf.scope/session {:user-id "u-42"}]
  :tags  #{[:article "welcome"] [:article-list]}
  :cause [:mutation :article/save mutation-id]}]
```

Matching entries are marked stale; entries with active owners refetch; inactive ones stay stale / eligible for GC. **Scoped by default** — a cross-scope invalidation opts in explicitly (it can refetch data for multiple users/tenants) and is visible in Xray.

### Mutations — the causal write counterpart

A mutation is a named WRITE that, on success, patches / populates / invalidates cached reads. Keyed by **instance id**, so two concurrent submissions never clobber each other.

```clojure
(rf/reg-mutation
  :article/save
  {:params-schema :app/article
   :request (fn [{:keys [slug] :as article} _ctx]
              {:request {:method :put :url (str "/api/articles/" slug) :body article}
               :decode  :app/article})
   :invalidates       (fn [{:keys [slug]} _result] #{[:article slug] [:article-list]})
   :scope             :rf.scope/global
   :invalidate-timing :after-success})              ;; | :before-request | :after-failure | :after-settle

[:rf.mutation/execute {:mutation :article/save :params article
                       :instance :form/save-1       ;; caller-supplied (or generated)
                       :cause [:form-submit :article/save]}]

;; observe passively, keyed by instance id:
@(rf/subscribe [:rf.mutation/state {:instance :form/save-1}])   ;; {:status :pending? :success? :result :error …}
```

`:patches` / `:populates` (optional) transform / seed resource entries before the success-time invalidation. Optimistic rollback is a deferred slice — do not hand-roll it against reserved keys.

### Route-driven loading (route `:resources`)

Routes are not required, but when a load is route-scoped, declare it on the route — the framework ensures on entry (owner `[:route route-id nav-token]`), releases on leave, and gives SSR a blocking wait point:

```clojure
(rf/reg-route
  :route/article
  {:path "/articles/:slug"
   :params [:map [:slug :string]]
   :resources
   [{:resource :article/by-slug
     :params   (fn [route] {:slug (get-in route [:params :slug])})
     :scope    (fn [_route ctx] (:current-session-scope ctx))
     :blocking? true}
    {:resource :comments/list
     :params   (fn [route] {:slug (get-in route [:params :slug])})
     :when     (fn [route _ctx] (some? (get-in route [:params :slug])))
     :blocking? false
     :keep-previous? true}]})
```

`:blocking?` keeps the route transition pending and gives SSR a wait point; `:when` gates conditional resources (use it, not sentinel `nil` params); `:keep-previous?` keeps the prior page visible while a new page/filter first-loads. `:after #{local-id}` orders **ensure-dispatch only** — it is **not** a data waterfall (a later entry's params come from the *route*, not an earlier entry's loaded data). `:on-match` remains canonical for arbitrary route-entry work — `:resources` is declarative server-state beside it, not a second router.

## When to use vs plain managed HTTP

| Reach for **Resources** when… | Reach for **[Pattern-RemoteData](remote-data.md) + managed HTTP** when… |
|---|---|
| Several views read the same fetch and you want **one cached entry**, dedupe, and shared invalidation. | A single feature owns a one-off fetch with no sharing or cache-invalidation story. |
| You need **freshness / staleness / TTL**, fresh-skip, focus/reconnect revalidation out of the box. | The slice shape and the four-event lifecycle are all you need; no TTL. |
| A **write must invalidate** related reads (list ⇄ detail). | There is no write, or the write's effect on other reads is trivial. |
| **Tenant / user / locale** scoping must be a fail-closed, auditable boundary. | The data is unambiguously global and the bookkeeping is light. |
| You want **route-declared loading + SSR preload** for free. | Boot/route loading is already handled by `:on-match` and a slice. |

The two compose: a resource's transport **is** managed HTTP (Spec 014). Resources is the higher layer that adds identity, scope, ownership, staleness, and invalidation on top.

## Anti-patterns

- **Fetching from a view / subscription.** Views are passive reads. A render must never trigger a fetch — `ensure` from the route or an event. (A subscription that "fetches" is a category error; v1 has no subscription-side fetch.)
- **Hand-editing the resource cache.** `:rf.runtime/resources` is framework-owned runtime-db state; never `assoc-in` into it from a `:db` handler. Use the `:rf.resource/*` events.
- **An implicit / forgotten scope.** A user-scoped read registered global (or with no scope) is the leak the fail-closed policy kills. Declare scope intent once, at registration.
- **An owner with no release path.** An app-minted lease (`[:lease …]`) that is never released pins an entry alive and keeps it refetching on focus/reconnect — a slow leak. Xray lints orphaned owners; mint a lease only with a matching `:rf.resource/release-owner`.
- **A manual refresh as an owner.** A button click that just wants fresh data is a **cause**, not an owner — it should not keep the entry alive.
- **Re-implementing a `:select` hook.** Project with an ordinary `reg-sub` over `[:rf.resource/data …]`; the subscription graph is the projection layer.
- **Keying a cache/lookup by host `=` over normalized EDN.** The scoped key's identity is the **`CEDN-1` byte comparison**, not host `=`. Host equality over a normalized EDN value is **not** sufficient unless it preserves EDN *kind*: in CLJS `(= [:a 1] '(:a 1))` is `true`, but a vector and a list are distinct `CEDN-1` identities (different type tags), so a hand-rolled `assoc`-into-a-host-map keyed on raw params can collide two distinct reads or miss a hit. Sorting map keys is necessary but not sufficient — kind-collapse still bites. Let the framework compute the key (`:scope` + `:params` flow through the canonical rule); never derive your own resource key from `(= params-a params-b)` or a host hash over raw params. See [`../references/cross-cutting/path-and-identity.md`](../references/cross-cutting/path-and-identity.md) §Canonical EDN identity (`CEDN-1`) — the "CEDN-1 byte trap" callout.
- **Hand-rolling optimistic rollback** against the reserved mutation keys — it is a deferred slice; the runtime does not ship it yet.

## Worked example

No standalone example app yet — the canonical shapes above are lifted from Spec 016's worked declarations. For a hand-rolled-slice equivalent (the shape Resources supersedes), `examples/reagent/realworld/articles.cljs` is the closest reference (a slice-form Pattern-RemoteData over `[:articles]`).

## Pointers

- Spec: `SKILL-REDIRECT.md` → *EP — Resources (016)* (full identity rules, scope resolution, the lifecycle FSM, the work ledger, race/in-flight semantics, route integration, SSR/hydration, the `:rf.resource/*` and `:rf.mutation/*` surfaces).
- Transport: `SKILL-REDIRECT.md` → *EP — HTTP requests (014)* (the `:rf.http/managed` surface resources lower onto).
- Compose: [`patterns/remote-data.md`](remote-data.md) (the hand-rolled slice form, and the slice-vs-resource decision); [`patterns/stale-detection.md`](stale-detection.md) (the epoch idiom — resources own generation-based stale suppression internally, so you rarely hand-roll it for a resource read).
- Frames: every resource carries its explicit frame (EP-0002 — no ambient `:rf/default`); a frameless resource op fails closed with `:rf.error/no-frame-context`.

---

*Derived from `spec/016-Resources.md` (optional capability `day8/re-frame2-resources`) @ main. The first public-beta surface (read-resource MVP + `reg-mutation` + focus/reconnect revalidation) is landed; optimistic rollback, polling, and GraphQL are deferred slices. Re-verify the surface after later resource slices land.*
