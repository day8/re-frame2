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
> | `queryClient.invalidateQueries` | `[:rf.resource/invalidate-tags …]` (scoped) | Invalidation is **data** an event dispatches, **scoped by default** — and a mutation can invalidate facts in *different* scopes precisely with per-target **descriptors** (`{:scope … :tags …}`); visible in trace/Xray. |
> | `useMutation` | `reg-mutation` + `[:rf.mutation/execute …]` | Keyed by **instance id** so concurrent submissions never clobber; success patches/populates entries then invalidates tags. |
> | `useMutation({ onSuccess })` callback | call-site **`:reply-to`** on `[:rf.mutation/execute …]` | The continuation is a **causal event target**, not a callback — the runtime dispatches your event with a reply map after cache consequences settle, so it lands on the event tape (replayable, traced, interceptor-visible). Cache effects stay declarative on `reg-mutation`; **`:reply-to` is for app workflow**. |

**Optional capability — `day8/re-frame2-resources` (Spec 016).** Resources is a post-v1 optional artefact. An app that does not require it expresses server state with [Pattern-RemoteData](remote-data.md) + managed HTTP directly — see §When to use vs plain managed HTTP. Everything below assumes the artefact is on the classpath; `(rf/feature-loaded? :resources)` answers whether it is.

## When to load

The prompt mentions: a **server-state cache** with freshness / staleness / TTL, "TanStack Query / React-Query / SWR / RTK Query in re-frame2", a fetch that several views read, cache **invalidation** after a write, "refetch on focus / reconnect", "don't refetch if fresh", a **mutation** that updates cached reads, "navigate / show a toast / update state **after** a write succeeds" (mutation completion / `:reply-to` workflow continuation — the `onSuccess` shape), invalidating facts in **different scopes** from one write (per-target descriptors), a reusable **session / tenant / account scope** (`reg-resource-scope`), route-driven data loading, auth headers / retry for resource requests, or "stop hand-rolling the loading/error/refetch bookkeeping". Also load when choosing between a resource and a plain Pattern-RemoteData slice (§When to use vs plain managed HTTP).

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

> **The leak boundary other libraries do not have.** TanStack Query / RTK Query / SWR all keep the same mental model — put the viewer id *inside the query key* (`['dashboard', userId, tenantId]`) so two principals' caches don't collide. But that segment is a **convention**: you hand-assemble it at every call site, and a forgotten `tenantId` in one of forty keys is a *silent* shared-cache leak (no error — a missing line throws nothing), with logout left to `queryClient.clear()` (drops everyone) or a hand-maintained key list. re-frame2 keeps the model and removes the two ways it goes wrong: **the resolved scope is part of the key's *type*, not a segment you concatenate** — the runtime computes it into `[scope resource-id params]` structurally, it is **required** at registration, it **fails closed** (an unresolvable scope is a loud error, never a silent shared read), and logout is a **causal scoped clear** (`:rf.resource/clear-scope`) that touches one principal's entries and leaves global intact. The shift is from *trust* (every dev remembering every segment forever) to *structure* (a property of the cache key's type). Depth — the six structural guarantees and the conformance tests pinning them — is in the guide (ch27 §"Scope — the leak boundary other libraries do not have").

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

The `{:inputs … :resolve …}` form **declares** which app facts decide the identity — so tooling can explain it and the runtime re-resolves only when a declared input changes. A whole-db function sugar exists (`(rf/reg-resource-scope :session (fn [db _ctx] …))`) but lowers to an explicit whole-db dependency that tooling flags as a cost — prefer declared inputs. A `{:from-db …}` reference resolves **at use time** against the causal db of its site, and is **fail-closed**: nil at a scope-requiring site is the unresolved condition (route planning never substitutes global; a sub is the loud `:rf.error/resource-sub-unresolved-scope`), never a silent fall-through. A **live subscription re-keys reactively** when the resolver's inputs change mid-session (account switch / login / logout): it points at the *new* scoped key and shows that key's state (`:idle` / `:loading`), never the old principal's data — the leak boundary holds across the re-key.

> **Declarative route-derived scope references (`{:from-route …}` / `{:from-frame …}`) are reserved, not shipped.** A `reg-resource-scope` resolver's declared `:inputs` are **db-derived** only in this slice — viewer identity that is app state → `[:db …]`; a future EP adds a route/runtime input source for pure-route facts. So do not write a *named* resolver that reaches for route/frame facts, and do not invent a `{:from-route …}` / `{:from-frame …}` reference form. **This does not retire route-resource scope functions.** A route `:resources` entry's `:scope` MAY still be an anonymous `(fn [route ctx] …)` route resolver (§Route-driven loading, below) — that one site carries a *populated* planning context (a real route match), so it is the exception to the reserved-`ctx` rule and stays valid. The narrow ban is: don't synthesise an anonymous scope fn at the registration / spec-side surfaces (where `ctx` is reserved-nil), and don't reach for the unshipped declarative route/frame references — name db-derived identity with `reg-resource-scope` instead.

Login, logout, account switch, tenant switch, permission change, and impersonation enter/exit MUST clear or replace the affected scope causally. Clear the scope the user was **in** — and resolve that scope from the handler's **coeffect db** (pre-transition by definition) *before* removing the user, using the `resolve-resource-scope` helper:

```clojure
(rf/reg-event :auth/logout
  (fn [{:keys [db]} _]
    (let [old-scope (rf/resolve-resource-scope db :session)]   ;; concrete scope from cofx db
      {:db (dissoc db :auth)
       :fx [[:dispatch [:rf.resource/clear-scope
                        {:scope old-scope :cause :logout}]]]})))
```

`resolve-resource-scope` is a **pure** data helper over the resolver registry — it resolves a named scope against a *given* db value, with no timing ambiguity. It is not an effect (no app-state or dispatch side effects) and emits **no** `:rf.resource/scope-resolved` trace: `:rf.resource/scope-resolved` is emitted only at causal resolution boundaries (resource events, route entry, mutation settlement, clear-scope diagnostics), while `resolve-resource-scope` and subscription-key resolution are trace-free passive reads. **Never** put a whole-db snapshot on the event payload (there is no `:snapshot-db` key): a db snapshot riding an event vector is an egress-bearing record on traces and epoch history, rejected under the egress policy. A `{:from-db …}` reference *may* still appear on a `clear-scope` payload (use-time resolution applies); one that resolves nil there emits a **loud diagnostic** (`:rf.warning/resource-clear-scope-unresolved`), never a silent no-op.

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
   :invalidates       (fn [{:keys [slug]} _result] #{[:article slug] [:article-list]})
   :scope             :rf.scope/global
   :invalidate-timing :after-success}               ;; | :before-request | :after-failure | :after-settle

  (fn [{:keys [slug] :as article} _ctx]
    {:request {:method :put :url (str "/api/articles/" slug) :body article}
     :decode  :app/article}))

[:rf.mutation/execute {:mutation :article/save :params article
                       :instance :form/save-1       ;; caller-supplied (or generated)
                       :cause [:form-submit :article/save]}]

;; observe passively, keyed by instance id:
@(rf/subscribe [:rf.mutation/state {:instance :form/save-1}])   ;; {:status :pending? :success? :result :error …}
```

`:patches` / `:populates` (optional) transform / seed resource entries before the success-time invalidation. For a write whose effect should show *immediately* — before the reply lands — declare an **optimistic plan** (`:optimistic` / `:optimistic-tags`); the runtime records the inverse and commits / rolls back / reconciles on settle (see [§Optimistic mutations](#optimistic-mutations-apply-before-the-reply)). Do **not** hand-roll optimistic rollback against the reserved cache keys.

### Two axes a mutation carries: cache consequences vs workflow

A write has two jobs, and re-frame2 keeps them on separate axes — the single rule that organises everything below:

| Axis | Where it lives | Examples |
|---|---|---|
| **Cache consequences** — keep the cache coherent | **declarative on `reg-mutation`**: `:patches` / `:populates` / `:invalidates` | refresh the article list after a save; seed the detail entry from the reply |
| **App workflow** — what the *app* does next | **call-site `:reply-to`** on `[:rf.mutation/execute …]` | navigate to the new slug; update the auth slice; show a toast; fold validation errors |

Mantra: **`:reply-to` is for workflow; populate / patch / invalidate are for cache.** This is re-frame2's deliberate divergence from TanStack/RTK-Query's `onSuccess` callback (see the mental-model table) — the continuation is a causal *event*, not a callback.

### Mutation completion — `:reply-to` (workflow continuation)

`[:rf.mutation/execute …]` takes an optional call-site **`:reply-to`** event target. When the runtime accepts the reply as current, it dispatches that target with one **reply map** appended as the final arg:

```clojure
(rf/reg-event :settings/save
  (fn [_ [_ form]]
    {:fx [[:dispatch
           [:rf.mutation/execute
            {:mutation :user/update
             :params   form
             :instance [:settings/save]
             :reply-to [:settings/save-replied]}]]]}))   ;; ← workflow target

(rf/reg-event :settings/save-replied
  (fn [{:keys [db]} [_ {:keys [status value error]}]]    ;; reply map is the last arg
    (case status
      :ok    {:db (assoc db :auth/user (:user value))    ;; app-db workflow lives here
              :fx [[:dispatch [:toast/show "Settings saved"]]]}
      :error {:db (assoc db :settings/error error)})))
```

The reply map carries (the mutation-specific facts): `:status` (the EP-0011 reply enum — `:ok` / `:error` / accepted terminal `:cancelled`; **never** `:stale`), `:mutation`, `:params`, `:instance`, `:scope`, `:value` (for `:ok`), `:error` (for `:error`), `:affected-keys`, `:work/id`, `:rf.frame/id`, `:completed-at`, and `:cause [:mutation <id> <instance>]`. Read `:completed-at` off the reply for any durable timestamp — do **not** re-read the host clock (EP-0010 causal-time rule).

Three load-bearing rules:

- **Fires on acceptance, for any accepted terminal reply** — `:ok`, `:error`, or an accepted terminal `:cancelled`. A **stale or superseded** reply (a re-execute under the same instance won, or `[:rf.mutation/clear …]` fired) **never** dispatches the continuation. One rule, no per-status table.
- **Fires exactly once, after the cache settles.** Phase order is runtime-owned and deterministic: resolve scope → send → accept/suppress → **cache consequences** (`:patches` / `:populates` / `:invalidates`) → mutation instance settlement → **continuation**. So a `:reply-to` handler observes the cache already coherent and the instance already settled for that reply.
- **Static args are preserved; the reply is appended after them.** `:reply-to [:toast/after-save {:kind :article}]` dispatches `[:toast/after-save {:kind :article} reply]`.

This is the **replacement** for the old watcher-reaction idiom (watch `[:rf.mutation/state …]` from a component lifecycle hook and dispatch when it goes successful) — see [`patterns/stale-detection.md` §Post-mutation workflow](stale-detection.md). Registration-level `:reply-to` on `reg-mutation` is deliberately **not** added: invariant workflow is spelled by every call site passing the same target; the cache plan already covers the declarative half.

### Per-target scoped invalidation (descriptors)

Bare shorthand stays valid — it means *invalidate those tags in the mutation's resolved scope* (`:rf.scope/same`):

```clojure
:invalidates #{[:article slug] [:article-list]}
```

But one write often touches facts in **different scopes** — a global article fact *and* the current viewer's session-scoped feed. (Tags name remote *facts*; scopes name *viewers*; stale is a property of a `(fact, viewer)` pair.) The bare form cannot say that. The **descriptor form** gives each target its own scope:

```clojure
:invalidates
(fn [{:keys [slug]} _result]
  [{:scope :rf.scope/global                  ;; global facts
    :tags  #{[:article slug] [:article-list]}}
   {:scope {:from-db :session}              ;; viewer-relative fact (named resolver, below)
    :tags  #{[:feed]}}])
```

A descriptor `:scope` may be: `:rf.scope/same` (the mutation's resolved scope — the default if omitted, and the meaning of bare shorthand); `:rf.scope/global`; a concrete canonical scope value; or a named-resolver reference `{:from-db <resolver-id>}` (resolved against db at settle time). Both forms lower to **one** invalidation engine.

The fail-closed lattice: a bare `invalidate-tags` with **no scope** is a loud error (`:rf.error/resource-invalidate-scope-required`); **descriptors** are the precise path; **`:cross-scope? true`** is the *only* scope-agnostic opt-out — the explicit, audited escape for "invalidate this tag in *every* scope holding it" (admin tooling, cache-poisoning response), which MUST carry `:cause` and is lintable in Xray as a privacy-relevant broad operation. Reach for a descriptor, not `:cross-scope?`, whenever you can name the scopes.

### Map-form exact targets — populate / patch / removes

Exact cache targets (`:populates`, `:patches`, `:removes`) name a single entry with **one canonical map form** — `{:resource … :params … :scope …}` (the `:scope` defaults to `:rf.scope/same`, the mutation's resolved scope, when omitted). It is the only **public input** form; the tuple `[scope resource-id params]` is the *internal storage* key shape, never written by hand. The three arms differ only in what the target maps to:

| Arm | Callback shape | The target maps to | Effect |
|---|---|---|---|
| **`:populates`** | `(fn [params result] -> {target value})` | the **value** to seed | seed/replace the entry as an authoritative load (below) |
| **`:patches`** | `(fn [params result] -> {target patch-fn})` | a **`patch-fn`** `(fn [old-data result] -> new-data)` | transform the entry's existing `:data` (no-op on an entry with no data) |
| **`:removes`** | `(fn [params result] -> [target …])` (or a single `target`) | — (no value) | dissoc the entry from the cache |

Populate is an **authoritative load**: a key seeded from an accepted reply becomes loaded/fresh exactly as if a GET had returned it (store the resource's full decoded shape, not a sub-projection), and is **exempt from immediate refetch by that same mutation's invalidation pass** — opt back in with `:refetch-populated? true` on the descriptor when the reply is partial relative to the full GET. A target whose `{:from-db …}` scope resolves nil is **fail-closed** (dropped, never a partial/wrong-scope write).

```clojure
(rf/reg-mutation :article/favorite
  {:scope :rf.scope/global
   ;; {target value} — the KEY is the map-form target; the VAL is the seeded value.
   :populates (fn [{:keys [slug]} result]                    ;; (params result)
                {{:resource :article/by-slug
                  :params   {:slug slug}
                  :scope    :rf.scope/global} result})        ;; stored = full resource shape
   :invalidates (fn [{:keys [slug]} _result]
                  [{:scope :rf.scope/global :tags #{[:article-list] [:article slug]}}
                   {:scope {:from-db :session} :tags #{[:feed]}}])}
  (fn [{:keys [slug]} _ctx]
    {:request {:method :post :url (str "/api/articles/" slug "/favorite")}
     :decode  :app/article}))

;; :patches — {target patch-fn}; the patch-fn reshapes the entry's existing :data.
;; :patches (fn [{:keys [slug]} result]
;;            {{:resource :article/by-slug :params {:slug slug} :scope :rf.scope/global}
;;             (fn [old-data _result] (update old-data :favorites-count inc))})

;; :removes — a target, or a collection of targets, to dissoc from the cache.
;; :removes (fn [{:keys [slug]} _result]
;;            [{:resource :article/by-slug :params {:slug slug} :scope :rf.scope/global}])
```

`:populates` / `:patches` / `:removes` / `:invalidates` all receive `(params result)` — the one canonical mutation-consequence signature. Derive a db-relative scope through a **named resolver reference** (`{:from-db …}`), never by threading `db`/`ctx` into the callback.

### Optimistic mutations (apply before the reply)

When a write's effect should show *the instant the user clicks* — the heart flips, the count increments, the card disappears — declare an **optimistic plan**. The runtime applies it to the cache *before* the request is sent, records a truthful inverse, and deterministically **commits**, **rolls back**, or **reconciles** when the reply settles. This is re-frame2's analogue of TanStack Query's `onMutate` + rollback context, but the inverse is **runtime-recorded, not author-written** — so it can never drift from the forward patch.

Two forward forms — the optimistic twins of `:patches` and tag-addressed `:invalidates`:

```clojure
(rf/reg-mutation :article/favorite
  {:scope :rf.scope/global

   ;; :optimistic — exact-target twin of :patches. (fn [params] -> {target patch-fn})
   ;; NOTE: no `result` arg — the apply runs before any reply exists.
   :optimistic
   (fn [{:keys [slug]}]
     {{:resource :article/by-slug :params {:slug slug} :scope :rf.scope/global}
      (fn [old-data] (update old-data :favorites-count inc))})    ;; (old-data) only

   ;; :optimistic-tags — tag-addressed twin, for cross-view consistency you can't enumerate by key.
   ;; (fn [params] -> [{:scope … :tags #{…} :patch (fn [old-data] new-data)}])
   :optimistic-tags
   (fn [{:keys [slug]}]
     [{:scope :rf.scope/same
       :tags  #{[:article slug]}
       :patch (fn [old-data] (assoc old-data :favorited? true))}])

   :on-conflict :invalidate}                ;; default; | :force

  (fn [{:keys [slug]} _ctx]
    {:request {:method :post :url (str "/api/articles/" slug "/favorite")}
     :decode  :app/article}))
```

Load-bearing rules:

- **The runtime records the inverse, not you.** Each touched entry's whole pre-patch state (or an `:rf.optimistic/absent` sentinel for a key with no entry) is snapshotted, so a rollback restores *exactly* what was there — never a reconstructed approximation. A `nil` patch-fn value is an **optimistic remove**; a patch over an **absent** key is an **optimistic seed**.
- **Settle is deterministic, keyed on the per-entry `:revision`** (a canonical-identity comparison, never a wall-clock race or value diff): an accepted **`:ok`** reply **commits** (the authoritative `:populates` / `:patches` overwrite the optimistic value, then `:invalidates` runs; the inverse is discarded); an accepted **`:error` / `:cancelled`** reply **rolls back**; a **stale / superseded** reply rolls back nothing.
- **`:on-conflict`** governs a rollback when a competing write moved the entry's revision since the apply: **`:invalidate`** (default, recommended) marks the entry stale and lets the read path refetch the authoritative value — re-frame2's deliberate divergence from TanStack/SWR's unconditional context restore; **`:force`** restores the (possibly stale) inverse anyway (single-writer last-write-wins) with a tooling warning. An out-of-enum value is a loud `reg-mutation` error.
- **Scope is fail-closed.** An optimistic `{:from-db …}` target that resolves nil is **dropped** (`:target-unresolved`), never an implicit global write — the same leak boundary a read has. There is no `:cross-scope?` optimistic form by construction: optimistic patching is exact-key or tag-within-named-scope only.
- **Incompatible with `:invalidate-timing :before-request`** — a before-request invalidation stales the very entries the optimistic apply re-populates (stale-then-optimistic-fresh). Declaring both is a loud registration error (`:rf.error/mutation-optimistic-before-request`); optimistic plans use the default `:after-success` timing.
- **Per-call opt-out** `{:optimistic? false}` on `[:rf.mutation/execute …]` forces the pessimistic path for one call (a boolean disable, never a per-call forward plan). The `[:rf.mutation/state …]` sub exposes a derived `:optimistic?` boolean — true while a live optimistic apply is showing — so a view can render "pending, but showing my optimistic value."

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
- **Watching mutation state from a component to drive workflow.** The watcher-reaction idiom (a Form-3 lifecycle hook watching `[:rf.mutation/state …]` and dispatching when it goes successful) is **superseded** — use call-site **`:reply-to`**. The runtime owns when it accepted and settled the reply; let it produce the causal continuation. See [`patterns/stale-detection.md` §Post-mutation workflow](stale-detection.md).
- **Putting app workflow on `reg-mutation`.** Navigation, toasts, auth-slice writes belong in the `:reply-to` handler (workflow axis), not in `:populates`/`:invalidates` (cache axis). Keep the two axes apart.
- **Threading `db`/`ctx` into a `:populates`/`:invalidates` callback** to compute a scope. The callback signature is `(params result)`; derive db-relative scope through a named resolver reference `{:from-db …}`, which tooling can name and which gives descriptors a stable reference.
- **`:cross-scope? true` as the ergonomic mixed-scope path.** It is the audited escape for scopes the call site *can't* enumerate. When you can name the scopes, use per-target **descriptors** — `:cross-scope?` invalidates a tag across *every* viewer (a privacy-relevant broad op).
- **`:snapshot-db` on a logout/clear-scope payload.** There is no such key. Resolve the concrete old scope from the handler's coeffect db via `resolve-resource-scope`, before removing the user. A whole-db snapshot on an event is an egress-bearing record.
- **Hand-rolling optimistic cache writes + rollback** against the reserved mutation keys. Optimistic mutations ship — declare a `reg-mutation` `:optimistic` / `:optimistic-tags` plan and let the runtime record the inverse and commit / roll back / reconcile on settle (see [§Optimistic mutations](#optimistic-mutations-apply-before-the-reply)). A hand-written inverse drifts from the forward patch and `assoc`-es into framework-owned runtime-db.

## Worked examples

Two runnable Reagent examples ship the live runtime — read them side by side with the shapes above:

- **`examples/capabilities/resources/resources/`** — the focused **read-side** demo. Route-driven page load (`:resources` route metadata), event-driven `ensure` under an app `[:lease …]` owner with a release path, manual refresh as a *cause* (not an owner), and a machine-owned resource. Views read through passive `[:rf.resource/*]` subs; scope is the fail-closed leak boundary (an explicit `:rf.scope/global` claim). Read-side only.
- **`examples/real-apps/realworld_resources/`** — the **EP-0016 dogfood**: RealWorld (Conduit) on resources + mutations, end to end. It exercises the completion surface this skill teaches — call-site **`:reply-to`** continuations (settings save, article create/edit/delete, the social controls), per-target **scoped invalidation descriptors** (one favourite/save stales global article tags *and* the session-scoped `[:feed]`), **populate-as-authoritative-load**, and a **named `reg-resource-scope` resolver** (`:realworld/session`) referenced everywhere as `{:from-db :realworld/session}` — the feed resource's `:scope`, the home-route feed entry, the mutations' session invalidation descriptor, and (via `resolve-resource-scope`) logout's `clear-scope`. It is the sibling of the managed-HTTP `examples/real-apps/realworld_http/` (kept intact as the Spec 014 counterpart) — read the two together to see what resources buy you.

For the hand-rolled-slice shape Resources *supersedes* (the before-picture), `examples/real-apps/realworld_http/articles.cljs` is the closest reference (a slice-form Pattern-RemoteData over `[:articles]`).

## Pointers

- Spec: `SKILL-REDIRECT.md` → *EP — Resources (016)* (full identity rules, scope resolution, the lifecycle FSM, the work ledger, race/in-flight semantics, route integration, SSR/hydration, the `:rf.resource/*` and `:rf.mutation/*` surfaces).
- Transport: `SKILL-REDIRECT.md` → *EP — HTTP requests (014)* (the `:rf.http/managed` surface resources lower onto).
- Compose: [`patterns/remote-data.md`](remote-data.md) (the hand-rolled slice form, and the slice-vs-resource decision); [`patterns/stale-detection.md`](stale-detection.md) (the epoch idiom — resources own generation-based stale suppression internally, so you rarely hand-roll it for a resource read).
- Frames: every resource carries its explicit frame (EP-0002 — no ambient `:rf/default`); a frameless resource op fails closed with `:rf.error/no-frame-context`.

---

*Derived from `spec/016-Resources.md` (optional capability `day8/re-frame2-resources`) @ main. The first public-beta surface (read-resource MVP + `reg-mutation` + focus/reconnect revalidation) plus the EP-0016 completion surface (call-site `:reply-to`, per-target scoped invalidation descriptors, `reg-resource-scope` named resolvers, populate-as-authoritative-load + `:refetch-populated?`, map-form exact targets, managed-HTTP request decoration) are landed, as are optimistic mutations (`:optimistic` / `:optimistic-tags` / `:on-conflict` / per-call `:optimistic? false`), tag-addressed patching, and polling. GraphQL remains a deferred slice. Re-verify the surface after later resource slices land.*
