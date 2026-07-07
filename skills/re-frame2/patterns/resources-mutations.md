# Pattern — Resource Mutations

The **write** counterpart to [Pattern-Resources](resources.md): `reg-mutation` registers a named WRITE that, on success, patches / populates / invalidates the cached reads [`resources.md`](resources.md) owns. Keyed by **instance id**, so two concurrent submissions never clobber each other. This leaf covers the mutation surface — declaration, the workflow-vs-cache split, `:reply-to`, scoped invalidation descriptors, exact-target populate/patch/removes, and optimistic plans. The read side (`reg-resource`, scope resolution, `ensure` / owners / causes) lives in [`resources.md`](resources.md).

> **Mental-model anchor:** `reg-mutation` + `[:rf.mutation/execute …]` is re-frame2's **`useMutation`**; call-site **`:reply-to`** is its **`onSuccess`** — but the continuation is a *causal event target*, not a callback (the runtime dispatches your event with a reply map after cache consequences settle, so it lands on the event tape: replayable, traced, interceptor-visible). The full TanStack / RTK-Query mapping is in [`resources.md`](resources.md)'s mental-model table.

**Optional capability — `day8/re-frame2-resources` (Spec 016).** Mutations ship with Resources; `(rf/feature-loaded? :resources)` answers whether the artefact is on the classpath.

## When to load

The prompt mentions: a **mutation** that updates cached reads, "navigate / show a toast / update state **after** a write succeeds" (mutation completion / `:reply-to` workflow continuation — the `onSuccess` shape), invalidating facts in **different scopes** from one write (per-target descriptors), an **optimistic** update (a heart flips before the reply lands), or "seed the detail entry from the reply" (populate). Load [`resources.md`](resources.md) alongside for the read-side cache these writes keep coherent.

## Mutations — the causal write counterpart

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

The mutation request fn returns a managed-HTTP args map but MUST NOT supply `:request-id` / `:on-success` / `:on-failure` — the runtime owns reply addressing and stale suppression. Cross-cutting transport concerns (auth headers, retry) live in the managed-HTTP decoration seam that resource reads use too — see [`resources.md` §Request decoration](resources.md#request-decoration--auth-headers-retry-the-managed-http-seam).

### Two axes a mutation carries: cache consequences vs workflow

A write has two jobs, and re-frame2 keeps them on separate axes — the single rule that organises everything below:

| Axis | Where it lives | Examples |
|---|---|---|
| **Cache consequences** — keep the cache coherent | **declarative on `reg-mutation`**: `:patches` / `:populates` / `:invalidates` | refresh the article list after a save; seed the detail entry from the reply |
| **App workflow** — what the *app* does next | **call-site `:reply-to`** on `[:rf.mutation/execute …]` | navigate to the new slug; update the auth slice; show a toast; fold validation errors |

Mantra: **`:reply-to` is for workflow; populate / patch / invalidate are for cache.** This is re-frame2's divergence from TanStack/RTK-Query's `onSuccess` callback — the continuation is a causal *event*, not a callback.

## Mutation completion — `:reply-to` (workflow continuation)

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

Use this instead of the watcher-reaction idiom (watch `[:rf.mutation/state …]` from a component lifecycle hook, dispatch when it goes successful) — see [`patterns/stale-detection.md` §Post-mutation workflow](stale-detection.md). There is **no** registration-level `:reply-to` on `reg-mutation`: invariant workflow is spelled by every call site passing the same target; the cache plan covers the declarative half.

## Per-target scoped invalidation (descriptors)

Bare shorthand stays valid — it means *invalidate those tags in the mutation's resolved scope* (`:rf.scope/same`):

```clojure
:invalidates #{[:article slug] [:article-list]}
```

But one write often touches facts in **different scopes** — a global article fact *and* the viewer's session-scoped feed — which the bare form cannot say. (Tags name remote *facts*; scopes name *viewers*; stale is a property of a `(fact, viewer)` pair.) The **descriptor form** gives each target its own scope:

```clojure
:invalidates
(fn [{:keys [slug]} _result]
  [{:scope :rf.scope/global                  ;; global facts
    :tags  #{[:article slug] [:article-list]}}
   {:scope {:from-db :session}              ;; viewer-relative fact (named resolver — see resources.md)
    :tags  #{[:feed]}}])
```

A descriptor `:scope` may be: `:rf.scope/same` (the mutation's resolved scope — the default if omitted, and the meaning of bare shorthand); `:rf.scope/global`; a concrete canonical scope value; or a named-resolver reference `{:from-db <resolver-id>}` (resolved against db at settle time — [`resources.md` §Named scope resolvers](resources.md#named-scope-resolvers--reg-resource-scope)). Both forms lower to **one** invalidation engine.

The fail-closed lattice: a bare `invalidate-tags` with **no scope** is a loud error (`:rf.error/resource-invalidate-scope-required`); **descriptors** are the precise path; **`:cross-scope? true`** is the *only* scope-agnostic opt-out — the explicit, audited escape for "invalidate this tag in *every* scope holding it" (admin tooling, cache-poisoning response), which MUST carry `:cause` and is lintable in Xray as a privacy-relevant broad operation. Reach for a descriptor, not `:cross-scope?`, whenever you can name the scopes.

## Map-form exact targets — populate / patch / removes

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

## Optimistic mutations (apply before the reply)

When a write's effect should show *the instant the user clicks* (heart flips, count increments, card disappears), declare an **optimistic plan**. The runtime applies it to the cache *before* the request is sent, records a truthful inverse, and deterministically **commits** / **rolls back** / **reconciles** on settle. re-frame2's analogue of TanStack Query's `onMutate` + rollback context, but the inverse is **runtime-recorded, not author-written** — so it can never drift from the forward patch.

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
- **`:on-conflict`** governs a rollback when a competing write moved the entry's revision since the apply: **`:invalidate`** (default, recommended) marks the entry stale and lets the read path refetch the authoritative value — re-frame2's divergence from TanStack/SWR's unconditional context restore; **`:force`** restores the (possibly stale) inverse anyway (single-writer last-write-wins) with a tooling warning. An out-of-enum value is a loud `reg-mutation` error.
- **Scope is fail-closed.** An optimistic `{:from-db …}` target that resolves nil is **dropped** (`:target-unresolved`), never an implicit global write — the same leak boundary a read has. There is no `:cross-scope?` optimistic form by construction: optimistic patching is exact-key or tag-within-named-scope only.
- **Incompatible with `:invalidate-timing :before-request`** — a before-request invalidation stales the very entries the optimistic apply re-populates (stale-then-optimistic-fresh). Declaring both is a loud registration error (`:rf.error/mutation-optimistic-before-request`); optimistic plans use the default `:after-success` timing.
- **Per-call opt-out** `{:optimistic? false}` on `[:rf.mutation/execute …]` forces the pessimistic path for one call (a boolean disable, never a per-call forward plan). The `[:rf.mutation/state …]` sub exposes a derived `:optimistic?` boolean — true while a live optimistic apply is showing — so a view can render "pending, but showing my optimistic value."

## Anti-patterns

- **Watching mutation state from a component to drive workflow.** Do not use the watcher-reaction idiom (a Form-3 lifecycle hook watching `[:rf.mutation/state …]` and dispatching when it goes successful) — use call-site **`:reply-to`**. The runtime owns when it accepted and settled the reply; let it produce the causal continuation. See [`patterns/stale-detection.md` §Post-mutation workflow](stale-detection.md).
- **Putting app workflow on `reg-mutation`.** Navigation, toasts, auth-slice writes belong in the `:reply-to` handler (workflow axis), not in `:populates`/`:invalidates` (cache axis). Keep the two axes apart.
- **Threading `db`/`ctx` into a `:populates`/`:invalidates` callback** to compute a scope. The callback signature is `(params result)`; derive db-relative scope through a named resolver reference `{:from-db …}`, which tooling can name and which gives descriptors a stable reference.
- **`:cross-scope? true` as the ergonomic mixed-scope path.** It is the audited escape for scopes the call site *can't* enumerate. When you can name the scopes, use per-target **descriptors** — `:cross-scope?` invalidates a tag across *every* viewer (a privacy-relevant broad op).
- **Hand-rolling optimistic cache writes + rollback** against the reserved mutation keys. Optimistic mutations ship — declare a `reg-mutation` `:optimistic` / `:optimistic-tags` plan and let the runtime record the inverse and commit / roll back / reconcile on settle (see [§Optimistic mutations](#optimistic-mutations-apply-before-the-reply)). A hand-written inverse drifts from the forward patch and `assoc`-es into framework-owned runtime-db.

## Worked example

**`examples/real-apps/realworld_resources/`** — the RealWorld (Conduit) app on resources + mutations, end to end. Exercises the completion surface this leaf teaches — call-site **`:reply-to`** continuations, per-target **scoped invalidation descriptors** (one favourite/save stales global article tags *and* the session-scoped `[:feed]`), **populate-as-authoritative-load**, and a **named `reg-resource-scope` resolver** (`:realworld/session`) referenced everywhere as `{:from-db :realworld/session}`. Sibling of the managed-HTTP `examples/real-apps/realworld_http/` (the Spec 014 counterpart) — read both to see what resources buy you.

## Pointers

- Read side: [`resources.md`](resources.md) (the cache these writes keep coherent — `reg-resource`, scope resolution, `ensure` / owners / causes, `:rf.resource/invalidate-tags`).
- Spec: `SKILL-REDIRECT.md` → *EP — Resources (016)* (the `:rf.mutation/*` surface, the work ledger, race/in-flight semantics, optimistic settle).
- Workflow after a write: [`patterns/stale-detection.md` §Post-mutation workflow](stale-detection.md) (why `:reply-to` beats the watcher-reaction idiom).

---

*Derived from `spec/016-Resources.md` (optional capability `day8/re-frame2-resources`) @ main. Re-verify the surface after later resource slices land.*
