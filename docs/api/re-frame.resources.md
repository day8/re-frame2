# re-frame.resources

Use resources to load server data, cache it and read it from views. You register a read once, with its params schema, scope and request; an event, route or machine then dispatches `[:rf.resource/ensure …]` to load it, and views read the cached result through the `[:rf/resource …]` subscription, which never fetches. The runtime deduplicates concurrent requests, tracks staleness, refetches after invalidation, garbage-collects entries nothing owns and hydrates the cache after SSR; a mutation is the matching write, which invalidates or patches the reads it changed.

Use a resource when the same server data is read from more than one place, or needs caching, deduplication, staleness or a refetch after a write; use a mutation for a write that changes such data. A request whose reply one event handles, such as a login or a one-off load you keep in your own `app-db`, needs neither: issue [`:rf.http/managed`](re-frame.http.md) directly.

Resources ship in the optional `day8/re-frame2-resources` artefact and use managed HTTP (`day8/re-frame2-http`) as their transport, so require both `re-frame.resources` and `re-frame.http.managed` once at boot; without the resources artefact, `rf/reg-resource` and the other resource functions throw `:rf.error/resources-artefact-missing`, and without `re-frame.http.managed` the first load or write throws `:rf.error/http-artefact-missing`.

```clojure
(:require [re-frame.core :as rf]
          [re-frame.http.managed]   ;; registers the :rf.http/managed transport
          [re-frame.resources])     ;; registers the resource events, subs and runtime
```

```clojure
;; cf. examples/capabilities/resources/resources/core.cljs

;; Register the read once, at boot.
(rf/reg-resource :article/by-slug
  {:params-schema [:map [:slug :string]]
   :scope         :rf.scope/global}
  (fn [{:keys [slug]} _ctx]
    {:request {:method :get :url (str "/api/articles/" slug)}
     :decode  :json}))

;; Load it from an event. The owner keeps the entry alive until it is released.
(rf/reg-event :article/preview-opened
  (fn [_ [_ slug]]
    {:fx [[:dispatch [:rf.resource/ensure
                      {:resource :article/by-slug
                       :params   {:slug slug}
                       :owner    [:article/preview-opened slug]
                       :cause    [:event :article/preview-opened]}]]]}))

(rf/reg-event :article/preview-closed
  (fn [_ [_ slug]]
    {:fx [[:dispatch [:rf.resource/release-owner
                      {:owner [:article/preview-opened slug]}]]]}))

;; Read it from a view. The subscription never fetches.
(rf/reg-view article-preview [slug]
  (let [state @(subscribe [:rf/resource {:resource :article/by-slug
                                         :params   {:slug slug}}])]
    (cond
      (:loading? state)                             [:p "Loading…"]
      (and (:error state) (not (:has-data? state))) [:p.error "Could not load the article."]
      :else                                         [:h2 (:title (:data state))])))
```

For page data, a route's `:resources` key is the usual cause: entering the route ensures each resource with the route as owner, and leaving releases it. Each entry names its `:resource` and may add `:params` `(fn [route] params)`, `:blocking?`, `:keep-previous?`, `:when` `(fn [route ctx] bool)`, a `:scope` override, and `:id` / `:after` to order the ensures; [`reg-route`](re-frame.routing.md#route-resources) documents them.

Three terms recur on this page:

- **Scope** says whose data an entry holds. The cache key, the *scoped key*, is `[scope resource-id canonical-params]`, so one user's entry is never served to another. Every resource declares a scope policy; see [Scope policy](#scope-policy).
- An **owner** is a value you choose, such as `[:article/preview-opened slug]`, that stands for something needing the entry: a route visit, a machine, an open panel. An owned entry is kept, and invalidation, focus and polling refetch it; once its last owner is released, it is garbage-collected within [`:gc-after-ms`](#the-resource-spec). A route releases its owner when you leave it, and a machine actor that ensures with the owner `[:machine actor-id]` has it released when the actor is destroyed. Every other owner needs a matching [`[:rf.resource/release-owner …]`](#rfresourcerelease-owner-). An `ensure` with no owner still loads, but nothing keeps the entry.
- A **cause** is a value saying why a load or write happened, such as `[:event :article/preview-opened]`. It is recorded in traces and Xray and has no other effect.

Each fetch of a scoped key starts a new *generation*, and each request attempt has a *work id*. A reply is written only while both still match the entry, so a late reply from a superseded, cancelled or removed request is discarded. This page calls that stale-reply suppression.

`reg-resource`, `reg-mutation`, `reg-resource-scope`, `resolve-resource-scope`, `resource-state` and `mutation-state` are called through the `re-frame.core` facade as `rf/…` (the three `reg-*` forms are macros there), and registrations are removed with `rf/clear`. Everything else is a keyword-addressed event or subscription.

[The model](../resources/concepts.md) teaches owners, causes, scope and invalidation.

## Registration

### `reg-resource`

- **Kind**: macro (`rf/reg-resource`); also a function, `re-frame.resources/reg-resource`
- **Signature**:
  ```clojure
  (reg-resource resource-id metadata request-fn) → resource-id
  ```
- **Description**: Registers a resource: a cached read that events load and subscriptions read. Returns `resource-id`.
    - `metadata` is the registration map: the required `:scope` and `:params-schema`, plus the optional keys in [The resource spec](#the-resource-spec). A non-map `metadata` raises `:rf.error/resource-bad-spec`, as does a `:request` key inside it; the request fn belongs in the third slot.
    - `request-fn` is `(fn [params ctx] …)` and returns a [managed-HTTP args map](re-frame.http.md). `params` are the canonical params. `ctx` is `nil`, except on an [infinite resource](#infinite-resources), where it carries `:rf.resource/page-param` and `:rf.resource/page-index` for the page being fetched.
    - Validates the combined spec (`:scope` first, then `:params-schema`), then writes a `:resource`-kind registrar entry.
    - The spec stored under `:rf/resource` is the metadata map with `:request` added ([Reading registrations](#reading-registrations)).
    - See [Register a resource](../resources/concepts.md#register-a-resource).
- **Example**:
  ```clojure
  (rf/reg-resource :article/by-slug
    {:doc "Article detail by slug."

     :params-schema [:map [:slug :string]]      ;; required: validates and canonicalizes params
     :data-schema   :app/article                ;; shape declaration for tooling; runtime validation is :decode

     :scope          :rf.scope/global            ;; required: an explicit, auditable claim
     :transport      :rf.http/managed            ;; the only transport
     :stale-after-ms 60000
     :gc-after-ms    300000
     :poll-interval-ms 5000                      ;; optional: refetch every 5s while owned and visible
     :tags           (fn [{:keys [slug]} _data] #{[:article slug]})
     :sensitive?     false}

    ;; required request fn (third argument): returns a managed-HTTP args map
    (fn [{:keys [slug]} _ctx]
      {:request {:method :get :url (str "/api/articles/" slug)}
       :decode  :app/article}))
  ```

#### The resource spec

**Required keys**:

| Key | Notes |
|---|---|
| `:params-schema` | Validates and canonicalizes params. The canonical params identify the cache entry. Validation runs when `re-frame.schemas` is loaded; without it, params are canonicalized but not checked. Params must be portable EDN, so a fractional number fails: send `9.99` as `999` (cents) or `"9.99"`. Instants are accepted. An omitted `:params` is `{}`; an explicit `nil` is validated as `nil`. A spec without `:params-schema` raises `:rf.error/resource-bad-spec`. |
| `:scope` | The scope policy: `:rf.scope/global` or `{:from-db <resource-scope-id>}` (see [Scope policy](#scope-policy)). Any other value, or none, raises `:rf.error/resource-missing-scope-policy`. |
| request fn (third argument) | For `:transport :rf.http/managed`, returns a [managed-HTTP args map](re-frame.http.md). It must be a fn (or a Var), or registration raises `:rf.error/resource-bad-spec`. It must not supply `:request-id`, `:on-success` or `:on-failure`: the runtime supplies those from the scoped key and generation, and supplying one raises `:rf.error/resource-reserved-request-key`. |

**Optional keys**:

- `:doc`
- `:data-schema` — a static declaration of the decoded data's shape, shown to tooling (the resource's `:schema` fact) and in the `:rf/resource` registration read. It is not checked at runtime; validate a response with the request's `:decode`.
- `:transport` — `:rf.http/managed`, the only transport and the default. Any other value registers, then raises `:rf.error/resource-unknown-transport` at the first load.
- `:stale-after-ms` — how long a loaded entry stays fresh. Absent means it never goes stale.
- `:gc-after-ms` — the interval of the GC check that removes an entry with no owner and no load in flight. The check's timer is armed when a load settles, not when the last owner is released. When it fires on an entry that is still owned or loading, it re-arms for another interval. So an idle entry is removed at most this long after its last owner is released, and possibly sooner. Absent defaults to `300000` (5 minutes); `:never` keeps it. Otherwise it must be a positive number of milliseconds; `nil`, `0` or any other value raises `:rf.error/resource-bad-spec`.
- `:poll-interval-ms` — the active-owner poll interval. See [Polling](#polling). A value that is neither a number nor `nil` raises `:rf.error/resource-bad-spec`.
- `:timeout-ms` — stamps each fetch's work-ledger record with `:deadline-at`, its `:started-at` plus this many milliseconds, which Xray's Resources panel shows. It is recorded, not enforced: nothing cancels a fetch at its deadline. Bound the request itself with the `:timeout-ms` of the managed-HTTP args your request fn returns.
- `:tags` — `(fn [params data] → #{tag …})`, the tags that [`invalidate-tags`](#rfresourceinvalidate-tags-) and mutations match.
- `:infinite`, plus the infinite-only keys `:next-page-param`, `:prev-page-param`, `:page->items`, `:initial-page-param` and `:refetch`. See [Infinite resources](#infinite-resources). `:infinite` takes only the literal `true`; any other value raises `:rf.error/resource-bad-spec`. There is no `:page-data-schema`; supplying one raises the same error.
- `:sensitive?` / `:large?` — classify the whole entry. The SSR hydration payload withholds such an entry entirely, so neither its data nor its scope and params ride, and the client loads it again if its route still needs it. Off-box trace and epoch exports replace its scope and params with opaque tokens. The same properties on a schema affect only how validation failures are redacted.
- `:sensitive` / `:large` — per-path classification: a vector of paths rooted at `:data`, `:params` or `:scope` (a bare path means `:data`), e.g. `{:sensitive [[:data :ssn]]}`. A malformed declaration raises `:rf.error/resource-bad-spec`.

No other key changes how a resource loads or caches. [Coming from TanStack Query](../resources/coming-from-tanstack-query.md) maps TanStack options such as `select` and `placeholderData`. The mutation keys (`:invalidates`, `:patches`, `:populates`, `:removes`, `:optimistic`, `:optimistic-tags`, `:on-conflict`) belong on [`reg-mutation`](#reg-mutation).

### Scope policy

`:scope` is required and takes one of two shapes:

| Policy | Meaning |
|---|---|
| `:rf.scope/global` | The same params return the same data for every user, tenant, permission set, locale and impersonation state. Declaring it is an explicit claim that tooling can audit. |
| `{:from-db <resource-scope-id>}` | Compute the scope from `app-db` at use time with a resolver registered by [`reg-resource-scope`](#reg-resource-scope). The resolver declares its `:inputs`, so tooling can show the derivation without running it. |

Anything else is a registration error: an app-namespaced keyword, a literal tuple, map or string, a fn, a misspelt `:rf.scope/*` keyword, or no `:scope` at all. A scope known only at the call site is modelled as `{:from-db …}` over an `app-db` slot the caller writes first, or passed as a concrete `:scope` at each use site.

The registered policy is the default. A route entry, subscription payload or event payload that omits `:scope` uses it; pass `:scope` at a use site only when that site reads as a different principal (an admin reading tenant X).

- Events resolve payload `:scope`, then the route entry's `:scope`, then the registered policy. A `{:from-db <id>}` reference that resolves to `nil` at an event raises `:rf.error/resource-scope-unresolved-reference`.
- A route entry's `:scope` is a concrete value or a `{:from-db <id>}` reference, never a function. At a route, a scope that cannot be resolved (a function or `nil` on the entry, or a reference on the entry or the registered policy that resolves to `nil`) fails the route's resource plan and never falls through to another tier: [`:rf.route/error`](re-frame.routing.md#subscriptions) reads `:rf.error/resource-route-plan`, with the original error's data under `:cause`.
- Subscriptions resolve payload `:scope`, then the registered policy. A `{:from-db …}` reference that resolves to `nil` raises `:rf.error/resource-sub-unresolved-scope`; the subscription never reads global data or returns `:idle` instead.

See [Scope: whose cache?](../resources/concepts.md#the-scoped-key-a-leak-boundary-that-fails-closed) in the guide.

### `reg-mutation`

- **Kind**: macro (`rf/reg-mutation`); also a function, `re-frame.resources/reg-mutation`
- **Signature**:
  ```clojure
  (reg-mutation mutation-id metadata request-fn) → mutation-id
  ```
- **Description**: Registers a mutation: a named write to the server that, on success, invalidates, patches, populates or removes cached resource entries. Returns `mutation-id`. Run it with [`[:rf.mutation/execute …]`](#rfmutationexecute-).
    - `metadata` is the registration map: `:params-schema`, `:invalidates`, `:patches`, `:doc` and the other keys in [The mutation spec](#the-mutation-spec). A non-map `metadata` raises `:rf.error/mutation-bad-spec`, as does a `:request` key inside it.
    - `request-fn` is `(fn [params ctx] …)` and returns the [managed-HTTP args](re-frame.http.md) for the write; `ctx` is `nil`. Writes use the same `:rf.http/managed` transport as reads, and the runtime addresses replies and suppresses stale ones the same way.
    - Runtime state is keyed by mutation instance id, so concurrent submissions of the same mutation keep separate rows.
    - Validates the combined spec and writes a `:mutation`-kind registrar entry. The spec stored under `:rf/mutation` is the metadata map with `:request` added.
- **Example**:
  ```clojure
  (rf/reg-mutation :article/save
    {:params-schema :app/article          ;; required: validates and canonicalizes params
     :scope        :rf.scope/global       ;; the cache scope the arms below target by default

     ;; On success, in this order: patches, populates, removes, then invalidates.
     ;; Patch transforms an entry's existing data: (fn [old-data result] → new-data).
     :patches      (fn [{:keys [slug]} _result]
                     {{:resource :articles/list :params {}}
                      (fn [articles result]
                        (mapv #(if (= slug (:slug %)) (assoc % :title (:title result)) %)
                              articles))})
     ;; Populate seeds an entry straight from the reply (the server returns the saved article).
     :populates    (fn [{:keys [slug]} result]
                     {{:resource :article/by-slug :params {:slug slug}} result})
     ;; Invalidate marks the other reads carrying these tags stale.
     :invalidates  (fn [{:keys [slug]} _result] #{[:article slug]})
     :invalidate-timing :after-success}   ;; | :before-request | :after-failure | :after-settle

    ;; required request fn (third argument): a managed-HTTP write
    (fn [{:keys [slug] :as article} _ctx]
      {:request {:method :put :url (str "/api/articles/" slug) :body article}
       :decode  :app/article}))
  ```

#### The mutation spec

**Required keys**:

| Key | Notes |
|---|---|
| `:params-schema` | Validates and canonicalizes the write's params. A spec without it raises `:rf.error/mutation-bad-spec`. |
| request fn (third argument) | Returns the [managed-HTTP args map](re-frame.http.md) for the write. It must be a fn (or a Var), or registration raises `:rf.error/mutation-bad-spec`. It must not supply `:request-id`, `:on-success` or `:on-failure`: the runtime supplies those from the instance and generation, and supplying one raises `:rf.error/resource-reserved-request-key`. |

A *target* names one cache entry as `{:resource <id> :params <params> :scope <scope>}`. Its `:scope` is a concrete scope, `:rf.scope/global` or a `{:from-db <id>}` reference, and defaults to the mutation's resolved scope (`:rf.scope/same`).

**Optional keys**:

- `:invalidates` — `(fn [params result] → tags-or-descriptors)`, what to mark stale on success. The runtime applies it as a scoped `:rf.resource/invalidate-tags`. Return either:
    - a tag set, e.g. `#{[:article slug]}`, invalidated in the mutation's resolved scope; or
    - one descriptor map or a vector of them, `{:scope … :tags #{…}}`, each naming its own scope (the same scope forms as a target, above), so one write can invalidate both global and per-user reads. A descriptor may set `:cross-scope? true` to invalidate the tags in every scope, and `:refetch-populated? true` so this invalidation also refetches keys the same mutation populated, which otherwise stay fresh; use it when the reply carries only part of the record.

    Any other return value raises `:rf.error/mutation-invalid-invalidation` when the write settles, before anything is invalidated. `result` is `nil` when `:invalidate-timing` runs `:invalidates` before the request or after a failure.
- `:patches` — `(fn [params result] → {target patch-fn})`, where each `patch-fn` is `(fn [old-data result] → new-data)`. It transforms an entry that already has data, with the same structural sharing as the read path; a target with no data is left alone.
- `:populates` — `(fn [params result] → {target value})`. It seeds the entry as if `value` had just loaded, so `value` must have the resource's stored shape.
- `:removes` — `(fn [params result] → [target …])`, entries to remove on success. A removed entry's in-flight request is aborted where possible.
- `:scope` — the default cache scope for the arms above (see **Scope** below).
- `:invalidate-timing` — when `:invalidates` runs: `:after-success` (default), `:before-request` (before the request is sent), `:after-failure` (only when the write fails) or `:after-settle` (either way). Any other value raises `:rf.error/mutation-bad-spec`.
- `:transport` (`:rf.http/managed`, the only transport; any other value raises `:rf.error/resource-unknown-transport` when the write runs), `:doc`.
- `:sensitive` / `:large` — per-path classification of the instance row, in the same shape as on `reg-resource`: `[:params …]` and `[:scope …]` paths classify the instance's params and scope, and `[:data …]` or bare paths classify its `:result` (e.g. `{:sensitive [[:params :token]]}`). A malformed declaration raises `:rf.error/mutation-bad-spec`.

The success arms run in a fixed order: `:patches`, `:populates`, `:removes`, then `:invalidates`. When one key is both patched and populated, the populate wins, and a key this mutation populated is not refetched by its own invalidation; a patched key can be.

Patches, populates and removes run after the server has accepted the write, so a bad target is skipped and the other targets still apply, unless it would write under a wrong identity:

- A target whose `{:from-db …}` reference resolves to `nil` is skipped. An `:invalidates` descriptor whose reference resolves to `nil` likewise invalidates nothing.
- A target that is not a map, has a non-keyword `:resource` or names an unregistered resource is skipped with `:rf.warning/mutation-target-skipped` in development builds.
- A target that would write under a wrong identity throws when the reply settles: params that are not portable EDN raise `:rf.error/mutation-invalid-target`, and a misspelt or non-EDN `:scope` raises `:rf.error/resource-invalid-scope` or `:rf.error/resource-non-edn-params`.

**Scope**:

A mutation's `:scope` is optional, unlike a resource's. It resolves from the payload `:scope`, then the spec `:scope`, then `:rf.scope/global`, and is the default scope for the success arms. Either `:scope` may be a concrete scope or a `{:from-db <id>}` reference, resolved against the executing handler's `app-db`; a reference that resolves to `nil` raises `:rf.error/resource-scope-unresolved-reference` before the write starts.

The resolved scope must match the scope of the resources the write changes. A write against user-, tenant- or locale-scoped entries that omits `:scope` invalidates the `:rf.scope/global` cache instead and leaves the scoped entries stale. No error is raised; development builds emit `:rf.warning/mutation-scope-mismatch` when the tags matched nothing in the resolved scope but do match entries in another. Declare the scope on the spec, pass it on [`[:rf.mutation/execute …]`](#rfmutationexecute-), or name it per descriptor in `:invalidates` ([The scope footgun](../resources/how-to/invalidate-after-a-mutation.md#the-scope-footgun-and-how-to-disarm-it)).

**Optimistic keys** (see [Invalidate after a mutation](../resources/how-to/invalidate-after-a-mutation.md) and [Mutations invalidate by tag](../resources/concepts.md#writes-invalidate-by-tag--causally)):

- `:optimistic` — `(fn [params] → {target patch-fn})`, applied before the request is sent. It has the shape of `:patches` without the `result` argument, since there is no reply yet: each `patch-fn` is `(fn [old-data] → new-data)`, and a `nil` patch-fn optimistically removes the entry.
- `:optimistic-tags` — `(fn [params] → [{:scope … :tags #{…} :patch patch-fn} …])`, the tag-addressed form: each descriptor patches every cached entry carrying its tags, for keeping other views consistent.
- `:on-conflict` — what to do when a rollback is contested because another write changed the entry after the optimistic apply. `:invalidate` (default) marks the entry stale so it refetches the server's value; `:force` restores the snapshot anyway and emits `:rf.warning/optimistic-force-clobber`. Any other value raises `:rf.error/mutation-bad-spec`.

An `:optimistic` target is checked before the request is sent. One that is not a map, has a non-keyword `:resource`, names an unregistered resource or has params that are not portable EDN raises `:rf.error/mutation-invalid-target`, and the write is not sent. A malformed `:optimistic-tags` descriptor (not a map, `:tags` not a collection, or no `:patch` fn) is dropped with `:rf.warning/optimistic-tags-descriptor-skipped` in development builds; the other descriptors and the write go ahead.

There is no `:rollback` key. The runtime snapshots each touched entry, with its `:revision`, into the `:rollback` slot of the instance row's `:patch-summary`, then commits, rolls back or reconciles when the write settles. Combining an optimistic plan with `:invalidate-timing :before-request` is rejected at registration with `:rf.error/mutation-optimistic-before-request`.

`:retry` is not a `reg-mutation` key. Retries are opt-in: put `:retry {…}` in the [managed-HTTP args](re-frame.http.md) the request fn returns, which the runtime passes to the transport unchanged. Reads work the same way (see [Retry](../async/http.md#retry-transport-retry-as-data)). Retrying stays explicit per request because re-sending a non-idempotent write after a slow reply writes it twice.

### Clearing a registration

- **Kind**: function (the facade's `rf/clear`)
- **Signature**:
  ```clojure
  (rf/clear :resource resource-id) → resource-id
  (rf/clear :mutation mutation-id) → mutation-id
  (rf/clear :resource-scope scope-id) → scope-id
  ```
- **Description**: Removes a registration, for hot reload or teardown. Each form returns the id, including when nothing is registered under it. This is registration lifecycle, not cache invalidation: to change cached data, dispatch `:rf.resource/invalidate-tags`, `:rf.resource/remove` or `:rf.resource/clear-scope`. There is no `clear-resource`, `clear-mutation` or `clear-resource-scope` function; [`clear`](re-frame.core.md#clear) takes the kind.
    - `:resource` also disposes the resource's runtime state in each affected frame. It releases owner indexes, cancels timers and host handles, aborts in-flight work where possible, suppresses late replies by generation, removes tag-index rows and emits a trace.
    - `:mutation` removes the registration only. To reset a mutation's runtime instance rows, dispatch [`[:rf.mutation/clear …]`](#rfmutationclear-).
    - `:resource-scope` removes the registrar entry only; a resolver holds no per-frame state.
- **Example**:
  ```clojure
  ;; deregister on hot reload or teardown
  (rf/clear :resource :feed/timeline)
  (rf/clear :mutation :article/save)
  (rf/clear :resource-scope :realworld/session)
  ```

## Named scope resolvers

A scope resolver computes a cache scope from `app-db`, for data that differs per user, tenant or locale. Register it once with `reg-resource-scope`, then reference it as `{:from-db <scope-id>}` wherever a scope is accepted: resource registration, route resources, event-side `ensure`, subscriptions, invalidation descriptors, and populate, patch and remove targets. `clear-scope` takes a concrete scope, which you get from the same resolver with [`resolve-resource-scope`](#resolve-resource-scope). A reference resolves at use time against the frame's `app-db`; a `nil` result fails closed at every site that needs a scope and never becomes a global read.

### `reg-resource-scope`

- **Kind**: macro (`rf/reg-resource-scope`); also a function, `re-frame.resources/reg-resource-scope`
- **Signature**:
  ```clojure
  (reg-resource-scope scope-id metadata resolve-fn) → scope-id   ;; one arity; :inputs is required
  ```
- **Description**: Registers a named scope resolver under `scope-id` and returns `scope-id`.
    - `metadata` holds the required `:inputs {name [:db <rf-path>]}` and an optional `:doc`. `[:db <rf-path>]` (a concrete `:rf/path`) is the only input source; `[:runtime …]` (route-derived scope) is reserved and rejected with `:rf.error/resource-scope-source-reserved`.
    - `resolve-fn` is `(fn [inputs ctx] → scope-or-nil)`. Its first argument is always the map of resolved inputs. `ctx` is reserved, and the runtime passes `nil`. The fn must be pure: no fetching, dispatching, state changes or reads of host state.
    - The returned scope must be concrete portable EDN. A misspelt `:rf.scope/*` keyword, `[:rf.scope/global]` or a `{:from-db …}` map raises `:rf.error/resource-invalid-scope`, and a non-EDN value raises `:rf.error/resource-non-edn-params`, wherever the resolver runs, including in `resolve-resource-scope`.
    - To read the whole db, declare it as an input on the root path: `{:inputs {:db [:db []]}}`. There is no bare-fn shorthand. The stored `:whole-db?` flag is derived from this declaration.
    - Missing `:inputs` (empty or `:doc`-only metadata), non-map metadata, a malformed `:inputs` descriptor, a `:resolve` key inside the metadata map, or a non-fn third argument raises `:rf.error/invalid-resource-scope-spec`.
    - Writes a `:resource-scope`-kind registrar entry holding the canonical spec and captured source coords.
- **Example**:
  ```clojure
  (rf/reg-resource-scope :realworld/session
    {:inputs {:username [:db [:auth :user :username]]}}
    (fn [{:keys [username]} _ctx]
      (when username [:rf.scope/session {:username username}])))

  ;; referenced from a resource / payload / route as {:from-db :realworld/session}
  ```

### `resolve-resource-scope`

- **Kind**: function
- **Signature**:
  ```clojure
  (resolve-resource-scope db scope-id) → scope or nil
  ```
- **Description**: Resolves the named resolver `scope-id` against a `db` value and returns the canonical concrete scope, or `nil`. Use it in a handler that needs the concrete scope. At logout, resolve the old scope from the handler's coeffect `db`, which still holds the logged-in state, and pass it to `[:rf.resource/clear-scope …]`.
    - A pure function over the resolver registry, not an effect. It has no app-state, dispatch or trace side effects: it does not emit `:rf.resource/scope-resolved`, which comes from the `{:from-db …}`, route-entry and mutation-settle resolution sites.
    - Throws `:rf.error/resource-scope-not-registered` when no resolver is registered under `scope-id`, so a misspelt reference never yields a silent `nil`.
    - Returns `nil` when the resolver returns `nil` for this db. That is the unresolved condition, and the caller decides what it means; it is never an implicit global.
- **Example**:
  ```clojure
  ;; the logout idiom: resolve the old scope from the coeffect db,
  ;; then clear that scope's whole cache so the next user can never read it
  (rf/reg-event :auth/logout
    (fn [{:keys [db]} _]
      (let [old (rf/resolve-resource-scope db :realworld/session)]   ;; nil when nobody was logged in
        {:db (dissoc db :auth)
         :fx (cond-> []
               old (conj [:dispatch [:rf.resource/clear-scope {:scope old :cause :logout}]]))})))
  ```

## Resource events (map payloads)

Resource events take a single map payload. The events that name a `:resource` validate it:

- An unregistered `:resource` raises `:rf.error/resource-not-registered`.
- Params that fail `:params-schema` raise `:rf.error/resource-invalid-params`.
- Scope resolution fails closed: a `{:from-db …}` reference that resolves to `nil` raises `:rf.error/resource-scope-unresolved-reference`, and one naming no registered resolver raises `:rf.error/resource-scope-not-registered` (see [Scope policy](#scope-policy)).
- Params and scope must be portable EDN: a fractional number, ratio, NaN, fn or other host object raises `:rf.error/resource-non-edn-params`. A misspelt `:rf.scope/*` keyword, the wrapped `[:rf.scope/global]` (write `:rf.scope/global`), or a `{:from-db …}` map where a concrete scope is required raises `:rf.error/resource-invalid-scope`.
- When the request is built, a request fn that returns `:request-id`, `:on-success` or `:on-failure` raises `:rf.error/resource-reserved-request-key`, a `:transport` other than `:rf.http/managed` raises `:rf.error/resource-unknown-transport`, and a missing `re-frame.http.managed` raises `:rf.error/http-artefact-missing`. Nothing is written to the cache.

### `[:rf.resource/ensure {…}]`

- **Kind**: event
- **Payload**: `{:resource …}` (required), plus optional `:params` (default `{}`), `:scope` (default: the registered policy; see [Scope policy](#scope-policy)), `:owner`, `:cause`, `:keep-previous?` and `:reply-to`.
- **Description**: Loads the resource instance for these params and scope, unless it is already loaded and fresh.
    - While the same scoped key is in flight, `ensure` joins that request: it attaches the owner, records the cause and emits a dedupe trace.
    - On an already-`:loaded` entry that is still fresh by policy, it does not fetch. It serves the cached value, attaches the owner and emits `:rf.resource/cache-hit`.
    - `:owner` adds to the entry's active owners, which keep it alive. `:cause` is recorded in the trace and history.
    - `:keep-previous? true` is for paging and filtering. While this key has no data of its own, its `:rf/resource` view-model also carries the most recently loaded data of the same resource and scope under other params (`:previous? true`, `:previous-data`), so the old page stays on screen while the new one loads. Nothing is copied into the new entry.
    - `:reply-to` is an optional data-only event vector. The accepted terminal reply is appended to it and dispatched once, immediately on a cache hit, and never for a stale reply. The reply is the managed-HTTP [reply map](re-frame.http.md#reply-shape) (`:status` `:ok`, `:error` or `:cancelled`, with `:value` or `:error`) plus `:resource`, `:params`, `:scope`, `:resource/key` and `:cache-hit?`. For an infinite feed, `:value` is the merged item list. An `ensure` that joins an in-flight load adds its target to that load. When a refetch, poll, focus scan or invalidation supersedes the load, the target moves to the new attempt, so exactly one reply still arrives. A target that is not a non-empty vector with a keyword head raises `:rf.error/reply-invalid-target`, and one carrying a fn or other host object raises `:rf.error/reply-non-data-target`, before anything is written.
    - See [Cause a fetch](../resources/concepts.md#cause-a-fetch).
- **Example**:
  ```clojure
  [:rf.resource/ensure
   {:resource :article/by-slug
    :scope    [:rf.scope/session {:user-id "u-42" :tenant-id "acme"}]
    :params   {:slug "welcome"}
    :owner    [:route :route/article nav-token]
    :cause    [:route-entry :route/article nav-token]}]
  ```

### `[:rf.resource/refetch {…}]`

- **Kind**: event
- **Payload**: `{:resource …}` (required), plus optional `:params` (default `{}`), `:scope` (default: the registered policy), `:owner`, `:cause` and `:reply-to`.
- **Description**: Forces a refresh. It always starts a new generation, even when a request is already in flight; the earlier request is marked superseded and aborted if possible, otherwise its reply is suppressed by work id and generation. A manual refresh usually passes a `:cause` and no `:owner`. `:reply-to` works as for `ensure`.
- **Example**:
  ```clojure
  ;; a "Refresh" button: a :cause and no :owner (the route keeps the entry alive)
  [:rf.resource/refetch
   {:resource :articles/list
    :params   {}
    :cause    [:manual :resources.app/refresh-articles]}]
  ```

### `[:rf.resource/invalidate-tags {…}]`

- **Kind**: event
- **Payload**: one of two shapes: scoped `{:scope :tags :cause?}`, or cross-scope `{:cross-scope? true :tags :cause}` with no `:scope`.
- **Description**: Marks every entry whose tags intersect `:tags` as stale. Entries with an active owner refetch now; the others stay stale and refetch the next time something ensures them.
    - `:tags` is a set or vector of tags. A lone tag vector such as `[:article slug]` is read as that one tag.
    - Invalidation is scoped by default. A scoped payload without `:scope` raises `:rf.error/resource-invalidate-scope-required`.
    - `:scope` is a concrete scope or a `{:from-db <id>}` reference, resolved against the handler's `app-db` coeffect as for `ensure`, so the handler does not need to resolve it first. A reference that resolves to `nil` raises `:rf.error/resource-scope-unresolved-reference`.
    - A cross-scope invalidation sets `:cross-scope? true` and carries no `:scope`. It ignores the scope filter and is visible in Xray. It must carry `:cause`, or it raises `:rf.error/resource-cross-scope-cause-required`; supplying `:scope` as well raises `:rf.error/resource-cross-scope-scope-conflict`.
    - A successful load replaces an entry's tags with the tags computed from the new data.
- **Example**:
  ```clojure
  ;; after a write settles, mark the acting viewer's reads with these tags stale;
  ;; the {:from-db …} reference resolves against the handler's db, as for ensure
  [:rf.resource/invalidate-tags
   {:scope {:from-db :realworld/session}
    :tags  #{[:article "welcome"]}
    :cause [:follow-author-detail-sync "welcome"]}]
  ```

### `[:rf.resource/release-owner {…}]`

- **Kind**: event
- **Payload**: `{:owner …}`
- **Description**: Releases an owner from every entry it holds; an owner that holds nothing is a no-op. In-flight work is aborted only when no remaining owner needs it. Every app-minted owner needs a matching release; an orphaned owner keeps its entries alive, and Xray flags it.
- **Example**:
  ```clojure
  ;; the matching release for an app-minted event owner
  [:rf.resource/release-owner
   {:owner [:article/preview-opened "welcome"]}]
  ```

### `[:rf.resource/clear-scope {…}]`

- **Kind**: event
- **Payload**: `{:scope :cause}`, where `:scope` is a concrete scope, never a `{:from-db <id>}` reference
- **Description**: Drops a whole scope's cache. Dispatch it on logout and on any account, tenant, permission, locale or impersonation change. It:
    - removes (or marks unusable) every entry in the scope
    - releases owners
    - aborts the scope's in-flight requests where possible
    - suppresses late replies by scope and generation
    - emits explanatory trace rows

    `:scope` must be concrete. `ensure` and `invalidate-tags` resolve a `{:from-db …}` reference against their own handler's db, but `clear-scope` is usually dispatched from a logout handler's `:fx` and so runs in the next event, where the resolver's inputs are already gone. Resolve the scope in the logout handler with [`resolve-resource-scope`](#resolve-resource-scope) and pass the result.

    A `{:from-db …}` map on the payload raises `:rf.error/resource-invalid-scope` (`:recovery :fix-scope`) before anything is cleared. It is rejected rather than ignored because a map is also a valid literal scope, which would match nothing and clear nothing.
- **Example**:
  ```clojure
  ;; logout / tenant switch: drop a whole scope's cache so the next
  ;; user can never read the last one's data
  [:rf.resource/clear-scope
   {:scope [:rf.scope/session {:user-id "u-42"}]
    :cause :logout}]
  ```

### `[:rf.resource/remove {…}]`

- **Kind**: event
- **Payload**: `{:resource …}` (required), plus optional `:params` (default `{}`) and `:scope` (default: the registered policy).
- **Description**: Removes one resource instance's cache entry. `:scope` resolves as for `ensure`. An in-flight request for the entry is aborted where possible, and its late reply is suppressed.
- **Example**:
  ```clojure
  ;; drop one cached instance, addressed by its scoped resource key
  [:rf.resource/remove
   {:resource :article/by-slug
    :scope    :rf.scope/global
    :params   {:slug "welcome"}}]
  ```

### `[:rf.resource/load-more {…}]`

- **Kind**: event (infinite resources only; see [Infinite resources](#infinite-resources))
- **Payload**: `{:resource …}` (required), plus optional `:params` (default `{}`), `:scope` (default: the registered policy) and `:cause`. It takes no `:owner`.
- **Description**: Loads the next page of an `:infinite` feed: the runtime computes the next page param from the last page with `:next-page-param`, fetches that page through the same managed transport and appends it to the feed's page vector. The loaded pages stay visible, and `:rf.resource/fetching-next?` is true until the page settles; [Infinite resources](#infinite-resources) says how `:status` and the `:fetching?` flags read meanwhile.
    - When there is no next page (`:next-page-param` returned `nil`), it is a no-op that emits a trace.
    - Before page 0 has loaded, or on a resource that is not `:infinite`, it is a no-op that emits a trace; load the first page with `ensure`.
    - A failed page keeps every loaded page and records the failure in `:rf.resource/page-error`. Dispatching `load-more` again retries the same page.
    - While any fetch of the feed is in flight, a page or a whole-feed refresh, another `load-more` dedupes and fetches nothing.
    - A supplied `:owner` is ignored with the warning `:rf.warning/resource-load-more-owner-ignored`. Whatever first loaded the feed, usually the route, already owns its one entry, and `load-more` never changes the owner set.
- **Example**:
  ```clojure
  ;; the "Load more" button: a :cause and no :owner
  [:rf.resource/load-more
   {:resource :feed/timeline
    :params   {:filter :all}
    :cause    [:user :feed/load-more]}]
  ```

### `[:rf.resource/window-focused]` / `[:rf.resource/network-reconnected]`

- **Kind**: event (no payload)
- **Description**: Scans the frame's stale entries that have an active owner and refetches them by policy. The frame's [`:revalidate-on`](#revalidation-is-a-frame-property) listeners dispatch these; application code must not dispatch them. The refetch carries cause `:focus` (window-focused) or `:reconnect` (network-reconnected) and no owner, so it keeps nothing alive. Generation and stale-reply suppression protect against late replies.
- **Example**:
  ```clojure
  ;; no payload; dispatched by the frame's :revalidate-on listeners, never by app code
  [:rf.resource/window-focused]
  [:rf.resource/network-reconnected]
  ```

## Resource subscriptions (passive)

A resource subscription reads the cache and never fetches. It resolves the scope as described in [Scope policy](#scope-policy) and raises `:rf.error/resource-sub-unresolved-scope` rather than reading global data or returning `:idle`.

It validates its payload as the events do, so an unregistered `:resource` (`:rf.error/resource-not-registered`), params that fail the schema or are not portable EDN (`:rf.error/resource-invalid-params`, `:rf.error/resource-non-edn-params`), an invalid scope (`:rf.error/resource-invalid-scope`) or an unregistered resolver (`:rf.error/resource-scope-not-registered`) throws when the view reads it. A valid key that nothing has ensured reads `:status :idle`.

```clojure
[:rf/resource         {:resource … :scope … :params …}]   ;; the full view-model
[:rf.resource/data          {…}]   [:rf.resource/status        {…}]
[:rf.resource/loading?      {…}]   [:rf.resource/fetching?     {…}]
[:rf.resource/stale?        {…}]   [:rf.resource/error         {…}]
[:rf.resource/refresh-error {…}]   [:rf.resource/has-data?     {…}]
[:rf.resource/previous-data {…}]
```

Each focused subscription returns the `:rf/resource` view-model key of the same name (`:rf.resource/data` returns `:data`, `:rf.resource/previous-data` returns `:previous-data`, …), so a view that reads one re-renders only when that value changes.

Read them with the ordinary `subscribe`; there is no separate read function. `subscribe`'s `{:frame <target>}` option reads from an explicit frame.

```clojure
@(rf/subscribe [:rf/resource {:resource :article/by-slug :params {:slug "hello"}}])
;; => {:status :loading …}  …then  {:status :loaded :data {…} …}
```

The `:rf/resource` view-model holds facts plus derived booleans:

```clojure
{:status        :idle | :loading | :fetching | :loaded | :error
 :data          <last-known-good-or-nil>
 :error         <first-load-error-or-nil>          ;; failure map {:kind :rf.http/… …}
 :refresh-error <background-refresh-error-or-nil>  ;; failure map {:kind :rf.http/… …}
 :loading?      <bool>   ;; first load, no usable data
 :fetching?     <bool>   ;; refresh in flight, prior data visible
 :stale?        <bool>   ;; freshness — orthogonal to load status
 :has-data?     <bool>
 :previous?     <bool>}  ;; :keep-previous? projection — when true, also
                         ;; :previous-key + :previous-data (the prior key's data)
```

- `:idle` means nothing has ensured this key, or its first load was aborted, for example because its last owner was released while the request was in flight.
- `:loading` is a first load with no usable data.
- `:fetching` is a refresh in flight while prior data stays visible.
- `:error` is a failed first load with no usable data.
- A failed background refresh stays `:loaded`, keeps the prior `:data` and records `:refresh-error`.
- `:error` and `:refresh-error` hold the managed-HTTP failure map, so a view branches on its `:kind` as in [Failure kinds](re-frame.http.md#failure-kinds-closed-set). An aborted request settles as a cancellation and records neither.
- An `:error` entry is never fresh, so the next `ensure` retries the load; `refetch` retries at once.

`:stale?`, `:loading?`, `:fetching?` and `:has-data?` are derived when the subscription runs and are never stored. See [Project: five statuses](../resources/concepts.md#what-a-view-sees-five-statuses) in the guide.

## Mutation events (map payloads)

### `[:rf.mutation/execute {…}]`

- **Kind**: event
- **Payload**: `{:mutation …}` (required), plus optional `:params` (default `{}`), `:instance`, `:scope`, `:cause`, `:reply-to` and `:optimistic?`.
- **Description**: Runs a mutation.
    - `:instance` is the instance id that keys all runtime state for this run, so two concurrent submissions keep distinct rows. Supply your own, such as `:form/save-1` or `[:article/save slug]`, when a view reads the write's state through `[:rf/mutation {:instance …}]`. When omitted the runtime generates one, which you see only in the `:reply-to` reply and in traces.
    - `:scope` overrides the spec's `:scope` and takes the same forms; see **Scope** in [The mutation spec](#the-mutation-spec).
    - On success the runtime patches, populates and removes resource entries, then invalidates tags, at the time `:invalidate-timing` sets.
    - `:reply-to` is an optional data-only event vector. When the write settles, the reply is appended to it and dispatched once, after the cache changes and the instance row have been written. It is the managed-HTTP [reply map](re-frame.http.md#reply-shape) (`:status` `:ok`, `:error` or `:cancelled`) plus `:mutation`, `:params`, `:instance`, `:scope`, `:affected-keys` and `:cause` (`[:mutation <mutation-id> <instance>]`, not the payload's `:cause`).
    - `:optimistic? false` runs a registered optimistic plan pessimistically for this call.
    - Before anything is written or sent: an unregistered `:mutation` raises `:rf.error/mutation-not-registered`. Params that fail `:params-schema` raise `:rf.error/mutation-invalid-params`, and params that are not portable EDN (a fractional number, a fn) raise `:rf.error/resource-non-edn-params`. An `:instance` that is not portable EDN raises `:rf.error/mutation-non-serializable-instance-id`. A misspelt `:rf.scope/*` scope raises `:rf.error/resource-invalid-scope`, and a `{:from-db …}` naming no registered resolver raises `:rf.error/resource-scope-not-registered`. A malformed `:reply-to` raises `:rf.error/reply-invalid-target` or `:rf.error/reply-non-data-target`. Building the request raises `:rf.error/resource-reserved-request-key`, `:rf.error/resource-unknown-transport` or `:rf.error/http-artefact-missing`, as for a resource load.
    - A superseded reply (after a re-execute under the same instance, or an `:rf.mutation/clear`) never overwrites the newer state; work id and generation suppress it.
    - See [Fire the write, watch the instance](../resources/how-to/invalidate-after-a-mutation.md#3-fire-the-write-watch-the-instance).
- **Example**:
  ```clojure
  [:rf.mutation/execute
   {:mutation :article/save
    :params   article
    :instance :form/save-1
    :scope    [:rf.scope/session {:user-id "u-42"}]
    :cause    [:form-submit :article/save]}]
  ```

### `[:rf.mutation/clear {…}]`

- **Kind**: event
- **Payload**: `{:instance …}` to clear one instance, or `{:mutation …}` to clear every instance of a mutation id
- **Description**: Resets mutation runtime state. It clears the addressed rows, aborts their in-flight work where possible and drops their work-ledger rows; the `:rf.mutation/cleared` trace names the aborted work. A pending optimistic apply is rolled back first: each touched entry is restored to its snapshot, marked stale and refetched if it has an owner, and `:rf.mutation/optimistic-rolled-back` is traced. A payload with neither key clears nothing. To remove the registration instead, call `(rf/clear :mutation mutation-id)`.
- **Example**:
  ```clojure
  ;; reset one runtime instance's row (e.g. in a completion continuation)
  [:rf.mutation/clear {:instance :form/save-1}]
  ```

## Mutation subscriptions (passive)

A mutation subscription reads one instance's row, keyed by instance id, and never runs a write.

```clojure
[:rf/mutation    {:instance :form/save-1}]   ;; {:status :result :error :affected-keys
                                                   ;;  :pending? :success? :error? :settled? :optimistic?}
[:rf.mutation/status   {:instance :form/save-1}]
[:rf.mutation/pending? {:instance :form/save-1}]
[:rf.mutation/result   {:instance :form/save-1}]
[:rf.mutation/error    {:instance :form/save-1}]
```

Each focused `:rf.mutation/*` subscription returns the `:rf/mutation` view-model key of the same name.

```clojure
;; a form reads its own submission's state, keyed by instance
@(rf/subscribe [:rf/mutation {:instance :form/save-1}])
;; => {:status :idle …}  …then  {:pending? true …}  …then  {:success? true …}
```

- `:status` is `:idle`, `:pending`, `:success` or `:error`. An instance reads as `:idle` until its first `:rf.mutation/execute`, and `:settled?` is true at `:success` or `:error`.
- `:result` is the decoded reply (the reply map's `:value`); `:error` is the managed-HTTP failure map.
- `:optimistic?` (derived) is true while an optimistic apply is showing: applied but not yet settled.
- `:affected-keys` holds the scoped keys the settle touched.
- There is no `:refresh-error` for mutations, because a write has no last-known-good value to keep.

## Revalidation is a frame property

A frame can refetch its stale data when the window regains focus or the network reconnects; [Owners, causes, refetch rules](../resources/concepts.md#owners-causes-refetch-rules) covers when to turn it on. Declare which signals it listens for with the `:revalidate-on` frame-config key, the same way URL ownership is declared:

```clojure
(rf/make-frame {:id :app :url-bound? true :revalidate-on #{:focus :reconnect}})

[rf/frame-root {:id app-frame :url-bound? true :revalidate-on #{:focus :reconnect}}
 [root-view]]
```

- `:revalidate-on` is optional and takes a set drawn from `#{:focus :reconnect}`.
    - `:focus` listens for `focus` on `window` and for `visibilitychange` to visible on `document` (its only valid target), and dispatches `[:rf.resource/window-focused]`. One setting covers both.
    - `:reconnect` listens for `online` on `window` and dispatches `[:rf.resource/network-reconnected]`.
- An absent key, or `#{}`, installs nothing.
- On each signal the runtime refetches the frame's stale entries that have an active owner. A stale entry with no active owner is left alone, since revalidation keeps nothing alive. Subscriptions never trigger this; only the two events do.
- The frame lifecycle manages the listeners. Creating the frame installs the declared set once the frame is live; re-registering it detaches whatever it had and attaches exactly the declared set, so listeners never stack and dropping the key removes them; destroying it removes them. There is no `install-revalidation-listeners!` / `remove-revalidation-listeners!`.
- The listeners exist only in CLJS. On the JVM and under SSR the key installs nothing, and nothing throws. Declaring `:revalidate-on` without `day8/re-frame2-resources` on the classpath raises `:rf.error/resources-artefact-missing` when the frame is registered.

## Polling

`:poll-interval-ms` refetches a resource every N milliseconds while its entry has at least one active owner and the document is visible, with no fetch call in any view. A route, machine or app-minted event owner keeps the poll running, and it stops as soon as the last owner releases.

```clojure
(rf/reg-resource :notifications/unread-count
  {:scope            {:from-db :app/session}
   :params-schema    [:map]
   :poll-interval-ms 15000           ;; refresh every 15s while owned and the tab is visible
   :tags    (fn [_ _] #{[:notifications]})}
  (fn [_ _ctx] {:request {:method :get :url "/notifications/unread"} :decode :json}))
```

- A positive integer turns polling on; absent or non-positive means no polling.
- Each tick refetches on the interval, whether or not the entry is stale. `:stale-after-ms` still governs focus and route-entry refetches. Structural sharing keeps views from re-rendering when an unchanged response comes back.
- A poll refetch has cause `:poll` and no owner: it keeps nothing alive and does not extend GC. Generation and stale-reply suppression apply as for any refetch.
- Ticks pause while the tab is hidden and resume when it returns; on a frame declaring `:revalidate-on #{:focus}` the return also triggers the focus refetch. There is no option to keep polling while hidden.
- A tick that finds a refetch already in flight is skipped, so a slow endpoint never gets overlapping requests, and focus and poll never fetch twice.
- A failed poll keeps the prior `:data` and records `:refresh-error`, and the next tick still fires.

An entry with no owner never polls. When no route or machine owns it, ensure it from an event with an owner named for that event (e.g. `[:dashboard/opened …]`), and dispatch the matching `[:rf.resource/release-owner {…}]` when the screen closes. See [Owners, causes, refetch rules](../resources/concepts.md#owners-and-causes-and-the-refetch-rules) in the guide.

## Infinite resources

An infinite resource is a load-more feed: the user sees page 1, then pages 1 and 2, then 1 to 3, rendered as one growing list. Register it with `:infinite true` and a pure `:next-page-param`, which derives the next page's param from the last page's data. Numbered or cursor pagination (`:keep-previous?`, one entry per page) is the other approach; choose per feed. [Paginate a feed](../resources/how-to/paginate-a-feed.md) walks through both.

```clojure
(rf/reg-resource :feed/timeline
  {:doc "Infinite home timeline (load-more)."
   :infinite         true
   :params-schema    [:map [:filter :keyword]]   ;; the feed's identity (filter/sort), not the page cursor
   :scope            {:from-db :app/session}
   :sensitive        [[:data :author-email]]      ;; per-page classification (matches the field on every page)
   :next-page-param  (fn [last-page _all-pages]    ;; required; nil means no more pages
                       (get-in last-page [:page-info :next-cursor]))
   :page->items      :items                        ;; required when a page is not a vector
   :tags             (fn [{:keys [filter]} _] #{[:feed filter]})}

  ;; request fn (third argument); the reserved ctx carries the page param
  (fn [{:keys [filter]} {:rf.resource/keys [page-param]}]
    {:request {:method :get :url "/api/timeline"
               :params (cond-> {:filter filter :limit 20} page-param (assoc :cursor page-param))}
     :decode  :app/timeline-page}))            ;; validates one page
```

- `:infinite true` makes `:next-page-param` required; omitting it raises `:rf.error/infinite-missing-next-page-param`. It is a pure `(fn [last-page all-pages] → next-param-or-nil)`, and `nil` is the one way to say there are no more pages, exposed as the derived `:has-next-page?`.
- A feed is one scoped entry. Its pages accumulate as an ordered vector inside that one `:rf.runtime/resources` entry, not as one entry per page and not in `app-db`, so the feed has one owner set, one freshness clock, one GC clock, one SSR-restore unit and one Xray row. The page param is internal sequencing state and not part of the cache key; changing the identity params gives a different feed.
- Validate pages with the request's `:decode`: a Malli schema there validates one page at a time, on page 0, each load-more and every refetch leg. Classify per-page fields with `:sensitive` / `:large`: a path `[:data :field]` matches `[:data <page-index> :field]` on every page, so the field is redacted on all of them. `:data-schema` does not apply to the accumulated vector.
- Other infinite-only keys:
    - `:prev-page-param` — `(fn [first-page all-pages] → param-or-nil)`, the mirror of `:next-page-param`, which feeds `:rf.resource/has-prev-page?`. There is no load-previous event; pages are only appended.
    - `:initial-page-param` — the first page's param. Default `nil`.
    - `:page->items` — a keyword or `(fn [page] → items)` that extracts a page's items; a vector page is its own items. A non-vector page with no `:page->items` makes `:rf.resource/items`, `:rf.resource/infinite-state` and an `ensure`'s `:reply-to` raise `:rf.error/infinite-missing-page-accessor`.
    - `:refetch` — which pages a refetch refreshes. By default only page 0 is refetched and replaced in place; the other loaded pages stay as they are. `{:refetch-all-pages? true}` refreshes every loaded page in order, and `{:refetch-window n}` refreshes the first `n`. Pages are replaced in place and the feed never shrinks.
    - A `:prev-page-param` that is not a fn, a `:page->items` that is neither a keyword nor a fn, or a `:refetch` that is not a map, has a non-boolean `:refetch-all-pages?` or a non-integer `:refetch-window` raises `:rf.error/resource-bad-spec`.

A view reads the merged list and dispatches [`[:rf.resource/load-more {…}]`](#rfresourceload-more-) for the next page:

```clojure
[:rf.resource/items          {:resource :feed/timeline :scope … :params …}]   ;; merged flat list, the main read
[:rf.resource/pages          {…}]   ;; raw page boundaries
[:rf.resource/has-next-page? {…}]   [:rf.resource/fetching-next? {…}]
[:rf.resource/has-prev-page? {…}]   ;; mirror of has-next-page? (there is no prepend event)
[:rf.resource/page-count     {…}]   [:rf.resource/page-error     {…}]
[:rf.resource/infinite-state {…}]   ;; combined view-model (the feed analogue of :rf/resource)
```

`:rf.resource/items`, `:rf.resource/pages` and `:rf.resource/infinite-state` are memoised framework subscriptions. `:rf.resource/ensure`, and a route entry, load page 0 only. A mutation that changes an item inside a feed invalidates the whole feed; patching one item in place inside a feed's pages is not supported.

`:rf.resource/infinite-state` returns `{:status :items :pages :page-count :has-next-page? :has-prev-page? :loading? :fetching? :fetching-next? :stale? :has-data? :error :refresh-error :page-error}`.

During a load-more the feed's `:status` is `:fetching`, and the `:rf/resource` view-model and `:rf.resource/fetching?` read `:fetching?` true, as for a whole-feed refresh. `:rf.resource/infinite-state` tells the two apart: it reports a load-more as `:fetching-next?` true and keeps its own `:fetching?` for a whole-feed refresh, so it reads `false` while a load-more is in flight.

A feed has three error channels:

- `:error` — page 0 failed with no pages loaded.
- `:refresh-error` — a refetch's page 0 failed; the loaded pages are kept.
- `:page-error` — a later page (a load-more or a multi-page refetch) failed; the pages are kept, and the next successful page clears it.

A feed's `:tags` fn receives the whole page vector as `data`. For a resource that is not `:infinite`, or a feed with no pages yet, the feed subscriptions read `[]`, `0`, `false` or `nil` (`:rf.resource/page-error`).

## Cache home

Resource and mutation runtime state lives in the runtime-db partition (`:rf.db/runtime`), not in `app-db`:

- the resource cache, only at `:rf.runtime/resources`
- the frame work ledger, at `:rf.runtime/work-ledger`
- mutation instance rows, at `:rf.runtime/mutations`

All three are reserved runtime-db keys: framework-owned, isolated per frame and allocated lazily. App code reads them through the subscriptions and the functions below and never edits them by hand.

Cache entries (durable facts) and work-ledger attempts (in-flight records) are kept separately. Host handles (AbortControllers, timers, promises) live in side tables and are never serialized. Cancellation is best-effort; stale-reply suppression by work id and generation always applies. See [The cache you don't own](../resources/concepts.md#the-cache-you-dont-own) in the guide.

## Framework integration

Not for application code — used by adapters, tools and the test harness.

The reads here return one-shot, non-reactive snapshots for Xray, unit tests and SSR serialization. They do not re-render on change; views read the same state through the [subscriptions](#resource-subscriptions-passive). There are no dedicated accessors (`resource-meta`, `mutation-meta`, `resource-ids`, `mutation-ids`, `scope-resolver-meta`, `scope-resolver-ids`, or a bundled `resources` / `mutations` read): registrations are read with the generic registrar functions, live state with `resource-state` / `mutation-state` or at the reserved runtime-db paths.

### Reading registrations

`rf/registrations` lists the ids registered under a kind, and `rf/handler-meta` plus the kind's inner key (`:rf/resource`, `:rf/mutation`, `:rf/resource-scope`) returns one registered spec, or `nil`. These reads take no frame and need no artefact. Source coords are on the enclosing `handler-meta` map, not in the projected spec.

```clojure
(keys (rf/registrations {:source :store :kind :resource}))        ;; => (:article/by-slug :feed/timeline)
(keys (rf/registrations {:source :store :kind :mutation}))        ;; => (:article/save)
(keys (rf/registrations {:source :store :kind :resource-scope}))  ;; => (:realworld/session)

(:rf/resource (rf/handler-meta {:source :store :kind :resource
                                :id     :article/by-slug}))
;; => {:scope :rf.scope/global :params-schema [...] :request #fn ... :doc "…"}

(:rf/mutation (rf/handler-meta {:source :store :kind :mutation
                                :id     :article/save}))
;; => {:request #fn ... :params-schema :app/article :invalidates #fn ... :scope :rf.scope/global …}

(:rf/resource-scope (rf/handler-meta {:source :store :kind :resource-scope
                                      :id     :realworld/session}))
;; => {:inputs {:username [:db [:auth :user :username]]} :resolve #fn :whole-db? false :doc nil}
```

- `:rf/resource` returns the metadata map as registered, plus `:request` (the third-argument fn): every key the registration supplied, `:infinite` and its keys, `:timeout-ms` and the classification keys included, and no key it left out. `:gc-after-ms` is always present, normalized (absent → `300000`).
- `:rf/mutation` returns the metadata map as registered, plus `:request`; an omitted key is absent, not defaulted.
- `:rf/resource-scope` returns the resolver's canonical spec: `:inputs`, `:resolve`, `:whole-db?` and `:doc`. `:whole-db?` is derived, not authored: true when some declared input targets the root path (`{:inputs {:db [:db []]}}`).

### `resource-state`

- **Kind**: function
- **Signature**:
  ```clojure
  (resource-state {:resource … :scope … :params … :frame …}) → entry or nil
  ```
- **Description**: Returns one resource instance's durable runtime entry at an explicit frame, or `nil` when no entry exists. The scoped key resolves as a subscription's does, so a `{:from-db <id>}` scope resolves against the frame's `app-db`.
    - An absent or `nil` `:frame` raises `:rf.error/no-frame-context`. There is no fallback to `:rf/default`; returning `nil` would be indistinguishable from an absent entry.
    - An explicit but unknown or destroyed `:frame` reads as `nil`. With a `{:from-db …}` scope, the scope resolves against that frame's `app-db`, which reads as `nil`, so a resolver that returns `nil` for it raises `:rf.error/resource-sub-unresolved-scope`.
    - `:frame` is a frame id or a live frame value.
    - An invalid key raises the same errors as a subscription (see [Resource subscriptions](#resource-subscriptions-passive)).
- **Example**:
  ```clojure
  ;; the live durable entry for one scoped key, at an explicit frame
  (rf/resource-state {:resource :article/by-slug
                      :scope    :rf.scope/global
                      :params   {:slug "welcome"}
                      :frame    :rf/default})
  ;; => entry map, or nil when no entry exists
  ```

### `mutation-state`

- **Kind**: function
- **Signature**:
  ```clojure
  (mutation-state {:instance … :frame …}) → row or nil
  ```
- **Description**: Returns one mutation instance's durable runtime row (`{:status :result :error …}`) at an explicit frame, or `nil`.
    - An absent or `nil` `:frame` raises `:rf.error/no-frame-context`, as for `resource-state`.
    - An explicit but unknown or destroyed `:frame` reads as `nil`.
- **Example**:
  ```clojure
  ;; one mutation instance's runtime row, at an explicit frame
  (rf/mutation-state {:instance :form/save-1 :frame :rf/default})
  ;; => {:status … :result … :error …}, or nil
  ```

### Enumerating the whole live table

The registry, the whole live table and one entry are three different reads. The registry says what is registered and takes no frame. The live tables are runtime-db state, read at an explicit frame from the reserved paths in [Cache home](#cache-home). `resource-state` and `mutation-state` narrow to one target.

```clojure
;; 1. REGISTRY — every registered id, no frame.
(keys (rf/registrations {:source :store :kind :resource}))  ;; => (:article/by-slug :feed/timeline)
(keys (rf/registrations {:source :store :kind :mutation}))  ;; => (:article/save)

;; 2. WHOLE LIVE TABLE — the reserved runtime-db path off the frame-state projection.
(get-in (rf/frame-state-value :app/main) [:rf.db/runtime :rf.runtime/resources :entries])
;; => {<key-id> {:resource/id :article/by-slug
;;               :resource/key [:rf.scope/global :article/by-slug {:slug "welcome"}]
;;               :data … :error … :generation … :current-work …}
;;     …}
(get-in (rf/frame-state-value :app/main) [:rf.db/runtime :rf.runtime/mutations])
;; => {<key-id> {:mutation/id :article/save :instance/id :form/save-1
;;                 :status … :result … :error … :generation …}
;;     …}

;; 3. ONE ENTRY / ONE INSTANCE — the narrowed reads documented above.
(rf/resource-state {:resource :article/by-slug :scope :rf.scope/global
                    :params {:slug "welcome"} :frame :app/main})
(rf/mutation-state {:instance :form/save-1 :frame :app/main})
```

Read each table by its own keys. `:entries` is keyed by each entry's `key-id`, a string encoding of the key in canonical EDN (CEDN-1) that keeps a list and a vector of the same values distinct; the readable `[scope resource-id params]` tuple is on the row as `:resource/key`, and re-keying the table by it can collapse distinct entries. `:rf.runtime/mutations` is keyed by the CEDN-1 byte `key-id` of each mutation instance id (the row carries its own `:instance/id`), never by mutation id, so concurrent submissions of the same mutation stay distinct; re-keying by `:mutation/id` collapses them.

Both subtrees are allocated lazily: `:rf.runtime/resources` is absent until the first resource write, and `:rf.runtime/mutations` is absent until the first `:rf.mutation/execute`, so either read can return `nil` at a live frame. `rf/frame-state-value` also returns `nil` for an unknown or destroyed frame, so a `nil` here means "not allocated" only at a frame you know is live; otherwise check `(rf/frame-state-value :app/main)` itself first.

### Xray

Xray's Resources panel shows the same shapes, plus the route/resource graph, the work-ledger table and the scope audit, a standing list of every `:rf.scope/global` resource. Its projections prefer summaries to raw values, and params and scopes get the same privacy and size elision as data. Xray has no read-only resource accessors (no `list-resources` / `get-resource-state` family); an out-of-process reader uses `re-frame2-pair` against the framework's registry, runtime-db and trace surfaces.

### Internal events and trace ops

These ids appear in traces and in Xray. Application code must not dispatch them.

- `:rf.resource.internal/succeeded`, `…/failed`, `…/page-succeeded`, `…/page-failed`, `…/stale-fired`, `…/gc-fired`, `…/poll-fired`, `…/stale-suppressed` and `…/refetch-page` are the resource runtime's replies and timer ticks. The replies carry `:work/id`, `:resource/key`, `:scope`, `:generation` and `:rf.frame/id`, and their handlers check frame, work id and generation before writing, which is where stale replies are suppressed. The timer ticks carry the `:resource/key` (a poll tick adds `:hidden?`) and re-check the entry when they fire.
    - `…/stale-fired` is the stale-timer re-check tick. It arms the stale transition and does not fetch.
    - `…/poll-fired` is the poll-timer re-check tick. It refetches an actively owned entry.
    - `…/page-succeeded` and `…/page-failed` are the infinite-feed page replies; `…/refetch-page` is one leg of a multi-page refetch.
    - An abort arrives on `…/failed` and settles as a cancellation, not an error (the `:rf.http/aborted` branch). There is no separate aborted reply.
- `:rf.resource.internal/adopt-owner` attaches an owner to an existing entry without fetching, and `…/release-owner-identities` releases an owner from a subset of the entries it holds. The route planner uses both to hand owners over.
- `:rf.resource/cache-hit` is the trace op for a fresh `ensure` served from cache with no fetch. For a blocking route resource it also releases the blocking slot at once, with no `:fetching` transition. `:rf.resource/stale-fired` is the trace op for a stale-timer tick. Neither is a `:status` value.
- `:rf.mutation.internal/succeeded` and `…/failed` are the mutation replies. The `:rf.mutation/*` trace family is `started`, `succeeded`, `failed`, `cleared`, `replied` and `stale-suppressed`, plus the optimistic rows `optimistic-applied`, `optimistic-reconciled` and `optimistic-rolled-back`. Both mutation families carry the instance id.

## See also

- [Glossary](../resources/glossary.md) — the resources and server-state vocabulary.
- [Testing resources](../resources/testing.md) — reads, scope resolvers and mutations under test.
- [Errors and warnings](../resources/errors-and-warnings.md) — the resources error and warning ids, grouped by when they fire, with each one's cause and fix.
- [Migration: re-frame-query → resources](../../migration/from-re-frame-v1/re-frame-query-to-resources.md) — moving off `shipclojure/re-frame-query` or a hand-rolled Pattern-RemoteData cache.
- [Managed HTTP](re-frame.http.md) — the `:rf.http/managed` transport and the `:rf.http/*` failure taxonomy.
- [re-frame.routing](re-frame.routing.md#route-resources) — the `:resources` route option and its entry keys.
- [re-frame.ssr](re-frame.ssr.md) — the hydration install path.
