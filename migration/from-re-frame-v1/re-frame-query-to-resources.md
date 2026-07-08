# O-18. Convert `shipclojure/re-frame-query` queries (and hand-rolled Pattern-RemoteData caches) to re-frame2 resources (`:rf.resource/*`)

> **Type B** (semantic rewrite, ask first). The agent identifies every `defquery` / `ensure-query` / `useQuery`-style site and every hand-rolled server-state cache, surfaces the proposed `reg-resource` + `:rf.resource/*` shape per resource, and asks the operator to approve before applying. The rewrite is a domain re-modelling — query *keys* become a scoped *resource identity*, `invalidateQueries` becomes *tag invalidation*, and *component observers* become *active owners* — not a structural lift. Mechanical translation handles the common cases (a declarative query with a fixed key, a route/event-driven fetch, a passive subscription read, tag invalidation on a mutation); the hard cases — implicit shared cache scope, component-observer-driven GC, subscription-driven fetching, infinite queries, optimistic updates — escalate to a human.

> **Status.** Resources is a **post-v1 optional artefact** (`day8/re-frame2-resources`, [Spec 016](../../spec/016-Resources.md)). The read-resource surface (`reg-resource`, `:rf.resource/ensure` / `:rf.resource/refetch` / `:rf.resource/invalidate-tags` / `:rf.resource/release-owner` / `:rf.resource/clear-scope` / `:rf.resource/remove`, the `:rf.resource/*` subs, route `:resources`, SSR hydration) is the migration target, and so are **mutations** (`reg-mutation` / `clear-mutation` / `:rf.mutation/execute`, the instance-keyed causal-write counterpart) — both have **landed**. **Optimistic rollback** and **GraphQL** are deferred later phases. Where a source app leans on a not-yet-landed surface, the agent flags it and migrates the rest. This note is an opt-in modernisation, not a required rule, for the same reason [O-17](http-fx-to-managed-http.md) is: nothing in re-frame2 *breaks* a query lib, but the re-frame2 idioms (frames, route metadata, SSR, runtime/app-db partitioning, Xray, privacy egress) only reach server-state once it's expressed as resources.

> **Cross-references.** [O-17 (`http-fx` → managed HTTP)](http-fx-to-managed-http.md) — resources lower onto `:rf.http/managed`, so a query lib built on `http-fx` usually runs O-17 first (or in the same pass). [O-16 (`async-flow-fx` → `reg-machine`)](async-flow-fx-to-reg-machine.md) — a workflow that *owns* a query becomes a machine that owns a resource. Required-rule [M-31 (`day8/re-frame2-http`)](README.md#m-31-managed-http-spec-014-ships-in-a-separate-artefact--day8re-frame2-http) names the transport artefact resources depend on.

---

## Summary

Two source shapes converge on the same target.

**`shipclojure/re-frame-query`** ([repo](https://github.com/ProjectTITAN/re-frame-query)) is the important prior art inside the re-frame ecosystem — it proved demand for declarative server-state in re-frame and already had many of the right ideas: declarative queries and mutations, automatic success/failure wiring, tag invalidation with active-query refetch, per-query cache-time GC, loading-vs-background-refetch status, transport-agnostic effects, and — crucially — a *route-driven* mode (`ensure-query`, `mark-active`, `mark-inactive`, `query-state`) that fetches from a router or event and renders through a passive subscription. That route-driven convergence point is exactly the re-frame2 model.

**Hand-rolled [Pattern-RemoteData](../../spec/Pattern-RemoteData.md) caches** are the other source: an `app-db` map like `{:articles {"welcome" {:status :loading :data nil :error nil}}}`, an HTTP event that writes `:loading` then `:loaded`/`:error`, a sub that reads the slice, and — usually — *no* answer for staleness, dedupe, scope isolation, GC, or stale-reply suppression. These are the bugs resources exist to delete.

re-frame2 resources learn from `re-frame-query` without cloning it. The structural differences are the whole point of the migration:

- **Server-state lives in the runtime partition, not `app-db`.** The cache is at `:rf.runtime/resources` inside `:rf.db/runtime` — an ordinary `:db` handler cannot wipe it. (A hand-rolled cache in `app-db` *moves* to the runtime partition; you stop owning the slice.)
- **Subscriptions are pure passive reads; fetching is causal.** `re-frame-query`'s ergonomic subscription-driven mode is deliberately *not* the default — it weakens event causality and makes route behaviour harder to inspect. In re-frame2 a route, event, or machine *causes* the fetch; the view only reads.
- **Scope is a mandatory, fail-closed leak boundary.** A query key in `re-frame-query` is just the cache key. A re-frame2 resource identity is `[scope resource-id params]` — and `:scope` is required at registration. A user-scoped read that forgets to say so is a loud registration error, not a silent shared-cache leak between users/tenants.
- **Owners are liveness leases, not component observers.** `re-frame-query` ties liveness to mounted components (`mark-active` / `mark-inactive`). re-frame2 uses explicit owners — `[:route …]`, `[:machine …]`, `[:lease …]` — each with a named release authority, so liveness is a property of the data, not of which component happens to be mounted.

## Why the rewrite is opt-in

`re-frame-query` is an add-on lib; nothing in re-frame2 depends on it and nothing breaks when an app keeps it. A migrating app can:

1. Keep `re-frame-query` for the queries you don't convert — but not cost-free under EP-0018. The lib's own registrations use `reg-fx` / `reg-sub` / `subscribe` (all preserved), so the lib still loads; however any **event handler** that drives a query through the lib's effects is a `reg-event-fx` handler, and `reg-event-fx` is removed in v2 (a throwing stub naming `reg-event` — [M-73](README.md#m-73-one-event-registration-form-reg-event-db--reg-event-fx-removed-reg-event-ctx-demoted-ep-0018)), so those migrate to the one public `reg-event` regardless; or
2. Migrate query-by-query to resources as part of broader v2 modernisation.

The agent does NOT auto-rewrite — every query / cache site is surfaced for operator approval, because the rewrite is semantic. The agent SHOULD recommend (2) when the app is otherwise adopting re-frame2 idioms: resources integrate with frames, route `:resources` metadata, SSR hydration, the Xray route/resource graph + work-ledger + scope-audit surfaces, and the privacy/elision egress — none of which a query lib's opaque cache participates in.

## Detection

The agent looks for:

- Maven coord `shipclojure/re-frame-query` (or the `re-frame-query` namespace) in `deps.edn` / `project.clj` / `shadow-cljs.edn` / `bb.edn`.
- `(:require [...re-frame-query...])` and its surface: query *definitions* (`defquery` / `reg-query`-style), `ensure-query`, `mark-active` / `mark-inactive`, `query-state`, `invalidate-queries` / `invalidateQueries`-style calls, and mutation definitions.
- **Hand-rolled Pattern-RemoteData caches** (no lib): an `app-db` slice of `{<id> {:status … :data … :error …}}` shape, an HTTP event that writes load status transitions, a sub reading the slice, and the absence of staleness/dedupe/scope/GC logic. (This is the higher-value but lower-confidence detection — surface it as a *candidate* for the operator.)

Each query / cache slice maps to one `reg-resource`. The agent presents the source query, the proposed resource shape, and the route/event causal wiring for operator approval before any edit.

## `re-frame-query` → resources concept mapping

| `re-frame-query` / hand-rolled concept | re-frame2 resources concept | Notes |
|---|---|---|
| **Query key** (e.g. `[:article slug]`) | **Resource identity** `[scope resource-id canonical-params]` | The key splits three ways: the head becomes the `resource-id` (`:article/by-slug`); the variable parts become canonical **params** (`{:slug slug}`) validated by `:params-schema`; and a new, **mandatory `:scope`** is added (it had no equivalent — see the scope row). Maps are canonicalized so key order doesn't affect identity; host values are rejected at the boundary. |
| *(no equivalent)* | **`:scope` policy** (REQUIRED) | The load-bearing addition. `re-frame-query` has one implicit shared cache; re-frame2 forces an explicit scope policy at `reg-resource` — `:rf.scope/global` (an auditable claim), a resolver, or `:rf.scope/from-caller`. **A user/tenant/locale/impersonation-dependent query MUST migrate to an explicit scope, or move those values into params.** The rewrite MUST classify each query's scope intent and surface it for approval — a query that read `/me`-style or tenant-local data becomes a scoped resource, never `:rf.scope/global`. Omitting the policy is a loud `:rf.error/resource-missing-scope-policy`. |
| **Query fetch fn** (returns an effect / HTTP descriptor) | **`:request`** (returns a Spec 014 managed-HTTP args map) | The fetch fn lowers to `(fn [params ctx] {:request {…} :decode …})`. The args map MUST NOT supply `:request-id` / `:on-success` / `:on-failure` — the resource runtime supplies those from the scoped key + generation (that's how stale-suppression is wired). Transport-agnostic query libs lower to `:transport :rf.http/managed`, the only initial-scope transport. |
| **`ensure-query`** (route/event-driven fetch) | **`[:rf.resource/ensure {:resource :scope :params :owner :cause}]`** | The direct analogue — and the *recommended* path in both. Route-driven: declare `:resources` route metadata ([Spec 012](../../spec/012-Routing.md) / [Spec 016 §Route integration](../../spec/016-Resources.md#route-integration)) instead of calling `ensure-query` in an `:on-match`. Event-driven: dispatch `:rf.resource/ensure` with an explicit `:owner` + `:cause`. An ensure on an in-flight key **joins** the existing work (dedupe), as `ensure-query` did. |
| **Subscription-driven query** (the ergonomic `useQuery`-style mode — a sub that fetches) | **causal ensure + passive `[:rf/resource …]`** | **Intentional divergence.** re-frame2 does NOT ship subscription-driven fetching in v1 — a subscription is a pure passive read. The rewrite splits a fetching-sub into (a) a *cause* (route `:resources`, an event, or a machine action) that ensures, and (b) a passive `[:rf/resource {…}]` sub the view reads. The agent MUST flag every subscription-driven query and propose the cause/read split; this is the biggest behavioural change in the migration and needs operator sign-off. |
| **`query-state`** (status read) | **`[:rf/resource {…}]`** (+ narrower subs) | The view-model is richer and refined: `:loading` (first load, no data), `:fetching` (refresh in flight, prior data visible), `:loaded`, `:error` (first-load failure, no data), plus derived `:stale?` / `:has-data?` / `:refresh-error`. A background-refresh failure stays `:loaded` and keeps prior data (`:refresh-error` set) — it does NOT become `:error`. The rewrite MUST update views that treated any failure as a blanket error state. |
| **`mark-active` / `mark-inactive`** (component observers) | **active owners** + **`:rf.resource/release-owner`** | Liveness moves from "is a component mounted?" to explicit owner leases. A route owns `[:route route-id nav-token]` (released on route-leave by routing). A machine owns `[:machine actor-id]` (released on actor-destroy by the machine teardown — [Spec 016 §Release authority is per owner kind](../../spec/016-Resources.md#release-authority-is-per-owner-kind)). An app/event lease `[:lease …]` is **app-authoritative** — the event that mints it MUST have a matching `:rf.resource/release-owner` path (Xray lints an orphaned lease). A mounted-component observer typically becomes a route owner (if the component is a page) or an event-minted lease released on the feature's teardown event. |
| **`invalidate-queries` / `invalidateQueries`** (by key/tag) | **`[:rf.resource/invalidate-tags {:scope :tags :cause}]`** | Tag invalidation maps directly, with two refinements: tags are produced by a resource's `:tags` fn from its data (and *replaced* on each successful load, so stale list/detail relationships stop receiving invalidations); and **invalidation is scoped by default** — a cross-scope invalidation MUST opt in explicitly and is visible in Xray. Invalidate-by-exact-key becomes invalidate-by-tag. |
| **Per-query cache-time / GC** (`:cache-time` / `:gc-time`) | **`:gc-after-ms`** (+ `:stale-after-ms`) | `:stale-after-ms` controls when a `:loaded` entry is considered stale (orthogonal to load status) — absent, it **never** ages out on a timer (freshness stays explicit-invalidation-driven). `:gc-after-ms` controls inactive-entry GC — absent, it defaults to `300000` (5 min, matching TanStack's finite `gcTime` default); `:gc-after-ms :never` is the explicit, auditable opt-out for intentional unowned-entry pinning. GC re-checks owner sets + generation after wake; an entry with an active owner is not GC'd. |
| **Polling / `refetch-interval`** | *(deferred)* | Polling/interval revalidation is a deferred slice. Flag polling queries; the interim path is an app-level `:dispatch-later` re-ensure or keeping the lib for those queries until the slice lands. |
| **Conditional fetching** (`:enabled?` / skip) | **route `:when`** predicate, or an event-side guard | A route resource declares `:when (fn [route ctx] …)`; an event-side ensure simply isn't dispatched when the condition is false. Conditional resources use `:when` rather than sentinel `nil` params. |
| **Dependent queries** (query B depends on query A's data) | route `:id` + **`:after #{local-id}`** plan, or sequenced events | Dependent route resources are modelled as a route plan: a resource declares a local `:id`, a dependent declares `:after #{that-id}`. Xray shows the dependency and any waterfall. Outside routes, sequence the ensures via the event pipeline. |
| **Mutations** (`defmutation` + `invalidateQueries` on success) | **`reg-mutation`** + **`[:rf.mutation/execute …]`** (causal write, instance-keyed) | The direct analogue — **landed** ([Spec 016 §Mutations](../../spec/016-Resources.md#mutations-first-public-beta-gate)). A mutation lowers to `(reg-mutation :article/save {:params-schema … :invalidates (fn [params result] #{[:article slug]}) :scope …} (fn [params ctx] {:request {…} :decode …}))` — the request fn is the third positional arg and follows the resource rule (no `:request-id` / `:on-success` / `:on-failure`; runtime owns reply addressing). `invalidateQueries`-on-success becomes the success-time `:invalidates` tag set (composing with `:rf.resource/invalidate-tags`); `:patches` / `:populates` transform / seed cached entries *before* invalidation; `:invalidate-timing` is explicit (`:before-request` / `:after-success` (default) / `:after-failure` / `:after-settle`). **Invalidation is scoped, and a mutation's invalidate/patch/populate run against the mutation's *resolved* scope** — which, unlike a resource read, is **not** fail-closed and **defaults to `:rf.scope/global`** when omitted. So the rewrite MUST classify each migrated mutation's invalidation scope the same way it classifies the resources it invalidates: a write against user/tenant/locale-scoped resource entries MUST set `:scope` in `reg-mutation` (or pass `:scope` on `[:rf.mutation/execute …]`) to that same scope, or its `:invalidates` tags resolve under `[:rf.scope/global]` and **silently miss** the scoped entries the write just changed (stale reads, no error). A mutation left at the default global scope is only correct when the resources it touches are themselves `:rf.scope/global`. Run it with `[:rf.mutation/execute {:mutation :params :instance :scope :cause}]` and watch it through the passive instance-keyed `:rf.mutation/*` subs (`:rf/mutation` / `status` / `pending?` / `result` / `error`); `[:rf.mutation/clear {:instance …}]` is the causal instance reset (distinct from `clear-mutation`, the registration-lifecycle removal). State is keyed by **mutation instance** id, so concurrent submissions never clobber. **Write retries are OPT-IN** — `:retry` rides the Spec 014 managed-HTTP args the `:request` returns, never a `reg-mutation` spec key. Still **deferred**: **optimistic rollback** (forward-only `:patches`/`:populates` for now; the success trace reserves the snapshot/rollback shape). |
| **Infinite / paginated queries** | ordinary resources + `:keep-previous?` | Infinite queries are deferred; paginated/filtered lists are ordinary resources in v1 — put every filter/sort/page/cursor in params, tag both the list and item identities, and set `:keep-previous?` on the route/resource declaration to keep old data visible while the next page first-loads (projected via `:rf.resource/previous-data`, never inserted into the new entry's cache). |
| **Optimistic updates / rollback** | *(deferred)* | Optimistic rollback is a later slice — the mutation success trace reserves the snapshot/rollback shape but rollback itself is unimplemented. A landed mutation's `:patches` / `:populates` are **forward-only** (a saved entry appears in the cache before the refetch, but a failure does NOT auto-revert it). Flag optimistic-rollback sites; the interim path keeps the rollback app-level (or in the lib) until the slice ships. |
| **Process-global cache** | **per-frame runtime cache** | `re-frame-query`'s cache is process-global; re-frame2's lives per frame in runtime-db. This matters for SSR (request-local frames — a process-global cache would leak between users) and for multi-frame apps. The rewrite gets request-local SSR isolation for free. |

## Before / after — a representative route-driven query

A typical `re-frame-query`-style article fetch: a query keyed by slug, ensured on route entry, read through a passive sub, invalidated when an article is saved.

### Before — `re-frame-query` (illustrative shape)

```clojure
(ns my-app.articles
  (:require [re-frame.core :as rf]
            [re-frame-query.core :as q]))

;; A query: key, fetch descriptor, GC time.
(q/defquery :article
  {:query-fn  (fn [{:keys [slug]}]
                {:method :get :uri (str "/api/articles/" slug)})
   :gc-time   300000})

;; Route entry ensures the query (and marks it active for liveness).
(rf/reg-event-fx :route/article-entered
  (fn [_ [_ slug]]
    {:fx [[:q/ensure-query [:article {:slug slug}]]
          [:q/mark-active  [:article {:slug slug}]]]}))

;; The view reads query state through a passive sub.
(rf/reg-sub-raw :article
  (fn [_ [_ slug]] (q/query-state [:article {:slug slug}])))

;; Saving an article invalidates the cached query by key.
(rf/reg-event-fx :article/saved
  (fn [_ [_ slug]]
    {:fx [[:q/invalidate-queries [[:article {:slug slug}]]]]}))
```

### After — re-frame2 resources

```clojure
(ns my-app.articles
  (:require [re-frame.core :as rf]
            [re-frame.resources]                          ;; boots the artefact
            [re-frame.routing]))

;; A resource: REQUIRED scope policy, params schema, request, tags, GC.
;; This article read is the SAME for everyone → an explicit :rf.scope/global
;; claim (the rewrite must confirm this per query; a user/tenant-scoped read
;; gets a scope resolver instead).
(rf/reg-resource :article/by-slug
  {:params-schema  [:map [:slug :string]]
   :scope          :rf.scope/global
   :stale-after-ms 60000
   :gc-after-ms    300000
   :tags           (fn [{:keys [slug]} _data] #{[:article slug]})}
  (fn [{:keys [slug]} _ctx]
    {:request {:method :get :url (str "/api/articles/" slug)}
     :decode  :json}))

;; Route entry CAUSES the fetch declaratively — the route owns the resource
;; for the duration of the page (released on route-leave by the nav-token).
;; No mark-active/mark-inactive: liveness is the route owner.
(rf/reg-route :route/article
  {:params [:map [:slug :string]]
   :resources
   [{:resource :article/by-slug
     :params   (fn [route] {:slug (get-in route [:params :slug])})
     :blocking? true}]}
  "/articles/:slug")

;; The view reads it passively — no fetching, no observer registration.
(rf/reg-view article-page []
  (let [slug  (:slug @(rf/subscribe [:rf.route/params]))
        state @(rf/subscribe [:rf/resource
                              {:resource :article/by-slug :params {:slug slug}}])]
    (cond
      (:loading? state)                              [article-skeleton]
      (and (:error state) (not (:has-data? state)))  [article-error (:error state)]
      :else [:<> [article-view (:data state)]
                 (when (:fetching? state)     [refresh-indicator])
                 (when (:refresh-error state) [refresh-warning (:refresh-error state)])])))

;; Saving is a mutation: a causal WRITE that invalidates by TAG on success.
;; `:invalidates` declares the write→invalidate→refetch loop once — no
;; hand-wired "POST then dispatch invalidate" per form. `:scope` MUST match
;; the scope of the resources this write invalidates: this article read is
;; :rf.scope/global, so the write claims :rf.scope/global too. Unlike a
;; resource read, a mutation's scope is NOT fail-closed — omitting it
;; defaults to :rf.scope/global, so a write against scoped entries that
;; forgets :scope invalidates the WRONG (global) scope and silently misses
;; the entries it changed. The rewrite MUST classify this per mutation.
(rf/reg-mutation :article/save
  {:params-schema [:map [:slug :string]]
   :scope         :rf.scope/global
   :invalidates   (fn [{:keys [slug]} _result] #{[:article slug]})}
  (fn [{:keys [slug] :as article} _ctx]
    {:request {:method :put :url (str "/api/articles/" slug) :body article}
     :decode  :json}))

;; A SCOPED mutation, by contrast. If the note were tenant-scoped, the write
;; MUST carry that same scope or its invalidation lands in the wrong (global)
;; cache and the tenant's stale read is never refreshed. A mutation's scope
;; resolves payload-:scope → spec-:scope → :rf.scope/global (no fail-closed
;; gate, no `:rf.scope/from-caller` policy — that is a *resource*-read enum,
;; not a mutation one). So when the principal is known only at the call site,
;; OMIT :scope from reg-mutation and ALWAYS pass :scope on the execute
;; payload (it takes precedence). Leaving the default global scope on a write
;; that touches scoped entries is the silent-miss bug this guidance exists to
;; prevent.
(rf/reg-mutation :note/save
  {:params-schema [:map [:tenant-id :string] [:id :string]]
   ;; No static :scope — the tenant principal is supplied at execute time.
   :invalidates   (fn [{:keys [id]} _result] #{[:note id]})}
  (fn [{:keys [tenant-id id] :as note} _ctx]
    {:request {:method :put :url (str "/api/" tenant-id "/notes/" id) :body note}
     :decode  :json}))
;; …executed with the SAME scope the resource was ensured under:
;;   [:rf.mutation/execute {:mutation :note/save :params note
;;                          :instance :form/note-save
;;                          :scope    [:rf.scope/session {:tenant-id "acme"}]
;;                          :cause    [:form-submit :note/save]}]

;; The form fires the write and watches it through instance-keyed subs.
(rf/reg-view article-form [article]
  (let [{:keys [pending? error?]} @(rf/subscribe [:rf/mutation {:instance :form/article-save}])]
    [:button {:disabled pending?
              :on-click #(rf/dispatch [:rf.mutation/execute
                                       {:mutation :article/save
                                        :params   article
                                        :instance :form/article-save
                                        :cause    [:form-submit :article/save]}])}
     (if pending? "Saving…" "Save")]))
```

What changed, and why it's better:

- **`mark-active` / `mark-inactive` disappeared.** The route owns the resource via its nav-token; route-leave releases it. Liveness is data, not a mounted-component side effect.
- **A scope policy is now explicit.** This article is genuinely global, so it says `:rf.scope/global` — an auditable claim Xray enumerates. A session-dependent read would carry a scope resolver instead, and forgetting would have been a loud registration error.
- **The view distinguishes first-load failure from a stale-data refresh warning** — `:loading?` / `:error` + `:has-data?` / `:fetching?` / `:refresh-error` — instead of a single blanket error state.
- **Invalidation is by tag and scoped by default**, and the cache lives in the runtime partition where an ordinary `:db` handler can't corrupt it.
- **The write is a mutation, not a hand-wired event.** `reg-mutation` declares the write→invalidate→refetch loop once via `:invalidates`; the form fires `[:rf.mutation/execute …]` and watches an instance-keyed `:rf.mutation/*` sub for `:pending?` / `:error?`. Concurrent submissions keep distinct instance rows and never clobber.

## Migrating a hand-rolled Pattern-RemoteData cache

A hand-rolled cache has *more* to delete than a query lib, because the lib already solved some of it. The rewrite:

1. **Move the slice out of `app-db`.** The `{<id> {:status … :data …}}` map and the events/subs that maintain it are replaced by `reg-resource` + the `:rf.resource/*` surface; the cache moves to runtime-db. Delete the bespoke status-transition event.
2. **Add the scope policy** — the hand-rolled cache almost certainly had no scope concept and was a latent cross-user leak. Classify and add `:scope`.
3. **Replace the bespoke loading FSM** with the resource status semantics — and audit any view that treated all failures as `:error` (a background-refresh failure now keeps data and surfaces `:refresh-error`).
4. **Adopt dedupe / stale / GC / stale-reply suppression** — these were almost certainly missing or buggy in the hand-roll; they're now the runtime's job. In particular the **stale-reply suppression** (a late reply for a superseded request can never mutate a newer entry, by generation check) deletes a whole class of race bugs.
5. **Wire causality** — route `:resources` or an event-side ensure with an explicit owner; the view becomes a passive `[:rf/resource …]` read.

## Acceptance

- Every migrated query / cache renders through a **passive** `[:rf.resource/*]` subscription — no subscription-driven fetching survives (or each surviving one is explicitly operator-approved).
- Every resource declares an **explicit `:scope`** policy, and every previously user/tenant/locale-dependent query is scoped (or its scoping values moved into params) — none silently `:rf.scope/global`.
- Liveness is expressed as **owners** with a matching release path (route nav-token, machine instance, or an app lease + `:rf.resource/release-owner`); no `mark-active`/`mark-inactive` observer survives.
- Invalidation is **tag-based and scoped**; cross-scope invalidations are explicit.
- Every migrated mutation is a **`reg-mutation`** whose `:request` follows the resource rule (no `:request-id` / `:on-success` / `:on-failure`), whose success-time `invalidateQueries` is expressed as `:invalidates` tags (with `:patches` / `:populates` where a write should update the cache before refetch), and that runs through `[:rf.mutation/execute …]` watched by an **instance-keyed** `:rf.mutation/*` sub — concurrent submissions never clobber, and write retries are opt-in (carried in the Spec 014 args the `:request` returns, never a `reg-mutation` spec key).
- Every migrated mutation's **invalidation scope is classified** to match the resources it invalidates: a write against user/tenant/locale-scoped entries sets `:scope` in `reg-mutation` (static principal) or passes `:scope` on `[:rf.mutation/execute …]` (call-site principal) to that scope — none left at the implicit `:rf.scope/global` default unless the resources it touches are themselves `:rf.scope/global`. (A mutation's scope is **not** fail-closed; an unscoped write against scoped entries silently invalidates the wrong cache.)
- Deferred surfaces (optimistic rollback, polling, infinite queries, GraphQL) are **flagged in the report**, not silently dropped — each either kept on the source lib for now or sequenced for the relevant slice. (Mutation `:patches` / `:populates` are forward-only until optimistic rollback lands.)
